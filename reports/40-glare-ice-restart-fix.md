# t75 报告：ICE restart 顺序修复 + 防 offer glare 分工 + 8 s 兜底

- 任务：t75（implementation r1，attempt 1，attempt_id `4e8c3fb9-6204-4d04-b542-df9008973dc2`）
- 承办：android-dev
- 依据：captain 2026-09-16 t75 任务书 + (b) 命名定案（"主键统一为 `restart_ice reason=rejoin initiator=…`"）
- inScope：`app/src/main/kotlin/.../ui/call/CallViewModel.kt`、`app/src/main/kotlin/.../ui/call/CallSurvivability.kt`、`app/src/test/kotlin/.../ui/call/CallSurvivabilityTest.kt`、本报告
- 明确未触碰：`reports/39`（t74 inScope）、`app/src/main/cpp/**`、`webrtc/**`、`signaling/**`（Go）、`doc/**`
- 未执行任何宿主机 Gradle 构建/发布；未 git commit

---

## 0. 摘要（两处功能缺陷 + 一处新增兜底）

| # | 缺陷（修复前） | 机制 | 修复 |
|---|---|---|---|
| ① | **host 侧顺序反了**：`maybeCreateOffer()` 先于 `maybeRestartIceAfterRejoin()` | `CallSession.restartIce()` 只调用 `PeerConnection.restartIce()` 置标志 + 重新 gathering，**它自己不发 offer**；只有**之后**创建的 offer 才携带 `iceRestart`。顺序反了 ⇒ 发出的 offer **不带 iceRestart** ⇒ 旧候选对已死时 ICE 不会重选 ⇒ 真机「信令已恢复、画面黑」 | 唯一入口 `restartIceThenOffer()`：**先 `restartIce(...)` 再发 offer**（顺序写死在一个函数体内）；host 侧由纯判定 `shouldRestartIceOnRejoin` 决定走"restart 版"或原 `maybeCreateOffer()` |
| ② | **两侧都可能发起重协商**（`Joined` 与 `PeerJoined` 分支都调了 `maybeRestartIceAfterRejoin`） | 重连侧与保持在线侧同时发带 `iceRestart` 的 offer ⇒ **offer glare**（libwebrtc 的 rollback 处理脆弱），症状同为「信令恢复但画面黑」 | **`Joined` 侧不再发起**（删除该调用，只等对端 offer 并 answer）；由服务端给**保持在线侧**（收到 `PeerJoined`）发起 |
| ③ | 若在线侧恰好也在重连/不在场，重连侧无人发起 ⇒ 永久停在"信令已恢复、画面黑" | — | **8 s 兜底**：`Joined` 后 8 s 内未收到 offer 且 ICE 未恢复 ⇒ 由重连侧兜底发起一次（可注入时钟，不 sleep） |

**离线预检（仓库树，宿主机 kotlinc-embeddable + JUnit4，未跑 Gradle）**：`MAIN_RC=0` / `TEST_RC=0` / **18 类 / 180 用例 / 0 failures**（t74 基线 18 类/176 例 ⇒ +4 例兜底用例）。
**old-red 对照**：把兜底判定回退为 `return false`（= 修复前无兜底路径）⇒ **2 failures**（见 §5）。

---

## 1. 缺陷定因（file:line）

修复前（t74 之后、本任务之前）：

- `CallViewModel.kt:830-838`（`Joined` 分支）：`maybeCreateOffer()` → `maybeRestartIceAfterRejoin("joined")`；
- `CallViewModel.kt:840-850`（`PeerJoined` 分支）：`maybeCreateOffer()` → `maybeRestartIceAfterRejoin("peer_joined")`；
- `maybeCreateOffer()` = `current.createOffer()`（**此刻 offer 已构造完毕，尚不含 iceRestart**）；
- `CallSession.restartIce()`（`webrtc/CallSession.kt:1149-1179`）内部：`connection.restartIce()`（:1163）+ 重新 `setConfiguration`（:1174，配了 TURN 时才做）⇒ **只影响"下一次" offer**。

⇒ 两处调用顺序均反了；且两侧都会发起（glare 面）。

**需求 4 复核（无需改动）**：带 `iceRestart` 的 offer 落到对端后，answer 路径不做任何 SDP 改写 —— `CallSession.onRemoteOffer` = `setRemoteDescription(offer)`（`webrtc/CallSession.kt:465-466`）→ `createAnswer`（:473-474），新 `ufrag/pwd` 被原样采纳（:527 仅做候选摘要日志）。故**两侧都能正确 answer 带 iceRestart 的 offer**，`webrtc/**` 零改动。

---

## 2. 实现

### 2.1 ① 顺序修复 + ② 分工（`ui/call/CallViewModel.kt`）

| 内容 | file:line |
|---|---|
| `PeerJoined` 分支（保持在线侧）才发起：`if (shouldInitiateRejoinRenegotiation()) restartIceThenOffer(INITIATOR_HOST, TRIGGER_PEER_JOINED, "restart_ice reason=rejoin initiator=host") else maybeCreateOffer()` | :884-890（调用 :887） |
| `Joined` 分支（重连侧）**不再发起**：删除原调用，只置"等 offer"状态（`awaitingPeerOfferAfterRejoin = role != ROLE_HOST` + `rejoinOfferWaitSinceMs = now`） | :874-880 |
| 唯一顺序入口 `restartIceThenOffer(initiator, trigger, logEvent, extra)`：**先 `restartIce("rejoin")`（:1344）再 `sendOfferInternal()`（:1370）** | :1337-1370 |
| 门控（纯判定）：`shouldInitiateRejoinRenegotiation()` = `CallSurvivability.shouldRestartIceOnRejoin(everConnected, iceDown, mediaAlive, hasSelectedPair)`（"媒体新鲜且 pair ⇒ 不重启"） | :1311-1319 |
| `sendOfferInternal()`：发 offer 的**统一内部入口**（不做 host 角色闸门，兜底路径也要能发） | :1372-1378 |
| `maybeCreateOffer()` 改为"host 闸门 + `sendOfferInternal()`"（行为不变） | :1300-1303 |

**顺序留证方式**：① 纯判定 `shouldRestartIceOnRejoin`（4 例单测，决定"要不要走 restart 版"）；② **结构留证** —— "restart 后 offer"这件事只存在于 `restartIceThenOffer()` 一个函数体内，`Joined` 分支已无任何发起路径（`grep maybeRestartIceAfterRejoin` 无命中；`restartIce(` 仅出现于 :1344）。运行期"调用顺序断言"需要真机 WebRTC 对象，交付前不可得，故以纯判定 + 单入口结构留证（见 §5.1 静态核对）。

### 2.2 ③ 8 s 兜底（可注入时钟，不 sleep）

| 内容 | file:line |
|---|---|
| 常量 `REJOIN_OFFER_FALLBACK_MS = 8_000L` | `ui/call/CallSurvivability.kt:139-148`（常量 :148） |
| 纯判定 `shouldRejoinerFallbackOffer(awaitingPeerOffer, waitedMs, iceDown, mediaAlive, hasSelectedPair)` = `awaiting && waited >= 8_000 && (iceDown \|\| !mediaAlive) && !(mediaAlive && hasSelectedPair)` | `ui/call/CallSurvivability.kt:150-174` |
| 在**既有 1 s 心跳循环**内评估（:284-296；循环体见 :246-280）；纯判定 + 时间戳，**不新增 sleep/协程** | `ui/call/CallViewModel.kt:284-296` |
| `evaluateRejoinOfferFallback(nowMs)`：一次性（判定为真即清标志防重复） | :1384-1397 |
| 收到对端 `Offer` ⇒ 取消兜底并落 `rejoin_offer_received waited_ms=…` | :962-976 |
| 兜底触发诊断 | :285-296（消息 `restart_ice reason=rejoin initiator=rejoiner`，字段 `trigger=joined`、`fallback=offer_timeout`、`waited_ms`、`threshold_ms`） |
| 新一代通话复位（`beginGeneration`） | :478-480（`awaitingPeerOfferAfterRejoin=false`、`rejoinOfferWaitSinceMs=0`） |

**8 s 取值依据**：正常 offer/answer 往返在 1–2 s 量级（真机日志中 offer→answer 均 <1 s），8 s 足以排除"offer 正在路上"，又远小于用户可感知的"卡住"阈值；且 SLA 上 < 服务端 90 s 宽限期（对端席位仍保留）。超时判定粒度 = 既有心跳 1 s ⇒ 实际触发窗口 **8–9 s**。

### 2.3 诊断字段（按 captain (b) 定案：单一主键）

统一为 **`restart_ice reason=rejoin initiator=host|rejoiner …`**：

| 位置 | 消息 | 字段 |
|---|---|---|
| host 侧发起（`PeerJoined`） | `restart_ice reason=rejoin initiator=host` | `trigger=peer_joined`、`accepted`、`ice_down`、`media_alive`、`pair`、`phase`、`seq`、`session` |
| 重连侧兜底 | `restart_ice reason=rejoin initiator=rejoiner` | `trigger=joined`、`fallback=offer_timeout`、`waited_ms`、`threshold_ms`、`accepted`、`ice_down`、`media_alive`、`pair`、`phase`、`seq`、`session` |
| 收到对端 offer | `rejoin_offer_received` | `waited_ms`、`seq`、`session` |

**与 t75 契约文本的两处字面差异（按 captain 2026-09-16 (b) 定案，以该封为准）**：
1. 契约③写 `rejoin_ice_restart initiator=rejoiner reason=offer_timeout` —— 按定案改为**同一主键** `restart_ice reason=rejoin initiator=rejoiner`，`offer_timeout` 记在字段 `fallback=offer_timeout`（不再使用 `rejoin_ice_restart` 这一旧写法，也无别名字段 `event=`）；
2. 契约①写 `restartIce("signaling_rejoin")` —— 实现保持 **`restartIce("rejoin")`**：该字符串仅落入 `CallSession` 内部诊断（`ice_restart reason=…`），保持与 t71 已落地/t72 APK **同一个值**，避免两版 APK 诊断口径分叉（captain 明确"t75 契约那句视为旧写法"）。
（`signaling_lost action=keep_call|end_call media_alive=…` 完全未动。）

---

## 3. 改动清单（file:line）

| 文件 | 类型 | sha256（落盘后） |
|---|---|---|
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` | 改 | `e1bcb441b1c8653bb003b96a8fdfdc3c1a58f9cf72a23c33a6df915a527b48ff` |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt` | 改 | `f9b3a9b694dcf3bc67bee374e6dfd9df2320236af24d2b08ec7ae4d1100c7a55` |
| `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt` | 改（+4 例） | `63fa18e5430631329584f8f7482c3fba61bbbfe9443ef8ef1aa986bf28641fea` |
| `code/webrtc-demo/reports/40-glare-ice-restart-fix.md` | **新增** | 见 §6.2 |

`app/src/main/cpp/**`、`app/src/main/kotlin/.../webrtc/**`、`signaling/**`（Go）、`doc/**`：**零改动**（§6.1 自检）。

---

## 4. 点名单测（新增 4 例，均在 `CallSurvivabilityTest`）

| 用例 | 断言 | file:line |
|---|---|---|
| `rejoinerFallbackFiresOnlyAtEightSecondBoundary` | `waited=7_999 ⇒ false`；`waited=8_000（= REJOIN_OFFER_FALLBACK_MS）且 iceDown 且无媒体 ⇒ true`；常量 = 8_000 | :133 |
| `rejoinerFallbackSkippedWhenMediaIsHealthy` | `mediaAlive && hasSelectedPair ⇒ false`（即使 waited=60_000、iceDown=true） | :150 |
| `rejoinerFallbackSkippedWhenOfferAlreadyArrived` | `awaitingPeerOffer=false ⇒ false` | :165 |
| `rejoinerFallbackAlsoFiresWhenMediaEvidenceIsGone` | `iceDown=false` 但无媒体证据、waited=9_000 ⇒ true | :180 |

既有相关用例（t71 起）：`rejoinWithIceDownTriggersRestartIce` / `healthyRejoinDoesNotTriggerRestartIce` / `firstConnectionNeverTriggersRestartIce`（门控三条）。

---

## 5. 验证

### 5.1 静态核对（顺序与分工留证）

```
$ grep -n 'restartIce\|maybeCreateOffer\|shouldRejoinerFallbackOffer\|awaitingPeerOfferAfterRejoin' app/src/main/kotlin/.../CallViewModel.kt app/src/main/kotlin/.../CallSurvivability.kt | head -30
```
要点（完整输出见交付消息）：
- `maybeRestartIceAfterRejoin` **零命中**（旧"两侧都调"的路径已被删除）；
- `restartIce(` 在 `CallViewModel.kt` 仅命中 :1344（`restartIceThenOffer()` 内部，**其后**才 `sendOfferInternal()`，:1370）；
- `shouldRejoinerFallbackOffer` 命中 `CallSurvivability.kt:166`（定义）与 `CallViewModel.kt:1392`（心跳内评估）；
- `awaitingPeerOfferAfterRejoin` 命中：字段声明 :219、`Joined` 置真 :878、`Offer` 清除 :969、兜底一次性清除 :1395、`beginGeneration` 复位 :479。

### 5.2 离线全量复跑（仓库树；未跑 Gradle）

```text
=== compile main ===
MAIN_RC=0
=== compile tests ===
TEST_RC=0
=== run JUnit ===
classes: 18
JUnit version 4.13.2
Time: 0.3xx

OK (180 tests)
```

- 测试类数 **18**（≥18 ✅）、用例数 **180**（= t74 后 176 + 本任务新增 4 ✅）、**0 failures**。

### 5.3 old-red 对照（移除兜底 ⇒ 必红）

方法：在 `/tmp` 副本把 `CallSurvivability.shouldRejoinerFallbackOffer` 的首行改为 `return false`（等价于"修复前无兜底路径"），其余不变后重编重跑：

```text
1) rejoinerFallbackAlsoFiresWhenMediaEvidenceIsGone(CallSurvivabilityTest)
2) rejoinerFallbackFiresOnlyAtEightSecondBoundary(CallSurvivabilityTest)
Tests run: 180,  Failures: 2
```

⇒ 兜底路径被移除时**恰有 2 条用例变红**，说明该行为被测试钉住（顺序修复无法在纯 JVM 断言，见 §2.1 留证方式说明）。

---

## 6. 纪律与边界

### 6.1 verify 命令原始输出

```text
$ cd /data/dsh/home/workspace/code/webrtc-demo && grep -n 'restartIce\|maybeCreateOffer\|shouldRejoinerFallbackOffer\|awaitingPeerOfferAfterRejoin' app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt | head -30
（见交付消息粘贴；退出码 0）
```

```text
$ cd /data/dsh/home/workspace/code/webrtc-demo && ls -l reports/40-glare-ice-restart-fix.md && git status --porcelain -- reports/39-reconnect-budget-ice-restart.md
（见交付消息粘贴；`reports/39` 无本任务改动）
```

```text
$ git status --porcelain -- app/src/main/cpp app/src/main/kotlin/com/example/webrtcdemo/webrtc app/src/main/kotlin/com/example/webrtcdemo/signaling
（空输出 ⇒ cpp/**、webrtc/**、signaling/** 零改动）
```

### 6.2 时序

- 落盘时刻：`CallViewModel.kt` / `CallSurvivability.kt` / `CallSurvivabilityTest.kt` = **2026-09-16 01:25:39 (+0800)**；本报告随后写入 ⇒ **`app/src` 最后写入时刻 = 见交付消息**（以 output 为准，供 t76 作 T1）。
- 写入前提：t72 completed（锚点 `f694a103…`）、t74 completed（env-installer attempt 2），宿主机无 gradle 进程。

### 6.3 需 captain 知情的协作事实（不影响本任务结论）

- t74 被重派给 env-installer（attempt 2，已 completed），其在 `reports/39` 写入 §5.3 复跑记录；我此前误认为自己持有 t74，在 `reports/39` 内做了两处**纯文本修正**（§5.2 old-red 第 4 项更名、结论句 `<75 s`）。**本任务（t75）未触碰 `reports/39`**（§6.1 第二条 verify 可证）。
- 我的 t74 代码改动（`ReconnectBudgetTest.kt`：`75_000` + 用例更名，sha `ef27e681…`）与 env-installer 复跑所引用的源码快照 sha **逐位一致** ⇒ 其复跑结论针对的正是该改动。

---

## 7. 未验证项（诚实清单）

| # | 未验证项 | 为何交付前不可验 | 判据（真机/后续构建） |
|---|---|---|---|
| V1 | **顺序修复真机生效**（offer 真带 iceRestart ⇒ ICE 重选 ⇒ 画面恢复） | 需要真机 WiFi↔4G 切换 + WebRTC 运行期对象；纯 JVM 只能断言"门控 + 单入口结构" | 日志出现 `restart_ice reason=rejoin initiator=host accepted=true` 且随后 `ui_conn_state phase=connected` + 新 `remote_frame_liveness`；`pc_*` 日志出现新的 ICE ufrag |
| V2 | **glare 不再发生** | 同上 | 同一时段**只有一侧**出现 `restart_ice reason=rejoin`；不出现两侧同时 `offer_sent`/rollback 迹象 |
| V3 | 8 s 兜底在真机的触发率与体验（8–9 s 粒度） | 同上 | 出现 `rejoin_offer_received waited_ms=…`（正常路径）或 `initiator=rejoiner fallback=offer_timeout`（兜底路径，应为少数） |
| V4 | 兜底窗口取值（8 s）是否合适 | 需真机网络统计 | 若频繁走兜底，评估放宽到 10–12 s（仍 < 宽限期）或收紧 |
| V5 | Gradle/APK 层（`:app:compileDebugKotlin`/`clean assembleDebug`/单测） | 本任务禁止跑 Gradle（属 t76） | t76 记录 EXIT=0 与 **≥18 类 / ≥180 用例** |
| V6 | `restartIce("rejoin")` 与契约文本 `"signaling_rejoin"` 的取舍已按 (b) 定案 | —（口径类） | 若 captain 改判，我在 1 处字符串内改回 |

---

## 8. 交接

- **t76（第三次构建）**：以本任务 output 给出的「app/src 最后写入时刻」起算静默窗口；闸门 **≥18 类 / ≥180 用例**。
- **verifier**：本次不修改 `reports/39` 与 `reports/36`；如需交叉引用，§2.1 的顺序留证方式（纯判定 + 单入口结构）与 §5.3 的 old-red 证据是核心。
