package com.example.webrtcdemo.webrtc

import android.content.Context
import com.example.webrtcdemo.log.AppLog
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

// ============================================================================
// 渲染池（doc/14 §7.3，全部用 org.webrtc.SurfaceViewRenderer）
// ----------------------------------------------------------------------------
// 冻结参数：
//   init(eglBase.eglBaseContext, rendererEvents)；setScalingType(SCALE_ASPECT_FILL)；
//   setEnableHardwareScaler(true)；本地预览 setMirror(true)（远端 false）。
//   ⚠️ 第二个参数**不得传 null**（契约 §7.1 已按一手源码校正；传 null 会导致首帧事件永不派发）。
// 接线：
//   远端 remoteVideoTrack.addSink(remoteRenderer)
//   本地 videoTrack.addSink(localRenderer)（与采集同一个 videoSource）
// 生命周期（硬性）：onDestroy/onPause **必须先 `track.removeSink(renderer)` 再 `renderer.release()`**。
// 首帧事件：SurfaceViewRenderer 的 RendererEvents.onFirstFrameRendered → CallUiState.isRemoteVideoReady
//   （取代 doc/05 §4 与 doc/11 §3.4 中已被 §6.5 取消的「远端帧就绪」JNI 回调）。
// ============================================================================

/**
 * 视频渲染器池：统一创建/接线/解绑 SurfaceViewRenderer。
 */
class VideoRendererPool(private val eglBase: EglBase) {

    @Volatile
    private var localTrack: VideoTrack? = null

    @Volatile
    private var localRenderer: SurfaceViewRenderer? = null

    @Volatile
    private var remoteTrack: VideoTrack? = null

    @Volatile
    private var remoteRenderer: SurfaceViewRenderer? = null

    /**
     * 创建一个配置好的渲染器（供 Compose `AndroidView` 使用）。
     *
     * **契约 §7.1（已按一手源码校正）：`init(...)` 的第二个参数不得传 `null`** ——
     * `SurfaceViewRenderer` 仅在 `rendererEvents != null` 时才派发
     * `onFirstFrameRendered()` / `onFrameResolutionChanged()`；传 `null` 会让首帧事件**永不触发**，
     * `CallUiState.isRemoteVideoReady` 便**永远为 false**（静默不工作，不是编译错误）。
     * 因此本端预览虽不需要首帧回调，也**同样传入 events 实现**（回调为空操作，仅用于日志）。
     *
     * @param context 视图上下文。
     * @param mirror 是否镜像（本地预览 true，远端 false）。
     * @param onFirstFrame 首帧回调（**仅远端需要**；为 null 时即本端预览，本方法仍会传非空 events）。
     */
    fun createRenderer(
        context: Context,
        mirror: Boolean,
        onFirstFrame: (() -> Unit)? = null,
    ): SurfaceViewRenderer {
        val renderer = SurfaceViewRenderer(context)
        val isRemote = onFirstFrame != null
        // 一律传非空 events（契约 §7.1）；远端额外把首帧上报给 CallViewModel
        val events = object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                AppLog.i(TAG, if (isRemote) "remote_first_frame" else "local_preview_first_frame")
                onFirstFrame?.invoke()
            }

            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                AppLog.d(
                    TAG,
                    if (isRemote) "remote_resolution_changed" else "local_preview_resolution_changed",
                    mapOf("w" to videoWidth.toString(), "h" to videoHeight.toString(), "rot" to rotation.toString()),
                )
            }
        }
        renderer.init(eglBase.eglBaseContext, events)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(mirror)
        return renderer
    }

    /** 绑定本地预览（`videoTrack.addSink`）。 */
    @Synchronized
    fun attachLocal(track: VideoTrack?, renderer: SurfaceViewRenderer?) {
        detachLocal()
        if (track == null || renderer == null) return
        track.addSink(renderer)
        localTrack = track
        localRenderer = renderer
        AppLog.i(TAG, "renderer_attached", mapOf("which" to "local"))
    }

    /** 绑定远端渲染（`remoteVideoTrack.addSink`）。 */
    @Synchronized
    fun attachRemote(track: VideoTrack?, renderer: SurfaceViewRenderer?) {
        detachRemote()
        if (track == null || renderer == null) return
        track.addSink(renderer)
        remoteTrack = track
        remoteRenderer = renderer
        AppLog.i(TAG, "renderer_attached", mapOf("which" to "remote"))
    }

    /** 解绑本地（**只 removeSink，不 release**：renderer 生命周期归 Compose 视图）。 */
    @Synchronized
    fun detachLocal() {
        val track = localTrack
        val renderer = localRenderer
        localTrack = null
        localRenderer = null
        if (track != null && renderer != null) {
            try {
                track.removeSink(renderer)
            } catch (t: Throwable) {
                AppLog.w(TAG, "renderer_detach_failed", mapOf("which" to "local", "reason" to (t.message ?: "-")))
            }
            AppLog.i(TAG, "renderer_detached", mapOf("which" to "local"))
        }
    }

    /** 解绑远端（同上）。 */
    @Synchronized
    fun detachRemote() {
        val track = remoteTrack
        val renderer = remoteRenderer
        remoteTrack = null
        remoteRenderer = null
        if (track != null && renderer != null) {
            try {
                track.removeSink(renderer)
            } catch (t: Throwable) {
                AppLog.w(TAG, "renderer_detach_failed", mapOf("which" to "remote", "reason" to (t.message ?: "-")))
            }
            AppLog.i(TAG, "renderer_detached", mapOf("which" to "remote"))
        }
    }

    /**
     * 解绑并释放渲染器（§7.3 顺序：先 removeSink 再 release）。
     *
     * 由 Compose 的 DisposableEffect 在离开通话页时调用。
     */
    @Synchronized
    fun detachAndReleaseAll() {
        val local = localRenderer
        val remote = remoteRenderer
        detachLocal()
        detachRemote()
        local?.release()
        remote?.release()
    }

    private companion object {
        const val TAG = "stats"
    }
}
