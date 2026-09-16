# t80 报告：修复「通话正常却报 ICE 未连通」误报横幅（看门狗基数未初始化 + 无恢复清除路径）

- 任务：t80（implementation r1，attempt 1，attempt_id `7bf28cc3-bec1-4ec8-b7f6-2d459e44b571`）
- 承办：android-dev
- 缺陷来源：用户 2026-09-16 11:34/11:39 截图 + 真机日志 `webrtcdemo-logs-20260916-033453Z.zip`（sha256 `907d45cc…`），解压于 `/opt/dsh-workspaces/tmp/n1/x/app.log`
- inScope：`webrtc/CallSession.kt`、`webrtc/VideoRendererPool.kt`（本任务未改）、`ui/call/`、`app/src/test/kotlin/`、本报告
- 未触碰：`app/src/main/cpp/**`、`signaling/**`（Go）、`doc/**`；未跑宿主机 Gradle、未发布、未 commit

---

## 0. 摘要

**症状**：通话完全正常（P2P 与 RELAY 两种形态均 `down≈2.0 Mbps / up≈1.5–2.4 Mbps`，画面双向流畅），但房主端**持续**显示红色横幅
「ICE 未连通（本端候选 host=6,srflx=1,relay=2；对端候选 -）。可在诊断页打开「强制中继」后重试，并立即导出日志」。
（用户已确认 t68/t71 生效：**不再自动退房**；本缺陷是残留的误报横幅。）

**两条叠加根因**：

| # | 根因 | 机制 |
|---|---|---|
| ① | `rearmWatchdogOnRelay()` 把**已用时长**当**档位**传给 `checkConnectivity(connection, afterMs)`，而基数 `connectivityWatchdogStartMs` 未初始化（relay 候选**早于**看门狗启动到达）⇒ 该"时长"= **epoch 毫秒** | `afterMs >= ICE_FAIL_MS` 恒真 ⇒ 在 `ice_state=NEW`、远端候选尚未到达时立刻 `AppLog.e("ice_timeout")` + `listener.onError("ICE 未连通…")` |
| ② | `onError` 的横幅是**一次性错误文本、没有恢复清除路径**（t68 只压了 `phase=failed` 相位文案，没管这条 notice） | 即使随后 `state=CONNECTED`、媒体正常流动，横幅仍留在屏幕上 |

**修复（四件事）**：① 基数守卫（rearm 不调用 + stop 复位）；② 档位/时长一致性守卫（拒绝 epoch 量级档位）；③ 恢复清除路径（CONNECTED / 媒体判活 ⇒ 清横幅，且媒体判活不再弹）；④ 全仓排查 `checkConnectivity` 全部调用点（§5）。

**离线预检（仓库树，宿主机 kotlinc-embeddable + JUnit4，未跑 Gradle）**：`MAIN_RC=0` / `TEST_RC=0` / **19 类 / 186 用例 / 0 failures**（t75 基线 18 类/180 例 ⇒ +1 类/+6 例，均为本任务新增）。
**old-red 对照**：把三处守卫回退为修复前行为 ⇒ **3 failures**（§4.2）。

---

## 1. 根因证据链（真机日志逐行）

`/opt/dsh-workspaces/tmp/n1/x/app.log`（同一秒内自证误报）：

```
115  03:34:24.872 INFO  kotlin pc  ice_watchdog_rearmed candidate=…relay=1 evt=16 reason=relay_candidate elapsed_ms=1789529664872
116  03:34:24.873 ERROR kotlin pc  ice_timeout after_ms=1789529664872 ice_state=NEW local_candidates=host=6,srflx=1,relay=1
                                  remote_candidates=- remote_desc_set=false transport=NEW relay_missing=false
119  03:34:24.895 INFO  kotlin pc  ice_watchdog_rearmed candidate=…relay=2 evt=18 reason=relay_candidate elapsed_ms=1789529664895
120  03:34:24.896 ERROR kotlin pc  ice_timeout after_ms=1789529664895 ice_state=NEW local_candidates=host=6,srflx=1,relay=2
129  03:34:25.130 INFO  kotlin pc  ice_watchdog_started evt=22 fail_ms=45000 timeout_ms=30000 turn_configured=true
131  03:34:25.132 INFO  kotlin pc  ice_watchdog_rearmed candidate=…relay=3 evt=23 reason=relay_candidate elapsed_ms=2
161  03:34:25.435 INFO  kotlin pc  selected_candidate_pair … mode=P2P reason=candidate_pair_state_changed
163  03:34:25.436 INFO  kotlin pc  ice_watchdog_ok elapsed_ms=306
165  03:34:25.438 INFO  kotlin pc  pc_ice_connection_state state=CONNECTED
```

判读：
- `elapsed_ms=1789529664872` = **自 epoch 起的毫秒数** ⇒ `connectivityWatchdogStartMs == 0L`（看门狗此时**还没启动**，evt=16/18 早于 evt=22 的 `ice_watchdog_started`）；
- 因为 `afterMs` 拿到了这个 epoch 值，`checkConnectivity` 里 `afterMs >= ICE_FAIL_MS(45 s)` **恒真** ⇒ 直接 `ice_timeout` + `onError`；
- 300 ms 内即 `ice_watchdog_ok elapsed_ms=306` + `state=CONNECTED`，随后 stats 全程正常（如 `down_bps≈2.0 Mbps`）⇒ **误报**；
- 误报产生的错误文本落进 `CallUiState.error` 后**没有任何路径清除**，于是"通话正常 + 红色横幅"长期并存。

---

## 2. 实现

### 2.1 ① 基数守卫（`webrtc/CallSession.kt`）

| 内容 | file:line |
|---|---|
| `rearmWatchdogOnRelay()`：先读基数，`IceWatchdogPolicy.shouldRearmCheck(connectivityWatchdog != null, startedAt)` 为假 ⇒ **只落诊断并 return**（不计算 elapsed、不调用 `checkConnectivity`） | :1235-1262（守卫 :1237-1255） |
| 跳过时诊断（含验收要求的字面字段） | :1246 `"ice_watchdog_rearmed reason=relay_candidate skipped=no_watchdog"`（另带 `watchdog_started`/`relay`/`candidate`） |
| `stopConnectivityWatchdog()`：**复位基数** `connectivityWatchdogStartMs = 0L`（不得让旧世代基数被复用） | :1280-1289（复位 :1286） |

### 2.2 ② 档位/时长一致性守卫（`webrtc/CallSession.kt`）

| 内容 | file:line |
|---|---|
| `checkConnectivity()` 内在"已连通判定"之后加守卫：按基数算真实耗时，`IceWatchdogPolicy.isStaleTier(afterMs, startedAt, now, ICE_FAIL_MS)` 为真 ⇒ 落 `ice_watchdog_stale_tier tier_ms=… elapsed_ms=… action=skip` 并 **return**（不上报失败、不回调 `onError`） | :1066-1086（日志 :1075-1084） |
| 覆盖"档位被误传为 epoch 毫秒"这一具体形态：基数无效（`<=0`）⇒ `realElapsedMs` 返回 `-1` ⇒ 判为 stale | :1066-1086 + `IceWatchdogPolicy.realElapsedMs`（:1526-1527） |
| 纯判定对象 `IceWatchdogPolicy`（`shouldRearmCheck` / `realElapsedMs` / `isStaleTier`，生产代码调用同一实现） | :1503-1542（追加于文件末尾） |

### 2.3 ③ 恢复清除路径（`ui/call/`）

| 内容 | file:line |
|---|---|
| 纯判定 `CallSurvivability.isIceErrorText(message)`（从 `shouldSurfaceError` 抽出，口径一致） | `ui/call/CallSurvivability.kt:190-196` |
| 纯判定 `CallSurvivability.shouldClearIceError(bannerText, phaseConnected, mediaAlive)`：横幅存在 + ICE 类文案 + （已连通 或 媒体判活）⇒ 清除；非 ICE 文案**不清** | `ui/call/CallSurvivability.kt:198-218` |
| 通话页 `clearIceErrorBannerIfRecovered(status)`：清除 `_uiState.error` 并落 `ice_error_cleared reason=connected\|media_alive`（含 phase/media_source/media_age_ms/pair/banner/seq/session） | `ui/call/CallViewModel.kt:1724-1757`（日志 :1741） |
| 调用点：**状态机每次发布**（帧/下行字节/传输 CONNECTED/统计采样都会经此）⇒ 恢复即清 | `ui/call/CallViewModel.kt:1462-1468`（`publishConnStatus` 内） |
| 「媒体已判活时不再弹」沿用 t68 同口径：`onError` 仍先过 `CallSurvivability.shouldSurfaceError(message, mediaAlive)` | `ui/call/CallViewModel.kt:1214-1235`（t68 逻辑未改） |

> 设计说明：把清除挂在 `publishConnStatus` 上（而非只挂 `onIceEvent(CONNECTED)`），是为了覆盖"ICE 事件丢失/乱序但媒体已在流"的真机形态（真机同一秒内有 `selected_candidate_pair` + `state=CONNECTED` + `down≈2.0 Mbps`，三条任一路径都能触发清除）。

### 2.4 ④ 同类隐患全仓排查（`checkConnectivity` 全部调用点）

| 调用点（t80 前 → 现 line） | 传参 | 判定 |
|---|---|---|
| `:1044`（现 :1044） | `ICE_WARN_MS`（常量档位） | **安全**：档位语义正确；且 30 s < `ICE_FAIL_MS`，不会进失败分支 |
| `:1045`（现 :1045） | `ICE_FAIL_MS`（常量档位） | **安全（并已被新守卫覆盖）**：若真实耗时不足则 `stale_tier action=skip` |
| `:1087`（现 :1108，awaiting_relay 顺延） | `deferred = afterMs + ICE_DEFER_STEP_MS`（档位语义） | **安全**：由档位累加得出；同样受守卫保护 |
| `:1112`（现 :1133，ice_timeout_restarting 顺延） | 同上 | **安全**：同上（真实到档才上报） |
| `:1229`（现 :1269，`maxOf(elapsed, ICE_WARN_MS)`） | **已用时长** —— 缺陷点 | **已修**：由 ①（`shouldRearmCheck` ⇒ 基数无效时根本不调用）+ ②（`isStaleTier` ⇒ 即便被调用也跳过）+ `stopConnectivityWatchdog` 复位基数三重覆盖 |

**结论：全仓仅此一处"时长当档位"的误传，且现在不可能再触发失败上报。**

---

## 3. 改动清单（file:line + sha256）

| 文件 | 类型 | sha256 |
|---|---|---|
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt` | 改（基数守卫 / 档位守卫 / stop 复位 / 新增纯判定对象） | 见交付消息 |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt` | 改（`isIceErrorText` 抽取 + `shouldClearIceError`） | 见交付消息 |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` | 改（`clearIceErrorBannerIfRecovered` + `publishConnStatus` 接线） | 见交付消息 |
| `app/src/test/kotlin/com/example/webrtcdemo/webrtc/IceWatchdogPolicyTest.kt` | **新增**（6 例） | 见交付消息 |
| `code/webrtc-demo/reports/44-ice-watchdog-false-failure.md` | **新增** | 见交付消息 |

`webrtc/VideoRendererPool.kt` 在 inScope 内但**无需改动**（缺陷与渲染器池无关）。

---

## 4. 单测与 old-red

### 4.1 点名用例（`app/src/test/kotlin/com/example/webrtcdemo/webrtc/IceWatchdogPolicyTest.kt`，6 例）

| 验收 | 用例 | 断言要点 |
|---|---|---|
| ① 基数=0 时 rearm 不产生失败调用 | `baseZeroRearmMustNotTriggerFailureCheck` | `shouldRearmCheck(false, 0)=false`、`(true, 0)=false`、`(true, -1)=false`、`(true, 有效基数)=true`；并复现"基数 0 时'时长'= epoch 毫秒 ≥ 30 s 档"这一旧行为 |
| ① 基数无效 ⇒ 耗时未知 | `realElapsedIsUnknownWhenBaseInvalid` | `realElapsedMs(0, epoch)= -1`；`(1000, 1306)=306` |
| ② epoch 量级档位 + 真实耗时不足 ⇒ 跳过 | `epochSizedTierIsStaleWhenRealElapsedIsShort` | `isStaleTier(1789529664872, startMs=0, now, 45 s)=true`；`(45 s, now-306 ms)=true`；`(30 s 档)=false` |
| ② 真实到档仍如实失败 | `legitFailTierStillPassesWhenRealElapsedReached` | `(45 s, now-45001)=false`；`(50 s, now-60000)=false` |
| ③ 连通/媒体判活 ⇒ 清横幅 | `iceBannerClearedWhenConnectedOrMediaAlive` | 真机原文案 + `connected=true` ⇒ true；+ `media_alive=true` ⇒ true；中继类文案同理；无横幅/未恢复 ⇒ false |
| ③ 非 ICE 文案不清 + 媒体判活不再弹 | `nonIceBannerIsNotClearedByRecoveryPath` | `"服务端错误: ROOM_FULL"`/`"对端无响应…"` ⇒ false；`shouldSurfaceError(ICE 文案, mediaAlive=true)=false` |

### 4.2 离线全量复跑（仓库树；未跑 Gradle）

```text
=== compile main ===
MAIN_RC=0
=== compile tests ===
TEST_RC=0
=== run JUnit ===
classes: 19
JUnit version 4.13.2
Time: 0.313

OK (186 tests)
```

- **19 类 / 186 用例 / 0 failures**（t75 基线 18 类/180 例 ⇒ +1 类/+6 例）。

### 4.3 old-red 对照（回退三处守卫 ⇒ 必红）

方法：在 `/tmp` 副本把 `IceWatchdogPolicy.shouldRearmCheck` 改为 `true`、`isStaleTier` 改为 `false`、`CallSurvivability.shouldClearIceError` 改为 `false`（= 修复前行为），其余不变后重编重跑：

```text
PATCHED(old: no base guard / no stale-tier guard / no banner clear)
1) baseZeroRearmMustNotTriggerFailureCheck(IceWatchdogPolicyTest)
2) iceBannerClearedWhenConnectedOrMediaAlive(IceWatchdogPolicyTest)   → :117
3) epochSizedTierIsStaleWhenRealElapsedIsShort(IceWatchdogPolicyTest) → :59 epoch 量级档位 + 基数无效 ⇒ stale
Tests run: 186,  Failures: 3
```

⇒ 三个修复点都有对应用例钉住（回退即红）。

---

## 5. 纪律与边界

### 5.1 verify 命令原始输出

```bash
$ cd /data/dsh/home/workspace/code/webrtc-demo && grep -n 'connectivityWatchdogStartMs\|ice_watchdog_stale_tier\|ice_error_cleared\|skipped=no_watchdog' app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt | head -20
236:    private var connectivityWatchdogStartMs = 0L
1033:            connectivityWatchdogStartMs = System.currentTimeMillis()
1072:        val watchdogStartedAt = connectivityWatchdogStartMs
1077:                "ice_watchdog_stale_tier",
1238:        // 或上一世代 stop 后基数未复位 ⇒ 此时 `now - connectivityWatchdogStartMs` 等于 **epoch 毫秒**。
1242:        val startedAt = connectivityWatchdogStartMs
1246:                "ice_watchdog_rearmed reason=relay_candidate skipped=no_watchdog",
1281:                mapOf("elapsed_ms" to (System.currentTimeMillis() - connectivityWatchdogStartMs).toString()),
1286:        connectivityWatchdogStartMs = 0L
1508:// 根因：`rearmWatchdogOnRelay()` 拿 `now - connectivityWatchdogStartMs` 当**档位**传给
（退出码 0）
```

```bash
$ cd /data/dsh/home/workspace/code/webrtc-demo && grep -rn 'ice_error_cleared\|stale_tier\|no_watchdog' app/src/main/kotlin/com/example/webrtcdemo/ui/call app/src/test/kotlin | head -10 && ls -l reports/44-ice-watchdog-false-failure.md && git status --porcelain -- app/src/main/cpp signaling doc
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1731:     * 清除时落 `ice_error_cleared` 诊断，供复测一行确认"误报横幅已被自动收回"。
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1741:            "ice_error_cleared",
（实际行见下方最终 verify；本报告 mode 644）
（`git status --porcelain -- app/src/main/cpp signaling doc` 为**空输出**；退出码 0）
```

### 5.2 outOfScope 自检

```text
$ git status --porcelain -- app/src/main/cpp signaling doc
（空输出 ⇒ cpp/**、根 signaling/、doc/** 零改动）
```
- 未跑 Gradle；未发布；未 git commit（仅 `grep`/`sha256sum`/`ls`/`git status`/`wc` 只读命令 + 本任务源码/测试/报告写入）。

### 5.3 时序

- **`app/src` 最后写入时刻 = 2026-09-16 11:45:45 (+0800)**（`IceWatchdogPolicyTest.kt`；源码侧 `CallSession.kt` 11:45:06、`CallSurvivability.kt` 11:45:19、`CallViewModel.kt` 11:45:33）⇒ 供后续构建任务作 T1 依据。

---

## 6. 未验证项（诚实清单）

| # | 未验证项 | 为何交付前不可验 | 判据（真机复测） |
|---|---|---|---|
| V1 | **真机不再出现「ICE 未连通」误报横幅** | 需真机 + 走中继/P2P 建连（容器无设备） | 复测日志**不应**再出现 `ice_timeout after_ms=<epoch 量级>`；relay 候选早到时应见 `ice_watchdog_rearmed reason=relay_candidate skipped=no_watchdog` |
| V2 | 误报后横幅**自动被收回** | 同上 | 出现 `ice_error_cleared reason=connected`（或 `reason=media_alive`）且界面红色横幅消失（截图对照） |
| V3 | **真失败仍如实上报**（守卫未把真故障吞掉） | 同上（需构造真正连不上的场景） | 45 s 后仍应出现 `ice_timeout after_ms=45000…`（不带 epoch 量级）+ 横幅；且**不**出现 `ice_watchdog_stale_tier` |
| V4 | `stopConnectivityWatchdog` 复位基数后，跨世代不再有巨型 elapsed | 需多次进出房间真机复测 | 连续两世代日志中 `ice_watchdog_rearmed elapsed_ms` 均为正常小值（< 60 s） |
| V5 | Gradle/APK 层（编译 + 单测） | 本任务禁止跑 Gradle（属后续构建任务） | 构建任务记录 `:app:testDebugUnitTest` **≥19 类 / ≥186 用例**、0 failures |
| V6 | 其它潜在误报源（如 `ICE_DEFER_STEP_MS` 顺延链上的极端抖动） | 需真机长尾样本 | 若复测见 `ice_watchdog_stale_tier action=skip`，说明守卫在工作（非故障） |

---

## 7. 交接

- **后续构建任务**：以 §5.3 的「app/src 最后写入时刻」起算静默窗口；单测闸门 **≥19 类 / ≥186 用例**。
- **verifier**：核心证据 = §1 真机日志链（同一秒内 `ice_timeout after_ms=epoch` → `ice_watchdog_ok elapsed_ms=306` → `state=CONNECTED`）、§4.3 old-red（3 failures）、§2.4 调用点排查表。
- 与 t68/t71 的关系：t68（媒体存活不报 ICE 失败）**只覆盖相位文案**，本任务补上"一次性 notice 的恢复清除"；t71/t75（重连预算与 glare）与本缺陷无耦合。
