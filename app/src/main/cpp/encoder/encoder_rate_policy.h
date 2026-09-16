// ============================================================================
// encoder/encoder_rate_policy.h —— 码率口径与 setRates 去抖的**纯函数策略**（t85）
// ----------------------------------------------------------------------------
// 存在原因（用户日志证据，见 reports/47-vp9-encode-perf.md §2）：
//   1) 我们把 libwebrtc 传来的 requested 总码率**截断**成 kbps（`total/1000`），
//      requested=6732 bps ⇒ applied=6 kbps（-11%），小码率档位下相对欠额明显；
//   2) requested 极小（n3 p50 仅 35.6 kbps）时 CBR 把 QP 顶到 193–224，画面糊 +
//      估算继续下探，形成死亡螺旋 ⇒ 需要一个**总码率下限**（明确的降级档位）；
//   3) `setRates` 被高频小幅波动反复触发（n2 一次通话 2072 次），每次都做
//      `vpx_codec_enc_config_set` 重配 ⇒ 抖动 ⇒ 需要**迟滞/去抖**。
//
// 设计：全部写成纯内联函数（不依赖 libvpx/Android/系统），既能在 App 里用，
//   也能用 NDK clang++ 以 freestanding 方式在容器内单测（见
//   encoder_rate_policy_host_test.cpp，含 old-red 对照）。
// ============================================================================
#pragma once

#if defined(ENCODER_RATE_POLICY_FREESTANDING)
using int32_t = int;
using int64_t = long long;
#else
#include <cstdint>
#endif

namespace webrtcdemo {

// kbps 与 bps 的换算（libvpx 的 rc_target_bitrate 单位是 kbps）。
constexpr int64_t kBpsPerKbps = 1000;

// 总码率下限（kbps）：requested 低于此值时按此档位供给，并打日志说明触发了下限。
// 为什么是 30：VP9 CBR 在 480×640 下低于 ~30 kbps 时 QP 会长期贴 200+（真机实测
// qp 193–224），画面完全不可用；30 kbps 是「仍有意义的最小可看档位」。
constexpr int32_t kDefaultTotalFloorKbps = 30;

// setRates 去抖：变化幅度 < 10%（千分比 100）且距上次重配 < 2 s ⇒ 跳过本次重配。
constexpr int32_t kRateDebouncePermille = 100;
constexpr int64_t kRateDebounceWindowMs = 2000;

// requested(bps) → applied(kbps)：**向上取整**，保证 applied_bps >= requested_bps
// （旧实现 `total / 1000` 是向下取整 ⇒ 欠额；例如 6732 → 6 kbps = -11%）。
// requested <= 0 时返回 0（调用方会再套下限）。
inline int32_t CeilKbpsFromBps(int64_t requested_bps) {
  if (requested_bps <= 0) {
    return 0;
  }
  return static_cast<int32_t>((requested_bps + kBpsPerKbps - 1) / kBpsPerKbps);
}

// 施加总码率下限：返回最终 applied(kbps)；*clamped=true 表示触发了下限。
inline int32_t ApplyTotalFloorKbps(int32_t applied_kbps, int32_t floor_kbps,
                                  bool* clamped) {
  if (clamped != nullptr) {
    *clamped = false;
  }
  if (floor_kbps <= 0) {
    return applied_kbps;
  }
  if (applied_kbps < floor_kbps) {
    if (clamped != nullptr) {
      *clamped = true;
    }
    return floor_kbps;
  }
  return applied_kbps;
}

// setRates 去抖判定：true = 需要真正重配（`vpx_codec_enc_config_set`）。
//
// 参数：
//   last_applied_bps 上次真正写进 libvpx 的总码率（bps；0 表示从未重配）
//   new_requested_bps 本次 libwebrtc 传来的总码率（bps）
//   last_ms/now_ms    上次重配时刻与当前时刻（同一时钟，ms）
// 判据：从未重配 ⇒ 必须应用；变化幅度 >= 10% ⇒ 必须应用；
//       否则若距上次重配 < 2 s ⇒ 跳过（去抖），>= 2 s ⇒ 应用（避免长期不跟随）。
inline bool ShouldApplyRates(int64_t last_applied_bps, int64_t new_requested_bps,
                             int64_t last_ms, int64_t now_ms) {
  if (last_applied_bps <= 0 || last_ms <= 0) {
    return true;
  }
  const int64_t delta = new_requested_bps - last_applied_bps;
  const int64_t magnitude = delta < 0 ? -delta : delta;
  // |Δ| * 1000 >= last * 100  ⇔ |Δ| >= 10% * last（无浮点，避免 -ffast-math 影响）
  if (magnitude * 1000 >= last_applied_bps * kRateDebouncePermille) {
    return true;
  }
  return (now_ms - last_ms) >= kRateDebounceWindowMs;
}

}  // namespace webrtcdemo
