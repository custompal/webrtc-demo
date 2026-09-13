package com.example.webrtcdemo.webrtc

import android.content.Context
import com.example.webrtcdemo.config.AppConfig
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
            val builder = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            if (AppConfig.useDefaultEncoder(appContext)) {
                // 受控偏离（§7.1 允许的诊断对照）：必须在报告中登记
                AppLog.w(TAG, "encoder_fallback", mapOf("reason" to "USE_DEFAULT_ENCODER=true（对照实验）"))
                builder.setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                )
            } else {
                builder.setVideoEncoderFactory(Vp9VideoEncoderFactory())
            }
            val factory = builder.createPeerConnectionFactory()

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
                )
            )
            true
        } catch (t: Throwable) {
            AppLog.e(TAG, "engine_init_failed", emptyMap(), t)
            cleanupAfterFailure()
            false
        }
    }

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
