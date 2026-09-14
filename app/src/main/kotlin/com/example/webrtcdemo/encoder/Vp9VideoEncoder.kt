package com.example.webrtcdemo.encoder

import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.nativebridge.NativeVp9Encoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import kotlin.math.max

// ============================================================================
// 自研 VP9 编码器（doc/14 §5.4，A1：Java 接口 + 自有 JNI → libvpx）
// ----------------------------------------------------------------------------
// 调用链（§5.2 权威）：
//   libwebrtc 编码线程 → VideoEncoderWrapper(JNI) → Java_VideoEncoder_encode
//     → Vp9VideoEncoder.encode() → NativeVp9Encoder.nativeEncode() → libvpx VP9
//     → nativeCopyEncodedFrame() → EncodedImage → callback.onEncodedFrame()
//
// 线程与阻塞约束（§5.4 硬性）：所有方法都在 **libwebrtc 编码线程**被调用：
//   - 不得做文件/网络 I/O、不得等待主线程、不得加长锁；
//   - encode() 是同步调用；实测单帧 > 33 ms 必须记 WARN（§5.5 cpu-used 调优依据）；
//   - setRateAllocation 与 release 可能来自不同线程 → @Volatile handle + 单例锁。
//
// 空间层固定 1、时序层 3（L1T3，§5.6 冻结）：Java 路径无法协商 SVC，禁止改成 >1。
// ============================================================================

/**
 * 编码器目标码率发布总线（§7.4「编码目标码率」数据源）。
 *
 * 由 [Vp9VideoEncoder.setRateAllocation] 直接发布（本地直通，不依赖 stats）；
 * UI 侧观察 [bitrate] 即可。放在本文件内以保持 §2.1 的冻结文件清单不变。
 */
object EncoderRateBus {

    private val _bitrateBps = MutableStateFlow(0)

    /** 当前编码器目标码率（bps）。 */
    val bitrateBps: StateFlow<Int> = _bitrateBps.asStateFlow()

    private val _framerate = MutableStateFlow(0)

    /** 当前编码器帧率。 */
    val framerate: StateFlow<Int> = _framerate.asStateFlow()

    /** 发布一次 SetRates 结果（由编码线程调用，StateFlow 线程安全）。 */
    fun publish(totalBps: Int, framerateFps: Int) {
        _bitrateBps.value = totalBps
        _framerate.value = framerateFps
    }
}

/**
 * 自研 VP9 编码器（Java 侧实现 [VideoEncoder]，内部走 [NativeVp9Encoder]）。
 */
class Vp9VideoEncoder : VideoEncoder {

    companion object {
        /** 实现名（§6.6 冻结，verifier 核对 `outbound-rtp.encoderImplementation`）。 */
        const val IMPL_NAME = "SelfVp9Libvpx"

        private const val TAG = "encoder"

        /** 空间层固定 1（§5.6：禁止 >1）。 */
        private const val SPATIAL_LAYERS = 1

        /** 时序层 3（L1T3，§5.6）。 */
        private const val TEMPORAL_LAYERS = 3

        /** 编码结果缓冲初值：max(512 KiB, startBitrateBps/8*2)。 */
        private const val MIN_DST_BYTES = 512 * 1024

        /** 编码结果缓冲上限（§6.7 冻结 8 MiB）。 */
        private const val MAX_DST_BYTES = 8 * 1024 * 1024

        /** 单帧编码耗时告警阈值（§5.5）。 */
        private const val SLOW_FRAME_WARN_MS = 33L
    }

    /** native 句柄（0 = 无效）；setRateAllocation/release 可能来自不同线程。 */
    @Volatile
    private var handle: Long = 0L

    /** 编码结果回调（由 SDK 注入）。 */
    @Volatile
    private var callback: VideoEncoder.Callback? = null

    /** 编码结果直接缓冲（只扩容不缩容）。 */
    private var dst: ByteBuffer = ByteBuffer.allocateDirect(MIN_DST_BYTES)

    /** 帧元数据：`[w, h, isKeyFrame, spatialIndex, temporalIndex, qp]`。 */
    private val meta = IntArray(6)

    private val lock = Any()

    private var released = false

    /**
     * 初始化编码器。
     *
     * @param settings SDK 下发的设置（宽高/起始码率 kbps/帧率）。
     * @param callback 编码结果回调。
     * @return [VideoCodecStatus.OK] 或 [VideoCodecStatus.ERROR]。
     */
    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        this.callback = callback
        val newHandle = NativeVp9Encoder.nativeCreate()
        if (newHandle == 0L) {
            AppLog.e(TAG, "encoder_init_failed", mapOf("stage" to "nativeCreate"))
            return VideoCodecStatus.ERROR
        }
        val startBitrateBps = settings.startBitrate * 1000
        val rc = NativeVp9Encoder.nativeInit(
            newHandle,
            settings.width,
            settings.height,
            startBitrateBps,
            0, // max 码率由 SetRates 决定（§5.4）
            settings.maxFramerate,
            SPATIAL_LAYERS,
            TEMPORAL_LAYERS,
        )
        if (rc != NativeVp9Encoder.STATUS_OK) {
            NativeVp9Encoder.nativeRelease(newHandle)
            AppLog.e(TAG, "encoder_init_failed", mapOf("stage" to "nativeInit", "rc" to rc.toString()))
            return statusOf(rc)
        }
        handle = newHandle
        dst = ByteBuffer.allocateDirect(max(MIN_DST_BYTES, startBitrateBps / 8 * 2).coerceAtMost(MAX_DST_BYTES))
        released = false
        AppLog.i(
            TAG,
            "encoder_init",
            mapOf(
                "impl" to IMPL_NAME,
                "w" to settings.width.toString(),
                "h" to settings.height.toString(),
                "s" to SPATIAL_LAYERS.toString(),
                "t" to TEMPORAL_LAYERS.toString(),
                "cpu" to (settings.numberOfCores).toString(),
            )
        )
        return VideoCodecStatus.OK
    }

    /**
     * 编码一帧。
     *
     * @param frame SDK 采集/处理的视频帧。
     * @param info 帧类型（是否关键帧）。
     * @return [VideoCodecStatus.OK] / [VideoCodecStatus.NO_OUTPUT] / [VideoCodecStatus.ERROR]。
     */
    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val currentHandle = handle
        if (currentHandle == 0L) return VideoCodecStatus.UNINITIALIZED
        val cb = callback ?: return VideoCodecStatus.ERROR

        val source = frame.buffer
        // 非 I420 时在编码线程转换；转换产生的 buffer 由本方法释放（§5.4）
        val converted = source !is VideoFrame.I420Buffer
        val i420: VideoFrame.I420Buffer = if (converted) {
            // 【修复（t10 首次真实编译）】本版 jar 的 `toI420()` 返回可空 `I420Buffer?`：
            // 转换失败即返回 ERROR（此时尚未分配任何资源，无泄漏），并由上层记录原因。
            source.toI420() ?: run {
                AppLog.w(TAG, "to_i420_failed", mapOf("reason" to "convert_null"))
                return VideoCodecStatus.ERROR
            }
        } else {
            source as VideoFrame.I420Buffer
        }

        val startedNs = System.nanoTime()
        try {
            // 与 t7 的接口约定（reports/07-native-dev.md §2.5）：nativeEncode 要求 3 个平面为
            // **direct** 且容量 >= stride*(rows-1)+row_bytes，否则返回 -4 ERR_PARAMETER。
            // 这里先做预检：失败就打出可诊断日志并直接返回，避免上游只看到一个没有原因的 -4。
            validatePlanes(i420)?.let { reason ->
                AppLog.e(
                    TAG,
                    "encoded_plane_rejected",
                    mapOf(
                        "reason" to reason,
                        "w" to i420.width.toString(),
                        "h" to i420.height.toString(),
                        "stride_y" to i420.strideY.toString(),
                        "stride_u" to i420.strideU.toString(),
                        "stride_v" to i420.strideV.toString(),
                        "direct" to (i420.dataY.isDirect && i420.dataU.isDirect && i420.dataV.isDirect).toString(),
                    )
                )
                return VideoCodecStatus.ERR_PARAMETER
            }
            val keyFrame = info.frameTypes.firstOrNull() == EncodedImage.FrameType.VideoFrameKey
            val rc = NativeVp9Encoder.nativeEncode(
                currentHandle,
                i420.dataY,
                i420.dataU,
                i420.dataV,
                i420.width,
                i420.height,
                i420.strideY,
                i420.strideU,
                i420.strideV,
                frame.timestampNs,
                normalizeRotation(frame.rotation),
                keyFrame,
            )
            return when (rc) {
                NativeVp9Encoder.STATUS_OK -> deliverFrame(currentHandle, frame.timestampNs, cb)
                NativeVp9Encoder.STATUS_NO_OUTPUT -> VideoCodecStatus.NO_OUTPUT
                else -> {
                    AppLog.e(TAG, "encoder_encode_failed", mapOf("rc" to rc.toString()))
                    statusOf(rc)
                }
            }
        } finally {
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            if (elapsedMs > SLOW_FRAME_WARN_MS) {
                AppLog.w(TAG, "encoder_slow_frame", mapOf("ms" to elapsedMs.toString()))
            }
            // 本层创建的引用必须释放；入参 frame 的所有权属上游，不释放（§5.4）
            if (converted) i420.release()
        }
    }

    /**
     * 应用 GCC 分配的分层码率（§5.4 / §5.6）。
     *
     * 运行期矩阵长度可能为 3×3（SDK 按 kMaxSpatialLayers × kMaxTemporalStreams 填），
     * 本路线下通常仅 `[0][0]` 非 0；展平后按 `s*T+t` 传给 C++，由本项目策略分配到 L1T3。
     */
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus {
        val currentHandle = handle
        if (currentHandle == 0L) return VideoCodecStatus.UNINITIALIZED
        val matrix = allocation.bitratesBbs
        val spatial = matrix.size
        val temporal = if (spatial > 0) matrix[0].size else 0
        if (spatial <= 0 || temporal <= 0) return VideoCodecStatus.ERR_PARAMETER
        // 不变量（与 t7 的 nativeSetRates 校验对齐，reports/07-native-dev.md v1.2 §2.6）：
        // 数组长度与 S/T 取自**同一个矩阵**，故 len == S*T **由构造保证**，
        // t7 的 nativeSetRates_rejected reason=length_mismatch 不可能被本层触发。
        // 注意运行期 S/T = 3/3（SDK 按 kMaxSpatialLayers×kMaxTemporalStreams 填），
        // 与 nativeInit 时的 1/3 不同；t7 侧按输入维度解析、按 configured 1/3 出分层，无需适配。
        val flat = IntArray(spatial * temporal)
        for (s in 0 until spatial) {
            for (t in 0 until temporal) {
                flat[s * temporal + t] = matrix[s][t]
            }
        }
        val total = allocation.sum
        val rc = NativeVp9Encoder.nativeSetRates(currentHandle, flat, spatial, temporal, total, framerate)
        EncoderRateBus.publish(total, framerate)
        if (rc != NativeVp9Encoder.STATUS_OK) {
            // 字段与 t7 的 nativeSetRates_rejected（reason=length_mismatch/bad_dim）对齐，便于真机配对定位
            AppLog.w(
                TAG,
                "setrates_failed",
                mapOf(
                    "rc" to rc.toString(),
                    "s" to spatial.toString(),
                    "t" to temporal.toString(),
                    "len" to flat.size.toString(),
                    "total_bps" to total.toString(),
                    "fps" to framerate.toString(),
                )
            )
            return statusOf(rc)
        }
        AppLog.i(
            TAG,
            "setrates",
            mapOf(
                "total_bps" to total.toString(),
                "fps" to framerate.toString(),
                "s" to spatial.toString(),
                "t" to temporal.toString(),
            )
        )
        return VideoCodecStatus.OK
    }

    /** 释放句柄（幂等；释放后 handle 置 0，后续调用返回 UNINITIALIZED）。 */
    override fun release(): VideoCodecStatus {
        synchronized(lock) {
            if (released) return VideoCodecStatus.OK
            released = true
            val currentHandle = handle
            handle = 0L
            callback = null
            if (currentHandle == 0L) return VideoCodecStatus.OK
            NativeVp9Encoder.nativeRelease(currentHandle)
            AppLog.i(TAG, "encoder_released")
            return VideoCodecStatus.OK
        }
    }

    /** 质量缩放交给本项目策略，避免与 SDK quality scaler 双控制（§5.4）。 */
    override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF

    /** 自研软编（§5.4）。 */
    override fun isHardwareEncoder(): Boolean = false

    /** 实现名固定 `SelfVp9Libvpx`（§5.4/§6.6）。 */
    override fun getImplementationName(): String = IMPL_NAME

    /**
     * 本轮保持 Java 路径（§5.4：§5.7 升级时才返回 native 指针）。
     *
     * 【API 适配（t10 首次真实编译核对，L2 证据）】本版 jar 的 `org.webrtc.VideoEncoder`
     * **没有** `createNativeVideoEncoder()`；同名语义的方法叫 **`createNative(long)`**
     * （interface 默认方法，class 文件实测：`createNative(J)J`）。
     * 返回 `0L` = **不创建 native 编码器对象** —— 自研 VP9 编码完全走 Java 侧
     * （`initEncode/encode/setRates` → 自有 JNI → libvpx，§5.4 的 A1 路线），语义与原意图完全一致。
     */
    override fun createNative(webrtcEnvRef: Long): Long = 0L

    /**
     * 分辨率—码率上下限（doc/14 §5.4 引用 doc/11 §5.3）。
     *
     * 【API 适配（t10 首次真实编译核对）】本版 jar 的 `ResolutionBitrateLimits` 构造为
     * **4 参** `(int frameSizePixels, int minStartBitrateBps, int minBitrateBps, int maxBitrateBps)`
     * —— 是**单个像素总数**，不是 (width, height)。故此处按 `w * h` 传入
     * （class 文件实测：字段 `frameSizePixels/minStartBitrateBps/minBitrateBps/maxBitrateBps`）。
     */
    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = arrayOf(
        VideoEncoder.ResolutionBitrateLimits(320 * 180, 0, 0, 500_000),
        VideoEncoder.ResolutionBitrateLimits(640 * 360, 0, 0, 1_000_000),
        VideoEncoder.ResolutionBitrateLimits(1280 * 720, 0, 0, 2_000_000),
    )

    // ============================ 内部实现 ============================

    /** 取回编码结果并回调（§5.4）。 */
    private fun deliverFrame(handle: Long, captureTimeNs: Long, cb: VideoEncoder.Callback): VideoCodecStatus {
        val size = NativeVp9Encoder.nativeGetEncodedFrameSize(handle)
        if (size <= 0) return VideoCodecStatus.NO_OUTPUT
        if (size > dst.capacity()) {
            if (size > MAX_DST_BYTES) {
                AppLog.e(TAG, "encoded_frame_too_large", mapOf("bytes" to size.toString(), "max" to MAX_DST_BYTES.toString()))
                return VideoCodecStatus.ERROR
            }
            dst = ByteBuffer.allocateDirect(size)
        }
        val written = NativeVp9Encoder.nativeCopyEncodedFrame(handle, dst, meta)
        if (written <= 0) return if (written == 0) VideoCodecStatus.NO_OUTPUT else VideoCodecStatus.ERROR
        dst.position(0)
        dst.limit(written)

        val builder = EncodedImage.builder()
            .setBuffer(dst, null)
            .setEncodedWidth(meta[0])
            .setEncodedHeight(meta[1])
            .setCaptureTimeNs(captureTimeNs)
            .setFrameType(
                if (meta[2] == 1) EncodedImage.FrameType.VideoFrameKey
                else EncodedImage.FrameType.VideoFrameDelta
            )
            // 【t46】编码帧的**旋转已烘进像素**（native 按 rotationDegrees 旋转 I420 并交换尺寸），
            // EncodedImage 自身不再携带角度 ⇒ 保持 0 是**语义正确**的（与 doc/14:490 冻结写法一致）；
            // `meta[0]/meta[1]` 已是旋转后的编码尺寸（90/270 时宽高已交换）。
            .setRotation(0)
        if (meta[5] >= 0) builder.setQp(meta[5])
        cb.onEncodedFrame(builder.createEncodedImage(), VideoEncoder.CodecSpecificInfoVP9())

        AppLog.d(
            TAG,
            "encoded_frame",
            mapOf(
                "bytes" to written.toString(),
                "w" to meta[0].toString(),
                "h" to meta[1].toString(),
                "key" to (meta[2] == 1).toString(),
                "qp" to meta[5].toString(),
            )
        )
        return VideoCodecStatus.OK
    }

    /**
     * 校验 I420 平面是否满足 t7 侧 nativeEncode 的前置条件（direct + 容量）。
     *
     * 容量公式与 native-dev 的校验一致：`stride * (rows - 1) + row_bytes`
     * （亮度行/色度行分开算，色度按 (w+1)/2 × (h+1)/2）。
     *
     * @param i420 待编码的 I420 平面集合。
     * @return null 表示通过；否则返回失败原因（写入日志，便于与 native 侧 nativeEncode_rejected 对齐）。
     */
    private fun validatePlanes(i420: VideoFrame.I420Buffer): String? {
        val width = i420.width
        val height = i420.height
        if (width <= 0 || height <= 0) return "bad_dimensions"
        val y = i420.dataY
        val u = i420.dataU
        val v = i420.dataV
        if (!y.isDirect || !u.isDirect || !v.isDirect) return "non_direct_buffer"
        val chromaRows = (height + 1) / 2
        val chromaCols = (width + 1) / 2
        val needY = i420.strideY.toLong() * (height - 1) + width
        val needU = i420.strideU.toLong() * (chromaRows - 1) + chromaCols
        val needV = i420.strideV.toLong() * (chromaRows - 1) + chromaCols
        if (y.capacity() < needY) return "plane_capacity_y"
        if (u.capacity() < needU) return "plane_capacity_u"
        if (v.capacity() < needV) return "plane_capacity_v"
        return null
    }

    /** 非法旋转值按 0 处理（§6.6）。 */
    private fun normalizeRotation(rotation: Int): Int =
        if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) rotation else 0

    /**
     * native 状态码 → org.webrtc 状态。
     *
     * 直接按 **§6.6 冻结的数值**显式映射：这些常量就是契约码表本身，
     * 既避免"两处各写一份数值"的漂移，也不再依赖 `VideoCodecStatus.getNumber()`
     * （原 §7.2 假设 A-5 因此解除）。
     */
    private fun statusOf(rc: Int): VideoCodecStatus = when (rc) {
        NativeVp9Encoder.STATUS_OK -> VideoCodecStatus.OK
        NativeVp9Encoder.STATUS_NO_OUTPUT -> VideoCodecStatus.NO_OUTPUT
        NativeVp9Encoder.STATUS_ERROR -> VideoCodecStatus.ERROR
        NativeVp9Encoder.STATUS_LEVEL_EXCEEDED -> VideoCodecStatus.LEVEL_EXCEEDED
        NativeVp9Encoder.STATUS_MEMORY -> VideoCodecStatus.MEMORY
        NativeVp9Encoder.STATUS_ERR_PARAMETER -> VideoCodecStatus.ERR_PARAMETER
        NativeVp9Encoder.STATUS_ERR_SIZE -> VideoCodecStatus.ERR_SIZE
        NativeVp9Encoder.STATUS_TIMEOUT -> VideoCodecStatus.TIMEOUT
        NativeVp9Encoder.STATUS_UNINITIALIZED -> VideoCodecStatus.UNINITIALIZED
        NativeVp9Encoder.STATUS_FALLBACK_SOFTWARE -> VideoCodecStatus.FALLBACK_SOFTWARE
        else -> VideoCodecStatus.ERROR
    }
}
