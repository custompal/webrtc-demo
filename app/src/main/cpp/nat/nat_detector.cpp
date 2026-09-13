// ============================================================================
// nat/nat_detector.cpp —— RFC 5780 四步探测实现
// ----------------------------------------------------------------------------
// 每一步都打日志（契约 D6：“NAT 探测每一步请求与响应”）：
//   nat_start → nat_request(testI..testIV) → nat_done
// 探测全程复用**同一个本地 UDP 端口**（StunClient::Bind(0)），否则映射端口
// 的变化无法区分是 NAT 行为还是本端端口改变。
// ============================================================================
#include "nat/nat_detector.h"

#include <cstdio>

#include "jni/callback_bridge.h"
#include "log/log_macros.h"
#include "nat/stun_client.h"
#include "util/jni_util.h"

namespace webrtcdemo {
namespace {

constexpr char kTag[] = "nat";
constexpr size_t kMaxDetailBytes = 480;  // 契约 §6.5：detail ≤ 512 字节

// 把一次测试结果追加进证据串（如 ";testII=resp"）。
void AppendTestResult(std::string* detail, const char* name,
                      const StunBindingResult& result) {
  if (detail == nullptr) {
    return;
  }
  std::string status;
  if (result.received) {
    status = "resp";
  } else if (result.timed_out) {
    status = "timeout";
  } else {
    status = result.error.empty() ? "error" : result.error;
  }
  detail->append(";");
  detail->append(name == nullptr ? "test" : name);
  detail->append("=");
  detail->append(status);
}

const char* ResultText(const StunBindingResult& result) {
  if (result.received) {
    return "resp";
  }
  if (result.timed_out) {
    return "timeout";
  }
  return result.error.empty() ? "error" : result.error.c_str();
}

}  // namespace

const char* NatTypeToString(NatType type) {
  switch (type) {
    case NatType::kOpen:
      return "Open";
    case NatType::kFullCone:
      return "FullCone";
    case NatType::kRestrictedCone:
      return "RestrictedCone";
    case NatType::kPortRestrictedCone:
      return "PortRestrictedCone";
    case NatType::kSymmetric:
      return "Symmetric";
    case NatType::kUnknown:
    default:
      return "Unknown";
  }
}

NatDetector& GetNatDetector() {
  static NatDetector instance;
  return instance;
}

NatDetector::NatDetector() = default;

NatDetector::~NatDetector() {
  Cancel();
}

bool NatDetector::running() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return running_;
}

void NatDetector::DetectAsync(const std::string& stun_host, int stun_port,
                              int64_t timeout_ms) {
  // 契约 §6.4：重复调用要先取消失败的上一次（幂等）。
  Cancel();
  int clamped_timeout = 200;
  if (timeout_ms > 200) {
    clamped_timeout =
        (timeout_ms > 10000) ? 10000 : static_cast<int>(timeout_ms);
  }
  std::lock_guard<std::mutex> lock(mutex_);
  cancel_.store(false);
  running_ = true;
  // 注意：-fno-exceptions 下 std::thread 构造失败会 terminate（资源耗尽才可能）；
  // 与 libwebrtc 自身的线程用法一致，这里不做额外兜底。
  thread_ = std::thread(&NatDetector::Run, this, stun_host, stun_port,
                        clamped_timeout);
}

void NatDetector::Cancel() {
  cancel_.store(true);
  std::thread to_join;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!thread_.joinable()) {
      running_ = false;
      return;
    }
    to_join = std::move(thread_);
  }
  to_join.join();
  cancel_.store(false);
  std::lock_guard<std::mutex> lock(mutex_);
  running_ = false;
}

void NatDetector::Finish(NatType type, const std::string& detail) {
  const char* type_text = NatTypeToString(type);
  std::string bounded = detail.size() > kMaxDetailBytes
                            ? detail.substr(0, kMaxDetailBytes)
                            : detail;
  NLOG_INFO(kTag, "nat_done type=%s detail=%s", type_text, bounded.c_str());
  // 表 B-1：结果回调（内部负责 Attach/Detach 与异常清理）。
  NotifyNatTypeDetected(type_text, bounded);
  // 表 B-1：低频事件，供 Kotlin 统一日志门面记录一行。
  char message[128];
  snprintf(message, sizeof(message), "nat_done type=%s", type_text);
  NotifyLogEvent(kLogInfo, kTag, message);
}

void NatDetector::Run(std::string host, int port, int timeout_ms) {
  SetCurrentThreadName("nat-detect");
  NLOG_INFO(kTag, "nat_start host=%s port=%d timeout_ms=%d", host.c_str(), port,
            timeout_ms);
  {
    char message[160];
    snprintf(message, sizeof(message), "nat_start host=%s port=%d",
             host.c_str(), port);
    NotifyLogEvent(kLogInfo, kTag, message);
  }

  StunClient client;
  std::string bind_error;
  if (!client.Bind(0, &bind_error)) {
    Finish(NatType::kUnknown, "mapped=-;bind=" + bind_error);
    return;
  }
  client.set_cancel_flag(&cancel_);
  // 补全本端出口 IP：绑定 INADDR_ANY 时 getsockname 只给 0.0.0.0，
  // 没有它就无法判定 Open（mapped == 本端地址）。失败不影响后续测试。
  if (!client.DiscoverLocalAddress(host, static_cast<uint16_t>(port))) {
    NLOG_WARN(kTag, "nat_local_address_unknown open_check_degraded");
  }

  std::string detail;
  // 每个测试步骤都打印请求参数与响应地址（学习点：看得见 STUN 往返）。
  auto run_test = [&](const char* name, bool change_ip, bool change_port) {
    const StunBindingResult result = client.SendBinding(
        host, static_cast<uint16_t>(port), change_ip, change_port, timeout_ms);
    NLOG_INFO(kTag,
              "nat_request name=%s change_ip=%d change_port=%d result=%s "
              "mapped=%s origin=%s other=%s rtt_ms=%lld",
              name, change_ip ? 1 : 0, change_port ? 1 : 0, ResultText(result),
              result.mapped.ToString().c_str(),
              result.response_origin.ToString().c_str(),
              result.other_address.ToString().c_str(),
              static_cast<long long>(result.rtt_ms));
    AppendTestResult(&detail, name, result);
    return result;
  };

  const StunBindingResult test1 = run_test("testI", false, false);
  if (cancel_.load()) {
    Finish(NatType::kUnknown, detail + ";cancelled");
    return;
  }
  if (!test1.received) {
    // 连普通 Binding 都收不到：STUN 不可达 / UDP 被完全阻断。
    Finish(NatType::kUnknown, "mapped=-" + detail);
    return;
  }
  const StunAddress mapped1 = test1.mapped;
  // 证据串以映射地址开头，便于事后排查。
  detail = "mapped=" + mapped1.ToString() + detail;
  if (mapped1 == client.local_address()) {
    // 映射地址与本端地址完全一致 → 在公网或 NAT 是 1:1 映射（无地址转换）。
    Finish(NatType::kOpen, detail);
    return;
  }

  // Test II：change IP + change port（RFC 5780 过滤行为测试）。
  const StunBindingResult test2 = run_test("testII", true, true);
  if (cancel_.load()) {
    Finish(NatType::kUnknown, detail + ";cancelled");
    return;
  }
  if (test2.received) {
    if (!(test2.mapped == mapped1)) {
      // 映射端口随目标变化 → 对称型（RFC 5780 最高优先级结论）。
      Finish(NatType::kSymmetric, detail);
      return;
    }
    Finish(NatType::kFullCone, detail);
    return;
  }

  // Test III：只 change port。
  const StunBindingResult test3 = run_test("testIII", false, true);
  if (cancel_.load()) {
    Finish(NatType::kUnknown, detail + ";cancelled");
    return;
  }
  if (test3.received) {
    if (!(test3.mapped == mapped1)) {
      Finish(NatType::kSymmetric, detail);
      return;
    }
    Finish(NatType::kRestrictedCone, detail);
    return;
  }

  // Test IV：再发一次普通 Binding，确认映射是否稳定。
  const StunBindingResult test4 = run_test("testIV", false, false);
  if (cancel_.load()) {
    Finish(NatType::kUnknown, detail + ";cancelled");
    return;
  }
  if (test4.received && !(test4.mapped == mapped1)) {
    Finish(NatType::kSymmetric, detail);
    return;
  }
  Finish(NatType::kPortRestrictedCone, detail);
}

}  // namespace webrtcdemo
