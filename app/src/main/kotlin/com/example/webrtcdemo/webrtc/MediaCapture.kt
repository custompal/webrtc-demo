package com.example.webrtcdemo.webrtc

import android.content.Context
import com.example.webrtcdemo.BuildConfig
import com.example.webrtcdemo.config.AppConfig
import com.example.webrtcdemo.log.AppLog
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

// ============================================================================
// 采集链（doc/14 §7.2，冻结：Camera2 + I420 归一化层）
// ----------------------------------------------------------------------------
// 冻结流程：
//   Camera2Enumerator → 选前/后摄 → SurfaceTextureHelper.create("CaptureThread", eglCtx)
//   → createCapturer(device, cameraEventsHandler) → factory.createVideoSource(false)
//   → FrameNormalizer(source.capturerObserver) 作为 CapturerObserver
//   → capturer.initialize(helper, context, normalizer) → startCapture(640,480,30)
//   → factory.createVideoTrack("video0", source)
//
// 【受控偏离（已在报告中登记，原因 = 真实 API 形状）】
//   契约 §7.2 片段写作 `enumerator.createCapturer(deviceName, surfaceTextureHelper)`；
//   但 org.webrtc 的 `CameraEnumerator.createCapturer(String, CameraVideoCapturer.CameraEventsHandler)`
//   第二个参数是**事件处理器**，SurfaceTextureHelper 是在 `capturer.initialize(...)` 时传入的。
//   因此本实现按真实 API 传出一个 CameraEventsHandler（只做日志），语义与契约一致。
//
// 参数：初值 640x480@30（§7.2/U4），诊断页可降为 480x360@24（R4）。
// 禁止：doc/10 §6.1 的相机库方案（C06 已作废），以及 §6.5/§7.2 已取消的 surface/sink JNI 入口。
// ============================================================================

/**
 * 摄像头采集与视频源/视频轨持有者（进程内单例，由 [WebRtcEngine] 创建）。
 *
 * @param context 应用上下文（用于枚举摄像头与 initialize）。
 * @param eglBase 进程内单例 EGL 上下文。
 * @param factory 共享的 PeerConnectionFactory。
 */
class MediaCapture(
    private val context: Context,
    private val eglBase: EglBase,
    private val factory: PeerConnectionFactory,
) {

    companion object {
        private const val TAG = "main"

        /** §7.2/U4 冻结初值。 */
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_FPS = 30

        /** R4 降级值（设备弱时）。 */
        const val LOW_WIDTH = 480
        const val LOW_HEIGHT = 360
        const val LOW_FPS = 24

        /** 采集线程名（§7.2 冻结）。 */
        const val CAPTURE_THREAD_NAME = "CaptureThread"
    }

    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var capturer: CameraVideoCapturer? = null

    private var videoSource: VideoSource? = null

    private var videoTrack: VideoTrack? = null

    /** 当前是否前置摄像头（UI「摄像头切换」提示用）。 */
    @Volatile
    private var frontCamera: Boolean = true

    /** 当前采集参数（诊断页可改）。 */
    @Volatile
    private var captureWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var captureHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var captureFps: Int = DEFAULT_FPS

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String) {
            AppLog.e(TAG, "camera_error", mapOf("detail" to errorDescription))
        }

        override fun onCameraDisconnected() {
            AppLog.e(TAG, "camera_disconnected")
        }

        override fun onCameraFreezed(errorDescription: String) {
            AppLog.w(TAG, "camera_freezed", mapOf("detail" to errorDescription))
        }

        override fun onCameraOpening(cameraName: String) {
            AppLog.i(TAG, "camera_opening", mapOf("camera" to cameraName))
        }

        override fun onFirstFrameAvailable() {
            AppLog.i(TAG, "camera_first_frame")
        }

        override fun onCameraClosed() {
            AppLog.i(TAG, "camera_closed")
        }
    }

    /**
     * 启动采集（幂等：已启动直接返回同一个 [VideoTrack]）。
     *
     * t45：幂等分支会记 `capture_already_started`（阈值 DEBUG 时可见），
     * 便于回前台路径判断"采集本来就活着"，从而把黑屏定因收敛到渲染层。
     *
     * @return 视频轨；摄像头不可用时返回 null（不抛异常）。
     */
    @Synchronized
    fun ensureStarted(): VideoTrack? {
        videoTrack?.let {
            AppLog.i(TAG, "capture_already_started", mapOf("facing" to if (frontCamera) "front" else "back"))
            return it
        }

        val source = factory.createVideoSource(false)
        val helper = SurfaceTextureHelper.create(CAPTURE_THREAD_NAME, eglBase.eglBaseContext)
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        if (names.isEmpty()) {
            AppLog.e(TAG, "camera_missing")
            helper.dispose()
            source.dispose()
            return null
        }
        // R4：弱设备降级（480x360@24）；开关来自诊断页，**下次启动采集时**生效
        if (AppConfig.useLowResolution(context)) {
            captureWidth = LOW_WIDTH
            captureHeight = LOW_HEIGHT
            captureFps = LOW_FPS
            AppLog.i(TAG, "capture_low_res", mapOf("w" to LOW_WIDTH.toString(), "h" to LOW_HEIGHT.toString(), "fps" to LOW_FPS.toString()))
        }
        val preferFront = !BuildConfig.LIBCAMERA_FACING.equals("back", ignoreCase = true)
        frontCamera = preferFront
        val device = names.firstOrNull { name ->
            if (preferFront) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
        } ?: names[0]

        val newCapturer = enumerator.createCapturer(device, cameraEvents)
        if (newCapturer == null) {
            AppLog.e(TAG, "camera_capturer_null", mapOf("device" to device))
            helper.dispose()
            source.dispose()
            return null
        }

        newCapturer.initialize(helper, context, FrameNormalizer(source.capturerObserver))
        newCapturer.startCapture(captureWidth, captureHeight, captureFps)

        val track = factory.createVideoTrack(WebRtcConfig.VIDEO_TRACK_ID, source)

        surfaceTextureHelper = helper
        capturer = newCapturer
        videoSource = source
        videoTrack = track
        AppLog.i(
            TAG,
            "capture_started",
            mapOf(
                "device" to device,
                "w" to captureWidth.toString(),
                "h" to captureHeight.toString(),
                "fps" to captureFps.toString(),
                "facing" to if (frontCamera) "front" else "back",
            )
        )
        return track
    }

    /**
     * 切换前后摄（§7.2：`capturer.switchCamera(null)`）。
     *
     * @return 是否已发出切换。
     */
    @Synchronized
    fun switchCamera(): Boolean {
        val current = capturer ?: return false
        frontCamera = !frontCamera
        current.switchCamera(null)
        AppLog.i(TAG, "camera_switched", mapOf("facing" to if (frontCamera) "front" else "back"))
        return true
    }

    /** 当前视频轨（未启动为 null）。 */
    fun currentVideoTrack(): VideoTrack? = videoTrack

    /** 采集是否存活（`videoTrack != null`）。t45：回前台判活用。 */
    @Synchronized
    fun isCapturing(): Boolean = videoTrack != null

    /**
     * 回前台恢复路径：**只在采集确实已停**时才重启（t45）。
     *
     * 设计要点：**采集与 surface 是两条独立链路** —— 真机日志显示回前台后
     * `local_preview_resolution_changed` 仍在打点（帧仍流向渲染器），因此这里**不得**无条件
     * 重建 `SurfaceTextureHelper`/`capturer`（会多占一份摄像头与 EGL 上下文，违反"不重复创建"）。
     * 故仅在 `isCapturing() == false` 时调用 [ensureStarted]，否则只记 `capture_resume_noop`。
     */
    @Synchronized
    fun resumeIfNeeded(): VideoTrack? {
        if (isCapturing()) {
            AppLog.i(TAG, "capture_resume_noop")
            return videoTrack
        }
        AppLog.w(TAG, "capture_resume_start")
        return ensureStarted()
    }

    /** 彻底释放（进程退出/引擎销毁）。 */
    @Synchronized
    fun release() {
        try {
            capturer?.stopCapture()
        } catch (t: Throwable) {
            AppLog.w(TAG, "capture_stop_failed", mapOf("reason" to (t.message ?: "-")))
        }
        capturer?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        capturer = null
        videoTrack = null
        videoSource = null
        surfaceTextureHelper = null
        AppLog.i(TAG, "capture_released")
    }
}
