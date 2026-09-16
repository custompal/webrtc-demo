# t93 — 第七次构建发布：打包 t92「关闭 FrameDropper（field trial）」并刷新公网快照

- 执行者：env-installer（宿主机基础设施与环境工程师）
- 任务：t93（work，deps=[t92]，attempt 1，attempt_id `6aa7fc2d-403e-4ce4-96e4-02a4067ec1a4`）
- 时间：2026-09-16 20:41 → 20:50（+0800，宿主机 `iZj6cgbzeotp84twpfniy5Z`）
- 原始日志：`code/webrtc-demo/reports/10-t93-build.log`（155 行 / 9,798 B / sha256 `cdddfb861c58d41e8d4718f0fe3f58cdbacfd1ab4bc72aa5f15e4833f3126be9`）
- 上一锚点（t90）：`0f60d90138f6f7bde0f583d0626cbcd0050564287d56fd47d022467a80cafe45`

---

## 1. 目标

用 t92 完成后的源码构建发布：**关闭 FrameDropper**（field trial `WebRTC-FrameDropper/Disabled/`，经 `PeerConnectionFactory.Builder.setFieldTrials`），消除「真机输入 30 fps 被丢到 ~20 fps」的直接原因（Java 编码器 `has_trusted_rate_controller` 恒 false ⇒ `frame_dropping_enabled=true`）。

## 2. 前置（写入静默 + 输入冻结）

| 项 | 实测 |
|---|---|
| 采样时刻 | 2026-09-16 20:37:55 / 20:41:30（+0800） |
| **T1 界**（t92 报告值，实测 `app/src` 最后写入，排除 jniLibs 固定件） | **2026-09-16 20:35:46.467**（`test/…/webrtc/FrameDropperFieldTrialTest.kt`；源码侧 `webrtc/WebRtcEngine.kt`/`FrameDropperFieldTrial.kt` 20:35:30.6） |
| T1+5min 静默复核（20:41:30，即 T1 后 5min44s） | `app/src`（非 jniLibs）近 5 分钟写入 **0**、`signaling/`（排除 logs）**0**、java/gradle 进程 **0**、工作树 **dirty=0** |
| git | HEAD `4130ddc68061d9012e373bef7d1f2d40e28563aa`（t92 改动已入库）；启动时脚本读到 dirty=1（13:31→20:41 期间无 `app/src` 变更，见 §7） |
| 构建输入关键源码（T0 前 16 位） | `vp9_encoder.cpp 83a54c491411b84a`、`vp9_encoder.h c83ceb687ff06762`、`layer_bitrate_allocator.cpp 3802c7f42206f220`、`encoder_rate_policy.h cde75a731db8609c` |

**固定件 T0/T2 四钉（逐位一致）**

| 固定件 | sha256 |
|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757` |
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099` |
| `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36` |

**jniLibs 口径**：**豁免 1 件**（`libjingle_peerconnection_so.so` 内容逐位 `757cef81…`）、**非豁免写入件数 = 0**。

## 3. 四段串行命令（uid 1000 `admin`；先 check-only，再断言静默）

| # | 命令 | EXIT | 摘要 |
|---|---|---|---|
| 1 | `bash scripts/build_app.sh --check-only` | **0** | FAIL 行 0；「前置校验全部通过（未构建）」 |
| 2 | `./gradlew --no-daemon --no-build-cache -PwebrtcDemo.skipNative=true :app:compileDebugKotlin` | **0** | `BUILD SUCCESSFUL in 1m 47s`；15 tasks（1 executed / 14 up-to-date）；0 错误 |
| 3 | `./gradlew --no-daemon --no-build-cache clean assembleDebug`（含 native） | **0** | `BUILD SUCCESSFUL in 2m 40s`；**FROM-CACHE = 0**；`:app:clean` 出现 = 1；43 tasks = 42 executed + 1 up-to-date；native 痕迹 externalNativeBuild=2 / ninja-cmake=2；T1=20:43:23 → T2=20:46:04 |
| 4 | `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 2m 25s`；24 tasks = **24 executed** |

**构建窗口完整性**：T1=20:43:23 → T2=20:46:04 内 `app/src` 写入 **0**、`signaling/` 写入 **0** ✅。

### 3.1 停手阈值闸门（类数 ≥24 且用例数 ≥216）

```text
XML 汇总: 类数=24  tests=216  failures=0  errors=0  skipped=0   (XML 最新 mtime=2026-09-16 20:48:29)
GATE: 类数 24>=24 ? True ; 用例 216>=216 ? True ; failures=0 errors=0 ⇒ PASS   (GATE_EXIT=0)
```

与预期**逐项一致**：t90 基线 23 类/213 例 + t92 新增 `FrameDropperFieldTrialTest` 1 类/3 例 = **24 类/216 例**。逐类：AppConfigUrl 8、EncoderFallbackController 5、EncoderFallbackPolicy 10、Vp9BitrateLimits 7、LogLevelFilter 6、NativeInterfaceContract 4、PongLiveness 8、ReconnectBudget 5、SignalingErrorPolicy 21、SignalingIdentity 11、CallSessionSlot 8、CallSurvivability 19、ConnectionStatusTracker 29、MediaAliveSuppression 10、PendingRemoteMessages 7、**FrameDropperFieldTrial 3**、IceCandidateInfo 8、IceWatchdogPolicy 6、JniBindingClasspath 6、LoopbackCandidates 5、RemoteCandidateAccounting 5、RendererRecoveryPolicy 7、SessionLifecycle 10、TurnTcpFallback 8。

## 4. 产物

| 项 | 值 |
|---|---|
| 绝对路径 | 宿主机 `/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk` |
| **sha256（新交付锚点）** | **`59c75778fb457e8c6d4c7955b349dfa4fde784fa616e852419e3b007e1e41d07`** |
| 字节数 | **33,472,645 B**（与上一锚点同尺寸；身份以 sha256 为准，见 §7 说明） |
| mtime | 2026-09-16 20:46:03.435559563 +0800 |

**native `.so` 与上一锚点逐字节相同**：`libwebrtcdemo_native.so` = 1,278,784 B / `043e851ac358d844f783423dfcef8f0aca0e94a190b9995fa201a6b0018e6fff`（**CHANGED = False**）⇒ 本版为纯 Kotlin 侧改动（符合 t92 契约预期）。

**四个 `.so` 的 ELF `p_align`（APK 内实测，全部 `0x4000`）**：`41e9a793`（androidx.graphics.path）/ `c9dbf4ec`（libc++_shared）/ `757cef81`（libjingle）/ `043e851a`（自研 native）。

**与 t90 锚点（`0f60d901`）的条目级差异**：165 → 165 entries，相同 **158**、不同 **7**、仅旧 0、仅新 0。
不同条目 = `classes3/5/6/9/11/12/14.dex`；四件 `.so` 与 `resources.arsc`、`AndroidManifest.xml` 逐字节 **SAME**。

## 5. 旁证（对 **`served` 下载件本体**取证）

**dex 侧**：**`WebRTC-FrameDropper/Disabled` = 1**、**`FrameDropperFieldTrial` = 3**（t92 新键，命中），并保留 `encoder_fallback_decision` = 1、`encoder_fallback_applied` = 1、`encoder_fallback_probe` = 2（t87）、`minStartBitrateBps` = 1、`bitrate_limit` = 1（t89）、`ice_watchdog_stale_tier` = 1（t80）。
**`.so` 侧**：`encoder_perf` = 1、`encoder_rates` = 2、`encoder_rate_floor` = 1、`encoder_threads` = 1（t85 埋点仍在）。

## 6. 发布与四方哈希对账（`publish_apk.sh` 正式模式，全程 uid 1000）

| 步 | 实测 |
|---|---|
| ⓪ 权限预检 | 四目录均可写 ✔ |
| ① 分片 staging 校验 | 拼接 sha == 新锚点；`SHA256SUMS` 自校验通过；4 片 = 8,388,608×3 + 8,306,821 |
| ② 归档旧件 | 旧 served → `/opt/apk-http/artifacts/app-debug-0f60d901.apk`；旧 parts → `parts-archive/parts-0f60d901-20260916-204838`（6 件） |
| ③④ 安装 | parts 逐文件 `mv -f` 原子替换（模式归一 0644）；served `.new`+`mv -f` 原子替换 |
| ⑤ **提交点** | 最后写 `parts/SOURCE.sha256` = `59c75778…1d07` |
| ⑥ 四方对账 | 构建输出 = served = `/opt/dsh-workspaces/artifacts/app-debug-59c75778.apk` = parts 拼接 = `SOURCE.sha256` = **`59c75778fb457e8c6d4c7955b349dfa4fde784fa616e852419e3b007e1e41d07`** ✔ |
| ⑧ 回滚命令 | 已打印（目标 `0f60d901`：`artifacts/app-debug-0f60d901.apk` + `parts-archive/parts-0f60d901-20260916-204838/` + 最后写回 `SOURCE.sha256`） |

`SHA256SUMS` 4/4 **OK**。属主：`/opt/apk-http` 四目录 root 条目 **0**、文件 0644；`app/build` root 条目 **0**。

## 7. 服务与公网复验

| 检查 | 结果 |
|---|---|
| `apk-http` | `enabled` + `active`，MainPID **664402** 未重启（零停机） |
| 回环 HEAD / Range | 200（`Content-Length: 33472645`、`Accept-Ranges: bytes`）/ 206（1024 B） |
| 公网 HEAD | **200**，`Content-Length: 33472645`，`Accept-Ranges: bytes`，`Last-Modified: Wed, 16 Sep 2026 12:48:39 GMT` |
| 公网 Range 0-1023 | **206**（1024 B） |
| 公网**全量**下载 sha256 | `59c75778fb457e8c6d4c7955b349dfa4fde784fa616e852419e3b007e1e41d07` ✅ |

**同尺寸说明**：本锚点与上一锚点总字节数相同（均 33,472,645 B）但 sha256 不同、7 个 dex 内容不同 ⇒ **APK 身份一律以 sha256 为准，字节数不作判据**（沿用既有裁定）。

**启动时 dirty=1 的说明**：20:37:55（我的只读采样）为 0，20:41:31（脚本头部）读到 1。该窗口内 `app/src` 无变更（T0 源码哈希固定 + 静默断言通过 + 构建窗口写入=0 三重佐证）；期间仓内唯一写入来自 `build_app.sh` 自身（`.gitignore` 内文件）。**该 1 项未定位**，如实登记，不影响输入冻结性（t92 全部写入 ≤20:35:46，早于本轮 T1+5min 复核）。

## 8. 收尾与纪律

- 未改任何源码（唯一 `app/src` 写入为 `build_app.sh` 既有 jniLibs 重拷，内容不变且已豁免）；未做 git 提交（HEAD 仍 `4130ddc`；收尾 `git status -uall` 仅 `?? reports/10-t93-build.log` 与本报告）。
- 本任务在 `code/webrtc-demo/` 内写入的文件：`reports/10-t93-build.log`、`reports/37-t93-build-publish.md`（均 uid 1000）；仓外：`/opt/dsh-workspaces/tmp/t93-build.sh`、`/opt/apk-http/**`。
- 未触碰 `/opt/signaling` 与 coturn；未触碰 `reports/39/40/44/46/47/48/49/50/51` 等他人账本。

## 9. 未验证项（不得读作通过）

1. **真机（本锚点核心目标）**：`field_trials_set` 是否出现、`Drop Frame:` 行数是否由 258/449 降为 ≈0、`in_fps` 是否贴近 `setrates fps`、`Render fps` 是否与 `in_fps` 同量级、`encoder_reinit` 是否不激增 —— 需用户复测（t92 报告 §V-1..V-4 已给判据）。
2. **回退预案未启用**：若真机 `Drop Frame` 未降为 0，t92 建议改用已弃用的 `InitializationOptions.Builder.setFieldTrials`；本构建未包含该分支。
3. **APK 非逐字节可复现**（同尺寸≠同内容）。
4. **锚点对应 HEAD `4130ddc`**；若后续再改同批文件即失配。
5. **服务端/设备侧**：未重部署 `/opt/signaling`；未做真机安装校验（仅 HTTP 200/206 + 全量 sha）。
6. **启动时 1 项 dirty 未定位**（见 §7 末，已三重佐证不影响输入冻结）。
