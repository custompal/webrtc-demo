package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.ui.call.CallSurvivability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t83：远端候选计数的去重 / SDP 分账 / 日志限频（纯 JVM）
// ----------------------------------------------------------------------------
// 背景：真机「ICE 未连通」误报横幅里的"对端候选"数字用于定因，但计数器有两处缺陷：
//   ① 回放路径重复计数：t51 的"PC 未就绪入队 → 就绪回放"会把同一候选**再次**送进
//      `onRemoteIceCandidate()` ⇒ `addRemote` 记两次（真机 `host=6,srflx=1,relay=2` 可能被放大）；
//   ② SDP 内候选从不进计数器 ⇒ 对端只写在 SDP 里时显示 `对端候选 -`，与"确实没有"无法区分。
// 生产代码（`CallSession`）与本文件调用同一实现（`RemoteCandidateAccounting` / `IceCandidateCounter`）。
// ============================================================================

class RemoteCandidateAccountingTest {

    // ============ ① 去重计数：入队 1 次 + 回放 1 次 ⇒ 远端计数只 +1 ============

    @Test
    fun enqueueThenReplayCountsOnlyOnce() {
        val counter = IceCandidateCounter()
        val candidate = "candidate:842163049 1 udp 1677729535 192.168.1.100 45509 typ host generation 0"
        val info = IceCandidateInfo.parse(candidate)

        // 首次从信令入口进来（PC 未就绪 ⇒ 会被入队）：计一次
        assertTrue("首次进入必须计数", RemoteCandidateAccounting.shouldCount(viaReplay = false))
        if (RemoteCandidateAccounting.shouldCount(viaReplay = false)) {
            counter.addRemote(info)
        }
        // 回放（flushPendingRemote 逐条重调）：**不得**再计
        assertFalse("回放路径不得重复计数", RemoteCandidateAccounting.shouldCount(viaReplay = true))
        if (RemoteCandidateAccounting.shouldCount(viaReplay = true)) {
            counter.addRemote(info)
        }

        assertEquals("入队 1 次 + 回放 1 次 ⇒ 远端计数只 +1（host=1）", "host=1", counter.remoteSummary())

        // 对照：若回放也计数（修复前行为）就会变成 host=2 —— 已被上面的纯判定挡住
        counter.addRemote(info)
        assertEquals("再计一次才到 host=2（说明差异确实来自回放重复计数）", "host=2", counter.remoteSummary())
    }

    // ============ ② SDP 内候选独立计数 + `-` 不再是假阴性 ============

    @Test
    fun sdpCandidatesAreCountedIndependently() {
        val sdp = listOf(
            "v=0",
            "o=- 123 2 IN IP4 127.0.0.1",
            "a=candidate:1 1 udp 2122260223 192.168.1.10 50000 typ host",
            "a=candidate:2 1 udp 1686052607 47.238.144.66 49155 typ srflx",
            "a=candidate:3 1 udp 41885439 47.238.144.66 49156 typ relay",
            "a=end-of-candidates",
        ).joinToString("\r\n")

        assertEquals("SDP 内 3 条候选必须被数出来", 3, RemoteCandidateAccounting.countSdpCandidates(sdp))
        assertEquals("无候选的 SDP ⇒ 0", 0, RemoteCandidateAccounting.countSdpCandidates("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        // 带 `a=` 前缀与非前缀两种写法都要认（既有 summarizeSdpCandidates 的口径一致）
        assertEquals(1, RemoteCandidateAccounting.countSdpCandidates("candidate:9 1 tcp 100 10.0.0.1 9 typ host"))
    }

    @Test
    fun mergedSummaryRemovesDashAmbiguity() {
        // 两者皆空 ⇒ 明确写清"均无"，不再是裸 `-`
        assertEquals("-（trickled 与 SDP 内均无）", RemoteCandidateAccounting.mergedSummary("-", 0))
        assertEquals("-（trickled 与 SDP 内均无）", RemoteCandidateAccounting.mergedSummary("", 0))
        // 只有 SDP 有 ⇒ **不再是** `-`（这正是修复前真机"对端候选 -"的假阴性形态）
        val onlySdp = RemoteCandidateAccounting.mergedSummary("-", 3)
        assertTrue("只有 SDP 候选时必须可见", onlySdp.contains("SDP内 3"))
        assertFalse("不得再显示为裸 -", onlySdp == "-")
        // 只有 trickle 有 ⇒ 保持原摘要（不引入噪声）
        assertEquals("host=6,srflx=1,relay=2", RemoteCandidateAccounting.mergedSummary("host=6,srflx=1,relay=2", 0))
        // 两者都有 ⇒ 摘要 + SDP 分账
        assertEquals("host=2 + SDP内 1", RemoteCandidateAccounting.mergedSummary("host=2", 1))
    }

    // ============ ③ 逐条应用日志限频（首 3 条 + 每 10 条） ============

    @Test
    fun remoteCandidateLoggingIsRateLimited() {
        val logged = (1..25).filter { RemoteCandidateAccounting.shouldLogRemoteCandidate(it) }
        assertEquals("首 3 条 + 每 10 条（10/20）⇒ 共 5 条", listOf(1, 2, 3, 10, 20), logged)
        assertTrue(RemoteCandidateAccounting.shouldLogRemoteCandidate(1))
        assertFalse(RemoteCandidateAccounting.shouldLogRemoteCandidate(4))
        assertFalse(RemoteCandidateAccounting.shouldLogRemoteCandidate(9))
        assertTrue(RemoteCandidateAccounting.shouldLogRemoteCandidate(10))
    }

    // ============ t80 耦合守护：t83 改后的横幅文案仍必须被"恢复清除"识别 ============

    @Test
    fun mergedBannerStillRecognizedAsIceError() {
        // t83 后 CallSession 里两条横幅的实际形态（对端候选改为合并口径）
        val mergedFull = "host=6,srflx=1,relay=2 + SDP内 1"
        val mergedOnlySdp = "-（trickle 无）+ SDP内 2"
        val mergedNone = RemoteCandidateAccounting.mergedSummary("-", 0)
        val banners = listOf(
            "ICE 未连通（本端候选 host=6,srflx=1,relay=2；对端候选 $mergedFull）。可在诊断页打开「强制中继」后重试，并立即导出日志",
            "ICE 未连通（本端候选 host=6,srflx=1,relay=2；对端候选 $mergedOnlySdp）。可在诊断页打开「强制中继」后重试，并立即导出日志",
            "ICE 未连通（本端候选 host=6,srflx=1,relay=2；对端候选 $mergedNone）。可在诊断页打开「强制中继」后重试，并立即导出日志",
            "未获取到中继候选（TURN 无响应）——本端候选 host=6,srflx=1,relay=2；对端候选 $mergedNone。可在通话页点「重试」重建中继，或…",
        )
        for (banner in banners) {
            assertTrue("文案必须仍被 isIceErrorText 识别：$banner", CallSurvivability.isIceErrorText(banner))
            assertTrue(
                "t80 的恢复清除路径必须仍然生效：$banner",
                CallSurvivability.shouldClearIceError(banner, phaseConnected = true, mediaAlive = false),
            )
            assertTrue(
                "媒体判活同样能清：$banner",
                CallSurvivability.shouldClearIceError(banner, phaseConnected = false, mediaAlive = true),
            )
        }
    }
}
