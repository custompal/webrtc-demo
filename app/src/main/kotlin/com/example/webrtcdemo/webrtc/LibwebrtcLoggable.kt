package com.example.webrtcdemo.webrtc

import com.example.webrtcdemo.BuildConfig
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.log.LogLevel
import org.webrtc.Logging
import org.webrtc.Loggable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ============================================================================
// libwebrtc 内部日志注入（doc/14 §9.7，回写 t5 的结论落地）
// ----------------------------------------------------------------------------
// 背景（§9.7 源码级结论）：release 下 `RTC_LOG` 未被编译掉，但 `is_debug=false ⇒ NDEBUG
//   ⇒ kDefaultLoggingSeverity = LS_NONE`，`IsNoop()` 为真时连 WARNING/ERROR 都被丢弃。
//   因此必须**注册日志接收器**：`InitializationOptions.setInjectableLogger(Loggable, Severity)`。
//   注册 sink 会抬高 `g_min_sev`，使 `RTC_LOG` 真正执行；**无需任何新增 GN 参数**。
//
// 契约要求（§9.7）：
//   - `object LibwebrtcLoggable : org.webrtc.Loggable`，Severity → §9.1 LEVEL：
//       LS_VERBOSE→VERBOSE、LS_INFO→INFO、LS_WARNING→WARN、LS_ERROR→ERROR、LS_NONE→ERROR
//   - 写 `logs/webrtc.log`，layer=webrtc、tag=`webrtc/<sanitized>`（截断 24 字符），同时 logcat；
//   - 注入级别：`BuildConfig.DEBUG ? LS_VERBOSE : LS_INFO`；
//   - **限流 200 条/秒**，超出丢弃并每 5 s 打一条 `webrtc_log_throttled dropped=<n>`；
//   - 禁止调用包私有 `Logging.injectLoggable`，也禁止与 `Logging.enableLogToDebugOutput` 同时用。
// ============================================================================

/**
 * libwebrtc 日志接收器（§9.7）。由 `WebRtcEngine` 在 `PeerConnectionFactory.initialize`
 * 时通过 `setInjectableLogger` 注入，**进程内只注入一次**。
 */
object LibwebrtcLoggable : Loggable {

    private const val TAG = "webrtc"

    /** §9.7 冻结限流：200 条/秒。 */
    private const val MAX_PER_SECOND = 200

    /** 限流丢弃的汇总上报间隔（§9.7：每 5 s 一条）。 */
    private const val THROTTLE_REPORT_INTERVAL_MS = 5_000L

    private const val WINDOW_MS = 1_000L

    private const val TAG_PREFIX = "webrtc/"

    private val windowStartMs = AtomicLong(0L)
    private val countInWindow = AtomicInteger(0)
    private val droppedInWindow = AtomicInteger(0)
    private val lastThrottleReportMs = AtomicLong(0L)

    /**
     * libwebrtc 回调入口（可能来自任意 native/Java 线程）。
     *
     * @param message 单条日志（可能含换行，会被压成单行）。
     * @param severity libwebrtc 严重级别。
     * @param tag libwebrtc 内部 tag。
     */
    override fun onLogMessage(message: String, severity: Logging.Severity, tag: String) {
        if (!allow()) return
        AppLog.webrtc(
            levelOf(severity),
            TAG_PREFIX + tag,
            message.replace('\n', ' ').replace('\r', ' '),
        )
    }

    /**
     * 注入级别（§9.7/§9.6）：debug `LS_VERBOSE`、release `LS_INFO`。
     *
     * 注意：webrtc 层级别**不支持运行时切换**，改级别需重启 App（§9.6）。
     */
    fun injectedSeverity(): Logging.Severity =
        if (BuildConfig.DEBUG) Logging.Severity.LS_VERBOSE else Logging.Severity.LS_INFO

    /** 限流判定 + 丢弃汇总（§9.7）。 */
    private fun allow(): Boolean {
        val now = System.currentTimeMillis()
        val start = windowStartMs.get()
        if (start == 0L || now - start >= WINDOW_MS) {
            windowStartMs.set(now)
            countInWindow.set(0)
        }
        if (countInWindow.incrementAndGet() <= MAX_PER_SECOND) return true
        val dropped = droppedInWindow.incrementAndGet()
        val lastReport = lastThrottleReportMs.get()
        if (now - lastReport >= THROTTLE_REPORT_INTERVAL_MS) {
            lastThrottleReportMs.set(now)
            droppedInWindow.set(0)
            AppLog.w(TAG, "webrtc_log_throttled", mapOf("dropped" to dropped.toString()))
        }
        return false
    }

    /** §9.7 冻结的 Severity → LEVEL 映射（Java 枚举需 else 兜底）。 */
    private fun levelOf(severity: Logging.Severity): LogLevel = when (severity) {
        Logging.Severity.LS_VERBOSE -> LogLevel.VERBOSE
        Logging.Severity.LS_INFO -> LogLevel.INFO
        Logging.Severity.LS_WARNING -> LogLevel.WARN
        Logging.Severity.LS_ERROR -> LogLevel.ERROR
        Logging.Severity.LS_NONE -> LogLevel.ERROR
        else -> LogLevel.INFO
    }
}
