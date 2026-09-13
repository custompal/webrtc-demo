package com.example.webrtcdemo.signaling

// ============================================================================
// 客户端信令状态机（doc/09 §4，逐字冻结）
// ----------------------------------------------------------------------------
//   DISCONNECTED 未连接
//        ↓ 用户点击创建/加入
//   CONNECTING   WebSocket 正在握手
//        ↓ WS open
//   CONNECTED    WS 已连接，未加入房间
//        ↓ 发送 create/join
//   WAITING      等待 created/joined
//        ↓ 收到 created/joined
//   IN_ROOM      已在房间，等待对端
//        ↓ 收到 peerJoined（host 发 offer）/ 作为 joiner 等待 offer
//   IN_CALL      通话中
//        ↓ peerLeft / 发送 leave / error
//   DISCONNECTED
// ============================================================================

/**
 * 信令连接状态。
 *
 * @property label 中文展示名（UI 状态面板）。
 * @property isInRoom 是否已进入房间（决定重连时是否需要用原 roomId 重新 join，§6 重连规则）。
 */
enum class ConnectionState(val label: String, val isInRoom: Boolean) {
    DISCONNECTED("未连接", false),
    CONNECTING("连接中", false),
    CONNECTED("已连接", false),
    WAITING("等待房间响应", false),
    IN_ROOM("已进入房间", true),
    IN_CALL("通话中", true),
    ;

    companion object {
        /** 可发送 `offer`/`answer`/`ice`/`natType` 的状态（§8.3 第 2 条：未 join 发这些 → NOT_IN_ROOM）。 */
        fun canSendMediaSignaling(state: ConnectionState): Boolean =
            state == IN_ROOM || state == IN_CALL
    }
}
