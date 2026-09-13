// ============================================================================
// log/native_log.h —— 自研 C++ 日志设施（D6 / doc/14 §9）
// ----------------------------------------------------------------------------
// 职责：
//   1) **同时**输出到 Android logcat（__android_log_print，tag=WebRtcDemo-Native）
//      与日志文件（<filesDir>/logs/native.log，2 MiB × 3 滚动）；
//   2) 线程安全（单例 + std::mutex 串行化写入）；
//   3) 运行时可切级别（nativeSetLevel），不用 C++ 异常（-fno-exceptions）；
//   4) 维护码率闭环 CSV：<filesDir>/logs/encoder_bitrate.csv（学习点留痕）。
//
// 统一行格式（契约 §9.1，三层必须一致）：
//   <ts> <LEVEL> <layer> <tag> [<pid>/<tid>] <message>[ key=value ...]
//   ts  = YYYY-MM-DDThh:mm:ss.SSSZ（UTC，毫秒，字面 Z）
//   LEVEL 左对齐定宽 7；layer 左对齐定宽 6（native）；tag 左对齐定宽 10 后紧跟 [
//   tag ≤24 字符 snake_case：main/ui/signaling/pc/ice/stats/encoder/bitrate/nat/jni
//
// 线程模型：编码线程（libwebrtc 的 encoding thread 调 Java 编码器，再进 JNI）、
//   NAT 探测线程、JNI 调用线程都会写日志，因此所有写操作都在同一把互斥锁内，
//   **每条日志一次 write()**，避免多线程写同一 fd 造成交错撕裂。
// ============================================================================
#pragma once

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <mutex>
#include <string>

namespace webrtcdemo {

// 日志级别（契约 §6.6：native 与 Kotlin 数值必须一致）
enum LogLevel : int32_t {
  kLogVerbose = 0,
  kLogDebug = 1,
  kLogInfo = 2,
  kLogWarn = 3,
  kLogError = 4,
  kLogOff = 5,
};

// 单条编码码率采样（写 encoder_bitrate.csv，契约 §9.4 表头见 .cpp）。
// 为什么单独一个结构体：SetRates 与 Encode 都要写同一张表，字段必须完全对齐，
// verifier 用它画“GCC 目标码率 → vpx 分层配置 → 实际输出”的曲线。
struct EncoderBitrateSample {
  int64_t ts_ms = 0;      // 采样时刻（UTC 毫秒）
  int32_t total_bps = 0;  // SetRates 收到的总码率（bps）
  int fps = 0;            // SetRates 收到的帧率
  int num_spatial = 0;  // 编码器配置的空间层数（本路线固定 1，契约 §5.6）
  int num_temporal = 0;  // 编码器配置的时序层数（3 = L1T3）
  int32_t layer_bps[3][3] = {{0}};  // 原始 [spatial][temporal] 码率矩阵（bps）
  int32_t ts_kbps[3] = {
      0};  // 实际写回 libvpx 的 ts_target_bitrate（累计，kbps）
  int32_t rc_target_kbps = 0;  // 实际写回 libvpx 的 rc_target_bitrate（kbps）
  int32_t encoded_bytes = 0;  // 本帧编码输出字节数（Encode 后填）
  int key_frame = 0;          // 是否关键帧（0/1）
  int qp = -1;                // 最后一帧量化参数（-1 = 未知）
};

// NativeLogger：进程内单例。所有公共方法线程安全。
class NativeLogger {
 public:
  // 返回进程内唯一实例（首次调用时构造，不抛异常）。
  static NativeLogger& Instance();

  // 初始化文件日志。
  // 参数：
  //   log_dir            日志目录，**必须已存在且可写**（Kotlin FileLogger 负责
  //                      mkdirs；契约 §9.4）。ENOENT/EACCES 时只落 logcat。
  //   file_name_base     文件名基名，例如 "native" → native.log / native.1.log
  //   level              初始级别（0..5，契约 §6.6）
  //   max_bytes_per_file 单文件上限（字节，默认 2*1024*1024）
  //   max_files          保留文件数（含当前文件，默认 3）
  // 返回值：文件是否成功打开。**幂等**：重复调用先关旧文件再重开。
  // 调用时机：必须在任何 NativeVp9Encoder / NativeNatDetector 调用之前
  //           （Java 侧 WebRtcDemoApp.onCreate 第一步，契约 §9.4）。
  bool Init(const std::string& log_dir, const std::string& file_name_base,
            int level, int64_t max_bytes_per_file, int max_files);

  // 运行时切换级别（0..5，越界钳制）；线程安全。
  void SetLevel(int level);

  // 当前级别。
  int level() const;

  // 该级别是否会被输出（供宏做零成本短路，避免无谓的字符串格式化）。
  bool ShouldLog(int level) const;

  // 写一条已格式化好的日志（message 可含 "key=value" 尾巴）。
  void Log(int level, const char* tag, const std::string& message);

  // printf 风格写日志（内部 vsnprintf，格式化结果 ≤1024 字节）。
  void LogF(int level, const char* tag, const char* fmt, ...)
      __attribute__((format(printf, 4, 5)));

  // 追加一行码率 CSV（文件写入 + 必要时滚动）；线程安全。
  void WriteBitrateSample(const EncoderBitrateSample& sample);

  // fflush + fsync 当前日志与 CSV；**导出日志前必调**（契约 §6.2/§9.5）。
  void Flush();

  // 关闭文件；之后 native 日志只落 logcat。幂等。
  void Shutdown();

 private:
  NativeLogger() = default;
  ~NativeLogger();
  NativeLogger(const NativeLogger&) = delete;
  NativeLogger& operator=(const NativeLogger&) = delete;

  // 以 base 生成 <dir>/<base>.log 路径。
  static std::string BuildLogPath(const std::string& dir,
                                  const std::string& base);
  // 生成 `<ts> <LEVEL> native <tag> [pid/tid] ` 前缀。
  static std::string BuildLinePrefix(int level, const char* tag);
  // 打开（若未打开）日志文件；调用者必须已持锁。
  bool OpenFileLocked();
  // 按大小滚动（base.1.log→base.2.log…），调用者必须已持锁。
  void RollLocked(int64_t incoming_bytes);
  // 把一行（含换行）写入 fd_；调用者必须已持锁。
  void WriteLineLocked(const std::string& line);
  // 未初始化时的一次性 ERROR（只落 logcat，契约 §9.4）。
  void WarnUninitializedOnce();

  mutable std::mutex mutex_;
  std::atomic<int> level_{kLogInfo};

  int fd_ = -1;      // native.log 的 fd（-1 = 未打开）
  int csv_fd_ = -1;  // encoder_bitrate.csv 的 fd
  std::string log_dir_;
  std::string file_base_;
  std::string log_path_;
  std::string csv_path_;
  int64_t max_bytes_per_file_ = 2 * 1024 * 1024;  // 契约 §9.2：2 MiB
  int max_files_ = 3;                             // 契约 §9.2：保留 3 个
  bool csv_header_written_ = false;
  bool uninitialized_warned_ = false;
};

// 当前 UTC 毫秒（编码器写码率 CSV 时取采样时间；实现见 native_log.cpp）。
int64_t NativeLoggerNowMillis();

}  // namespace webrtcdemo
