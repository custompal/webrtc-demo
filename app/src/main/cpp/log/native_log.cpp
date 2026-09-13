// ============================================================================
// log/native_log.cpp —— 自研 C++ 日志设施实现（D6 / doc/14 §9.2、§9.4）
// ----------------------------------------------------------------------------
// 关键实现点：
//   1) 单例 + std::mutex：编码线程 / NAT 线程 / JNI 线程都会写日志，
//      所有文件写操作串行化，且**每条日志一次 write()**，不会互相撕裂。
//   2) 双写：先写 logcat（__android_log_print），再写文件；
//      nativeInit 之前（fd_ < 0）只落 logcat，并打一次 ERROR（契约 §9.4）。
//   3) 滚动：写前 fstat 判断 size + incoming > max_bytes_per_file，
//      删除最旧、依次改名 native.1.log → native.2.log …（契约 §9.4）。
//   4) encoder_bitrate.csv：SetRates / Encode 后的码率闭环留痕（契约 §9.4），
//      表头与行字段顺序冻结，verifier 据此画动态码率曲线。
// ============================================================================
#include "log/native_log.h"

#include <android/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>

namespace webrtcdemo {
namespace {

// logcat 侧 tag 冻结（契约 §9.2）：C++ 用 WebRtcDemo-Native。
constexpr char kLogcatTag[] = "WebRtcDemo-Native";

// 码率 CSV 表头（契约 §9.4 冻结，V23 按此字符串做 grep 锚点）。
constexpr char kBitrateCsvHeader[] =
    "ts_ms,total_bps,fps,s,t,layer_0_0,layer_0_1,layer_0_2,"
    "ts_kbps_0,ts_kbps_1,ts_kbps_2,rc_target_kbps,encoded_bytes,key,qp\n";

// 单条消息格式化上限：超出截断并在末尾标记。
constexpr size_t kMaxMessageBytes = 1024;

// 级别名（契约 §9.1：VERBOSE/DEBUG/INFO/WARN/ERROR，左对齐定宽 7）。
const char* LevelName(int level) {
  switch (level) {
    case kLogVerbose:
      return "VERBOSE";
    case kLogDebug:
      return "DEBUG";
    case kLogInfo:
      return "INFO";
    case kLogWarn:
      return "WARN";
    case kLogError:
      return "ERROR";
    default:
      return "OFF";
  }
}

// 级别 → logcat 优先级。
int AndroidPriority(int level) {
  switch (level) {
    case kLogVerbose:
      return ANDROID_LOG_VERBOSE;
    case kLogDebug:
      return ANDROID_LOG_DEBUG;
    case kLogInfo:
      return ANDROID_LOG_INFO;
    case kLogWarn:
      return ANDROID_LOG_WARN;
    default:
      return ANDROID_LOG_ERROR;
  }
}

// 生成 "YYYY-MM-DDThh:mm:ss.SSSZ"（UTC，契约 §9.1）。
void FormatUtcTimestamp(char* out, size_t out_size, struct timespec* ts) {
  struct tm tm_utc;
  gmtime_r(&ts->tv_sec, &tm_utc);
  snprintf(out, out_size, "%04d-%02d-%02dT%02d:%02d:%02d.%03ldZ",
           tm_utc.tm_year + 1900, tm_utc.tm_mon + 1, tm_utc.tm_mday,
           tm_utc.tm_hour, tm_utc.tm_min, tm_utc.tm_sec, ts->tv_nsec / 1000000);
}

// 当前 UTC 毫秒（CSV 用）。
int64_t NowUnixMillis() {
  struct timespec ts;
  clock_gettime(CLOCK_REALTIME, &ts);
  return static_cast<int64_t>(ts.tv_sec) * 1000 +
         static_cast<int64_t>(ts.tv_nsec) / 1000000;
}

// tag 截断到 24 字符（契约 §9.1）。
std::string SanitizeTag(const char* tag) {
  std::string t = (tag != nullptr) ? tag : "main";
  if (t.size() > 24) {
    t.resize(24);
  }
  return t;
}

}  // namespace

NativeLogger& NativeLogger::Instance() {
  // C++11 起函数内 static 的初始化是线程安全的（无需异常）。
  static NativeLogger instance;
  return instance;
}

NativeLogger::~NativeLogger() {
  Shutdown();
}

std::string NativeLogger::BuildLogPath(const std::string& dir,
                                       const std::string& base) {
  std::string path = dir;
  if (!path.empty() && path.back() != '/') {
    path.push_back('/');
  }
  path += base;
  path += ".log";
  return path;
}

bool NativeLogger::Init(const std::string& log_dir,
                        const std::string& file_name_base, int level,
                        int64_t max_bytes_per_file, int max_files) {
  const int clamped_level =
      (level < kLogVerbose || level > kLogOff) ? kLogInfo : level;
  // 参数钳制：上限 64 MiB / 保留 1..10 个，防误传导致磁盘写爆。
  int64_t clamped_max = max_bytes_per_file;
  if (clamped_max < 64 * 1024) {
    clamped_max = 64 * 1024;
  }
  if (clamped_max > 64 * 1024 * 1024) {
    clamped_max = 64 * 1024 * 1024;
  }
  int clamped_files = (max_files < 1) ? 1 : max_files;
  if (clamped_files > 10) {
    clamped_files = 10;
  }

  std::lock_guard<std::mutex> lock(mutex_);
  // 幂等：重复调用先关旧文件（契约 §6.2）。
  if (fd_ >= 0) {
    close(fd_);
    fd_ = -1;
  }
  if (csv_fd_ >= 0) {
    close(csv_fd_);
    csv_fd_ = -1;
  }

  log_dir_ = log_dir;
  file_base_ = file_name_base.empty() ? "native" : file_name_base;
  log_path_ = BuildLogPath(log_dir_, file_base_);
  csv_path_ = log_dir_;
  if (!csv_path_.empty() && csv_path_.back() != '/') {
    csv_path_.push_back('/');
  }
  csv_path_ += "encoder_bitrate.csv";
  max_bytes_per_file_ = clamped_max;
  max_files_ = clamped_files;
  level_.store(clamped_level);
  csv_header_written_ = false;

  if (!OpenFileLocked()) {
    __android_log_print(ANDROID_LOG_ERROR, kLogcatTag,
                        "native_log_init_failed dir=%s base=%s errno=%d",
                        log_dir_.c_str(), file_base_.c_str(), errno);
    return false;
  }
  // CSV 文件独立打开（同一目录）；失败只告警，不影响主日志。
  csv_fd_ = open(csv_path_.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
  if (csv_fd_ >= 0) {
    struct stat st;
    if (fstat(csv_fd_, &st) == 0 && st.st_size == 0) {
      ssize_t written =
          write(csv_fd_, kBitrateCsvHeader, strlen(kBitrateCsvHeader));
      csv_header_written_ = (written > 0);
    } else {
      csv_header_written_ = true;  // 已有表头（追加模式）
    }
  } else {
    __android_log_print(ANDROID_LOG_WARN, kLogcatTag,
                        "native_bitrate_csv_open_failed path=%s errno=%d",
                        csv_path_.c_str(), errno);
  }
  uninitialized_warned_ = false;
  return true;
}

bool NativeLogger::OpenFileLocked() {
  fd_ = open(log_path_.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
  return fd_ >= 0;
}

void NativeLogger::SetLevel(int level) {
  const int clamped =
      (level < kLogVerbose || level > kLogOff) ? kLogInfo : level;
  level_.store(clamped);
}

int NativeLogger::level() const {
  return level_.load();
}

bool NativeLogger::ShouldLog(int level) const {
  // 非法或 OFF 级别永不输出（避免出现 LEVEL 字段为 "OFF" 的日志行，
  // 破坏契约 §9.1 的固定行格式）。
  if (level < kLogVerbose || level > kLogError) {
    return false;
  }
  const int current = level_.load();
  return current != kLogOff && level >= current;
}

std::string NativeLogger::BuildLinePrefix(int level, const char* tag) {
  char ts[96] = {0};  // 足够容纳最长的时间戳（避免 -Wformat-truncation）
  struct timespec now;
  clock_gettime(CLOCK_REALTIME, &now);
  FormatUtcTimestamp(ts, sizeof(ts), &now);

  const std::string safe_tag = SanitizeTag(tag);
  char prefix[160] = {0};
  // 字段宽度冻结（契约 §9.1 示例逐字符对齐）：
  //   LEVEL 定宽 7 + 1 空格；layer 定宽 6 + 1 空格；tag 定宽 10 后紧跟 '['。
  snprintf(prefix, sizeof(prefix), "%s %-7s %-6s %-10s[%d/%d] ", ts,
           LevelName(level), "native", safe_tag.c_str(),
           static_cast<int>(getpid()), static_cast<int>(gettid()));
  return std::string(prefix);
}

void NativeLogger::WarnUninitializedOnce() {
  if (uninitialized_warned_) {
    return;
  }
  uninitialized_warned_ = true;
  __android_log_print(ANDROID_LOG_ERROR, kLogcatTag,
                      "native_log_not_initialized"
                      " hint=call_NativeLog_nativeInit_first");
}

void NativeLogger::WriteLineLocked(const std::string& line) {
  if (fd_ < 0) {
    return;
  }
  RollLocked(static_cast<int64_t>(line.size()));
  if (fd_ < 0) {
    return;
  }
  // 单次 write()：保证多线程下整行原子落盘（契约 §9.2）。
  ssize_t written = write(fd_, line.data(), line.size());
  (void)written;
}

void NativeLogger::RollLocked(int64_t incoming_bytes) {
  struct stat st;
  if (fstat(fd_, &st) != 0) {
    return;
  }
  if (static_cast<int64_t>(st.st_size) + incoming_bytes <=
      max_bytes_per_file_) {
    return;
  }
  // 滚动：native.log → native.1.log → … → native.(N-1).log，超出即删。
  const std::string dir = log_dir_.empty() ? std::string(".") : log_dir_;
  auto path_for = [&dir](const std::string& base, int index) {
    std::string p = dir;
    if (!p.empty() && p.back() != '/') {
      p.push_back('/');
    }
    p += base;
    if (index > 0) {
      p += "." + std::to_string(index);
    }
    p += ".log";
    return p;
  };

  close(fd_);
  fd_ = -1;
  if (max_files_ <= 1) {
    unlink(path_for(file_base_, 0).c_str());
  } else {
    unlink(path_for(file_base_, max_files_ - 1).c_str());
    for (int i = max_files_ - 2; i >= 0; --i) {
      const std::string from = path_for(file_base_, i);
      const std::string to = path_for(file_base_, i + 1);
      if (rename(from.c_str(), to.c_str()) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kLogcatTag,
                            "native_log_roll_rename_failed from=%s to=%s",
                            from.c_str(), to.c_str());
      }
    }
  }
  if (!OpenFileLocked()) {
    __android_log_print(ANDROID_LOG_ERROR, kLogcatTag,
                        "native_log_reopen_failed path=%s", log_path_.c_str());
  }
}

void NativeLogger::Log(int level, const char* tag, const std::string& message) {
  if (!ShouldLog(level)) {
    return;
  }
  const std::string line = BuildLinePrefix(level, tag) + message;
  // 1) logcat：始终写（D6）；nativeInit 之前这是唯一的输出去向。
  __android_log_print(AndroidPriority(level), kLogcatTag, "%s", line.c_str());
  // 2) 文件：加锁 + 一次 write()。
  std::lock_guard<std::mutex> lock(mutex_);
  if (fd_ < 0) {
    WarnUninitializedOnce();
    return;
  }
  WriteLineLocked(line + "\n");
}

void NativeLogger::LogF(int level, const char* tag, const char* fmt, ...) {
  if (!ShouldLog(level) || fmt == nullptr) {
    return;
  }
  char buffer[kMaxMessageBytes];
  va_list args;
  va_start(args, fmt);
  const int n = vsnprintf(buffer, sizeof(buffer), fmt, args);
  va_end(args);
  if (n < 0) {
    return;
  }
  Log(level, tag, std::string(buffer));
}

void NativeLogger::WriteBitrateSample(const EncoderBitrateSample& s) {
  // 行字段顺序与表头严格一致（契约 §9.4）。
  char row[512];
  const int n = snprintf(
      row, sizeof(row), "%lld,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
      static_cast<long long>(s.ts_ms), s.total_bps, s.fps, s.num_spatial,
      s.num_temporal, s.layer_bps[0][0], s.layer_bps[0][1], s.layer_bps[0][2],
      s.ts_kbps[0], s.ts_kbps[1], s.ts_kbps[2], s.rc_target_kbps,
      s.encoded_bytes, s.key_frame, s.qp);
  if (n <= 0) {
    return;
  }
  std::lock_guard<std::mutex> lock(mutex_);
  if (csv_fd_ < 0) {
    return;  // 未初始化：CSV 没有 logcat 对应物，直接丢弃
  }
  if (!csv_header_written_) {
    ssize_t written =
        write(csv_fd_, kBitrateCsvHeader, strlen(kBitrateCsvHeader));
    csv_header_written_ = (written > 0);
  }
  // CSV 也按同一上限滚动，只保留一份历史（demo 场景足够）。
  struct stat st;
  if (fstat(csv_fd_, &st) == 0 &&
      static_cast<int64_t>(st.st_size) + n > max_bytes_per_file_) {
    close(csv_fd_);
    csv_fd_ = -1;
    const std::string backup = csv_path_ + ".1";
    unlink(backup.c_str());
    if (rename(csv_path_.c_str(), backup.c_str()) != 0) {
      __android_log_print(ANDROID_LOG_WARN, kLogcatTag,
                          "bitrate_csv_roll_failed path=%s", csv_path_.c_str());
    }
    csv_fd_ = open(csv_path_.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
    csv_header_written_ = false;
    if (csv_fd_ >= 0) {
      ssize_t written =
          write(csv_fd_, kBitrateCsvHeader, strlen(kBitrateCsvHeader));
      csv_header_written_ = (written > 0);
    }
  }
  if (csv_fd_ >= 0) {
    ssize_t written = write(csv_fd_, row, static_cast<size_t>(n));
    (void)written;
  }
}

void NativeLogger::Flush() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (fd_ >= 0) {
    fsync(fd_);
  }
  if (csv_fd_ >= 0) {
    fsync(csv_fd_);
  }
}

void NativeLogger::Shutdown() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (fd_ >= 0) {
    fsync(fd_);
    close(fd_);
    fd_ = -1;
  }
  if (csv_fd_ >= 0) {
    fsync(csv_fd_);
    close(csv_fd_);
    csv_fd_ = -1;
  }
}

// 暴露给编码器/探测器的“当前 UTC 毫秒”工具（同一实现，避免重复）。
int64_t NativeLoggerNowMillis() {
  return NowUnixMillis();
}

}  // namespace webrtcdemo
