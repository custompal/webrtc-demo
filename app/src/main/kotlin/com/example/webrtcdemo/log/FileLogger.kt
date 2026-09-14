package com.example.webrtcdemo.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

// ============================================================================
// 文件日志器（文件：log/FileLogger.kt，文档依据 doc/14 §2.1 / §9.1 / §9.2 / §9.3 / §9.7）
// ----------------------------------------------------------------------------
// 【§9 契约要点，逐条对齐】
//   1. 路径：<filesDir>/logs/  —— Kotlin 负责 mkdirs()，与 C++/webrtc 共用（§9.2/§9.4）。
//   2. 文件与滚动（§9.2 冻结）：
//        Kotlin   app.log    （+ app.1.log / app.2.log）
//        webrtc   webrtc.log （+ webrtc.1.log / webrtc.2.log，§9.7 由 Loggable 转发）
//        单文件上限 2 MiB、保留 3 个（当前 + 2 历史），超限删最旧。
//   3. 行格式（§9.1）：`<ts> <LEVEL(7)> <layer(6)> <tag(9)> [<pid>/<tid>] <message>[ k=v ...]`
//        ts 为 UTC、毫秒、字面 Z；key=value 按键字典序、值内空格转 `_`；
//        layer ∈ {kotlin, webrtc}。
//   4. 写盘模型（§9.3）：独立单线程写盘线程；日志先入**内存队列**，
//        满 256 行或每 200 ms 触发批量写 + flush；每条日志一次 write()（§9.2）。
//        §9.5 要求「webrtc 日志由同一个写盘线程保证已落盘」→ 多 channel 共用一个 writer。
//   5. 终端保留（§9.2）：同时写 logcat，tag 固定 `WebRtcDemo`；
//        webrtc channel 额外加前缀 `webrtc|<级别>|`（§9.2）。
//
// 线程安全：业务线程只做「格式化 + 入队」（持锁极短，不阻塞 UI / 编码线程）；
//            全部文件 I/O 只发生在 writer 线程。
// 本文件不引用任何 JNI / 信令契约类型，可独立编译。
// ============================================================================

/**
 * 日志通道（决定落到哪个文件与 §9.1 的 layer 段）。
 *
 * @property baseName 主文件名（§9.2 冻结）。
 * @property rollBase 滚动备份的基名（`<rollBase>.<n>.log`）。
 * @property layer §9.1 的 layer 段取值。
 */
enum class LogChannel(val baseName: String, val rollBase: String, val layer: String) {
    /** Kotlin 上层日志 → `app.log`。 */
    APP("app.log", "app", "kotlin"),

    /** libwebrtc 内部日志（经 §9.7 的 Loggable 转发）→ `webrtc.log`。 */
    WEBRTC("webrtc.log", "webrtc", "webrtc"),
}

/**
 * 应用文件日志器：内存队列 + 单线程批量落盘 + Logcat 双写。
 *
 * 业务代码不要直接使用本类，统一走 [AppLog]（§9.3：`log/Log.kt` 是唯一入口）。
 */
class FileLogger private constructor(
    private val logDirectory: File,
    initialLevel: LogLevel,
    private val maxFileBytes: Long,
    private val maxFiles: Int,
) {

    /** writer 线程本轮的动作 */
    private enum class Action { WRITE, FLUSH_ONLY, STOP }

    /** 一条待写记录：channel 决定落哪个文件，line 是已格式化的完整文本（可含换行） */
    private data class Record(val channel: LogChannel, val line: String)

    /** 每个 channel 的写文件状态（只在 writer 线程访问） */
    private class ChannelState(var stream: FileOutputStream? = null, var size: Long = 0L)

    private val lock = ReentrantLock()

    /** 有日志入队 / 需要立即收尾时唤醒 writer */
    private val notEmpty = lock.newCondition()

    /** writer 完成一轮写盘后唤醒 flush()/shutdown() 等待者 */
    private val flushed = lock.newCondition()

    /** 内存队列：满 [LINES_PER_FLUSH] 行触发写盘（§9.3） */
    private val queue = ArrayDeque<Record>()

    /** 是否有 I/O 正在进行（flush() 需要等待） */
    private var writing = false

    /** 写盘失败累计（t25：让"日志系统自身故障"可观测，而不是静默丢日志）。 */
    private val writeFailures = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var currentLevel: LogLevel = initialLevel

    @Volatile
    private var stopped: Boolean = false

    /** 每个 channel 的写状态（只允许 writer 线程访问） */
    private val channels: MutableMap<LogChannel, ChannelState> =
        LogChannel.entries.associateWith { ChannelState() }.toMutableMap()

    /** 单线程写盘线程（daemon，不阻止进程退出） */
    private val writer: Thread = Thread({ writerLoop() }, WRITER_THREAD_NAME).apply {
        isDaemon = true
        start()
    }

    /**
     * 写一条日志。可在任意线程调用（UI / WebRTC 回调 / 编码线程 / native 回调线程）。
     *
     * @param level 本条日志等级；未达到当前阈值时直接丢弃。
     * @param moduleTag 模块标签（§9.1 的 `tag` 段，snake_case，截断 24 字符）。
     * @param message 正文（建议以固定事件名开头，如 `ws_open`、`setrates`）。
     * @param fields 行尾 `key=value` 字段，按键字典序输出；值内空格转 `_`。
     * @param throwable 可选异常；会附加完整堆栈。
     * @param channel 目标通道（默认 [LogChannel.APP]）。
     */
    fun log(
        level: LogLevel,
        moduleTag: String,
        message: String,
        fields: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
        channel: LogChannel = LogChannel.APP,
    ) {
        if (!currentLevel.isEnabledFor(level)) return

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val line = formatLine(now, level, channel, moduleTag, message, fields, throwable)

        // 终端保留：logcat tag 固定 WebRtcDemo；webrtc channel 加 `webrtc|<级别>|` 前缀（§9.2）
        val logcatMessage =
            if (throwable == null) {
                val body = line.substringAfter("] ")
                if (channel == LogChannel.WEBRTC) "webrtc|${level.label}| $body" else body
            } else {
                message + "\n" + Log.getStackTraceString(throwable)
            }
        Log.println(level.androidPriority, LOGCAT_TAG, logcatMessage)

        lock.lock()
        try {
            if (stopped) return
            queue.addLast(Record(channel, line))
            if (queue.size >= LINES_PER_FLUSH) notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /**
     * **同步直写**一条关键日志（t25）：绕过内存队列，直接 append 到 `app.log` 并立即 flush。
     *
     * 用途：**必须落盘**的引擎生命周期事件（`log_sink_state` / `engine_native_loaded` /
     * `engine_init_skipped` / `jni_binding_missing` / `engine_init_failed` / `engine_ready`）。
     * 动机：真机上曾出现"`native.log`（C++ 独立写）有记录、而 Kotlin 的 `app.log` 整段为空"——
     * 异步写盘路径（队列 + writer 线程）一旦静默失败，外部就完全看不到 Kotlin 侧发生了什么。
     *
     * 行格式与异步路径**完全一致**（复用 [formatLine]），因此不影响 §9.1 契约。
     *
     * @return `true` = 已写入文件；`false` = 写失败（此时已向 `app-fallback.log` 留痕并计数）。
     */
    fun critical(
        level: LogLevel,
        moduleTag: String,
        message: String,
        fields: Map<String, String> = emptyMap(),
    ): Boolean {
        if (!currentLevel.isEnabledFor(level)) return true
        val line = formatLine(
            OffsetDateTime.now(ZoneOffset.UTC), level, LogChannel.APP, moduleTag, message, fields, null,
        )
        Log.println(level.androidPriority, LOGCAT_TAG, line.substringAfter("] "))
        return directAppend(line)
    }

    /**
     * 直接 append 一行到 `app.log`（自建流，独立于 writer 线程）。
     *
     * 失败时：计数 + 向 `app-fallback.log` 留痕（**绝不静默**）。
     */
    private fun directAppend(line: String): Boolean = try {
        val file = File(logDirectory, LogChannel.APP.baseName)
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
        true
    } catch (t: Throwable) {
        writeFailures.incrementAndGet()
        fallbackAppend("direct_append_failed msg=${t.javaClass.simpleName}:${t.message}")
        false
    }

    /** 兜底文件 append（同步、best-effort）：`<logs>/app-fallback.log`。 */
    private fun fallbackAppend(reason: String): Boolean = try {
        FileOutputStream(File(logDirectory, FALLBACK_FILE_NAME), true).use { out ->
            out.write(("${OffsetDateTime.now(ZoneOffset.UTC)} $reason\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
        true
    } catch (t: Throwable) {
        false
    }

    /** 写盘失败累计次数（供诊断页/导出自检引用；t25）。 */
    fun writeFailureCount(): Int = writeFailures.get()

    /** 组装 §9.1 行格式（layer 取 channel.layer）。 */
    private fun formatLine(
        now: OffsetDateTime,
        level: LogLevel,
        channel: LogChannel,
        moduleTag: String,
        message: String,
        fields: Map<String, String>,
        throwable: Throwable?,
    ): String {
        val builder = StringBuilder(160)
        builder.append(TIME_FORMAT.format(now)).append(' ')
        builder.append(level.label.padEnd(LEVEL_WIDTH)).append(' ')
        builder.append(channel.layer.padEnd(LAYER_WIDTH)).append(' ')
        builder.append(sanitizeTag(moduleTag).padEnd(TAG_WIDTH)).append(' ')
        // pid 恒定；tid 为**调用线程** id（§9.1 示例中不同线程有不同 tid）
        builder.append('[').append(PID).append('/').append(android.os.Process.myTid()).append(']').append(' ')
        builder.append(message.replace(' ', '_'))
        if (fields.isNotEmpty()) {
            for ((key, value) in fields.toSortedMap()) {
                builder.append(' ').append(key).append('=').append(value.replace(' ', '_'))
            }
        }
        if (throwable != null) {
            builder.append('\n')
            for (stackLine in Log.getStackTraceString(throwable).split('\n')) {
                builder.append(STACK_INDENT).append(stackLine).append('\n')
            }
        }
        return builder.toString()
    }

    // ============================ writer 线程 ============================

    private fun writerLoop() {
        val batch = ArrayList<Record>(LINES_PER_FLUSH)
        while (true) {
            when (takeBatch(batch)) {
                Action.STOP -> {
                    batch.clear()
                    return
                }

                Action.WRITE -> {
                    writeRecords(batch)
                    flushStreams()
                    batch.clear()
                    markWritten()
                }

                Action.FLUSH_ONLY -> {
                    flushStreams()
                    markWritten()
                }
            }
        }
    }

    /** 加锁取一批（最多 [LINES_PER_FLUSH] 行）；无数据时最多等待 200 ms（§9.3） */
    private fun takeBatch(batch: ArrayList<Record>): Action {
        lock.lock()
        try {
            if (queue.isEmpty() && !stopped) {
                try {
                    notEmpty.await(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (stopped && queue.isEmpty()) {
                closeStreams()
                return Action.STOP
            }
            var taken = 0
            while (queue.isNotEmpty() && taken < LINES_PER_FLUSH) {
                batch.add(queue.removeFirst())
                taken++
            }
            if (batch.isEmpty()) return Action.FLUSH_ONLY
            writing = true
            return Action.WRITE
        } finally {
            lock.unlock()
        }
    }

    private fun markWritten() {
        lock.lock()
        try {
            writing = false
            flushed.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** 批量写盘：每条日志一次 write()（§9.2），写前按 channel 做大小滚动 */
    private fun writeRecords(records: List<Record>) {
        for (record in records) {
            try {
                val state = channels.getValue(record.channel)
                ensureOpen(record.channel, state)
                val bytes = (record.line + "\n").toByteArray(Charsets.UTF_8)
                // 单条就超上限时不滚动（否则会无限滚动），直接写下去
                if (state.size > 0L && state.size + bytes.size > maxFileBytes) {
                    rollFiles(record.channel, state)
                    openFresh(record.channel, state)
                }
                val out = state.stream ?: continue
                out.write(bytes)
                state.size += bytes.size
            } catch (t: Throwable) {
                // 日志失败绝不能影响业务；但**不得静默**（t25）：计数 + 兜底文件留痕 + logcat。
                writeFailures.incrementAndGet()
                fallbackAppend("write_failed ch=${record.channel.baseName} msg=${t.javaClass.simpleName}:${t.message}")
                Log.e(LOGCAT_TAG, "写入日志文件失败(${record.channel.baseName}): ${t.message}", t)
            }
        }
    }

    /** 首次写入时打开文件；若已存在且达上限，先滚动 */
    private fun ensureOpen(channel: LogChannel, state: ChannelState) {
        if (state.stream != null) return
        var file = File(logDirectory, channel.baseName)
        if (file.length() >= maxFileBytes) {
            rollFiles(channel, state)
            file = File(logDirectory, channel.baseName)
        }
        state.stream = FileOutputStream(file, true)
        state.size = file.length()
    }

    /** 截断重开（滚动之后调用） */
    private fun openFresh(channel: LogChannel, state: ChannelState) {
        closeStream(state)
        state.stream = FileOutputStream(File(logDirectory, channel.baseName), false)
        state.size = 0L
    }

    /**
     * 滚动（§9.2）：删除最旧的 `<base>.<maxFiles-1>.log`，
     * 依次 `<base>.N.log -> <base>.(N+1).log`、`<base>.log -> <base>.1.log`，然后重开。
     */
    private fun rollFiles(channel: LogChannel, state: ChannelState) {
        closeStream(state)
        val active = File(logDirectory, channel.baseName)
        if (maxFiles <= 1) {
            if (active.exists() && !active.delete()) {
                Log.w(LOGCAT_TAG, "日志滚动：删除 ${active.name} 失败")
            }
            return
        }
        val oldestIndex = maxFiles - 1
        val oldest = File(logDirectory, rollName(channel, oldestIndex))
        if (oldest.exists() && !oldest.delete()) {
            Log.w(LOGCAT_TAG, "日志滚动：删除最旧备份 ${oldest.name} 失败")
        }
        for (index in oldestIndex - 1 downTo 1) {
            val from = File(logDirectory, rollName(channel, index))
            if (!from.exists()) continue
            val to = File(logDirectory, rollName(channel, index + 1))
            if (!from.renameTo(to)) {
                Log.w(LOGCAT_TAG, "日志滚动：${from.name} -> ${to.name} 失败")
            }
        }
        if (active.exists() && !active.renameTo(File(logDirectory, rollName(channel, 1)))) {
            Log.w(LOGCAT_TAG, "日志滚动：${active.name} -> ${rollName(channel, 1)} 失败")
        }
    }

    private fun closeStream(state: ChannelState) {
        val out = state.stream ?: return
        state.stream = null
        try {
            out.flush()
        } catch (t: Throwable) {
            Log.w(LOGCAT_TAG, "日志文件 flush 失败: ${t.message}")
        }
        try {
            out.close()
        } catch (t: Throwable) {
            Log.w(LOGCAT_TAG, "日志文件关闭失败: ${t.message}")
        }
    }

    private fun closeStreams() {
        for (state in channels.values) closeStream(state)
    }

    private fun flushStreams() {
        for (state in channels.values) {
            try {
                state.stream?.flush()
            } catch (t: Throwable) {
                Log.w(LOGCAT_TAG, "日志文件 flush 失败: ${t.message}")
            }
        }
    }

    // ============================ 对外接口 ============================

    /**
     * 运行时切换日志等级（§9.6）。等级数值与 native 一致（§6.6）。
     *
     * t8 注意：应同时调用 `NativeLog.nativeSetLevel(level.code)` 保持两层一致。
     */
    fun setLevel(level: LogLevel) {
        val previous = currentLevel
        currentLevel = level
        AppLog.i(TAG, "log_level_changed", mapOf("from" to previous.label, "to" to level.label))
    }

    /** 当前日志等级。 */
    fun getLevel(): LogLevel = currentLevel

    /** 日志目录（已保证存在），同时也是 C++ / webrtc 日志目录（§9.2）。 */
    fun logDir(): File = logDirectory

    /**
     * 排空内存队列并 flush 全部 channel 文件（**导出日志前必须调用**，§9.5 第 1 步）。
     *
     * t8 注意：导出前还应调用 `NativeLog.nativeFlush()`。
     *
     * @param timeoutMs 最长等待毫秒数；超时即返回（不抛异常）。
     */
    fun flush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS) {
        if (stopped) return
        lock.lock()
        try {
            if (queue.isEmpty() && !writing) return
            notEmpty.signalAll()
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while ((queue.isNotEmpty() || writing) && remaining > 0) {
                val start = System.nanoTime()
                try {
                    flushed.await(remaining, TimeUnit.NANOSECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                remaining -= (System.nanoTime() - start)
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 关闭日志器：排空队列、flush 并关闭全部文件。进程退出前调用即可。
     *
     * @param timeoutMs 等待 writer 线程收尾的最长毫秒数。
     */
    fun shutdown(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS) {
        lock.lock()
        try {
            if (stopped) return
            stopped = true
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
        try {
            writer.join(timeoutMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "log"
        private const val WRITER_THREAD_NAME = "webrtcdemo-log-writer"
        private const val STACK_INDENT = "        "

        /** 兜底文件（t25）：日志系统自身故障的落痕，与 `app.log` 同目录、随导出一并打包。 */
        const val FALLBACK_FILE_NAME = "app-fallback.log"

        /** logcat tag：固定值（§9.2） */
        const val LOGCAT_TAG = "WebRtcDemo"

        /** §9.1 各段定宽 */
        private const val LEVEL_WIDTH = 7
        private const val LAYER_WIDTH = 6
        private const val TAG_WIDTH = 9
        private const val TAG_MAX_LENGTH = 24

        /** §9.3：满 256 行触发一次写盘 */
        const val LINES_PER_FLUSH = 256

        /** §9.3：每 200 ms 触发一次 flush */
        const val FLUSH_INTERVAL_MS = 200L

        /** §9.2 冻结：Kotlin 主日志文件名 */
        const val FILE_NAME = "app.log"

        /** §9.2 冻结：单文件上限 2 MiB（写作 `2 * 1024 * 1024L` 以匹配契约 §12.6 V43 的 grep 锚点） */
        const val MAX_FILE_BYTES: Long = 2 * 1024 * 1024L

        /** §9.2 冻结：保留 3 个（当前 + 2 历史） */
        const val MAX_FILES: Int = 3

        /** §9.2 冻结：Android 日志根目录名（Kotlin / C++ / webrtc 共用） */
        const val LOG_DIR_NAME = "logs"

        /** 默认等级（§9.6：release INFO；debug 由 WebRtcDemoApp 依 BuildConfig 提升为 DEBUG） */
        val DEFAULT_LEVEL: LogLevel = LogLevel.INFO

        /** flush/shutdown 默认超时 */
        const val DEFAULT_FLUSH_TIMEOUT_MS: Long = 2_000L

        /** §9.1 时间戳：UTC、毫秒、字面 Z */
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        /** §9.1 的 pid（进程启动后恒定）；tid 每次取调用线程，见 formatLine */
        private val PID: Int = android.os.Process.myPid()

        @Volatile
        private var instance: FileLogger? = null

        /**
         * 最近一次解析出的日志目录（t25）。
         *
         * 存在意义：当 `FileLogger` **根本没初始化成功**（`get() == null`）时，业务日志会退化为
         * "只有 logcat"，外部完全看不到 `app.log` 为什么是空的。记住目录后即可用
         * [fallbackMarker] 在其旁边落一条 `app-fallback.log`，把"日志系统故障"变成**可导出的证据**。
         */
        @Volatile
        private var lastKnownLogDir: File? = null

        /** 记录日志目录（`AppLog.init` 在构造 FileLogger 之前调用；幂等）。 */
        fun rememberLogDir(context: Context): File =
            resolveLogDir(context).also { lastKnownLogDir = it }

        /** 当前已知日志目录（可能为 null：尚未调用过 remember/resolve）。 */
        fun knownLogDir(): File? = lastKnownLogDir

        /**
         * **日志器不可用时的兜底落痕**（t25，同步、best-effort）。
         *
         * 写到 `<logs>/app-fallback.log`（与 `app.log` 同目录 ⇒ 会被日志导出一起打包），
         * 因此"app.log 为空"这类现象**总能**在导出物里找到原因记录。
         *
         * @return `true` = 已写入兜底文件。
         */
        fun fallbackMarker(reason: String): Boolean {
            val dir = lastKnownLogDir ?: return false
            return try {
                FileOutputStream(File(dir, FALLBACK_FILE_NAME), true).use { out ->
                    out.write(
                        ("${OffsetDateTime.now(ZoneOffset.UTC)} $reason\n").toByteArray(Charsets.UTF_8)
                    )
                    out.flush()
                }
                true
            } catch (t: Throwable) {
                false
            }
        }

        /**
         * 初始化全局日志器（幂等）。
         *
         * @param context 任意 Context。
         * @param level 初始等级。
         */
        fun init(
            context: Context,
            level: LogLevel = DEFAULT_LEVEL,
            maxFileBytes: Long = MAX_FILE_BYTES,
            maxFiles: Int = MAX_FILES,
        ): FileLogger {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: FileLogger(resolveLogDir(context), level, maxFileBytes, maxFiles)
                    .also { created ->
                        instance = created
                        created.log(
                            LogLevel.INFO,
                            TAG,
                            "file_logger_ready",
                            mapOf(
                                "dir" to created.logDir().absolutePath,
                                "level" to level.label,
                                "max_bytes" to maxFileBytes.toString(),
                                "max_files" to maxFiles.toString(),
                            )
                        )
                    }
            }
        }

        /** 已初始化的日志器；未初始化返回 null（此时业务日志仍会走 logcat）。 */
        fun get(): FileLogger? = instance

        /**
         * 解析并创建日志目录：`<filesDir>/logs/`（§9.2 冻结，**不是**外部存储）。
         *
         * 该目录由 Kotlin 负责 mkdirs()，C++ / webrtc 日志共用（§9.4）。
         */
        fun resolveLogDir(context: Context): File {
            val dir = File(context.filesDir, LOG_DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(LOGCAT_TAG, "创建日志目录失败: ${dir.absolutePath}")
            }
            return dir
        }

        /** 滚动备份名：`<rollBase>.<index>.log`（§9.2）。 */
        fun rollName(channel: LogChannel, index: Int): String = "${channel.rollBase}.$index.log"

        /** 标签规范化：snake_case、截断 24 字符（§9.1/§9.7）。 */
        fun sanitizeTag(raw: String): String {
            val normalized = raw.trim().lowercase(Locale.US)
                .map { ch -> if (ch.isLetterOrDigit() || ch == '_') ch else '_' }
                .joinToString("")
            return if (normalized.length <= TAG_MAX_LENGTH) normalized else normalized.substring(0, TAG_MAX_LENGTH)
        }
    }
}
