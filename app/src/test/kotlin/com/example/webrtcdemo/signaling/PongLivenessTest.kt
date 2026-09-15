package com.example.webrtcdemo.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t68：pong 存活判定（单次丢失容忍）的 JUnit4 回归
// ----------------------------------------------------------------------------
// 真机缺陷（dl-b 2026-09-15）：
//   15:27:45.779 `ws_pong_timeout timeout_ms=5000`  ← 旧口径：**单次** 5 s 没等到 pong 就判断线
//   15:27:45.781 `ws_reconnect_scheduled attempt=1 reason=pong_timeout`
//   15:27:47.722 `remote_frame_liveness age_ms=701 down_bps=326398 frames=1707`  ← 媒体其实还在流
//   15:28:02.248 `server_error ROOM_NOT_FOUND` → `call_end` + `hangup`          ← 房间被回收后自动退房
// 现在：单次丢失只记 `pong_miss count=N` 并重发 ping 继续等；连续 PONG_MISS_TOLERANCE 次才断线
// （有效阈值 = 5 s × 4 = 20 s ≥ 验收要求的 20 s）。
//
// 纯 JVM 可跑：pongMissed/pongTimeoutReached 都是 companion 里的纯函数，不触碰 Android API。
// 运行方式（宿主机）：./gradlew :app:testDebugUnitTest --tests '*PongLivenessTest'
// ============================================================================

class PongLivenessTest {

    // ==================== 有效阈值：验收要求 ≥ 20 s ====================

    @Test
    fun failAfterIsAtLeast20Seconds() {
        assertEquals(5_000L, SignalingClient.PONG_TIMEOUT_MS)
        assertEquals(4, SignalingClient.PONG_MISS_TOLERANCE)
        assertEquals(20_000L, SignalingClient.PONG_FAIL_AFTER_MS)
        assertTrue(
            "连续丢失容忍的**有效**判活阈值必须 ≥ 20 s（验收要求）",
            SignalingClient.PONG_FAIL_AFTER_MS >= 20_000L,
        )
    }

    // ==================== 单窗口丢失判定（纯函数） ====================

    @Test
    fun pongMissedOnlyAfterWindowTimeout() {
        val sentAt = 1_000_000L
        val noPong = 0L
        // 未到窗口时长：不算丢
        assertFalse(SignalingClient.pongMissed(sentAt + 4_999L, sentAt, noPong))
        // 恰好到窗口时长：未**超过** ⇒ 仍不算丢（避免边界上重复计数）
        assertFalse(SignalingClient.pongMissed(sentAt + 5_000L, sentAt, noPong))
        // 超过窗口：算丢
        assertTrue(SignalingClient.pongMissed(sentAt + 5_001L, sentAt, noPong))
    }

    @Test
    fun pongReceivedDuringWindowIsNotAMiss() {
        val sentAt = 1_000_000L
        // 该窗口内收到过 pong（lastPongAtMs >= pingSentAtMs）⇒ 不算丢
        assertFalse(SignalingClient.pongMissed(sentAt + 9_000L, sentAt, lastPongAtMs = sentAt + 10L))
        // 甚至"收到 pong 的时刻恰好等于发 ping 的时刻"也不算丢
        assertFalse(SignalingClient.pongMissed(sentAt + 9_000L, sentAt, lastPongAtMs = sentAt))
    }

    @Test
    fun noPendingPingIsNeverAMiss() {
        // pingSentAtMs == 0 表示没有待回应的 ping（例如刚连上/刚判活）⇒ 恒不判丢
        assertFalse(SignalingClient.pongMissed(9_999_999L, pingSentAtMs = 0L, lastPongAtMs = 0L))
    }

    // ==================== 连续丢失 → 断线阈值（核心口径变化） ====================

    @Test
    fun singleMissDoesNotDeclareTransportDead() {
        // 旧口径（RED）：1 次丢失即断线。新口径（GREEN）：只有达到容忍数才断线。
        assertFalse("单次 pong 丢失绝不能判断线（真机 dl-b 根因）", SignalingClient.pongTimeoutReached(1))
        assertFalse(SignalingClient.pongTimeoutReached(2))
        assertFalse(SignalingClient.pongTimeoutReached(3))
    }

    @Test
    fun consecutiveMissesUpToToleranceDeclareTransportDead() {
        assertTrue(SignalingClient.pongTimeoutReached(4))
        assertTrue(SignalingClient.pongTimeoutReached(5))
    }

    @Test
    fun toleranceIsParameterizable() {
        // 容忍上限可注入（便于后续按网络类型调整而无需改测试语义）
        assertTrue(SignalingClient.pongTimeoutReached(2, tolerance = 2))
        assertFalse(SignalingClient.pongTimeoutReached(1, tolerance = 2))
    }

    /**
     * dl-b 时间线回放：把"每 5 s 一个存活窗口、期间没有 pong"序列化，钉住
     * "单次丢失不断线 + 第 4 次才断线"的完整演进（count 逐次递增、判定只在第 4 次为真）。
     */
    @Test
    fun replayOfDlbTimeline_ToleratesFirstThreeMissesOnly() {
        var pingSentAt = 1_000_000L
        var lastPongAt = pingSentAt - 1_000L
        var misses = 0
        val declaredDeadAt = mutableListOf<Int>()
        // 4 个窗口，逐一"超时":每次超时后按实现重发 ping（把窗口推进到下一段）
        repeat(4) { index ->
            val now = pingSentAt + SignalingClient.PONG_TIMEOUT_MS + 1L
            assertTrue("第 ${index + 1} 个窗口应判定为丢失", SignalingClient.pongMissed(now, pingSentAt, lastPongAt))
            misses += 1
            if (SignalingClient.pongTimeoutReached(misses)) declaredDeadAt.add(misses)
            // 容忍期内实现会重发 ping（窗口推进）；达到阈值则会断线并清零
            pingSentAt = now
            lastPongAt = now - 1_000L
        }
        assertEquals("只有第 4 次（≈20 s）才允许判断线", listOf(4), declaredDeadAt)
        assertEquals(4, misses)
    }
}
