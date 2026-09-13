package com.example.webrtcdemo.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.webrtcdemo.R
import com.example.webrtcdemo.diag.LogExporter
import com.example.webrtcdemo.log.AppLog
import kotlinx.coroutines.launch

// ============================================================================
// 首页（doc/10 §3.1）：创建会议 / 房间号输入 + 加入会议 / 本端 NAT / 导出日志
// ----------------------------------------------------------------------------
// · 运行时权限（§7.6）：进入首页即请求 CAMERA + RECORD_AUDIO；被拒则禁用创建/加入；
// · 房间号输入：全大写、最多 6 位、过滤非法字符（§8.2）；
// · 导出日志入口（D6/§9.5）：首页与通话页各一个。
// ============================================================================

/**
 * 首页。
 *
 * @param onEnterCall 进入通话页回调 `(roomId, role)`。
 * @param onOpenDiagnostics 打开诊断页回调。
 * @param callEndNotice 通话结束提示（由通话页经导航回传；`null` 表示无提示）。
 * @param viewModel 首页 ViewModel。
 */
@Composable
fun HomeScreen(
    onEnterCall: (String, String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    callEndNotice: String? = null,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // 状态流转：权限结果 → 允许/禁用创建与加入
        viewModel.onPermissionResult(result.values.all { granted -> granted })
    }

    // 首次进入请求相机与麦克风权限（已授予则不重复弹窗）
    LaunchedEffect(Unit) {
        val required = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val allGranted = required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.onPermissionResult(true)
        } else {
            permissionLauncher.launch(required)
        }
    }

    // 进入通话页（收到 created/joined 之后）
    LaunchedEffect(state.navigateToCall) {
        if (state.navigateToCall) {
            onEnterCall(state.roomId, state.role)
            viewModel.onNavigationConsumed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = state.granted && !state.isConnecting,
            onClick = { viewModel.onCreate() },
        ) {
            Text(stringResource(R.string.home_create_meeting))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = state.roomIdInput,
                onValueChange = { viewModel.onRoomIdInputChange(it) },
                label = { Text(stringResource(R.string.home_room_id_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
            Button(
                enabled = state.granted && !state.isConnecting && state.roomIdInput.isNotEmpty(),
                onClick = { viewModel.onJoin() },
            ) {
                Text(stringResource(R.string.home_join_meeting))
            }
        }

        // 状态面板：信令状态 + 本端 NAT
        Text(
            text = "${state.connectionState} · ${stringResource(R.string.home_nat_type_label)}: " +
                state.natType.ifEmpty { stringResource(R.string.home_nat_detecting) },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.isConnecting) {
            CircularProgressIndicator()
            Text(text = stringResource(R.string.home_connecting), style = MaterialTheme.typography.bodySmall)
        }

        state.error?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        // 通话结束提示（由通话页在返回时回传；captain 2026-09-13「回首页 + 明确提示」）：
        // 显示在首页的错误区，用户点「创建/加入」后由导航层清除。
        callEndNotice?.let { notice ->
            Text(
                text = notice,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // 导出日志（D6：首页入口）
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !exporting,
            onClick = {
                exporting = true
                exportMessage = null
                AppLog.i("export", "export_zip", mapOf("from" to "home"))
                scope.launch {
                    when (val result = LogExporter.exportZip(context)) {
                        is LogExporter.Result.Success -> {
                            exportMessage = context.getString(R.string.log_export_success, result.fileCount)
                            context.startActivity(
                                LogExporter.buildShareChooser(context, result.uri, result.zipFile.name)
                            )
                        }

                        LogExporter.Result.NoLogs -> exportMessage = context.getString(R.string.log_export_empty)
                        is LogExporter.Result.Failure ->
                            exportMessage = context.getString(R.string.log_export_failed, result.message)
                    }
                    exporting = false
                }
            },
        ) {
            Text(stringResource(R.string.log_export_entry))
        }

        exportMessage?.let { text ->
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenDiagnostics,
        ) {
            Text(stringResource(R.string.diag_entry))
        }
    }
}
