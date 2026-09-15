package server

import (
	"fmt"
	"os"
	"testing"
	"time"

	"webrtcdemo-signaling/config"
)

// graceConfig 返回一个「宽限期很短但语义完整」的测试配置（测试需秒级完成）。
func graceConfig(grace time.Duration) *config.Config {
	cfg := config.Default()
	cfg.StunURL = "stun:127.0.0.1:3478"
	cfg.TurnURL = "turn:127.0.0.1:3478?transport=udp"
	cfg.RoomGrace = grace
	return cfg
}

// ---------------------------------------------------------------------------
// t67 验收①②③：WS 瞬断后房间/席位保留、重连同身份入会、期间不给对端发 peerLeft
// ---------------------------------------------------------------------------

// TestE2E_GraceDisconnectKeepsRoomSeatAndSuppressesPeerLeft 对应验收项 ①③②。
//
// 步骤与断言：
//  1. host 建房、joiner 入会（host 收到 peerJoined）；
//  2. joiner **异常断开**（不走 leave）；
//  3. 断言「③ 宽限期内在线对端未收到 peerLeft」——用「host 发 ping 收到 pong」
//     这一有序探针代替读超时断言（gorilla 读超时会永久污染连接，不能用它探测"无消息"）：
//     若服务端在断开后发了 peerLeft，它必然排在这次 pong 之前；
//  4. 断言「① 房间仍存在且席位保留」——新连接用原 roomId join 能拿到 **同一个 peerId
//     （peer-002）**（席位被接管即为席位保留的直接证据），并回 joined；
//  5. 断言「② 重连入会成功 + 对端走既有 peerJoined 路径」——host 收到 peerJoined(peer-002)，
//     且始终没有 peerLeft。
func TestE2E_GraceDisconnectKeepsRoomSeatAndSuppressesPeerLeft(t *testing.T) {
	s, ts, buf := newTestServer(t, graceConfig(3*time.Second))
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	joiner := dial(t, "JOINER", url)

	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joiner.expect("joined")
	host.expect("peerJoined")

	// ②-前置：异常断开（模拟真机 ws_pong_timeout 后的瞬断）
	joiner.close()

	// ③ 有序探针：host 发 ping → 必须收到的下一条是 pong（期间没有 peerLeft）
	host.sendRaw(`{"type":"ping","timestamp":1}`)
	if m := host.expectTypeWithin("pong", 3*time.Second); m["type"] != "pong" {
		t.Fatalf("期望 pong，实际 %v", m)
	}
	t.Log("宽限期内在线对端未收到 peerLeft（有序探针：ping→pong 之间无 peerLeft）")

	// ① 房间仍在（未回收）
	if n := s.Manager().Count(); n != 1 {
		t.Fatalf("宽限期内房间应保留，实际房间数 %d", n)
	}

	// ①② 重连：同一 roomId join，应成功且拿到被保留席位的同一个 peerId
	again := dial(t, "JOINER-AGAIN", url)
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joined := again.expectTypeWithin("joined", 3*time.Second)
	if joined["peerId"] != "peer-002" {
		t.Fatalf("重连应恢复原席位 peer-002，实际 %v", joined["peerId"])
	}
	if joined["roomId"] != roomID {
		t.Fatalf("joined.roomId 应为 %s，实际 %v", roomID, joined["roomId"])
	}

	// ② 对端走既有 peerJoined 路径触发重协商；且全程没有 peerLeft
	if m := host.expectTypeWithin("peerJoined", 3*time.Second); m["peerId"] != "peer-002" {
		t.Fatalf("重连后 host 应收到 peerJoined(peer-002)，实际 %v", m["peerId"])
	}
	if !waitForLog(t, buf, "seat_takeover", 2*time.Second) {
		t.Fatalf("服务端日志应记录 seat_takeover，实际日志：\n%s", buf.String())
	}
	if waitForLog(t, buf, "peer_left_sent", 300*time.Millisecond) {
		t.Fatalf("宽限期内不应出现 peer_left_sent，实际日志：\n%s", buf.String())
	}

	// 宽限期早已过去：重连接管后不应再有 peerLeft（代次校验生效）
	time.Sleep(1200 * time.Millisecond)
	host.sendRaw(`{"type":"ping","timestamp":2}`)
	if m := host.expectTypeWithin("pong", 3*time.Second); m["type"] != "pong" {
		t.Fatalf("期望 pong，实际 %v", m)
	}
	if waitForLog(t, buf, "peer_left_sent", 300*time.Millisecond) {
		t.Fatalf("重连接管后不应再出现 peer_left_sent（过期回调未生效），日志：\n%s", buf.String())
	}
	t.Logf("房间 %s：瞬断→席位保留→同身份重连成功，全程 0 次 peerLeft", roomID)
}

// ---------------------------------------------------------------------------
// t67 验收④：宽限期满未重连 → 回收席位并**只**给在线对端发一次 peerLeft
// ---------------------------------------------------------------------------

func TestE2E_GraceExpiryReclaimsSeatAndNotifiesPeerLeftOnce(t *testing.T) {
	const grace = 400 * time.Millisecond
	s, ts, buf := newTestServer(t, graceConfig(grace))
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	joiner := dial(t, "JOINER", url)

	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joiner.expect("joined")
	host.expect("peerJoined")

	start := time.Now()
	joiner.close() // 断开后不重连

	// ④ 宽限期满后，在线对端收到 peerLeft(peer-002)
	left := host.expectTypeWithin("peerLeft", 5*time.Second)
	if left["peerId"] != "peer-002" {
		t.Fatalf("peerLeft.peerId 应为 peer-002，实际 %v", left["peerId"])
	}
	elapsed := time.Since(start)
	if elapsed < grace*8/10 {
		t.Fatalf("peerLeft 不应早于宽限期（%v）出现，实际 %v", grace, elapsed)
	}
	t.Logf("宽限期 %v 后收到 peerLeft（实测 %v）", grace, elapsed.Round(time.Millisecond))

	// 「只发一次」：host 发 ping 收到的下一条必须是 pong（若重复发 peerLeft 会排在其前）
	host.sendRaw(`{"type":"ping","timestamp":9}`)
	if m := host.expectTypeWithin("pong", 2*time.Second); m["type"] != "pong" {
		t.Fatalf("期望 pong，实际 %v", m)
	}
	if n := countLog(t, buf, "peer_left_sent"); n != 1 {
		t.Fatalf("peer_left_sent 应恰好 1 次，实际 %d 次：\n%s", n, buf.String())
	}

	// 席位已回收但 host 仍在房 → 房间保留（不销毁）；host 显式 leave 后才销毁
	if n := s.Manager().Count(); n != 1 {
		t.Fatalf("在线对端仍在房时房间不应销毁，实际房间数 %d", n)
	}
	host.sendRaw(`{"type":"leave"}`)
	deadline := time.Now().Add(2 * time.Second)
	for s.Manager().Count() != 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if n := s.Manager().Count(); n != 0 {
		t.Fatalf("显式 leave 后房间应销毁，实际 %d", n)
	}
	t.Log("宽限期满 → 席位回收 + 一次 peerLeft；survivor 显式 leave 后房间销毁")
}

// ---------------------------------------------------------------------------
// 静默掉线（读超时）场景：读超时前=ROOM_FULL；读超时后=席位进入宽限期→可接管
// ---------------------------------------------------------------------------

// TestE2E_StaleSeatTakeoverAfterReadTimeout 验证与真机一致的「静默掉线」时序：
//
//	读超时前：服务端不知道对方已死，房间仍算满员 → 新连接 join 得 ROOM_FULL（可重试）；
//	读超时后：该席位进入宽限期 → 新连接用原 roomId join 直接接管该席位（同 peerId），
//	          在线对端收到 peerJoined 而非 peerLeft。
func TestE2E_StaleSeatTakeoverAfterReadTimeout(t *testing.T) {
	const pongWait = 600 * time.Millisecond
	cfg := graceConfig(3 * time.Second)
	cfg.PongWait = pongWait
	_, ts, buf := newTestServer(t, cfg)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)

	stale := dial(t, "STALE-JOINER", url) // 静默：保持 TCP 打开但不收发 → 只能等读超时
	stale.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	stale.expect("joined")
	host.expect("peerJoined")

	stopHost := host.startHeartbeat(200 * time.Millisecond)
	defer stopHost()

	// 读超时前：新连接 join 得 ROOM_FULL（服务端尚不知 stale 已死）
	again := dial(t, "JOINER-AGAIN", url)
	stopAgain := again.startHeartbeat(200 * time.Millisecond)
	defer stopAgain()
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	again.expectTypeWithin("error", 2*time.Second)
	t.Log("读超时前：ROOM_FULL（服务端未察觉静默掉线）")

	// 等读超时把 stale 标记为宽限期席位（其连接被服务端关闭）
	stale.expectClosed(3 * time.Second)

	// 读超时后：直接接管席位（同一 peerId），对端收到 peerJoined、没有 peerLeft
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	if m := again.expectTypeWithin("joined", 3*time.Second); m["peerId"] != "peer-002" {
		t.Fatalf("接管席位后应拿到 peer-002，实际 %v", m["peerId"])
	}
	if m := host.expectTypeWithin("peerJoined", 3*time.Second); m["peerId"] != "peer-002" {
		t.Fatalf("对端应收到 peerJoined(peer-002)，实际 %v", m["peerId"])
	}
	if waitForLog(t, buf, "peer_left_sent", 300*time.Millisecond) {
		t.Fatalf("接管路径不应产生 peerLeft，日志：\n%s", buf.String())
	}
	if !waitForLog(t, buf, "room_peer_offline_grace", 2*time.Second) {
		t.Fatalf("应记录 room_peer_offline_grace，日志：\n%s", buf.String())
	}
	t.Log("读超时后：席位接管成功（peer-002 恢复），对端走 peerJoined 重协商路径")
}

// ---------------------------------------------------------------------------
// 双方都断开：房间在宽限期内保留（这改变了 v1.0-m 时代的"房间立即销毁"结论）
// ---------------------------------------------------------------------------

// TestE2E_BothDisconnectedRoomSurvivesGrace 验证双方断开后房间在宽限期内仍存在、
// 可被任一连接接管；宽限期满且无人接管时房间才被销毁。
//
// 该行为替代了旧用例 TestE2E_ReconnectAfterBothDisconnectedIsRoomNotFound
// （当时语义是"房间一空即销毁→重连得 ROOM_NOT_FOUND"）。
func TestE2E_BothDisconnectedRoomSurvivesGrace(t *testing.T) {
	const grace = 700 * time.Millisecond
	s, ts, _ := newTestServer(t, graceConfig(grace))
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)
	joiner := dial(t, "JOINER", url)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joiner.expect("joined")
	host.expect("peerJoined")

	// 双方都断开
	host.close()
	joiner.close()
	time.Sleep(150 * time.Millisecond)

	// 宽限期内：房间与两个席位都保留
	if n := s.Manager().Count(); n != 1 {
		t.Fatalf("宽限期内房间应保留，实际房间数 %d", n)
	}

	// 任一连接可用原 roomId 接管席位并成功入会
	again := dial(t, "AGAIN", url)
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	if m := again.expectTypeWithin("joined", 3*time.Second); m["roomId"] != roomID {
		t.Fatalf("宽限期内应能接管入会，实际 %v", m)
	}

	// 该连接也断开后，两个席位都会在宽限期满时被回收 → 房间销毁 → 再 join 得 ROOM_NOT_FOUND
	again.close()
	deadline := time.Now().Add(4 * time.Second)
	for s.Manager().Count() != 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if n := s.Manager().Count(); n != 0 {
		t.Fatalf("宽限期满且无人接管后房间应销毁，实际 %d", n)
	}
	late := dial(t, "LATE", url)
	late.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	late.expectError("ROOM_NOT_FOUND")
	t.Log("双方断开：宽限期内房间保留且可接管；宽限期满无人接管才销毁（→ ROOM_NOT_FOUND）")
}

// ---------------------------------------------------------------------------
// 时序实测（t67 后的新语义）：读超时回收 → 席位进入宽限期 → 宽限期满才发 peerLeft
// ---------------------------------------------------------------------------

// TestE2E_OfflineGraceTimingAfterStaleReap 量化三条时序，供报告 §5.1 与客户端重连预算对照：
//  1. readTimeoutReap：最后活动 → 服务端察觉静默掉线（≈ PongWait）；
//  2. offlineNotify  ：察觉 → 对端收到 peerLeft（≈ RoomGrace，即宽限期不被提前打断）；
//  3. 期间（grace 内）新连接可直接接管席位（无需等待 peerLeft）。
func TestE2E_OfflineGraceTimingAfterStaleReap(t *testing.T) {
	const pongWait = 600 * time.Millisecond
	const grace = 1200 * time.Millisecond
	cfg := graceConfig(grace)
	cfg.PongWait = pongWait
	s, ts, buf := newTestServer(t, cfg)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)
	stale := dial(t, "STALE", url)
	stale.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	stale.expect("joined")
	host.expect("peerJoined")

	stopHost := host.startHeartbeat(200 * time.Millisecond)
	defer stopHost()

	lastActivity := time.Now()
	stale.expectClosed(3 * time.Second) // 读超时 → 服务端回收连接（席位进入宽限期）
	reapAt := time.Now()
	readTimeoutReap := reapAt.Sub(lastActivity)

	// 宽限期内：新连接接管席位（不需要等 peerLeft）
	again := dial(t, "AGAIN", url)
	stopAgain := again.startHeartbeat(200 * time.Millisecond)
	defer stopAgain()
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	if m := again.expectTypeWithin("joined", 2*time.Second); m["peerId"] != "peer-002" {
		t.Fatalf("宽限期内应能接管席位，实际 %v", m["peerId"])
	}
	takeoverLatency := time.Since(reapAt)

	// 由于已接管，宽限期回调应失效 → 之后不应出现 peerLeft
	time.Sleep(grace + 400*time.Millisecond)
	if waitForLog(t, buf, "peer_left_sent", 300*time.Millisecond) {
		t.Fatalf("席位已接管，不应出现 peerLeft，日志：\n%s", buf.String())
	}

	t.Logf("实测（PongWait=%v, RoomGrace=%v）：readTimeoutReap=%v；Reap→接管入会=%v；接管后无 peerLeft ✅",
		pongWait, grace, readTimeoutReap.Round(time.Millisecond), takeoverLatency.Round(time.Millisecond))

	if readTimeoutReap < pongWait*8/10 || readTimeoutReap > pongWait+2*time.Second {
		t.Fatalf("读超时回收耗时应≈%v，实际 %v", pongWait, readTimeoutReap)
	}
	if takeoverLatency > grace {
		t.Fatalf("接管应发生在宽限期内（<%v），实际 %v", grace, takeoverLatency)
	}
	_ = s
}

// countLog 统计日志缓冲中某个子串出现次数。
func countLog(t *testing.T, buf *syncBuffer, substr string) int {
	t.Helper()
	return stringsCount(buf.String(), substr)
}

// ---------------------------------------------------------------------------
// 活体验证：对**真实二进制**复现真机缺陷场景（需 SIGNALING_WS_URL）
// ---------------------------------------------------------------------------

// TestLive_GraceReconnect 打真实监听端口复现真机缺陷（dl-b 瞬断）并验证已修复：
//
//	SIGNALING_WS_URL=ws://127.0.0.1:18443/ws go test ./server -run TestLive_GraceReconnect -v
//
// 前置：服务端宽限期 > 1s（例如 `./signaling -room-grace 3s ...`）。
func TestLive_GraceReconnect(t *testing.T) {
	url := os.Getenv("SIGNALING_WS_URL")
	if url == "" {
		t.Skip("未设置 SIGNALING_WS_URL，跳过活体宽限期测试")
	}

	host := dial(t, "LIVE-HOST", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)

	joiner := dial(t, "LIVE-JOINER", url)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joiner.expect("joined")
	host.expect("peerJoined")

	// 复现真机：入会方瞬断（不走 leave）
	joiner.close()

	// 宽限期内在线对端不应收到 peerLeft（有序探针：ping → 下一条必须是 pong）
	host.sendRaw(`{"type":"ping","timestamp":1}`)
	if m := host.expectTypeWithin("pong", 3*time.Second); m["type"] != "pong" {
		t.Fatalf("期望 pong，实际 %v", m)
	}

	// 同身份重连入会（拿到被保留席位的 peerId）
	again := dial(t, "LIVE-REJOIN", url)
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joined := again.expectTypeWithin("joined", 3*time.Second)
	if joined["peerId"] != "peer-002" {
		t.Fatalf("重连应恢复 peer-002，实际 %v", joined["peerId"])
	}
	if m := host.expectTypeWithin("peerJoined", 3*time.Second); m["peerId"] != "peer-002" {
		t.Fatalf("对端应收到 peerJoined(peer-002)，实际 %v", m["peerId"])
	}
	t.Logf("活体验证通过：房间 %s 瞬断后席位保留、同身份重连成功、宽限期内无 peerLeft", roomID)
}
