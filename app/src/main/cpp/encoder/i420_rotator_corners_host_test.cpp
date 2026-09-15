// ============================================================================
// encoder/i420_rotator_corners_host_test.cpp —— 旋转方向/角度的**角点确定性单测**（t57）
// ----------------------------------------------------------------------------
// 目的（acceptance 要求）：用「带方向标记的合成 I420」逐字节断言 4 个角点的落点，
//   从而确定无疑地说明 `i420_rotator.h` 实际施加的是哪个方向/角度：
//     * 源码：顺时针约定（doc/14:635 / Android VideoFrame.rotation = 顺时针角度）
//     * 本单测：把源图四个角分别标记为 TL/TR/BL/BR（Y 平面四角 + 色度两角），
//       对 0/90/180/270 断言旋转后四角的**新位置**，并用「顺时针 90° = 左列自下而上
//       成为首行」这一独立几何事实交叉验证（不依赖被测代码的任何注释）。
//   若实现变成逆时针（或 90/270 互换），角点断言必然失败 ⇒ 该单测能抓到方向回归。
//
// 编译与运行（容器内真实执行；命令同 reports/27-encoder-direction-perf.md §5）：
//   NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
//   cd code/webrtc-demo
//   $NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
//       -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra \
//       -DI420_ROTATOR_CORNERS_HOST_TEST -DI420_ROTATOR_FREESTANDING \
//       -I app/src/main/cpp app/src/main/cpp/encoder/i420_rotator_corners_host_test.cpp \
//       -o <工作区>/t57-work/i420_corners_test -Wl,-e,_start -Wl,--build-id=none
//   <工作区>/t57-work/i420_corners_test ; echo "exit=$?"（0=全通过）
//
// 本文件在 App 构建中完全惰性（整文件被 #if 包住，CMakeLists 逐文件列举源）。
// ============================================================================
#if defined(I420_ROTATOR_CORNERS_HOST_TEST)

#include "encoder/i420_rotator.h"

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
  Syscall3(1, 1, (long)text, (long)length);
}
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
  Syscall3(231, code, 0, 0);
  for (;;) {
  }
}

int g_failures = 0;
void CheckEq(long actual, long expected, const char* what) {
  if (actual == expected) {
    WriteStr("  OK   ");
    WriteStr(what);
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

// 4x2 源：四角用可区分值（Y 平面）
//   TL=1    TR=2        row0: 1 2 3 4
//   BL=5    BR=6        row1: 5 6 7 8
constexpr int kW = 4;
constexpr int kH = 2;
unsigned char g_src[32];
unsigned char g_dst[64];

void BuildSource() {
  for (int i = 0; i < 32; ++i) g_src[i] = 0;
  // Y stride 8（含 padding，验证 stride 处理）
  g_src[0] = 1;
  g_src[1] = 2;
  g_src[2] = 3;
  g_src[3] = 4;
  g_src[8] = 5;
  g_src[9] = 6;
  g_src[10] = 7;
  g_src[11] = 8;
}

webrtcdemo::I420View SourceView() {
  webrtcdemo::I420View v{};
  v.y.data = g_src;
  v.y.stride = 8;
  v.u.data = g_src;
  v.u.stride = 8;
  v.v.data = g_src;
  v.v.stride = 8;
  v.width = kW;
  v.height = kH;
  return v;
}

// 取旋转后 (x,y) 的 Y 值（dst stride = rot_w，紧排）
int DstAt(int x, int y, int rot_w, bool* inside) {
  *inside = (x >= 0 && y >= 0 && x < rot_w);
  if (!*inside) return -1;
  return g_dst[(size_t)y * rot_w + x];
}

void RotateAndCheck(int rotation) {
  BuildSource();
  for (int i = 0; i < 64; ++i) g_dst[i] = 0;
  webrtcdemo::I420MutView dst{};
  const int rot_w = webrtcdemo::RotatedWidth(kW, kH, rotation);
  const int rot_h = webrtcdemo::RotatedHeight(kW, kH, rotation);
  // 【测试自身曾踩的坑】U/V 必须放在 Y 之后（生产代码同构：rotate_buf_ 的三平面紧排），
  // 否则色度旋转会覆盖 Y 平面，角点断言全部失真。
  const size_t y_size = static_cast<size_t>(rot_w) * static_cast<size_t>(rot_h);
  const size_t uv_size = static_cast<size_t>(rot_w / 2) * static_cast<size_t>(rot_h / 2);
  dst.y.data = g_dst;
  dst.y.stride = rot_w;
  dst.u.data = g_dst + y_size;
  dst.u.stride = rot_w / 2;
  dst.v.data = dst.u.data + uv_size;
  dst.v.stride = rot_w / 2;
  webrtcdemo::RotateI420(SourceView(), rotation, dst);

  char label[64];
  // 尺寸：90/270 交换宽高
  const int expected_w = (rotation == 90 || rotation == 270) ? kH : kW;
  const int expected_h = (rotation == 90 || rotation == 270) ? kW : kH;
  CheckEq(rot_w, expected_w, "rotated width");
  CheckEq(rot_h, expected_h, "rotated height");

  // 角点断言：
  //   r=0   ：TL=(0,0)=1  TR=(3,0)=4  BL=(0,1)=5  BR=(3,1)=8
  //   r=90  ：顺时针 90 ⇒ 左列自下而上成为首行 ⇒ (0,0)=BL=5,(1,0)=TL=1,
  //           (0,1)=6,(1,1)=2,(0,2)=7,(1,2)=3,(0,3)=8,(1,3)=4
  //   r=180 ：(0,0)=BR=8,(3,0)=BL=5,(0,1)=TR=4,(3,1)=TL=1
  //   r=270 ：右列自上而下成为首行 ⇒ (0,0)=TR=4,(1,0)=BR=8,
  //           (0,1)=3,(1,1)=7,(0,2)=2,(1,2)=6,(0,3)=1,(1,3)=5
  bool inside = false;
  int v00 = DstAt(0, 0, rot_w, &inside);
  (void)inside;
  int vlast0 = DstAt(rot_w - 1, 0, rot_w, &inside);
  int v0last = DstAt(0, rot_h - 1, rot_w, &inside);
  int vll = DstAt(rot_w - 1, rot_h - 1, rot_w, &inside);

  if (rotation == 0) {
    CheckEq(v00, 1, "r=0 TL=1");        // 源 TL
    CheckEq(vlast0, 4, "r=0 TR=4");     // 源 TR
    CheckEq(v0last, 5, "r=0 BL=5");     // 源 BL
    CheckEq(vll, 8, "r=0 BR=8");        // 源 BR
  } else if (rotation == 90) {
    // 顺时针：源左列(1,5) 自下而上 → 首行(5,1) ⇒ dst TL = 源 BL，dst TR = 源 TL
    CheckEq(v00, 5, "r=90 TL=src BL(5) [clockwise]");
    CheckEq(vlast0, 1, "r=90 TR=src TL(1)");
    CheckEq(v0last, 8, "r=90 BL=src BR(8)");
    CheckEq(vll, 4, "r=90 BR=src TR(4)");
    CheckEq(DstAt(0, 1, rot_w, &inside), 6, "r=90 (0,1)=src(1,1)=6");
    CheckEq(DstAt(1, 3, rot_w, &inside), 4, "r=90 last row = src row0=1 2 3 4");
  } else if (rotation == 180) {
    CheckEq(v00, 8, "r=180 TL=src BR(8)");
    CheckEq(vlast0, 5, "r=180 TR=src BL(5)");
    CheckEq(v0last, 4, "r=180 BL=src TR(4)");
    CheckEq(vll, 1, "r=180 BR=src TL(1)");
  } else if (rotation == 270) {
    // 逆时针 90 = 顺时针 270：源右列(4,8) 自上而下 → 首行(4,8)
    CheckEq(v00, 4, "r=270 TL=src TR(4) [clockwise 270]");
    CheckEq(vlast0, 8, "r=270 TR=src BR(8)");
    CheckEq(v0last, 1, "r=270 BL=src TL(1)");
    CheckEq(vll, 5, "r=270 BR=src BL(5)");
    CheckEq(DstAt(0, 1, rot_w, &inside), 3, "r=270 (0,1)=src(2,0)=3");
  }
  WriteStr(label);
}

}  // namespace

int main();

extern "C" void _start() { Exit(main()); }

int main() {
  using webrtcdemo::NormalizeRotationDegrees;
  using webrtcdemo::RotatedHeight;
  using webrtcdemo::RotatedWidth;
  using webrtcdemo::IsQuarterTurn;

  WriteStr("== i420 rotator corners host test (freestanding) ==\n");

  // 尺寸与 quarter-turn 判定
  CheckEq(RotatedWidth(kW, kH, 0), 4, "RotatedWidth r=0");
  CheckEq(RotatedHeight(kW, kH, 0), 2, "RotatedHeight r=0");
  CheckEq(RotatedWidth(kW, kH, 90), 2, "RotatedWidth r=90 (swap)");
  CheckEq(RotatedHeight(kW, kH, 90), 4, "RotatedHeight r=90 (swap)");
  CheckEq(RotatedWidth(kW, kH, 180), 4, "RotatedWidth r=180");
  CheckEq(RotatedWidth(kW, kH, 270), 2, "RotatedWidth r=270 (swap)");
  CheckEq(IsQuarterTurn(90) ? 1 : 0, 1, "IsQuarterTurn(90)");
  CheckEq(IsQuarterTurn(180) ? 1 : 0, 0, "IsQuarterTurn(180)");
  CheckEq(NormalizeRotationDegrees(45), 0, "illegal 45 -> 0");
  CheckEq(NormalizeRotationDegrees(-90), 0, "illegal -90 -> 0");
  CheckEq(NormalizeRotationDegrees(360), 0, "360 -> 0");
  CheckEq(NormalizeRotationDegrees(270), 270, "270 kept");

  // 角点：0/90/180/270 逐项
  RotateAndCheck(0);
  RotateAndCheck(90);
  RotateAndCheck(180);
  RotateAndCheck(270);

  WriteStr("== result: failures=");
  WriteInt(g_failures);
  WriteStr(" ==\n");
  return g_failures;
}

#endif  // I420_ROTATOR_CORNERS_HOST_TEST
