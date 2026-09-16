# reports/51-frame-dropper-and-trusted-rc.md —— Java 编码器 FrameDropper / 受信速率控制器（t92）

- 任务：t92（native-dev）。改动落在 `webrtc/**` 与 `app/src/test/kotlin/**`（inScope）；**未 commit**（等 captain 决定是否再走一次构建）。
- **app/src 最后写入时刻 = 2026-09-16 20:35:46**（`app/src/test/kotlin/.../FrameDropperFieldTrialTest.kt`；源码侧最后一次写入 = **20:35:30** `WebRtcEngine.kt`）。
- 证据：libwebrtc 源码 submodule（`third_party/libwebrtc-src`，只读）+ 真机日志 `tmp/n4/x`、`tmp/n5/x`。

---

## 1. 摘要

| 问题 | 结论 | 处置 |
| --- | --- | --- |
| Java 编码器为什么被常开 FrameDropper | `VideoEncoderWrapper` 构造 `EncoderInfo` 时**不设置** `has_trusted_rate_controller` ⇒ 取默认 `false`；`video_stream_encoder.cc:2016-2019` 在该值为 false 时启用丢帧 | 见 §2（事实链） |
| **候选 1**：能否用 field trial 关闭 | **能**：`WebRTC-FrameDropper/Disabled/` 会把 `force_disable_frame_dropper_` 置真（`:108`/`:1459-1463`）；Java 侧有非弃用入口 `PeerConnectionFactory.Builder.setFieldTrials`（`PeerConnectionFactory.java:191-194`） | **已实施**（§3）：新常量文件 + `WebRtcEngine.kt:143` + 初始化日志 |
| **候选 2**：能否把 Java 编码器标记为受信 | **本版本未暴露**：`video_encoder_wrapper.cc:124-140` 写入的 `EncoderInfo` 字段清单里没有该字段，`org.webrtc.VideoEncoder` 也没有相应申报方法 | 用替代路径（§4）：`ScalingSettings(24,37)`（t91）+ 码率地板（t89）+ `setRates` 去抖（t85） |
| 效果 | 真机 before 基线：`Drop Frame:` 行 **258（n4）/ 449（n5）**，样本 `target_bitrate 543494, input_frame_rate 30`（n4）、`target_bitrate 14072, input_frame_rate 20`（n5） | 判据见 §5（**本轮无真机复测**，明确列为未验证） |

---

## 2. 事实确认：`has_trusted_rate_controller` 由谁决定

1. **字段定义**：`third_party/libwebrtc-src/api/video_codecs/video_encoder.h:210` `bool has_trusted_rate_controller;`
   （`EncoderInfo` 的成员，默认由 `EncoderInfo` 的初始化决定为 **false**）。
2. **Java 编码器的 native 包装器**：`third_party/libwebrtc-src/sdk/android/src/jni/video_encoder_wrapper.cc:124-140`
   依次设置
   `supports_native_handle`（:124）、`implementation_name`（:126）、`is_hardware_accelerated`（:129）、
   `scaling_settings`（:132）、`resolution_bitrate_limits`（:134）、`requested_resolution_alignment`（:138）、
   `apply_alignment_to_all_simulcast_layers`（:140）—— **没有 `has_trusted_rate_controller`**
   ⇒ 任何 Java 实现（含我们的 `Vp9VideoEncoder`）都只能是 `false`。
3. **消费点**：`third_party/libwebrtc-src/video/video_stream_encoder.cc:2016-2019`
   ```cpp
   const bool frame_dropping_enabled =
       !force_disable_frame_dropper_ && !encoder_info_.has_trusted_rate_controller;
   frame_dropper_.Enable(frame_dropping_enabled);
   ```
   ⇒ Java 编码器路径下丢帧**恒开**（除非 §3 的强制关闭）。
4. **真机可观测形态**（t86 版日志，`webrtc*.log`）：
   `Drop Frame: target_bitrate 543494, input_frame_rate 30`（n4）、`Drop Frame: target_bitrate 14072, input_frame_rate 20`（n5）；
   行数 **258（n4）/ 449（n5）** ⇒ 丢帧确实在发生（t91 报告 §4 的 `in_fps 18–23 < 30` 与此一致）。
5. 对照：默认编码路径是 **C++ 原生 libvpx**（`modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc`
   里的 `trusted_rate_controller_` 可在受信时置真）⇒ 拥塞时它**保帧率、只降质量**；我们则被丢帧。

## 3. 候选 1（已实施）：用 field trial 关闭 FrameDropper

**可用性核实（file:line）**
- 试验名：`video/video_stream_encoder.cc:108` `constexpr char kFrameDropperFieldTrial[] = "WebRTC-FrameDropper";`
- 生效逻辑：`video/video_stream_encoder.cc:1459-1463`
  ```cpp
  // Force-disable frame dropper if either:
  //  * We have screensharing with layers.
  //  * "WebRTC-FrameDropper" field trial is "Disabled".
  force_disable_frame_dropper_ = env_.field_trials().IsDisabled(kFrameDropperFieldTrial) || (…screenshare…);
  ```
  ⇒ 取值必须是 **`Disabled`**（`IsDisabled`）。
- Java 入口：`sdk/android/api/org/webrtc/PeerConnectionFactory.java:191-194`
  `public Builder setFieldTrials(String fieldTrials) { envBuilder.setFieldTrials(fieldTrials); return this; }`
  （**非弃用**；等价的 `InitializationOptions.Builder.setFieldTrials` 在 `:100-104`，已标记 Deprecated）。
- field-trial 语法：`<name>/<value>/`（多试验用 `/` 分隔）⇒ 完整串 `WebRTC-FrameDropper/Disabled/`。

**实施**
| 文件 | 改动 |
| --- | --- |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrial.kt`（新增） | `NAME="WebRTC-FrameDropper"`、`DISABLED_VALUE="Disabled"`、`DISABLED_TRIAL="WebRTC-FrameDropper/Disabled/"`（含完整理由与 file:line 注释） |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:143` | `PeerConnectionFactory.builder()…setFieldTrials(FrameDropperFieldTrial.DISABLED_TRIAL)` |
| 同文件 `:164` | 新增初始化日志 `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/`（真机可观测；事件名登记于本报告） |
| `app/src/test/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrialTest.kt`（新增，3 例） | 钉死试验名/取值/格式：`NAME=="WebRTC-FrameDropper"`、`DISABLED_VALUE=="Disabled"`、`DISABLED_TRIAL=="WebRTC-FrameDropper/Disabled/"`、以 `/` 结尾、恰好两个 `/`、无空格；**old-red 断言** `assertFalse(trial.contains("/Enabled/"))`（写错取值会让 dropper 继续工作） |

> 为什么不用「受信控制器」（候选 2）：见 §4 —— 本版本没有该入口；而关掉 dropper 后，
> 拥塞降级由**已启用**的 `ScalingSettings(24, 37)`（t91，提交 `da6c025`）接管 ⇒ 变成「先降分辨率/质量、保帧率」，
> 正是用户体感更好的默认路径行为。

## 4. 候选 2：本版本**未暴露**「标记 Java 编码器为受信」的入口

- native 侧：`video_encoder_wrapper.cc:124-140` 的 `EncoderInfo` 赋值清单里**没有** `has_trusted_rate_controller`，
  也没有从 Java 读该值的 JNI 调用（全文件 grep `trusted` 无命中）。
- Java 侧：`org.webrtc.VideoEncoder`（jar/jar 源 `sdk/android/api/org/webrtc/VideoEncoder.java`）暴露的能力申报只有
  `getScalingSettings()`（`:364`）、`isHardwareEncoder()`、`getImplementationName()`、`createNative(...)` 等，
  **没有** trusted-rate-controller 相关方法；`VideoEncoderFactory` 也没有。
- ⇒ **结论：本版本无法把 Java 编码器标记为受信**。替代优化（已全部落地或已在线上）：
  1. **关闭丢帧**（本报告 §3，新上线）⇒ 拥塞时不再丢输入帧；
  2. **QP 质量缩放**`ScalingSettings(24, 37)`（t91，`da6c025`）⇒ 降分辨率而不是降可用性；
  3. **码率地板/启动码率**（t89，`c1dc06cf`… 见 `c1d06cf`）⇒ 目标码率不再掉到 30 kbps 以下、启动即 120/190 kbps；
  4. **`setRates` 去抖 + 分数码率向上取整**（t85，`8f51e8e`）⇒ 减少重配抖动、`applied ≥ requested`；
  5. 兜底自动降级（t87）作为最后手段。

## 5. 效果验证（before / after 与真机判据）

**before（t86 版真机，本报告 §2.4）**：`Drop Frame:` 行 258（n4）/ 449（n5）；`encoder_perf in_fps 18–23`；
`setrates fps 17–23`；`eglrenderer Render fps 7.0–23.7`。

**after 判据（下一轮真机；本轮无设备、未重打包 ⇒ 未验证）**：

| 键 | 期望 | 说明 |
| --- | --- | --- |
| `app.log` `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/` | 出现 1 次 | 证明试验串已下发（若 API 层未生效，则需改用弃用入口 `InitializationOptions.Builder.setFieldTrials`，见 V-1） |
| `webrtc*.log` `Drop Frame:` | **行数应≈0**（对照 258/449） | dropper 被强制关闭的核心判据 |
| `native.log` `encoder_perf … in_fps=` | 应贴近 `setrates fps`（不再被丢帧压到 18–23 而目标 30 时） | 帧率损失是否消除 |
| `eglrenderer … Render fps` | 应与 `in_fps` 同量级，低谷减少 | 观感（用户体感） |
| `native.log` `encoder_reinit` 次数 / 关键帧占比 | **不应激增** | 关掉丢帧后降级改走分辨率（每档一次 reinit）⇒ 若激增需评估 `ScalingSettings` 阈值 |
| `encoder_rate_floor` | 不应上升 | 帧率保持后码率被摊薄，地板触发可能略增属预期 |

**为何宿主不可复现**：field trial 的判定在 libwebrtc 内部（`env_.field_trials()`），
容器内既无 libwebrtc 宿主构建产物，也无 Android 运行时（`PeerConnectionFactory`）⇒ 只能以源码 file:line +
真机判据留证（诚实声明，见 V-2）。

## 6. 不引入回归

- 新增纯 JVM 用例 **1 类 / 3 例**（`FrameDropperFieldTrialTest`），含 old-red 断言。
- 离线全量基线（t90 构建记录）：**23 类 / 213 例 / 0 失败** ⇒ 本改动后预期 **24 类 / 216 例 / 0 失败**
  （由构建任务复跑确认；本轮未跑 gradle，按纪律不跑）。
- 改动面很小：新增 1 个常量文件 + `WebRtcEngine` 两行（`setFieldTrials` 与一条日志）+ 1 个测试文件；
  JNI/编码器/信令/生命周期均未动。

## 7. 未验证项

| # | 未验证项 | 说明 / 下一步 |
| --- | --- | --- |
| V-1 | `PeerConnectionFactory.Builder.setFieldTrials` 在本版 jar 上**是否真正把试验传给 native env** | 仅源码级证据（`PeerConnectionFactory.java:191-194` → `envBuilder.setFieldTrials`）；若真机 `Drop Frame:` 未降为 0，改用弃用入口 `InitializationOptions.Builder.setFieldTrials`（`:100-104`）并回报 |
| V-2 | 关闭丢帧后**体感**是否改善、是否引入副作用（分辨率抖动、reinit 频率） | 真机判据见 §5 |
| V-3 | 关闭丢帧后 GCC/BWE 行为是否变化（丢帧曾被用作「节流信号」） | 观察 `OnBitrateUpdated`/`avail_bps` 与 `PacketLoss`；若 BWE 因此恶化（过度排队），需回退并改回 dropper |
| V-4 | 默认编码路径的 `trusted_rate_controller_` 实际取值（我们只是从源码推断其为受信） | 可下一轮抓默认路径的 `encoder_info`/`Drop Frame:` 行对照确认 |

## 8. 交付与状态

| 文件 | 变更 |
| --- | --- |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrial.kt` | 新增（常量 + 理由/file:line） |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt` | `setFieldTrials(...)`（:143）+ `field_trials_set` 日志（:164） |
| `app/src/test/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrialTest.kt` | 新增（3 例，含 old-red） |
| `reports/51-frame-dropper-and-trusted-rc.md` | 本报告（mode 644） |

未改 `app/src/main/cpp/**`、`signaling/**`、`doc/**`、`third_party/**`；未跑宿主机 Gradle；**未 commit**。
**app/src 最后写入时刻 = 2026-09-16 20:35:46**（源码 20:35:30 / 测试 20:35:46）。
