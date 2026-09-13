# 实现路线（分阶段）

## 阶段 0：环境准备（约 1 天）
- 开腾讯轻量香港 + 配安全组（UDP 3478 / 49152-49200，TCP 443/8443）。
- 开 Hetzner SG VM（CPX32）。
- Hetzner VM：装 depot_tools、Android NDK、JDK、libvpx 源码。
- Windows：Android Studio + NDK + CMake。

## 阶段 1：编译 libwebrtc Android（Hetzner VM，4-8h）
- `fetch --nohooks webrtc_android` → `gclient sync`（约 50GB）。
- `gn gen out/arm64 --args='target_os="android" target_cpu="arm64" is_debug=false treat_warnings_as_errors=false'`。
- `ninja -C out/arm64 ...`（先编静态库 + 必要组件）。
- 打包 `.a` + 头文件，拷回 Windows。

## 阶段 2：Go 信令服务（约 0.5 天）
- WebSocket `/signal`；消息 `create/join/leave/offer/answer/ice`。
- room = 6 位码、上限 2 人；`createRoom` 响应带 `stunUrl/turnUrl/user/pass`。
- 编译单二进制，systemd 部署到腾讯轻量。
- coturn 配好，测 STUN/TURN 通。

## 阶段 3：客户端骨架（2-3 天）
- Android Compose UI：发起 / 加入 / 本端预览 / 远端 SurfaceView。
- JNI 薄层：PeerConnection 生命周期、SDP/ICE 收发。
- 用 libwebrtc 内置采集器 + MediaCodec 解码跑通“1:1 默认编码器”通话（先用默认编码器验证链路，再换自研编码器）。

## 阶段 4：自研 VideoEncoder（VP9 动态码率 + SVC 分层，约 1-2 周）
- C++ 实现 `VideoEncoder` 接口，封装 libvpx VP9。
- `VideoEncoderFactory` 注册自研编码器。
- `SetRates(VideoBitrateAllocation)` → 解析每空间/时序层码率 → 更新 vpx `ss_target_bitrate[]`/`layer_target_bitrate[]`/`rc_target_bitrate` → `vpx_codec_enc_config_set` → 后续 `vpx_codec_encode` 按新码率编码。
- 在丢包 / 限速场景验证动态码率生效（getStats outbound bitrate + 编码器日志 + 分层码率曲线）。
- 可对照 libwebrtc 官方 VP9 编码器实现（`modules/video_coding/codecs/vp9/`）。

## 阶段 5：ICE 全量观察 + UI（3-5 天）
- `PeerConnectionObserver` 全回调投 UI：`onIceCandidate` / `onIceConnectionChange` / `onIceCandidatePairChanged`。
- getStats 拉取 candidate pair、selected pair、P2P/RELAY 标注。
- ICE 事件列表缓冲，通话结束才清空。

## 阶段 6：NAT 探测（3-5 天）
- 自研 RFC 5780 STUN 测试：变更 IP / 端口行为，判定 Full-Cone / Restricted / Symmetric 等。
- 结果投 UI（本端）；对端 NAT 类型经信令交换探测结果。

## 阶段 7：内部学习与读源码（贯穿）
- GCC：读 `modules/congestion_controller/gcc/` + getStats 带宽曲线。
- NACK/FEC：读 `modules/rtp_rtcp/` + 丢包重传统计。
- DTLS/SRTP：读 `rtc_base/openssl_*`、`pc/`。
- 各技术写一页“读码笔记”进 `doc/notes/`（可选）。

## 阶段 8：联调与压测
- 弱网 / 限速 / 丢包场景验证：P2P 失败 → RELAY、GCC 降码率、NACK 重传。
- UI 全量回放。

## 每阶段产出
| 阶段 | 产出 |
|---|---|
| 0-1 | libwebrtc `.a` + 头文件（arm64） |
| 2 | Go 信令二进制 + coturn 配置 |
| 3 | Android APK（1:1 通话跑通，默认编码器） |
| 4 | 自研 VP9 编码器集成 APK（动态码率 + SVC 分层可观察） |
| 5 | ICE 全量事件 UI |
| 6 | NAT 类型探测与展示 |
| 7 | 读码笔记 |
| 8 | 弱网压测报告 |

## 验收点（对应学习目标）
- ICE/STUN/TURN：candidate 全过程可见，P2P 与 RELAY 路径均能复现。
- GCC/动态码率：限速场景下 getStats 带宽估计与 vpx 输出码率同步下降。
- NACK/FEC：丢包场景下重传统计 / 冗余包统计可见。
- NAT 类型：本端 / 对端均能判出类型。
- RTP/RTCP/DTLS/SRTP：通过读码笔记 + getStats 证书/传输统计理解链路。
