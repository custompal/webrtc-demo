package com.example.webrtcdemo.encoder

import com.example.webrtcdemo.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ============================================================================
// 自研编码「自动降级兜底」的**执行器**（t87 验收第 2/4 条）
// ----------------------------------------------------------------------------
// 数据流（全部在 Kotlin 侧，不改 C++）：
//   编码线程（libwebrtc）── Vp9VideoEncoder.encode() ──► onEncodeResult(单帧耗时, 是否出帧)
//        │（只做计数：一把极短锁，无 I/O、无分配大对象）
//        ▼ 满 1 s
//   单线程后台执行器 enc-fallback ──► 聚合出 EncodeWindowSample ──► EncoderFallbackPolicy.decide
//        │                                            │
//        │ 每窗口 1 条                                 └─ SWITCH_TO_DEFAULT
//        ▼                                                  ▼
//   encoder_fallback_probe(ok=1 …)                  requestInCallSwitch()
//                                                        │（selector 在 onAvailableBitrate 取走请求）
//                                                        ▼
//                                        Vp9VideoEncoderFactory.createEncoder(VP9) → 默认实现
//                                                        │
//                                    onEncoderCreated(DEFAULT) ──► encoder_fallback_applied
//                                    mech=in_call_switch（5 s 内未确认 ⇒ mech=next_call + pending）
//
// 关键约束（doc/14 §5.4）：
//   · `onEncodeResult` 在**编码线程**被调用 —— 内部只做计数与一次 `executor.execute`，
//     不做文件/网络 I/O，不等待主线程，不排序/不打日志（排序与日志都在后台线程做）；
//   · 任何异常都不允许冒泡到编码线程（`runCatching` 包住后台投递）。
// ============================================================================

/**
 * 自动降级执行器（进程内单例）。
 *
 * 与 [EncoderFallbackPolicy] 的分工：本对象只负责「采样 → 聚合 → 调用纯判据 → 执行/记录」，
 * 判定逻辑本身全部在纯函数里，便于离线单测与真机日志对账。
 */
object EncoderFallbackController {

    private const val TAG = "encoder"

    /** 采样窗口长度（毫秒）：正常 1 s 出一个窗口样本。 */
    private const val WINDOW_MS = 1_000L

    /** 单个窗口最多记录的单帧耗时数（1 s @ 60 fps = 60，300 足够；超出只计数不采样）。 */
    private const val MAX_SAMPLES_PER_WINDOW = 300

    /** 通话内切换请求的确认超时：超时即退化为「下次通话生效」（不中断通话）。 */
    private const val IN_CALL_CONFIRM_TIMEOUT_MS = 5_000L

    /** 编码器在 `setRateAllocation` 之前未上报请求帧率时用的兜底值（不用于触发判定）。 */
    private const val UNKNOWN_FPS = 0

    /** 判据配置（真机复测按此口径核对；`window_s`/阈值都在 [EncoderFallbackPolicy.Config]）。 */
    val config: EncoderFallbackPolicy.Config = EncoderFallbackPolicy.Config()

    // ---------------------------------------------------------------- 外部配置

    @Volatile
    private var mode: EncoderOverrideMode = EncoderOverrideMode.AUTO

    @Volatile
    private var fallbackEnabled: Boolean = true

    // ---------------------------------------------------------------- 运行状态

    /**
     * 「本次通话内切换未获确认，下次通话直接采用默认实现」标记（t87 验收第 2 条的退化路径）。
     *
     * 语义：`true` 表示**已经在本次通话内决定降级、但通话内未确认生效**；
     * 下一次 [onCallStarted] 会把实现直接置为 [EncoderImpl.DEFAULT] 并清除本标记。
     */
    @Volatile
    var pendingFallbackForNextCall: Boolean = false
        private set

    /** 当前生效实现（AUTO 模式下由状态机推进；三态覆盖见 [EncoderFallbackPolicy.resolveImpl]）。 */
    @Volatile
    private var activeImpl: EncoderImpl = EncoderImpl.SELF

    /** 是否已向 selector 发出通话内切换请求（等待确认）。 */
    @Volatile
    private var switchRequested: Boolean = false

    /** 请求是否已被 selector 取走（避免反复重建 send stream）。 */
    @Volatile
    private var switchRequestConsumed: Boolean = false

    /** 是否已确认切换到默认实现。 */
    @Volatile
    private var switchConfirmed: Boolean = false

    /** 上次切换请求时刻（单调毫秒）。 */
    @Volatile
    private var switchRequestAtMs: Long = -1L

    /** 本次降级生效的机制（`in_call_switch` / `next_call`），用于 `encoder_fallback_applied`。 */
    @Volatile
    private var appliedMech: String? = null

    /** 采样是否开启（通话中且用自研编码时为 true；`onEncodeResult` 的快速闸门）。 */
    @Volatile
    private var probeActive: Boolean = false

    /** 请求帧率（`initEncode.maxFramerate` / `setRateAllocation` 上报）。 */
    @Volatile
    private var requestedFps: Int = UNKNOWN_FPS

    /** 当前实现名（真实类名，如 `SelfVp9Libvpx` / `MediaCodecVideoEncoder`）。 */
    @Volatile
    private var implName: String = Vp9VideoEncoder.IMPL_NAME

    /** selector 上报的当前编解码名（`VP9`），仅用于诊断对账（不参与判定）。 */
    @Volatile
    private var currentCodecName: String = "-"

    /** 上一次决策的原因（UI 展示）。 */
    @Volatile
    private var lastReason: String = "init"

    /** 最近一个窗口样本（UI/日志展示）。 */
    @Volatile
    private var lastSample: EncodeWindowSample? = null

    // ---------------------------------------------------------------- 采样聚合

    private val lock = Any()

    /** 窗口内每帧耗时（ms），样本数见 [sampleCount]。 */
    private var windowSamples = IntArray(MAX_SAMPLES_PER_WINDOW)

    private var sampleCount = 0

    private var producedCount = 0

    private var noOutputCount = 0

    private var windowStartMs = -1L

    /** 滚动窗口历史（最多 [EncoderFallbackPolicy.Config.windowSeconds] 个）。 */
    private val history = ArrayDeque<EncodeWindowSample>()

    /** 判据状态（切换次数/上次切换时刻）。 */
    private var policyState = EncoderFallbackPolicy.State()

    /** 后台单线程执行器：窗口聚合/判据/日志都在这里，**绝不占用编码线程**。 */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "enc-fallback").apply { isDaemon = true }
        }
    }

    /** 可注入时钟（单测用；默认单调毫秒）。 */
    @Volatile
    private var timeSource: () -> Long = { System.nanoTime() / 1_000_000L }

    private val _uiState = MutableStateFlow(
        UiState(
            impl = EncoderImpl.SELF,
            implName = Vp9VideoEncoder.IMPL_NAME,
            mode = EncoderOverrideMode.AUTO,
            fallbackEnabled = true,
            pendingNextCall = false,
            reason = "init",
            encodeP95Ms = 0,
            outFps = 0.0,
            requestedFps = 0,
            switchesThisCall = 0,
        )
    )

    /** 诊断页/通话页展示用的只读状态。 */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * 诊断页展示状态。
     *
     * @param impl 当前实现标识。
     * @param implName 当前实现名（真实类名）。
     * @param mode 三态覆盖。
     * @param fallbackEnabled 自动兜底开关。
     * @param pendingNextCall 是否已标记「下次通话生效默认实现」。
     * @param reason 最近一次决策原因 / 生效原因。
     * @param encodeP95Ms 最近窗口单帧耗时 p95（ms）。
     * @param outFps 最近窗口产出帧率。
     * @param requestedFps 最近窗口请求帧率。
     * @param switchesThisCall 本次通话已发起切换次数。
     * @param mech 上次降级生效机制（`in_call_switch` / `next_call` / `-`）。
     */
    data class UiState(
        val impl: EncoderImpl,
        val implName: String,
        val mode: EncoderOverrideMode,
        val fallbackEnabled: Boolean,
        val pendingNextCall: Boolean,
        val reason: String,
        val encodeP95Ms: Int,
        val outFps: Double,
        val requestedFps: Int,
        val switchesThisCall: Int,
        val mech: String = "-",
    )

    // ---------------------------------------------------------------- 配置入口

    /**
     * 应用外部配置（启动时由 `WebRtcEngine` 从 `AppConfig` 读入；诊断页改动后立即调用）。
     *
     * @param mode 三态覆盖。
     * @param fallbackEnabled 自动兜底开关（默认 true）。
     */
    fun configure(mode: EncoderOverrideMode, fallbackEnabled: Boolean) {
        this.mode = mode
        this.fallbackEnabled = fallbackEnabled
        logI("encoder_fallback_config", mapOf("mode" to mode.wire, "fallback" to fallbackEnabled.toString()))
        publishState()
    }

    // ---------------------------------------------------------------- 通话生命周期

    /**
     * 一次通话（一代 `CallSession`）开始：复位窗口/判据状态，并应用「下次通话生效」标记。
     *
     * 调用点：`CallViewModel.beginGeneration`（首呼与一键重试都算新一代）。
     * **不涉及 `webrtc/CallSession.kt` 的会话生命周期**（本任务 inScope 内完成）。
     *
     * @param reason 调用原因（`init`/`retry`，仅用于日志）。
     */
    fun onCallStarted(reason: String = "init") {
        val pending = pendingFallbackForNextCall
        synchronized(lock) {
            history.clear()
            policyState = EncoderFallbackPolicy.State()
            windowSamples = IntArray(MAX_SAMPLES_PER_WINDOW)
            sampleCount = 0
            producedCount = 0
            noOutputCount = 0
            windowStartMs = -1L
        }
        lastSample = null
        switchRequested = false
        switchRequestConsumed = false
        switchConfirmed = false
        switchRequestAtMs = -1L
        probeActive = true
        requestedFps = UNKNOWN_FPS
        // 三态覆盖优先；AUTO 下若上次通话已标记 pending ⇒ 本次直接用默认实现（不中断通话、不丢房间）
        val resolved = when (mode) {
            EncoderOverrideMode.SELF -> EncoderImpl.SELF
            EncoderOverrideMode.DEFAULT -> EncoderImpl.DEFAULT
            EncoderOverrideMode.AUTO -> if (pending) EncoderImpl.DEFAULT else EncoderImpl.SELF
        }
        activeImpl = resolved
        appliedMech = when {
            resolved != EncoderImpl.DEFAULT -> null
            mode == EncoderOverrideMode.DEFAULT -> "in_call_switch"
            else -> "next_call"
        }
        lastReason = when {
            resolved != EncoderImpl.DEFAULT -> "call_start_self"
            mode == EncoderOverrideMode.DEFAULT -> "manual_default"
            else -> "pending_for_next_call"
        }
        if (resolved == EncoderImpl.DEFAULT) {
            // pending 已兑现 ⇒ 清除标记（真实创建时会再落一条 confirmed=1）
            pendingFallbackForNextCall = false
            logI(
                "encoder_fallback_applied",
                mapOf(
                    "impl" to EncoderImpl.DEFAULT.wire,
                    "mech" to (appliedMech ?: "next_call"),
                    "confirmed" to "0",
                    "reason" to lastReason,
                ),
            )
        }
        logI(
            "encoder_fallback_call_start",
            mapOf(
                "reason" to reason,
                "mode" to mode.wire,
                "fallback" to fallbackEnabled.toString(),
                "impl" to resolved.wire,
                "pending_before" to pending.toString(),
            ),
        )
        publishState()
    }

    /** 通话结束：停止采样（不清 pending 标记 —— 它要留到下一次通话兑现）。 */
    fun onCallEnded(reason: String = "hangup") {
        probeActive = false
        logI(
            "encoder_fallback_call_end",
            mapOf(
                "reason" to reason,
                "impl" to activeImpl.wire,
                "switches" to policyState.switchesThisCall.toString(),
                "pending_next_call" to pendingFallbackForNextCall.toString(),
            ),
        )
        publishState()
    }

    // ---------------------------------------------------------------- 编码线程入口

    /**
     * 编码器初始化（编码线程调用，`Vp9VideoEncoder.initEncode` 成功后）。
     *
     * @param impl 实现名（自研固定 `SelfVp9Libvpx`）。
     * @param width 宽。
     * @param height 高。
     * @param maxFramerate 初始请求帧率。
     */
    fun onEncoderInit(impl: String, width: Int, height: Int, maxFramerate: Int) {
        implName = impl
        requestedFps = maxFramerate
        probeActive = true
        logI(
            "encoder_fallback_probe_start",
            mapOf(
                "impl" to impl,
                "w" to width.toString(),
                "h" to height.toString(),
                "requested_fps" to maxFramerate.toString(),
                "window_s" to config.windowSeconds.toString(),
            ),
        )
    }

    /** 请求帧率变化（编码线程调用，`setRateAllocation`）。 */
    fun onRequestedFps(framerate: Int) {
        if (framerate > 0) requestedFps = framerate
    }

    /**
     * 单帧编码结果（**编码线程**调用；必须廉价）。
     *
     * 只做：计数 + 满 1 s 时把窗口快照投递到后台线程。加锁区间内不做排序/日志/分配大对象。
     *
     * @param elapsedMs 该帧 `System.nanoTime()` 包裹 JNI 调用的耗时（ms）。
     * @param produced 是否产出了非空编码帧（`encode()` 返回 OK）。
     */
    fun onEncodeResult(elapsedMs: Long, produced: Boolean) {
        if (!probeActive) return
        val snapshot: WindowSnapshot? = synchronized(lock) {
            if (windowStartMs < 0L) windowStartMs = nowMs()
            if (sampleCount < MAX_SAMPLES_PER_WINDOW) {
                windowSamples[sampleCount] = elapsedMs.toInt()
                sampleCount++
            }
            if (produced) producedCount++ else noOutputCount++
            val elapsed = nowMs() - windowStartMs
            if (elapsed < WINDOW_MS) {
                null
            } else {
                val snap = WindowSnapshot(
                    samplesMs = windowSamples.copyOf(sampleCount),
                    producedFrames = producedCount,
                    noOutputFrames = noOutputCount,
                    requestedFps = requestedFps,
                    windowMs = elapsed,
                    impl = activeImpl,
                )
                sampleCount = 0
                producedCount = 0
                noOutputCount = 0
                windowStartMs = nowMs()
                snap
            }
        }
        if (snapshot != null) {
            // 后台结算里的任何异常都不得静默消失（否则表现为“策略不生效”且无从查证）：
            // 捕获后落 `encoder_fallback_error` 并把原因带进 UI 状态，便于真机一眼定位。
            runCatching {
                executor.execute {
                    runCatching { evaluateWindow(snapshot) }.onFailure { t -> onEvaluateFailed(t) }
                }
            }
        }
    }

    /**
     * 后台结算失败：记录并暴露原因（**不冒泡、不中断通话**）。
     *
     * @param t 异常。
     */
    private fun onEvaluateFailed(t: Throwable) {
        lastReason = "policy_error:${t.javaClass.simpleName}:${t.message ?: "-"}"
        logW(
            "encoder_fallback_error",
            mapOf("ex" to t.javaClass.name, "msg" to (t.message ?: "-")),
        )
        publishState()
    }

    // ---------------------------------------------------------------- 工厂/selector 查询

    /**
     * 工厂创建编码器时应使用的实现（`Vp9VideoEncoderFactory.createEncoder` 调用）。
     *
     * 语义：
     *   · 三态覆盖直接决定（`SELF`/`DEFAULT`）；
     *   · `AUTO`：已标记 pending、或正在等待通话内切换确认（[switchRequested] 且未确认）
     *     ⇒ [EncoderImpl.DEFAULT] —— 后者正是「native 因 selector 请求而重建编码器」时我们
     *     需要返回默认实现的那一刻，它同时充当**通话内切换的确认信号**。
     *
     * @return 目标实现。
     */
    fun implForCreate(): EncoderImpl {
        if (mode == EncoderOverrideMode.SELF) return EncoderImpl.SELF
        if (mode == EncoderOverrideMode.DEFAULT) return EncoderImpl.DEFAULT
        if (pendingFallbackForNextCall) return EncoderImpl.DEFAULT
        if (switchRequested && !switchConfirmed) return EncoderImpl.DEFAULT
        return activeImpl
    }

    /** 当前生效实现（诊断用）。 */
    fun currentImpl(): EncoderImpl = EncoderFallbackPolicy.resolveImpl(mode, activeImpl)

    /**
     * selector 取走一次「通话内切换」请求。
     *
     * 只成功一次：避免每次码率更新都返回格式导致 send stream 反复重建。
     *
     * @return true = 本次应返回默认实现对应的编码格式以触发切换。
     */
    fun takeSwitchRequest(): Boolean {
        if (!switchRequested || switchRequestConsumed || switchConfirmed) return false
        switchRequestConsumed = true
        logI(
            "encoder_fallback_switch_requested",
            mapOf(
                "mech" to "in_call_switch",
                "waited_ms" to (nowMs() - switchRequestAtMs).toString(),
            ),
        )
        return true
    }

    /**
     * 工厂成功创建了默认实现（默认编码器实例）时的回调。
     *
     * @param createdImplName 默认编码器的实现名（`VideoEncoder.implementationName`）。
     * @param createdImpl 目标实现（正常情况下为 [EncoderImpl.DEFAULT]）。
     */
    fun onEncoderCreated(createdImpl: EncoderImpl, createdImplName: String?) {
        implName = createdImplName ?: implName
        if (createdImpl != EncoderImpl.DEFAULT) return
        val mech = appliedMech ?: "in_call_switch"
        activeImpl = EncoderImpl.DEFAULT
        switchConfirmed = true
        appliedMech = mech
        pendingFallbackForNextCall = false
        lastReason = if (mech == "next_call") "pending_for_next_call" else lastReason
        logI(
            "encoder_fallback_applied",
            mapOf(
                "impl" to implName,
                "mech" to mech,
                "confirmed" to "1",
                "source" to "factory_create",
            ),
        )
        publishState()
    }

    /** 默认实现不可用（工厂返回 null）：撤销降级意图，避免“想切却切不动”的悬挂状态。 */
    fun onDefaultUnavailable(reason: String) {
        switchRequested = false
        switchRequestConsumed = true
        pendingFallbackForNextCall = false
        activeImpl = EncoderImpl.SELF
        appliedMech = null
        lastReason = EncoderFallbackPolicy.REASON_DEFAULT_UNAVAILABLE
        logW(
            "encoder_fallback_unavailable",
            mapOf("reason" to reason, "impl" to EncoderImpl.SELF.wire),
        )
        publishState()
    }

    /** 编码实现名上报（selector 的 `onCurrentEncoder`，用于日志/UI 对账）。 */
    fun onCurrentEncoder(codecName: String) {
        if (codecName.isNotBlank()) currentCodecName = codecName
        logI(
            "encoder_fallback_current",
            mapOf("impl" to implName, "codec" to currentCodecName, "mode" to mode.wire),
        )
        publishState()
    }

    // ---------------------------------------------------------------- 内部：后台评估

    /** 一个已结算窗口（从编码线程移交后台线程的不可变快照）。 */
    private data class WindowSnapshot(
        val samplesMs: IntArray,
        val producedFrames: Int,
        val noOutputFrames: Int,
        val requestedFps: Int,
        val windowMs: Long,
        val impl: EncoderImpl,
    )

    /**
     * 结算一个窗口（**后台线程**）：聚合 → 判据 → 记录/执行。
     *
     * @param snapshot 已在编码线程结算的窗口快照。
     */
    private fun evaluateWindow(snapshot: WindowSnapshot) {
        val sample = EncodeWindowSample(
            encodeP95Ms = EncoderFallbackPolicy.p95(snapshot.samplesMs),
            producedFrames = snapshot.producedFrames,
            noOutputFrames = snapshot.noOutputFrames,
            requestedFps = snapshot.requestedFps,
            windowMs = snapshot.windowMs,
            impl = snapshot.impl,
        )
        lastSample = sample
        val currentImpl = currentImpl()
        if (currentImpl != EncoderImpl.SELF) {
            // 已用默认实现：不再做降级判定（避免来回切），只保留一条 probe 说明策略在跑
            lastReason = "impl_default"
            logI(
                "encoder_fallback_probe",
                mapOf(
                    "ok" to "1",
                    "action" to "skip",
                    "reason" to "impl_default",
                    "impl" to currentImpl.wire,
                    "encode_p95_ms" to sample.encodeP95Ms.toString(),
                    "out_fps" to EncoderFallbackPolicy.formatFps(sample.outFps),
                    "requested_fps" to sample.requestedFps.toString(),
                    "window_s" to ((sample.windowMs / 1000L).toInt()).toString(),
                ),
            )
            publishState()
            return
        }
        if (mode == EncoderOverrideMode.SELF || !fallbackEnabled) {
            // 手动「强制自研」或用户关闭兜底：只留 probe 证明策略在跑，不做任何切换判定
            lastReason = if (mode == EncoderOverrideMode.SELF) {
                "manual_self"
            } else {
                EncoderFallbackPolicy.REASON_FALLBACK_DISABLED
            }
            logI("encoder_fallback_probe", probeFields(sample, ok = true, action = "disabled"))
            logDecisionIfChanged(
                EncoderFallbackPolicy.Decision(
                    action = EncoderFallbackPolicy.Action.KEEP_SELF,
                    reason = lastReason,
                    encodeP95Ms = sample.encodeP95Ms,
                    outFps = sample.outFps,
                    requestedFps = sample.requestedFps,
                    windowS = (sample.windowMs / 1000L).toInt(),
                    badSeconds = if (EncoderFallbackPolicy.isBadSecond(sample, config)) 1 else 0,
                    noOutputFrames = sample.noOutputFrames,
                )
            )
            publishState()
            return
        }
        val now = nowMs()
        val decision = synchronized(lock) {
            history.addLast(sample)
            while (history.size > config.windowSeconds) history.removeFirst()
            val decided = EncoderFallbackPolicy.decide(history.toList(), policyState, now, config)
            if (decided.action == EncoderFallbackPolicy.Action.SWITCH_TO_DEFAULT) {
                // 立即记账（幂等上界与滞回都据此生效），实际生效由 onEncoderCreated 确认
                policyState = EncoderFallbackPolicy.State(
                    switchesThisCall = policyState.switchesThisCall + 1,
                    lastSwitchAtMs = now,
                )
            }
            decided
        }
        lastReason = decision.reason
        logI("encoder_fallback_probe", probeFields(sample, ok = true, action = null))
        logDecisionIfChanged(decision)
        if (decision.action == EncoderFallbackPolicy.Action.SWITCH_TO_DEFAULT) {
            requestInCallSwitch(decision)
        } else {
            checkInCallConfirmTimeout(now)
        }
        publishState()
    }

    /** probe 字段（每个窗口一条；`ok=1` 表示策略仍在跑）。 */
    private fun probeFields(sample: EncodeWindowSample, ok: Boolean, action: String?): Map<String, String> =
        linkedMapOf<String, String>(
            "ok" to if (ok) "1" else "0",
            "impl" to currentImpl().wire,
            "encode_p95_ms" to sample.encodeP95Ms.toString(),
            "out_fps" to EncoderFallbackPolicy.formatFps(sample.outFps),
            "requested_fps" to sample.requestedFps.toString(),
            "window_s" to (sample.windowMs / 1000L).toString(),
            "produced" to sample.producedFrames.toString(),
            "no_output" to sample.noOutputFrames.toString(),
            "bad_s" to (if (EncoderFallbackPolicy.isBadSecond(sample, config)) 1 else 0).toString(),
            "switches" to policyState.switchesThisCall.toString(),
            "reason" to lastReason,
        ).also { fields -> if (action != null) fields["action"] = action }

    /** 决策日志：仅在动作/原因变化或首次判定时落一条（避免与 1 Hz probe 重复刷屏）。 */
    private var lastDecisionKey: String? = null

    private fun logDecisionIfChanged(decision: EncoderFallbackPolicy.Decision) {
        val key = "${decision.action}:${decision.reason}:${decision.windowS}"
        if (key == lastDecisionKey && decision.action != EncoderFallbackPolicy.Action.SWITCH_TO_DEFAULT) return
        lastDecisionKey = key
        val fields = LinkedHashMap<String, String>(decision.logFields())
        fields["impl"] = currentImpl().wire
        fields["mode"] = mode.wire
        fields["fallback"] = fallbackEnabled.toString()
        logI("encoder_fallback_decision", fields)
    }

    /** 发起通话内切换（selector 会在下一次 `onAvailableBitrate` 取走该请求）。 */
    private fun requestInCallSwitch(decision: EncoderFallbackPolicy.Decision) {
        switchRequested = true
        switchRequestConsumed = false
        switchRequestAtMs = nowMs()
        logW(
            "encoder_fallback_requested",
            mapOf(
                "mech" to "in_call_switch",
                "reason" to decision.reason,
                "encode_p95_ms" to decision.encodeP95Ms.toString(),
                "out_fps" to EncoderFallbackPolicy.formatFps(decision.outFps),
                "requested_fps" to decision.requestedFps.toString(),
                "window_s" to decision.windowS.toString(),
            ),
        )
    }

    /**
     * 通话内切换确认超时 ⇒ 退化为「下次通话生效」（**不中断通话、不丢房间**）。
     *
     * 触发条件：已请求、未确认、且已等待 ≥ [IN_CALL_CONFIRM_TIMEOUT_MS]。
     */
    private fun checkInCallConfirmTimeout(nowMs: Long) {
        if (!switchRequested || switchConfirmed) return
        if (switchRequestAtMs < 0L || nowMs - switchRequestAtMs < IN_CALL_CONFIRM_TIMEOUT_MS) return
        switchRequested = false
        pendingFallbackForNextCall = true
        activeImpl = EncoderImpl.SELF
        appliedMech = "next_call"
        lastReason = "in_call_not_confirmed_pending_next_call"
        logW(
            "encoder_fallback_applied",
            mapOf(
                "impl" to EncoderImpl.DEFAULT.wire,
                "mech" to "next_call",
                "confirmed" to "0",
                "reason" to "in_call_not_confirmed",
                "waited_ms" to (nowMs - switchRequestAtMs).toString(),
                "pending_next_call" to "1",
            ),
        )
        publishState()
    }

    /** 发布 UI 状态（StateFlow 线程安全；后台线程调用无妨）。 */
    private fun publishState() {
        val sample = lastSample
        _uiState.value = UiState(
            impl = currentImpl(),
            implName = implName,
            mode = mode,
            fallbackEnabled = fallbackEnabled,
            pendingNextCall = pendingFallbackForNextCall,
            reason = lastReason,
            encodeP95Ms = sample?.encodeP95Ms ?: 0,
            outFps = sample?.outFps ?: 0.0,
            requestedFps = sample?.requestedFps ?: 0,
            switchesThisCall = policyState.switchesThisCall,
            mech = appliedMech ?: "-",
        )
    }

    /** 单调毫秒（单测可注入）。 */
    private fun nowMs(): Long = timeSource()

    // ---------------------------------------------------------------- 内部工具

    /**
     * 安全 Info 日志：**日志失败绝不允许影响编码/判据**。
     *
     * 两层意义：
     *   1. 生产：编码线程上的任何异常都不能冒泡回 libwebrtc（doc/14 §5.4）；
     *   2. 离线单测：纯 JVM 下 `android.os.Process.myPid()` 是 stub（`FileLogger` 静态初始化失败），
     *      不包裹会让判据/机制单测直接红 —— 包住后日志在测试环境静默降级，判据照常可测。
     *
     * @param event 事件名。
     * @param fields 字段。
     */
    private fun logI(event: String, fields: Map<String, String>) {
        runCatching { AppLog.i(TAG, event, fields) }
    }

    /**
     * 安全 Warn 日志（语义同 [logI]）。
     *
     * @param event 事件名。
     * @param fields 字段。
     */
    private fun logW(event: String, fields: Map<String, String>) {
        runCatching { AppLog.w(TAG, event, fields) }
    }

    // ---------------------------------------------------------------- 测试支持

    /**
     * 等待后台结算任务全部跑完（**仅供离线单测**）。
     *
     * 用于消除「编码线程侧已喂完窗口、后台线程尚未结算」的时序不确定性：
     * 提交一个屏障任务到同一个单线程执行器并等待其完成，即保证此前投递的窗口都已结算。
     *
     * @param timeoutMs 等待上限（ms）。
     */
    fun awaitIdleForTest(timeoutMs: Long = 3_000L) {
        runCatching { executor.submit(Runnable { }).get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * 注入时钟（**仅供离线单测**，生产不调用）。
     *
     * @param source 返回单调毫秒的函数。
     */
    fun setTimeSourceForTest(source: () -> Long) {
        timeSource = source
    }

    /**
     * 复位内部状态（**仅供离线单测**，生产不调用）。
     *
     * @param mode 三态覆盖初值。
     * @param fallbackEnabled 兜底开关初值。
     */
    fun resetForTest(mode: EncoderOverrideMode = EncoderOverrideMode.AUTO, fallbackEnabled: Boolean = true) {
        // 先把上一轮用例投递的结算任务全部跑完（单线程执行器 ⇒ 提交一个屏障任务并等待它）。
        // 否则上一轮残留的窗口会在本轮复位之后结算，把状态/原因覆盖成上一轮的结论（测试间串扰）。
        runCatching { executor.submit(Runnable { }).get(3L, TimeUnit.SECONDS) }
        probeActive = false
        switchRequested = false
        switchRequestConsumed = false
        switchConfirmed = false
        switchRequestAtMs = -1L
        appliedMech = null
        pendingFallbackForNextCall = false
        activeImpl = EncoderImpl.SELF
        requestedFps = UNKNOWN_FPS
        implName = Vp9VideoEncoder.IMPL_NAME
        lastReason = "init"
        lastSample = null
        lastDecisionKey = null
        // 注意：**不重置** timeSource —— 单测先注入假时钟再调用本方法，重置会把假时钟冲掉。
        synchronized(lock) {
            history.clear()
            policyState = EncoderFallbackPolicy.State()
            sampleCount = 0
            producedCount = 0
            noOutputCount = 0
            windowStartMs = -1L
        }
        configure(mode, fallbackEnabled)
    }
}
