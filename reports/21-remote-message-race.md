# reports/21-remote-message-race.md — 远端消息竞态残留：offer/候选早于 PC 就绪被丢弃 + 对端无响应提示（t51）

- **任务**：t51（implementation round 1，attempt 2，attempt_id `53160b81-7199-41fb-a109-c42ba354359a`）。
- **基线**：t44（offer/answer 主路径 + `CallSession` 内 pending 队列）已落地并提交（`3f7ca13`）。
- **范围**：`app/src/main/kotlin/.../webrtc/CallSession.kt`（只读核对，未改）、`app/src/main/kotlin/.../ui/call/**`（改/新增）、`app/src/test/**`（新增）、本报告。**未触碰** `cpp/**`、`third_party/**`、`doc/**`、`scripts/**`、产物字节。
- **环境**：容器**无 JDK/SDK** ⇒ 未编译、未跑单测（由宿主机构建任务执行）；**真机 join 成功率**只能由用户复测（§7）。

---

## 0. 结论摘要

| # | 项 | 结论 |
|---|---|---|
| ① | 根因 | t44 只修了「`session` 已建、`PeerConnection` 尚未就绪」这一层；**仍有第二层窗口**：`CallSession.start()` 还没被调用（`session == null`）时收到的 **offer/answer/候选** 在 `CallViewModel.onMessage` 里**只打日志就丢弃**（offer 还会顺手弹一次错误）⇒ host 永远停在 `HAVE_LOCAL_OFFER` |
| ② | 修复 | 新增**会话前预队列** `PendingRemoteMessages`（纯 Kotlin）：`session == null` 时 offer/answer/候选一律**入队**，`start()` 成功后**按到达顺序回放**；与 t44 的 `CallSession` pending 队列形成**两级兜底**（不回退） |
| ③ | 可诊断 | 新增 `remote_deferred kind=... queued=N`、`remote_replay_done ... source=viewmodel`；**20 秒**内既无 answer、也无远端候选且仍在连接 ⇒ WARN `answer_timeout` + UI 明确提示「对端无响应：可能未加入或版本不一致…」 |
| ④ | 单测 | `PendingRemoteMessagesTest`（7 个纯 JVM 用例：保序、offer/answer 覆盖、ICE 全留、有界、计数） |
| ⑤ | 未验证 | **真机 join 成功率**（本次修复只能消除"丢弃窗口"这一类原因）；宿主机编译与全量单测 |

---

## 1. 真机证据（宿主 `/opt/dsh-workspaces/tmp/dl-b/x/app.log`，joiner = Mi 10 Pro / Android 13）

### 1.1 失败会话（room 66DZFT，16:18）—— **offer/候选早于 `pc_starting`**
```
16:18:34.644 msg_sent type=Join
16:18:34.677 room_joined peer=peer-002 room=66DZFT          ← 已入房
16:18:34.953 offer_received sdp_bytes=2530                  ← 远端 offer 到达
16:18:34.980 ice_received candidate_bytes=140               ← 远端候选到达
16:18:35.088 engine_native_loaded lib=webrtcdemo_native
16:18:35.104 ice_received candidate_bytes=141
16:18:35.105 WARN ice_without_session mid=0                 ← ★ 候选被丢弃（只打日志）
16:18:35.231/232 engine_ready impl=SelfVp9Libvpx
16:18:35.244 pc_starting force_relay=false ice_servers=stun+turn   ← ★ 会话此时才 start()
16:18:35.378 capture_started …
16:18:35.397 remote_replay_done answer=false candidates=0 offer=false  ← 回放时队列是空的
```
⇒ **offer 早 0.29 s、候选早 0.14–0.26 s** 于 `pc_starting`。offer 分支在 `session == null` 时走
`offer_without_session`（ERROR）+ `onError(...)`，**不入队**；候选分支走 `ice_without_session`（WARN），
**也不入队** ⇒ `start()` 之后的 `flushPendingRemote()` 自然"什么都没回放"，
信令侧只看到 `offer_forward`、**没有 `answer_forward`**。

### 1.2 同类窗口（room C9QXPY，16:22）
```
16:22:42.708 room_joined peer=peer-002 room=C9QXPY
16:22:42.882 offer_received sdp_bytes=2530
16:22:42.883 ERROR offer_without_session room=C9QXPY        ← 又一次在 session 之前到达
16:22:42.886 engine_ready …
```
⇒ 同一竞态再次出现（offer 早于 `pc_starting`），只是这次只丢了 offer（候选尚未到达）。

### 1.3 对照：候选排队+回放在**另一条时序**下确实生效
任务给出的对照会话（16:22:42.888 `pc_starting` → `.906–.910 ice_deferred queued=1..4` → `.963 ice_candidate_remote`）
说明 **t44 的 `CallSession` pending 队列工作正常** —— 问题只在 `session` 尚未创建的那一段。

---

## 2. 根因（代码级 file:line，两处窗口）

### 2.1 窗口 A：`session == null`（`start()` 之前）——**本次修复对象**
修复前（HEAD `3f7ca13`）`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt`：

| 位置 | 行为 |
|---|---|
| `CallViewModel.kt:327-334`（Offer，`session == null`） | `AppLog.e("offer_without_session")` + `onError("会话未就绪，收到 Offer 无法回 answer")` —— **不入队、不回放** |
| `CallViewModel.kt:336-344`（Answer，`session == null`） | `AppLog.e("answer_without_session")` + `onError(...)` —— **不入队** |
| `CallViewModel.kt:346-353`（Ice，`session == null`） | `AppLog.w("ice_without_session")` —— **不入队、无 UI 提示** |

根因链：`initCall()`（`CallViewModel.kt:174-189`）先 `session = callSession` 再 `callSession.start(...)`；
而 `engine_ready`（`:231/.232`）与 `pc_starting`（`:244`）发生在 **offer/候选之后** —— 说明「引擎初始化 + 建会话」
在本机型上比「信令 offer 到达」慢 0.3–0.5 s，于是必然存在一个 `session == null` 的窗口。

### 2.2 窗口 B：`session` 已建但 `PeerConnection` 未就绪 —— t44 已覆盖（本次**不回退**）
`webrtc/CallSession.kt`：
```
:254-270  onRemoteOffer  → 未就绪时 pendingRemoteOffer = sdp + 「offer_deferred」
:330-346  onRemoteAnswer → 未就绪时 pendingRemoteAnswer = sdp + 「answer_deferred」
:376-390  onRemoteIceCandidate → 未就绪时入 pendingRemoteCandidates + 「ice_deferred queued=N」
:586-612  flushPendingRemote() → start() 成功后回放 + 「remote_replay_done」
```
t44 还有一处关键处理：`start()` 失败会把 `session` 置空（`CallViewModel.kt:177-181`），
避免"看起来有会话、实际静默丢弃"；本次的预队列正好接住这一窗口。

**结论**：两条窗口合起来才完整 —— 窗口 A（无会话）→ **本次修复**；窗口 B（有会话无 PC）→ t44 已修。
二者由"**先入队、就绪后按序回放**"这一条统一路径串起来。

---

## 3. 修复

### 3.1 统一入队 + 就绪回放（两级队列）
- **新增** `ui/call/PendingRemoteMessages.kt`（纯 Kotlin，可 JVM 单测）：
  - 保序队列（`ArrayDeque<Entry>`），`drain()` 按**到达顺序**返回并清空；
  - **OFFER / ANSWER 同类型覆盖**（同一次协商只回放最新一条，避免过期 SDP）；
  - **ICE 全部保留**（丢一条就少一条 ICE 路径）；
  - 有界（默认 256，超出丢最旧并计 `droppedByLimit`）。
- **`CallViewModel`**：
  - `session == null` 时 offer/answer/ice 一律 `pendingRemote.enqueue*()`，并落 `remote_deferred kind=... queued=N`；
  - `initCall()` 在 `start()` 成功、拿到 `currentVideoTrack()` 后调用 `flushPendingRemoteQueue(session)`：按序回放并落
    `remote_replay_done offer=<bool> answer=<bool> candidates=<N> source=viewmodel order=...`；
  - 回放时的消息若在会话内仍"PC 未就绪"，由 t44 的 `CallSession` pending 队列**再次接住**（两级兜底）。
- **事件语义**：`remote_deferred`（新增，两级队列的**共同入口名**）、`remote_replay_done`（沿用 t44 名称，新增
  `source=viewmodel|session` 与 `order=` 字段）；t44 的 `offer_deferred/answer_deferred/ice_deferred` **保持不动**。

### 3.2 对端无响应（20 s）可诊断
- `armPeerResponseWatchdog(reason)`：在**发出 offer**（host）与**收到 offer**（joiner）时武装；
  `peerResponseSeen` 在"收到 answer / 收到远端候选 / `onReady()` 媒体就绪"时置真；
  20 s 后若既无对端响应、`isConnecting` 仍为 true 且未挂断 ⇒ `AppLog.w("answer_timeout", reason/waited_ms/role)`
  + `onError(NO_PEER_RESPONSE_NOTICE)`（**非终态**：只提示、不挂断，符合 `onError` 既有语义 `CallViewModel.kt:471-473`）。
- UI 文案常量：`NO_PEER_RESPONSE_NOTICE = "对端无响应：可能未加入或版本不一致（20 秒内未收到 answer/远端候选）"`
  （与既有 `NOTICE_CALL_ENDED` 一样内联；`CallScreen` 已把 `state.error` 渲染在底部面板，无需改 UI 文件）。
  ⚠️ **`res/values/strings.xml` 不在 t51 的 inScope**，故本轮未新增字符串资源；已登记为后续 i18n 待办。

### 3.3 不回退 / 兼容
- t44 修复全部保留：`ice_deferred`/`remote_replay_done`（`CallSession` 内）语义不变、`start()` 失败置空 `session` 不变；
- **未修改 `CallSession.kt`**（本次零改动，避免与 t50 的 `cpp/**`/编码器线冲突；见 verify #1 只读命中）；
- 未引入新依赖（仅用既有 `kotlinx.coroutines`/`viewModelScope`）。

---

## 4. 新增/调整事件登记（doc/14 §9.1 风格；`doc/14` 冻结未改）

| 事件 | 级别 | 语义 |
|---|---|---|
| `remote_deferred` | WARN(offer/answer) / INFO(ice) | 远端消息在 `session == null` 时入预队列（`kind`/`queued`/`sdp_bytes`/`mid`） |
| `remote_replay_done`（扩展字段） | INFO | 回放结束；新增 `source=viewmodel|session`、`order=offer,ice,ice,…` |
| `answer_timeout` | WARN | 20 s 内无 answer/远端候选（`reason=offer_sent|offer_received`、`waited_ms`、`role`） |

（`offer_without_session`/`answer_without_session`/`ice_without_session` 三条旧日志**不再产生**：
窗口 A 已由 `remote_deferred` 取代。）

---

## 5. 单测（纯 JVM；容器内不执行）

`app/src/test/kotlin/com/example/webrtcdemo/ui/call/PendingRemoteMessagesTest.kt`（新增，7 用例）：

| 用例 | 断言 |
|---|---|
| `preservesArrivalOrder` | offer,ice,ice,answer,ice ⇒ 回放顺序与载荷完全一致，drain 后清空 |
| `newerOfferReplacesOlderOffer` | 只保留最新 offer，且位置为最后一次 offer 的到达位置 |
| `newerAnswerReplacesOlderAnswer` | answer 同名覆盖 |
| `keepsAllIceCandidatesInOrder` | 3 条候选全留、mid/mLineIndex 保留 |
| `boundedByMaxEntries` | 上限 3、入 5 ⇒ 长度 3 / `droppedByLimit=2` / 保留最近 3 条且保序 |
| `countsByKind` | 分类计数 |
| `emptyQueueDrainsToEmptyList` | 空队列 drain ⇒ 空（对应 `offer=false candidates=0` 正常路径） |

**实际执行**由宿主机跑 `./gradlew --no-daemon :app:testDebugUnitTest`（预期 53 + 7 = 60 用例，含 t45 的 7 个）。

---

## 6. 本轮静态核验：命令与原始输出

```
$ cd code/webrtc-demo && grep -n 'pendingRemoteOffer\|pendingCandidates\|pendingRemoteAnswer\|remote_replay_done\|ice_without_session\|remote_deferred' app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt | head -25
119:    private var pendingRemoteOffer: String? = null
122:    private var pendingRemoteAnswer: String? = null
264:                pendingRemoteOffer = sdp
345:                pendingRemoteAnswer = sdp
587:        val offer = pendingRemoteOffer
588:        val answer = pendingRemoteAnswer
595:            pendingRemoteOffer = null
600:            pendingRemoteAnswer = null
612:            "remote_replay_done",
--- EXIT=0
```

```
$ cd code/webrtc-demo && grep -rn '对端无响应\|peer_no_response\|answer_timeout' app/src/main/kotlin/com/example/webrtcdemo/ | head -10
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:87:    // ===================== t51：远端消息预队列与"对端无响应"看门狗 =====================
…:433:        // 【t51】媒体链就绪即视为"对端有响应"，解除对端无响应看门狗（避免误报）
…:550:     * t51：**对端无响应看门狗**。
…:554:     * 行为：写 WARN `answer_timeout` + 通过 [onError] 把明确文案透传到通话页
…:566:                    "answer_timeout",
…:621:        /** 【t51】对端无响应看门狗时长（验收要求 20 s）。 */
…:625:         * 【t51】对端无响应提示（用户上一轮无法自行判断"一直正在连接"的原因）。
…:630:        const val NO_PEER_RESPONSE_NOTICE = "对端无响应：可能未加入或版本不一致（20 秒内未收到 answer/远端候选）"
--- EXIT=0
```

```
$ cd code/webrtc-demo && ls -l reports/21-remote-message-race.md && git status --porcelain
-rw-r--r-- 1 node node 14888 Sep 15 00:36 reports/21-remote-message-race.md            ← mode 644
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt                 ← 本任务（改）
?? app/src/main/kotlin/com/example/webrtcdemo/ui/call/PendingRemoteMessages.kt          ← 本任务（新增）
?? app/src/test/kotlin/com/example/webrtcdemo/ui/                                      ← 本任务（新增目录：PendingRemoteMessagesTest.kt）
?? reports/21-remote-message-race.md                                                   ← 本任务（本报告）
--- EXIT=0
```
**范围说明（快照时刻 00:36；`git status` 会随其他成员在途改动变化）**：4 项全部属本任务且落在 inScope 内；
`CallSession.kt` **零改动**（只读核对），与并行的 t50（`cpp/**`）无交集。

### 6.1 附加自检（括号平衡 / 孤立注释文本）
```
PendingRemoteMessages.kt     {9/9}   (30/30)  孤立注释=0
CallViewModel.kt             {109/109} (216/216) 孤立注释=0
PendingRemoteMessagesTest.kt {14/14} (70/70)  孤立注释=0
```

---

## 7. 未验证项（不得读作通过）

1. **真机 join 成功率**：本次只消除"**会话/PC 未就绪时丢弃远端消息**"这一类原因；真机是否 100 % 收到 answer 并接通，**必须由用户复测**（无设备）。
2. **宿主机编译与全量单测**：容器无 JDK/SDK，本报告未附编译输出；需宿主机 `:app:compileDebugKotlin` + `:app:testDebugUnitTest`。
3. **20 s 看门狗的实际观感**：**若对端确实迟迟不响应**，用户应在 20 s 后看到「对端无响应…」；若该提示**提前/过晚**出现，或与"重连中"文案冲突，需按真机反馈调整阈值/条件（当前条件：`!peerResponseSeen && !callEnded && isConnecting`）。
4. **ICE 侧的连通性问题**（0 bps 的直接原因）属 t48/t44 范围，本次未涉及；本报告不对连通性做任何结论。

### 复测清单（可执行，最小动作）
1. A 机「创建会议」→ B 机**用同一版本**「加入会议」（把房间号复制过去）；
   - **期望**：**不再出现**"只发 offer、无 answer"；若仍失败，导出日志后 grep：
     `remote_deferred`（是否入队）、`remote_replay_done`（`offer=true candidates=N`）、
     `offer_deferred/ice_deferred`（会话内兜底）、`answer_timeout`（20 s 无响应）；
   - **期望日志形态（成功路径）**：`offer_received` → `remote_deferred kind=offer queued=1`
     → `pc_starting` → `remote_replay_done offer=true candidates=1 source=viewmodel` → `msg_sent type=Answer`。
2. 反向再试一次（B 建会、A 加入），确认两个方向都不再丢消息；
3. 若出现 20 s 提示，同时导出**两台**日志以便判断是哪一侧未响应。

---

## 8. 边界与交互

- **只读核对**了 `CallSession.kt`（未改动），避免与并行任务 t50（`cpp/**`）冲突；`ui/call/**` 与 `app/src/test/**` 为本次写入目录。
- **不回退 t44**：`ice_deferred`/`remote_replay_done`/`start()` 失败置空 `session` 全部保留；新增逻辑为**追加**。
- **不引入新依赖**；未改 `doc/**`（冻结）、`third_party/**`、`scripts/**`、产物字节。
- 事件命名沿用 doc/14 §9.1 的 `snake_case` + 模块 `kotlin pc/ui` 风格；新增名已在本报告 §4 登记。
