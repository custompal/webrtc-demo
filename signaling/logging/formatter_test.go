package logging

import (
	"strings"
	"testing"
	"time"

	"github.com/sirupsen/logrus"
)

// TestFormatterContractLayout 逐字符校验 doc/14 §9.1 的行格式。
//
// 期望（契约 §9.1 示例 go 行）：
//
//	2026-09-13T07:29:20.020Z ERROR   go      room      [2301/-] join_rejected code=ROOM_FULL room=A1B2C3
func TestFormatterContractLayout(t *testing.T) {
	f := Formatter{PID: 2301}
	entry := &logrus.Entry{
		Time:    time.Date(2026, 9, 13, 7, 29, 20, 20*1e6, time.UTC),
		Level:   logrus.ErrorLevel,
		Message: "join_rejected",
		Data: logrus.Fields{
			"tag":  TagRoom,
			"room": "A1B2C3",
			"code": "ROOM_FULL",
		},
	}
	got, err := f.Format(entry)
	if err != nil {
		t.Fatalf("Format 失败: %v", err)
	}
	want := "2026-09-13T07:29:20.020Z ERROR   go     room      [2301/-] join_rejected code=ROOM_FULL room=A1B2C3\n"
	if string(got) != want {
		t.Fatalf("行格式不符合契约：\n期望 %q\n实际 %q", want, string(got))
	}
}

// TestFormatterRules 校验字段排序、空格转下划线、tag 截断与级别名映射。
func TestFormatterRules(t *testing.T) {
	f := Formatter{PID: 7}
	entry := &logrus.Entry{
		Time:    time.Date(2026, 9, 13, 7, 29, 18, 4*1e6, time.UTC),
		Level:   logrus.InfoLevel,
		Message: "ws_open",
		Data: logrus.Fields{
			"tag":    "signaling",
			"url":    "ws://47.238.144.66:8443/ws",
			"origin": "",                           // 空值 → -
			"detail": "Room A1B2C3 does not exist", // 空格 → _
			"a_key":  "v",                          // 与 b_key 一起验证字典序
			"b_key":  "v",
		},
	}
	got, _ := f.Format(entry)
	line := strings.TrimRight(string(got), "\n")

	if !strings.HasPrefix(line, "2026-09-13T07:29:18.004Z INFO    go     signaling ") {
		t.Fatalf("前缀不符合契约（LEVEL 定宽 7、layer 定宽 6、tag 定宽 10）：%q", line)
	}
	if !strings.Contains(line, "[7/-] ws_open") {
		t.Fatalf("缺少 [pid/-] 或事件名：%q", line)
	}
	if !strings.Contains(line, "detail=Room_A1B2C3_does_not_exist") {
		t.Fatalf("值中的空格未转下划线：%q", line)
	}
	if !strings.Contains(line, "origin=-") {
		t.Fatalf("空值应输出 -：%q", line)
	}
	// 字段按键字典序：a_key b_key detail origin url
	order := []string{"a_key=v", "b_key=v", "detail=", "origin=-", "url="}
	idx := -1
	for _, k := range order {
		i := strings.Index(line, k)
		if i < 0 {
			t.Fatalf("缺少字段 %s：%q", k, line)
		}
		if i < idx {
			t.Fatalf("字段未按字典序排列：%q", line)
		}
		idx = i
	}

	// tag 截断到 24 字符、定宽 10
	long := &logrus.Entry{
		Time: time.Unix(0, 0).UTC(), Level: logrus.WarnLevel, Message: "x",
		Data: logrus.Fields{"tag": strings.Repeat("t", 40)},
	}
	got2, _ := f.Format(long)
	if !strings.Contains(string(got2), strings.Repeat("t", 24)+" ") {
		t.Fatalf("tag 未截断到 24 字符：%q", string(got2))
	}

	// 级别名映射
	for lvl, want := range map[logrus.Level]string{
		logrus.TraceLevel: "VERBOSE",
		logrus.DebugLevel: "DEBUG",
		logrus.InfoLevel:  "INFO",
		logrus.WarnLevel:  "WARN",
		logrus.ErrorLevel: "ERROR",
	} {
		if got := LevelName(lvl); got != want {
			t.Fatalf("LevelName(%v) = %q，期望 %q", lvl, got, want)
		}
	}
}

// TestSetupFallback 校验日志目录/文件不可用时降级为仅终端且不报错。
func TestSetupFallback(t *testing.T) {
	logger, closeFn := Setup("/proc/definitely-not-writable/signaling.log", "info")
	defer closeFn()
	if logger.Level != logrus.InfoLevel {
		t.Fatalf("级别应为 info，实际 %v", logger.Level)
	}

	logger2, closeFn2 := Setup("", "trace")
	defer closeFn2()
	if logger2.Level != logrus.TraceLevel {
		t.Fatalf("-log 置空时应支持 trace 级别，实际 %v", logger2.Level)
	}

	if _, err := ParseLevel("verbose"); err != nil {
		t.Fatalf("verbose 应被接受: %v", err)
	}
	if _, err := ParseLevel("bogus"); err == nil {
		t.Fatal("非法级别应报错（由 Setup 降级为 info）")
	}
}
