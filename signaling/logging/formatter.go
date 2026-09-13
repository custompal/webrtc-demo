// Package logging 实现契约 doc/14-interface-contract.md §9 要求的日志输出。
//
// 统一行格式（§9.1，三层一致）：
//
//		<ts> <LEVEL> <layer> <tag> [<pid>/<tid>] <message>[ key=value ...]
//
//	  - ts     : UTC，毫秒精度，字面量 Z（如 2026-09-13T07:29:20.020Z）
//	  - LEVEL  : VERBOSE|DEBUG|INFO|WARN|ERROR，左对齐定宽 7
//	  - layer  : kotlin|native|go|webrtc，左对齐定宽 6（本包固定 go）
//	  - tag    : ≤24 字符 snake_case 模块名，左对齐定宽 10
//	  - [pid/-]: Go 无 goroutine id，契约允许 [<pid>/-]（登记为格式允许差异）
//	  - key=value：行尾追加，key 为 snake_case，值不含空格（空格转 `_`），按键字典序
package logging

import (
	"fmt"
	"os"
	"sort"
	"strings"
	"time"
	"unicode"

	"github.com/sirupsen/logrus"
)

// 格式常量（doc/14 §9.1）。
const (
	// LayerGo 是本层在日志中的 layer 名。
	LayerGo = "go"
	// TimestampLayout 是契约规定的 UTC 毫秒时间戳格式（字面量 Z）。
	TimestampLayout = "2006-01-02T15:04:05.000Z07:00"

	levelWidth = 7
	layerWidth = 6
	tagWidth   = 9
	maxTagLen  = 24
)

// 常用 tag（契约 §9.1 允许的模块名子集）。
const (
	TagMain      = "main"      // 进程生命周期：启动、监听、关闭
	TagSignaling = "signaling" // WebSocket 连接与消息转发
	TagRoom      = "room"      // 房间生命周期与路由错误
)

// Formatter 输出 doc/14 §9.1 的统一行格式。
//
// 说明：契约 §9.3 写的是「自定义 TextFormatter」，但 logrus 内置 TextFormatter
// 无法产出 §9.1 的列式布局（layer/tag 列 + [pid/-]）；这里实现 logrus.Formatter
// 接口，产出的**输出格式与 §9.1 完全一致**，功能项（禁色、全时间戳、不截断级别名）
// 均天然满足。
type Formatter struct {
	// PID 可注入用于测试；为 0 时取当前进程号。
	PID int
}

// Format 实现 logrus.Formatter。
func (f Formatter) Format(entry *logrus.Entry) ([]byte, error) {
	pid := f.PID
	if pid == 0 {
		pid = os.Getpid()
	}

	ts := entry.Time
	if ts.IsZero() {
		ts = time.Now()
	}

	layer := LayerGo
	tag := TagMain
	fields := make([]string, 0, len(entry.Data))
	for k, v := range entry.Data {
		switch k {
		case "layer":
			layer = toString(v)
			continue
		case "tag":
			tag = toString(v)
			continue
		}
		fields = append(fields, k+"="+sanitizeValue(toString(v)))
	}
	// 契约：按键字典序（便于 diff 与 grep）
	sort.Strings(fields)

	var b strings.Builder
	b.WriteString(ts.UTC().Format(TimestampLayout))
	b.WriteByte(' ')
	b.WriteString(padRight(LevelName(entry.Level), levelWidth))
	b.WriteByte(' ')
	b.WriteString(padRight(layer, layerWidth))
	b.WriteByte(' ')
	b.WriteString(padRight(truncate(tag, maxTagLen), tagWidth))
	fmt.Fprintf(&b, " [%d/-] %s", pid, sanitizeValue(entry.Message))
	for _, kv := range fields {
		b.WriteByte(' ')
		b.WriteString(kv)
	}
	b.WriteByte('\n')
	return []byte(b.String()), nil
}

// LevelName 把 logrus 级别映射为契约的 LEVEL 名称。
func LevelName(l logrus.Level) string {
	switch l {
	case logrus.TraceLevel:
		return "VERBOSE"
	case logrus.DebugLevel:
		return "DEBUG"
	case logrus.InfoLevel:
		return "INFO"
	case logrus.WarnLevel:
		return "WARN"
	case logrus.ErrorLevel, logrus.FatalLevel, logrus.PanicLevel:
		return "ERROR"
	}
	return "INFO"
}

// sanitizeValue 把值里的空白替换为 `_`（契约：value 不得含空格），空值输出 `-`。
func sanitizeValue(s string) string {
	s = strings.Map(func(r rune) rune {
		switch {
		case r == ' ', r == '\t', r == '\n', r == '\r':
			return '_'
		case unicode.IsControl(r):
			return -1
		}
		return r
	}, s)
	if s == "" {
		return "-"
	}
	return s
}

func toString(v interface{}) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprint(v)
}

func padRight(s string, width int) string {
	n := len([]rune(s))
	if n >= width {
		return s
	}
	return s + strings.Repeat(" ", width-n)
}

func truncate(s string, max int) string {
	r := []rune(s)
	if len(r) <= max {
		return s
	}
	return string(r[:max])
}
