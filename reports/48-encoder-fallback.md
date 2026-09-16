# 48 · 自研编码自动降级兜底（t87）

- 任务：t87（implementation，attempt 1）
- 负责：android-dev
- 关联：doc/14 §5.3/§5.4/§7.1、doc/10 §8；上游 t85（native-dev 自研软编优化）、t87 用户决策「(丙) 两手都做」的**兜底**一半
- 变更范围：`app/src/main/kotlin/.../encoder/**`、`webrtc/WebRtcEngine.kt`、`config/AppConfig.kt`、`diag/DiagnosticsScreen.kt`、`ui/call/CallViewModel.kt`、`app/src/test/kotlin/.../encoder/**`、本报告
- **未改**：`app/src/main/cpp/**`、`signaling/**`、`doc/**`、`third_party/**`；未跑宿主机 Gradle；未发布；未 `git commit`

---

## 1. 结论（TL;DR）

1. **判据**：新增纯函数 `EncoderFallbackPolicy`（`encoder/EncoderFallbackPolicy.kt`）。输入 = 按秒聚合的窗口样本
   （JNI 边界实测单帧耗时 p95 / 产出帧数 / 无产出帧数 / 请求帧率）+ 本次通话切换状态 + 当前时刻；
   输出 = `KEEP_SELF | SWITCH_TO_DEFAULT`，**含窗口累积、30 s 滞回、一轮通话最多切 1 次**（三层都点名单测）。
2. **机制**：采用 **通话内切换**（libwebrtc 官方 `VideoEncoderFactory.getEncoderSelector()` →
   `VideoEncoderSelector.onAvailableBitrate/onResolutionChange/onEncoderBroken` 返回非空格式即“请求切换”），
   并在 **5 s 内未获确认时自动退化**为 `pendingFallbackForNextCall`（下次通话一开始就用默认实现）。
   **两条路径都不中断通话、不丢房间**，且都不需要改 `webrtc/CallSession.kt` 的会话生命周期、不需要改 `cpp/jni/**`。
3. **开关/覆盖**：诊断页新增三态「自动 / 自研 / 默认」（默认 = 自动 + 兜底开启），持久化到既有 `AppConfig`（`app_cfg`），
   UI 实时显示当前实现、原因、最近窗口指标与生效机制。
4. **诊断键**：`encoder_fallback_decision` / `encoder_fallback_applied` / `encoder_fallback_probe`（每窗口一条，未切换时也能看出策略在跑）。
5. **验证**：离线全量 **22 类 / 206 例 / 0 failures**（新增 2 类 15 例）；old-red 对照 **6 例必红**（回退判据 ⇒ 红）。

---

## 2. 机制路径与可行性证据（先核实、后实现）

### 2.1 本版 libwebrtc 支持通话内切换（L2 静态实测）

| 证据 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| Java 侧 selector 接口存在 | `javap -p -classpath libwebrtc-java.jar 'org.webrtc.VideoEncoderFactory$VideoEncoderSelector'` | `void onCurrentEncoder(VideoCodecInfo)`；`@Nullable VideoCodecInfo onAvailableBitrate(int)`；`@Nullable VideoCodecInfo onResolutionChange(int,int)`（default）；`@Nullable VideoCodecInfo onEncoderBroken()` |
| 工厂可提供 selector | `javap … org.webrtc.VideoEncoderFactory` | `default VideoEncoderFactory$VideoEncoderSelector getEncoderSelector()` |
| 原生侧确实会回调 Java selector | `strings libjingle_peerconnection_so.so`（aar 内） | `getEncoderSelector`、`()Lorg/webrtc/VideoEncoderFactory$VideoEncoderSelector;`、`onCurrentEncoder`、`onAvailableBitrate`、`onResolutionChange`、`onEncoderBroken` 各 1 处；另有 `RecreateWebRtcStream (send) because of SetEncoderSelector, ssrc=` |
| 原生语义（返回非空 = 请求切换） | `third_party/libwebrtc/include/api/video_codecs/video_encoder_factory.h` `EncoderSelectorInterface` | “Called every time the available bitrate is updated. **Should return a non-empty if an encoder switch should be performed**”“… Creates a EncoderSelector to use for a VideoSendStream” |

结论：**通话内切换可用**，实现完全落在 Kotlin 侧（工厂 + selector），无需改 C++、无需改会话生命周期。

### 2.2 实现的数据流

```
libwebrtc 编码线程 ── Vp9VideoEncoder.encode()
      │  System.nanoTime() 包住 nativeEncode；produced = (status == OK)
      ▼
EncoderFallbackController.onEncodeResult(单帧耗时, 是否出帧)      ← 只计数：一把极短锁、无 I/O、无排序/日志
      │  满 1 s ⇒ 快照投递到单线程后台执行器 enc-fallback
      ▼
evaluateWindow()：p95 / out_fps / 请求帧率 ⇒ EncodeWindowSample ⇒ 滚动 10 窗口 ⇒ EncoderFallbackPolicy.decide()
      │                                   │
      │ 每窗口 1 条                        └─ SWITCH_TO_DEFAULT ⇒ requestInCallSwitch()
      ▼                                                              │
encoder_fallback_probe(ok=1 …)                     selector 下一次 onAvailableBitrate 取走请求
                                                                     ▼
                            Vp9VideoEncoderFactory.createEncoder(VP9, 默认实现) → onEncoderCreated(DEFAULT)
                                                                     ▼
                                   encoder_fallback_applied impl=<默认实现名> mech=in_call_switch confirmed=1
      └─ 5 s 未确认 ⇒ encoder_fallback_applied impl=default mech=next_call confirmed=0 + pendingFallbackForNextCall=true
         （本次通话继续自研；下次通话 onCallStarted 时直接采用默认实现，仍不中断通话）
```

### 2.3 关键实现细节（file:line）

| 位置 | 说明 |
| --- | --- |
| `encoder/Vp9VideoEncoder.kt:131`（`onEncoderInit`）、`:229`（`onEncodeResult`）、`:270`（`onRequestedFps`） | 三处探针埋点：initEncode 上报尺寸/请求帧率；encode 的 `finally` 上报单帧耗时与产出；`setRateAllocation` 上报最新请求帧率 |
| `encoder/EncoderFallbackController.kt:347`（`onEncodeResult`） | 编码线程侧聚合（`WINDOW_MS=1 s`，最多 300 个样本/窗口；超 1 s 即在下一帧结算） |
| `encoder/EncoderFallbackController.kt:513`（`evaluateWindow`） | 后台线程：聚合 → 纯判据 → 记录/执行；异常经 `onEvaluateFailed` 落 `encoder_fallback_error` 并写进 UI 原因（不冒泡） |
| `encoder/EncoderFallbackController.kt:413`（`implForCreate`） | 工厂侧唯一决策入口：三态覆盖 > pending > “等待通话内切换确认” > 当前实现 |
| `encoder/EncoderFallbackController.kt:431`（`takeSwitchRequest`） | 请求**只交付一次**（避免每次码率更新都重建 send stream） |
| `encoder/EncoderFallbackController.kt:450`（`onEncoderCreated`） | 默认实现**真的被创建**才算通话内切换确认（`mech=in_call_switch`） |
| `encoder/EncoderFallbackController.kt:644`（`checkInCallConfirmTimeout`） | 5 s 未确认 ⇒ `pendingFallbackForNextCall=true` + `mech=next_call`（本次通话不中断） |
| `encoder/EncoderFallbackController.kt:232` / `:294`（`onCallStarted` / `onCallEnded`） | 通话代际复位与 pending 兑现；由 `ui/call/CallViewModel.kt:438`（`beginGeneration`）与 `:635`（`hangup`）调用 |
| `encoder/Vp9VideoEncoderFactory.kt:51`（`createEncoder`）、`:110`（`getEncoderSelector`）、`:42`（`defaultEncoderFactory`） | 降级时改用默认（硬件优先）工厂；`fallback` 语义**未变**，`defaultEncoderFactory` **不参与** `getSupportedCodecs/getImplementations` ⇒ 协商列表仍只有 VP9 |
| `encoder/FallbackVideoEncoderSelector.kt:46/57/68/79` | 三个回调统一走 `switchRequestOrNull`：只有控制器有待取请求时返回格式，且**原样回传当前 VP9 格式**（不换编解码器）；非 VP9 时拒绝切换并落 `encoder_fallback_switch_skipped` |
| `webrtc/WebRtcEngine.kt:148-155` | 注入 `DefaultVideoEncoderFactory(egl, true, true)` 作为默认实现来源，并从 `AppConfig` 读三态/开关 |
| `config/AppConfig.kt:51/54/183/202` | `encoder_override`（默认 `AUTO`）、`encoder_fallback_enabled`（默认 `true`） |
| `diag/DiagnosticsScreen.kt:198-249/382` | 三态按钮 + 兜底开关 + 当前实现/原因/窗口指标/机制展示 |
| `ui/call/CallViewModel.kt:438/635` | 通话开始/结束钩子（**未改** `webrtc/CallSession.kt`） |

---

## 3. 设计取舍

1. **为什么按秒聚合、而不是逐帧判定**：逐帧抖动大（t85 真机 p50 11.15 ms / p95 18.19 / max 35.75），单帧超时没有代表性；
   1 s 窗口能吃掉采集/GC 抖动，且与 `encoder_fallback_probe` 1 Hz 的诊断节奏一致。
2. **为什么窗口要“连续 ≥10 s”而不是“单秒即切”**：验收口径要求避免抖动误切；实现为
   `windowSeconds=10` 且 10 秒中 **≥8 秒** 为坏秒（`badSecondRatio=0.8`）才切 —— 留 2 秒容差抗抖动，同时仍是“持续跟不上”。
3. **坏秒双条件**：`encode_p95_ms > 0.6 × (1000/请求帧率)` **或** `out_fps < 0.7 × 请求帧率`（两个常量都可配置，见 `Config`）。
   前者抓“编得慢”，后者抓“编得慢到出不了帧（或干脆 NO_OUTPUT）”。无产出帧数同时作为诊断字段输出。
4. **p95 口径**：逐帧耗时算 95 分位（`ceil(0.95n)-1`）；窗口级 p95 取**窗口内各秒 p95 的最大值**（保守：宁可看到最差一秒）；
   窗口 out_fps 取**总产出/总时长**（不是各秒平均，避免权重偏差）。
5. **滞回与幂等分开可观测**：`decide` 的判定顺序是 窗口未满 → **滞回** → **次数上界** → …，因此真机日志能区分
   `reason=hysteresis`（30 s 内）与 `reason=already_switched`（滞回过后仍已切满）。这也是 old-red 里 3 条纯函数用例的靶点。
6. **为什么“请求式”而不是“直接换工厂”**：工厂实例是进程级单例（`WebRtcEngine.initialize` 时创建），
   无法在通话中途替换；唯一合法的通话内通道就是 selector 的“返回非空格式 ⇒ 原生重建 send stream ⇒ 再次 `createEncoder`”。
   因此把“切换生效”定义为**默认实现真的被创建**（可验证、可落盘），而不是“我们发了请求”。
7. **退化路径的必要性**：容器内无法验证原生是否真的重建（多流/版本差异都可能不重建）。若 5 s 内没有
   `encoder_created mech=fallback_default`，就标记 `pendingFallbackForNextCall` —— 保证“兜底一定会生效”，最坏只晚一次通话。
8. **日志安全**：控制器所有日志走 `logI/logW`（`runCatching` 包裹）。生产意义是编码线程上的日志异常不得冒泡回 libwebrtc；
   离线意义是纯 JVM 里 `FileLogger` 静态初始化失败（`android.os.Process.myPid()` 是 stub）不会把机制单测染红。
9. **测试确定性**：控制器单测用可注入时钟（`setTimeSourceForTest`）+ 排空屏障（`awaitIdleForTest`，`resetForTest` 内置同款屏障），
   不靠 `sleep` 猜时序；`resetForTest` **刻意不重置 timeSource**（否则会把注入的假时钟冲掉）。

---

## 4. 诊断键与真机复测判据

### 4.1 键与字段

| 键 | 级别 | 字段 | 何时落 |
| --- | --- | --- | --- |
| `encoder_fallback_probe` | INFO | `ok=1`、`impl`、`encode_p95_ms`、`out_fps`、`requested_fps`、`window_s`、`produced`、`no_output`、`bad_s`、`switches`、`reason`（`action=disabled/skip` 视分支） | **每个 1 s 窗口一条**（未切换时也证明策略在跑） |
| `encoder_fallback_decision` | INFO | `action=keep_self|switch_to_default`、`reason`、`encode_p95_ms`、`out_fps`、`requested_fps`、`window_s`、`bad_s`、`no_output_frames`、`impl`、`mode`、`fallback` | 动作/原因变化时，或任何一次 `switch_to_default` |
| `encoder_fallback_requested` | WARN | `mech=in_call_switch`、`reason`、`encode_p95_ms`、`out_fps`、`requested_fps`、`window_s` | 发出通话内切换请求时 |
| `encoder_fallback_switch_signal` | INFO | `trigger`（`bitrate`/`resolution`/`encoder_broken`）、`value`、`codec`、`mech` | selector 真正返回格式时 |
| `encoder_created` | INFO | `impl`、`codec`、`mech=fallback_default` | 工厂创建了默认实现（切换生效的**直接证据**） |
| `encoder_fallback_applied` | INFO/WARN | `impl`、`mech=in_call_switch|next_call`、`confirmed=0/1`、`reason`、可选 `waited_ms`/`pending_next_call` | 通话内确认（`confirmed=1`）／退化到下次通话（`confirmed=0`）／下次通话兑现（`confirmed=0`，创建时再有 `confirmed=1`） |
| 辅助 | INFO/WARN | `encoder_fallback_config`、`encoder_fallback_call_start`、`encoder_fallback_call_end`、`encoder_fallback_probe_start`、`encoder_fallback_current`、`encoder_fallback_switch_skipped`、`encoder_fallback_unavailable`、`encoder_fallback_error`；`engine_ready` 新增 `encoder_mode` / `encoder_fallback` | 配置/生命周期/防御路径 |

### 4.2 真机复测判据（看哪几个键、期望值）

| 场景 | 期望日志序列 | 判定 |
| --- | --- | --- |
| **健康**（自研够快） | 每 1 s `encoder_fallback_probe ok=1 reason=healthy switches=0 bad_s=0`；`encoder_fallback_decision action=keep_self reason=healthy window_s=10`；**不出现** `encoder_fallback_requested` | 全程 `impl=self`，无 `encoder_created mech=fallback_default` |
| **落后 ⇒ 通话内切换** | 持续 `bad_s≈8..10` → `encoder_fallback_decision action=switch_to_default reason=encode_p95_over_budget|out_fps_below_requested|encode_p95_and_out_fps` → `encoder_fallback_requested` → `encoder_fallback_switch_signal trigger=bitrate` → `encoder_created impl=<默认实现> mech=fallback_default` → `encoder_fallback_applied mech=in_call_switch confirmed=1`；此后 `encoder_fallback_probe action=skip reason=impl_default impl=default` | 通话**不断**（无 `hangup` / 无 `peerLeft`）、房间不变；`up_fps` 回升、`Drop Frame` 噪声下降；对端 `outbound-rtp.encoderImplementation`（统计）变为默认实现名 |
| **落后但原生未重建** | 同上到 `encoder_fallback_switch_signal`，但 5 s 内无 `encoder_created mech=fallback_default` → `encoder_fallback_applied impl=default mech=next_call confirmed=0 pending_next_call=1`（`waited_ms≥5000`） | 本次通话仍 `impl=self` 且**不中断**；下次通话第一帧前 `encoder_fallback_call_start … impl=default` + `encoder_fallback_applied mech=next_call` |
| **手动“默认”** | `encoder_fallback_config mode=DEFAULT`；下次通话 `encoder_fallback_call_start mode=DEFAULT impl=default` → `encoder_created mech=fallback_default` | 全程无自研 `encoder_init impl=SelfVp9Libvpx` |
| **手动“自研”** | `encoder_fallback_config mode=SELF`；`encoder_fallback_probe action=disabled reason=manual_self switches=0`，**永不**出现 `encoder_fallback_requested` | 用于对照自研极限（旧卡顿可能复现，属预期） |
| **关兜底** | `encoder_fallback_config fallback=false`；probe `action=disabled reason=fallback_disabled` | 只记日志不切换 |
| **一轮最多一次** | 一次 `switch_to_default` 后所有后续窗口 `reason=hysteresis`（30 s 内）或 `reason=already_switched`；`switches` 不超过 1 | 幂等 |

---

## 5. 与默认（硬件 / MediaCodec）路径的差异

| 维度 | 自研路径（默认使用） | 默认路径（降级后，`DefaultVideoEncoderFactory(egl,true,true)`） |
| --- | --- | --- |
| 编解码 | 只有 VP9（`profile-id=0`），编码走自有 JNI → libvpx（L1T3） | `hardwareVideoEncoderFactory`（MediaCodec，硬件优先）+ `softwareVideoEncoderFactory` 兜底（jar 实测 `DefaultVideoEncoderFactory` 持有这两个工厂） |
| 实际实现 | `SelfVp9Libvpx`（`VideoEncoder.getImplementationName()`） | 有 MediaCodec VP9 编码器 ⇒ `MediaCodecVideoEncoder`（`isHardwareEncoder()=true`）；**无**则落 SDK 软件 VP9（libvpx），此时“降级”只是换了 SDK 侧的软编实现与码控策略 |
| CPU/功耗 | libvpx 软编（t85 已做多线程 + row-mt：宿主 p50 15.33→9.34 ms；真机单帧 p50 11.15 / p95 18.19 / max 35.75 ms） | VP9 硬编时 CPU 显著更低；落到 SDK 软编时 CPU 与自研同量级（差异主要在码控/线程策略） |
| 码控/质量 | 自研：`setRateAllocation` 直通 native，`getScalingSettings()=OFF`（质量缩放交本项目策略） | SDK：`DefaultVideoEncoderFactory` 的默认码控 + quality scaler 行为（会做分辨率/帧率降级） |
| 关键帧/层 | 1 空间层 × 3 时域层（L1T3 冻结） | 由 SDK 决定（通常无 L1T3；`CodecSpecificInfo` 不同） |
| 协商 | 只有 VP9（`getSupportedCodecs()` 不变） | 同左 —— **降级不改 `getSupportedCodecs/getImplementations`**，SDP 仍是 VP9；只是本端实现换了 ⇒ 对端无需重新协商 |
| 学习点 | 有（本项目学习目标） | 无（§7.1 明确“禁止把 DefaultVideoEncoderFactory 作为主编码器”，仅 t87 兜底/`USE_DEFAULT_ENCODER` 对照时允许） |

风险提示：若目标机型**没有** VP9 硬件编码器，降级到默认路径仍可能是软编 —— 届时兜底改善的主要是
“换掉自研码控/线程策略带来的尾延迟”，而不是“变成零成本硬编”。真机复测必须用 `encoder_created impl=` 确认实际落到了哪一种。

---

## 6. 单测清单（新增 2 类 15 例）

### 6.1 `app/src/test/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackPolicyTest.kt`（10 例，纯函数）

| # | 用例（行号） | 断言要点 |
| --- | --- | --- |
| 1 | `healthyMetricsKeepSelf:57` | 10 个健康窗口 ⇒ `KEEP_SELF` / `reason=healthy` / `badSeconds=0` |
| 2 | `sustainedOverBudgetSwitchesToDefault:73` | 持续 p95 = 0.9×预算 ⇒ `SWITCH_TO_DEFAULT` / `reason=encode_p95_over_budget` / 诊断字段齐全 |
| 3 | `lowOutFpsAloneSwitchesToDefault:95` | 仅产出帧率不足（40% 帧）⇒ 切换 / `reason=out_fps_below_requested` |
| 4 | `warmupWindowKeepsSelf:109` | 9/10 窗口（全坏）⇒ `KEEP_SELF` / `reason=warmup` |
| 5 | **`noSecondSwitchWithinHysteresisWindow:123`** | 切换后 15 s / 29.999 s ⇒ `reason=hysteresis`；把次数上界放宽到 2 仍被滞回挡住；30.000 s 处才允许再切 |
| 6 | **`atMostOneSwitchPerCall:143`** | 已切 1 次 + 60 s 后的坏窗口 ⇒ `KEEP_SELF` / `reason=already_switched`（幂等） |
| 7 | `configMatchesAcceptanceThresholds:153` | 默认常量逐条对齐验收（10 s / 0.6 / 0.7 / 30 s / 1 次）；边界值 20 ms、21 fps 精确判坏/不坏 |
| 8 | `manualOverrideWins:169` | 三态覆盖优先级；脏配置回退 `AUTO`（不抛异常） |
| 9 | `p95UsesNinetyFifthPercentile:193` | p95 = `ceil(0.95n)-1`；不修改入参；空数组 = 0 |
| 10 | `noOutputFramesAreReportedInWindow:205` | 无产出帧数按窗口汇总并出现在 `no_output_frames` 字段 |

### 6.2 `app/src/test/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackControllerTest.kt`（5 例，机制端到端）

| # | 用例（行号） | 断言要点 |
| --- | --- | --- |
| 1 | **`badWindowsEventuallyRequestInCallSwitch:55`** | 10 个坏窗口 ⇒ 决策 = p95 超预算；决策只“请求”（`impl=self`）；`switchesThisCall=1`；`implForCreate()=DEFAULT`；`takeSwitchRequest()` **恰一次**为真；`onEncoderCreated(DEFAULT,"MediaCodecVideoEncoder")` ⇒ `impl=default`、`mech=in_call_switch`、pending=false |
| 2 | **`inCallSwitchTimeoutFallsBackToNextCall:75`** | 请求被取走但未确认 + 6 s ⇒ `pendingFallbackForNextCall=true`、`mech=next_call`、本次仍 `self`（不中断）；下次 `onCallStarted` ⇒ `implForCreate()=DEFAULT`、pending 清除 |
| 3 | `manualSelfOverrideSuppressesAutoSwitch:98` | 三态 `SELF`：12 个坏窗口 ⇒ `reason=manual_self`、`switches=0`、无切换请求、无 pending |
| 4 | `healthyWindowsNeverSwitch:110` | 15 个健康窗口 ⇒ `reason=healthy`、`switches=0`、无请求、无 pending |
| 5 | `pendingFlagIsHonoredOnNextCallStart:121` | `onCallEnded` **不丢** pending；下次通话兑现为默认实现（`mech=next_call`） |

---

## 7. 验证证据

### 7.1 新增/修改文件（sha256）

| 文件 | sha256（前 16） | 模式 |
| --- | --- | --- |
| `encoder/EncoderFallbackPolicy.kt`（新） | `bd4f43d6b22eae7d` | 600 |
| `encoder/EncoderFallbackController.kt`（新） | `8b16bb20179fa4e9` | 600 |
| `encoder/FallbackVideoEncoderSelector.kt`（新） | `491b537b141cc6e3` | 600 |
| `encoder/Vp9VideoEncoderFactory.kt` | `c6a8e1841ce31884` | 600 |
| `encoder/Vp9VideoEncoder.kt` | `513429c233d8e039` | 600 |
| `webrtc/WebRtcEngine.kt` | `8402aeeff3f536dc` | 600 |
| `config/AppConfig.kt` | `c03c2cfdc69a7540` | 600 |
| `diag/DiagnosticsScreen.kt` | `0707fe7cb31493e6` | 600 |
| `ui/call/CallViewModel.kt` | `065fa0be93c4cf1f` | 600 |
| `test/.../EncoderFallbackPolicyTest.kt`（新） | `1a573af5f208f3b6` | 600 |
| `test/.../EncoderFallbackControllerTest.kt`（新） | `45cc5107a67b2cae` | 600 |

### 7.2 离线全量（新代码）

```
$ bash /tmp/kcheck/t71.sh /tmp/kcheck/outT87 new      # 仓库树离线编译 + 全量单测（宿主机 kotlinc 2.0.21 + JUnit4）
=== compile main ===   MAIN_RC=0
=== compile tests ===  TEST_RC=0
=== run JUnit ===      classes: 22
JUnit version 4.13.2
..............................................................................................................................................
Time: 0.385
OK (206 tests)
```

- 基线（t83 之前）：20 类 / 191 例；本次 **22 类 / 206 例 / 0 failures**（新增 2 类 15 例，总数上升）。
- 稳定性：新增两类的 6 次独立重跑（`policy,controller` 与 `controller,policy` 两种顺序 ×3）全部 `OK (15 tests)`。

### 7.3 old-red 对照（回退判据 ⇒ 必红）

补丁只作用于**离线暂存树**（`/tmp/kcheck/t71-old/**`，仓库零写入），把「窗口累积 + 滞回 + 一轮一次上界」回退成
“单窗口（甚至不足一窗口）即切”的朴素语义：

```
$ bash /tmp/kcheck/t87old.sh /tmp/kcheck/outT87old old
PATCHED(pre-t87 naive decision): /tmp/kcheck/t71-old/main/.../EncoderFallbackPolicy.kt
MAIN_RC=0  TEST_RC=0
classes: 22
Tests run: 206,  Failures: 6
FAILURES!!!
   1) noSecondSwitchWithinHysteresisWindow(EncoderFallbackPolicyTest)   expected:<KEEP_SELF> but was:<SWITCH_TO_DEFAULT>
   2) atMostOneSwitchPerCall(EncoderFallbackPolicyTest)                 expected:<KEEP_SELF> but was:<SWITCH_TO_DEFAULT>
   3) warmupWindowKeepsSelf(EncoderFallbackPolicyTest)                  expected:<KEEP_SELF> but was:<SWITCH_TO_DEFAULT>
   4) pendingFlagIsHonoredOnNextCallStart(EncoderFallbackControllerTest)
   5) badWindowsEventuallyRequestInCallSwitch(EncoderFallbackControllerTest)  expected:<1> but was:<10>
   6) inCallSwitchTimeoutFallsBackToNextCall(EncoderFallbackControllerTest)   "5 s 未确认 ⇒ 标记下次通话生效"
```

其中第 5 条信息量最大：回退后**每个窗口都切**（`switchesThisCall` 期望 1、实得 10），
正是 t87 要求的“滞回 + 一轮最多一次”要防的抖动行为。

### 7.4 契约 verify 命令

见任务完成汇报（`grep -rn 'EncoderFallbackPolicy\|encoder_fallback_applied\|encoder_fallback_decision\|pendingFallbackForNextCall' …`
与 `ls -l reports/48-encoder-fallback.md && git status --porcelain -- app/src/main/cpp signaling doc`）。

---

## 8. 未验证项 / 风险（**必须在真机复测**）

1. **原生是否真的会因 selector 返回非空格式而重建 send stream**：静态证据只有 `.so` 内的 JNI 注册字符串与
   `RecreateWebRtcStream (send) because of SetEncoderSelector` 日志串；容器内无设备、无 JDK builder，**无法执行**。
   若实际不重建，退化路径（`mech=next_call`）保证下一次通话一定生效，但这需要真机确认“退化确实发生且日志是 `mech=next_call`”。
2. **`onAvailableBitrate` 的实际调用频率**未知：若它在通话中很少被调用，通话内切换就依赖 `onResolutionChange`/`onEncoderBroken`，
   或退化到下次通话。复测判据：出现 `encoder_fallback_requested` 后 5 s 内是否出现 `encoder_fallback_switch_signal`。
3. **目标机型是否有 VP9 MediaCodec 硬编**未知 ⇒ 无法断言“降级一定更省 CPU”（见 §5 风险提示）。
4. **端到端体感**（无卡顿、不中断、不丢房间、对端画面恢复）未验证：容器内无设备/无 coturn/无对端。
5. **协商不变性**：`getSupportedCodecs()` 未纳入 `defaultEncoderFactory`（代码级保证），但“SDP 仍只有 VP9、payload 不变”需真机 SDP 侧确认。
6. **多流场景**：`VideoEncoderFactory.getEncoderSelector()` 的 selector 不带流标识（libwebrtc 头文件明确注明），
   本项目是 1:1 单视频流，暂不适用多流；若将来多流，应改用 `RtpSenderInterface::SetEncoderSelector`（Java 侧本版未暴露）。
7. **`Vp9VideoEncoder` 新增埋点的开销**：每帧多一次 `synchronized` 计数与非空判定（无分配、无 I/O），
   预期 <1 µs；未做真机微基准对账（t85 的 p50/p95 数据可用于对照回归）。
8. 本轮**未改** `app/src/main/cpp/**`（不改 JNI）、未跑宿主机 Gradle、未发布、未 commit —— 因此 APK 侧行为需下一次构建验证。

---

## 9. 与既有契约/文档的一致性

- doc/14 §5.4「不得在编码线程做 I/O/长锁」：探针只计数 + 每秒一次 `execute` 投递；排序/日志全在 `enc-fallback` 线程。
- doc/14 §7.1「禁止把 `DefaultVideoEncoderFactory` 作为主编码器」：默认实现**仅在降级/三态“默认”时**用于创建编码器，
  且不进入 `getSupportedCodecs()`；`USE_DEFAULT_ENCODER` 对照开关的既有语义不变。
- doc/14 §6.6 `impl` 名 `SelfVp9Libvpx`：自研路径不变；降级后日志中的 `impl` 为默认实现的真实类名（诊断需要）。
- 既有诊断键未被改动或删除（`encoder_created`/`encoder_init`/`setrates`/`encoder_slow_frame` 等保持原样，新增字段为增量）。

---

## 10. 交付时戳与构建窗口交接（给下一次宿主机构建）

| 项 | 值 |
| --- | --- |
| **app/src 最后写入时刻（T1）** | **2026-09-16 20:00:48（+0800）** —— `app/src/test/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackControllerTest.kt` |
| 其中主源码最后一次写入 | 2026-09-16 20:00:41（`encoder/EncoderFallbackController.kt`） |
| 本报告写入时刻 | 2026-09-16 20:05:46（`reports/48-encoder-fallback.md`，644） |
| 本次是否跑宿主机 Gradle | **否**（只有离线 `kotlinc` + JUnit，输出在 `/tmp/kcheck/**`） |
| 本次是否发布 | **否** |
| 本次是否 `git commit` | **否** |
| `git status --porcelain -- app/src/main/cpp signaling doc` | **空**（未越界） |
| 变更清单（git） | `M`：`config/AppConfig.kt`、`diag/DiagnosticsScreen.kt`、`encoder/Vp9VideoEncoder.kt`、`encoder/Vp9VideoEncoderFactory.kt`、`ui/call/CallViewModel.kt`、`webrtc/WebRtcEngine.kt`；`??`：`encoder/EncoderFallbackController.kt`、`encoder/EncoderFallbackPolicy.kt`、`encoder/FallbackVideoEncoderSelector.kt`、`app/src/test/kotlin/com/example/webrtcdemo/encoder/`、`reports/48-encoder-fallback.md` |

**给下一次构建（t88+）的提示**：t87 不含任何 `cpp/**`、`third_party/**`、`AndroidManifest.xml`、`res/**`、Gradle 配置改动，
因此**不需要重新编译 native**；新增 2 个 Kotlin 主源码文件 + 2 个测试文件，构建后可从 `engine_ready` 的
`encoder_mode` / `encoder_fallback` 字段确认注入生效。

