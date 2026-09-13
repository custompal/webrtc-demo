// Package util 提供与信令协议相关的纯函数工具。
package util

import (
	"crypto/rand"
	"math/big"
	"strings"
	"time"
)

// RoomIDLength roomId 长度（doc/09 §7：6 字符）。
const RoomIDLength = 6

// roomIDCharset 是 roomId 字符集：大写字母 + 数字，排除易混淆字符
// O/0/I/1/L（doc/09 §7 要求排除 O/0/I/1；doc/12 §5 进一步排除 L，
// 本实现采用更严格的 doc/12 版本，仍满足 doc/09 的 JSON Schema
// ^[A-Z2-9]{6}$）。
const roomIDCharset = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

// GenerateRoomID 生成 6 字符随机 roomId。
//
// 使用 crypto/rand 而非数学随机：无需种子、天然并发安全、结果不可预测
// （房间号会通过口头/IM 传递，可预测的房间号意味着可被随意加入）。
// 采用拒绝采样保证均匀分布。
func GenerateRoomID() string {
	buf := make([]byte, RoomIDLength)
	max := big.NewInt(int64(len(roomIDCharset)))
	for i := range buf {
		n, err := rand.Int(rand.Reader, max)
		if err != nil {
			// crypto/rand 不可用属环境级故障：退化到时间驱动的回退，
			// 保证服务不因随机源故障而完全不可用（极小概率路径）。
			buf[i] = roomIDCharset[(int(time.Now().UnixNano()>>uint(i*4))+i)%len(roomIDCharset)]
			continue
		}
		buf[i] = roomIDCharset[n.Int64()]
	}
	return string(buf)
}

// IsValidRoomID 校验 roomId 格式（长度 6、字符集内）。
func IsValidRoomID(id string) bool {
	if len(id) != RoomIDLength {
		return false
	}
	for i := 0; i < len(id); i++ {
		if !strings.ContainsRune(roomIDCharset, rune(id[i])) {
			return false
		}
	}
	return true
}

// NormalizeRoomID 归一化用户输入：去空白 + 转大写。
// 客户端手输房间号时常见小写/带空格，这里做宽松处理（不改变服务端生成规则）。
func NormalizeRoomID(id string) string {
	return strings.ToUpper(strings.TrimSpace(id))
}
