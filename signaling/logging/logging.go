// Logger 构造：logrus + doc/14 §9.1 行格式 + §9.2 终端/文件双写与滚动。
package logging

import (
	"io"
	"os"
	"strings"

	"github.com/sirupsen/logrus"
)

// Setup 创建符合契约的 logger，并返回关闭函数。
//
// 行为（用户明确要求 + 契约 §9.2/§9.3）：
//   - 终端（stdout，systemd journal 可见）与日志文件同时输出：io.MultiWriter；
//   - 日志目录不存在自动创建；
//   - 目录/文件不可用时**只告警并降级为仅终端**，绝不让服务起不来；
//   - path 为空表示仅终端；
//   - 级别支持 trace|verbose|debug|info|warn|error。
func Setup(path, levelName string) (*logrus.Logger, func()) {
	logger := logrus.New()
	logger.SetFormatter(Formatter{})
	logger.SetOutput(os.Stdout)

	level, err := ParseLevel(levelName)
	if err != nil {
		logger.WithField("tag", TagMain).WithField("level_arg", levelName).
			WithField("fallback", "info").Warn("log_level_invalid")
		level = logrus.InfoLevel
	}
	logger.SetLevel(level)

	if strings.TrimSpace(path) == "" {
		logger.WithField("tag", TagMain).WithField("stdout_only", "true").Warn("log_file_disabled")
		return logger, func() {}
	}

	rw, err := NewRotatingWriter(path, DefaultMaxBytes, DefaultMaxFiles)
	if err != nil {
		logger.WithField("tag", TagMain).WithField("log_file", path).WithField("error", err.Error()).
			WithField("stdout_only", "true").Warn("log_file_fallback")
		return logger, func() {}
	}

	logger.SetOutput(io.MultiWriter(os.Stdout, rw))
	logger.WithField("tag", TagMain).WithField("log_file", path).
		WithField("max_bytes", DefaultMaxBytes).WithField("max_files", DefaultMaxFiles).
		Info("log_file_open")

	return logger, func() { _ = rw.Close() }
}

// ParseLevel 解析契约允许的级别名（trace|verbose|debug|info|warn|error）。
func ParseLevel(name string) (logrus.Level, error) {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "verbose", "trace":
		return logrus.TraceLevel, nil
	}
	return logrus.ParseLevel(name)
}
