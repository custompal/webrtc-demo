package com.example.webrtcdemo.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** 连上后掉线（且媒体已不新鲜）⇒ 先回落 CONNECTING（画面立刻被遮罩），宽限期内未恢复 ⇒ FAILED。 */
    @Test
    fun lostAfterConnectedFallsBackThenFails() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onRemoteFrame(2_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)

        // 【t63】媒体已不新鲜（帧龄 5 s > 4 s 阈值）时才允许界面跳到"连接中断"
        tracker.onConnectionLost(7_000L)
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

        tracker.onTick(15_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.CONNECTION_LOST, tracker.status.reason)
        assertTrue(tracker.status.canRetry)
    }

    /**
     * 【t63】ICE 掉线但**媒体仍在流动** ⇒ 界面**不得**跳到"连接中断"
     * （真机 dk-a：`pc_ice_connection_state FAILED` 每 ~16 s 一次，而同刻 `down_bps` 稳定 645k–680k、
     * 画面持续更新）；只记录 `iceDown` 供失败归因。
     */
    @Test
    fun iceFlapWithLiveMediaKeepsUiConnected() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onMediaFrame(2_000L, MEDIA_SOURCE_SINK, 33L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)

        // 帧龄 1 s（≤ 4 s 阈值）时 ICE 掉线 ⇒ 抑制界面跳变
        tracker.onConnectionLost(3_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)
        assertTrue(tracker.status.iceDown)
        assertTrue(tracker.status.remoteFrameReady)

        // 媒体继续流动 ⇒ 保持 connected（即使 ICE 仍为 down）
        tracker.onMediaFrame(4_000L, MEDIA_SOURCE_SINK, 33L)
        tracker.onTick(5_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)

        // 媒体真的停了（帧龄越阈 + 连续 2 tick 去抖）⇒ 原因归类为 connection_lost（ICE 事实）
        tracker.onTick(9_000L)
        tracker.onTick(10_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.CONNECTION_LOST, tracker.status.reason)
    }

    /** 掉线后在宽限期内恢复 ⇒ 回到 CONNECTED（不误报失败）。 */
    @Test
    fun recoveryWithinGraceKeepsCallAlive() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onRemoteFrame(2_000L)
        // 【t63】媒体已不新鲜（帧龄 5 s）⇒ ICE 掉线正常进入"连接中断"
        tracker.onConnectionLost(7_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)

        tracker.onTick(12_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)

        // 宽限期内媒体恢复 ⇒ 立刻回到 connected（不误报失败）
        tracker.onRemoteFrame(13_000L)

        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)
    }

    /**
     * 画面停滞（真机"一帧卡住"）：曾就绪但超过阈值无下行 ⇒ 清 `remoteFrameReady`、
     * 回落 CONNECTING 并遮罩远端画面；继续停滞超过宽限期 ⇒ FAILED(REMOTE_FRAME_STALLED)。
     *
     * 【t63】判停滞需要**连续 2 个 tick** 越阈（滞回去抖），因此这里在 t=7_000 与 t=8_000 各 tick 一次。
     */
    @Test
    fun stalledRemoteFrameFallsBackAndEventuallyFails() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onRemoteFrame(2_000L)
        tracker.onTick(3_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)

        // 阈值 4 s：t=7_000 时帧龄 5 s（第 1 次越阈，去抖未满 ⇒ 仍 connected）
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)

        // t=8_000：连续第 2 个 tick 越阈 ⇒ 判停滞
        tracker.onTick(8_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.REMOTE_FRAME_STALLED, tracker.status.reason)
        assertFalse(tracker.status.remoteFrameReady)
        assertTrue(tracker.status.remoteFrameStalled)
        assertTrue(tracker.status.remoteDimmed)
        assertTrue(tracker.status.title.contains("画面已中断"))

        // 停滞宽限期（自"帧龄刚越阈"起 8 s）后仍无下行 ⇒ FAILED
        tracker.onTick(16_000L)
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
        // 【t63】越阈需连续 2 个 tick（去抖）
        tracker.onTick(6_000L)
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.REMOTE_FRAME_STALLED, tracker.status.reason)
    }

    /**
     * 【t63】去抖：单次越阈不得判停滞（吸收采样抖动）。
     */
    @Test
    fun stallDebounceAbsorbsSingleGap() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_SINK, 33L)

        // 一次越阈（帧龄 6 s）⇒ 去抖计数 1 < 2，仍 connected
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)

        // 帧又来了（画面其实在动）⇒ 计数清零，保持 connected
        tracker.onMediaFrame(8_000L, MEDIA_SOURCE_SINK, 33L)
        tracker.onTick(14_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)
    }

    /**
     * 【t63 验收点名单测之一】**每帧回调持续 + `down_bps>0` + 分辨率回调稀疏** 的样本序列
     * ⇒ 状态机必须**稳定保持 connected**（连续 30 秒内 `phase=connected` 占比 100%、
     * 且 `reason` 不出现 `connection_lost`/`remote_frame_stalled`）。
     *
     * 稀疏分辨率回调由"本测试**从不**调用任何分辨率相关入口"来体现 —— 即模拟
     * `onFrameResolutionChanged` 在整个 30 s 内一次都不触发（真机正是如此）。
     */
    @Test
    fun perFrameLivenessKeepsConnectedFor30Seconds() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(1_000L)
        // 连上（候选对/传输事实）
        tracker.onSelectedPair(1_500L)

        var connectedTicks = 0
        var ticks = 0
        var now = 1_500L
        // 30 个 1 s tick；每个 tick 之前都有"每帧时间戳"（≈30 fps）+ down_bps>0 的样本
        repeat(30) {
            now += 1_000L
            tracker.onMediaFrame(now - 200L, MEDIA_SOURCE_SINK, 33L)   // 每帧路径（新鲜）
            tracker.onMediaFrame(now, MEDIA_SOURCE_DOWN_BPS, 0L)       // 下行字节路径（真的在收）
            val st = tracker.onTick(now)
            ticks++
            if (st.phase == ConnPhase.CONNECTED) connectedTicks++
            assertNotEquals(ConnReason.CONNECTION_LOST, st.reason)
            assertNotEquals(ConnReason.REMOTE_FRAME_STALLED, st.reason)
        }

        assertEquals(30, ticks)
        assertEquals("30 秒内必须 100% 处于 connected", 30, connectedTicks)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertTrue(tracker.status.remoteFrameReady)
        assertEquals(MEDIA_SOURCE_DOWN_BPS, tracker.status.mediaSource)
    }

    /**
     * 【t63 验收点名单测之二】**真实停流**（帧回调停止 + `down_bps=0`）的样本序列
     * ⇒ 必须进入 stalled（随后按宽限期转 FAILED）。
     */
    @Test
    fun realStreamStopEntersStalled() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onSelectedPair(500L)
        // 正常流动 3 秒
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_SINK, 33L)
        tracker.onMediaFrame(2_000L, MEDIA_SOURCE_SINK, 33L)
        tracker.onMediaFrame(3_000L, MEDIA_SOURCE_SINK, 33L)
        tracker.onTick(3_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)

        // 真实停流：再无帧回调、down_bps=0（ViewModel 不再调用 onMediaFrame）
        tracker.onTick(8_000L)   // 帧龄 5 s：第 1 次越阈
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        tracker.onTick(9_000L)   // 第 2 次越阈 ⇒ 判停滞
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.REMOTE_FRAME_STALLED, tracker.status.reason)
        assertFalse(tracker.status.remoteFrameReady)
        assertTrue(tracker.status.remoteDimmed)

        // 宽限期内未恢复 ⇒ FAILED，可重试
        tracker.onTick(18_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertTrue(tracker.status.canRetry)
    }

    /** 【t63】帧存活来源与帧龄必须体现在状态里（供 `ui_conn_state`/`remote_frame_liveness` 一行判定）。 */
    @Test
    fun mediaLivenessSourceAndAgeAreRecorded() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onSelectedPair(100L)

        tracker.onMediaFrame(2_000L, MEDIA_SOURCE_SINK, 33L)
        assertEquals(MEDIA_SOURCE_SINK, tracker.status.mediaSource)
        assertEquals(33L, tracker.status.mediaAgeMs)

        // 无新帧时 tick 会把帧龄写进状态（诊断用）
        tracker.onTick(3_000L)
        assertEquals(1_000L, tracker.status.mediaAgeMs)

        // 切到下行字节来源
        tracker.onMediaFrame(3_100L, MEDIA_SOURCE_DOWN_BPS, 0L)
        assertEquals(MEDIA_SOURCE_DOWN_BPS, tracker.status.mediaSource)
    }

    // ===================== t64：等待对端期间不计时/不给重试 =====================
    // 对应日志形态：等待期 `ui_conn_state phase=waiting_peer reason=none retry=0`（不推进 elapsed_ms）；
    // 收到对端后 `retry_clock_started trigger=peer_joined|joined|offer`；
    // 对端离开后 `retry_clock_reset reason=peer_left` 并回到 `phase=waiting_peer`。

    /**
     * 【t64① 点名单测】**等待期不得进入 failed/retry**：
     * 房主创建房间后（对端未加入，`phase=waiting_peer`）无论推进多久，都要停在 `WAITING_PEER`、
     * 计时器为 0、无可重试。
     */
    @Test
    fun waitingPeerNeverTimesOutNorOffersRetry() {
        val tracker = ConnectionStatusTracker()
        tracker.onWaitingPeer(1_000L)

        assertEquals(ConnPhase.WAITING_PEER, tracker.status.phase)
        assertEquals("等待对方加入", tracker.status.title)
        assertFalse(tracker.status.detail.contains("重试"))
        assertFalse(tracker.status.canRetry)
        assertFalse(tracker.isRetryClockRunning)
        assertTrue(tracker.status.showOverlay)

        // 推进到远超两档阈值（15 s / 30 s）的时刻，甚至 65 s —— 仍必须停在等待态
        for (t in listOf(16_000L, 31_000L, 66_000L)) {
            val st = tracker.onTick(t)
            assertEquals(ConnPhase.WAITING_PEER, st.phase)
            assertEquals(ConnReason.NONE, st.reason)
            assertEquals(0L, st.elapsedMs)
            assertEquals(0, st.retryCount)
            assertFalse(st.retryHintReached)
            assertFalse(st.canRetry)
        }
        // 等待时长只作为诊断（不参与任何超时判定）
        assertEquals(65_000L, tracker.waitingMs(66_000L))
    }

    /**
     * 【t64② 点名单测】**收到对端才起算**：等待 60 s 后由 `peerJoined` 触发，
     * 两档计时（15 s 提示可重试 / 30 s 判失败）从**触发时刻**起算（阈值沿用 t59/t61）。
     */
    @Test
    fun retryClockStartsOnlyAfterPeerArrives() {
        val tracker = ConnectionStatusTracker()
        tracker.onWaitingPeer(0L)
        // 等待 60 s：不动作
        tracker.onTick(60_000L)
        assertEquals(ConnPhase.WAITING_PEER, tracker.status.phase)

        // host: peerJoined ⇒ 启动计时（从 60 s 起算）
        tracker.startRetryClock(60_000L)
        assertTrue(tracker.isRetryClockRunning)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(0L, tracker.status.elapsedMs)

        val at74 = tracker.onTick(74_000L) // +14 s
        assertEquals(ConnPhase.CONNECTING, at74.phase)
        assertFalse(at74.retryHintReached)
        assertFalse(at74.canRetry)

        val at75 = tracker.onTick(75_000L) // +15 s ⇒ 可重试提示（仍不判失败）
        assertTrue(at75.retryHintReached)
        assertTrue(at75.canRetry)
        assertEquals(ConnPhase.CONNECTING, at75.phase)

        val at90 = tracker.onTick(90_000L) // +30 s ⇒ FAILED
        assertEquals(ConnPhase.FAILED, at90.phase)
        assertEquals(ConnReason.TIMEOUT_NO_PAIR, at90.reason)

        // 幂等：重复触发不得把已失败/已连上的状态重置
        tracker.startRetryClock(95_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
    }

    /**
     * 【t64③ 点名单测】**对端离开则复位**（`retry_clock_reset`）：`peerLeft` ⇒ 停表、回 `waiting_peer` 态、不显示失败/重试；
     * 对端再次加入时可**重新**计时（不残留上一轮状态）。
     */
    @Test
    fun peerLeftResetsToWaitingAndClockCanRestart() {
        val tracker = ConnectionStatusTracker()
        tracker.onWaitingPeer(0L)
        tracker.startRetryClock(1_000L)
        tracker.onTick(31_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)

        // peerLeft ⇒ 复位到等待态
        tracker.onWaitingPeer(40_000L)
        assertEquals(ConnPhase.WAITING_PEER, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)
        assertEquals(0, tracker.status.retryCount)
        assertFalse(tracker.status.canRetry)
        assertFalse(tracker.isRetryClockRunning)
        assertEquals("等待对方加入", tracker.status.title)

        // 复位后仍不超时（再等 50 s）
        tracker.onTick(90_000L)
        assertEquals(ConnPhase.WAITING_PEER, tracker.status.phase)

        // 对端再次进入 ⇒ 新一轮计时（窗口从 100 s 起算）
        tracker.startRetryClock(100_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(0L, tracker.status.elapsedMs)
        assertTrue(tracker.isRetryClockRunning)

        tracker.onTick(115_000L)
        assertTrue(tracker.status.retryHintReached)
        tracker.onTick(130_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.TIMEOUT_NO_PAIR, tracker.status.reason)
    }

    /** 【t64】等待态下界面不得显示"连接中"、也不得有重试入口（`isConnecting` 由 phase 派生）。 */
    @Test
    fun waitingPeerIsNotConnectingAndHasNoRetryEntry() {
        val tracker = ConnectionStatusTracker()
        val waiting = tracker.onWaitingPeer(0L)

        assertNotEquals(ConnPhase.CONNECTING, waiting.phase)
        assertFalse(waiting.canRetry)
        assertFalse(waiting.retryHintReached)
        assertEquals(0, waiting.retryCount)
        assertTrue(waiting.title.contains("等待"))
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

    // ===================== t68：`mediaAlive` latch 的复位不变量 =====================
    // 为什么单独钉：`mediaAlive` 是"媒体存活期不显示 ICE 失败"的**唯一闸门**。
    // 它必须能被"重新等待对端（peerLeft）"与"用户点击重试"两条路径**及时复位**，
    // 否则一次中断后残留的真值会永久压掉失败文案与「点击重试」（比原缺陷更糟）。

    /** `peerLeft` ⇒ `onWaitingPeer`：必须复位（等待期没有媒体证据，也不能继承上一段的抑制）。 */
    @Test
    fun waitingPeerResetClearsMediaAliveSuppression() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_SINK, ageMs = 30L)
        assertTrue("已连上 ⇒ 媒体存活", tracker.status.mediaAlive)

        val waiting = tracker.onWaitingPeer(2_000L)

        assertEquals(ConnPhase.WAITING_PEER, waiting.phase)
        assertFalse("等待对端期间不得继承上一段的 mediaAlive", waiting.mediaAlive)
        assertFalse("等待期不存在被抑制的失败文案", waiting.iceFailureSuppressed)
        assertEquals("等待对方加入", waiting.title)
    }

    /** 用户「点击重试」⇒ `onRetry`：必须复位 —— 新一代会话必须能如实显示失败/重试。 */
    @Test
    fun retryResetClearsMediaAliveSuppression() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_SINK, ageMs = 20L)
        tracker.onConnectionLost(2_000L)
        tracker.onTick(6_000L)
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertTrue("宽限期内仍按'媒体可能还在'处理", tracker.status.mediaAlive)
        assertTrue(tracker.status.iceFailureSuppressed)

        val retried = tracker.onRetry(8_000L)

        assertEquals(ConnPhase.CONNECTING, retried.phase)
        assertEquals(1, retried.retryCount)
        assertFalse("重试后必须复位 mediaAlive", retried.mediaAlive)
        assertFalse("重试后新一代会话的失败文案不得被旧抑制压掉", retried.iceFailureSuppressed)
    }
}
