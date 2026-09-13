# ADR-001：采用 libwebrtc C++ 库并从源码编译

- 状态：Accepted（2026-09-09）
- 决策者：用户 + 拷问共识

## 背景
目的是学透 WebRTC 内部（ICE / GCC / NACK / FEC / RTP / RTCP / DTLS / SRTP）。候选路径：
1. 从零自研栈——学得最深，但工作量数月起、风险极高。
2. 用预编译 libwebrtc——快，但黑盒，学不到内部。
3. 从源码编译 libwebrtc——可运行 + 可读源码 + 可加日志 / 打补丁 + getStats 观察。

## 决策
采用 libwebrtc C++ 原生 API，并从 Google 源码用 depot_tools 编译。

## 后果
- 优点：运行真实栈；可读 GCC / NACK / FEC / SRTP 真实实现；可加日志 / 打补丁做实验；getStats / observer 观察行为；工程可行。
- 缺点：构建重（约 50GB、数小时、需 Linux 构建机）；内部不可整体“替换”为自研（要替换则回到自研混合路径，本方案仅替编码器，见 ADR-002）。
- 取舍：不重写 GCC / NACK / FEC / SRTP，靠“读 + 观察”学，不靠“动手实现”学。

## 相关
- ADR-002（仅编码器动手实现）、ADR-004（构建机选型）。
