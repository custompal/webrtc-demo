// ============================================================================
// encoder/encoder_rate_policy_host_test.cpp —— 码率口径/去抖纯函数的离线单测（t85）
// ----------------------------------------------------------------------------
// 覆盖 acceptance「修 2/修 3」：
//   * applied_total_bps 必须 **>= requested**（向上取整；旧实现 total/1000 会欠额）；
//   * 总码率下限（避免 requested 6.7 kbps 这种档位）；
//   * setRates 去抖（变化 <10% 且 <2 s ⇒ 跳过重配）；
//   * **old-red 对照**：把修复前的行为（向下取整 / 无下限 / 无去抖）作为参照实现在
//     本测试里，断言「旧行为会欠额/会重配」⇒ 证明这些单测确实能抓到回归。
//
// 编译与运行（容器内真实执行；命令同 reports/47-vp9-encode-perf.md §5）：
//   NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10910125/toolchains/llvm/prebuilt/linux-x86_64/bin
//   cd code/webrtc-demo
//   $NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
//       -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra \
//       -DENCODER_RATE_POLICY_HOST_TEST -DENCODER_RATE_POLICY_FREESTANDING \
//       -I app/src/main/cpp app/src/main/cpp/encoder/encoder_rate_policy_host_test.cpp \
//       -o <工作区>/t85-work/rate_policy_test -Wl,-e,_start -Wl,--build-id=none
//   <工作区>/t85-work/rate_policy_test ; echo "exit=$?"（0=全通过）
//
// 本文件在 App 构建中完全惰性（整文件被 #if 包住，CMakeLists 逐文件列举源）。
// ============================================================================
#if defined(ENCODER_RATE_POLICY_HOST_TEST)

#include "encoder/encoder_rate_policy.h"

using size_t = __SIZE_TYPE__;

extern "C" void* memset(void* dst, int value, size_t count) {
  unsigned char* out = static_cast<unsigned char*>(dst);
  for (size_t i = 0; i < count; ++i) out[i] = static_cast<unsigned char>(value);
  return dst;
}
extern "C" void* memcpy(void* dst, const void* src, size_t count) {
  unsigned char* out = static_cast<unsigned char*>(dst);
  const unsigned char* in = static_cast<const unsigned char*>(src);
  for (size_t i = 0; i < count; ++i) out[i] = in[i];
  return dst;
}

namespace {
long Syscall3(long number, long a, long b, long c) {
  long result;
  __asm__ volatile("syscall"
                   : "=a"(result)
                   : "a"(number), "D"(a), "S"(b), "d"(c)
                   : "rcx", "r11", "memory");
  return result;
}
void Write(const char* text, size_t length) { Syscall3(1, 1, (long)text, (long)length); }
void WriteStr(const char* text) {
  size_t n = 0;
  while (text[n] != '\0') ++n;
  Write(text, n);
}
void WriteInt(long value) {
  char buffer[24];
  int index = 23;
  buffer[index] = '\0';
  if (value == 0) {
    buffer[--index] = '0';
  } else {
    const bool negative = value < 0;
    unsigned long magnitude = negative ? static_cast<unsigned long>(-value)
                                       : static_cast<unsigned long>(value);
    while (magnitude > 0) {
      buffer[--index] = static_cast<char>('0' + (magnitude % 10));
      magnitude /= 10;
    }
    if (negative) buffer[--index] = '-';
  }
  WriteStr(&buffer[index]);
}
void Exit(int code) { Syscall3(231, code, 0, 0); for (;;) {} }
int g_failures = 0;
void CheckEq(long actual, long expected, const char* what) {
  if (actual == expected) {
    WriteStr("  OK   "); WriteStr(what); WriteStr("\n");
  } else {
    ++g_failures;
    WriteStr("  FAIL "); WriteStr(what); WriteStr(": actual="); WriteInt(actual);
    WriteStr(" expected="); WriteInt(expected); WriteStr("\n");
  }
}

// ---- 「修复前」的参照实现（old-red 对照）---------------------------------
int32_t OldAppliedKbps(int64_t requested_bps) {          // 旧：total/1000 向下取整
  if (requested_bps <= 0) return 0;
  return static_cast<int32_t>(requested_bps / 1000);
}
int32_t OldAppliedWithLayerMin(int64_t requested_bps) {  // 旧：只保证 >= kMinLayerKbps(4)
  int32_t kbps = OldAppliedKbps(requested_bps);
  return kbps < 4 ? 4 : kbps;
}
}  // namespace

int main();
extern "C" void _start() { Exit(main()); }

int main() {
  using webrtcdemo::ApplyTotalFloorKbps;
  using webrtcdemo::CeilKbpsFromBps;
  using webrtcdemo::kDefaultTotalFloorKbps;
  using webrtcdemo::ShouldApplyRates;

  WriteStr("== encoder rate policy host test (freestanding) ==\n");

  // 1) 向上取整：applied_bps >= requested_bps（旧实现会欠额）
  CheckEq(CeilKbpsFromBps(6732), 7, "ceil(6732 bps) = 7 kbps (old 6 = -11%)");
  CheckEq(CeilKbpsFromBps(1000000), 1000, "ceil(1 Mbps) = 1000 kbps");
  CheckEq(CeilKbpsFromBps(999), 1, "ceil(999 bps) = 1 kbps");
  CheckEq(CeilKbpsFromBps(0), 0, "ceil(0) = 0");
  CheckEq(OldAppliedKbps(6732), 6, "old-red: old impl gives 6 kbps (< requested)");
  {
    const int32_t applied = CeilKbpsFromBps(6732) * 1000;
    CheckEq(applied >= 6732 ? 1 : 0, 1, "applied_bps >= requested_bps (6732)");
  }

  // 2) 总码率下限
  {
    bool clamped = false;
    const int32_t v = ApplyTotalFloorKbps(7, kDefaultTotalFloorKbps, &clamped);
    CheckEq(v, 30, "floor: 7 kbps -> 30 kbps");
    CheckEq(clamped ? 1 : 0, 1, "floor: clamped flag set");
    bool clamped2 = false;
    const int32_t v2 = ApplyTotalFloorKbps(500, kDefaultTotalFloorKbps, &clamped2);
    CheckEq(v2, 500, "floor: 500 kbps unchanged");
    CheckEq(clamped2 ? 1 : 0, 0, "floor: not clamped at 500 kbps");
    CheckEq(OldAppliedWithLayerMin(6732), 6, "old-red: old impl allows 6 kbps total");
  }

  // 3) setRates 去抖：<10% 且 <2 s ⇒ 跳过；>=10% 或 >=2 s ⇒ 应用
  CheckEq(ShouldApplyRates(0, 500000, 0, 1000) ? 1 : 0, 1,
          "debounce: first setRates always applies");
  CheckEq(ShouldApplyRates(1000000, 1050000, 10000, 11000) ? 1 : 0, 0,
          "debounce: +5% within 1 s -> skipped");
  CheckEq(ShouldApplyRates(1000000, 900000, 10000, 11000) ? 1 : 0, 1,
          "debounce: -10% (= threshold) -> applied");
  CheckEq(ShouldApplyRates(1000000, 940000, 10000, 11000) ? 1 : 0, 0,
          "debounce: -6% within 1 s -> skipped");
  CheckEq(ShouldApplyRates(1000000, 949999, 10000, 11000) ? 1 : 0, 0,
          "debounce: -5.1% within 1 s -> skipped");
  CheckEq(ShouldApplyRates(1000000, 1000000, 10000, 9000) ? 1 : 0, 0,
          "debounce: same value within 1 s -> skipped");
  CheckEq(ShouldApplyRates(1000000, 1000000, 10000, 13000) ? 1 : 0, 1,
          "debounce: same value after 3 s -> applied (avoid stale)");
  CheckEq(ShouldApplyRates(1000000, 1200000, 10000, 10500) ? 1 : 0, 1,
          "debounce: +20% within 0.5 s -> applied");

  WriteStr("== result: failures=");
  WriteInt(g_failures);
  WriteStr(" ==\n");
  return g_failures;
}

#endif  // ENCODER_RATE_POLICY_HOST_TEST
