# t81 — 第四次构建发布：用 t80 修复后的源码重编 APK（消除「通话正常却报 ICE 未连通」误报横幅）

- 执行者：env-installer（宿主机基础设施与环境工程师）
- 任务：t81（work，deps=[t80]，attempt 1，attempt_id `6627f4f1-92e0-431d-aae3-93215eaf490c`）
- 时间：2026-09-16 11:52 → 12:01（+0800，宿主机 `iZj6cgbzeotp84twpfniy5Z`）
- 原始日志：`code/webrtc-demo/reports/10-t81-build.log`（133 行 / 8,615 B / sha256 `324e42a17e9ddb7d47a2e75b34c9b826abb9ee0697de11c680edaf97181d4959`）
- 上一锚点（t76）：`ff93c2e4c4037c0ba1746a445edef5344e2f8817ef951b67ad2adb9052ce8871`

---

## 1. 目标

用 t80 完成后的源码做**第四次**构建并发布，消除「通话正常却显示 ICE 未连通」的误报：
① 看门狗基数未初始化把 epoch 毫秒当「已用时长」⇒ `ice_state=NEW` 时立刻判失败；② 错误横幅缺少恢复清除路径。产出用户下一轮复测的目标件。

## 2. 前置（写入静默 + 输入冻结）

| 项 | 实测 |
|---|---|
| 采样时刻 | 2026-09-16 11:52:14 / 11:52:39（+0800） |
| **T1 界**（t80 报告值，实测 `app/src` 最后写入，排除 jniLibs 固定件） | **2026-09-16 11:45:45.871**（`test/.../webrtc/IceWatchdogPolicyTest.kt`；源码侧 `CallViewModel.kt` 11:45:33、`CallSurvivability.kt` 11:45:19、`CallSession.kt` 11:45:06） |
| T1+5min 静默复核（11:52:39，即 T1 后 6min54s） | `app/src`（非 jniLibs）近 5 分钟写入 **0**、`signaling/`（排除 logs）**0**、java/gradle 进程 **0** |
| git | HEAD `67edd1348aea32cd58270a00569e75f1bb471b3b`（t76 交付已由 captain 提交），`git status --porcelain -uall` = **6 项在途** |
| 构建输入关键源码（T0 前 16 位） | `CallSession.kt 234413f0519dd28f`、`CallSurvivability.kt 43bac8eb40479534`、`CallViewModel.kt 97c08862a5929b47`、`IceWatchdogPolicyTest.kt b5d3da08c69ef5ab` |

**固定件 T0/T2 双钉（逐位一致）**

| 固定件 | sha256 |
|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757` |
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099` |
| `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36` |

**jniLibs 口径（沿用裁定）**：`build_app.sh` 第 5 阶段重拷 `libjingle_peerconnection_so.so` 为预期行为；本轮实测 **豁免 1 件（内容逐位 = `757cef81…`）**、**非豁免写入件数 = 0**；未触发停手条件。

## 3. 四段串行命令（uid 1000 `admin`；先 check-only，再断言静默）

| # | 命令 | EXIT | 摘要 |
|---|---|---|---|
| 1 | `bash scripts/build_app.sh --check-only` | **0** | FAIL 行 0；尾部「前置校验全部通过（未构建）」 |
| 2 | `./gradlew --no-daemon --no-build-cache -PwebrtcDemo.skipNative=true :app:compileDebugKotlin` | **0** | `BUILD SUCCESSFUL in 1m 44s`；15 tasks（1 executed / 14 up-to-date）；Kotlin 错误 0 |
| 3 | `./gradlew --no-daemon --no-build-cache clean assembleDebug` | **0** | `BUILD SUCCESSFUL in 2m 36s`；**FROM-CACHE 行数 = 0**；`:app:clean` 出现 = 1；43 tasks = 42 executed + 1 up-to-date；T1=11:54:28 → T2=11:57:04 |
| 4 | `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 2m 19s`；24 tasks = **24 executed** |

**构建窗口完整性**：T1=11:54:28 → T2=11:57:04 内 `app/src` 写入 **0**、`signaling/` 写入 **0**。

### 3.1 停手阈值闸门（captain 口径：类数 ≥19 且用例数 ≥ t80 报告实测值 = 186）

```text
XML 汇总: 类数=19  tests=186  failures=0  errors=0  skipped=0   (XML 最新 mtime=2026-09-16 11:59:25)
GATE: 类数 19>=19 ? True ; 用例 186>=186 ? True ; failures=0 errors=0 ⇒ PASS   (GATE_EXIT=0)
```

逐类明细（tests / fail / err / skip）：

| 类 | tests | 类 | tests |
|---|---|---|---|
| AppConfigUrlTest | 8 | CallSurvivabilityTest | 19 |
| LogLevelFilterTest | 6 | ConnectionStatusTrackerTest | 29 |
| NativeInterfaceContractTest | 4 | MediaAliveSuppressionTest | 10 |
| PongLivenessTest | 8 | PendingRemoteMessagesTest | 7 |
| ReconnectBudgetTest | 5 | IceCandidateInfoTest | 8 |
| SignalingErrorPolicyTest | 21 | **IceWatchdogPolicyTest（t80 新增）** | **6** |
| SignalingIdentityTest | 11 | JniBindingClasspathTest | 6 |
| CallSessionSlotTest | 8 | LoopbackCandidatesTest | 5 |
| RendererRecoveryPolicyTest | 7 | SessionLifecycleTest | 10 |
| TurnTcpFallbackTest | 8 | | |

合计 **19 类 / 186 例 / 0 失败**，与 android-dev t80 报告的离线预检（19 类 / 186 例 / 0 failures）**逐项一致**；较 t76 基线（18 类 / 180 例）= **+1 类 / +6 例**（即 t80 新增的 `IceWatchdogPolicyTest` 6 例）。

## 4. 产物

| 项 | 值 |
|---|---|
| 绝对路径 | 宿主机 `/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk`（容器同路径 `/data/dsh/home/workspace/code/webrtc-demo/...`） |
| **sha256（新交付锚点）** | **`93773a8a9d0cbe22e3dc280934d01bb492b51187dab042e33c8691dfad309091`** |
| 字节数 | **33,436,269 B** |
| mtime | 2026-09-16 11:57:04.160233111 +0800 |

**四个 `.so` 的 ELF `p_align`（APK 内实测，全部 `0x4000`）**

| `.so` | sha256（前 16） | p_align |
|---|---|---|
| libandroidx.graphics.path.so | `41e9a793c43a0f4f` | `0x4000` |
| libc++_shared.so | `c9dbf4ec15e931f5` | `0x4000` |
| libjingle_peerconnection_so.so | `757cef8128bf9151` | `0x4000` |
| libwebrtcdemo_native.so | `d49eafc3e7cdeaa2` | `0x4000` |

自有库 `libwebrtcdemo_native.so`（APK 内）= `d49eafc3e7cdeaa296579f039dfda75b0dc34b76885b173dc61e3c1a426212a5`（与 t76/t72 锚点相同 ⇒ 本批 `webrtc/**`、`cpp/**` 的 native 侧零字节变化）。

**与 t76 锚点（`ff93c2e4`）的条目级差异**：165 → 165 entries，相同 **158**、不同 **7**、仅旧 0、仅新 0。
不同条目 = `classes3/5/6/9/11/12/14.dex`；四件 `.so` 与 `resources.arsc`、`AndroidManifest.xml` 逐字节 **SAME** ⇒ 本锚点相对 t76 的差异全部落在 dex（t80 为 Kotlin 侧改动）。

**观察（如实记录）**：本锚点与 t76 锚点**总字节数相同**（均 33,436,269 B）但内容不同（sha256 不同、7 个 dex 不同）。按 captain 既有裁定：**APK 身份一律以 sha256 为准，字节数不得作为判别依据**（同尺寸属巧合）。

**包体旁证（`served` 下载件本体 dex 串统计）**：`ice_watchdog_stale_tier` = 1、`ice_error_cleared` = 1、`ice_watchdog_rearmed` = 2、`skipped=no_watchdog` = 1、`ice_timeout` = 2；并保留前几轮的 `signaling_lost` = 2、`restart_ice` = 2、`rejoin_offer_received` = 1 ⇒ 下载到手机的包体确含 t80 的**档位守卫**（`ice_watchdog_stale_tier`）、**基数跳过**（`skipped=no_watchdog`）与**恢复清除**（`ice_error_cleared`）三条修复路径。

## 5. 发布与四方哈希对账（`publish_apk.sh` 正式模式，**全程 uid 1000，零 root 补步**）

命令：`su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh --apk <构建输出> --log reports/10-t81-build.log"`（**不带** `--rehearsal`/`--allow-root`）

| 步 | 实测 |
|---|---|
| ⓪ 权限预检 | served/parts/artifacts/parts-archive 均可写 ✔ |
| ① 分片 staging 校验 | 拼接 sha == 新锚点；`SHA256SUMS` 自校验通过；4 片 = 8,388,608×3 + 8,270,445 |
| ② 归档旧件 | 旧 served → `/opt/apk-http/artifacts/app-debug-ff93c2e4.apk`；旧 parts → `/opt/apk-http/parts-archive/parts-ff93c2e4-20260916-115932`（6 件） |
| ③ 安装 parts | 逐文件 `mv -f` 原子替换 + 清理多余分片；模式归一 0644 |
| ④ 安装 served | `.new` → `mv -f` 原子替换（served mtime 11:59:33.260） |
| ⑤ **提交点** | 最后写 `parts/SOURCE.sha256` = `93773a8a…9091` |
| ⑥ 四方对账 | 构建输出 = served = `/opt/dsh-workspaces/artifacts/app-debug-93773a8a.apk` = parts 拼接 = `SOURCE.sha256` = **`93773a8a9d0cbe22e3dc280934d01bb492b51187dab042e33c8691dfad309091`** ✔ |
| ⑧ 回滚命令 | 已打印（目标 `ff93c2e4`：`artifacts/app-debug-ff93c2e4.apk` → served + `parts-archive/parts-ff93c2e4-20260916-115932/` 恢复分片 + 最后写回 `SOURCE.sha256`） |

`SHA256SUMS` 自校验：`part00…part03` 4/4 **OK**。

## 6. 服务与公网复验

| 检查 | 结果 |
|---|---|
| `apk-http` systemd | `enabled` + `active`，MainPID **664402**（启动时刻仍 2026-09-14 18:57:09，**未重启** ⇒ 原地替换零停机；进程 user=root） |
| 回环 `HEAD` | 200（`Content-Length: 33436269`、`Accept-Ranges: bytes`） |
| 回环 `Range 0-1023` | 206（1024 B） |
| 公网 `HEAD http://47.238.144.66:8080/app-debug.apk` | **200**，`Content-Length: 33436269`，`Accept-Ranges: bytes`，`Last-Modified: Wed, 16 Sep 2026 03:59:33 GMT` |
| 公网 `Range 0-1023` | **206**（1024 B） |
| 公网**全量**下载 sha256 | `93773a8a9d0cbe22e3dc280934d01bb492b51187dab042e33c8691dfad309091` ✅ |

## 7. 环境与流程事件

1. **身份守卫（t77 ②，本轮再次生效）**：正式发布以 `su -s /bin/bash admin -c …` 运行 ⇒ uid=1000 正常放行；root 直跑会被拒绝（`exit 3`），如需应急须显式 `--allow-root`。本轮**零 Permission denied、零 root 补步**。
2. **静默闸门顺序**：先 check-only、再断言静默，jniLibs 固定件「同哈希重拷」豁免 ⇒ `非豁免写入件数=0、豁免件数=1`。
3. **`.gradle-home` 的 `.lck Permission denied` 教训（t72 修复）**：本轮未复现，第 2 段命令一次通过；收尾 `app/build`/`.gradle`/`app/.cxx` root 条目均为 **0**。
4. **账本冻结核查（新规则）**：`reports/39` = 22,039 B / sha256 `60f19a27…0a22ff`（自 01:24:50 起无写入）；`reports/40` = 15,477 B / sha256 `a5d4e41b…6dd2c8`（自 01:26:01 起无写入）；`reports/44`（t80）= 16,254 B / sha256 `1b642bc2…469a`（11:50:56）。**三份均未被第三方改写**。

## 8. 收尾与纪律

- 属主归一：`chown -R 1000:1000 app/build app/.cxx .gradle .kotlin` 后 root 条目 = **0 / 0 / 0**；`/opt/apk-http/{served,parts,artifacts,parts-archive}` 保持 `admin:admin 0755`、文件 0644、root 条目 **0**。
- 未改任何源码（`app/**`、`signaling/**`、`webrtc/**`、`cpp/**` 全程只读；唯一 `app/src` 写入为 `build_app.sh` 既有 jniLibs 重拷，内容不变且已豁免）；未做 `git commit/reset/checkout`（HEAD 仍 `67edd134`，6 项在途）。
- 本任务在 `code/webrtc-demo/` 内写入的文件：`reports/10-t81-build.log`、`reports/37-t81-build-publish.md`（均 uid 1000）；仓外：`/opt/dsh-workspaces/tmp/t81-build.sh`、`/opt/apk-http/**`（发布产物与归档）。
- 服务端 `/opt/signaling` 与 coturn 未触碰。

## 9. 未验证项（不得读作通过）

1. **真机行为（本锚点的核心目标）**：误报横幅不再出现（`ice_state=NEW` 时不再 `ice_timeout`）、真正连通后 ICED 横幅被清除（`ice_error_cleared reason=connected|media_alive`）、且**真故障仍如实上报**（真到档不被守卫吞掉）—— 需用户复测。
2. **APK 非逐字节可复现**：身份以 sha256 为准（与 t76 同尺寸属巧合，已按裁定记录）。
3. **锚点与源码的对应性有时效**：锚点 `93773a8a…` 对应 HEAD `67edd134` + 6 项未提交改动（本批 t80 改动仍在工作树，未提交）。
4. **服务端**：本轮未重部署或活体复测 `/opt/signaling`（属 t70 范围）。
5. **设备侧**：未做真机安装/`fetch` 校验；下载链路仅验证 HTTP 200/206 与全量 sha。
