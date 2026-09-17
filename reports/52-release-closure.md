# 52 · 交付收口记录（Release Closure）

> 作者：captain（交付收口）｜日期：2026-09-17｜依据：用户 2026-09-17「视频通话更流畅了…没有异常就着手归档」

## 1. 最终交付锚点

| 项 | 值 |
|---|---|
| APK sha256 | `59c75778fb457e8c6d4c7955b349dfa4fde784fa616e852419e3b007e1e41d07` |
| 字节数 | 33 472 645 B |
| mtime | 2026-09-16 20:46:03.435 +0800 |
| 下载 | http://47.238.144.66:8080/app-debug.apk （分片：`/parts/` + `SHA256SUMS` + `SOURCE.sha256`） |
| 归档副本 | `/opt/apk-http/artifacts/app-debug-59c75778.apk`（宿主）、`/data/dsh/home/workspace/artifacts/app-debug-59c75778.apk` |
| 构建输入 | HEAD `4130ddc`（源码树与现 HEAD 的 `app/src` 无差异） |
| 单测 | **24 类 / 216 例 / 0 失败**（Gradle `:app:testDebugUnitTest`，XML 实测） |

## 2. 提交链（HEAD 最新提交）

| 提交 | 内容 |
|---|---|
| `5b7efe0` | t67/t68/t70/t71/t74/t75：服务端房间宽限期 90 s、客户端可存活化、重连预算 63 s、ICE restart 顺序与 glare 修复 |
| `9212021` | t78 独立交付验证（verdict=pass）+ 终报 §15 契约偏差登记 |
| `9a02edc` | t79 部署 unit 与现网逐参数对齐（`-room-grace 90s`） |
| `67edd13` | t82 TURN 暴露面「接受风险 (C)」登记 |
| `6c815e2` → `5ef9d72` → `8ccbbed` | t84/t81/t83 构建发布与诊断精度（ICE 误报横幅根治、候选计数可信化） |
| `4130ddc` | t92 关闭 FrameDropper（`WebRTC-FrameDropper/Disabled`） |
| `4ddcced` | t93 构建发布记录（现役锚点） |

## 3. 最终件包含的修复（按落地顺序）

1. **服务端**（t67/t70）：WS 瞬断不再销毁房间；席位宽限期默认 **90 s**（`-room-grace 90s`，现网已部署 `8708629e…`）；宽限期内不发 `peerLeft`；同身份重连接管席位。
2. **客户端可存活化**（t68/t80）：pong 容忍（4 次/20 s）；`peerLeft` 与重连期 `ROOM_NOT_FOUND` 不再自动退房（可恢复态）；ICE 看门狗基数/档位守卫 + 误报横幅恢复清除；媒体存活期不报 ICE 失败。
3. **重连预算**（t71/t74）：复用 `rejoinDelayMs`，1/2/4/8 s ×10 = **63 s**，断言 **<75 s**；每次重连前显式关闭旧 socket。
4. **ICE restart**（t75）：先 `restartIce("rejoin")` 再发 offer（修「offer 不带 iceRestart」真 bug）；仅 `PeerJoined` 侧发起（防 glare）；重连侧 8 s 兜底。
5. **编码性能**（t85）：`g_threads = min(核数−1,4)` + `row-mt`（宿主 A/B p50 15.33→9.34 ms、p95 19.10→11.76 ms，输出字节一致）；`encoder_rate_policy`（ceil 保证 applied ≥ requested、30 kbps 下限、<10%/<2 s 去抖）。
6. **码率地板**（t89）：`getResolutionBitrateLimits()` 的 min 由 0 改为官方 VP9 表（每档 30 kbps；480×270 启动 120 kbps、640×360 启动 190 kbps）——修复「同链路默认编码 1.7 Mbps vs 自研 40 kbps」的崩塌根因。
7. **质量降级**（t91 前置）：`ScalingSettings(24,37)`，拥塞时先降分辨率保帧率（真机日志已见 `QualityScalerResource … Adapted up successfully`）。
8. **关闭 FrameDropper**（t92）：Java 编码器 `has_trusted_rate_controller` 恒 false（`sdk/android/src/jni/video_encoder_wrapper.cc:124-140` 未设置）⇒ FrameDropper 常开；经 field trial `WebRTC-FrameDropper/Disabled` 关闭，**输入 30 fps 不再被丢到 ~20 fps**。
9. **兜底**（t87）：编码跟不上（10 窗口内 ≥8 坏秒）⇒ 通话内切默认编码，5 s 未确认则下次通话生效；诊断页三态可强制。
10. **发布链**（t77）：`/opt/apk-http` 属主 uid 1000、`SOURCE.sha256` 作最后写的提交点、`publish_apk.sh` 含身份守卫与回滚打印。

## 4. 最终一轮真机体检（2026-09-17 04:34–04:49Z，用户日志）

| 指标 | 实测 | 判定 |
|---|---|---|
| `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/` | 已加载（两端） | ✅ |
| `Drop Frame`（webrtc 日志） | **258/449 → 0** | ✅ 修复生效 |
| `encoder_perf in_fps` | n6 p50 **30**（min 21/max 30）；n7 p50 22（受传入 fps 20–22 限制） | ✅ 无丢帧 |
| `encode_ms_p95` | p50 9.8 ms（p90 15.2 / max 33.5） | ✅ 远小于 33 ms 预算 |
| `encoder_fallback_decision` | `action=keep_self bad_s=0 reason=healthy out_fps=30.4/25.5` | ✅ 未误切换 |
| 码率 | up≈1.0–1.16 Mbps，avail 1.6–2.2 Mbps（RELAY） | ✅ 不再塌到 40 kbps |
| 质量降级 | `QualityScalerResource … Adapted up successfully` | ✅ 启用 |
| RTT / 丢包 | n6 RTT 54–65 ms、loss 0；n7 RTT 128–217 ms、loss 最高 11.8% | ⚠️ 网络侧波动（非缺陷） |
| 渲染 | `eglrenderer Dropped=0`、耗时 0.5–1.4 ms | ✅ 非瓶颈 |

**非缺陷但会被日志提及的项目**（已核实为设计行为）：`ice_candidate_filtered … reason=loopback`（t60 过滤回环候选）、`room_not_found_action=keep_call`（t68 可恢复态）、`ice_turn_error code=701`（回环候选被过滤后 coturn 的 STUN/TURN 超时）、`camera_error … device policy`（Android 侧一次相机策略错误）、跨会话遗留的 `ROOM_NOT_FOUND` 计数。

## 5. 已知限制与未验证项（诚实清单）

1. **中继依赖**：两端 NAT 均为 Symmetric ⇒ 无 P2P，媒体经 TURN 中转（RTT 54–217 ms、偶发丢包），拥塞时帧率/画质由带宽决定；默认编码在同级丢包下是否同样掉到 ~20 fps **未做对照实测**。
2. **APK 非逐字节可复现**（D8 dex 分区不稳定）；身份一律以 sha256 为准。
3. **契约 errata**（详见 `reports/99-final-report.md §15`）：`doc/09 §6` 重连 3 s/3 次 → 1/2/4/8 s×10；`doc/14:489/:490` 旋转语义（`kBakeRotationInEncoder=false` 直通，真机直通方向正确）；coturn `use-fingerprint` → `fingerprint` 命名；`doc/14:692`「rotation 已烘进 I420」被真机证据证伪。
4. **TURN 暴露面**：按用户 2026-09-16 决策 **(C) 接受风险并记录**（`reports/45-turn-exposure-accepted-risk.md`）；24 h 实况：认证强制生效、无未授权成功分配；复评触发条件 T1–T7 已在报告中。
5. **运维遗留**：`parts-archive` 保留策略未启用（现 4+ 份）；`/opt/signaling` 未在本轮重部署或活体复测（现网 `8708629e…` 已由 t78 独立复核）。
6. 真机未覆盖：长时间（>30 min）稳定性、弱网极限（>20% 丢包）、8 s 兜底触发率、自动降级在真实低端机上的效果。

## 6. 回滚

```bash
# 回滚下载件到指定历史锚点（示例：t90）
cp -a /opt/apk-http/artifacts/app-debug-0f60d901.apk /opt/apk-http/served/app-debug.apk
# 或按 publish_apk.sh 打印的回滚命令执行（含 parts 归档恢复）
```
历史锚点（`/opt/apk-http/artifacts/`）：`59c75778`(现役) ← `0f60d901` ← `48a04b2f` ← `f2ccd860` ← `93773a8a` ← `ff93c2e4` ← `3190ef73`。

## 7. 归档结论

- 交付件：最终 APK（§1）、仓库（HEAD，含全部源码/报告/脚本）、宿主机服务（coturn + signaling 90 s 宽限期 + apk-http 发布链）。
- 团队：按用户指示归档（成员与任务板定格于本记录）。
- 后续如再复测发现问题：以本记录 §1 锚点与 §6 回滚路径为基线，新建任务按「目标 HEAD + 范围 + T1」流程继续。
