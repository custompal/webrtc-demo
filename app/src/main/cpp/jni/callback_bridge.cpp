// ============================================================================
// jni/callback_bridge.cpp —— 调回 NativeCallbacks 的实现
// ----------------------------------------------------------------------------
// 线程模型（契约 §6.5）：
//   - NAT 探测线程：调用前 AttachCurrentThread、退出前 DetachCurrentThread
//     （由 ScopedJniEnv 判定“本来就是 Java 线程”还是“自己 attach 的”）；
//   - 编码线程：本来就 attached（JNI 调进来的），ScopedJniEnv 不会重复 attach。
// 任何一步失败都只打日志、不抛异常、不影响调用方流程（探测/编码不能因为
// 回调失败而中断）。
// ============================================================================
#include "jni/callback_bridge.h"

#include "jni/jni_bridge.h"
#include "log/log_macros.h"
#include "util/jni_util.h"
#include "util/thread_util.h"

namespace webrtcdemo {
namespace {

constexpr char kTag[] = "jni";
constexpr char kOnNatTypeDetected[] = "onNatTypeDetected";
constexpr char kOnLogEvent[] = "onLogEvent";
constexpr char kOnNatTypeDetectedSignature[] =
    "(Ljava/lang/String;Ljava/lang/String;)V";
constexpr char kOnLogEventSignature[] =
    "(ILjava/lang/String;Ljava/lang/String;)V";

jmethodID g_on_nat_type_detected = nullptr;
jmethodID g_on_log_event = nullptr;

}  // namespace

bool InitCallbackBridge(JNIEnv* env) {
  if (env == nullptr) {
    return false;
  }
  jclass callbacks = GetClassRefs().native_callbacks;
  if (callbacks == nullptr) {
    return false;
  }
  g_on_nat_type_detected = env->GetStaticMethodID(callbacks, kOnNatTypeDetected,
                                                  kOnNatTypeDetectedSignature);
  g_on_log_event =
      env->GetStaticMethodID(callbacks, kOnLogEvent, kOnLogEventSignature);
  if (g_on_nat_type_detected == nullptr || g_on_log_event == nullptr) {
    NLOG_ERROR(kTag, "callback_bridge_init_failed nat=%d log=%d",
               g_on_nat_type_detected != nullptr ? 1 : 0,
               g_on_log_event != nullptr ? 1 : 0);
    ClearPendingJavaException(env, "GetStaticMethodID");
    return false;
  }
  return true;
}

void NotifyNatTypeDetected(const std::string& nat_type,
                           const std::string& detail) {
  ScopedJniEnv scoped_env;
  if (!scoped_env.valid() || g_on_nat_type_detected == nullptr) {
    return;
  }
  JNIEnv* env = scoped_env.get();
  jstring java_type = Utf8ToJString(env, nat_type);
  jstring java_detail = Utf8ToJString(env, detail);
  if (java_type == nullptr || java_detail == nullptr) {
    if (java_type != nullptr) {
      env->DeleteLocalRef(java_type);
    }
    if (java_detail != nullptr) {
      env->DeleteLocalRef(java_detail);
    }
    return;
  }
  env->CallStaticVoidMethod(GetClassRefs().native_callbacks,
                            g_on_nat_type_detected, java_type, java_detail);
  ClearPendingJavaException(env, "onNatTypeDetected");
  env->DeleteLocalRef(java_type);
  env->DeleteLocalRef(java_detail);
}

void NotifyLogEvent(int level, const std::string& tag,
                    const std::string& message) {
  ScopedJniEnv scoped_env;
  if (!scoped_env.valid() || g_on_log_event == nullptr) {
    return;
  }
  JNIEnv* env = scoped_env.get();
  jstring java_tag = Utf8ToJString(env, tag);
  jstring java_message = Utf8ToJString(env, message);
  if (java_tag == nullptr || java_message == nullptr) {
    if (java_tag != nullptr) {
      env->DeleteLocalRef(java_tag);
    }
    if (java_message != nullptr) {
      env->DeleteLocalRef(java_message);
    }
    return;
  }
  env->CallStaticVoidMethod(GetClassRefs().native_callbacks, g_on_log_event,
                            static_cast<jint>(level), java_tag, java_message);
  ClearPendingJavaException(env, "onLogEvent");
  env->DeleteLocalRef(java_tag);
  env->DeleteLocalRef(java_message);
}

}  // namespace webrtcdemo
