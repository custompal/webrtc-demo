package com.example.webrtcdemo.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ============================================================================
// 信令消息（doc/14 §8.2 冻结字段名；协议权威 = doc/09-signaling-protocol-spec.md）
// ----------------------------------------------------------------------------
// 消息类型与字段**逐字冻结**，不得改名（§8.2 列出「作废字段名」：
//   stun/turn/turnUser/turnPass → 必须用 stunUrl/turnUrl/turnUsername/turnCredential）。
// 序列化判别字段固定为 `type`（kotlinx.serialization 默认 classDiscriminator）。
// ============================================================================

/**
 * 信令消息（密封类，`type` 为判别字段）。
 */
@Serializable
sealed class SignalingMessage {

    /** `create` —— C→S，发起方创建房间（无其它字段）。 */
    @Serializable
    @SerialName("create")
    data object Create : SignalingMessage()

    /**
     * `created` —— S→C，房间创建成功（doc/09 §3.1）。
     *
     * @property roomId 6 字符房间号（服务端生成）。
     * @property stunUrl STUN URL。
     * @property turnUrl TURN URL（含 transport 参数）。
     * @property turnUsername TURN 用户名。
     * @property turnCredential TURN 密码。
     */
    @Serializable
    @SerialName("created")
    data class Created(
        val roomId: String,
        val stunUrl: String,
        val turnUrl: String,
        val turnUsername: String,
        val turnCredential: String,
    ) : SignalingMessage()

    /**
     * `join` —— C→S，加入房间（doc/09 §3.2）。
     *
     * @property roomId 6 字符房间号。
     */
    @Serializable
    @SerialName("join")
    data class Join(val roomId: String) : SignalingMessage()

    /**
     * `joined` —— S→C，加入成功（doc/09 §3.2）。
     *
     * @property peerId 服务端分配的本端 ID（`peer-001`/`peer-002`）。
     */
    @Serializable
    @SerialName("joined")
    data class Joined(
        val roomId: String,
        val stunUrl: String,
        val turnUrl: String,
        val turnUsername: String,
        val turnCredential: String,
        val peerId: String,
    ) : SignalingMessage()

    /**
     * `peerJoined` —— S→C，对端加入（doc/09 §3.3）。**收到方是发起方**（应发 Offer）。
     */
    @Serializable
    @SerialName("peerJoined")
    data class PeerJoined(val peerId: String) : SignalingMessage()

    /** `peerLeft` —— S→C，对端离开（doc/09 §3.9）。 */
    @Serializable
    @SerialName("peerLeft")
    data class PeerLeft(val peerId: String) : SignalingMessage()

    /** `offer` —— C→S→C，SDP Offer（doc/09 §3.4）。 */
    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : SignalingMessage()

    /** `answer` —— C→S→C，SDP Answer（doc/09 §3.5）。 */
    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : SignalingMessage()

    /**
     * `ice` —— C→S→C，ICE Candidate（doc/09 §3.6）。
     *
     * `sdpMid` 与 `sdpMLineIndex` 至少一个有效（§8.2）。
     */
    @Serializable
    @SerialName("ice")
    data class Ice(
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null,
    ) : SignalingMessage()

    /**
     * `natType` —— C→S→C，NAT 类型交换（doc/09 §3.7）。
     *
     * @property natType 枚举字符串（§6.6）。
     */
    @Serializable
    @SerialName("natType")
    data class NatTypeMessage(val natType: String) : SignalingMessage()

    /** `leave` —— C→S，主动离开（doc/09 §3.8）。 */
    @Serializable
    @SerialName("leave")
    data object Leave : SignalingMessage()

    /**
     * `error` —— S→C，错误响应（doc/09 §3.10）。
     *
     * @property code 错误码（ROOM_NOT_FOUND / ROOM_FULL / …）。
     * @property message 人类可读描述（英文）。
     */
    @Serializable
    @SerialName("error")
    data class ServerError(val code: String, val message: String) : SignalingMessage()

    /** `ping` —— C→S 心跳（doc/09 §3.11）。 */
    @Serializable
    @SerialName("ping")
    data class Ping(val timestamp: Long) : SignalingMessage()

    /** `pong` —— S→C 心跳响应；`timestamp` 为**服务端**当前 Unix 毫秒（§8.1）。 */
    @Serializable
    @SerialName("pong")
    data class Pong(val timestamp: Long) : SignalingMessage()
}

/**
 * 信令编解码与房间号工具。
 */
object SignalingCodec {

    /** 解析器：忽略未知字段（服务端演进时不崩），但**不**忽略必填字段缺失。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 编码为文本帧。
     *
     * @throws kotlinx.serialization.SerializationException 理论不会发生（均为可序列化类型）。
     */
    fun encode(message: SignalingMessage): String =
        json.encodeToString(SignalingMessage.serializer(), message)

    /**
     * 解码文本帧。调用方需容忍异常（服务端异常/协议漂移）。
     *
     * @throws kotlinx.serialization.SerializationException 文本非法或缺必填字段。
     */
    fun decode(text: String): SignalingMessage =
        json.decodeFromString(SignalingMessage.serializer(), text)

    /** 房间号校验正则（§8.2：排除易混淆字符 I L O 0 1）。 */
    val ROOM_ID_REGEX = Regex("^[A-HJ-KM-NP-Z2-9]{6}$")

    /** 房间号字符集（§10 C17 权威取值）。 */
    const val ROOM_ID_CHARSET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /**
     * 规范化用户输入的房间号：去空白、转大写、剔除非法字符、截断 6 位。
     *
     * @param raw 用户输入。
     */
    fun normalizeRoomId(raw: String): String =
        raw.trim().uppercase()
            .filter { it in ROOM_ID_CHARSET }
            .take(6)

    /** 是否为合法的 6 位房间号。 */
    fun isValidRoomId(roomId: String): Boolean = ROOM_ID_REGEX.matches(roomId)
}
