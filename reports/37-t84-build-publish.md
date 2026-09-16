# t84 — 第五次构建发布：单独打包 t83 诊断增强（远端候选计数去重 + SDP 分账 + 日志限频）

- 执行者：env-installer（宿主机基础设施与环境工程师）
- 任务：t84（work，deps=[t83]，attempt 1，attempt_id `6a4903bb-7466-4f4c-90dc-6b7f097bc6c6`）
- 时间：2026-09-16 13:28 → 13:35（+0800，宿主机 `iZj6cgbzeotp84twpfniy5Z`）
- 原始日志：`code/webrtc-demo/reports/10-t84-build.log`（136 行 / 8,821 B / sha256 `cefaa279c4defc697cbfb30949f9fba5641088817446900262fe3629cea193ec`）
- 上一锚点（t81）：`93773a8a9d0cbe22e3dc280934d01bb492b51187dab042e33c8691dfad309091`

---

## 1. 目标

用 t83 完成后的源码做**第五次**构建并发布（用户要求「t83 单独打包」）。本版在 t81（含「通话正常却报 ICE 未连通」根治）之上叠加 t83 的**诊断精度增强**（**行为无功能变化**）：
① 远端候选计数去重（回放路径 `viaReplay` ⇒ 同一候选不再计两次）；
② SDP 内候选独立分账（`remote_trickled` / `remote_sdp`，消除「对端候选 -」歧义）；
③ 逐条候选日志限频（首 3 条 + 每 10 条，总数由汇总键保证可见）。

## 2. 前置（写入静默 + 输入冻结）

| 项 | 实测 |
|---|---|
| 采样时刻 | 2026-09-16 13:27:57 / 13:28:14（+0800） |
| **T1 界**（t83 报告值，实测 `app/src` 最后写入，排除 jniLibs 固定件） | **2026-09-16 12:06:36.842**（`test/.../webrtc/RemoteCandidateAccountingTest.kt`；源码侧 `webrtc/CallSession.kt` 12:06:22） |
| T1 后静默（13:27:57，即 T1 后 1h21min） | `app/src`（非 jniLibs）近 5 分钟写入 **0**、`signaling/`（排除 logs）**0**、java/gradle 进程 **0** |
| git | HEAD `8ccbbed2585ee43bea79e59f7d677cbcea6a7a7d`（t83 交付已由 captain 提交；13:27:57 采样 `git status --porcelain -uall` = **0** 项，13:28:14 脚本启动时读得 **1** 项） |
| 构建输入关键源码（T0 前 16 位） | `CallSession.kt 58b8f44ef5f84f5a`、`CallSurvivability.kt 43bac8eb40479534`、`CallViewModel.kt 97c08862a5929b47`、`RemoteCandidateAccountingTest.kt 56f5b49a3c820b22`、`IceWatchdogPolicyTest.kt b5d3da08c69ef5ab` |

> **关于启动时那 1 项 dirty**：13:27:57（我的只读采样）为 0，13:28:14（脚本头部）为 1，**中间无 `app/src` 变更**（T0 源码哈希 + 静默闸门 + 构建窗口写入检查三重佐证）。该窗口内仓内唯一写入来自 `build_app.sh` 自身（`local.properties`、`reports/logs/build_app-<ts>.log`，均为 .gitignore 内文件，收尾 `git status -uall` 未列出）。**该 1 项未能定位**，如实登记；不影响输入冻结性（本轮全部源码写入均早于 12:06:37）。

**固定件 T0/T2 双钉（逐位一致）**

| 固定件 | sha256 |
|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757` |
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099` |
| `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36` |

**jniLibs 口径**：`build_app.sh` 第 5 阶段重拷 `libjingle_peerconnection_so.so` 属预期行为；本轮 **豁免 1 件**（内容逐位 = `757cef81…`）、**非豁免写入件数 = 0**。

## 3. 四段串行命令（uid 1000 `admin`；先 check-only，再断言静默）

| # | 命令 | EXIT | 摘要 |
|---|---|---|---|
| 1 | `bash scripts/build_app.sh --check-only` | **0** | FAIL 行 0；尾部「前置校验全部通过（未构建）」 |
| 2 | `./gradlew --no-daemon --no-build-cache -PwebrtcDemo.skipNative=true :app:compileDebugKotlin` | **0** | `BUILD SUCCESSFUL in 1m 41s`；15 tasks（1 executed / 14 up-to-date）；Kotlin 错误 0 |
| 3 | `./gradlew --no-daemon --no-build-cache clean assembleDebug` | **0** | `BUILD SUCCESSFUL in 2m 18s`；**FROM-CACHE 行数 = 0**；`:app:clean` 出现 = 1；43 tasks = 42 executed + 1 up-to-date；T1=13:29:59 → T2=13:32:18 |
| 4 | `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 2m 18s`；24 tasks = **24 executed** |

**构建窗口完整性**：T1=13:29:59 → T2=13:32:18 内 `app/src` 写入 **0**、`signaling/` 写入 **0**。

### 3.1 停手阈值闸门（captain 口径：类数 ≥20 且用例数 ≥ t83 报告实测值 = 191）

```text
XML 汇总: 类数=20  tests=191  failures=0  errors=0  skipped=0   (XML 最新 mtime=2026-09-16 13:34:37)
GATE: 类数 20>=20 ? True ; 用例 191>=191 ? True ; failures=0 errors=0 ⇒ PASS   (GATE_EXIT=0)
```

逐类明细（tests / fail / err / skip）：

| 类 | tests | 类 | tests |
|---|---|---|---|
| AppConfigUrlTest | 8 | CallSurvivabilityTest | 19 |
| LogLevelFilterTest | 6 | ConnectionStatusTrackerTest | 29 |
| NativeInterfaceContractTest | 4 | MediaAliveSuppressionTest | 10 |
| PongLivenessTest | 8 | PendingRemoteMessagesTest | 7 |
| ReconnectBudgetTest | 5 | IceCandidateInfoTest | 8 |
| SignalingErrorPolicyTest | 21 | IceWatchdogPolicyTest | 6 |
| SignalingIdentityTest | 11 | **RemoteCandidateAccountingTest（t83 新增）** | **5** |
| CallSessionSlotTest | 8 | RendererRecoveryPolicyTest | 7 |
| SessionLifecycleTest | 10 | TurnTcpFallbackTest | 8 |
| JniBindingClasspathTest | 6 | LoopbackCandidatesTest | 5 |

合计 **20 类 / 191 例 / 0 失败**，与 android-dev t83 报告的离线预检（20 类 / 191 例 / 0 failures）**逐项一致**；较 t81 基线（19 类 / 186 例）= **+1 类 / +5 例**（即 t83 新增的 `RemoteCandidateAccountingTest` 5 例）。

## 4. 产物

| 项 | 值 |
|---|---|
| 绝对路径 | 宿主机 `/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk`（容器同路径 `/data/dsh/home/workspace/code/webrtc-demo/...`） |
| **sha256（新交付锚点）** | **`f2ccd860588f2e297954fd19c4497560742e1007f396bb3592b9d91ef88717e5`** |
| 字节数 | **33,436,269 B** |
| mtime | 2026-09-16 13:32:18.186284885 +0800 |

**四个 `.so` 的 ELF `p_align`（APK 内实测，全部 `0x4000`）**

| `.so` | sha256（前 16） | p_align |
|---|---|---|
| libandroidx.graphics.path.so | `41e9a793c43a0f4f` | `0x4000` |
| libc++_shared.so | `c9dbf4ec15e931f5` | `0x4000` |
| libjingle_peerconnection_so.so | `757cef8128bf9151` | `0x4000` |
| libwebrtcdemo_native.so | `d49eafc3e7cdeaa2` | `0x4000` |

自有库 `libwebrtcdemo_native.so`（APK 内）= `d49eafc3e7cdeaa296579f039dfda75b0dc34b76885b173dc61e3c1a426212a5`（与 t81 锚点**逐字节相同** ⇒ 本版未动 `cpp/**`）。

**与 t81 锚点（`93773a8a`）的条目级差异**：165 → 165 entries，相同 **158**、不同 **7**、仅旧 0、仅新 0。
不同条目 = `classes3/5/6/9/11/12/14.dex`；四件 `.so` 与 `resources.arsc`、`AndroidManifest.xml` 逐字节 **SAME** ⇒ 差异全部落在 dex（t83 为 Kotlin 侧诊断改动）。

**观察（如实记录，沿用既有裁定）**：本锚点与 t81 锚点**总字节数相同**（均 33,436,269 B）但内容不同（sha256 不同、7 个 dex 不同）。**APK 身份一律以 sha256 为准，字节数不得作为判别依据**（这是本轮第三次出现同尺寸，属巧合）。

**包体旁证（`served` 下载件本体 dex 串统计）**：**`ice_candidate_remote_total` = 1、`via_replay` = 1、`viaReplay` = 1、`remote_trickled` = 1、`remote_sdp` = 1**（t83 新键全部命中）；并保留 t80 的 `ice_watchdog_stale_tier` = 1、`ice_error_cleared` = 1，以及 `skipped=no_watchdog` = 1、`restart_ice` = 2、`signaling_lost` = 2 ⇒ 下载到手机的包体同时含 t83 诊断增强与 t80 误报根治。

## 5. 发布与四方哈希对账（`publish_apk.sh` 正式模式，**全程 uid 1000，零 root 补步**）

命令：`su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh --apk <构建输出> --log reports/10-t84-build.log"`

| 步 | 实测 |
|---|---|
| ⓪ 权限预检 | served/parts/artifacts/parts-archive 均可写 ✔ |
| ① 分片 staging 校验 | 拼接 sha == 新锚点；`SHA256SUMS` 自校验通过；4 片 = 8,388,608×3 + 8,270,445 |
| ② 归档旧件 | 旧 served → `/opt/apk-http/artifacts/app-debug-93773a8a.apk`；旧 parts → `/opt/apk-http/parts-archive/parts-93773a8a-20260916-133444`（6 件） |
| ③ 安装 parts | 逐文件 `mv -f` 原子替换 + 清理多余分片；模式归一 0644 |
| ④ 安装 served | `.new` → `mv -f` 原子替换（served mtime 13:34:45） |
| ⑤ **提交点** | 最后写 `parts/SOURCE.sha256` = `f2ccd860…17e5` |
| ⑥ 四方对账 | 构建输出 = served = `/opt/dsh-workspaces/artifacts/app-debug-f2ccd860.apk` = parts 拼接 = `SOURCE.sha256` = **`f2ccd860588f2e297954fd19c4497560742e1007f396bb3592b9d91ef88717e5`** ✔ |
| ⑧ 回滚命令 | 已打印（目标 `93773a8a`：`artifacts/app-debug-93773a8a.apk` + `parts-archive/parts-93773a8a-20260916-133444/`，最后写回 `SOURCE.sha256`） |

`SHA256SUMS` 自校验：`part00…part03` 4/4 **OK**。

## 6. 服务与公网复验

| 检查 | 结果 |
|---|---|
| `apk-http` systemd | `enabled` + `active`，MainPID **664402**（启动时刻仍 2026-09-14 18:57:09，**未重启** ⇒ 零停机；进程 user=root） |
| 回环 `HEAD` | 200（`Content-Length: 33436269`、`Accept-Ranges: bytes`） |
| 回环 `Range 0-1023` | 206（1024 B） |
| 公网 `HEAD http://47.238.144.66:8080/app-debug.apk` | **200**，`Content-Length: 33436269`，`Accept-Ranges: bytes`，`Last-Modified: Wed, 16 Sep 2026 05:34:45 GMT` |
| 公网 `Range 0-1023` | **206**（1024 B） |
| 公网**全量**下载 sha256 | `f2ccd860588f2e297954fd19c4497560742e1007f396bb3592b9d91ef88717e5` ✅ |

## 7. 环境与流程事件

1. **身份守卫（t77 ②）**：正式发布以 uid=1000 运行，零 Permission denied、零 root 补步（root 直跑会被拒绝并提示归位命令）。
2. **静默闸门顺序**：先 check-only、再断言静默；jniLibs 固定件同哈希重拷豁免 ⇒ `非豁免写入件数=0、豁免件数=1`。
3. **`.gradle-home` 的 `.lck Permission denied`（t72 修复）**：本轮未复现，第 2 段一次通过；收尾 `app/build`/`.gradle`/`app/.cxx` root 条目均为 **0**。
4. **账本冻结核查（新规则）**：`reports/39` = `60f19a27…0a22ff`、`reports/40` = `a5d4e41b…6dd2c8`、`reports/44` = `1b642bc2…469a`、`reports/46`（t83）= `45dabe49cb2fe86a778e45cd586db32eb6c050d04016b7c3d47f7e9fe699c2e0` —— **自各自写入后均未被第三方改写**。
5. **启动时 1 项 dirty 未定位**（见 §2 注）：已如实登记，不构成输入冻结风险。

## 8. 收尾与纪律

- 属主归一：`chown -R 1000:1000 app/build app/.cxx .gradle .kotlin` 后 root 条目 = **0 / 0 / 0**；`/opt/apk-http/{served,parts,artifacts,parts-archive}` 保持 `admin:admin 0755`、文件 0644、root 条目 **0**。
- 未改任何源码（`app/**`、`signaling/**`、`webrtc/**`、`cpp/**` 只读；唯一 `app/src` 写入为 `build_app.sh` 既有 jniLibs 重拷，内容不变且已豁免）；未做 `git commit/reset/checkout`（HEAD 仍 `8ccbbed2`；收尾 `git status -uall` 仅剩本任务新增的 `?? reports/10-t84-build.log`）。
- 本任务在 `code/webrtc-demo/` 内写入的文件：`reports/10-t84-build.log`、`reports/37-t84-build-publish.md`（均 uid 1000）；仓外：`/opt/dsh-workspaces/tmp/t84-build.sh`、`/opt/apk-http/**`。
- 未触碰 `/opt/signaling` 与 coturn。

## 9. 未验证项（不得读作通过）

1. **真机诊断口径**：`ice_candidate_remote_total trickled=… sdp=…`、`remote_trickled`/`remote_sdp` 两条横幅合并口径、限频日志在真机上的可读性 —— 需用户复测（本版**无行为变化**，仅诊断/文案）。
2. **APK 非逐字节可复现**：身份以 sha256 为准；与 t81 同尺寸属巧合（已按裁定记录）。
3. **锚点与源码的对应性有时效**：锚点 `f2ccd860…` 对应 HEAD `8ccbbed2`（工作树干净，除本任务日志）。
4. **服务端**：本轮未重部署或活体复测 `/opt/signaling`（属 t70 范围）。
5. **设备侧**：未做真机安装校验；下载链路仅验证 HTTP 200/206 与全量 sha。
