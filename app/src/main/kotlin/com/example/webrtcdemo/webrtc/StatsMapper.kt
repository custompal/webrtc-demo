package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.model.StatsSnapshot
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

// ============================================================================
// 统计映射（doc/14 §7.4）—— 取代旧 JNI 回调面
// ----------------------------------------------------------------------------
// 数据来源全部是 org.webrtc 公共 API：
//   传输模式    candidate-pair(selected==true) 的本地/远端候选类型；含 relay → RELAY，否则 P2P
//   上下行速率  outbound-rtp.bytesSent / inbound-rtp.bytesReceived 的**差分 ÷ Δt**
//   可用带宽    candidate-pair.availableOutgoingBitrate（缺失回退 availableReceiveBitrate）
//   编码实现名  outbound-rtp.encoderImplementation（验收须 == "SelfVp9Libvpx"）
//
// 实现选择（已在报告中登记理由）：统一走 `RTCStats.getType()/getMembers()` 的**通用成员表**
// 读取，而不是逐个 typed stats 子类（RTCIceCandidatePairStats 等）。原因：
//   1) typed 子类的 getter 集随版本漂移（如 selected 只有成员表里有）；
//   2) §7.4 引用的字段名（bytesSent/encoderImplementation/availableOutgoingBitrate/
//      localCandidateType…）就是**统计成员名**本身，通用表是最直接的映射；
//   3) 候选类型在 candidate-pair 成员缺失时，再按 localCandidateId/remoteCandidateId
//      回查 local-candidate/remote-candidate 的 candidateType（兼容两种上游实现）。
// ============================================================================

/**
 * `RTCStatsReport` → [StatsSnapshot] 映射器。
 *
 * 有状态（保存上次字节数用于差分），应在通话内固定一个实例复用；通话结束调用 [reset]。
 */
class StatsMapper {

    private var lastBytesSent: Long = -1L
    private var lastBytesReceived: Long = -1L
    private var lastSampleMs: Long = 0L

    /** 清空差分基线（每次通话开始时调用）。 */
    @Synchronized
    fun reset() {
        lastBytesSent = -1L
        lastBytesReceived = -1L
        lastSampleMs = 0L
    }

    /**
     * 映射一次 stats。
     *
     * @param report SDK 回调的统计报告。
     * @param nowMs 采样时刻（Unix 毫秒），与上次采样共同决定 Δt。
     */
    @Synchronized
    fun map(report: RTCStatsReport, nowMs: Long): StatsSnapshot {
        val all: List<RTCStats> = report.statsMap.values.toList()

        val pair = selectCandidatePair(all)
        val localType = resolveCandidateType(all, pair, "localCandidateType", "localCandidateId")
        val remoteType = resolveCandidateType(all, pair, "remoteCandidateType", "remoteCandidateId")

        val bytesSent = sumMember(all, "outbound-rtp", "bytesSent")
        val bytesReceived = sumMember(all, "inbound-rtp", "bytesReceived")

        val upBitrate = diffBitrate(bytesSent, lastBytesSent, nowMs)
        val downBitrate = diffBitrate(bytesReceived, lastBytesReceived, nowMs)
        lastBytesSent = bytesSent
        lastBytesReceived = bytesReceived
        lastSampleMs = nowMs

        // 【修复（t10 首次真实编译）】原写法 `String? ?: String? ?: 0L` 会得到 `Any`，
        // 后面的 `.toInt()` 无候选可匹配。这里显式收敛为 Int（缺失或非数字一律 0）。
        val available = (
            member(pair, "availableOutgoingBitrate")
                ?: member(pair, "availableReceiveBitrate")
            )?.toDoubleOrNull()?.toInt() ?: 0
        val rttSeconds = member(pair, "currentRoundTripTime")?.toDoubleOrNull() ?: 0.0

        val snapshot = StatsSnapshot(
            connectionType = connectionTypeOf(localType, remoteType),
            upBitrateBps = upBitrate,
            downBitrateBps = downBitrate,
            availableOutgoingBitrateBps = available,
            encoderImplementation = firstMember(all, "outbound-rtp", "encoderImplementation").orEmpty(),
            localCandidateType = localType,
            remoteCandidateType = remoteType,
            rttMs = rttSeconds * 1000.0,
            sampledAtMs = nowMs,
        )
        AppLog.i(
            TAG,
            "stats_sample",
            mapOf(
                "mode" to snapshot.connectionType.ifEmpty { "-" },
                "up_bps" to snapshot.upBitrateBps.toString(),
                "down_bps" to snapshot.downBitrateBps.toString(),
                "avail_bps" to snapshot.availableOutgoingBitrateBps.toString(),
                "impl" to snapshot.encoderImplementation.ifEmpty { "-" },
                "local" to localType.ifEmpty { "-" },
                "remote" to remoteType.ifEmpty { "-" },
            )
        )
        return snapshot
    }

    private fun selectCandidatePair(all: List<RTCStats>): RTCStats? {
        val pairs = all.filter { it.type == TYPE_CANDIDATE_PAIR }
        if (pairs.isEmpty()) return null
        return pairs.firstOrNull { member(it, "selected") == "true" }
            ?: pairs.firstOrNull { member(it, "nominated") == "true" && member(it, "state") == "succeeded" }
            ?: pairs.firstOrNull { member(it, "state") == "succeeded" }
            ?: pairs.firstOrNull()
    }

    /** 候选类型：优先候选对成员，缺失时按 candidate id 回查候选统计的 `candidateType`。 */
    private fun resolveCandidateType(
        all: List<RTCStats>,
        pair: RTCStats?,
        memberKey: String,
        idKey: String,
    ): String {
        pair ?: return ""
        member(pair, memberKey)?.let { if (it.isNotBlank()) return it }
        val candidateId = member(pair, idKey) ?: return ""
        val candidate = all.firstOrNull { it.id == candidateId && it.type.contains("candidate") }
        return member(candidate, "candidateType").orEmpty()
    }

    /** §7.4：本地或远端含 relay → RELAY；二者都有值且均非 relay → P2P。 */
    private fun connectionTypeOf(localType: String, remoteType: String): String {
        if (localType.isEmpty() && remoteType.isEmpty()) return ""
        return if (localType.contains(TYPE_RELAY_TOKEN) || remoteType.contains(TYPE_RELAY_TOKEN)) {
            StatsSnapshot.TYPE_RELAY
        } else {
            StatsSnapshot.TYPE_P2P
        }
    }

    /** 按 (bytesNow - bytesPrev) * 8 / Δt(ms) * 1000 计算 bps。 */
    private fun diffBitrate(bytesNow: Long, bytesPrev: Long, nowMs: Long): Int {
        if (bytesPrev < 0L || lastSampleMs <= 0L) return 0
        val deltaMs = nowMs - lastSampleMs
        if (deltaMs <= 0L) return 0
        val deltaBytes = bytesNow - bytesPrev
        if (deltaBytes <= 0L) return 0
        return ((deltaBytes * 8L * 1000L) / deltaMs).toInt()
    }

    private fun sumMember(all: List<RTCStats>, type: String, memberKey: String): Long =
        all.filter { it.type == type }
            .sumOf { member(it, memberKey)?.toLongOrNull() ?: 0L }

    private fun firstMember(all: List<RTCStats>, type: String, memberKey: String): String? =
        all.asSequence()
            .filter { it.type == type }
            .mapNotNull { member(it, memberKey) }
            .firstOrNull { it.isNotBlank() }

    /**
     * 取某个 stats 成员的**文本**值。
     *
     * 【修复（t10 首次真实编译）】本版 jar 的 `RTCStats.members` 是 `Map<String, Any>`：
     * 成员值可能是 String / Double / Long / Boolean，直接当 String 用会编译失败
     * （原写法在 `Any?` 上调 `isNotBlank()` 必然报"未解析引用"）。
     * 这里统一 `as? String ?: toString()` 归一成文本，非字符串成员（如 `"true"`）同样可用。
     *
     * @return 归一化后的非空文本；成员缺失、为空串或字面 `"null"` 时返回 null。
     */
    private fun member(stats: RTCStats?, key: String): String? {
        val raw = stats?.members?.get(key) ?: return null
        val text = raw as? String ?: raw.toString()
        return text.takeIf { it.isNotBlank() && it != "null" }
    }

    private companion object {
        const val TAG = "stats"
        const val TYPE_CANDIDATE_PAIR = "candidate-pair"
        const val TYPE_RELAY_TOKEN = "relay"
    }
}
