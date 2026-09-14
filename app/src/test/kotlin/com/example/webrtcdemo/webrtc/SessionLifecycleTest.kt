package com.example.webrtcdemo.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t53：`SessionLifecycle` 纯 JVM 单测（无 Android / org.webrtc 依赖）
// ----------------------------------------------------------------------------
// 覆盖验收第 2 条（每次通话都用**新建** PeerConnection、`pc_created` 必早于 `answer_create`）
// 与第 6 条（连续会话状态机）。核心不变量：
//   * `beginStart()` 只成功一次 ⇒ 同一次会话绝不复用已有 PeerConnection；
//   * `markReady()`（= `pc_created` 发布点）之前收到的远端 offer/answer/候选**一律暂存**（DEFER）
//     —— 这就是"answer 绝不会在本地视频轨挂载之前生成"的形式化保证；
//   * `markClosed()` 之后一律丢弃（DROP），且关闭后不可复活。
//
// 真机对照（room U9FQHG，reports/23-session-lifecycle.md §1）：
//   修复前 `answer_create`(17:37:06.929) 早于 `pc_created`(17:37:07.031) 102 ms；
//   修复后该序列在状态机上不可能出现（见 readyMarkerAlwaysPrecedesAnswer）。
//
// 运行（宿主机，容器内无 JDK）：./gradlew --no-daemon :app:testDebugUnitTest
// ============================================================================

class SessionLifecycleTest {

    /** 会话刚建立（NEW）：任何远端消息都必须暂存，不能立即处理。 */
    @Test
    fun newSessionDefersRemoteMessages() {
        val lifecycle = SessionLifecycle()

        assertEquals(SessionPhase.NEW, lifecycle.phase)
        assertFalse(lifecycle.isReady)
        assertEquals(SessionGate.DEFER, lifecycle.admit())
    }

    /** `start()` 执行中（STARTING）：PC 已建但本地轨未挂载 —— 仍然必须暂存。 */
    @Test
    fun startingPhaseStillDefers() {
        val lifecycle = SessionLifecycle()

        assertTrue(lifecycle.beginStart())
        assertEquals(SessionPhase.STARTING, lifecycle.phase)
        assertTrue(lifecycle.peerConnectionCreated)
        assertFalse(lifecycle.isReady)
        assertEquals(SessionGate.DEFER, lifecycle.admit())
    }

    /** 发布点之后（READY）：可以立即处理远端 offer/answer/候选。 */
    @Test
    fun readyPhaseProceeds() {
        val lifecycle = SessionLifecycle()

        lifecycle.beginStart()
        assertTrue(lifecycle.markReady())

        assertEquals(SessionPhase.READY, lifecycle.phase)
        assertTrue(lifecycle.isReady)
        assertEquals(SessionGate.PROCEED, lifecycle.admit())
    }

    /** 未开始就 markReady 无效（防止绕过 `beginStart` 直接置就绪）。 */
    @Test
    fun markReadyBeforeStartIsRejected() {
        val lifecycle = SessionLifecycle()

        assertFalse(lifecycle.markReady())
        assertEquals(SessionPhase.NEW, lifecycle.phase)
        assertEquals(SessionGate.DEFER, lifecycle.admit())
    }

    /** 同一次会话只允许建立一次 PeerConnection：第二次 `start()` 必须被拒绝（绝不复用旧 PC）。 */
    @Test
    fun secondBeginStartIsRejected() {
        val lifecycle = SessionLifecycle()

        assertTrue(lifecycle.beginStart())
        assertFalse(lifecycle.beginStart())
        assertEquals(SessionPhase.STARTING, lifecycle.phase)
        assertTrue(lifecycle.peerConnectionCreated)
    }

    /** 已关闭的会话不得再建 PC。 */
    @Test
    fun closedSessionRejectsStart() {
        val lifecycle = SessionLifecycle()

        lifecycle.beginStart()
        assertTrue(lifecycle.markClosed())
        assertFalse(lifecycle.beginStart())
        assertEquals(SessionPhase.CLOSED, lifecycle.phase)
    }

    /** 关闭后任何远端消息一律丢弃（既不暂存也不回放）。 */
    @Test
    fun closedSessionDropsRemoteMessages() {
        val lifecycle = SessionLifecycle()

        lifecycle.beginStart()
        lifecycle.markReady()
        assertEquals(SessionGate.PROCEED, lifecycle.admit())
        lifecycle.markClosed()

        assertEquals(SessionGate.DROP, lifecycle.admit())
        assertFalse(lifecycle.isReady)
    }

    /** markClosed 幂等：重复关闭返回 false。 */
    @Test
    fun markClosedIsIdempotent() {
        val lifecycle = SessionLifecycle()

        lifecycle.beginStart()
        assertTrue(lifecycle.markClosed())
        assertFalse(lifecycle.markClosed())
    }

    /** `start()` 期间被挂断：关闭之后的 markReady 不得让会话复活。 */
    @Test
    fun markReadyAfterCloseDoesNotRevive() {
        val lifecycle = SessionLifecycle()

        lifecycle.beginStart()
        lifecycle.markClosed()
        assertFalse(lifecycle.markReady())

        assertEquals(SessionPhase.CLOSED, lifecycle.phase)
        assertEquals(SessionGate.DROP, lifecycle.admit())
    }

    /**
     * 复刻真机 U9FQHG 的时序：offer 在 `start()` 窗口内到达时必须暂存，
     * `answer_create` 只可能发生在 `pc_created`（发布点）之后。
     */
    @Test
    fun readyMarkerAlwaysPrecedesAnswer() {
        val lifecycle = SessionLifecycle()
        val trace = ArrayList<String>()

        // start()：主线程进入建 PC 阶段（本地媒体尚未挂载）
        assertTrue(lifecycle.beginStart())
        trace += "pc_starting"

        // 期间信令线程送来远端 offer
        assertEquals(SessionGate.DEFER, lifecycle.admit())
        trace += "offer_deferred"

        // start() 继续：本地音视频轨挂好 → 发布点
        assertTrue(lifecycle.markReady())
        trace += "pc_created"

        // 回放暂存的 offer
        assertEquals(SessionGate.PROCEED, lifecycle.admit())
        trace += "answer_create"

        assertEquals(
            listOf("pc_starting", "offer_deferred", "pc_created", "answer_create"),
            trace,
        )
        assertTrue(trace.indexOf("pc_created") < trace.indexOf("answer_create"))
    }
}
