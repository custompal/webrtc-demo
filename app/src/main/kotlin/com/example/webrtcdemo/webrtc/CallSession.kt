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

        /**
         * 看门狗第一档（t60/A1①：由 15 s 放宽到 **30 s**）。
         *
         * 依据 t58 §6.3 A1：`4G↔WiFi + 走中继` 场景下中继候选实测可能 >15 s 才到位
         * （真机 dj-a 先 `local=-` 约 13 s 才拿到 relay 候选），15 s 固定窗口会把
         * "正在正常收集"误判为"连不上"。
         */
        const val ICE_WARN_MS = 30_000L

        /** 看门狗第二档（t60/A1①：由 30 s 放宽到 **45 s**）：确认为失败并上报 UI。 */
        const val ICE_FAIL_MS = 45_000L

        /** 【t60/A1③】超时后先做 ICE restart 的尝试上限（而不是立刻判失败）。 */
        const val MAX_ICE_RESTARTS = 2

        /** 【t60/A1②】看门狗在"TURN 已配置但中继候选还没 gather 到"时的顺延档（每次 +15 s）。 */
        const val ICE_DEFER_STEP_MS = 15_000L

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

    // ======================= t60：中继健壮性（A2/A3/A4/A7） =======================

    /** 本会话是否配置了 TURN（决定"未 gather 到中继候选"是否算异常，A7）。 */
    @Volatile
    private var turnConfigured = false

    /** 本会话使用的 ICE 配置（ICE restart 时用 `setConfiguration` 重新应用以触发重新 gathering）。 */
    private var iceConfig: IceServerConfig? = null

    /** `start()` 时的 forceRelay 开关（重新应用配置时保持一致的强制中继语义）。 */
    @Volatile
    private var forceRelayConfig = false

    /** `restartIce` 已尝试次数（A1③/A4 的上限控制）。 */
    @Volatile
    private var iceRestartAttempts = 0

    /** 收到的 TURN 相关候选错误计数（A4：`701 TURN_allocate_request_timed_out` 等）。 */
    @Volatile
    private var turnErrorCount = 0

    /** 被过滤掉的 loopback **本端**候选数（A2）。 */
    @Volatile
    private var filteredLocalLoopback = 0

    /** 被过滤掉的 loopback **对端**候选数（A3）。 */
    @Volatile
    private var filteredRemoteLoopback = 0

    /** ICE 收集完成时刻（A1②：中继场景下超时锚点后移的依据）。 */
    @Volatile
    private var gatheringCompleteAtMs = 0L

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

    // ===================== t83：远端候选计数的去重 / SDP 分账 / 日志限频 =====================

    /** 【t83①】已**计入** `candidateCounter` 的远端候选条数（回放不重复计入）。 */
    @Volatile
    private var remoteCandidateTallied = 0

    /** 【t83③】已落盘 `ice_candidate_remote` 日志的次数（限频用；总数另见 [remoteCandidateTallied]）。 */
    @Volatile
    private var remoteCandidateLogged = 0

    /**
     * 【t83②】**SDP 内**携带的远端候选条数（`a=candidate:` 行数累计）。
     *
     * 与 `candidateCounter.remoteSummary()`（只统计 trickle 进来的候选）分开记账：
     * 二者都为 0 才是"确实没有对端候选"，只有 trickled 为 0 而 SDP 有 → 不再是假阴性。
     */
    @Volatile
    private var sdpRemoteCandidateCount = 0

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
                    // 【t65/A5】TCP 回退默认开启：让"本次到底带没带 TCP 回退"在会话开始就一行可读
                    if (WebRtcConfig.turnTcpFallbackActive(ice)) append("+tcp")
                }
                if (isEmpty()) append("-")
            }
            AppLog.i(
                TAG,
                "pc_starting",
                mapOf(
                    "ice_servers" to iceSummary,
                    "force_relay" to forceRelay.toString(),
                    // 【t65/A5】新增字段（既有 `ice_servers` 字段名与形态不变）
                    "turn_tcp" to WebRtcConfig.turnTcpFallbackActive(ice).toString(),
                ).withKey(),
            )
            val config = WebRtcConfig.build(ice, forceRelay)
            // 【t60/A4/A7】配了 TURN ⇒ 打开**持续 gathering**：中继候选实测可能晚到
            // （真机 dj-a 约 13 s 才拿到 relay），GATHER_ONCE 在首次收集结束后不会再补，
            // 网络切换/中继恢复后也拿不到新候选；GATHER_CONTINUALLY 允许后续继续补候选，
            // 并在 [restartIce] 里通过 `setConfiguration` 再次应用以**主动触发重新 gathering**。
            iceConfig = ice
            forceRelayConfig = forceRelay
            turnConfigured = ice?.turnUrl?.isNotBlank() == true
            if (turnConfigured) {
                config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
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
        // 【t83②】SDP 内候选单独记账（覆盖 ICE restart 后对端重发的新 SDP）
        accountSdpCandidates(sdp, "offer")
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
        // 【t83②】SDP 内候选单独记账（覆盖 ICE restart 后对端重发的新 SDP）
        accountSdpCandidates(sdp, "answer")
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
    fun onRemoteIceCandidate(
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?,
        viaReplay: Boolean = false,
    ) {
        val mid = sdpMid ?: ""
        // §8.2：sdpMid 与 sdpMLineIndex 至少一个有效
        if (mid.isEmpty() && sdpMLineIndex == null) {
            AppLog.w(TAG, "ice_dropped", mapOf("reason" to "no_mid_and_no_index").withKey())
            return
        }
        // 【t44】对端候选的类型/地址/端口必须落盘：这是判断"对端把什么候选送到了本端"的唯一证据
        val info = IceCandidateInfo.parse(candidate)
        // 【t60/A3】对端的**回环候选**同样丢弃：真机对端会把自己机器的 `127.0.0.1`/`::1` host 候选
        // 发过来，灌进 libwebrtc 后本端会为其建立权限并尝试连接 → coturn `403 Forbidden IP`（t58）。
        // 必须在**解析后、计数/暂存之前**过滤，避免它进入 t53 的暂存队列被回放。
        if (LoopbackCandidates.isLoopback(info.address)) {
            filteredRemoteLoopback++
            AppLog.w(
                TAG,
                "ice_candidate_filtered",
                mapOf(
                    "direction" to "remote",
                    "reason" to "loopback",
                    "remote" to info.summary(),
                    "n" to filteredRemoteLoopback.toString(),
                ).withKey(),
            )
            return
        }
        // 【t83① 去重计数】只有"首次从信令进入"才计数；`viaReplay=true`（[flushPendingRemote] 回放）
        // 表示该候选此前已计过一次 ⇒ 跳过，避免同一候选被 `addRemote` 计两次。判据是纯函数便于单测。
        if (RemoteCandidateAccounting.shouldCount(viaReplay)) {
            candidateCounter.addRemote(info)
            remoteCandidateTallied++
        }
        logRemoteCandidateIfNeeded(info, viaReplay)
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
        // 【t83③】逐条应用日志已在上方的 `logRemoteCandidateIfNeeded` 里限频（首 3 条 + 每 10 条），
        // 这里不再无条件落盘；总数仍可通过 `ice_candidate_remote_total`（每次变化落一条）核对。
        connection.addIceCandidate(IceCandidate(mid, sdpMLineIndex ?: 0, candidate))
    }

    /**
     * 【t83②】SDP 内候选的独立记账 + 汇总诊断。
     *
     * 背景：`candidateCounter` 只统计**trickle** 进来的候选，SDP 内携带的候选从来不进计数器 ⇒
     * 若对端把候选只写在 SDP 里（不 trickle 或被抑制），真机横幅会显示 `对端候选 -`，
     * 而链路其实可用 —— 且 `-` 无法区分"确实没有候选"与"候选只在 SDP 里"。这里把两者分开记账。
     */
    private fun accountSdpCandidates(sdp: String, trigger: String) {
        val counted = RemoteCandidateAccounting.countSdpCandidates(sdp)
        if (counted > 0) {
            sdpRemoteCandidateCount += counted
        }
        logRemoteCandidateTotal(trigger)
    }

    /**
     * 【t83③】远端候选应用日志的**限频**落盘（首 3 条 + 每 10 条），避免大候选量时刷屏。
     *
     * 限频参数在 [RemoteCandidateAccounting.shouldLogRemoteCandidate]；限频跳过的条目不会丢失总数
     * ——计数每次变化都会由 [logRemoteCandidateTotal] 落一条 `ice_candidate_remote_total`。
     *
     * @param viaReplay 回放路径（日志里显式标注，便于区分"首次进入"与"回放应用"）。
     */
    private fun logRemoteCandidateIfNeeded(info: IceCandidateInfo, viaReplay: Boolean) {
        val total = remoteCandidateTallied
        if (!RemoteCandidateAccounting.shouldLogRemoteCandidate(remoteCandidateLogged + 1)) return
        remoteCandidateLogged++
        AppLog.i(
            TAG,
            "ice_candidate_remote",
            mapOf(
                "remote" to info.summary(),
                "total" to total.toString(),
                "via_replay" to viaReplay.toString(),
                "sdp_remote" to sdpRemoteCandidateCount.toString(),
            ).withKey(),
        )
    }

    /**
     * 【t83②】远端候选总账：把"trickled 计数"与"SDP 内计数"分开落盘，消除 `对端候选 -` 的歧义
     * （`-` 可能是"确实没有候选"，也可能只是"候选只写在 SDP 里没 trickle"）。
     */
    private fun logRemoteCandidateTotal(trigger: String) {
        val tallied = candidateCounter.remoteSummary()
        AppLog.i(
            TAG,
            "ice_candidate_remote_total trickled=${tallied} sdp=${sdpRemoteCandidateCount} " +
                "summary=${RemoteCandidateAccounting.mergedSummary(tallied, sdpRemoteCandidateCount)}",
            mapOf("trigger" to trigger).withKey(),
        )
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
            // 【t60/A2】回环候选**不发送**：真机两台设备都在广播 `127.0.0.1`/`::1` 的 host 候选
            // （t58 §2.1 合计 63+176+177+4 条），对端把回环地址当 peer 去建权限会被 coturn
            // 以 `403 Forbidden IP` 拒绝（t58 §2.2 探针矩阵），既浪费信令又污染对端诊断。
            if (LoopbackCandidates.isLoopback(info.address)) {
                filteredLocalLoopback++
                AppLog.w(
                    TAG,
                    "ice_candidate_filtered",
                    mapOf(
                        "direction" to "local",
                        "reason" to "loopback",
                        "local" to info.summary(),
                        "n" to filteredLocalLoopback.toString(),
                    ).withKey(),
                )
                return
            }
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
            // 【t60/A1②】**首个 relay 候选到位**即刻重新校准看门狗（不等下一档定时器）：
            // 契约要求"首个 relay 候选到位或 iceGatheringState==COMPLETE 后再起算"，
            // 这里在候选到达瞬间补一次检查，使"等到 relay 再判"真正生效。
            if (info.type == IceCandidateInfo.TYPE_RELAY) {
                rearmWatchdogOnRelay(info.summary())
            }
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
                // 【t60/A1②】记录收集完成时刻：中继场景下它是看门狗超时的**锚点**
                if (gatheringCompleteAtMs == 0L) {
                    gatheringCompleteAtMs = System.currentTimeMillis()
                }
                // 新增事件名（t44，已在 reports/15-connection-defect.md 登记）：
                // 收集结束时一次性给出**按类型的候选计数**，这是"到底 gather 到什么"的直接证据。
                AppLog.i(
                    TAG,
                    "ice_gathering_complete",
                    mapOf(
                        "local" to candidateCounter.localSummary(),
                        "relay" to candidateCounter.localRelayCount().toString(),
                        "turn_configured" to turnConfigured.toString(),
                        // 【t65/A5】本次是否带 TCP 回退（判"local_relay=0 时是否已尝试 TCP 路径"）
                        "turn_tcp" to WebRtcConfig.turnTcpFallbackActive(iceConfig).toString(),
                        "turn_errors" to turnErrorCount.toString(),
                        "filtered_loopback" to (filteredLocalLoopback + filteredRemoteLoopback).toString(),
                    ),
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
            // 【t60/A4】TURN 相关错误（真机：`code=701 TURN_allocate_request_timed_out`，与 STUN 同端口双超时）
            // 旧实现只打一行日志、不重试，最终直接 FAILED。现在：计次 + 主动**重新 gathering**。
            val url = event.url ?: ""
            if (url.startsWith("turn", ignoreCase = true) || url.startsWith("turns", ignoreCase = true)) {
                turnErrorCount++
                AppLog.w(
                    TAG,
                    "ice_turn_error",
                    mapOf(
                        "code" to event.errorCode.toString(),
                        "text" to (event.errorText ?: "-"),
                        "url" to url,
                        "count" to turnErrorCount.toString(),
                        "local_relay" to candidateCounter.localRelayCount().toString(),
                    ).withKey(),
                )
                if (candidateCounter.localRelayCount() == 0) {
                    // 中继候选一个都没拿到 ⇒ 中继路径不可用，立刻尝试重新 gathering（不等看门狗超时）
                    restartIce("turn_error_${event.errorCode}")
                }
            }
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
                // 【t83①】回放标记：同一候选此前已计入 `candidateCounter`，回放不得重复计数
                onRemoteIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex, viaReplay = true)
            }
            logRemoteCandidateTotal("replay")
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
     * ICE/DTLS 连通性看门狗（t44；t60 按 t58 §6.3 A1 重新标定口径）。
     *
     * 目的：把"停在正在连接会议"从**不可诊断**变成**一次复测即可定因**。
     *
     * 【t60 口径变化】
     *  * 第一档由 15 s 放宽到 **30 s**、第二档由 30 s 放宽到 **45 s**（中继 gather 实测可 >15 s）；
     *  * 若"配了 TURN 但还没 gather 到中继候选且收集未完成" ⇒ **顺延一档**（并主动重新 gathering），
     *    而不是判失败（A1②）；
     *  * 第二档先尝试 **ICE restart**，用尽重启次数才上报失败（A1③）。
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
        AppLog.i(
            TAG,
            "ice_watchdog_started",
            mapOf(
                "timeout_ms" to ICE_WARN_MS.toString(),
                "fail_ms" to ICE_FAIL_MS.toString(),
                "turn_configured" to turnConfigured.toString(),
            ).withKey(),
        )
        timer.schedule({ checkConnectivity(connection, ICE_WARN_MS) }, ICE_WARN_MS, TimeUnit.MILLISECONDS)
        timer.schedule({ checkConnectivity(connection, ICE_FAIL_MS) }, ICE_FAIL_MS, TimeUnit.MILLISECONDS)
    }

    /** 连通性仍未建立时的诊断/上报；[afterMs] 区分 WARN（30 s）与 ERROR（45 s）两档。 */
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
        // 【t80 档位/时长一致性守卫】`afterMs` 的语义是**档位**（ICE_WARN_MS / ICE_FAIL_MS，或顺延后的档位），
        // 而调用点曾把"**已用时长**"当它传入（rearmWatchdogOnRelay）——基数未初始化时该时长是 epoch 毫秒。
        // 这里按看门狗基数复核**真实耗时**：档位已到失败档、但真实耗时不足（或基数无效）⇒ 一律跳过，
        // **不上报失败、不回调 onError**（真机症状：`ice_timeout after_ms=1789529664872 ice_state=NEW`，
        // 同一秒就 `ice_watchdog_ok elapsed_ms=306` 并 `state=CONNECTED`）。
        val watchdogStartedAt = connectivityWatchdogStartMs
        val nowForTier = System.currentTimeMillis()
        if (IceWatchdogPolicy.isStaleTier(afterMs, watchdogStartedAt, nowForTier, ICE_FAIL_MS)) {
            AppLog.w(
                TAG,
                "ice_watchdog_stale_tier",
                mapOf(
                    "tier_ms" to afterMs.toString(),
                    "elapsed_ms" to IceWatchdogPolicy.realElapsedMs(watchdogStartedAt, nowForTier).toString(),
                    "action" to "skip",
                    "watchdog_active" to (connectivityWatchdog != null).toString(),
                    "ice_state" to (iceState?.name ?: "-"),
                ).withKey(),
            )
            return
        }
        val relayCount = candidateCounter.localRelayCount()
        val relayMissing = turnConfigured && relayCount == 0
        // 【t60/A1②】中继场景下"还没收集完"就不该判失败：等 relay 候选到位或收集完成再起算。
        // 真机 dj-a 先 `local=-` 约 13 s 才拿到 relay 候选，15 s 固定窗口必然误报。
        if (afterMs < ICE_FAIL_MS && relayMissing && gatheringCompleteAtMs == 0L && !closed) {
            val deferred = afterMs + ICE_DEFER_STEP_MS
            AppLog.w(
                TAG,
                "ice_watchdog_rearmed",
                mapOf(
                    "after_ms" to afterMs.toString(),
                    "next_ms" to deferred.toString(),
                    "reason" to "awaiting_relay",
                    "turn_configured" to turnConfigured.toString(),
                ).withKey(),
            )
            // 顺延一档再查（并主动触发一次重新 gathering，见 A4/A7）
            restartIce("watchdog_defer")
            val timer = connectivityWatchdog
            if (timer != null) {
                timer.schedule({ checkConnectivity(connection, deferred) }, ICE_DEFER_STEP_MS, TimeUnit.MILLISECONDS)
            }
            return
        }
        val fields = mapOf(
            "after_ms" to afterMs.toString(),
            "ice_state" to (iceState?.name ?: "-"),
            "transport" to (transportState?.name ?: "-"),
            "local_candidates" to candidateCounter.localSummary(),
            // 【t83②】对端口径拆成三项：trickle 摘要 / SDP 内条数 / 合并摘要（消除 `-` 的歧义）
            "remote_candidates" to RemoteCandidateAccounting.mergedSummary(
                candidateCounter.remoteSummary(),
                sdpRemoteCandidateCount,
            ),
            "remote_trickled" to candidateCounter.remoteSummary(),
            "remote_sdp" to sdpRemoteCandidateCount.toString(),
            "local_relay" to relayCount.toString(),
            "turn_configured" to turnConfigured.toString(),
            "turn_errors" to turnErrorCount.toString(),
            "relay_missing" to relayMissing.toString(),
            "filtered_loopback" to (filteredLocalLoopback + filteredRemoteLoopback).toString(),
            "ice_restarts" to iceRestartAttempts.toString(),
            "remote_desc_set" to remoteDescriptionSet.toString(),
        )
        if (afterMs >= ICE_FAIL_MS) {
            // 【t60/A1③】先尝试 ICE restart（而不是直接 FAILED）；重启次数用尽才上报失败。
            if (relayMissing && restartIce("ice_timeout")) {
                AppLog.w(TAG, "ice_timeout_restarting", fields)
                val timer = connectivityWatchdog
                if (timer != null && !closed) {
                    val deferred = afterMs + ICE_DEFER_STEP_MS
                    timer.schedule({ checkConnectivity(connection, deferred) }, ICE_DEFER_STEP_MS, TimeUnit.MILLISECONDS)
                }
                return
            }
            AppLog.e(TAG, "ice_timeout", fields)
            // 【t83②】横幅里的"对端候选"改用**合并口径**（trickle 摘要 + SDP 内条数）：
            // 两侧都为空时显式写 `-（trickled 与 SDP 内均无）`，不再让 `-` 同时代表两种含义。
            val remoteMerged = RemoteCandidateAccounting.mergedSummary(
                candidateCounter.remoteSummary(),
                sdpRemoteCandidateCount,
            )
            listener.onError(
                if (relayMissing) {
                    // 【t60/A7】显式区分"中继不可用"与"单纯没配上候选对"，复测时可直接定因
                    "未获取到中继候选（TURN ${if (turnErrorCount > 0) "报错 $turnErrorCount 次" else "无响应"}）——" +
                        "本端候选 ${candidateCounter.localSummary()}；对端候选 $remoteMerged。" +
                        "可在通话页点「重试」重建中继，或在诊断页打开「强制中继」后重试，并立即导出日志"
                } else {
                    "ICE 未连通（本端候选 ${candidateCounter.localSummary()}；" +
                        "对端候选 $remoteMerged）。" +
                        "可在诊断页打开「强制中继」后重试，并立即导出日志"
                },
            )
        } else {
            AppLog.w(TAG, "ice_not_connected", fields)
            if (relayMissing) {
                // 中继一个都没到：第一档就主动重新 gathering（A7）
                restartIce("relay_missing_warn")
            }
        }
    }

    /**
     * 主动 ICE restart + 重新 gathering（t60/A1③、A4、A7）。
     *
     * 做两件事：
     *  1. `PeerConnection.restartIce()`：标记下一次 offer 带 `ice-restart`（由上层决定何时重发 offer）；
     *  2. `setConfiguration(再次应用 GATHER_CONTINUALLY)`：**立即触发重新收集**中继候选 —— 这是
     *     "有 TURN 配置但 `local_relay==0`"时唯一能在 ICE 层自救的动作（不重建 PeerConnection）。
     *
     * @param reason `turn_error_701` / `watchdog_defer` / `relay_missing_warn` / `ice_timeout` / `no_relay_candidate`。
     * @return true 表示本次确实发起了 restart（调用方可据此顺延判定，而不是直接判失败）。
     */
    fun restartIce(reason: String): Boolean {
        if (closed) return false
        if (iceRestartAttempts >= MAX_ICE_RESTARTS) {
            AppLog.w(
                TAG,
                "ice_restart_exhausted",
                mapOf("reason" to reason, "attempts" to iceRestartAttempts.toString()).withKey(),
            )
            return false
        }
        val connection = peerConnection ?: return false
        iceRestartAttempts++
        var accepted = false
        try {
            connection.restartIce()
            accepted = true
        } catch (t: Throwable) {
            AppLog.w(TAG, "ice_restart_failed", mapOf("reason" to (t.message ?: "-")).withKey())
        }
        // 再次应用配置以触发重新 gathering（配了 TURN 才需要）
        if (turnConfigured) {
            try {
                val config = WebRtcConfig.build(iceConfig, forceRelayConfig).apply {
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                }
                connection.setConfiguration(config)
            } catch (t: Throwable) {
                AppLog.w(TAG, "ice_regather_failed", mapOf("reason" to (t.message ?: "-")).withKey())
            }
        }
        AppLog.i(
            TAG,
            "ice_restart_requested",
            mapOf(
                "reason" to reason,
                "attempt" to iceRestartAttempts.toString(),
                "accepted" to accepted.toString(),
                "local_relay" to candidateCounter.localRelayCount().toString(),
                "turn_errors" to turnErrorCount.toString(),
            ).withKey(),
        )
        return accepted
    }

    /** 是否"配了 TURN 但本会话一个中继候选都没有"（t60/A7：UI 侧显式子原因的依据）。 */
    fun relayMissing(): Boolean = turnConfigured && candidateCounter.localRelayCount() == 0
    /** 本会话已 gather 到的中继候选数（诊断用）。 */
    fun localRelayCandidateCount(): Int = candidateCounter.localRelayCount()

    /** TURN 相关候选错误计数（诊断用）。 */
    fun turnErrorCount(): Int = turnErrorCount

    /** 已过滤的回环候选数（A2+A3，诊断用）。 */
    fun filteredLoopbackCount(): Int = filteredLocalLoopback + filteredRemoteLoopback

    /**
     * 首个 relay 候选到位 ⇒ 立刻重新校准看门狗（t60/A1②）。
     *
     * 契约口径："首个 relay 候选到位或 `iceGatheringState==COMPLETE` 后**再起算**"。
     * 这里在候选到达瞬间补一次检查（以看门狗启动起的**实际耗时**为 `afterMs`），
     * 使"等 relay 再判"不必依赖下一档定时器；同时落 `ice_watchdog_rearmed` 供复测自证。
     *
     * @param candidateSummary 触发本次重新校准的候选摘要（诊断用）。
     */
    private fun rearmWatchdogOnRelay(candidateSummary: String) {
        if (closed) return
        val connection = peerConnection ?: return
        // 【t80 基数守卫】relay 候选常**早于** `startConnectivityWatchdog()` 到达（真机 evt=16/18 早于 evt=22），
        // 或上一世代 stop 后基数未复位 ⇒ 此时 `now - connectivityWatchdogStartMs` 等于 **epoch 毫秒**。
        // 旧实现把它当"档位"传给 checkConnectivity ⇒ `afterMs >= ICE_FAIL_MS` 恒真 ⇒ 在 `ice_state=NEW`、
        // 远端候选还没到时立刻误报「ICE 未连通」（真机 03:34:24.873 `ice_timeout after_ms=1789529664872`）。
        // 这里：看门狗未启动/基数无效时**只落诊断、绝不触发检查**。
        val startedAt = connectivityWatchdogStartMs
        if (!IceWatchdogPolicy.shouldRearmCheck(connectivityWatchdog != null, startedAt)) {
            AppLog.i(
                TAG,
                "ice_watchdog_rearmed reason=relay_candidate skipped=no_watchdog",
                mapOf(
                    "elapsed_ms" to "-",
                    "relay" to candidateCounter.localRelayCount().toString(),
                    "candidate" to candidateSummary,
                    "watchdog_started" to (startedAt > 0L).toString(),
                ).withKey(),
            )
            return
        }
        val elapsed = IceWatchdogPolicy.realElapsedMs(startedAt, System.currentTimeMillis())
        AppLog.i(
            TAG,
            "ice_watchdog_rearmed",
            mapOf(
                "reason" to "relay_candidate",
                "elapsed_ms" to elapsed.toString(),
                "relay" to candidateCounter.localRelayCount().toString(),
                "candidate" to candidateSummary,
            ).withKey(),
        )
        // 已经 CRITICAL（relay 到位但还没连上）：按当前耗时立即复核一次
        if (elapsed >= ICE_WARN_MS) {
            checkConnectivity(connection, maxOf(elapsed, ICE_WARN_MS))
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
        // 【t80】基数必须复位：否则下一世代（或本世代后续的 relay 候选回调）会把**旧基数**当起点，
        // 算出跨世代的巨大"时长"，再次被误当档位 ⇒ 误报失败。
        connectivityWatchdogStartMs = 0L
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

// ============================================================================
// 【t60】回环候选判定（A2/A3；纯 Kotlin，无 Android / org.webrtc 依赖）
// ----------------------------------------------------------------------------
// 为什么必须过滤：真机两台设备都把 `127.0.0.1` / `::1` 的 host 候选写进 SDP 互发
// （t58 §2.1：di-a 63 处 `addr=127.0.0.1`、di-b 176+177+4 条），对端把它当 peer 去
// CREATE_PERMISSION 时被 coturn 以 `403 Forbidden IP` 拒绝（t58 §2.2 探针矩阵：
// 只有 0/8 与 127/8 被拒），既浪费信令、又把对端日志污染成"有候选却连不上"。
// 单测见 app/src/test/kotlin/com/example/webrtcdemo/webrtc/LoopbackCandidatesTest.kt。
// ============================================================================

/**
 * 回环候选判定。
 *
 * 覆盖：IPv4 `127.0.0.0/8`、IPv6 `::1`（含展开写法）以及 IPv4-mapped 写法
 * （`::ffff:127.0.0.1`）。**不**过滤私网/链路本地/CGNAT（那是服务端策略，t58 附录 C 已裁决），
 * 未知/空地址一律**不过滤**（保守：宁可多发一条候选，也不能因解析失败丢掉可用路径）。
 */
object LoopbackCandidates {

    /**
     * @param address 候选地址（`IceCandidateInfo.address`，可能为空串或畸形）。
     * @return true 表示该地址是回环地址，应丢弃（A2 发送前 / A3 接收后）。
     */
    fun isLoopback(address: String): Boolean {
        val addr = address.trim().trim('[', ']').lowercase()
        if (addr.isEmpty()) return false
        if (addr == "::1" || addr == "0:0:0:0:0:0:0:1") return true
        // IPv4-mapped（::ffff:127.0.0.1）与 IPv4
        val v4 = when {
            addr.startsWith("::ffff:") -> addr.removePrefix("::ffff:")
            else -> addr
        }
        val parts = v4.split('.')
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        for (part in parts) {
            val value = part.toIntOrNull() ?: return false
            if (value < 0 || value > 255) return false
        }
        return first == 127
    }
}

// ============================================================================
// 【t80】看门狗"档位 vs 真实耗时"纯判定（可 JVM 单测）
// ----------------------------------------------------------------------------
// 真机缺陷（webrtcdemo-logs-20260916-033453Z）：房主端持续显示「ICE 未连通（…对端候选 -）」
// 而通话完全正常（P2P/RELAY 双形态 down≈2.0 Mbps / up≈1.5–2.4 Mbps）。日志铁证：
//   03:34:24.872 ice_watchdog_rearmed … elapsed_ms=1789529664872  ← epoch 毫秒被当"已用时长"
//   03:34:24.873 ice_timeout after_ms=1789529664872 ice_state=NEW  ← 立刻误判失败
//   03:34:25.130 ice_watchdog_started evt=22                       ← 看门狗其实**这时**才启动
//   03:34:25.436 ice_watchdog_ok elapsed_ms=306 → 25.438 state=CONNECTED
// 根因：`rearmWatchdogOnRelay()` 拿 `now - connectivityWatchdogStartMs` 当**档位**传给
// `checkConnectivity(connection, afterMs)`；基数未初始化（relay 候选早于看门狗启动）时该差值等于
// epoch 毫秒 ⇒ `afterMs >= ICE_FAIL_MS` 恒真 ⇒ 在 `ice_state=NEW` 时直接上报 `onError`。
// 本对象把两处判定抽成纯函数，便于单测钉住（生产代码 `CallSession` 调用同一实现）。
// ============================================================================

/**
 * 看门狗判定（纯函数，无 Android 依赖）。【t80】
 */
object IceWatchdogPolicy {

    /**
     * relay 候选触发的"重新校准"是否**允许**调用 [checkConnectivity]。
     *
     * 看门狗未启动（`watchdogActive=false`）或基数无效（`startMs <= 0`）时必须为假 ——
     * 否则会把 epoch 量级的"时长"当档位，在 `ice_state=NEW` 时立刻误报失败。
     */
    fun shouldRearmCheck(watchdogActive: Boolean, startMs: Long): Boolean =
        watchdogActive && startMs > 0L

    /** 按看门狗基数算出的**真实**已用时长（ms）；基数无效时返回 `-1`（"未知"）。 */
    fun realElapsedMs(startMs: Long, nowMs: Long): Long =
        if (startMs > 0L) nowMs - startMs else -1L

    /**
     * 传入的"档位"是否**陈旧/被误传**（⇒ 必须跳过，不得据此上报失败）。
     *
     * 判据：档位已到失败档（`tierMs >= failMs`）**但**按基数算出的真实耗时仍不足 `failMs`
     * （含"基数无效 ⇒ 耗时未知"这种形态 —— 等价于把 epoch 毫秒当档位）。
     */
    fun isStaleTier(tierMs: Long, startMs: Long, nowMs: Long, failMs: Long): Boolean {
        if (tierMs < failMs) return false
        return realElapsedMs(startMs, nowMs) < failMs
    }
}

// ============================================================================
// 【t83】远端候选计数策略（纯函数，可 JVM 单测）
// ----------------------------------------------------------------------------
// 背景（真机误报横幅的定因质量）：横幅里的"对端候选"取自 `IceCandidateCounter.remoteSummary()`，
// 而它存在两处使数字**不可信**的缺陷：
//   ① 重复计数：t51 的"PC 未就绪入队 → 就绪回放"路径会把队列里的候选**再次**送进
//      `onRemoteIceCandidate()`，同一候选被 `addRemote` 计两次（真机 `host=6,srflx=1,relay=2` 可能被放大）；
//   ② 假阴性：SDP 内携带的远端候选**从不进计数器** ⇒ 对端只把候选写在 SDP 里时，
//      `remoteSummary()` 显示 `-`，与"确实没有候选"无法区分。
// 本对象把三处判定抽成纯函数；生产代码（CallSession）调用同一实现。
// ============================================================================

/**
 * 远端候选计数策略（纯函数，无 Android 依赖）。【t83】
 */
object RemoteCandidateAccounting {

    /**
     * 是否应把本次 `onRemoteIceCandidate()` 计入 `candidateCounter`。
     *
     * **只有首次进入信令入口才计数**：`viaReplay=true` 表示这是暂存队列的回放（该候选此前已计过），
     * 必须跳过，否则同一候选被计两次（t83 缺陷①）。
     */
    fun shouldCount(viaReplay: Boolean): Boolean = !viaReplay

    /**
     * 统计 SDP 中携带的远端候选条数（`a=candidate:` / `candidate:` 行）。
     *
     * 与 `IceCandidateInfo.summarizeSdpCandidates()`（返回类型摘要字符串）互补：这里要的是**条数**，
     * 用于与 trickle 计数分账，消除 `对端候选 -` 的假阴性（t83 缺陷②）。
     */
    fun countSdpCandidates(sdp: String): Int =
        sdp.lineSequence().count { line ->
            val trimmed = line.trim().removePrefix("a=")
            trimmed.startsWith("candidate:")
        }

    /**
     * "对端候选"的**合并口径**（横幅与 `remote_candidates` 诊断字段统一使用）。
     *
     * - 两者皆空 ⇒ `-（trickled 与 SDP 内均无）`（明确"确实没有"，不再是裸 `-`）；
     * - 只有 SDP 有 ⇒ `<trickled 摘要或 -> + SDP内 N`（不再显示为 `-` ⇒ 消除假阴性）；
     * - 只有 trickle 有 ⇒ 原摘要；
     * - 两者都有 ⇒ 摘要 + ` + SDP内 N`。
     */
    fun mergedSummary(trickledSummary: String, sdpCount: Int): String {
        val trickledKnown = trickledSummary.isNotBlank() && trickledSummary != "-"
        return when {
            !trickledKnown && sdpCount <= 0 -> "-（trickled 与 SDP 内均无）"
            !trickledKnown -> "-（trickle 无）+ SDP内 $sdpCount"
            sdpCount <= 0 -> trickledSummary
            else -> "$trickledSummary + SDP内 $sdpCount"
        }
    }

    /**
     * 逐条应用日志（`ice_candidate_remote`）是否应落盘：**首 3 条 + 每 10 条**。
     *
     * 限频只为防刷屏：总数仍由计数变化时的 `ice_candidate_remote_total` 保证可见（t83③）。
     *
     * @param ordinal 本条是第几条（从 1 开始）。
     */
    fun shouldLogRemoteCandidate(ordinal: Int): Boolean =
        ordinal <= 3 || ordinal % 10 == 0
}
