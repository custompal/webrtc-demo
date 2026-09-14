// ============================================================================
// encoder/layer_bitrate_fold_host_test.cpp —— 分层码率折叠的**离线宿主自测**（t48）
// ----------------------------------------------------------------------------
// 目的：真机上每一次 nativeSetRates 都被 nativeSetRates_rejected reason=bad_dim
//   （s=5 t=4）拒绝，根因是「SDK 矩阵维度 = 5×4」与「本项目内部维度上限 = 3×3」
//   不一致。本自测**在容器内真实执行**，对修复后的 FoldSdkLayerMatrix() 与
//   LayerBitrateAllocator::Compute() 给出可复现证据：
//     ① 5×4（真机形态）能被接受并折叠成 1×3，且**不越界写**；
//     ② 折叠后 total 不丢、L1T3 的 40/30/30 拆分仍成立（120/210/300 kbps）；
//     ③ 越界/非法维度必须被拒绝（返回 false），而不是悄悄写坏内存。
//
// 编译与运行（容器内实测通过；命令见 reports/18-encoder-stall.md §5）：
//   NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
//   cd code/webrtc-demo
//   $NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
//       -fno-exceptions -fno-rtti -std=c++17 \
//       -DLAYER_BITRATE_HOST_TEST -DLAYER_BITRATE_FREESTANDING \
//       -I app/src/main/cpp \
//       app/src/main/cpp/encoder/layer_bitrate_fold_host_test.cpp \
//       app/src/main/cpp/encoder/layer_bitrate_allocator.cpp \
//       -o <工作区>/t48-work/layer_bitrate_fold_test -Wl,-e,_start -Wl,--build-id=none
//   <工作区>/t48-work/layer_bitrate_fold_test ; echo "exit=$?（0=全通过；非 0=失败项数）"
//
// 本文件在 App 构建中**完全惰性**（整文件被 `#if` 包住、无构建脚本改动）。
// ============================================================================
#if defined(LAYER_BITRATE_HOST_TEST)

#include "encoder/layer_bitrate_allocator.h"

// freestanding：不引入 <cstddef>，用编译器内建类型。
using size_t = __SIZE_TYPE__;

// freestanding：编译器会为结构体赋值生成 memset/memcpy 调用，这里给最小实现。
extern "C" void* memset(void* dst, int value, size_t count) {
  unsigned char* out = static_cast<unsigned char*>(dst);
  for (size_t i = 0; i < count; ++i) {
    out[i] = static_cast<unsigned char>(value);
  }
  return dst;
}

extern "C" void* memcpy(void* dst, const void* src, size_t count) {
  unsigned char* out = static_cast<unsigned char*>(dst);
  const unsigned char* in = static_cast<const unsigned char*>(src);
  for (size_t i = 0; i < count; ++i) {
    out[i] = in[i];
  }
  return dst;
}

// --------------------------- 极简 freestanding 运行时 ---------------------------
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
  while (text[length] != '\0') {
    ++length;
  }
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
    unsigned long magnitude =
        negative ? static_cast<unsigned long>(-value)
                 : static_cast<unsigned long>(value);
    while (magnitude > 0) {
      buffer[--index] = static_cast<char>('0' + (magnitude % 10));
      magnitude /= 10;
    }
    if (negative) {
      buffer[--index] = '-';
    }
  }
  WriteStr(&buffer[index]);
}

void Exit(int code) {
  Syscall3(231 /*exit_group*/, code, 0, 0);
  for (;;) {
  }
}

int g_failures = 0;

void Check(bool condition, const char* what) {
  if (condition) {
    WriteStr("  OK   ");
    WriteStr(what);
    WriteStr("\n");
  } else {
    ++g_failures;
    WriteStr("  FAIL ");
    WriteStr(what);
    WriteStr("\n");
  }
}

void CheckEq(long actual, long expected, const char* what) {
  if (actual == expected) {
    WriteStr("  OK   ");
    WriteStr(what);
    WriteStr(" = ");
    WriteInt(actual);
    WriteStr("\n");
  } else {
    ++g_failures;
    WriteStr("  FAIL ");
    WriteStr(what);
    WriteStr(": actual=");
    WriteInt(actual);
    WriteStr(" expected=");
    WriteInt(expected);
    WriteStr("\n");
  }
}

// 真机形态：SDK 5×4 矩阵，只有 [0][0] 非 0（其余由 SDK 填 0）。
int32_t g_sdk_flat[webrtcdemo::kSdkMaxSpatialLayers *
                    webrtcdemo::kSdkMaxTemporalStreams];

void FillSdkMatrix(int32_t value_at_0_0) {
  for (int i = 0; i < webrtcdemo::kSdkMaxSpatialLayers *
                          webrtcdemo::kSdkMaxTemporalStreams;
       ++i) {
    g_sdk_flat[i] = 0;
  }
  g_sdk_flat[0] = value_at_0_0;
}

// 边界哨兵：验证折叠**不会**写到 LayerBitrate 数组之外。
// 做法：在 out 前后各放一个 int32_t 哨兵，折叠后检查哨兵未被改写。
struct Guarded {
  int32_t before;
  webrtcdemo::LayerBitrate value;
  int32_t after;
};

}  // namespace

int main();

// _start：freestanding 入口（不依赖 libc 的 crt0）。
extern "C" void _start() {
  const int failures = main();
  Exit(failures);
}

int main() {
  using webrtcdemo::FoldSdkLayerMatrix;
  using webrtcdemo::LayerBitrate;
  using webrtcdemo::LayerBitrateAllocator;

  WriteStr("== layer_bitrate fold host test (freestanding) ==\n");

  // ---- ① 真机形态：5×4，total = 231813 bps（真机 GCC 请求值）----------------
  FillSdkMatrix(231813);
  {
    Guarded guarded;
    guarded.before = 0x5A5A5A5A;
    guarded.after = 0x5A5A5A5A;
    const bool ok = FoldSdkLayerMatrix(g_sdk_flat, 5, 4, 231813, 19,
                                       &guarded.value);
    Check(ok, "fold(5x4) accepted (was: bad_dim reject)");
    // 折叠目标 = 内部数组维度上限 3×3（不是配置维度 1×3）：5 个空间层里
    // 第 3、4 行被求和折进 0..2 行，保留可读性；真正写回 libvpx 的分层数
    // 由 Compute(folded, configured_spatial=1, configured_temporal=3) 决定
    // （见 ⑤：configured_spatial 恒为 1，契约 §5.6）。
    CheckEq(guarded.value.num_spatial, 3, "fold num_spatial (clamped to 3)");
    CheckEq(guarded.value.num_temporal, 3, "fold num_temporal");
    CheckEq(guarded.value.total_bps, 231813, "fold total_bps");
    CheckEq(guarded.value.framerate_fps, 19, "fold framerate_fps");
    CheckEq(guarded.value.layer_bps[0][0], 231813, "fold layer_bps[0][0]");
    CheckEq(guarded.value.layer_bps[0][1], 0, "fold layer_bps[0][1]");
    CheckEq(guarded.value.layer_bps[2][2], 0, "fold layer_bps[2][2]");
    CheckEq(guarded.before, 0x5A5A5A5A, "guard before untouched (no OOB)");
    CheckEq(guarded.after, 0x5A5A5A5A, "guard after untouched (no OOB)");
  }

  // ---- ② 5×4 且被钳掉的空间层有数据 → 按列求和，总量不丢 --------------------
  {
    FillSdkMatrix(0);
    g_sdk_flat[0 * 4 + 0] = 100000;  // s0t0
    g_sdk_flat[3 * 4 + 0] = 40000;   // s3t0（被钳掉，折进 s0t0）
    g_sdk_flat[1 * 4 + 3] = 7000;    // s1t3（t3 被钳掉，应被忽略）
    LayerBitrate out;
    const bool ok = FoldSdkLayerMatrix(g_sdk_flat, 5, 4, 140000, 30, &out);
    Check(ok, "fold(5x4 with folded spatial data) accepted");
    CheckEq(out.layer_bps[0][0], 140000, "fold sums clamped spatial layers");
  }

  // ---- ③ 1×1（单层）与 3×3（旧假定的形态）都仍被接受 -----------------------
  {
    const int32_t one[1] = {123456};
    LayerBitrate out;
    Check(FoldSdkLayerMatrix(one, 1, 1, 123456, 30, &out), "fold(1x1) accepted");
    CheckEq(out.num_temporal, 1, "fold(1x1) num_temporal");
    CheckEq(out.layer_bps[0][0], 123456, "fold(1x1) layer_bps[0][0]");
  }
  {
    int32_t flat3x3[9] = {0};
    flat3x3[0] = 500000;
    LayerBitrate out;
    Check(FoldSdkLayerMatrix(flat3x3, 3, 3, 500000, 30, &out),
          "fold(3x3) accepted");
    CheckEq(out.num_spatial, 3, "fold(3x3) num_spatial");
    CheckEq(out.layer_bps[2][2], 0, "fold(3x3) layer_bps[2][2]");
  }

  // ---- ④ 非法维度必须被拒绝（而不是越界写）--------------------------------
  {
    LayerBitrate out;
    Check(!FoldSdkLayerMatrix(g_sdk_flat, 6, 4, 1000, 30, &out),
          "fold(6x4) rejected (s > 5)");
    Check(!FoldSdkLayerMatrix(g_sdk_flat, 5, 5, 1000, 30, &out),
          "fold(5x5) rejected (t > 4)");
    Check(!FoldSdkLayerMatrix(g_sdk_flat, 0, 4, 1000, 30, &out),
          "fold(0x4) rejected");
    Check(!FoldSdkLayerMatrix(nullptr, 5, 4, 1000, 30, &out),
          "fold(nullptr) rejected");
  }

  // ---- ⑤ 折叠结果直接喂给 Compute：L1T3 的 40/30/30 与 {2,1,1} --------------
  {
    FillSdkMatrix(231813);
    LayerBitrate folded;
    FoldSdkLayerMatrix(g_sdk_flat, 5, 4, 231813, 19, &folded);
    const webrtcdemo::VpxLayerRates rates =
        LayerBitrateAllocator::Compute(folded, 1, 3);
    CheckEq(rates.configured_spatial, 1, "compute configured_spatial");
    CheckEq(rates.configured_temporal, 3, "compute configured_temporal");
    CheckEq(rates.rc_target_bitrate_kbps, 231, "compute rc_target_kbps");
    CheckEq(rates.ts_target_bitrate_kbps[0], 92,
            "compute ts l0 (cum 40%) kbps");
    CheckEq(rates.ts_target_bitrate_kbps[1], 162,
            "compute ts l1 (cum 70%) kbps");
    CheckEq(rates.ts_target_bitrate_kbps[2], 231,
            "compute ts l2 (cum 100%) kbps");
    Check(rates.ts_target_bitrate_kbps[0] <= rates.ts_target_bitrate_kbps[1] &&
              rates.ts_target_bitrate_kbps[1] <=
                  rates.ts_target_bitrate_kbps[2],
          "compute ts targets monotonic");
    CheckEq(static_cast<long>(rates.ts_rate_decimator[0]), 2,
            "compute decimator[0]");
    CheckEq(static_cast<long>(rates.ts_rate_decimator[1]), 1,
            "compute decimator[1]");
    CheckEq(static_cast<long>(rates.ts_rate_decimator[2]), 1,
            "compute decimator[2]");
  }

  // ---- ⑥ 真机首帧 300 kbps 的初始分层（与 nativeInit 日志逐值对照）----------
  {
    FillSdkMatrix(300000);
    LayerBitrate folded;
    FoldSdkLayerMatrix(g_sdk_flat, 5, 4, 300000, 60, &folded);
    const webrtcdemo::VpxLayerRates rates =
        LayerBitrateAllocator::Compute(folded, 1, 3);
    CheckEq(rates.ts_target_bitrate_kbps[0], 120, "init ts l0 = 120 kbps");
    CheckEq(rates.ts_target_bitrate_kbps[1], 210, "init ts l1 = 210 kbps");
    CheckEq(rates.ts_target_bitrate_kbps[2], 300, "init ts l2 = 300 kbps");
    CheckEq(rates.rc_target_bitrate_kbps, 300, "init rc_target = 300 kbps");
  }

  WriteStr("== result: failures=");
  WriteInt(g_failures);
  WriteStr(" ==\n");
  return g_failures;
}

#endif  // LAYER_BITRATE_HOST_TEST
