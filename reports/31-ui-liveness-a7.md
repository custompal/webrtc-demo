# t61 报告：`ui/call` 侧三条硬要求（判活禁用 `up_bps` / `NO_RELAY_CANDIDATE` 自动重 gather / 阈值与收集完成锚点）

- 任务：t61「登记并收口 ui/call 侧三条硬要求」——captain 在 t58 复核后用邮件追加的三条硬要求，
  其落点在 **t59 的 `ui/call/**`**，而 t60 的 inScope 只含 `webrtc/**` ⇒ 单独建单登记（本报告即其交付物）。
- 归属：android-dev（attempt 1）
- 范围：`app/src/main/kotlin/com/example/webrtcdemo/ui/call/`（`ConnectionStatus.kt`、`CallViewModel.kt`）、
  `app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt`、本报告
- **代码没有再改**：全部改动在 t60 期间落盘，本任务只做登记、复核与收口（`webrtc/**` 侧改动见 `reports/30-ice-relay-robustness.md`）。

---

## §0 为什么单独登记（与 t60 的分工）

| 侧 | 内容 | 交付物 |
|---|---|---|
| **t60**（`webrtc/CallSession.kt`） | A1 看门狗窗口 30 s/45 s、首个 relay 就绪/收集完成起算、超时**先 ICE restart**；A2/A3 回环候选过滤；A4 TURN 701 计次 + 重 gather；`restartIce()`/`relayMissing()` 等事实接口 | `reports/30-ice-relay-robustness.md` |
| **t61**（`ui/call/**`，本报告） | 邮件三条硬要求：**判活禁用 `up_bps`**（+点名单测）、**`NO_RELAY_CANDIDATE` 显式子原因 + 自动重 gather**、**两档阈值 + 收集完成锚点**；以及 `ui_conn_state`/`no_selected_pair`/`retry_invoked` 诊断 | 本报告 |

> 依据：captain 邮件「请并入你的 t59/t60 实现与单测」+ 裁定 (B)「ui 侧单独建单 → t61」。
> 本任务**未新增实现**，仅登记并复核 t60 期间已落盘、已通过验证的这批改动。

---

## §1 硬要求 1：判活**绝对不能用 `up_bps`**

### 1.1 实现（file:line）

| 位置 | 内容 |
|---|---|
| `ui/call/ConnectionStatus.kt:71-88` | `enum class LivenessEvidence { SELECTED_PAIR, DOWNLINK, NONE }` + KDoc 记录真机依据（`mode=-` 时 `up_bps≈49 kbps` 而 `down_bps=0`） |
| `ui/call/ConnectionStatus.kt:91-101` | **唯一判活入口** `fun livenessEvidence(hasSelectedPair: Boolean, downBitrateBps: Int, upBitrateBps: Int): LivenessEvidence`：`hasSelectedPair ⇒ SELECTED_PAIR`；`downBitrateBps > 0 ⇒ DOWNLINK`；否则 `NONE`。**`upBitrateBps` 保留在签名里但永不参与判定**（KDoc 明确写"刻意保留 … 永不参与判定"，使该纪律成为**可被单测直接钉住**的行为契约） |
| `ui/call/CallViewModel.kt:768-772` | `onStats()` 唯一使用点：`when (livenessEvidence(connectionType.isNotEmpty(), downBps, upBps)) { SELECTED_PAIR → onSelectedPair ; DOWNLINK → onRemoteFrame ; NONE → Unit }` —— `NONE` 时状态机**不动**（不因上行增长而"连上"） |

**关键点**：`up_bps` 不是"少用"，而是**结构上不可能**被用来判活 —— 唯一的判活函数把它排除在 `when` 之外，
且它在全工程只有这一处消费点（`grep livenessEvidence` 仅 `CallViewModel.onStats`）。

### 1.2 点名单测（契约要求的两条）

```kotlin
// app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt
/** 真机形态样本序列（每 2 s 一条 ×15）：mode=- / up_bps≈49k / down_bps=0 ⇒ 状态机不得 connected */
@Test fun upstreamOnlySamplesNeverReachConnected() {
    var everConnected = false; var now = 0L
    repeat(15) {
        now += 2_000L
        val evidence = livenessEvidence(hasSelectedPair = false, downBitrateBps = 0, upBitrateBps = 49_000)
        assertEquals(LivenessEvidence.NONE, evidence)
        if (evidence != LivenessEvidence.NONE) everConnected = true
        tracker.onTick(now)
    }
    assertFalse("up_bps 不得把状态推成已连接", everConnected)          // ← 点名断言
    assertEquals(ConnPhase.FAILED, tracker.status.phase)               // 最终必须失败（可重试）
    assertEquals(ConnReason.TIMEOUT_NO_PAIR, tracker.status.reason)
}

/** 口径单元断言：up_bps 任意大都不算证据 */
@Test fun livenessNeverUsesUpstreamBitrate() {
    assertEquals(LivenessEvidence.NONE, livenessEvidence(false, 0, 0))
    assertEquals(LivenessEvidence.NONE, livenessEvidence(false, 0, 49_000))
    assertEquals(LivenessEvidence.NONE, livenessEvidence(false, 0, 5_000_000))   // ← 5 Mbps 上行仍是 NONE
    assertEquals(LivenessEvidence.SELECTED_PAIR, livenessEvidence(true, 0, 0))
    assertEquals(LivenessEvidence.DOWNLINK, livenessEvidence(false, 1, 0))
}
```
另有 t59 既有用例 `stallIgnoresUpstreamOnlySamples`（"只有上行增长"不得刷新画面新鲜度）继续通过。

### 1.3 `TIMEOUT_NO_PAIR` 的触发条件（按"无选中对 + 经过时长"）

`ui/call/ConnectionStatus.kt:414-490`（`onTick`）：超时判定只看
`nowMs - max(进房时刻, 收集完成时刻) >= 30_000`（或中断类原因看 `lostGraceMs`），
**完全不读任何字节计数**（`ConnStatus` 里也没有 `upBps` 字段）。

---

## §2 硬要求 2（A7）：`NO_RELAY_CANDIDATE` 显式子原因 + 自动重 gather

### 2.1 实现（file:line）

| 位置 | 内容 |
|---|---|
| `ui/call/ConnectionStatus.kt:52` | 新增 `ConnReason.NO_RELAY_CANDIDATE`（KDoc 说明它是 `TIMEOUT_NO_PAIR` 的**显式子原因**：真机失败会话特征＝"有 TURN 配置但 `local_relay=0`"） |
| `ui/call/ConnectionStatus.kt:123-125` | `ConnStatus.relayMissing`（"配了 TURN 但本会话 relay 候选数仍为 0"） |
| `ui/call/ConnectionStatus.kt:414-470` | `onTick(nowMs, relayMissing)`：超时时按 `relayMissing` 选子原因（`:462 relayMissing -> NO_RELAY_CANDIDATE`，否则 `TIMEOUT_NO_PAIR`） |
| `ui/call/ConnectionStatus.kt:175`/`:202-203` | 文案：「中继不可用」/「N 秒内未获取到中继候选（TURN 不可用或放行策略拒绝）」 |
| `ui/call/CallViewModel.kt:202` | 1 s 心跳把 `session?.relayMissing() == true` 传给 `onTick`（事实来自 t60 的 `CallSession.relayMissing()`） |
| `ui/call/CallViewModel.kt:893` + `:906-940` | `maybeAutoRegather(status)`：失败态为 `NO_RELAY_CANDIDATE` 时调 `session.restartIce("no_relay_candidate")`（`restartIce()` + 重新 `setConfiguration(GATHER_CONTINUALLY)` ⇒ **重新 gathering**），接受则按角色重新协商（host 立即 offer / joiner 延迟 1.2 s，复用 t59 防 glare），落 **`ice_regather_invoked`**（`accepted/local_relay/turn_errors/filtered_loopback/elapsed_ms/seq/session`）；**每世代最多一次**（`autoRegatherDone`，换代/重试复位） |
| `ui/call/CallViewModel.kt:725-740` | `IceEventType.END_OF_CANDIDATES` ⇒ 落 `ice_relay_state`（`relay`/`turn_errors`/`filtered_loopback`/`relay_missing`）+ `connTracker.onGatheringComplete()` |

### 2.2 判据（复测可直接对照）

1. 失败态日志必须是 `ui_conn_state phase=failed reason=no_relay_candidate … relay_missing=true`
   （**不是**笼统的 `timeout_no_pair`）；
2. 紧随其后应出现 `ice_regather_invoked reason=no_relay_candidate accepted=true …`，
   且**同一世代内只出现一次**；
3. 若重新 gathering 成功补到中继，应能看到 `ice_candidate_local … type=relay`（`local_relay>0`）
   与 `selected_candidate_pair … mode=RELAY` ⇒ `ui_conn_state phase=connected`；
4. 用户仍可用「点击重试」做**世代化重建**（`retry_invoked` → `session_retry` → 新 `session` 编号）。

---

## §3 硬要求 3：两档阈值 + 收集完成锚点

| 档位 | 常量（`ui/call/ConnectionStatus.kt:534-540`） | 行为（file:line） |
|---|---|---|
| 可重试提示 | `DEFAULT_RETRY_HINT_MS = 15_000` | `:414-490` 置 `retryHintReached`；`:147-148 `canRetry` = FAILED ‖ (CONNECTING ∧ retryHintReached)`；`:191-195` 文案追加"（可点击重试）"。**仍 CONNECTING，不判失败**（避免中继 gather 慢时误报，t58 §6.3 A1） |
| 硬失败 | `DEFAULT_CONNECT_TIMEOUT_MS = 30_000`（30–45 s 区间下沿） | `:452-470` `phase = FAILED` + 子原因 |
| 收集完成锚点 | `onGatheringComplete(nowMs)`（`:357-361`）；`onTick` 用 `anchor = max(进房时刻, gatheringCompleteAtMs)`（`:417-419`） | 收集完成得晚 ⇒ 硬超时相应后移（"等 `iceGatheringState==COMPLETE` 后起算"的第二种口径） |

**单测**：`gatheringCompleteAnchorsTimeout`（20 s 收集完成 ⇒ 49 s 仍 CONNECTING、50 s 才 FAILED）、
`retryHintAt15sThenHardFailAt30s`（15 s 仅提示/可重试，30 s 才 FAILED）。

> 与 t60 的关系：`webrtc/CallSession.kt` 的 ICE 层看门狗另有一套 30 s/45 s 窗口 + `ice_watchdog_rearmed`（见 `reports/30` §4）；
> 两者**分工明确**：`CallSession` 管 ICE 层（含 restart 与重 gather），`ConnectionStatusTracker` 管**UI 可见状态与时长**。

---

## §4 诊断事件（不破坏既有）

| 事件 | 位置 | 关键字段 |
|---|---|---|
| `ui_conn_state` | `CallViewModel.kt:866` | `phase`(connecting/connected/failed)、`reason`（含 `no_relay_candidate`）、`elapsed_ms`、`pair`、`frame`、`stalled`、`relay_missing`、`retry`、`seq`、`session` |
| `no_selected_pair` | `CallViewModel.kt:898` | `after_ms`、`pair=false`、`impl`、`avail_bps`、`up_bps`、`down_bps`、`local`、`remote`、`seq`、`session`（首次在超时瞬间落盘，之后每 15 s 一条） |
| `retry_invoked` | `CallViewModel.kt:255`（ignored）/`:268`（实际） | `room`/`role`/`phase`/`reason`/`elapsed_ms`/`retry`(第几次)/`seq`/`session` |
| `ice_regather_invoked` | `CallViewModel.kt:914` | `reason`、`accepted`、`local_relay`、`turn_errors`、`filtered_loopback`、`elapsed_ms`、`seq`、`session` |
| `ice_candidate_filtered` | `webrtc/CallSession.kt:574`/`:795` | `direction=local\|remote`、`reason=loopback`、`n=N`（t60 侧） |
| `ice_turn_error` | `webrtc/CallSession.kt:861` | `code`、`text`、`url`、`count`、`local_relay`（t60 侧） |

**既有事件名一个未删、未改名**：`isConnecting`/`isRemoteVideoReady` 仍只由 `publishConnStatus`（`CallViewModel.kt:859-860`）单点派生；
t59 的 `call_init_ignored`/`call_reinit`/`session_teardown`/`answer_timeout`/`remote_frame_ignored` 等全部保留。

---

## §5 单测与验证

- 新增/更新 `app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt`：**18 用例**，
  其中本轮为三条硬要求新增/改写 5 条：`upstreamOnlySamplesNeverReachConnected`（点名）、`livenessNeverUsesUpstreamBitrate`、
  `relayMissingIsExplicitFailureReason`、`gatheringCompleteAnchorsTimeout`、`retryHintAt15sThenHardFailAt30s`（替换 t59 的 15 s 硬超时用例）。
- **全量**：13 个 JVM 测试类 ⇒ **`OK (115 tests)`**（t59 基线 106 + 新增 9：本文件 4 + `LoopbackCandidatesTest` 5），**无回归**。
- **离线预检**（宿主机 /tmp，容器内无 JDK，非 Gradle）：Kotlin 2.0.21 + 真实 classpath（android.jar(SDK34) +
  `third_party/libwebrtc/java/libwebrtc-java.jar` + 230 项依赖）+ 序列化/Compose 插件，整模块编译
  `app/src/main/kotlin` + `app/src/test/kotlin` ⇒ **EXIT=0、`error:` 计数 0**（本任务 3 个文件零错误；源文件 mtime 早于编译）。
- Gradle `:app:testDebugUnitTest` / `assembleDebug` 与真机复测由 captain 构建窗口执行。

---

## §6 verify 命令原始输出

> 采集时 HEAD 与工作区状态见 §6.3。

### V1
```
$ cd code/webrtc-demo && grep -n 'livenessEvidence\|NO_RELAY_CANDIDATE\|gatheringCompleteAnchorsTimeout\|upBps' \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt | head -20
52:    NO_RELAY_CANDIDATE,
91:fun livenessEvidence(
175:                ConnReason.NO_RELAY_CANDIDATE -> "中继不可用"
202:                ConnReason.NO_RELAY_CANDIDATE ->
412:     *        决定超时后的子原因（[ConnReason.NO_RELAY_CANDIDATE] vs [ConnReason.TIMEOUT_NO_PAIR]）。
462:                            relayMissing -> ConnReason.NO_RELAY_CANDIDATE
EXIT=0
```
**读法**：命中 6 行 —— `livenessEvidence` 定义（`:91`）、`ConnReason.NO_RELAY_CANDIDATE` 的枚举项（`:52`）
与两处文案/子原因选择（`:175`/`:202`/`:462`）及 KDoc（`:412`）。
注意 `upBps` **只出现在 `livenessEvidence` 的形参与 KDoc 中**（形参名 `upBitrateBps`），
代码里**没有任何读取点**（函数体只读 `hasSelectedPair`/`downBitrateBps`）；
`gatheringCompleteAnchorsTimeout` 是**测试用例名**，不在本文件（见 V2 与 §5），故此处不命中。
其余落点（`retryHintReached`/`relayMissing`/`canRetry`/`onGatheringComplete`/`onTick`/两档常量）见 §1–§3 的 file:line 表。

### V2
```
$ cd code/webrtc-demo && grep -rn 'upstreamOnlySamplesNeverReachConnected\|livenessNeverUsesUpstreamBitrate' app/src/test/ | head -5
app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt:93:    fun upstreamOnlySamplesNeverReachConnected() {
app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt:122:    fun livenessNeverUsesUpstreamBitrate() {
EXIT=0
```
**读法**：两条点名单测都存在（`:93` / `:122`，位于 `app/src/test/**/ui/call/`，即 t61 判据所在目录）。

### V3
```
$ cd code/webrtc-demo && ls -l reports/31-ui-liveness-a7.md 2>/dev/null; git status --porcelain
-rw-r--r-- 1 node node 15801 Sep 15 21:12 reports/31-ui-liveness-a7.md
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt
 M app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt
?? app/src/test/kotlin/com/example/webrtcdemo/webrtc/LoopbackCandidatesTest.kt
?? reports/30-ice-relay-robustness.md
?? reports/31-ui-liveness-a7.md
EXIT=0
```
> mode 恒 `644`；size/mtime/sha256 以交付消息为准（定稿后再采集）。
> **属 t61 的 3 条**：`ui/call/CallViewModel.kt`、`ui/call/ConnectionStatus.kt`、`ui/call/ConnectionStatusTrackerTest.kt`（+ 本报告）；
> **属 t60 的 3 条**：`webrtc/CallSession.kt`、`webrtc/LoopbackCandidatesTest.kt`、`reports/30-ice-relay-robustness.md`（已收口）。

### 6.3 版本上下文（采集时）
```
$ git log --oneline -1
72ff1f7 fix(infra): t58b/c 实测修正——coturn 4.6.1 内建拒绝表仅含 0/8+127/8 不含 link-local，…（报告附录 C）
```
> 本任务改动尚未提交（未获提交授权）。

---

## §7 未验证项（诚实清单）

| # | 未验证项 | 为什么没验 | 复测方式 |
|---|---|---|---|
| U1 | 真机 4G↔WiFi 下「15 s 提示 / 30 s 失败」两档的观感与不误报 | 本成员无真机、无截图能力 | 复测：连接慢时 15 s 只出现"（可点击重试）"且仍转圈；30 s 前**不得**出现失败卡 |
| U2 | `NO_RELAY_CANDIDATE` + 自动重 gather 的真机成功率（能否补到中继并连上） | 同上（需可复现的网络切换窗口） | 复测：`ui_conn_state phase=failed reason=no_relay_candidate relay_missing=true` → `ice_regather_invoked accepted=true`（且**仅一次**）→ 是否出现 `type=relay` 候选与 `selected_candidate_pair mode=RELAY` |
| U3 | "`up_bps` 很大也不会显示已连接"的真机判别 | 同上 | 复测：`stats_sample` 出现 `mode=- up_bps>0 down_bps=0` 时 **不得**出现 `ui_conn_state phase=connected` |
| U4 | Gradle 全量单测/构建 | 容器无 JDK/SDK；构建窗口归 captain | captain 构建窗口（本报告提供离线预检证据：115 tests OK、整模块 0 error） |
| U5 | 收集完成锚点在真机上的效果（中继晚到 13 s+ 时不误判） | 需真机 | 复测：`END_OF_CANDIDATES` 之后再看 30 s，而非从进房起算（对照 `ice_relay_state` 时间） |

---

## §8 边界与遗留

- **未改 `webrtc/**`**（t60 范围）：本任务的 ui 代码只**消费** `CallSession` 提供的事实
  （`relayMissing()`/`restartIce()`/`localRelayCandidateCount()`/`turnErrorCount()`/`filteredLoopbackCount()`）与事件（`END_OF_CANDIDATES`），
  不重复实现一份中继/判活逻辑（满足 captain 邮件"避免两处各有一套"的要求）。
- **文案内联**：本 inScope 不含 `res/values/strings.xml`，故 `ConnStatus.title/detail`、`RETRY_LABEL`、
  `WAITING_REMOTE_FRAME_NOTICE` 均为 Kotlin 常量（与既有 `NO_PEER_RESPONSE_NOTICE` 同例）；建议后续统一迁字符串资源。
- **遗留建议**：
  1. 自动重 gather 目前"每世代一次"；若真机显示中继需要多轮，可改为"与 `retryCount` 同寿命的上限"并在 UI 上显示"已自动重试中继 N 次"；
  2. `remoteFrameReady` 的新鲜度仍以 `down_bps > 0` 为代理（无法区分仅音频），若要精确到视频需 `VideoRendererPool` 暴露最近渲染帧时刻（`webrtc/**`，非本任务）。
