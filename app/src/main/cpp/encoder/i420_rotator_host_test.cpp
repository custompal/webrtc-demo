// ============================================================================
// encoder/i420_rotator_host_test.cpp —— I420 旋转的**离线宿主自测**（t46）
// ----------------------------------------------------------------------------
// 目的：容器内无 JDK/SDK、无 libc 头（`/usr/include/stdio.h` 缺），但**有 NDK clang++**。
//   因此本文件写成 **freestanding**（不引入任何标准库头，只用 Linux syscall 写 stdout/退出），
//   用 NDK clang 以 host target 编译成静态可执行文件后**在容器内真实运行**，
//   从而对 rotation=0/90/180/270 的**输出尺寸与像素布局**给出可复现证据。
//
// 编译与运行（容器内实测通过；命令同 reports/17-vp9-rotation.md §5）：
//   NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
//   cd code/webrtc-demo
//   $NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
//       -fno-exceptions -fno-rtti -std=c++17 -DI420_ROTATOR_HOST_TEST -DI420_ROTATOR_FREESTANDING \
//       -I app/src/main/cpp app/src/main/cpp/encoder/i420_rotator_host_test.cpp \
//       -o <工作区>/t46-work/i420_rotator_test -Wl,-e,_start -Wl,--build-id=none
//   <工作区>/t46-work/i420_rotator_test ; echo "exit=$?（0=全通过；非 0=失败项数）"
//
// 本文件在 App 构建中**完全惰性**（整文件被 `#if defined(I420_ROTATOR_HOST_TEST)` 包住），
// 无需任何构建脚本改动（不新增被 App 编译的翻译单元）。
// ============================================================================
#if defined(I420_ROTATOR_HOST_TEST)

#include "encoder/i420_rotator.h"

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

void WriteInt(int value) {
  char buffer[16];
  int index = 15;
  buffer[index] = '\0';
  if (value == 0) {
    buffer[--index] = '0';
  } else {
    const bool negative = value < 0;
    int magnitude = negative ? -value : value;
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

[[noreturn]] void Exit(int code) {
  Syscall3(231 /*exit_group*/, code, 0, 0);
  for (;;) {
  }
}

// --------------------------- 断言与统计 ---------------------------
int g_failures = 0;

void ExpectEqInt(const char* name, int actual, int expected) {
  WriteStr(actual == expected ? "  OK   " : "  FAIL ");
  WriteStr(name);
  WriteStr(": actual=");
  WriteInt(actual);
  WriteStr(" expected=");
  WriteInt(expected);
  WriteStr("\n");
  if (actual != expected) {
    ++g_failures;
  }
}

void ExpectEqByte(const char* name, int index, uint8_t actual, uint8_t expected) {
  if (actual == expected) {
    return;  // 逐字节一致时不打噪声，仅失败时输出
  }
  WriteStr("  FAIL ");
  WriteStr(name);
  WriteStr("[");
  WriteInt(index);
  WriteStr("]: actual=");
  WriteInt(actual);
  WriteStr(" expected=");
  WriteInt(expected);
  WriteStr("\n");
  ++g_failures;
}

// 源图：4x2 I420，Y/U/V 的 stride 都**大于**有效宽度（带 padding），
// 以验证"只读有效像素、不读不写 padding"。
uint8_t g_src_y[2 * 8];
uint8_t g_src_u[1 * 4];
uint8_t g_src_v[1 * 4];
uint8_t g_dst[512];

constexpr uint8_t kSrcPad = 0xEE;
constexpr uint8_t kDstPad = 0x77;

void FillSource() {
  // Y：stride=8 的两行（每行 4 个有效像素 + 4 个 padding）
  const uint8_t row0[4] = {10, 11, 12, 13};
  const uint8_t row1[4] = {20, 21, 22, 23};
  for (int x = 0; x < 4; ++x) {
    g_src_y[x] = row0[x];
    g_src_y[4 + x] = kSrcPad;
    g_src_y[8 + x] = row1[x];
    g_src_y[12 + x] = kSrcPad;
  }
  g_src_u[0] = 100;
  g_src_u[1] = 101;
  g_src_u[2] = kSrcPad;
  g_src_u[3] = kSrcPad;
  g_src_v[0] = 200;
  g_src_v[1] = 201;
  g_src_v[2] = kSrcPad;
  g_src_v[3] = kSrcPad;
}

webrtcdemo::I420View MakeSrcView() {
  webrtcdemo::I420View view{};
  view.y.data = g_src_y;
  view.y.stride = 8;
  view.u.data = g_src_u;
  view.u.stride = 4;
  view.v.data = g_src_v;
  view.v.stride = 4;
  view.width = 4;
  view.height = 2;
  return view;
}

webrtcdemo::I420MutView MakeDstView(int rotation, int* out_y_stride, int* out_uv_stride) {
  const int rot_w = webrtcdemo::RotatedWidth(4, 2, rotation);
  const int rot_h = webrtcdemo::RotatedHeight(4, 2, rotation);
  const int y_stride = rot_w + 4;   // 刻意带 padding：越界写会被下面的 padding 检查抓到
  const int uv_stride = rot_w / 2 + 2;
  const size_t y_size = static_cast<size_t>(y_stride) * static_cast<size_t>(rot_h);
  const size_t uv_size = static_cast<size_t>(uv_stride) * static_cast<size_t>(rot_h / 2);
  webrtcdemo::I420MutView view{};
  view.y.data = g_dst;
  view.y.stride = y_stride;
  view.u.data = g_dst + y_size;
  view.u.stride = uv_stride;
  view.v.data = view.u.data + uv_size;
  view.v.stride = uv_stride;
  *out_y_stride = y_stride;
  *out_uv_stride = uv_stride;
  return view;
}

void FillDst() {
  for (int i = 0; i < 512; ++i) {
    g_dst[i] = kDstPad;
  }
}

/// 逐字节校验一个平面的有效区域，并确认该平面行尾 padding 未被写入。
void CheckPlane(const char* name, const uint8_t* data, int stride, int width, int height,
                const uint8_t* expected) {
  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      ExpectEqByte(name, y * width + x, data[static_cast<size_t>(y) * static_cast<size_t>(stride) + x],
                   expected[y * width + x]);
    }
    for (int x = width; x < stride; ++x) {
      const uint8_t padding = data[static_cast<size_t>(y) * static_cast<size_t>(stride) + x];
      if (padding != kDstPad) {
        WriteStr("  FAIL ");
        WriteStr(name);
        WriteStr(": dst padding written row=");
        WriteInt(y);
        WriteStr(" col=");
        WriteInt(x);
        WriteStr(" value=");
        WriteInt(padding);
        WriteStr("\n");
        ++g_failures;
      }
    }
  }
}

/// 单个 rotation 的四项检查：尺寸/总字节数、Y 布局、U 布局、V 布局。
void TestRotation(int rotation, const uint8_t* expected_y, const uint8_t* expected_u,
                  const uint8_t* expected_v) {
  WriteStr("[rotation=");
  WriteInt(rotation);
  WriteStr("] 4x2 -> ");
  WriteInt(webrtcdemo::RotatedWidth(4, 2, rotation));
  WriteStr("x");
  WriteInt(webrtcdemo::RotatedHeight(4, 2, rotation));
  WriteStr("\n");

  FillSource();
  FillDst();
  const webrtcdemo::I420View src = MakeSrcView();
  int y_stride = 0;
  int uv_stride = 0;
  const webrtcdemo::I420MutView dst = MakeDstView(rotation, &y_stride, &uv_stride);

  const int rot_w = webrtcdemo::RotatedWidth(4, 2, rotation);
  const int rot_h = webrtcdemo::RotatedHeight(4, 2, rotation);
  ExpectEqInt("rotated_width", rot_w, webrtcdemo::IsQuarterTurn(rotation) ? 2 : 4);
  ExpectEqInt("rotated_height", rot_h, webrtcdemo::IsQuarterTurn(rotation) ? 4 : 2);
  ExpectEqInt("rotated_y_size", static_cast<int>(webrtcdemo::RotatedYSize(4, 2, rotation)),
              rot_w * rot_h);
  ExpectEqInt("rotated_uv_size", static_cast<int>(webrtcdemo::RotatedUvSize(4, 2, rotation)),
              (rot_w / 2) * (rot_h / 2));
  ExpectEqInt("rotated_total_size", static_cast<int>(webrtcdemo::RotatedTotalSize(4, 2, rotation)),
              rot_w * rot_h + 2 * (rot_w / 2) * (rot_h / 2));

  webrtcdemo::RotateI420(src, rotation, dst);

  CheckPlane("dst_y", dst.y.data, y_stride, rot_w, rot_h, expected_y);
  CheckPlane("dst_u", dst.u.data, uv_stride, rot_w / 2, rot_h / 2, expected_u);
  CheckPlane("dst_v", dst.v.data, uv_stride, rot_w / 2, rot_h / 2, expected_v);
}

}  // namespace

extern "C" void _start() {
  WriteStr("== i420_rotator host test (freestanding) ==\n");

  // 规范化（doc/14:635：非法按 0 处理）
  ExpectEqInt("normalize(0)", webrtcdemo::NormalizeRotationDegrees(0), 0);
  ExpectEqInt("normalize(90)", webrtcdemo::NormalizeRotationDegrees(90), 90);
  ExpectEqInt("normalize(180)", webrtcdemo::NormalizeRotationDegrees(180), 180);
  ExpectEqInt("normalize(270)", webrtcdemo::NormalizeRotationDegrees(270), 270);
  ExpectEqInt("normalize(45)", webrtcdemo::NormalizeRotationDegrees(45), 0);
  ExpectEqInt("normalize(-90)", webrtcdemo::NormalizeRotationDegrees(-90), 0);
  ExpectEqInt("normalize(360)", webrtcdemo::NormalizeRotationDegrees(360), 0);
  ExpectEqInt("quarter(0)", webrtcdemo::IsQuarterTurn(0) ? 1 : 0, 0);
  ExpectEqInt("quarter(90)", webrtcdemo::IsQuarterTurn(90) ? 1 : 0, 1);
  ExpectEqInt("quarter(180)", webrtcdemo::IsQuarterTurn(180) ? 1 : 0, 0);
  ExpectEqInt("quarter(270)", webrtcdemo::IsQuarterTurn(270) ? 1 : 0, 1);

  // 源 Y = [[10,11,12,13],[20,21,22,23]]；U = [[100,101]]；V = [[200,201]]
  const uint8_t y0[8] = {10, 11, 12, 13, 20, 21, 22, 23};
  const uint8_t u0[2] = {100, 101};
  const uint8_t v0[2] = {200, 201};
  TestRotation(0, y0, u0, v0);

  // 顺时针 90°：4x2 → 2x4；左列自下而上成为首行
  const uint8_t y90[8] = {20, 10, 21, 11, 22, 12, 23, 13};
  const uint8_t u90[2] = {100, 101};  // 1x2 紧密排列
  const uint8_t v90[2] = {200, 201};
  TestRotation(90, y90, u90, v90);

  // 180°：4x2 → 4x2（内容 180°）
  const uint8_t y180[8] = {23, 22, 21, 20, 13, 12, 11, 10};
  const uint8_t u180[2] = {101, 100};  // 2x1
  const uint8_t v180[2] = {201, 200};
  TestRotation(180, y180, u180, v180);

  // 顺时针 270°（= 逆时针 90°）：4x2 → 2x4；右列自下而上成为首行
  const uint8_t y270[8] = {13, 23, 12, 22, 11, 21, 10, 20};
  const uint8_t u270[2] = {101, 100};
  const uint8_t v270[2] = {201, 200};
  TestRotation(270, y270, u270, v270);

  WriteStr("== result: failures=");
  WriteInt(g_failures);
  WriteStr(" ==\n");
  Exit(g_failures);
}

#endif  // I420_ROTATOR_HOST_TEST
