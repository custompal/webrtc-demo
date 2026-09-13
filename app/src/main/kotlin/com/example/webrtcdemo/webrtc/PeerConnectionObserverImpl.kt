package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.log.AppLog
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
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
// 其余带默认实现的方法（onTrack / onConnectionChange / onSelectedCandidatePairChanged 等）
// 不覆盖，避免依赖不确定的默认实现形态。
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

    private companion object {
        const val TAG = "pc"
    }
}
