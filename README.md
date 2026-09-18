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
| 端到端通话流程 | [06-flows.md](doc/design/06-flows.md)（待翻译（planned）） |
| 构建、部署与发布 | [07-build-and-deploy.md](doc/design/07-build-and-deploy.md)（待翻译（planned）） |
| 历史缺陷与被否决的改法 | [08-issues-and-solutions.md](doc/design/08-issues-and-solutions.md)（待翻译（planned）） |
| 哪些已验证、哪些没有 | [09-verification-and-limitations.md](doc/design/09-verification-and-limitations.md)（待翻译（planned）） |
| 症状对应哪段代码 | [10-code-map.md](doc/design/10-code-map.md)（待翻译（planned）） |
| 改代码前必读的规范 | [11-coding-standards.md](doc/design/11-coding-standards.md)（待翻译（planned）） |
| 这里如何写文档 | [SPEC.md](doc/design/SPEC.md)（英文唯一规范，不翻译）与 [中文导读](doc/design/zh-CN/SPEC-guide.md) |

## 2. 生成的参考表

四张表由代码生成，禁止手改：[signaling-messages.md](doc/design/_generated/signaling-messages.md)、[log-events.md](doc/design/_generated/log-events.md)、[jni-contract.md](doc/design/_generated/jni-contract.md) 与 [host-commands.md](doc/design/_generated/host-commands.md)。重新生成的命令是 `bash scripts/gen-doc-tables.sh`。

## 3. 构建与部署

构建配方、它自己的门禁、发布链与仅主机侧的发布步骤记录在 [07-build-and-deploy.md](doc/design/07-build-and-deploy.md)（待翻译（planned））。应用构建由 `scripts/build_app.sh` 驱动。

## 4. 编码规范

语言约定、改动安全清单与文档门禁在 [11-coding-standards.md](doc/design/11-coding-standards.md)（待翻译（planned））。

## 5. 验证

验证矩阵、已知限制与复测方法在 [09-verification-and-limitations.md](doc/design/09-verification-and-limitations.md)（待翻译（planned））。文档门禁本身是 `bash scripts/doc-verify.sh`，文档改动被接受之前它必须报告零 failures。冻结的门禁修订与冻结工件集记录在 [reports/54-docs-freeze-manifest.md](reports/54-docs-freeze-manifest.md)。

## 6. 文档索引与历史材料

当前文档集的索引是 [doc/design/README.md](doc/design/README.md)。归档的旧文档，以及旧路径到当前章节的映射，见 [doc/README.md](doc/README.md)。
