package logging

import (
	"os"
	"path/filepath"
	"testing"
)

// TestRotatingWriter 校验 2 MiB×3 的滚动命名与保留策略（用小上限加速）。
func TestRotatingWriter(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "signaling.log")

	const maxBytes = 100
	const maxFiles = 3
	w, err := NewRotatingWriter(path, maxBytes, maxFiles)
	if err != nil {
		t.Fatalf("NewRotatingWriter 失败: %v", err)
	}
	defer w.Close()

	line := make([]byte, 40)
	for i := range line {
		line[i] = 'x'
	}
	line[39] = '\n'

	// 每写一条 40B：第 1、2 条落 signaling.log；第 3 条触发滚动 → .1
	for i := 0; i < 8; i++ {
		if _, err := w.Write(line); err != nil {
			t.Fatalf("第 %d 次写入失败: %v", i, err)
		}
	}
	if w.Size() > maxBytes {
		t.Fatalf("当前文件超过上限：%d > %d", w.Size(), maxBytes)
	}

	for _, name := range []string{"signaling.log", "signaling.log.1", "signaling.log.2"} {
		p := filepath.Join(dir, name)
		st, err := os.Stat(p)
		if err != nil {
			t.Fatalf("滚动后缺少文件 %s: %v", name, err)
		}
		if st.Size() > maxBytes {
			t.Fatalf("%s 超过上限：%d", name, st.Size())
		}
	}
	// 最多保留 maxFiles 个（当前 + 2 历史）
	entries, _ := os.ReadDir(dir)
	if len(entries) > maxFiles {
		t.Fatalf("保留文件数应 ≤ %d，实际 %d", maxFiles, len(entries))
	}
}

// TestRotatingWriterCreatesDir 校验多级目录自动创建。
func TestRotatingWriterCreatesDir(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "a", "b", "signaling.log")
	w, err := NewRotatingWriter(path, DefaultMaxBytes, DefaultMaxFiles)
	if err != nil {
		t.Fatalf("应自动创建目录: %v", err)
	}
	defer w.Close()
	if _, err := w.Write([]byte("hello\n")); err != nil {
		t.Fatalf("写入失败: %v", err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("日志文件应存在: %v", err)
	}
}
