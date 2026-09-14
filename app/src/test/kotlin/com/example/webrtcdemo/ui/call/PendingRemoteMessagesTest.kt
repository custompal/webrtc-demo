package com.example.webrtcdemo.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t51：`PendingRemoteMessages` 纯 JVM 单测（无 Android / native 依赖）
// ----------------------------------------------------------------------------
// 覆盖验收第 5 条：**排队与回放顺序**。
//   * 回放顺序 == 到达顺序（offer / ice / ice / answer / ice）；
//   * offer、answer 各自**同类型覆盖**（只保留最新一条，避免把过期 SDP 回放）；
//   * ICE 候选**全部保留且保序**（丢一条就可能少一条 ICE 路径）；
//   * 有界（超出上限丢最旧并计数）。
// 运行（宿主机，容器内无 JDK）：./gradlew --no-daemon :app:testDebugUnitTest
// ============================================================================

class PendingRemoteMessagesTest {

    /** 回放顺序必须等于到达顺序（两级队列的核心不变量）。 */
    @Test
    fun preservesArrivalOrder() {
        val queue = PendingRemoteMessages()
        queue.enqueueOffer("offer-1")
        queue.enqueueIce("ice-1", "0", 0)
        queue.enqueueIce("ice-2", "0", 1)
        queue.enqueueAnswer("answer-1")
        queue.enqueueIce("ice-3", "1", 0)

        val drained = queue.drain()
        assertEquals(5, drained.size)
        assertEquals(
            listOf(
                PendingRemoteMessages.Kind.OFFER,
                PendingRemoteMessages.Kind.ICE,
                PendingRemoteMessages.Kind.ICE,
                PendingRemoteMessages.Kind.ANSWER,
                PendingRemoteMessages.Kind.ICE,
            ),
            drained.map { it.kind },
        )
        assertEquals(
            listOf("offer-1", "ice-1", "ice-2", "answer-1", "ice-3"),
            drained.map { it.sdpOrCandidate },
        )
        assertTrue("drain 后应清空", queue.isEmpty)
    }

    /** offer 同类型覆盖：只保留最新，且相对 ICE 的位置为"最后一次 offer 的到达位置"。 */
    @Test
    fun newerOfferReplacesOlderOffer() {
        val queue = PendingRemoteMessages()
        queue.enqueueOffer("offer-old")
        queue.enqueueIce("ice-1", "0", 0)
        queue.enqueueOffer("offer-new")

        val drained = queue.drain()
        assertEquals(2, drained.size)
        assertEquals(PendingRemoteMessages.Kind.ICE, drained[0].kind)
        assertEquals(PendingRemoteMessages.Kind.OFFER, drained[1].kind)
        assertEquals("offer-new", drained[1].sdpOrCandidate)
    }

    /** answer 同类型覆盖（重发/重协商以最后一条为准）。 */
    @Test
    fun newerAnswerReplacesOlderAnswer() {
        val queue = PendingRemoteMessages()
        queue.enqueueAnswer("answer-old")
        queue.enqueueAnswer("answer-new")

        val drained = queue.drain()
        assertEquals(1, drained.size)
        assertEquals("answer-new", drained[0].sdpOrCandidate)
    }

    /** ICE 候选全部保留且保序（含 mid / mLineIndex 字段）。 */
    @Test
    fun keepsAllIceCandidatesInOrder() {
        val queue = PendingRemoteMessages()
        queue.enqueueIce("c1", "0", 0)
        queue.enqueueIce("c2", "1", 1)
        queue.enqueueIce("c3", null, 0)

        val drained = queue.drain()
        assertEquals(3, drained.size)
        assertEquals(listOf("c1", "c2", "c3"), drained.map { it.sdpOrCandidate })
        assertEquals(listOf("0", "1", null), drained.map { it.sdpMid })
        assertEquals(listOf(0, 1, 0), drained.map { it.sdpMLineIndex })
    }

    /** 有界：超出上限丢最旧并计数，保留最近 maxEntries 条且保序。 */
    @Test
    fun boundedByMaxEntries() {
        val queue = PendingRemoteMessages(maxEntries = 3)
        for (i in 1..5) queue.enqueueIce("c$i", "0", 0)

        assertEquals(3, queue.size)
        assertEquals(2, queue.droppedByLimit)
        assertEquals(listOf("c3", "c4", "c5"), queue.drain().map { it.sdpOrCandidate })
    }

    /** 各类计数（诊断日志用）。 */
    @Test
    fun countsByKind() {
        val queue = PendingRemoteMessages()
        queue.enqueueOffer("o")
        queue.enqueueAnswer("a")
        queue.enqueueIce("c1", "0", 0)
        queue.enqueueIce("c2", "0", 1)

        val counts = queue.counts()
        assertEquals(1, counts[PendingRemoteMessages.Kind.OFFER])
        assertEquals(1, counts[PendingRemoteMessages.Kind.ANSWER])
        assertEquals(2, counts[PendingRemoteMessages.Kind.ICE])
    }

    /** 空队列 drain ⇒ 空列表（用于 `remote_replay_done ... offer=false candidates=0` 的正常路径）。 */
    @Test
    fun emptyQueueDrainsToEmptyList() {
        val queue = PendingRemoteMessages()
        assertTrue(queue.isEmpty)
        assertTrue(queue.drain().isEmpty())
        assertFalse(queue.droppedByLimit > 0)
    }
}
