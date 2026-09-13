package server

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/sirupsen/logrus"

	"webrtcdemo-signaling/config"
)

// ---------------------------------------------------------------------------
// 测试脚手架
// ---------------------------------------------------------------------------

// syncBuffer 是并发安全的日志缓冲，用于把服务端日志一并作为测试证据。
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

// waitForLog 轮询等待日志中出现某个片段。
//
// 断开日志由服务端在读循环退出后异步写出，与客户端感知到 close 帧之间存在竞态，
// 因此断言服务端日志时必须轮询而不是立即读取。
func waitForLog(t *testing.T, buf *syncBuffer, substr string, timeout time.Duration) bool {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if strings.Contains(buf.String(), substr) {
			return true
		}
		time.Sleep(20 * time.Millisecond)
	}
	return strings.Contains(buf.String(), substr)
}

// newTestServer 启动一个真实的 HTTP/WebSocket 服务（httptest 走真实 TCP 端口）。
func newTestServer(t *testing.T, cfg *config.Config) (*Server, *httptest.Server, *syncBuffer) {
	t.Helper()
	if cfg == nil {
		cfg = config.Default()
		cfg.StunURL = "stun:127.0.0.1:3478"
		cfg.TurnURL = "turn:127.0.0.1:3478?transport=udp"
	}
	buf := &syncBuffer{}
	logger := logrus.New()
	logger.SetOutput(buf)
	logger.SetLevel(logrus.DebugLevel)
	logger.SetFormatter(&logrus.TextFormatter{FullTimestamp: true, DisableColors: true})

	s := NewServer(cfg, logger)
	ts := httptest.NewServer(s.Handler())
	t.Cleanup(func() {
		ts.Close()
		s.Manager().Stop()
	})
	return s, ts, buf
}

func wsURL(ts *httptest.Server, path string) string {
	return "ws" + strings.TrimPrefix(ts.URL, "http") + path
}

// wsClient 是带原始报文转录的测试客户端。
type wsClient struct {
	t    *testing.T
	name string
	conn *websocket.Conn

	// mu 串行化写操作：gorilla 允许"一个读 + 一个写"并发，
	// 心跳 goroutine 与测试主 goroutine 都可能是写者，故需互斥。
	mu sync.Mutex
}

func dial(t *testing.T, name, url string) *wsClient {
	t.Helper()
	conn, resp, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		status := "nil"
		if resp != nil {
			status = resp.Status
		}
		t.Fatalf("[%s] 连接 %s 失败: %v (HTTP %s)", name, url, err, status)
	}
	conn.SetReadLimit(4 << 20)
	t.Cleanup(func() { _ = conn.Close() })
	t.Logf("[%s] == WebSocket 已连接 %s", name, url)
	return &wsClient{t: t, name: name, conn: conn}
}

func (c *wsClient) send(v interface{}) {
	c.t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		c.t.Fatalf("[%s] 序列化失败: %v", c.name, err)
	}
	c.sendRaw(string(b))
}

func (c *wsClient) sendRaw(s string) {
	c.t.Helper()
	c.t.Logf("[%s] => %s", c.name, s)
	if err := c.writeRaw(s); err != nil {
		c.t.Fatalf("[%s] 发送失败: %v", c.name, err)
	}
}

// writeRaw 是并发安全的写原语（sendRaw 与心跳 goroutine 共用同一把锁）。
func (c *wsClient) writeRaw(s string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	return c.conn.WriteMessage(websocket.TextMessage, []byte(s))
}

// startHeartbeat 周期发送应用层 ping，避免该连接被服务端读超时回收；
// 返回停止函数。用于验证「只有静默掉线的旧会话才会被回收」这类重连场景。
func (c *wsClient) startHeartbeat(interval time.Duration) func() {
	stop := make(chan struct{})
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				if err := c.writeRaw(fmt.Sprintf("{\"type\":\"ping\",\"timestamp\":%d}", time.Now().UnixMilli())); err != nil {
					return
				}
			}
		}
	}()
	return func() { close(stop) }
}

// recv 读取一条消息，返回原始文本与解析后的 map（原始文本用于验证「原样转发」）。
func (c *wsClient) recv() (string, map[string]interface{}) {
	c.t.Helper()
	_ = c.conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, data, err := c.conn.ReadMessage()
	if err != nil {
		c.t.Fatalf("[%s] 读取失败: %v", c.name, err)
	}
	raw := string(data)
	c.t.Logf("[%s] <= %s", c.name, raw)
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		c.t.Fatalf("[%s] JSON 解析失败: %v（原始报文 %s）", c.name, err, raw)
	}
	return raw, m
}

func (c *wsClient) expect(typ string) map[string]interface{} {
	c.t.Helper()
	raw, m := c.recv()
	if m["type"] != typ {
		c.t.Fatalf("[%s] 期望 type=%q，实际 %v（原始报文 %s）", c.name, typ, m["type"], raw)
	}
	return m
}

func (c *wsClient) expectError(code string) map[string]interface{} {
	c.t.Helper()
	m := c.expect("error")
	if m["code"] != code {
		c.t.Fatalf("[%s] 期望错误码 %s，实际 %v", c.name, code, m["code"])
	}
	if msg, _ := m["message"].(string); strings.TrimSpace(msg) == "" {
		c.t.Fatalf("[%s] error 消息缺少 message 字段: %v", c.name, m)
	}
	return m
}

// expectTypeWithin 在 timeout 内持续读取，直到收到指定 type 的消息；
// 期间收到的其它消息（例如心跳的 pong）会被转录并忽略。
// 用于「客户端带心跳」的重连类用例：此时读流里会混入 pong。
func (c *wsClient) expectTypeWithin(typ string, timeout time.Duration) map[string]interface{} {
	c.t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		_ = c.conn.SetReadDeadline(deadline)
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			c.t.Fatalf("[%s] 等待 type=%q 时读失败（%.1fs 内）: %v", c.name, typ, timeout.Seconds(), err)
		}
		c.t.Logf("[%s] <= %s", c.name, data)
		var m map[string]interface{}
		if err := json.Unmarshal(data, &m); err != nil {
			c.t.Fatalf("[%s] JSON 解析失败: %v（原始报文 %s）", c.name, err, data)
		}
		if m["type"] == typ {
			return m
		}
	}
	c.t.Fatalf("[%s] %.1fs 内未收到 type=%q", c.name, timeout.Seconds(), typ)
	return nil
}

// expectClosed 断言服务端已关闭连接。
func (c *wsClient) expectClosed(timeout time.Duration) error {
	c.t.Helper()
	_ = c.conn.SetReadDeadline(time.Now().Add(timeout))
	_, _, err := c.conn.ReadMessage()
	if err == nil {
		c.t.Fatalf("[%s] 期望连接已被服务端关闭，但读到了消息", c.name)
	}
	c.t.Logf("[%s] == 连接已关闭: %v", c.name, err)
	return err
}

func (c *wsClient) close() { _ = c.conn.Close() }

// ---------------------------------------------------------------------------
// 端到端：完整建连流程（doc/09 §5.1 时序图）
// ---------------------------------------------------------------------------

func TestE2E_FullCallFlow(t *testing.T) {
	s, ts, buf := newTestServer(t, nil)
	_ = s
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	joiner := dial(t, "JOINER", url)

	// 1) create → created
	host.sendRaw(`{"type":"create"}`)
	created := host.expect("created")
	roomID, _ := created["roomId"].(string)
	if len(roomID) != 6 {
		t.Fatalf("roomId 应为 6 字符，实际 %q", roomID)
	}
	for _, k := range []string{"roomId", "stunUrl", "turnUrl", "turnUsername", "turnCredential"} {
		if v, _ := created[k].(string); v == "" {
			t.Fatalf("created 缺少必填字段 %s：%v", k, created)
		}
	}
	if created["turnCredential"] != "demopass" {
		t.Fatalf("turnCredential 应下发配置值，实际 %v", created["turnCredential"])
	}

	// 2) join → joined + 对端 peerJoined
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joined := joiner.expect("joined")
	if joined["roomId"] != roomID {
		t.Fatalf("joined.roomId 应为 %s，实际 %v", roomID, joined["roomId"])
	}
	if joined["peerId"] != "peer-002" {
		t.Fatalf("joined.peerId 应为 peer-002，实际 %v", joined["peerId"])
	}
	peerJoined := host.expect("peerJoined")
	if peerJoined["peerId"] != "peer-002" {
		t.Fatalf("peerJoined.peerId 应为 peer-002，实际 %v", peerJoined["peerId"])
	}

	// 3) offer：必须原样转发（逐字节一致）
	offerRaw := `{"type":"offer","sdp":"v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"}`
	host.sendRaw(offerRaw)
	if raw, _ := joiner.recv(); raw != offerRaw {
		t.Fatalf("offer 未被原样转发：\n发送 %s\n收到 %s", offerRaw, raw)
	}

	// 4) answer：同样原样转发
	answerRaw := `{"type":"answer","sdp":"v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"}`
	joiner.sendRaw(answerRaw)
	if raw, _ := host.recv(); raw != answerRaw {
		t.Fatalf("answer 未被原样转发：\n发送 %s\n收到 %s", answerRaw, raw)
	}

	// 5) ice：双向原样转发
	ice1 := `{"type":"ice","candidate":"candidate:842163049 1 udp 1677729535 1.2.3.4 3478 typ srflx raddr 0.0.0.0 generation 0","sdpMid":"0","sdpMLineIndex":0}`
	host.sendRaw(ice1)
	if raw, _ := joiner.recv(); raw != ice1 {
		t.Fatalf("ice 未被原样转发：\n发送 %s\n收到 %s", ice1, raw)
	}
	ice2 := `{"type":"ice","candidate":"candidate:1 1 udp 2113937151 192.168.1.2 50000 typ host","sdpMid":"1"}`
	joiner.sendRaw(ice2)
	if raw, _ := host.recv(); raw != ice2 {
		t.Fatalf("ice 未被原样转发：\n发送 %s\n收到 %s", ice2, raw)
	}

	// 6) natType：双向交换
	host.sendRaw(`{"type":"natType","natType":"FullCone"}`)
	if m := joiner.expect("natType"); m["natType"] != "FullCone" {
		t.Fatalf("natType 转发内容错误: %v", m)
	}
	joiner.sendRaw(`{"type":"natType","natType":"Symmetric"}`)
	if m := host.expect("natType"); m["natType"] != "Symmetric" {
		t.Fatalf("natType 转发内容错误: %v", m)
	}

	// 7) ping → pong
	before := time.Now().UnixMilli()
	host.sendRaw(`{"type":"ping","timestamp":1715432100000}`)
	pong := host.expect("pong")
	tsv, ok := pong["timestamp"].(float64)
	if !ok || int64(tsv) < before {
		t.Fatalf("pong.timestamp 应为服务端当前毫秒时间戳，实际 %v", pong["timestamp"])
	}

	// 8) leave：对端收到 peerLeft，离开方连接被关闭，房间销毁
	host.sendRaw(`{"type":"leave"}`)
	peerLeft := joiner.expect("peerLeft")
	if peerLeft["peerId"] != "peer-001" {
		t.Fatalf("peerLeft.peerId 应为 peer-001，实际 %v", peerLeft["peerId"])
	}
	host.expectClosed(3 * time.Second)

	// 房间已销毁：原 roomId 无法再加入
	late := dial(t, "LATE", url)
	late.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	late.expectError("ROOM_NOT_FOUND")

	t.Logf("---- 服务端日志 ----\n%s", buf.String())
}

// ---------------------------------------------------------------------------
// 错误码覆盖（doc/09 §3.10）
// ---------------------------------------------------------------------------

func TestE2E_ErrorCases(t *testing.T) {
	s, ts, buf := newTestServer(t, nil)
	url := wsURL(ts, PathWS)

	// --- INVALID_MESSAGE：非法 JSON / 缺 type / 未知 type / 服务端专属 type ---
	bad := dial(t, "BAD", url)
	bad.sendRaw(`{"type":`)
	bad.expectError("INVALID_MESSAGE")

	bad.sendRaw(`{}`)
	bad.expectError("INVALID_MESSAGE")

	bad.sendRaw(`{"type":"nope"}`)
	bad.expectError("INVALID_MESSAGE")

	bad.sendRaw(`{"type":"pong","timestamp":1}`)
	bad.expectError("INVALID_MESSAGE")

	// --- NOT_IN_ROOM：未 join 就发 offer/answer/ice/natType/leave ---
	notInRoom := dial(t, "NOT_IN_ROOM", url)
	for _, raw := range []string{
		`{"type":"offer","sdp":"v=0"}`,
		`{"type":"answer","sdp":"v=0"}`,
		`{"type":"ice","candidate":"candidate:1 1 udp 1 1.2.3.4 1 typ host"}`,
		`{"type":"natType","natType":"Open"}`,
		`{"type":"leave"}`,
	} {
		notInRoom.sendRaw(raw)
		notInRoom.expectError("NOT_IN_ROOM")
	}

	// --- 必填字段缺失 ---
	host := dial(t, "HOST", url)
	host.sendRaw(`{"type":"create"}`)
	created := host.expect("created")
	roomID := created["roomId"].(string)
	host.sendRaw(`{"type":"offer"}`)
	host.expectError("INVALID_MESSAGE")
	host.sendRaw(`{"type":"ice","sdpMid":"0"}`)
	host.expectError("INVALID_MESSAGE")

	// --- join 参数问题 ---
	probe := dial(t, "PROBE", url)
	probe.sendRaw(`{"type":"join"}`)
	probe.expectError("INVALID_MESSAGE")
	probe.sendRaw(`{"type":"join","roomId":"ABC"}`)
	probe.expectError("INVALID_MESSAGE")
	probe.sendRaw(`{"type":"join","roomId":"ZZZZZZ"}`)
	probe.expectError("ROOM_NOT_FOUND")

	// --- ROOM_FULL ---
	j1 := dial(t, "J1", url)
	j1.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	j1.expect("joined")
	host.expect("peerJoined")

	j2 := dial(t, "J2", url)
	j2.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	j2.expectError("ROOM_FULL")

	// --- 已在房间时重复 create/join ---
	host.sendRaw(`{"type":"create"}`)
	host.expectError("INVALID_MESSAGE")
	host.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	host.expectError("INVALID_MESSAGE")

	if got := s.Manager().Count(); got != 1 {
		t.Fatalf("房间数应为 1，实际 %d", got)
	}
	t.Logf("---- 服务端日志（错误路径摘录）----\n%s", buf.String())
}

// ---------------------------------------------------------------------------
// 断线：通知对端 + 房间保留（doc/09 §6 重连规则）
// ---------------------------------------------------------------------------

func TestE2E_DisconnectNotifiesPeerAndAllowsReconnect(t *testing.T) {
	_, ts, _ := newTestServer(t, nil)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	joiner := dial(t, "JOINER", url)

	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joiner.expect("joined")
	host.expect("peerJoined")

	// 模拟网络异常断开（不走 leave）
	joiner.close()

	// 对端应收到 peerLeft
	left := host.expect("peerLeft")
	if left["peerId"] != "peer-002" {
		t.Fatalf("peerLeft.peerId 应为 peer-002，实际 %v", left["peerId"])
	}

	// 房间仍存在，可用原 roomId 重连（对方在线 → joined + 对方收到 peerJoined）
	rejoin := dial(t, "REJOIN", url)
	rejoin.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	rejoin.expect("joined")
	if m := host.expect("peerJoined"); m["peerId"] != "peer-002" {
		t.Fatalf("重连后 peerJoined.peerId 应为 peer-002，实际 %v", m["peerId"])
	}
}

// ---------------------------------------------------------------------------
// 消息大小上限（doc/09 §1：64KB）
// ---------------------------------------------------------------------------

func TestE2E_MessageTooLarge(t *testing.T) {
	_, ts, _ := newTestServer(t, nil)
	url := wsURL(ts, PathWS)

	c := dial(t, "TOOBIG", url)
	huge := `{"type":"create","pad":"` + strings.Repeat("A", 70*1024) + `"}`
	if err := c.conn.WriteMessage(websocket.TextMessage, []byte(huge)); err != nil {
		// 服务端读超限后会立刻关闭/重置连接，客户端的大包写入可能先失败；
		// 这同样是「超大消息被拒绝」的证据，不算用例失败。
		t.Logf("写入超大消息时连接已被服务端重置（符合预期）：%v", err)
		return
	}
	_ = c.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, _, err := c.conn.ReadMessage()
	if err == nil {
		t.Fatal("超过 64KB 的消息应导致服务端关闭连接")
	}
	t.Logf("超大消息被拒绝，连接关闭：%v", err)
}

// ---------------------------------------------------------------------------
// 房间过期（doc/09 §7）
// ---------------------------------------------------------------------------

func TestE2E_RoomExpiry(t *testing.T) {
	cfg := config.Default()
	cfg.StunURL = "stun:127.0.0.1:3478"
	cfg.TurnURL = "turn:127.0.0.1:3478?transport=udp"
	cfg.RoomExpirySec = 1 // 1 秒过期，清理周期自动缩短为 250ms
	_, ts, buf := newTestServer(t, cfg)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)

	// 无人 join → 过期后收到 ROOM_EXPIRED 并被关闭
	host.expectError("ROOM_EXPIRED")
	host.expectClosed(3 * time.Second)

	// 过期房间不可再加入
	late := dial(t, "LATE", url)
	late.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	late.expectError("ROOM_NOT_FOUND")

	t.Logf("---- 服务端日志（过期路径摘录）----\n%s", buf.String())
}

// ---------------------------------------------------------------------------
// HTTP 端点：/healthz 与 /ws 非升级请求
// ---------------------------------------------------------------------------

func TestHTTP_HealthzAndNonUpgrade(t *testing.T) {
	_, ts, _ := newTestServer(t, nil)

	resp, err := http.Get(ts.URL + PathHealthz)
	if err != nil {
		t.Fatalf("GET /healthz 失败: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("/healthz 状态码应为 200，实际 %d", resp.StatusCode)
	}
	var health map[string]interface{}
	if err := json.Unmarshal(body, &health); err != nil {
		t.Fatalf("/healthz 返回非 JSON: %v（%s）", err, body)
	}
	if health["status"] != "ok" {
		t.Fatalf("/healthz status 应为 ok，实际 %v", health["status"])
	}

	resp2, err := http.Get(ts.URL + PathWS)
	if err != nil {
		t.Fatalf("GET %s 失败: %v", PathWS, err)
	}
	defer resp2.Body.Close()
	body2, _ := io.ReadAll(resp2.Body)
	if resp2.StatusCode != http.StatusBadRequest {
		t.Fatalf("非升级请求应返回 400，实际 %d", resp2.StatusCode)
	}
	if !strings.Contains(string(body2), "WebSocket upgrade required") {
		t.Fatalf("400 响应体应提示需要 WebSocket 升级，实际 %q", body2)
	}

	resp3, err := http.Get(ts.URL + "/nope")
	if err != nil {
		t.Fatalf("GET /nope 失败: %v", err)
	}
	defer resp3.Body.Close()
	if resp3.StatusCode != http.StatusNotFound {
		t.Fatalf("未知路径应返回 404，实际 %d", resp3.StatusCode)
	}
}

// ---------------------------------------------------------------------------
// 活体服务测试：直接用已部署/已构建的二进制验证（联调用）
//
//	SIGNALING_WS_URL=ws://127.0.0.1:18443/ws go test ./server -run TestLive -v
// ---------------------------------------------------------------------------

func TestLive_FullCallFlow(t *testing.T) {
	url := os.Getenv("SIGNALING_WS_URL")
	if url == "" {
		t.Skip("未设置 SIGNALING_WS_URL，跳过活体服务端到端测试")
	}
	// 与 httptest 路径相同的流程，但打到真实监听端口（自测二进制/宿主部署验证）
	liveFullCallFlow(t, url)
}

func liveFullCallFlow(t *testing.T, url string) {
	host := dial(t, "LIVE-HOST", url)
	joiner := dial(t, "LIVE-JOINER", url)

	host.sendRaw(`{"type":"create"}`)
	created := host.expect("created")
	roomID := created["roomId"].(string)

	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joined := joiner.expect("joined")
	host.expect("peerJoined")

	offerRaw := `{"type":"offer","sdp":"v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"}`
	host.sendRaw(offerRaw)
	if raw, _ := joiner.recv(); raw != offerRaw {
		t.Fatalf("offer 未被原样转发")
	}
	joiner.sendRaw(`{"type":"answer","sdp":"v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"}`)
	host.recv()
	host.sendRaw(`{"type":"natType","natType":"FullCone"}`)
	joiner.expect("natType")
	joiner.sendRaw(`{"type":"ping","timestamp":1715432100000}`)
	joiner.expect("pong")
	host.sendRaw(`{"type":"ice","candidate":"candidate:1 1 udp 1 1.2.3.4 1 typ host","sdpMid":"0"}`)
	joiner.expect("ice")

	t.Logf("房间号 %s，joined.peerId=%v，活体流程全部通过", roomID, joined["peerId"])

	host.sendRaw(`{"type":"leave"}`)
	joiner.expect("peerLeft")
	host.expectClosed(3 * time.Second)
}

// ---------------------------------------------------------------------------
// 并发与吞吐：多房间并行 + 单房间突发转发的顺序性
// ---------------------------------------------------------------------------

// TestE2E_ConcurrentRooms 并行跑 16 组「create → join → offer/answer → leave」，
// 覆盖 Manager/Room 的并发路径（容器内无 gcc，无法跑 -race，用并发压测替代）。
func TestE2E_ConcurrentRooms(t *testing.T) {
	s, ts, _ := newTestServer(t, nil)
	url := wsURL(ts, PathWS)

	const rooms = 16
	var wg sync.WaitGroup
	errCh := make(chan error, rooms)

	for i := 0; i < rooms; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			host, err := dialQuiet(url)
			if err != nil {
				errCh <- err
				return
			}
			defer host.Close()
			joiner, err := dialQuiet(url)
			if err != nil {
				errCh <- err
				return
			}
			defer joiner.Close()

			if err := host.Send(`{"type":"create"}`); err != nil {
				errCh <- err
				return
			}
			created, err := host.Expect("created")
			if err != nil {
				errCh <- err
				return
			}
			roomID, _ := created["roomId"].(string)

			if err := joiner.Send(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID)); err != nil {
				errCh <- err
				return
			}
			if _, err := joiner.Expect("joined"); err != nil {
				errCh <- err
				return
			}
			if _, err := host.Expect("peerJoined"); err != nil {
				errCh <- err
				return
			}

			offer := fmt.Sprintf(`{"type":"offer","sdp":"room-%s"}`, roomID)
			if err := host.Send(offer); err != nil {
				errCh <- err
				return
			}
			raw, err := joiner.ExpectRaw("offer")
			if err != nil {
				errCh <- err
				return
			}
			if raw != offer {
				errCh <- fmt.Errorf("房间 %s 的 offer 转发不一致", roomID)
				return
			}

			if err := host.Send(`{"type":"leave"}`); err != nil {
				errCh <- err
				return
			}
			if _, err := joiner.Expect("peerLeft"); err != nil {
				errCh <- err
				return
			}
		}(i)
	}
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Fatalf("并发场景失败: %v", err)
	}

	if got := s.Manager().Count(); got != 0 {
		t.Fatalf("全部 leave 后房间数应为 0，实际 %d", got)
	}
	created, destroyed := s.Manager().Stats()
	if created != rooms || destroyed != rooms {
		t.Fatalf("统计应为 created=%d destroyed=%d，实际 created=%d destroyed=%d",
			rooms, rooms, created, destroyed)
	}
	t.Logf("并发 %d 个房间全部完成，累计创建/销毁 = %d/%d", rooms, created, destroyed)
}

// TestE2E_BurstForwardOrdering 校验单房间内 200 条 ice 的原样转发与顺序保持。
func TestE2E_BurstForwardOrdering(t *testing.T) {
	_, ts, _ := newTestServer(t, nil)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	joiner := dial(t, "JOINER", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joiner.expect("joined")
	host.expect("peerJoined")

	const n = 200
	for i := 0; i < n; i++ {
		host.sendRaw(fmt.Sprintf(`{"type":"ice","candidate":"candidate:%d 1 udp 1 1.2.3.4 %d typ host","sdpMid":"0"}`, i, 40000+i))
	}
	for i := 0; i < n; i++ {
		_, m := joiner.recv()
		want := fmt.Sprintf("candidate:%d 1 udp 1 1.2.3.4 %d typ host", i, 40000+i)
		if m["candidate"] != want {
			t.Fatalf("第 %d 条 ice 顺序错乱：期望 %q，实际 %v", i, want, m["candidate"])
		}
	}
	t.Logf("突发转发 %d 条 ice：全部到达且顺序保持", n)
}

// ---------------------------------------------------------------------------
// 心跳超时（服务端侧 ping/pong 超时判定）
// ---------------------------------------------------------------------------

func TestE2E_PingPongTimeout(t *testing.T) {
	cfg := config.Default()
	cfg.StunURL = "stun:127.0.0.1:3478"
	cfg.TurnURL = "turn:127.0.0.1:3478?transport=udp"
	cfg.PongWait = 700 * time.Millisecond
	_, ts, buf := newTestServer(t, cfg)
	url := wsURL(ts, PathWS)

	c := dial(t, "SILENT", url)
	c.sendRaw(`{"type":"create"}`)
	c.expect("created")

	// 之后保持沉默：服务端应在 PongWait 后主动断开
	start := time.Now()
	c.expectClosed(5 * time.Second)
	elapsed := time.Since(start)
	if elapsed < 500*time.Millisecond {
		t.Fatalf("断开过早（%v），未等满 PongWait", elapsed)
	}
	if !waitForLog(t, buf, "heartbeat_timeout", 3*time.Second) {
		t.Fatalf("服务端日志应记录 heartbeat_timeout，实际日志：\n%s", buf.String())
	}
	t.Logf("静默 %v 后服务端按 PongWait 断开连接", elapsed.Round(10*time.Millisecond))
}

// ---------------------------------------------------------------------------
// C30 / doc/14 §11.4 D-2：日志键 snake_case，但协议 JSON 必须保持 camelCase
// ---------------------------------------------------------------------------

// TestLogKeysSnakeCaseProtocolJSONCamelCase 验证「只约束日志键名、不约束协议字段名」。
//
// 期望：
//   - 服务端日志里出现 stun_url / turn_url / room= / peer= 等 snake_case 键；
//   - 服务端日志里**不得**出现 stunUrl= / turnUrl= / roomId= / peerId= 等 camelCase 键；
//   - 客户端收到的 created/joined 原始 JSON 仍是 doc/09 的 camelCase 拼写
//     （stunUrl / turnUrl / turnUsername / turnCredential / roomId / peerId）。
func TestLogKeysSnakeCaseProtocolJSONCamelCase(t *testing.T) {
	_, ts, buf := newTestServer(t, nil)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	joiner := dial(t, "JOINER", url)

	host.sendRaw(`{"type":"create"}`)
	rawCreated, created := host.recv()
	for _, k := range []string{`"stunUrl"`, `"turnUrl"`, `"turnUsername"`, `"turnCredential"`, `"roomId"`} {
		if !strings.Contains(rawCreated, k) {
			t.Fatalf("协议 JSON 必须保持 camelCase，created 缺少 %s：%s", k, rawCreated)
		}
	}
	for _, bad := range []string{`"stun_url"`, `"turn_url"`, `"turn_username"`, `"room_id"`} {
		if strings.Contains(rawCreated, bad) {
			t.Fatalf("协议 JSON 不得被改成 snake_case：%s（原始报文 %s）", bad, rawCreated)
		}
	}

	roomID, _ := created["roomId"].(string)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	rawJoined, joined := joiner.recv()
	for _, k := range []string{`"stunUrl"`, `"turnUrl"`, `"turnUsername"`, `"turnCredential"`, `"peerId"`} {
		if !strings.Contains(rawJoined, k) {
			t.Fatalf("协议 JSON 必须保持 camelCase，joined 缺少 %s：%s", k, rawJoined)
		}
	}
	if joined["peerId"] != "peer-002" {
		t.Fatalf("joined.peerId 应为 peer-002，实际 %v", joined["peerId"])
	}
	host.expect("peerJoined")

	// 服务端日志（测试内使用 TextFormatter，键名与生产 formatter 一致）
	if !waitForLog(t, buf, "stun_url=", 3*time.Second) {
		t.Fatalf("日志应包含 snake_case 键 stun_url=，实际：\n%s", buf.String())
	}
	logs := buf.String()
	for _, k := range []string{"stun_url=", "turn_url=", "room=", "peer=", "user_agent=", "active_conns="} {
		if !strings.Contains(logs, k) {
			t.Errorf("日志缺少 snake_case 键 %s", k)
		}
	}
	for _, bad := range []string{"stunUrl=", "turnUrl=", "turnUsername=", "roomId=", "peerId="} {
		if strings.Contains(logs, bad) {
			t.Errorf("日志出现 camelCase 键 %s（C30 要求 snake_case）", bad)
		}
	}

	t.Logf("协议 JSON（camelCase）: %s", rawCreated)
	t.Logf("协议 JSON（camelCase）: %s", rawJoined)
	for _, line := range strings.Split(strings.TrimSpace(logs), "\n") {
		if strings.Contains(line, "created_sent") || strings.Contains(line, "joined_sent") || strings.Contains(line, "room_created") {
			t.Logf("服务端日志（snake_case）: %s", line)
		}
	}
}

// ---------------------------------------------------------------------------
// 静默（无 t.Fatal 依赖 t）的简易客户端，供并发用例使用
// ---------------------------------------------------------------------------

type quietClient struct{ conn *websocket.Conn }

func dialQuiet(url string) (*quietClient, error) {
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		return nil, err
	}
	conn.SetReadLimit(1 << 20)
	return &quietClient{conn: conn}, nil
}

func (q *quietClient) Close() { _ = q.conn.Close() }

func (q *quietClient) Send(s string) error {
	_ = q.conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	return q.conn.WriteMessage(websocket.TextMessage, []byte(s))
}

func (q *quietClient) readRaw(timeout time.Duration) (string, error) {
	_ = q.conn.SetReadDeadline(time.Now().Add(timeout))
	_, data, err := q.conn.ReadMessage()
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func (q *quietClient) Expect(typ string) (map[string]interface{}, error) {
	raw, err := q.ExpectRaw(typ)
	if err != nil {
		return nil, err
	}
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return nil, err
	}
	return m, nil
}

func (q *quietClient) ExpectRaw(typ string) (string, error) {
	raw, err := q.readRaw(5 * time.Second)
	if err != nil {
		return "", err
	}
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return "", err
	}
	if m["type"] != typ {
		return "", fmt.Errorf("期望 type=%s，实际 %v（%s）", typ, m["type"], raw)
	}
	return raw, nil
}

// ---------------------------------------------------------------------------
// 重连语义（doc/09 §6 + §3.3 组合）：把「谁发 offer」的边界变成可验证事实
// ---------------------------------------------------------------------------

// TestE2E_ReconnectStaleSessionCausesRoomFull 验证重连时旧会话尚未被回收的真实行为。
//
// 场景：客户端**静默掉线**（TCP 未关闭、不再收发）→ 服务端要等读超时
// （`-pong-wait`，默认 45s）才会回收该槽位。此时设备侧"立即重连"会因房间仍满
// 而拿到 ROOM_FULL；待旧会话被读超时回收（对端会先收到 peerLeft）后，
// **同一条新连接重试 join 即可成功**。
//
// 这正是「重连遇到 ROOM_FULL 应视为可重试，而非终态」的依据（见 README §3.1）。
func TestE2E_ReconnectStaleSessionCausesRoomFull(t *testing.T) {
	cfg := config.Default()
	cfg.StunURL = "stun:127.0.0.1:3478"
	cfg.TurnURL = "turn:127.0.0.1:3478?transport=udp"
	cfg.PongWait = 600 * time.Millisecond // 缩短读超时，便于在测试内等到「旧会话被回收」
	_, ts, _ := newTestServer(t, cfg)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)

	stale := dial(t, "STALE-JOINER", url) // 模拟"静默掉线"的旧连接：保持打开、不读不写（不发心跳）
	stale.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	stale.expect("joined")
	host.expect("peerJoined")

	// host 需要保活（否则会与 stale 会话一样被读超时回收）；again 也保活以便回收后重试 join。
	stopHost := host.startHeartbeat(250 * time.Millisecond)
	defer stopHost()

	// 设备侧"立即重连"：新连接用原 roomId join
	again := dial(t, "JOINER-AGAIN", url)
	stopAgain := again.startHeartbeat(250 * time.Millisecond)
	defer stopAgain()
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	again.expectError("ROOM_FULL") // 旧槽位仍被死会话占用 → 房间仍满
	t.Log("重连遇到旧会话未回收时，服务端返回 ROOM_FULL（可重试，非终态）")

	// 服务端读超时回收旧会话 → 仍在线的一方收到 peerLeft（读流中可能先混入心跳 pong）
	left := host.expectTypeWithin("peerLeft", 3*time.Second)
	if left["peerId"] != "peer-002" {
		t.Fatalf("peerLeft.peerId 应为 peer-002，实际 %v", left["peerId"])
	}

	// 回收后同一条新连接重试 join 即可成功（且再次拿到 peer-002 —— 按空槽位分配）
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	if m := again.expectTypeWithin("joined", 3*time.Second); m["peerId"] != "peer-002" {
		t.Fatalf("重试后应拿到 peer-002，实际 %v", m["peerId"])
	}
	host.expectTypeWithin("peerJoined", 3*time.Second)
}

// TestE2E_ReconnectAfterBothDisconnectedIsRoomNotFound 验证「双方都断开」后的重连语义。
//
// 双方都断开 → 房间变空 → 立即销毁；此后用原 roomId 重连只能得到 ROOM_NOT_FOUND。
// 这也说明「双方同时重连且都收到 joined、却无人收到 peerJoined」的场景
// 在本实现中**不可达**（房间不会在双方都掉线后仍存在），因此 §3.3 的字面规则
// 不会因此产生"双方互等"的死锁。
func TestE2E_ReconnectAfterBothDisconnectedIsRoomNotFound(t *testing.T) {
	s, ts, _ := newTestServer(t, nil)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)
	joiner := dial(t, "JOINER", url)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joiner.expect("joined")
	host.expect("peerJoined")

	// 双方都断线（不走 leave）
	host.close()
	joiner.close()

	// 等房间被清理（服务端处理完两个连接的断开）
	deadline := time.Now().Add(3 * time.Second)
	for s.Manager().Count() != 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if n := s.Manager().Count(); n != 0 {
		t.Fatalf("双方断开后房间应被销毁，实际仍有 %d 个房间", n)
	}

	// 用原 roomId 重连 → ROOM_NOT_FOUND（房间已销毁；不存在"双方都 joined"的情况）
	again := dial(t, "AGAIN", url)
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	again.expectError("ROOM_NOT_FOUND")
	t.Log("双方断开 → 房间销毁 → 重连得 ROOM_NOT_FOUND；§3.3「双 joined 无 peerJoined」场景不可达")
}

// TestE2E_ReconnectTimingAfterStaleReap 测量「静默掉线 → 服务端回收 → 对端收到 peerLeft → 房间销毁」
// 这条链上的真实时序，供客户端决定重连重试间隔与宽限期（android-dev 请求的实测数据）。
//
// 三条被测量的量（均在测试内直接观测）：
//  1. reapLatency  ：从"最后一条活动"到"对端收到 peerLeft"——即服务端读超时的实际生效时间；
//  2. rejoinLatency：对端**不挂断**（房间仍保留）时，重连方在同一时刻 join 的成功耗时；
//  3. destroyDelay ：对端一收到 peerLeft 立刻 leave 时，房间被销毁耗时（≈ 一次 RTT + 服务端处理）。
//
// 结论（供客户端设计）：房间是否销毁**完全取决于仍在线一方的挂断策略**，服务端不做超时兜底；
// 因此"静默掉线后的可恢复窗口"由 survivor 的宽限期决定，而不是由服务端固定窗口决定。
func TestE2E_ReconnectTimingAfterStaleReap(t *testing.T) {
	const pongWait = 800 * time.Millisecond
	cfg := config.Default()
	cfg.StunURL = "stun:127.0.0.1:3478"
	cfg.TurnURL = "turn:127.0.0.1:3478?transport=udp"
	cfg.PongWait = pongWait
	s, ts, _ := newTestServer(t, cfg)
	url := wsURL(ts, PathWS)

	host := dial(t, "HOST", url)
	host.sendRaw(`{"type":"create"}`)
	roomID := host.expect("created")["roomId"].(string)

	joiner := dial(t, "JOINER", url)
	joiner.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	joiner.expect("joined")
	host.expect("peerJoined")

	// joiner 从此静默（不发心跳、也不关连接）→ 服务端只能等读超时回收
	lastActivity := time.Now()

	// host 必须保活，否则它也会被同一条读超时回收
	stopHost := host.startHeartbeat(200 * time.Millisecond)
	defer stopHost()

	host.expectTypeWithin("peerLeft", 5*time.Second)
	tPeerLeft := time.Now()
	reapLatency := tPeerLeft.Sub(lastActivity)

	// 1) 回收后房间仍在（survivor 还在），此时重连可直接成功
	if n := s.Manager().Count(); n != 1 {
		t.Fatalf("回收死会话后房间应仍在（survivor 持有），实际房间数 %d", n)
	}
	again := dial(t, "JOINER-AGAIN", url)
	t0 := time.Now()
	again.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	again.expectTypeWithin("joined", 3*time.Second)
	rejoinLatency := time.Since(t0)
	host.expectTypeWithin("peerJoined", 3*time.Second)

	// 2) 反向：survivor 收到 peerLeft 立即 leave（= doc/09 §4 的 IN_CALL→DISCONNECTED 行为）
	//    房间随即销毁，此后任何 join 都是 ROOM_NOT_FOUND。
	stopAgain := again.startHeartbeat(200 * time.Millisecond)
	defer stopAgain()
	host.sendRaw(`{"type":"leave"}`)
	tLeave := time.Now()
	deadline := time.Now().Add(3 * time.Second)
	for s.Manager().Count() != 0 && time.Now().Before(deadline) {
		time.Sleep(2 * time.Millisecond)
	}
	destroyDelay := time.Since(tLeave)
	if n := s.Manager().Count(); n != 0 {
		t.Fatalf("survivor leave 后房间应销毁，实际 %d", n)
	}

	late := dial(t, "LATE", url)
	late.sendRaw(fmt.Sprintf(`{"type":"join","roomId":"%s"}`, roomID))
	late.expectError("ROOM_NOT_FOUND")

	t.Logf("实测时序（PongWait=%v）：read_timeout_reap=%v；survivor_keeps_room→rejoin_ok=%v；"+
		"survivor_immediate_leave→room_destroyed=%v（此后 join=ROOM_NOT_FOUND）",
		pongWait, reapLatency.Round(time.Millisecond), rejoinLatency.Round(time.Millisecond),
		destroyDelay.Round(time.Millisecond))

	// 断言：回收耗时应在 PongWait 附近（读超时生效 + 少量调度抖动）
	if reapLatency < pongWait*8/10 || reapLatency > pongWait+2*time.Second {
		t.Fatalf("读超时回收耗时应≈%v，实际 %v", pongWait, reapLatency)
	}
}
