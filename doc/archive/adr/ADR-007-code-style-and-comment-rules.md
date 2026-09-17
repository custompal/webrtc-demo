# ADR-007：代码风格与注释规范（WebRTC 风格 + 中文注释必含）

- 状态：Accepted（2026-09-10）

## 背景
自研代码需与 libwebrtc 风格保持一致（便于读源码、对照官方实现），同时项目以学习为目的，注释需用中文帮助理解。经语音讨论确认两点决定：

1. C++ 代码遵循 WebRTC C++ 代码风格规范（参考 Chromium / Google C++ 风格）。
2. 自研代码必须包含中文注释，英文注释可同步保留，但不可只有英文而无中文。

## 决策

### C++ 代码风格
自研 C++ 代码（`native/` 下所有 `.h/.cc`）遵循 WebRTC C++ 代码风格规范：
- 命名：文件 `snake_case`，类/函数 `CamelCase`，变量 `snake_with_underscores_`，常量 `kCamelCase`。
- 头文件守卫，include 顺序（对应头 → 系统头 → 库 → 本项目）。
- 2 空格缩进，行末左括号，80 字符行宽。
- 不使用 C++ 异常、不使用 `dynamic_cast`（与 libwebrtc 一致）。
- 可用 `clang-format`（WebRTC 的 `.clang-format`）+ `cpplint` 校验。

### 注释语言规范
- 自研 C++ / Kotlin / Go 代码必须包含中文注释。
- 英文注释可同步保留，但不可只有英文而无中文；中文为主，英文为辅。
- 文件头、公共接口函数、关键学习点逻辑、不直观的 API 调用必须用中文注释说明。

## 后果
- 优点：风格与 libwebrtc 一致，对照阅读源码无割裂感；中文注释帮助理解每个 WebRTC 技术点，符合学习目的。
- 缺点：中文注释增加少量工作量；风格约束需用 clang-format/cpplint 校验才能保持一致。
- 范围：仅约束自研代码（`native/`、`app/`、`signaling/`）；`third_party/` 下的 libwebrtc / libvpx 源码不约束。

## 相关
- `05-code-design.md` 第 11 节（完整风格表 + 注释示例）、ADR-006（代码设计层决定）。
