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
//   因此本项目**空间层固定 1、时序层 3（L1T3）**；Java 侧仍会传来完整矩阵，
//   但实际只有 [0][0] 非 0（其余层由 SDK 填 0）。
//
// 【t48 真机修正】矩阵维度**不是** 3×3：SDK 按 libwebrtc 的上限分配
//   （api/video_codecs/video_codec.h：kMaxSpatialLayers = 5、
//    kMaxTemporalStreams = 4），真机 nativeSetRates 的日志实测 s=5 t=4。
//   早期代码按 3×3 校验，导致**每一次** SetRates 都被 reason=bad_dim 拒绝
//   （见 reports/18-encoder-stall.md §2）；现在由 FoldSdkLayerMatrix() 把
//   SDK 维度的矩阵折叠到本项目的层数上限内。
//   本文件按项目策略把总码率切到 3 个时序层：
//     累计目标 = {40%, 70%, 100%} × total，即三层增量份额 = {40%, 30%, 30%}。
// ============================================================================
#pragma once

#if defined(LAYER_BITRATE_FREESTANDING)
// 离线宿主自测专用（容器内没有宿主 libc++ 头，见
// layer_bitrate_fold_host_test.cpp）：给出最小整型定义，App 构建不受影响。
using int32_t = int;
using uint32_t = unsigned int;
using int64_t = long long;
using uint64_t = unsigned long long;
#else
#include <cstdint>
#endif

namespace webrtcdemo {

// 单个编码器实例允许的最大层数（与 libvpx 的 VPX_SS_MAX_LAYERS /
// VPX_TS_MAX_LAYERS 在本项目用到的范围一致）。
constexpr int kMaxSpatialLayers = 3;
constexpr int kMaxTemporalLayers = 3;

// SDK（org.webrtc.VideoEncoder.BitrateAllocation）矩阵的维度上限，与 libwebrtc
// api/video_codecs/video_codec.h 的 kMaxSpatialLayers / kMaxTemporalStreams 一致。
// 为什么需要它：nativeSetRates 的入参维度由 SDK 决定（真机实测 5×4），而本项目
// 内部数组只有 kMaxSpatialLayers × kMaxTemporalLayers；两者必须先折叠再写入，
// 否则会出现**逐层循环越界写**（对 3×3 数组按 5×4 写 = 越界 11 个 int）。
constexpr int kSdkMaxSpatialLayers = 5;
constexpr int kSdkMaxTemporalStreams = 4;

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

// 把 SDK 展平的码率矩阵折叠为本项目内部的 [LayerBitrate]。
//
// 参数：
//   flat          SDK 矩阵展平值，索引 = s * sdk_temporal + t（长度必须 = s*t）
//   sdk_spatial   SDK 矩阵空间维（合法范围 1..kSdkMaxSpatialLayers，真机为 5）
//   sdk_temporal  SDK 矩阵时序维（合法范围 1..kSdkMaxTemporalStreams，真机为 4）
//   total_bps     BitrateAllocation.sum（bps），原样透传
//   framerate_fps SetRates 的帧率，原样透传
//   out           输出（原对象内容被覆盖）
//
// 返回值：true = 成功；false = 入参非法（空指针 / 维度越界 / 长度不匹配）。
// 调用时机：nativeSetRates（GCC 每次更新目标码率）进入时调用一次。
//
// 折叠规则（为什么这样折）：
//   * 空间/时序维**只保留** kMaxSpatialLayers / kMaxTemporalLayers 以内的部分，
//     保证不会越界写到 LayerBitrate 的定长数组；
//   * 被钳掉的空间层按**同一时序层求和**累加进来（s, s+3, …），避免丢总量；
//   * total_bps 不受折叠影响 —— 本项目的分层拆分策略始终以 total 为准
//     （LayerBitrateAllocator::Compute 的 40/30/30）。
bool FoldSdkLayerMatrix(const int32_t* flat, int sdk_spatial, int sdk_temporal,
                        int32_t total_bps, int framerate_fps,
                        LayerBitrate* out);

}  // namespace webrtcdemo
