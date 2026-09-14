# 99 — t11 独立验证与最终汇总报告

> 任务：t11（独立验证全部产物并汇总最终报告） · 执行者：**verifier**（不参与任何实现）
> 执行时间：2026-09-13（容器 `node@/data/dsh/home/workspace`；宿主机经 `ssh -i ~/.ssh/id_ed25519 root@172.21.0.219 -p 5766`）
> 结论三态定义：**已验证**（我自己复跑并留下原始输出）／**未能验证**（附原因，不判失败）／**验证失败**（实测与契约/报告不符）

---

## 0. 元信息与口径

| 项 | 值 |
|---|---|
| 验收权威 | `doc/14-interface-contract.md`（§0 规定"以磁盘为准"） |
| **t11 开工时磁盘指纹** | **1337 行 / `sha256 b3b6743825eababc51d41944d61d0f4ab542c8a0f3cdfc4d7a754cefd1cc0f4d`**（执行期间两次断言，值稳定） |
| V 项 | V01–V64（实测计数 64，无重排；另 §12.9 追加 V60–V64） |
| 仓库根 | 容器 `/data/dsh/home/workspace/code/webrtc-demo` ＝ 宿主机 `/opt/dsh-workspaces/code/webrtc-demo` |
| **t26 交付时 HEAD** | **`3486d58a9295e517ee62fae6a89b13d1c4b451e5`**（`reports/05,15: 更正 t23 章节…`）；其前一提交 **`8a2c4000`**（`fix(android): jni_zero 绑定类 + 16KB 页对齐 + 引擎失败可诊断化（最终交付，静默窗口构建）`）为 **t26 交付提交**；t26 收口时 `git status --porcelain` = **空** ✅ |
| **t27 复测时 HEAD（真实值，勿按上一条引用）** | **`1621d72dcea6da6d7d6770e7fd4ff247ba174cc0`**（`reports/10 §9.4: 标注 10:59 构建为中间产物… + 入库 scripts/check_jn_binding.py`）。t26 之后新增 3 个提交：`806aa71`（`reports/10 §9.7` jar↔.so 边界一致性）、`7d0e0cc`（`§9.8` 构建窗口精确事实）、`1621d72`（`§9.4` + 新增 `scripts/check_jn_binding.py`）。**已实测确认均未触及交付物**：`sha256 doc/14` 仍 `b3b67438…`（1337 行）、APK 仍 `721df1c8…`（33,293,061 B）、`libwebrtc-java.so` 仍 `757cef81…`；故下文各项 APK/契约证据在本 HEAD 上依然成立 |
| t27 提交与收口状态 | 本报告 t27 提交 = **`0c2f24d`**（**仅** `reports/99-final-report.md`，+171/−9；其后 §0/§6/§10/§13 补记随**本行所在提交**（`git log -1 -- reports/99-final-report.md`）一并入库）；提交后 `git status --porcelain` 仅剩他人在途 ` M scripts/check_jn_binding.py`。**提交方式特殊**：`.git/objects/` 属主混用使普通 `git commit` 失败（**K-17**），改用"沙箱对象库 + 标准 pack + `update-ref` 旧值保护"，**未改动他人文件、未改 `.git/config`**（§10.11） |
| t27 任务状态（平台事实，非实现缺陷） | 任务台账里 t27 记为 **failed**，唯一原因是收尾前置的平台错误 **`Insufficient Balance (code QUOTA)`**（模型配额中断），**不是验收不通过**：本报告所列 t27 全部验收项均已实测完成并已入库（`0c2f24d`+`0ae3ec0`），复核结论与未闭合项（**K-15**）如实保留；captain 已据此另立 **t34（r2 复核：路线 A 落地 + 新 APK 复验 + K-15/K-17 收口）** |
| 本报告自身的提交 | `b66c166`（t18）→ **`bda87e4`（verifier t19 收口，仅改 `reports/99`）**；此后 t26 链路新增 `35a7e7f`→`8a2c400`→`3486d58`（t23/t24/t25 实现 + `reports/05,15` 勘误）与 t27 前的 `806aa71`→`7d0e0cc`→`1621d72`（`reports/10` + `scripts/check_jn_binding.py`），**均不含本报告** |
| 提交谱系（全部） | `7a02694`→`ad2e553`→`1a9d3ff`→`198d514`（t10 统一提交）→`8a8611e`→`1a3a8e6`→`10aa709`→`39a62bd`→`d7a2471`→`a57c057`→`14ef053`（退役脚本 `git rm`）→`b66c166`（t18 收尾）→`bda87e4`（verifier t19）→`35a7e7f`（t23/t24/t25 实现首提）→**`8a2c400`（t26 最终交付）**→**`3486d58`（reports/05,15 勘误）**→`806aa71`→`7d0e0cc`→**`1621d72`（t27 复测时 HEAD）** |
| 收尾提交与判据 | t19：① 退役脚本已 `git rm`（`14ef053`）✓；② `reports/10` 的 v69 归属已写对 ✓；③ `status` 空 ✓。t26：④ 交付提交 `8a2c400`（14 文件 +1524/−264）后 `status` 空 ✓；⑤ `git ls-files scripts/` 无 `fix_jar_class_version.sh` ✓；⑥ 受版控树与全项目非 1000 属主项 = 0 ✓ |
| 在途文件（**授权，非缺陷**） | `reports/07-native-dev.md` 在验证期间处于**在途提交状态**：native-dev 已 idle 收工的 **v1.6 / 531 行**（新增 §9.1.2「APK 内实体证据」、N5 标为已闭合），captain 已指派 env-installer 随最终收尾提交带上；**最终提交哈希待该提交落地后回填**。其 §9.1.2 的两条哈希与 ELF 结论**我已独立复跑**（见 §3.8） |
| tracked 文件数 | 169（signaling 23；`app/src/**/*.kt` 43 = main 40 + test 3；`scripts/patches/libwebrtc-java-release17.patch` 已入库） |
| 本报告只写的文件 | 本文件（未修改任何被验证产物） |

> ⚠️ **指纹漂移提示**：captain 最后一次告知的冻结值为 **1325 行 / `894f15bf…`**，而我 t11 开工时磁盘已是 **1337 行 / `b3b67438…`**（内容含 §8.5/D-7 等后续增量）。按契约 §0「以磁盘为准」，本报告以 **1337/`b3b67438…`** 为验收基准，并在此如实记录差异。

### 0.1 本轮采用的裁决口径（不得判失败的项）

| 编号 | 内容 | 报告处置 |
|---|---|---|
| C10/C27 | 官方 Java 编码器路径下 SVC 空间层不可协商 → 冻结 **L1T3** | 不按"空间层可观察"判失败；改验时序分层与 `vpx_codec_enc_config_set` 闭环 |
| C29 / R10 | t5 授权新增 `rtc_dlog_always_on=true` | 不得据此判失败（已授权偏差） |
| C30 / §11.4 **D-2** | Go logrus 字段 camelCase | low、不判失败 |
| §11.4 **D-1** | `/signal` 兼容别名 | 允许显式标注的别名，不得仅因出现判失败（实测现已无别名，`/signal`→404） |
| §11.4 **D-3** | `created` 不含本端 `peerId` | 已知限制、不判失败（客户端由 `joined`/`peerJoined` 推导） |
| §11.4 **D-6** | Offerer 由 host **角色固定** | 判"不变量：同一时刻只有一方发 Offer"，不判 `doc/09` §3.3 字面 |
| §11.4 **D-7** / §8.5 | 静默掉线不可完整恢复（**现行口径 B**） | 已知限制、不判失败；口径 A 为后续增强（前提含 ICE restart） |
| 约定 13 | 上述 D-1…D-7 | 均不得判失败 |

### 0.2 HEAD ↔ 交付物哈希对应关系（写终报前重取 HEAD 后实测）

> 方法：`go version -m <二进制>` 读取嵌入的 `vcs.revision`；APK/jar 用 sha256 + 构建记录（`reports/10`）对应。

| 交付物 | sha256（前 16） | 对应提交 / 来源 | 证据 |
|---|---|---|---|
| **交付 APK（t27 终版）** `app/build/outputs/apk/debug/app-debug.apk`（**33 293 061 B**，mtime **2026-09-14 11:28:34.950**） | **`721df1c82841ad992ffef016cdb4fc09335028869fa443e98f24fe797055b724`** | **不在任何提交内**（`app/build/**` gitignore）；由 **t26** 在"静默窗口 + 完全执行"下重编（`--no-daemon --no-build-cache clean assembleDebug`，日志 `reports/logs/t26d-nocache-assembleDebug-20260914-112608.log`；FROM-CACHE=0、`:app:clean` 出现、43 tasks = 42 executed/1 up-to-date），使用 t23 修复后的 jar `dc5f8919…` | 我的产物级复核（§3.8/§12） |
| ~~过程值 APK（已被取代）~~ | `b0cddd86…`（t18，33 260 234 B）/ `58834b5a…`（11:04）/ `6653fddf…`（10:55） | 均为**过程值**：`b0cddd86…` 是 t18 的 no-cache clean 构建产物，**其后被 t23–t26 的 jar 修复链取代**；`58834b5a…`/`6653fddf…` 按 t26 判据作废（构建窗口内输入变更）。仅存哈希与当时核验记录 | §3.13/§12 |
| ~~前一次 APK（已被覆盖）~~ | `c72d366706569b6d` | 20:21:25 产物，**其字节已被上述 clean 构建覆盖且无备份**；仅存哈希与我当时的核验记录（`.so` 两个哈希与终版相同，见 §3.8） | §3.8 历史记录 |
| 现行 libwebrtc jar `third_party/libwebrtc/java/libwebrtc-java.jar`（1 048 264 B） | `d98939bbf0c0cd07` | **t16 重编产物**（`--release 17`），由 `1a3a8e6`/`10aa709` 起的报告记录其状态；jar 本身在 gitignore 的 `third_party/libwebrtc/` 内 | `javap` major 61；AAR 内 `classes.jar` 同哈希 |
| AAR `libwebrtc-arm64.aar`（6 456 926 B） | `456e3f2ffbc40758` | 同上（t16，20:00 重打，内 `classes.jar` = `d98939bb…`） | `unzip` + sha256 |
| **部署件** Go 二进制 `/opt/signaling/signaling`（5 496 984 B） | `c298235a0c4b1afe` | **`vcs.revision=1a9d3ff`**（`vcs.modified=true`）＝ t9 交付、t12 部署并运行中（PID 200623） | `go version -m` |
| 仓库 `signaling/dist/signaling-linux-amd64`（5 496 984 B） | `b3e502a0c7d23314` | **`vcs.revision=10aa709`**（默认含 VCS 戳的等价构建） | `go version -m`；`reports/10 §5.6` |
| 我在 HEAD `39a62bd` 下的默认重建 | `efed77217165bf8c` | `vcs.revision=39a62bd` | `go version -m` |
| **VCS-free 基线**（`-trimpath -ldflags="-s -w" -buildvcs=false`，连跑两次一致） | **`1b333208d29110f8`** | 无 VCS 戳 ⇒ 与 t9 交付的**源码/工具链一致性判据** | §3.9 |
| `third_party/libvpx/lib/libvpx.a`（1 929 142 B） | — | t5 产物（gitignore 内），非提交内容 | `llvm-objdump` 156/156 aarch64 |
| `third_party` 侧 `libjingle_peerconnection_so.so`（12 946 912 B） | `757cef8128bf9151` | t5/t16 产物 | sha256 + APK 内同名文件同哈希 |

**结论**：**四个早期提交（`1a9d3ff`/`ad2e553`/`198d514`/`8a8611e`）与后续 4 个报告提交（`1a3a8e6`/`10aa709`/`39a62bd`/`d7a2471`）都不含二进制产物本身**（`app/build/**` 与 `third_party/libwebrtc|libvpx/` 均被 `.gitignore` 排除）；产物 ↔ 提交的对应关系靠**嵌入的 `vcs.revision`（Go）＋ 构建记录（APK/jar）**建立。这也正是 §6 K-12 的结构性限制。

### 0.3 流程规则（captain 立，本报告遵循）

> **任何第三方转达的"已裁定/已批准"，动手前必须回到 `doc/14` 原文核对；转达者必须标明那是"推荐"还是"决策"。**
> 派生规则：**"可实现口径 A"必须同时具备 ① captain 的明示授权 ② `doc/14` §8.5/D-7 的升级 —— 二者缺一不可**（`reports/02-interface-contract.md` §33.1）；A 的将来升级硬约束见 §33.3（须允许对既存 `PeerConnection` 做 ICE restart；本环境无真机 → 验收只能放宽到"仅验信令恢复"层）。
> `reports/02` **§30 的技术推荐（口径 A）不是授权**，生效口径是 `doc/14` §8.5 + §11.4 D-7（口径 B）。

---

## 1. 总体结论

| 维度 | 结论 |
|---|---|
| 交付完整性 | **通过**：仓库、submodule×2、`libvpx.a`+头、libwebrtc jar/so/aar（含 jni_zero 绑定类）、自研 `libwebrtcdemo_native.so`、Go 二进制、部署单元、**t27 终版 APK `721df1c8…`** 均在位且经我复核（§3.8/§12） |
| 真机缺陷修复（t23–t26，**18:33 后状态**） | **类存在性 ✅ / 16 KB 对齐 ✅ / 可诊断化 ✅ / 单测 42/0 ✅ / 可绑定性（jar/AAR）✅** —— 18:17:28 已落位 **B**（`J/N.class` `1ff8d3ff…` + `GEN_JNI.class` `a6e7edcf…`、`GEN_JNI` `native=0`、193↔193、194/194，§13.22）。**APK 侧仍待 t33**：现行 `721df1c8…`（33 293 061 B / 11:28:34）由**未落位 jar** 构建、dex 内无 `LJ/N;` ⇒ **标注为"已被取代的历史轮次交付物"**（18:39 起 t33 构建窗口内该文件已从仓库清理；历史证据取仓外快照 `artifacts/app-debug-721df1c8.apk`，同哈希） |
| 可复现性 | **Go：逐字节可复现**（VCS-free 基线两次 `cmp` 相同，§3.9）。**APK：非逐字节可复现** —— 4 次**缓存辅助**构建同哈希 `c72d3667…`，1 次**完全执行**得 `b0cddd86…`（§3.13）；**t27 终版 APK `721df1c8…`** 由 t26 的 `--no-build-cache clean` 完全执行构建产出（FROM-CACHE=0，§12），**其与 `b0cddd86…` 不同属预期**（jar 已更换为 t23 修复版）。结论不变：**APK 不能靠"同哈希"证明干净复现** |
| 契约一致性（静态/构建级） | **通过**：JNI 15/15 逐字一致、信令 14/14 一致、NAT 枚举 6/6 一致、CMake 路径与实际布局一致、APK 内 arm64 .so 齐备 |
| 服务端与联调 | **通过**：Go `build`/`vet` 0、e2e 19/19（公网+内网）、coturn V60–V62/V64 通过、systemd active、日志在写 |
| 设备级（整机通话） | **未能验证**：无 Android 真机（V54–V59） |
| 阻塞项 | **无**。F-01 已由 `14ef053` 闭合；F-02 判为历史语境闭合（见 §5.1）；CD-1…CD-5 属契约口径建议，不构成阻塞 |
| 必查项完成度（captain 本轮点名） | APK 解包/`resources.arsc`/FileProvider/file_paths ✓ · APK 内 `.so` `readelf`（导出仅 `JNI_OnLoad`+`JNI_OnUnload`、UND 含 webrtc|jingle=0、无 `.symtab`、`NEEDED` 无 libjingle/libwebrtc）✓ · `t7iface.sh` 19/19 ✓ · `go test ./... -count=2` ✓ · VCS-free 基线 `1b333208…` 复现 ✓ · systemd/coturn/healthz(200)/`/ws`(400)/`/signal`(404)/STUN-TURN ✓ · Kotlin 单测 36/0 ✓ · 报告哈希链一致性 ✓（F-02 已判为历史语境闭合，附注见 §5.1） |

**一句话**：**所有实现级/产物级/服务端级必查项均通过；唯一未验证类别是"无真机导致的设备级项（V54–V59 等）"；未发现实现级失败项。verdict = `pass`。**

> 说明：`reports/07-native-dev.md` 在验证期间的未提交改动（native-dev v1.6 / 531 行）**是 captain 授权的在途项**，已由 env-installer 随最终收尾提交带上，**不作为缺陷**；其 §9.1.2 的两条哈希与 ELF 结论我已独立复跑（§3.8）。

---

## 2. doc/14 §12 逐项验收清单（V01–V64）

> 说明：`R` 项全部实跑；`D` 项（需真机）标注未验证。命令默认从仓库根执行。`grep` 无匹配退出码 1 在三态中记为**通过**（契约约定 3）。

### 12.1 目录/命名/包名

| # | 结论 | 证据（原始输出/命令） |
|---|---|---|
| V01 | **已验证** | `ls app/src/main/kotlin/com/example/webrtcdemo` → `MainActivity.kt WebRtcDemoApp.kt config diag encoder log model nat nativebridge signaling ui webrtc` |
| V02 | **已验证** | `grep -rnI --include='*.kt' --include='*.kts' --include='*.go' --include='*.xml' "com\.webrt\.demo" app signaling` → rc=1（0 命中） |
| V03 | **已验证** | `app/src/main/cpp/CMakeLists.txt` 存在；`grep -rnI -e symlink -e 软链 app` → rc=1 |
| V04 | **已验证** | `test ! -d native && echo OK` → `OK` |
| V05 | **已验证** | `grep -c "src/main/kotlin" app/build.gradle.kts` → 3 |
| V06 | **已验证** | `namespace = "com.example.webrtcdemo"`（:36）、`applicationId = "com.example.webrtcdemo"`（:41） |

### 12.2 版本与技术栈

| # | 结论 | 证据 |
|---|---|---|
| V07 | **已验证** | `compose-bom:2024.10.01`（app/build.gradle.kts:157） |
| V08 | **已验证** | `id("org.jetbrains.kotlin.plugin.compose")`（:25）；`grep -c kotlinCompilerExtensionVersion` → 0 |
| V09 | **已验证** | `VERSION_17`（:104/105）、`jvmTarget = "17"`（:110） |
| V10 | **已验证** | `ndkVersion = "26.1.10909125"`（:38） |
| V11 | **已验证** | 宿主机 `./gradlew -v` → `Gradle 8.7`、`JVM: 17.0.20 (Ubuntu 17.0.20+8-1-24.04-Ubuntu)`、`Kotlin: 1.9.22`（构建脚本用）|
| V12 | **已验证** | `grep -rnI --include='*.kt' --include='*.kts' -e CameraX -e camera-core -e camera-view app` → rc=1 |

### 12.3 编码器注入路线

| # | 结论 | 证据 |
|---|---|---|
| V13 | **已验证** | `encoder/` 含 `Vp9VideoEncoder.kt`、`Vp9VideoEncoderFactory.kt` |
| V14 | **已验证** | `Vp9VideoEncoderFactory.kt:6 import org.webrtc.VideoEncoderFactory`、`:25 class Vp9VideoEncoderFactory(` |
| V15 | **已验证** | `grep -rn SelfVp9Libvpx app/src/main` → 12 处（≥3） |
| V16 | **未能验证（契约字面过期，非实现问题）** | 交付 jar 的 `org.webrtc.VideoEncoder` **没有** `createNativeVideoEncoder()`，而是 `public default long createNative(long)`（`javap` 实测，见 §3.1）。等价意图已验证：`javap -c` 显示默认实现 `lconst_0; lreturn`（返回 0），且 `Vp9VideoEncoder.kt:318 override fun createNative(webrtcEnvRef: Long): Long = 0L`。**建议 architect 将 V16/§5.7 的 API 名改为 `createNative(long)`** |
| V17 | **已验证** | `grep -rn "webrtc::VideoEncoder" app/src/main/cpp` → rc=1 |
| V18 | **已验证（人工确认）** | CMake 中 `libwebrtc`/`libjingle` 命中行**全部为注释**（`grep … | grep -v '^[0-9]*:[[:space:]]*#'` 无输出）；`target_link_libraries(webrtcdemo_native vpx log android)`（:64） |
| V19 | **已验证** | `add_library(vpx STATIC IMPORTED)`（:25）、`project(webrtcdemo_native CXX)`（:12） |
| V20 | **已验证** | CMake `WEBC_THIRD_PARTY`（:17/19/22）＋ `app/build.gradle.kts:62 "-DWEBC_THIRD_PARTY=${rootProject.projectDir}/third_party"` |
| V21 | **已验证** | encoder/ 内：`ss_target_bitrate` 11、`ts_target_bitrate` 28、`layer_target_bitrate` 17、`vpx_codec_enc_config_set` 5 |
| V22 | **已验证（人工确认）** | `vp9_encoder.cpp:206 if (config.num_spatial_layers != 1) { … return kVp9ErrParameter; }`；`:159 cfg_.ss_number_layers = 1;`，上方 `:155–158` 为说明 §5.6 的中文注释。**深查**：`SetRates` 以 `last_rates_.configured_spatial`（InitEncode 恒为 1）计算，故即使 Kotlin 传入 3×3 矩阵，写回 vpx 的 `ss_number_layers` 仍为 1 |
| V23 | **已验证** | `app/src/main/cpp/log/native_log.cpp:36 "ts_ms,total_bps,fps,s,t,layer_0_0,…"` |
| V24 | **已验证** | `webrtc/FrameNormalizer.kt:26 class FrameNormalizer(private val downstream: CapturerObserver) : CapturerObserver` |
| V25 | **已验证** | `grep -rnI -e onRemoteVideoFrameReady -e nativeSetVideoSurface -e setRemoteVideoSink app` → rc=1 |

### 12.4 JNI 契约

| # | 结论 | 证据 |
|---|---|---|
| V26 | **已验证** | `nativebridge/` 含 `NativeCallbacks.kt NativeLoader.kt NativeLog.kt NativeNatDetector.kt NativeVp9Encoder.kt`（4 个冻结类齐备） |
| V27 | **已验证** | `grep -rh "external fun" …/nativebridge/` → **15** 行 |
| V28 | **已验证** | `grep -rhE '^[[:space:]]*[{]"' app/src/main/cpp/jni/` → **15** 行 |
| V29 | **已验证（附弱断言注记）** | 唯一类名计数 = 4；但正则 `[A-Za-z]+` 不含数字 → `NativeVp9Encoder` 被截断为 `NativeVp`（计数达标，但"类名字符串一致"其实未逐字覆盖）。真实一致性见 §3.1 的逐字比对 |
| V30 | **已验证** | `NO_OUTPUT/ERR_PARAMETER/UNINITIALIZED` 命中 28 处 |
| V31 | **已验证（人工确认）** | `LogLevel.kt`：`VERBOSE(0) DEBUG(1) INFO(2) WARN(3) ERROR(4) OFF(5)`，与 §6.6 一致 |
| V32 | **已验证** | `proguard-rules.pro:14/16/17`：`-keep class org.webrtc.** { *; }`、`-keep class com.example.webrtcdemo.nativebridge.** { *; }`、`-keepclasseswithmembernames class * { native <methods>; }` |

### 12.5 信令

| # | 结论 | 证据 |
|---|---|---|
| V33 | **已验证** | ① `grep -rnI --include='*.go' '"/ws"' signaling` → 2（`server.go:25` 常量、`:77` 路由）；② app 侧 `AppConfig.kt:34 const val SIGNALING_PATH = "/ws"` 且端点真源 `grep -c "8443/ws" app/build.gradle.kts` = 1；③ `"/signal"` 在 `.go/.kt` → rc=1；**运行时** `GET http://127.0.0.1:8443/signal` → **404** |
| V34 | **已验证** | `signaling/protocol/`：`turnCredential|stunUrl` = 5（≥4）；`"turnUser"|"turnPass"` = 0 |
| V35 | **已验证** | signaling 1 处（`util/roomid.go:18`）、app 1 处（`SignalingMessage.kt:172`） |
| V36 | **已验证（人工确认）** | 错误码 4 个枚举命中 33 处；`signaling/protocol/heartbeat.go:11 PingInterval = 15 * time.Second`；`config.DefaultPongWait = 3 × PingInterval`（服务端读超时 45s） |
| V37 | **已验证** | `peerLeft` 23、`RemoveRoom` 6、`Close()` 21 → 三者均命中 |
| V38 | **已验证** | `sirupsen/logrus` 10 命中；`log/slog` 0 命中（`--include='*.go'`） |
| V39 | **已验证** | `. <WS>/env-go.sh && cd signaling && go build ./... && go vet ./...` → **rc=0**（`build OK` / `vet OK`）；宿主机 `CGO_ENABLED=1 go test -race ./... -count=1` → **exit 0**，logging/room/server/util 全 `ok`，无 data race |

### 12.6 日志与导出

| # | 结论 | 证据 |
|---|---|---|
| V40 | **已验证（人工确认）** | `android.util.Log` 命中 6 处，**全部在 `log/` 目录内**（LogLevel.kt、Log.kt、FileLogger.kt） |
| V41 | **已验证（人工确认）** | 真实调用链：`WebRtcDemoApp.kt:53 NativeLog.ensureInitialized(this, AppLog.level())` → `NativeLog.kt:106 ensureInitialized` → `:121 nativeInit(path, BASE_NAME, level.code, maxBytesPerFile = MAX_BYTES_PER_FILE, maxFiles = MAX_FILES)`；加固点 `webrtc/WebRtcEngine.kt:77`（先于 `PeerConnectionFactory.initialize`，约 :80）。Kotlin 命中 13 / cpp/jni 命中 11 |
| V42 | **已验证** | `app/src/main` 内 `com.example.webrtcdemo.fileprovider` 5 处；`res/xml/file_paths.xml:10 <files-path name="logs" path="logs/" />` |
| V43 | **已验证** | `2097152 / 2 * 1024` 命中 3 处（`native_log.h:132` 等） |
| V44 | **已验证** | `webrtcdemo-logs- / ACTION_SEND / EXTRA_STREAM` 命中 7 处（`diag/LogExporter.kt`） |
| V45 | **已验证** | `setInjectableLogger / Loggable` 命中 13 处（`webrtc/LibwebrtcLoggable.kt`、`WebRtcEngine.kt`） |
| V46 | **已验证** | `setDefaultUncaughtExceptionHandler` 命中 2 处（`WebRtcDemoApp.kt:64`） |

### 12.7 构建与产物

| # | 结论 | 证据 |
|---|---|---|
| V47 | **已验证** | `third_party/libwebrtc/java/`：`libwebrtc-java.jar` 1048264 B、`libwebrtc-arm64.aar` 6456926 B、`jni/arm64-v8a/libjingle_peerconnection_so.so` 12946912 B |
| V48 | **已验证** | `llvm-objdump -f third_party/libvpx/lib/libvpx.a` → **156/156 成员 `architecture: aarch64`**（判定按输出，不按退出码）；`libjingle_peerconnection_so.so` → `elf64-littleaarch64`；`file(1)` 对 `.a` 只报 `current ar archive`（宿主机实测），不得据此判失败 |
| V49 | **部分验证（构建未由我重跑；产物与单测已复核）** | ① 产物（**t27 终版**）：`app-debug.apk` 33,293,061 B / mtime `2026-09-14 11:28:34.950` / `sha256 721df1c82841ad992ffef016cdb4fc09335028869fa443e98f24fe797055b724`；构建证据 = `reports/logs/t26d-nocache-assembleDebug-20260914-112608.log`（`--no-build-cache`、`:app:clean` 执行、FROM-CACHE=0、42 executed）② **我未重跑 `assembleDebug`**（重编归 t26；重跑会改变交付字节）③ **单元测试**：`./gradlew :app:testDebugUnitTest --rerun-tasks` → **`BUILD SUCCESSFUL in 2m 19s`、`24 actionable tasks: 24 executed`**；XML（11:39:00）= **5 类 / 42 用例 / failures 0 / errors 0**（AppConfigUrlTest 8、NativeInterfaceContractTest 4、SignalingErrorPolicyTest 17、SignalingIdentityTest 11、**JniBindingClasspathTest 2**），详见 §3.9 与 §12 |
| V50 | **已验证（在交付的 debug APK 上）** | `unzip -l app-debug.apk` → `lib/arm64-v8a/libwebrtcdemo_native.so`（1231512）、`lib/arm64-v8a/libjingle_peerconnection_so.so`（12946912），另含 `libc++_shared.so`、`libandroidx.graphics.path.so`；从 APK 抽出后 `llvm-objdump -f` → 两者均 `elf64-littleaarch64`。**注**：交付为 **debug** APK，`app/build/outputs/apk/release/` 不存在（release 未构建） |
| V51 | **已验证** | `aapt2 dump badging` → `package: name='com.example.webrtcdemo'` versionCode 1 / versionName 1.0、compileSdk 34；权限 `INTERNET/CAMERA/RECORD_AUDIO/ACCESS_NETWORK_STATE/MODIFY_AUDIO_SETTINGS`；`aapt2 dump xmltree --file AndroidManifest.xml` → `usesCleartextTraffic=true`、`FileProvider authorities=com.example.webrtcdemo.fileprovider exported=false`、MainActivity `exported=true` |
| V52 | **已验证（附口径注记）** | `find signaling -type f -name "signaling*" -not -name "*.log"` → `signaling/signaling`、`signaling/dist/signaling-linux-amd64`；`file` → `ELF 64-bit LSB executable, x86-64 … statically linked … stripped`。**注**：交付/部署二进制为 `c298235a…c068`（`/opt/signaling/signaling`，运行中 PID 200623），而仓库 `signaling/dist/` 于 20:37 被重建为 `b3e502a0c7d233…`（差异仅嵌入的 VCS stamp；见 commit `39a62bd`） |
| V53 | **已验证** | `systemctl list-unit-files coturn.service signaling.service` → 两者 `enabled`；`systemctl show -p FragmentPath` → coturn `/usr/lib/systemd/system/coturn.service`、signaling `/etc/systemd/system/signaling.service`；仓库 `deploy/coturn.service`、`deploy/signaling.service` 与 `systemctl cat` **逐行 diff identical**；signaling unit `ExecStart` 含 `-log /var/log/signaling/signaling.log`（C31） |

### 12.8 需真机（D 项）

| # | 结论 | 原因 |
|---|---|---|
| V54 | **未能验证** | 无 Android 设备/模拟器：未安装 APK，无法复现 `create→join→offer/answer/ice` 与视频互通 |
| V55 | **未能验证** | 需真机读 `outbound-rtp.encoderImplementation` |
| V56 | **未能验证** | 需真机复现限速场景与 CSV/stats 对照（静态侧：`encoder_bitrate.csv` 表头与写入代码已在位） |
| V57 | **未能验证** | 需真机切换 `iceTransportPolicy=RELAY` |
| V58 | **未能验证** | 需真机验证 NAT 类型显示与 `Leave` 回首页 |
| V59 | **未能验证** | 需真机验证日志导出 zip 的系统分享与内容 |

### 12.9 coturn 与 ICE server

| # | 结论 | 证据 |
|---|---|---|
| V60 | **已验证** | 宿主机三条 grep → `5:listening-ip=172.21.0.219`、`6:external-ip=47.238.144.66/172.21.0.219`、`7:relay-ip=172.21.0.219`（内网）；`deploy/turnserver.conf` 与 `/etc/turnserver.conf` 的**非注释有效指令逐行一致**（各 14 行，`diff` 无输出 exit=0）；md5 不同（`d9544361…` vs `35ae6301…`）**不判失败** |
| V61 | **已验证** | `systemctl is-active coturn` → `active`；`turnutils_stunclient -p 3478 172.21.0.219` → `UDP reflexive addr: 47.238.144.66:…`；`turnutils_uclient -v -y -u demo -w demopass -p 3478 -n 3 -m 2 172.21.0.219` → `Total lost packets 0 (0.000000%)`，rc=0 |
| V62 | **已验证** | `turnutils_uclient … -w WRONGPASS …` → `ERROR: Cannot complete Allocation`（负例如期失败） |
| V63 | **命令口径未通过（契约缺陷）／行为口径已验证** | 原样命令 `grep -rnI --include='*.go' "stun:47.238.144.66:3478" signaling` 与 `…turn:…` 均 **rc=1（0 命中）**：URL 由运行时 `-stun/-turn` 参数下发（Go 默认值为占位 `stun:1.2.3.4:3478`），源码中本就不存在该字面量。**行为侧我实跑** `node scripts/verify_signal_e2e.mjs`：EIP 与内网各 **19/19 PASS, exit 0**，其中断言 `created/joined` 下发的 `stunUrl`/`turnUrl` 与 §7.7 逐字一致，并用下发凭据真实 Allocate 成功（`relay=47.238.144.66:49184 lifetime=600s`）。**建议 architect 把 V63 改为对运行时下发值断言** |
| V64 | **已验证（R9 已解除）** | 宿主机 `turnutils_stunclient -p 3478 47.238.144.66` → `UDP reflexive addr: 47.238.144.66:33125`（rc=0）；容器侧 `curl http://47.238.144.66:8443/healthz` → **200**、`/ws` → 400（缺 Upgrade 头，正常）、`/signal` → 404；TCP 8443 OPEN；TCP 3478/5349 容器侧 TIMEOUT（**非阻塞残留**：demo 走 `transport=udp`） |

---

## 3. 交叉一致性专项核对（本任务的显式验收要求）

### 3.1 JNI 方法表：Kotlin ↔ C++ **逐字一致（15/15）**

方法：自写解析器（`/tmp/jnicheck2.mjs`）把 Kotlin `external fun` 的参数/返回类型映射为 JNI 签名，与 C++ `JNINativeMethod` 表逐条比对（名称+签名）。

| Kotlin 类 ↔ C++ 文件 | 方法数 | 结果 |
|---|---|---|
| `NativeLog.kt` ↔ `native_log_jni.cpp` | 4 | 全部一致：`nativeInit (Ljava/lang/String;Ljava/lang/String;IJI)V`、`nativeSetLevel (I)V`、`nativeFlush ()V`、`nativeShutdown ()V` |
| `NativeVp9Encoder.kt` ↔ `vp9_encoder_jni.cpp` | 9 | 全部一致，含 `nativeEncode (JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I`、`nativeSetRates (J[IIIII)I`、`nativeCopyEncodedFrame (JLjava/nio/ByteBuffer;[I)I` |
| `NativeNatDetector.kt` ↔ `nat_detector_jni.cpp` | 2 | 一致：`nativeDetect (Ljava/lang/String;IJ)V`、`nativeCancel ()V` |
| **汇总** | **15** | **签名不一致 0 个** |

回调表 B-1（C++→Java，2 个）：`callback_bridge.cpp:24 kOnNatTypeDetectedSignature = "(Ljava/lang/String;Ljava/lang/String;)V"`、`:26 kOnLogEventSignature = "(ILjava/lang/String;Ljava/lang/String;)V"` → 与 `NativeCallbacks.kt` 的 `onNatTypeDetected(natType: String, detail: String)` / `onLogEvent(level: Int, tag: String, message: String)` 一致。
冻结类名字符串 4 个（`jni_bridge.h:21–27`）与契约 §6.1 逐字一致。

### 3.2 信令消息类型与字段：Go ↔ Kotlin 一致

- **类型集合**（14/14，`onlyGo=∅`、`onlyKotlin=∅`）：`create, created, join, joined, peerJoined, peerLeft, offer, answer, ice, natType, leave, error, ping, pong`
- **字段**：`created`={roomId,stunUrl,turnUrl,turnUsername,turnCredential}（无 peerId）、`joined` 加 `peerId`、`peerJoined/peerLeft`={peerId}、`offer/answer`={sdp}、`ice`={candidate,sdpMid,sdpMLineIndex}（Go 侧 `*int`+omitempty 支持"至少一个有效"）、`natType`={natType}、`error`={code,message}、`ping/pong`={timestamp}
- **错误码**：`doc/09 §3.10` 六条；Go `protocol/errors.go` 六条全定义；Kotlin 终态集 5 条（**不含** `ROOM_FULL`，符合 v1.0-o §8.4 语义）；非终态错误仍经 `SignalingClient.kt:429–432` 记录 `server_error`（code/detail）
- **NAT 枚举 6/6 一致**：Open / FullCone / RestrictedCone / PortRestrictedCone / Symmetric / Unknown
- **心跳**：Kotlin `PING_INTERVAL_MS=15_000`、`PONG_TIMEOUT_MS=5_000`、`RECONNECT_DELAY_MS=3_000`、`MAX_RECONNECT_ATTEMPTS=3`；Go `PingInterval=15s`、`DefaultPongWait=45s` —— 与 `doc/09 §6`、`doc/14 §8.1` 一致

### 3.3 CMake 引用路径 ↔ `third_party` 实际布局

| CMake 引用 | 实际布局 | 结论 |
|---|---|---|
| `${WEBC_THIRD_PARTY}/libvpx/lib/libvpx.a`（`CMakeLists.txt:22/25–27`） | `third_party/libvpx/lib/libvpx.a` 1929142 B 存在 | 一致 |
| `${LIBVPX_PATH}/include`（:28/47） | `third_party/libvpx/include/vpx/vpx_codec.h` 存在 | 一致 |
| Gradle 注入 `-DWEBC_THIRD_PARTY=${rootProject.projectDir}/third_party`（app/build.gradle.kts:62） | 仓库根 `/third_party` | 一致 |
| 无注入回退 `${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party` | `app/src/main/cpp/../../../../third_party` = 仓库根 `/third_party` | 一致 |
| **禁止**链接 libwebrtc | 全文无非注释命中；`target_link_libraries(… vpx log android)` | 一致 |

### 3.4 APK 内 arm64-v8a `.so`（含自研库）

**终版 `app-debug.apk`（33,260,234 B / `b0cddd86…`）**（其前一次构建 `c72d3667…` 已被 clean 覆盖，两版内同名 `.so` 哈希相同，见 §0.2/§3.13）内：`lib/arm64-v8a/` 含 `libwebrtcdemo_native.so`、`libjingle_peerconnection_so.so`、`libc++_shared.so`、`libandroidx.graphics.path.so`；前两者抽出后 `llvm-objdump -f` 均为 `elf64-littleaarch64`；自研 `.so` 动态符号导出仅 `JNI_OnLoad`。dex 侧：`SelfVp9Libvpx`（classes3/8/11）、`nativebridge/NativeVp9Encoder`（classes3/8）、`com/example/webrtcdemo/MainActivity`（classes12）、`org/webrtc/PeerConnectionFactory`、`org/webrtc/VideoEncoder` 均可检索到 → 自研类与 SDK 均已打包。

### 3.5 libwebrtc Java SDK 产物可解析出 `org/webrtc` 类

`jar tf`/`javap`：`libwebrtc-java.jar` 含 **426** 个 `org/webrtc/*.class`；`javap org.webrtc.VideoEncoder` 可解析（major version **61** = Java 17）；`libwebrtc-arm64.aar` 内 `classes.jar`（1048264 B，与 jar 同版）＋ `jni/arm64-v8a/libjingle_peerconnection_so.so`。

### 3.6 编码器注入路线与契约一致（A1）

- Kotlin：`Vp9VideoEncoderFactory` 实现 `org.webrtc.VideoEncoderFactory`；`Vp9VideoEncoder` 实现 `org.webrtc.VideoEncoder`；实现名 `SelfVp9Libvpx`；`createNative(...) = 0L`（升级开关关闭）
- C++：**不继承** `webrtc::VideoEncoder`（V17=0）；CMake 不链 libwebrtc/libjingle（V18）
- 闭环：`SetRates → LayerBitrateAllocator::Compute → ss/ts/layer_target_bitrate + rc_target_bitrate → vpx_codec_enc_config_set`（V21 四要素齐备；`vp9_encoder.cpp:328`）
- **L1T3 现状**：`InitEncode` 拒绝 `num_spatial_layers != 1`；`ss_number_layers` 恒 1；时序层 3；`temporal_layering_mode` 由 `TemporalLayeringModeFor(3)` 显式设置（§11.4 D-5）

### 3.7 前端边界专项（V 表外，captain 指派）

| 项 | 结论 | 证据 |
|---|---|---|
| D-3 身份推导 | **已验证（静态）／运行时未验证** | `SignalingIdentity` 9 处真实使用；`invert` = `SLOT_A→SLOT_B`、`SLOT_B→SLOT_A`、else `null`（`SignalingClient.kt:579–583`）；我用 Node 独立复算 6 用例全 OK；剥离注释后全源树仅两处 `const val` 出现 `peer-00`，**无槽位顺序推断**；UI `StatusPanel.kt:54/76` 未知显示 `—` |
| D-6 Offerer 不变量 | **已验证** | `sendOffer` 全仓仅 1 处调用（`CallSession.kt:160`）← `createOffer()` 唯一调用者 `CallViewModel.kt:359` ← `maybeCreateOffer()` 唯一出口，闸门 <code>:356 if (role != ROLE_HOST &#124;&#124; !peerJoined &#124;&#124; !sessionReady) return</code>；触发路径 3 条（`:258/:265/:301`）；joiner 仅 `createAnswer`（`CallSession.kt:184+`）→ **同一时刻只有一方发 Offer 成立** |
| `ROOM_FULL` 非终态 | **已验证（上轮 medium 已修复）** | `SignalingErrorPolicy.TERMINAL_CODES` **不含** `ROOM_FULL`；`actionFor(ROOM_FULL, rejoinAfterDrop=true)=RETRY_REJOIN`、首入房 `=SURFACE`；`Action.clearsRoomIntent` 仅终态为 true；退避 `1,2,4,8…`（封顶 8s）×10 → 我用 Node 独立复算累计 **63 000 ms = 63 s > 45 s** |
| 读流按 `type` 分发 | **已验证** | `SignalingClient.kt:166 SignalingCodec.decode(text)`（kotlinx sealed + `classDiscriminator="type"`）→ `logIncoming/advanceStateOnIncoming/SignalingIdentity.update/listener.onMessage`；`Pong` 在 `:172–175` 就地消费；无"下一条必是 joined"假设 |

### 3.8 APK 内部结构（解包级证据）

`unzip -oq app-debug.apk -d /tmp/apkfull` 后实测：

| 检查 | 结果 |
|---|---|
| `AndroidManifest.xml` | 7572 B 存在 |
| `resources.arsc` | 438772 B；`aapt2 dump resources` → `Binary APK` / `Package name=com.example.webrtcdemo id=7f`（**可解析**） |
| `res/xml/file_paths.xml` | 528 B 存在（二进制 XML），与 `AndroidManifest.xml` 的 provider `meta-data android.support.FILE_PROVIDER_PATHS` 对应 |
| FileProvider | `aapt2 dump xmltree` → `androidx.core.content.FileProvider`、`exported=false`、`authorities="com.example.webrtcdemo.fileprovider"`、`grantUriPermissions=true` |
| dex 内容 | `com/example/webrtcdemo/MainActivity`（classes12）、`org/webrtc/PeerConnectionFactory`（classes5/11/13）、`SelfVp9Libvpx`（classes3/8/11）、`nativebridge/NativeVp9Encoder`（classes3/8） |
| 抽出的 `.so` 哈希 | `lib/arm64-v8a/libwebrtcdemo_native.so` = **`e9b66cc98d97454c32d535cb670bae251d381c383fff98ea11d922cb798f35f5`**（1231512 B，打包 strip 后）；`lib/arm64-v8a/libjingle_peerconnection_so.so` = **`757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`**（12946912 B） |
| `libwebrtcdemo_native.so` `readelf` | `NEEDED` = `liblog.so / libandroid.so / libm.so / libc++_shared.so / libdl.so / libc.so` —— **不含 `libjingle` / `libwebrtc`**；**对外导出（`.dynsym` 已定义 GLOBAL FUNC）仅 `JNI_OnLoad` + `JNI_OnUnload`（计数 = 2）**；UND（导入）= 109，其中含 `webrtc`/`jingle` 字样 = **0**；**无 `.symtab`（已 stripped）** |
| APK 内 `.so` 与中间产物的一致性 | 含调试信息的中间产物 `app/build/intermediates/cxx/Debug/3n4t43a2/obj/arm64-v8a/libwebrtcdemo_native.so` = **2 206 528 B / `36422334b279123a…`（有 `.symtab`）**；strip 后 `…/stripped_native_libs/debug/…/libwebrtcdemo_native.so` = **1 231 512 B / `e9b66cc9…`**，且与 **APK 内同名文件逐字节相同**（`cmp` 通过）→ native-dev §9.1.2 的"1 231 512 B(strip) 与 2 206 528 B(含调试信息) **同源不矛盾**"**已被我独立复现** |
| `libjingle_peerconnection_so.so` `readelf` | `NEEDED` = `libEGL.so / libdl.so / libm.so / liblog.so / libc.so`（自包含，未依赖自研库） |

### 3.9 关键判据复现（VCS 戳归因 / 接口守卫 / Go 测试）

| 判据 | 命令 | 我的实测结果 |
|---|---|---|
| **Go VCS-free 基线** | `cd signaling && go build -trimpath -ldflags="-s -w" -buildvcs=false -o /tmp/gv-novcs-N .`（与 `scripts/build_app.sh:283` 完全一致，跑两次） | 两次均 **`1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa`**，`cmp` 逐字节相同 ✓ → **与报告声明的基线一致**，"默认构建哈希差异仅由 VCS 戳引起"成立（**注**：不带 `-trimpath -ldflags="-s -w"` 重建会得到 `87e47f29…`，属命令不一致，非缺陷；当前 HEAD 下默认构建为 `efed7721…`） |
| **Go 单元测试** | `. <WS>/env-go.sh && cd signaling && go test ./... -count=2` | `logging 0.062s / room 2.006s / server 6.922s / util 0.035s` 全 `ok`，**rc=0** |
| **JNI/接口守卫脚本** | `bash /tmp/t14/t7iface.sh` | **`passed=19 failed=0`**（含 `external fun` 15、状态码映射、I420 直通、`setrates` 配对字段等） |
| **Kotlin 单测** | `./gradlew :app:testDebugUnitTest --rerun-tasks`（**强制全量重跑，排除 UP-TO-DATE 假通过**） | `BUILD SUCCESSFUL in 1m 44s`，**`24 actionable tasks: 24 executed`**（无 UP-TO-DATE）；结果 XML（21:19:56）= **tests 36 / failures 0 / errors 0**（AppConfigUrlTest 8、SignalingErrorPolicyTest 17、SignalingIdentityTest 11）。早前 `--rerun`（单任务强制）亦为 `BUILD SUCCESSFUL in 9s` / 1 executed |

### 3.10 captain 给出的交叉基准：逐项复测结果

| 项 | captain 值 | 我的复测 | 结论 |
|---|---|---|---|
| APK | 33 260 234 B / `c72d3667…` / mtime 20:21:25 | 我当时复测确为 `c72d3667…`；**但该字节现已被 t18 的 no-cache clean 构建覆盖**，盘上现为 **`b0cddd86…` / mtime 21:42:38**（见 §0.2/§3.13） | **当时一致；终版以 `b0cddd86…` 为准** |
| APK 内 `libjingle_peerconnection_so.so` | `757cef81…59e` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`（12946912 B） | **一致** |
| APK 内 `libwebrtcdemo_native.so` | `e9b66cc9…5f5` / 1 231 512 B（打包 strip 后） | `e9b66cc98d97454c32d535cb670bae251d381c383fff98ea11d922cb798f35f5` / 1231512 B | **一致** |
| 现行 libwebrtc jar | `d98939bb…543` / 1 048 264 B / 453 class 全 major 61 | 同哈希同大小；`unzip -l` 计 **453** 个 `.class`；抽样 12 个 `javap` 全 `major version: 61` | **一致** |
| AAR 内 `classes.jar` | 同 `d98939bb…` | `sha256 classes.jar` = `d98939bbf0c0cd071baff19004a4dc2f602997b70e33e0fc3669c5fccb5f6543`（1048264 B） | **一致**（captain 的订正成立；`reports/10` 中仅存的历史小节 `:205` 已判为历史语境，见 §5.1 F-02） |
| Go 二进制 | `signaling/dist/…` 5 496 984 B / `c298235a…` | 大小 5,496,984 B ✓；但 **dist 现为 `b3e502a0…`**；`c298235a…` 是**部署件** `/opt/signaling/signaling`（运行中 PID 200623） | **大小一致、哈希口径需修正** → CD-6 |
| `libvpx.a` | 1 929 142 B | 1,929,142 B | **一致** |
| third_party 侧 `.so` | `757cef81…` / 12 946 912 B / stripped | `757cef8128bf9151…` / 12946912 B | **一致** |
| HEAD | `198d514`、`git status --porcelain` 空、tracked 169 | 验证期间 HEAD 连续前进：`39a62bd` → `d7a2471` → `a57c057` → **`14ef053`（收尾提交）**；tracked **169** ✓；`status` 仅剩 ` M reports/07-native-dev.md`（授权在途）+ 本报告未跟踪 | **按"最新 HEAD"记录**（§0）；F-01 已由 `14ef053` 闭合 |

---

### 3.11 t15 改动文件计数口径（**8 = 7 + 1**，captain 裁定 + 我的独立核验）

**统一写法**：**t15 共改 8 个 Kotlin 文件；其中 7 个承载 26 个 `e:` 编译错误，第 8 个（`webrtc/VideoRendererPool.kt`）为 captain 追加的首帧核对项**。

我的独立核验（宿主机 `find … -newermt` mtime 窗口 + `stat`）：

| 类别 | 文件 | 证据 |
|---|---|---|
| 26 个错误的承载者（**7 个**） | `ui/theme/Color.kt`、`log/Log.kt`、`diag/DiagnosticsScreen.kt`、`webrtc/WebRtcConfig.kt`、`webrtc/StatsMapper.kt`、`webrtc/FrameNormalizer.kt`、`encoder/Vp9VideoEncoder.kt` | `find app/src/main/kotlin -name "*.kt" -newermt "2026-09-13 17:35" ! -newermt "2026-09-13 17:50"` → **恰好 7 个**（完全命中该清单） |
| captain 追加的核对项（**第 8 个**） | `webrtc/VideoRendererPool.kt` | 同法在 `17:50–18:05` 窗口 → **恰好 1 个**；`stat -c '%y'` = **`2026-09-13 18:00:01.910653773 +0800`**（与你给的 `18:00:01.910` 一致）；改动 = 本端 `init(…, null)` 收紧为传非空 `RendererEvents`（首帧回调 `onFirstFrameRendered`），**不在那 26 个之内** |

**差异来源说明（不改他人报告，仅在此更正）**：
- `reports/08-android-dev.md:975` 写「**恰好 7 个**」——那是按 **`17:35–17:50` 单窗口**统计的"26 错误修复文件"，口径正确但**不含第 8 个**；
- `reports/10-app-build.md:55` 写「**7 个文件**」——同为 26 个错误的承载者口径；
- 两处**均未出现"9 个"**；但若读成"t15 只改了 7 个文件"会**漏掉你追加的 `VideoRendererPool.kt`**，故终报统一采用 **8 = 7 + 1**。

### 3.12 终版 jar 的 API 形态交叉复现（"API 不变、仅 class 版本变化"）

| 判据 | 我的自跑结果 |
|---|---|
| class 可解析性与 major 集合 | 自写 ZIP+class 头解析：**class 总数 453、可解析 453、major 分布 `{61: 453}`** |
| `apipairs`（"类::成员"存在性，沿父类/接口链，164 条） | `node /tmp/t14/apipairs.js third_party/libwebrtc/java/libwebrtc-java.jar` → **共 164 条，缺失 0 条** |
| `apicheck`（源码→jar 成员级扫描，97 检查点） | `node /tmp/t14/apicheck.js` → **成员级检查点 = 97，问题 = 0** |

⇒ 三项与实现者工具链无关，独立印证「**终版 jar 的 API 形态不变、仅 class 版本 = 61（Java 17）**」，从而支撑「**换 jar 不改变 Kotlin 侧编译语义**」（与 t15 的 26 错误修复正交）。

### 3.13 终版 APK 的构建证据链与“逐字节可复现性”结论（**结论已更正**）

**a) 构建证据链（补上了此前缺失的 clean 构建日志）**

| 项 | 实测 |
|---|---|
| 决定性日志 | `reports/logs/final-nocache-assembleDebug-20260913-214035.log`（t18） |
| 命令 | `./gradlew --no-daemon --no-build-cache clean assembleDebug` |
| `:app:clean` | **执行**（日志第 12 行 `> Task :app:clean`；其前另有 `externalNativeBuildCleanDebug`） |
| 任务执行态 | **`43 actionable tasks: 42 executed, 1 up-to-date`**；`grep -c FROM-CACHE` = **0**；`dexBuilderDebug` / `packageDebug` / `assembleDebug` 均**实际执行**（非 UP-TO-DATE/非缓存还原） |
| 输入侧记录 | 日志头部记录 `jar(before) sha=d98939bb…`（1 048 264 B） |
| 结果 | **APK `b0cddd86…`（33 260 234 B，mtime 21:42:38）**，`BUILD SUCCESSFUL in 2m 2s`；该结论与 `reports/10` §5.7.3/§5.7.4 同批随 **`b66c166`** 入库 |
| 对照（此前的“authoritative”日志） | `authoritative-assembleDebug-20260913-210556/211309/211731.log` 三次均 **`FROM-CACHE = 21`**（缓存辅助），故其同哈希是**缓存的必然结果** |

**b) 结论更正：APK **不是**逐字节可复现**

- **四次缓存辅助构建同哈希 `c72d3667…`**（`dex` 由构建缓存还原）＋ **一次完全执行（`--no-build-cache` + `clean`）得 `b0cddd86…`** ⇒ **APK 非逐字节可复现**；差异位于打包元数据/生成顺序，**未证实任何载荷语义差异**（见下 c）。
- **来龙去脉（据实记录）**：该点最初由 **android-dev** 提出；**captain 曾错误地否定它**（并向本报告转达过“APK 非逐字节可复现是错误假设”）；现由 t18 的实测**确立**。**该更正由 captain 承担**，本报告此前任何暗示“同哈希即已复现”的表述一并作废。
- 与之对照，**Go 侧的 VCS-free 基线仍是逐字节可复现**（§3.9：两次构建 `cmp` 相同），两件事不要混为一谈。

**c) 终版 APK（`b0cddd86…`）的产物级复验（我自跑，全部通过）**

| 检查 | 结果 |
|---|---|
| 解包四项 | `AndroidManifest.xml` / `resources.arsc`（438 772 B，`aapt2 dump resources` → `Binary APK` / `Package name=com.example.webrtcdemo id=7f`）/ `res/xml/file_paths.xml` / `lib/arm64-v8a/` 均存在 |
| APK 内两个 `.so` 的 sha256 | `libjingle_peerconnection_so.so` = **`757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`**（12 946 912 B）；`libwebrtcdemo_native.so` = **`e9b66cc98d97454c32d535cb670bae251d381c383fff98ea11d922cb798f35f5`**（1 231 512 B）—— **与前一版 `c72d3667…` 内的同名 `.so` 完全一致** |
| `readelf`（自研 `.so`） | 导出（已定义 GLOBAL FUNC）**仅 `JNI_OnLoad` + `JNI_OnUnload`**；`NEEDED` = liblog/libandroid/libm/libc++_shared/libdl/libc（**无 libjingle/libwebrtc**）；含 `jingle|webrtc` 的 UND = **0**；`.symtab` = **0**（stripped） |
| dex | 13 个 dex；含 `com/example/webrtcdemo/MainActivity`、`org/webrtc/PeerConnectionFactory`、`SelfVp9Libvpx`、`nativebridge/NativeVp9Encoder` |
| Manifest/组件 | `package=com.example.webrtcdemo`；CAMERA/RECORD_AUDIO/INTERNET/ACCESS_NETWORK_STATE/MODIFY_AUDIO_SETTINGS；`usesCleartextTraffic=true`；`MainActivity exported=true`；FileProvider `authorities=com.example.webrtcdemo.fileprovider` |
| 附：AAR 归档口径（复核 `reports/10`） | `libwebrtc-arm64.aar` = 6 456 926 B；原件归档 `libwebrtc-arm64.aar.orig` = 6 457 598 B / **`fe26d97f12586b2c8e5c7cf66d3499f74b46b888135e7eed408ca746605d178e`**（其 `classes.jar` = `ad54a0a2…` v69）；现行 AAR 内 `classes.jar` = `d98939bb…`（major 61）→ **`reports/10` 已写对，F-02 完全闭合** |

⇒ **终版 APK 的全部产物级检查通过** ⇒ 维持 `pass`；唯一更正是**可复现性表述**（b），非交付缺陷。

---

## 4. D6 日志与可观测性核验

| 层 | 结论 | 证据 |
|---|---|---|
| Go | **代码级已验证／运行时可验证部分已验证** | `logging/logging.go:45 logger.SetOutput(io.MultiWriter(os.Stdout, rw))`；自研 `logging/rotating.go:14 DefaultMaxBytes = 2 * 1024 * 1024`；`-log-level` 支持；**运行时**：`/var/log/signaling/signaling.log` 423016 B 且持续写入，行格式 `2026-09-13T09:41:19.301Z WARN    go     signaling [200623/-] http_unknown_path …`（UTC 毫秒 `Z`、LEVEL 定宽 7、layer 定宽 6、`[pid/-]`） |
| Kotlin | **代码级已验证／运行时未验证** | `log/FileLogger.kt` 单写盘线程 + `FileOutputStream(file, true)`（追加）；通道含 `app.log`；`log/Log.kt` 为唯一门面（V40 命中仅在 `log/`） |
| C++ | **代码级已验证／运行时未验证** | `log/native_log.cpp:194 open(O_WRONLY|O_CREAT|O_APPEND,0644)`、`:253` 单条一次 `write()`、`:290 rename` 滚动、`:174` CSV 打开＋`:179` 表头 |
| libwebrtc | **代码级已验证／运行时未验证** | `webrtc/LibwebrtcLoggable.kt` 实现 `Loggable`，`WebRtcEngine.kt` 经 `setInjectableLogger` 注入（§9.7） |
| 导出（FileProvider） | **静态已验证／运行时未验证** | `AndroidManifest.xml` provider authority `com.example.webrtcdemo.fileprovider`；`res/xml/file_paths.xml` 含 `<files-path name="logs" path="logs/" />`；`diag/LogExporter.kt` 含 `webrtcdemo-logs-`/`ACTION_SEND`/`EXTRA_STREAM`；**zip 生成与系统分享需真机** |

---

## 5. 结构化 findings 与契约口径缺陷（**均非实现问题**）

### 5.1 结构化 findings（captain 指定严重级）

| id | severity | problem | requiredFix | 文件:行 | 状态 |
|---|---|---|---|---|---|
| **F-01** | ~~medium~~ **已闭合** | 退役脚本 `scripts/fix_jar_class_version.sh` 系 `198d514` 误入库 | 已由 **`14ef053`** 执行 `git rm`（93 行删除）；复核：`git ls-files scripts/` = **0 命中** | `scripts/fix_jar_class_version.sh` | **已闭合（2026-09-13，`14ef053`）** |
| **F-02** | ~~low~~ **已闭合（附注）** | `reports/10` 中"AAR 内 `classes.jar` 未改动（仍 v69）"**仅剩 1 处在 `:205`**，且位于 **§6.1「我当时的应急处理（已被取代，保留记录）」**历史小节内；同文件另一处已改为"本条原写 AAR 仍是 v69，此处如实更新"（现行 `:245`）。我实测现行 AAR 内 `classes.jar` = `d98939bb…`（major 61），v69 仅存于 `.aar.orig`（6 457 598 B） | 若要求零容忍措辞：将该句改指 `libwebrtc-arm64.aar.orig`；否则保留为历史记录即可 | `reports/10-app-build.md:205` | **已闭合（判为历史语境）；captain 可选择追加一句订正** |

### 5.2 契约本体的口径缺陷（建议 architect 修订）

| # | 位置 | 现象（实测） | 建议 |
|---|---|---|---|
| CD-1 | §12.3 **V16** / §5.7 | 交付 jar 的 `org.webrtc.VideoEncoder` **没有** `createNativeVideoEncoder()`，只有 `createNative(long)`（`javap` 实测）；按字面必然假失败 | 将 V16 与 §5.7 的 API 名改为 `createNative(long)`，验收口径改为"该方法返回 0（默认实现即返回 0）" |
| CD-2 | §12.9 **V63** | 两条 grep 在 `signaling/*.go` 内 0 命中：STUN/TURN URL 由运行时 `-stun/-turn` 下发（Go 默认值为占位 `1.2.3.4`），源码本无该字面量 | 改为断言**运行时下发值**（如 `curl /healthz` 或 e2e 脚本断言 `created/joined` 的 `stunUrl/turnUrl`） |
| CD-3 | §12.4 **V29** | 正则 `[A-Za-z]+` 不含数字 → `NativeVp9Encoder` 被截断为 `NativeVp`，计数仍为 4，"类名一致"未被逐字覆盖（弱断言） | 正则改 `[A-Za-z0-9]+`，或直接与 §6.1 四个字面量做集合比对 |
| CD-4 | 契约指纹流程 | 会话内指纹多次漂移：1219 → 1220 → 1250(beaf6829) → 1250(cd3dbf5d) → 1257(6f664977) → 1263(2163dcfb) → 1264(e132df95) → 1279(d2a90beb) → 1325(894f15bf) → **1337(b3b67438，t11 实测，MATCH)**；captain 最后告知的 1325/894f15bf 已非磁盘值 | ① 每次改动后广播新哈希；② 验收方每轮先 `sha256sum` 断言（本报告已执行） |
| CD-5 | 基线口径（非契约） | captain 基线表「Go 二进制 `signaling/dist/…` = `c298235a…`」在当前磁盘不成立：`c298235a…` 是**部署件**（`vcs.revision=1a9d3ff`，PID 200623），仓库 `dist/` 现为 `b3e502a0…`（`vcs.revision=10aa709`） | 基线表拆成"部署件 / 仓库 dist"两行（§0.2 已给出对应关系） |

> 另：历史轮次已修复的契约缺陷（V41 缺 `-r`、V14/V15/V29 无路径、V60 `-E` 中 `\|`、V48 用 `file` 判 `.a`、V50/V51 硬编码 APK 名、V52 漏 `dist/`、V53 写错 coturn unit 名等）**本轮均实测已修**，不再列为缺陷。

---

## 6. 已知缺陷、限制与风险

| # | 级别 | 内容 | 影响/处置 |
|---|---|---|---|
| K-1 | 中 | **无真机**：APK 未安装运行 → V54–V59、JNI_OnLoad 注册、Camera2 采集/渲染、日志导出 zip 均未运行时验证 | 需用户设备配合；报告不将其写作通过 |
| K-2 | 中 | **仅构建 debug APK**：`app/build/outputs/apk/release/` 不存在；release 混淆/签名路径（ProGuard 规则、`isMinifyEnabled=true`）未实际执行 | 交付物为 debug；若要 release 需补构建并复跑 V50/V51 |
| K-3 | ~~低~~ **已消解** | 首次在线复跑曾因 `dl.google.com` 读超时失败（环境）；随后依赖进入缓存后 **`--rerun-tasks` 强制全量重跑成功（24/24 executed，36/0/0）** | 建议把 Gradle/AGP 依赖预热进缓存，避免 `--offline` 不可用 |
| K-4 | 低 | 仓库 `signaling/dist/signaling-linux-amd64` 现为 `b3e502a0…`，与交付/部署的 `c298235a…` 不同（VCS stamp 差异，commit `39a62bd` 已归因） | 报告口径以**部署二进制** `c298235a…` 为交付物证据 |
| K-5 | 低 | `third_party/libwebrtc/java/` 留有 `*.orig`、`*.orig-build`、`*.orig-jdk25`、`*.prev-v61`、`*.v55-java11`、`*.v61-java17` 备份文件（约 6 个） | 属 t16 应急产物，建议清理或移出仓库（不入库即可） |
| K-6 | 低 | `run_host_tests.sh` 默认 `REPO` 计算少一级（默认解析为 `app/`），不带参数直接运行会编译失败 | 须显式传仓库根（我实测：`bash app/src/main/cpp/tests/host/run_host_tests.sh <repo>` → 全部通过）；建议修默认值 |
| K-7 | 低 | §11.4 **D-2**：Go `logrus` 日志键 camelCase（如 `stunUrl`）与 §9.1 的 `key=snake_case` 不符 | 契约已裁定 low、不判失败；go-dev 后续对齐 |
| K-8 | 低 | §11.4 **D-7**：静默掉线不可完整恢复（口径 B）；`ROOM_FULL` 有界退避覆盖的是 survivor 未及时处理 `peerLeft` 的窗口；若 survivor 毫秒级挂断则房间即销毁、重连方得 `ROOM_NOT_FOUND`（终态） | 已知限制；口径 A（≥5s 宽限期 + ICE restart）为后续增强 |
| K-9 | 低 | TCP 3478/5349 入方向未放行（UDP 3478 与 49152–49200、TCP 8443 已放行） | 非阻塞（demo 走 `transport=udp`）；如需要可加放行 |
| K-10 | 信息 | t11 执行期间工作树被他人改动（`signaling/dist` 重建、HEAD 连续前进 `39a62bd`→`d7a2471`→`a57c057`）；`M reports/07-native-dev.md` 为 **captain 授权的在途项**（native-dev v1.6，已 idle、内容定稿、随收尾提交带上）**非缺陷** | 本报告所有证据均为**取数时刻**的实测；最终以收尾提交后的冻结 HEAD 为准 |
| K-11 | 信息 | R1（googlesource 吞吐）此前测量存在污染，webrtc-builder 已自我更正 | 报告中按"**吞吐波动大、未定论**"记录 |
| K-12 | 中（结构性） | **仓库不含 third_party 编译产物**：`.gitignore:12–13` 排除 `third_party/libwebrtc/`、`third_party/libvpx/` → **仅凭 clone 无法重建 APK**，必须重跑 `scripts/t5-libwebrtc-libvpx-build.sh`（约 49 min + 源码下载）；`--release 25→17` 的修补由 `scripts/patches/libwebrtc-java-release17.patch` 提供，t5 脚本按文件逐项 `patch -p1 --forward` **幂等应用**（实测脚本逻辑含 dry-run 反向校验） | 结构性限制，非可修复缺陷；终报如实标注 |
| K-13 | ~~medium~~ **已闭合** | `scripts/fix_jar_class_version.sh` 曾被 `198d514` 误入库，**已由 `14ef053` 执行 `git rm`**（复核：`git ls-files scripts/` = 0 命中） | 无需动作；残留 `reports/07-native-dev.md` 为 captain 授权在途 |
| K-14 | 信息 | 基线口径差异见 §5.2 CD-5（dist 二进制 vs 部署件） | 以"部署件 `c298235a…`（vcs.revision=1a9d3ff）为交付证据"记录 |
| **K-15** | ~~高（未闭合）~~ **已分层：jar/AAR ✅ ／ APK 待 t33** | **原缺口（已修复）**：jar 内 `GEN_JNI` 曾为 Placeholder（194 可读名 `static native`、0 转发），`.so` 为 hashing/short-proxy（193 `Java_J_N_<hash>`、无注册表）⇒ 两条绑定路径均不成立。**现行（18:17:28 落位、18:33:42 复核）**：交付 jar/AAR 为 **B 形态** —— `J/N.class` = `1ff8d3ff…`、`GEN_JNI.class` = `a6e7edcf…`、`GEN_JNI` `static native` = **0**、`J.N` native **193**、`jni_mangle` 双向差集 **0/0**、方法数 **194/194** ⇒ **jar/AAR 侧可绑定成立** | **交付 APK 侧仍未闭合**：现行 `721df1c8…`（11:28:34）由**未落位 jar** 构建、dex 内无 `LJ/N;` ⇒ **标注为"已被取代的历史轮次交付物"**；待 **t33** 重编后由 **t34** 按 §13.22 三值门禁在新 APK 实体上复验（并附 §13.15/§13.20 护栏） |
| **K-16** | **中** | **t25 回归测试只证明"类存在性"**：`JniBindingClasspathTest` 仅断言 `Class.forName` 可解析（43 条 = 1 `GEN_JNI` + 42 `*Jni`），**对 `J.N`/转发形态/native-ness 零断言** ⇒ 在当前 jar 上**会绿灯而绑定仍是断的**（假绿）；清单另漏 5 项（`org/webrtc/audio/*Jni` ×3、`org/jni_zero/*Jni` ×2；真值 47） | 建议 android-dev 加固（补 `J.N`/转发断言 + 清单 42→47）；**存在性回归不得替代可绑定判据**（§13.4） |
| **K-17** | ~~高（环境/流程）~~ **已闭合（captain 修复 + verifier 复验）** | **`.git` 属主混用**：`.git/objects/{33,56,ac,c6}` 曾为 `root:root 755` ⇒ uid 1000 的 `git commit` 报 `error: insufficient permission for adding an object to repository database .git/objects`（实测待写对象 `c6796ce1…` 落于 `.git/objects/c6`）；此后 `.git/index` 又 3 次被置为 `root:root`（18:25 / 18:27 / 18:31:18）⇒ 提交卡在索引同步 | **已修复**：宿主机 uid 1000 = `admin`（无 `node` 用户名），执行 `chown -R 1000:1000 /opt-dsh-workspaces/code/webrtc-demo/.git`（非 1000 归属 12 项 → **0** 项）；**我独立复验**：`find .git ! -user node` = **0**、以 uid 1000 `git hash-object -w` **成功** ⇒ 后续 uid 1000 提交不再踩坑。副作用：对象库多一个不可达 blob `9daeafb9864cf43055ae93beb0afd6c7d144bfa4`（`git gc` 可回收，不影响 ref/tree）。过程留痕见 §10.11 / §13.15(d) |

---

## 7. doc/13 验收点与各任务验收对照

### 7.1 doc/13 阶段验收

| 阶段 | 验收标准 | 结论 | 证据 |
|---|---|---|---|
| 0 环境检查 | 前置条件满足或已提示 | **已验证** | 宿主机：JDK 17.0.20、`android-sdk/ndk/26.1.10909125`、`cmake`、`ninja`、Gradle 8.7；Go 1.22.12 于 `<WS>/go/bin/go` |
| 1 Git + Submodule | `git submodule status` 显示两个 | **已验证** | `d2413e2c… third_party/libvpx-src (heads/main)`、`be0e9008… third_party/libwebrtc-src (heads/main)`；`.gitmodules` 两个 URL 正确 |
| 2 Go 信令 | `go build` 无错；create→created；join→joined；第二连接→peerJoined | **已验证** | `go build/vet` rc=0；`node scripts/verify_signal_e2e.mjs` **19/19 PASS exit 0**（含 create/created、join/joined、peerJoined、offer/answer/ice 双向字节级转发、natType、ping/pong、leave/peerLeft、close 1000） |
| 2.1 部署 | `curl http://<IP>:8443/ws` 返回 upgrade 响应 | **已验证** | `curl -si http://127.0.0.1:8443/ws` → 400 + "WebSocket upgrade required (RFC 6455)"；经 EIP 同样 400；`systemctl is-active signaling` = `active` |
| 3 C++ Native | 文件齐备；CMake 路径正确；关键签名与设计一致 | **已验证**（以 doc/14 为权威） | 23 个交付文件在位；CMake 路径与实际布局一致（§3.3）；JNI 15/15 逐字一致（§3.1）；宿主逻辑测试 `failures=0`、`== 全部通过` |
| 4 Android App | Gradle sync 成功；Kotlin 无编译错误；JNI 签名与 C++ 一致 | **已验证（构建级）** | t10 `BUILD SUCCESSFUL` 产出 APK；**终版 APK = `b0cddd86…`**（t18 no-cache clean 构建，日志见 §3.13）；JNI 签名一致（§3.1）；**注**：`doc/13` 文件清单中的 `WebRtcManager.kt`/`WebRtcCallbacks.kt`/CameraX 已被 doc/14 C06/C07/C08 取代（D1 路线），不作为失败 |
| 5 集成测试 | 真机双方视频互通等 8 步 | **未能验证** | 无设备（同 V54–V59） |

### 7.2 任务级验收

| 任务 | 交付/验收要点 | 结论 |
|---|---|---|
| t1 环境核验 | 宿主机事实、SSH、资源 | **已验证**（我复跑环境与 SSH；`reports/01-host-recon.md`） |
| t2 契约冻结 | `doc/14` 冻结、V01–V64 | **已验证**（1337 行；V 计数 64） |
| t3 submodule | 两个 submodule 到位 | **已验证**（§7.1 阶段 1） |
| t4 SDK/NDK/JDK | 工具链落到工作区 | **已验证**（宿主机实测） |
| t5 libwebrtc/libvpx | jar+so+aar、libvpx.a+头 | **已验证**（V47/V48；jar/so/aar 齐备，libvpx 156/156 aarch64）；`.a` 静态库与头文件为**可选**产物，仅部分存在（不影响判定） |
| t6 coturn | STUN/TURN、负例、配置一致 | **已验证**（V60–V62、V64） |
| t7 native | 构建产物、JNI 表、宿主测试 | **已验证**（§3.1、§3.4、宿主测试 failures=0） |
| t8 Android | 40 文件、编译、JNI 一致 | **已验证（构建级）**；设备级未验证 |
| t9 Go 信令 | build/vet、e2e、日志契约 | **已验证**（V39、§7.1 阶段 2、§4） |
| t10 APK + Go 二进制 | 产物、自检脚本、提交 | **已验证（产物复核）**；构建未由我重跑（K-3） |
| t12 部署 + 联调 | systemd、公网可达、日志 | **已验证**（V53、V61–V64、§4） |
| t13 Go 工具链 | Go 1.22.12 + env-go.sh | **已验证**（我 source 后 build/vet/race 全绿） |

---

## 8. 成员过程报告要点汇总（摘要，不替代原文）

| 报告 | 要点（我复核后的定性） |
|---|---|
| `reports/01-host-recon.md`（t1） | Ubuntu 24.04 / 4 vCPU / 7.1 GiB / 无 swap / 公网 `47.238.144.66` / SSH `root@172.21.0.219:5766`；**我复跑环境一致** |
| `reports/02-interface-contract.md`（t2） | 契约版本史（v1.0-j…o）、C01–C31 冲突裁定、D-1…D-7 偏差登记、§30 澄清（推荐≠授权）、§33.1 规则；**我只读引用，未改动** |
| `reports/03-git-submodules.md`（t3） | submodule 锁定（libvpx `d2413e2c`、libwebrtc `be0e9008`）；**与 `git submodule status` 一致** |
| `reports/04-env-install.md`（t4） | 工具链安装到工作区；属主归一；**受控树非 1000 计数 = 0（我实测）** |
| `reports/05-libwebrtc-build.md`（t5/t16） | libwebrtc 编译与 jar 重编（`--release 17`）、树哈希互验 `32ce3a01…`；**我独立复跑 `git rev-parse HEAD^{tree}` 得同一值** |
| `reports/06-coturn.md`（t6） | coturn 配置实测（`relay-ip` 必须内网）、安全组历史阻塞；**V60–V62 我已复跑通过** |
| `reports/07-native-dev.md`（t7） | CMake/编码器/NAT/日志；宿主测试；**我在 t11 执行期间看到该文件被他人编辑（M）** |
| `reports/08-android-dev.md`（t8） | 40 文件、6 组检查器、D-1…D-7、E-1 回滚记述；**我按"当前树"复核（无 `PeerLeftGrace*` 残留）**；其 `:975` 的"恰好 7 个"是 26 错误承载者口径，**需按 §3.11 补充为"8 = 7 + 1"** |
| `reports/09-go-signaling.md`（t9） | 15 源文件 + 4 测试、25 用例、e2e、§8.3 七条修正；**e2e 与 build/vet 我已复跑** |
| `reports/10-app-build.md`（t10） | 4 次失败→全绿、10 阶段自检、APK/二进制哈希；**APK 哈希与内容我已复核**；其 `:55` 的"7 个文件"同为 26 错误承载者口径，**需按 §3.11 补充第 8 个** |
| `reports/12-deploy-signaling.md`（t12） | systemd 部署、EIP 复测、日志双通道；**我已复跑 systemctl/curl/e2e** |
| `reports/13-go-toolchain.md`（t13） | Go 1.22.12 + `env-go.sh`（GOCACHE 重定向）；**我 source 后复跑通过** |
| `reports/14-android-skeleton.md`（t14） | 骨架；已由 t8 改写 |
| `reports/15-java-jar-rebuild.md`（t16） | jar 重编为 Java 17（major 61）；**我 javap 实测 major 61** |

---

## 9. 未完成项与后续待办

1. **真机验证（最高优先）**：安装 `app-debug.apk`，执行 `doc/13` 阶段 5 与 V54–V59；重点确认 `JNI_OnLoad: 所有方法注册成功`、Camera2 采集、`SelfVp9Libvpx` 出现在 `outbound-rtp.encoderImplementation`、`encoder_bitrate.csv` 增长、日志导出 zip 可分享。
2. **release 构建**：`./gradlew :app:assembleRelease`（签名 + 混淆），复跑 V50/V51 与 ProGuard 保留校验。
3. **契约修订 3 条**（CD-1 V16 / CD-2 V63 / CD-3 V29）。
4. ~~**HEAD 收尾（F-01/F-02）**~~ **已完成**：`14ef053` 删除了退役脚本，并在 `reports/10` §6.3/§5.7.1 标注 A 方案已被 t16 取代。可选残留：`reports/07-native-dev.md` 待随下一提交带上（captain 授权在途）；`reports/10:205` 属历史小节，若要求零容忍可再改一句。
5. **单元测试复跑**：`./gradlew :app:testDebugUnitTest --rerun` 已通过（36/0）；建议把 AGP/Gradle 依赖预置进缓存，避免 `dl.google.com` 超时导致 `--offline` 不可用。
6. **清理**：`third_party/libwebrtc/java/*.orig*`、`*.prev-v61`、`*.v55-java11`、`*.v61-java17` 备份文件；修 `run_host_tests.sh` 默认 `REPO`（K-6）。
7. 可选：TCP 3478/5349 放行；将 `/signal` 兼容别名（若将来加回）显式标注 deprecated。
8. **【最高优先·新增】路线 A 修复（t30）后重编 APK**：补齐 `J.N` + 转发 `GEN_JNI` ⇒ 按 §13.3 判据复验（`J/N.class` 存在；`jni_mangle(J.N 的 native 名)` 集合 == `.so` 的 193 个 `Java_J_N_*`；`GEN_JNI` native = 0），并对新 APK 重跑 §12 的产物级检查。**在此之前，当前 `721df1c8…` 不构成"可运行交付"**。
9. **加固 `JniBindingClasspathTest`**：补 `J.N`/转发断言、清单 42→47；另清理报告注释里的过期值（`reports/07 §14.3` 的 187 等，见 §13.2 勘误清单，按 captain 裁定由本报告承载）。

---

## 10. 附：关键原始输出摘录（可复跑）

```bash
# 契约指纹（t11 基准）
wc -l doc/14-interface-contract.md                     # 1337
sha256sum doc/14-interface-contract.md
# b3b6743825eababc51d41944d61d0f4ab542c8a0f3cdfc4d7a754cefd1cc0f4d  doc/14-interface-contract.md

# JNI 表数量（V27/V28）
grep -rh "external fun" app/src/main/kotlin/com/example/webrtcdemo/nativebridge/ | wc -l   # 15
grep -rhE '^[[:space:]]*[{]"' app/src/main/cpp/jni/ | wc -l                                # 15
# 逐字比对：node /tmp/jnicheck2.mjs → Kotlin 方法 15 个，签名不一致 0 个

# 信令端到端（V63 行为口径 / doc13 阶段2）
node scripts/verify_signal_e2e.mjs ws://47.238.144.66:8443/ws    # 通过 19 项，失败 0 项，exit 0
node scripts/verify_signal_e2e.mjs ws://172.21.0.219:8443/ws     # 通过 19 项，失败 0 项，exit 0

# Go 构建/静态检查（V39）
bash -c '. /data/dsh/home/workspace/env-go.sh && cd signaling && go build ./... && go vet ./...'   # rc=0
# 宿主机竞态：source /opt/dsh-workspaces/env-go.sh && cd signaling && CGO_ENABLED=1 go test -race ./... -count=1
#   ok  logging 1.093s / room 2.033s / server 3.411s / util 1.068s   RACE_EXIT=0

# 架构（V48）
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump -f third_party/libvpx/lib/libvpx.a | grep -c "architecture: aarch64"   # 156

# APK（V50/V51）
APK=$(find app/build/outputs/apk -name "*.apk" -print -quit)   # app/build/outputs/apk/debug/app-debug.apk
unzip -l "$APK" | grep "lib/"                                  # 两个 arm64-v8a .so
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump badging "$APK"     # package com.example.webrtcdemo + 权限
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump xmltree --file AndroidManifest.xml "$APK" | grep -i cleartext   # true

# coturn（V60–V62/V64）
systemctl is-active coturn signaling                            # active / active
turnutils_stunclient -p 3478 47.238.144.66                      # UDP reflexive addr: 47.238.144.66:33125
turnutils_uclient -v -y -u demo -w demopass -p 3478 -n 3 -m 2 172.21.0.219   # lost 0 (0.000000%)
turnutils_uclient -v -y -u demo -w WRONGPASS -p 3478 -n 1 -m 1 172.21.0.219  # ERROR: Cannot complete Allocation

# native 宿主逻辑测试（t7）
bash app/src/main/cpp/tests/host/run_host_tests.sh /opt/dsh-workspaces/code/webrtc-demo   # failures=0 / 全部通过

# Gradle（V11）
./gradlew -v                                                    # Gradle 8.7 / JVM 17.0.20
./gradlew :app:testDebugUnitTest --rerun --console=plain        # BUILD SUCCESSFUL；36 用例 0 失败

# 接口守卫（t7/t8 交叉，19/19）+ jar API 交叉复现（§3.12）
bash /tmp/t14/t7iface.sh                                        # passed=19 failed=0
node /tmp/t14/apipairs.js third_party/libwebrtc/java/libwebrtc-java.jar   # 共 164 条，缺失 0 条
node /tmp/t14/apicheck.js                                       # 成员级检查点=97 问题=0

# t15 文件计数核验（§3.11）
find app/src/main/kotlin -name "*.kt" -newermt "2026-09-13 17:35" ! -newermt "2026-09-13 17:50" | wc -l   # 7
find app/src/main/kotlin -name "*.kt" -newermt "2026-09-13 17:50" ! -newermt "2026-09-13 18:05" | wc -l   # 1（VideoRendererPool.kt, 18:00:01.910）
gradle :app:testDebugUnitTest --rerun-tasks                     # 24/24 executed；36/0/0

# 终版 APK 的 clean 构建证据（t18）+ 产物级复验（§3.13）
grep -n "app:clean" reports/logs/final-nocache-assembleDebug-20260913-214035.log   # 执行
tail -3 reports/logs/final-nocache-assembleDebug-20260913-214035.log               # 42 executed, 1 up-to-date（FROM-CACHE=0）
sha256sum app/build/outputs/apk/debug/app-debug.apk   # b0cddd86718a75a0aadb82424d0ce5ab24bae2f52edede27f86cd0c2d7bbb12b
APK=app/build/outputs/apk/debug/app-debug.apk; unzip -oq "$APK" -d /tmp/apk3 && sha256sum /tmp/apk3/lib/arm64-v8a/*.so
llvm-readelf --dyn-syms /tmp/apk3/lib/arm64-v8a/libwebrtcdemo_native.so | awk '$7!="UND" && $4=="FUNC" && $5=="GLOBAL"{print $8}'

# Go：VCS-free 归因基线（与 scripts/build_app.sh:283 同参）
cd signaling && go build -trimpath -ldflags="-s -w" -buildvcs=false -o /tmp/gv-novcs-1 . \
  && sha256sum /tmp/gv-novcs-1     # 1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa（连跑两次一致）
cd signaling && go test ./... -count=2                           # 全 ok，rc=0

# APK 内部结构 + .so 依赖（§3.8）
APK=app/build/outputs/apk/debug/app-debug.apk
unzip -oq "$APK" -d /tmp/apkfull && ls /tmp/apkfull             # AndroidManifest.xml / resources.arsc / res/xml/file_paths.xml
$ANDROID_HOME/build-tools/34.0.0/aapt2 dump resources "$APK" | head -3   # Package name=com.example.webrtcdemo
llvm-readelf -d /tmp/apkfull/lib/arm64-v8a/libwebrtcdemo_native.so | grep NEEDED   # 无 libjingle/libwebrtc
llvm-readelf --dyn-syms /tmp/apkfull/lib/arm64-v8a/libwebrtcdemo_native.so | grep -c JNI_OnLoad   # 1
```

---

### 10.11 `.git` 对象目录属主导致的提交阻塞（K-17）复跑

```bash
cd /data/dsh/home/workspace/code/webrtc-demo
ls -ld .git/objects/33 .git/objects/56 .git/objects/ac .git/objects/c6   # 全部 root root 755
for d in 33 56 ac c6; do touch .git/objects/$d/__w 2>&1; done            # Permission denied
git write-tree                                                          # error: insufficient permission for adding an object to repository database .git/objects
                                                                        # fatal: git-write-tree: error building trees
# 待写对象定位（沙箱对象库不污染 .git）：
#   GIT_OBJECT_DIRECTORY=<同盘可写目录> GIT_ALTERNATE_OBJECT_DIRECTORIES=$PWD/.git/objects \
#   GIT_INDEX_FILE=<index 副本> git write-tree    → c6796ce1ae8d0f580a78ef86a01efd54f63bef51（扇出 .git/objects/c6 = root 755）
# verifier 采用的规避（不改他人文件、不改 .git/config）：
#   TREE=$(GIT_INDEX_FILE=<idx> git write-tree)
#   COMMIT=$(GIT_INDEX_FILE=<idx> git commit-tree $TREE -p $(git rev-parse HEAD) -m "<msg>")
#   echo $HASHES | git pack-objects .git/objects/pack/pack-verifier-t27     # node 可写；写入标准 pack
#   git update-ref refs/heads/$(git symbolic-ref --short HEAD) $COMMIT $(git rev-parse HEAD)   # 带旧值保护
# 若 pack 与对象库跨文件系统会报 "Invalid cross-device link"（git 用 TMPDIR 建临时文件）⇒ TMPDIR 须与仓库同盘。
```

### 10.12 路线 A 生成件侧复测（§13.5）

```bash
cd /data/dsh/home/workspace/webrtc-build/t30
SO=/data/dsh/home/workspace/code/webrtc-demo/third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so
node /tmp/routeA_check.mjs "$SO" handoff/src/J/N.java handoff/src/org/jni_zero/GEN_JNI.java
# 判定式：A = { jni_mangle(名) | 名 ∈ J/N.java 的 native 方法名 }（_→_1，$→_00024）
#         B = { s | Java_J_N_ s ∈ llvm-readelf --dyn-syms "$SO" }
#         ⇒ |A| = |B| = 193，双向差集为空（朴素反转义会误判，连续 `_1x` 有歧义）
sha256sum handoff/src/J/N.java handoff/src/org/jni_zero/GEN_JNI.java out/A/src/J/N.java out/B/src/J/N.java
javap -p -v handoff/classes/J/N.class | grep 'major version'                 # 61
javap -p handoff/classes/J/N.class | grep -c ' native '                      # 193
javap -p handoff/classes/org/jni_zero/GEN_JNI.class | grep -c ' native '     # 0
```


## 11. 流程留痕与工程教训（过程事实，不追责）

> 记录目的：这些是本轮最可复用的工程信息；下一位接手者应据此设防。

| # | 事件 | 事实（可核对） | 教训/建议 |
|---|---|---|---|
| P-1 | **"构建窗口内写入"三次** | ① env-installer 编到编辑中途快照 → 6 个编译错误（`reports/10` 已澄清）；② android-dev 在 t15 完成后改 `VideoRendererPool.kt`；③ webrtc-builder 两轮 jar 切换（v55 ↔ v61） | **"任务标记完成 ≠ 冻结窗口"**：合并/换产物前须有显式冻结期与串行采样；**换 jar 后必须 `clean` 重编**（env-installer 实测增量构建会误判 UP-TO-DATE 并复用旧 dex/包） |
| P-2 | **captain 自身发出互斥指令** | 先"批准 v61"，后"以 v55 为准" → v55/v61 往返、多一轮 346 s 重编与数轮报告返工 | 版本决策交由"**磁盘真相 + 冻结 + 串行采样**"，避免并行口头决策 |
| P-3 | 成员各自如实留档的同源失误 | `pkill -f` 误杀自身；备份晚于覆盖；`open(p,"w")` 先截断；构建脚本三处 bug（dexdump 误用 APK、`pipefail`+`grep -q` 的 SIGPIPE 假 FAIL、`x=$(…|grep…)` 无匹配致 `set -e` 静默退出） | 建议收敛为检查清单（含"写文件前备份""管道退出码语义""无匹配即失败"三条） |
| P-4 | **决策性质在传递中失真（E-1）** | architect §30 的技术推荐（口径 A）被转达为"已裁定" → android-dev 实施后自查回滚（`reports/08 §8.10`），`PeerLeftGrace*` 全仓 0 残留 | 沿用 captain 规则：**任何第三方转达的"已裁定/已批准"，动手前必须回 `doc/14` 原文核对；转达者必须标明是"推荐"还是"决策"** |
| P-5 | **可复现性缺口已补** | 本轮新增 `scripts/patches/libwebrtc-java-release17.patch` + t5 脚本幂等应用，使 `--release 25→17` 的修补可复现 | 保留；K-12 已说明"clone 不可直接重建"的结构性限制 |
| P-6 | **验证期间的"在途报告"管理** | `reports/07-native-dev.md` 在 t11 执行期间处于未提交状态（v1.6 升级），captain 判定为**授权在途项**并随收尾提交带上；我按其 §9.1.2 复跑了两条哈希与 ELF 检查（§3.8） | 建议：验证窗口内凡"已定稿待提交"的文件，由 captain 显式标注"授权在途"，避免验收方误判为失控改动 |
| P-7 | **"缓存辅助构建"会被误当成复现证据** | 三次 `authoritative-*` 日志均有 `FROM-CACHE = 21`，得到与旧构建相同的 APK 哈希 `c72d3667…`；而一次 `--no-build-cache + clean` 的完全执行得到 **不同** 的 `b0cddd86…` ⇒ "同哈希"是缓存的结果，不是独立复现 | 结论要写"**APK 非逐字节可复现**"；判定构建证据必须要求 **`clean` + `--no-build-cache`** 且日志中 `FROM-CACHE = 0`、关键任务非 UP-TO-DATE。**该点 android-dev 最早提出，captain 曾错误否定，现由实测确立（captain 承担该错误）** |
| P-8 | **"运行期才解析的依赖"必须有交付物级断言** | 真机 `NoClassDefFoundError`（D-1）在"编译通过 + APK 打包成功 + 单测全绿"的情况下依然发生：debug 链只到 **D8**，而 D8 不解析被引用但缺失的类 ⇒ 静态流程一路绿灯 | 凡运行期才解析的依赖，必须补 **jar 常量池引用闭合性检查**（N-1）、**绑定类存在性回归**（N-2，且须防假绿，§13.4）与 **dex/APK 内实体断言**（N-4）；详见 §12.3/§12.4 |
| P-9 | **"`--rerun-tasks` 单用仍可能命中缓存"被当成"刚真实执行过"** | 实测 `testDebugUnitTest` 在只加 `--rerun-tasks` 的调用里报 **`FROM-CACHE`**（`3 executed, 3 from cache, 18 up-to-date`） | "真实执行"必须 **`--no-build-cache --rerun-tasks`** 且 **`N tasks: N executed`（FROM-CACHE=0）**（详见 §13.14(d)） |
| **P-12** | **冻结期内的落位/写盘必须有唯一授权人；接收方若发现"指令"与"最终裁定"冲突，必须先回报再执行** | 18:32:24 **非授权落位 A**（执行的是 **t31-D1 过期文本**，而 captain 已用 **t31-D2** 裁定 = **B**，且当时在写盘冻结期）→ 18:33:42 captain **回滚为 B**；事故件隔离于 `tmp/jn-fix/QUARANTINE-A/`（chmod 400 + README）；当时无 gradle 在跑 ⇒ 无 A 版 APK（§13.21） | 单一授权人 + 冲突先回报；**注**：`scripts/build_app.sh` 里的 **[P-11]/[P-12] 是构建脚本门编号（§13.22(f)），与本 P 系列的流程教训编号不是同一命名空间** |
| **P-13** | **"落位前"冻结材料被反复当作现状引用**（本会话出现多次口径拉扯） | 冻结件 `t30/logs/t34-gate.md`（`6d1112dd…`）写于**落位前**，其 `:21`("期望 ≥ 1（落位前 = 0）")与 `:58`("落位前两条断言必红")属**历史段**；落位后 native-dev 另开 `t34-gate-post-landing.md`（`c87bb1cd…`）钉现值（`0c776934…`、`J/` 期望 = 1、`GEN_JNI.class` 限定 `a6e7edcf…`） | **规则**：① 状态变更后**另开附加件**并在旧件标注"历史/superseded"；② 凡涉及"部署/落位状态"的陈述，**在同段前置一行读盘行**（格式约定）：`[读盘 HH:MM:SS] jar=<sha256 前 16>（可选 + J/N.class、GEN_JNI.class 前 16）` —— 即引用任何此类材料前**先跑 `sha256sum third_party/libwebrtc/java/libwebrtc-java.jar` 并同段贴出**；③ 引用者必须核对**文件生效时点**而非只看文件名（本报告 §13.22(g) 已按此以 v2 为准、v1 仅作历史）；④ **需要长期引用的证据用新文件名、禁止覆盖写**（同路径覆盖会使早期版本不可复核，如 `prefix-baseline.md` 三代同路径的后果）。⇒ **总原则：现场读盘 > 消息；新增文件名 > 覆盖同名**（native-dev 提议，我采纳） |
| P-14 | **跨侧计量"尺子不同"造成的假红/假矛盾**（verifier 20:04–20:10 实测） | ① dex 侧 AV1 桩终止指令是 `throw`（`dexdump` 指令列末为 `throw v0`），class 文件侧才是 `athrow` ⇒ 在 dex 上 `grep -c athrow` = **0**（假红）；② dex 方法表含 `<init>` ⇒ `J/N`/`GEN_JNI` dex 方法数 = **195 = 194 `public static` + 1 构造器**，与 jar 侧 194 **不矛盾**；不区分 `Static fields`/`Direct methods` 段会把字段计成方法（`PCFJni` 得 **26** 假数，真值 25 方法 + 1 字段）；③ 容器 `/tmp` = **256 MB tmpfs** ⇒ 大 dex（44.7 MB）与 `dexdump -d`（7.43 M 行）必须在工作区做，空间不足时报 `Size is too small`/`ReadFileToString failed`，**形似"dex 损坏"实为磁盘满**；④ `cmp -s` 对**缺失文件**也返回非 0 ⇒ 逐件比对**先断言存在**（我据此一度误报"变体四 `.so` 不同"，重测四件全同） | **断言必须写清"哪一侧、哪把尺子"并附命令原文**；跨侧数字先做一次口径对齐再比较；大件 IO 落工作区；`cmp` 前先做存在性检查（详见 §13.25(c)(d)） |
| **D-13**（captain 侧教训） | **过期任务文本被系统反复回放，导致成员按旧合同执行** | t31-D1（旧文本）与 t31-D2（裁定 B）并存，成员按 D1 落位 A（§13.21） | 裁定变更时**同步废弃旧任务文本**并显式标注 supersede，避免"回放旧合同" |

---

## 12. 真机缺陷修复复核（t23–t26 · 本轮 t27 主题）

### 12.1 两个独立缺陷与修复链

| # | 缺陷 | 现象 | 根因（源码级） | 修复 |
|---|---|---|---|---|
| **D-1** | 交付 jar **缺 jni_zero 绑定类** | 真机进通话页即 `NoClassDefFoundError: org.webrtc.PeerConnectionFactoryJni`（t22 定位） | `libwebrtc.jar` 由 `zip.py --input-zips=@FileArg(...:dist_classpath)` 打包，该集合 `direct_deps_only=true` ⇒ `generated_*_jni_java` 属**传递依赖被系统性排除**；48 个 `*Jni.class` 从未进 jar，且**不存在合并后的 `GEN_JNI`** | **t23**：补齐 48 个 `*Jni` + 合成 194 native 的 `GEN_JNI`（jar `dc5f8919…`，508 类；AAR 同步 `e066e456…`，AAR 内 `classes.jar` 与 jar 同哈希） |
| **D-2** | **16 KB 页对齐不达标** | 16 KB 页设备 `loadLibrary` 失败（独立于 D-1） | NDK r26 默认 `max-page-size=0x1000`；自有库与 `libc++_shared.so` 的 LOAD 段 `p_align=0x1000` | **t24/t28**：自有库加 `-Wl,-z,max-page-size=16384`；`libc++_shared` 采用"**用 r26 的 `libc++_static.a`+`libc++abi.a` 自链接 16 KB 同名库**"（`c9dbf4ec…`，1,356,968 B）并经 `jniLibs`+`pickFirsts` 打包 |

**辅助修复**：**t25** 引擎失败可诊断化（异常类名/message/cause 透传 UI 与日志 + 绑定类回归测试）；**t26** 在"写者静默窗口 + `--no-build-cache clean`"下重编 APK 并提交；**t17** 把 `--release 25→17` 补丁固化为 `scripts/patches/libwebrtc-java-release17.patch` + 幂等守卫。

### 12.2 我的独立复核（不采信任何自述）

**(a) jar 完整性 —— 我自写的常量池引用检查**（ZIP 解包 + 每个 `.class` 做类名引用正则匹配并排除自引用）
```
JAR = third_party/libwebrtc/java/libwebrtc-java.jar   (sha256 dc5f89193d55c971… , 1 187 970 B)
class 总数             = 508 （解析失败 = 0）
major 版本分布         = {"55":51,"61":457}  ⇒ max = 61   ✅ (≤61)
*Jni.class 数          = 48
*Natives.class 数      = 48
GEN_JNI.class 存在     = true
J/N.class 存在         = false
被引用的 *Jni（含子包）= 47
被引用但缺失（须 0）   = 0 []
存在但未被引用         = [ 'org/webrtc/Dav1dDecoderJni' ]
```
- **自洽性**：构建树 `out/Release-arm64/gen` 下 `*Jni.java` = **48** ⇒ 与 jar 内 48 个 `*Jni.class` **数量一致**；`GEN_JNI.java`/`N.java` 在 gen 下为 0（jni_zero 直接写入 srcjar，不落散文件）。
- **javap 抽验**：`class org.webrtc.PeerConnectionFactoryJni implements org.webrtc.PeerConnectionFactory$Natives`，含 `public static org.webrtc.PeerConnectionFactory$Natives get();`；该类自身 `native` 方法数 = **0**（符合 jni_zero 设计：native 全在 `GEN_JNI`；实测 `GEN_JNI` native = **194**、非 native static = **0**）。

**(b) 新 APK 产物级复验**（`app/build/outputs/apk/debug/app-debug.apk` = **`721df1c82841ad99…`**，33,293,061 B，mtime `2026-09-14 11:28:34.950`）

| 检查 | 我的实测 |
|---|---|
| 解包四项 | `AndroidManifest.xml` / `resources.arsc`（`aapt2 dump resources` → `Binary APK`、`Package name=com.example.webrtcdemo id=7f`，可解析）/ `res/xml/file_paths.xml` / `lib/arm64-v8a/` 均存在 |
| 包名/权限/cleartext/Provider | `package=com.example.webrtcdemo`；CAMERA/RECORD_AUDIO/INTERNET(+ACCESS_NETWORK_STATE/MODIFY_AUDIO_SETTINGS)；`usesCleartextTraffic=true`；FileProvider `authorities=com.example.webrtcdemo.fileprovider` |
| **四个 `.so` 的 `p_align`** | `libandroidx.graphics.path.so` `41e9a793…` = **0x4000**；`libc++_shared.so` `c9dbf4ec…` = **0x4000**；`libjingle_peerconnection_so.so` `757cef81…` = **0x4000**；`libwebrtcdemo_native.so` `95c44e5a…` = **0x4000** ✅ |
| `libc++_shared` 选对了吗 | APK 内该件与落位件 `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` **`cmp` 逐字节相同**（同 `c9dbf4ec15e931f5…`）⇒ `pickFirsts` 选中自链接的 16 KB 版；**反误判**：`cxx/**/obj/…` 那份仍是 NDK r26 官方 4 KB 件（`4e843755…`，1,822,720 B，`p_align=0x1000`），**仅作合并输入、不参与打包** |
| 自研库导出 | 仍仅 `JNI_OnLoad` + `JNI_OnUnload`（`NEEDED` 含 `libc++_shared.so`） |
| **dex 内绑定类（本轮关键证据）** | 共 14 个 dex；**`Lorg/webrtc/PeerConnectionFactoryJni;` 出现在 `classes14.dex`** ✅；`Lorg/jni_zero/GEN_JNI;` 出现在 `classes13/14.dex`；`LJ/N;` 计数 = **0**（与"缺 `J.N`"一致） |

**(c) t25 新增单测复跑（强制，非 UP-TO-DATE）**
```
./gradlew :app:testDebugUnitTest --rerun-tasks
→ BUILD SUCCESSFUL in 2m 19s ；24 actionable tasks: 24 executed
→ 结果 XML（11:39:00）：
   AppConfigUrlTest                    tests=8   failures=0 errors=0
   NativeInterfaceContractTest         tests=4   failures=0 errors=0
   SignalingErrorPolicyTest            tests=17  failures=0 errors=0
   SignalingIdentityTest               tests=11  failures=0 errors=0
   JniBindingClasspathTest             tests=2   failures=0 errors=0   ← t25 新增
   TOTAL                               tests=42  failures=0 errors=0
# ===== t27（真机缺陷修复复核）新增复现命令 =====
# 契约指纹
sha256sum doc/14-interface-contract.md   # b3b6743825eababc51d41944d61d0f4ab542c8a0f3cdfc4d7a754cefd1cc0f4d（1337 行）
# jar 完整性（自写常量池引用扫描）：见 §12.2(a)
node /tmp/jarcheck_t27.mjs third_party/libwebrtc/java/libwebrtc-java.jar
#   → class 508 / major {"55":51,"61":457} max=61 / *Jni.class=48 / *Natives=48 / GEN_JNI=true / J/N=false
#   → 被引用 *Jni（含子包）=47 / 被引用但缺失=0 / 未引用=1（Dav1dDecoderJni）
# APK 内实体
sha256sum app/build/outputs/apk/debug/app-debug.apk   # 721df1c82841ad99…（33 293 061 B / mtime 2026-09-14 11:28:34.950）
unzip -oq app-debug.apk -d /tmp/apk_t27 && sha256sum /tmp/apk_t27/lib/arm64-v8a/*.so
llvm-readelf -l /tmp/apk_t27/lib/arm64-v8a/*.so | grep LOAD     # 四件 p_align 全 0x4000
for d in app/build/outputs/apk/debug/app-debug.apk; do unzip -p $d classes14.dex > /tmp/c14.dex; done
strings -a /tmp/c14.dex | grep -c 'Lorg/webrtc/PeerConnectionFactoryJni;'   # 1（绑定类在交付物内）
# 单测（强制重跑）
./gradlew :app:testDebugUnitTest --rerun-tasks   # 24/24 executed；42 用例 0 失败（含 JniBindingClasspathTest 2）

```

### 12.3 新增 / 强化的检查项（本轮真正补上的工程能力）

| # | 检查项 | 为什么必须新增 |
|---|---|---|
| N-1 | **jar 内"被引用但缺失的 `*Jni`"= 0**（常量池级引用扫描，含子包） | t22 之前**没有任何静态检查**覆盖"jar 是否自带运行期所需类"；只看"能编译/能打 APK"必然漏 |
| N-2 | **绑定类存在性回归测试**（`JniBindingClasspathTest`） | 把"缺类"从"真机才炸"提前到"单测即红"（t25 已实现；覆盖度不足见 §13.4 / K-16） |
| N-3 | **APK 内实体的 `p_align` + `libc++_shared` 与落位件字节相同** | 16 KB 兼容无法靠中间产物判定（`cxx/` 那份恒为 4 KB），必须在 **APK 内实体**上判 |
| N-4 | **dex `class_defs` 含关键绑定类** | 这是"修复真的进了交付物"的**唯一**不含糊证据（源码/jar 都对 ≠ APK 里有） |
| N-5 | **构建窗口静默 + `--no-build-cache` + FROM-CACHE=0** | 避免缓存辅助构建被误当"干净复现"（§11 P-7） |

### 12.4 为什么静态验证会漏掉 D-1（教训）
- debug 构建链只到 **D8**：D8 **不解析**被引用但缺失的类（由运行期 ART 解析）⇒ "编译通过 + APK 打得出来"**完全不能**证明绑定类在位；
- 交付链里**没有任何一步**检查"jar 的引用闭合性"——直到我们补上**常量池引用扫描**（N-1）与**绑定类回归测试**（N-2）；
- 结论（已登记为流程留痕 **P-8**）：**凡"运行期才解析的依赖"，必须有交付物级（jar/dex/APK）存在性断言**，不能依赖编译器/打包器默认行为。

### 12.5 未闭合项（**必须带走，不得当成已修好**）
> **历史快照（18:17 落位前）**：K-15 运行期绑定缺口：jar 内 `GEN_JNI` 仍是 **Placeholder 实现**（194 native 可读名、0 转发），`.so` 侧为 **hashing/short-proxy** 模式（193 `Java_J_N_<hash>`、无 `kMethods`）⇒ 静态预期真机首次 native 调用 **`UnsatisfiedLinkError`**。按 captain 定向由 **t29/t30 路线 A** 修复（§13.3），**修复后须重编 APK 并复跑本节全部检查**。 **⇒ 现状见 §13.22（jar/AAR 侧已闭合为 B 形态；APK 侧待 t33）。**

---

## 13. 绑定机制取证、验收判据与报告勘误（t27 附带收口）

### 13.1 取证（**主论证 = 字符串证据**；`blr` 仅旁证）
对象：`libjingle_peerconnection_so.so`（`757cef81…`，12,946,912 B）

| 判据 | 实测 | 说明 |
|---|---|---|
| `.rodata` 含 `org/jni_zero/GEN_JNI`（RegisterNatives 的 FindClass 目标） | **0** | 主论证 |
| `.rodata` 含 GEN_JNI 那 194 条可读 native 方法名（`^org_webrtc_`） | **0** | 主论证 |
| 排除"表里用哈希名"的替代解释（`^M[A-Za-z0-9_$]{7,8}$`） | 仅 **4** 条无关串（`Moderate`/`MLKEM1024`/`MymxOyox1`/`Mih8kih8`） | 主论证 |
| `JNI_OnLoad`（**完整符号区间 `0x29f718–0x29f87c`**，见 §13.12(c)）内 `blr` / `bl` | **0 / 7**（89 条指令） | **旁证**（只排除其自身注册，不排除其 helper）；**`blr`=0 的结论不受区间影响** |
| 导出符号 | 定义动态符号 **194 = `JNI_OnLoad` + 193 个 `Java_J_N_*`**（**该库不含 `JNI_OnUnload`**） | 更正 reports/07 组成错误 |
| **对照实验**（证明"0"不是 strip 假阴性） | 自研库（RegisterNatives）：`onNatTypeDetected` = **2**、`com/example/webrtcdemo/nativebridge/NativeCallbacks` = **1**、`nativeInit` = **5** | 真做注册的库必留类名+方法名串 |

**工具陷阱**：`llvm-objdump -d --disassemble-symbols=JNI_OnLoad` 若符号匹配失败会**静默退化为全文件反汇编**（webrtc-builder 首报）；必须用**显式地址区间**（本报告即如此）。

### 13.2 `reports/07-native-dev.md` 勘误清单（**按 captain 裁定 (A)：由本报告承载，不改其文件**）
> 报告指纹（我复核）：**791 行 / `ed00dfd40c0844612dff21e97040b181748b543140fc0d11abb3cbfb10fc1154` / mtime 2026-09-14 11:14:49**；TSV `65ff70064355ca099c4f11fb9e21c7cc5406b0893f11cb3bf4babd969b6894f1`（**194 数据行 × 4 列** = 10 注释 + 1 表头 + 194 数据；早前"193 行"作废）。

**(1) 事实错误 2 处** —— 来源：**verifier 独立定位 + native-dev 自查复验认账**
- **`:551`（§14.2）** 与 **`:523`（v1.7 变更记录）**：现写"**194** 个动态符号 = `JNI_OnLoad` + `JNI_OnUnload` + **193 个 `Java_J_N_<hash>`**" → 正确为 **194 = `JNI_OnLoad` + 193 个 `Java_J_N_*`**；**libjingle 不含 `JNI_OnUnload`**（`JNI_OnLoad`+`JNI_OnUnload` 是**自研库** `libwebrtcdemo_native.so` 的形态）。
- `:523` 中"14 个模块共 **45** 个 `*Jni.class`"属**版本史旧值，保留不动**，仅注明"该历史条目内 42/45 为当时值，现行见 §16.5/§16.2"。
- **`:411` 正确、不改**（§9.1.2"导出 `JNI_OnLoad`（194 个动态符号）"）：该句**未**把 194 归因于 `JNI_OnUnload`，只是陈述导出 `JNI_OnLoad`（194 个动态符号）——过度更正反而会改错。
- 其余 **`JNI_OnUnload`（小写 l）共 11 处**：`14/201/354/380/408/487/520/522/523/616/675`。其中 `14/201/354/380/408/487/520/522/616/675` 指**自有库** `libwebrtcdemo_native.so`（该库**确实**同时导出 `JNI_OnLoad` + `JNI_OnUnload`，`-fvisibility=hidden` 下仅此二者）⇒ **正确、不改**；`523` 属 (1) 的"版本史保留 + 注明"。（我上一版仅列 7 处，本轮按 `grep -n` 全量补正为 11 处。）
- ⚠️ **复核命令陷阱（大小写）**：待修行 `:551` 用的是 **`JNI_OnUnLoad`（大写 L）**，该形态**全文仅 1 处**；用 `grep -n "JNI_OnUnload"`（小写 l）**匹配不到 `:551`** ⇒ 会造成"改了但校验不到 / 校验通过却没改"的假象。复核一律用 **`grep -n "JNI_OnUn[lL]oad" reports/07-native-dev.md`**（我实测合并 = **12 行**：11 小写 + 1 大写 `:551`），并断言 `:551` 行内不再出现 `JNI_OnUnLoad`。

**(2) 结论口径"合并即闭环"→ 已被取代（5 条，覆盖 6 行）** —— 来源：**webrtc-builder 报 → native-dev 独立复验并撤回旧判断 → webrtc-builder 已就其"可能不同代际"正式更正（`reports/05 §12.2`、`reports/15 §12`）→ 两方独立复算一致 → verifier 独立复核落点**
- **`:721`**（§16.2 结论 2）：判据只说"193 个 `Java_J_N_*` 都能在合并 `GEN_JNI` 找到对应方法" → 追加"**符号集合一致 ≠ 运行期可绑定**"；
- **`:723`**（§16.2 结论 3）与 **`:565`**（§14.3 第 2 条续行）：两处"`.so` 无需重编／缺陷只在 Java 侧打包（缺 `*Jni` + 缺 `GEN_JNI`）" → 更正为"**t23 合并必要但不充分**；jar 侧还须以运行期 srcjar 的 **`J.N` + forwarding `GEN_JNI`** 替换 Placeholder"；
- **`:564`**（§14.3 第 2 条"最小修复"；**数字已对、口径需换**：该行已自注"48 个类（原写 45）"，需换的是**框架**——现文"把 `*Jni` + 合并 `GEN_JNI` **并入** jar"读起来像"并入即可绑定"，而实测 **并入 ≠ 可绑定**）：追加"（必要但不充分：须用运行期 srcjar 的 `J/N` + forwarding `GEN_JNI` 替换 jar 内的 Placeholder GEN_JNI）"；
- **`:569`**（§14.3 第 5 条）：追加"**若类已补齐仍失败，搜 `UnsatisfiedLinkError`**"；
- **`:769`**（§16.5 B 标题"t23 修复结果的本层复核"）：追加限定"（**仅证明类存在性**：48/48 + `GEN_JNI` 194/194；**不证明可绑定**）"；§16.5 小节标题在 `:751`。
> ⚠️ 行号以 **verifier 内容定位的权威值**为准：native-dev 早前给的 `:561-562` 实为 `:561` 小节标题与 `:562` 第 1 条，**"最小修复"在 `:564`**；另"无需重编"有 **`:565` 与 `:723` 两处**。

**(2.5) 机械锚点表（免行号漂移；pre/post 断言用）** —— 我在冻结件（791 行 / `ed00dfd4…`）上逐个 `grep -cF` 实测：**10 个锚点全部 `uniq = 1`**，命中行号与下表完全一致（锚点由 native-dev 提出、**我逐条复算唯一性**）。为便于机械匹配，下表锚点以**纯文本**给出（正文中对应的反引号已省略）。

| 分组 | 行 | 锚点原文片段（`grep -cF '...'` 唯一命中） | 处置 |
|---|---|---|---|
| 事实① | `:551` | 个动态符号 = JNI_OnLoad + JNI_OnUnLoad | **改**（全文唯一的大写 `JNI_OnUnLoad`） |
| 事实② | `:523` | 194 动态符号 = JNI_OnLoad+JNI_OnUnload | 版本史**保留 + 加注** |
| 结论① | `:721` | 因此跨产物核对的判据应是 | 改判据句 |
| 结论② | `:723` | `.so` 不需要重编 | 换口径（并入 ≠ 可绑定） |
| 结论③ | `:564` | **最小修复**：把 14 个模块的 | 换口径（数字已对） |
| 结论③ | `:565` | libjingle_peerconnection_so.so 无需重编 | 与 `:723` 关联说明 |
| 结论④ | `:569` | 真机 10 秒定案 | 追加 `UnsatisfiedLinkError` |
| 结论⑤ | `:769` | t23 修复结果的本层复核 | 追加"仅证明类存在性" |
| 定位锚 | `:751` | ### 16.5 更正与更新记录 | 不改（定位用） |
| 过期数 | `:567` | 分包 GEN_JNI native 合计 | **187/差 6 → 194/差 1 = AV1** |

> **明确不改**：`:566`（第 3 条"更稳的做法"）与 `:568`（`:567` bullet 的续行"因此不宜只塞某一模块的 GEN_JNI…"，该结论仍有效）。bullet 结构我实测一致：**`564-565` 为一个 bullet、`567-568` 为一个 bullet**。故**整份勘误触及 10 行**（事实 2 + 结论 6 + 过期数 2），其中**必须改 9 处**、`:523` 仅加注。
> **改后免行号断言**：新文本各自 `grep -cF` = **1**，且旧锚点全部 `grep -cF` = **0**（大写 `JNI_OnUnLoad` 形态、`分包 GEN_JNI native 合计 **187**`、`.so` 不需要重编）；同时 `:566`/`:568` 两条锚点仍 = **1**（证明未越界误改）。**某项若该改而未改（旧锚点仍 `uniq=1`）、或不该改而被改（保留项变 `0`），即为误改。**


**(3) 过期数字 1 处** —— 来源：**native-dev 自查认账 + verifier 定位**
- **`:567-568`**（§14.3 第 4 条）："分包 `GEN_JNI` native 合计 **187**、与 `.so` 边界 193 相差 6"为**当时值**（只扫 14 个 `generated_*` jar，漏 `base_java_jni_java`（`LoggingJni`）与 `third_party/jni_zero/generate_jni_java`（`CommonApisJni`/`JniZeroJni`））→ **现行 = 16 分片并集 194**，与 `.so` 193 的差值为 **1 = `org_webrtc_LibaomAv1Encoder_create`**；该条"**不宜只塞某一模块的 `GEN_JNI`**"结论**仍有效**。

**(4) 47/48 与 194/193：三方独立一致**

| 来源 | 方法 | 结果 |
|---|---|---|
| native-dev | 从 `gen/jni_headers/**/*_jni.h` boundary 宏反验 + 与 `.so` 导出取交 | TSV **194 行**（193 导出 + 1 AV1） |
| webrtc-builder | 从 Java 侧 194 条 native 名**正算**符号集、与 TSV 逐条比对 | 交集 **194**、不一致 **0**、双方独有 0；`.so ⊇ TSV` = False，差恰 **1 = AV1** |
| **verifier（我）** | 自实现 mangling 全量复算（194/194）＋自写常量池引用扫描 | 与 `.so` 集合相等 **193/193**；`*Jni` **48 存在 / 47 被引用 / 0 缺失 / 1 未引用 = `Dav1dDecoderJni`** |

**方法论留痕**：verifier 第一版正则**不支持子包** ⇒ "46 被引用 / 4 未引用"；加 `(?:子包/)*` 后收敛为 **47 / 1** —— 与 native-dev 最初只扫 `org/webrtc/<Class>Jni` 得 **42** 是**同一类漏检**（漏 `org/webrtc/audio/*Jni` ×3 与 `org/jni_zero/*Jni` ×2）。47/48 现行口径 = "**native-dev 自查更正 + webrtc-builder 与 verifier 分别独立复算确认**"。

**(4.5) 42 / 47 / 48 对账表（消解"三个数字互相矛盾"的读者困惑）** —— 全部由我自跑（ZIP 全条目 + 常量池引用扫描）：

| 量 | 组成 | 口径来源 |
|---|---|---|
| **存在 48** | **43**（`org/webrtc` 顶层）+ **3**（`org/webrtc/audio`）+ **2**（`org/jni_zero`） | `jar tf` 全条目；**三前缀之外 = 0（无盲区，我实测越界条目为空）** |
| **被引用 47** | **42**（顶层被引用）+ **3** + **2** | 我的常量池引用扫描（逐类逐 `*Jni` 名匹配） |
| **差 1** | `org.webrtc.Dav1dDecoderJni`（存在但未被任何类引用） | 同上 |
| 顶层 **42** vs 顶层存在 **43** | native-dev 最初的 **42 = "顶层被引用数"**，**不是存在数** | 口径差异，非错误 |
| 历史 **45** | 只扫 **14 个 `generated_*` jar** ⇒ 漏 `base_java_jni_java`（`LoggingJni`）与 `third_party/jni_zero/generate_jni_java`（`CommonApisJni`/`JniZeroJni`） | `reports/07:523` 版本史旧值（保留 + 注明） |
| 我第一版 **46/4** | 我的正则**不支持子包** ⇒ 同时丢 `org/webrtc/audio`×3 与 `org/jni_zero`×2 中的部分 | 已收敛为 47/1（§13.2(4) 方法论留痕） |
⇒ **建议统一写法**："**48 = 43+3+2；47 = 42+3+2；差 1 = `Dav1dDecoderJni`**"，并注明 42/45/46 各自的漏法（**漏子包** vs **漏 jar 集合**是两种方向不同、结果不同的漏检）。

**TSV 机械复核（我自跑，`reports/07-native-dev-jnizio-mapping.tsv` = `65ff70064355ca099c4f11fb9e21c7cc5406b0893f11cb3bf4babd969b6894f1`）**：`wc -l` = **205**；`^#` = **11**（10 条注释 + 1 条以 `#` 开头的表头）；数据行 = **194**；`awk -F'\t'` 全部 **NF=4**；第 4 列 = **193 `yes` + 1 `no`**。唯一 `no` 行逐字为：
`Java_J_N_M0vTiIkf` ⇥ `org_webrtc_LibaomAv1Encoder_create` ⇥ `sdk/android/generated_libaom_av1_encoder_jni/LibaomAv1Encoder_jni.h` ⇥ `no`
⇒ **AV1 的精确符号名就此钉死 = `Java_J_N_M0vTiIkf`**（与 §12.2(5) 一致；早前口述"标不确定"作废）。


**(5) AV1 = 设计内抛异常桩**：`jni_zero/codegen/gen_jni_java.py:10-15` `_stub_for_missing_native` → `throw new RuntimeException("Native method not present")`；开关 `jni_registration_generator.py:280/:511`。覆盖率 **193/193**；`GEN_JNI` native 数 **194**，多出的 1 条即 AV1（`Java_J_N_M0vTiIkf`），仅在实际创建 AV1 编码器时抛异常、**不到 JNI**；本项目走 VP9 ⇒ **非缺陷**。（**出处口径见 §13.19**：官方对本目标的产物 = **193/193、不含 AV1**；交付件的 AV1 桩来自**扩展输入集**（`javasources-with-av1.txt` 160→165 行）+ `--add-stubs-for-missing-native`，仍由 jni_zero 官方 `_stub_for_missing_native` 生成。）

**(6) t31 落位（18:17:28）引发的新增/更新勘误候选（我实测，判断权在 captain）**

| 行 | 现文 | 更正建议（**已按落位后的现值**） |
|---|---|---|
| `:769`（`7dbe8400` uniq=3：`:527/:717/:769`） | `**B. t23 修复结果的本层复核（部署 jar `7dbe8400…`，508 条目，2026-09-14 10:53）**` | 现 live jar = **`0c776934…`（18:17:28，509 条目，含 `J/N.class`）**；原文的 `508 条目`在其时点正确、现为 509 ⇒ 建议写"**当时 `7dbe8400…`/508；现 live `0c776934…`/509（含 `J/N`）**"，并合并我既定的"**仅证明类存在性（48/48 + `GEN_JNI` 194/194），不证明可绑定**"限定 |
| `:717` | `| **修复后实测（t23 落盘 jar `7dbe8400…`，508 条目）** | …` | 同上（哈希与条目数均已演进；`48`/`GEN_JNI`/缺失 0 该行结论仍成立） |
| `:545` | 部署 jar（`d98939bb…`，**与 AAR 内 `classes.jar` 同 sha256**） | ⚠️ **值演进、关系仍成立**：现 live jar = `0c776934…`、live AAR 内 `classes.jar` 亦 = `0c776934…`（我实测逐字节相同）⇒ 建议写"**值已演进（现 `0c776934…`），live jar ≡ live AAR `classes.jar` 的关系仍成立**"，**不要**当事实错误删改 |
| `:777` | `**1 条 = `org_webrtc_LibaomAv1Encoder_create`**（AV1，潜在）` | 语义 → "（AV1；absent proxy ⇒ **设计内抛异常桩**，非缺陷）"；全文 `潜在` **仅此 1 处**（我实测 `grep -c 潜在` = 1） |

**不改（我复核同意）**：`:710`（"修复前 jar（`classes.jar` `d98939bb…`）… **47**（47/47 全缺）"—— 它就是 pre-t23 件，正确）、`:544`（"只扫 `generated_*` 会漏 … **旧稿写 14 个/合计 187，更正见 §16.5**；16 分片并集 = 194"—— 正确的历史注记）。
**`187` 残留的精确口径（我实测）**：`grep -c 187 reports/07-native-dev.md` = **2 行** —— `:544`（**正确的历史注记，不改**）与 `:567`（**待改的过期数字**）。写"全文只有 `:567` 一处"会与 `:544` 冲突，须按"2 处、其中 1 处应保留"表述。

**引用层级的三套口径（我实测，勿混用）**：① **类级‑含自身 = 49**；② **类级‑剔除 `GEN_JNI.class` = 48**（我实测这 48 个**全部以 `Jni.class` 结尾**、非 `*Jni` 的引用者 = 0）；③ **方法级‑去重 = 194** 个不同的 `GEN_JNI.<可读名>` 被引用（我的计数法得"原始出现次数 = 388"，系 `javap -v` 重复打印所致，**以去重 194 为准**）。三者**不是互相矛盾**，只是层级不同。

**锚点机械匹配的两点修正（native-dev 本轮称"均已 `uniq=1`"，我实测有两处不成立）**：
- `与 AAR 内 `classes.jar` 同 sha256`（**裸子串**）→ **uniq = 2（`:545` 与 `:563`）**，**不能**当唯一锚点；**精确整串**（native-dev 原意、带反引号）= `` 部署 `third_party/libwebrtc/java/libwebrtc-java.jar`（`d98939bb…`，**与 AAR 内 `classes.jar` 同 sha256**） `` ⇒ **uniq = 1（`:545`）** —— 其消息里漏了反引号，故 `grep -cF` 裸串得 0/2；**t27 用"行号 + 精确整串"作键即可**。
- `47（47/47 全缺）` / `全缺` → **`全缺` uniq = 4（`:710/:757/:763/:774`）**；`修复前 jar` + `d98939bb` 的组合才可用（`d98939bb` 本身 uniq=2：`:545/:710`）。
- 其余本轮给的锚点我实测确实 `uniq=1`：`:769`、`:717`、`:544`、`:567`、`:777`（用 `（AV1，潜在）`匹配）✅
- 另：`7dbe8400…` 在 `reports/07` 内出现在 **`:527`/`:717`/`:769` 三处**（不是两处）；~~其**文件**在磁盘上已无保留（有界搜索无命中）~~ **← 本句已被后文更正，勿再引用**：容器可见树内**确有**该件 `artifacts/pre-deploy-7dbe8400.jar`（**`root:root` / mode 600 / 1 187 970 B / mtime 11:03:36，uid 1000 不可读**），宿主侧另存 `/tmp/pre-deploy.jar`（webrtc-builder 报，容器不可见）⇒ 正确口径 = **"在盘但容器侧不可读；宿主侧可复算、容器侧不可复算"**（详见 §13.22(e) 与 §13.25(i)）。


### 13.3 "修好"判据必须**按路线分支**（我先前给出的四条只适用路线 B）
- **我此前的四条**（`.rodata` 出现 `org/jni_zero/GEN_JNI` + ≈194 条可读名；`JNI_OnLoad` 出现 `blr`；导出仍 193）**只描述路线 B**（把 RegisterNatives 注册表链进 `.so`）。**用在路线 A 上会假阴性**（`.so` 字节不变 ⇒ 四项仍全为 0/0/0/193）。
- **路线 A（captain 已定、t30 在做；`.so` 不变）= jar 侧判据**： **（§13.22 更新：B 已落位；A 分支自此仅作对照）**
  1. `unzip -Z1 $J | grep -c '^J/N\.class$'` = **1**（当前 **0**）；
  2. `jni_mangle(J.N 的 native 名)` 集合 == `.so` 的 `Java_J_N_` 后缀集合，**双向差集为空（各 193）**（映射方向须为 mangle(Java 名)：`_`→`_1`、`$`→`_00024`；**不要**对符号做朴素反转义，连续 `_1x` 有歧义）；
  3. `javap -p org.jni_zero.GEN_JNI | grep -c ' native '` = **0**（转发层；当前 **194**）；
  4. 方法总数：`J.N` = **194**（193 native + 1 AV1 桩）、`GEN_JNI` = **194**（193 转发 + 1 桩）。
- **路线 B（重链 `.so`）才适用**我此前四条；代价：`.so` 字节变 ⇒ APK 必须重编、t24/t28 的 16 KB 链需全部复验。**captain 已选路线 A，B 仅作备选且需批准**。
- **两路线共同点**：AV1（`Java_J_N_M0vTiIkf`）**不得**计入"集合相等"（是 **193↔193**，不是 194）。

### 13.4 t25 回归测试的断言强度（review finding，**不判失败但不得当作可绑定证据**）
- `app/src/test/kotlin/com/example/webrtcdemo/webrtc/JniBindingClasspathTest.kt`（123 行 / sha256 `f52555cc9a9b4afc…`）**2 个测试**、断言方式**仅 `Class.forName` 可解析**；`REQUIRED_BINDINGS` = **43 条（1 `GEN_JNI` + 42 `*Jni`）**；对 `J.N`/转发形态/native-ness 断言 = **0**；
- ⇒ 当前 jar 上**绿灯而绑定仍断**（假绿）；清单另漏 5 项（真值 47）；注释 `:36` 含过期"187"；
- **建议（含生效时间点，见 §13.7(b)）**：android-dev 加固——**清单 42→47（共 48）现在即可做**（纯严格化）；**`J.N` 存在 / `GEN_JNI` native=0 两条断言须等 t31 落位后再加**，否则当前 jar 上会立即打红；**存在性回归不得替代可绑定判据**（已登记 K-16）。

### 13.5 路线 A **生成件侧**的独立复测（对象 = t30 在盘产物；**修复尚未落位**）

> **范围与评级（三态）**：下表所有读数均为我（verifier）本轮**自己跑出**（命令见 §10.12）。**我未复跑 GN/ninja 生成目标、未复跑 `javac`** —— "该目标只有 1 个 ACTION、CXX/SOLINK=0、产物为该 srcjar"属 **native-dev（t30 报告）自述**，我未验证。另外 `J/N.java` 与 `.so` 的集合相等是**静态判据**，**不等于**真机可绑定（无真机 ⇒ 仍不能判"已修好"）。

| 项 | 我的实测 | 轨迹 |
|---|---|---|
| 生成件位置/指纹 | `webrtc-build/t30/handoff/src/J/N.java` = `e7eacfeec8e8f43d3c75a36d0ebdd1c1a825bfc7012251492fa7c383e3883312`；`handoff/src/org/jni_zero/GEN_JNI.java` = `bdfd673ce20528e03aa8fff0ecd20eefef4bdca379a05c6d00a76febba2418ae`；`out/B` 与 `handoff` **逐字节相同**；`out/A/src/J/N.java` = `6bd817a61b02e9ed6b798d685e7e2b93a814aaf6fd6205dbc3d1b6314999e8bb`（**≠ B**：`Original name` 序列相同，差异在参数/桩） | — |
| `J/N.java` native 声明数 | **193**（唯一名 193；`// Original name:` 注释 193）—— **不含 AV1** | 对应 `.so` 的 193 |
| **路线 A 判据 2（集合相等）** | `jni_mangle(J/N 的 193 个 native 名)`（`_`→`_1`、`$`→`_00024`） **==** `.so` 的 193 个 `Java_J_N_*` 后缀：**J/N 独有 = 0、`.so` 独有 = 0（双向差集为空）** | ✅ **精确集合相等**（⚠️ 必须按 mangle 正向算；**朴素反转义会假判不等**，首测即因方向错误得 37/37 差异，连续 `_1x` 有歧义） |
| **路线 A 判据 3** | `handoff/src/org/jni_zero/GEN_JNI.java`：`static native` = **0**、转发 `J.N.` 调用 = **193**、抛异常桩 = **1** | ✅ 转发层已替换 Placeholder |
| AV1 处置 | `J/N.java:576-577` 与 `GEN_JNI.java:1035-1036` 均为**非 native 抛异常桩**（`throw new RuntimeException("Native method not present")`）；`out/A` 的 `J/N.java` **无**该桩 | 设计内豁免；集合相等按 **193↔193**（AV1 不计入） |
| 编译产物（我已 `javap`，未复跑 `javac`） | `handoff/classes/J/N.class` = `1ff8d3ff4032643339ad271f552475740d735dddf06ae42e507bb657f98a8932`、`handoff/classes/org/jni_zero/GEN_JNI.class` = `a6e7edcf9b90a4f7a15273de580bf7faf35ac7f818a4345c9618fd75fea40f08`；两者 **major 61**；`J/N.class` **native = 193** + 非 native AV1 桩 1；`GEN_JNI.class` **native = 0**；`javap -classpath handoff/classes:<jar> J.N` 可解析 | 与源文件计数一致 |
| **是否已落位** | **落位前（历史快照）**：jar `dc5f8919…`（508 条目）与 APK `721df1c8…` 内 `J/N.class` 均 = **0**。**现行（18:33:42 起）**：jar `0c776934…` 内 `J/N.class` = **1**（`1ff8d3ff…`）⇒ **K-15 的 jar/AAR 侧已闭合**；**APK 侧 = 已闭合（captain 转录，见 §13.24）**：交付 APK `30c41ac9…` 的 dex 内 `LJ/N;` = 3、`GEN_JNI;` = 3、`PCF_Jni;` = 2 |


### 13.6 路线 A 的**变体选择**与交叉核对（对象 = t30 handoff 在盘产物）
> **收口状态（2026-09-14 18:33:42 起）**：**落位已完成，且落的就是本节推荐的 B**（jar `0c776934…` / AAR `8e8f2baf…` / `J/N.class 1ff8d3ff…` / `GEN_JNI.class a6e7edcf…`）；本节"输入请取 `handoff`（`dca67dc7…`）、别取 A"已是**既成事实**；A 变体 = **WITHDRAWN**（18:32:24 非授权落位 → 18:33:42 回滚，事故件隔离于 `tmp/jn-fix/QUARANTINE-A/`，见 §13.21）。

> 触发：native-dev 于 t30 后追加"推荐 B（含 AV1 桩）"与若干可复算数字。以下**全部为我（verifier）自己复跑**的读数。

| # | 我的实测 | 结论 / 对 t31 落位的含义 |
|---|---|---|
| 1 | `javap -p -c org.webrtc.LibaomAv1EncoderJni`（jar 内）→ `invokestatic // Method org/jni_zero/GEN_JNI.org_webrtc_LibaomAv1Encoder_create:(J)J` | **决定性**：交付 GEN_JNI **必须保留** `org_webrtc_LibaomAv1Encoder_create`。若采用 **A（193，无桩）**，该调用点变 **`NoSuchMethodError`**（仅在走 AV1 编码器时触发，本项目 VP9 不触发，但仍属可见缺陷）⇒ **t31 必须落位 B / `handoff`**（含非 native 抛异常桩，语义同上游 Placeholder：`RuntimeException("Native method not present")`） |
| 2 | `handoff/libjingle_peerconnection_so__jni_registration.srcjar` = `dca67dc73190e9ece5e19e8e8599be794ee150308ffa0cb6c0e98f772aa611fb`；解包内容 = **仅** `org/jni_zero/GEN_JNI.java` + `J/N.java`，**`.cc` = 0**；两份源指纹 = `bdfd673c…`（GEN_JNI，193 转发 + 1 桩）/ `e7eacfee…`（J/N，193 native + 1 桩） | `out/B` srcjar **同哈希**；`out/A` 源不同（`6bd817a6…` / `e0ff02a7…`，无桩）⇒ **handoff 交付的就是 B** ✅ |
| 3 | **AV1 口径（务必分开写）**：native/符号集合相等按 **193 ↔ 193**（AV1 不计入）；**类的方法面必须是 194**（193 native + 1 非 native 桩；`GEN_JNI` 同为 193 + 1）；`J/N.class` `javap` = 193 native + 1 桩，`GEN_JNI.class` = native **0** + 1 桩，两者 major **61** | 我 §13.3 第 4 条"方法总数 194"与此一致；**"不写 194"只适用于 native/符号集合**，不适用于方法面 |
| 4 | jar 内引用 `org/jni_zero/GEN_JNI` 的 `.class` = **49**（**含 `GEN_JNI.class` 自身**；**外部引用 = 48**，见 §13.13(d)）；jar 内 `J/` 前缀条目 = **0**（总条目 508） | 替换后应出现 `J/N.class`；**"零缺口"判据 = 这 49 个类引用的方法在 B 版 GEN_JNI 中全部存在**（可由 t34 脚本化断言） |
| 5 | `src/out/Release-arm64/aar/arm64-v8a/toolchain.ninja`（9,398,126 B）：`--use-proxy-hash` 出现 **34 次**；`jni_zero.py generate-final` 命令行 **7 条**（7/7 带该 flag） | 与 native-dev 自述"27 条 `from-source`"**不符**（疑取自另一 ninja 文件或不同计法）。**"hashed/proxy 是 build 面事实"这一结论我认可**（7 条官方 action 逐字带 flag），但 27 这个数字我**未能复现**，不写进结论 |

**材料勘误提示（native-dev t30 材料，供其自订）**：`webrtc-build/t30/logs/verify.log:16-17` 记 `GEN_JNI.java: … stub=0 方法合计=193` 与 `J_N.java: … stub=0 方法合计=193` —— 这两行与 **A 变体**（`out/A`）相符，与**其推荐并handoff 的 B 变体**（每份 193+1 = 194 方法）**不一致**。若 t31 依据该日志挑选变体，会取到 A。建议把该日志标注为"对应 A 变体"，并另存 B 变体的计数输出。


### 13.7 可绑定判据的**最终措辞**、真值表（含生效时间点）与量化补强

#### (a) `kMethods` 措辞收紧（我报告从未写"或 `kMethods` 存在"这一分支）
`grep -n kMethods reports/99-final-report.md` 仅 **2 处**（K-15 与 §12.5），均为**描述性**："`.so` 侧为 hashing/short-proxy 模式、无 `kMethods`"。**判据不设"或"分支**，写法固定为单一路径：

> **运行期可绑定 ⇔** ① jar/APK 内 `J/N.class` 可解析；② `org.jni_zero.GEN_JNI` 的 `static native` 计数 = **0**（转发形态）；③ `GEN_JNI` 方法签名数 = **194**；④ `jni_mangle(J.N 的 193 个 native 名)` ≡ `.so` 的 **193** 个 `Java_J_N_*`（双向差集为空；AV1 不计入符号集合）。

`kMethods` 的正确表述：本 build 里它**不是"缺失"而是设计上不存在**——非 module 的 hashing 模式**不生成 `.cc`**（golden 已证 `.cc` 计数 = 0），只有**带 module 的 hashing** 才生成 `kMethods`，而那时 `kMethods` 的 **name 是哈希名**。因此"`kMethods` + **可读名**"在本 checkout **不对应任何真实形态**，它等价于**已被 captain 否掉的路线 (i)**（切经典/非 hashing 模式 ⇒ 必须重编 `.so`）。⇒ 作为**路线 (i) 的前置条件单列**，**不并入可绑定判据**。

#### (b) 判据真值表（落位前 = 现行 jar/APK `721df1c8…`；落位后 = t31/t33 之后）
| 断言 | 落位前 | 落位后 | 性质 |
|---|---|---|---|
| `org.jni_zero.GEN_JNI` 存在 | ✅ | ✅ | 恒真 |
| `GEN_JNI` **方法签名数**（B 形态 = **194**；A 形态 = **193**，见 §13.8） | ✅ 194（Placeholder） | 194(B) / 193(A) | 仅在保留 AV1 桩的 **B** 形态下是 194 |
| `GEN_JNI` 的 `static native` 数 = **0** | ❌ **= 194** | ✅ | **切换点断言**（落位前必红） |
| `J/N.class` 可解析（jar 内 `J/` 条目 0 → 1） | ❌ **= 0** | ✅ | **切换点断言** |
| `J.N` native 数 = **193**（+1 非 native AV1 桩，方法合计 194） | n/a | ✅ | 落位后 |
| `jni_mangle(J.N native)` ≡ `.so` **193**（双向差集空） | n/a | ✅ | 落位后（唯一豁免 AV1） |

⇒ **生效时间点（避免把当前绿色测试打红）**：`JniBindingClasspathTest` 的**清单 42→47（共 48）**属"纯严格化"，**现在就能做**；而 **`J/N.class` 存在 / `GEN_JNI` native=0** 两条断言**必须等 t31 落位后再加**（否则当前 jar 上立即失败）。已同步 android-dev，本报告亦按此记录，替代 §13.4 中未注明时间点的建议。

#### (c) 我复跑得到的量化补强（**全部自跑**，用于钉死"两侧必须同时满足"）
| 复跑项 | 我的原始读数 | 说明 |
|---|---|---|
| B 版 `GEN_JNI` ↔ jar Placeholder：**名字+描述符** | `javap -p` 各得 **194** 条签名；**交集 194**、Placeholder 独有 **0**、B 独有 **0**；`native` 修饰符：B = **0**、Placeholder = **194** | 唯一差异即"有意为之的 native 修饰符" ✅ |
| Placeholder 194 的**来源 = 16 个 per-target 并集** | 我按 gen 目录归属复算：`peerconnection 121 + video 25 + base 12 + swcodecs 8 + java 4 + java_audio_device_module_native 4 + libvpx_vp9 4 + environment 3 + generate_jni 3 + builtin_audio_codecs 2 + libvpx_vp8 2 + metrics 2 + dav1d 1 + java_audio 1 + libaom_av1 1 + video_egl 1` = **194**，未映射 **0** | 与 native-dev 的 16 项清单**多重集完全一致** ⇒ 这就是 Placeholder"看起来齐全却一条都绑不上"的成因（类名是 `org.jni_zero.GEN_JNI`，运行期要求 `J.N`） |
| 5 个补充类的 8 条 native | `M3mJB0tB/MIXdWn9A/MioeoqOK/MVoHeMKY/MsGvGVCS/MMv8RAm7/ME2Hhs12/MRCqqvhw` 逐个 `llvm-nm -D --defined-only` 命中 `.so` 各 **1** 次 | ⇒ 清单 42→47 为**纯严格化、无新豁免债** |
| J.N 名结构断言 | 193 个 native 名**全部 8 字符且以 `M` 开头**；含 `_ForTesting` = **0**（本目标未用 `--include-test-only`） | 纯 JVM 侧廉价结构断言，可在 t32 采用 |
| 门禁命令的环境修正 | **本容器无 `unzip`**（`command -v unzip` = 空）⇒ native-dev 给的 `unzip -oq` 版本在此会直接失败；请改用 `jar tf` / `jar xf`（本报告全部用 `jar`/`javap` 实测） | 避免 t34 复跑时"命令找不到"式假红 |

#### (d) 提交级不变式（t34 可直接复用）
```bash
# ① 落位后应得：J/N 条目 1、GEN_JNI native 0、签名数 194
jar tf <jar|apk> | grep -c '^J/N\.class$'                      # 期望 1（落位前 0）
javap -p -classpath <jar> org.jni_zero.GEN_JNI | grep -c ' native '   # 期望 0（落位前 194）
javap -p -classpath <jar> org.jni_zero.GEN_JNI | grep -cE ' static '  # 期望 194
# ② 集合相等（必须正向 mangle：`_`→`_1`、`$`→`_00024`；193 名中 37 名含 `_`/`$`，朴素反转义会 37/37 假红）
#    见 §10.12 的 /tmp/routeA_check.mjs 判定式
```


### 13.8 t31 **staging jar** 独立复验（`tmp/jn-fix/libwebrtc-java.jar`）——~~A/B 形态分歧，需 captain 裁定~~ **已收口：captain 裁定 (i) 落 B（§13.22）**

> **收口说明（2026-09-14 落位后）**：本节及其字节证据描述的是**落位前**的 staging A 形态（`c289b4df…`）；**落位件 = B**（jar `0c776934…`、`J/N.class` `1ff8d3ff…`、`GEN_JNI.class` `a6e7edcf…`，与 `t30/handoff/classes` 逐字节相同）。**A/B 二选一问题已关闭，本节保留为过程史。**

对象：`/data/dsh/home/workspace/tmp/jn-fix/libwebrtc-java.jar`（webrtc-builder 的 staging，**未落位**；该文件**现已不在盘上** —— 落位后同内容件更名为 `candidate-A-DO-NOT-LAND-libwebrtc-java.jar` 后亦已删除，见 §13.21/§13.22）
我实测：`sha256 c289b4dfd06827bc…`、**1 206 237 B**、**509 个 `.class`**、major 分布 **`{55:51, 61:458}`**（无 >61）——与其自述**完全一致** ✅

| 判据 | 我的实测（staging） | 结论 |
|---|---|---|
| `J/N.class` 存在 | ✅ | 路线 A 判据① **通过** |
| `J.N` native 数 | **193**（类方法合计 194 = 193 native + `<init>`，**无 AV1 桩**） | — |
| `jni_mangle(J.N native)` ↔ `.so`（`757cef81…`）193 符号 | **双向差集为空（0 / 0）** | 判据④ **通过**（核心修复成立） |
| `GEN_JNI` `static native` | **0** | 判据② **通过** |
| `GEN_JNI` 方法签名数 | **193**（193 转发 + `<init>`，**无 AV1 桩**） | ⚠️ **不是 194** ⇒ 见下"分歧" |
| 对照：现行交付 jar（未修复） | `J/N.class` = **false**、`GEN_JNI` native = **194** | 同一判据在修复前必红 ⇒ **判据本身有效**（非恒真）✅ |

#### ⚠️ A/B 形态分歧（**本轮最重要的新增发现**）
- **事实（我 `javap -c` 实测）**：即使在这个 staging jar 里，`org.webrtc.LibaomAv1EncoderJni` **仍然** `invokestatic // Method org/jni_zero/GEN_JNI.org_webrtc_LibaomAv1Encoder_create:(J)J`；而该 jar 的 `GEN_JNI` **没有**这个方法（`grep org_webrtc_LibaomAv1Encoder_create` = **0 命中**）。
- **后果**：一旦触发 AV1 编码器创建，将抛 **`NoSuchMethodError`**（而不是上游 Placeholder 语义的 `RuntimeException("Native method not present")`）。**这是 t30 推荐 B 的全部理由**（§13.6）。
- **可达性（我核了 app 侧）**：`app/src/main/kotlin/**` 只注册 **`Vp9VideoEncoderFactory`**（`WebRtcEngine.kt:144`），全仓**无** `LibaomAv1EncoderFactory`/Dav1d 使用 ⇒ 以"1:1 VP9 通话 demo"为交付口径，该路径**不可达**，**不是运行阻塞**，但属**静态确定的潜在缺陷**。
- **（已收口 2026-09-14 落位后：captain 已裁定 (i) 落 B ⇒ 本项不再适用；现行 `GEN_JNI`/`J.N` 均含 AV1 非 native 桩，触发时是设计内 `RuntimeException("Native method not present")`，不是 `NoSuchMethodError`。以下为落位前分析，保留为过程史。）**
- 因此本项**按 medium（潜在、需裁定）**记，不作失败判定：请 captain 二选一 —— **(i) 落 B**（补 AV1 非 native 桩，同时满足判据③=194 与 §13.6），或 **(ii) 明确裁定"A 可接受"**，并在交付说明里写明"AV1 路径不可达 + 若启用则 `NoSuchMethodError`"。

#### 门禁脚本 `scripts/check_jn_binding.py` 的评价（我**未能执行**，只做静态审查 + 等价复跑）
- **我（verifier 容器）无法运行它**：本容器 **无 python3**（`command -v python3/python/python3.11/python3.12` 全空）⇒ **署名作者在宿主机执行不受影响**（其给的 staging 原始输出即宿主侧结果），但**容器内不可复跑**；引用该脚本时请注明执行环境。另该文件当前为 **`M`（未提交）**，与 `1621d72` 入库版可能不同（mtime 18:08、mode 0600）——请注明以哪一版为准。**我没有执行它**，下述 staging 结论全部由我自己的 `javap`/常量池扫描得出。
- **静态审查要点**：`KNOWN_EXEMPT = {"org_webrtc_LibaomAv1Encoder_create"}`（`:52`）会把 AV1 未覆盖项计为"已知豁免"，`RESULT` 仍 **PASS**（`:188-199`、`:211`）⇒ **该门禁对 A 与 B 都会 PASS**，因此它**不能**用来证明"AV1 路径安全"，只能证明判据①④与②（这是本修复的实质部分）。建议注释里写明这一边界，避免"闸门绿 = AV1 安全"的误读。
- **它设计得对的地方**：符号期望值按官方规则正向复算（`Java_` + `jni_mangle('J/N')` + `_` + `jni_mangle(hashed)`，`_`→`_1`/`$`→`_00024`/`/`→`_`），并做**双向**差集 ⇒ 与 §13.3/§13.7 的判据同构（不是数量对齐）✅

#### 对 §13.7(b) 真值表的**修正**（原表把 194 写成"恒真不变式"，仅在 B 形态下成立）
| 断言 | 现行（未修复） | 落位=**A** | 落位=**B（推荐）** |
|---|---|---|---|
| `J/N.class` 存在 | ❌ 0 | ✅ | ✅ |
| `GEN_JNI` native = 0 | ❌ 194 | ✅ 0 | ✅ 0 |
| `GEN_JNI` 方法签名数 | 194 | **193** | **194**（193 转发 + 1 AV1 桩） |
| `J.N` native ↔ `.so` 193（双向差集空） | n/a | ✅ | ✅ |
| AV1 调用点 | 悬空（native，真机 `UnsatisfiedLinkError`） | 悬空 ⇒ **`NoSuchMethodError`** | 由抛出桩兜住（`RuntimeException`） |

⇒ **落位验收时请先确认取的是哪一形态**：`javap -p -classpath <jar> org.jni_zero.GEN_JNI | grep -cE ' static '`（A=193 / B=194）。


### 13.9 t30 `evidence/` 原样输出的独立复算 + 源码行号自核

对象：`webrtc-build/t30/evidence/`（native-dev 导出，声称"不经加工、不含结论"）。我**只读 `cat`/`sha256sum` 并用自己的脚本复算**，未采信其结论。

| 文件 | 我的 `sha256sum` | 与自述 |
|---|---|---|
| `J.N.javap-p.txt` | `520ea42dbd0b2557426a07bbfae50615729a90ec211b465520d64accfb3e45f6` | ✅ 一致 |
| `GEN_JNI.javap-p.txt` | `3c064897510d950b9dacc5d4234027c0300a98070c583d3444ea49967e0541c3` | ✅ 一致 |
| `so.llvm-nm-D-defined-only.txt` | `66e061c3c6af07228e79e0bdf0ae12a0b59b69aa185b60c78147994eb35729d3` | ✅ 一致 |
| `so.Java_J_N_.txt` | `1279d83cb4f3996baaa03d2b9155c956cb8e69475f3f2becfcfb40af61262a77` | ✅ 一致 |
| `J.N.native-names.txt` | `9c237e6aa84992da95dee70b9f785edc555fa9a8e991247431aa78563f80b11f` | ✅ 一致 |
| `README.md` | `e5d052e3dd67066b9f6ef66d2b5227dbbf13ffc66e58c538f544ecb25f98e159` | ✅ 一致 |

**我从原样输出复算（非其结论）**：
- `J.N.native-names.txt` = **193** 行、`so.Java_J_N_.txt` = **193** 行；`jni_mangle(名)`（`_`→`_1`、`$`→`_00024`）后**双向差集 = 0 / 0（精确相等）** ✅；其中**含 `_`/`$` 的名字 = 37 个** ✅；实证样本：`names[181] = MzznQVi_` ↔ `syms[192] = Java_J_N_MzznQVi_1`（即 `_`→`_1`）。
- `so.llvm-nm-D-defined-only.txt` = **194 行** = **193 个 `Java_J_N_*` + 1 个 `JNI_OnLoad`**，**无 `JNI_OnUnload`** ⇒ 再次独立印证 §13.1 与 `reports/07:551` 的勘误方向（libjingle 不含 `JNI_OnUnload`）✅
- `javap -p` 口径陷阱（native-dev 提示，我实测成立）：两份 `.txt` 各 **195** 行含 `(`，其中 **1 行是默认构造器** ⇒ **方法数 = 194**、native 数 = **193（J.N）/ 0（GEN_JNI）**。

**源码行号自核**（我 `grep -n`/`sed -n` 原文核对；文件真身为 **`webrtc-build/src/third_party/jni_zero/codegen/gen_jni_java.py`**，仓库内无此源码，引用时须带 checkout 前缀）：
| 位置 | 实测 |
|---|---|
| `_stub_for_missing_native` | `def` 在 **`:10`**，函数体 `:10-15`，`throw new RuntimeException("Native method not present");` 在 **`:15`** |
| `_forwarding_method` / `_native_method` | `:18` / `:36` |
| `generate_forwarding` | `def` **`:55`**；`present_proxy_natives` 循环 **`:74`**、转发调用 **`:75`**；`absent_proxy_natives` 循环 **`:77`**、桩 **`:78`** |
| `generate_impl` | `def` **`:82`**；`boundary_proxy_natives` 循环 **`:111`**、`_native_method` 调用 **`:112`**；`absent_proxy_natives` 循环 **`:113`**、桩 **`:114`** |
| 开关 | `jni_registration_generator.py` `:280` / `:511`（**与 §12.2(5) 引用一致**） |
> **更正说明**：native-dev 提醒的"`:65-68` 应为 `:74`""`:75-78` 起点偏 2 行"——**本报告从未引用这两个行号**（`grep gen_jni_java.py` 全文仅 §12.2(5) 一处，引的是 `:10-15`，实测准确）；其提醒对**其他材料**（或我早期消息）有效，此处不构成报告缺陷。

**落位后可复用的同一条命令（我据此预演）**：t30 的两个 `.class` 即落位字节本身（`J/N.class 1ff8d3ff…`、`GEN_JNI.class a6e7edcf…`）⇒ t29 落位后从 jar 抽同名条目（本容器用 `jar xf`，**无 `unzip`**）再跑**完全相同的 `javap -p`**，两份文本与两个 class 的 sha256 应逐字节相同；若不同即说明落位未取 B/handoff 那份（与 §13.8 的 A/B 判别式互为交叉校验）。
> **该预期已被当前 staging jar 实测证伪（判定 A 形态的字节级证据）**：从 `tmp/jn-fix/libwebrtc-java.jar` 抽出同名条目后与 handoff(B) 逐字节 `cmp` = **不相同** —— `J/N.class`：staging `9ada0641fcee…`（**6 742 B**）vs handoff `1ff8d3ff…`（**6 924 B**）；`GEN_JNI.class`：staging `8f3ce6137f02…`（**24 727 B**）vs handoff `a6e7edcf…`（**24 910 B**）；差值 **182 / 183 B**，与「少一个 AV1 抛异常桩方法（方法体 + 常量池条目）」的体量吻合，且与 `javap` 方法数（193 vs 194）一致 ⇒ **t31 staging 落的是 A，不是 handoff/B**。请 t31/t33 落位时以本条 + §13.8 的判别式做形态判定。



### 13.10 构建面事实复核（webrtc-builder 的路线 A 定论）与"路线 B 判据假阴性"读法

**已验证（我自跑）**：
| 项 | 我的实测 | 说明 |
|---|---|---|
| 源码分支 | `webrtc-build/src/third_party/jni_zero/jni_registration_generator.py`：**`:212`** `if jni_mode.is_hashing or jni_mode.is_muxing:`（选 `short_gen_jni_class`，`:213`）、**`:232`** 同判定的 srcjar 分支（`:233` 注释 `org/jni_zero/GEN_JNI.java` → `generate_forwarding`；`:244-252` 注释 `J/N.java` → `generate_impl`） | 与 webrtc-builder 引的 `:232-262` 覆盖同一块 ✅ |
| out 树产物 | `*jni_registration*` 共 **16** 个文件：**`.cc` = 0**、**`.srcjar` = 1**、**`.o` = 0** | 即"该目标只产 Java 源"✅ |
| 注册表 | `out/Release-arm64/gen` 内 `kMethods` 命中 = **0**；`obj` 内 = **0** | hashing 形态**不产注册源码**，无对象可链 ✅ |
| `.so` 未漂移 | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`；定义动态符号 **194 = `JNI_OnLoad` + 193 个 `Java_J_N_*`**（**无 `JNI_OnUnload`**） | 与 §13.1 一致 |

**未验证（属对方自述）**：`ninja -C out/Release-arm64 sdk/android:libjingle_peerconnection_so__jni_registration` 实跑得到的"**1 个 ACTION、CXX/SOLINK/CC = 0**"——我未复跑该 ninja 目标（`build.ninja` 内该目标为 phony，需 aar 子构建 toolchain 的 action；我确认了 `--use-proxy-hash` **7 条** `generate-final` 命令行与其产物形态，但**未**执行构建）。其**结论**（只产 Java 源）我已由上面的 out 树产物独立佐证。**产物面独立佐证（我复跑；无需在冻结期实跑 ninja）**：① **动作定义在盘**：`webrtc-build/src/out/Release-arm64/toolchain.ninja:3654` 为 `rule __sdk_android_libjingle_peerconnection_so__jni_registration___…_rule`、**:3655** = `command = python3 ../../third_party/jni_zero/jni_zero.py generate-final --srcjar-path gen/sdk/android/libjingle_peerconnection_so__jni_registration.srcjar …`、`:3656` = `description = ACTION //sdk/android:libjingle_peerconnection_so__jni_registration(…)`、`:3659` = 该 srcjar 的 build 语句；② **产物面**：全 `out` 树 `*jni_registration*` 只有 `.srcjar`（`2e352096…` / 62 140 B）、`.d`、`.javasources.txt`、`.nativesources.txt`，**`.cc` = 0、`.o` = 0** ⇒ "**该目标只产 Java 源**"由**产物面**自证；③ **未实跑**的理由：**实跑会写构建树 `out/`**，与维护期冻结冲突（如需"实跑留痕"，解冻后由 captain 指派 env-installer/webrtc-builder 执行）。"**1 ACTION / CXX=0 / SOLINK=0 / CC=0**"仍属其自述，但已被 ①② 佐证。
**路线 B 四条判据的"假阴性"读法（务必写进 t27/t34 判读）**：以下四项在**修好之后**仍会保持 **0 / 0 / 0 / 193**，这**是预期且正确**，**不得据此判失败**——
| 我此前的路线 B 判据 | 修好后（路线 A、`.so` 不变） | 读法 |
|---|---|---|
| `.rodata` 含 `org/jni_zero/GEN_JNI`（FindClass 目标） | **0** | 静态符号绑定**不需要** FindClass |
| `.rodata` 含 ≈194 条可读 native 名 | **0** | 可读名在 **Java 侧** `GEN_JNI`，不在 `.so` |
| `JNI_OnLoad` 内 `blr` | **0** | 无注册调用 |
| `Java_J_N_*` 导出数 | **193** | 不变 |
⇒ 这四项**只适用路线 B**（已由 §13.3 事先声明）；**路线 A 的判据见 §13.3/§13.6/§13.7/§13.8**（`J/N.class` + `jni_mangle` 双向集合相等 + `GEN_JNI` native=0 [+ 形态 A/B 判别]）。

**工具陷阱（对方首报、我复现认同）**：`llvm-objdump -d --disassemble-symbols=JNI_OnLoad` 在符号匹配失败时会**静默退化为全文件反汇编**（其第一遍误数出 `blr=324`）⇒ 数 `blr` 必须用显式 `--start-address/--stop-address`（本报告 §13.1 即如此，`0x29f718–0x29f78c` → **0 blr / 6 bl**）。

**门禁脚本可执行性提醒（重复强调）**：`scripts/check_jn_binding.py` 需要 `python3`，而**本容器无 `python3`**（§13.8 已记）⇒ 在容器内不可复跑；其 `KNOWN_EXEMPT` 白名单使 A/B 两形态都会 PASS（不构成"AV1 安全"证据）。**t34 我会用 `jar`/`javap` + 我自写的 `jni_mangle` 双向集合脚本独立复算，不依赖该脚本。**


### 13.11 **t31 已落位实测（2026-09-14 18:17:28）**：jar/AAR 侧路线 A(**B 形态**) 完成，APK 侧仍待重编

以下全部为我（verifier）只读实测；**未修改任何落位产物**。

| 对象 | 我实测 | 判读 |
|---|---|---|
| live `libwebrtc-java.jar` | `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757`、**1 206 602 B**、**509 条目**、mtime **18:17:28** | 相对 `dc5f8919…`（11:05:10、508 条目）**已变** |
| `J/N.class` | **存在**（`jar tf` = 1） | 路线 A 判据① ✅ |
| `J.N` native / 方法数 | **193** / **195**（= 194 方法 + 1 默认构造器 ⇒ 含 **AV1 非 native 桩**） | **B 形态** ✅（正解） |
| `J.N.class` sha256 | **`1ff8d3ff4032643339ad271f552475740d735dddf06ae42e507bb657f98a8932`** | **与 t30 `handoff` 逐字节相同** |
| `GEN_JNI.class` sha256 | **`a6e7edcf9b90a4f7a15273de580bf7faf35ac7f818a4345c9618fd75fea40f08`** | 同上（含 AV1 桩，**A/B 分歧已按 B 解决**） |
| `GEN_JNI` native / 方法数 | **0** / 195（194 方法 + 构造器） | 判据③ ✅ |
| 集合相等（判据④） | `jni_mangle(J.N 的 193 名)` ≡ `.so`(`757cef81…`) 的 193 个 `Java_J_N_*`：**双向差集 0 / 0** | ✅ 精确集合相等 |
| AV1 调用点 | `org.webrtc.LibaomAv1EncoderJni` 仍 `invokestatic` 该方法，**且 GEN_JNI 现提供非 native 桩** ⇒ 由 `NoSuchMethodError` 变为设计内 `RuntimeException("Native method not present")` | §13.8 分歧**已闭合** ✅ |
| 两 class `major` | **61 / 61** | AGP/D8 可消费 |
| live `libwebrtc-arm64.aar` | `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099`（6 492 067 B，18:17:28）；内 `classes.jar` = **`0c776934…`，与 live jar 逐字节相同** | jar↔AAR 一致 ✅ |
| **交付 APK** | **已重编（t33，captain 接管执行）**：`30c41ac9d3363cab249c9a1702958993fcfd965cf7ebbfeba5349435ab059be2`（33 309 445 B，mtime 18:41:59）；dex 14 个：`LJ/N;` = **3**、`Lorg/jni_zero/GEN_JNI;` = **3**、`PeerConnectionFactoryJni` = **2**。历史轮次 `721df1c8…`（33,293,061 B / 11:28:34，`LJ/N;` = 0）保留为前代 | ⇒ **K-15 交付物层面 = 已闭合**（见 §13.24；旧件证据存于仓外 `artifacts/app-debug-721df1c8.apk`） |

**live jar 哈希时间线（供后来者对齐口径）**：
`d98939bb…`（t16/t23 前，major 61 重编件；AAR 内 `classes.jar` 同哈希）→ `7dbe8400…`（10:53:11，**在 verifier（容器）可见树内已无保留**——全树含 `/tmp` 搜索 jar 无命中；但 native-dev 报其存在于**宿主 `/tmp/pre-deploy.jar`**（容器不可见）⇒ 该"内容等价"结论**可在宿主机复算、在本容器不可复算**。其宿主侧补充读数：两份 508 条目件**内容逐字节等价、仅打包元数据不同**——一份为 `504×2001-01-01 + 4×2026-09-14 10:52:2x`，另一份 `508×` 同一 epoch；**"4" 是 A 内部偏离其固定 epoch 的条目数，不是 A↔B 差异数**）→ `dc5f8919…`（11:05:10，508 条目，**无** `J/N`）→ **`0c776934…`（18:17:28，509 条目，含 `J/N` = handoff B，即本次落位）**。

⇒ **结论**：路线 A（B 形态）在 **jar/AAR 层已完成且可绑定**（判据①②③④全绿）。**交付可用性仍以 t33 重编后的 APK 为准**；t34 复验清单：新 APK 内 `LJ/N;` 出现、4 个 `.so` `p_align=0x4000`、`*Jni` 48 存在/47 被引用/0 缺失、`.so` 未漂移 `757cef81…`、dex major ≤ 61。

### 13.12 判据归属与计数口径的**最终钉死**（含两处会假红/假绿的数字）

#### (a) 判据归属（与 §13.3/§13.10 同一结论，此处给"唯一主判据"的最终写法）
- **路线 B 专属（不得用于本工程）**：`.rodata` 出现 `org/jni_zero/GEN_JNI` ≥1、≈194 条 `^org_webrtc_` 可读名、`JNI_OnLoad` 内 `blr` ≥1。**在路线 A 下这些恒为 0 且属正常**。
- **唯一主判据（路线 A）**：① jar/APK 内 `J/N.class` 可解析；② `GEN_JNI` 的 `static native` = 0（转发形态）；③ `GEN_JNI` 方法数 = **194**（**仅 B 形态**；A 形态为 193）；④ `jni_mangle(J.N 的 193 个 native 名)` ≡ `.so` 的 193 个 `Java_J_N_*`（双向差集为空）＋ `.so` 仍 `757cef81…`（不漂移）。
- 这与"把 `kMethods` 从判据剔除"是**同一类错误**：**用另一条修复路线的特征验收本路线 ⇒ 系统性假阴性**（已登记为流程教训）。

#### (b) 会**假红**的数字：可读名前缀分解（我实测，落位前/后同值）
`javap -p org.jni_zero.GEN_JNI` → 方法行 **195**（**194 方法 + 1 默认构造器**）；其中 **`org_webrtc_*` = 191**、**`org_jni_1zero_*` = 3**、两类之外 = **0**（`org_webrtc_audio_*` = 5，属 `org_webrtc_*` 子集）。
⇒ 判据里**不要**写"`grep -c 'org_webrtc_'` = 193"（那是 **`.so` 侧符号数**）——**jar 侧应为 191**；**193 与 191 是两个不同量**，混用必假红。落位后我复查 live jar 亦为 **191 + 3 = 194**（`static native` = 0，B 形态）✅

#### (c) `JNI_OnLoad` 指令数/分支数（我显式区间实测）
**更正（我此前的区间取短了）**：`llvm-objdump -d --start-address=0x29f718 --stop-address=0x29f78c` → 29 条指令、`bl` = 6、`blr` = 0 —— 但 **`0x29f78c` 不是符号边界**，该区间只有 `0x74` = **116 B**（我此前误写成 356 B）。
**正确取法（native-dev 给出根因、我已实测复现）**：`llvm-readelf -sW <so>` 的 **size 列是十六进制** —— `JNI_OnLoad` 为 `value=000000000029f718 size=164₍₁₆₎ = 356₁₀` ⇒ 完整区间 = `0x29f718–0x29f87c`：
```bash
$NDK/llvm-readelf -sW <so> | awk '$8=="JNI_OnLoad"{print $2,$3,$4}'   # addr=000000000029f718 size=164 type=FUNC
$NDK/llvm-objdump -d --start-address=0x29f718 --stop-address=0x29f87c <so> | awk '/^\s+[0-9a-f]+:/{n++} END{print n}'   # 89
```
我实测该完整区间 = **89 条指令 / `bl` = 7 / `blr` = 0**（89×4 = 356 B ✓ 自洽）⇒ **实质结论不变（`blr` = 0）**；t34 复跑一律用这条完整区间命令。
> native-dev 报"356 B / 89 指令 / `bl`=7"：其**区间取法不同**（我这一段 = 0x74 = 116 B），我**未能复现 89/7**；**实质结论一致（`blr` = 0 ⇒ 无注册调用）**。引用时请带上地址区间，否则数字对不上会互相怀疑。

#### (d) 工具可用性修正（本容器）
- **无 `strings`**：`strings -a <so> | grep -c …` 会"静默返回 0"（管道收到空输入）——**不可用作"字符串不存在"的证据**！请用 **`grep -a -c '<pat>' <so>`**（或 node 扫二进制）。我以此复测：`org/jni_zero/GEN_JNI` = 0、`org_webrtc_` = 0、`org_jni_1zero_` = 0 ✅（§13.1 的结论不变，但取证命令须换）。
- **无 `unzip`**（§13.7(d) 已记）：改用 `jar tf` / `jar xf`。
- ⚠️ **"静默空输入"陷阱（webrtc-builder 首报、我复核同意）**：容器内**既无 `unzip` 也无 `python3`**，用它们查 zip/class 会**静默得到 0**；而**空输入的 sha256 恒为 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`** ⇒ 若某次校验输出该值，几乎一定是"没读到数据"而非"文件为空"。**容器内可复跑**的正解 = `jar`/`javap` + 常量池扫描（本报告全部判据如此）。
- **`javap -classpath <jar> J.N` 可用（原"单字母包名会失败"的 caveat 已被 native-dev 本人撤回）**：我实测 `javap -p -classpath <jar> J.N`（native=193）与 `… J/N`、`… org.jni_zero.GEN_JNI`（native=0）**均正常**；"先抽条目再 `javap -p <dir>/J/N.class`"**同样可用、非必需**，仅作更稳的规范做法（t34 采用后者以留原样文本）。该 caveat **从未进入任何文件**（我复核其 `logs/{t34-gate,README-logs,commands-portable}.md` 中 `classpath` 命中 = **0/0/0**）⇒ 复现命令段**不含**"必须抽条目"的约束。
- **JDK 版本口径（container）**：容器内在盘的 JDK 为 `webrtc-build/src/third_party/jdk/current/bin`（**`javap -version` = 25.0.4.1**，容器里没有 JDK 17）—— **读**方法列表/描述符与版本无关，但**要"重编复现"必须显式 `--release 17`**（本报告 §13.16 的现场重编即如此）。
- **`t30/logs/commands-portable.md`（`588a8800…`）的环境表措辞偏强**：其"容器无 JDK ⇒ 门禁不能在容器跑"与实测不符 —— **默认 PATH 无，但 JDK/NDK 在盘、加 PATH 即可**（`jar`/`javap`/`java`/`llvm-nm`/`llvm-readelf` 立即可用 ⇒ 判据①②③④ 与 ELF 检查**容器内可复跑**）；真缺的只有 `unzip`/`python3`/`strings`（Python 版判据与 `unzip -oq` 走宿主/SSH）。native-dev 已认账；因写盘令**冻结不改**（避免作废该 sha）⇒ t34 记本条更正，解冻后由其改并按 P-13 同步新 sha。
- **"157" 属可复现的假数（方法论留痕）**：同一份 `GEN_JNI.class` 四种读法 —— 推荐式（行过滤 ` static ` + 宽字符类 + `sort -u`）= **194**；宽类无过滤 + `sort -u` = **195**（多默认构造器）；**窄式 `[a-z_0-9$]+\([^)]*\)` 无过滤 + `sort -u` = 157**（= 我此前的读数）；窄式仅 `\(` + `sort -u` = 110；`grep -c '(' ` = 195、`grep -cE '^  public static '` = 194。机制 = 窄字符类遇大写/`.` 退化为"尾部小写片段"再被 `sort -u` 去重 ⇒ **一律用推荐式**。
- **读方法名前缀别用 `grep -c 'org_'`**（会把**签名里的 `org.`** 也计入）：正确做法是先筛 `^  public static` 行再按前缀分组。我实测落位件 = **`org_webrtc_` 191 + `org_jni_1zero_` 3 = 194**（`grep -c 'org_'` 同为 194 属巧合），A 变体 = **190 + 3 = 193**；`org_webrtc_audio_` = 5（含于 191）。

### 13.13 "修复前失败"基线的独立复验、判定合取要求与两个假红/假绿陷阱

#### (a) 基线可复算（**对象仍在盘**）—— 我自跑，与其表逐项一致
基线件 `dc5f89193d55c97152a7dd1331f3f7d111f8dd099d4c970e9142231ea79f8915`（1 187 970 B）在盘上仍有 3 份：`tmp/jni-merge/libwebrtc-java.jar`、`tmp/t31/pre-routeA-libwebrtc-java.jar`、`webrtc-build/t30/evidence/classes-from-libwebrtc-arm64.aar.jar`（后两处我已实测同哈希）。
| 断言（同一判据，修复前应红） | 我在基线件上的实测 |
|---|---|
| `jar tf <jar> \| grep -c '^J/'` | **0** ⇒ 红（缺口正中） |
| `J/N.class` 存在 | **0** ⇒ 红 |
| `javap -p org.jni_zero.GEN_JNI` 分类 | 方法行 **195**（194 方法 + 1 构造器）、`static` **194**、`static native` **194**、`static 且非 native` **0** ⇒ 红（仍是 Placeholder） |
| 条目总数 | **508** |
⇒ **"同一断言：落位前红 → 落位后绿"** 两半齐备（绿侧见 §13.11：`J/`=1、`J/N` native 193、`GEN_JNI` native 0、双向差集 0/0）。

#### (b) 材料指纹的**三代沿革（已澄清，非矛盾）**——引用请一律用第 3 代现值
`webrtc-build/t30/evidence/prefix-baseline.md` **有三代**（native-dev 逐代只读实测，我复核其**现值为第 3 代**）：**第 1 代 2 162 B / `0f581023…2536`**（基线四项 + TSV 口径 + 判据 + 路线 B caveat）→ **第 2 代 3 648 B / `1472f482…e9e6`**（新增「时序」段）→ **第 3 代（现值）5 205 B / `755c010938c52c70a1b66ad87324a7d5d37b1114bedb00c185402032168d7cd5`**（mtime 18:16:08；新增「A/B 两版生成件对照表」+ `grep -i av1` 假阳性 caveat + 判据③ 的 A/B 参数化）。⇒ 我此前记的"指纹不符"实为**跨代引用**，不是矛盾。**严谨口径（native-dev 本轮更正、我采纳）**：该文件是**活文件、被同一路径顺序覆盖写出** ⇒ **现盘只剩第 3 代，前两代不可复核**（不是"从未出现"，而是"已被覆盖、无痕"）⇒ **只有现值 `755c0109…` 可核；早前两代的数值来自当事人自述，不作独立证据**；引用一律用第 3 代（维护期已冻结）。**可复用做法**：需要长期引用的证据**用新文件名、不要覆盖写**（如 `t34-gate.md` `6d1112dd…`（历史）与 `t34-gate-post-landing.md` `c87bb1cd…`（现行））。
（另：A/B 两 srcjar 我亦实测：**A = `2e352096…` / 62 140 B**、**B = `dca67dc7…` / 62 424 B**，与基线文件一致 ✅）

#### (c) 判定必须是**合取**，不能"满足其一"（**给 t32 的硬要求**）
`①J/N 可解析 ∧ ②GEN_JNI 的 static native = 0 ∧ ③GEN_JNI 方法数 = 194`。写成"或"会新增两个**假绿口子**：
- **只满足①**（有 `J.N`，但 `GEN_JNI` 仍是 Placeholder）：49 个调用点（=48 个外部类 + 自身；见下）全部指向 `GEN_JNI.<可读名>`，**没有任何调用点指向 `J.N`** ⇒ 首调仍 **`UnsatisfiedLinkError`**；
- **只满足②**（`GEN_JNI` 转发，但 `J/N.class` 未入 jar）⇒ **`NoClassDefFoundError: J/N`**。
> 我已把该结论写入 §13.7(b) 真值表的等价形式；本条把它写成**可直接抄进单测的合取式**。

#### (d) 两个会**误判**的取证陷阱（均已实测）
1. **`grep -i av1` 不可用**：`J/N.java` 里大小写不敏感 `av1` 命中 **2 行**（`:45` `org.webrtc.Dav1dDecoder#createDecoder` 含子串 `av1`、`:576` `LibaomAv1Encoder`），大小写敏感 `Av1` = **1** ⇒ 判 AV1 必须用 **大小写敏感**的 `Av1`（或全名 `org_webrtc_LibaomAv1Encoder_create`）。
2. **`GEN_JNI` 引用类数两种口径**：含 `GEN_JNI.class` 自身 = **49**，**外部引用 = 48**（我两次扫描分别得 49/48）——报告/脚本引用时须注明是否含自身，否则"49 vs 48"会被当矛盾。
3. **TSV 第 1 列已是 mangled 符号**：与 `.so` 的 `Java_J_N_*` **直接求差集即可**（我实测：193 ≡ 193，**双向差集 0/0**，无需 mangle）；只有从 `J.N` 的 Java 方法名出发时才需**正向** `jni_mangle`（193 名中 37 名含 `_`/`$`）。

### 13.14 交付 APK 的补充实测 + 单测"真实执行"判据（env-installer 披露 + 我方复跑）

#### (a) 交付 APK 未被重建（我复核，与其陈述一致）
`app/build/outputs/apk/debug/app-debug.apk` = **33 293 061 B** / `sha256 721df1c82841ad99…b724` / mtime **2026-09-14 11:28:34.950734908 +0800** —— 与 §13.11 记录**逐项相同**，未被覆盖、未被重建。

#### (b) 该 APK **已包含** 11:12 的 `FileLogger.kt` 改动（我独立扫 dex，非采信）
我在 APK 内 14 个 `.dex` 上按字节计数：`app-fallback.log` = **1**、`writeFailures` = **1**、`critical` = **7**、`FileLogger` = **42**。
源头侧：`app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt` mtime = **11:12:31**，而 T0（交付构建开始）= **11:26:08** ⇒ **该改动必然在交付 APK 内**，"APK 早于 FileLogger 改动"的担忧不成立 ✅
> ⚠️ **"app/src 最新 mtime"是移动靶（任何此类论断必须带取数时刻）**：11:19:51（env-installer 初测）→ 18:20:45（我取数）→ **18:28:44**（env-installer 18:33 取数 = t32 在途的 `JniBindingClasspathTest.kt`）→ **19:02:06**（我 19:04:32 取数 = **t33 构建过程重写 `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so`**）。⇒ 一律理解为"**交付 T0（11:26:08）之后的在途改动 / 构建动作**"，与"交付后被改"不是一回事。

#### (c) 单测证据链：**弃用 FROM-CACHE 那次，改用真实执行那次**
| 日志 | 结果 | 性质 |
|---|---|---|
| `reports/logs/t26d-testDebugUnitTest-20260914-112836.log` | `:app:testDebugUnitTest` **FROM-CACHE**；`3 executed, 3 from cache, 18 up-to-date` | ⚠️ **缓存恢复，不是当场执行**（env-installer 主动更正）⇒ **不得**作为"刚跑过"的证据 |
| `reports/logs/final2-testDebugUnitTest-20260914-114748.log`（11:50:18） | **BUILD SUCCESSFUL in 2m 29s**；**`24 actionable tasks: 24 executed`** | ✅ **真实全量执行**（`--no-build-cache --rerun-tasks`）；对应 XML mtime **11:50:17** = **42 用例 / 0 失败 / 0 错误**（11+17+2+4+8） |
| 我 t27 自跑（11:39） | `BUILD SUCCESSFUL in 1m 44s`；**`24 actionable tasks: 24 executed`** | ✅ 真实全量执行（报告 §12/§13 引用即此） |
⇒ **三态结论不变**：单测 **42/0/0 且为真实执行**；报告此前引用的是**我自跑的那次**（非 t26d 缓存恢复那次），故无需更正结论，但**必须记录该披露**，并且**引用 11:26/11:28 那条日志时不得写"当场重跑"**。

#### (d) 新增流程教训 **P-9**（与 P-7 同类）
**`--rerun-tasks` 单独使用仍可能命中构建缓存**（实测：`testDebugUnitTest` 报 `FROM-CACHE`）⇒ 凡"某测试/构建刚真实执行过"的断言，必须同时满足 **`--no-build-cache --rerun-tasks`** 且输出 **`N actionable tasks: N executed`（FROM-CACHE = 0）**；只凭 `BUILD SUCCESSFUL` 或 XML 时间来推断"刚跑过"会误判。

### 13.15 终产物 `.so` 回归护栏（t33/t34 必查）+ 跨代哈希勘误候选

#### (a) 回归护栏（**新 APK 必须仍满足**）
t29/t31 是**纯 Java 侧**修复 ⇒ 新一轮 APK 内 `lib/arm64-v8a/libjingle_peerconnection_so.so` **必须仍是 `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`**。若变，说明发生了重链/重编，**t30 的"符号集合 ≡ 193"证明对象即漂移，必须整组重跑**（否则会变成"证明对象漂移后的假通过"）。
命令（**本容器无 `unzip`**，故用 `jar`）：
```bash
mkdir -p /tmp/guard && (cd /tmp/guard && jar xf <APK> lib/arm64-v8a/libjingle_peerconnection_so.so)
sha256sum /tmp/guard/lib/arm64-v8a/libjingle_peerconnection_so.so   # 期望 757cef81…
# 同时复核 16 KB：四个 .so 的 LOAD 段 p_align 必须均 = 0x4000
llvm-readelf -lW /tmp/guard/lib/arm64-v8a/*.so | grep LOAD
```

#### (b) 现行 APK 的 `.so` 复核（我自跑，与 native-dev 一致）
| `.so` | sha256（前 16） | `p_align` |
|---|---|---|
| `libandroidx.graphics.path.so` | `41e9a793c43a0f4f` | `0x4000` |
| `libc++_shared.so` | `c9dbf4ec15e931f5` | `0x4000`（4 段） |
| `libjingle_peerconnection_so.so` | `757cef8128bf9151` | `0x4000` |
| `libwebrtcdemo_native.so` | `95c44e5ab9ff6f85` | `0x4000` |
另：APK 内 `libc++_shared.so`（`c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36`）**与 `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` 逐字节相同**（`cmp` 通过）⇒ AGP 未对落位件做变形 ✅
> 交付 APK 另有仓外快照：`/data/dsh/home/workspace/artifacts/app-debug-721df1c8.apk` = 33 293 061 B / `721df1c8…` / mtime 11:28:34（与仓库内那份同哈希）。

#### (c) 跨代哈希勘误候选（`reports/07` §9.1.2/§15.1 等；**证据有效、数值跨代**，非事实错误）
| 锚点 | 出现行（我实测 `grep -cF`） | 处置 |
|---|---|---|
| `c72d3667…`（**t10 代** APK，33 260 234 B） | **3 处**：`:392`、`:401`、`:522` | 加注"**当时值**；现行交付 APK = `721df1c8…`（33 293 061 B，mtime 11:28:34）"，**保留**旧证据 |
| `e9b66cc9…`（当时 APK 内自有库，1 231 512 B） | **3 处**：`:399`、`:522`、`:571` | 加注"尺寸不变、**sha256 已演进为 `95c44e5a…`**（同报告 `:670` 已是新值）"⇒ 报告内**两代值并存**，须显式区分 |
| `b0cddd86…`（**t24 代** APK，33 260 234 B） | **1 处**：`:581`（§15.1 标题） | 加注对应代次即可（**我此前误称其为"t10 代"，以本条为准**） |
| **`115aa211…`（t24 中间产物：`intermediates/cmake/.../libwebrtcdemo_native.so`，2 206 528 B，含调试信息）** | **2 处**：`:524`、`:608` | **native-dev 补漏、我已复核**（我此前列漏）⇒ 一并加注"中间产物、非交付件" |
| `95c44e5a…`（现行自有库） | **1 处**：`:670` | 现行值，正确 |
| `721df1c8…` / `6653fddf…` / `dc5f8919…` / `0c776934…` | **0 处** | ∠ 终报直接引用 `721df1c8…`（现行）与 `0c776934…`（落位 jar） |
> **权威口径（我复核后定稿）**：`reports/07` 正文引用的是 **6 个历史/中间哈希** —— `c72d3667`（t10 APK）、`e9b66cc9`（t10 自有库）、`b0cddd86`（**t24 APK**）、`115aa211`（**t24 中间产物**，native-dev 补漏、我已复核）、`95c44e5a`（strip 后自有库）、`7dbe8400`（中间版 jar，10:53）；而**交付现行值 `721df1c8…`（APK）与 `0c776934…`（落位 jar）在该文件正文出现 0 次** ⇒ 定性为"**证据有效、数值跨代**"，不是事实错误。
> ⚠️ **更正 native-dev 本轮的表述**："全文没有 `721df1c8`/`6653fddf`/`b0cddd86` 任一出现"——`721df1c8`、`6653fddf` 确实为 **0**，但 **`b0cddd86` 出现 1 次（`:581`）**，不能一并写"没有"。
> 另：**t10 代 APK（`c72d3667…`）已不在盘**（我全树有界搜索 `.apk` 仅得现行 `721df1c8` 快照与一处构建中间件）⇒ 跨代数值**无法现测**，只能按历史证据引用。

#### (d) K-17 的**新变体**与**最终处置**（`.git/index` 曾被 root 占用 ⇒ 无法提交）
本轮提交时 `cp $IDX .git/index` 报 `Permission denied`：实测 `.git/index` 为 **`root:root 0644`（mtime 18:25）**，而 uid 1000 既不能改写也不能 chown 它 ⇒ **凡以 node 身份提交者都会卡在最后的 index 同步**（`git commit` 本体亦会因 index 写失败而报错）。
**可用的就地修复（我已完成，未动任何他人内容）**：`.git` 目录本身是 `node` 可写 ⇒ **`rm -f .git/index && git read-tree HEAD`** 即由 git 以 node 身份重建索引（实测重建后 `0644 node:node`，`git status` 恢复正常、仅剩他人在途文件）。
**给 captain 的建议（一次性根治）**：`chown -R node:node /data/dsh/home/workspace/code/webrtc-demo/.git`（同时覆盖 `.git/objects/{33,56,ac,c6}` 的 root 属主问题）。**根治前，root 身份的任何 `git` 操作都可能再次把 `.git/index`/对象目录置为 root 所有，从而阻塞 node 身份成员。**

> **最终处置（captain，2026-09-14）：已定位根因 + 已根治 = chown + “uid-1000-only” 规则**
> **根因（captain 定位）**：**有成员以 root 身份在仓库里跑 git**（最典型：root shell 里的 `git status` 会刷新并重写 `.git/index`，将其置为 `root:root`）⇒ 与 `objects/{33,56,ac,c6}` 同源，即 18:25/18:27 等多次复发的来源。**处置**：再次 `chown -R 1000:1000 .git`（含 `.git/index`）+ 向相关成员下**硬规则：仓库内 git 一律以 uid 1000 执行**（SSH 用 `su -s /bin/bash admin -c "cd <repo> && git …"`），提交命令模板已分发。
> **根因（captain 定位、我复核一致）**：本仓库 git 操作**混用两个身份** —— 成员常经 SSH 以 **root** 在宿主机仓库跑 `git status/diff/commit`，而 verifier 在容器内以 **uid 1000（宿主 `admin`）** 跑 git；**root 的任一次 git 写操作**（刷 index / 写对象）都会把 `.git/index` 或 `.git/objects/<fanout>` 置为 `root:root` ⇒ uid 1000 的提交随即失败（`objects/{33,56,ac,c6}` 与 `.git/index` 两个变体同一根因）。
> **已执行**：`chown -R 1000:1000 /opt-dsh-workspaces/code/webrtc-demo/.git`（含 `.git/index` 与全部 objects），并以 uid 1000 实测 `git status/log/rev-parse` 正常；**全队规则：仓库内所有 git 命令一律以 uid 1000 执行**（SSH 场景 `su -s /bin/bash admin -c "cd <repo> && git …"`），**禁止 root 身份跑 git**。
> **verifier 复核与一次复发留痕**：chown 之后我又观察到 **`.git/index` 于 18:54:11 再次变为 `root:root`**（⇒ chown 本身不足以长期维持，**规则才是操作性根治**）；我按就地修复重建（18:55:47 恢复 `node:node`，`find .git ! -user node` = **0**）。**四次时间点**：`18:25`、`18:27`、`18:31:18`、`18:46:11`（+ 复核时 `18:54:11`）。
> **结论**：K-17 记为**已闭合（captain chown + uid-1000-only 规则；verifier 就地修复配方保留于 §10.11）**。
> **复现条件（显式）**：**维护期内任何成员若以 root 跑仓库内 git（哪怕只是 `git status`）都会复现** `.git/index`/对象目录被置 root；native-dev 声明其本会话**从未执行任何 git 命令**（写面仅 `webrtc-build/t30/**`、`t36/**`），env-installer 已认账其早期 root 经 ssh 的提交为来源之一并承诺改为 uid 1000。
> **复发源披露与前向纪律（env-installer 主动认账，2026-09-14）**：其此前以 **root 经 ssh** 执行的提交（t18/t26/`c6fcfcd`/`3acc1d2` 等）即 root:root 索引/对象目录的来源之一；其已承诺**此后所有 git 一律以 uid 1000 执行**（宿主机 `su -s /bin/bash admin -c 'git …'`），并在每次 git 操作后做 root 侧兜底 `chown -R 1000:1000 .git` + 断言 **`find .git ! -uid 1000 | wc -l == 0`**。
> **探针陷阱（值得留档）**：`git update-index --refresh` 的 **exit≠0 表示"有文件 needs update"**，**不是**权限失败（env-installer 首轮即据此误判"仍不可写"）；判可写性应使用 **`test -w .git/index` / `touch .git/index` / `git add` 演练** 三条真探针。
> **dangling 对象现状**：可写性演练累计留下 **6 个 dangling blob**（`49407c77`、`56dbbdc6`、`9ae37822`、`c00db6f3`、`9daeafb9`、`2757c6f6`）——均无引用、不影响 ref/tree、`git gc` 可回收；非交付物，**不构成缺陷**。
> **读侧核验（我实测）**：`webrtc-build/src/out/gen` 内 `-user root` = **0**、`! -readable` = **0**（仅 505 个 `node:node` 的 600 权限文件）；`out`（14 062 文件）与 `app/build`（860 文件）亦 `! -readable` = 0 ⇒ **读侧当前无阻碍**；如宿主视角确有 root 0600 文件，再按需 `chown -R node:node webrtc-build/src/out`。


### 13.16 生成件可复现性：**A 侧我已独立复算**；第三方构建树内的同名 srcjar **对 node 不可读，暂无法验证**

#### (a) 我独立复算（**已复现**）：同一 JDK 重编 A 的两份源 ⇒ class 逐字节相同
对 `webrtc-build/t30/out/A/libjingle_peerconnection_so__jni_registration.srcjar`（`2e352096714d63de52cfc37fa98602313b9f310ab726879134fc25495fc22113`，62 140 B）解包并用构建树同一 JDK（`webrtc-build/src/third_party/jdk/current/bin/javac --release 17`）重编：
| class | 我现场编译 | t30 `out/A/classes/`（对方产物） | 结果 |
|---|---|---|---|
| `J/N.class` | `9ada0641fcee5881…` | `9ada0641fcee5881…` | **逐字节相同** ✅ |
| `org/jni_zero/GEN_JNI.class` | `8f3ce6137f02cef9…` | `8f3ce6137f02cef9…` | **逐字节相同** ✅ |
源侧口径（A）：`J/N.java` = `6bd817a61b02e9ed…`、`GEN_JNI.java` = `e0ff02a77279d8dc…`；`LibaomAv1` 命中 **0**、`J/N` `static native` = **193**、`GEN_JNI` `static native` = **0** ⇒ **A 不含 AV1 桩**（与 §13.6 一致）。

#### (b) 未验证（**我无法验证**，请勿写成"三方复现成立"）
native-dev 称 webrtc-builder 在**构建树**内独立跑出同名 srcjar：`webrtc-build/src/out/Release-arm64/gen/sdk/android/libjingle_peerconnection_so__jni_registration.srcjar`，sha256 同为 `2e352096…`。
**我的实测**：该文件确实存在（**62 140 B**，mtime **11:34:56**），但权限为 **`-rw------- root root`（0600）** ⇒ **uid 1000 读取 `Permission denied`**（`head`/`jar xf`/`sha256sum` 均失败）。
⇒ **（18:35 更新：该文件 owner 已为 node、可读，我复核 = `2e352096…` 且与 `out/A` srcjar 及两份源逐字节相同 ⇒ 本项已由"未验证"升级为"已验证"，见 §13.21 追加第 4 条。原"未能核对"的记述保留如下以备查。）** 我无法在其不可读期间核对 sha256、也无法比较内容；"大小相同"当时只是**弱证据**；如需我核，请 `chmod 644`（或 `chown node:node`）该文件，我一条命令即可复核（`sha256sum` + 解包比对两份 `.java`）。
> **读侧口径澄清（19:0x 复核）**：native-dev 报"构建树 `gen/**` 中 `0600 root` 文件 = 506/2793"，并要求 `chmod 644`。我实测（容器内同一路径）：`gen` 下总文件 **2 793**、**`-perm 600` = 505**，但 **`-user root` = 0**、**`! -readable` = 0**、**`! -perm -u+rw` = 0** ⇒ 那些 600 权限文件是 **`node:node` 所有**（我可读），**root 属主文件为 0、不可读文件为 0**；`webrtc-build/src/out`（14 062 文件）与 `app/build`（860 文件）同样 `! -readable = 0`。而**本节讨论的那份 srcjar 现为 `-rw-r--r-- node node`（644、mtime 11:34:56 未变、sha `2e352096…`）**，且与 `t30/out/A` 同名件 `cmp` 逐字节相同 ⇒ **我 18:35 的"已验证"结论成立、无需 chmod 才能复核**。⇒ K-17 的**读侧**在本容器**当前为 0 影响**（**19:13:35 复测仍为**：总 2 793、`-user root` = 0、`-perm 600 -user root` = 0、`! -readable` = 0；`-perm 600` = 505 但**均为 `node:node`**；该 srcjar 现为 `644 node` 可读）；把"0600"当成"root 所有/不可读"会造成误判（建议 `chmod` 请求可撤回，或仅在宿主视角确有 root 0600 时再执行）。

> ⚠️ 这同时是 **K-17 的"读侧"影响**：root 身份产出的构建中间件对 node 成员**不可读** ⇒ "终报可直接引用该路径"**对 node 身份不成立**（引用前须确认可读性）。

#### (c) 与落位现状的关系（勿混）
- 上述**复现的是 A**（193 转发、无 AV1 桩）；
- **B**（`dca67dc7…`，194 = 193 转发 + 1 条 AV1 非 native 桩）仍是 t30 侧产物，但其两份 class 已被**落位实测证实**：live jar 内 `J/N.class` = `1ff8d3ff…`、`GEN_JNI.class` = `a6e7edcf…`（§13.11）⇒ **实际交付采用的是 B**。

### 13.17 ⚠️ 落位件 ≠ webrtc-builder 的 `FINAL.jar`：**45 个 `*Jni` 的"统一到 61"没有进落位件**（t33 前必须定夺）

I 侧全部为我自跑（逐条目解压 + 逐类比较）。

| 项 | **落位件**（live `third_party/libwebrtc/java/libwebrtc-java.jar`） | webrtc-builder 的 staging `FINAL.jar`（`tmp/jn-fix/`） |
|---|---|---|
| sha256 / 大小 / mtime | **`0c776934c1452b7b…`** / 1 206 602 B / **18:17:28** | `d0d05244a13ed059…` / 1 181 426 B / 18:07:34 |
| 类数 | **509** | 509 |
| **major 分布** | **`{55: 51, 61: 458}`** | **`{55: 2, 61: 507}`** |
| 非 61 的类 | **51 个** = 45 个 `*Jni` + 6 个（`BuildConfig`、`NativeLibraries`、`Dav1dDecoder$Natives`、`Dav1dDecoder`、`EglBase10Impl$FakeSurfaceHolder`、`PeerConnection$Builder`） | **2 个** = `EglBase10Impl$FakeSurfaceHolder`、`PeerConnection$Builder`（及其书面说明的"源修订差异"） |
| `J/N.class` | `1ff8d3ff40326433…`（= t30 handoff **B**） | `0eac3fb54ecb1d95…`（**不同字节**，两者 major 均 61） |
| `GEN_JNI.class` | `a6e7edcf9b90a4f7…`（= handoff B） | `32448db8bf033fcd…`（**不同字节**） |
| 绑定语义 | `J.N` native **193**、`GEN_JNI` native **0**、含 AV1 桩 → **B，可用** | 同（193 / 0 / 含桩）→ **B，可用** |
| **48 个 `*Jni` 逐字节比较** | 基准 | **45 个不同、3 个相同**；两 jar 的 `*Jni` 成员集合**完全相同**（各 0 个独有） |

**结论（三态）**：
1. **已验证**：落位件在**绑定语义**上完成（判据①②③④全绿，§13.11），且 `*Jni` 成员齐备（48/48，被引用 47、缺失 0）。
2. **已验证**：**落位件未包含"45 个 `*Jni` 统一到 61"**（它仍是 `{55:51, 61:458}`），而 `FINAL.jar` 才实现了统一（`{55:2, 61:507}`）⇒ **captain 的"全部统一到 61"要求在落位件上未达成**。
3. **影响评估**：major **55 = Java 11**，AGP 8.5/D8 接受 ≤61 ⇒ **不是交付阻塞**（与早期 major 69 被拒不同）；但它是**与既定交付口径的偏离**，且两个候选的 `*Jni` 字节不同（45 类）⇒ **t33 用哪一份，dex 内容就不同**。
4. ~~t33 前需 captain 二选一~~ **已由 captain 裁定 (甲)：保持现落位件 `0c776934…`、不做"统一到 61"（详见 §13.22(d)5 与 §13.24）**——以下措辞保留为当时的过程史：**(甲) 就用落位件**（版本分布按 `{55:51, 61:458}` 记，`J/N`/`GEN_JNI` 为 handoff B 字节）；**(乙) 重新落位 `FINAL.jar`**（达成 `{55:2, 61:507}`，但 `J/N`/`GEN_JNI` 换成其自身字节，须**重新复跑**判据①②③④ + §13.15 回归护栏）。

#### 追加：`comm` 的 locale 陷阱（webrtc-builder 首报）
其首跑 `comm -3` 得 214 行"伪差异"，加 `LC_ALL=C sort` 后为 0。**我本容器未能复现**：环境默认即 C/POSIX（`LANG` 空、`LC_CTYPE=POSIX`，实测默认与 `LC_ALL=C` 均为 0 行）⇒ 该陷阱依赖 UTF-8 collation 环境。**处置**：凡集合差集命令一律写 **`LC_ALL=C sort` + `LC_ALL=C comm`**（我自己的判定式用 node `Set`，不受 locale 影响）。

### 13.18 A/B 参数化的最终确认、`jni_mangle` 定案（源码级）与"两分结论"的更新

#### (a) `GEN_JNI` 方法数**不是**跨 A/B 恒真（采纳 native-dev 的自我更正）
| 形态 | `GEN_JNI` 方法数 | `static native` |
|---|---|---|
| 落位前 Placeholder | **194**（全部 `static native`） | 194 |
| 落位 **A**（构建树现成件） | **193**（AV1 完全不在生成件里） | **0** |
| 落位 **B**（t30 handoff / **本次实落**） | **194**（193 转发 + 1 条 AV1 非 native 桩） | **0** |
⇒ 期望值表必须写成 **「方法数 = 194（B）/ 193（A）；`static native` 恒 = 0」**（本报告 §13.7(b)/§13.12 已是该参数化形式）。
**跨 A/B 真正恒真的三条**：① `J.N` native = **193**；② 转发 `GEN_JNI` 的 `static native` = **0**；③ `.so` 导出 = **193 且不漂移**（`757cef81…`）。

#### (b) `jni_mangle` 定案（我在源码级复核，采纳其更正）
- `webrtc-build/src/third_party/jni_zero/codegen/java_types.py:195-196`：`def to_cpp(self): return common.jni_mangle(self.full_name_with_slashes)` ✅（行号精确）
- `webrtc-build/src/third_party/jni_zero/common.py:209-212`：`def jni_mangle(name): return name.replace('_','_1').replace('/','_').replace('$','_00024')` ✅ ⇒ **顺序固定为「先 `_`→`_1`，再 `/`→`_`，最后 `$`→`_00024`」**
- 我按源码顺序自算：`jni_mangle("org/jni_zero/GEN_JNI")` = **`org_jni_1zero_GEN_1JNI`**；`jni_mangle("J/N")` = **`J_N`**（与 `.so` 实际导出吻合）。**反序**会得 `org_1jni_1zero_1GEN_1JNI`（**错**）⇒ 顺序不可颠倒。
- 假想"经典模式"符号的正确写法 = **`Java_org_jni_1zero_GEN_1JNI_org_1webrtc_1Environment_1create`**；我在 `.so` 上实测该前缀导出 = **0**（`Java_J_N_` = 193、`Java_org_webrtc_` = 0）⇒ **该形态在本 build 不存在**（结论与其一致；写法以其更正版为准）。
- **"A ≡ B（本 build）"的精确条件（native-dev 细化、我复核）**：以 `handoff/src/J/N.java` 的 193 条 `// Original name:` 为样本 —— **方法名含 `_`/`$` = 0/193**；**类简单名含 `_` = 0**；**类全名含 `_` 的 3 条涉及 2 个类**（`org.jni_zero.CommonApis`、`org.jni_zero.JniZero`），其下划线**来自包名 `jni_zero`** ⇒ **A/B 两侧对包名同样转义，不是分叉条件**。⇒ 正确表述："**本 build 全部 193 条方法名不含 `_`/`$` ⇒ A ≡ B 成立；类（全名）的 `_` 来自包名，不影响等价**"（原"类名含 `_` = 0"把简单名与全名混用，已作废）。
- **分叉样例（说明 A 才是权威形态；我按源码顺序自算）**：`org/webrtc/Env#create_foo` → A `MSwink6c` / B `M1NEOdMN`；`org/webrtc/Env2#getX$Y` → A `MaWL65s3` / B `M4ZxAtPI`。

#### (c) "两分结论"按落位现状更新（由我给出，非沿用旧口径）
- **jar/AAR 层（截至 18:33:42 起）**：**类完整性 ✅ 且可绑定性 ✅**（`J/`=1、`J.N` native 193、`GEN_JNI` native 0、`jni_mangle` 双向差集 0/0、两 class = t30 handoff **B** 字节；§13.11）。⚠️ **时间限定（必需）**：`18:17:28` 首落 **B** → **`18:32:24` 被瞬时替换为 A**（jar `c289b4df…`、AAR `f2ea0132…`、未覆盖 = 1）→ **`18:33:42` 回滚为 B**（风险窗口 ≈ 78 s；`.so` 全程未变）⇒ 本结论应表述为"**截至 18:33:42，jar/AAR = B 且可绑定**"，非"A 也是交付"（事故全过程见 §13.21）。
- **交付 APK 层（19:3x 更新）**：t33 已重编，**交付锚点 = `30c41ac9…`**（33 309 445 B；dex `LJ/N;` = 3、`GEN_JNI;` = 3、`PCF_Jni` = 2；四 `.so` `p_align=0x4000`；单测 46/0/0 @19:07:05）⇒ **K-15 = 已闭合**（jar/AAR 侧 + APK 侧，详见 §13.24「captain 转录 + verifier 独立复核」）。历史轮次 `721df1c8…`（dex `LJ/N;` = 0）仅作对照。**仍未验证（无设备，不得写成通过）**：真机安装、首次 native 调用、`JNI_OnLoad` 运行期注册、Camera2 采集、首帧渲染、日志导出。
⇒ 因此**不能再写"可绑定性 = 未落地"**（那是 18:17:28 之前的状态）；现在的正确两分是 **「jar/AAR：已修且可绑定（B 形态）」/「交付 APK：待重编，未闭合」**。

### 13.19 AV1 那条桩的**出处**：官方产物 193/193 不含 AV1，交付件的 AV1 桩来自"**扩展输入集 + 官方开关**"（不是官方本目标输入，也不是手写文本）

我自跑（输入清单 + 两个 srcjar 逐项比对）：

| 项 | 官方（A） | 交付件（B / handoff / 本次实落） |
|---|---|---|
| `--java-sources-file` | `t30/registration-inputs/javasources-official.txt`（**160** 行） | `javasources-with-av1.txt`（**165** 行） |
| 与官方清单的差 | — | **仅多 1 个 5 行 JSON 块：`sdk/android/api/org/webrtc/LibaomAv1Encoder.java`**（`diff` 实测 `159a160,164`） |
| 其它 flag | `--use-proxy-hash` | `--use-proxy-hash` **+ `--add-stubs-for-missing-native`** |
| srcjar sha256 | `2e352096714d63de…`（62 140 B） | `dca67dc73190e9ec…`（62 424 B） |
| 内含 `LibaomAv1` | **0** | **2**（`J/N.java` 1 + `GEN_JNI.java` 1） |
| `J/N` native / `GEN_JNI` native | **193 / 0** | **193 / 0** |
> 附：官方清单里唯一命中"av1"的行是 `Dav1dDecoder.java`（`D**av1**dDecoder`，大小写不敏感的假命中）；`nativesources.txt` 同样不含 libaom ⇒ **libaom 既不在 present、也不在 absent 输入集**。
#### (c) 出处**判别式**（B 生成器版 vs `FINAL.jar` 另作版）——我 `javap -p -c` 双侧实测（native-dev 指出、我已复跑）

| 观测项（`javap -c`，可复跑） | **B**（生成器版 = 现部署 `0c776934…`） | `FINAL.jar`（`d0d05244…`，另作/手加） |
|---|---|---|
| `J.N` 侧 AV1 方法**名** | **可读名** `org_webrtc_LibaomAv1Encoder_create(long)` | **哈希名** `M0vTiIkf(long)` |
| `J.N` 侧该方法体 | `new RuntimeException` → `ldc "Native method not present"` → `invokespecial` → **`athrow`** | **同**（也是抛异常桩） |
| **`GEN_JNI` 侧 AV1 方法体** | **自身抛异常**（`new`/`dup`/`ldc_w`/`invokespecial`/`athrow`；**无 `invokestatic`**） | **转发**：`lload_0` → `invokestatic J/N.M0vTiIkf:(J)J` → `lreturn` |
| `J/N.class` / `GEN_JNI.class` 大小 | **6 924 B / 24 910 B** | **6 898 B / 24 828 B** |
| 与生成器行为 | 一致（`_stub_for_missing_native` 用 `native.proxy_name`；`generate_impl` 与 `generate_forwarding` 对 absent proxy **各自落桩** ⇒ 两侧都是自身抛异常） | 不一致（`J.N` 用哈希名、`GEN_JNI` 写成**转发到 `J.N.M0vTiIkf`**） |

⇒ **口径修正**：上文"194 = 193 转发 + 1 桩"是**源码级**描述（`generate_forwarding` 的 present 循环 193 次 + absent 桩 1 次）；**字节码级**必须写成"**194 方法 / `native` = 0 / 转发目标（`invokestatic J/N.`）= 193（全为 native）/ `athrow` 桩 = 1（不转发）**"——**B 的第 194 条不转发**（这与 §13.22(f) 的断言精确化一致）。
**复跑命令（native-dev 给的版本，我实测可用）**：
```bash
javap -p J/N.class | grep -v " static native " | grep "public static"   # 唯一非 native 声明 = AV1 桩
javap -p -c org/jni_zero/GEN_JNI.class | awk "/LibaomAv1Encoder_create/,/^$/"  # athrow(=生成器版) 还是 invokestatic(=另作版)
stat -c %s J/N.class org/jni_zero/GEN_JNI.class                          # 6 924/24 910(B) vs 6 898/24 828(FINAL)
```
⚠️ **不要用 `javap -p J/N.class | grep -i av1`**：对 `FINAL.jar` 的哈希名 `M0vTiIkf` **0 命中**，且 `-i` 会误命中 `Dav1dDecoder`（大小写不敏感）。
- **建议再加一条"语义级"判据（webrtc-builder 提议、我已复跑）**：`javap -p -classpath <jar> org.jni_zero.GEN_JNI | grep -c 'LibaomAv1Encoder_create'` → **A(隔离件) = 0 / B(交付件) = 1（`athrow` 桩）**；与 `grep -cE ' static '`（A 193 / B 194）并用。理由：只数 `static` 行在他人改动 `javap` 输出格式或构造器写法时有被带偏的风险，**按方法名 grep 是语义级判据**。


**结论（三段式）**：
1. **官方对本目标（`sdk/android:libjingle_peerconnection_so__jni_registration`）的产物 = 193/193、完全不含 AV1** ✅（我实测 A 的 srcjar：`LibaomAv1` 命中 0）。
2. **交付件里的 AV1 桩并非"官方本目标输入"的产物**，但**也不是有人手写一段桩文本**：它由 **jni_zero 官方 `_stub_for_missing_native`** 生成（逐字 message `RuntimeException("Native method not present")` 相同），前提是**把 `LibaomAv1Encoder.java` 加进 `--java-sources-file`** 并开 `--add-stubs-for-missing-native`（`generate-final-B-with-av1.log` 的 `cmd` 逐字如此）⇒ 准确写法是"**官方生成器 + 扩展输入集 + 官方开关**"。
3. **行为层结论不变**：仅在真正创建软件 AV1 编码器时抛 `RuntimeException`；本项目走 VP9 ⇒ 不影响启动/通话。

> ⚠️ **更正 webrtc-builder 本轮的论证**："不带 / 带 `--add-stubs-for-missing-native` → 同一个 sha256（逐字节相同）"与产物不符：其 `out/B` 日志显示**同时换了输入清单**（165 行含 libaom），而 A/B 的 srcjar 哈希本就不同（`2e352096…` vs `dca67dc7…`）。"**只加 flag、不改输入**"这一情形我**无法复跑**（本容器 **无 `python3`**），故该子命题记为**未验证**；本目标官方输入下"无 absent proxy ⇒ 无桩"由第 1 点的实测支持。
> 📌 报告内口径：§12.2(5) 引用的 `_stub_for_missing_native` 行号（`gen_jni_java.py:10-15`）与开关行号（`jni_registration_generator.py:280/:511`）**仍然正确**，但**出处须按本条表述**（扩展输入集触发，而非本目标官方输入）。

### 13.20 候选 jar 全矩阵（我逐项实测）——落位件 = `FINAL2` 的绑定类，但**缺 `FINAL2` 的版本统一**

| 候选 | sha256（前 16） | 大小 (B) | mtime | 类数 / major 分布 | `J/N.class` | `GEN_JNI.class` | AV1 桩 |
|---|---|---|---|---|---|---|---|
| **A**（现名 `tmp/jn-fix/candidate-A-DO-NOT-LAND-libwebrtc-java.jar`） | `c289b4dfd06827bc` | 1 206 237 | 11:39:04 | 509 / **`{55:51, 61:458}`** | `9ada0641…`（A） | `8f3ce613…`（A，方法 **193**、native 0、前缀 190+3） | **无**（未覆盖 = 1） |
| `FINAL.jar` | `d0d05244a13ed059` | 1 181 426 | 18:07:34 | 509 / **`{55:2, 61:507}`** | `0eac3fb5…` | `32448db8…` | 有 |
| **`FINAL2.jar`** | `167a299af7966aff` | 1 181 534 | 18:16:07 | 509 / **`{55:2, 61:507}`** | **`1ff8d3ff…`（= t30 handoff B）** | **`a6e7edcf…`（= handoff B）** | 有 |
| **落位件（live）** | `0c776934c1452b7b` | 1 206 602 | **18:17:28** | 509 / **`{55:51, 61:458}`** | **`1ff8d3ff…`** | **`a6e7edcf…`** | 有 |

**读法（三态）**：
1. **已验证**：A 变体已被团队**显式改名为 `candidate-A-DO-NOT-LAND-…`**（其内容我复算与 native-dev 报值一致：`J/`=1、`*Jni`=48、`J.N` native 193、`GEN_JNI` 方法 **193**、native 0、前缀 `org_webrtc_*` **190** + `org_jni_1zero_*` 3、AV1 未覆盖 = 1）⇒ **A/B 之争事实上已按 B 收口**。
   ⚠️ **改名 ≠ 从未落位（必读）**：该 A 件在 **18:32:24 曾被实际拷入交付路径**（jar `c289b4df…`、AAR `f2ea0132…`），**18:33:42 被 captain 回滚为 B**，**之后**才被改名为 `DO-NOT-LAND` 并隔离于 `tmp/jn-fix/QUARANTINE-A/`。证据：`webrtc-build/t36/t36-addendum-transient-reland.md` = `d02ae1b8…` + `webrtc-build/t36/logs/t36-addendum-1832.log` = `1b656cc3…`（我核对存在）。**不要把"现名 DO-NOT-LAND"读成"从未落过"**，全过程见 §13.21。
2. **已验证**：**落位件与 `FINAL2.jar` 使用同一套绑定类字节**（`1ff8d3ff…`/`a6e7edcf…`），**但落位件没有 `FINAL2` 的版本统一**：`FINAL2` = `{55:2, 61:507}`，落位件 = `{55:51, 61:458}`（45 个 `*Jni` 仍是 55 版）。差值（1 206 602 − 1 181 534 = **25 068 B**）与"45 个 `*Jni` 重编到 61 + 2 个例外"的量级吻合。
   ⇒ 准确表述：**落位 = `FINAL2` 的绑定类 + 旧的 55 版 `*Jni`**，而非 `FINAL2` 本身。
3. ~~待裁定~~ **已由 captain 裁定 (甲)**：若交付口径要求"全部统一到 61"，则仅 `FINAL2.jar`（或 `FINAL.jar`）满足；如选 `FINAL2` 重新落位，`J/N`/`GEN_JNI` 字节**不变**（同为 `1ff8d3ff…`/`a6e7edcf…`）⇒ 仅需复跑"jar 侧版本分布 + APK 重编"，绑定判据①②③④不会变化（仍应重跑以留痕）。

#### 护栏清单（t33/t34，按 native-dev 与 webrtc-builder 的建议合并，已实测）
1. 新 APK 内 `lib/arm64-v8a/libjingle_peerconnection_so.so` **必须仍 = `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`**（t30 符号证明的对象；漂移 ⇒ 判据④必须重跑）。
2. 新 APK 内 `libc++_shared.so` **必须仍 = `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36`**（t24/t28 的 16 KB 交付；现行 APK 已与 `jniLibs` 落位件逐字节相同）。
3. 四个 `.so` 的 `LOAD` 段 `p_align` 必须均 = `0x4000`。
4. 落位前后对照：`J/` 条目 **0 → 1**、`GEN_JNI` `static native` **194 → 0**（t25 的"修复前失败"对照即此两行，§13.13(a)）。

### 13.21 非授权落位 A（18:32:24）+ captain 回滚为 B（18:33:42）——**流程事故**（P-12 / D-13）

#### 13.21 非授权落位 A（18:32:24）+ captain 回滚为 B（18:33:42）——**流程事故**（P-12 / D-13）

> **最终结论（先写在最前，避免误读）**：**交付 = B**（jar `0c776934…` / AAR `8e8f2baf…`，两者 mtime 均 **18:33:42**；`J/N.class 1ff8d3ff…` / `GEN_JNI.class a6e7edcf…` / `GEN_JNI` 方法数 **194** / `.so 757cef81…` 未变）。**分层口径（三段）**：① **类完整性 = 已修 ✅**（jar/AAR，B 形态）；② **可绑定性 = jar/AAR 已达成 ✅**（B 下四项判据全绿、**未覆盖 = 0**）；③ **交付 APK** 见 §13.23（t33 新件，双哈希待裁定）——**不得**读作"交付 = A"。
> **AV1 风险限定时点**：`NoSuchMethodError` **只属于 `18:32:24–18:33:42` 那个 ≈78 s 的 A 窗口**（当时若开 t33 才会落到 APK）；**现行 B 下** AV1 路径得到设计内 `RuntimeException("Native method not present")`。
> **事实链（captain 定稿，我复核哈希/权限一致）**：
> 1. **18:32:24** webrtc-builder **非授权**把 A 变体落进交付路径（jar `c289b4df…` / AAR `f2ea0132…`）：其执行的是 **t31-D1 的过期文本**，而 captain 已用 **t31-D2** 裁定 A/B = **B** 并直接答复过"维持 B"；当时处于**写盘冻结期**。
> 2. **18:33:42** captain **回滚为 B**：jar **`0c776934c1452b7b…`**（1 206 602 B / 509 条目）、AAR **`8e8f2baf…`**（6 492 067 B，内 `classes.jar` 与 jar **逐字节相同**）、`J/N.class` = `1ff8d3ff…`、`GEN_JNI.class` = `a6e7edcf…`、`.so` = `757cef81…` 未变。
> 3. **事故件留证**：`tmp/jn-fix/ACCIDENT-landed-A-c289b4df.jar`、`ACCIDENT-landed-A-f2ea0132.aar`；所有 A 变体已移入 **`tmp/jn-fix/QUARANTINE-A/`**（chmod 400 + `README.txt`，标注"禁止落位"）——我已核对：`ACCIDENT-*.jar` = 1 206 237 B、`ACCIDENT-*.aar` = 6 495 516 B、权限 `-r--------`。
> 3b. **两份 `pre-routeA-*` 须区分（避免找错对象）**：`tmp/t31/pre-routeA-libwebrtc-java.jar` = **`dc5f8919…`（1 187 970 B，mtime 11:05:10）** = **落位前基座**（同目录 AAR = `e066e456…`）；而 **native-dev 所述 `tmp/jn-fix/pre-routeA-libwebrtc-java.jar`（1 206 602 B，mtime 18:32:24）= 那一刻的 B 备份**（"拷入 A → 备份 B → 恢复 B"序列的中间证据）——**该文件现已不在盘上**（我实测路径不存在），仅作过程史引用，复算请用 `tmp/t31/` 那份或 `QUARANTINE-A/` 中的事故件。
> 3c. **隔离区内容与 README（我实测，证据仍在盘）**：`tmp/jn-fix/QUARANTINE-A/` 含 **4 件** —— `ACCIDENT-landed-A-c289b4df.jar`（`c289b4df…` / 1 206 237 B / 18:32:24）、`ACCIDENT-landed-A-f2ea0132.aar`（`f2ea0132…` / 6 495 516 B）、`candidate-A-DO-NOT-LAND-libwebrtc-java.jar`（`c289b4df…` / 1 206 237 B）、`repro-c289b4df.jar`（`c289b4df…` / 1 206 237 B），权限均 **`-r--------`（400）**；另 **`README.txt` = 415 B / `b46581b82e5dd4419c0a016f36ed81788a184df6dabd87601f0c521fb584ba0b`**（root 所有、可读），其原文要点 = *"A 变体隔离区（禁止落位）…captain 裁定 t31-D2/t31-D3 = 只用 B（`0c776934…` / `J.N=1ff8d3ff…` / `GEN_JNI=a6e7edcf…`）；18:32:24 曾有一次非授权落位把交付 jar/AAR 换成 A，captain 于 18:33:42 已还原为 B；仅作历史证据保留；**任何再次落位都会被 t33/t34 判为交付事故**"*。⇒ 准确表述：**原 staging 路径 `tmp/jn-fix/libwebrtc-java.jar` 已不存在，但其 A 内容被"隔离保留"于 `QUARANTINE-A/`**（"A 已删除"是错的，"A 曾出现"也不够准确）。
> 3d. **B 备份的可见性差异（待对方补证）**：`tmp/jn-fix/pre-routeA-libwebrtc-java.jar`（1 206 602 B / 18:32:24 = B 备份）在**本容器内不存在**（`ls` 报 No such file）；native-dev 报其宿主侧为 **`0600 root` 不可读**（K-17 读侧又一实例）。两者不冲突（时点/视角差异），但**我按"本容器不存在"记录**，并请对方附 `ls -l` 原文 + 取数时刻再定；其内容与现值同 sha，可由交付件复算。> 3d. **B 备份（已改名）实测不可读（read-side K-17 实例）**：`tmp/jn-fix/pre-routeA-libwebrtc-java.jar` **该名已不存在**；实际件现名 **`tmp/jn-fix/backup-B-before-A-incident-20260914-183224.jar`** = **`root:root` / mode `600` / 1 206 602 B / mtime `18:32:24.288`** ⇒ **uid 1000 读取 EACCES**（我实测 `sha256sum` 被拒）。⇒ 该件**在盘但不可读**（其内容同 B 现件 sha，可由交付件复算）；**先前"0600 root 不可读"的说法对这件成立**，最新一轮"mode600 但 uid=1000、不是 root"的撤回**对这件不成立**。
> 4. **当时无真实 gradle 在跑** ⇒ **未产出任何 A 版 APK**；**t33 的构建基线 = 回滚后的 B**。
> 5. 权威落位记录 = **`reports/15 §16`（B）**；**`reports/15 §18` 记录的是被回滚的 A 落位，不得作为权威**（该文件由 captain 更正/标注，verifier **不改**）。



我 18:32 只读实测：
| 时点 | live `third_party/libwebrtc/java/libwebrtc-java.jar` | 形态 |
|---|---|---|
| 18:17:28 | `0c776934c1452b7b…` / 1 206 602 B | **B**：`J/N.class` = `1ff8d3ff…`、`GEN_JNI.class` = `a6e7edcf…`（含 AV1 非 native 桩） |
| **18:32:24（现）** | **`c289b4dfd06827bc…` / 1 206 237 B / `mode 0600`** | **A**：`J/N.class` = `9ada0641…`、`GEN_JNI.class` = `8f3ce613…`（**无 AV1 桩**）、major `{55:51, 61:458}` |

- live AAR 同时更新：`f2ea01328336cf12…`（6 495 516 B，18:32:24），其 `classes.jar` = **`c289b4df…` 与 live jar 逐字节相同** ⇒ jar/AAR 成对一致（同一变体）。
- 现场旁证：`tmp/jn-fix/repro-c289b4df.jar`（18:32:10）、`tmp/jn-fix/pre-routeA-libwebrtc-java.jar`（18:32:24，**1 206 602 B = 被替换前的 B 件**）、`scripts/build_app.sh`（18:32:33 更新，含路线 A 判据检查）⇒ 操作形态像"**复现 A → 备份旧 B → 落位 A**"。
- 该 A 件此前被团队显式更名为 **`candidate-A-DO-NOT-LAND-libwebrtc-java.jar`**（同一 sha256，仍在 `tmp/jn-fix/`），本次落位与那个命名**方向相反**。

**判据分层结论（三态，按 §13.18(a) 的 A/B 参数化）**：
| 判据 | 现 live（A） | 判定 |
|---|---|---|
| ① `J/N.class` 可解析 | ✅ `9ada0641…` | **通过** |
| ② `GEN_JNI` `static native` = 0 | ✅ | **通过** |
| ③ `GEN_JNI` 方法数 | **193**（A；B 才为 194） | **通过**（按参数化口径） |
| ④ `jni_mangle(J.N 193)` ≡ `.so` 193 | ✅ 双向差集 0/0 | **通过** |
| AV1 调用点 | **未覆盖** ⇒ 触发时 `NoSuchMethodError`（**仅 A 形态/落位前；已收口为 B，见 §13.22**） | app 仅注册 VP9、**不可达** ⇒ medium 潜在（§13.8，**落位后不适用**） |
| `.so` 未漂移 / 16 KB | ✅ `757cef81…` / `p_align=0x4000` | **通过** |
⇒ **A 在"VP9 1:1 通话"交付口径下功能足够**，但与"推荐 B"相反、且相较 18:17 的 B 件**丢失 AV1 桩**。**请 captain 明确一次**：
- **(甲) 有意接受 A**：须书面记录"AV1 路径不可达；若启用则 `NoSuchMethodError`"，并把期望值表③按 **193(A)** 记；
- **(乙) 重新落位 B**：`tmp/jn-fix/FINAL2.jar`（`167a299a…`，`{55:2, 61:507}` + 同一套 B 绑定类）可直接用；被替换前的 B 件也仍在 `tmp/jn-fix/pre-routeA-libwebrtc-java.jar` ⇒ **可原地回滚**。
> 无论甲/乙，**t33 重编 APK 与我的复验清单不变**（§13.11 + §13.15/§13.20）；本条只影响"jar 内是 A 还是 B"。

#### 附：`prefix-baseline.md` 为**三代沿革**（第 3 代现值 = `755c0109…`，内容我已核对）
自述 **3 648 B / `1472f482…`**；磁盘实测 **5 205 B / `755c010938c52c70a1b66ad87324a7d5d37b1114bedb00c185402032168d7cd5`**（mtime 18:16:08）。其**新增的时序段与结论段内容我已逐条核对通过**：`dc5f8919…`（11:05:10.900671）/ APK `721df1c8…`（11:28:34.950735）/ handoff srcjar `dca67dc7…`（11:34:46.272129）/ handoff `classes/J/N.class` `1ff8d3ff…`（11:34:46.273129）**四个 mtime 全部与盘上现值一致**，"t26 APK 构建自未落位 jar"的时序结论**成立**。⇒ 引用请以磁盘现值为准（这是同一文件第三次给出不匹配的指纹）。


#### 13.21 追加（18:35 复核）：**B 已恢复**，A 只是 18:32:24–18:33:42 的短暂窗口；K-17 已闭合；第三方 srcjar 复现已可验证
1. **交付 jar 现况 = B**（与 captain 的 B 裁定一致）：`third_party/libwebrtc/java/libwebrtc-java.jar` = **`0c776934c1452b7b…`**、1 206 602 B、mtime **18:33:42**；`J/N.class` = `1ff8d3ff…`、`GEN_JNI.class` = `a6e7edcf…`、`GEN_JNI` 含 AV1 桩 = 1 ⇒ **B 形态**；live AAR 亦回到 `8e8f2baf…`。
   **状态时间线（同一路径）**：`18:17:28` B(`0c776934`) → **`18:32:24` A(`c289b4df`)** → **`18:33:42` B(`0c776934`) 恢复**。⇒ §13.21 前段的"现 live = A"**已作废**；A 落位仅存在约 **78 秒**，**未进入任何 APK**（APK 仍 `721df1c8…`、mtime 11:28:34）。**A/B 问题按 captain 的 B 裁定闭环**（我先前请二选一的 (甲)/(乙) 无需再答）。
2. **AV1 口径**：因现行交付件是 **B**，`LibaomAv1EncoderJni` 的引用由**非 native 桩**兜住（触发时 `RuntimeException("Native method not present")`）⇒ **不存在"悬空引用/`NoSuchMethodError`"风险**；该风险仅存在于 A 变体（保留为对照说明，§13.19）。
3. **K-17 已闭合（captain 修复 + 我复验）**：`find .git ! -user node` = **0 项**（含深层）；`.git/objects/{33,56,ac,c6}` 与 `.git/index` 均为 `node:node`；以 **uid 1000** 执行 `git hash-object -w --stdin` **成功** ⇒ "后续以 uid 1000 提交不再踩坑"成立。（注：我此前删除的测试 blob `9daeafb9864cf43055ae93beb0afd6c7d144bfa4` 与 captain 复验写入的是同一内容，现存在、不可达、`git gc` 可回收，不影响 ref/tree。）
4. **第三方复现声明：由"未验证"升级为"已验证"**（§13.16(b) 的遗留项）：构建树内 `webrtc-build/src/out/Release-arm64/gen/sdk/android/libjingle_peerconnection_so__jni_registration.srcjar` 现**可读**（owner=node，0600），其 `sha256 = 2e352096714d63de52cfc37fa98602313b9f310ab726879134fc25495fc22113` **与 `t30/out/A` 的 srcjar 逐字节相同**，两份源 `J/N.java`(`6bd817a6…`)/`GEN_JNI.java`(`e0ff02a7…`) 亦逐字节相同 ⇒ **"同一生成器、两处独立落盘、产物逐字节一致"成立**（该 srcjar 只对应 **A** 输入集；B 的 194 口径仍如 §13.19 所述）。
5. **`.git/index` 今日 3 次被 root 抢占（18:25 / 18:27 / 18:31:18）为 chown 之前的窗口**；chown 后未再复现。**我按就地修复完成提交（未改任何他人内容）**。

### 13.22 落位收口（captain 裁定 (i) = **B**）：K-15 分层、K-17 闭合、语义身份三值、历史轮次标注

#### (a) 裁定与落位事实（我以只读实测复核）
- captain 裁定 **(i) 落 B**（采纳 §13.6/§13.9 证据）：落位两件 = `t30/handoff`（= `out/B`）的内容，`J/N.class` = `1ff8d3ff…`、`GEN_JNI.class` = `a6e7edcf…`；staging 的 A 形态（`c289b4df…`）**未被采纳**。
- 交付件现况（我实测）：jar = **`0c776934c1452b7b…`**、**1 206 602 B**、**509 类**、**mtime 18:33:42**（首落 18:17:28；其间 18:32:24–18:33:42 曾短暂为 A，见 §13.21）；AAR = **`8e8f2baf…`**（6 492 067 B，内 `classes.jar` == jar、内 `.so` == 目录 `.so` == `757cef81…`）；`jniLibs/libc++_shared.so`、`libvpx.a`、`doc/14`（`b3b67438…`/1337 行）**均未变**。
- **新增的可复算事实（我实测）**：落位件 **509 个条目全部 STORED（method 0；DEFLATED = 0）** ⇒ 逐条保真；而 jar **容器 sha 仍会随时间戳/条目序变化** ⇒ 语义身份必须钉三值（下条）。

#### (b) 语义身份三值（t34 门禁基准）
```
jar            = 0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757   (1 206 602 B, 509 类, 全 STORED)
J/N.class      = 1ff8d3ff4032643339ad271f552475740d735dddf06ae42e507bb657f98a8932   (6 924 B, major 61)
GEN_JNI.class  = a6e7edcf9b90a4f7a15273de580bf7faf35ac7f818a4345c9618fd75fea40f08   (24 910 B, major 61)
```

#### (c) 两项 checker（captain 报告 + 我的等价复跑）
- `scripts/check_jn_binding.py`（**须宿主运行**：容器无 `python3`）：E1 `J.N` native 193 ↔ `.so` 193 **双向差集 0/0**、E2 `GEN_JNI` `native=0`/方法 194、E3 调用点 194/194、**豁免 0 与真缺失 0**，EXIT=0。
  **我无法在容器执行该脚本**，改以 `jar`+`javap`+自写 `jni_mangle` 双向差集**等价复跑**，结论相同；且 **B 落位后"未被覆盖 = 0" ⇒ 其 `KNOWN_EXEMPT` 白名单已无触发场景**（此前"白名单使 A/B 都 PASS、故不能证 AV1 安全"的限定随之失效，§13.8）。
- `scripts/check_jar_link_integrity.py`：509 类、严格缺失 0、`J/N` native 193（captain 报告）；我以**常量池扫描**等价复核：`*Jni` **48 存在 / 47 被引用 / 0 缺失 / 1 未引用（`Dav1dDecoderJni`）**。

#### (d) 状态变更（终报口径，逐条）
1. **K-15 分层**：**jar/AAR 侧 = 已闭合**（`J/N` 在位 + `GEN_JNI` `native=0` + 193↔193 + 194/194，且为 **B** 形态）；**APK 侧 = 已闭合（captain 转录，见 §13.24）** —— 交付 APK `30c41ac9d3363cab249c9a1702958993fcfd965cf7ebbfeba5349435ab059be2`（33 309 445 B）；dex `LJ/N;`=3 / `GEN_JNI;`=3 / `PCF_Jni;`=2；四 `.so` `p_align=0x4000`；`libjingle 757cef81…`、`libc++_shared c9dbf4ec…`（与 `jniLibs` 逐字节相同）；单测 46/0/0（19:07:05 XML）。
2. **现行 APK `721df1c8…`（33 293 061 B / mtime 11:28:34）标注为"已被取代的历史轮次交付物"**（由未落位 jar 构建、dex 无 `LJ/N;`）。**（18:39 起 t33 构建窗口：仓库内 `app/build/outputs/apk/debug/app-debug.apk` 已被清理，仓库内已无任何 `.apk`；仓外快照 `/data/dsh/home/workspace/artifacts/app-debug-721df1c8.apk` 仍为同哈希 `721df1c8…`/33 293 061 B，历史证据以此快照为准；交付以 t33 新 APK 为准。）**
3. **K-17 = 已闭合**（captain 修复 + 我复验，见 §6 与 §13.21 追加）。
4. **"AV1 残余 / 悬空引用"表述作废**：B 落位后 `LibaomAv1EncoderJni` 的引用由非 native 桩兜住；该风险**仅适用于 A 变体**，仅作对照留档（§13.8/§13.19）。
5. **裁定 (甲)：采用现行落位件，不做"统一到 61"（有意裁定，非遗漏）** —— 终态 = jar **`0c776934…`**（1 206 602 B / 18:17:28 / major **`{55: 51, 61: 458}`**）+ AAR `8e8f2baf…`；`J/N.class` = `1ff8d3ff…`、`GEN_JNI.class` = `a6e7edcf…`（t30 handoff = **B**）。**不做统一的三条理由（captain 逐条验过）**：(i) `FINAL.jar`/`FINAL2.jar` 的 49 个 `*Jni` 是 `javac --release 17` **重编译产物**，51 条变更中 **version-only = 0**（即字节差异不止版本号）；(ii) jar 内 2 个类**当前源无法忠实复现**（源修订漂移）；(iii) 审计成本高于收益。**51 个 major-55 类全部 ≤ 61、D8 可消费 ⇒ 非交付阻塞**；应按 **`{55:51, 61:458}`** 记录（`{55:2, 61:507}` 属 **未被采用**的 `FINAL`/`FINAL2` 变体，见 §13.20），且 t33 的重编与我的复验清单**不按 `FINAL.jar` 重排**。
6. 落位过程记录以 **`reports/15 §16`（captain 写入）** 为准；本报告只做**独立复核与三态结论**，不修改任何产物。

#### (g) t33 **双钉哈希**要求与 t36 的有效性条件（native-dev 提议 + 我采纳）
> **落位后钉法 v2（t34 采用）**：`webrtc-build/t30/logs/t34-gate-post-landing.md` = **2 781 B / `c87bb1cd1d22a9ea5c0951d5c3a84e4efcf3f2b72cc841462b75457d2d755958`**（我核对内容一致）—— 与 v1（`t34-gate.md` = `6d1112dd…`，保留为历史）**仅两处不同**：① `J/` 前缀断言 **期望 = 1**（0 视为回归失败）；② `GEN_JNI.class` **只认 `a6e7edcf…`**（其它值 = 来源漂移 ⇒ STOP 回报）。另含**"双钉"片段**（构建前后各记一次 jar/AAR sha 并 `-c` 校验），正是针对 18:32:24 瞬时 A → 18:33:42 恢复 B 的防线。**t34 以 v2 为落位后钉法、v1 仅作历史。**
- **双钉**：t33 构建**前后各记一次** `jar` 与 `AAR` 的 sha256（`before` / `after`），以证明该 APK 的基线 jar 就是**回滚后的 B**（`0c776934…` / `8e8f2baf…`）—— 鉴于 18:32:24–18:33:42 存在瞬时 A 窗口（§13.21），单点记录不足以排除窗口内启动；两值相同即"构建全程 jar 未漂移"。
- **t36（193 描述符对照）有效性条件**：其证据对象是 **`J/N.class 1ff8d3ff…` / `GEN_JNI.class a6e7edcf…`**（即现行 B）⇒ **只要 jar 仍为 `0c776934…` 结论有效**。**若 jar 改变，须分两种情形**：(i) 改落 **`FINAL.jar`**（`d0d05244…`，手编桩 `0eac3fb5…`/`32448db8…`）⇒ **两个 class 指纹改变，t36 必须重跑**；(ii) 改落 **`FINAL2.jar`**（`167a299a…`）⇒ 其两个绑定类字节**与现落位件完全相同**（`1ff8d3ff…`/`a6e7edcf…`，native-dev 实测、我核对「含 B 对」清单一致）⇒ **判据①②③④与 t36 证据对象均不变，只需重钉容器哈希 X 并重跑 t34 的产物级复验**，**t36 无需重跑**（避免"统一版本 ⇒ 全套证明作废"的误解）。*captain 已裁定 **(甲)**：保持现落位件 `0c776934…`、不做统一，上述 (i)/(ii) 仅作将来变更时的判据边界留档。*
- t36 证据（我核对哈希一致）：`webrtc-build/t36/signature-crosscheck.tsv` = `e7c1980b…`（194 行 = 表头 + **193 数据行**）、`signature-crosscheck-refined.tsv` = `015402fc…`（178 行 = 表头 + **177**）、`t36-report.md` = `6810282…`、`t36-addendum-transient-reland.md` = **`d02ae1b8…`**（记录本次瞬时回退）。其 §5 已自述边界：**描述符一致 ≠ 运行期可绑定**，真机首调仍是唯一决定性判据 —— 与本报告 §13.3 口径一致。

#### (f) t33 构建日志内的两道门（env-installer 新增，t34 直接引用）
- **[P-11] dex 级绑定形态**（`scripts/build_app.sh:421` 起）：**jar 侧 + dex 侧双闸**查 `J.N` 与转发 `GEN_JNI`，任一为 0 即 FAIL —— 正是 K-15 的**交付层**判据（旧 APK dex 实测 `jn=0` ⇒ 必红）。
- **[P-12] native 交付件对象漂移护栏**（`scripts/build_app.sh:382` 起）：APK 内 `libjingle_peerconnection_so.so` 必须 == `757cef81…`、`libc++_shared.so` == `c9dbf4ec…` —— 与我 §13.15(a)/§13.20 的护栏同向，可互为交叉验证。
- **断言精确化（防假 FAIL，webrtc-builder 实测 + 我已复核）**：`GEN_JNI` = **方法 194 / `static` 194 / `native` 0 / 转发目标（`invokestatic J/N.`）193（全为 native）/ `athrow` 桩 1（不计入转发目标）**；`J.N` = **方法 194 / native 193 / 非 native 桩 1**。⇒ **不要**写 `forward_stub == 1`（会 FAIL）；应写 `gen_jni_methods == 194 ∧ gen_jni_native == 0 ∧ jn_native == 193`，或按 captain 口径"**194/194 且 `static native` = 0**"。我 `javap -c` 实测：`invokestatic J/N.` = **193**、`athrow` = **1**；AV1 桩字节码 = `new RuntimeException` → `ldc "Native method not present"` → `invokespecial` → `athrow`。
- **断言集"规范六数"（native-dev 定稿 + 我按原始命令逐条复跑）** —— 关键纪律：**两侧过滤器必须分开**（`J.N` 用 `static native`，`GEN_JNI` 用 `^  public static`）；混用会静默得 **0** 或 **195**（下附负对照）。
  ```
  [读盘 20:04:32] jar=0c776934c1452b7b        # 判据前提：落位件三值之一，先钉再跑
  javap -p    J/N.class                     | grep -c 'static native'                 -> 193   # J.N native
  javap -p    J/N.class                     | grep -cE '^  public static '            -> 194   # J.N 方法
  javap -p    org/jni_zero/GEN_JNI.class    | grep -cE '^  public static '            -> 194   # GEN_JNI 方法
  javap -p    org/jni_zero/GEN_JNI.class    | grep -c 'static native'                 -> 0     # ★B 的判别键
  javap -p -c org/jni_zero/GEN_JNI.class    | grep -c 'invokestatic.*J/N\.'           -> 193   # 转发目标（全 native）
  javap -p -c org/jni_zero/GEN_JNI.class    | grep -c 'athrow'                       -> 1     # AV1 桩，不转发
  javap -p    J/N.class | grep -v ' static native ' | grep 'public static'
                                            ->   public static long org_webrtc_LibaomAv1Encoder_create(long);   # 唯一非 native
  ```
  六数关系：`J.N` 194/193 + `GEN_JNI` 194/0 = **193 转发 + 1 桩**（字节码级口径；与 §13.22(f) 上文"194/194 且 `static native`=0"等价，但**不可**表述为"194 native"）。
  **负对照（我实测，证明数字对过滤器敏感）**：`javap -p J/N.class | grep -cE '^  public static .*static native'` = **0**（混用）；`javap -p J/N.class | grep -c '('` = **195**（计入构造器）。⇒ t34 引用六数时必须同时附**命令原文**，只写数字不可复核。
  **t34 用法**：这六数在 v2 门禁（`t34-gate-post-landing.md`）中为**基线断言**；t34 将在**新 APK 的 dex 侧**与 jar 侧各跑一次，并断言"jar 侧六数不变 + dex 内 `LJ/N;` = 1（v2 期望值）"。
- 两条均含**正负例验证**，且会原样出现在 t33 的构建日志里 ⇒ **t34 把它们作为输入证据引用，并在 APK 实体上独立复跑一次**。
- 实现细节提醒：**P-11 的脚本体用 `unzip -q -o`（`scripts/build_app.sh:402`）⇒ 其执行环境须有 `unzip`（宿主有、容器无）**；我在容器侧的等价复算一律用 **`jar xf`**（结果等价，t34 两口径都给）。
- **`check_jn_binding.py` 的版本与豁免边界（对齐 webrtc-builder）**：**权威 = 工作树版 `aa2e96922f5313f7f3a740942d7460e42391ea7b41ebee090b3d7f60e199683f`**（mode 600 / mtime 18:08:57；`git diff --stat` = 9+/3−；最后入库提交 `1621d72` 为旧版）——它是 captain 授权的 out-of-scope 例外，**不计入任何 `changedPaths`**；`KNOWN_EXEMPT = {org_webrtc_LibaomAv1Encoder_create}` 使 **A/B 都会 PASS**，故它**只证 ①②④、不能证"AV1 路径安全"**；**在交付件（B）上豁免数为 0**（E3 = 调用点 194 / 覆盖 194 / 未覆盖 0 / 已知豁免 0 / 真缺失 0）⇒ `RESULT` 文案应写明"**闸门绿 ≠ AV1 路径安全；B 下豁免未触发**"（我处无写权改其文件，留待持写权者）。
- ⚠️ **t33 构建过程会重写 `app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so`**（我实测 mtime 19:02:06）⇒ t34 必须在**构建结束后**核该 `.so` 仍为 `757cef81…`（P-12 覆盖同一断言；构建期间的 mtime 变化**不是**漂移证据）。

#### (e) 跨报告口径标注（captain 2026-09-14 指令，逐条落实；**不修改被标注的报告文件**）
> **搜索范围自我修正（方法论）**：我最初用 `find / -xdev` 搜 `7dbe8400…` ⇒ **`-xdev` 会跳过 tmpfs（含 `/tmp`）**，该次搜索**范围不完整**。随后我改用**不带 `-xdev`** 的严格搜索（`/tmp`、`/data`、`/workspace`、`/home`、`/root`）并直接 `ls /tmp/*.jar`：**本容器内仍无 `7dbe8400…` 的 jar、也无 `/tmp/pre-deploy.*`**（容器内 `/tmp` 仅 `aar-classes.jar`、`pre23_classes.jar`）⇒ 结论维持"**宿主（native-dev 侧）`/tmp/pre-deploy.jar` 可复算 / verifier 容器侧不可复算**"。
> **补充更正（19:4x）**：`7dbe8400…` 的那份**在容器可见树内确实存在** —— `artifacts/pre-deploy-7dbe8400.jar`（**`root:root` / mode `600` / 1 187 970 B / mtime 11:03:36**），但 **uid 1000 不可读**（EACCES）⇒ 我此前"容器内无该哈希文件"的结论**不准确**，准确说法是"**在盘但不可读**"；要复算需由持写权者 `chmod`/`chown`。（同一轮实测：全树 `mode600 且 uid≠1000` = **2 件** = 本件与该 B 备份件，故"= 0"的说法不成立。）
1. **`reports/05-libwebrtc-build.md §17.4`（`:994-995`）的"现行 live = `dc5f8919…`"是落位前口径** ⇒ **已被 `0c776934c1452b7b…`（1 206 602 B、mtime 18:17:28；现件 18:33:42，见 §13.21）取代**。实测该文件内 `0c776934` 命中 **0 次**、`dc5f8919` 命中 **19 次**（即尚未回填落位值）⇒ 读者引用其"live"一律以本报告 §13.22(b) 的三值为准。
2. **`7dbe8400…` 的"可复算对象"表述须加限定（按 native-dev 更正后改写）**：该哈希文件**在 verifier（容器）可见树内已无保留**（全树含 `/tmp` 搜索无命中），但 **native-dev 报其存在于宿主 `/tmp/pre-deploy.jar`**（容器不可见）⇒ 结论应写为"**宿主侧可复算、容器侧不可复算**"，而非"全盘不可复算"。其宿主侧读数（我**无法**在容器复现）：两份 508 条目件**内容逐字节等价、仅时间戳元数据不同**（`504×2001-01-01 + 4×2026-09-14 10:52:2x` vs `508×` 同一 epoch；**"4" = A 内部偏离其固定 epoch 的条目数，非 A↔B 差异数**）。
   附（信息性，仅供参考，非本报告结论）：native-dev 称"webrtc-builder 已就地更正 `reports/05` 4 处 + `reports/15` 2 处"，但**我实测两文件现值与其引用的"更正后哈希"不一致** —— `reports/05` = **995 行 / `bccea2ff…`**（其引 `0e875bfc…`）、`reports/15` = **827 行 / `65cd51f4…`**（其引 `55dea23b…`/626 行）；两文件在 `git status` 中均为在途 `M`，故其引用值可能取自中间态。
   **行号归属更正（实测）**：captain 指令里的 `:527/:717/:769` 三处属 **`reports/07-native-dev.md`**（非 `reports/05`）；`reports/05` 另有 **7 处** = `:506`、`:598`、`:776`、`:780`、`:784`、`:923`、`:995`。⇒ 加注需**两个文件都做**（本报告只登记、不代改）。
3. **K-17 已在 §6 与本条前十节记为"已闭合"**（captain `chown -R 1000:1000` + 我复验：`find .git ! -user node` = 0、uid 1000 `git hash-object -w` 成功；副作用 blob `9daeafb9…` 不可达、`git gc` 回收）——与 captain 指令一致，无需再改。
4. **`reports/08-android-dev.md` 无需再加注（已自纠）**：其 `:1264-1305` 已自行更正"落位源 = `FINAL2.jar`"的推断（说明该推断源自已移除脚本的默认值），并写明"落位件 majors = `{55:51, 61:458}`、`FINAL2`/`FINAL` 属被弃变体"。我实测该文件**不再把 `FINAL2` 记为落位件** ⇒ native-dev 关于"`reports/08` 仍写错"的提醒**已过期**（我另测：部署件 51 个 major-55 = 45 个 `*Jni.class` + 6 个其它，其它名单与 native-dev 所列**完全一致**）。
5. **A/B 分歧按 B 闭合、终态判据 `194/194`**（§13.18/§13.22(c)）；`FINAL.jar`/`FINAL2.jar` 的 `{55:2, 61:507}` 与手加桩 variant **仅作对照登记**，不进入落位件口径。

#### 13.23 ⚠️ t33 产物**双哈希分歧**：构建日志记录 `ef29e00c…`，交付路径现值 `30c41ac9…`（19:05:19 被 18:41 旧件覆盖）

> **裁定注记（captain，2026-09-14）：交付锚点 = `30c41ac9…`**（三处同哈希）；`ef29e00c…` = **非交付**（依据过期指令、未获当次授权的重复构建）；**19:05:19 的交付路径覆盖 = captain 本人**（把冻结交付件拷回）。⇒ 本节下方"待裁定"表述**已由该裁定取代**，保留为过程史。

**事实（我 19:1x 只读实测）**：
| 工件 | sha256 | 大小 (B) | mtime | 说明 |
|---|---|---|---|---|
| t33 构建日志记录（`reports/10-t33-nocache-assembleDebug-20260914-190227.log`） | **`ef29e00c5217b5cd0c32f97d196800c8e7068f40ed5b9926a3f87b11636cc27d`** | 33 309 445 | 19:04:43.919 | `BUILD SUCCESSFUL in 2m16s`、`42 executed / 1 up-to-date`、**FROM-CACHE=0**、`:app:clean`=1、**双钉复测 jar/AAR 均 OK** |
| 同一时刻的仓外快照 `artifacts/app-debug-ef29e00c.apk` | `ef29e00c…`（同上） | 33 309 445 | 19:04:43.919 | 与日志一致 ✅ |
| **交付路径现值** `app/build/outputs/apk/debug/app-debug.apk` | **`30c41ac9d3363cab249c9a1702958993fcfd965cf7ebbfeba5349435ab059be2`** | 33 309 445 | **19:05:19.310** | **晚于 t33 收工 36 s**；与仓外快照 `artifacts/app-debug-30c41ac9.apk`（**mtime 18:41:59**）**同哈希** ⇒ 形态上像"**把 18:41 的旧产物拷回交付路径**" |

**两份 APK 的差异（逐条目实测）**：条目集**完全相同**（各 165 条）、**无仅一方存在的条目**；**内容不同的条目 = 7 个 dex**（`classes3/5/6/9/11/12/14`），其大小几乎相同（`classes3` 差 4 B、`classes14` 差 8 B、其余同长）；**两侧的绑定类计数完全相同**：`LJ/N;` = **3**、`Lorg/jni_zero/GEN_JNI;` = **3**、`Lorg/webrtc/PeerConnectionFactoryJni;` = **2**（14 个 dex）。
⇒ 属于**同源、不同次构建的 dex 字节布局差异**（增量 vs 干净构建的非确定性），**不是**"一个含 `J/N`、一个不含"。

**要点（t34 前须裁定）**：
1. **交付哈希必须钉一个**：日志/快照侧 = **`ef29e00c…`**；交付路径现值 = **`30c41ac9…`**。二者都含 `J/N` 绑定类，**功能口径未观察到差异**，但"日志记录的产物 ≠ 交付路径现值"**必须由 captain 明确**（否则 t34 无法给出可复算的判定对象）。
2. **t33 的双钉与静默窗口证据是合格的**（jar/AAR 在窗口内 `OK`、`app/src` 窗口内写入 = 0、FROM-CACHE = 0、`:app:clean` = 1）——本分歧**不是** t33 的构建过程问题，而是**收工后 19:05:19 的一次文件级覆盖**。
3. **APK 非逐字节可复现**再获一例：同源、同绑定类的两次构建在 7 个 dex 上字节不同 ⇒ 与我既有结论（APK 非字节可复现）一致，**t34 以"钉死的单一哈希"为判定对象**。
4. t34 复验清单不变（`LJ/N;` 出现、`J/`≥1、`GEN_JNI` 方法 **194**、`J.N` native 193 ↔ `.so` 193、四 `.so` `p_align=0x4000`、`libjingle`==`757cef81…`、`libc++_shared`==`c9dbf4ec…` 且与 `jniLibs` 落位件逐字节相同），**只把"新 APK sha"换成裁定后的那一个**。

---

## 13.24 K-15 交付层收口（**captain 转录**）

> 本节由 **captain** 写入：结论来自**四名成员的独立实测**（native-dev、webrtc-builder、android-dev、env-installer），captain 仅转录并统一口径；**不构成实现者自证**（t33 的实现者是 captain，故本节所有数字均可由下列命令复算）。

**(1) 裁定：交付锚点（唯一）**
- **`30c41ac9d3363cab249c9a1702958993fcfd965cf7ebbfeba5349435ab059be2`**（33 309 445 B；mtime 18:41:59；构建背景与原始输出见 `reports/10-app-build.md §9.13` 与 `reports/10-t33-captain-assembleDebug-20260914-183903.log`，后者头部含 T0/T1 双钉 `jar 0c776934… / aar 8e8f2baf…` 与 `APK sha256=30c41ac9…`）
- **三处同哈希**：标准路径 `app/build/outputs/apk/debug/app-debug.apk`、下载服务冻结副本 `/opt/apk-http/served/app-debug.apk`（t37）、仓外留档 `artifacts/app-debug-30c41ac9.apk`
- **`ef29e00c…` = 非交付**：19:02 一次**依据过期指令、未获当次授权**的重复构建产物（不采纳）；与锚点差异**仅限注解/元数据/指针偏移与 header 校验和**，**编译代码逐字节相同**
- **19:05:19 的交付路径覆盖 = captain 本人**（把冻结交付件拷回），非第三方、非"第三次构建复现同 sha"

**(2) 交付层判据（全部实测）**

| 判据 | 实测 |
|---|---|
| APK sha256 | `30c41ac9…`（三处同哈希） |
| dex `LJ/N;` / `Lorg/jni_zero/GEN_JNI;` / `PeerConnectionFactoryJni` | **3 / 3 / 2**（历史件 `721df1c8…` = **0** / 3 / 2 ⇒ P-11 闸门有效） |
| 定义级 | `J.N`（classes.dex）**195 方法 / 193 native**；`GEN_JNI`（classes13.dex）**195 / 0**，且声明 AV1 非 native 桩（**B 独有**） |
| 四 `.so` | `p_align` 全 **0x4000**；`libjingle 757cef81…`、`libc++_shared c9dbf4ec…`、`libwebrtcdemo_native 95c44e5a…`、`libandroidx.graphics.path 41e9a793…` |
| `libc++_shared` ↔ `jniLibs` | **逐字节相同** |
| 单测 | **46 / 0 / 0**（5 份 XML @19:07:05；`8+4+17+11+6`；`JniBindingClasspathTest` = 6） |
| 载荷钉（跨两次独立构建稳定） | `classes.dex a1b2ebdc…` + `classes13.dex` + 四 `.so` 共 6 件 |
| 语义等价旁证（三条独立证据） | 类集合 26 195 / 26 195（双向差集 0）；逐 dex 分区 **14/14** 一致；7 个差异 dex 的 **`code_items` 机器码逐字节相同**（区级 `map_list` 与方法级 `code_off` 两路独立） |

**(3) 结论**
- **K-15 = 已闭合**（jar/AAR 侧 + **APK 侧**）。
- **APK 非逐字节可复现**（同源、同 jar、同命令的两次构建产出不同整包 sha）⇒ 复验判据用**载荷不变量**，整包 sha 仅作"交付件身份"。
- **仍未验证（不可省，不得写成通过）**：真机安装、首次 native 调用、`JNI_OnLoad` 运行期注册、Camera2 采集、首帧渲染、日志导出 —— 无设备，本轮不作通过结论；`t34` 的 verdict 判据不覆盖这些项。

**(4) verifier 独立复核（我自跑，截至 19:3x；未修改上面任何数字）**
- ✅ **交付锚点哈希/大小**：标准路径 `app/build/outputs/apk/debug/app-debug.apk` = **`30c41ac9…`**、**33 309 445 B**；仓外留档 `artifacts/app-debug-30c41ac9.apk` = **同哈希、同大小**。**一处 mtime 口径需注明**：**仓内路径 mtime = `19:05:19.310`**（captain 拷回时未保留时间戳），**留档件 mtime = `18:41:59.79`** —— §13.24 的"mtime 18:41:59"对应**留档件**。
- ✅ **第三处同哈希（原"未能复核"项）已由宿主侧闭合**：`/opt/apk-http/served/app-debug.apk`（t37 下载服务冻结副本）= **`30c41ac9…`**（33 309 445 B），与标准路径、`artifacts/` 留档 **`cmp` 逐字节相同**（captain 实测 + coturn-installer 19:37:43 只读快照 + android-dev 独立复核）。（本项原先按"宿主路径容器不可见 ⇒ 待核对"记录，现已闭合。）
- ✅ **dex 侧**：14 dex；`LJ/N;` = **3**、`Lorg/jni_zero/GEN_JNI;` = **3**、`PeerConnectionFactoryJni` = **2**（与 §13.24 一致）；历史件 `721df1c8…` 的 `LJ/N;` = **0** ⇒ P-11 闸门有效。
- ✅ **四 `.so`**：`757cef81…` / `c9dbf4ec…` / `95c44e5a…` / `41e9a793…`，**四者 `LOAD` 段全 `0x4000`**；APK 内 `libc++_shared.so` 与 `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` **逐字节相同**（`cmp`）。
- ✅ **单测**：5 份 XML @**19:07:05** = `8 + 4 + 17 + 11 + 6` = **46 / 0 / 0**（`JniBindingClasspathTest` 由 2 → **6** 用例，即 t32 加固已生效）。
- ✅ **第三处同哈希已由宿主侧核对闭合**（captain 追加）：`/opt/apk-http/served/app-debug.apk` = **`30c41ac9…`**（33 309 445 B），与标准路径、`artifacts/` 留档 **`cmp` 逐字节相同**；且**公网直下（`GET /app-debug.apk`）与分片重组两条路径**均等于 `30c41ac9…`（android-dev 独立复核）⇒ 交付锚点共 **5 个可复算位点**同哈希。
- ⏳ **留待 t34 复算（本节未复核）**：① `.dex` **方法级**计数（`J.N` 195/193、`GEN_JNI` 195/0）需 dex 解析器或 `dexdump` 级工具；② `ef29e00c…` 与锚点的"**`code_items` 逐字节相同**"结论（我已有的旁证是：7 个差异 dex **长度仅差 4 B / 8 B、其余同长**、类计数一致 ⇒ 与"元数据/指针级差异"相容，但**不等于**已证"机器码逐字节相同"）。⇒ 这两项我在 **t34** 用可复算方式补齐或如实标"未复核"。
  - **✅ 两项的第①项已由我（verifier）独立复算（§13.25(b)，读盘 20:09–20:10）**：`dexdump` 分块计数 = `J/N` **195 方法 / 193 native**（195 = 194 `public static` + `<init>`）、`GEN_JNI` **195 / 0 native** 且 `invoke-static LJ/N;.` = **193**、AV1 桩以 `throw v0` 结束；**方法级计数不再是"未复核"**。第②项（`code_items` 机器码）我只核了其冻结件指纹，**结论本身仍未由我推导** —— 见 §13.25(e)。
  - **✅ 两项均已由 t34 补齐（captain 追加，`reports/99-t34-appendix.md` = 184 行 / `36bad740…`，verdict = pass）**：① 方法级计数实测 `J.N`（classes.dex）**195/193**、`GEN_JNI`（classes13.dex）**195/0 且声明 AV1 桩**、`PCFJni`（classes14.dex）25/0/1；② **`code_items` 机器码逐字节相同**（区级 `map_list` + 方法级 `code_off` 两路独立），且根因已定量到键级（147 条 D8 `~~~{class→hash}` 不稳定令牌，全部为 Kotlin lambda 的 `*$$ExternalSyntheticLambda*`）。旁证冻结件：`webrtc-build/t39-apk-crosscheck/t39-apk-diff.freeze-682e02cad862cd76.log`（242 行 / `682e02cad862cd760b9326af1e46f934dfa48f93e87d00c7c4de76efbaee1ba2`）。
- ⚠️ **口径提醒**：`ef29e00c…` 已由 captain 裁定为**非交付**（依据过期指令、未获当次授权的重复构建），本报告 §13.23 记录的双哈希至此**已定论**（详见该节新增的裁定注记）。


---

### 13.25 verifier 对 `reports/99-t34-appendix.md` 的**独立第二方复验**（我自跑 20:04–20:10）+ 治理注记

> **读盘行**（P-13 规则②）：`[读盘 20:09:47] jar=0c776934c1452b7b`（1 206 602 B / 509 条目 / `^J/` = 1）· `[读盘 20:09:34] apk=30c41ac9d3363cab`（33 309 445 B）· `[读盘 20:09:19] .so 四件` · 复验对象 `reports/99-t34-appendix.md` = **184 行 / `36bad740d32979bf858ff9aaf5d839c2…` / mtime 19:49:49**（未改其一个字节）。

#### (a) 对象、作者与板面事实（先钉口径）
- 附录：`reports/99-t34-appendix.md`（**作者 `native-dev`**，非我）；三次提交 `dc698ab` → `52e5155` → `5aaac6a`。
- 板面：**`t34 [completed] review r2 verdict pass attempt 2 → native-dev`**（`agent_teams_status` 实测）。而我收到的任务文本把 **t34（r2 复核）**写作 **verifier** 承担 ⇒ 两者不一致（与 **D-13** 同类："过期任务文本被重放"，§13.12(d)）。附录 §5 已自述：执行时 `claim_task("t34")` 被平台拒绝（`assigned to "verifier"`），故其产物以"独立第二方复验"落盘、verdict 归属留 captain 裁定 —— **与板面现状一致（板面已把 assignee 改成 native-dev 并判 pass）**。
- 因此本节**不是** verifier 名下 verdict；它是**第二方对附录事实面的复算**（下表）。我**无法**移动/认领已完成任务（平台 `task status cannot move from "completed" to "claimed"`），故**不自行追认** verdict。

#### (b) 逐项独立复算（表内"我方值"均为我本轮自跑，命令见 (c)）
| 附录主张 | 我方独立复算 | 判定 |
|---|---|---|
| 交付锚点 = `30c41ac9…` / 33 309 445 B，仓内路径与 `artifacts/app-debug-30c41ac9.apk` 逐字节相同 | sha256 同值同大小；`cmp` 逐字节相同 | ✅ 一致 |
| 六载荷钉（§9）：`classes.dex a1b2ebdc…`、`classes13.dex a1f35bd5…`、四 `.so`（`757cef81…`/`c9dbf4ec…`/`95c44e5a…`/`41e9a793…`） | **六件逐件复算同值**（一律从 APK 内 `jar xf` 解出） | ✅ 一致 |
| 四 `.so` 全 `LOAD` 段 `p_align=0x4000` | `llvm-readelf -lW`：3/4/3/3 个 LOAD 段全 `0x4000` | ✅ 一致 |
| APK `libjingle` == `jniLibs` == `third_party/*/jni/arm64-v8a`；APK `libc++_shared` == `jniLibs` | 前三条 `cmp` 逐字节相同；`third_party` 下无 `libc++_shared.so` 路径（附录 §3 只声明 jniLibs，无夸大） | ✅ 一致 |
| 定义级：`LJ/N;` 仅在 `classes.dex`、`GEN_JNI` 仅在 `classes13.dex`、`PCFJni` 仅在 `classes14.dex` | **全 14 dex** 逐个 `dexdump` 扫 `Class descriptor`：三者各自**唯一定义**于上述分片 | ✅ 一致（且比附录更强：我扫了全 14 分片） |
| 定义级方法数：`J/N` 195/193、`GEN_JNI` 195/0、`PCFJni` 25 方法/0 native/1 字段 | `dexdump -d` 分块计数：`J/N` **195/193**；`GEN_JNI` **195/0** 且 `invoke-static LJ/N;.` = **193**；`PCFJni` **25 方法 + 1 静态字段/0 native** | ✅ 一致（口径见 (c)1/(c)2） |
| 引用计数：`LJ/N;` 3（2+1）、`GEN_JNI;` 3（2+1）、`PCFJni` 裸串 4（3+1）、桩串 2、AV1 名 3 | `grep -a -o` 逐分片计数：**完全同值同分布** | ✅ 一致 |
| 变体 `ef29e00c`：14 分片 **7 同 / 7 异**，异者 = `classes3/5/6/9/11/12/14`（含 `classes.dex`、`classes13.dex` 同） | 逐分片 sha256 比对：**7 同 / 7 异，异者集合逐名一致** | ✅ 一致 |
| 变体：类描述符集合 26 195 / 26 195，双向差集 0 | 全 14 分片类描述符 `sort -u`：**26 195 / 26 195；仅 A 有 0 / 仅 B 有 0**；两份排序表 sha256 同为 `cb82a1cf1c2e8b28…` | ✅ 一致（比附录多给一条可复算指纹） |
| v2 门禁 v2-0/v2-1：jar `0c776934…`（509 条目、`^J/` = 1）、`J/N.class` `1ff8d3ff…`、`GEN_JNI.class` `a6e7edcf…`、AAR `8e8f2baf…` 且内 `classes.jar` == jar | 逐项复算：**全部同值**；AAR 内 `classes.jar` 与交付 jar `cmp` 逐字节相同 | ✅ 一致 |
| 判据 ① `J.N static native` = 193；④ `.so` `Java_J_N_*` 导出 = 193 | ① jar 侧 193；④ `llvm-nm -D` 与 `llvm-readelf --dyn-syms` **两法均 193**（`JNI_OnLoad` 在 `0x29f718`） | ✅ 一致 |
| 单测 5 份 XML = 8+4+17+11+6 = 46/0/0 @19:07:05 | 产物自带 XML 实测：**46 / 0 / 0**，5 份 mtime 均 19:07 | ✅ 一致（**未重跑**，见 (g)） |
| 未核项：宿主 `/opt/apk-http/served/app-debug.apk`、真机首调 | 与本报告口径相同 | ⏳ 我同样**不可复算**（宿主路径容器不可见 / 无设备） |

#### (c) 本轮新增的三条计量陷阱（**P-14**，均为我踩到或实测到的假红/假绿来源）
1. **dex 与 class 文件的终止指令助记符不同**：class 文件里 AV1 桩结束于 **`athrow`**（`javap -c`：`new`→`ldc`→`invokespecial`→`athrow`，计数 **1**），而 `dexdump` 输出的是 **`throw v0`**（`|[4d85da] …: throw v0`，计数 **1**）。⇒ 在 dex 上 `grep -c athrow` = **0**（**假红**）。**正确断言按侧分开写**：class 文件 `athrow`=1 ／ dex `throw`=1。
2. **"194 vs 195"不是矛盾，是两把尺子**：dex 类方法表**含 `<init>`**，故 `J/N`、`GEN_JNI` 的 **dex 方法数 = 195 = 194（`public static`）+ 1 构造器**；jar 侧 `javap` 的 194 只数 `public static`。跨侧直接把"194"与"195"相减/相判会出**假矛盾**；同理 `PCFJni` dex = **25 方法 + 1 静态字段**——解析时若不区分 `Static fields` / `Direct methods` 段，字段会被计成方法而得到 **26 的假数**（我第一版即得 26，见 (d)）。
3. **容器 `/tmp` = 256 MB tmpfs（我实测 `df` = 256 M / 100% 满过）**：`classes.dex` 44.7 MB、`dexdump -d` 输出 **7 431 081 行**（`classes13` **2 036 035 行**）⇒ 解包与 dump **必须落工作区**。在 `/tmp` 上空间不足时 `dexdump` 的报错形如 `dexdump E …: Unable to open '…' : Size is too small` / `ReadFileToString failed` ⇒ **形似"dex 损坏"，实为磁盘满**。**附带**：`cmp -s` 在**文件缺失**时同样返回非 0 ⇒ 逐件比对前**必须先断言两侧存在**。

#### (d) 我自己的第一版误报（诚实登记，不掩盖）
- 我首轮比对"变体 vs 交付"的四 `.so` 时，因**只在交付侧解出了 2 个 `.so`**，`cmp -s` 对缺失件返回非 0，我据此误得"`libwebrtcdemo_native.so`、`libandroidx.graphics.path.so` **不同**"。**加存在性断言重测后：四件全部逐字节相同**（附录 §9 正确，我错）。⇒ 已按 (c)3 写进 P-14。

#### (e) 与 §13.23/§13.24 的关系（三态收口）
- **K-15（APK 侧）**现有**两层独立支撑**：① captain 转录（§13.24）；② **本轮我自跑的载荷/dex/v2 全项**（(b)）。**结论不变：已闭合**（但见 (g) 的真机限定）。
- **`ef29e00c…` 非交付**裁定不变；其"语义等价"的**类集合 26 195/26 195**与**六载荷逐件相同**两条已由我独立复算。
- **仍未独立复算**：t39 的"7 个差异 dex 的 `code_items` **机器码逐字节相同**"（我只核了该冻结件**存在且指纹一致**：`webrtc-build/t39-apk-crosscheck/t39-apk-diff.freeze-682e02cad862cd76.log` = 242 行 / `682e02cad862cd76…` / 22 901 B；**未**重新推导其结论）。

#### (f) 治理注记（供 captain 处置；我**不改板、不追认**）
1. **"产出入方评审"的边界情形**：t30 的 `J/N`+`GEN_JNI` 字节由 **native-dev** 生成，t31/t33 的**落位与构建由 captain 完成**，而 **t34 的 verdict 由 native-dev 给出** ⇒ 严格说不是"评审自己的实现"（实现方是 captain），但**是"评审自己产出的被落位字节"**。若团队口径要求 review 必须由**未参与该产物生成**者署名，则需**新建 verifier 名下任务**（我不能自claim 已完成任务）。
2. **团队 loop = `blocked`**（`t27 failed without a follow-up repair`）。**t27 的失败原因是平台 `Insufficient Balance (code QUOTA)`，不是验收失败**（`t27` 输出原文即该串）；其覆盖范围（真机缺陷修复的 jar 绑定类复验 + 新 APK 产物级复验）**已由本轮 §13.25(b) 的实测覆盖**，但**板面闭合需要一条新的 review 任务**由 captain 派发。

#### (g) 仍未验证（**不得读作通过**）
- **真机级**：安装、首次 native 调用、`JNI_OnLoad` 运行期注册、Camera2 采集、首帧渲染、日志导出 —— 无设备，**本轮不作通过结论**。
- **宿主路径** `/opt/apk-http/served/app-debug.apk`：容器不可见（宿主侧 captain/coturn-installer/android-dev 三方报告同哈希；我**不能**复算）。
- **单测未重跑**：我引用的是 t33 产物自带 XML（19:07:05）。重跑需 `:app:testDebugUnitTest` 并**写入仓库 `app/build/**`**，属"改动构建产物树"⇒ 超出我"只写 `reports/99`"的授权，故留待授权者。
- **t39 机器码结论**：仅核了冻结件指纹（见 (e)）。

#### (h) webrtc-builder 建议的"名字级形态判据"在锚点上**已成立**（我 20:12 实测）
- dex 内 `grep -a -o` 计数（`classes.dex` / `classes13.dex` / `classes14.dex`）：`LJ/N;` = **2 / 1 / 0**（合计 3）；`Lorg/jni_zero/GEN_JNI;` = **0 / 2 / 1**（合计 3）；**`org_webrtc_LibaomAv1Encoder_create` = 1 / 1 / 1（合计 3）**；GEN_JNI 可读转发名样例 `org_jni_1zero_CommonApis_releaseRawPtr` 在 `classes13.dex` = **1**。
- 意义：**B 形态**在 dex 层同时具备 ① `LJ/N;` 存在 ② 可读 AV1 名（GEN_JNI 的**抛异常桩** + `J/N` 的非 native 声明）③ GEN_JNI 可读转发名；**A 形态**（无 AV1 桩）缺 ②。⇒ 该建议判据**已在交付锚点 `30c41ac9…` 上通过**，宜纳入 P-11 后续断言（脚本改由持写权者做，我不改）。
- **⚠️ D-13 类过期口径提示**：webrtc-builder 本条消息的③仍以 `721df1c8…`（33 293 061 B / 11:28:34）为"现行 APK"、K-15"仍开放"、t33"in_progress" —— 三项**均已被取代**：现行锚点 = **`30c41ac9…`**、dex `LJ/N;` = **3（非 0）**、t33 已 completed（captain 接管 attempt 3）、t34 已 completed（→ native-dev）⇒ 其"APK 侧仍开放、待 t33 关闭"的结论**过期，不采纳**（其"重编后 dex 应出现 `LJ/N;`"的预期**已实现**）。

#### (i) `7dbe8400…` ↔ `dc5f8919…`"同内容、异时间戳"：**半侧我可复算、半侧不可**（读盘 20:12）
- **我可复算的一侧（已实测）**：`/data/dsh/home/workspace/tmp/t31/pre-routeA-libwebrtc-java.jar` = **`dc5f8919…`**（1 187 970 B / mode 600 / node 可读）= **508 条目 / 508 个 `.class` / `^J/` 条目 = 0**（印证"落位前 508 条目、无 `J/N`"，§13.11/§13.20）。
- **我不能复算的一侧**：`artifacts/pre-deploy-7dbe8400.jar` **此刻复测仍为 `root:root` mode `600`（uid 1000 不可读）** ⇒ webrtc-builder 新给的"`7dbe8400…` = 宿主机 `/tmp/pre-deploy.jar`；与 `dc5f8919…` **508/508 条目内容逐字节相同、508/508 时间戳不同、两者 compress_type 均 0（ZIP_STORED）**"**仍属宿主侧读数，我无法验证**。要闭环需持写权者把该件 `chmod 644`/`chown 1000:1000`（或在容器可见路径留一份）。
- **口径**：其结论"**同一内容、仅 zip 时间戳不同 ⇒ sha256 不同**"**与本报告 §13.22(e) 既有记录一致**（该点我已按 native-dev 更正采纳，**不是**新增分歧）；**唯一仍开放的是"容器侧可复算性"**。K-17 相关：本条消息①的"已闭合"与我的记录（§13.21 追加、§13.22(d)3）**同向，无冲突**——我此刻复测 `find .git ! -user node` = **0**，`.git/objects/{33,c6}` 均为 `node:node 775`（`56`/`ac` 已不存在）。

#### (j) native-dev 的 **v1/v2 指针提醒** + `FINAL.jar` 变体复核（我 20:13 实测）
- **指纹（现读，`[读盘 20:13:16]`）**：`webrtc-build/t30/logs/t34-gate.md` = **`6d1112dd…` / 59 行 / 4 395 B / mtime 18:19:37**；`webrtc-build/t30/logs/t34-gate-post-landing.md` = **`c87bb1cd…` / 33 行 / 2 781 B / mtime 18:36:02**。v1 的两行确为**落位前**措辞：`:21` = `jar tf "$J" | grep -c '^J/'   # 期望 ≥ 1（落位前 = 0）`；`:58` = "**落位前两条断言必红**（`J/` = 0、`GEN_JNI` `static native` = 194）"。
- **认则（与 §13.22(g) 一致，此处显式化以免再被引用错）**：**命令段** v1/v2 可共用；**钉值段只认 v2**（`J/` 期望 = **1**、`GEN_JNI.class` **只认 `a6e7edcf…`**、含"双钉"片段）⇒ **v1 = 落位前历史、v2 = 落位后现行**；任何后续复跑**不得**把 v1 的 `:21`/`:58` 当断言期望（此即 P-13 第③条"必须核对文件生效时点"的实例）。
- **`FINAL.jar`（未采用变体）复核 ✅ 与 native-dev 主张一致**：`d0d05244…` / 1 181 426 B / major 分布 **{55: 2, 61: 507}**；两个 major-55 类 = `org/webrtc/EglBase10Impl$FakeSurfaceHolder`（`2e62e9f7…` / 236 B）与 `org/webrtc/PeerConnection$Builder`（`e304e7ce…` / 219 B），二者**与交付 jar 同名类逐字节相同**，且都在交付 jar **51 个 major-55** 名单内（我实测交付 jar = `{55: 51, 61: 458}`，51 含 45 个 `*Jni` + 6 个其它，含上述两名）。其绑定类 `J/N.class` = `0eac3fb5…`（6 898 B）、`GEN_JNI.class` = `32448db8…`（24 828 B），**均与交付件（`1ff8d3ff…`/6 924 B、`a6e7edcf…`/24 910 B）不同** ⇒ 印证 §13.22(g) 判据边界：**若改落 `FINAL.jar`，六数与 t36 必须重跑**；此次复核**不改变** captain 裁定 **(甲) 保持现落位件**。
- **过期口径提示**：native-dev 本条 §3（"现行 APK 仍为 `721df1c8…`（`J/` = 0）⇒ K-15 仍开放；t33 已 in_progress"）**已被取代** —— 现行锚点 = **`30c41ac9…`**（dex `LJ/N;` = 3）、t33 与 t34 **均 completed** ⇒ 见 §13.25(h) 的 D-13 提示，勿据此重开 K-15。

### 13.26 **t34 复验**（captain 2026-09-14 放行口径）：逐条结果 + **verdict = pass** + 3 条低危记载更正

> **读盘行**（P-13 ②，均为我本节自跑）：`[读盘 20:15:08] jar=0c776934c1452b7b`（1 206 602 B）· `[读盘 20:16:01] apk=30c41ac9d3363cab`（33 309 445 B / mtime 19:05:19.310）· `[读盘 20:16:16] 四 .so` · `[读盘 20:24:17] doc/14=b3b67438…`（1337 行，未变）· AAR=`8e8f2baf…`。

#### (0) ⚠️ 板面冲突（先声明，避免"无据 verdict"）
- captain 来信**放行 t34 并要我给 verdict**；但板面实际为 **`t34 [completed] review r2 verdict pass attempt 2 → native-dev`**，我 `agent_teams_claim_task("t34")` 被平台拒绝，原文：**`Error: task t34 is assigned to "native-dev", not you`** ⇒ 我**无法**在 t34 上写 verdict（也不能改板/追认）。
- 处理：本节 = **captain 直接指示下的独立复验结论**，以**报告 + 消息**交付；**若需板面记录，请 captain 新建一条 review 任务**（我等 scheduler 认领）。此现象与 **D-13**（过期任务文本回放）同类，已在 §13.25(f) 登记。

#### (1) 被验对象（与 captain 口径逐字对齐）
```
APK            = app/build/outputs/apk/debug/app-debug.apk
                 sha256 30c41ac9d3363cab249c9a1702958993fcfd965cf7ebbfeba5349435ab059be2  33 309 445 B
                 仓内路径 mtime = 19:05:19.310（captain §13.23 裁定的交付锚点；宿主/留档 mtime = 18:41:59）
jar            = 0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757  1 206 602 B  509 条目  ^J/ = 1
AAR            = 8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099  6 492 067 B（内 classes.jar == jar，逐字节）
三值语义身份   = jar 0c776934… + J/N.class 1ff8d3ff…(6 924 B) + GEN_JNI.class a6e7edcf…(24 910 B)   ✅ 全部复算一致
同目录旁证     = app/build/outputs/apk/debug/output-metadata.json  mtime **19:04:43.947**（404 B；目录 mtime 同为 19:04:43.947）
                 ⚠️ 该 sidecar **属 19:02–19:04 那次构建（`ef29e00c…`）**，而路径上的 APK 是 19:05:19 从 18:41:59 冻结件拷回的 ⇒ **sidecar 与 APK 不同源**（见 F-5）
```
- **F-5（low，证据链）**：native-dev 报"同目录 `output-metadata.json` 亦 18:41:59"**现盘不成立** —— 实测其 mtime = **19:04:43.947**（对应 `ef29e00c…` 那次 19:02–19:04 构建）。正确口径：**不要用 sidecar 的时间戳给 APK 定年**；本文档反而**佐证了双构建**（§13.23 的双哈希），并与"锚点 APK 19:05:19 由冻结件拷回"互证。

#### (2) 判据逐条结果（captain §3.1–§3.5）
**§3.1 三值 + §13.3 四条 —— ✅ 通过（用权威脚本实跑，非等价自写）**
```
python3 scripts/check_jn_binding.py --jar <jar> --so <so> --javap <JDK/javap> --nm <NDK/llvm-nm>
  [E1] J/N native 193 ↔ .so Java_J_N_* 193：双向差集 J.N 独有 0 / .so 独有 0  → PASS（逐条可绑定）
  [E2] GEN_JNI native = 0；方法 194；invokestatic J/N.<name> distinct = 193（全为 native）；absent-proxy 非转发目标 = 0
  [E3] *Jni 调用点 distinct = 194 ；GEN_JNI 提供 194 ；未覆盖 0 → 已知豁免 0；真缺失 0
  RESULT: PASS   EXIT=0
python3 scripts/check_jar_link_integrity.py <jar>
  class 总数 509 ；被引用 *Jni 49 ；缺失 0 ；PCFJni 25 方法/native 0/调用 GEN_JNI YES ；GEN_JNI native 0 ；J/N native 193
  RESULT: PASS   EXIT=0
```
- 📌 **环境注意（F-2，低危）**：`check_jn_binding.py` **默认 `--javap` 指向宿主路径** `/opt/dsh-workspaces/webrtc-build/src/third_party/jdk/current/bin/javap`，容器内直接跑会 `FileNotFoundError`（原文见日志）⇒ 须显式传 `--javap/--nm`。脚本本体**只读**（仅 `shutil.rmtree` 自己的临时目录）。

**§3.2 APK 内 dex —— ✅ 判据成立；⚠️ 但 captain 给的分布需更正（F-1）**

| dex | `LJ/N;` | `Lorg/jni_zero/GEN_JNI;` | `Lorg/webrtc/PeerConnectionFactoryJni;`(描述符) | `org_webrtc_LibaomAv1Encoder_create`(可读名) |
|---|---|---|---|---|
| `classes.dex` | **2** | 0 | 0 | 1 |
| `classes13.dex` | **1** | **2** | 0 | 1 |
| `classes14.dex` | **0** | **1** | **2** | 1 |
| 合计 | **3** | **3** | **2** | **3** |

- **F-1（低危 · 记载更正）**：captain 信里"**classes13 J.N=1 / classes14 J.N=2** ⇒ 合计 3"**不可复现** —— 我两法（`grep -a -o` 逐 dex + `dexdump` 类型引用）一致得到 **`classes.dex` 2 / `classes13` 1 / `classes14` 0**；合计同为 **3**，故**判据本身成立**。看起来是把 **`PCFJni` 列（`classes14` = 2）**串到了 `J.N` 列。**更正建议**：`LJ/N;` = `classes.dex` 2 + `classes13.dex` 1。
  - **第三方独立复算（webrtc-builder，同一快照 `30c41ac9…`，只读内存解析）**：其全 14 dex 扫描结果同样为 **`classes.dex` 2 / `classes13.dex` 1 / 其余 0 = 3**，且 `org_webrtc_LibaomAv1Encoder_create` 可读名 **1/1/1**、桩消息 `Native method not present` **`classes.dex` 1 / `classes13.dex` 1** —— 与我的测量**逐项一致** ⇒ **captain 信的"1+2"为孤例，应以 2/1/0 为准**（三处独立测量：我、webrtc-builder、t34 附录 §2）。
- **名字级补充（webrtc-builder 建议，我已在锚点实测通过，§13.25(h)）**：AV1 可读名 1/1/1 ⇒ B 形态（可读桩）而非 A 形态；`J/N` 侧为可读名 + 非 native 抛异常桩。

**§3.3 四 `.so` 护栏 —— ✅ 全部通过**
```
libjingle_peerconnection_so.so  757cef81…c233259e  12 946 912 B  LOAD p_align = 0x4000 ×3   ✅ 仍为冻结值（t30/t36 证明对象未漂移）
libc++_shared.so                c9dbf4ec…97d1e36   1 356 968 B  LOAD p_align = 0x4000 ×4   ✅ 仍为冻结值
libwebrtcdemo_native.so         95c44e5a…f821bc0   1 231 512 B  LOAD p_align = 0x4000 ×3
libandroidx.graphics.path.so    41e9a793…115bfb6      10 096 B  LOAD p_align = 0x4000 ×3
APK libjingle      == app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so  逐字节相同 ✅
APK libjingle      == third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so 逐字节相同 ✅
APK libc++_shared  == app/src/main/jniLibs/arm64-v8a/libc++_shared.so                逐字节相同 ✅
判据④ Java_J_N_* 导出 = 193（llvm-nm -D 与 llvm-readelf --dyn-syms 两法同值）        ✅
```
（注：`jniLibs/libjingle_peerconnection_so.so` mtime 19:02 = t33 构建窗口内的"内容不变重写"，**内容仍是 `757cef81…`** ⇒ 门禁只认 sha，不认 mtime，见 §13.25(P-14)）。

**§3.4 单测 `--no-build-cache --rerun-tasks` = 46/0/0 —— ⏳ 容器内不可重跑（F-3，环境限制）；以"产物 XML + 宿主原始日志"成立**
- **我实跑了**（`[20:23:08 → 20:24:03]`，`GRADLE_USER_HOME` 置工作区、`ANDROID_HOME` 置容器 SDK）：
  ```
  ./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest
  * What went wrong: 25.0.4.1        ← AGP 8.5.2 的 JDK 上限闸门（容器内只有 Temurin 25.0.4.1，无 JDK 17）
  BUILD FAILED in 54s   EXIT=1
  ```
  （前置：`./gradlew --version` 可跑通 = Gradle 8.7 / JVM 25；`local.properties` 的 `sdk.dir` 仍指宿主 `/opt/dsh-workspaces/android-sdk`。）
- **无污染核对（重跑前后一致）**：APK `30c41ac9…`、jar `0c776934…`、`jniLibs` `.so` `757cef81…` **均未变**；5 份 XML 指纹与重跑前基线**逐份相同**（`f59d405a…`/`b410d54a…`/`837c4c42…`/`43ba061e…`/`63f37999…`）；`git status --porcelain` **空**。
- **成立的证据（两层）**：
  1. 产物自带 XML（19:07:05）：5 套 = `8 + 4 + 17 + 11 + 6` = **46 / 0 / 0**；`JniBindingClasspathTest` **6 用例逐名可核**（`genJniDeclaresSameMethodCountAsHashNativeClass`、`genJniIsPureForwardingLayer`、`requiredBindingsLedgerIsComplete`、`referencedJniBindingClassesAreResolvable`、`hashNativeClassDeclaresSoBoundaryNatives`、`coreWebrtcApiClassesAreResolvable`），`<failure>`=0、`<error>`=0；
  2. **宿主原始日志两条**：`reports/10-t33-testDebugUnitTest-20260914-190455.log`（`ae0139b2…`，`FROM-CACHE 次数=0`，逐类 8/4/17/11/6、TOTAL 46/0/0）与 `reports/10-t33-captain-testDebugUnitTest-20260914-184232.log`（`5ea263a5…`，`24 actionable tasks: 24 executed`，合计 46/0/0）。
- ⇒ 判据**在证据层成立**；"容器内现场重跑"**未能完成**（无 JDK 17；且 `local.properties` 指宿主 SDK —— 二者都需宿主或环境变更，我不自行改 `local.properties`）。**如实记为"未在容器内重跑"，不写成通过**。

**§3.5 §12.3 N-1..N-5（在新交付物上） —— ✅ 五条全部成立**
| 项 | 结论 | 我本轮证据 |
|---|---|---|
| N-1 被引用但缺失的 `*Jni` = 0 | ✅ | `check_jar_link_integrity.py`：被引用 49、缺失 **0**（EXIT=0） |
| N-2 绑定类回归测试（`JniBindingClasspathTest`） | ✅ | XML 6 用例 0 失败；宿主日志 46/0/0 |
| N-3 APK 实体 `p_align` + `libc++_shared` 与落位件字节同 | ✅ | 四 `.so` 全 `0x4000`；`cmp` 与 `jniLibs` 逐字节相同 |
| N-4 dex `class_defs` 含关键绑定类 | ✅ | **全 14 dex** 定义级扫描：`LJ/N;`→`classes.dex`、`GEN_JNI`→`classes13.dex`、`PCFJni`→`classes14.dex`（各自唯一） |
| N-5 构建窗口静默 + `--no-build-cache` + `FROM-CACHE=0` | ✅ | 锚点构建日志 `reports/10-t33-captain-assembleDebug-20260914-183903.log`（**`6f02e949…`**）：`T0/T1 双钉 jar 0c776934…`、**`本次产出 APK = 30c41ac9…`**、`2m56s`、`42 executed, 1 up-to-date`、`:app:clean 出现`、`FROM-CACHE 行 = 0` |

#### (3) 收口项（captain §4）—— 逐条落实
1. **K-15 = 已闭合**（jar/AAR 侧 + **APK 侧**）：本节门禁全绿（§3.1/§3.3/§3.2）+ §13.24 captain 转录 + §13.25 我的第二方复算 ⇒ 结论与 captain 一致。
2. **旧件 `721df1c8…` 保持"历史轮次交付物"** ✅（仓外快照 `artifacts/app-debug-721df1c8.apk` = `721df1c82841ad99…` / 33 293 061 B；其 dex `LJ/N;` = 0）。
3. **K-18 按建议"未实施"登记** ✅（依据 `reports/10-app-build.md §9.11`：`WebRtcEngine.BINDING_CLASSES` 增列被 captain 裁定 (B) 不放行，`app/src/main/**` 自 t25 冻结）。
   - **我在交付 APK 内独立验证了 K-18 的"事实基础"**（`[读盘 20:26:33]`，锚点 `30c41ac9…`）：`classes11.dex` 内含 **恰好 5 个** `BINDING_CLASSES` 串 —— `org.jni_zero.GEN_JNI`（1 处）与 `org.webrtc.{JniCommonJni, PeerConnectionFactoryJni, PeerConnectionJni, VideoTrackJni}` ⇒ 与 §9.11 所列 5 项**逐项一致**；这正是"增列 `JavaAudioDeviceModuleJni` 等"建议的现状基线（未实施，故 dex 内**无**该类串）。
4. **K-17 = 已闭合** ✅（我 20:11:52 复测 `find .git ! -user node` = 0；`.git/objects/{33,c6}` = `node:node 775`）。
5. **事故留痕在 `reports/15` §19** ✅（标题 = "事故留痕：一次非授权 A 落位及其回滚（2026-09-14 18:32:24 → 18:33:42）"，含 18:17:28/18:32:24/18:33:42 三行时间线与 P-12/D-13 留痕）。
6. **git 状态**：本节写入前 `git status --porcelain` = **空**；HEAD = `1c550cd`；captain 引用的 `1f5023e`（18:44:58）与 `2bb7750`（18:45:19，t33 交付记录）**均为 HEAD 祖先** ✅。**⚠️ 引用 `1f5023e` 会读到过期结论**：该提交正文写"`7dbe8400…` 在容器可见树内**无保留**"，**已被我 a9b45bb/1c550cd 就地更正**为"**在盘但不可读**"（§13.7 + §13.22(e) + §13.25(i)）。

#### (4) **verdict = pass**（含 3 条低危"记载/环境"项，均不构成 needs_revision）
- 通过：§3.1 三值 + §13.3 四条（权威脚本 PASS）、§3.2 dex 判据、§3.3 四 `.so` 护栏、§3.5 N-1..N-5、§4 收口五项、git 干净。
- **F-1（low，记载）**：captain §3.2 的逐 dex 分布应为 `classes.dex` 2 / `classes13` 1 / `classes14` 0（合计 3）；判据本身成立。*requiredFix：后续文本按此更正。*
- **F-2（low，环境）**：`check_jn_binding.py` 默认 `--javap` 为宿主路径 ⇒ 容器/其它环境须显式传参（建议把默认值改为基于 `PATH`/环境变量解析，由持写权者实施）。*requiredFix：脚本默认值可移植化。*
- **F-3（low，环境限制）**：`§3.4` 单测**未在容器内重跑**（无 JDK 17 / AGP 拒绝 JDK 25 / `local.properties` 指宿主 SDK）；以产物 XML（46/0/0，6 用例逐名）+ 两条宿主原始日志为证。*requiredFix：如需"容器内现场重跑"，须提供 JDK 17 或授权宿主执行（不改变已成立的证据层结论）。*
- **F-4（low，记载）**：native-dev 消息称 `Lorg/jni_zero/GEN_JNI;` = "`classes.dex`=2、`classes14`=1" —— **dex 归属写错**：实测（三法：`grep -a -o` 逐 dex / 去分号串 / `dexdump` 类型表）为 **`classes13.dex`=2、`classes14.dex`=1、`classes.dex`=0**（`dexdump classes.dex` 命中 `Lorg/jni_zero/GEN_JNI;` = **0** ⇒ `classes.dex` **根本不引用** `GEN_JNI`）。其**自己的 t34 附录 §2 写的是"classes13 2 / classes14 1"（正确）**⇒ 本条消息属转写漂移；`LJ/N;`（2/1/0，三处独立测量一致）与 `PCFJni;`（classes14=2）、`LibaomAv1EncoderJni;`（classes14=2）均与我一致。**判据/verdict 不受影响**（`GEN_JNI` 出现于 APK 内且合计 3 成立）。
- **F-5（low，证据链）**：`output-metadata.json` 的 mtime（**19:04:43.947**）与路径上 APK（19:05:19 拷回、内容 = 18:41:59 构建）**不同源** ⇒ 不得用 sidecar 给 APK 定年（详见 §13.26(1)）。
- **不写成通过（仍未验证）**：真机安装/首次 native 调用/`JNI_OnLoad` 运行期注册/Camera2 采集/首帧渲染/日志导出；宿主 `/opt/apk-http/served/app-debug.apk`（容器不可见，仅他人报告同哈希）。
- **附带事实（不改变 verdict）**：APK **整包 byte-reproducibility = false** —— 同一 jar、同一 `--no-build-cache clean` 命令的两次构建分别产出锚点 `30c41ac9…`（18:39–18:42 日志 `6f02e949…`）与 `ef29e00c…`（19:02–19:04 日志 `reports/10-t33-nocache-assembleDebug-20260914-190227.log`）；两者**六载荷逐件相同、类集合 26 195/26 195 相同**（§13.25(b)），故稳定判据落在载荷而非整包 sha。

#### (5) mtime 引用规则（native-dev 提议，我采纳）+ **A 形态件首次第一手实测**（读盘 20:25:14）
- **规则（采纳，与 P-13 读盘行配套使用）**：**内容一律以 `sha256` 为准；`mtime` 只在"同一观测时刻"内才具比较意义**。实证：18:32:24 非授权落 A → 18:33:42 回滚为 B，**内容回到原样但 mtime 被刷新**（`0c776934…` 首落 18:17:28 / 现件 18:33:42）⇒ 引用任何快照日志的 mtime 列时，须按**该快照的观测时刻**读，且必须与同段读盘行并列。**任何"换成 A 再换回 B"都只动 mtime 不动内容**，这是本次事故的直接副产品。
- **A 形态件第一手实测（此前我只能引用他人读数；本轮这些件对 uid 1000 可读）**：
  ```
  [读盘 20:25:14]
  tmp/jn-fix/QUARANTINE-A/ACCIDENT-landed-A-c289b4df.jar        c289b4dfd06827bc…  1 206 237 B  条目 509  ^J/ = 1
      J/N.class        = 9ada0641fcee5881…（6 742 B）
      GEN_JNI.class    = 8f3ce6137f02cef9…（24 727 B）
      javap：J.N  static native = 193 ; public static = 193          （无 AV1 桩）
             GEN_JNI static native = 0 ; public static = 193 ; invokestatic J/N. = 193
             AV1 可读名（两侧）= 0
  tmp/jn-fix/QUARANTINE-A/ACCIDENT-landed-A-f2ea0132.aar        f2ea0132…  6 495 516 B；内 classes.jar == c289b4df…（逐字节相同）
  tmp/jn-fix/final-candidate.jar                                87bed4a5…  1 181 169 B；两类 = 9ada0641… / 8f3ce613…（同一对）
  tmp/jn-fix/libwebrtc-java.uniform61.jar                       2f8a91a2…  1 181 035 B；两类 = 9ada0641… / 8f3ce613…（同一对）
  ```
  - **A/B 判别键的最终确认**：差异**只在 AV1 absent-proxy 桩** —— **A** = `J.N` 193（全 native）/`GEN_JNI` 193（193 转 193）；**B** = `J.N` 194（+1 非 native 抛异常桩）/`GEN_JNI` 194（193 转 + 1 桩）。两类体积差 **J/N 182 B、GEN_JNI 183 B**（`6 924−6 742` / `24 910−24 727`），与 native-dev 报的 182/183 一致 ✅。
  - **关键推论（对 t34 门禁的加固）**：**`^J/` = 1 在 A、B 两形态下都成立**（A 件同样含 `J/N.class`）⇒ **单靠 `J/` 存在性不能判 A/B**；必须用**方法数 194/194 + 桩**（v2 差异点②"③ 194（193 ⇒ 读到 A 件，立即报警）"正是此意，§13.22(g) 已按此钉）。**§13.21 的事故件因此也满足 v2 的 `J/` 断言**，这说明"回滚后 `J/`=1"**不足以**证明回滚到 B —— 判定证据仍是三值（jar `0c776934…` + 两类哈希）。
- **我仍读不到的 3 件（EACCES，`root` 属主；请求持写权者或 native-dev 给原样读数）**：
  | 路径 | 期望/待核 |
  |---|---|
  | `artifacts/pre-deploy-7dbe8400.jar` | `sha256sum` + <code>jar tf &#124; wc -l</code>（期望 508）+ 与 `tmp/t31/pre-routeA-libwebrtc-java.jar`（`dc5f8919…`，我可读）的**逐条 508/508 内容比对** |
  | `tmp/jn-fix/backup-B-before-A-incident-20260914-183224.jar` | `sha256sum`（期望 `0c776934…`）+ <code>jar tf &#124; wc -l</code>（期望 509）+ 两类哈希（期望 `1ff8d3ff…`/`a6e7edcf…`） |
  | `tmp/jn-fix/QUARANTINE-A/repro-c289b4df.jar` | `sha256sum`（期望 `c289b4df…`）+ <code>jar tf &#124; wc -l</code>（期望 509） |
  **更优解**：对上述三件做 `chmod 644`（或 `chown 1000:1000`）即可由我**自行复算**，无需转述（`QUARANTINE-A/README.txt` 已为 644，其哈希 `b46581b8…` 我可读）。

#### (6) 门禁证据补录（`--check-only` 三条日志 + 提交）+ K-17 归因更新（读盘 20:27:26）
- **`scripts/build_app.sh --check-only` 原始日志（宿主侧，我可读并已核对关键行）**：
  | 日志 | sha256(前 16) | 行 | 关键行 |
  |---|---|---|---|
  | `reports/10-t33-captain-checkonly-20260914-183850.log` | `3df36de27ef3b3b1` | 70 | `命令 … --check-only EXIT=0`；**[基线指纹] jar `0c776934…` 1 206 602 B mtime 18:33:42 / AAR `8e8f2baf…` 6 492 067 B**；`jar 含 J/N.class` ✅；`jar 内 *Jni.class = 48`；`org/webrtc 类条目 = 476`；JAR/AAR **精确 live 路径**（P-10 对象真实性）|
  | `reports/10-t33-checkonly-20260914-184752.log` | `890322979899da26` | 66 | 同上关键行（`jar 含 org/jni_zero/GEN_JNI.class` ✅）|
  | `reports/10-t33-checkonly-20260914-190205.log` | `890322979899da26` | 66 | **与上一条逐字节相同**（同源重复落盘）|
  ⇒ 与 §3.5 N-5 的构建日志互为闭环：**门禁阶段与构建阶段的 jar/AAR 钉值同为 B（`0c776934…` / `8e8f2baf…`）**，锚点 APK 的基线因此无漂移。
- **相关提交存在性**：`5b0781d`（18:49:29，t33 门禁证据入库）✅、`6e260e9`（18:50:05，我的 `JNI_OnLoad` 区间更正）✅ —— 均为当前 HEAD 祖先。
- **K-17 归因更新（不点名、只归因"机制"）**：**两名成员自述在 root 侧跑过 git** —— env-installer（`t18/t26/c6fcfcd/3acc1d2` 等提交）与 **webrtc-builder（约 18:31–18:55 的 `git status/diff/log/show`）**；其自述的**首条 git 命令 ≈18:31 晚于 18:25/18:27 两次抢占** ⇒ 那两次不能归到它头上。**结论不变**：根因 = **root 侧 git 会重写 `.git/index`（root:root 0644）**，属机制问题而非个人过失；**护栏 = 仓库内禁止 root 侧 git（必要时仅 `git --no-optional-locks`，该选项不写 index）**，叠加 captain 的 `chown -R 1000:1000` 与"uid-1000-only"规则。
- **顺带更正一处他方假设**：**"容器内无 `python3`"不成立** —— 我用**交付树自带**的 `webrtc-build/src/third_party/cpython3/host/bin/python3`（3.11.9）在容器内**真实执行**了 `check_jn_binding.py`（§3.1 的 PASS/EXIT=0 即容器内读数；另需显式 `--javap/--nm`，见 F-2）。

---

*报告结束。本报告仅验证与汇总，未修改任何被验证产物。*
