package com.example.webrtcdemo.signaling

import com.example.webrtcdemo.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ============================================================================
// 信令客户端（doc/14 §8；协议权威 doc/09-signaling-protocol-spec.md）
// ----------------------------------------------------------------------------
// §8.1 冻结：
//   端点     `/ws`（doc/05 与 ADR-006 的 `/signal` 作废）
//   默认 URL BuildConfig.SIGNALING_URL = ws://47.238.144.66:8443/ws（可用诊断页覆盖）
//   心跳     ping 间隔 15 s / pong 超时 5 s / 重连等待 3 s / 最多 3 次（doc/09 §6）
//   凭据     TURN 凭据只能来自 created/joined 下发，**禁止硬编码**
//
// 状态机：doc/09 §4（DISCONNECTED→CONNECTING→CONNECTED→WAITING→IN_ROOM→IN_CALL）。
// 线程模型：okhttp 回调线程 + 单线程定时器；状态用 StateFlow 暴露给 Compose。
// ============================================================================

/**
 * WebSocket 信令客户端。
 *
 * @param url 信令地址（`ws://host:port/ws`）。
 */
class SignalingClient(val url: String) {

    /**
     * 上层回调。**可替换**：首页（HomeViewModel）与通话页（CallViewModel）先后接管同一个
     * 连接，避免重建 WebSocket（契约 §2.1 只有 Home/Call 两个 ViewModel，故用可替换监听器）。
     *
     * 赋值时会把「无人接管期间」缓冲的消息补投给新监听器（见 [pendingMessages]），
     * 避免首页导航到通话页的空档丢掉对端的 offer。
     */
    @Volatile
    var listener: Listener? = null
        set(value) {
            field = value
            if (value != null) {
                val queued: List<SignalingMessage>
                synchronized(pendingLock) {
                    queued = pendingMessages.toList()
                    pendingMessages.clear()
                }
                for (message in queued) value.onMessage(message)
            }
        }

    private val pendingLock = Any()

    /** 无监听器期间的缓冲（上限 [MAX_PENDING_MESSAGES]，超出丢弃最旧）。 */
    private val pendingMessages = ArrayDeque<SignalingMessage>()

    /**
     * 上层回调。
     */
    interface Listener {
        /** 状态迁移（已在客户端内部完成可观测的状态推进）。 */
        fun onStateChanged(state: ConnectionState)

        /** 收到一条已解析消息（含 ping/pong，由上层决定是否忽略）。 */
        fun onMessage(message: SignalingMessage)

        /** 传输层失败（会随后自动重连，除非已达重连上限）。 */
        fun onTransportFailure(reason: String, cause: Throwable?)
    }

    companion object {
        /** 心跳发送间隔（doc/09 §6 冻结）。 */
        const val PING_INTERVAL_MS = 15_000L

        /** pong 单窗口超时（发送 ping 后 5 s 未收到该窗口的 pong）。 */
        const val PONG_TIMEOUT_MS = 5_000L

        /**
         * 【t68】容忍的**连续** pong 丢失窗口数（默认 4 ⇒ 有效阈值 ≈ 20 s）。
         *
         * 真机缺陷（dl-b 15:27:45.779）：单次 `ws_pong_timeout`（5 s）即判断线 ⇒ 服务端回收房间、
         * 对端收到 peerLeft、本端重连得 ROOM_NOT_FOUND ⇒ **媒体明明还在流却自动退房**。
         * 现在：单次丢失只记 `pong_miss` 并继续等（重发 ping），连续 [PONG_MISS_TOLERANCE] 次才断线。
         */
        const val PONG_MISS_TOLERANCE = 4

        /** 【t68】有效判活阈值（= [PONG_TIMEOUT_MS] × [PONG_MISS_TOLERANCE]，验收要求 ≥20 s）。 */
        const val PONG_FAIL_AFTER_MS = PONG_TIMEOUT_MS * PONG_MISS_TOLERANCE

        /**
         * 【t68】某个存活窗口是否已丢 pong（**纯函数**，可 JVM 单测）。
         *
         * @param nowMs 当前时刻。
         * @param pingSentAtMs 该窗口发出 ping 的时刻（0 = 无待回应 ping）。
         * @param lastPongAtMs 最近一次收到 pong 的时刻。
         * @param timeoutMs 单窗口超时。
         */
        fun pongMissed(
            nowMs: Long,
            pingSentAtMs: Long,
            lastPongAtMs: Long,
            timeoutMs: Long = PONG_TIMEOUT_MS,
        ): Boolean = pingSentAtMs > 0L && nowMs - pingSentAtMs > timeoutMs && lastPongAtMs < pingSentAtMs

        /**
         * 【t68】连续丢失是否已达到断线阈值（**纯函数**，可 JVM 单测）。
         *
         * @param consecutiveMisses 已连续丢失的窗口数。
         * @param tolerance 容忍上限（默认 [PONG_MISS_TOLERANCE]）。
         */
        fun pongTimeoutReached(consecutiveMisses: Int, tolerance: Int = PONG_MISS_TOLERANCE): Boolean =
            consecutiveMisses >= tolerance

        // socket 重连**不另设次数常量**（t71 契约①："复用既有 rejoinDelayMs 口径，不造平行常量"）：
        // `scheduleReconnect` 直接使用 MAX_REJOIN_ATTEMPTS（10 次，1/2/4/8 s 封顶）
        // ⇒ 总预算 rejoinBudgetMs() = **63 s**（≥60 s 且 < 服务端 90 s 宽限期）。
        // 历史：修复前为"固定 3 s × 3 次 ≈ 9–12 s"（RECONNECT_DELAY_MS + 独立上限 3），3 次失败即把
        // DISCONNECTED 当终态 ⇒ 服务端仍保留席位（对端未收到 peerLeft）、RTP 仍在流时客户端
        // **自杀式退房**（t71 缺口①）。旧常量已删除，防止回退成第二套口径。

        private const val TAG = "signaling"
        private const val WS_CLOSE_NORMAL = 1000

        /**
         * rejoin 重试：**指数退避**（1s,2s,4s,8s,8s,...）上限 [REJOIN_RETRY_MAX_MS]，
         * 最多 [MAX_REJOIN_ATTEMPTS] 次 ≈ 63 s，覆盖服务端约 45 s 的读超时回收窗口。
         *
         * 处置依据见 [SignalingErrorPolicy]（ROOM_FULL 在此语境下是暂时性的）。
         *
         * 【t71】socket 断线重连（[scheduleReconnect]）**共用**本退避函数与次数上限 ——
         * 口径统一为"1/2/4/8 s 封顶、10 次 ≈63 s"，不再维护第二套常量。
         */
        const val REJOIN_RETRY_BASE_MS = 1_000L

        /** 单次重试间隔上限（8 s），避免长尾时对服务端过密。 */
        const val REJOIN_RETRY_MAX_MS = 8_000L

        /** 重试次数上限：累计等待 ≈ 63 s（> 45 s 窗口，< 90 s 宽限期）。 */
        const val MAX_REJOIN_ATTEMPTS = 10

        /**
         * 指数退避延迟：1s,2s,4s,8s,8s,...（上限 [REJOIN_RETRY_MAX_MS]）。
         *
         * 公开是为了让 SignalingErrorPolicyTest 能断言退避序列与"累计等待 > 45 s"这一硬要求
         * （可测试性是该 public 的唯一理由，生产代码同样调用它）。
         *
         * @param attempt 第几次重试（从 1 开始）。
         * @return 本次等待毫秒数。
         */
        fun rejoinDelayMs(attempt: Int): Long {
            var delay = REJOIN_RETRY_BASE_MS
            repeat((attempt - 1).coerceAtLeast(0)) { delay = (delay * 2).coerceAtMost(REJOIN_RETRY_MAX_MS) }
            return delay
        }

        /** rejoin 重试的**累计**等待（ms）—— 诊断用（`ws_rejoin_give_up budget_ms=…`）。 */
        fun rejoinBudgetMs(maxAttempts: Int = MAX_REJOIN_ATTEMPTS): Long {
            var total = 0L
            for (attempt in 1..maxAttempts) total += rejoinDelayMs(attempt)
            return total
        }

        /** 无监听器期间最多缓冲的消息数（含 send 方向的 SDP 消息）。 */
        const val MAX_PENDING_MESSAGES = 32
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // WebSocket 长连接不设读超时（0 = 不超时）
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "signaling-timer").apply { isDaemon = true }
        }

    private val reconnectAttempts = AtomicInteger(0)

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)

    /** 当前状态（供 Compose 观察）。 */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    /** 连接后的「意图」：建房间 or 加入某房间；重连时复用（doc/09 §6 重连规则）。 */
    @Volatile
    private var pendingCreate: Boolean = false

    @Volatile
    private var pendingRoomId: String? = null

    /** 是否由用户主动关闭（主动关闭不触发重连）。 */
    @Volatile
    private var closedByUser: Boolean = false

    /** 已建立/重建的房间号（收到 created/joined 后记录）；异常断线后用它 re-join。 */
    @Volatile
    private var currentRoomId: String? = null

    /** 终态错误后抑制重连（避免对已销毁/已过期房间反复重连）。 */
    @Volatile
    private var reconnectSuppressed: Boolean = false

    /** 本次断线是否属于「已经在房内后掉线」——只有这种场景才值得对 ROOM_FULL 做有界重试。 */
    @Volatile
    private var rejoinAfterDrop: Boolean = false

    /** 已进行的 rejoin 重试次数（成功建立房间或用户新动作时清零）。 */
    @Volatile
    private var rejoinAttempt: Int = 0

    /**
     * 【t68】最近一次进入 `DISCONNECTED` 的**来源**。
     *
     * 真机缺陷（dl-a:7516-7520 / dl-b:9717-9720）：`peerLeft` 与"重连期房间丢失"都会把状态推到
     * `DISCONNECTED`，上层（通话页）随即把它当作**传输层终态**自动挂断 —— 而当时媒体仍在流动
     * （dl-a `down_bps=2047967`、dl-b `down_bps=326398`）。
     * 因此把"这次掉线是否仍可由上层按事件去留"作为**显式信号**暴露给监听者：
     * 见 [disconnectCause] / [DisconnectCause.survivable]。
     *
     * **每个** `setState(DISCONNECTED)` 调用点都必须先写本字段（含 `leave()` / `shutdown()` /
     * 重连耗尽等终态路径，写 [DisconnectCause.FATAL]），否则会读到上一次的陈旧值。
     */
    @Volatile
    private var disconnectCause: DisconnectCause = DisconnectCause.FATAL

    /** 【t68】最近一次 `DISCONNECTED` 的来源（驱动通话页"是否自动结束"的判定）。 */
    val lastDisconnectCause: DisconnectCause
        get() = disconnectCause

    /**
     * 【t68】当前是否处于"**曾经在房内、断线后重连**"的语境。
     *
     * 由 [scheduleReconnect] 在"已有 roomId 却掉线"时置真、[onRoomEstablished] 置假。
     * 通话页用它判定 `ROOM_NOT_FOUND` 属于"房间被回收（可重建）"还是"首次入房就找不到房间"。
     */
    val isRejoinContext: Boolean
        get() = rejoinAfterDrop

    /** 最近一次发出 ping 的时刻；0 表示没有待回应的 ping。 */
    @Volatile
    private var pingSentAtMs: Long = 0L

    /** 最近一次收到 pong 的时刻。 */
    @Volatile
    private var lastPongAtMs: Long = 0L

    /**
     * 【t68】连续丢失的 pong 窗口数。
     *
     * 单次丢失不再直接判断线（真机缺陷：5 s 抖动即被回收房间）；连续 [PONG_MISS_TOLERANCE] 次才断线。
     * 收到任何 pong 即归零。
     */
    @Volatile
    private var consecutivePongMisses: Int = 0

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts.set(0)
            lastPongAtMs = System.currentTimeMillis()
            pingSentAtMs = 0L
            AppLog.i(TAG, "ws_open", mapOf("url" to url))
            setState(ConnectionState.CONNECTED)
            // 重连场景：用原 roomId 重新 join；首次连接：发送待执行意图
            if (pendingCreate || pendingRoomId != null) {
                sendPendingIntent()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                SignalingCodec.decode(text)
            } catch (t: Throwable) {
                AppLog.e(TAG, "msg_decode_failed", mapOf("bytes" to text.length.toString()), t)
                return
            }
            if (message is SignalingMessage.Pong) {
                lastPongAtMs = System.currentTimeMillis()
                pingSentAtMs = 0L
                // 【t68】收到 pong ⇒ 清零"连续丢失"计数
                consecutivePongMisses = 0
            }
            logIncoming(message)
            advanceStateOnIncoming(message)
            SignalingIdentity.update(message)
            val target = listener
            if (target == null) {
                synchronized(pendingLock) {
                    while (pendingMessages.size >= MAX_PENDING_MESSAGES) pendingMessages.removeFirst()
                    pendingMessages.addLast(message)
                }
            } else {
                target.onMessage(message)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.i(TAG, "ws_closing", mapOf("code" to code.toString(), "reason" to reason))
            webSocket.close(WS_CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.i(TAG, "ws_close", mapOf("code" to code.toString(), "reason" to reason))
            this@SignalingClient.webSocket = null
            if (!closedByUser) {
                scheduleReconnect("closed")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.w(
                TAG,
                "ws_close",
                mapOf("reason" to "failure", "code" to (response?.code?.toString() ?: "-")),
            )
            this@SignalingClient.webSocket = null
            listener?.onTransportFailure(t.message ?: t.javaClass.simpleName, t)
            if (!closedByUser) {
                scheduleReconnect("failure")
            }
        }
    }

    // ============================ 对外操作 ============================

    /** 创建房间：连接（必要时）→ 发送 `create` → 进入 WAITING。 */
    fun createRoom() {
        closedByUser = false
        reconnectSuppressed = false
        rejoinAfterDrop = false
        rejoinAttempt = 0
        currentRoomId = null
        pendingCreate = true
        pendingRoomId = null
        if (webSocket == null) {
            openSocket()
        } else {
            sendPendingIntent()
        }
    }

    /**
     * 加入房间：连接（必要时）→ 发送 `join` → 进入 WAITING。
     *
     * @param roomId 已规范化的 6 位房间号（见 [SignalingCodec.normalizeRoomId]）。
     */
    fun joinRoom(roomId: String) {
        closedByUser = false
        reconnectSuppressed = false
        rejoinAfterDrop = false
        rejoinAttempt = 0
        pendingCreate = false
        pendingRoomId = roomId
        if (webSocket == null) {
            openSocket()
        } else {
            sendPendingIntent()
        }
    }

    /** 发送 SDP Offer（§8.3：未入房发送会被服务端拒绝，故本地先拦）。 */
    fun sendOffer(sdp: String) {
        if (!requireMediaSignaling()) return
        if (send(SignalingMessage.Offer(sdp))) {
            AppLog.i(TAG, "offer_sent", mapOf("sdp_bytes" to sdp.length.toString()))
        }
    }

    /** 发送 SDP Answer。 */
    fun sendAnswer(sdp: String) {
        if (!requireMediaSignaling()) return
        if (send(SignalingMessage.Answer(sdp))) {
            AppLog.i(TAG, "answer_sent", mapOf("sdp_bytes" to sdp.length.toString()))
        }
    }

    /**
     * 发送 ICE candidate。
     *
     * @param candidate 候选文本。
     * @param sdpMid 媒体标识（可空）。
     * @param sdpMLineIndex 媒体行索引（可空）；§8.2 要求二者至少一个有效。
     */
    fun sendIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        if (!requireMediaSignaling()) return
        send(SignalingMessage.Ice(candidate, sdpMid, sdpMLineIndex))
    }

    /**
     * 发送本端 NAT 类型（§8.2）。
     *
     * @param natType 线上枚举字符串（§6.6）。
     */
    fun sendNatType(natType: String) {
        if (!requireMediaSignaling()) return
        send(SignalingMessage.NatTypeMessage(natType))
    }

    /** 主动离开：发送 `leave` → 关闭连接 → 进入 DISCONNECTED（不重连）。 */
    fun leave() {
        closedByUser = true
        reconnectSuppressed = false
        rejoinAfterDrop = false
        rejoinAttempt = 0
        currentRoomId = null
        pendingCreate = false
        pendingRoomId = null
        if (_state.value != ConnectionState.DISCONNECTED) {
            send(SignalingMessage.Leave)
        }
        // 【t68】用户主动离开 = 传输层终态（通话页已由 hangup() 的 callEnded 闸门接管）
        disconnectCause = DisconnectCause.FATAL
        setState(ConnectionState.DISCONNECTED)
        webSocket?.close(WS_CLOSE_NORMAL, null)
        webSocket = null
    }

    /** 释放资源（进程退出/诊断页关闭）。 */
    fun shutdown() {
        closedByUser = true
        scheduler.shutdownNow()
        webSocket?.close(WS_CLOSE_NORMAL, null)
        webSocket = null
        disconnectCause = DisconnectCause.FATAL
        setState(ConnectionState.DISCONNECTED)
    }

    /** 当前是否处于可收发媒体信令的状态。 */
    fun canSendMediaSignaling(): Boolean = ConnectionState.canSendMediaSignaling(_state.value)

    // ============================ 内部实现 ============================

    private fun openSocket() {
        setState(ConnectionState.CONNECTING)
        AppLog.i(TAG, "ws_connecting", mapOf("url" to url))
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, socketListener)
        startTimers()
    }

    private fun sendPendingIntent() {
        if (pendingCreate) {
            if (send(SignalingMessage.Create)) {
                AppLog.i(TAG, "room_create_sent")
                setState(ConnectionState.WAITING)
            }
            return
        }
        val roomId = pendingRoomId ?: return
        if (send(SignalingMessage.Join(roomId))) {
            AppLog.i(TAG, "room_join_sent", mapOf("room" to roomId))
            setState(ConnectionState.WAITING)
        }
    }

    /** 只有 IN_ROOM / IN_CALL 才允许发 offer/answer/ice/natType（§8.3 第 2 条）。 */
    private fun requireMediaSignaling(): Boolean {
        if (canSendMediaSignaling()) return true
        AppLog.w(TAG, "msg_blocked", mapOf("state" to _state.value.name, "reason" to "not_in_room"))
        return false
    }

    private fun send(message: SignalingMessage): Boolean {
        val socket = webSocket
        if (socket == null) {
            AppLog.w(TAG, "msg_dropped", mapOf("reason" to "no_socket", "type" to typeName(message)))
            return false
        }
        val payload = try {
            SignalingCodec.encode(message)
        } catch (t: Throwable) {
            AppLog.e(TAG, "msg_encode_failed", mapOf("type" to typeName(message)), t)
            return false
        }
        val ok = socket.send(payload)
        if (!ok) {
            AppLog.w(TAG, "msg_dropped", mapOf("reason" to "send_failed", "type" to typeName(message)))
            return false
        }
        // 不逐帧打印 candidate 正文，只记长度（§9 低频要求）
        if (message !is SignalingMessage.Offer && message !is SignalingMessage.Answer) {
            AppLog.d(TAG, "msg_sent", mapOf("type" to typeName(message), "bytes" to payload.length.toString()))
        }
        return true
    }

    /** 收到消息时客户端可自主推进的状态（§4 状态机）。 */
    private fun advanceStateOnIncoming(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Created -> onRoomEstablished(message.roomId)
            is SignalingMessage.Joined -> onRoomEstablished(message.roomId)
            is SignalingMessage.PeerJoined -> setState(ConnectionState.IN_CALL)

            is SignalingMessage.ServerError -> {
                // 处置策略抽成纯函数（SignalingErrorPolicy，可 JVM 单测），避免后人把 ROOM_FULL 挪回终态表
                val action = SignalingErrorPolicy.actionFor(message.code, rejoinAfterDrop)
                when (action) {
                    SignalingErrorPolicy.Action.RETRY_REJOIN -> {
                        // 静默掉线重连撞上"槽位尚未回收"（约 45 s 窗口）：暂时性，指数退避重试。
                        // 这里**直接 return**：绝不清空房间意图，否则退避后不知道 join 哪个房间（verifier 要点）。
                        scheduleRejoinRetry()
                        return
                    }

                    SignalingErrorPolicy.Action.TERMINAL_SUPPRESS -> {
                        // 房间已销毁 / 报文非法：重试无意义，抑制重连；清空意图由 action.clearsRoomIntent 决定
                        if (action.clearsRoomIntent) {
                            reconnectSuppressed = true
                            pendingCreate = false
                            pendingRoomId = null
                            currentRoomId = null
                        }
                        AppLog.w(TAG, "ws_reconnect_suppressed", mapOf("code" to message.code))
                        // 【t68】"重连期房间被回收" ⇒ 可存活（上层应给可恢复态，**不得**自动退出通话页）；
                        // 其余终态码（报文非法等）仍为传输层终态。
                        disconnectCause = if (rejoinAfterDrop &&
                            SignalingErrorPolicy.isRoomLossCode(message.code)
                        ) {
                            DisconnectCause.ROOM_LOST
                        } else {
                            DisconnectCause.FATAL
                        }
                        AppLog.w(
                            TAG,
                            "disconnect_cause",
                            mapOf(
                                "cause" to disconnectCause.name.lowercase(),
                                "code" to message.code,
                                "rejoin" to rejoinAfterDrop.toString(),
                                "room" to (currentRoomId ?: "-"),
                            ),
                        )
                    }

                    SignalingErrorPolicy.Action.SURFACE -> {
                        // 首次入房遇满房等：不抑制也不重试，交给 UI 呈现错误码
                        AppLog.w(TAG, "server_error_surfaced", mapOf("code" to message.code))
                    }
                }
                setState(ConnectionState.DISCONNECTED)
            }

            is SignalingMessage.PeerLeft -> {
                SignalingIdentity.clearRemote()
                // 【t68】对端离开 ≠ 传输层终态：连接本身仍然健康（"等待对方加入"是合法状态），
                // 去留由上层按"是否曾连上过 / 媒体是否存活"判定（见 CallSurvivability.peerLeftAction）。
                disconnectCause = DisconnectCause.PEER_LEFT
                AppLog.w(
                    TAG,
                    "disconnect_cause",
                    mapOf("cause" to disconnectCause.name.lowercase(), "peer_left" to "true"),
                )
                setState(ConnectionState.DISCONNECTED)
            }
            else -> Unit
        }
    }

    /**
     * 房间已建立（首次 created/joined，或重连后再次 joined）。
     *
     * 记录 roomId 并把「连接意图」切换为 **join(roomId)**：go-dev 对齐通知第 7 点明确
     * ——显式 leave 才销毁房间，**异常断线保留房间**，重连必须用原 roomId 重新 join；
     * host 若继续用 create 会新建房间、丢掉对端（doc/09 §6 重连规则）。
     */
    private fun onRoomEstablished(roomId: String) {
        currentRoomId = roomId
        rejoinAfterDrop = false
        rejoinAttempt = 0
        pendingCreate = false
        pendingRoomId = roomId
        setState(ConnectionState.IN_ROOM)
    }

    private fun logIncoming(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Created -> AppLog.i(TAG, "room_created", mapOf("room" to message.roomId))
            is SignalingMessage.Joined -> AppLog.i(
                TAG,
                "room_joined",
                mapOf("room" to message.roomId, "peer" to message.peerId),
            )

            is SignalingMessage.PeerJoined -> AppLog.i(TAG, "peer_joined", mapOf("peer" to message.peerId))
            is SignalingMessage.PeerLeft -> AppLog.i(TAG, "peer_left", mapOf("peer" to message.peerId))
            is SignalingMessage.Offer -> AppLog.i(TAG, "offer_received", mapOf("sdp_bytes" to message.sdp.length.toString()))
            is SignalingMessage.Answer -> AppLog.i(TAG, "answer_received", mapOf("sdp_bytes" to message.sdp.length.toString()))
            is SignalingMessage.Ice -> AppLog.d(TAG, "ice_received", mapOf("candidate_bytes" to message.candidate.length.toString()))
            is SignalingMessage.NatTypeMessage -> AppLog.i(TAG, "nat_received", mapOf("nat" to message.natType))
            is SignalingMessage.ServerError -> AppLog.e(
                TAG,
                "server_error",
                mapOf("code" to message.code, "detail" to message.message),
            )

            is SignalingMessage.Pong -> AppLog.v(TAG, "pong_received")
            else -> AppLog.d(TAG, "msg_received", mapOf("type" to typeName(message)))
        }
    }

    private fun startTimers() {
        // 心跳：固定 15 s 发一次 ping（doc/09 §6；服务端读超时 45 s，容忍 3 个心跳周期）
        scheduler.scheduleWithFixedDelay(
            Runnable {
                if (webSocket == null) return@Runnable
                // 记录「发出时刻」：pong 超时判定依赖它。
                // 注意（go-dev 对齐通知第 3 点 / doc/14 §8.1）：pong.timestamp 是**服务端**当前时间，
                // 不能用「ping.timestamp == pong.timestamp」配对算 RTT，这里只做存活判定。
                val sentAt = System.currentTimeMillis()
                pingSentAtMs = sentAt
                send(SignalingMessage.Ping(sentAt))
            },
            PING_INTERVAL_MS,
            PING_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        // 存活检查（t68 口径变化）：单次 pong 丢失**不再**判断线 —— 记 `pong_miss` 并重发 ping 继续等；
        // 仅当连续丢失达到 PONG_MISS_TOLERANCE（默认 4 ⇒ ≈20 s）才取消连接并重连。
        scheduler.scheduleWithFixedDelay(
            {
                val sentAt = pingSentAtMs
                if (SignalingClient.pongMissed(System.currentTimeMillis(), sentAt, lastPongAtMs)) {
                    consecutivePongMisses += 1
                    AppLog.w(
                        TAG,
                        "pong_miss",
                        mapOf(
                            "count" to consecutivePongMisses.toString(),
                            "tolerance" to PONG_MISS_TOLERANCE.toString(),
                            "timeout_ms" to PONG_TIMEOUT_MS.toString(),
                            "fail_after_ms" to PONG_FAIL_AFTER_MS.toString(),
                        ),
                    )
                    if (SignalingClient.pongTimeoutReached(consecutivePongMisses)) {
                        AppLog.w(
                            TAG,
                            "ws_pong_timeout",
                            mapOf(
                                "timeout_ms" to PONG_TIMEOUT_MS.toString(),
                                "misses" to consecutivePongMisses.toString(),
                            ),
                        )
                        pingSentAtMs = 0L
                        consecutivePongMisses = 0
                        webSocket?.cancel()
                        webSocket = null
                        listener?.onTransportFailure("pong_timeout", null)
                        if (!closedByUser) scheduleReconnect("pong_timeout")
                    } else {
                        // 容忍期内：重发一个 ping，把"窗口"推进到下一段（避免同一窗口被重复计数）
                        pingSentAtMs = System.currentTimeMillis()
                        send(SignalingMessage.Ping(pingSentAtMs))
                    }
                }
            },
            PONG_TIMEOUT_MS,
            PONG_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * 静默掉线重连撞上 ROOM_FULL 时的有界重试（go-dev 实测边界）。
     *
     * 服务端要等读超时（约 45 s）才回收死会话槽位；该窗口内重连 join 会被拒。
     * 回收后仍在线的一方会先收到 peerLeft，此后同一条连接再 join 即可成功。
     *
     * @see REJOIN_RETRY_BASE_MS
     * @see MAX_REJOIN_ATTEMPTS
     */
    private fun scheduleRejoinRetry() {
        val attempt = rejoinAttempt + 1
        if (attempt > MAX_REJOIN_ATTEMPTS) {
            AppLog.e(
                TAG,
                "ws_rejoin_give_up",
                mapOf(
                    "attempts" to attempt.toString(),
                    "budget_ms" to rejoinBudgetMs().toString(),
                    "room" to (currentRoomId ?: "-"),
                ),
            )
            reconnectSuppressed = true
            pendingCreate = false
            pendingRoomId = null
            currentRoomId = null
            // 【t68 收尾】rejoin（ROOM_FULL）重试耗尽同样**不等于通话结束**：房间意图虽被清空，
            // 但服务端宽限期内对端并未收到 peerLeft ⇒ 交给上层按媒体存活判定（SIGNAL_LOST）。
            disconnectCause = DisconnectCause.SIGNAL_LOST
            setState(ConnectionState.DISCONNECTED)
            return
        }
        rejoinAttempt = attempt
        val delayMs = rejoinDelayMs(attempt)
        AppLog.w(
            TAG,
            "ws_rejoin_retry",
            mapOf(
                "attempt" to attempt.toString(),
                "delay_ms" to delayMs.toString(),
                "room" to (currentRoomId ?: "-"),
            )
        )
        scheduler.schedule(
            { if (webSocket == null) openSocket() else sendPendingIntent() },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleReconnect(reason: String) {
        if (reconnectSuppressed) {
            AppLog.i(TAG, "ws_reconnect_skipped", mapOf("reason" to reason))
            return
        }
        // 曾在房内 → 标记为「掉线后重连」：这决定了 ROOM_FULL 是否有界重试、以及 join(roomId) 语义
        val inRoomBeforeDrop = currentRoomId != null
        if (inRoomBeforeDrop) rejoinAfterDrop = true
        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt > MAX_REJOIN_ATTEMPTS) {
            AppLog.e(
                TAG,
                "ws_reconnect_give_up",
                mapOf(
                    "attempts" to attempt.toString(),
                    "budget_ms" to SignalingClient.rejoinBudgetMs().toString(),
                    "in_room_before_drop" to inRoomBeforeDrop.toString(),
                    "room" to (currentRoomId ?: "-"),
                ),
            )
            // 【t68 收尾】重连预算耗尽**不等于通话结束**：服务端按 t67 保留席位 90 s 且未发 peerLeft，
            // 此时媒体（RTP）往往仍在流 ⇒ 交给上层按"曾连上过 / 媒体是否存活"判定
            // （CallSurvivability.signalLostAction），不得在这里当作终态。
            disconnectCause = DisconnectCause.SIGNAL_LOST
            setState(ConnectionState.DISCONNECTED)
            return
        }
        val delayMs = SignalingClient.rejoinDelayMs(attempt)
        AppLog.w(
            TAG,
            "ws_reconnect_scheduled",
            mapOf(
                "attempt" to attempt.toString(),
                "reason" to reason,
                "delay_ms" to delayMs.toString(),
                "budget_ms" to SignalingClient.rejoinBudgetMs().toString(),
                "max_attempts" to MAX_REJOIN_ATTEMPTS.toString(),
            ),
        )
        setState(ConnectionState.CONNECTING)
        scheduler.schedule({ openSocket() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun setState(newState: ConnectionState) {
        val old = _state.value
        if (old == newState) return
        _state.value = newState
        AppLog.i(TAG, "state_change", mapOf("from" to old.name, "to" to newState.name))
        listener?.onStateChanged(newState)
    }

    private fun typeName(message: SignalingMessage): String = message::class.simpleName ?: "unknown"
}

// ============================================================================
// DISCONNECTED 的来源（t68，纯逻辑，可 JVM 单测）
// ----------------------------------------------------------------------------
// 真机缺陷根因（dl-a:7516-7520 / dl-b:9717-9720）：`peerLeft` 与"重连期 ROOM_NOT_FOUND"都会把
// 信令状态推到 DISCONNECTED，而通话页把 DISCONNECTED 一律当作**传输层终态**自动挂断/退出房间 ——
// 当时媒体仍在流动（dl-a down_bps=2047967、dl-b down_bps=326398）。
// 因此把"这次掉线是否仍可由上层按事件去留"显式建模，避免上层再按状态名猜语义。
// ============================================================================

/**
 * 最近一次 `DISCONNECTED` 的来源（t68）。
 *
 * @property survivable 是否**不得**被上层当作传输层终态自动结束通话
 *   （真正的结束仍可由用户显式操作，或由 `FAILED`/重连耗尽等终态路径触发）。
 */
enum class DisconnectCause(val survivable: Boolean) {
    /** 用户主动离开、进程退出等：通话必须结束。 */
    FATAL(survivable = false),

    /** 对端离开（`peerLeft`）：连接健康，去留由上层按"是否曾连上过/媒体是否存活"判定。 */
    PEER_LEFT(survivable = true),

    /** 重连期房间被回收（`ROOM_NOT_FOUND`/`ROOM_EXPIRED`）：给可恢复态 + 显式重建入口。 */
    ROOM_LOST(survivable = true),

    /**
     * 【t68 收尾】信令链路丢失且**重连预算耗尽**（`ws_reconnect_give_up` / `ws_rejoin_give_up`）。
     *
     * 与 [FATAL] 的区别：服务端按 t67 保留席位 90 s、宽限期内**不发 peerLeft**，因此"重连不上"只说明
     * **本端信令**断了，**不说明对端已离开**；此时 RTP 往往仍在流（dl-b：`down_bps=326398`）。
     * 上层必须用 `CallSurvivability.signalLostAction` 判定：本世代曾连上过或有媒体证据 ⇒ 保留通话页
     * （会话不销毁）+ 可恢复态入口；否则才结束通话（避免"半死不活"的页面）。
     */
    SIGNAL_LOST(survivable = true),
}

// ============================================================================
// 服务端错误码的客户端处置策略（纯逻辑，可 JVM 单测）
// ----------------------------------------------------------------------------
// 抽出来的原因：go-dev 的 TestE2E_ReconnectStaleSessionCausesRoomFull 证明
// ROOM_FULL 在「静默掉线后立即重连」语境下是**暂时性**的（服务端要等约 45 s 读超时才回收槽位），
// 若当作终态抑制重连，移动网络下通话会**永久无法恢复**。
// 因此把"哪类错误抑制重连 / 哪类有界重试 / 哪类只交 UI"固化成可单测的纯函数，
// 避免后人再把 ROOM_FULL 挪回终态表（见 SignalingErrorPolicyTest）。
// ============================================================================

/**
 * 服务端错误码 → 客户端处置动作（doc/09 §3.10 码表 + go-dev 实测边界）。
 */
object SignalingErrorPolicy {

    /**
     * 处置动作。
     *
     * @property clearsRoomIntent 是否清空"房间意图"（pendingCreate / pendingRoomId / currentRoomId）。
     *   **只有终态动作才允许清空** —— 若 ROOM_FULL 走重试却把意图清掉，退避后将不知道要 join 哪个房间，
     *   重试必然失败（verifier 追踪要点，由 SignalingErrorPolicyTest 锁定）。
     */
    enum class Action(val clearsRoomIntent: Boolean) {
        /** 终态：抑制重连并清空房间意图（房间已销毁 / 报文非法等，重试无意义）。 */
        TERMINAL_SUPPRESS(clearsRoomIntent = true),

        /** 掉线重连语境下的暂时性拒绝：有界指数退避重试 join，**必须保留房间意图**。 */
        RETRY_REJOIN(clearsRoomIntent = false),

        /** 其它：不抑制、不重试，仅把错误码交给 UI 呈现（保留意图，用户可手动重试）。 */
        SURFACE(clearsRoomIntent = false),
    }

    /**
     * 终态错误码（**不含** ROOM_FULL）。
     *
     * ROOM_NOT_FOUND / ROOM_EXPIRED = 房间确已销毁（go-dev 实测：一空即销毁）；
     * INVALID_MESSAGE / NOT_IN_ROOM = 客户端报文问题，重试同样无意义。
     */
    val TERMINAL_CODES: Set<String> = setOf(
        "ROOM_NOT_FOUND",
        "ROOM_EXPIRED",
        "INVALID_MESSAGE",
        "NOT_IN_ROOM",
    )

    /** 房间已满：重连语境下属**暂时性**错误码（约 45 s 回收窗口）。 */
    const val CODE_ROOM_FULL = "ROOM_FULL"

    /**
     * 计算某错误码在当前语境下的处置动作。
     *
     * @param code 服务端 error.code。
     * @param rejoinAfterDrop 本次错误是否发生在「曾经在房内、断线后重连」的语境中。
     * @return 处置动作；未知错误码返回 [Action.SURFACE]（不抑制、不重试）。
     */
    fun actionFor(code: String, rejoinAfterDrop: Boolean): Action = when {
        code == CODE_ROOM_FULL && rejoinAfterDrop -> Action.RETRY_REJOIN
        code in TERMINAL_CODES -> Action.TERMINAL_SUPPRESS
        else -> Action.SURFACE
    }

    /**
     * 该错误码是否意味着**本次通话已结束**（UI 应"干脆回首页 + 明确提示"，而非停在通话页）。
     *
     * 与 [TERMINAL_CODES] 同集合：房间确已销毁（`ROOM_NOT_FOUND` / `ROOM_EXPIRED`）或报文非法
     * （`INVALID_MESSAGE` / `NOT_IN_ROOM`）—— 重试无意义，通话**不可能**继续。
     *
     * **为什么不含 `ROOM_FULL`**：它在"掉线重连"语境下是**暂时性**的（服务端约 45 s 回收窗口），
     * 由 [Action.RETRY_REJOIN] 有界重试；只有重试耗尽或再也拿不到房间时才转为结束通话
     * （见 `CallViewModel.endCallNow`，以及 captain 2026-09-13「不留半死不活的通话界面」要求）。
     *
     * @param code 服务端 error.code。
     * @return `true` 表示应结束通话并退出通话页。
     */
    fun endsCall(code: String): Boolean = code in TERMINAL_CODES

    /**
     * 【t68】"房间确已丢失"的错误码集合（**只**含房间被回收这一类，可由"重新创建房间"恢复）。
     *
     * 与 [TERMINAL_CODES] 的区别：`INVALID_MESSAGE`/`NOT_IN_ROOM` 属于客户端报文问题，
     * 不是房间丢失，**不得**因为它们给用户"重新创建房间"的可恢复态。
     */
    val ROOM_LOSS_CODES: Set<String> = setOf("ROOM_NOT_FOUND", "ROOM_EXPIRED")

    /**
     * 【t68】该错误码是否属于"房间确已丢失"（= 可重建；去留再由上层按语境/媒体判定）。
     *
     * 真机缺陷（dl-b 15:28:02.248）：pong 单次丢失 ⇒ 服务端回收房间 ⇒ 本端重连得 `ROOM_NOT_FOUND`
     * ⇒ 旧实现直接按 [endsCall] **自动退房**，而当时 `down_bps=326398`、画面仍在更新。
     * 现在由调用方组合本函数与 `CallSurvivability.roomLostAction(rejoinContext, mediaAlive)`：
     * 只有"曾连上过 / 仍在重连语境 / 媒体仍存活"才保持通话页并给出显式重建入口。
     *
     * @param code 服务端 error.code。
     */
    fun isRoomLossCode(code: String): Boolean = code in ROOM_LOSS_CODES

    /**
     * 【t68】通话可存活化判定：该错误码在"曾经在房内"的语境下应**保持通话页**还是结束通话。
     *
     * 与 [endsCall] 的关系：[endsCall] 保持**首次入房**的语义不变（找不到房间 ⇒ 结束），
     * 本函数是它之前的"重连语境闸门"；两者在同一语境下会给出**不同**结论，这正是本缺陷的修复点
     * （见 `CallSurvivabilityTest` / `SignalingErrorPolicyTest` 的 old-red/new-green 对照用例）。
     *
     * @param code 服务端 error.code。
     * @param rejoinContext 是否处于"曾经在房内（等待/通话/重连）"的语境。
     * @param mediaAlive 是否有可用媒体证据（媒体仍在流时必须保持通话页）。
     */
    fun keepsCallOnRoomLoss(code: String, rejoinContext: Boolean, mediaAlive: Boolean): Boolean =
        isRoomLossCode(code) && (rejoinContext || mediaAlive)
}

// ============================================================================
// 对端身份推导（doc/14 §8.2 + 架构裁定 D-3，low）
// ----------------------------------------------------------------------------
// 服务端**不**在 created 里下发本端 peerId（doc/09 未定义该字段，契约不扩字段），
// 因此本端 ID 必须由客户端推导：
//   · joiner：直接取 joined.peerId；
//   · host  ：收到 peerJoined.peerId = X 后**取反**推导本端（房间恒 2 人，doc/09 §7）。
// **禁止**用「host 恒为 peer-001」这类槽位顺序推断 —— 服务端按**空槽位**分配 ID：
// 断线重连（房间保留、Detach 后空槽被重连者占用）时，后加入者可能拿到 peer-001 或 peer-002。
// 未收到 peerJoined 之前，UI 显示占位符（—）。
// ============================================================================

/**
 * 本端 / 对端 peerId（进程内单例，供 UI 展示与日志引用）。
 */
object SignalingIdentity {

    private val _selfPeerId = MutableStateFlow("")
    /** 本端 peerId；未知时为空串（UI 显示占位符 —）。 */
    val selfPeerId: StateFlow<String> = _selfPeerId.asStateFlow()

    private val _remotePeerId = MutableStateFlow("")
    /** 对端 peerId；未知时为空串。 */
    val remotePeerId: StateFlow<String> = _remotePeerId.asStateFlow()

    /** 两个合法槽位（**仅**用于"取反"，不表示任何分配顺序）。 */
    private const val SLOT_A = "peer-001"
    private const val SLOT_B = "peer-002"

    /**
     * 按收到的信令消息更新身份。
     *
     * @param message 已解析消息（由 [SignalingClient] 收帧时调用）。
     */
    fun update(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Joined -> {
                // joiner 与重连者：joined.peerId 是**权威值**，直接采用（可覆盖旧值）
                _selfPeerId.value = message.peerId
            }

            is SignalingMessage.PeerJoined -> {
                _remotePeerId.value = message.peerId
                if (_selfPeerId.value.isEmpty()) {
                    // host：由对端 ID 取反推导本端（禁止假设自己恒为 peer-001）
                    invert(message.peerId)?.let { _selfPeerId.value = it }
                }
            }

            else -> Unit
        }
    }

    /** 对端离开：清掉对端 ID（本端 ID 保留，重连后由 joined/peerJoined 刷新）。 */
    fun clearRemote() {
        _remotePeerId.value = ""
    }

    /** 新会话开始：清空两侧身份。 */
    fun reset() {
        _selfPeerId.value = ""
        _remotePeerId.value = ""
    }

    /**
     * 由对端 ID 取反得到本端 ID（仅两人房间）。
     *
     * @param peerId 对端 peerId（peer-001 / peer-002）。
     * @return 本端 peerId；无法识别时返回 null（不猜测顺序）。
     */
    fun invert(peerId: String): String? = when (peerId) {
        SLOT_A -> SLOT_B
        SLOT_B -> SLOT_A
        else -> null
    }
}

// ============================================================================
// 信令连接持有者（进程内单例）

// ----------------------------------------------------------------------------
// 存在的理由：契约 §2.1 只给了 HomeViewModel 与 CallViewModel 两个 ViewModel，
// 而「首页发起 create/join → 导航到通话页 → 通话页继续收发 offer/answer/ice」
// 需要**同一个** WebSocket 连接跨页面存活。故用一个极薄的持有者保存连接，
// 两个 ViewModel 通过替换 [SignalingClient.listener] 依次接管回调。
// 这样既不改契约的文件清单，也不重建连接（重建会丢房间上下文）。
// ============================================================================

/**
 * 信令客户端持有者。
 */
object SignalingHolder {

    @Volatile
    private var client: SignalingClient? = null

    /**
     * 取连接（不存在则按 [url] 创建）。
     *
     * @param url 信令地址（见 [com.example.webrtcdemo.config.AppConfig.signalingUrl]）。
     */
    @Synchronized
    fun getOrCreate(url: String): SignalingClient {
        client?.let {
            if (it.url == url) return it
            // URL 被诊断页改过：关闭旧连接，重建
            it.shutdown()
        }
        val created = SignalingClient(url)
        client = created
        return created
    }

    /** 当前连接（可能为 null）。 */
    fun current(): SignalingClient? = client

    /** 丢弃连接（挂断/返回首页时释放）。 */
    @Synchronized
    fun release() {
        client?.shutdown()
        client = null
    }
}
