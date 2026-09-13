package room

import (
	"io"
	"testing"
	"time"

	"github.com/sirupsen/logrus"

	"webrtcdemo-signaling/config"
)

// testLogger 返回静默 logger（单测不需要日志噪音）。
func testLogger() *logrus.Logger {
	l := logrus.New()
	l.SetOutput(io.Discard)
	return l
}

func testConfig(expirySec int) *config.Config {
	cfg := config.Default()
	cfg.RoomExpirySec = expirySec
	return cfg
}

// TestRoomAddAndRemove 校验槽位分配与对端查找（doc/09 §3.2 peer-001/peer-002）。
func TestRoomAddAndRemove(t *testing.T) {
	r := NewRoom("ABCDEF", 1800)
	p1 := NewPeer(nil, PeerOptions{}, testLogger())
	p2 := NewPeer(nil, PeerOptions{}, testLogger())

	id1, ok := r.AddPeer(p1)
	if !ok || id1 != PeerID1 {
		t.Fatalf("第一个 peer 应为 %s，实际 %q ok=%v", PeerID1, id1, ok)
	}
	id2, ok := r.AddPeer(p2)
	if !ok || id2 != PeerID2 {
		t.Fatalf("第二个 peer 应为 %s，实际 %q ok=%v", PeerID2, id2, ok)
	}
	p1.SetID(id1)
	p2.SetID(id2)

	// 第三个 peer 必须被拒绝（房间容量 2）
	p3 := NewPeer(nil, PeerOptions{}, testLogger())
	if _, ok := r.AddPeer(p3); ok {
		t.Fatal("房间已满时 AddPeer 应返回 ok=false")
	}

	if got := r.OtherPeer(p1); got != p2 {
		t.Fatalf("p1 的对端应为 p2，实际 %v", got)
	}
	if got := r.OtherPeer(p2); got != p1 {
		t.Fatalf("p2 的对端应为 p1，实际 %v", got)
	}
	if r.PeerCount() != 2 {
		t.Fatalf("PeerCount 应为 2，实际 %d", r.PeerCount())
	}

	other, empty := r.RemovePeer(p1)
	if other != p2 || empty {
		t.Fatalf("移除 p1 后应对端=p2、未空，实际 other=%v empty=%v", other, empty)
	}
	other, empty = r.RemovePeer(p2)
	if other != nil || !empty {
		t.Fatalf("移除 p2 后应为空，实际 other=%v empty=%v", other, empty)
	}
	if !r.IsEmpty() {
		t.Fatal("房间应已空")
	}
}

// TestRoomExpiry 校验过期判定：人未满且超过有效期才算过期。
func TestRoomExpiry(t *testing.T) {
	r := NewRoom("ABCDEF", 1)
	if r.IsExpired(time.Now()) {
		t.Fatal("刚创建的房间不应过期")
	}
	if !r.IsExpired(time.Now().Add(2 * time.Second)) {
		t.Fatal("1 秒有效期、2 秒后应过期")
	}

	// 满员房间不过期（正在通话不应被清理协程打断）
	p1 := NewPeer(nil, PeerOptions{}, testLogger())
	p2 := NewPeer(nil, PeerOptions{}, testLogger())
	_, _ = r.AddPeer(p1)
	_, _ = r.AddPeer(p2)
	if r.IsExpired(time.Now().Add(10 * time.Second)) {
		t.Fatal("满员房间不应判定过期")
	}
}

// TestManagerCreateJoinFull 校验管理器的主流程与房间已满错误。
func TestManagerCreateJoinFull(t *testing.T) {
	m := NewManager(testConfig(1800), testLogger())
	defer m.Stop()

	host := NewPeer(nil, PeerOptions{}, testLogger())
	r, peerID, err := m.CreateRoom(host)
	if err != nil {
		t.Fatalf("CreateRoom 失败: %v", err)
	}
	if peerID != PeerID1 || len(r.ID) != 6 {
		t.Fatalf("CreateRoom 结果异常：peerId=%q roomId=%q", peerID, r.ID)
	}
	if m.Count() != 1 {
		t.Fatalf("房间数应为 1，实际 %d", m.Count())
	}

	joiner := NewPeer(nil, PeerOptions{}, testLogger())
	r2, id2, err := m.JoinRoom(r.ID, joiner)
	if err != nil {
		t.Fatalf("JoinRoom 失败: %v", err)
	}
	if r2 != r || id2 != PeerID2 {
		t.Fatalf("JoinRoom 结果异常：room=%v peerId=%q", r2, id2)
	}

	third := NewPeer(nil, PeerOptions{}, testLogger())
	if _, _, err := m.JoinRoom(r.ID, third); err != ErrRoomFull {
		t.Fatalf("第三个 peer 应得到 ErrRoomFull，实际 %v", err)
	}

	if _, _, err := m.JoinRoom("ZZZZZZ", third); err != ErrRoomNotFound {
		t.Fatalf("不存在的房间应得到 ErrRoomNotFound，实际 %v", err)
	}

	created, destroyed := m.Stats()
	if created != 1 || destroyed != 0 {
		t.Fatalf("统计异常：created=%d destroyed=%d", created, destroyed)
	}
}

// TestManagerLeaveDestroysRoom 校验显式 leave 立即销毁房间（doc/09 §3.8）。
func TestManagerLeaveDestroysRoom(t *testing.T) {
	m := NewManager(testConfig(1800), testLogger())
	defer m.Stop()

	host := NewPeer(nil, PeerOptions{}, testLogger())
	joiner := NewPeer(nil, PeerOptions{}, testLogger())
	r, _, _ := m.CreateRoom(host)
	_, _, _ = m.JoinRoom(r.ID, joiner)

	left, other, destroyed := m.Leave(host)
	if left != r || other != joiner || !destroyed {
		t.Fatalf("Leave 结果异常：room=%v other=%v destroyed=%v", left, other, destroyed)
	}
	if m.Count() != 0 {
		t.Fatalf("leave 后房间应被销毁，实际仍有 %d 个房间", m.Count())
	}
	if host.Room() != nil || joiner.Room() != nil {
		t.Fatal("房间销毁后双方 Room 引用都应清空")
	}
}

// TestManagerDetachKeepsRoom 校验意外断线：房间有残留 peer 时保留（等待重连）。
func TestManagerDetachKeepsRoom(t *testing.T) {
	m := NewManager(testConfig(1800), testLogger())
	defer m.Stop()

	host := NewPeer(nil, PeerOptions{}, testLogger())
	joiner := NewPeer(nil, PeerOptions{}, testLogger())
	r, _, _ := m.CreateRoom(host)
	_, _, _ = m.JoinRoom(r.ID, joiner)

	_, other, destroyed := m.Detach(joiner)
	if other != host || destroyed {
		t.Fatalf("断线后房间应保留：other=%v destroyed=%v", other, destroyed)
	}
	if m.Count() != 1 {
		t.Fatalf("房间应仍存在，实际 %d", m.Count())
	}
	if host.Room() != r {
		t.Fatal("残留 peer 应仍在房间内")
	}

	// 对端重连：可用原 roomId 重新 join（doc/09 §6 重连规则第 4 条）
	rejoin := NewPeer(nil, PeerOptions{}, testLogger())
	if _, _, err := m.JoinRoom(r.ID, rejoin); err != nil {
		t.Fatalf("重连 join 失败: %v", err)
	}

	// 重连者再断开 → 仍剩 host，房间继续保留
	if _, _, destroyed := m.Detach(rejoin); destroyed {
		t.Fatal("仍有残留 peer 时房间不应销毁")
	}

	// 最后一人断开 → 房间销毁
	_, _, destroyed = m.Detach(host)
	if !destroyed {
		t.Fatal("最后一人断开后房间应销毁")
	}
	if m.Count() != 0 {
		t.Fatalf("房间应已销毁，实际 %d", m.Count())
	}
}

// TestManagerSweepExpired 校验过期清理协程能回收房间并回调通知残留 peer。
func TestManagerSweepExpired(t *testing.T) {
	m := NewManager(testConfig(1), testLogger())
	defer m.Stop()

	type expiredEvent struct {
		room  *Room
		peers []*Peer
	}
	events := make(chan expiredEvent, 4)
	m.SetExpiredHandler(func(r *Room, peers []*Peer) {
		events <- expiredEvent{room: r, peers: peers}
	})

	host := NewPeer(nil, PeerOptions{}, testLogger())
	r, _, _ := m.CreateRoom(host)

	select {
	case ev := <-events:
		if ev.room.ID != r.ID || len(ev.peers) != 1 || ev.peers[0] != host {
			t.Fatalf("过期回调内容异常：room=%s peers=%d", ev.room.ID, len(ev.peers))
		}
	case <-time.After(3 * time.Second):
		t.Fatal("3 秒内未收到房间过期回调")
	}

	if m.Count() != 0 {
		t.Fatalf("过期房间应被清理，实际仍有 %d", m.Count())
	}
	if host.Room() != nil {
		t.Fatal("过期后残留 peer 的 Room 引用应清空")
	}
}

// TestManagerJoinExpiredRoom 校验 join 命中过期房间返回 ErrRoomExpired。
func TestManagerJoinExpiredRoom(t *testing.T) {
	m := NewManager(testConfig(1800), testLogger())
	defer m.Stop()

	host := NewPeer(nil, PeerOptions{}, testLogger())
	r, _, _ := m.CreateRoom(host)

	// 把房间的 lastActiveAt 拨回 1 小时前，模拟过期
	r.mu.Lock()
	r.lastActiveAt = time.Now().Add(-time.Hour)
	r.mu.Unlock()

	if _, _, err := m.JoinRoom(r.ID, NewPeer(nil, PeerOptions{}, testLogger())); err != ErrRoomExpired {
		t.Fatalf("应得到 ErrRoomExpired，实际 %v", err)
	}
	if m.Count() != 0 {
		t.Fatal("过期房间应被销毁")
	}
}
