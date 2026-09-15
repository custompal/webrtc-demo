// ============================================================================
// nat/stun_address_host_test.cpp —— STUN 地址字节序的**离线宿主自测**（t54）
// ----------------------------------------------------------------------------
// 目的：容器内没有宿主 libc/libc++ 头，但有 NDK clang++；本文件写成 freestanding
//   （只用 Linux syscall 写 stdout / 退出），把**真机日志里的真实数值**构造成
//   STUN 属性字节串，断言解码结果：
//     * XOR-MAPPED-ADDRESS：蜂窝端真实映射 111.55.211.195:37341（libwebrtc 的
//       `ice_candidate_local … srflx addr=111.55.211.195` 为真值），探针旧实现打成
//       195.211.55.111 ⇒ 本自测同时断言「正确路径」与「旧的反转结果」。
//     * 明文 MAPPED-ADDRESS：同一地址，验证非 XOR 路径同样正确。
//     * 边界：长度不足 / 非 IPv4 / 缓冲过小 / nullptr 必须返回失败。
//
// 编译与运行（容器内真实执行；命令同 reports/24-nat-address-endianness.md §5）：
//   NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
//   cd code/webrtc-demo
//   $NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
//       -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra \
//       -DSTUN_ADDRESS_HOST_TEST -DSTUN_ADDRESS_FREESTANDING -I app/src/main/cpp \
//       app/src/main/cpp/nat/stun_address_host_test.cpp \
//       -o <工作区>/t54-work/stun_address_test -Wl,-e,_start -Wl,--build-id=none
//   <工作区>/t54-work/stun_address_test ; echo "exit=$?"（0=全通过）
//
// 本文件在 App 构建中完全惰性（整文件被 `#if` 包住，且 CMakeLists 逐文件列举源）。
// ============================================================================
#if defined(STUN_ADDRESS_HOST_TEST)

#include "nat/stun_address.h"

using size_t = __SIZE_TYPE__;

// freestanding：结构体赋值可能生成 memset/memcpy 调用，这里给最小实现。
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

bool StrEq(const char* a, const char* b) {
  size_t i = 0;
  while (a[i] != '\0' && b[i] != '\0') {
    if (a[i] != b[i]) {
      return false;
    }
    ++i;
  }
  return a[i] == b[i];
}

void CheckStr(const char* actual, const char* expected, const char* what) {
  if (StrEq(actual, expected)) {
    WriteStr("  OK   ");
    WriteStr(what);
    WriteStr(" = ");
    WriteStr(actual);
    WriteStr("\n");
  } else {
    ++g_failures;
    WriteStr("  FAIL ");
    WriteStr(what);
    WriteStr(": actual=");
    WriteStr(actual);
    WriteStr(" expected=");
    WriteStr(expected);
    WriteStr("\n");
  }
}

void CheckU16(unsigned int actual, unsigned int expected, const char* what) {
  if (actual == expected) {
    WriteStr("  OK   ");
    WriteStr(what);
    WriteStr(" = ");
    WriteInt(static_cast<long>(actual));
    WriteStr("\n");
  } else {
    ++g_failures;
    WriteStr("  FAIL ");
    WriteStr(what);
    WriteStr(": actual=");
    WriteInt(static_cast<long>(actual));
    WriteStr(" expected=");
    WriteInt(static_cast<long>(expected));
    WriteStr("\n");
  }
}

// 把 4 个地址字节「反转」（复现旧实现的错误结果，用于证明自测能抓到回归）。
void ReverseBytes(const unsigned char* in, unsigned char* out) {
  out[0] = in[3];
  out[1] = in[2];
  out[2] = in[1];
  out[3] = in[0];
}

}  // namespace

int main();

extern "C" void _start() { Exit(main()); }

int main() {
  using webrtcdemo::DecodePlainAddress;
  using webrtcdemo::DecodeXorAddress;
  using webrtcdemo::FormatIpv4;
  using webrtcdemo::kStunMagicCookieBytes;

  WriteStr("== stun address endianness host test (freestanding) ==\n");

  // ---- 0) cookie 常量自洽：0x21 0x12 0xA4 0x42 == 0x2112A442 -----------------
  {
    const unsigned long cookie =
        (static_cast<unsigned long>(kStunMagicCookieBytes[0]) << 24) |
        (static_cast<unsigned long>(kStunMagicCookieBytes[1]) << 16) |
        (static_cast<unsigned long>(kStunMagicCookieBytes[2]) << 8) |
        static_cast<unsigned long>(kStunMagicCookieBytes[3]);
    Check(cookie == 0x2112A442ul, "magic cookie bytes == 0x2112A442 (big endian)");
  }

  // ---- 1) 明文 MAPPED-ADDRESS：真机蜂窝端真实映射 111.55.211.195:37341 ----
  {
    const unsigned char value[8] = {0x00, 0x01, 0x91, 0xDD,
                                    0x6F, 0x37, 0xD3, 0xC3};
    unsigned char bytes[4] = {0};
    unsigned short port = 0;
    Check(DecodePlainAddress(value, sizeof(value), bytes, &port),
          "plain MAPPED-ADDRESS(IPv4) decoded");
    CheckU16(port, 37341, "plain port");
    char ip[16] = {0};
    Check(FormatIpv4(bytes, ip, sizeof(ip)), "plain ip formatted");
    CheckStr(ip, "111.55.211.195", "plain ip (NOT reversed)");
  }

  // ---- 2) XOR-MAPPED-ADDRESS：RFC 5389 §15.2（真机蜂窝端同一条映射） --------
  // 端口 37341(0x91DD) ^ 0x2112 = 0xB0CF
  // 地址 6F.37.D3.C3 ^ 21.12.A4.42 = 4E.25.77.81
  {
    const unsigned char value[8] = {0x00, 0x01, 0xB0, 0xCF,
                                    0x4E, 0x25, 0x77, 0x81};
    unsigned char bytes[4] = {0};
    unsigned short port = 0;
    Check(DecodeXorAddress(value, sizeof(value), bytes, &port),
          "XOR-MAPPED-ADDRESS(IPv4) decoded");
    CheckU16(port, 37341, "xor port");
    char ip[16] = {0};
    FormatIpv4(bytes, ip, sizeof(ip));
    CheckStr(ip, "111.55.211.195", "xor ip (NOT reversed)");
    // 旧实现的错误结果（把网络序字节当成主机序整数交给 inet_ntop）：
    unsigned char reversed[4] = {0};
    ReverseBytes(bytes, reversed);
    char wrong[16] = {0};
    FormatIpv4(reversed, wrong, sizeof(wrong));
    CheckStr(wrong, "195.211.55.111", "old buggy path reproduces reversal");
  }

  // ---- 3) 第二条真机对照值（WiFi 端 120.230.119.148） -----------------------
  // 端口 7431(0x1D07) ^ 0x2112 = 0x3C15
  // 地址 78.E6.77.94 ^ 21.12.A4.42 = 59.F4.D3.D6
  {
    const unsigned char value[8] = {0x00, 0x01, 0x3C, 0x15,
                                    0x59, 0xF4, 0xD3, 0xD6};
    unsigned char bytes[4] = {0};
    unsigned short port = 0;
    Check(DecodeXorAddress(value, sizeof(value), bytes, &port),
          "XOR-MAPPED-ADDRESS #2 decoded");
    CheckU16(port, 7431, "xor port #2");
    char ip[16] = {0};
    FormatIpv4(bytes, ip, sizeof(ip));
    CheckStr(ip, "120.230.119.148", "xor ip #2 (NOT reversed)");
  }

  // ---- 4) 边界：长度/族/缓冲/空指针 ----------------------------------------
  {
    unsigned char bytes[4] = {0};
    unsigned short port = 0;
    const unsigned char short_value[7] = {0};
    const unsigned char ipv6_value[8] = {0x00, 0x02, 0x91, 0xDD,
                                         0x20, 0x01, 0x0D, 0xB8};
    Check(!DecodePlainAddress(short_value, sizeof(short_value), bytes, &port),
          "plain rejected when length < 8");
    Check(!DecodeXorAddress(short_value, sizeof(short_value), bytes, &port),
          "xor rejected when length < 8");
    Check(!DecodePlainAddress(ipv6_value, sizeof(ipv6_value), bytes, &port),
          "plain rejected when family != IPv4");
    Check(!DecodeXorAddress(ipv6_value, sizeof(ipv6_value), bytes, &port),
          "xor rejected when family != IPv4");
    char small[8] = {0};
    Check(!FormatIpv4(bytes, small, sizeof(small)),
          "format rejected when capacity < 16");
    Check(!FormatIpv4(nullptr, small, sizeof(small)),
          "format rejected when bytes == nullptr");
    Check(!DecodePlainAddress(nullptr, 8, bytes, &port),
          "plain rejected when value == nullptr");
  }

  // ---- 5) 十进制格式化边界（0 / 255 / 10.0.0.1） ---------------------------
  {
    char ip[16] = {0};
    const unsigned char zeros[4] = {0, 0, 0, 0};
    const unsigned char maxes[4] = {255, 255, 255, 255};
    const unsigned char ten[4] = {10, 0, 0, 1};
    FormatIpv4(zeros, ip, sizeof(ip));
    CheckStr(ip, "0.0.0.0", "format 0.0.0.0");
    FormatIpv4(maxes, ip, sizeof(ip));
    CheckStr(ip, "255.255.255.255", "format 255.255.255.255");
    FormatIpv4(ten, ip, sizeof(ip));
    CheckStr(ip, "10.0.0.1", "format 10.0.0.1");
  }

  WriteStr("== result: failures=");
  WriteInt(g_failures);
  WriteStr(" ==\n");
  return g_failures;
}

#endif  // STUN_ADDRESS_HOST_TEST
