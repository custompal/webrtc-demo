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
     * 构造 [PeerConnection.RTCConfiguration]。
     *
     * @param ice 信令下发的 ICE server（null 表示尚未收到 → 仅用空列表，不硬编码凭据）。
     * @param forceRelay 是否强制中继（诊断页开关，默认 false）。
     */
    fun build(ice: IceServerConfig?, forceRelay: Boolean): PeerConnection.RTCConfiguration {
        val servers = ArrayList<IceServer>(2)
        if (ice != null) {
            if (ice.stunUrl.isNotBlank()) {
                servers.add(IceServer.builder(ice.stunUrl).createIceServer())
            }
            if (ice.turnUrl.isNotBlank()) {
                servers.add(
                    IceServer.builder(ice.turnUrl)
                        .setUsername(ice.turnUsername)
                        .setPassword(ice.turnCredential)
                        .createIceServer()
                )
            }
        }
        AppLog.i(
            TAG,
            "rtc_config",
            mapOf(
                "stun" to (ice?.stunUrl ?: "-"),
                "turn" to (ice?.turnUrl ?: "-"),
                "force_relay" to forceRelay.toString(),
            )
        )
        return PeerConnection.RTCConfiguration(servers).apply {
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
