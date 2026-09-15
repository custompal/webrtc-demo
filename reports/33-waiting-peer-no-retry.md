# t64 报告：等待对端加入期间不提示"连接中/重试" —— 新增 `waiting_peer` 态

- 任务：t64「等待对端加入期间不提示连接中/重试：新增 waiting_peer 态，仅 peerJoined（joiner: joined）后启动重试计时，peerLeft 复位」
- 归属：android-dev（attempt 3）
- 范围：`ui/call/**`（`ConnectionStatus.kt`、`CallViewModel.kt`；`CallScreen.kt` **无需改**，卡片文案由状态机提供）、
  `app/src/test/**`、本报告
- **未触碰**：`webrtc/**`（t63 已收口）、`cpp/**`、`nat/**`、`config/**`、`doc/**`（冻结）、`third_party/**`、`scripts/**`

---

## §0 摘要（结论先行）

| 项 | 结论 |
|---|---|
| **用户需求** | 「创建会议后等待对方进入会议前不要提示在等待并重试，只有对方进入房间后才启动重试机制。」 |
| **现状缺陷** | t59/t61 的两档计时从**会话开始**（或 ICE 收集完成）起算 ⇒ 房主创建房间后独自等待时，界面会走「连接中 → 15 s 可重试 → 30 s 失败卡」，把"对端还没来"误报成"连接失败" |
| **修复** | 新增显式 phase **`WAITING_PEER`**：开局即进入该态（**不计时、不提示重试/失败**，显示「等待对方加入」+ 会议号 + 复制入口）；收到对端（host `peerJoined` / joiner `joined` 或首个 `offer`）才 `startRetryClock` 起算两档计时；`peerLeft` ⇒ 停表复位回 `WAITING_PEER` |
| **诊断** | 新增 `ui_conn_state_enter phase=waiting_peer reason=peer_absent`、`retry_clock_started trigger=peer_joined\|joined\|offer waited_ms=N`、`retry_clock_reset reason=peer_left`、`peer_left_keep_waiting`；等待期 `ui_conn_state phase=waiting_peer reason=none retry=0`（不推进 `elapsed_ms`）；既有事件名一个未删改名 |
| **阈值/口径** | **沿用** t59/t61：15 s 仅给"（可点击重试）"提示、30 s 才 `FAILED`；只是**起点**改为"收到对端" |
| **验证** | 纯 JVM 单测 **124 tests OK**（t63 基线 120 → **+4**，其中 3 条为验收点名单测）；离线整模块编译 **EXIT=0 / 0 error**；契约 3 条 verify 全 EXIT=0 |

---

## §1 需求与现状问题

### 1.1 用户原话（任务书引用）

> "创建会议后等待对方进入会议前不要提示在等待并重试，只有对方进入房间后才启动重试机制。"

### 1.2 现状（t63 基线 `bac78a2` + t63 改动）

- 房主创建房间后立刻 `beginGeneration(room, role, REASON_INIT)` ⇒ `connTracker.onCallStarted(now)` ⇒ `phase=CONNECTING`，
  `elapsedMs` 从**进房那一刻**开始累加；对端未到时 15 s 出现"（可点击重试）"、30 s 进 `FAILED(TIMEOUT_NO_PAIR)` + 失败卡。
- 真机日志可佐证：`peerJoined` 之前就已有 `ui_conn_state … phase=connecting` 输出（captain 取证）。
- 语义上"对端还没来"与"连接失败"是两件事：前者不该有任何重试/失败暗示。

---

## §2 修复

### 2.1 状态机（`ui/call/ConnectionStatus.kt`）

| 改动 | 说明 |
|---|---|
| `ConnPhase` 新增 **`WAITING_PEER`** | 与 `CONNECTING/CONNECTED/FAILED` 并列；KDoc 明确"此态不计时、不给重试/失败" |
| `ConnStatus.title` | `WAITING_PEER -> "等待对方加入"`（**不含"连接"字样**）；`detail -> "对方进入房间后自动开始建立连接"`（**不提时长、不提重试**） |
| `ConnStatus.canRetry` | 保持 `FAILED ‖ (CONNECTING ∧ retryHintReached)` ⇒ 等待态**必然 false**（无重试入口） |
| `ConnStatus.showOverlay` | 保持 `phase != CONNECTED` ⇒ 等待态仍显示状态卡（含会议号） |
| `onWaitingPeer(nowMs)` | **新增**：进入/复位等待态 —— `elapsedMs=0`、`retryCount=0`、`retryHintReached=false`、`retryClockRunning=false`、`waitingSinceMs=now` |
| `startRetryClock(nowMs)` | **新增**：等待态 ⇒ `CONNECTING`，`startedAtMs=now`、`elapsedMs=0`、`gatheringCompleteAtMs=0`（新一轮）；**幂等**（已计时/已连上/已失败时不重置） |
| `isRetryClockRunning` | **新增**只读属性（等待期必须为 false；供单测/诊断） |
| `waitingMs(nowMs)` | **新增**：等待时长，**仅诊断**（不参与任何超时判定） |
| `onTick` 新增 `WAITING_PEER` 分支 | **不推进 `elapsedMs`、不判超时、不置 `retryHintReached`** —— 这是"不计时"的实现点；仅同步 `mediaSource/mediaAgeMs/iceDown` 诊断字段 |
| `onCallStarted`/`onRetry` | 置 `retryClockRunning = true`（走"立即起算"路径时） |

### 2.2 `ui/call/CallViewModel.kt`

| 改动 | 说明 |
|---|---|
| `beginGeneration` 的三分支起点 | `REASON_RETRY -> onRetry()`（立即计时）；`peerKnownBefore -> onCallStarted()`（立即计时）；**否则 `onWaitingPeer()`**（等待对端，不计时）＋ 落盘 `ui_conn_state_enter phase=waiting_peer reason=peer_absent room=… role=… seq=…` |
| `startRetryClockIfWaiting(trigger)` | **新增**：仅当处于 `WAITING_PEER` 时启动计时，落盘 `retry_clock_started trigger=… waited_ms=… role=… seq=… session=…`（**幂等**，重复触发不归零） |
| `Joined` 分支 | 触发 `startRetryClockIfWaiting("joined")`（joiner 侧起点之一） |
| `PeerJoined` 分支 | 触发 `startRetryClockIfWaiting("peer_joined")`（host 侧起点） |
| `Offer` 分支 | 触发 `startRetryClockIfWaiting("offer")`（joiner 侧起点之二；也覆盖 host 收到 offer 的异常路径） |
| `resetRetryClock(reason)` | **新增**：`onWaitingPeer()` + 落盘 `retry_clock_reset reason=…` |
| `PeerLeft` 分支 | **先** `resetRetryClock("peer_left")`（停表复位，界面回到「等待对方加入」）；**若本世代真的连上过**（`everConnectedInGeneration`）则保持既有口径 B 终态挂断并回首页，否则落盘 `peer_left_keep_waiting reason=never_connected` 并**留在通话页**等下一个设备 |
| `everConnectedInGeneration` | 新增（在 `publishConnStatus` 中当 `phase == CONNECTED` 置真、换代复位）——用于**精确界定**上面的口径 B 适用范围 |

### 2.3 `CallScreen.kt`

**无需改动**（复核结论）：状态卡的标题/副标题来自 `conn.title`/`conn.detail`（t59/t63 已按 `conn.phase` 渲染），
`CircularProgressIndicator` 仅在 `phase == CONNECTING` 时显示、重试按钮仅在 `conn.canRetry` 时显示 ⇒
等待态自动是"无转圈 + 无重试按钮 + 显示「等待对方加入」"；会议号覆盖层与复制按钮是**常驻元素**（t39），照旧可用。

### 2.4 与 doc/14 §8.5 口径 B 的关系（**需要 captain 复核的一处判断**）

口径 B 的原文语义是「**IN_CALL** 收到 peerLeft 立即转 DISCONNECTED 并挂断 —— 不留半死不活的状态」。
本任务把它**精确化**为"**真的连上过**（本世代 `phase` 曾为 CONNECTED）才终态挂断"：

- 失败/等待阶段对端离开（本缺陷链的真实场景：对端加入 → 尝试建连失败 → 对端退房）⇒ **不再被踢回首页**，
  而是回到「等待对方加入」，房主可继续等下一个设备；用户需求正是"等待期不要被当成失败"。
- 已成功通话后对端离开 ⇒ 保持口径 B（挂断 + 回首页 + 明确提示）——**行为未变**。
- 残留（如实登记）：**已连上过但随后对端离开**时仍回首页，此时不会停留在等待态。
  若 captain 要求"任何 peerLeft 都留在等待态"，只需把 `PeerLeft` 分支的 `if (everConnectedInGeneration) hangup()`
  去掉即可（一行；本轮按"最小改动 + 不越权改冻结口径"处理）。

---

## §3 修正前后行为对照

| 场景 | 修正前（t63） | 修正后（t64） |
|---|---|---|
| 房主创建房间，独自等待 | `phase=connecting`，15 s 出现"（可点击重试）"、30 s 失败卡 | **`phase=waiting_peer`**：「等待对方加入／对方进入房间后自动开始建立连接」，**无计时、无重试、无失败** |
| 等待 65 s 仍无人 | 早已 `FAILED` | 仍 `waiting_peer`（单测 `waitingPeerNeverTimesOutNorOffersRetry` 钉死） |
| 对端加入（host: peerJoined） | 计时从进房起算（可能已耗尽） | **从 peerJoined 起算**：+15 s 提示可重试、+30 s 失败（单测 `retryClockStartsOnlyAfterPeerArrives`） |
| joiner 加入（收到 joined/offer） | 计时从进房起算 | **从 joined/offer 起算** |
| 对端离开（从未连上过） | 直接挂断回首页 | **停表复位回 `waiting_peer`**，留在通话页等下一个设备（单测 `peerLeftResetsToWaitingAndClockCanRestart`） |
| 对端离开（已成功通话过） | 挂断回首页 | **不变**（口径 B 保留） |
| 界面文案 | 连接中/失败/点击重试 | 等待期只有「等待对方加入」，无"连接/失败/重试"字样 |

---

## §4 诊断事件（新增；既有事件名一个未删改名）

| 事件 | 等级 | 关键字段 |
|---|---|---|
| `ui_conn_state_enter` | INFO | `phase=waiting_peer`、`reason=peer_absent`、`room`、`role`、`seq`（开局进入等待态时落盘） |
| `retry_clock_started` | INFO | `trigger=peer_joined\|joined\|offer`、`waited_ms`（等待了多久才起算）、`role`、`seq`、`session` |
| `retry_clock_reset` | INFO | `reason=peer_left`、`role`、`seq`、`session` |
| `peer_left_keep_waiting` | INFO | `reason=never_connected`、`seq`、`session`（未连上过的对端离开 ⇒ 留在等待态） |
| `ui_conn_state` | INFO | 等待期输出 `phase=waiting_peer reason=none retry=0`、`elapsed_ms=0`；既有字段（`pair/frame/stalled/media_source/media_age_ms/ice_down/…`）全部保留 |
| 既有 | — | `peer_joined`/`peer_left`/`rejoined`/`offer_received`/`answer_timeout`/`no_selected_pair`/`retry_invoked`/`ice_regather_invoked`/`remote_frame_liveness`/`ice_flap_suppressed` 等**均未改名、未删除** |

> 验收判据依赖关系：`no_selected_pair`/`retry_invoked`/`retry_reoffer*`/`ui_conn_state phase=failed` **只可能**在
> `FAILED`（即计时已启动并耗尽）时出现，而 `FAILED` 只能由已启动的计时在等待态之外到达 ⇒
> 等待期天然不会产生这些事件。

---

## §5 单测（纯 JVM）

`app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt` 新增 **4 用例**
（该文件 23 → 27；全量 **120 → 124**）：

| 用例 | 断言（对应验收） |
|---|---|
| **`waitingPeerNeverTimesOutNorOffersRetry`**（点名①） | `onWaitingPeer` 后推进到 `16 s / 31 s / 66 s` 三个时刻：**始终** `WAITING_PEER`、`reason=NONE`、`elapsedMs=0`、`retryCount=0`、`retryHintReached=false`、`canRetry=false`；标题为「等待对方加入」且不含"重试"；`waitingMs` 仅作诊断 |
| **`retryClockStartsOnlyAfterPeerArrives`**（点名②） | 等待 60 s 不动；`startRetryClock(60 s)` 后：+14 s 无提示、**+15 s `retryHintReached` 且仍 CONNECTING**、**+30 s `FAILED(TIMEOUT_NO_PAIR)`**；重复触发不重置（幂等） |
| **`peerLeftResetsToWaitingAndClockCanRestart`**（点名③） | 失败后用 `onWaitingPeer` 复位 ⇒ `WAITING_PEER`/`reason=NONE`/`retryCount=0`/`canRetry=false`/`isRetryClockRunning=false`；再等 50 s 仍不超时；对端再次加入 ⇒ 新一轮窗口从此刻起算（+15 s 提示、+30 s 失败） |
| `waitingPeerIsNotConnectingAndHasNoRetryEntry` | 等待态 `phase != CONNECTING`（界面不显示"连接中"）、无重试入口、`retryCount=0`、标题含"等待" |

**离线预检（宿主机 /tmp；容器内无 JDK，非 Gradle）**：Kotlin 2.0.21 + 真实 classpath（android.jar(SDK34) +
`third_party/libwebrtc/java/libwebrtc-java.jar` + 230 项依赖）+ 序列化/Compose 插件，整模块编译
`app/src/main/kotlin`+`app/src/test/kotlin` ⇒ **`EXIT=0`、`error:` 计数 0**；JUnit4 **全量 13 类 `OK (124 tests)`** 无回归。
Gradle `:app:testDebugUnitTest`/`assembleDebug` 与真机由 captain 构建窗口执行。

---

## §6 verify 命令原始输出

### V1
```
$ cd code/webrtc-demo && grep -n 'waiting_peer\|retry_clock_started\|peerJoined\|peerLeft\|TIMEOUT_NO_PAIR' \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt | head -25
CallViewModel.kt:38://   2. 信令编排（doc/09 §5）：peerJoined→createOffer、offer→answer、answer/ice/natType；
CallViewModel.kt:101:    private var peerJoined = false
CallViewModel.kt:175:     * 用途：`peerLeft` 时的收尾口径 —— 见 [onMessage] 的 `PeerLeft` 分支。
CallViewModel.kt:179:    /** 【t64】等待态起点（仅用于 `retry_clock_started` 的 `waited_ms` 诊断）。 */
CallViewModel.kt:214:        // `FAILED(TIMEOUT_NO_PAIR)` ⇒ 界面显示可操作失败提示 + 一键重试（见 CallScreen）。
CallViewModel.kt:221:                // NO_RELAY_CANDIDATE（而不是笼统的 TIMEOUT_NO_PAIR）。
CallViewModel.kt:311:     * `peerLeft` 而被连带挂断）。
CallViewModel.kt:401:                    "phase" to "waiting_peer",
CallViewModel.kt:451:                    // host：沿用既有 `peerJoined → createOffer` 正常路径
CallViewModel.kt:452:                    peerJoined = true
CallViewModel.kt:642:                // 服务端保留房间、对端会收到新的 peerJoined，因此发起方必须重新走 offer/answer；
CallViewModel.kt:648:                    peerJoined = true
CallViewModel.kt:654:                peerJoined = true
CallViewModel.kt:938:        peerJoined = false
CallViewModel.kt:969:    /** host 侧：只有「已收到 peerJoined」且「会话已就绪」时才发 offer（避免顺序问题）。 */
CallViewModel.kt:971:        if (role != ROLE_HOST || !peerJoined || !sessionReady) return
CallViewModel.kt:986:     * 触发点（由调用方给出）：host = `peerJoined`；joiner = `joined` 或首个 `offer`。
CallViewModel.kt:989:     * @param trigger 触发来源，写入 `retry_clock_started trigger=…` 供复测自证。
CallViewModel.kt:999:            "retry_clock_started",
CallViewModel.kt:1011:     * 【t64】停表并复位回「等待对方加入」（`peerLeft`/对端离开时调用）。
CallViewModel.kt:1044:        // 【t64】记住"本世代是否真的连上过"（决定 peerLeft 时是终态挂断还是回等待态）
CallViewModel.kt:1073:        if (status.phase == ConnPhase.FAILED && status.reason == ConnReason.TIMEOUT_NO_PAIR) {
CallViewModel.kt:1117:                peerJoined = true
ConnectionStatus.kt:31:     * 由 `peerJoined`（host）/ `joined`（joiner）/ 首个 `offer` 触发切换到 [CONNECTING] 并开始两档计时。
ConnectionStatus.kt:51:    TIMEOUT_NO_PAIR,
EXIT=0
```
**读法**：`CallViewModel.kt:401` = 开局进入等待态的 `phase=waiting_peer` 落盘；`:999` = `retry_clock_started`
（`:986-989` 为其触发点 KDoc：host=peerJoined / joiner=joined|offer）；`ConnectionStatus.kt:31` =
`WAITING_PEER` 的 KDoc（枚举项在 `:33`，被 `head -25` 截断）；`:1073` = `no_selected_pair` 仍然**只在 FAILED** 触发。

### V2
```
$ cd code/webrtc-demo && grep -rn 'waiting_peer' app/src/test/ | head -10
app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt:455:    // 对应日志形态：等待期 `ui_conn_state phase=waiting_peer reason=none retry=0`（不推进 elapsed_ms）；
app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt:457:    // 对端离开后 `retry_clock_reset reason=peer_left` 并回到 `phase=waiting_peer`。
app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt:461:     * 房主创建房间后（对端未加入，`phase=waiting_peer`）无论推进多久，都要停在 `WAITING_PEER`、
app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt:528:     * 【t64③ 点名单测】**对端离开则复位**（`retry_clock_reset`）：`peerLeft` ⇒ 停表、回 `waiting_peer` 态、不显示失败/重试；
EXIT=0
```
**读法**：t64 的四条用例集中在同一文件的 `t64：等待对端期间不计时/不给重试` 分节（`:455-560` 区间），
其中三条为验收点名单测（`waitingPeerNeverTimesOutNorOffersRetry` / `retryClockStartsOnlyAfterPeerArrives` /
`peerLeftResetsToWaitingAndClockCanRestart`）。

### V3
```
$ cd code/webrtc-demo && ls -l reports/33-waiting-peer-no-retry.md && git status --porcelain
-rw-r--r-- 1 node node 21020 Sep 15 22:04 reports/33-waiting-peer-no-retry.md
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt
 M app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt
?? reports/33-waiting-peer-no-retry.md
（另有 t63 的未提交产物：` M webrtc/VideoRendererPool.kt`、`?? reports/32-remote-frame-liveness.md` —— 属 t63；
 以及 captain/其他成员产物 `reports/05-*`、`scripts/t5*`、`reports/10-t47b-*` 等，非本任务）
```
> mode 恒 `644`；size/mtime/sha256 以交付消息为准。

### 版本上下文（采集时）
```
$ git log --oneline -1
bac78a2 fix(android): t60+t61 4G/中继健壮性与连接可见性——…；全量 115 tests
```
> 本任务改动（含 t63 的未提交改动）尚未提交（未获提交授权）。

---

## §7 未验证项（诚实清单）

| # | 未验证项 | 为什么没验 | 复测方式 |
|---|---|---|---|
| U1 | **真机：房主独自等待 ≥60 s 不出现重试提示** | 本成员无真机、无截图能力 | 创建房间后什么都不做等 60 s：界面应只有「等待对方加入」+ 会议号；日志应只有 `ui_conn_state_enter phase=waiting_peer`，**不得**出现 `ui_conn_state phase=failed`、`retry_invoked`、`no_selected_pair`、`retry_reoffer*` |
| U2 | **真机：对端加入后 15/30 s 两档如期出现** | 同上 | 第二台设备加入后核对 `retry_clock_started trigger=peer_joined`（host）或 `trigger=joined/offer`（joiner）的时间戳，及其后 +15 s 的"（可点击重试）"与 +30 s 的失败卡 |
| U3 | **对端离开后回到等待态的真机表现**（含"房间是否仍存在"） | 需真机 + 服务端 | 对端退出后核对 `retry_clock_reset reason=peer_left` + `peer_left_keep_waiting reason=never_connected`，界面停在「等待对方加入」；随后第三台设备加入是否能重新走通（若服务端已销毁房间则会给 `ROOM_NOT_FOUND`，需据实记录） |
| U4 | 口径 B 的精确化是否被 captain 认可（见 §2.4） | 属口径判断，非技术缺陷 | captain 一句话即可：保留"已连上过才挂断"或改为"任何 peerLeft 都留在等待态" |
| U5 | Gradle 全量单测/构建 | 容器无 JDK/SDK；构建窗口归 captain | captain 构建窗口（本报告提供离线预检证据：124 tests OK、整模块 0 error） |

### 7.1 真机复测清单（供 captain 交付前执行）

1. 房主创建房间，**保持 60 s 不做任何操作**：界面仅「等待对方加入」+ 会议号 + 复制入口；无转圈、无重试按钮、无失败卡。
2. 核对日志（等待期）：`ui_conn_state_enter phase=waiting_peer reason=peer_absent`（一条）＋ 周期性 `ui_conn_state phase=waiting_peer reason=none retry=0 elapsed_ms=0`；
   **不得**出现 `phase=failed`/`retry_invoked`/`no_selected_pair`/`retry_reoffer*`。
3. 第二台设备加入：核对 `retry_clock_started trigger=peer_joined`（host 侧）/`trigger=joined` 或 `offer`（joiner 侧），
   其后 +15 s 出现"（可点击重试）"、+30 s 出现失败卡（阈值与 t59/t61 一致）。
4. 让第二台设备**在未连上时**退出：核对 `retry_clock_reset reason=peer_left` + `peer_left_keep_waiting reason=never_connected`，
   界面回到「等待对方加入」；再让第三台设备加入，确认能重新计时（无上一轮残留）。
5. 让第二台设备**成功通话后**退出：确认仍按口径 B 结束并回首页（行为未变）。

---

## §8 边界与遗留

- **未改 `webrtc/**`**：本任务纯 `ui/call` 状态机与信令触发点，未触碰 `CallSession`/渲染器。
- **未改 `CallScreen.kt`**：`WAITING_PEER` 的呈现完全由 `conn.title/detail/canRetry/phase` 驱动，无需新分支
  （复核结论见 §2.3）。
- **`isConnecting` 仍单点派生**：`publishConnStatus` 中 `isConnecting = phase == CONNECTING` ⇒ 等待态为 false，
  界面不会出现"连接中"遮罩；`isRemoteVideoReady` 仍为 `CONNECTED && remoteFrameReady`。
- **计时器仍只有一套**：等待态不新增第二个计时器；`startedAtMs` 在 `startRetryClock` 时才被赋值为"收到对端"的时刻，
  因此 15 s/30 s 两档语义与 t59/t61 **完全一致**，只是起点变了。
- **遗留建议**：
  1. 若后续要显示"已等待 N 秒"（等待期），可直接用 `waitingMs()`——它刻意不参与超时判定；
  2. `joinRoom` 侧若服务端支持"房间有人在等"的回执，可把 joiner 的起点更早地精确到服务端事件（当前用 `joined`/`offer`）。
