package com.example.webrtcdemo

import android.app.Application
import com.example.webrtcdemo.diag.LogExporter
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.log.FileLogger
import com.example.webrtcdemo.log.LogLevel
import com.example.webrtcdemo.nativebridge.NativeLog
import java.io.File
import java.io.RandomAccessFile

// ============================================================================
// Application 入口（文件：WebRtcDemoApp.kt，文档依据 doc/14 §2.1 / §9.3 / §9.4 / §9.5）
// ----------------------------------------------------------------------------
// 职责（§9.3/§9.4 冻结）：
//   1. 初始化 Kotlin 日志：`<filesDir>/logs/app.log`，默认等级 debug=DEBUG / release=INFO（§9.6）；
//   2. **最早**初始化 native 日志（§9.4 时序）：把日志目录 + 文件基名 + 参数一次性传给 C++，
//      由 C++ 自己 open/write/rename（禁止每行日志跨 JNI）；
//   3. 注册 `Thread.setDefaultUncaughtExceptionHandler`，崩溃栈以 ERROR 写入 app.log 后再交给系统；
//   4. 清理 logs/export/ 中过旧的导出 zip（只留最近 3 个，§9.5 第 4 步）。
//
// 严格时序：`nativeInit` 必须在任何 `NativeVp9Encoder` / `NativeNatDetector` 调用之前完成
// （§9.4）；native 库缺失时只记 WARN 并把日志降级为 logcat，**不得崩溃**。
// ============================================================================

/**
 * 应用 Application：日志与全局异常处理的最早入口。
 */
class WebRtcDemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1) Kotlin 日志初始化（§9.3/§9.6）：默认等级由 BuildConfig.LOG_DEFAULT_DEBUG 决定，
        //    若用户在诊断页改过等级，则以 SharedPreferences("log_cfg") 的 level_kotlin 为准。
        val defaultLevel = if (BuildConfig.LOG_DEFAULT_DEBUG) LogLevel.DEBUG else LogLevel.INFO
        AppLog.init(this, defaultLevel)

        val logDir = AppLog.logDir(this)
        AppLog.i(
            MODULE_TAG,
            "app_create",
            mapOf(
                "log_dir" to logDir.absolutePath,
                "level" to AppLog.level().label,
                "max_bytes" to FileLogger.MAX_FILE_BYTES.toString(),
                "max_files" to FileLogger.MAX_FILES.toString(),
            )
        )

        // 1b) **日志落盘自检**（t25）：真机上曾出现"native.log（C++ 独立写）有记录、而 Kotlin 的
        //     app.log 该进程整段为空"，且**导出物里毫无线索**（`AppLog.emit` 在未初始化时只写 logcat）。
        //     这里同步直写一条 `log_sink_state` → flush → **回读 app.log 验证**；验证失败则写
        //     `app-fallback.log` + `log_sink_degraded`，使"app.log 为空"永远可解释、可导出。
        verifyLogSink(logDir)

        // 2) native 日志最早初始化（§9.4 指定位置）
        //    NativeLog.ensureInitialized 内部保证：先创建 <filesDir>/logs（§9.2）→ **幂等**地
        //    调用 nativeInit(logDir, "native", level, maxBytesPerFile = 2 MiB, maxFiles = 3)（§6.2）→
        //    失败（库缺/目录不可写/任何 Throwable）**只写 logcat、不抛异常**（降级为仅 logcat）。
        if (!NativeLog.ensureInitialized(this, AppLog.level())) {
            AppLog.w(MODULE_TAG, "native_log_init_skipped", mapOf("reason" to "degraded_to_logcat_only"))
            AppLog.critical(MODULE_TAG, "native_log_init_skipped", mapOf("reason" to "degraded_to_logcat_only"))
        }

        // 3) 清理过旧导出（§9.5 第 4 步）
        LogExporter.cleanupOldExports(this)

        // 4) 全局未捕获异常落盘，保证崩溃现场可从导出日志中看到
        installGlobalExceptionLogger()
    }

    /**
     * 安装全局异常处理器：记录完整堆栈 → flush 落盘 → 交回原有处理器。
     *
     * §9.3 明确要求崩溃栈以 ERROR 写入 app.log 后再交给系统。
     */
    private fun installGlobalExceptionLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 此处运行在崩溃线程上；flush 有超时保护（默认 2 s），不会卡死
            AppLog.e(
                MODULE_TAG,
                "uncaught_exception",
                throwable,
            )
            AppLog.flush()
            // native 日志同样落盘（§6.2：flush）；未初始化/库缺失时静默返回，绝不抛异常
            NativeLog.flush()
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 日志落盘自检（t25）。
     *
     * 步骤：同步直写 `log_sink_state` → `AppLog.flush()` → **回读 `app.log`** 校验该标记是否真的在盘上。
     * 失败时：`app-fallback.log` 落一条 `log_sink_degraded`（+ logcat ERROR），
     * 使"Kotlin 通道为空"这类现象**永远能在导出物里找到原因**，而不是只能靠猜。
     *
     * @param logDir 日志目录（`<filesDir>/logs/`）。
     */
    private fun verifyLogSink(logDir: File) {
        val marker = "log_sink_state"
        AppLog.critical(
            MODULE_TAG,
            marker,
            mapOf(
                "dir" to logDir.absolutePath,
                "level" to AppLog.level().label,
                "initialized" to AppLog.isInitialized().toString(),
                "writable" to logDir.canWrite().toString(),
            )
        )
        AppLog.flush()
        val persisted = readTail(File(logDir, FileLogger.FILE_NAME), marker)
        if (!persisted) {
            AppLog.e(
                MODULE_TAG,
                "log_sink_degraded",
                mapOf(
                    "reason" to "marker_not_found_after_flush",
                    "file" to FileLogger.FILE_NAME,
                    "initialized" to AppLog.isInitialized().toString(),
                    "write_failures" to AppLog.fileWriteFailureCount().toString(),
                )
            )
            FileLogger.fallbackMarker(
                "log_sink_degraded reason=marker_not_found_after_flush initialized=${AppLog.isInitialized()} " +
                    "write_failures=${AppLog.fileWriteFailureCount()}"
            )
        }
    }

    /** 回读文件尾部，判断 [needle] 是否落盘（只读最后 64 KiB，避免大文件抖动）。 */
    private fun readTail(file: File, needle: String): Boolean {
        if (!file.isFile) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - READ_TAIL_BYTES).coerceAtLeast(0L)
                raf.seek(start)
                val buf = ByteArray((raf.length() - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8).contains(needle)
            }
        } catch (t: Throwable) {
            false
        }
    }

    private companion object {
        /** §9.1 模块标签（取值见契约白名单 main/ui/signaling/pc/ice/stats/encoder/nat/room/export）。 */
        const val MODULE_TAG = "main"

        /** 自检回读窗口（t25）：只读文件尾 64 KiB。 */
        const val READ_TAIL_BYTES = 64 * 1024L
    }
}
