package com.example.webrtcdemo.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// 重连错误处置策略 + 退避参数的 JUnit4 回归（doc/14 §8 / go-dev 实测边界）
// ----------------------------------------------------------------------------
// 起因：go-dev 的 TestE2E_ReconnectStaleSessionCausesRoomFull 证明
//   「静默掉线（TCP 未关）→ 服务端要等约 45 s 读超时才回收槽位 → 这期间重连 join 拿 ROOM_FULL」。
// 若把 ROOM_FULL 当终态抑制重连，移动网络下通话会**永久无法恢复**。
// 本测试把「ROOM_FULL → 有界退避重试」与「ROOM_NOT_FOUND/ROOM_EXPIRED → 终态抑制」锁成对照，
// 防止后人再把 ROOM_FULL 挪回终态表。
//
// 纯 JVM 可跑：SignalingErrorPolicy 与 rejoinDelayMs 都不触碰 Android API。
// 运行方式（宿主机）：./gradlew :app:testDebugUnitTest
// ============================================================================

class SignalingErrorPolicyTest {

    // ==================== 对照核心：ROOM_FULL 可重试 vs 房间销毁类终态 ====================

    @Test
    fun roomFullDuringRejoinIsRetryable() {
        // 掉线重连语境：必须重试，绝不能抑制（否则通话永久无法恢复）
        assertEquals(
            SignalingErrorPolicy.Action.RETRY_REJOIN,
            SignalingErrorPolicy.actionFor("ROOM_FULL", rejoinAfterDrop = true),
        )
    }

    @Test
    fun roomFullOnFirstJoinIsSurfacedNotRetried() {
        // 首次入房就撞满房：不抑制、也不做 50 s 静默轮询，直接把错误码交给 UI
        assertEquals(
            SignalingErrorPolicy.Action.SURFACE,
            SignalingErrorPolicy.actionFor("ROOM_FULL", rejoinAfterDrop = false),
        )
    }

    @Test
    fun destroyedRoomErrorsAreTerminalInBothContexts() {
        for (code in listOf("ROOM_NOT_FOUND", "ROOM_EXPIRED")) {
            assertEquals(
                "$code 在重连语境应为终态",
                SignalingErrorPolicy.Action.TERMINAL_SUPPRESS,
                SignalingErrorPolicy.actionFor(code, rejoinAfterDrop = true),
            )
            assertEquals(
                "$code 在首次入房也应为终态",
                SignalingErrorPolicy.Action.TERMINAL_SUPPRESS,
                SignalingErrorPolicy.actionFor(code, rejoinAfterDrop = false),
            )
        }
    }

    @Test
    fun clientFaultErrorsAreTerminal() {
        for (code in listOf("INVALID_MESSAGE", "NOT_IN_ROOM")) {
            assertEquals(
                "$code 应为终态（重试无意义）",
                SignalingErrorPolicy.Action.TERMINAL_SUPPRESS,
                SignalingErrorPolicy.actionFor(code, rejoinAfterDrop = true),
            )
        }
    }

    @Test
    fun unknownCodeIsSurfaced() {
        assertEquals(
            SignalingErrorPolicy.Action.SURFACE,
            SignalingErrorPolicy.actionFor("SOMETHING_NEW", rejoinAfterDrop = true),
        )
    }

    @Test
    fun terminalCodesNeverContainRoomFull() {
        // 防回归的直接断言：ROOM_FULL 一旦被加回终态表，这条立刻失败
        assertFalse(
            "ROOM_FULL 不得出现在终态码集合中（go-dev 实测其为暂时性）",
            SignalingErrorPolicy.TERMINAL_CODES.contains("ROOM_FULL"),
        )
        assertEquals(
            setOf("ROOM_NOT_FOUND", "ROOM_EXPIRED", "INVALID_MESSAGE", "NOT_IN_ROOM"),
            SignalingErrorPolicy.TERMINAL_CODES,
        )
    }

    // ==================== 退避参数：序列 + 覆盖 >45 s 窗口 ====================

    @Test
    fun rejoinBackoffIsExponentialWithCap() {
        assertEquals(1_000L, SignalingClient.rejoinDelayMs(1))
        assertEquals(2_000L, SignalingClient.rejoinDelayMs(2))
        assertEquals(4_000L, SignalingClient.rejoinDelayMs(3))
        assertEquals(8_000L, SignalingClient.rejoinDelayMs(4))
        // 到达上限后不再增长（避免长尾时对服务端过密）
        assertEquals(8_000L, SignalingClient.rejoinDelayMs(5))
        assertEquals(8_000L, SignalingClient.rejoinDelayMs(50))
    }

    @Test
    fun rejoinBackoffCoversServerReadTimeoutWindow() {
        // 服务端读超时约 45 s：累计等待必须 > 45 s，否则回收后的重试窗口会被错过
        var cumulative = 0L
        for (attempt in 1..SignalingClient.MAX_REJOIN_ATTEMPTS) {
            cumulative += SignalingClient.rejoinDelayMs(attempt)
        }
        assertTrue(
            "累计退避等待 ${cumulative}ms 应覆盖 >45000ms 的回收窗口",
            cumulative > 45_000L,
        )
    }

    @Test
    fun backoffHandlesNonPositiveAttempt() {
        // 边界：attempt <= 0 不应抛异常，退化为 base
        assertEquals(1_000L, SignalingClient.rejoinDelayMs(0))
        assertEquals(1_000L, SignalingClient.rejoinDelayMs(-3))
    }
    // ==================== 房间意图保留（verifier 追踪要点）====================

    @Test
    fun retryRejoinKeepsRoomIntent() {
        // 关键：ROOM_FULL 走重试时**绝不能**清空房间意图（pendingRoomId/currentRoomId），
        // 否则退避结束后不知道要 join 哪个房间，重试必然失败。
        assertFalse(
            "RETRY_REJOIN 必须保留房间意图",
            SignalingErrorPolicy.Action.RETRY_REJOIN.clearsRoomIntent,
        )
    }

    @Test
    fun terminalSuppressClearsRoomIntent() {
        assertTrue(
            "只有终态（房间已销毁/报文非法）才允许清空房间意图",
            SignalingErrorPolicy.Action.TERMINAL_SUPPRESS.clearsRoomIntent,
        )
    }

    @Test
    fun surfacingAnErrorKeepsRoomIntent() {
        // 首次入房遇满房 → SURFACE：不清意图，用户可手动重试
        assertFalse(SignalingErrorPolicy.Action.SURFACE.clearsRoomIntent)
    }

    // ================== 终态结束通话的判据（captain 2026-09-13） ==================
    // 背景：口径 B（严格）下「静默掉线后不可完整恢复」是已登记 known limitation（§11.4 D-7，low）。
    // 要求：终态失败必须「干脆回首页 + 明确提示」，**不得**停在通话页显示"重试中…"。

    @Test
    fun roomGoneEndsCall() {
        // 房间确已销毁（对端挂断 → 房间立即销毁）→ 通话不可能继续
        assertTrue(SignalingErrorPolicy.endsCall("ROOM_NOT_FOUND"))
        assertTrue(SignalingErrorPolicy.endsCall("ROOM_EXPIRED"))
    }

    @Test
    fun invalidMessageEndsCall() {
        // 报文非法 / 未入房：重试无意义
        assertTrue(SignalingErrorPolicy.endsCall("INVALID_MESSAGE"))
        assertTrue(SignalingErrorPolicy.endsCall("NOT_IN_ROOM"))
    }

    @Test
    fun roomFullDoesNotEndCallByItself() {
        // ROOM_FULL 在重连语境下是暂时性的：由 RETRY_REJOIN 有界重试；
        // 只有重试耗尽（SignalingClient 落到 DISCONNECTED）才结束通话。
        assertFalse(SignalingErrorPolicy.endsCall(SignalingErrorPolicy.CODE_ROOM_FULL))
    }

    @Test
    fun unknownCodeDoesNotEndCall() {
        // 未知码按 SURFACE 处理（保留意图、只呈现错误），不据此结束通话
        assertFalse(SignalingErrorPolicy.endsCall("SOME_UNKNOWN_CODE"))
        assertFalse(SignalingErrorPolicy.endsCall(""))
    }

    @Test
    fun endsCallCoversExactlyTerminalCodes() {
        // endsCall 与 TERMINAL_CODES 必须同集合：否则会出现「抑制了重连却还留在通话页」的不一致
        val covered = SignalingErrorPolicy.TERMINAL_CODES.filter { SignalingErrorPolicy.endsCall(it) }.toSet()
        assertEquals(SignalingErrorPolicy.TERMINAL_CODES, covered)
        // 且终态集合确实非空（防止集合被误清空后此断言恒真）
        assertTrue(SignalingErrorPolicy.TERMINAL_CODES.isNotEmpty())
    }

    // ============ t68：房间丢失的"可存活化"闸门（old-red / new-green 对照） ============
    // 背景（真机 dl-b 2026-09-15）：
    //   15:27:45.779 pong 单次丢失 ⇒ ws_pong_timeout ⇒ 重连
    //   15:28:02.247 server_error ROOM_NOT_FOUND
    //   15:28:02.248 state_change WAITING→DISCONNECTED
    //   15:28:02.248 call_end notice=通话已结束，可重新创建   ← **旧口径：自动退房**
    //   15:28:02.249 hangup / session_teardown
    // 当时 `down_bps=326398`（画面仍在更新）。新口径：只要处于"曾经在房内"的语境或媒体仍存活，
    // 就**不得**因为 ROOM_NOT_FOUND 自动退出通话页，而要给出可恢复态 + 显式「重新创建房间」。

    @Test
    fun roomGoneDuringRejoinNoLongerEndsCall_oldRedNewGreen() {
        // —— 旧口径（RED，修复前的行为）：ROOM_NOT_FOUND ⇒ endsCall == true ⇒ 上层 hangup 退出通话页
        assertTrue("旧口径确实会'自动退房'（本用例即缺陷复现的断言）", SignalingErrorPolicy.endsCall("ROOM_NOT_FOUND"))
        assertEquals(
            "旧口径下重连语境同样判终态",
            SignalingErrorPolicy.Action.TERMINAL_SUPPRESS,
            SignalingErrorPolicy.actionFor("ROOM_NOT_FOUND", rejoinAfterDrop = true),
        )

        // —— 新口径（GREEN）：同一错误码在重连语境 / 媒体存活时**保持通话页**
        assertTrue(
            "重连语境 + 媒体存活 ⇒ 保持通话页（dl-b 场景）",
            SignalingErrorPolicy.keepsCallOnRoomLoss("ROOM_NOT_FOUND", rejoinContext = true, mediaAlive = true),
        )
        assertTrue(
            "重连语境（此刻无新鲜帧）⇒ 仍保持通话页",
            SignalingErrorPolicy.keepsCallOnRoomLoss("ROOM_NOT_FOUND", rejoinContext = true, mediaAlive = false),
        )
        assertTrue(
            "媒体仍在流动 ⇒ 即使语境判定缺失也必须保持",
            SignalingErrorPolicy.keepsCallOnRoomLoss("ROOM_EXPIRED", rejoinContext = false, mediaAlive = true),
        )
        // 对照：首次入房就找不到房间 ⇒ 仍按原语义结束（不留半死不活的界面）
        assertFalse(
            SignalingErrorPolicy.keepsCallOnRoomLoss("ROOM_NOT_FOUND", rejoinContext = false, mediaAlive = false),
        )
    }

    @Test
    fun roomLossCodesCoverExactlyRoomGone() {
        // 可恢复集合只含"房间被回收"两类：报文类错误（INVALID_MESSAGE/NOT_IN_ROOM）不得被"可恢复"化，
        // 否则用户会在一个永远不会成功的房间上反复点「重新创建房间」。
        assertEquals(setOf("ROOM_NOT_FOUND", "ROOM_EXPIRED"), SignalingErrorPolicy.ROOM_LOSS_CODES)
        assertTrue(SignalingErrorPolicy.isRoomLossCode("ROOM_NOT_FOUND"))
        assertFalse(SignalingErrorPolicy.isRoomLossCode("INVALID_MESSAGE"))
        assertFalse(SignalingErrorPolicy.isRoomLossCode("NOT_IN_ROOM"))
        assertFalse(SignalingErrorPolicy.isRoomLossCode(SignalingErrorPolicy.CODE_ROOM_FULL))
        // 且 ROOM_LOSS_CODES 必须是 TERMINAL_CODES 的真子集（可恢复 ≠ 不再抑制重连）
        assertTrue(SignalingErrorPolicy.TERMINAL_CODES.containsAll(SignalingErrorPolicy.ROOM_LOSS_CODES))
        assertFalse(SignalingErrorPolicy.keepsCallOnRoomLoss("INVALID_MESSAGE", rejoinContext = true, mediaAlive = true))
    }

    // ============ ① 重连期 ROOM_NOT_FOUND 不得自动退出通话页（captain 2026-09-16 指令②） ============

    @Test
    fun reconnectRoomNotFoundDoesNotAutoExitCallPage() {
        // 真机 dl-b 15:28:02.248：`server_error ROOM_NOT_FOUND` → 旧实现 `call_end` + `hangup` 自动退房。
        // 新口径：重连期房间被回收 ⇒ 保持通话页（媒体仍在流时尤甚）+ 显式「重新创建房间」。
        assertTrue(
            "重连期 ROOM_NOT_FOUND ⇒ 保留通话页（不得自动退出）",
            SignalingErrorPolicy.keepsCallOnRoomLoss("ROOM_NOT_FOUND", rejoinContext = true, mediaAlive = true),
        )
        // 与 endsCall 的对照：endsCall 仍是"首次入房"口径（true = 应结束），两者语义分离且都由用例钉住
        assertTrue(SignalingErrorPolicy.endsCall("ROOM_NOT_FOUND"))
        // 首次入房（非重连语境且无媒体）才结束
        assertFalse(SignalingErrorPolicy.keepsCallOnRoomLoss("ROOM_NOT_FOUND", rejoinContext = false, mediaAlive = false))
    }

    // ============ t68：DISCONNECTED 的来源必须能区分"可存活"与"传输层终态" ============
    // 上层（通话页）在 onStateChanged(DISCONNECTED) 里据此决定：推迟给消息处理器判定 / 立即结束通话。

    @Test
    fun disconnectCauseSeparatesSurvivableFromFatal() {
        assertFalse("用户主动离开/进程退出必须结束通话", DisconnectCause.FATAL.survivable)
        assertTrue("peerLeft 不得被当作传输层终态", DisconnectCause.PEER_LEFT.survivable)
        assertTrue("重连期房间丢失必须给可恢复态", DisconnectCause.ROOM_LOST.survivable)
        assertTrue("重连预算耗尽 ≠ 通话结束（服务端宽限期未满、对端未离开）", DisconnectCause.SIGNAL_LOST.survivable)
        assertEquals(4, DisconnectCause.entries.size)
        // 唯一"不可存活"的来源只有 FATAL
        assertEquals(listOf(DisconnectCause.FATAL), DisconnectCause.entries.filter { !it.survivable })
    }
}

