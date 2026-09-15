package com.example.webrtcdemo.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection

// ============================================================================
// t65 / A5：TURN over TCP 回退（`turn:…?transport=tcp`）纯 JVM 单测
// ----------------------------------------------------------------------------
// 覆盖验收第 1/3/4 条：
//   * TURN 配置存在时，server 列表在 UDP 项**之后**追加 TCP 回退项（UDP 优先）；
//   * 凭据同源（来自信令下发的 `IceServerConfig`，不硬编码）；
//   * 开关关闭时不追加；无 TURN 配置时不追加（也不硬编码任何凭据）；
//   * 其它 ICE 语义不变（sdpSemantics / bundlePolicy / rtcpMuxPolicy /
//     continualGatheringPolicy / iceTransportsType）。
//
// 为什么测的是 `iceServersFor()` / `rtcConfiguration()` 而不是 `build()`：
// `build()` 会落 `rtc_config` 日志，而日志路径依赖 Android（`FileLogger`）⇒ 纯 JVM 下不可用
// （首轮离线运行即以 `ExceptionInInitializerError: FileLogger` 失败，证据在报告 §5）。
// 因此 t65 把"列表构建"与"配置装配"抽成**纯函数**（`build()` = 二者 + 一条日志）。
//
// 说明：本文件不在 t65 契约的 inScope 内（t65 inScope 仅 `WebRtcConfig.kt` / `CallSession.kt` / 报告），
// 按 t62 先例**在 acceptance 证据与报告中显式登记**，并在交付消息注明「待账本补齐」。
//
// 运行（宿主机，容器内无 JDK）：./gradlew --no-daemon :app:testDebugUnitTest
// ============================================================================

class TurnTcpFallbackTest {

    private fun iceConfig(
        stun: String = "stun:47.238.144.66:3478",
        turn: String = "turn:47.238.144.66:3478?transport=udp",
        user: String = "demo",
        pass: String = "demopass",
    ) = IceServerConfig(stunUrl = stun, turnUrl = turn, turnUsername = user, turnCredential = pass)

    /** 全部 server 的 URL（按尝试顺序展开）。 */
    private fun urlsOf(servers: List<PeerConnection.IceServer>): List<String> = servers.flatMap { it.urls }

    /** 【验收 1】UDP 项在前、TCP 回退项在后，且只多这一项。 */
    @Test
    fun addsTcpFallbackAfterUdpKeepingUdpFirst() {
        val servers = WebRtcConfig.iceServersFor(iceConfig())

        val urls = urlsOf(servers)
        assertEquals(3, servers.size)
        assertEquals("stun:47.238.144.66:3478", urls[0])
        assertEquals("turn:47.238.144.66:3478?transport=udp", urls[1])
        assertEquals("turn:47.238.144.66:3478?transport=tcp", urls[2])

        // UDP 仍为首选：udp 索引 < tcp 索引
        assertTrue(
            "UDP 必须排在 TCP 之前",
            urls.indexOfFirst { it.contains("transport=udp") } < urls.indexOfFirst { it.contains("transport=tcp") },
        )
    }

    /** 【验收 1】TCP 回退项与 UDP 项**同源凭据**（来自信令下发，不硬编码）。 */
    @Test
    fun tcpFallbackSharesCredentialsWithUdpServer() {
        val servers = WebRtcConfig.iceServersFor(iceConfig(user = "demo", pass = "demopass"))

        val udp = servers.first { s -> s.urls.any { it.contains("transport=udp") } }
        val tcp = servers.first { s -> s.urls.any { it.contains("transport=tcp") } }
        assertEquals("demo", tcp.username)
        assertEquals("demopass", tcp.password)
        assertEquals(udp.username, tcp.username)
        assertEquals(udp.password, tcp.password)
    }

    /** 【验收 1】开关关闭 ⇒ 不追加 TCP 回退（但 UDP 仍在）。 */
    @Test
    fun switchOffRemovesTcpFallback() {
        val servers = WebRtcConfig.iceServersFor(iceConfig(), turnTcpFallback = false)

        assertEquals(2, servers.size)
        assertTrue(urlsOf(servers).none { it.contains("transport=tcp") })
        assertTrue(urlsOf(servers).any { it.contains("transport=udp") })
    }

    /** 【验收 1】默认开关为**开启**（不传参即带 TCP 回退），且 `turnTcpFallbackActive` 与之一致。 */
    @Test
    fun switchDefaultsToEnabled() {
        assertTrue(WebRtcConfig.DEFAULT_TURN_TCP_FALLBACK)
        assertTrue(WebRtcConfig.turnTcpFallbackActive(iceConfig()))
        assertFalse(WebRtcConfig.turnTcpFallbackActive(iceConfig(), turnTcpFallback = false))
        assertFalse(WebRtcConfig.turnTcpFallbackActive(null))
        assertFalse(WebRtcConfig.turnTcpFallbackActive(iceConfig(turn = "")))
    }

    /** 【验收 1】无 TURN 配置（ice=null）⇒ 不硬编码任何 server/凭据。 */
    @Test
    fun noTurnConfigAddsNothing() {
        val servers = WebRtcConfig.iceServersFor(null)
        assertTrue(servers.isEmpty())
    }

    /** 【验收 1】仅 STUN（turn 为空）⇒ 不追加 TCP 回退。 */
    @Test
    fun stunOnlyConfigHasNoTcpFallback() {
        val servers = WebRtcConfig.iceServersFor(iceConfig(turn = ""))
        assertEquals(1, servers.size)
        assertTrue(urlsOf(servers).none { it.contains("transport=tcp") })
    }

    /** `turnTcpUrl` 的推导规则（纯函数边界）。 */
    @Test
    fun derivesTcpUrlFromTurnUrl() {
        assertEquals(
            "turn:47.238.144.66:3478?transport=tcp",
            WebRtcConfig.turnTcpUrl("turn:47.238.144.66:3478?transport=udp"),
        )
        // 无查询串 ⇒ 追加 transport=tcp
        assertEquals("turn:host:3478?transport=tcp", WebRtcConfig.turnTcpUrl("turn:host:3478"))
        // 其它参数保留
        assertEquals(
            "turn:host:3478?transport=tcp&foo=bar",
            WebRtcConfig.turnTcpUrl("turn:host:3478?transport=udp&foo=bar"),
        )
        // 大小写不敏感
        assertEquals("turn:host:3478?transport=tcp", WebRtcConfig.turnTcpUrl("turn:host:3478?TRANSPORT=UDP"))
        // turns:（TLS/TCP）与空值不推导
        assertNull(WebRtcConfig.turnTcpUrl("turns:host:5349?transport=tcp"))
        assertNull(WebRtcConfig.turnTcpUrl(""))
        assertNull(WebRtcConfig.turnTcpUrl("   "))
        // 非 turn 协议不猜
        assertNull(WebRtcConfig.turnTcpUrl("stun:host:3478"))
    }

    /** 【验收 3】其它 ICE 语义保持现有取值（不得被 TCP 回退改动影响）。 */
    @Test
    fun otherIceSemanticsUnchanged() {
        val servers = WebRtcConfig.iceServersFor(iceConfig())
        val all = WebRtcConfig.rtcConfiguration(servers, forceRelay = false)
        assertEquals(PeerConnection.SdpSemantics.UNIFIED_PLAN, all.sdpSemantics)
        assertEquals(PeerConnection.BundlePolicy.MAXBUNDLE, all.bundlePolicy)
        assertEquals(PeerConnection.RtcpMuxPolicy.REQUIRE, all.rtcpMuxPolicy)
        assertEquals(PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY, all.continualGatheringPolicy)
        assertEquals(PeerConnection.IceTransportsType.ALL, all.iceTransportsType)
        assertEquals(3, all.iceServers.size)

        // 强制中继仍为 RELAY（且与 TCP 回退叠加后不变）
        val relay = WebRtcConfig.rtcConfiguration(servers, forceRelay = true)
        assertEquals(PeerConnection.IceTransportsType.RELAY, relay.iceTransportsType)
        assertEquals(3, relay.iceServers.size)
    }
}
