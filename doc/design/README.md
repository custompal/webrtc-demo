> **中文（默认）** · [English](README.en.md)

# 文档集 — 索引

> Status: draft · Owner: writer-app · Task: t23
> 文档标准：[SPEC.md](SPEC.md)
> 历史材料与旧路径到当前章节的映射：[../README.md](../README.md)
> 仓库入口：[../../README.md](../../README.md)

## 1. 写作规范

[SPEC.md](SPEC.md) 是规范来源：目录约定、双语术语、引用格式、文档模板、门禁判定规则、变更控制与交付门槛。编辑任何章节之前先读它。SPEC 的读法见 [zh-CN/SPEC-guide.md](zh-CN/SPEC-guide.md)；中文页的语言、切换器与术语规则见 [zh-CN/GLOSSARY.md](zh-CN/GLOSSARY.md)。

## 2. 章节

| # | 文档 | 范围 |
|---|---|---|
| 01 | [zh-CN/01-requirements.md](zh-CN/01-requirements.md) | 功能与非功能需求，每条含引用与状态（中文） |
| 02 | [zh-CN/02-architecture.md](zh-CN/02-architecture.md) | 组件、平面、部署拓扑与文档地图（中文） |
| 03 | [zh-CN/03-app-architecture.md](zh-CN/03-app-architecture.md) | 应用分层、线程模型、生命周期、恢复与开关（中文） |
| 04 | [zh-CN/04-signaling-service.md](zh-CN/04-signaling-service.md) | 服务布局、房间与席位状态机、路由与健康（中文） |
| 05 | [zh-CN/05-protocols.md](zh-CN/05-protocols.md) | 字段级消息、重连策略、ICE/TURN/SDP 与 JNI 契约（中文） |
| 06 | [zh-CN/06-flows.md](zh-CN/06-flows.md) | 带步骤清单与设备日志证据的 Mermaid 流程（中文） |
| 07 | [zh-CN/07-build-and-deploy.md](zh-CN/07-build-and-deploy.md) | 工具链、构建阶段、构建不变量、发布链与主机服务（中文） |
| 08 | [zh-CN/08-issues-and-solutions.md](zh-CN/08-issues-and-solutions.md) | 缺陷史、根因、被否决与被证伪的替代方案（中文） |
| 09 | [zh-CN/09-verification-and-limitations.md](zh-CN/09-verification-and-limitations.md) | 验证矩阵、已知限制与勘误（中文） |
| 10 | [zh-CN/10-code-map.md](zh-CN/10-code-map.md) | 代码地图、报告索引、症状到代码的查找（中文） |
| 11 | [zh-CN/11-coding-standards.md](zh-CN/11-coding-standards.md) | 语言约定、改动安全清单与门禁规则（中文） |
| SPEC | [SPEC.md](SPEC.md) | 文档标准本体（英文唯一规范，不翻译）；读法见[中文导读](zh-CN/SPEC-guide.md) |

## 3. 生成的表格

[signaling-messages.md](_generated/signaling-messages.md)、[log-events.md](_generated/log-events.md)、[jni-contract.md](_generated/jni-contract.md) 与 [host-commands.md](_generated/host-commands.md) 由 `bash scripts/gen-doc-tables.sh` 从代码生成，禁止手改。

## 4. 历史存档

本目录之外、`doc/` 下的所有内容都是归档历史，保留为一行 stub，使报告中的历史引用不致悬空；完整映射见 [../README.md](../README.md)。
