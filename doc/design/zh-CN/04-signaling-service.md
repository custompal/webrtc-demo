> **中文（默认）** · [English](../04-signaling-service.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 04 — 信令服务

> Status: draft · Owner: writer-signaling · Task: t4
> Evidence base: reports/09-go-signaling.md, reports/12-deploy-signaling.md, reports/35-room-grace.md, reports/38-signaling-grace-deploy.md
> Doc standard: `doc/design/SPEC.md`

## 1. 范围

本文件描述 `signaling/` 下的 Go 信令（signaling）服务：它的包布局、房间与席位状态机、消息路由与错误码、
健康端点、配置与 systemd 单元，以及它与 coturn 服务器的关系。

本文件不描述 Android 客户端（见 [03-app-architecture.md](../03-app-architecture.md)），也不描述线格式本身
（见 [05-protocols.md](../05-protocols.md)），也不描述通话流程（见 [06-flows.md](../06-flows.md)）。

本服务暴露一个 WebSocket 端点与一个健康端点。端点路径是
`PathWS`（`signaling/server/server.go:25`），健康路径是 `PathHealthz`
（`signaling/server/server.go:28`）；HTTP 路由注册在 `Routes`
（`signaling/server/server.go:76`）中。

References: `signaling/server/server.go:25`, `signaling/server/server.go:28`, `signaling/server/server.go:76`

## 2. 包布局

本服务是由模块 `webrtcdemo-signaling` 构建的单个二进制。入口点 `main` 调用
`run`（`signaling/main.go:28`，`signaling/main.go:34`），后者解析 flag、构建日志器，然后
交给服务器。

| 包 | 文件 | 职责 |
|---|---|---|
| `main` | `signaling/main.go` | flag 解析、日志器装配、信号处理、生命周期 |
| `config` | `signaling/config/config.go` | 默认值、校验、非致命告警 |
| `protocol` | `signaling/protocol/*.go` | 消息结构体、错误码、心跳常量 |
| `room` | `signaling/room/room.go`, `signaling/room/manager.go`, `signaling/room/peer.go` | 房间、席位、对端、过期 |
| `server` | `signaling/server/server.go`, `signaling/server/ws_handler.go` | HTTP 路由、升级、消息分派 |
| `logging` | `signaling/logging/*.go` | 日志行格式与文件轮转 |
| `util` | `signaling/util/roomid.go` | 房间号生成、校验、规范化 |

管理器由 `NewManager`（`signaling/room/manager.go:59`）构建，服务器由
`NewServer`（`signaling/server/server.go:47`）构建，后者注册房间过期回调
`SetExpiredHandler`（`signaling/room/manager.go:74`）与宽限过期回调
`SetGraceExpiredHandler`（`signaling/room/manager.go:82`）。

References: `signaling/main.go:28`, `signaling/main.go:34`, `signaling/room/manager.go:59`, `signaling/server/server.go:47`, `signaling/room/manager.go:74`, `signaling/room/manager.go:82`

## 3. 房间与席位状态机

一个房间最多持有 `RoomMaxPeers` 个席位（`signaling/room/room.go:13`）。两个席位标识是
`PeerID1` 与 `PeerID2`（`signaling/room/room.go:17`，`signaling/room/room.go:18`），并按
席位索引经 `peerIDAt`（`signaling/room/room.go:102`）选定。

席位是状态的单位：它持有当前对端、一个可选的宽限定时器与一个世代
计数器（`signaling/room/room.go:26`）。一个席位恰好处于三种状态之一：

| 状态 | 条件 | 含义 |
|---|---|---|
| free | 未分配对端 | 该槽位接受新连接 |
| occupied | 已分配对端，无宽限定时器 | 连接在线 |
| pending | 已分配对端，宽限定时器已武装 | 连接已断开；席位为重连保留 |

状态迁移：

| 迁移 | 函数 | 行为 |
|---|---|---|
| free → occupied | `AddPeer`（`signaling/room/room.go:66`） | 分配第一个空闲席位并递增世代 |
| occupied → pending | `MarkPending`（`signaling/room/room.go:113`） | 武装宽限定时器；重复调用会重置它 |
| pending → occupied | `AddPeer`（`signaling/room/room.go:66`） | 接管挂起席位并保留其对端标识 |
| pending → free | `ExpirePending`（`signaling/room/room.go:140`） | 仅当对端与世代仍然匹配时才回收席位 |
| occupied → free | `RemovePeer`（`signaling/room/room.go:159`） | 显式离开，或在宽限窗口关闭时断开 |

WebSocket 断开后的路径是 `MarkOffline`（`signaling/room/manager.go:225`）。宽限期为正时，
它把席位标记为挂起并直接返回，不通知对端；宽限期关闭时，它立即移除席位。定时器触发时，
`expireGrace`（`signaling/room/manager.go:258`）经 `ExpirePending` 回收席位，并在房间非空时
恰好一次通知仍然在线的对端。世代计数器正是这一操作幂等的来源：接管之后旧定时器的世代
不再匹配，因此回调直接返回而不通知，如 reports/35-room-grace.md §2.2 所述。

显式离开由 `Leave`（`signaling/room/manager.go:311`）处理：它移除对端、销毁房间，
并清除另一个对端的房间引用。`Detach`（`signaling/room/manager.go:333`）是宽限窗口关闭时
使用的立即移除变体。

计数器反映同一机制：`seatTakeovers`（`signaling/room/manager.go:55`）与
`graceExpired`（`signaling/room/manager.go:54`）经 `StatsGrace`
（`signaling/room/manager.go:303`）读取。调用方使用的存活查询是 `PendingCount`
（`signaling/room/room.go:243`）、`IsPending`（`signaling/room/room.go:258`）与 `LivePeers`
（`signaling/room/room.go:212`）。

对端配对由 `OtherPeer`（`signaling/room/room.go:181`）解析；挂起席位仍计为存在，
因此重连的对端可以与仍然在线的对端重新配对。

过期由 `IsExpired`（`signaling/room/room.go:295`）判定：未满且在配置窗口内没有活动的房间
会被销毁。`cleanupInterval`（`signaling/room/manager.go:375`）计算清扫周期：默认为 60 s，
当过期间隔更短时取其四分之一，并以 200 ms 为下限；`cleanupLoop`
（`signaling/room/manager.go:388`）驱动它，`sweep`
（`signaling/room/manager.go:407`）先收集已过期的房间，再在锁外调用回调。
`RemoveRoom`（`signaling/room/manager.go:348`）删除房间并统计销毁次数。

References: `signaling/room/room.go:13`, `signaling/room/room.go:17`, `signaling/room/room.go:18`, `signaling/room/room.go:26`, `signaling/room/room.go:66`, `signaling/room/room.go:102`, `signaling/room/room.go:113`, `signaling/room/room.go:140`, `signaling/room/room.go:159`, `signaling/room/room.go:181`, `signaling/room/room.go:212`, `signaling/room/room.go:243`, `signaling/room/room.go:258`, `signaling/room/room.go:295`, `signaling/room/manager.go:54`, `signaling/room/manager.go:55`, `signaling/room/manager.go:225`, `signaling/room/manager.go:258`, `signaling/room/manager.go:303`, `signaling/room/manager.go:311`, `signaling/room/manager.go:333`, `signaling/room/manager.go:348`, `signaling/room/manager.go:375`, `signaling/room/manager.go:388`, `signaling/room/manager.go:407`

## 4. 连接处理与路由

`HandleWS`（`signaling/server/ws_handler.go:34`）负责一条连接从升级到清理的全程。非升级请求
会被以 HTTP 400 和一段纯文本提示拒绝
（`signaling/server/ws_handler.go:40`）。升级成功后它恰好启动一个写循环
（`signaling/room/peer.go:196`）与一个读循环（`signaling/room/peer.go:220`）；所有写入都汇聚
经由发送队列，其容量是 `PeerSendQueueSize`（`signaling/room/peer.go:23`）。

`ReadLoop` 把读上限设为配置的最大消息大小，并在每一帧（含控制帧 pong）刷新读截止时间
（`signaling/room/peer.go:220`）。投递使用 `SendRaw`
（`signaling/room/peer.go:169`）：快速路径立即入队，慢速路径最多等待发送超时后返回
`ErrPeerBufferFull`（`signaling/room/peer.go:28`），而不是静默丢弃。

`handleMessage`（`signaling/server/ws_handler.go:174`）只解析信封并按消息类型分派。
路由表：

| 入站消息 | 处理器 | 是否转发 |
|---|---|---|
| `create` | `handleCreate`（`signaling/server/ws_handler.go:212`） | 否 |
| `join` | `handleJoin`（`signaling/server/ws_handler.go:247`） | 否 |
| `offer`, `answer` | `forwardSDP`（`signaling/server/ws_handler.go:326`） | 是，原样逐字节转发 |
| `ice` | `forwardIce`（`signaling/server/ws_handler.go:340`） | 是，原样逐字节转发 |
| `natType` | `forwardNatType`（`signaling/server/ws_handler.go:359`） | 是，原样逐字节转发 |
| `leave` | `handleLeave`（`signaling/server/ws_handler.go:435`） | 先发送 `peerLeft` |
| `ping` | `handlePing`（`signaling/server/ws_handler.go:457`） | 以 `pong` 应答 |
| 仅服务端类型或未知类型 | `sendError`（`signaling/server/ws_handler.go:541`） | 否 |

转发帧不会被重新编码：`forwardToPeer`（`signaling/server/ws_handler.go:379`）把原始字节
交给 `SendRaw`，日志事件名由 `forwardEventName`
（`signaling/server/ws_handler.go:420`）选定。类型分类辅助函数是 `IsServerOnlyType`
（`signaling/protocol/message.go:155`）、`IsKnownType`（`signaling/protocol/message.go:160`）与
`IsForwardType`（`signaling/protocol/message.go:171`）。

错误响应由 `NewErrorWithDetail`（`signaling/protocol/errors.go:31`）依据错误码表
（`signaling/protocol/errors.go:6`）构建：

| 错误码 | 触发条件 | 客户端义务 |
|---|---|---|
| `ROOM_NOT_FOUND` | 未知房间号（`signaling/server/ws_handler.go:272`） | 终态：停止重试 |
| `ROOM_FULL` | 没有空闲也没有挂起席位（`signaling/server/ws_handler.go:278`） | 暂态：退避后重试 |
| `ROOM_EXPIRED` | 过期窗口已过（`signaling/server/ws_handler.go:275`） | 终态 |
| `INVALID_MESSAGE` | JSON 畸形、字段缺失、房间号非法、仅服务端类型（`signaling/server/ws_handler.go:177`） | 修复客户端 |
| `NOT_IN_ROOM` | 在成功 create 或 join 之前转发（`signaling/server/ws_handler.go:382`） | 修复客户端 |
| `INTERNAL_ERROR` | 房间创建或加入失败（`signaling/server/ws_handler.go:221`） | 稍后重试 |

`sendError`（`signaling/server/ws_handler.go:541`）对加入失败记录 `join_rejected`，其余情况记录
`error_sent`。

连接拆除由 `classifyReadError`（`signaling/server/ws_handler.go:145`）分类，它把服务端发起的
关闭与读超时、读上限溢出、对端关闭区分开。拆除随后调用 `MarkOffline` 并关闭对端
（`signaling/server/ws_handler.go:87`）。

References: `signaling/server/ws_handler.go:34`, `signaling/server/ws_handler.go:40`, `signaling/server/ws_handler.go:87`, `signaling/server/ws_handler.go:145`, `signaling/server/ws_handler.go:174`, `signaling/server/ws_handler.go:177`, `signaling/server/ws_handler.go:212`, `signaling/server/ws_handler.go:221`, `signaling/server/ws_handler.go:247`, `signaling/server/ws_handler.go:272`, `signaling/server/ws_handler.go:275`, `signaling/server/ws_handler.go:278`, `signaling/server/ws_handler.go:326`, `signaling/server/ws_handler.go:340`, `signaling/server/ws_handler.go:359`, `signaling/server/ws_handler.go:379`, `signaling/server/ws_handler.go:382`, `signaling/server/ws_handler.go:420`, `signaling/server/ws_handler.go:435`, `signaling/server/ws_handler.go:457`, `signaling/server/ws_handler.go:541`, `signaling/room/peer.go:23`, `signaling/room/peer.go:28`, `signaling/room/peer.go:169`, `signaling/room/peer.go:196`, `signaling/room/peer.go:220`, `signaling/protocol/message.go:155`, `signaling/protocol/message.go:160`, `signaling/protocol/message.go:171`, `signaling/protocol/errors.go:6`, `signaling/protocol/errors.go:31`

## 5. 健康端点

`HandleHealthz`（`signaling/server/server.go:102`）返回一个 JSON 对象。其字段：

| 字段 | 来源 |
|---|---|
| `status` | 常量 `ok` |
| `version` | `Version`（`signaling/server/server.go:22`） |
| `addr` | 配置的监听地址 |
| `uptimeSec` | 进程运行时长，单位为秒 |
| `rooms` | `Count`（`signaling/room/manager.go:94`） |
| `roomIds` | `RoomIDs`（`signaling/room/manager.go:101`） |
| `activeConns` | 当前连接数 |
| `totalConns` | 累计连接数 |
| `roomsCreated`, `roomsDestroyed` | `Stats`（`signaling/room/manager.go:112`） |
| `roomExpirySec` | 配置的过期窗口 |
| `roomGraceSec` | 配置的宽限期，单位为秒 |
| `seatTakeovers`, `graceExpired` | `StatsGrace`（`signaling/room/manager.go:303`） |
| `maxMessageBytes` | 配置的帧上限 |
| `stunUrl`, `turnUrl` | 交给客户端的 ICE 服务器配置 |
| `serverTimeMillis` | 当前服务器时间，单位为 Unix 毫秒 |

References: `signaling/server/server.go:22`, `signaling/server/server.go:102`, `signaling/room/manager.go:94`, `signaling/room/manager.go:101`, `signaling/room/manager.go:112`, `signaling/room/manager.go:303`

## 6. 配置与单元

默认值位于 `config`（`signaling/config/config.go:19`）：`DefaultListenAddr` 是 `:8443`
（`signaling/config/config.go:19`），`DefaultRoomExpirySec` 是 1800 s（`signaling/config/config.go:26`），
`MaxMessageSize` 是 65 536 B（`signaling/config/config.go:29`），`DefaultWriteTimeout` 是 10 s
（`signaling/config/config.go:32`），`DefaultPongWait` 是三个 ping 间隔
（`signaling/config/config.go:38`），`DefaultSendTimeout` 是 5 s（`signaling/config/config.go:42`），
`DefaultRoomGrace` 是 90 s（`signaling/config/config.go:54`）。`MinRecommendedRoomGrace`
（`signaling/config/config.go:57`）是告警阈值，`PlaceholderHost`
（`signaling/config/config.go:60`）用于识别文档中的示例地址。

Flag 声明在 `signaling/main.go:38`，并在同一函数中应用（`signaling/main.go:59`）。
本服务接受 `-addr`、`-stun`、`-turn`、`-user`、`-log`、`-log-level`、`-room-expiry`、
`-room-grace`、`-max-message-bytes`、`-write-timeout`、`-pong-wait`、`-send-timeout` 与 `-version`。
TURN 凭据由 `SetTurnUser`（`signaling/config/config.go:99`）从 `-user` 取值解析；
校验是 `Validate`（`signaling/config/config.go:110`），非致命建议是 `Warnings`
（`signaling/config/config.go:146`）。启动时以 `server_start`
（`signaling/main.go:101`）记录生效配置，其中包括 `room_grace_ms`（`signaling/main.go:95`）。

仓库中的单元文件是 `deploy/signaling.service`。它的 `ExecStart`
（`deploy/signaling.service:28`）显式固定了宽限期：

```sh
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:<host>:3478 -turn 'turn:<host>:3478?transport=udp' -user <user>:<pass> -log /var/log/signaling/signaling.log -room-grace 90s
```

`WorkingDirectory` 是 `deploy/signaling.service:27`，重启行为是 `Restart=always` 配
`RestartSec=3`（`deploy/signaling.service:29`，`deploy/signaling.service:30`）。优雅关闭是
`Shutdown`（`signaling/server/server.go:166`），由 SIGINT 或 SIGTERM 驱动
（`signaling/main.go:113`）。

HOST: `/opt/signaling/signaling` 是单元使用的已安装二进制路径；仓库副本
与已部署单元逐参数一致（见 `deploy/signaling.service:1` 与
reports/12-deploy-signaling.md §4）。HOST: `/var/log/signaling/signaling.log` 是
`deploy/signaling.service:28` 设置的日志目标；轮转写入器会创建其目录，并默认每个
文件 2 MiB、保留 3 个文件（`signaling/logging/rotating.go:14`，`signaling/logging/rotating.go:16`，
`signaling/logging/rotating.go:43`），它由 `Setup`
（`signaling/logging/logging.go:20`）安装。

References: `signaling/main.go:38`, `signaling/main.go:59`, `signaling/main.go:95`, `signaling/main.go:101`, `signaling/main.go:113`, `signaling/config/config.go:19`, `signaling/config/config.go:26`, `signaling/config/config.go:29`, `signaling/config/config.go:32`, `signaling/config/config.go:38`, `signaling/config/config.go:42`, `signaling/config/config.go:54`, `signaling/config/config.go:57`, `signaling/config/config.go:60`, `signaling/config/config.go:99`, `signaling/config/config.go:110`, `signaling/config/config.go:146`, `deploy/signaling.service:1`, `deploy/signaling.service:27`, `deploy/signaling.service:28`, `deploy/signaling.service:29`, `deploy/signaling.service:30`, `signaling/server/server.go:166`, `signaling/logging/logging.go:20`, `signaling/logging/rotating.go:14`, `signaling/logging/rotating.go:16`, `signaling/logging/rotating.go:43`

## 7. 与 coturn 的关系

本服务不转发媒体。它只把 ICE 服务器列表交给客户端：STUN URL、TURN
URL 与 TURN 凭据从配置读取，并在 `created` 响应
（`signaling/server/ws_handler.go:226`）与 `joined` 响应
（`signaling/server/ws_handler.go:287`）中发送。

TURN 服务器本身在仓库之外配置。仓库内的副本是
`deploy/turnserver.conf`，冻结值为：

| 设置 | 取值 | 行 |
|---|---|---|
| 监听端口 | 3478 | `deploy/turnserver.conf:4` |
| 监听地址 | 内部地址 | `deploy/turnserver.conf:5` |
| 外部地址 | 公网/内网地址对 | `deploy/turnserver.conf:6` |
| 中继地址 | 内部地址 | `deploy/turnserver.conf:7` |
| 中继端口范围 | 49152-49200 | `deploy/turnserver.conf:8`, `deploy/turnserver.conf:9` |
| realm、服务器名 | webrtc-demo | `deploy/turnserver.conf:10`, `deploy/turnserver.conf:11` |
| 长期凭据 | 已启用 | `deploy/turnserver.conf:14` |
| user | demo | `deploy/turnserver.conf:15` |
| 总配额 | 45 | `deploy/turnserver.conf:16` |

fingerprint 选项在这里不起作用：`deploy/turnserver.conf:12` 记录 coturn 4.6.1 拒绝
`use-fingerprint` 这种拼写，生效的名称是 `fingerprint`。中继地址的选择
是唯一带有实测失败模式的配置值（在中继字段填外部地址会导致绑定失败与
分配错误 508）；证据是 reports/06-coturn.md §5。

References: `signaling/server/ws_handler.go:226`, `signaling/server/ws_handler.go:287`, `deploy/turnserver.conf:4`, `deploy/turnserver.conf:5`, `deploy/turnserver.conf:6`, `deploy/turnserver.conf:7`, `deploy/turnserver.conf:8`, `deploy/turnserver.conf:9`, `deploy/turnserver.conf:10`, `deploy/turnserver.conf:11`, `deploy/turnserver.conf:12`, `deploy/turnserver.conf:14`, `deploy/turnserver.conf:15`, `deploy/turnserver.conf:16`

## 8. 证据索引

| 主张 | 引用 | 验证工件 |
|---|---|---|
| 端点路径与路由 | `signaling/server/server.go:76` | reports/12-deploy-signaling.md §3 |
| 房间容量为两个席位 | `signaling/room/room.go:13` | reports/09-go-signaling.md §3 |
| 断开后在宽限窗口内保留席位 | `signaling/room/manager.go:225` | reports/35-room-grace.md §2.2 |
| 宽限过期只通知对端一次 | `signaling/room/manager.go:258` | reports/35-room-grace.md §3.4 |
| 转发逐字节进行 | `signaling/server/ws_handler.go:379` | reports/09-go-signaling.md §4 |
| 错误码表 | `signaling/protocol/errors.go:6` | reports/09-go-signaling.md §5 |
| 健康字段 | `signaling/server/server.go:102` | reports/38-signaling-grace-deploy.md §3 |
| 单元中固定的宽限期 | `deploy/signaling.service:28` | reports/38-signaling-grace-deploy.md §2 |
| TURN 中继设置 | `deploy/turnserver.conf:7` | reports/06-coturn.md §5 |

## 9. 未决项

1. `ReconnectDelay`（`signaling/protocol/heartbeat.go:15`）与 `MaxReconnectAttempts`
   （`signaling/protocol/heartbeat.go:17`）只有声明：它们在仓库中没有任何调用点。
   客户端侧重连策略是 [05-protocols.md](../05-protocols.md) 中实测的那一个，这两个常量
   作为行为是 `unverified`。
2. 本文件中的 Go 日志事件名直接引用自发出事件的源码，因为生成的
   [log-events.md](../_generated/log-events.md) 只覆盖 Kotlin 与原生层。这是生成工件中的
   覆盖缺口，不是分歧。
3. `room_grace_ms` 启动字段与健康计数器仅由源码验证；撰写本文件时没有
   查询任何已部署实例。

References: `signaling/protocol/heartbeat.go:15`, `signaling/protocol/heartbeat.go:17`, `signaling/main.go:95`
