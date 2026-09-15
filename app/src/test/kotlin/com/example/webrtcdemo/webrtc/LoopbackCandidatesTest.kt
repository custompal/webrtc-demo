package com.example.webrtcdemo.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// t60：`LoopbackCandidates` 纯 JVM 单测（无 Android / org.webrtc 依赖）
// ----------------------------------------------------------------------------
// 覆盖 t58 §6.3 A2/A3：本端**发送前**与对端候选**接收时**都必须丢弃回环候选。
// 真机依据（t58 §2.1/§2.2）：两台设备都把 `127.0.0.1`/`::1` 写进 SDP 互发
// （di-a 63 处、di-b 176+177+4 条），对端拿回环地址去 CREATE_PERMISSION 被 coturn
// 以 `403 Forbidden IP` 拒绝（探针矩阵：只有 0/8 与 127/8 被拒）。
//
// 运行（宿主机，容器内无 JDK）：./gradlew --no-daemon :app:testDebugUnitTest
// ============================================================================

class LoopbackCandidatesTest {

    /** IPv4 回环整段 127.0.0.0/8 都必须被过滤。 */
    @Test
    fun filtersIpv4LoopbackRange() {
        assertTrue(LoopbackCandidates.isLoopback("127.0.0.1"))
        assertTrue(LoopbackCandidates.isLoopback("127.0.0.53"))
        assertTrue(LoopbackCandidates.isLoopback("127.255.255.254"))
        assertTrue(LoopbackCandidates.isLoopback("127.1.2.3"))
    }

    /** IPv6 回环 `::1`（含展开写法与方括号写法）。 */
    @Test
    fun filtersIpv6Loopback() {
        assertTrue(LoopbackCandidates.isLoopback("::1"))
        assertTrue(LoopbackCandidates.isLoopback("0:0:0:0:0:0:0:1"))
        assertTrue(LoopbackCandidates.isLoopback("[::1]"))
        assertTrue(LoopbackCandidates.isLoopback(" ::1 "))
    }

    /** IPv4-mapped 写法（`::ffff:127.0.0.1`）同样是回环。 */
    @Test
    fun filtersIpv4MappedLoopback() {
        assertTrue(LoopbackCandidates.isLoopback("::ffff:127.0.0.1"))
        assertTrue(LoopbackCandidates.isLoopback("::FFFF:127.0.0.1"))
    }

    /** 常见可用地址**不得**被误过滤（真机日志里的地址形态）。 */
    @Test
    fun keepsUsableAddresses() {
        assertFalse(LoopbackCandidates.isLoopback("192.168.1.101"))
        assertFalse(LoopbackCandidates.isLoopback("10.1.146.251"))
        assertFalse(LoopbackCandidates.isLoopback("172.21.0.219"))
        assertFalse(LoopbackCandidates.isLoopback("120.233.71.120"))
        assertFalse(LoopbackCandidates.isLoopback("223.104.67.210"))
        assertFalse(LoopbackCandidates.isLoopback("47.238.144.66"))
        assertFalse(LoopbackCandidates.isLoopback("2409:895a:e98:b48:fc14:fdff:fe54:7c0c"))
        assertFalse(LoopbackCandidates.isLoopback("::"))
        // 链路本地/私网由服务端策略裁决（t58 附录 C），客户端**不**越权过滤
        assertFalse(LoopbackCandidates.isLoopback("169.254.1.1"))
    }

    /** 空/畸形地址保守放行：解析失败宁可多发一条候选，也不能丢掉可用路径。 */
    @Test
    fun malformedAddressesAreNotFiltered() {
        assertFalse(LoopbackCandidates.isLoopback(""))
        assertFalse(LoopbackCandidates.isLoopback("   "))
        assertFalse(LoopbackCandidates.isLoopback("host"))
        assertFalse(LoopbackCandidates.isLoopback("127.0.0"))
        assertFalse(LoopbackCandidates.isLoopback("127.0.0.0.1"))
        assertFalse(LoopbackCandidates.isLoopback("127.0.0.256"))
        assertFalse(LoopbackCandidates.isLoopback("a.b.c.d"))
    }
}
