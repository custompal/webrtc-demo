package com.example.webrtcdemo.encoder

import org.webrtc.VideoEncoder

// ============================================================================
// Vp9BitrateLimits —— 自研 VP9 的分辨率—码率上下限（t89 修复「码率分配崩塌」）
// ----------------------------------------------------------------------------
// 为什么需要这个文件（真机 A/B 铁证，详见 reports/49-bitrate-allocation-collapse.md）：
//   同一台 Mi 10 Pro、同一条 RELAY 链路、相隔几分钟的两段会话：
//     A 默认编码（use_default_encoder=true，impl=libvpx）：up_bps 1.36–1.97 Mbps
//     B 自研编码（impl=SelfVp9Libvpx）：up_bps 41–56 kbps，且
//       `setrates total_bps=235947 → 125722 → 40536 → 37156`（1 秒内掉到 37 kbps）
//   而 native 侧实测 requested→applied ≈ 99.6–100%（t85）⇒ 这 40 kbps 是 libwebrtc
//   自己的分配值；两段都是 VP9 软编（`codec_preferences_set codec=VP9 count=1`），
//   所以差异只能来自**编码器自身向 libwebrtc 申报的信息**。
//
// 根因：本类旧实现把 `minBitrateBps` 全填 0
//   (320*180, 0, 0, 500k) / (640*360, 0, 0, 1M) / (1280*720, 0, 0, 2M)
//   而 libwebrtc 会用 `EncoderInfo::GetEncoderBitrateLimitsForResolution()`
//   （消费点：video/video_stream_encoder.cc:469、:540、video/adaptation/bitrate_constraint.cc:80）
//   把匹配档位的 min/max 写进 VideoEncoderConfig ⇒ **min=0 等于把视频流的地板抽掉**：
//   一旦估计器下探，分配可以掉到几十 kbps 且没有地板可回弹（CBR 又把 QP 顶到 193–224
//   ⇒ 画面糊、估算继续下探 = 死亡螺旋）。
//
// 修复：改为 **libwebrtc 官方 VP9 单播参考表**（逐值照抄，含 30 kbps 地板与启动码率）：
//   rtc_base/experiments/encoder_info_settings.cc 的
//   `EncoderInfoSettings::GetDefaultSinglecastBitrateLimits(kVideoCodecVP9)`：
//     {320*180, 0,      30000, 150000}
//     {480*270, 120000, 30000, 300000}
//     {640*360, 190000, 30000, 420000}
//     {960*540, 350000, 30000, 1000000}
//     {1280*720,480000, 30000, 1500000}
//     {1920*1080,1000000,30000, 3700000}
//   ⇒ 同时修好 H3（地板 30 kbps）与 H4（启动码率 120/190/350/480/1000 kbps，
//     让估计器一开始就有可探测流量）。
//
// 与 `Vp9VideoEncoder.getScalingSettings() = OFF` 的关系：我们把质量缩放关闭，
// 分辨率自适应交给 SDK 的 VideoAdapter；官方表里 30 kbps 的 min 正是「单播下
// 允许的最低目标码率」，与 QP-based quality scaler 关闭并不冲突。
// ============================================================================
object Vp9BitrateLimits {

    /**
     * 官方 VP9 单播分辨率—码率上下限（frameSizePixels, minStartBitrateBps,
     * minBitrateBps, maxBitrateBps）。
     *
     * 说明（照抄时的口径）：
     * - `frameSizePixels` 是**像素总数**（本版 `ResolutionBitrateLimits` 的 4 参构造）；
     * - `minStartBitrateBps` 影响 InitEncode 时的 `settings.startBitrate`（H4）；
     * - `minBitrateBps = 30_000` 是**视频流的地板**（H3，绝不能填 0）；
     * - `maxBitrateBps` 必须 ≥ minBitrateBps（单播下 BWE 端到端可知，官方值已足够）。
     */
    private val kDefaultVp9SinglecastLimits: List<VideoEncoder.ResolutionBitrateLimits> =
        listOf(
            VideoEncoder.ResolutionBitrateLimits(320 * 180, 0, 30_000, 150_000),
            VideoEncoder.ResolutionBitrateLimits(480 * 270, 120_000, 30_000, 300_000),
            VideoEncoder.ResolutionBitrateLimits(640 * 360, 190_000, 30_000, 420_000),
            VideoEncoder.ResolutionBitrateLimits(960 * 540, 350_000, 30_000, 1_000_000),
            VideoEncoder.ResolutionBitrateLimits(1280 * 720, 480_000, 30_000, 1_500_000),
            VideoEncoder.ResolutionBitrateLimits(1920 * 1080, 1_000_000, 30_000, 3_700_000),
        )

    /** 供 `VideoEncoder.getResolutionBitrateLimits()` 使用（每次返回新数组，避免调用方改写内部表）。 */
    fun limits(): Array<VideoEncoder.ResolutionBitrateLimits> =
        kDefaultVp9SinglecastLimits.toTypedArray()

    /** 表中条数（单测用）。 */
    val count: Int get() = kDefaultVp9SinglecastLimits.size

    /**
     * 与 libwebrtc `GetEncoderBitrateLimitsForResolution()` 等价的**档位选择**（单测用）：
     * 取「frameSizePixels ≤ 目标像素数」中最大的那一档；都比目标大时取最小档。
     *
     * 注意：App 运行时**不调用**本函数（选择逻辑在 libwebrtc native 侧），这里提供
     * 同口径实现只为让纯 JVM 单测能断言「给定分辨率会命中哪一档、地板是多少」。
     */
    fun matchForPixels(frameSizePixels: Int): VideoEncoder.ResolutionBitrateLimits {
        var best = kDefaultVp9SinglecastLimits.first()
        for (limit in kDefaultVp9SinglecastLimits) {
            if (limit.frameSizePixels <= frameSizePixels) {
                best = limit
            } else {
                break
            }
        }
        return best
    }
}
