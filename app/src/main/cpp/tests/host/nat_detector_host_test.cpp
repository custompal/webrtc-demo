// 主机侧 NAT 探测逻辑验证（非交付物）：
//   用本机假 STUN 服务器驱动 NatDetector 的 RFC5780 四步判定，
//   逐场景断言 NAT 类型字符串（契约 §6.6 枚举）。
// 编译（宿主机 g++）：见 reports/07-native-dev.md 中的命令。
#include <arpa/inet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#include "jni/callback_bridge.h"
#include "nat/nat_detector.h"
#include "log/native_log.h"
#include "nat/stun_client.h"
#include "util/jni_util.h"

namespace webrtcdemo {

std::mutex g_result_mutex;
std::string g_last_type;
std::string g_last_detail;
std::atomic<bool> g_done{false};

// 供 nat_detector.cpp 链接用的桩（真实实现在 jni/callback_bridge.cpp）
void NotifyNatTypeDetected(const std::string& nat_type,
                           const std::string& detail) {
  std::lock_guard<std::mutex> lock(g_result_mutex);
  g_last_type = nat_type;
  g_last_detail = detail;
  g_done = true;
}
void NotifyLogEvent(int level, const std::string& tag,
                    const std::string& message) {
  (void)level;
  (void)tag;
  (void)message;
}
void SetCurrentThreadName(const char* name) {
  (void)name;
}

}  // namespace webrtcdemo

using namespace webrtcdemo;

namespace {

// 假 STUN 服务器配置
struct ServerConfig {
  bool reply_plain = true;                    // 普通 Binding（Test I / IV）
  bool reply_change_ip_port = false;          // Test II（change IP + port）
  bool reply_change_port_only = false;        // Test III（change port）
  uint16_t mapped_port = 40000;               // 默认映射端口
  uint16_t second_plain_mapped_port = 40000;  // Test IV 的映射端口
  uint16_t change_ip_port_mapped_port = 40000;  // Test II 的映射端口
  bool echo_client_port = false;  // true → 映射端口 = 客户端源端口（Open 场景）
  std::atomic<int> plain_count{0};
  std::atomic<int> seen_change_ip{0};
  std::atomic<int> seen_change_port{0};
};

uint16_t ReadU16(const uint8_t* d) {
  return static_cast<uint16_t>((d[0] << 8) | d[1]);
}
uint32_t ReadU32(const uint8_t* d) {
  return (static_cast<uint32_t>(d[0]) << 24) |
         (static_cast<uint32_t>(d[1]) << 16) |
         (static_cast<uint32_t>(d[2]) << 8) | static_cast<uint32_t>(d[3]);
}
void WriteU16(uint8_t* d, uint16_t v) {
  d[0] = static_cast<uint8_t>(v >> 8);
  d[1] = static_cast<uint8_t>(v & 0xFF);
}
void WriteU32(uint8_t* d, uint32_t v) {
  d[0] = static_cast<uint8_t>(v >> 24);
  d[1] = static_cast<uint8_t>(v >> 16);
  d[2] = static_cast<uint8_t>(v >> 8);
  d[3] = static_cast<uint8_t>(v);
}

size_t AppendAddressAttr(uint8_t* out, size_t offset, uint16_t type,
                         const struct sockaddr_in& addr, bool xored) {
  WriteU16(out + offset, type);
  WriteU16(out + offset + 2, 8);
  out[offset + 4] = 0;
  out[offset + 5] = 0x01;  // IPv4
  uint16_t port = ntohs(addr.sin_port);
  uint32_t ip = addr.sin_addr.s_addr;  // 网络序
  if (xored) {
    port = static_cast<uint16_t>(port ^ 0x2112);
    ip = ip ^ 0x2112A442u;
  }
  WriteU16(out + offset + 6, port);
  WriteU32(out + offset + 8, ip);
  return offset + 12;
}

// 假 STUN 服务器线程：解析请求（含 CHANGE-REQUEST），按配置决定是否回包。
class FakeStunServer {
 public:
  explicit FakeStunServer(ServerConfig* config) : config_(config) {}
  bool Start() {
    fd_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd_ < 0) {
      return false;
    }
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (bind(fd_, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) !=
        0) {
      return false;
    }
    socklen_t length = sizeof(addr);
    getsockname(fd_, reinterpret_cast<struct sockaddr*>(&addr), &length);
    port_ = ntohs(addr.sin_port);
    thread_ = std::thread([this] { Loop(); });
    return true;
  }
  void Stop() {
    stop_.store(true);
    // 触发一次 recvfrom 返回
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(port_);
    uint8_t dummy[4] = {0};
    sendto(fd_, dummy, sizeof(dummy), 0,
           reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr));
    if (thread_.joinable()) {
      thread_.join();
    }
    if (fd_ >= 0) {
      close(fd_);
      fd_ = -1;
    }
  }
  uint16_t port() const {
    return port_;
  }

 private:
  void Loop() {
    struct timeval timeout;
    timeout.tv_sec = 0;
    timeout.tv_usec = 100000;
    setsockopt(fd_, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    uint8_t buffer[1024];
    while (!stop_.load()) {
      struct sockaddr_in from;
      socklen_t from_length = sizeof(from);
      const ssize_t received =
          recvfrom(fd_, buffer, sizeof(buffer), 0,
                   reinterpret_cast<struct sockaddr*>(&from), &from_length);
      if (received < 20) {
        continue;
      }
      if (ReadU16(buffer) != 0x0001) {
        continue;  // 只处理 Binding Request
      }
      const size_t body_length = ReadU16(buffer + 2);
      size_t offset = 20;
      uint16_t change_flags = 0;
      bool has_change = false;
      while (offset + 4 <= 20 + body_length) {
        const uint16_t type = ReadU16(buffer + offset);
        const uint16_t length = ReadU16(buffer + offset + 2);
        if (type == 0x0003 && length == 4) {
          // CHANGE-REQUEST 是 32 位位掩码：change IP = 0x02、change port = 0x04
          change_flags =
              static_cast<uint16_t>(ReadU32(buffer + offset + 4) & 0xFFFFu);
          has_change = true;
        }
        offset += 4 + ((static_cast<size_t>(length) + 3) & ~3u);
      }
      const bool change_ip = has_change && (change_flags & 0x02) != 0;
      const bool change_port = has_change && (change_flags & 0x04) != 0;
      if (change_ip) {
        config_->seen_change_ip.fetch_add(1);
      }
      if (change_port) {
        config_->seen_change_port.fetch_add(1);
      }

      bool reply = false;
      uint16_t mapped_port = config_->mapped_port;
      if (!has_change) {
        const int index = config_->plain_count.fetch_add(1) + 1;
        reply = config_->reply_plain;
        mapped_port = (index >= 2) ? config_->second_plain_mapped_port
                                   : config_->mapped_port;
      } else if (change_ip && change_port) {
        reply = config_->reply_change_ip_port;
        mapped_port = config_->change_ip_port_mapped_port;
      } else if (change_port) {
        reply = config_->reply_change_port_only;
        mapped_port = config_->mapped_port;
      }
      if (config_->echo_client_port && !has_change) {
        mapped_port = ntohs(from.sin_port);
      }
      if (!reply) {
        continue;  // 不响应：让客户端走超时分支
      }

      uint8_t response[128];
      memset(response, 0, sizeof(response));
      WriteU16(response, 0x0101);
      memcpy(response + 4, buffer + 4, 4);   // magic cookie
      memcpy(response + 8, buffer + 8, 12);  // transaction id
      size_t length = 20;
      struct sockaddr_in mapped;
      memset(&mapped, 0, sizeof(mapped));
      mapped.sin_family = AF_INET;
      mapped.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
      mapped.sin_port = htons(mapped_port);
      length = AppendAddressAttr(response, length, 0x0020, mapped, true);
      length = AppendAddressAttr(response, length, 0x802B, from, false);
      length = AppendAddressAttr(response, length, 0x802C, from, false);
      WriteU16(response + 2, static_cast<uint16_t>(length - 20));
      sendto(fd_, response, length, 0,
             reinterpret_cast<struct sockaddr*>(&from), from_length);
    }
  }

  ServerConfig* config_;
  int fd_ = -1;
  uint16_t port_ = 0;
  std::atomic<bool> stop_{false};
  std::thread thread_;
};

int g_failures = 0;

void Expect(const std::string& expected, const std::string& what) {
  const std::string actual = g_last_type;
  if (actual != expected) {
    ++g_failures;
    printf("FAIL: %s expect=%s actual=%s detail=%s\n", what.c_str(),
           expected.c_str(), actual.c_str(), g_last_detail.c_str());
  } else {
    printf("ok  : %-22s → %-18s detail=%s\n", what.c_str(), actual.c_str(),
           g_last_detail.c_str());
  }
}

// 跑一个场景：启动假服务器 → 触发探测 → 等回调 → 取消。
void RunScenario(const char* name, ServerConfig* config,
                 const std::string& expected, bool with_server = true) {
  FakeStunServer server(config);
  uint16_t port = 0;
  if (with_server) {
    if (!server.Start()) {
      printf("FAIL: cannot start fake server\n");
      ++g_failures;
      return;
    }
    port = server.port();
  } else {
    port = 1;  // 无人监听 → Unknown
  }
  {
    std::lock_guard<std::mutex> lock(g_result_mutex);
    g_last_type.clear();
    g_last_detail.clear();
    g_done = false;
  }
  GetNatDetector().DetectAsync("127.0.0.1", port, 300);
  for (int i = 0; i < 100 && !g_done.load(); ++i) {
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  GetNatDetector().Cancel();
  if (with_server) {
    server.Stop();
  }
  if (!g_done.load()) {
    ++g_failures;
    printf("FAIL: %s no callback\n", name);
    return;
  }
  Expect(expected, name);
}

}  // namespace

int main() {
  NativeLogger::Instance().Init("/tmp/t7natlogs", "native", 0, 65536, 3);
  // 场景 1：映射地址 == 本端地址 → Open
  {
    ServerConfig config;
    config.echo_client_port = true;
    RunScenario("open", &config, "Open");
  }
  // 场景 2：Test II 有响应且映射一致 → FullCone
  {
    ServerConfig config;
    config.reply_change_ip_port = true;
    RunScenario("full_cone", &config, "FullCone");
  }
  // 场景 3：Test II 无响应、Test III 有响应 → RestrictedCone
  {
    ServerConfig config;
    config.reply_change_port_only = true;
    RunScenario("restricted_cone", &config, "RestrictedCone");
  }
  // 场景 4：Test II/III 都无响应、Test IV 映射一致 → PortRestrictedCone
  {
    ServerConfig config;
    RunScenario("port_restricted", &config, "PortRestrictedCone");
  }
  // 场景 5：Test IV 映射端口变化 → Symmetric
  {
    ServerConfig config;
    config.second_plain_mapped_port = 40001;
    RunScenario("symmetric_mapping", &config, "Symmetric");
  }
  // 场景 6：Test II 响应但映射端口变化 → Symmetric
  {
    ServerConfig config;
    config.reply_change_ip_port = true;
    config.change_ip_port_mapped_port = 40002;
    RunScenario("symmetric_on_testII", &config, "Symmetric");
  }
  // 场景 7：STUN 不可达 → Unknown
  {
    ServerConfig config;
    RunScenario("stun_unreachable", &config, "Unknown", false);
  }
  // 校验 CHANGE-REQUEST 位确实发出去了
  {
    ServerConfig config;
    // 只回 Test III：让 Test I、II、III 都发出，检查两种 CHANGE-REQUEST 位
    config.reply_change_port_only = true;
    RunScenario("change_request_bits", &config, "RestrictedCone");
    if (config.seen_change_ip.load() < 1 ||
        config.seen_change_port.load() < 2) {
      ++g_failures;
      printf("FAIL: change-request flags seen ip=%d port=%d\n",
             config.seen_change_ip.load(), config.seen_change_port.load());
    } else {
      printf("ok  : change-request seen ip=%d port=%d\n",
             config.seen_change_ip.load(), config.seen_change_port.load());
    }
  }

  printf("\nRESULT: failures=%d\n", g_failures);
  return g_failures == 0 ? 0 : 1;
}
