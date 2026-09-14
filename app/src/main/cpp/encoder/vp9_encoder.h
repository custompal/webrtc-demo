// ============================================================================
// encoder/vp9_encoder.h —— 自研 VP9 编码器（封装 libvpx，A1 路线）
// ----------------------------------------------------------------------------
// 定位（契约 §5.1 的受控偏离）：本类**不继承** libwebrtc 的 C++ VideoEncoder
// 接口（避免与官方 Java SDK 的 libc++/absl ABI 依赖），而是实现同形状的自研
// 编码器；由 Kotlin 侧的 org.webrtc.VideoEncoder 适配器逐帧经 JNI 调用：
//
//   VideoStreamEncoder(SDK)
//     → VideoEncoderWrapper::SetRates → Java → Vp9VideoEncoder.setRateAllocation
//       → NativeVp9Encoder.nativeSetRates → Vp9Encoder::SetRates  ★ 动态码率
//     → VideoEncoderWrapper::Encode   → Java → Vp9VideoEncoder.encode
//       → NativeVp9Encoder.nativeEncode  → Vp9Encoder::Encode     ★ 逐帧编码
//
// 线程模型（契约 §5.4）：Init/Encode/SetRates 都在 libwebrtc 的**编码线程**
// 上被调用；Release 可能来自其它线程。因此：
//   - 内部用 std::mutex 防御 Release 与 Encode 竞争；
//   - 不做文件/网络 I/O（除了写码率 CSV 与日志，均为本地小写入）；
//   - 不使用 C++ 异常（-fno-exceptions）。
// ============================================================================
#pragma once

#include <cstdint>
#include <mutex>
#include <vector>

// libvpx 公共头（t5 产物 third_party/libvpx/include/vpx/*.h）
#include "vpx/vp8cx.h"
#include "vpx/vpx_codec.h"
#include "vpx/vpx_encoder.h"

#include "encoder/layer_bitrate_allocator.h"

namespace webrtcdemo {

// 编码器实现名（契约 §6.6 冻结）。verifier 用它核对
// outbound-rtp.encoderImplementation == "SelfVp9Libvpx"。
constexpr char kVp9ImplName[] = "SelfVp9Libvpx";

// 状态码冻结（契约 §6.6，数值 = org.webrtc.VideoCodecStatus.getNumber()）。
enum Vp9StatusCode : int32_t {
  kVp9Ok = 0,                  // OK
  kVp9NoOutput = 1,            // NO_OUTPUT：本次调用没有产出帧
  kVp9Error = -1,              // ERROR
  kVp9LevelExceeded = -2,      // LEVEL_EXCEEDED
  kVp9Memory = -3,             // MEMORY
  kVp9ErrParameter = -4,       // ERR_PARAMETER：入参非法
  kVp9ErrSize = -5,            // ERR_SIZE
  kVp9Timeout = -6,            // TIMEOUT
  kVp9Uninitialized = -7,      // UNINITIALIZED：句柄失效或未 Init
  kVp9FallbackSoftware = -13,  // FALLBACK_SOFTWARE：vpx 初始化彻底失败
};

// 编码器初始化参数（对应契约 §5.5 的 EncoderConfig）。
struct EncoderConfig {
  int width = 0;              // 请求宽度（Init 内向上对齐到偶数）
  int height = 0;             // 请求高度
  int start_bitrate_bps = 0;  // 起始码率（bps）
  int max_bitrate_bps = 0;    // 上限码率（bps，0 = 未知）
  int max_framerate = 30;     // 最大帧率
  int num_spatial_layers = 1;  // **本项目只接受 1**（契约 §5.6，L1T3）
  int num_temporal_layers = 3;  // 时序层数（1..3，3 = 默认）
};

// 一帧 I420 输入（指针**仅在 nativeEncode 调用期间有效**，契约 §6.7）。
struct I420Frame {
  const uint8_t* y = nullptr;
  const uint8_t* u = nullptr;
  const uint8_t* v = nullptr;
  int width = 0;
  int height = 0;
  int stride_y = 0;
  int stride_u = 0;
  int stride_v = 0;
  int rotation_degrees = 0;  // 0/90/180/270（非法按 0 处理）；t46：编码器按此角度**旋转像素+交换尺寸**
  int64_t capture_time_ns = 0;
};

// 一帧编码结果元数据（对应契约 §6.3 nativeCopyEncodedFrame 的 outMeta）。
struct EncodedFrameMeta {
  int32_t width = 0;
  int32_t height = 0;
  int32_t is_key_frame = 0;
  int32_t spatial_index = 0;
  int32_t temporal_index = 0;
  int32_t qp = -1;
};

class Vp9Encoder {
 public:
  Vp9Encoder();
  ~Vp9Encoder();

  Vp9Encoder(const Vp9Encoder&) = delete;
  Vp9Encoder& operator=(const Vp9Encoder&) = delete;

  // 初始化 libvpx VP9 编码器（可重复调用以重建实例）。
  // 参数：config 见上；num_spatial_layers 必须为 1，否则返回 ERR_PARAMETER。
  // 返回值：kVp9Ok / kVp9ErrParameter / kVp9FallbackSoftware / kVp9Error。
  // 调用时机：Kotlin Vp9VideoEncoder.initEncode（编码线程）。
  int32_t Init(const EncoderConfig& config);

  // 编码一帧 I420。
  // 参数：
  //   frame             输入平面（**本调用内同步读取并拷贝结果**，不保留指针）
  //   request_key_frame true → 追加 VPX_EFLAG_FORCE_KF（关键帧请求）
  // 返回值：kVp9Ok（已产出帧，可用 CopyEncodedFrame 取回）/
  //         kVp9NoOutput / kVp9Uninitialized / kVp9Error / kVp9ErrParameter。
  // 调用时机：Kotlin Vp9VideoEncoder.encode（编码线程，同步阻塞）。
  int32_t Encode(const I420Frame& frame, bool request_key_frame);

  // 动态码率入口（**核心学习点**）：GCC 分配 → vpx 分层码率 → config_set。
  // 参数：rates 为 JNI 展平的 [spatial][temporal] 矩阵（bps）。
  // 返回值：kVp9Ok / kVp9Uninitialized / kVp9ErrParameter / kVp9Error。
  int32_t SetRates(const LayerBitrate& rates);

  // 下一帧强制关键帧（契约 §6.3 nativeRequestKeyFrame）。
  int32_t RequestKeyFrame();

  // 释放内部 vpx 实例；幂等（重复调用返回 kVp9Ok）。
  int32_t Release();

  // 待取帧字节数；无待取帧返回 0（契约 §6.3 nativeGetEncodedFrameSize）。
  int32_t GetEncodedFrameSize();

  // 拷贝最近一帧编码结果到 dst。
  // 参数：dst 目标缓冲；capacity 其容量；out_meta 长度 ≥6 的 int 数组。
  // 返回值：拷贝字节数（>0）/ 0（无待取帧）/ -1（出错）。
  int32_t CopyEncodedFrame(uint8_t* dst, int32_t capacity, int32_t* out_meta);

  // 编码器实现名（契约 §6.6 冻结："SelfVp9Libvpx"）。
  const char* ImplName() const;

  // 是否已成功初始化。
  bool initialized() const;

 private:
  // 应用契约 §5.5 冻结的 vpx 静态配置（除码率外的所有字段）。
  void ApplyFrozenConfigLocked();
  // 把分层码率写进 cfg_（不改动已开的 codec，由调用者决定 config_set/init）。
  void ApplyLayerRatesLocked(const VpxLayerRates& rates);
  // 关闭底层 codec（调用者持锁）。
  void DestroyCodecLocked();
  // 写一行码率 CSV（SetRates 与 Encode 共用）。
  void WriteBitrateCsvLocked(int32_t encoded_bytes, int key_frame, int qp);

  mutable std::mutex mutex_;
  vpx_codec_ctx_t codec_;
  vpx_codec_enc_cfg_t cfg_;
  bool codec_open_ = false;
  bool initialized_ = false;

  EncoderConfig config_;
  int width_ = 0;   // 当前 codec 宽度（偶数）
  int height_ = 0;  // 当前 codec 高度（偶数）
  int64_t last_pts_us_ = -1;
  bool force_key_frame_ = false;
  bool rotation_warned_ = false;

  // 最近一次 SetRates 的输入与输出（CSV/日志留痕）。
  LayerBitrate last_matrix_;
  VpxLayerRates last_rates_;

  // 最近一帧编码结果（内部拷贝，见契约 §5.5：vpx 缓冲会被复用）。
  std::vector<uint8_t> encoded_;
  EncodedFrameMeta meta_;

  // 【t46】旋转暂存缓冲：rotation=90/180/270 时把输入 I420 旋转到这里再送 vpx。
  //
  // 为什么需要：VP9 码流**不携带 CVO/rotation 元数据**，而 doc/14:518/:635 要求编码侧
  // 处理 rotation（:518 明确"rotation 90/270 时交换"）⇒ 必须把角度**烘进像素**，
  // 否则远端解出的是未旋转画面（仅交换尺寸会得到错乱图像）。
  // 缓冲按"旋转后尺寸"单调增长并复用（避免逐帧分配），`Release()` 时释放。
  std::vector<uint8_t> rotate_buf_;

  int64_t frame_count_ = 0;
  int64_t slow_frame_count_ = 0;
};

}  // namespace webrtcdemo
