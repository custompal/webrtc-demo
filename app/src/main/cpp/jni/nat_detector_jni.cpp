// ============================================================================
// jni/nat_detector_jni.cpp —— 表 A-3：NativeNatDetector 的 2 个 native 方法
//                             （契约 §6.4）
// ----------------------------------------------------------------------------
//   nativeDetect (Ljava/lang/String;IJ)V   (stunHost, stunPort, timeoutMs)
//   nativeCancel ()V
// 语义：nativeDetect **立即返回**，探测在自有线程里跑，结果经表 B-1 的
// onNatTypeDetected 回调；重复调用先取消并 join 上一次；nativeCancel 幂等。
// ============================================================================
#include <jni.h>

#include <string>

#include "jni/jni_bridge.h"
#include "log/log_macros.h"
#include "nat/nat_detector.h"
#include "util/jni_util.h"

namespace webrtcdemo {
namespace {

constexpr char kTag[] = "jni";

// nativeDetect：启动后台探测（不阻塞调用线程）。
void NativeDetect(JNIEnv* env, jclass, jstring stun_host, jint stun_port,
                  jlong timeout_ms) {
  const std::string host = JStringToUtf8(env, stun_host);
  NLOG_INFO(kTag,
            "jni_call method=nativeDetect host=%s port=%d timeout_ms=%lld",
            host.c_str(), static_cast<int>(stun_port),
            static_cast<long long>(timeout_ms));
  if (host.empty() || stun_port <= 0 || stun_port > 65535) {
    NLOG_ERROR(kTag, "nativeDetect_rejected reason=bad_args host=%s port=%d",
               host.c_str(), static_cast<int>(stun_port));
    return;
  }
  GetNatDetector().DetectAsync(host, static_cast<int>(stun_port),
                               static_cast<int64_t>(timeout_ms));
}

// nativeCancel：取消并 join 探测线程（幂等）。
void NativeCancel(JNIEnv*, jclass) {
  NLOG_INFO(kTag, "jni_call method=nativeCancel");
  GetNatDetector().Cancel();
}

// clang-format off
// 方法表逐字照抄契约 §6.4（V28 按每行一条方法表项计数）。
const JNINativeMethod kNatDetectorMethods[] = {
    {"nativeDetect", "(Ljava/lang/String;IJ)V",
     reinterpret_cast<void*>(NativeDetect)},
    {"nativeCancel", "()V", reinterpret_cast<void*>(NativeCancel)},
};
// clang-format on

}  // namespace

bool RegisterNatDetectorMethods(JNIEnv* env) {
  if (env == nullptr || GetClassRefs().native_nat_detector == nullptr) {
    return false;
  }
  const int count = static_cast<int>(sizeof(kNatDetectorMethods) /
                                     sizeof(kNatDetectorMethods[0]));
  if (env->RegisterNatives(GetClassRefs().native_nat_detector,
                           kNatDetectorMethods, count) < 0) {
    NLOG_ERROR(kTag, "register_natives_failed class=NativeNatDetector count=%d",
               count);
    ClearPendingJavaException(env, "RegisterNatives(NativeNatDetector)");
    return false;
  }
  return true;
}

}  // namespace webrtcdemo
