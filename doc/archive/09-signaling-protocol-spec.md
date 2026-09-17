> **Archived 2026-09-17** — superseded by `doc/design/`; archived verbatim as `doc/archive/09-signaling-protocol-spec.md`. This copy is history, not current guidance.

# 09 — 信令协议精确规格

> 本文档定义 Android 客户端与 Go 信令服务之间的 WebSocket 协议。
> 前后端两个 agent 各自按此文档实现，接口无歧义。

## 1. 传输层

| 项目 | 值 |
|---|---|
| 协议 | WebSocket（RFC 6455） |
| 安全 | WSS（TLS），经 Caddy 反代 443 → 8443 |
| URL | `wss://<SERVER_HOST>/ws` |
| 帧格式 | 文本帧（UTF-8 JSON） |
| 单条消息最大 | 64KB（SDP 约 2-4KB，足够） |

## 2. 消息通用格式

所有消息均为 JSON 对象，必须包含 `type` 字段。

```json
{
  "type": "<MessageType>",
  ... 其他字段
}
```

### 2.1 消息类型枚举

| type 值 | 方向 | 说明 |
|---|---|---|
| `create` | C → S | 发起方创建房间 |
| `created` | S → C | 房间创建成功 |
| `join` | C → S | 加入方加入房间 |
| `joined` | S → C | 加入成功 |
| `error` | S → C | 错误响应 |
| `offer` | C → S → C | SDP Offer |
| `answer` | C → S → C | SDP Answer |
| `ice` | C → S → C | ICE Candidate |
| `natType` | C → S → C | NAT 类型交换 |
| `peerJoined` | S → C | 对端加入通知 |
| `peerLeft` | S → C | 对端离开通知 |
| `leave` | C → S | 主动离开 |
| `ping` | C → S | 心跳请求 |
| `pong` | S → C | 心跳响应 |

## 3. 各消息精确定义

### 3.1 create — 创建房间

**请求（C → S）**：
```json
{
  "type": "create"
}
```

**成功响应 created（S → C）**：
```json
{
  "type": "created",
  "roomId": "A1B2C3",
  "stunUrl": "stun:1.2.3.4:3478",
  "turnUrl": "turn:1.2.3.4:3478?transport=udp",
  "turnUsername": "demo",
  "turnCredential": "demopass"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"created"` |
| `roomId` | string | 是 | 6 字符大写字母+数字，服务端生成 |
| `stunUrl` | string | 是 | STUN 服务器 URL |
| `turnUrl` | string | 是 | TURN 服务器 URL（含 transport 参数） |
| `turnUsername` | string | 是 | TURN 用户名 |
| `turnCredential` | string | 是 | TURN 密码 |

### 3.2 join — 加入房间

**请求（C → S）**：
```json
{
  "type": "join",
  "roomId": "A1B2C3"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"join"` |
| `roomId` | string | 是 | 6 字符房间 ID |

**成功响应 joined（S → C）**：
```json
{
  "type": "joined",
  "roomId": "A1B2C3",
  "stunUrl": "stun:1.2.3.4:3478",
  "turnUrl": "turn:1.2.3.4:3478?transport=udp",
  "turnUsername": "demo",
  "turnCredential": "demopass",
  "peerId": "peer-002"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"joined"` |
| `roomId` | string | 是 | 房间 ID |
| `stunUrl` | string | 是 | STUN URL |
| `turnUrl` | string | 是 | TURN URL |
| `turnUsername` | string | 是 | TURN 用户名 |
| `turnCredential` | string | 是 | TURN 密码 |
| `peerId` | string | 是 | 服务端分配的本端 ID（`peer-001`/`peer-002`） |

### 3.3 peerJoined — 对端加入通知（S → C）

当房间已有一人，第二人加入时，服务端向第一人发送：
```json
{
  "type": "peerJoined",
  "peerId": "peer-002"
}
```

**规则**：收到 `peerJoined` 的一方是**发起方**（应发 Offer）。

### 3.4 offer — SDP Offer（C → S → C）

**发起方发送（C → S）**：
```json
{
  "type": "offer",
  "sdp": "v=0\r\no=- 123456 2 IN IP4 127.0.0.1\r\n..."
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"offer"` |
| `sdp` | string | 是 | 完整 SDP 文本（RFC 4566 格式） |

**服务端**：收到 offer 后，按 roomId 转发给对端（原样转发，不修改）。

### 3.5 answer — SDP Answer（C → S → C）

```json
{
  "type": "answer",
  "sdp": "v=0\r\no=- 789012 2 IN IP4 127.0.0.1\r\n..."
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"answer"` |
| `sdp` | string | 是 | 完整 SDP 文本 |

### 3.6 ice — ICE Candidate（C → S → C）

```json
{
  "type": "ice",
  "candidate": "candidate:842163049 1 udp 1677729535 1.2.3.4 3478 typ srflx raddr 0.0.0.0 generation 0",
  "sdpMid": "0",
  "sdpMLineIndex": 0
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"ice"` |
| `candidate` | string | 是 | ICE candidate 文本（RFC 5245） |
| `sdpMid` | string | 否 | 媒体流标识，如 `"0"`、`"1"` |
| `sdpMLineIndex` | number | 否 | 媒体行索引，0=音频 1=视频 |

> `sdpMid` 和 `sdpMLineIndex` 至少有一个非空。

### 3.7 natType — NAT 类型交换（C → S → C）

```json
{
  "type": "natType",
  "natType": "Symmetric"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"natType"` |
| `natType` | string | 是 | 枚举值见下表 |

**natType 枚举值**：

| 值 | 说明 |
|---|---|
| `"Open"` | 无 NAT（公网 IP） |
| `"FullCone"` | Full-Cone NAT |
| `"RestrictedCone"` | Restricted Cone |
| `"PortRestrictedCone"` | Port Restricted Cone |
| `"Symmetric"` | Symmetric NAT |
| `"Unknown"` | 探测失败 |

### 3.8 leave — 主动离开（C → S）

```json
{
  "type": "leave"
}
```

服务端收到后：
1. 向同 room 对端发 `peerLeft`
2. 关闭该 WebSocket 连接
3. 销毁房间

### 3.9 peerLeft — 对端离开通知（S → C）

```json
{
  "type": "peerLeft",
  "peerId": "peer-002"
}
```

### 3.10 error — 错误响应（S → C）

```json
{
  "type": "error",
  "code": "ROOM_NOT_FOUND",
  "message": "Room A1B2C3 does not exist"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"error"` |
| `code` | string | 是 | 错误码枚举（见下表） |
| `message` | string | 是 | 人类可读错误描述（英文） |

**错误码表**：

| code | 说明 | 触发场景 |
|---|---|---|
| `ROOM_NOT_FOUND` | 房间不存在 | join 时 roomId 无效或已被销毁 |
| `ROOM_FULL` | 房间已满 | join 时房间已有 2 人 |
| `ROOM_EXPIRED` | 房间已过期 | 房间超过 30 分钟无人加入 |
| `INVALID_MESSAGE` | 消息格式错误 | JSON 解析失败 / 缺必填字段 / type 不合法 |
| `NOT_IN_ROOM` | 未在房间中 | 在未 join 的情况下发 offer/answer/ice/leave |
| `INTERNAL_ERROR` | 服务内部错误 | Go panic / 其他未预期错误 |

### 3.11 ping / pong — 心跳

**客户端 ping（C → S）**：
```json
{
  "type": "ping",
  "timestamp": 1715432100000
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | 固定 `"ping"` |
| `timestamp` | number | 是 | Unix 毫秒时间戳 |

**服务端 pong（S → C）**：
```json
{
  "type": "pong",
  "timestamp": 1715432100005
}
```

## 4. 客户端状态机

```
                    ┌──────────┐
              ┌─────→│ DISCONNECTED │←──────────┐
              │      └──────┘                │
              │        │ connect WSS           │ error / timeout / leave
              │        ▼                       │
              │      ┌──────────┐             │
              │      │ CONNECTING │              │
              │      └──────┘             │
              │        │ WS open               │
              │        ▼                       │
              │      ┌──────────┐             │
              │      │ CONNECTED │───────────────┘
              │      └──────┘
              │        │ send "create" or "join"
              │        ▼
              │      ┌──────────────────┐
              │      │ wait created/joined │
              │      └──────────────────┘
              │        │ received created/joined
              │        ▼
              │      ┌──────────┐
              │      │ IN_ROOM  │
              │      └──────┘
              │     /          \
              │  peerJoined /   peerJoined received
              │  received         (I'm joiner, wait for offer)
              │  (I'm host,
              │   send offer)
              │     \          /
              │      \        /
              │      ┌──────────┐
              │      │ IN_CALL  │
              │      └──────┘
              │        │ leave / peerLeft / error
              └────────┘
```

**状态枚举**：

| 状态 | 说明 | 触发转移 |
|---|---|---|
| `DISCONNECTED` | 未连接 | 用户点击创建/加入 → `CONNECTING` |
| `CONNECTING` | WebSocket 正在握手 | WS open 事件 → `CONNECTED` |
| `CONNECTED` | WS 已连接，未加入房间 | 发送 create/join → `WAITING` |
| `WAITING` | 等待 created/joined 响应 | 收到 created/joined → `IN_ROOM`；收到 error → `DISCONNECTED` |
| `IN_ROOM` | 已在房间，等待对端 | 收到 peerJoined → `IN_CALL`（作为 host，需发 offer） |
| `IN_CALL` | 通话中 | 收到 peerLeft / 发送 leave / error → `DISCONNECTED` |

## 5. 时序图

### 5.1 完整建连流程

```
  Host (A)              Server              Joiner (B)
     │                     │                     │
     │── WS connect ──────→│                     │
     │←── WS open ─────────│                     │
     │── create ──────────→│                     │
     │                     │ 生成 roomId          │
     │←── created ─────────│                     │
     │   (roomId, ICE svrs)│                     │
     │                     │                     │
     │   NAT探测(独立)      │                     │── WS connect ──────→│
     │                     │                     │←── WS open ─────────│
     │                     │                     │── join(roomId) ────→│
     │                     │                     │  验证 roomId          │
     │←── peerJoined ─────│                     │←── joined ──────────│
     │                     │                     │   (ICE servers,      │
     │                     │                     │    peerId)            │
     │                     │                     │                     │
     │── natType ─────────→│── natType ────────→│                     │
     │←── natType ─────────│←── natType ────────│                     │
     │                     │                     │                     │
     │── offer(sdp) ──────→│── offer(sdp) ──────→│                     │
     │                     │                     │  setRemoteDescription│
     │                     │                     │  createAnswer        │
     │←── answer(sdp) ─────│←── answer(sdp) ─────│                     │
     │  setRemoteDescription│                     │                     │
     │                     │                     │                     │
     │── ice(candidate) ──→│── ice(candidate) ──→│                     │
     │←── ice(candidate) ──│←── ice(candidate) ──│                     │
     │   (双向，直到连通)   │                     │                     │
     │                     │                     │                     │
     │←═══ ICE Connected ══│═══ ICE Connected ══→│                     │
     │                     │                     │                     │
     │    视频/音频 RTP/SRTP 直传 (P2P 或 RELAY)   │                     │
     │←══════════════════════════════════════════→│                     │
     │                     │                     │                     │
```

### 5.2 离开流程

```
  Host (A)              Server              Joiner (B)
     │                     │                     │
     │── leave ──────────→│                     │
     │                     │── peerLeft ────────→│
     │←── WS close ───────│                     │←── WS close ────────│
     │   房间销毁           │                     │                     │
```

## 6. 心跳机制

| 参数 | 值 | 说明 |
|---|---|---|
| ping 间隔 | 15 秒 | 客户端定时发送 |
| pong 超时 | 5 秒 | 5 秒未收到 pong 视为连接断开 |
| 重连等待 | 3 秒 | 断线后等待 3 秒重连 |
| 最大重连次数 | 3 | 超过后回 DISCONNECTED 状态 |

**重连规则**：
1. WebSocket 断开后，客户端等待 3 秒
2. 重新连接 WSS
3. 如果之前在 `IN_ROOM` 状态，用原 roomId 重新 `join`
4. 如果房间仍存在且对端在线，服务端发 `joined` + 对端发 `peerJoined`
5. 重新走 offer/answer/ice 流程
6. 如果房间已过期，收到 `error: ROOM_EXPIRED`

## 7. 服务端房间管理规则

| 规则 | 说明 |
|---|---|
| roomId 生成 | 6 字符随机，大写字母 + 数字，排除易混淆字符（O/0/I/1） |
| 房间容量 | 最多 2 人 |
| 房间过期 | 创建后 30 分钟无人 join，自动销毁 |
| 房间销毁 | 2 人都离开后立即销毁 |
| 消息转发 | offer/answer/ice/natType 按 roomId 转发给对端（原样转发，不解析内容） |
| TURN 凭据 | 全局共享用户名/密码（demo/demopass），不按用户分配（学习 demo 足够） |

## 8. Go 服务端实现约束

```go
// 房间结构
type Room struct {
    ID       string         // roomId
    Peers    [2]*Peer       // 最多 2 人
    CreatedAt time.Time     // 创建时间
    mu       sync.Mutex
}

// Peer 结构
type Peer struct {
    ID       string         // peer-001 / peer-002
    Conn     *websocket.Conn
    Room     *Room
    Send     chan []byte     // 发送队列
}

// 消息路由
func handleMessage(peer *Peer, msg map[string]interface{}) {
    switch msg["type"] {
    case "create":  handleCreate(peer)
    case "join":    handleJoin(peer, msg)
    case "offer":   forwardToPeer(peer, msg)    // 转发给同 room 对端
    case "answer":  forwardToPeer(peer, msg)
    case "ice":     forwardToPeer(peer, msg)
    case "natType": forwardToPeer(peer, msg)
    case "leave":   handleLeave(peer)
    case "ping":    handlePing(peer, msg)
    }
}
```

## 9. Android 客户端实现约束

```kotlin
// 消息序列化/反序列化
@Serializable
sealed class SignalingMessage {
    @Serializable
    @SerialName("create")
    data object Create : SignalingMessage()

    @Serializable
    @SerialName("created")
    data class Created(
        val roomId: String,
        val stunUrl: String,
        val turnUrl: String,
        val turnUsername: String,
        val turnCredential: String
    ) : SignalingMessage()

    // ... 其他消息类型同理
}

// WebSocket 客户端
class SignalingClient(
    private val url: String,
    private val listener: SignalingListener
) {
    // 状态机
    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    // 心跳定时器
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (state == IN_ROOM || state == IN_CALL) {
                sendPing()
                heartbeatHandler.postDelayed(this, 15_000)
            }
        }
    }
}
```

## 10. JSON Schema（可选：用于运行时校验）

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "oneOf": [
    {
      "type": "object",
      "properties": {
        "type": { "const": "create" }
      },
      "required": ["type"],
      "additionalProperties": false
    },
    {
      "type": "object",
      "properties": {
        "type": { "const": "create" },
        "roomId": { "type": "string", "pattern": "^[A-Z2-9]{6}$" },
        "stunUrl": { "type": "string" },
        "turnUrl": { "type": "string" },
        "turnUsername": { "type": "string" },
        "turnCredential": { "type": "string" }
      },
      "required": ["type", "roomId", "stunUrl", "turnUrl", "turnUsername", "turnCredential"],
      "additionalProperties": false
    }
  ]
}
```

> 完整 JSON Schema 较长，Go 端可用 `github.com/xeipuuv/gojsonschema` 校验，Android 端用 kotlinx.serialization 做类型约束即可。
