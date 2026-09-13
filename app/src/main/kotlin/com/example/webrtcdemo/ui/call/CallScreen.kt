package com.example.webrtcdemo.ui.call

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.webrtcdemo.R
import com.example.webrtcdemo.diag.LogExporter
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.ui.theme.CallBackgroundLight
import com.example.webrtcdemo.webrtc.WebRtcEngine
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

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
    val localTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val navigateHome by viewModel.navigateHome.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

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

    // 离开页面：先 removeSink 再 release（§7.3）
    DisposableEffect(Unit) {
        onDispose {
            WebRtcEngine.rendererPool()?.detachAndReleaseAll()
            viewModel.hangup()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CallBackgroundLight)) {
        // 远端视频（大画面）
        if (remoteRenderer != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { remoteRenderer },
            )
        }

        // 本地视频（右上角小窗）
        if (localRenderer != null) {
            AndroidView(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .width(110.dp)
                    .height(150.dp),
                factory = { localRenderer },
            )
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

        // 连接中遮罩（doc/10 §3.3）
        if (state.isConnecting) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), color = Color.White)
                Text(
                    text = stringResource(R.string.call_connecting),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = roomId, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
