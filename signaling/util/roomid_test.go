package util

import (
	"strings"
	"testing"
)

// TestGenerateRoomID 校验生成规则（doc/09 §7：6 字符、大写字母+数字、排除易混淆字符）。
func TestGenerateRoomID(t *testing.T) {
	seen := make(map[string]bool)
	for i := 0; i < 2000; i++ {
		id := GenerateRoomID()
		if len(id) != RoomIDLength {
			t.Fatalf("roomId 长度应为 %d，实际 %d（%q）", RoomIDLength, len(id), id)
		}
		if !IsValidRoomID(id) {
			t.Fatalf("生成的 roomId 未通过校验：%q", id)
		}
		for _, bad := range []string{"O", "0", "I", "1", "L"} {
			if strings.Contains(id, bad) {
				t.Fatalf("roomId %q 含易混淆字符 %q", id, bad)
			}
		}
		seen[id] = true
	}
	// 6 字符、31 个候选字符：2000 次采样几乎不可能只出现极少数组合
	if len(seen) < 1900 {
		t.Fatalf("随机性异常：2000 次仅生成 %d 个不同 roomId", len(seen))
	}
}

// TestIsValidRoomID 校验格式判断。
func TestIsValidRoomID(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"A2B3C4", true},
		{"ABCDEF", true},
		{"234567", true},
		{"A2B3C", false},   // 太短
		{"A2B3C4D", false}, // 太长
		{"A2B3C0", false},  // 含易混淆字符 0
		{"A2B3C1", false},  // 含易混淆字符 1
		{"A2B3CL", false},  // 含易混淆字符 L
		{"A2B3C-", false},  // 非法字符
		{"a2b3c4", false},  // 小写（需先 Normalize）
		{"", false},
	}
	for _, c := range cases {
		if got := IsValidRoomID(c.in); got != c.want {
			t.Errorf("IsValidRoomID(%q) = %v，期望 %v", c.in, got, c.want)
		}
	}
}

// TestNormalizeRoomID 校验手输房间号的宽松处理。
func TestNormalizeRoomID(t *testing.T) {
	cases := map[string]string{
		" a1b2c3 ": "A1B2C3",
		"A1B2C3":   "A1B2C3",
		"\tab2cd3": "AB2CD3",
	}
	for in, want := range cases {
		if got := NormalizeRoomID(in); got != want {
			t.Errorf("NormalizeRoomID(%q) = %q，期望 %q", in, got, want)
		}
	}
}
