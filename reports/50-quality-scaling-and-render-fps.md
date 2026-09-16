# reports/50-quality-scaling-and-render-fps.md —— 观感卡顿跟进：质量降级 / 渲染 fps / ~20fps 上限归因（t91）

- 任务：t91（native-dev）。**本轮只写本报告，未改任何代码**（`ScalingSettings(24,37)` 与 `reports/49 §8/§9` 已由 captain 提交在 `da6c025`；t90 交付锚点 `0f60d901…cafe45`）。
- 证据：t86 版真机日志 `/opt/dsh-workspaces/tmp/n4/x`（Mi 10 Pro）、`n5/x`（Xiaomi 24117RK2CC）↔ 容器 `/data/dsh/home/workspace/tmp/n4/x`、`tmp/n5/x`；**埋点在 `native*.log`**，libwebrtc 侧在 `webrtc*.log`，渲染在 `eglrenderer` 行。
- `app/src` 最后写入时刻 = **2026-09-16 20:15:38.476**（t90 记录的 T1；本轮 t91 未写 `app/src`，仅本报告）。

---

## 1. 摘要

| # | 问题 | 结论（一句话） | 处置 |
| --- | --- | --- | --- |
| ① | 质量降级策略缺失 | `ScalingSettings.OFF` 使 libwebrtc 移除 `QualityScalerResource`，拥塞时只能饿码率 | **已启用 `ScalingSettings(24, 37)`**（`Vp9VideoEncoder.kt:89-90`/`:339-340`，提交 `da6c025`）；真机判据见 §2.3 |
| ② | 远端渲染 fps 7.0–17.5 | **不是渲染侧损耗**：每个 4 s 窗口 `Dropped=0` 且 `Rendered == Frames received`，渲染耗时 0.53–1.36 ms；损耗发生在**接收/解码侧（丢包）** | 无需改代码；`RenderQueue` 仅 0–7，`Receiver` 丢帧 35/73/84 + 丢包 1/2/18% |
| ③ | ~20 fps 上限是谁压的 | **不是采集侧**（`DroppedFrames.Capturer=0`）；是 **libwebrtc 按可用码率/拥塞做的降级与 FrameDropper 丢帧**（`VideoStreamAdapter {res=2, fps=0}` + `kLimitReached` + `OnBitrateUpdated packet_loss 0–24%`） | 可提升空间已用 ①+t89 地板+t85 多线程覆盖；采集侧无需改 |

---

## 2. ① 质量降级策略：**启用**（决策依据 + 真机可观测影响）

### 2.1 问题与依据

- 旧行为：`getScalingSettings()` 返回 `VideoEncoder.ScalingSettings.OFF` ⇒ libwebrtc 打
  `Removing resource "QualityScalerResource"`，拥塞时没有 QP 侧的平滑降级，只能把码率饿到地板
  （n5：`encoder_rate_floor` 触发 **68** 次、`requested_bps` p10 **12 670**）。
- 启用依据：① 我们已把 libvpx 的真实 QP 回传（`setQp(meta[5])`）；② libwebrtc 自带软编 VP9 同构——
  `modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc:1897` 在可缩放时给
  `VideoEncoder::ScalingSettings(low, high)`，否则 `kOff`；③ Java 侧构造存在
  `sdk/android/api/org/webrtc/VideoEncoder.java:139` `ScalingSettings(int low, int high)`。
- 阈值来源（如实）：本 checkout 全树 grep **没有** `kLowQpThreshold/kHighQpThreshold` 常量，
  24/37 取 libwebrtc 生态（H.264/MediaCodec 实现）惯用的一对值；如真机表现过激/过缓，改这两个常量即可。

### 2.2 当前代码（已提交 `da6c025`）

| 位置 | 值 |
| --- | --- |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:89-90` | `LOW_QP_THRESHOLD = 24`、`HIGH_QP_THRESHOLD = 37` |
| 同文件 `:339-340` | `getScalingSettings() = VideoEncoder.ScalingSettings(24, 37)`（不再是 `OFF`） |
| 回退（单行） | 改回 `VideoEncoder.ScalingSettings.OFF` 即恢复原状 |

### 2.3 真机可观测影响（复测看点）

| 键 | 启用前（n4/n5） | 启用后期望 |
| --- | --- | --- |
| `webrtc*.log` `Removing resource "QualityScalerResource"` | 出现（n4 2 次 / n5 4 次） | **应消失**（资源被保留） |
| `QualityScalerResource signalled kOveruse/kUnderuse` | 仅 `kOveruse/kUnderuse Not adapting … kLimitReached` | 应出现 `Adapted down/up`（QP 驱动真正下调/回升） |
| `VideoStreamAdapter … {res=…, fps=…}` | `{res=2, fps=0}`（只降分辨率、不降帧率） | 分辨率档位变化应更平滑、`kLimitReached` 减少 |
| `encoder_rate_floor`（native.log） | 18（n4）/ **68**（n5） | 应显著下降（先降分辨率而不是饿码率） |
| `encoder_rates requested_bps` | n5 p10 **12 670** | 应抬离地板（≥30 000 且不再频繁触底） |
| 副作用监控 | — | `encoder_reinit` 次数与关键帧占比不应激增（每档分辨率变化 = 一次 destroy+enc_init）；若激增 ⇒ 回退 §2.2 |

## 3. ② 渲染侧二次损耗定因：**「到达多少就渲染多少」，损耗在接收/解码侧**

`webrtc.eglrenderer`（n4/n5 全样本，字段 `Duration / Frames received / Dropped / Rendered / Render fps / Average render time`）：

| Duration | received | Dropped | Rendered | Render fps | Avg render time |
| --- | --- | --- | --- | --- | --- |
| 4002 ms | 95 | **0** | 95 | 23.7 | 1074 µs |
| 4002 ms | 75 | **0** | 75 | 18.7 | 528 µs |
| 4001 ms | 87 | **0** | 87 | 21.7 | 1361 µs |
| 4002 ms | 76 | **0** | 76 | 19.0 | 1093 µs |
| 4007 ms | 53 | **0** | 53 | 13.2 | 1276 µs |
| 4005 ms | 28 | **0** | 28 | **7.0** | 530 µs |
| 4009/4007 ms | 0 | 0 | 0 | 0.0 | — |

**判定（三条量化依据）**：
1. **每个窗口 `Dropped = 0` 且 `Rendered == Frames received`** ⇒ 渲染队列没有丢帧，也没有 sink 节流；
2. **`Average render time` 0.53–1.36 ms**（≪ 25 fps 的 40 ms 帧间隔）⇒ EGL 绘制不是限制；
3. 损耗位置用 libwebrtc 计数器定位：

| 计数器（`WebRTC.Video.…`） | n4 | n5 | 含义 |
| --- | --- | --- | --- |
| `DroppedFrames.RenderQueue` | 0 / 1 / **7** | 0 / 1 / 3 | 本地渲染队列丢帧：**0–7**（可忽略） |
| `DroppedFrames.Receiver` | **35 / 73** | **84** | 接收侧丢帧：主项 |
| `ReceivedPacketsLostInPercent` | 1 / 2 | **18** | 丢包 |
| `DroppedFrames.Capturer` | 0 | 0 | 采集侧无丢帧 |

⇒ **二次损耗在接收/解码侧（丢包 ⇒ 帧缺失/解码失败），不在渲染队列，也不在 EGL**；`remote_frame_liveness`
（n4：`frames=302/598`、`down_bps=1.0–2.0 Mbps`、`age_ms=16–33`）说明有窗口到达率本来就好，与 `Render fps 23.7`
对应；而 7.0 fps 的窗口与 `packet_loss 8–24%` 同期 ⇒ **网络（RELAY + 丢包/重传）是主因**。
**必要修复：无**（渲染与采集侧证据均干净）；可选后续：把接收侧 `frames_decoded/frames_dropped` 一并导出以便逐窗口对账（见 V-7）。

## 4. ③ ~20 fps 上限归因：**不是采集上限，是 libwebrtc 按码率/拥塞的降级**

| 观测 | 数值/证据 | 指向 |
| --- | --- | --- |
| `encoder_perf in_fps` | 18–23（n4/n5） | 编码器**收到**的输入就只有 18–23 fps |
| `setrates fps` | 17–23 | 同源（libwebrtc 入参，t85 已证本层无下调） |
| `DroppedFrames.Capturer` | **0**（n4/n5） | **采集侧没有丢帧** ⇒ 不是「采集上限」 |
| `VideoStreamAdapter … {res=2, fps=0}` | n5 出现 3 次 | libwebrtc 只降**分辨率**（res=2），**没有**设置 fps 限制 |
| `VideoStreamAdapter returned kLimitReached` | 4（n4）/ 3（n5） | 适配器按码率把分辨率降到下限后无法再降 |
| `OnBitrateUpdated … packet_loss 0–24%, rtt 57–75` | n4 `bitrate 1698503 → loss 24% rtt 73`、n5 `bitrate 14096 / 0` | **码率/拥塞驱动**（丢包最高 24%） |
| `DroppedFrames.Capturer … BadTimestamp` | n5 3 次（值 0） | 采集时间戳异常丢帧计数为 0，非原因 |

⇒ 结论：**~20 fps 是 libwebrtc 侧按可用码率/拥塞做的降级与丢帧结果**（`VideoStreamAdapter` 在「只降分辨率」的前提下无法继续缓解 ⇒
`kLimitReached`；Java 编码器的 `has_trusted_rate_controller=false` ⇒ `FrameDropper` 常开，把帧丢到与目标码率匹配的量级）。
**采集侧无需改**（无采集丢帧、无 fps 限制配置；我们从未设置采集帧率上限，`local_preview_resolution_changed` 由 SDK 决定）。
**可提升空间与已做措施**：① 质量降级改为「QP 驱动」（本报告 §2，`da6c025`）；② 码率地板 30 kbps + 官方启动码率（t89，`c1d06cf`）；
③ 多线程/row-mt 降低单帧耗时与抖动（t85，`8f51e8e`）；④ 兜底自动降级（t87）作为最后手段。
若下一轮真机显示 `in_fps` 仍锁在 ~20 而 `VideoStreamAdapter {fps=0}`：说明瓶颈在**网络可用码率**（RELAY + 丢包），
需与 t87 兜底/默认编码 A/B 一起判定（见 §5）。

## 5. 与 t89/t85/t87 的关系 · 不引入回归

- **t89（码率地板）**：把 `minBitrateBps` 从 0 改成官方 30 kbps、启动码率 0 → 120k/190k ⇒ 解决「饿到 40 kbps 且无回弹」；
  与本报告的 §2（QP 降级）、§4（帧率归因）互补——地板保证下限，QP 降级保证「降分辨率而不是降可用性」。
- **t85（CPU/码率口径）**：`g_threads=4 + row-mt`、ceil+地板+去抖、`encoder_perf/encoder_rates` 埋点；真机
  `encoder_perf p50 5.2–11.9 ms / p95 11.6–14.0 ms / no_output=0` ⇒ 编码侧已非瓶颈。
- **t87（自动降级兜底）**：会话内切默认编码；本报告的三块结论说明「编码侧已不是主因」，因此 t87 应保持**不被频繁触发**
  （若仍触发，见 §6 判据）。
- **回归面**：本轮**未改代码**（仅报告）⇒ 无新增回归面；t90 构建已记录离线全量 **23 类 / 213 例 / 0 失败**
  （含 `EncoderFallbackPolicyTest` 10、`EncoderFallbackControllerTest` 5、`Vp9BitrateLimitsTest` 7）；t89 的 old-red（6 例必红）仍有效。

## 6. 未验证项与真机复测判据

| # | 未验证项 | 复测键与期望 |
| --- | --- | --- |
| U1/V-6 | `ScalingSettings(24,37)` 在真机的降级行为与副作用 | 期望：`Removing resource "QualityScalerResource"` 消失、出现 `QualityScalerResource … Adapted down/up`、`encoder_rate_floor` 次数下降、**`encoder_reinit` 不激增**；若激增 ⇒ 回退 `OFF` |
| U2/V-7 | 渲染侧结论基于 `eglrenderer` 4 s 窗口；接收侧解码器计数未导出 | 建议下轮导出 `WebRTC.Video.DecodedFrames`/`frames_dropped`，逐窗口与 `eglrenderer Frames received` 对账（期望 `Rendered == received`、`RenderQueue ≤ 个位数`） |
| U3/V-8 | 默认编码在**同等**丢包/RTT 下的帧率对照（判定「网络 vs 我们侧」） | 同机同 RELAY 路径、`use_default_encoder` 真/假各 60 s，同窗口比 `eglrenderer Render fps` / `Frames received` / `Dropped` + `OnBitrateUpdated packet_loss/rtt`；默认编码同级丢包下仍 ≥25 fps ⇒ 我们侧仍有空间；两者同为 7–23 fps ⇒ 网络为主因 |
| U4 | ~20 fps 是否会随 t89 地板 + QP 降级回升 | `encoder_perf in_fps` 应从 18–23 回升；若仍 ~20 且 `VideoStreamAdapter {fps=0}` ⇒ 网络可用码率所限 |

## 7. 交付与状态

- 本轮唯一写入：`reports/50-quality-scaling-and-render-fps.md`（mode 644）。**未改任何代码**；`app/src` 最后写入时刻 =
  **2026-09-16 20:15:38.476**（早于 t90 构建窗口 T1=20:23:26，本轮 t91 无 `app/src` 写入）。
- 未改 `app/src/main/cpp/**`、`signaling/**`、`doc/**`、`third_party/**`；未跑宿主机 Gradle；未 commit。
- 已提交的相关改动（非本轮）：`da6c025`（`ScalingSettings(24,37)`，作者 native-dev，t91 前置）、`c1d06cf`（t87+t89）、
  `8f51e8e`（t85）；t90 锚点 `0f60d901…cafe45`。
