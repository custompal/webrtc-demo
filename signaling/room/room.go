// 房间结构与方法（doc/09 §7 房间管理规则、§8 Room 结构）。
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

// Room 表示一个 1:1 通话房间。
//
// 并发模型：所有字段读写都必须持有 mu。为避免与 Manager 的锁形成环，
// 本结构不会在自己持锁期间调用 Manager 的任何方法（返回结果由调用方处理）。
type Room struct {
	ID        string    // roomId（6 字符）
	CreatedAt time.Time // 创建时间

	expirySec int

	mu           sync.Mutex
	peers        [RoomMaxPeers]*Peer
	lastActiveAt time.Time // 最近一次「房间满员/有人加入」的时间，用于过期判定
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
func (r *Room) AddPeer(p *Peer) (peerID string, ok bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for i := 0; i < RoomMaxPeers; i++ {
		if r.peers[i] == nil {
			r.peers[i] = p
			p.SetRoom(r)
			r.lastActiveAt = time.Now()
			if i == 0 {
				return PeerID1, true
			}
			return PeerID2, true
		}
	}
	return "", false
}

// OtherPeer 返回房间内的另一端；只有自己或房间为空时返回 nil。
func (r *Room) OtherPeer(self *Peer) *Peer {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.otherPeerLocked(self)
}

func (r *Room) otherPeerLocked(self *Peer) *Peer {
	for i := 0; i < RoomMaxPeers; i++ {
		if r.peers[i] != nil && r.peers[i] != self {
			return r.peers[i]
		}
	}
	return nil
}

// RemovePeer 移除指定 peer，返回剩余的对端与「房间是否已空」。
// 调用方据此决定是否销毁房间、是否给对端发 peerLeft。
func (r *Room) RemovePeer(p *Peer) (other *Peer, empty bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for i := 0; i < RoomMaxPeers; i++ {
		if r.peers[i] == p {
			r.peers[i] = nil
			return r.otherPeerLocked(p), r.isEmptyLocked()
		}
	}
	// peer 不在本房间（例如已因显式 leave 移除过）
	return r.otherPeerLocked(p), r.isEmptyLocked()
}

// Peers 返回当前房间内的 peers 快照。
func (r *Room) Peers() []*Peer {
	r.mu.Lock()
	defer r.mu.Unlock()

	out := make([]*Peer, 0, RoomMaxPeers)
	for i := 0; i < RoomMaxPeers; i++ {
		if r.peers[i] != nil {
			out = append(out, r.peers[i])
		}
	}
	return out
}

// PeerCount 返回当前人数。
func (r *Room) PeerCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.peerCountLocked()
}

func (r *Room) peerCountLocked() int {
	n := 0
	for i := 0; i < RoomMaxPeers; i++ {
		if r.peers[i] != nil {
			n++
		}
	}
	return n
}

// IsEmpty 判断房间是否已无连接。
func (r *Room) IsEmpty() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.isEmptyLocked()
}

func (r *Room) isEmptyLocked() bool {
	for i := 0; i < RoomMaxPeers; i++ {
		if r.peers[i] != nil {
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
//  2. 距最近一次活动超过 expirySec（活动 = 创建或成功加入）。
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
