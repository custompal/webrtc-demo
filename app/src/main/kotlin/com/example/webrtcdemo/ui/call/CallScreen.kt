package com.example.webrtcdemo.ui.call

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.webrtcdemo.R
import com.example.webrtcdemo.diag.LogExporter
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.ui.theme.CallBackgroundLight
import com.example.webrtcdemo.webrtc.RendererRecoveryPolicy
import com.example.webrtcdemo.webrtc.VideoRendererPool
import com.example.webrtcdemo.webrtc.WebRtcEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/** §9.1 模块标签（UI 层固定 ui）。 */
private const val TAG = "ui"

/** t45 恢复看门狗轮询间隔（500 ms ⇒ 3 s 窗口内约 6 次判定）。 */
private const val RECOVER_POLL_MS = 500L

// ============================================================================
// 通话页（doc/10 §3.2/§3.3；数据源 doc/14 §7.3/§7.4）
// ----------------------------------------------------------------------------
// 布局：远端大画面 + 本地右上角小窗（SurfaceViewRenderer，§7.3）
//       + 状态面板（连接模式/速率/NAT/编码码率）+ 三个控制按钮 + 连接中遮罩 + 导出日志。
// 生命周期（§7.3 硬性）：离开页面时先 `track.removeSink(renderer)` 再 `renderer.release()`
//   —— 由 [com.example.webrtcdemo.webrtc.VideoRendererPool.detachAndReleaseAll] 统一处理。
// ============================================================================

/**
 * 通话页。
 *
 * @param roomId 房间号（导航参数）。
 * @param role `host` / `joiner`（导航参数）。
 * @param onHangup 返回首页回调；参数为**结束提示**（正常挂断时为 `null`，
 *   终态失败时为"通话已结束，可重新创建"之类文案，由首页展示 —— captain 2026-09-13）。
 * @param viewModel 通话 ViewModel。
 */
@Composable
fun CallScreen(
    roomId: String,
    role: String,
    onHangup: (String?) -> Unit,
    viewModel: CallViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 【t59】连接状态（connecting/connected/failed + 已用时长 + 是否可重试）：
    // 与 `state.isConnecting` 不同，它是"是否真的连上"的唯一判定（`isConnecting` 也由它派生）。
    val conn by viewModel.connStatus.collectAsStateWithLifecycle()
    val localTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val navigateHome by viewModel.navigateHome.collectAsStateWithLifecycle()
    // 【t68】可恢复态（房间被服务端回收）：非空 ⇒ 显示「重新创建房间」显式入口（不自动退出通话页）
    val recoverable by viewModel.recoverable.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    // 【t68】显示用房间号：可恢复态下点「重新创建房间」会拿到**服务端重新分配的房间号**，
    // 因此以 ViewModel 的状态为准，仅在其为空时回落到导航参数（保证新房间号可见/可复制）。
    val displayRoomId = state.roomId.ifBlank { roomId }

    // 【t39 修复②】复制会议号：用**框架 ClipboardManager**（版本稳定，避开 Compose 侧
    // `LocalClipboardManager` 在不同 BOM 版本的弃用差异）+ Toast 可见反馈。
    // `CLIPBOARD_SERVICE` 是 Android 核心系统服务（API 1 起恒存在），故此处强转安全。
    val copyRoomId: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // label 与内容都用房间号本身（避免为此再新增第 4 个字符串资源）
        clipboard.setPrimaryClip(ClipData.newPlainText(displayRoomId, displayRoomId))
        Toast.makeText(context, context.getString(R.string.call_room_code_copied), Toast.LENGTH_SHORT).show()
    }

    // 渲染器：引擎初始化完成后才能创建（initCall 在同一次 LaunchedEffect 里先行）
    var renderers by remember { mutableStateOf<Pair<SurfaceViewRenderer?, SurfaceViewRenderer?>?>(null) }

    LaunchedEffect(Unit) {
        viewModel.initCall(roomId, role)
        viewModel.installSessionSummaryProvider()
        val pool = WebRtcEngine.rendererPool()
        renderers = if (pool == null) {
            null
        } else {
            pool.createRenderer(context, mirror = true) to
                pool.createRenderer(context, mirror = false) { viewModel.onRemoteFirstFrame() }
        }
    }

    // 离开通话页：把结束提示（若有）交回首页展示 —— 正常挂断时 state.error 为空，不弹提示。
    LaunchedEffect(navigateHome) {
        if (navigateHome) {
            viewModel.onNavigateHomeConsumed()
            onHangup(state.error)
        }
    }

    val localRenderer = renderers?.first
    val remoteRenderer = renderers?.second

    LaunchedEffect(localTrack, localRenderer) {
        WebRtcEngine.rendererPool()?.attachLocal(localTrack, localRenderer)
    }
    LaunchedEffect(remoteTrack, remoteRenderer) {
        WebRtcEngine.rendererPool()?.attachRemote(remoteTrack, remoteRenderer)
    }

    // ===================== t45：后台 → 前台预览恢复 =====================
    // 现象：真机切后台再回前台，本地预览黑屏（其余 UI 正常）。回前台后
    // `local_preview_resolution_changed` 仍在打点（14:34:42.835 / 14:34:44.520）⇒ **不能用
    // "回调还在"判活**：EglRenderer 在"无 surface 丢帧"前同样会回调（见 VideoRendererPool 头注释）。
    // 代码级根因：本页原先**没有任何** onPause/onResume 处理 —— sink 只在
    // `LaunchedEffect(localTrack, localRenderer)` 键变化时挂载，恢复路径既不重挂也不重建渲染器，
    // 一旦 surface/EGL surface 没自动回来（或渲染器已被 release）就永久黑屏。
    val lifecycleOwner = LocalLifecycleOwner.current
    val recoveryPolicy = remember { RendererRecoveryPolicy() }
    var resumeTick by remember { mutableStateOf(0) }
    var localGeneration by remember { mutableStateOf(0) }
    var remoteGeneration by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    recoveryPolicy.onPause()
                    // §7.3：切后台**不是离页** —— 只解除看门狗，绝不 release/reinit；
                    // 渲染器实例与其 sink 保持，回前台优先走"零成本恢复"（幂等重挂）。
                    AppLog.i(TAG, "on_pause", mapOf("release" to "no", "reason" to "background_keep_renderers"))
                }

                Lifecycle.Event.ON_RESUME -> {
                    recoveryPolicy.onResume(System.nanoTime())
                    resumeTick++
                    AppLog.i(TAG, "on_resume", mapOf("tick" to resumeTick.toString()))
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 恢复看门狗：① 立即幂等重挂 sink（覆盖"被 detach 过"的情形）② 3 秒内仍无帧 ⇒ 换**新实例**重建
    LaunchedEffect(resumeTick, localRenderer, remoteRenderer) {
        if (resumeTick == 0) return@LaunchedEffect
        val pool = WebRtcEngine.rendererPool() ?: return@LaunchedEffect
        val resumedAtNs = recoveryPolicy.resumedAt
        AppLog.i(
            TAG,
            "preview_recover_armed",
            mapOf(
                "localSurface" to pool.surfaceAlive(VideoRendererPool.WHICH_LOCAL).toString(),
                "remoteSurface" to pool.surfaceAlive(VideoRendererPool.WHICH_REMOTE).toString(),
                "localReleased" to pool.isReleased(localRenderer).toString(),
            ),
        )
        // 采集与 surface 是两条独立链路：只在采集确实已停时才重启（避免重复占用摄像头/EGL）
        WebRtcEngine.mediaCapture()?.resumeIfNeeded()
        pool.attachLocal(localTrack, localRenderer)
        pool.attachRemote(remoteTrack, remoteRenderer)

        while (true) {
            delay(RECOVER_POLL_MS)
            val now = System.nanoTime()
            val localOk = pool.frameSeenSince(VideoRendererPool.WHICH_LOCAL, resumedAtNs)
            val remoteOk = remoteTrack == null || pool.frameSeenSince(VideoRendererPool.WHICH_REMOTE, resumedAtNs)
            when (recoveryPolicy.evaluate(now, localOk && remoteOk)) {
                RendererRecoveryPolicy.Action.RECOVER -> {
                    AppLog.w(
                        TAG,
                        "preview_recover_attempt",
                        mapOf(
                            "attempt" to recoveryPolicy.attempts.toString(),
                            "localFrame" to localOk.toString(),
                            "remoteFrame" to remoteOk.toString(),
                            "localSurface" to pool.surfaceAlive(VideoRendererPool.WHICH_LOCAL).toString(),
                            "localReleased" to pool.isReleased(localRenderer).toString(),
                        ),
                    )
                    // 【t45b 修复】**不再重建渲染器实例**。
                    // 真机证据（2026-09-14T17:16:13，Mi 10 Pro，默认编码器已出画面）：
                    //   `preview_recover_attempt attempt=1 localFrame=false remoteFrame=false`
                    //   → `renderer_recreate/released/surface_destroyed` → 未捕获异常：
                    //   `java.lang.IllegalStateException: The specified child already has a parent.
                    //    You must call removeView() on the child's parent first.`
                    //   （栈：AndroidViewHolder.<init> ← AndroidView，Compose 插入节点时
                    //    旧实例仍挂在旧 AndroidViewHolder 上）⇒ 出画面后反而被看门狗"修"到闪退。
                    // 恢复动作改为**幂等重挂 sink + 按需恢复采集**：安全、无新视图、无父容器冲突，
                    // 且这正是让 surface 重新出画所需的最小动作。
                    WebRtcEngine.mediaCapture()?.resumeIfNeeded()
                    pool.attachLocal(localTrack, localRenderer)
                    pool.attachRemote(remoteTrack, remoteRenderer)
                    AppLog.i(
                        TAG,
                        "preview_recover_reattached",
                        mapOf(
                            "attempt" to recoveryPolicy.attempts.toString(),
                            "localFrame" to localOk.toString(),
                            "remoteFrame" to remoteOk.toString(),
                        ),
                    )
                }

                RendererRecoveryPolicy.Action.GIVE_UP -> {
                    AppLog.e(
                        TAG,
                        "preview_recover_give_up",
                        mapOf(
                            "attempts" to recoveryPolicy.attempts.toString(),
                            "localSurface" to pool.surfaceAlive(VideoRendererPool.WHICH_LOCAL).toString(),
                        ),
                    )
                    return@LaunchedEffect
                }

                RendererRecoveryPolicy.Action.NONE -> Unit
            }
        }
    }

    // 离开页面：先 removeSink 再 release（§7.3）
    DisposableEffect(Unit) {
        onDispose {
            WebRtcEngine.rendererPool()?.detachAndReleaseAll()
            viewModel.hangup()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CallBackgroundLight)) {
        // 远端视频（大画面）
        // t45：`key(generation)` —— 恢复路径重建渲染器后必须让 Compose **挂一个全新视图**，
        // 否则复用的是旧 SurfaceView（其 surface 已销毁/EGL surface 不会重建）⇒ 仍然黑屏。
        if (remoteRenderer != null) {
            key(remoteGeneration) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    // 【t45b 防御】交给 Compose 前先与旧父容器解绑，否则 AndroidViewHolder.<init>
                    // 的 addView 会抛 "The specified child already has a parent"（真机已实测该崩溃）。
                    factory = { _ ->
                        (remoteRenderer.parent as? android.view.ViewGroup)
                            ?.removeView(remoteRenderer)
                        remoteRenderer
                    },
                )
            }
        }

        // 【t59】远端区域的"非实时"遮罩：连接未建立 / 没有新鲜远端帧时，**压暗远端画面**，
        // 杜绝"把最后一帧静止帧当成已出画面"（SurfaceViewRenderer 在 RTP 停止后会把最后一帧留在屏幕上）。
        // 位置放在本地小窗**之前** ⇒ 不会挡住本地预览。
        if (conn.remoteDimmed) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xB3000000)))
        }

        // 本地视频（右上角小窗）
        // 注：`Modifier.align` 是 BoxScope 扩展，必须在 `key {}` 之外求值（key 的 block 无 BoxScope 接收者）。
        val localViewModifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .width(110.dp)
            .height(150.dp)
        if (localRenderer != null) {
            key(localGeneration) {
                AndroidView(
                    modifier = localViewModifier,
                    factory = { _ ->
                        // 【t45b 防御】同上：解绑旧父容器，避免 addView 时抛
                        // "The specified child already has a parent"。
                        (localRenderer.parent as? android.view.ViewGroup)
                            ?.removeView(localRenderer)
                        localRenderer
                    },
                )
            }
        }

        // 【t39 修复②】会议号**常驻顶部覆盖层**：整个通话生命周期可见（原先只在 isConnecting
        // 遮罩内渲染 ⇒ 连接完成/被远端画面盖住后房主无法把 6 位房间号告知第二台设备）。
        // 空值守卫：roomId 为空白时不渲染，避免出现"会议号："空壳。
        if (displayRoomId.isNotBlank()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0x99000000), MaterialTheme.shapes.small)
                    .clickable(onClick = copyRoomId)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.call_room_code, displayRoomId),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = copyRoomId) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.call_room_code_copy_desc),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // 状态面板 + 控制栏
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPanel(state = state)

            state.error?.let { error ->
                Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            exportMessage?.let { text ->
                Text(text = text, color = Color.White, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 静音切换
                IconButton(onClick = { viewModel.toggleMute() }) {
                    Icon(
                        imageVector = if (state.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (state.isMuted) R.string.call_action_unmute else R.string.call_action_mute
                        ),
                        tint = Color.White,
                    )
                }
                // 摄像头开关
                IconButton(onClick = { viewModel.toggleCamera() }) {
                    Icon(
                        imageVector = if (state.isCameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        contentDescription = stringResource(
                            if (state.isCameraOn) R.string.call_action_camera_off else R.string.call_action_camera_on
                        ),
                        tint = Color.White,
                    )
                }
                // 前后摄切换
                IconButton(onClick = { viewModel.switchCamera() }) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = stringResource(R.string.call_action_switch_camera),
                        tint = Color.White,
                    )
                }
                // 导出日志（D6：通话页入口）
                IconButton(
                    enabled = !exporting,
                    onClick = {
                        exporting = true
                        exportMessage = null
                        AppLog.i("export", "export_zip", mapOf("from" to "call"))
                        scope.launch {
                            when (val result = viewModel.exportLogs()) {
                                is LogExporter.Result.Success -> {
                                    exportMessage = context.getString(R.string.log_export_success, result.fileCount)
                                    context.startActivity(
                                        LogExporter.buildShareChooser(context, result.uri, result.zipFile.name)
                                    )
                                }

                                LogExporter.Result.NoLogs ->
                                    exportMessage = context.getString(R.string.log_export_empty)

                                is LogExporter.Result.Failure ->
                                    exportMessage = context.getString(R.string.log_export_failed, result.message)
                            }
                            exporting = false
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.log_export_entry),
                        tint = Color.White,
                    )
                }
                // 挂断
                IconButton(onClick = { viewModel.hangup() }) {
                    Icon(
                        imageVector = Icons.Filled.CallEnd,
                        contentDescription = stringResource(R.string.call_action_hangup),
                        tint = Color(0xFFFF5252),
                    )
                }
            }
        }

        // 【t59】连接状态卡（替换原先只看 `state.isConnecting` 的遮罩）
        //   * 未连上（含"连上又断/画面停滞"）⇒ 明确的连接中状态 + **已用/已中断时长**；
        //   * 失败 ⇒ 可操作失败提示 + **一键重试**（走 ViewModel 的世代化新会话，见 retryConnection）；
        //   * 已连上但还没收到画面 ⇒ 顶部小提示（不遮挡画面）。
        // 【t68】两处闸门（顺序即优先级）：
        //   ① `recoverable != null`（房间被回收但**通话未结束**）⇒ 只显示可恢复面板 + 显式
        //      「重新创建房间」，**不**显示失败卡（不得让用户以为通话已终止）；
        //   ② `conn.failureCardVisible`（= 需要遮罩 且 **未被媒体存活抑制**）⇒ 才渲染失败卡；
        //      媒体仍存活时改渲染中性提示 `conn.recoveryBannerText`（文案不含 ICE/失败/重连）。
        val recoverableNotice = recoverable
        if (recoverableNotice != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = recoverableNotice.notice,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (recoverableNotice.mediaAlive) {
                        RECOVERABLE_MEDIA_ALIVE_NOTICE
                    } else {
                        RECOVERABLE_MEDIA_STOPPED_NOTICE
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                // 显式入口（captain 2026-09-15 裁定：不得自动退出，必须给出可操作入口）
                Button(
                    onClick = { viewModel.recreateRoom() },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(text = RECREATE_ROOM_LABEL)
                }
                Text(text = displayRoomId, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (conn.failureCardVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (conn.phase == ConnPhase.CONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Color.White)
                }
                Text(
                    text = conn.title,
                    color = if (conn.phase == ConnPhase.FAILED) Color(0xFFFF8A80) else Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = conn.detail, color = Color.White, style = MaterialTheme.typography.bodySmall)
                if (conn.canRetry) {
                    // 一键重试：新世代会话（旧 PC/轨道/统计循环先关掉）
                    Button(
                        onClick = { viewModel.retryConnection() },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(text = RETRY_LABEL)
                    }
                }
                Text(text = displayRoomId, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (conn.recoveryBannerVisible) {
            // 【t68③】媒体存活（帧新鲜/候选对仍在）但链路在抖动：只给中性提示 —— **不遮挡画面、
            // 不出现 ICE/失败/重连字样**（真机 dl-b 15:27:54：`ice_down=true` 而 `media_age_ms=2705`，
            // 视频随后自行恢复，旧实现却弹"连接中断…"失败卡）。
            Text(
                text = conn.recoveryBannerText,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color(0x99000000), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        } else if (!conn.showOverlay && !conn.remoteFrameReady) {
            // 已连上但尚未收到远端画面：给一条不遮挡画面的提示（避免用户以为"卡住了"）
            Text(
                text = WAITING_REMOTE_FRAME_NOTICE,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color(0x99000000), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** 【t59】一键重试按钮文案（inScope 不含 `res/values/strings.xml`，故与 ViewModel 的提示常量一致内联）。 */
private const val RETRY_LABEL = "点击重试"

/** 【t68】可恢复态下的显式入口文案（房间被服务端回收后新建房间继续通话）。 */
private const val RECREATE_ROOM_LABEL = "重新创建房间"

/** 【t68】可恢复态副标题：媒体仍在流（明确"通话没断"，避免用户误以为已退出）。 */
private const val RECOVERABLE_MEDIA_ALIVE_NOTICE = "画面仍在传输；重建后请把新会议号告知对方"

/** 【t68】可恢复态副标题：媒体也已停止（房间与画面都需要重建）。 */
private const val RECOVERABLE_MEDIA_STOPPED_NOTICE = "房间已无法恢复；可重新创建房间继续"

/** 【t59】已连上、等待远端画面时的提示。 */
private const val WAITING_REMOTE_FRAME_NOTICE = "已连接，等待对端画面…"
