package com.example.webrtcdemo.ui.call

// ============================================================================
// t59：连接状态可见性状态机（纯 Kotlin，无 Android / Compose / org.webrtc 依赖）
// ----------------------------------------------------------------------------
// 为什么需要这一层：
//   真机 4G↔WiFi 缺陷（reports/29-connect-state-ui.md §1）中，连接**从未建立**
//   （`stats_sample … local=- mode=- remote=-`，无选中的候选对），但界面既不显示
//   "连接中"、也不报失败，只把远端画面最后一次渲染的**静止帧**留在屏幕上，
//   同时"编码速率"很低 ⇒ 用户完全无法判断发生了什么。
//   既有 UI 状态机只有布尔 `isConnecting`，且会被"编码器已在跑"（`impl=SelfVp9Libvpx`）
//   误清 ⇒ 必须换成按"是否真的连上"判定的三态状态机。
//
// 判定依据（全部来自既有日志/回调，无需改协议）：
//   * `stats_sample mode=P2P|RELAY`（`StatsSnapshot.connectionType` 非空）= 有选中的候选对；
//   * `pc_connection_state dtls=true state=CONNECTED` / `pc_ice_connection_state state=CONNECTED`
//     （经 `CallSession.Listener.onIceEvent(ICE_CONNECTION, …)` 上报）；
//   * 远端帧（`onFirstFrameRendered`）与 **下行字节**（`down_bps > 0`）——只有下行字节能证明
//     路径真的打通：无候选对时 `up_bps` 仍会增长（RTP sender 的 bytesSent，真机实测 ≈49 kbps），
//     故**绝不能用 up_bps 判活**。
//
// 单测见 app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt。
// ============================================================================

/** 通话连接阶段（UI 可见）。 */
enum class ConnPhase {
    /** 尚未连上（含"曾连上又断了、正在自动重连"）。 */
    CONNECTING,

    /** 已连上：选中候选对出现 / 传输 CONNECTED。 */
    CONNECTED,

    /** 失败：超过阈值仍未连上或无法自动恢复，需要用户点击重试。 */
    FAILED,
}

/** 连接阶段的原因（用于提示文案与诊断字段）。 */
enum class ConnReason {
    /** 无失败/无中断。 */
    NONE,

    /** 超时：15 s 内始终没有选中的候选对（`stats_sample local=- mode=-`）。 */
    TIMEOUT_NO_PAIR,

    /** 曾连上但 ICE/DTLS 掉线，且在宽限期内未能自动恢复。 */
    CONNECTION_LOST,

    /** 曾连上但远端媒体流停止（画面冻结），且在宽限期内未能恢复。 */
    REMOTE_FRAME_STALLED,

    /** 本地建会话失败（PeerConnection 创建失败等）。 */
    SESSION_START_FAILED,
}

/**
 * 连接状态快照（Compose 单向渲染用）。
 *
 * @property phase 当前阶段。
 * @property reason 阶段原因。
 * @property elapsedMs 自"进入通话/最近一次重试"起的已用时长（ms）。
 * @property sinceLossMs 自"最近一次中断"起的时长（ms）；未中断时为 0。
 * @property hasSelectedPair 是否已观察到选中的候选对。
 * @property remoteFrameReady 远端画面是否**新鲜**（近期确实收到远端帧/下行字节）。
 * @property remoteFrameStalled 远端画面是否已停滞（曾就绪但现在无下行）。
 * @property retryCount 本通话内用户点击重试的次数。
 */
data class ConnStatus(
    val phase: ConnPhase = ConnPhase.CONNECTING,
    val reason: ConnReason = ConnReason.NONE,
    val elapsedMs: Long = 0L,
    val sinceLossMs: Long = 0L,
    val hasSelectedPair: Boolean = false,
    val remoteFrameReady: Boolean = false,
    val remoteFrameStalled: Boolean = false,
    val retryCount: Int = 0,
) {
    /** 是否需要中央状态卡（未连上 / 已失败）。 */
    val showOverlay: Boolean
        get() = phase != ConnPhase.CONNECTED

    /**
     * 远端画面是否必须被遮罩。
     *
     * 取"未连上 或 没有新鲜远端帧"——这是"**不把静止帧当作已出画面**"的判定核心：
     * SurfaceViewRenderer 在 RTP 停止后会把最后一帧一直留在屏幕上。
     */
    val remoteDimmed: Boolean
        get() = phase != ConnPhase.CONNECTED || !remoteFrameReady

    /** 是否可一键重试（仅失败态）。 */
    val canRetry: Boolean
        get() = phase == ConnPhase.FAILED

    /** 已用秒数（向上取整，供"已等待 N 秒"文案与单测）。 */
    val elapsedSeconds: Long
        get() = (elapsedMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND

    /** 中断时长秒数（向上取整）；未中断为 0。 */
    val sinceLossSeconds: Long
        get() = (sinceLossMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND

    /**
     * 中央状态卡主标题（用户可见文案）。
     *
     * 注：本任务 inScope 不含 `res/values/strings.xml`，故与既有 `CallViewModel.NO_PEER_RESPONSE_NOTICE`
     * 一致地内联为 Kotlin 常量；后续若要 i18n 可迁移为字符串资源（已在报告 §8 登记）。
     */
    val title: String
        get() = when (phase) {
            ConnPhase.CONNECTED -> ""
            ConnPhase.CONNECTING -> when (reason) {
                ConnReason.CONNECTION_LOST -> "连接中断，正在重连…"
                ConnReason.REMOTE_FRAME_STALLED -> "画面已中断，正在恢复…"
                else -> "正在建立连接…"
            }

            ConnPhase.FAILED -> when (reason) {
                ConnReason.TIMEOUT_NO_PAIR -> "连接失败"
                ConnReason.CONNECTION_LOST -> "连接已断开"
                ConnReason.REMOTE_FRAME_STALLED -> "画面已中断"
                ConnReason.SESSION_START_FAILED -> "会话创建失败"
                ConnReason.NONE -> "连接失败"
            }
        }

    /** 中央状态卡副标题（含已用/中断时长，满足"含已用时长"的要求）。 */
    val detail: String
        get() = when (phase) {
            ConnPhase.CONNECTED -> ""
            ConnPhase.CONNECTING -> when (reason) {
                ConnReason.CONNECTION_LOST -> "已中断 $sinceLossSeconds 秒，等待自动恢复"
                ConnReason.REMOTE_FRAME_STALLED -> "已中断 $sinceLossSeconds 秒，等待画面恢复"
                else -> "已等待 $elapsedSeconds 秒"
            }

            ConnPhase.FAILED -> when (reason) {
                ConnReason.TIMEOUT_NO_PAIR ->
                    "${elapsedSeconds} 秒内未能建立媒体通道（NAT/防火墙可能阻断了候选对）"

                ConnReason.CONNECTION_LOST -> "连接中断后未能自动恢复"
                ConnReason.REMOTE_FRAME_STALLED -> "连接仍在，但未收到对端画面"
                ConnReason.SESSION_START_FAILED -> "会话未能建立"
                ConnReason.NONE -> "媒体通道未能建立"
            }
        }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/** 从 `onIceEvent(ICE_CONNECTION, detail)` 的 detail 解析出的连通性信号（t59，纯函数、可单测）。 */
enum class ConnSignal {
    /** 已连通（`CONNECTED` / `transport=CONNECTED` / `COMPLETED`）。 */
    CONNECTED,

    /** 掉线（`DISCONNECTED`）。 */
    DISCONNECTED,

    /** 失败（`FAILED`）。 */
    FAILED,

    /** 仍在尝试（`CHECKING` / `CONNECTING`）——不改变状态。 */
    IN_PROGRESS,

    /** 与本状态机无关的事件。 */
    IGNORED,
}

/**
 * 解析 ICE/传输状态详情的连通性信号。
 *
 * **陷阱**：`DISCONNECTED` 里**包含**子串 `CONNECTED`，必须先判 `DISCONNECTED`（真机日志里
 * `pc_ice_connection_state state=DISCONNECTED` 正是"曾连上又断"的唯一证据）。
 *
 * @param detail 形如 `CHECKING` / `CONNECTED` / `DISCONNECTED` / `FAILED` / `transport=CONNECTED`。
 */
fun parseConnSignal(detail: String): ConnSignal {
    val upper = detail.uppercase()
    return when {
        upper.contains("DISCONNECTED") -> ConnSignal.DISCONNECTED
        upper.contains("FAILED") -> ConnSignal.FAILED
        upper.contains("CONNECTED") || upper.contains("COMPLETED") -> ConnSignal.CONNECTED
        upper.contains("CHECKING") || upper.contains("CONNECTING") -> ConnSignal.IN_PROGRESS
        else -> ConnSignal.IGNORED
    }
}

/** 重试后需要的重新协商动作（t59）。 */
enum class RetryNegotiation {
    /** 立刻由本端重发 offer（host：沿用既有 `peerJoined → createOffer` 正常路径）。 */
    OFFER_NOW,

    /**
     * 延迟重发 offer（joiner）。
     *
     * 为什么 joiner 也要重发：对端已在房中、不会因为本端重建会话而重新发 offer；
     * 若 joiner 只重建不重发，会永远停在"已连接不上"的等待态。延迟是为了给 host 的
     * offer 让路，避免双方同时 re-offer（glare）。
     */
    OFFER_DELAYED,

    /** 对端尚未出现：什么都不做，等正常的 `peerJoined` 流程。 */
    NONE,
}

/**
 * 判定重试后的重新协商动作（纯函数，可单测）。
 *
 * @param role 本端角色（`host` / `joiner`）。
 * @param peerKnown 是否已知对端在房中（收到过对方的 offer/answer/候选）。
 * @param sawRemoteNegotiationSinceRetry 重试后是否已收到对端的 offer/answer（收到则无需本端再发）。
 */
fun retryNegotiationFor(
    role: String,
    peerKnown: Boolean,
    sawRemoteNegotiationSinceRetry: Boolean,
): RetryNegotiation = when {
    !peerKnown -> RetryNegotiation.NONE
    sawRemoteNegotiationSinceRetry -> RetryNegotiation.NONE
    role == ROLE_HOST_WIRE -> RetryNegotiation.OFFER_NOW
    else -> RetryNegotiation.OFFER_DELAYED
}

/** host 角色字面量（与 `CallViewModel` 的角色常量一致；文件内私有，避免与外层同名常量冲突）。 */
private const val ROLE_HOST_WIRE = "host"

/**
 * 连接状态可见性状态机（t59）。
 *
 * 三条规则：
 *  1. **只有真的连上才算连上**：`CONNECTED` 只由"选中候选对 / 传输 CONNECTED / 远端帧"触发；
 *     编码器在跑（`impl` 非空）**不是**连通证据 —— 这正是旧 UI 的根因。
 *  2. **超时可操作**：进入通话 15 s（[connectTimeoutMs]）仍未连上 ⇒ `FAILED(TIMEOUT_NO_PAIR)`；
 *     已连上后掉线/画面停滞，宽限期（[lostGraceMs]）内未恢复 ⇒ `FAILED`。
 *  3. **静止帧不算画面**：远端帧/下行字节超过 [frameStallMs] 没有更新 ⇒ 清 `remoteFrameReady`
 *     并回落到"中断"提示，`ConnStatus.remoteDimmed` 为真。
 */
class ConnectionStatusTracker(
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val frameStallMs: Long = DEFAULT_FRAME_STALL_MS,
    private val lostGraceMs: Long = DEFAULT_LOST_GRACE_MS,
) {

    /** 当前状态快照。 */
    var status: ConnStatus = ConnStatus()
        private set

    /** 是否已进入通话（未进入时 [onTick] 不做任何推进，避免把进程启动到进房的时间算作等待）。 */
    var active: Boolean = false
        private set

    private var startedAtMs = 0L
    private var lastFreshMediaMs = 0L
    private var lostAtMs = 0L
    private var retries = 0

    /**
     * 是否"曾经看到过新鲜远端媒体"。
     *
     * 不用 `lastFreshMediaMs > 0` 当哨兵：`0` 是合法时刻（单测直接以 0 为起点），
     * 用哨兵会与真实值冲突（本实现的第一版就踩了这个坑并被单测抓出）。
     */
    private var hasFreshMedia = false

    /** 进入通话（新世代开始 / 重试后重新计时）。 */
    fun onCallStarted(nowMs: Long): ConnStatus {
        active = true
        startedAtMs = nowMs
        lastFreshMediaMs = 0L
        hasFreshMedia = false
        lostAtMs = 0L
        status = ConnStatus(phase = ConnPhase.CONNECTING, retryCount = retries)
        return status
    }

    /** 本地建会话失败（`CallSession.start()` 返回 false）。 */
    fun onSessionFailed(nowMs: Long): ConnStatus {
        if (!active) {
            active = true
            startedAtMs = nowMs
        }
        status = status.copy(
            phase = ConnPhase.FAILED,
            reason = ConnReason.SESSION_START_FAILED,
            elapsedMs = nowMs - startedAtMs,
        )
        return status
    }

    /** 观察到选中的候选对（`stats_sample mode=P2P|RELAY`）。 */
    fun onSelectedPair(nowMs: Long): ConnStatus = markConnected(nowMs, viaFrame = false)

    /** 观察到传输层 CONNECTED（`pc_connection_state dtls=true state=CONNECTED`）。 */
    fun onTransportConnected(nowMs: Long): ConnStatus = markConnected(nowMs, viaFrame = false)

    /**
     * 收到远端帧（首帧回调）或观察到下行字节（`down_bps > 0`）。
     *
     * 远端帧是"媒体确实打通"的**充分证据**，故这里也直接判定为已连接
     * （即使 stats 的 `mode` 字段因故滞后）。
     */
    fun onRemoteFrame(nowMs: Long): ConnStatus = markConnected(nowMs, viaFrame = true)

    /** ICE/传输掉线（`DISCONNECTED`/`FAILED`）。 */
    fun onConnectionLost(nowMs: Long): ConnStatus {
        if (!active) return status
        if (status.phase != ConnPhase.CONNECTED) return status
        lostAtMs = nowMs
        lastFreshMediaMs = 0L
        hasFreshMedia = false
        status = status.copy(
            phase = ConnPhase.CONNECTING,
            reason = ConnReason.CONNECTION_LOST,
            sinceLossMs = 0L,
            remoteFrameReady = false,
            remoteFrameStalled = true,
        )
        return status
    }

    /** 每 ~1 s 的心跳：推进时长、判定超时与帧停滞。 */
    fun onTick(nowMs: Long): ConnStatus {
        if (!active) return status
        val elapsed = nowMs - startedAtMs
        when (status.phase) {
            ConnPhase.CONNECTED -> {
                // 画面停滞：曾就绪但超过阈值没有新的远端帧/下行字节
                if (status.remoteFrameReady &&
                    hasFreshMedia &&
                    nowMs - lastFreshMediaMs > frameStallMs
                ) {
                    lostAtMs = lastFreshMediaMs + frameStallMs
                    status = status.copy(
                        phase = ConnPhase.CONNECTING,
                        reason = ConnReason.REMOTE_FRAME_STALLED,
                        sinceLossMs = nowMs - lostAtMs,
                        remoteFrameReady = false,
                        remoteFrameStalled = true,
                        elapsedMs = elapsed,
                    )
                } else {
                    status = status.copy(elapsedMs = elapsed)
                }
            }

            ConnPhase.CONNECTING -> {
                val loss = if (lostAtMs > 0L) nowMs - lostAtMs else 0L
                val timedOut = when (status.reason) {
                    // 从未连上：自进入通话起 connectTimeoutMs
                    ConnReason.NONE -> elapsed >= connectTimeoutMs
                    // 中断类：自中断起 lostGraceMs（期间允许自动恢复）
                    else -> loss >= lostGraceMs
                }
                status = if (timedOut) {
                    status.copy(
                        phase = ConnPhase.FAILED,
                        reason = if (status.reason == ConnReason.NONE) {
                            ConnReason.TIMEOUT_NO_PAIR
                        } else {
                            status.reason
                        },
                        elapsedMs = elapsed,
                        sinceLossMs = loss,
                    )
                } else {
                    status.copy(elapsedMs = elapsed, sinceLossMs = loss)
                }
            }

            ConnPhase.FAILED -> status = status.copy(elapsedMs = elapsed)
        }
        return status
    }

    /** 用户点击重试：次数 +1，状态回到 CONNECTING 并重新计时。 */
    fun onRetry(nowMs: Long): ConnStatus {
        retries += 1
        active = true
        startedAtMs = nowMs
        lastFreshMediaMs = 0L
        hasFreshMedia = false
        lostAtMs = 0L
        status = ConnStatus(phase = ConnPhase.CONNECTING, retryCount = retries)
        return status
    }

    /** 通话已结束（挂断）：停止计时推进（`onTick` 变为空操作），状态保持不变。 */
    fun onCallEnded(): ConnStatus {
        active = false
        return status
    }

    private fun markConnected(nowMs: Long, viaFrame: Boolean): ConnStatus {
        if (!active) return status
        if (viaFrame) {
            hasFreshMedia = true
            lastFreshMediaMs = nowMs
        }
        lostAtMs = 0L
        status = status.copy(
            phase = ConnPhase.CONNECTED,
            reason = ConnReason.NONE,
            elapsedMs = nowMs - startedAtMs,
            sinceLossMs = 0L,
            hasSelectedPair = true,
            // 只有帧/下行字节才能把"画面就绪"置真；仅凭候选对不算拿到画面
            remoteFrameReady = status.remoteFrameReady || viaFrame,
            remoteFrameStalled = false,
        )
        return status
    }

    companion object {
        /** 进入通话后未连上的失败阈值（验收要求 15 s）。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L

        /** 远端媒体停滞阈值（stats 采样间隔 2 s，取 2 个采样周期）。 */
        const val DEFAULT_FRAME_STALL_MS = 4_000L

        /** 掉线/停滞后的自动恢复宽限期。 */
        const val DEFAULT_LOST_GRACE_MS = 8_000L
    }
}
