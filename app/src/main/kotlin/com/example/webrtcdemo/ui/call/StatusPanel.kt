package com.example.webrtcdemo.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.webrtcdemo.R
import com.example.webrtcdemo.model.CallUiState

// ============================================================================
// 状态面板（doc/10 §3.2；数据源 doc/14 §7.4）
//   连接状态 P2P/RELAY、传输速率 ↑/↓、本端 NAT、对端 NAT、编码码率（+ 编码实现名）
// ============================================================================

/**
 * 通话状态面板。
 *
 * @param state 通话 UI 状态。
 */
@Composable
fun StatusPanel(state: CallUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatusRow(
            stringResource(R.string.call_status_connection_type),
            state.connectionType.ifEmpty { state.connectionState },
        )
        StatusRow(
            stringResource(R.string.call_status_bitrate),
            "↑${formatBps(state.upBitrate)} ↓${formatBps(state.downBitrate)}",
        )
        StatusRow(
            stringResource(R.string.call_status_local_nat),
            state.localNatType.ifEmpty { stringResource(R.string.home_nat_detecting) },
        )
        StatusRow(
            stringResource(R.string.call_status_remote_nat),
            state.remoteNatType.ifEmpty { stringResource(R.string.home_nat_detecting) },
        )
        StatusRow(
            stringResource(R.string.call_status_encoder_bitrate),
            formatBps(state.encoderBitrate),
        )
        if (state.encoderImplementation.isNotEmpty()) {
            StatusRow(stringResource(R.string.call_status_encoder_impl), state.encoderImplementation)
        }
        StatusRow(
            stringResource(R.string.call_status_peer_ids),
            "本端 ${state.selfPeerId.ifEmpty { PLACEHOLDER }} / 对端 ${state.remotePeerId.ifEmpty { PLACEHOLDER }}",
        )
        if (!state.isRemoteVideoReady) {
            StatusRow(stringResource(R.string.call_status_remote_video), stringResource(R.string.call_connecting))
        }
    }
}

/** 单行「标签: 值」。 */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 未知身份的占位符（D-3：收到 peerJoined 之前显示 —）。 */
private const val PLACEHOLDER = "—"

/** bps → 人类可读（kbps/Mbps）。 */
private fun formatBps(bps: Int): String = when {
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.2fMbps", bps / 1_000_000.0)
    bps >= 1_000 -> "${bps / 1_000}kbps"
    else -> "${bps}bps"
}
