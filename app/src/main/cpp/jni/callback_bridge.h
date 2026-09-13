// ============================================================================
// jni/callback_bridge.h —— C++ → Java 回调桥（契约 §6.5 表 B-1，共 2 个方法）
// ----------------------------------------------------------------------------
// 只有两个回调，且都**低频**：
//   onNatTypeDetected(natType, detail)  NAT 探测结束时一次
//   onLogEvent(level, tag, message)     极少量关键事件（encoder_init 等）
// 高频数据（ICE/候选/统计/每帧码率）一律走 Kotlin 侧的 org.webrtc 观察者与
// 本地日志，**不跨 JNI**（契约 §6.5 的"已取消的旧回调"）。
// ============================================================================
#pragma once

#include <jni.h>

#include <string>

namespace webrtcdemo {

// 缓存 NativeCallbacks 的两个静态方法 ID；JNI_OnLoad 内调用，失败返回 false。
bool InitCallbackBridge(JNIEnv* env);

// 表 B-1：报告 NAT 类型与证据串（在 NAT 探测线程内调用；内部负责
// AttachCurrentThread/DetachCurrentThread 与 Java 异常清理）。
void NotifyNatTypeDetected(const std::string& nat_type,
                           const std::string& detail);

// 表 B-1：上报低频事件。level 用契约 §6.6 数值（0..5）。
void NotifyLogEvent(int level, const std::string& tag,
                    const std::string& message);

}  // namespace webrtcdemo
