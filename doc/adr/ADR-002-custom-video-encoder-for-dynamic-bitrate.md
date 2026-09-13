# ADR-002：自研 VideoEncoder 封装 libvpx VP9（动态码率 + SVC 分层）

- 状态：Accepted（2026-09-10 修订，原 VP8 → VP9）

## 背景
全栈自研不可行（见 ADR-001），但仍想“动手”学一项核心技术。视频编码动态码率是 `GCC → BitrateAllocator → VideoEncoder.SetRates` 闭环的可见末端，且 libwebrtc 暴露 `VideoEncoder` / `VideoEncoderFactory` 扩展点，可插入自研编码器而不动其余栈。

在 VP8 与 VP9 之间选择：
- VP8：SVC 支持弱，`SetRates` 基本是单层 `rc_target_bitrate`，学不到分层码率分配。
- VP9：原生支持空间/时序分层（SVC），`SetRates` 收 `VideoBitrateAllocation`（每空间/时序层码率矩阵），libvpx VP9 有 `ss_target_bitrate[]`/`layer_target_bitrate[]`，能学“分层码率分配”——现代视频会议（simulcast/SVC）的核心机制。且 libwebrtc 自带 VP9 编码器实现可对照。

## 决策
- 自研 C++ `VideoEncoder` 封装 libvpx VP9，注册进 `VideoEncoderFactory`。
- `SetRates(VideoBitrateAllocation)` 解析每空间/时序层码率，更新 vpx `ss_target_bitrate[]`/`layer_target_bitrate[]`/`rc_target_bitrate`，再 `vpx_codec_enc_config_set`，后续 `vpx_codec_encode` 按新码率编码。
- 不写 VP9 算法本身（用 libvpx 的 `vpx_codec_encode`）。

## 动态码率实现位置（闭环）
```
libwebrtc GCC 估带宽
  → BitrateAllocator
  → VideoEncoder::SetRates(VideoBitrateAllocation)   ← 你实现这里
       解析 rates.GetSpatialLayer(i).GetBitrateBps(j)
  → 更新 vpx_codec_enc_config_t:
       ss_target_bitrate[i]     = 空间层 i 目标码率
       layer_target_bitrate[k]  = 时序层 k 累计码率
       rc_target_bitrate        = 总码率
  → vpx_codec_enc_config_set(&codec, &cfg)
  → vpx_codec_encode() 按新码率编码
```

## 后果
- 优点：亲手实现“动态码率 + SVC 分层码率分配”闭环；可对照官方 VP9 实现；能观察 GCC 估带宽 → 分层分配 → 编码输出整条链。
- 缺点：VP9 软编 CPU 开销比 VP8 高约 1.5-2x（移动端软编更吃力）；libvpx VP9 API 比 VP8 复杂，实现工作量略增；GCC 估计算法本身仍靠读源码 + getStats 学，不靠动手实现。
- 边界：解码侧用 libwebrtc 内置 MediaCodec 硬解，不自研。

## 相关
- ADR-001（路径基调）、`02-architecture.md`（自研模块边界 + 数据流第 6 步）。
