> **中文（默认）** · [English](../05-protocols.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 05 — 协议与交互

> Status: draft · Owner: writer-signaling · Task: t4
> Evidence base: reports/09-go-signaling.md, reports/35-room-grace.md, reports/39-reconnect-budget-ice-restart.md, reports/40-glare-ice-restart-fix.md, reports/06-coturn.md
> Doc standard: `doc/design/SPEC.md`

## 1. 范围

本文件描述 Android 客户端与信令服务之间的线契约、连接与重连（rejoin）策略、客户端在 ICE、TURN 与 SDP 上的
行为、Kotlin 层与原生库之间的 JNI 契约，以及诊断事件词汇表。

本文件不描述服务内部（见 [04-signaling-service.md](../04-signaling-service.md)），也不描述应用分层（见
[03-app-architecture.md](../03-app-architecture.md)），也不描述端到端流程（见 [06-flows.md](../06-flows.md)）。

字段级消息表是机器生成的、具权威性；本文件交叉引用它们，且从不自行复述任何类型集合。生成工件位于
`doc/design/_generated/`（契约拼写为 `docs/_generated/`，经 `docs` 兼容链接指向同一批文件），由
`scripts/gen-doc-tables.sh` 产出。

## 2. 传输与分帧

信令运行在单一 WebSocket 端点上，即 `PathWS`（`signaling/server/server.go:25`）。每一帧都是
UTF-8 JSON 文本帧，判别字段是 `type`。

信封被解析为 `Message`（`signaling/protocol/message.go:41`）；路由只读取判别字段，转发类型
按原始字节原样传出（`signaling/server/ws_handler.go:379`）。大于配置上限的帧会被
读上限拒绝，该上限是 `MaxMessageSize`（65 536 B，`signaling/config/config.go:29`），由读
循环（`signaling/room/peer.go:221`）施加。

客户端使用 `SignalingCodec`
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:143`）编解码，它会忽略
未知键，但不会忽略缺失的必填键
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:146`）。

References: `signaling/server/server.go:25`, `signaling/protocol/message.go:41`, `signaling/server/ws_handler.go:379`, `signaling/config/config.go:29`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:143`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:146`, `signaling/room/peer.go:221`

## 3. 消息目录

两侧实现同样的 14 种消息类型。生成表
[signaling messages](../_generated/signaling-messages.md) 给出每一种的 Go 常量、JSON
取值、方向与字段列表；该文件 §4 说明两个类型集合完全相同，§6 说明每个 JSON 键都有对应的
Kotlin 属性。

| Go 常量 | JSON 取值 | 方向 | Kotlin 类 |
|---|---|---|---|
| `TypeCreate` | create | C→S | `Create` |
| `TypeCreated` | created | S→C | `Created` |
| `TypeJoin` | join | C→S | `Join` |
| `TypeJoined` | joined | S→C | `Joined` |
| `TypePeerJoined` | peerJoined | S→C | `PeerJoined` |
| `TypePeerLeft` | peerLeft | S→C | `PeerLeft` |
| `TypeOffer` | offer | C→S→C | `Offer` |
| `TypeAnswer` | answer | C→S→C | `Answer` |
| `TypeIce` | ice | C→S→C | `Ice` |
| `TypeNatType` | natType | C→S→C | `NatTypeMessage` |
| `TypeLeave` | leave | C→S | `Leave` |
| `TypeError` | error | S→C | `ServerError` |
| `TypePing` | ping | C→S | `Ping` |
| `TypePong` | pong | S→C | `Pong` |

全部 14 个常量声明在一个代码块中（`signaling/protocol/message.go:12`），全部 14 个 Kotlin
类都带有显式序列化名
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:19`）。

字段列表之外的行为：

| 消息 | 契约 |
|---|---|
| `TypeCreate` | 创建房间并返回带回 ICE 服务器列表的 `TypeCreated` |
| `TypeJoin` | 按房间号加入并返回带已分配对端标识的 `TypeJoined` |
| `TypePeerJoined` | 告知已在房间内的对端另一方已到达 |
| `TypePeerLeft` | 告知剩余对端另一方已离开；每次离开只发送一次 |
| `TypeOffer`, `TypeAnswer` | 承载非空的会话描述，原样转发 |
| `TypeIce` | 承载一个候选（candidate）；媒体标识字段是可选的 |
| `TypeNatType` | 承载本机 NAT 类型；未知取值会被转发并告警 |
| `TypeLeave` | 服务端通知对端、关闭套接字并销毁房间 |
| `TypePing`, `TypePong` | 应用层心跳；应答携带服务器时间 |
| `TypeError` | 承载一个错误码与一条可读消息（message） |

两处字段细节对实现可见。第一，媒体标识索引在 Go 侧是指针，因此缺失字段与显式零值可以区分
（`signaling/protocol/message.go:106`）。第二，缺少两个媒体标识字段之一的候选会被告警但仍会
转发，这是有意接受的序列化差异
（`signaling/server/ws_handler.go:350`）。

References: `signaling/protocol/message.go:12`, `signaling/protocol/message.go:106`, `signaling/server/ws_handler.go:350`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:19`

## 4. 连接与重连策略

客户端心跳是 15 s（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`），
单个 pong 窗口是 5 s
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`）。单次错过窗口
不再断开连接：最多容忍 `PONG_MISS_TOLERANCE` 次连续错过
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`），给出
`PONG_FAIL_AFTER_MS` = 20 s 的有效存活阈值
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:94`）。两个谓词是
`pongMissed`（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:104`）与
`pongTimeoutReached`（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:117`）。

重连对套接字丢失与重新加入使用同一个指数退避：`REJOIN_RETRY_BASE_MS` 是
1 s（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`），
`REJOIN_RETRY_MAX_MS` 把单次延迟封顶在 8 s
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`），
`MAX_REJOIN_ATTEMPTS` 是 10
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`）。
`rejoinDelayMs` 依次给出 1 s、2 s、4 s 与 8 s
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:156`），
`rejoinBudgetMs` 把该调度求和为 63 s
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163`）。该预算高于
服务端读超时，且低于服务端宽限期，如
reports/39-reconnect-budget-ice-restart.md §3 所述。

在服务端，读超时是三个 ping 间隔（`signaling/config/config.go:38`），保留已断开席位的
宽限期（grace period）是 `DefaultRoomGrace` = 90 s
（`signaling/config/config.go:54`）。由于席位能在断开后存活，宽限窗口内的重连会按原始对端标识
被接受（见 [04-signaling-service.md](../04-signaling-service.md)）；窗口之外席位被回收，仍然在线的对端
会通过 `peerLeft` 得到通知。

由此对任何客户端提出两条义务：

| 义务 | 原因 |
|---|---|
| 按判别字段而非到达顺序分派每一帧入站帧 | 心跳应答与通话消息共享同一条流 |
| 把房间已满错误视为暂态，把未找到或已过期视为终态 | 房间存活期间席位仍可能被回收 |

未注册监听器时，客户端最多缓冲 `MAX_PENDING_MESSAGES` = 32 条消息
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:170`），并且正常关闭码
1000 的套接字关闭会被显式处理
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:128`）。在调度重连之前，
前一个套接字会被关闭或取消
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:424`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:655`），重连
本身由 `scheduleReconnect`
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:721`）驱动。

References: `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:94`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:104`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:117`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:128`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:156`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:170`, `signaling/config/config.go:38`, `signaling/config/config.go:54`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:424`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:655`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:721`

## 5. ICE、TURN 与 SDP 行为

ICE 服务器配置只经信令响应到达客户端：STUN URL、TURN URL 与 TURN 凭据被附加到房间创建响应
（`signaling/server/ws_handler.go:226`）与加入响应
（`signaling/server/ws_handler.go:287`）上。这些取值背后的 TURN 部署见
[04-signaling-service.md](../04-signaling-service.md)；其中继地址规则与实测失败模式见
reports/06-coturn.md §5。

服务端从不改写会话描述。提议（offer）或应答（answer）只校验正文非空，随后按原始字节转发
（`signaling/server/ws_handler.go:326`）；候选（`signaling/server/ws_handler.go:340`）与 NAT 类型
（`signaling/server/ws_handler.go:359`）同理。服务端不检查会话描述，因此编解码器（codec）选择与任何重协商都
属于客户端职责。

NAT 类型交换承载六个字符串之一，声明为常量
（`signaling/protocol/message.go:30`）：`Open`、`FullCone`、`RestrictedCone`、`PortRestrictedCone`、
`Symmetric` 与 `Unknown`。服务端原样转发该取值，并对集合之外的字符串记录告警
（`signaling/server/ws_handler.go:369`）。

媒体传输选择是 ICE 的客户端侧结果，表现为直连路径或中继路径。三种客户端行为塑造它：

| 行为 | 实现 |
|---|---|
| 优先 UDP 上的 TURN，并附加一条 TCP 条目作为回退 | `WebRtcConfig` 把 TURN URL 改写为 `transport=tcp` 并添加，除非服务端已提供一条（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:105`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:174`）；该回退默认开启（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:165`） |
| 回环候选在双向都被过滤 | 本地收集到的 `127.0.0.0/8` 与 `::1` 候选不发送（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:886`），远端候选在收到时丢弃（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:605`），经 `LoopbackCandidates.isLoopback`（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1581`） |
| 以强制中继复现中继通话 | 诊断开关选择 `iceTransportsType` RELAY 而非 ALL（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:156`, `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:162`） |

对称 NAT 不留下直连路径，因此这类通话只能经中继完成；上面的强制
中继开关就是复现该路径的方式（见 reports/30-ice-relay-robustness.md）。

重协商受两条已登记行为约束。第一，提议义务只属于房间
创建者，这消除了同时重协商冲突
（reports/40-glare-ice-restart-fix.md §2）。第二，ICE 重启把下一次提议标记为
需要重协商，而不是立即发送一条（`restartIce`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`）：通话标记该
连接（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1286`），提议方随后
重新提议，最多 `MAX_ICE_RESTARTS` = 2 次
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`）。不触发
重协商的静默断开是已登记限制，记录在 [09-verification-and-limitations.md](../09-verification-and-limitations.md)。

`app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` 提供客户端使用的 ICE 与 DTLS/SRTP
实现；它是固定的构建输入，且不在版本控制中。

References: `signaling/server/ws_handler.go:226`, `signaling/server/ws_handler.go:287`, `signaling/server/ws_handler.go:326`, `signaling/server/ws_handler.go:340`, `signaling/server/ws_handler.go:359`, `signaling/server/ws_handler.go:369`, `signaling/protocol/message.go:30`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:105`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:156`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:165`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:174`, `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:162`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:605`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:886`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1286`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1581`

## 6. JNI 契约

原生库是 `webrtcdemo_native`，由 `NativeLoader`
（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:17`）加载一次。绑定使用
`JNI_OnLoad` 配 `RegisterNatives`（`app/src/main/cpp/jni/jni_bridge.h:9`，
`app/src/main/cpp/jni/jni_bridge.cpp:57`），并返回 `JNI_VERSION_1_6`
（`app/src/main/cpp/jni/jni_bridge.cpp:56`）。本项目没有生成头文件，也没有名称修饰
入口点，因此不存在可供发现的 `Java_*` 符号。

共注册四个 Kotlin 类，总计 15 个原生方法：

| 类 | 方法数 | 注册表 |
|---|---|---|
| `NativeLog` | 4 | `app/src/main/cpp/jni/native_log_jni.cpp:71` |
| `NativeVp9Encoder` | 9 | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` |
| `NativeNatDetector` | 2 | `app/src/main/cpp/jni/nat_detector_jni.cpp:49` |
| `NativeCallbacks` | 2（原生调 Kotlin） | `app/src/main/cpp/jni/jni_bridge.cpp:56` |

对 Kotlin 声明、注册表与 C 原型的权威交叉核对是
生成表 [jni contract](../_generated/jni-contract.md)；其 §7 报告每一项的分歧
结果，其 §6 记录可选的导出符号检查——当库尚未构建时，该检查为
`unverified (build product absent)`。

方法名与签名已冻结。在 Kotlin 侧，声明分散在
`NativeLog`（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:32`）、
`NativeVp9Encoder`（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:21`）、
`NativeNatDetector`（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeNatDetector.kt:11`）
与 `NativeCallbacks`（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:25`）中。
原生层调用的两个回调是 `onNatTypeDetected`
（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:36`）与 `onLogEvent`
（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:52`）。

`NativeVp9Encoder` 返回一个不透明句柄，Kotlin 侧必须恰好释放一次；失败
模式经返回码而非异常报告，因为原生代码是在关闭异常的情况下构建的。

`libwebrtcdemo_native.so` 是 CMake 目标 `webrtcdemo_native`
（`app/src/main/cpp/CMakeLists.txt:32`）的构建产物；它在构建时生成，不在版本控制中。

References: `app/src/main/cpp/jni/jni_bridge.h:9`, `app/src/main/cpp/jni/jni_bridge.cpp:56`, `app/src/main/cpp/jni/jni_bridge.cpp:57`, `app/src/main/cpp/jni/native_log_jni.cpp:71`, `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347`, `app/src/main/cpp/jni/nat_detector_jni.cpp:49`, `app/src/main/cpp/CMakeLists.txt:32`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:17`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:32`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:21`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeNatDetector.kt:11`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:25`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:36`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:52`

## 7. 诊断事件

事件键是日志驱动诊断的契约。机器生成的清单
[log events](../_generated/log-events.md) 列出 Kotlin 与原生事件键及其
发出文件与行号，另有一个排序索引（§3）；它对这两层具权威性。

信令服务发出自己的键，它们不属于该生成文件，因此直接
引用自发出事件的源码：

| 事件键 | 发出位置 |
|---|---|
| `server_start` | `signaling/main.go:101` |
| `listening` | `signaling/server/server.go:157` |
| `ws_open` | `signaling/server/ws_handler.go:72` |
| `ws_close` | `signaling/server/ws_handler.go:118` |
| `heartbeat_timeout` | `signaling/server/ws_handler.go:114` |
| `room_created` | `signaling/room/manager.go:150` |
| `room_joined` | `signaling/room/manager.go:210` |
| `seat_takeover` | `signaling/room/manager.go:207` |
| `room_destroyed` | `signaling/room/manager.go:367` |
| `room_expired` | `signaling/room/manager.go:430` |
| `grace_expired` | `signaling/room/manager.go:285` |
| `peer_joined` | `signaling/server/ws_handler.go:322` |
| `peer_left_sent` | `signaling/server/ws_handler.go:535` |
| `join_rejected` | `signaling/server/ws_handler.go:553` |
| `error_sent` | `signaling/server/ws_handler.go:551` |
| `offer_forward` | `signaling/server/ws_handler.go:423` |
| `answer_forward` | `signaling/server/ws_handler.go:425` |
| `ice_forward` | `signaling/server/ws_handler.go:427` |
| `nattype_forward` | `signaling/server/ws_handler.go:429` |
| `forward_dropped` | `signaling/server/ws_handler.go:395` |
| `heartbeat_ping` | `signaling/server/ws_handler.go:473` |

对协议诊断重要的客户端键是连接生命周期键 `ws_open`
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:272`）、`ws_close`
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:313`）、`ws_pong_timeout`
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:647`）、
`ws_reconnect_scheduled`
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:751`）与
`ws_reconnect_suppressed`
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523`）。

References: `signaling/main.go:101`, `signaling/server/server.go:157`, `signaling/server/ws_handler.go:72`, `signaling/server/ws_handler.go:114`, `signaling/server/ws_handler.go:118`, `signaling/server/ws_handler.go:322`, `signaling/server/ws_handler.go:395`, `signaling/server/ws_handler.go:423`, `signaling/server/ws_handler.go:425`, `signaling/server/ws_handler.go:427`, `signaling/server/ws_handler.go:429`, `signaling/server/ws_handler.go:473`, `signaling/server/ws_handler.go:535`, `signaling/server/ws_handler.go:551`, `signaling/server/ws_handler.go:553`, `signaling/room/manager.go:150`, `signaling/room/manager.go:207`, `signaling/room/manager.go:210`, `signaling/room/manager.go:285`, `signaling/room/manager.go:367`, `signaling/room/manager.go:430`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:272`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:313`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:647`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:751`

## 8. 证据索引

| 主张 | 引用 | 验证工件 |
|---|---|---|
| 单一 WebSocket 端点 | `signaling/server/server.go:25` | reports/09-go-signaling.md §3 |
| 两侧类型集合相同 | `doc/design/_generated/signaling-messages.md` | generated table §4 |
| 转发逐字节进行 | `signaling/server/ws_handler.go:379` | reports/09-go-signaling.md §4 |
| 重连预算 63 s | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163` | reports/39-reconnect-budget-ice-restart.md §3 |
| 宽限期 90 s | `signaling/config/config.go:54` | reports/35-room-grace.md §2.1 |
| ICE 取值仅经信令交付 | `signaling/server/ws_handler.go:287` | reports/09-go-signaling.md §3 |
| 通过单一提议义务消除冲突 | reports/40-glare-ice-restart-fix.md §2 | reports/40-glare-ice-restart-fix.md |
| JNI 绑定基于注册 | `app/src/main/cpp/jni/jni_bridge.h:9` | `doc/design/_generated/jni-contract.md` §1 |
| 15 个原生方法 | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` | `doc/design/_generated/jni-contract.md` §3 |

## 9. 未决项

1. 原生库的导出符号检查是 `unverified`，因为版本控制中没有保留任何构建产物
   （见 `doc/design/_generated/jni-contract.md` §6）。
2. 第 7 节的 Go 事件键引用自源码而非生成的日志清单，
   后者只覆盖 Kotlin 与原生层。这是覆盖缺口，不是分歧。
3. 套接字丢失后客户端对已缓冲消息的排空在本文件中为 `not measured`；
   只引用了缓冲区容量。
