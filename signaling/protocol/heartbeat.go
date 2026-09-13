// 心跳与重连参数（doc/09 §6）。
//
// 这些参数主要约束**客户端**行为；服务端按同一时间基准配置读超时
// （config.DefaultPongWait = 3 × PingInterval），两侧保持一致。
package protocol

import "time"

const (
	// PingInterval 客户端心跳间隔：15 秒。
	PingInterval = 15 * time.Second
	// PongTimeout 客户端等待 pong 的超时：5 秒。
	PongTimeout = 5 * time.Second
	// ReconnectDelay 断线后的重连等待：3 秒。
	ReconnectDelay = 3 * time.Second
	// MaxReconnectAttempts 最大重连次数：3 次。
	MaxReconnectAttempts = 3
)
