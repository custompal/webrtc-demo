// ============================================================================
// encoder/libvpx_cpu_guard_host_test.cpp —— CPU 能力自检的离线宿主单测（t55）
// ----------------------------------------------------------------------------
// 目的：容器内没有真机、也不能跑 gradle；把「本机 CPU 是否满足交付 libvpx 的
//   编译期 SIMD 假定」抽成纯函数后，可用 NDK clang++ 以 freestanding 方式
//   在容器内穷举各种 hwcap 组合，断言判定结果与日志短名。
//
// 编译与运行（容器内真实执行；命令同 reports/25-encoder-vpx-encode-crash.md）：
//   NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
//   cd code/webrtc-demo
//   $NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
//       -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra \
//       -DLIBVPX_CPU_GUARD_HOST_TEST -I app/src/main/cpp \
//       app/src/main/cpp/encoder/libvpx_cpu_guard_host_test.cpp \
//       -o <工作区>/t55-work/cpu_guard_test -Wl,-e,_start -Wl,--build-id=none
//   <工作区>/t55-work/cpu_guard_test ; echo "exit=$?"（0=全通过）
//
// 本文件在 App 构建中完全惰性（整文件被 #if 包住，CMakeLists 逐文件列举源）。
// ============================================================================
#if defined(LIBVPX_CPU_GUARD_HOST_TEST)

#include "encoder/libvpx_cpu_guard.h"

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

void Write(const char* text, size_t length) {
  Syscall3(1 /*write*/, 1 /*stdout*/, (long)text, (long)length);
}

void WriteStr(const char* text) {
  size_t length = 0;
  while (text[length] != '\0') ++length;
  Write(text, length);
}

void WriteInt(long value) {
  char buffer[24];
  int index = 23;
  buffer[index] = '\0';
  if (value == 0) {
    buffer[--index] = '0';
  } else {
    const bool negative = value < 0;
    unsigned long magnitude = negative
                                  ? static_cast<unsigned long>(-value)
                                  : static_cast<unsigned long>(value);
    while (magnitude > 0) {
      buffer[--index] = static_cast<char>('0' + (magnitude % 10));
      magnitude /= 10;
    }
    if (negative) buffer[--index] = '-';
  }
  WriteStr(&buffer[index]);
}

void Exit(int code) {
  Syscall3(231 /*exit_group*/, code, 0, 0);
  for (;;) {
  }
}

int g_failures = 0;

bool StrEq(const char* a, const char* b) {
  size_t i = 0;
  while (a[i] != '\0' && b[i] != '\0') {
    if (a[i] != b[i]) return false;
    ++i;
  }
  return a[i] == b[i];
}

void CheckStatus(webrtcdemo::LibvpxCpuStatus actual,
                 webrtcdemo::LibvpxCpuStatus expected, const char* what) {
  if (actual == expected) {
    WriteStr("  OK   ");
    WriteStr(what);
    WriteStr(" -> ");
    WriteStr(webrtcdemo::LibvpxCpuStatusName(actual));
    WriteStr("\n");
  } else {
    ++g_failures;
    WriteStr("  FAIL ");
    WriteStr(what);
    WriteStr(": actual=");
    WriteStr(webrtcdemo::LibvpxCpuStatusName(actual));
    WriteStr(" expected=");
    WriteStr(webrtcdemo::LibvpxCpuStatusName(expected));
    WriteStr("\n");
  }
}

}  // namespace

int main();

extern "C" void _start() { Exit(main()); }

int main() {
  using webrtcdemo::CheckLibvpxCpu;
  using webrtcdemo::kHwcap2I8mm;
  using webrtcdemo::kHwcap2Sve2;
  using webrtcdemo::kHwcapAsimdDotprod;
  using webrtcdemo::kHwcapSve;
  using webrtcdemo::LibvpxCpuStatus;

  WriteStr("== libvpx cpu guard host test (freestanding) ==\n");

  const unsigned long all_hwcap = kHwcapSve | kHwcapAsimdDotprod;
  const unsigned long all_hwcap2 = kHwcap2Sve2 | kHwcap2I8mm;

  // 1) 全齐 → 可安全使用
  CheckStatus(CheckLibvpxCpu(all_hwcap, all_hwcap2), LibvpxCpuStatus::kOk,
              "all extensions present");

  // 2) 典型真机（ARMv8.2 手机：有 dotprod，无 SVE/SVE2/i8mm）→ 必须拒绝
  CheckStatus(CheckLibvpxCpu(kHwcapAsimdDotprod, 0),
              LibvpxCpuStatus::kMissingSve, "typical phone (dotprod only)");

  // 3) 逐项缺失（顺序 = 判定优先级）
  CheckStatus(CheckLibvpxCpu(kHwcapAsimdDotprod, kHwcap2I8mm),
              LibvpxCpuStatus::kMissingSve, "missing sve");
  CheckStatus(CheckLibvpxCpu(all_hwcap, kHwcap2I8mm),
              LibvpxCpuStatus::kMissingSve2, "missing sve2");
  CheckStatus(CheckLibvpxCpu(kHwcapSve, all_hwcap2),
              LibvpxCpuStatus::kMissingDotprod, "missing dotprod");
  CheckStatus(CheckLibvpxCpu(all_hwcap, kHwcap2Sve2),
              LibvpxCpuStatus::kMissingI8mm, "missing i8mm");

  // 4) 全 0（不支持任何扩展的 arm64）→ 拒绝
  CheckStatus(CheckLibvpxCpu(0, 0), LibvpxCpuStatus::kMissingSve,
              "no extensions at all");

  // 5) 位掩码自洽：各常量对应内核 uapi 的标准位值
  {
    const bool ok = (kHwcapSve == (1UL << 22)) &&
                    (kHwcapAsimdDotprod == (1UL << 20)) &&
                    (kHwcap2Sve2 == (1UL << 1)) &&
                    (kHwcap2I8mm == (1UL << 13));
    if (ok) {
      WriteStr("  OK   hwcap bit values (SVE 1<<22, ASIMDDP 1<<20, SVE2 1<<1, "
               "I8MM 1<<13)\n");
    } else {
      ++g_failures;
      WriteStr("  FAIL hwcap bit values\n");
    }
  }

  // 6) 日志短名稳定（真机判据 grep 依赖它）
  {
    const bool ok = StrEq(webrtcdemo::LibvpxCpuStatusName(LibvpxCpuStatus::kOk), "ok") &&
                    StrEq(webrtcdemo::LibvpxCpuStatusName(LibvpxCpuStatus::kMissingSve), "missing_sve") &&
                    StrEq(webrtcdemo::LibvpxCpuStatusName(LibvpxCpuStatus::kMissingSve2), "missing_sve2") &&
                    StrEq(webrtcdemo::LibvpxCpuStatusName(LibvpxCpuStatus::kMissingDotprod), "missing_dotprod") &&
                    StrEq(webrtcdemo::LibvpxCpuStatusName(LibvpxCpuStatus::kMissingI8mm), "missing_i8mm");
    if (ok) {
      WriteStr("  OK   status names stable (ok/missing_sve/missing_sve2/"
               "missing_dotprod/missing_i8mm)\n");
    } else {
      ++g_failures;
      WriteStr("  FAIL status names\n");
    }
  }

  WriteStr("== result: failures=");
  WriteInt(g_failures);
  WriteStr(" ==\n");
  return g_failures;
}

#endif  // LIBVPX_CPU_GUARD_HOST_TEST
