package com.example.webrtcdemo.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t87 机制单测：编码线程采样 → 每秒窗口 →（后台线程）判据 → 通话内切换请求/确认/退化
// ----------------------------------------------------------------------------
// 这些用例证明「机制路径真的接通了」而不只是纯函数正确：
//   ① badWindowsEventuallyRequestInCallSwitch  采样→决策→selector 取走请求→工厂建默认实现=确认
//   ② inCallSwitchTimeoutFallsBackToNextCall   5 s 未确认 ⇒ pendingFallbackForNextCall，下次通话生效
//   ③ manualSelfOverrideSuppressesAutoSwitch   强制自研 ⇒ 只 probe 不切换
//   ④ healthyWindowsNeverSwitch                健康流量 ⇒ 全程不切、不产生任何请求
//   ⑤ pendingFlagIsHonoredOnNextCallStart      下次通话开始即直接用默认实现（不中断通话）
//
// 线程说明：`onEncodeResult` 在调用线程聚合，结算在 `enc-fallback` 后台线程；因此每次喂完窗口
// 都用 [EncoderFallbackController.awaitIdleForTest] 把后台队列排空后再断言（确定性，不靠 sleep 猜测）。
// ============================================================================

class EncoderFallbackControllerTest {

    /** 假时钟：由用例推进（毫秒）。 */
    private var now = 0L

    private fun initController(mode: EncoderOverrideMode = EncoderOverrideMode.AUTO) {
        now = 0L
        EncoderFallbackController.setTimeSourceForTest { now }
        EncoderFallbackController.resetForTest(mode = mode, fallbackEnabled = true)
        EncoderFallbackController.onCallStarted("test")
        // 与真机一致：通话开始后编码器 initEncode 会上报请求帧率（判据的单帧预算来源）
        EncoderFallbackController.onEncoderInit(Vp9VideoEncoder.IMPL_NAME, 640, 480, 30)
    }

    /**
     * 喂一个 1 s 窗口：`fps` 帧、每帧耗时 [frameMs]；推进假时钟并再喂一帧触发结算。
     *
     * @param fps 每秒帧数（= 产出帧数，全部计为产出）。
     * @param frameMs 单帧耗时（ms）。
     */
    private fun feedWindow(fps: Int = 30, frameMs: Long = 8L) {
        repeat(fps) { EncoderFallbackController.onEncodeResult(frameMs, true) }
        now += 1_000L
        EncoderFallbackController.onEncodeResult(frameMs, true)
    }

    /** 喂 [count] 个窗口并等待后台结算完成（确定性）。 */
    private fun feedWindows(count: Int, fps: Int = 30, frameMs: Long = 8L) {
        repeat(count) { feedWindow(fps = fps, frameMs = frameMs) }
        EncoderFallbackController.awaitIdleForTest()
    }

    @Test
    fun badWindowsEventuallyRequestInCallSwitch() {
        initController()
        // 30 fps 预算 33.3 ms ⇒ 触发线 20 ms；每帧 30 ms 持续 10 个窗口
        feedWindows(10, fps = 30, frameMs = 30L)
        // 决策只“请求”切换：本次通话暂时仍是自研，且会向 selector 交付一次请求
        assertEquals(EncoderFallbackPolicy.REASON_P95_OVER_BUDGET, EncoderFallbackController.uiState.value.reason)
        assertEquals(EncoderImpl.SELF, EncoderFallbackController.currentImpl())
        assertEquals(1, EncoderFallbackController.uiState.value.switchesThisCall)
        assertEquals(EncoderImpl.DEFAULT, EncoderFallbackController.implForCreate())
        assertTrue("请求应被 selector 取走一次", EncoderFallbackController.takeSwitchRequest())
        assertFalse("请求只能取走一次（避免反复重建 send stream）", EncoderFallbackController.takeSwitchRequest())
        // 工厂真的创建了默认实现 ⇒ 记为通话内切换确认
        EncoderFallbackController.onEncoderCreated(EncoderImpl.DEFAULT, "MediaCodecVideoEncoder")
        assertEquals(EncoderImpl.DEFAULT, EncoderFallbackController.currentImpl())
        assertEquals(EncoderImpl.DEFAULT, EncoderFallbackController.uiState.value.impl)
        assertEquals("in_call_switch", EncoderFallbackController.uiState.value.mech)
        assertFalse(EncoderFallbackController.pendingFallbackForNextCall)
    }

    @Test
    fun inCallSwitchTimeoutFallsBackToNextCall() {
        initController()
        feedWindows(10, fps = 30, frameMs = 30L)
        assertEquals(EncoderFallbackPolicy.REASON_P95_OVER_BUDGET, EncoderFallbackController.uiState.value.reason)
        // 请求被 selector 取走但**从未确认**（模拟 native 未重建编码器）
        assertTrue(EncoderFallbackController.takeSwitchRequest())
        // 时间推进 6 s（> 5 s 确认超时）并继续喂窗口，让超时检查跑到
        now += 6_000L
        feedWindows(2, fps = 30, frameMs = 30L)
        assertTrue(
            "5 s 未确认 ⇒ 标记下次通话生效",
            EncoderFallbackController.pendingFallbackForNextCall,
        )
        // 本次通话**不中断**：仍是自研，房间/会话不受影响
        assertEquals(EncoderImpl.SELF, EncoderFallbackController.currentImpl())
        assertEquals("next_call", EncoderFallbackController.uiState.value.mech)
        // 下一次通话开始 ⇒ 直接采用默认实现，并清除标记
        EncoderFallbackController.onCallStarted("next")
        assertEquals(EncoderImpl.DEFAULT, EncoderFallbackController.implForCreate())
        assertFalse(EncoderFallbackController.pendingFallbackForNextCall)
    }

    @Test
    fun manualSelfOverrideSuppressesAutoSwitch() {
        initController(EncoderOverrideMode.SELF)
        feedWindows(12, fps = 30, frameMs = 30L)
        // 强制自研：只 probe，不切换、不请求
        assertEquals("manual_self", EncoderFallbackController.uiState.value.reason)
        assertEquals(0, EncoderFallbackController.uiState.value.switchesThisCall)
        assertEquals(EncoderImpl.SELF, EncoderFallbackController.implForCreate())
        assertFalse("强制自研下不应产生切换请求", EncoderFallbackController.takeSwitchRequest())
        assertFalse(EncoderFallbackController.pendingFallbackForNextCall)
    }

    @Test
    fun healthyWindowsNeverSwitch() {
        initController()
        feedWindows(15, fps = 30, frameMs = 8L)
        assertEquals(EncoderFallbackPolicy.REASON_HEALTHY, EncoderFallbackController.uiState.value.reason)
        assertEquals(EncoderImpl.SELF, EncoderFallbackController.currentImpl())
        assertEquals(0, EncoderFallbackController.uiState.value.switchesThisCall)
        assertFalse(EncoderFallbackController.takeSwitchRequest())
        assertFalse(EncoderFallbackController.pendingFallbackForNextCall)
    }

    @Test
    fun pendingFlagIsHonoredOnNextCallStart() {
        initController()
        feedWindows(10, fps = 30, frameMs = 30L)
        assertEquals(EncoderFallbackPolicy.REASON_P95_OVER_BUDGET, EncoderFallbackController.uiState.value.reason)
        assertTrue(EncoderFallbackController.takeSwitchRequest())
        now += 6_000L
        feedWindows(2, fps = 30, frameMs = 30L)
        assertTrue(EncoderFallbackController.pendingFallbackForNextCall)
        // 挂断不应丢失「下次通话生效」标记
        EncoderFallbackController.onCallEnded("test")
        assertTrue(EncoderFallbackController.pendingFallbackForNextCall)
        EncoderFallbackController.onCallStarted("next")
        assertEquals(EncoderImpl.DEFAULT, EncoderFallbackController.implForCreate())
        assertEquals("next_call", EncoderFallbackController.uiState.value.mech)
        assertEquals(EncoderImpl.DEFAULT, EncoderFallbackController.currentImpl())
    }
}
