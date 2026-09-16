package com.example.webrtcdemo.encoder

// ============================================================================
// 自研编码「自动降级兜底」的**纯函数判据**（t87 验收第 1 条）
// ----------------------------------------------------------------------------
// 职责边界：
//   · 本文件**只有纯函数与不可变数据**：不碰 Android API、不做 I/O、不打日志、不持有可变状态；
//     输入 = 按秒聚合的窗口样本 + 状态（已在本次通话切过几次、上次切换时刻）+ 当前时刻；
//     输出 = `KEEP_SELF | SWITCH_TO_DEFAULT`；
//   · 采集（JNI 边界计时/计数）与执行（selector/工厂切换）分别落在
//     `EncoderFallbackController` 与 `Vp9VideoEncoderFactory`，便于离线单测这一层。
//
// 触发口径（可配置常量，见 [EncoderFallbackPolicy.Config]）：
//   · 单帧预算 budget_ms = 1000 / 请求帧率；
//   · 「坏秒」判据：`encode_p95_ms > 0.6 × budget_ms` **或** `out_fps < 0.7 × 请求帧率`；
//   · 窗口判据：最近 `windowSeconds=10` 秒中**至少 ceil(10 × 0.8)=8 秒**为坏秒
//     （等价于“连续 ≥10 s 跟不上”，留 2 秒容差以抗抖动，不是“单秒即切”）；
//   · 滞回：切换后 `hysteresisMs=30 s` 内不再切；且一轮通话 `maxSwitchesPerCall=1` 次。
// ============================================================================

/** 编码实现标识（自研 VP9 软编 / 默认（硬件优先）编码）。 */
enum class EncoderImpl(val wire: String) {
    /** 自研 VP9 软编（`SelfVp9Libvpx`）。 */
    SELF("self"),

    /** 默认（硬件优先）编码 —— `DefaultVideoEncoderFactory`，即 MediaCodec 硬编优先、软编兜底。 */
    DEFAULT("default"),
    ;

    /** 用于诊断键 `impl=` 的稳定取值。 */
    override fun toString(): String = wire
}

/**
 * 诊断页的**三态手动覆盖**（t87 验收第 3 条）：自动（默认，兜底开启）/ 强制自研 / 强制默认。
 */
enum class EncoderOverrideMode(val wire: String) {
    /** 自动：按实测指标自动兜底（默认值）。 */
    AUTO("AUTO"),

    /** 强制自研：不自动兜底（用于验收/对照，看自研极限）。 */
    SELF("SELF"),

    /** 强制默认（硬件优先）：完全绕过自研编码器。 */
    DEFAULT("DEFAULT"),
    ;

    companion object {
        /** 持久化字符串 → 枚举；非法/空值回退 [AUTO]（**不抛异常**，避免脏配置让 App 起不来）。 */
        fun fromWire(raw: String?): EncoderOverrideMode =
            entries.firstOrNull { it.wire == raw } ?: AUTO
    }
}

/**
 * 一个**按秒聚合**的编码窗口样本（t87 验收第 1 条字段口径）。
 *
 * @param encodeP95Ms 本窗口内单帧编码耗时的 p95（Kotlin 侧 JNI 边界 `System.nanoTime()` 实测）。
 * @param producedFrames 本窗口产出的**非空**编码帧数（`encode()` 返回 OK 的次数）。
 * @param noOutputFrames 本窗口**无产出**帧数（`NO_OUTPUT`；用于区分“在跑但不出帧”与“根本没输入”）。
 * @param requestedFps 本窗口末次请求帧率（`setRateAllocation(framerate)` / `initEncode.maxFramerate`）。
 * @param windowMs 本窗口实际时长（毫秒）；正常为 1000，严重卡顿时可更长。
 * @param impl 本窗口所属实现（默认实现下不再做降级判定）。
 */
data class EncodeWindowSample(
    val encodeP95Ms: Int,
    val producedFrames: Int,
    val noOutputFrames: Int,
    val requestedFps: Int,
    val windowMs: Long,
    val impl: EncoderImpl = EncoderImpl.SELF,
) {
    /** 本窗口实际产出帧率（非空编码帧数 / 窗口时长）。 */
    val outFps: Double
        get() = if (windowMs <= 0L) 0.0 else producedFrames * 1000.0 / windowMs

    /** 单帧预算（ms）= 1000 / 请求帧率；请求帧率非法时为 0。 */
    val frameBudgetMs: Double
        get() = if (requestedFps <= 0) 0.0 else 1000.0 / requestedFps
}

/**
 * 自动降级判据（纯函数）。
 */
object EncoderFallbackPolicy {

    // ---- 决策原因（诊断键 `reason=` 的稳定取值，报告与真机复测按这些字符串核对）----
    /** 窗口样本不足（刚开编码/刚切换），不判定。 */
    const val REASON_WARMUP = "warmup"

    /** 指标健康。 */
    const val REASON_HEALTHY = "healthy"

    /** 窗口内 p95 超预算占比过高。 */
    const val REASON_P95_OVER_BUDGET = "encode_p95_over_budget"

    /** 窗口内产出帧率不足。 */
    const val REASON_OUT_FPS_LOW = "out_fps_below_requested"

    /** 两个症状同时成立。 */
    const val REASON_P95_AND_FPS = "encode_p95_and_out_fps"

    /** 切换后滞回期内（≥30 s 不切回）。 */
    const val REASON_HYSTERESIS = "hysteresis"

    /** 一轮通话已切满（幂等，最多 1 次）。 */
    const val REASON_ALREADY_SWITCHED = "already_switched"

    /** 兜底开关被关闭（三态=自动但用户关掉兜底）。 */
    const val REASON_FALLBACK_DISABLED = "fallback_disabled"

    /** 请求帧率非法（0/负），无法算预算。 */
    const val REASON_NO_REQUESTED_FPS = "no_requested_fps"

    /** 需要切换，但默认实现不可用（工厂返回 null）。 */
    const val REASON_DEFAULT_UNAVAILABLE = "default_unavailable"

    /**
     * 判据配置（验收第 1 条要求“两者都需可配置常量”）。
     */
    data class Config(
        /** 判定窗口长度（秒）。 */
        val windowSeconds: Int = 10,

        /** 窗口内坏秒占比下限（10 s × 0.8 = 8 秒）。 */
        val badSecondRatio: Double = 0.8,

        /** p95 触发比：`encode_p95_ms > frameBudgetRatio × 单帧预算`。 */
        val frameBudgetRatio: Double = 0.6,

        /** 产出帧率触发比：`out_fps < minOutFpsRatio × 请求帧率`。 */
        val minOutFpsRatio: Double = 0.7,

        /** 滞回：切换后多久内不再切（ms）。 */
        val hysteresisMs: Long = 30_000L,

        /** 一轮通话最多切换次数（幂等上界）。 */
        val maxSwitchesPerCall: Int = 1,
    )

    /** 决策动作。 */
    enum class Action {
        /** 继续用自研编码。 */
        KEEP_SELF,

        /** 切换到默认（硬件优先）实现。 */
        SWITCH_TO_DEFAULT,
    }

    /**
     * 一轮通话内的判据状态（由调用方持有，本对象不保存）。
     *
     * @param switchesThisCall 本次通话已发起切换次数。
     * @param lastSwitchAtMs 上次切换时刻（单调毫秒）；从未切换为 -1。
     */
    data class State(
        val switchesThisCall: Int = 0,
        val lastSwitchAtMs: Long = -1L,
    )

    /**
     * 一次判定的结果（含落诊断键所需的全部量化字段）。
     */
    data class Decision(
        val action: Action,
        val reason: String,
        val encodeP95Ms: Int,
        val outFps: Double,
        val requestedFps: Int,
        val windowS: Int,
        val badSeconds: Int,
        val noOutputFrames: Int,
    ) {
        /**
         * 诊断键字段（`encoder_fallback_decision …`）。
         *
         * 键名与 t87 验收第 4 条逐字对应：`action/reason/encode_p95_ms/out_fps/requested_fps/window_s`
         * （另附 `bad_s` 与 `no_output_frames` 便于真机判读，不改既有键名）。
         */
        fun logFields(): Map<String, String> = linkedMapOf(
            "action" to if (action == Action.SWITCH_TO_DEFAULT) "switch_to_default" else "keep_self",
            "reason" to reason,
            "encode_p95_ms" to encodeP95Ms.toString(),
            "out_fps" to formatFps(outFps),
            "requested_fps" to requestedFps.toString(),
            "window_s" to windowS.toString(),
            "bad_s" to badSeconds.toString(),
            "no_output_frames" to noOutputFrames.toString(),
        )
    }

    /**
     * 单个窗口是否“坏秒”（p95 超预算 **或** 产出帧率不足）。
     *
     * @param sample 窗口样本。
     * @param config 判据配置。
     * @return true = 该秒跟不上。
     */
    fun isBadSecond(sample: EncodeWindowSample, config: Config = Config()): Boolean =
        isBadSecond(sample.encodeP95Ms, sample.outFps, sample.requestedFps, config)

    /**
     * 坏秒判据的**标量形态**（便于单测逐条覆盖两个触发条件）。
     *
     * @param encodeP95Ms 单帧编码耗时 p95（ms）。
     * @param outFps 实际产出帧率。
     * @param requestedFps 请求帧率。
     * @param config 判据配置。
     * @return true = 该秒跟不上；请求帧率非法时返回 false（无法判定，不倒向切换）。
     */
    fun isBadSecond(
        encodeP95Ms: Int,
        outFps: Double,
        requestedFps: Int,
        config: Config = Config(),
    ): Boolean {
        if (requestedFps <= 0) return false
        val budgetMs = 1000.0 / requestedFps
        val slow = encodeP95Ms > config.frameBudgetRatio * budgetMs
        val thin = outFps < config.minOutFpsRatio * requestedFps
        return slow || thin
    }

    /**
     * 核心判定：给定最近窗口历史与状态，决定是否切换到默认（硬件优先）实现。
     *
     * 判定顺序（**顺序本身是验收的一部分**）：
     *   1. 窗口未满 `windowSeconds` 个样本 → [REASON_WARMUP]（不切）；
     *   2. 滞回期内（`now - lastSwitchAtMs < hysteresisMs`）→ [REASON_HYSTERESIS]（不切）；
     *   3. 本通话切换次数已达 `maxSwitchesPerCall` → [REASON_ALREADY_SWITCHED]（不切，幂等）；
     *   4. 请求帧率非法 → [REASON_NO_REQUESTED_FPS]（不切）；
     *   5. 坏秒数 < ceil(windowSeconds × badSecondRatio) → [REASON_HEALTHY]（不切）；
     *   6. 否则 → [SWITCH_TO_DEFAULT]，原因按症状取
     *      [REASON_P95_OVER_BUDGET] / [REASON_OUT_FPS_LOW] / [REASON_P95_AND_FPS]。
     *
     * 注意 2 与 3 的先后：滞回未过时给 `reason=hysteresis`，滞回过后（仍已切满）才给
     * `reason=already_switched` —— 这样「切换后 30 s 内不再切」与「一轮最多一次」是两个
     * **可分别观测**的判据（真机日志能一眼区分是滞回挡住还是次数挡住）。
     *
     * 窗口聚合口径：
     *   · `encode_p95_ms` 取窗口内各秒 p95 的**最大值**（保守，宁可看到最差一秒）；
     *   · `out_fps` 取窗口内总产出帧数 / 总时长（不是各秒平均，避免权重偏差）；
     *   · `requested_fps` 取窗口末样本（帧率变化时以最新为准）。
     *
     * @param history 最近的窗口样本（按时间升序；可长于 windowSeconds，内部只取末尾）。
     * @param state 本次通话的切换状态。
     * @param nowMs 当前单调毫秒（用于滞回判断）。
     * @param config 判据配置。
     * @return 决策（含落诊断键的全部字段）。
     */
    fun decide(
        history: List<EncodeWindowSample>,
        state: State,
        nowMs: Long,
        config: Config = Config(),
    ): Decision {
        val window = history.takeLast(config.windowSeconds.coerceAtLeast(1))
        val requestedFps = window.lastOrNull()?.requestedFps ?: 0
        val p95 = window.maxOfOrNull { it.encodeP95Ms } ?: 0
        val totalMs = window.sumOf { it.windowMs }
        val totalProduced = window.sumOf { it.producedFrames }
        val totalNoOutput = window.sumOf { it.noOutputFrames }
        val outFps = if (totalMs > 0L) totalProduced * 1000.0 / totalMs else 0.0
        val badSeconds = window.count { isBadSecond(it, config) }
        val windowS = (totalMs / 1000L).toInt()

        if (window.size < config.windowSeconds.coerceAtLeast(1)) {
            return Decision(Action.KEEP_SELF, REASON_WARMUP, p95, outFps, requestedFps, windowS, badSeconds, totalNoOutput)
        }
        if (state.lastSwitchAtMs >= 0L && nowMs - state.lastSwitchAtMs < config.hysteresisMs) {
            return Decision(Action.KEEP_SELF, REASON_HYSTERESIS, p95, outFps, requestedFps, windowS, badSeconds, totalNoOutput)
        }
        if (state.switchesThisCall >= config.maxSwitchesPerCall) {
            return Decision(Action.KEEP_SELF, REASON_ALREADY_SWITCHED, p95, outFps, requestedFps, windowS, badSeconds, totalNoOutput)
        }
        if (requestedFps <= 0) {
            return Decision(Action.KEEP_SELF, REASON_NO_REQUESTED_FPS, p95, outFps, requestedFps, windowS, badSeconds, totalNoOutput)
        }
        val neededBadSeconds = kotlin.math.ceil(
            config.windowSeconds.coerceAtLeast(1) * config.badSecondRatio
        ).toInt().coerceAtLeast(1)
        if (badSeconds < neededBadSeconds) {
            return Decision(Action.KEEP_SELF, REASON_HEALTHY, p95, outFps, requestedFps, windowS, badSeconds, totalNoOutput)
        }
        val budgetMs = 1000.0 / requestedFps
        val slow = p95 > config.frameBudgetRatio * budgetMs
        val thin = outFps < config.minOutFpsRatio * requestedFps
        val reason = when {
            slow && thin -> REASON_P95_AND_FPS
            slow -> REASON_P95_OVER_BUDGET
            else -> REASON_OUT_FPS_LOW
        }
        return Decision(Action.SWITCH_TO_DEFAULT, reason, p95, outFps, requestedFps, windowS, badSeconds, totalNoOutput)
    }

    /**
     * 三态覆盖 → 实际实现（纯函数；`AUTO` 透传自动状态）。
     *
     * @param mode 诊断页三态。
     * @param autoImpl 自动路径当前实现（由控制器状态机给出）。
     * @return 实际应使用的实现。
     */
    fun resolveImpl(mode: EncoderOverrideMode, autoImpl: EncoderImpl): EncoderImpl = when (mode) {
        EncoderOverrideMode.AUTO -> autoImpl
        EncoderOverrideMode.SELF -> EncoderImpl.SELF
        EncoderOverrideMode.DEFAULT -> EncoderImpl.DEFAULT
    }

    /**
     * p95（**纯函数**）：对一组单帧耗时取 95 分位。
     *
     * 口径：升序排序后取索引 `ceil(0.95 × n) - 1`（n=30 时即第 29 个，接近最大值）；
     * 空数组返回 0（表示“没有样本”，调用方不应据此判坏）。
     *
     * @param valuesMs 单帧耗时（ms），可为乱序；**不会被修改**（内部先复制）。
     * @return p95（ms）。
     */
    fun p95(valuesMs: IntArray): Int {
        if (valuesMs.isEmpty()) return 0
        val sorted = valuesMs.copyOf()
        sorted.sort()
        val index = kotlin.math.ceil(0.95 * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    /** 帧率格式化（诊断键 `out_fps` 保留 1 位小数）。 */
    fun formatFps(fps: Double): String = String.format(java.util.Locale.US, "%.1f", fps)
}
