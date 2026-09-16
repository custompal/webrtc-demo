package com.example.webrtcdemo.ui.call

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.webrtcdemo.config.AppConfig
import com.example.webrtcdemo.diag.LogExporter
import com.example.webrtcdemo.encoder.EncoderRateBus
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.model.CallUiState
import com.example.webrtcdemo.model.IceEvent
import com.example.webrtcdemo.model.IceEventType
import com.example.webrtcdemo.model.StatsSnapshot
import com.example.webrtcdemo.nat.NatTypeRepository
import com.example.webrtcdemo.signaling.ConnectionState
import com.example.webrtcdemo.signaling.DisconnectCause
import com.example.webrtcdemo.signaling.SignalingClient
import com.example.webrtcdemo.signaling.SignalingErrorPolicy
import com.example.webrtcdemo.signaling.SignalingHolder
import com.example.webrtcdemo.signaling.SignalingIdentity
import com.example.webrtcdemo.signaling.SignalingMessage
import com.example.webrtcdemo.webrtc.CallSession
import com.example.webrtcdemo.webrtc.IceServerCache
import com.example.webrtcdemo.webrtc.IceServerConfig
import com.example.webrtcdemo.webrtc.WebRtcEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicInteger

// ============================================================================
// 通话页 ViewModel（doc/14 §2.1 / §7.4；界面契约 doc/10 §3.2/§5.2）
// ----------------------------------------------------------------------------
// 职责：
//   1. 建立会话（WebRtcEngine → CallSession → 采集 + PeerConnection）；
//   2. 信令编排（doc/09 §5）：peerJoined→createOffer、offer→answer、answer/ice/natType；
//   3. UI 状态：连接模式 P2P/RELAY、上下行速率、本端/对端 NAT、编码码率、首帧、错误；
//   4. 操作：静音/摄像头切换/挂断/导出日志。
//
// 事件来源（§7.4）：ICE 与 stats 全部来自 org.webrtc（不再跨 JNI）；
// 本端 NAT 来自 NativeCallbacks.onNatTypeDetected（§6.5）→ NatTypeRepository。
// ============================================================================

/**
 * 通话页 ViewModel。
 */
class CallViewModel(application: Application) :
    AndroidViewModel(application),
    SignalingClient.Listener,
    CallSession.Listener {

    private val _uiState = MutableStateFlow(CallUiState())

    /** 通话 UI 状态。 */
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _iceEvents = MutableStateFlow<List<IceEvent>>(emptyList())

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)

    /** 本地视频轨（渲染预览）。 */
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)

    /** 远端视频轨（绑定时可为 null；到达后自动绑定）。 */
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _navigateHome = MutableStateFlow(false)

    /** 是否应返回首页（挂断/对端离开/错误）。 */
    val navigateHome: StateFlow<Boolean> = _navigateHome.asStateFlow()

    private var client: SignalingClient? = null

    private var session: CallSession? = null

    // ===================== t53：会话槽位（每次通话必须全新会话） =====================
    // 真机缺陷：反复进出房间后，joiner 在新 PeerConnection 就绪前就 answer_create/sent，
    // 导致该端 up_bps 恒 0（详见 reports/23-session-lifecycle.md）。
    // 根因之一就是"旧会话没有被彻底替换"：旧实现的 `if (started) return` 让重复 initCall
    // 变成静默空操作，新房间会跑在上一次通话遗留的 CallSession/PeerConnection 上。

    /**
     * 当前通话槽位（纯 Kotlin 状态机，见 [CallSessionSlot]）。
     *
     * `beginCall()` 返回"上一代仍活跃"时必须先关闭旧会话，绝不复用其 PC/轨道/统计循环。
     */
    private val slot = CallSessionSlot()

    /** 本次通话的进程内递增序号（`call_init` 起的所有关键事件都带 `seq`）。 */
    private var callSeq = 0

    /** 本次通话实际使用的 [CallSession.sessionId]（0 表示尚未建立）。 */
    private var sessionId = 0

    private var sessionReady = false

    private var peerJoined = false

    private var role = ""

    // ===================== t51：远端消息预队列与"对端无响应"看门狗 =====================
    // 真机 room 66DZFT 证据：offer(16:18:34.953)/候选(16:18:34.980…)比 pc_starting(16:18:35.244)
    // 早 0.3–0.5 s 到达，此时 `session` 仍为 null ⇒ 旧实现只打日志就丢弃（候选）或弹错（offer），
    // host 因此永远停在 HAVE_LOCAL_OFFER。这里改为**先入队**，`start()` 成功后**按到达顺序回放**。
    private val pendingRemote = PendingRemoteMessages()

    /** 看门狗是否已武装（避免重复计时）。 */
    private var peerWatchdogArmed = false

    /** 是否已观察到"对端活着"的证据（answer 或远端候选或连接就绪）。 */
    @Volatile
    private var peerResponseSeen = false

    /**
     * 通话是否已结束（挂断已开始）。
     *
     * 作用有二：① `hangup()` 幂等 —— 信令回调（`onStateChanged(DISCONNECTED)`）可能在 `leave()`
     * 同步触发时再次进入挂断路径，需要闸门防止重入/递归；
     * ② 区分"用户主动挂断"与"终态失败退出"，避免给正常挂断也弹结束提示。
     */
    private var callEnded = false

    /** 本端 NAT 是否已经上报（§3.7 一次性交换）。 */
    @Volatile
    private var natTypeSent = false

    // ===================== t59：连接状态可见性 / 一键重试 =====================
    // 真机 4G↔WiFi 缺陷（reports/29-connect-state-ui.md §1）：连接**从未建立**（无选中候选对，
    // `stats_sample … local=- mode=- remote=-`），但界面只把远端画面的最后一帧（静止帧）留在屏幕上，
    // "连接中"遮罩又被"编码器已在跑"（`impl=SelfVp9Libvpx`）错误关闭 ⇒ 用户看不到任何状态。

    /** 连接状态机（纯 Kotlin，可 JVM 单测：ConnectionStatusTrackerTest）。 */
    private val connTracker = ConnectionStatusTracker()

    private val _connStatus = MutableStateFlow(ConnStatus())

    /**
     * 连接状态（t59）：`connecting`/`connected`/`failed` + 已用时长 + 是否可重试。
     *
     * 与 [CallUiState.isConnecting] 的关系：本字段是**唯一真源**，`isConnecting` 由它派生
     * （见 [publishConnStatus]），UI 用本字段渲染状态卡与重试按钮。
     */
    val connStatus: StateFlow<ConnStatus> = _connStatus.asStateFlow()

    /** 最近一次 stats（`no_selected_pair` 诊断要带上"当时到底看到了什么"）。 */
    @Volatile
    private var lastStats: StatsSnapshot? = null

    /** 上一次 `no_selected_pair` 落盘时刻（按 [NO_PAIR_LOG_INTERVAL_MS] 限频）。 */
    private var lastNoPairLogMs = 0L

    /** 重试后是否有"延迟重发 offer"待执行（收到对端 offer/answer 即取消）。 */
    private var retryReofferPending = false

    /** 【t60】本世代是否已自动做过"中继不可用 ⇒ 重新 gathering"（每世代最多一次）。 */
    private var autoRegatherDone = false

    // ===================== t63：帧存活（每帧时间戳）诊断状态 =====================

    /** 上一次 `remote_frame_liveness` 记录的来源（变化即立刻落盘）。 */
    private var lastFrameLivenessSource: String = MEDIA_SOURCE_NONE

    /** 上一次 `remote_frame_liveness` 落盘时刻（按 [FRAME_LIVENESS_LOG_INTERVAL_MS] 限频）。 */
    private var lastFrameLivenessLogMs = 0L

    // ===================== t64：等待对端 / 重试计时起点 =====================

    /**
     * 本世代是否**真的连上过**（`phase == CONNECTED`）。
     *
     * 用途：`peerLeft` 时的收尾口径 —— 见 [onMessage] 的 `PeerLeft` 分支。
     */
    private var everConnectedInGeneration = false

    /** 【t64】等待态起点（仅用于 `retry_clock_started` 的 `waited_ms` 诊断）。 */
    private var waitingSinceMs = 0L

    // ===================== t68：通话可存活化（媒体存活期不退出通话页） =====================
    // 真机缺陷（2026-09-15，用户报告）：「视频没断，UI 却显示 ICE 失败，过一会自动退出房间」。
    // 三条根因（均有真机日志定位）：
    //   ① `SignalingClient` pong **单次**丢失即判断线（dl-b 15:27:45.779 `ws_pong_timeout timeout_ms=5000`）
    //      ⇒ 服务端回收房间 ⇒ 重连得 `ROOM_NOT_FOUND`；
    //   ② `peerLeft`/`ROOM_NOT_FOUND` 经 `onStateChanged(DISCONNECTED)` **自动挂断退出**
    //      （dl-a:7516-7520 `peer_left → state_change → call_end/hangup`，当时 `down_bps=2047967`；
    //        dl-b:9717-9720 `ROOM_NOT_FOUND → DISCONNECTED → call_end/hangup`，当时画面仍在更新）；
    //   ③ `ice_down` 被用来驱动用户可见的失败文案（dl-b 15:27:54.733
    //      `phase=connecting reason=connection_lost media_age_ms=2705 pair=true`，视频随后自行恢复）。
    // 纯判定集中在 `CallSurvivability`（可 JVM 单测）；本处只做"接线 + 诊断落盘"。

    /**
     * 可恢复态（t68）：房间被服务端回收，但通话页**必须保留**（媒体仍在流时更是如此）。
     *
     * 非空 ⇒ 通话页显示 [RecoverableState.notice] 与显式的「重新创建房间」入口。
     */
    private val _recoverable = MutableStateFlow<RecoverableState?>(null)

    /** 可恢复态（t68）；`null` 表示无待处理的可恢复状态。 */
    val recoverable: StateFlow<RecoverableState?> = _recoverable.asStateFlow()

    /** 【t68】「重新创建房间」已发起、正在等待服务端 `created`（避免重复发起）。 */
    private var recreatePending = false

    /** 【t68】上一次"ICE 失败文案被抑制"的落盘时刻（限频，避免每秒刷屏）。 */
    private var lastIceSuppressedLogMs = 0L

    // ===================== t75：防 glare 的重连重协商分工 =====================

    /**
     * 【t75】是否处于"重连入会成功（`Joined`）、正在等待对端 offer"的状态。
     *
     * 仅重连侧（非 host）会置真；一旦收到 `Offer`（或被兜底发起后）即置假。
     */
    private var awaitingPeerOfferAfterRejoin = false

    /** 【t75】`Joined` 时刻（用于计算"8 s 内未收到 offer"；超时判定在既有 1 s 心跳里做，不 sleep）。 */
    private var rejoinOfferWaitSinceMs = 0L

    init {
        // 编码目标码率：来自 Vp9VideoEncoder.setRateAllocation（§7.4，本地直通）
        viewModelScope.launch {
            EncoderRateBus.bitrateBps.collect { bps ->
                _uiState.update { it.copy(encoderBitrate = bps) }
            }
        }
        viewModelScope.launch {
            NatTypeRepository.localNat.collect { nat ->
                _uiState.update { it.copy(localNatType = if (nat.wire == "Unknown") "" else nat.wire) }
            }
        }
        viewModelScope.launch {
            NatTypeRepository.remoteNat.collect { nat ->
                _uiState.update { it.copy(remoteNatType = if (nat.wire == "Unknown") "" else nat.wire) }
            }
        }

        // 本端 NAT 探测完成后上报对端（doc/09 §3.7 / §8.2「natType 交换」）：一次性
        viewModelScope.launch {
            NatTypeRepository.localNat.collect { maybeSendNatType() }
        }

        // 本端/对端 peerId（§8.2 + 架构裁定 D-3：host 由对端 ID 取反推导）—— UI 展示用
        viewModelScope.launch {
            SignalingIdentity.selfPeerId.collect { id -> _uiState.update { it.copy(selfPeerId = id) } }
        }
        viewModelScope.launch {
            SignalingIdentity.remotePeerId.collect { id -> _uiState.update { it.copy(remotePeerId = id) } }
        }

        // 【t59】连接状态心跳（1 s）：推进"已等待 N 秒"，并在阈值（15 s）到达时把状态推进到
        // `FAILED(TIMEOUT_NO_PAIR)` ⇒ 界面显示可操作失败提示 + 一键重试（见 CallScreen）。
        // 旧实现没有任何"连接是否真的建立"的计时，因此既能一直显示静止帧、也不会给出失败提示。
        viewModelScope.launch {
            while (true) {
                delay(CONN_TICK_MS)
                if (callEnded || !connTracker.active) continue
                // 【t60/A7】把"配了 TURN 但没有中继候选"传给状态机 ⇒ 超时时给出**显式子原因**
                // NO_RELAY_CANDIDATE（而不是笼统的 TIMEOUT_NO_PAIR）。
                val relayMissing = session?.relayMissing() == true
                // 【t63】媒体存活：优先用**每帧都会触发**的 `VideoSink.onFrame` 时间戳（renderer 池），
                // 次选 `down_bps > 0`（真的在收）。二者都不新鲜才算"画面没在更新"。
                val now = System.currentTimeMillis()
                val liveness = WebRtcEngine.rendererPool()?.remoteFrameLiveness(now)
                val frameAge = liveness?.ageMs ?: Long.MAX_VALUE
                val downBps = lastStats?.downBitrateBps ?: 0
                val source = when {
                    frameAge <= FRAME_ALIVE_MS -> MEDIA_SOURCE_SINK
                    downBps > 0 -> MEDIA_SOURCE_DOWN_BPS
                    else -> MEDIA_SOURCE_NONE
                }
                if (source != MEDIA_SOURCE_NONE) {
                    publishConnStatus(connTracker.onMediaFrame(now, source, frameAge))
                }
                logFrameLivenessIfNeeded(now, source, frameAge, liveness?.frames ?: 0L, downBps)
                publishConnStatus(connTracker.onTick(now, relayMissing))
                // 【t75】重连侧兜底：`Joined` 后 8 s 仍未收到对端 offer 且 ICE 未恢复 ⇒ 由本端兜底发起
                // 一次带 iceRestart 的 offer（否则在线侧若也在重连/不在场，会永久停在"信令已恢复、画面黑"）。
                // 超时用**时间戳**判定（既有 1 s 心跳即评估点，不新增 sleep/协程）；纯判定可 JVM 单测。
                if (evaluateRejoinOfferFallback(now)) {
                    // 诊断主键统一为 `restart_ice reason=rejoin initiator=…`（captain 2026-09-16 定案；`rejoin_ice_restart …` 为旧写法）
                    restartIceThenOffer(
                        initiator = INITIATOR_REJOINER,
                        trigger = TRIGGER_JOINED,
                        logEvent = "restart_ice reason=rejoin initiator=rejoiner",
                        extra = mapOf(
                            "fallback" to "offer_timeout",
                            "waited_ms" to (now - rejoinOfferWaitSinceMs).toString(),
                            "threshold_ms" to CallSurvivability.REJOIN_OFFER_FALLBACK_MS.toString(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 【t63】落盘帧存活诊断（`remote_frame_liveness source=… age_ms=N frames=… down_bps=…`）。
     *
     * 只在来源变化或每 [FRAME_LIVENESS_LOG_INTERVAL_MS] 记一条 —— 下一轮复测时**一行就能判定**
     * "到底有没有每帧时间戳"（这是本缺陷最容易再次误判的地方）。
     */
    private fun logFrameLivenessIfNeeded(now: Long, source: String, ageMs: Long, frames: Long, downBps: Int) {
        val sourceChanged = source != lastFrameLivenessSource
        if (!sourceChanged && now - lastFrameLivenessLogMs < FRAME_LIVENESS_LOG_INTERVAL_MS) return
        lastFrameLivenessSource = source
        lastFrameLivenessLogMs = now
        AppLog.i(
            TAG,
            "remote_frame_liveness",
            mapOf(
                "source" to source,
                "age_ms" to if (ageMs == Long.MAX_VALUE) "-1" else ageMs.toString(),
                "frames" to frames.toString(),
                "down_bps" to downBps.toString(),
                "phase" to connStatus.value.phase.name.lowercase(),
                "view" to connStatus.value.remoteFrameReady.toString(),
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ),
        )
    }

    /**
     * 初始化通话（导航到 CallScreen 时调用；**每次进入通话都必须调用**）。
     *
     * 【t53】与旧实现的关键区别：旧实现是 `if (started) return` —— 一旦同一 ViewModel 被复用
     * （返回后再次进入、配置变更、Compose 重新进入组合），第二次 `initCall` 变成**静默空操作**，
     * 新房间就"继承"了上一次通话的 `CallSession`/`PeerConnection`/轨道/编码器状态。
     * 现在改为：每次调用都开启新一世代（[CallSessionSlot.beginCall]），
     * 上一代仍活跃则**先彻底关闭**，然后一律新建 `CallSession` + 新建 `PeerConnection`。
     *
     * @param roomId 房间号。
     * @param role `host` / `joiner`。
     */
    fun initCall(roomId: String, role: String) {
        // 【t53】幂等闸门：**同一次通话**的重复 initCall 必须保持空操作。
        // `CallScreen` 用 `LaunchedEffect(Unit)` 调用本方法；Activity 因配置变更重建时组合会重建、
        // 该方法会在**保留下来的同一个 ViewModel** 上再次执行 —— 若此处无条件重建会话，
        // 会把正在进行的通话拆掉。只有"换了房间/角色"或"上一次通话已结束"才允许开新一代。
        if (slot.decideInit(roomId, role, callEnded) == InitDecision.IGNORE) {
            AppLog.i(
                TAG,
                "call_init_ignored",
                mapOf(
                    "reason" to "same_call",
                    "room" to roomId,
                    "role" to role,
                    "seq" to callSeq.toString(),
                    "session" to "s$sessionId",
                    "pc_ready" to slot.peerReady.toString(),
                ),
            )
            return
        }
        beginGeneration(roomId, role, REASON_INIT)
    }

    /**
     * 一键重试（t59）：连接失败后由用户点击触发。
     *
     * 重试必须走 t53 的**世代化**路径（`slot.beginCall` ⇒ 上一代仍活跃则先 `close()`），
     * 即**新建 `CallSession` + 新建 `PeerConnection`**，绝不复用旧 PC/轨道/编码器状态 ——
     * 效果等价于"挂断后重新进入房间"，但**保留房间与信令连接**（不 `leave()`，避免对端收到
     * `peerLeft` 而被连带挂断）。
     *
     * 重试后如何让协商重新开始，见 [retryNegotiationFor] 与 [beginGeneration]：
     * host 立刻重发 offer；joiner **延迟**重发（避免与 host 的 offer 相撞）。
     */
    fun retryConnection() {
        val room = _uiState.value.roomId
        val currentRole = role
        val status = connStatus.value
        if (callEnded || room.isBlank() || currentRole.isBlank()) {
            AppLog.w(
                TAG,
                "retry_invoked",
                mapOf(
                    "result" to "ignored",
                    "reason" to "no_active_call",
                    "room" to room,
                    "role" to currentRole,
                    "seq" to callSeq.toString(),
                ),
            )
            return
        }
        AppLog.i(
            TAG,
            "retry_invoked",
            mapOf(
                "room" to room,
                "role" to currentRole,
                "phase" to status.phase.name.lowercase(),
                "reason" to status.reason.name.lowercase(),
                "elapsed_ms" to status.elapsedMs.toString(),
                "retry" to (status.retryCount + 1).toString(),
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ),
        )
        beginGeneration(room, currentRole, REASON_RETRY)
    }

    /**
     * 开启新一代通话（t53 世代化 + t59 连接状态机复位）。
     *
     * @param reason [REASON_INIT]（首次进入通话）或 [REASON_RETRY]（一键重试）。
     */
    private fun beginGeneration(roomId: String, role: String, reason: String) {
        // 【t59】重试前先记住"对端是否已在房中"：releaseSession 会复位 peerResponseSeen，
        // 而"重试后要不要由本端重发 offer"完全取决于这个事实。
        val peerKnownBefore = peerResponseSeen || _uiState.value.remotePeerId.isNotBlank()
        val staleActive = slot.beginCall(roomId, role)
        callSeq = CALL_SEQ.incrementAndGet()
        if (staleActive) {
            AppLog.w(
                TAG,
                if (reason == REASON_RETRY) "session_retry" else "call_reinit",
                mapOf("seq" to callSeq.toString(), "room" to roomId, "role" to role),
            )
            // 旧会话必须被关闭：它可能仍在跑 stats 采样、仍持有 PeerConnection
            releaseSession(reason)
        }
        // 【t53】新一代通话必须把"上一次通话的遗留闸门/一次性标记"全部复位：
        // 旧实现靠"每次新的 ViewModel 实例"来保证这些字段是干净的，一旦同一 VM 被复用，
        // 残留的 `callEnded` 会让新一代通话**无法挂断**、残留的 `natTypeSent` 会让本端 NAT
        // 不再上报、残留的 ICE 事件会串到新通话。
        callEnded = false
        natTypeSent = false
        _iceEvents.value = emptyList()
        this.role = role
        _uiState.update {
            it.copy(roomId = roomId, role = role, isConnecting = true, error = null)
        }

        // 【t59】连接状态机复位：首次进入 = 开始计时；重试 = 重试次数 +1 且重新计时。
        // `publishConnStatus` 是 `CallUiState.isConnecting` 的**唯一写入方**（见其 KDoc）。
        // 【t64】连接计时的**起点**取决于"对端是否已在房间里"：
        //   * 还没人 ⇒ 进入 `WAITING_PEER`：**不计时、不给重试/失败**（用户需求：等待期不要提示重试）；
        //   * 已知对端（一键重试、或上一世代对端已出现）⇒ 立即起算（沿用 t59/t61 的两档阈值）。
        val startedAt = System.currentTimeMillis()
        publishConnStatus(
            when {
                reason == REASON_RETRY -> connTracker.onRetry(startedAt)
                // 【t68】重建房间 = **全新空房间**（本端是 host）⇒ 回到"等待对方加入"，不计时
                reason == REASON_RECREATE -> connTracker.onWaitingPeer(startedAt)
                peerKnownBefore -> connTracker.onCallStarted(startedAt)
                else -> connTracker.onWaitingPeer(startedAt)
            },
        )
        if (connTracker.status.phase == ConnPhase.WAITING_PEER) {
            AppLog.i(
                TAG,
                "ui_conn_state_enter",
                mapOf(
                    "phase" to "waiting_peer",
                    "reason" to "peer_absent",
                    "room" to roomId,
                    "role" to role,
                    "seq" to callSeq.toString(),
                ),
            )
        }
        everConnectedInGeneration = false
        lastNoPairLogMs = 0L
        autoRegatherDone = false
        // 【t68】新一代通话 ⇒ 清掉上一代的可恢复态与限频闸门
        _recoverable.value = null
        // 【t75】新一代通话：清掉"等待对端 offer"的兜底计时
        recreatePending = false
        lastIceSuppressedLogMs = 0L
        awaitingPeerOfferAfterRejoin = false
        rejoinOfferWaitSinceMs = 0L
        val app = getApplication<Application>()

        // 【t59】重试不重置信令身份：房间与对端都没变，清掉反而会让 UI 丢失对端 ID
        if (reason != REASON_RETRY) {
            SignalingIdentity.reset()
        }
        val connection = SignalingHolder.getOrCreate(AppConfig.signalingUrl(app))
        connection.listener = this
        client = connection

        if (!WebRtcEngine.initialize(app)) {
            // t25：把**真实异常类名/message**带进用户可见文案（原先只有笼统一句，真机排障极难）。
            val detail = WebRtcEngine.lastFailureDetail()
            // 【t53】初始化失败必须结束本世代，否则 `decideInit` 会把"同房间重试"判成重复调用而拒绝
            slot.endCall()
            publishConnStatus(connTracker.onSessionFailed(System.currentTimeMillis()))
            fail(
                if (detail.isNullOrBlank()) {
                    "WebRTC 引擎初始化失败（native 库缺失或初始化异常）"
                } else {
                    "WebRTC 引擎初始化失败（$detail）"
                }
            )
            return
        }
        val factory = WebRtcEngine.factory()
        val capture = WebRtcEngine.mediaCapture()
        if (factory == null || capture == null) {
            slot.endCall()
            publishConnStatus(connTracker.onSessionFailed(System.currentTimeMillis()))
            fail("WebRTC 引擎组件缺失")
            return
        }

        // 【t59】重试时的重新协商准备（必须在 `start()` 之前 —— `onReady()` 会立刻尝试发 offer）
        if (reason == REASON_RETRY) {
            retryReofferPending = false
            when (retryNegotiationFor(role, peerKnownBefore, sawRemoteNegotiationSinceRetry = false)) {
                RetryNegotiation.OFFER_NOW -> {
                    // host：沿用既有 `peerJoined → createOffer` 正常路径
                    peerJoined = true
                    AppLog.i(
                        TAG,
                        "retry_reoffer",
                        mapOf(
                            "role" to role,
                            "mode" to "immediate",
                            "peer_known" to "true",
                            "seq" to callSeq.toString(),
                        ),
                    )
                }

                RetryNegotiation.OFFER_DELAYED -> AppLog.i(
                    TAG,
                    "retry_reoffer",
                    mapOf(
                        "role" to role,
                        "mode" to "delayed",
                        "delay_ms" to RETRY_REOFFER_DELAY_MS.toString(),
                        "seq" to callSeq.toString(),
                    ),
                )

                RetryNegotiation.NONE -> Unit
            }
        }

        // 【t53】**无条件**新建会话：绝不复用 `session` 字段里的旧对象
        val callSession = CallSession(
            factory = factory,
            mediaCapture = capture,
            signaling = connection,
            audioTrack = WebRtcEngine.audioTrack(),
            listener = this,
        )
        session = callSession
        sessionId = callSession.sessionId
        val ok = callSession.start(IceServerCache.get(), AppConfig.forceRelay(app))
        if (!ok) {
            // 【t44】start 失败后必须把 session 置空：否则 `session` 非空但 `peerConnection` 为空，
            // 后续 offer/answer 会走进"看起来有会话、实际静默丢弃"的路径（正是真机缺陷①的形态）。
            slot.endCall()
            releaseSession("start_failed")
            publishConnStatus(connTracker.onSessionFailed(System.currentTimeMillis()))
            fail("创建 PeerConnection 失败")
            return
        }
        slot.markPeerReady()
        _localVideoTrack.value = callSession.currentVideoTrack()
        // 【t51】会话就绪 ⇒ 把此前排队等待的远端消息（offer/answer/候选）按到达顺序回放
        flushPendingRemoteQueue(callSession)
        // 若 NAT 探测在进入通话页之前就完成了（常见：host 在等待对端时探测完），这里补发一次
        maybeSendNatType()
        // 【t59】joiner 侧延迟重发 offer（给 host 的 offer 让路，避免双方同时 re-offer）
        if (reason == REASON_RETRY &&
            retryNegotiationFor(role, peerKnownBefore, sawRemoteNegotiationSinceRetry = false) ==
            RetryNegotiation.OFFER_DELAYED
        ) {
            scheduleRetryReoffer()
        }
        AppLog.i(
            TAG,
            "call_init",
            mapOf(
                "room" to roomId,
                "role" to role,
                "reason" to reason,
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
                "pc_ready" to slot.peerReady.toString(),
            ),
        )
    }

    // ============================ 用户操作 ============================

    /** 静音切换（麦克风）。 */
    fun toggleMute() {
        val muted = !_uiState.value.isMuted
        session?.setAudioEnabled(!muted)
        _uiState.update { it.copy(isMuted = muted) }
    }

    /** 摄像头开关。 */
    fun toggleCamera() {
        val on = !_uiState.value.isCameraOn
        session?.setVideoEnabled(on)
        _uiState.update { it.copy(isCameraOn = on) }
    }

    /** 切换前后摄（诊断/演示用）。 */
    fun switchCamera() {
        WebRtcEngine.mediaCapture()?.switchCamera()
    }

    /** 挂断：发 leave → 关会话 → 回首页（doc/09 §5.2）。幂等闸门防止信令回调重入导致的递归挂断。 */
    fun hangup() {
        if (callEnded) return
        callEnded = true
        AppLog.i(TAG, "hangup", mapOf("seq" to callSeq.toString(), "session" to "s$sessionId"))
        // 【t68】挂断即清掉可恢复态（避免返回首页后仍留着"重新创建房间"入口）
        _recoverable.value = null
        // 【t75】新一代通话：清掉"等待对端 offer"的兜底计时
        recreatePending = false
        client?.leave()
        // 【t53】会话释放纪律：旧 CallSession 必须 close()（PC/统计循环/看门狗一并停）
        slot.endCall()
        releaseSession("hangup")
        // 【t59】停止连接状态心跳
        connTracker.onCallEnded()
        IceServerCache.clear()
        NatTypeRepository.cancel()
        SignalingHolder.release()
        _navigateHome.value = true
    }

    /** 已消费「回首页」事件。 */
    fun onNavigateHomeConsumed() {
        _navigateHome.value = false
    }

    /**
     * 远端首帧（SurfaceViewRenderer.onFirstFrameRendered，§7.3）。
     *
     * 【t59】连接未建立期间**不得**把这一帧当作"已出画面"：该回调只说明解码器吐过一帧，
     * 真机缺陷里连接从未建立（无选中候选对），画面其实是上一个连接的最后一帧（静止帧）。
     * 因此这里先做连通性闸门，未连上则只落盘不上报 UI。
     */
    fun onRemoteFirstFrame() {
        val status = connStatus.value
        if (status.phase != ConnPhase.CONNECTED) {
            AppLog.w(
                TAG,
                "remote_frame_ignored",
                mapOf(
                    "reason" to "not_connected",
                    "phase" to status.phase.name.lowercase(),
                    "seq" to callSeq.toString(),
                    "session" to "s$sessionId",
                ),
            )
            return
        }
        // 【t63】首帧本身也是"媒体存活"证据（此后由 renderer 池的每帧时间戳持续刷新）
        publishConnStatus(connTracker.onMediaFrame(System.currentTimeMillis(), MEDIA_SOURCE_SINK))
    }

    /**
     * 导出日志（D6/§9.5）。导出前先 flush Kotlin 与 native 两层。
     *
     * @return [LogExporter.Result]；调用方负责展示与分享。
     */
    suspend fun exportLogs(): LogExporter.Result {
        AppLog.flush()
        return LogExporter.exportZip(getApplication())
    }

    /**
     * 【t68】可恢复态下的显式入口：**重新创建房间**（房间被服务端回收后继续通话）。
     *
     * 背景：captain 2026-09-15 裁定 —— 重连期 `ROOM_NOT_FOUND` 不得自动退出通话页，
     * 必须给出可操作入口。服务端 `create` 会分配**新的 6 位房间号**（doc/09 §3.1，
     * 客户端不能指定房间号），因此本方法只发 `create`，真正的"新一代会话"由收到
     * `created` 之后在 [onMessage] 里开启（新房间号与新的 ICE 配置都要先用上）。
     */
    fun recreateRoom() {
        if (callEnded) {
            AppLog.w(TAG, "room_recreate_invoked", mapOf("result" to "ignored", "reason" to "call_ended"))
            return
        }
        val room = _uiState.value.roomId
        val currentRole = role
        if (room.isBlank() || currentRole.isBlank()) {
            AppLog.w(
                TAG,
                "room_recreate_invoked",
                mapOf("result" to "ignored", "reason" to "no_active_call", "room" to room, "role" to currentRole),
            )
            return
        }
        AppLog.w(
            TAG,
            "room_recreate_invoked",
            mapOf(
                "room" to room,
                "role" to currentRole,
                "media_alive" to mediaAliveForUi().toString(),
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ),
        )
        recreatePending = true
        _recoverable.value = null
        // 【t75】新一代通话：清掉"等待对端 offer"的兜底计时
        _uiState.update { it.copy(isConnecting = true, error = null, connectionState = ROOM_RECREATING_STATE) }
        // 信令侧：清掉终态抑制并用 create 建一个新房间（服务端分配新房间号，经 created 回传）
        client?.createRoom()
    }

    /** 设置导出会话摘要（§9.5 的 session-summary.txt 内容）。 */
    fun installSessionSummaryProvider() {
        val state = _uiState.value
        LogExporter.sessionSummaryProvider = {
            buildString {
                appendLine("roomId=${state.roomId}")
                appendLine("role=${state.role}")
                appendLine("connection_type=${state.connectionType}")
                appendLine("encoder_implementation=${state.encoderImplementation}")
                appendLine("encoder_bitrate_bps=${state.encoderBitrate}")
                appendLine("up_bps=${state.upBitrate} down_bps=${state.downBitrate}")
                appendLine("local_nat=${state.localNatType} remote_nat=${state.remoteNatType}")
                appendLine("ice_events=${_iceEvents.value.size}")
            }
        }
    }

    // ============================ 信令回调 ============================

    override fun onStateChanged(state: ConnectionState) {
        // 【t59】只更新信令侧展示文案：`isConnecting`（连接中遮罩）已改由连接状态机唯一拥有 ——
        // 信令到了 IN_CALL **不等于**媒体连通（真机缺陷：信令正常但无选中候选对）。
        _uiState.update { it.copy(connectionState = state.label) }
        // 通话已建立后落到 DISCONNECTED，且不是用户主动挂断 ⇒ 通话已**终态结束**
        // （重连 3 次 / 重连后 join 重试 10 次均耗尽，或服务端终态码被抑制重连）。
        // 按 captain 2026-09-13 要求：**干脆回首页 + 明确提示，不留半死不活的通话界面**。
        // 注意：短暂掉线走的是 CONNECTING（见 SignalingClient.scheduleReconnect），不会误触发这里。
        if (state == ConnectionState.DISCONNECTED && !callEnded && sessionReady) {
            // 【t68 根因②修复】先看这次掉线的**来源**：`peerLeft` / "重连期房间被回收"**不得**在这里
            // 自动结束通话（真机 dl-a:7516-7520、dl-b:9717-9720 —— 两处媒体都还在流动）。
            // 去留由紧接着到达的消息按 `CallSurvivability` 的纯判定决定：
            //   * PeerLeft  → 曾连上过/有媒体 ⇒ 回「等待对方加入」，保留房间；否则才结束；
            //   * ServerError(ROOM_NOT_FOUND) → 可恢复态 + 显式「重新创建房间」。
            // 两者的 `listener.onMessage(...)` 都在**同一次同步派发**里紧随其后（SignalingClient.onMessage：
            // advanceStateOnIncoming → listener.onMessage），因此这里只"推迟"，不会漏处理。
            // 【t68 收尾 / captain 2026-09-16 指令①】`SIGNAL_LOST`（重连预算耗尽）**必须先按媒体判定**：
            // 服务端宽限期 90 s 内不发 peerLeft，本端"重连不上"不说明对端离开 ⇒ 曾连上过/媒体存活时
            // 保留通话页与**会话**（不 hangup、不销毁），否则才结束（避免半死不活的页面）。
            val cause = client?.lastDisconnectCause ?: DisconnectCause.FATAL
            val mediaAlive = mediaAliveForUi()
            when {
                cause == DisconnectCause.SIGNAL_LOST -> when (
                    CallSurvivability.signalLostAction(everConnectedInGeneration, mediaAlive)
                ) {
                    SignalLostAction.KEEP_CALL -> {
                        AppLog.w(
                            TAG,
                            "signaling_lost action=keep_call",
                            mapOf(
                                "ever_connected" to everConnectedInGeneration.toString(),
                                "media_alive" to mediaAlive.toString(),
                                "media_age_ms" to connStatus.value.mediaAgeMs.toString(),
                                "phase" to connStatus.value.phase.name.lowercase(),
                                "seq" to callSeq.toString(),
                                "session" to "s$sessionId",
                            ),
                        )
                        // 可恢复态：保留通话页与会话（媒体可能仍在流），给出显式入口
                        _recoverable.value = RecoverableState(
                            reason = cause.name,
                            mediaAlive = mediaAlive,
                            notice = SIGNAL_LOST_NOTICE,
                        )
                        _uiState.update {
                            it.copy(
                                connectionState = SIGNAL_LOST_STATE,
                                isConnecting = false,
                                error = null,
                            )
                        }
                    }

                    SignalLostAction.END_CALL -> {
                        AppLog.w(
                            TAG,
                            "signaling_lost action=end_call",
                            mapOf(
                                "reason" to "never_connected_no_media",
                                "phase" to connStatus.value.phase.name.lowercase(),
                                "seq" to callSeq.toString(),
                                "session" to "s$sessionId",
                            ),
                        )
                        endCallNow(NOTICE_CALL_ENDED)
                    }
                }

                // `peerLeft` / 房间丢失：同一次派发中的消息分支会立刻接管（只需记录"推迟"）
                cause.survivable -> AppLog.w(
                    TAG,
                    "disconnect_deferred",
                    mapOf(
                        "cause" to cause.name.lowercase(),
                        "ever_connected" to everConnectedInGeneration.toString(),
                        "media_alive" to mediaAlive.toString(),
                        "phase" to connStatus.value.phase.name.lowercase(),
                        "seq" to callSeq.toString(),
                        "session" to "s$sessionId",
                    ),
                )

                else -> endCallNow(NOTICE_CALL_ENDED)
            }
        }
    }

    override fun onMessage(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Created -> {
                // 【t68】「重新创建房间」（可恢复态下的显式入口）成功：服务端分配了**新的房间号**。
                // 这里先缓存新下发的 ICE 配置（新会话必须用它建 PC），再开新一代会话；
                // 房间号随之更新到 UI（真机复测时要能把新房间号念给对端）。
                AppLog.i(
                    TAG,
                    "room_recreated",
                    mapOf(
                        "room" to message.roomId,
                        "recreate" to recreatePending.toString(),
                        "seq" to callSeq.toString(),
                        "session" to "s$sessionId",
                    ),
                )
                IceServerCache.put(
                    IceServerConfig(
                        stunUrl = message.stunUrl,
                        turnUrl = message.turnUrl,
                        turnUsername = message.turnUsername,
                        turnCredential = message.turnCredential,
                    )
                )
                _uiState.update {
                    it.copy(roomId = message.roomId, connectionState = ROOM_RECREATED_STATE, error = null)
                }
                if (recreatePending) {
                    recreatePending = false
                    // 新房间的第一个成员 ⇒ 本端角色为 host（与 HomeViewModel 的 create 路径一致）
                    beginGeneration(message.roomId, ROLE_HOST, REASON_RECREATE)
                }
            }

            is SignalingMessage.Joined -> {
                // 通话中收到 joined = **异常断线后重连成功**（go-dev 对齐通知第 7 点 / doc/09 §6）：
                // 服务端保留房间、对端会收到新的 peerJoined，因此发起方必须重新走 offer/answer；
                // 接收方（joiner）不主动发 offer，等对端的新 offer（maybeCreateOffer 内按 role 判定）。
                AppLog.i(TAG, "rejoined", mapOf("room" to message.roomId))
                // 【t64】joiner 侧计时起点：收到 `joined` 即视为"房间里已有对端在等/已在"
                startRetryClockIfWaiting("joined")
                if (sessionReady) {
                    peerJoined = true
                    maybeCreateOffer()
                    // 【t75 防 glare】`Joined` 一侧（重连侧）**不得立即发起**重协商：服务端已给"保持在线
                    // 的一侧"发 `peerJoined`，由它发起带 iceRestart 的 offer；本侧只等 offer 并 answer
                    // （两边界同时发 offer 会撞 glare，libwebrtc 的 rollback 很脆弱 ⇒ 「信令恢复但画面黑」）。
                    // 仅当 `REJOIN_OFFER_FALLBACK_MS`(8 s) 内未收到 offer 且 ICE 未恢复时，才由心跳兜底发起一次。
                    awaitingPeerOfferAfterRejoin = role != ROLE_HOST
                    rejoinOfferWaitSinceMs = System.currentTimeMillis()
                }
            }

            is SignalingMessage.PeerJoined -> {
                peerJoined = true
                AppLog.i(TAG, "peer_joined", mapOf("peer" to message.peerId))
                // 【t64】host 侧计时起点：对端进入房间 ⇒ 从此刻开始两档计时
                startRetryClockIfWaiting("peer_joined")
                // 【t75 顺序修复】保持在线的一侧（收到 peerJoined 的一方）才发起重协商；若 ICE 已掉，
                // **必须**先 restartIce（使**下一次** offer 携带 iceRestart）**再** createOffer ——
                // 顺序反了会发出不带 iceRestart 的 offer ⇒ 旧候选对已死、ICE 不重选 ⇒ 「信令已恢复、画面黑」。
                if (shouldInitiateRejoinRenegotiation()) {
                    restartIceThenOffer(INITIATOR_HOST, TRIGGER_PEER_JOINED, "restart_ice reason=rejoin initiator=host")
                } else {
                    maybeCreateOffer()
                }
            }

            is SignalingMessage.PeerLeft -> {
                AppLog.i(TAG, "peer_left")
                onIceEvent(IceEvent(IceEventType.SELECTED_PAIR, "peer_left"))
                // 【t64】对端离开：**先停表并复位回等待态**（不计时、不显示失败/重试）。
                resetRetryClock("peer_left")
                // 【t68】captain 2026-09-15 裁定（答复 t64 报告 §2.4 的 U4）：
                // **本世代曾连上过**（或当前仍有媒体证据）时的 peerLeft **不再挂断** ——
                // 回到「等待对方加入」并保留房间（配合服务端宽限期，对端可能重连回来）；
                // 界面上的「挂断」按钮始终是显式结束入口。
                // 仅"从未连上过且无媒体证据"时按 doc/14 §8.5 原语义结束通话（不留半死不活的界面）。
                val mediaAlive = mediaAliveForUi()
                when (CallSurvivability.peerLeftAction(everConnectedInGeneration, mediaAlive)) {
                    PeerLeftAction.KEEP_CALL -> {
                        AppLog.w(
                            TAG,
                            "peer_left action=keep_call",
                            mapOf(
                                "ever_connected" to everConnectedInGeneration.toString(),
                                "media_alive" to mediaAlive.toString(),
                                "media_age_ms" to connStatus.value.mediaAgeMs.toString(),
                                "seq" to callSeq.toString(),
                                "session" to "s$sessionId",
                            ),
                        )
                        // 【t68】留在通话页：文案回到"等待对方加入"（保留房间意图，服务端宽限期内
                        // 对端可以重连回来）；媒体仍在流时桌面画面照旧，不遮挡。
                        _uiState.update {
                            it.copy(
                                connectionState = ConnectionState.IN_ROOM.label,
                                isConnecting = false,
                                error = null,
                            )
                        }
                    }

                    PeerLeftAction.END_CALL -> {
                        AppLog.w(
                            TAG,
                            "peer_left action=end_call",
                            mapOf(
                                "reason" to "never_connected_no_media",
                                "seq" to callSeq.toString(),
                                "session" to "s$sessionId",
                            ),
                        )
                        hangup()
                    }
                }
            }

            is SignalingMessage.Offer -> {
                // 【t44 修复①】原先写作 `session?.onRemoteOffer(...)`：会话为空时**静默丢弃**
                // 远端 offer —— 信令侧表现就是"只有 offer_forward、没有 answer_forward"，
                // 且 App 内没有任何日志/UI 错误（真机缺陷①）。现在必须落盘 + 报错可诊断。
                // 【t51 修复】再往前一步：offer/候选**早于 `CallSession.start()`（pc_starting）**
                // 到达时不再丢弃，而是先入预队列，start() 成功后**按到达顺序回放**。
                // 真机 room 66DZFT：offer 16:18:34.953 < pc_starting 16:18:35.244；候选 16:18:35.105
                // 当时只打了 `ice_without_session` 就被丢掉。
                peerResponseSeen = false
                // 【t75】已收到对端 offer ⇒ 取消重连侧的 8 s 兜底（避免两侧同时发 offer 撞 glare）
                if (awaitingPeerOfferAfterRejoin) {
                    AppLog.i(
                        TAG,
                        "rejoin_offer_received",
                        mapOf(
                            "waited_ms" to (System.currentTimeMillis() - rejoinOfferWaitSinceMs).toString(),
                            "seq" to callSeq.toString(),
                            "session" to "s$sessionId",
                        ),
                    )
                }
                awaitingPeerOfferAfterRejoin = false
                // 【t64】joiner 侧计时起点之二：收到对端 offer 即视为"对端已在"
                startRetryClockIfWaiting("offer")
                val current = session
                if (current == null) {
                    val queued = pendingRemote.enqueueOffer(message.sdp)
                    AppLog.w(
                        TAG,
                        "remote_deferred",
                        mapOf(
                            "kind" to "offer",
                            "seq" to callSeq.toString(),
                            "queued" to queued.toString(),
                            "sdp_bytes" to message.sdp.length.toString(),
                        ),
                    )
                } else {
                    current.onRemoteOffer(message.sdp)
                }
                // 【t59】对端已经主动发起协商 ⇒ 取消本端"重试后延迟重发 offer"（避免 glare）
                retryReofferPending = false
                // joiner：收到 offer 后开始等待远端候选（20 s 内无 ⇒ UI 明确提示）
                armPeerResponseWatchdog("offer_received")
            }

            is SignalingMessage.Answer -> {
                peerResponseSeen = true
                // 【t59】同上：对端已回应 ⇒ 无需本端再发 offer
                retryReofferPending = false
                val current = session
                if (current == null) {
                    val queued = pendingRemote.enqueueAnswer(message.sdp)
                    AppLog.w(
                        TAG,
                        "remote_deferred",
                        mapOf(
                            "kind" to "answer",
                            "seq" to callSeq.toString(),
                            "queued" to queued.toString(),
                            "sdp_bytes" to message.sdp.length.toString(),
                        ),
                    )
                } else {
                    current.onRemoteAnswer(message.sdp)
                }
            }

            is SignalingMessage.Ice -> {
                val current = session
                if (current == null) {
                    // 【t51】候选同样入队（旧实现只打 `ice_without_session` 即丢弃）；
                    // 回放走 start() 成功路径，PC 仍未就绪时由 t44 的 CallSession pending 队列兜底。
                    val queued = pendingRemote.enqueueIce(message.candidate, message.sdpMid, message.sdpMLineIndex)
                    AppLog.i(
                        TAG,
                        "remote_deferred",
                        mapOf(
                            "kind" to "ice",
                            "seq" to callSeq.toString(),
                            "queued" to queued.toString(),
                            "mid" to (message.sdpMid ?: "-"),
                        ),
                    )
                } else {
                    peerResponseSeen = true
                    current.onRemoteIceCandidate(message.candidate, message.sdpMid, message.sdpMLineIndex)
                }
            }

            is SignalingMessage.NatTypeMessage -> NatTypeRepository.onRemoteResult(message.natType)

            is SignalingMessage.ServerError -> {
                if (message.code == CODE_ROOM_FULL) {
                    // 静默掉线重连撞上"槽位尚未回收"（服务端约 45 s 读超时窗口）：
                    // 客户端在 SignalingClient 内有界重试 join，UI 显示"重试中"而非终态错误
                    // （go-dev TestE2E_ReconnectStaleSessionCausesRoomFull）
                    AppLog.w(TAG, "rejoin_room_full", mapOf("code" to message.code))
                    _uiState.update {
                        it.copy(connectionState = "房间暂不可用，重试中…", isConnecting = true, error = null)
                    }
                } else {
                    AppLog.e(TAG, "server_error", mapOf("code" to message.code))
                    // 【t68】房间丢失（ROOM_NOT_FOUND / ROOM_EXPIRED）：若本端仍处于
                    // "曾经在房内"的语境（断线重连 / 通话中 / 尚在等对端）**或媒体仍在流动**，
                    // **不得自动退出通话页** —— 改为可恢复态 + 显式「重新创建房间」入口
                    // （captain 2026-09-15 裁定；媒体在流时通话页必须保持）。
                    // 首次入房就找不到房间（从未在房内）时仍按原语义结束通话。
                    val mediaAlive = mediaAliveForUi()
                    val rejoinContext = inRoomContext()
                    val keepCall = SignalingErrorPolicy.keepsCallOnRoomLoss(message.code, rejoinContext, mediaAlive)
                    val action = if (keepCall) RoomLostAction.KEEP_CALL else RoomLostAction.END_CALL
                    if (action == RoomLostAction.KEEP_CALL) {
                        AppLog.w(
                            TAG,
                            "room_not_found action=keep_call",
                            mapOf(
                                "code" to message.code,
                                "media_alive" to mediaAlive.toString(),
                                "media_age_ms" to connStatus.value.mediaAgeMs.toString(),
                                "rejoin_context" to rejoinContext.toString(),
                                "rejoin_drop" to (client?.isRejoinContext == true).toString(),
                                "phase" to connStatus.value.phase.name.lowercase(),
                                "seq" to callSeq.toString(),
                                "session" to "s$sessionId",
                            ),
                        )
                        // 可恢复态：保持通话页（不 hangup、不 endCallNow），给出显式入口
                        _recoverable.value = RecoverableState(
                            reason = message.code,
                            mediaAlive = mediaAlive,
                            notice = ROOM_LOST_NOTICE,
                        )
                        _uiState.update {
                            it.copy(
                                connectionState = ROOM_LOST_STATE,
                                isConnecting = false,
                                error = null,
                            )
                        }
                    } else {
                        // 终态码且非重连语境 ⇒ 明确失败并退出通话页（captain 2026-09-13 口径）
                        endCallNow(
                            if (SignalingErrorPolicy.endsCall(message.code)) {
                                NOTICE_CALL_ENDED
                            } else {
                                "服务端错误: ${message.code}"
                            }
                        )
                    }
                }
            }

            else -> Unit
        }
    }

    override fun onTransportFailure(reason: String, cause: Throwable?) {
        if (reason == "pong_timeout" || reason == "failure") {
            _uiState.update { it.copy(connectionState = "重连中") }
        }
    }

    // ============================ 会话回调 ============================

    override fun onReady() {
        sessionReady = true
        // 【t51】媒体链就绪即视为"对端有响应"，解除对端无响应看门狗（避免误报）
        peerResponseSeen = true
        // 【t59 根因修复】这里**删除**了旧的 `_uiState.update { isConnecting = false }`：
        // `onReady()` 是"本地媒体链就绪"（PC 已建、本地轨已挂），**不是**"已连上对端"。
        // 旧代码在进房约 0.4 s 后就关掉"连接中"遮罩，正是"界面没有任何状态提示"的根因之一。
        maybeCreateOffer()
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        _remoteVideoTrack.value = track
    }

    override fun onIceEvent(event: IceEvent) {
        _iceEvents.update { current -> current + event }
        // 【t59】把 ICE/传输状态变化接入 UI 连接状态机 —— 旧实现只把事件堆进诊断列表，
        // 因此"曾连上又断掉"（真机 `pc_ice_connection_state state=DISCONNECTED`）对主界面完全不可见。
        when (event.type) {
            IceEventType.ICE_CONNECTION -> {
                val now = System.currentTimeMillis()
                when (parseConnSignal(event.detail)) {
                    ConnSignal.CONNECTED -> publishConnStatus(connTracker.onTransportConnected(now))
                    ConnSignal.DISCONNECTED, ConnSignal.FAILED -> {
                        val before = connStatus.value
                        val after = connTracker.onConnectionLost(now)
                        publishConnStatus(after)
                        // 【t63】ICE 掉了但媒体仍在流动 ⇒ **不让界面跳变**（4G 中继会周期性 flap），
                        // 只落盘供失败归因；真正的中断由"帧龄 + 去抖"判定后可归因为 connection_lost。
                        if (before.phase == ConnPhase.CONNECTED && after.phase == ConnPhase.CONNECTED) {
                            AppLog.w(
                                TAG,
                                "ice_flap_suppressed",
                                mapOf(
                                    "detail" to event.detail,
                                    "media_source" to after.mediaSource,
                                    "age_ms" to after.mediaAgeMs.toString(),
                                    "seq" to callSeq.toString(),
                                    "session" to "s$sessionId",
                                ),
                            )
                        }
                    }

                    ConnSignal.IN_PROGRESS, ConnSignal.IGNORED -> Unit
                }
            }

            // 【t60/A1②】收集完成 ⇒ 后移超时锚点，并落盘"到底 gather 到什么"（中继是否到位）
            IceEventType.END_OF_CANDIDATES -> {
                val current = session
                AppLog.i(
                    TAG,
                    "ice_relay_state",
                    mapOf(
                        "relay" to (current?.localRelayCandidateCount()?.toString() ?: "-"),
                        "turn_errors" to (current?.turnErrorCount()?.toString() ?: "-"),
                        "filtered_loopback" to (current?.filteredLoopbackCount()?.toString() ?: "-"),
                        "relay_missing" to (current?.relayMissing()?.toString() ?: "-"),
                        "seq" to callSeq.toString(),
                        "session" to "s$sessionId",
                    ),
                )
                publishConnStatus(connTracker.onGatheringComplete(System.currentTimeMillis()))
            }

            else -> Unit
        }
    }

    override fun onStats(snapshot: StatsSnapshot) {
        lastStats = snapshot
        _uiState.update {
            it.copy(
                connectionType = snapshot.connectionType,
                upBitrate = snapshot.upBitrateBps,
                downBitrate = snapshot.downBitrateBps,
                availableOutgoingBitrate = snapshot.availableOutgoingBitrateBps,
                encoderImplementation = snapshot.encoderImplementation,
            )
        }
        // 【t59 根因修复】旧代码是：
        //     if (snapshot.connectionType.isNotEmpty() || snapshot.encoderImplementation.isNotEmpty())
        //         _uiState.update { it.copy(isConnecting = false) }
        // `encoderImplementation`（真机 `SelfVp9Libvpx`）在**没有选中候选对**时同样非空
        // （编码器照样被驱动、`up_bps` 照样增长），于是"连接中"遮罩在进房约 1 s 后就被错误关闭，
        // 用户只看到一帧静止画面且没有任何提示。
        // 【t60】判活口径收敛为 `livenessEvidence(...)` 的**唯一实现**（`ui/call/ConnectionStatus.kt`）：
        //   ① `connectionType` 非空（`mode=P2P|RELAY` = 有选中的候选对）；
        //   ② `down_bps > 0`（**下行字节**是唯一能证明路径打通的信号）；
        //   ③ `up_bps` **永不参与判定**（真机 `mode=-` 时 `up_bps`≈49 kbps，是"交给 ICE 层的字节"）。
        val now = System.currentTimeMillis()
        when (livenessEvidence(snapshot.connectionType.isNotEmpty(), snapshot.downBitrateBps, snapshot.upBitrateBps)) {
            LivenessEvidence.SELECTED_PAIR -> publishConnStatus(connTracker.onSelectedPair(now))
            // 无候选对但真的在收 ⇒ 路径确实打通（同样视为已连接）
            LivenessEvidence.DOWNLINK -> publishConnStatus(connTracker.onSelectedPair(now))
            LivenessEvidence.NONE -> Unit
        }
        // 【t63】**媒体存活**是独立通道，与"是否已连接"解耦：`down_bps > 0` 必须参与存活判定，
        // 不能因为 `mode` 非空（健康时必然非空！）就被 `SELECTED_PAIR` 分支吃掉 ——
        // 这正是真机 `frame=false` 全程成立、状态反复跳的直接原因之一。
        if (snapshot.downBitrateBps > 0) {
            publishConnStatus(connTracker.onMediaFrame(now, MEDIA_SOURCE_DOWN_BPS))
        }
    }

    override fun onError(message: String) {
        // 【t68 根因③】媒体存活期间**不得**显示 ICE/连接失败文案：链路层的 ICE 事实只用于失败归因
        // 与日志，不驱动用户可见的失败提示（真机 dl-b 15:27:54：`ice_down=true` 但 `media_age_ms=2705`
        // 且 `pair=true`，视频随后自行恢复）。
        val mediaAlive = mediaAliveForUi()
        if (!CallSurvivability.shouldSurfaceError(message, mediaAlive)) {
            AppLog.w(
                TAG,
                "ice_down_ui_suppressed",
                mapOf(
                    "age_ms" to connStatus.value.mediaAgeMs.toString(),
                    "media_source" to connStatus.value.mediaSource,
                    "pair" to connStatus.value.hasSelectedPair.toString(),
                    "phase" to connStatus.value.phase.name.lowercase(),
                    "message" to message,
                    "seq" to callSeq.toString(),
                    "session" to "s$sessionId",
                ),
            )
            return
        }
        _uiState.update { it.copy(error = message) }
    }

    override fun onCleared() {
        // 【t53】离开通话页（ViewModel 销毁）也必须关闭旧会话：不得留下仍在跑统计循环的 PC。
        if (!callEnded) {
            AppLog.i(
                TAG,
                "session_release",
                mapOf("reason" to "on_cleared", "seq" to callSeq.toString(), "session" to "s$sessionId"),
            )
        }
        slot.endCall()
        releaseSession("on_cleared")
        super.onCleared()
    }

    // ============================ 内部实现 ============================

    /**
     * 释放当前 [CallSession]（t53）。
     *
     * 只负责"关会话 + 清本端/远端轨道状态"，**不改变** [slot] 的通话世代 ——
     * 由调用方决定这是"新一代通话的前置清理"（[initCall]）还是"本次通话彻底结束"（[hangup]）。
     *
     * @param reason 释放原因，用于日志定因（`reinit`/`hangup`/`on_cleared`/`start_failed`）。
     */
    private fun releaseSession(reason: String) {
        val old = session
        session = null
        sessionReady = false
        peerJoined = false
        peerWatchdogArmed = false
        peerResponseSeen = false
        // 【t53】上一代的轨道对象绝不可留给下一代使用（旧轨随旧 PC 一起失效）
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        if (old != null) {
            AppLog.i(
                TAG,
                "session_teardown",
                mapOf("reason" to reason, "session" to "s${old.sessionId}"),
            )
            old.close()
        }
    }

    /**
     * 上报本端 NAT 类型（doc/09 §3.7；§7.4「对端 NAT ← 信令 natType」）。
     *
     * 只在探测出有效结果、且已处于可发媒体信令的状态（IN_ROOM/IN_CALL）时发一次。
     */
    private fun maybeSendNatType() {
        if (natTypeSent) return
        val wire = NatTypeRepository.localWire()
        if (wire.isEmpty() || wire == "Unknown") return
        val connection = client ?: return
        if (!connection.canSendMediaSignaling()) return
        connection.sendNatType(wire)
        natTypeSent = true
    }

    /** host 侧：只有「已收到 peerJoined」且「会话已就绪」时才发 offer（避免顺序问题）。 */
    private fun maybeCreateOffer() {
        if (role != ROLE_HOST || !peerJoined || !sessionReady) return
        sendOfferInternal()
    }

    /**
     * 【t71③ / t75】重连入会成功后的 ICE 恢复门控（纯判定在 [CallSurvivability.shouldRestartIceOnRejoin]）。
     *
     * 规则：本世代曾连上过 且（ICE 掉了 或 无媒体证据）；**媒体证据新鲜（帧龄 <3 s）且存在 selected pair
     * ⇒ 不重启**（健康通话不受打扰，也避免与对端 offer 撞 glare）。
     */
    private fun shouldInitiateRejoinRenegotiation(): Boolean {
        val status = connStatus.value
        return CallSurvivability.shouldRestartIceOnRejoin(
            everConnected = everConnectedInGeneration,
            iceDown = status.iceDown,
            mediaAlive = status.mediaAlive,
            hasSelectedPair = status.hasSelectedPair,
        )
    }

    /**
     * 【t75 顺序修复】先 `restartIce("rejoin")`（让**下一次** offer 携带 `iceRestart`）**再**发 offer。
     *
     * 为什么不能反过来：`CallSession.restartIce()` 只调用 `PeerConnection.restartIce()` 置标志并重新
     * gathering，**它自己不发 offer**；若先 `createOffer()` 再 `restartIce()`，发出去的 offer 不带
     * `iceRestart` ⇒ 旧候选对已死时 ICE 不会重选 ⇒ 真机表现为「信令已恢复、画面黑」。
     *
     * @param initiator 发起方（`host` = 保持在线侧；`rejoiner` = 重连侧 8 s 兜底）——作为**新增字段**写入诊断；
     *   注意：既有键名**一处都不改**（host 侧消息仍为 `restart_ice reason=rejoin`，既有字段仍有 `trigger`）。
     * @param trigger 触发来源（`peer_joined` / `joined`），沿用 t71 既有字段名。
     * @param logEvent 该路径的诊断消息：主键统一为 `restart_ice reason=rejoin initiator=host|rejoiner`
     *   （`rejoin_ice_restart …` 为旧写法，按 captain 2026-09-16 定案不再使用）。
     */
    private fun restartIceThenOffer(
        initiator: String,
        trigger: String,
        logEvent: String = "restart_ice reason=rejoin initiator=host",
        extra: Map<String, String> = emptyMap(),
    ) {
        val status = connStatus.value
        val current = session ?: return
        if (current.isClosed()) return
        // 【t75 顺序修复】先 restartIce("rejoin")（让**下一次** offer 带 iceRestart）**再**发 offer；
        // reason 字符串保持 `rejoin`（与 t71 已落地、t72 APK 一致；captain 2026-09-16 定案"主键统一为 restart_ice"）
        val accepted = current.restartIce("rejoin")
        AppLog.i(
            TAG,
            logEvent,
            mapOf(
                // 【t75】新增 `initiator=host|rejoiner`（仅"新增字段"，既有 trigger/accepted/… 键名不变）
                "initiator" to initiator,
                "trigger" to trigger,
                "accepted" to accepted.toString(),
                "ice_down" to status.iceDown.toString(),
                "media_alive" to status.mediaAlive.toString(),
                "pair" to status.hasSelectedPair.toString(),
                "phase" to status.phase.name.lowercase(),
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ) + extra,
        )
        sendOfferInternal()
    }

    /** [t75] 发 offer 的**统一内部入口**（不做 host 角色闸门：重连侧 8 s 兜底也要能发）。 */
    private fun sendOfferInternal() {
        val current = session ?: return
        if (current.isClosed()) return
        current.createOffer()
        armPeerResponseWatchdog("offer_sent")
    }

    /**
     * 【t75】在既有心跳里评估"重连侧 8 s 兜底"是否需要发起（**一次性**：触发后立即清标志）。
     *
     * @return `true` 表示本次调用已判定需要兜底发起（调用方随后 `restartIceThenOffer(INITIATOR_REJOINER, …)`）。
     */
    private fun evaluateRejoinOfferFallback(nowMs: Long): Boolean {
        if (!awaitingPeerOfferAfterRejoin) return false
        val status = connStatus.value
        val decided = CallSurvivability.shouldRejoinerFallbackOffer(
            awaitingPeerOffer = true,
            waitedMs = nowMs - rejoinOfferWaitSinceMs,
            iceDown = status.iceDown,
            mediaAlive = status.mediaAlive,
            hasSelectedPair = status.hasSelectedPair,
        )
        if (decided) awaitingPeerOfferAfterRejoin = false
        return decided
    }

    // ===================== t59：连接状态发布 / 诊断 / 重试重协商 =====================

    // ===================== t64：等待对端 / 重试计时起点 =====================

    /**
     * 【t64】**收到对端后才启动两档计时**（等待态 ⇒ CONNECTING）。
     *
     * 触发点（由调用方给出）：host = `peerJoined`；joiner = `joined` 或首个 `offer`。
     * 幂等：已经在计时/已连上/已失败时**不重置**（避免重复触发把计时反复归零）。
     *
     * @param trigger 触发来源，写入 `retry_clock_started trigger=…` 供复测自证。
     */
    private fun startRetryClockIfWaiting(trigger: String) {
        if (connTracker.status.phase != ConnPhase.WAITING_PEER) return
        val now = System.currentTimeMillis()
        val waited = if (waitingSinceMs > 0L) now - waitingSinceMs else 0L
        waitingSinceMs = 0L
        publishConnStatus(connTracker.startRetryClock(now))
        AppLog.i(
            TAG,
            "retry_clock_started",
            mapOf(
                "trigger" to trigger,
                "waited_ms" to waited.toString(),
                "role" to role,
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ),
        )
    }

    /**
     * 【t64】停表并复位回「等待对方加入」（`peerLeft`/对端离开时调用）。
     *
     * 之后界面**不显示失败与重试**；若对端再次进入，[startRetryClockIfWaiting] 会重新起算（无残留）。
     */
    private fun resetRetryClock(reason: String) {
        val now = System.currentTimeMillis()
        waitingSinceMs = now
        publishConnStatus(connTracker.onWaitingPeer(now))
        AppLog.i(
            TAG,
            "retry_clock_reset",
            mapOf(
                "reason" to reason,
                "role" to role,
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ),
        )
    }

    /**
     * 发布连接状态（**`isConnecting` 的唯一写入方**）。
     *
     * 做的事：
     *  1. 更新 [connStatus]；
     *  2. 由状态派生 `isConnecting`（CONNECTING ⇒ true）与 `isRemoteVideoReady`
     *     （只有"已连上**且**画面新鲜"才为真 —— 静止帧绝不算已出画面）；
     *  3. 阶段/原因变化时落盘 `ui_conn_state`（阈值 DEBUG 下必落盘）；
     *  4. 停在"没有选中候选对"的失败态时按 [NO_PAIR_LOG_INTERVAL_MS] 限频落盘 `no_selected_pair`。
     */
    private fun publishConnStatus(status: ConnStatus) {
        val previous = _connStatus.value
        _connStatus.value = status
        // 【t64】记住"本世代是否真的连上过"（决定 peerLeft 时是终态挂断还是回等待态）
        if (status.phase == ConnPhase.CONNECTED) everConnectedInGeneration = true
        _uiState.update {
            it.copy(
                isConnecting = status.phase == ConnPhase.CONNECTING,
                isRemoteVideoReady = status.phase == ConnPhase.CONNECTED && status.remoteFrameReady,
            )
        }
        // 【t80 恢复清除路径】看门狗误报留下的"ICE 未连通"横幅是**一次性错误文本、没有清除路径**：
        // 一旦会话连通或媒体判活（帧新鲜 / down_bps>0）就必须把它清掉，否则通话正常时横幅会一直挂着
        // （真机 03:34:24 误报 → 03:34:25.438 已 CONNECTED、down≈2.0 Mbps，横幅却持续显示）。
        // 判据在 CallSurvivability.shouldClearIceError（纯函数，可 JVM 单测）；非 ICE 类文案不受影响。
        clearIceErrorBannerIfRecovered(status)
        if (status.phase != previous.phase || status.reason != previous.reason) {
            AppLog.i(
                TAG,
                "ui_conn_state",
                mapOf(
                    "phase" to status.phase.name.lowercase(),
                    "reason" to status.reason.name.lowercase(),
                    "elapsed_ms" to status.elapsedMs.toString(),
                    "pair" to status.hasSelectedPair.toString(),
                    "frame" to status.remoteFrameReady.toString(),
                    "stalled" to status.remoteFrameStalled.toString(),
                    // 【t63】帧存活的来源与帧龄：一行即可判定"到底有没有每帧时间戳"
                    "media_source" to status.mediaSource,
                    "media_age_ms" to status.mediaAgeMs.toString(),
                    "ice_down" to status.iceDown.toString(),
                    "retry" to status.retryCount.toString(),
                    "seq" to callSeq.toString(),
                    "session" to "s$sessionId",
                ),
            )
        }
        if (status.phase == ConnPhase.FAILED && status.reason == ConnReason.TIMEOUT_NO_PAIR) {
            maybeLogNoSelectedPair(status)
        }
        // 【t68】媒体存活期抑制了"ICE/连接失败"呈现 ⇒ 落一条诊断，便于下一轮复测**一行确认**
        // "界面为什么不报失败"（真机缺陷：视频仍在流却显示 ICE 失败）。
        if (status.iceFailureSuppressed && lastIceSuppressedLogMs == 0L) {
            lastIceSuppressedLogMs = System.currentTimeMillis()
            AppLog.w(
                TAG,
                "ice_down_ui_suppressed",
                mapOf(
                    "age_ms" to status.mediaAgeMs.toString(),
                    "media_source" to status.mediaSource,
                    "pair" to status.hasSelectedPair.toString(),
                    "phase" to status.phase.name.lowercase(),
                    "reason" to status.reason.name.lowercase(),
                    "ice_down" to status.iceDown.toString(),
                    "seq" to callSeq.toString(),
                    "session" to "s$sessionId",
                ),
            )
        } else if (!status.iceFailureSuppressed) {
            // 抑制结束（已恢复 或 已确认失败）⇒ 重置限频闸门，下一次抑制仍会落盘
            lastIceSuppressedLogMs = 0L
        }
        // 【t60/A7】"配了 TURN 却没有中继候选" ⇒ 失败时**自动**做一次 ICE restart + 重新 gathering
        // 并重新协商（而不是让用户只能点重试）。
        if (status.phase == ConnPhase.FAILED && status.reason == ConnReason.NO_RELAY_CANDIDATE) {
            maybeLogNoSelectedPair(status)
            maybeAutoRegather(status)
        }
    }

    /**
     * 一次性自动"重新 gathering"（t60/A4/A7）。
     *
     * 触发条件：连接判定失败且原因为 [ConnReason.NO_RELAY_CANDIDATE]（有 TURN 配置但 `local_relay==0`）。
     * 动作：`CallSession.restartIce()`（`restartIce()` + 重新 `setConfiguration(GATHER_CONTINUALLY)`
     * 触发重新收集）⇒ 若被接受，再按角色重新协商（host 立刻重发 offer / joiner 延迟重发，
     * 复用 t59 的 [scheduleRetryReoffer] 与防 glare 机制）。每个世代最多自动一次，
     * 之后由用户用「点击重试」（世代化重建）继续。
     */
    private fun maybeAutoRegather(status: ConnStatus) {
        if (autoRegatherDone || callEnded) return
        val current = session ?: return
        if (current.isClosed()) return
        autoRegatherDone = true
        val accepted = current.restartIce("no_relay_candidate")
        AppLog.w(
            TAG,
            "ice_regather_invoked",
            mapOf(
                "reason" to status.reason.name.lowercase(),
                "accepted" to accepted.toString(),
                "local_relay" to current.localRelayCandidateCount().toString(),
                "turn_errors" to current.turnErrorCount().toString(),
                "filtered_loopback" to current.filteredLoopbackCount().toString(),
                "elapsed_ms" to status.elapsedMs.toString(),
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ),
        )
        if (!accepted) return
        val peerKnown = SignalingIdentity.remotePeerId.value.isNotBlank()
        when (retryNegotiationFor(role, peerKnown, sawRemoteNegotiationSinceRetry = false)) {
            RetryNegotiation.OFFER_NOW -> {
                peerJoined = true
                maybeCreateOffer()
            }

            RetryNegotiation.OFFER_DELAYED -> scheduleRetryReoffer()

            RetryNegotiation.NONE -> Unit
        }
    }

    /**
     * 落盘"没有选中的候选对"诊断（`no_selected_pair after_ms=N`）。
     *
     * 字段刻意与真机定因时用到的口径一致（`impl`/`avail_bps`/`up_bps`/`down_bps`），
     * 下一轮复测可一眼确认"编码器在跑但链路没通"。
     */
    private fun maybeLogNoSelectedPair(status: ConnStatus) {
        val now = System.currentTimeMillis()
        if (lastNoPairLogMs != 0L && now - lastNoPairLogMs < NO_PAIR_LOG_INTERVAL_MS) return
        lastNoPairLogMs = now
        val stats = lastStats
        AppLog.w(
            TAG,
            "no_selected_pair",
            mapOf(
                "after_ms" to status.elapsedMs.toString(),
                "pair" to "false",
                "impl" to (stats?.encoderImplementation ?: "-"),
                "avail_bps" to (stats?.availableOutgoingBitrateBps?.toString() ?: "-"),
                "up_bps" to (stats?.upBitrateBps?.toString() ?: "-"),
                "down_bps" to (stats?.downBitrateBps?.toString() ?: "-"),
                "local" to (stats?.localCandidateType ?: "-"),
                "remote" to (stats?.remoteCandidateType ?: "-"),
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ),
        )
    }

    /**
     * 重试后**延迟**重发 offer（joiner 侧）。
     *
     * 为什么需要：对端已在房中且不会因为本端重建会话而重新发 offer；joiner 若不主动重发，
     * 会一直停在"没连上"的等待态。延迟 [RETRY_REOFFER_DELAY_MS] 是为了给 host 的 offer 让路，
     * 收到对端 offer/answer 即取消（见 [retryReofferPending] 的两处取消点），避免 glare。
     */
    private fun scheduleRetryReoffer() {
        retryReofferPending = true
        val scheduledSeq = callSeq
        viewModelScope.launch {
            delay(RETRY_REOFFER_DELAY_MS)
            if (!retryReofferPending || callEnded || scheduledSeq != callSeq) return@launch
            retryReofferPending = false
            val current = session
            if (current == null || current.isClosed() || connStatus.value.phase == ConnPhase.CONNECTED) {
                return@launch
            }
            AppLog.i(
                TAG,
                "retry_reoffer_run",
                mapOf(
                    "role" to role,
                    "delay_ms" to RETRY_REOFFER_DELAY_MS.toString(),
                    "seq" to scheduledSeq.toString(),
                    "session" to "s$sessionId",
                ),
            )
            current.createOffer()
            armPeerResponseWatchdog("retry_reoffer")
        }
    }

    /**
     * t51：把 `session == null` 窗口内到达的远端消息**按到达顺序**回放给刚建好的会话。
     *
     * 两级队列配合（不回退 t44）：本层负责"会话尚未创建"的窗口；
     * 会话内的 PC 未就绪窗口仍由 `CallSession` 的 pending 队列（`ice_deferred`/`remote_replay_done`）兜底。
     */
    private fun flushPendingRemoteQueue(target: CallSession) {
        val entries = pendingRemote.drain()
        if (entries.isEmpty()) {
            AppLog.i(
                TAG,
                "remote_replay_done",
                mapOf("offer" to "false", "answer" to "false", "candidates" to "0", "source" to "viewmodel"),
            )
            return
        }
        var offered = false
        var answered = false
        var candidates = 0
        for (entry in entries) {
            when (entry.kind) {
                PendingRemoteMessages.Kind.OFFER -> {
                    target.onRemoteOffer(entry.sdpOrCandidate)
                    offered = true
                }

                PendingRemoteMessages.Kind.ANSWER -> {
                    target.onRemoteAnswer(entry.sdpOrCandidate)
                    answered = true
                }

                PendingRemoteMessages.Kind.ICE -> {
                    target.onRemoteIceCandidate(entry.sdpOrCandidate, entry.sdpMid, entry.sdpMLineIndex)
                    candidates++
                }
            }
        }
        AppLog.i(
            TAG,
            "remote_replay_done",
            mapOf(
                "offer" to offered.toString(),
                "answer" to answered.toString(),
                "candidates" to candidates.toString(),
                "source" to "viewmodel",
                "order" to entries.joinToString(",") { it.kind.name.lowercase() },
            ),
        )
    }

    /**
     * t51：**对端无响应看门狗**。
     *
     * 触发条件：已发出/收到 offer，20 s（[PEER_RESPONSE_TIMEOUT_MS]）内既未收到 answer、
     * 未收到任何远端候选、连接也未建立且未挂断。
     * 行为：写 WARN `answer_timeout` + 通过 [onError] 把明确文案透传到通话页
     * （旧版本用户只能看到"一直正在连接会议…"，无从判断是哪一侧的问题）。
     *
     * 【t59】判定基准由 `isConnecting` 改为"连接未建立"：t59 后 `isConnecting` 由连接状态机拥有，
     * 15 s 超时会把它置 false —— 若继续用它判活，20 s 的对端无响应提示将永远不触发（行为回退）。
     */
    private fun armPeerResponseWatchdog(reason: String) {
        if (peerWatchdogArmed) return
        peerWatchdogArmed = true
        viewModelScope.launch {
            delay(PEER_RESPONSE_TIMEOUT_MS)
            val stillWaiting = connStatus.value.phase != ConnPhase.CONNECTED
            if (!peerResponseSeen && !callEnded && stillWaiting) {
                AppLog.w(
                    TAG,
                    "answer_timeout",
                    mapOf(
                        "reason" to reason,
                        "waited_ms" to PEER_RESPONSE_TIMEOUT_MS.toString(),
                        "role" to role,
                        "seq" to callSeq.toString(),
                        "session" to "s$sessionId",
                    ),
                )
                onError(NO_PEER_RESPONSE_NOTICE)
            }
        }
    }

    private fun fail(message: String) {
        AppLog.e(TAG, "call_failed", mapOf("reason" to message))
        _uiState.update { it.copy(error = message, isConnecting = false) }
    }

    // ===================== t68：可存活化判定（薄接线，纯逻辑在 CallSurvivability） =====================

    /**
     * 当前是否有可用媒体证据（t68）。
     *
     * 取连接状态机的**权威快照**：`ConnStatus.mediaAlive`（帧新鲜 <3 s 或已选中候选对，
     * 且未被确认为中断）—— 与 `ConnStatus.mediaAgeMs`/`hasSelectedPair` 同源，避免多处口径分叉。
     */
    private fun mediaAliveForUi(): Boolean = connStatus.value.mediaAlive

    /**
     * 【t80】恢复后清除一次性 ICE 失败横幅。
     *
     * 触发条件（纯判定 [CallSurvivability.shouldClearIceError]）：横幅存在、属 ICE 类文案，且
     * 连接状态机已 `CONNECTED`（`reason=connected`）或有媒体判活（`reason=media_alive`）。
     * 清除时落 `ice_error_cleared` 诊断，供复测一行确认"误报横幅已被自动收回"。
     */
    private fun clearIceErrorBannerIfRecovered(status: ConnStatus) {
        val banner = _uiState.value.error
        if (!CallSurvivability.shouldClearIceError(banner, status.phase == ConnPhase.CONNECTED, status.mediaAlive)) {
            return
        }
        _uiState.update { it.copy(error = null) }
        AppLog.i(
            TAG,
            "ice_error_cleared",
            mapOf(
                "reason" to if (status.phase == ConnPhase.CONNECTED) "connected" else "media_alive",
                "phase" to status.phase.name.lowercase(),
                "media_source" to status.mediaSource,
                "media_age_ms" to status.mediaAgeMs.toString(),
                "pair" to status.hasSelectedPair.toString(),
                "banner" to (banner ?: "-"),
                "seq" to callSeq.toString(),
                "session" to "s$sessionId",
            ),
        )
    }

    /**
     * 是否处于"**曾经在房内**（等待/通话/重连）"的语境（t68）。
     *
     * 判定来源（任一成立即可）：
     *   * 信令侧正在"掉线后重连"（[SignalingClient.isRejoinContext]，dl-b 场景的直接证据）；
     *   * 本世代曾 `CONNECTED`（`everConnectedInGeneration`）；
     *   * 对端出现过或已回应（`peerJoined` / `peerResponseSeen`）；
     *   * 已选中候选对（媒体层面确实通过）。
     *
     * 用于区分"房间被回收（可重建）"与"首次入房就找不到房间"（后者仍是终态）。
     */
    private fun inRoomContext(): Boolean =
        client?.isRejoinContext == true ||
            everConnectedInGeneration ||
            peerJoined ||
            peerResponseSeen ||
            connStatus.value.hasSelectedPair

    /**
     * **终态结束**本次通话：给出明确提示 → 清理 → 回首页（captain 2026-09-13 要求）。
     *
     * 与 [fail] 的区别：`fail` 只把错误写进状态（用于**建会话阶段**的失败，此时尚未进入通话，
     * 由 UI 就地提示即可）；`endCallNow` 用于**通话/入房之后**的终态失败，必须**退出通话页**，
     * 避免留下"半死不活的通话界面"。提示文案经 `CallUiState.error` 透传给首页展示
     * （见 `CallScreen` → `AppNavHost` 的 `savedStateHandle` 回传）。
     *
     * @param notice 首页要展示的结束提示（如"通话已结束，可重新创建"）。
     */
    private fun endCallNow(notice: String) {
        if (callEnded) return
        AppLog.w(TAG, "call_end", mapOf("notice" to notice))
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.DISCONNECTED.label,
                isConnecting = false,
                error = notice,
            )
        }
        hangup()
    }

    private companion object {
        const val TAG = "pc"
        const val ROLE_HOST = "host"

        /** 房间已满：重连语境下由 SignalingClient 有界重试，UI 只提示"重试中"。 */
        const val CODE_ROOM_FULL = "ROOM_FULL"

        /** 【t59】连接状态心跳间隔（推进"已等待 N 秒"与超时判定）。 */
        const val CONN_TICK_MS = 1_000L

        /** 【t59】`no_selected_pair` 诊断的限频间隔（首次在超时瞬间落盘）。 */
        const val NO_PAIR_LOG_INTERVAL_MS = 15_000L

        /** 【t59】joiner 重试后延迟重发 offer 的等待时长（给 host 的 offer 让路）。 */

        /** 【t75】诊断 `trigger` 取值（沿用 t71 既有字段名）。 */
        const val TRIGGER_PEER_JOINED = "peer_joined"

        /** 【t75】诊断 `trigger` 取值：重连侧 8 s 兜底。 */
        const val TRIGGER_JOINED = "joined"
        const val RETRY_REOFFER_DELAY_MS = 1_200L

        /** 【t53/t59】世代化开启原因：首次进入通话。 */
        const val REASON_INIT = "init"

        /** 【t53/t59】世代化开启原因：用户一键重试。 */
        const val REASON_RETRY = "retry"

        /**
         * 【t68】世代化开启原因：可恢复态下用户点击「重新创建房间」。
         *
         * 与 [REASON_RETRY] 的区别：重试保留原房间（服务端房间仍在）；重建是**新房间**
         * （原房间已被服务端回收），房间号由服务端重新分配。
         */
        const val REASON_RECREATE = "recreate"


        /** 【t75】重协商发起方：保持在线侧（收到 peerJoined）。 */
        const val INITIATOR_HOST = "host"

        /** 【t75】重协商发起方：重连侧 8 s 兜底（收到 joined）。 */
        const val INITIATOR_REJOINER = "rejoiner"
        /**
         * 【t63】帧存活阈值：`VideoSink.onFrame` 时间戳在此时长内即视为"画面在更新"。
         *
         * 取 1.5 s ⇒ 即使 4G 抖动导致某一秒没有新帧回调，也不会立刻判定"没在更新"；
         * 真正的停流由状态机的帧龄阈值（4 s）+ 去抖（连续 2 tick）判定。
         */
        const val FRAME_ALIVE_MS = 1_500L

        /** 【t63】`remote_frame_liveness` 诊断的限频间隔（来源变化时立即落盘）。 */
        const val FRAME_LIVENESS_LOG_INTERVAL_MS = 15_000L

        /**
         * 终态结束提示（doc/14 §8.5 指定的文案）。
         *
         * 依据：口径 B（严格）下"静默掉线后不可完整恢复"是**已登记 known limitation**
         * （§11.4 D-7，low，不得判失败）——结束时必须**明确告知**并回首页，不留下半死不活的状态。
         */
        const val NOTICE_CALL_ENDED = "通话已结束，可重新创建"

        /**
         * 【t68】房间被服务端回收（可恢复）时的通话页提示。
         *
         * 措辞要点：明确"**没有**结束通话"、明确"画面可能仍在"、给出下一步动作（重建）。
         * 本任务 inScope 不含 `res/values/strings.xml`，故与既有 [NOTICE_CALL_ENDED] 一致内联。
         */
        const val ROOM_LOST_NOTICE = "房间已被服务端回收（通话未结束）：可重新创建房间继续"

        /** 【t68】可恢复态下的通话页连接文案（替换"未连接"，避免误导为终态）。 */
        const val ROOM_LOST_STATE = "房间已回收（可重新创建）"

        /** 【t68】「重新创建房间」进行中的状态文案。 */
        const val ROOM_RECREATING_STATE = "正在重新创建房间…"

        /**
         * 【t68 收尾】信令链路丢失（重连预算耗尽）但通话**未结束**时的提示。
         *
         * 与 [ROOM_LOST_NOTICE] 的区别：房间可能仍在（服务端保留 90 s），只是本端信令断了；
         * 措辞必须说明"画面可能仍在"，并给出重建/挂断两条显式出路。
         */
        const val SIGNAL_LOST_NOTICE = "信令连接已断开（对端未离开，画面可能仍在）：可重新创建房间或手动挂断"

        /** 【t68 收尾】信令丢失但保留通话页时的连接文案。 */
        const val SIGNAL_LOST_STATE = "信令已断开（画面可能仍在）"

        /** 【t68】重建成功、等待对端时的状态文案。 */
        const val ROOM_RECREATED_STATE = "已创建新房间，等待对方加入"

        /** 【t51】对端无响应看门狗时长（验收要求 20 s）。 */
        const val PEER_RESPONSE_TIMEOUT_MS = 20_000L

        /**
         * 【t51】对端无响应提示（用户上一轮无法自行判断"一直正在连接"的原因）。
         *
         * 注：本任务 inScope 不含 `res/values/strings.xml`，故与既有 [NOTICE_CALL_ENDED] 同样
         * 以常量内联；后续若要 i18n 可迁移为字符串资源（已在报告中登记）。
         */
        const val NO_PEER_RESPONSE_NOTICE = "对端无响应：可能未加入或版本不一致（20 秒内未收到 answer/远端候选）"

        /**
         * 进程内递增通话序号（t53）。
         *
         * 为什么放在伴生对象：反复进出房间会创建多个 [CallViewModel] 实例，
         * 只有进程级单调递增的 `seq` 才能把"第几次通话"在多份日志里串起来
         * （`session` 来自 [CallSession.sessionId]，两者一起构成会话身份）。
         */
        private val CALL_SEQ = AtomicInteger(0)
    }
}

// ============================================================================
// 【t53】通话会话槽位（纯 Kotlin，无 Android 依赖，可 JVM 单测）
// ----------------------------------------------------------------------------
// 建模 CallViewModel 的"当前通话"槽位，把三条纪律变成可断言的不变量：
//   1. 每次 initCall 都开启**新一代**通话（旧实现 `if (started) return` 会让新房间跑在旧会话上）；
//   2. 新一代开始前，上一代若仍活跃，**必须被关闭**（`beginCall()` 返回 true 即调用方须 close）；
//   3. 上一代的"PC 已就绪"标记**绝不**泄漏到新一代（`peerReady` 在换代时清零）——
//      这正是"反复进出房间后复用旧 PeerConnection"的状态残留点。
// 单测见 app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSessionSlotTest.kt。
// ============================================================================

/**
 * `initCall` 的处置结果（t53）。
 */
enum class InitDecision {
    /** 同一次通话的重复初始化：必须**空操作**（配置变更重建组合时会走到这里）。 */
    IGNORE,

    /** 新一代通话：必须新建 `CallSession`/`PeerConnection`，上一代活跃时先 `close()`。 */
    NEW_CALL,
}

/**
 * 通话会话槽位状态机（t53）。
 */
class CallSessionSlot {

    /** 当前世代号（从 0 开始，每 [beginCall] 递增 1）。 */
    var generation: Int = 0
        private set

    /** 当前世代是否已有活跃会话。 */
    var hasActiveSession: Boolean = false
        private set

    /** 当前世代的会话是否已建立 PeerConnection（换代时必清零）。 */
    var peerReady: Boolean = false
        private set

    /** 当前世代的房间号（无会话时为空串）。 */
    var roomId: String = ""
        private set

    /** 当前世代的角色（无会话时为空串）。 */
    var role: String = ""
        private set

    /**
     * 判定一次 `initCall` 应当如何处理。
     *
     * @param roomId 本次要进入的房间。
     * @param role 本次角色。
     * @param callEnded 上一次通话是否已结束（挂断/终态失败）。
     * @return [InitDecision.IGNORE] = 同一次通话的重复初始化；[InitDecision.NEW_CALL] = 开新一代。
     */
    fun decideInit(roomId: String, role: String, callEnded: Boolean): InitDecision =
        if (hasActiveSession && !callEnded && this.roomId == roomId && this.role == role) {
            InitDecision.IGNORE
        } else {
            InitDecision.NEW_CALL
        }

    /**
     * 开始新一代通话。
     *
     * @return 上一代是否仍活跃；true ⇒ 调用方**必须**先关闭旧 `CallSession`（绝不复用）。
     */
    fun beginCall(roomId: String, role: String): Boolean {
        val stale = hasActiveSession
        generation += 1
        hasActiveSession = true
        peerReady = false
        this.roomId = roomId
        this.role = role
        return stale
    }

    /** 标记当前世代的 PeerConnection 已建立；没有活跃会话时不生效（防止给已结束的通话打标记）。 */
    fun markPeerReady() {
        if (hasActiveSession) peerReady = true
    }

    /**
     * 结束当前世代。
     *
     * @return true 表示本次真的从"活跃"变为"不活跃"（幂等：重复结束返回 false）。
     */
    fun endCall(): Boolean {
        if (!hasActiveSession) return false
        hasActiveSession = false
        peerReady = false
        roomId = ""
        role = ""
        return true
    }
}
