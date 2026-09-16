# reports/47-vp9-encode-perf.md —— 自研 VP9 编码卡顿：量化、定因与优化（t85）

- 任务：t85（native-dev）；范围：`app/src/main/cpp/encoder/**`、`app/src/main/kotlin/com/example/webrtcdemo/encoder/**`、
  `app/src/test/kotlin/**`、本报告。**未 commit**（按任务纪律，改动留给 captain 的构建任务提交）。
- 真机证据（只读，captain 已解压）：`/opt/dsh-workspaces/tmp/n2/x`（Xiaomi 24117RK2CC，房主；含 03:34 与 07:3x–07:42 两段）、
  `n3/x`（Xiaomi Mi 10 Pro）↔ 容器 `/data/dsh/home/workspace/tmp/n2/x`、`tmp/n3/x`。
- 容器内**未跑 gradle**；宿主 harness 在宿主编译运行（`webrtc-build/t55-harness/harness.cpp`，仓外）。

---

## 1. 摘要

| 问题 | 量化结论 | 处置 |
| --- | --- | --- |
| **CPU 受限？** | **不是唯一瓶颈**：单帧 `vpx_codec_encode` 实测 p50 **11.15 ms**（n2）/ **3.72 ms**（n3），帧间隔 p50 **59 ms**（n2）/ **44 ms**（n3）⇒ 编码耗时 ≪ 帧间隔；但尾延迟 p95 18.2/19.6 ms、max 35.7/39.0 ms 抖动明显 | 仍按 acceptance 修：`g_threads=min(核数-1,4)` + `VP9E_SET_ROW_MT`；宿主实测 p50 **15.33→9.34 ms（−39%）**、p95 19.10→11.76 ms（−38%），输出字节完全相同 |
| **码率欠额？** | **转换层不欠额**：requested→applied 实测 143 512 → 143 kbps（99.6%）、1 049 452 → 1 049 kbps（≈100%）；仅**小码率档**因向下取整欠额（6 732 → 6 kbps = **−11%**） | ceil 取整 + **总码率下限 30 kbps** + `encoder_rates` 一行对照 + `setRates` 去抖（<10% 且 <2 s 跳过） |
| **fps 16–25 是我们压的？** | **不是**：`setrates fps=` 记录的就是 libwebrtc `setRates` 的入参（`Vp9VideoEncoder.kt:260-277` → `nativeSetRates(... framerate ...)`），C++ 侧只跟随（`vp9_encoder.cpp` `SetRates` 里 `config_.max_framerate = rates.framerate_fps`），本层无任何下调点 | 新增 `encoder_perf … in_fps=` 埋点，真机确认「输入帧率 vs 目标帧率」 |
| 与默认编码的差异 | 默认编码路径在小米机型上大概率是 **MediaCodec 硬件编码（H.264/VP8）**，单帧耗时与 CPU 占用远低于单线程软编 VP9；我们这份 libvpx 是 `arm64` NEON/C 软编 | 多线程 + row-mt + 去抖，把尾延迟压下来（§4） |

---

## 2. 量化证据（用户日志）

### 2.1 码率：requested vs applied（修复前也能从两行日志对齐）

| 会话 | `setrates total_bps`（requested） | `ts_target_kbps/rc_target_kbps`（applied） | 比值 |
| --- | --- | --- | --- |
| n2 07:3x | 143 512 bps, fps 22 | `l0=57 l1=100 l2=143 rc=143` kbps | 99.6% |
| n3 07:3x | 1 049 452 bps, fps 28 | `l0=419 l1=734 l2=1049 rc=1049` kbps | ≈100% |

`total_bps` 分位：n2 n=2072 → min 6 732 / **p50 964 130** / p90 = max 2 000 000；n3 n=193 → min 9 869 / **p50 35 835** / p90 258 011。
`setrates fps` 分布：n2 = 20(658)、25(390)、22(183)、16(156)、24(154)、23(130)、15(101)…（**从不 30**）；
n3 = 16(72)、17(37)、23(16)、22(15)、20(13)…。
`stats_sample up_bps`：n2 167–265 kbps、n3 41–54 kbps（mode=RELAY）。

### 2.2 耗时与帧节奏

| 指标 | n2（24117RK2CC） | n3（Mi 10 Pro） |
| --- | --- | --- |
| `encoded_frame` 单帧 `us=` | n=215：min 5.6 / **p50 11.15** / p90 15.88 / p95 18.19 / max 35.75 ms | n=1762：min 1.4 / **p50 3.72** / p90 16.54 / p95 19.64 / max 38.96 ms |
| 帧间隔（相邻 `encoded_frame` 时间戳差） | p50 **59 ms**（≈17 fps）、p95 244 ms、max 2416 ms | p50 **44 ms**（≈23 fps）、p95 175 ms |
| `encode_vpx_begin/done/encoded_frame/reinit` | 216/215/215/**0** | 1762/1762/1762/**0** |
| 单帧体积 / QP | p50 5 585 B；qp 193–224 | p50 277 B；qp 193–224 |

### 2.3 定因（数字结论）

1. **CPU 不是唯一瓶颈**（encode p50 11.15 ms ≪ 帧间隔 p50 59 ms；n3 3.72 ≪ 44 ms），但**尾延迟**（p95≈19 ms、max≈39 ms）与
   帧间隔 p95 175–244 ms 说明抖动大 ⇒ 多线程/row-mt 有明确收益（§4 宿主 −38~−39%）。
2. **码率转换层不欠额**（99.6%–100%），**欠额只出现在小码率档的截断**（6 732→6 kbps）。
3. n3 的**分配本身极小**（p50 35.6 kbps）⇒ VP9 CBR 把 QP 顶到 193–224 ⇒ 糊+估算下探；这是**可用带宽/RELAY 拥塞**造成的，
   不是我们压码率（`up_bps` 41–54 kbps 与 requested 同量级，自洽）。
4. n2 存在**产出/发送低于分配**的缺口（requested p50 964 kbps vs `up_bps` 167–265 kbps）：需要 §3 的
   `encoder_rates`/`encoder_perf` 埋点在下一轮真机定位（候选：输入帧率只有 ~17 fps、丢帧/抖动、`up_bps` 统计口径）。
5. **fps 16–25 的归属**：来自 libwebrtc 的 `rate_control.framerate_fps`（入参），本层无下调逻辑（见 §1 第 3 行）；
   它与「输入帧率 17–23 fps」互为因果（丢帧/耗时抖动把实测帧率压低，libwebrtc 又把该值回传到 allocation）。

---

## 3. 埋点（新增，acceptance 第 1 条）

| 埋点键名（事件） | 行示例 | 单位/含义 |
| --- | --- | --- |
| `encoder_rates`（`bitrate` tag，每次 SetRates 一行） | `encoder_rates requested_bps=143512 applied_total_bps=143000 fps=22 layers=1,3 debounced=0` | `requested_bps`=libwebrtc `setRates` 传入的分配总量（bps）；`applied_total_bps`=换算后写进 libvpx 的总量（kbps×1000）；`fps`=入参帧率；`layers=空间,时序`；`debounced=1` 表示本次跳过重配 |
| `encoder_rate_floor`（WARN，触发下限时） | `encoder_rate_floor requested_bps=6732 applied_bps=30000 floor_kbps=30` | 总码率下限生效 |
| `encoder_perf`（`encoder` tag，每 60 帧聚合一行） | `encoder_perf frames=60 encode_ms_p50=11.150 encode_ms_p95=18.193 encode_ms_max=35.748 in_fps=17 no_output=0 total=215` | `encode_ms_*`=本帧 `vpx_codec_encode` 耗时的窗口内 p50/p95/max（ms，三位小数用整数拼接）；`in_fps`=按 `VideoFrame.timestampNs` 算出的窗口输入帧率；`no_output`=窗口内未产出帧数；`total`=累计编码调用数 |
| `encoder_threads`（Init 一次） | `encoder_threads g_threads=4 row_mt=0 cpu_used=8 cores=8` | `row_mt=0` 表示控制调用返回 OK；非 0 表示该构建未生效（需上报） |

---

## 4. 改动（acceptance 第 3/4/5 条）

| 文件:行 | 改动 | 依据 |
| --- | --- | --- |
| `encoder/vp9_encoder.cpp`（新增常量 `kMaxEncoderThreads=4` + `ResolveEncoderThreads()`，`ApplyFrozenConfigLocked` 里 `cfg_.g_threads`） | `g_threads` 由 **1 → min(核数−1, 4)**（留一颗给采集/渲染/网络） | 单线程尾延迟 p95 19 ms 与帧间隔 p95 175–244 ms 抖动 |
| `encoder/vp9_encoder.cpp`（`Init()` 与尺寸重建路径，`enc_init` 之后） | `vpx_codec_control(&codec_, VP9E_SET_ROW_MT, 1)`（`#ifdef VPX_CTRL_VP9E_SET_ROW_MT`） | 头里存在该控制（`third_party/libvpx/include/vpx/vp8cx.h:576`）、控制表里有 `ctrl_set_row_mt`（`vp9_cx_iface.c:2245`）；设备 libvpx 构建为 `CONFIG_MULTITHREAD=1` |
| `encoder/encoder_rate_policy.h`（新增） | `CeilKbpsFromBps`（向上取整）、`ApplyTotalFloorKbps`（下限 30 kbps）、`ShouldApplyRates`（<10% 且 <2 s 去抖） | §2.3 第 2 条 |
| `encoder/layer_bitrate_allocator.{h,cpp}` | `rc_kbps = CeilKbpsFromBps(total)` + 下限；`VpxLayerRates` 新增 `requested_total_bps/applied_total_bps/total_floor_clamped` | applied_bps ≥ requested_bps |
| `encoder/vp9_encoder.cpp`（`SetRates`） | 去抖（跳过 `vpx_codec_enc_config_set`）+ 一行 `encoder_rates` 对照 + 下限 WARN | 一次通话 2072 次 SetRates |
| `encoder/vp9_encoder.cpp`（`Encode` + `RecordPerfLocked`） | 每帧记录 `vpx_codec_encode` 耗时与产出，60 帧聚合 | acceptance 第 1 条 |

**参数表（before → after）**

| 参数 | before | after | 说明 |
| --- | --- | --- | --- |
| `cfg_.g_threads` | 1 | `min(核数−1, 4)`（真机 8 核 → 4） | 宿主 A/B 见下 |
| `VP9E_SET_ROW_MT` | 未设置 | 1（控制存在时） | 行级并行 |
| `cpu-used` | 8 | 8（不变） | 已是最快的实时档 |
| `tile-columns` | 未设置（libvpx 自动） | 不变 | 避免改变码流结构；后续可选调优 |
| `rc_target_bitrate` | `total/1000`（截断） | `ceil(total/1000)`，下限 30 kbps | 小码率档不再欠额 |
| `setRates` 重配 | 每次调用都重配 | <10% 且 <2 s 跳过（仍更新记账与日志） | 去抖 |
| 目标 fps | 只跟随入参（无下调） | 同前 + `encoder_perf in_fps` 可观测 | 见 §2.3 第 5 条 |

### 4.1 宿主 A/B（`webrtc-build/t55-harness`，480×640，400 帧，`generic-gnu` libvpx）

```
--path=direct480 --frames=400 --threads=1        produced=400 bytes=748897  PERF n=400 p50=15.33ms p95=19.10ms max=27.02ms
--path=direct480 --frames=400 --threads=4 --row-mt produced=400 bytes=748897 PERF n=400 p50=9.34ms  p95=11.76ms max=24.56ms
```
⇒ **p50 −39%（15.33→9.34 ms）、p95 −38%（19.10→11.76 ms）、max −9%**，且**输出字节完全一致**（748 897 B）⇒ 无码流体积回归。

---

## 5. 验证（容器内 / 宿主真实执行）

1. 码率策略单测（含 **old-red 对照**）：`encoder/encoder_rate_policy_host_test.cpp` → **failures=0 / exit=0**（20 项）。
   ```bash
   NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
   cd code/webrtc-demo
   $NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
     -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra \
     -DENCODER_RATE_POLICY_HOST_TEST -DENCODER_RATE_POLICY_FREESTANDING -I app/src/main/cpp \
     app/src/main/cpp/encoder/encoder_rate_policy_host_test.cpp \
     -o <工作区>/t85-work/rate_policy_test -Wl,-e,_start -Wl,--build-id=none
   <工作区>/t85-work/rate_policy_test ; echo "exit=$?"
   ```
   old-red 断言（证明单测能抓回归）：`old-red: old impl gives 6 kbps (< requested)`（旧 `total/1000`）、
   `old-red: old impl allows 6 kbps total`（旧无下限）。
2. 真实目标语法检查：`clang++ --target=aarch64-linux-android26 -std=c++17 -fno-exceptions -fno-rtti -Wall -Wextra
   -I app/src/main/cpp -I third_party/libvpx/include -fsyntax-only` → `encoder/vp9_encoder.cpp` **exit=0**、
   `encoder/layer_bitrate_allocator.cpp` **exit=0**（无诊断）。
3. 宿主 harness A/B：见 §4.1（`PERF` 行）。
4. 既有单测：`encoder/i420_rotator_corners_host_test.cpp`（t57，39 断言）不受影响（未改 rotator）。

---

## 6. 与默认编码路径的差异（必须写清）

- 默认编码（`useDefaultEncoder=true`）走 libwebrtc 的硬件优先工厂：小米机型上大概率命中
  **MediaCodec 硬编（H.264/VP8）**，编码在 SoC 的专用块上完成，**不占 CPU 大核**、单帧耗时通常 <5 ms 且方差小；
  同一网络下因此更流畅。
- 自研路径是 **CPU 软编 VP9**（`SelfVp9Libvpx`，libvpx `generic/arm64` C+NEON 内核）：真机实测单帧 p50 11.2 ms（n2）/3.7 ms（n3），
  p95 19 ms，且与采集/渲染抢核。本次用 `g_threads=4 + row-mt` 把宿主 p50/p95 压了约 39%/38%，把差距缩小但**不可能完全消除**。
- 结论：若用户仍反馈不如默认编码流畅，下一步应评估「自研 VP9 仅用于学习/对照，通话默认走硬编」的产品口径，
  这属于 captain 的交付口径决策。

## 7. 未验证项与真机复测建议

| # | 未验证项 | 下一轮真机只看这几行 |
| --- | --- | --- |
| U1 | 多线程是否真在真机生效 | `native.log` 的 `encoder_threads g_threads=4 row_mt=0 …`（`row_mt≠0` ⇒ 未生效，需上报 rebuild） |
| U2 | 卡顿是否改善 | `encoder_perf` 行的 `encode_ms_p50/p95/max` 与 `in_fps`；p95 应从 ~19 ms 降到 ~12 ms 级，`in_fps` 是否回升到 25–30 |
| U3 | 码率口径 | `encoder_rates requested_bps=… applied_total_bps=…`（applied ≥ requested）、`encoder_rate_floor` 是否出现（出现=分配过低档）、`debounced=1` 的比例（应显著 >0，说明去抖生效） |
| U4 | 产出/发送缺口（n2 的 requested 964 kbps vs up_bps 167–265 kbps） | `encoder_perf` 的 `no_output`、`encoder_bitrate.csv` 的 `encoded_bytes`×`in_fps` 与 `stats_sample up_bps` 三者对齐 |
| V-1 | Kotlin/Java 侧未编译（容器无 JDK/SDK）；`setRates` 去抖在 C++ 侧实现，Kotlin 未改 | 构建任务编译 + 单测 |
| V-2 | 未 commit（按任务纪律）；改动文件见 §4 | captain 构建任务统一提交 |
| V-3 | 真机 `row-mt` 与 dotprod/i8mm SIMD 的交互未测（t56 已修 SIGILL，但多线程+SIMD 组合只在宿主 C 版验证） | U1/U2 的日志 |

## 8. 交付清单（未 commit）

| 文件 | 变更 |
| --- | --- |
| `app/src/main/cpp/encoder/encoder_rate_policy.h` | 新增：ceil/下限/去抖纯函数 |
| `app/src/main/cpp/encoder/encoder_rate_policy_host_test.cpp` | 新增：20 断言（含 old-red） |
| `app/src/main/cpp/encoder/vp9_encoder.{h,cpp}` | 多线程 + row-mt + `encoder_rates`/`encoder_perf`/`encoder_threads` 埋点 + 去抖 |
| `app/src/main/cpp/encoder/layer_bitrate_allocator.{h,cpp}` | ceil 取整 + 下限 + requested/applied 字段 |
| `reports/47-vp9-encode-perf.md` | 本报告（mode 644） |

未改：`nat/**`、`jni/**`（除既有）、`signaling/**`、`doc/**`、`third_party/**`；未跑宿主机 Gradle；未 commit。
仓外产物：`webrtc-build/t85-work/{rate_policy_test,rate_policy_test.log}`、`webrtc-build/t55-harness/harness.cpp`（加了 `--threads/--row-mt` 与 `PERF` 统计，备份 `harness.cpp.bak-t85`）。
