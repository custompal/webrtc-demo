// ============================================================================
// encoder/libvpx_cpu_guard.h —— 交付 libvpx 的「编译期 SIMD 假定」运行时自检（t55）
// ----------------------------------------------------------------------------
// 背景（真机首帧 vpx_codec_encode 崩溃的根因）：
//   交付件 `third_party/libvpx/lib/libvpx.a` 是用
//     ./configure --target=arm64-android-gcc … **--disable-runtime-cpu-detect** …
//   构建的（scripts/t5-libwebrtc-libvpx-build.sh:284）。该开关让 libvpx 的
//   rtcd 生成器把「运行时选择」退化为**编译期 #define 直连**，而 NDK clang 的
//   特性探测又打开了 SVE/SVE2/dotprod/i8mm：
//     vp9_rtcd.h      : vp9_block_error      → vp9_block_error_sve      （SVE）
//                       vp9_block_error_fp   → vp9_block_error_fp_sve
//                       vpx_sum_squares_2d_i16 → …_sve
//     vpx_dsp_rtcd.h  : 78 个函数 → *_neon_dotprod / *_neon_i8mm
//   ⇒ 在**没有**这些扩展的真机上，首帧（关键帧）的 RD 就会执行 SVE 指令 →
//     SIGILL → 进程立即死亡（日志停在 encode_vpx_begin，恰与真机现象一致）。
//
// 本头文件做什么：把「CPU 是否满足交付件的编译期假定」变成一个**纯函数**，
//   以便 (a) 在 App 里初始化前自检、缺扩展时优雅回退而不是闪退；
//       (b) 在容器内用 freestanding 宿主单测穷举各组合（见
//           libvpx_cpu_guard_host_test.cpp）。
//
// hwcap 位（AArch64，内核 uapi asm/hwcap.h 的标准值）：
//   AT_HWCAP : HWCAP_ASIMDDP = 1<<20（ARMv8.2 dotprod）、HWCAP_SVE = 1<<22
//   AT_HWCAP2: HWCAP2_SVE2    = 1<<1 、HWCAP2_I8MM = 1<<13（ARMv8.4/8.6 i8mm）
// ============================================================================
#pragma once

namespace webrtcdemo {

// AArch64 的 hwcap 位（常量写在代码里：NDK 的 sysroot 不带 asm/hwcap.h）。
constexpr unsigned long kHwcapAsimdDotprod = 1UL << 20;  // AT_HWCAP
constexpr unsigned long kHwcapSve = 1UL << 22;           // AT_HWCAP
constexpr unsigned long kHwcap2Sve2 = 1UL << 1;          // AT_HWCAP2
constexpr unsigned long kHwcap2I8mm = 1UL << 13;         // AT_HWCAP2

// 自检结果：kOk = 该 CPU 能安全执行交付 libvpx 的代码；
// 其它值 = 缺少对应扩展（用哪台设备/哪颗 SoC 一测就知道，日志会打出来）。
enum class LibvpxCpuStatus {
  kOk = 0,
  kMissingSve = 1,
  kMissingSve2 = 2,
  kMissingDotprod = 3,
  kMissingI8mm = 4,
};

// 纯函数：给定 AT_HWCAP / AT_HWCAP2，判断是否满足交付 libvpx 的编译期假定。
// 参数：hwcap = getauxval(AT_HWCAP)，hwcap2 = getauxval(AT_HWCAP2)。
// 返回值：kOk 才能初始化自研编码器；否则必须拒绝（回退默认编码器）。
// 调用时机：Vp9Encoder::Init() 开头（App 运行期）与离线单测。
inline LibvpxCpuStatus CheckLibvpxCpu(unsigned long hwcap,
                                      unsigned long hwcap2) {
  // 顺序即“最可能缺失优先”，便于日志一眼看出缺哪一项。
  if ((hwcap & kHwcapSve) == 0) {
    return LibvpxCpuStatus::kMissingSve;
  }
  if ((hwcap2 & kHwcap2Sve2) == 0) {
    return LibvpxCpuStatus::kMissingSve2;
  }
  if ((hwcap & kHwcapAsimdDotprod) == 0) {
    return LibvpxCpuStatus::kMissingDotprod;
  }
  if ((hwcap2 & kHwcap2I8mm) == 0) {
    return LibvpxCpuStatus::kMissingI8mm;
  }
  return LibvpxCpuStatus::kOk;
}

// 供日志使用的事件短名（保持稳定，便于真机判据 grep）。
inline const char* LibvpxCpuStatusName(LibvpxCpuStatus status) {
  switch (status) {
    case LibvpxCpuStatus::kOk:
      return "ok";
    case LibvpxCpuStatus::kMissingSve:
      return "missing_sve";
    case LibvpxCpuStatus::kMissingSve2:
      return "missing_sve2";
    case LibvpxCpuStatus::kMissingDotprod:
      return "missing_dotprod";
    case LibvpxCpuStatus::kMissingI8mm:
      return "missing_i8mm";
  }
  return "unknown";
}

}  // namespace webrtcdemo
