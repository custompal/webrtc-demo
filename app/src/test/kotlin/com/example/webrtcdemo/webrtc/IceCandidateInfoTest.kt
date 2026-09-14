package com.example.webrtcdemo.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// ICE candidate 文本解析 + 类型计数的 JUnit4 回归（t44 诊断加固）
// ----------------------------------------------------------------------------
// 背景：真机「两端连不通」时，日志里看不到候选类型/地址/端口，无法区分
// 「本端根本没 gather 到 relay」与「gather 到了但配对/检查失败」。
// `IceCandidateInfo.parse` 把 `IceCandidate.sdp` 解析成 type/protocol/address/port，
// `IceCandidateCounter` 按类型累加 —— 二者都是纯 Kotlin（不依赖 org.webrtc/native），
// 因此可在纯 JVM 下回归；样例取自真机 webrtc.log 中出现的候选形态。
// ============================================================================

class IceCandidateInfoTest {

    private val hostLine =
        "candidate:2998576043 1 udp 2122260223 192.168.10.7 41234 typ host generation 0 ufrag aB3 network-id 1"
    private val srflxLine =
        "candidate:842163049 1 udp 1677729535 120.230.119.5 7627 typ srflx raddr 0.0.0.0 rport 0 generation 0"
    private val relayLine =
        "candidate:1847415856 1 udp 41885439 47.238.144.66 49160 typ relay raddr 0.0.0.0 rport 0 network-id 3"

    @Test
    fun `解析 host 候选`() {
        val info = IceCandidateInfo.parse(hostLine)
        assertEquals("host", info.type)
        assertEquals("udp", info.protocol)
        assertEquals("192.168.10.7", info.address)
        assertEquals(41234, info.port)
        assertEquals("2998576043", info.foundation)
        assertEquals(1, info.component)
        assertFalse(info.isRelay())
    }

    @Test
    fun `解析 srflx 候选并带 raddr rport`() {
        val info = IceCandidateInfo.parse(srflxLine)
        assertEquals("srflx", info.type)
        assertEquals("120.230.119.5", info.address)
        assertEquals(7627, info.port)
        assertEquals("0.0.0.0", info.relatedAddress)
        assertEquals(0, info.relatedPort)
    }

    @Test
    fun `解析 relay 候选（中继路径判定的关键）`() {
        val info = IceCandidateInfo.parse(relayLine)
        assertEquals("relay", info.type)
        assertTrue(info.isRelay())
        assertEquals(49160, info.port)
    }

    @Test
    fun `兼容 a= 前缀与多余空白`() {
        val info = IceCandidateInfo.parse("a=candidate:1 1 udp 100 10.0.0.1 5000 typ host")
        assertEquals("host", info.type)
        assertEquals(5000, info.port)
    }

    @Test
    fun `非法输入不抛异常且标记 unknown`() {
        val info = IceCandidateInfo.parse("not-a-candidate")
        assertEquals(IceCandidateInfo.TYPE_UNKNOWN, info.type)
        assertEquals(0, info.port)
    }

    @Test
    fun `计数器按类型累加本端与对端`() {
        val counter = IceCandidateCounter()
        counter.addLocal(IceCandidateInfo.parse(hostLine))
        counter.addLocal(IceCandidateInfo.parse(hostLine))
        counter.addLocal(IceCandidateInfo.parse(relayLine))
        counter.addRemote(IceCandidateInfo.parse(srflxLine))
        assertEquals("host=2,relay=1", counter.localSummary())
        assertEquals("srflx=1", counter.remoteSummary())
        assertEquals(1, counter.localRelayCount())
    }

    @Test
    fun `空计数器摘要为连字符`() {
        val counter = IceCandidateCounter()
        assertEquals("-", counter.localSummary())
        assertEquals("-", counter.remoteSummary())
        assertEquals(0, counter.localRelayCount())
    }

    @Test
    fun `统计 SDP 中的候选行数与类型分布`() {
        val sdp = """
            v=0
            o=- 1 2 IN IP4 127.0.0.1
            m=video 9 UDP/TLS/RTP/SAVPF 98
            a=candidate:1 1 udp 2122260223 192.168.10.7 41234 typ host
            a=candidate:2 1 udp 1677729535 120.230.119.5 7627 typ srflx raddr 0.0.0.0 rport 0
            a=candidate:3 1 udp 41885439 47.238.144.66 49160 typ relay raddr 0.0.0.0 rport 0
            a=end-of-candidates
        """.trimIndent()
        assertEquals(3, IceCandidateInfo.countCandidates(sdp))
        assertEquals("host=1,srflx=1,relay=1", IceCandidateInfo.summarizeSdpCandidates(sdp))
        assertEquals("-", IceCandidateInfo.summarizeSdpCandidates("v=0\r\na=end-of-candidates"))
        assertEquals(0, IceCandidateInfo.countCandidates("v=0"))
    }
}
