// ============================================================================
// encoder/vp9_encoder.cpp —— 自研 VP9 编码器实现（libvpx 封装）
// ----------------------------------------------------------------------------
// 本文件是“动态码率闭环”的末端执行者，两条主线：
//   1) SetRates：GCC 分配的分层码率 → layer_bitrate_allocator 换算 →
//      vpx_codec_enc_config_set()（**学习核心**，每层收到的与写回的值都打日志）
//   2) Encode：VideoFrame(I420) → vpx_codec_encode() → 内部拷贝暂存 →
//      Kotlin 侧 nativeCopyEncodedFrame 取回（契约 §5.2 调用链）
//
// 关于 libvpx 分层码率的“真实生效范围”（源码核实结论，写入报告）：
//   - cfg->layer_target_bitrate[] 只在**空间 SVC**（ss_number_layers > 1）时被
//     使用：见 vp9/encoder/vp9_svc_layercontext.c:131/206/249/277；
//   - ss=1 + ts>1（本项目 L1T3）时，时序层的码率预算由
//     temporal_layering_mode（ts=3 → MODE_0212）+ rc_target_bitrate 决定，
//     ts_target_bitrate[] 会被 libvpx 接收但不参与内部预算。
//   本项目按契约 §5.6 仍然完整写入 ss/ts/layer_target_bitrate（学习点：
//   亲手算累计关系），并把“写回值”打进日志与 encoder_bitrate.csv；
//   同时显式设置 temporal_layering_mode（与 libvpx 默认自动选择一致），
//   保证时序分层真实生效。差异已在 reports/07-native-dev.md 登记。
// ============================================================================
#include "encoder/vp9_encoder.h"

#include <time.h>

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <vector>

#include <sys/auxv.h>

#include "encoder/i420_rotator.h"
#include "encoder/libvpx_cpu_guard.h"
#include "jni/callback_bridge.h"
#include "log/log_macros.h"

namespace webrtcdemo {
namespace {

constexpr char kTagEncoder[] = "encoder";
constexpr char kTagBitrate[] = "bitrate";

// 契约 §5.5 冻结值
constexpr int kCpuUsed = 8;  // VP8E_SET_CPUUSED（移动端实时软编）
constexpr int kKeyFrameMaxDist = 3000;  // 关键帧上限 ~3 s @30fps
constexpr int kMinQuantizer = 4;
constexpr int kMaxQuantizer = 56;
constexpr int kRcBufferMs = 600;
constexpr int kRcBufferInitialMs = 400;
constexpr int kRcBufferOptimalMs = 500;
constexpr int kMaxDimension = 4096;  // 契约 §6.7 尺寸上限
// 【t46】旋转暂存缓冲上限（防御性）：4096x4096 的 I420 = 25 MB；超过即视为尺寸异常。
constexpr size_t kMaxRotateBufferBytes = 64u * 1024u * 1024u;
constexpr int32_t kDefaultStartBps = 300 * 1000;
// 【t57 旋转方向决定】是否把 frame.rotation_degrees 烘进像素（t46 路径）。
//   真机证据（dh-a/dh-b）：对 rot=270 施加「顺时针 270°」后用户看到**对端逆时针 90°**
//   ⇒ 送达编码器的 I420 **已经是显示方向**（SDK 的纹理→I420 转换已把方向烘进像素），
//   再烘一次等于多转 270°(=逆时针 90°)。故默认**直通**：不旋转、不交换宽高，
//   EncodedImage 仍 setRotation(0)（像素已正立，无需 CVO）。
//   若后续真机复测发现对端变成「侧躺/竖躺」，把本常量改回 true 即恢复 t46
//   「顺时针 rotation 度 + 90/270 尺寸交换」——rotator 的方向已由角点单测证明正确
//   （encoder/i420_rotator_corners_host_test.cpp）。
constexpr bool kBakeRotationInEncoder = false;

constexpr int64_t kSlowFrameThresholdUs =
    33 * 1000;  // >33 ms 记 WARN（契约 §5.4）

int AlignEvenUp(int value) {
  return (value % 2 == 0) ? value : value + 1;
}

int AlignEvenDown(int value) {
  return (value % 2 == 0) ? value : value - 1;
}

// 【t56 起：仅诊断，不再拒绝】真机 SIGILL 的根因是**交付 libvpx 用了
// `--disable-runtime-cpu-detect`**（t55 定位，见 encoder/libvpx_cpu_guard.h）。
// t56 已把交付件重建为**开启运行时 CPU 探测**：rtcd 在运行期按 HWCAP 派发，
// 缺 SVE/SVE2/dotprod/i8mm 时自动走 C/NEON 路径 ⇒ 「编译期假定不满足即 SIGILL」
// 的风险已消除。故此处保留 CPU 能力探测**仅供诊断**（一条可 grep 的证据），
// **不再据此拒绝自研编码器**（t55 的 encoder_cpu_incompatible 回退逻辑随之解除）。
bool DeliveredLibvpxCpuOk() {
  static const bool kProbed = [] {
    const unsigned long hwcap = getauxval(AT_HWCAP);
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
    const LibvpxCpuStatus status = CheckLibvpxCpu(hwcap, hwcap2);
    NLOG_INFO(kTagEncoder,
              "encoder_cpu_probe status=%s hwcap=%lu hwcap2=%lu "
              "libvpx=runtime_cpu_detect_on",
              LibvpxCpuStatusName(status), hwcap, hwcap2);
    return true;
  }();
  (void)kProbed;
  return true;
}

int64_t NowMicros() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<int64_t>(ts.tv_sec) * 1000000 +
         static_cast<int64_t>(ts.tv_nsec) / 1000;
}

// 依据时序层数给出 libvpx 的时序分层模式（与 vp9_cx_iface.c:703-708 的自动
// 选择一致：ts=2 → 0101，ts=3 → 0212，ts=1 → 无分层）。显式设置可让内部
// 码率预算划分与「累计 40%/70%/100%」的项目策略对齐。
int TemporalLayeringModeFor(int temporal_layers) {
  if (temporal_layers >= 3) {
    return VP9E_TEMPORAL_LAYERING_MODE_0212;
  }
  if (temporal_layers == 2) {
    return VP9E_TEMPORAL_LAYERING_MODE_0101;
  }
  return VP9E_TEMPORAL_LAYERING_MODE_NOLAYERING;
}

// 由帧序号推算该帧所属的时序层。
// 为什么自己算：本版本 libvpx 的 vpx_codec_cx_pkt_t.data.frame 里**没有**
// spatial_layer_id / temporal_layer_id 字段（只有 spatial_layer_encoded[]），
// 而我们是让 libvpx 自动做时序分层，因此按分层模式的固定帧序回推：
//   MODE_0212（3 层）帧序 = 0,2,1,2 循环；MODE_0101（2 层）= 0,1 循环。
// 该值只用于 outMeta[4] 与日志/CSV；Kotlin 的 EncodedImage 无层索引字段
// （契约 §5.6 已说明），因此不影响对端解码。
int TemporalIndexForFrame(int64_t frame_index, int temporal_layers) {
  if (temporal_layers >= 3) {
    static const int kPattern[4] = {0, 2, 1, 2};
    return kPattern[static_cast<int>(frame_index % 4)];
  }
  if (temporal_layers == 2) {
    return static_cast<int>(frame_index % 2);
  }
  return 0;
}

}  // namespace

Vp9Encoder::Vp9Encoder() {
  memset(&codec_, 0, sizeof(codec_));
  memset(&cfg_, 0, sizeof(cfg_));
}

Vp9Encoder::~Vp9Encoder() {
  std::lock_guard<std::mutex> lock(mutex_);
  DestroyCodecLocked();
}

namespace {
// 【t50b】把一帧平面逐行拷进 libvpx 自持图像（行宽 = 平面宽度，目标 stride 由 libvpx 决定）。
void CopyPlaneIntoImage(const uint8_t* src, int src_stride, uint8_t* dst,
                        int dst_stride, int width, int height) {
  if (src == nullptr || dst == nullptr || width <= 0 || height <= 0) {
    return;
  }
  if (src_stride == width && dst_stride == width) {
    std::memcpy(dst, src, static_cast<size_t>(width) * static_cast<size_t>(height));
    return;
  }
  for (int row = 0; row < height; ++row) {
    std::memcpy(dst + static_cast<size_t>(row) * static_cast<size_t>(dst_stride),
                src + static_cast<size_t>(row) * static_cast<size_t>(src_stride),
                static_cast<size_t>(width));
  }
}
}  // namespace

void Vp9Encoder::DestroyCodecLocked() {
  if (codec_open_) {
    vpx_codec_destroy(&codec_);
    memset(&codec_, 0, sizeof(codec_));
    codec_open_ = false;
  }
  // 【t50b】自持输入图像随 vpx 状态一起归还（尺寸变化/Release 时都会走到这里）。
  if (raw_img_ != nullptr) {
    vpx_img_free(raw_img_);
    raw_img_ = nullptr;
    raw_w_ = 0;
    raw_h_ = 0;
  }
  initialized_ = false;
}

bool Vp9Encoder::EnsureRawImageLocked() {
  if (raw_img_ != nullptr && raw_w_ == width_ && raw_h_ == height_) {
    return true;
  }
  if (raw_img_ != nullptr) {
    vpx_img_free(raw_img_);
    raw_img_ = nullptr;
    raw_w_ = 0;
    raw_h_ = 0;
  }
  raw_img_ = vpx_img_wrap(nullptr, VPX_IMG_FMT_I420,
                          static_cast<unsigned int>(width_),
                          static_cast<unsigned int>(height_), 1, nullptr);
  if (raw_img_ == nullptr) {
    NLOG_ERROR(kTagEncoder, "encode_raw_img_alloc_failed w=%d h=%d", width_,
               height_);
    return false;
  }
  raw_w_ = width_;
  raw_h_ = height_;
  NLOG_INFO(kTagEncoder,
            "encoder_raw_img_alloc w=%d h=%d sy=%d su=%d sv=%d owner=%d",
            width_, height_, raw_img_->stride[VPX_PLANE_Y],
            raw_img_->stride[VPX_PLANE_U], raw_img_->stride[VPX_PLANE_V],
            static_cast<int>(raw_img_->img_data_owner));
  return true;
}


const char* Vp9Encoder::ImplName() const {
  return kVp9ImplName;
}

bool Vp9Encoder::initialized() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return initialized_;
}

void Vp9Encoder::ApplyFrozenConfigLocked() {
  cfg_.g_w = static_cast<unsigned int>(width_);
  cfg_.g_h = static_cast<unsigned int>(height_);
  // 时间基 = 1/1000000（微秒）：Encode 传 capture_time_ns/1000 作为 pts，
  // duration 传 1000000/fps，二者单位自洽（libvpx 用 pts 差值推断帧率）。
  cfg_.g_timebase.num = 1;
  cfg_.g_timebase.den = 1000000;
  cfg_.g_lag_in_frames = 0;  // 零延迟（实时通话）
  cfg_.g_threads = 1;  // 契约 §5.5：不额外起线程，帧在编码线程内串行
  cfg_.g_profile = 0;             // VP9 profile 0（I420 8bit）
  cfg_.g_pass = VPX_RC_ONE_PASS;  // 单通；也是允许动态改分辨率的前提
  cfg_.g_error_resilient = VPX_ERROR_RESILIENT_DEFAULT;

  cfg_.rc_end_usage = VPX_CBR;  // 实时通话用 CBR（GCC 已在上层控制总量）
  cfg_.rc_min_quantizer = kMinQuantizer;
  cfg_.rc_max_quantizer = kMaxQuantizer;
  cfg_.rc_undershoot_pct = 50;
  cfg_.rc_overshoot_pct = 50;
  cfg_.rc_buf_sz = kRcBufferMs;
  cfg_.rc_buf_initial_sz = kRcBufferInitialMs;
  cfg_.rc_buf_optimal_sz = kRcBufferOptimalMs;
  cfg_.rc_dropframe_thresh = 0;  // 丢帧交给 GCC，编码器不主动丢
  cfg_.rc_resize_allowed = 0;    // 分辨率由 SDK 的 VideoAdapter 控制

  cfg_.kf_mode = VPX_KF_AUTO;
  cfg_.kf_min_dist = 0;  // 自动模式下 libvpx 不支持非 0 的 min_dist
  cfg_.kf_max_dist = kKeyFrameMaxDist;

  // **契约 §5.6 冻结**：官方 Java 编码器路径无法协商 SVC 空间层
  // （VideoEncoderWrapper 使用 ScalableVideoControllerNoLayering，
  //  VP9 的 num_spatial_layers 被硬编码为 1），因此这里恒为 1。
  // 把空间层改成 >1 会让 SDP 与实际打包不一致，导致对端解码异常。
  cfg_.ss_number_layers = 1;
  cfg_.ts_number_layers =
      static_cast<unsigned int>(config_.num_temporal_layers);
  cfg_.temporal_layering_mode =
      static_cast<int>(TemporalLayeringModeFor(config_.num_temporal_layers));
  for (int t = 0; t < kMaxTemporalLayers; ++t) {
    cfg_.ts_target_bitrate[t] = 0;
    cfg_.ts_rate_decimator[t] = 1;
  }
  for (int i = 0; i < kMaxSpatialLayers * kMaxTemporalLayers; ++i) {
    cfg_.layer_target_bitrate[i] = 0;
  }
  cfg_.ss_target_bitrate[0] = 0;
}

void Vp9Encoder::ApplyLayerRatesLocked(const VpxLayerRates& rates) {
  // libvpx 索引约定（vp9_cx_iface.c:719）：layer = sl * ts_number_layers + tl
  const int temporal = rates.configured_temporal;
  cfg_.ss_number_layers = static_cast<unsigned int>(rates.configured_spatial);
  cfg_.ts_number_layers = static_cast<unsigned int>(temporal);
  cfg_.ss_target_bitrate[0] =
      static_cast<unsigned int>(rates.ss_target_bitrate_kbps[0]);
  for (int t = 0; t < kMaxTemporalLayers; ++t) {
    cfg_.ts_target_bitrate[t] =
        static_cast<unsigned int>(rates.ts_target_bitrate_kbps[t]);
    cfg_.ts_rate_decimator[t] = rates.ts_rate_decimator[t];
  }
  for (int s = 0; s < rates.configured_spatial; ++s) {
    for (int t = 0; t < temporal; ++t) {
      const int index = s * temporal + t;
      cfg_.layer_target_bitrate[index] =
          static_cast<unsigned int>(rates.layer_target_bitrate_kbps[index]);
    }
  }
  // 总目标码率：单位 kbps（rc_target_bitrate 是 libvpx 唯一的“总闸门”）。
  cfg_.rc_target_bitrate =
      static_cast<unsigned int>(rates.rc_target_bitrate_kbps);
}

int32_t Vp9Encoder::Init(const EncoderConfig& config) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (config.width <= 0 || config.height <= 0 || config.width > kMaxDimension ||
      config.height > kMaxDimension) {
    NLOG_ERROR(kTagEncoder, "encoder_init_bad_size w=%d h=%d", config.width,
               config.height);
    return kVp9ErrParameter;
  }
  if (config.num_spatial_layers != 1) {
    // 契约 §5.6：空间 SVC 在官方 Java 编码器路径下无法协商，明确拒绝。
    NLOG_ERROR(kTagEncoder,
               "encoder_init_rejected reason=svc_spatial_unsupported s=%d",
               config.num_spatial_layers);
    return kVp9ErrParameter;
  }
  if (config.num_temporal_layers < 1 ||
      config.num_temporal_layers > kMaxTemporalLayers) {
    NLOG_ERROR(kTagEncoder, "encoder_init_rejected reason=bad_t t=%d",
               config.num_temporal_layers);
    return kVp9ErrParameter;
  }

  EncoderConfig applied = config;
  if (applied.max_framerate <= 0) {
    applied.max_framerate = 30;
  }
  if (applied.start_bitrate_bps <= 0) {
    applied.start_bitrate_bps = kDefaultStartBps;
  }

  // 【t55】交付 libvpx 是“编译期绑定 SIMD”的（--disable-runtime-cpu-detect）：
  //   真机 CPU 若缺 SVE/SVE2/dotprod/i8mm，首帧 vpx_codec_encode 内的 SVE 指令
  //   会 SIGILL 直接杀进程（日志停在 encode_vpx_begin）。这里宁可**拒绝启用**并
  //   回退默认编码器，也不要闪退：返回 FALLBACK_SOFTWARE，Wrapper 会走软件回退。
  if (!DeliveredLibvpxCpuOk()) {
    return kVp9FallbackSoftware;
  }

  DestroyCodecLocked();
  config_ = applied;
  width_ = AlignEvenUp(applied.width);
  height_ = AlignEvenUp(applied.height);

  if (vpx_codec_enc_config_default(vpx_codec_vp9_cx(), &cfg_, 0) !=
      VPX_CODEC_OK) {
    NLOG_ERROR(kTagEncoder, "encoder_init_failed stage=config_default");
    return kVp9Error;
  }
  ApplyFrozenConfigLocked();

  // 初始分层码率：把 start_bitrate 按项目策略切成 T 份（与 SetRates 同一算法）。
  LayerBitrate initial;
  initial.num_spatial = 1;
  initial.num_temporal = applied.num_temporal_layers;
  initial.layer_bps[0][0] = applied.start_bitrate_bps;
  initial.total_bps = applied.start_bitrate_bps;
  initial.framerate_fps = applied.max_framerate;
  last_matrix_ = initial;
  last_rates_ =
      LayerBitrateAllocator::Compute(initial, 1, applied.num_temporal_layers);
  ApplyLayerRatesLocked(last_rates_);

  const vpx_codec_err_t init_result =
      vpx_codec_enc_init(&codec_, vpx_codec_vp9_cx(), &cfg_, 0);
  if (init_result != VPX_CODEC_OK) {
    NLOG_ERROR(kTagEncoder, "encoder_init_failed stage=enc_init err=%s",
               vpx_codec_error(&codec_));
    NotifyLogEvent(kLogError, kTagEncoder, "encoder_fallback reason=vpx_init");
    initialized_ = false;
    codec_open_ = false;
    return kVp9FallbackSoftware;
  }
  codec_open_ = true;
  vpx_codec_control(&codec_, VP8E_SET_CPUUSED, kCpuUsed);

  last_pts_us_ = -1;
  force_key_frame_ = true;  // 首帧必须是关键帧，否则对端无法起播
  frame_count_ = 0;
  slow_frame_count_ = 0;
  encoded_.clear();
  meta_ = EncodedFrameMeta();
  initialized_ = true;

  NLOG_INFO(kTagEncoder,
            "encoder_init w=%d h=%d s=%d t=%d cpu=%d start_bps=%d max_bps=%d",
            width_, height_, last_rates_.configured_spatial,
            last_rates_.configured_temporal, kCpuUsed,
            applied.start_bitrate_bps, applied.max_bitrate_bps);
  NLOG_INFO(kTagBitrate, "ts_target_kbps l0=%d l1=%d l2=%d rc_target_kbps=%d",
            last_rates_.ts_target_bitrate_kbps[0],
            last_rates_.ts_target_bitrate_kbps[1],
            last_rates_.ts_target_bitrate_kbps[2],
            last_rates_.rc_target_bitrate_kbps);
  // 低频事件回调（契约 §6.5）：禁止逐帧调用。
  NotifyLogEvent(kLogInfo, kTagEncoder,
                 "encoder_libvpx_init impl=SelfVp9Libvpx");
  WriteBitrateCsvLocked(0, 0, -1);
  return kVp9Ok;
}

int32_t Vp9Encoder::SetRates(const LayerBitrate& rates) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_ || !codec_open_) {
    NLOG_WARN(kTagBitrate, "setrates_rejected reason=uninitialized");
    return kVp9Uninitialized;
  }
  if (rates.num_spatial < 1 || rates.num_spatial > kMaxSpatialLayers ||
      rates.num_temporal < 1 || rates.num_temporal > kMaxTemporalLayers) {
    NLOG_ERROR(kTagBitrate, "setrates_rejected reason=bad_dim s=%d t=%d",
               rates.num_spatial, rates.num_temporal);
    return kVp9ErrParameter;
  }

  // ---- 学习核心日志 ①：收到的原始 [spatial][temporal] 矩阵 -----------------
  NLOG_INFO(kTagBitrate, "setrates total_bps=%d fps=%d s=%d t=%d",
            rates.total_bps, rates.framerate_fps, rates.num_spatial,
            rates.num_temporal);
  NLOG_INFO(kTagBitrate,
            "layer_bps s0t0=%d s0t1=%d s0t2=%d s1t0=%d s1t1=%d s1t2=%d "
            "s2t0=%d s2t1=%d s2t2=%d",
            rates.layer_bps[0][0], rates.layer_bps[0][1], rates.layer_bps[0][2],
            rates.layer_bps[1][0], rates.layer_bps[1][1], rates.layer_bps[1][2],
            rates.layer_bps[2][0], rates.layer_bps[2][1],
            rates.layer_bps[2][2]);

  // ---- 换算：矩阵 → vpx 分层码率（核心学习点，见 layer_bitrate_allocator.cpp）
  const VpxLayerRates computed = LayerBitrateAllocator::Compute(
      rates, last_rates_.configured_spatial, last_rates_.configured_temporal);
  last_matrix_ = rates;
  last_rates_ = computed;
  if (rates.framerate_fps > 0) {
    config_.max_framerate = rates.framerate_fps;
  }
  // 记录 matrix 的 s/t（用于 CSV 的 s,t 列：描述编码器实际分层）
  last_matrix_.num_spatial = computed.configured_spatial;
  last_matrix_.num_temporal = computed.configured_temporal;

  // ---- 写回 vpx 并下发 -----------------------------------------------------
  ApplyLayerRatesLocked(computed);
  if (vpx_codec_enc_config_set(&codec_, &cfg_) != VPX_CODEC_OK) {
    NLOG_ERROR(kTagBitrate, "setrates_config_set_failed err=%s rc_kbps=%u",
               vpx_codec_error(&codec_), cfg_.rc_target_bitrate);
  }

  // ---- 学习核心日志 ②：写回 vpx 的值 --------------------------------------
  NLOG_INFO(
      kTagBitrate, "ts_target_kbps l0=%d l1=%d l2=%d rc_target_kbps=%d",
      computed.ts_target_bitrate_kbps[0], computed.ts_target_bitrate_kbps[1],
      computed.ts_target_bitrate_kbps[2], computed.rc_target_bitrate_kbps);
  NLOG_INFO(kTagBitrate,
            "vpx_apply ss0=%u layer0=%u layer1=%u layer2=%u decimator=%u,%u,%u",
            cfg_.ss_target_bitrate[0], cfg_.layer_target_bitrate[0],
            cfg_.layer_target_bitrate[1], cfg_.layer_target_bitrate[2],
            cfg_.ts_rate_decimator[0], cfg_.ts_rate_decimator[1],
            cfg_.ts_rate_decimator[2]);

  WriteBitrateCsvLocked(0, 0, -1);
  return kVp9Ok;
}

int32_t Vp9Encoder::RequestKeyFrame() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_ || !codec_open_) {
    return kVp9Uninitialized;
  }
  force_key_frame_ = true;
  return kVp9Ok;
}

int32_t Vp9Encoder::GetEncodedFrameSize() {
  std::lock_guard<std::mutex> lock(mutex_);
  return static_cast<int32_t>(encoded_.size());
}

int32_t Vp9Encoder::CopyEncodedFrame(uint8_t* dst, int32_t capacity,
                                     int32_t* out_meta) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (dst == nullptr || out_meta == nullptr || capacity < 0) {
    return -1;
  }
  if (encoded_.empty()) {
    return 0;  // 无待取帧（契约 §6.3）
  }
  if (capacity < static_cast<int32_t>(encoded_.size())) {
    NLOG_ERROR(kTagEncoder,
               "copy_encoded_frame_size_mismatch need=%zu capacity=%d",
               encoded_.size(), capacity);
    return -1;
  }
  memcpy(dst, encoded_.data(), encoded_.size());
  out_meta[0] = meta_.width;
  out_meta[1] = meta_.height;
  out_meta[2] = meta_.is_key_frame;
  out_meta[3] = meta_.spatial_index;
  out_meta[4] = meta_.temporal_index;
  out_meta[5] = meta_.qp;
  return static_cast<int32_t>(encoded_.size());
}

int32_t Vp9Encoder::Release() {
  std::lock_guard<std::mutex> lock(mutex_);
  DestroyCodecLocked();
  encoded_.clear();
  // 【t46】释放旋转暂存（挂断/重建编码器时归还内存；下次 Encode 按需重新分配）
  std::vector<uint8_t>().swap(rotate_buf_);
  rotation_warned_ = false;
  return kVp9Ok;  // 幂等
}

int32_t Vp9Encoder::Encode(const I420Frame& frame, bool request_key_frame) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_ || !codec_open_) {
    return kVp9Uninitialized;
  }
  if (frame.y == nullptr || frame.u == nullptr || frame.v == nullptr ||
      frame.width <= 0 || frame.height <= 0 || frame.width > kMaxDimension ||
      frame.height > kMaxDimension || frame.stride_y <= 0 ||
      frame.stride_u <= 0 || frame.stride_v <= 0) {
    NLOG_ERROR(kTagEncoder, "encode_bad_frame w=%d h=%d sy=%d su=%d sv=%d",
               frame.width, frame.height, frame.stride_y, frame.stride_u,
               frame.stride_v);
    return kVp9ErrParameter;
  }
  const int frame_width = AlignEvenDown(frame.width);
  const int frame_height = AlignEvenDown(frame.height);
  if ((frame.width % 2) != 0 || (frame.height % 2) != 0) {
    NLOG_WARN(kTagEncoder, "encode_odd_size w=%d h=%d -> %dx%d", frame.width,
              frame.height, frame_width, frame_height);
  }
  if (frame_width <= 0 || frame_height <= 0) {
    return kVp9ErrParameter;
  }
  // 【t46】旋转语义（doc/14:518「rotation 90/270 时交换」+ :635「0|90|180|270；非法按 0」）：
  //   VP9 码流**不携带 CVO/rotation 元数据**，因此编码器必须把角度**烘进像素**：
  //     R=0   ：原样直通（零拷贝，保持既有行为）
  //     R=90  ：顺时针 90°（旋转 + 宽高交换）
  //     R=180 ：顺时针 180°（只旋转，尺寸不变）
  //     R=270 ：顺时针 270°（旋转 + 宽高交换）
  //   只交换 g_w/g_h 而不旋转像素会得到**错乱图像**，故两者必须一起做。
  //   非法值（非 0/90/180/270）按 0 处理并只 WARN 一次（既有行为保持）。
  const int rotation =
      kBakeRotationInEncoder ? NormalizeRotationDegrees(frame.rotation_degrees)
                             : 0;
  if (kBakeRotationInEncoder && frame.rotation_degrees != rotation &&
      !rotation_warned_) {
    rotation_warned_ = true;
    NLOG_WARN(kTagEncoder, "encode_bad_rotation rot=%d treat_as=0",
              frame.rotation_degrees);
  }
  if (!rotation_mode_logged_) {
    rotation_mode_logged_ = true;
    NLOG_INFO(kTagEncoder, "encoder_rotation_mode mode=%s rot=%d",
              kBakeRotationInEncoder ? "bake" : "passthrough",
              frame.rotation_degrees);
  }
  // 旋转后的编码尺寸（90/270 交换；0/180 不变）
  const int target_width = RotatedWidth(frame_width, frame_height, rotation);
  const int target_height = RotatedHeight(frame_width, frame_height, rotation);

  // 分辨率变化（SDK 的 VideoAdapter 会按带宽下调分辨率）+【t46】旋转导致的尺寸交换
  //   ⇒ 必须用**旋转后**尺寸，与 doc/14:518「90/270 时交换」一致。
  // 【t50 修复】尺寸变化时**重建编解码上下文**，不能用 `vpx_codec_enc_config_set`：
  //   真机证据（reports/20-encode-resize-crash.md；宿主 `/opt/dsh-workspaces/tmp/dl-a/x/`）：
  //     16:24:15.691 encoder_resize w=480 h=640      ← config_set 返回 OK（本行即有日志）
  //     16:24:15.698 encode_vpx_begin frame=1 w=480 h=640 … img_owner=0
  //     （随后该进程再无任何日志；16:24:18.556 新进程启动 = App 闪退重启）
  //     计数：encode_vpx_begin=2 / encode_vpx_done=0 / encoded_frame=0
  //   ⇒ `vpx_codec_encode()` 内部崩溃。原因：本 checkout 的 libvpx 在 config_set
  //   路径下**不会随 g_w/g_h 重建内部帧缓冲**，图像(480×640) 与编解码器内部尺寸
  //   (仍是 Init 时的 640×480) 不一致 ⇒ 越界访问。故改为 destroy + 以新尺寸重新
  //   `vpx_codec_enc_init`（cfg_ 已含冻结配置与分层码率，无需重算）。
  if (target_width != width_ || target_height != height_) {
    const int old_w = width_;
    const int old_h = height_;
    width_ = target_width;
    height_ = target_height;
    DestroyCodecLocked();  // 幂等：vpx_codec_destroy + 清句柄 + codec_open_=false
    cfg_.g_w = static_cast<unsigned int>(width_);
    cfg_.g_h = static_cast<unsigned int>(height_);
    const vpx_codec_err_t reinit_result =
        vpx_codec_enc_init(&codec_, vpx_codec_vp9_cx(), &cfg_, 0);
    if (reinit_result != VPX_CODEC_OK) {
      NLOG_ERROR(kTagEncoder, "encode_resize_reinit_failed w=%d h=%d err=%s",
                 width_, height_, vpx_codec_error(&codec_));
      initialized_ = false;
      return kVp9Error;
    }
    codec_open_ = true;
    initialized_ = true;
    vpx_codec_control(&codec_, VP8E_SET_CPUUSED, kCpuUsed);
    // 新码流：取证标记按"首帧"重打；尺寸变化后必须重新出关键帧，否则远端无法起播。
    frame_count_ = 0;
    last_pts_us_ = -1;
    force_key_frame_ = true;
    encoded_.clear();
    NLOG_INFO(kTagEncoder,
              "encoder_reinit w=%d h=%d old_w=%d old_h=%d reason=size_change_"
              "rotation_or_adapt",
              width_, height_, old_w, old_h);
  }

  // ---- 组装 vpx_image_t --------------------------------------------------
  // rotation==0：**零拷贝**借用调用方平面（既有语义；direct ByteBuffer 前提不变）；
  // rotation!=0：旋转到内部 rotate_buf_（三平面紧密排列，stride = 旋转后宽度）。
  const uint8_t* plane_y = frame.y;
  const uint8_t* plane_u = frame.u;
  const uint8_t* plane_v = frame.v;
  int plane_stride_y = frame.stride_y;
  int plane_stride_u = frame.stride_u;
  int plane_stride_v = frame.stride_v;
  if (rotation != 0) {
    const size_t needed = RotatedTotalSize(frame_width, frame_height, rotation);
    if (needed == 0 || needed > kMaxRotateBufferBytes) {
      NLOG_ERROR(kTagEncoder, "encode_rotate_buf_invalid need=%zu rot=%d", needed,
                 rotation);
      return kVp9ErrSize;
    }
    if (rotate_buf_.size() < needed) {
      // 复用优先：仅在尺寸增长时分配（本工程 -fno-exceptions，分配失败即 abort，
      // 与既有 encoded_.assign 的策略一致）。
      rotate_buf_.assign(needed, 0);
    }
    const int rot_w = RotatedWidth(frame_width, frame_height, rotation);
    const int rot_h = RotatedHeight(frame_width, frame_height, rotation);
    const size_t y_size = static_cast<size_t>(rot_w) * static_cast<size_t>(rot_h);
    const size_t uv_size = static_cast<size_t>(rot_w / 2) * static_cast<size_t>(rot_h / 2);

    I420View src_view{};
    src_view.y.data = frame.y;
    src_view.y.stride = frame.stride_y;
    src_view.u.data = frame.u;
    src_view.u.stride = frame.stride_u;
    src_view.v.data = frame.v;
    src_view.v.stride = frame.stride_v;
    src_view.width = frame_width;
    src_view.height = frame_height;

    I420MutView dst_view{};
    dst_view.y.data = rotate_buf_.data();
    dst_view.y.stride = rot_w;
    dst_view.u.data = rotate_buf_.data() + y_size;
    dst_view.u.stride = rot_w / 2;
    dst_view.v.data = dst_view.u.data + uv_size;
    dst_view.v.stride = rot_w / 2;

    RotateI420(src_view, rotation, dst_view);

    plane_y = dst_view.y.data;
    plane_u = dst_view.u.data;
    plane_v = dst_view.v.data;
    plane_stride_y = dst_view.y.stride;
    plane_stride_u = dst_view.u.stride;
    plane_stride_v = dst_view.v.stride;
    NLOG_INFO(kTagEncoder, "encoder_rotate rot=%d in=%dx%d out=%dx%d", rotation,
              frame_width, frame_height, rot_w, rot_h);
  }

  // 【t50b】改为「拷进 libvpx 自持图像」：不再把**外部缓冲/任意 stride**直接交给
  // `vpx_codec_encode()` —— 真机连续两次在该调用内部崩溃（`reports/20`、`reports/22`），
  // 而 SDK 给的是 `sy=640 su=640 sv=640` 这类非常规布局。libwebrtc 自己的
  // `LibvpxVp9Encoder` 同样是"先拷进自持图像再编码"。
  if (!EnsureRawImageLocked()) {
    return kVp9Error;
  }
  CopyPlaneIntoImage(plane_y, plane_stride_y, raw_img_->planes[VPX_PLANE_Y],
                     raw_img_->stride[VPX_PLANE_Y], width_, height_);
  CopyPlaneIntoImage(plane_u, plane_stride_u, raw_img_->planes[VPX_PLANE_U],
                     raw_img_->stride[VPX_PLANE_U], width_ / 2, height_ / 2);
  CopyPlaneIntoImage(plane_v, plane_stride_v, raw_img_->planes[VPX_PLANE_V],
                     raw_img_->stride[VPX_PLANE_V], width_ / 2, height_ / 2);

  // pts 单调递增（libvpx 用 pts 差值推断帧率；重复时间戳会告警）。
  int64_t pts_us = (frame.capture_time_ns > 0) ? frame.capture_time_ns / 1000
                                               : last_pts_us_ + 1;
  if (pts_us <= last_pts_us_) {
    pts_us = last_pts_us_ + 1;
  }
  last_pts_us_ = pts_us;
  const unsigned long duration_us =
      static_cast<unsigned long>(1000000 / std::max(1, config_.max_framerate));

  uint32_t flags = 0;
  if (request_key_frame || force_key_frame_) {
    flags |= VPX_EFLAG_FORCE_KF;
    force_key_frame_ = false;
  }

  const int64_t start_us = NowMicros();
  // 【t48 取证标记】真机上首帧 nativeEncode 之后**再无任何日志**（既没有
  // encoded_frame，也没有 encode_failed，且 CSV 没有对应行）⇒ 故障点在
  // vpx_codec_encode() 内部或紧随其后。下面两条「进入/返回」标记把这段
  // 收敛到一行：若下次真机日志停在 encode_vpx_begin，就是 libvpx 内部
  // 崩溃/卡死；若停在 encode_vpx_done，则问题在取包/回调层。
  if (frame_count_ == 0) {
    NLOG_INFO(kTagEncoder,
              "encode_vpx_begin frame=1 pts_us=%lld dur_us=%lu flags=%u "
              "w=%d h=%d sy=%d su=%d sv=%d img_owner=%d",
              static_cast<long long>(pts_us), duration_us,
              static_cast<unsigned int>(flags), width_, height_,
              raw_img_ != nullptr ? raw_img_->stride[VPX_PLANE_Y] : -1,
              raw_img_ != nullptr ? raw_img_->stride[VPX_PLANE_U] : -1,
              raw_img_ != nullptr ? raw_img_->stride[VPX_PLANE_V] : -1,
              raw_img_ != nullptr ? static_cast<int>(raw_img_->img_data_owner)
                                  : -1);
  } else {
    NLOG_DEBUG(kTagEncoder,
               "encode_vpx_begin frame=%lld pts_us=%lld dur_us=%lu flags=%u",
               static_cast<long long>(frame_count_ + 1),
               static_cast<long long>(pts_us), duration_us,
               static_cast<unsigned int>(flags));
  }
  const vpx_codec_err_t encode_result = vpx_codec_encode(
      &codec_, raw_img_, pts_us, duration_us, flags, VPX_DL_REALTIME);
  NLOG_DEBUG(kTagEncoder, "encode_vpx_done frame=%lld err=%d us=%lld",
             static_cast<long long>(frame_count_ + 1),
             static_cast<int>(encode_result),
             static_cast<long long>(NowMicros() - start_us));
  if (encode_result != VPX_CODEC_OK) {
    NLOG_ERROR(kTagEncoder, "encode_failed err=%s detail=%s",
               vpx_codec_error(&codec_), vpx_codec_error_detail(&codec_));
    return kVp9Error;
  }

  // ---- 取包：vpx 内部缓冲会在下次 encode 后复用，必须**立即拷贝** ----------
  encoded_.clear();
  meta_ = EncodedFrameMeta();
  const vpx_codec_cx_pkt_t* packet = nullptr;
  vpx_codec_iter_t iter = nullptr;
  while ((packet = vpx_codec_get_cx_data(&codec_, &iter)) != nullptr) {
    if (packet->kind != VPX_CODEC_CX_FRAME_PKT) {
      continue;
    }
    const size_t size = packet->data.frame.sz;
    const uint8_t* source = static_cast<const uint8_t*>(packet->data.frame.buf);
    if (source != nullptr && size > 0) {
      encoded_.assign(source, source + size);
      // 优先用 vpx 回传的实际编码尺寸（与请求尺寸可能因对齐而不同）。
      const unsigned int packet_width = packet->data.frame.width[0];
      const unsigned int packet_height = packet->data.frame.height[0];
      meta_.width =
          packet_width > 0 ? static_cast<int32_t>(packet_width) : width_;
      meta_.height =
          packet_height > 0 ? static_cast<int32_t>(packet_height) : height_;
      meta_.is_key_frame =
          (packet->data.frame.flags & VPX_FRAME_IS_KEY) != 0 ? 1 : 0;
      // ss=1（L1T3）下空间层恒为 0；本版本 vpx 不回传时序层号，按帧序回推。
      meta_.spatial_index = 0;
      meta_.temporal_index =
          TemporalIndexForFrame(frame_count_, last_rates_.configured_temporal);
    }
  }

  // 量化参数：VP9 复用 VP8E_GET_LAST_QUANTIZER（libvpx 该版本无
  // VP9E_GET_LAST_QUANTIZER，见 vp9_cx_iface.c:2260 的控制表）；失败记 -1。
  int qp = -1;
  if (vpx_codec_control(&codec_, VP8E_GET_LAST_QUANTIZER, &qp) !=
      VPX_CODEC_OK) {
    qp = -1;
  }
  meta_.qp = qp;

  // 【t48 取证标记】vpx 返回 OK 但没产出包（例如分层/RC 把该帧丢掉）时明确记一笔：
  // 旧代码在这种情形下只会走 WriteBitrateCsvLocked(0,…)，CSV 里看不出区别。
  if (encoded_.empty()) {
    NLOG_WARN(kTagEncoder,
              "encode_no_packet frame=%lld pts_us=%lld rot_w=%d rot_h=%d "
              "flags=%u",
              static_cast<long long>(frame_count_ + 1),
              static_cast<long long>(pts_us), width_, height_,
              static_cast<unsigned int>(flags));
  }

  const int64_t elapsed_us = NowMicros() - start_us;
  ++frame_count_;
  const bool produced = !encoded_.empty();
  const int32_t encoded_bytes =
      produced ? static_cast<int32_t>(encoded_.size()) : 0;
  if (elapsed_us > kSlowFrameThresholdUs) {
    ++slow_frame_count_;
    NLOG_WARN(kTagEncoder,
              "encoded_frame slow frame=%lld us=%lld w=%d h=%d bytes=%d "
              "slow_total=%lld",
              static_cast<long long>(frame_count_),
              static_cast<long long>(elapsed_us), width_, height_,
              encoded_bytes, static_cast<long long>(slow_frame_count_));
  } else {
    NLOG_DEBUG(kTagEncoder,
               "encoded_frame frame=%lld us=%lld bytes=%d key=%d t=%d qp=%d",
               static_cast<long long>(frame_count_),
               static_cast<long long>(elapsed_us), encoded_bytes,
               meta_.is_key_frame, meta_.temporal_index, qp);
  }
  WriteBitrateCsvLocked(encoded_bytes, meta_.is_key_frame, qp);
  return produced ? kVp9Ok : kVp9NoOutput;
}

void Vp9Encoder::WriteBitrateCsvLocked(int32_t encoded_bytes, int key_frame,
                                       int qp) {
  EncoderBitrateSample sample;
  sample.ts_ms = NativeLoggerNowMillis();
  sample.total_bps = last_rates_.total_bps;
  sample.fps = config_.max_framerate;
  sample.num_spatial = last_rates_.configured_spatial;
  sample.num_temporal = last_rates_.configured_temporal;
  for (int s = 0; s < kMaxSpatialLayers; ++s) {
    for (int t = 0; t < kMaxTemporalLayers; ++t) {
      sample.layer_bps[s][t] = last_matrix_.layer_bps[s][t];
    }
  }
  for (int t = 0; t < kMaxTemporalLayers; ++t) {
    sample.ts_kbps[t] = last_rates_.ts_target_bitrate_kbps[t];
  }
  sample.rc_target_kbps = last_rates_.rc_target_bitrate_kbps;
  sample.encoded_bytes = encoded_bytes;
  sample.key_frame = key_frame;
  sample.qp = qp;
  NativeLogger::Instance().WriteBitrateSample(sample);
}

}  // namespace webrtcdemo
