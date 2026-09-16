package com.example.webrtcdemo.encoder

import com.example.webrtcdemo.log.AppLog
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoEncoderFactory

// ============================================================================
// 通话内编码器切换 selector（t87 验收第 2 条）
// ----------------------------------------------------------------------------
// libwebrtc 语义（本版 `libwebrtc/include/api/video_codecs/video_encoder_factory.h` 原文）：
//   * `OnCurrentEncoder(format)`              —— 告知当前正在用的编码器；
//   * `OnAvailableBitrate(rate)` 返回非空     —— “应当执行一次编码器切换”；
//   * `OnResolutionChange(w,h)` 返回非空      —— 同上；
//   * `OnEncoderBroken()` 返回非空            —— 同上。
// 我们**只在控制器已发出请求时**返回非空（返回的是当前 VP9 格式本身，不换编解码器 ⇒
// 不影响 SDP/协商），其余时刻一律返回 null（零行为变化）。
//
// 返回非空格式后，原生侧会重建 send stream 并按该格式**再次调用工厂** `createEncoder`，
// 控制器彼时已把目标实现置为 DEFAULT ⇒ 工厂返回默认（硬件优先）实现 = 切换完成。
// 若原生侧在 5 s 内没有重建（版本差异/多流情形），控制器自动退化为 `pendingFallbackForNextCall`
// 且**本次通话继续用自研编码器**，不中断通话、不丢房间。
// ============================================================================

/**
 * 通话内切换 selector：把 [EncoderFallbackController] 的一次性请求翻译成 libwebrtc 的
 * “返回非空格式 = 请求切换”协议。
 *
 * 线程：由 libwebrtc 的编码/流控制线程调用；本类只读/写 `@Volatile` 字段，不做 I/O。
 */
class FallbackVideoEncoderSelector : VideoEncoderFactory.VideoEncoderSelector {

    /** 当前编码格式（由 `onCurrentEncoder` 提供；用于原样回传，避免改变编解码）。 */
    @Volatile
    private var currentFormat: VideoCodecInfo? = null

    /**
     * 当前正在使用的编码器。
     *
     * @param info 当前格式（libwebrtc 传入，非空）。
     */
    override fun onCurrentEncoder(info: VideoCodecInfo) {
        currentFormat = info
        EncoderFallbackController.onCurrentEncoder(info.name)
    }

    /**
     * 码率更新（本版本被周期性调用，是切换请求的主要投递点）。
     *
     * @param bitrateBps 可用码率。
     * @return 需要切换时返回目标格式；否则 null。
     */
    override fun onAvailableBitrate(bitrateBps: Int): VideoCodecInfo? =
        switchRequestOrNull(trigger = "bitrate", value = bitrateBps)

    /**
     * 分辨率变化。
     *
     * @param width 新宽度。
     * @param height 新高度。
     * @return 需要切换时返回目标格式；否则 null。
     */
    override fun onResolutionChange(width: Int, height: Int): VideoCodecInfo? =
        switchRequestOrNull(trigger = "resolution", value = width * height)

    /**
     * 当前编码器报告损坏 —— 若控制器正好有降级请求，这里顺带兑现。
     *
     * @return 需要切换时返回目标格式；否则 null。
     */
    override fun onEncoderBroken(): VideoCodecInfo? =
        switchRequestOrNull(trigger = "encoder_broken", value = -1)

    /**
     * 取一次切换请求并转成格式。
     *
     * @param trigger 触发点（日志用）。
     * @param value 触发值（码率/像素数；-1 表示无）。
     * @return 目标格式；无请求或条件不满足时 null。
     */
    private fun switchRequestOrNull(trigger: String, value: Int): VideoCodecInfo? {
        if (!EncoderFallbackController.takeSwitchRequest()) return null
        val format = currentFormat ?: VideoCodecInfo(
            Vp9VideoEncoderFactory.CODEC_VP9,
            mapOf(Vp9VideoEncoderFactory.PARAM_PROFILE_ID to Vp9VideoEncoderFactory.VP9_PROFILE_ID),
        )
        if (!format.name.equals(Vp9VideoEncoderFactory.CODEC_VP9, ignoreCase = true)) {
            // 防御：本工厂只提供 VP9；万一拿到别的编解码格式，宁可不切（避免换编解码器导致协商失败）
            AppLog.w(
                TAG,
                "encoder_fallback_switch_skipped",
                mapOf("reason" to "codec_not_vp9", "codec" to format.name),
            )
            return null
        }
        AppLog.i(
            TAG,
            "encoder_fallback_switch_signal",
            mapOf(
                "trigger" to trigger,
                "value" to value.toString(),
                "codec" to format.name,
                "mech" to "in_call_switch",
            ),
        )
        return format
    }

    private companion object {
        const val TAG = "encoder"
    }
}
