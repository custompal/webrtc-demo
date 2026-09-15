package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.log.AppLog
import org.webrtc.PeerConnection
// 【API 适配（t10 首次真实编译核对，L2 证据）】本版 jar 中 IceServer 是 **PeerConnection 的嵌套类**，
// 不存在顶层 `org.webrtc.IceServer`（javap/classdump 实测：`PeerConnection$IceServer`）。
import org.webrtc.PeerConnection.IceServer

// ============================================================================
// RTCConfiguration 与 SDP 策略（doc/14 §7.5，逐条冻结）
// ----------------------------------------------------------------------------
// - ICE server **只能**来自 created/joined 下发（禁止硬编码 demo:demopass）；
// - sdpSemantics = UNIFIED_PLAN、bundlePolicy = MAXBUNDLE、rtcpMuxPolicy = REQUIRE、
//   continualGatheringPolicy = GATHER_CONTINUALLY；
// - iceTransportsType = ALL（本版 jar 的字段名；旧文档写的 iceTransportPolicy 不存在），
//   诊断页可切 RELAY 以复现中继路径（§7.4）；
// - **禁止手改 SDP 文本**；VP9 通过 Vp9VideoEncoderFactory 的支持列表 +
//   RtpTransceiver.setCodecPreferences 强制（见 CallSession.applyCodecPreferences）。
//
// 本文件只做「配置构造」，不持有 native 资源。
// ============================================================================

/**
 * 信令下发的 ICE server 配置（§7.5/§8.2：字段名与 doc/09 逐字一致）。
 *
 * @property stunUrl `stun:host:port`。
 * @property turnUrl `turn:host:port?transport=udp`。
 * @property turnUsername TURN 用户名（全局 demo）。
 * @property turnCredential TURN 密码（全局 demopass）。
 */
data class IceServerConfig(
    val stunUrl: String,
    val turnUrl: String,
    val turnUsername: String,
    val turnCredential: String,
)

/**
 * PeerConnection 配置工厂。
 */
object WebRtcConfig {

    private const val TAG = "pc"

    /**
     * 【t65 / t58 A5】TURN over TCP 回退开关（**默认开启**）。
     *
     * 依据：服务端前置已复核通过（coturn 在 3478 有 TCP LISTEN、安全组已放行 TCP 3478，
     * 官方 `turnutils_uclient -t` 与探针均验证 `ALLOCATE/CreatePermission` 成功；见 reports/28 附录 D）。
     * TCP 回退**只作为 UDP 失败时的第二条路**：UDP server 仍排在前面（libwebrtc 按顺序尝试）。
     */
    const val DEFAULT_TURN_TCP_FALLBACK = true

    /**
     * 由 UDP 的 TURN URL 推导 **TURN over TCP** URL（纯函数，无副作用，可 JVM 单测）。
     *
     * 规则：
     *  * `turn:host:3478?transport=udp` → `turn:host:3478?transport=tcp`（**保留**其它查询参数）；
     *  * `turn:host:3478`（无查询串）→ `turn:host:3478?transport=tcp`；
     *  * `turns:…`（TLS，本身走 TCP）→ **null**（不重复添加）；
     *  * 空白或非 `turn:` 前缀 → null（保守：不猜测）。
     *
     * @param turnUrl 信令下发的 TURN URL。
     * @return TCP 回退 URL；无法推导时为 null。
     */
    fun turnTcpUrl(turnUrl: String): String? {
        val url = turnUrl.trim()
        if (url.isEmpty()) return null
        if (!url.lowercase().startsWith("turn:")) return null
        val q = url.indexOf('?')
        if (q < 0) return "$url?transport=tcp"
        val base = url.substring(0, q)
        val params = url.substring(q + 1).split('&').filter { it.isNotEmpty() }
        var replaced = false
        val rewritten = params.map { p ->
            val eq = p.indexOf('=')
            val key = if (eq >= 0) p.substring(0, eq) else p
            if (key.equals("transport", ignoreCase = true)) {
                replaced = true
                "transport=tcp"
            } else {
                p
            }
        }.toMutableList()
        if (!replaced) rewritten.add("transport=tcp")
        return base + "?" + rewritten.joinToString("&")
    }

    /**
     * 【t65】当前配置下是否真的会启用 TCP 回退（供 `pc_starting`/诊断一行判定）。
     *
     * @param ice 信令下发的 ICE server（null 表示尚未收到）。
     * @param turnTcpFallback 开关（默认 [DEFAULT_TURN_TCP_FALLBACK]）。
     */
    fun turnTcpFallbackActive(ice: IceServerConfig?, turnTcpFallback: Boolean = DEFAULT_TURN_TCP_FALLBACK): Boolean =
        turnTcpFallback && ice != null && turnTcpUrl(ice.turnUrl) != null

    /**
     * 【t65】构造 ICE server 列表（UDP 优先，可选追加 TCP 回退）。
     *
     * **纯函数、不落盘** —— 这样纯 JVM 单测可以直接断言"包含/不包含 TCP 回退项与顺序"
     * （`build()` 内部会调用本函数并落 `rtc_config`；日志路径依赖 Android，JVM 单测不可用）。
     *
     * @param ice 信令下发的 ICE server（null ⇒ 空列表，**不硬编码凭据**）。
     * @param turnTcpFallback 是否追加 `turn:…?transport=tcp` 回退。
     */
    fun iceServersFor(
        ice: IceServerConfig?,
        turnTcpFallback: Boolean = DEFAULT_TURN_TCP_FALLBACK,
    ): List<IceServer> {
        val servers = ArrayList<IceServer>(3)
        if (ice == null) return servers
        if (ice.stunUrl.isNotBlank()) {
            servers.add(IceServer.builder(ice.stunUrl).createIceServer())
        }
        if (ice.turnUrl.isNotBlank()) {
            // ① UDP 优先（保持既有形态/顺序不变）
            servers.add(
                IceServer.builder(ice.turnUrl)
                    .setUsername(ice.turnUsername)
                    .setPassword(ice.turnCredential)
                    .createIceServer()
            )
            // ② 【t65/A5】追加 TCP 回退（**同一组凭据**，仍来自信令下发，不硬编码）
            val tcpUrl = if (turnTcpFallback) turnTcpUrl(ice.turnUrl) else null
            if (tcpUrl != null) {
                servers.add(
                    IceServer.builder(tcpUrl)
                        .setUsername(ice.turnUsername)
                        .setPassword(ice.turnCredential)
                        .createIceServer()
                )
            }
        }
        return servers
    }

    /**
     * 【t65】按冻结策略构造 [PeerConnection.RTCConfiguration]（**纯函数、不落盘**）。
     *
     * 除 ICE server 列表外，其余语义逐条冻结（不得被 TCP 回退影响）：
     * `sdpSemantics=UNIFIED_PLAN`、`bundlePolicy=MAXBUNDLE`、`rtcpMuxPolicy=REQUIRE`、
     * `continualGatheringPolicy=GATHER_CONTINUALLY`、`iceTransportsType=ALL|RELAY`。
     */
    fun rtcConfiguration(
        servers: List<IceServer>,
        forceRelay: Boolean,
    ): PeerConnection.RTCConfiguration = PeerConnection.RTCConfiguration(ArrayList(servers)).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        // 【API 适配】本版 jar 无 `iceTransportPolicy` / `IceTransportPolicy`：
        // 真实字段是 `iceTransportsType`，类型 `PeerConnection.IceTransportsType`（枚举 ALL/RELAY/NOHOST/NONE）。
        iceTransportsType =
            if (forceRelay) PeerConnection.IceTransportsType.RELAY
            else PeerConnection.IceTransportsType.ALL
    }

    /**
     * 构造 [PeerConnection.RTCConfiguration]（并落 `rtc_config` 日志）。
     *
     * @param ice 信令下发的 ICE server（null 表示尚未收到 → 仅用空列表，不硬编码凭据）。
     * @param forceRelay 是否强制中继（诊断页开关，默认 false）。
     * @param turnTcpFallback 【t65/A5】是否追加 `turn:…?transport=tcp` 回退（默认开启；
     *        凭据与 UDP 项**完全同源**，仍来自信令下发，不硬编码）。
     */
    fun build(
        ice: IceServerConfig?,
        forceRelay: Boolean,
        turnTcpFallback: Boolean = DEFAULT_TURN_TCP_FALLBACK,
    ): PeerConnection.RTCConfiguration {
        val servers = iceServersFor(ice, turnTcpFallback)
        val turnTcpUsed = servers.any { server -> server.urls.any { it.contains("transport=tcp") } }
        AppLog.i(
            TAG,
            "rtc_config",
            mapOf(
                // 既有字段（不得删除改名）
                "stun" to (ice?.stunUrl ?: "-"),
                "turn" to (ice?.turnUrl ?: "-"),
                "force_relay" to forceRelay.toString(),
                // 【t65/A5】新增字段：是否启用 TCP 回退 + **实际**启用的 server 列表（按尝试顺序）
                "turn_tcp" to turnTcpUsed.toString(),
                "ice_servers" to servers.joinToString(",") { it.urls.joinToString("|") },
            )
        )
        return rtcConfiguration(servers, forceRelay)
    }

    /** 冻结的 track/stream id（§7.5）。 */
    const val VIDEO_TRACK_ID = "video0"

    /** 音频 track id（§7.5）。 */
    const val AUDIO_TRACK_ID = "audio0"

    /** stream id（§7.5）。 */
    const val STREAM_ID = "stream0"
}

/**
 * ICE server 缓存（进程内）。
 *
 * 首页 ViewModel 收到 `created`/`joined` 时写入，通话页 ViewModel 建立 PeerConnection 时读取。
 * 依据 §7.5：ICE server **只能**来自服务端下发，禁止硬编码凭据。
 */
object IceServerCache {

    @Volatile
    private var cached: IceServerConfig? = null

    /** 写入（created/joined 到达时）。 */
    fun put(config: IceServerConfig) {
        cached = config
    }

    /** 读取（可能为 null：尚未收到 created/joined）。 */
    fun get(): IceServerConfig? = cached

    /** 清空（挂断/回首页）。 */
    fun clear() {
        cached = null
    }
}
