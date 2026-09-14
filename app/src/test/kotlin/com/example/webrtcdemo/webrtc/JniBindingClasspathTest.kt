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
         * **`org.jni_zero.GEN_JNI` 必须在列，且必须是【合并后】的那一份**（native-dev 宿主机反编译实测，2026-09-14）：
         * 生成的 `*Jni` 只是中间层（`class PeerConnectionFactoryJni implements PeerConnectionFactory.Natives`），
         * 方法体把调用**委托**给 `org.jni_zero.GEN_JNI`（`public static native` 声明在那里）。
         * ⇒ **只补 `*Jni` 必崩**（`NoClassDefFoundError: org.jni_zero.GEN_JNI`），本测试若不含它就会"假绿"。
         * ⚠️ 且 **`GEN_JNI` 是分片生成的**：构建树里 16 个分片各自只含一部分 native（分包合计 187 ↔
         * `.so` 边界 193），**挑任意一份都会漏方法** ⇒ 必须是**合并后**的单一 `GEN_JNI`（t23 明确要防的错）。
         *
         * 下面的 `*Jni` 清单 = **t22 实测的 42 个"被 jar 引用但 jar 内不存在"的绑定类全量台账**
         * （见 reports/08-android-dev.md §8.15.3）：断言逻辑不变，失败时会**一次列全**缺失项。
         */
        val REQUIRED_BINDINGS = listOf(
            "org.jni_zero.GEN_JNI",
            "org.webrtc.AudioTrackJni",
            "org.webrtc.BuiltinAudioDecoderFactoryFactoryJni",
            "org.webrtc.BuiltinAudioEncoderFactoryFactoryJni",
            "org.webrtc.CallSessionFileRotatingLogSinkJni",
            "org.webrtc.DataChannelJni",
            "org.webrtc.DtmfSenderJni",
            "org.webrtc.EglBase10ImplJni",
            "org.webrtc.EnvironmentJni",
            "org.webrtc.H264UtilsJni",
            "org.webrtc.HistogramJni",
            "org.webrtc.JavaI420BufferJni",
            "org.webrtc.JniCommonJni",
            "org.webrtc.LibaomAv1EncoderJni",
            "org.webrtc.LibvpxVp8DecoderJni",
            "org.webrtc.LibvpxVp8EncoderJni",
            "org.webrtc.LibvpxVp9DecoderJni",
            "org.webrtc.LibvpxVp9EncoderJni",
            "org.webrtc.LoggingJni",
            "org.webrtc.MediaSourceJni",
            "org.webrtc.MediaStreamJni",
            "org.webrtc.MediaStreamTrackJni",
            "org.webrtc.MetricsJni",
            "org.webrtc.NV12BufferJni",
            "org.webrtc.NV21BufferJni",
            "org.webrtc.NativeAndroidVideoTrackSourceJni",
            "org.webrtc.NetworkMonitorJni",
            "org.webrtc.PeerConnectionFactoryJni",
            "org.webrtc.PeerConnectionJni",
            "org.webrtc.RtcCertificatePemJni",
            "org.webrtc.RtpReceiverJni",
            "org.webrtc.RtpSenderJni",
            "org.webrtc.RtpTransceiverJni",
            "org.webrtc.SoftwareVideoDecoderFactoryJni",
            "org.webrtc.SoftwareVideoEncoderFactoryJni",
            "org.webrtc.TimestampAlignerJni",
            "org.webrtc.TurnCustomizerJni",
            "org.webrtc.VideoDecoderFallbackJni",
            "org.webrtc.VideoDecoderWrapperJni",
            "org.webrtc.VideoEncoderFallbackJni",
            "org.webrtc.VideoEncoderWrapperJni",
            "org.webrtc.VideoTrackJni",
            "org.webrtc.YuvHelperJni",
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
