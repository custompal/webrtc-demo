> **中文（默认）** · [English](../10-code-map.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 10 — 代码地图

> Status: draft · Owner: writer-ops · Task: t5
> Evidence base: current HEAD source tree, reports/01..52, doc/design/SPEC.md
> Doc standard: `doc/design/SPEC.md`

## 1. 范围

本文件是导航层：代码在哪里、每份报告记录了什么、存在哪些文档，以及如何从观察到的症状找到产生它的代码。

它刻意不复述行为：应用架构、信令服务与协议各有自己的文档。这里的断言仅限于文件位置、每个文件声明的入口，以及报告出处。

行号对当前 HEAD 源码有效。它们是指针，不是契约：如果代码移动，更新的是本文件，而不是反过来。

## 2. 代码地图

### 2.1 Kotlin 应用（`app/src/main/kotlin/com/example/webrtcdemo/`）

| 路径 | 职责 | 入口 |
|---|---|---|
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt` | 通话状态机、ICE/协商生命周期、看门狗 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt` | 引擎单例与 PeerConnection 工厂归属 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:43` |
| `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt` | 信令通道的 WebSocket 客户端 | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:35` |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt` | 自建 VP9 编码器 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59` |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt` | 编码器选择与兜底接线 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:40` |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt` | 自建编码器跟不上时兜底到平台编码器 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:41` |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt` | 渲染器挂载/卸载生命周期 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:44` |
| `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt` | 暴露给 UI 的 NAT 类型状态 | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:24` |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` | 通话页状态持有者与通话生命周期协调 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:52` |
| `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt` | 诊断日志 zip 导出 | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:51` |
| `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt` | 运行时配置开关 | `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:22` |

其余 Kotlin 包为：`log/`（文件日志）、`model/`（UI 与统计数据模型）、`nativebridge/`（轻量 JNI 声明）、`ui/`（Compose 页面、导航、主题）与 `webrtc/`（对等连接辅助代码，例如候选解析、统计映射与帧归一化）。

References: `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt`

### 2.2 原生层（`app/src/main/cpp/`）

| 路径 | 职责 | 入口 |
|---|---|---|
| `app/src/main/cpp/jni/jni_bridge.cpp` | JNI 注册 | `app/src/main/cpp/jni/jni_bridge.cpp:57` |
| `app/src/main/cpp/jni/jni_bridge.h` | 注册契约（register-natives 风格） | `app/src/main/cpp/jni/jni_bridge.h:9` |
| `app/src/main/cpp/jni/vp9_encoder_jni.cpp` | VP9 编码器的 JNI 表面 | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:69` |
| `app/src/main/cpp/encoder/vp9_encoder.cpp` | VP9 编码器实现 | `app/src/main/cpp/encoder/vp9_encoder.cpp:77` |
| `app/src/main/cpp/nat/nat_detector.cpp` | RFC 5780 NAT 探测 | `app/src/main/cpp/nat/nat_detector.cpp:25` |
| `app/src/main/cpp/log/native_log.cpp` | 原生日志汇聚点 | `app/src/main/cpp/log/native_log.cpp:61` |

未在上表列出的子目录：`encoder/` 还包含码率策略、旋转器与码率分配器及其主机测试；`nat/` 包含 STUN 客户端与地址处理；`tests/host/` 包含带 stub 头文件的主机编译测试；`util/` 包含 JNI 与线程辅助代码。`app/src/main/cpp/CMakeLists.txt` 定义原生库。

References: `app/src/main/cpp/jni/jni_bridge.cpp`

### 2.3 信令服务（`signaling/`）

| 路径 | 职责 | 入口 |
|---|---|---|
| `signaling/main.go` | 进程入口与接线 | `signaling/main.go:28` |
| `signaling/server/server.go` | HTTP/WebSocket 服务器构建 | `signaling/server/server.go:47` |
| `signaling/server/ws_handler.go` | 连接处理与对端选项 | `signaling/server/ws_handler.go:24` |
| `signaling/room/room.go` | 房间与席位状态 | `signaling/room/room.go:49` |
| `signaling/room/manager.go` | 房间生命周期与过期 | `signaling/room/manager.go:59` |
| `signaling/protocol/message.go` | 消息类型词汇表 | `signaling/protocol/message.go:12` |
| `signaling/protocol/errors.go` | 错误码 | `signaling/protocol/errors.go:5` |
| `signaling/config/config.go` | 配置常量与解析 | `signaling/config/config.go:18` |
| `signaling/logging/logging.go` | 日志器构建 | `signaling/logging/logging.go:20` |

另有：`signaling/logging/` 的轮转与格式化、`signaling/util/` 的房间号生成，以及与被测代码同目录的 `_test.go` 文件。编译出的二进制不入版本控制；见 §4。

References: `signaling/server/server.go`

### 2.4 工具与部署

| 路径 | 职责 |
|---|---|
| `scripts/build_app.sh` | 主机构建流水线与门禁 |
| `scripts/deploy_signaling.sh` | 部署信令二进制并重启 unit |
| `scripts/doc-verify.sh` | 文档门禁 |
| `scripts/gen-doc-tables.sh` | 生成 `doc/design/_generated/**` |
| `scripts/verify_signal_e2e.mjs` | 信令端到端检查 |
| `scripts/t5-libwebrtc-libvpx-build.sh` | libwebrtc 与 libvpx 构建 |
| `scripts/t56-libvpx-runtime-cpu-detect-rebuild.sh` | libvpx 重建辅助脚本 |
| `scripts/t56-libvpx-verify-runtime-cpu-detect.sh` | libvpx 验证辅助脚本 |
| `scripts/make-libcxx-shared-16k.sh` | C++ 运行时库准备 |
| `scripts/check_jn_binding.py` | JNI 绑定检查 |
| `scripts/check_jar_link_integrity.py` | Java SDK 链接检查 |
| `scripts/build_java_sdk_with_jni.sh` | Java SDK 重建 |
| `deploy/signaling.service` | 信令 systemd unit（仓库副本） |
| `deploy/coturn.service`, `deploy/coturn.default` | coturn unit 与默认值 |
| `deploy/turnserver.conf` | coturn 配置 |
| `deploy/turnperm_probe.mjs`, `deploy/turnperm_probe_tcp.mjs` | TURN 权限探针 |
| `deploy/README.md` | 部署复现笔记 |
| `doc/design/_generated/host-commands.md` | 生成命令清单 |
| `doc/design/_generated/log-events.md` | 生成日志事件清单 |
| `doc/design/_generated/jni-contract.md` | 生成 JNI 契约 |
| `doc/design/_generated/signaling-messages.md` | 生成信令消息表 |

References: `scripts/build_app.sh`, `deploy/signaling.service`

## 3. 报告索引

编号遵循文件名。有些编号下不止一份文档；出现这种情况时，由主题列区分它们。「Referenced by」给出报告所属的任务，取自报告自身的标题。

| 报告 | 主题 | 要点 | Referenced by |
|---|---|---|---|
| 01-host-recon.md | 主机连通性、资源与环境 | 搭建前先验证主机环境 | t1 |
| 02-interface-contract.md | 接口与架构契约 | 实现所对照衡量的契约 | t2 |
| 02-webrtc-network-recon.md | 源码拉取的网络瓶颈 | 实测瓶颈与缓解措施 | t5 preflight |
| 03-git-submodules.md | git 仓库与源码 submodule | 引入 libvpx 与 libwebrtc 源码 | t3 |
| 04-env-install.md | Android SDK/NDK、JDK 17、cmake/ninja | 主机工具链安装记录 | t4 |
| 05-libwebrtc-build.md | libwebrtc arm64 与 libvpx 构建 | 原生构建记录 | t5 |
| 06-coturn.md | coturn STUN/TURN 部署 | 中继的部署与验证 | t6 |
| 07-native-dev.md | C++ 原生层 | 原生层实现记录 | t7 |
| 08-android-dev.md | Kotlin/Compose、信令与 JNI 封装 | 应用实现记录 | t8 |
| 09-go-signaling.md | Go 信令服务与端到端自检 | 服务实现记录 | t9 |
| 10-app-build.md | 主机上的 APK 与 Go 二进制构建 | 交付构建及其锚点 | t10 |
| 12-deploy-signaling.md | 信令 systemd 部署与 coturn 集成 | 部署验证 | t12 |
| 13-device-defect-fix.md | 两个真机缺陷 | 缺陷修复 | t39 |
| 13-go-toolchain.md | Go 工具链安装 | 工具链验证 | t13 |
| 14-android-skeleton.md | 与契约无关的 Android 骨架 | Gradle 工程与资源 | pre-t14 |
| 15-connection-defect.md | 真机连通性缺陷 | 缺陷修复 | t15 |
| 15-java-jar-rebuild.md | libwebrtc Java SDK 重建 | Java 17 重建 | t16/t17 |
| 16-foreground-black-preview.md | 切到后台后预览黑屏 | 缺陷修复 | t16 |
| 17-vp9-rotation.md | VP9 旋转语义 | 编码器旋转完成 | t46 |
| 18-encoder-stall.md | 编码器只出一帧就停 | 缺陷诊断 | t47 |
| 19-ice-candidate-parse.md | 候选解析字段错位 | 缺陷修复 | t49 |
| 20-encode-resize-crash.md | 编码器改尺寸时崩溃 | 缺陷修复 | t50 |
| 21-remote-message-race.md | 远端消息竞态 | 缺陷修复 | t52 |
| 22-encode-selfowned-image.md | 编码器崩溃：自持有图像 | 缺陷修复 | t50b |
| 23-session-lifecycle.md | 加入方应答时序 | 缺陷修复 | t53 |
| 24-nat-address-endianness.md | 原生 STUN 映射地址字节序 | 缺陷修复 | t54 |
| 25-encoder-vpx-encode-crash.md | 首帧编码器崩溃 | 缺陷修复 | t55 |
| 26-libvpx-runtime-cpu-detect.md | arm64 libvpx 运行时 CPU 检测 | 恢复运行时分发 | t56 |
| 27-encoder-direction-perf.md | 编码器方向与性能 | 缺陷与性能修复 | t57 |
| 28-turn-permission-403.md | 移动网络与 wifi 之间中继不可用 | 定位 create-permission 失败 | t58 |
| 29-connect-state-ui.md | 未连接时 UI 误导 | 缺陷修复 | t59 |
| 30-ice-relay-robustness.md | 中继健壮性、看门狗与回环候选 | 缺陷修复 | t60 |
| 31-ui-liveness-a7.md | 通话页存活判据需求 | 缺陷修复 | t61 |
| 32-remote-frame-liveness.md | 远端帧存活的误报 | 缺陷修复 | t63 |
| 33-waiting-peer-no-retry.md | 等待对端时出现重试提示 | 缺陷修复 | t64 |
| 34-turn-tcp-fallback.md | TURN over TCP 回退 | 增加客户端回退 | t65 |
| 35-room-grace.md | WebSocket 断开时的房间宽限期 | 房间在瞬时断开后存活 | t66 |
| 36-call-survivability.md | pong 容差与信令丢失 | 通话存活工作 | t68 |
| 37-t69-build-publish.md | 构建与发布运行 | 发布链运行记录 | t69 |
| 37-t72-build-publish.md | 构建与发布运行 | 四路对账与公开验证 | t72 |
| 37-t76-build-publish.md | 构建与发布运行 | 带门禁输出的四阶段配方 | t76 |
| 37-t81-build-publish.md | 构建与发布运行 | 身份守卫证据 | t81 |
| 37-t84-build-publish.md | 构建与发布运行 | 发布链运行记录 | t84 |
| 37-t86-build-publish.md | 构建与发布运行 | 发布链运行记录 | t86 |
| 37-t88-build-publish.md | 构建与发布运行 | 中止记录 | t88 |
| 37-t90-build-publish.md | 构建与发布运行 | 发布链运行记录 | t90 |
| 37-t93-build-publish.md | 构建与发布运行 | 发布链运行记录 | t93 |
| 38-signaling-grace-deploy.md | 宽限期修复的部署 | 在主机上编译并部署 | t67 |
| 39-reconnect-budget-ice-restart.md | 重连预算与 ICE 重启 | 预算与宽限期对齐 | t71 |
| 40-glare-ice-restart-fix.md | ICE 重启时序与冲突 | 拆分提议职责并加兜底 | t75 |
| 41-apk-http-ownership.md | 发布目录归属 | 归属改为 uid 1000 并加守卫 | t77 |
| 42-delivery-verification.md | 交付验证 | 对某个锚点的独立验证 | t78 |
| 43-deploy-unit-consistency.md | 仓库 unit 与主机在线 unit | 两者已对齐 | D-5 fix |
| 44-ice-watchdog-false-failure.md | ICE 看门狗误报失败 | 修复误报失败横幅 | t80 |
| 45-turn-exposure-accepted-risk.md | TURN 暴露面决策 | 接受风险并附补偿措施 | decision C |
| 46-remote-candidate-counting.md | 远端候选计数 | 修复两个缺陷 | t83 |
| 47-vp9-encode-perf.md | 编码器卡顿 | 已量化并定位 | t85 |
| 48-encoder-fallback.md | 编码器自动兜底 | 兜底已交付 | t87 |
| 49-bitrate-allocation-collapse.md | 码率分配坍塌 | 已诊断 | t89 |
| 50-quality-scaling-and-render-fps.md | 质量降级与渲染帧率 | 渲染侧不是瓶颈 | t91 |
| 51-frame-dropper-and-trusted-rc.md | 帧丢弃器与可信码率控制器 | 经 field trial 禁用丢弃器 | t92 |
| 52-release-closure.md | 发布收口 | 最终锚点、修复与已知限制 | release |
| 99-final-report.md | 独立验证与总结 | 最终总结报告 | t11 |
| 99-t34-appendix.md | 独立复验附录 | 制品级复验 | t34 |

## 4. 文档地图

### 4.1 当前文档（`doc/design/`）

规范集合中的所有成员在本次修订下都存在，并在下方给出链接。

| 文档 | 内容 |
|---|---|
| [SPEC](../SPEC.md) | 冻结的文档规范与门禁判定规则 |
| [01 — Requirements](../01-requirements.md) | 需求及其实现与证据 |
| [02 — Architecture](../02-architecture.md) | 总体架构、平面、端口与拓扑 |
| [03 — App architecture](../03-app-architecture.md) | 应用分层、线程模型、生命周期、恢复、配置开关 |
| [04 — Signaling service](../04-signaling-service.md) | 包布局、房间与席位状态、路由、配置 |
| [05 — Protocols](../05-protocols.md) | 字段级消息表、ICE/TURN/SDP 行为、JNI 契约 |
| [06 — Flows](../06-flows.md) | 关键流程的时序图，附真实日志行 |
| [07 — Build and deploy](../07-build-and-deploy.md) | 工具链、构建阶段、发布链、主机服务 |
| [08 — Issues and solutions](../08-issues-and-solutions.md) | 从症状到根因的历史，含已否决（rejected）的方案 |
| [09 — Verification and limitations](../09-verification-and-limitations.md) | 验证矩阵、已知限制、契约勘误 |
| [10 — Code map](../10-code-map.md) | 本文件 |
| [11 — Coding standards](../11-coding-standards.md) | 约定与改动安全 |
| `doc/design/_generated/` | 生成表格；从不手工编辑 |

### 4.2 历史文档与决策（`doc/`）

[doc/README.md](../../README.md) 解释历史文档与当前文档的分界，是存档材料的入口。

原先直接位于 `doc/` 下的每个文件都逐字移入 `doc/archive/`，并在原路径上替换为一行 stub，注明存档文件名与取代它的文档。存档集合覆盖总览、云基础设施、架构、实现计划、术语表、代码设计、编码器内部、构建指南、协议规范、UI 设计、原生与后端实现、agent 任务规范以及接口契约。这些 stub 的存在是为了让来自 `reports/**` 的历史引用不至于悬空。

架构决策记录位于 `doc/adr/`：
[ADR-001](../../adr/ADR-001-use-libwebrtc-and-source-build.md),
[ADR-002](../../adr/ADR-002-custom-video-encoder-for-dynamic-bitrate.md),
[ADR-003](../../adr/ADR-003-1to1-p2p-first.md),
[ADR-004](../../adr/ADR-004-split-runtime-and-build-vms.md)。

仓库根 README.md 是文档集计划中的入口，撰写时尚未编写。这里刻意留作纯文本：指向不存在文件的链接或代码跨度会让文档门禁失败，因此一旦入口存在，该链接就会被恢复。

## 5. 症状到代码

| 观察到的症状 | 阅读位置 | 证据 |
|---|---|---|
| 作出了兜底决定 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:41` | `reports/48-encoder-fallback.md` |
| 对端离开 / 通话被保留存活 | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:35`, `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:52` | `reports/35-room-grace.md`, `reports/36-call-survivability.md` |
| 编码器卡顿或质量降级 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59` | `reports/47-vp9-encode-perf.md`, `reports/50-quality-scaling-and-render-fps.md` |
| 编码路径上的帧丢弃 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:40` | `reports/51-frame-dropper-and-trusted-rc.md` |
| ICE 或中继故障 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` | `reports/28-turn-permission-403.md`, `reports/44-ice-watchdog-false-failure.md` |
| NAT 类型看起来不对 | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:24`, `app/src/main/cpp/nat/nat_detector.cpp:25` | `reports/24-nat-address-endianness.md` |
| 请求了诊断包 | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:51` | `reports/52-release-closure.md` |
| 房间生命周期表现异常 | `signaling/room/room.go:49`, `signaling/room/manager.go:59` | `reports/35-room-grace.md`, `reports/38-signaling-grace-deploy.md` |

有两类设备侧指标在源码中没有出现位置，必须从报告读取而不能引用到某一行：`reports/50-quality-scaling-and-render-fps.md` 中的帧丢弃计数器，以及 `reports/49-bitrate-allocation-collapse.md` 中的码率更新观测。禁用帧丢弃器的 field trial 开关记录在 `reports/51-frame-dropper-and-trusted-rc.md`。

## 6. 证据索引

| 声明 | 引用 | 验证制品 |
|---|---|---|
| Kotlin 通话状态机入口 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` | 源码 |
| 信令客户端入口 | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:35` | 源码 |
| 自建编码器入口 | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59` | 源码 |
| 原生注册 | `app/src/main/cpp/jni/jni_bridge.cpp:57` | 源码 |
| 信令进程入口 | `signaling/main.go:28` | 源码 |
| 房间状态入口 | `signaling/room/room.go:49` | 源码 |
| 带门禁输出的构建配方 | — | `reports/37-t76-build-publish.md` §3 |
| 发布身份守卫 | — | `reports/41-apk-http-ownership.md` §5 |
| 历史 stub 只有一行 | `doc/00-overview.md:1` | stub 文件 |

## 7. 待办事项

- §4 的文档地图在本次修订下是完整的：规范集合中的每个成员都存在且已被链接。加入该集合的文档必须加入该表，且表中提到的任何文件都必须存在，因为指向缺失文件的链接或反引号路径会让文档门禁失败。
- 报告索引给出报告所属的任务，取自报告自身的标题。因此「Referenced by」列记录的是出处，而不是一次穷尽的反向引用扫描 —— 后者并未执行：`unverified`。
- 报告只按名称与小节引用；从不使用报告行号，因为报告编号会与源码树漂移，且报告行号不是源码指针。
- §2 的包级分组只是摘要；向某个包新增文件而不更新本文件，不会被文档门禁察觉。
