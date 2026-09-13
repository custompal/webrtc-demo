package com.example.webrtcdemo.model

// ============================================================================
// ICE 事件（doc/14 §7.4：来源为 org.webrtc.PeerConnection.Observer 的公开回调）
// 约定（doc/02 §5、doc/03 阶段 5）：事件在 App 侧累积，**通话结束才清空**。
// ============================================================================

/**
 * ICE 事件类型。
 */
enum class IceEventType {
    /** 本地候选（`onIceCandidate`）。 */
    CANDIDATE,

    /** ICE 连接状态变化（`onIceConnectionChange`）。 */
    ICE_CONNECTION,

    /** ICE 收集状态变化（`onIceGatheringChange`）。 */
    ICE_GATHERING,

    /** 候选对变化（`onIceCandidatePairChange`）。 */
    CANDIDATE_PAIR,

    /** 候选收集结束（`onIceGatheringChange(COMPLETE)` 时补一条）。 */
    END_OF_CANDIDATES,

    /** 选中的传输路径（来自 2 s 一次的 stats：P2P / RELAY）。 */
    SELECTED_PAIR,
}

/**
 * 一条 ICE 事件记录。
 *
 * @property type 事件类型。
 * @property detail 事件细节（候选文本/状态名/`local->remote` 等）。
 * @property timestampMs 发生时刻（Unix 毫秒）。
 */
data class IceEvent(
    val type: IceEventType,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    /** 供诊断页/日志使用的单行摘要。 */
    fun summary(): String = "${type.name} $detail"
}
