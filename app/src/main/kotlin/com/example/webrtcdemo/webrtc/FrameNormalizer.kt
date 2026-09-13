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
     * - I420 帧：原帧**零拷贝透传**（不做任何 release）；
     * - 其它格式：`toI420()`（在采集线程完成 GL 读取）→ 构造新 `VideoFrame`（rotation 已烘进 I420，故置 0）
     *   → 交给下游 → `out.release()`。
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
        val converted = VideoFrame(i420, 0, frame.timestampNs)
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
