# t90 — 统一构建发布：合并打包 t87（编码自动降级兜底）+ t89（视频流码率地板修复）

- 执行者：env-installer（宿主机基础设施与环境工程师）
- 任务：t90（work，deps=[t87,t89]，attempt 1，attempt_id `40fc8e54-597d-44ea-a629-76e6ba3df8c3`）
- 时间：2026-09-16 20:21 → 20:30（+0800，宿主机 `iZj6cgbzeotp84twpfniy5Z`）
- 原始日志：`code/webrtc-demo/reports/10-t90-build.log`（155 行 / 9,757 B / sha256 `6a811bc7e43eb9c6cca4a2da70f2bcf0d4455b92bc33c6f88f724d096b2605e7`）
- 上一锚点（t86）：`48a04b2fa60f2941f7aec2c493be4cedcffce9432078c4deca00201f78796842`
- 前序：**t88（单打 t87）已 failed/中止**（构建窗口内源码被 t89 落盘改写、输入不连贯、未发布）⇒ 本任务为其合并替代件。

---

## 0. 范围说明（重要，如实登记）

t90 的**契约正文未随派发送达**（我只收到 t88 的派发文本 + 任务牌上的标题「统一构建发布：合并打包 t87 + t89 并刷新公网快照」），故按既定构建口径执行：四段串行命令 + 计数闸门 + `publish_apk.sh` 发布 + 四方对账 + 公网复验，闸门取 **t87 基线（22 类/206 例）+ t89 新增（1 类/7 例 `Vp9BitrateLimitsTest`）= ≥23 类 / ≥213 例**。
另：动工时的 HEAD `da6c025` 除 t87+t89 外，还包含 **t91 前置**的一次提交（`feat(encoder): 启用质量降级 ScalingSettings(24,37)`，作者 native-dev）与 `reports/49` 的 §8/§9 追加。⇒ **本锚点实际含 t87 + t89 + t91 前置**，请 captain 在交付账本中按此登记（若需严格只含 t87+t89，需回到 `c1d06cf` 之前的树，但那已将 t89 文件一并提交，工作树无法回退到“纯 t87”状态）。

## 1. 前置（写入静默 + 输入冻结）

| 项 | 实测 |
|---|---|
| 采样时刻 | 2026-09-16 20:17:53 / 20:21:40（+0800） |
| **T1 界**（实测 `app/src` 最后写入，排除 jniLibs 固定件） | **2026-09-16 20:15:38.476**（`app/src/main/kotlin/…/encoder/Vp9VideoEncoder.kt`，即 t91 前置的 ScalingSettings 改动；次新 `Vp9BitrateLimitsTest.kt` 20:11:44） |
| T1+5min 静默复核（20:21:40，即 T1 后 6min02s） | `app/src`（非 jniLibs）近 5 分钟写入 **0**、`signaling/`（排除 logs）**0**、java/gradle 进程 **0**；工作树 **干净（dirty=0）** |
| git | HEAD `da6c025dbb57145fb05bc7e79bbdd6d6288c424c`（提交信息含 t87 + t89 + t91 前置 + `reports/49` §8/§9 + t88 中止记录） |
| 构建输入关键源码（T0 前 16 位） | `CallSession.kt 58b8f44ef5f84f5a`、`CallSurvivability.kt 43bac8eb40479534`、`CallViewModel.kt 97c08862a5929b47`、`RemoteCandidateAccountingTest.kt 56f5b49a3c820b22`、`IceWatchdogPolicyTest.kt b5d3da08c69ef5ab` |

**固定件 T0/T2 四钉（逐位一致）**

| 固定件 | sha256 |
|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757` |
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099` |
| `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36` |

**jniLibs 口径**：`build_app.sh` 第 5 阶段重拷 `libjingle_peerconnection_so.so` 属预期；**豁免 1 件**（内容逐位 `757cef81…`）、**非豁免写入件数 = 0**。

## 2. 四段串行命令（uid 1000 `admin`；先 check-only，再断言静默）

| # | 命令 | EXIT | 摘要 |
|---|---|---|---|
| 1 | `bash scripts/build_app.sh --check-only` | **0** | FAIL 行 0；「前置校验全部通过（未构建）」 |
| 2 | `./gradlew --no-daemon --no-build-cache -PwebrtcDemo.skipNative=true :app:compileDebugKotlin` | **0** | `BUILD SUCCESSFUL in 1m 41s`；15 tasks（1 executed / 14 up-to-date）；0 错误 |
| 3 | `./gradlew --no-daemon --no-build-cache clean assembleDebug`（含 native） | **0** | `BUILD SUCCESSFUL in 2m 34s`；**FROM-CACHE = 0**；`:app:clean` 出现 = 1；43 tasks = 42 executed + 1 up-to-date；native 痕迹 externalNativeBuild=2 / ninja-cmake=2；T1=20:23:26 → T2=20:26:01 |
| 4 | `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 2m 26s`；24 tasks = **24 executed** |

**构建窗口完整性**：T1=20:23:26 → T2=20:26:01 内 `app/src` 写入 **0**、`signaling/` 写入 **0** ✅（这正是 t88 失败的那一项，本轮通过）。

### 2.1 停手阈值闸门（≥23 类 且 ≥213 例）

```text
XML 汇总: 类数=23  tests=213  failures=0  errors=0  skipped=0   (XML 最新 mtime=2026-09-16 20:28:28)
GATE: 类数 23>=23 ? True ; 用例 213>=213 ? True ; failures=0 errors=0 ⇒ PASS   (GATE_EXIT=0)
```

新增/关键类：`EncoderFallbackPolicyTest` 10、`EncoderFallbackControllerTest` 5（t87）、**`Vp9BitrateLimitsTest` 7（t89）**；其余 20 类与 t86 相同（AppConfigUrl 8、LogLevelFilter 6、NativeInterfaceContract 4、PongLiveness 8、ReconnectBudget 5、SignalingErrorPolicy 21、SignalingIdentity 11、CallSessionSlot 8、CallSurvivability 19、ConnectionStatusTracker 29、MediaAliveSuppression 10、PendingRemoteMessages 7、IceCandidateInfo 8、IceWatchdogPolicy 6、JniBindingClasspath 6、LoopbackCandidates 5、RemoteCandidateAccounting 5、RendererRecoveryPolicy 7、SessionLifecycle 10、TurnTcpFallback 8）。

## 3. 产物

| 项 | 值 |
|---|---|
| 绝对路径 | 宿主机 `/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk` |
| **sha256（新交付锚点）** | **`0f60d90138f6f7bde0f583d0626cbcd0050564287d56fd47d022467a80cafe45`** |
| 字节数 | **33,472,645 B**（较上一锚点 33,439,877 B **+32,768 B**） |
| mtime | 2026-09-16 20:26:00.908707442 +0800 |

**native `.so` 与上一锚点逐字节相同**：`libwebrtcdemo_native.so` = 1,278,784 B / `043e851ac358d844f783423dfcef8f0aca0e94a190b9995fa201a6b0018e6fff`（**CHANGED = False**）⇒ t87/t89/t91-前置 均为 Kotlin 侧改动（符合预期）。

**四个 `.so` 的 ELF `p_align`（APK 内实测，全部 `0x4000`）**：`41e9a793`（androidx.graphics.path）/ `c9dbf4ec`（libc++_shared）/ `757cef81`（libjingle）/ `043e851a`（自研 native）。

**与 t86 锚点（`48a04b2f`）的条目级差异**：165 → 165 entries，相同 **157**、不同 **8**、仅旧 0、仅新 0。
不同条目 = `classes3/4/5/6/9/11/12/14.dex`（共 8 个 dex，含 t87 的 fallback 判据 + t89 的码率地板 + t91 前置的 ScalingSettings）；四件 `.so` 与 `resources.arsc`、`AndroidManifest.xml` 逐字节 **SAME**。

## 4. 旁证（对 **`served` 下载件本体**取证）

**dex 侧**：`encoder_fallback_decision` = 1、`encoder_fallback_applied` = 1、`encoder_fallback_probe` = 2（t87）、**`bitrate_limit` = 1、`minStartBitrateBps` = 1**（t89）、`encoder_fallback_switch_signal` = 1；并保留 `ice_watchdog_stale_tier` = 1、`ice_error_cleared` = 1、`ice_candidate_remote_total` = 1（t80/t83）。
**`.so` 侧**：`encoder_perf` = 1、`encoder_rates` = 2、`encoder_rate_floor` = 1、`encoder_threads` = 1（t85 埋点仍在）。

## 5. 发布与四方哈希对账（`publish_apk.sh` 正式模式，全程 uid 1000）

| 步 | 实测 |
|---|---|
| ⓪ 权限预检 | 四目录均可写 ✔ |
| ① 分片 staging 校验 | 拼接 sha == 新锚点；`SHA256SUMS` 自校验通过；4 片 = 8,388,608×3 + 8,306,821 |
| ② 归档旧件 | 旧 served → `/opt/apk-http/artifacts/app-debug-48a04b2f.apk`；旧 parts → `parts-archive/parts-48a04b2f-20260916-202838`（6 件） |
| ③④ 安装 | parts 逐文件 `mv -f` 原子替换；served `.new`+`mv -f` 原子替换 |
| ⑤ **提交点** | 最后写 `parts/SOURCE.sha256` = `0f60d901…fe45` |
| ⑥ 四方对账 | 构建输出 = served = `/opt/dsh-workspaces/artifacts/app-debug-0f60d901.apk` = parts 拼接 = `SOURCE.sha256` = **`0f60d90138f6f7bde0f583d0626cbcd0050564287d56fd47d022467a80cafe45`** ✔ |
| ⑧ 回滚命令 | 已打印（目标 `48a04b2f`：`artifacts/app-debug-48a04b2f.apk` + `parts-archive/parts-48a04b2f-20260916-202838/` + 最后写回 `SOURCE.sha256`） |

`SHA256SUMS` 4/4 **OK**。

## 6. 服务与公网复验

| 检查 | 结果 |
|---|---|
| `apk-http` | `enabled` + `active`，MainPID **664402** 未重启（零停机） |
| 回环 HEAD / Range | 200（`Content-Length: 33472645`、`Accept-Ranges: bytes`）/ 206（1024 B） |
| 公网 HEAD | **200**，`Content-Length: 33472645`，`Accept-Ranges: bytes`，`Last-Modified: Wed, 16 Sep 2026 12:28:39 GMT` |
| 公网 Range 0-1023 | **206**（1024 B） |
| 公网**全量**下载 sha256 | `0f60d90138f6f7bde0f583d0626cbcd0050564287d56fd47d022467a80cafe45` ✅ |

## 7. 与 t88 中止件的关系（隔离声明）

- t88 被中止的产物 `app/build/outputs/apk/debug/app-debug.apk` = 33,472,645 B / `f1eacbf4036c1ade0a5dcb27840cee746eaed776d8a19c440a0c8f47dea20298`（混入产物，**从未发布**）现已被本轮 `clean assembleDebug` **覆盖**；同一路径字节数恰好与本次产物相同（33,472,645 B）但 **sha256 不同** ⇒ 身份以 sha256 为准，`f1eacbf4…` 在交付链中**不存在任何副本**（`artifacts/` 内只有历次已发布锚点与 `t88` 中止前归档的 `48a04b2f`）。
- t88 的中止记录仍在 `reports/37-t88-build-publish.md`（已随 `da6c025` 入库），本任务为其**合并替代件**。

## 8. 收尾与纪律

- 属主归一：`chown -R 1000:1000 app/build app/.cxx .gradle .kotlin` 后 root 条目 = **0 / 0 / 0**；`/opt/apk-http` 四目录 `admin:admin`、文件 0644、root 条目 **0**。
- 未改任何源码（唯一 `app/src` 写入为 `build_app.sh` 既有 jniLibs 重拷，内容不变且已豁免）；未做 git 提交（HEAD 仍 `da6c025`；收尾 `git status -uall` 仅 `?? reports/10-t90-build.log` 与本报告）。
- 本任务在 `code/webrtc-demo/` 内写入的文件：`reports/10-t90-build.log`、`reports/37-t90-build-publish.md`（uid 1000）；仓外：`/opt/dsh-workspaces/tmp/t90-build.sh`、`/opt/apk-http/**`。
- 账本冻结未受影响：`reports/39`=`60f19a27…`、`40`=`a5d4e41b…`、`44`=`1b642bc2…`、`46`=`45dabe49…`、`47`=`db455f82…`、`48`（t87）、`49`（t89）均由各自作者维护，我未触碰。
- 未触碰 `/opt/signaling` 与 coturn。

## 9. 未验证项（不得读作通过）

1. **真机（本锚点核心）**：① t87 降级兜底（`encoder_fallback_decision/probe`、通话内切换或 5 s 退化为下次通话）；② t89 码率地板（`setrates total_bps` 前 5 s 不低于 120 000、全程不低于 30 000，不再出现 40536/37156 量级）；③ t91 前置的 ScalingSettings 效果（拥塞时先降分辨率保帧率）—— 均需用户真机复测。
2. **契约正文缺失**：t90 的正式契约文本未送达，闸门口径由我按 t87/t89 报告推导（≥23 类/≥213 例）；若 captain 有不同阈值请以正式契约更正。
3. **范围含 t91 前置**：本锚点含 `da6c025` 的 ScalingSettings 改动（非 t90 标题所列），已登记；如需严格范围界定请裁定。
4. **APK 非逐字节可复现**；身份以 sha256 为准。
5. **服务端/设备侧**：未重部署 `/opt/signaling`；未做真机安装校验（仅 HTTP 200/206 + 全量 sha）。
