package com.example.webrtcdemo.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// 日志阈值过滤方向的 JUnit4 回归（t44 真机缺陷「日志过滤 Bug」）
// ----------------------------------------------------------------------------
// 缺陷：`LogLevel.isEnabledFor` 原为 `this != OFF && level.code <= code`，方向与阈值语义
// **相反** —— 阈值 = DEBUG(1) 时只放行 VERBOSE(0)/DEBUG(1)，把 INFO(2)/WARN(3)/ERROR(4)
// 全部丢弃。真机表现：app.log 在 log_level=DEBUG(1) 下 257 行全是 DEBUG，
// `rtc_config`/`pc_created`/`pc_ice_connection_state`/`pc_create_failed` 命中 0，
// 导致「两端连不通」完全无法定因。
//
// 本测试把「阈值 N ⇒ 输出 code >= N」与 native 侧规则（doc/14 §6.6：0 VERBOSE…5 OFF）
// 锁成对照，防止后人再改回相反方向。
//
// 纯 JVM：`LogLevel` 的 androidPriority 来自 android.jar 的 **static final int 常量**，
// 编译期即内联，故不触发任何 Android 运行时调用（与既有纯 JVM 测试同风格）。
// ============================================================================

class LogLevelFilterTest {

    @Test
    fun `阈值 DEBUG 必须放行 DEBUG 及以上（INFO WARN ERROR），不放行 VERBOSE`() {
        val threshold = LogLevel.DEBUG
        assertFalse("VERBOSE 比 DEBUG 更啰嗦，阈值 DEBUG 时不应输出", threshold.isEnabledFor(LogLevel.VERBOSE))
        assertTrue(threshold.isEnabledFor(LogLevel.DEBUG))
        assertTrue("INFO 被丢弃正是真机缺陷本体", threshold.isEnabledFor(LogLevel.INFO))
        assertTrue(threshold.isEnabledFor(LogLevel.WARN))
        assertTrue(threshold.isEnabledFor(LogLevel.ERROR))
    }

    @Test
    fun `阈值 INFO 放行 INFO 及以上，不放行 DEBUG VERBOSE`() {
        val threshold = LogLevel.INFO
        assertFalse(threshold.isEnabledFor(LogLevel.VERBOSE))
        assertFalse(threshold.isEnabledFor(LogLevel.DEBUG))
        assertTrue(threshold.isEnabledFor(LogLevel.INFO))
        assertTrue(threshold.isEnabledFor(LogLevel.WARN))
        assertTrue(threshold.isEnabledFor(LogLevel.ERROR))
    }

    @Test
    fun `阈值 VERBOSE 放行全部`() {
        val threshold = LogLevel.VERBOSE
        LogLevel.entries.filter { it != LogLevel.OFF }.forEach { level ->
            assertTrue("阈值 VERBOSE 应放行 $level", threshold.isEnabledFor(level))
        }
    }

    @Test
    fun `阈值 OFF 一律不输出（含 ERROR）`() {
        LogLevel.entries.forEach { level ->
            assertFalse("阈值 OFF 不应输出 $level", LogLevel.OFF.isEnabledFor(level))
        }
    }

    @Test
    fun `阈值 ERROR 只放行 ERROR`() {
        val threshold = LogLevel.ERROR
        assertFalse(threshold.isEnabledFor(LogLevel.WARN))
        assertTrue(threshold.isEnabledFor(LogLevel.ERROR))
    }

    @Test
    fun `数值口径与 native 一致（0VERBOSE 1DEBUG 2INFO 3WARN 4ERROR 5OFF）`() {
        assertTrue(LogLevel.VERBOSE.code == 0)
        assertTrue(LogLevel.DEBUG.code == 1)
        assertTrue(LogLevel.INFO.code == 2)
        assertTrue(LogLevel.WARN.code == 3)
        assertTrue(LogLevel.ERROR.code == 4)
        assertTrue(LogLevel.OFF.code == 5)
    }
}
