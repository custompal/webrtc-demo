# 12 — Go 后端信令服务实现指南

> 本文档定义 `signaling/` 目录下的全部 Go 代码。
> Agent 按此文档生成后端信令服务，实现 WebSocket 房间管理和消息转发。

## 1. 技术栈

| 项目 | 版本 |
|---|---|
| 语言 | Go 1.22 |
| WebSocket | `gorilla/websocket` v1.5.1 |
| 日志 | `log/slog`（标准库） |
| 构建 | `go build`，无第三方框架 |
| 部署 | systemd 直接跑二进制 |

## 2. 目录结构

```
signaling/
├── go.mod
├── main.go                     # 入口
├── server/
│   ├── server.go               # HTTP + WebSocket 服务
│   └── ws_handler.go           # WebSocket 连接处理
├── room/
│   ├── manager.go              # 房间管理器
│   ├── room.go                 # 房间结构与方法
│   └── peer.go                 # 客户端 peer 结构
├── protocol/
│   ├── message.go              # 消息类型定义
│   └── errors.go               # 错误码定义
├── config/
│   └── config.go               # 配置结构
└── util/
    └── roomid.go               # roomId 生成
```

## 3. 消息类型定义

```go
// protocol/message.go
package protocol

// 消息类型常量
const (
    TypeCreate    = "create"
    TypeCreated   = "created"
    TypeJoin      = "join"
    TypeJoined    = "joined"
    TypeOffer     = "offer"
    TypeAnswer    = "answer"
    TypeIce       = "ice"
    TypeNatType   = "natType"
    TypePeerJoined = "peerJoined"
    TypePeerLeft   = "peerLeft"
    TypeLeave     = "leave"
    TypeError     = "error"
    TypePing      = "ping"
    TypePong      = "pong"
)

// Message 通用消息结构（用于解析 type）
type Message struct {
    Type string `json:"type"`
}

// CreateRequest 创建房间请求
type CreateRequest struct {
    Type string `json:"type"` // "create"
}

// CreatedResponse 创建房间成功响应
type CreatedResponse struct {
    Type           string `json:"type"`            // "created"
    RoomID         string `json:"roomId"`         // 6 字符
    StunURL        string `json:"stunUrl"`        // "stun:1.2.3.4:3478"
    TurnURL        string `json:"turnUrl"`         // "turn:1.2.3.4:3478?transport=udp"
    TurnUsername   string `json:"turnUsername"`   // "demo"
    TurnCredential string `json:"turnCredential"` // "demopass"
}

// JoinRequest 加入房间请求
type JoinRequest struct {
    Type   string `json:"type"`   // "join"
    RoomID string `json:"roomId"` // 6 字符
}

// JoinedResponse 加入成功响应
type JoinedResponse struct {
    Type           string `json:"type"`            // "joined"
    RoomID         string `json:"roomId"`
    StunURL        string `json:"stunUrl"`
    TurnURL        string `json:"turnUrl"`
    TurnUsername   string `json:"turnUsername"`
    TurnCredential string `json:"turnCredential"`
    PeerID        string `json:"peerId"`          // "peer-001" 或 "peer-002"
}

// PeerJoinedNotify 对端加入通知
type PeerJoinedNotify struct {
    Type   string `json:"type"`    // "peerJoined"
    PeerID string `json:"peerId"`
}

// PeerLeftNotify 对端离开通知
type PeerLeftNotify struct {
    Type   string `json:"type"`    // "peerLeft"
    PeerID string `json:"peerId"`
}

// OfferMessage SDP Offer
type OfferMessage struct {
    Type string `json:"type"`      // "offer"
    SDP  string `json:"sdp"`
}

// AnswerMessage SDP Answer
type AnswerMessage struct {
    Type string `json:"type"`      // "answer"
    SDP  string `json:"sdp"`
}

// IceMessage ICE Candidate
type IceMessage struct {
    Type          string `json:"type"`           // "ice"
    Candidate     string `json:"candidate"`
    SDPMid        string `json:"sdpMid"`
    SDPMLineIndex int    `json:"sdpMLineIndex"`
}

// NatTypeMessage NAT 类型交换
type NatTypeMessage struct {
    Type    string `json:"type"`    // "natType"
    NatType string `json:"natType"`
}

// LeaveMessage 离开
type LeaveMessage struct {
    Type string `json:"type"` // "leave"
}

// ErrorMessage 错误
type ErrorMessage struct {
    Type    string `json:"type"`    // "error"
    Code    string `json:"code"`    // 错误码
    Message string `json:"message"` // 错误描述
}

// PingMessage 心跳
type PingMessage struct {
    Type      string `json:"type"`      // "ping"
    Timestamp int64  `json:"timestamp"` // Unix 毫秒
}

// PongMessage 心跳响应
type PongMessage struct {
    Type      string `json:"type"`      // "pong"
    Timestamp int64  `json:"timestamp"` // Unix 毫秒
}
```

```go
// protocol/errors.go
package protocol

// 错误码
const (
    ErrRoomNotFound    = "ROOM_NOT_FOUND"
    ErrRoomFull        = "ROOM_FULL"
    ErrRoomExpired     = "ROOM_EXPIRED"
    ErrInvalidMessage  = "INVALID_MESSAGE"
    ErrNotInRoom       = "NOT_IN_ROOM"
    ErrInternalError   = "INTERNAL_ERROR"
)

// 错误消息文本
var errorMessages = map[string]string{
    ErrRoomNotFound:   "Room does not exist",
    ErrRoomFull:       "Room is full (max 2 peers)",
    ErrRoomExpired:    "Room has expired",
    ErrInvalidMessage: "Invalid message format",
    ErrNotInRoom:      "You are not in a room",
    ErrInternalError:  "Internal server error",
}

func NewError(code string) ErrorMessage {
    msg, ok := errorMessages[code]
    if !ok {
        msg = "Unknown error"
    }
    return ErrorMessage{
        Type:    TypeError,
        Code:    code,
        Message: msg,
    }
}
```

## 4. 配置

```go
// config/config.go
package config

type Config struct {
    ListenAddr    string // ":8443"
    StunURL       string // "stun:1.2.3.4:3478"
    TurnURL       string // "turn:1.2.3.4:3478?transport=udp"
    TurnUsername  string // "demo"
    TurnPassword  string // "demopass"
    RoomExpirySec int    // 1800 (30 分钟)
}

func Default() *Config {
    return &Config{
        ListenAddr:    ":8443",
        StunURL:       "stun:1.2.3.4:3478",
        TurnURL:       "turn:1.2.3.4:3478?transport=udp",
        TurnUsername:  "demo",
        TurnPassword:  "demopass",
        RoomExpirySec: 1800,
    }
}
```

## 5. roomId 生成

```go
// util/roomid.go
package util

import (
    "math/rand"
    "time"
)

// 排除易混淆字符 O/0/I/1/L
const charset = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

func init() {
    rand.Seed(time.Now().UnixNano())
}

// GenerateRoomID 生成 6 字符随机房间 ID
func GenerateRoomID() string {
    b := make([]byte, 6)
    for i := range b {
        b[i] = charset[rand.Intn(len(charset))]
    }
    return string(b)
}
```

## 6. Peer 结构

```go
// room/peer.go
package room

import (
    "encoding/json"
    "sync"
    "github.com/gorilla/websocket"
)

type Peer struct {
    ID   string                          // "peer-001" 或 "peer-002"
    Conn *websocket.Conn                  // WebSocket 连接
    Room *Room                            // 所在房间
    Send chan []byte                       // 发送队列
    mu   sync.Mutex
}

func NewPeer(id string, conn *websocket.Conn) *Peer {
    return &Peer{
        ID:   id,
        Conn: conn,
        Send: make(chan []byte, 16),
    }
}

// SendMessage 发送消息给该 peer
func (p *Peer) SendMessage(msg interface{}) error {
    data, err := json.Marshal(msg)
    if err != nil {
        return err
    }
    select {
    case p.Send <- data:
        return nil
    default:
        return ErrPeerBufferFull
    }
}

// ReadLoop 读取循环
func (p *Peer) ReadLoop(handle func([]byte)) {
    defer func() {
        if p.Room != nil {
            p.Room.RemovePeer(p)
        }
    }()

    for {
        _, data, err := p.Conn.ReadMessage()
        if err != nil {
            break
        }
        handle(data)
    }
}

// WriteLoop 写入循环
func (p *Peer) WriteLoop() {
    for msg := range p.Send {
        err := p.Conn.WriteMessage(websocket.TextMessage, msg)
        if err != nil {
            break
        }
    }
}
```

## 7. Room 结构

```go
// room/room.go
package room

import (
    "sync"
    "time"
)

type Room struct {
    ID        string
    Peers     [2]*Peer     // 最多 2 人
    CreatedAt time.Time
    mu        sync.Mutex
}

func NewRoom(id string) *Room {
    return &Room{
        ID:        id,
        CreatedAt: time.Now(),
    }
}

// AddPeer 添加 peer 到房间，返回 peerId 和是否已满
func (r *Room) AddPeer(p *Peer) (string, bool) {
    r.mu.Lock()
    defer r.mu.Unlock()

    for i := 0; i < 2; i++ {
        if r.Peers[i] == nil {
            r.Peers[i] = p
            p.Room = r
            if i == 0 {
                return "peer-001", true
            }
            return "peer-002", true
        }
    }
    return "", false // 房间已满
}

// GetOtherPeer 获取对端
func (r *Room) GetOtherPeer(self *Peer) *Peer {
    r.mu.Lock()
    defer r.mu.Unlock()

    for i := 0; i < 2; i++ {
        if r.Peers[i] != nil && r.Peers[i] != self {
            return r.Peers[i]
        }
    }
    return nil
}

// RemovePeer 移除 peer
func (r *Room) RemovePeer(p *Peer) {
    r.mu.Lock()
    defer r.mu.Unlock()

    for i := 0; i < 2; i++ {
        if r.Peers[i] == p {
            r.Peers[i] = nil
            // 通知对端
            other := r.GetOtherPeerLocked(p)
            if other != nil {
                other.SendMessage(protocol.PeerLeftNotify{
                    Type:   protocol.TypePeerLeft,
                    PeerID: p.ID,
                })
            }
            break
        }
    }
}

// IsEmpty 房间是否为空
func (r *Room) IsEmpty() bool {
    r.mu.Lock()
    defer r.mu.Unlock()
    return r.Peers[0] == nil && r.Peers[1] == nil
}

// IsExpired 是否过期
func (r *Room) IsExpired(expirySec int) bool {
    return time.Since(r.CreatedAt) > time.Duration(expirySec)*time.Second
}

func (r *Room) GetOtherPeerLocked(self *Peer) *Peer {
    for i := 0; i < 2; i++ {
        if r.Peers[i] != nil && r.Peers[i] != self {
            return r.Peers[i]
        }
    }
    return nil
}
```

## 8. RoomManager

```go
// room/manager.go
package room

import (
    "sync"
    "time"
    "yourmodule/config"
    "yourmodule/util"
)

var (
    ErrPeerBufferFull = errors.New("peer send buffer full")
)

type Manager struct {
    rooms  map[string]*Room
    mu     sync.RWMutex
    config *config.Config
}

func NewManager(cfg *config.Config) *Manager {
    m := &Manager{
        rooms:  make(map[string]*Room),
        config: cfg,
    }
    // 启动过期清理协程
    go m.cleanupLoop()
    return m
}

// CreateRoom 创建房间，返回 room 和 peerId
func (m *Manager) CreateRoom() (*Room, string) {
    m.mu.Lock()
    defer m.mu.Unlock()

    // 生成唯一 roomId
    var roomID string
    for {
        roomID = util.GenerateRoomID()
        if _, exists := m.rooms[roomID]; !exists {
            break
        }
    }

    room := NewRoom(roomID)
    m.rooms[roomID] = room
    return room, "peer-001"
}

// JoinRoom 加入房间
func (m *Manager) JoinRoom(roomID string) (*Room, string, error) {
    m.mu.RLock()
    room, exists := m.rooms[roomID]
    m.mu.RUnlock()

    if !exists {
        return nil, "", ErrRoomNotFound
    }

    if room.IsExpired(m.config.RoomExpirySec) {
        m.RemoveRoom(roomID)
        return nil, "", ErrRoomExpired
    }

    // AddPeer 会检查是否已满
    return room, "", nil
}

// RemoveRoom 删除房间
func (m *Manager) RemoveRoom(roomID string) {
    m.mu.Lock()
    defer m.mu.Unlock()
    delete(m.rooms, roomID)
}

// cleanupLoop 定时清理过期房间
func (m *Manager) cleanupLoop() {
    ticker := time.NewTicker(60 * time.Second)
    defer ticker.Stop()

    for range ticker.C {
        m.mu.RLock()
        for id, room := range m.rooms {
            if room.IsEmpty() || room.IsExpired(m.config.RoomExpirySec) {
                m.mu.RUnlock()
                m.RemoveRoom(id)
                m.mu.RLock()
            }
        }
        m.mu.RUnlock()
    }
}
```

## 9. WebSocket 处理

```go
// server/ws_handler.go
package server

import (
    "encoding/json"
    "net/http"
    "github.com/gorilla/websocket"
    "yourmodule/config"
    "yourmodule/protocol"
    "yourmodule/room"
)

var upgrader = websocket.Upgrader{
    CheckOrigin: func(r *http.Request) bool {
        return true // 学习 demo，不校验 origin
    },
}

type Server struct {
    config  *config.Config
    manager *room.Manager
}

func NewServer(cfg *config.Config) *Server {
    return &Server{
        config:  cfg,
        manager: room.NewManager(cfg),
    }
}

// HandleWS WebSocket 处理函数
func (s *Server) HandleWS(w http.ResponseWriter, r *http.Request) {
    conn, err := upgrader.Upgrade(w, r, nil)
    if err != nil {
        slog.Error("WebSocket upgrade failed", "err", err)
        return
    }

    peer := room.NewPeer("", conn)
    go peer.WriteLoop()

    // 读取循环
    peer.ReadLoop(func(data []byte) {
        s.handleMessage(peer, data)
    })

    // 连接关闭
    conn.Close()
}

func (s *Server) handleMessage(peer *room.Peer, data []byte) {
    var msg protocol.Message
    if err := json.Unmarshal(data, &msg); err != nil {
        peer.SendMessage(protocol.NewError(protocol.ErrInvalidMessage))
        return
    }

    switch msg.Type {
    case protocol.TypeCreate:
        s.handleCreate(peer)
    case protocol.TypeJoin:
        s.handleJoin(peer, data)
    case protocol.TypeOffer:
        s.forwardToPeer(peer, data)
    case protocol.TypeAnswer:
        s.forwardToPeer(peer, data)
    case protocol.TypeIce:
        s.forwardToPeer(peer, data)
    case protocol.TypeNatType:
        s.forwardToPeer(peer, data)
    case protocol.TypeLeave:
        s.handleLeave(peer)
    case protocol.TypePing:
        s.handlePing(peer, data)
    default:
        peer.SendMessage(protocol.NewError(protocol.ErrInvalidMessage))
    }
}

func (s *Server) handleCreate(peer *room.Peer) {
    room, peerId := s.manager.CreateRoom()
    peer.ID = peerId
    peer.Room = room
    // 将 peer 加入房间
    room.Peers[0] = peer

    peer.SendMessage(protocol.CreatedResponse{
        Type:           protocol.TypeCreated,
        RoomID:         room.ID,
        StunURL:        s.config.StunURL,
        TurnURL:        s.config.TurnURL,
        TurnUsername:   s.config.TurnUsername,
        TurnCredential: s.config.TurnPassword,
    })
}

func (s *Server) handleJoin(peer *room.Peer, data []byte) {
    var req protocol.JoinRequest
    json.Unmarshal(data, &req)

    room, _, err := s.manager.JoinRoom(req.RoomID)
    if err != nil {
        code := protocol.ErrRoomNotFound
        if err.Error() == "expired" {
            code = protocol.ErrRoomExpired
        }
        peer.SendMessage(protocol.NewError(code))
        return
    }

    // 尝试加入
    peerId, ok := room.AddPeer(peer)
    if !ok {
        peer.SendMessage(protocol.NewError(protocol.ErrRoomFull))
        return
    }
    peer.ID = peerId

    // 发 joined 给新加入者
    peer.SendMessage(protocol.JoinedResponse{
        Type:           protocol.TypeJoined,
        RoomID:         room.ID,
        StunURL:        s.config.StunURL,
        TurnURL:        s.config.TurnURL,
        TurnUsername:   s.config.TurnUsername,
        TurnCredential: s.config.TurnPassword,
        PeerID:         peerId,
    })

    // 通知房间内的对端
    other := room.GetOtherPeer(peer)
    if other != nil {
        other.SendMessage(protocol.PeerJoinedNotify{
            Type:   protocol.TypePeerJoined,
            PeerID: peerId,
        })
    }
}

// forwardToPeer 转发消息给同房间对端（原样转发）
func (s *Server) forwardToPeer(peer *room.Peer, data []byte) {
    if peer.Room == nil {
        peer.SendMessage(protocol.NewError(protocol.ErrNotInRoom))
        return
    }
    other := peer.Room.GetOtherPeer(peer)
    if other == nil {
        return // 对端不在线，静默丢弃
    }
    // 原样转发（data 已经是 JSON）
    select {
    case other.Send <- data:
    default:
        slog.Warn("peer buffer full, dropping message",
            "peer", other.ID)
    }
}

func (s *Server) handleLeave(peer *room.Peer) {
    if peer.Room != nil {
        peer.Room.RemovePeer(peer)
        peer.Room = nil
    }
}

func (s *Server) handlePing(peer *room.Peer, data []byte) {
    var ping protocol.PingMessage
    json.Unmarshal(data, &ping)

    peer.SendMessage(protocol.PongMessage{
        Type:      protocol.TypePong,
        Timestamp: time.Now().UnixMilli(),
    })
}
```

## 10. main.go

```go
// main.go
package main

import (
    "flag"
    "log/slog"
    "net/http"
    "yourmodule/config"
    "yourmodule/server"
)

func main() {
    addr := flag.String("addr", ":8443", "监听地址")
    stun := flag.String("stun", "stun:1.2.3.4:3478", "STUN 服务器 URL")
    turn := flag.String("turn", "turn:1.2.3.4:3478?transport=udp", "TURN 服务器 URL")
    turnUser := flag.String("user", "demo:demopass", "TURN 用户名:密码")
    flag.Parse()

    cfg := &config.Config{
        ListenAddr:    *addr,
        StunURL:       *stun,
        TurnURL:       *turn,
        TurnUsername:  "demo",
        TurnPassword:  "demopass",
        RoomExpirySec: 1800,
    }

    s := server.NewServer(cfg)
    http.HandleFunc("/ws", s.HandleWS)

    slog.Info("信令服务启动",
        "addr", cfg.ListenAddr,
        "stun", cfg.StunURL,
        "turn", cfg.TurnURL)

    if err := http.ListenAndServe(cfg.ListenAddr, nil); err != nil {
        slog.Error("服务启动失败", "err", err)
        panic(err)
    }
}
```

## 11. go.mod

```go
module webrtcdemo-signaling

go 1.22

require (
    github.com/gorilla/websocket v1.5.1
)
```

## 12. 部署

### 12.1 编译

```bash
# 在云主机上编译（Linux amd64）
cd signaling/
GOOS=linux GOARCH=amd64 go build -o /opt/signaling/signaling .

# 或交叉编译（在 Windows 上）
set GOOS=linux
set GOARCH=amd64
go build -o signaling
# scp 到云主机
```

### 12.2 systemd 服务

```ini
# /etc/systemd/system/signaling.service
[Unit]
Description=WebRTC Demo Signaling Server
After=network.target

[Service]
Type=simple
ExecStart=/opt/signaling/signaling \
    -addr :8443 \
    -stun stun:<CLOUD_IP>:3478 \
    -turn turn:<CLOUD_IP>:3478?transport=udp \
    -user demo:demopass
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable signaling
sudo systemctl start signaling
sudo systemctl status signaling
```

### 12.3 Caddy 反代（TLS）

```bash
# /etc/caddy/Caddyfile
<CLOUD_IP>:443 {
    reverse_proxy localhost:8443
}
```

```bash
sudo systemctl restart caddy
```

> Android 客户端连接 `wss://<CLOUD_IP>/ws`（443 端口，Caddy 反代到 8443）。

## 13. 测试验证

```bash
# 用 wscat 测试
npm install -g wscat

# 创建房间
wscat -c wss://<CLOUD_IP>/ws
> {"type":"create"}
< {"type":"created","roomId":"XXXXXX",...}

# 另一个终端加入
wscat -c wss://<CLOUD_IP>/ws
> {"type":"join","roomId":"XXXXXX"}
< {"type":"joined",...}

# 第一个终端收到
< {"type":"peerJoined","peerId":"peer-002"}
```
