// ============================================================================
// encoder/layer_bitrate_allocator.cpp —— 动态码率换算实现（核心学习点）
// ----------------------------------------------------------------------------
// 一次换算的完整推导（每一步都对应 libvpx 的一个字段语义）：
//
//   输入（bps）：M[s][t]，s ∈ [0, num_spatial)，t ∈ [0, num_temporal)
//                M[0][0] 是本路线唯一的非 0 项（契约 §5.6）
//
//   ① 按时序层汇总：T[t] = Σ_s M[s][t]            → 单位 bps
//   ② 总码率：total = Σ_t T[t]（矩阵全 0 时回退 JNI 传入的 total_bps）
//   ③ 未带时序分层数据时（A1 实际路径），按项目策略把 total 切成增量：
//        inc[t] = total × {40%, 30%, 30%}[t]
//      带时序分层数据时直接用 T[t] 作为增量（未来 SVC 升级路径）。
//   ④ 累计（libvpx 语义：这两个数组都是**累计**目标码率，必须单调不减）：
//        ts_target_bitrate[t]        = Σ_{i<=t} inc[i]      ← 时序维累计
//        layer_target_bitrate[s*T+t] = Σ_{i<=t} inc_s[i]    ← 每空间层内累计
//      对本项目 S=1：layer_target_bitrate[t] == ts_target_bitrate[t]。
//   ⑤ 总目标：rc_target_bitrate = total（kbps）
//   ⑥ ss_target_bitrate[0] = rc_target_bitrate（S=1 时空间层目标 == 总目标；
//      注意 libvpx 在 ss_number_layers==1 且非双通时会用 target_bandwidth
//      覆盖 oxcf->ss_target_bitrate[0]，见 vp9_cx_iface.c:727-729，所以真正
//      生效的分层数组是 layer_target_bitrate）。
//
//   kbps 换算与取整：libvpx 的 *_target_bitrate 单位是 kbps，而 WebRTC 全链路
//   是 bps，因此这里统一 /1000（向下取整），并保证每层 ≥ kMinLayerKbps、
//   整体单调不减、末层恰好等于 rc_target_bitrate，避免 vp9_cx_iface.c 的
//   "ts_target_bitrate entries are not increasing" 校验失败。
//
//   ⑦ ts_rate_decimator：契约 §5.6 冻结 {2,1,1}（T=3）。
//      vp9_cx_iface.c:274-277 校验：decimator[T-1] 必须为 1，且相邻满足
//      decimator[t-1] == 2 × decimator[t]。{2,1,1} 满足（仅 t=1 参与校验），
//      语义为“时序层 0 只编 1/2 的帧、层 1/2 全帧率”。
//
// 对照：libwebrtc LibvpxVp9Encoder::SetSvcRates() 做同样的事（对照阅读见
// doc/06 §2.6、doc/11 §5.2）。本项目额外把计算过程打进 CSV/日志，
// 让“GCC 目标 → 分层配置”的中间量可观察（doc/14 §9.4）。
// ============================================================================
#include "encoder/layer_bitrate_allocator.h"

#include <algorithm>

namespace webrtcdemo {

const int32_t
    LayerBitrateAllocator::kTemporalIncrementPermille[kMaxTemporalLayers] = {
        400, 300, 300};
const int32_t LayerBitrateAllocator::kMinLayerKbps = 4;

namespace {

// 兜底总码率（bps）：矩阵与 total_bps 都为 0 时使用 300 kbps。
constexpr int32_t kFallbackTotalBps = 300 * 1000;

int ClampLayerCount(int value) {
  if (value < 1) {
    return 1;
  }
  if (value > kMaxTemporalLayers) {
    return kMaxTemporalLayers;
  }
  return value;
}

int32_t NonNegative(int32_t value) {
  return value > 0 ? value : 0;
}

}  // namespace

LayerBitrate::LayerBitrate()
    : num_spatial(1), num_temporal(1), total_bps(0), framerate_fps(0) {
  for (int s = 0; s < kMaxSpatialLayers; ++s) {
    for (int t = 0; t < kMaxTemporalLayers; ++t) {
      layer_bps[s][t] = 0;
    }
  }
}

VpxLayerRates::VpxLayerRates()
    : configured_spatial(1),
      configured_temporal(1),
      total_bps(0),
      rc_target_bitrate_kbps(0) {
  for (int s = 0; s < kMaxSpatialLayers; ++s) {
    ss_target_bitrate_kbps[s] = 0;
  }
  for (int t = 0; t < kMaxTemporalLayers; ++t) {
    ts_target_bitrate_kbps[t] = 0;
    ts_rate_decimator[t] = 1;
    increment_kbps[t] = 0;
  }
  for (int i = 0; i < kMaxSpatialLayers * kMaxTemporalLayers; ++i) {
    layer_target_bitrate_kbps[i] = 0;
  }
}

VpxLayerRates LayerBitrateAllocator::Compute(const LayerBitrate& in,
                                             int configured_spatial,
                                             int configured_temporal) {
  VpxLayerRates out;
  // ---- ① 维度钳制 -----------------------------------------------------------
  const int matrix_spatial = ClampLayerCount(in.num_spatial);
  const int matrix_temporal = ClampLayerCount(in.num_temporal);
  const int spatial = ClampLayerCount(configured_spatial);
  const int temporal = ClampLayerCount(configured_temporal);
  out.configured_spatial = spatial;
  out.configured_temporal = temporal;

  // ---- ② 按时序层汇总 + 判定是否带真实时序分层数据 --------------------------
  int64_t temporal_sum[kMaxTemporalLayers] = {0};
  int64_t total_from_matrix = 0;
  bool has_temporal_layers_data = false;
  for (int t = 0; t < matrix_temporal; ++t) {
    for (int s = 0; s < matrix_spatial; ++s) {
      temporal_sum[t] += NonNegative(in.layer_bps[s][t]);
    }
    total_from_matrix += temporal_sum[t];
    // t>0 且该列有数据 → 说明上游真的做了时序分层分配（未来 SVC/更细策略）。
    if (t > 0 && temporal_sum[t] > 0) {
      has_temporal_layers_data = true;
    }
  }

  int64_t total = total_from_matrix;
  if (total <= 0) {
    total = NonNegative(in.total_bps);
  }
  if (total <= 0) {
    total = kFallbackTotalBps;
  }
  out.total_bps = static_cast<int32_t>(total);

  // ---- ③ 计算每个时序层的增量（bps）----------------------------------------
  int64_t increment_bps[kMaxTemporalLayers] = {0};
  if (has_temporal_layers_data) {
    // 真实分层数据：直接采用上游分配（只取前 configured_temporal 层）。
    for (int t = 0; t < temporal; ++t) {
      increment_bps[t] = (t < matrix_temporal) ? temporal_sum[t] : 0;
    }
  } else {
    // A1 实际路径：上游只给总量，按项目策略切分（契约 §5.6 冻结的 40/70/100）。
    for (int t = 0; t < temporal; ++t) {
      increment_bps[t] =
          total * kTemporalIncrementPermille[t] / 1000;  // 千分比 → bps
    }
  }
  // 把取整余量补到最后一层，保证 Σinc == total（总量守恒）。
  int64_t increment_total = 0;
  for (int t = 0; t < temporal; ++t) {
    increment_total += increment_bps[t];
  }
  if (increment_total < total) {
    increment_bps[temporal - 1] += total - increment_total;
  } else if (increment_total > total && increment_total > 0) {
    // 上游分层之和大于总量（理论不该发生）：按比例回缩最后一层，避免倒挂。
    int64_t excess = increment_total - total;
    increment_bps[temporal - 1] =
        std::max<int64_t>(0, increment_bps[temporal - 1] - excess);
  }

  // ---- ④ 总目标码率（kbps）--------------------------------------------------
  int32_t rc_kbps = static_cast<int32_t>(total / 1000);
  if (rc_kbps < kMinLayerKbps) {
    rc_kbps = kMinLayerKbps;
  }
  out.rc_target_bitrate_kbps = rc_kbps;

  // ---- ⑤ 逐层累计 → ts_target_bitrate / layer_target_bitrate ----------------
  int64_t cumulative = 0;
  for (int t = 0; t < temporal; ++t) {
    cumulative += increment_bps[t];
    int32_t layer_kbps = static_cast<int32_t>(cumulative / 1000);
    if (layer_kbps < kMinLayerKbps) {
      layer_kbps = kMinLayerKbps;
    }
    // 单调不减（libvpx 校验 ts_target_bitrate 必须递增）。
    if (t > 0 && layer_kbps < out.ts_target_bitrate_kbps[t - 1]) {
      layer_kbps = out.ts_target_bitrate_kbps[t - 1];
    }
    out.ts_target_bitrate_kbps[t] = layer_kbps;
    out.increment_kbps[t] = static_cast<int32_t>(increment_bps[t] / 1000);
  }
  // 末层必须等于总目标（契约 §5.6：累计 100% × total）。
  if (out.ts_target_bitrate_kbps[temporal - 1] != rc_kbps) {
    out.ts_target_bitrate_kbps[temporal - 1] = rc_kbps;
    // 修正后再次保证单调。
    for (int t = temporal - 2; t >= 0; --t) {
      if (out.ts_target_bitrate_kbps[t] > out.ts_target_bitrate_kbps[t + 1]) {
        out.ts_target_bitrate_kbps[t] = out.ts_target_bitrate_kbps[t + 1];
      }
    }
  }
  // S == 1（本项目唯一合法配置）：layer_target_bitrate[t] 与 ts 完全一致。
  for (int s = 0; s < spatial; ++s) {
    for (int t = 0; t < temporal; ++t) {
      out.layer_target_bitrate_kbps[s * temporal + t] =
          out.ts_target_bitrate_kbps[t];
    }
    // ss_target_bitrate[s] 为空间层累计目标；S=1 时等于总目标。
    out.ss_target_bitrate_kbps[s] = out.ts_target_bitrate_kbps[temporal - 1];
  }

  // ---- ⑥ decimator（契约 §5.6 冻结 {2,1,1}）--------------------------------
  out.ts_rate_decimator[0] = (temporal >= 3) ? 2u : 1u;
  for (int t = 1; t < temporal; ++t) {
    out.ts_rate_decimator[t] = 1u;
  }

  return out;
}

}  // namespace webrtcdemo
