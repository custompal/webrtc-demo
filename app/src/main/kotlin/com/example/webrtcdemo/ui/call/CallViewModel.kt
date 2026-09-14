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
import com.example.webrtcdemo.signaling.SignalingClient
import com.example.webrtcdemo.signaling.SignalingErrorPolicy
import com.example.webrtcdemo.signaling.SignalingHolder
import com.example.webrtcdemo.signaling.SignalingIdentity
import com.example.webrtcdemo.signaling.SignalingMessage
import com.example.webrtcdemo.webrtc.CallSession
import com.example.webrtcdemo.webrtc.IceServerCache
import com.example.webrtcdemo.webrtc.WebRtcEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack

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

    private var started = false

    private var sessionReady = false

    private var peerJoined = false

    private var role = ""

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
    }

    /**
     * 初始化通话（导航到 CallScreen 时调用一次）。
     *
     * @param roomId 房间号。
     * @param role `host` / `joiner`。
     */
    fun initCall(roomId: String, role: String) {
        if (started) return
        started = true
        this.role = role
        _uiState.update { it.copy(roomId = roomId, role = role, isConnecting = true) }
        val app = getApplication<Application>()

        SignalingIdentity.reset()
        val connection = SignalingHolder.getOrCreate(AppConfig.signalingUrl(app))
        connection.listener = this
        client = connection

        if (!WebRtcEngine.initialize(app)) {
            // t25：把**真实异常类名/message**带进用户可见文案（原先只有笼统一句，真机排障极难）。
            val detail = WebRtcEngine.lastFailureDetail()
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
            fail("WebRTC 引擎组件缺失")
            return
        }
        val callSession = CallSession(
            factory = factory,
            mediaCapture = capture,
            signaling = connection,
            audioTrack = WebRtcEngine.audioTrack(),
            listener = this,
        )
        session = callSession
        val ok = callSession.start(IceServerCache.get(), AppConfig.forceRelay(app))
        if (!ok) {
            fail("创建 PeerConnection 失败")
            return
        }
        _localVideoTrack.value = callSession.currentVideoTrack()
        // 若 NAT 探测在进入通话页之前就完成了（常见：host 在等待对端时探测完），这里补发一次
        maybeSendNatType()
        AppLog.i(TAG, "call_init", mapOf("room" to roomId, "role" to role))
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
        AppLog.i(TAG, "hangup")
        client?.leave()
        session?.close()
        session = null
        IceServerCache.clear()
        NatTypeRepository.cancel()
        SignalingHolder.release()
        _navigateHome.value = true
    }

    /** 已消费「回首页」事件。 */
    fun onNavigateHomeConsumed() {
        _navigateHome.value = false
    }

    /** 远端首帧（SurfaceViewRenderer.onFirstFrameRendered，§7.3）。 */
    fun onRemoteFirstFrame() {
        _uiState.update { it.copy(isRemoteVideoReady = true) }
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
        _uiState.update {
            it.copy(connectionState = state.label, isConnecting = state == ConnectionState.WAITING)
        }
        if (state == ConnectionState.IN_CALL) {
            _uiState.update { it.copy(isConnecting = false) }
        }
        // 通话已建立后落到 DISCONNECTED，且不是用户主动挂断 ⇒ 通话已**终态结束**
        // （重连 3 次 / 重连后 join 重试 10 次均耗尽，或服务端终态码被抑制重连）。
        // 按 captain 2026-09-13 要求：**干脆回首页 + 明确提示，不留半死不活的通话界面**。
        // 注意：短暂掉线走的是 CONNECTING（见 SignalingClient.scheduleReconnect），不会误触发这里。
        if (state == ConnectionState.DISCONNECTED && !callEnded && sessionReady) {
            endCallNow(NOTICE_CALL_ENDED)
        }
    }

    override fun onMessage(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Joined -> {
                // 通话中收到 joined = **异常断线后重连成功**（go-dev 对齐通知第 7 点 / doc/09 §6）：
                // 服务端保留房间、对端会收到新的 peerJoined，因此发起方必须重新走 offer/answer；
                // 接收方（joiner）不主动发 offer，等对端的新 offer（maybeCreateOffer 内按 role 判定）。
                AppLog.i(TAG, "rejoined", mapOf("room" to message.roomId))
                if (sessionReady) {
                    peerJoined = true
                    maybeCreateOffer()
                }
            }

            is SignalingMessage.PeerJoined -> {
                peerJoined = true
                AppLog.i(TAG, "peer_joined", mapOf("peer" to message.peerId))
                maybeCreateOffer()
            }

            is SignalingMessage.PeerLeft -> {
                AppLog.i(TAG, "peer_left")
                onIceEvent(IceEvent(IceEventType.SELECTED_PAIR, "peer_left"))
                // 口径 B（doc/14 §8.5 现行口径 + §11.4 D-7，known limitation/low/不得判失败）：
                // IN_CALL 收到 peerLeft 立即转 DISCONNECTED 并挂断 —— 房间立即销毁（§7），
                // 掉线方重连得 ROOM_NOT_FOUND（终态），双方回首页并明确提示，不产生半死不活的状态。
                // 口径 A（有界宽限期）属**后续增强**且**前提是同时实现 ICE restart**，本轮按契约不实施。
                hangup()
            }

            is SignalingMessage.Offer -> session?.onRemoteOffer(message.sdp)
            is SignalingMessage.Answer -> session?.onRemoteAnswer(message.sdp)
            is SignalingMessage.Ice ->
                session?.onRemoteIceCandidate(message.candidate, message.sdpMid, message.sdpMLineIndex)

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
                    // 终态码（ROOM_NOT_FOUND / ROOM_EXPIRED / INVALID_MESSAGE / NOT_IN_ROOM）：
                    // 房间已销毁或报文非法 ⇒ 明确失败并退出通话页；其余码只呈现错误但仍退出，
                    // 绝不停在通话页显示"重试中…"（captain 2026-09-13）。
                    endCallNow(
                        if (SignalingErrorPolicy.endsCall(message.code)) {
                            NOTICE_CALL_ENDED
                        } else {
                            "服务端错误: ${message.code}"
                        }
                    )
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
        _uiState.update { it.copy(isConnecting = false) }
        maybeCreateOffer()
    }

    override fun onRemoteVideoTrack(track: VideoTrack) {
        _remoteVideoTrack.value = track
    }

    override fun onIceEvent(event: IceEvent) {
        _iceEvents.update { current -> current + event }
    }

    override fun onStats(snapshot: StatsSnapshot) {
        _uiState.update {
            it.copy(
                connectionType = snapshot.connectionType,
                upBitrate = snapshot.upBitrateBps,
                downBitrate = snapshot.downBitrateBps,
                availableOutgoingBitrate = snapshot.availableOutgoingBitrateBps,
                encoderImplementation = snapshot.encoderImplementation,
            )
        }
        if (snapshot.connectionType.isNotEmpty() || snapshot.encoderImplementation.isNotEmpty()) {
            _uiState.update { it.copy(isConnecting = false) }
        }
    }

    override fun onError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    override fun onCleared() {
        session?.close()
        session = null
        super.onCleared()
    }

    // ============================ 内部实现 ============================

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
        val current = session ?: return
        if (current.isClosed()) return
        current.createOffer()
    }

    private fun fail(message: String) {
        AppLog.e(TAG, "call_failed", mapOf("reason" to message))
        _uiState.update { it.copy(error = message, isConnecting = false) }
    }

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

        /**
         * 终态结束提示（doc/14 §8.5 指定的文案）。
         *
         * 依据：口径 B（严格）下"静默掉线后不可完整恢复"是**已登记 known limitation**
         * （§11.4 D-7，low，不得判失败）——结束时必须**明确告知**并回首页，不留下半死不活的状态。
         */
        const val NOTICE_CALL_ENDED = "通话已结束，可重新创建"
    }
}
