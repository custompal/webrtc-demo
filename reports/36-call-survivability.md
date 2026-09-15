# t68 通话可存活化报告：pong 容忍 + peerLeft/ROOM_NOT_FOUND/信令丢失 不自动挂断退房 + 媒体存活期不报 ICE 失败（含 §9 服务端宽限期对齐收尾）

- 任务：t68（implementation r1，attempt 1，attempt_id `e9728c05-5a51-4098-a5b7-be0fe0099972`）
- 承办：android-dev
- 依据：doc/14（权威）、doc/09（信令冻结）、captain 2026-09-15 对 t64 报文 U4 的口径裁定
- 范围：`app/src/main/kotlin/com/example/webrtcdemo/signaling/`、`app/src/main/kotlin/com/example/webrtcdemo/ui/call/`、`app/src/test/kotlin/`、本报告
- 明确**未触碰**：`app/src/main/cpp/**`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/**`（见 §8.2）
- 本任务**未执行**任何宿主机 Gradle 构建/发布（构建窗口属 t69）；离线预检方式见 §5

---

## 0. 摘要（TL;DR）

用户报告：「明明通话成功但 UI 还是显示 ICE 失败，视频流没断，过了一会自动退出房间了」。三条根因**全部**有真机日志与代码 file:line：

| # | 根因（修复前） | 后果（真机） | 修复落点 |
|---|---|---|---|
| ① | `PONG_TIMEOUT_MS=5_000L` 单次丢失即判断线（`SignalingClient.kt:82`、存活检查 :509-516） | dl-b 15:27:45.779 `ws_pong_timeout` ⇒ 重连 ⇒ 房间被回收 | `PONG_MISS_TOLERANCE=4`（有效阈值 20 s）：单次只记 `pong_miss` 并重发 ping，连续 4 次才断线 |
| ② | `peerLeft` / 重连期 `ROOM_NOT_FOUND` 把状态推到 `DISCONNECTED`，通话页 `onStateChanged(DISCONNECTED)` **无条件** `endCallNow()` → `hangup()` | dl-a:7517-7520（`down_bps=2047967` 仍在流）与 dl-b:9717-9720（画面仍在更新）**双双被踢回首页** | 新增 `DisconnectCause`（`survivable` 标志）+ 事件级判定 `CallSurvivability`；peerLeft ⇒ 回 `waiting_peer` 保留房间；ROOM_NOT_FOUND ⇒ **可恢复态 + 显式「重新创建房间」**，不自动退出 |
| ③ | `ice_down` 驱动的失败文案/失败卡 | dl-b 15:27:54.733 `phase=connecting reason=connection_lost media_age_ms=2705 pair=true`（15:27:55.900 又自行回到 `connected`） | 新增 `ConnStatus.mediaAlive` / `iceFailureSuppressed` / `failureCardVisible` / 中性提示；跟踪器在中断后**宽限期**内保持"媒体可能仍在"，且中断状态改记**真实帧龄** |

**离线预检结果（宿主机、kotlinc-embeddable 2.0.21 直接编译 + JUnit4 运行，未跑 Gradle）**：
整模块主源码编译 `MAIN_RC=0`、测试源码编译 `TEST_RC=0`、**18 个测试类 / 173 个用例全绿 OK (173 tests)**（基线 132 ⇒ 新增 41；含 §9 收尾：与服务端 90 s 宽限期对齐）。
old-red/new-green 两组对照均取得（§5.3）：本修复语义回退后同一份测试代码**必红**。

---

## 1. 真机根因时间线（原始日志）

日志来源：`/data/dsh/home/workspace/tmp/dl-b/x/app.log`（Mi 10 Pro，入会方）、`tmp/dl-a/x/app.log`（房主）。行号即该文件行号。

### 1.1 dl-b（入会方）——「pong 单次丢失 → 房间回收 → 自动退房」

```
9159  2026-09-15T15:27:45.779Z WARN  kotlin signaling [17430/17480] ws_pong_timeout timeout_ms=5000
9160  2026-09-15T15:27:45.781Z WARN  kotlin signaling [17430/17480] ws_reconnect_scheduled attempt=1 reason=pong_timeout
9161  2026-09-15T15:27:45.781Z WARN  kotlin signaling [17430/17479] ws_close code=- reason=failure
9162  2026-09-15T15:27:45.781Z WARN  kotlin signaling [17430/17479] ws_reconnect_scheduled attempt=2 reason=failure
9163  2026-09-15T15:27:45.781Z INFO  kotlin signaling [17430/17480] state_change from=IN_ROOM to=CONNECTING
9216  2026-09-15T15:27:46.895Z WARN  kotlin pc        [17430/17490] ice_flap_suppressed age_ms=32 detail=DISCONNECTED media_source=sink seq=1 session=s1
9243  2026-09-15T15:27:47.722Z INFO  kotlin pc        [17430/17430] remote_frame_liveness age_ms=701 down_bps=326398 frames=1707 phase=connected … source=sink view=true   ← 媒体确实在流
9408  2026-09-15T15:27:52.719Z WARN  kotlin pc        [17430/17490] ice_flap_suppressed age_ms=2705 detail=transport=DISCONNECTED media_source=down_bps seq=1 session=s1
9471  2026-09-15T15:27:54.733Z INFO  kotlin pc        [17430/17430] ui_conn_state … ice_down=true media_age_ms=2705 media_source=down_bps pair=true phase=connecting reason=connection_lost stalled=true   ← 根因③：媒体存活期出现失败态
9509  2026-09-15T15:27:55.900Z INFO  kotlin pc        [17430/17490] ui_conn_state … phase=connected reason=none stalled=false                                  ← 1.2 s 后自行恢复（证明③的报错是误报）
9600  2026-09-15T15:27:58.797Z INFO  kotlin signaling [17430/17632] state_change from=WAITING to=CONNECTING
9662  2026-09-15T15:28:00.783Z WARN  kotlin signaling [17430/17480] ws_pong_timeout timeout_ms=5000                                                              ← 又一次单次丢失即判断线
9663  2026-09-15T15:28:00.783Z WARN  kotlin signaling [17430/17480] ws_reconnect_scheduled attempt=2 reason=pong_timeout
9701  2026-09-15T15:28:01.924Z INFO  kotlin signaling [17430/17632] state_change from=CONNECTING to=CONNECTED
9703  2026-09-15T15:28:01.928Z INFO  kotlin signaling [17430/17632] room_join_sent room=2EF9FM                                                                 ← 房间已被服务端回收
9704  2026-09-15T15:28:01.928Z INFO  kotlin signaling [17430/17632] state_change from=CONNECTED to=WAITING
9715  2026-09-15T15:28:02.247Z ERROR kotlin signaling [17430/17632] server_error code=ROOM_NOT_FOUND detail=Room_2EF9FM_does_not_exist
9717  2026-09-15T15:28:02.248Z WARN  kotlin signaling [17430/17632] ws_reconnect_suppressed code=ROOM_NOT_FOUND
9718  2026-09-15T15:28:02.248Z INFO  kotlin signaling [17430/17632] state_change from=WAITING to=DISCONNECTED
9719  2026-09-15T15:28:02.248Z WARN  kotlin pc        [17430/17632] call_end notice=通话已结束，可重新创建                                          ← **自动退出通话页**
9720  2026-09-15T15:28:02.249Z INFO  kotlin pc        [17430/17632] hangup seq=1 session=s1
9721  2026-09-15T15:28:02.249Z INFO  kotlin pc        [17430/17632] session_teardown reason=hangup session=s1
9730  2026-09-15T15:28:02.580Z ERROR kotlin pc        [17430/17632] server_error code=ROOM_NOT_FOUND                                               ← 通话页的消息分支迟 333 ms 才跑到
```

### 1.2 dl-a（房主）——「对端离开即被踢回首页」

```
7516  2026-09-15T15:27:46.615Z INFO kotlin signaling [20727/28325] peer_left peer=peer-002
7517  2026-09-15T15:27:46.615Z INFO kotlin signaling [20727/28325] state_change from=IN_CALL to=DISCONNECTED
7518  2026-09-15T15:27:46.615Z WARN kotlin pc        [20727/28325] call_end notice=通话已结束，可重新创建     ← **自动退出通话页**
7519  2026-09-15T15:27:46.616Z INFO kotlin pc        [20727/28325] hangup seq=1 session=s1
7520  2026-09-15T15:27:46.616Z INFO kotlin pc        [20727/28325] session_teardown reason=hangup session=s1
7530  2026-09-15T15:27:46.844Z INFO kotlin pc        [20727/28325] peer_left                            ← 通话页的 peerLeft 分支迟 228 ms 才跑到（会话已拆）
7531  2026-09-15T15:27:46.844Z INFO kotlin pc        [20727/28325] ui_conn_state … phase=waiting_peer …     ← 在一个已经结束的通话上发布等待态
7532  2026-09-15T15:27:46.845Z INFO kotlin pc        [20727/28325] retry_clock_reset reason=peer_left role=host seq=1 session=s1
```
同期该端媒体健康：`stats_sample … down_bps=2047967 up_bps=353401 mode=RELAY`；dl-b 同时刻 `down_bps=326398`。

### 1.3 决定性发现：自动挂断来自 `onStateChanged(DISCONNECTED)`，不是消息分支

`SignalingClient` 的派发顺序是 `logIncoming → advanceStateOnIncoming(setState) → SignalingIdentity.update → listener.onMessage`。
`advanceStateOnIncoming` 里 `PeerLeft` 与 `TERMINAL_SUPPRESS` 两处都会 `setState(DISCONNECTED)`（修复前 `SignalingClient.kt:490-493` / `:471-487`），
`setState` 同步回调 `listener.onStateChanged`，而通话页修复前**无条件**：

```kotlin
if (state == ConnectionState.DISCONNECTED && !callEnded && sessionReady) endCallNow(NOTICE_CALL_ENDED)
```

⇒ 退出通话页发生在**消息回调之前**（dl-a 早 228 ms、dl-b 早 333 ms）。
因此只改 `CallViewModel.onMessage` 的两个分支**不足以**修复②；必须让"这次 DISCONNECTED 的来源"可被上层识别（见 §2.2）。
这也是本轮定因时最容易走偏的地方，故在此单列。

---

## 2. 实现（三条口径）

### 2.1 ① pong 超时容忍（有效阈值 ≥20 s）

`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt`

| 内容 | file:line |
|---|---|
| `PONG_TIMEOUT_MS = 5_000L`（单窗口超时，不变） | :82 |
| `PONG_MISS_TOLERANCE = 4`（容忍的**连续**丢失窗口数） | :91 |
| `PONG_FAIL_AFTER_MS = PONG_TIMEOUT_MS * PONG_MISS_TOLERANCE`（= 20 000 ms，验收要求 ≥20 s） | :94 |
| 纯函数 `pongMissed(nowMs, pingSentAtMs, lastPongAtMs, timeoutMs)` | :104-109 |
| 纯函数 `pongTimeoutReached(consecutiveMisses, tolerance)` | :117-118 |
| `@Volatile consecutivePongMisses`（收到任何 pong 即归零） | :304 / :307（`onMessage` 的 `Pong` 分支） |
| 存活检查：单次丢失 ⇒ `pong_miss` + **重发 ping 推进窗口**；仅达容忍数才 `ws_pong_timeout` + `cancel()` + `onTransportFailure("pong_timeout")` + `scheduleReconnect` | :667-705（`pong_miss` 落盘 :676-683、断线 :684-698、容忍期重发 :699-703） |

有效阈值推导：健康时 pong 立即返回（`lastPongAtMs > pingSentAtMs` ⇒ 不算丢）；丢包时存活检查（每 5 s）在第 1/2/3 次超时后各重发一个 ping 把窗口推进一段，
第 4 次（≈20 s）才断线 ⇒ `PONG_FAIL_AFTER_MS = 20 s`，与验收"≥20 s 或连续未响应计数"一致（两者都实现：阈值 20 s + 计数 4）。
注意「连续」是**收到 pong 即清零**的语义（`consecutivePongMisses = 0`，:250）。

### 2.2 ② peerLeft / ROOM_NOT_FOUND 不自动挂断退出

**(a) 让"掉线来源"可判定** —— `signaling/SignalingClient.kt`

| 内容 | file:line |
|---|---|
| 新增 `enum class DisconnectCause(val survivable: Boolean)`：`FATAL(false)` / `PEER_LEFT(true)` / `ROOM_LOST(true)` / `SIGNAL_LOST(true)`（§9.1） | :830-849 |
| `@Volatile disconnectCause` + `val lastDisconnectCause` | :274-278 |
| `PeerLeft` 分支先置 `PEER_LEFT` 再 `setState(DISCONNECTED)`（并落 `disconnect_cause`） | :593-600 |
| `TERMINAL_SUPPRESS` 分支：`rejoinAfterDrop && isRoomLossCode(code)` ⇒ `ROOM_LOST`，否则 `FATAL`（落 `disconnect_cause`） | :555-580 |
| 重连耗尽 / rejoin 重试耗尽（§9.1 后改为 `SIGNAL_LOST`）/ `leave()` / `shutdown()` 均显式赋值（防陈旧值） | :739 / :784 / :462 / :474 |
| `val isRejoinContext`（"曾在房内、掉线后重连"语境，供通话页判定） | :286-287 |

> 设计取舍：**没有改动 doc/09 §4 的状态机迁移名**（`peerLeft → DISCONNECTED` 保持原样），只把"来源"作为显式信号暴露给上层 ——
> 协议冻结不被破坏，而通话页不再需要"按状态名猜语义"。

**(b) 纯判定** —— `ui/call/CallSurvivability.kt`（新文件，114 行，零 Android 依赖）

| 内容 | file:line |
|---|---|
| `MEDIA_ALIVE_MS = 3_000L`（帧新鲜阈值，验收口径） | :55 |
| `mediaAlive(remoteFrameAgeMs, hasSelectedPair)` = `hasSelectedPair \|\| age ∈ [0, 3000)` | :63-64 |
| `peerLeftAction(everConnected, mediaAlive)` ⇒ 曾连上过 **或** 有媒体证据 = `KEEP_CALL` | :76-77 |
| `roomLostAction(rejoinContext, mediaAlive)` ⇒ 重连语境 **或** 媒体存活 = `KEEP_CALL` | :88-89（§9.1 新增 `signalLostAction` :105-106） |
| `shouldSurfaceError(message, mediaAlive)`：媒体存活时抑制含 `ICE`/`未连通`/`中继` 的文案 | :118-123 |
| `data class RecoverableState(reason, mediaAlive, notice)` | :136-140 |

与 doc/14 §8.5「口径 B（peerLeft ⇒ 立即挂断）」的关系：captain 2026-09-15 裁定**本世代曾连上过**时不再挂断，
本文件 KDoc :12-14 已把该裁定与"首次入房即失败仍按原语义结束"写清（口径变更**已在报告单列**，见 §7.2）。

**(c) 信令错误码策略** —— `signaling/SignalingClient.kt` 的 `SignalingErrorPolicy`

| 内容 | file:line |
|---|---|
| `val ROOM_LOSS_CODES = setOf("ROOM_NOT_FOUND", "ROOM_EXPIRED")`（只含"房间被回收"） | :934 |
| `isRoomLossCode(code)` | :946 |
| `keepsCallOnRoomLoss(code, rejoinContext, mediaAlive)` = `isRoomLossCode(code) && (rejoinContext \|\| mediaAlive)` | :959-960 |
| `endsCall(code)` **保持不变**（首次入房语义：找不到房间 ⇒ 结束） | :926 |

> 为什么不把 `RoomLostAction` 返回类型放进 `signaling`：`signaling` 层当前**零** `ui.*` 依赖（分层纪律），
> 故把"哪些码属于房间丢失"留在信令层、把"保留/结束"的动作判定留在 UI 层 `CallSurvivability`，由通话页组合。
> 中间稿曾有过 `SignalingErrorPolicy.survivabilityFor(...): RoomLostAction`，因引入 `signaling → ui.call` 反向依赖已删除（**终稿不存在该 API**）。
> 另注：`INVALID_MESSAGE`/`NOT_IN_ROOM` 仍在 `TERMINAL_CODES`（`endsCall` 语义不变），但**不**在 `ROOM_LOSS_CODES`，
> 因此不会被"可恢复化"（否则用户会在一个永远失败的房间上反复点重建）。

**(d) 通话页接线** —— `ui/call/CallViewModel.kt`

| 内容 | file:line |
|---|---|
| `_recoverable` / `val recoverable`（新 StateFlow；`model/CallUiState` 不在 inScope，故不改既有 UI 模型） | :201 / :204 |
| `onStateChanged(DISCONNECTED)`：来源 `survivable` ⇒ 只落 `disconnect_deferred` 并**推迟**给消息分支；否则才 `endCallNow` | :713-740（`disconnect_deferred` :723-734） |
| `PeerLeft` 分支：`peerLeftAction` = `KEEP_CALL` ⇒ 落 `peer_left action=keep_call` + 连接文案回到 `IN_ROOM`（不 hangup） | :802-832（日志 :809-817） |
| `ServerError` 分支：`keepsCallOnRoomLoss` ⇒ 落 `room_not_found action=keep_call` + `_recoverable` + 可恢复文案（不 hangup） | :939-980（日志 :944-958、可恢复态 :960-976） |
| `mediaAliveForUi()`（取 `ConnStatus.mediaAlive` 权威快照） | :1526 |
| `inRoomContext()`（`isRejoinContext` / 曾 CONNECTED / 对端出现过 / 已选中候选对） | :1539-1544 |
| `recreateRoom()`（显式入口：`client.createRoom()`，真正的换代在收到 `created` 后） | :648-681 |
| `Created` 分支：缓存新 ICE + 更新房间号 + `beginGeneration(..., REASON_RECREATE)` | :743-773 |
| 新一代重置 `_recoverable`/`recreatePending`；`REASON_RECREATE ⇒ onWaitingPeer`（新房间是空房间，不计时） | :445-448 / :423 |
| `hangup()` 清 `_recoverable` | :590 |

**(e) 通话页 UI** —— `ui/call/CallScreen.kt`

| 内容 | file:line |
|---|---|
| `recoverable` 收集；显示用房间号 `displayRoomId = state.roomId.ifBlank { roomId }`（重建后房间号会变） | :105 / :114（使用 :122 / :332 / :344 / :494） |
| 分区优先级：`recoverable != null` ⇒ 可恢复面板（提示 + 「重新创建房间」按钮）；否则 `conn.failureCardVisible` ⇒ 失败卡；否则 `conn.recoveryBannerVisible` ⇒ 中性提示；否则原"等待对端画面"提示 | :455-540（面板 :463-495、按钮 :489-493、中性提示 :525-537） |
| 文案常量：`RECREATE_ROOM_LABEL="重新创建房间"`、可恢复副标题两条 | :559 / :562 / :565 |

「结束通话」入口：通话页既有的挂断 `IconButton`（`CallScreen.kt:438`）始终可用，满足 captain "必要时提供显式结束入口"。

### 2.3 ③ 媒体存活期间不显示 ICE 失败

`ui/call/ConnectionStatus.kt`

| 内容 | file:line |
|---|---|
| `ConnStatus.mediaAlive`（新字段，默认 false） | :150（KDoc :139-149） |
| `iceFailureSuppressed = mediaAlive && phase != CONNECTED` | :163-164 |
| `failureCardVisible = showOverlay && !iceFailureSuppressed` | :167-168 |
| `recoveryBannerVisible = iceFailureSuppressed && !remoteFrameReady` | :171-172 |
| `recoveryBannerText = "画面恢复中…（网络抖动，请稍候）"`（**不含** ICE/失败/重连/中断） | :180-182 |
| 跟踪器 latch `mediaAlive`（真源） | :419-426 |
| `onMediaFrame` ⇒ `mediaAlive = true`；`markConnected`（帧/候选对/传输）⇒ true | :579 / :802-817 |
| 中断后**宽限期**内 `markLost` ⇒ `mediaAlive = status.hasSelectedPair`（曾选中候选对 ⇒ 抑制失败文案）；宽限期用尽（`FAILED`）⇒ `mediaAlive = false`（失败卡与「点击重试」照常） | :634-651（latch :641）/ :722-742 / :756-767 |
| `mediaAgeOf(nowMs)`：中断状态写入**真实帧龄**（不沿用滞后的 `mediaAgeMs` 字段） | :620-621（使用 :611 / :698） |
| 诊断落盘（限频一次）：`ice_down_ui_suppressed` | `CallViewModel.kt:1296-1314`（`onError` 文案闸门同名诊断 :1108-1131） |

**为什么"selected pair"不是永久抑制**：`ConnStatus.hasSelectedPair` 是"曾经选中过"（不随中断复位）。
若把它当作永久抑制条件，那么一次真实断流后将**永远**不显示失败卡与「点击重试」，直接破坏 t59/t60 验收（"失败 ⇒ 可操作提示 + 一键重试"）。
因此本实现的语义是：**中断后先进入宽限期（`lostGraceMs = 8 s`）并保持 `mediaAlive = true`（候选对仍在）⇒ 该窗口内绝不显示失败文案、绝不进入 `FAILED`；
窗口内任何恢复（帧重新新鲜 / 传输回到 CONNECTED）即回 `CONNECTED`；只有"宽限期用尽且仍无任何恢复"才 `FAILED` 并如实呈现失败卡。**
即"帧新鲜 <3 s 或存在 selected pair ⇒ 不进入 FAILED"在**宽限期**这一层成立，且不会把真实断流永久伪装成正常。

**帧龄诚实性**：dl-b `media_age_ms=2705` 是上一次 `onMediaFrame` 存下的**滞后字段值**，当刻真实空窗已 ~5 s。
修复后中断状态记录真实帧龄（`mediaAgeOf`），避免下一轮复测照着 2.7 s 又误判"媒体还活着"（本轮定因踩过该坑）。
故 15:27:54.733 那一瞬按**真实**帧龄（5.0 s > 3 s）判定为"不存活"，走的是宽限期抑制路径；
而"媒体**确实**新鲜（<3 s）时"由 `iceFailureSuppressed` 直接压掉失败文案/失败卡（点名用例④，§5.1）。

### 2.4 与既有冻结约定的关系

- doc/09 §4 状态机：**迁移名不变**（`peerLeft → DISCONNECTED`），只新增"来源"信号（`DisconnectCause`），无协议变更。
- doc/14 §8.5 口径 B（peerLeft ⇒ 立即挂断）：**被 captain 2026-09-15 裁定覆盖**（曾连上过 ⇒ 保留通话页回 `waiting_peer`）；首次入房即失败仍按口径 B 结束。见 §7.2。
- t64 口径（`WAITING_PEER` 不计时、不提示重试）与 t63 口径（帧存活独立通道、`iceDown` 只归因）**全部保留**，并由既有用例（`ConnectionStatusTrackerTest` 现 29 例）回归保护。
- t70（服务端房间宽限期 90 s）**已完成部署**：硬断开在 90 s 内不会再发 `peerLeft`，与本任务②形成双保险（客户端仍能在"宽限期满/房间确实被回收"时给可恢复态而不是退出）。

---

## 3. 改动清单（file:line）

| 文件 | 类型 | 关键改动（file:line） |
|---|---|---|
| `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt` | 改 | pong 容忍 :91/:94/:104-118/:667-705；`DisconnectCause`（4 来源）:830-849；`SIGNAL_LOST` 落点 :739/:784；`disconnectCause`/`isRejoinContext` :274-288；PeerLeft :593-600；TERMINAL_SUPPRESS :555-580；`ROOM_LOSS_CODES`/`isRoomLossCode`/`keepsCallOnRoomLoss` :934-960；§9.1 重连预算 :132/:140/:150-168/:788-800 |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt` | **新增** | 全部（140 行）：四条判定（peerLeft / 房间丢失 / 信令丢失 / 文案闸门）+ `RecoverableState` |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` | 改 | `recoverable` :201-204；`onStateChanged` :705-790（含 §9.1 `signal_lost_keep_call` :733 / `signal_lost_end_call` :761）；`recreateRoom` :653-685；`Created` :794-824；`Joined`/`PeerJoined` 重协商与 §9.2 `maybeRestartIceAfterRejoin` :1267-1290（调用 :838/:850）；PeerLeft :853-885；ServerError :982-1030；`onError` 闸门 :1161-1190；`mediaAliveForUi`/`inRoomContext` :1618/:1631-1636；诊断 :1386-1408；常量 :1689/:1716-1736 |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt` | 改 | `mediaAlive`/`iceFailureSuppressed`/`failureCardVisible`/`recoveryBannerVisible`/`recoveryBannerText` :150-182；latch :419-426；`onMediaFrame` :579；`onConnectionLost` :601-611；`mediaAgeOf` :620-621；`markLost` :634-651；`onTick` 四分支 :674-767；`markConnected` :802-817 |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt` | 改 | `recoverable` + `displayRoomId` :105/:114；分区渲染 :455-540；文案常量 :559-565 |
| `app/src/test/kotlin/com/example/webrtcdemo/signaling/PongLivenessTest.kt` | **新增** | 8 个用例（①） |
| `app/src/test/kotlin/com/example/webrtcdemo/signaling/SignalingErrorPolicyTest.kt` | 改 | +4 个用例（②③ + `DisconnectCause` 四来源对照 + §9.3 `reconnectRoomNotFoundDoesNotAutoExitCallPage`） |
| `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt` | **新增** | 12 个用例（②③ + §9.3 ①两条） |
| `app/src/test/kotlin/com/example/webrtcdemo/ui/call/MediaAliveSuppressionTest.kt` | **新增** | 10 个用例（③④ + 宽限期/诚实帧龄） |
| `app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt` | 改 | +2 个用例（t68）：`mediaAlive` latch 必须在 `onWaitingPeer`（peerLeft 复位）与 `onRetry`（用户重试）两条路径上复位 —— 防"残留真值永久压掉失败卡/重试"（:641-682） |
| `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt` | **新增（§9.3）** | 5 个用例：重连总预算 < 90 s 宽限期且 > 45 s 读超时、退避单调封顶 8 s、rejoin 预算 63 s、旧口径（3 s×3）回退必红 |
| `code/webrtc-demo/reports/36-call-survivability.md` | **新增** | 本报告 |

`git diff --stat`（仅 app 源码/测试，排除其他成员的在飞改动；新增文件不计入）：

```
 .../webrtcdemo/signaling/SignalingClient.kt        | 215 ++++++++++++-
 .../com/example/webrtcdemo/ui/call/CallScreen.kt   |  79 ++++-
 .../com/example/webrtcdemo/ui/call/CallViewModel.kt| 345 +++++++++++++++++++--
 .../com/example/webrtcdemo/ui/call/ConnectionStatus.kt | 133 +++++++-
 .../signaling/SignalingErrorPolicyTest.kt          |  64 ++++
 .../ui/call/ConnectionStatusTrackerTest.kt         |  42 +++
 7 files changed, 1047 insertions(+), 62 deletions(-)（含 §9 收尾；新增文件不计入 diffstat）
```

---

## 4. 新增/复用的可复测诊断字段（契约验收③）

| 字段（原始日志形态） | file:line | 判读方式 |
|---|---|---|
| `pong_miss count=N tolerance=4 timeout_ms=5000 fail_after_ms=20000` | `signaling/SignalingClient.kt:623-630` | `count` 逐次 +1；**只有 count=4 之后**才出现 `ws_pong_timeout misses=4`（:634-639）⇒ 一行即可确认"单次丢失被容忍了" |
| `disconnect_cause cause=peer_left\|room_lost\|fatal code=… rejoin=… room=…` | `signaling/SignalingClient.kt:520-530`（room_lost/fatal）、:546-549（peer_left） | 每次 `DISCONNECTED` 必有一条，直接说明"上层该不该自动结束" |
| `disconnect_deferred cause=peer_left\|room_lost ever_connected=… media_alive=… phase=… seq=… session=…` | `ui/call/CallViewModel.kt:723-734` | 证明**没有**在 `onStateChanged` 里自动挂断，去留交给事件判定 |
| `peer_left action=keep_call ever_connected=… media_alive=… media_age_ms=… seq=… session=…` | `ui/call/CallViewModel.kt:809-817` | 与旧行为（紧跟 `call_end`/`hangup`）形成对照 |
| `room_not_found action=keep_call code=… media_alive=… media_age_ms=… rejoin_context=… rejoin_drop=… phase=…` | `ui/call/CallViewModel.kt:944-958` | 出现即表示"房间被回收但通话页保留了"，并带可恢复态依据 |
| `room_recreate_invoked room=… role=… media_alive=…` / `room_recreated room=… recreate=true` | `ui/call/CallViewModel.kt:663-677` / :747-756 | 用户显式重建入口的两端留痕（含新房间号） |
| `ice_down_ui_suppressed age_ms=N media_source=… pair=… phase=… reason=… ice_down=…` | `ui/call/CallViewModel.kt:1296-1314`；错误文案路径同名 :1112-1127 | 出现即表示"媒体存活，失败文案被抑制"；恢复/确认失败后会重置限频闸门，可再次落盘 |
| `ui_conn_state … phase=… media_age_ms=… pair=… ice_down=…`（复用，t63） | `ui/call/CallViewModel.kt:1273-1295` | 复测时 `phase` 不应再出现"视频在流却 failed/connecting(connection_lost)"的长期停留 |

---

## 5. 单测（点名单测 + 全量原始摘要）

运行方式（**容器内无 JDK，宿主机离线直编**，不跑 Gradle —— 构建窗口属 t69）：
宿主机 `/tmp/kcheck/t68.sh`（本轮新增，仓库零写入）用 `kotlin-compiler-embeddable-2.0.21` + `-Xplugin` serialization/compose 编译
`app/src/main/kotlin`（+ `RStub.kt`）与 `app/src/test/kotlin`，classpath = `android.jar(SDK34)` + `libwebrtc-java.jar` + Gradle 缓存 jar + transforms classes.jar，
再以 `org.junit.runner.JUnitCore` 运行全部 `*Test` 类。

### 5.1 点名单测（对应验收①：至少四条，用例名体现题意）

| 验收 | 点名用例 | 文件 |
|---|---|---|
| ① pong 单次丢失不判失败、连续丢失才失败 | `PongLivenessTest.singleMissDoesNotDeclareTransportDead`、`consecutiveMissesUpToToleranceDeclareTransportDead`、`failAfterIsAtLeast20Seconds`、`replayOfDlbTimeline_ToleratesFirstThreeMissesOnly` | `app/src/test/kotlin/com/example/webrtcdemo/signaling/PongLivenessTest.kt` |
| ② peerLeft 且曾连上过 ⇒ 停在 waiting_peer、不 hangup/不 call_end | `CallSurvivabilityTest.peerLeftAfterConnectedStaysInWaitingPeerWithoutHangup`、`peerLeftAfterConnectedKeepsCall`、`peerLeftBeforeAnyConnectionStillEndsCall` | `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt` |
| ③ 重连期 ROOM_NOT_FOUND 不再直接 endsCall/hangup，而是可恢复态 | `SignalingErrorPolicyTest.roomGoneDuringRejoinNoLongerEndsCall_oldRedNewGreen`、`roomLossCodesCoverExactlyRoomGone`、`CallSurvivabilityTest.roomLostDuringRejoinOrWithMediaKeepsCall`、`roomLostNoticeDocumentsKeptCall` | `.../signaling/SignalingErrorPolicyTest.kt`、`.../ui/call/CallSurvivabilityTest.kt` |
| ④ 帧新鲜（<3 s）或 selected pair ⇒ 不进入 FAILED 且文案不含「ICE 失败」 | `MediaAliveSuppressionTest.mediaAliveSuppressesFailureCardAndShowsNeutralBanner`、`neutralBannerTextContainsNoIceFailureWording`、`freshFrameKeepsOutOfFailedWhileMediaAlive`、`graceperiodSuppressesThenConfirmedFailureSurfaces`、`neverConnectedTimeoutStillSurfacesFailureCard` | `app/src/test/kotlin/com/example/webrtcdemo/ui/call/MediaAliveSuppressionTest.kt` |
| 附（掉线来源语义） | `SignalingErrorPolicyTest.disconnectCauseSeparatesSurvivableFromFatal` | 同上 |

### 5.2 全量原始摘要（修复后 = new 语义）

```
=== compile main ===
MAIN_RC=0
=== compile tests ===
TEST_RC=0
=== run JUnit ===
JUnit version 4.13.2
...................................................................................................................................................................
Time: 0.300

OK (165 tests)
```

- 测试类数：**17**（基线 14 ⇒ +3：`PongLivenessTest`、`CallSurvivabilityTest`、`MediaAliveSuppressionTest`）
- 用例数：**165 OK / 0 failed**（基线 132 ⇒ +33）｜**注：§9 收尾后为 18 类 / 173 用例（见 §9.4，以其为准）**
- 离线整模块编译：**主源码 0 error、测试源码 0 error**（`MAIN_RC=0` / `TEST_RC=0`）
- 按类明细（本轮相关）：`PongLivenessTest` OK (8)、`SignalingErrorPolicyTest` OK (20, 基线 17)、`CallSurvivabilityTest` OK (10)、`MediaAliveSuppressionTest` OK (10)、`ConnectionStatusTrackerTest` OK (29, 基线 27 ⇒ +2：t68 新增 `mediaAlive` 复位不变量)
- 全部 17 类（逐类复跑**全绿**，计数合计 165）：`AppConfigUrlTest`(8)、`LogLevelFilterTest`(6)、`NativeInterfaceContractTest`(4)、`PongLivenessTest`(8,新)、`SignalingErrorPolicyTest`(20)、`SignalingIdentityTest`(11)、`CallSessionSlotTest`(8)、`CallSurvivabilityTest`(10,新)、`ConnectionStatusTrackerTest`(29)、`MediaAliveSuppressionTest`(10,新)、`PendingRemoteMessagesTest`(7)、`IceCandidateInfoTest`(8)、`JniBindingClasspathTest`(6)、`LoopbackCandidatesTest`(5)、`RendererRecoveryPolicyTest`(7)、`SessionLifecycleTest`(10)、`TurnTcpFallbackTest`(8)
- 逐类复跑的原始摘要（`java -cp out/main:out/test:$CP:junit:hamcrest:android.jar:libwebrtc-java.jar org.junit.runner.JUnitCore <单类>`）：

  ```text
  com.example.webrtcdemo.webrtc.TurnTcpFallbackTest                OK (8 tests)
  com.example.webrtcdemo.webrtc.JniBindingClasspathTest            OK (6 tests)
  com.example.webrtcdemo.webrtc.RendererRecoveryPolicyTest         OK (7 tests)
  com.example.webrtcdemo.webrtc.SessionLifecycleTest               OK (10 tests)
  com.example.webrtcdemo.webrtc.IceCandidateInfoTest               OK (8 tests)
  com.example.webrtcdemo.webrtc.LoopbackCandidatesTest             OK (5 tests)
  com.example.webrtcdemo.log.LogLevelFilterTest                    OK (6 tests)
  com.example.webrtcdemo.signaling.SignalingErrorPolicyTest        OK (20 tests)
  com.example.webrtcdemo.signaling.PongLivenessTest                OK (8 tests)
  com.example.webrtcdemo.signaling.SignalingIdentityTest           OK (11 tests)
  com.example.webrtcdemo.config.AppConfigUrlTest                   OK (8 tests)
  com.example.webrtcdemo.ui.call.CallSessionSlotTest               OK (8 tests)
  com.example.webrtcdemo.ui.call.ConnectionStatusTrackerTest       OK (29 tests)
  com.example.webrtcdemo.ui.call.MediaAliveSuppressionTest         OK (10 tests)
  com.example.webrtcdemo.ui.call.PendingRemoteMessagesTest         OK (7 tests)
  com.example.webrtcdemo.ui.call.CallSurvivabilityTest             OK (10 tests)
  com.example.webrtcdemo.nativebridge.NativeInterfaceContractTest  OK (4 tests)
  ```

  > 运行方式提示：逐类复跑必须带齐 `libwebrtc-java.jar`（`org.jni_zero.GEN_JNI` 等绑定类在其中）与 `android.jar`，
  > 否则 `JniBindingClasspathTest`/`NativeInterfaceContractTest`/`TurnTcpFallbackTest` 会因 `ClassNotFoundException` 假红（已实测：补上 jar 后三类全绿）—— 属运行方式问题，非用例失败。

### 5.3 old-red / new-green 对照（契约纪律要求：语义变更必须留证）

方法：**同一份测试代码**，仅在 `/tmp` 副本里把新语义回退为修复前实现后重编重跑（仓库零写入；补丁锚点见 `/tmp/kcheck/t68.sh`）。

| 对照 | 回退内容 | 结果（原始输出摘要） |
|---|---|---|
| new（现行） | — | `OK (165 tests)`，`MAIN_RC=0` / `TEST_RC=0` |
| old（②口径回退） | `SignalingErrorPolicy.keepsCallOnRoomLoss` 返回 `false`（= 修复前 `ROOM_NOT_FOUND ⇒ endsCall ⇒ hangup`） | `There was 1 failure:` → `1) roomGoneDuringRejoinNoLongerEndsCall_oldRedNewGreen(com.example.webrtcdemo.signaling.SignalingErrorPolicyTest)` `java.lang.AssertionError: 重连语境 + 媒体存活 ⇒ 保持通话页（dl-b 场景）` … `Tests run: 165,  Failures: 1` |
| pong-old（①口径回退） | `SignalingClient.pongTimeoutReached` 改为 `consecutiveMisses >= 1`（= 修复前"单次丢失即判断线"） | `There were 3 failures:` → `1) singleMissDoesNotDeclareTransportDead`（`AssertionError: 单次 pong 丢失绝不能判断线（真机 dl-b 根因）`）、`2) replayOfDlbTimeline_ToleratesFirstThreeMissesOnly`（`只有第 4 次（≈20 s）才允许判断线 expected:<[4]> but was:<[1, 2, 3, 4]>`）、`3) toleranceIsParameterizable` … `Tests run: 165,  Failures: 3` |

即：两条修复语义都是**测试可钉住**的（回退必红），不存在"只靠注释声明"的口径。

---

## 6. 契约 verify 命令原始输出

### 6.1 verify #1（`grep … | head -20`，**§9 收尾后重跑**的实测输出）

```bash
$ cd /data/dsh/home/workspace/code/webrtc-demo && grep -rn 'PONG_TIMEOUT_MS\|ROOM_NOT_FOUND\|peer_left\|waiting_peer' app/src/main/kotlin/com/example/webrtcdemo/signaling app/src/main/kotlin/com/example/webrtcdemo/ui/call | head -20
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82:        const val PONG_TIMEOUT_MS = 5_000L
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:88:         * 对端收到 peerLeft、本端重连得 ROOM_NOT_FOUND ⇒ **媒体明明还在流却自动退房**。
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:93:        /** 【t68】有效判活阈值（= [PONG_TIMEOUT_MS] × [PONG_MISS_TOLERANCE]，验收要求 ≥20 s）。 */
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:94:        const val PONG_FAIL_AFTER_MS = PONG_TIMEOUT_MS * PONG_MISS_TOLERANCE
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:108:            timeoutMs: Long = PONG_TIMEOUT_MS,
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:284:     * 通话页用它判定 `ROOM_NOT_FOUND` 属于"房间被回收（可重建）"还是"首次入房就找不到房间"。
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:601:                    mapOf("cause" to disconnectCause.name.lowercase(), "peer_left" to "true"),
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:635:            is SignalingMessage.PeerLeft -> AppLog.i(TAG, "peer_left", mapOf("peer" to message.peerId))
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:680:                            "timeout_ms" to PONG_TIMEOUT_MS.toString(),
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:689:                                "timeout_ms" to PONG_TIMEOUT_MS.toString(),
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:706:            PONG_TIMEOUT_MS,
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:707:            PONG_TIMEOUT_MS,
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:818:// 真机缺陷根因（dl-a:7516-7520 / dl-b:9717-9720）：`peerLeft` 与"重连期 ROOM_NOT_FOUND"都会把状态推到
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:837:    /** 重连期房间被回收（`ROOM_NOT_FOUND`/`ROOM_EXPIRED`）：给可恢复态 + 显式重建入口。 */
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:887:     * ROOM_NOT_FOUND / ROOM_EXPIRED = 房间确已销毁（go-dev 实测：一空即销毁）；
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:891:        "ROOM_NOT_FOUND",
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:916:     * 与 [TERMINAL_CODES] 同集合：房间确已销毁（`ROOM_NOT_FOUND` / `ROOM_EXPIRED`）或报文非法
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:934:    val ROOM_LOSS_CODES: Set<String> = setOf("ROOM_NOT_FOUND", "ROOM_EXPIRED")
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:939:     * 真机缺陷（dl-b 15:28:02.248）：pong 单次丢失 ⇒ 服务端回收房间 ⇒ 本端重连得 `ROOM_NOT_FOUND`
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:122:     * @property code 错误码（ROOM_NOT_FOUND / ROOM_FULL / …）。
（退出码 0；`head -20` 截断，ui/call 下的 `peer_left action=keep_call`/`waiting_peer`/`ROOM_LOST_*` 等命中被 head 截掉，实际匹配更多）
```

```bash
$ cd /data/dsh/home/workspace/code/webrtc-demo && ls -l reports/36-call-survivability.md && git status --porcelain
-rw-r--r-- 1 node node 53219 Sep 16 00:32 reports/36-call-survivability.md
（`git status --porcelain` 实测共 **89 行**；下面只列本任务 inScope 的 12 个路径）
 M app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt
 M app/src/test/kotlin/com/example/webrtcdemo/signaling/SignalingErrorPolicyTest.kt
 M app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt
?? app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt
?? app/src/test/kotlin/com/example/webrtcdemo/signaling/PongLivenessTest.kt
?? app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt
?? app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt
?? app/src/test/kotlin/com/example/webrtcdemo/ui/call/MediaAliveSuppressionTest.kt
?? reports/36-call-survivability.md
（退出码 0；其余 77 行是其他成员的在飞改动与历史未跟踪文件：`signaling/*.go`（t67，go-dev）、`reports/05`+`scripts/t5*`（webrtc-builder）、`reports/10-t47b-*.log`/`reports/26-*` 等；本任务未 git commit）
```

> 注：为可读性上面只保留**本任务 inScope 的 12 个路径**；`git status --porcelain` 实际输出为 **89 行**（总行数已如实给出），
> 其余行均为其他成员/历史未跟踪文件，未删改任何结论。`git status --porcelain -- app/src/main/cpp app/src/main/kotlin/com/example/webrtcdemo/webrtc` 为**空输出**（outOfScope 未触碰，见 §8.2）。

---

## 7. 未验证项（诚实清单）与口径说明

### 7.1 未验证项（真机/运行期，均需下一轮真机复测才能判定）

| # | 未验证项 | 为什么无法在交付前验证 | 复测时的判据 |
|---|---|---|---|
| U1 | **真机不再"误报 ICE 失败 + 自动退房"**（本任务的核心用户诉求） | 容器/宿主机均无设备与摄像头链路；只能静态 + 纯 JVM 验证 | 复测日志应满足：`pong_miss count=1..3` 后仍 `phase=connected`、**不出现** `ws_pong_timeout`（除非连续 4 次）；`disconnect_deferred cause=peer_left` + `peer_left action=keep_call`（**不**出现紧跟的 `call_end`/`hangup`）；视频短暂抖动时出现 `ice_down_ui_suppressed age_ms=…` 而**不**出现失败卡 |
| U2 | `ConnStatus` 三闸门在真机渲染层的视觉效果（中性提示不遮挡画面、可恢复面板按钮可点） | 需 Compose 真机渲染 | 截图 + `ui_conn_state`/`room_not_found action=keep_call` 对照 |
| U3 | 「重新创建房间」端到端可用（服务端 `create` → 新房间号 → 新一代会话 → 对端可用新号加入） | 需真机 + 服务端活体 | 点击后应见 `room_recreate_invoked` → `room_created`/`room_recreated`（**新房间号**）→ 通话页顶部会议号更新 → `call_init reason=recreate seq=N+1`；对端用新号 `join` 成功 |
| U4 | 重建后对端体验（对端仍停在旧房间/被宽限期回收后回首页） | 同上 | 对端日志：`grace_expired` → `peer_left`（服务端已部署 90 s 宽限期，t70） |
| U5 | 4G/弱网下有效阈值 20 s 是否合适（是否过长/过短） | 需真测网络抖动分布 | 复测日志中 `pong_miss` 次数分布；若频繁出现 count=4，再评估调大 `PONG_MISS_TOLERANCE` |
| U6 | 设备端 `AppLog` 落盘是否包含新增 `disconnect_cause`/`ice_down_ui_suppressed` 字段（格式与限频） | 需真机导出 logs.zip | 导出日志中逐字检索 §4 字段名 |
| U7 | t69 构建窗口才可验证的项：`:app:compileDebugKotlin` / `clean assembleDebug` / `:app:testDebugUnitTest`（Gradle 真跑）、APK 产物哈希 | **契约明令本任务不得跑宿主机 Gradle**（构建窗口属 t69）；离线直编不等价于 AGP 构建 | t69 的四次串行命令 EXIT=0 + 单测类数/用例数（预期 ≥18 类 / ≥173 用例；§9 收尾后） |

### 7.2 已登记的口径变更（非缺陷，但必须显式说明）

1. **doc/14 §8.5 口径 B 被覆盖**：本世代**曾连上过**时的 `peerLeft` 不再立即挂断，改为回 `waiting_peer` 保留房间（captain 2026-09-15 裁定，`CallSurvivability.kt:12-14` 有 KDoc 记录）；首次入房即失败仍按口径 B 结束。
2. **③ 的"selected pair ⇒ 不进入 FAILED"限定在宽限期内**（8 s）：宽限期用尽且无恢复即如实 `FAILED`（否则真实断流将永久被伪装成正常，破坏 t59/t60 的失败+重试验收）。理由与取舍见 §2.3 末段。
3. **中断状态的帧龄语义修正**：`markLost` 现在写**真实帧龄**（`mediaAgeOf`），不再沿用滞后的 `mediaAgeMs` 字段值。这会让下一轮真机日志里的 `media_age_ms` 比修复前"更大"（例如 dl-b 同类场景由 2705 变为 ~5000）——**这是诚实性修正，不是回归**。
4. **`SignalingErrorPolicy` 终稿 API 与中间稿不同**：中间稿的 `survivabilityFor(...): RoomLostAction` 已删除（会引入 `signaling → ui.call` 反向依赖），改为 `ROOM_LOSS_CODES` + `isRoomLossCode` + `keepsCallOnRoomLoss`（信令层只管"是不是房间丢失"，动作判定在 UI 层 `CallSurvivability`）。`endsCall`/`TERMINAL_CODES` 语义**未变**（`SignalingErrorPolicyTest` 既有 17 例全部保留通过）。

### 7.3 未决问题（建议 captain 决策，不阻塞本次交付）

- Q1：`ROOM_NOT_FOUND` 可恢复态下，是否也允许"回到首页重开"（除「重新创建房间」外再给一条"退出重来"）？当前只有「重新创建房间」+ 既有挂断按钮。
- Q2：重建房间后房间号**必然变化**（服务端 `create` 不接收客户端房间号）：是否需要在可恢复面板里显式提示"请把新会议号告知对方"（当前已写在副标题 `RECOVERABLE_MEDIA_ALIVE_NOTICE`，但未用 Toast/弹窗强调）。
- Q3：是否把可恢复态的两个文案常量迁到 `res/values/strings.xml`（本任务 inScope 不含资源文件，故内联，与既有 `NOTICE_CALL_ENDED` 同惯例）。

---

## 8. 纪律与边界自检

### 8.1 inScope 合规

改动路径 12 个（源码 4 改 + 1 新增；测试 3 改 + 4 新增；报告 1 新增），全部落在 inScope（`.../signaling/`、`.../ui/call/`、`app/src/test/kotlin/`、`reports/36-call-survivability.md`）；
`ui/navigation/` 未改（终态失败仍走既有 `onHangup` 回首页逻辑，无需改动）。

### 8.2 outOfScope 未触碰（自检命令与结果）

```
$ git status --porcelain -- app/src/main/cpp app/src/main/kotlin/com/example/webrtcdemo/webrtc
（空输出）
```
- `cpp/**`、`webrtc/**`：**未触碰**，也**不需要**触碰 ⇒ 契约验收⑤（"若确有必要改动须在报告单列"）不适用，无单列项。
- 根目录 `signaling/`（Go）：未触碰（t67/t70 由 go-dev / coturn-installer 负责）。
- `doc/14-interface-contract.md`：未触碰。
- **生产代码未依赖 `app/src/test`、未新增第三方依赖**（`gradle.properties`/`build.gradle.kts` 未改）。

### 8.3 构建窗口纪律

本任务期间**未**执行 `clean assembleDebug`、**未**跑任何 Gradle 任务、**未**做 git commit；
离线预检只在宿主机 `/tmp/kcheck` 下用 kotlinc-embeddable 直编直跑（仓库零写入）。
t69 可安全开工：`app/src` 最后写入时刻见产出消息，之后本任务不再写任何源码/测试文件。

### 8.4 权限/属主

新增/修改的源码与测试文件均为 `-rw-------`（600，本仓库源码惯例）；本报告 `-rw-r--r--`（644）。

---

## 9. 收尾：与服务端房间宽限期（t67/t70）对齐 —— captain 2026-09-16 指令 1/3/4

> 本节在 t68 完成**之后**、按 captain 2026-09-16 指令追加（服务端 t67 已交付：WS 断开不再等于离开房间，
> 默认保留席位 **90 s**、宽限期内**不下发 peerLeft**、同身份重连接管原席位并回既有 `joined` 路径、
> wire 协议零改动；t70 已把该二进制部署到宿主机并活体验证）。本节改动落在 `signaling/`、`ui/call/`、
> `app/src/test/kotlin/`，**未触碰** `webrtc/**`、`cpp/**`、`doc/14`。

### 9.1 指令①：未收到 `peerLeft` 不得自行挂断（本轮补的**是唯一遗留路径**）

t68 已覆盖"消息驱动"的两条（`peerLeft`、重连期 `ROOM_NOT_FOUND`），但**"本地判活"还有一条漏网**：

- 修复前：`SignalingClient.RECONNECT_DELAY_MS=3_000` × `MAX_RECONNECT_ATTEMPTS=3` ⇒ socket 重连预算仅 **9–12 s**；
  3 次失败即置 `disconnectCause=FATAL` ⇒ `CallViewModel.onStateChanged` 走 `endCallNow()` **退出通话页**。
  即：抖动持续 >12 s 时，客户端会在**服务端仍保留席位（90 s）且对端未收到 peerLeft**的情况下自行退房，
  而此刻 RTP 往往仍在流 —— 正是 captain 要求消除的形态。

本轮改动：

| 内容 | file:line |
|---|---|
| 新增 `RECONNECT_MAX_DELAY_MS = 8_000L`（单次上限） | `signaling/SignalingClient.kt:132` |
| `MAX_RECONNECT_ATTEMPTS` 3 → **11** | `signaling/SignalingClient.kt:140` |
| 纯函数 `reconnectDelayMs(attempt)`：3 s 起、翻倍、封顶 8 s（3,6,8,8,…） | `signaling/SignalingClient.kt:150-157` |
| 纯函数 `reconnectBudgetMs()`：**累计 81 s < 服务端 90 s 宽限期** | `signaling/SignalingClient.kt:164` |
| `rejoinBudgetMs()`（63 s，诊断/单测用） | `signaling/SignalingClient.kt:203` |
| `ws_reconnect_give_up` ⇒ 改置 `SIGNAL_LOST`（不再 FATAL）并带 `budget_ms`/`in_room_before_drop`/`room` | `signaling/SignalingClient.kt:770-786` |
| `ws_rejoin_give_up`（ROOM_FULL 退避耗尽）⇒ 同样改置 `SIGNAL_LOST` + `budget_ms` | `signaling/SignalingClient.kt:722-740` |
| `ws_reconnect_scheduled` 增 `delay_ms`/`budget_ms`/`max_attempts` | `signaling/SignalingClient.kt:788-800` |
| 新增 `DisconnectCause.SIGNAL_LOST(survivable=true)` | `signaling/SignalingClient.kt:841-848` |
| 新增纯判定 `SignalLostAction` + `signalLostAction(everConnected, mediaAlive)` | `ui/call/CallSurvivability.kt:36-45` / `:105-106` |
| 通话页按媒体判定：`KEEP_CALL` ⇒ 保留通话页**与会话** + 可恢复态（不 hangup）；否则才结束 | `ui/call/CallViewModel.kt:724-780`（`signal_lost_keep_call` :733、`signal_lost_end_call` :761） |
| 新提示文案（明确"对端未离开、画面可能仍在"+ 两条显式出路） | `ui/call/CallViewModel.kt:1730` / `:1733` |

**预算口径自证**：`3+6+8×9 = 81 s`（`reconnectBudgetMs()`），严格 **< 90 s**（服务端宽限期），且 **> 45 s**（覆盖服务端读超时）。
`ROOM_FULL` 退避（1/2/4/8 s × 10）累计 `rejoinBudgetMs() = 63 s`，同样 < 90 s。

### 9.2 指令③：重连入会成功后重协商 + `restartIce()`（仅 ViewModel 侧触发）

- 重协商前半**已具备**（t68）：`Joined`/`PeerJoined` ⇒ `maybeCreateOffer()`（host 发 offer；joiner 等 host 的 offer，防 glare）。
- 本轮补**后半**：新增 `maybeRestartIceAfterRejoin(trigger)`（`ui/call/CallViewModel.kt:1267-1290`），
  在 `Joined`（:838）与 `PeerJoined`（:850）分支调用，复用**既有** `CallSession.restartIce("signaling_rejoin")`
  （`webrtc/` 目录**零改动**；该 API 内部已有每会话次数上限与 `ice_restart_exhausted` 诊断）。
- 门控（避免打扰健康通话）：仅当 `everConnectedInGeneration`（本世代曾连上过）**且**（`iceDown` 或 `!mediaAlive`）才触发；
  日志 `rejoin_ice_restart trigger=… accepted=… ice_down=… media_alive=… phase=… seq=… session=…`（:1276-1288）。

### 9.3 指令④：点名单测（新增 2 条 + 1 个新测试类）

| 验收 | 点名用例 | file:line |
|---|---|---|
| ① IN_CALL 且未收到 peerLeft ⇒ 不 hangup / 不 call_end | `CallSurvivabilityTest.inCallWithoutPeerLeftDoesNotHangupOrEndCall`、`neverConnectedSignalLossEndsCallToAvoidDeadPage` | `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt:111` / `:125` |
| ② 重连期 ROOM_NOT_FOUND ⇒ 不自动退出通话页 | `SignalingErrorPolicyTest.reconnectRoomNotFoundDoesNotAutoExitCallPage` | `app/src/test/kotlin/com/example/webrtcdemo/signaling/SignalingErrorPolicyTest.kt:246` |
| ①口径的预算硬约束（新测试类，5 例） | `ReconnectBudgetTest.reconnectBudgetIsStrictlyBelowServerGrace` / `reconnectDelayIsCappedAndMonotonic` / `reconnectBudgetMatchesAttemptCount` / `rejoinBudgetAlsoStaysBelowServerGrace` / `oldThreeAttemptBudgetWouldViolateTheRequirement` | `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt:23/34/51/60/72` |
| 掉线来源语义（更新为 4 个来源，唯一不可存活 = FATAL） | `SignalingErrorPolicyTest.disconnectCauseSeparatesSurvivableFromFatal` | `.../signaling/SignalingErrorPolicyTest.kt`（`assertEquals(4, entries.size)` + `filter{!survivable} == [FATAL]`） |

### 9.4 离线预检原始摘要（应用补丁后，**仓库树**；宿主机 kotlinc-embeddable 直编 + JUnit4，未跑 Gradle）

```text
=== compile main ===
MAIN_RC=0
=== compile tests ===
TEST_RC=0
=== run JUnit ===
classes: 18
JUnit version 4.13.2
Time: 0.299

OK (173 tests)
```

- 测试类数：**18**（原 17 ⇒ +1：`ReconnectBudgetTest`）
- 用例数：**173 OK / 0 failed**（原 165 ⇒ +8：ReconnectBudget 5 + CallSurvivability 2 + SignalingErrorPolicy 1）
- 应用补丁前后均在同一 harness 下跑（先 staging、后仓库树），结果一致 ⇒ 补丁内容与验证内容**同一份**。

### 9.5 old-red / new-green 对照（重连预算口径）

方法同 §5.3：**同一份测试代码**，仅在 `/tmp` 副本把新口径回退为修复前实现（`MAX_RECONNECT_ATTEMPTS = 3` + `reconnectDelayMs` 固定返回 3 s）：

```text
1) reconnectDelayIsCappedAndMonotonic  AssertionError: expected:<6000> but was:<3000>
2) oldThreeAttemptBudgetWouldViolateTheRequirement  AssertionError: 新口径必须覆盖住
3) reconnectBudgetIsStrictlyBelowServerGrace  AssertionError: 预算应覆盖服务端读超时 45 s（实测 9000 ms）
（另有 1 例同类失败）
Tests run: 173,  Failures: 4
```

即"预算 9 s 不满足 45 s 读超时 / 90 s 宽限期"是**可被测试钉住**的事实（回退必红）。

### 9.6 本节新增的诊断字段（供下一轮真机复测）

| 字段 | file:line | 判读 |
|---|---|---|
| `ws_reconnect_scheduled attempt=N reason=… delay_ms=… budget_ms=81000 max_attempts=11` | `signaling/SignalingClient.kt:788-800` | 重连预算与退避节奏一眼可见 |
| `ws_reconnect_give_up attempts=12 budget_ms=81000 in_room_before_drop=true room=…` | `signaling/SignalingClient.kt:770-786` | 预算耗尽时的语境（是否曾在房内） |
| `disconnect_cause cause=signal_lost …` | `signaling/SignalingClient.kt`（SIGNAL_LOST 分支） | 与 `peer_left`/`room_lost`/`fatal` 并列的第 4 种来源 |
| `signal_lost_keep_call ever_connected=… media_alive=… media_age_ms=… phase=…` | `ui/call/CallViewModel.kt:733-744` | **出现即证明"未收到 peerLeft 时没有自行退房"** |
| `signal_lost_end_call reason=never_connected_no_media …` | `ui/call/CallViewModel.kt:761-770` | 仅从未连上过且无媒体时才结束 |
| `rejoin_ice_restart trigger=joined\|peer_joined accepted=… ice_down=… media_alive=… phase=…` | `ui/call/CallViewModel.kt:1276-1288` | 重连后是否真的补了 ICE restart |

### 9.7 本节未验证项（真机）

| # | 未验证项 | 复测判据 |
|---|---|---|
| V1 | 抖动 >12 s 时客户端**不再退房** | 日志出现 `ws_reconnect_scheduled … attempt≥4 … budget_ms=81000` 且**不出现** `call_end`/`hangup`；出现 `signal_lost_keep_call` 后界面仍在通话页 |
| V2 | 90 s 预算与服务端宽限期的实际配合（是否仍有更早的本地放弃） | 复测日志里 `ws_reconnect_give_up attempts=12` 的时刻应 ≥ 首个失败重连 + 81 s |
| V3 | 重连成功后 `restartIce()` 是否真的修复黑屏 | 出现 `rejoin_ice_restart accepted=true`，随后 `ui_conn_state phase=connected` 且有新帧 |
| V4 | 可恢复态面板在"信令已断开"场景的可读性/可操作性 | 截图 + `signal_lost_keep_call` 对照 |
| V5 | t69/Gradle 真跑（本任务禁跑） | 新构建任务需记录 **≥18 类 / ≥173 用例** |

### 9.8 时序与写入说明（诚实披露）

- 本轮写入发生在 **2026-09-16 00:29:35 (+0800)**；写入前实测宿主机**无 gradle 进程**、`app/build` 无 3 分钟内写入。
- ⚠️ **重要**：t69 已于 **00:17:26** 产出 `app-debug.apk`（33 419 885 B）、并在 **00:21:58** 跑完单测 ——
  即该 APK **不包含本节改动**（它对应 §1–§8 的 t68 状态）。若要将指令 1/3/4 一并交付，需要**再次构建**。
- git 状态：本节新增/修改 6 个文件（3 改源码 + 2 改测试 + 1 新增测试），全部落在 inScope；未触碰 `cpp/**`、`webrtc/**`、`doc/14`、根 `signaling/`。
