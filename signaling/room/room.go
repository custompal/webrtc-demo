// 房间结构与方法（doc/09 §7 房间管理规则、§8 Room 结构）。
//
// t67 起新增「席位宽限期」：WS 断开**不再**等同离开房间——
// 席位保留、对端不下发 peerLeft，直到宽限期满（见 Manager.MarkOffline / expireGrace）。
package room

import (
	"sync"
	"time"
)

// RoomMaxPeers 房间容量上限（doc/09 §7：最多 2 人）。
const RoomMaxPeers = 2

// 两个 peer 的固定 ID（按槽位分配，doc/09 §3.2）。
const (
	PeerID1 = "peer-001"
	PeerID2 = "peer-002"
)

// seat 是一个席位：peer 为当前占用者；grace 非 nil 表示该席位处于宽限期
// （占用者 WS 已断开，但身份/座位被保留，等待其重连接管）。
//
// gen 是"代次"，用于丢弃过期的宽限定时器回调：每次进入/离开宽限期都自增，
// 回调携带启动时的代次，与当前代次不符即视为过期（例如席位已被重连接管）。
type seat struct {
	peer  *Peer
	grace *time.Timer
	gen   uint64
}

// Room 表示一个 1:1 通话房间。
//
// 并发模型：所有字段读写都必须持有 mu。为避免与 Manager 的锁形成环，
// 本结构不会在自己持锁期间调用 Manager 的任何方法（返回结果由调用方处理）；
// 也不在本结构持锁期间对同包 Peer 做可能回调本结构的操作（如 Peer.Log）。
type Room struct {
	ID        string    // roomId（6 字符）
	CreatedAt time.Time // 创建时间

	expirySec int

	mu           sync.Mutex
	seats        [RoomMaxPeers]seat
	lastActiveAt time.Time // 最近一次「有人加入/席位恢复」的时间，用于过期判定
}

// NewRoom 创建房间。
func NewRoom(id string, expirySec int) *Room {
	now := time.Now()
	return &Room{
		ID:           id,
		CreatedAt:    now,
		expirySec:    expirySec,
		lastActiveAt: now,
	}
}

// AddPeer 把 peer 放进空槽位；成功返回分配的 peerId，房间已满返回 ok=false。
//
// 与 t67 之前不同：若没有空槽位但存在**处于宽限期**的席位，则本方法会
// 「接管」该席位（取消原宽限定时器、按该席位原有 peerId 接纳新连接），
// 使瞬断的一方能用同一身份重连入会。接管发生时 takenOver=true，
// 且被替换的原 peer 会通过 prev 返回，调用方应在**释放本房间锁之后**
// 调用 prev.SetRoom(nil)。
func (r *Room) AddPeer(p *Peer, grace time.Duration, onExpire func(gen uint64)) (peerID string, takenOver bool, prev *Peer, ok bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	// 1) 优先使用空席位
	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].peer == nil {
			r.seats[i].peer = p
			r.seats[i].grace = nil
			r.seats[i].gen++
			r.lastActiveAt = time.Now()
			p.SetRoom(r)
			return peerIDAt(i), false, nil, true
		}
	}

	// 2) 没有空席位：若存在处于宽限期的席位，则接管它（同一 peerId 身份恢复）
	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].grace != nil {
			old := r.seats[i].peer
			if r.seats[i].grace.Stop() {
				// 定时器被成功取消（未触发）
			}
			r.seats[i].gen++ // 使可能已触发的旧回调失效
			r.seats[i].grace = nil
			r.seats[i].peer = p
			r.lastActiveAt = time.Now()
			p.SetRoom(r)
			return peerIDAt(i), true, old, true
		}
	}

	return "", false, nil, false
}

// peerIDAt 返回第 i 个席位的 peerId。
func peerIDAt(i int) string {
	if i == 0 {
		return PeerID1
	}
	return PeerID2
}

// MarkPending 把 peer 所在席位标记为「宽限期」（等待重连）。
//
// 返回当前的对端（可能为 nil，仅用于日志/上层决策，本方法不做任何通知）
// 以及是否成功标记。已在宽限期的席位重复调用会重置计时器（保持幂等）。
func (r *Room) MarkPending(p *Peer, grace time.Duration, onExpire func(gen uint64)) (other *Peer, ok bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].peer != p {
			continue
		}
		if r.seats[i].grace != nil {
			r.seats[i].grace.Stop()
		}
		r.seats[i].gen++
		gen := r.seats[i].gen
		r.seats[i].grace = time.AfterFunc(grace, func() {
			if onExpire != nil {
				onExpire(gen)
			}
		})
		return r.otherPeerLocked(p), true
	}
	return nil, false
}

// ExpirePending 处理某个席位的宽限期到期：
// 仅当该席位仍由 p 占用、仍处于宽限期、且代次匹配时才回收席位。
//
// 返回：剩余对端、是否确实回收、房间是否已空。
func (r *Room) ExpirePending(p *Peer, gen uint64) (other *Peer, removed bool, empty bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for i := 0; i < RoomMaxPeers; i++ {
		s := &r.seats[i]
		if s.peer != p || s.grace == nil || s.gen != gen {
			continue // 已被重连接管 / 已显式离开 / 回调过期
		}
		s.grace = nil
		s.gen++
		s.peer = nil
		return r.otherPeerLocked(p), true, r.isEmptyLocked()
	}
	return nil, false, r.isEmptyLocked()
}

// RemovePeer 立即移除指定 peer（显式 leave、或宽限期已关闭时的断线路径），
// 返回剩余的对端与「房间是否已空」。调用方据此决定是否销毁房间、是否给对端发 peerLeft。
func (r *Room) RemovePeer(p *Peer) (other *Peer, empty bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for i := 0; i < RoomMaxPeers; i++ {
		s := &r.seats[i]
		if s.peer == p {
			if s.grace != nil {
				s.grace.Stop()
			}
			s.grace = nil
			s.gen++
			s.peer = nil
			return r.otherPeerLocked(p), r.isEmptyLocked()
		}
	}
	// peer 不在本房间（例如已因显式 leave 移除过）
	return r.otherPeerLocked(p), r.isEmptyLocked()
}

// OtherPeer 返回房间内的另一端；只有自己或房间为空时返回 nil。
// 处于宽限期的席位也算「在房」，因此重连方仍能与其配对。
func (r *Room) OtherPeer(self *Peer) *Peer {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.otherPeerLocked(self)
}

func (r *Room) otherPeerLocked(self *Peer) *Peer {
	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].peer != nil && r.seats[i].peer != self {
			return r.seats[i].peer
		}
	}
	return nil
}

// Peers 返回当前占用席位的 peers 快照（含处于宽限期的席位）。
func (r *Room) Peers() []*Peer {
	r.mu.Lock()
	defer r.mu.Unlock()

	out := make([]*Peer, 0, RoomMaxPeers)
	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].peer != nil {
			out = append(out, r.seats[i].peer)
		}
	}
	return out
}

// LivePeers 返回「当前仍有活动连接」的 peers（不含处于宽限期的席位）。
// 用于房间过期时只通知真正在线的对端。
func (r *Room) LivePeers() []*Peer {
	r.mu.Lock()
	defer r.mu.Unlock()

	out := make([]*Peer, 0, RoomMaxPeers)
	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].peer != nil && r.seats[i].grace == nil {
			out = append(out, r.seats[i].peer)
		}
	}
	return out
}

// PeerCount 返回当前占用席位的数量（含处于宽限期的席位，它们仍占位）。
func (r *Room) PeerCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.peerCountLocked()
}

func (r *Room) peerCountLocked() int {
	n := 0
	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].peer != nil {
			n++
		}
	}
	return n
}

// PendingCount 返回处于宽限期的席位数（供日志/健康检查观察）。
func (r *Room) PendingCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()

	n := 0
	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].grace != nil {
			n++
		}
	}
	return n
}

// IsPending 判断某个 peer 当前是否处于宽限期（WS 已断开、席位保留）。
// 用于避免向"已死"的对端写 peerLeft。
func (r *Room) IsPending(p *Peer) bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].peer == p {
			return r.seats[i].grace != nil
		}
	}
	return false
}

// IsEmpty 判断房间是否已无任何席位占用者。
func (r *Room) IsEmpty() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.isEmptyLocked()
}

func (r *Room) isEmptyLocked() bool {
	for i := 0; i < RoomMaxPeers; i++ {
		if r.seats[i].peer != nil {
			return false
		}
	}
	return true
}

// Age 返回房间存活时长，用于日志。
func (r *Room) Age() time.Duration { return time.Since(r.CreatedAt) }

// IsExpired 判断房间是否过期（doc/09 §7：创建后 30 分钟无人加入即销毁）。
//
// 判定条件（两条件同时满足）：
//  1. 房间未满员（只有 0 或 1 人）——正在通话的房间不会被清理；
//     *处于宽限期的席位仍算占位*，因此「一人离线等待重连」的房间不会被误清；
//  2. 距最近一次活动超过 expirySec（活动 = 创建、成功加入或席位恢复）。
func (r *Room) IsExpired(now time.Time) bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.peerCountLocked() >= RoomMaxPeers {
		return false
	}
	if r.expirySec <= 0 {
		return false
	}
	return now.Sub(r.lastActiveAt) > time.Duration(r.expirySec)*time.Second
}
