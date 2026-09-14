package com.example.webrtcdemo.ui.call

// ============================================================================
// t51：**会话/PC 就绪前的远端消息预队列**（纯 Kotlin，可 JVM 单测，无 Android/native 依赖）
// ----------------------------------------------------------------------------
// 真机证据（room 66DZFT，宿主 /opt/dsh-workspaces/tmp/dl-b/x/app.log）：
//   16:18:34.677 room_joined(peer-002) → 16:18:34.953 offer_received sdp_bytes=2530
//   → 16:18:34.980 / 16:18:35.104 ice_received → **16:18:35.105 WARN ice_without_session mid=0**
//   → 16:18:35.232 engine_ready → 16:18:35.244 pc_starting → 16:18:35.397 remote_replay_done
//      answer=false candidates=0 offer=false
// ⇒ offer 与候选比 `CallSession.start()`（pc_starting）**早**约 0.3–0.5 s 到达；此时
//   `CallViewModel.session == null`，旧实现**只打一条日志就丢弃**（offer 分支还会弹一次
//   `onError`），导致 host 永远停在 HAVE_LOCAL_OFFER、信令侧只有 `offer_forward`。
//
// 设计：
//   * **保序**：所有远端消息按到达顺序入队，`drain()` 原序返回（回放顺序 = 到达顺序）；
//   * **OFFER/ANSWER 同类型覆盖**：同一次协商只保留**最新**的一条（信令语义：重发/重协商
//     以最后一条为准），避免把过期 SDP 回放给 PeerConnection；
//   * **ICE 全留**：候选可多条且必须逐条 `addIceCandidate`（丢一条就可能少一条路径）；
//   * **有界**：超过 [maxEntries] 时丢**最旧**一条并计数，避免异常流量无限占用内存；
//   * 由 `CallViewModel` 在 `session == null`（含 `start()` 失败后置空的窗口）时入队，
//     并在 `start()` 成功后 `drain()` 逐条回放。
// ============================================================================

/**
 * 远端消息预队列（offer / answer / ICE 候选），保持到达顺序。
 *
 * @param maxEntries 队列上限（超出丢最旧，默认 256 ≈ 足够覆盖任何正常协商窗口）。
 */
class PendingRemoteMessages(val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    /** 远端消息种类。 */
    enum class Kind { OFFER, ANSWER, ICE }

    /** 队列条目（`sdpOrCandidate`：OFFER/ANSWER 为 SDP，ICE 为候选字符串）。 */
    data class Entry(
        val kind: Kind,
        val sdpOrCandidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = 0,
    )

    private val queue = ArrayDeque<Entry>()

    /** 被上限挤掉的条数（诊断用）。 */
    var droppedByLimit: Int = 0
        private set

    /** 当前排队长度（供 `remote_deferred queued=N` 日志）。 */
    val size: Int get() = queue.size

    /** 是否为空。 */
    val isEmpty: Boolean get() = queue.isEmpty()

    /** 入队 offer；返回入队后的长度。 */
    fun enqueueOffer(sdp: String): Int = enqueue(Entry(Kind.OFFER, sdp))

    /** 入队 answer；返回入队后的长度。 */
    fun enqueueAnswer(sdp: String): Int = enqueue(Entry(Kind.ANSWER, sdp))

    /** 入队远端候选；返回入队后的长度。 */
    fun enqueueIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int): Int =
        enqueue(Entry(Kind.ICE, candidate, sdpMid, sdpMLineIndex))

    /**
     * 取出全部条目并清空（**按到达顺序**返回，调用方据此顺序回放）。
     */
    fun drain(): List<Entry> {
        val out = queue.toList()
        queue.clear()
        return out
    }

    /** 各类当前条数（诊断用）。 */
    fun counts(): Map<Kind, Int> = Kind.entries.associateWith { kind -> queue.count { it.kind == kind } }

    private fun enqueue(entry: Entry): Int {
        // 同类型覆盖：offer/answer 只保留最新；ICE 全部保留
        if (entry.kind != Kind.ICE) queue.removeAll { it.kind == entry.kind }
        if (queue.size >= maxEntries) {
            queue.removeFirst()
            droppedByLimit++
        }
        queue.addLast(entry)
        return queue.size
    }

    companion object {
        /** 默认上限（正常协商窗口下远不会触及）。 */
        const val DEFAULT_MAX_ENTRIES: Int = 256
    }
}
