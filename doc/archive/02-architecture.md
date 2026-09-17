> **Archived 2026-09-17** — superseded by `doc/design/`; archived verbatim as `doc/archive/02-architecture.md`. This copy is history, not current guidance.

# 系统架构

## 1. 分层
```
┌─────────────────────────────────────────┐
│  Android App（Kotlin / Jetpack Compose） │  UI、采集回调、状态展示
│   - 发起/加入、本端预览、远端 SurfaceView │
│   - ICE 事件列表、NAT 类型、速率面板        │
├─────────────── JNI ─────────────────────┤
│  JNI 薄层（C++）                           │  Java ↔ C++ 边界
├─────────────────────────────────────────┤
│  自研模块（C++）                           │
│   - VideoEncoder(libvpx VP9)              │  动手实现动态码率 + SVC 分层码率分配
│   - NAT 探测(RFC5780 mini STUN)           │
├─────────────────────────────────────────┤
│  libwebrtc C++（源码编译，可读可改）         │
│   PeerConnection / ICE / RTP / RTCP      │
│   GCC / NACK / FEC / DTLS / SRTP         │  观察 + 读源码
│   AndroidVideoDecoder(MediaCodec 硬解)   │
│   Camera2Capturer / AudioDeviceModule     │
└─────────────────────────────────────────┘
        ▲ 信令（WebSocket）        ▲ 媒体（SRTP over UDP）
        │                         │
┌─────────────── 单台云主机 ─────────────────┐
│  Go 信令服务（room / SDP / ICE 中继）      │
│  coturn（STUN+TURN）                      │
│  + 编译环境（depot_tools/NDK/libvpx）       │
└─────────────────────────────────────────┘
```

## 2. 组件
- **App 上层（Kotlin/Compose）**：UI 交互、相机 / 麦克风采集器绑定（libwebrtc 内置）、状态面板。
- **JNI 薄层（C++）**：封装 PeerConnection 操作与回调；把 ICE 事件 / getStats / 编码器码率回调投到 Java。
- **自研 VideoEncoder（C++）**：实现 libwebrtc `VideoEncoder` 接口，封装 libvpx VP9；`SetRates(VideoBitrateAllocation)` 解析每空间/时序层码率，更新 vpx `ss_target_bitrate[]`/`layer_target_bitrate[]`/`rc_target_bitrate`；注册进 `VideoEncoderFactory`。
- **自研 NAT 探测（C++）**：RFC 5780 STUN 行为测试，判定 NAT 类型，结果经 JNI 投 UI。
- **libwebrtc（C++ 源码编译）**：PeerConnection/ICE/RTP/RTCP/GCC/NACK/FEC/DTLS/SRTP + Android MediaCodec 解码器 + 采集器。
- **Go 信令（云）**：WebSocket，room 管理，SDP/ICE 中继，下发 coturn 配置。
- **coturn（云）**：STUN + TURN。

## 3. 媒体与信令数据流（1:1 建连）
1. A 发起会议 → 信令建 room=123456，B 输入 ID 加入。
2. A `createOffer` → SDP offer → 信令 → B；B `setRemoteDescription` → `createAnswer` → 信令 → A。
3. 双方生成 ICE candidate → 信令互发 → `addIceCandidate`。
4. ICE agent 收集 host/srflx/relay candidate，连通性检查，选中 selected pair（host/srflx=P2P，relay=RELAY）。
5. DTLS 握手 → SRTP 协商；RTP 传视频 / 音频，RTCP 反馈（NACK / FEC / 带宽估计）。
6. GCC 估带宽 → BitrateAllocator → `VideoEncoder.SetRates(VideoBitrateAllocation)` → 解析每空间/时序层码率 → 更新 vpx `ss_target_bitrate[]`/`layer_target_bitrate[]`/`rc_target_bitrate` → VP9 按新码率编码（动态码率 + SVC 分层闭环，你在此观察 / 动手）。

## 4. JNI 界面（关键回调跨边界）
**Java → C++**：
`createPeerConnection` / `createOffer` / `createAnswer` / `setLocalDescription` / `setRemoteDescription` / `addIceCandidate` / `toggleMute` / `hangup` / `startNatTest`。

**C++ → Java（observer）**：
`onIceCandidate` / `onIceConnectionChange` / `onIceCandidatePairChanged` / `onConnectionChange` / `onStatsReport` / `onBitrateChanged`（编码器码率回调）/ `onNatTypeDetected` / `onRemoteVideoFrame`（渲染回调）。

## 5. 展示数据来源
- **NAT 类型**：自研 RFC5780 探测 + libwebrtc candidate 类型。
- **P2P / RELAY**：getStats `RTCIceCandidatePairStats.selected` + local/remote candidate type（host/srflx=PEER，relay=RELAY）。
- **传输速率**：getStats outbound/inbound bitrate + available send/recv bandwidth。
- **ICE 全量过程**：`PeerConnectionObserver` 全序列 + candidate pair 状态，app 端缓冲到通话结束才清空。

## 6. 模块边界原则
- libwebrtc 栈不动其内部实现（GCC/NACK/FEC/SRTP 靠“读 + 观察”，不重写）。
- 唯一“替换 / 插入”libwebrtc 的点 = `VideoEncoder`（自研），其余均通过 API / observer / getStats 接入。
- 采集与解码用 libwebrtc 内置，不自研，聚焦学习精力在“动态码率 + ICE/NAT 观察”。
