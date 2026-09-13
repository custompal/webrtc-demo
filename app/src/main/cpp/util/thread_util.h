// ============================================================================
// util/thread_util.h —— 线程工具（JavaVM attach/detach RAII、线程命名）
// ----------------------------------------------------------------------------
// 为什么需要 ScopedJniEnv：契约 §6.5 规定 NAT 探测线程回调 Java 前必须
// AttachCurrentThread、退出前 DetachCurrentThread；而 libwebrtc 的编码线程
// 本身已 attach（JNI 调进来的线程天然 attached），因此必须区分
// “本来就在 JVM 上” 与 “自己 attach 的” 两种情况：
//   - 本来 attached：析构时**不** detach（否则会把 Java 线程从 JVM 剥离）；
//   - 自己 attach 的：析构时 detach，避免线程泄漏 attached 状态。
// ============================================================================
#pragma once

#include <jni.h>

#include "jni/jni_bridge.h"  // webrtcdemo::GetJavaVm()

namespace webrtcdemo {

// RAII：为当前 native 线程准备可用的 JNIEnv*。
class ScopedJniEnv {
 public:
  ScopedJniEnv();
  ~ScopedJniEnv();

  ScopedJniEnv(const ScopedJniEnv&) = delete;
  ScopedJniEnv& operator=(const ScopedJniEnv&) = delete;

  // 可用的 JNIEnv*；未初始化或 attach 失败时为 nullptr。
  JNIEnv* get() const {
    return env_;
  }
  JNIEnv* operator->() const {
    return env_;
  }
  bool valid() const {
    return env_ != nullptr;
  }

 private:
  JNIEnv* env_ = nullptr;
  bool attached_by_us_ = false;
};

inline ScopedJniEnv::ScopedJniEnv() {
  JavaVM* vm = GetJavaVm();
  if (vm == nullptr) {
    return;
  }
  void* env = nullptr;
  const jint status = vm->GetEnv(&env, JNI_VERSION_1_6);
  if (status == JNI_OK) {
    env_ = reinterpret_cast<JNIEnv*>(env);
    attached_by_us_ = false;
    return;
  }
  if (status == JNI_EDETACHED) {
    JNIEnv* attached = nullptr;
    if (vm->AttachCurrentThread(&attached, nullptr) == JNI_OK) {
      env_ = attached;
      attached_by_us_ = true;
    }
  }
}

inline ScopedJniEnv::~ScopedJniEnv() {
  if (!attached_by_us_) {
    return;
  }
  JavaVM* vm = GetJavaVm();
  if (vm != nullptr) {
    vm->DetachCurrentThread();
  }
}

}  // namespace webrtcdemo
