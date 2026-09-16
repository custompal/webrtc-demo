package com.example.webrtcdemo.encoder

import com.example.webrtcdemo.log.AppLog
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory

// ============================================================================
// VP9 编码器工厂（doc/14 §5.3/§5.4，A1 注入路线的 Kotlin 侧入口）
// ----------------------------------------------------------------------------
// 契约行为（逐条冻结）：
//   createEncoder(info)   info == null → null；name == "VP9"（忽略大小写）→ Vp9VideoEncoder()；
//                         否则 fallback?.createEncoder(info)
//   getSupportedCodecs()  arrayOf(VideoCodecInfo("VP9", {"profile-id":"0"})) + fallback 的支持列表
//   getImplementations()  与 getSupportedCodecs() 相同
// 默认 fallback 为 null：本项目**禁止**把 DefaultVideoEncoderFactory 作为主编码器
// （§7.1：会走硬件编码、失去学习点；诊断开关 USE_DEFAULT_ENCODER 时才临时替换）。
//
// 【t87 扩展（自研编码自动降级兜底）】新增两条**只影响编码实现选择、不改协商列表**的能力：
//   1. `defaultEncoderFactory`：默认（硬件优先）实现来源，**只用于「降级后创建编码器」**，
//      不进入 [getSupportedCodecs]/[getImplementations] ⇒ SDP 协商仍只出现 VP9（契约不变）；
//   2. `getEncoderSelector()`：通话内切换通道（libwebrtc `VideoEncoderFactory` +
//      `VideoEncoderSelector`；本版 jar/AAR 实测存在 `getEncoderSelector()` 与
//      `onCurrentEncoder/onAvailableBitrate/onResolutionChange/onEncoderBroken`，
//      `.so` 内亦含 `RecreateWebRtcStream (send) because of SetEncoderSelector` ⇒ 原生侧支持
//      由 selector 驱动的编码器重建）。selector 返回非空格式即“请求切换”，原生随后会**再次**
//      调用本工厂的 `createEncoder(info)` —— 那一刻 [EncoderFallbackController.implForCreate]
//      已返回 [EncoderImpl.DEFAULT]，于是这里返回默认实现；该次创建同时被记为切换确认。
//
// 兼容性：`defaultEncoderFactory == null` 且无降级请求时，本类行为与 t87 之前**完全一致**
// （纯自研 VP9、仅提供 VP9）。
// ============================================================================

/**
 * 自研 VP9 编码器工厂。
 *
 * @param fallback 回退工厂（用于非 VP9 编解码请求）；默认 null（只提供 VP9，强制走自研编码器）。
 * @param defaultEncoderFactory 默认（硬件优先）实现来源（t87 降级兜底用）；默认 null = 关闭兜底。
 */
class Vp9VideoEncoderFactory(
    private val fallback: VideoEncoderFactory? = null,
    private val defaultEncoderFactory: VideoEncoderFactory? = null,
) : VideoEncoderFactory {

    /**
     * 创建编码器。
     *
     * @param info 目标编解码信息；null 时返回 null（契约要求）。
     * @return 命中 VP9 时返回 [Vp9VideoEncoder] 或降级后的默认实现；否则交给 [fallback]。
     */
    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? {
        if (info == null) return null
        if (info.name.equals(CODEC_VP9, ignoreCase = true)) {
            // 【t87】降级判定：只有在“需要默认实现”时才走 defaultEncoderFactory，
            // 其余情况保持原行为（自研 VP9）—— 策略判定全在纯函数/控制器里，本类不做策略。
            if (EncoderFallbackController.implForCreate() == EncoderImpl.DEFAULT) {
                val created = defaultEncoderFactory?.createEncoder(info)
                if (created != null) {
                    val implName = runCatching { created.implementationName }.getOrNull()
                    EncoderFallbackController.onEncoderCreated(EncoderImpl.DEFAULT, implName)
                    AppLog.i(
                        TAG,
                        "encoder_created",
                        mapOf(
                            "impl" to (implName ?: DEFAULT_IMPL_UNKNOWN),
                            "codec" to CODEC_VP9,
                            "mech" to "fallback_default",
                        ),
                    )
                    return created
                }
                // 默认实现不可用（例如设备/工厂两侧都不支持）：**不中断通话**，继续自研并撤销降级意图
                EncoderFallbackController.onDefaultUnavailable("default_factory_null")
                AppLog.w(
                    TAG,
                    "encoder_fallback_unavailable",
                    mapOf("reason" to "default_factory_null", "codec" to CODEC_VP9),
                )
            }
            AppLog.i(
                TAG,
                "encoder_created",
                mapOf("impl" to Vp9VideoEncoder.IMPL_NAME, "codec" to CODEC_VP9, "profile" to VP9_PROFILE_ID),
            )
            return Vp9VideoEncoder()
        }
        return fallback?.createEncoder(info)
    }

    /**
     * 本工厂支持的编解码列表（VP9 + fallback）。
     *
     * @return `[VideoCodecInfo("VP9", {"profile-id":"0"})]`（有 fallback 时追加其列表）。
     */
    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val mine = arrayOf(VideoCodecInfo(CODEC_VP9, mapOf(PARAM_PROFILE_ID to VP9_PROFILE_ID)))
        val extra = fallback?.supportedCodecs ?: emptyArray<VideoCodecInfo>()
        return if (extra.isEmpty()) mine else mine + extra
    }

    /** 与 [getSupportedCodecs] 相同（§5.4 冻结）。 */
    override fun getImplementations(): Array<VideoCodecInfo> = getSupportedCodecs()

    /**
     * 通话内切换的 selector（t87）。
     *
     * 原生侧在创建/重建 send stream 时调用本方法；返回的 [FallbackVideoEncoderSelector]
     * 只在控制器发出请求时返回非空格式（其余一律 null，不干扰既有行为）。
     */
    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector = FallbackVideoEncoderSelector()

    companion object {
        /** VP9 编解码名（§5.4 冻结）。 */
        const val CODEC_VP9 = "VP9"

        /** VP9 profile 参数名（SDP `profile-id`）。 */
        const val PARAM_PROFILE_ID = "profile-id"

        /** VP9 profile 值（§5.4 冻结为 `0`）。 */
        const val VP9_PROFILE_ID = "0"

        /** 默认实现名未知时的占位（诊断键 `impl=` 不允许空值）。 */
        const val DEFAULT_IMPL_UNKNOWN = "DefaultUnknown"

        private const val TAG = "encoder"
    }
}
