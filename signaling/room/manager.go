// 房间管理器：rooms map 的并发安全实现 + 过期房间清理（doc/09 §7、doc/12 §8）。
package room

import (
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sirupsen/logrus"

	"webrtcdemo-signaling/config"
	"webrtcdemo-signaling/logging"
	"webrtcdemo-signaling/util"
)

// 管理器对外暴露的哨兵错误，由 server 层映射为协议错误码。
var (
	// ErrRoomNotFound 房间不存在（→ ROOM_NOT_FOUND）。
	ErrRoomNotFound = errors.New("room not found")
	// ErrRoomFull 房间已满（→ ROOM_FULL）。
	ErrRoomFull = errors.New("room is full")
	// ErrRoomExpired 房间已过期（→ ROOM_EXPIRED）。
	ErrRoomExpired = errors.New("room expired")
)

// maxRoomIDAttempts 生成唯一 roomId 的最大重试次数（6 字符 32 进制，冲突概率极低）。
const maxRoomIDAttempts = 64

// Manager 管理全部房间的生命周期。
//
// 锁顺序约定：Manager.mu → Room.mu（单向）。任何需要通知外部（server）的动作
// 都通过返回值或回调在释放锁之后执行，避免与 Room 的锁形成环。
type Manager struct {
	cfg *config.Config
	log *logrus.Logger

	mu    sync.RWMutex
	rooms map[string]*Room

	stopCh   chan struct{}
	stopOnce sync.Once

	// expiredHandler 在房间因过期被清理时被调用（已在锁外），
	// 由 server 负责关闭其中 peer 的连接并下发 ROOM_EXPIRED。
	expiredHandler func(*Room, []*Peer)
	// graceExpiredHandler 在某个席位的宽限期满、且**确实**被回收时被调用（已在锁外），
	// 由 server 给仍在线对端下发一次 peerLeft（t67）。
	graceExpiredHandler func(r *Room, offline *Peer, other *Peer)
	handlerMu           sync.RWMutex

	createdTotal   atomic.Int64
	destroyedTotal atomic.Int64
	graceExpired   atomic.Int64
	seatTakeovers  atomic.Int64
}

// NewManager 创建管理器并启动过期清理协程。
func NewManager(cfg *config.Config, log *logrus.Logger) *Manager {
	if log == nil {
		log = logrus.New()
	}
	m := &Manager{
		cfg:    cfg,
		log:    log,
		rooms:  make(map[string]*Room),
		stopCh: make(chan struct{}),
	}
	go m.cleanupLoop()
	return m
}

// SetExpiredHandler 注册房间过期回调（必须在并发进入前调用一次）。
func (m *Manager) SetExpiredHandler(fn func(*Room, []*Peer)) {
	m.handlerMu.Lock()
	m.expiredHandler = fn
	m.handlerMu.Unlock()
}

// SetGraceExpiredHandler 注册「席位宽限期满被回收」回调（t67）。
// 回调在锁外执行；server 据此给仍在线对端**只发一次** peerLeft。
func (m *Manager) SetGraceExpiredHandler(fn func(r *Room, offline *Peer, other *Peer)) {
	m.handlerMu.Lock()
	m.graceExpiredHandler = fn
	m.handlerMu.Unlock()
}

// Stop 停止清理协程。
func (m *Manager) Stop() {
	m.stopOnce.Do(func() { close(m.stopCh) })
}

// Count 返回当前房间数。
func (m *Manager) Count() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.rooms)
}

// RoomIDs 返回当前全部 roomId（供 /healthz 与排查使用）。
func (m *Manager) RoomIDs() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]string, 0, len(m.rooms))
	for id := range m.rooms {
		out = append(out, id)
	}
	return out
}

// Stats 返回累计创建/销毁房间数。
func (m *Manager) Stats() (created, destroyed int64) {
	return m.createdTotal.Load(), m.destroyedTotal.Load()
}

// CreateRoom 创建新房间并把 peer 作为 peer-001 放入，返回房间与 peerId。
func (m *Manager) CreateRoom(p *Peer) (*Room, string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	var roomID string
	for i := 0; i < maxRoomIDAttempts; i++ {
		candidate := util.GenerateRoomID()
		if _, exists := m.rooms[candidate]; !exists {
			roomID = candidate
			break
		}
	}
	if roomID == "" {
		m.log.WithField("tag", logging.TagRoom).WithField("reason", "room_id_retry_exhausted").Error("room_created_failed")
		return nil, "", ErrRoomExpired
	}

	r := NewRoom(roomID, m.cfg.RoomExpirySec)
	peerID, _, _, ok := r.AddPeer(p, m.cfg.RoomGrace, m.graceCallback(r, p))
	if !ok {
		// 新房间必然有空位；走到这里说明逻辑异常
		return nil, "", ErrRoomFull
	}
	m.rooms[roomID] = r
	m.createdTotal.Add(1)

	m.log.WithFields(logrus.Fields{
		"tag":        logging.TagRoom,
		"room":       roomID,
		"peer":       peerID,
		"role":       "host",
		"remote":     p.RemoteAddr(),
		"room_count": len(m.rooms),
	}).Info("room_created")
	return r, peerID, nil
}

// JoinRoom 把 peer 加入指定房间，返回房间与其 peerId。
//
// 错误语义（doc/09 §3.10）：
//   - 房间不存在 → ErrRoomNotFound
//   - 房间已过期 → ErrRoomExpired（同时销毁该房间）
//   - 房间已满   → ErrRoomFull（**注意**：若满员席位里有处于宽限期的席位，
//     则不算满——新连接会接管该席位，见 t67）
func (m *Manager) JoinRoom(roomID string, p *Peer) (*Room, string, error) {
	m.mu.RLock()
	r, exists := m.rooms[roomID]
	m.mu.RUnlock()
	if !exists {
		return nil, "", ErrRoomNotFound
	}

	if r.IsExpired(time.Now()) {
		if m.RemoveRoom(roomID, "expired-before-join") {
			m.log.WithField("tag", logging.TagRoom).WithField("room", roomID).
				Warn("join_expired_before_rejected")
		}
		return nil, "", ErrRoomExpired
	}

	peerID, takenOver, prev, ok := r.AddPeer(p, m.cfg.RoomGrace, m.graceCallback(r, p))
	if !ok {
		return nil, "", ErrRoomFull
	}
	if prev != nil {
		// 被接管席位的旧连接（WS 已断）不再属于本房间
		prev.SetRoom(nil)
		m.seatTakeovers.Add(1)
	}

	// 并发窗口兜底：AddPeer 期间房间可能已被清理协程删除，此时回滚。
	m.mu.RLock()
	stillThere := m.rooms[roomID] == r
	m.mu.RUnlock()
	if !stillThere {
		r.RemovePeer(p)
		p.SetRoom(nil)
		return nil, "", ErrRoomNotFound
	}

	entry := m.log.WithFields(logrus.Fields{
		"tag":    logging.TagRoom,
		"room":   roomID,
		"peer":   peerID,
		"peers":  r.PeerCount(),
		"remote": p.RemoteAddr(),
	})
	if takenOver {
		entry = entry.WithField("seat_takeover", true).
			WithField("prev_remote", prev.RemoteAddr())
		entry.Info("seat_takeover")
		return r, peerID, nil
	}
	entry.Info("room_joined")
	return r, peerID, nil
}

// graceCallback 生成该席位的宽限期满回调（携带代次，过期回调会被 Room 丢弃）。
func (m *Manager) graceCallback(r *Room, p *Peer) func(gen uint64) {
	return func(gen uint64) { m.expireGrace(r.ID, p, gen) }
}

// MarkOffline 处理「WS 断开」：**不立即离开房间**，而是把席位标记为宽限期
// （默认 config.RoomGrace），保留房间与席位、且**不给在线对端发 peerLeft**（t67）。
//
// 返回：房间、对端（仅用于日志）、实际使用的宽限期、以及是否走了「立即移除」旧路径。
// 当 config.RoomGrace <= 0 时退化为 t67 之前的语义（立即移除、房间空则销毁），
// 此时 immediate=true，调用方应照旧给对端发 peerLeft。
func (m *Manager) MarkOffline(p *Peer) (r *Room, other *Peer, grace time.Duration, immediate bool) {
	r = p.Room()
	if r == nil {
		return nil, nil, 0, false
	}

	if m.cfg.RoomGrace <= 0 {
		other, empty := r.RemovePeer(p)
		p.SetRoom(nil)
		if empty {
			m.RemoveRoom(r.ID, "room-empty")
		}
		return r, other, 0, true
	}

	other, ok := r.MarkPending(p, m.cfg.RoomGrace, m.graceCallback(r, p))
	if !ok {
		// 席位已不在（例如并发显式 leave）：退回显式移除语义
		o, empty := r.RemovePeer(p)
		p.SetRoom(nil)
		if empty {
			m.RemoveRoom(r.ID, "room-empty")
		}
		return r, o, 0, true
	}
	return r, other, m.cfg.RoomGrace, false
}

// expireGrace 处理宽限期满：确认席位仍处于宽限期后回收它；
// 若房间因此为空则销毁，否则回调 server 给在线对端**只发一次** peerLeft。
//
// 幂等性由 Room.ExpirePending 的代次校验保证：若期间已被重连接管或显式离开，
// 本方法直接返回、不做任何通知（因此不会出现"重连成功后又收到 peerLeft"）。
func (m *Manager) expireGrace(roomID string, p *Peer, gen uint64) {
	m.mu.RLock()
	r, ok := m.rooms[roomID]
	m.mu.RUnlock()
	if !ok {
		return
	}

	other, removed, empty := r.ExpirePending(p, gen)
	if !removed {
		return
	}
	p.SetRoom(nil)
	m.graceExpired.Add(1)

	entry := m.log.WithFields(logrus.Fields{
		"tag":    logging.TagRoom,
		"room":   roomID,
		"peer":   p.ID(),
		"remote": p.RemoteAddr(),
		"empty":  empty,
	})
	if empty {
		m.RemoveRoom(roomID, "grace-expired-empty")
		entry.Info("grace_expired_room_destroyed")
		return
	}
	entry.Warn("grace_expired")

	if other != nil {
		// 对端若同样处于宽限期（双方都瞬断），其连接已死，无需写入；等它自己的定时器处理
		if r.IsPending(other) {
			entry.Info("grace_expired_peer_also_pending")
			return
		}
		m.handlerMu.RLock()
		h := m.graceExpiredHandler
		m.handlerMu.RUnlock()
		if h != nil {
			h(r, p, other)
		}
	}
}

// StatsGrace 返回宽限期相关计数（席位接管次数、宽限期满回收次数），供 /healthz。
func (m *Manager) StatsGrace() (takeovers, expired int64) {
	return m.seatTakeovers.Load(), m.graceExpired.Load()
}

// Leave 处理显式 leave：把 peer 移出房间并立即销毁房间（doc/09 §3.8）。
//
// 返回：房间、对端（可能为 nil）、房间是否被销毁。
// 对端若仍在线，其 Room 引用也会被清空（房间已不存在），由 server 下发 peerLeft。
func (m *Manager) Leave(p *Peer) (r *Room, other *Peer, destroyed bool) {
	r = p.Room()
	if r == nil {
		return nil, nil, false
	}
	other, _ = r.RemovePeer(p)
	p.SetRoom(nil)
	if other != nil {
		other.SetRoom(nil)
		if r.IsPending(other) {
			// 对端自身也处于宽限期（WS 已断）：无需通知，避免写入已死连接
			other = nil
		}
	}
	m.RemoveRoom(r.ID, "peer-leave")
	return r, other, true
}

// Detach 立即移除 peer（不进入宽限期）：房间空了才销毁。
//
// 用途：① `config.RoomGrace <= 0` 时由 MarkOffline 走此语义；② 测试与诊断。
// t67 之后**正常的 WS 断开路径**走 MarkOffline（保留席位 + 宽限期）。
func (m *Manager) Detach(p *Peer) (r *Room, other *Peer, destroyed bool) {
	r = p.Room()
	if r == nil {
		return nil, nil, false
	}
	other, empty := r.RemovePeer(p)
	p.SetRoom(nil)
	if empty {
		m.RemoveRoom(r.ID, "room-empty")
		return r, other, true
	}
	return r, other, false
}

// RemoveRoom 从 map 中删除房间；返回是否确实删除。
func (m *Manager) RemoveRoom(roomID, reason string) bool {
	m.mu.Lock()
	r, ok := m.rooms[roomID]
	if ok {
		delete(m.rooms, roomID)
	}
	remaining := len(m.rooms)
	m.mu.Unlock()
	if !ok {
		return false
	}

	m.destroyedTotal.Add(1)
	m.log.WithFields(logrus.Fields{
		"tag":        logging.TagRoom,
		"room":       roomID,
		"reason":     reason,
		"age_s":      int(r.Age().Seconds()),
		"room_count": remaining,
	}).Info("room_destroyed")
	return true
}

// cleanupInterval 计算清理周期。
//
// 默认 60s（doc/12 §8）；当配置的过期时间很短（自测/联调场景，如 -room-expiry 3）
// 时自动缩短，否则过期房间要等一分钟才被清理。
func (m *Manager) cleanupInterval() time.Duration {
	const def = 60 * time.Second
	d := time.Duration(m.cfg.RoomExpirySec) * time.Second / 4
	if d >= def {
		return def
	}
	if d < 200*time.Millisecond {
		return 200 * time.Millisecond
	}
	return d
}

// cleanupLoop 周期性清理过期房间。
func (m *Manager) cleanupLoop() {
	interval := m.cleanupInterval()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	m.log.WithField("tag", logging.TagRoom).WithField("interval_ms", interval.Milliseconds()).
		Info("room_cleanup_start")

	for {
		select {
		case <-m.stopCh:
			m.log.WithField("tag", logging.TagRoom).Info("room_cleanup_stop")
			return
		case <-ticker.C:
			m.sweep()
		}
	}
}

// sweep 执行一轮过期清理；过期房间的 peers 交由回调在锁外处理。
func (m *Manager) sweep() {
	now := time.Now()

	m.mu.RLock()
	var expired []*Room
	for _, r := range m.rooms {
		if r.IsExpired(now) {
			expired = append(expired, r)
		}
	}
	m.mu.RUnlock()

	for _, r := range expired {
		peers := r.LivePeers() // 只通知仍有活动连接的 peer（宽限期席位由各自回调处理）
		if !m.RemoveRoom(r.ID, "expired") {
			continue // 已被其它路径删除
		}
		m.log.WithFields(logrus.Fields{
			"tag":      logging.TagRoom,
			"room":     r.ID,
			"peers":    len(peers),
			"age_s":    int(r.Age().Seconds()),
			"expiry_s": m.cfg.RoomExpirySec,
		}).Warn("room_expired")

		for _, p := range peers {
			p.SetRoom(nil)
		}
		m.handlerMu.RLock()
		h := m.expiredHandler
		m.handlerMu.RUnlock()
		if h != nil && len(peers) > 0 {
			h(r, peers)
		}
	}
}
