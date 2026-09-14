# t53 报告：会话生命周期缺陷 —— joiner `answer_create` 早于 `pc_created` 导致单向 0 上行

- 任务：t53「修复单向上行缺失：joiner 在 pc_created 之前发 answer（旧会话/竞态）导致该端 up_bps 恒 0」
- 归属：android-dev（attempt 2）
- 范围（inScope）：`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt`、
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt`、`app/src/test/**`、本报告
- 依据：captain 提供的两份真机日志（宿主 `/opt/dsh-workspaces/tmp/dl-f/x`＝蜂窝端 host、
  `/opt/dsh-workspaces/tmp/dl-g/x`＝WiFi 端 joiner）+ 本轮自行核对的原始时序
- **本报告只做真机日志定因 + 代码级修复 + 离线预检编译/JVM 单测；最终真机双向出画面属未验证项（§7）**

---

## §0 摘要（结论先行）

1. **根因不是"旧会话残留"，而是同一次会话内的顺序缺陷**：旧实现在 `CallSession.start()` 里
   **先发布 `peerConnection`（`:184`），再启动摄像头（`:186` `ensureStarted()`，真机耗时 90–190 ms），
   最后才 `addTrack(本地视频轨)`（`:189`）**。而 `pc_created`（就绪标记，`:200`）比发布点晚 100+ ms。
   于是**信令线程**可以在"PC 已存在、本地视频轨尚未挂载"的窗口里拿到该 PC 直接
   `setRemoteDescription(offer)` → `createAnswer()`（`:258`/`:274`/`:281`）。
2. 真机 U9FQHG（WiFi/joiner）实测：`answer_create` 在 **17:37:06.929（信令线程 19825）**，
   而"摄像头真正就绪"的 `capture_started` 在 **06.931**、`pc_created` 在 **07.031（主线程 19765）**。
   ⇒ 生成的 answer **没有本地视频发送方向**（正常会话 answer 为 2450 B，本次仅 **2312 B**，少 138 B），
   该端此后 `impl=-`、`up_bps=0` 恒定，对端 `down_bps=0` 恒定 ⇒ **单向无画面**。
3. 同机（同一 App/同一 WiFi）**4 分钟前的对照会话 CND45N** 因为 offer 被延迟到 `pc_created` 之后，
   `answer_create` 发生在本地轨挂载之后 ⇒ answer 2450 B、`impl=libvpx`、`up_bps≈1.71 Mbps`、`down_bps≈1.31 Mbps`。
   **两个会话唯一差别就是 answer 是否在本地轨挂载之后生成** —— 这是根因的定量证据。
4. 与 NAT 无关：缺陷会话本身就是 `mode=RELAY`、双向候选对均可用（蜂窝端 `local=srflx→remote=relay`，
   WiFi 端 `local=relay→remote=srflx`），本端 NAT 皆为 `Symmetric` 但中继可用。
5. 修复：**把 `peerConnection` 的发布点推迟到"本地音视频轨挂载 + 编码偏好设置"之后**，
   并加一把**就绪闸门锁**让"未就绪 ⇒ 暂存"与"发布 ⇒ 排空暂存区"互斥（消除丢消息的 TOCTOU）；
   同时按验收补齐"**一次会话只建一个 PeerConnection**""**每次通话都是全新会话 + 旧会话必 close**"
   与**会话标识/递增序号**诊断。

---

## §1 真机证据（原始日志）

### 1.1 缺陷会话：dl-g（WiFi, joiner, room=U9FQHG, pid=19765，2026-09-14）

`app.log` 原文（**按文件顺序**，`[pid/tid]` 中 tid=19765 为主线程、19815 为信令线程、19825 为 libwebrtc 信令线程）：

```
17:37:06.843 INFO  pc        [19765/19765] pc_starting force_relay=false ice_servers=stun+turn
17:37:06.844 INFO  pc        [19765/19765] rtc_config  force_relay=false stun=…:3478 turn=…:3478?transport=udp
17:37:06.900 INFO  signaling [19765/19815] offer_received sdp_bytes=2525          ← 远端 offer 到达（信令线程）
17:37:06.901 INFO  pc        [19765/19815] offer_received sdp_bytes=2525          ← ViewModel 转给 CallSession
17:37:06.914 DEBUG pc        [19765/19825] pc_signaling_state state=HAVE_REMOTE_OFFER  ← setRemoteDescription 已生效
17:37:06.917 INFO  main      [19765/19829] camera_opening camera=1                 ← 摄像头还在打开
17:37:06.928 INFO  pc        [19765/19825] pc_add_track kind=video + remote_track_added kind=video  ← 远端轨(非本地轨)
17:37:06.929 INFO  pc        [19765/19825] answer_create                          ★ 早于 pc_created 102 ms
17:37:06.931 INFO  main      [19765/19765] capture_started device=1 facing=front fps=30 h=480 w=640
                                                                                  ★ ensureStarted() 此刻才返回 ⇒ 本地 addTrack 在此之后
17:37:06.935 INFO  pc        [19765/19825] answer_created candidates=- sdp_bytes=2312  ★ 正常应为 2450
17:37:07.022 INFO  signaling [19765/19825] answer_sent sdp_bytes=2312
17:37:07.023 INFO  pc        [19765/19825] renegotiation_needed                    ★ 协商完成后才 addTrack 的痕迹
17:37:07.031 INFO  pc        [19765/19765] codec_preferences_set codec=VP9 count=1
17:37:07.031 INFO  pc        [19765/19765] pc_created
17:37:07.031 INFO  pc        [19765/19765] remote_replay_done answer=false candidates=0 offer=false
17:37:07.032 INFO  pc        [19765/19765] remote_replay_done answer=false candidates=0 offer=false source=viewmodel
17:37:07.032 INFO  pc        [19765/19765] call_init role=joiner room=U9FQHG
```

该会话整段 stats（`impl` 恒空、`up_bps` 恒 0）：

```
17:37:09.056 stats_sample avail_bps=0 down_bps=0       impl=- local=srflx mode=RELAY remote=relay up_bps=0
17:37:11.058 stats_sample avail_bps=0 down_bps=1447213 impl=- local=srflx mode=RELAY remote=relay up_bps=0
17:37:13.055 stats_sample avail_bps=0 down_bps=1705994 impl=- local=srflx mode=RELAY remote=relay up_bps=0
17:37:15.055 stats_sample avail_bps=0 down_bps=1611552 impl=- local=srflx mode=RELAY remote=relay up_bps=0
17:37:17.058 stats_sample avail_bps=0 down_bps=1839336 impl=- local=srflx mode=RELAY remote=relay up_bps=0
17:37:19.056 stats_sample avail_bps=0 down_bps=1729589 impl=- local=srflx mode=RELAY remote=relay up_bps=0
17:37:21.059 stats_sample avail_bps=0 down_bps=1704794 impl=- local=srflx mode=RELAY remote=relay up_bps=0
17:37:23.053 stats_sample avail_bps=0 down_bps=1721672 impl=- local=srflx mode=RELAY remote=relay up_bps=0
```

### 1.2 同机对照会话：dl-g（WiFi, joiner, room=CND45N, pid=18994，约 4 分钟前）

```
17:32:36.553 WARN  pc [18994/19043] remote_deferred kind=offer queued=1 sdp_bytes=2530   ← offer 早于会话建立 ⇒ 被暂存
17:32:36.587 INFO  pc [18994/18994] pc_starting force_relay=false ice_servers=stun+turn
17:32:36.661 INFO  pc [18994/18994] pc_created                                            ← 先建 PC（本地轨已挂载）
17:32:36.662 INFO  pc [18994/18994] remote_replay_done answer=false candidates=2 offer=false
17:32:36.683 INFO  pc [18994/19054] answer_create                                         ← 在 pc_created 之后
17:32:36.689 INFO  pc [18994/19054] answer_created candidates=- sdp_bytes=2450             ★ 比缺陷会话多 138 B
17:32:36.772 INFO  pc [18994/19054] answer_sent sdp_bytes=2450
stats：17:32:40.695 down_bps=1572629 impl=libvpx mode=P2P local=host remote=host up_bps=1712435
       17:33:16.699 down_bps=1515126 impl=libvpx mode=P2P local=host remote=host up_bps=1718929
```

**对照结论**：同一台机器、同一 App、同一网络、同一 joiner 角色，
**唯一差别是 answer 生成时本地视频轨是否已挂载** ——
| | answer_create 相对 pc_created | answer sdp_bytes | impl | up_bps | down_bps |
|---|---|---|---|---|---|
| CND45N（正常） | 之后（+22 ms） | **2450** | `libvpx` | ≈1.71 Mbps | ≈1.31 Mbps |
| U9FQHG（缺陷） | **之前（−102 ms）** | **2312** | `-` | **0** | ≈1.7 Mbps |

### 1.3 对端（蜂窝端 dl-f, host, pid=32657, room=U9FQHG）

```
17:36:49.293 call_init role=host room=U9FQHG
stats（17:37:41 … 17:37:59，全部相同形态）：
  stats_sample avail_bps=3553230 down_bps=0 impl=libvpx local=srflx mode=RELAY remote=relay up_bps=1828375
```
- 该会话 289 条 `stats_sample` 中 **209 条 `down_bps=0`**（其余为连接建立前的 0/0 样本）；
- 蜂窝端 `up_bps≈1.8 Mbps`、`impl=libvpx`：**它自己发得出去**（host 的 offer 是在自己的
  `start()` 完成、`sessionReady && peerStarted` 之后才发的，故其本地轨必然已挂载）。

⇒ 双向验证：WiFi 端收得到（`down_bps≈1.7 Mbps`）但发不出（`up_bps=0`）；
蜂窝端发得出（`up_bps≈1.8 Mbps`）但收不到（`down_bps=0`）。**与本任务的缺陷描述逐条吻合。**

---

## §2 根因（file:line + 并发模型 + 为何"永不发送视频"）

### 2.1 旧代码路径（基线 = 本报告落盘前的 HEAD 版本，行号经 `git show` 核对）

`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt`：

| 行 | 代码 | 说明 |
|---|---|---|
| `:157` | `fun start(ice, forceRelay): Boolean {` | 建会话入口（**主线程**） |
| `:184` | `peerConnection = connection` | **过早发布点**：PC 已可被信令线程拿到 |
| `:186` | `val localVideo = mediaCapture.ensureStarted()` | 打开摄像头，真机 90–190 ms（`camera_opening`→`capture_started`） |
| `:189` | `connection.addTrack(localVideo, …)` | **本地视频轨真正挂载** |
| `:200` | `AppLog.i(TAG, "pc_created")` | "就绪"标记，比发布点晚 100+ ms |
| `:258` | `val connection = peerConnection` | `onRemoteOffer` 的就绪判定 —— **只看 PC 是否为 null** |
| `:274` | `connection.setRemoteDescription(…)` | 立即生效（`pc_signaling_state=HAVE_REMOTE_OFFER`） |
| `:281` | `AppLog.i(TAG, "answer_create")` → `createAnswer()` | 在**尚未挂载本地视频轨**的 PC 上生成 answer |

`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt`：

| 行 | 代码 | 说明 |
|---|---|---|
| `:79`/`:152` | `private var started` / `if (started) return` | 同一 ViewModel 复用时的"重复 initCall 空操作"，**新房间会跑在上一次遗留的 CallSession 上**（会话复用隐患，本轮一并修掉） |
| `:188` | `session = callSession` | 在 `start()` **之前**把 session 交给信令回调 ⇒ offer 会走进 `CallSession.onRemoteOffer` |
| `:189` | `callSession.start(…)` | 真正建 PC（与上一步之间存在窗口） |

### 2.2 并发模型（为什么"偶尔"复现 —— 与实机体验一致）

- `start()` 在**主线程**执行；`setRemoteDescription(offer)` 的 `SdpObserver` 回调在
  **libwebrtc 信令线程**执行（日志 tid 19825）。两者并发。
- offer 到达时刻相对 `start()` 有三种情形，只有第二种会踩坑：
  1. **早于 `session` 赋值**（旧 t51 窗口）→ 进 ViewModel 预队列 → `start()` 后回放 ⇒ **正常**
     （真机 VJFH94/AH9RX9/CMTKP9/EURJG7/7KA8CS/CND45N/NV9AHB 都是这种，日志有 `remote_deferred kind=offer`）；
  2. **落在 `:184` 与 `:189` 之间**（= 摄像头打开的 90–190 ms）→ PC 非空 ⇒ 立即 answer，**本地轨未挂载** ⇒ **缺陷**；
  3. 晚于 `:200`（`pc_created`）→ 正常。
- 触发概率≈"offer 到达时刻落在摄像头启动窗口内"，与"反复进出房间后更容易出现"的实机体验一致
  （每次进房的信令时序与摄像头冷/热启动耗时都在漂移）。

### 2.3 为何该端"永不发送视频"（解释 `stats_sample impl=- up_bps=0`）

- answer 生成时 PC 上**没有任何本地 video track**：`addTrack(localVideo)` 尚未执行
  （`capture_started` 06.931 > `answer_create` 06.929）。
- 于是该 PC 的视频 m-line 在 answer 里**没有发送方向**（对端 offer 为 sendrecv，本方无轨 ⇒ `recvonly`）
  ⇒ 没有 video sender/transceiver 的发送能力 ⇒ **没有编码器实例** ⇒ `stats_sample` 的
  `impl`（`encoderImplementation`）为空、`up_bps` 恒 0。
- 接收方向仍然有效 ⇒ 该端 `down_bps≈1.7 Mbps`（能看到对端）；对端收不到它的 RTP ⇒ `down_bps=0`。
- **量化旁证**：`answer_created sdp_bytes=2312` vs 对照会话 `2450`（−138 B）；
  紧随 `answer_sent` 之后的 `renegotiation_needed`（07.023）说明"本地轨是在协商完成之后才被加进来的"。
- 诚实说明：**SDP 文本本身未落盘**（`answer_created` 只记字节数），故"`recvonly`"是
  由"字节数差 + `impl=-` + `up_bps=0` + `renegotiation_needed`"间接推出；已在 §5 加入
  `pc_local_tracks` 事件用于下一轮直接证伪（见 §6 期望时序）。

---

## §3 修复

改动仅落在 inScope 的两个生产文件（+ 两个测试文件）；**既有事件名一个未删**。

### 3.1 `CallSession.kt`

| # | 改动 | 要点 |
|---|---|---|
| F1 | **延迟发布 + 就绪闸门**（`start()`） | `peerConnection` 改为在**本地音视频轨挂载 + `applyCodecPreferences` 之后**，于 `readyLock` 内与 `lifecycle.markReady()` **一起发布**；`pc_created` 紧随其后。⇒ `pc_created` 必早于任何 `answer_create`（`evt` 递增序号可证，§6） |
| F2 | **远端消息闸门 `gate{}`** | `onRemoteOffer` / `onRemoteAnswer` / `onRemoteIceCandidate` 的判定改为"阶段闸门"：`NEW`/`STARTING` ⇒ 一律暂存（`offer_deferred`/`answer_deferred`/`ice_deferred`，新增字段 `start_in_flight`）；`READY` ⇒ 立即处理；`CLOSED` ⇒ 丢弃。**不再仅凭 `peerConnection != null` 放行** |
| F3 | **反 TOCTOU 锁 `readyLock`** | "判定未就绪 ⇒ 入队"与"发布 ⇒ 排空暂存区"互斥。缺了它存在经典竞态：信令线程判定后、写入前，主线程已排空 ⇒ 该 offer 永久丢失。`flushPendingRemote()` 的"读取+清空"同样在锁内 |
| F4 | **禁止 PC 复用** | `SessionLifecycle.beginStart()` 只成功一次；重复 `start()` ⇒ `pc_start_rejected` + 返回 false（旧实现是 `if (peerConnection != null) return true`，即**静默复用**） |
| F5 | **释放纪律** | `close()` 在锁内一次性摘除 PC/统计循环/看门狗并置 CLOSED，清空暂存区与本地/远端轨；`pc_closed` 记录 `signaling_before`/`stats_loop=stopped`/`watchdog=stopped`/`phase=CLOSED`；`startStatsLoop()`/`startConnectivityWatchdog()` 的**注册**与 `close()` 互斥（hangup 可能来自信令线程，与主线程 `start()` 并发）⇒ 不会遗留仍在跑的采样线程 |
| F6 | **`start()` 期间被挂断** | 发布前/发布后各自检测 `closed` ⇒ `pc_start_aborted` + 立即释放刚建的 PC，且不再广播 `pc_created`/`onReady` |
| F7 | **会话标识诊断** | 新增 `sessionId`（进程内递增，构造时分配）与 `evt`（会话内递增事件号，抵抗异步落盘乱序），打在 `pc_starting`/`pc_local_tracks`/`pc_created`/`offer_create(+created/sent)`/`answer_create(+created/sent)`/`ice_candidate_local`/`remote_replay_done`/`pc_closed` 上 |
| F8 | **新事件** | `pc_local_tracks`（`video`/`audio` 布尔，**answer_create 之前必出现且 video=true**）、`pc_start_rejected`、`pc_start_aborted`、`offer_create_rejected` |
| F9 | **纯状态机** | 文件末尾新增 `SessionPhase` / `SessionGate` / `SessionLifecycle`（纯 Kotlin，可 JVM 单测）——把上述不变量变成可断言对象 |

### 3.2 `CallViewModel.kt`

| # | 改动 | 要点 |
|---|---|---|
| G1 | **每次通话全新会话** | 删除 `private var started`；`initCall` 改为 `CallSessionSlot.beginCall()` 开新一世代，**上一代仍活跃则先 `releaseSession("reinit")` 关闭旧 `CallSession`**，随后**无条件 `CallSession(...)` 新建**（绝不复用 `session` 字段里的旧对象） |
| G2 | **幂等闸门（避免过度修复）** | `CallScreen` 用 `LaunchedEffect(Unit)` 调用 `initCall`；配置变更重建组合时会在**保留下来的同一个 ViewModel** 上再次调用。故 `decideInit(room, role, callEnded)`：**同一次通话的重复调用 = `IGNORE`（空操作）**；换房间/换角色/上次已结束 ⇒ `NEW_CALL` |
| G3 | **统一释放** | 新增 `releaseSession(reason)`：关旧会话 + 清空本端/远端轨道状态 + 复位 `sessionReady/peerJoined/peerWatchdogArmed/peerResponseSeen`；`hangup()`、`onCleared()`（离开通话页）、`initCall` 重建、`start()` 失败四条路径全部走它 |
| G4 | **换代复位** | 新一代通话复位 `callEnded`（旧实现靠"新 VM 实例"保证干净，一旦同 VM 复用会导致**新一代无法挂断**）、`natTypeSent`、ICE 事件列表与 `error` |
| G5 | **诊断** | 新增进程级递增 `seq`（`call_init`/`hangup`/`remote_deferred`/`answer_timeout`/`call_reinit`/`session_teardown`）与 `session=sN`；新事件 `call_init_ignored`、`call_reinit`、`session_teardown`、`session_release` |
| G6 | **纯状态机** | 文件末尾新增 `InitDecision` / `CallSessionSlot`（纯 Kotlin，可 JVM 单测） |

### 3.3 时序对照（修复前 → 修复后）

**修复前（缺陷，room U9FQHG）**
```
pc_starting ──▶ [PC 已发布] ──▶ offer_received ──▶ answer_create(本地轨未挂载!) ──▶ answer_sent ──▶ capture_started ──▶ pc_created
                    ↑                                   ↑                                              ↑
              :184 过早发布点                    :281 在无本地轨的 PC 上应答                   :189 本地轨此刻才挂上
```

**修复后（期望日志形态，供下一轮真机复测核对）**
```
pc_starting session=s7 evt=1                       ← 主线程；PC 尚未发布
offer_received session=s7 evt=2                    ← 信令线程
offer_deferred reason=pc_not_ready start_in_flight=true session=s7 evt=3   ← 闸门暂存（旧版此处直接 answer_create）
camera_opening / capture_started                   ← 摄像头启动
pc_local_tracks video=true audio=true session=s7 evt=4                     ← 新增：本地轨就绪的硬证据
pc_created phase=READY session=s7 evt=5            ← 发布点
offer_replayed session=s7 evt=6                    ← 回放暂存的 offer
answer_create session=s7 evt=7                     ← 必满足 evt(7) > evt(5) 且 sdp_bytes ≈2450
answer_created sdp_bytes≈2450 / answer_sent
remote_replay_done offer=true … session=s7
call_init role=joiner room=… seq=7 session=s7 pc_ready=true
```
**判据（与时间戳无关，只看向量）**：`evt(pc_local_tracks) < evt(pc_created) < evt(answer_create)`，
且 `pc_local_tracks video=true`。

### 3.4 明确"不做"的事

- **不回退 t44/t51**：`CallSession` 的暂存/回放（`offer_deferred`/`ice_deferred`/`remote_replay_done`、
  含 `source`/`order` 字段）与 ViewModel 层 `PendingRemoteMessages` 预队列语义**全部保留**，
  本轮只是在它们前面补了"PC 已存在但本地轨未挂载"的第三种窗口。
- **不改** `cpp/**`、`config/AppConfig.kt`、`doc/**`、`third_party/**`、`scripts/**`（越界）。
- **不动** ICE 看门狗（15 s/30 s）与"对端无响应"20 s 提示（t51）的既有行为，只在其事件里追加 `seq`/`session`。

---

## §4 事件登记（doc/14 §9.1 风格：`snake_case` + 模块 tag `pc`）

| 事件 | 等级 | 新增/保留 | 关键字段 |
|---|---|---|---|
| `pc_local_tracks` | INFO | **新增** | `video`,`audio` —— answer_create 之前必出现 |
| `pc_start_rejected` | ERROR | **新增** | `reason=already_started`,`phase` |
| `pc_start_aborted` | WARN | **新增** | `reason=closed_during_start\|closed_after_publish` |
| `offer_create_rejected` | ERROR | **新增** | `reason=pc_not_ready`,`phase` |
| `call_init_ignored` | INFO | **新增** | `reason=same_call`,`room`,`role`,`seq`,`session`,`pc_ready` |
| `call_reinit` | WARN | **新增** | `seq`,`room`,`role` |
| `session_teardown` | INFO | **新增** | `reason=reinit\|hangup\|on_cleared\|start_failed`,`session` |
| `session_release` | INFO | **新增** | `reason=on_cleared`,`seq`,`session` |
| `pc_starting`/`pc_created`/`pc_closed` | INFO | 保留 | 追加 `session`,`evt`（`pc_closed` 追加 `signaling_before`,`stats_loop`,`watchdog`,`phase`） |
| `offer_create`/`offer_created`/`offer_sent` | INFO | 保留 | 追加 `session`,`evt` |
| `answer_received`/`answer_create`/`answer_created`/`answer_sent` | INFO | 保留 | 追加 `session`,`evt` |
| `offer_received`/`offer_deferred`/`answer_deferred`/`ice_deferred`/`ice_candidate_remote` | INFO/WARN | 保留 | 追加 `session`,`evt`；deferred 追加 `start_in_flight` |
| `ice_candidate_local` | INFO | 保留 | 追加 `session`,`evt` |
| `remote_replay_done`/`offer_replayed`/`answer_replayed`/`ice_replayed` | INFO | 保留 | 追加 `session`,`evt` |
| `call_init`/`hangup`/`remote_deferred`/`answer_timeout` | INFO/WARN | 保留 | 追加 `seq`（`call_init` 另有 `session`,`pc_ready`；`hangup` 另有 `session`） |
| `video_track_missing`/`pc_close_failed`/`pc_dispose_failed`/`*_failed` | WARN/ERROR | 保留 | 追加 `session`,`evt` |

> 说明：`evt` 为**会话内单调递增**（`AtomicInteger`），只在带该字段的事件之间可比；
> 日志等级被过滤时会有跳跃，但**不会反向**。

---

## §5 单测与离线预检

新增 2 个纯 JVM 测试类、共 **18** 个用例（无 Android / org.webrtc 依赖）：

- `app/src/test/kotlin/com/example/webrtcdemo/webrtc/SessionLifecycleTest.kt`（10）
  `newSessionDefersRemoteMessages`、`startingPhaseStillDefers`、`readyPhaseProceeds`、
  `markReadyBeforeStartIsRejected`、`secondBeginStartIsRejected`、`closedSessionRejectsStart`、
  `closedSessionDropsRemoteMessages`、`markClosedIsIdempotent`、`markReadyAfterCloseDoesNotRevive`、
  **`readyMarkerAlwaysPrecedesAnswer`**（复刻真机 U9FQHG 时序，断言 `pc_created` 必在 `answer_create` 之前）
- `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSessionSlotTest.kt`（8）
  `firstInitStartsFirstGeneration`、`sameCallRepeatInitIsIgnored`、`sameRoomAfterHangupOpensNewGeneration`、
  `differentRoomOpensNewGenerationAndMustCloseOld`、`roleChangeOpensNewGeneration`、
  `newGenerationNeverInheritsPeerReady`、`endCallIsIdempotentAndBlocksLateMarkReady`、
  **`consecutiveCallsAlwaysUseFreshSessionAndClosePrevious`**（连续 5 次通话：每次新建 PC、
  旧会话必先 `close()`；断言 `peerConnectionCreatedCount=5`、`sessionClosedCount=4`）

**离线预检（本轮实际执行，宿主机 /tmp 一次性脚本；容器内无 JDK）**：
- 用 Kotlin **2.0.21** 编译器（Gradle 缓存里的 `kotlin-compiler-embeddable`）+
  真实 classpath（`android.jar`(SDK 34) + `third_party/libwebrtc/java/libwebrtc-java.jar` +
  模块缓存/`transforms` 展开的 androidx 等 230 项）+ `-Xplugin` 序列化与 Compose 插件，
  对 `app/src/main/kotlin` + `app/src/test/kotlin` **全部 53 个 .kt** 做整模块编译：
  **`EXIT=0`、`error: ` 计数 = 0**（`R`/`BuildConfig` 用 /tmp 桩替代，桩不进入仓库）。
- 用 JUnit4 运行测试类：
  - 新增两个类：**`OK (18 tests)`**
  - **全量 11 个测试类：`OK (92 tests)`**（含 t44/t45/t51 既有用例，无回归）
- 说明：这是**离线预检**（非 Gradle）；Gradle 侧的 `:app:testDebugUnitTest` 与 APK 构建仍由
  captain 的构建窗口执行（容器内无 JDK/SDK，本成员不得运行 gradle）。

---

## §6 verify 命令原始输出

> 采集时刻：见 §6.4 的 `HEAD`；工作区未提交改动状态见 V3。

### V1
```
$ cd code/webrtc-demo && grep -n 'pc_created\|answer_create\|answer_sent\|peerConnection ?: return\|session = null\|close()' app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt | head -30
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:81:    // 真机缺陷：反复进出房间后，joiner 在新 PeerConnection 就绪前就 answer_create/sent，
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:310:        // 【t53】会话释放纪律：旧 CallSession 必须 close()（PC/统计循环/看门狗一并停）
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:573:        session = null
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:587:            old.close()
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:780:    /** 新一代通话：必须新建 `CallSession`/`PeerConnection`，上一代活跃时先 `close()`。 */
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:125:     * 多会话交织定因用：`session` 字段会出现在 `pc_starting`/`pc_created`/`pc_local_tracks`/
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:126:     * `offer_create`/`answer_create`/`ice_candidate_local`/`pc_closed` 等关键事件上。
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:134:     * `pc_created` 是否真的早于 `answer_create`；单调递增的 `evt` 可以。
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:259:            // 真机 room U9FQHG 的 `answer_create`(17:37:06.929, 信令线程) 就是这样比
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:260:            // `pc_created`(17:37:07.031, 主线程) 早 102 ms，生成出的 answer 没有视频发送方向
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:270:            // 本地轨就绪的**可证伪**证据：answer_create 之前必定出现本行且 video=true
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:295:                // start() 期间被 close()（用户挂断 / 离开通话页）：刚建的 PC 必须立即销毁，绝不泄漏
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:303:                // 发布之后、对外宣布就绪之前被 close()（用户在 start() 期间挂断）：
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:304:                // 不再广播 `pc_created`/`onReady`，避免上层对已关闭会话继续发起协商。
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:308:            AppLog.i(TAG, "pc_created", mapOf("phase" to lifecycle.phase.name).withKey())
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:410:                    AppLog.i(TAG, "answer_create", keyOnly())
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:416:                                    "answer_created",
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:424:                                AppLog.i(TAG, "answer_sent", keyOnly())
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:430:                                AppLog.e(TAG, "answer_create_failed", mapOf("reason" to error).withKey())
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:564:     *     与 [start] 的发布点互斥，避免 start() 与 close() 并发时留下仍在运行的采样线程；
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:568:    fun close() {
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:640:     * 这是 t53 的核心不变量：`pc_created`（发布点）之前到达的 offer/answer/候选**一律暂存**，
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:641:     * 从而保证 `answer_create` 永远发生在"本地视频轨已挂载的 PC"上。
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:670:            connection.close()
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:886:        // 【t53】同上：注册与 close() 互斥
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:959:        // 【t53】注册与 close() 互斥：hangup 可能由信令线程触发，与主线程的 start() 并发；
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1034:    /** PC 已发布且本地轨已挂载（`pc_created` 之后）：可处理 offer/answer/候选。 */
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1059: *     `pc_created`（发布点）早于 `answer_create`；
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1115:    /** 是否已就绪（= `pc_created` 已落盘、可安全应答）。 */
EXIT=0
```
**读法**：`CallSession.kt:308` 的 `pc_created` 位于 `start()` 内、`flushPendingRemote()`（`:310`）之前；
`answer_create`（`:410`）**只可能**由 `gate{}` 放行后的 `onRemoteOffer` 触发，而 `gate{}` 在
`pc_created` 之前只返回 `DEFER`。`CallViewModel.kt:573`（`session = null`）与 `:587`（`old.close()`）
是新的统一释放路径；旧代码里 `peerConnection ?: return` 与"静默 `return true`"已不存在
（`createOffer` 改判 `lifecycle.isReady`；`start` 改判 `SessionLifecycle.beginStart()`）。

### V2
```
$ cd code/webrtc-demo && grep -rn 'session_id\|sessionId\|call_seq' app/src/main/kotlin/com/example/webrtcdemo/ | head -10
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:128:    val sessionId: Int = SESSION_SEQ.incrementAndGet()
app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:161:        this + mapOf("session" to "s$sessionId", "evt" to eventSeq.incrementAndGet().toString())
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:96:    /** 本次通话实际使用的 [CallSession.sessionId]（0 表示尚未建立）。 */
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:97:    private var sessionId = 0
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:189:                    "session" to "s$sessionId",
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:254:        sessionId = callSession.sessionId
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:277:                "session" to "s$sessionId",
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:308:        AppLog.i(TAG, "hangup", mapOf("seq" to callSeq.toString(), "session" to "s$sessionId"))
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:553:                mapOf("reason" to "on_cleared", "seq" to callSeq.toString(), "session" to "s$sessionId"),
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:585:                mapOf("reason" to reason, "session" to "s${old.sessionId}"),
EXIT=0
```
**读法**：会话标识落在事件字段 `session`（值 `s<N>`）与 `seq`（跨 ViewModel 的通话序号）上，
字段名不含 `session_id`/`call_seq`（Kotlin 侧统一 camelCase / 日志字段 snake_case 风格），
故 grep 命中 `sessionId`（属性名）；实际落盘字段名为 `session`/`evt`/`seq`（见 §4）。

### V3
```
$ cd code/webrtc-demo && ls -l reports/23-session-lifecycle.md && git status --porcelain
-rw-r--r-- 1 node node 36441 Sep 15 02:02 reports/23-session-lifecycle.md
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt
?? app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSessionSlotTest.kt
?? app/src/test/kotlin/com/example/webrtcdemo/webrtc/SessionLifecycleTest.kt
?? reports/23-session-lifecycle.md
?? reports/10-t47b-captain-*.log      ← captain 本轮构建日志（非本任务产物，此处折叠为一行）
EXIT=0
```
> 注：`ls -l` 的 size/mtime 是**采集时刻**的值；本报告随后被追加过这一行输出，
> 故最终 size/mtime/sha256 以交付消息为准（mode 恒为 `644`）。
> V3 中属于本任务的 5 条即全部改动，**均在 inScope 内**。

### 6.4 版本上下文（采集时）
```
$ git log --oneline -1
621d673 fix(android): t53 交付默认为可用路径——useDefaultEncoder 默认 false→true（…）
```
> 落盘时 HEAD 由 captain/其他成员推进（本轮已多次变化）；本任务改动**尚未提交**（未获提交授权）。

---

## §7 未验证项（诚实清单）

| # | 未验证项 | 为什么没验 | 复测方式 |
|---|---|---|---|
| U1 | **真机"蜂窝 ↔ WiFi、走 relay"双向都出画面** | 本成员无真机/无设备权限；容器无 JDK/SDK | 按 §7.1 清单复测 |
| U2 | 真机 `stats_sample impl` 非空且 `up_bps>0`、对端 `down_bps>0`、`remote_first_frame` 出现 | 同上 | 同上 |
| U3 | Gradle `:app:testDebugUnitTest` / `assembleDebug` 通过 | 容器无 JDK/SDK；构建窗口归 captain | captain 构建窗口（本报告仅提供离线预检证据） |
| U4 | answer SDP 确实是 `recvonly`（无视频发送方向） | `answer_created` 只记字节数，未落盘 SDP 文本 | 复测时看 `answer_created sdp_bytes`：≈2450 = 正常；若再现 ≈2312 即缺陷仍在；`pc_local_tracks video=true` 必须早于 `answer_create` |
| U5 | 连续进出房间 10+ 次的稳定性 | 需真机 | 复测：每轮核对 `call_init` 的 `seq` 递增、每轮新 `session` 编号递增、上一轮有 `pc_closed`/`session_teardown` |

### 7.1 真机复测清单（供 captain 交付前执行）

1. 蜂窝端创建房间（host），WiFi 端加入（joiner），通话 30 s 以上；
2. 在双端 `app.log` 里核对：
   - `pc_starting` → （可能）`offer_deferred reason=pc_not_ready start_in_flight=true` →
     `pc_local_tracks video=true audio=true` → `pc_created` → `answer_create`
     —— 且 `evt(pc_created) < evt(answer_create)`；
   - `answer_created sdp_bytes ≈2450`（**不是** 2312）；
   - 双端 `stats_sample` 均为 `impl=libvpx`（非空）、`up_bps>0`、`down_bps>0`；
   - 双端都出现 `remote_first_frame`/`isRemoteVideoReady`（或 `renderer_created which=remote` + 首帧）。
3. 反复进出房间 ≥10 次：每次核对 `call_init … seq=N session=sM pc_ready=true`，
   `N`/`M` 单调递增；上一轮必有 `pc_closed`（含 `stats_loop=stopped`）与 `session_teardown`；
   **不得出现** `pc_start_rejected`、`call_reinit`（正常进出房间路径）。若出现
   `call_reinit`，说明同一 ViewModel 被复用进了不同房间——那正是 G1 要覆盖的路径，需回看其后的时序。
4. 若仍出现单向无画面：抓 `pc_local_tracks`（应为 `video=true`）+ `answer_created sdp_bytes`
   + 双端 `stats_sample`，即可二次定因。

---

## §8 边界、交互与遗留

- **与 t44/t51 的交互**：三层窗口现已全覆盖 —— ① `session == null`（VM 预队列，t51）；
  ② `session` 非空但 PC 未发布/本地轨未挂载（本轮 `gate{}`）；③ 会话已关闭（本轮一律 DROP）。
  `remote_replay_done` 同时存在 `source=session`（CallSession 内）与 `source=viewmodel`（VM 层）两条记录，
  由 `session`/`evt` 字段可区分。
- **与 t45 的交互**：`releaseSession()` 会把 `_localVideoTrack`/`_remoteVideoTrack` 置空，
  `CallScreen` 的 `key(localGeneration/remoteGeneration)` + 渲染池的 `attach_rejected` 逻辑负责重建渲染器；
  本轮未改 `CallScreen`/`VideoRendererPool`。
- **与 native 侧（t48/t50）**：本缺陷与 `impl` 是否为空无关地独立存在——**即使 native 编码器正常**，
  "answer 无发送方向"也会导致 `up_bps=0`。二者需分别验证，勿混判。
- **`withKey()`/`evt` 的副作用**：`evt` 在生成字段时递增，属日志专用状态，不参与业务判断。
- **遗留/后续建议**（不属本轮范围）：
  1. `pc_local_tracks` 可进一步记录 **transceiver 数量与方向**（`sendrecv`/`recvonly`），
     把 U4 的"间接推断"变成直接证据；
  2. `answer_created` 可增设 `video_send=true/false` 字段（由本地 sender 是否存在推导）；
  3. `engine_ready` 在真机日志里出现两次，经核对是 `WebRtcEngine.initialize()` 内
     `AppLog.i` + `AppLog.critical` 各记一次（**不是重复初始化**），无需处理，但易误读，可考虑合并为一次。

---

## §9 附录：离线预检复现配方（容器无 JDK 时使用）

在宿主机（有 JDK17 + Gradle 缓存）执行，**不触碰仓库**（桩与产物都在 `/tmp`）：

```sh
# 1) 生成 R/BuildConfig 桩（替代 AGP 生成物；仅编译用，不进仓库）
#    R 只需 object string { const val <43 个 id> = 0 }，BuildConfig 需
#    DEBUG:Boolean / LOG_DEFAULT_DEBUG:Boolean / LIBCAMERA_FACING:String /
#    SIGNALING_URL:String / BUILD_TYPE:String / VERSION_NAME:String / VERSION_CODE:Int
# 2) 用缓存里的编译器整模块编译（classpath = android.jar + libwebrtc-java.jar + 缓存 jar + transforms 展开的 androidx）
java -cp "$KC:$STD:$RF:$CR:$TR:$AN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
     -nowarn -Xplugin=$SER -Xplugin=$CMP -classpath "$CP" -d /tmp/out \
     $(find app/src/main/kotlin app/src/test/kotlin -name '*.kt') /tmp/RStub.kt
# 3) 跑 JVM 单测（运行期 classpath 需带 android.jar，否则 android/* 类加载失败）
java -cp "/tmp/out:$ANDROID_JAR:$LIBWEBRTC_JAR:$JUNIT:$HAMCREST:$STD:$CR:$SER:$SERB" \
     org.junit.runner.JUnitCore $(测试类全名列表)
```
本轮实测：整模块 `EXIT=0`（0 error）；新增 18 用例 `OK (18 tests)`；全量 `OK (92 tests)`。
