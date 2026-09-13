package com.example.webrtcdemo.nativebridge

import android.content.Context
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.log.FileLogger
import com.example.webrtcdemo.log.LogLevel

// ============================================================================
// 表 A-1：`NativeLog`（doc/14 §6.2，4 个方法，逐字冻结）
// ----------------------------------------------------------------------------
// JNI 注册方式：C++ `JNI_OnLoad` → `FindClass("com/example/webrtcdemo/nativebridge/NativeLog")`
//               → `RegisterNatives`，因此**类名、方法名、签名三者逐字不可改**。
//
// §9.4 时序要求：`nativeInit` 必须在**任何** `NativeVp9Encoder` / `NativeNatDetector` 调用之前
//   完成一次。本文件提供 [ensureInitialized] 作为**唯一初始化入口**：
//     · 满足「幂等」（重复调用直接返回，不再重开文件；C++ 侧本身也幂等）；
//     · 满足「失败只写 logcat 不抛异常」（含 UnsatisfiedLinkError 等 Error）；
//     · 由 Kotlin 侧保证 `<filesDir>/logs` 先创建（§9.2/§9.4）。
//   两个调用点：`WebRtcDemoApp.onCreate`（§9.4 指定位置）与
//   `WebRtcEngine.initialize`（创建 factory/编码器之前的硬时序闸口）。
//
// 可见性说明（受控偏离 D-1，已在 reports/08-android-dev.md 登记）：
//   契约 §6.1 建议写成 `internal object`；但 Kotlin 对 `internal` 成员函数做名字修饰
//   （JVM 名变为 `nativeInit$<module>`），会使 `RegisterNatives` 的字面名失配。
//   故这里用 **public object**（类名/方法名/签名仍与 §6.2 逐字一致），
//   并由 ProGuard 规则 `-keep class com.example.webrtcdemo.nativebridge.**` 保护不被裁剪。
// ============================================================================

/**
 * native 日志（§6.2）。
 */
object NativeLog {

    /** §9.2 冻结：C++ 日志文件基名 → `native.log` / `native.1.log` / `native.2.log`。 */
    const val BASE_NAME = "native"

    /** §9.2 冻结：单文件上限 2 MiB。 */
    const val MAX_BYTES_PER_FILE: Long = 2 * 1024 * 1024L

    /** §9.2 冻结：保留 3 个（当前 + 2 历史）。 */
    const val MAX_FILES: Int = 3

    private const val TAG = "main"

    /** 已初始化的日志目录；用于实现「幂等」语义（同一目录不重复调用 C++）。 */
    @Volatile
    private var initializedDir: String? = null

    /**
     * `(Ljava/lang/String;Ljava/lang/String;IJI)V`
     *
     * `(logDir, fileNameBase, level, maxBytesPerFile, maxFiles)`；**幂等**（重复调用先关旧文件）；
     * 失败只写 logcat 不抛异常。
     *
     * 业务代码请改用 [ensureInitialized]（它是本方法的唯一调用者，负责目录创建、幂等与降级）。
     *
     * @param logDir 必须存在且可写（由 Kotlin `mkdirs()` 保证，§9.2/§9.4）。
     * @param fileNameBase 文件基名，冻结为 `"native"`。
     * @param level 日志级别 0..5（§6.6，与 `LogLevel.code` 一致）。
     * @param maxBytesPerFile 单文件上限，§9.2 冻结 `2 * 1024 * 1024`。
     * @param maxFiles 保留文件数（当前 + 历史），§9.2 冻结 `3`。
     */
    @JvmStatic
    external fun nativeInit(
        logDir: String,
        fileNameBase: String,
        level: Int,
        maxBytesPerFile: Long,
        maxFiles: Int,
    )

    /**
     * `(I)V` —— 运行时切换级别（0..5，线程安全）。
     *
     * @param level 级别数值（§6.6）。
     */
    @JvmStatic
    external fun nativeSetLevel(level: Int)

    /** `()V` —— `fflush` + `fsync` 当前日志文件；**导出前必调**（§6.2/§9.5）。 */
    @JvmStatic
    external fun nativeFlush()

    /** `()V` —— 关闭文件；之后 native 日志只落 logcat。 */
    @JvmStatic
    external fun nativeShutdown()

    // ========================================================================
    // Kotlin 侧初始化入口（§9.4）
    // ========================================================================

    /**
     * 幂等初始化 native 日志（§9.4 唯一入口）。
     *
     * 行为：
     *   1. 由 Kotlin 保证 `<filesDir>/logs` 存在且可写（§9.2：Kotlin 负责 `mkdirs()`）；
     *   2. 同一进程、同一目录只真正调用一次 [nativeInit]（幂等，避免重复开关文件）；
     *   3. native 库未加载 / 目录不可写 / 调用抛任何 `Throwable` → **只写 logcat，不抛异常**
     *      （C++ 侧未初始化时自身也只会把日志落到 logcat 并打一次 ERROR，§9.4）。
     *
     * @param context 任意 Context（取 `filesDir`）。
     * @param level 当前日志等级（§9.6：debug `DEBUG` / release `INFO`），数值与 native 一致（§6.6）。
     * @return 是否可用（false 表示已降级为「仅 logcat」）。
     */
    @Synchronized
    fun ensureInitialized(context: Context, level: LogLevel): Boolean {
        val logDir = FileLogger.resolveLogDir(context)
        val path = logDir.absolutePath
        if (initializedDir == path) return true

        if (!logDir.isDirectory || !logDir.canWrite()) {
            AppLog.w(TAG, "native_log_init_failed", mapOf("reason" to "log_dir_not_writable", "dir" to path))
            return false
        }
        if (!NativeLoader.ensureLoaded()) {
            AppLog.w(TAG, "native_log_init_failed", mapOf("reason" to "native_lib_missing"))
            return false
        }
        return try {
            // 契约 §6.2 的字面调用（基名/等级/2 MiB/3 个 均按 §9.2、§9.6 冻结值）
            nativeInit(path, BASE_NAME, level.code, maxBytesPerFile = MAX_BYTES_PER_FILE, maxFiles = MAX_FILES)
            initializedDir = path
            AppLog.i(
                TAG,
                "native_log_init",
                mapOf(
                    "dir" to path,
                    "base" to BASE_NAME,
                    "level" to level.label,
                    "max_bytes" to MAX_BYTES_PER_FILE.toString(),
                    "max_files" to MAX_FILES.toString(),
                )
            )
            true
        } catch (t: Throwable) {
            // 降级：仅 logcat（不抛异常，绝不因日志设施失败而影响通话）
            AppLog.w(TAG, "native_log_init_failed", mapOf("reason" to (t.message ?: t.javaClass.simpleName)))
            false
        }
    }

    /** 当前是否已成功初始化（诊断页展示用）。 */
    fun isInitialized(): Boolean = initializedDir != null

    /**
     * 切换 native 日志级别（§9.6）。
     *
     * @return 是否已下发（native 未初始化时返回 false，不抛异常）。
     */
    fun setLevel(level: LogLevel): Boolean {
        if (initializedDir == null || !NativeLoader.isLoaded()) return false
        return try {
            nativeSetLevel(level.code)
            true
        } catch (t: Throwable) {
            AppLog.w(TAG, "native_log_set_level_failed", mapOf("reason" to (t.message ?: "-")))
            false
        }
    }

    /** 排空 native 日志文件（§6.2：导出前必调；未初始化时静默返回）。 */
    fun flush(): Boolean {
        if (initializedDir == null || !NativeLoader.isLoaded()) return false
        return try {
            nativeFlush()
            true
        } catch (t: Throwable) {
            AppLog.w(TAG, "native_log_flush_failed", mapOf("reason" to (t.message ?: "-")))
            false
        }
    }
}
