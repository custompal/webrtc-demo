// ============================================================================
// jni/vp9_encoder_jni.cpp —— 表 A-2：NativeVp9Encoder 的 9 个 native 方法
//                            （契约 §6.3，名称 + 签名逐字一致）
// ----------------------------------------------------------------------------
//   nativeCreate               ()J
//   nativeInit                 (JIIIIIII)I
//   nativeEncode               (JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;
//                               Ljava/nio/ByteBuffer;IIIIIJIZ)I
//   nativeCopyEncodedFrame     (JLjava/nio/ByteBuffer;[I)I
//   nativeGetEncodedFrameSize  (J)I
//   nativeSetRates             (J[IIIII)I
//   nativeRequestKeyFrame      (J)I
//   nativeRelease              (J)I
//   nativeGetImplName          ()Ljava/lang/String;
//
// 句柄所有权（契约 §6.7）：nativeCreate 用 new 分配 → nativeRelease 用 delete
// 释放；Kotlin 只能释放一次。为了满足“失效句柄调用返回 UNINITIALIZED 且不崩溃”，
// 这里维护一个**存活句柄注册表**，并且所有操作都在注册表锁内执行 —— 这样
// Release 与 Encode 并发时不会出现 use-after-free。
// ============================================================================
#include <jni.h>

#include <cstdint>
#include <mutex>
#include <new>
#include <set>

#include "encoder/vp9_encoder.h"
#include "jni/jni_bridge.h"
#include "log/log_macros.h"
#include "util/jni_util.h"

namespace webrtcdemo {
namespace {

constexpr char kTag[] = "jni";
constexpr int kMaxDimension = 4096;  // 契约 §6.7：宽高上限

// 存活编码器句柄集合（同一把锁同时保护句柄生命周期与调用互斥）。
std::mutex g_registry_mutex;
std::set<Vp9Encoder*> g_encoders;

Vp9Encoder* HandleToPointer(jlong handle) {
  return reinterpret_cast<Vp9Encoder*>(static_cast<intptr_t>(handle));
}

// 在注册表锁内执行一次操作：句柄无效 → UNINITIALIZED，且不会被并发 Release
// 释放掉正在使用的对象。
template <typename Fn>
jint WithEncoderLocked(jlong handle, Fn&& fn) {
  if (handle == 0) {
    return kVp9Uninitialized;
  }
  Vp9Encoder* encoder = HandleToPointer(handle);
  std::lock_guard<std::mutex> lock(g_registry_mutex);
  if (g_encoders.find(encoder) == g_encoders.end()) {
    return kVp9Uninitialized;
  }
  return static_cast<jint>(fn(encoder));
}

// nativeCreate：分配编码器对象，返回句柄（>0）；失败返回 0。
jlong NativeCreate(JNIEnv*, jclass) {
  Vp9Encoder* encoder = new (std::nothrow) Vp9Encoder();
  if (encoder == nullptr) {
    NLOG_ERROR(kTag, "nativeCreate_failed reason=oom");
    return 0;
  }
  {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    g_encoders.insert(encoder);
  }
  NLOG_DEBUG(kTag, "jni_call method=nativeCreate handle=%lld impl=%s",
             static_cast<long long>(reinterpret_cast<intptr_t>(encoder)),
             kVp9ImplName);
  return static_cast<jlong>(reinterpret_cast<intptr_t>(encoder));
}

// nativeInit：初始化 libvpx；S 必须为 1（契约 §5.6/§6.3）。
jint NativeInit(JNIEnv*, jclass, jlong handle, jint width, jint height,
                jint start_bitrate_bps, jint max_bitrate_bps,
                jint max_framerate, jint num_spatial_layers,
                jint num_temporal_layers) {
  NLOG_DEBUG(kTag,
             "jni_call method=nativeInit handle=%lld w=%d h=%d start_bps=%d "
             "max_bps=%d fps=%d s=%d t=%d",
             static_cast<long long>(handle), static_cast<int>(width),
             static_cast<int>(height), static_cast<int>(start_bitrate_bps),
             static_cast<int>(max_bitrate_bps), static_cast<int>(max_framerate),
             static_cast<int>(num_spatial_layers),
             static_cast<int>(num_temporal_layers));
  if (width <= 0 || height <= 0 || width > kMaxDimension ||
      height > kMaxDimension) {
    NLOG_ERROR(kTag, "nativeInit_rejected reason=bad_size w=%d h=%d",
               static_cast<int>(width), static_cast<int>(height));
    return kVp9ErrParameter;
  }
  return WithEncoderLocked(handle, [&](Vp9Encoder* encoder) {
    EncoderConfig config;
    config.width = width;
    config.height = height;
    config.start_bitrate_bps = start_bitrate_bps;
    config.max_bitrate_bps = max_bitrate_bps;
    config.max_framerate = max_framerate;
    config.num_spatial_layers = num_spatial_layers;
    config.num_temporal_layers = num_temporal_layers;
    return encoder->Init(config);
  });
}

// nativeEncode：逐帧编码。3 个平面必须是 direct ByteBuffer（契约 §6.3）。
jint NativeEncode(JNIEnv* env, jclass, jlong handle, jobject y, jobject u,
                  jobject v, jint width, jint height, jint stride_y,
                  jint stride_u, jint stride_v, jlong capture_time_ns,
                  jint rotation_degrees, jboolean request_key_frame) {
  NLOG_DEBUG(kTag,
             "jni_call method=nativeEncode handle=%lld w=%d h=%d sy=%d "
             "su=%d sv=%d ts_ns=%lld rot=%d key=%d",
             static_cast<long long>(handle), static_cast<int>(width),
             static_cast<int>(height), static_cast<int>(stride_y),
             static_cast<int>(stride_u), static_cast<int>(stride_v),
             static_cast<long long>(capture_time_ns),
             static_cast<int>(rotation_degrees),
             request_key_frame != JNI_FALSE ? 1 : 0);
  if (width <= 0 || height <= 0 || width > kMaxDimension ||
      height > kMaxDimension) {
    NLOG_ERROR(kTag, "nativeEncode_rejected reason=bad_size w=%d h=%d",
               static_cast<int>(width), static_cast<int>(height));
    return kVp9ErrParameter;
  }
  uint8_t* y_bytes = GetDirectBufferBytes(env, y);
  uint8_t* u_bytes = GetDirectBufferBytes(env, u);
  uint8_t* v_bytes = GetDirectBufferBytes(env, v);
  if (y_bytes == nullptr || u_bytes == nullptr || v_bytes == nullptr) {
    NLOG_ERROR(kTag, "nativeEncode_rejected reason=non_direct_buffer");
    return kVp9ErrParameter;
  }
  // 越界读取防护：Kotlin 侧 stride 来自 SDK，必须确认缓冲确实装得下这些平面。
  const int chroma_rows = (height + 1) / 2;
  const int chroma_row_bytes = (width + 1) / 2;
  if (!CheckPlaneCapacity(GetDirectBufferCapacity(env, y), stride_y, height,
                          width) ||
      !CheckPlaneCapacity(GetDirectBufferCapacity(env, u), stride_u,
                          chroma_rows, chroma_row_bytes) ||
      !CheckPlaneCapacity(GetDirectBufferCapacity(env, v), stride_v,
                          chroma_rows, chroma_row_bytes)) {
    NLOG_ERROR(kTag,
               "nativeEncode_rejected reason=plane_capacity w=%d h=%d "
               "sy=%d su=%d sv=%d",
               static_cast<int>(width), static_cast<int>(height),
               static_cast<int>(stride_y), static_cast<int>(stride_u),
               static_cast<int>(stride_v));
    return kVp9ErrParameter;
  }
  return WithEncoderLocked(handle, [&](Vp9Encoder* encoder) {
    I420Frame frame;
    frame.y = y_bytes;
    frame.u = u_bytes;
    frame.v = v_bytes;
    frame.width = width;
    frame.height = height;
    frame.stride_y = stride_y;
    frame.stride_u = stride_u;
    frame.stride_v = stride_v;
    frame.rotation_degrees = rotation_degrees;
    frame.capture_time_ns = capture_time_ns;
    return encoder->Encode(frame, request_key_frame != JNI_FALSE);
  });
}

// nativeCopyEncodedFrame：把最近一帧结果拷进 Kotlin 的 direct buffer。
// 返回值语义与其它方法不同：>0 拷贝字节数 / 0 无待取帧 / -1 出错（契约 §6.3）。
jint NativeCopyEncodedFrame(JNIEnv* env, jclass, jlong handle, jobject dst,
                            jintArray out_meta) {
  if (handle == 0 || dst == nullptr || out_meta == nullptr) {
    return -1;
  }
  if (env->GetArrayLength(out_meta) < 6) {
    NLOG_ERROR(kTag, "nativeCopyEncodedFrame_rejected reason=meta_too_short");
    return -1;
  }
  uint8_t* data = GetDirectBufferBytes(env, dst);
  const int64_t capacity = GetDirectBufferCapacity(env, dst);
  if (data == nullptr || capacity <= 0) {
    NLOG_ERROR(kTag,
               "nativeCopyEncodedFrame_rejected reason=bad_buffer cap=%lld",
               static_cast<long long>(capacity));
    return -1;
  }
  int32_t meta[6] = {0};
  int32_t copied = -1;
  {
    Vp9Encoder* encoder = HandleToPointer(handle);
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    if (g_encoders.find(encoder) == g_encoders.end()) {
      return -1;
    }
    copied =
        encoder->CopyEncodedFrame(data, static_cast<int32_t>(capacity), meta);
  }
  if (copied > 0) {
    env->SetIntArrayRegion(out_meta, 0, 6, reinterpret_cast<const jint*>(meta));
    NLOG_DEBUG(kTag,
               "jni_return method=nativeCopyEncodedFrame bytes=%d w=%d h=%d "
               "key=%d s=%d t=%d qp=%d",
               static_cast<int>(copied), static_cast<int>(meta[0]),
               static_cast<int>(meta[1]), static_cast<int>(meta[2]),
               static_cast<int>(meta[3]), static_cast<int>(meta[4]),
               static_cast<int>(meta[5]));
  }
  return static_cast<jint>(copied);
}

// nativeGetEncodedFrameSize：待取帧字节数（无则 0），Kotlin 据此按需扩容。
jint NativeGetEncodedFrameSize(JNIEnv*, jclass, jlong handle) {
  return WithEncoderLocked(handle, [](Vp9Encoder* encoder) {
    return encoder->GetEncodedFrameSize();
  });
}

// nativeSetRates：动态码率入口（学习核心）。数组长度必须 = S*T，索引 s*T+t。
jint NativeSetRates(JNIEnv* env, jclass, jlong handle, jintArray layer_bitrates,
                    jint num_spatial_layers, jint num_temporal_layers,
                    jint total_bitrate_bps, jint framerate_fps) {
  NLOG_DEBUG(
      kTag,
      "jni_call method=nativeSetRates handle=%lld s=%d t=%d total_bps=%d "
      "fps=%d",
      static_cast<long long>(handle), static_cast<int>(num_spatial_layers),
      static_cast<int>(num_temporal_layers),
      static_cast<int>(total_bitrate_bps), static_cast<int>(framerate_fps));
  if (layer_bitrates == nullptr) {
    return kVp9ErrParameter;
  }
  if (num_spatial_layers < 1 || num_spatial_layers > kMaxSpatialLayers ||
      num_temporal_layers < 1 || num_temporal_layers > kMaxTemporalLayers) {
    NLOG_ERROR(kTag, "nativeSetRates_rejected reason=bad_dim s=%d t=%d",
               static_cast<int>(num_spatial_layers),
               static_cast<int>(num_temporal_layers));
    return kVp9ErrParameter;
  }
  const jsize length = env->GetArrayLength(layer_bitrates);
  // 契约 §6.3：长度必须等于 S*T，否则 ERR_PARAMETER。
  if (length != num_spatial_layers * num_temporal_layers) {
    NLOG_ERROR(kTag,
               "nativeSetRates_rejected reason=length_mismatch length=%d "
               "expected=%d",
               static_cast<int>(length),
               static_cast<int>(num_spatial_layers * num_temporal_layers));
    return kVp9ErrParameter;
  }
  jint values[kMaxSpatialLayers * kMaxTemporalLayers] = {0};
  env->GetIntArrayRegion(layer_bitrates, 0, length, values);
  LayerBitrate rates;
  rates.num_spatial = num_spatial_layers;
  rates.num_temporal = num_temporal_layers;
  rates.total_bps = total_bitrate_bps;
  rates.framerate_fps = framerate_fps;
  for (int s = 0; s < num_spatial_layers; ++s) {
    for (int t = 0; t < num_temporal_layers; ++t) {
      rates.layer_bps[s][t] = values[s * num_temporal_layers + t];
    }
  }
  return WithEncoderLocked(
      handle, [&](Vp9Encoder* encoder) { return encoder->SetRates(rates); });
}

// nativeRequestKeyFrame：下一帧强制关键帧。
jint NativeRequestKeyFrame(JNIEnv*, jclass, jlong handle) {
  return WithEncoderLocked(
      handle, [](Vp9Encoder* encoder) { return encoder->RequestKeyFrame(); });
}

// nativeRelease：释放句柄；**幂等**（重复调用仍返回 OK，契约 §6.3）。
jint NativeRelease(JNIEnv*, jclass, jlong handle) {
  if (handle == 0) {
    return kVp9Ok;
  }
  Vp9Encoder* encoder = HandleToPointer(handle);
  {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    auto it = g_encoders.find(encoder);
    if (it == g_encoders.end()) {
      return kVp9Ok;  // 已释放/未知句柄：幂等返回 OK
    }
    g_encoders.erase(it);
  }
  delete encoder;  // vpx_codec_destroy 在析构里完成
  NLOG_DEBUG(kTag, "jni_call method=nativeRelease handle=%lld",
             static_cast<long long>(handle));
  return kVp9Ok;
}

// nativeGetImplName：固定返回契约冻结的实现名。
jstring NativeGetImplName(JNIEnv* env, jclass) {
  jstring name = env->NewStringUTF(kVp9ImplName);
  if (name == nullptr) {
    ClearPendingJavaException(env, "nativeGetImplName");
  }
  return name;
}

// clang-format off
// 方法表逐字照抄契约 §6.3（含签名）。这里禁用 clang-format，避免长签名被拆成
// 相邻字符串字面量而影响与契约的逐字比对（V28 按每行一条方法表项计数）。
const JNINativeMethod kVp9EncoderMethods[] = {
    {"nativeCreate", "()J", reinterpret_cast<void*>(NativeCreate)},
    {"nativeInit", "(JIIIIIII)I", reinterpret_cast<void*>(NativeInit)},
    {"nativeEncode",
     "(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I",
     reinterpret_cast<void*>(NativeEncode)},
    {"nativeCopyEncodedFrame", "(JLjava/nio/ByteBuffer;[I)I",
     reinterpret_cast<void*>(NativeCopyEncodedFrame)},
    {"nativeGetEncodedFrameSize", "(J)I",
     reinterpret_cast<void*>(NativeGetEncodedFrameSize)},
    {"nativeSetRates", "(J[IIIII)I", reinterpret_cast<void*>(NativeSetRates)},
    {"nativeRequestKeyFrame", "(J)I",
     reinterpret_cast<void*>(NativeRequestKeyFrame)},
    {"nativeRelease", "(J)I", reinterpret_cast<void*>(NativeRelease)},
    {"nativeGetImplName", "()Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetImplName)},
};
// clang-format on

}  // namespace

bool RegisterVp9EncoderMethods(JNIEnv* env) {
  if (env == nullptr || GetClassRefs().native_vp9_encoder == nullptr) {
    return false;
  }
  const int count = static_cast<int>(sizeof(kVp9EncoderMethods) /
                                     sizeof(kVp9EncoderMethods[0]));
  if (env->RegisterNatives(GetClassRefs().native_vp9_encoder,
                           kVp9EncoderMethods, count) < 0) {
    NLOG_ERROR(kTag, "register_natives_failed class=NativeVp9Encoder count=%d",
               count);
    ClearPendingJavaException(env, "RegisterNatives(NativeVp9Encoder)");
    return false;
  }
  return true;
}

}  // namespace webrtcdemo
