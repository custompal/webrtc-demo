package com.example.webrtcdemo.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t87 点名单测：自研编码自动降级判据（纯 JVM，无 Android/无 native 依赖）
// ----------------------------------------------------------------------------
// 覆盖验收第 5 条点名的 4 条 + 边界：
//   ① healthyMetricsKeepSelf                 健康指标不切换
//   ② sustainedOverBudgetSwitchesToDefault   持续超预算 ⇒ 切换
//   ③ noSecondSwitchWithinHysteresisWindow   切换后 30 s 内不再切（滞回）
//   ④ atMostOneSwitchPerCall                 一轮通话最多切一次（幂等）
//   ⑤ warmupWindowKeepsSelf                  窗口未满不判定
//   ⑥ lowOutFpsAloneSwitchesToDefault        只靠产出帧率不足也能触发
//   ⑦ configMatchesAcceptanceThresholds      默认常量与验收数字逐条一致
//   ⑧ manualOverrideWins                    三态手动覆盖优先级
//   ⑨ p95UsesNinetyFifthPercentile           p95 口径
// ============================================================================

class EncoderFallbackPolicyTest {

    private val config = EncoderFallbackPolicy.Config()

    /** 30 fps 下的单帧预算 = 33.33 ms；健康样本 p95=8 ms、满产出 30 帧/s。 */
    private fun healthySample(fps: Int = 30): EncodeWindowSample = EncodeWindowSample(
        encodeP95Ms = 8,
        producedFrames = fps,
        noOutputFrames = 0,
        requestedFps = fps,
        windowMs = 1_000L,
    )

    /**
     * 构造一个「持续超预算」样本：p95 = 0.9 × 预算（> 0.6 × 预算），产出帧率仍达标。
     */
    private fun overBudgetSample(fps: Int = 30): EncodeWindowSample = EncodeWindowSample(
        encodeP95Ms = (0.9 * 1000.0 / fps).toInt(),
        producedFrames = fps,
        noOutputFrames = 0,
        requestedFps = fps,
        windowMs = 1_000L,
    )

    /** 构造一个「产出帧率不足」样本：p95 达标，但只产出 40% 的帧。 */
    private fun lowFpsSample(fps: Int = 30): EncodeWindowSample = EncodeWindowSample(
        encodeP95Ms = 8,
        producedFrames = (fps * 0.4).toInt(),
        noOutputFrames = fps - (fps * 0.4).toInt(),
        requestedFps = fps,
        windowMs = 1_000L,
    )

    @Test
    fun healthyMetricsKeepSelf() {
        val history = List(config.windowSeconds) { healthySample() }
        val decision = EncoderFallbackPolicy.decide(
            history,
            EncoderFallbackPolicy.State(),
            nowMs = 60_000L,
            config = config,
        )
        assertEquals(EncoderFallbackPolicy.Action.KEEP_SELF, decision.action)
        assertEquals(EncoderFallbackPolicy.REASON_HEALTHY, decision.reason)
        assertEquals(0, decision.badSeconds)
        assertEquals(30, decision.requestedFps)
        assertEquals(10, decision.windowS)
    }

    @Test
    fun sustainedOverBudgetSwitchesToDefault() {
        val history = List(config.windowSeconds) { overBudgetSample() }
        val decision = EncoderFallbackPolicy.decide(
            history,
            EncoderFallbackPolicy.State(),
            nowMs = 60_000L,
            config = config,
        )
        assertEquals(EncoderFallbackPolicy.Action.SWITCH_TO_DEFAULT, decision.action)
        assertEquals(EncoderFallbackPolicy.REASON_P95_OVER_BUDGET, decision.reason)
        assertEquals(config.windowSeconds, decision.badSeconds)
        // 诊断键必填字段齐全（验收第 4 条）
        val fields = decision.logFields()
        assertEquals("switch_to_default", fields["action"])
        assertEquals("encode_p95_over_budget", fields["reason"])
        assertTrue(fields.containsKey("encode_p95_ms"))
        assertTrue(fields.containsKey("out_fps"))
        assertTrue(fields.containsKey("requested_fps"))
        assertEquals("10", fields["window_s"])
    }

    @Test
    fun lowOutFpsAloneSwitchesToDefault() {
        val history = List(config.windowSeconds) { lowFpsSample() }
        val decision = EncoderFallbackPolicy.decide(
            history,
            EncoderFallbackPolicy.State(),
            nowMs = 60_000L,
            config = config,
        )
        assertEquals(EncoderFallbackPolicy.Action.SWITCH_TO_DEFAULT, decision.action)
        assertEquals(EncoderFallbackPolicy.REASON_OUT_FPS_LOW, decision.reason)
        assertTrue(decision.outFps < 0.7 * 30)
    }

    @Test
    fun warmupWindowKeepsSelf() {
        // 窗口未满（9/10）即便全坏也不切 —— 避免刚开编码的抖动直接降级
        val history = List(config.windowSeconds - 1) { overBudgetSample() }
        val decision = EncoderFallbackPolicy.decide(
            history,
            EncoderFallbackPolicy.State(),
            nowMs = 9_000L,
            config = config,
        )
        assertEquals(EncoderFallbackPolicy.Action.KEEP_SELF, decision.action)
        assertEquals(EncoderFallbackPolicy.REASON_WARMUP, decision.reason)
    }

    @Test
    fun noSecondSwitchWithinHysteresisWindow() {
        // 切换发生在 t=0；30 s 内即使指标全坏也不得再切（把 maxSwitchesPerCall 放宽到 2，
        // 以证明拦住它的是**滞回**而不是次数上界）。
        val state = EncoderFallbackPolicy.State(switchesThisCall = 1, lastSwitchAtMs = 0L)
        val history = List(config.windowSeconds) { overBudgetSample() }
        val relaxed = config.copy(maxSwitchesPerCall = 2)

        val at15s = EncoderFallbackPolicy.decide(history, state, nowMs = 15_000L, config = relaxed)
        assertEquals(EncoderFallbackPolicy.Action.KEEP_SELF, at15s.action)
        assertEquals(EncoderFallbackPolicy.REASON_HYSTERESIS, at15s.reason)

        val at29s = EncoderFallbackPolicy.decide(history, state, nowMs = 29_999L, config = relaxed)
        assertEquals(EncoderFallbackPolicy.REASON_HYSTERESIS, at29s.reason)

        // 滞回刚过（30 s）→ 放宽次数上界后允许再切（证明滞回窗口边界准确）
        val at30s = EncoderFallbackPolicy.decide(history, state, nowMs = 30_000L, config = relaxed)
        assertEquals(EncoderFallbackPolicy.Action.SWITCH_TO_DEFAULT, at30s.action)
    }

    @Test
    fun atMostOneSwitchPerCall() {
        val state = EncoderFallbackPolicy.State(switchesThisCall = 1, lastSwitchAtMs = 0L)
        val history = List(config.windowSeconds) { overBudgetSample() }
        // 滞回早已过去（60 s > 30 s），但本轮已切过 1 次 ⇒ 幂等不再切
        val decision = EncoderFallbackPolicy.decide(history, state, nowMs = 60_000L, config = config)
        assertEquals(EncoderFallbackPolicy.Action.KEEP_SELF, decision.action)
        assertEquals(EncoderFallbackPolicy.REASON_ALREADY_SWITCHED, decision.reason)
    }

    @Test
    fun configMatchesAcceptanceThresholds() {
        assertEquals(10, config.windowSeconds)
        assertEquals(0.6, config.frameBudgetRatio, 1e-9)
        assertEquals(0.7, config.minOutFpsRatio, 1e-9)
        assertEquals(30_000L, config.hysteresisMs)
        assertEquals(1, config.maxSwitchesPerCall)
        // 30 fps：预算 33.3 ms ⇒ 触发线 20 ms；产出触发线 21 fps
        assertTrue(EncoderFallbackPolicy.isBadSecond(encodeP95Ms = 21, outFps = 30.0, requestedFps = 30, config = config))
        assertFalse(EncoderFallbackPolicy.isBadSecond(encodeP95Ms = 20, outFps = 30.0, requestedFps = 30, config = config))
        assertTrue(EncoderFallbackPolicy.isBadSecond(encodeP95Ms = 5, outFps = 20.9, requestedFps = 30, config = config))
        assertFalse(EncoderFallbackPolicy.isBadSecond(encodeP95Ms = 5, outFps = 21.0, requestedFps = 30, config = config))
        // 请求帧率非法 ⇒ 不判定（不倒向切换）
        assertFalse(EncoderFallbackPolicy.isBadSecond(encodeP95Ms = 999, outFps = 0.0, requestedFps = 0, config = config))
    }

    @Test
    fun manualOverrideWins() {
        assertEquals(
            EncoderImpl.SELF,
            EncoderFallbackPolicy.resolveImpl(EncoderOverrideMode.SELF, EncoderImpl.DEFAULT),
        )
        assertEquals(
            EncoderImpl.DEFAULT,
            EncoderFallbackPolicy.resolveImpl(EncoderOverrideMode.DEFAULT, EncoderImpl.SELF),
        )
        assertEquals(
            EncoderImpl.DEFAULT,
            EncoderFallbackPolicy.resolveImpl(EncoderOverrideMode.AUTO, EncoderImpl.DEFAULT),
        )
        assertEquals(
            EncoderImpl.SELF,
            EncoderFallbackPolicy.resolveImpl(EncoderOverrideMode.AUTO, EncoderImpl.SELF),
        )
        // 脏配置回退 AUTO（不抛异常）
        assertEquals(EncoderOverrideMode.AUTO, EncoderOverrideMode.fromWire("bogus"))
        assertEquals(EncoderOverrideMode.AUTO, EncoderOverrideMode.fromWire(null))
        assertEquals(EncoderOverrideMode.DEFAULT, EncoderOverrideMode.fromWire("DEFAULT"))
    }

    @Test
    fun p95UsesNinetyFifthPercentile() {
        // 1..30 的 95 分位：ceil(0.95*30)=29 ⇒ 索引 28 ⇒ 值 29
        val values = IntArray(30) { it + 1 }
        assertEquals(29, EncoderFallbackPolicy.p95(values))
        // 不修改入参
        assertEquals(1, values[0])
        assertEquals(30, EncoderFallbackPolicy.p95(IntArray(30) { 30 }))
        assertEquals(0, EncoderFallbackPolicy.p95(IntArray(0)))
        assertEquals(7, EncoderFallbackPolicy.p95(intArrayOf(7)))
    }

    @Test
    fun noOutputFramesAreReportedInWindow() {
        val sample = lowFpsSample()
        val history = List(config.windowSeconds) { sample }
        val decision = EncoderFallbackPolicy.decide(history, EncoderFallbackPolicy.State(), 60_000L, config)
        assertEquals(sample.noOutputFrames * config.windowSeconds, decision.noOutputFrames)
        assertEquals(decision.noOutputFrames.toString(), decision.logFields()["no_output_frames"])
    }
}
