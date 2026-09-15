# t60 报告：4G↔WiFi 中继健壮性（A1 看门狗口径 / A2·A3 回环候选过滤 / A4 TURN 701 重 gather / A7 NO_RELAY_CANDIDATE）

- 任务：t60「4G↔WiFi 中继健壮性：A1 看门狗口径（等 relay/收集完成 + 30–45 s + 先 ICE restart）、
  A2/A3 回环候选过滤、A4 TURN 701 计次重 gather」
- 归属：android-dev（attempt 1）
- 上游依据：`reports/28-turn-permission-403.md`（t58）**§6.3 表 A1–A4** 与 **附录 B.4 的 A7**、
  `reports/28` §2.1/§2.2 的回环证据；captain 邮件（t58 复核后确认的三条硬要求）
- 范围：`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt`（A1/A2/A3/A4/A7 的 ICE 层）、
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/**`（判活口径 + 子原因 + 自动重 gather 编排）、
  `app/src/test/**`、本报告
- **未触碰**：`cpp/**`、`config/**`、`nat/**`、`doc/**`（冻结）、`third_party/**`、`scripts/**`、
  服务端/中继配置（t58 负责）

---

## §0 摘要（结论先行）

| 条目 | 内容 | 落地位置 |
|---|---|---|
| **硬要求 1** | **判活绝不用 `up_bps`**：只用 `mode=P2P\|RELAY` 或 `down_bps>0`；`TIMEOUT_NO_PAIR` 按"无选中对 + 经过时长"判定 | `ui/call/ConnectionStatus.kt:livenessEvidence()`（唯一实现）+ `CallViewModel.onStats()`；**纯 JVM 单测** `upstreamOnlySamplesNeverReachConnected`（`mode=- / up_bps=49k / down_bps=0` × 15 条样本 ⇒ 断言**永不 connected**、最终 FAILED）与 `livenessNeverUsesUpstreamBitrate` |
| **A1①** | 看门狗窗口 15 s→**30 s**、30 s→**45 s**（t58 实测中继 gather 可 >15 s） | `CallSession.kt` `ICE_WARN_MS=30_000` / `ICE_FAIL_MS=45_000` |
| **A1②** | "TURN 已配置但还没拿到中继候选且收集未完成"时**顺延一档**（+15 s）并主动重新 gathering，**不**判失败 | `CallSession.checkConnectivity()` 的 `ice_watchdog_rearmed` 分支 + `startConnectivityWatchdog` 重新排程 |
| **A1③** | 到第二档**先做 ICE restart**，重启次数用尽才上报失败 | `CallSession.restartIce()`（`MAX_ICE_RESTARTS=2`）+ `checkConnectivity()` 的 `ice_timeout_restarting` 分支 |
| **A2** | 本端**发送前**丢弃回环候选（`127.0.0.0/8`、`::1`、`::ffff:127.0.0.1`） | `CallSession.events.onIceCandidate` → `ice_candidate_filtered`(direction=local) |
| **A3** | 对端候选**接收时**同样丢弃（在计数/暂存**之前**，避免进 t53 暂存队列被回放） | `CallSession.onRemoteIceCandidate` → `ice_candidate_filtered`(direction=remote) |
| **A4** | `turn:` 的候选错误（真机 `701 TURN_allocate_request_timed_out`）**计次**并触发**重新 gathering** | `CallSession.onIceCandidateError` → `ice_turn_error` + `restartIce("turn_error_701")` |
| **A7** | `local_relay==0` 作为 `TIMEOUT_NO_PAIR` 的**显式子原因** `NO_RELAY_CANDIDATE`，并自动重 gather/重协商 | `ConnectionStatus.ConnReason.NO_RELAY_CANDIDATE` + `CallViewModel.maybeAutoRegather()` → `ice_regather_invoked` |
| **硬要求 3** | 阈值取 **30 s**（30–45 区间下沿）**且**锚点可后移到 `iceGatheringState==COMPLETE`；15 s 处只给"可重试"提示，**不**判失败 | `DEFAULT_CONNECT_TIMEOUT_MS=30_000` + `DEFAULT_RETRY_HINT_MS=15_000` + `ConnectionStatusTracker.onGatheringComplete()` |

---

## §1 需求与口径（逐条对照上游）

### 1.1 t58 §6.3 表（A1–A4 原文要点）

| 项 | t58 指出的问题 | t58 建议 |
|---|---|---|
| A1 | `CallSession.kt` 看门狗 15 s 固定窗口对"4G↔WiFi + 走中继"过短；失败会话正是在 15 s 触发 | ①放宽到 30–45 s；②或"必须先看到 relay 候选或 `iceGatheringState==COMPLETE`"才起算；③超时后先 **ICE restart** 而不是直接 FAILED |
| A2 | 会把 `127.0.0.1`/`::1` loopback host 候选**发给对端**，诱发对端 403、浪费信令（两机日志此类候选 63+176+177+4 条） | 发送前过滤 |
| A3 | 会把对端的 loopback 候选**灌进 libwebrtc**，libwebrtc 随即为其建权限 → 403 | 接收侧同样过滤（可保留 `::1` 之外的 IPv6） |
| A4 | `onIceCandidateError` 目前只打日志：`code=701 TURN_allocate_request_timed_out` 后不重试，最终 FAILED | 对 `turn:` 的 701 计次并触发**重新 gathering**；把"本会话 relay 候选数"计入失败诊断字段 |
| A7（附录 B.4） | 检测到"有 TURN 配置但 `local_relay==0`"时应触发**重新 gather / ICE restart / 重建 PC**，而不是等 15 s 判失败；并打点重试次数与结果 | 建议作为 `TIMEOUT_NO_PAIR` 的子原因或独立原因并入 t59/t60 |

### 1.2 真机依据（t58 §6.1，失败会话 vs 成功会话，同一台 Mi 10 Pro / joiner）

```
# 失败会话（local_relay=0，看门狗恰好在 15 s 触发）
12:31:52.290 pc_starting force_relay=false ice_servers=stun+turn session=s1
12:31:53.169 ice_candidate_remote remote=type=relay addr=47.238.144.66 port=49168   ← 对端 relay 候选已到
12:32:08.055 WARN ice_not_connected after_ms=15000 ice_state=CHECKING
             local_candidates=host=5 local_relay=0 remote_candidates=host=6,srflx=1,relay=1 transport=CONNECTING
12:32:08.375 stats_sample avail_bps=0 down_bps=0 impl=- local=host mode=RELAY remote=relay up_bps=0
12:32:08.664 ERROR pc_connection_state dtls=false state=FAILED

# 同机上一次成功会话（relay 候选 +372 ms 到位）
12:31:12.852 ice_candidate_local local=type=relay addr=47.238.144.66 port=49181
12:31:13.380 selected_candidate_pair mode=RELAY
```
另：失败会话里 App 自己的 STUN/TURN 请求同时超时（`code=701`），且都出自 **loopback 终点**（`address=127.0.0.x`）的 socket
—— 这正是 A2/A3 要根除的形态；t58 §6.2 已排除服务端拒绝/配额（`ALLOCATE success 32 / error 0`），
即 `local_relay=0` 是**客户端在该窗口内没完成分配/收集**，被看门狗误判成失败。

### 1.3 captain 邮件三条硬要求

1. 判活**绝对不能用 `up_bps`**（真机 `mode=-` 时仍 ≈49 kbps）；判活只能用 `mode=P2P|RELAY` 或 `down_bps>0`；
   `TIMEOUT_NO_PAIR` 按"无选中对 + 经过时长"判定；**加纯 JVM 单测**（样本序列 `mode=- / up_bps=49k / down_bps=0` ⇒ 不得 connected）。
2. **A7 并入**：`local_relay==0` 作为 `TIMEOUT_NO_PAIR` 的显式子原因（`NO_RELAY_CANDIDATE`），超时时触发重新 gathering / ICE restart，
   位置在 `CallSession.kt` 的候选回调、`onIceCandidateError`、看门狗调度；若 t59 已有同名逻辑则**去重收敛**。
3. 阈值取 **30–45 s**，或改为"等 `iceGatheringState==COMPLETE` / 首个 relay 候选到位后再起算"。

> **去重收敛说明**：t59 的 `ConnectionStatusTracker` 原本只有 `TIMEOUT_NO_PAIR/CONNECTION_LOST/REMOTE_FRAME_STALLED/SESSION_START_FAILED`
> 四类原因、15 s 硬超时、无"中继未 gather"概念。t60 把"中继/判活/阈值"的口径**全部收敛到同一处**：
> 判活在 `livenessEvidence()`、子原因在 `ConnReason.NO_RELAY_CANDIDATE`、阈值与锚点在 `ConnectionStatusTracker`
> —— **不存在两套并行逻辑**（`CallSession` 只提供事实：候选计数/收集完成/错误计数/restart 能力）。

---

## §2 改动清单（逐条 → file:line）

### 2.1 `webrtc/CallSession.kt`

| # | 改动 | 说明 |
|---|---|---|
| C1 | `ICE_WARN_MS 15_000→30_000`、`ICE_FAIL_MS 30_000→45_000`，新增 `MAX_ICE_RESTARTS=2`、`ICE_DEFER_STEP_MS=15_000` | A1① |
| C2 | `start()`：记住 `iceConfig`/`forceRelayConfig`/`turnConfigured`；**配了 TURN 时**把 `continualGatheringPolicy = GATHER_CONTINUALLY` | A4/A7 的"重新 gathering"前置能力（`GATHER_ONCE` 收集结束后不再补候选） |
| C3 | 新增字段：`turnConfigured`/`iceConfig`/`forceRelayConfig`/`iceRestartAttempts`/`turnErrorCount`/`filteredLocalLoopback`/`filteredRemoteLoopback`/`gatheringCompleteAtMs` | 诊断与上限控制 |
| C4 | `onIceCandidate()`（本端候选）：**先判回环 ⇒ 丢弃**（不 `sendIce`、不进诊断列表），落 `ice_candidate_local_filtered reason=loopback filtered=N` | **A2** |
| C5 | `onRemoteIceCandidate()`：解析后**先判回环 ⇒ 丢弃**（早于 `candidateCounter`/闸门/暂存队列），落 `ice_candidate_filtered`(direction=remote) | **A3** |
| C6 | `onIceCandidateError()`：`turn:`/`turns:` 的 URL ⇒ `turnErrorCount++` + 落 `ice_turn_error`（含 code/text/count/local_relay）；若 `local_relay==0` ⇒ 立刻 `restartIce("turn_error_<code>")` | **A4** |
| C7 | `onIceGatheringState(COMPLETE)`：记录 `gatheringCompleteAtMs`；`ice_gathering_complete` 增字段 `turn_configured/turn_errors/filtered_loopback` | A1②、A7 诊断 |
| C8 | `checkConnectivity()`：① 中继未到且收集未完成 ⇒ `ice_watchdog_rearmed`（顺延一档 + 主动重 gather，**不判失败**）；② 第二档先 `restartIce("ice_timeout")`（成功则 `ice_timeout_restarting` + 顺延）；③ 用尽才 `ice_timeout` + 上报 UI，且**中继缺失时给专门文案**；字段新增 `turn_configured/turn_errors/relay_missing/filtered_loopback/ice_restarts` | A1②③、A4、A7 |
| C9 | 新增公开 API：`restartIce(reason): Boolean`（`restartIce()` + 重新 `setConfiguration(GATHER_CONTINUALLY)`，带上限与 `ice_restart_requested`/`ice_restart_exhausted`/`ice_regather_failed` 日志）、`relayMissing()`、`localRelayCandidateCount()`、`turnErrorCount()`、`filteredLoopbackCount()` | A4/A7 供上层编排 |
| C10 | 新增纯 Kotlin `object LoopbackCandidates { fun isLoopback(address) }`（IPv4 `127/8`、IPv6 `::1`(含展开/方括号)、`::ffff:127.0.0.1`） | A2/A3 的可单测判定 |
| C11 | `startConnectivityWatchdog` 日志增 `fail_ms`/`turn_configured`，KDoc 记录 t60 口径变化 | A1 |

### 2.2 `ui/call/ConnectionStatus.kt`

| # | 改动 | 说明 |
|---|---|---|
| S1 | 新增 `LivenessEvidence { SELECTED_PAIR, DOWNLINK, NONE }` 与 `livenessEvidence(hasSelectedPair, downBitrateBps, upBitrateBps)` | **硬要求 1**：`upBps` 刻意保留在签名里但**永不参与判定**，使"不得用它判活"成为**可单测钉住**的行为契约 |
| S2 | `ConnReason` 新增 **`NO_RELAY_CANDIDATE`**（t58 附录 B.4 / A7 的显式子原因），并给出专门标题/详情文案 | **硬要求 2 / A7** |
| S3 | `ConnStatus` 新增 `retryHintReached`/`relayMissing`；`canRetry = FAILED ‖ (CONNECTING ∧ retryHintReached)` | **硬要求 3** 的两档口径：15 s 给可操作提示 + 重试入口，30 s 才判失败 |
| S4 | `DEFAULT_CONNECT_TIMEOUT_MS 15_000→30_000`、新增 `DEFAULT_RETRY_HINT_MS=15_000` | 阈值取 30–45 s 区间下沿 |
| S5 | 新增 `onGatheringComplete(nowMs)`；`onTick(nowMs, relayMissing=false)`：超时锚点 = `max(进房时刻, 收集完成时刻)`，超时时按 `relayMissing` 选择子原因 | **硬要求 3 的第二种口径 + A1②** |
| S6 | `CONNECTING` 档的 `detail` 在 `retryHintReached` 后追加"（可点击重试）" | 用户可见的可操作性 |

### 2.3 `ui/call/CallViewModel.kt`

| # | 改动 | 说明 |
|---|---|---|
| V1 | `onStats()` 改用 `livenessEvidence(...)` 三态分支（判活口径**只有一处实现**） | 硬要求 1 |
| V2 | `onIceEvent()`：`ICE_CONNECTION` 驱动连接状态；新增 **`END_OF_CANDIDATES`** 分支 ⇒ 落 `ice_relay_state`（relay/turn_errors/filtered_loopback/relay_missing）+ `connTracker.onGatheringComplete()` | A1②、A7 诊断 |
| V3 | 1 s 心跳把 `relayMissing = session?.relayMissing() == true` 传给 `onTick` | 硬要求 2 |
| V4 | 失败态为 `NO_RELAY_CANDIDATE` 时新增 `maybeAutoRegather()`：`session.restartIce("no_relay_candidate")` + 按角色重新协商（host 立刻 offer / joiner 延迟 1.2 s，复用 t59 防 glare 机制），落 `ice_regather_invoked`（含 accepted/relay/turn_errors/filtered/elapsed）；**每世代最多自动一次**（`autoRegatherDone`，换代复位） | **A7 的"自动重试"** |
| V5 | `publishConnStatus` 对两种超时原因都调用 `maybeLogNoSelectedPair`（`no_selected_pair after_ms=N` 诊断保留） | 诊断 |

### 2.4 `ui/call/CallScreen.kt`

无需改动：t59 已按 `conn.canRetry` 渲染「点击重试」；t60 让 `canRetry` 在 15 s 后即为真（行为自然生效）。
（复核：`if (conn.showOverlay) { … if (conn.canRetry) { Button(… viewModel.retryConnection()) } }`。）

---

## §3 判活口径（硬要求 1）与对应单测

**规则**：一次 stats 样本只能提供两种连通证据 —— ① `mode` 非空（有选中候选对）；② `down_bps > 0`（真的在收）。
**`up_bps` 永不参与判定**：真机 `mode=- local=- remote=-` 时 `up_bps≈49 kbps`（编码器被压到最低码率持续产出，
`bytesSent` 只是"交给 ICE 层的字节"），`down_bps=0`。

**实现**（唯一入口）：

```kotlin
fun livenessEvidence(hasSelectedPair: Boolean, downBitrateBps: Int, upBitrateBps: Int): LivenessEvidence = when {
    hasSelectedPair -> LivenessEvidence.SELECTED_PAIR
    downBitrateBps > 0 -> LivenessEvidence.DOWNLINK
    else -> LivenessEvidence.NONE        // ← 即使 upBitrateBps = 5_000_000
}
```
`CallViewModel.onStats()` 只用它的返回值驱动 `connTracker`（`SELECTED_PAIR`→`onSelectedPair`、`DOWNLINK`→`onRemoteFrame`、`NONE`→不动）。

**单测（验收要求的那条）**：

```kotlin
/** 真机形态样本序列（每 2 s 一条 ×15）：mode=- / up_bps≈49k / down_bps=0 ⇒ 状态机不得 connected */
@Test fun upstreamOnlySamplesNeverReachConnected() { ... 断言 everConnected == false
    assertEquals(ConnPhase.FAILED, tracker.status.phase)
    assertEquals(ConnReason.TIMEOUT_NO_PAIR, tracker.status.reason) }

/** 口径单元断言：up_bps 任意大都不算证据 */
@Test fun livenessNeverUsesUpstreamBitrate() { ... NONE(NONE,0,0) / NONE(false,0,49_000) / NONE(false,0,5_000_000)
    SELECTED_PAIR(true,0,0) / DOWNLINK(false,1,0) }
```
另有 t59 既有的 `stallIgnoresUpstreamOnlySamples`（"只有上行"不得刷新画面新鲜度）继续生效。

---

## §4 阈值与锚点（硬要求 3 / A1①②）

| 档位 | 阈值 | 行为 |
|---|---|---|
| 可重试提示 | **15 s**（`DEFAULT_RETRY_HINT_MS`） | `retryHintReached=true` ⇒ 状态卡文案追加"（可点击重试）"+ **重试按钮可见**；**仍为 CONNECTING**（不判失败） |
| 硬失败 | **30 s**（`DEFAULT_CONNECT_TIMEOUT_MS`，30–45 区间下沿） | `FAILED` + 子原因（`NO_RELAY_CANDIDATE` / `TIMEOUT_NO_PAIR`）+ 重试按钮；若中继缺失 ⇒ `maybeAutoRegather()` 自动重 gather + 重协商 |
| 超时**锚点** | `max(进房时刻, iceGatheringState==COMPLETE 时刻)` | 收集完成得晚 ⇒ 硬超时相应后移（单测 `gatheringCompleteAnchorsTimeout`：20 s 收集完成 ⇒ 50 s 才判失败） |
| ICE 层看门狗 | WARN **30 s** / FAIL **45 s** | 中继未 gather 且收集未完成 ⇒ `ice_watchdog_rearmed` 顺延一档（+15 s）；第二档先 `restartIce`，用尽（`MAX_ICE_RESTARTS=2`）才 `ice_timeout` 上报 |

两档口径同时满足：**t59 验收**"15 s 内未 CONNECTED ⇒ 可操作提示 + 一键重试"（重试入口 15 s 即出现）
与 **captain 邮件 3 / A1①**"判失败取 30–45 s 或按收集完成起算"（不误报慢 gather）。

---

## §5 事件登记（沿用既有命名；既有事件名一个未删）

| 事件 | 等级 | 关键字段 |
|---|---|---|
| `ice_candidate_filtered`(direction=local) | WARN | `reason=loopback`、`local`、`n=N`（**A2** 新增） |
| `ice_candidate_filtered`(direction=remote) | WARN | `reason=loopback`、`remote`、`n=N`（**A3** 新增） |
| `ice_turn_error` | WARN | `code`、`text`、`url`、`count`、`local_relay`（**A4** 新增） |
| `ice_restart_requested` | INFO | `reason`(turn_error_701/watchdog_defer/relay_missing_warn/ice_timeout/no_relay_candidate)、`attempt`、`accepted`、`local_relay`、`turn_errors`（**A1③/A4/A7** 新增） |
| `ice_restart_exhausted` / `ice_restart_failed` / `ice_regather_failed` | WARN | `reason`、`attempts`（新增） |
| `ice_watchdog_rearmed` | WARN | `after_ms`、`next_ms`、`reason=awaiting_relay`、`turn_configured`（**A1②** 新增） |
| `ice_timeout_restarting` | WARN | 同 `ice_timeout` 字段（**A1③** 新增：先重启再判失败） |
| `ice_relay_state` | INFO | `relay`、`turn_errors`、`filtered_loopback`、`relay_missing`、`seq`、`session`（**A7** 新增，收集完成时） |
| `ice_regather_invoked` | WARN | `reason`、`accepted`、`local_relay`、`turn_errors`、`filtered_loopback`、`elapsed_ms`、`seq`、`session`（**A7** 新增） |
| `ice_gathering_complete` | INFO | 追加 `turn_configured`、`turn_errors`、`filtered_loopback`（保留 `local`、`relay`） |
| `ice_watchdog_started` | INFO | 追加 `fail_ms`、`turn_configured` |
| `ice_timeout` / `ice_not_connected` | ERROR/WARN | 追加 `turn_configured`、`turn_errors`、`relay_missing`、`filtered_loopback`、`ice_restarts` |
| `ui_conn_state` | INFO | `reason` 新增取值 `no_relay_candidate`；新增 `relay_missing`（t59 事件，t60 扩字段） |
| `no_selected_pair` | WARN | 保持（两种超时原因都会落盘） |

---

## §6 单测与离线预检

新增/更新（纯 JVM，无 Android / Compose 依赖）：

| 文件 | 用例 | 关键断言 |
|---|---|---|
| `ui/call/ConnectionStatusTrackerTest.kt`（**18**） | `retryHintAt15sThenHardFailAt30s`（**替换** t59 的 15 s 硬超时用例） | 15 s ⇒ `retryHintReached`+`canRetry`+文案含"15 秒/可点击重试"但**仍 CONNECTING**；30 s ⇒ `FAILED(TIMEOUT_NO_PAIR)` |
| | `upstreamOnlySamplesNeverReachConnected`（**新增，硬要求 1 的点名用例**） | `mode=- / up_bps=49k / down_bps=0` × 15 样本 ⇒ **永不 connected**，最终 FAILED + 可重试 |
| | `livenessNeverUsesUpstreamBitrate`（新增） | `up_bps` 任意大 ⇒ `NONE`；`SELECTED_PAIR`/`DOWNLINK` 各自成立 |
| | `relayMissingIsExplicitFailureReason`（新增，A7） | `relayMissing=true` ⇒ `NO_RELAY_CANDIDATE` + 标题「中继不可用」+ 详情含"中继候选"；`false` ⇒ `TIMEOUT_NO_PAIR` |
| | `gatheringCompleteAnchorsTimeout`（新增，A1②） | 20 s 收集完成 ⇒ 49 s 仍 CONNECTING、50 s 才 FAILED |
| | 其余 13 个 t59 用例（连接中/已用时长/选对即连/掉线回落/宽限恢复/停滞/重试计数/子串陷阱等） | 保持通过 |
| `ui/call/PendingRemoteMessagesTest.kt`、`webrtc/SessionLifecycleTest.kt`、`webrtc/CallSessionSlotTest.kt` 等既有用例 | 106 → 全部保持通过 | 无回归 |
| `webrtc/LoopbackCandidatesTest.kt`（**新增 5**，A2/A3） | `filtersIpv4LoopbackRange` / `filtersIpv6Loopback`（含 `[::1]`、展开式）/ `filtersIpv4MappedLoopback` / `keepsUsableAddresses`（私网/公网/relay/`2409:…`/链路本地 `169.254.1.1` **不得**误过滤）/ `malformedAddressesAreNotFiltered`（空、`host`、`127.0.0`、`127.0.0.256` 保守放行） | A2/A3 的判定边界 |

**离线预检（宿主机 /tmp 一次性脚本；容器内无 JDK，非 Gradle）**：
- Kotlin **2.0.21** + 真实 classpath（`android.jar`(SDK 34) + `third_party/libwebrtc/java/libwebrtc-java.jar` +
  缓存/`transforms` 展开共 230 项）+ 序列化与 Compose 插件，整模块编译 `app/src/main/kotlin`+`app/src/test/kotlin`：
  **`EXIT=0`、`error:` 计数 = 0**（本任务 5 个改动文件零错误）。
- JUnit4：**全量 13 个测试类 `OK (115 tests)`**（t59 基线 106 + 新增 9 个有效用例：4 个 tracker + 5 个 loopback）。
- 关键 API 事前核对（`javap` 于交付 jar）：`PeerConnection.restartIce()` ✓、`setConfiguration(RTCConfiguration)` ✓、
  `PeerConnection.RTCConfiguration.continualGatheringPolicy` **public 字段** ✓、`IceCandidateErrorEvent.url/errorCode/errorText` ✓
  ⇒ 不依赖不存在的方法（否则离线编译会直接失败）。
- Gradle 侧 `:app:testDebugUnitTest` / `assembleDebug` 与真机复测仍由 captain 构建窗口执行。

---

## §7 verify 命令原始输出

> 采集时刻 HEAD 见 §7.4；本任务改动尚未提交。**V1–V3 即 t60 契约给出的 verify 命令**（逐字执行）；
> V4 为补充的 A1–A4/A7 落点自查；V5 为与 t59 对照的 UI 侧自查（本任务改动涉及 `ui/call/**`，见 §2.2/§2.3 与 §9）。

### V1（契约）
```
$ cd code/webrtc-demo && grep -n 'ICE_WARN_MS\|ice_watchdog\|iceGatheringState\|relay' \
    app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt | head -25
91:         * （真机 dj-a 先 `local=-` 约 13 s 才拿到 relay 候选），15 s 固定窗口会把
94:        const val ICE_WARN_MS = 30_000L
234:    /** 看门狗启动时刻（用于 `ice_watchdog_ok` 的耗时字段）。 */
288:                mapOf("ice_servers" to iceSummary, "force_relay" to forceRelay.toString()).withKey(),
292:            // （真机 dj-a 约 13 s 才拿到 relay），GATHER_ONCE 在首次收集结束后不会再补，
785:            // 【t44】本端候选类型/地址/端口落盘（判断是否真的 gather 到 host/srflx/relay）
816:            // 【t60/A1②】**首个 relay 候选到位**即刻重新校准看门狗（不等下一档定时器）：
817:            // 契约要求"首个 relay 候选到位或 iceGatheringState==COMPLETE 后再起算"，
818:            // 这里在候选到达瞬间补一次检查，使"等到 relay 再判"真正生效。
849:                        "relay" to candidateCounter.localRelayCount().toString(),
895:                        "local_relay" to candidateCounter.localRelayCount().toString(),
1010:     * 若期间 ICE/传输已 CONNECTED，则取消（记 `ice_watchdog_ok`）。
1028:            "ice_watchdog_started",
1030:                "timeout_ms" to ICE_WARN_MS.toString(),
1035:        timer.schedule({ checkConnectivity(connection, ICE_WARN_MS) }, ICE_WARN_MS, TimeUnit.MILLISECONDS)
1058:        val relayCount = candidateCounter.localRelayCount()
1059:        val relayMissing = turnConfigured && relayCount == 0
1060:        // 【t60/A1②】中继场景下"还没收集完"就不该判失败：等 relay 候选到位或收集完成再起算。
1061:        // 真机 dj-a 先 `local=-` 约 13 s 才拿到 relay 候选，15 s 固定窗口必然误报。
1062:        if (afterMs < ICE_FAIL_MS && relayMissing && gatheringCompleteAtMs == 0L && !closed) {
1066:                "ice_watchdog_rearmed",
1070:                    "reason" to "awaiting_relay",
1088:            "local_relay" to relayCount.toString(),
1091:            "relay_missing" to relayMissing.toString(),
1098:            if (relayMissing && restartIce("ice_timeout")) {
EXIT=0
```
**读法**：`ICE_WARN_MS=30_000`（`:94`，A1①）；起算口径 = "relay 未到且收集未完成 ⇒ `ice_watchdog_rearmed reason=awaiting_relay` 顺延"
（`:1058-1070`，A1②）；第二档先 `restartIce("ice_timeout")`（`:1098`，A1③）；失败上报带 `relay_missing`/`filtered_loopback`（A4/A7 诊断）。
另 `:816-818` 为"**首个 relay 候选到位即重新校准**"的挂点（契约口径的第二种触发）。

### V2（契约）
```
$ cd code/webrtc-demo && grep -n 'loopback\|127\.0\.0\.1\|::1\|filtered' \
    app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt | head -20
199:    /** 被过滤掉的 loopback **本端**候选数（A2）。 */
201:    private var filteredLocalLoopback = 0
203:    /** 被过滤掉的 loopback **对端**候选数（A3）。 */
205:    private var filteredRemoteLoopback = 0
567:        // 【t60/A3】对端的**回环候选**同样丢弃：真机对端会把自己机器的 `127.0.0.1`/`::1` host 候选
571:            filteredRemoteLoopback++
574:                "ice_candidate_filtered",
577:                    "reason" to "loopback",
579:                    "n" to filteredRemoteLoopback.toString(),
788:            // 【t60/A2】回环候选**不发送**：真机两台设备都在广播 `127.0.0.1`/`::1` 的 host 候选
792:                filteredLocalLoopback++
795:                "ice_candidate_filtered",
798:                        "reason" to "loopback",
800:                        "n" to filteredLocalLoopback.toString(),
852:                        "filtered_loopback" to (filteredLocalLoopback + filteredRemoteLoopback).toString(),
1092:            "filtered_loopback" to (filteredLocalLoopback + filteredRemoteLoopback).toString(),
1193:    fun filteredLoopbackCount(): Int = filteredLocalLoopback + filteredRemoteLoopback
1407:// 为什么必须过滤：真机两台设备都把 `127.0.0.1` / `::1` 的 host 候选写进 SDP 互发
1408:// （t58 §2.1：di-a 63 处 `addr=127.0.0.1`、di-b 176+177+4 条），对端把它当 peer 去
1417: * 覆盖：IPv4 `127.0.0.0/8`、IPv6 `::1`（含展开写法）以及 IPv4-mapped 写法
EXIT=0
```
**读法**：A3 在 `:567-581`（接收侧，**早于**计数/闸门/暂存）、A2 在 `:788-802`（发送前，**早于** `sendIce`）；
两者共用契约建议的事件名 `ice_candidate_filtered reason=loopback n=N`（另带 `direction=local|remote` 区分方向）；
判定实现 `object LoopbackCandidates`（`:1407+`）覆盖 `127/8`、`::1`（含展开/方括号）、`::ffff:127.0.0.1`。

### V3（契约）
```
$ cd code/webrtc-demo && ls -l reports/30-ice-relay-robustness.md && git status --porcelain
-rw-r--r-- 1 node node 31059 Sep 15 21:06 reports/30-ice-relay-robustness.md
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt
 M app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt
?? app/src/test/kotlin/com/example/webrtcdemo/webrtc/LoopbackCandidatesTest.kt
?? reports/30-ice-relay-robustness.md
EXIT=0
```
> mode 恒 `644`；size/mtime/sha256 以交付消息为准（§7 写完后再采集）。
> 其中 `ui/call/*` 两条**不在 t60 契约 inScope**（契约写"不改 ui/**（t59 在改）"），
> 但 captain 邮件明确要求把"判活禁用 up_bps / A7 子原因 / 30–45 s 阈值"并入 **t59/t60 实现与单测**，
> 而 t59 已 completed+committed（`ui/call/**` 已不再被 t59 占用）⇒ 该 ui 改动为**受命为之**，
> 见 §9 的范围说明；若 captain 要求严格隔离，可将这两条拆到后续任务并保持本文件不变。

### 7.4 版本上下文（采集时）
```
$ git log --oneline -1
72ff1f7 fix(infra): t58b/c 实测修正——coturn 4.6.1 内建拒绝表仅含 0/8+127/8 不含 link-local，…（报告附录 C）
```
> 本任务改动尚未提交（未获提交授权）。

### V5（与 t59 对照的 UI 侧自查）
```
$ cd code/webrtc-demo && grep -n 'isRemoteVideoReady\|isConnecting\|pc_connection_state\|avail_bps\|local=-' \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt | head -25
CallViewModel.kt:133:    // `stats_sample … local=- mode=- remote=-`），但界面只把远端画面的最后一帧（静止帧）留在屏幕上，
CallViewModel.kt:144:     * 与 [CallUiState.isConnecting] 的关系：本字段是**唯一真源**，`isConnecting` 由它派生
CallViewModel.kt:312:            it.copy(roomId = roomId, role = role, isConnecting = true, error = null)
CallViewModel.kt:317:        // `publishConnStatus` 是 `CallUiState.isConnecting` 的**唯一写入方**（见其 KDoc）。
CallViewModel.kt:535:        // 【t59】只更新信令侧展示文案：`isConnecting`（连接中遮罩）已改由连接状态机唯一拥有 ——
CallViewModel.kt:660:                        it.copy(connectionState = "房间暂不可用，重试中…", isConnecting = true, error = null)
CallViewModel.kt:693:        // 【t59 根因修复】这里**删除**了旧的 `_uiState.update { isConnecting = false }`：
CallViewModel.kt:731:        //         _uiState.update { it.copy(isConnecting = false) }
CallViewModel.kt:845:     * 发布连接状态（**`isConnecting` 的唯一写入方**）。
CallViewModel.kt:849:     *  2. 由状态派生 `isConnecting`（CONNECTING ⇒ true）与 `isRemoteVideoReady`
CallViewModel.kt:859:                isConnecting = status.phase == ConnPhase.CONNECTING,
CallViewModel.kt:860:                isRemoteVideoReady = status.phase == ConnPhase.CONNECTED && status.remoteFrameReady,
CallViewModel.kt:888:     * 字段刻意与真机定因时用到的口径一致（`impl`/`avail_bps`/`up_bps`/`down_bps`），
CallViewModel.kt:903:                "avail_bps" to (stats?.availableOutgoingBitrateBps?.toString() ?: "-"),
CallViewModel.kt:1005:     * 【t59】判定基准由 `isConnecting` 改为"连接未建立"：t59 后 `isConnecting` 由连接状态机拥有，
CallViewModel.kt:1033:        _uiState.update { it.copy(error = message, isConnecting = false) }
CallViewModel.kt:1052:                isConnecting = false,
CallScreen.kt:99:    // 与 `state.isConnecting` 不同，它是"是否真的连上"的唯一判定（`isConnecting` 也由它派生）。
CallScreen.kt:323:        // 【t39 修复②】会议号**常驻顶部覆盖层**：整个通话生命周期可见（原先只在 isConnecting
CallScreen.kt:448:        // 【t59】连接状态卡（替换原先只看 `state.isConnecting` 的遮罩）
EXIT=0
```
**读法**：与 t59 同口径；t60 未新增 `isConnecting` 写入点（判活/阈值口径收敛在 `ConnectionStatus.kt`，
`isConnecting`/`isRemoteVideoReady` 仍由 `publishConnStatus`（`:859`/`:860`）单点派生）。
### V6（A1–A4/A7 落点自查）
```
$ cd code/webrtc-demo && grep -n 'LoopbackCandidates\|turnErrorCount\|restartIce\|gatheringCompleteAtMs\|NO_RELAY_CANDIDATE\|livenessEvidence\|relayMissing' \
    app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt | wc -l
（输出见交付消息；覆盖 A2/A3 过滤、A4 计次重 gather、A1② 锚点、A1③ restart、A7 子原因与自动重 gather）
```

---

## §8 未验证项（诚实清单）

| # | 未验证项 | 为什么没验 | 复测方式 |
|---|---|---|---|
| U1 | **回环过滤后 4G↔WiFi 是否真的不再出现 coturn `403 Forbidden IP`** | 本成员无真机、无宿主 coturn 写权限（t58 负责服务端） | 真机复测 + 宿主 `journalctl -u coturn | grep 403` 计数（A2/A3 生效后应为 0） |
| U2 | **A1/A7 的"重新 gathering 是否成功补到 relay 候选"** | 同上（需真机 + 可复现的网络切换窗口） | 复测看 `ice_restart_requested` → 之后是否出现 `ice_candidate_local … type=relay`（`local_relay>0`）与 `ice_relay_state relay>0` |
| U3 | **30 s/45 s 与 15 s 提示两档在真机上的观感** | 无设备/无截图能力 | 复测：15 s 出现"（可点击重试）"但仍转圈；30 s 才转失败卡；中继场景下不应再出现"15 s 就判失败" |
| U4 | A4 对 701 的"计次 + 重 gather"能否挽回失败会话 | 需真机 + 服务端权限抖动 | 复测看 `ice_turn_error count=N` → `ice_restart_requested reason=turn_error_701` → 是否 `selected_candidate_pair mode=RELAY` |
| U5 | Gradle `:app:testDebugUnitTest` / `assembleDebug` | 容器无 JDK/SDK；构建窗口归 captain | captain 构建窗口（本报告提供离线预检证据） |
| U6 | `GATHER_CONTINUALLY` 对耗电/信令量的影响 | 需真机长时观测 | 复测时看 `ice_candidate_local` 条数与耗电（若显著上升可只在 relay 场景开启，现已按"配了 TURN 才开"限制） |

### 8.1 真机复测清单（供 captain 交付前执行）

1. 4G（host）↔ WiFi（joiner）通话，**先确认服务端已按 t58 固化策略**；预期日志中
   **不再出现** `ice_candidate_local … addr=127.0.0.1`（被 `ice_candidate_local_filtered reason=loopback filtered=N` 取代），
   对端也**不再出现** `ice_candidate_remote … addr=127.0.0.1`（被 `ice_candidate_filtered`(direction=remote) 取代）。
2. 若本端中继分配慢：预期在**30 s 之前不判失败**，出现 `ice_watchdog_rearmed reason=awaiting_relay`；
   随后 relay 候选到位（`ice_candidate_local … type=relay`）并可能直接 `selected_candidate_pair mode=RELAY`。
3. 若 TURN 报错：预期 `ice_turn_error code=701 count=1` → `ice_restart_requested reason=turn_error_701`；
   复测 relay 候选是否补到。
4. 30 s 仍无候选对：预期 `ui_conn_state phase=failed reason=no_relay_candidate`（中继缺失）或 `reason=timeout_no_pair`，
   同时 `ice_regather_invoked accepted=true`（自动重 gather）**只出现一次**；UI 显示「中继不可用／N 秒内未获取到中继候选…」+「点击重试」。
5. 全程 `up_bps` 很大但 `down_bps=0` 时**不得**出现 `ui_conn_state phase=connected`（硬要求 1 的真机判据）。
6. 点「点击重试」⇒ 世代化重建（t59 机制）：新 `session` 编号、旧会话 `pc_closed`、计时归零。

---

## §9 边界与遗留

- **A1–A4 与 A7 的关系**：A1（看门狗口径）与 A4/A7（中继自救）在实现上共用一条链路 ——
  `CallSession` 只提供**事实**（relay 候选计数、收集完成时刻、TURN 错误计数、`restartIce` 能力），
  **决策**收敛在 `ConnectionStatusTracker`（阈值/锚点/子原因）与 `CallViewModel`（自动重 gather + 重协商编排），
  避免 t58 提示的"两处各有一套"。t59 的 `TIMEOUT_NO_PAIR` 语义未删，只新增 `NO_RELAY_CANDIDATE` 作为其子原因。
- **未改服务端**：`/etc/turnserver.conf`、coturn 策略、`signaling/` 一律未触碰（t58 已裁决：回环**继续拒绝**，
  客户端侧过滤由本任务承接，与 t58 附录 C 一致）。
- **`webrtc/**` 改动的正当性**：captain 邮件明确指定 A7 的位置为 `CallSession.kt`（候选回调 / `onIceCandidateError` /
  看门狗调度），故本任务对 `webrtc/CallSession.kt` 的改动是**受命为之**，且只做与之相关的最小改动
  （未改 `MediaCapture`/`WebRtcEngine`/`StatsMapper`/`WebRtcConfig`；`continualGatheringPolicy` 只在
  `CallSession` 内对该配置对象赋值，`WebRtcConfig.build` 一行未动）。
- **遗留/后续建议**：
  1. `NO_RELAY_CANDIDATE` 与「中继不可用」文案目前内联在 `ConnectionStatus`（inScope 不含 `strings.xml`），
     建议后续统一迁字符串资源（i18n）；
  2. A1② 的"顺延"目前是**固定 +15 s 一档**；若真机中继经常更晚到位，可改为"等 `local_relay>0` 事件驱动"
     （`ice_candidate_local type=relay` 到达即立刻复核，而不是等下一档定时器）；
  3. `restartIce()` 的 `ice-restart` 语义要求**重新发 offer** 才真正生效；本任务由 ViewModel 按角色重发
     （host 立刻 / joiner 延迟 1.2 s）。若后续引入服务端"强制重新协商"信令，可改为服务端驱动，去掉延迟猜测。
