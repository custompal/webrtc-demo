package com.example.webrtcdemo.log

import android.util.Log

// ============================================================================
// 日志等级（文件：log/LogLevel.kt，文档依据 doc/14 §2.1）
// ----------------------------------------------------------------------------
// 【冻结】日志级别数值必须与 native 侧一致（doc/14 §6.6）：
//   0 VERBOSE、1 DEBUG、2 INFO、3 WARN、4 ERROR、5 OFF
// Kotlin 与 C++ 使用同一套数值，`NativeLog.nativeInit(..., level: Int, ...)`
// 与 `nativeSetLevel(int)` 直接传递本枚举的 [code]，不得另行映射。
// 本文件不引用任何 JNI / 信令契约类型，可独立编译。
// ============================================================================

/**
 * 日志等级。
 *
 * @property code 与 native 侧一致的数值（0..5），跨 JNI 传递用（doc/14 §6.6）。
 * @property androidPriority 对应的 [android.util.Log] 优先级，仅用于 Logcat 输出。
 * @property label 写入日志文件的等级文本。
 */
enum class LogLevel(
    val code: Int,
    val androidPriority: Int,
    val label: String,
) {
    VERBOSE(0, Log.VERBOSE, "VERBOSE"),
    DEBUG(1, Log.DEBUG, "DEBUG"),
    INFO(2, Log.INFO, "INFO"),
    WARN(3, Log.WARN, "WARN"),
    ERROR(4, Log.ERROR, "ERROR"),

    /** 关闭全部输出（含 Logcat 与文件）。 */
    OFF(5, Log.ASSERT, "OFF"),
    ;

    /**
     * 判断 [level] 是否达到当前阈值。
     *
     * 规则（**阈值语义 = 输出"本级与更严重"的日志**）：`level.code >= code` 才输出；
     * 阈值为 [OFF] 时一律不输出；[WARN]/[ERROR] 在非 OFF 阈值下必然输出。
     *
     * ⚠️ **t44 真机缺陷修复（P0：诊断被日志 Bug 挡住）**：原实现为 `level.code <= code`，
     * 方向与阈值语义**相反** —— 阈值取 `DEBUG(1)` 时只放行 `VERBOSE(0)`/`DEBUG(1)`，
     * 把 `INFO(2)`/`WARN(3)`/`ERROR(4)` **全部丢弃**。真机证据：`app.log` 在
     * `log_level=DEBUG(1)` 下 257 行**全部是 DEBUG**，而 `rtc_config`/`pc_created`/
     * `pc_ice_connection_state`/`pc_create_failed`/`video_track_missing` 命中均为 **0**；
     * native 侧（C++ 同数值规则）INFO/WARN 正常 ⇒ 两侧过滤方向不一致。
     * 修复后与 native 一致：阈值 N ⇒ 输出所有 `code >= N` 的等级（WARN/ERROR 恒输出）。
     *
     * @param level 待判断的日志等级。
     */
    fun isEnabledFor(level: LogLevel): Boolean = this != OFF && level.code >= code

    companion object {
        /**
         * 从 native/配置数值解析等级（doc/14 §6.6）。
         *
         * @param code 0..5；越界时回退 [INFO]，不抛异常。
         */
        fun fromCode(code: Int): LogLevel = entries.firstOrNull { it.code == code } ?: INFO
    }
}
