> **中文（默认）** · [English](../01-requirements.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 01 — 需求

> Status: draft · Owner: architect · Task: t1
> Evidence base: `reports/52-release-closure.md`, `reports/99-final-report.md` §15, `reports/35-room-grace.md`,
> `reports/42-delivery-verification.md`
> Doc standard: `doc/design/SPEC.md`

---

## 1. 范围

本文档说明已交付系统必须做到什么、每条需求在哪里实现，以及存在哪些证据。所谓「已交付」，指以 APK
`59c75778fb457e8c6d4c7955b349dfa4fde784fa616e852419e3b007e1e41d07`（33 472 645 B）为锚点、由提交
`4130ddc` 构建的发布版本（`reports/52-release-closure.md` §1）。

范围之外：WebRTC 内部行为（ICE/DTLS/GCC/NACK 是拿来用的，不重新实现）、`doc/archive/` 下的历史设计
文档，以及任何已登记为契约勘误的内容（汇总见 §7）。

状态词由 `doc/design/SPEC.md` §6（E4）固定：`implemented`、`partially implemented`、
`known limitation`、`unverified`。

引用键：下文每个代码指针都是 `file:` 形式的引用（`file:LINE`），因此「需求 → 实现 → 证据」这条链路可
由 `scripts/doc-verify.sh` 机器校验。

## 2. 功能需求

| ID | 需求 | 状态 |
|---|---|---|
| FR-1 | 主叫方可以创建一个 1:1 房间，并获得一个短房间号与 ICE 凭据 | 已实现（implemented） |
| FR-2 | 第二个对端可以用该房间号加入；房间最多容纳 2 个对端 | 已实现（implemented） |
| FR-3 | 两个对端经信令通道交换 SDP 与 ICE 候选，并建立音频+视频 | 已实现（implemented） |
| FR-4 | 短暂的信令断连（短于宽限期）不得结束通话；对端重连并收回自己的席位 | 已实现（implemented） |
| FR-5 | 远端对端离开（或其宽限期到期）时，本端回到等待状态，而不是挂断 | 已实现（implemented） |
| FR-6 | 房间不存在时（`ROOM_NOT_FOUND`/`ROOM_EXPIRED`），通话保持可恢复，而不是被拆除 | 已实现（implemented） |
| FR-7 | 用户可以从诊断页强制媒体走 TURN | 已实现（implemented） |
| FR-8 | 用户可以从 App 导出诊断（日志 zip） | 已实现（implemented） |

### FR-1 — 创建房间

* **陈述。** 发起方通过 WebSocket 发送 `create`；服务器以 `created` 应答，其中含 6 位房间号以及它希望
  客户端使用的 STUN/TURN 配置。
* **实现。** 线上格式 `signaling/protocol/message.go:46`（`CreateRequest`）与
  `signaling/protocol/message.go:51`（`CreatedResponse`）；房间号生成
  `signaling/util/roomid.go`（`Generate`）；房间创建 `signaling/room/manager.go:117`；客户端请求
  `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:858` 区域（`createRoom`）。
  房间号使用无易混淆字符的字母表 `ABCDEFGHJKMNPQRSTUVWXYZ23456789`
  （`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:172`）。
* **证据。** `signaling/room/manager_test.go`；`signaling/server/e2e_test.go`（端到端创建/加入）；
  `reports/99-final-report.md` §15.4 中的实况回放（`create` → `roomId=SA6ABK`）。
* **状态。** 已实现（implemented）。

### FR-2 — 加入房间，最多两个对端

* **陈述。** 第二个对端发送 `join{roomId}`；服务器以 `joined` 应答，给出分配给它的 `peerId`
  （`peer-001`/`peer-002`），房间未知或已满时则返回 `error`。
* **实现。** `JoinRequest` `signaling/protocol/message.go:61`，`JoinedResponse`
  `signaling/protocol/message.go:67`；容量常量 `signaling/room/room.go:13`（`RoomMaxPeers = 2`）；
  加入路径 `signaling/room/manager.go:161`；席位分配 `signaling/room/room.go:102`
  （`peerIDAt`）；错误码 `signaling/protocol/errors.go:6-11`。
* **证据。** `signaling/room/manager_test.go`；`signaling/server/e2e_test.go`；
  `reports/99-final-report.md` §15.4（`join peerId=peer-002`，在线对端收到 `peerJoined`）。
* **状态。** 已实现（implemented）。

### FR-3 — 双向音频与视频

* **陈述。** 两个对端协商一条音频轨与一条视频轨，并交换媒体直到挂断。
* **实现。** 会话启动与轨道接线
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:277`（`start`）；提议创建
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:402`；远端提议
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:454`；远端应答
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:542`；远端 ICE
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:588`；编解码器偏好 VP9
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1427`；自定义编码器工厂
  `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt`；
  渲染器池 `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt`。
* **证据。** `reports/52-release-closure.md` §4（真机通话：`encoder_perf in_fps` p50 30，
  `eglrenderer Dropped=0`，上行 ≈1.0–1.16 Mbps）；单元测试
  `app/src/test/kotlin/com/example/webrtcdemo/webrtc/SessionLifecycleTest.kt`。
  该路径内部的 JNI 边界由 `doc/design/_generated/jni-contract.md` 做契约校验，该文件由 Kotlin
  `external fun` 声明（`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/**`）、
  `JNINativeMethod` 表（`app/src/main/cpp/jni/nat_detector_jni.cpp:49`、
  `app/src/main/cpp/jni/native_log_jni.cpp:71`、`app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` — 15 条）
  以及 `app/src/main/cpp/jni/*.h` 中的 C 原型生成。绑定方式是 `JNI_OnLoad` + `RegisterNatives`
  （`app/src/main/cpp/jni/jni_bridge.h:9`），因此没有 `Java_*` 导出符号，也没有项目生成的
  `*_jni.h`；这两种缺失都是预期的（`doc/design/SPEC.md` §2.1）。
* **状态。** 已实现（implemented）。

### FR-4 — 经受短暂的信令断连

* **陈述。** 若 WebSocket 掉线且客户端在宽限期内重连，通话继续：房间与席位都保留，不发送 `peerLeft`，
  对端收回其 `peerId`，并重新建立媒体（必要时做 ICE 重启），用户无需挂断。
* **实现。** 服务端宽限期默认值 `signaling/config/config.go:54`（`DefaultRoomGrace = 90 s`），
  由 `-room-grace` flag 接线 `signaling/main.go:45`；离线标记
  `signaling/room/manager.go:225`（`MarkOffline`），席位挂起状态 `signaling/room/room.go:113`
  （`MarkPending`），宽限期到期 `signaling/room/manager.go:258`（`expireGrace`），接管
  `signaling/room/room.go:66`（`AddPeer` 返回 `takenOver`）；客户端 pong 容忍度
  `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`
  （`PONG_MISS_TOLERANCE = 4`），重连预算
  `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`
  （`MAX_REJOIN_ATTEMPTS = 10`），退避
  `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:156`（`rejoinDelayMs`）；
  ICE 重启判定 `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:127`
  （`shouldRestartIceOnRejoin`）。
* **证据。** `reports/99-final-report.md` §15.4（硬关闭 socket：在线对端在 12 s 内看到 `peerLeft` 计数
  为 0；同身份重连收到 `joined`，`peerId` 相同）；预算测试
  `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt:50`（63 000 ms）与
  `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt:52`（< 75 000 ms）；
  `reports/35-room-grace.md`、`reports/39-reconnect-budget-ice-restart.md`。
* **状态。** 已实现（implemented），但有一处未验证（unverified）的余量：30–90 s 断网的真机行为与 8 s 提议
  兜底的触发情况没有实测（`reports/52-release-closure.md` §5 第 6 项）。

### FR-5 — 远端对端离开 → 等待状态

* **陈述。** 远端对端离开后（显式 `leave`，或其宽限期结束），本地客户端必须保留通话页、停止重试计时
  并回到等待状态，以便这对对端可以再次通话。
* **实现。** 对端离开判定 `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:76`
  （`peerLeftAction`，当该世代曾经连接成功时保留通话）；等待状态
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:448`（阶段 `waiting_peer`，无计
  时器、无重试提示）；计时器重置
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:903`（`resetRetryClock("peer_left")`）；
  保留通话分支 `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:914`
  （`peer_left action=keep_call`）；等待状态不按重试/失败条件处理
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:460`。
* **证据。** `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt`；
  `reports/33-waiting-peer-no-retry.md`；`reports/36-call-survivability.md`。
* **状态。** 已实现（implemented）。

### FR-6 — 房间不存在 → 可恢复状态

* **陈述。** `ROOM_NOT_FOUND`（以及 `ROOM_EXPIRED`）不得自动结束通话：通话中或媒体仍在流动时，客户
  端留在通话页，并提供显式的「重建房间」入口。
* **实现。** 判定 `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:88`
  （`roomLostAction`）；保留通话分支带 `room_not_found action=keep_call` 日志
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1052-1064`；信号丢失判定
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:105`（`signalLostAction`）
  及其分支 `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:757-772`。
* **证据。** `app/src/test/kotlin/com/example/webrtcdemo/signaling/SignalingErrorPolicyTest.kt`；
  `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt`；
  `reports/52-release-closure.md` §4 将 `room_not_found_action=keep_call` 列为已确认的设计行为。
* **状态。** 已实现（implemented）。

### FR-7 — 强制中继（诊断开关）

* **陈述。** 用户可以强制所有媒体走 TURN；该设置会持久化，并应用于每个新会话。
* **实现。** 策略常量 `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:69`
  （`ICE_POLICY_RELAY`）与 getter `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:162`
  （`forceRelay`）；映射为 `PeerConnection.IceTransportsType.RELAY` 于
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:156`；启动会话时应用
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:561`；ICE 重启时重新应用
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1294-1295`。
* **证据。** `app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt`；
  `reports/30-ice-relay-robustness.md`；`reports/52-release-closure.md` §4（观测到通话走 `RELAY`）。
* **状态。** 已实现（implemented）。

### FR-8 — 诊断导出

* **陈述。** 用户可以把 App 日志导出为 zip 并分享；导出内容包含设备信息与会话摘要，最多保留 3 个归档。
* **实现。** `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:139`
  （`exportBlocking`）；zip 名前缀 `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:60`
  （`ZIP_PREFIX`），设备信息条目 `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:66`，
  最多保留数 `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:72`（`MAX_EXPORTS = 3`），
  旧导出清理 `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:248`；`FileProvider`
  authority `app/src/main/AndroidManifest.xml:67`。
* **证据。** `reports/07-native-dev.md` §15（日志管线与导出）；`reports/52-release-closure.md`
  §5（日志证据是通过这条路径收集的）。没有单元测试端到端覆盖 zip 内容。
* **状态。** 已实现（implemented）；归档*内容*为 `unverified`。

## 3. 非功能需求

| ID | 需求 | 状态 |
|---|---|---|
| NFR-1 | 在 640x360 下编码时延 p95 保持在 33 ms 帧预算之内 | 已实现（implemented） |
| NFR-2 | 帧率跟随所请求的 30 fps；编码器绝不悄悄把它减半 | 已实现（implemented） |
| NFR-3 | 时延/RTT 在中继路径上保持在观测到的 54–217 ms 区间 | 已知限制（known limitation） |
| NFR-4 | 通话在长时间运行下稳定，且宽限期语义可测试 | 部分实现（partially implemented） |
| NFR-5 | 每种失败都能从导出的日志中定位 | 已实现（implemented） |
| NFR-6 | TURN 暴露面是被记录并接受的风险 | 已知限制（known limitation） |
| NFR-7 | 工具链与固定件是可复现的锚点 | 已实现（implemented） |
| NFR-8 | 发布工件由哈希标识且可回滚 | 已实现（implemented） |

### NFR-1 — 编码时延

* **陈述。** 在 30 fps、640x360 下，`encode_ms_p95` 必须远低于 33 ms 预算。
* **实现。** 线程数与 row-mt 调优 `app/src/main/cpp/encoder/vp9_encoder.cpp`；
  速率策略 `app/src/main/cpp/encoder/encoder_rate_policy.h`；码率地板表
  `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9BitrateLimits.kt`。
* **证据。** `reports/52-release-closure.md` §4：`encode_ms_p95` p50 9.8 ms、p90 15.2 ms、max 33.5 ms；
  `reports/47-vp9-encode-perf.md`（主机 A/B p50 15.33 → 9.34 ms，p95 19.10 → 11.76 ms，输出字节相同）。
* **状态。** 已实现（implemented）。

### NFR-2 — 帧率

* **陈述。** 在 30 fps 源下，编码器必须输出约 30 fps；收到的帧不得被本地 FrameDropper 丢弃。
* **实现。** Field trial `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrial.kt`；
  质量降级与逐帧 direct-buffer 尺寸设定在
  `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt`。
* **证据。** `reports/52-release-closure.md` §4（`DroppedFrames` 在 webrtc 日志中统计，帧丢弃 field
  trial `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/` 出现在 App 日志中；
  `encoder_perf in_fps` p50 30）；`reports/51-frame-dropper-and-trusted-rc.md`；
  `app/src/test/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrialTest.kt`。
* **状态。** 已实现（implemented）。

### NFR-3 — 时延与 RTT

* **陈述。** RTT 应低到足以支撑对话式视频。
* **证据。** `reports/52-release-closure.md` §4：n6 RTT 54–65 ms、丢包 0；n7 RTT 128–217 ms，丢包最高
  11.8%。这一离散度来自网络侧，不是缺陷。
* **状态。** 已知限制（known limitation）：两个对端都在对称 NAT 之后，因此媒体始终走中继路径
  （`reports/52-release-closure.md` §5 第 1 项）。没有 P2P 对比数据。

### NFR-4 — 稳定性与宽限期语义

* **陈述。** 宽限期必须大于客户端重连预算，且房间/席位状态机必须确定、幂等。
* **实现。** `DefaultRoomGrace = 90 s`（`signaling/config/config.go:54`），告警阈值
  `signaling/config/config.go:57`（`MinRecommendedRoomGrace`）；幂等到期
  `signaling/room/manager.go:258`（`expireGrace`）与 `signaling/room/room.go:140`（`ExpirePending`）；
  部署固定该值 `deploy/signaling.service:28`（`-room-grace 90s`）。
* **证据。** `signaling/room/grace_test.go`、`signaling/server/grace_test.go`；
  `reports/35-room-grace.md`、`reports/43-deploy-unit-consistency.md`；
  实况 `/healthz` 读数 `roomGraceSec = 90`（`reports/99-final-report.md` §15.4）。
* **状态。** 部分实现（partially implemented）：超过约 30 分钟的通话、重度丢包（>20%）以及 8 s 提议
  兜底的触发率从未在真机上测量（`reports/52-release-closure.md` §5 第 6 项）。

### NFR-5 — 可诊断性

* **陈述。** 每种失败模式都必须留下字段足够定位它的日志事件；日志会轮转，因此不会撑爆设备。
* **实现。** 滚动文件日志器 `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:502`
  （`MAX_FILE_BYTES = 2 MiB`）、`app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:505`
  （`MAX_FILES = 3`），日志目录 `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:508`
  （`logs`）；关键事件绕过异步队列 `app/src/main/kotlin/com/example/webrtcdemo/log/Log.kt:226`
  （`critical`）；原生日志初始化契约 `app/src/main/kotlin/com/example/webrtcdemo/log/Log.kt:212`；
  生成的键清单 `doc/design/_generated/`（日志事件表）。
* **证据。** `reports/07-native-dev.md` §15；记录在
  `reports/46-remote-candidate-counting.md` 中的 `reports/83` 式诊断增强；
  `reports/52-release-closure.md` §4 引用的设备日志行。
* **状态。** 已实现（implemented）。

### NFR-6 — 安全：TURN 暴露面

* **陈述。** TURN 服务必须要求认证，且不得成为开放中继；剩余暴露面被显式接受。
* **实现。** `deploy/turnserver.conf`（配额、被拒绝的对端网段、fingerprint 设置）；
  STUN/TURN 由信令服务交给客户端 `signaling/protocol/message.go:54-57`。
* **证据。** `reports/45-turn-exposure-accepted-risk.md`（用户决定 (C)：接受并记录；24 h 观察期内零
  未授权分配）；`reports/28-turn-permission-403.md`。
* **状态。** 已知限制（known limitation），由决定 (C) 接受。

### NFR-7 — 可复现性锚点

* **陈述。** 工具链版本与固定件是固定的，因此重建是可归因的。
* **实现。** 版本冻结于 `app/build.gradle.kts:38`（`ndkVersion`）、`:104`/`:105`
  （`VERSION_17`）、`:110`（`jvmTarget = "17"`），Gradle wrapper
  `gradle/wrapper/gradle-wrapper.properties`（8.7），Go 工具链 `signaling/go.mod:3`（go 1.22）。
* **证据。** `reports/52-release-closure.md` §1（锚点 + 单元测试计数）；
  `reports/99-final-report.md` §15.1（`jar 0c776934…`、`aar 8e8f2baf…`、`libjingle 757cef81…`、
  `libc++_shared c9dbf4ec…` 在 T0 = T2 未变）；`reports/04-env-install.md`。
* **状态。** 已实现（implemented）。APK 字节**不**可复现（D8 dex 分区）——
  `reports/52-release-closure.md` §5 第 2 项。

### NFR-8 — 发布工件标识与回滚

* **陈述。** 已发布的 APK 由 sha256 标识；发布链拒绝用不同字节的同名工件覆盖，并打印回滚命令。
* **实现。** 发布脚本 `HOST: /opt/apk-http/publish_apk.sh`（主机侧，仓库之外，契约勘误 D-6）；
  `SOURCE.sha256` 标记最后写入，作为提交点。**该脚本本身从容器内不可见**，因此关于其内部的一切陈述
  都是 `unverified (host script)`；可验证的是下面引用的可观测发布面。
* **证据。** `reports/52-release-closure.md` §1（锚点、大小、下载 URL），§6（回滚命令与锚点清单）；
  `reports/99-final-report.md` §15.1（公开 `HEAD` 200、`Range` 206 且 sha256 匹配）；
  `reports/37-t76-build-publish.md` §7（身份保护：不带 `--allow-root` 时 `exit 3`，`--rehearsal`
  路径不改动已提供的文件）。命令/flag 名在
  `doc/design/_generated/host-commands.md` 中有清单，且这里引用的每条仅主机命令都在同一陈述中带有
  仓库内可读证据（`doc/design/SPEC.md` §7 A9）。
* **状态。** 已实现（implemented）；发布脚本体与主机 `parts/` 目录为 `unverified`。

## 4. 已知限制

产品限制按需求逐条陈述（NFR-3、NFR-4、NFR-6）。下面的**工具链**限制同样影响读者能否复现交付，且
每条都在 §6 登记为待办项：

| # | 限制 | 证据 | 影响 | 改进（本阶段未做） |
|---|---|---|---|---|
| L-1 | `scripts/build_app.sh` 只能在主机运行，无法在容器中执行 | 它把 `WS=/opt/dsh-workspaces` 硬编码在 `scripts/build_app.sh:33`，并 source `. "$WS/env.sh"`（见 `scripts/build_app.sh:62`） | 没有主机的读者无法执行文档所述的完整流程；只有 Gradle 步骤是可移植的 | 接受 `WS` 环境变量覆盖，使脚本能可移植地解析工作区（O-6） |
| L-2 | 发布步骤没有仓库内实现 | 本仓库中没有 `publish_apk.sh`；它是 `HOST: /opt/apk-http/publish_apk.sh`，且 `reports/10-app-build.md:1093` 说明发布步骤从不属于构建脚本 | 仅凭仓库无法复现发布（契约勘误 D-6） | 把可移植的发布脚本移入仓库，或保留每个发布版本的报告证据（O-7） |
| L-3 | `HOST` 作用域的路径无法从容器内验证 | `doc/design/SPEC.md` §2.5；`HOST: /opt/signaling`、`HOST: /etc/systemd/system/signaling.service` | 在线服务的事实依赖报告证据，而非第一手检查 | 在能访问主机的会话中重跑主机检查 |
| L-4 | 设备级事项仍然未测量 | `reports/52-release-closure.md` §5 第 6 项 | 长通话稳定性、弱网极限与 8 s 兜底触发率都未知 | 见 O-1 与 O-3 |

## 5. 需求到测试的映射

| 需求 | 主要验证 |
|---|---|
| FR-1, FR-2 | `signaling/room/manager_test.go`, `signaling/server/e2e_test.go`, `reports/99-final-report.md` §15.4 |
| FR-3 | `app/src/test/kotlin/com/example/webrtcdemo/webrtc/SessionLifecycleTest.kt`, `reports/52-release-closure.md` §4 |
| FR-4 | `signaling/room/grace_test.go`, `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt`, `reports/99-final-report.md` §15.4 |
| FR-5 | `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt`, `reports/33-waiting-peer-no-retry.md` |
| FR-6 | `app/src/test/kotlin/com/example/webrtcdemo/signaling/SignalingErrorPolicyTest.kt` |
| FR-7 | `app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt`, `reports/30-ice-relay-robustness.md` |
| FR-8 | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:139`（无自动化测试） |
| NFR-1 | `reports/47-vp9-encode-perf.md`, `reports/52-release-closure.md` §4 |
| NFR-2 | `app/src/test/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrialTest.kt`, `reports/51-frame-dropper-and-trusted-rc.md` |
| NFR-4 | `signaling/server/grace_test.go`, `reports/43-deploy-unit-consistency.md` |
| NFR-7 | `reports/99-final-report.md` §15.1 |
| NFR-7, NFR-8 | 四阶段构建配方 `reports/37-t76-build-publish.md` §3（门槛见 §3.1），发布与四路哈希对账 §5，公开复核 §6 |
| NFR-4, NFR-5 | `/healthz` 字段检查 `deploy/README.md` §Health（主机侧观测；该 unit 的仓库副本是 `deploy/signaling.service:28`） |

## 6. 待办项

| # | 事项 | 为何未决 | 如何关闭 |
|---|---|---|---|
| O-1 | 真机上的 30–90 s 断网 | 只演练过短暂掉线 | 保持通话并开关射频 60 s；期望 `KEEP_CALL` 与一次重加入 |
| O-2 | 提议携带 `iceRestart` | 已在代码中验证（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272` 及其调用点 `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1343`），但未在线路上验证 | 抓取重加入提议的 SDP |
| O-3 | 8 s 兜底触发率 | 没有足够长的真机会话 | 在多次重连中统计 `offer_timeout` 次数 |
| O-4 | 诊断 zip 内容 | `LogExporter` 没有端到端测试 | 在真机上检查导出的 zip |
| O-5 | P2P 与中继的质量对比 | 两个测试对端都在对称 NAT 之后 | 用位于 full-cone NAT 的对端重测 |
| O-6 | `scripts/build_app.sh` 无法在容器中运行 | 它把 `WS=/opt/dsh-workspaces` 硬编码在 `scripts/build_app.sh:33`，并 source `. "$WS/env.sh"`（见 `scripts/build_app.sh:62`），属于 `HOST:` 脚本 | 后续阶段的改进项：接受 `WS` 环境变量覆盖，使脚本可移植；文档阶段不改代码。与容器等价的 Gradle 步骤见 `doc/design/07-build-and-deploy.md` |
| O-7 | 发布步骤无法仅凭仓库复现 | 发布是主机手工流程：本仓库中**没有** `publish_apk.sh`（`HOST: /opt/apk-http/publish_apk.sh`，`reports/10-app-build.md:1093`） | 保留按发布版本的报告证据（`reports/37-*-build-publish.md`），或在后续阶段把可移植的发布脚本移入仓库 |

## 7. 勘误汇总（D-1..D-6）

历史契约 `doc/archive/14-interface-contract.md` 与实现在六处已登记的地方存在分歧
（`reports/99-final-report.md` §15.5）：

| ID | 分歧 | 若照历史文本执行的后果 |
|---|---|---|
| D-1 | 历史重连文本写 3 s / 3 次；实现使用 1/2/4/8 s ×10 = 63 s | 客户端会在 90 s 宽限期之前放弃并断开通话 |
| D-2 | 历史文本称旋转已烘焙进 I420 像素；实现只做旋转传递（`app/src/main/cpp/encoder/vp9_encoder.cpp` flag `kBakeRotationInEncoder = false`） | 照文本实现会改变像素方向 |
| D-3 | coturn 选项是 `fingerprint`，不是 `use-fingerprint`，在 coturn 4.6.1 中如此 | coturn 拒绝启动或静默忽略该选项 |
| D-4 | 历史文本「旋转已烘焙进 I420」被上游源码已证伪（disproven） | 对采集路径的错误心智模型 |
| D-5 | 仓库 `deploy/signaling.service` 先前缺少 `-room-grace 90s`，而在线 unit 有 | 从仓库重新部署会静默退回旧的断连行为 |
| D-6 | 下载面（`parts/`、`SHA256SUMS`、`SOURCE.sha256`、发布脚本）位于主机、仓库之外 | 服务端无法仅凭仓库重建 |

D-1、D-2 与 D-5 现已在代码或仓库副本中关闭；D-3 与 D-6 作为主机运维事实被接受。每条事项的完整陈
述、证据与影响维护在 doc/design/09-verification-and-limitations.md。
