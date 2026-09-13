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
// ============================================================================

/**
 * 自研 VP9 编码器工厂。
 *
 * @param fallback 回退工厂；默认 null（只提供 VP9，强制走自研编码器）。
 */
class Vp9VideoEncoderFactory(
    private val fallback: VideoEncoderFactory? = null,
) : VideoEncoderFactory {

    /**
     * 创建编码器。
     *
     * @param info 目标编解码信息；null 时返回 null（契约要求）。
     * @return 命中 VP9 时返回 [Vp9VideoEncoder]，否则交给 [fallback]。
     */
    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? {
        if (info == null) return null
        if (info.name.equals(CODEC_VP9, ignoreCase = true)) {
            AppLog.i(TAG, "encoder_created", mapOf("impl" to Vp9VideoEncoder.IMPL_NAME, "codec" to CODEC_VP9, "profile" to VP9_PROFILE_ID))
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
        val mine = arrayOf(VideoCodecInfo(CODEC_VP9, mapOf("profile-id" to VP9_PROFILE_ID)))
        val extra = fallback?.supportedCodecs ?: emptyArray<VideoCodecInfo>()
        return if (extra.isEmpty()) mine else mine + extra
    }

    /** 与 [getSupportedCodecs] 相同（§5.4 冻结）。 */
    override fun getImplementations(): Array<VideoCodecInfo> = getSupportedCodecs()

    private companion object {
        const val TAG = "encoder"
        const val CODEC_VP9 = "VP9"
        const val VP9_PROFILE_ID = "0"
    }
}
