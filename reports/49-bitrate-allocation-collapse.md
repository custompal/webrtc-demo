# reports/49-bitrate-allocation-collapse.md —— 自研 VP9 码率分配崩塌：定因与修复（t89）

- 任务：t89（native-dev）；范围：`app/src/main/kotlin/com/example/webrtcdemo/encoder/**`、`app/src/test/kotlin/**` + 本报告。
- 证据：用户日志 `webrtcdemo-logs-20260916-074228Z_034237`（宿主 `/opt/dsh-workspaces/tmp/n3/x/app.log` ↔ 容器 `/data/dsh/home/workspace/tmp/n3/x/app.log`）。
- **未 commit**（任务纪律）；未跑宿主机 Gradle；未改 `signaling/**`、`doc/**`、`third_party/**`。

---

## 1. A/B 对照（同一台 Mi 10 Pro、同一条 RELAY 链路、相隔几分钟）

| 维度 | **A：默认编码**（07:40:30–07:41:00，`use_default_encoder=true`） | **B：自研编码**（07:41:36–07:42:30，`use_default_encoder=false`） |
| --- | --- | --- |
| 编码器 | `impl=libvpx` | `impl=SelfVp9Libvpx`（`encoder_created impl=SelfVp9Libvpx`） |
| `stats_sample up_bps` | 最高 **1 969 387 bps**（≈1.97 Mbps），n=15 | **39 084–330 181 bps**（p50 约 41–56 kbps），n=23 |
| `stats_sample avail_bps`/`down_bps` | avail **2 026 409–2 292 091**、down 1 470 822–2 037 722 | avail 0–84 400、down 147 897–518 991 |
| 链路/模式 | `mode=RELAY local=relay remote=prflx` | 同 RELAY（`local=relay`） |
| 编解码偏好 | `codec_preferences_set codec=VP9 count=1` | **同**（都是 VP9 软编，**不是**软/硬编差异） |
| `setrates total_bps` 轨迹 | —（默认编码不经过我们） | `235947 → 125722 → 40536 → 37156`（**1 秒内掉到 37 kbps**） |

**回答 acceptance 第 1 条的关键问句**：这 40 kbps **是 libwebrtc 自己 requested 的**——native-dev 在 t85 已实测
requested→applied ≈ 99.6–100%（`encoder_rates requested_bps=143512 applied_total_bps=143000`、`1049452→1049 kbps`），
本次 A/B 又证明两条路径用的是**同一个 VP9 软编**（排除软/硬编差异）⇒ 差异只能来自**编码器自身向 libwebrtc 申报的
`EncoderInfo`**，而不是我们压低了写进 libvpx 的值。

---

## 2. 四个候选的逐条排查

### H3（分辨率码率下限为 0）——**成立，本次修复项**

- 修复前 `Vp9VideoEncoder.getResolutionBitrateLimits()`（`Vp9VideoEncoder.kt:343-346`）：
  `(320*180, 0, 0, 500k)`、`(640*360, 0, 0, 1M)`、`(1280*720, 0, 0, 2M)` —— **`minStartBitrateBps=0`、`minBitrateBps=0`**。
- libwebrtc 侧这条信息是**消费**的（不是纯声明）：
  - `video/video_stream_encoder.cc:407-434`（`GetEncoderInfoWithBitrateLimitUpdate`）——我们的表 `min==0 && max!=0`，
    `are_all_bitrate_limits_zero=false` ⇒ **原样保留并被使用**；
  - `video/video_stream_encoder.cc:469`、`:540`、`:1197`、`:2577` 与 `video/adaptation/bitrate_constraint.cc:80` 都通过
    `GetEncoderBitrateLimitsForResolution(frame_size_pixels)` 取匹配档位，写进 `VideoEncoderConfig` 的 min/max。
- ⇒ **`minBitrateBps=0` 等于把视频流的地板抽掉**：估计器一旦下探，分配可以掉到几十 kbps 且**没有地板可回弹**；
  CBR 随即把 QP 顶到真机实测的 193–224（糊），画面变小/变糊又让估算继续下探 = 死亡螺旋。这与 B 段的
  `avail_bps≈0 / up_bps≈40 kbps / total_bps 1 秒掉到 37 kbps` 完全吻合。
- **参考基准**（captain 要求去源码取）：libwebrtc 自己的 VP9 单播表在
  `rtc_base/experiments/encoder_info_settings.cc`（`GetDefaultSinglecastBitrateLimits(kVideoCodecVP9)`）：
  ```cpp
  return {{320 * 180, 0,      30000, 150000},
          {480 * 270, 120000, 30000, 300000},
          {640 * 360, 190000, 30000, 420000},
          {960 * 540, 350000, 30000, 1000000},
          {1280 * 720,480000, 30000, 1500000},
          {1920 * 1080,1000000,30000, 3700000}};
  ```
  ⇒ **每一档 `minBitrateBps = 30000`**，且 480×270 以上都有**非零启动码率**。我们的旧表与官方基准的差异正是这两列。

### H1（两条路径的 `VideoEncoderConfig`/SDP 差异）——**排除**

- 两条路径共用同一个 `PeerConnectionFactory`/PeerConnection/SDP 构造：全仓 `grep -rn 'maxBitrateBps|startBitrateBps|minBitrateBps|b=AS|maxbr'`
  在 `webrtc/**` 与 `encoder/**` 下**只有**「读取 `settings.startBitrate`」（`Vp9VideoEncoder.kt:112`）与我们自己的 limits 表，
  **没有任何**地方显式设置码率上下限/b=AS/maxbr。
- 两段会话的 `codec_preferences_set codec=VP9 count=1` 相同、`mode=RELAY` 相同 ⇒ SDP/配置面不构成差异。
- 唯一差异是编码器实现（`impl`），而其对外可见的差异就是 `getEncoderInfo()`/`getResolutionBitrateLimits()` ⇒ 归入 H3。

### H2（我们回给 libwebrtc 的 `EncodedImage` 元数据）——**排除**

- `Vp9VideoEncoder.kt:376-387`：`setEncodedWidth(meta[0])`、`setEncodedHeight(meta[1])`、`setCaptureTimeNs(captureTimeNs)`、
  `setFrameType(key/delta)`、`setQp(meta[5])`、`setRotation(0)` 全部正确；`captureTimeNs` 用的是该帧的 `frame.timestampNs`，
  与 libwebrtc 侧按 `frame_extra_infos_` 匹配的时间戳一致（不匹配会打 `Java encoder produced an unexpected frame with
  timestamp` 并丢帧；真机日志无此告警）。
- 另：`EncodedImage` 的**缓冲容量=帧长**缺陷属于同一类「元数据」问题，已在 t57 修掉（`sdk/android/src/jni/encoded_image.cc`
  用 `GetDirectBufferCapacity()` 当帧长）；且该缺陷会让上报帧长**变大**（512 KiB），方向是抬高而非压低码率 ⇒ 与本崩塌无因果。

### H4（启动阶段无可探测流量 ⇒ 估计器不爬升）——**部分成立，被同一处修复覆盖**

- 旧表 `minStartBitrateBps = 0`：`InitEncode` 的 `settings.startBitrate` 由 config 的 min/start 决定 ⇒ 启动码率过低
  （B 段首条 `setrates total_bps=235947` 虽不算低，但一旦下探就再无地板）。
- 新表给 480×270/640×360/960×540/1280×720/1920×1080 分别 120/190/350/480/1000 kbps 的启动码率 ⇒ 一开始就有可探测流量。

---

## 3. 修复（至少一处，已含 before/after 数值）

| 文件 | 改动 |
| --- | --- |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9BitrateLimits.kt`（**新增**） | 照抄 libwebrtc 官方 VP9 单播表（6 档，含 `minBitrateBps=30_000` 地板与 120k/190k/350k/480k/1000k 启动码率），并提供 `limits()`/`matchForPixels()`（后者仅单测用，运行时选择在 libwebrtc native 侧） |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:343-344` | `getResolutionBitrateLimits()` 改为 `Vp9BitrateLimits.limits()` |
| `app/src/test/kotlin/com/example/webrtcdemo/encoder/Vp9BitrateLimitsTest.kt`（**新增**，7 个用例） | 回归断言：6 档像素递增、**每档 `minBitrateBps == 30_000`（>0）**、`max ≥ min`、480×270 起启动码率 >0、640×360 档与官方逐值一致、480×360/640×480 命中档位地板非零、`limits()` 返回新数组 |

**before / after（单位 bps；`frameSizePixels, minStart, min, max`）**

| 档位 | before（旧表） | after（官方表） | 关键变化 |
| --- | --- | --- | --- |
| 320×180 | 57 600, **0, 0, 500 000** | 57 600, 0, **30 000**, 150 000 | 地板 0 → 30 kbps |
| 480×270 | *无此档* | 129 600, **120 000**, **30 000**, 300 000 | 新增档（真机 480×360 命中它） |
| 640×360 | 230 400, **0, 0, 1 000 000** | 230 400, **190 000**, **30 000**, 420 000 | 地板 0 → 30 kbps；启动 0 → 190 kbps |
| 960×540 | *无此档* | 518 400, 350 000, 30 000, 1 000 000 | 新增档 |
| 1280×720 | 921 600, **0, 0, 2 000 000** | 921 600, **480 000**, **30 000**, 1 500 000 | 地板 0 → 30 kbps；启动 0 → 480 kbps |
| 1920×1080 | *无此档* | 2 073 600, 1 000 000, 30 000, 3 700 000 | 新增档 |

**量化预期（可复测）**：真机 480×360 的会话命中 480×270 档 ⇒ 视频流地板由 **0 → 30 kbps**、启动码率 **0 → 120 kbps**；
`setrates total_bps` 不应再在启动 1 秒内掉到 37 156 这类量级（**下限 30 000，且启动 ≥120 000**），
`stats_sample up_bps` 应能与 `avail_bps` 同量级爬升（A 段同链路跑到 1.97 Mbps）。

## 4. 下一轮真机复测判据（acceptance 第 4 条）

| 键 | 期望（修复后） | 判定 |
| --- | --- | --- |
| `app.log` `setrates fps=… total_bps=…` | 前 5 s 不低于 **120 000**，全程不低于 **30 000** | 不再出现 `40536 / 37156` 这类量级即通过 |
| `native.log` `encoder_rates requested_bps=…`（t85 埋点） | `requested_bps >= 30000`，且与 `setrates total_bps` 一致 | 低于 30 000 ⇒ 说明仍有其它路径压码率，需回报 |
| `app.log` `stats_sample … up_bps / avail_bps` | 同链路（RELAY）应回到 **≥0.5–1 Mbps 量级**（A 段 1.97 Mbps 为上限参考） | `up_bps` 与 `avail_bps` 同量级即通过 |
| `app.log` `encoder_fallback reason=…` | **不应再因码率崩塌触发**（若 t87 兜底仍触发，说明本修复未完全成立） | 触发即需回报 |

## 5. 与 t87「自动降级兜底」的关系

- 本修复针对**分配侧的地板**（H3/H4）；t87 的兜底是会话级的粗粒度保险（检测到崩塌就切默认编码/重建 PC）。
- 两者**不冲突、有重叠**：若本修复成立，t87 不会触发（用户也不再需要等兜底生效）；
  若本修复不成立，t87 会把症状「盖住」（切回硬编/默认编码后流畅），但**自研 VP9 路径的码率仍然上不去** ⇒
  复测时必须用 §4 的 `setrates total_bps` / `encoder_rates requested_bps` 两个键区分「兜底救了场」与「真的修好了」。
- 另注：t87 的兜底逻辑本身不依赖 `resolution_bitrate_limits`，因此**不受本缺陷影响**。

## 6. 未验证项

| # | 未验证项 | 说明 |
| --- | --- | --- |
| V-1 | **无真机 A/B 复测**（本次改动未重打包） | 量化证据为「limits 表 before/after + libwebrtc 消费链 file:line + 地板/启动码率数值」；真机数字由 §4 的判据在下一轮取得 |
| V-2 | Kotlin 未编译 | 容器无 JDK/SDK；新增 `Vp9BitrateLimitsTest.kt`（7 用例）与 `Vp9BitrateLimits.kt` 由构建任务编译验证 |
| V-3 | `GetEncoderBitrateLimitsForResolution` 的**精确匹配规则**（同档边界）未在容器内执行 | 我们的 `matchForPixels()` 是「≤ 目标像素中最大档」的同口径实现（单测覆盖），运行时仍以 native 实现为准 |
| V-4 | 是否还有第二处压低码率的路径（H4 之外） | §4 的 `encoder_rates requested_bps` 与 `setrates total_bps` 对照可一次判明 |
| V-5 | 改 `webrtc/**` 之外（如 `CallSession` 生命周期）**未做** | 按任务要求：只读评估，未改；如需动会话生命周期将先上报 |

## 7. 交付清单（未 commit）

| 文件 | 变更 |
| --- | --- |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9BitrateLimits.kt` | 新增（官方 VP9 单播 limits 表 + `limits()`/`matchForPixels()`） |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt` | `getResolutionBitrateLimits()` 改用官方表 |
| `app/src/test/kotlin/com/example/webrtcdemo/encoder/Vp9BitrateLimitsTest.kt` | 新增（7 用例，含「每档地板必须 > 0」回归） |
| `reports/49-bitrate-allocation-collapse.md` | 本报告（mode 644） |

未改：`signaling/**`、`doc/**`、`third_party/**`、`app/src/main/cpp/**`（t85 的 C++ 改动仍在工作树，未提交）。

---

## 8. 【t89 追加 1】启用 QP 质量缩放（ScalingSettings 不再 OFF）

**证据（t86 版真机，埋点在 `native*.log`）**：`encoder_threads g_threads=4 row_mt=0 cpu_used=8 cores=8`；
`encoder_perf` p50 2.9–13.8 ms、**p95 5.3–18.2 ms**、`no_output=0`；`debounced=1` 占比 724/890（n4）、263/391（n5）；
**`encoder_rate_floor` 触发 18 次（n4）/ 68 次（n5）**（requested 7–27 kbps → 地板 30 kbps）；
`requested_bps` n5 p10 **12 670** / p50 273 371 / max 754 307，n4 p50 850 606 / p90 1 699 073。

**问题**：`Vp9VideoEncoder.getScalingSettings()` 返回 `ScalingSettings.OFF` ⇒ libwebrtc 日志出现
`Removing resource "QualityScalerResource"` ⇒ 拥塞时**没有 QP 侧的优雅降级**（分辨率/帧率不会平滑下调，只会码率被饿死，
真机同时可见 `encoder_rate_floor` 频繁触发 + `VideoStreamAdapter … kLimitReached`）。

**判定：启用**（依据）：① 我们已把 libvpx 的真实 QP 回传（`setQp(meta[5])`，`Vp9VideoEncoder.kt:387`）⇒ 具备 QP 阈值判定的输入；
② libwebrtc 自带软编 VP9 的参考实现同样在「可缩放」时给出阈值
（`modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc:1897` `info.scaling_settings = VideoEncoder::ScalingSettings(low, high)`，
否则 `kOff`）；③ Java 侧有 `VideoEncoder.ScalingSettings(int low, int high)` 构造
（`sdk/android/api/org/webrtc/VideoEncoder.java:139`）。

**改动**：`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:89-90` 新增常量
`LOW_QP_THRESHOLD = 24`、`HIGH_QP_THRESHOLD = 37`；`:339-340` 改为
`VideoEncoder.ScalingSettings(24, 37)`。
> 阈值来源说明（如实）：本 checkout 内**没有** `kLowQpThreshold/kHighQpThreshold` 常量（全树 grep 为空），
> 24/37 是 libwebrtc 生态（H.264/MediaCodec 实现）惯用的一对取值；若真机复测显示降级过于激进/保守，
> 按 U1 调整这两个常量即可（单点可回退）。

**启用后对「糊/卡」的实际影响（预期）**：QP 高于 37 时 libwebrtc 逐档下调分辨率（每次经过 SDK 的 VideoAdapter；
我们的 C++ 侧会按 t50 的「destroy + enc_init」重建一次编码器并强制关键帧），QP 低于 24 时回升 ⇒
在 8% 丢包/63–72 ms RTT 的中继链路上，宁可**先降分辨率保帧率**，而不是把码率饿到 30 kbps 地板后画面糊且卡。
**代价**：分辨率每变一档 = 一次 reinit（关键帧 + RC 复位）；若真机出现 reinit 风暴（`encoder_reinit` 次数激增、
关键帧占比升高），把 `getScalingSettings()` 改回 `ScalingSettings.OFF` 即恢复原状（单行回退）。

## 9. 【t89 追加 2】渲染侧是否是二次瓶颈 —— 归因结论

**证据**（`eglrenderer` 行，n4/n5 导出）：
```
Duration 4002ms Frames received  95 Dropped 0 Rendered  95 Render fps 23.7 Avg render time 1074us
Duration 4002ms Frames received  75 Dropped 0 Rendered  75 Render fps 18.7 Avg render time  528us
Duration 4001ms Frames received  87 Dropped 0 Rendered  87 Render fps 21.7 Avg render time 1361us
Duration 4007ms Frames received  53 Dropped 0 Rendered  53 Render fps 13.2 Avg render time 1276us
Duration 4005ms Frames received  28 Dropped 0 Rendered  28 Render fps  7.0 Avg render time  530us
```

**结论：渲染侧不是瓶颈，是「到达就这么多」**。判据三条：
1. **`Dropped = 0` 且 `Rendered == Frames received`**（每个 4 s 窗口）⇒ 渲染队列没有丢帧、没有节流；
2. **`Average render time 0.53–1.36 ms`**（远小于 40 ms 的 25 fps 帧间隔）⇒ EGL/绘制不是限制；
3. 渲染 fps（7.0–23.7）与同期编码侧 `encoder_perf in_fps=18–23` 大体同量级，但**出现 7.0/13.2 这类低谷**——
   低谷与网络侧指标同期（`OnBitrateUpdated … packet_loss 8`、NACK 700+、RTT 63–72 ms、`mode=RELAY`）。

**归因分离（按用户/你的要求）**：

| 侧 | 观测事实 | 判定 |
| --- | --- | --- |
| 网络 | `mode=RELAY`（两端 Symmetric NAT ⇒ 无法 P2P）、`packet_loss 8%`、NACK 700+ 次、RTT 63–72 ms | **主因之一**：丢包/重传造成到达帧率的周期性低谷（4 s 窗口内 28 帧 = 7 fps） |
| 我们侧·编码 | `encode_ms` p50 2.9–13.8 ms、p95 5.3–18.2 ms（< 帧间隔）、`no_output=0`、`g_threads=4 row_mt=0` | **不是瓶颈**（t85 的多线程已生效） |
| 我们侧·码率 | `encoder_rate_floor` 18/68 次、requested p10 12.7 kbps（n5） | 地板已生效（30 kbps），但**分配本身过低**是分配/估计侧问题（t89 §2） |
| 我们侧·渲染 | `Dropped 0`、`Rendered == received`、渲染耗时 0.5–1.4 ms | **不是瓶颈**（无本地丢帧/节流） |
| 帧率口径 | `in_fps` 18–23、`setrates fps` 17–23 | 跟随 libwebrtc 入参（t85 已证），且被丢包/重传压低 |

**你的问题：在 8% 丢包 + 63–72 ms RTT 的中继链路上，默认编码路径是否也会掉到 18–23 fps？**
**结论：未验证**（本轮无真机、未重打包）。可比对的**部分** A/B 只有 n3：同一台 Mi 10 Pro、同 `mode=RELAY`，
默认编码 `up_bps` 1.36–1.97 Mbps；但两段的丢包/RTT 并不相同（n3 未记录 `packet_loss 8`）⇒ 存在混淆变量，
**不能据此断言默认路径在 8% 丢包下仍不掉帧**。建议的 A/B（一次复测即可判定，需你派构建+真机）：
同一对设备、同一条 RELAY 路径、`use_default_encoder` 真/假各跑 60 s，**同窗口**采集
`eglrenderer Render fps / Frames received / Dropped`、`stats_sample up_bps/avail_bps`、
`OnBitrateUpdated … packet_loss … rtt …`；判据：若默认编码的 `Render fps` 在同级丢包/RTT 下仍 ≥25，
则说明我们侧仍有优化空间；若两者同为 7–23 fps，则瓶颈在网络（RELAY + 丢包），与编码器实现无关。

**未验证项（追加）**：
- V-6：ScalingSettings 启用后真机的降级行为（分辨率档位变化次数、`encoder_reinit` 频率、观感）——见 U1（在本报告 §4 判据之外，另看 `encoder_reinit` 与关键帧占比）。
- V-7：渲染侧结论基于 `eglrenderer` 的 4 s 窗口计数；接收侧解码器统计（`frames_decoded/frames_dropped`）不在本轮导出中，建议下一轮一并导出。
- V-8：默认编码路径在同等丢包/RTT 下的帧率对照（如上，需真机 A/B）。
