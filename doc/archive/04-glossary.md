> **Archived 2026-09-17** — superseded by `doc/design/`; archived verbatim as `doc/archive/04-glossary.md`. This copy is history, not current guidance.

# 术语表

> 每条含中文释义 + 在本项目的学法。

## 信令与协商
- **SDP (Session Description Protocol)**：描述媒体能力 / 编解码 / 传输的会话描述，经 offer/answer 交换。学法：信令日志。
- **信令 (Signaling)**：交换 SDP 与 ICE 候选的控制通道，本项目用 Go WebSocket 自研。

## NAT 穿透
- **ICE (Interactive Connectivity Establishment)**：用 STUN/TURN 候选地址建立端到端连接的框架。学法：observer 观察 + 读 `pc/`、`modules/`。
- **STUN (Session Traversal Utilities for NAT)**：经 NAT 取得公网映射地址(srflx)。学法：coturn + candidate 变化。
- **TURN (Traversal Using Relays around NAT)**：NAT 穿透失败时经中继转发(relay)。学法：coturn 日志 + candidate 变 relay。
- **P2P 穿透**：两端直连不经中继。判定：selected pair = host/srflx。
- **RELAY**：经 TURN 中继。判定：selected pair = relay。
- **candidate 类型**：host(本机) / srflx(STUN 公网映射) / relay(TURN 中继) / prflx(对端反射)。
- **candidate pair / selected pair**：本地 + 对端 candidate 组成的连通候选对；ICE 最终选中的为 selected pair。
- **RFC 5780**：NAT 行为发现，判定 NAT 类型（Full-Cone / Restricted / Symmetric 等）。本项目自研 mini 测试。

## 媒体传输与安全
- **RTP (Real-time Transport Protocol)**：承载媒体实时传输。读 `modules/rtp_rtcp/`。
- **RTCP (RTP Control Protocol)**：RTP 的控制 / 反馈（丢包、带宽、统计）。读 `modules/rtp_rtcp/`。
- **SRTP (Secure RTP)**：加密的 RTP，用 DTLS 协商的密钥。读 `pc/`、`rtc_base/`。
- **DTLS (Datagram TLS)**：UDP 上的 TLS，协商密钥。读 `rtc_base/openssl_*`。

## 拥塞控制与可靠性
- **GCC (Google Congestion Control)**：基于丢包 / 时延的带宽估计算法。读 `modules/congestion_controller/gcc/`，getStats 看带宽估计。
- **NACK (Negative Acknowledgment)**：丢包负确认请求重传。读 `modules/rtp_rtcp/`。
- **FEC (Forward Error Correction)**：前向纠错冗余包。读 `modules/rtp_rtcp/`。

## 编解码与本项目扩展点
- **VideoEncoder / SetRates / VideoBitrateAllocation**：libwebrtc 编码器接口与码率分配结构；`SetRates` 收 `VideoBitrateAllocation`（每空间/时序层码率矩阵）。本项目动手实现。
- **VideoEncoderFactory**：注册自研编码器到 libwebrtc 的工厂接口。
- **libvpx**：VP8/VP9 编解码库，本项目用它实现自研 VP9 编码器。
- **SVC (Scalable Video Coding)**：可伸缩视频编码，分空间层（分辨率）/时序层（帧率），每层独立码率。VP9 原生支持，本项目在 `SetRates` 学分层码率分配。
- **ss_target_bitrate / layer_target_bitrate**：libvpx VP9 每空间层 / 每时序层累计目标码率，本项目在 `SetRates` 中更新。
- **MediaCodec**：Android 硬件编解码 API；本项目用 libwebrtc 内置的 MediaCodec 解码器。
- **Camera2Capturer / JavaAudioDeviceModule**：libwebrtc Android 内置的相机 / 麦克风采集器。
- **SurfaceTextureHelper**：libwebrtc Android 渲染到 Surface 的辅助组件。

## 统计与观察
- **getStats**：libwebrtc 运行时统计接口，给 candidate pair、bitrate、可用带宽、证书等。本项目主要观察手段。
- **PeerConnectionObserver**：PeerConnection 回调接口（ICE 事件、连接状态、candidate pair 变化等）。
