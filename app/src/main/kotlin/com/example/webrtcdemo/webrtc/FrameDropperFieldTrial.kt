package com.example.webrtcdemo.webrtc

// ============================================================================
// FrameDropperFieldTrial —— 关闭 libwebrtc FrameDropper 的 field trial（t92）
// ----------------------------------------------------------------------------
// 为什么需要（真机证据链，详见 reports/51-frame-dropper-and-trusted-rc.md）：
//   我们的编码器是 **Java 实现**（`Vp9VideoEncoder`），libwebrtc 的 native 包装器
//   `sdk/android/src/jni/video_encoder_wrapper.cc` 构造 `EncoderInfo` 时**没有**（也无法）
//   设置 `has_trusted_rate_controller` ⇒ 取默认值 **false**（`api/video_codecs/video_encoder.h`）。
//   而 `video/video_stream_encoder.cc:2016-2019`：
//       frame_dropping_enabled = !force_disable_frame_dropper_ && !encoder_info_.has_trusted_rate_controller;
//   ⇒ **Java 编码器永远开着 FrameDropper**（按目标帧率丢弃输入帧）。
//   默认编码路径用的是 C++ 原生 libvpx（`libvpx_vp9_encoder.cc` 里 `trusted_rate_controller_`
//   可为 true）⇒ 拥塞时它保帧率、只降质量；我们则被丢帧 ⇒ 「同样 ~20 fps 目标下默认更顺」。
//
// 本版本可用的关闭入口（已核实）：
//   `video/video_stream_encoder.cc:108`        constexpr char kFrameDropperFieldTrial[] = "WebRTC-FrameDropper";
//   `video/video_stream_encoder.cc:1459-1463`  force_disable_frame_dropper_ =
//         env_.field_trials().IsDisabled(kFrameDropperFieldTrial) || (screenshare 多层的特例);
//   Java 侧：`PeerConnectionFactory.Builder.setFieldTrials(String)`（`PeerConnectionFactory.java:191-194`，
//         非弃用入口；`InitializationOptions.Builder.setFieldTrials` 为已弃用的等价入口 `:100-104`）。
//
// field-trial 语法：`<name>/<value>/`（多个试验用 `/` 分隔），因此这里给出完整试验串。
// ============================================================================
object FrameDropperFieldTrial {

    /** libwebrtc 侧试验名（必须逐字一致，错一个字符就静默失效）。 */
    const val NAME = "WebRTC-FrameDropper"

    /** 取值 `Disabled` ⇒ `force_disable_frame_dropper_ = true` ⇒ 完全关闭丢帧。 */
    const val DISABLED_VALUE = "Disabled"

    /**
     * 传给 `PeerConnectionFactory.Builder.setFieldTrials(...)` 的完整试验串。
     *
     * 语义：关闭 FrameDropper ⇒ 拥塞时不再丢输入帧；配合已启用的
     * `Vp9VideoEncoder.getScalingSettings() = ScalingSettings(24, 37)`（t91）
     * 与 t89 的码率地板，降级路径变成「先降分辨率/质量」而不是「丢帧」。
     */
    const val DISABLED_TRIAL = "$NAME/$DISABLED_VALUE/"
}
