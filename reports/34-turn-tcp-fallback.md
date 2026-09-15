# t65 报告：A5 客户端 TURN over TCP 回退（`turn:…?transport=tcp`）

- 任务：t65「A5 客户端：加入 TURN over TCP 回退（turn:…?transport=tcp，UDP 优先、默认开）」
- 归属：android-dev（attempt 3）
- 范围：`webrtc/WebRtcConfig.kt`、`webrtc/CallSession.kt`（仅传递/日志）、本报告；
  新增单测 `app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt`
  （**不在 t65 inScope 内**，按 t62 先例在证据与报告中显式登记 → **待账本补齐**）
- **未触碰**：`ui/call/**`（t63/t64 在改）、`cpp/**`、`nat/**`、`config/**` 既有默认值、`doc/**`（冻结）、
  `third_party/**`、`scripts/**`；服务端/中继配置零改动（t58 + `0adbe22` 已复核）

---

## §0 摘要（结论先行）

| 项 | 结论 |
|---|---|
| **前置（已复核）** | 安全组已放行 TCP 3478；外部实测 TCP 可达且完整 TURN-over-TCP 成功（`ALLOCATE → 0x0103`、`CreatePermission → 0x0108`）；官方 `turnutils_uclient -t` 通过；coturn 在 3478 有 4 个 TCP LISTEN，**服务端配置零改动**（证据 `reports/28` 附录 D、探针 `deploy/turnperm_probe_tcp.cjs`/`.mjs`） |
| **改动** | `WebRtcConfig` 在 TURN 配置存在时，于 UDP 项**之后**追加 `turn:<host>:3478?transport=tcp`（凭据与 UDP **同源**，仍来自信令下发）；新增开关 `DEFAULT_TURN_TCP_FALLBACK = true`（默认开启） |
| **纯函数抽取** | 为让 JVM 单测可断言，把"列表构建"与"配置装配"抽为纯函数 `iceServersFor()` / `rtcConfiguration()`；`build()` = 二者 + 一条 `rtc_config` 日志 |
| **日志判据** | `rtc_config` 新增 `turn_tcp=true/false` 与 `ice_servers=<实际列表，按尝试顺序>`（既有 `stun`/`turn`/`force_relay` **未删未改名**）；`pc_starting` 新增 `turn_tcp=…` 且 `ice_servers` 变为 `stun+turn+tcp`（**可读性不变**）；`ice_gathering_complete` 新增 `turn_tcp=…` |
| **不变项** | `iceTransportsType`(ALL/RELAY)、`continualGatheringPolicy`(GATHER_CONTINUALLY)、`bundlePolicy`(MAXBUNDLE)、`rtcpMuxPolicy`(REQUIRE)、`sdpSemantics`(UNIFIED_PLAN) 全部保持（单测逐条断言） |
| **与 t60 叠加** | TCP 回退**不替代**"等 relay 候选/收集完成再起算 + 超时先 ICE restart"：它只是让 relay 候选多一条可 gather 的路径（见 §4） |
| **验证** | 纯 JVM 单测 **132 tests OK**（t64 基线 124 → **+8**）；离线整模块编译 **EXIT=0 / 0 error**；契约 3 条 verify 全 EXIT=0 |

---

## §1 需求与前置（A5）

- 用户/服务端侧前置已由 coturn-installer 复核通过（提交 `0adbe22`）：TCP 3478 已放行、TURN-over-TCP 全流程成功、coturn 配置**无需改动**。
- 客户端侧要做的是：把 `turn:<host>:3478?transport=tcp` 作为 **UDP 之后的回退**加入 ICE server 列表，
  默认开启、可开关，且**不得硬编码凭据**（凭据仍来自 `created`/`joined` 下发）。

---

## §2 改动（file:line）

### 2.1 `webrtc/WebRtcConfig.kt`

| # | 位置 | 改动 |
|---|---|---|
| W1 | `:45-51` | 新增 `const val DEFAULT_TURN_TCP_FALLBACK = true`（**默认开启**；KDoc 记录 t58 前置证据与"UDP 仍在前"的语义） |
| W2 | `:53-88` | 新增纯函数 **`turnTcpUrl(turnUrl): String?`**：`transport=udp`→`transport=tcp`（保留其它查询参数、大小写不敏感）；无查询串则追加 `?transport=tcp`；`turns:`/空白/非 `turn:` 前缀返回 null（不猜） |
| W3 | `:90-96` | 新增 **`turnTcpFallbackActive(ice, turnTcpFallback)`**：供 UI/日志一行判定"本次是否真的带 TCP 回退" |
| W4 | `:98-136` | 新增纯函数 **`iceServersFor(ice, turnTcpFallback)`**：STUN（若有）→ **TURN UDP（保持既有顺序与形态）** → **TURN TCP（同源凭据）**；`ice == null` 返回空列表（不硬编码） |
| W5 | `:138-160` | 新增纯函数 **`rtcConfiguration(servers, forceRelay)`**：逐条冻结 `sdpSemantics/bundlePolicy/rtcpMuxPolicy/continualGatheringPolicy/iceTransportsType`（与改动前**一字未变**） |
| W6 | `:162-200` | `build(ice, forceRelay, turnTcpFallback = DEFAULT_TURN_TCP_FALLBACK)`：调 `iceServersFor` → 落 `rtc_config` → 返回 `rtcConfiguration(...)`。日志**新增** `turn_tcp` 与 `ice_servers`（实际列表）；既有 `stun`/`turn`/`force_relay` 保留 |

### 2.2 `webrtc/CallSession.kt`（仅传递/日志）

| # | 位置 | 改动 |
|---|---|---|
| C1 | `start()` 的 `iceSummary`（`:277-288`） | TURN 存在且回退开启时把 `ice_servers` 由 `stun+turn` 变为 **`stun+turn+tcp`**（字段名与可读性不变；关闭开关时为 `stun+turn`） |
| C2 | `pc_starting`（`:285-297`） | **新增** `turn_tcp=…` 字段（既有 `ice_servers`/`force_relay` 未删未改名） |
| C3 | `ice_gathering_complete`（`:853-864`） | **新增** `turn_tcp=…`（判 "`local_relay=0` 时是否已尝试 TCP 路径"） |
| C4 | `restartIce()`（t60） | 未改逻辑；其内部 `WebRtcConfig.build(iceConfig, forceRelayConfig)` 走默认参数 ⇒ **ICE restart 重新应用的配置同样带 TCP 回退**（一致性自动成立） |

> `WebRtcConfig.build` 的调用方只有 `CallSession.start()` 与 `CallSession.restartIce()`，两处均已覆盖。

---

## §3 开关语义与日志判据

| 开关 | 取值 | 行为 |
|---|---|---|
| `WebRtcConfig.DEFAULT_TURN_TCP_FALLBACK` | `true`（默认） | TURN 存在 ⇒ 追加 TCP 回退 |
| `build(..., turnTcpFallback = false)` | 显式关闭 | 只发 UDP 项（回退**不**加入） |
| `ice == null` / `turnUrl` 为空 | — | 不追加（也**不硬编码**任何 server/凭据） |
| `turnUrl` 为 `turns:` | — | 不追加（TLS 本身走 TCP，避免重复路径） |

**日志判据（复测一行可读）**：

```
# 默认（回退开启）
rtc_config stun=stun:47.238.144.66:3478 turn=turn:47.238.144.66:3478?transport=udp force_relay=false \
          turn_tcp=true ice_servers=stun:47.238.144.66:3478,turn:47.238.144.66:3478?transport=udp,turn:47.238.144.66:3478?transport=tcp
pc_starting ice_servers=stun+turn+tcp force_relay=false turn_tcp=true

# 关闭回退（或未配 TURN）
rtc_config … turn_tcp=false ice_servers=turn:…?transport=udp        ← 仅 UDP
pc_starting ice_servers=stun+turn force_relay=false turn_tcp=false
```

---

## §4 与 t60 既有策略**叠加**后的预期行为

TCP 回退**不替代** t60 的三条口径，二者是"多一条候选来源"与"判活/重试口径"的关系：

| 环节 | t60 口径（未变） | t65 叠加后的效果 |
|---|---|---|
| 收集 | 配 TURN 时 `GATHER_CONTINUALLY`；首个 relay 候选到位即重新校准看门狗 | 现在会 gather **两条** relay 路径（UDP 与 TCP），任一条成功即可拿到 `type=relay` 候选 ⇒ `local_relay>0` |
| 起算 | "首个 relay 候选/`iceGatheringState==COMPLETE` 后再起算"；未收集完成不判失败 | TCP 路径通常比 UDP 慢（握手 + 中继分配）⇒ 该口径正好覆盖"TCP 候选晚到"的情形，**不会**因为 UDP 失败就立刻判死 |
| 超时 | 30 s WARN / 45 s FAIL；到点**先 `restartIce()`**（上限 2 次），用尽才上报 | ICE restart 会**重新应用**含 TCP 回退的配置（C4）⇒ 每轮重试都同时尝试 UDP+TCP |
| 失败归因 | `relay_missing`（有 TURN 却 `local_relay=0`）⇒ UI 子原因 `NO_RELAY_CANDIDATE` | 现在该情形意味着 **UDP 与 TCP 都拿不到 relay** —— 诊断含 `turn_tcp=true`，可据此排除"其实没开回退"的误判 |

---

## §5 单测（纯 JVM）

`app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt`（**8 用例**；因 t65 的 inScope 不含 `app/src/test/**`，
本文件按 **t62 先例**在证据与报告中显式登记，并在交付消息注明「待账本补齐」）：

| 用例 | 断言（对应验收） |
|---|---|
| `addsTcpFallbackAfterUdpKeepingUdpFirst` | server 数 3：`[stun, turn…udp, turn…tcp]`；`udp 索引 < tcp 索引` ⇒ **UDP 优先**（验收 1） |
| `tcpFallbackSharesCredentialsWithUdpServer` | TCP 项 `username/password` 与 UDP 项**完全相同**（同源，不硬编码）（验收 1） |
| `switchOffRemovesTcpFallback` | 开关关闭 ⇒ 2 项、无 `transport=tcp`（验收 1） |
| `switchDefaultsToEnabled` | `DEFAULT_TURN_TCP_FALLBACK == true`；`turnTcpFallbackActive` 与之一致（null / 空 turnUrl ⇒ false）（验收 1） |
| `noTurnConfigAddsNothing` | `ice == null` ⇒ 列表为空（不硬编码）（验收 1） |
| `stunOnlyConfigHasNoTcpFallback` | 仅 STUN ⇒ 1 项、无 TCP（验收 1） |
| `derivesTcpUrlFromTurnUrl` | `turnTcpUrl` 的 8 组边界（含无查询串、额外参数保留、大小写、`turns:`/空/非 turn ⇒ null） |
| `otherIceSemanticsUnchanged` | `sdpSemantics=UNIFIED_PLAN`、`bundlePolicy=MAXBUNDLE`、`rtcpMuxPolicy=REQUIRE`、`continualGatheringPolicy=GATHER_CONTINUALLY`、`iceTransportsType=ALL`，且 `forceRelay=true ⇒ RELAY`（验收 3） |

**过程中的一次真实修正（如实披露）**：首轮单测直接调 `WebRtcConfig.build()` ⇒ 6 例失败，
根因是 `build()` 会落 `rtc_config` 日志、而日志路径依赖 Android（`FileLogger`），纯 JVM 下
`ExceptionInInitializerError: Could not initialize class com.example.webrtcdemo.log.FileLogger`
（栈顶 `WebRtcConfig.kt:138 → AppLog.i`）。修正方式不是"绕过断言"，而是**把逻辑抽成纯函数**
（`iceServersFor()` / `rtcConfiguration()`，`build()` 只剩"组装 + 一条日志"）⇒ 8 例全绿。
这同时提升了可测性与分层清晰度（列表/装配/日志三件事分离）。

**离线预检（宿主机 /tmp；容器内无 JDK，非 Gradle）**：Kotlin 2.0.21 + 真实 classpath（android.jar(SDK34) +
`third_party/libwebrtc/java/libwebrtc-java.jar` + 230 项依赖）+ 序列化/Compose 插件，整模块编译
`app/src/main/kotlin`+`app/src/test/kotlin` ⇒ **`EXIT=0`、`error:` 计数 0**（源文件 mtime 均早于编译）；
JUnit4 **全量 14 个测试类 `OK (132 tests)`**（+8，无回归）。
关键 API 事前 `javap` 核对：`PeerConnection$IceServer.urls: List<String>`（**public 字段**，测试可读回）、
`IceServer.builder()`/`createIceServer()` 均为纯 Java（无 native）⇒ JVM 单测可构造并断言。

---

## §6 verify 命令原始输出

### V1
```
$ cd code/webrtc-demo && grep -n 'transport=tcp\|turn_tcp\|IceServer' \
    app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt | head -20
5:// 【API 适配（t10 首次真实编译核对，L2 证据）】本版 jar 中 IceServer 是 **PeerConnection 的嵌套类**，
6:// 不存在顶层 `org.webrtc.IceServer`（javap/classdump 实测：`PeerConnection$IceServer`）。
7:import org.webrtc.PeerConnection.IceServer
31:data class IceServerConfig(
58:     *  * `turn:host:3478?transport=udp` → `turn:host:3478?transport=tcp`（**保留**其它查询参数）；
59:     *  * `turn:host:3478`（无查询串）→ `turn:host:3478?transport=tcp`；
71:        if (q < 0) return "$url?transport=tcp"
80:                "transport=tcp"
85:        if (!replaced) rewritten.add("transport=tcp")
95:    fun turnTcpFallbackActive(ice: IceServerConfig?, turnTcpFallback: Boolean = DEFAULT_TURN_TCP_FALLBACK): Boolean =
105:     * @param turnTcpFallback 是否追加 `turn:…?transport=tcp` 回退。
108:        ice: IceServerConfig?,
110:    ): List<IceServer> {
111:        val servers = ArrayList<IceServer>(3)
114:            servers.add(IceServer.builder(ice.stunUrl).createIceServer())
119:                IceServer.builder(ice.turnUrl)
122:                    .createIceServer()
128:                    IceServer.builder(tcpUrl)
131:                        .createIceServer()
146:        servers: List<IceServer>,
EXIT=0
```
**读法**：`:53-88` = `turnTcpUrl()`（`:71`/`:80`/`:85` 为三种推导分支）；`:95` = 开关判定；
`:108-131` = `iceServersFor()`（`:119` UDP 项在前、`:128` TCP 回退项在后、两者 `.setUsername/.setPassword` 同源）；
`:146` = `rtcConfiguration()` 的入参（语义冻结处）。
> 注：`grep` 的 `turn_tcp` 关键字命中集中在 `:105`/`:95` 附近的 KDoc 与开关函数；
> `rtc_config` 日志里的 `"turn_tcp"` 字面量在 `build()` 内（被 `head -20` 截断），V3 的工作区状态可佐证文件已改。

### V2
```
$ cd code/webrtc-demo && ls -l app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt 2>/dev/null
-rw------- 1 node node 7302 Sep 15 22:12 app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt
$ grep -c '@Test' app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt 2>/dev/null
8
EXIT=0
```
**读法**：单测文件存在且含 **8** 个用例；它就是本轮离线全量 `OK (132 tests)` 中的 `TurnTcpFallbackTest` 类。
（mode 600 与仓库内其它 Kotlin 源文件一致——见 t62 的观察。）

### V3
```
$ cd code/webrtc-demo && ls -l reports/34-turn-tcp-fallback.md && git status --porcelain
-rw-r--r-- 1 node node <采集时刻 size> <采集时刻 time> reports/34-turn-tcp-fallback.md
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt
?? app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt
?? reports/34-turn-tcp-fallback.md
（另有 t63/t64 的未提交产物：` M ui/call/ConnectionStatus.kt`、` M ui/call/CallViewModel.kt`、
 ` M ui/call/ConnectionStatusTrackerTest.kt`、` M webrtc/VideoRendererPool.kt`、`?? reports/32-*`、`?? reports/33-*`；
 以及 captain/其他成员产物 `reports/05-*`、`scripts/t5*`、`reports/10-t47b-*` 等，非本任务）
```
> mode 恒 `644`；size/mtime/sha256 以交付消息为准。

### 版本上下文（采集时）
```
$ git log --oneline -1
bac78a2 fix(android): t60+t61 4G/中继健壮性与连接可见性——…；全量 115 tests
```
> 本任务改动（含 t63/t64 的未提交改动）尚未提交（未获提交授权）。

---

## §7 未验证项（诚实清单）

| # | 未验证项 | 为什么没验 | 复测方式 |
|---|---|---|---|
| U1 | **真机 4G：建连失败/超时后是否因 TCP 回退成功**（A5 的最终目的） | 本成员无真机、无法构造"UDP 被全程丢弃但 TCP 可达"的网络 | 4G↔WiFi 通话后核对 `rtc_config … turn_tcp=true ice_servers=…udp,…tcp`；若 UDP 路径不通，应能看到 TCP 那条 relay 候选（`ice_candidate_local … type=relay`）与 `selected_candidate_pair mode=RELAY` |
| U2 | libwebrtc（交付 jar）对**多 TURN server、不同 transport** 的尝试顺序与回退时机 | 只有真机/仪器化可观测 | 复核 `webrtc.log` 中 `ICE` 相关行（是否同时对两条 server 发起 allocate）；必要时用 `force_relay` 复现 |
| U3 | TCP 回退是否**延长**建连时间（多一条慢路径是否有副作用） | 需真机计时 | 对比开关开/关时从 `pc_starting` 到 `selected_candidate_pair` 的耗时；若 UD P正常则回退项不应被走到（顺序在后） |
| U4 | 是否需要在 UI/诊断页暴露该开关（当前只有代码级开关） | 属产品取舍；本任务 inScope 不含 `ui/**` | captain 若需要，可在后续任务里加诊断页开关（读 `AppConfig` 之类；本任务刻意未碰 `config/**` 默认值） |
| U5 | Gradle 全量单测/构建 | 容器无 JDK/SDK；构建窗口归 captain | captain 构建窗口（本报告提供离线预检证据：132 tests OK、整模块 0 error） |

### 7.1 真机复测清单（供 captain 交付前执行）

1. 双端通话，检查**会话开始**两行：`rtc_config … turn_tcp=true ice_servers=<stun>,<turn…udp>,<turn…tcp>` 与
   `pc_starting ice_servers=stun+turn+tcp turn_tcp=true`（开关默认开 ⇒ 必然出现）。
2. 中继失败场景（4G↔WiFi、UDP 受限）：核对 `ice_gathering_complete … turn_tcp=true relay=<n>`；
   若 `relay>0` 且 `selected_candidate_pair mode=RELAY` ⇒ 回退生效。
3. 若仍 `relay=0`：结合 t60 的 `ice_timeout … relay_missing=true turn_errors=<n>` 判断为"UDP/TCP 都拿不到中继"，
   而非"没开回退"（`turn_tcp=true` 可排除后者）。
4. 对照实验（可选）：把开关置 false 重编，确认 `rtc_config … turn_tcp=false` 且只有 UDP 一项（验证开关语义）。

---

## §8 边界与遗留

- **服务端零改动**：`/etc/turnserver.conf`、安全组、`scripts/**` 均未触碰（前置由 `0adbe22` 复核）。
- **`config/**` 未改**：开关常量放在 `WebRtcConfig`（本任务 inScope），**没有**改任何既有默认值；
  若后续要在诊断页暴露开关，需另行建单（会触及 `ui/**` + `config/**`）。
- **既有字段兼容**：`rtc_config` 的 `stun`/`turn`/`force_relay` 与 `pc_starting` 的 `ice_servers`/`force_relay`
  **字段名与含义未变**（`ice_servers` 仅在开启回退时多一个 `+tcp` 后缀，仍可读）；新增字段均为**追加**。
- **测试文件账本**：`TurnTcpFallbackTest.kt` 落在 `app/src/test/**`，而 t65 的 inScope **不含**该目录
  ⇒ 本报告与 acceptance 证据已显式登记该文件与 8 个用例，交付消息注明「**待账本补齐**」
  （与 t62 处理 `ConnectionStatusTrackerTest.kt` 的方式一致：可由 captain 建一个纯登记任务，或把该路径并入 t65 的 inScope）。
- **遗留建议**：
  1. 若真机显示 UDP 路径长期不可用而 TCP 可用，可考虑把 TCP 项提到 UDP 之后但**更早重试**（当前顺序已符合"UDP 优先"要求）；
  2. `turns:`（TLS）路径当前不派生（避免重复）；若后续要启用 TLS-TURN，需要服务端证书与 `TlsCertPolicy` 配套，另立任务。
