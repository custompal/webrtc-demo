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
// 这里维护一个**存活句柄注册表**。
//
// 【t48 真机故障后的加固】注册表由「裸指针 + 全程持锁」改为
// 「shared_ptr + 只在查表时持锁」：
//   旧实现把 g_registry_mutex 一直握到 encoder->Encode() 返回，于是
//   ① 一次慢编码会阻塞所有其它 JNI 调用（包括挂断时的 nativeRelease）；
//   ② 真机上首帧 nativeEncode 之后再无任何 nativeRelease 日志 —— 与
//      “释放路径被编码线程堵住”完全一致（reports/18-encoder-stall.md §3）。
//   新实现：查表拿到 shared_ptr 后立即解锁，调用在锁外执行；nativeRelease
//   只把句柄从注册表摘掉，对象在最后一个引用（可能正在 Encode 的那个）释放后
//   才析构 ⇒ 既不会 use-after-free，也不会互相阻塞。
// ============================================================================
#include <jni.h>

#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <new>

#include "encoder/vp9_encoder.h"
#include "jni/jni_bridge.h"
#include "log/log_macros.h"
#include "util/jni_util.h"

namespace webrtcdemo {
namespace {

constexpr char kTag[] = "jni";
constexpr int kMaxDimension = 4096;  // 契约 §6.7：宽高上限

// 存活编码器注册表：handle（对象地址）→ 对象所有权。
std::mutex g_registry_mutex;
std::map<intptr_t, std::shared_ptr<Vp9Encoder>> g_encoders;

// 查表取一次强引用；未注册/已释放返回 nullptr。锁只覆盖查表本身。
std::shared_ptr<Vp9Encoder> AcquireEncoder(jlong handle) {
  if (handle == 0) {
    return nullptr;
  }
  const intptr_t key = static_cast<intptr_t>(handle);
  std::lock_guard<std::mutex> lock(g_registry_mutex);
  auto it = g_encoders.find(key);
  if (it == g_encoders.end()) {
    return nullptr;
  }
  return it->second;
}

// 执行一次操作：句柄无效 → UNINITIALIZED；调用期间不持有注册表锁。
template <typename Fn>
jint WithEncoder(jlong handle, Fn&& fn) {
  std::shared_ptr<Vp9Encoder> encoder = AcquireEncoder(handle);
  if (encoder == nullptr) {
    return kVp9Uninitialized;
  }
  return static_cast<jint>(fn(encoder.get()));
}

// nativeCreate：分配编码器对象，返回句柄（>0）；失败返回 0。
jlong NativeCreate(JNIEnv*, jclass) {
  // 本工程 -fno-exceptions：用 nothrow 分配 + 空判断，避免直接 new 抛异常。
  std::shared_ptr<Vp9Encoder> encoder(new (std::nothrow) Vp9Encoder());
  if (encoder == nullptr) {
    NLOG_ERROR(kTag, "nativeCreate_failed reason=oom");
    return 0;
  }
  const intptr_t key = reinterpret_cast<intptr_t>(encoder.get());
  {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    g_encoders[key] = std::move(encoder);
  }
  NLOG_DEBUG(kTag, "jni_call method=nativeCreate handle=%lld impl=%s",
             static_cast<long long>(key), kVp9ImplName);
  return static_cast<jlong>(key);
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
  return WithEncoder(handle, [&](Vp9Encoder* encoder) {
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
  return WithEncoder(handle, [&](Vp9Encoder* encoder) {
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
    std::shared_ptr<Vp9Encoder> encoder = AcquireEncoder(handle);
    if (encoder == nullptr) {
      return -1;
    }
    copied = encoder->CopyEncodedFrame(data, static_cast<int32_t>(capacity),
                                       meta);
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
  return WithEncoder(handle, [](Vp9Encoder* encoder) {
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
  // 【t48】维度校验改用 **SDK 上限**（5×4，见 layer_bitrate_allocator.h）：
  // 真机实测 SDK 每次下发 s=5 t=4，旧代码按本项目内部上限 3×3 校验，导致
  // **每一次** SetRates 都被拒（nativeSetRates_rejected reason=bad_dim），
  // GCC 的目标码率永远到不了 libvpx，并且 VideoEncoderWrapper::HandleReturnCode
  // 会在编码线程上做 Release()+InitEncode() 复位（真机日志可见 nativeRelease →
  // nativeCreate → nativeInit 紧贴首帧）。契约 §6.3 的“长度 = S*T”校验保留。
  if (num_spatial_layers < 1 ||
      num_spatial_layers > kSdkMaxSpatialLayers ||
      num_temporal_layers < 1 ||
      num_temporal_layers > kSdkMaxTemporalStreams) {
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
  // 接收缓冲按 **SDK 上限** 开（5×4 = 20）：若沿用内部的 3×3 = 9，
  // GetIntArrayRegion(length=20) 会越界写 11 个 int（栈破坏）。
  jint values[kSdkMaxSpatialLayers * kSdkMaxTemporalStreams] = {0};
  env->GetIntArrayRegion(layer_bitrates, 0, length, values);
  // 折叠到本项目内部维度（越界安全的纯函数，见 layer_bitrate_allocator.h）。
  LayerBitrate rates;
  if (!FoldSdkLayerMatrix(values, num_spatial_layers, num_temporal_layers,
                          total_bitrate_bps, framerate_fps, &rates)) {
    NLOG_ERROR(kTag, "nativeSetRates_rejected reason=fold_failed s=%d t=%d",
               static_cast<int>(num_spatial_layers),
               static_cast<int>(num_temporal_layers));
    return kVp9ErrParameter;
  }
  // 学习核心日志：收到的 SDK 矩阵 → 折叠后的内部矩阵（配对报告 §9.4）。
  NLOG_INFO(kTag,
            "nativeSetRates_fold in_s=%d in_t=%d len=%d out_s=%d out_t=%d "
            "total_bps=%d",
            static_cast<int>(num_spatial_layers),
            static_cast<int>(num_temporal_layers), static_cast<int>(length),
            rates.num_spatial, rates.num_temporal,
            static_cast<int>(total_bitrate_bps));
  NLOG_INFO(kTag, "nativeSetRates_matrix s0t0=%d s0t1=%d s0t2=%d s0t3=%d",
            static_cast<int>(values[0]),
            static_cast<int>(values[1 < length ? 1 : 0]),
            static_cast<int>(values[2 < length ? 2 : 0]),
            static_cast<int>(values[3 < length ? 3 : 0]));
  return WithEncoder(
      handle, [&](Vp9Encoder* encoder) { return encoder->SetRates(rates); });
}

// nativeRequestKeyFrame：下一帧强制关键帧。
jint NativeRequestKeyFrame(JNIEnv*, jclass, jlong handle) {
  return WithEncoder(
      handle, [](Vp9Encoder* encoder) { return encoder->RequestKeyFrame(); });
}

// nativeRelease：释放句柄；**幂等**（重复调用仍返回 OK，契约 §6.3）。
//
// 【t48】只做“从注册表摘除”，不再 delete：对象由 shared_ptr 管理，若此刻编码
// 线程正持有一个引用（正在 Encode），则析构推迟到该调用返回后执行 ⇒ 既不
// use-after-free，也不会让挂断路径被编码线程阻塞（真机首帧后 nativeRelease
// 日志消失的真因，见 reports/18-encoder-stall.md §3）。
jint NativeRelease(JNIEnv*, jclass, jlong handle) {
  if (handle == 0) {
    return kVp9Ok;
  }
  const intptr_t key = static_cast<intptr_t>(handle);
  {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    auto it = g_encoders.find(key);
    if (it == g_encoders.end()) {
      return kVp9Ok;  // 已释放/未知句柄：幂等返回 OK
    }
    g_encoders.erase(it);  // vpx_codec_destroy 在最后一个引用析构时完成
  }
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
