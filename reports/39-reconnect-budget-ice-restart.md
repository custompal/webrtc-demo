# t71 报告：信令重连预算与 90 s 宽限期对齐 + 重连后 ICE restart 接线

- 任务：t71（implementation r1，attempt 1，attempt_id `ceea1b6a-5c16-4ea2-9f8c-656e33d7107c`）
- 承办：android-dev
- 依据：captain 2026-09-16 裁定 + t71 任务书 + 附件（go-dev `reports/35-room-grace.md` §5.3）
- inScope：`app/src/main/kotlin/.../signaling/`、`.../ui/call/`、`.../webrtc/`（**本轮零改动**）、`app/src/test/kotlin/`、本报告
- 明确未触碰：`app/src/main/cpp/**`、`signaling/`（Go）、`doc/14-interface-contract.md`
- 本任务**未执行**任何宿主机 Gradle 构建/发布（构建属 t72）

> **t74 同步（2026-09-16，env-installer）**：本报告所登记的实现事实不变；仅**断言上限**由 `< 90 s` 收紧为 **`< 75 s`**（依据同一份附件 go-dev `reports/35 §5.3`「服务端察觉断开」起点 ⇒ 显式关旧 socket 时可用预算 ≤75 s），对应测试文件为 `app/src/test/.../signaling/ReconnectBudgetTest.kt`。详见 **§5.3**（离线复跑 + old-red 对照）与 §3/§4 行内标注。

---

## 0. 摘要

修复两处与 t67/t70 服务端口径不匹配的客户端行为：

| # | 缺口（修复前） | 后果 | 修复 |
|---|---|---|---|
| ① | socket 重连 = `RECONNECT_DELAY_MS=3_000 × MAX_RECONNECT_ATTEMPTS=3` ≈ **9–12 s**，3 次失败即 `disconnectCause=FATAL` → 通话页 `endCallNow()` | 服务端按 t67 保留席位 **90 s**、宽限期内**不发 peerLeft**、RTP 仍可能流动时，客户端**自杀式退房** | 改用**既有 rejoin 退避口径**：`rejoinDelayMs`（1/2/4/8 s 封顶）×`MAX_REJOIN_ATTEMPTS`(10) ⇒ **总预算 63 s**（≥60 s 且 **<75 s**，t74 口径）；耗尽改为 `SIGNAL_LOST`（可存活），按 `mediaAlive` 分派 |
| ③ | 重连入会成功后**未接线** ICE 恢复（`CallSession.restartIce()` 只挂在 t60 的 NO_RELAY_CANDIDATE 路径） | WiFi↔4G 切换后「信令已恢复、画面黑且不再自愈」 | 新增纯判定 `shouldRestartIceOnRejoin(...)` + VM 在 `joined`/`peerJoined` 后触发复用既有 `restartIce()`；诊断 `restart_ice reason=rejoin` |

**离线预检（仓库树，宿主机 kotlinc-embeddable 直编 + JUnit4，未跑 Gradle）**：`MAIN_RC=0` / `TEST_RC=0` / **18 类 / 176 用例全绿**（t68 基线 17 类/165 ⇒ +1 类/+11 例）。
old-red 对照：把 `MAX_REJOIN_ATTEMPTS`/`rejoinDelayMs` 回退为修复前语义（3 次、固定 3 s）⇒ **8 个失败**（见 §5）。

---

## 1. 缺口定因（file:line，均为修复前）

- 重连预算与上限：`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:121-122`（`RECONNECT_DELAY_MS=3_000L`、`MAX_RECONNECT_ATTEMPTS=3`）；重连调度 `:706-716`（`scheduler.schedule(..., RECONNECT_DELAY_MS, ...)`）。
- 耗尽即终态：`SignalingClient.kt:706-713`（`disconnectCause = FATAL` + `setState(DISCONNECTED)`）。
- 终态 → 退出通话页：`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:713-740`（`else -> endCallNow(NOTICE_CALL_ENDED)`）。
- 服务端对照：go-dev `reports/35-room-grace.md` §5.3（宽限期 90 s；**计时起点 = 服务端察觉断开**；客户端显式关闭旧 socket ⇒ 察觉 ≈0 ⇒ 可用预算 ≤75 s；静默不关 ⇒ 只剩 45 s）。
- 本端三条重连路径**都先拆旧 socket**（满足"显式关闭"前置，d 见 §3.3）：

## 2. 实现

### 2.1 ① 重连预算 = 复用 rejoin 口径（不造平行常量）

`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt`

| 内容 | file:line |
|---|---|
| socket 重连**不另设常量** :120-125 .| :120-125 |
| `REJOIN_RETRY_BASE_MS = 1_000L`（既有） | :139 |
| `REJOIN_RETRY_MAX_MS = 8_000L`（既有，单次上限） | :142 |
| `MAX_REJOIN_ATTEMPTS = 10`（既有；socket 重连**共用**） | :145 |
| 纯函数 `rejoinDelayMs(attempt)`：1/2/4/8 s 封顶（既有，公开供单测） | :156-160 |
| 纯函数 `rejoinBudgetMs(maxAttempts = 10)`：**累计 63 s** | :163-168 |
| `scheduleReconnect`：次数上限用 `MAX_REJOIN_ATTEMPTS`；延迟用 `rejoinDelayMs(attempt)` | :730（判定）、:748（延迟） |
| 耗尽 ⇒ `DisconnectCause.SIGNAL_LOST`（**不再 FATAL**）+ 落 `budget_ms`/`in_room_before_drop`/`room` | :730-745（`SIGNAL_LOST` :744） |
| `ws_reconnect_scheduled` 增 `delay_ms`/`budget_ms`/`max_attempts` | :749-762 |
| `ws_rejoin_give_up`（ROOM_FULL 退避耗尽）同样改置 `SIGNAL_LOST` + `budget_ms` | :683-700 |
| 新增 `DisconnectCause.SIGNAL_LOST(survivable = true)`（唯一不可存活来源只剩 `FATAL`） | :790-808（枚举体 :790、`SIGNAL_LOST` :808） |

**预算推导（报告硬要求）**：`rejoinBudgetMs()` = `1+2+4+8×7` = **63 s** ⇒ `63_000 ≥ 60_000` 且 `63_000 < 75_000`（**t74 收紧**：断言上限由 `< 90_000` 改为 `< 75_000`，依据 go-dev `reports/35 §5.3`「服务端察觉断开」起点 ⇒ 显式关旧 socket 时可用预算 ≤75 s；服务端宽限期本身仍是 `-room-grace 90s`）。
常量 file:line：`REJOIN_RETRY_BASE_MS` :139、`REJOIN_RETRY_MAX_MS` :142、`MAX_REJOIN_ATTEMPTS` :145。

**与 doc/09 §6 的偏离（须登记）**：doc/09 §6 冻结过"重连等待 3 s / 最多 3 次"；本任务按 captain 裁定与 go-dev 服务端口径改为"1/2/4/8 s 封顶 × 10 次 ≈63 s"。
**未改 doc/14**（契约同步按 §0 建议由 architect 处理）——已在 §7.3 列为待办。

### 2.2 ② 耗尽时按 `mediaAlive` 分派（方案②，go-dev 明确背书）

| 内容 | file:line |
|---|---|
| 纯判定 `SignalLostAction` + `signalLostAction(everConnected, mediaAlive)` | `ui/call/CallSurvivability.kt:36-45` / `:105-106` |
| 通话页：`cause == SIGNAL_LOST` ⇒ 走该判定 | `ui/call/CallViewModel.kt:727-729` |
| `KEEP_CALL` ⇒ 保留通话页**与会话**（不 `hangup`/不销毁）+ 可恢复面板；诊断 **`signaling_lost action=keep_call media_alive=…`** | :730-755（日志 :733-742） |
| `END_CALL` ⇒ 才结束；诊断 `signaling_lost action=end_call` | :758-770 |
| 提示文案（中性，明确"对端未离开、画面可能仍在"+两条出路） | :1737 / :1740 |

`peerLeft` / 重连期 `ROOM_NOT_FOUND` 两条既有可存活路径**保持不变**（`disconnect_deferred` + 各自消息分支，t68 §2.2）。

### 2.3 ③ 重连入会成功后的 ICE restart（仅在 ViewModel 侧触发）

| 内容 | file:line |
|---|---|
| 纯判定 `shouldRestartIceOnRejoin(everConnected, iceDown, mediaAlive, hasSelectedPair)` | `ui/call/CallSurvivability.kt:108-136`（函数体 :127-136） |
| VM `maybeRestartIceAfterRejoin(trigger)`：判定 ⇒ 复用 `CallSession.restartIce("rejoin")`；诊断 **`restart_ice reason=rejoin`**（含 `trigger/accepted/ice_down/media_alive/pair/phase`） | `ui/call/CallViewModel.kt:1267-1294`（诊断 :1282） |
| 调用点：`joined` 分支 | :838 |
| 调用点：`peerJoined` 分支 | :850 |

规则（契约③）：`everConnected` 为假 ⇒ 不重启（首次握手）；**媒体证据新鲜且存在 selected pair ⇒ 不重启**（健康通话不受打扰，优先级最高）；否则 `iceDown`（ICE DISCONNECTED/FAILED）或无媒体证据 ⇒ 触发一次。
**`webrtc/` 目录本轮零改动**（`CallSession.restartIce(reason): Boolean` 为既有 API，内部已有每会话次数上限与 `ice_restart_exhausted` 诊断）—— 见 §6.2 自检。

### 2.4 ③ 的"显式关闭旧 socket"前置（go-dev 条件，已满足）

| 重连触发路径 | 拆旧 socket 的 file:line | 随后调度 |
|---|---|---|
| `onClosed`（对端/服务端关闭） | `SignalingClient.kt:354`（`webSocket = null`） | `:356` `scheduleReconnect("closed")` |
| `onFailure`（TCP/握手失败） | `:366`（`webSocket = null`） | `:369` `scheduleReconnect("failure")` |
| pong 超时（`ws_pong_timeout`） | `:695`（`webSocket?.cancel()`）+ `:696`（`null`） | `:698` `scheduleReconnect("pong_timeout")` |
| `onClosing`（对端发起关闭） | `:347-350`（显式 `close(WS_CLOSE_NORMAL=1000)`） | — |

⇒ 不存在"放弃旧 socket 而不关闭"的路径，服务端察觉延迟 ≈0，"静默 45 s 窗口"不会叠加进 63 s 预算。

---

## 3. 改动清单（file:line）

| 文件 | 类型 | 关键改动 |
|---|---|---|
| `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt` | 改 | 删除平行常量（`RECONNECT_DELAY_MS`/`RECONNECT_MAX_DELAY_MS`/`MAX_RECONNECT_ATTEMPTS`/`reconnectDelayMs`/`reconnectBudgetMs`）⇒ 复用 `rejoinDelayMs`/`rejoinBudgetMs`/`MAX_REJOIN_ATTEMPTS`（:120-168）；重连耗尽 ⇒ `SIGNAL_LOST`（:731-744、:683-698）；诊断 :750-761；`DisconnectCause.SIGNAL_LOST`（:821-838） |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt` | 改 | `SignalLostAction`/`signalLostAction`（:36-45/:105-106）；新增 `shouldRestartIceOnRejoin`（:108-140） |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` | 改 | `onStateChanged` 的 `SIGNAL_LOST` 分派（:726-770）；`maybeRestartIceAfterRejoin`（:1254-1294，调用 :838/:850）；文案常量 :1712/:1715 |
| `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt` | 改（t68 时新增；**t74** 再改断言上限） | 重写为 t71 口径：退避 1/2/4/8 封顶、总预算 63 s（≥60 且 **<75**〔t74 收紧，原 <90〕）、"复用 rejoin 口径且无平行常量"（反射断言旧常量已删）、>45 s 读超时、old-red 用例（5 例）；**t74** 将预算用例更名为 `reconnectBudgetIsStrictlyBelowSeventyFiveSeconds` |
| `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt` | 改 | +3 例：`rejoinWithIceDownTriggersRestartIce` / `healthyRejoinDoesNotTriggerRestartIce` / `firstConnectionNeverTriggersRestartIce`（③点名） |
| `code/webrtc-demo/reports/39-reconnect-budget-ice-restart.md` | **新增** | 本报告 |

`webrtc/**`、`cpp/**` 零改动（§6.2）。`reports/36-call-survivability.md` **未改**（其 §9 为上一轮中间态记录，由 captain 另建的 **t73** 负责账本登记）。

---

## 4. 点名单测（契约④，≥4 条，纯 JVM）

| 验收 | 点名用例 | file:line |
|---|---|---|
| ① 退避单调、封顶且总预算 **<75 s**（且 ≥60 s）〔t74 收紧；本文原为 <90 s〕 | `ReconnectBudgetTest.reconnectDelayIsCappedAndMonotonic`、`reconnectBudgetIsStrictlyBelowSeventyFiveSeconds`（t74 更名，原名 `reconnectBudgetIsWithinSixtyToNinetySeconds`）、`socketReconnectReusesRejoinBudgetWithoutParallelConstants`、`reconnectBudgetExceedsServerReadTimeout` | `app/src/test/.../signaling/ReconnectBudgetTest.kt:26` / `:47` / `:55` / `:70` |
| ② 耗尽 + mediaAlive ⇒ 不退出通话页 | `CallSurvivabilityTest.inCallWithoutPeerLeftDoesNotHangupOrEndCall` | `app/src/test/.../ui/call/CallSurvivabilityTest.kt:111` |
| ③ 耗尽 + 无媒体 ⇒ 结束 | `CallSurvivabilityTest.neverConnectedSignalLossEndsCallToAvoidDeadPage` | 同上 `:125` |
| ④ rejoin + iceDown ⇒ 触发；rejoin + 健康 ⇒ 不触发 | `rejoinWithIceDownTriggersRestartIce` / `healthyRejoinDoesNotTriggerRestartIce` / `firstConnectionNeverTriggersRestartIce` | 同上 `:133` / `:150` / `:165` |

---

## 5. 全量单测原始摘要 + old-red / new-green 对照

### 5.1 new（现行语义，仓库树）

```text
=== compile main ===
MAIN_RC=0
=== compile tests ===
TEST_RC=0
=== run JUnit ===
classes: 18
JUnit version 4.13.2
Time: 0.307

OK (176 tests)
```

- 测试类数 **18**、用例数 **176 OK / 0 failed**（t68 基线 17 类 / 165 例 ⇒ +1 类 / +11 例）。
- 离线整模块编译：主源码 0 error、测试源码 0 error（`MAIN_RC=0` / `TEST_RC=0`）。

### 5.2 old-red 对照（同一份测试代码，仅 `/tmp` 副本回退为修复前语义）

回退内容（`/tmp/kcheck/t71.sh` 的 `old` 模式）：`MAX_REJOIN_ATTEMPTS` 10→3、`rejoinDelayMs` 改为**固定 3 s**（= 修复前"3 s × 3 次 ≈9 s"）。

```text
There were 8 failures:
1) socketReconnectReusesRejoinBudgetWithoutParallelConstants(ReconnectBudgetTest)
2) reconnectDelayIsCappedAndMonotonic(ReconnectBudgetTest)
3) oldThreeAttemptBudgetWouldViolateTheRequirement(ReconnectBudgetTest)
4) reconnectBudgetIsStrictlyBelowSeventyFiveSeconds(ReconnectBudgetTest) → 预算 9000 ms（t74 更名后复跑）
5) reconnectBudgetExceedsServerReadTimeout(ReconnectBudgetTest)
6) rejoinBackoffIsExponentialWithCap(SignalingErrorPolicyTest)
7) backoffHandlesNonPositiveAttempt(SignalingErrorPolicyTest)
8) rejoinBackoffCoversServerReadTimeoutWindow(SignalingErrorPolicyTest) → 累计退避等待 9000ms 应覆盖 >45000ms 的回收窗口
Tests run: 176,  Failures: 8
```

⇒ "9 s 预算不满足 ≥60 s / **<75 s**（t74 收紧；原 `<90 s`）/ 覆盖 45 s 读超时"是**可被测试钉住**的事实（回退必红；`ReconnectBudgetTest` 5/5 全红，含更名后的 75 s 断言）。

### 5.3 t74 复跑（断言上限 90 s → 75 s 后；env-installer）

- 改动面：**仅** `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt`（断言上限 `75_000`，用例更名 `reconnectBudgetIsStrictlyBelowSeventyFiveSeconds`；单调性与 8 s 封顶断言保留、`63_000` 实测值断言保留）+ 本报告；**`app/src/main/**` 零改动**。
- 跑道：宿主机 kotlinc-embeddable 2.0.21 + JUnit 4.13.2 直编（`/tmp/kcheck/t74.sh`，**未跑 Gradle**）。源码快照 sha 前缀：`SignalingClient.kt 7c32c5db0f6da640`、`ReconnectBudgetTest.kt ef27e681d7a438cf`（= 仓库现行文件）。
- **new 模式**（现行仓库树，输出 `/tmp/kcheck/outT74b`）：
  ```text
  MODE=new  main_files=45  test_files=18
  === compile main ===  MAIN_RC=0
  === compile tests === TEST_RC=0
  === run JUnit ===
  classes: 18
  JUnit version 4.13.2
  Time: 0.532
  OK (176 tests)
  --- ReconnectBudgetTest 单类结果 ---
  OK (5 tests)
  ```
  ⇒ **18 类 / 176 用例 / 0 failures**（与 t71 基线一致；本任务未增删用例）。
- **old-red 模式**（`/tmp` 副本回退生产预算口径：`MAX_REJOIN_ATTEMPTS` 10→3、`rejoinDelayMs` 固定 3 s，输出 `/tmp/kcheck/outT74old`）：
  ```text
  PATCHED(old reconnect semantics: fixed 3 s x 3 attempts ⇒ budget 9 s)
  classes: 18
  ...
  Tests run: 176,  Failures: 8
  --- ReconnectBudgetTest 单类结果 ---
  ...ReconnectBudgetTest.reconnectBudgetIsStrictlyBelowSeventyFiveSeconds(ReconnectBudgetTest.kt:50)
  Tests run: 5,  Failures: 5
  ```
  ⇒ 回退即红（**≥1 failure 达成**：全套 8 failures，其中 `ReconnectBudgetTest` 5/5 全红，含新的 75 s 断言）。
- 边界：本任务**未跑 Gradle、未发布、未 commit**；`app/src/main` 的 5 项脏文件（`SignalingClient.kt`/`CallScreen.kt`/`CallViewModel.kt`/`ConnectionStatus.kt`/`CallSurvivability.kt`）为 t68/t71 遗留的未提交改动，mtime 均 ≤ 00:39:01（早于本任务开工 01:15），与 t74 无关。

---

## 6. 纪律与边界

### 6.1 verify 命令原始输出

```bash
$ cd /data/dsh/home/workspace/code/webrtc-demo && grep -rn 'RECONNECT_DELAY_MS\|MAX_RECONNECT_ATTEMPTS\|mediaAlive\|restartIce' app/src/main/kotlin/com/example/webrtcdemo/signaling app/src/main/kotlin/com/example/webrtcdemo/ui/call app/src/main/kotlin/com/example/webrtcdemo/webrtc | head -20
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:123:        // 历史：修复前为"固定 3 s × 3 次 ≈ 9–12 s"（RECONNECT_DELAY_MS + 独立上限 3），3 次失败即把
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:901:     * 现在由调用方组合本函数与 `CallSurvivability.roomLostAction(rejoinContext, mediaAlive)`：
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:917:     * @param mediaAlive 是否有可用媒体证据（媒体仍在流时必须保持通话页）。
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:919:    fun keepsCallOnRoomLoss(code: String, rejoinContext: Boolean, mediaAlive: Boolean): Boolean =
app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:920:        isRoomLossCode(code) && (rejoinContext || mediaAlive)
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:63:    fun mediaAlive(remoteFrameAgeMs: Long, hasSelectedPair: Boolean): Boolean =
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:74:     * @param mediaAlive 当前是否有可用媒体证据。
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:76:    fun peerLeftAction(everConnected: Boolean, mediaAlive: Boolean): PeerLeftAction =
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:77:        if (everConnected || mediaAlive) PeerLeftAction.KEEP_CALL else PeerLeftAction.END_CALL
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:86:     * @param mediaAlive 是否有可用媒体证据（媒体在流时即使语境判定不成立也必须留在通话页）。
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:88:    fun roomLostAction(rejoinContext: Boolean, mediaAlive: Boolean): RoomLostAction =
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:89:        if (rejoinContext || mediaAlive) RoomLostAction.KEEP_CALL else RoomLostAction.END_CALL
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:103:     * @param mediaAlive 当前是否有可用媒体证据。
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:105:    fun signalLostAction(everConnected: Boolean, mediaAlive: Boolean): SignalLostAction =
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:106:        if (everConnected || mediaAlive) SignalLostAction.KEEP_CALL else SignalLostAction.END_CALL
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:110:     * `CallSession.restartIce()`；本判定本身不碰 `webrtc/`）。
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:124:     * @param mediaAlive 是否有可用媒体证据（帧新鲜 <3 s 或已选中候选对）。
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:130:        mediaAlive: Boolean,
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:134:        if (mediaAlive && hasSelectedPair) return false
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:135:        return iceDown || !mediaAlive
（退出码 0；`head -20` 截断。关键观察：`RECONNECT_DELAY_MS`/`MAX_RECONNECT_ATTEMPTS` 在 **signaling 生产代码中已无定义**
（仅 :123 的历史注释提及已删除的旧常量）⇒ 平行口径确已清除；`.../webrtc/` 未出现在前 20 行 = 该目录未改动）
```

```bash
$ cd /data/dsh/home/workspace/code/webrtc-demo && ls -l reports/39-reconnect-budget-ice-restart.md && git status --porcelain
（完整输出见交付消息；本报告 mode 644；`git status` 中本任务路径 = SignalingClient.kt / CallViewModel.kt /
  CallSurvivability.kt / ReconnectBudgetTest.kt / CallSurvivabilityTest.kt + 本报告，其余为其他成员在飞改动）
```

### 6.2 outOfScope 自检

```text
$ git status --porcelain -- app/src/main/cpp app/src/main/kotlin/com/example/webrtcdemo/webrtc
（空输出 ⇒ cpp/** 与 webrtc/** 本轮零改动）
```
- `webrtc/` 虽在 t71 inScope 内，但**本轮无需改动**：`restartIce()` 是既有 API，VM 直接调用即可（契约"优先零改动"达成）。
- Go `signaling/`、`doc/14`：未触碰。
- 未跑 Gradle（由 t72 负责构建）；未 git commit。

### 6.3 时序

- 开工前确认 t69 已 completed（其落盘记录：java/gradle 进程=0、`app/src` 近 5 分钟写入=0、最新写入 23:53:46）⇒ 构建窗口已关闭。
- 本任务写入：见交付消息的「app/src 最后写入时刻」（供 t72 作为 T0 静默判据）。

---

## 7. 未验证项（诚实清单）

| # | 未验证项 | 为何无法在交付前验证 | 复测判据（真机/构建） |
|---|---|---|---|
| V1 | **抖动 >12 s 时不再自杀式退房** | 无设备；只能静态 + 纯 JVM 验证 | 日志出现 `ws_reconnect_scheduled … attempt≥4 delay_ms=… budget_ms=63000 max_attempts=10`，且**不出现** `call_end`/`hangup`；出现 `signaling_lost action=keep_call media_alive=true` 后仍在通话页 |
| V2 | 63 s 预算与现网 `-room-grace 90s` 的实际配合 | 同上 | `ws_reconnect_give_up attempts=11` 的时刻应 ≥ 首次失败重连 + 63 s；宽限期内重连应至少成功一次（t70 活体已证服务端会保留席位） |
| V3 | **重连后 ICE restart 是否真的修复"画面黑"** | 需真机 + WiFi↔4G 切换 | 出现 `restart_ice reason=rejoin accepted=true`，随后 `ui_conn_state phase=connected` + 新 `remote_frame_liveness`；健康通话（媒体新鲜+pair）**不得**出现该日志 |
| V4 | 可恢复面板（`signaling_lost action=keep_call`）真机可读性/可操作性 | 需真机渲染 | 截图 + 上述日志对照 |
| V5 | Gradle 真跑（`:app:compileDebugKotlin` / `clean assembleDebug` / `:app:testDebugUnitTest`）与 APK 产物 | **契约禁止本任务跑 Gradle**（属 t72） | t72 记录 EXIT=0 与 **≥18 类 / ≥176 用例** |
| V6 | doc/09 §6「重连 3 s / 最多 3 次」与本次 63 s 口径的**文档同步** | 需 architect 按 doc/14 §0 流程处理 | 契约同步后 doc/09 §6 与新口径一致 |
| V7 | `ROOM_FULL` 重入退避 10 次 ≈63 s 的**实测值**对账 | 由 go-dev 在 `reports/35-room-grace.md` §5.3 记录（本报告交叉引用） | 一致即通过（无需再对账） |

---

## 8. 交接

- **t72（构建/发布）**：请以交付消息给出的「app/src 最后写入时刻」起算静默窗口；预期单测 **≥18 类 / ≥176 用例**。
- **t73（账本补齐）**：`reports/36-call-survivability.md`（含 §9 中间态记录）由 t73 登记；本报告（reports/39）覆盖 t71 的最终口径。
- **architect（如需）**：doc/09 §6 的重连参数口径同步（V6）。
