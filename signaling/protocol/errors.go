// 错误码与错误响应构造（doc/09 §3.10）。
package protocol

// 错误码枚举（doc/09 §3.10 错误码表）。
const (
	ErrRoomNotFound   = "ROOM_NOT_FOUND"  // 房间不存在
	ErrRoomFull       = "ROOM_FULL"       // 房间已满（已有 2 人）
	ErrRoomExpired    = "ROOM_EXPIRED"    // 房间已过期（创建后 30 分钟无人加入）
	ErrInvalidMessage = "INVALID_MESSAGE" // JSON 解析失败 / 缺必填字段 / type 不合法
	ErrNotInRoom      = "NOT_IN_ROOM"     // 未 join 就发 offer/answer/ice/leave
	ErrInternalError  = "INTERNAL_ERROR"  // 服务内部错误
)

// errorMessages 是各错误码的默认英文描述（doc/09 §3.10 示例风格）。
var errorMessages = map[string]string{
	ErrRoomNotFound:   "Room does not exist",
	ErrRoomFull:       "Room is full (max 2 peers)",
	ErrRoomExpired:    "Room has expired",
	ErrInvalidMessage: "Invalid message format",
	ErrNotInRoom:      "You are not in a room",
	ErrInternalError:  "Internal server error",
}

// NewError 用错误码的默认描述构造 error 消息。
func NewError(code string) ErrorMessage {
	return NewErrorWithDetail(code, "")
}

// NewErrorWithDetail 构造 error 消息；detail 非空时作为人类可读描述，
// 便于区分同一错误码的不同触发场景（doc/09 §3.10 示例 "Room A1B2C3 does not exist"）。
func NewErrorWithDetail(code, detail string) ErrorMessage {
	msg := detail
	if msg == "" {
		if m, ok := errorMessages[code]; ok {
			msg = m
		} else {
			msg = "Unknown error"
		}
	}
	return ErrorMessage{
		Type:    TypeError,
		Code:    code,
		Message: msg,
	}
}
