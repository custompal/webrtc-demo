# reports/15-connection-defect.md — 真机「两端连不通」缺陷修复（t44）

- **任务**：t44（implementation，attempt 5，执行者 `native-dev`）。
- **现象**（用户第二轮真机反馈）：预览朝向与会议号已修好，但**两端通话一直停在「正在连接会议」**，
  传输 ↑0bps/↓0bps、编码码率 0、远端画面停在「正在连接会议…」。
- **约束遵守**：容器内**未跑 gradle**（无 JDK/SDK）；未改 `third_party/**`、`doc/**`、`*.jar|aar|so`、`scripts/**`；
  仅改 `app/src/**` 与本报告。**真机连通性未验证** —— 下文"结论"只代表源码级 + 日志级证据自洽。

---

## 0. 结论摘要

| # | 缺陷 | 根因（代码级） | 根因（日志级） | 状态 |
|---|---|---|---|---|
| ① | joiner 收到 offer **不回 answer** | `CallViewModel.onMessage` 的 `session?.onRemoteOffer(...)`（空会话**静默丢弃**）+ `CallSession.onRemoteOffer` 的 `peerConnection ?: return`（**早退发生在日志之前**）+ `initCall` 在 `start()` 失败后**仍保留 session** | 信令日志 `TYPNM9`/`BWTYCN` **只有 `offer_forward`、无 `answer_forward`**；host 侧导出日志 `answer*` 命中 **0** | **已修复**（暂存+回放+必报错） |
| ② | 有 answer 的会话 **ICE 不连通（无 DTLS）** | 缺 ICE/DTLS/selected-pair/候选诊断 + 无超时兜底与 UI 上报，且本地 NAT = **Symmetric** 时默认 `ALL` 策略下 srflx 不可用 | `stun_request` 1→9 无响应；`Ice not completed yet`；**无一次 DTLS 握手**；但 relay 端口已建立、候选已入 SDP/信令 | **已定因到"连通性检查/可达性"层 + 补齐诊断与兜底**；relay 对为何未通过**仍需下一轮真机日志判定**（如实登记） |

**日志过滤 Bug 是本轮定因受阻的直接原因**（`app.log` 在 `log_level=DEBUG(1)` 下只剩 DEBUG 行），已修复。

---

## 1. 证据来源与口径

- 设备日志（宿主 `/opt/dsh-workspaces/tmp/dev-logs-2232/x/` ↔ 容器 `/data/dsh/home/workspace/tmp/dev-logs-2232/x/`，本报告全部读盘在**容器内**完成）：
  `app.log`（257 行）、`native.log`（31 805 B）、`webrtc.log`（254 444 B）、`session-summary.txt`、`device-info.txt`、`app-fallback.log`。
- 信令日志 `/var/log/signaling/signaling.log` **容器不可见**（宿主路径）；相关事实（`offer_forward`/`answer_forward` 会话对照）按任务给定证据引用，**不当作我方第一手复算**。
- `device-info.txt`：`log_level=DEBUG(1)`、设备 `Xiaomi 24117RK2CC / Android 16 (API 36) / arm64-v8a`。
- `session-summary.txt`：`roomId=BWTYCN role=host local_nat=Symmetric remote_nat= ice_events=13 up_bps=0 down_bps=0`。

---

## 2. 缺陷① joiner 不回 answer —— 根因与修复

### 2.1 日志级证据
- 信令侧（任务给定）：`TYPNM9`(14:32:19–14:33:09) 与 `BWTYCN`(14:34:02–14:35:03) **只有 `offer_forward`、没有任何 `answer_forward`**；
  而 `VMCJ2S`(14:31:49)、`FSANEG`(14:32:11) 有 `answer_forward bytes=2470/2615`。
- 设备侧 `app.log`：`answer` 关键词命中 **0**（257 行全部为 DEBUG 行）——**既没有 answer 相关日志，也没有任何错误日志**。
- 关键放大因素：`log_level=DEBUG(1)` 时 INFO/WARN/ERROR 全被丢弃（见 §4），所以"静默丢弃 offer"这条路径**完全没有痕迹**。

### 2.2 代码级根因（修复前 `file:line`）
| 位置 | 代码 | 问题 |
|---|---|---|
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:305` | `is SignalingMessage.Offer -> session?.onRemoteOffer(message.sdp)` | `session == null` 时**静默丢弃**，无日志、无 UI 错误 |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:184-186` | `fun onRemoteOffer(sdp: String) { val connection = peerConnection ?: return; AppLog.i(... "offer_received" ...) }` | **早退发生在日志之前** ⇒ PC 未就绪时连"收到过 offer"都不落盘 |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:174-179` | `session = callSession` … `if (!ok) { fail("创建 PeerConnection 失败"); return }` | `start()` 失败后 **`session` 仍非空**，但 `peerConnection` 为空 ⇒ 后续 offer/answer 全部走进上面的静默路径 |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:232-238`（`onRemoteAnswer`） | `val connection = peerConnection ?: return` | host 侧对称竞态同样静默 |

**竞态成因**：joiner 侧 `joined`（带来 TURN 配置，`IceServerCache.put` 在 `ui/home/HomeViewModel.kt:196`）与 `offer` 是两条独立信令；
`CallViewModel.initCall` 在进入通话页时就 `callSession.start(IceServerCache.get(), …)`（`CallViewModel.kt:175`）。
若 offer 先于 PC 创建到达（或 `start()` 失败/引擎初始化失败），原实现**既不回 answer 也不报错** —— 与"信令只见 offer_forward"完全吻合。

### 2.3 修复（本仓库现行 `file:line`）
| 位置 | 修改 |
|---|---|
| `CallSession.kt:117-142` | 新增 `pendingRemoteOffer` / `pendingRemoteAnswer` / `pendingRemoteCandidates` + `RemoteCandidate`（PC 就绪前暂存） |
| `CallSession.kt:196-198` | `start()` 成功后调用 `flushPendingRemote()`：**回放**暂存 offer/answer/ICE（`offer_replayed`/`answer_replayed`/`ice_replayed`/`remote_replay_done`） |
| `CallSession.kt:250-273` | `onRemoteOffer`：**先** 记 `offer_received`，再判就绪；未就绪 ⇒ `offer_deferred` + `listener.onError("…Offer 已暂存（就绪后会回 answer）")`；已关闭 ⇒ `offer_dropped` + 报错 |
| `CallSession.kt:333-347` | `onRemoteAnswer` 同样处理（`answer_deferred` / `answer_dropped`） |
| `CallSession.kt:369-392` | `onRemoteIceCandidate`：解析候选 → 计数 → 未就绪则 `ice_deferred` 入队；非法 mid/index 仍 `ice_dropped` |
| `CallViewModel.kt:305-333` | offer/answer/ice 三分支**不再用 `session?.`**：会话为空时 `AppLog.e(… "offer_without_session"/"answer_without_session")` + `onError(...)`；ICE 为空记 WARN |
| `CallViewModel.kt:174-184` | `start()` 失败 ⇒ `session = null` + `callSession.close()` + `fail(...)`，消除"有 session 无 PC"的不一致态 |

⇒ 语义达到要求：**「收到 offer 必回 answer（暂存后回放）或必报可诊断错误」**。

---

## 3. 缺陷② 有 answer 的会话 ICE 不连通 —— 逐条判定

### 3.1 `AllocationSequence: UDP/STUN ports disabled, skipping` 的含义（**不是"禁用 UDP"**）
- 源码：`third_party/libwebrtc/include/p2p/client/basic_port_allocator.cc:1442`（UDP）、`:1530`（STUN）、`:1568`（Relay）为日志点；
  判定逻辑在 `:1330-1373`（`AllocationSequence::ComputeFlags` 段）：
  - `:1330-1333` 注释明确：`PORTALLOCATOR_DISABLE_UDP` 是**避免因已存在的 UDP 端口（例如 UDP 上的 TurnPort）重复 gather host/srflx**；
  - `:1334-1342` 仅当**同一网络**上已存在**未剪枝且无错误的 `kHost` UDP 端口**时才置 `DISABLE_UDP`；
  - `:1361-1365` 仅当 STUN 服务器与上一轮相同且已 `DISABLE_UDP` 时才置 `DISABLE_STUN`；`:1366-1373` relay 已覆盖时置 `DISABLE_RELAY`。
- ⇒ 语义 = **re-gather 去重（同一网络/同一 STUN/同一 relay 不重复分配）**，**不是**"禁用 UDP/STUN"。
  真机日志里该行旁边同时存在 relay/host 端口的实际分配（见 3.2），与该解释一致。

### 3.2 本端是否真的 gather 到 host/srflx/**relay** —— **是**
- relay（TURN 分配成功）：
  - `webrtc.log:183/185/219/221/255/257 …`（`webrtc_turn_port_cc (line_803)`）共 **21 行**，两种网络各一：
    `Port[…:relay:Net[wlan0:192.168.10.x/24:Wifi:id=5]]`（11 行）、`Port[…:relay:Net[rmnet_data6:10.46.17.x/29:Cellular:id=3]]`（10 行）；
  - `webrtc.log:468/476/484/488/492`（`webrtc_connection_cc (line_1015)`）出现**可用的 relay 连接对象**：
    `…:relay:udp:47.238.144.x:49166->mIK83Td+…`、`…:relay:udp:47.238.144.x:49156->…`（本地 relay 地址 = 公网 TURN 47.238.144.x）。
- srflx：`webrtc.log` 出现 `srflx:udp:120.230.119.x:7627`（并有 `UpdateState()` 记录）。
- host：`webrtc.log` 中 `host:` 命中 421 次（`192.168.10.x` wlan0 / `10.46.17.x` rmnet_data6 / `127.0.0.x` lo）。
- 本地 NAT 判定：`native.log`（`stun_response mapped=195.211.55.111:10268 origin=66.144.238.47:3478 rtt_ms=41` + 后续 `change_ip=1 change_port=1` 探针）⇒ `session-summary.txt` 记 **local_nat=Symmetric**。

### 3.3 候选是否进入 SDP / 信令 —— **是**
- 信令：`app.log` 中 `msg_sent … type=Ice` 多条（单条 175–214 B）⇒ 本端 ICE 候选**确实通过信令发出**。
- SDP：t44 新增 `offer_created`/`answer_created`/`answer_received` 事件会打印 `candidates=host=n,srflx=n,relay=n`（**本轮之前无此证据**）；
  历史日志可间接佐证：对端候选已到达本端（下述连接对象引用对端 ufrag `VdQrdrIG`/`mIK83Td+`/`N5Zn0/CO` 等）。

### 3.4 失败点定位：**连通性检查 / 可达性**，不在 gather、不在信令、不在 TURN 服务端
- `webrtc_stun_request_cc (line_336)`：`Sent STUN request 1..9`，退避 **250→8000 ms**，**全程无响应**（`webrtc.log` 中该模式 79 行）。
- `webrtc_p2p_transport_cha (line_445)`：`Channel[0|1|RW: Ice not completed yet for this channel as Net[rmnet_data6:…] has more than 1 connection.`
- **全日志无一次 DTLS 握手**：`DTLS` 命中 17 处，逐行核对**全部**是 `webrtc_webrtc_session_de (line_172): DTLS-SRTP enabled; sending DTLS identity request`（会话描述阶段），
  无 ClientHello/证书/`pc_connection_state=CONNECTED`；`ICE connected`/`CONNECTED`/`selected` 命中 **0**。
- TURN 服务端无罪（任务给定）：`turnutils_uclient` 公网中继 8/8、0 丢包；宿主 relay 端口段（49152-49200）外部可达；coturn `active`。
- ⇒ **可验证结论**：本端 **gather 完整**（host+srflx+relay）、**候选已发出且对端候选已到达**（连接对象引用对端 ufrag）、
  但 **连通性检查从未成功**（STUN 重发无响应 + 无 DTLS），因此停在"正在连接会议"。
  **在 Symmetric NAT 下，srflx 候选对 P2P 不可用**，唯一有希望的路径是 **relay**；
  默认 `ALL` 策略下 ICE 会把大量检查浪费在不可达的 host/srflx 对上。
- **仍未能判定（如实登记）**：已建立的 relay 候选对是否被真正检查、以及为何未通过（对端网络屏蔽 UDP？对端候选不可达？TURN 端口在对端方向被限速/丢包？）——
  历史日志里**没有任何 selected-pair/连接状态**记录（被日志 Bug 吞掉）。t44 新增的诊断 + 超时兜底正是为"一次复测即定因"设计。

### 3.5 本轮实施的必要修复（②相关）
| 位置 | 内容 |
|---|---|
| `webrtc/PeerConnectionObserverImpl.kt` | 覆盖三个此前未实现的默认回调：`onConnectionChange`（→`pc_connection_state`，DTLS/传输是否 CONNECTED）、`onSelectedCandidatePairChanged`（→`selected_candidate_pair`，本地/远端候选类型 + P2P/RELAY）、`onIceCandidateError`（→`pc_ice_candidate_error`） |
| `webrtc/CallSession.kt:461-524` | `ice_candidate_local` / `ice_candidate_remote` 打点（**类型/协议/地址/端口**）；`ice_gathering_complete` 打印本端按类型计数与 relay 数 |
| `webrtc/CallSession.kt:616-690` | **连通性看门狗**：15 s `ice_not_connected`（WARN，含本端/对端候选计数、relay 数、`remote_desc_set`）；30 s `ice_timeout`（ERROR + `listener.onError(… 可打开「强制中继」后重试 …)`）；连上则 `ice_watchdog_ok` 并取消 |
| `webrtc/CallSession.kt:150-176` | `pc_starting`：落盘**实际 ICE 配置**（stun/turn）与 `force_relay` |
| `webrtc/IceCandidateInfo.kt`（新增） | 纯 Kotlin 候选解析 + 计数 + SDP 候选统计（可单测） |

---

## 4. 日志等级过滤缺陷（P0，本轮定因受阻的直接原因）

- **根因**：`app/src/main/kotlin/com/example/webrtcdemo/log/LogLevel.kt:44`
  `fun isEnabledFor(level) = this != OFF && level.code <= code` —— 与阈值语义**相反**：
  阈值 `DEBUG(1)` 时只放行 `VERBOSE(0)`/`DEBUG(1)`，**丢弃 INFO/WARN/ERROR**。
- **日志级证据**（`app.log`，257 行）：等级分布 **DEBUG 257 / 其余 0**；
  `rtc_config`、`pc_created`、`pc_ice_connection_state`、`pc_create_failed`、`video_track_missing`、`answer_create`、`offer_received`、`call_init` **命中全部为 0**。
  同期 `native.log` 的 INFO/WARN **正常**（如 `jni_call`/`nat_start`/`stun_socket_ready` 为 INFO）⇒ **两侧过滤方向不一致**。
- **修复**：`LogLevel.kt:53` → `this != OFF && level.code >= code`（阈值 N ⇒ 输出所有 `code >= N`；WARN/ERROR 恒输出；`OFF` 全关），与 native（doc/14 §6.6 数值口径）一致。
- **doc/14 勘误（doc/14 冻结，不直接改）**：
  - doc/14 §6.6（`doc/14-interface-contract.md:632-635`）只规定**等级数值**（0 VERBOSE…5 OFF）与"非法值按 0 处理并记 WARN"，
    **未**明文规定"阈值 = 输出本级及更严重"这一方向；实现中出现方向反转且与 native 不一致 ⇒ 正确口径 = **`level.code >= threshold.code`**，`OFF` 一律不输出。
  - doc/14 §9 打点字段本身无误（`capture frame buf=… rot=…` 等）；本轮问题在**过滤方向**，属实现缺陷 + 文档未显式写清阈值方向，建议在 §9/§6.6 增补一句"阈值语义 = 本级及更严重"。

---

## 5. 变更摘要与新增事件名登记

```
git diff --stat（本仓库，t44）
 .../log/LogLevel.kt                  |  13 +-
 .../ui/call/CallViewModel.kt         |  39 ++-
 .../webrtc/CallSession.kt            | 321 +++++++++++++++++++-
 .../webrtc/PeerConnectionObserverImpl.kt | 70 ++++-
 4 files changed, 429 insertions(+), 14 deletions(-)
新增文件：webrtc/IceCandidateInfo.kt(151) / test/log/LogLevelFilterTest.kt(76) / test/webrtc/IceCandidateInfoTest.kt(107)
```

**新增日志事件名（doc/14 §9.1 固定表之外，按任务要求在报告登记）**：

| 事件名 | 等级 | 作用 |
|---|---|---|
| `pc_starting` | INFO | 实际 ICE 配置（stun/turn）+ `force_relay` |
| `offer_created` / `offer_sent` | INFO | offer SDP 字节数与 SDP 内候选统计；已发送 |
| `answer_created` / `answer_sent` | INFO | answer SDP 字节数与候选统计；已发送 |
| `offer_deferred` / `answer_deferred` / `ice_deferred` | WARN | PC 未就绪，暂存（缺陷①的可诊断痕迹） |
| `offer_replayed` / `answer_replayed` / `ice_replayed` / `remote_replay_done` | INFO | 就绪后回放暂存信令 |
| `offer_dropped` / `answer_dropped` | ERROR | 会话已关闭导致丢弃（必报错） |
| `offer_without_session` / `answer_without_session` / `ice_without_session` | ERROR/WARN | ViewModel 层缺会话（缺陷①修复点） |
| `ice_candidate_local` / `ice_candidate_remote` | INFO | 每个候选的 `type/proto/addr/port` |
| `ice_gathering_complete` | INFO | 收集结束时的本端按类型计数 + relay 数 |
| `pc_connection_state` | INFO/ERROR | DTLS/传输状态（`state=` + `dtls=true/false`；CONNECTED=建链成功；FAILED=ERROR）。本版 org.webrtc 无独立 `dtls_state` 回调，DTLS 完成即传输 CONNECTED，故以该字段标注 |
| `selected_candidate_pair` | INFO | 选中候选对（本地/远端 type + 地址端口 + P2P/RELAY + reason） |
| `pc_ice_candidate_error` | WARN | STUN/TURN 候选层错误（url/addr/port/code/text） |
| `ice_watchdog_started` / `ice_not_connected` / `ice_timeout` / `ice_watchdog_ok` | INFO/WARN/ERROR/INFO | 超时兜底与用户可见上报 |

沿用既有名：`rtc_config`、`pc_created`、`pc_ice_connection_state`、`pc_ice_gathering_state`、`ice_dropped`。

---

## 6. 单测（纯 JVM，不依赖 native；容器内未执行 gradle，由宿主机构建任务运行）

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `app/src/test/kotlin/com/example/webrtcdemo/log/LogLevelFilterTest.kt` | 6 | 阈值方向：`DEBUG` 放行 DEBUG/INFO/WARN/ERROR 且不放行 VERBOSE；`INFO`/`VERBOSE`/`ERROR`/`OFF` 各档；数值口径 0..5 |
| `app/src/test/kotlin/com/example/webrtcdemo/webrtc/IceCandidateInfoTest.kt` | 8 | 候选解析（host/srflx/relay、`a=` 前缀、非法输入不抛异常）、计数器摘要、SDP 候选行数与类型分布 |

⇒ 预期宿主 `:app:testDebugUnitTest` 由基线 **46/0/0** 变为 **60/0/0**（新增 14 例）。**未在本容器执行**（无 JDK/SDK），如实登记。

---

## 7. 给用户的一次性复测清单（一次即可区分"host/srflx 路径"与"relay 路径"）

**准备**：两台设备都安装新 APK；两台都进「诊断」页把「连接策略」设为 **强制中继（RELAY）**；日志级别保持 **DEBUG**。

1. 设备 A 创建房间 → 记下 6 位会议号；设备 B 用会议号加入。
2. 观察 30 秒：
   - **连上** ⇒ 说明 P2P（host/srflx）路径不通、**relay 路径可用**；请**立即导出两台日志**。
   - **仍未连上** ⇒ 请**立即导出两台日志**（此时 `ice_timeout` 会带出本端/对端候选类型计数，可判定是"没 gather 到 relay"还是"gather 到了但检查失败"）。
3. 第二轮（可选，用于区分 Wi-Fi 与蜂窝）：两台都**只开 Wi-Fi**（关蜂窝数据）再按 1–2 复测一次；随后两台都**只开蜂窝**（关 Wi-Fi）再复测一次。
4. 每次复测请把**两台设备**的导出日志一起提供（诊断页「导出日志」），日志里现在必定包含：
   `pc_starting`（ICE 配置/force_relay）、`ice_candidate_local/remote`（类型/地址/端口）、`ice_gathering_complete`（本端计数）、
   `selected_candidate_pair`（若选中）、`pc_connection_state`（是否 DTLS 建链）、以及未连通时的 `ice_not_connected`/`ice_timeout`。

> 判定口径：`ice_timeout` 的 `local_candidates` 里若**没有 `relay=`** ⇒ 是 TURN 配置/分配问题；
> 若有 `relay=` 但仍超时 ⇒ 是**对端方向**可达性/端口策略问题（下一步再查对端候选与网络策略）。

---

## 8. 未验证项（**不得读作已通过**）

1. **真机连通性本身**：修复后两台真机是否真能接通（`pc_connection_state=CONNECTED`、首帧到达）—— 只能由用户按 §7 复测判定。
2. `selected_candidate_pair` 是否出现、选中路径是 P2P 还是 RELAY —— 需新日志（本容器无法产生）。
3. 缺陷①修复后 joiner 是否**必然**回 answer（含"offer 先于 PC 到达"竞态的实机路径）—— 逻辑上已保证（暂存+回放+报错），但未在真机复现验证。
4. TURN 分配是否在两台设备、两种网络下都成功（`ice_gathering_complete` 的 `relay=n`）—— 待复测。
5. 宿主 `:app:testDebugUnitTest` 新用例是否 60/0/0 —— 需宿主机构建任务执行。
