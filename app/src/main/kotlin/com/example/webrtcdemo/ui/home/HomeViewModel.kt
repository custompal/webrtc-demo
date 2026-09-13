package com.example.webrtcdemo.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.webrtcdemo.config.AppConfig
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.nat.NatTypeRepository
import com.example.webrtcdemo.signaling.ConnectionState
import com.example.webrtcdemo.signaling.SignalingClient
import com.example.webrtcdemo.signaling.SignalingCodec
import com.example.webrtcdemo.signaling.SignalingHolder
import com.example.webrtcdemo.signaling.SignalingMessage
import com.example.webrtcdemo.webrtc.IceServerCache
import com.example.webrtcdemo.webrtc.IceServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ============================================================================
// 首页 ViewModel（doc/14 §2.1；界面契约 doc/10 §3.1/§5.1）
// ----------------------------------------------------------------------------
// 职责：房间号输入规范化、创建/加入、连接中状态、本端 NAT 展示、错误提示。
// 信令：通过 [SignalingHolder] 复用连接（导航到通话页后由 CallViewModel 接管 listener）。
//
// 与 doc/10 §3.1 的差异（受控，已在报告登记）：
//   doc/10 写「app 启动后异步探测 NAT」；但 §7.5 冻结「ICE server 只能来自 created/joined 下发」，
//   在收到 created/joined 之前拿不到 STUN 服务器，故本端 NAT 探测在**收到 created/joined 时**启动，
//   首页在此之前显示「检测中…」。
// ============================================================================

/**
 * 首页 ViewModel。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application), SignalingClient.Listener {

    /**
     * 首页 UI 状态。
     *
     * @property roomIdInput 已规范化（大写、6 位、过滤非法字符）的房间号输入。
     * @property isConnecting 正在连接/等待房间响应。
     * @property connectionState 信令状态中文名。
     * @property natType 本端 NAT（线上枚举字符串；空串表示尚未探测）。
     * @property detectingNat 是否正在探测 NAT。
     * @property natDetail native 返回的证据串（诊断用）。
     * @property error 错误提示。
     * @property granted 运行时权限是否已授予（未授予时禁用创建/加入，§7.6）。
     * @property navigateToCall 是否应导航到通话页。
     */
    data class UiState(
        val roomIdInput: String = "",
        val isConnecting: Boolean = false,
        val connectionState: String = ConnectionState.DISCONNECTED.label,
        val natType: String = "",
        val detectingNat: Boolean = false,
        val natDetail: String = "",
        val error: String? = null,
        val granted: Boolean = false,
        val navigateToCall: Boolean = false,
        val roomId: String = "",
        val role: String = "",
    )

    private val _uiState = MutableStateFlow(UiState())

    /** 首页状态流。 */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var client: SignalingClient? = null

    private var createRequested = false

    init {
        // 本端 NAT 结果（native 回调 → repository）
        viewModelScope.launch {
            NatTypeRepository.localNat.collect { nat ->
                _uiState.update { it.copy(natType = if (NatTypeRepository.detecting.value) "" else nat.wire) }
            }
        }
        viewModelScope.launch {
            NatTypeRepository.detecting.collect { detecting ->
                _uiState.update { it.copy(detectingNat = detecting) }
            }
        }
        viewModelScope.launch {
            NatTypeRepository.localDetail.collect { detail ->
                _uiState.update { it.copy(natDetail = detail) }
            }
        }
    }

    // ============================ 用户操作 ============================

    /** 房间号输入变化（自动大写 + 过滤非法字符 + 截断 6 位，§8.2）。 */
    fun onRoomIdInputChange(raw: String) {
        _uiState.update { it.copy(roomIdInput = SignalingCodec.normalizeRoomId(raw), error = null) }
    }

    /** 运行时权限结果（§7.6）。 */
    fun onPermissionResult(granted: Boolean) {
        AppLog.i(TAG, "permission_result", mapOf("granted" to granted.toString()))
        _uiState.update { it.copy(granted = granted, error = if (granted) null else "缺少相机/麦克风权限，无法创建或加入会议") }
    }

    /** 创建会议（doc/10 §3.1）。 */
    fun onCreate() {
        createRequested = true
        startSession(roomId = "")
    }

    /** 加入会议（doc/10 §3.1）。 */
    fun onJoin() {
        val roomId = _uiState.value.roomIdInput
        if (!SignalingCodec.isValidRoomId(roomId)) {
            _uiState.update { it.copy(error = "请输入 6 位房间号（不含 I/L/O/0/1）") }
            return
        }
        createRequested = false
        startSession(roomId = roomId)
    }

    /** 已消费导航事件（避免重复导航）。 */
    fun onNavigationConsumed() {
        _uiState.update { it.copy(navigateToCall = false) }
    }

    // ============================ 内部实现 ============================

    private fun startSession(roomId: String) {
        val app = getApplication<Application>()
        if (!_uiState.value.granted) {
            _uiState.update { it.copy(error = "请先授予相机与麦克风权限") }
            return
        }
        val url = AppConfig.signalingUrl(app)
        AppLog.i(TAG, "session_start", mapOf("url" to url, "create" to createRequested.toString(), "room" to roomId.ifEmpty { "-" }))
        val connection = SignalingHolder.getOrCreate(url)
        connection.listener = this
        client = connection
        _uiState.update { it.copy(isConnecting = true, error = null) }
        if (createRequested) {
            connection.createRoom()
        } else {
            connection.joinRoom(roomId)
        }
    }

    // ============================ 信令回调 ============================

    override fun onStateChanged(state: ConnectionState) {
        _uiState.update {
            it.copy(
                connectionState = state.label,
                isConnecting = state == ConnectionState.CONNECTING || state == ConnectionState.WAITING,
            )
        }
    }

    override fun onMessage(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Created -> onRoomReady(
                roomId = message.roomId,
                role = ROLE_HOST,
                ice = IceServerConfig(
                    stunUrl = message.stunUrl,
                    turnUrl = message.turnUrl,
                    turnUsername = message.turnUsername,
                    turnCredential = message.turnCredential,
                ),
            )

            is SignalingMessage.Joined -> onRoomReady(
                roomId = message.roomId,
                role = ROLE_JOINER,
                ice = IceServerConfig(
                    stunUrl = message.stunUrl,
                    turnUrl = message.turnUrl,
                    turnUsername = message.turnUsername,
                    turnCredential = message.turnCredential,
                ),
            )

            is SignalingMessage.ServerError -> {
                AppLog.e(TAG, "server_error", mapOf("code" to message.code))
                _uiState.update { it.copy(isConnecting = false, error = "服务端错误: ${message.code}") }
            }

            else -> Unit
        }
    }

    private fun onRoomReady(roomId: String, role: String, ice: IceServerConfig) {
        // §7.5：ICE server 只能来自 created/joined —— 缓存给通话页，并在此启动本端 NAT 探测
        IceServerCache.put(ice)
        NatTypeRepository.startLocalDetection(ice.stunUrl)
        _uiState.update {
            it.copy(
                isConnecting = false,
                navigateToCall = true,
                roomId = roomId,
                role = role,
                error = null,
            )
        }
    }

    override fun onTransportFailure(reason: String, cause: Throwable?) {
        _uiState.update { it.copy(isConnecting = false, error = "信令连接失败: $reason") }
    }

    private companion object {
        const val TAG = "signaling"
        const val ROLE_HOST = "host"
        const val ROLE_JOINER = "joiner"
    }
}
