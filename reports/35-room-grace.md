# 35 — 信令服务房间宽限期（WS 瞬断不销毁房间、不立即 peerLeft、宽限期内可重连入会）

- 任务：**t67**（implementation，attempt 1，attempt_id `f4ec9d8f-0e6a-4316-8b3d-6091dbcf0cbd`）
- 执行人：go-dev
- 执行时间：2026-09-15 23:3x–23:4x（容器本地时间 CST，UTC+8）
- 结论：**通过**。WS 断开与「离开房间」已解耦：断开后席位进入**宽限期**（默认 **90 s**、可用 `-room-grace` 配置），期间房间不回收、席位保留、在线对端**不收** `peerLeft`；断开者用原 `roomId` 重连即**接管原席位（同一 peerId）**并回 `joined`，对端走既有 `peerJoined` 路径；宽限期满仍无重连才回收席位并对在线对端**只发一次** `peerLeft`。38 个用例（含 9 个新增）`go test ./... -count=2` 全绿，`go build`/`go vet` 通过，真实二进制活体验证通过。
- **wire 协议零改动**：未新增/未修改任何消息类型或必需字段（复用 `joined`/`peerJoined`/`peerLeft`），旧客户端 `create`/`join` 行为不变。

---

## 1. 根因证据链（用户 2026-09-15 真机报告）

两台设备日志合看，缺陷链条是「WS 瞬断 → 服务端立即判离线 → 对端挂断 → 房间销毁 → 重连方 ROOM_NOT_FOUND → 自动退出」，而**媒体当时是健康的**：

**dl-b（Mi 10 Pro，入会方，room=2EF9FM）**
```
15:27:45.779  ws_pong_timeout timeout_ms=5000          # 客户端 5s 未收到 pong → 判本端 WS 失败
15:27:45.781  ws_close reason=failure                   # 两个重连被排程
15:27:46.894  pc_ice_connection_state=DISCONNECTED      # 同一段瞬断（媒体也抖了一下）
15:27:48.782  ws_connecting → 15:27:49.132 ws_open
15:27:49.133  room_join_sent room=2EF9FM
              ← 没有 room_joined                          # 房间此时已被对端销毁
15:27:58.797  ws_close reason=failure
15:28:01.924  ws_open → 15:28:01.928 room_join_sent
15:28:02.248  server_error code=ROOM_NOT_FOUND detail=Room_2EF9FM_does_not_exist
15:28:02.248  ws_reconnect_suppressed → 本地 call_end + hangup   ⇒ 自动退出房间
```

**dl-a（Xiaomi 24117RK2CC，房主）**
```
15:27:45.096  stats_sample down_bps=2047967 up_bps=353401 mode=RELAY   # 媒体完全健康
15:27:46.615  peer_left peer=peer-002                                   # 收到 peerLeft
15:27:46.6xx  立即 call_end notice=通话已结束 + hangup                   ⇒ 房间被销毁
```

**旧代码定位（复核确认，t67 前）**
| 位置 | 旧行为 |
|---|---|
| `signaling/server/ws_handler.go:87`（t67 前）`s.manager.Detach(peer)` | WS 断开即从房间移除该 peer |
| `signaling/server/ws_handler.go:117-119`（t67 前）`if other != nil { s.sendPeerLeft(...) }` | 立即给对端发 `peerLeft` |
| `signaling/room/manager.go` `Detach`（t67 前） | 房间空了就销毁；对端若在线也会保留房间，但**对端已收到 peerLeft**（按 doc/09 §4 就会挂断） |
| `signaling/room/room.go` `AddPeer`（t67 前） | 无空席位即 `ROOM_FULL`；**没有"保留席位"概念**，掉线者的身份不可能恢复 |

⇒ 结论：**"WS 断开"与"离开房间"被混为一谈**。瞬断（移动网络抖动、客户端 pong 超时）被当成主动离开处理，而真机场景里双方都还在、媒体也还在。

---

## 2. 改动清单（file:line）

### 2.1 配置与入口（宽限期可配置，默认 ≥60 s）
| 位置 | 内容 |
|---|---|
| `signaling/config/config.go:54` | **`DefaultRoomGrace = 90 * time.Second`**（默认值，满足验收「≥60 s」） |
| `signaling/config/config.go:57` | `MinRecommendedRoomGrace = 60 * time.Second`（低于此值仅告警，见 §5.1 预算推导） |
| `signaling/config/config.go:71` | 配置字段 `RoomGrace time.Duration`（`<=0` = 关闭宽限期、退化为旧行为） |
| `signaling/config/config.go:89` | `Default()` 注入 `RoomGrace: DefaultRoomGrace` |
| `signaling/config/config.go:136` | `Validate()`：负值报错；`0` 合法（=关闭） |
| `signaling/config/config.go:157` | `Warnings()`：`0 < grace < 60s` 或 `grace<=0` 时给出明确告警 |
| `signaling/main.go:45` | 命令行参数 **`-room-grace`**（默认 90s） |
| `signaling/main.go:65` | `cfg.RoomGrace = *flagRoomGrace` |
| `signaling/main.go:95` | 启动日志字段 `room_grace_ms` |

### 2.2 房间与席位（核心）
| 位置 | 内容 |
|---|---|
| `signaling/room/room.go:26` | 新增 `seat{peer, grace *time.Timer, gen uint64}`：`grace != nil` 表示该席位处于宽限期；`gen` 为代次，用于**丢弃过期定时器回调**（重连接管后不会再触发回收） |
| `signaling/room/room.go:66` | `AddPeer(p, grace, onExpire)`：有空席位→正常入会；**无空席位但有宽限期席位→接管该席位**（取消定时器、`gen++`、按原 peerId 接纳），返回 `takenOver/prev` |
| `signaling/room/room.go:113` | `MarkPending(p, grace, onExpire)`：把席位标记为宽限期并起定时器（幂等，重复调用重置） |
| `signaling/room/room.go:140` | `ExpirePending(p, gen)`：**仅当席位仍由 p 占用、仍处于宽限期、代次匹配**时才回收（幂等 ⇒ 保证 peerLeft 只发一次） |
| `signaling/room/room.go:212` | `LivePeers()`：只返回有活动连接的 peer（房间过期通知用） |
| `signaling/room/room.go:243` / `:258` | `PendingCount()` / `IsPending(p)`：观测与"避免向已死对端写 peerLeft" |
| `signaling/room/manager.go:225` | **`MarkOffline(p)`**：新的断开入口。宽限期 > 0 时保留席位并返回 `immediate=false`；**宽限期 <= 0 时退化为旧行为**（立即移除、房间空则销毁、`immediate=true`） |
| `signaling/room/manager.go:258` | **`expireGrace(roomID, p, gen)`**：宽限期满的唯一出口——回收席位；房间空→销毁；否则回调 server 发**一次** `peerLeft`；对端若自身也在宽限期则跳过（`grace_expired_peer_also_pending`） |
| `signaling/room/manager.go:82` | `SetGraceExpiredHandler(fn)`：注册回调（锁外执行） |
| `signaling/room/manager.go:177-186` | `JoinRoom` 走接管路径；被接管旧 peer 的 `Room` 引用被清空（在释放房间锁之后调用） |
| `signaling/room/manager.go:184` | 计数 `seatTakeovers`；`:55` 新增 `graceExpired`；`:303` `StatsGrace()` 供 `/healthz` |
| `signaling/room/manager.go:191-203` | 显式 `leave` 语义**不变**（doc/09 §3.8：通知对端→关连接→销毁房间）；仅当对端自身也在宽限期时不发无用通知 |
| `signaling/room/manager.go:420` | 房间过期清理改用 `LivePeers()`（宽限期席位由各自定时器处理） |

### 2.3 服务端接线
| 位置 | 内容 |
|---|---|
| `signaling/server/ws_handler.go:87` | 断开路径由 `Detach` 改为 **`MarkOffline`**；`immediate=false`（宽限期）时**不给对端发 peerLeft**，只记 `room_peer_offline_grace` |
| `signaling/server/ws_handler.go:498` | **`handleGraceExpired`**：宽限期满唯一出口 → 记 `grace_expired_notify_peer_left` 并调用既有 `sendPeerLeft` |
| `signaling/server/ws_handler.go:516` | `sendPeerLeft` 增加「对端连接已关闭则跳过」保护（`peer_left_skip_closed_peer`） |
| `signaling/server/server.go:66` | 注册 `SetGraceExpiredHandler` |
| `signaling/server/server.go:117-119` | `/healthz` 新增 `roomGraceSec`、`seatTakeovers`、`graceExpired`（可观测） |

### 2.4 保持不变的语义（明确边界）
- **显式 `leave`**：仍是"通知对端 → 关闭连接 → 立即销毁房间"（doc/09 §3.8），不进入宽限期。
- **房间过期（30 分钟无人加入）**：逻辑不变；宽限期席位**仍算占位**，因此"一人离线等重连"的房间不会被过期清理误杀。
- **`ROOM_FULL`**：仅在"无空席位且无宽限期席位"时返回（即对端确实在线且房间满）。
- **`ROOM_NOT_FOUND`**：仍是房间已被销毁（宽限期满且无人接管、或双方显式离开）。

---

## 3. 单测清单（新增 9 个；验收四项逐条对应）

### 3.1 验收① 断开后宽限期内房间仍存在且席位保留
- `room.TestGraceSeatKeptDuringGrace`（`room/grace_test.go`）：
  `MarkOffline` 后断言 `Manager.Count()==1`、`Room.PeerCount()==2`（席位保留）、`PendingCount()==1`、`LivePeers()==1`、`OtherPeer(host)==joiner`（离线席位仍参与配对）、宽限期未满时回收回调 **0 次**。

### 3.2 验收② 宽限期内同 peer 重连 join 成功并回 `joined`
- `room.TestGraceReconnectTakesOverSeat`：重连 `JoinRoom` 返回**同一个 `peer-002`**、`PendingCount()==0`、旧 peer 脱离房间、新 peer 在房；**代次校验**：接管后即使等到宽限期之后，回收回调仍为 0 次。
- `server.TestE2E_GraceDisconnectKeepsRoomSeatAndSuppressesPeerLeft`：真实 WS 上，`joiner.close()` 后新连接 `join` 同 roomId → `joined.peerId == "peer-002"`，且 `host` 收到 `peerJoined(peer-002)`（服务端日志含 `seat_takeover`）。
- `server.TestE2E_StaleSeatTakeoverAfterReadTimeout`：**静默掉线**（TCP 未关、不收发）时——读超时前 `ROOM_FULL`（服务端尚不知对方已死）；读超时后（`room_peer_offline_grace`）新连接直接接管席位，对端收 `peerJoined` 而非 `peerLeft`。
- `server.TestE2E_BothDisconnectedRoomSurvivesGrace`：双方同时断开 → 宽限期内房间保留且可被接管入会。

### 3.3 验收③ 宽限期内在线对端未收到 `peerLeft`
- 同 `TestE2E_GraceDisconnectKeepsRoomSeatAndSuppressesPeerLeft`：用**有序探针**（`host` 发 `ping` → 下一条必须是 `pong`；若服务端发了 `peerLeft` 必然排在其前）证明"期间无 peerLeft"；并断言服务端日志中 `peer_left_sent` 出现 **0 次**（宽限期 3 s，之后再次等待 1.2 s 复检）。
- `server.TestE2E_OfflineGraceTimingAfterStaleReap`：接管后等到超过宽限期，仍无 `peer_left_sent`。
- ⚠️ 说明：不用「读超时断言无消息」，因为 gorilla 的读超时会**永久污染**该连接（`Errors returned from this method are permanent`），会使后续断言失真。

### 3.4 验收④ 宽限期满未重连 → 回收房间并**只**发一次 `peerLeft`
- `server.TestE2E_GraceExpiryReclaimsSeatAndNotifiesPeerLeftOnce`（宽限期 400 ms）：`peerLeft(peer-002)` 在宽限期之后才到达（实测时间 ≥ 0.8×grace）、`peer_left_sent` 日志**恰好 1 次**、再发 `ping` 收到的下一条是 `pong`（无重复 peerLeft）；在线对端仍在房时房间**保留**，其显式 `leave` 后房间销毁。
- `room.TestGraceExpiryRemovesSeatOnce`：`ExpirePending` 首次 `removed=true`，同代次再调用 `removed=false`（幂等 ⇒ 只通知一次）。
- `room.TestManagerGraceExpiryNotifiesOnce`：回调恰好 1 次且内容为 `(room, offline=joiner, other=host)`，之后不再触发；`StatsGrace() = (0, 1)`。

### 3.5 附带（不属四条验收，但保证兼容与可观测）
- `room.TestManagerMarkOfflineWithGraceDisabled`：`-room-grace 0` 时**退化为旧语义**（立即移除、房间空则销毁、返回对端供通知）。
- `room.TestConfigRoomGraceDefaults`：默认值 90 s ≥ 60 s；偏低值可启动但**有告警**；负值校验失败；`0` 合法且有告警。
- `server.TestLive_GraceReconnect`（`SIGNALING_WS_URL` 门控）：对**真实二进制**复现真机瞬断并验证修复（见 §6）。

### 3.6 因语义变更而**重写/替换**的既有用例（不是"删掉避免失败"，是行为契约变了）
| 原用例 | 处置 |
|---|---|
| `TestE2E_DisconnectNotifiesPeerAndAllowsReconnect` | 旧断言"断开即收 peerLeft"与新行为相反 → 拆成 `TestE2E_GraceDisconnectKeepsRoomSeatAndSuppressesPeerLeft` + `TestE2E_GraceExpiryReclaimsSeatAndNotifiesPeerLeftOnce`（两个方向都覆盖） |
| `TestE2E_ReconnectStaleSessionCausesRoomFull` | 保留"读超时前 ROOM_FULL"这一半，把"之后重试成功"改写到 `TestE2E_StaleSeatTakeoverAfterReadTimeout`（读超时后是**接管**而非等 peerLeft） |
| `TestE2E_ReconnectAfterBothDisconnectedIsRoomNotFound` | 旧结论"双方断开→房间立即销毁→ROOM_NOT_FOUND"已被新语义取代 → 改写为 `TestE2E_BothDisconnectedRoomSurvivesGrace`（宽限期内保留并可接管；宽限期满无人接管才销毁） |
| `TestE2E_ReconnectTimingAfterStaleReap` | 时序含义变了（对端不再在读超时时立即收 peerLeft）→ 改写为 `TestE2E_OfflineGraceTimingAfterStaleReap`（度量 读超时回收 → 宽限期内接管成功 → 接管后无 peerLeft） |

---

## 4. 验证证据

### 4.1 静态检查与回归
```
$ . /data/dsh/home/workspace/env-go.sh && cd code/webrtc-demo/signaling
$ go build ./... && go vet ./...          → 退出码 0（无输出）
$ go test ./... -count=2
?   	webrtcdemo-signaling	[no test files]
?   	webrtcdemo-signaling/config	[no test files]
?   	webrtcdemo-signaling/protocol	[no test files]
ok  	webrtcdemo-signaling/logging	0.015s
ok  	webrtcdemo-signaling/room	5.618s
ok  	webrtcdemo-signaling/server	17.094s
ok  	webrtcdemo-signaling/util	0.036s
```
用例总数 **38**（36 PASS + 2 个 `SIGNALING_WS_URL` 门控 SKIP；门控项在设置环境变量后已单独跑通，见 §6）。既有 `logging`/`room`/`server`/`util` 全部通过，无回归。

### 4.2 活体验证（真实二进制 + 真实 TCP，复现真机场景）
`-room-grace 3s` 启动真实二进制，客户端侧 `go test -run TestLive_GraceReconnect`：
```
[LIVE-HOST]   => {"type":"create"}                    <= created roomId=PPHZBE
[LIVE-JOINER] => {"type":"join","roomId":"PPHZBE"}     <= joined peerId=peer-002
[LIVE-HOST]   <= {"type":"peerJoined","peerId":"peer-002"}
              —— [LIVE-JOINER] 瞬断（不走 leave）——
[LIVE-HOST]   => {"type":"ping","timestamp":1}         <= {"type":"pong",...}      # 期间无 peerLeft
[LIVE-REJOIN] => {"type":"join","roomId":"PPHZBE"}     <= joined peerId=peer-002   # 同身份恢复
[LIVE-HOST]   <= {"type":"peerJoined","peerId":"peer-002"}                         # 走既有重协商路径
活体验证通过：房间 PPHZBE 瞬断后席位保留、同身份重连成功、宽限期内无 peerLeft
--- PASS: TestLive_GraceReconnect (0.00s)
```
服务端日志（同一次运行）：
```
room_created room=PPHZBE peer=peer-001 role=host
room_joined  room=PPHZBE peer=peer-002 peers=2
ws_close     peer=peer-002 reason=unexpected-close grace_ms=3000 seat_kept=true
room_peer_offline_grace grace_ms=3000 peer=peer-002 room=PPHZBE      # ← 旧行为在这里就发 peerLeft + 销毁房间
seat_takeover room=PPHZBE peer=peer-002 seat_takeover=true prev_remote=127.0.0.1:56928
```
原始证据文件：`signaling/logs/t67-grace-live-evidence.log`。

### 4.3 宽限期满的相反路径（单测层）
`TestE2E_GraceExpiryReclaimsSeatAndNotifiesPeerLeftOnce`（grace=400 ms）日志断言 `peer_left_sent` **恰好 1 次**、且 `peerLeft` 到达时间 ≥ 0.8×grace；`TestManagerGraceExpiryNotifiesOnce` 断言回调 1 次且 `StatsGrace()==(0,1)`。

---

## 5. 宽限期取值与客户端配合（验收③要求说明）

### 5.1 为什么默认 90 s（> 客户端重连预算）
宽限期必须覆盖「客户端从瞬断到重连入会」的最坏耗时。预算由三段构成：

| 段 | 依据 | 量级 |
|---|---|---|
| 服务端察觉静默掉线（读超时） | `-pong-wait` = `3 × protocol.PingInterval` = **45 s**（`config/config.go:38`）；实测：`TestE2E_OfflineGraceTimingAfterStaleReap` 中读超时回收 ≈ 801 ms@PongWait=800 ms，即**几乎精确等于 PongWait** | 45 s |
| 客户端本端判失败 | 客户端日志 `ws_pong_timeout timeout_ms=5000`（5 s）；客户端心跳 15 s 间隔（doc/09 §6） | 5–15 s |
| 客户端重连退避 | 真机日志：断开后 ~3 s 首次重连，失败后下一次 ~13 s（`15:27:45.781 → 15:27:48.782 → 15:27:58.797`） | ~13–30 s |

⇒ 最坏情况 ≈ 45 s（服务端察觉）+ 15 s（下一次重连）= **60 s**；取 **90 s** 留 50% 余量（且 90 s > 用户报告里 dl-b 两次重连尝试的 17 s，也覆盖"重连退避到第 2–3 次"的情形）。
`MinRecommendedRoomGrace = 60 s` 仅用于**告警**（低于它仍可启动，便于实验/联调）。

### 5.2 与客户端行为的分工（wire 协议不变）
- **服务端**：宽限期内保持房间与席位、**不发** `peerLeft`；断开者重连 `join` 时接管原席位（同 `peerId`）并回 `joined`；同时给在线对端发 `peerJoined`（**既有**消息），对端按既有 §3.3/D-6 口径（host 发 offer）重新协商。
- **客户端（android-dev 侧）**：`IN_CALL` 期间若**没有**收到 `peerLeft`，就继续通话（不应因本端 WS 抖动自行挂断）；收到 `joined`/`peerJoined` 时按既有路径重协商（必要时 ICE restart，属客户端已知限制）。
- 这样真机上"两端都在、媒体健康"的场景不再被打断；而真正的离开（用户挂断 / 宽限期满）仍然走原有 `peerLeft` + 挂断路径。

### 5.3 客户端重连预算与宽限期的对账（2026-09-15，android-dev 复核后）

android-dev 在收到 §5.2 的分工要求后复核了客户端实际预算，发现**一处其 t68 未覆盖的提前退房路径**，并给出如下口径（出处：其 `reports/36-call-survivability.md`）：

| 客户端机制 | 实际值（file:line 由 android-dev 提供） | 与 90 s 宽限期的关系 |
|---|---|---|
| socket 重连（`scheduleReconnect`） | `RECONNECT_DELAY_MS=3_000` × `MAX_RECONNECT_ATTEMPTS=3` ≈ **9–12 s**；3 次耗尽即 `cause=FATAL` → `setState(DISCONNECTED)` → `CallViewModel` 走 `endCallNow(NOTICE_CALL_ENDED)` | ⚠️ **仍会提前退房**：抖动 >12 s 时客户端在 90 s 宽限期内主动结束通话（媒体很可能仍在流） |
| `ROOM_FULL` 重入退避 | `1/2/4/8 s` 上限、**10 次封顶 ≈ 63 s**（`SignalingClient.kt:133-139`） | ✅ 63 s < 90 s；按 t67 语义只在"对端确实在线且真满"的短窗口用到 |
| 媒体存活标记 | `ConnStatus.mediaAlive`（t68 新增） | 建议作为"是否允许结束通话"的判据 |

**go-dev 给出的服务端口径与建议预算**（与真机证据、宽限期取值一致）：

1. **预算上限 = `RoomGrace`(90 s) − 安全余量**。注意宽限期**计时起点是"服务端察觉断开"**，因此分两种情形：
   - **客户端显式关闭旧 socket**（真机 dl-b 即如此：`ws_pong_timeout` → `ws_close reason=failure`）→ 服务端**立刻**标记席位 pending → 预算可用满 90 s；建议 **≤ 75 s**（留 15 s 余量）。
   - **客户端放弃旧 socket 而不关闭**（静默掉线）→ 服务端要等 `-pong-wait`（默认 **45 s**）才察觉 → 此时可用预算 = 90 − 45 = **45 s**。
   ⇒ **建议：socket 重连退避沿用 `1/2/4/8 s（上限 8 s）`、10 次封顶（总上限 ≈63 s）**，仍 < 90 s；并在每次重连前**显式关闭旧 socket**，把服务端察觉延迟压到 ~0，使"45 s 静默窗口"不再叠加进客户端预算。
   - **✅ 该前提已由 android-dev 逐行复核确认成立**（其 `signaling/SignalingClient.kt` 四条路径都会关闭/置空旧 socket）：`onClosed` → `webSocket = null`（:354）→ `scheduleReconnect("closed")`（:356）；`onFailure` → `webSocket = null`（:366）→ `scheduleReconnect("failure")`（:369）；pong 超时 → `webSocket?.cancel()`（:695）+ `webSocket = null`（:696）→ `scheduleReconnect("pong_timeout")`（:698）；`onClosing` 显式 `close(WS_CLOSE_NORMAL=1000)`（:347-350）。
     ⇒ **不存在"放弃旧 socket 而不关闭"的路径**，服务端察觉延迟 ≈0，故**上表第 2 种（45 s 叠加）情形对本客户端不适用**，63 s 预算在该前提下安全（余量 ≈27 s）。
2. **更关键的一条（android-dev 方案②，go-dev 明确背书）**：socket 重连**耗尽**时，若 `mediaAlive == true`，**不要** `endCallNow`，改为可恢复面板（保留通话页 + 显式「重新创建房间」/手动挂断）；只有媒体也不存活才结束。理由：服务端此刻仍保留席位、对端也未收到 `peerLeft` —— 客户端单方面"判死"是本次真机缺陷的**最后一环**，必须与服务端修复形成"双保险"。
3. **需要更长窗口时的部署侧旋钮**（不需改代码）：
   - 提高宽限期：`-room-grace 120s`（`config/config.go:71` 支持；>60 s 不触发告警）；
   - 缩短静默掉线察觉：`-pong-wait 30s`（当前 45 s = 3×15 s 心跳；调小更易在弱网误判，需权衡）。
   - 我未改默认值：90 s / 45 s 结合显式关闭前提，对应客户端预算 **≤75 s**，> 现行的 63 s。

4. **三方口径已对齐（2026-09-15）**：客户端（android-dev）与 captain 的 **t71** 契约均采用 **`1/2/4/8 s（上限 8）、10 次封顶 ≈63 s`**，要求"复用既有 `rejoinDelayMs` 口径、总预算 ≥60 s 且 <90 s、不造平行常量"；本条（服务端 `RoomGrace` 默认 90 s）与之相容：`63 s < 90 s` 且余量 ≈27 s。客户端侧的诊断字段与 ICE restart 接线（`restart_ice reason=rejoin`）属 t71 范围，其报告 `reports/39-reconnect-budget-ice-restart.md` 将交叉引用本节。

> 第 1、2 条属**客户端侧待办**（android-dev 已报 captain；t69 构建窗口被 env-installer 认领期间不改 `app/src`）。**服务端无需任何改动**——本节数值都以 t67 已交付行为为前提（席位保留 `RoomGrace`；`ROOM_FULL` 可重试，且会随席位进入宽限期自动转为"接管成功"）。

---

## 6. 与 doc/14 冻结契约的关系（errata 登记，未改 doc/14）

**本轮未修改 `doc/14-interface-contract.md`（任何字节）**——`git status --porcelain doc/14-interface-contract.md` 无输出；也未新增/修改任何 wire 消息类型与必需字段。登记 errata 时以**磁盘实测**为准：

```
2026-09-15T23:43:07+08:00  sha256sum doc/14-interface-contract.md
b3b6743825eababc51d41944d61d0f4ab542c8a0f3cdfc4d7a754cefd1cc0f4d  doc/14-interface-contract.md   (1337 行)
```

服务端**行为语义**有两处变化，按契约 §0 流程在此登记勘误（**不直接改 doc/14**）：

| # | 变化 | 涉及契约/文档条文 | 处置与依据 |
|---|---|---|---|
| E-1 | **服务端发 `peerLeft` 的时机推迟**：WS 断开不再立即发，而是宽限期内不发、宽限期满（默认 90 s）才发一次 | `doc/09` §3.9（peerLeft）、`doc/14` §8.3-3（"2 人都离开后立即销毁"）、§8.5 + **D-7** | **注意分层**：D-7 / 口径 B 规定的是**客户端收到 `peerLeft` 之后**的行为（→ 立即 `DISCONNECTED`/挂断），该行为**本轮不变**；t67 改的是**服务端何时发** `peerLeft`。因此对端在瞬断期间不再被误判为"对端离开"，而真正的离开仍按原语义通知。`peerLeft` 的消息结构与语义均未变 |
| E-2 | **`ROOM_FULL` 触发条件收窄**：仅当"无空席位且无宽限期席位"时返回；若满员席位中含处于宽限期的席位，新连接会**接管**该席位并成功入会 | `doc/14` §8.4-1（`ROOM_FULL` = transient）、`doc/09` §7（容量 2） | 与 §8.4-1 的"暂时性"语义**方向一致**（出现更少，且总能被重连化解）；`ROOM_FULL` 仍是合法响应，客户端"有界重试"策略无需调整 |

**对既有结论的影响（供 verifier 注意）**：`doc/14` v1.0-m 曾记录"双方同时重连不可达 / 房间一空即销毁"这一论断；t67 之后该前提改变——**双方断开后房间在宽限期内依然存在**（`TestE2E_BothDisconnectedRoomSurvivesGrace`），"任一连接可接管席位"。但**"不会出现双方互等死锁"的结论仍成立**，只是兜底机制从"房间一空即销毁"变为"宽限期满发 `peerLeft` → survivor 干净结束"。若 architect 认为需在契约/报告中同步这一条，建议按 §0 走一次登记（本任务不直接改 doc/14）。

---

## 7. 未验证项 / 已知限制

1. **单测未覆盖"宽限期内的实时媒体行为"**：服务端不接触媒体。宽限期内重连后是否需要 **ICE restart** 才能恢复画面，属客户端（`org.webrtc`）范畴，本轮**无真机可验证**（容器无 JDK/设备）。若联调出现"信令已恢复、画面黑"，按既有 D-7/A 增强项（需与 ICE restart 一起做）处理。
2. **席位接管不校验身份**：协议层没有客户端身份，任何知道 `roomId` 的连接都能**接管**处于宽限期的席位（拿到原 peerId）。这与 t67 之前"房间码是唯一秘密"的性质一致，未引入新的暴露面，但**不是**身份认证；如需强绑定需扩协议（未做）。
3. **宽限期 > 房间过期时间时的组合未做端到端实测**：房间过期判定把宽限期席位算作占位（`IsExpired` 不变），因此"一人离线等重连"不会被 30 分钟过期清理误杀；但"宽限期（90 s）与 `-room-expiry` 很小（如 3 s）"的极端组合未专门实测（逻辑上仍安全：过期只清"未满员 **且** 距最后活动超时"的房间，而宽限期席位互相配对使 `peerCount==2`）。
4. **客户端侧配合未验证**：android-dev 客户端是否会在"WS 抖动但未收到 peerLeft"时保持通话，需真机回归；本轮只保证**服务端不再提前发 peerLeft**。
5. **活体验证用的是 `-room-grace 3s`**（缩短以便测试），默认 90 s 的端到端行为未在真机上跑过（真机回归属 t69/联调窗口）。
6. **`/healthz` 新增字段未写入任何契约**（`roomGraceSec`/`seatTakeovers`/`graceExpired`）：属诊断端点扩展，与既有 `healthz` 字段同性质。

---

## 8. 复现命令

```sh
. /data/dsh/home/workspace/env-go.sh
cd /data/dsh/home/workspace/code/webrtc-demo/signaling

# 1) 静态检查 + 全量回归（38 例）
go build ./... && go vet ./...
go test ./... -count=2

# 2) 只看宽限期相关用例（秒级完成）
go test ./room -run 'TestGrace|TestManagerGrace|TestManagerMarkOffline|TestConfigRoomGrace' -v
go test ./server -run 'TestE2E_Grace|TestE2E_StaleSeatTakeover|TestE2E_BothDisconnected|TestE2E_OfflineGraceTiming' -v

# 3) 活体验证（真实二进制；宽限期缩短到 3s 便于观察）
go build -trimpath -ldflags "-s -w" -o /tmp/signaling-grace .
/tmp/signaling-grace -addr 127.0.0.1:18447 -stun stun:127.0.0.1:3478 \
  -turn 'turn:127.0.0.1:3478?transport=udp' -user demo:demopass \
  -room-grace 3s -log logs/signaling-grace-live.log -log-level debug &
SIGNALING_WS_URL=ws://127.0.0.1:18447/ws go test ./server -run TestLive_GraceReconnect -v -count=1
kill -TERM %1

# 4) 生产默认值自检
./signaling -version ; ./signaling -room-grace 0 ...   # 0=关闭宽限期（旧行为），启动时会告警
```

---

## 9. 交付物变更（workspace 相对路径）

```
code/webrtc-demo/signaling/config/config.go          （+RoomGrace/默认值/校验/告警）
code/webrtc-demo/signaling/main.go                   （+ -room-grace 参数与启动日志字段）
code/webrtc-demo/signaling/room/room.go              （席位化 + 宽限期 + 接管 + 代次）
code/webrtc-demo/signaling/room/manager.go           （MarkOffline/expireGrace/接管/统计）
code/webrtc-demo/signaling/room/grace_test.go        （新增：6 个 room 层用例）
code/webrtc-demo/signaling/server/ws_handler.go      （断开走宽限期 + handleGraceExpired + 保护）
code/webrtc-demo/signaling/server/server.go          （注册回调 + healthz 字段）
code/webrtc-demo/signaling/server/grace_test.go      （新增：4 个 E2E/活体用例）
code/webrtc-demo/signaling/server/e2e_test.go        （移除 4 个旧语义用例 + 1 个小工具）
code/webrtc-demo/reports/35-room-grace.md            （本报告）
```
> 未触碰：`code/webrtc-demo/doc/14-interface-contract.md`、`code/webrtc-demo/app/**`、`code/webrtc-demo/third_party/**`；本任务期间未执行任何宿主机 Gradle 构建。
