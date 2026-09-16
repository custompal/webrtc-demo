# t86 — 第六次构建发布：打包 t85 自研 VP9 编码性能优化（多线程/row-mt + 码率下限去抖 + 新埋点）

- 执行者：env-installer（宿主机基础设施与环境工程师）
- 任务：t86（work，deps=[t85]，attempt 1，attempt_id `fdabfe59-84cd-45fc-b1e7-fcfc9e479f65`）
- 时间：2026-09-16 19:02 → 19:08（+0800，宿主机 `iZj6cgbzeotp84twpfniy5Z`）
- 原始日志：`code/webrtc-demo/reports/10-t86-build.log`（151 行 / 9,401 B / sha256 `50bce5cef70c9e62eff0b8ce91a87afd488b4278c566c805643a66d45eca7fbe`）
- 上一锚点（t84）：`f2ccd860588f2e297954fd19c4497560742e1007f396bb3592b9d91ef88717e5`

---

## 1. 目标

用 t85 完成后的源码做**第六次**构建并发布，交付自研 VP9 编码性能优化（供用户复测「卡顿是否改善」）：
① **多线程 + row-mt**（`g_threads = min(核数−1, 4)`、`VP9E_SET_ROW_MT`；宿主 A/B：p50 15.33→9.34 ms、p95 19.10→11.76 ms）；
② **码率口径**（`encoder_rate_policy.h` 的 ceil 保证 `applied ≥ requested` + 总码率下限 30 kbps + `<10% 且 <2 s` 去抖）；
③ **新埋点**（`encoder_perf` / `encoder_rates` / `encoder_rate_floor` / `encoder_threads`）。
**本版 native 侧有实质改动** ⇒ `libwebrtcdemo_native.so` 按设计变化；四钉（jar/aar/libjingle/libc++_shared）必须不变。

## 2. 前置（写入静默 + 输入冻结）

| 项 | 实测 |
|---|---|
| 采样时刻 | 2026-09-16 18:58:19 / 19:02:49（+0800） |
| **T1 界**（t85 报告值；实测 `app/src` 最后写入，排除 jniLibs 固定件） | **18:56:35.136**（`cpp/encoder/encoder_rate_policy_host_test.cpp`；次新 `vp9_encoder.cpp` 18:56:09、`vp9_encoder.h` 18:56:09、`layer_bitrate_allocator.{h,cpp}` 18:55:41、`encoder_rate_policy.h` 18:55:35；报告 `reports/47` 18:57） |
| T1+5min 静默复核（19:02:49，即 T1 后 6min14s） | `app/src`（非 jniLibs）近 5 分钟写入 **0**、`signaling/`（排除 logs）**0**、java/gradle 进程 **0** |
| git | HEAD `6c815e29515696804858bb88bee58d3d01c9dd84`；`git status --porcelain -uall` = **8 项**（t85 的 4 改 2 增 + `reports/47` + 本任务日志；**未提交**，符合 t85 纪律） |
| cpp 输入指纹（T0 前 16 位） | `vp9_encoder.cpp 83a54c491411b84a`、`vp9_encoder.h c83ceb687ff06762`、`layer_bitrate_allocator.cpp 3802c7f42206f220`、`encoder_rate_policy.h cde75a731db8609c` |

**固定件 T0/T2 四钉（逐位一致）**

| 固定件 | sha256 |
|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757` |
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099` |
| `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36` |

**jniLibs 口径**：`build_app.sh` 第 5 阶段重拷 `libjingle_peerconnection_so.so` 属预期；**豁免 1 件**（内容逐位 `757cef81…`）、**非豁免写入件数 = 0**。

## 3. 四段串行命令（uid 1000 `admin`；先 check-only，再断言静默）

| # | 命令 | EXIT | 摘要 |
|---|---|---|---|
| 1 | `bash scripts/build_app.sh --check-only` | **0** | FAIL 行 0；「前置校验全部通过（未构建）」 |
| 2 | `./gradlew --no-daemon --no-build-cache -PwebrtcDemo.skipNative=true :app:compileDebugKotlin` | **0** | `BUILD SUCCESSFUL in 27s`；**15 actionable tasks: 15 up-to-date**（Kotlin 侧自上一轮未变，全命中增量）；0 错误 |
| 3 | `./gradlew --no-daemon --no-build-cache clean assembleDebug`（**含 native 构建**） | **0** | `BUILD SUCCESSFUL in 2m 18s`；**FROM-CACHE = 0**；`:app:clean` 出现 = 1；43 tasks = 42 executed + 1 up-to-date；native 痕迹：`externalNativeBuild` 相关行 **2**、ninja/cmake 行 **2**；T1=19:03:21 → T2=19:05:40 |
| 4 | `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 2m 20s`；24 tasks = **24 executed** |

> **契约第 2/3 段说明**：第 2 段按要求使用 `-PwebrtcDemo.skipNative=true`（仅 Kotlin 编译闸门），**交付构建是第 3 段**：`clean assembleDebug` 未带任何 skip 参数 ⇒ native 由 CMake/ninja 真实重建（下方 §4 的 `.so` 变化即证据）。

**构建窗口完整性**：T1=19:03:21 → T2=19:05:40 内 `app/src` 写入 **0**、`signaling/` 写入 **0**。

### 3.1 停手阈值闸门（captain 口径：类数 ≥20 且用例数 ≥191）

```text
XML 汇总: 类数=20  tests=191  failures=0  errors=0  skipped=0   (XML 最新 mtime=2026-09-16 19:08:00)
GATE: 类数 20>=20 ? True ; 用例 191>=191 ? True ; failures=0 errors=0 ⇒ PASS   (GATE_EXIT=0)
```

逐类明细（tests / fail / err / skip）：AppConfigUrlTest 8、LogLevelFilterTest 6、NativeInterfaceContractTest 4、PongLivenessTest 8、ReconnectBudgetTest 5、SignalingErrorPolicyTest 21、SignalingIdentityTest 11、CallSessionSlotTest 8、CallSurvivabilityTest 19、ConnectionStatusTrackerTest 29、MediaAliveSuppressionTest 10、PendingRemoteMessagesTest 7、IceCandidateInfoTest 8、IceWatchdogPolicyTest 6、JniBindingClasspathTest 6、LoopbackCandidatesTest 5、RemoteCandidateAccountingTest 5、RendererRecoveryPolicyTest 7、SessionLifecycleTest 10、TurnTcpFallbackTest 8。

合计 **20 类 / 191 例 / 0 失败**，与 t84 基线一致（t85 未改 Kotlin 测试，符合预期）。

## 4. 产物

| 项 | 值 |
|---|---|
| 绝对路径 | 宿主机 `/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk` |
| **sha256（新交付锚点）** | **`48a04b2fa60f2941f7aec2c493be4cedcffce9432078c4deca00201f78796842`** |
| 字节数 | **33,439,877 B**（较 t84 的 33,436,269 B **+3,608 B** ⇒ 本版尺寸不再与前几版相同） |
| mtime | 2026-09-16 19:05:39.886152889 +0800 |

**新 `libwebrtcdemo_native.so`（本版核心变化）**

| | 字节数 | sha256 |
|---|---|---|
| **新（本锚点）** | **1,278,784** | **`043e851ac358d844f783423dfcef8f0aca0e94a190b9995fa201a6b0018e6fff`** |
| 旧（t84 锚点 `f2ccd860`） | 1,275,176 | `d49eafc3e7cdeaa296579f039dfda75b0dc34b76885b173dc61e3c1a426212a5` |
| 结论 | **+3,608 B** | **CHANGED = True**（符合 t85 native 改动的预期；`webrtc/**`/`cpp/**` 之外无 native 变更） |

**四个 `.so` 的 ELF `p_align`（APK 内实测，全部 `0x4000`）**：`libandroidx.graphics.path.so 41e9a793` / `libc++_shared.so c9dbf4ec` / `libjingle_peerconnection_so.so 757cef81` / `libwebrtcdemo_native.so 043e851a` ⇒ 均 `['0x4000']`。

**与 t84 锚点的条目级差异**：165 → 165 entries，相同 **157**、不同 **8**、仅旧 0、仅新 0。
不同条目 = `classes3/5/6/9/11/12/14.dex` **+ `lib/arm64-v8a/libwebrtcdemo_native.so`**（1,275,176 → 1,278,784）；其余三件 `.so` 与 `resources.arsc`、`AndroidManifest.xml` 逐字节 **SAME**。

## 5. 旁证（对 **`served` 下载件本体**取证，非构建输出）

**`.so` 侧（C++ 字符串，t85 新埋点）**：`encoder_perf` = 1、`encoder_rates` = 2、`encoder_rate_floor` = 1、`encoder_threads` = 1；另 `row_mt` = 6、`g_threads` = 2 ⇒ 多线程/row-mt 与四个新埋点键**确实在交付的 native 库里**。
**dex 侧（前几轮键保留）**：`ice_watchdog_stale_tier` = 1、`ice_error_cleared` = 1、`ice_candidate_remote_total` = 1（另构建期打印 `remote_trickled`=1、`via_replay`=1、`restart_ice`=2、`signaling_lost`=2）。

## 6. 发布与四方哈希对账（`publish_apk.sh` 正式模式，全程 uid 1000）

| 步 | 实测 |
|---|---|
| ⓪ 权限预检 | 四目录均可写 ✔ |
| ① 分片 staging 校验 | 拼接 sha == 新锚点；`SHA256SUMS` 自校验通过；4 片 = 8,388,608×3 + 8,274,053 |
| ② 归档旧件 | 旧 served → `/opt/apk-http/artifacts/app-debug-f2ccd860.apk`；旧 parts → `parts-archive/parts-f2ccd860-20260916-190808`（6 件） |
| ③④ 安装 | parts 逐文件 `mv -f` 原子替换；served `.new`+`mv -f` 原子替换（served mtime 19:08:09） |
| ⑤ **提交点** | 最后写 `parts/SOURCE.sha256` = `48a04b2f…6842` |
| ⑥ 四方对账 | 构建输出 = served = `/opt/dsh-workspaces/artifacts/app-debug-48a04b2f.apk` = parts 拼接 = `SOURCE.sha256` = **`48a04b2fa60f2941f7aec2c493be4cedcffce9432078c4deca00201f78796842`** ✔ |
| ⑧ 回滚命令 | 已打印（目标 `f2ccd860`：`artifacts/app-debug-f2ccd860.apk` + `parts-archive/parts-f2ccd860-20260916-190808/` + 最后写回 `SOURCE.sha256`） |

`SHA256SUMS` 自校验：`part00…part03` 4/4 **OK**。

## 7. 服务与公网复验

| 检查 | 结果 |
|---|---|
| `apk-http` | `enabled` + `active`，MainPID **664402** 未重启（零停机） |
| 回环 `HEAD` / `Range` | 200（`Content-Length: 33439877`、`Accept-Ranges: bytes`）/ 206（1024 B） |
| 公网 `HEAD` | **200**，`Content-Length: 33439877`，`Accept-Ranges: bytes`，`Last-Modified: Wed, 16 Sep 2026 11:08:09 GMT` |
| 公网 `Range 0-1023` | **206**（1024 B） |
| 公网**全量**下载 sha256 | `48a04b2fa60f2941f7aec2c493be4cedcffce9432078c4deca00201f78796842` ✅ |

## 8. 收尾与纪律

- 属主归一：`chown -R 1000:1000 app/build app/.cxx .gradle .kotlin` 后 root 条目 = **0 / 0 / 0**；`/opt/apk-http` 四目录 `admin:admin`、文件 0644、root 条目 **0**。
- 未改任何源码（`app/**`、`signaling/**`、`webrtc/**`、`cpp/**` 只读；唯一 `app/src` 写入为 `build_app.sh` 既有 jniLibs 重拷，内容不变且已豁免）；未做 git 提交（HEAD 仍 `6c815e29`，8 项在途，含 t85 的 native 改动与 `reports/47`）。
- 本任务在 `code/webrtc-demo/` 内写入的文件：`reports/10-t86-build.log`、`reports/37-t86-build-publish.md`（uid 1000）；仓外：`/opt/dsh-workspaces/tmp/t86-build.sh`、`/opt/apk-http/**`。
- 未触碰 `/opt/signaling` 与 coturn。

## 9. 未验证项（不得读作通过）

1. **真机性能与编码行为（本版核心目标）**：`encoder_threads g_threads=4 row_mt=…`（t85 判据 U1：`row_mt` 是否为非 0）、`encoder_perf` 的 `encode_ms_p95` 是否降到 ~12 ms 级、`in_fps` 是否回到 25–30、`encoder_rates` 是否 `applied ≥ requested` 与去抖占比、产出发送缺口（U2–U4）—— 需用户真机复测。
2. **row-mt 的 arm64 运行时可用性**：宿主 A/B 只证明参数与收益方向；真机是否命中 `VP9E_SET_ROW_MT` 分支以 U1 日志为准。
3. **APK 非逐字节可复现**；身份以 sha256 为准（本版尺寸与前几版不同，同尺寸巧合不再出现）。
4. **锚点与源码对应性**：锚点 `48a04b2f…` 对应 HEAD `6c815e29` + 8 项未提交改动（t85 改动仍在工作树）。
5. **服务端/设备侧**：未重部署 `/opt/signaling`；未做真机安装校验（仅 HTTP 200/206 + 全量 sha）。
