package com.example.webrtcdemo.log

import android.content.Context
import android.util.Log
import java.io.File

// ============================================================================
// 统一日志门面（文件：log/Log.kt，文档依据 doc/14 §2.1 / §9.1 / §9.3 / §9.6）
// ----------------------------------------------------------------------------
// §9.3 明确：**`log/Log.kt` 是 Kotlin 侧唯一日志入口**（业务代码禁止直接调用
// `android.util.Log`，§9.7 的 `Loggable` 转发除外）。业务代码只依赖本对象。
//
// 命名说明：契约冻结的是**文件名** `log/Log.kt` 与职责；对象名取 `AppLog`，
// 以免与 `android.util.Log` 同名造成引用歧义（同文件的 import 也会被遮蔽）。
// t8 统一使用 `AppLog.i/w/e/...`。
//
// 关键日志点（D6，由 t8 在业务代码中落位，事件名复用 §9.1 的固定表）：
//   ws_open / ws_close / room_created / room_joined / peer_joined /
//   offer_sent / answer_sent / ice_* / stats_sample / nat_start / nat_done /
//   encoder_init / setrates / ts_target_kbps / encoded_frame / export_zip
//
// 本文件不引用任何 JNI / 信令契约类型，可独立编译。
// ============================================================================

/**
 * 应用日志门面：Kotlin 侧唯一日志入口。
 */
object AppLog {

    /** 等级持久化（§9.6）SharedPreferences 名称 */
    const val PREFS_NAME = "log_cfg"

    /** Kotlin 层等级键（§9.6 冻结键名） */
    const val KEY_LEVEL_KOTLIN = "level_kotlin"

    /** native 层等级键（§9.6 冻结键名；由 t8 读取后传给 `NativeLog.nativeSetLevel`） */
    const val KEY_LEVEL_NATIVE = "level_native"

    /** webrtc 层等级键（§9.6 冻结键名；该层**不支持**运行时切换，需重启） */
    const val KEY_LEVEL_WEBRTC = "level_webrtc"

    /**
     * 初始化日志设施（幂等），应尽早调用（见 `WebRtcDemoApp.onCreate`，§9.3/§9.4）。
     *
     * 等级取值顺序（§9.6）：SharedPreferences 已保存值 → [defaultLevel]（debug DEBUG / release INFO）。
     *
     * @param context 任意 Context。
     * @param defaultLevel 未持久化时的默认等级。
     */
    fun init(context: Context, defaultLevel: LogLevel = FileLogger.DEFAULT_LEVEL): FileLogger {
        // t25：**先记住目录**再构造 FileLogger —— 这样即使构造/写盘失败，兜底留痕也有地方可写。
        FileLogger.rememberLogDir(context)
        val persisted = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LEVEL_KOTLIN, -1)
        val level = if (persisted >= 0) LogLevel.fromCode(persisted) else defaultLevel
        return FileLogger.init(context, level)
    }

    /** 是否已初始化（未初始化时只有 logcat）。 */
    fun isInitialized(): Boolean = FileLogger.get() != null

    /** VERBOSE 日志。 */
    fun v(tag: String, message: String, fields: Map<String, String> = emptyMap()) =
        emit(LogLevel.VERBOSE, tag, message, fields, null)

    /** DEBUG 日志。 */
    fun d(tag: String, message: String, fields: Map<String, String> = emptyMap()) =
        emit(LogLevel.DEBUG, tag, message, fields, null)

    /** INFO 日志。 */
    fun i(tag: String, message: String, fields: Map<String, String> = emptyMap()) =
        emit(LogLevel.INFO, tag, message, fields, null)

    /** WARN 日志。 */
    fun w(tag: String, message: String, fields: Map<String, String> = emptyMap()) =
        emit(LogLevel.WARN, tag, message, fields, null)

    /** ERROR 日志（无异常对象）。 */
    fun e(tag: String, message: String, fields: Map<String, String> = emptyMap()) =
        emit(LogLevel.ERROR, tag, message, fields, null)

    /**
     * ERROR 日志（带异常，会记录完整堆栈；用于崩溃/异常路径）。
     *
     * @param throwable 可为 null（等价于无异常重载）。
     */
    fun e(tag: String, message: String, throwable: Throwable?) =
        emit(LogLevel.ERROR, tag, message, emptyMap(), throwable)

    /**
     * ERROR 日志（同时带字段与异常）。
     *
     * @param fields 行尾 k=v 字段。
     * @param throwable 可为 null。
     */
    fun e(tag: String, message: String, fields: Map<String, String>, throwable: Throwable?) =
        emit(LogLevel.ERROR, tag, message, fields, throwable)

    /**
     * webrtc 通道日志入口（§9.7：由 `webrtc/LibwebrtcLoggable` 转发到此）。
     *
     * 与其他日志共用一个写盘线程，落到 `webrtc.log`（§9.2/§9.5）。
     *
     * @param level 映射后的等级。
     * @param tag §9.7 规定的 `webrtc/<sanitized>`（≤24 字符）。
     * @param message 已去掉换行的单行消息。
     */
    fun webrtc(level: LogLevel, tag: String, message: String) {
        val logger = FileLogger.get()
        if (logger != null) {
            logger.log(level, tag, message, emptyMap(), null, LogChannel.WEBRTC)
            return
        }
        Log.println(level.androidPriority, FileLogger.LOGCAT_TAG, "webrtc|${level.label}| $message")
    }

    /**
     * native 侧低频事件入口（§6.5 的 `NativeCallbacks.onLogEvent` 转发到此）。
     *
     * @param levelCode 级别 0..5（§6.6），与 [LogLevel.code] 一致；越界回退 INFO。
     * @param tag 模块 tag（§9.1 白名单）。
     * @param message 单行消息。
     */
    fun fromNative(levelCode: Int, tag: String, message: String) {
        emit(LogLevel.fromCode(levelCode), tag, message, emptyMap(), null)
    }

    /**
     * 运行时切换等级并持久化（§9.6）。
     *
     * t8 注意：为保持三层一致，应同时
     *   1) 调用 `NativeLog.nativeSetLevel(level.code)`；
     *   2) 把 level 写入 [PREFS_NAME] 的 [KEY_LEVEL_NATIVE]；
     *   3) webrtc 层不支持运行时切换，UI 需提示「需重启生效」。
     */
    fun setLevel(context: Context, level: LogLevel) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LEVEL_KOTLIN, level.code)
            .apply()
        val logger = FileLogger.get()
        if (logger != null) {
            logger.setLevel(level)
        } else {
            Log.i(FileLogger.LOGCAT_TAG, "日志器未初始化，仅记录等级到 $PREFS_NAME")
        }
    }

    /**
     * 持久化 native 层级别（§9.6 的 `level_native`）。
     *
     * 注意分层：本方法**只写配置**，不直接调用 native；由调用方（诊断页）再调
     * `NativeLog.setLevel(level)` 使其立即生效，避免 log 包反向依赖 nativebridge 包。
     */
    fun persistNativeLevel(context: Context, level: LogLevel) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LEVEL_NATIVE, level.code)
            .apply()
    }

    /** 读取已持久化的 native 层级别（未设置时返回默认等级）。 */
    fun nativeLevel(context: Context): LogLevel =
        LogLevel.fromCode(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_LEVEL_NATIVE, FileLogger.DEFAULT_LEVEL.code)
        )

    /**
     * 记录 webrtc 层注入级别（§9.6 的 `level_webrtc`）。
     *
     * 该层级别**只在启动时**由 `PeerConnectionFactory.initialize` 的 sink 决定，
     * 运行时不可切换（UI 必须提示「需重启生效」），故这里只是留下"当前生效值"供诊断页展示。
     */
    fun persistWebrtcLevel(context: Context, label: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LEVEL_WEBRTC, label)
            .apply()
    }

    /** 读取 webrtc 层注入级别（未记录时返回空串）。 */
    fun webrtcLevel(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LEVEL_WEBRTC, "")
            .orEmpty()

    /** 当前等级（未初始化时返回默认值）。 */
    fun level(): LogLevel = FileLogger.get()?.getLevel() ?: FileLogger.DEFAULT_LEVEL

    /**
     * 导出日志前调用：排空内存队列并 flush 文件（§9.5 第 1 步）。
     *
     * t8 注意：导出前还应调用 `NativeLog.nativeFlush()`。
     *
     * @param timeoutMs 最长等待毫秒数。
     */
    fun flush(timeoutMs: Long = FileLogger.DEFAULT_FLUSH_TIMEOUT_MS) {
        FileLogger.get()?.flush(timeoutMs)
    }

    /** 关闭日志器（进程退出前）。 */
    fun shutdown() {
        FileLogger.get()?.shutdown()
    }

    /**
     * 日志根目录 `<filesDir>/logs/`（§9.2 冻结；Kotlin / C++ / webrtc 共用）。
     *
     * t8 用它调用原生初始化（§9.4）：
     * `nativeInit(logDir.absolutePath, "native", level.code, 2*1024*1024, 3)`
     *
     * @return 日志目录（若日志器未初始化则即时解析，不抛异常）。
     */
    fun logDir(context: Context): File =
        FileLogger.get()?.logDir() ?: FileLogger.resolveLogDir(context)

    /**
     * **关键日志**（t25）：同步直写 `app.log`，保证**必定落盘**（绕过异步队列）。
     *
     * 用于引擎生命周期等"失败必须可诊断"的事件。若日志器本身不可用/写失败，
     * 会向 `app-fallback.log` 留一条 `critical_not_persisted` 兜底记录（**绝不静默**）。
     *
     * @param tag 模块标签（§9.1 的 tag 段）。
     * @param message 事件名（如 `engine_ready`）。
     * @param fields 行尾 k=v 字段。
     */
    fun critical(tag: String, message: String, fields: Map<String, String> = emptyMap()) {
        val logger = FileLogger.get()
        if (logger != null && logger.critical(LogLevel.INFO, tag, message, fields)) return
        FileLogger.fallbackMarker("critical_not_persisted event=$message tag=$tag")
        Log.println(
            LogLevel.INFO.androidPriority,
            FileLogger.LOGCAT_TAG,
            message + if (fields.isEmpty()) "" else " " + fields.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" },
        )
    }

    /** 文件写盘失败累计次数（t25；供诊断页/导出自检；未初始化返回 -1）。 */
    fun fileWriteFailureCount(): Int = FileLogger.get()?.writeFailureCount() ?: -1

    private fun emit(
        level: LogLevel,
        tag: String,
        message: String,
        fields: Map<String, String>,
        throwable: Throwable?,
    ) {
        val logger = FileLogger.get()
        if (logger != null) {
            logger.log(level, tag, message, fields, throwable)
            return
        }
        // 未初始化：只写 logcat，避免丢日志或抛异常。
        // t25：**同时**在日志目录旁留一条兜底落痕 —— 否则"app.log 为空"在导出物里毫无线索。
        FileLogger.fallbackMarker("log_sink_missing tag=$tag event=$message")
        val text = buildString {
            append(message)
            for ((key, value) in fields.toSortedMap()) append(' ').append(key).append('=').append(value)
            if (throwable != null) append('\n').append(Log.getStackTraceString(throwable))
        }
        Log.println(level.androidPriority, FileLogger.LOGCAT_TAG, text)
    }
}
