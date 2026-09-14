package com.example.webrtcdemo.webrtc

// ============================================================================
// ICE candidate 文本解析（t44 诊断加固；doc/14 §2.1 目录下的纯 Kotlin 工具）
// ----------------------------------------------------------------------------
// 存在原因：真机「两端连不通」时，日志里**看不到候选类型/地址/端口**，
// 无法判断本端到底 gather 到了 host / srflx / relay 中的哪一种、以及
// 对端发来的候选是什么类型。org.webrtc 只给 `IceCandidate.sdp` 字符串，
// 因此这里做**纯字符串解析**（不依赖 org.webrtc / Android / native），
// 既能在采集回调里打点，也能在纯 JVM 单测里覆盖（app/src/test）。
//
// 候选行格式（RFC 5245 / SDP）示例：
//   candidate:842163049 1 udp 1677729535 120.230.119.5 7627 typ srflx raddr 0.0.0.0 rport 0 generation 0
//   candidate:2998576043 1 udp 2122260223 192.168.10.7 41234 typ host generation 0
//   candidate:1847415856 1 udp 41885439 47.238.144.66 49160 typ relay raddr 0.0.0.0 rport 0
// 字段：candidate:<foundation> <component> <protocol> <priority> <address> <port> typ <type> [raddr <a> rport <p> …]
// ============================================================================

/**
 * 解析后的 ICE candidate 摘要（仅诊断用，不参与协议逻辑）。
 *
 * @property foundation 候选基础（同基础 = 同网卡/同类型）。
 * @property component 1=RTP、2=RTCP。
 * @property protocol `udp` / `tcp`。
 * @property address IP 字面量或主机名。
 * @property port 端口。
 * @property type `host` / `srflx` / `prflx` / `relay`（小写）；解析不到为 `unknown`。
 * @property relatedAddress `raddr`（仅 srflx/relay 有）。
 * @property relatedPort `rport`（仅 srflx/relay 有）。
 */
data class IceCandidateInfo(
    val foundation: String,
    val component: Int,
    val protocol: String,
    val address: String,
    val port: Int,
    val type: String,
    val relatedAddress: String = "",
    val relatedPort: Int = 0,
) {
    /** 日志字段：一行内给出"类型/协议/地址:端口"（doc/14 §9.1 `ice_candidate_*` 事件用）。 */
    fun summary(): String = "type=$type proto=$protocol addr=$address port=$port"

    /** 是否中继候选（决定连接模式 RELAY，也是"强制中继"对照复测的关键）。 */
    fun isRelay(): Boolean = type == TYPE_RELAY

    companion object {
        const val TYPE_HOST = "host"
        const val TYPE_SRFLX = "srflx"
        const val TYPE_PRFLX = "prflx"
        const val TYPE_RELAY = "relay"
        const val TYPE_UNKNOWN = "unknown"

        /**
         * 解析 `IceCandidate.sdp` 文本。
         *
         * @param sdp 候选行（org.webrtc 的 `IceCandidate.sdp`，不带 `a=` 前缀）。
         * @return 解析结果；格式不符时返回带 `type=unknown` 的结果而不是抛异常（日志路径不得抛）。
         */
        fun parse(sdp: String): IceCandidateInfo {
            // 【t49 修复】`candidate:` 与 foundation 处在**同一个 token** 内
            // （形如 `candidate:2998576043`）。原实现 `tokens.drop(1)` 把 foundation 整块丢掉，
            // 使 body 整体前移一格 ⇒ protocol 读到 priority、address 读到 port、port 读到字面量 `typ`
            // （实测 4 个单测失败：expected:<udp> but was:<2122260223> 等）。
            // 正确做法：只剥掉 `candidate:` 前缀，保留 foundation 作为 body[0]。
            val raw = sdp.trim().removePrefix("a=").split(' ').filter { it.isNotEmpty() }
            val body = raw.mapIndexed { index, token ->
                if (index == 0) token.removePrefix("candidate:") else token
            }
            // body = [foundation, component, protocol, priority, address, port, "typ", type, …]
            val foundation = body.getOrElse(0) { "" }
            val component = body.getOrElse(1) { "0" }.toIntOrNull() ?: 0
            val protocol = body.getOrElse(2) { "" }.lowercase()
            val address = body.getOrElse(4) { "" }
            val port = body.getOrElse(5) { "0" }.toIntOrNull() ?: 0
            val typIndex = body.indexOfFirst { it.equals("typ", ignoreCase = true) }
            val type = if (typIndex >= 0) body.getOrElse(typIndex + 1) { TYPE_UNKNOWN }.lowercase() else TYPE_UNKNOWN
            val raddrIndex = body.indexOfFirst { it.equals("raddr", ignoreCase = true) }
            val rportIndex = body.indexOfFirst { it.equals("rport", ignoreCase = true) }
            return IceCandidateInfo(
                foundation = foundation,
                component = component,
                protocol = protocol,
                address = address,
                port = port,
                type = type,
                relatedAddress = if (raddrIndex >= 0) body.getOrElse(raddrIndex + 1) { "" } else "",
                relatedPort = (if (rportIndex >= 0) body.getOrElse(rportIndex + 1) { "0" } else "0").toIntOrNull() ?: 0,
            )
        }

        /**
         * 统计 SDP 文本中 `a=candidate:` 行数（t44：证明"候选是否真的进入 SDP/信令"）。
         *
         * @param sdp 本地/远端会话描述全文。
         * @return 候选行数量（0 表示该 SDP 未携带候选 —— 例如 trickle 模式下的初始 offer/answer）。
         */
        fun countCandidates(sdp: String): Int = sdp.lineSequence().count { line ->
            val trimmed = line.trim()
            trimmed.startsWith("a=candidate:") || trimmed.startsWith("candidate:")
        }

        /**
         * 按类型统计 SDP 中的候选（`host=2,relay=1` 形式；t44）。
         *
         * @param sdp 会话描述全文。
         * @return 类型 → 数量的摘要；无候选返回 `-`。
         */
        fun summarizeSdpCandidates(sdp: String): String {
            val counts = LinkedHashMap<String, Int>()
            sdp.lineSequence().forEach { line ->
                val trimmed = line.trim().removePrefix("a=")
                if (trimmed.startsWith("candidate:")) {
                    val info = parse(trimmed)
                    counts[info.type] = (counts[info.type] ?: 0) + 1
                }
            }
            return if (counts.isEmpty()) "-" else counts.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }
}

/**
 * 候选类型计数器（诊断用）。
 *
 * 用途：ICE 超时兜底时一次性打印"本端/对端各类型候选数量"，从而**一次真机复测**
 * 就能区分「根本没 gather 到 relay」与「gather 到了但配对/检查失败」两种情况。
 */
class IceCandidateCounter {
    private val local = LinkedHashMap<String, Int>()
    private val remote = LinkedHashMap<String, Int>()

    /** 记录一条本端候选。 */
    @Synchronized
    fun addLocal(info: IceCandidateInfo) {
        local[info.type] = (local[info.type] ?: 0) + 1
    }

    /** 记录一条对端候选。 */
    @Synchronized
    fun addRemote(info: IceCandidateInfo) {
        remote[info.type] = (remote[info.type] ?: 0) + 1
    }

    /** `host=2,srflx=1,relay=2` 形式的本端摘要。 */
    @Synchronized
    fun localSummary(): String = summaryOf(local)

    /** 对端摘要。 */
    @Synchronized
    fun remoteSummary(): String = summaryOf(remote)

    /** 本端 relay 候选数（>0 才说明 TURN 分配成功）。 */
    @Synchronized
    fun localRelayCount(): Int = local[IceCandidateInfo.TYPE_RELAY] ?: 0

    private fun summaryOf(map: Map<String, Int>): String =
        if (map.isEmpty()) "-" else map.entries.joinToString(",") { "${it.key}=${it.value}" }
}
