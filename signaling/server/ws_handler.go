// WebSocket 连接处理与消息路由（doc/09 §3 消息定义、§7 房间规则、§8 消息路由）。
package server

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gorilla/websocket"
	"github.com/sirupsen/logrus"

	"webrtcdemo-signaling/logging"
	"webrtcdemo-signaling/protocol"
	"webrtcdemo-signaling/room"
	"webrtcdemo-signaling/util"
)

// peerOptions 把配置转成 room.Peer 需要的网络参数。
func (s *Server) peerOptions() room.PeerOptions {
	return room.PeerOptions{
		MaxMessageBytes: s.cfg.MaxMessageSize,
		WriteTimeout:    s.cfg.WriteTimeout,
		PongWait:        s.cfg.PongWait,
		SendTimeout:     s.cfg.SendTimeout,
	}
}

// HandleWS 处理一次 WebSocket 连接的生命周期：握手 → 读循环 → 断开清理。
func (s *Server) HandleWS(w http.ResponseWriter, r *http.Request) {
	remote := r.RemoteAddr
	origin := r.Header.Get("Origin")
	path := r.URL.Path

	// 非升级请求（例如 curl 探活）：返回明确的 400，便于运维确认端点存在。
	if !websocket.IsWebSocketUpgrade(r) {
		s.log.WithFields(logrus.Fields{
			"tag": logging.TagSignaling, "path": path, "remote": remote,
			"user_agent": r.UserAgent(), "upgrade": r.Header.Get("Upgrade"),
		}).WithField("http_status", "400").Warn("ws_upgrade_rejected")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusBadRequest)
		fmt.Fprintf(w, "WebSocket upgrade required (RFC 6455). Connect to ws://%s%s\n", r.Host, PathWS)
		return
	}

	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.log.WithError(err).WithFields(logrus.Fields{
			"tag": logging.TagSignaling, "path": path, "remote": remote, "origin": origin,
		}).Warn("ws_handshake_failed")
		return
	}

	connNo := s.totalConns.Add(1)
	active := s.activeConns.Add(1)
	peer := room.NewPeer(conn, s.peerOptions(), s.log)
	peer.SetUserAgent(r.UserAgent())

	s.log.WithFields(logrus.Fields{
		"tag":          logging.TagSignaling,
		"conn":         connNo,
		"url":          path,
		"remote":       remote,
		"origin":       origin,
		"user_agent":   r.UserAgent(),
		"active_conns": active,
	}).Info("ws_open")

	// 唯一的写协程
	go peer.WriteLoop()

	// 唯一的读协程；退出即代表连接结束
	var readErr error
	peer.ReadLoop(func(data []byte) {
		s.handleMessage(peer, data)
	}, func(err error) {
		readErr = err
	})

	// ---- 断开清理 ----
	reason, level := classifyReadError(peer, readErr)
	roomRef, other, destroyed := s.manager.Detach(peer)
	peer.Close()
	left := s.activeConns.Add(-1)

	fields := logrus.Fields{
		"tag":          logging.TagSignaling,
		"conn":         connNo,
		"remote":       remote,
		"peer":         peer.ID(),
		"reason":       reason,
		"duration_ms":  time.Since(peer.ConnectedAt()).Milliseconds(),
		"active_conns": left,
	}
	if readErr != nil {
		fields["error"] = readErr.Error()
	}
	if roomRef != nil {
		fields["room"] = roomRef.ID
		fields["room_destroyed"] = destroyed
	}
	entry := s.log.WithFields(fields)
	switch {
	case reason == "ping/pong-timeout":
		entry.Warn("heartbeat_timeout")
	case level == logrus.WarnLevel:
		entry.Warn("ws_close")
	default:
		entry.Info("ws_close")
	}

	if other != nil {
		s.sendPeerLeft(other, peer.ID(), roomRef)
	}
}

// classifyReadError 把底层读错误翻译成可读原因 + 日志级别。
//
// 注意：gorilla/websocket v1.5.1 的 hideTempErr 会把底层 net.Error
// （包括 net.ErrClosed / os.ErrDeadlineExceeded）重新包成 *netError 并截断错误链，
// 因此这里优先用 peer.Closed() 判断「服务端主动关闭」，
// 用 net.Error.Timeout() 判断读超时，不做 errors.Is 的假设。
func classifyReadError(peer *room.Peer, err error) (reason string, level logrus.Level) {
	if peer != nil && peer.Closed() {
		return "closed-by-server", logrus.InfoLevel
	}
	if err == nil {
		return "closed-by-server", logrus.InfoLevel
	}
	var netErr net.Error
	if errors.Is(err, os.ErrDeadlineExceeded) || (errors.As(err, &netErr) && netErr.Timeout()) {
		return "ping/pong-timeout", logrus.WarnLevel
	}
	if errors.Is(err, net.ErrClosed) || strings.Contains(err.Error(), "use of closed network connection") {
		// 服务端主动关闭（leave / 房间过期 / 优雅退出）后读循环必然报这个错误，
		// 属预期路径，不应作为异常告警。
		return "closed-by-server", logrus.InfoLevel
	}
	if errors.Is(err, websocket.ErrReadLimit) {
		return "message-too-large", logrus.WarnLevel
	}
	if websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway, websocket.CloseNoStatusReceived) {
		return "normal-close", logrus.InfoLevel
	}
	if websocket.IsUnexpectedCloseError(err) {
		return "unexpected-close", logrus.WarnLevel
	}
	return "read-error", logrus.WarnLevel
}

// handleMessage 按 type 路由（doc/09 §8）。
func (s *Server) handleMessage(peer *room.Peer, data []byte) {
	var env protocol.Message
	if err := json.Unmarshal(data, &env); err != nil {
		s.sendError(peer, protocol.ErrInvalidMessage, "invalid JSON: "+err.Error(), "json-parse")
		return
	}
	if env.Type == "" {
		s.sendError(peer, protocol.ErrInvalidMessage, "missing required field: type", "missing-type")
		return
	}

	switch env.Type {
	case protocol.TypeCreate:
		s.handleCreate(peer)
	case protocol.TypeJoin:
		s.handleJoin(peer, data)
	case protocol.TypeOffer, protocol.TypeAnswer:
		s.forwardSDP(peer, env.Type, data)
	case protocol.TypeIce:
		s.forwardIce(peer, data)
	case protocol.TypeNatType:
		s.forwardNatType(peer, data)
	case protocol.TypeLeave:
		s.handleLeave(peer)
	case protocol.TypePing:
		s.handlePing(peer, data)
	default:
		if protocol.IsServerOnlyType(env.Type) {
			s.sendError(peer, protocol.ErrInvalidMessage,
				fmt.Sprintf("type %q is server-to-client only", env.Type), "server-only-type")
			return
		}
		s.sendError(peer, protocol.ErrInvalidMessage,
			fmt.Sprintf("unknown type %q", env.Type), "unknown-type")
	}
}

// handleCreate 处理 create：创建房间并下发 ICE 服务器配置（doc/09 §3.1）。
func (s *Server) handleCreate(peer *room.Peer) {
	if r := peer.Room(); r != nil {
		s.sendError(peer, protocol.ErrInvalidMessage,
			fmt.Sprintf("already in room %s", r.ID), "already-in-room")
		return
	}

	r, peerID, err := s.manager.CreateRoom(peer)
	if err != nil {
		s.sendError(peer, protocol.ErrInternalError, "failed to create room: "+err.Error(), "create-failed")
		return
	}
	peer.SetID(peerID)

	if err := peer.SendMessage(protocol.CreatedResponse{
		Type:           protocol.TypeCreated,
		RoomID:         r.ID,
		StunURL:        s.cfg.StunURL,
		TurnURL:        s.cfg.TurnURL,
		TurnUsername:   s.cfg.TurnUsername,
		TurnCredential: s.cfg.TurnCredential,
	}); err != nil {
		peer.Log(logging.TagRoom).WithError(err).Error("created_send_failed")
		return
	}
	peer.Log(logging.TagRoom).WithFields(logrus.Fields{
		"room":     r.ID,
		"peer":     peerID,
		"role":     "host",
		"stun_url": s.cfg.StunURL,
		"turn_url": s.cfg.TurnURL,
	}).Info("created_sent")
}

// handleJoin 处理 join：校验房间并把 join 结果/对端通知发出（doc/09 §3.2、§3.3）。
func (s *Server) handleJoin(peer *room.Peer, data []byte) {
	if r := peer.Room(); r != nil {
		s.sendError(peer, protocol.ErrInvalidMessage,
			fmt.Sprintf("already in room %s", r.ID), "already-in-room")
		return
	}

	var req protocol.JoinRequest
	if err := json.Unmarshal(data, &req); err != nil {
		s.sendError(peer, protocol.ErrInvalidMessage, "invalid join payload: "+err.Error(), "join-payload")
		return
	}
	roomID := util.NormalizeRoomID(req.RoomID)
	if roomID == "" {
		s.sendError(peer, protocol.ErrInvalidMessage, "missing required field: roomId", "missing-roomId")
		return
	}
	if !util.IsValidRoomID(roomID) {
		s.sendError(peer, protocol.ErrInvalidMessage,
			fmt.Sprintf("invalid roomId %q (expect %d chars of [A-Z2-9])", req.RoomID, util.RoomIDLength), "bad-roomId")
		return
	}

	r, peerID, err := s.manager.JoinRoom(roomID, peer)
	switch {
	case errors.Is(err, room.ErrRoomNotFound):
		s.sendError(peer, protocol.ErrRoomNotFound, fmt.Sprintf("Room %s does not exist", roomID), "join-not-found")
		return
	case errors.Is(err, room.ErrRoomExpired):
		s.sendError(peer, protocol.ErrRoomExpired, fmt.Sprintf("Room %s has expired", roomID), "join-expired")
		return
	case errors.Is(err, room.ErrRoomFull):
		s.sendError(peer, protocol.ErrRoomFull, fmt.Sprintf("Room %s is full (max 2 peers)", roomID), "join-full")
		return
	case err != nil:
		s.sendError(peer, protocol.ErrInternalError, "failed to join room: "+err.Error(), "join-failed")
		return
	}
	peer.SetID(peerID)

	if err := peer.SendMessage(protocol.JoinedResponse{
		Type:           protocol.TypeJoined,
		RoomID:         r.ID,
		StunURL:        s.cfg.StunURL,
		TurnURL:        s.cfg.TurnURL,
		TurnUsername:   s.cfg.TurnUsername,
		TurnCredential: s.cfg.TurnCredential,
		PeerID:         peerID,
	}); err != nil {
		peer.Log(logging.TagRoom).WithError(err).Error("joined_send_failed")
		return
	}
	peer.Log(logging.TagRoom).WithFields(logrus.Fields{
		"room":  r.ID,
		"peer":  peerID,
		"peers": r.PeerCount(),
	}).Info("joined_sent")

	// 通知房间内已有的一方（doc/09 §3.3：收到 peerJoined 的一方是发起方）
	other := r.OtherPeer(peer)
	if other == nil {
		peer.Log(logging.TagRoom).WithField("room", r.ID).Info("peer_joined_waiting")
		return
	}
	if err := other.SendMessage(protocol.PeerJoinedNotify{
		Type:   protocol.TypePeerJoined,
		PeerID: peerID,
	}); err != nil {
		peer.Log(logging.TagRoom).WithError(err).WithField("to_peer", other.ID()).Warn("peer_joined_send_failed")
		return
	}
	other.Log(logging.TagRoom).WithFields(logrus.Fields{
		"room":     r.ID,
		"new_peer": peerID,
		"peers":    r.PeerCount(),
	}).Info("peer_joined")
}

// forwardSDP 校验并转发 offer/answer（doc/09 §3.4/§3.5）。
func (s *Server) forwardSDP(peer *room.Peer, typ string, data []byte) {
	var msg protocol.OfferMessage
	if err := json.Unmarshal(data, &msg); err != nil {
		s.sendError(peer, protocol.ErrInvalidMessage, fmt.Sprintf("%s payload 解析失败: %v", typ, err), "sdp-payload")
		return
	}
	if strings.TrimSpace(msg.SDP) == "" {
		s.sendError(peer, protocol.ErrInvalidMessage, fmt.Sprintf("%s requires non-empty field: sdp", typ), "missing-sdp")
		return
	}
	s.forwardToPeer(peer, typ, data)
}

// forwardIce 校验并转发 ice（doc/09 §3.6）。
func (s *Server) forwardIce(peer *room.Peer, data []byte) {
	var msg protocol.IceMessage
	if err := json.Unmarshal(data, &msg); err != nil {
		s.sendError(peer, protocol.ErrInvalidMessage, "ice payload 解析失败: "+err.Error(), "ice-payload")
		return
	}
	if strings.TrimSpace(msg.Candidate) == "" {
		s.sendError(peer, protocol.ErrInvalidMessage, "ice requires non-empty field: candidate", "missing-candidate")
		return
	}
	if msg.SDPMid == "" && msg.SDPMLineIndex == nil {
		// doc/09 §3.6 备注要求两者至少一个非空；此处宽松处理（告警但仍转发），
		// 避免因 Android 端字段序列化差异中断建连。
		peer.Log(logging.TagSignaling).WithField("forward", "true").Warn("ice_missing_media_id")
	}
	s.forwardToPeer(peer, protocol.TypeIce, data)
}

// forwardNatType 校验并转发 natType（doc/09 §3.7）。
func (s *Server) forwardNatType(peer *room.Peer, data []byte) {
	var msg protocol.NatTypeMessage
	if err := json.Unmarshal(data, &msg); err != nil {
		s.sendError(peer, protocol.ErrInvalidMessage, "natType payload 解析失败: "+err.Error(), "nattype-payload")
		return
	}
	if strings.TrimSpace(msg.NatType) == "" {
		s.sendError(peer, protocol.ErrInvalidMessage, "natType requires non-empty field: natType", "missing-nattype")
		return
	}
	if !protocol.IsValidNatType(msg.NatType) {
		peer.Log(logging.TagSignaling).WithField("nat_type", msg.NatType).Warn("nattype_unknown_forwarded")
	}
	s.forwardToPeer(peer, protocol.TypeNatType, data)
}

// forwardToPeer 把消息原样转发给同房间对端（doc/09 §7）。
//
// 事件名按类型区分，便于 grep 与统计：offer_forward / answer_forward /
// ice_forward（对齐契约 §9.1 的 `ice_*` 事件族）/ nattype_forward。
func (s *Server) forwardToPeer(peer *room.Peer, typ string, data []byte) {
	r := peer.Room()
	if r == nil {
		s.sendError(peer, protocol.ErrNotInRoom,
			fmt.Sprintf("cannot send %s before create/join", typ), "not-in-room")
		return
	}

	other := r.OtherPeer(peer)
	if other == nil {
		// doc/12 §9：对端不在线时静默丢弃；这里保留一条日志便于排查建连失败。
		peer.Log(logging.TagSignaling).WithFields(logrus.Fields{
			"room":   r.ID,
			"type":   typ,
			"bytes":  len(data),
			"reason": "peer_offline",
		}).Warn("forward_dropped")
		return
	}

	if err := other.SendRaw(data); err != nil {
		peer.Log(logging.TagSignaling).WithError(err).WithFields(logrus.Fields{
			"room":   r.ID,
			"type":   typ,
			"bytes":  len(data),
			"to":     other.ID(),
			"reason": "send_timeout_or_closed",
		}).Warn("forward_dropped")
		return
	}

	peer.Log(logging.TagSignaling).WithFields(logrus.Fields{
		"room":  r.ID,
		"type":  typ,
		"bytes": len(data),
		"from":  peer.ID(),
		"to":    other.ID(),
	}).Info(forwardEventName(typ))
}

// forwardEventName 返回契约 §9.1 风格的事件名。
func forwardEventName(typ string) string {
	switch typ {
	case protocol.TypeOffer:
		return "offer_forward"
	case protocol.TypeAnswer:
		return "answer_forward"
	case protocol.TypeIce:
		return "ice_forward"
	case protocol.TypeNatType:
		return "nattype_forward"
	}
	return "msg_forward"
}

// handleLeave 处理主动 leave（doc/09 §3.8：通知对端 → 关闭连接 → 销毁房间）。
func (s *Server) handleLeave(peer *room.Peer) {
	r, other, destroyed := s.manager.Leave(peer)
	if r == nil {
		s.sendError(peer, protocol.ErrNotInRoom, "leave received before create/join", "not-in-room")
		return
	}

	peer.Log(logging.TagRoom).WithFields(logrus.Fields{
		"room":           r.ID,
		"room_destroyed": destroyed,
		"age_s":          int(r.Age().Seconds()),
		"peers_after":    r.PeerCount(),
	}).Info("leave_received")

	if other != nil {
		s.sendPeerLeft(other, peer.ID(), r)
	}
	// 关闭该 WebSocket（doc/09 §3.8 步骤 2）
	peer.Close()
}

// handlePing 处理应用层心跳（doc/09 §3.11）。
func (s *Server) handlePing(peer *room.Peer, data []byte) {
	var msg protocol.PingMessage
	if err := json.Unmarshal(data, &msg); err != nil {
		s.sendError(peer, protocol.ErrInvalidMessage, "ping payload 解析失败: "+err.Error(), "ping-payload")
		return
	}
	if msg.Timestamp == 0 {
		peer.Log(logging.TagSignaling).WithField("pong_sent", "true").Warn("ping_missing_timestamp")
	}
	if err := peer.SendMessage(protocol.PongMessage{
		Type:      protocol.TypePong,
		Timestamp: time.Now().UnixMilli(),
	}); err != nil {
		peer.Log(logging.TagSignaling).WithError(err).Warn("pong_send_failed")
		return
	}
	peer.Log(logging.TagSignaling).WithField("client_ts", msg.Timestamp).Debug("heartbeat_ping")
}

// handleRoomExpired 由房间管理器在锁外回调：房间过期后通知残留 peer 并关闭连接。
func (s *Server) handleRoomExpired(r *room.Room, peers []*room.Peer) {
	for _, p := range peers {
		if err := p.SendMessage(protocol.NewErrorWithDetail(
			protocol.ErrRoomExpired,
			fmt.Sprintf("Room %s has expired", r.ID),
		)); err != nil {
			p.Log(logging.TagRoom).WithError(err).Warn("room_expired_notify_failed")
		}
		p.Log(logging.TagRoom).WithFields(logrus.Fields{
			"room":  r.ID,
			"age_s": int(r.Age().Seconds()),
		}).Warn("room_expired_notify")
		// 给写协程一点时间把错误帧发出去，再关闭连接
		time.AfterFunc(150*time.Millisecond, p.Close)
	}
}

// sendPeerLeft 向对端下发 peerLeft（doc/09 §3.9）。
func (s *Server) sendPeerLeft(other *room.Peer, leftPeerID string, r *room.Room) {
	if other == nil {
		return
	}
	if err := other.SendMessage(protocol.PeerLeftNotify{
		Type:   protocol.TypePeerLeft,
		PeerID: leftPeerID,
	}); err != nil {
		other.Log(logging.TagRoom).WithError(err).WithField("left_peer", leftPeerID).Warn("peer_left_send_failed")
		return
	}
	fields := logrus.Fields{"left_peer": leftPeerID}
	if r != nil {
		fields["room"] = r.ID
	}
	other.Log(logging.TagRoom).WithFields(fields).Info("peer_left_sent")
}

// sendError 下发错误响应并记录错误码（doc/09 §3.10）。
//
// 事件名区分：join 失败用契约 §9.1 示例的 `join_rejected`，其余用 `error_sent`。
func (s *Server) sendError(peer *room.Peer, code, detail, reason string) {
	msg := protocol.NewErrorWithDetail(code, detail)
	if err := peer.SendMessage(msg); err != nil {
		peer.Log(logging.TagRoom).WithError(err).WithField("code", code).Warn("error_send_failed")
	}
	entry := peer.Log(logging.TagRoom).WithFields(logrus.Fields{
		"code":   code,
		"reason": reason,
		"detail": detail,
	})
	event := "error_sent"
	if strings.HasPrefix(reason, "join-") {
		event = "join_rejected"
	}
	switch code {
	case protocol.ErrInvalidMessage, protocol.ErrInternalError:
		entry.Warn(event)
	default:
		entry.Info(event)
	}
}
