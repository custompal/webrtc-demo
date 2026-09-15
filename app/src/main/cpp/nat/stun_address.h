// ============================================================================
// nat/stun_address.h —— STUN 地址属性的**字节序安全**解码（t54 修复）
// ----------------------------------------------------------------------------
// 背景（真机缺陷，详见 reports/24-nat-address-endianness.md）：
//   探针把 XOR-MAPPED-ADDRESS 解出来的**公网地址打印成逐字节反转**的形式：
//     实际 111.55.211.195 打成 195.211.55.111；实际 120.230.119.148 打成
//     148.119.230.120（libwebrtc 的 srflx 候选地址是正确的，可作真值）。
//   根因不是「异或算错」，而是**同一个整数在两种字节序约定之间被混用**：
//     `ReadUint32Be()` 返回的是「地址的大端数值」（= 人类书写顺序的数值），
//     而 `struct in_addr::s_addr` 要求**网络序整数**（小端机上 = 内存里
//     就按 a.b.c.d 排布）。把前者直接赋给 s_addr 后 `inet_ntop` 读到的
//     4 个内存字节正好反过来 —— 于是地址被逐字节反转，端口却正确。
//
// 本头文件的设计原则（为什么这样写）：
//   * 只暴露**「4 个网络序字节」→ 字符串**的接口，**不接受整数**：
//     没有整数参与就没有主机序/网络序的混淆面；
//   * XOR 解码严格按 RFC 5389 §15.2 做**逐字节**异或（不借助 32 位整数），
//     因此在小端/大端上行为完全一致；
//   * 纯头文件、无 libc 依赖（唯一 extern 的 memcpy 由调用方/测试提供或走
//     内联循环），可在容器内用 NDK clang++ 以 freestanding 方式离线单测
//     （见 nat/stun_address_host_test.cpp）。
// ============================================================================
#pragma once

#if defined(STUN_ADDRESS_FREESTANDING)
// 离线宿主自测专用（容器内没有宿主 libc/libc++ 头）。
using uint8_t = unsigned char;
using uint16_t = unsigned short;
using uint32_t = unsigned int;
using size_t = __SIZE_TYPE__;
#else
#include <cstddef>
#include <cstdint>
#endif

namespace webrtcdemo {

// RFC 5389 magic cookie（0x2112A442）的**网络序字节**。
// 与 stun_client.cpp 里的 kMagicCookie 数值常量必须一致（下方有断言式注释）：
// 0x21 0x12 0xA4 0x42 按大端解释即 0x2112A442。
constexpr uint8_t kStunMagicCookieBytes[4] = {0x21, 0x12, 0xA4, 0x42};

// 大端读 16 位（返回「网络序数值」，即人类书写顺序的数值）。
inline uint16_t ReadUint16Be(const uint8_t* data) {
  return static_cast<uint16_t>((static_cast<uint16_t>(data[0]) << 8) |
                               static_cast<uint16_t>(data[1]));
}

// 大端读 32 位（返回「网络序数值」；**不要**直接赋给 in_addr::s_addr）。
inline uint32_t ReadUint32Be(const uint8_t* data) {
  return (static_cast<uint32_t>(data[0]) << 24) |
         (static_cast<uint32_t>(data[1]) << 16) |
         (static_cast<uint32_t>(data[2]) << 8) | static_cast<uint32_t>(data[3]);
}

// 把「网络序的 4 个地址字节」写成 `a.b.c.d`。
//
// 参数：
//   bytes     4 个地址字节，bytes[0] = 最高位（即 111.55.211.195 的 0x6F）。
//   out       输出缓冲（至少 16 字节；成功时写入以 '\0' 结尾的字符串）。
//   capacity  输出缓冲容量（< 16 时直接失败，避免截断出错误地址）。
// 返回值：true = 成功。
// 调用时机：解析 STUN 地址属性后格式化；以及把 sockaddr_in::sin_addr.s_addr
//           的 4 字节（同样是网络序）格式化时（取地址后按字节传入）。
inline bool FormatIpv4(const uint8_t* bytes, char* out, size_t capacity) {
  if (bytes == nullptr || out == nullptr || capacity < 16) {
    return false;
  }
  size_t pos = 0;
  for (int part = 0; part < 4; ++part) {
    const unsigned int value = static_cast<unsigned int>(bytes[part]);
    // 逐位写十进制，避免引入 snprintf/itoa 依赖（freestanding 可测）。
    char digits[3];
    int count = 0;
    unsigned int rest = value;
    do {
      digits[count++] = static_cast<char>('0' + (rest % 10u));
      rest /= 10u;
    } while (rest > 0);
    while (count > 0) {
      out[pos++] = digits[--count];
    }
    if (part < 3) {
      out[pos++] = '.';
    }
  }
  out[pos] = '\0';
  return true;
}

// 明文地址属性（MAPPED-ADDRESS 0x0001 / RESPONSE-ORIGIN 0x802B /
// OTHER-ADDRESS 0x802C）的值布局：
//   [0]   保留（0）
//   [1]   family（0x01 = IPv4）
//   [2..3] 端口（网络序）
//   [4..7] 地址（网络序 4 字节）
//
// 参数：value/length = 属性值及其长度（属性头已由调用方去掉）；
//       out_bytes4 = 输出 4 字节地址（可空）；out_port = 输出端口（可空）。
// 返回值：true = 解析成功（仅 IPv4；family 非 1 或长度 < 8 返回 false）。
inline bool DecodePlainAddress(const uint8_t* value, size_t length,
                               uint8_t* out_bytes4, uint16_t* out_port) {
  if (value == nullptr || length < 8) {
    return false;
  }
  if (value[1] != 0x01) {
    return false;  // 本项目只处理 IPv4
  }
  if (out_port != nullptr) {
    *out_port = ReadUint16Be(value + 2);
  }
  if (out_bytes4 != nullptr) {
    for (int i = 0; i < 4; ++i) {
      out_bytes4[i] = value[4 + i];
    }
  }
  return true;
}

// XOR 地址属性（XOR-MAPPED-ADDRESS 0x0020 / XOR-PEER-ADDRESS 0x0012 /
// XOR-RELAYED-ADDRESS 0x0016）的值布局与上面相同，但：
//   端口：与 magic cookie 的高 16 位（0x2112）异或；
//   地址：**逐字节**与 cookie 的 4 个网络序字节异或（RFC 5389 §15.2）。
// 为什么逐字节而不是 32 位整数异或：两者数学上等价，但逐字节写法不含任何
// 主机序假设，且与「不反转」的语义直接对应 —— 本缺陷正是整数路径引入的。
inline bool DecodeXorAddress(const uint8_t* value, size_t length,
                             uint8_t* out_bytes4, uint16_t* out_port) {
  if (value == nullptr || length < 8) {
    return false;
  }
  if (value[1] != 0x01) {
    return false;  // 本项目只处理 IPv4
  }
  const uint16_t xor_port =
      static_cast<uint16_t>((kStunMagicCookieBytes[0] << 8) |
                            kStunMagicCookieBytes[1]);
  if (out_port != nullptr) {
    *out_port = static_cast<uint16_t>(ReadUint16Be(value + 2) ^ xor_port);
  }
  if (out_bytes4 != nullptr) {
    for (int i = 0; i < 4; ++i) {
      out_bytes4[i] =
          static_cast<uint8_t>(value[4 + i] ^ kStunMagicCookieBytes[i]);
    }
  }
  return true;
}

}  // namespace webrtcdemo
