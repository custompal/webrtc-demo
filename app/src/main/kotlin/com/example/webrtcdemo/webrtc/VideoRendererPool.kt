package com.example.webrtcdemo.webrtc

import android.content.Context
import android.view.SurfaceHolder
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
// 生命周期（硬性）：**离开通话页**时必须先 `track.removeSink(renderer)` 再 `renderer.release()`；
//   而 **切后台/回前台不属于离页**（t45）：`onPause` 只解绑观察、**不得 release**，
//   回前台由 ui/call/CallScreen.kt 的看门狗按 RendererRecoveryPolicy 重建渲染器并重挂 sink。
// 首帧事件：SurfaceViewRenderer 的 RendererEvents.onFirstFrameRendered → CallUiState.isRemoteVideoReady
//   （取代 doc/05 §4 与 doc/11 §3.4 中已被 §6.5 取消的「远端帧就绪」JNI 回调）。
//
// t45 新增（真机「切后台再回前台预览黑屏」定因与自愈）：
//   * `renderer_created` / `renderer_released` / `renderer_attach_rejected` 打点；
//   * **surface 生命周期打点** —— `SurfaceViewRenderer.surfaceDestroyed()` 是空实现
//     （`SurfaceViewRenderer.java:246`），上层原本完全看不到 surface 何时消失，
//     故这里在 `holder` 上追加一个 `SurfaceHolder.Callback` 记录 `surface_created`/`surface_changed`/
//     `surface_destroyed`，并维护 `surfaceAlive`；
//   * **每渲染器最后一帧时间戳**（`lastFrameAtNs`）—— 注意 `EglRenderer` 在**丢弃**帧（无 surface）
//     时仍会回调 `onFrameResolutionChanged`，所以"回调还在"**不能**证明画面正常；判活必须用
//     `frameSeenSince(which, sinceNs)`；
//   * `recreateRenderer()`：用同一规格重建**全新实例**（旧实例必须随后 `releaseRenderer()`），
//     避免"released 实例无法再出画"与"surface 已存在时 re-init 不会重建 EGL surface"两个坑。
// ============================================================================

/**
 * 视频渲染器池：统一创建/接线/解绑 SurfaceViewRenderer。
 */
class VideoRendererPool(private val eglBase: EglBase) {

    /** 渲染器规格（t45：前台恢复时按同规格重建）。 */
    private data class RendererSpec(
        val which: String,
        val mirror: Boolean,
        val isRemote: Boolean,
        val onFirstFrame: (() -> Unit)?,
    )

    @Volatile
    private var localTrack: VideoTrack? = null

    @Volatile
    private var localRenderer: SurfaceViewRenderer? = null

    @Volatile
    private var remoteTrack: VideoTrack? = null

    @Volatile
    private var remoteRenderer: SurfaceViewRenderer? = null

    /** 渲染器 → 规格（重建用）。 */
    private val specs = HashMap<SurfaceViewRenderer, RendererSpec>()

    /** 已 release 的渲染器（禁止再 attach，防 use-after-release）。 */
    private val releasedRenderers = HashSet<SurfaceViewRenderer>()

    @Volatile
    private var lastLocalFrameAtNs: Long = Long.MIN_VALUE

    @Volatile
    private var lastRemoteFrameAtNs: Long = Long.MIN_VALUE

    @Volatile
    private var localSurfaceAlive: Boolean = false

    @Volatile
    private var remoteSurfaceAlive: Boolean = false

    @Volatile
    private var releasedCount: Int = 0

    /**
     * 创建一个配置好的渲染器（供 Compose `AndroidView` 使用）。
     *
     * **契约 §7.1（已按一手源码校正）：`init(...)` 的第二个参数不得传 `null`** ——
     * `SurfaceViewRenderer` 仅在 `rendererEvents != null` 时才派发
     * `onFirstFrameRendered()` / `onFrameResolutionChanged()`；传 `null` 会让首帧事件**永不触发**，
     * `CallUiState.isRemoteVideoReady` 便**永远为 false**（静默不工作，不是编译错误）。
     * 因此本端预览虽不需要首帧回调，也**同样传入 events 实现**（回调为空操作，仅用于诊断打点）。
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
        val spec = RendererSpec(
            which = if (onFirstFrame != null) WHICH_REMOTE else WHICH_LOCAL,
            mirror = mirror,
            isRemote = onFirstFrame != null,
            onFirstFrame = onFirstFrame,
        )
        return build(context, spec)
    }

    /**
     * 按已有规格重建一个**全新** [SurfaceViewRenderer]（t45 前台恢复路径）。
     *
     * 为什么必须换实例而不是原地 `release()` + `init()`：
     *   ① `SurfaceViewRenderer.release()`（`SurfaceViewRenderer.java:96-98` → `eglRenderer.release()`）
     *      之后该实例**永不再出画**；
     *   ② 即便再 `init()`，只要旧 surface 仍存在（`surfaceCreated` 不会二次触发），
     *      `EglRenderer` 就不会重建 EGL surface ⇒ 仍然黑屏。
     * 新实例由 Compose `key(generation)` 重新挂到视图树，框架会重新走 `surfaceCreated`。
     *
     * @return 新渲染器；无对应规格（未创建过）时返回 null。
     */
    @Synchronized
    fun recreateRenderer(context: Context, which: String): SurfaceViewRenderer? {
        val spec = specs.values.firstOrNull { it.which == which }
        if (spec == null) {
            AppLog.w(TAG, "renderer_recreate_skipped", mapOf("which" to which, "reason" to "no_spec"))
            return null
        }
        AppLog.i(TAG, "renderer_recreate", mapOf("which" to which))
        val fresh = build(context, spec)
        // 旧实例不再被池跟踪：调用方拿到新实例后应立即 releaseRenderer(旧实例)
        return fresh
    }

    private fun build(context: Context, spec: RendererSpec): SurfaceViewRenderer {
        val renderer = SurfaceViewRenderer(context)
        val events = object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                markFrame(spec.which)
                AppLog.i(
                    TAG,
                    if (spec.isRemote) "remote_first_frame" else "local_preview_first_frame",
                    mapOf("which" to spec.which, "surface" to surfaceAlive(spec.which).toString()),
                )
                spec.onFirstFrame?.invoke()
            }

            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                markFrame(spec.which)
                AppLog.d(
                    TAG,
                    if (spec.isRemote) "remote_resolution_changed" else "local_preview_resolution_changed",
                    mapOf(
                        "w" to videoWidth.toString(),
                        "h" to videoHeight.toString(),
                        "rot" to rotation.toString(),
                        // ⚠️ 该回调在"无 surface 丢帧"时同样会触发（EglRenderer 先更新尺寸再丢帧），
                        // 故把 surface 是否存活一并打出，避免把它当成"画面正常"的证据。
                        "surface" to surfaceAlive(spec.which).toString(),
                    ),
                )
            }
        }
        renderer.init(eglBase.eglBaseContext, events)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(spec.mirror)

        // t45：surface 生命周期打点（SurfaceViewRenderer 自身 surfaceDestroyed 为空实现）
        renderer.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    setSurfaceAlive(spec.which, true)
                    AppLog.i(TAG, "surface_created", mapOf("which" to spec.which))
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    AppLog.d(
                        TAG,
                        "surface_changed",
                        mapOf("which" to spec.which, "w" to width.toString(), "h" to height.toString()),
                    )
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    setSurfaceAlive(spec.which, false)
                    AppLog.i(TAG, "surface_destroyed", mapOf("which" to spec.which))
                }
            }
        )

        synchronized(this) { specs[renderer] = spec }
        AppLog.i(
            TAG,
            "renderer_created",
            mapOf("which" to spec.which, "mirror" to spec.mirror.toString(), "tracked" to specs.size.toString()),
        )
        return renderer
    }

    /** 绑定本地预览（`videoTrack.addSink`）。 */
    @Synchronized
    fun attachLocal(track: VideoTrack?, renderer: SurfaceViewRenderer?) {
        detachLocal()
        if (track == null || renderer == null) return
        if (releasedRenderers.contains(renderer)) {
            AppLog.w(TAG, "renderer_attach_rejected", mapOf("which" to WHICH_LOCAL, "reason" to "released"))
            return
        }
        track.addSink(renderer)
        localTrack = track
        localRenderer = renderer
        AppLog.i(TAG, "renderer_attached", mapOf("which" to WHICH_LOCAL, "surface" to surfaceAlive(WHICH_LOCAL).toString()))
    }

    /** 绑定远端渲染（`remoteVideoTrack.addSink`）。 */
    @Synchronized
    fun attachRemote(track: VideoTrack?, renderer: SurfaceViewRenderer?) {
        detachRemote()
        if (track == null || renderer == null) return
        if (releasedRenderers.contains(renderer)) {
            AppLog.w(TAG, "renderer_attach_rejected", mapOf("which" to WHICH_REMOTE, "reason" to "released"))
            return
        }
        track.addSink(renderer)
        remoteTrack = track
        remoteRenderer = renderer
        AppLog.i(TAG, "renderer_attached", mapOf("which" to WHICH_REMOTE, "surface" to surfaceAlive(WHICH_REMOTE).toString()))
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
                AppLog.w(TAG, "renderer_detach_failed", mapOf("which" to WHICH_LOCAL, "reason" to (t.message ?: "-")))
            }
            AppLog.i(TAG, "renderer_detached", mapOf("which" to WHICH_LOCAL))
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
                AppLog.w(TAG, "renderer_detach_failed", mapOf("which" to WHICH_REMOTE, "reason" to (t.message ?: "-")))
            }
            AppLog.i(TAG, "renderer_detached", mapOf("which" to WHICH_REMOTE))
        }
    }

    /**
     * 解绑并释放指定渲染器（§7.3 顺序：先 removeSink 再 release）。
     *
     * t45：此方法同时把该实例标记为 released，后续 attach 会被拒绝并记
     * `renderer_attach_rejected`，避免 use-after-release。
     */
    @Synchronized
    fun releaseRenderer(renderer: SurfaceViewRenderer?) {
        if (renderer == null) return
        if (renderer === localRenderer) detachLocal()
        if (renderer === remoteRenderer) detachRemote()
        val spec = specs.remove(renderer)
        if (spec != null) {
            if (spec.which == WHICH_LOCAL) localSurfaceAlive = false else remoteSurfaceAlive = false
        }
        try {
            renderer.release()
        } catch (t: Throwable) {
            AppLog.w(TAG, "renderer_release_failed", mapOf("reason" to (t.message ?: "-")))
        }
        releasedRenderers.add(renderer)
        releasedCount++
        AppLog.i(
            TAG,
            "renderer_released",
            mapOf("which" to (spec?.which ?: "-"), "releasedTotal" to releasedCount.toString()),
        )
    }

    /**
     * 解绑并释放池内**当前**的本地/远端渲染器（§7.3）。
     *
     * 由 Compose 的 DisposableEffect 在**离开通话页**时调用；切后台/回前台**不得**调用。
     */
    @Synchronized
    fun detachAndReleaseAll() {
        val local = localRenderer
        val remote = remoteRenderer
        detachLocal()
        detachRemote()
        releaseRenderer(local)
        releaseRenderer(remote)
    }

    /** 该渲染器的最后一帧时间戳（无帧时为 [Long.MIN_VALUE]）。 */
    @Synchronized
    fun lastFrameAtNs(which: String): Long =
        if (which == WHICH_REMOTE) lastRemoteFrameAtNs else lastLocalFrameAtNs

    /** 自 `sinceNs` 起是否出过帧（t45 判活口径；勿用"回调还在"代替）。 */
    @Synchronized
    fun frameSeenSince(which: String, sinceNs: Long): Boolean = lastFrameAtNs(which) >= sinceNs

    /** surface 是否存活（`surfaceCreated` 与 `surfaceDestroyed` 之间）。 */
    @Synchronized
    fun surfaceAlive(which: String): Boolean =
        if (which == WHICH_REMOTE) remoteSurfaceAlive else localSurfaceAlive

    /** 是否已 release（禁止再挂载）。 */
    @Synchronized
    fun isReleased(renderer: SurfaceViewRenderer?): Boolean = renderer != null && releasedRenderers.contains(renderer)

    private fun markFrame(which: String) {
        val now = System.nanoTime()
        if (which == WHICH_REMOTE) lastRemoteFrameAtNs = now else lastLocalFrameAtNs = now
    }

    private fun setSurfaceAlive(which: String, alive: Boolean) {
        if (which == WHICH_REMOTE) remoteSurfaceAlive = alive else localSurfaceAlive = alive
    }

    companion object {
        /** 诊断/日志用：本地预览通道名。 */
        const val WHICH_LOCAL = "local"

        /** 诊断/日志用：远端画面通道名。 */
        const val WHICH_REMOTE = "remote"

        /** §9.1 模块标签（stats）。 */
        private const val TAG = "stats"
    }
}

// ============================================================================
// 前台恢复看门狗策略（t45：真机「切后台再回前台本地预览黑屏」）
// ----------------------------------------------------------------------------
// 为什么需要它：`SurfaceViewRenderer` 的 surface 在 `onPause` 之后可能被系统销毁，
// 而本工程的渲染器只在创建时 `init(...)` 一次、只在 `LaunchedEffect(localTrack,
// localRenderer)` 键变化时 `addSink`（见 `ui/call/CallScreen.kt`）。**恢复路径上没有任何
// 重新挂载/重建逻辑** ⇒ 若 surface/EGL surface 没有自动回来（或渲染器已处于 released 态），
// 画面就此永久黑掉，而 `EglRenderer` 仍会在丢帧前回调 `onFrameResolutionChanged`
// （真机日志 14:34:42.835 / 14:34:44.520 仍在打点，见 reports/16），因此**不能**用
// "回调还在" 判定"画面正常"。
//
// 本类把"回到前台后多久没出帧 ⇒ 该做什么"抽成**纯 Kotlin 状态机**（无 Android 依赖），
// 以便用纯 JVM 单测覆盖（容器内无 JDK，实际执行由宿主机 `:app:testDebugUnitTest` 覆盖）。
//
// 语义：
//   - `onResume(now)`：进入恢复窗口，清空计数；
//   - `evaluate(now, frameSeenAfterResume)`：窗口内出帧 ⇒ 解除看门狗（NONE，armed=false）；
//     窗口到期仍无帧 ⇒ `RECOVER`（最多 [maxAttempts] 次，每次动作后**重新计时**）；
//     超过次数 ⇒ `GIVE_UP`（由调用方上报 UI/日志，不再无限重建）。
// ============================================================================

/**
 * 前台恢复看门狗策略。
 *
 * @param windowNs 单次判定窗口（默认 3 秒 —— 对应验收「切回前台 3 秒内恢复出画」）。
 * @param maxAttempts 最多自动恢复次数（默认 2；之后 `GIVE_UP`，避免无限重建渲染器）。
 */
class RendererRecoveryPolicy(
    val windowNs: Long = DEFAULT_WINDOW_NS,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {

    /** 看门狗动作。 */
    enum class Action {
        /** 无需动作（未武装，或窗口未到期，或已出帧）。 */
        NONE,

        /** 需要一次自动恢复（重建渲染器并重挂 sink）。 */
        RECOVER,

        /** 已超过最大尝试次数 —— 上报 UI/日志，停止自动重建。 */
        GIVE_UP,
    }

    /** 是否处于恢复观察窗口内。 */
    var armed: Boolean = false
        private set

    /** 已触发的自动恢复次数。 */
    var attempts: Int = 0
        private set

    private var resumedAtNs: Long = Long.MIN_VALUE

    /** 进入前台的时刻（最近一次 `onResume`）。 */
    val resumedAt: Long get() = resumedAtNs

    /** 是否已在本轮恢复窗口内见到帧。 */
    var sawFrame: Boolean = false
        private set

    /** 进入前台：武装看门狗并开始计时。 */
    fun onResume(nowNs: Long) {
        armed = true
        sawFrame = false
        attempts = 0
        resumedAtNs = nowNs
    }

    /** 切到后台：解除看门狗（恢复路径与会话生命周期解耦）。 */
    fun onPause() {
        armed = false
    }

    /** 收到一帧（任一渲染器的 `onFrameResolutionChanged` / 首帧回调）。 */
    fun onFrame() {
        sawFrame = true
    }

    /**
     * 评估当前状态。
     *
     * @param nowNs 当前单调时钟（`System.nanoTime()`）。
     * @param frameSeenAfterResume 调用方独立测得"本轮恢复后是否已出帧"（渲染器回调可能来自
     *   被 release 的实例，故以渲染池的帧时间戳为准，不单靠本状态机）。
     */
    fun evaluate(nowNs: Long, frameSeenAfterResume: Boolean): Action {
        if (!armed) return Action.NONE
        if (sawFrame || frameSeenAfterResume) {
            armed = false
            return Action.NONE
        }
        if (nowNs - resumedAtNs < windowNs) return Action.NONE
        if (attempts >= maxAttempts) {
            armed = false
            return Action.GIVE_UP
        }
        attempts++
        resumedAtNs = nowNs // 每次动作后重新计时，避免连续重建
        return Action.RECOVER
    }

    companion object {
        /** 验收要求：切回前台 **3 秒**内恢复出画。 */
        const val DEFAULT_WINDOW_NS: Long = 3_000_000_000L

        /** 最多自动重建 2 次；仍失败则交给用户/日志定位。 */
        const val DEFAULT_MAX_ATTEMPTS: Int = 2
    }
}
