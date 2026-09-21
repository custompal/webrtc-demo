> **中文（默认）** · [English](../08-issues-and-solutions.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 08 — 问题与解决方案

> Status: draft · Owner: writer-history · Task: t6
> Evidence base: `reports/15-connection-defect.md` .. `reports/52-release-closure.md`, `reports/99-final-report.md` §15,
> `reports/27-encoder-direction-perf.md`, `reports/49-bitrate-allocation-collapse.md`, `reports/51-frame-dropper-and-trusted-rc.md`
> Doc standard: `doc/design/SPEC.md`

---

## 1. 范围

本文件是已交付系统的缺陷史。对每个缺陷，它给出症状、在设备上观测到的日志特征、`file:LINE` 级别的
根因、修复、验证证据（报告编号与关键数字）以及残留风险。它最后记录那些被提出、尝试或假定过、但被
**已否决（rejected）或已证伪（disproven）**的方案，因为这些决定解释了当前实现为何是现在这样。

在范围内：产生了错误、误导或不可用行为的缺陷，以及阻塞诊断的工具与环境缺陷。范围之外：正常的功能
演进、`doc/archive/` 下的历史设计文档，以及契约勘误 —— 后者汇总在 doc/design/09-verification-and-limitations.md
§5。

此处适用的引用规则，依 `doc/design/SPEC.md` §4 与 §5：

* 代码指针是取自**当前 HEAD** 工作树的 `file:LINE` 引用，绝不从报告复制。
* 报告按**名称与小节**引用（`reports/35-room-grace.md` §5.1），绝不写成 `reports/<file>:LINE`，
  因为报告行号已经漂移。
* 日志键与字段键按代码发出的原样引用。只存在于设备日志、不在本仓库源码中的键，作为报告对象引用，
  而不是 `file:LINE` 符号。
* 主机路径以 `HOST:` 前缀标注。构建产物写成裸的仓库相对路径。

状态词由 `doc/design/SPEC.md` §6（E4）固定：`implemented`、`partially implemented`、
`known limitation`、`unverified`、`rejected`、`disproven`。

## 2. 模块：信令、房间与重连

### 2.1 加入方从未发送应答

| 字段 | 内容 |
|---|---|
| 症状 | 两个对端加入了同一房间，双方都始终没有画面；加入方一侧的通话一直静默 |
| 日志特征 | `offer_received` 出现，而整个会话中 `answer_create` **缺失**；服务器保持了房间存活，但没有任何 SDP 回流 |
| 根因 | 加入方的提议处理器在任何会话存在之前就运行了，因此收到的提议被丢弃。当时用排队修复，后来被 §2.6 的两级队列取代 |
| 修复 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` 先创建会话再应答；提议不再在活跃会话之外处理 |
| 证据 | `reports/15-connection-defect.md` §2.2 与 §2.3，其中 §2.8 的日志级别缺陷解释了该缺失最初为何不可见 |
| 残留风险 | 无已知项；应答路径由 §2.2 的会话生命周期顺序修复覆盖 |

### 2.2 加入方在 `PeerConnection` 存在之前就应答了

| 字段 | 内容 |
|---|---|
| 症状 | 一个方向完全没有媒体：加入方显示了远端流，但发起方从未收到视频。间歇性复现，「有时能用」 |
| 日志特征 | 同一会话世代中 `answer_create` 出现在 `pc_created` **之前**；整个通话期间发送侧统计一直是 `stats_sample impl=- up_bps=0` |
| 根因 | `PeerConnection` 被异步创建，而应答是在信令线程上创建的；`pc_created` 与 `answer_create` 的先后是一场竞态（`reports/23-session-lifecycle.md` §2.1 与 §2.2） |
| 修复 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt` 只在本地轨道与编码偏好就绪之后才发布 `PeerConnection`，因此 `pc_created` 先于 `answer_create`；启动门控加上按世代划分的通话会话槽消除了 TOCTOU 窗口（`reports/23-session-lifecycle.md` §3.1 与 §3.2） |
| 证据 | `reports/23-session-lifecycle.md` §1.1（缺陷会话）对照 §1.2（同一设备上的健康会话），以及 `reports/23-session-lifecycle.md` §5 报告的 18 个新增单元测试 |
| 残留风险 | 无已知项；顺序由单元测试断言，而不是由设备采集断言 |

### 2.3 远端候选被重复计数，而 SDP 内候选完全未被计数

| 字段 | 内容 |
|---|---|
| 症状 | 诊断横幅报告的远端候选数与线上不一致，使「是否存在中继候选？」无法判定 |
| 日志特征 | 当消息被排队并重放时，`ice_candidate_remote` 对同一个 trickle 候选出现两次；嵌在 SDP 中的候选**完全没有**计数，摘要对非零集合显示 `-` |
| 根因 | 唯一的计数点是远端候选入口回调，而它在排队候选被重放时会再次运行；`answer_received` 内的候选只用于日志，从未进入计数器（`reports/46-remote-candidate-counting.md` §1） |
| 修复 | 计数绑定到首次到达（重放被识别且不再计数），SDP 内嵌候选记入单独的桶，逐候选日志做了限流（`reports/46-remote-candidate-counting.md` §2.1 至 §2.3） |
| 证据 | 5 个新增单元测试（`RemoteCandidateAccountingTest`，`reports/46-remote-candidate-counting.md` §4.1）与 `reports/46-remote-candidate-counting.md` §4.3 的旧红对照 |
| 残留风险 | 该修复改变了此前报告引用过的某个计数器的含义；候选数只能从当前构建读取（`reports/46-remote-candidate-counting.md` §5） |

### 2.4 一次短暂的信令断连摧毁了房间

| 字段 | 内容 |
|---|---|
| 症状 | 即使媒体路径健康，瞬时的 WebSocket 断连也让双方的通话结束 |
| 日志特征 | 幸存的一方在断连后立刻收到 `peerLeft`，几秒内的重连返回 `ROOM_NOT_FOUND`（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:244`） |
| 根因 | 套接字一关闭，房间与席位就被回收；重连的对端没有任何窗口可以收回其席位（`reports/35-room-grace.md` §1） |
| 修复 | 可配置的房间宽限期保留房间与席位，`signaling/config/config.go:54`（`DefaultRoomGrace = 90 * time.Second`）；重连的对端在 `signaling/room/room.go:66` 取回席位；对端在 `signaling/room/room.go:113` 被标记为挂起、在 `signaling/room/room.go:140` 过期；`peerLeft` 恰好发送一次，来自 `signaling/server/ws_handler.go:512` |
| 证据 | `reports/35-room-grace.md` §3（9 个新增单元测试，每个验收项一个）与 §4.2（真实二进制 + 真实 TCP 探针）；同一取值的部署见 `reports/38-signaling-grace-deploy.md` §3 与 §5 |
| 残留风险 | 该取值必须始终高于客户端重连预算，见 §2.5 |

### 2.5 客户端重连预算短于服务端宽限期

| 字段 | 内容 |
|---|---|
| 症状 | 重连的客户端在服务端仍持有其席位时就放弃并退出了通话 |
| 日志特征 | 重连尝试在宽限期到期前就停止；客户端报告房间错误并退回主界面 |
| 根因 | 预算是固定的三次尝试，与 90 s 宽限期无关（`reports/39-reconnect-budget-ice-restart.md` §1） |
| 修复 | 预算复用既有的重连延迟：1/2/4/8 s 封顶，十轮，共 63 s，断言低于 75 s（`reports/39-reconnect-budget-ice-restart.md` §2.1）；耗尽的派发依据媒体活性状态（`reports/39-reconnect-budget-ice-restart.md` §2.2） |
| 证据 | `reports/39-reconnect-budget-ice-restart.md` §4（具名单元测试）与 §5.3（把断言上界收紧到 75 s 后重跑） |
| 残留风险 | 预算是编译期常量；未来的宽限期变更必须与它重新协调 |

### 2.6 会话存在之前到达的远端消息被丢弃

| 字段 | 内容 |
|---|---|
| 症状 | 重连或快速加入之后，提议与候选丢失，即使双方都在房间里也始终连不上 |
| 日志特征 | `offer_received` 早于 `pc_starting`；SDP 中的候选列表从未到达远端；出现 20 s 的「对端无响应」提示 |
| 根因 | 两个窗口：`start()` 之前的 `session == null`（窗口 A，本次修复的对象），以及 `PeerConnection` 尚未就绪的会话（窗口 B，已被更早的顺序修复覆盖）（`reports/21-remote-message-race.md` §2.1 与 §2.2） |
| 修复 | 单一队列加就绪驱动的重放：提前到达的消息被暂存，并在会话与 `PeerConnection` 就绪后按序重放（`reports/21-remote-message-race.md` §3.1）；20 s 无响应路径让停滞可诊断（`reports/21-remote-message-race.md` §3.2） |
| 证据 | `reports/21-remote-message-race.md` §1.1 对照 §1.3（重放路径在另一个会话中工作）以及 `reports/21-remote-message-race.md` §5 的单元测试 |
| 残留风险 | 重放顺序仅由单元测试断言 |

### 2.7 掉线的对端被本地断开，一次丢失的 pong 摧毁了通话

| 字段 | 内容 |
|---|---|
| 症状 | 一次丢失的 pong 升级为完整拆线；远端对端一掉线，通话也在本地被结束，宽限期来不及起作用 |
| 日志特征 | `onStateChanged(DISCONNECTED)` 驱动了本地挂断（不是消息分支）；`room_not_found_action` 与 `peerLeft` 处理都会结束通话 |
| 根因 | 本地状态机把瞬时的信令状态当作最终状态，而宽限期语义只在服务端实现（`reports/36-call-survivability.md` §1.3 与 §2.2） |
| 修复 | pong 容忍度的有效阈值至少 20 s，`peerLeft` 与 `ROOM_NOT_FOUND` 不再结束通话，媒体活性在媒体仍在流动时抑制虚假的 ICE 失败（`reports/36-call-survivability.md` §2.1 至 §2.3） |
| 证据 | `reports/36-call-survivability.md` §5.1（具名测试）与 §5.3（旧红 / 新绿对照）；服务端的对齐见 `reports/36-call-survivability.md` §9 |
| 残留风险 | 超过宽限期的长时间中断恢复仍是已知限制（known limitation），见 doc/design/09-verification-and-limitations.md §3 |

### 2.8 应用日志过滤器写反，隐藏了所有 INFO 与 WARN 行

| 字段 | 内容 |
|---|---|
| 症状 | 导出的日志里只有 DEBUG 行，因此上面头两个缺陷无法从设备采集诊断 |
| 日志特征 | 导出的 `app.log` 中级别分布为 `DEBUG 257 / other 0`；`rtc_config`（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:177`）、`pc_created`（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:389`）、`pc_ice_connection_state`（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:87`）、`answer_create` 与 `offer_received`（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:457`）以及 `call_init`（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:586`）全部**零命中**，而同一时刻的原生日志 INFO 行一切正常 |
| 根因 | 阈值判断写反了 —— 它放行的是小于等于阈值的级别，而不是大于等于 |
| 修复 | `app/src/main/kotlin/com/example/webrtcdemo/log/LogLevel.kt:53` 现在判断 `level.code >= code`，因此级别 N 放行所有至少与 N 同等严重的级别，`OFF` 静默一切，应用两侧的判据一致 |
| 证据 | `reports/15-connection-defect.md` §4（级别直方图与零命中清单） |
| 残留风险 | Kotlin 与原生过滤器是两套实现；改一处必须镜像到另一处 |

### 2.9 ICE 重启以错误的顺序发送，可能与对端冲突

| 字段 | 内容 |
|---|---|
| 症状 | 重连之后新的提议不带 ICE 重启，因此媒体路径继续使用陈旧的候选；同时重协商可能死锁 |
| 日志特征 | 重连提议缺少 `iceRestart` 标志；双方在同一窗口内都尝试发起提议 |
| 根因 | 提议在 `restartIce()` 被请求之前就发出，且两种角色都被允许重协商（`reports/40-glare-ice-restart-fix.md` §1） |
| 修复 | `restartIce("rejoin")` 在提议创建之前运行，只有对端加入的一侧发起，8 s 兜底覆盖应答缺失（`reports/40-glare-ice-restart-fix.md` §2.1 与 §2.2） |
| 证据 | `reports/40-glare-ice-restart-fix.md` §4（4 个新测试）与 §5.3（移除兜底会让测试变红） |
| 残留风险 | 该重启从未在任何设备采集中被执行过，见 doc/design/09-verification-and-limitations.md §4 |

## 3. 模块：ICE、网络、NAT 与 TURN

### 3.1 STUN 映射地址按字节反序打印

| 字段 | 内容 |
|---|---|
| 症状 | 报告的公网地址与对端实际看到的地址不一致；因此 NAT 分类不可信 |
| 日志特征 | NAT 事件载荷中的映射地址，其字节是真实地址的反序 |
| 根因 | 从 STUN 属性读出的一个大端整数被当作网络序字节数组交给地址格式化器（`reports/24-nat-address-endianness.md` §3.1 与 §3.2） |
| 修复 | `app/src/main/cpp/nat/stun_address.h` 处的字节安全解析器只处理网络序字节，并逐字节应用 RFC 5389 §15.2 的 XOR 规则；`MAPPED`、`RESPONSE-ORIGIN` 与 `OTHER-ADDRESS` 在同一路径上重新核对（`reports/24-nat-address-endianness.md` §4.1 与 §5） |
| 证据 | `reports/24-nat-address-endianness.md` §7.1（22 条字节序断言，零失败）与 §2（改前/改后地址对） |
| 残留风险 | `XOR-PEER-ADDRESS` 与 `XOR-RELAYED-ADDRESS` 不由该路径解析（`reports/24-nat-address-endianness.md` §5） |

### 3.2 ICE 候选字符串解析错位一个字段

| 字段 | 内容 |
|---|---|
| 症状 | 诊断把错误的协议、地址与端口归到某个候选上，使「中继候选是否被收集并选中？」无法回答 |
| 日志特征 | 形如 `expected:<udp> but was:<2122260223>` 与 `expected:<120.230.119.5> but was:<7627>` 的测试失败；摘要报告协议为 `2122260223`、端口为 `0` |
| 根因 | `candidate:` 前缀与 foundation 共享同一个 token；解析器把整个 token 去掉，使之后每个字段都错位一个（`reports/19-ice-candidate-parse.md` §root cause） |
| 修复 | 只剥掉前缀并让 foundation 保留为第一个字段，`app/src/main/kotlin/com/example/webrtcdemo/webrtc/IceCandidateInfo.kt:61` |
| 证据 | `reports/19-ice-candidate-parse.md` §验证（4 个夹具：host、带 raddr/rport 的 srflx、relay、带额外空白的 `a=` 前缀；`bad=0`） |
| 残留风险 | 无已知项；解析器为候选诊断供数，因此回归会导致误报而不是崩溃 |

### 3.3 coturn 拒绝回环候选的中继权限

| 字段 | 内容 |
|---|---|
| 症状 | 在一次 4G 到 WiFi 的通话中，turn 分配创建成功但对端不可达；通话回落到无媒体 |
| 日志特征 | `CreatePermission` 返回 `403 Forbidden IP`；客户端报告 `local_relay=0`，`ice_turn_error code=701` 紧随被过滤的候选 |
| 根因 | 两台设备都通告了回环候选，而 coturn 内置的拒绝列表覆盖 `0/8` 与 `127/8`，因此权限请求被拒（`reports/28-turn-permission-403.md` §2.1 与 §2.2） |
| 修复 | 在客户端两侧过滤回环候选（`ice_candidate_filtered reason=loopback`，`reports/30-ice-relay-robustness.md` §2），并针对配额与生存期加固了 turn 配置（`reports/28-turn-permission-403.md` §4.1） |
| 证据 | `reports/28-turn-permission-403.md` §2.2 的探针矩阵，以及 `reports/28-turn-permission-403.md` §5 的与真实对端的端到端复测 |
| 残留风险 | 回环拒绝条目是有意为之，必须保留；部署事实在主机侧（`HOST: /etc/turnserver.conf`） |

### 3.4 ICE 看门狗在健康通话上报告失败

| 字段 | 内容 |
|---|---|
| 症状 | 一个红色的「ICE 未连接」横幅压在正常视频通话之上，并且一直不走 |
| 日志特征 | `ice_watchdog_rearmed … elapsed_ms=1789529664872` 紧接 `ice_timeout after_ms=1789529664872`，而 300 ms 后 `ice_watchdog_ok elapsed_ms=306` 与 `pc_ice_connection_state state=CONNECTED` 到达 |
| 根因 | 看门狗起始时间未初始化，因此 elapsed 值是 epoch 毫秒数，45 s 阈值总被满足；随后错误文案没有清除路径（`reports/44-ice-watchdog-false-failure.md` §1） |
| 修复 | 基值守卫与阈值/时长一致性守卫，外加清除横幅的恢复路径（`reports/44-ice-watchdog-false-failure.md` §2.1 至 §2.3） |
| 证据 | `reports/44-ice-watchdog-false-failure.md` §4.1（6 个具名测试，`IceWatchdogPolicyTest`）与 §4.3（回退守卫会让它们变红） |
| 残留风险 | 同一「基值未初始化」模式已在所有调用点搜索过；搜索记录在 `reports/44-ice-watchdog-false-failure.md` §2.4 |

### 3.5 通话尚未连接时 UI 却报告「已连接」

| 字段 | 内容 |
|---|---|
| 症状 | 一帧冻结画面被当作实时画面呈现，不显示任何状态，也无法重试 |
| 日志特征 | 统计显示编码器在运行，但**没有选中的候选对**；会话摘要为空；此前一次「先连接后丢失」的过程不可见 |
| 根因 | 三个写入点把「尚未连接」等同于「已连接」，而活性判据用的发送侧速率（`up_bps`）无法证明有任何数据到达（`reports/29-connect-state-ui.md` §2） |
| 修复 | 一个简单的状态机 `app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt`，单一写入者，活性判据绝不使用 `up_bps`；并加入 15 s 失败卡片与重试路径（`reports/29-connect-state-ui.md` §3，`reports/31-ui-liveness-a7.md` §1） |
| 证据 | `reports/29-connect-state-ui.md` §5（单元测试）与 `reports/31-ui-liveness-a7.md` §1.2（禁止把 `up_bps` 作为活性输入的具名测试） |
| 残留风险 | 阈值是策略取值；改动它们会改变用户可见行为 |

### 3.6 画面正在更新时却报告远端帧缺失

| 字段 | 内容 |
|---|---|
| 症状 | 屏幕在实时画面之上显示「等待对方视频」，状态在已连接与连接中之间来回跳 |
| 日志特征 | 渲染器正在渲染帧，而活性信号一直沉默，因为唯一的活性来源是一次性的首帧回调 |
| 根因 | 选中候选对分支先返回，使下行速率通道不可达，而只有一次性回调能证明帧已到达（`reports/32-remote-frame-liveness.md` §1.2） |
| 修复 | 逐帧 sink 包装器统计帧数并记录墙上时钟时间，下行速率独立地驱动活性，停滞信号必须持续两个 tick（`reports/32-remote-frame-liveness.md` §2.1 至 §2.3） |
| 证据 | `reports/32-remote-frame-liveness.md` §3（单元测试）与 §5（契约检查） |
| 残留风险 | 两 tick 规则把真实的停滞报告延迟一个 tick |

### 3.7 等待对端被呈现为连接失败

| 字段 | 内容 |
|---|---|
| 症状 | 第二个对端加入之前，第一个对端看到「连接中」随后是重试提示，仿佛它自己的连接失败了 |
| 日志特征 | 尚无任何对端加入时重试计时器就在跑，因此累计时间增长并触发超时分支 |
| 根因 | 状态机没有专门的等待状态，因此「还没有对端」与「对端在但不可达」共享连接中状态及其计时器（`reports/33-waiting-peer-no-retry.md` §1.2） |
| 修复 | 等待状态（`waiting_peer`）计时器停止、`elapsedMs=0`、不做超时判定且 `canRetry=false`，在任何对端加入之前进入、对端加入时离开（`reports/33-waiting-peer-no-retry.md` §2.1） |
| 证据 | `reports/33-waiting-peer-no-retry.md` §5（单元测试）与 §6（契约检查） |
| 残留风险 | 状态迁移依赖 `peerJoined`；漏掉一条消息会让 UI 一直等待，这是更安全的失败方向 |

### 3.8 UDP 上的 TURN 是唯一被尝试的传输

| 字段 | 内容 |
|---|---|
| 症状 | 在封锁 UDP 的网络上，即使 TURN 服务器同时监听 TCP，中继路径也从未建立 |
| 日志特征 | 中继候选列表为空，或中继候选始终不可用，日志中没有任何 TCP 中继尝试 |
| 根因 | ICE 服务器列表只包含一个 UDP TURN URL（`reports/34-turn-tcp-fallback.md` §1） |
| 修复 | 在 UDP 条目之后追加一个 TCP TURN URL，凭证相同，默认开启且可切换；URL 构造被抽成纯函数，因此 JVM 测试可以断言它（`reports/34-turn-tcp-fallback.md` §2.1） |
| 证据 | `reports/34-turn-tcp-fallback.md` §5（单元测试，该修订下共 132 个）以及 `reports/34-turn-tcp-fallback.md` §1 汇总的 `reports/58` 系列证据中的 TCP 可达性预检 |
| 残留风险 | 本阶段未在设备上执行 TCP 兜底，见 doc/design/09-verification-and-limitations.md §4 |

## 4. 模块：自建 VP9 编码器

### 4.1 第一个编码帧杀死了进程

| 字段 | 内容 |
|---|---|
| 症状 | 通话连接后，在第一帧之后一秒内应用就死了；每次尝试都重复这一模式 |
| 日志特征 | 崩溃前最后一行是 `encode_vpx_begin frame=1 …`；`encode_vpx_done` **从未**出现，也没有任何 C++ 错误行，这是信号而非异常的签名 |
| 根因 | 交付的 `libvpx` 构建时禁用了运行时 CPU 检测，因此生成的 dispatch 表把率失真误差函数直接绑定到 SVE 实现；测试手机没有 SVE，于是第一个关键帧触发 `SIGILL`（`reports/25-encoder-vpx-encode-crash.md` §4） |
| 修复 | 交付的库以启用运行时 CPU 检测的方式重建（`reports/26-libvpx-runtime-cpu-detect.md` §2），应用内的 CPU 探针被降级为诊断，不再拒绝编码器（`app/src/main/cpp/encoder/vp9_encoder.cpp:84-90`） |
| 证据 | `reports/25-encoder-vpx-encode-crash.md` §1 与 §2（主机复现与反向证明），`reports/26-libvpx-runtime-cpu-detect.md` §4（新制品通过、旧制品在同一脚本下失败） |
| 残留风险 | 解码侧用的是平台解码器；这里只重建了编码器库 |

### 4.2 改变编码尺寸会在下一帧崩溃

| 字段 | 内容 |
|---|---|
| 症状 | 遇到旋转的相机帧时，编码尺寸一改变应用就崩溃，而这正是竖屏设备上会发生的情形 |
| 日志特征 | `encoder_resize` 报告成功，紧接着的一帧没有任何输出也没有错误，随后进程死亡 |
| 根因 | 尺寸是通过编码器配置更新调用改变的，而在这个单遍低延迟配置下它不重建内部帧缓冲，于是图像与编解码器尺寸不一致（`reports/20-encode-resize-crash.md` §2） |
| 修复 | 尺寸变化现在会销毁并重建编解码器上下文并强制一个关键帧，`app/src/main/cpp/encoder/vp9_encoder.cpp:616`，因此跟随方可以立即重新开始解码 |
| 证据 | `reports/20-encode-resize-crash.md` §1（设备证据）与 §4（验证）；后续的缓冲归属缺陷见下面 §4.3 |
| 残留风险 | 速率更新调用仍会写入编码器配置，这是安全的，因为它不改变尺寸（`reports/20-encode-resize-crash.md` §3） |

### 4.3 编码器包装了调用方持有的缓冲

| 字段 | 内容 |
|---|---|
| 症状 | 尺寸变更修复并不足够：第一帧仍然从同一处杀死了进程 |
| 日志特征 | 帧到达编码器，进程在编码调用内部死亡，图像被描述为外部持有的缓冲 |
| 根因 | 上一个修复通过包装调用方的平面指针消除了泄漏，却让编码器持有一个它并不拥有数据的图像，且其色度 stride 跟随亮度宽度（`reports/22-encode-selfowned-image.md` §2） |
| 修复 | 编码器保留自己的图像（由库分配），并逐行把每个平面拷贝进去；图像随编解码器上下文一起释放（`reports/22-encode-selfowned-image.md` §3） |
| 证据 | `reports/22-encode-selfowned-image.md` §1 与 §4；分配标记在 `reports/22-encode-selfowned-image.md` §3 报告自有图像的 stride |
| 残留风险 | 流水线中每帧仍多一次拷贝，它在编码延迟预算中已被计入 |

### 4.4 报告的帧长度是缓冲容量

| 字段 | 内容 |
|---|---|
| 症状 | 自建编码器上严重卡顿：发送方报告每秒数兆比特，而跟随方每秒只收到几个残缺帧 |
| 日志特征 | `encoder_perf` 对 30 fps 输入报告每秒 0.25 到 0.45 个编码帧，`up_bps` 达到 3 到 5 Mbps，而编码帧大小维持在每帧约 2.9 KB |
| 根因 | 交付的帧复用一个 512 KiB 直接缓冲，只移动其 position 与 limit；接收侧库读的是缓冲**容量**，因此每帧都被报告为 512 KiB，约为每帧预算的 63 倍。这驱动了帧丢弃器，并让 RTP 打包器迭代越过真实帧（`reports/27-encoder-direction-perf.md` §3.2） |
| 修复 | 每个交付帧现在拿到一个恰好等于编码尺寸的直接缓冲，并记录容量（`reports/27-encoder-direction-perf.md` §3.3） |
| 证据 | `reports/27-encoder-direction-perf.md` §3.1（设备量化）与 §3.2（上游读取路径） |
| 残留风险 | 无已知项；该修复同时移除了此前报告引用过的一个虚假速率信号 |

### 4.5 自建编码器产出旋转的画面

| 字段 | 内容 |
|---|---|
| 症状 | 跟随方看到的画面相对发送方屏幕逆时针旋转了 90 度 |
| 日志特征 | 设备在输入处报告 `rot=270`，而画面到达时是旋转的 |
| 根因 | 旋转既在编码器内部被应用，**又**在帧上保留了旋转标注，而输入帧本来就以显示方向交付，因此旋转实际被应用了两次（`reports/27-encoder-direction-perf.md` §2.2） |
| 修复 | 编码器默认透传旋转（`app/src/main/cpp/encoder/vp9_encoder.cpp:65`，该标志为 false），Kotlin 侧不再声称自己把旋转烤进了像素（`reports/27-encoder-direction-perf.md` §2.3） |
| 证据 | `reports/27-encoder-direction-perf.md` §2.1（证明旋转器本身正确的边界用例单元测试，39 条断言，零失败） |
| 残留风险 | 透传决定在 §5.2 中作为被否决的替代方案记录，而不是一个中性选择 |

### 4.6 码率塌缩到每秒几十千比特

| 字段 | 内容 |
|---|---|
| 症状 | 中继路径上的自建编码器通话塌缩到不可用的画面，而几分钟后同一路径上用默认编码器的通话一切正常 |
| 日志特征 | `avail_bps≈0`、`up_bps≈40 kbps`，`total_bps` 在一秒内降到 37 kbps，量化器被钉在 193 到 224 |
| 根因 | 每分辨率码率表为每个分辨率声明下限为零，这抽掉了分配中的地板：估计器一旦向下探测就无处可回（`reports/49-bitrate-allocation-collapse.md` §2，假设 H3） |
| 修复 | 该表现在遵循官方单播 VP9 上限，每档 30 kbps 地板与非零起始码率，并通过 `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:367` 暴露 |
| 证据 | `reports/49-bitrate-allocation-collapse.md` §1 的 A/B 对与 §3 的改前/改后数字；收尾数字在 `reports/52-release-closure.md` §3 |
| 残留风险 | 同一通话之后在可用 1.6 到 2.2 Mbps 的中继路径上达到约 1.0 到 1.16 Mbps，所以地板就是地板，不是质量保证 |

### 4.7 编码器慢到错过帧预算

| 字段 | 内容 |
|---|---|
| 症状 | 即使网络不是瓶颈，单线程编码器下也有可见卡顿 |
| 日志特征 | `encoder_perf` 对 480x640 帧报告 p50 高于 15 ms、p95 接近 19 ms |
| 根因 | 编码器只用一个工作线程，也没有基于行的多线程，因此无法利用可用核心（`reports/47-vp9-encode-perf.md` §2.3） |
| 修复 | 工作线程数改为 `min(cores - 1, 4)` 并启用基于行的多线程，速率请求策略让实际速率不低于请求值，且下限为 30 kbps（`reports/47-vp9-encode-perf.md` §4） |
| 证据 | `reports/47-vp9-encode-perf.md` §4.1 的主机 A/B（p50 从 15.33 ms 到 9.34 ms，p95 从 19.10 ms 到 11.76 ms，输出字节相同）与 `reports/52-release-closure.md` §4 的设备数字 |
| 残留风险 | 线程数被封顶，以便给采集与渲染留出核心；该上限是策略取值 |

### 4.8 Java 编码器从不是受信任的速率控制器，因此其输入被丢弃

| 字段 | 内容 |
|---|---|
| 症状 | 帧率一直在 20 fps 附近，而采集请求 30 fps，且没有网络拥塞 |
| 日志特征 | `native*.log` 中 `encoder_perf … in_fps=17–23` 而请求速率是 30，同时 `webrtc*.log` 中有接收侧 `WebRTC.Video.DroppedFrames.Receiver` / `.Capturer` 计数器 |
| 根因 | 适配 Java 编码器的原生包装器从不设置受信任速率控制器标志（上游 `third_party/libwebrtc-src/sdk/android/src/jni/video_encoder_wrapper.cc:124-140`），因此流编码器对每个 Java 编码器都保持帧丢弃器开启，为满足速率而丢弃输入帧（`third_party/libwebrtc-src/video/video_stream_encoder.cc:2016-2019`） |
| 修复 | 通过 field trial `WebRTC-FrameDropper/Disabled` 禁用帧丢弃器，应用在 `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164`；降级于是转向分辨率与码率，而不是丢帧 |
| 证据 | 源码链与修复前的设备计数在 `reports/51-frame-dropper-and-trusted-rc.md` §2 与 §3；修复由双方对端上的 `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/` 以及之后的 `encoder_perf in_fps=30` 确认（`reports/52-release-closure.md` §4） |
| 残留风险 | 字面串 `Drop Frame:` 是 libwebrtc 源码字符串（`third_party/libwebrtc-src/video/video_stream_encoder.cc:2022`），在 `reports/27-encoder-direction-perf.md` §3.1 记录的修复前采集中曾在设备上观测到（812 次），并且在当前七设备采集集中**不存在**；丢包下的低帧率是接收侧效应（`reports/50-quality-scaling-and-render-fps.md` §3 与 §4），不是本次修复的回归 |

### 4.9 编码器跟不上，且没有兜底

| 字段 | 内容 |
|---|---|
| 症状 | 自建编码器跟不上时通话一直很差；不重启通话就没有办法切到平台编码器 |
| 日志特征 | `encoder_perf` 窗口显示低输入帧率与高最坏编码耗时，日志中没有任何切换决定 |
| 根因 | 不存在把流交给平台编码器的策略，也没有决定日志让该情形可诊断（`reports/48-encoder-fallback.md` §1） |
| 修复 | 策略加控制器在活跃通话内切换编码器，带迟滞且每轮至多切换一次；切换在 5 s 内未被确认则在下次通话生效；诊断屏可以强制任一状态（`reports/48-encoder-fallback.md` §2.3） |
| 证据 | `reports/48-encoder-fallback.md` §6（2 个测试类，15 个用例）与 §7.3（回退判据会让它们变红） |
| 残留风险 | 真实低端手机上的触发率没有测量，见 doc/design/09-verification-and-limitations.md §4 |

## 5. 模块：渲染与通话 UI 状态机

### 5.1 离开应用再进入后本地预览变黑

| 字段 | 内容 |
|---|---|
| 症状 | 切到另一个应用再切回来之后，本地预览是黑的，尽管编码仍在继续 |
| 日志特征 | 切换前后没有任何 surface 生命周期事件，恢复后也没有重新挂载渲染器 |
| 根因 | 屏幕完全没有前台/后台处理，sink 只在 key 变化时挂载，因此恢复路径从不重新挂载它，而唯一的卸载点不可逆（`reports/16-foreground-black-preview.md` §2） |
| 修复 | 幂等的恢复路径重新挂载 sink 并按需重启采集，恢复时不再重建渲染器实例（`reports/16-foreground-black-preview.md` §3） |
| 证据 | `reports/16-foreground-black-preview.md` §1（设备日志）与 §3.2（改动的文件） |
| 残留风险 | 相机策略在某些设备上仍可拒绝重启，这会记录为相机错误而不是被隐藏 |

### 5.2 渲染器曾被怀疑是卡顿原因并被排除

| 字段 | 内容 |
|---|---|
| 症状 | 卡顿被归因于渲染器，那将证明改动渲染路径是合理的 |
| 日志特征 | 接收侧渲染指标显示**零**丢帧，渲染开销 0.5 到 1.4 ms，而流本身到达得很慢 |
| 根因 | 不是渲染器缺陷：丢失在接收/解码侧，发送方帧率受速率/拥塞策略限制（`reports/50-quality-scaling-and-render-fps.md` §3 与 §4） |
| 修复 | 不改渲染器；渲染流水线保持「到达的帧即被渲染」的路径，并记录该结论以免重复归因 |
| 证据 | `reports/50-quality-scaling-and-render-fps.md` §3（丢帧计数器与逐帧开销）与 `reports/52-release-closure.md` §4 |
| 残留风险 | 接收侧丢失本身仍是网络属性；在一个对端上测得的丢失率达到 18% |

## 6. 模块：构建、发布与诊断完整性

### 6.1 交付的 jar 缺少生成的 JNI 绑定类

| 字段 | 内容 |
|---|---|
| 症状 | 通话界面一加载应用就以 `NoClassDefFoundError` 崩溃，尽管编译、打包与单元测试全部通过 |
| 日志特征 | 一个类未找到错误，点名一个生成的绑定类，在首次使用时抛出 |
| 根因 | jar 是用仅含直接依赖的 classpath 组装的，因此作为传递输入的生成绑定类被系统性地排除（`reports/99-final-report.md` §11.4，条目 D-1） |
| 修复 | 显式加入绑定类并合成生成的 dispatcher，构建中带类存在性检查（`reports/99-final-report.md` §13.24） |
| 证据 | `reports/99-final-report.md` §12.4（静态验证为何漏掉它）与 §13.24（修复后的制品及其验证） |
| 残留风险 | 静态流水线无法证明运行时依赖存在；类存在性检查就是守卫 |

### 6.2 原生库未按 16 KB 页对齐

| 字段 | 内容 |
|---|---|
| 症状 | 在 16 KB 内存页的设备上库加载失败，与缺失类缺陷相互独立 |
| 日志特征 | 启动时库加载失败 |
| 根因 | 工具链默认的最大页大小产出了按 4 KB 对齐的加载段（`reports/99-final-report.md` §11.4，条目 D-2） |
| 修复 | 应用库以 16 KB 最大页大小链接，共享 C++ 运行时为同一对齐重建并打包（`reports/99-final-report.md` §11.4 与 §13.26） |
| 证据 | 打包 APK 内每个加载段的对齐情况记录在 `reports/42-delivery-verification.md` §1.3 |
| 残留风险 | 未来的工具链变更可能重新引入默认值；对齐检查应属于发布链 |

### 6.3 仓库中的部署单元与运行中的服务不一致

| 字段 | 内容 |
|---|---|
| 症状 | 从仓库重新部署会把房间宽限期悄悄回退到旧行为 |
| 日志特征 | 服务启动时没有宽限期标志，并在健康输出中报告了不同的宽限期取值 |
| 根因 | 仓库中的单元文件缺少线上单元已有的宽限期标志（`reports/43-deploy-unit-consistency.md` §1） |
| 修复 | 把该标志加入 `deploy/signaling.service`，使仓库单元与线上单元在有效指令上逐字节一致（`reports/43-deploy-unit-consistency.md` §2） |
| 证据 | 三份对比输出（有效指令、完整 `ExecStart` 行、原始 diff）在 `reports/43-deploy-unit-consistency.md` §3.2，旧二进制的显式失败行为在 §4.2 中测量 |
| 残留风险 | 下载与发布面在主机侧，保持在仓库之外（`reports/99-final-report.md` §11.4，条目 D-6） |

### 6.4 APK 不可字节复现

| 字段 | 内容 |
|---|---|
| 症状 | 同一源码的两次构建产出不同的 APK 哈希，削弱了基于哈希的发布识别 |
| 日志特征 | 不是日志项；表现为相同输入下各次构建的摘要不同 |
| 根因 | dex 分片在构建之间不稳定（`reports/52-release-closure.md` §5） |
| 修复 | 无：作为已知限制（known limitation）处理；身份始终是记录的摘要加大小，绝不是「同一次构建」 |
| 证据 | `reports/52-release-closure.md` §1（带哈希与字节大小的发布锚点）与 `reports/42-delivery-verification.md` §1 |
| 残留风险 | 重建无法被证明完全相同；发布链必须发布它构建出的摘要 |

## 7. 模块：工具与环境

### 7.1 文档门禁的路径纪律在修订之间反复反转

| 字段 | 内容 |
|---|---|
| 症状 | 门禁在某个修订上接受的引用写法，在下一个修订上被拒绝，两个方向都发生过，因此一次绿色运行只能证明文档与产出它的那个修订匹配 |
| 日志特征 | 无日志；表现为在某修订上探针退出码 0 且发现列表为空，而在另一修订上同一段探针文本以非零退出并给出标记发现 |
| 根因 | 变的是规则本身，不只是它的实现。工作区路径引用所要求的写法经历了四种状态：在提交前的草稿修订里要求标签前缀，随后被当作硬失败，随后在自动分类路径上被容忍为提示，最终在冻结修订里恢复为硬失败（`reports/55-captain-ruling-path-notation.md` §3）。原因是规范对同一条规则陈述了两种严重度 —— 它的标签规则与 V8 规则要求失败，而它作用域小节的 P5 行与 V13 错误列读起来像提示 —— 因此每次重新实现都遵循同一文档的不同句子（`reports/55-captain-ruling-path-notation.md` §3）。captain 裁定采用失败读法，规范措辞也对齐到它（`reports/55-captain-ruling-path-notation.md` §2） |
| 修复 | 路径分类被整合为基于形状的类别，并为主机路径给出显式作用域，规范的两处严重度陈述被对齐为一处。本文件集采用的写法在所有已观测状态下都被接受 —— 受跟踪文件用裸的仓库相对路径，工作区证据与构建产物用裸路径，主机路径用显式主机作用域 |
| 证据 | 四态历史及其根因记录在 `reports/55-captain-ruling-path-notation.md` §3，被作废的裁定在 §2；路径标签探针记录在仓库之外的 `/data/dsh/home/workspace/tmp/verifier-recon/`；对本文件的修订登记在 `reports/55-captain-ruling-path-notation.md` §9；本条要求的摘要绑定陈述在 doc/design/09-verification-and-limitations.md §2 |
| 残留风险 | 门禁在修订之间不是字节稳定的，因此只有点明产出它的摘要时，结论才有意义。冻结修订在它被提交期间是可重算的：`git show 8fdb222:scripts/doc-verify.sh` 给出被固定的摘要，写作时 `HEAD` 携带的是同一内容。早于检查脚本首次提交的那些修订无法恢复或重跑，这就是为什么每个结论都必须点明某个已提交修订的摘要，而不是工作树状态 |
| 状态 | `known limitation`（写作规则已定，且本文件集在每个实测修订下都满足它；门禁仍然对修订敏感，因此每个结论都绑定到 doc/design/09-verification-and-limitations.md §2 记录的摘要） |

### 7.2 规范自己的示例围栏教了退役写法

| 字段 | 内容 |
|---|---|
| 症状 | 按规范路径词汇小节中的示例块照做的读者，会写出同一规范规则判为失败的写法 |
| 日志特征 | 无日志；表现为对逐字照抄该围栏的文档报门禁失败（`retired marker used as a path prefix`） |
| 根因 | 该示例围栏在一次让标记前缀变成强制的修订中被改写，从而反转了极性：它的「正确」行变成了带标签形式，「错误」行变成了无标记形式，与同一文件陈述的规则集相反。该缺陷是在那次改写中引入的，而不是继承来的：`git show e093e8f:doc/design/SPEC.md` 处更早的规范文本把裸形式标为正确、把未标记的主机路径标为错误 |
| 修复 | 规范从更早的文本恢复并对齐措辞，使示例极性与规则重新一致（`reports/55-captain-ruling-path-notation.md` §9） |
| 证据 | 当前文件与 `e093e8f` 处更早文本的对比登记在 `reports/55-captain-ruling-path-notation.md` §9；该围栏的两条「正确」行正是对规范自身报告的两条失败 |
| 残留风险 | 示例块不会与其旁边的规则做一致性检查，因此未来的改写可以再次反转它而没有任何门禁察觉 |
| 状态 | `implemented`（规范文本已恢复并重新对齐；这类缺陷 —— 一个与自身规则集矛盾的示例 —— 记录在此，以免后续修订重蹈覆辙） |

## 8. 已否决（rejected）与已证伪（disproven）的方案

本小节记录被论证过但未采纳、或被检验并证明为假的路径。每个条目给出假设、推翻它的证据，以及替代它
的东西。未记录在此的被否决方案往往会被再次提出。

| # | 方案或假设 | 状态 | 证据 | 替代方案 |
|---|---|---|---|---|
| R-1 | 把旋转烤进编码器内的 I420 像素 | `rejected` | 跟随方看到旋转的画面：输入帧本来就以显示方向到达，因此烤入会旋转两次（`reports/27-encoder-direction-perf.md` §2.2 与 §2.3） | 透传旋转（`app/src/main/cpp/encoder/vp9_encoder.cpp:65`） |
| R-2 | 用「第一帧被 libwebrtc 推迟，所以后续帧被丢弃」解释编码器停滞 | `disproven` | 阅读了上游逻辑，并用帧级标记复现了停滞；真实原因是缓冲容量导致的帧长度（`reports/18-encoder-stall.md` §3，`reports/27-encoder-direction-perf.md` §3.2） | 精确容量的缓冲修复 |
| R-3 | 通过编码器配置更新调用改变编码尺寸 | `rejected` | 该调用报告成功而下一帧杀死了进程（`reports/20-encode-resize-crash.md` §2） | 销毁并重建编解码器上下文，然后强制一个关键帧 |
| R-4 | 用包装调用方平面的方式减少内部帧拷贝，而不是拷贝 | `rejected` | 被包装的图像归外部所有且色度 stride 非标准；第一帧仍然崩溃（`reports/22-encode-selfowned-image.md` §2） | 库自有图像加逐行拷贝 |
| R-5 | 启动时在 CPU 缺少 SVE2 或点积支持时拒绝自建编码器 | `rejected` | 该守卫只是诊断性的：真正的缺陷是构建标志，用运行时 CPU 检测重建库后 SIGILL 完全消失（`reports/26-libvpx-runtime-cpu-detect.md` §1 与 §4） | 以运行时 CPU 检测重建 `libvpx`；把探针保留为诊断（`app/src/main/cpp/encoder/vp9_encoder.cpp:84-90`） |
| R-6 | 禁用运行时 CPU 检测以减少每帧开销 | `rejected` | 它把 dispatch 表在构建期绑定到 SVE，在没有 SVE 的设备上杀死每一次通话（`reports/25-encoder-vpx-encode-crash.md` §4） | 启用运行时 CPU 检测（`reports/26-libvpx-runtime-cpu-detect.md` §2） |
| R-7 | 把「旋转已烤进 I420 像素」当作采集路径的性质 | `disproven` | 上游帧缓冲不携带旋转，绘制器在绘制时应用旋转（`reports/99-final-report.md` §14，条目 D-4） | §4.5 记录的透传模型 |
| R-8 | 在每次状态迁移时用完整 ICE 重启恢复静默离线的对端 | `rejected` | 当前运行时不具备该能力，因此验收只能覆盖信令恢复（`reports/99-final-report.md` §0.3 与 §8.5） | 保持通话并重连；记录该限制 |
| R-9 | 重连期间允许两种角色都重协商 | `rejected` | 双方可能同时发送提议，使重协商停滞（`reports/40-glare-ice-restart-fix.md` §1） | 只有对端加入的一侧发起；发起方保留提议职责 |
| R-10 | 认为 TURN 服务器的回环拒绝列表过严并放宽它 | `rejected` | 拒绝条目是有意的边界：回环候选必须由客户端过滤，为回环地址请求权限才是缺陷（`reports/28-turn-permission-403.md` §2.3） | 在客户端两侧过滤回环候选（`reports/30-ice-relay-robustness.md` §2） |
| R-11 | 用发送侧速率作为连通性活性信号 | `rejected` | 它无法证明有任何数据到达，并同时产生虚假的「已连接」与「失败」状态（`reports/31-ui-liveness-a7.md` §1.1，`reports/36-call-survivability.md` §2.3） | 候选对状态加远端帧或下行速率证据 |
| R-12 | 用采集速率或渲染器解释低帧率 | `disproven` | 采集报告零丢帧、渲染器零丢帧，而流到达很慢；发送方在丢弃输入帧（`reports/50-quality-scaling-and-render-fps.md` §3 与 §4，`reports/51-frame-dropper-and-trusted-rc.md` §2） | 禁用帧丢弃器，让分辨率与码率吸收拥塞 |
| R-13 | 把 Java 编码器标记为受信任速率控制器 | `rejected` | 这个 libwebrtc 修订没有从 Java 编码器路径暴露该入口（`reports/51-frame-dropper-and-trusted-rc.md` §4） | 通过 field trial 禁用帧丢弃器 |
| R-14 | 作为即时修复，用短时 REST 方案给 turn 服务器提供凭证 | `rejected`（已推迟） | 暴露面已被测量且用户接受了风险；如果出现触发条件，短时方案是推荐加固（`reports/45-turn-exposure-accepted-risk.md` §4 与 §5） | 接受风险并记录触发条件，见 doc/design/09-verification-and-limitations.md §3 |
| R-15 | 用句中恰好出现的某个词来满足路径标签 | `rejected` | 一对 A/B 探针只差一个词，同一检查器却给出不同结论，使门禁结论歧义 | 基于形状的路径分类加显式主机作用域，绑定到检查器摘要 |

## 9. 证据索引

| 声明或条目 | 引用 | 验证产物 |
|---|---|---|
| 2.1 加入方从未应答 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` | `reports/15-connection-defect.md` §2 |
| 2.2 应答早于连接 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140` | `reports/23-session-lifecycle.md` §1 与 §3 |
| 2.3 候选计数 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1186` | `reports/46-remote-candidate-counting.md` §4 |
| 2.4 房间宽限期 | `signaling/config/config.go:54`, `signaling/room/room.go:66`, `signaling/server/ws_handler.go:512` | `reports/35-room-grace.md` §3 与 §4 |
| 2.5 重连预算 | `reports/39-reconnect-budget-ice-restart.md` §2.1 | `reports/39-reconnect-budget-ice-restart.md` §4 |
| 2.6 提前到达的远端消息 | `reports/21-remote-message-race.md` §3.1 | `reports/21-remote-message-race.md` §1.3 与 §5 |
| 2.7 存活能力 | `reports/36-call-survivability.md` §2 | `reports/36-call-survivability.md` §5 |
| 2.8 日志级别过滤器 | `app/src/main/kotlin/com/example/webrtcdemo/log/LogLevel.kt:53` | `reports/15-connection-defect.md` §4 |
| 2.9 ICE 重启顺序 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272` | `reports/40-glare-ice-restart-fix.md` §4 |
| 3.1 STUN 字节序 | `app/src/main/cpp/nat/stun_address.h` | `reports/24-nat-address-endianness.md` §7.1 |
| 3.2 候选解析 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/IceCandidateInfo.kt:61` | `reports/19-ice-candidate-parse.md` §验证 |
| 3.3 turn 权限 | `reports/28-turn-permission-403.md` §2 与 §5 | `reports/28-turn-permission-403.md` §2.2 |
| 3.4 看门狗 | `reports/44-ice-watchdog-false-failure.md` §2 | `reports/44-ice-watchdog-false-failure.md` §4 |
| 3.5 连接状态 UI | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt` | `reports/29-connect-state-ui.md` §5, `reports/31-ui-liveness-a7.md` §1.2 |
| 3.6 帧活性 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt` | `reports/32-remote-frame-liveness.md` §3 |
| 3.7 等待对端 | `reports/33-waiting-peer-no-retry.md` §2.1 | `reports/33-waiting-peer-no-retry.md` §5 |
| 3.8 TCP 上的 TURN | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt` | `reports/34-turn-tcp-fallback.md` §5 |
| 4.1 SVE SIGILL | `third_party/libwebrtc-src/video/video_stream_encoder.cc:2022` | `reports/25-encoder-vpx-encode-crash.md` §1, `reports/26-libvpx-runtime-cpu-detect.md` §4 |
| 4.2 尺寸变更崩溃 | `app/src/main/cpp/encoder/vp9_encoder.cpp:616` | `reports/20-encode-resize-crash.md` §1 |
| 4.3 自有图像 | `app/src/main/cpp/encoder/vp9_encoder.cpp:186` | `reports/22-encode-selfowned-image.md` §3 |
| 4.4 帧长度 | `reports/27-encoder-direction-perf.md` §3.2 | `reports/27-encoder-direction-perf.md` §3.1 |
| 4.5 旋转透传 | `app/src/main/cpp/encoder/vp9_encoder.cpp:65` | `reports/27-encoder-direction-perf.md` §2.1 |
| 4.6 码率地板 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:367` | `reports/49-bitrate-allocation-collapse.md` §1 与 §3 |
| 4.7 编码性能 | `app/src/main/cpp/encoder/vp9_encoder.cpp:906` | `reports/47-vp9-encode-perf.md` §4.1 |
| 4.8 帧丢弃器 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164` | `reports/51-frame-dropper-and-trusted-rc.md` §3, `reports/52-release-closure.md` §4 |
| 4.9 编码器兜底 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:618` | `reports/48-encoder-fallback.md` §6 |
| 5.1 黑屏预览 | `reports/16-foreground-black-preview.md` §3 | `reports/16-foreground-black-preview.md` §1 |
| 5.2 渲染器已排除 | `reports/50-quality-scaling-and-render-fps.md` §3 | `reports/50-quality-scaling-and-render-fps.md` §3 与 §4 |
| 6.1 缺失绑定类 | `reports/99-final-report.md` §11.4 条目 D-1 | `reports/99-final-report.md` §12.4 与 §13.24 |
| 6.2 16 KB 页 | `reports/99-final-report.md` §11.4 条目 D-2 | `reports/42-delivery-verification.md` §1.3 |
| 6.3 部署单元 | `deploy/signaling.service` | `reports/43-deploy-unit-consistency.md` §3.2 |
| 6.4 APK 可复现性 | `reports/52-release-closure.md` §1 | `reports/99-final-report.md` §0.2 |
| 7.1 门禁纪律 | `scripts/doc-verify.sh` | `reports/55-captain-ruling-path-notation.md` §2 与 §3, doc/design/09-verification-and-limitations.md §2 |
| 7.2 规范示例围栏 | `reports/55-captain-ruling-path-notation.md` §9 | 同一小节，以及它所引用的 `e093e8f` 文本 |
| R-1 .. R-15 已否决或已证伪的方案 | 各行点名的报告 | 同样的报告 |

## 10. 待办事项

| # | 事项 | 为何仍未闭合 | 如何闭合 |
|---|---|---|---|
| I-1 | ICE 重启从未在设备上被执行 | 当前设备日志集里每个文件都缺少重启键（例如 `../tmp/n6/x/app.log:6170`）；只验证了信令路径 | 强制一次足够长的重连以触发重启，并在日志中确认重启请求 |
| I-2 | 超出服务端宽限期的长时间中断恢复 | 没有会话被保持到跨过宽限期 | 保持一次通话，把无线模块阻断超过 90 s，并观察恢复路径 |
| I-3 | 弱网限制（丢包约高于 20%） | 没有采集进入该区间 | 在模拟丢包配置下跑一次通话，并同时记录帧率与丢包 |
| I-4 | 8 s 兜底的触发率 | 该兜底没有被观测到触发 | 在多次重连中统计兜底事件 |
| I-5 | 真实低端设备上的编码器兜底 | 测试设备不是低端机 | 在低端手机上重复采集并读取兜底决定键 |
| I-6 | 同一丢包下默认编码器与自建编码器的对比 | 两条路径从未在相同丢包下比较过 | 在一条链路上先后跑两条路径并比较帧率 |
| I-7 | 每个门禁结论只对产出它的摘要有效 | 文档集写作期间检查器被反复修订，因此从其它修订复制的结论无效 | 在每条门禁陈述中给出检查器的 `sha256`，如 doc/design/09-verification-and-limitations.md §2 所做的那样；冻结修订可通过 `git show 8fdb222:scripts/doc-verify.sh` 重算 |
