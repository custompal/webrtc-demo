// ============================================================================
// nat/stun_client.cpp —— STUN 报文编解码与收发实现
// ----------------------------------------------------------------------------
// 报文格式（RFC 5389 §6）：
//   0                   1                   2                   3
//   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
//   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//   |0 0|     STUN Message Type     |         Message Length        |
//   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//   |                         Magic Cookie = 0x2112A442             |
//   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//   |                     Transaction ID (96 bits)                  |
//   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
//   属性为 TLV：type(2) + length(2) + value（4 字节对齐填充）。
// 用到的属性：
//   0x0003 CHANGE-REQUEST（bit1 = change IP，bit2 = change port，RFC 5780 §7.2）
//   0x0001 MAPPED-ADDRESS（未加密）
//   0x0020 XOR-MAPPED-ADDRESS（与 cookie/事务 ID 异或）
//   0x002B/0x802B RESPONSE-ORIGIN（RFC 5780）
//   0x002C/0x802C OTHER-ADDRESS（RFC 5780）
// ============================================================================
#include "nat/stun_client.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <algorithm>
#include <cstring>
#include <string>

#include "log/log_macros.h"
#include "nat/stun_address.h"

namespace webrtcdemo {
namespace {

constexpr char kTag[] = "nat";

constexpr uint32_t kMagicCookie = 0x2112A442u;
constexpr uint16_t kBindingRequest = 0x0001;
constexpr uint16_t kBindingSuccessResponse = 0x0101;
constexpr uint16_t kBindingErrorResponse = 0x0111;

constexpr uint16_t kAttrMappedAddress = 0x0001;
constexpr uint16_t kAttrChangeRequest = 0x0003;
constexpr uint16_t kAttrXorMappedAddress = 0x0020;
constexpr uint16_t kAttrResponseOrigin = 0x802B;
constexpr uint16_t kAttrOtherAddress = 0x802C;

constexpr uint16_t kChangeIpFlag = 0x02;
constexpr uint16_t kChangePortFlag = 0x04;

constexpr size_t kHeaderSize = 20;
constexpr int kRecvSliceMs = 100;  // 接收超时切片：便于及时响应取消

int64_t NowMillis() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<int64_t>(ts.tv_sec) * 1000 +
         static_cast<int64_t>(ts.tv_nsec) / 1000000;
}

uint16_t ReadUint16(const uint8_t* data) {
  return static_cast<uint16_t>((static_cast<uint16_t>(data[0]) << 8) |
                               static_cast<uint16_t>(data[1]));
}

uint32_t ReadUint32(const uint8_t* data) {
  return (static_cast<uint32_t>(data[0]) << 24) |
         (static_cast<uint32_t>(data[1]) << 16) |
         (static_cast<uint32_t>(data[2]) << 8) | static_cast<uint32_t>(data[3]);
}

void WriteUint16(uint8_t* data, uint16_t value) {
  data[0] = static_cast<uint8_t>((value >> 8) & 0xFF);
  data[1] = static_cast<uint8_t>(value & 0xFF);
}

void WriteUint32(uint8_t* data, uint32_t value) {
  data[0] = static_cast<uint8_t>((value >> 24) & 0xFF);
  data[1] = static_cast<uint8_t>((value >> 16) & 0xFF);
  data[2] = static_cast<uint8_t>((value >> 8) & 0xFF);
  data[3] = static_cast<uint8_t>(value & 0xFF);
}

// 【t54 修复】把「网络序的 4 个地址字节」格式化为 `a.b.c.d`。
//
// 【t54 修复】旧实现 `IpToString(uint32_t)` 要求「网络序整数」，而 STUN 属性里读
// 出的 4 字节经 `ReadUint32()` 得到的是「大端数值」（人类书写顺序的整数）；把后者
// 直接交给 `inet_ntop` 时，小端设备上读到的内存字节正好反转 ⇒ 真机打印出
// 195.211.55.111（实际 111.55.211.195）。现在统一走**字节**接口：STUN 属性用
// `DecodePlainAddress()/DecodeXorAddress()`（nat/stun_address.h）+ 本函数，
// sockaddr（getsockname/recvfrom，本身即网络序）用 IpFromSockaddrInAddr()。
std::string IpFromNetworkOrderBytes(const uint8_t* bytes) {
  char buffer[INET_ADDRSTRLEN] = {0};
  if (!FormatIpv4(bytes, buffer, sizeof(buffer))) {
    return std::string("-");
  }
  return std::string(buffer);
}

// sockaddr_in::sin_addr.s_addr（网络序）→ 字符串。
std::string IpFromSockaddrInAddr(const struct in_addr& addr) {
  return IpFromNetworkOrderBytes(
      reinterpret_cast<const uint8_t*>(&addr.s_addr));
}

// 生成 96 位事务 ID。这里不需要密码学强度，只需在本次探测内唯一且能匹配响应；
// 用单调时间 + 原子计数器 + pid 混合，避免依赖随机数设施（-fno-exceptions 下
// std::random_device 失败会直接 terminate）。
void FillTransactionId(uint8_t* transaction_id) {
  static std::atomic<uint64_t> counter{0};
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  const uint64_t a = static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
                     static_cast<uint64_t>(ts.tv_nsec);
  const uint64_t b = (counter.fetch_add(1) + 1) * 0x9E3779B97F4A7C15ull +
                     static_cast<uint64_t>(getpid());
  uint64_t mixed[2] = {a ^ b, a + b};
  for (size_t i = 0; i < 12; ++i) {
    transaction_id[i] =
        static_cast<uint8_t>((mixed[i / 8] >> ((i % 8) * 8)) & 0xFFu);
  }
}

// 解析 MAPPED-ADDRESS / RESPONSE-ORIGIN / OTHER-ADDRESS 这类“明文地址”属性。
// 【t54 修复】改为按**字节**解码（DecodePlainAddress），不再经过
// `IpToString(ReadUint32(value + 4))` 这条会反转 4 字节的整数路径。
bool ParsePlainAddress(const uint8_t* value, size_t length, StunAddress* out) {
  if (value == nullptr || out == nullptr || length < 8) {
    return false;
  }
  uint8_t bytes[4] = {0};
  uint16_t port = 0;
  if (!DecodePlainAddress(value, length, bytes, &port)) {
    return false;  // 非 IPv4：本项目不处理
  }
  out->port = port;
  out->ip = IpFromNetworkOrderBytes(bytes);
  out->valid = !out->ip.empty() && out->ip != "-";
  return out->valid;
}

// 解析 XOR-MAPPED-ADDRESS（RFC 5389 §15.2）。
// 【t54 修复】改为**逐字节**与 cookie 异或（DecodeXorAddress），
// 端口与地址都按网络序解读，不做任何整数/主机序转换。
bool ParseXorMappedAddress(const uint8_t* value, size_t length,
                           const uint8_t* transaction_id, StunAddress* out) {
  if (value == nullptr || out == nullptr || transaction_id == nullptr ||
      length < 8) {
    return false;
  }
  uint8_t bytes[4] = {0};
  uint16_t port = 0;
  if (!DecodeXorAddress(value, length, bytes, &port)) {
    return false;  // 非 IPv4：本项目不处理
  }
  out->port = port;
  out->ip = IpFromNetworkOrderBytes(bytes);
  out->valid = !out->ip.empty() && out->ip != "-";
  return out->valid;
}

}  // namespace

std::string StunAddress::ToString() const {
  if (!valid) {
    return std::string("-");
  }
  return ip + ":" + std::to_string(port);
}

bool StunAddress::operator==(const StunAddress& other) const {
  return valid == other.valid && ip == other.ip && port == other.port;
}

StunClient::StunClient() = default;

StunClient::~StunClient() {
  Close();
}

bool StunClient::Bind(uint16_t local_port, std::string* error) {
  Close();
  fd_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (fd_ < 0) {
    if (error != nullptr) {
      *error = "socket_failed";
    }
    NLOG_ERROR(kTag, "stun_socket_failed errno=%d", errno);
    return false;
  }
  struct timeval timeout;
  timeout.tv_sec = kRecvSliceMs / 1000;
  timeout.tv_usec = (kRecvSliceMs % 1000) * 1000;
  (void)setsockopt(fd_, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

  struct sockaddr_in local;
  memset(&local, 0, sizeof(local));
  local.sin_family = AF_INET;
  local.sin_addr.s_addr = htonl(INADDR_ANY);
  local.sin_port = htons(local_port);
  if (bind(fd_, reinterpret_cast<struct sockaddr*>(&local), sizeof(local)) !=
      0) {
    if (error != nullptr) {
      *error = "bind_failed";
    }
    NLOG_ERROR(kTag, "stun_bind_failed port=%u errno=%d",
               static_cast<unsigned>(local_port), errno);
    Close();
    return false;
  }

  struct sockaddr_in actual;
  socklen_t actual_length = sizeof(actual);
  memset(&actual, 0, sizeof(actual));
  if (getsockname(fd_, reinterpret_cast<struct sockaddr*>(&actual),
                  &actual_length) == 0) {
    local_port_ = ntohs(actual.sin_port);
    local_address_.ip = IpFromSockaddrInAddr(actual.sin_addr);
    local_address_.port = local_port_;
    local_address_.valid = local_address_.ip != "-";
  }
  NLOG_INFO(kTag, "stun_socket_ready local=%s requested_port=%u",
            local_address_.ToString().c_str(),
            static_cast<unsigned>(local_port));
  return true;
}

bool StunClient::DiscoverLocalAddress(const std::string& server_host,
                                      uint16_t server_port) {
  if (fd_ < 0 || local_address_.port == 0) {
    return false;
  }
  struct sockaddr_in server;
  memset(&server, 0, sizeof(server));
  std::string error;
  if (!Resolve(server_host, server_port, &server, &error)) {
    return false;
  }
  const int probe_fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (probe_fd < 0) {
    return false;
  }
  bool ok = false;
  // connect() 一个 UDP socket 不会真正发包，只让内核选好路由与出口地址。
  if (connect(probe_fd, reinterpret_cast<struct sockaddr*>(&server),
              sizeof(server)) == 0) {
    struct sockaddr_in local;
    socklen_t local_length = sizeof(local);
    memset(&local, 0, sizeof(local));
    if (getsockname(probe_fd, reinterpret_cast<struct sockaddr*>(&local),
                    &local_length) == 0) {
      const std::string ip = IpFromSockaddrInAddr(local.sin_addr);
      if (!ip.empty() && ip != "-" && ip != "0.0.0.0") {
        local_address_.ip = ip;
        local_address_.valid = true;
        ok = true;
      }
    }
  }
  close(probe_fd);
  NLOG_INFO(kTag, "stun_local_address ip=%s port=%u source=route_probe ok=%d",
            local_address_.ip.c_str(),
            static_cast<unsigned>(local_address_.port), ok ? 1 : 0);
  return ok;
}

void StunClient::Close() {
  if (fd_ >= 0) {
    close(fd_);
    fd_ = -1;
  }
  local_port_ = 0;
  local_address_ = StunAddress();
}

bool StunClient::Resolve(const std::string& host, uint16_t port,
                         struct sockaddr_in* out, std::string* error) const {
  if (out == nullptr) {
    return false;
  }
  struct addrinfo hints;
  memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_DGRAM;
  hints.ai_protocol = IPPROTO_UDP;
  struct addrinfo* result = nullptr;
  const std::string port_text = std::to_string(port);
  const int status =
      getaddrinfo(host.c_str(), port_text.c_str(), &hints, &result);
  if (status != 0 || result == nullptr) {
    if (error != nullptr) {
      *error = std::string("resolve_failed:") + gai_strerror(status);
    }
    NLOG_ERROR(kTag, "stun_resolve_failed host=%s port=%u status=%d",
               host.c_str(), static_cast<unsigned>(port), status);
    return false;
  }
  memcpy(out, result->ai_addr, sizeof(struct sockaddr_in));
  freeaddrinfo(result);
  return true;
}

StunBindingResult StunClient::SendBinding(const std::string& server_host,
                                          uint16_t server_port, bool change_ip,
                                          bool change_port, int timeout_ms) {
  StunBindingResult result;
  if (fd_ < 0) {
    result.error = "not_bound";
    return result;
  }
  struct sockaddr_in server;
  memset(&server, 0, sizeof(server));
  if (!Resolve(server_host, server_port, &server, &result.error)) {
    return result;
  }

  // ---- 组装 Binding Request ------------------------------------------------
  uint8_t request[kHeaderSize + 8] = {0};
  uint8_t transaction_id[12] = {0};
  FillTransactionId(transaction_id);
  size_t length = 0;
  WriteUint16(request, kBindingRequest);
  WriteUint32(request + 4, kMagicCookie);
  memcpy(request + 8, transaction_id, sizeof(transaction_id));
  length = kHeaderSize;
  if (change_ip || change_port) {
    uint16_t flags = 0;
    if (change_ip) {
      flags |= kChangeIpFlag;
    }
    if (change_port) {
      flags |= kChangePortFlag;
    }
    WriteUint16(request + length, kAttrChangeRequest);
    WriteUint16(request + length + 2, 4);
    WriteUint32(request + length + 4, flags);
    length += 8;  // 属性总长 4 + 4（value 无填充）
  }
  WriteUint16(request + 2, static_cast<uint16_t>(length - kHeaderSize));

  NLOG_DEBUG(kTag,
             "stun_request target=%s:%u change_ip=%d change_port=%d len=%zu",
             server_host.c_str(), static_cast<unsigned>(server_port),
             change_ip ? 1 : 0, change_port ? 1 : 0, length);
  const int64_t send_ms = NowMillis();
  const ssize_t sent =
      sendto(fd_, request, length, 0,
             reinterpret_cast<struct sockaddr*>(&server), sizeof(server));
  if (sent != static_cast<ssize_t>(length)) {
    result.error = "sendto_failed";
    NLOG_ERROR(kTag, "stun_sendto_failed errno=%d", errno);
    return result;
  }

  // ---- 等待响应（100 ms 切片轮询，便于取消）--------------------------------
  const int64_t deadline_ms = send_ms + (timeout_ms > 0 ? timeout_ms : 500);
  uint8_t buffer[1024];
  while (NowMillis() < deadline_ms) {
    if (cancel_flag_ != nullptr && cancel_flag_->load()) {
      result.error = "cancelled";
      return result;
    }
    struct sockaddr_in from;
    socklen_t from_length = sizeof(from);
    memset(&from, 0, sizeof(from));
    const ssize_t received =
        recvfrom(fd_, buffer, sizeof(buffer), 0,
                 reinterpret_cast<struct sockaddr*>(&from), &from_length);
    if (received < 0) {
      if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
        continue;  // 超时切片，继续等待
      }
      result.error = "recvfrom_failed";
      NLOG_ERROR(kTag, "stun_recvfrom_failed errno=%d", errno);
      return result;
    }
    if (received < static_cast<ssize_t>(kHeaderSize)) {
      continue;
    }
    if (ReadUint32(buffer + 4) != kMagicCookie) {
      continue;  // 非 STUN 报文
    }
    const uint16_t message_type = ReadUint16(buffer);
    if (message_type != kBindingSuccessResponse &&
        message_type != kBindingErrorResponse) {
      continue;
    }
    if (memcmp(buffer + 8, transaction_id, sizeof(transaction_id)) != 0) {
      continue;  // 不是本次事务的响应
    }

    result.received = true;
    result.rtt_ms = NowMillis() - send_ms;
    result.source.ip = IpFromSockaddrInAddr(from.sin_addr);
    result.source.port = ntohs(from.sin_port);
    result.source.valid = result.source.ip != "-";
    if (message_type == kBindingErrorResponse) {
      result.error = "binding_error_response";
      NLOG_WARN(kTag, "stun_response_error source=%s rtt_ms=%lld",
                result.source.ToString().c_str(),
                static_cast<long long>(result.rtt_ms));
      return result;
    }

    // ---- 解析属性 -----------------------------------------------------------
    const size_t body_length = ReadUint16(buffer + 2);
    size_t offset = kHeaderSize;
    const size_t limit = std::min<size_t>(static_cast<size_t>(received),
                                          kHeaderSize + body_length);
    while (offset + 4 <= limit) {
      const uint16_t attr_type = ReadUint16(buffer + offset);
      const uint16_t attr_length = ReadUint16(buffer + offset + 2);
      const uint8_t* value = buffer + offset + 4;
      if (offset + 4 + attr_length > limit) {
        break;  // 报文被截断
      }
      if (attr_type == kAttrXorMappedAddress) {
        ParseXorMappedAddress(value, attr_length, transaction_id,
                              &result.mapped);
      } else if (attr_type == kAttrMappedAddress) {
        ParsePlainAddress(value, attr_length, &result.mapped);
      } else if (attr_type == kAttrResponseOrigin) {
        ParsePlainAddress(value, attr_length, &result.response_origin);
      } else if (attr_type == kAttrOtherAddress) {
        ParsePlainAddress(value, attr_length, &result.other_address);
      }
      // 属性值按 4 字节对齐填充
      offset += 4 + ((static_cast<size_t>(attr_length) + 3u) & ~3u);
    }
    NLOG_DEBUG(kTag,
               "stun_response source=%s mapped=%s origin=%s other=%s "
               "rtt_ms=%lld",
               result.source.ToString().c_str(),
               result.mapped.ToString().c_str(),
               result.response_origin.ToString().c_str(),
               result.other_address.ToString().c_str(),
               static_cast<long long>(result.rtt_ms));
    return result;
  }

  result.timed_out = true;
  return result;
}

}  // namespace webrtcdemo
