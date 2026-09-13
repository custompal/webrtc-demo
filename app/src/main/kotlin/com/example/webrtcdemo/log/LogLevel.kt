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
     * 规则：数值 <= 阈值即输出；阈值为 [OFF] 时一律不输出。
     *
     * @param level 待判断的日志等级。
     */
    fun isEnabledFor(level: LogLevel): Boolean = this != OFF && level.code <= code

    companion object {
        /**
         * 从 native/配置数值解析等级（doc/14 §6.6）。
         *
         * @param code 0..5；越界时回退 [INFO]，不抛异常。
         */
        fun fromCode(code: Int): LogLevel = entries.firstOrNull { it.code == code } ?: INFO
    }
}
