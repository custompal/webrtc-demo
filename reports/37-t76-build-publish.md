# t76 — 第三次构建发布：用 t75 修复后的源码重编 APK 并刷新公网下载快照（最终交付锚点）

- 执行者：env-installer（宿主机基础设施与环境工程师）
- 任务：t76（work，deps=[t75]，attempt 1，attempt_id `96b8d79e-f822-4c3d-bcce-48906c32b257`）
- 时间：2026-09-16 01:31 → 01:38（+0800，宿主机 `iZj6cgbzeotp84twpfniy5Z`）
- 原始日志：`code/webrtc-demo/reports/10-t76-build.log`（133 行 / 8,603 B / sha256 `83d7f7657feb75984aa688123b5b8135d6b7515df5040c70d726b08ab6e4ec49`）
- 上一锚点（t72）：`f694a103d963f444cf1e0c5780f881967d226254cd68bc082c4309016c239901`

---

## 1. 目标

用 t75 完成后的源码做**第三次**构建并发布，产出**最终交付锚点**：在 t72 内容（t68 可存活化 + t71 重连预算 63 s / `SIGNAL_LOST` / 诊断命名 + 服务端 90 s 宽限期）之上，叠加 t75 的 **ICE restart 顺序修复（先 `restartIce` 再 `createOffer`）+ glare 分工（仅 `PeerJoined` 侧发起、`Joined` 侧只 answer）+ 8 s 兜底**。

## 2. 前置（写入静默 + 输入冻结）

| 项 | 实测 |
|---|---|
| 采样时刻 | 2026-09-16 01:30:14 / 01:31:22（+0800） |
| **T1 界**（t75 报告值，实测 `app/src` 最后写入，排除 jniLibs 固定件） | **2026-09-16 01:25:39.624**（`ui/call/CallSurvivabilityTest.kt`；同秒 `CallSurvivability.kt` .622、`CallViewModel.kt` .620） |
| T1+5min 静默复核（01:31:22） | `app/src`（非 jniLibs）近 5 分钟写入 **0**、`signaling/`（排除 logs）**0**、java/gradle 进程 **0** |
| `signaling/` 最后写入 | 2026-09-15 23:45:10（t70 部署产物；本任务不触碰） |
| git | HEAD `8563addbe096a3b288b94013bf8e5d89002cd678`，`git status --porcelain -uall` = **95 项在途**（未提交；本任务不做任何 git 操作） |
| 构建输入关键源码（T0 前 16 位） | `SignalingClient.kt 7c32c5db0f6da640`、`CallSurvivability.kt f9b3a9b694dcf3bc`、`CallViewModel.kt e1bcb441b1c8653b`、`ReconnectBudgetTest.kt ef27e681d7a438cf`、`CallSurvivabilityTest.kt 63fa18e543063132` |

**固定件 T0/T2 双钉（逐位一致）**

| 固定件 | sha256 |
|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757` |
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099` |
| `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36` |

**jniLibs 口径（沿用 captain 裁定）**：`build_app.sh` 第 5 阶段 `cp -f` 重拷 `libjingle_peerconnection_so.so`（第 262/268 行）为**预期行为**；本轮实测 **豁免 1 件、内容逐位等于固定件 `757cef81…`**，**非豁免写入件数 = 0**；若出现 jniLibs 之外的内容变更、或 jniLibs `.so` 哈希不符 ⇒ 立即停手（本轮未触发）。

## 3. 四段串行命令（uid 1000 `admin`；先 check-only，再断言静默）

| # | 命令 | EXIT | 摘要 |
|---|---|---|---|
| 1 | `bash scripts/build_app.sh --check-only` | **0** | FAIL 行 0；尾部「前置校验全部通过（未构建）」 |
| 2 | `./gradlew --no-daemon --no-build-cache -PwebrtcDemo.skipNative=true :app:compileDebugKotlin` | **0** | `BUILD SUCCESSFUL in 1m 36s`；15 tasks（1 executed / 14 up-to-date）；Kotlin 错误 0 |
| 3 | `./gradlew --no-daemon --no-build-cache clean assembleDebug` | **0** | `BUILD SUCCESSFUL in 2m 16s`；**FROM-CACHE 行数 = 0**；`:app:clean` 出现 = 1；43 tasks = 42 executed + 1 up-to-date；T1=01:33:06 → T2=01:35:23 |
| 4 | `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 2m 17s`；24 tasks = **24 executed** |

**构建窗口完整性**：T1=01:33:06 → T2=01:35:23 内 `app/src` 写入 **0**、`signaling/` 写入 **0**。

### 3.1 停手阈值闸门（captain 裁定：类数 ≥18 且用例数 ≥ t75 报告实测值 = 180）

```text
XML 汇总: 类数=18  tests=180  failures=0  errors=0  skipped=0   (XML 最新 mtime=2026-09-16 01:37:41)
GATE: 类数 18>=18 ? True ; 用例 180>=180 ? True ; failures=0 errors=0 ⇒ PASS   (GATE_EXIT=0)
```

逐类明细（tests / fail / err / skip）：

| 类 | tests | 类 | tests |
|---|---|---|---|
| AppConfigUrlTest | 8 | CallSurvivabilityTest | **19** |
| LogLevelFilterTest | 6 | ConnectionStatusTrackerTest | 29 |
| NativeInterfaceContractTest | 4 | MediaAliveSuppressionTest | 10 |
| PongLivenessTest | 8 | PendingRemoteMessagesTest | 7 |
| ReconnectBudgetTest | 5 | IceCandidateInfoTest | 8 |
| SignalingErrorPolicyTest | 21 | JniBindingClasspathTest | 6 |
| SignalingIdentityTest | 11 | LoopbackCandidatesTest | 5 |
| CallSessionSlotTest | 8 | RendererRecoveryPolicyTest | 7 |
| SessionLifecycleTest | 10 | TurnTcpFallbackTest | 8 |

合计 **18 类 / 180 例 / 0 失败**，与 android-dev t75 报告的离线预检（18 类 / 180 例 / 0 failures）**逐项一致**；较 t72 基线（18 类 / 176 例）= **+4 例**（`CallSurvivabilityTest` 15→19，即 t75 的兜底用例）。

## 4. 产物

| 项 | 值 |
|---|---|
| 绝对路径 | 宿主机 `/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk`（容器同路径 `/data/dsh/home/workspace/code/webrtc-demo/...`） |
| **sha256（最终交付锚点）** | **`ff93c2e4c4037c0ba1746a445edef5344e2f8817ef951b67ad2adb9052ce8871`** |
| 字节数 | **33,436,269 B** |
| mtime | 2026-09-16 01:35:23.033437470 +0800 |

**四个 `.so` 的 ELF `p_align`（APK 内实测，全部 `0x4000`）**

| `.so` | sha256（前 16） | p_align |
|---|---|---|
| libandroidx.graphics.path.so | `41e9a793c43a0f4f` | `0x4000` |
| libc++_shared.so | `c9dbf4ec15e931f5` | `0x4000` |
| libjingle_peerconnection_so.so | `757cef8128bf9151` | `0x4000` |
| libwebrtcdemo_native.so | `d49eafc3e7cdeaa2` | `0x4000` |

自有库 `libwebrtcdemo_native.so`（APK 内）= `d49eafc3e7cdeaa296579f039dfda75b0dc34b76885b173dc61e3c1a426212a5`（与 t72 相同；`webrtc/**`、`cpp/**` 本批零改动）。

**与 t72 锚点（`f694a103`）的条目级差异**：165 → 165 entries，相同 **158**、不同 **7**、仅旧 0、仅新 0。
不同条目 = `classes3/5/6/9/11/12/14.dex`；四件 `.so` 与 `resources.arsc`、`AndroidManifest.xml` 逐字节 **SAME**。
⇒ 本锚点相对 t72 的差异**全部落在 dex**（t74 断言 + t75 顺序/glare/兜底的都是 Kotlin 侧改动），无新增/删除条目。

**包体旁证（`served` 下载件本体 dex 串统计，非构建输出）**：`signaling_lost` = 2、`restart_ice` = 2、`rejoin_offer_received` = 1、`rejoinBudgetMs` = 2、`initiator=host` = 1、`initiator=rejoiner` = 1、`offer_timeout` = 1；且 t71 已删的平行常量 `MAX_RECONNECT_ATTEMPTS` = 0。
（说明：`fallback=` 单独计数为 0，因实现用结构化字段 `"fallback" to "offer_timeout"`（`CallViewModel.kt:291`），dex 中两个字面分开存放；故按 `offer_timeout` = 1 计数为准。）⇒ 下载到手机的包体确含 t75 的 `restart_ice … initiator=host|rejoiner` 与兜底 `fallback=offer_timeout` 路径。

## 5. 发布与四方哈希对账（走 t77 固化的有序脚本，**全程 uid 1000，无需 root 补步**）

命令：`su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh --apk <构建输出> --log reports/10-t76-build.log"`（正式模式，**不带** `--rehearsal`/`--allow-root`）

| 步 | 实测 |
|---|---|
| ⓪ 权限预检 | served/parts/artifacts/parts-archive 均可写 ✔（t77 移交属主生效） |
| ① 分片 staging 校验 | 拼接 sha == 新锚点；`SHA256SUMS` 自校验通过；4 片 = 8,388,608×3 + 8,270,445 |
| ② 归档旧件 | 旧 served → `/opt/apk-http/artifacts/app-debug-f694a103.apk`；旧 parts → `/opt/apk-http/parts-archive/parts-f694a103-20260916-013749`（6 件） |
| ③ 安装 parts | 逐文件 `mv -f` 原子替换 + 清理多余分片；模式归一 0644 |
| ④ 安装 served | `served/.app-debug.apk.new` → `mv -f` 原子替换 |
| ⑤ **提交点** | 最后写 `parts/SOURCE.sha256` = `ff93c2e4…8871` |
| ⑥ 四方对账 | 构建输出 = served = `/opt/dsh-workspaces/artifacts/app-debug-ff93c2e4.apk` = parts 拼接 = `SOURCE.sha256` = **`ff93c2e4c4037c0ba1746a445edef5344e2f8817ef951b67ad2adb9052ce8871`** ✔ |
| ⑧ 回滚命令 | 已由脚本打印（回滚目标 `f694a103`：`cp artifacts/app-debug-f694a103.apk → served/` + 从 `parts-archive/parts-f694a103-20260916-013749/` 恢复分片 + 最后写回 `SOURCE.sha256`） |

`SHA256SUMS` 自校验：`part00…part03` 4/4 **OK**；`make_parts` 合并校验通过。

## 6. 服务与公网复验

| 检查 | 结果 |
|---|---|
| `apk-http` systemd | `enabled` + `active`，MainPID **664402**（启动时刻仍 2026-09-14 18:57:09，**未重启** ⇒ 原地替换零停机） |
| 回环 `HEAD` | 200（`Content-Length: 33436269`、`Accept-Ranges: bytes`） |
| 回环 `Range 0-1023` | 206（1024 B） |
| 公网 `HEAD http://47.238.144.66:8080/app-debug.apk` | **200**，`Content-Length: 33436269`，`Accept-Ranges: bytes`，`Last-Modified: Tue, 15 Sep 2026 17:37:50 GMT` |
| 公网 `Range 0-1023` | **206**（1024 B） |
| 公网**全量**下载 sha256 | `ff93c2e4c4037c0ba1746a445edef5344e2f8817ef951b67ad2adb9052ce8871` ✅（服务日志 `sent_bytes=33436269 declared_bytes=33436269`） |

## 7. 环境与流程事件（本任务沿用 t72/t77 的处置，均已闭环）

1. **`.gradle-home` 的 `.lck Permission denied`（t72 已修，本轮未复现）**：教训是工作区内 `GRADLE_USER_HOME=/opt/dsh-workspaces/.gradle-home` 曾被历史 root 构建写入 13,084 个 root 属主条目，导致 uid 1000 无法创建 wrapper 锁文件。t72 已 `chown -R 1000:1000` 归位；本轮第 2 段命令**一次通过**，`app/build`/`.gradle`/`app/.cxx` root 条目收尾均为 **0**。**后续成员若见同类 Permission denied，先查该目录属主，勿改为 root 构建**。
2. **身份守卫（t77 ②，本轮生效）**：`publish_apk.sh` 在参数解析后拒绝 `id -u != 1000`（除非显式 `--allow-root`）。本轮三向验证输出（宿主 `/tmp/kcheck` 为宿主命名空间；完整日志 `/opt/dsh-workspaces/tmp/t77-guard-verification.log`）：
   - **A) root 不带开关 ⇒ `A_ROOT_NO_FLAG_EXIT=3`**：`拒绝执行：publish_apk.sh 必须由 uid 1000(admin) 运行（当前 uid=0，user=root）。` + 原因/正确用法/归位命令/应急例外；
   - **B) root + `--allow-root` ⇒ `B_ROOT_ALLOW_FLAG_EXIT=9`**：先打印应急告警，再因 `--apk /nonexistent/...` 报 `ERROR: 找不到 APK` ⇒ 开关生效且零写入；
   - **C) uid 1000 `--rehearsal` ⇒ `C_ADMIN_EXIT=0`**：放行、四方一致、服务未重启。
   本轮正式发布即发生在 **C 类路径**（uid=1000，无 root）。
3. **静默闸门顺序（captain 裁定 2）**：先 check-only、再断言静默，并对 jniLibs 固定件「同哈希重拷」豁免 ⇒ 本轮 `非豁免写入件数=0、豁免件数=1`，构建窗口内 `app/src`/`signaling` 写入 = 0。
4. **旧 `make_parts.sh` 顺序问题（t77 已标注）**：其 `split → SHA256SUMS → SOURCE.sha256 → 合并校验` 属「先写提交标记再校验」，本任务发布**未使用**它，改用 `publish_apk.sh`。

## 8. 收尾与纪律

- 属主归一：`chown -R 1000:1000 app/build app/.cxx .gradle .kotlin` 后 root 条目 = **0 / 0 / 0**；`/opt/apk-http/{served,parts,artifacts,parts-archive}` 保持 `admin:admin 0755`、文件 0644、root 条目 **0**。
- 未改任何源码（`app/**`、`signaling/**`、`webrtc/**`、`cpp/**` 全程只读；`build_app.sh` 的 jniLibs 重拷为既有行为且内容不变）；未做 `git commit/reset/checkout`（HEAD 仍 `8563add`，95 项在途）。
- 本任务在 `code/webrtc-demo/` 内写入的文件：`reports/10-t76-build.log`、`reports/37-t76-build-publish.md`（均 uid 1000）；仓外：`/opt/dsh-workspaces/tmp/t76-build.sh`、`/opt/apk-http/**`（发布产物与归档）。
- 服务端 `/opt/signaling`（`8708629ee152eb6b…`，`-room-grace 90s`）与 coturn 未触碰。

## 9. 未验证项（不得读作通过）

1. **真机行为（本锚点的核心目标）**：WiFi↔4G 切换后 host 发出的 offer **确实带 `iceRestart`**、ICE 重选、画面恢复；`Joined` 侧不再并发 offer（无 glare）；8 s 兜底在真机触发一次且不误触（需用户复测，日志判据：`restart_ice reason=rejoin initiator=host|rejoiner`、`rejoin_offer_received waited_ms=…`）。
2. **APK 非逐字节可复现**：身份以 sha256 为准。
3. **锚点与源码的对应性有时效**：锚点 `ff93c2e4…` 对应 HEAD `8563add` + 95 项未提交改动；此后任何同批文件改动都会使其失配（当前 in-flight 改动由 captain 统一提交）。
4. **服务端**：本轮未重新部署或活体复测 `/opt/signaling`（属 t70 范围）。
5. **`fetch`/真机安装**：未做设备端安装校验；下载链路仅验证 HTTP 200/206 与全量 sha。
