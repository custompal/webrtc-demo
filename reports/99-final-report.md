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
| **最终 HEAD（t19 收口）** | **`b66c1664a059202b1b2d020060b9083a8d5cb6ca`**（`t18: 闭合 t11 findings F-1/F-2 + reports/07 v1.6 与 reports/99 入库 + no-cache 决定性构建证据`）—— **该提交只含报告文件**（`reports/07-native-dev.md` +44 / `reports/10-app-build.md` +67 / `reports/99-final-report.md` 572 行），**不含任何产物字节**；收口时 `git status --porcelain` = **空** ✅ |
| 本报告自身的收口提交 | 上表 `b66c166` 已把本报告（572 行版）入库；**此后 verifier 仅为本节的 HEAD 回填/APK 终值确认提交了一次 `reports/99` 更新（只改本文件，不含产物字节）**——该后续提交的哈希不写进本报告（避免自引用）；**项目交付物的最终 HEAD 以 `b66c166` 为准** |
| 提交谱系（全部） | `7a02694`（初始化文档）→ `ad2e553`（submodule）→ `1a9d3ff`（t3 报告）→ `198d514`（**t10 统一提交**）→ `8a8611e`（t10 收尾/属主归一）→ `1a3a8e6` → `10aa709` → `39a62bd` → `d7a2471` → `a57c057` → `14ef053`（退役脚本 `git rm`）→ **`b66c166`（t18 收尾，最终）** |
| 收尾提交与判据（**t19 收口，全部达成**） | ① `git ls-files scripts/` **不含** `fix_jar_class_version.sh` ✓（F-01 闭合，`14ef053`）；② `reports/10` 的 v69 归属**已写对** —— 原件归档为 `.aar.orig`（6 457 598 B / `fe26d97f…d178e`，其 `classes.jar` = `ad54a0a2…` v69），现行 AAR 内 `classes.jar` = `d98939bb…`（major 61）✓（F-02 闭合，`b66c166`）；③ `git status --porcelain` = **空** ✓（`reports/07` v1.6、`reports/10`、`reports/99` 均随 `b66c166` 入库） |
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
| **交付 APK（终版）** `app/build/outputs/apk/debug/app-debug.apk`（33 260 234 B，mtime **21:42:38**） | **`b0cddd86718a75a0`**（全量 `b0cddd86718a75a0aadb82424d0ce5ab24bae2f52edede27f86cd0c2d7bbb12b`） | **不在任何提交内**（`app/build/**` 被 gitignore）；由 t18 的**决定性 no-cache clean 构建**产出（`--no-daemon --no-build-cache clean assembleDebug`，日志 `reports/logs/final-nocache-assembleDebug-20260913-214035.log`），使用 t16 的 v61 jar `d98939bb…` | 我的产物级复核（§3.8/§3.13）；构建日志 |
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
| 交付完整性 | **通过**：仓库、submodule×2、`libvpx.a`+头、libwebrtc jar/so/aar、自研 `libwebrtcdemo_native.so`、Go 二进制、部署单元、**终版 APK `b0cddd86…`** 均在位且经我复核（§3.8/§3.13） |
| 可复现性 | **Go：逐字节可复现（VCS-free 基线两次 `cmp` 相同）**；**APK：非逐字节可复现**（4 次缓存辅助 = `c72d3667…`，1 次完全执行 = `b0cddd86…`；差异在打包元数据/生成顺序，载荷语义未证实有差异）——见 §3.13 |
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
| V49 | **部分验证（构建未由我重跑；产物与单测已复核）** | ① 产物（**终版**）：`app/build/outputs/apk/debug/app-debug.apk` 33,260,234 B / mtime `2026-09-13 21:42:38.205` / `sha256 b0cddd86718a75a0aadb82424d0ce5ab24bae2f52edede27f86cd0c2d7bbb12b`；构建证据 = `reports/logs/final-nocache-assembleDebug-20260913-214035.log`（`--no-build-cache`，`:app:clean` 执行，`43 actionable tasks: 42 executed, 1 up-to-date`，**FROM-CACHE = 0**，`dexBuilderDebug`/`packageDebug`/`assembleDebug` 均实际执行）。**前一次 `c72d3667…`（20:21:25）已被该 clean 构建覆盖**；`find app/build -name libwebrtcdemo_native.so` → 3 条（cxx/merged/stripped，均 aarch64）。② **我未重跑 `./gradlew :app:assembleDebug`**：debug APK 含签名与时间戳，重跑会改变交付物字节；以产物复核 + t10 原始输出路径（`reports/10-app-build.md`、`scripts/build_app.sh` 10 阶段自检）为证据。③ **单元测试（强制重跑，非 UP-TO-DATE）**：`./gradlew :app:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL in 1m 44s`、**24/24 executed**；结果 XML = **3 类 / 36 用例 / failures 0 / errors 0**（AppConfigUrlTest 8、SignalingErrorPolicyTest 17、SignalingIdentityTest 11），详见 §3.9。⚠️ 首次在线复跑曾因 `dl.google.com` 读超时 `BUILD FAILED in 15m 4s`（环境），离线模式亦因 AGP 依赖未缓存失败——最终以缓存就绪后的成功运行为准 |
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
| D-6 Offerer 不变量 | **已验证** | `sendOffer` 全仓仅 1 处调用（`CallSession.kt:160`）← `createOffer()` 唯一调用者 `CallViewModel.kt:359` ← `maybeCreateOffer()` 唯一出口，闸门 `:356 if (role != ROLE_HOST || !peerJoined || !sessionReady) return`；触发路径 3 条（`:258/:265/:301`）；joiner 仅 `createAnswer`（`CallSession.kt:184+`）→ **同一时刻只有一方发 Offer 成立** |
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

---

*报告结束。本报告仅验证与汇总，未修改任何被验证产物。*
