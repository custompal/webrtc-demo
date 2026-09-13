# ADR-006：代码设计层决定（单仓 / 预编译 / 手写 JNI / systemd）

- 状态：Accepted（2026-09-10）

## 背景
架构与云方案锁定后，代码层有四个关键选择：仓组织、libwebrtc 集成方式、JNI 方式、部署形态。

## 决策
1. **单仓多模块**：app/native/signaling/third_party/scripts 同一 git 仓。跨模块改动原子提交、引用方便，学习项目最适。
2. **libwebrtc 预编译产物 + 头文件**：云主机 depot_tools 编出 `.a` + 头，scp 进 `third_party/libwebrtc/`，CMake 直接链接。不引源码 submodule（AGP 配置复杂、Windows 构建负担重）。
3. **手写 JNI**：`JNI_OnLoad` 里 `FindClass + RegisterNatives` 注册，不用 libwebrtc JNI Generator（它是内部设计，多一层工具链）。手写更透、边界更清，符合"学内部"。
4. **systemd 直接跑**：信令 + coturn 各一 systemd unit，不引 Docker（多一层运行时，学习 demo 非必要）。

## 附带决定
- libwebrtc 锁定 M_release 分支（可复现，与读码笔记对齐）。
- native 用 CMake + AGP externalNativeBuild，abiFilters = arm64-v8a。
- 信令 JSON 文本帧（WebSocket /signal），消息类型 create/join/offer/answer/ice/natType/leave。
- NAT 探测 = 独立 socket 模块直实现 RFC5780，不经 PeerConnection，学 STUN 协议本身。
- 代码风格与注释规范见 ADR-007。

## 后果
- 优点：工程边界清晰；构建可复现；JNI 完全可控；部署最简。
- 缺点：单仓体积大（含 third_party 预编译产物，建议 .gitignore 大文件或 LFS）；手写 JNI 工作量在；systemd 无隔离（学习 demo 可接受）。
- 风险：third_party 的 `.a` 文件大，git 仓臃肿——建议 .gitignore 掉 third_party 产物，用脚本从云主机重新拉取，或用 git-lfs。

## 相关
- `05-code-design.md`（完整代码设计）、`02-architecture.md`（架构层）。
