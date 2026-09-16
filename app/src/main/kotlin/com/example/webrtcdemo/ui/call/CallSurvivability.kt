package com.example.webrtcdemo.ui.call

// ============================================================================
// t68：通话可存活化判定（纯 Kotlin，无 Android 依赖，可 JVM 单测）
// ----------------------------------------------------------------------------
// 真机缺陷（2026-09-15，用户报告）：「通话成功但 UI 仍显示 ICE 失败，视频没断，过一会自动退出房间」。
// 三条根因对应的判定集中在这里（便于单测钉住，也避免散落在 ViewModel 里的 if）：
//   ① 信令 pong 单次丢失即判断线 ⇒ 房间被回收、对端收到 peerLeft、本端重连得 ROOM_NOT_FOUND ⇒ 自动退房；
//   ② peerLeft / ROOM_NOT_FOUND 一律 `hangup()` ⇒ 媒体其实还在流也被踢回首页；
//   ③ `iceDown`（ICE 事实）被用来显示失败文案 ⇒ 媒体健康时界面仍报 ICE 失败。
//
// 与 doc/14 §8.5「口径 B（peerLeft ⇒ 立即挂断）」的关系：captain 2026-09-15 裁定
// **本世代曾连上过**时的 peerLeft 不再挂断（保留房间等待对端重连），本文件是该裁定的落点；
// 首次入房即失败（从未连上过）仍按原语义结束通话。
// ============================================================================

/** peerLeft 的处置（t68）。 */
enum class PeerLeftAction {
    /** 保留通话页并回到「等待对方加入」（房间意图保留，等对端重连回来）。 */
    KEEP_CALL,

    /** 结束通话并回首页（仅用于"从未连上过且没有可用媒体证据"的兜底路径）。 */
    END_CALL,
}

/** 房间丢失（ROOM_NOT_FOUND / ROOM_EXPIRED）的处置（t68）。 */
enum class RoomLostAction {
    /** 可恢复态：保持通话页 + 给出显式「重新创建房间」入口（媒体仍在流时**必须**保持）。 */
    KEEP_CALL,

    /** 终态：结束通话并退出通话页（首次入房就找不到房间等重试无意义的场景）。 */
    END_CALL,
}

/** 信令链路丢失（重连预算耗尽）的处置（t68 收尾 / captain 2026-09-16 指令①）。 */
enum class SignalLostAction {
    /** 保留通话页与会话（媒体可能仍在流），并给出可恢复态入口。 */
    KEEP_CALL,

    /** 结束通话并退出通话页（从未连上过且无媒体证据，避免"半死不活"的页面）。 */
    END_CALL,
}

/**
 * 通话可存活化判定（t68）。
 */
object CallSurvivability {

    /**
     * 媒体存活阈值：远端帧龄小于该值即视为"画面仍在更新"。
     *
     * 取 3 s（验收要求"远端帧新鲜（<3 s）"）：比 t63 的 `FRAME_ALIVE_MS=1.5 s` 宽松，
     * 用于"是否把链路问题呈现为失败"这种低频判定；两者用途不同，故分开定义。
     */
    const val MEDIA_ALIVE_MS = 3_000L

    /**
     * 是否存在可用媒体证据。
     *
     * @param remoteFrameAgeMs 最近远端帧的帧龄（ms；无帧时传 -1 或 [Long.MAX_VALUE]）。
     * @param hasSelectedPair 是否已观察到选中的候选对（`stats_sample mode=P2P|RELAY`）。
     */
    fun mediaAlive(remoteFrameAgeMs: Long, hasSelectedPair: Boolean): Boolean =
        hasSelectedPair || (remoteFrameAgeMs in 0 until MEDIA_ALIVE_MS)

    /**
     * `peerLeft` 的处置。
     *
     * 规则（captain 2026-09-15 裁定）：**曾连上过**（或仍有媒体证据）⇒ 保留通话页 + 回等待态；
     * 从未连上过 ⇒ 维持既有"结束通话"语义（避免停在半死不活的界面）。界面上的「挂断」按钮
     * 始终是可用的显式结束入口。
     *
     * @param everConnected 本世代是否真的连上过（`ConnPhase.CONNECTED` 至少出现过一次）。
     * @param mediaAlive 当前是否有可用媒体证据。
     */
    fun peerLeftAction(everConnected: Boolean, mediaAlive: Boolean): PeerLeftAction =
        if (everConnected || mediaAlive) PeerLeftAction.KEEP_CALL else PeerLeftAction.END_CALL

    /**
     * 房间丢失（`ROOM_NOT_FOUND`/`ROOM_EXPIRED`）的处置。
     *
     * 规则：只要处于"曾经在房内"的语境（断线重连/通话中）**或媒体仍在流动**，就**不得**自动退出
     * 通话页 —— 改为可恢复态并提供显式「重新创建房间」入口；否则（首次入房即找不到房间）按终态处理。
     *
     * @param rejoinContext 是否处于"曾经在房内（通话/重连）"的语境。
     * @param mediaAlive 是否有可用媒体证据（媒体在流时即使语境判定不成立也必须留在通话页）。
     */
    fun roomLostAction(rejoinContext: Boolean, mediaAlive: Boolean): RoomLostAction =
        if (rejoinContext || mediaAlive) RoomLostAction.KEEP_CALL else RoomLostAction.END_CALL

    /**
     * 【t68 收尾】信令链路丢失（重连预算耗尽，`ws_reconnect_give_up` / `ws_rejoin_give_up`）的处置。
     *
     * 规则（captain 2026-09-16 指令①）：服务端按 t67 保留席位 90 s、宽限期内**不发 peerLeft**，
     * 因此"本端重连不上"**不等于**对端离开 —— 处于通话中（本世代曾连上过）或仍有媒体证据时，
     * **任何本地判活都不得据此结束通话或销毁会话**：保留通话页 + 会话，给可恢复态入口
     * （「重新创建房间」/ 手动挂断）。从未连上过且无媒体证据时才结束（避免半死不活的页面）。
     *
     * 注意与 [peerLeftAction] 的区别：那条是**服务端明确通知对端已离开**（宽限期已满）；
     * 本条只是**本端信令链路**的本地判定，证据弱得多，故同样的输入下判据相同但语义不同。
     *
     * @param everConnected 本世代是否真的连上过（`ConnPhase.CONNECTED` 至少出现过一次）。
     * @param mediaAlive 当前是否有可用媒体证据。
     */
    fun signalLostAction(everConnected: Boolean, mediaAlive: Boolean): SignalLostAction =
        if (everConnected || mediaAlive) SignalLostAction.KEEP_CALL else SignalLostAction.END_CALL

    /**
     * 【t71③】信令重连并入会成功后，是否应触发一次 **ICE restart**（复用既有
     * `CallSession.restartIce()`；本判定本身不碰 `webrtc/`）。
     *
     * 为什么需要：信令重连成功只说明**信令层**恢复；用户常做 WiFi↔4G 切换，底层路径变了以后
     * ICE/DTLS 可能仍在 DISCONNECTED/FAILED ⇒ 界面「信令已恢复但画面黑且不再自愈」。
     *
     * 规则（captain 2026-09-16 t71 契约③）：
     *   1. 仅当本世代**曾连上过**（首次建立连接属正常握手，不走这条）；
     *   2. **媒体证据新鲜且存在 selected pair ⇒ 不得重启**（健康通话不受打扰，避免无谓抖动）——
     *      该条优先于第 3 条；
     *   3. 否则：ICE 掉了（[iceDown] = `pc_ice_connection_state` DISCONNECTED/FAILED）**或**没有媒体证据
     *      ⇒ 需要一次 restart。
     *
     * @param everConnected 本世代是否真的连上过（`ConnPhase.CONNECTED` 至少出现过一次）。
     * @param iceDown ICE/传输是否已离开 CONNECTED（`ConnStatus.iceDown`）。
     * @param mediaAlive 是否有可用媒体证据（帧新鲜 <3 s 或已选中候选对）。
     * @param hasSelectedPair 是否观察到选中的候选对。
     */
    fun shouldRestartIceOnRejoin(
        everConnected: Boolean,
        iceDown: Boolean,
        mediaAlive: Boolean,
        hasSelectedPair: Boolean,
    ): Boolean {
        if (!everConnected) return false
        if (mediaAlive && hasSelectedPair) return false
        return iceDown || !mediaAlive
    }

    /**
     * 【t75】重连侧（`Joined` 一侧）**兜底发起**重协商的超时窗口（ms）。
     *
     * 取值 8 s（captain 2026-09-16 t75 契约）：正常情况下服务端给**保持在线的一侧**发 `peerJoined`，
     * 由它立刻发起带 `iceRestart` 的 offer；重连侧只 answer（防 offer glare —— libwebrtc 的 rollback
     * 处理很脆弱）。若在线侧恰好在重连/不在场，重连侧必须在 8 s 后兜底，否则永久停在"信令已恢复、
     * 画面黑"。取 8 s：正常 offer 往返在 1–2 s 量级，8 s 足以排除"offer 正在路上"，又远小于用户
     * 可感知的"卡住"阈值。
     */
    const val REJOIN_OFFER_FALLBACK_MS = 8_000L

    /**
     * 【t75】重连侧是否应**兜底发起**一次带 `iceRestart` 的 offer（超时用**可注入**参数判定，不 sleep）。
     *
     * 规则：仍在等对端 offer（[awaitingPeerOffer]）**且**已等待 ≥ [REJOIN_OFFER_FALLBACK_MS]
     * **且** ICE 未恢复（`iceDown` 或无媒体证据）；若媒体证据新鲜且存在 selected pair ⇒ 不发起
     * （健康通话不受打扰，也避免与在线侧的 offer 撞 glare）。
     *
     * @param awaitingPeerOffer 是否处于"重连入会成功、等待对端 offer"的状态。
     * @param waitedMs 自 `Joined` 起的等待时长（调用方用时间戳计算，便于单测注入）。
     * @param iceDown ICE/传输是否已离开 CONNECTED。
     * @param mediaAlive 是否有可用媒体证据。
     * @param hasSelectedPair 是否观察到选中的候选对。
     */
    fun shouldRejoinerFallbackOffer(
        awaitingPeerOffer: Boolean,
        waitedMs: Long,
        iceDown: Boolean,
        mediaAlive: Boolean,
        hasSelectedPair: Boolean,
    ): Boolean {
        if (!awaitingPeerOffer) return false
        if (waitedMs < REJOIN_OFFER_FALLBACK_MS) return false
        if (mediaAlive && hasSelectedPair) return false
        return iceDown || !mediaAlive
    }

    /**
     * 是否应把一条错误文案透传到界面。
     *
     * t68 硬要求：**媒体存活期间不得显示 ICE 失败** —— 链路层的 ICE 事实（`iceDown`）只用于
     * 失败归因与日志，不驱动用户可见的失败文案。
     *
     * @param message 待呈现的错误文案。
     * @param mediaAlive 是否有可用媒体证据。
     * @return `false` 表示应抑制该文案（调用方落 `ice_down_ui_suppressed age_ms=N` 诊断）。
     */
    fun shouldSurfaceError(message: String, mediaAlive: Boolean): Boolean {
        if (!mediaAlive) return true
        return !isIceErrorText(message)
    }

    /**
     * 文案是否属于"ICE/连接类失败"（t80 抽为共享判定）。
     *
     * 与 [shouldSurfaceError] 同一口径：含 `ICE` / `未连通` / `中继` 字样即视为链路类失败文案。
     */
    fun isIceErrorText(message: String): Boolean {
        val upper = message.uppercase()
        return upper.contains("ICE") || message.contains("未连通") || message.contains("中继")
    }

    /**
     * 【t80】是否应**清除**已显示的 ICE 失败横幅（恢复清除路径）。
     *
     * 真机缺陷：看门狗误报产生的一次性错误文本（`CallUiState.error`）**没有任何清除路径** ——
     * t68 只压住了 `phase=failed` 的相位文案，这条 notice 会一直挂在屏幕上（而通话完全正常）。
     * 规则：横幅确实存在且属于 ICE 类文案时，只要会话已连通（[phaseConnected]）或有媒体证据
     * （[mediaAlive]：帧新鲜 / `down_bps>0`）⇒ 清除；非 ICE 类文案（如"服务端错误: ROOM_FULL"）
     * **不得**被本路径清掉。
     *
     * @param bannerText 当前要展示的错误文案（`null` 表示没有横幅）。
     * @param phaseConnected 连接状态机是否已 `CONNECTED`。
     * @param mediaAlive 是否有可用媒体证据。
     * @return `true` 表示应清除该横幅。
     */
    fun shouldClearIceError(bannerText: String?, phaseConnected: Boolean, mediaAlive: Boolean): Boolean {
        if (bannerText.isNullOrBlank()) return false
        if (!isIceErrorText(bannerText)) return false
        return phaseConnected || mediaAlive
    }
}

/**
 * 可恢复态（t68）：房间被服务端回收等"**不退出通话页**、由用户显式重建"的状态。
 *
 * 为什么单独建模：`CallUiState`（`model/` 包）不属于本任务改动范围，故新增状态经由
 * `CallViewModel.recoverable` 这个**新 StateFlow** 暴露给通话页（不扩既有 UI 模型）。
 *
 * @property reason 触发原因（服务端错误码，如 `ROOM_NOT_FOUND`）。
 * @property mediaAlive 触发时是否有可用媒体证据（决定文案措辞：媒体仍在时明确"画面仍在"）。
 * @property notice 用户可见提示。
 */
data class RecoverableState(
    val reason: String,
    val mediaAlive: Boolean,
    val notice: String,
)
