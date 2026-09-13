// ============================================================================
// encoder/layer_bitrate_allocator.h —— 分层码率换算（**核心学习点**）
// ----------------------------------------------------------------------------
// 对应 WebRTC 技术点（doc/06 §1 整条链路）：
//   GCC 估带宽 → BitrateAllocator → VideoEncoder.SetRates(VideoBitrateAllocation)
//   → 本文件把 [spatial][temporal] 码率矩阵换算成 libvpx 的
//      ss_target_bitrate[] / ts_target_bitrate[] / layer_target_bitrate[] /
//      rc_target_bitrate → vpx_codec_enc_config_set() → vpx_codec_encode()。
// 对照阅读：libwebrtc modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc
//           的 LibvpxVp9Encoder::SetSvcRates()（本文件是它的“手写版”）。
//
// 现实边界（契约 §5.6，源码级硬约束）：官方 Java 编码器路径使用
//   ScalableVideoControllerNoLayering，VP9 的 num_spatial_layers 被硬编码为 1，
//   因此本项目**空间层固定 1、时序层 3（L1T3）**；Java 侧仍会传来完整
//   3×3 矩阵，但实际只有 [0][0] 非 0（其余层由 SDK 填 0）。
//   本文件按项目策略把总码率切到 3 个时序层：
//     累计目标 = {40%, 70%, 100%} × total，即三层增量份额 = {40%, 30%, 30%}。
// ============================================================================
#pragma once

#include <cstdint>

namespace webrtcdemo {

// 单个编码器实例允许的最大层数（与 libvpx 的 VPX_SS_MAX_LAYERS /
// VPX_TS_MAX_LAYERS 在本项目用到的范围一致）。
constexpr int kMaxSpatialLayers = 3;
constexpr int kMaxTemporalLayers = 3;

// 输入：JNI 侧展平后的码率矩阵（契约 §6.3 nativeSetRates 的 layerBitratesBps）。
struct LayerBitrate {
  // 索引 [spatial][temporal]，单位 bps；来自
  // org.webrtc.VideoEncoder.BitrateAllocation.bitratesBbs。
  int32_t layer_bps[kMaxSpatialLayers][kMaxTemporalLayers];
  int num_spatial;    // 矩阵空间维（SDK 通常给 3）
  int num_temporal;   // 矩阵时序维（SDK 通常给 3）
  int32_t total_bps;  // BitrateAllocation.sum（JNI 参数 totalBitrateBps）
  int framerate_fps;  // SetRates 的 framerate

  LayerBitrate();
};

// 输出：写回 vpx_codec_enc_cfg_t 的分层码率（libvpx 单位一律 kbps）。
struct VpxLayerRates {
  int configured_spatial;  // 实际配置的空间层数（本项目恒为 1，契约 §5.6）
  int configured_temporal;  // 实际配置的时序层数（默认 3 = L1T3）
  int32_t total_bps;        // 归一化后的总码率（bps）
  int32_t rc_target_bitrate_kbps;  // 总目标码率
  int32_t ss_target_bitrate_kbps[kMaxSpatialLayers];
  int32_t ts_target_bitrate_kbps[kMaxTemporalLayers];  // **累计**值
  int32_t layer_target_bitrate_kbps[kMaxSpatialLayers * kMaxTemporalLayers];
  uint32_t ts_rate_decimator[kMaxTemporalLayers];  // 契约 §5.6：{2,1,1}
  int32_t increment_kbps[kMaxTemporalLayers];  // 每层增量（仅日志/排查）

  VpxLayerRates();
};

// 分层码率换算器（纯函数，无状态、线程安全）。
class LayerBitrateAllocator {
 public:
  // 把 [spatial][temporal] 矩阵换算为 vpx 分层码率配置。
  // 参数：
  //   in                   JNI 传入的码率矩阵
  //   configured_spatial   编码器实际配置的空间层数（本项目必须为 1）
  //   configured_temporal  编码器实际配置的时序层数（本项目默认 3）
  // 返回值：可直接写入 vpx_codec_enc_cfg_t 的 kbps 值（已保证单调、非零）。
  // 调用时机：每次 nativeSetRates（即 GCC 每次更新目标码率）时调用。
  static VpxLayerRates Compute(const LayerBitrate& in, int configured_spatial,
                               int configured_temporal);

  // 三层时序的增量份额（千分比）：400+300+300 = 1000。
  // 为什么用“增量”而不是“累计”：libvpx 的 ts_target_bitrate/layer_target_bitrate
  // 存的是**累计**目标，须由增量逐层累加（40% → 70% → 100%）。
  static const int32_t kTemporalIncrementPermille[kMaxTemporalLayers];

  // 每层最小目标码率（kbps）：避免拥塞极低时 libvpx 收到 0 或倒挂值而拒绝配置。
  static const int32_t kMinLayerKbps;

 private:
  LayerBitrateAllocator() = delete;
};

}  // namespace webrtcdemo
