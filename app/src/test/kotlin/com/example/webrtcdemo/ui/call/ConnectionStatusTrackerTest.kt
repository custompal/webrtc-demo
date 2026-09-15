package com.example.webrtcdemo.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t59：`ConnectionStatusTracker` 纯 JVM 单测（无 Android / Compose 依赖）
// ----------------------------------------------------------------------------
// 覆盖验收第 2/3/4/5 条对应不变量：
//   * 未连上：一直停留在 CONNECTING（含已用时长），15 s ⇒ FAILED(TIMEOUT_NO_PAIR) + 可重试；
//   * 编码器在跑（impl 非空）**不构成**连通证据（旧 UI 的根因，用状态机层面钉死）；
//   * 连上：选中候选对 / 传输 CONNECTED / 远端帧 ⇒ CONNECTED；
//   * 连上后掉线 / 画面停滞：先回落 CONNECTING（画面必须被遮罩），宽限期内不恢复 ⇒ FAILED；
//   * 静止帧不算画面：超过停滞阈值 ⇒ remoteFrameReady=false 且 remoteDimmed=true；
//   * 重试 ⇒ 新一代（retryCount+1、计时归零、重新 CONNECTING）；
//   * `DISCONNECTED` 里含 `CONNECTED` 子串的解析陷阱。
//
// 运行（宿主机，容器内无 JDK）：./gradlew --no-daemon :app:testDebugUnitTest
// ============================================================================

class ConnectionStatusTrackerTest {

    /** 未连上时必须停留在 CONNECTING，并带上已用时长（而不是被"编码器在跑"清掉）。 */
    @Test
    fun staysConnectingWithElapsedUntilPairAppears() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(1_000L)

        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertTrue(tracker.status.showOverlay)
        assertEquals(0L, tracker.status.elapsedSeconds)

        tracker.onTick(4_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(3L, tracker.status.elapsedSeconds)
        assertTrue(tracker.status.title.startsWith("正在建立连接"))
        assertTrue(tracker.status.detail.contains("3 秒"))
    }

    /** 只有 tick 能推进时间；未进入通话时不得把进程启动耗时算进"已等待"。 */
    @Test
    fun tickBeforeCallStartDoesNotAdvance() {
        val tracker = ConnectionStatusTracker()
        tracker.onTick(99_000L)

        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(0L, tracker.status.elapsedMs)
        assertFalse(tracker.active)
    }

    /**
     * 【t60 两档口径】15 s ⇒ 出现"可重试"提示但**仍 CONNECTING**（不误判失败，t58 §6.3 A1）；
     * 30 s（硬超时，取 30–45 s 区间下沿）⇒ `FAILED(TIMEOUT_NO_PAIR)` + 可重试。
     */
    @Test
    fun retryHintAt15sThenHardFailAt30s() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)

        tracker.onTick(14_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertFalse(tracker.status.retryHintReached)
        assertFalse(tracker.status.canRetry)

        // 15 s：给出可操作提示 + 重试入口（t59 验收），但**不**判失败
        tracker.onTick(15_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertTrue(tracker.status.retryHintReached)
        assertTrue(tracker.status.canRetry)
        assertTrue(tracker.status.detail.contains("15 秒"))
        assertTrue(tracker.status.detail.contains("可点击重试"))

        tracker.onTick(29_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)

        // 30 s：硬失败
        tracker.onTick(30_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.TIMEOUT_NO_PAIR, tracker.status.reason)
        assertEquals("连接失败", tracker.status.title)
        assertTrue(tracker.status.canRetry)
        assertFalse(tracker.status.hasSelectedPair)
    }

    /**
     * 【t60 硬要求（captain 邮件 + t58 §6.6 A7）】判活**绝不能用 `up_bps`**：
     * 给定真机形态的样本序列（`mode=- / up_bps≈49k / down_bps=0`，持续 30 s），
     * 状态机**不得**进入 `CONNECTED`，最终必须落到失败态 + 可重试。
     */
    @Test
    fun upstreamOnlySamplesNeverReachConnected() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)

        // 15 次 stats 采样（每 2 s 一条），恒定：无选中候选对、上行 ≈49 kbps、下行 0
        var everConnected = false
        var now = 0L
        repeat(15) {
            now += 2_000L
            val evidence = livenessEvidence(
                hasSelectedPair = false,
                downBitrateBps = 0,
                upBitrateBps = 49_000,
            )
            assertEquals(LivenessEvidence.NONE, evidence)
            if (evidence != LivenessEvidence.NONE) everConnected = true
            tracker.onTick(now)
        }

        assertFalse("up_bps 不得把状态推成已连接", everConnected)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.TIMEOUT_NO_PAIR, tracker.status.reason)
        assertTrue(tracker.status.canRetry)
        assertFalse(tracker.status.hasSelectedPair)
        assertFalse(tracker.status.remoteFrameReady)
    }

    /** 判活口径的单元断言：只有"选中候选对"或"下行字节"才算证据，`up_bps` 永不参与。 */
    @Test
    fun livenessNeverUsesUpstreamBitrate() {
        assertEquals(LivenessEvidence.NONE, livenessEvidence(hasSelectedPair = false, downBitrateBps = 0, upBitrateBps = 0))
        assertEquals(LivenessEvidence.NONE, livenessEvidence(hasSelectedPair = false, downBitrateBps = 0, upBitrateBps = 49_000))
        assertEquals(LivenessEvidence.NONE, livenessEvidence(hasSelectedPair = false, downBitrateBps = 0, upBitrateBps = 5_000_000))
        assertEquals(LivenessEvidence.SELECTED_PAIR, livenessEvidence(hasSelectedPair = true, downBitrateBps = 0, upBitrateBps = 0))
        assertEquals(LivenessEvidence.DOWNLINK, livenessEvidence(hasSelectedPair = false, downBitrateBps = 1, upBitrateBps = 0))
    }

    /** 【t60/A7】"配了 TURN 但没有中继候选"必须是**显式子原因**，而不能与 TIMEOUT_NO_PAIR 混同。 */
    @Test
    fun relayMissingIsExplicitFailureReason() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)

        tracker.onTick(30_000L, relayMissing = true)

        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.NO_RELAY_CANDIDATE, tracker.status.reason)
        assertTrue(tracker.status.relayMissing)
        assertEquals("中继不可用", tracker.status.title)
        assertTrue(tracker.status.detail.contains("中继候选"))
        assertTrue(tracker.status.canRetry)

        // 反之：单纯没配上候选对 ⇒ TIMEOUT_NO_PAIR
        val other = ConnectionStatusTracker()
        other.onCallStarted(0L)
        other.onTick(30_000L, relayMissing = false)
        assertEquals(ConnReason.TIMEOUT_NO_PAIR, other.status.reason)
    }

    /** 【t60/A1②】收集完成是超时**锚点**：收集完成得晚 ⇒ 硬超时相应后移（不误判）。 */
    @Test
    fun gatheringCompleteAnchorsTimeout() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)

        // 20 s 才收集完成（真机中继候选可晚到）
        tracker.onGatheringComplete(20_000L)

        tracker.onTick(49_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)

        tracker.onTick(50_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.TIMEOUT_NO_PAIR, tracker.status.reason)
    }

    /** 选中候选对（`stats_sample mode=RELAY`）⇒ CONNECTED；此时不再显示状态卡。 */
    @Test
    fun selectedPairMarksConnected() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onTick(2_000L)

        tracker.onSelectedPair(2_500L)

        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)
        assertTrue(tracker.status.hasSelectedPair)
        assertFalse(tracker.status.showOverlay)
        // 仅"候选对出现"还不足以宣称已收到画面
        assertFalse(tracker.status.remoteFrameReady)
        assertTrue(tracker.status.remoteDimmed)
    }

    /** 传输层 CONNECTED（`pc_connection_state dtls=true state=CONNECTED`）同样算连上。 */
    @Test
    fun transportConnectedMarksConnected() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)

        tracker.onTransportConnected(1_800L)

        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertTrue(tracker.status.hasSelectedPair)
    }

    /** 收到远端帧 ⇒ 已连上且画面就绪（不再遮罩）。 */
    @Test
    fun remoteFrameMarksReadyAndUnblocksPicture() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)

        tracker.onRemoteFrame(1_900L)
        tracker.onTick(2_000L)

        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertTrue(tracker.status.remoteFrameReady)
        assertFalse(tracker.status.remoteFrameStalled)
        assertFalse(tracker.status.remoteDimmed)
    }

    /** 连上后掉线 ⇒ 先回落 CONNECTING（画面立刻被遮罩），宽限期内未恢复 ⇒ FAILED。 */
    @Test
    fun lostAfterConnectedFallsBackThenFails() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onRemoteFrame(2_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)

        tracker.onConnectionLost(6_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.CONNECTION_LOST, tracker.status.reason)
        assertFalse(tracker.status.remoteFrameReady)
        assertTrue(tracker.status.remoteDimmed)
        assertTrue(tracker.status.showOverlay)
        assertTrue(tracker.status.title.contains("连接中断"))
        assertFalse(tracker.status.canRetry)

        // 宽限期（8 s）内还能自动恢复
        tracker.onTick(13_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)

        tracker.onTick(14_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.CONNECTION_LOST, tracker.status.reason)
        assertTrue(tracker.status.canRetry)
    }

    /** 掉线后在宽限期内恢复 ⇒ 回到 CONNECTED（不误报失败）。 */
    @Test
    fun recoveryWithinGraceKeepsCallAlive() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onRemoteFrame(2_000L)
        tracker.onConnectionLost(6_000L)

        tracker.onTick(10_000L)
        tracker.onRemoteFrame(12_000L)

        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)
    }

    /**
     * 画面停滞（真机"一帧卡住"）：曾就绪但超过阈值无下行 ⇒ 清 `remoteFrameReady`、
     * 回落 CONNECTING 并遮罩远端画面；继续停滞超过宽限期 ⇒ FAILED(REMOTE_FRAME_STALLED)。
     */
    @Test
    fun stalledRemoteFrameFallsBackAndEventuallyFails() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onRemoteFrame(2_000L)
        tracker.onTick(3_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)

        // 4 s 阈值：t=7_000 时已超过 2_000+4_000
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.REMOTE_FRAME_STALLED, tracker.status.reason)
        assertFalse(tracker.status.remoteFrameReady)
        assertTrue(tracker.status.remoteFrameStalled)
        assertTrue(tracker.status.remoteDimmed)
        assertTrue(tracker.status.title.contains("画面已中断"))

        // 停滞宽限期（自 lastFresh+stall 起 8 s）后仍无下行 ⇒ FAILED
        tracker.onTick(15_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.REMOTE_FRAME_STALLED, tracker.status.reason)
        assertTrue(tracker.status.canRetry)
    }

    /** 停滞判定不受"上行仍在增长"影响：只有远端帧/下行字节才算新鲜。 */
    @Test
    fun stallIgnoresUpstreamOnlySamples() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onRemoteFrame(1_000L)
        // 之后 2 s 一次只有上行（真机无候选对时 up_bps≈49 kbps）——不得刷新 lastFreshMedia
        tracker.onTick(3_000L)
        tracker.onTick(5_000L)
        tracker.onTick(6_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.REMOTE_FRAME_STALLED, tracker.status.reason)
    }

    /** 重试：次数 +1、计时归零、重新 CONNECTING（配合 t53 的世代化新会话）。 */
    @Test
    fun retryResetsToConnectingAndCounts() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onTick(31_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(0, tracker.status.retryCount)

        tracker.onRetry(40_000L)

        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)
        assertEquals(1, tracker.status.retryCount)
        assertEquals(0L, tracker.status.elapsedMs)
        assertFalse(tracker.status.hasSelectedPair)
        assertFalse(tracker.status.remoteFrameReady)
        // 重试后仍不连通 ⇒ 再次失败并可再重试
        tracker.onTick(71_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(1, tracker.status.retryCount)
        assertTrue(tracker.status.canRetry)
    }

    /** 本地建会话失败 ⇒ 立刻 FAILED(SESSION_START_FAILED) 并可重试。 */
    @Test
    fun sessionFailureFailsImmediately() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)

        tracker.onSessionFailed(500L)

        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.SESSION_START_FAILED, tracker.status.reason)
        assertTrue(tracker.status.canRetry)
        assertEquals("会话创建失败", tracker.status.title)
    }

    /** `DISCONNECTED` 含 `CONNECTED` 子串 —— 解析必须优先判掉线。 */
    @Test
    fun parsesDisconnectedBeforeConnected() {
        assertEquals(ConnSignal.DISCONNECTED, parseConnSignal("DISCONNECTED"))
        assertEquals(ConnSignal.DISCONNECTED, parseConnSignal("state=DISCONNECTED"))
        assertEquals(ConnSignal.CONNECTED, parseConnSignal("CONNECTED"))
        assertEquals(ConnSignal.CONNECTED, parseConnSignal("state=CONNECTED"))
        assertEquals(ConnSignal.CONNECTED, parseConnSignal("transport=CONNECTED"))
        assertEquals(ConnSignal.CONNECTED, parseConnSignal("COMPLETED"))
        assertEquals(ConnSignal.FAILED, parseConnSignal("FAILED"))
        assertEquals(ConnSignal.IN_PROGRESS, parseConnSignal("CHECKING"))
        assertEquals(ConnSignal.IN_PROGRESS, parseConnSignal("transport=CONNECTING"))
        assertEquals(ConnSignal.IGNORED, parseConnSignal("mid=0 host srflx"))
    }

    /** 重试后的重新协商决策：host 立刻重发 offer、joiner 延迟重发、对端未知则等待。 */
    @Test
    fun retryNegotiationFollowsRoleAndPeerKnowledge() {
        assertEquals(RetryNegotiation.OFFER_NOW, retryNegotiationFor("host", peerKnown = true, sawRemoteNegotiationSinceRetry = false))
        assertEquals(RetryNegotiation.OFFER_DELAYED, retryNegotiationFor("joiner", peerKnown = true, sawRemoteNegotiationSinceRetry = false))
        assertEquals(RetryNegotiation.NONE, retryNegotiationFor("host", peerKnown = false, sawRemoteNegotiationSinceRetry = false))
        assertEquals(RetryNegotiation.NONE, retryNegotiationFor("joiner", peerKnown = false, sawRemoteNegotiationSinceRetry = false))
        assertEquals(RetryNegotiation.NONE, retryNegotiationFor("joiner", peerKnown = true, sawRemoteNegotiationSinceRetry = true))
    }
}
