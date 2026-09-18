> **中文（默认）** · [English](../03-app-architecture.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 03 — App 架构

> Status: draft · Owner: writer-app · Task: t3
> Evidence base: `reports/27-encoder-direction-perf.md`, `reports/34-turn-tcp-fallback.md`, `reports/35-room-grace.md`, `reports/36-call-survivability.md`, `reports/39-reconnect-budget-ice-restart.md`, `reports/40-glare-ice-restart-fix.md`, `reports/44-ice-watchdog-false-failure.md`, `reports/47-vp9-encode-perf.md`, `reports/48-encoder-fallback.md`, `reports/49-bitrate-allocation-collapse.md`, `reports/50-quality-scaling-and-render-fps.md`, `reports/51-frame-dropper-and-trusted-rc.md`; seven device captures under the container path pattern /data/dsh/home/workspace/tmp/n1…n7/x/.
> Doc standard: `doc/design/SPEC.md`

## 1. 范围

本文档描述演示应用的 Android 侧：它的分层、跨越 Kotlin/native 边界的线程与回调模型、含 `session=sN`
世代方案的会话生命周期、失败与恢复策略、诊断页暴露的配置开关，以及日志词表的入口。

它不描述信令服务内部或线上协议（见信令服务与协议文档）、构建与发布流水线、仅主机服务，或历史决策
记录。下文每条架构陈述都锚定到当前 HEAD 的源码位置；从历史文档或 `reports/**` 抄来的行号按构造就是
过期的，绝不使用。

## 2. 分层图

应用是严格向下的栈。每层只调用它下面的一层，native 边界在每个方向上只在一处跨越。

| 层 | 职责 | 锚点 |
|---|---|---|
| 进程入口 | 先初始化日志设施，再加载 native 库 | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31` |
| Compose UI | 屏幕、导航、状态面板 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:91` |
| 连接状态机 | 阶段/原因/存活模型，面向 UI 的连接状态 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt:26` |
| 通话编排 | 会话槽、信令/房间动作、恢复判定、UI 状态 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:52` |
| 对端连接会话 | 一个 `PeerConnection`、SDP/ICE 处理、看门狗、统计采样 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` |
| 引擎 | 进程级 `PeerConnectionFactory`、`EglBase`、native 日志汇聚 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:43` |
| 视频编码 | 自定义 VP9 编码器及其兜底选择器 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59` |
| 视频采集/渲染 | 摄像头采集器、基于 `SurfaceViewRenderer` 的渲染器池 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:44` |
| 信令传输 | WebSocket 客户端、重连/重加入预算、断连原因 | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:127` |
| Native/JNI | VP9 编码、日志汇聚、NAT 探测、回调 | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:1` |

UI 从不直接与 `CallSession` 对话：它观察编排层发布的 `StateFlow`
（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:57`），并把用户意图作为同一对
象上的方法发回。传输层从不触碰 UI：它通过回调接口汇报结果，这些接口的实现在编排层与会话层
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:1`,
`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:1`）。

参考：`app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:91`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt:26`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:52`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:43`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:44`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:127`,
`app/src/main/cpp/jni/vp9_encoder_jni.cpp:1`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:1`,
`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:1`

## 3. 线程模型与回调边界

应用自己拥有的是一小组具名线程。其余一切都跑在 Android 主线程或 libwebrtc 自己的 native 线程里。

| 线程 | 所有者 | 用途 |
|---|---|---|
| main / UI | Android | Compose 重组、`StateFlow` 收集 |
| `signaling-timer` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:180` | ping 节奏、重加入退避 |
| `ice-watchdog` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1113` | ICE 连通性截止检查 |
| `stats-sampler` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1394` | 周期性 `getStats()` 采样 |
| `enc-fallback` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:151` | 通话中编码器切换决策 |
| log writer | `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:101` | 日志文件的单写者追加 |
| export I/O | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:203` | 在主线程之外创建 zip |

共享可变状态只由两种机制保护——对每个会话或每个编码器句柄只写一次的字段使用 `@Volatile`，以及对
「这个会话是否已就绪」这类复合判定使用显式监视器：

* `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:122` 与
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:181` — volatile 会话标志。
* `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:368` 与
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:812` — 就绪/拆除监视器
  （`readyLock`）。
* `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:629` 与
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:748` — 待处理远端候选队列。
* `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:97` — native 编码器句柄，由
  `initEncode`/`release` 写入，并由 `setRateAllocation` 从不同线程读取。

UI 状态不是共享可变状态：它作为不可变值经 `StateFlow` 跨线程传递
（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:57`）。因此，来自 libwebrtc
native 线程的回调绝不允许直接改动 UI 状态；它们先汇入会话，再对外发布。

参考：`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:180`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:122`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:181`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:368`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:812`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:629`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:748`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1113`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1394`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:97`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:151`,
`app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:101`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:203`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:57`

## 4. 会话生命周期与世代语义

启动顺序是固定的，并且可在日志中观察。日志设施先于其他一切初始化
（`app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:37`），native 库按需懒加载
（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:36`），引擎只执行一次
libwebrtc 全局初始化：

1. native 日志汇聚与库加载 — `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31`
2. `PeerConnectionFactory` 全局初始化 — `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:124`
3. `EglBase` 单例 — `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:133`
4. 注入编码器的工厂 — `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:135`
5. 就绪宣告 — `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176`

每次通话都会得到一个新的 `CallSession`，且每个会话携带一个单调递增的整数 id
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`）。会话发出的每一行日志都
携带两个字段：作为世代标识的 `session=sN`，以及一个按会话计的事件计数器，二者都在字段映射器中集中
注入（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:173`）。这正是让设备抓包在
重连前后仍可读的原因：同一次通话可能产生多个世代，而来自世代 *N-1* 的过期计时器或迟到回调是可检测
的，因为它的 `session=` 值与当前值不同。

编排层把当前世代放在一个槽对象里，而不是散落在各个字段中
（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1938`），导航到新通话会开启
新世代（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1982`）。一个世代内的
对端连接生命周期是：`pc_starting`
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`）→ offer/answer 交换 →
ICE → `close()`（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:735`），后者也会
停止该会话拥有的计时器。

真机上观测到的启动顺序（n1 抓包，`app.log` 第 3、4、5 与 49 行）：

```text
2026-09-16T03:30:57.417Z INFO    kotlin main      [25397/25397] app_create level=DEBUG log_dir=/data/user/0/com.example.webrtcdemo/files/logs max_bytes=2097152 max_files=3
2026-09-16T03:30:57.430Z INFO    kotlin main      [25397/25397] native_lib_loaded lib=webrtcdemo_native
2026-09-16T03:30:57.431Z INFO    kotlin main      [25397/25397] native_log_init base=native dir=/data/user/0/com.example.webrtcdemo/files/logs level=DEBUG max_bytes=2097152 max_files=3
2026-09-16T03:34:09.322Z INFO    kotlin pc        [2098/2098] engine_ready impl=SelfVp9Libvpx use_default_encoder=false
```

参考：`app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31`,
`app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:37`,
`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:36`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:124`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:133`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:135`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:173`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:735`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1938`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1982`

## 5. 失败与恢复策略

设计规则是：通话只因显式用户操作或致命传输判定而结束；其余一切都会变成可恢复的 UI 状态。

**断连分类。** 传输层把每次断连归类为一个 `DisconnectCause`，该类型同时声明它是否可存活
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:790`）。致命原因会结束
通话（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:792`）；对端离开是可
存活的，把决定权留给编排层
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:795`）；重连期间房间被回
收，则进入可恢复的「房间丢失」状态
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:798`）。

**重连预算。** 存活判定使用 15 s 的 ping 节奏、5 s 的 pong 超时，并容忍连续四次丢失后才认为
socket 已死（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`）。socket 重连刻意复用重
加入退避，而不是另定一个平行常量：基数 1 s、上限 8 s、最多 10 次尝试
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`），源码把总预算表述为
约 63 s——低于服务端宽限期
（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:122`）。

**中性等待状态。** 在对端到达之前，UI 显示的是不会失败的等待阶段，而不是重试计数器
（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt:33`），并且该阶段以与抓包
中相同的字段名发布（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:465`）。
状态发布器发出 `ui_conn_state`，含阶段、原因、存活证据与世代
（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475`）。

**对端离开。** 离开的对端是可存活的；是否保留通话，取决于该通话是否曾经连接成功、以及媒体是否仍存
活（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:912`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:935`）。媒体存活使用 3 s 窗口
（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:55`）。

**房间被回收。** 重连期间出现房间丢失结果时，保留通话并提供显式的重建入口
（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1062`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:691`），而成功的重建会以
`room_recreated` 宣告（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:837`）。

**ICE 看门狗与重启。** 单个看门狗同时覆盖初始连接与之后的掉线：30 s 告警、45 s 失败，最多两次重
启，且各层之间有一个 15 s 的推迟步长
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:94`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:97`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:103`）。看门狗在会话启动时武装
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1111`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1125`），在中继候选出现时重新武装
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1336`），在连通性得到证实后停止
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1376`），而重启通过单一入口点请
求（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`）。

**防冲突分工。** 重加入之后，只有本来就已在房间内的一侧会重新协商；重连的一侧只做应答，并武装一个
8 s 的兜底提议（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:142`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:147`）。其理由记录在实现旁
边，包括双方同时提议的后果
（`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:875`）。

参考：`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:122`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:790`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:792`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:795`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:798`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt:33`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:465`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:691`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:837`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:875`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:912`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:935`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1062`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:55`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:142`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:147`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:94`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:97`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:103`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1111`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1125`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1336`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1376`

## 6. 配置与诊断开关

所有应用开关都位于一个由偏好设置支撑的对象中
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:25`）：信令 URL 覆盖
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:39`）、ICE 策略
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:42`）、默认编码器开关
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:45`）、低分辨率开关
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:48`）、编码器覆盖
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:51`）与编码器兜底开关
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:54`）。

编码器覆盖是一个三态开关——自动、强制自建、强制平台默认
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:57`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:60`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:63`）——而 ICE 策略是「全部候选」与
「仅中继」之间的两态开关
（`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:66`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:69`）。诊断屏渲染这些开关以及各层日
志级别（`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:65`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:120`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:171`）。

TURN over TCP 是构建期默认值，可以关闭；URL 推导是纯函数，因此可以单元测试：默认开启
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:52`），推导出的 TCP URL 由信
令下发的 TURN URL 生成（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:66`）。
实际生效的传输决策在 `pc_starting` 行上可见，该行报告 ICE server 种类、强制中继 flag，以及是否加
入了 TCP 兜底（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`）。真机观测
（n1 抓包，`app.log` 第 51 行）：

```text
2026-09-16T03:34:09.325Z INFO    kotlin pc        [2098/2098] pc_starting evt=1 force_relay=false ice_servers=stun+turn+tcp session=s1 turn_tcp=true
```

帧丢弃器 field trial 在引擎启动时应用，并宣告一次
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164`）。真机观测（n6 抓包，
`app.2.log` 第 6170 行）：

```text
2026-09-16T15:34:23.898Z INFO    kotlin pc        [20650/20650] field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/
```

参考：`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:25`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:39`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:42`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:45`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:48`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:51`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:54`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:57`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:60`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:63`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:66`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:69`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:65`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:120`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:171`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:52`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:66`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164`

## 7. 诊断与日志词表入口

Kotlin 代码只有一个日志入口点（`app/src/main/kotlin/com/example/webrtcdemo/log/Log.kt:28`）。业务代
码不得直接调用平台日志器；libwebrtc 桥是唯一的转发例外
（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:32`）。三条通道共享一个写
线程与一个目录：应用事件、经回调桥转发的 native 事件，以及由上面那座桥转发的 webrtc 事件。

权威的事件词表是机器生成的，不是手写的：见 `doc/design/_generated/log-events.md` 中生成的日志事件
表，它列出每个 Kotlin 事件键及其定义源位置与字段键、每个 native 事件键，以及一份排序索引。文档引
用事件名时，必须取自该表，这样该键才能被证明由当前代码发出。

常被引用的锚点，全部取自生成的表：

| 事件 | 发出位置 | 含义 |
|---|---|---|
| `app_create` | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:40` | 日志设施就绪，第一行持久日志 |
| `engine_ready` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176` | 工厂与 `EglBase` 就绪 |
| `pc_starting` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306` | 会话已创建，传输决策已定 |
| `call_init` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:584` | 编排层进入一次通话 |
| `ui_conn_state` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475` | 阶段/原因/存活发布 |
| `stats_sample` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/StatsMapper.kt:87` | 周期性统计采样 |
| `setrates` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:300` | 已应用目标码率 |
| `encoder_created` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:61` | 已选定编码器实例 |
| `export_zip` | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:179` | 已写出诊断包 |

参考：`app/src/main/kotlin/com/example/webrtcdemo/log/Log.kt:28`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:32`,
`app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:40`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:584`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/StatsMapper.kt:87`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:300`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:61`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:179`

## 8. 证据索引

| 论断 | 引用 | 验证工件 |
|---|---|---|
| 启动顺序是日志优先，然后 native 加载，然后引擎 | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31` | n1 `app.log` 第 3–5 行 |
| 引擎全局只初始化一次 libwebrtc | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:99` | n1 `app.log` 第 49 行 |
| 每个会话都有世代与事件计数器 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:173` | n1 `app.log` 第 95 行 |
| 重连预算约 63 s，低于服务端宽限窗口 | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:122` | `reports/39-reconnect-budget-ice-restart.md` |
| 重加入重协商是单侧的，8 s 兜底 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:147` | `reports/40-glare-ice-restart-fix.md` |
| ICE 看门狗层级 30 s / 45 s，重启 ≤2 次 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:94` | `reports/44-ice-watchdog-false-failure.md` |
| 房间丢失时保留通话并提供重建 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1062` | n6 `app.2.log` 第 13492 行 |
| TURN over TCP 兜底默认开启 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:52` | `reports/34-turn-tcp-fallback.md` |
| 帧丢弃器被 field trial 禁用 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164` | n6 `app.2.log` 第 6170 行 |
| 日志键是机器生成的，带源码锚点 | `doc/design/_generated/log-events.md:1` | 生成的表本身 |

## 9. 待办项

* **门禁通过配方。** `scripts/doc-verify.sh` 是交付门禁，且变化频繁。本文档使用的稳定配方：把仓库源
  码以裸 `path:LINE` 形式引用，锚定当前 HEAD；主机绝对路径写在显式 `HOST:` 作用域下，并在同一句中
  给出仓库内证据；工作区与容器路径裸写（`WORKSPACE:`/`ARTIFACT:` 之类的标记已退役，现在会失败）；
  命令放在不带反引号的围栏块中；未写成的文档以纯文本引用，并加 `not yet written at authoring time`。
  对脚本或本文档做任何改动后，重跑 `bash scripts/doc-verify.sh --only docs/03-app-architecture.md docs/06-flows.md`。
* **ICE 重启在抓取到的会话中从未被触发过。** 七次设备抓包中重启计数器处处为零；因此重启路径是依据
  代码以及看门狗/防冲突日志证据记录的，而不是依据一次抓取到的重启。这是覆盖度限制，不是代码论断。
* **渲染器侧的帧指标没有源码侧键。** 渲染器与接收器丢帧计数器、渲染器日志 tag 之类的 token 只存在
  于二进制产物与 `reports/**` 中，因此按报告小节引用，而不是按 `file:LINE` 引用。
* **SPEC 版本变动。** 本次任务期间文档标准从 v1.3.0 移到 v1.4.0；这里引用的引用规则都是小节级引
  用，以便后续修订不会使它们失效。
