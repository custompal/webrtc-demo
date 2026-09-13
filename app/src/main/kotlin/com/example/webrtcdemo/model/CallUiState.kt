package com.example.webrtcdemo.model

// ============================================================================
// 通话 UI 状态（doc/10 §5.2 + doc/14 §7.4 的数据源合并）
// 由 CallViewModel 单向驱动；Compose 侧 collectAsState 观察。
// ============================================================================

/**
 * 通话界面状态。
 *
 * @property roomId 房间号。
 * @property role `host` / `joiner`。
 * @property connectionState ICE 连接状态的中文展示（连接中/已连接/失败…）。
 * @property selfPeerId 本端 peerId（D-3 推导）。
 * @property remotePeerId 对端 peerId。
 * @property connectionType `P2P` / `RELAY` / 空（未判定）。
 * @property upBitrate 上行速率 bps。
 * @property downBitrate 下行速率 bps。
 * @property availableOutgoingBitrate 可用上行带宽 bps。
 * @property localNatType 本端 NAT（空串表示「检测中…」）。
 * @property remoteNatType 对端 NAT（来自信令 `natType`）。
 * @property encoderBitrate 编码器目标码率 bps（来自 `Vp9VideoEncoder.setRateAllocation` 的 total）。
 * @property encoderImplementation 编码实现名（期望 `SelfVp9Libvpx`）。
 * @property isMuted 是否静音（本地麦克风）。
 * @property isCameraOn 摄像头是否开启。
 * @property isRemoteVideoReady 是否已收到远端首帧（`SurfaceViewRenderer.onFirstFrameRendered`）。
 * @property isConnecting 是否处于连接中（显示 LoadingOverlay）。
 * @property error 错误信息（非空则 UI 提示）。
 */
data class CallUiState(
    val roomId: String = "",
    val role: String = "",
    val connectionState: String = "连接中",
    /** 本端 peerId（D-3 推导；未收到 peerJoined 时为空串，UI 显示 —）。 */
    val selfPeerId: String = "",
    /** 对端 peerId（joined/peerJoined 下发；未知时为空串）。 */
    val remotePeerId: String = "",
    val connectionType: String = "",
    val upBitrate: Int = 0,
    val downBitrate: Int = 0,
    val availableOutgoingBitrate: Int = 0,
    val localNatType: String = "",
    val remoteNatType: String = "",
    val encoderBitrate: Int = 0,
    val encoderImplementation: String = "",
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = true,
    val isRemoteVideoReady: Boolean = false,
    val isConnecting: Boolean = true,
    val error: String? = null,
)
