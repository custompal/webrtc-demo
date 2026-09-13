package com.example.webrtcdemo.model

// ============================================================================
// 统计快照（doc/14 §7.4 冻结的字段来源）
// ----------------------------------------------------------------------------
// 全部来自 org.webrtc 公共 API（不再跨 JNI）：
//   传输模式      candidate-pair.selected==true 的 localCandidateType/remoteCandidateType
//   上下行速率    outbound-rtp.bytesSent / inbound-rtp.bytesReceived 差分 ÷ Δt
//   可用带宽      candidate-pair.availableOutgoingBitrate（缺失回退 availableReceiveBitrate）
//   编码实现名    outbound-rtp.encoderImplementation（验收须 == "SelfVp9Libvpx"）
// 采集频率：每 2 s（§7.4）。
// ============================================================================

/**
 * 一次 stats 采样结果。
 *
 * @property connectionType `P2P` / `RELAY` / 空（未判定）。
 * @property upBitrateBps 上行速率（bps，由 bytesSent 差分算出）。
 * @property downBitrateBps 下行速率（bps，由 bytesReceived 差分算出）。
 * @property availableOutgoingBitrateBps 可用上行带宽（bps，缺失为 0）。
 * @property encoderImplementation `outbound-rtp.encoderImplementation`（期望 `SelfVp9Libvpx`）。
 * @property localCandidateType 选中对的本地候选类型（host/srflx/prflx/relay）。
 * @property remoteCandidateType 选中对的远端候选类型。
 * @property rttMs 选中对的往返时延（毫秒，缺失为 0）。
 * @property sampledAtMs 采样时刻（Unix 毫秒）。
 */
data class StatsSnapshot(
    val connectionType: String = "",
    val upBitrateBps: Int = 0,
    val downBitrateBps: Int = 0,
    val availableOutgoingBitrateBps: Int = 0,
    val encoderImplementation: String = "",
    val localCandidateType: String = "",
    val remoteCandidateType: String = "",
    val rttMs: Double = 0.0,
    val sampledAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        /** 传输模式取值（UI 显示用）。 */
        const val TYPE_P2P = "P2P"
        const val TYPE_RELAY = "RELAY"
    }
}
