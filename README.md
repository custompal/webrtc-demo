> **中文（默认）** · [English](README.en.md)

# webrtc-demo — 文档入口

> Status: draft · Owner: writer-app · Task: t23
> 文档标准：[doc/design/SPEC.md](doc/design/SPEC.md)
> 冻结工件集：[reports/54-docs-freeze-manifest.md](reports/54-docs-freeze-manifest.md)

一个 Android 一对一 WebRTC 演示：基于 libwebrtc 的 Kotlin 应用（含自建的 VP9 编码器）、一个 Go 信令服务，以及一个 coturn 中继。当前文档集是 `doc/design/`；`doc/` 下的其余内容都是归档历史。

## 1. 从这里开始

| 目标 | 文档 |
|---|---|
| 产品必须实现什么 | [01 需求（中文）](doc/design/zh-CN/01-requirements.md) |
| 系统形态与部署 | [02 整体架构（中文）](doc/design/zh-CN/02-architecture.md) |
| Android 应用内部结构 | [03 App 架构（中文）](doc/design/zh-CN/03-app-architecture.md) |
| 信令服务内部结构 | [04 信令服务（中文）](doc/design/zh-CN/04-signaling-service.md) |
| 线上协议与 JNI 契约 | [05 协议与交互（中文）](doc/design/zh-CN/05-protocols.md) |
| 端到端通话流程 | [06 关键流程（中文）](doc/design/zh-CN/06-flows.md) |
| 构建、部署与发布 | [07 构建与部署（中文）](doc/design/zh-CN/07-build-and-deploy.md) |
| 历史缺陷与被否决的改法 | [08 问题与解决（中文）](doc/design/zh-CN/08-issues-and-solutions.md) |
| 哪些已验证、哪些没有 | [09 验证与限制（中文）](doc/design/zh-CN/09-verification-and-limitations.md) |
| 症状对应哪段代码 | [10 代码地图（中文）](doc/design/zh-CN/10-code-map.md) |
| 改代码前必读的规范 | [11 编码规范（中文）](doc/design/zh-CN/11-coding-standards.md) |
| 这里如何写文档 | [SPEC.md](doc/design/SPEC.md)（英文唯一规范，不翻译）与 [中文导读](doc/design/zh-CN/SPEC-guide.md) |

## 2. 生成的参考表

四张表由代码生成，禁止手改：[signaling-messages.md](doc/design/_generated/signaling-messages.md)、[log-events.md](doc/design/_generated/log-events.md)、[jni-contract.md](doc/design/_generated/jni-contract.md) 与 [host-commands.md](doc/design/_generated/host-commands.md)。重新生成的命令是 `bash scripts/gen-doc-tables.sh`。

## 3. 构建与部署

构建配方、它自己的门禁、发布链与仅主机侧的发布步骤记录在 [07 构建与部署（中文）](doc/design/zh-CN/07-build-and-deploy.md)。应用构建由 `scripts/build_app.sh` 驱动。

## 4. 编码规范

语言约定、改动安全清单与文档门禁在 [11 编码规范（中文）](doc/design/zh-CN/11-coding-standards.md)。

## 5. 验证

验证矩阵、已知限制与复测方法在 [09 验证与限制（中文）](doc/design/zh-CN/09-verification-and-limitations.md)。文档门禁本身是 `bash scripts/doc-verify.sh`，文档改动被接受之前它必须报告零 failures。冻结的门禁修订与冻结工件集记录在 [reports/54-docs-freeze-manifest.md](reports/54-docs-freeze-manifest.md)。

## 6. 文档索引与历史材料

当前文档集的索引是 [doc/design/README.md](doc/design/README.md)。归档的旧文档，以及旧路径到当前章节的映射，见 [doc/README.md](doc/README.md)。

## 7. 浏览器 Demo

除真机 App 外，本仓库还带一个**纯静态的浏览器视频通话 Demo**（仅桌面 Chrome）：与 App 经同一信令服务真实互拨，全部过程在浏览器控制台与页面面板可见、可导出。

* 上手：宿主机运行 `bash scripts/serve-web-demo.sh start`；在 VSCode PORTS 面板**只转发 8081**；Chrome 打开 `http://localhost:8081/`，点 Create（浏览器建房）或填入 App 给出的 6 位房号后 Join。
* 信令由页面直连 `ws://47.238.144.66:8443/ws`（明文，8443 不需要转发）。
* 面板六区：信令时间线、SDP 原文与解析、ICE 候选与选中候选对、状态机跃迁、1 Hz RTP/RTCP 统计、事件与错误；支持导出 JSON/CSV。
* 运行手册与失败矩阵：[web/README.md](web/README.md)。交付报告与独立验证：[reports/71-browser-call-demo.md](reports/71-browser-call-demo.md)、[reports/72-browser-demo-verification.md](reports/72-browser-demo-verification.md)。
* 已知限制：只支持桌面 Chrome；页面必须以 `http://localhost` 打开；mDNS 候选可能不可解析；TURN relay 端口范围 49152-49200（并发上限 45）。
