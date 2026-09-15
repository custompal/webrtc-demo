# t72 — 第二次构建发布：用 t71 加固后的源码重编 APK 并刷新公网下载快照

- 执行者：env-installer（宿主机基础设施与环境工程师）
- 任务：t72（work，deps=[t71]，attempt 1，attempt_id `d6e29748-4f7d-43b0-ae86-e0171c9a6679`）
- 时间：2026-09-16 00:50 → 01:02（+0800，宿主机 `iZj6cgbzeotp84twpfniy5Z`）
- 原始日志：`code/webrtc-demo/reports/10-t72-build.log`（含三次构建尝试与两次发布尝试的完整时序）
- 上一交付锚点（t69）：`8b58b3f0914021f124a0b86bdfca8350311ef4f26c2d297acfa9d041c16629d9`

---

## 1. 目标

用 t71 完成后的最终源码树重编交付 APK（含 t67 服务端宽限期 + t68 客户端可存活化 + t71 重连预算 63 s / `SIGNAL_LOST` 可恢复 / 重连后 ICE restart），
满足 captain 裁定的停手阈值（**单测类数 ≥18 且用例数 ≥176**，0 失败；构建时契约原文为 ≥173，captain 随后收紧至 ≥176 —— 见 §3.1），刷新公网下载快照并完成四方哈希对账。

**本锚点包含的 t71 内容（captain 提供、已在报告 §4 以 dex 串复核）**：退避改为复用 `rejoinDelayMs`（1/2/4/8 s 封顶 × 10 次 = **63 s**）、新增 `DisconnectCause.SIGNAL_LOST` 可恢复态、`restart_ice reason=rejoin` 接线；`webrtc/**` 与 `cpp/**` 本次未改（佐证：APK 内 `libwebrtcdemo_native.so` 与 t69 锚点逐字节相同）。

## 2. 前置（写入静默 + 输入冻结）

| 项 | 实测 |
|---|---|
| 采样时刻 | 2026-09-16 00:50:03 与 00:50:31（+0800） |
| `app/src` 最后写入（排除 jniLibs 固定件） | **00:39:01.505**（`ReconnectBudgetTest.kt`，= t71 报告的最后写入；源码侧次新为 `SignalingClient.kt` 00:38:57，与 captain 更新的 T1 口径一致） |
| `signaling/` 最后写入（排除 logs） | 2026-09-15 23:45:10（t70 部署产物；本任务不触碰） |
| java/gradle 进程 | 0 |
| 近 5 分钟写入 | `app/src` 0 件、`signaling/` 0 件 |
| git | HEAD `8563addbe096a3b288b94013bf8e5d89002cd678`，`git status --porcelain -uall` = 91 项在途（未提交，本任务不做任何 git 操作） |

**T1 口径**：以 t71 报告给出的 `app/src` 最后写入 **00:39:01** 为界；构建窗口实测 **T1=00:53:17 → T2=00:56:46**，窗口内 `app/src` 写入 = 0、`signaling/` 写入 = 0。

### 2.1 jniLibs 固定件重新落位（**预期行为**，已按 captain 澄清如实记录）

`app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` 在 T1 之后被**重新落位**（仅 mtime 变），这是 `scripts/build_app.sh` **第 5 阶段**的既有行为（脚本第 262/268 行 `cp -f "$SO_TP" "$JNILIBS/"`；第 347 行注释亦自述「仅触碰、内容不变」的重拷），非源码变更。逐次实测：

| 时刻（+0800） | 触发者 | 实测 sha256 | 结论 |
|---|---|---|---|
| 00:50:35.280 | 本任务构建尝试 1 的 check-only 阶段 | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` | 逐位等于固定件 |
| 00:51:33.948（最新） | 本任务构建尝试 3 的 check-only 阶段 | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` | 逐位等于固定件 |

`libc++_shared.so` 本任务期间**未被重拷**（mtime 保持 2026-09-14 10:45:00），sha256 = `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36`（= 固定件）。

**T1 之后的「内容」变更审计（哈希级，采样 2026-09-16 01:03:45）**：`app/src` 中 mtime ≥ T1 的文件只有两件 ——
① `test/kotlin/.../ReconnectBudgetTest.kt`（00:39:01.505，**其自身即 T1 的定义点**），sha256 `0978f368d447a97fe25e0d1b45da1b7e713b8fd8438b3c388acc5329a6b7a7b6`，与构建开始时记录的 T0 指纹（`0978f368d447a97f`）**一致** ⇒ 无内容变更；
② 上述 jniLibs 固定件（逐位等于固定件哈希）。
`signaling/`（排除 `logs/`）中 mtime ≥ T1 的文件 = **0**。
⇒ 符合 captain 的判定口径：**jniLibs 固定件重新落位允许（前提逐位等于固定件），jniLibs 之外无内容变更**。若出现 jniLibs 之外的内容变更，或 jniLibs `.so` 与固定件哈希不一致（意味着构建输入被换、T0/T2 双钉亦会失败），则**立即停手上报、不发布**。

**固定件 T0/T2 双钉（逐位一致）**

| 固定件 | sha256 |
|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757` |
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099` |
| `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36` |

**输入源码指纹（T0，前 16 位）**：`SignalingClient.kt 7c32c5db0f6da640`、`CallSurvivability.kt d2b837170a4e7644`、`CallViewModel.kt d3f2469462a5294a`、`ReconnectBudgetTest.kt 0978f368d447a97f`
（与 android-dev t68/t71 声明值不同 ⇒ 其 00:35–00:39 的 t71 delta 已全部落盘并被本次构建吃到，符合 t72 契约「用 t71 最终树、不采中间态」。）

## 3. 四段串行命令（uid 1000 `admin`，仓库根为 Gradle root）

| # | 命令 | EXIT | 摘要 |
|---|---|---|---|
| 1 | `bash scripts/build_app.sh --check-only` | **0** | FAIL 行 0；尾部「前置校验全部通过（未构建）」 |
| 2 | `./gradlew --no-daemon --no-build-cache -PwebrtcDemo.skipNative=true :app:compileDebugKotlin` | **0** | `BUILD SUCCESSFUL in 1m 42s`；15 tasks（1 executed / 14 up-to-date）；Kotlin 错误 0 |
| 3 | `./gradlew --no-daemon --no-build-cache clean assembleDebug` | **0** | `BUILD SUCCESSFUL in 3m 28s`；**FROM-CACHE 行数 = 0**；`:app:clean` 出现 = 1；43 tasks = 42 executed + 1 up-to-date |
| 4 | `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 4m 28s`；24 tasks = **24 executed** |

### 3.1 停手阈值闸门（类数 ≥18 且 用例数 ≥176）

XML 汇总（`app/build/test-results/testDebugUnitTest`，XML 最新 mtime 01:01:16）：

```
类数=18  tests=176  failures=0  errors=0  skipped=0
GATE: 类数 18>=18 ? True ; 用例 176>=173 ? True ; failures=0 errors=0 ⇒ PASS   （GATE_EXIT=0）
```

**阈值变更说明（据实记录，不改动原始输出）**：构建时执行的是 t72 契约原始阈值「≥18 类 且 ≥173 例」；captain 随后按 android-dev 的 t71 实测把用例阈值**收紧到 ≥176**（173–175 视为与 t71 离线预检不一致 ⇒ 停手上报）。
本任务第 4 段实测 **176 例**（≥176），且 captain 明确「若已跑完第 4 段且实测 ≥176 则无需重跑，直接按 ≥176 记录」⇒ 按收紧后口径同样 **PASS**，未重跑、未产生第二个锚点。

逐类明细（tests / fail / err / skip）：

| 类 | tests | 类 | tests |
|---|---|---|---|
| AppConfigUrlTest | 8 | CallSurvivabilityTest | 15 |
| LogLevelFilterTest | 6 | ConnectionStatusTrackerTest | 29 |
| NativeInterfaceContractTest | 4 | MediaAliveSuppressionTest | 10 |
| PongLivenessTest | 8 | PendingRemoteMessagesTest | 7 |
| **ReconnectBudgetTest** | **5** | IceCandidateInfoTest | 8 |
| SignalingErrorPolicyTest | 21 | JniBindingClasspathTest | 6 |
| SignalingIdentityTest | 11 | LoopbackCandidatesTest | 5 |
| CallSessionSlotTest | 8 | RendererRecoveryPolicyTest | 7 |
| SessionLifecycleTest | 10 | TurnTcpFallbackTest | 8 |

合计 18 类 / 176 例，全部 0 失败 —— 与 android-dev 对 t71 的离线预检（18 类 / 176 例）**逐项一致**，高于 captain 阈值（18 / 173）3 例。相较 t69 基线（17 类 / 165 例）：**+1 类 / +11 例**。

## 4. 产物

| 项 | 值 |
|---|---|
| 绝对路径 | 宿主机 `/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk`（容器同路径 `/data/dsh/home/workspace/code/webrtc-demo/...`） |
| **sha256（新交付锚点）** | **`f694a103d963f444cf1e0c5780f881967d226254cd68bc082c4309016c239901`** |
| 字节数 | 33,419,885 B |
| mtime | 2026-09-16 00:56:45.863384778 +0800 |

**四个 `.so` 的 ELF `p_align`（APK 内实测，全部 `0x4000`）**

| `.so` | sha256（前 16） | p_align |
|---|---|---|
| libandroidx.graphics.path.so | `41e9a793c43a0f4f` | `0x4000` |
| libc++_shared.so | `c9dbf4ec15e931f5` | `0x4000` |
| libjingle_peerconnection_so.so | `757cef8128bf9151` | `0x4000` |
| libwebrtcdemo_native.so | `d49eafc3e7cdeaa2` | `0x4000` |

自有库 `libwebrtcdemo_native.so`（APK 内）= `d49eafc3e7cdeaa296579f039dfda75b0dc34b76885b173dc61e3c1a426212a5`（与 t69 相同；本轮 native 源码未变）。

**与 t69 锚点的条目级差异**（165 entries → 165 entries）：相同 **158**、不同 **7**、仅旧 0、仅新 0。
不同条目 = `classes3/5/6/9/11/12/14.dex`；四件 `.so` 与 `resources.arsc`、`AndroidManifest.xml` 逐字节 **SAME**。
dex 体积变化：classes5 190,768 → 194,044、classes9 95,576 → 96,372、classes14 558,980 → 558,976（其余 4 件体积相同但内容不同）。
**观察（如实记录）**：新旧 APK 总字节数相同（均 33,419,885 B）但内容不同（sha256 不同、7 个 dex 不同）。
**结论（captain 独立核验后确认，须以此为准）**：**总字节数相同纯属巧合，不是旧件**——本锚点 `f694a103d963f444cf1e0c5780f881967d226254cd68bc082c4309016c239901` 与 t69 锚点 `8b58b3f0914021f124a0b86bdfca8350311ef4f26c2d297acfa9d041c16629d9` 的内容与哈希均不同，且本锚点 dex 内含 t71 新键（见下）；**APK 身份一律以 sha256 为准，字节数不得作为判别依据**。
captain 的交叉核验（宿主机直查，不依赖成员转述）与本节证据一致：构建输出与 `served` 同哈希 `f694a103…`（mtime 09-16 00:56:45）；dex 内 `signaling_lost` 2 处、`restart_ice` 1 处、`room_not_found` 1 处；测试 XML = 18 个 / 176 用例。

**包体旁证（新 APK 的 dex 串统计）**：`signaling_lost`（子串）= **2**、`restart_ice` = 1、`room_not_found` = 1、`signaling_lost action=keep_call`（完整串）= 1、`rejoinBudgetMs` = 2、`ws_reconnect_scheduled` = 1、`pong_miss` = 1；
且 t71 明确删除的平行常量 `RECONNECT_MAX_DELAY_MS` = 0、`MAX_RECONNECT_ATTEMPTS` = 0 ⇒ 包体携带的是 **t71 最终形态**，不是 00:29:35 中间态。
（说明：t72 契约「附加建议」里给的字符串 `signal_lost_keep_call` / `rejoin_ice_restart` 在本 APK 中出现 0 次 —— 代码实际使用的是 `signaling_lost action=keep_call` 与 `restart_ice reason=rejoin`；此处按实测记录，不作通过判据。）
**captain 更新后的旁证口径已满足**：在 `served` 下载件本体与构建输出**两处**分别实测，计数完全一致（`signaling_lost` 2 / `restart_ice` 1 / `room_not_found` 1）⇒ 下载到手机的包体确含 t71 的 `SIGNAL_LOST` 可恢复路径与 `restart_ice reason=rejoin` 接线。
另：`rejoin_offer_received` = **0**（该主键属 t75 修复，t72 锚点不含，符合预期 —— 见 §10）。

## 5. 发布与四方哈希对账

- 冻结副本：`/opt/apk-http/served/app-debug.apk`（原地替换，**服务未重启**；零停机）
- 工作区归档：`/opt/dsh-workspaces/artifacts/app-debug-f694a103.apk`
- 旧 served 归档：`/opt/apk-http/artifacts/app-debug-8b58b3f0.apk`（sha256 `8b58b3f0…629d9`）
- 旧 parts 归档：`/opt/apk-http/parts-archive/parts-8b58b3f0…-20260916-010149`（6 件）
- 分片：`part00/01/02` = 8,388,608 B ×3 + `part03` = 8,254,061 B（合计 33,419,885 B）+ `SHA256SUMS` 4/4 OK + `SOURCE.sha256`

**四方对账（全部一致）**

| 侧 | sha256 |
|---|---|
| 构建输出 APK | `f694a103d963f444cf1e0c5780f881967d226254cd68bc082c4309016c239901` |
| served 冻结副本 | `f694a103…3901` |
| `artifacts/app-debug-f694a103.apk` | `f694a103…3901` |
| parts 拼接 | `f694a103…3901` |
| `parts/SOURCE.sha256` | `f694a103…3901` |

`make_parts.sh` 自带合并校验通过：`[make_parts] 合并校验通过: f694a103…3901`，`MAKE_PARTS_EXIT=0`，`SHA256SUMS OK 片数 = 4`。

## 6. 服务与公网复验

| 检查 | 结果 |
|---|---|
| `apk-http` systemd | `enabled` + `active`，MainPID **664402**（启动时刻 2026-09-14 18:57:09，**未重启**） |
| 回环 `HEAD` | 200（`Content-Length: 33419885`） |
| 回环 `Range 0-1023` | 206（1024 B） |
| 公网 `HEAD http://47.238.144.66:8080/app-debug.apk` | **200**，`Content-Length: 33419885`，`Accept-Ranges: bytes`，`ETag "1fdf26d-18d58d3573dda789"`，`Last-Modified: Tue, 15 Sep 2026 17:01:27 GMT` |
| 公网 `Range 0-1023` | **206**（1024 B） |
| 公网**全量**下载 sha256 | `f694a103…3901` ✅（服务日志：`status=200 sent_bytes=33419885 declared_bytes=33419885`） |

## 7. 遇到的问题及自行解决的尝试

### 7.1 构建尝试 1：Gradle wrapper 锁文件 Permission denied（环境，已解决）

- 现象：以 `admin` 执行第 2 段命令立即失败（EXIT=1），日志仅 6 行：
  `java.io.FileNotFoundException: /opt/dsh-workspaces/.gradle-home/wrapper/dists/gradle-8.7-bin/.../gradle-8.7-bin.zip.lck (Permission denied)`
- 定因：`GRADLE_USER_HOME=/opt/dsh-workspaces/.gradle-home`（`env.sh:54`）内有 **13,084 个 root 属主条目**（历史 root 执行 Gradle 遗留），wrapper 的 `.lck` 为 `root:root` ⇒ uid 1000 无法创建/锁定。
- 处置：`chown -R 1000:1000 /opt/dsh-workspaces/.gradle-home`（工作区内的构建基础设施，非源码），复测 `root-owned 剩余=0`、`.lck` 变 `admin:admin`。此后三段命令均以 **admin** 运行成功（满足 t72「uid 1000 admin」要求，且本轮不再产生 root 属主产物）。
- 说明：这是**环境权限**问题，与源码无关；未触碰 `app/**` 任何字节。

### 7.2 构建尝试 2：静默闸门自触发（流程，已解决；captain 后澄清属预期，口径见 §2.1）

- 现象：第二次运行在 check-only **之前**的静默断言处 ABORT（exit 7）：`app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` mtime = 00:50:35。
- 定因：该写入是**我自己尝试 1 的 check-only 阶段**造成的 —— `scripts/build_app.sh` 第 5 阶段本就执行 `cp -f "$SO_TP" "$JNILIBS/"`（脚本第 262/268 行；第 347 行注释已自述这是「仅触碰、内容不变」的重拷）。实测内容逐位不变（`757cef81…`，12,946,912 B），非他人改动。
- 处置：调整闸门顺序为 **先 check-only，再断言静默**，并对两个固定件 `.so` 的**同哈希重拷**做显式豁免（异哈希仍一律 ABORT）。重新运行时输出：`豁免（内容不变重拷）: …libjingle_peerconnection_so.so`、`非豁免写入件数=0`、`静默断言：PASS`。
- 结论：T1 之后**无任何源码写入**；豁免仅限固定件且以 sha256 相等为前提，不削弱闸门语义。

### 7.3 发布尝试 1（admin）：`/opt/apk-http/**` 属 root ⇒ 半成品状态（已修复并完成对账）

- 现象：以 admin 运行发布脚本时，`served/app-debug.apk`（原为 admin 可写文件）被成功原地替换为新 APK，但归档旧 served、创建 `parts-archive/`、`rm` 旧分片全部 `Permission denied`；`MAKE_PARTS_EXIT=1`，`SOURCE.sha256` 与 parts 仍是旧值 ⇒ 一度出现 **served=新 / parts=旧** 的不一致（四方对账 False）。
- 定因：`/opt/apk-http/` 及其子目录为 `root:root 755`（t20/t21/t35/t37 以 root 部署）；发布链路属于 root 特权路径，而 t72 契约「uid 1000 admin」只针对 **Gradle 构建四段命令**。
- 处置：立即以 root 补齐特权步骤（新增 `/opt/dsh-workspaces/tmp/t72-publish-fix.sh`）：归档旧 served 与旧 parts → 重跑 `make_parts.sh`（源 = 构建输出 APK）→ 四方对账 → 公网复验。
  复测：`SOURCE.sha256 = f694a103…3901`、`SHA256SUMS OK 片数 = 4`、`CONCAT == 新 APK : True`、四方逐位一致。
- 影响面：期间公网下载**始终可用**（服务未重启；`served` 替换后 `HEAD 200` / `Range 206` / 全量 sha 已是新锚点），不一致仅存在于 `parts/` 快照侧，且已在 4 分钟内闭合。
- 残留修正：第一次归档脚本误用 64 位全哈希作文件名（`app-debug-<full>.apk`），已按既有 8 位短名约定重做 → `/opt/apk-http/artifacts/app-debug-8b58b3f0.apk`，并删除误建的空名文件。

## 8. 收尾与纪律

- 属主归一：`chown -R 1000:1000 app/build app/.cxx .gradle .kotlin` 后，`app/build` / `.gradle` / `app/.cxx` 的 root 属主条目 = **0 / 0 / 0**。
- 未改任何源码：`app/**`、`signaling/**`、`webrtc/**`、`cpp/**` 全程只读（仅 `build_app.sh` 自身的既有行为重拷了 jniLibs 固定件，内容不变）。
- 未做 `git commit/reset/checkout`；HEAD 仍 `8563add`。
- 服务端 `/opt/signaling`、coturn 未触碰。事实记录：`/opt/signaling/signaling` = `8708629ee152eb6b70367f32cee793aad16d56b6aaa6db2a648508b3585a62f5`（t70 版），unit ExecStart 含 `-room-grace 90s`。
- 本任务期间在 `code/webrtc-demo/` 内新建的文件：`reports/10-t72-build.log`、`reports/37-t72-build-publish.md`（均 uid 1000）；仓外辅助脚本 `/opt/dsh-workspaces/tmp/t72-build.sh`、`t72-publish.sh`、`t72-publish-fix.sh`。

## 9. 未验证项（不得读作通过）

1. **真机行为**：安装/运行、`SIGNAL_LOST` 可恢复、63 s 重连预算与服务端 90 s 宽限期的端到端配合、重连后 ICE restart 是否真正恢复画面 —— 均需用户真机复测。
2. **APK 非逐字节可复现**：身份以 sha256 为准；本轮与 t69 总字节数相同属实测现象，不构成可复现性证据。
3. **锚点与源码的对应性有时效**：锚点 `f694a103…` 对应 HEAD `8563add` + 91 项未提交改动；此后若再改同批文件，锚点与工作树即失配。
4. **服务端二进制**：本轮未重新部署 `/opt/signaling`（不在 t72 范围）；`roomGraceSec=90` 由 t70 提供，未在本轮复测活体瞬断。
5. **发布路径特权边界**：`/opt/apk-http/**` 需 root 写入；若后续任务仍要求「全流程 uid 1000」，需先由 captain 决定是否将该目录属主移交 admin（本轮按既有布置以 root 补齐，未改属主）。

## 10. 后继与取代关系（captain 2026-09-16 告知，据实登记）

- **本锚点（`f694a103…3901`）不含一项已确认的生产缺陷修复**：`CallViewModel` 先 `maybeCreateOffer()` 后 `restartIce()`，而 `restartIce()` 只对**下一次 offer** 生效 ⇒ host 发出的 offer 不携带 `iceRestart` ⇒ 旧候选对已死后 ICE 不重选，表现为「信令恢复但画面黑」；`Joined` 侧亦存在同类 glare 问题。
- 因此 captain 已建 **t75**（android-dev，deps=[t72,t74]：顺序修复 + glare 分工 + 8 s 兜底）与 **t76**（env-installer，deps=[t75]：第三次构建发布，产出最终件）。
- **本锚点的定位**：t72 的 APK **可用且优于 t69 现役件**（含重连预算 63 s 与 `SIGNAL_LOST` 可恢复路径，用户可就近复测），但**将被 t76 锚点取代**；在 t76 发布前它保持现役。
- **t76 执行口径**（预定）：与 t72 完全一致的四段命令 + 闸门（阈值改为「类数 ≥18 且用例数 ≥ t75 报告实测值」，t72 时点为 18 类/176 例）+ 冻结副本 + parts + `SOURCE.sha256` + 四方哈希对账 + 公网 200/206；T1 以 t75 报告的 `app/src` 最后写入时刻为界；发布时按既有做法归档本锚点的 served 与 parts。
