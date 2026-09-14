package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.model.IceEvent
import com.example.webrtcdemo.model.IceEventType
import com.example.webrtcdemo.model.StatsSnapshot
import com.example.webrtcdemo.signaling.SignalingClient
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// ============================================================================
// 1:1 会话编排（doc/14 §2.1 / §7.4 / §7.5；协议 doc/09 §5）
// ----------------------------------------------------------------------------
// 职责：PeerConnection 生命周期、offer/answer/ice 流程、stats 采样（每 2 s）、
//       音视频轨开关（静音/摄像头）、ICE 事件累积。
//
// 事件来源（§7.4，全部来自 org.webrtc 公共 API，不跨 JNI）：
//   ICE candidate / 连接状态 / 收集状态  ← PeerConnectionObserverImpl
//   传输模式 / 上下行速率 / 编码实现名     ← StatsMapper（getStats 每 2 s）
//   编码目标码率                          ← EncoderRateBus（setRateAllocation 直通）
//
// 冻结约束：track id `video0`/`audio0`、stream id `stream0`（§7.5）；
//           禁止手改 SDP 文本，VP9 由 setCodecPreferences 强制（§7.5）。
// ============================================================================

/**
 * 通话会话。
 *
 * @param factory 共享工厂（[WebRtcEngine.factory]）。
 * @param mediaCapture 采集链（[WebRtcEngine.mediaCapture]）。
 * @param signaling 信令客户端（用于发送 offer/answer/ice）。
 * @param audioTrack 共享音频轨（[WebRtcEngine.audioTrack]）。
 * @param listener 上层回调（UI 状态）。
 */
class CallSession(
    private val factory: PeerConnectionFactory,
    private val mediaCapture: MediaCapture,
    private val signaling: SignalingClient,
    private val audioTrack: AudioTrack?,
    private val listener: Listener,
) {

    /**
     * 上层回调。
     */
    interface Listener {
        /** 一条 ICE 事件（累积在 UI 侧，通话结束才清空，§7.4）。 */
        fun onIceEvent(event: IceEvent)

        /** 一次 stats 采样（每 2 s）。 */
        fun onStats(snapshot: StatsSnapshot)

        /** 出错（UI 提示，不崩溃）。 */
        fun onError(message: String)

        /** PeerConnection 已创建并可发 offer/answer。 */
        fun onReady()

        /** 远端视频轨已就绪（Unified Plan 下由 `onAddTrack` 得到），用于绑定渲染器（§7.3）。 */
        fun onRemoteVideoTrack(track: VideoTrack)
    }

    companion object {
        private const val TAG = "pc"

        /** stats 采样间隔（§7.4 冻结 2 s）。 */
        const val STATS_INTERVAL_MS = 2_000L

        /** 看门狗第一档：15 s 打 WARN（含候选类型计数）。 */
        const val ICE_WARN_MS = 15_000L

        /** 看门狗第二档：30 s 打 ERROR 并上报 UI（含「强制中继」提示）。 */
        const val ICE_FAIL_MS = 30_000L

        private const val CODEC_VP9 = "VP9"
    }

    private var peerConnection: PeerConnection? = null

    private var videoTrack: VideoTrack? = null

    /** 远端视频轨（Unified Plan：来自 `onAddTrack`；Plan-B 兼容路径来自 `onAddStream`）。 */
    @Volatile
    private var remoteVideoTrack: VideoTrack? = null

    private var statsTimer: ScheduledExecutorService? = null

    private val statsMapper = StatsMapper()

    @Volatile
    private var closed = false

    // ======================= t44 诊断与竞态兜底状态 =======================

    /**
     * PeerConnection 就绪前到达的**远端 offer** 暂存（真机缺陷①：joiner 不回 answer）。
     *
     * 竞态：joiner 侧 `joined`（带来 TURN 配置）与 `offer` 是两条独立信令，
     * 若 offer 先于 PeerConnection 创建到达，原实现直接 `return` —— **既无日志也无 UI 错误**，
     * 表现为"信令侧只有 offer_forward、没有 answer_forward"。暂存后在 [start] 成功时回放。
     */
    private var pendingRemoteOffer: String? = null

    /** 同上，**远端 answer** 暂存（host 侧对称竞态）。 */
    private var pendingRemoteAnswer: String? = null

    /** 同上，**远端 ICE candidate** 暂存（PC 未就绪时丢失会导致候选对永远配不上）。 */
    private val pendingRemoteCandidates = ArrayList<RemoteCandidate>()

    /** 本端/对端候选按类型的计数（ICE 超时兜底时一次性打印，供下一轮真机定因）。 */
    private val candidateCounter = IceCandidateCounter()

    /** ICE/DTLS 连通性看门狗（超时兜底 + 错误上报 UI）。 */
    private var connectivityWatchdog: ScheduledExecutorService? = null

    /** 看门狗启动时刻（用于 `ice_watchdog_ok` 的耗时字段）。 */
    @Volatile
    private var connectivityWatchdogStartMs = 0L

    /** 是否已收到远端描述（决定看门狗起点）。 */
    @Volatile
    private var remoteDescriptionSet = false

    /** 一条待回放的远端候选。 */
    private data class RemoteCandidate(val sdp: String, val sdpMid: String?, val sdpMLineIndex: Int?)

    /** 是否已关闭。 */
    fun isClosed(): Boolean = closed

    /** 当前视频轨（供渲染池绑定）。 */
    fun currentVideoTrack(): VideoTrack? = videoTrack

    /**
     * 建立 PeerConnection 并挂上本地音视频轨。
     *
     * @param ice 信令下发的 ICE server（§7.5：只能来自 created/joined）。
     * @param forceRelay 是否强制中继（诊断开关）。
     * @return 是否创建成功。
     */
    fun start(ice: IceServerConfig?, forceRelay: Boolean): Boolean {
        if (closed) return false
        if (peerConnection != null) return true
        // 【t44】把"本次到底用了什么 ICE 配置"与"是否强制中继"显式落盘：
        // 真机复测时这一行即可判定"没配 TURN"还是"配了但连不上"（原 `rtc_config` 在 INFO，
        // 被日志过滤 Bug 吞掉，导致上一轮完全看不到）。
        val iceSummary = buildString {
            if (ice?.stunUrl?.isNotBlank() == true) append("stun")
            if (ice?.turnUrl?.isNotBlank() == true) {
                if (isNotEmpty()) append('+')
                append("turn")
            }
            if (isEmpty()) append("-")
        }
        AppLog.i(
            TAG,
            "pc_starting",
            mapOf("ice_servers" to iceSummary, "force_relay" to forceRelay.toString()),
        )
        val config = WebRtcConfig.build(ice, forceRelay)
        val observer = PeerConnectionObserverImpl(events)
        val connection = factory.createPeerConnection(config, observer)
        if (connection == null) {
            AppLog.e(TAG, "pc_create_failed")
            listener.onError("创建 PeerConnection 失败")
            return false
        }
        peerConnection = connection

        val localVideo = mediaCapture.ensureStarted()
        videoTrack = localVideo
        if (localVideo != null) {
            connection.addTrack(localVideo, listOf(WebRtcConfig.STREAM_ID))
        } else {
            AppLog.w(TAG, "video_track_missing")
        }
        audioTrack?.let { connection.addTrack(it, listOf(WebRtcConfig.STREAM_ID)) }

        // VP9 置于首位并移除其它视频编码（§7.5：不改 SDP 文本）
        applyCodecPreferences(connection)

        statsMapper.reset()
        startStatsLoop(connection)
        AppLog.i(TAG, "pc_created")
        // 【t44】把 PeerConnection 就绪前暂存的远端 offer/answer/ICE 回放进来（消除信令竞态）
        flushPendingRemote()
        listener.onReady()
        return true
    }

    /**
     * 作为 host 创建 offer（收到 `peerJoined` 后调用，doc/09 §3.3）。
     */
    fun createOffer() {
        val connection = peerConnection ?: run {
            listener.onError("PeerConnection 未就绪")
            return
        }
        AppLog.i(TAG, "offer_create")
        connection.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    // 【t44】证明"候选确实进入了 SDP"（trickle 下初始 offer 通常为 0，随后逐个 ICE 发送）
                    AppLog.i(
                        TAG,
                        "offer_created",
                        mapOf(
                            "sdp_bytes" to sdp.description.length.toString(),
                            "candidates" to IceCandidateInfo.summarizeSdpCandidates(sdp.description),
                        ),
                    )
                    connection.setLocalDescription(localSetObserver, sdp)
                    signaling.sendOffer(sdp.description)
                    AppLog.i(TAG, "offer_sent")
                }

                override fun onSetSuccess() = Unit

                override fun onCreateFailure(error: String) {
                    AppLog.e(TAG, "offer_create_failed", mapOf("reason" to error))
                    listener.onError("创建 Offer 失败: $error")
                }

                override fun onSetFailure(error: String) {
                    AppLog.e(TAG, "offer_set_failed", mapOf("reason" to error))
                    listener.onError("设置 Offer 失败: $error")
                }
            },
            offerAnswerConstraints(),
        )
    }

    /**
     * 作为 joiner 处理远端 offer：setRemoteDescription → createAnswer → setLocalDescription → 发送。
     *
     * @param sdp 远端 SDP（doc/09 §3.4）。
     */
    fun onRemoteOffer(sdp: String) {
        // 【t44】先记录"确实收到了 offer"，再做就绪判断 —— 原实现把日志放在 early-return 之后，
        // 一旦 PC 未就绪就**完全没有痕迹**（真机缺陷①无法定因的直接原因之一）。
        AppLog.i(TAG, "offer_received", mapOf("sdp_bytes" to sdp.length.toString()))
        val connection = peerConnection
        if (connection == null) {
            if (closed) {
                AppLog.e(TAG, "offer_dropped", mapOf("reason" to "session_closed"))
                listener.onError("会话已关闭，收到 Offer 无法处理（未回 answer）")
            } else {
                pendingRemoteOffer = sdp
                AppLog.w(
                    TAG,
                    "offer_deferred",
                    mapOf("reason" to "pc_not_ready", "sdp_bytes" to sdp.length.toString()),
                )
                listener.onError("会话尚未就绪，Offer 已暂存（就绪后会回 answer）")
            }
            return
        }
        connection.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(sdp0: SessionDescription) = Unit

                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    startConnectivityWatchdog(connection)
                    AppLog.i(TAG, "answer_create")
                    connection.createAnswer(
                        object : SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription) {
                                AppLog.i(
                                    TAG,
                                    "answer_created",
                                    mapOf(
                                        "sdp_bytes" to description.description.length.toString(),
                                        "candidates" to IceCandidateInfo.summarizeSdpCandidates(description.description),
                                    ),
                                )
                                connection.setLocalDescription(localSetObserver, description)
                                signaling.sendAnswer(description.description)
                                AppLog.i(TAG, "answer_sent")
                            }

                            override fun onSetSuccess() = Unit

                            override fun onCreateFailure(error: String) {
                                AppLog.e(TAG, "answer_create_failed", mapOf("reason" to error))
                                listener.onError("创建 Answer 失败: $error")
                            }

                            override fun onSetFailure(error: String) {
                                AppLog.e(TAG, "answer_set_failed", mapOf("reason" to error))
                                listener.onError("设置 Answer 失败: $error")
                            }
                        },
                        offerAnswerConstraints(),
                    )
                }

                override fun onCreateFailure(error: String) = Unit

                override fun onSetFailure(error: String) {
                    AppLog.e(TAG, "remote_offer_set_failed", mapOf("reason" to error))
                    listener.onError("设置远端 Offer 失败: $error")
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    /**
     * 处理远端 answer（host 侧，doc/09 §3.5）。
     *
     * @param sdp 远端 SDP。
     */
    fun onRemoteAnswer(sdp: String) {
        AppLog.i(
            TAG,
            "answer_received",
            mapOf(
                "sdp_bytes" to sdp.length.toString(),
                "candidates" to IceCandidateInfo.summarizeSdpCandidates(sdp),
            ),
        )
        val connection = peerConnection
        if (connection == null) {
            if (closed) {
                AppLog.e(TAG, "answer_dropped", mapOf("reason" to "session_closed"))
                listener.onError("会话已关闭，收到 Answer 无法处理")
            } else {
                pendingRemoteAnswer = sdp
                AppLog.w(TAG, "answer_deferred", mapOf("reason" to "pc_not_ready"))
                listener.onError("会话尚未就绪，Answer 已暂存（就绪后自动应用）")
            }
            return
        }
        remoteDescriptionSet = true
        startConnectivityWatchdog(connection)
        connection.setRemoteDescription(
            remoteSetObserver,
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    /**
     * 添加远端 ICE candidate（doc/09 §3.6）。
     *
     * @param candidate 候选文本。
     * @param sdpMid 媒体标识（可空）。
     * @param sdpMLineIndex 媒体行索引（可空；二者至少一个有效，§8.2）。
     */
    fun onRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        val mid = sdpMid ?: ""
        // §8.2：sdpMid 与 sdpMLineIndex 至少一个有效
        if (mid.isEmpty() && sdpMLineIndex == null) {
            AppLog.w(TAG, "ice_dropped", mapOf("reason" to "no_mid_and_no_index"))
            return
        }
        // 【t44】对端候选的类型/地址/端口必须落盘：这是判断"对端把什么候选送到了本端"的唯一证据
        val info = IceCandidateInfo.parse(candidate)
        candidateCounter.addRemote(info)
        val connection = peerConnection
        if (connection == null) {
            if (closed) {
                AppLog.w(TAG, "ice_dropped", mapOf("reason" to "session_closed", "remote" to info.summary()))
                return
            }
            synchronized(pendingRemoteCandidates) {
                pendingRemoteCandidates.add(RemoteCandidate(candidate, sdpMid, sdpMLineIndex))
            }
            AppLog.w(
                TAG,
                "ice_deferred",
                mapOf("remote" to info.summary(), "queued" to pendingRemoteCandidates.size.toString()),
            )
            return
        }
        AppLog.i(TAG, "ice_candidate_remote", mapOf("remote" to info.summary()))
        connection.addIceCandidate(IceCandidate(mid, sdpMLineIndex ?: 0, candidate))
    }

    /** 静音/取消静音（麦克风）。 */
    fun setAudioEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
        AppLog.i(TAG, "audio_toggle", mapOf("enabled" to enabled.toString()))
    }

    /** 开启/关闭摄像头（本地视频轨）。 */
    fun setVideoEnabled(enabled: Boolean) {
        val track = videoTrack ?: mediaCapture.currentVideoTrack()
        track?.setEnabled(enabled)
        AppLog.i(TAG, "video_toggle", mapOf("enabled" to enabled.toString()))
    }

    /** 关闭会话（`hangup` 调用；不销毁 EglBase/工厂，§7.1）。 */
    fun close() {
        if (closed) return
        closed = true
        statsTimer?.shutdownNow()
        statsTimer = null
        connectivityWatchdog?.shutdownNow()
        connectivityWatchdog = null
        val connection = peerConnection
        peerConnection = null
        videoTrack = null
        try {
            connection?.close()
        } catch (t: Throwable) {
            AppLog.w(TAG, "pc_close_failed", mapOf("reason" to (t.message ?: "-")))
        }
        try {
            connection?.dispose()
        } catch (t: Throwable) {
            AppLog.w(TAG, "pc_dispose_failed", mapOf("reason" to (t.message ?: "-")))
        }
        AppLog.i(TAG, "pc_closed")
    }

    // ============================ 内部实现 ============================

    private val localSetObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit

        override fun onSetSuccess() {
            AppLog.d(TAG, "local_description_set")
        }

        override fun onCreateFailure(error: String) = Unit

        override fun onSetFailure(error: String) {
            AppLog.e(TAG, "local_description_set_failed", mapOf("reason" to error))
            listener.onError("设置本地描述失败: $error")
        }
    }

    private val remoteSetObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit

        override fun onSetSuccess() {
            AppLog.d(TAG, "remote_description_set")
        }

        override fun onCreateFailure(error: String) = Unit

        override fun onSetFailure(error: String) {
            AppLog.e(TAG, "remote_description_set_failed", mapOf("reason" to error))
            listener.onError("设置远端描述失败: $error")
        }
    }

    private val events = object : PeerConnectionObserverImpl.Events {
        override fun onIceCandidate(candidate: IceCandidate) {
            // 【t44】本端候选类型/地址/端口落盘（判断是否真的 gather 到 host/srflx/relay）
            val info = IceCandidateInfo.parse(candidate.sdp)
            candidateCounter.addLocal(info)
            AppLog.i(
                TAG,
                "ice_candidate_local",
                mapOf(
                    "local" to info.summary(),
                    "mid" to (candidate.sdpMid ?: "-"),
                    "idx" to candidate.sdpMLineIndex.toString(),
                ),
            )
            signaling.sendIce(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            emitEvent(IceEventType.CANDIDATE, "mid=${candidate.sdpMid} idx=${candidate.sdpMLineIndex} ${info.summary()}")
        }

        override fun onIceConnectionState(state: PeerConnection.IceConnectionState) {
            // 事件名由 PeerConnectionObserverImpl 统一落盘（`pc_ice_connection_state`，§9.1 固定表），
            // 这里只做状态机处理，避免同名事件重复两遍、字段不一致。
            if (state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED
            ) {
                stopConnectivityWatchdog(ifConnected = true)
            }
            emitEvent(IceEventType.ICE_CONNECTION, state.name)
        }

        override fun onIceGatheringState(state: PeerConnection.IceGatheringState) {
            emitEvent(IceEventType.ICE_GATHERING, state.name)
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                // 新增事件名（t44，已在 reports/15-connection-defect.md 登记）：
                // 收集结束时一次性给出**按类型的候选计数**，这是"到底 gather 到什么"的直接证据。
                AppLog.i(
                    TAG,
                    "ice_gathering_complete",
                    mapOf("local" to candidateCounter.localSummary(), "relay" to candidateCounter.localRelayCount().toString()),
                )
                emitEvent(IceEventType.END_OF_CANDIDATES, "complete local=${candidateCounter.localSummary()}")
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            // 事件名由 PeerConnectionObserverImpl 统一落盘（`pc_connection_state`，t44 新增，已在报告登记）
            if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                stopConnectivityWatchdog(ifConnected = true)
            }
            emitEvent(IceEventType.ICE_CONNECTION, "transport=${state.name}")
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            val local = IceCandidateInfo.parse(event.local?.sdp ?: "")
            val remote = IceCandidateInfo.parse(event.remote?.sdp ?: "")
            emitEvent(
                IceEventType.CANDIDATE_PAIR,
                "local=${local.summary()} remote=${remote.summary()} reason=${event.reason ?: "-"}",
            )
        }

        override fun onIceCandidateError(event: IceCandidateErrorEvent) {
            emitEvent(
                IceEventType.CANDIDATE,
                "ice_candidate_error url=${event.url ?: "-"} addr=${event.address ?: "-"}:${event.port} " +
                    "code=${event.errorCode} text=${event.errorText ?: "-"}",
            )
        }

        override fun onSignalingState(state: PeerConnection.SignalingState) {
            AppLog.d(TAG, "signaling_state", mapOf("state" to state.name))
        }

        override fun onIceConnectionReceiving(receiving: Boolean) {
            AppLog.d(TAG, "ice_receiving", mapOf("receiving" to receiving.toString()))
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
            AppLog.d(TAG, "ice_candidates_removed", mapOf("count" to candidates.size.toString()))
        }

        override fun onAddStream(stream: MediaStream) {
            AppLog.i(TAG, "remote_stream_added", mapOf("id" to stream.id))
        }

        override fun onRemoveStream(stream: MediaStream) {
            AppLog.i(TAG, "remote_stream_removed", mapOf("id" to stream.id))
        }

        override fun onDataChannel(channel: DataChannel) {
            AppLog.d(TAG, "data_channel", mapOf("label" to channel.label()))
        }

        override fun onRenegotiationNeeded() {
            AppLog.d(TAG, "renegotiation_needed")
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
            val track = receiver.track()
            AppLog.i(TAG, "remote_track_added", mapOf("kind" to (track?.kind() ?: "-")))
            if (track is VideoTrack) {
                remoteVideoTrack = track
                listener.onRemoteVideoTrack(track)
            }
        }
    }

    private fun emitEvent(type: IceEventType, detail: String) {
        listener.onIceEvent(IceEvent(type, detail))
    }

    // ============================ t44：竞态兜底与连通性看门狗 ============================

    /**
     * 回放 PeerConnection 就绪前暂存的远端 offer/answer/ICE（t44 真机缺陷①修复）。
     *
     * 为什么必须做：`joined` 与 `offer` 是两条独立信令，joiner 侧如果 offer 先到，
     * 原实现在 `peerConnection == null` 时**静默 return** ⇒ 信令侧只看到 `offer_forward`、
     * 没有 `answer_forward`，且 UI 无任何错误提示（真机现象完全一致）。
     * 这里保证"**收到 offer 必回 answer**"：暂存 → 就绪后立即回放；真回放失败则经
     * [Listener.onError] 报出可诊断错误。
     */
    private fun flushPendingRemote() {
        val offer = pendingRemoteOffer
        val answer = pendingRemoteAnswer
        val candidates = synchronized(pendingRemoteCandidates) {
            val copy = ArrayList(pendingRemoteCandidates)
            pendingRemoteCandidates.clear()
            copy
        }
        if (offer != null) {
            pendingRemoteOffer = null
            AppLog.i(TAG, "offer_replayed", mapOf("sdp_bytes" to offer.length.toString()))
            onRemoteOffer(offer)
        }
        if (answer != null) {
            pendingRemoteAnswer = null
            AppLog.i(TAG, "answer_replayed", mapOf("sdp_bytes" to answer.length.toString()))
            onRemoteAnswer(answer)
        }
        if (candidates.isNotEmpty()) {
            AppLog.i(TAG, "ice_replayed", mapOf("count" to candidates.size.toString()))
            for (candidate in candidates) {
                onRemoteIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }
        }
        AppLog.i(
            TAG,
            "remote_replay_done",
            mapOf(
                "offer" to (offer != null).toString(),
                "answer" to (answer != null).toString(),
                "candidates" to candidates.size.toString(),
            ),
        )
    }

    /**
     * ICE/DTLS 连通性看门狗（t44）。
     *
     * 目的：把"停在正在连接会议"从**不可诊断**变成**一次复测即可定因** ——
     * 15 s 打 WARN（含本端/对端候选类型计数），30 s 打 ERROR 并**上报 UI**（含「强制中继」提示）。
     * 若期间 ICE/传输已 CONNECTED，则取消（记 `ice_watchdog_ok`）。
     */
    private fun startConnectivityWatchdog(connection: PeerConnection) {
        if (connectivityWatchdog != null || closed) return
        val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ice-watchdog").apply { isDaemon = true }
        }
        connectivityWatchdog = timer
        connectivityWatchdogStartMs = System.currentTimeMillis()
        AppLog.i(TAG, "ice_watchdog_started", mapOf("timeout_ms" to ICE_WARN_MS.toString()))
        timer.schedule({ checkConnectivity(connection, ICE_WARN_MS) }, ICE_WARN_MS, TimeUnit.MILLISECONDS)
        timer.schedule({ checkConnectivity(connection, ICE_FAIL_MS) }, ICE_FAIL_MS, TimeUnit.MILLISECONDS)
    }

    /** 连通性仍未建立时的诊断/上报；[afterMs] 区分 WARN（15 s）与 ERROR（30 s）两档。 */
    private fun checkConnectivity(connection: PeerConnection, afterMs: Long) {
        if (closed) return
        val iceState = try {
            connection.iceConnectionState()
        } catch (t: Throwable) {
            null
        }
        val transportState = try {
            connection.connectionState()
        } catch (t: Throwable) {
            null
        }
        if (iceState == PeerConnection.IceConnectionState.CONNECTED ||
            iceState == PeerConnection.IceConnectionState.COMPLETED
        ) {
            stopConnectivityWatchdog(ifConnected = true)
            return
        }
        val fields = mapOf(
            "after_ms" to afterMs.toString(),
            "ice_state" to (iceState?.name ?: "-"),
            "transport" to (transportState?.name ?: "-"),
            "local_candidates" to candidateCounter.localSummary(),
            "remote_candidates" to candidateCounter.remoteSummary(),
            "local_relay" to candidateCounter.localRelayCount().toString(),
            "remote_desc_set" to remoteDescriptionSet.toString(),
        )
        if (afterMs >= ICE_FAIL_MS) {
            AppLog.e(TAG, "ice_timeout", fields)
            listener.onError(
                "ICE 未连通（本端候选 ${candidateCounter.localSummary()}；" +
                    "对端候选 ${candidateCounter.remoteSummary()}）。" +
                    "可在诊断页打开「强制中继」后重试，并立即导出日志",
            )
        } else {
            AppLog.w(TAG, "ice_not_connected", fields)
        }
    }

    /** 停止看门狗；[ifConnected] 为真表示"因已连通而停止"（记一条正面证据）。 */
    private fun stopConnectivityWatchdog(ifConnected: Boolean) {
        val timer = connectivityWatchdog ?: return
        connectivityWatchdog = null
        if (ifConnected) {
            AppLog.i(
                TAG,
                "ice_watchdog_ok",
                mapOf("elapsed_ms" to (System.currentTimeMillis() - connectivityWatchdogStartMs).toString()),
            )
        }
        timer.shutdownNow()
    }

    /** 每 2 s 拉一次 stats（§7.4）。 */
    private fun startStatsLoop(connection: PeerConnection) {
        val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "stats-sampler").apply { isDaemon = true }
        }
        statsTimer = timer
        timer.scheduleWithFixedDelay(
            Runnable {
                if (closed) return@Runnable
                try {
                    connection.getStats(object : RTCStatsCollectorCallback {
                        override fun onStatsDelivered(report: RTCStatsReport) {
                            if (closed) return
                            listener.onStats(statsMapper.map(report, System.currentTimeMillis()))
                        }
                    })
                } catch (t: Throwable) {
                    AppLog.w(TAG, "stats_failed", mapOf("reason" to (t.message ?: "-")))
                }
            },
            STATS_INTERVAL_MS,
            STATS_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** 用 RtpSender capabilities 把 VP9 置首（§7.5：禁止改 SDP 文本）。 */
    private fun applyCodecPreferences(connection: PeerConnection) {
        try {
            val capabilities = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            val vp9 = capabilities.codecs.filter { it.name.equals(CODEC_VP9, ignoreCase = true) }
            if (vp9.isEmpty()) {
                AppLog.w(TAG, "vp9_not_advertised", mapOf("codecs" to capabilities.codecs.size.toString()))
                return
            }
            for (transceiver in connection.transceivers) {
                if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                    transceiver.setCodecPreferences(vp9)
                }
            }
            AppLog.i(TAG, "codec_preferences_set", mapOf("codec" to CODEC_VP9, "count" to vp9.size.toString()))
        } catch (t: Throwable) {
            AppLog.w(TAG, "codec_preferences_failed", mapOf("reason" to (t.message ?: "-")))
        }
    }

    /** offer/answer 的接收约束（Unified Plan 下 addTrack 已隐含，这里显式声明便于对端只发不收的场景）。 */
    private fun offerAnswerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }
}
