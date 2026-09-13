// ============================================================================
// log/log_macros.h —— 日志宏门面（NLOG_*）
// ----------------------------------------------------------------------------
// 为什么用宏而不是 inline 函数：宏里先做 ShouldLog(level) 短路，级别不够时
// **完全不求值参数**（避免编码线程上每帧构造字符串的开销，契约 §5.4 要求
// 编码线程不得做重活）。tag 必须是字符串字面量（≤24 字符 snake_case）。
//
// 用法：
//   NLOG_INFO("encoder", "encoder_init w=%d h=%d s=%d t=%d cpu=%d",
//             w, h, s, t, cpu);
//   NLOG_WARN("nat", "nat_test name=%s result=%s", name, result);
// ============================================================================
#pragma once

#include "log/native_log.h"

// 注意：所有宏都只在 ShouldLog 为真时才求值参数。
#define NLOG_VERBOSE(tag, ...)                                               \
  do {                                                                       \
    if (::webrtcdemo::NativeLogger::Instance().ShouldLog(                    \
            ::webrtcdemo::kLogVerbose)) {                                    \
      ::webrtcdemo::NativeLogger::Instance().LogF(::webrtcdemo::kLogVerbose, \
                                                  (tag), __VA_ARGS__);       \
    }                                                                        \
  } while (0)

#define NLOG_DEBUG(tag, ...)                                               \
  do {                                                                     \
    if (::webrtcdemo::NativeLogger::Instance().ShouldLog(                  \
            ::webrtcdemo::kLogDebug)) {                                    \
      ::webrtcdemo::NativeLogger::Instance().LogF(::webrtcdemo::kLogDebug, \
                                                  (tag), __VA_ARGS__);     \
    }                                                                      \
  } while (0)

#define NLOG_INFO(tag, ...)                                               \
  do {                                                                    \
    if (::webrtcdemo::NativeLogger::Instance().ShouldLog(                 \
            ::webrtcdemo::kLogInfo)) {                                    \
      ::webrtcdemo::NativeLogger::Instance().LogF(::webrtcdemo::kLogInfo, \
                                                  (tag), __VA_ARGS__);    \
    }                                                                     \
  } while (0)

#define NLOG_WARN(tag, ...)                                               \
  do {                                                                    \
    if (::webrtcdemo::NativeLogger::Instance().ShouldLog(                 \
            ::webrtcdemo::kLogWarn)) {                                    \
      ::webrtcdemo::NativeLogger::Instance().LogF(::webrtcdemo::kLogWarn, \
                                                  (tag), __VA_ARGS__);    \
    }                                                                     \
  } while (0)

#define NLOG_ERROR(tag, ...)                                               \
  do {                                                                     \
    if (::webrtcdemo::NativeLogger::Instance().ShouldLog(                  \
            ::webrtcdemo::kLogError)) {                                    \
      ::webrtcdemo::NativeLogger::Instance().LogF(::webrtcdemo::kLogError, \
                                                  (tag), __VA_ARGS__);     \
    }                                                                      \
  } while (0)
