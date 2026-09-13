package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.model.IceEvent
import com.example.webrtcdemo.model.IceEventType
import com.example.webrtcdemo.model.StatsSnapshot
import com.example.webrtcdemo.signaling.SignalingClient
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
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
                    connection.setLocalDescription(localSetObserver, sdp)
                    signaling.sendOffer(sdp.description)
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
        val connection = peerConnection ?: return
        AppLog.i(TAG, "offer_received", mapOf("sdp_bytes" to sdp.length.toString()))
        connection.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(sdp0: SessionDescription) = Unit

                override fun onSetSuccess() {
                    AppLog.i(TAG, "answer_create")
                    connection.createAnswer(
                        object : SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription) {
                                connection.setLocalDescription(localSetObserver, description)
                                signaling.sendAnswer(description.description)
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
        val connection = peerConnection ?: return
        AppLog.i(TAG, "answer_received", mapOf("sdp_bytes" to sdp.length.toString()))
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
        val connection = peerConnection ?: return
        val mid = sdpMid ?: ""
        // §8.2：sdpMid 与 sdpMLineIndex 至少一个有效
        if (mid.isEmpty() && sdpMLineIndex == null) {
            AppLog.w(TAG, "ice_dropped", mapOf("reason" to "no_mid_and_no_index"))
            return
        }
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
            signaling.sendIce(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            emitEvent(IceEventType.CANDIDATE, "mid=${candidate.sdpMid} idx=${candidate.sdpMLineIndex}")
        }

        override fun onIceConnectionState(state: PeerConnection.IceConnectionState) {
            emitEvent(IceEventType.ICE_CONNECTION, state.name)
        }

        override fun onIceGatheringState(state: PeerConnection.IceGatheringState) {
            emitEvent(IceEventType.ICE_GATHERING, state.name)
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                emitEvent(IceEventType.END_OF_CANDIDATES, "complete")
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
