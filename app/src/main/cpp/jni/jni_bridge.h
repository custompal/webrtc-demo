// ============================================================================
// jni/jni_bridge.h —— JNI 注册与生命周期（契约 §6.1）
// ----------------------------------------------------------------------------
// 职责：
//   1) 声明 4 个 Java 类的**冻结类名**（逐字照抄，禁止改名）；
//   2) 缓存 JavaVM* 与 4 个类的 global ref（RegisterNatives 与回调都要用）；
//   3) 声明三个 JNI 方法表的注册函数（各自实现在 *_jni.cpp）。
//
// 注册方式（ADR-006 / 契约 §6.1）：JNI_OnLoad + RegisterNatives，返回
// JNI_VERSION_1_6；FindClass / RegisterNatives 失败**必须**打 ERROR 并返回
// JNI_ERR，不得静默降级（否则 Java 侧调用 native 方法会直接 UnsatisfiedLinkError）。
// ============================================================================
#pragma once

#include <jni.h>

namespace webrtcdemo {

// 冻结类名（契约 §6.1 / 附录 B）。ProGuard 必须保留，否则 FindClass 失败。
constexpr char kNativeLogClass[] =
    "com/example/webrtcdemo/nativebridge/NativeLog";
constexpr char kNativeVp9EncoderClass[] =
    "com/example/webrtcdemo/nativebridge/NativeVp9Encoder";
constexpr char kNativeNatDetectorClass[] =
    "com/example/webrtcdemo/nativebridge/NativeNatDetector";
constexpr char kNativeCallbacksClass[] =
    "com/example/webrtcdemo/nativebridge/NativeCallbacks";

// 4 个类的 global ref（JNI_OnLoad 内建立，进程生命周期内不释放）。
struct JniClassRefs {
  jclass native_log = nullptr;
  jclass native_vp9_encoder = nullptr;
  jclass native_nat_detector = nullptr;
  jclass native_callbacks = nullptr;
};

// JNI_OnLoad 缓存的 JavaVM*（NAT 线程 attach 时需要）。
JavaVM* GetJavaVm();

// 4 个类的 global ref 集合。
JniClassRefs& GetClassRefs();

// 注册表 A-1（NativeLog，4 个方法）。
bool RegisterNativeLogMethods(JNIEnv* env);

// 注册表 A-2（NativeVp9Encoder，9 个方法）。
bool RegisterVp9EncoderMethods(JNIEnv* env);

// 注册表 A-3（NativeNatDetector，2 个方法）。
bool RegisterNatDetectorMethods(JNIEnv* env);

}  // namespace webrtcdemo
