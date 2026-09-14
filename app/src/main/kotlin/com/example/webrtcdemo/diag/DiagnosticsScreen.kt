package com.example.webrtcdemo.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.webrtcdemo.BuildConfig
import com.example.webrtcdemo.R
import com.example.webrtcdemo.config.AppConfig
import com.example.webrtcdemo.encoder.Vp9VideoEncoder
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.log.FileLogger
import com.example.webrtcdemo.log.LogLevel
import com.example.webrtcdemo.nat.NatTypeRepository
import com.example.webrtcdemo.nativebridge.NativeLoader
import com.example.webrtcdemo.nativebridge.NativeLog
import com.example.webrtcdemo.signaling.SignalingIdentity
import com.example.webrtcdemo.webrtc.WebRtcEngine
import kotlinx.coroutines.launch

// ============================================================================
// 诊断页（doc/14 §2.1 / §7.4 / §9.5 / §9.6）
// ----------------------------------------------------------------------------
// 提供（契约要求）：
//   · 日志级别开关（Kotlin 立即生效 + native `nativeSetLevel`；webrtc 层需重启，UI 明确提示）
//   · NAT 重测（§7.4）
//   · ICE 策略切换 ALL/RELAY（§7.4：复现中继路径）
//   · 默认编码器对照开关（§7.1，受控偏离，需在报告登记）
//   · 导出日志（§9.5）
//   · 关键路径与环境信息展示（日志目录 / 导出目录 / native 库状态 / 编码实现名）
// ============================================================================

/**
 * 诊断页。
 *
 * @param onBack 返回回调。
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf(AppLog.level()) }
    var iceRelay by remember { mutableStateOf(AppConfig.forceRelay(context)) }
    var useDefaultEncoder by remember { mutableStateOf(AppConfig.useDefaultEncoder(context)) }
    var urlInput by remember { mutableStateOf(AppConfig.signalingUrl(context)) }
    var effectiveUrl by remember { mutableStateOf(AppConfig.signalingUrl(context)) }
    var useLowRes by remember { mutableStateOf(AppConfig.useLowResolution(context)) }
    var pendingSaveFile by remember { mutableStateOf<java.io.File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    // ACTION_CREATE_DOCUMENT（§9.5 可选路径）：用标准契约拉起系统「另存为」，把导出的 zip 拷贝到用户选择的位置
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LogExporter.ZIP_MIME)
    ) { uri ->
        val source = pendingSaveFile
        pendingSaveFile = null
        if (uri == null || source == null || !source.exists()) {
            message = "已取消另存为"
        } else {
            // 注意：`use { ... }` 的最后表达式必须是 Boolean —— `copyTo` 返回 Long，
            // 若直接把它当返回值，`copied` 会被推断为 `Comparable & Serializable`（Long/Boolean 联合），
            // `if (copied)` 便无法编译（t10 首次真实编译暴露，L2 证据）。
            val copied = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { input ->
                        input.copyTo(out)
                        true
                    }
                } ?: false
            }.getOrDefault(false)
            message = if (copied) "已另存为: $uri" else "另存失败"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.diag_title), style = MaterialTheme.typography.headlineSmall)

        // ---- 日志级别（§9.6）----
        Text(text = stringResource(R.string.diag_log_level), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (candidate in LogLevel.entries) {
                OutlinedButton(
                    enabled = candidate != level,
                    onClick = {
                        level = candidate
                        AppLog.setLevel(context, candidate)
                        // native 层：先持久化 level_native（§9.6），再让 native 立即生效（§6.2；内部不抛异常）
                        AppLog.persistNativeLevel(context, candidate)
                        NativeLog.setLevel(candidate)
                        message = "日志级别已切换为 ${candidate.label}"
                    },
                ) {
                    Text(text = candidate.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            text = stringResource(R.string.diag_level_restart_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- NAT 重测（§7.4）----
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val stunUrl = com.example.webrtcdemo.webrtc.IceServerCache.get()?.stunUrl
                if (stunUrl.isNullOrBlank()) {
                    message = "尚未收到 created/joined，无可用 STUN 服务器"
                } else {
                    NatTypeRepository.startLocalDetection(stunUrl)
                    message = "已重新发起 NAT 探测"
                }
            },
        ) {
            Text(stringResource(R.string.diag_nat_redetect))
        }

        // ---- ICE 策略（§7.4：RELAY 复现中继路径）----
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.diag_ice_policy),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(
                checked = iceRelay,
                onCheckedChange = { checked ->
                    iceRelay = checked
                    AppConfig.setIcePolicy(
                        context,
                        if (checked) AppConfig.ICE_POLICY_RELAY else AppConfig.ICE_POLICY_ALL,
                    )
                    message = if (checked) "ICE 策略: RELAY（下次通话生效）" else "ICE 策略: ALL（下次通话生效）"
                },
            )
        }

        // ---- 默认编码器对照（§7.1，受控偏离）----
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.diag_use_default_encoder),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(
                checked = useDefaultEncoder,
                onCheckedChange = { checked ->
                    useDefaultEncoder = checked
                    AppConfig.setUseDefaultEncoder(context, checked)
                    message = "需重启 App 生效（对照实验，会失去自研编码器学习点）"
                },
            )
        }

        // ---- 弱设备降级（§7.2/R4：480x360@24，下次通话生效）----
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.diag_low_res),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(
                checked = useLowRes,
                onCheckedChange = { checked ->
                    useLowRes = checked
                    AppConfig.setUseLowResolution(context, checked)
                    message = if (checked) "已开启 480x360@24（下次通话生效）" else "已恢复 640x480@30（下次通话生效）"
                },
            )
        }

        // ---- 信令地址覆盖（§8.1：诊断页可改并持久化，覆盖值优先）----
        Text(text = stringResource(R.string.diag_signaling_url), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = urlInput,
            onValueChange = { urlInput = it },
            singleLine = true,
            label = { Text(AppConfig.SIGNALING_PATH) },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    // §8.1：校验/补齐以 SIGNALING_PATH 结尾；非法则拒绝、保持原值并提示
                    val accepted = AppConfig.setSignalingUrl(context, urlInput)
                    effectiveUrl = AppConfig.signalingUrl(context)
                    message = if (accepted) {
                        "已保存；生效地址: $effectiveUrl （下次连接生效）"
                    } else {
                        "地址非法：仅支持 ws:// 或 wss://，路径固定为 ${AppConfig.SIGNALING_PATH}（已拒绝，保持原值）"
                    }
                },
            ) { Text(stringResource(R.string.diag_save_url)) }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    urlInput = ""
                    AppConfig.setSignalingUrl(context, "")
                    effectiveUrl = AppConfig.signalingUrl(context)
                    message = "已恢复默认地址: $effectiveUrl"
                },
            ) { Text(stringResource(R.string.diag_reset_url)) }
        }
        Text(
            text = "当前生效: $effectiveUrl",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 导出日志（§9.5）----
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !exporting,
            onClick = {
                exporting = true
                message = null
                scope.launch {
                    AppLog.flush()
                    when (val result = LogExporter.exportZip(context)) {
                        is LogExporter.Result.Success -> {
                            message = context.getString(R.string.log_export_success, result.fileCount) +
                                "\n" + result.zipFile.absolutePath
                            context.startActivity(
                                LogExporter.buildShareChooser(context, result.uri, result.zipFile.name)
                            )
                        }

                        LogExporter.Result.NoLogs -> message = context.getString(R.string.log_export_empty)
                        is LogExporter.Result.Failure ->
                            message = context.getString(R.string.log_export_failed, result.message)
                    }
                    exporting = false
                }
            },
        ) {
            Text(stringResource(R.string.log_export_entry))
        }

        // ---- 另存为（§9.5 可选路径：ACTION_CREATE_DOCUMENT）----
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !exporting,
            onClick = {
                exporting = true
                message = null
                scope.launch {
                    when (val result = LogExporter.exportZip(context)) {
                        is LogExporter.Result.Success -> {
                            pendingSaveFile = result.zipFile
                            saveAsLauncher.launch(result.zipFile.name)
                        }

                        LogExporter.Result.NoLogs -> message = context.getString(R.string.log_export_empty)
                        is LogExporter.Result.Failure ->
                            message = context.getString(R.string.log_export_failed, result.message)
                    }
                    exporting = false
                }
            },
        ) { Text(stringResource(R.string.diag_save_as)) }

        // ---- 环境信息 ----
        Text(
            text = buildString {
                appendLine("日志目录: ${FileLogger.get()?.logDir()?.absolutePath ?: AppLog.logDir(context).absolutePath}")
                appendLine("导出目录: ${LogExporter.exportDir(context).absolutePath}")
                appendLine("日志文件: ${FileLogger.FILE_NAME}")
                appendLine("native 层级别: ${AppLog.nativeLevel(context).label}（已持久化 level_native）")
                appendLine("webrtc 层级别: ${AppLog.webrtcLevel(context).ifEmpty { "未记录" }}（启动时确定，需重启生效）")
                // t25：失败时把**真实异常**一并显示（可复制），便于真机远程排障。
                appendLine(
                    "WebRTC 引擎: " + if (WebRtcEngine.isReady()) {
                        "就绪"
                    } else {
                        "未初始化" + (WebRtcEngine.lastFailureDetail()?.let { "（失败原因: $it）" } ?: "")
                    }
                )
                appendLine("本端 peerId: ${SignalingIdentity.selfPeerId.value.ifEmpty { "—" }} / 对端 peerId: ${SignalingIdentity.remotePeerId.value.ifEmpty { "—" }}")
                appendLine("单文件上限: ${FileLogger.MAX_FILE_BYTES} B × ${FileLogger.MAX_FILES}")
                appendLine("native 库: ${if (NativeLoader.isLoaded()) "已加载" else "未加载"}")
                appendLine("编码实现名: ${Vp9VideoEncoder.IMPL_NAME}")
                appendLine("本端 NAT: ${NatTypeRepository.localNat.value.wire}")
                appendLine("对端 NAT: ${NatTypeRepository.remoteNat.value.wire}")
                appendLine("信令地址: $effectiveUrl（默认 ${BuildConfig.SIGNALING_URL}）")
            },
            style = MaterialTheme.typography.bodySmall,
        )

        message?.let { text ->
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onBack) {
            Text(stringResource(R.string.diag_back))
        }
    }
}
