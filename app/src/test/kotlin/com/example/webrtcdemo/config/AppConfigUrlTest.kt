package com.example.webrtcdemo.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// ============================================================================
// AppConfig.normalizeSignalingUrl 单元测试（doc/14 §8.1 端点冻结）
// ----------------------------------------------------------------------------
// 为什么它可以做纯 JVM 单测：本方法只依赖 java.net.URI，不触碰任何 Android API。
// 运行方式（宿主机）：./gradlew :app:testDebugUnitTest
//
// 覆盖点：
//   · 路径补齐（缺路径 / 根路径 → 补成 /ws）
//   · 冻结路径原样通过（/ws）
//   · 默认端口补齐（ws→80、wss→443）
//   · 非法 scheme / 非法路径（含作废的 /signal）/ 缺 host / 空串 → 返回 null（调用方回退默认）
// ============================================================================

class AppConfigUrlTest {

    @Test
    fun keepsFrozenPathUnchanged() {
        assertEquals("ws://47.238.144.66:8443/ws", AppConfig.normalizeSignalingUrl("ws://47.238.144.66:8443/ws"))
    }

    @Test
    fun appendsFrozenPathWhenMissing() {
        // 手填 ws://host:port（§8.1 冻结路径由 SIGNALING_PATH 补齐）
        assertEquals("ws://1.2.3.4:8443/ws", AppConfig.normalizeSignalingUrl("ws://1.2.3.4:8443"))
        // 根路径同样补齐
        assertEquals("ws://1.2.3.4:8443/ws", AppConfig.normalizeSignalingUrl("ws://1.2.3.4:8443/"))
    }

    @Test
    fun fillsDefaultPortByScheme() {
        assertEquals("ws://1.2.3.4:80/ws", AppConfig.normalizeSignalingUrl("ws://1.2.3.4"))
        assertEquals("wss://example.com:443/ws", AppConfig.normalizeSignalingUrl("wss://example.com"))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("ws://1.2.3.4:8443/ws", AppConfig.normalizeSignalingUrl("  ws://1.2.3.4:8443  "))
    }

    @Test
    fun rejectsNonWebSocketScheme() {
        assertNull(AppConfig.normalizeSignalingUrl("http://1.2.3.4:8443/ws"))
        assertNull(AppConfig.normalizeSignalingUrl("https://1.2.3.4:8443/ws"))
        assertNull(AppConfig.normalizeSignalingUrl("1.2.3.4:8443/ws"))
    }

    @Test
    fun rejectsNonFrozenPath() {
        // doc/05 §7 与 ADR-006 的 /signal 作废（doc/14 C11）
        assertNull(AppConfig.normalizeSignalingUrl("ws://1.2.3.4:8443/signal"))
        // 前缀路径同样不是冻结端点
        assertNull(AppConfig.normalizeSignalingUrl("ws://1.2.3.4:8443/foo/ws"))
    }

    @Test
    fun rejectsMissingHostOrEmpty() {
        assertNull(AppConfig.normalizeSignalingUrl(""))
        assertNull(AppConfig.normalizeSignalingUrl("   "))
        assertNull(AppConfig.normalizeSignalingUrl("ws:///ws"))
    }

    @Test
    fun frozenPathConstantIsUsedByValidation() {
        // 承重断言：常量必须等于冻结端点，且出现在规范化结果里（防止被当作死代码删除）
        assertEquals("/ws", AppConfig.SIGNALING_PATH)
        val normalized = AppConfig.normalizeSignalingUrl("ws://1.2.3.4:8443")
        assertEquals("ws://1.2.3.4:8443" + AppConfig.SIGNALING_PATH, normalized)
    }
}
