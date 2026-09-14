package com.example.webrtcdemo.ui.call

import com.example.webrtcdemo.webrtc.SessionLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t53：`CallSessionSlot` 纯 JVM 单测（无 Android 依赖）
// ----------------------------------------------------------------------------
// 覆盖验收第 2 条与第 4 条：**重复 initCall / 连续会话**的生命周期状态机
//   * 同一次通话的重复 `initCall`（配置变更重建组合）必须**空操作**，不得拆掉正在进行的通话；
//   * 换房间 / 上一次通话已结束后再次 `initCall` ⇒ 开**新一世代**，且 `beginCall()` 明确
//     告知"上一代仍活跃"⇒ 调用方必须 close 旧 `CallSession`（绝不复用其 PC）；
//   * 换代时 `peerReady` 必须清零 —— 新一代的"已就绪"只能由新一代自己的 PC 建立；
//   * 连续 N 次通话：每次新建一个 PeerConnection，旧会话先被关闭（与 `SessionLifecycle` 联测）。
//
// 真机对照（reports/23-session-lifecycle.md §3）：修复前 `if (started) return` 会让新房间
// 跑在上一次通话遗留的 CallSession/PeerConnection 上。
//
// 运行（宿主机，容器内无 JDK）：./gradlew --no-daemon :app:testDebugUnitTest
// ============================================================================

class CallSessionSlotTest {

    /** 首次 initCall：开第 1 代，没有上一代需要关闭，PC 尚未就绪。 */
    @Test
    fun firstInitStartsFirstGeneration() {
        val slot = CallSessionSlot()

        assertEquals(InitDecision.NEW_CALL, slot.decideInit("ROOM1", "joiner", callEnded = false))
        assertFalse(slot.beginCall("ROOM1", "joiner"))

        assertEquals(1, slot.generation)
        assertTrue(slot.hasActiveSession)
        assertFalse(slot.peerReady)
        assertEquals("ROOM1", slot.roomId)
        assertEquals("joiner", slot.role)
    }

    /** 同一次通话的重复 initCall（Activity 配置变更重建组合）必须保持空操作。 */
    @Test
    fun sameCallRepeatInitIsIgnored() {
        val slot = CallSessionSlot()
        slot.beginCall("ROOM1", "joiner")
        slot.markPeerReady()

        assertEquals(InitDecision.IGNORE, slot.decideInit("ROOM1", "joiner", callEnded = false))
        // 幂等：不换代、不动已就绪标记
        assertEquals(1, slot.generation)
        assertTrue(slot.peerReady)
    }

    /** 同一房间但已经挂断（callEnded）：再次 initCall 必须开新一代。 */
    @Test
    fun sameRoomAfterHangupOpensNewGeneration() {
        val slot = CallSessionSlot()
        slot.beginCall("ROOM1", "joiner")
        slot.markPeerReady()

        assertEquals(InitDecision.NEW_CALL, slot.decideInit("ROOM1", "joiner", callEnded = true))

        slot.endCall()
        assertEquals(InitDecision.NEW_CALL, slot.decideInit("ROOM1", "joiner", callEnded = true))
    }

    /** 换了房间：必须开新一代，并明确告知"上一代仍活跃 ⇒ 必须先 close"。 */
    @Test
    fun differentRoomOpensNewGenerationAndMustCloseOld() {
        val slot = CallSessionSlot()
        slot.beginCall("ROOM1", "joiner")
        slot.markPeerReady()

        assertEquals(InitDecision.NEW_CALL, slot.decideInit("ROOM2", "joiner", callEnded = false))
        assertTrue(slot.beginCall("ROOM2", "joiner"))

        assertEquals(2, slot.generation)
        assertEquals("ROOM2", slot.roomId)
    }

    /** 角色变化（host ↔ joiner）同样算新通话。 */
    @Test
    fun roleChangeOpensNewGeneration() {
        val slot = CallSessionSlot()
        slot.beginCall("ROOM1", "joiner")

        assertEquals(InitDecision.NEW_CALL, slot.decideInit("ROOM1", "host", callEnded = false))
    }

    /** 换代必须清零 `peerReady`：新一代绝不复用上一代的"已就绪"状态。 */
    @Test
    fun newGenerationNeverInheritsPeerReady() {
        val slot = CallSessionSlot()
        slot.beginCall("ROOM1", "joiner")
        slot.markPeerReady()
        assertTrue(slot.peerReady)

        assertTrue(slot.beginCall("ROOM2", "joiner"))

        assertFalse(slot.peerReady)
        slot.markPeerReady()
        assertTrue(slot.peerReady)
    }

    /** `endCall` 幂等，且结束后不再允许给已结束的通话打"就绪"标记。 */
    @Test
    fun endCallIsIdempotentAndBlocksLateMarkReady() {
        val slot = CallSessionSlot()
        slot.beginCall("ROOM1", "joiner")

        assertTrue(slot.endCall())
        assertFalse(slot.endCall())
        assertFalse(slot.hasActiveSession)

        slot.markPeerReady()
        assertFalse(slot.peerReady)
    }

    /**
     * 连续 5 次通话（验收第 2/4/6 条的核心断言）：
     * 每次都是**新会话 + 新建 PeerConnection**，且上一代会话**必先被关闭**。
     */
    @Test
    fun consecutiveCallsAlwaysUseFreshSessionAndClosePrevious() {
        val slot = CallSessionSlot()
        var activeLifecycle: SessionLifecycle? = null
        var peerConnectionCreatedCount = 0
        var sessionClosedCount = 0

        for (n in 1..5) {
            val room = "ROOM$n"
            assertEquals(InitDecision.NEW_CALL, slot.decideInit(room, "joiner", callEnded = true))

            val staleActive = slot.beginCall(room, "joiner")
            if (staleActive) {
                val stale = activeLifecycle
                assertTrue(stale != null)
                if (stale != null) {
                    // 旧会话必须被 close()（SessionLifecycle.markClosed 幂等）
                    assertTrue(stale.markClosed())
                    sessionClosedCount++
                }
            }

            assertEquals(n, slot.generation)
            assertFalse(slot.peerReady)

            // 新会话必**新建** PeerConnection（beginStart 只成功一次）
            val lifecycle = SessionLifecycle()
            assertTrue(lifecycle.beginStart())
            peerConnectionCreatedCount++
            assertFalse(lifecycle.beginStart())

            assertTrue(lifecycle.markReady())
            slot.markPeerReady()
            assertTrue(slot.peerReady)

            activeLifecycle = lifecycle
        }

        assertEquals(5, peerConnectionCreatedCount)
        assertEquals(4, sessionClosedCount)
        assertEquals(5, slot.generation)
    }
}
