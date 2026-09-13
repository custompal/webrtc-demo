// ============================================================================
// jni/native_log_jni.cpp —— 表 A-1：NativeLog 的 4 个 native 方法（契约 §6.2）
// ----------------------------------------------------------------------------
// 方法表（名称 + 签名**逐字**与契约一致，禁止改名）：
//   nativeInit     (Ljava/lang/String;Ljava/lang/String;IJI)V
//                  (logDir, fileNameBase, level, maxBytesPerFile, maxFiles)
//   nativeSetLevel (I)V
//   nativeFlush    ()V
//   nativeShutdown ()V
// 这些方法是 C++ 文件日志的**唯一配置入口**（契约 §9.4：一次性传目录+基名+参数，
// C++ 自管 open/write/rename，禁止逐行走 JNI）。
// ============================================================================
#include <jni.h>

#include <string>

#include "jni/callback_bridge.h"
#include "jni/jni_bridge.h"
#include "log/log_macros.h"
#include "log/native_log.h"
#include "util/jni_util.h"

namespace webrtcdemo {
namespace {

constexpr char kTag[] = "jni";

// nativeInit：初始化 native 文件日志（幂等；失败只写 logcat，不抛异常）。
// 调用时机：WebRtcDemoApp.onCreate 中**先于**任何 NativeVp9Encoder /
// NativeNatDetector 调用（契约 §9.4）。
void NativeLogInit(JNIEnv* env, jclass, jstring log_dir, jstring file_name_base,
                   jint level, jlong max_bytes_per_file, jint max_files) {
  const std::string dir = JStringToUtf8(env, log_dir);
  const std::string base = JStringToUtf8(env, file_name_base);
  NLOG_DEBUG(kTag,
             "jni_call method=nativeInit dir=%s base=%s level=%d "
             "max_bytes=%lld max_files=%d",
             dir.c_str(), base.c_str(), static_cast<int>(level),
             static_cast<long long>(max_bytes_per_file),
             static_cast<int>(max_files));
  const bool ok = NativeLogger::Instance().Init(
      dir, base, static_cast<int>(level),
      static_cast<int64_t>(max_bytes_per_file), static_cast<int>(max_files));
  if (!ok) {
    // Kotlin 侧据此在 app.log 里留一条线索（低频事件，允许走表 B-1）。
    NotifyLogEvent(kLogError, "main", "native_log_init_failed");
  }
}

// nativeSetLevel：运行时切换级别（0..5，越界钳制；线程安全）。
void NativeLogSetLevel(JNIEnv*, jclass, jint level) {
  NativeLogger::Instance().SetLevel(static_cast<int>(level));
  NLOG_INFO(kTag, "jni_call method=nativeSetLevel level=%d",
            static_cast<int>(level));
}

// nativeFlush：fflush + fsync（导出日志前必调，契约 §9.5）。
void NativeLogFlush(JNIEnv*, jclass) {
  NativeLogger::Instance().Flush();
  NLOG_DEBUG(kTag, "jni_call method=nativeFlush");
}

// nativeShutdown：关闭文件；之后 native 日志只落 logcat。
void NativeLogShutdown(JNIEnv*, jclass) {
  NLOG_INFO(kTag, "jni_call method=nativeShutdown");
  NativeLogger::Instance().Shutdown();
}

// clang-format off
// 方法表逐字照抄契约 §6.2（V28 按每行一条方法表项计数）。
const JNINativeMethod kNativeLogMethods[] = {
    {"nativeInit", "(Ljava/lang/String;Ljava/lang/String;IJI)V",
     reinterpret_cast<void*>(NativeLogInit)},
    {"nativeSetLevel", "(I)V", reinterpret_cast<void*>(NativeLogSetLevel)},
    {"nativeFlush", "()V", reinterpret_cast<void*>(NativeLogFlush)},
    {"nativeShutdown", "()V", reinterpret_cast<void*>(NativeLogShutdown)},
};
// clang-format on

}  // namespace

bool RegisterNativeLogMethods(JNIEnv* env) {
  if (env == nullptr || GetClassRefs().native_log == nullptr) {
    return false;
  }
  const int count = static_cast<int>(sizeof(kNativeLogMethods) /
                                     sizeof(kNativeLogMethods[0]));
  if (env->RegisterNatives(GetClassRefs().native_log, kNativeLogMethods,
                           count) < 0) {
    NLOG_ERROR(kTag, "register_natives_failed class=NativeLog count=%d", count);
    ClearPendingJavaException(env, "RegisterNatives(NativeLog)");
    return false;
  }
  return true;
}

}  // namespace webrtcdemo
