package com.example.webrtcdemo.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t68③：媒体存活期间"不显示 ICE 失败 / 不进入 FAILED"的 JUnit4 回归
// ----------------------------------------------------------------------------
// 真机缺陷（dl-b 2026-09-15）：
//   15:27:54.733 `ui_conn_state elapsed_ms=69169 frame=false ice_down=true media_age_ms=2705
//                  media_source=down_bps pair=true phase=connecting reason=connection_lost stalled=true`
//   —— `ice_down` 驱动了用户可见的"连接中断…"失败卡，而帧龄只有 2.7 s、视频随后（15:27:55.900 回到
//      `phase=connected`）自行恢复；随后又因信令缺陷（t68①②）自动退房。
// 现在：
//   * 中断后进入**宽限期**（`CONNECTING`），`mediaAlive=true` ⇒ 失败卡被抑制，只给中性提示；
//   * 宽限期用尽 ⇒ `FAILED` + `mediaAlive=false`：真的断了照常显示失败文案与「点击重试」（t59/t60 口径不变）。
//
// 纯 JVM 可跑：ConnectionStatus/ConnectionStatusTracker 都是纯 Kotlin。
// 运行方式（宿主机）：./gradlew :app:testDebugUnitTest --tests '*MediaAliveSuppressionTest'
// ============================================================================

class MediaAliveSuppressionTest {

    // ==================== 呈现层闸门（CallScreen 就是按这三个属性分支的） ====================

    @Test
    fun mediaAliveSuppressesFailureCardAndShowsNeutralBanner() {
        // 复刻 dl-b 15:27:54.733 的状态快照（含 pair=true / media_age_ms=2705 / ice_down=true）
        val status = ConnStatus(
            phase = ConnPhase.CONNECTING,
            reason = ConnReason.CONNECTION_LOST,
            mediaAlive = true,
            mediaAgeMs = 2_705L,
            mediaSource = MEDIA_SOURCE_DOWN_BPS,
            hasSelectedPair = true,
            iceDown = true,
            remoteFrameReady = false,
        )
        assertTrue("媒体存活时必须抑制失败样式状态卡", status.iceFailureSuppressed)
        assertFalse("被抑制时不得渲染失败卡", status.failureCardVisible)
        assertTrue("被抑制时改渲染中性提示", status.recoveryBannerVisible)
    }

    @Test
    fun neutralBannerTextContainsNoIceFailureWording() {
        // 硬要求"文案不含 ICE 失败"：逐字断言（含 失败/重连/ICE/中断 这些会吓到用户的词）
        val text = ConnStatus(phase = ConnPhase.CONNECTING, reason = ConnReason.CONNECTION_LOST, mediaAlive = true)
            .recoveryBannerText
        assertFalse("中性提示不得出现 ICE", text.contains("ICE"))
        assertFalse("中性提示不得出现 失败", text.contains("失败"))
        assertFalse("中性提示不得出现 重连", text.contains("重连"))
        assertFalse("中性提示不得出现 中断", text.contains("中断"))
        assertTrue(text.isNotBlank())
    }

    @Test
    fun confirmedFailureStillRendersCardWithRetry() {
        // 真的要断了（宽限期用尽）⇒ 失败卡 + 一键重试必须回来（t59 验收不被本任务削弱）
        val status = ConnStatus(
            phase = ConnPhase.FAILED,
            reason = ConnReason.CONNECTION_LOST,
            mediaAlive = false,
            mediaAgeMs = 12_000L,
            hasSelectedPair = true,
            iceDown = true,
        )
        assertFalse(status.iceFailureSuppressed)
        assertTrue(status.failureCardVisible)
        assertTrue(status.canRetry)
        assertEquals("连接已断开", status.title)
    }

    @Test
    fun connectedPhaseNeverShowsCard() {
        val status = ConnStatus(phase = ConnPhase.CONNECTED, mediaAlive = true, mediaAgeMs = 40L)
        assertFalse(status.showOverlay)
        assertFalse(status.failureCardVisible)
        assertFalse("已连上不需要中性提示（画面就在屏幕上）", status.recoveryBannerVisible)
    }

    // ==================== 状态机：宽限期内抑制、宽限期后如实失败 ====================

    /**
     * 完整时间线（dl-b 形态）：
     *   已连上且帧新鲜 → ICE 掉线（媒体还新鲜）⇒ 界面保持 CONNECTED；
     *   帧真的停了超过阈值（去抖 2 tick）⇒ 进入 `CONNECTING/CONNECTION_LOST` **宽限期**（抑制失败卡）；
     *   宽限期 8 s 用尽仍无恢复 ⇒ `FAILED/CONNECTION_LOST`（失败卡 + 可重试）。
     */
    @Test
    fun graceperiodSuppressesThenConfirmedFailureSurfaces() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        // 首帧/持续媒体证据（帧龄 30 ms）
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_SINK, ageMs = 30L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertTrue(tracker.status.mediaAlive)

        // ICE 掉线，但媒体仍新鲜 ⇒ 只落盘（iceDown），界面不跳变
        tracker.onConnectionLost(2_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertTrue(tracker.status.mediaAlive)
        assertFalse(tracker.status.failureCardVisible)

        // 帧停了：tick 1 次不判停滞（去抖），第 2 次才进入宽限期
        tracker.onTick(6_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.CONNECTION_LOST, tracker.status.reason)
        assertTrue("宽限期内媒体按'可能仍在流'处理 ⇒ 抑制 ICE 失败文案", tracker.status.iceFailureSuppressed)
        assertFalse(tracker.status.failureCardVisible)
        assertTrue(tracker.status.recoveryBannerVisible)

        // 宽限期（8 s，自中断起点 5 s 起算）用尽 ⇒ 如实失败
        tracker.onTick(13_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.CONNECTION_LOST, tracker.status.reason)
        assertFalse(tracker.status.mediaAlive)
        assertTrue("确认失败后必须给出失败卡与一键重试", tracker.status.failureCardVisible)
        assertTrue(tracker.status.canRetry)
    }

    @Test
    fun recoveryWithinGraceKeepsConnectedAndAlive() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_SINK, ageMs = 20L)
        tracker.onConnectionLost(2_000L)
        tracker.onTick(6_000L)
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        // 宽限期内恢复（新的每帧时间戳）⇒ 回到 CONNECTED，媒体存活
        tracker.onMediaFrame(9_000L, MEDIA_SOURCE_SINK, ageMs = 25L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertEquals(ConnReason.NONE, tracker.status.reason)
        assertTrue(tracker.status.mediaAlive)
        assertFalse(tracker.status.failureCardVisible)
    }

    /**
     * 点名用例④（验收原文后半）：远端帧新鲜（<3 s）或存在 selected pair 时**连接状态不进入 FAILED**。
     *
     * 实现口径（见 `ConnectionStatusTracker.markLost` 的 KDoc）：中断后先进入宽限期并保持
     * `mediaAlive=true`（候选对仍在）⇒ 该窗口内绝不会 FAILED；只要宽限期内恢复（帧重新新鲜）
     * 就回到 CONNECTED。只有"宽限期用尽且仍无任何恢复"才判 FAILED —— 否则"真的断了"将永远
     * 拿不到失败卡与「点击重试」（t59/t60 验收）。
     */
    @Test
    fun freshFrameKeepsOutOfFailedWhileMediaAlive() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_SINK, ageMs = 30L)
        tracker.onConnectionLost(2_000L)
        tracker.onTick(6_000L)
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        // 宽限期内恢复：新的新鲜帧（<3 s）⇒ 不得进入 FAILED
        tracker.onMediaFrame(9_000L, MEDIA_SOURCE_SINK, ageMs = 25L)
        tracker.onTick(11_000L)
        assertEquals(ConnPhase.CONNECTED, tracker.status.phase)
        assertTrue(tracker.status.mediaAlive)
        assertFalse(tracker.status.failureCardVisible)
        assertEquals("", tracker.status.title)
    }

    @Test
    fun neverConnectedTimeoutStillSurfacesFailureCard() {
        // t59 口径：从未连上（无候选对、无帧）⇒ 15 s 给可重试提示、30 s 判失败，且**不得**被抑制
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onTick(15_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertFalse(tracker.status.mediaAlive)
        assertTrue(tracker.status.failureCardVisible)
        assertTrue(tracker.status.canRetry)

        tracker.onTick(30_000L)
        assertEquals(ConnPhase.FAILED, tracker.status.phase)
        assertEquals(ConnReason.TIMEOUT_NO_PAIR, tracker.status.reason)
        assertFalse(tracker.status.mediaAlive)
        assertTrue(tracker.status.failureCardVisible)
    }

    @Test
    fun waitingPeerShowsWaitingNoticeNotFailure() {
        val tracker = ConnectionStatusTracker()
        tracker.onWaitingPeer(0L)
        tracker.onTick(60_000L)
        assertEquals(ConnPhase.WAITING_PEER, tracker.status.phase)
        assertFalse(tracker.status.mediaAlive)
        assertFalse("等待期不涉及 ICE 抑制", tracker.status.iceFailureSuppressed)
        // t64 口径：等待期显示"等待对方加入"（不是失败/重试）
        assertEquals("等待对方加入", tracker.status.title)
        assertFalse(tracker.status.remoteFrameReady)
    }

    // ==================== 诊断诚实性：帧龄不得沿用滞后字段 ====================

    /**
     * dl-b 的 `ui_conn_state … media_age_ms=2705` 是**滞后的字段值**（上一次 `onMediaFrame` 存下的），
     * 而真实的帧空窗已达 ~5 s。中断状态必须记录**真实帧龄**，否则下一轮复测会照着 2.7 s 误判
     * "媒体还活着"（这正是本轮定因时踩过的坑）。
     */
    @Test
    fun lossRecordsRealFrameAgeNotStaleField() {
        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_DOWN_BPS, ageMs = 2_705L)
        // 帧刚过期（真实空窗 5 s+，且此刻无 ICE 事件 ⇒ 归因为画面停滞），连续两次 tick 触发停滞判定
        tracker.onTick(6_000L)
        tracker.onTick(7_000L)
        assertEquals(ConnPhase.CONNECTING, tracker.status.phase)
        assertEquals(ConnReason.REMOTE_FRAME_STALLED, tracker.status.reason)
        assertEquals("中断状态必须写入真实帧龄（6 s），而不是滞后的 2705 ms", 6_000L, tracker.status.mediaAgeMs)
        assertTrue(tracker.status.mediaAlive)
    }
}
