# t63 报告：远端帧存活误报 —— 「画面在更新却提示等待对方画面」+ 状态在 connected/connecting 反复跳

- 任务：t63「修复远端帧存活误报：画面在更新却提示『等待对方画面』+ 状态在 connected/connecting 间反复跳」
- 归属：android-dev（attempt 2）
- 范围：`ui/call/**`（`ConnectionStatus.kt`、`CallViewModel.kt`）、`webrtc/VideoRendererPool.kt`（仅新增帧计数/时间戳，
  未改渲染生命周期）、`app/src/test/**`、本报告
- 依据：captain 取证（宿主 `/opt/dsh-workspaces/tmp/dk-a/x`（平板）、`dk-b/x`（Mi 10 Pro））+ 本轮自行核对的逐行时序

---

## §0 摘要（结论先行）

| 项 | 结论 |
|---|---|
| **根因** | 「远端帧存活」此前**两处都不成立**：① 唯一能置 `remoteFrameReady` 的**每帧类**通道（`onStats` 的 `LivenessEvidence.DOWNLINK` 分支）在健康时**不可达** —— `mode=RELAY` 非空必然先命中 `SELECTED_PAIR` 分支，只调 `onSelectedPair`（`viaFrame=false`，**不刷帧新鲜度**）；② 于是只剩 renderer 的**一次性** `onFirstFrameRendered` 能置真 ⇒ `lastFreshMediaMs` 之后**永不更新** ⇒ 帧龄阈值（4 s）一到就判「画面停滞」。真机 dk-a 精确吻合：首帧 `13:36:06.692` → 判停滞 `13:36:11.259`（**4.57 s** ≈ `DEFAULT_FRAME_STALL_MS=4000` + tick 粒度），而同刻 `down_bps` 稳定 645k–680k（画面确实在动） |
| **修复 1（每帧信号）** | `VideoRendererPool.attachRemote()` 把远端 sink 包一层 `VideoSink { frame -> 计数 + 墙上时刻 + 转发 renderer.onFrame }` ⇒ **每帧**刷新；新增 `remoteLastFrameAtMs()/remoteFrameCount()/remoteFrameLiveness(nowMs)` |
| **修复 2（信号解耦）** | `onStats` 里「媒体存活」改为**独立通道**：`down_bps > 0` 一律 `onMediaFrame(..., MEDIA_SOURCE_DOWN_BPS)`，不再被 `SELECTED_PAIR` 分支吃掉；ViewModel 每秒 tick 用**池内每帧时间戳**（优先）或 `down_bps`（次选）驱动 |
| **修复 3（去抖）** | 停滞需**连续 2 个 tick** 越阈（`DEFAULT_STALL_DEBOUNCE_TICKS=2`）；`connection_lost` 仍**只**由 ICE 事实产生，且**媒体仍在流动时不跳变界面**（只记 `ice_flap_suppressed`） |
| **诊断** | 新增 `remote_frame_liveness source=sink\|down_bps\|none age_ms=N frames=K down_bps=D`（来源变化或每 15 s 一条）；`ui_conn_state` 追加 `media_source`/`media_age_ms`/`ice_down` |
| **验证** | 纯 JVM 单测 **120 tests OK**（t62 基线 115 → **+5**，其中 2 条为验收点名单测）；离线整模块编译 **EXIT=0 / 0 error**；契约 3 条 verify 全 EXIT=0 |

---

## §1 根因（代码级，含 file:line）

### 1.1 真机证据（dk-a，pid 18696，session=s6）

```
13:35:51.225 ui_conn_state elapsed_ms=2170  frame=false pair=true phase=connected reason=none   stalled=false  ← 靠 mode=RELAY 判连上（viaFrame=false ⇒ frame=false）
13:36:05.600 pc_connection_state dtls=true state=CONNECTED
13:36:06.692 stats remote_first_frame surface=true which=remote                                  ← 首帧（全程唯一一次）
13:36:11.259 ui_conn_state elapsed_ms=22204 frame=false pair=true phase=connecting reason=remote_frame_stalled stalled=true   ★ 首帧后 4.57 s 就判"画面停滞"
13:36:13.233 ui_conn_state elapsed_ms=24178 frame=false pair=true phase=connected  reason=none   stalled=false                ← 又被复位
…
13:37:17.303 stats_sample avail_bps=314716 down_bps=665449 impl=SelfVp9Libvpx local=relay mode=RELAY remote=srflx up_bps=249496
13:37:27.288 stats_sample avail_bps=405182 down_bps=660710 impl=SelfVp9Libvpx local=relay mode=RELAY remote=srflx up_bps=339957
```
- **`frame=false` 全程成立**（两端都是），而同刻 `down_bps` 稳定 325k–680k ⇒ 画面在更新；
- `remote_first_frame` 全程**仅 1 次**；`remote_frame_stalled` 各 1 次 —— 与"一次性首帧 + 阈值判停"完全一致。

### 1.2 两条信号链的代码级缺陷（基线 = `bac78a2`）

| # | file:line | 代码/事实 | 为何在正常流动时**长期为 false** |
|---|---|---|---|
| R1 | `ui/call/CallViewModel.kt` `onStats()`（`when (livenessEvidence(...))` 三分支） | `SELECTED_PAIR -> onSelectedPair(now)`；`DOWNLINK -> onRemoteFrame(now)` | `livenessEvidence` 的 `SELECTED_PAIR` **优先**返回；健康时 `mode=P2P\|RELAY` 必然非空 ⇒ **永远走第一分支**，`DOWNLINK`（唯一带 `viaFrame=true` 的 stats 通道）**不可达** ⇒ `down_bps>0` 完全没参与存活判定 |
| R2 | `ui/call/ConnectionStatus.kt` `markConnected(nowMs, viaFrame)` | `if (viaFrame) { hasFreshMedia = true; lastFreshMediaMs = nowMs }` | `onSelectedPair` 传 `viaFrame=false` ⇒ **不刷新**帧新鲜度；能刷新它的只剩：① 被 R1 抢占的 DOWNLINK；② renderer 的**一次性** `onFirstFrameRendered` |
| R3 | `webrtc/VideoRendererPool.kt` `markFrame()`（`:75`/`:508` 附近） | 仅在 `onFirstFrameRendered` / `onFrameResolutionChanged` 里更新 `lastRemoteFrameAtNs` | 二者都是**稀疏回调**：分辨率在正常通话中几乎不变 ⇒ 该时间戳长期不动（且 `lastFrameAtNs` 当时**根本没被 ViewModel 消费**） |
| R4 | `ui/call/CallViewModel.kt` `onRemoteFirstFrame()` | 以 `connStatus.value.phase == CONNECTED` 为闸门 | 首帧若早于"已连接"到达就**只落盘不上报**（真机 `remote_frame_ignored`）；即使通过也只发生**一次** |

**结论**：`remoteFrameReady` 的置真机会只有"首帧那一次"；`onTick` 的停滞判定用 `nowMs - lastFreshMediaMs > 4000`
⇒ 首帧后 4 s 立刻误报"画面停滞"（R5 见下），把健康通话打成 CONNECTING；下一次 stats（`mode` 非空）又把它拉回
connected ⇒ **在 connected/connecting 之间反复跳**，同时 `remoteDimmed = phase != CONNECTED || !remoteFrameReady`
让界面显示「等待对方画面」—— 与用户"实际画面又在更新"的描述完全一致。

| # | file:line | 内容 |
|---|---|---|
| R5 | `ui/call/ConnectionStatus.kt` `onTick` CONNECTED 分支（t60 版） | `if (status.remoteFrameReady && hasFreshMedia && nowMs - lastFreshMediaMs > frameStallMs) → CONNECTING(REMOTE_FRAME_STALLED)` —— 无去抖、且 `lastFreshMediaMs` 永不更新（R2） |
| R6 | `ui/call/ConnectionStatus.kt` `onConnectionLost`（t60 版） | ICE 一掉线就立刻把界面打成"连接中断"。真机 dk-a 的 ICE 在中继路径上**每 ~16 s** FAILED 一次（`13:35:09`/`13:35:29`/`13:35:47`），而同刻 `down_bps` 稳定 ⇒ 界面无谓跳变（**注**：这些 `connection_lost` 都确实来自 ICE 事件，不是帧信号推断，R6 只是"展示过早"） |

---

## §2 修复

### 2.1 `webrtc/VideoRendererPool.kt`（仅新增帧计数/时间戳，**未动渲染生命周期**）

| 改动 | 说明 |
|---|---|
| `attachRemote()` 用 `VideoSink` 包装层挂载 | `track.addSink(wrapper)`；`wrapper` 内 `remoteFrameCount++`、`lastRemoteFrameAtMs = System.currentTimeMillis()`，并**原样转发** `renderer.onFrame(frame)`（渲染顺序/内容不变；转发异常兜底 `renderer_on_frame_failed`） |
| `detachRemote()` 移除的改为包装层 | `track.removeSink(wrapper ?: renderer)` —— 生命周期（先 removeSink 再 release、`releasedRenderers` 拒绝重建）**完全未变** |
| 新增查询接口 | `remoteLastFrameAtMs()`、`remoteFrameCount()`、`remoteFrameLiveness(nowMs): FrameLiveness(frames,lastFrameAtMs,ageMs)`；`NO_FRAME_AGE_MS` 表示"从未收到帧" |
| 保留既有 | `lastFrameAtNs`/`frameSeenSince`/`surfaceAlive`/`markFrame` 全部保留（t45 恢复路径仍用），**未删除任何既有 API** |

### 2.2 `ui/call/ConnectionStatus.kt`

| 改动 | 说明 |
|---|---|
| 新增 `onMediaFrame(nowMs, source, ageMs)` | **媒体存活**独立入口：刷新 `lastFreshMediaMs/hasFreshMedia`、重置去抖计数、置 `remoteFrameReady=true` 并按需回到 CONNECTED；来源只能是 `sink`（每帧时间戳）或 `down_bps`（真的在收） |
| `ConnStatus` 新增诊断字段 | `mediaSource`（sink/down_bps/none）、`mediaAgeMs`（帧龄，-1=无）、`iceDown`（ICE 事实，仅诊断） |
| 停滞判定加**滞回去抖** | `overThreshold = hasFreshMedia && age > frameStallMs`；需 `staleTicks >= DEFAULT_STALL_DEBOUNCE_TICKS(2)` 才判停滞（吸收单次采样抖动） |
| 停滞/中断**原因归类** | `iceDown ? CONNECTION_LOST : REMOTE_FRAME_STALLED` —— `connection_lost` **只**由 ICE 事实产生，绝不由帧信号推断 |
| `onConnectionLost` 纪律 | ICE 掉线**先记 `iceDown`**；若媒体仍新鲜（帧龄 ≤ 阈值）⇒ **不跳变界面**（返回仍是 CONNECTED，供 VM 记 `ice_flap_suppressed`）；媒体也已停 ⇒ 按 `CONNECTION_LOST` 进入中断 |
| `onTransportConnected` | 清除 `iceDown` |

> 顺带修掉一个真实缺陷：t61 版 `onConnectionLost` 的抑制分支只 `return status.copy(...)` 而**未赋值回 `status`**，
> 导致 `iceDown` 等字段不生效（被本轮新增单测 `iceFlapWithLiveMediaKeepsUiConnected` 抓出并修复）。

### 2.3 `ui/call/CallViewModel.kt`

| 改动 | 说明 |
|---|---|
| 1 s 心跳改为"先喂存活、再 tick" | 每 tick 取 `WebRtcEngine.rendererPool()?.remoteFrameLiveness(now)`：`age ≤ FRAME_ALIVE_MS(1.5 s)` ⇒ `source=sink`；否则 `down_bps > 0` ⇒ `source=down_bps`；都不满足 ⇒ 不喂（让状态机按帧龄判定） |
| `onStats` 解耦 | `down_bps > 0` ⇒ **独立**调用 `onMediaFrame(now, MEDIA_SOURCE_DOWN_BPS)`（不再被 `SELECTED_PAIR` 分支吃掉）；连接判定仍走 `livenessEvidence(...)`（`up_bps` 依旧不参与） |
| `onRemoteFirstFrame` | 通过闸门后改调 `onMediaFrame(now, MEDIA_SOURCE_SINK)`（此后由池内每帧时间戳持续刷新，不再依赖一次性回调） |
| `onIceEvent` | `DISCONNECTED/FAILED` 分支：若抑制了跳变则落盘 `ice_flap_suppressed detail=… media_source=… age_ms=…` |
| `publishConnStatus` | `ui_conn_state` 追加 `media_source`/`media_age_ms`/`ice_down` |
| 新增 `logFrameLivenessIfNeeded` | 落盘 `remote_frame_liveness source=… age_ms=… frames=… down_bps=… phase=… view=…`（来源变化即记，否则每 15 s 一条） |
| 常量 | `FRAME_ALIVE_MS = 1_500`、`FRAME_LIVENESS_LOG_INTERVAL_MS = 15_000` |

### 2.4 修正前后对照

| 场景 | 修正前（`bac78a2`） | 修正后（t63） |
|---|---|---|
| 视频正常流动（`mode=RELAY` + `down_bps>0` + 每帧回调） | `down_bps` 通道被 `SELECTED_PAIR` 抢占 ⇒ `frame` 长期 false ⇒ 首帧后 4 s 判 `remote_frame_stalled`，connected/connecting 反复跳 | 每帧时间戳（`age≈33 ms`）+ `down_bps` 双通道持续刷新 ⇒ **稳定 connected、`frame=true`**；30 s 内 100% connected（单测钉死） |
| 单次采样抖动/短暂 0 下行 | 立即判停滞（阈值一到即翻） | 需**连续 2 个 tick** 越阈（去抖） |
| ICE 短暂 FAILED 而画面仍在动（4G 中继常态） | 立即显示「连接中断」（8 s 宽限后才 FAILED） | **界面不跳变**，只落 `ice_flap_suppressed`；媒体真的停了才按 `connection_lost` 进入中断 |
| 真实停流 | 判停滞（但无法区分"ICE 掉了"与"对端停发"） | 判停滞且原因归类准确：`iceDown ⇒ connection_lost`，否则 `remote_frame_stalled` |
| 诊断 | 只有 `frame=false` 这一个反直觉字段 | `remote_frame_liveness source=sink age_ms=33 frames=1234 …` 一行定因 |

---

## §3 单测（纯 JVM）

新增/更新 `app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt`：**23 用例**
（t62 基线该文件 18 → 本文件净增 5；全量 115 → **120**）：

| 用例 | 断言（对应验收） |
|---|---|
| **`perFrameLivenessKeepsConnectedFor30Seconds`**（点名①） | 30 个 1 s tick，每 tick 前都有每帧时间戳 + `down_bps>0`，且**全程不触发任何分辨率回调** ⇒ `connectedTicks == 30`（**100% connected**）、`reason` 从不出现 `connection_lost`/`remote_frame_stalled`、终态 `remoteFrameReady=true` |
| **`realStreamStopEntersStalled`**（点名②） | 帧回调停止 + `down_bps=0`（ViewModel 不再喂存活）⇒ 帧龄越阈 + 连续 2 tick ⇒ `CONNECTING(REMOTE_FRAME_STALLED)`、`remoteFrameReady=false`、`remoteDimmed=true`；宽限期后 ⇒ `FAILED` + 可重试 |
| `iceFlapWithLiveMediaKeepsUiConnected` | ICE 掉线 + 媒体新鲜 ⇒ **保持 CONNECTED**、`reason` 不出现 `connection_lost`、`iceDown=true`；媒体随后真停 ⇒ 原因归类为 `CONNECTION_LOST` |
| `stallDebounceAbsorbsSingleGap` | 单次越阈不判停滞；帧恢复后计数清零、保持 connected |
| `mediaLivenessSourceAndAgeAreRecorded` | `mediaSource`/`mediaAgeMs` 正确落到状态（`sink` → `down_bps` 切换可见），tick 时帧龄随之外推 |
| 既有 18 用例（含 t59/t60/t61 的连接中/重试/两档阈值/A7/`up_bps` 禁令） | 全部保持通过；其中 3 条按新语义校正：`lostAfterConnectedFallsBackThenFails`（媒体已陈旧才允许跳中断）、`recoveryWithinGraceKeepsCallAlive`（走真实"中断→宽限内恢复"路径）、`stalledRemoteFrameFallsBackAndEventuallyFails`/`stallIgnoresUpstreamOnlySamples`（补去抖所需的第 2 个 tick） |

**离线预检（宿主机 /tmp；容器内无 JDK，非 Gradle）**：
- Kotlin 2.0.21 + 真实 classpath（`android.jar`(SDK34) + `third_party/libwebrtc/java/libwebrtc-java.jar` + 230 项依赖）
  + 序列化/Compose 插件，整模块编译 `app/src/main/kotlin` + `app/src/test/kotlin` ⇒ **`EXIT=0`、`error:` 计数 0**
  （本任务 4 个改动文件零错误；源文件 mtime 均早于编译）。
- JUnit4：**全量 13 个测试类 `OK (120 tests)`**（t62 基线 115 → +5，无回归）。
- 本轮另发现并修复 1 个真实缺陷（`onConnectionLost` 抑制分支未回写 `status`）—— 由新增单测当场抓出，证据链完整。
- Gradle `:app:testDebugUnitTest`/`assembleDebug` 与真机由 captain 构建窗口执行。

---

## §4 事件登记（新增/扩字段；既有事件名一个未删改名）

| 事件 | 等级 | 关键字段 |
|---|---|---|
| `remote_frame_liveness` | INFO | `source=sink\|down_bps\|none`、`age_ms`、`frames`、`down_bps`、`phase`、`view`、`seq`、`session`（**t63 新增**；来源变化即记，否则每 15 s） |
| `ice_flap_suppressed` | WARN | `detail`（ICE 事件原文）、`media_source`、`age_ms`、`seq`、`session`（**t63 新增**） |
| `ui_conn_state` | INFO | 追加 `media_source`、`media_age_ms`、`ice_down`（原有 `phase/reason/elapsed_ms/pair/frame/stalled/retry/seq/session` 保留） |
| `renderer_attached` | INFO | 追加 `frame_sink=wrapped`（表明已挂每帧计数包装） |
| `renderer_on_frame_failed` | WARN | `which`、`reason`（**t63 新增**，转发异常兜底） |
| 既有 | — | `remote_first_frame`/`remote_frame_ignored`/`frame_seen_since`/`remote_dimmed` 等一律保留；`renderer_created/detached/attached/released/recreate/attach_rejected` 语义未变 |

---

## §5 verify 命令原始输出

### V1
```
$ cd code/webrtc-demo && grep -n 'remoteFrameReady\|onFrameResolutionChanged\|remote_frame_stalled\|connection_lost\|down_bps' \
    app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt \
    app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt | head -25
CallViewModel.kt:212:                // 次选 `down_bps > 0`（真的在收）。二者都不新鲜才算"画面没在更新"。
CallViewModel.kt:232:     * 【t63】落盘帧存活诊断（`remote_frame_liveness source=… age_ms=N frames=… down_bps=…`）。
CallViewModel.kt:249:                "down_bps" to downBps.toString(),
CallViewModel.kt:251:                "view" to connStatus.value.remoteFrameReady.toString(),
CallViewModel.kt:583:                appendLine("up_bps=${state.upBitrate} down_bps=${state.downBitrate}")
CallViewModel.kt:775:                        // 只落盘供失败归因；真正的中断由"帧龄 + 去抖"判定后可归因为 connection_lost。
CallViewModel.kt:836:        //   ② `down_bps > 0`（**下行字节**是唯一能证明路径打通的信号）；
CallViewModel.kt:845:        // 【t63】**媒体存活**是独立通道，与"是否已连接"解耦：`down_bps > 0` 必须参与存活判定，
CallViewModel.kt:944:                isRemoteVideoReady = status.phase == ConnPhase.CONNECTED && status.remoteFrameReady,
CallViewModel.kt:956:                    "frame" to status.remoteFrameReady.toString(),
CallViewModel.kt:1025:     * 字段刻意与真机定因时用到的口径一致（`impl`/`avail_bps`/`up_bps`/`down_bps`），
CallViewModel.kt:1042:                "down_bps" to (stats?.downBitrateBps?.toString() ?: "-"),
VideoRendererPool.kt:35://     时仍会回调 `onFrameResolutionChanged`，所以"回调还在"**不能**证明画面正常；判活必须用
VideoRendererPool.kt:80:    // `onFrameResolutionChanged`）里更新 —— 视频正常流动时分辨率几乎不变 ⇒ 该时间戳长期不动，
VideoRendererPool.kt:82:    // 间隔 4.57 s ≈ 阈值；而同刻 `down_bps` 稳定 645k–680k，画面确实在更新）。
VideoRendererPool.kt:111:     * `onFirstFrameRendered()` / `onFrameResolutionChanged()`；传 `null` 会让首帧事件**永不触发**，
VideoRendererPool.kt:171:            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
VideoRendererPool.kt:441:// 画面就此永久黑掉，而 `EglRenderer` 仍会在丢帧前回调 `onFrameResolutionChanged`
VideoRendererPool.kt:508:    /** 收到一帧（任一渲染器的 `onFrameResolutionChanged` / 首帧回调）。 */
EXIT=0
```
**读法**：`onFrameResolutionChanged` 只以**遗留路径**（t45 恢复判活）存在，**不再**参与帧存活；`remoteFrameReady` 由
`ConnectionStatusTracker` 统一维护（`CallViewModel.kt:944` 处由它派生 `isRemoteVideoReady`）。

### V2
```
$ cd code/webrtc-demo && grep -rn 'onFrame\|frameLiveness\|lastFrameAt' app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt | head -15
34://   * **每渲染器最后一帧时间戳**（`lastFrameAtNs`）—— 注意 `EglRenderer` 在**丢弃**帧（无 surface）
35://     时仍会回调 `onFrameResolutionChanged`，所以"回调还在"**不能**证明画面正常；判活必须用
80:    // `onFrameResolutionChanged`）里更新 —— 视频正常流动时分辨率几乎不变 ⇒ 该时间戳长期不动，
83:    // 现在把**远端渲染器**的 sink 包一层：`VideoSink.onFrame` **每帧**都会调用 ⇒ 计数 + 墙上时刻。
85:    /** 远端 `VideoSink.onFrame` 的实际挂载对象（计数包装，转发给渲染器；detach 时移除的是它）。 */
111:     * `onFirstFrameRendered()` / `onFrameResolutionChanged()`；传 `null` 会让首帧事件**永不触发**，
171:            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
248:        // 【t63】多包一层 VideoSink：`onFrame` **每帧**触发 ⇒ 计数 + 墙上时刻（帧存活判定的唯一真源）。
254:                renderer.onFrame(frame)
358:    fun lastFrameAtNs(which: String): Long =
363:    fun frameSeenSince(which: String, sinceNs: Long): Boolean = lastFrameAtNs(which) >= sinceNs
370:     * 【t63】这是"画面是否在更新"的**唯一可靠真源**：由 `VideoSink.onFrame` 每帧刷新，
371:     * 不像 `lastFrameAtNs` 那样只在首帧/分辨率变化等稀疏回调里更新。
387:            lastFrameAtMs = last,
396:     * @property lastFrameAtMs 最近一帧墙上时刻（0 = 从未收到）。
EXIT=0
```
**读法**：`:254 renderer.onFrame(frame)` = 包装层**每帧**转发点；`:370-396` = `remoteFrameLiveness()` 查询接口；
`lastFrameAtNs`/`frameSeenSince`（`:358`/`:363`）作为 t45 遗留 API **保留未动**。

### V3
```
$ cd code/webrtc-demo && ls -l reports/32-remote-frame-liveness.md && git status --porcelain
-rw-r--r-- 1 node node <采集时刻 size> <采集时刻 time> reports/32-remote-frame-liveness.md
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt
 M app/src/test/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatusTrackerTest.kt
?? reports/32-remote-frame-liveness.md
（另有 captain/其他成员的产物：`reports/05-*`、`scripts/t5*`、`reports/10-t47b-*`、`reports/26-*` 等，非本任务）
```
> mode 恒 `644`；size/mtime/sha256 以交付消息为准。

### 版本上下文（采集时）
```
$ git log --oneline -1
bac78a2 fix(android): t60+t61 4G/中继健壮性与连接可见性——…；全量 115 tests
```
> 本任务改动尚未提交（未获提交授权）。

---

## §6 未验证项（诚实清单）

| # | 未验证项 | 为什么没验 | 复测方式 |
|---|---|---|---|
| U1 | **真机 30 秒稳定性**：`ui_conn_state` 稳定 `phase=connected frame=true`（connected 占比 100%、无 `remote_frame_stalled`） | 本成员无真机、无截图能力 | 4G↔WiFi 通话 ≥30 s，核对：`remote_frame_liveness source=sink age_ms<200` 持续出现、`ui_conn_state` 不再出现 `reason=remote_frame_stalled`、界面不弹「等待对方画面」 |
| U2 | 包装 sink 后**渲染无副作用**（画面正常显示、切前后台恢复不受影响） | 需真机肉眼 + 日志 | 通话中确认画面/镜像正常；切后台再回前台看 `preview_recover_*`（t45 路径）仍绿 |
| U3 | `removeSink(包装层)` 在真机上确实生效（防重复挂载/漏摘） | 需真机 | 看 `renderer_attached`/`renderer_detached` 成对；无 `renderer_attach_rejected` |
| U4 | ICE flap 抑制是否"抑制过头"（真实断网时界面会不会迟迟不报） | 需真机（真断网场景） | 真断网：帧龄 + 去抖（约 5–6 s）后应出现「连接中断」→ 8 s 后 `FAILED` + 重试 |
| U5 | Gradle 全量单测/构建 | 容器无 JDK/SDK；构建窗口归 captain | captain 构建窗口（本报告提供离线预检证据：120 tests OK、整模块 0 error） |

### 6.1 真机复测清单（供 captain 交付前执行）

1. 4G↔WiFi 通话 ≥30 s，确认**不再**出现「等待对方画面」；`app.log` 中
   `remote_frame_liveness source=sink age_ms=<小值> frames=<递增> down_bps=<>0>` 持续出现。
2. 若某刻只有 `down_bps`（`source=down_bps`）也属正常（备用通道）；关键判据是**不出现**
   `ui_conn_state … reason=remote_frame_stalled`（除非真的停流）。
3. 制造真停流（对端关摄像头/断网）：预期 4–6 s 内出现「画面已中断」并压暗远端；再约 8 s ⇒ 失败卡 + 「点击重试」。
4. 观察 `ice_flap_suppressed`：若出现且同刻画面正常 ⇒ 修复生效（此前该场景会跳「连接中断」）。
5. 切后台→回前台，确认 t45 预览恢复路径未受影响（`preview_recover_armed`/`preview_recover_ok`）。

---

## §7 边界与遗留

- **未动**渲染生命周期：`VideoRendererPool` 只新增"包装 sink + 计数/时间戳 + 查询接口"，
  `createRenderer/recreateRenderer/releaseRenderer/detachAndReleaseAll/surfaceAlive/markFrame` 语义与顺序未变；
  包装层转发 `renderer.onFrame(frame)`，渲染内容与镜像配置不变。
- **未碰** `cpp/**`、`nat/**`、`config/**`、`doc/**`（冻结）、`third_party/**`、`scripts/**`；
  `CallSession.kt`（t60 范围）本轮**未改** —— 本缺陷纯客户端 UI 判活问题。
- **`connection_lost` 纪律**：仍然**只**由 `pc_connection_state`/ICE 事件产生（不由帧信号推断）；
  t63 只调整了"何时展示为中断"（媒体仍流动时抑制界面跳变），ICE 事实始终落盘（`ice_down`/`ice_flap_suppressed`）。
- **遗留建议**：
  1. 本地预览（`local`）通道目前没有每帧时间戳；若后续要判"本地预览是否在动"（t45 场景已有 `frameSeenSince`），
     可复用同一包装层给 `attachLocal` 也加一份；
  2. `FRAME_ALIVE_MS=1.5 s` 与 `DEFAULT_FRAME_STALL_MS=4 s` + 2 tick 去抖的取值可在真机上进一步收敛
     （若观察到弱网下频繁"画面已中断"，可把去抖提到 3 tick）。
