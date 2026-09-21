> **中文（默认）** · [English](../02-architecture.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 02 — 架构

> Status: draft · Owner: architect · Task: t1
> Evidence base: `reports/52-release-closure.md`, `reports/99-final-report.md` §15, `deploy/README.md`,
> `reports/06-coturn.md`, `reports/09-go-signaling.md`
> Doc standard: `doc/design/SPEC.md`

---

## 1. 范围

本文档是系统级地图：有哪些组件、它们如何部署、每个关注点属于哪个面，以及由哪篇专门文档承载细节。
它刻意不重述协议字段（见 [05-protocols.md](../05-protocols.md)）、App 内部（见
[03-app-architecture.md](../03-app-architecture.md)）或发布流程（见
[07-build-and-deploy.md](../07-build-and-deploy.md)）。

## 2. 一段话概述系统

一个 Android 应用进行一对一视频通话。两部手机通过一条 WebSocket 与同一个 Go 信令服务通信，交换
SDP 与 ICE 候选，然后经由 WebRTC 承载媒体。应用自带 VP9 编码器（libvpx 经 JNI 边界），而不是用平
台编码器。当 NAT 穿透失败时，媒体由 coturn 中继。构建出的 APK 由同一台主机上的一个小型 HTTP 文件
服务发布。系统没有用户账号体系，也没有房间持久化：房间只存在于信令进程的内存中。

引用键：下文每个代码指针都是 `file:` 形式的引用（`file:LINE`），可被 `scripts/doc-verify.sh` 机器校验。

## 3. 组件

| 组件 | 角色 | 实现 | 运行时事实 |
|---|---|---|---|
| Android App | UI、通话状态机、WebRTC 会话、自定义 VP9 编码器、诊断 | `app/src/main/kotlin/**`, `app/src/main/cpp/**` | 仅 arm64-v8a；`applicationId com.example.webrtcdemo`（`app/build.gradle.kts:41`） |
| 信令服务 | 房间/席位生命周期、消息路由、ICE 配置下发、健康端点 | `signaling/**`（Go 1.22，`signaling/go.mod:3`） | 单个静态二进制；监听 `:8443`（`signaling/config/config.go:19`） |
| coturn | STUN 与 TURN 中继 | 主机软件包，配置副本 `deploy/turnserver.conf` | `listening-port=3478`（`deploy/turnserver.conf:4`），中继池 `49152-49200`（`deploy/turnserver.conf:8-9`） |
| apk-http | 提供构建出的 APK 及其校验和 | 主机侧服务，仓库之外（契约勘误 D-6） | `HOST: /opt/apk-http`；公开 URL `http://47.238.144.66:8080/app-debug.apk`（`reports/52-release-closure.md` §1） |

## 4. 部署拓扑

```
                       47.238.144.66 (public) / 172.21.0.219 (private)   [HOST: all services below]
 ┌───────────────────────────────────────────────────────────────────────────┐
 │  HOST: /etc/systemd/system/signaling.service   :8443/tcp   /ws, /healthz   │
 │  coturn                                        3478/udp+tcp, 49152-49200  │
 │  HOST: /opt/apk-http                           :8080/tcp   /app-debug.apk  │
 └───────────────────────────────────────────────────────────────────────────┘
        ▲ ws://47.238.144.66:8443/ws              ▲ stun:/turn:47.238.144.66:3478
        │  (SDP, ICE, NAT type, heartbeat)        │  (candidate gathering, relay)
   ┌────┴────┐                                ┌────┴────┐
   │ Phone A │◄──── media: P2P if possible, otherwise TURN relay ────►│ Phone B │
   └─────────┘        (DTLS/SRTP, VP9 video, Opus audio)             └─────────┘
```

图后的事实：

* 信令服务通过 `/ws` 访问（`signaling/server/server.go:25`）；编译内置的默认值是
  `ws://47.238.144.66:8443/ws`（`app/build.gradle.kts:69`），并可在运行时从诊断页覆盖
  （`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:106`）。
* 客户端使用的 STUN/TURN URL 并非编译内置：服务器在 `created`/`joined` 中下发它们
  （`signaling/protocol/message.go:54-57`），它们的值是 `-stun`/`-turn` flag 的取值
  （`deploy/signaling.service:28`）。
* 只有 `3478/udp`、`3478/tcp`、`49152-49200/udp` 与 `8443/tcp` 开放；`5349`（TLS/DTLS）不开放
  （`deploy/README.md` §TURN exposure）。客户端侧在 ICE server 列表中有一条 TURN-over-TCP 兜底项
  （`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:66`）。
* Unit 与仓库副本保持参数一致；检查流程在 `deploy/README.md` §6，对齐修复是
  `reports/43-deploy-unit-consistency.md`。
* 仓库提供 `scripts/build_app.sh` 作为构建顺序的文档，但它是 `HOST` 作用域脚本（在
  `scripts/build_app.sh:33` 硬编码 `WS=/opt/dsh-workspaces`，并在
  `scripts/build_app.sh:62` source `$WS/env.sh`），因此无法在容器内执行；见 §5.4。工作区根目录的
  `env.sh`、`env-container.sh` 与 `env-go.sh` 提供工具链变量（自动归类为 P2，
  `workspace-only, outside git repo`；`doc/design/SPEC.md` §7.1）。

## 5. 面

每个面有一篇所有者文档；这张表的存在是为了让读者直接跳到正确的那一篇。

| 面 | 它回答的问题 | 所有者文档 |
|---|---|---|
| 信令 | 对端如何找到彼此并交换 SDP/ICE？ | [04-signaling-service.md](../04-signaling-service.md), [05-protocols.md](../05-protocols.md) |
| 媒体 | 帧与音频如何传过去，又是什么在塑造码率？ | [03-app-architecture.md](../03-app-architecture.md), [05-protocols.md](../05-protocols.md) |
| 诊断 | 我如何看到发生了什么？ | [03-app-architecture.md](../03-app-architecture.md) §Diagnostics, [_generated/log-events.md](../_generated/log-events.md) |
| 发布 | 代码如何变成可下载的 APK？ | [07-build-and-deploy.md](../07-build-and-deploy.md) |

### 5.1 信令面

* 每个对端一条 WebSocket；JSON 文本帧由 `type` 字段区分（`signaling/protocol/message.go:41`）。
* 房间在内存中：最多 2 个席位（`signaling/room/room.go:13`），无人加入的房间在 `-room-expiry`
  1800 s 后过期（`signaling/config/config.go:26`）。
* 心跳由客户端驱动：每 15 s 一次 ping（`signaling/protocol/heartbeat.go:11`）；服务端读超时为 3 个
  ping 间隔（`signaling/config/config.go:38`）；客户端容忍连续 4 次丢失后才判定链路已死
  （`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`）。
* WebSocket 断开不会销毁房间：席位在宽限期内保留（`signaling/config/config.go:54`），同身份重连的
  对端接管该席位（`signaling/room/room.go:66`）。

### 5.2 媒体面

* 采集、编码、打包、加密与渲染都由 libwebrtc 完成；本项目提供视频编码器与可观测性
  （`app/src/main/cpp/encoder/vp9_encoder.cpp`、
  `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt`）。
* ICE 候选来自信令服务下发的 STUN/TURN 配置；回环候选在两侧都被过滤
  （`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1581`）。
* 网络路径变化通过 ICE 重启恢复，且只由发起方发起
  （`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:127`）。
* 在实测部署中两个对端都在对称 NAT 之后，因此每次通话都走中继路径
  （`reports/52-release-closure.md` §5 第 1 项）。

### 5.3 诊断面

* 三类生产者（Kotlin、C++ native、webrtc）都写入同一个目录 `<filesDir>/logs/`
  （`app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:508`），轮转策略为 2 MiB × 3
  （`app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:502`, `:505`）。
* 每个事件都携带 `session=sN` 以及事件序号，因此可以区分不同会话的行
  （`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`,
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:173`）。
* 面向用户的入口是诊断页
  （`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt`）与日志 zip 导出
  （`app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:139`）。
* 完整的事件键清单由代码生成到 `doc/design/_generated/`（绝不手写）。

### 5.4 发布面

* 构建在主机上使用固定工具链进行（JDK 17、Gradle 8.7、AGP 8.5.2、Kotlin 2.0.21、
  NDK 26.1.10909125、CMake 3.22.1、Go 1.22）。
* `scripts/build_app.sh` **只能在主机运行**：它在 `scripts/build_app.sh:33` 硬编码
  `WS=/opt/dsh-workspaces`，并在 `scripts/build_app.sh:62` source `. "$WS/env.sh"`，因此无法在容器内
  运行（`HOST` 作用域脚本；与容器等价的 Gradle 步骤见 doc/design/07-build-and-deploy.md）。
* 发布是主机手工流程：`HOST: /opt/apk-http/publish_apk.sh`（本仓库中不存在该文件）——见
  `doc/design/SPEC.md` §2.5 与 `reports/10-app-build.md:1093`。
* 发布链拒绝用不同字节覆盖同名工件，并把最后写入的 `SOURCE.sha256` 视为提交点
  （`reports/52-release-closure.md` §1、§6）。
* 交付锚点是 APK sha256
  `59c75778fb457e8c6d4c7955b349dfa4fde784fa616e852419e3b007e1e41d07`，33 472 645 B，由提交
  `4130ddc` 构建（`reports/52-release-closure.md` §1）。该文件本身是构建产物
  （`app-debug.apk`），绝不是仓库路径（P5，自动归类；`doc/design/SPEC.md` §7.1）。

## 6. 文档地图

| 文档 | 一句话用途 |
|---|---|
| `doc/design/SPEC.md` | 冻结的写作与引用规范、目录约定、门禁规则 |
| `doc/design/01-requirements.md` | 带引用与状态的功能与非功能需求 |
| `doc/design/02-architecture.md` | 本文档 |
| [03-app-architecture.md](../03-app-architecture.md) | App 分层、线程、生命周期、恢复、开关 |
| [04-signaling-service.md](../04-signaling-service.md) | Go 服务结构与房间/席位状态机 |
| [05-protocols.md](../05-protocols.md) | 字段级消息、重连策略、ICE/TURN/SDP、JNI 契约 |
| [06-flows.md](../06-flows.md) | 带步骤清单与真实日志证据的 Mermaid 流程 |
| [07-build-and-deploy.md](../07-build-and-deploy.md) | 工具链、构建阶段、不变量、发布链、主机服务 |
| [08-issues-and-solutions.md](../08-issues-and-solutions.md) | 缺陷史，含已否决（rejected）/已证伪（disproven）的替代方案 |
| [09-verification-and-limitations.md](../09-verification-and-limitations.md) | 验证矩阵、已知限制（known limitation）、契约勘误 |
| [10-code-map.md](../10-code-map.md) | 代码地图、报告索引、症状到代码的查找 |
| [11-coding-standards.md](../11-coding-standards.md) | 语言约定与改动安全清单 |
| `doc/design/_generated/signaling-messages.md` | 生成的 signalling 消息/字段表，含 Go↔Kotlin 分歧 |
| `doc/design/_generated/log-events.md` | 生成的日志事件键表（产生文件与行号） |
| `doc/design/_generated/jni-contract.md` | 生成的 JNI 契约，交叉核对 Kotlin ↔ C++ `RegisterNatives` |
| `doc/design/_generated/host-commands.md` | 生成的命令/flag 清单及其证据状态 |
| `doc/README.md` | 历史与现状说明、完整归档清单与 stub 机制 |

上表中的每一章都已落地：在干净检出的这个目录下，每个相对链接都能解析，因此没有任何一行退化为纯文
本，也不需要标注「待翻译（planned）」（`doc/design/SPEC.md` C7）。

仓库根 README.md 是中文默认入口，README.en.md 保存英文原文；两个文件都已落地，因此两个名字都不保留为纯
文本，也不适用「待翻译（planned）」标记。

## 7. 从 ADR 继承的决策

七份 ADR 已原样移入 `doc/archive/adr/`；每个原路径保留一行 stub
（`doc/adr/ADR-001-use-libwebrtc-and-source-build.md` …
`doc/adr/ADR-007-code-style-and-comment-rules.md`，见 `doc/adr/README.md`）。它们的决策仍然有效；
下表记录每条决策今天落在哪里。

| ADR | 决策 | 今天可见于 |
|---|---|---|
| ADR-001 | 使用 libwebrtc 并从源码构建 | `third_party/libwebrtc/`，固定 jar/aar 哈希见 `reports/99-final-report.md` §15.1 |
| ADR-002 | 自定义 VP9 编码器以研究动态码率 | `app/src/main/cpp/encoder/`、`app/src/main/kotlin/com/example/webrtcdemo/encoder/` |
| ADR-003 | 先做 1:1 P2P | 房间容量 2（`signaling/room/room.go:13`） |
| ADR-004 | 拆分运行机与构建机 | 构建在主机运行（`scripts/build_app.sh` 头部使用说明） |
| ADR-005 | Go 信令加一个 RFC 5780 NAT 测试 | `signaling/`、`app/src/main/cpp/nat/nat_detector.cpp` |
| ADR-006 | 代码设计决策 | [03-app-architecture.md](../03-app-architecture.md) |
| ADR-007 | 代码风格与注释规则 | [11-coding-standards.md](../11-coding-standards.md) |

## 8. 跨面不变量

* **I-1** — 房间绝不超过两个席位（`signaling/room/room.go:13`,
  `signaling/room/manager.go:161`）。
* **I-2** — 同一时间只有一侧提出提议：发起方提议，加入方应答
  （`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:127` 编码了提议职责）。
* **I-3** — 宽限期必须大于客户端重连预算：90 s > 63 s
  （`signaling/config/config.go:54`,
  `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163`；
  断言 `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt:52`）。
* **I-4** — 本地存活判定绝不能结束媒体仍在流动的通话
  （`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:105`）。
* **I-5** — 媒体几何（旋转、stride、缓冲容量）在每个方向上只在一处决定；见
  doc/design/08-issues-and-solutions.md 中的旋转烘焙实验：已否决（rejected）。

## 8.1 JNI 边界与证据类别

* 自定义 native 库是 CMake target `webrtcdemo_native`（`app/src/main/cpp/CMakeLists.txt:32`）。其契
  约由 `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/**` 下的 Kotlin `external fun` 声明
  定义，并与 `app/src/main/cpp/jni/nat_detector_jni.cpp:49`、
  `app/src/main/cpp/jni/native_log_jni.cpp:71` 与
  `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` 中的 `JNINativeMethod` 表交叉核对（共 15 条注册
  项），再加上 `app/src/main/cpp/jni/*.h` 中的 C 原型。生成的交叉核对结果是
  `doc/design/_generated/jni-contract.md`（`doc/design/SPEC.md` §2.1）。
* 绑定方式是 `JNI_OnLoad` + `RegisterNatives`（`app/src/main/cpp/jni/jni_bridge.h:9`），因此该库
  **不**暴露任何 `Java_*` 入口点。仓库中也没有项目生成的 `*_jni.h`；它是 CMake 构建产物。
* 构建出的 `libwebrtcdemo_native.so` 只是构建产物：`.gitignore:56` 忽略 `*.so`，它位于
  `app/build/intermediates/**` 下。文档不得把它当作仓库事实引用（`doc/design/SPEC.md` §2.4）。
* 本文档集通篇使用的证据类别（`doc/design/SPEC.md` §2.3）：**仓库**路径可被校验；**仅工作区**路径
  （如 `/data/dsh/home/workspace/tmp/t47b-captain-build.sh`）在这里可读但在 git 之外，必须如此标
  注；**仅主机**路径（`/opt/**`、`/etc/**`）无法从容器内验证，且始终标记为 `unverified`。

## 9. 待办项

| # | 事项 | 为何未决 |
|---|---|---|
| A-1 | 实测部署中没有 P2P 路径 | 两个对端都在对称 NAT 之后（`reports/52-release-closure.md` §5 第 1 项） |
| A-2 | 主机侧发布脚本不在仓库中 | 契约勘误 D-6 |
| A-3 | `deploy/README.md` 是用中文写的 | 它早于 `doc/design/SPEC.md` §6 的仅英文规则；它所记录的 unit 与 coturn 配置的仓库副本对部署具有权威性 |
