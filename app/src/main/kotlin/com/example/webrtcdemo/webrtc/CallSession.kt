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
import java.util.concurrent.atomic.AtomicInteger

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

        /**
         * 全局递增会话号（t53）。
         *
         * 为什么必须**进程内全局**递增：反复进出房间会在同一进程里创建多个 [CallSession]，
         * 只有单调递增的编号才能在多份日志交织时一眼判断"这条事件属于哪一次会话"。
         * `session` 字段与 [CallViewModel] 侧的 `seq` 一起构成会话身份，见 reports/23-session-lifecycle.md。
         */
        private val SESSION_SEQ = AtomicInteger(0)
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

    // ======================= t53：会话标识与就绪闸门 =======================

    /**
     * 本会话的递增编号（形如 `s1`/`s2`）。
     *
     * 多会话交织定因用：`session` 字段会出现在 `pc_starting`/`pc_created`/`pc_local_tracks`/
     * `offer_create`/`answer_create`/`ice_candidate_local`/`pc_closed` 等关键事件上。
     */
    val sessionId: Int = SESSION_SEQ.incrementAndGet()

    /**
     * 会话内事件序号。
     *
     * 日志是异步落盘的（真机日志里同毫秒的两行确实出现过乱序），单看时间戳无法判定
     * `pc_created` 是否真的早于 `answer_create`；单调递增的 `evt` 可以。
     * 只保证单调递增（等级被过滤时会有跳跃）。
     */
    private val eventSeq = AtomicInteger(0)

    /** 会话生命周期状态机：[SessionLifecycle]（禁止 PC 复用 + offer/answer 就绪闸门）。 */
    private val lifecycle = SessionLifecycle()

    /**
     * 就绪闸门锁。
     *
     * 必须让「判定不可用 ⇒ 入队」与「发布 PeerConnection ⇒ 排空暂存区」互斥，否则存在 TOCTOU：
     * 信令线程判定"未就绪"之后、写入暂存区之前，主线程可能已经排空暂存区 ⇒ 该消息永远不会被回放。
     */
    private val readyLock = Any()

    /**
     * [start] 是否正在执行。
     *
     * 用途：区分两种暂存 —— ① `start()` 执行中的暂存**必定**会被回放（无需打扰用户）；
     * ② 没有 `start()` 在跑却仍不可用时是真的卡住（必须给用户提示）。
     */
    @Volatile
    private var startInFlight = false

    /** 给既有事件字段追加 `session` + `evt`（t53，**只新增字段、不改事件名**）。 */
    private fun Map<String, String>.withKey(): Map<String, String> =
        this + mapOf("session" to "s$sessionId", "evt" to eventSeq.incrementAndGet().toString())

    /** 仅带 `session` + `evt` 的关键事件字段（t53）。 */
    private fun keyOnly(): Map<String, String> = emptyMap<String, String>().withKey()

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
        // 【t53】一次 CallSession 只能建立一个 PeerConnection：重复 start 必须**拒绝**，
        // 而不是像旧实现那样静默 `return true` —— 那会让上层以为"新会话已就绪"，
        // 实际把上一次通话遗留的 PC/轨道/编码器状态继续用下去（真机单向 0 上行的直接来源）。
        if (!lifecycle.beginStart()) {
            AppLog.e(
                TAG,
                "pc_start_rejected",
                mapOf("reason" to "already_started", "phase" to lifecycle.phase.name).withKey(),
            )
            listener.onError("会话已建立，拒绝重复创建 PeerConnection")
            return false
        }
        startInFlight = true
        try {
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
                mapOf("ice_servers" to iceSummary, "force_relay" to forceRelay.toString()).withKey(),
            )
            val config = WebRtcConfig.build(ice, forceRelay)
            val observer = PeerConnectionObserverImpl(events)
            val connection = factory.createPeerConnection(config, observer)
            if (connection == null) {
                AppLog.e(TAG, "pc_create_failed", keyOnly())
                listener.onError("创建 PeerConnection 失败")
                return false
            }

            // 【t53】★顺序即是修复本身★：先挂本地音视频轨，**最后**才发布 `peerConnection`。
            //
            // 旧实现先 `peerConnection = connection`，再 `mediaCapture.ensureStarted()`
            // （真机 `pc_starting`→`capture_started` 实测 90–190 ms），于是信令线程可以在这段
            // 窗口里拿"还没有本地视频轨"的 PC 直接 setRemoteDescription→createAnswer：
            // 真机 room U9FQHG 的 `answer_create`(17:37:06.929, 信令线程) 就是这样比
            // `pc_created`(17:37:07.031, 主线程) 早 102 ms，生成出的 answer 没有视频发送方向
            // ⇒ 该端 up_bps 恒 0、impl 恒空，且对端 down_bps 恒 0（单向无画面）。
            val localVideo = mediaCapture.ensureStarted()
            videoTrack = localVideo
            if (localVideo != null) {
                connection.addTrack(localVideo, listOf(WebRtcConfig.STREAM_ID))
            } else {
                AppLog.w(TAG, "video_track_missing", keyOnly())
            }
            audioTrack?.let { connection.addTrack(it, listOf(WebRtcConfig.STREAM_ID)) }
            // 本地轨就绪的**可证伪**证据：answer_create 之前必定出现本行且 video=true
            AppLog.i(
                TAG,
                "pc_local_tracks",
                mapOf(
                    "video" to (localVideo != null).toString(),
                    "audio" to (audioTrack != null).toString(),
                ).withKey(),
            )

            // VP9 置于首位并移除其它视频编码（§7.5：不改 SDP 文本）
            applyCodecPreferences(connection)

            statsMapper.reset()

            // 【t53】发布点：在同一把锁内"发布 PC + 置就绪"，与闸门判定/入队互斥（见 [readyLock]）。
            var published = false
            synchronized(readyLock) {
                if (!closed) {
                    peerConnection = connection
                    lifecycle.markReady()
                    published = true
                }
            }
            if (!published) {
                // start() 期间被 close()（用户挂断 / 离开通话页）：刚建的 PC 必须立即销毁，绝不泄漏
                AppLog.w(TAG, "pc_start_aborted", mapOf("reason" to "closed_during_start").withKey())
                safeRelease(connection)
                return false
            }

            startStatsLoop(connection)
            if (closed) {
                // 发布之后、对外宣布就绪之前被 close()（用户在 start() 期间挂断）：
                // 不再广播 `pc_created`/`onReady`，避免上层对已关闭会话继续发起协商。
                AppLog.w(TAG, "pc_start_aborted", mapOf("reason" to "closed_after_publish").withKey())
                return false
            }
            AppLog.i(TAG, "pc_created", mapOf("phase" to lifecycle.phase.name).withKey())
            // 【t44】把 PeerConnection 就绪前暂存的远端 offer/answer/ICE 回放进来（消除信令竞态）
            flushPendingRemote()
            listener.onReady()
            return true
        } finally {
            startInFlight = false
        }
    }

    /**
     * 作为 host 创建 offer（收到 `peerJoined` 后调用，doc/09 §3.3）。
     */
    fun createOffer() {
        // 【t53】只有"就绪"（PC 已发布 + 本地轨已挂载 + 编码器偏好已设）才允许发起协商。
        // 未就绪就发 offer，等于把"尚未挂本地轨"的会话推给对端，形态与 joiner 侧缺陷对称。
        val connection = peerConnection
        if (connection == null || !lifecycle.isReady) {
            AppLog.e(
                TAG,
                "offer_create_rejected",
                mapOf("reason" to "pc_not_ready", "phase" to lifecycle.phase.name).withKey(),
            )
            listener.onError("PeerConnection 未就绪")
            return
        }
        AppLog.i(TAG, "offer_create", keyOnly())
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
                        ).withKey(),
                    )
                    connection.setLocalDescription(localSetObserver, sdp)
                    signaling.sendOffer(sdp.description)
                    AppLog.i(TAG, "offer_sent", keyOnly())
                }

                override fun onSetSuccess() = Unit

                override fun onCreateFailure(error: String) {
                    AppLog.e(TAG, "offer_create_failed", mapOf("reason" to error).withKey())
                    listener.onError("创建 Offer 失败: $error")
                }

                override fun onSetFailure(error: String) {
                    AppLog.e(TAG, "offer_set_failed", mapOf("reason" to error).withKey())
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
        AppLog.i(TAG, "offer_received", mapOf("sdp_bytes" to sdp.length.toString()).withKey())
        // 【t53】闸门：PC **未发布**或**本地轨未挂载**时一律先暂存，绝不在这种 PC 上 createAnswer。
        val result = gate { pendingRemoteOffer = sdp }
        val connection = result.connection
        if (connection == null) {
            val reason = result.deferReason
            if (reason == null) {
                AppLog.e(TAG, "offer_dropped", mapOf("reason" to "session_closed").withKey())
                listener.onError("会话已关闭，收到 Offer 无法处理（未回 answer）")
            } else {
                AppLog.w(
                    TAG,
                    "offer_deferred",
                    mapOf(
                        "reason" to reason,
                        "start_in_flight" to result.startInFlight.toString(),
                        "sdp_bytes" to sdp.length.toString(),
                    ).withKey(),
                )
                // `start()` 执行中的暂存**必定**会被回放（见 start() 的发布点），不打扰用户；
                // 只有在没有 start() 在跑却仍不可用时（真的卡住）才提示。
                if (!result.startInFlight) {
                    listener.onError("会话尚未就绪，Offer 已暂存（就绪后会回 answer）")
                }
            }
            return
        }
        connection.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(sdp0: SessionDescription) = Unit

                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    startConnectivityWatchdog(connection)
                    AppLog.i(TAG, "answer_create", keyOnly())
                    connection.createAnswer(
                        object : SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription) {
                                AppLog.i(
                                    TAG,
                                    "answer_created",
                                    mapOf(
                                        "sdp_bytes" to description.description.length.toString(),
                                        "candidates" to IceCandidateInfo.summarizeSdpCandidates(description.description),
                                    ).withKey(),
                                )
                                connection.setLocalDescription(localSetObserver, description)
                                signaling.sendAnswer(description.description)
                                AppLog.i(TAG, "answer_sent", keyOnly())
                            }

                            override fun onSetSuccess() = Unit

                            override fun onCreateFailure(error: String) {
                                AppLog.e(TAG, "answer_create_failed", mapOf("reason" to error).withKey())
                                listener.onError("创建 Answer 失败: $error")
                            }

                            override fun onSetFailure(error: String) {
                                AppLog.e(TAG, "answer_set_failed", mapOf("reason" to error).withKey())
                                listener.onError("设置 Answer 失败: $error")
                            }
                        },
                        offerAnswerConstraints(),
                    )
                }

                override fun onCreateFailure(error: String) = Unit

                override fun onSetFailure(error: String) {
                    AppLog.e(TAG, "remote_offer_set_failed", mapOf("reason" to error).withKey())
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
            ).withKey(),
        )
        // 【t53】与 offer 同一把闸门：未就绪（PC 未发布 / 本地轨未挂载）时一律先暂存。
        val result = gate { pendingRemoteAnswer = sdp }
        val connection = result.connection
        if (connection == null) {
            val reason = result.deferReason
            if (reason == null) {
                AppLog.e(TAG, "answer_dropped", mapOf("reason" to "session_closed").withKey())
                listener.onError("会话已关闭，收到 Answer 无法处理")
            } else {
                AppLog.w(
                    TAG,
                    "answer_deferred",
                    mapOf("reason" to reason, "start_in_flight" to result.startInFlight.toString()).withKey(),
                )
                if (!result.startInFlight) {
                    listener.onError("会话尚未就绪，Answer 已暂存（就绪后自动应用）")
                }
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
            AppLog.w(TAG, "ice_dropped", mapOf("reason" to "no_mid_and_no_index").withKey())
            return
        }
        // 【t44】对端候选的类型/地址/端口必须落盘：这是判断"对端把什么候选送到了本端"的唯一证据
        val info = IceCandidateInfo.parse(candidate)
        candidateCounter.addRemote(info)
        // 【t53】候选同样走闸门：PC 未发布时暂存（并在同一把锁内记录队列长度）。
        var queued = 0
        val result = gate {
            synchronized(pendingRemoteCandidates) {
                pendingRemoteCandidates.add(RemoteCandidate(candidate, sdpMid, sdpMLineIndex))
                queued = pendingRemoteCandidates.size
            }
        }
        val connection = result.connection
        if (connection == null) {
            if (result.deferReason == null) {
                AppLog.w(
                    TAG,
                    "ice_dropped",
                    mapOf("reason" to "session_closed", "remote" to info.summary()).withKey(),
                )
            } else {
                AppLog.w(
                    TAG,
                    "ice_deferred",
                    mapOf(
                        "remote" to info.summary(),
                        "queued" to queued.toString(),
                        "start_in_flight" to result.startInFlight.toString(),
                    ).withKey(),
                )
            }
            return
        }
        AppLog.i(TAG, "ice_candidate_remote", mapOf("remote" to info.summary()).withKey())
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

    /**
     * 关闭会话（`hangup`/离开通话页调用；不销毁 EglBase/工厂，§7.1）。
     *
     * 【t53】会话释放纪律：
     *  1. 在闸门锁内一次性"摘除"PC/统计循环/看门狗并置 [SessionLifecycle] 为 CLOSED ——
     *     与 [start] 的发布点互斥，避免 start() 与 close() 并发时留下仍在运行的采样线程；
     *  2. 关闭后任何远端消息一律 [SessionGate.DROP]（不暂存、不回放），不残留"半死会话"；
     *  3. 清空暂存区，防止旧会话的 offer/answer/候选被新会话误用。
     */
    fun close() {
        val teardown = synchronized(readyLock) {
            if (closed) return
            closed = true
            lifecycle.markClosed()
            val pending = Teardown(statsTimer, connectivityWatchdog, peerConnection)
            statsTimer = null
            connectivityWatchdog = null
            peerConnection = null
            videoTrack = null
            remoteVideoTrack = null
            pendingRemoteOffer = null
            pendingRemoteAnswer = null
            synchronized(pendingRemoteCandidates) { pendingRemoteCandidates.clear() }
            pending
        }
        teardown.stats?.shutdownNow()
        teardown.watchdog?.shutdownNow()
        val connection = teardown.connection
        // 关前先读出信令状态：这是"旧会话确实被拆掉"的可核对证据（配合 `pc_closed`）
        val stateBefore = try {
            connection?.signalingState()?.name ?: "-"
        } catch (t: Throwable) {
            "unknown"
        }
        if (connection != null) {
            safeRelease(connection)
        }
        AppLog.i(
            TAG,
            "pc_closed",
            mapOf(
                "signaling_before" to stateBefore,
                "stats_loop" to "stopped",
                "watchdog" to "stopped",
                "phase" to lifecycle.phase.name,
            ).withKey(),
        )
    }

    // ============================ 内部实现 ============================

    // ======================= t53：闸门与资源释放 =======================

    /**
     * 待释放资源。
     *
     * 在闸门锁内一次性摘除，锁外再真正关闭：关闭 libwebrtc 对象可能阻塞，
     * 不宜在持锁期间做，否则会把信令线程卡在闸门上。
     */
    private class Teardown(
        val stats: ScheduledExecutorService?,
        val watchdog: ScheduledExecutorService?,
        val connection: PeerConnection?,
    )

    /**
     * 闸门判定结果。
     *
     * @param connection 可立即使用的 PeerConnection；null 表示本次没有立即处理。
     * @param deferReason 非 null 表示"已暂存"，值为 `pc_not_ready`（就绪后会回放）。
     * @param startInFlight 暂存时 [start] 是否正在执行（执行中 ⇒ 必定会被回放，无需打扰用户）。
     */
    private class GateResult(
        val connection: PeerConnection?,
        val deferReason: String?,
        val startInFlight: Boolean,
    )

    /**
     * 远端消息闸门：判定"能否立即处理"，不能则在**同一把锁内**暂存。
     *
     * 这是 t53 的核心不变量：`pc_created`（发布点）之前到达的 offer/answer/候选**一律暂存**，
     * 从而保证 `answer_create` 永远发生在"本地视频轨已挂载的 PC"上。
     *
     * @param store 判定为不可立即处理时的暂存动作（在锁内执行）。
     */
    private fun gate(store: () -> Unit): GateResult = synchronized(readyLock) {
        when (lifecycle.admit()) {
            SessionGate.PROCEED -> {
                val connection = peerConnection
                if (connection != null) {
                    GateResult(connection, null, false)
                } else {
                    // 不变量被破坏（READY 却没有 PC）时的保守兜底：暂存而非崩溃
                    store()
                    GateResult(null, "pc_not_ready", startInFlight)
                }
            }

            SessionGate.DROP -> GateResult(null, null, false)

            SessionGate.DEFER -> {
                store()
                GateResult(null, "pc_not_ready", startInFlight)
            }
        }
    }

    /** 关闭并释放 PeerConnection（含异常兜底，绝不抛出）。 */
    private fun safeRelease(connection: PeerConnection) {
        try {
            connection.close()
        } catch (t: Throwable) {
            AppLog.w(TAG, "pc_close_failed", mapOf("reason" to (t.message ?: "-")).withKey())
        }
        try {
            connection.dispose()
        } catch (t: Throwable) {
            AppLog.w(TAG, "pc_dispose_failed", mapOf("reason" to (t.message ?: "-")).withKey())
        }
    }

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
                ).withKey(),
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
        // 【t53】必须在闸门锁内"读取 + 清空"暂存区：与 gate{} 的"判定 + 入队"互斥，
        // 否则信令线程可能在排空之后才写入（TOCTOU），该消息永远不会被回放。
        var offer: String? = null
        var answer: String? = null
        var candidates: List<RemoteCandidate> = emptyList()
        synchronized(readyLock) {
            offer = pendingRemoteOffer
            answer = pendingRemoteAnswer
            candidates = synchronized(pendingRemoteCandidates) {
                val copy = ArrayList(pendingRemoteCandidates)
                pendingRemoteCandidates.clear()
                copy
            }
            pendingRemoteOffer = null
            pendingRemoteAnswer = null
        }
        if (offer != null) {
            AppLog.i(TAG, "offer_replayed", mapOf("sdp_bytes" to offer.length.toString()).withKey())
            onRemoteOffer(offer)
        }
        if (answer != null) {
            AppLog.i(TAG, "answer_replayed", mapOf("sdp_bytes" to answer.length.toString()).withKey())
            onRemoteAnswer(answer)
        }
        if (candidates.isNotEmpty()) {
            AppLog.i(TAG, "ice_replayed", mapOf("count" to candidates.size.toString()).withKey())
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
            ).withKey(),
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
        // 【t53】同上：注册与 close() 互斥
        synchronized(readyLock) {
            if (closed || connectivityWatchdog != null) {
                timer.shutdownNow()
                return
            }
            connectivityWatchdog = timer
            connectivityWatchdogStartMs = System.currentTimeMillis()
        }
        AppLog.i(TAG, "ice_watchdog_started", mapOf("timeout_ms" to ICE_WARN_MS.toString()).withKey())
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
        // 【t53】注册与 close() 互斥：hangup 可能由信令线程触发，与主线程的 start() 并发；
        // 若已关闭就绝不注册，否则会留下一条永不停歇的采样线程（旧会话残留）。
        synchronized(readyLock) {
            if (closed) {
                timer.shutdownNow()
                return
            }
            statsTimer = timer
        }
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

// ============================================================================
// 【t53】会话生命周期状态机（纯 Kotlin，无 Android / org.webrtc 依赖）
// ----------------------------------------------------------------------------
// 为什么要单独抽出来：
//   1. 真机缺陷（单向 0 上行）的根因是**时序**，必须能被纯 JVM 单测直接钉死；
//   2. `CallSession` 依赖 Android 与 org.webrtc，无法在 JVM 单测里实例化；
//   3. 把"一次会话只建一个 PeerConnection""未就绪的消息必须暂存"
//      从散落的 `if` 升级为**可断言的不变量**。
// 单测见 app/src/test/kotlin/com/example/webrtcdemo/webrtc/SessionLifecycleTest.kt。
// ============================================================================

/** 会话阶段（t53）。 */
enum class SessionPhase {
    /** 尚未开始建立 PeerConnection。 */
    NEW,

    /** `start()` 执行中：PC 已创建，但**本地音视频轨尚未挂载** —— 绝不可在此阶段应答 offer。 */
    STARTING,

    /** PC 已发布且本地轨已挂载（`pc_created` 之后）：可处理 offer/answer/候选。 */
    READY,

    /** 已关闭：任何远端消息一律丢弃。 */
    CLOSED,
}

/** 远端消息的闸门判定结果（t53）。 */
enum class SessionGate {
    /** 立即可处理（PC 已发布、本地轨已挂载）。 */
    PROCEED,

    /** 尚不可处理：必须暂存，待就绪后回放。 */
    DEFER,

    /** 会话已关闭：丢弃（不暂存、不回放）。 */
    DROP,
}

/**
 * 会话生命周期状态机（t53）。
 *
 * 不变量（每条都有对应单测）：
 *  1. [beginStart] 只成功一次 —— 同一次会话**绝不**复用已有的 PeerConnection；
 *  2. [markReady] 之前 [admit] 一律 [SessionGate.DEFER]，从而保证
 *     `pc_created`（发布点）早于 `answer_create`；
 *  3. [markReady] 之后 [admit] 为 [SessionGate.PROCEED]；[markClosed] 之后一律 [SessionGate.DROP]；
 *  4. [markClosed] 之后的 [markReady] 不复活（`start()` 期间被挂断的场景）。
 */
class SessionLifecycle {

    /** 当前阶段。 */
    var phase: SessionPhase = SessionPhase.NEW
        private set

    /** 本会话是否已经建立过 PeerConnection（用于拒绝重复 `start()`）。 */
    var peerConnectionCreated: Boolean = false
        private set

    /**
     * 开始建立 PeerConnection。
     *
     * @return true 表示可以开始建；false 表示**已经建过**或**已关闭**，
     *         调用方必须放弃（绝不复用旧 PC —— 真机单向 0 上行正是"跨会话复用"的形态）。
     */
    fun beginStart(): Boolean {
        if (phase == SessionPhase.CLOSED || peerConnectionCreated) return false
        peerConnectionCreated = true
        phase = SessionPhase.STARTING
        return true
    }

    /**
     * 本地音视频轨已挂载、PC 已发布 ⇒ 进入就绪。
     *
     * @return true 表示确实转为就绪；false 表示当前阶段不允许（已关闭 / 未开始）。
     */
    fun markReady(): Boolean {
        if (phase != SessionPhase.STARTING) return false
        phase = SessionPhase.READY
        return true
    }

    /**
     * 关闭会话（幂等）。
     *
     * @return true 表示本次真的执行了关闭。
     */
    fun markClosed(): Boolean {
        if (phase == SessionPhase.CLOSED) return false
        phase = SessionPhase.CLOSED
        return true
    }

    /** 远端 offer/answer/候选的闸门判定。 */
    fun admit(): SessionGate = when (phase) {
        SessionPhase.READY -> SessionGate.PROCEED
        SessionPhase.CLOSED -> SessionGate.DROP
        SessionPhase.NEW, SessionPhase.STARTING -> SessionGate.DEFER
    }

    /** 是否已就绪（= `pc_created` 已落盘、可安全应答）。 */
    val isReady: Boolean
        get() = phase == SessionPhase.READY
}
