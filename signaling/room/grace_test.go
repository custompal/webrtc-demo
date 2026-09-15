package room

import (
	"sync/atomic"
	"testing"
	"time"

	"webrtcdemo-signaling/config"
)

// graceCfg 返回宽限期可注入的测试配置（不 sleep 60s，全部秒级完成）。
func graceCfg(grace time.Duration) *config.Config {
	cfg := config.Default()
	cfg.RoomGrace = grace
	return cfg
}

func testPeer(t *testing.T, name string) *Peer {
	t.Helper()
	return NewPeer(nil, PeerOptions{}, testLogger())
}

// TestGraceSeatKeptDuringGrace 覆盖验收①：断开后宽限期内房间仍在、席位保留。
func TestGraceSeatKeptDuringGrace(t *testing.T) {
	const grace = 300 * time.Millisecond
	m := NewManager(graceCfg(grace), testLogger())
	defer m.Stop()

	host := testPeer(t, "host")
	joiner := testPeer(t, "joiner")
	r, _, err := m.CreateRoom(host)
	if err != nil {
		t.Fatalf("CreateRoom: %v", err)
	}
	if _, _, err := m.JoinRoom(r.ID, joiner); err != nil {
		t.Fatalf("JoinRoom: %v", err)
	}

	var expired atomic.Int32
	m.SetGraceExpiredHandler(func(_ *Room, _, _ *Peer) { expired.Add(1) })

	// joiner 断开
	if _, other, usedGrace, immediate := m.MarkOffline(joiner); immediate || other != host || usedGrace != grace {
		t.Fatalf("MarkOffline 结果异常：other=%v grace=%v immediate=%v", other, usedGrace, immediate)
	}

	if n := m.Count(); n != 1 {
		t.Fatalf("宽限期内房间应保留，实际 %d", n)
	}
	if r.PeerCount() != 2 {
		t.Fatalf("席位应保留（2 席），实际 %d", r.PeerCount())
	}
	if r.PendingCount() != 1 {
		t.Fatalf("应有 1 个宽限期席位，实际 %d", r.PendingCount())
	}
	if r.LivePeers() == nil || len(r.LivePeers()) != 1 {
		t.Fatalf("在线 peer 应为 1（host），实际 %v", r.LivePeers())
	}
	if got := r.OtherPeer(host); got != joiner {
		t.Fatalf("离线席位仍应与对端配对（重连用），实际 %v", got)
	}

	time.Sleep(grace / 3)
	if n := expired.Load(); n != 0 {
		t.Fatalf("宽限期未满不应触发回收回调，实际 %d 次", n)
	}
}

// TestGraceReconnectTakesOverSeat 覆盖验收②：宽限期内同 peer 身份重连 join 成功。
func TestGraceReconnectTakesOverSeat(t *testing.T) {
	const grace = 400 * time.Millisecond
	m := NewManager(graceCfg(grace), testLogger())
	defer m.Stop()

	host := testPeer(t, "host")
	joiner := testPeer(t, "joiner")
	r, _, _ := m.CreateRoom(host)
	if _, id, err := m.JoinRoom(r.ID, joiner); err != nil || id != PeerID2 {
		t.Fatalf("首次入会应得 %s，实际 %s err=%v", PeerID2, id, err)
	}
	joiner.SetID(PeerID2)

	if _, _, _, immediate := m.MarkOffline(joiner); immediate {
		t.Fatal("应进入宽限期而非立即移除")
	}

	// 重连：新 peer 用原 roomId join → 接管保留席位、拿回同一 peerId
	rejoin := testPeer(t, "rejoin")
	_, id, err := m.JoinRoom(r.ID, rejoin)
	if err != nil {
		t.Fatalf("宽限期内重连应成功，实际 err=%v", err)
	}
	if id != PeerID2 {
		t.Fatalf("重连应恢复原席位 %s，实际 %s", PeerID2, id)
	}
	if r.PendingCount() != 0 {
		t.Fatalf("接管后不应再有宽限期席位，实际 %d", r.PendingCount())
	}
	if joiner.Room() != nil {
		t.Fatal("被接管的旧 peer 应已脱离房间")
	}
	if rejoin.Room() != r {
		t.Fatal("新 peer 应在房间内")
	}

	// 代次校验：接管后过期回调必须失效（不再回收、不通知对端）
	var expired atomic.Int32
	m.SetGraceExpiredHandler(func(_ *Room, _, _ *Peer) { expired.Add(1) })
	time.Sleep(grace + 250*time.Millisecond)
	if n := expired.Load(); n != 0 {
		t.Fatalf("席位已被接管，过期回调不应触发，实际 %d 次", n)
	}
	if r.PeerCount() != 2 {
		t.Fatalf("接管后仍应 2 席，实际 %d", r.PeerCount())
	}
}

// TestGraceExpiryRemovesSeatOnce 覆盖验收④（Room 层）：宽限期满回收席位，且只回收一次。
func TestGraceExpiryRemovesSeatOnce(t *testing.T) {
	const grace = 200 * time.Millisecond
	r := NewRoom("ABCDEF", 1800)
	host := testPeer(t, "host")
	joiner := testPeer(t, "joiner")
	_, _, _, _ = r.AddPeer(host, grace, nil)
	_, _, _, _ = r.AddPeer(joiner, grace, nil)

	var fired atomic.Int32
	var firedGen atomic.Uint64
	other, ok := r.MarkPending(joiner, grace, func(gen uint64) {
		fired.Add(1)
		firedGen.Store(gen)
	})
	if !ok || other != host {
		t.Fatalf("MarkPending 异常：ok=%v other=%v", ok, other)
	}

	// 等回调触发（time.AfterFunc）
	time.Sleep(grace + 150*time.Millisecond)
	if fired.Load() != 1 {
		t.Fatalf("宽限期满应恰好回调 1 次，实际 %d", fired.Load())
	}

	// ExpirePending 幂等：首次回收，第二次（同代次）不再回收
	o, removed, empty := r.ExpirePending(joiner, firedGen.Load())
	if !removed || empty || o != host {
		t.Fatalf("首次回收应成功且房间非空：removed=%v empty=%v other=%v", removed, empty, o)
	}
	if _, removed2, _ := r.ExpirePending(joiner, firedGen.Load()); removed2 {
		t.Fatal("重复回收应无效（保证 peerLeft 只发一次）")
	}
	if r.PeerCount() != 1 || r.PendingCount() != 0 {
		t.Fatalf("回收后应剩 1 席且无宽限期席位，实际 peers=%d pending=%d", r.PeerCount(), r.PendingCount())
	}
}

// TestManagerGraceExpiryNotifiesOnce 覆盖验收④（Manager 层）：
// 宽限期满且未重连 → 回收席位并**只**回调一次（由 server 据此发一次 peerLeft）。
func TestManagerGraceExpiryNotifiesOnce(t *testing.T) {
	const grace = 250 * time.Millisecond
	m := NewManager(graceCfg(grace), testLogger())
	defer m.Stop()

	host := testPeer(t, "host")
	joiner := testPeer(t, "joiner")
	joiner.SetID(PeerID2)
	r, _, _ := m.CreateRoom(host)
	_, _, _ = m.JoinRoom(r.ID, joiner)

	type ev struct {
		room    *Room
		offline *Peer
		other   *Peer
	}
	events := make(chan ev, 4)
	m.SetGraceExpiredHandler(func(rr *Room, off, oth *Peer) { events <- ev{rr, off, oth} })

	if _, _, _, immediate := m.MarkOffline(joiner); immediate {
		t.Fatal("默认应进入宽限期")
	}

	select {
	case e := <-events:
		if e.room.ID != r.ID || e.offline != joiner || e.other != host {
			t.Fatalf("过期事件内容异常：room=%v offline=%v other=%v", e.room, e.offline, e.other)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("3s 内未收到宽限期满回调")
	}

	// 不应有第二次，也不应再残留宽限期席位；host 仍在房 → 房间保留
	select {
	case e := <-events:
		t.Fatalf("宽限期满回调应只触发一次，第二次内容 %+v", e)
	case <-time.After(grace + 200*time.Millisecond):
	}
	if r.PendingCount() != 0 {
		t.Fatalf("宽限期席位应已回收，实际 %d", r.PendingCount())
	}
	if n := m.Count(); n != 1 {
		t.Fatalf("在线对端仍在房，房间应保留，实际 %d", n)
	}
	takeovers, expired := m.StatsGrace()
	if expired != 1 || takeovers != 0 {
		t.Fatalf("计数异常：takeovers=%d expired=%d", takeovers, expired)
	}
}

// TestManagerMarkOfflineWithGraceDisabled 兼容性：-room-grace 0 时退化为旧语义
// （立即移除、房间空则销毁），保证既有部署/客户端行为在显式关闭宽限期时不变。
func TestManagerMarkOfflineWithGraceDisabled(t *testing.T) {
	cfg := config.Default()
	cfg.RoomGrace = 0
	m := NewManager(cfg, testLogger())
	defer m.Stop()

	host := testPeer(t, "host")
	joiner := testPeer(t, "joiner")
	r, _, _ := m.CreateRoom(host)
	_, _, _ = m.JoinRoom(r.ID, joiner)

	_, other, grace, immediate := m.MarkOffline(joiner)
	if !immediate || other != host || grace != 0 {
		t.Fatalf("宽限期关闭时应立即移除并返回对端：immediate=%v other=%v grace=%v", immediate, other, grace)
	}
	if r.PeerCount() != 1 || r.PendingCount() != 0 {
		t.Fatalf("应立即移除席位，实际 peers=%d pending=%d", r.PeerCount(), r.PendingCount())
	}

	// 再断开 host → 房间应销毁（旧语义）
	_, _, _, immediate = m.MarkOffline(host)
	if !immediate {
		t.Fatal("宽限期关闭时应立即移除")
	}
	if n := m.Count(); n != 0 {
		t.Fatalf("最后一人离开后房间应销毁，实际 %d", n)
	}
}

// TestConfigRoomGraceDefaults 校验宽限期默认值 ≥60s 且校验/告警行为符合验收③。
func TestConfigRoomGraceDefaults(t *testing.T) {
	cfg := config.Default()
	if cfg.RoomGrace < 60*time.Second {
		t.Fatalf("默认宽限期应 ≥60s，实际 %v", cfg.RoomGrace)
	}
	if cfg.RoomGrace != config.DefaultRoomGrace {
		t.Fatalf("默认宽限期应为 DefaultRoomGrace=%v，实际 %v", config.DefaultRoomGrace, cfg.RoomGrace)
	}

	// 偏低 → 有告警但不报错
	low := config.Default()
	low.RoomGrace = 10 * time.Second
	if err := low.Validate(); err != nil {
		t.Fatalf("偏低宽限期应可启动（仅告警），实际 err=%v", err)
	}
	if len(low.Warnings()) == 0 {
		t.Fatal("偏低宽限期应产生告警")
	}

	// 负数 → 校验失败
	bad := config.Default()
	bad.RoomGrace = -1
	if err := bad.Validate(); err == nil {
		t.Fatal("负宽限期应校验失败")
	}

	// 0 → 合法（关闭宽限期），但有告警
	off := config.Default()
	off.RoomGrace = 0
	if err := off.Validate(); err != nil {
		t.Fatalf("0 应合法（关闭宽限期），实际 err=%v", err)
	}
	if len(off.Warnings()) == 0 {
		t.Fatal("关闭宽限期应产生告警")
	}
}
