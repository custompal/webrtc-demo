// 主机侧最小 stub：仅用于本地单元验证（非交付物）。
#pragma once
#include <stdarg.h>
#include <stdio.h>
typedef enum {
  ANDROID_LOG_VERBOSE = 2,
  ANDROID_LOG_DEBUG = 3,
  ANDROID_LOG_INFO = 4,
  ANDROID_LOG_WARN = 5,
  ANDROID_LOG_ERROR = 6,
  ANDROID_LOG_SILENT = 8
} android_LogPriority;
static inline int __android_log_print(int prio, const char* tag,
                                      const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fprintf(stderr, "[logcat:%d:%s] ", prio, tag);
  int n = vfprintf(stderr, fmt, ap);
  va_end(ap);
  fprintf(stderr, "\n");
  return n;
}
