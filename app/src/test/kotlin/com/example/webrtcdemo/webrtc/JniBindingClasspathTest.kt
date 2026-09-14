package com.example.webrtcdemo.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// ============================================================================
// libwebrtc Java 绑定回归测试
//   t25 新增：**类存在性**（缺 `*Jni` ⇒ 真机 NoClassDefFoundError）
//   t32 升级：追加**运行期绑定形态**断言（存在 ≠ 可绑定，见下）
// ----------------------------------------------------------------------------
// 背景一（t22 真机缺陷）：交付的 `libwebrtc-java.jar` 里 42 个 jni_zero 生成的 `*Jni` 绑定类
// **一个都没有**（t32 复核：实为 47 个，t22 的扫描漏检 5 个子包类，见下），而 jar 自身的字节码
// （如 `PeerConnectionFactory.initialize`）却直接调用它们
// （`invokestatic org/webrtc/PeerConnectionFactoryJni.get()`）。后果：真机一进通话页即
// `NoClassDefFoundError`，且被上层 catch 吞成笼统文案，排查成本极高。
// 这类缺陷**编译期不报错**（javac 不解析已编译类的引用），所以必须用"运行期类解析"来固化。
//
// 背景二（t27 复核判定"存在性断言会假绿"、K-16）：**类存在 ≠ 可绑定**。本 build 的 `.so` 走
// jni_zero **short/proxy 哈希符号**绑定：`.so` 里只有 193 个 `Java_J_N_M<hash>` 导出，
// 既无可读名、也无 `kMethods`/`RegisterNatives`；Java 侧真正的 `native` 声明必须在运行期类
// **`J.N`**（193 条哈希名）上，而 `org.jni_zero.GEN_JNI` 必须是**纯转发层**（`static native` = 0，
// 方法体 `invokestatic J/N.<hash>`）。若 `GEN_JNI` 仍是编译期 **Placeholder**（194 个可读名
// `static native`、无 `J.N`），则 48/48 全绿也照样在真机首个 native 调用（`PeerConnectionFactory
// .initialize` → `initializeAndroidGlobals`）抛 `UnsatisfiedLinkError`。
// ⇒ 故本测试同时钉住"存在性"（用例 1/2）与"绑定形态"（用例 3/4/5）。
//
// 运行（宿主机）：./gradlew --no-daemon :app:testDebugUnitTest --rerun-tasks
// ============================================================================

/**
 * 断言交付 jar 的 jni_zero 绑定面：**台账齐全（48 项）**且**运行期绑定形态正确**
 * （`J.N` 哈希 native 193 条 + `GEN_JNI` 纯转发层）。
 *
 * 口径来源：`reports/15-java-jar-rebuild.md` §14（t31 落位后实测与判据 ①②③④）、
 * `reports/99-final-report.md` §13.3/§13.4（t27 复核），本文件仅作可回归化。
 */
class JniBindingClasspathTest {

    private companion object {
        /**
         * 必须可解析的绑定类台账 = **47 个 `*Jni` + `org.jni_zero.GEN_JNI` = 48 项**。
         *
         * 说明：`Class.forName(name, false, loader)` 的 `initialize=false` 很关键 ——
         * 这些类的 `static {}` / `get()` 工厂会触碰 native，测试**不应**触发它们。
         *
         * **`org.jni_zero.GEN_JNI` 必须在列**：生成的 `*Jni` 只是中间层
         * （`class PeerConnectionFactoryJni implements PeerConnectionFactory.Natives`），方法体把调用
         * **委托**给 `org.jni_zero.GEN_JNI` ⇒ **只补 `*Jni` 必崩**（`NoClassDefFoundError: org.jni_zero.GEN_JNI`），
         * 本测试若不含它就会"假绿"。
         *
         * ⚠️ 且 **`GEN_JNI` 是分片生成的**：构建树里 **16 个分包分片**各自只含一部分 native；
         * **挑任意一份都会漏方法** ⇒ 必须是**合并后的单一 `GEN_JNI`**（t23 明确要防的错）。
         *
         * **现行口径（t30/t31/t32 实测，取代本注释早前那个过期数字 187）**：
         * **16 个分片并集 = 194 个调用点，其中 193 条被 `.so` 覆盖**（`.so` 导出 `Java_J_N_*` = 193）；
         * 差额恰 1 条 = `org_webrtc_LibaomAv1Encoder_create`（AV1 未编入本 `.so`）——它**必须保留为
         * 抛异常桩声明**（`RuntimeException("Native method not present")`），否则交付 jar 内
         * `LibaomAv1EncoderJni` 对 `GEN_JNI.org_webrtc_LibaomAv1Encoder_create` 的引用会**悬空**
         * （落 193 版会变 `NoSuchMethodError`）。见 `reports/15 §16` 的 A/B 裁定（= B，194/194）。
         *
         * 下面的 `*Jni` 清单 = **t22 实测 42 条 + t32 补齐的 5 条子包漏检 = 47 条**，
         * 连同 `org.jni_zero.GEN_JNI` 共 **48 项**（台账见 `reports/08-android-dev.md` §8.15.3/§8.15.6）。
         * **t22 漏检根因：当时扫描的正则只锚定 `org/webrtc/<Class>Jni` 顶层形态，不支持子包**，
         * 于是漏掉 `org.webrtc.audio.*Jni` ×3（`JavaAudioDeviceModuleJni`、`WebRtcAudioRecordJni`、
         * `WebRtcAudioTrackJni`）与 `org.jni_zero.*Jni` ×2（`JniZeroJni`、`CommonApisJni`）。
         * 断言逻辑不变，失败时会**一次列全**缺失项。
         */
        val REQUIRED_BINDINGS = listOf(
            // —— jni_zero 自身：合并后的转发层 `GEN_JNI` + 2 个运行期胶水类（t32 补齐）——
            "org.jni_zero.CommonApisJni",
            "org.jni_zero.GEN_JNI",
            "org.jni_zero.JniZeroJni",
            // —— org.webrtc 顶层（42 条，t22 台账）——
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
            // —— 子包补齐：t22 漏检 5 条中属于 org.webrtc.audio 的 3 条（默认 ADM / 录音 / 播放路径）——
            "org.webrtc.audio.JavaAudioDeviceModuleJni",
            "org.webrtc.audio.WebRtcAudioRecordJni",
            "org.webrtc.audio.WebRtcAudioTrackJni",
        )

        /** 台账规模：48 = 47 个 `*Jni` + `org.jni_zero.GEN_JNI`。 */
        const val EXPECTED_LEDGER_SIZE = 48

        /** 台账内的 `*Jni` 条数（顶层 42 + `org.webrtc/audio` 3 + `org/jni_zero` 2）。 */
        const val EXPECTED_JNI_CLASS_COUNT = 47

        // ---------- 运行期绑定形态口径（t31 落位后实测，reports/15 §14 判据 ①②③④）----------

        /**
         * `.so` 边界符号数：`libjingle_peerconnection_so.so` 导出的 `Java_J_N_*` 恰 **193** 条
         * （另 `JNI_OnLoad`/`JNI_OnUnLoad` 为分析快照名，非静态可绑定边界）；`J.N` 的 native 声明数须 == 193。
         */
        const val SO_BOUNDARY_SYMBOLS = 193

        /**
         * 调用点总数 = **194**：`*Jni` 的调用名去重后的合计（16 分片并集），
         * = 193 条被 `.so` 覆盖 + 1 条 AV1 已知豁免（`org_webrtc_LibaomAv1Encoder_create`）。
         * `GEN_JNI` 与 `J.N` 的**声明方法总数**（不含构造器）均须 == 194。
         */
        const val CALL_SITE_METHODS = 194

        /** 运行期哈希 native 类（jni_zero short/proxy 模式）：193 条 `M<hash>` native 声明。 */
        const val HASH_NATIVE_CLASS = "J.N"

        /** 纯转发层（合并后）的 `GEN_JNI`。 */
        const val GEN_JNI_CLASS = "org.jni_zero.GEN_JNI"

        /** 唯一已知豁免：AV1 未编入本 `.so`，必须以**非 native 抛异常桩**声明（否则引用悬空）。 */
        const val AV1_EXEMPT_METHOD = "org_webrtc_LibaomAv1Encoder_create"

        /** jni_zero hashed_name 形态：`('M' + b64(md5(name))).rstrip('=')[:8]` ⇒ 恒 8 字符、以 `M` 开头。 */
        const val HASH_NAME_LENGTH = 8
    }

    /** 解析类（不触发初始化）。 */
    private fun resolve(className: String): Class<*> =
        Class.forName(className, false, javaClass.classLoader)

    /** 声明方法（不含构造器）。 */
    private fun declaredMethods(className: String): List<Method> = resolve(className).declaredMethods.toList()

    /** 声明方法中的 native 条数。 */
    private fun declaredNativeCount(className: String): Int =
        declaredMethods(className).count { Modifier.isNative(it.modifiers) }

    /**
     * 用例 1（t25 原有）：台账内每个绑定类都必须可解析；缺失则列出**全部**缺失类。
     *
     * 这条钉的是 **jar 完整性**（t22 缺陷：缺 `*Jni` ⇒ `NoClassDefFoundError`）。
     */
    @Test
    fun referencedJniBindingClassesAreResolvable() {
        val missing = REQUIRED_BINDINGS.filter { name ->
            try {
                resolve(name)
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
     * 用例 2（t32 新增）：台账规模自检 —— 必须恰好 **48 项 = 47 个 `*Jni` + `GEN_JNI`**，且无重复项。
     *
     * 存在意义：本清单是"交付 jar 绑定面"的**唯一台账**，它自身缩水（为绕过失败而删条目、或合并冲突丢一截）
     * 会让用例 1 的断言**静默变弱** —— 这是本测试最危险的失效方式，故用计数把它钉死。
     *
     * 口径来源：t22 实测 42 条 + t32 补齐子包漏检 5 条
     * （`org.webrtc.audio.*Jni` ×3 与 `org.jni_zero.*Jni` ×2；漏检根因 = 旧正则不支持子包）。
     */
    @Test
    fun requiredBindingsLedgerIsComplete() {
        assertEquals(
            "台账缩水会让用例 1 静默变弱：应为 48 项（47 个 *Jni + org.jni_zero.GEN_JNI）",
            EXPECTED_LEDGER_SIZE,
            REQUIRED_BINDINGS.size,
        )
        assertEquals(
            "台账内 *Jni 应为 47 个（顶层 42 + org.webrtc/audio 3 + org/jni_zero 2）",
            EXPECTED_JNI_CLASS_COUNT,
            REQUIRED_BINDINGS.count { it.endsWith("Jni") },
        )
        val duplicates = REQUIRED_BINDINGS.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("REQUIRED_BINDINGS 存在重复项: $duplicates", duplicates.isEmpty())
    }

    /**
     * 用例 3（t32 新增）—— 判据 ①+④：`J.N` 可解析，其声明 **native 数 == 193**（= `.so` 边界符号数），
     * 且每条 native 名都是 jni_zero 的 **8 字符 `M<hash>`** 形态；非 native 声明**恰好 1 条**且就是 AV1 豁免桩。
     *
     * 存在意义：这是"`.so` 的 193 个 `Java_J_N_M*` 在 Java 侧确有对应声明"的**可回归判据**；
     * 若 jar 被换回 Placeholder 形态（无 `J.N`）或误落 193 版（丢 AV1 桩），本用例立即变红。
     */
    @Test
    fun hashNativeClassDeclaresSoBoundaryNatives() {
        val methods = declaredMethods(HASH_NATIVE_CLASS)
        val natives = methods.filter { Modifier.isNative(it.modifiers) }
        assertEquals(
            "$HASH_NATIVE_CLASS 声明的 native 数必须等于 .so 导出边界符号数 $SO_BOUNDARY_SYMBOLS" +
                "（reports/15 §14 E1：双向差集 0/0）；实际 ${natives.size}",
            SO_BOUNDARY_SYMBOLS,
            natives.size,
        )
        val malformed = natives.map { it.name }.filterNot { it.length == HASH_NAME_LENGTH && it.startsWith("M") }
        assertTrue(
            "$HASH_NATIVE_CLASS 的 native 名必须是 jni_zero 哈希形态" +
                "（'M' + 7 字符，恒 $HASH_NAME_LENGTH 字符）：异常项 $malformed",
            malformed.isEmpty(),
        )
        val nonNatives = methods.filterNot { Modifier.isNative(it.modifiers) }.map { it.name }
        assertEquals(
            "$HASH_NATIVE_CLASS 的非 native 声明应恰好 1 条（AV1 absent-proxy 抛异常桩）；实际 $nonNatives",
            1,
            nonNatives.size,
        )
        assertEquals(
            "$HASH_NATIVE_CLASS 的非 native 桩必须是 $AV1_EXEMPT_METHOD；实际 $nonNatives",
            AV1_EXEMPT_METHOD,
            nonNatives.single(),
        )
    }

    /**
     * 用例 4（t32 新增）—— 判据 ②：`org.jni_zero.GEN_JNI` 必须是**纯转发层**，`static native` 数 == 0。
     *
     * 存在意义：**这是"存在 ≠ 可绑定"的关键反面。** Placeholder 形态的 `GEN_JNI` 有 194 个可读名
     * `static native`，它只保证"编译期名字齐全"，运行期真机首跳仍会 `UnsatisfiedLinkError`
     * （`.so` 里只有哈希符号、没有这些可读名，也无 `kMethods` 注册表）。
     */
    @Test
    fun genJniIsPureForwardingLayer() {
        assertEquals(
            "$GEN_JNI_CLASS 必须是纯转发层（static native = 0）；" +
                "若为 194 则可判定仍是编译期 Placeholder 形态（真机首个 native 调用必 UnsatisfiedLinkError）",
            0,
            declaredNativeCount(GEN_JNI_CLASS),
        )
    }

    /**
     * 用例 5（t32 新增）—— 判据 ③：`GEN_JNI` 声明方法总数 == `J.N` 声明方法总数 == **194**，
     * `J.N` 的 194 = **193 native**（哈希名）+ **1 条非 native AV1 桩**；`GEN_JNI` 的 194 = **全部非 native**
     * （193 条转发 + 同 1 条 AV1 桩）⇒ 转发层与哈希 native 类**方法集一一对应**。
     *
     * ⚠️ **口径说明（与任务书 (c) 的差异，已在 reports/08 §8.15.6 记录）**：任务书 (c) 原写
     * "`GEN_JNI` 方法总数 == `J.N` 声明 **native** 数（== 193）"，但 t31 落位（**A/B 裁定 = B**）
     * 后实测为 **194 / 194**：`J.N` = 193 native + 1 条 AV1 抛异常桩；`GEN_JNI` = 193 条转发 +
     * 同 1 条桩。captain 的 t31-D2 口径修订（`reports/15 §16`）已把该值由 193 改为 **194**
     * （`reports/15 §14` 判据 ③ 亦为"两者方法数各 194"）⇒ **本用例按 194 实现**，不按 193。
     * 若按字面 193 实现，测试会在**已正确落位**的 jar 上变红（假红），且为让它变绿只能放宽断言。
     */
    @Test
    fun genJniDeclaresSameMethodCountAsHashNativeClass() {
        val genJniMethods = declaredMethods(GEN_JNI_CLASS).size
        val hashNativeMethods = declaredMethods(HASH_NATIVE_CLASS).size
        assertEquals(
            "$GEN_JNI_CLASS 声明方法数应为 $CALL_SITE_METHODS（= 193 条被 .so 覆盖 + 1 条 AV1 豁免）",
            CALL_SITE_METHODS,
            genJniMethods,
        )
        assertEquals(
            "$GEN_JNI_CLASS 与 $HASH_NATIVE_CLASS 的声明方法数必须相等（转发层与哈希 native 类一一对应）",
            genJniMethods,
            hashNativeMethods,
        )
        assertEquals(
            "$HASH_NATIVE_CLASS 声明方法数应为 native $SO_BOUNDARY_SYMBOLS + AV1 桩 1 = $CALL_SITE_METHODS",
            SO_BOUNDARY_SYMBOLS + 1,
            hashNativeMethods,
        )
        // ⚠️ 注意方向：`GEN_JNI` 的 `native` = 0 ⇒ 它的 **194 个方法全部是"非 native"转发体**
        // （其中 193 条 `invokestatic J/N.<hash>`，1 条 AV1 直接 `new RuntimeException(...)` athrow）。
        // 故此处**不能**断言"非 native 恰 1 条"（那是 `J.N` 的形态：193 native + 1 条非 native 桩）。
        val genJniNames = declaredMethods(GEN_JNI_CLASS).map { it.name }
        assertEquals(
            "$GEN_JNI_CLASS 的方法名去重后应为 $CALL_SITE_METHODS 个（193 条转发生效 + 1 条 AV1 桩）",
            CALL_SITE_METHODS,
            genJniNames.distinct().size,
        )
        assertTrue(
            "$GEN_JNI_CLASS 必须以非 native 桩声明 $AV1_EXEMPT_METHOD" +
                "（否则交付 jar 内 LibaomAv1EncoderJni 对它的引用悬空 → NoSuchMethodError）",
            genJniNames.contains(AV1_EXEMPT_METHOD),
        )
    }

    /**
     * 用例 6（t25 原有）：正向对照 —— **核心 API 类本身**必须可解析。
     *
     * 存在意义：若本用例与用例 1 同时失败，说明是 classpath 接线/依赖问题，而不是"jar 缺绑定类"，
     * 从而区分两类故障、避免误判（也证明用例 1 的缺失不是环境噪音）。
     */
    @Test
    fun coreWebrtcApiClassesAreResolvable() {
        for (name in listOf("org.webrtc.PeerConnectionFactory", "org.webrtc.VideoEncoder")) {
            resolve(name)
        }
    }
}
