# WebRTC 内部动态码率实现详解

> 本文梳理 libwebrtc 源码中动态码率控制的完整链路，作为自研 VP9 编码器的对照参考。所有源码路径基于 Google libwebrtc 源码树。

## 1. 整条调用链

```
RTCP 反馈 (Receiver → Sender)
  → GoogCcNetworkController::OnRemoteBitrateReport() / OnTransportLossReport() / OnRoundTripTimeUpdate()
  → SendSideBandwidthEstimation 综合延迟+丢包估计
  → GoogCcNetworkController 输出目标带宽 (target_bitrate_bps)
  → BitrateAllocator::OnNetworkEstimateChanged()
  → 按 min/max/priority 分配 → 各 VideoStreamEncoder::OnBitrateUpdated()
  → VideoStreamEncoder::SetRates(RateControlParameters)
  → VP9Encoder::SetRates(RateControlParameters)
  → SetSvcRates() 将 VideoBitrateAllocation 转为 VP9 参数
  → 更新 vpx_codec_enc_cfg_t: ss_target_bitrate / layer_target_bitrate / rc_target_bitrate
  → vpx_codec_enc_config_set() 更新编码器
  → vpx_codec_encode() 按新码率编码
```

## 2. 各环节源码位置与关键类

### 2.1 GCC 拥塞控制（带宽估计）
**目录：`modules/congestion_controller/goog_cc/`**

| 文件 | 类/函数 | 职责 |
|---|---|---|
| `goog_cc_network_control.h/.cc` | `GoogCcNetworkController` | GCC 核心；综合延迟+丢包输出目标带宽 |
| `send_side_bandwidth_estimation.h` | `SendSideBandwidthEstimation` | 发送端带宽估计 |
| `delay_based_bwe.h` | `DelayBasedBwe` | 基于延迟的 BWE |
| `loss_based_bwe_v2.h` | `LossBasedBweV2` | 基于丢包的 BWE |
| `bitrate_estimator.h` | `BitrateEstimator` | 贝叶斯估计吞吐量 |
| `probe_controller.h` | `ProbeController` | 主动探测带宽 |
| `alr_detector.h` | `AlrDetector` | ALR（应用受限）检测 |

**关键入口**：`GoogCcNetworkController::OnRemoteBitrateReport()` / `OnTransportLossReport()` / `OnRoundTripTimeUpdate()` / `OnSentPacket()` / `OnProcessInterval()` — 收到 RTCP 反馈（到达时间、RTT、丢包率）和发送包信息后，计算目标带宽。

### 2.2 BitrateAllocator（码率分配）
**目录：`call/`**

| 文件 | 类/函数 | 职责 |
|---|---|---|
| `bitrate_allocator.h/.cc` | `BitrateAllocator` | 收到带宽估计后分配给各视频流 |
| `bitrate_allocator.h` | `BitrateAllocatorObserver::OnBitrateUpdated()` | 分配结果回调 |

**分配机制**：`BitrateAllocator::OnNetworkEstimateChanged()` 对已注册的各 `AllocatableTrack` 按 `min_bitrate`、`max_bitrate`、`bitrate_priority` 分配。注意：此处分配的是**每个 SendStream 层面的总码率**（bps），不是空间层级别。

### 2.3 VideoBitrateAllocation 结构
**文件：`api/video/video_bitrate_allocation.h/.cc`**

```
VideoBitrateAllocation
├── bitrates_[kMaxSpatialLayers][kMaxTemporalStreams]  // 每空间×时序层码率
├── sum_                                                // 总码率
├── SetBitrate(si, ti, bps)                             // 设置某层码率
├── GetSpatialLayerSum(si)                              // 某空间层总和
├── GetTemporalLayerAllocation(si)                      // 某空间层各时序层
└── GetSimulcastAllocations()                            // 拆分为独立分配
```

这是从 BitrateAllocator 到编码器的**核心数据结构**：一个"每空间层 × 每时序层"的码率矩阵。

### 2.4 VideoBitrateAllocator 策略
**文件：**
- `api/video/builtin_video_bitrate_allocator_factory.cc` — 工厂
- `modules/video_coding/svc/svc_rate_allocator.h` — VP9/AV1 单流 SVC 分配器
- `modules/video_coding/utility/simulcast_rate_allocator.h` — Simulcast 分配器

VP9 单流 SVC 用 `SvcRateAllocator`，将总码率按 SVC 层配置拆分为 `VideoBitrateAllocation`。

### 2.5 VideoStreamEncoder → VideoEncoder::SetRates
**文件：`video/video_stream_encoder.cc`**

`VideoStreamEncoder` 收到 BitrateAllocator 分配的 `VideoBitrateAllocation` 后，封装为 `RateControlParameters`，调 `VideoEncoder::SetRates(RateControlParameters)` 传递给具体编码器。

### 2.6 libwebrtc 内置 VP9 编码器
**文件：`modules/video_coding/codecs/vp9/libvpx_vp9_encoder.h/.cc`**

**类：`LibvpxVp9Encoder`**

**SetRates 内部操作**：
1. 解析 `RateControlParameters` 中的 `VideoBitrateAllocation` 和 `framerate_fps`
2. 调用 `SetSvcRates()` — 将码率分配转化为 VP9 编码参数
3. 通过 `svc_controller_`（`ScalableVideoController`）管理 SVC 层
4. 更新 `vpx_codec_enc_cfg_t` 中的 `ss_target_bitrate[]`、`layer_target_bitrate[]`、`rc_target_bitrate`
5. 调用 `vpx_codec_enc_config_set()` 更新编码器码率

**关键方法**：`SetSvcRates(const VideoBitrateAllocation&)` — 遍历各空间层，根据 `VideoBitrateAllocation` 设置 VP9 的空间层目标码率。

### 2.7 libvpx VP9 码率控制
**文件：`third_party/libvpx/`（libwebrtc 内嵌）**

| vpx_codec_enc_cfg_t 字段 | 含义 |
|---|---|
| `ss_target_bitrate[i]` | 第 i 个空间层目标码率 |
| `layer_target_bitrate[k]` | 第 k 个时序层累计目标码率 |
| `rc_target_bitrate` | 总体目标码率 |

`vpx_codec_enc_config_set(&codec, &cfg)` 更新编码器配置，后续 `vpx_codec_encode()` 按新码率编码。

## 3. 自研 vs 加日志：哪种学得更深？

你在语音讨论里问的核心问题："WebRTC 内部已有 VP9 编码，为什么还要自研？加日志能不能达到同样效果？"

### 3.1 加日志能学到什么
在 libwebrtc 源码的 `SetSvcRates()` / `vpx_codec_enc_config_set()` 前后加 `RTC_LOG`，能观察到：
- GCC 输出的目标带宽值
- `VideoBitrateAllocation` 每层码率分配
- vpx 配置更新前后的 `ss_target_bitrate` / `rc_target_bitrate`
- 实际编码输出码率（通过 getStats）

**价值**：能看到"输入 → 输出"的数据流，理解参数怎么传递。

### 3.2 加日志学不到什么
- **控制权缺失**：你不能改码率分配策略（如手动给某层多分、少分），只能看不能动。
- **细节被封装**：`SetSvcRates()` 内部的分配逻辑、SVC controller 与 vpx 配置的映射关系，加日志只能看到结果，看不到"为什么这么分"的推导。
- **调试受限**：如果想验证"如果把某层码率调到极低会怎样"，改不了参数，只能观察自然发生的情况。
- **对 vpx 的理解停在表面**：你看到 `ss_target_bitrate` 被赋值，但不会亲手处理 `layer_target_bitrate` 的累计关系、帧率与码率的交互。

### 3.3 自研能额外学到什么
- **亲手实现分配逻辑**：自己解析 `VideoBitrateAllocation`，决定怎么把码率分到各空间/时序层，理解分配的"为什么"。
- **可实验**：可以改策略（平均分、按权重分、给基础层优先），对比效果。
- **vpx 闭环**：亲手调 `vpx_codec_enc_config_set`、亲手处理 `ss_target_bitrate` / `layer_target_bitrate` 的累计关系，理解帧率/分辨率/码率的交互。
- **对照官方实现**：写完自己的，读 `LibvpxVp9Encoder::SetSvcRates()` 对照，看官方怎么处理，差异在哪。

### 3.4 结论
| 维度 | 加日志 | 自研 |
|---|---|---|
| 看到参数传递 | 能 | 能 |
| 改分配策略 | 不能 | 能 |
| 理解"为什么这么分" | 浅 | 深 |
| 可做对比实验 | 不能 | 能 |
| 对照官方实现 | — | 能 |
| 工作量 | 小 | 中（1-2 周） |

**建议**：两者不互斥。最优学习路径 = **自研 VP9 编码器 + 读官方 `LibvpxVp9Encoder` 源码对照 + 在 GCC 关键节点加日志观察带宽估计**。自研编码器学"分配+码率控制"的动手能力，GCC 加日志学"带宽估计"的观察能力——各学各的，互为补充。

## 4. 学法建议（对照源码阅读路线）

| 学习目标 | 读哪些源码 | 怎么学 |
|---|---|---|
| GCC 带宽估计 | `modules/congestion_controller/goog_cc/` | 加日志观察目标带宽变化 + 读 `GoogCcNetworkController` |
| 码率分配 | `call/bitrate_allocator.cc`、`modules/video_coding/svc/svc_rate_allocator.cc` | 读源码 + getStats 对比 |
| SetRates 接口 | `api/video_codecs/video_encoder.h` | 自研实现 + 对照 |
| VP9 SVC 码率控制 | `modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc` 的 `SetSvcRates()` | 自研后对照读 |
| vpx 编码配置 | `third_party/libvpx/` 的 `vpx_codec_enc_config_t` | 自研中动手处理 |

## 5. 信息来源
- [WebRTC 码率分配全流程深度解析](https://blog.csdn.net/qq_39267110/article/details/160794538)
- [WebRTC 带宽评估与码率控制深度剖析](https://blog.csdn.net/qq_39267110/article/details/149005913)
- [libwebrtc video_stream_encoder.cc](https://github.com/webrtc-uwp/webrtc/blob/master/video/video_stream_encoder.cc)
- [libwebrtc video_bitrate_allocation.cc](https://raw.githubusercontent.com/mozilla-firefox/firefox/main/third_party/libwebrtc/api/video/video_bitrate_allocation.cc)
- [libwebrtc BitrateAllocator](https://raw.githubusercontent.com/mozilla-firefox/firefox/main/third_party/libwebrtc/call/bitrate_allocator.h)
- [libwebrtc GoogCcNetworkController](https://raw.githubusercontent.com/mozilla-firefox/firefox/main/third_party/libwebrtc/modules/congestion_controller/goog_cc/goog_cc_network_control.h)
- [libwebrtc VP9Encoder](https://raw.githubusercontent.com/mozilla-firefox/firefox/main/third_party/libwebrtc/modules/video_coding/codecs/vp9/libvpx_vp9_encoder.h)
- [libwebrtc BuiltinVideoBitrateAllocatorFactory](https://raw.githubusercontent.com/mozilla-firefox/firefox/main/third_party/libwebrtc/api/video/builtin_video_bitrate_allocator_factory.cc)
- [libwebrtc VideoEncoder::SetRates](https://github.com/JumpingYang001/webrtc/blob/master/api/video_codecs/video_encoder.h)
- [libvpx VP9 编码器](https://github.com/webmproject/libvpx/blob/main/vp9/encoder/vp9_encoder.h)
