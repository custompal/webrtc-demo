> **Archived 2026-09-17** — superseded by `doc/design/`; archived verbatim as `doc/archive/00-overview.md`. This copy is history, not current guidance.

# WebRTC 视频通话学习 Demo — 项目概述

## 目标
搭建一个 Android 1:1 视频通话 demo：发起会议生成会议 ID，他人输入 ID 加入即可视频通话。通过“实现 + 阅读 + 观察”三种方式学透 WebRTC 内部技术：ICE、STUN、TURN、P2P 穿透、视频编码动态码率、GCC 拥塞控制、NACK、FEC、RTP、RTCP、DTLS、SRTP。

## 核心权衡（一句话）
用 libwebrtc 的 C++ 库作为“可运行、可观察、可读源码”的平台；唯一动手实现层是视频编码器（学动态码率闭环，VP9 含分层码率分配）；其余内部技术靠 getStats/observer 观察 + 读源码学习，不重写。

## 技术路径
- libwebrtc C++ 原生 API，Google 源码 depot_tools 编译（可读 / 可加日志 / 可打补丁）。
- 自研 C++ `VideoEncoder` 封装 libvpx(VP9)，经 `SetRates()` 解析 `VideoBitrateAllocation` 的每空间/时序层码率，更新 `ss_target_bitrate[]`/`layer_target_bitrate[]`/`rc_target_bitrate`，学“编码侧动态码率 + 分层码率分配(SVC)”闭环。
- 解码 = libwebrtc 内置 Android MediaCodec 硬解；采集 = libwebrtc 内置 Camera2Capturer + JavaAudioDeviceModule + SurfaceTextureHelper（app 层）。
- NAT 类型 = 自研 RFC 5780 mini STUN 测试客户端，判定 Full-Cone / Symmetric 等。

## 会议 / 信令
- 会议 ID = 6 位随机码、无鉴权、上限 2 人（1:1）。
- 信令 = Go 单二进制 WebSocket 服务，按 room id 中继 SDP offer/answer + ICE 候选。

## UI（Jetpack Compose + SurfaceView）
- 发起 / 加入、本端预览、远端视频。
- ICE 全量过程列表（候选收集 + candidate pair 状态 + selected pair 标注 P2P host/srflx / RELAY），通话结束才清空。
- 展示本端 / 对端 NAT 类型、传输模式（P2P/RELAY）、当前传输速率（getStats 定时拉）。

## 云服务器（2026-09 实时价，详见 01-cloud-infra.md）
- 单台国内/香港大档云主机（~8C8G/100GB+，~¥100+/周），既编译 libwebrtc Android 又跑 coturn(STUN+TURN) + Go 信令，省去编译后拷贝部署。
- 注意：原 ¥50 预算不足以单台扛编译，已提到约 ¥100-112/周（详见 `adr/ADR-004.md`）。

## 学习映射表
| 技术 | 学法 | 代码 / 数据位置 |
|---|---|---|
| ICE / STUN / TURN / P2P 穿透 | observer+getStats 观察 + 读源码 + 自研 RFC5780 NAT 测试 | `pc/`、`modules/`、自研模块 |
| GCC 拥塞控制 | getStats 带宽估计 + 读源码 | `modules/congestion_controller/gcc/` |
| NACK 负确认重传 | 丢包 / 重传统计 + 读源码 | `modules/rtp_rtcp/` |
| FEC 前向纠错 | getStats + 读源码 | `modules/rtp_rtcp/` |
| RTP / RTCP | 读源码 + getStats 传输统计 | `modules/rtp_rtcp/` |
| DTLS | 读源码 | `rtc_base/openssl_*`、`pc/dtls_srtp_*` |
| SRTP | 读源码 | `pc/`、`rtc_base/` |
| 视频编码动态码率 | 动手实现 `VideoEncoder::SetRates` 闭环（VP9 含 SVC 分层码率分配） | 自研编码器 + libvpx |

## 文档目录
- `01-cloud-infra.md` — 云方案、coturn、信令部署、成本、操作清单
- `02-architecture.md` — 系统架构、组件、数据流、JNI 界面
- `03-implementation-plan.md` — 分阶段实现路线
- `04-glossary.md` — 术语表
- `05-code-design.md` — 代码设计：目录、构建、技术栈、模块、协议、部署
- `06-webrtc-dynamic-bitrate-internals.md` — WebRTC 内部动态码率实现详解（源码路径 + 自研vs加日志对比）
- `adr/` — 架构决策记录（ADR-001 ~ ADR-007）
