// ============================================================================
// util/jni_util.h —— JNI 边界工具（字符串转换、direct buffer 校验、异常清理）
// ----------------------------------------------------------------------------
// 职责：把 JNI 侧易错、易重复的样板代码收敛到一处。
// 设计约束（契约 §6.7）：-fno-exceptions，所有失败都返回错误码/空值 + ERROR 日志，
// 绝不抛异常、绝不因非法入参崩溃。
// ============================================================================
#pragma once

#include <jni.h>

#include <cstdint>
#include <string>

namespace webrtcdemo {

// jstring → UTF-8 std::string。
// 参数：value 可为 nullptr（返回空串）。
// 调用时机：所有接收 jstring 的 JNI 入口第一件事。
std::string JStringToUtf8(JNIEnv* env, jstring value);

// UTF-8 std::string → jstring。
// 返回值：失败（OOM/异常）返回 nullptr；调用者负责 DeleteLocalRef。
jstring Utf8ToJString(JNIEnv* env, const std::string& value);

// 取 direct ByteBuffer 的首地址。
// 返回值：非 direct / nullptr / 已异常 → nullptr（契约 §6.3 要求返回 ERR_PARAMETER）。
uint8_t* GetDirectBufferBytes(JNIEnv* env, jobject buffer);

// direct ByteBuffer 容量；非 direct 返回 -1。
int64_t GetDirectBufferCapacity(JNIEnv* env, jobject buffer);

// 校验一个 I420 平面在给定 stride/尺寸下不会越界读取。
// 参数：
//   capacity  该 direct buffer 的容量（GetDirectBufferCapacity）
//   stride    行跨度（字节）
//   rows      行数
//   row_bytes 每行有效字节数（亮度=width，色度=width/2）
// 返回值：capacity >= stride*(rows-1)+row_bytes 时为 true。
// 为什么需要：编码器按 stride 读取平面，Kotlin 侧 stride 来自 SDK，
//   一旦不匹配就会读到 buffer 之外（native 崩溃），这里做最后一道防线。
bool CheckPlaneCapacity(int64_t capacity, int stride, int rows, int row_bytes);

// 把当前线程名称设为 name（截断到 15 字符，pthread 限制）。
void SetCurrentThreadName(const char* name);

// 若存在挂起的 Java 异常则打印并清除（跨语言回调后必须调用，防止
// 把 Java 异常带回 native 造成后续 JNI 调用全部失败）。
void ClearPendingJavaException(JNIEnv* env, const char* where);

}  // namespace webrtcdemo
