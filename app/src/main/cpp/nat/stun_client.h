// ============================================================================
// nat/stun_client.h —— 极简 STUN 客户端（RFC 5389 + RFC 5780 行为测试用）
// ----------------------------------------------------------------------------
// 为什么自研：ADR-005 / 契约 §6.4 要求展示“真实 NAT 类型”，而 libwebrtc 只暴露
// candidate 类型（host/srflx/relay）。本文件实现 RFC 5780 所需的三个动作：
//   1) Binding Request → 读 XOR-MAPPED-ADDRESS（映射地址）；
//   2) CHANGE-REQUEST（change IP / change port）→ 观察是否收到响应；
//   3) 解析 RESPONSE-ORIGIN / OTHER-ADDRESS（备用地址，用于 RFC5780 判定）。
// 只依赖 BSD socket，不经 PeerConnection，学习点就是 STUN 报文本身。
// ============================================================================
#pragma once

#include <atomic>
#include <cstdint>
#include <string>

#include <netinet/in.h>

namespace webrtcdemo {

// STUN 地址（IPv4）。
struct StunAddress {
  std::string ip;
  uint16_t port = 0;
  bool valid = false;

  // 形如 "1.2.3.4:54321"；无效时返回 "-"。
  std::string ToString() const;
  bool operator==(const StunAddress& other) const;
};

// 一次 Binding 事务的结果。
struct StunBindingResult {
  bool received = false;   // 是否在超时前收到成功响应
  bool timed_out = false;  // 是否超时（无响应）
  StunAddress mapped;      // XOR-MAPPED-ADDRESS（NAT 后的公网映射）
  StunAddress response_origin;  // RESPONSE-ORIGIN 属性（响应来自哪个地址）
  StunAddress other_address;  // OTHER-ADDRESS 属性（服务器备用地址）
  StunAddress source;         // 实际收到响应的 UDP 源地址
  int64_t rtt_ms = -1;        // 往返时延
  std::string error;          // 非空表示本地错误（socket/解析）
};

class StunClient {
 public:
  StunClient();
  ~StunClient();

  StunClient(const StunClient&) = delete;
  StunClient& operator=(const StunClient&) = delete;

  // 创建并绑定 UDP socket。
  // 参数：local_port = 0 表示随机端口；**NAT 映射行为测试必须全程使用同一
  //       本地端口**，否则 mapped 端口变化无法区分是 NAT 还是端口改变所致。
  // 返回值：成功 true；失败 false 并写 error（+ WARN 日志）。
  bool Bind(uint16_t local_port, std::string* error);

  // 关闭 socket（幂等）。
  void Close();

  bool bound() const {
    return fd_ >= 0;
  }
  uint16_t local_port() const {
    return local_port_;
  }

  // 本端地址（getsockname 结果，用于判定 Open：mapped == local）。
  const StunAddress& local_address() const {
    return local_address_;
  }

  // 探测“到 STUN 服务器的本端出口 IP”。
  // 为什么需要：Bind() 绑定 INADDR_ANY 时 getsockname 返回 0.0.0.0，
  // 无法与映射地址比较，Open（无 NAT）判定会失效。这里用临时 UDP socket
  // connect() 到服务器后再 getsockname()，拿到真实出口 IP 补全本端地址
  // （端口仍用真实探测 socket 的绑定端口）。
  // 返回值：本端 IP 是否可用。
  bool DiscoverLocalAddress(const std::string& server_host,
                            uint16_t server_port);

  // 注册取消标志：探测线程被取消时，SendBinding 会在 100 ms 内返回。
  void set_cancel_flag(const std::atomic<bool>* flag) {
    cancel_flag_ = flag;
  }

  // 发送一次 STUN Binding Request 并等待响应。
  // 参数：
  //   server_host/server_port 目标 STUN 服务器
  //   change_ip               置位 CHANGE-REQUEST 的“change IP”位（RFC5780）
  //   change_port             置位 CHANGE-REQUEST 的“change port”位
  //   timeout_ms              等待上限（内部按 100 ms 切片轮询，便于响应取消）
  // 返回值：StunBindingResult（received/timed_out/error 三者互斥）
  StunBindingResult SendBinding(const std::string& server_host,
                                uint16_t server_port, bool change_ip,
                                bool change_port, int timeout_ms);

 private:
  // DNS 解析（仅 IPv4）；失败写 error 并返回 false。
  bool Resolve(const std::string& host, uint16_t port, struct sockaddr_in* out,
               std::string* error) const;

  int fd_ = -1;
  uint16_t local_port_ = 0;
  StunAddress local_address_;
  const std::atomic<bool>* cancel_flag_ = nullptr;
};

}  // namespace webrtcdemo
