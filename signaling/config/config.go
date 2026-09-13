// Package config 定义信令服务的运行配置与默认值。
//
// 默认值取自 doc/12 §4 与 doc/09 §1/§7：
//   - 监听 8443（Caddy 反代 443 → 8443）
//   - 单条消息上限 64KB
//   - 房间 30 分钟无人加入即过期，最多 2 人
package config

import (
	"fmt"
	"strings"
	"time"

	"webrtcdemo-signaling/protocol"
)

// 默认值与协议约束常量。
const (
	DefaultListenAddr     = ":8443"
	DefaultStunURL        = "stun:1.2.3.4:3478"
	DefaultTurnURL        = "turn:1.2.3.4:3478?transport=udp"
	DefaultTurnUsername   = "demo"
	DefaultTurnCredential = "demopass"

	// DefaultRoomExpirySec 房间过期时间：创建后 30 分钟无人加入即销毁（doc/09 §7）。
	DefaultRoomExpirySec = 1800

	// MaxMessageSize 单条消息最大字节数：64KB（doc/09 §1）。
	MaxMessageSize = 64 * 1024

	// DefaultWriteTimeout 单次写超时。
	DefaultWriteTimeout = 10 * time.Second

	// DefaultPongWait 服务端读超时（ping/pong 超时判定）。
	//
	// doc/09 §6 规定客户端每 15s 发一次 ping、5s 收不到 pong 视为断开。
	// 服务端侧取 3 个心跳周期（= 45s），容忍连续丢包，避免移动网络抖动误杀连接。
	DefaultPongWait = 3 * protocol.PingInterval

	// DefaultSendTimeout 是对端发送队列满时的投递等待上限。
	// 信令丢 ICE 会导致建连失败，因此使用「等待 + 超时」而非「满即丢」。
	DefaultSendTimeout = 5 * time.Second

	// PlaceholderHost 是 doc/01、doc/12 中的示例 IP，启动时若仍是它说明未按实际部署配置。
	PlaceholderHost = "1.2.3.4"
)

// Config 是信令服务的完整配置。
type Config struct {
	ListenAddr     string        // 监听地址，如 ":8443"
	StunURL        string        // 下发给客户端的 STUN URL
	TurnURL        string        // 下发给客户端的 TURN URL
	TurnUsername   string        // TURN 用户名（全局共享）
	TurnCredential string        // TURN 密码（全局共享）
	RoomExpirySec  int           // 房间过期秒数
	MaxMessageSize int64         // 单条消息最大字节数
	WriteTimeout   time.Duration // 单次 WebSocket 写超时
	PongWait       time.Duration // 读超时（ping/pong 超时）
	SendTimeout    time.Duration // 对端发送队列满时的投递等待上限
	LogFile        string        // 日志文件路径（空 = 仅终端）
	LogLevel       string        // 日志级别
}

// Default 返回 doc/12 §4 的默认配置。
func Default() *Config {
	return &Config{
		ListenAddr:     DefaultListenAddr,
		StunURL:        DefaultStunURL,
		TurnURL:        DefaultTurnURL,
		TurnUsername:   DefaultTurnUsername,
		TurnCredential: DefaultTurnCredential,
		RoomExpirySec:  DefaultRoomExpirySec,
		MaxMessageSize: MaxMessageSize,
		WriteTimeout:   DefaultWriteTimeout,
		PongWait:       DefaultPongWait,
		SendTimeout:    DefaultSendTimeout,
		LogLevel:       "info",
	}
}

// SetTurnUser 解析 "-user 用户名:密码" 形式的命令行参数。
func (c *Config) SetTurnUser(v string) error {
	parts := strings.SplitN(v, ":", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return fmt.Errorf("TURN 凭据格式应为 用户名:密码，收到 %q", v)
	}
	c.TurnUsername = parts[0]
	c.TurnCredential = parts[1]
	return nil
}

// Validate 校验配置合法性，并返回需要提醒但不致命的问题。
func (c *Config) Validate() error {
	if c.ListenAddr == "" {
		return fmt.Errorf("监听地址不能为空")
	}
	if c.StunURL == "" {
		return fmt.Errorf("STUN URL 不能为空")
	}
	if c.TurnURL == "" {
		return fmt.Errorf("TURN URL 不能为空")
	}
	if c.TurnUsername == "" || c.TurnCredential == "" {
		return fmt.Errorf("TURN 用户名/密码不能为空")
	}
	if c.RoomExpirySec <= 0 {
		return fmt.Errorf("房间过期时间必须为正数，收到 %d", c.RoomExpirySec)
	}
	if c.MaxMessageSize <= 0 {
		return fmt.Errorf("消息大小上限必须为正数，收到 %d", c.MaxMessageSize)
	}
	if c.PongWait <= 0 || c.WriteTimeout <= 0 {
		return fmt.Errorf("读/写超时必须为正数")
	}
	if c.SendTimeout <= 0 {
		return fmt.Errorf("发送等待超时必须为正数，收到 %s", c.SendTimeout)
	}
	return nil
}

// Warnings 返回非致命的配置告警（例如仍在用文档里的示例 IP）。
func (c *Config) Warnings() []string {
	var w []string
	if strings.Contains(c.StunURL, PlaceholderHost) {
		w = append(w, fmt.Sprintf("STUN URL 仍是文档示例值 %s，请用 -stun 指定实际部署地址", c.StunURL))
	}
	if strings.Contains(c.TurnURL, PlaceholderHost) {
		w = append(w, fmt.Sprintf("TURN URL 仍是文档示例值 %s，请用 -turn 指定实际部署地址", c.TurnURL))
	}
	if c.TurnCredential == DefaultTurnCredential {
		w = append(w, "TURN 凭据仍是文档示例值 demo/demopass，公网部署建议通过 -user 更换")
	}
	return w
}
