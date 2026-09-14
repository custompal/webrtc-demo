package com.example.webrtcdemo.webrtc

import org.junit.Assert.assertTrue
import org.junit.Test

// ============================================================================
// libwebrtc **Java 绑定类存在性**回归测试（t25 新增）
// ----------------------------------------------------------------------------
// 背景（t22 真机缺陷）：交付的 `libwebrtc-java.jar` 里 42 个 jni_zero 生成的 `*Jni` 绑定类
// **一个都没有**，而 jar 自身的字节码（如 `PeerConnectionFactory.initialize`）却直接调用它们
// （`invokestatic org/webrtc/PeerConnectionFactoryJni.get()`）。后果：真机一进通话页即
// `NoClassDefFoundError`，且被上层 catch 吞成笼统文案，排查成本极高。
//
// 这类缺陷**编译期不报错**（javac 不解析已编译类的引用），所以必须用"运行期类解析"来固化：
// 本测试断言这些绑定类能在 classpath 解析 —— **修复前必然失败、修复后必须通过**。
//
// 运行（宿主机）：./gradlew --no-daemon :app:testDebugUnitTest
// ============================================================================

/**
 * 断言被 libwebrtc Java API 引用的 jni_zero 绑定类在 classpath 可解析。
 */
class JniBindingClasspathTest {

    private companion object {
        /**
         * 必须可解析的绑定类（取自初始化链与首个 JNI 调用链）。
         *
         * 说明：`Class.forName(name, false, loader)` 的 `initialize=false` 很关键 ——
         * 这些类的 `static {}` / `get()` 工厂会触碰 native，测试**不应**触发它们。
         *
         * **`org.jni_zero.GEN_JNI` 必须在列**（native-dev 宿主机反编译实测，2026-09-14）：
         * 生成的 `*Jni` 只是中间层，方法体把调用委托给 `GEN_JNI`（那里才是 `public static native`）。
         * 少了它，即使 `*Jni` 全部补齐，真机仍抛 `NoClassDefFoundError`；本测试若不含它就会"假绿"
         * —— 这正是它被放进来的原因。完整 42 个缺失 `*Jni` 台账见 reports/08-android-dev.md §8.15.3
         * （测试只钉关键入口，避免清单随构建演进产生误报）。
         */
        val REQUIRED_BINDINGS = listOf(
            "org.jni_zero.GEN_JNI",
            "org.webrtc.PeerConnectionFactoryJni",
            "org.webrtc.PeerConnectionJni",
            "org.webrtc.VideoTrackJni",
            "org.webrtc.JniCommonJni",
        )
    }

    /**
     * 每个绑定类都必须可解析；缺失则列出**全部**缺失类，便于一次性看清缺陷范围。
     */
    @Test
    fun referencedJniBindingClassesAreResolvable() {
        val loader = javaClass.classLoader
        val missing = REQUIRED_BINDINGS.filter { name ->
            try {
                Class.forName(name, false, loader)
                false
            } catch (t: Throwable) {
                true
            }
        }
        assertTrue(
            "classpath 缺少 libwebrtc jni_zero 绑定类: $missing —— 交付 jar 不完整" +
                "（真机将抛 NoClassDefFoundError；需重新产出含 *Jni 的 libwebrtc-java.jar）",
            missing.isEmpty(),
        )
    }

    /**
     * 正向对照：**核心 API 类本身**必须可解析。
     *
     * 存在意义：若本测试同时失败，说明是 classpath 接线/依赖问题，而不是"jar 缺绑定类"，
     * 从而区分两类故障、避免误判（也证明上面的缺失断言不是环境噪音）。
     */
    @Test
    fun coreWebrtcApiClassesAreResolvable() {
        val loader = javaClass.classLoader
        for (name in listOf("org.webrtc.PeerConnectionFactory", "org.webrtc.VideoEncoder")) {
            Class.forName(name, false, loader)
        }
    }
}
