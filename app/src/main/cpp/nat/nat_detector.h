// ============================================================================
// nat/nat_detector.h —— RFC 5780 NAT 行为探测（后台线程 + 结果回调）
// ----------------------------------------------------------------------------
// 与 doc/11 §6.2 的经典四步法一致，判定规则（为什么这么判）：
//   Test I   : 普通 Binding → 拿到映射 M1；若 M1 == 本端地址 → Open（无 NAT）
//   Test II  : CHANGE-REQUEST(change IP + change port)
//              收到响应 → 全锥（FullCone）：任何外部主机都能穿过映射；
//              若 M1 与 M2 映射端口不同 → 对称型（Symmetric，最高优先级）
//   Test III : CHANGE-REQUEST(change port only)
//              收到响应 → 地址受限锥（RestrictedCone）
//   Test IV  : 再发一次普通 Binding → 与 M1 比较
//              端口不同 → Symmetric；相同 → 端口受限锥（PortRestrictedCone）
// 判定顺序体现 RFC 5780 的核心：**映射行为（mapping）优先于过滤行为（filtering）**
// ——只要任一测试的映射端口与 M1 不同，就是 Symmetric，后面不必再测。
//
// 线程模型（契约 §6.4/§6.5）：探测在**自有线程**内跑，`nativeDetect` 立即返回；
// 结果经 callback_bridge → Java 静态方法 `NativeCallbacks.onNatTypeDetected`，
// 回调前 AttachCurrentThread、退出前 DetachCurrentThread（由 ScopedJniEnv 负责）。
// ============================================================================
#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

namespace webrtcdemo {

// NAT 类型（契约 §6.6 字符串冻结，与 doc/09 §3.7 枚举一致）。
enum class NatType {
  kUnknown = 0,
  kOpen,
  kFullCone,
  kRestrictedCone,
  kPortRestrictedCone,
  kSymmetric,
};

// 转成契约字符串：Open / FullCone / RestrictedCone / PortRestrictedCone /
// Symmetric / Unknown。
const char* NatTypeToString(NatType type);

class NatDetector {
 public:
  NatDetector();
  ~NatDetector();

  NatDetector(const NatDetector&) = delete;
  NatDetector& operator=(const NatDetector&) = delete;

  // 启动一次探测（**立即返回**，结果通过 NativeCallbacks 回调）。
  // 参数：stun_host/stun_port 目标 STUN（coturn 3478）；timeout_ms 单次请求
  //       超时（钳制到 200..10000 ms）。
  // 调用时机：Kotlin NativeNatDetector.nativeDetect（可重复调用；重复调用会
  //          先取消并 join 上一次探测线程，契约 §6.4）。
  void DetectAsync(const std::string& stun_host, int stun_port,
                   int64_t timeout_ms);

  // 取消并 join 探测线程（幂等，契约 §6.4）。
  void Cancel();

  bool running() const;

 private:
  // 探测线程主体：串行执行 Test I..IV，最后回调结果。
  void Run(std::string host, int port, int timeout_ms);
  // 汇总结果：拼证据串 → 回调 Java → 打 nat_done 日志。
  void Finish(NatType type, const std::string& detail);

  mutable std::mutex mutex_;
  std::thread thread_;
  std::atomic<bool> cancel_{false};
  bool running_ = false;
};

// 进程内单例（JNI 表 A-3 的两个方法是无句柄静态方法，因此用单例承载状态）。
NatDetector& GetNatDetector();

}  // namespace webrtcdemo
