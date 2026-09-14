package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.log.AppLog
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver

// ============================================================================
// PeerConnection.Observer 实现（doc/14 §2.1 / §7.4）
// ----------------------------------------------------------------------------
// §7.4 冻结：ICE candidate / ICE 连接状态 / ICE gathering **由本类从 org.webrtc 直接获得**，
// 不再跨 JNI（§6.5 已取消 onIceCandidate / onIceConnectionChange / onStatsReport 等旧回调面）。
//
// 实现集合取 org.webrtc M129 的 PeerConnection.Observer 经典必需集
// （与官方 AppRTC 的 PeerConnectionClient.Observer 一致）；
// 【t44 诊断加固】额外覆盖三个**默认实现**的回调（本版 jar 实测存在：
// `javap org.webrtc.PeerConnection$Observer` 列出 onConnectionChange /
// onSelectedCandidatePairChanged / onIceCandidateError）：
//   onConnectionChange(PeerConnectionState)                    → 传输/DTLS 是否真正建立（CONNECTED）
//   onSelectedCandidatePairChanged(CandidatePairChangeEvent)   → 被选中的候选对（本地/远端类型）
//   onIceCandidateError(IceCandidateErrorEvent)                → 候选/检查错误（STUN/TURN 层）
// 真机「连不通」时，这三者是把"停在连通性检查"与"根本没建链"区分开的关键证据。
// ============================================================================

/**
 * PeerConnection 事件观察者：把 SDK 回调转成 [Events] 上的普通方法。
 */
class PeerConnectionObserverImpl(private val events: Events) : PeerConnection.Observer {

    /**
     * 事件接收方（由 [CallSession] 实现）。
     */
    interface Events {
        /** 本地 ICE candidate（需经信令发给对端）。 */
        fun onIceCandidate(candidate: IceCandidate)

        /** ICE 连接状态变化（UI 状态面板）。 */
        fun onIceConnectionState(state: PeerConnection.IceConnectionState)

        /** ICE 收集状态变化。 */
        fun onIceGatheringState(state: PeerConnection.IceGatheringState)

        /** 信令状态变化。 */
        fun onSignalingState(state: PeerConnection.SignalingState)

        /** 是否在接收 ICE。 */
        fun onIceConnectionReceiving(receiving: Boolean)

        /** 被移除的候选（一般无需处理）。 */
        fun onIceCandidatesRemoved(candidates: Array<IceCandidate>)

        /** 远端新增媒体流（Plan-B 兼容路径，Unified Plan 下通常为空实现）。 */
        fun onAddStream(stream: MediaStream)

        /** 远端移除媒体流。 */
        fun onRemoveStream(stream: MediaStream)

        /** 数据通道（本项目未使用）。 */
        fun onDataChannel(channel: DataChannel)

        /** 需要重新协商（如加入/移除轨道）。 */
        fun onRenegotiationNeeded()

        /** 新增远端轨道接收器（Unified Plan 路径）。 */
        fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>)

        /** 传输层（含 DTLS）状态变化：`CONNECTED` 才代表 DTLS 握手成功（t44）。 */
        fun onConnectionChange(state: PeerConnection.PeerConnectionState) = Unit

        /** 选中的候选对变化：本地/远端候选类型（t44；`host/srflx/relay`）。 */
        fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit

        /** 候选/检查层错误（STUN/TURN 分配失败、绑定失败等，t44）。 */
        fun onIceCandidateError(event: IceCandidateErrorEvent) = Unit
    }

    override fun onSignalingChange(newState: PeerConnection.SignalingState) {
        AppLog.d(TAG, "pc_signaling_state", mapOf("state" to newState.name))
        events.onSignalingState(newState)
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
        AppLog.i(TAG, "pc_ice_connection_state", mapOf("state" to newState.name))
        events.onIceConnectionState(newState)
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        events.onIceConnectionReceiving(receiving)
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
        AppLog.i(TAG, "pc_ice_gathering_state", mapOf("state" to newState.name))
        events.onIceGatheringState(newState)
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        events.onIceCandidate(candidate)
    }

    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
        events.onIceCandidatesRemoved(candidates)
    }

    override fun onAddStream(stream: MediaStream) {
        events.onAddStream(stream)
    }

    override fun onRemoveStream(stream: MediaStream) {
        events.onRemoveStream(stream)
    }

    override fun onDataChannel(dataChannel: DataChannel) {
        events.onDataChannel(dataChannel)
    }

    override fun onRenegotiationNeeded() {
        events.onRenegotiationNeeded()
    }

    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
        AppLog.i(TAG, "pc_add_track", mapOf("kind" to (receiver.track()?.kind() ?: "-")))
        events.onAddTrack(receiver, mediaStreams)
    }

    /**
     * 传输层状态（含 DTLS 握手结果）。
     *
     * `CONNECTED` 出现即代表 ICE 已完成**且** DTLS 握手成功；真机"停在正在连接会议"
     * 时该事件通常只会看到 `CONNECTING`/`FAILED` —— 这是判定"是否真的建链"的第一手证据（t44）。
     */
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        val fields = mapOf(
            "state" to newState.name,
            // 本版 org.webrtc 没有独立的 "dtls_state" 回调：**DTLS 完成即传输 CONNECTED**
            // （ICE 完成后立即进入 DTLS 握手），故这里显式标注二者的关系，便于日志检索。
            "dtls" to (newState == PeerConnection.PeerConnectionState.CONNECTED).toString(),
        )
        if (newState == PeerConnection.PeerConnectionState.FAILED) {
            AppLog.e(TAG, "pc_connection_state", fields)
        } else {
            AppLog.i(TAG, "pc_connection_state", fields)
        }
        events.onConnectionChange(newState)
    }

    /** 选中的候选对（本地/远端类型 + 地址端口），决定 P2P 还是 RELAY（t44）。 */
    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
        val local = IceCandidateInfo.parse(event.local?.sdp ?: "")
        val remote = IceCandidateInfo.parse(event.remote?.sdp ?: "")
        AppLog.i(
            TAG,
            "selected_candidate_pair",
            mapOf(
                "local" to local.summary(),
                "remote" to remote.summary(),
                "mode" to if (local.isRelay() || remote.isRelay()) "RELAY" else "P2P",
                "reason" to (event.reason ?: "-"),
                "last_data_ms" to event.lastDataReceivedMs.toString(),
            ),
        )
        events.onSelectedCandidatePairChanged(event)
    }

    /** 候选/检查错误（STUN 绑定失败、TURN 分配失败等）——真机连不通时必须能看到（t44）。 */
    override fun onIceCandidateError(event: IceCandidateErrorEvent) {
        AppLog.w(
            TAG,
            "pc_ice_candidate_error",
            mapOf(
                "url" to (event.url ?: "-"),
                "address" to (event.address ?: "-"),
                "port" to event.port.toString(),
                "code" to event.errorCode.toString(),
                "text" to (event.errorText ?: "-"),
            ),
        )
        events.onIceCandidateError(event)
    }

    private companion object {
        const val TAG = "pc"
    }
}
