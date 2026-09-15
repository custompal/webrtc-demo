# t59 报告：连接未建立时的界面误导 —— 静止帧被当已出画面、无状态提示、无一键重试

- 任务：t59「修复连接未建立时的界面误导：静止帧被当已出画面 + 无状态提示/无一键重试」
- 归属：android-dev（attempt 2）
- 范围（inScope）：`app/src/main/kotlin/com/example/webrtcdemo/ui/call/**`（含新增 `ConnectionStatus.kt`）、
  `app/src/test/**`、本报告
- 依据：captain 提供的真机日志（宿主 `/opt/dsh-workspaces/tmp/dj-a/x`＝host、`dj-b/x`＝joiner，room `J3EEX7`）
  + 本轮自行核对的完整时序与 stats 形态分布
- **app 侧只做两件事**：① 连接状态可见性与一键重试；② 诊断事件。
  **服务端/中继侧根因（`CREATE_PERMISSION 403 Forbidden IP`、`local_relay=0`、分配超时/残留）由 t58 处理，本任务未触碰任何服务端/中继配置。**

---

## §0 摘要（结论先行）

1. **界面"没有任何提示"的直接根因是 UI 状态机把"编码器在跑"当成了"已连上"**：
   旧 `CallViewModel.onStats()`（HEAD `eb4b768`，`:538-539`）写的是
   `if (snapshot.connectionType.isNotEmpty() || snapshot.encoderImplementation.isNotEmpty()) isConnecting = false`，
   而真机 `stats_sample … impl=SelfVp9Libvpx local=- mode=- remote=-` 里
   **`encoderImplementation` 非空、`connectionType` 为空** —— 编码器照样被驱动（`up_bps≈49 kbps` 全部是
   "交给 ICE 层的字节"，**不是**链路打通的证据），于是"连接中"遮罩在进房约 1 s 后就被错误关闭。
   真机 `dj-b` 全量 stats 里这种"编码器在跑但没有候选对"的样本有 **21 条**（§1.2）。
2. **第二处误判**：`onReady()`（HEAD `:512/516`）是"本地媒体链就绪（PC 已建、本地轨已挂）"，
   **不是**"已连上对端"，旧代码却在这里也关掉了遮罩 ⇒ 进房约 0.4 s 后界面就再无连接状态。
3. **"曾连上又断掉"对主界面完全不可见**：`onIceEvent()`（HEAD `:524-526`）只把事件追加进诊断列表，
   `pc_ice_connection_state state=DISCONNECTED`（真机 12:37:24.420）、
   `pc_connection_state dtls=false state=FAILED`（12:37:34.384，**ERROR 级**）都没有驱动任何 UI 状态；
   而 `CallSession` 的 ICE 看门狗在**第一次 CONNECTED 时就自我关闭**（`CallSession.kt:941-951`
   `stopConnectivityWatchdog(ifConnected = true)`，真机 `ice_watchdog_ok elapsed_ms=952`），
   之后没有任何东西再看守 ⇒ 断线后连 `state.error` 都不会出现。
4. **"静止帧被当已出画面"**：`isRemoteFirstFrame` 侧 `onRemoteFirstFrame()`（HEAD `:325-327`）无条件
   `isRemoteVideoReady = true` 且**从不复位**；`SurfaceViewRenderer` 在 RTP 停止后会把最后一帧一直留在
   surface 上 ⇒ 用户看到一张永久静止的"画面"，而 UI 认为通话正常。
5. **没有任何一键重试**：旧界面唯一的失败出口是 `CallScreen.kt:437-449` 的"连接中遮罩"
   （只依赖 `state.isConnecting`）和底部一行小号红字 `state.error`；没有失败态、没有重试按钮。
6. 修复：新增**纯 Kotlin 连接状态机** `ui/call/ConnectionStatus.kt`（`CONNECTING/CONNECTED/FAILED` +
   已用/已中断时长 + 可重试标志 + 中文提示文案 + `DISCONNECTED` 解析陷阱处理 + 重试重协商决策），
   由 ViewModel 用"选中候选对 / `pc_connection_state CONNECTED` / 远端帧或下行字节 / ICE 掉线"驱动，
   **15 s 未连上 ⇒ 失败 + 一键重试**（重试走 t53 的世代化新会话：新 `CallSession` + 新 `PeerConnection`）；
   `CallScreen` 改为按状态渲染（未连上/画面停滞时压暗远端画面 + 显示含时长的状态卡/失败卡 + 重试按钮）。

---

## §1 现象与真机证据

### 1.1 用户可见现象

真机 4G（host）↔ WiFi（joiner），room `J3EEX7`：**画面出现一帧后永远卡住**，两端"编码速率"极低，
界面没有任何"连接中/失败"提示。

### 1.2 `dj-b`（joiner，pid 15780）全量 stats 形态分布 —— "编码器在跑但没有候选对"

```
$ grep -o "impl=[^ ]* local=[^ ]* mode=[^ ]* remote=[^ ]*" app.log | sort | uniq -c | sort -rn
    104 impl=SelfVp9Libvpx local=host mode=P2P remote=host        ← 正常（有选中候选对）
     97 impl=- local=- mode=- remote=-                             ← 完全没开始（编码器也未起）
     80 impl=libvpx local=host mode=P2P remote=host
     41 impl=libvpx local=relay mode=RELAY remote=prflx
     38 impl=SelfVp9Libvpx local=relay mode=RELAY remote=prflx
     26 impl=libvpx local=prflx mode=P2P remote=prflx
     22 impl=- local=relay mode=RELAY remote=srflx
     21 impl=SelfVp9Libvpx local=- mode=- remote=-                 ← ★ 本缺陷形态
```
**`impl=SelfVp9Libvpx` + `local=- mode=- remote=-`（21 条）**：编码器在跑、`up_bps` 在涨
（captain 取证 ≈49 kbps）、但**没有任何选中的候选对** —— 链路根本没通。
旧 UI 只看 `encoderImplementation.isNotEmpty()` ⇒ 判成"已连接"。

### 1.3 `dj-b` 一次真实"连上又断"的完整时序（12:37 会话）

```
12:37:17.266 offer_received sdp_bytes=2528 / remote_deferred kind=offer queued=1 seq=1
12:37:17.287 pc_starting evt=1 session=s1
12:37:17.355 pc_created evt=6 phase=READY session=s1
12:37:17.393 answer_create evt=14 / answer_created sdp_bytes=2450 / answer_sent evt=16   ← t53 修复后顺序正确
12:37:17.599 pc_connection_state dtls=false state=CONNECTING
12:37:18.254 selected_candidate_pair … mode=RELAY reason=candidate_pair_state_changed
12:37:18.344 pc_connection_state dtls=true state=CONNECTED        ← 连上了
12:37:18.344 ice_watchdog_ok elapsed_ms=952                       ← ★ 看门狗在此自我关闭，之后无人看守
12:37:19.377 stats_sample avail_bps=0 down_bps=0 impl=SelfVp9Libvpx local=host mode=RELAY up_bps=0
12:37:21.371 stats_sample avail_bps=0 down_bps=372416 impl=SelfVp9Libvpx … up_bps=133066   ← 短暂有画面
12:37:24.420 pc_ice_connection_state state=DISCONNECTED            ← ★ 6 秒后掉线
12:37:24.423 pc_connection_state dtls=false state=DISCONNECTED
12:37:34.384 pc_ice_connection_state state=FAILED
12:37:34.384 ERROR pc_connection_state dtls=false state=FAILED      ← ★ ERROR 级，但界面无任何变化
12:37:56.832 stats_sample avail_bps=0 down_bps=0 impl=SelfVp9Libvpx local=- mode=- up_bps=51436
12:38:15.414 state_change from=IN_ROOM to=DISCONNECTED              ← 25 秒后信令才断开
```
**这段时间里用户看到的**：12:37:21 的那一帧画面（静止）、`up_bps≈49 kbps`、以及**完全没有提示**的界面。

### 1.4 会话摘要（导出日志）也是空的

```
roomId=J3EEX7  role=host     connection_type=  encoder_implementation=  up_bps=0 down_bps=0  ice_events=20
roomId=J3EEX7  role=joiner   connection_type=  encoder_implementation=  up_bps=0 down_bps=0  ice_events=26
```
`ice_events` 有 20/26 条 —— 事件**到了** ViewModel，只是**从未驱动过界面**（§2.3）。

---

## §2 根因（代码级，基线 = HEAD `eb4b768`）

### 2.1 三条"把未连接当已连接"的写入点

| # | file:line（旧代码） | 代码 | 为什么错 |
|---|---|---|---|
| R1 | `CallViewModel.kt:528`/`:538-539` | `onStats()`：`if (connectionType.isNotEmpty() \|\| encoderImplementation.isNotEmpty()) isConnecting = false` | `impl=SelfVp9Libvpx` 在**无候选对**时同样非空 ⇒ 进房约 1 s 后就关掉遮罩（**主根因**） |
| R2 | `CallViewModel.kt:512`/`:516` | `onReady()`：`isConnecting = false` | `onReady` 是"本地媒体链就绪"（`pc_created` 之后），**不是**连接建立（真机 `pc_created` 与 `CONNECTED` 相隔 1 s；缺陷会话里从未 CONNECTED） |
| R3 | `CallViewModel.kt:360`/`:363` | `onStateChanged(IN_CALL)` ⇒ `isConnecting = false` | 信令 `IN_CALL` ≠ 媒体连通（真机缺陷会话信令一路正常） |

三处叠加后，`isConnecting` 在**任何连接尝试之前**就变成 `false`，而 `CallScreen.kt:437-449` 的
唯一状态卡是 `if (state.isConnecting) { … }` ⇒ **界面从此不再有任何连接状态**。

### 2.2 "静止帧被当已出画面"

| # | file:line | 代码 | 后果 |
|---|---|---|---|
| R4 | `CallViewModel.kt:325-327` | `fun onRemoteFirstFrame() { _uiState.update { it.copy(isRemoteVideoReady = true) } }` | 无连通性闸门、**从不复位**；`SurfaceViewRenderer` 保留最后一帧 ⇒ 静止帧被当作"通话正常" |

### 2.3 "曾连上又断"完全不可见

| # | file:line | 代码 | 后果 |
|---|---|---|---|
| R5 | `CallViewModel.kt:524-526` | `onIceEvent(event) { _iceEvents.update { it + event } }` | `DISCONNECTED`/`FAILED` 只进诊断列表，不驱动任何 UI 状态 |
| R6 | `CallSession.kt:941-951` | `stopConnectivityWatchdog(ifConnected = true)`（首次 CONNECTED 时 `shutdownNow()`） | t44 的 15 s/30 s 看门狗**一次连接成功后即永久关闭**，此后断线无人看守（真机 `ice_watchdog_ok elapsed_ms=952`）⇒ 连 `state.error` 都不会出现 |
| R7 | `CallScreen.kt:437-449` | 连接中遮罩 `if (state.isConnecting)` + 底部一行小号红字 | 无失败态、无重试入口 |

> **字段来源核对**（`webrtc/StatsMapper.kt`，本轮**只读核对、未改动** —— `webrtc/**` 不在本任务范围）：
> `encoderImplementation ← outbound-rtp.encoderImplementation`（`:81`）——**只要发送侧存在 outbound-rtp 就非空**，
> 与链路是否连通**无关**；`connectionType` 由选中候选对的候选类型推导（`:56-57`/`:77`），
> 且 `connectionTypeOf()` 在本地/远端候选类型**都为空时返回 `""`**（`:128`）⇒
> **`connectionType` 非空 ⇔ 存在可解析的选中候选对**，这正是本修复采用的判据（F3）。
> `bytesSent`（上行）同样只反映"交给 ICE 层的字节"（`:59`/`:62`），无候选对时仍会增长（真机 ≈49 kbps）
> ⇒ 判活只能用 `bytesReceived`（`down_bps`）。

### 2.4 结论

现象的全部四条（静止帧 / 编码速率极低 / 无"连接中" / 无失败与重试）都可以由 R1–R7 解释，
**不需要**假设任何服务端行为；服务端侧（TURN 权限/中继分配）是**为什么连不上**的原因（t58），
而本任务是**为什么界面不告诉用户**（UI 状态机把"本地编码器在跑"错当成"端到端已连通"）。

---

## §3 修复

### 3.1 新增纯 Kotlin 状态机 `ui/call/ConnectionStatus.kt`（inScope）

| 元素 | 作用 |
|---|---|
| `ConnPhase { CONNECTING, CONNECTED, FAILED }` | 三态；`CONNECTED` **只**由"选中候选对 / 传输 CONNECTED / 远端帧或下行字节"触发 |
| `ConnReason { NONE, TIMEOUT_NO_PAIR, CONNECTION_LOST, REMOTE_FRAME_STALLED, SESSION_START_FAILED }` | 失败/中断原因，直接进诊断与提示文案 |
| `ConnStatus` | 快照：`phase/reason/elapsedMs/sinceLossMs/hasSelectedPair/remoteFrameReady/remoteFrameStalled/retryCount` + 派生 `showOverlay`/`remoteDimmed`/`canRetry`/`elapsedSeconds`/`title`/`detail`（中文文案是纯函数，可单测） |
| `parseConnSignal(detail)` | 解析 `onIceEvent` 的 detail；**先判 `DISCONNECTED` 再判 `CONNECTED`**（子串陷阱，真机 `state=DISCONNECTED` 里含 `CONNECTED`） |
| `retryNegotiationFor(role, peerKnown, sawRemoteNegotiationSinceRetry)` | 重试后的重协商决策：host ⇒ 立刻重发 offer；joiner ⇒ 延迟重发；对端未知 ⇒ 什么都不做 |
| `ConnectionStatusTracker` | 状态机本体：`onCallStarted/onRetry/onSelectedPair/onTransportConnected/onRemoteFrame/onConnectionLost/onTick/onSessionFailed/onCallEnded`；默认阈值 **15 s 未连上 ⇒ FAILED**、4 s 无新鲜下行 ⇒ 画面回落、8 s 宽限未恢复 ⇒ FAILED |

> 该文件是**唯一新增的生产文件**，且完全无 Android/Compose/`org.webrtc` 依赖 ⇒ 可在纯 JVM 下单测。

### 3.2 `CallViewModel.kt` 改动

| # | 改动 | 对应根因 |
|---|---|---|
| F1 | 新增 `connStatus: StateFlow<ConnStatus>` 与 1 s 心跳协程（推进"已等待 N 秒"、判定 15 s 超时） | 无状态、无时长 |
| F2 | **`publishConnStatus()` 成为 `isConnecting` 的唯一写入方**：`CONNECTING ⇒ true`；同时派生 `isRemoteVideoReady = CONNECTED && remoteFrameReady`（**连接未建立或画面停滞即回落**） | R1/R2/R3/R4 |
| F3 | `onStats()`：删除 `encoderImplementation.isNotEmpty()` 判据；只认 `connectionType` 非空（有候选对）或 `down_bps > 0`（**下行字节是唯一能证明路径打通的信号**；无候选对时 `up_bps` 仍涨到 ≈49 kbps） | **R1（主根因）** |
| F4 | `onReady()`：删除 `isConnecting = false` | R2 |
| F5 | `onStateChanged()`：不再写 `isConnecting`（只更新信令文案）；`armPeerResponseWatchdog` 的判活基准改为"连接未建立"（避免 15 s 置 false 后 t51 的 20 s 提示永不触发） | R3 |
| F6 | `onIceEvent()`：`ICE_CONNECTION` 经 `parseConnSignal` 驱动状态机（`CONNECTED` / `DISCONNECTED` / `FAILED`） | R5/R6 |
| F7 | `onRemoteFirstFrame()`：加连通性闸门；未连上时落盘 `remote_frame_ignored reason=not_connected` 并**不上报 UI** | R4 |
| F8 | 一键重试 `retryConnection()`：日志 `retry_invoked` → `beginGeneration(room, role, REASON_RETRY)`；**走 t53 世代化路径**（`slot.beginCall` ⇒ 旧会话必先 `close()`），新建 `CallSession`/`PeerConnection` | R7 |
| F9 | 重试重协商：host 置 `peerJoined = true` 走既有 `onReady → createOffer`；joiner 延迟 1.2 s 重发 offer（`retry_reoffer`/`retry_reoffer_run`），收到对端 offer/answer 即取消（避免 glare） | R7 |
| F10 | 重试**不** `SignalingIdentity.reset()`、**不** `leave()`：保留房间与信令连接（`leave()` 会让对端收到 `peerLeft` 而被连带挂断） | R7 |
| F11 | 诊断：`ui_conn_state`（阶段/原因/时长/对端/画面/重试次数）、`no_selected_pair after_ms=N`（含 `impl/avail_bps/up_bps/down_bps/local/remote`，15 s 限频）、`session_retry`、`remote_frame_ignored` | 验收第 4 条 |

### 3.3 `CallScreen.kt` 改动

| # | 改动 |
|---|---|
| S1 | 采集 `connStatus`（`collectAsStateWithLifecycle`） |
| S2 | 中央状态卡改为按 `conn.phase` 渲染：`CONNECTING` ⇒ 转圈 + `conn.title` + **`conn.detail`（含已等待/已中断秒数）**；`FAILED` ⇒ 红色标题 + 失败详情 + **「点击重试」Button → `viewModel.retryConnection()`**；并保留房间号 |
| S3 | 新增远端区域**压暗遮罩**（`conn.remoteDimmed`，位于本地小窗**之前** ⇒ 不遮挡本地预览）：连接未建立或没有新鲜远端帧时，绝不把静止帧呈现为"通话中" |
| S4 | 已连上但尚未收到画面 ⇒ 顶部小提示「已连接，等待对端画面…」（不遮挡画面，避免用户误判"卡住"） |

> 文案说明：本任务 inScope 不含 `res/values/strings.xml`，故与既有 `NO_PEER_RESPONSE_NOTICE` 一致地
> 以 Kotlin 常量内联（`ConnStatus.title/detail`、`RETRY_LABEL`、`WAITING_REMOTE_FRAME_NOTICE`）；
> 已在 §8 登记为 i18n 后续项。

### 3.4 修正前后行为对照

| 场景 | 修正前（HEAD `eb4b768`） | 修正后（t59） |
|---|---|---|
| 进房、尚未连上（无候选对） | 约 0.4–1 s 后遮罩消失；只看到静止帧/黑屏与低码率，**无任何提示** | 中央状态卡「正在建立连接…／已等待 N 秒」（每秒刷新）+ 远端画面压暗 |
| 15 s 仍无候选对 | 无提示；继续无限等待 | `FAILED(TIMEOUT_NO_PAIR)`：「连接失败／15 秒内未能建立媒体通道（NAT/防火墙可能阻断了候选对）」+ **「点击重试」** |
| 点击重试 | 不存在该入口 | `retry_invoked` → **新一世代**：旧 `CallSession.close()`（PC/统计循环/看门狗停）→ 新建 PC → 重新协商（host 立刻 offer / joiner 延迟 1.2 s offer）→ 状态回到「正在建立连接…」 |
| 曾连上又断（真机 12:37:24 DISCONNECTED） | 界面完全无变化（看门狗已自我关闭） | 立即回落 `CONNECTING(CONNECTION_LOST)`：压暗画面 + 「连接中断，正在重连…／已中断 N 秒」；8 s 未恢复 ⇒ `FAILED` + 重试 |
| 连上但下行停止（静止帧） | 视为正常通话 | 4 s 无新鲜下行 ⇒ 压暗 + 「画面已中断，正在恢复…」；再 8 s ⇒ `FAILED(REMOTE_FRAME_STALLED)` + 重试 |
| 远端首帧回调在未连接时触发 | 直接判定"已出画面" | 落盘 `remote_frame_ignored reason=not_connected`，UI 不上报 |

---

## §4 事件登记（沿用 `snake_case` + 模块 tag `pc`；既有事件名一个未删）

| 事件 | 等级 | 关键字段 |
|---|---|---|
| `ui_conn_state` | INFO | `phase`(connecting/connected/failed)、`reason`、`elapsed_ms`、`pair`、`frame`、`stalled`、`retry`、`seq`、`session` |
| `no_selected_pair` | WARN | `after_ms`、`pair=false`、`impl`、`avail_bps`、`up_bps`、`down_bps`、`local`、`remote`、`seq`、`session`（首次在超时瞬间落盘，之后每 15 s 一条） |
| `retry_invoked` | INFO | `room`、`role`、`phase`、`reason`、`elapsed_ms`、`retry`(第几次)、`seq`、`session`；无可重试通话时记 `result=ignored reason=no_active_call` |
| `retry_reoffer` | INFO | `role`、`mode`(immediate/delayed)、`delay_ms`、`peer_known`、`seq` |
| `retry_reoffer_run` | INFO | `role`、`delay_ms`、`seq`、`session`（延迟重发真正执行时） |
| `session_retry` | WARN | `seq`、`room`、`role`（重试路径关闭旧会话；`call_reinit` 仍用于换房间） |
| `remote_frame_ignored` | WARN | `reason=not_connected`、`phase`、`seq`、`session` |
| `call_init` | INFO | **新增字段 `reason`**(init/retry)，原有 `room/role/seq/session/pc_ready` 保留 |

> 全部为 INFO/WARN/ERROR（≥ DEBUG 阈值）⇒ 「阈值 DEBUG 下必落盘」满足。

---

## §5 单测与离线预检

新增 `app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt`（**14 个用例**，
纯 JVM，无 Android/Compose 依赖）：

| 用例 | 断言要点（对应验收） |
|---|---|
| `staysConnectingWithElapsedUntilPairAppears` | 未连上时保持 `CONNECTING` 且 `detail` 含"3 秒"（**明确连接状态 + 已用时长**） |
| `tickBeforeCallStartDoesNotAdvance` | 进房前的心跳不把进程耗时算作"已等待" |
| `noSelectedPairTimesOutToFailedAndOffersRetry` | **15 s 未连上 ⇒ `FAILED(TIMEOUT_NO_PAIR)` + `canRetry`**，文案「连接失败／15 秒内未能建立媒体通道…」 |
| `selectedPairMarksConnected` / `transportConnectedMarksConnected` | `mode=RELAY` / `transport=CONNECTED` ⇒ `CONNECTED`，且**仅凭候选对不算已收到画面** |
| `remoteFrameMarksReadyAndUnblocksPicture` | 收到远端帧 ⇒ `CONNECTED` 且 `remoteDimmed=false` |
| `lostAfterConnectedFallsBackThenFails` | 连上后掉线 ⇒ 立刻回落 `CONNECTING(CONNECTION_LOST)` + 遮罩；8 s 宽限未恢复 ⇒ `FAILED` + 可重试 |
| `recoveryWithinGraceKeepsCallAlive` | 宽限期内恢复 ⇒ 回到 `CONNECTED`，不误报失败 |
| `stalledRemoteFrameFallsBackAndEventuallyFails` | **4 s 无新鲜下行 ⇒ `remoteFrameReady=false` + 遮罩（静止帧不算画面）**；再 8 s ⇒ `FAILED(REMOTE_FRAME_STALLED)` |
| `stallIgnoresUpstreamOnlySamples` | 只有上行增长（真机 ≈49 kbps）**不得**刷新"画面新鲜度" |
| `retryResetsToConnectingAndCounts` | **重试 ⇒ 重新 `CONNECTING`、`retryCount+1`、计时归零**，并再次超时仍可重试 |
| `sessionFailureFailsImmediately` | 建会话失败 ⇒ 立刻 `FAILED(SESSION_START_FAILED)` |
| `parsesDisconnectedBeforeConnected` | `DISCONNECTED` 与 `CONNECTED` 的子串陷阱（10 组断言） |
| `retryNegotiationFollowsRoleAndPeerKnowledge` | 重试重协商决策（host 立刻 / joiner 延迟 / 对端未知等待 / 已收到对端协商则取消） |

**离线预检（宿主机 /tmp 一次性脚本，容器内无 JDK；非 Gradle）**：
- Kotlin **2.0.21** 编译器 + 真实 classpath（`android.jar`(SDK 34) + `third_party/libwebrtc/java/libwebrtc-java.jar`
  + 缓存/`transforms` 展开共 230 项）+ 序列化与 Compose 插件，整模块编译
  `app/src/main/kotlin` + `app/src/test/kotlin` **共 56 个 .kt**：**`EXIT=0`、`error:` 计数 = 0**；
  本任务 4 个文件（`ConnectionStatus.kt`/`CallViewModel.kt`/`CallScreen.kt`/测试）**无任何错误**。
- JUnit4 运行：新增测试类 **14/14 通过**；**全量 12 个测试类 `OK (106 tests)`**（t44/t45/t51/t53 既有用例无回归）。
- 过程记录（诚实披露）：第一版 `ConnectionStatusTracker` 用 `startedAtMs == 0L` 当"未开始"哨兵，
  被离线单测抓出（`onCallStarted(0L)` 时哨兵与真实时刻冲突 ⇒ 超时不触发），已改为显式 `active` 标志 +
  `hasFreshMedia` 布尔，重跑全绿。这正是把状态机抽成纯 Kotlin 的价值。
- Gradle 侧 `:app:testDebugUnitTest` / `assembleDebug` 与真机验证仍由 captain 的构建窗口执行
  （本成员不得运行 gradle）。

---

## §6 verify 命令原始输出

> 采集时刻：HEAD 见 §6.3；本任务改动尚未提交。

### V1
```
$ cd code/webrtc-demo && grep -n 'isRemoteVideoReady\|isConnecting\|pc_connection_state\|avail_bps\|local=-' \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt | head -25
CallViewModel.kt:133:    // `stats_sample … local=- mode=- remote=-`），但界面只把远端画面的最后一帧（静止帧）留在屏幕上，
CallViewModel.kt:144:     * 与 [CallUiState.isConnecting] 的关系：本字段是**唯一真源**，`isConnecting` 由它派生
CallViewModel.kt:312:            it.copy(roomId = roomId, role = role, isConnecting = true, error = null)
CallViewModel.kt:316:        // `publishConnStatus` 是 `CallUiState.isConnecting` 的**唯一写入方**（见其 KDoc）。
CallViewModel.kt:535:        // 【t59】只更新信令侧展示文案：`isConnecting`（连接中遮罩）已改由连接状态机唯一拥有 ——
CallViewModel.kt:660:                        it.copy(connectionState = "房间暂不可用，重试中…", isConnecting = true, error = null)
CallViewModel.kt:693:        // 【t59 根因修复】这里**删除**了旧的 `_uiState.update { isConnecting = false }`：
CallViewModel.kt:730:        //         _uiState.update { it.copy(isConnecting = false) }
CallViewModel.kt:821:     * 发布连接状态（**`isConnecting` 的唯一写入方**）。
CallViewModel.kt:825:     *  2. 由状态派生 `isConnecting`（CONNECTING ⇒ true）与 `isRemoteVideoReady`
CallViewModel.kt:835:                isConnecting = status.phase == ConnPhase.CONNECTING,
CallViewModel.kt:836:                isRemoteVideoReady = status.phase == ConnPhase.CONNECTED && status.remoteFrameReady,
CallViewModel.kt:864:     * 字段刻意与真机定因时用到的口径一致（`impl`/`avail_bps`/`up_bps`/`down_bps`），
CallViewModel.kt:879:                "avail_bps" to (stats?.availableOutgoingBitrateBps?.toString() ?: "-"),
CallViewModel.kt:981:     * 【t59】判定基准由 `isConnecting` 改为"连接未建立"：t59 后 `isConnecting` 由连接状态机拥有，
CallViewModel.kt:1009:        _uiState.update { it.copy(error = message, isConnecting = false) }
CallViewModel.kt:1028:                isConnecting = false,
CallScreen.kt:99:    // 与 `state.isConnecting` 不同，它是"是否真的连上"的唯一判定（`isConnecting` 也由它派生）。
CallScreen.kt:323:        // 【t39 修复②】会议号**常驻顶部覆盖层**：整个通话生命周期可见（原先只在 isConnecting
CallScreen.kt:448:        // 【t59】连接状态卡（替换原先只看 `state.isConnecting` 的遮罩）
EXIT=0
```
**读法**：`isConnecting` 现在只有 `publishConnStatus`（`:835`）与终态路径（`:1009`/`:1028`）、
新世代入口（`:312`）、`ROOM_FULL` 重试（`:660`）会写；旧根因三处（`onReady`/`onStats(impl)`/`onStateChanged(IN_CALL)`）
的写入已删除（保留注释说明，见 `:693`/`:730`）。`isRemoteVideoReady` 由 `:836` 单点派生，
其值为 `CONNECTED && remoteFrameReady` ⇒ **未连上/画面停滞即回落**。

### V2
```
$ cd code/webrtc-demo && grep -rn 'ui_conn_state\|retry_invoked\|no_selected_pair' app/src/main/kotlin/com/example/webrtcdemo/ | head -10
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:149:    /** 最近一次 stats（`no_selected_pair` 诊断要带上"当时到底看到了什么"）。 */
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:153:    /** 上一次 `no_selected_pair` 落盘时刻（按 [NO_PAIR_LOG_INTERVAL_MS] 限频）。 */
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:255:                "retry_invoked",
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:268:            "retry_invoked",
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:827:     *  3. 阶段/原因变化时落盘 `ui_conn_state`（阈值 DEBUG 下必落盘）；
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:828:     *  4. 停在"没有选中候选对"的失败态时按 [NO_PAIR_LOG_INTERVAL_MS] 限频落盘 `no_selected_pair`。
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:842:                "ui_conn_state",
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:862:     * 落盘"没有选中的候选对"诊断（`no_selected_pair after_ms=N`）。
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:874:            "no_selected_pair",
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1045:        /** 【t59】`no_selected_pair` 诊断的限频间隔（首次在超时瞬间落盘）。 */
EXIT=0
```
**读法**：三个事件都落在 inScope 的 `ui/call/CallViewModel.kt`：`ui_conn_state` 落盘点 `:842`、
`no_selected_pair` 落盘点 `:874`、`retry_invoked` 两处 `:255`/`:268`（忽略/实际执行）。

### V3
```
$ cd code/webrtc-demo && ls -l reports/29-connect-state-ui.md && git status --porcelain
-rw-r--r-- 1 node node 32602 Sep 15 20:52 reports/29-connect-state-ui.md
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt
?? app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt
?? app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt
?? reports/29-connect-state-ui.md
（其余为 captain/其他成员的本轮产物：scripts/t5*、reports/05-*、reports/26-* 等，非本任务）
```
> 注：size/时间 为采集时刻值；本报告随后被追加过这一行输出，最终 size/mtime/sha256 以交付消息为准
> （mode 恒为 `644`）。V3 中属于本任务的 5 条即全部改动，**均在 inScope 内**。

### 6.3 版本上下文（采集时）
```
$ git log --oneline -1
eb4b768 fix(native): t57 修复自研 VP9 ①对端逆时针 90° ②卡顿严重 …（报告 reports/27-encoder-direction-perf.md）
```
> 本任务改动**尚未提交**（未获提交授权）；基线即为该 HEAD 版本的 `CallViewModel.kt`/`CallScreen.kt`。

---

## §7 未验证项（诚实清单）

| # | 未验证项 | 为什么没验 | 复测方式 |
|---|---|---|---|
| U1 | **真机 4G↔WiFi 的一键重试成功率** | 本成员无真机/无设备权限；容器无 JDK/SDK | §7.1 清单第 4 步 |
| U2 | 15 s 失败提示与重试按钮的真机观感（Compose 布局/文案在真实屏上的可读性） | 同上（无设备、无截图能力） | §7.1 第 2–3 步 |
| U3 | Gradle `:app:testDebugUnitTest` / `assembleDebug` 通过 | 容器无 JDK/SDK；构建窗口归 captain | captain 构建窗口（本报告提供离线预检证据） |
| U4 | 重试后 joiner 主动 offer 与对端 `onRemoteOffer` 的实测兼容性（glare/重复协商） | 需两端真机 + 服务端 | §7.1 第 4 步：看两端 `retry_reoffer*` 与 `offer_create`/`answer_create` 是否成对；若出现 `offer_create_failed` 说明撞上 glare（可调大 `RETRY_REOFFER_DELAY_MS`） |
| U5 | `REMOTE_FRAME_STALLED` 阈值（4 s/8 s）在弱网下的误报率 | 需真机弱网 | §7.1 第 5 步：弱网下观察是否出现"画面已中断"但实际仍在播放 |
| U6 | 服务端/中继侧根因（`CREATE_PERMISSION 403` 等） | **明确不是本任务范围**（t58） | t58 |

### 7.1 真机复测清单（供 captain 交付前执行）

1. 4G（host）↔ WiFi（joiner）通话，**保持 t58 修复前的复现条件**：预期（若中继仍不可用）
   进房后界面显示「正在建立连接…／已等待 N 秒」，远端画面被压暗，**不出现静止帧假象**。
2. 等 15 s：预期出现「连接失败／15 秒内未能建立媒体通道（NAT/防火墙可能阻断了候选对）」+「点击重试」；
   同时 `app.log` 出现 `ui_conn_state phase=failed reason=timeout_no_pair elapsed_ms≈15xxx`
   与 `no_selected_pair after_ms≈15xxx pair=false impl=SelfVp9Libvpx avail_bps=0 up_bps≈49xxx down_bps=0`。
3. 点「点击重试」：预期 `retry_invoked`（含 `retry=1`）→ `session_retry` → 旧会话 `pc_closed`（`phase=CLOSED`、
   `stats_loop=stopped`）→ 新 `pc_starting`/`pc_created`（**新 `session` 编号**）→ 状态回到「正在建立连接…」
   且**计时从 0 重新开始**。
4. 重试后的协商核对：host 侧 `retry_reoffer mode=immediate` + `offer_create`；joiner 侧
   `retry_reoffer mode=delayed` → （若对端未发）`retry_reoffer_run` + `offer_create`；
   两端 `pc_connection_state dtls=true state=CONNECTED` ⇒ `ui_conn_state phase=connected`
   且 `remote_frame_ready`（画面解遮罩）。
5. 中途拔网/切飞行模式制造"曾连上又断"：预期 2–4 s 内画面被压暗并显示「连接中断，正在重连…」，
   8 s 未恢复转「连接已断开」+「点击重试」；恢复网络后若自动重连成功应回到正常（无假失败）。
6. 一直连通时的回归：正常通话全程**不应**出现任何状态卡或遮罩（`ui_conn_state phase=connected` 只落一次）。

---

## §8 边界、交互与遗留

- **未触碰**：`app/src/main/cpp/**`、`config/**`、`webrtc/**`（`CallSession`/`WebRtcEngine` 一行未改）、
  `nat/**`、`doc/**`（冻结）、`third_party/**`、`scripts/**`。全部改动落在 `ui/call/**` + `app/src/test/**`。
- **与 t53 的关系**：重试直接复用 t53 的世代化机制（`CallSessionSlot.beginCall` + `releaseSession` +
  无条件新建 `CallSession`），因此"重试 = 新会话 + 新 PC"由 t53 的不变量保证（t53 单测
  `consecutiveCallsAlwaysUseFreshSessionAndClosePrevious` 已覆盖）。
- **与 t51 的关系**：`armPeerResponseWatchdog`（20 s 对端无响应）判活基准改为"连接未建立"，
  避免 t59 的 15 s 失败把它连带禁用（否则是行为回退）；t51 的文案与事件名未变。
- **与 t44 的关系**：**未改** `CallSession` 的 15 s/30 s ICE 看门狗。已知其局限（首次 CONNECTED 后自我关闭，
  见 §2.3 R6）由 t59 的 UI 状态机在**上层**补上（不依赖该看门狗）；若后续要让看门狗也覆盖"连上后再断"，
  建议在 `CallSession` 内做（属 `webrtc/` 范围，本轮刻意不动）。
- **重试的作用域（重要）**：重试只重建**媒体会话**并重新协商，**不** `leave()`/重新 `join`——
  这是刻意的：`leave()` 会让对端收到 `peerLeft` 而被连带挂断（doc/14 §8.5 口径 B）。
  因此若底层原因（t58 的 TURN 权限/中继分配）仍存在，重试会**再次失败**并再次给出失败提示 ——
  这是诚实的行为（可重复诊断），不是"重试无效"。
- **遗留/后续建议**：
  1. 新增文案内联在 Kotlin（`ConnStatus.title/detail`、`RETRY_LABEL`、`WAITING_REMOTE_FRAME_NOTICE`），
     inScope 不含 `res/values/strings.xml`；建议后续统一迁到字符串资源（i18n）；
  2. `remoteFrameReady` 的"新鲜度"目前用 `down_bps > 0`（RTP 任意媒体）作代理：无法区分"只有音频在流"；
     若后续要精确到视频，可在 `VideoRendererPool` 暴露"最近一次渲染帧时刻"（属 `webrtc/` 范围）。
  3. `CallUiState.connectionState`（信令文案）与新的 `ConnStatus`（媒体连接）是两个不同维度，UI 上建议
     后续把二者合并展示（如"信令：通话中／媒体：连接失败"），本轮保持既有展示不变以避免大改。
