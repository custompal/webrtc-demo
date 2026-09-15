package com.example.webrtcdemo.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t68：通话可存活化判定的 JUnit4 回归（纯 Kotlin，无 Android 依赖）
// ----------------------------------------------------------------------------
// 三处真机缺陷（2026-09-15，用户报告「视频没断，UI 却显示 ICE 失败，过一会自动退出房间」）：
//   ① dl-b 15:27:45.779 `ws_pong_timeout timeout_ms=5000`（单次丢失即判断线 ⇒ 房间被回收）；
//   ② dl-a:7516-7520 `peer_left → state_change(IN_CALL→DISCONNECTED) → call_end + hangup`
//      （当时 `stats_sample … down_bps=2047967 mode=RELAY`：媒体仍在流动却被踢回首页）；
//      dl-b:9717-9720 `ROOM_NOT_FOUND → DISCONNECTED → call_end + hangup`（`down_bps=326398`）；
//   ③ dl-b 15:27:54.733 `ui_conn_state … phase=connecting reason=connection_lost
//      media_age_ms=2705 pair=true`（媒体存活却显示失败文案）。
//
// 本文件只钉**纯判定**：`CallSurvivability`（peerLeft/房间丢失/文案闸门）。
// 运行方式（宿主机）：./gradlew :app:testDebugUnitTest --tests '*CallSurvivabilityTest'
// ============================================================================

class CallSurvivabilityTest {

    // ==================== 媒体存活口径（帧新鲜 <3 s 或 selected pair） ====================

    @Test
    fun dlbFrameAgeIsAliveAndStaleAgeIsNot() {
        // dl-b 15:27:54 的真机值：帧龄 2705 ms + 已选中候选对
        assertTrue(CallSurvivability.mediaAlive(remoteFrameAgeMs = 2_705L, hasSelectedPair = true))
        // 边界：2999 ms 仍算存活；3000 ms 起不算
        assertTrue(CallSurvivability.mediaAlive(2_999L, hasSelectedPair = false))
        assertFalse(CallSurvivability.mediaAlive(3_000L, hasSelectedPair = false))
    }

    @Test
    fun selectedPairAloneIsEnoughAndNoEvidenceIsNot() {
        // "或 selected pair"：即使没有帧龄也要视为存活
        assertTrue(CallSurvivability.mediaAlive(remoteFrameAgeMs = -1L, hasSelectedPair = true))
        // 无帧（-1 / MAX_VALUE）且无候选对 ⇒ 不存活
        assertFalse(CallSurvivability.mediaAlive(-1L, hasSelectedPair = false))
        assertFalse(CallSurvivability.mediaAlive(Long.MAX_VALUE, hasSelectedPair = false))
    }

    // ==================== ② peerLeft：不再自动挂断 ====================

    @Test
    fun peerLeftAfterConnectedKeepsCall() {
        // dl-a 场景：本世代曾连上过 + 媒体仍在流 ⇒ 保持通话页（回等待对方加入）
        assertSame(PeerLeftAction.KEEP_CALL, CallSurvivability.peerLeftAction(everConnected = true, mediaAlive = true))
        // 曾连上过（此刻恰好没有新鲜帧）⇒ 仍保持通话页（保留房间，等对端重连回来）
        assertSame(PeerLeftAction.KEEP_CALL, CallSurvivability.peerLeftAction(everConnected = true, mediaAlive = false))
        // 媒体还在流（即使状态机尚未记录"曾连上"）⇒ 也必须保持
        assertSame(PeerLeftAction.KEEP_CALL, CallSurvivability.peerLeftAction(everConnected = false, mediaAlive = true))
    }

    @Test
    fun peerLeftBeforeAnyConnectionStillEndsCall() {
        // 从未连上过且无媒体证据（首次入房就被对端放鸽子）⇒ 维持原语义结束通话，不留半死不活的界面
        assertSame(PeerLeftAction.END_CALL, CallSurvivability.peerLeftAction(everConnected = false, mediaAlive = false))
    }

    /**
     * 点名用例②（验收原文）：`peerLeft` 且本世代**曾连上过** ⇒ 停在 `waiting_peer`，
     * 不 `hangup`/不 `call_end`。
     *
     * 判定 + 状态机**组合**断言：`CallSurvivability` 给出"保留通话页"，`ConnectionStatusTracker.onWaitingPeer`
     * （CallViewModel 在 KEEP_CALL 分支调用，见 `CallViewModel.resetRetryClock`）确实落到等待态且不给重试/失败。
     * 真机反例（dl-a:7517-7520）：同一事件旧实现打出 `state_change(IN_CALL→DISCONNECTED)` → `call_end` → `hangup`。
     */
    @Test
    fun peerLeftAfterConnectedStaysInWaitingPeerWithoutHangup() {
        val action = CallSurvivability.peerLeftAction(everConnected = true, mediaAlive = true)
        assertSame("曾连上过 ⇒ 不得挂断（KEEP_CALL）", PeerLeftAction.KEEP_CALL, action)

        val tracker = ConnectionStatusTracker()
        tracker.onCallStarted(0L)
        tracker.onMediaFrame(1_000L, MEDIA_SOURCE_SINK, ageMs = 20L)
        tracker.onConnectionLost(2_000L)
        val afterPeerLeft = tracker.onWaitingPeer(3_000L)
        assertEquals("必须回到 waiting_peer（不是 CONNECTED/FAILED）", ConnPhase.WAITING_PEER, afterPeerLeft.phase)
        assertEquals(ConnReason.NONE, afterPeerLeft.reason)
        assertEquals("等待对方加入", afterPeerLeft.title)
        assertFalse("等待期不得给出重试/失败入口（t64 口径）", afterPeerLeft.canRetry)
    }

    // ==================== ② ROOM_NOT_FOUND：可恢复态，不自动退出 ====================

    @Test
    fun roomLostDuringRejoinOrWithMediaKeepsCall() {
        // dl-b 场景：重连期拿到 ROOM_NOT_FOUND，且媒体仍在流 ⇒ 保持通话页 + 显式重建入口
        assertSame(RoomLostAction.KEEP_CALL, CallSurvivability.roomLostAction(rejoinContext = true, mediaAlive = true))
        // 重连语境（即使此刻无新鲜帧）⇒ 仍保持
        assertSame(RoomLostAction.KEEP_CALL, CallSurvivability.roomLostAction(rejoinContext = true, mediaAlive = false))
        // 媒体仍在流动 ⇒ 保持（语境缺失也不得退出）
        assertSame(RoomLostAction.KEEP_CALL, CallSurvivability.roomLostAction(rejoinContext = false, mediaAlive = true))
    }

    @Test
    fun roomLostOnFirstJoinStillEndsCall() {
        // 首次入房就找不到房间（从未在房内、也无媒体）⇒ 终态结束（captain 2026-09-13 口径不变）
        assertSame(RoomLostAction.END_CALL, CallSurvivability.roomLostAction(rejoinContext = false, mediaAlive = false))
    }

    // ============ ① IN_CALL 且未收到 peerLeft ⇒ 不 hangup / 不 call_end（captain 2026-09-16 指令①） ============
    // 服务端（t67）断线后保留席位 90 s 且**不下发 peerLeft**；客户端"自己重连不上"只说明本端信令断了，
    // **不说明对端离开**。因此本地判活（重连预算耗尽 SIGNAL_LOST）在通话中绝不允许自行结束通话/销毁会话。

    @Test
    fun inCallWithoutPeerLeftDoesNotHangupOrEndCall() {
        // 本世代曾连上过（IN_CALL）但此刻没有新鲜帧 ⇒ 仍必须保留通话页与会话
        assertSame(
            "IN_CALL + 未收到 peerLeft ⇒ 不得 hangup/不得 call_end",
            SignalLostAction.KEEP_CALL,
            CallSurvivability.signalLostAction(everConnected = true, mediaAlive = false),
        )
        // 媒体仍在流（dl-b：down_bps=326398）⇒ 更必须保留
        assertSame(SignalLostAction.KEEP_CALL, CallSurvivability.signalLostAction(everConnected = true, mediaAlive = true))
        // 状态机尚未标记"曾连上"但画面已在更新 ⇒ 同样保留
        assertSame(SignalLostAction.KEEP_CALL, CallSurvivability.signalLostAction(everConnected = false, mediaAlive = true))
    }

    @Test
    fun neverConnectedSignalLossEndsCallToAvoidDeadPage() {
        // 从未连上过且无媒体证据 ⇒ 允许结束（否则留在无信令、无画面的"半死不活"页面）
        assertSame(SignalLostAction.END_CALL, CallSurvivability.signalLostAction(everConnected = false, mediaAlive = false))
    }

    // ============ t71③ 重连并入会成功后的 ICE restart 门控（点名用例④） ============

    @Test
    fun rejoinWithIceDownTriggersRestartIce() {
        // ICE 掉了（DISCONNECTED/FAILED ⇒ iceDown）且没有新鲜媒体证据 ⇒ 必须触发一次 restartIce
        assertTrue(
            "rejoin + iceDown ⇒ 触发 restartIce",
            CallSurvivability.shouldRestartIceOnRejoin(
                everConnected = true, iceDown = true, mediaAlive = false, hasSelectedPair = true,
            ),
        )
        // iceDown 且无候选对同样触发
        assertTrue(
            CallSurvivability.shouldRestartIceOnRejoin(
                everConnected = true, iceDown = true, mediaAlive = false, hasSelectedPair = false,
            ),
        )
    }

    @Test
    fun healthyRejoinDoesNotTriggerRestartIce() {
        // 媒体证据新鲜 + 存在 selected pair ⇒ **不得**重启（健康通话不受打扰，即使 iceDown 只是抖动）
        assertFalse(
            CallSurvivability.shouldRestartIceOnRejoin(
                everConnected = true, iceDown = true, mediaAlive = true, hasSelectedPair = true,
            ),
        )
        assertFalse(
            CallSurvivability.shouldRestartIceOnRejoin(
                everConnected = true, iceDown = false, mediaAlive = true, hasSelectedPair = true,
            ),
        )
    }

    @Test
    fun firstConnectionNeverTriggersRestartIce() {
        // 首次建立连接（本世代尚未连上过）属正常握手，**不走**这条路径
        assertFalse(
            CallSurvivability.shouldRestartIceOnRejoin(
                everConnected = false, iceDown = true, mediaAlive = false, hasSelectedPair = false,
            ),
        )
    }

    // ============ t75 重连侧 8 s 兜底（可注入时钟：只传 waitedMs，绝不 sleep） ============

    @Test
    fun rejoinerFallbackFiresOnlyAtEightSecondBoundary() {
        // 7_999 ms 尚未到时 ⇒ 不发（给对端 offer 留足往返时间）
        assertFalse(
            CallSurvivability.shouldRejoinerFallbackOffer(
                awaitingPeerOffer = true, waitedMs = 7_999L,
                iceDown = true, mediaAlive = false, hasSelectedPair = false,
            ),
        )
        // 恰好 8_000 ms 且 ICE 未恢复（iceDown、无媒体）⇒ 兜底发起
        assertTrue(
            CallSurvivability.shouldRejoinerFallbackOffer(
                awaitingPeerOffer = true, waitedMs = CallSurvivability.REJOIN_OFFER_FALLBACK_MS,
                iceDown = true, mediaAlive = false, hasSelectedPair = false,
            ),
        )
        assertEquals(8_000L, CallSurvivability.REJOIN_OFFER_FALLBACK_MS)
    }

    @Test
    fun rejoinerFallbackSkippedWhenMediaIsHealthy() {
        // 媒体证据新鲜且存在 selected pair ⇒ 不发起（健康通话不受打扰，避免与在线侧 offer 撞 glare）
        assertFalse(
            CallSurvivability.shouldRejoinerFallbackOffer(
                awaitingPeerOffer = true, waitedMs = 60_000L,
                iceDown = true, mediaAlive = true, hasSelectedPair = true,
            ),
        )
    }

    @Test
    fun rejoinerFallbackSkippedWhenOfferAlreadyArrived() {
        // 已收到对端 offer（awaiting=false）⇒ 永不兜底，即使超时很久
        assertFalse(
            CallSurvivability.shouldRejoinerFallbackOffer(
                awaitingPeerOffer = false, waitedMs = 60_000L,
                iceDown = true, mediaAlive = false, hasSelectedPair = false,
            ),
        )
    }

    @Test
    fun rejoinerFallbackAlsoFiresWhenMediaEvidenceIsGone() {
        // iceDown=false 但没有媒体证据（路径已变更、旧候选对沉默）⇒ 超时也应兜底
        assertTrue(
            CallSurvivability.shouldRejoinerFallbackOffer(
                awaitingPeerOffer = true, waitedMs = 9_000L,
                iceDown = false, mediaAlive = false, hasSelectedPair = false,
            ),
        )
    }

    // ==================== ③ 媒体存活期间不显示 ICE 失败文案 ====================

    @Test
    fun iceFailureTextIsSuppressedWhileMediaAlive() {
        // dl-b 15:27:54 的文案来源
        assertFalse(
            CallSurvivability.shouldSurfaceError("ICE 连接失败：未获取到中继候选", mediaAlive = true),
        )
        assertFalse(CallSurvivability.shouldSurfaceError("连接中断（ICE 未连通）", mediaAlive = true))
        // 非 ICE 文案不受抑制（媒体存活也要把真正的问题告诉用户）
        assertTrue(CallSurvivability.shouldSurfaceError("服务端错误: ROOM_FULL", mediaAlive = true))
    }

    @Test
    fun everyErrorIsSurfacedWhenMediaIsNotAlive() {
        // 媒体不存活 ⇒ 任何错误文案都必须如实呈现（不得因为本闸门静默失败）
        assertTrue(CallSurvivability.shouldSurfaceError("ICE 连接失败", mediaAlive = false))
        assertTrue(CallSurvivability.shouldSurfaceError("中继不可用", mediaAlive = false))
        assertTrue(CallSurvivability.shouldSurfaceError("未连通", mediaAlive = false))
    }

    @Test
    fun roomLostNoticeDocumentsKeptCall() {
        // RecoverableState 是"不退出通话页"的载体：reason/notice 必须齐备（UI 直接渲染）
        val state = RecoverableState(reason = "ROOM_NOT_FOUND", mediaAlive = true, notice = "房间已被服务端回收（通话未结束）：可重新创建房间继续")
        assertEquals("ROOM_NOT_FOUND", state.reason)
        assertTrue("可恢复提示必须明确'通话未结束'，否则用户会以为已退房", state.notice.contains("未结束"))
        assertTrue(state.mediaAlive)
    }
}
