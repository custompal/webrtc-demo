// 主机侧最小 jni.h stub：仅用于在无 Android 设备时编译 NAT 模块做逻辑验证。
// 只声明 NAT 相关 TU 需要的类型（非交付物）。
#pragma once

#include <cstdint>

typedef int32_t jint;
typedef int64_t jlong;
typedef int8_t jboolean;
typedef void* jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jarray;
typedef jobject jintArray;
typedef jobject jbyteArray;

struct JNIEnv {};
struct JavaVM {
  jint GetEnv(void** env, jint version);
  jint AttachCurrentThread(JNIEnv** env, void* args);
  jint DetachCurrentThread();
};

struct JNINativeMethod {
  const char* name;
  const char* signature;
  void* fnPtr;
};

#define JNI_VERSION_1_6 0x00010006
#define JNI_OK 0
#define JNI_ERR (-1)
#define JNI_EDETACHED (-2)
#define JNI_FALSE 0
#define JNI_TRUE 1
