// Package server 提供信令服务的 HTTP/WebSocket 服务层。
package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/sirupsen/logrus"

	"webrtcdemo-signaling/config"
	"webrtcdemo-signaling/logging"
	"webrtcdemo-signaling/room"
)

// Version 是信令服务版本号，出现在日志与 /healthz 中。
const Version = "0.1.0"

// PathWS 是权威的 WebSocket 端点（doc/09 §1：wss://<HOST>/ws）。
const PathWS = "/ws"

// PathHealthz 是健康检查端点（便于 systemd/联调时 curl 探活）。
const PathHealthz = "/healthz"

// Server 聚合配置、房间管理器与 HTTP 服务。
type Server struct {
	cfg     *config.Config
	log     *logrus.Logger
	manager *room.Manager

	upgrader websocket.Upgrader

	httpSrv   *http.Server
	startedAt time.Time

	// 指标：累计连接数 / 当前活跃连接数
	totalConns  atomic.Int64
	activeConns atomic.Int64
}

// NewServer 构造服务，并注册房间过期回调。
func NewServer(cfg *config.Config, log *logrus.Logger) *Server {
	if log == nil {
		log = logrus.New()
	}
	s := &Server{
		cfg:       cfg,
		log:       log,
		manager:   room.NewManager(cfg, log),
		startedAt: time.Now(),
		upgrader: websocket.Upgrader{
			// 学习 Demo 不校验 Origin（doc/12 §9 的 CheckOrigin 约定）。
			// 公网部署时若担心跨站滥用，可在 Caddy 层做来源限制。
			CheckOrigin:      func(r *http.Request) bool { return true },
			HandshakeTimeout: 10 * time.Second,
			ReadBufferSize:   4096,
			WriteBufferSize:  4096,
		},
	}
	s.manager.SetExpiredHandler(s.handleRoomExpired)
	s.manager.SetGraceExpiredHandler(s.handleGraceExpired) // t67：席位宽限期满才通知 peerLeft
	return s
}

// Manager 暴露房间管理器（测试与健康检查使用）。
func (s *Server) Manager() *room.Manager { return s.manager }

// Routes 返回注册好全部路由的 mux。
//
// 端点（doc/14 §8.1 冻结）：/ws（唯一信令端点，C11 裁定 /signal 作废）、/healthz、/。
func (s *Server) Routes() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.HandleWS)
	mux.HandleFunc(PathHealthz, s.HandleHealthz)
	mux.HandleFunc("/", s.handleRoot)
	return mux
}

// Handler 返回可直接交给 httptest / http.Server 使用的 handler。
func (s *Server) Handler() http.Handler { return s.Routes() }

// handleRoot 给非 WebSocket 路径一个明确的提示。
func (s *Server) handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		s.log.WithFields(logrus.Fields{
			"tag": logging.TagSignaling, "path": r.URL.Path, "remote": r.RemoteAddr,
		}).Warn("http_unknown_path")
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	fmt.Fprintf(w, "webrtcdemo signaling %s\nendpoints:\n  %s   WebSocket (RFC6455)\n  %s     健康检查\n",
		Version, PathWS, PathHealthz)
}

// HandleHealthz 返回服务状态 JSON，供部署/联调探活。
func (s *Server) HandleHealthz(w http.ResponseWriter, r *http.Request) {
	created, destroyed := s.manager.Stats()
	takeovers, graceExpired := s.manager.StatsGrace()
	body := map[string]interface{}{
		"status":           "ok",
		"version":          Version,
		"addr":             s.cfg.ListenAddr,
		"uptimeSec":        int(time.Since(s.startedAt).Seconds()),
		"rooms":            s.manager.Count(),
		"roomIds":          s.manager.RoomIDs(),
		"activeConns":      s.activeConns.Load(),
		"totalConns":       s.totalConns.Load(),
		"roomsCreated":     created,
		"roomsDestroyed":   destroyed,
		"roomExpirySec":    s.cfg.RoomExpirySec,
		"roomGraceSec":     int(s.cfg.RoomGrace.Seconds()),
		"seatTakeovers":    takeovers,
		"graceExpired":     graceExpired,
		"maxMessageBytes":  s.cfg.MaxMessageSize,
		"stunUrl":          s.cfg.StunURL,
		"turnUrl":          s.cfg.TurnURL,
		"serverTimeMillis": time.Now().UnixMilli(),
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	if err := enc.Encode(body); err != nil {
		s.log.WithField("tag", logging.TagSignaling).WithError(err).Warn("healthz_encode_failed")
	}
}

// ListenAndServe 监听并开始服务；返回时表示服务已停止（错误非 nil 表示异常退出）。
func (s *Server) ListenAndServe() error {
	mux := s.Routes()
	s.httpSrv = &http.Server{
		Addr:              s.cfg.ListenAddr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}

	ln, err := net.Listen("tcp", s.cfg.ListenAddr)
	if err != nil {
		return fmt.Errorf("监听 %s 失败: %w", s.cfg.ListenAddr, err)
	}

	s.log.WithFields(logrus.Fields{
		"tag":           logging.TagMain,
		"addr":          s.cfg.ListenAddr,
		"ws_path":       PathWS,
		"health_path":   PathHealthz,
		"version":       Version,
		"actual_addr":   ln.Addr().String(),
		"max_msg_bytes": s.cfg.MaxMessageSize,
		"pong_wait_ms":  s.cfg.PongWait.Milliseconds(),
		"room_expiry_s": s.cfg.RoomExpirySec,
	}).Info("listening")

	if err := s.httpSrv.Serve(ln); err != nil && err != http.ErrServerClosed {
		return fmt.Errorf("HTTP 服务异常退出: %w", err)
	}
	return nil
}

// Shutdown 优雅停止 HTTP 服务与清理协程。
func (s *Server) Shutdown(ctx context.Context) error {
	s.log.WithField("tag", logging.TagMain).Info("shutdown_begin")
	s.manager.Stop()
	if s.httpSrv == nil {
		return nil
	}
	err := s.httpSrv.Shutdown(ctx)
	if err != nil {
		s.log.WithField("tag", logging.TagMain).WithError(err).Warn("shutdown_force")
		_ = s.httpSrv.Close()
	}
	s.log.WithField("tag", logging.TagMain).Info("shutdown_done")
	return err
}
