// 命令 signaling 是 WebRTC Demo 的 Go 信令服务入口。
//
// 职责（doc/09、doc/12）：
//   - 提供 WebSocket 端点 /ws，按 roomId 中继 SDP/ICE/NAT 类型；
//   - 房间管理：6 位房间号、上限 2 人、30 分钟无人加入即过期；
//   - 向客户端下发 coturn 的 STUN/TURN 配置。
//
// 日志（用户明确要求）：logrus，同时输出终端与文件；
// 文件打不开时降级为仅终端并告警，绝不让服务起不来。
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/sirupsen/logrus"

	"webrtcdemo-signaling/config"
	"webrtcdemo-signaling/logging"
	"webrtcdemo-signaling/server"
)

func main() {
	code := run()
	os.Exit(code)
}

// run 承载真实逻辑，便于用 defer 收尾（main 里直接 os.Exit 会跳过 defer）。
func run() int {
	cfg := config.Default()

	var (
		flagAddr       = flag.String("addr", cfg.ListenAddr, "监听地址，如 :8443")
		flagStun       = flag.String("stun", cfg.StunURL, "下发给客户端的 STUN URL，如 stun:1.2.3.4:3478")
		flagTurn       = flag.String("turn", cfg.TurnURL, "下发给客户端的 TURN URL，如 turn:1.2.3.4:3478?transport=udp")
		flagUser       = flag.String("user", cfg.TurnUsername+":"+cfg.TurnCredential, "TURN 凭据，格式 用户名:密码")
		flagLog        = flag.String("log", "logs/signaling.log", "日志文件路径（目录不存在会自动创建；置空则仅输出终端）")
		flagLogLevel   = flag.String("log-level", "info", "日志级别：debug/info/warn/error")
		flagRoomExpiry = flag.Int("room-expiry", cfg.RoomExpirySec, "房间过期秒数（创建后无人加入即销毁）")
		flagRoomGrace  = flag.Duration("room-grace", cfg.RoomGrace, "WS 瞬断后保留房间与席位的宽限期（t67；默认 90s，须大于客户端重连预算；0=关闭宽限期=旧行为）")
		flagMaxMsg     = flag.Int64("max-message-bytes", cfg.MaxMessageSize, "单条 WebSocket 消息大小上限（字节，doc/09 §1：65536）")
		flagWriteTO    = flag.Duration("write-timeout", cfg.WriteTimeout, "单次 WebSocket 写超时")
		flagPongWait   = flag.Duration("pong-wait", cfg.PongWait, "读超时（ping/pong 超时判定）")
		flagSendTO     = flag.Duration("send-timeout", cfg.SendTimeout, "对端发送队列满时的投递等待上限")
		flagVersion    = flag.Bool("version", false, "打印版本号后退出")
	)
	flag.Parse()

	if *flagVersion {
		fmt.Printf("webrtcdemo-signaling %s (go build)\n", server.Version)
		return 0
	}

	cfg.ListenAddr = *flagAddr
	cfg.StunURL = *flagStun
	cfg.TurnURL = *flagTurn
	cfg.LogFile = *flagLog
	cfg.LogLevel = *flagLogLevel
	cfg.RoomExpirySec = *flagRoomExpiry
	cfg.RoomGrace = *flagRoomGrace
	cfg.MaxMessageSize = *flagMaxMsg
	cfg.WriteTimeout = *flagWriteTO
	cfg.PongWait = *flagPongWait
	cfg.SendTimeout = *flagSendTO

	// 1) 日志：先建立，保证后续任何失败都能被记录
	//    （doc/14 §9.1 行格式 + §9.2 终端/文件双写 + 2 MiB×3 滚动）
	logger, closeLog := logging.Setup(cfg.LogFile, cfg.LogLevel)
	defer closeLog()

	if err := cfg.SetTurnUser(*flagUser); err != nil {
		logger.WithField("tag", logging.TagMain).WithError(err).WithField("field", "turn_user").Error("config_invalid")
		return 2
	}
	if err := cfg.Validate(); err != nil {
		logger.WithField("tag", logging.TagMain).WithError(err).Error("config_invalid")
		return 2
	}

	logger.WithFields(logrus.Fields{
		"tag":              logging.TagMain,
		"version":          server.Version,
		"listen_addr":      cfg.ListenAddr,
		"stun_url":         cfg.StunURL,
		"turn_url":         cfg.TurnURL,
		"turn_user":        cfg.TurnUsername,
		"log_file":         cfg.LogFile,
		"log_level":        cfg.LogLevel,
		"room_expiry_s":    cfg.RoomExpirySec,
		"room_grace_ms":    cfg.RoomGrace.Milliseconds(),
		"max_msg_bytes":    cfg.MaxMessageSize,
		"pong_wait_ms":     cfg.PongWait.Milliseconds(),
		"write_timeout_ms": cfg.WriteTimeout.Milliseconds(),
		"send_timeout_ms":  cfg.SendTimeout.Milliseconds(),
		"pid":              os.Getpid(),
	}).Info("server_start")
	for _, w := range cfg.Warnings() {
		logger.WithField("tag", logging.TagMain).WithField("warning", w).Warn("config_warning")
	}

	// 2) 起服务
	srv := server.NewServer(cfg, logger)
	errCh := make(chan error, 1)
	go func() { errCh <- srv.ListenAndServe() }()

	// 3) 等待信号或异常退出
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case err := <-errCh:
		if err != nil {
			logger.WithField("tag", logging.TagMain).WithError(err).WithField("code", "1").Error("server_exit")
			return 1
		}
		logger.WithField("tag", logging.TagMain).WithField("code", "0").Info("server_exit")
		return 0
	case sig := <-sigCh:
		logger.WithField("tag", logging.TagMain).WithField("signal", sig.String()).Info("signal_received")
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := srv.Shutdown(ctx); err != nil {
			logger.WithField("tag", logging.TagMain).WithError(err).Warn("shutdown_incomplete")
			return 1
		}
		return 0
	}
}
