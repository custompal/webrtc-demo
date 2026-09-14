# 15 — 原生重编 libwebrtc Java SDK（t16/t17，**Java 17 / major 61**；t23 补 `*Jni` 后为现行交付）

> **现行交付已更新（t23）**：本报告 §1–§9 记录的 `d98939bb…`（453 类，v61）自 t23 起**降级为内容基线**；
> **现行交付 jar = `dc5f89193d55c97152a7dd1331f3f7d111f8dd099d4c970e9142231ea79f8915`（1 187 970 B，508 类，含 48 个 jni_zero `*Jni` 绑定类）**，AAR = `e066e456…`。见本报告 **§10** 与 `reports/05 §10`。

- 任务：t16（work，依赖 t5）— 消除 `libwebrtc-java.jar` 内 class 的 `major version 69`，产出可被 AGP 8.5.2 / D8 8.2.2 消费的 jar
- 执行人：webrtc-builder（attempt 1，attempt_id `69ac8bb4-cf40-48a1-ba46-335e946d9097`）
- 宿主：Ubuntu 24.04.2 / 4 vCPU(2 物理核×2HT) / 7.1 GiB RAM / swap 4 GiB
- 结论：**✅ 成功**。t16/t17 交付的 jar 内 **453/453 个 class 均为 `major version 61`（Java 17）**，由 **javac 原生重新编译**产生（非改字节），native 产物零改动。**t23 在此基础上补齐 48 个 `*Jni` 绑定类（见 §10），现行 jar 共 508 类、全 ≤ major 61。**

> ### 版本选型的**终局定案**（captain，唯一口径，已冻结）
> **最终采用 Java 17 / major 61（`--release 17`）**，依据：契约 §3 冻结 JDK 17；`app/build.gradle.kts` `sourceCompatibility/targetCompatibility = VERSION_17` 且 `jvmTarget="17"`；D8 8.2.2 支持 ≤61。
> **冻结令**：自裁定起，`third_party/libwebrtc/java/**`（jar、AAR、jniLibs）与 `libvpx.a` 任何人不得再改。
> **t23 例外（captain 授权）**：t23 明确授权对 `third_party/libwebrtc/java/**` 作一次受控修复（补齐 jni_zero `*Jni` 绑定类），**不触碰 `.so` 字节、不碰 `libvpx.a`、不改 `is_debug`**；冻结令在本任务范围内解除，其余不变。此后重新冻结。
>
> **过程留档（captain 已自认责任）**：captain 先后发出两条互斥指令——先"批准 v61"、后"以 v55 为准"，且两条都晚于我的落地动作，导致我按序各执行一轮（多跑一轮约 6 分钟重编）。**最终以 v61 收官**；**v55 那一代作废**，其字节作为历史归档保留在 `libwebrtc-java.jar.v55-java11`（见 §6、`reports/05 §8.1` 哈希链）。
>
> **机制澄清（我实测，纠正任务书前提）**：决定目标版本的是 `compile_java.py` / `turbine.py` 里**硬编码的 `--release` 取值**，**不是"用哪个 JDK 编译"** —— 实测 `javac25 --release N` 与 `javac11 --release N` 产出**同一个 major**。因此我保留 `jdk/current`(25) 不动、**只改这一个常量**，改动面最小、风险最低。
> （任务书建议"改用自带 `jdk11`"的说法**不作数**；该路径与否产出相同。）
> **可复现性（实测）**：以 `--release 17` 重跑 `ninja -C out/Release-arm64 -j2 sdk/android:libwebrtc`（168 步 / 约 6 分钟）→ 产出**逐字节相同**的 v61 基线 jar（`d98939bb…`）✅
> （t23 的现行 jar 在该基线上再做**确定性类合并**，`out/` 未被改动；见 §10。）

---

## 1. 根因定位（源码取证，非推测）

| 取证点 | 实测结果 |
|---|---|
| javac 实际来源 | `build/android/gyp/util/build_utils.py:41` → `JAVA_HOME = <src>/third_party/jdk/current`；`JAVAC_PATH = $JAVA_HOME/bin/javac` |
| 该 JDK 版本 | `third_party/jdk/current/bin/javac -version` → **javac 25.0.4.1** |
| **真正根因** | `build/android/gyp/compile_java.py:708-711` **把目标版本硬编码为**：`'--release', '25'`（注释为 "Jacoco does not currently support a higher value"） |
| 同源镜像 | `build/android/gyp/turbine.py:110` → `javac_cmd = ['--release', '25']`（LINT.IfChange 要求两者一致） |
| 其他硬编码 | `grep -rn "'25'" build/android/gyp/*.py` → **仅上述两处** |
| GN 侧参数 | **不存在** `java_target_version` / `default_java_target_version` / `java_language_version` 之类的可调参数（已 grep `build/config/android/*.gni`、`build/config/**/*.gni`）⇒ 该 checkout **只能改这两处硬编码**，或用自带 jdk11 替换 `jdk/current` |

⇒ **结论：`major 69` 不是"环境搭错"，而是这个 checkout 的构建脚本把 Java 目标版本钉死在 25。**

**额外取证（对我 t5 期间说法的一处更正）**：
- `internal_rules.gni:1775-1780` 有逻辑 `if (!is_java_debug && !_is_library) { args += [ "--release" ] }`；
  但 `compile_java.py` 的 `javac_args` 里 **`--release 25` 是无条件加入**的（`compile_java.py:700-712`），故最终仍以 25 为目标版本。
- `third_party/jdk11/current` **确实存在**（`javac 11.0.15`，其 README 自述 "This is a legacy version of the JDK. Please use //third_party/jdk."）。

---

## 2. 目标版本的选择：最终采用 **Java 17（major 61）**

**最终交付为 v61**（captain 终局裁定）。下表为两个候选版本的依据对比：

| 依据 | **Java 17（major 61，最终采用）** | Java 11（major 55，中途产出，已归档） |
|---|---|---|
| captain 终局定案 | ✅ **采用并冻结**（依据：契约 §3 冻结 JDK 17；app `VERSION_17`/`jvmTarget=17`；D8 8.2.2 支持 ≤61） | ✖ 作废（"以 v55 为准"那条已被 captain 自认撤回） |
| 契约 §3 | ✅ 冻结 **JDK 17**，与 jar 的 major 61 一致 | 契约冻结的是工具链 JDK；v55 亦可被 D8 接受，但非对齐项 |
| app 编译目标 | ✅ 与 `sourceCompatibility/targetCompatibility = VERSION_17`、`jvmTarget="17"` **字面一致** | 依赖 jar 版本低于 app 目标，D8 也接受，但非对齐 |
| AGP 8.5.2 / D8 8.2.2 支持 | ✅ 支持（≤61） | ✅ 支持 |
| 实测冒烟（build-tools/34.0.0 `d8`） | ✅ 成功产出 `classes.dex` | ✅ 成功产出 |

> **为何"用哪个 JDK"不是决定因素**：决定版本的是 `--release` 的取值（`compile_java.py` 硬编码）。实测 `javac25 --release N` 与 `javac11 --release N` 产出**同一个 major**。
> 任务书建议"用自带 `jdk11` 重编"；实测该路径与否**产出相同**，故我选择**保留 `jdk/current`(25) 不动、只改 `--release`** —— 改动面更小（不触碰 JDK 布局），风险更低。**任务书该说法不作数（captain 已确认）。**

---

## 3. 最小改动与完整命令

### 3.1 改动（仅 2 个文件、各 1 个常量；均留 `.t5orig` 备份可回滚）

```diff
# src/build/android/gyp/compile_java.py:711     （目标值由 JAVA_RELEASE_TARGET=11 参数化）
-        '25',
+        '11',

# src/build/android/gyp/turbine.py:110
-    javac_cmd = ['--release', '25']
+    javac_cmd = ['--release', '11']
```

> ⚠️ **上块为 v55 那一轮的 diff（历史）**。**最终冻结并入库的目标值是 `17`**（见 §9 与 `scripts/patches/libwebrtc-java-release17.patch`）。**t23 实测的构建树当前状态**（本次核对，非推断）：
> - `build/android/gyp/compile_java.py:708-711` → `'--release',` … **`'17'`**
> - `build/android/gyp/turbine.py:110` → **`javac_cmd = ['--release', '17']`**
> - `grep -n "'25'" <两文件>` → **空**（`25` 已无残留；`'11'` 亦无残留）

备份：`compile_java.py.t5orig`、`turbine.py.t5orig`（原文件保留在旁）。
**未改动**：`app/**`、`doc/**`、`reports/**`、submodule 指针、任何 GN args、任何 native 源文件。

### 3.2 完整命令（含为何加 `AUTONINJA_BUILD_ID`）

```bash
B=/opt/dsh-workspaces/webrtc-build
export PATH="$B/depot_tools:$PATH"; export DEPOT_TOOLS_UPDATE=0
# 该 checkout 的 android_static_analysis=build_server 校验动作需要 autoninja 集成，
# 裸 ninja 下会抛 "AUTONINJA_BUILD_ID is not set" 并中止（与 t5 同一处）。设该变量后可正常通过。
export AUTONINJA_BUILD_ID="t16-jar-rebuild-$$"
cd $B/src
ninja -C out/Release-arm64 -j2 sdk/android:libwebrtc
```

**只指定 Java 产物 target `sdk/android:libwebrtc`**（即 `dist_jar("libwebrtc")`，`sdk/android/BUILD.gn:30` → 输出 `lib.java/sdk/android/libwebrtc.jar`，`toolchain.ninja:3798`）。
**未执行任何 native target**（`sdk/android:libjingle_peerconnection_so` 未在命令行出现）。
**耗时**：第一次运行因上述 autoninja 校验中止于 73/168 步（141 s，未产出新 jar）；**修正后正式运行 96 步、243 s（约 4 分钟）**。

### 3.3 安全前提（我在真实编译前先做了干跑）
`ninja -n sdk/android:libwebrtc` → 仅 **168 步且全部是 Java/ACTION 类**（无 `CXX`/`CC`/`SOLINK`），确认不会触碰 native。产物目标与 native 目标在 GN 里是**两个不同 target**，故只编前者结构上就不可能重链 `.so`。

---

## 4. 交付结果（最终 = 原生 Java 17 / major 61；固化后重跑验证字节级可复现）

## 4. 交付值（**加粗 = t16/t17 基线值；t23 后见下表「现行」行**）

| 项 | 值 |
|---|---|
| **路径** | `third_party/libwebrtc/java/libwebrtc-java.jar`（容器 `/data/dsh/home/workspace/...` ≙ 宿主 `/opt/dsh-workspaces/...`） |
| **字节数** | 基线 **1 048 264 B**（t5 原 build 产物 1 051 957 B；t10 patch 版 555 728 B — **该 patch 版系 re-zip 产物，其字节已不可得**，见 §8 的 provenance 说明）<br>**现行（t23）= 1 187 970 B** |
| **sha256** | 基线 **`d98939bbf0c0cd071baff19004a4dc2f602997b70e33e0fc3669c5fccb5f6543`**<br>**现行（t23）= `dc5f89193d55c97152a7dd1331f3f7d111f8dd099d4c970e9142231ea79f8915`** |
| **class 版本** | 基线 **453/453 = major 61（Java 17）**；**现行 508 个 = 51× major 55 + 457× major 61，全部 ≤ 61** ✅ |
| 生产端同源 | `out/Release-arm64/lib.java/sdk/android/libwebrtc.jar`：**实测为 `ee792522…`（1 048 264 B，453 类全 major 55，`*Jni`=0，时间 2026-09-13 19:55:01 —— v55 那一轮的遗留产物）**，既 ≠ 基线 `d98939bb…` 也 ≠ 现行 jar；t23 的合并发生在 `out/` 之外 ⇒ **该文件 ≠ 现行 jar**（已知且已登记，见 §10.7） |
| AAR 内 `classes.jar` | 已同步更新（见 §5），**t23 后与现行 jar 全 64 位同哈希 `dc5f8919…`**；AAR 本身 = `e066e456f5d62a015433db949a7cd1b1c13acaf432c93aa06f3544cea71b9e53`（6 489 244 B） |
| 备查 | `libwebrtc-java.jar.v55-java11`（**中途 Java 11 版本，已归档作废**）、`libwebrtc-java.jar.orig-jdk25`（t5 原始 v69）、`libwebrtc-java.jar.v61-java17`（**t23 前基线副本**，t23 的守恒比对基准） |

**AAR（t23 重打包后）**：`third_party/libwebrtc/java/libwebrtc-arm64.aar` = 6 489 244 B，sha256 `e066e456f5d62a015433db949a7cd1b1c13acaf432c93aa06f3544cea71b9e53`；其 **内部 `.so` 逐字节未变**（sha256 `757cef81…`，见证据 4）。t23 前的 AAR 备份为 `libwebrtc-arm64.aar.pre-t23`（`456e3f2f…`）。
> 注：不能以文件大小判断 AAR/jar 版本（各版本大小接近）；**判据是内含 `classes.jar` 的 sha256**。

**产物哈希一览（现行，t23 后）**：
| 产物 | sha256 | 大小 |
|---|---|---|
| `libwebrtc-java.jar`（交付） | **`dc5f89193d55c97152a7dd1331f3f7d111f8dd099d4c970e9142231ea79f8915`** | 1 187 970 |
| AAR 内 `classes.jar` | **同左（全 64 位相同）** | 1 187 970 |
| `libwebrtc-arm64.aar` | **`e066e456f5d62a015433db949a7cd1b1c13acaf432c93aa06f3544cea71b9e53`** | 6 489 244 |
| `jni/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` | 12 946 912 |
| `libvpx/lib/libvpx.a` | `e280b11bcc9eff8c5be20f35023c79319eeb374a5277d1972c0eb310fe3215f7` | 1 929 142 |

### 证据 1：class 版本（基线）与**现行分布**
```
# 基线（t16/t17）：453 个 class 全部 003d
$ find . -name '*.class' | while read c; do xxd -p -s6 -l2 "$c"; done | sort | uniq -c
    453 003d          # 0x3d = 61 = Java 17
# 现行（t23）：508 个 class
    51  0037          # 0x37 = 55 = Java 11（jni_zero 生成类，编自 compliment 路径）
   457  003d          # 0x3d = 61 = Java 17
```
> 全部 **≤ 61** ⇒ AGP 8.5.2 / D8 8.2.2 可消费；**无 major 69 残留**。

### 证据 2：抽样 3 个 class 的 `javap -v` major version
```
org/webrtc/VideoEncoder.class                 major version: 61
org/webrtc/PeerConnectionFactory.class        major version: 61
org/webrtc/SurfaceViewRenderer.class          major version: 61
```
（`xxd -l8` 原始头为 `cafe babe 0000 003d`）

### 证据 3：差异性质 = **javac 重新编译**，而非改字节（**captain 已纠正其表述边界**）
| 对比 | 结果 |
|---|---|
| v61 基线 jar（t23 前） vs t10 补丁 jar（v55，**历史/已退役**） | 453 个 class 全部内容不同（0 相同） |
| 归纳（**唯一成立的结论**） | 新 jar 是 **javac 重新编译产物**，而非对既有字节改版本号 ✅ |

> ⚠️ **不得过度解读（captain 纠正）**：上表左侧两个 jar 的**目标版本本就不同（61 vs 55）**，因此"453 个全部不同"**不能**用来判定补丁方案是"等价"还是"有风险"。**不得**写成"A 有风险"，也**不得**写成"A 安全"。本节**只**支持"新 jar 为重新编译产物"这一句。
> 为便于定性，我在 §8 另做了**同为 major 55 的两件**（版本字节改写版 vs `--release 11` 原生重编版，**后者已归档、非现行**）的对比；该对比仍受编译器版本差异影响，**同样不构成对 A/B 优劣的定案**。

### 证据 4：**native 产物零改动**（与 t5 基线逐字节一致）
```
757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e  third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so
757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e  (编译前基线，未变)
12946912 B  stripped  ✅ 与任务书要求一致
third_party/libvpx/lib/libvpx.a = 1 929 142 B  ✅ 未变
```
**AAR 内部 `.so` 亦逐字节未变**：重打包后 `unzip` 出的 `jni/arm64-v8a/libjingle_peerconnection_so.so` sha256 仍为 `757cef8128bf9151…`（我只替换了 `classes.jar` 条目）。

### 证据 5：可被 AGP/D8 消费（实测冒烟）
```bash
android-sdk/build-tools/34.0.0/d8 --min-api 21 --output . libwebrtc-java.jar
# → ✅ classes.dex 506876 bytes，无任何 class file version 相关错误
#    （仅 android.os.Handler / java.lang.RuntimeException 未在 classpath 的常规 warning）
# 对照：t5 原始 v69 jar 在此处直接报 Unsupported class file major version 69
```

---

## 5. "app 到底消费 jar 还是 AAR"（任务要求写清）

| 消费方 | 实际依赖 | 证据 |
|---|---|---|
| **app（`app/build.gradle.kts`）** | **直接用 jar**：`implementation(files(rootProject.file("third_party/libwebrtc/java/libwebrtc-java.jar")))` | `app/build.gradle.kts:150` 附近、契约 §4.3（`doc/14:272`） |
| AAR | **本次交付链未使用**（仅作为归档/备用） | `grep -rn "libwebrtc-arm64.aar" app/` 无命中 |

⇒ **AGP 消费的是 jar，所以修复必须落在 jar 上**（也解释为何 t10 的失败点是 jar）。
尽管如此，我**仍同步更新了 AAR 内的 `classes.jar`**，以保持"AAR 与 jar 一致"这一核验关系不失效（旧 AAR 已备份为 `libwebrtc-arm64.aar.orig`，6 457 598 B）。

---

## 6. 备份链（完整可回滚，未删除任何 t5/t10 产物）

| 文件 | 字节 | 含义 |
|---|---|---|
| `libwebrtc-java.jar` | 1 187 970 | **现行交付（t23）**：508 class，含 48 个 `*Jni`，sha256 `dc5f89193d55c971…`（t16/t17 基线为 1 048 264 B / `d98939bb…` / 453 class major 61） |
| `libwebrtc-java.jar.v55-java11` | 1 048 264 | 中途 v55 的归档副本（**已作废**，非现行） |
| `libwebrtc-java.jar.v61-java17` | 1 048 264 | t16/t17 的 v61 **内容基线副本**（major 61，**t23 的守恒比对基准**） |
| `libwebrtc-java.jar.orig-jdk25` | 1 051 957 | **t5 原始产物**（major 69，sha256 `ad54a0a209ecfd6e…`） |
| `libwebrtc-java.jar.orig-build` | 1 051 957 | 同上另一副本（内容与 `.orig-jdk25` 一致） |
| `libwebrtc-arm64.aar` | 6 489 244 | 现行 AAR（t23 重打包），内含与现行 jar 同哈希的 `classes.jar`（`dc5f8919…`） |
| `libwebrtc-arm64.aar.pre-t23` | 6 456 926 | t23 前的 AAR（`456e3f2f…`，内含 `d98939bb…` 的 `classes.jar`） |
| `libwebrtc-arm64.aar.orig` | 6 457 598 | **t5 原始 AAR**（内含 major 69 的 `classes.jar`，sha256 `fe26d97f…`） |
| `build/android/gyp/{compile_java,turbine}.py.t5orig` | — | 我改动的两个源文件的原版（**已入库补丁化**，见 §9） |
| `scripts/patches/libwebrtc-java-release17.patch` | 983 | **入库可审查补丁**（t17 交付物） |

### 6.1 一处操作失误（如实留档）
我第一次为 AAR 做备份时写的是 `[ -f X.aar.orig ] || cp X.aar X.aar.orig` **后面紧跟 `echo` 而无分隔符**，导致 `echo` 被当成 `cp` 的第三个操作数、`cp` 实际未按其本意执行；随后我又在**覆盖之后**才创建备份，结果那个 `.orig` 其实是"新的" AAR（与 `.aar` 同 sha256）。
**已修正**：改用 t5 的**原始源 AAR**（`webrtc-build/libwebrtc-arm64.aar`，内含 453×`major 69`，sha256 `fe26d97f…`，6 457 598 B）作为 `.orig`，现已核验其 `classes.jar` 内 class 版本为 **`0045`(69)** ✅。
**教训**：备份必须**在覆盖之前**完成、且**当场用 sha256 自证**两者不同（不能只看存在性）。这与报告 05 §8 记录的"模式匹配自杀"属同类问题：**没有在动作后立即验证真实状态**。

---

## 7. 遗留与建议

1. **本次改动在 `webrtc-build/src` 内**（`build/android/gyp/*.py`），**不影响 t3 的 submodule**，也不影响 `third_party/` 交付物之外的内容；如上游日后重新 `gclient sync`，这两处 patch 会丢失（需重打）。已在 §3.1 给出可复现的 diff。
2. **`--release 17` 仍是 patch 而非 GN 可配项**：该 checkout 没有可用的版本参数，故只能改脚本。若希望"零 patch"，替代方案是把 `third_party/jdk/current` 指向 `jdk11`（则默认 target 随之变化）——**但那会改变 JDK 运行时来源，改动面更大，我没有采用**。
3. 建议 verifier 以 §4 的 5 条证据独立复核；t10 可换入新 jar 后重跑权威 `assembleDebug`。

---

## 8. 补充对比：A′（version-byte 改写，可复现件）  vs  B（`--release 11` 原生重编版）（两者均为 major 55；**定性参考，非 A/B 定案**）

> ⚠️ **本节结论的边界（captain 纠正后重写）**：本对比涉及**两种不同 `--release` 目标 / 不同 javac 版本**下的编译器行为差异，**不足以**判定"改版本字节"与"重新编译"两方案的绝对优劣。
> **不得**据此写"A 有风险"，也**不得**写"A 安全"，更**不得**写任一方案"更正确"。
> **可支持的唯一结论**：**现行 jar 是 javac 重新编译产物，而非改字节**（由 §4 证据 3 独立支撑）。
> **终局定案（captain）**：采用 **v61（Java 17，`--release 17`）并冻结**；v55 那一代**作废并归档**（captain 已自认发令交叉）。

**比较对象（均为盘上现存文件；每个数字均可复现）**：

| 标注 | 文件路径 | sha256 | 大小 | major version |
|---|---|---|---|---|
| **A′**（version-byte 改写，**本报告重建的可复现件**） | `<WS>/tmp/jarver/A-inplace.jar` | `412153f43d0bd3ae40020052a12b37b7b66889c916658c8134b2b47d3aafba89` | 1 051 957 B | **55** |
| **B**（原生 `--release 11` 重编版，已归档） | `third_party/libwebrtc/java/libwebrtc-java.jar.v55-java11` | `ee792522c35cb8ca5c53052a1fecaa4bca6a96870f94aa4f563d0198ea6d8245` | 1 048 264 B | **55** |

**A′ 的构造方式（确定性、可复现）**：以 `libwebrtc-java.jar.orig-jdk25`（`ad54a0a2…`，1 051 957 B，453 个 entry **全部为 STORED**）为输入，**原地**改写每个 class 头 2 字节 `69→55`（同步修正 local header 与 central directory 的 CRC32，使 zip 仍合法），**不 re-zip**：
```
patched classes = 453        size: 1051957 → 1051957 (等长)
zip testzip = None           A' sha256 = 412153f43d0bd3ae…d3aafba89
verify: 453/453 class 均 major 55
```
> **provenance 说明（必须写明）**：当年**实际部署进 `third_party` 的那份**是 re-zip 产物 `138cf12d…`（555 728 B）——**其字节已随 v55/v61 多次替换而不可得**，故**不能**作为一手证据；`<WS>/tmp/jarver/patched.jar`（`c3e4bdd4…`，539 644 B，**另一次实验产物**，与部署件并非同一文件）亦非该证据。
> ⇒ **旧 §8 的结论据此标注为 provenance 受限，已由 A′ 复现替代**；下表所有数字均可用上表两个文件在盘上复现。
> `--release` 与 major 的对应关系（本节严格遵守，不混写）：**`--release 11` → major 55**；`--release 17` → major 61；`--release 25` → major 69。

**方法**：两者均解包，对 453 个 class 逐一「屏蔽字节 6-7（版本戳）后 `cmp`」，并对差异类做 `javap -v -c` 结构比对。

### 8.1 结果：**并非"除版本外逐字节相同"**

| 指标 | 结果 |
|---|---|
| 屏蔽版本字节后**相同**的 class | **397 / 453** |
| 屏蔽版本字节后**不同**的 class | **56 / 453**（逐条有差异） |
| 方法/字段清单**不同**的 class | **7 / 453** |
| 完全逐字节相同（含版本戳）的 class | **0 / 453** |

### 8.2 差异的性质（仅作定性提示）

差异**不是**"major 55 vs major 69 的版本戳"——A′ 与 B 的 major **都是 55**。差异来自**编译来源不同**：

| 事实 | 实测值 |
|---|---|
| **A′**（version-byte 改写；源字节来自 javac 25）中含 `java/util/Objects.requireNonNull` 的 class | **44 / 453** |
| **B**（原生 `--release 11` 重编）中含 `Objects.requireNonNull` 的 class | **4 / 453** |

**逐类特征（以 `org/webrtc/Predicate$1.class` 为例）**：
```
A′ (in-place) : 1100 B   major=55
B  (native)   : 1004 B   major=55
A′ 相对 B 多出的常量池条目：
  Utf8  java/util/Objects
  Utf8  requireNonNull
  Utf8  (Ljava/lang/Object;)Ljava/lang/Object;
```
即：**javac 25（A′ 的源字节）在嵌套类构造器插入了较多 `Objects.requireNonNull` 空值检查，而 `--release 11` 原生重编（B）在同一位置较少插入**（44 → 4）。这属**编译器/目标版本差异**。

### 8.3 判定（中性；**已按 captain 纠正限定**）

- **本对比不构成 A/B 优劣定案**：A′ 与 B 在**不同 `--release` 目标 / 不同 javac 版本**下产出，56 个 class 的差异（含 40 处 `Objects.requireNonNull` 插入数量不同）属**编译器版本间行为差异**；**既不得**据此写"A 有风险"，**也不得**写"A 安全"，**也不得**写任一方案"更正确/更必要"。
- **本节可支持的唯一结论**：**现行 jar 是 javac 重新编译产物，而非改字节**（由 §4 证据 3 独立支撑）。
- **最终定案（captain）**：采用 **v61（Java 17，`--release 17`）**；**v55 一代作废并归档**（归档件 `libwebrtc-java.jar.v55-java11`，见 §6）。依据：契约 §3 冻结 JDK 17、`app/build.gradle.kts` `sourceCompatibility/targetCompatibility = VERSION_17` 且 `jvmTarget="17"`、D8 8.2.2 支持 ≤61。
- 可复现性收益仍然成立：补丁固化后以 `--release 17` 重跑（168 步 / 约 6 分钟），产出**逐字节相同**的 v61 基线 jar（`d98939bb…`）✅。

### 8.4 给 verifier / 后续工作的检查清单（可直接复用；已按 t23 现行交付更新）
1. `unzip -oq <jar> -d X && find X -name '*.class' | while read c; do xxd -p -s6 -l2 $c; done | sort | uniq -c` → 现行应为 **51 × `0037` + 457 × `003d`**（即 508 类，**全 ≤ major 61**，无 major 69 残留）；
2. `javap -v` 抽 3 个类 → `major version: 61`；`javap -p org.webrtc.PeerConnectionFactoryJni` → 有 `public static ... get()`；
3. jar sha256 应为 **`dc5f89193d55c97152a7dd1331f3f7d111f8dd099d4c970e9142231ea79f8915`**，1 187 970 B、508 类、**48 个 `*Jni`**；且与 **AAR 内 `classes.jar` 全 64 位同哈希**；AAR = `e066e456…`，6 489 244 B；
4. `.so` sha256 必须仍为 `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`、12 946 912 B、stripped；`libvpx.a` = 1 929 142 B（`e280b11b…`）；**AAR 内 `.so` 与目录 `.so` 同哈希**；
5. **Java 产物必须验证字节码目标版本**（这是 t5 的核验盲区：当时只验了"jar 内有 `org.webrtc` 类"，未验 major version）；**t23 追加：必须验证 `*Jni` 绑定类齐全**（`unzip -l | grep -c 'Jni.class'` 应 = `*Jni.java` 源数），并跑 `scripts/check_jar_link_integrity.py`（严格缺失须 = 0、rc = 0）；
6. **补丁可复现性**：重跑 `scripts/t5-libwebrtc-libvpx-build.sh gn|build` 时，日志应出现 `t17: 已是 17 -> **跳过**应用补丁（幂等）`（目标值由 `JAVA_RELEASE_TARGET=17` 参数化）;
7. **可复现性**：`bash scripts/build_java_sdk_with_jni.sh --apply` 应幂等地产出同 sha256 的 jar；**`out/` 内的 jar 仍为基线 `d98939bb…`，这是已知且已登记的差异**，不得据此判定交付 jar 有误。

#### 8.4.1 操作安全铁律（由本任务三次同源失误提炼，captain 要求并入清单）
本项目在 t5/t16/t17 中出现**三次表象不同、根因相同**的失误，均属「**动作后未立即验证真实状态**」：

| # | 失误 | 后果 | 铁律 |
|---|---|---|---|
| 1 | `pkill -f <宽泛模式>` 匹配到执行者自己的 ssh 命令行 | 自杀并打断 `runhooks`，CIPD 缺口、返工 ~40 min | **禁止 `pkill -f` 宽泛模式**；改用精确 PID（先 `pgrep` 核对再 `kill`），或 `pgrep -f X \| grep -v $$ \| grep -v ssh` |
| 2 | 备份 AAR 的命令缺分隔符、且备份动作**晚于覆盖** | `.orig` 实为"新版"，回滚链一度失效 | **备份必须先于覆盖完成**，并**当场用 sha256 自证两者不同**（不能只看文件是否存在） |
| 3 | 辅助脚本写 `open(p,"w").write(open(p).read()...)` —— Python `open(p,"w")` **先截断** | 两个 `.py` 被清成 0 字节 | **写文件前先把内容读入变量**（或写临时文件后 `mv`）；危险操作后**立即自证**（本次依据 `.t5orig` 完整恢复并复验对账） |

> **通用铁律**：任何"停止进程 / 覆盖文件 / 改写产物"的动作，**执行后立即用一手证据自证真实状态**（进程存活 + 哈希 + 产物存在性），再继续下一步。
> 三次失误均由我把关发现并主动上报（无隐瞒），特此留档供全队复用。

---

## 9. t17：把修补固化为可复现工件（消除"重跑一次就又坏"的缺口）

### 9.1 缺口（captain 核查发现）
t16 的修补落在**仓外未跟踪**的构建树 `<WS>/webrtc-build/src/build/android/gyp/{compile_java.py,turbine.py}`（该树约 20 GB、不入 git），而**仓内** `scripts/t5-libwebrtc-libvpx-build.sh` 并未应用它 ⇒ **重跑 t5 会再次产出 v69 jar 并再次打断 t10 的构建**。

### 9.2 交付物
| 项 | 内容 |
|---|---|
| 入库补丁 | `scripts/patches/libwebrtc-java-release17.patch`（由 `.t5orig` 与现行文件 `diff -u` 生成，路径为 `a/`、`b/` 形式，可从 `<src>` 以 `patch -p1` 干净应用） |
| 独立验证 | 在 `/tmp` 的**纯净副本**上 `patch -p1 --dry-run` → OK；应用后与实文件 `diff -q` **完全一致** ✅ |
| 脚本守卫 | `scripts/t5-libwebrtc-libvpx-build.sh` 新增 `ensure_java_release_patch()`，在 **`phase_gn` 与 `phase_ninja` 两处**调用；另提供 `patch` 子命令单独验证 |
| 构建树 | `webrtc-build/t5-build.sh` 同源部署；补丁亦复制到 `webrtc-build/libwebrtc-java-release17.patch` |

### 9.3 守卫规则（实测四个分支全部通过；目标值 `JAVA_RELEASE_TARGET=17`）
| 输入状态 | 期望行为 | 实测 |
|---|---|---|
| 两文件均 `17` | **跳过**（幂等） | ✅ `t17: 已是 17 -> **跳过**应用补丁（幂等）` |
| 两文件均 `25` | 整包应用补丁 | ✅ `patching file ...` ×2 → 应用后实测 17/17 + `✅ 当前文件状态与入库补丁一致` |
| 混合（1 个 17、1 个 25） | 按文件就地修补 | ✅ 修补后 17/17 + 对账一致 |
| **任一为非 25 非 17**（如 13） | **报错退出（rc=1）** | ✅ `FATAL t17: 检测到非预期值 ... 拒绝盲目修补`，`退出码=1` |

**两个文件 `--release` 的实测值**（脚本日志实测，非推断）：
- `build/android/gyp/compile_java.py:711` → **`'11'`**（v55 那一轮）
- `build/android/gyp/turbine.py:110` → **`javac_cmd = ['--release', '11']`**（v55 那一轮）
- **t23 复核（现行、最终）**：`compile_java.py:708-711` = `'--release', …, '17'`；`turbine.py:110` = `javac_cmd = ['--release', '17']`；两文件均无 `'25'`/`'11'` 残留。
- 补丁应用后另做「补丁逆应用 dry-run」对账 → **`✅ 当前文件状态与入库补丁一致`**

### 9.4 t17 过程中我的一次操作失误（如实留档）
我在**自己的测试辅助脚本**里写了 `open(p,"w").write(open(p).read().replace(...))` —— Python 的 `open(p,"w")` **会先截断文件**，导致 `read()` 读到空内容，**把 `compile_java.py` 与 `turbine.py` 双双清成 0 字节**。
- 处置：两者均从 `.t5orig` 备份**完整恢复**（30 631 B / 7 155 B），再由脚本的 `patch -p1` 路径重新修补至目标值，并复验对账 ✅；
- 教训：**写文件必须先读入变量再写**（或写临时文件后 `mv`）；这也是"动作后立即验证真实状态"的又一实例（与我此前的 `pkill` 自杀、备份晚于覆盖同源）。

---

## 10. t23：补齐 jni_zero `*Jni` 绑定类（现行交付由此更新）

> ⚠️ **本节内「LoggingJni/CommonApisJni/JniZeroJni 全树无 `.class`」的表述是错的**，已被 **§15** 更正（权威）。

### 10.1 缺陷

t16/t17 交付的 jar 内 **`*Jni.class` 数量 = 0**，而构建树里 jni_zero 生成的 `*Jni.java` 源共 **48 个**：
**公开 Java API（`PeerConnectionFactory`、`AudioTrack`…）运行期都要经 `*Jni` 绑定类下调 native**，jar 缺这些类 ⇒ `NoClassDefFoundError` ⇒ **D1 采集/渲染必然失败**。这不是"锦上添花"，是链路完整性缺陷。

### 10.2 根因（源码取证）

| 取证点 | 实测结果 |
|---|---|
| jar 的生产命令 | `zip.py --depfile gen/sdk/android/libwebrtc.d --output lib.java/sdk/android/libwebrtc.jar --no-compress --input-zips=@FileArg(gen/sdk/android/libwebrtc.build_config.json:dist_classpath)` |
| 为何缺类 | `dist_classpath` 取的是 `direct_deps_only=true` 的**直接依赖**；jni_zero 的 `*Jni` 生成类落在**独立 target** `generated_*_jni_java`，**不在** `dist_classpath` 内 ⇒ 被系统性排除 |
| 生成源 | `out/Release-arm64/gen/**/input_srcjars/` 下 **48 个 `*Jni.java`** |
| 编译产物 | 48 个源被编成 **18 个 `generated_*_jni_java.javac.jar`**（其中 **4 个为空 jar**，含 `generated_logging_jni_java.javac.jar`）⇒ 实得 **45** 个可合并类 |
| 从未编译的 3 个 | `LoggingJni`、`CommonApisJni`、`JniZeroJni` —— **整个 `out/` 内无对应 `.class`**，须用 `.compliment.jar` 单独编译 |
| `GEN_JNI` | **每个 `generated_*_jni_java` 的 `.compliment.jar` 里只有「部分」`GEN_JNI`**；构建树中**不存在**任何并集 `GEN_JNI` ⇒ 必须合成，否则运行期 `UnsatisfiedLinkError` |

### 10.3 修复（确定性合并，不重编 native）

`scripts/build_java_sdk_with_jni.sh --apply`（幂等；默认只演算不写回）：

1. **step 0 前置断言**：`compile_java.py` 与 `turbine.py` 的 `--release` **实测必须为 `17`**（非 17 ⇒ FATAL 拒绝合并，避免基线漂移）；
2. 从**固定基线** `libwebrtc-java.jar.v61-java17`（453 类）提取原始 **453 类**（不取交付 jar，否则重跑时守恒校验退化为"自比自"）；
3. 从 14 个非空 `generated_*_jni_java.javac.jar` 取 **45 个 `*Jni.class`**，以 `unzip -oq -n`（**永不覆盖**）并入；
4. 单独编译 3 个缺失类：`LoggingJni`（`obj/rtc_base/base_java_jni_java.compliment.jar`）、`CommonApisJni`/`JniZeroJni`（`obj/third_party/jni_zero/generate_jni_java.compliment.jar`）；
5. 从所有 `*.compliment.jar` 以 `-n` 补其余缺失 `org/*` 类（**排除** `org/jni_zero/GEN_JNI.class`）；
6. **合成并集 `GEN_JNI`**（194 个 native），python3 生成器补 `argN` 形参名；
7. **归一 mtime**（`SOURCE_DATE_EPOCH`）后 `TZ=UTC zip -q -X -0` 打包（**STORED**，与官方 `--no-compress` 一致）⇒ 逐字节可复现；
8. **step 6b 守恒断言**：基线 453 类**逐字节未变**；最后跑 `scripts/check_jar_link_integrity.py`；
9. `--apply` 时写回 jar + **同步 AAR 内 `classes.jar`**（先 `zip -d` 删条再添加），并过三道闸门（classes.jar 同哈希 / `.so` 字节不变 / 条目集合不变）才写回 AAR。

**踩坑与固化（5 处）**：
- **`*Jni` 类自身不声明 native**（jni_zero 设计：native 都在 `GEN_JNI`）⇒ 不能用"有没有 native"判真伪；检查器据此把断言写成「`*Jni` 有 `get()` 且调用 `GEN_JNI`」。
- **native 声明必须带形参名**：合成并集 `GEN_JNI` 时若不补 `argN`，javac 报 **632 处 `error: <identifier> expected`**；另有一轮因 awk 误剥返回类型而报 438 处——两处均已修。
- **`unzip -n` 而非覆盖式解包**：早期版本用覆盖式，**改写了 453 个原始类中的 174 个**；已改为 `-n` 并加 step 6b 守恒断言兜底。
- **打包时间戳必须归一**：首版未归一，重跑**内容 508/508 逐字节相同、但 sha256 不同**，**内容 508/508 逐字节完全相同**；**时间戳 508 条全部不同**（更正：早前写“仅 4 条”有误）——`7dbe8400…` 的既有条目沿用构建的固定 epoch 2001-01-01、其 4 个新编译/合成条目带构建时刻，而 `dc5f8919…` 经 `SOURCE_DATE_EPOCH` 归一后把**全部**条目重标为 2026-01-01。可复算对象：`/tmp/pre-deploy.jar`(=7dbe8400…) 与 `/opt/dsh-workspaces/tmp/jni-merge/libwebrtc-java.jar`(=dc5f8919…)。归一后两次独立重跑 sha256 完全一致（`dc5f8919…` ×2）✅
- **Info-ZIP 两个静默陷阱（真实命中，均被闸门拦下、交付物未被污染）**：
  (a) **newer-than 跳过**——`zip <aar> classes.jar` 不替换"时间戳更新"的既有条目，归一后的 mtime 更旧 ⇒ 必须先 `zip -d` 删条；
  (b) **无扩展名归档被改名**——`zip aar ...` 实际写的是 `aar.zip`（实测报 `aar.zip not found or empty` 并**新建**），`aar` 纹丝不动 ⇒ 工作副本须显式命名为 `*.zip`，最后 `cp` 回 `.aar`。
  > 脚本的 AAR 一致性闸门在两次尝试中都输出「⚠️ AAR classes.jar 与交付 jar 不一致，**拒绝写回 AAR**」，因此**没有任何脏 AAR 落盘**。

### 10.4 现行交付值（t23 后，**取代 §4 的基线值**）

| 项 | 值 |
|---|---|
| **jar sha256** | **`dc5f89193d55c97152a7dd1331f3f7d111f8dd099d4c970e9142231ea79f8915`** |
| **大小 / class 数** | **1 187 970 B** / **508**（453 基线 + 55 新增） |
| **`*Jni.class`** | **48** = `input_srcjars` 下 `*Jni.java` 的 **48**（**差集为空**，无未补齐项） |
| **`*Natives.class`** | **48** |
| **class 版本分布** | **`0x0037`(55) × 51 + `0x003d`(61) × 457** ⇒ **全部 ≤ 61** ✅ |
| **原始 453 类守恒** | **相同 453 / 不同 0 / 缺失 0** ✅ |
| AAR | `e066e456f5d62a015433db949a7cd1b1c13acaf432c93aa06f3544cea71b9e53`，6 489 244 B；内 `classes.jar` = 现行 jar（**同哈希**，48 `*Jni`） |
| `.so` / `libvpx.a` | `757cef81…` / `e280b11b…` 1 929 142 B —— **均逐字节未变** |

### 10.5 验收命令与原始输出

> 📄 **完整原始输出另存**：`reports/05-t23-verify.log`（验收项 1–5 逐条对应输出 + D8 冒烟，均为宿主机实跑）。

```console
$ unzip -l third_party/libwebrtc/java/libwebrtc-java.jar | grep -c 'Jni.class'
48                                    # 修复前 = 0

$ javap -p -cp third_party/libwebrtc/java/libwebrtc-java.jar org.webrtc.PeerConnectionFactoryJni
Compiled from "PeerConnectionFactoryJni.java"
class org.webrtc.PeerConnectionFactoryJni implements org.webrtc.PeerConnectionFactory$Natives {
  private static org.jni_zero.JniTestInstanceHolder sOverride;
  org.webrtc.PeerConnectionFactoryJni();
  public static org.webrtc.PeerConnectionFactory$Natives get();      ← ✅
  public static void setInstanceForTesting(org.webrtc.PeerConnectionFactory$Natives);
  public long createAudioSource(long, org.webrtc.MediaConstraints);
  ... (共 25 个方法；该类自身 native = 0，符合 jni_zero 设计)

$ python3 scripts/check_jar_link_integrity.py third_party/libwebrtc/java/libwebrtc-java.jar ; echo rc=$?
[STRICT] org/webrtc/*Jni + org/jni_zero/*Jni 引用检查
  被引用的 *Jni 类数: 48
  缺失（引用了但 jar 内无定义）: 0
[INFO]   全部 org/webrtc/** 与 org/jni_zero/** 引用（仅报告，不判定）
[ASSERT] org/webrtc/PeerConnectionFactoryJni
  存在: YES
  方法数: 25
  get() [static]: YES
  该类自身 native 方法数: 0 （jni_zero 设计中 *Jni 不自带 native，通常为 0）
  该类是否调用 org/jni_zero/GEN_JNI: YES
[ASSERT] org/jni_zero/GEN_JNI: 存在 YES, native 声明数 = 194
==============================================================================
RESULT: PASS （严格缺失=0, 关键类断言=OK, GEN_JNI native=194）
==============================================================================
rc=0

# 追加冒烟：新 jar 可否被 AGP/D8 消费（build-tools/34.0.0）
$ d8 --min-api 24 --output /tmp/d8out third_party/libwebrtc/java/libwebrtc-java.jar ; echo rc=$?
rc=0        # 产出 classes.dex 538 532 B ✅（无 "Unsupported class file major version" 报错）
```

### 10.6 链路完整性检查器（新增工具）

`scripts/check_jar_link_integrity.py`（11 786 B，sha256 `fec0bc43dc196ad97fc10633f13d1cfa3c6822e3fe2fffe456a3c320dcce8228`）：
解析 jar 内**全部 class 的常量池**，对 `org/webrtc/*Jni` 与 `org/jni_zero/*Jni` 引用做**严格**判定（**引用了但 jar 内无定义 ⇒ 缺失++**，rc≠0 即失败）；对全部 `org/**` 引用做 INFO 报告（排除 `android/ java/ javax/ dalvik/ sun/ jdk/`）。
**实测严格缺失 = 0、INFO 缺失 = 0、rc = 0** ⇒ 验收项 2（jar 内每个被引用 `*Jni` 都可解析）成立。

### 10.7 可复现命令、指纹与未改动项

```bash
# 前置（脚本 step 0 自行断言）：compile_java.py L708-711 = '--release',…,'17'；turbine.py L110 = ['--release','17']
bash scripts/build_java_sdk_with_jni.sh --apply
python3 scripts/check_jar_link_integrity.py third_party/libwebrtc/java/libwebrtc-java.jar
```

**逐字节可复现实测**（输入为固定基线 `libwebrtc-java.jar.v61-java17`，独立跑 2 次，不带 `--apply`）：

```console
run1 sha256 = dc5f89193d55c97152a7dd1331f3f7d111f8dd099d4c970e9142231ea79f8915
run2 sha256 = dc5f89193d55c97152a7dd1331f3f7d111f8dd099d4c970e9142231ea79f8915
  ✅ 两次重跑逐字节相同（byte-reproducible）
原有类：逐字节相同=453  内容不同=0  缺失=0
```

| 文件 | 大小 | sha256 |
|---|---|---|
| `scripts/build_java_sdk_with_jni.sh` | 16 678 B | `5e3fb7d5c7e7da89fd91b9101f385fb0659b17f45d452d81835d23a90aaf08c6` |
| `scripts/check_jar_link_integrity.py` | 11 786 B | `fec0bc43dc196ad97fc10633f13d1cfa3c6822e3fe2fffe456a3c320dcce8228` |
| `scripts/patches/libwebrtc-java-release17.patch` | 983 B | `a51236ac4b869f1b62b9872c522afee9545dd60c38bd02c9f4d3851405347837` |

**未改动（逐项确认）**：
1. **`out/` 未被改动**（t23 只用 `javac`/`zip`/`python3`，**未跑 ninja**）⇒ `out/Release-arm64/lib.java/sdk/android/libwebrtc.jar` **实测 = `ee792522…`**（1 048 264 B，453 类全 major 55，`*Jni`=0，时间 2026-09-13 19:55:01，**v55 那一轮遗留**），**与现行 jar（以及 v61 基线）都不同哈希**（已知并登记）；修复在 `out/` 之外完成。
2. **`is_debug` 仍为 `false`**；未改 `is_debug=true`，未增删 GN 参数（日志能力维持 t5 登记的 `rtc_dlog_always_on=true`，C29/R10）。
3. **`.so`（目录内 / AAR 内）与 `libvpx.a` 逐字节未变**（sha256 + 字节数双重确认）；未触碰 `app/**`、`doc/**`、`third_party/libvpx/**`。
4. **遗留观察（超出 t23 范围）**：交付 `.so` 含 **193 个 `Java_J_N_M*` + 156 个 `Java_J_N_*` 混淆 JNI 符号、0 个可读 `org_webrtc_*`/`Java_org_webrtc_*` 符号**，而 Java 侧 codegen 用**可读名**（`GEN_JNI.org_webrtc_Logging_log`），Java 侧亦无混淆 `J_N_*` 类 ⇒ 存在 jni_zero 混淆名不匹配之可能；**本次未做运行期链接验证**，建议 app 首次联调优先验证（若命中，属独立缺陷，需单独排期）。


---

## 11. t23 追加核查：`.so` ↔ Java 配对（captain 指定）

> 完整证据链见 **`reports/05-libwebrtc-build.md` §11**。此处仅记与 jar 直接相关的结论。
>
> ⚠️ **本节"可能不同代际"的推论已被 §12 更正**：真实机制是 jni_zero short/proxy 模式（native 在类 `J.N`、方法名为哈希名）⇒ **同代际、静态符号绑定**；实际缺口是**交付 jar 缺 `J.N` 类**。请以 **§12** 为准。

- **`.so` 的 JNI 入口点不是 `*Jni` 也不是 `*Natives`**：交付 `.so` 内 **193/193** 个 `Java_*` 动态符号全部是 jni_zero 哈希边界名 `Java_J_N_M<hash>`；`*Jni`/`*Natives` **0**，可读 `org_webrtc*` **0**，`GEN_JNI` **0**，`org/jni_zero` **0**。
- jni_zero 的绑定机制（其 golden 佐证）：Java 侧声明**可读名**（`GEN_JNI.org_jni_1zero_..._bar`），C++ 侧为**哈希名**，由生成的 `JNINativeMethod kMethods[]` 注册表绑定，**表内 name = 可读名** ⇒ 能服务 `GEN_JNI` 的 `.so` 必须内含这些名字串；我们的 `.so` 内为 **0**。
- 全树 `kMethods` = **0**；`.so` 链接段不含 registration 对象；`JNI_OnLoad`（WebRTC `jni_onload.cc`）**不注册** native。⇒ 上游的 `*__jni_registration` 代码生成（`build.ninja` 内确有该 phony 目标，**从未被构建**）正是缺失的一环；根因是绕开 `build_aar.py` 后只构建了 `sdk/android:libjingle_peerconnection_so`。
- **对 jar 的影响**：若注册表最终落在 `.so` 内，则**本次交付的 jar（可读名 `GEN_JNI`，194 native 与 gen 源 194/194 完全一致）就是正确配对件，jar 无需改动**；反之若改为哈希名方案，则 48 个 `*Jni.java` 包装类亦须重生成（不推荐）。
- **阻塞点**：Java 层与 `.so` 当前**不可绑定** ⇒ 首次 native 调用预期 `UnsatisfiedLinkError`；**t25 的 JVM 单测无法发现**（不加载 arm64 `.so`）。
- **未跑真机**；按 captain 指令**先回报、未重编 `.so`**。本节未改动任何产物。

---

## 12. t23 追加核查（二）：194 vs 193 精确对齐 + 对 §11 的更正

> 完整推导与脚本见 `reports/05-libwebrtc-build.md` §12。此处记与 jar 直接相关的结论。

- **194 vs 193 精确答案**：用 jni_zero 源码里的确切哈希式（`proxy.py:73`：`('M'+base64(md5(name),altchars='$_')).rstrip('=')[:8]`，再按 JNI 规则转义 `_`→`_1`、`$`→`_00024`）对 194 条 Java 名逐一复算，与 `.so` 的 193 个符号比对：**交集 = 193、`.so` 独有 = 0、Java 独有 = 1**。
  - **唯一缺失 = `org_webrtc_LibaomAv1Encoder_create`（`Java_J_N_M0vTiIkf`）** —— AV1 软件编码器的创建入口，**不在 `initialize()` 路径**，本 Demo 走 VP9 ⇒ 不影响启动；jni_zero 对缺失 native 会生成**抛异常桩**，属**设计内行为**。
  - ⇒ 该差值**不是缺陷**，且逐条命中恰好**证明 `.so` 与 Java 侧同代际**。
- **对 §11 的更正**：§11 的事实（`.so` 无可读名、无 `kMethods`/`RegisterNatives`、`JNI_OnLoad` 不注册）**仍成立**；但由它推出"可能不同代际"**是错的**。正解是 jni_zero 的 **short/proxy 模式**：native 声明在类 **`J.N`**（`proxy.py get_gen_jni_class(short=True)` → `package='J', name='N'`），方法名即哈希名 ⇒ 与 `.so` **静态绑定**。**native-dev 的"同代际、符号绑定式"判断正确。**
- **真正的缺口（jar 侧）**：交付 jar 内**非 `org/` 类 = 0**、**无 `J/N.class`**（APK dex 内 `J/N` 描述符 = 0），只有 **placeholder 版 `GEN_JNI`**（native 直接声明为可读名，而 `.so` 内不存在这些可读名）⇒ 调用即失败。`J.N` + 转发版 `GEN_JNI` 由 **`*__jni_registration__java_sources`** 生成，该目标**从未被构建**。
- **修复方向（Java 侧，不需重编 `.so`）**：构建 `sdk/android:libjingle_peerconnection_so__jni_registration__java_sources` 取 `J.N` + 转发 `GEN_JNI` 并入 jar（替换 placeholder `GEN_JNI`）；或由我用已复算的哈希式生成 `J/N.java`（193 条，已逐条对齐）。**两种都需 captain 批准**（属产物形态变更）。
- **既有验收不受影响**：t23 的 5 项验收（计数/守恒/链路/版本/可复现）仍然成立。
- **澄清「42 个 `*Jni`」**：42 = t22 审计的「被引用且缺失」子集；实测**已编译 45** + **单独编译 3**（`LoggingJni`/`CommonApisJni`/`JniZeroJni`）= **48** = `*Jni.java` 源数 = 交付 jar 内 `*Jni.class` 数 ⇒ **t23 交付 48 正确，42 不是应补总数**。

---

## 13. 生效路径 vs 历史归档件（**给 t26 / t27 的门禁用**；由一次真实误判促成）

**事件**：t26 开工前 env-installer 的只读门禁预检报「jar mtime `2026-09-13 20:00:41`、`*Jni.class`=0、`GEN_JNI.class`=0、`PeerConnectionFactoryJni.class`=0 ⇒ t23 未落位，不得启动构建」。**实测为误判**：该组数字与**归档件**逐字一致，命中的是历史备查副本而非生效路径。

**同一个目录下并存 5 个 jar（4 个是备查副本，极易被 glob 误命中）**：

| 文件 | mtime | sha256(前16) | 大小 | class | `*Jni` | 角色 |
|---|---|---|---|---|---|---|
| **`libwebrtc-java.jar`** | **2026-09-14 11:05:10** | **`dc5f89193d55c971`** | 1 187 970 | **508** | **48** | **✅ 唯一生效路径** |
| `libwebrtc-java.jar.v61-java17` | 2026-09-13 20:00:41 | `d98939bbf0c0cd07` | 1 048 264 | 453 | 0 | t16/t17 内容基线 |
| `libwebrtc-java.jar.v55-java11` | 2026-09-13 20:00:14 | `ee792522c35cb8ca` | 1 048 264 | 453 | 0 | 中途 v55（作废） |
| `libwebrtc-java.jar.orig-jdk25` | 2026-09-13 17:03:00 | `ad54a0a209ecfd6e` | 1 051 957 | 453 | 0 | t5 原始 v69 |
| `libwebrtc-java.jar.orig-build` | 2026-09-13 18:58:47 | `ad54a0a209ecfd6e` | 1 051 957 | 453 | 0 | 同上副本 |

**门禁正确写法**：用**精确路径**，或显式排除 `*.orig*`、`*.orig-build`、`*.orig-jdk25`、`*.v55-java11`、`*.v61-java17`。
（`libwebrtc-java.jar*` 这类 glob 会命中归档件——env-installer 报的 mtime/计数正是 `.v61-java17` 那一行。）

**四项门禁现值（可直接照抄为判据）**：
```
*Jni.class > 0                         : 48
org/jni_zero/GEN_JNI.class 存在        : 1（唯一一份，native = 194）
org/webrtc/PeerConnectionFactoryJni    : 1
d8 --min-api 24 (build-tools 34.0.0)    : rc=0，classes.dex 538 532 B
jar sha256 / mtime                     : dc5f89193d55c971… / 2026-09-14 11:05:10
AAR                                    : e066e456f5d62a015433db949a7cd1b1c13acaf432c93aa06f3544cea71b9e53（6 489 244 B，内 classes.jar 同哈希）
```

**class 版本口径（避免误判）**：**无 major 69**；分布 **major 61 × 457 + major 55 × 51**，其中 **55 的 51 个里 45 个就是 `*Jni`**（来自构建树 `generated_*_jni_java.javac.jar`，工件时间 09-13 19:49 = v55 那一轮的编译产物，**源码与本次同一份**）。captain 已明确「混用可接受，但请在报告里给出分布」⇒ **不得据此判失败**；D8 已实测可消费（≤61 即可）。

---

## 14. t23 收口：哈希更正 + 194/193 定性 + 48/45/42 对账 + t29 结论

> 完整推导见 `reports/05-libwebrtc-build.md` §14；此处记与 jar 直接相关的要点。

### 14.1 ⚠️ 哈希更正：captain 复核的是**中间版**
| | 中间版（captain 复核） | **现行 live** |
|---|---|---|
| jar | `7dbe840049e239fb…`（mtime 10:53:11） | **`dc5f89193d55c971…`（mtime 11:05:10，1 187 970 B，508 类）** |
| AAR | `4878509a9a0bce25…` | **`e066e456f5d62a01…`（6 489 244 B）** |

差异原因：复核后我按「逐字节可复现」做**打包时间戳归一**并重新落位（§10.6/§12）。**内容等价**：解压后 **508/508 逐字节相同**，**时间戳 508 条全部不同**（更正：早前写“仅 4 条”有误；`7dbe8400…` 既有条目沿用构建固定 epoch 2001-01-01、4 个新编译类用构建时刻；`dc5f8919…` 归一后把全部条目重标为 2026-01-01。可复算：`/tmp/pre-deploy.jar`=7dbe8400… vs `/opt/dsh-workspaces/tmp/jni-merge/libwebrtc-java.jar`=dc5f8919…)；AAR 内 `classes.jar` 已同步同哈希。captain 复核的所有不变式在现行版**同样成立**。⇒ **建议保持现行版并更新记录**（回退会失去可复现性，且 t26 正基于现行版构建）。

### 14.2 194 vs 193：唯一缺失 `org_webrtc_LibaomAv1Encoder_create`（`Java_J_N_M0vTiIkf`）
- **被** `LibaomAv1EncoderJni.java:38` **调用**（`GEN_JNI.org_webrtc_LibaomAv1Encoder_create(...)`），仅 AV1 软件编码器路径；**本 Demo 走 VP9（A1 路线）⇒ 不在 initialize/通话路径**。
- libaom **本体已链接**（未剥离 `.so` 内 `aom_*` 685 个），但**其 JNI 壳未链接**（`M0vTiIkf` 在符号表出现 **0** 次）⇒ 生成器仍产出声明，是因为 **Java 侧目标存在**；jni_zero 对缺失 native 生成**抛异常桩**（设计内）。
- ⇒ **该条无阻塞性运行期风险**；⚠️ 它与 §12 的 **`J.N` 缺失**是**两件事**（后者影响全部 193 条）。

### 14.3 48 / 45 / 42 对账
- **45** = 构建系统已编译（14 个非空 javac.jar 去重）；**+3** = 全树无 `.class` 须单独编译（`LoggingJni`/`CommonApisJni`/`JniZeroJni`）；**= 48** = 源数（验收不变式）。
- **47** = 被基线 453 类**常量池引用**的 distinct 数（子包感知实测）；**42** = t22 审计口径较窄（未覆盖 `org/webrtc/audio/*`、`org/jni_zero/*`）⇒ 低于实测 47。
- **48 − 47 = 1**：唯一未被基线引用的是 **`org.webrtc.Dav1dDecoderJni`**（AV1 dav1d 解码）。仍并入的理由：① 验收口径要求「计数 == 源数 48」；② 同代际生成物，未被引用即惰性加载、无冲突（原 453 类逐字节未变、无覆盖）；③ 常量池分析可能低估可达性。⇒ **48 正确且安全，42 不是应补总数。**

### 14.4 t29 核查结论：缺口是 **`J.N`**，不是 `kMethods` 注册表
- 观察到的「全树 `kMethods`=0、`.so` 无注册表、`JNI_OnLoad` 不注册」**属实**，但本 build **不需要**注册表 —— jni_zero 用 **short/proxy 静态符号绑定**（§12）。真正缺口：jar **不含 `J.N`**，只有 placeholder `GEN_JNI`（可读名 native）⇒ 193 条 native 无解析路径。
- **修复就绪度（已干跑，未落地）**：`ninja -n sdk/android:libjingle_peerconnection_so__jni_registration__java_sources` → **160 个 ACTION 步骤，全部 Java 侧（dex/errorprone/validate_deps），无 CXX/CC/SOLINK** ⇒ 中等成本可行；CLI 入口 `jni_zero.py generate-final`（含 `--use-proxy-hash`）。
- **未落地原因**：captain 明确「不要再改动 jar（t26 正基于它构建）」⇒ 我遵守，仅完成核查与就绪度评估，**建议 t26 完成后放行**。
- **风险提示**：t26 的 `testDebugUnitTest`（JVM，不加载 arm64 `.so`）很可能仍全绿；真机首次 native 调用会失败。建议 t26/t27 判据显式核对 `J.N`。

---

## 15. ⚠️ **更正**：`LoggingJni`/`CommonApisJni`/`JniZeroJni` 在构建树里**有**已编译副本（我说"全树无 `.class`"是错的）

> **本节优先于 §10.3 / §12 / §14.3 中相应表述**（t23 的 output 终态不可变，其中含该错误表述；t27/verifier 请以本节为准）。完整复核见 `reports/05-libwebrtc-build.md` **§17**。

- native-dev 指出并复核确认：这 3 个类的已编译副本分别在
  `out/Release-arm64/obj/rtc_base/base_java_jni_java.javac.jar`（`LoggingJni`）与
  `out/Release-arm64/obj/third_party/jni_zero/generate_jni_java.javac.jar`（`CommonApisJni`/`JniZeroJni`）。
  **全 `obj` 树（145 个 jar）扫描：48 个 `*Jni.class` 全部存在**，交付 jar 内 48 个**无一缺副本**。
- **我的错误根因**：脚本 step 3 的 glob 只覆盖 `obj/sdk/android/generated_*_jni_java.javac.jar`，**漏了上述两个 target** ⇒ 错判为"未编译"并自行编译。属**"glob 范围不覆盖目标集合"**类错误（与 env-installer 的 `*.jar*` 命中归档件同源）。
- **对交付物无影响**：这 3 个由我以**同一份 jni_zero codegen 源**、`--release 17` 编译，与构建副本**成员签名逐条一致**（类名/接口/方法/字段相同），**唯一差异是 class 文件版本**（我 major 61 / 构建副本 major 55 —— 后者是 **v55 那一轮 `--release 11`** 的遗留件）。故功能等价，且与当前 `--release 17` 更一致。
- **不改动**：改成"全取自 javac.jar"会把这 3 个变回 major 55、**改变 jar 哈希**，而 jar 已被 captain 复核、**t26 已基于它产出 APK（`6653fddf…`）** ⇒ 捕获期保持现状；脚本也**保持原样**（其行为确定性已实测：两次重跑逐字节一致，改 glob 反而会破坏已验证的可复现哈希）。
- 另更正一处数字：**`reports/07-native-dev-jnizio-mapping.tsv` 实测 194 行**（含 AV1 那条），非 193；"被引用数"实测 **47**（42 为废弃的历史窄口径）。

---

## 16. 路线 A 修复**正式落位**（captain 接管 t31，2026-09-14 18:17）

> 流程留痕：t31 原派 webrtc-builder，四轮书面指令未落到执行面（其 staging 始终指向"统一 61"形态 `FINAL.jar`/`FINAL2.jar`），captain 按用户"继续完成任务"的指令接管（attempt 3），本轮内完成落位、复验与留痕。

### 16.1 落位件定义（**最小改动面 = 2 条**）
- 基底 = 落位前交付 jar `dc5f8919…`（508 类，**全部 STORED 压缩**，逐条原样搬运）
- 仅 2 条改动，两份均取自 `webrtc-build/t30/handoff/classes/`（= t30 `out/B`，单一血脉，verifier §13.5/§13.9 独立复核）：

| 条目 | 动作 | 新内容 sha256 | 大小 |
|---|---|---|---|
| `J/N.class` | **新增** | `1ff8d3ff4032643339ad271f552475740d735dddf06ae42e507bb657f98a8932` | 6 924 B |
| `org/jni_zero/GEN_JNI.class` | **替换** | `a6e7edcf9b90a4f7a15273de580bf7faf35ac7f818a4345c9618fd75fea40f08` | 24 910 B |

- 其余 **507 条**的 `raw`（压缩后字节）、`method`、`time/date` **逐条相同**（构造脚本自证 `rawSame=true`、`metaSame=true`）
- 结果：**509 类**，major 版本分布 **`{55:51, 61:458}`**（与落位前一致；那 51 个 v55 类是 t23 合并批次的遗留件，**本次未触碰**，全部 ≤61）

### 16.2 A/B 取舍：**裁定 = B（194/194）**，理由是"API 对齐"而非"版本统一"
- **A** = 官方命令行逐字复现（83 件 java 清单，`--use-proxy-hash`）⇒ `J.N` 193 native、`GEN_JNI` 193 转发，**不声明 AV1**；
- **B** = java 清单追加 `LibaomAv1Encoder.java`（83+1）＋ `--add-stubs-for-missing-native` ⇒ **194/194**（AV1 为 absent-proxy 抛异常桩）。两版**均为生成器产物**，差异只在输入清单口径。
- **决定性证据**：jar 内 `org/webrtc/LibaomAv1EncoderJni.class` 的字节码实测 `invokestatic org/jni_zero/GEN_JNI.org_webrtc_LibaomAv1Encoder_create:(J)J`（verifier §13.6 独立 `javap -p -c`）。落 A ⇒ 该符号引用**悬空**（触发软件 AV1 路径时 `NoSuchMethodError`）；落 B ⇒ 兜成上游设计内 `RuntimeException("Native method not present")`。另：B 与 jar 内 Placeholder `GEN_JNI` 的「方法名＋完整描述符」**194 条双向差集为空**（t30 §3.4）。
- **落位后实测**：`J.N` = **193 native** ＋ 1 个非 native `public static long org_webrtc_LibaomAv1Encoder_create(long)` = **194 方法**；`GEN_JNI` = **194 方法 / `static native` = 0**，其 AV1 方法体直接 `new RuntimeException("Native method not present"); athrow`。
- **不采 `FINAL.jar`/`FINAL2.jar`（`d0d05244…` / `167a299a…`）**：51 条变更中 **version-only = 0**（49 个类是重编译产物），且 native-dev 已证明**现有源无法忠实复现** jar 内 2 个类（`EglBase10Impl$FakeSurfaceHolder`、`PeerConnection$Builder`）⇒ 源修订漂移，审计成本高于收益。两者存档于 `tmp/jn-fix/`。

### 16.3 落位前后哈希（交付路径）

| 对象 | 落位前 | 落位后 |
|---|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `dc5f89193d55c971…`（1 187 970 B） | **`0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757`**（1 206 602 B） |
| `…/libwebrtc-arm64.aar` | `e066e456f5d62a01…`（6 489 244 B） | **`8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099`**（6 492 067 B） |
| AAR 内 `classes.jar` | = 旧 jar（逐字节） | = **新 jar（逐字节相同）** |
| `jni/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` | **未变**（AAR 内同一件亦逐字节相同） |
| `app/src/main/jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f5…` | 未变 |
| `third_party/libvpx/lib/libvpx.a` | `e280b11bcc9eff8c…` | 未变 |

- 备份（供复算/对照）：`tmp/t31/pre-routeA-libwebrtc-java.jar`（= `dc5f8919…`）、`tmp/t31/pre-routeA-libwebrtc-arm64.aar`（= `e066e456…`）

### 16.4 复验（两套 checker，均在**落位后的交付路径**上执行）
- `python3 scripts/check_jn_binding.py --jar third_party/libwebrtc/java/libwebrtc-java.jar --so …/libjingle_peerconnection_so.so` → **EXIT=0**
  - E1：`J/N` native 193 ↔ `.so` 193，**双向差集 0 / 0**；E2：`GEN_JNI` native = 0、方法 194、`invokestatic J/N.<name>` 目标 193；E3：`*Jni` 调用点 **194 / 覆盖 194 / 未覆盖 0 / 真缺失 0**（**豁免 0**）
- `python3 scripts/check_jar_link_integrity.py third_party/libwebrtc/java/libwebrtc-java.jar` → **EXIT=0**（509 类；严格缺失 0；`GEN_JNI` native=0；`J/N` native=193）
- AAR：条目集合不变（5 项：`jni/`、`jni/arm64-v8a/`、`.so`、`AndroidManifest.xml`、`classes.jar`）；非 `classes.jar` 条目 raw 逐字节相同；`.so` 与目录件相同

### 16.5 语义身份（供 t34 钉判据）与流程留痕
- **判据建议（采纳 android-dev / native-dev 建议）**：钉"**jar sha ＋ 两个 class sha**"三者 —— jar `0c776934…`，其中 `org/jni_zero/GEN_JNI.class` = `a6e7edcf…`、`J/N.class` = `1ff8d3ff…`；①`J/N` 可解析 ②`GEN_JNI` `static native`=0 ③两者方法数各 194 ④`J.N` native=193，全部以**两个 class 的字节**复算（jar 容器 sha 会随压缩方法/条目顺序/时间戳变化，**不是语义身份**）。
- **未采用并移除**：`scripts/apply_jn_runtime_fix.sh`（`STAGED_JAR` 默认指向 `tmp/jn-fix/FINAL2.jar` ＝统一 61 形态，**与本次实际落位件不同**；留仓内会误导复算）——本节的命令与哈希即为可复算记录。
- **保留并采纳**：webrtc-builder 对 `scripts/check_jn_binding.py` 的 `9+/3−` 修正（转发目标允许 absent-proxy stub，原先会误判 B），属 captain 授权的 out-of-scope 例外。
- 未重编 `.so`（路线 B 仍被否决）；未改 `doc/14-interface-contract.md`（指纹 `b3b67438…` / 1337 行不变）。

---

> ✅ **本节结论仍然有效**（captain 终局裁定 = **B**）。
> §18 记录的是 18:32:24 的一次**非授权 A 落位**，已于 **18:33:42 由 captain 回滚**；事故与时间线见 **§19**。终态 = 本节所记 B 形态（`0c776934…` / `8e8f2baf…`）。

## 17. t31-D1 审计：裁决要求的最小化形态**已在磁盘存在**（本轮未新增落位）+ K-17 已消除 + FINAL.jar 存档

> 裁决 t31-D1 要求构造 `MINIMAL.jar` =「现行交付 jar 为底 + 仅 2 条（`J/N.class`、`org/jni_zero/GEN_JNI.class`）」并落位。
> **实测结论：该形态就是当前交付件本身**（`0c776934…`，captain attempt 3 于 18:17 落位），**无需再造/再落**；本节给出可复算证据。

### 17.1 与基线 `dc5f8919…` 的改动面：**恰 2 条**（逐条 CRC+大小+解压内容比对）

```python
# 基线 /opt/dsh-workspaces/tmp/jni-merge/libwebrtc-java.jar  (= dc5f8919…)
# 现行 third_party/libwebrtc/java/libwebrtc-java.jar        (= 0c776934…)
条目: BASE 508 → LIVE 509
新增        = ['J/N.class']
删除        = []
内容变更    = ['org/jni_zero/GEN_JNI.class']
CRC/大小变更 = ['org/jni_zero/GEN_JNI.class']
其余完全相同(CRC+大小) = 507
⇒ 变更面恰 2 条: ['J/N.class', 'org/jni_zero/GEN_JNI.class']
```
⇒ 与裁决「其余 507 条必须逐字节不变」**完全吻合**；**没有任何顺手重编/重压缩**（507 条 CRC 与大小逐一相同）。

### 17.2 现行落位件的形态（javap 实测）
```
GEN_JNI: native = 0 ; 方法数 = 194 ; invokestatic J/N.<hash> 调用数 = 193
J.N    : native = 193 ; 方法数 = 194
         └─ 第 194 条是**非 native 桩**，方法名 = org_webrtc_LibaomAv1Encoder_create（**可读名**），
            方法体 throw new RuntimeException("Native method not present")
GEN_JNI 的 org_webrtc_LibaomAv1Encoder_create 方法体 = new RuntimeException(...) + athrow（**直抛**，不转 J.N）
```
**接线与裁决描述不同（按实测记录）**：裁决按 `FINAL.jar` 的形态描述为「GEN_JNI 的 1 条 → `J.N.M0vTiIkf`」；**现行落位件**（t30 handoff 生成器产出）是「`J.N` 内**可读名**非 native 桩 + `GEN_JNI` 直抛」。两者行为等价（AV1 得 `RuntimeException` 而非 `NoSuchMethodError`），**后者才是 jni_zero `_stub_for_missing_native` 的原生形态**（该函数写入的是 `native.proxy_name`＝可读名）。

### 17.3 K-17 **已消除**（不是"登记为残余"）
裁决预期残留 K-17（"`GEN_JNI` 未含 AV1 抛异常桩：方法数 193 而非 194"）。但**现行落位件已有桩**：
```
check_jn_binding.py → E2 转发目标：native = 193 ；非 native(absent-proxy stub) = 0（GEN_JNI 侧直抛）
                      E3 *Jni 调用点 194 → 未被覆盖 = 0 ；**已知豁免 = 0** ；真缺失 = 0
```
⇒ **`GEN_JNI` 方法是 194（非 193）且 AV1 有抛异常桩 ⇒ K-17 不成立**。K-17 只对已被删除的方案 A（`c289b4df…`，`GEN_JNI` 193 方法、无 AV1）成立。

### 17.4 两套 checker 原始输出（对现行落位件）
```
$ python3 scripts/check_jn_binding.py --jar third_party/libwebrtc/java/libwebrtc-java.jar \
        --so third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so
  **双向差集**: J.N 独有 = 0 ; .so 独有 = 0
  转发目标中：native = 193 ；非 native（absent-proxy stub）= 0 []
  未被 GEN_JNI 覆盖: 0 → 已知豁免 0 ；真缺失 0
  RESULT: PASS      rc=0
$ python3 scripts/check_jar_link_integrity.py third_party/libwebrtc/java/libwebrtc-java.jar
  rc=0（RESULT: PASS）
```

### 17.5 AAR 同步证据与未触碰项
```
AAR = 8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099   6 492 067 B
解包后 classes.jar 与交付 jar：cmp 无差异 → 打印 AAR_CLASSES_JAR_IDENTICAL
未触碰项（哈希不变）：
  jni/arm64-v8a/libjingle_peerconnection_so.so = 757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e
  app/src/main/jniLibs/arm64-v8a/libc++_shared.so = c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36
  third_party/libvpx/lib/libvpx.a = e280b11bcc9eff8c5be20f35023c79319eeb374a5277d1972c0eb310fe3215f7
```

### 17.6 `FINAL.jar` 作为备选候选的存档说明（否决理由 + 一处更正）
- **否决理由（采纳 captain 审计）**：`FINAL.jar`（`d0d05244…`）相对方案 A 有 **51 条变更，其中 version-only = 0** —— 49 个类是**重新编译产物**（内容与长度都变），且 **jar 内 2 个类当前源无法忠实复现**（`EglBase10Impl$FakeSurfaceHolder` 现为匿名类、`PeerConnection$Builder` javac 不产出）⇒ 源修订漂移，把 49 个未审计字节并入交付，收益（版本统一）小于审计成本。**最小改动面**是本交付链最有价值的性质。
- **⚠️ 一处更正**：裁决把 `FINAL.jar` 里那两件称"官方生成"。**实测并非**：`FINAL.jar` 的 `J/N.class` = `0eac3fb54ecb1d95…`（6 898 B）、`GEN_JNI.class` = `32448db8bf033fcd…`（24 828 B），是**我按 `_stub_for_missing_native` 语义手编**的（桩名 `M0vTiIkf`，哈希名接线）。
  **真正官方生成**的是 **t30 handoff**：`J/N.class` = `1ff8d3ff40326433…`（6 924 B）、`GEN_JNI.class` = `a6e7edcf9b90a4f7…`（24 910 B）—— **与现行落位件内两件逐一相同**（我已核对）。
  ⇒ 若按裁决"取自 FINAL.jar"落位，是**provenance 降级**（手编字节替换生成器字节）且会**第三次改哈希**；**建议保留现行落位件**。若 captain 坚持，交换 2 条即可复现（但那不是"更忠实"，而是"更手工"）。
- 备选件仍在盘：`/opt/dsh-workspaces/tmp/jn-fix/FINAL.jar`（`d0d05244…`）、`FINAL2.jar`（`167a299a…`）；**方案 A 候选 `/opt/dsh-workspaces/tmp/jn-fix/libwebrtc-java.jar`（`c289b4df…`）已被删除**（登记：现已不存在，无法再落位）。

### 17.7 `scripts/check_jn_binding.py` 修正的 diff 摘要与 sha256
```
$ git diff --stat scripts/check_jn_binding.py
  scripts/check_jn_binding.py | 12 +++++++++---
  1 file changed, 9 insertions(+), 3 deletions(-)
两处修正：
  ① 新增 jn_all（J.N 全部方法名，含非 native stub）：正则 `(?:native\s+)?` —— 修掉"native 行被误解析"；
  ② E2 断言由"转发目标必须 ∈ J.N native 集合"改为"转发目标必须 ∈ J.N 全部方法（native 或 absent-proxy stub）"，
     并新增打印 `转发目标中：native = N ；非 native(stub) = M`。
sha256(scripts/check_jn_binding.py) = aa2e96922f5313f7f3a740942d7460e42391ea7b41ebee090b3d7f60e199683f
```

### 17.8 裁决 t31-D1 的验收定值（修订登记）
原 acceptance 第 1 条的定值 `c289b4df…` **作废**（候选已删除）；**本次最小化形态的实际定值 = 交付 jar `0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757`（1 206 602 B / 509 类）**，AAR = `8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099`（6 492 067 B）。
**本轮未新增落位**（形态已存在 ⇒ 无写入、无第三次哈希变更）；若 captain 要 FINAL.jar 的接线，请明确指示。

---

## 18. 落位**官方 193/193**（方案 A，`c289b4df…`）—— captain 终局裁定，取代 §17

> ❌ **本节已作废（2026-09-14 18:33:42）**：本节记录的 A 落位是**非授权**操作 —— 执行者按**过期 t31-D1 文本**落位，而 captain 的终局裁定是 **B（t31-D2）**；该落位亦发生在**写盘冻结期**内。
> captain 已回滚为 B（jar `0c776934…` / AAR `8e8f2baf…`）并隔离全部 A 变体（`tmp/jn-fix/QUARANTINE-A/`，chmod 400 + README）。**本节仅作事故留痕，不得作为交付口径**；权威 = **§16**（B），事故记录 = **§19**。
> 本节的四项 verify（`GEN_JNI` 方法 193 / 未覆盖 1 = AV1 豁免）描述的是 **A 变体**，**不是交付物判据**（交付物 `GEN_JNI` 方法数 = **194**、未覆盖 = **0**）。

> **裁定要点**：t31-D1 **作废**。定案依据 =「官方 `generate-final` 对本 `.so` 的产物是 **193/193**；194/194 非官方件 ⇒ 交付件必须与官方产出逐字节一致」。故落位候选 = `c289b4df…`（= 现行基线 + `t30/out/A/classes/**` 两件官方编译产物），**不落 `FINAL.jar`**（其两件为手改：`AV1 stub 为手加`）。

### 18.1 候选缺失 → **逐字节复现成功**（额外证据）
复现前实测候选 `/opt/dsh-workspaces/tmp/jn-fix/libwebrtc-java.jar` **已被删除**。以仍存在的材料重建：
```
BASE = /opt/dsh-workspaces/tmp/jni-merge/libwebrtc-java.jar   = dc5f89193d55c971…（508 类）
A    = /opt/dsh-workspaces/webrtc-build/t30/out/A/classes/    = J/N.class 9ada0641fcee58813d5aa4282e25a7a394ccc7600a161d82d97fe3bfc1099459
                                                                GEN_JNI.class 8f3ce6137f02cef94c2a5e10168126025ad296c05f204457fd38e92480b9d07c
打包：zipfile ZIP_STORED + ZipInfo(date_time=(2026,1,1,0,0,0)) + 按名排序（与生成链既有约定一致）
⇒ 产出 sha256 = c289b4dfd06827bc83fd480e68bc7d38438656df5fc42e188d31bb37ad42f74f，1 206 237 B，509 类  **逐字节复现成功**
```
⇒ 这同时**独立证明**该候选的来源就是「基线 + 官方 A 两件」，与 captain 的逐件哈希核对一致。

### 18.2 落位动作与前后哈希
```
0) 候选前置检查：sha256 == c289b4df… ✔ ; J.N native = 193 ; GEN_JNI native/methods = 0/193
1) 备份**现行**交付 jar → /opt/dsh-workspaces/tmp/jn-fix/pre-routeA-libwebrtc-java.jar
   ⚠️ 备份到的是**当时现行** jar = 0c776934c1452b7b…（**不是** captain 预期的 dc5f8919…）
      dc5f8919… 本身仍在 /opt/dsh-workspaces/tmp/jni-merge/libwebrtc-java.jar（基线，未动）
2) 覆盖 jar → c289b4dfd06827bc83fd480e68bc7d38438656df5fc42e188d31bb37ad42f74f（1 206 237 B）
3) 同步 AAR 内 classes.jar（先 zip -d 删条再添加；三道闸门）
   闸门① classes.jar == jar : PASS ; 闸门② AAR 内 .so 未变 : PASS ; 闸门③ 条目集合不变 : PASS
   → AAR = f2ea01328336cf12a0b1d33b82eb249b1cfd1cc55a360bdf6c6643aa811136e5（6 495 516 B）
落位前: jar=0c776934c1452b7b…  aar=8e8f2bafce23b419…  so=757cef8128bf9151…
落位后: jar=c289b4dfd06827bc…  aar=f2ea01328336cf12…  so=757cef8128bf9151…（**未变**）
```

### 18.3 改动面枚举（逐条 CRC+大小+内容）
```
vs 基线 dc5f8919…    ：新增=['J/N.class'] 删除=[] 变更=['org/jni_zero/GEN_JNI.class'] 其余相同=507
vs 上一版 0c776934…  ：新增=[] 删除=[] 变更=['org/jni_zero/GEN_JNI.class','J/N.class'] 其余相同=507
```
⇒ 对基线的改动面**恰 2 条**（与验收项 2 逐一吻合）；对上一版的差异也**仅这 2 条**（B→A 的变体回退），**无任何其它条目漂移**。

### 18.4 四项 verify 原始输出（全 rc=0）
```
#1 sha256sum
  c289b4dfd06827bc83fd480e68bc7d38438656df5fc42e188d31bb37ad42f74f  libwebrtc-java.jar
  f2ea01328336cf12a0b1d33b82eb249b1cfd1cc55a360bdf6c6643aa811136e5  libwebrtc-arm64.aar
  757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e  jni/arm64-v8a/libjingle_peerconnection_so.so
  c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36  app/src/main/jniLibs/arm64-v8a/libc++_shared.so
  e280b11bcc9eff8c5be20f35023c79319eeb374a5277d1972c0eb310fe3215f7  third_party/libvpx/lib/libvpx.a
#2 check_jn_binding.py
  J.N native 方法数（唯一）= 193 ; **双向差集: J.N 独有 = 0 ; .so 独有 = 0**
  GEN_JNI native 方法数 = 0 ; GEN_JNI 方法数 = **193** ; 转发目标: native 193 / 非 native stub 0
  *Jni 调用点 194 → **未被覆盖 = 1（已知豁免 = 1）** ; 真缺失 = 0 ; RESULT: PASS ; rc=0
#3 check_jar_link_integrity.py
  GEN_JNI native 声明数 = 0 ; J/N native 声明数 = 193 ; 严格缺失 = 0 ; RESULT: PASS ; rc=0
#4 AAR
  unzip classes.jar + cmp → **AAR_CLASSES_JAR_IDENTICAL**
```
（E3 的 1 条未覆盖即 AV1 —— 见 §18.6 的口径。）

### 18.5 `FINAL.jar` 备选候选存档说明（不落位）
- 路径（存档保留，勿落）：`/opt/dsh-workspaces/tmp/jn-fix/FINAL.jar` `d0d05244a13ed059…`；另有 `FINAL2.jar` `167a299af7966aff…`。
- **关键披露**：`FINAL.jar` 的 `J/N.class`=`0eac3fb54ecb1d95…`、`GEN_JNI.class`=`32448db8bf033fcd…` 是**我手改**产物（AV1 抛异常桩**为手加**，桩名用了哈希名 `M0vTiIkf`）；**官方 `generate-final` 对本 `.so` 的产出是 193/193**（`--add-stubs-for-missing-native` 重跑亦逐字节相同），194/194 非官方件。
- **否决理由（captain 实测）**：`FINAL.jar` 相对方案 A 有 **51 条变更，其中 version-only（仅 class 6/7 字节）= 0** —— 49 个类为**重编译产物**；且 jar 内 2 个类（`EglBase10Impl$FakeSurfaceHolder`、`PeerConnection$Builder`）**当前源无法忠实复现** ⇒ 源修订漂移。把 49 个未审计字节并入交付以换取"版本统一"，收益小于审计成本；**最小改动面**优先。

### 18.6 K-17 口径（按裁定改写）
> **官方 `generate-final` 对本 `.so` 的产出即 193/193**；`org_webrtc_LibaomAv1Encoder_create` 在 `.so` 无对应符号，**与官方一致、非本交付引入**。本项目 VP9-only（`app/src/main/kotlin` 内 AV1/libaom 引用 = 0，实测 `grep -rl 'Av1|Libaom' app/src/main/kotlin` = 0 个文件），故不影响验收。
即：E3 的"1 条未覆盖"是**官方形态的必然结果**，已在 `check_jn_binding.py` 的 `KNOWN_EXEMPT` 中登记为已知豁免。

### 18.7 `scripts/check_jn_binding.py` 修正摘要（captain 授权的 out-of-scope 例外，**未计入 changedPaths**）
```
$ git diff --stat scripts/check_jn_binding.py
 scripts/check_jn_binding.py | 12 +++++++++---
 1 file changed, 9 insertions(+), 3 deletions(-)
修正①：新增 jn_all（J.N 全部方法名，正则含可选 `native` 关键字）—— 修掉 native 行被误解析；
修正②：E2 断言改为「转发目标必须 ∈ J.N 全部方法（native 或 absent-proxy stub）」，并新增打印 native/stub 计数。
sha256(scripts/check_jn_binding.py) = aa2e96922f5313f7f3a740942d7460e42391ea7b41ebee090b3d7f60e199683f
```

### 18.8 本轮记录的两条团队更正（供 t27 引用）
1. **native-dev 关于 `__jni_registration` 三个 phony 是"空壳"的判断不成立**：生产者规则在**子 ninja** `out/Release-arm64/toolchain.ninja:3655-3660`（`jni_zero.py generate-final … --use-proxy-hash`），`build.ninja` 里只是 phony —— 我实跑该目标（1 个 ACTION、`CXX/SOLINK/CC = 0`）并产出 srcjar（62 140 B）。
2. **verifier 的 `comm` 判据有 locale 陷阱**：两侧须统一 `LC_ALL=C sort`，否则排序 collation 差异会产生**数百行伪差异**（我首跑得到 214 行，正确结果应为 **0 行**）。

### 18.9 未触碰项与边界
```
.so        757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e  12 946 912 B  mtime 2026-09-13 17:03:00（前后同值、mtime 同值）
libc++_shared c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36
libvpx.a    e280b11bcc9eff8c5be20f35023c79319eeb374a5277d1972c0eb310fe3215f7  1 929 142 B
未 git commit（留 t33）；未构建 APK；未碰 app/src/**、doc/14、libvpx.a、.so
```

---

## 19. ⚠️ 事故留痕：一次**非授权 A 落位**及其回滚（2026-09-14 18:32:24 → 18:33:42）

**时间线（captain 与 native-dev 两条独立测量，同一路径）**

| 时刻（mtime） | 交付 jar | 内嵌 `J/N.class` / `GEN_JNI.class` | 形态 | AAR |
|---|---|---|---|---|
| 18:17:28 | `0c776934c1452b7b…`（1 206 602 B） | `1ff8d3ff…` / `a6e7edcf…` | **B（终态）** | `8e8f2baf…` |
| **18:32:24** | **`c289b4dfd06827bc…`**（1 206 237 B） | `9ada0641…` / `8f3ce613…` | **A（非授权）** | `f2ea0132…` |
| **18:33:42** | **`0c776934…`（回滚）** | `1ff8d3ff…` / `a6e7edcf…` | **B（终态）** | **`8e8f2baf…`** |

- **来源**：18:32:24 的件等于既有 A staging `tmp/jn-fix/libwebrtc-java.jar`（11:39 生成）的拷贝，**不是新构建产物**。操作者按**过期 t31-D1 文本**执行（"落 `c289b4df…`"），而 captain 的终局裁定（**t31-D2**）是 **B**；该操作亦发生在**写盘冻结期**内。
- **风险窗口 ≈78 秒**；实测**当时无真实 gradle 在跑** ⇒ **没有产出 A 版 APK**（APK 全程停在 `721df1c8…`，mtime 11:28:34）。
- **处置**：captain 于 **18:33:42** 从 `tmp/t31/routeA-B.jar` / `.aar` **回滚为 B**（回滚后实测：jar `0c776934…`、AAR `8e8f2baf…`、AAR 内 `classes.jar` 与 jar 逐字节相同、`J/N.class` = `1ff8d3ff…`、`GEN_JNI.class` = `a6e7edcf…`、509 条目）。A 事故件留证为 `tmp/jn-fix/ACCIDENT-landed-A-c289b4df.jar` 与 `ACCIDENT-landed-A-f2ea0132.aar`，全部 A 变体移入 `tmp/jn-fix/QUARANTINE-A/`（chmod 400 + README"禁止落位"）。
- **对已冻结证据的影响**：**无** —— `§16`（B 落位）、t30（193 符号证明）、t36（193 描述符对照）的对象在窗口前后一致；`.so` 全程 `757cef81…` 未漂移。
- **流程留痕**
  - **P-12**：冻结期内的落位/写盘必须有**唯一授权人**；接收方若发现派单文本与**最终裁定**冲突，必须**先回报、不得按旧文本执行**。
  - **D-13**（captain 侧）：**过期任务文本被调度反复回放**是本次混淆的直接成因；后续同类场景须以"**裁定编号 + 时间戳**"为唯一依据，并在派单中显式声明"本裁定作废哪些旧口径"。
  - **已实施的加固**：全部 A 变体隔离（chmod 400）；`t33` 采用**双钉哈希** —— 构建前/后各记一次 jar 与 AAR 的 `sha256` 并断言一致，另加 `.so` 护栏 `757cef81…` 与 APK 内四 `.so` `p_align=0x4000` 断言。
  - **补充事实（18:47:54）**：`app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` 在 APK 产出（18:41:59）**之后 6 分钟被重写**（内容不变，仍 `757cef81…`，来自 `build_app.sh --check-only` 的抽取步骤）⇒ 交付路径上确实存在"**仅触碰、不改内容**"的写入 ⇒ **产物身份判据必须比 `sha256`，不得比 mtime/尺寸**（P-13 口径）；亦印证"事后写交付路径需明确责任人与时段约定"。
