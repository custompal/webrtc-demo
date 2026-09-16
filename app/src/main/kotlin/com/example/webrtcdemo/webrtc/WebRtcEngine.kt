package com.example.webrtcdemo.webrtc

import android.content.Context
import com.example.webrtcdemo.config.AppConfig
import com.example.webrtcdemo.encoder.EncoderFallbackController
import com.example.webrtcdemo.encoder.EncoderOverrideMode
import com.example.webrtcdemo.encoder.Vp9VideoEncoder
import com.example.webrtcdemo.encoder.Vp9VideoEncoderFactory
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.nativebridge.NativeLoader
import com.example.webrtcdemo.nativebridge.NativeLog
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory

// ============================================================================
// WebRTC 引擎（doc/14 §2.1 / §7.1）—— EglBase / PeerConnectionFactory 生命周期
// ----------------------------------------------------------------------------
// §7.1 冻结初始化顺序（进程内**只做一次**）：
//   1) native 日志最早初始化（§9.4，实际调用点放在 WebRtcDemoApp.onCreate）
//   2) PeerConnectionFactory.initialize(InitializationOptions…)  ← 必须在任何 factory/PC 之前
//      · setEnableInternalTracer(false)
//      · setInjectableLogger(LibwebrtcLoggable, BuildConfig.DEBUG ? LS_VERBOSE : LS_INFO)（§9.7）
//   3) EglBase.create()（进程内单例）
//   4) PeerConnectionFactory.builder()
//      .setVideoEncoderFactory(Vp9VideoEncoderFactory())        ← A1 注入路线（§5.4）
//      .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl)) ← MediaCodec 硬解
//      .createPeerConnectionFactory()
// 「hangup **不销毁**」：EglBase / PeerConnectionFactory / SurfaceTextureHelper 常驻进程。
//
// 禁止：把 DefaultVideoEncoderFactory 作为主编码器（§7.1，会失去学习点）；
//       仅当诊断开关 `USE_DEFAULT_ENCODER=true` 时允许临时替换（用于 doc/03 阶段 3 对照实验，
//       必须在报告中登记 —— 见 reports/08-android-dev.md）。
// ============================================================================

/**
 * WebRTC 引擎单例：持有 EglBase / PeerConnectionFactory / 采集 / 渲染池 / 音频轨。
 */
object WebRtcEngine {

    private const val TAG = "pc"

    @Volatile
    private var initialized = false

    /**
     * 最近一次初始化失败的**可诊断详情**（形如 `NoClassDefFoundError: org.webrtc.PeerConnectionFactoryJni`）。
     *
     * 用途：`initialize()` 只返回 Boolean，调用方（`CallViewModel` / 诊断页）需要把**真实异常**呈现给用户与日志，
     * 否则只剩一句笼统文案（真机排障成本极高 —— 见 t22/t25 的缺陷记录）。
     * 成功时清空；仅在失败路径写入。
     */
    @Volatile
    private var lastFailure: String? = null

    /**
     * 启动自检：libwebrtc Java 绑定类是否可用（t25 新增，防御性）。
     *
     * 背景：交付 jar 曾缺失 42 个 jni_zero 生成的 `*Jni` 类（`NoClassDefFoundError`），
     * 但报错发生在 `PeerConnectionFactory.initialize(...)` 深处、且被我们吞掉，现象是笼统的"引擎初始化失败"。
     * 这里在调用前**主动**探测一次，命中即打**专属事件** `jni_binding_missing` 并快速失败。
     *
     * 注意：用 `Class.forName(name, initialize = false, loader)` —— **不触发类初始化**
     * （初始化这些类会调 `get()` 工厂 → 进而触碰 native，探测本身不应产生副作用）。
     */
    private fun missingBindingClass(): String? =
        BINDING_CLASSES.firstOrNull { name ->
            try {
                Class.forName(name, false, WebRtcEngine::class.java.classLoader)
                false
            } catch (t: Throwable) {
                true
            }
        }

    private var egl: EglBase? = null

    private var peerConnectionFactory: PeerConnectionFactory? = null

    private var capture: MediaCapture? = null

    private var renderers: VideoRendererPool? = null

    private var audioSource: AudioSource? = null

    private var audioTrack: AudioTrack? = null

    /**
     * 初始化引擎（幂等）。必须在创建任何 PeerConnection 之前调用。
     *
     * @param context 任意 Context。
     * @return 是否可用（native 库缺失时返回 false，不抛异常）。
     */
    @Synchronized
    fun initialize(context: Context): Boolean {
        if (initialized) return true
        val appContext = context.applicationContext
        val nativeOk = NativeLoader.ensureLoaded()
        if (!nativeOk) {
            AppLog.e(TAG, "engine_init_skipped", mapOf("reason" to "native_lib_missing"))
            // t25：同一事件再落一条**同步直写**（保证 app.log 里必有），字段与上面一致。
            AppLog.critical(TAG, "engine_init_skipped", mapOf("reason" to "native_lib_missing"))
            lastFailure = "UnsatisfiedLinkError: libwebrtcdemo_native.so（native 库加载失败，详见 native_lib_load_failed）"
            return false
        }
        // t25：native 库就绪是"引擎生命周期"的关键一步，同步直写保证可导出（此前只有 AsyncApp 路径）。
        AppLog.critical(TAG, "engine_native_loaded", mapOf("lib" to NativeLoader.LIBRARY_NAME))
        // t25 新增自检：绑定类缺失时给出**专属事件 + 确切类名**，而不是让它在 initialize() 深处变成笼统报错。
        val missing = missingBindingClass()
        if (missing != null) {
            AppLog.e(TAG, "jni_binding_missing", mapOf("cls" to missing))
            AppLog.critical(TAG, "jni_binding_missing", mapOf("cls" to missing))
            lastFailure = "NoClassDefFoundError: $missing"
            return false
        }
        // §9.4 时序闸口：本方法是「创建 factory → 用 NativeVp9Encoder」与「NAT 探测」的唯一上游，
        // 因此在这里再确保一次 native 日志已初始化（ensureInitialized 幂等，重复调用不会重开文件）。
        NativeLog.ensureInitialized(appContext, AppLog.level())
        return try {
            // 2) 全局初始化：sink 注册一次性（§9.7），必须最早
            //    同时把本次注入的级别记录到 SharedPreferences("log_cfg":level_webrtc)（§9.6）
            AppLog.persistWebrtcLevel(appContext, LibwebrtcLoggable.injectedSeverity().name)
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .setInjectableLogger(LibwebrtcLoggable, LibwebrtcLoggable.injectedSeverity())
                    .createInitializationOptions()
            )
            // 3) EglBase 单例
            val eglBase = EglBase.create()
            // 4) 工厂（注入自研 VP9 编码器）
            // 【t92】关闭 FrameDropper：Java 编码器的 EncoderInfo.has_trusted_rate_controller
            // 恒为 false（native 包装器未设置）⇒ video_stream_encoder.cc:2016-2019 里
            // frame_dropping_enabled 恒真 ⇒ 拥塞时按目标帧率丢输入帧（真机 `Drop Frame:` 可见）。
            // 置 "WebRTC-FrameDropper/Disabled/"（:108/:1459-1463）后 dropped 关闭，降级交给
            // t91 的 ScalingSettings(24,37)（降分辨率）与 t89 的码率地板（保下限）。
            val builder = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setFieldTrials(FrameDropperFieldTrial.DISABLED_TRIAL)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            if (AppConfig.useDefaultEncoder(appContext)) {
                // 受控偏离（§7.1 允许的诊断对照）：必须在报告中登记
                AppLog.w(TAG, "encoder_fallback", mapOf("reason" to "USE_DEFAULT_ENCODER=true（对照实验）"))
                builder.setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                )
            } else {
                // 【t87】降级兜底的默认实现来源：**只**在判定需要降级时用于创建编码器，
                // 不参与 getSupportedCodecs ⇒ 协商列表仍是「只有 VP9」（§5.4 契约不变）。
                val defaultEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                EncoderFallbackController.configure(
                    EncoderOverrideMode.fromWire(AppConfig.encoderOverride(appContext)),
                    AppConfig.encoderFallbackEnabled(appContext),
                )
                builder.setVideoEncoderFactory(
                    Vp9VideoEncoderFactory(defaultEncoderFactory = defaultEncoderFactory)
                )
            }
            val factory = builder.createPeerConnectionFactory()
            AppLog.i(TAG, "field_trials_set", mapOf("frame_dropper" to FrameDropperFieldTrial.DISABLED_TRIAL))

            val audioSource0 = factory.createAudioSource(MediaConstraints())
            val audioTrack0 = factory.createAudioTrack(WebRtcConfig.AUDIO_TRACK_ID, audioSource0)

            egl = eglBase
            peerConnectionFactory = factory
            audioSource = audioSource0
            audioTrack = audioTrack0
            capture = MediaCapture(appContext, eglBase, factory)
            renderers = VideoRendererPool(eglBase)
            initialized = true
            AppLog.i(
                TAG,
                "engine_ready",
                mapOf(
                    "impl" to Vp9VideoEncoder.IMPL_NAME,
                    "use_default_encoder" to AppConfig.useDefaultEncoder(appContext).toString(),
                    // 【t87】兜底配置一并落盘，便于真机判断「是没触发还是没开」
                    "encoder_mode" to EncoderFallbackController.uiState.value.mode.wire,
                    "encoder_fallback" to EncoderFallbackController.uiState.value.fallbackEnabled.toString(),
                )
            )
            // t25：关键事件同步直写，保证 app.log 中必有（不依赖异步队列）。
            AppLog.critical(
                TAG,
                "engine_ready",
                mapOf(
                    "impl" to Vp9VideoEncoder.IMPL_NAME,
                    "use_default_encoder" to AppConfig.useDefaultEncoder(appContext).toString(),
                    "encoder_mode" to EncoderFallbackController.uiState.value.mode.wire,
                    "encoder_fallback" to EncoderFallbackController.uiState.value.fallbackEnabled.toString(),
                )
            )
            true
        } catch (t: Throwable) {
            // t25：**只新增字段**（事件名 `engine_init_failed` 不变）—— 让真机日志一眼区分
            // NoClassDefFoundError / UnsatisfiedLinkError / IllegalStateException / GLException 等。
            val detail = describe(t)
            lastFailure = detail
            val fields = LinkedHashMap<String, String>(4)
            fields["ex"] = t.javaClass.name
            fields["msg"] = t.message?.take(300) ?: "-"
            t.cause?.let { fields["cause"] = it.javaClass.name }
            AppLog.e(TAG, "engine_init_failed", fields, t)
            // t25：**同步直写**同事件（含 ex/msg/cause 字段）—— 即使异步写盘路径失效，
            // app.log 里也必然有一条 engine_init_failed 可导出；堆栈仍由上面的 AppLog.e 完整记录。
            AppLog.critical(TAG, "engine_init_failed", fields)
            cleanupAfterFailure()
            false
        }
    }

    /**
     * 最近一次初始化失败的可诊断详情（`异常类名: message`；无 cause 链首行）。
     *
     * @return 失败详情；从未失败或已成功时为 `null`。
     */
    fun lastFailureDetail(): String? = lastFailure

    /** 把 Throwable 压成一行可读文本（UI/日志共用口径）。 */
    private fun describe(t: Throwable): String {
        val msg = t.message?.replace('\n', ' ')?.take(300)
        return if (msg.isNullOrBlank()) t.javaClass.name else "${t.javaClass.name}: $msg"
    }

    /**
     * libwebrtc Java 绑定类清单（t25 自检用）。
     *
     * 这些类由 jni_zero **在 libwebrtc 构建期生成**，必须先被引用它们的 API 类解析到；
     * 缺失即 `NoClassDefFoundError`（t22 已确证的交付缺陷）。
     *
     * **为何必须包含 `org.jni_zero.GEN_JNI`**（native-dev 于宿主机反编译实测，2026-09-14）：
     * 生成的 `*Jni` 类只是**中间层**（`class PeerConnectionFactoryJni implements PeerConnectionFactory.Natives`，
     * 方法体把调用**委托**给 `GEN_JNI.org_webrtc_...(...)`），**真正声明 `public static native` 的是 `GEN_JNI`**。
     * 因此只补 `*Jni` 而漏 `GEN_JNI` 仍会崩 —— 自检必须把两者都覆盖，否则会给出"半修复通过"的假绿。
     * 列表保持短（O(5)），覆盖初始化链与首个 JNI 调用链的关键入口。
     */
    private val BINDING_CLASSES = listOf(
        "org.jni_zero.GEN_JNI",                  // 真正的 native 声明持有者（所有 *Jni 的委托目标）
        "org.webrtc.PeerConnectionFactoryJni",   // PeerConnectionFactory.initialize / createAudioSource 的调用目标
        "org.webrtc.PeerConnectionJni",          // PeerConnection（通话建立后第一条 JNI 链）
        "org.webrtc.VideoTrackJni",              // 视频轨（渲染/预览绑定）
        "org.webrtc.JniCommonJni",               // jni_zero 公共入口（refcount 等）
    )

    /** 共享的 PeerConnectionFactory（未初始化时为 null）。 */
    fun factory(): PeerConnectionFactory? = peerConnectionFactory

    /** 共享的 EglBase（未初始化时为 null）。 */
    fun eglBase(): EglBase? = egl

    /** 采集链（未初始化时为 null）。 */
    fun mediaCapture(): MediaCapture? = capture

    /** 渲染池（未初始化时为 null）。 */
    fun rendererPool(): VideoRendererPool? = renderers

    /** 共享音频轨（未初始化时为 null）。 */
    fun audioTrack(): AudioTrack? = audioTrack

    /** 引擎是否可用。 */
    fun isReady(): Boolean = initialized

    /** 释放引擎（进程退出/测试用；`hangup` **不**调用本方法，§7.1）。 */
    @Synchronized
    fun shutdown() {
        if (!initialized) return
        capture?.release()
        audioTrack?.dispose()
        audioSource?.dispose()
        peerConnectionFactory?.dispose()
        egl?.release()
        capture = null
        renderers = null
        audioTrack = null
        audioSource = null
        peerConnectionFactory = null
        egl = null
        initialized = false
        AppLog.i(TAG, "engine_shutdown")
    }

    /** 初始化失败时的清理（尽力而为，忽略二次异常）。 */
    private fun cleanupAfterFailure() {
        try {
            capture?.release()
        } catch (ignored: Throwable) {
            // 二次异常忽略
        }
        capture = null
        renderers = null
        initialized = false
    }
}
