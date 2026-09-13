// Package room 提供房间、Peer 与房间管理器的并发安全实现。
//
// 与 doc/09 §8 / doc/12 §6 的伪代码相比，本实现的字段是私有的并配以访问器：
// 伪代码里 Peer.ID / Peer.Room 是导出字段，但读循环、写循环与清理协程会并发访问，
// 裸导出字段无法加锁。字段改名不影响对外协议，仅影响包内实现，故做此封装。
package room

import (
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/sirupsen/logrus"
)

// PeerSendQueueSize 是 peer 发送队列容量。
//
// ICE 是「涓流」发送，一个开启 trickle 的客户端在几秒内可能连发数十个 candidate，
// 加上 SDP/DTLS 等消息，队列太小会在对端读得稍慢时丢包（信令丢 ICE 会直接导致建连失败）。
// 因此容量取 256，并配合 SendTimeout 的「等待式投递」而非「满即丢」。
const PeerSendQueueSize = 256

// Peer 发送/关闭相关错误。
var (
	// ErrPeerBufferFull 发送队列已满，消息被丢弃。
	ErrPeerBufferFull = errors.New("peer send buffer full")
	// ErrPeerClosed peer 连接已关闭。
	ErrPeerClosed = errors.New("peer connection closed")
)

// PeerOptions 是 peer 的网络行为参数，来自 config.Config。
type PeerOptions struct {
	MaxMessageBytes int64         // 单条消息上限（doc/09 §1：64KB）
	WriteTimeout    time.Duration // 单次写超时
	PongWait        time.Duration // 读超时（ping/pong 超时判定）
	SendTimeout     time.Duration // 队列满时等待写协程腾出空间的最长时间
}

// defaultSendTimeout 是 SendTimeout 未配置时的兜底值。
const defaultSendTimeout = 5 * time.Second

// Peer 表示一条 WebSocket 连接对应的一端（doc/09 §8）。
type Peer struct {
	conn *websocket.Conn
	send chan []byte
	done chan struct{}

	closeOnce sync.Once
	opts      PeerOptions

	mu          sync.RWMutex
	id          string // "peer-001" / "peer-002"，未入房时为空
	room        *Room
	connectedAt time.Time
	remoteAddr  string
	userAgent   string
	log         *logrus.Logger
}

// NewPeer 创建一个尚未入房的 peer（peerId 在 create/join 成功时分配）。
func NewPeer(conn *websocket.Conn, opts PeerOptions, log *logrus.Logger) *Peer {
	remote := ""
	if conn != nil {
		remote = conn.RemoteAddr().String()
	}
	if log == nil {
		log = logrus.New()
	}
	if opts.SendTimeout <= 0 {
		opts.SendTimeout = defaultSendTimeout
	}
	return &Peer{
		conn:        conn,
		send:        make(chan []byte, PeerSendQueueSize),
		done:        make(chan struct{}),
		opts:        opts,
		connectedAt: time.Now(),
		remoteAddr:  remote,
		log:         log,
	}
}

// SetID 设置本端 peerId（create/join 成功后由服务端分配）。
func (p *Peer) SetID(id string) {
	p.mu.Lock()
	p.id = id
	p.mu.Unlock()
}

// ID 返回本端 peerId（未入房时为空字符串）。
func (p *Peer) ID() string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.id
}

// SetRoom 设置所属房间（nil 表示已不在任何房间）。
func (p *Peer) SetRoom(r *Room) {
	p.mu.Lock()
	p.room = r
	p.mu.Unlock()
}

// Room 返回所属房间，未入房时为 nil。
func (p *Peer) Room() *Room {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.room
}

// RemoteAddr 返回对端地址，用于日志。
func (p *Peer) RemoteAddr() string { return p.remoteAddr }

// SetUserAgent 记录握手时的 User-Agent（由 server 层从 http.Request 取得）。
func (p *Peer) SetUserAgent(ua string) {
	p.mu.Lock()
	p.userAgent = ua
	p.mu.Unlock()
}

// ConnectedAt 返回连接建立时间。
func (p *Peer) ConnectedAt() time.Time { return p.connectedAt }

// Log 返回带契约字段的日志条目（doc/14 §9.1：layer=go，tag 由调用方指定；
// 高频字段固定为 peer=<peerId>、room=<roomId>）。
func (p *Peer) Log(tag string) *logrus.Entry {
	p.mu.RLock()
	fields := logrus.Fields{
		"tag":    tag,
		"peer":   p.id,
		"remote": p.remoteAddr,
	}
	if p.userAgent != "" {
		fields["user_agent"] = p.userAgent
	}
	r := p.room
	p.mu.RUnlock()

	if r != nil {
		fields["room"] = r.ID
		fields["peers"] = r.PeerCount()
	}
	return p.log.WithFields(fields)
}

// SendMessage 将结构体序列化为 JSON 后入队；队列满返回 ErrPeerBufferFull。
func (p *Peer) SendMessage(msg interface{}) error {
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	return p.SendRaw(data)
}

// SendRaw 把已序列化的消息投递给写协程。
//
// offer/answer/ice/natType 转发走这条路径：直接投递原始字节，
// 保证「原样转发、不修改内容」（doc/09 §7）。
//
// 投递语义：
//  1. 队列有空位 → 立即入队（快路径，绝大多数情况）；
//  2. 队列已满（对端读得慢/TCP 背压）→ 最多等待 SendTimeout 让写协程腾出空间，
//     而不是直接丢弃。信令里丢掉一个 ICE candidate 就可能让通话建不起来，
//     因此宁可让发送方短暂阻塞（背压），也不静默丢包；
//  3. 超时仍无空间 → 返回 ErrPeerBufferFull，由调用方记录并丢弃该条消息，
//     同时说明对端确实已经不可用。
func (p *Peer) SendRaw(data []byte) error {
	// 快路径
	select {
	case p.send <- data:
		return nil
	case <-p.done:
		return ErrPeerClosed
	default:
	}

	// 慢路径：等待写协程消费
	timer := time.NewTimer(p.opts.SendTimeout)
	defer timer.Stop()
	select {
	case p.send <- data:
		return nil
	case <-p.done:
		return ErrPeerClosed
	case <-timer.C:
		return ErrPeerBufferFull
	}
}

// WriteLoop 是每个 peer 唯一的写协程。
//
// gorilla/websocket 不允许对同一连接并发写，因此所有写操作
// （包括服务端应答与转发）都集中在此，其它协程只往 p.send 投递。
func (p *Peer) WriteLoop() {
	for {
		select {
		case <-p.done:
			return
		case msg := <-p.send:
			_ = p.conn.SetWriteDeadline(time.Now().Add(p.opts.WriteTimeout))
			if err := p.conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				p.Log("signaling").WithError(err).WithField("close_conn", "true").Warn("ws_write_failed")
				p.Close()
				return
			}
		}
	}
}

// ReadLoop 是每个 peer 唯一的读协程。
//
// 职责：
//  1. 设置单条消息大小上限（超过时 gorilla 会以 1009 关闭连接）；
//  2. 设置读超时实现 ping/pong 超时检测：每条消息（含 pong 控制帧）都会刷新；
//  3. 逐条调用 handle 分发。
//
// 循环退出时通过 onClose 回调把错误交给上层（用于日志与房间清理）。
func (p *Peer) ReadLoop(handle func(data []byte), onClose func(err error)) {
	p.conn.SetReadLimit(p.opts.MaxMessageBytes)
	_ = p.conn.SetReadDeadline(time.Now().Add(p.opts.PongWait))
	p.conn.SetPongHandler(func(string) error {
		// 控制帧 pong：刷新读超时（客户端若用 WebSocket 控制帧心跳也能保活）
		return p.conn.SetReadDeadline(time.Now().Add(p.opts.PongWait))
	})

	var readErr error
	for {
		_, data, err := p.conn.ReadMessage()
		if err != nil {
			readErr = err
			break
		}
		// 应用层 ping 同样是活跃证据，统一刷新读超时
		_ = p.conn.SetReadDeadline(time.Now().Add(p.opts.PongWait))
		handle(data)
	}
	if onClose != nil {
		onClose(readErr)
	}
}

// Close 幂等关闭连接：关闭 done 通道、尽力发送 close 帧并关闭底层 TCP。
func (p *Peer) Close() {
	p.closeOnce.Do(func() {
		close(p.done)
		_ = p.conn.WriteControl(websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""),
			time.Now().Add(time.Second))
		_ = p.conn.Close()
	})
}

// Closed 返回本连接是否已被服务端主动关闭。
//
// 用途：gorilla/websocket v1.5.1 的 hideTempErr 会把底层 net.Error
// （含 net.ErrClosed）重新包成自己的 *netError，错误链被截断，
// 因此上层无法用 errors.Is(err, net.ErrClosed) 判断；
// 这里用 done 通道记录关闭事实，作为断开原因的可靠依据。
func (p *Peer) Closed() bool {
	select {
	case <-p.done:
		return true
	default:
		return false
	}
}
