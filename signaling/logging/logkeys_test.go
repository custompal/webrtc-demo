package logging

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// TestSourceLogKeysAreSnakeCase 是 C30 / doc/14 §11.4 D-2 的机械化守卫：
//
//	doc/14 §9.1 的 k=v 键名（= logrus 的 Fields 键）必须是 snake_case；
//	该约束**只**作用于日志键名，不作用于协议 JSON 字段名
//	（协议字段必须保持 doc/09 的 camelCase，见 server 包的端到端断言）。
//
// 本用例直接扫描本模块源码里所有 logrus 字段键（`logrus.Fields{...}` 的 map 键
// 与 `WithField("...")` 的第一个参数），任何 camelCase 键都会让测试失败。
func TestSourceLogKeysAreSnakeCase(t *testing.T) {
	root := ".." // 包目录的上一级 = module 根 webrtcdemo-signaling

	reWithField := regexp.MustCompile(`WithField\("([^"]+)"`)
	reFieldsKey := regexp.MustCompile(`"([A-Za-z][A-Za-z0-9_]*)":`)
	reFieldsOpen := regexp.MustCompile(`logrus\.Fields\{`)
	reSnake := regexp.MustCompile(`^[a-z][a-z0-9_]*$`)

	var checked int
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		src := string(data)

		// 1) WithField("key", ...)
		for _, m := range reWithField.FindAllStringSubmatch(src, -1) {
			checked++
			if !reSnake.MatchString(m[1]) {
				t.Errorf("%s: 日志键 %q 不是 snake_case（C30）", path, m[1])
			}
		}

		// 2) logrus.Fields{ ... } 内的 map 键（用花括号配对切出整块）
		for _, loc := range reFieldsOpen.FindAllStringIndex(src, -1) {
			block := braceBlock(src, loc[0]+len("logrus.Fields"))
			for _, m := range reFieldsKey.FindAllStringSubmatch(block, -1) {
				checked++
				if !reSnake.MatchString(m[1]) {
					t.Errorf("%s: logrus.Fields 键 %q 不是 snake_case（C30）", path, m[1])
				}
			}
		}
		return nil
	})
	if err != nil {
		t.Fatalf("扫描源码失败: %v", err)
	}
	if checked < 30 {
		t.Fatalf("扫描到的日志键过少（%d），守卫可能失效", checked)
	}
	t.Logf("已校验 %d 个日志字段键，全部为 snake_case", checked)
}

// braceBlock 从 s[start] == '{' 开始，返回配对花括号内的内容（含大括号）。
func braceBlock(s string, start int) string {
	if start >= len(s) || s[start] != '{' {
		return ""
	}
	depth := 0
	for i := start; i < len(s); i++ {
		switch s[i] {
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return s[start : i+1]
			}
		}
	}
	return s[start:]
}
