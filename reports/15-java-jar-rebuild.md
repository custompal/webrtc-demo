# 15 — 原生重编 libwebrtc Java SDK（t16/t17，**Java 17 / major 61**；t23 补 `*Jni` 后为现行交付）

> **现行交付已更新（t23）**：本报告 §1–§9 记录的 `d98939bb…`（453 类，v61）自 t23 起**降级为内容基线**；
> **现行交付 jar = `7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`（1 187 970 B，508 类，含 48 个 jni_zero `*Jni` 绑定类）**，AAR = `4878509a…`。见本报告 **§10** 与 `reports/05 §10`。

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
| **sha256** | 基线 **`d98939bbf0c0cd071baff19004a4dc2f602997b70e33e0fc3669c5fccb5f6543`**<br>**现行（t23）= `7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`** |
| **class 版本** | 基线 **453/453 = major 61（Java 17）**；**现行 508 个 = 51× major 55 + 457× major 61，全部 ≤ 61** ✅ |
| 生产端同源 | `out/Release-arm64/lib.java/sdk/android/libwebrtc.jar`：**仅与基线同哈希 `d98939bb…`**；t23 的合并发生在 `out/` 之外 ⇒ **该文件 ≠ 现行 jar**（已知且已登记，见 §10.7） |
| AAR 内 `classes.jar` | 已同步更新（见 §5），**t23 后与现行 jar 全 64 位同哈希 `7dbe8400…`**；AAR 本身 = `4878509a9a0bce254d71f728fb1ff6635b7fea8e310342216b454672f8dfe028`（6 489 348 B） |
| 备查 | `libwebrtc-java.jar.v55-java11`（**中途 Java 11 版本，已归档作废**）、`libwebrtc-java.jar.orig-jdk25`（t5 原始 v69）、`libwebrtc-java.jar.v61-java17`（**t23 前基线副本**，t23 的守恒比对基准） |

**AAR（t23 重打包后）**：`third_party/libwebrtc/java/libwebrtc-arm64.aar` = 6 489 348 B，sha256 `4878509a9a0bce254d71f728fb1ff6635b7fea8e310342216b454672f8dfe028`；其 **内部 `.so` 逐字节未变**（sha256 `757cef81…`，见证据 4）。t23 前的 AAR 备份为 `libwebrtc-arm64.aar.pre-t23`（`456e3f2f…`）。
> 注：不能以文件大小判断 AAR/jar 版本（各版本大小接近）；**判据是内含 `classes.jar` 的 sha256**。

**产物哈希一览（现行，t23 后）**：
| 产物 | sha256 | 大小 |
|---|---|---|
| `libwebrtc-java.jar`（交付） | **`7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`** | 1 187 970 |
| AAR 内 `classes.jar` | **同左（全 64 位相同）** | 1 187 970 |
| `libwebrtc-arm64.aar` | **`4878509a9a0bce254d71f728fb1ff6635b7fea8e310342216b454672f8dfe028`** | 6 489 348 |
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
| `libwebrtc-java.jar` | 1 187 970 | **现行交付（t23）**：508 class，含 48 个 `*Jni`，sha256 `7dbe840049e239fb…`（t16/t17 基线为 1 048 264 B / `d98939bb…` / 453 class major 61） |
| `libwebrtc-java.jar.v55-java11` | 1 048 264 | 中途 v55 的归档副本（**已作废**，非现行） |
| `libwebrtc-java.jar.v61-java17` | 1 048 264 | t16/t17 的 v61 **内容基线副本**（major 61，**t23 的守恒比对基准**） |
| `libwebrtc-java.jar.orig-jdk25` | 1 051 957 | **t5 原始产物**（major 69，sha256 `ad54a0a209ecfd6e…`） |
| `libwebrtc-java.jar.orig-build` | 1 051 957 | 同上另一副本（内容与 `.orig-jdk25` 一致） |
| `libwebrtc-arm64.aar` | 6 489 348 | 现行 AAR（t23 重打包），内含与现行 jar 同哈希的 `classes.jar`（`7dbe8400…`） |
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
3. jar sha256 应为 **`7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`**，1 187 970 B、508 类、**48 个 `*Jni`**；且与 **AAR 内 `classes.jar` 全 64 位同哈希**；AAR = `4878509a…`，6 489 348 B；
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
2. 从 t17 基线 jar 提取原始 **453 类**；
3. 从 14 个非空 `generated_*_jni_java.javac.jar` 取 **45 个 `*Jni.class`**，以 `unzip -oq -n`（**永不覆盖**）并入；
4. 单独编译 3 个缺失类：`LoggingJni`（`obj/rtc_base/base_java_jni_java.compliment.jar`）、`CommonApisJni`/`JniZeroJni`（`obj/third_party/jni_zero/generate_jni_java.compliment.jar`）；
5. 从所有 `*.compliment.jar` 以 `-n` 补其余缺失 `org/*` 类（**排除** `org/jni_zero/GEN_JNI.class`）；
6. **合成并集 `GEN_JNI`**（194 个 native），python3 生成器补 `argN` 形参名；
7. `zip -q -X -0` 打包（**STORED**，与官方 `--no-compress` 一致）；
8. **step 6b 守恒断言**：原始 453 类**逐字节未变**；最后跑 `scripts/check_jar_link_integrity.py`。

**踩坑与固化（3 处）**：
- **`*Jni` 类自身不声明 native**（jni_zero 设计：native 都在 `GEN_JNI`）⇒ 不能用"有没有 native"判真伪；检查器据此把断言写成「`*Jni` 有 `get()` 且调用 `GEN_JNI`」。
- **native 声明必须带形参名**：合成并集 `GEN_JNI` 时若不补 `argN`，javac 报 **632 处 `error: <identifier> expected`**；另有一轮因 awk 误剥返回类型而报 438 处——两处均已修。
- **`unzip -n` 而非覆盖式解包**：早期版本用覆盖式，**改写了 453 个原始类中的 174 个**；已改为 `-n` 并加 step 6b 守恒断言兜底。

### 10.4 现行交付值（t23 后，**取代 §4 的基线值**）

| 项 | 值 |
|---|---|
| **jar sha256** | **`7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`** |
| **大小 / class 数** | **1 187 970 B** / **508**（453 基线 + 55 新增） |
| **`*Jni.class`** | **48** = `input_srcjars` 下 `*Jni.java` 的 **48**（**差集为空**，无未补齐项） |
| **`*Natives.class`** | **48** |
| **class 版本分布** | **`0x0037`(55) × 51 + `0x003d`(61) × 457** ⇒ **全部 ≤ 61** ✅ |
| **原始 453 类守恒** | **相同 453 / 不同 0 / 缺失 0** ✅ |
| AAR | `4878509a9a0bce254d71f728fb1ff6635b7fea8e310342216b454672f8dfe028`，6 489 348 B；内 `classes.jar` = 现行 jar（**同哈希**，48 `*Jni`） |
| `.so` / `libvpx.a` | `757cef81…` / `e280b11b…` 1 929 142 B —— **均逐字节未变** |

### 10.5 验收命令与原始输出

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

| 文件 | 大小 | sha256 |
|---|---|---|
| `scripts/build_java_sdk_with_jni.sh` | 13 336 B | `538aae2cd5f63ad7876d5684081595bcb70fa398887fed3738635d05eed7f220` |
| `scripts/check_jar_link_integrity.py` | 11 786 B | `fec0bc43dc196ad97fc10633f13d1cfa3c6822e3fe2fffe456a3c320dcce8228` |
| `scripts/patches/libwebrtc-java-release17.patch` | 983 B | `a51236ac4b869f1b62b9872c522afee9545dd60c38bd02c9f4d3851405347837` |

**未改动（逐项确认）**：
1. **`out/` 未被改动** ⇒ `out/Release-arm64/lib.java/sdk/android/libwebrtc.jar` 仍为 `d98939bb…`（453 类），**与现行 jar 不同哈希**（已知并登记）；修复在 `out/` 之外完成。
2. **`is_debug` 仍为 `false`**；未改 `is_debug=true`，未增删 GN 参数（日志能力维持 t5 登记的 `rtc_dlog_always_on=true`，C29/R10）。
3. **`.so`（目录内 / AAR 内）与 `libvpx.a` 逐字节未变**（sha256 + 字节数双重确认）；未触碰 `app/**`、`doc/**`、`third_party/libvpx/**`。
4. **遗留观察（超出 t23 范围）**：交付 `.so` 含 **193 个 `Java_J_N_M*` + 156 个 `Java_J_N_*` 混淆 JNI 符号、0 个可读 `org_webrtc_*`/`Java_org_webrtc_*` 符号**，而 Java 侧 codegen 用**可读名**（`GEN_JNI.org_webrtc_Logging_log`），Java 侧亦无混淆 `J_N_*` 类 ⇒ 存在 jni_zero 混淆名不匹配之可能；**本次未做运行期链接验证**，建议 app 首次联调优先验证（若命中，属独立缺陷，需单独排期）。

