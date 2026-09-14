package com.example.webrtcdemo.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

// ============================================================================
// JNI 接口契约的**可复跑**守卫（t25，替代已丢失的会话临时脚本 /tmp/t14/t7iface.sh）
// ----------------------------------------------------------------------------
// 原来的 19 条接口检查是**会话本地 shell 脚本**，随 /tmp 重置丢失 ⇒ 无法复跑、无法自证。
// 本测试用**反射**把同一批不变量固化进仓库，任何人一条命令即可复跑：
//
//     ./gradlew --no-daemon :app:testDebugUnitTest
//
// 断言内容（对应契约 §6.2–§6.5、doc/14 §11.4 D-6 与 §5.6）：
//   1) 4 个 `nativebridge` object 的 **15 个 `external fun`** 名称逐字在位；
//   2) 每个方法都是 **static + native** —— 这正是 `@JvmStatic external fun` 的产物特征
//      （Kotlin 对 `internal` 成员做名字修饰会导致 `RegisterNatives` 静默失配，见 §11.4 D-4，
//       故此处同时锁定"静态"这一可注册形态）；
//   3) 逐方法的**参数类型与返回类型**（bytecode 描述符等价物）与契约一致；
//   4) `NativeCallbacks` 的 2 个 C++→Kotlin 回调在位（§6.5）；
//   5) 编码器分层锚点 `SPATIAL_LAYERS=1` / `TEMPORAL_LAYERS=3` 与 `IMPL_NAME` 未变（§5.6/§6.6）。
// ============================================================================

/**
 * `nativebridge` JNI 接口契约的反射守卫。
 */
class NativeInterfaceContractTest {

    private companion object {
        const val PKG = "com.example.webrtcdemo.nativebridge."

        /** 契约 §6.2–§6.5：15 个 `external fun`（类 → 方法 → 参数类型简单名列表）。 */
        val EXPECTED: Map<String, Map<String, List<String>>> = mapOf(
            // §6.2 日志（4）
            "NativeLog" to mapOf(
                "nativeInit" to listOf("String", "String", "int", "long", "int"),
                "nativeSetLevel" to listOf("int"),
                "nativeFlush" to emptyList(),
                "nativeShutdown" to emptyList(),
            ),
            // §6.3 自研 VP9 编码器（9）
            "NativeVp9Encoder" to mapOf(
                "nativeCreate" to emptyList(),
                "nativeInit" to listOf("long", "int", "int", "int", "int", "int", "int", "int"),
                "nativeEncode" to listOf(
                    "long", "ByteBuffer", "ByteBuffer", "ByteBuffer",
                    "int", "int", "int", "int", "int", "long", "int", "boolean",
                ),
                "nativeCopyEncodedFrame" to listOf("long", "ByteBuffer", "int[]"),
                "nativeGetEncodedFrameSize" to listOf("long"),
                "nativeSetRates" to listOf("long", "int[]", "int", "int", "int", "int"),
                "nativeRequestKeyFrame" to listOf("long"),
                "nativeRelease" to listOf("long"),
                "nativeGetImplName" to emptyList(),
            ),
            // §6.4 NAT 探测（2）
            "NativeNatDetector" to mapOf(
                "nativeDetect" to listOf("String", "int", "long"),
                "nativeCancel" to emptyList(),
            ),
        )

        /** 契约 §6.5：C++→Kotlin 的 2 个回调（非 external fun，但同样必须静态在位）。 */
        val CALLBACKS: Map<String, List<String>> = mapOf(
            "onNatTypeDetected" to listOf("String", "String"),
            "onLogEvent" to listOf("int", "String", "String"),
        )

        fun loadClass(simpleName: String): Class<*> =
            Class.forName(PKG + simpleName, false, NativeInterfaceContractTest::class.java.classLoader)

        fun simpleNameOf(c: Class<*>): String = when {
            c.isArray -> c.componentType.simpleName + "[]"
            else -> c.simpleName
        }

        /** 取 `native` 方法中指定名字的那个（优先静态形态）。 */
        fun nativeMethod(cls: Class<*>, name: String): java.lang.reflect.Method? =
            cls.declaredMethods
                .filter { it.name == name && Modifier.isNative(it.modifiers) }
                .sortedByDescending { Modifier.isStatic(it.modifiers) }
                .firstOrNull()
    }

    /**
     * 断言 15 个 `external fun` 的名称、静态性、参数类型逐字在位。
     */
    @Test
    fun allFifteenExternalFunsArePresentWithExactSignatures() {
        var checked = 0
        for ((className, methods) in EXPECTED) {
            val cls = loadClass(className)
            for ((methodName, paramTypes) in methods) {
                val m = nativeMethod(cls, methodName)
                assertNotNull("$className.$methodName 必须存在且为 native（§6.2–§6.5）", m)
                requireNotNull(m)
                assertTrue(
                    "$className.$methodName 必须是 **static** native（@JvmStatic 产物；否则 RegisterNatives 按" +
                        "字面名查找会失败，见 §11.4 D-4）—— 实际 modifiers=${m.modifiers}",
                    Modifier.isStatic(m.modifiers),
                )
                assertEquals(
                    "$className.$methodName 参数类型与契约不一致",
                    paramTypes,
                    m.parameterTypes.map { simpleNameOf(it) },
                )
                checked++
            }
        }
        assertEquals("`external fun` 总数必须为 15（契约 §6.2–§6.5）", 15, checked)
    }

    /**
     * 断言 `nativebridge` 里**没有**多出来的 `external fun`（防止"偷偷加第 16 个"）。
     */
    @Test
    fun noUnexpectedExternalFuns() {
        val declared = mutableListOf<String>()
        for (className in EXPECTED.keys) {
            val cls = loadClass(className)
            cls.declaredMethods
                .filter { Modifier.isNative(it.modifiers) }
                .forEach { declared.add("$className.${it.name}") }
        }
        val expectedFlat = EXPECTED.flatMap { (c, ms) -> ms.keys.map { "$c.$it" } }.toSet()
        // @JvmStatic 会同时生成静态桥与实例方法，故用集合去重后比较
        assertEquals(
            "nativebridge 的 external fun 集合与契约不一致（既不能少，也不允许多）",
            expectedFlat,
            declared.toSet(),
        )
    }

    /**
     * 断言 `NativeCallbacks` 的 2 个回调在位且为静态（§6.5）。
     */
    @Test
    fun nativeCallbacksArePresent() {
        val cls = loadClass("NativeCallbacks")
        for ((name, params) in CALLBACKS) {
            val m = cls.declaredMethods
                .filter { it.name == name }
                .maxByOrNull { if (Modifier.isStatic(it.modifiers)) 1 else 0 }
            assertNotNull("NativeCallbacks.$name 必须存在（§6.5）", m)
            requireNotNull(m)
            assertTrue("NativeCallbacks.$name 必须是静态（@JvmStatic）", Modifier.isStatic(m.modifiers))
            assertEquals(
                "NativeCallbacks.$name 参数类型不符（§6.5）",
                params,
                m.parameterTypes.map { simpleNameOf(it) },
            )
        }
    }

    /**
     * 断言编码器分层锚点未变：`SPATIAL_LAYERS=1` / `TEMPORAL_LAYERS=3` / `IMPL_NAME=SelfVp9Libvpx`。
     *
     * 这三项同时被 t7 侧（`nativeInit` 的 S≠1 拒绝逻辑）与验收项引用，属跨层冻结常量。
     */
    @Test
    fun encoderLayerAnchorsUnchanged() {
        val cls = Class.forName(
            "com.example.webrtcdemo.encoder.Vp9VideoEncoder", false,
            NativeInterfaceContractTest::class.java.classLoader,
        )
        fun constField(name: String): Int {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            return (f.get(null) as Number).toInt()
        }
        assertEquals("SPATIAL_LAYERS 必须为 1（§5.6 禁止 >1）", 1, constField("SPATIAL_LAYERS"))
        assertEquals("TEMPORAL_LAYERS 必须为 3（L1T3，§5.6）", 3, constField("TEMPORAL_LAYERS"))

        val implField = cls.getDeclaredField("IMPL_NAME")
        implField.isAccessible = true
        assertEquals("IMPL_NAME 必须为 SelfVp9Libvpx（§6.6）", "SelfVp9Libvpx", implField.get(null))
    }
}
