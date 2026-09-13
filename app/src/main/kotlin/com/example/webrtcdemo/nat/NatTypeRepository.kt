package com.example.webrtcdemo.nat

import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.model.NatType
import com.example.webrtcdemo.nativebridge.NativeLoader
import com.example.webrtcdemo.nativebridge.NativeNatDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ============================================================================
// NAT 类型仓库（doc/14 §2.1 / §7.4）
// ----------------------------------------------------------------------------
//   本端：NativeCallbacks.onNatTypeDetected（§6.5）→ 这里 → StateFlow → UI
//   对端：信令 `natType` 消息（doc/09 §3.7）→ 这里 → StateFlow → UI
//
// 线程模型：onNatTypeDetected 由 native 探测线程调用；本对象只更新线程安全的
// StateFlow（Compose 在主线程 collect），因此天然满足 §6.5「立即切主线程再更新 UI」。
// ============================================================================

/**
 * NAT 类型仓库（进程内单例）。
 */
object NatTypeRepository {

    private const val TAG = "nat"

    /** 单次探测超时（ms）。 */
    const val DEFAULT_TIMEOUT_MS = 3_000L

    private val _localNat = MutableStateFlow(NatType.UNKNOWN)
    /** 本端 NAT（native 探测结果）。 */
    val localNat: StateFlow<NatType> = _localNat.asStateFlow()

    private val _localDetail = MutableStateFlow("")
    /** 本端探测证据串（§6.5 的 `detail`，诊断页展示）。 */
    val localDetail: StateFlow<String> = _localDetail.asStateFlow()

    private val _detecting = MutableStateFlow(false)
    /** 是否正在探测（UI 显示「检测中…」）。 */
    val detecting: StateFlow<Boolean> = _detecting.asStateFlow()

    private val _remoteNat = MutableStateFlow(NatType.UNKNOWN)
    /** 对端 NAT（信令交换结果）。 */
    val remoteNat: StateFlow<NatType> = _remoteNat.asStateFlow()

    @Volatile
    private var stunHost: String = ""
    @Volatile
    private var stunPort: Int = DEFAULT_STUN_PORT

    private const val DEFAULT_STUN_PORT = 3478

    /**
     * 用信令下发的 ICE server 配置并启动本端探测（§7.4「一次性」）。
     *
     * @param stunUrl 形如 `stun:47.238.144.66:3478`，来自 `created`/`joined`。
     */
    fun startLocalDetection(stunUrl: String) {
        if (!NativeLoader.ensureLoaded()) {
            AppLog.w(TAG, "nat_start_skipped", mapOf("reason" to "native_not_loaded"))
            _detecting.value = false
            _localNat.value = NatType.UNKNOWN
            return
        }
        val (host, port) = parseStunUrl(stunUrl)
        if (host.isEmpty()) {
            AppLog.w(TAG, "nat_start_skipped", mapOf("reason" to "bad_stun_url", "stun" to stunUrl))
            _detecting.value = false
            return
        }
        stunHost = host
        stunPort = port
        _detecting.value = true
        AppLog.i(TAG, "nat_start", mapOf("host" to host, "port" to port.toString()))
        NativeNatDetector.nativeDetect(host, port, DEFAULT_TIMEOUT_MS)
    }

    /** 取消探测（幂等）。 */
    fun cancel() {
        if (NativeLoader.isLoaded()) {
            NativeNatDetector.nativeCancel()
        }
        _detecting.value = false
    }

    /**
     * native 回调入口（§6.5，由 NativeCallbacks 转发）。
     *
     * @param natType 线上枚举字符串（§6.6）。
     * @param detail 证据串。
     */
    fun onNativeResult(natType: String, detail: String) {
        _localNat.value = NatType.fromWire(natType)
        _localDetail.value = detail
        _detecting.value = false
    }

    /**
     * 信令 `natType` 消息入口（对端 NAT，doc/09 §3.7）。
     *
     * @param natType 线上枚举字符串。
     */
    fun onRemoteResult(natType: String) {
        _remoteNat.value = NatType.fromWire(natType)
        AppLog.i(TAG, "peer_nat_received", mapOf("nat" to _remoteNat.value.wire))
    }

    /** 本端 NAT 的线上字符串（未探测时为 `Unknown`）。 */
    fun localWire(): String = _localNat.value.wire

    /**
     * 解析 STUN URL。
     *
     * 支持 `stun:host:port`、`stun:host`、`host:port`、`host`（缺省端口 3478）。
     *
     * @return `(host, port)`；解析失败 host 为空串。
     */
    fun parseStunUrl(stunUrl: String): Pair<String, Int> {
        val stripped = stunUrl.trim().removePrefix("stun:").removePrefix("turn:").substringBefore('?')
        if (stripped.isEmpty()) return "" to DEFAULT_STUN_PORT
        val parts = stripped.split(':')
        return when (parts.size) {
            1 -> parts[0] to DEFAULT_STUN_PORT
            else -> {
                val port = parts[1].toIntOrNull() ?: DEFAULT_STUN_PORT
                parts[0] to port
            }
        }
    }
}
