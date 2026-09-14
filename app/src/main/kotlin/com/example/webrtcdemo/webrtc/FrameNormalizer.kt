package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.log.AppLog
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

// ============================================================================
// I420 归一化层（doc/14 §7.2，**必做且冻结**）
// ----------------------------------------------------------------------------
// 存在原因：Camera2 采集出来的是 **texture 帧**，`toI420()` 必须在**采集线程**执行
// （`SurfaceTextureHelper` 的采集线程持有 GL 上下文），这样进入编码器的帧恒为 I420。
//
// 所有权约定（硬性，§7.2）：
//   - 入参 `frame` 的所有权属**上游采集器**（CameraSession 在 onFrameCaptured 返回后自行
//     `frame.release()`）→ 本层**不得**释放入参；
//   - 本层自己 `toI420()` 得到的 buffer 与本层构造的 `out` 由**本层**释放。
// 若真机实测出现 double-free 或帧被复用（上游不释放入参），以实测为准改为由本层释放，
// 并在 t8 报告中登记该偏差（契约 §7.2 明确允许）。
// ============================================================================

/**
 * 采集帧归一化包装：保证下游（编码器）收到 I420。
 *
 * @param downstream 真正的下游观察者（通常是 `videoSource.capturerObserver`）。
 */
class FrameNormalizer(private val downstream: CapturerObserver) : CapturerObserver {

    /**
     * 采集到一帧。
     *
     * - I420 帧：原帧**零拷贝透传**（不做任何 release；**rotation 元数据随原帧一并保留**）；
     * - 其它格式：`toI420()`（在采集线程完成 GL 读取）→ 构造新 `VideoFrame`，
     *   **`rotation` 取 `frame.rotation` 原样保留** → 交给下游 → `out.release()`。
     *
     * ⚠️ **t39 真机缺陷修复（本地预览逆时针 90°）—— 为什么必须保留 rotation 元数据**：
     * 上游 `toI420()` **只做 YUV 转换、不做任何旋转**：见
     * `third_party/libwebrtc/include/sdk/android/api/org/webrtc/TextureBufferImpl.java:111-113`
     * （`yuvConverter.convert(this)`；该文件全篇 `rotation` 命中数 = **0**）。
     * 朝向由**渲染器读取 `VideoFrame.getRotation()` 元数据**得到：
     * `.../org/webrtc/VideoFrameDrawer.java:204` → `renderMatrix.preRotate(frame.getRotation())`
     * （`EglRenderer` → `drawFrame` 链路对 I420 与 texture 帧都走这里）；
     * 编码器同样**直取元数据**：本工程 `encoder/Vp9VideoEncoder.kt:208` →
     * `nativeEncode(..., normalizeRotation(frame.rotation), ...)`（doc/14 §6.6 第 635 行：
     * "`VideoFrame.getRotation()` 直接映射"），且 doc/14 第 518 行要求 90/270 时交换编码尺寸。
     * ⇒ 此处把 rotation **置 0** 会同时造成两个缺陷：①本地预览朝向错误；②编码器失去 90/270 尺寸交换信号。
     *
     * 注：doc/14 §7.4 第 692 行原先把第二实参（rotation）**写死为 0**，理由是"rotation 已烘进 I420"；
     * 该假设已被上述上游源码证伪；勘误文本见 `reports/13-device-defect-fix.md`（doc/14 为冻结件，未直接改动）。
     *
     * @param frame 上游采集帧（所有权属上游）。
     */
    override fun onFrameCaptured(frame: VideoFrame) {
        val buffer = frame.buffer
        if (buffer is VideoFrame.I420Buffer) {
            if (DEBUG_FRAME_LOG) {
                AppLog.d(
                    TAG,
                    "capture_frame",
                    mapOf(
                        "buf" to "i420",
                        "w" to buffer.width.toString(),
                        "h" to buffer.height.toString(),
                        "rot" to frame.rotation.toString(),
                    )
                )
            }
            downstream.onFrameCaptured(frame)
            return
        }

        val startedNs = System.nanoTime()
        // 【修复（t10 首次真实编译）】本版 jar 的 `VideoFrame.Buffer.toI420()` 返回**可空**
        // `VideoFrame.I420Buffer?`（转换可能失败）。失败时丢弃该帧并留可诊断日志：
        // 上游 frame 的所有权仍属上游，本层此时尚未创建任何对象，故**无需释放**。
        val i420 = buffer.toI420()
        if (i420 == null) {
            AppLog.w(TAG, "frame_convert_failed", mapOf("reason" to "toI420_null"))
            return
        }
        // 【t39 修复】rotation 必须保留：`toI420()` 不做旋转（TextureBufferImpl.java:111-113），
        // 朝向由渲染器/编码器读取 VideoFrame.getRotation() 元数据决定（VideoFrameDrawer.java:204）。
        val converted = VideoFrame(i420, frame.rotation, frame.timestampNs)
        try {
            downstream.onFrameCaptured(converted)
        } finally {
            // 只释放本层创建的对象；入参 frame 由上游释放
            converted.release()
        }
        if (DEBUG_FRAME_LOG) {
            val convertUs = (System.nanoTime() - startedNs) / 1_000L
            AppLog.d(
                TAG,
                "capture_frame",
                mapOf(
                    "buf" to "texture",
                    "convert_us" to convertUs.toString(),
                    "w" to i420.width.toString(),
                    "h" to i420.height.toString(),
                    "rot" to frame.rotation.toString(),
                )
            )
        }
    }

    /** 采集开始（透传）。 */
    override fun onCapturerStarted(success: Boolean) {
        AppLog.i(TAG, "capturer_started", mapOf("success" to success.toString()))
        downstream.onCapturerStarted(success)
    }

    /** 采集停止（透传）。 */
    override fun onCapturerStopped() {
        AppLog.i(TAG, "capturer_stopped")
        downstream.onCapturerStopped()
    }

    private companion object {
        const val TAG = "main"

        /** 逐帧打点会有成本，默认关闭（需要时诊断页打开）。 */
        const val DEBUG_FRAME_LOG = false
    }
}
