// 日志文件滚动写入（doc/14 §9.2/§9.3：单文件 2 MiB、保留 3 个、自实现、不引第三方）。
package logging

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// 滚动参数（契约冻结值，不可随意调整）。
const (
	// DefaultMaxBytes 单文件上限 2 MiB。
	DefaultMaxBytes int64 = 2 * 1024 * 1024
	// DefaultMaxFiles 保留文件数（当前 + 2 历史）。
	DefaultMaxFiles = 3
)

// RotatingWriter 是自实现的滚动文件写入器。
//
// 文件命名：`signaling.log`、`signaling.log.1`、`signaling.log.2`（§9.2）。
// 写前判断 `已有大小 + 本条长度 > maxBytes` → 先滚动再写，保证单文件不超上限。
// 每条日志只调用一次底层 write()（§9.2：防止多线程交错撕裂）。
type RotatingWriter struct {
	path     string
	maxBytes int64
	maxFiles int

	mu   sync.Mutex
	file *os.File
	size int64
}

// NewRotatingWriter 打开（必要时创建）日志文件。
func NewRotatingWriter(path string, maxBytes int64, maxFiles int) (*RotatingWriter, error) {
	if maxBytes <= 0 {
		maxBytes = DefaultMaxBytes
	}
	if maxFiles < 2 {
		maxFiles = DefaultMaxFiles
	}
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, fmt.Errorf("创建日志目录 %s 失败: %w", dir, err)
		}
	}
	w := &RotatingWriter{path: path, maxBytes: maxBytes, maxFiles: maxFiles}
	if err := w.open(); err != nil {
		return nil, err
	}
	return w, nil
}

func (w *RotatingWriter) open() error {
	f, err := os.OpenFile(w.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return fmt.Errorf("打开日志文件 %s 失败: %w", w.path, err)
	}
	if st, err := f.Stat(); err == nil {
		w.size = st.Size()
	}
	w.file = f
	return nil
}

// Write 实现 io.Writer：超限先滚动，再以单次 write 落盘。
func (w *RotatingWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.file == nil {
		if err := w.open(); err != nil {
			return 0, err
		}
	}
	if w.size+int64(len(p)) > w.maxBytes {
		if err := w.rotateLocked(); err != nil {
			return 0, err
		}
	}
	n, err := w.file.Write(p)
	w.size += int64(n)
	return n, err
}

// rotateLocked 滚动：删最旧 → 依次改名 → 重开当前文件。调用方必须持锁。
func (w *RotatingWriter) rotateLocked() error {
	_ = w.file.Sync()
	if err := w.file.Close(); err != nil {
		return err
	}

	// signaling.log.2 删除，.1 → .2，log → .1
	oldest := fmt.Sprintf("%s.%d", w.path, w.maxFiles-1)
	if err := os.Remove(oldest); err != nil && !os.IsNotExist(err) {
		return err
	}
	for i := w.maxFiles - 2; i >= 1; i-- {
		from := fmt.Sprintf("%s.%d", w.path, i)
		to := fmt.Sprintf("%s.%d", w.path, i+1)
		if err := os.Rename(from, to); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	if err := os.Rename(w.path, w.path+".1"); err != nil && !os.IsNotExist(err) {
		return err
	}
	return w.open()
}

// Close 关闭文件并刷盘。
func (w *RotatingWriter) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.file == nil {
		return nil
	}
	err := w.file.Close()
	_ = err
	w.file = nil
	return err
}

// Size 返回当前文件大小（测试与诊断使用）。
func (w *RotatingWriter) Size() int64 {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.size
}
