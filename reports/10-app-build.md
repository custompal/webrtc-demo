# 10 宿主机构建 APK 与 Go 二进制（t10）

- 任务：t10（work，依赖 t5/t7/t8/t9）— 宿主机构建 APK（Kotlin 上层 + C++ native）与 Go 二进制
- 执行人：env-installer（attempt 1，attempt_id `e54c262c-b671-4dc2-bc94-fba307106ac0`）
- 执行位置：**宿主机** `/opt/dsh-workspaces/code/webrtc-demo`（≙ 容器 `/data/dsh/home/workspace/code/webrtc-demo`）
- 执行时间：2026-09-13 17:03–20:20（CST）
- **结果：成功**。APK 构建成功、Go 二进制构建成功、脚本全流程 `✅ 全部通过`（FAIL=0）。
- 一键复跑：`ssh … root@172.21.0.219 -p 5766 'bash /opt/dsh-workspaces/code/webrtc-demo/scripts/build_app.sh'`

---

## 1. 目标

在宿主机用 t5 的 libwebrtc Java SDK/so + libvpx 产物，构建 Kotlin 上层 + C++ native 的 APK，交叉编译 Go 信令二进制，并把命令固化为可复跑脚本。

## 2. 交付物（绝对路径）

| 交付物 | 路径 | 大小 | 校验 |
|---|---|---|---|
| **APK** | `/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk` | **33,260,234 B** | sha256 `c72d366706569b6dab5689200bc0902ce94fb7241b238a401e61fa5e745caa96`（`clean assembleDebug` 重编，使用 **t16 原生 v61 jar** `d98939bb…`） |
| **Go 二进制** | `/opt/dsh-workspaces/code/webrtc-demo/signaling/dist/signaling-linux-amd64` | 5,496,984 B | sha256 `c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068`（与 t9 交付物逐字节一致） |
| 构建脚本 | `…/code/webrtc-demo/scripts/build_app.sh` | 10 阶段 | 语法校验通过，实跑 `✅ 全部通过` |
| jar 版本归一脚本 | `…/code/webrtc-demo/scripts/fix_jar_class_version.sh` | — | 见 §6 |
| 构建日志 | `…/code/webrtc-demo/reports/logs/build_app-20260913-201723.log` | — | 最近一次全绿运行 |

## 3. 前置校验（阶段 2–6，EXIT=0）

| 项 | 实测 |
|---|---|
| Java SDK AAR | `third_party/libwebrtc/java/libwebrtc-arm64.aar` 6,456,926 B，sha256 `456e3f2ffbc407588b9430e719500f434caff7097eddc3e410b5d27f496fbaf0`（**t16 于 20:00 重打**：内含 `classes.jar` 453/453 = **major 61**、`jni/arm64-v8a/libjingle_peerconnection_so.so` 为 stripped 版 `757cef8128bf9151…`、`AndroidManifest.xml`） |
| Java 类 jar | `third_party/libwebrtc/java/libwebrtc-java.jar`（**最终 = t16 原生重编版**：1,048,264 B，453/453 个 class = **major 61 / Java 17**，sha256 `d98939bb…`；版本演进与归档见 §6） |
| JNI 共享库 | `…/java/jni/arm64-v8a/libjingle_peerconnection_so.so` 12,946,912 B，sha256 `757cef8128bf9151…`，**stripped** |
| libvpx | `third_party/libvpx/lib/libvpx.a` 1,929,142 B，NDK `llvm-readelf` 逐成员 **AArch64**；`include/vpx/` 11 个头 |
| jar 内容 | `org/webrtc/` 条目 **426** |
| CMakeLists 硬约束 | `add_library(webrtcdemo_native SHARED` ✅；**未出现** `libwebrtc.a`/`libjingle_peerconnection_so` 链接（契约 §4.2）✅ |
| Kotlin NativeLoader | `System.loadLibrary("webrtcdemo_native")` ✅ |

> **说明**：`third_party/libwebrtc/lib/*.a` **不存在也不要求**（t5 已删除其中的 thin archive；契约 §4.2 本就禁止链接）。

## 4. 构建过程与失败重试记录（共 4 次构建尝试）

| # | 时间 | 失败点 | 定位与处理 |
|---|---|---|---|
| 1 | 17:05→17:27 | 阶段 6.5 `:app:compileDebugKotlin` **26 个 `e:` 错误**（21m54s） | 全部落在 **t8 的 Kotlin 代码**（非环境问题）。逐条诊断 + `javap` 真实签名证据 → 发 android-dev 修复（**未擅自改他人文件**）。典型：`IceServer` 实为嵌套类 `PeerConnection.IceServer`；`IceTransportPolicy` 实为 `RTCConfiguration.iceTransportsType` + `IceTransportsType{ALL,RELAY,NOHOST,NONE}`；`ResolutionBitrateLimits` 是 **4 参**；`VideoEncoder` **无** `createNativeVideoEncoder`（只有 `createNative(long)`） |
| 2 | 18:22→18:44 | `:app:desugarDebugFileDependencies` → **`D8: Unsupported class file major version 69`** | 根因：t5 用 webrtc 自带 JDK 编译 Java（`src/third_party/jdk/current/bin/javac` **实测 javac 25.0.4.1**），453 个 class 全是 **Java 25 字节码**，AGP 8.5.2 的 R8/D8 8.2.2 不支持。处理见 §6（jar 版本戳归一化，含备份与逐字节核验） |
| 3 | 18:46→18:53 | 构建**成功**（5m30s），但脚本阶段 9 **假 FAIL**："dex 未含 org.webrtc 类" | 脚本 bug：把 APK 直接交给 `dexdump`（它要 `.dex`）。改为抽出全部 `classes*.dex` 再统计 → 491 个 `org.webrtc` 类（主要在 `classes13.dex`） |
| 4 | 20:15→20:17 | 脚本**静默中途退出**（阶段 9 后直接结束，不打印总结） | 脚本 bug：`set -o pipefail` 下 `x=$(… \| grep … \| wc -l)` 若 grep 无匹配则管道返回 1 → 命令替换失败 → `set -e` **终止脚本**；另 `unzip -l \| grep -q` 会因 SIGPIPE 造成**随机假 FAIL**（libjingle 通过、native 报"缺"）。已全部改为"先读入变量 + here-string 匹配 + `|| true`" |
| **5（脚本全绿）** | 20:17 | — | `build_app.sh` **`✅ 全部通过`（FAIL=0）**——但当时 APK 仍绑定我应急归一化的 v55 jar |
| **6（终版，clean 重编）** | 20:20→20:21 | 增量构建把 dex/package 判为 **UP-TO-DATE**（没识别 jar 在 20:00:41 被 t16 换成 v61） | 强制 `./gradlew --no-daemon clean assembleDebug`（BUILD SUCCESSFUL in 41s）→ **终版 APK sha256 `c72d3667…caa96`**；随后再跑 `build_app.sh` 全绿确认（org.webrtc 491 类、FileProvider、两个 so 全部核验通过） |

> **教训（写进 §6.2/§7.6）**：更换 libwebrtc jar 后**必须 clean 重编**，否则增量构建可能给出"构建成功但仍绑定旧 jar"的 APK。

> android-dev 修复后复测：`:app:compileDebugKotlin` **BUILD SUCCESSFUL（27s，0 错误）** —— 这正是 t8 之前缺失的**编译级证据**（t8 报告自述"40 个文件仅过静态检查，不保证零编译错误"）。
>
> **归因与证据（补记，来源 android-dev t15）**：26 处错误由 **t15**（android-dev）修复，**只改 `app/src/main/kotlin/**` 的 7 个文件**（`WebRtcConfig`/`StatsMapper`/`FrameNormalizer`/`Vp9VideoEncoder`/`ui/theme/Color`/`log/Log`/`diag/DiagnosticsScreen`），**`nativebridge/**` 与 `app/src/main/cpp/**` 一行未动**（其 `t7iface.sh` 19/19 复跑通过）。t15 自己的串行证据：`reports/logs/kotlin-compile+test-20260913-1754-T15-SERIAL-BUILD-SUCCESSFUL.log`（`compileDebugKotlin + testDebugUnitTest` → BUILD SUCCESSFUL 1m22s / EXIT=0）与 `reports/logs/junit-20260913-1754-T15-tests36.txt`（**tests=36, failures=0, errors=0**）。
> **一处中间态澄清（避免误判）**：我在 **17:43:55** 的另一次编译曾报**只剩 6 个错误**（全在 `Vp9VideoEncoder.kt:164/165/301/315/316/318`），那是 **t15 编辑中途的快照**（该文件最后一批修复落盘于 17:48:33，26−20=6 完全吻合），**不是"仍有 6 处缺陷"**。t10 的结论以**修复后串行运行**为准（0 错误）。
> **说明**：`scripts/build_app.sh` **不跑单测、也不写死任何单测数字**（避免把 31/36 之类计数固化）；单测数由 t15 证据与本报告引用给出。

## 5. 验收证据（原始输出）

### 5.1 APK 内容（阶段 9）

```
OK   APK: …/app/build/outputs/apk/debug/app-debug.apk（33260234 bytes）
--- APK 内 arm64-v8a .so ---
    10096   lib/arm64-v8a/libandroidx.graphics.path.so
  1330832   lib/arm64-v8a/libc++_shared.so
 12946912   lib/arm64-v8a/libjingle_peerconnection_so.so      ← t5，stripped 版（sha256 757cef8128bf9151…）
  1231512   lib/arm64-v8a/libwebrtcdemo_native.so             ← t7 自研（AGP 打包时自动 strip）
OK   APK 含 libjingle_peerconnection_so.so
OK   APK 含 libwebrtcdemo_native.so
--- org.webrtc 类是否入 dex ---
    dex 文件数=13  org.webrtc 唯一类=491  本项目类=239
OK   dex 含 org.webrtc 类（491 个）
OK   dex 含本项目类（239 个）
      classes11.dex: org.webrtc 条目 214
      classes13.dex: org.webrtc 条目 10062
      classes3.dex: org.webrtc 条目 60
      classes5.dex: org.webrtc 条目 31
```

### 5.2 日志导出设施打包证据（doc/10 §8 要求）

```
--- FileProvider / file_paths.xml 打包证据 ---
     7572   AndroidManifest.xml
      528   res/xml/file_paths.xml
OK   AndroidManifest 含 FileProvider 节点
        android:authorities="com.example.webrtcdemo.fileprovider"
        android:authorities="com.example.webrtcdemo.androidx-startup"
```

### 5.3 Go 二进制（阶段 8）

```
OK   已构建 …/signaling/dist/signaling-linux-amd64（5496984 bytes）
     sha256: c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068
     ELF 64-bit LSB executable, x86-64, statically linked, stripped
OK   新构建与 t9 交付的 signaling/signaling 逐字节一致（t9 产物可复现）
     （vcs.revision=1a9d3ff69667f4198cb7952d8ecd015fc9965052）
```
`-log` / `-log-level` / `-addr` / `-stun` / `-turn` / `-user` 等参数实测可用（`-h` 输出）。

### 5.4 native 单独验证（与 Kotlin 解耦；阶段 7 的 native 部分提前预验证）

```
$ ./gradlew --no-daemon :app:externalNativeBuildDebug
> Task :app:configureCMakeDebug[arm64-v8a]
> Task :app:buildCMakeDebug[arm64-v8a]
BUILD SUCCESSFUL in 34s（复跑 24s）
产出 app/build/intermediates/cmake/debug/obj/arm64-v8a/libwebrtcdemo_native.so
     2,206,528 B（未 strip 的中间产物）/ ELF ARM aarch64 / 导出 JNI_OnLoad
     NEEDED = liblog, libandroid, libm, libc++_shared, libdl, libc → **无 libjingle/libwebrtc**（§4.2 二进制级证实）
```

### 5.5 构建命令（可复跑）

```bash
# 宿主机
. /opt/dsh-workspaces/env.sh                 # 含 env-go.sh（JAVA_HOME/ANDROID_HOME/NDK/Go 全部就位）
cd /opt/dsh-workspaces/code/webrtc-demo
bash scripts/build_app.sh                    # 10 阶段：校验→抽 so→Kotlin 编译→assembleDebug→Go→APK 核验→属主归一
bash scripts/build_app.sh --check-only       # 只做前置校验
bash scripts/build_app.sh --skip-go          # 跳过 Go
```
关键 Gradle 命令（脚本内）：
```bash
./gradlew --no-daemon :app:compileDebugKotlin -PwebrtcDemo.skipNative=true   # 阶段 6.5 早失败
./gradlew --no-daemon assembleDebug                                          # 阶段 7
```

## 6. jar 的 class 版本问题：我的临时归一化 → **被 t16 的原生重编取代（最终口径 = Java 17 / major 61）**

**问题（t10 第 2 次构建失败）**：t5 的 `third_party/libwebrtc/java/libwebrtc-java.jar` 内 **453 个 class 全部是 major version 69（Java 25）**，AGP 8.5.2 的 D8 直接拒绝（`Unsupported class file major version 69`）。

### 6.1 我当时的应急处理（**已被取代，保留记录**）

`scripts/fix_jar_class_version.sh`：只改每个 class 头部 2 字节版本号 69→55。
- 备份原始字节：`libwebrtc-java.jar.orig-jdk25`，sha256 `ad54a0a2…af1f`（= t5 原值）
- 我产出的 v55 jar：sha256 `138cf12d…013a`，453 个 class 全 v55
- 逐字节核验：解包清单一致；**忽略字节 6–7 后 453 个 class 全部 `cmp` 相同（内容不同数 = 0）**
- 冒烟验证：`d8` 对旧 jar 复现错误、对新 jar 成功产出 `classes.dex`
- 原始字节另存：AAR 内 `classes.jar` 未改动（仍 v69，可对照）

**该应急版本现已作废**：captain 最终裁定 **Java 17 / major 61**，并由 webrtc-builder 在 **t16** 用 javac 原生重编（`build/android/gyp/compile_java.py` 中硬编码的 `--release 25` 改为 17，非改字节）产出终版 jar。

### 6.2 最终采用（t16 原生重编，**已实地重编 APK 验证**）

| 项 | 值 |
|---|---|
| 终版 jar | `…/java/libwebrtc-java.jar`，1,048,264 B，sha256 **`d98939bbf0c0cd071baff19004a4dc2f602997b70e33e0fc3669c5fccb5f6543`** |
| class 版本 | **453/453 = major 61（Java 17）** |
| 归档对照 | `.orig-jdk25`（`ad54a0a2…` 原始 v69）、`.orig-build`（同上）、`.v55-java11`（`ee792522…`，t16 的 v55 代）、`.v61-java17`（`d98939bb…`） |
| 可复现性 | t16 报告：`ninja -C out/Release-arm64 -j2 sdk/android:libwebrtc`（168 步/约 6 分钟）产出**逐字节相同**的 jar ✅ |

**重要实测教训（我踩到并记录）**：终版 jar 在 20:00:41 落地后，我按增量方式再跑 `assembleDebug`，Gradle 把 `desugarDebugFileDependencies`/`dexBuilderDebug`/`packageDebug` 全判为 **UP-TO-DATE**（APK 时间戳仍是 19:12），**即增量构建没有因该 jar 内容变化而重新 dex**。我据此**强制 `./gradlew --no-daemon clean assembleDebug`** 重编（BUILD SUCCESSFUL in 41s），得到**当前 APK sha256 `c72d3667…caa96`**，并再跑一遍 `build_app.sh` 全绿确认（`✅ 全部通过`，org.webrtc 491 类、FileProvider 节点、两个 so 均核验通过）。
→ 结论：**更换 libwebrtc jar 后必须 clean 重编**，否则可能得到一个"看起来构建成功、实际仍绑定旧 jar"的 APK。

> **给 verifier 的口径**：终版 jar 的 sha256 是 `d98939bb…`（Java 17/major 61，t16 原生重编）；我此前的 `138cf12d…`（v55 版本戳改写）**已作废**，仅作为过程记录与本报告中保留的脚本存在；原始 v69 字节在 `.orig-jdk25` 与 AAR 内 `classes.jar` 中可查。

## 7. 未解决 / 未运行时验证（如实标注，不夸大）

1. **未做真机/模拟器运行验证**：无 Android 设备，**未验证** APK 能否安装、`JNI_OnLoad` 是否注册成功、Camera2 采集/渲染、日志导出（ACTION_SEND）实际可用性。APK 侧证据止于"静态打包正确 + dex 含类 + native so 就位"。
2. **AAR 已随 t16 同步，原告警消除**：`libwebrtc-arm64.aar` 于 20:00 由 t16 重打，其 `classes.jar` 现为 **453/453 = major 61（Java 17）**、内含 so 为 stripped 版（`757cef8128bf9151…`，与 third_party 一致），sha256 `456e3f2f…`。故契约 §4.3 的**备选路径 ②（直接依赖 AAR）在当前状态下同样可用**。（本条原写"AAR 仍是 v69"，此处如实更新，避免与历史结论矛盾。）
3. **debug 构建**：APK 为 `assembleDebug`（未签名发布版、未开启 R8/混淆）。契约 V 表若要求 release APK，需另行构建（本任务按"assembleDebug（或契约指定任务）"执行）。
4. **t8 的 12 条"待验证 API 假设"**：本次编译已实际核对掉一批（`IceServer` 嵌套、`ResolutionBitrateLimits` 4 参、`VideoEncoder` 无 `createNativeVideoEncoder` 等）；但**运行期语义**（如 `setInjectableLogger` 是否真的生效、`setCodecPreferences` 是否能保证 VP9 置首）仍需真机验证。
5. **脚本自身的三个 bug**（已修，见 §4 第 3/4 行与 §6.2）：`dexdump` 误用 APK；`pipefail`+`grep -q` 的 SIGPIPE 造成**随机假 FAIL**；`x=$(… \| grep …)` 无匹配时 `set -e` **静默终止脚本**——均已记录，供后人避免同样的坑。
6. **增量构建陷阱**：更换 `libwebrtc-java.jar` 后，Gradle 仍可能把 dex/package 判为 UP-TO-DATE（见 §6.2 实测）；**必须 `clean assembleDebug`** 才能保证 APK 绑定新 jar。
7. **环境遗留**：`app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` 由脚本从 third_party 复制而来（契约 §4.3 要求），属**构建输入**，已在 `.gitignore` 中排除，不入库。
8. **属主归一的范围盲区（webrtc-builder 复核触发，已修）**：阶段 10 原先只 chown `jniLibs/、reports/logs/、local.properties、app/build/` + 受版控树，**漏掉 AGP 以 root 生成的 gitignored 目录** `app/.cxx/**`（实测 99 项）与项目级 `.gradle/**`、`.kotlin/**`（实测 29 项）。现已把这四个目录纳入阶段 10 的 chown 列表，并实测项目内非 1000 项 **= 0**。
   （webrtc-builder 提到的 4 项——`jniLibs` 的 so、2 个 `build_app-*.log`、`local.properties`——在我最终那次全流程运行后**已是 1000:1000**，其测量早于该次运行。）

## 8. 复跑与验证命令清单（供 verifier 独立复核）

```bash
APK=/opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk
sha256sum $APK                     # 期望 c72d366706569b6dab5689200bc0902ce94fb7241b238a401e61fa5e745caa96
unzip -l $APK | grep 'lib/arm64-v8a'
unzip -l $APK | grep -E 'res/xml/file_paths|AndroidManifest'
/opt/dsh-workspaces/android-sdk/build-tools/34.0.0/aapt2 dump xmltree $APK --file AndroidManifest.xml | grep -i fileprovider
# dex 内 org.webrtc 类（注意：类主要落在 classes13.dex）
D=$(mktemp -d); (cd $D && unzip -q -o $APK 'classes*.dex')
for f in $D/classes*.dex; do /opt/dsh-workspaces/android-sdk/build-tools/34.0.0/dexdump $f; done \
  | grep -oE 'Lorg/webrtc/[A-Za-z0-9_$]+;' | sort -u | wc -l      # 期望 491
# Go 二进制
sha256sum /opt/dsh-workspaces/code/webrtc-demo/signaling/dist/signaling-linux-amd64   # 期望 c298235a…
# jar 版本演进对照（终版 = d98939bb…，v61/Java17）
sha256sum /opt/dsh-workspaces/code/webrtc-demo/third_party/libwebrtc/java/libwebrtc-java.jar{,.orig-jdk25,.v55-java11,.v61-java17}
# 完整复跑（含所有自检）
bash /opt/dsh-workspaces/code/webrtc-demo/scripts/build_app.sh
```
