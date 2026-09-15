package com.example.webrtcdemo.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t71①：socket 重连预算 vs 服务端房间宽限期（复用 rejoin 退避口径，不造平行常量）
// ----------------------------------------------------------------------------
// 服务端（go-dev，t67/t70）已把「WS 断开」与「离开房间」解耦：断线后**保留席位 90 s**、宽限期内
// **不下发 peerLeft**（活体实测：硬断开 8.1 s 内对端收到 peerLeft 次数 = 0；90 s 期满才发一次）。
// 修复前客户端 socket 重连是"固定 3 s × 3 次 ≈ 9–12 s"，3 次失败即把 DISCONNECTED 当终态
// ⇒ 服务端仍保留席位、对端未收到 peerLeft、RTP 仍在流时**自杀式退房**（真机 dl-b 形态）。
//
// t71 口径：socket 重连**复用既有 `rejoinDelayMs`**（1/2/4/8 s 封顶）与 10 次上限 ⇒ 总预算 63 s
// ⇒ ≥60 s 且 < 服务端 90 s 宽限期；**不新增第二套常量**（本测试直接断言二者同源）。
//
// 纯 JVM 可跑：全是 companion 里的纯函数，不触碰 Android API。
// ============================================================================

class ReconnectBudgetTest {

    // ==================== ① 退避序列与上限（1/2/4/8 s 封顶） ====================

    @Test
    fun reconnectDelayIsCappedAndMonotonic() {
        assertEquals(1_000L, SignalingClient.rejoinDelayMs(1))
        assertEquals(2_000L, SignalingClient.rejoinDelayMs(2))
        assertEquals(4_000L, SignalingClient.rejoinDelayMs(3))
        assertEquals(8_000L, SignalingClient.rejoinDelayMs(4))
        assertEquals(8_000L, SignalingClient.rejoinDelayMs(10))
        assertEquals(8_000L, SignalingClient.rejoinDelayMs(50))
        for (attempt in 2..12) {
            assertTrue(
                "退避必须单调不减（attempt=$attempt）",
                SignalingClient.rejoinDelayMs(attempt) >= SignalingClient.rejoinDelayMs(attempt - 1),
            )
        }
        assertEquals(8_000L, SignalingClient.REJOIN_RETRY_MAX_MS)
    }

    // ============ ① 总预算：≥60 s 且 < 75 s（t74 口径；75 s 来自 go-dev reports/35 §5.3） ============
    // 依据：宽限期的计时起点是"**服务端察觉断开**"——客户端显式关闭旧 socket 时可用预算 ≤75 s，
    // 静默掉线（不关旧 socket）时只剩 45 s。故上限取 **75 s**（t71 早期文本写的 <90 s 已按本口径收紧）。

    @Test
    fun reconnectBudgetIsStrictlyBelowSeventyFiveSeconds() {
        val budget = SignalingClient.rejoinBudgetMs()
        assertEquals(63_000L, budget)
        assertTrue("总预算必须 ≥60 s（实测 ${budget} ms）", budget >= 60_000L)
        assertTrue("总预算必须 < 75 s（可用预算上限，实测 ${budget} ms）", budget < 75_000L)
    }

    @Test
    fun socketReconnectReusesRejoinBudgetWithoutParallelConstants() {
        // t71 明确要求"复用 rejoin 口径、不造平行常量"：socket 重连的次数上限必须与 rejoin 完全同源
        assertEquals(10, SignalingClient.MAX_REJOIN_ATTEMPTS)
        // 逐次退避之和 = 预算（生产代码 scheduleReconnect 调用的就是 rejoinDelayMs + MAX_REJOIN_ATTEMPTS）
        var sum = 0L
        for (attempt in 1..SignalingClient.MAX_REJOIN_ATTEMPTS) sum += SignalingClient.rejoinDelayMs(attempt)
        assertEquals(SignalingClient.rejoinBudgetMs(), sum)
        // 修复前那套"固定 3 s"平行常量与独立次数常量必须已删除（防回退/防平行口径）
        val leftover = SignalingClient::class.java.declaredFields
            .map { it.name }
            .filter { it == "RECONNECT_DELAY_MS" || it == "RECONNECT_MAX_DELAY_MS" || it == "MAX_RECONNECT_ATTEMPTS" }
        assertEquals("不得再存在第二套 socket 重连常量", emptyList<String>(), leftover)
    }

    @Test
    fun reconnectBudgetExceedsServerReadTimeout() {
        // 服务端静默掉线察觉窗口 45 s（-pong-wait）；预算必须覆盖它
        assertTrue(SignalingClient.rejoinBudgetMs() > 45_000L)
    }

    /**
     * old-red 对照（修复前语义）：预算只有 3 s × 3 次 = 9 s ⇒ 既不覆盖 45 s 读超时，也远早于
     * 90 s 宽限期就放弃 ⇒ 客户端自杀式退房。本用例把"旧口径不满足硬要求"固化为事实（回退即红）。
     */
    @Test
    fun oldThreeAttemptBudgetWouldViolateTheRequirement() {
        val oldBudget = 3_000L * 3
        assertEquals(9_000L, oldBudget)
        assertFalse("旧口径（9 s）不满足 ≥60 s 的硬要求，必须已改", oldBudget >= 60_000L)
        assertTrue("新口径必须覆盖住旧口径", SignalingClient.rejoinBudgetMs() > oldBudget)
    }
}
