// Package protocol 定义 Android 客户端与 Go 信令服务之间的 JSON 消息结构。
//
// 权威规格：doc/09-signaling-protocol-spec.md（已冻结）。
// doc/12 §3 给出了同名字段的伪代码；两者字段名一致，可直接对照。
// 注意：doc/05 §7 使用了旧字段名（stun/turn/turnUser/turnPass），与 doc/09 冲突，
// 本实现以 doc/09 为准（stunUrl/turnUrl/turnUsername/turnCredential）。
//
// 所有消息都是 UTF-8 JSON 文本帧，且必须包含 "type" 字段。
package protocol

// 消息类型枚举（doc/09 §2.1）。
const (
	TypeCreate     = "create"     // C → S 创建房间
	TypeCreated    = "created"    // S → C 房间创建成功
	TypeJoin       = "join"       // C → S 加入房间
	TypeJoined     = "joined"     // S → C 加入成功
	TypeError      = "error"      // S → C 错误响应
	TypeOffer      = "offer"      // C → S → C SDP Offer
	TypeAnswer     = "answer"     // C → S → C SDP Answer
	TypeIce        = "ice"        // C → S → C ICE Candidate
	TypeNatType    = "natType"    // C → S → C NAT 类型交换
	TypePeerJoined = "peerJoined" // S → C 对端加入通知
	TypePeerLeft   = "peerLeft"   // S → C 对端离开通知
	TypeLeave      = "leave"      // C → S 主动离开
	TypePing       = "ping"       // C → S 心跳请求
	TypePong       = "pong"       // S → C 心跳响应
)

// NAT 类型枚举值（doc/09 §3.7）。
const (
	NatOpen               = "Open"
	NatFullCone           = "FullCone"
	NatRestrictedCone     = "RestrictedCone"
	NatPortRestrictedCone = "PortRestrictedCone"
	NatSymmetric          = "Symmetric"
	NatUnknown            = "Unknown"
)

// Message 是所有消息的最小信封，仅用于解析 type 字段做路由。
// 转发类消息（offer/answer/ice/natType）不重新编码，原样转发原始字节。
type Message struct {
	Type string `json:"type"`
}

// CreateRequest 创建房间请求（doc/09 §3.1）。
type CreateRequest struct {
	Type string `json:"type"` // 固定 "create"
}

// CreatedResponse 创建房间成功响应（doc/09 §3.1）。
type CreatedResponse struct {
	Type           string `json:"type"`           // 固定 "created"
	RoomID         string `json:"roomId"`         // 6 字符房间号
	StunURL        string `json:"stunUrl"`        // 如 stun:1.2.3.4:3478
	TurnURL        string `json:"turnUrl"`        // 如 turn:1.2.3.4:3478?transport=udp
	TurnUsername   string `json:"turnUsername"`   // TURN 用户名
	TurnCredential string `json:"turnCredential"` // TURN 密码
}

// JoinRequest 加入房间请求（doc/09 §3.2）。
type JoinRequest struct {
	Type   string `json:"type"`   // 固定 "join"
	RoomID string `json:"roomId"` // 6 字符房间号
}

// JoinedResponse 加入成功响应（doc/09 §3.2）。
type JoinedResponse struct {
	Type           string `json:"type"` // 固定 "joined"
	RoomID         string `json:"roomId"`
	StunURL        string `json:"stunUrl"`
	TurnURL        string `json:"turnUrl"`
	TurnUsername   string `json:"turnUsername"`
	TurnCredential string `json:"turnCredential"`
	PeerID         string `json:"peerId"` // 服务端分配：peer-001 / peer-002
}

// PeerJoinedNotify 对端加入通知（doc/09 §3.3）。收到方是发起方，应发 Offer。
type PeerJoinedNotify struct {
	Type   string `json:"type"` // 固定 "peerJoined"
	PeerID string `json:"peerId"`
}

// PeerLeftNotify 对端离开通知（doc/09 §3.9）。
type PeerLeftNotify struct {
	Type   string `json:"type"` // 固定 "peerLeft"
	PeerID string `json:"peerId"`
}

// OfferMessage SDP Offer（doc/09 §3.4）。
type OfferMessage struct {
	Type string `json:"type"` // 固定 "offer"
	SDP  string `json:"sdp"`
}

// AnswerMessage SDP Answer（doc/09 §3.5）。
type AnswerMessage struct {
	Type string `json:"type"` // 固定 "answer"
	SDP  string `json:"sdp"`
}

// IceMessage ICE Candidate（doc/09 §3.6）。
//
// 与 doc/12 §3 的伪代码相比，SDPMLineIndex 用 *int 而不是 int：
// 只有指针才能区分「字段缺失」与「显式 0」，否则无法校验
// 「sdpMid 与 sdpMLineIndex 至少一个非空」。转发仍走原始字节，不受影响。
type IceMessage struct {
	Type          string `json:"type"` // 固定 "ice"
	Candidate     string `json:"candidate"`
	SDPMid        string `json:"sdpMid,omitempty"`
	SDPMLineIndex *int   `json:"sdpMLineIndex,omitempty"`
}

// NatTypeMessage NAT 类型交换（doc/09 §3.7）。
type NatTypeMessage struct {
	Type    string `json:"type"` // 固定 "natType"
	NatType string `json:"natType"`
}

// LeaveMessage 主动离开（doc/09 §3.8）。
type LeaveMessage struct {
	Type string `json:"type"` // 固定 "leave"
}

// ErrorMessage 错误响应（doc/09 §3.10）。
type ErrorMessage struct {
	Type    string `json:"type"`    // 固定 "error"
	Code    string `json:"code"`    // 错误码枚举
	Message string `json:"message"` // 人类可读描述（英文）
}

// PingMessage 心跳请求（doc/09 §3.11）。
type PingMessage struct {
	Type      string `json:"type"`      // 固定 "ping"
	Timestamp int64  `json:"timestamp"` // Unix 毫秒
}

// PongMessage 心跳响应（doc/09 §3.11）。
type PongMessage struct {
	Type      string `json:"type"`      // 固定 "pong"
	Timestamp int64  `json:"timestamp"` // Unix 毫秒（服务端当前时间）
}

// serverOnlyTypes 是只允许服务端 → 客户端方向的类型。
// 客户端若发送这些类型，服务端按 doc/09 §3.10 返回 INVALID_MESSAGE。
var serverOnlyTypes = map[string]bool{
	TypeCreated:    true,
	TypeJoined:     true,
	TypeError:      true,
	TypePeerJoined: true,
	TypePeerLeft:   true,
	TypePong:       true,
}

// IsServerOnlyType 判断类型是否只能由服务端下发。
func IsServerOnlyType(t string) bool {
	return serverOnlyTypes[t]
}

// IsKnownType 判断类型是否属于 doc/09 §2.1 枚举。
func IsKnownType(t string) bool {
	switch t {
	case TypeCreate, TypeCreated, TypeJoin, TypeJoined, TypeError,
		TypeOffer, TypeAnswer, TypeIce, TypeNatType,
		TypePeerJoined, TypePeerLeft, TypeLeave, TypePing, TypePong:
		return true
	}
	return false
}

// IsForwardType 判断类型是否需要按 roomId 转发给对端（doc/09 §7）。
func IsForwardType(t string) bool {
	switch t {
	case TypeOffer, TypeAnswer, TypeIce, TypeNatType:
		return true
	}
	return false
}

// IsValidNatType 判断 natType 字符串是否属于 doc/09 §3.7 枚举。
func IsValidNatType(v string) bool {
	switch v {
	case NatOpen, NatFullCone, NatRestrictedCone, NatPortRestrictedCone, NatSymmetric, NatUnknown:
		return true
	}
	return false
}
