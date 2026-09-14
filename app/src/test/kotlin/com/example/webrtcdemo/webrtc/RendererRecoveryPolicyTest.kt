package com.example.webrtcdemo.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t45：`RendererRecoveryPolicy` 纯 JVM 单测（无 Android / native 依赖）
// ----------------------------------------------------------------------------
// 覆盖验收语义：
//   * 切回前台 **3 秒内出帧 ⇒ 不重建**（NONE 且解除看门狗）；
//   * 3 秒仍无帧 ⇒ `RECOVER`（最多 2 次），随后 `GIVE_UP`（不再无限重建）；
//   * 切后台（onPause）后不再触发任何动作。
// 运行（宿主机，容器内无 JDK）：./gradlew --no-daemon :app:testDebugUnitTest
// ============================================================================

class RendererRecoveryPolicyTest {

    private val second = 1_000_000_000L

    /** 默认窗口 = 验收要求的 3 秒；默认最多 2 次自动恢复。 */
    @Test
    fun defaultsMatchAcceptance() {
        val policy = RendererRecoveryPolicy()
        assertEquals(3L * second, policy.windowNs)
        assertEquals(2, policy.maxAttempts)
    }

    /** 3 秒窗口内不动作；到期仍无帧 ⇒ 触发一次 RECOVER。 */
    @Test
    fun noFrameWithinWindowTriggersRecover() {
        val policy = RendererRecoveryPolicy()
        policy.onResume(0L)

        assertTrue("窗口未到期不应动作", policy.evaluate(2 * second, false) == RendererRecoveryPolicy.Action.NONE)
        assertTrue("窗口到期且无帧应恢复", policy.evaluate(3 * second, false) == RendererRecoveryPolicy.Action.RECOVER)
        assertEquals(1, policy.attempts)
    }

    /** 恢复后重新计时：不会在同一时刻连续重建。 */
    @Test
    fun recoverRestartsWindow() {
        val policy = RendererRecoveryPolicy()
        policy.onResume(0L)
        policy.evaluate(3 * second, false)

        // 刚恢复过 ⇒ 立刻再评估不应再次动作
        assertTrue(policy.evaluate(3 * second + 1, false) == RendererRecoveryPolicy.Action.NONE)
        // 再过一个窗口仍无帧 ⇒ 第二次恢复
        assertTrue(policy.evaluate(6 * second + 1, false) == RendererRecoveryPolicy.Action.RECOVER)
        assertEquals(2, policy.attempts)
    }

    /** 超过最大次数 ⇒ GIVE_UP（由调用方上报 UI/日志）。 */
    @Test
    fun givesUpAfterMaxAttempts() {
        val policy = RendererRecoveryPolicy()
        policy.onResume(0L)
        policy.evaluate(3 * second, false)
        policy.evaluate(6 * second, false)

        assertTrue(policy.evaluate(9 * second, false) == RendererRecoveryPolicy.Action.GIVE_UP)
        assertFalse("GIVE_UP 后解除看门狗", policy.armed)
    }

    /** 出帧即解除看门狗：后续即使再无帧也不再重建（避免误重建正常画面）。 */
    @Test
    fun frameDisarmsWatchdog() {
        val policy = RendererRecoveryPolicy()
        policy.onResume(0L)

        assertTrue(policy.evaluate(1 * second, true) == RendererRecoveryPolicy.Action.NONE)
        assertFalse(policy.armed)
        assertTrue("已解除后不再动作", policy.evaluate(30 * second, false) == RendererRecoveryPolicy.Action.NONE)
        assertEquals(0, policy.attempts)
    }

    /** 看门狗自身的 onFrame 计数同样能解除（供上层直接投喂帧事件时使用）。 */
    @Test
    fun reportedFrameDisarmsWatchdog() {
        val policy = RendererRecoveryPolicy()
        policy.onResume(0L)
        policy.onFrame()

        assertTrue(policy.evaluate(10 * second, false) == RendererRecoveryPolicy.Action.NONE)
        assertEquals(0, policy.attempts)
    }

    /** 切后台后彻底静默（回到前台需重新 onResume —— 与"切后台不 release"配套）。 */
    @Test
    fun pauseDisarmsUntilNextResume() {
        val policy = RendererRecoveryPolicy()
        policy.onResume(0L)
        policy.onPause()

        assertTrue(policy.evaluate(30 * second, false) == RendererRecoveryPolicy.Action.NONE)

        policy.onResume(31 * second)
        assertTrue(policy.evaluate(33 * second, false) == RendererRecoveryPolicy.Action.NONE)
        assertTrue(policy.evaluate(34 * second, false) == RendererRecoveryPolicy.Action.RECOVER)
    }
}
