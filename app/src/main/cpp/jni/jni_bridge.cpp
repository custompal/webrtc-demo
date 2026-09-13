// ============================================================================
// jni/jni_bridge.cpp —— JNI_OnLoad / JNI_OnUnload 与类引用缓存
// ----------------------------------------------------------------------------
// 加载顺序（契约 §9.4）：Java 侧 WebRtcDemoApp.onCreate →
//   System.loadLibrary("webrtcdemo_native") → JNI_OnLoad（本文件）
//   → NativeLog.nativeInit(...)（表 A-1）→ 其它 native 调用。
// 本文件只做“注册与缓存”，不含任何业务逻辑（JNI 薄层原则）。
// ============================================================================
#include "jni/jni_bridge.h"

#include <android/log.h>

#include "jni/callback_bridge.h"
#include "log/log_macros.h"
#include "log/native_log.h"

namespace webrtcdemo {
namespace {

// 契约 §6.1：注册失败必须用 "WebRtcDemo" 这个 tag 打 ERROR。
constexpr char kBridgeLogTag[] = "WebRtcDemo";
constexpr char kJniTag[] = "jni";

JavaVM* g_java_vm = nullptr;
JniClassRefs g_class_refs;

// FindClass + NewGlobalRef；失败打 ERROR 并返回 nullptr。
jclass FindAndCacheClass(JNIEnv* env, const char* class_name) {
  jclass local = env->FindClass(class_name);
  if (local == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kBridgeLogTag,
                        "JNI_OnLoad: FindClass failed: %s", class_name);
    return nullptr;
  }
  jclass global = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  if (global == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kBridgeLogTag,
                        "JNI_OnLoad: NewGlobalRef failed: %s", class_name);
  }
  return global;
}

}  // namespace

JavaVM* GetJavaVm() {
  return g_java_vm;
}

JniClassRefs& GetClassRefs() {
  return g_class_refs;
}

}  // namespace webrtcdemo

// JNI_OnLoad：System.loadLibrary 时由 ART 调用（在调用 loadLibrary 的线程上）。
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
  (void)reserved;
  using namespace webrtcdemo;

  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
      env == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kBridgeLogTag,
                        "JNI_OnLoad: GetEnv failed");
    return JNI_ERR;
  }
  g_java_vm = vm;

  // ---- 缓存 4 个冻结类 ------------------------------------------------------
  JniClassRefs& refs = GetClassRefs();
  refs.native_log = FindAndCacheClass(env, kNativeLogClass);
  refs.native_vp9_encoder = FindAndCacheClass(env, kNativeVp9EncoderClass);
  refs.native_nat_detector = FindAndCacheClass(env, kNativeNatDetectorClass);
  refs.native_callbacks = FindAndCacheClass(env, kNativeCallbacksClass);
  if (refs.native_log == nullptr || refs.native_vp9_encoder == nullptr ||
      refs.native_nat_detector == nullptr || refs.native_callbacks == nullptr) {
    return JNI_ERR;
  }

  // ---- 注册 15 个 native 方法（表 A-1 4 + 表 A-2 9 + 表 A-3 2）-------------
  if (!RegisterNativeLogMethods(env)) {
    __android_log_print(ANDROID_LOG_ERROR, kBridgeLogTag,
                        "registerNativeLogMethods failed");
    return JNI_ERR;
  }
  if (!RegisterVp9EncoderMethods(env)) {
    __android_log_print(ANDROID_LOG_ERROR, kBridgeLogTag,
                        "registerVp9EncoderMethods failed");
    return JNI_ERR;
  }
  if (!RegisterNatDetectorMethods(env)) {
    __android_log_print(ANDROID_LOG_ERROR, kBridgeLogTag,
                        "registerNatDetectorMethods failed");
    return JNI_ERR;
  }
  // ---- 缓存 2 个 C++→Java 回调（表 B-1）------------------------------------
  if (!InitCallbackBridge(env)) {
    __android_log_print(ANDROID_LOG_ERROR, kBridgeLogTag,
                        "initCallbackBridge failed");
    return JNI_ERR;
  }

  // 此时 NativeLog.nativeInit 可能尚未调用：日志只落 logcat（契约 §9.4）。
  NLOG_INFO(kJniTag, "jni_onload methods=15 callbacks=2 version=%d",
            static_cast<int>(JNI_VERSION_1_6));
  return JNI_VERSION_1_6;
}

// JNI_OnUnload：进程退出/卸载库时调用；关闭日志文件即可
// （global ref 按契约 §6.7 在进程生命周期内不释放）。
extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
  (void)vm;
  (void)reserved;
  webrtcdemo::NativeLogger::Instance().Shutdown();
}
