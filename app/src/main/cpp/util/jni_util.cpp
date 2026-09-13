// ============================================================================
// util/jni_util.cpp —— JNI 边界工具实现
// ----------------------------------------------------------------------------
// 说明：所有函数都做空指针校验；不使用 C++ 异常（-fno-exceptions），
// 失败路径一律返回空值/错误码并把原因写进 native 日志。
// ============================================================================
#include "util/jni_util.h"

#include <pthread.h>

#include <string>

#include "log/log_macros.h"

namespace webrtcdemo {
namespace {
constexpr char kTag[] = "jni";
}  // namespace

std::string JStringToUtf8(JNIEnv* env, jstring value) {
  if (env == nullptr || value == nullptr) {
    return std::string();
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) {
    ClearPendingJavaException(env, "GetStringUTFChars");
    return std::string();
  }
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

jstring Utf8ToJString(JNIEnv* env, const std::string& value) {
  if (env == nullptr) {
    return nullptr;
  }
  jstring result = env->NewStringUTF(value.c_str());
  if (result == nullptr) {
    ClearPendingJavaException(env, "NewStringUTF");
  }
  return result;
}

uint8_t* GetDirectBufferBytes(JNIEnv* env, jobject buffer) {
  if (env == nullptr || buffer == nullptr) {
    return nullptr;
  }
  void* address = env->GetDirectBufferAddress(buffer);
  return static_cast<uint8_t*>(address);
}

int64_t GetDirectBufferCapacity(JNIEnv* env, jobject buffer) {
  if (env == nullptr || buffer == nullptr) {
    return -1;
  }
  return static_cast<int64_t>(env->GetDirectBufferCapacity(buffer));
}

bool CheckPlaneCapacity(int64_t capacity, int stride, int rows, int row_bytes) {
  if (capacity < 0 || stride <= 0 || rows <= 0 || row_bytes <= 0 ||
      row_bytes > stride) {
    return false;
  }
  const int64_t required =
      static_cast<int64_t>(stride) * static_cast<int64_t>(rows - 1) +
      static_cast<int64_t>(row_bytes);
  return capacity >= required;
}

void SetCurrentThreadName(const char* name) {
  if (name == nullptr) {
    return;
  }
  char truncated[16] = {0};
  for (size_t i = 0; i < sizeof(truncated) - 1 && name[i] != '\0'; ++i) {
    truncated[i] = name[i];
  }
  (void)pthread_setname_np(pthread_self(), truncated);
}

void ClearPendingJavaException(JNIEnv* env, const char* where) {
  if (env == nullptr || !env->ExceptionCheck()) {
    return;
  }
  NLOG_ERROR(kTag, "java_exception where=%s", where == nullptr ? "?" : where);
  env->ExceptionDescribe();
  env->ExceptionClear();
}

}  // namespace webrtcdemo
