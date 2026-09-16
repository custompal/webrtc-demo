# t83 报告：远端候选计数两处缺陷（回放重复计数 + SDP 内候选不入计数）

- 任务：t83（implementation r1，attempt 1，attempt_id `a71b2139-9026-4a7c-a991-db6fa9cff1d3`）
- 承办：android-dev
- 缺陷来源：android-dev 只读审计（captain 已自证代码）；真机日志 `webrtcdemo-logs-20260916-033453Z`（解压于 `/opt/dsh-workspaces/tmp/n1/x/app.log`）
- inScope：`webrtc/CallSession.kt`、`app/src/test/kotlin/`、本报告
- 未触碰：`app/src/main/cpp/**`、`signaling/**`（Go）、`doc/**`；`webrtc/IceCandidateInfo.kt` **零改动**（按 captain 裁定"全部逻辑留在 CallSession.kt"）
- 未跑宿主机 Gradle、未发布、未 commit

---

## 0. 摘要

「ICE 未连通」横幅里的**对端候选数**是定因的关键证据，但计数器存在两处使数字**不可信**的缺陷：

| # | 缺陷 | 机制 | 影响 |
|---|---|---|---|
| ① | **回放路径重复计数** | `candidateCounter.addRemote(info)` 全仓唯一调用点 `CallSession.kt:591`（`onRemoteIceCandidate` 内）；t51 的「PC 未就绪入队 → 就绪回放」路径会把队列里的候选**再次**送进同一入口（`flushPendingRemote()` 逐条重调）⇒ 同一候选被计两次 | 真机 `host=6,srflx=1,relay=2` 可能被**放大**（早到的那批候选各多算一次）⇒ 复测定因被误导 |
| ② | **SDP 内候选从不入计数（假阴性）** | 代码只用 `IceCandidateInfo.summarizeSdpCandidates(sdp)` 打诊断日志（`:406/:481/:527`），未喂给计数器 | 对端若把候选只写在 SDP 里（不 trickle 或被抑制），`remoteSummary()` 显示 `-`，而链路其实可用；且 `-` 无法区分「确实没有」与「只在 SDP 里」 |
| ③ | 逐条应用日志无上限 | `ice_candidate_remote` 每条候选打一行 | 大候选量时刷屏（诊断可读性下降） |

**修复**：① `viaReplay` 去重计数；② SDP 候选独立分账 + `ice_candidate_remote_total trickled=… sdp=… summary=…` + 横幅改为合并口径（两侧皆空写 `-（trickled 与 SDP 内均无）`）；③ 逐条日志限频（首 3 条 + 每 10 条），总数仍可见。

**离线预检（仓库树，宿主机 kotlinc-embeddable + JUnit4，未跑 Gradle）**：`MAIN_RC=0` / `TEST_RC=0` / **20 类 / 191 用例 / 0 failures**（t80 基线 19 类/186 例 ⇒ +1 类/+5 例，全部为本任务新增）。
**old-red**：回退四处策略 ⇒ **4 failures**（§4.3）。

---

## 1. 缺陷定因（file:line，均为修复前）

- **唯一计数点与入队/回放**：
  - `webrtc/CallSession.kt:591` `candidateCounter.addRemote(info)`（`onRemoteIceCandidate` 内，trickle 远端候选唯一入口）；
  - `gate()`（`:724-742`）语义：`PROCEED`（PC 就绪）⇒ **不调用** `store()`（不入队）；`DEFER` / READY-无 PC ⇒ `store()` 入队；
  - `flushPendingRemote()`（`:1036+`）排空暂存队列并**逐条重调** `onRemoteIceCandidate(...)`（原 `:1064`）⇒ 回到 `:591` **再计一次**（回放时 `gate` 返回 PROCEED ⇒ 不再入队，故只多计一次、不无限增长）。
- **SDP 内候选**：`IceCandidateInfo.summarizeSdpCandidates(...)` 仅在 `:406`（本地 SDP 诊断）、`:481`、`:527`（`answer_received`）用于日志，**从未**进入 `candidateCounter`。
- **逐条日志**：原 `:632` `AppLog.i(TAG, "ice_candidate_remote", …)` 无条件落盘。

**为何 PROCEED 不入队也必须去重**：入队发生在"首次到达但 PC 未就绪"时，**计数已经发生**；回放只是把同一候选真正应用一次 —— 因此"计数"必须绑定"首次进入"，而不是"每次进入"。

---

## 2. 实现（全部在 `webrtc/CallSession.kt`）

### 2.1 ① 去重计数

| 内容 | file:line |
|---|---|
| `onRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex, viaReplay: Boolean = false)` 新增回放标记 | :578-583 |
| 计数守卫：`if (RemoteCandidateAccounting.shouldCount(viaReplay)) { candidateCounter.addRemote(info); remoteCandidateTallied++ }` | :610-615 |
| 限频日志调用 `logRemoteCandidateIfNeeded(info, viaReplay)` | :616 |
| `flushPendingRemote()` 回放逐条传 `viaReplay = true` + 汇总落盘 | :1074-1077 |
| 纯判定 `RemoteCandidateAccounting.shouldCount(viaReplay) = !viaReplay` | :1673 |

### 2.2 ② SDP 候选独立分账 + 消除 `-` 歧义

| 内容 | file:line |
|---|---|
| 字段 `sdpRemoteCandidateCount`（SDP 内候选累计） | :248-256 |
| `accountSdpCandidates(sdp, trigger)`：`countSdpCandidates(sdp)` 累加 + `logRemoteCandidateTotal(trigger)` | :1113-1124 |
| 调用点：`onRemoteOffer`（覆盖 ICE restart 后对端重发的新 SDP） | :456-458 |
| 调用点：`onRemoteAnswer` | :563-565 |
| 汇总诊断 `logRemoteCandidateTotal(trigger)`：落 `ice_candidate_remote_total trickled=… sdp=… summary=…` | :1146-1158 |
| 纯函数 `countSdpCandidates(sdp)`（按 `a=candidate:` / `candidate:` 计行） | :1682-1687 |
| 纯函数 `mergedSummary(trickledSummary, sdpCount)`（四态合并口径） | :1697-1707 |
| `checkConnectivity` 的 `fields`：`remote_candidates` 改为合并口径，另加 `remote_trickled` / `remote_sdp` 分项 | :1205-1212 |
| **横幅文案**（两条）改用合并口径 ⇒ 两侧皆空时 `-（trickled 与 SDP 内均无）` | :1231-1246 |

### 2.3 ③ 逐条日志限频

| 内容 | file:line |
|---|---|
| `logRemoteCandidateIfNeeded(info, viaReplay)`：首 3 条 + 每 10 条；日志含 `total`/`via_replay`/`sdp_remote`（限频跳过的条目**不丢总数**，总数由 `ice_candidate_remote_total` 保证可见） | :1126-1144 |
| 应用点不再无条件打日志（原 `:632` 删除，改为上方限频） | :1178-1181 |
| 纯判定 `shouldLogRemoteCandidate(ordinal) = ordinal <= 3 \|\| ordinal % 10 == 0` | :1709-1710 |

> `IceCandidateInfo.kt` **未改**：`IceCandidateCounter` 的数据结构保持原样（避免放大回归面），SDP 分账只是 `CallSession` 侧的会话级口径。

---

## 3. 改动清单（file:line + sha256）

| 文件 | 类型 | sha256 |
|---|---|---|
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt` | 改 | 见交付消息 |
| `app/src/test/kotlin/com/example/webrtcdemo/webrtc/RemoteCandidateAccountingTest.kt` | **新增**（5 例） | 见交付消息 |
| `code/webrtc-demo/reports/46-remote-candidate-counting.md` | **新增** | 见交付消息 |

---

## 4. 单测与验证

### 4.1 点名用例（`RemoteCandidateAccountingTest`，5 例）

| 要求 | 用例 | 断言要点 |
|---|---|---|
| ① 去重 | `enqueueThenReplayCountsOnlyOnce` | 首次进入 `shouldCount(false)=true` ⇒ `addRemote`；回放 `shouldCount(true)=false` ⇒ **不再计** ⇒ `remoteSummary()=="host=1"`；再显式计一次才到 `host=2`（证明差异来自回放重复计数） |
| ② SDP 计入 | `sdpCandidatesAreCountedIndependently` | 3 条 `a=candidate:` ⇒ `countSdpCandidates==3`；无候选 SDP ⇒ 0；无 `a=` 前缀的 `candidate:` 也认 |
| ② 消歧 | `mergedSummaryRemovesDashAmbiguity` | `("-",0)` ⇒ `-（trickled 与 SDP 内均无）`；`("-",3)` ⇒ 含 `SDP内 3` 且**不等于** `-`；纯 trickle ⇒ 不变；两者都有 ⇒ `host=2 + SDP内 1` |
| ③ 限频 | `remoteCandidateLoggingIsRateLimited` | 1..25 ⇒ 落盘序号 `[1,2,3,10,20]` |
| t80 耦合守护 | `mergedBannerStillRecognizedAsIceError` | t83 后 4 条实际横幅文案逐条断言 `CallSurvivability.isIceErrorText(...)==true` 且 `shouldClearIceError(..., connected=true/mediaAlive=true)==true` ⇒ **文案改动不会让 t80 的恢复清除路径失效** |

### 4.2 离线全量复跑（仓库树；未跑 Gradle）

```text
=== compile main ===
MAIN_RC=0
=== compile tests ===
TEST_RC=0
=== run JUnit ===
classes: 20
JUnit version 4.13.2
Time: 0.314

OK (191 tests)
```

- **20 类 / 191 用例 / 0 failures**（t80 基线 19 类/186 例 ⇒ +1 类/+5 例）。

### 4.3 old-red 对照（回退四处策略 ⇒ 必红）

方法：`/tmp` 副本把 `shouldCount`→`true`、`countSdpCandidates`→`0`、`mergedSummary`→裸摘要、`shouldLogRemoteCandidate`→`true`（= 修复前行为），其余不变后重编重跑：

```text
PATCHED(old: replay double-count / sdp not counted / bare dash / no rate limit)
1) sdpCandidatesAreCountedIndependently(RemoteCandidateAccountingTest)
2) enqueueThenReplayCountsOnlyOnce(RemoteCandidateAccountingTest)
3) remoteCandidateLoggingIsRateLimited(RemoteCandidateAccountingTest)   → :86
4) mergedSummaryRemovesDashAmbiguity(RemoteCandidateAccountingTest)     → expected:<-[（trickled 与 SDP 内均无）]> but was:<-[]>
Tests run: 5,  Failures: 4
```

⇒ 三个修复点各有对应用例钉住（回退即红）；守护例 `mergedBannerStillRecognizedAsIceError` 在 old/new 下均通过（它只约束文案与 t80 判据的耦合，与本次回退无关）。

---

## 5. 对既有复测判读的影响（验收要求）

- **此前真机日志里的 `对端候选 host=6,srflx=1,relay=2` 可能被放大**：若其中部分候选是在 PC 就绪前到达（t51 入队路径），它们各被多计一次 ⇒ 数字偏大。**结论：修复前请勿用候选条数做定量判断**（尤其"对端候选 6 个"这类计数）；修复后该数字可信。
- **`对端候选 -` 的含义变化**：修复前 `-` 有两种可能（确实没有 / 只在 SDP 里）；修复后横幅与 `remote_candidates` 字段给出合并口径，日志另有 `remote_trickled` / `remote_sdp` 分项与 `ice_candidate_remote_total trickled=… sdp=… summary=…` 汇总行 ⇒ 一眼可分。
- 与 t80 的关系：本次改了横幅文案，已加**守护单测**确保仍被 `CallSurvivability.isIceErrorText` 识别 ⇒ t80 的 `ice_error_cleared` 恢复清除路径不受影响。

---

## 6. 纪律与边界

### 6.1 verify 命令原始输出

```bash
$ cd /data/dsh/home/workspace/code/webrtc-demo && grep -n 'addRemote\|viaReplay\|sdp_remote\|ice_candidate_remote_total' app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt | head -20
（见交付消息；关键：`addRemote` 仅 1 处且被 `shouldCount(viaReplay)` 守卫、`viaReplay` 出现在签名/回放/日志、`sdp_remote` 出现在限频日志字段、`ice_candidate_remote_total` 出现在汇总诊断）
```

```bash
$ cd /data/dsh/home/workspace/code/webrtc-demo && ls -l reports/46-remote-candidate-counting.md && git status --porcelain -- app/src/main/cpp signaling doc
（见交付消息；`ls` 显示本报告 mode 644；`git status` 三目录**空输出**）
```

### 6.2 outOfScope 自检

```text
$ git status --porcelain -- app/src/main/cpp signaling doc
（空输出 ⇒ cpp/**、根 signaling/、doc/** 零改动）
```
- `webrtc/IceCandidateInfo.kt` 未改（`git status` 中不出现）⇒ 贴合 t83 inScope，零 scope 扩张。
- 未跑 Gradle；未发布；未 commit。

### 6.3 时序

- **`app/src` 最后写入时刻 = 见交付消息**（本任务源码/测试写入时刻；`reports/46` 随后写入）⇒ 供后续构建任务作 T1。

---

## 7. 未验证项（诚实清单）

| # | 未验证项 | 为何交付前不可验 | 判据（真机/构建） |
|---|---|---|---|
| V1 | 真机复测中"对端候选"数字不再被放大 | 需真机 + PC 未就绪窗口内确有候选到达（时序相关） | 日志中 `ice_candidate_remote_total trickled=…` 应与 `ice_replayed count=N` 不产生重复加成；`ice_candidate_remote` 的 `total` 单调且等于 `trickled` 条数 |
| V2 | SDP 内候选分账在真机的取值合理 | 需真机（对端是否把候选写进 SDP） | 出现 `ice_candidate_remote_total trickled=… sdp=N`；若 `trickled=-` 而 `sdp>0` ⇒ 修复前会误显示 `-`（本次已消歧） |
| V3 | 限频后诊断仍可读 | 需真机大候选量场景 | 首 3 条 + 每 10 条 `ice_candidate_remote`，且 `ice_candidate_remote_total` 每次变化一条 ⇒ 总数可核对 |
| V4 | 横幅新文案的真机观感 | 需真机复现 ICE 失败 | 文案含 `-（trickled 与 SDP 内均无）` 或 `+ SDP内 N`；且 t80 的 `ice_error_cleared` 仍能在恢复时清掉它 |
| V5 | Gradle/APK 层 | 本任务禁止跑 Gradle | 后续构建记录 `:app:testDebugUnitTest` **≥20 类 / ≥191 用例**、0 failures |
| V6 | `viaReplay` 默认参数对其它调用方无影响 | 需全仓确认（已完成：`onRemoteIceCandidate` 仅两处调用 —— 信令入口与回放） | 编译期默认参数 + 既有全量用例全绿（191/191） |

---

## 8. 交接

- **后续构建任务**：以 §6.3 的「app/src 最后写入时刻」起算静默窗口；单测闸门 **≥20 类 / ≥191 用例**。
- **verifier**：核心证据 = §1 缺陷机制（含 `gate()` 语义与 `flushPendingRemote()` 重调链）、§4.3 old-red（4 failures）、§5 对既有复测判读的影响说明。
- **与 t80 的耦合**：横幅文案改动已由 `mergedBannerStillRecognizedAsIceError` 守护（t80 恢复清除路径不受影响）。
