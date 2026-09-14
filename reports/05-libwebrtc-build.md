# 05 — libwebrtc(android arm64) + libvpx 编译报告（t5）

> 状态：**✅ 编译完成（硬交付三件全部达成）**。本报告含实际 gn args、ninja 并行度、耗时、磁盘占用、完整产物清单与绝对路径、
> release 下日志可用性结论、失败与返工记录（含作者自身失误）、以及 AAR 等价性论证。

- 任务：t5（work，依赖 t3+t4）— 编译 libwebrtc(android arm64) 与 libvpx 并回传产物
- 执行人：webrtc-builder（attempt 1，attempt_id `2a718b15-6ad7-476d-957b-4a245ed51705`）
- 宿主：Ubuntu 24.04.2 / 4 vCPU(2 物理核×2HT) / 7.1 GiB RAM / **swap 4 GiB**（swappiness=10）/ 根分区 69 GB ext4
- 契约：`doc/14-interface-contract.md` §4.2 / §4.3 / §4.4 / §9.7

---

## 0. 结论速览

| 项 | 结果 |
|---|---|
| ① libvpx(aarch64, VP9) 静态库 + 头文件 | ✅ 完成并双重核验（`nm -C` C++ 符号=0；verifier 复核 156/156 成员 AArch64） |
| ② `libwebrtc-java.jar`（org.webrtc.*） | ✅ 完成。t5 原为 453 类 / Java 25 字节码（AGP 不可用）→ t16/t17 原生重编为 v61（453 类）→ **t23 补齐 48 个 jni_zero `*Jni` 绑定类（现行 508 类）** → **详见 §8.1 与 §10** |
| ③ `libjingle_peerconnection_so.so`（aarch64, stripped） | ✅ 完成（12 946 912 B；t23 全程未变） |
| ④ `libwebrtc-arm64.aar`（归档三件） | ✅ 完成（t23 重打包 6 489 348 B） |
| 可选 `libwebrtc.a` 单体静态库 | ⛔ **未产出**（按契约 §4.2/§4.3 + captain 授权裁剪；详见 §7.2） |
| 编译耗时 | **约 49 分钟**（`ninja -j2`，4262 步） |
| 磁盘峰值 | 已用 ~35 G / 可用 32 G（53%）——**远低于 50 G 告警线** |
| OOM | 未发生（swap 峰值仅用 37 MB） |

---

## 1. 源码 revision 与「对照代码 == 编译代码」核验

| 项 | 值 |
|---|---|
| 编译树 commit（`webrtc-build/src`） | `5c25072bda9b8c8d9acab443acef5b330b1588b7`（作者重新 `git init` 包装为单 commit，message 注明基 rev） |
| **内容树哈希** | **`32ce3a01ab8862adc169f01b28ddc8268c279200`** |
| t3 submodule 指针 | `be0e900885631e028972f18ed682d6ddb13be637`（2026-09-12） |
| 核验结论 | **两者树哈希完全相同 ⇒ 内容逐字节一致**。「对照阅读的代码 == 实际编译的代码」天然成立，**无需**收尾时重新钉 submodule |

> 约束遵守：**未再移动/复用 t3 的 submodule 工作树**。构建一律在独立目录 `webrtc-build/src` 进行（早先误移已用 `git submodule update --init` 复原，t3 仓库现为干净状态）。

---

## 2. 网络根因留档（★ 这是对 t1 结论的**第二次修正**，请勿与前述结论视为矛盾）

**t1 初版结论**：googlesource git 约 0.32 MB/s，`gclient sync` 可能需 15–30 小时。

**t5 第一次修正（本报告 §2.1）**：作者实测 `webrtc/src` 30 s 仅 460 KB，判定超大仓不可用，改换路由。

**t5 第二次修正（最终结论，采用）**：第一次修正属**作者自身的测量误差**——该测量与作者自己发起的 4 路并发 clone 基准测试**在同一窗口**，带宽被自身占用，得到污染值。按 captain 要求的 60 s 窗口、**纯 `git clone --depth 1`（无任何 filter）**复测：

| 目标 | 实测 |
|---|---|
| `webrtc.googlesource.com/src` | **37 MB / < 60 s（≈5+ MB/s）成功完成** |
| `chromium.googlesource.com/webm/libvpx`（对照） | 6.3 MB 完成 |

**最终结论（三层，供后人辨别）**：
1. googlesource git **可用**；超大仓吞吐**波动大**，**小样本数字不可用于下结论**（0.32 MB/s 与 460 KB/30 s 均属此类）。
2. 慢的原因**不是** `--filter=blob:none`（第二次修正的测量未使用 filter）。
3. **`fetch webrtc_android` 仍不可用**——但原因不是限速，而是它要求把 **chromium/src 作为 master 全仓克隆（10–20 GB，与 Android arm64 构建无关）**。

**采用的技术方案（有效且必要）**：**独立 `.gclient` + `managed:False`**，复用 t3 已 checkout 的 `libwebrtc-src` 作为 `src`，从而**跳过 chromium/src master 全仓**，同时依赖仍全部走官方源 `gclient sync`。实测：**322 个依赖仓、src 达 20 GB**，约 3 分钟即拉下 5.1 GB。**未配代理、未改造镜像。**

### 2.1 另外两个真实踩坑（均非网络问题，留档后人）

1. **`checkout_android` 未设 → 55 个 Android CIPD 依赖全被跳过**。独立 `.gclient` 缺少 `fetch` 注入的 custom_vars，导致 `android.jar`、`third_party/turbine/cipd/turbine.jar`、clang aarch64 runtime 缺失，ninja 报 `missing and no known rule to make it`。修复：`.gclient` 加 `custom_vars: {checkout_android: True, checkout_android_prebuilts: True, checkout_android_sdk: True}` 后重新 sync。
2. **depot_tools 的 `gn` wrapper 不可用**（需 bootstrap 且找不到二进制）→ 改用 `src/buildtools/linux64/gn`（v2562）。
3. **官方 `build_aar.py` 本机走不通**：内部调 `third_party/siso/cipd/siso ninja`（siso 缺 `backend.config`）**且会用自有 GN 参数另建 `aar/<arch>` 目录、丢掉 `rtc_dlog_always_on`**。改为等价实现：用系统 ninja 编同样 target，再按官方步骤手工打 AAR（`classes.jar` + `jni/arm64-v8a/libjingle_peerconnection_so.so` + `AndroidManifest.xml`），**target 与产物路径与官方一致**。

---

## 3. GN 参数与红线核验（契约 §4.3）

```gn
target_os="android" target_cpu="arm64" is_debug=false is_component_build=false
rtc_include_tests=false rtc_build_examples=false rtc_build_tools=false
treat_warnings_as_errors=false is_clang=true use_sysroot=true
symbol_level=0 rtc_dlog_always_on=true
```

| 红线 | 核验 |
|---|---|
| `rtc_disable_logging` 不得为 true | ✅ `out/Release-arm64/args.gn` **不含该项** → 默认 **false** |
| `use_custom_libcxx` 不得显式设置 | ✅ 未设置（Android 默认 true）。**因果链见 §4** |
| `ninja -C out/Release-arm64 sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so` | ✅ 实际使用该 target 组合 |

**并行度**：`ninja -j2`（4 vCPU=2 物理核，可用内存约 5 GiB）；脚本对失败自动降 `-j1` 重试一次。**磁盘 88% 自动中止看门狗**常驻。

---

## 4. 日志可用性结论（契约 §9.7 / 用户要求 D6）

源码核验（`rtc_base/logging.h`）：

```c
#if !defined(NDEBUG) || defined(DLOG_ALWAYS_ON)
#define RTC_DLOG_IS_ON 1
#else
#define RTC_DLOG_IS_ON 0
#endif
```

**结论**：
1. `is_debug=false` 时定义 `NDEBUG` ⇒ **`RTC_DLOG` 默认被编译掉**；`RTC_LOG(LS_INFO/WARNING/ERROR)` **本就可用**（默认 `min_severity_ = LS_INFO`）。
2. 为在 release 下拿到 **LS_VERBOSE/GCC/NACK/ICE 等内部日志**，本次编译加入 **`rtc_dlog_always_on=true`** → 经 `//:common_inherited_config`（`rtc_common_configs` 全局生效）添加 **`-DDLOG_ALWAYS_ON`**，从而开启 `RTC_DLOG`。
3. **未使用 `is_debug=true`**（遵守用户红线；体积/性能代价远小于 debug 构建）。
4. 未打 libwebrtc 补丁（契约 U6 决定：Android 端经 `Loggable`/`setInjectableLogger` 捕获日志）。

**`use_custom_libcxx` 因果链（后人会反复问，此处固化）**：Android 目标默认 `use_custom_libcxx=true` ⇒ libwebrtc 的 `.a`/`.so` **静态链入 Chromium 自带 libc++**（std 类型带 `std::__Cr` 内联命名空间），而自有 `libwebrtcdemo_native.so` 用 NDK `c++_shared`（`std::__ndk1`）⇒ 同一进程两份 libc++、两套 std 类型，跨 `.so` 传 `std::string`/`rtc::scoped_refptr` 属 UB ⇒ **这正是自有 .so 禁止链接 `libwebrtc.a`、只保留 JNI 边界的根因**（契约 §4.2/A1）。

---

## 5. libvpx 交叉编译（契约 §4.4）✅ 已完成

- 工具链：**NDK 26.1.10909125** standalone（`CHOST=aarch64-linux-android`、`CC/CXX=…-clang(++)`、API 21），**未使用** webrtc 的 custom-libcxx 工具链。
- **接口修正（doc/08 已过期）**：现代 libvpx 移除了 `--sdk-path` 与 `armv8-android-gcc`，正确用法为 `--target=arm64-android-gcc` + NDK 环境变量。
- configure：`--enable-vp9 --enable-vp9-encoder --enable-vp9-decoder --disable-vp8-* --enable-static --disable-shared --disable-examples --disable-tools --disable-unit-tests --disable-runtime-cpu-detect --enable-pic`。

**§4.4 硬约束实测核验**：

| 检查 | 结果 |
|---|---|
| `nm -C` 统计 C++ 符号 | **0 个** ✅（纯 C，不含 C++ 标准库） |
| 未定义符号 | 仅 `getenv`、`strtol` 等 libc ✅ |
| 架构 | `ELF 64-bit LSB relocatable, ARM aarch64` ✅ |
| 体积 | `libvpx.a` = 1.9 MB |

**无需告警**（未用 custom-libcxx 工具链、未开 C++ 特性）。

### 5.1 ★ 文档实测修正（与 t6 的 `relay-ip` 同类，供后人直接采用）

`doc/08-libwebrtc-android-build.md` §9.3 给出的 libvpx 交叉编译命令**已过期，照抄必然失败**：

```bash
# ❌ doc/08 原文（会报 Unknown option "--sdk-path=..."）
./configure --target=arm64-android-gcc --sdk-path=$NDK_PATH ...
#   且示例中还写了 --target=armv8-android-gcc（该 target 已不存在）
```

实测结论与正确做法（已在源码 `build/make/configure.sh` 的 `*-android-*` 分支核验）：

| 项 | doc/08 原文 | 实测正确值 |
|---|---|---|
| `--sdk-path` | 使用 | **已移除**（`configure --help` 无此项，源码 grep 命中 0） |
| target 名 | `armv8-android-gcc` | **`arm64-android-gcc`**（`--help` 的 Supported targets 列表） |
| 工具链传递方式 | `--sdk-path=<NDK>` | **环境变量**：`CHOST=aarch64-linux-android`、`CC/CXX=<NDK>/…/bin/aarch64-linux-android21-clang(++)`、`AR=llvm-ar`、`AS=clang`、`LD=ld.lld`、`STRIP=llvm-strip` |

**可用命令（本报告实际执行）**：`./configure --target=arm64-android-gcc` + 上述环境变量，其余选项见本节上方。

---

## 6. 磁盘投影（captain 要求：报峰值，不只报当前值）

**实测分解（进行中）**：

| 项 | 当前 | 说明 |
|---|---|---|
| 根分区 | 已用 **35 G** / 可用 **32 G**（52%） | 69 G ext4 |
| `webrtc-build` | 21.1 G | `depot_tools` 1.1 G + `src` 20 G |
| `out/Release-arm64` | **230 MB**（3235/4262 步） | 见下投影 |
| `android-sdk` 2.6 G / `go` 0.68 G / JDK 0.26 G / `code` 0.28 G / swap 4 G | — | |

**峰值投影（基于实测速率，非猜测）**：
- 剩余步骤分布（`ninja -n` 统计）：**CXX 1642 + CC 727 + ACTION 320 + SOLINK/LINK 2**，其中已消耗大部分 ACTION/JAVA。
- 实测曲线：`out/` 1188 步时 189 MB → 2706 步时 208 MB → **3235 步时 230 MB**；**系统盘 5 分钟窗口仅 +21 MB（34.85→34.87 G）**，且 `gclient sync` 已结束、不再有外部增长。
- 剩余约 **1027 步 C++/C 编译**（对象文件典型 50–150 KB）≈ **+0.1–0.4 GB**；另加最终 `SOLINK libjingle_peerconnection_so.so`（release + `symbol_level=0`，典型 **5–15 MB**）与 `libwebrtc.jar`（数十 MB）。
- ⇒ **`out/` 峰值投影 ≈ 0.3–0.6 GB**（**远低于**我早先"10–16 GB"的悲观估计——那基于完整 `webrtc` 静态库 target，已按契约裁剪）。
- 另注：官方 `sdk/android:libwebrtc` target **本身不产出 monolithic `libwebrtc.a`**，故裁掉 `webrtc` target 无"丢掉现成大文件"的损失。

**总占用峰值投影 ≈ 36–37 G 已用 / 32–33 G 可用** ⇒ **不会触及 captain 的 50 G 告警线，也不会到 55 G 被看门狗中止**（余量约 13–14 G）。仍在脚本 88% 看门狗保护下。

**✅ 编译结束后的实测结果（投影已兑现，无偏差）**：

| 项 | 投影值 | **实测最终值** |
|---|---|---|
| `out/Release-arm64` | 0.3–0.6 G | **319 MB** ✅ 落在区间内 |
| 根分区已用 / 可用 | 36–37 G / 32–33 G | **35 G / 32 G** ✅ |
| OOM | 未预期 | **未发生**；swap 峰值 **37 MB**（4 GB 几乎未用） |

> **更正说明**：我此前报给 captain 的"out/ 10–16 GB"是**基于包含可选 `webrtc` 静态库 target 的估算**，在按契约 §4.2 裁掉该 target 后**必须下修**为本节的实测投影。这是**第三次口径更正**，特此显式记录。

**可选清理项（B 退路，未启用）**：`src/resources` 850 MB（测试音频 fixtures）、`third_party/android_system_sdk` 52 MB、`src/.git` 37 MB ≈ **0.9 GB**。因磁盘始终充裕（32 G 可用），**本次未执行任何清理**。

---

## 7. 最终产物清单与核验（✅ 编译已完成）

### 7.1 硬交付三件（+ AAR 归档）—— 全部达成

| # | 产物（绝对路径，容器内 `/data/dsh/home/workspace/...`） | 大小 | 核验 |
|---|---|---|---|
| ① | `third_party/libvpx/lib/libvpx.a` | 1 929 142 B | `file` → current ar archive；`llvm-ar x` 首个成员 → **ELF 64-bit ARM aarch64**；`nm -C` C++ 符号 = 0；verifier 独立复核 **156/156 成员 AArch64** |
| ① | `third_party/libvpx/include/vpx/{vpx_codec,vpx_encoder,vpx_image,vp8cx}.h` | — | 四个必需头全部存在（另有 vpx_decoder/vpx_frame_buffer/vpx_integer/vpx_tpl/vp8/vp8dx/vpx_ext_ratectrl） |
| ② | `third_party/libwebrtc/java/libwebrtc-java.jar` | 1 187 970 B | 内含 **508 个 `.class`**（t23 后；t5 原始为 453 个），其中含 **48 个 `*Jni` jni_zero 绑定类**（t23 前 = **0**）—— 见 **§10** |
| ③ | `third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so` | 12 946 912 B | `file` → **ELF 64-bit LSB shared object, ARM aarch64, stripped** |
| — | `third_party/libwebrtc/java/libwebrtc-arm64.aar` | 6 489 348 B | Zip；内含 `classes.jar` + `jni/arm64-v8a/*.so` + `AndroidManifest.xml`（t23 后重打包，见 §10.5） |

**契约 §4.3 / D1 关键类存在性核验**（`unzip -l` 实测，全部 ✅）：

```
org/webrtc/VideoEncoder.class                    org/webrtc/VideoEncoderFactory.class
org/webrtc/VideoCodecInfo.class                   org/webrtc/EncodedImage.class
org/webrtc/SurfaceViewRenderer.class              org/webrtc/SurfaceTextureHelper.class
org/webrtc/Camera2Capturer.class                  org/webrtc/DefaultVideoEncoderFactory.class
org/webrtc/SoftwareVideoEncoderFactory.class      org/webrtc/HardwareVideoEncoderFactory.class
org/webrtc/PeerConnectionFactory.class            org/webrtc/audio/JavaAudioDeviceModule.class   ← 注意在 audio/ 子包
```
> `JavaAudioDeviceModule` 位于 **`org/webrtc/audio/`** 子包（D1 采集/音频所需）。

**一致性交叉核验（防止"取了旧/错文件"）**：
> ⚠️ **本节数值已按 t16/t17 交付更新，并在 t23 后再次更新**（t5 当时为 `ad54a0a2…`，属历史值，见 §8.1 的哈希对账表与 §10）。
- `third_party/.../libwebrtc-java.jar` ≡ AAR 内 `classes.jar`：二者 **sha256 均为 `7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`** ✅（508 class，全 ≤ major 61；t23 现行值）
  > ⚠️ **`out/.../libwebrtc.jar` 目前不等于交付 jar**（`out/` 内仍是 t17 旧值 `d98939bb…`，453 类）：t23 是在 `out/` 之外对 jar 做**受控合并**（把 jni_zero 生成的 `*Jni` 类从 `generated_*_jni_java.javac.jar` 并入），**未改动 `out/`**，详见 §10.7。
- `out/.../libjingle_peerconnection_so.so`（stripped）≡ AAR 内 `.so` ≡ `third_party/.../jni/arm64-v8a/*.so`：**sha256 全 64 位均为 `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`** ✅（t23 全程未改）

**一次自查发现并修正的缺陷（留档）**：extract 初次使用了
`find "$OUT" -maxdepth 2 -name 'libjingle_peerconnection_so.so'`，
它**误取了 `out/lib.unstripped/` 的未 strip 版本**（18 224 488 B，带 7 个 debug section），
而非 Android 打包惯例所用的 `out/` 顶层 stripped 版本（12 946 912 B）。
已修正为优先取 `out/` 顶层文件，并重新打 AAR + 重新 extract。
两者**动态导出符号 md5 完全相同**（`1b436f4840e091de`）⇒ **功能等价，差异仅在调试段**，但为符合打包惯例与体积预期，最终交付采用 **stripped 版**。

### 7.2 可选产物（契约 §4.2/§4.3 明示为可选）—— ⛔ 未按 `webrtc` target 产出

- **`libwebrtc.a`（单体静态库）：未产出**。原因：**本次不执行 `ninja webrtc`**（`BUILD_WEBRTC_A=0`，经 captain 明确授权裁剪）。
  **按磁盘约束与契约裁剪，不判失败**（契约 §4.2/§4.3 明示该项为可选；自有 native 库本就禁止链接 `libwebrtc.a`）。若日后需要，可增量补跑，不影响已交付硬产物。
  理由：契约 §4.2 **禁止**自有 native 库链接 `libwebrtc.a`（A1 路线只保留 JNI 边界）。
- 但需说明：编译 `libjingle_peerconnection_so.so` 期间，`out/obj/**` 曾产生 **570 个 `.a`（2.4 MB）**，
  它们是**各子 target 的 thin archive**（文件头 `!<thin>`），**内容只是对 `out/` 下 `.o` 的路径引用**，
  **脱离 `out/` 树即不可链接**，**不是"可用的 libwebrtc.a"**。
  ⇒ **已从 `third_party/libwebrtc/lib/` 删除**，以免下游（t7/t10/verifier）误以为拿到了可用静态库。
    如需真正可链接的单体静态库，须另跑 `ninja -C out/Release-arm64 webrtc`（本次**按授权未跑**）。
- **`third_party/libwebrtc/include/`（76 MB，含 `api/`、`sdk/android/`、`rtc_base/`、`modules/`、`pc/`、`third_party/absl` 等）：已提取**（契约标为可选）。占用很小（76 MB，磁盘充裕），保留供对照阅读与 §5.7 升级备料。若需严格按"裁掉"执行可随时删除，不影响任何硬交付。

### 7.3 编译耗时与资源

| 项 | 值 |
|---|---|
| `gn gen` | 2026-09-13 **16:05:57**（8018 targets，约 3.5 s） |
| `jar` + `so` 产出 | 2026-09-13 **16:55:06** |
| **主编译耗时** | **约 49 分钟**（16:05:57 → 16:55:06），`ninja -j2`，共 **4262 步**（`ninja -n` 计数）；`.ninja_log` 行数 5509（含嵌套/重复记录） |
| AAR 组装 + extract | 16:55 → 17:03（含一次 stripped/unstripped 修正后的重做） |
| `out/Release-arm64` 最终占用 | **319 MB** |
| 峰值磁盘 | 根分区 **已用 ~35 G / 可用 32 G**（53%）；`webrtc-build` 21 G（src 20 G + depot_tools 1.1 G） |
| 内存/swap | 编译期间 Mem available ~5.0–5.4 G；**swap 使用峰值仅 37 MB**（4 GB swapfile 未受压） |
| OOM | **未发生**（`-j2` 下峰值安全；`-j1` 兜底逻辑未被触发） |
| 产物属主 | `third_party/libwebrtc`、`third_party/libvpx` 均为 **1000:1000**（容器内 `node` 可读写） |
| `.gitignore` | `third_party/libwebrtc/`、`third_party/libvpx/` 已被忽略（`git check-ignore` 命中 `.gitignore:12-13`） |

### 7.4 与契约 §4.3 的逐项对照

| 契约要求 | 状态 |
|---|---|
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | ✅ 6 489 348 B（`4878509a…`；t23 重打包，见 §10） |
| `third_party/libwebrtc/java/libwebrtc-java.jar`（= AAR 内 classes.jar，提供 org.webrtc.*） | ✅ 1 187 970 B，**508 类**（含 **48 个 `*Jni`**；t23 前为 453 类且 `*Jni` = 0） |
| `third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so` | ✅ 12 946 912 B，aarch64 stripped |
| `third_party/libvpx/lib/libvpx.a` + `include/vpx/*.h` | ✅ aarch64，C++ 符号 0 |
| `third_party/libwebrtc/{lib,include}` | ⚠️ `include/` ✅ 已提取；`lib/*.a` **仅为 thin archive 引用、非可用单体库**（见 §7.2，按授权未跑 `webrtc` target） |

---

## 8. 失败与返工记录（含作者自身操作失误，如实留档）

| # | 现象 | 根因 | 处置 | 代价 |
|---|---|---|---|---|
| F1 | `gn gen` 报 `python3_bin_reldir.txt not found` / gn wrapper 报 `Unable to find gn in your $PATH` | depot_tools 的 `gn` 包装脚本未 bootstrap 且找不到真实二进制 | `ensure_bootstrap` + **改用 `src/buildtools/linux64/gn`（v2562）** 直调 | ~10 min |
| F2 | ninja 反复报 `../../third_party/android_sdk/public/platforms/android-37.0/android.jar ... missing and no known rule`、`turbine.jar missing`、`libclang_rt.builtins-aarch64-android.a missing` | **独立 `.gclient` 缺少 `fetch` 注入的 custom_vars，`checkout_android` 未定义 ⇒ DEPS 中 55 个 Android CIPD 依赖全部被跳过** | `.gclient` 补 `custom_vars: {checkout_android: True, checkout_android_prebuilts: True, checkout_android_sdk: True}` 后重新 `gclient sync` | ~40 min |
| F3 | 官方 `build_aar.py` 失败：`cannot load ./backend_config/backend.star` | 其内部走 `third_party/siso/cipd/siso ninja`，本机 siso 缺配置 | 改为系统 ninja 编同 target + 手工打 AAR（等价性见 §9） | ~15 min |
| F4 | **（作者失误 ①）** `gclient runhooks` 中途被中断，日志记 `returned non-zero exit status -15` | 作者为跳过 `src/resources` 测试音频下载，执行了 **`pkill -f download_from_google_storage…`**；该**宽泛模式匹配到了作者自己的 SSH 命令行**，导致自杀并连带打断 runhooks | 重新 sync 补全被跳过的 CIPD 依赖 | **~40 min**（与 F2 同源） |
| F5 | **（作者失误 ②）** 停"外层包装链"时再次自杀 | 使用 `ps -eo cmd \| grep <模式> \| awk '{print $1}' \| kill`；**第二次执行时匹配串仍出现在作者自己的 SSH 命令行里**，故仍自杀 | 改用**由命令自己 pgrep 出的 PID 变量**（不把模式写进被匹配的命令行）；随后确认 ninja 存活 | **0 min（无返工）**，但**风险极高**——当时 ninja 正运行在 10–20 h 关键路径上 |

**教训（固化）——`pkill`/`pgrep -f` 的固有陷阱与硬性做法**：

> **陷阱本质**：`ssh root@HOST 'pkill -f X'` 时，**执行者自己的 ssh/远端 shell 命令行本身就在进程表里，且包含字符串 `X`**，因此 `-f`（匹配整条命令行）会命中自己。两次失误同源（第 2 次虽改为"精确 PID"，但**待匹配的模式串仍出现在作者那条 ssh 命令行内**，`grep` 依旧命中它）。

**硬性做法（此后一律照此执行）**：
1. **启动时把 PID 落盘**：`setsid nohup <cmd>… & echo $! > logs/<name>.pid`
2. **此后只用** `kill "$(cat logs/<name>.pid)"`；**永不再用** `pkill` / `pgrep -f | kill` 组合。
3. **任何 kill 动作后立刻自证存活**（两条都要过）：`kill -0 "$(cat logs/<name>.pid)"` **且** 观察 `.ninja_log` 行数是否仍在增长。
4. 若确属必须用模式匹配的场景，**必须排除自身**：`pgrep -f X | grep -v $$ | grep -v ssh`，或改用**锚定到完整绝对路径**的正则（`^/opt/…/ninja -C out/…$`）。

**其它教训**：
- 长任务的**前置成功不应被当作理所当然**：`runhooks` 被中断后表面无差异，但已造成 CIPD 缺口（F2），直到 ninja 报缺文件才暴露。**每个阶段结束都应显式核验其关键产物是否存在**，而非只看退出码。
- **关键路径进程绝不可用模糊模式去 kill**：本任务 ninja 是 10–20 h 的长构建，若被误杀，除数小时白费外，还会因半成品状态被误判为"编译失败"。这也是本报告 §7.1 记录"extract 误取 unstripped so 后重做"之外，作者**第二次**自查出并修正的操作缺陷。

---

## 8.1 ⚠️ 交付后被消费者发现并由 t10 修复的缺陷：jar 字节码版本 = Java 25（v69）

**这是本任务唯一一个"我的产物在交付后被下游发现不合格"的问题，必须完整留档。**

### 现象与根因
t10 第二次构建在 `:app:desugarDebugFileDependencies` 失败：
```
libwebrtc-java.jar: D8: java.lang.IllegalArgumentException: Unsupported class file major version 69
```
- 实测 jar 内 **453 个 class 全部为 major version 69 = Java 25 字节码**。
- **根因**：本次构建用的是 **webrtc 自带的 JDK**（`src/third_party/jdk/current/bin/javac` = **javac 25.0.4.1**），**而非系统 JDK17**；且 GN 未传 `--release`，javac 25 遂以**默认 target（25）**产出字节码。
- **AGP 8.5.2 内置 R8/D8 8.2.2 不支持 major 69** ⇒ 契约 §4.3 指定的那个 jar 路径**在 AGP 8.5 下不可直接消费**。
- **我的 GN args 缺项**：`args.gn` 中无任何 Java 版本约束项（已核实），这是我的构建配置遗漏。

> 附带更正一条我此前的表述：我曾说"t5 用 webrtc 自带工具链、不依赖系统工具链"——**对 Java 部分这句话正是问题所在**：webrtc 自带 `third_party/jdk/current` 是 **25**，而另有 `third_party/jdk11/current` = **11.0.15**。

### t10 的处置（env-installer 实施，最小化/可回滚）
新增 `scripts/fix_jar_class_version.sh`：把 jar 内每个 class **头部 2 字节版本号 69→55（Java 11）**。

| 项 | 值 |
|---|---|
| 原始 jar（t5 产物，v69） | `libwebrtc-java.jar.orig-jdk25`，1 051 957 B，sha256 `ad54a0a209ecfd6e5c407d9ba04f61b3…` |
| t10 临时补丁版（v55） | 555 728 B，sha256 `138cf12dfa7c2a6b30a74c95c0f19536…`（`fix_jar_class_version.sh` 只改每 class 头 2 字节；**系 re-zip 产物，字节已不可得**） |
| t16/t17 原生重编（v61 基线） | 1 048 264 B，sha256 `d98939bbf0c0cd071baff19004a4dc2f602997b70e33e0fc3669c5fccb5f6543`（**t23 后为内容基线**） |
| **现行（t23：v61 + `*Jni` 绑定类）** | **1 187 970 B，sha256 `7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`（508 类，48 `*Jni`）—— 见 §10** |
| 冒烟验证 | build-tools/34.0.0 `d8`：v69 jar 复现报错；v55 与 v61 均成功产出 `classes.dex` |

### ★ 我的独立复核（已按 captain 更正两次修订）

**① 成立的部分（限定结论）**：对 453 个 class 逐一「屏蔽字节 6-7 后 `cmp`」（补丁版 vs 原始 v69）：
```
总 class=453   除版本戳外内容不同数 = 0
```
⇒ **t10 的改写仅触及每个 class 的 2 字节版本号，字节码本体零改动，原始字节完整可回滚**（`orig-jdk25` 与当时 AAR 内 `classes.jar` 均为 `ad54a0a2…`）。
> **该结论的边界（必须显式声明）**：它**仅**说明"那次改写只动了 2 字节/class"，**不构成**对"源码只使用了 Java ≤11 语言级构造"的证明 —— 因为比较对象是"补丁版"与其"自己的原始版"，二者按构造只差版本号，属**循环证据**。

**② 已删除的无效推论**：我曾据此断言「⇒ 源码只用 Java ≤11 构造 ⇒ 改回 v55 是修回正确形态」。该推论**无效**（循环论证），**已删除**，不再出现于本报告。

**③ t16 证据的精确表述（captain 已纠正）**：t16 产出的**原生 v61 即现行交付**（见 ⑤）与 t10 的**补丁 v55** 之间存在 **453 个 class 全部不同** —— 但**二者目标版本本就不同（61 vs 55）**，因此：
- 该对比**只能**证明「**新 jar 是 javac 重新编译产物、而非改字节**」；
- **不能**用来判定补丁方案是"等价"还是"有风险"（**不得**写成"A 有风险"，也**不得**写成"A 安全"）。

**④ 补充对比（供定性参考；见 `reports/15-java-jar-rebuild.md` §8）**：对**两个均为 major 55、但来源不同**的 jar 做逐 class 比对（**均取盘上现存文件，故每个数字可复现**）：

| 角色 | 文件路径 | sha256 | 大小 | major |
|---|---|---|---|---|
| **A′**（version-byte 改写，**本批重建的可复现件**） | `<WS>/tmp/jarver/A-inplace.jar` | `412153f43d0bd3ae40020052a12b37b7b66889c916658c8134b2b47d3aafba89` | 1 051 957 B | **55** |
| **B**（原生 `--release 11` 重编版，**已归档**） | `third_party/libwebrtc/java/libwebrtc-java.jar.v55-java11` | `ee792522c35cb8ca5c53052a1fecaa4bca6a96870f94aa4f563d0198ea6d8245` | 1 048 264 B | **55** |

**A′ 构造方式（确定性）**：以 `libwebrtc-java.jar.orig-jdk25`（`ad54a0a2…`，1 051 957 B，453 entry **全部 STORED**）为输入，**原地**改写每 class 头 2 字节 `69→55` 并同步修正 CRC32，**不 re-zip** ⇒ 大小保持 **1 051 957 B** 不变、`zip testzip = None`。

实测（可复现）：屏蔽每 class 版本字节后 **397 相同 / 56 不同**；含 `java/util/Objects.requireNonNull` 常量的 class 数 **A′ = 44 / B = 4**（**方向**：44 属 javac 25 原字节一侧）；另有 7 个 class 的方法/字段清单不同。

> **结论（中性，不得越界）**：上述差异属**编译器 / 目标版本差异**（javac 25 与 `--release 11` 在嵌套类构造器插入空值检查的数量不同）——**该对比不构成 A/B 优劣定案**；**既不得**表述为任一方案"有风险"，**也不得**表述为任一方案"安全"或"更正确 / 更必要"。
> **provenance 说明（必须写明）**：当年**实际部署进 `third_party`** 的 patch 版是 re-zip 产物 `138cf12d…`（555 728 B）——**其字节已不可得**；`<WS>/tmp/jarver/patched.jar`（`c3e4bdd4…`，539 644 B）是**另一次实验产物**，与部署件**并非同一文件**。故旧的 §8 结论标注为 **provenance 受限，已由 A′ 复现替代**。
> `--release` ↔ major 对应（全文严格遵守、不混写）：**`--release 11` → 55**、**`--release 17` → 61**、`--release 25` → 69。

> **终局定案（captain，唯一口径）**：采用 **v61（Java 17，`--release 17`）**，**并已冻结**（任何人不得再改 `third_party/libwebrtc/java/**` 与 `libvpx.a`）。依据：契约 §3 冻结 JDK 17、`app/build.gradle.kts` `VERSION_17`/`jvmTarget=17`、D8 8.2.2 支持 61。
> 过程留档：captain 曾先后发出"批准 v61"与"以 v55 为准"两条互斥指令（**captain 已自认责任**），我按序各执行一轮；最终以 **v61** 收官。**v55 那一代产物作为历史归档保留，不再是现行交付**（见 §8.1 哈希链与备查路径）。

**⑤ 现行交付哈希（对账更新）**：`ad54a0a2…`（v69 原始）/ `138cf12d…`（t10 临时补丁，**re-zip 产物、字节已不可得**）**均降级为历史值**；`d98939bb…`（t16/t17 原生 v61，453 类）自 **t23 起降级为「内容基线」**；**现行值 = `7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`**（1 187 970 B，508 class 全 ≤ major 61），且与 AAR 内 `classes.jar` **同哈希**（契约 §4.3 第 348 行"= AAR 内 classes.jar"因此成立）。

### 原始字节的留存（未丢失；截至 t10 阶段的状态）
- 备份：`libwebrtc-java.jar.orig-jdk25`（历史值 `ad54a0a2…`，**至今保留**）
- 当时 AAR 内 `classes.jar` 亦为 `ad54a0a2…`（该 AAR 现备份为 `libwebrtc-arm64.aar.orig`，内含 major 69 的 classes.jar）
- 生产端 `webrtc-build/src/out/Release-arm64/lib.java/sdk/android/libwebrtc.jar` 当时亦为原值
> **状态（t16/t17 后为上述基线；t23 后为现行新值）**：jar 与 AAR 内 `classes.jar` 已更新为 **`7dbe8400…`（v61 + 48 个 `*Jni`，508 类，见 §10）**；AAR 内 `.so` 始终为 `757cef81…`（**逐字节未变**）。原始 v69 字节的完整副本仍存于 `libwebrtc-java.jar.orig-jdk25` 与 `libwebrtc-arm64.aar.orig`。

### 已执行的正解（t16/t17，已完成）
用 `--release 17` 重编 Java targets（`ninja -C out/Release-arm64 -j2 sdk/android:libwebrtc`），产出**原生 v61** jar，**只重编 Java 目标、未触碰 native `.so`**（`.so` sha256 前后均为 `757cef81…`）。
**机制澄清**：决定目标版本的是 `compile_java.py`/`turbine.py` 里**硬编码的 `--release` 取值**，而**不是"用哪个 JDK"** —— 实测 `javac25 --release N` 与 `javac11 --release N` 产出**同一 major**。故保留 `jdk/current`(25) 不动、只改 `--release`，改动面最小。
**可复现性**：t17 固化补丁后重跑一次，产出**逐字节相同的 v61 基线 jar**（sha256 仍为 `d98939bb…`）✅；t23 后现行 jar = 该基线 + `*Jni` 合并（见 §10.6）。

### 遗留缺口与修复（t17 已落地）
**缺口**：上述修补原先只落在**仓外未跟踪**的构建树 `<WS>/webrtc-build/src/...`，仓内脚本并未应用它 ⇒ **重跑 t5 会再次产出 v69 jar 并再次打断构建**。
**修复（t17）**：
- 入库可审查补丁：`scripts/patches/libwebrtc-java-release17.patch`（`--release 25` → `17`，覆盖 `compile_java.py` 与 `turbine.py`）；
- `scripts/t5-libwebrtc-libvpx-build.sh` 新增 `ensure_java_release_patch()`，在 **gn 与 ninja 两个阶段**均先执行，守卫规则：
  | 实测状态 | 行为 |
  |---|---|
  | 两文件均 `17` | **跳过**（幂等） |
  | 两文件均 `25` | 整包应用入库补丁 |
  | 混合（一个 17、一个 25） | 按文件就地修补（幂等） |
  | **任一为既非 25 也非 17** | **报错退出**（防上游改动致修补静默失效） |
- **目标值由 captain 终局裁定为 `--release 17`（Java 17 / major 61）并冻结**：依据契约 §3 冻结 JDK 17、app 的 `VERSION_17`/`jvmTarget=17`、D8 8.2.2 支持 ≤61。脚本中以 `JAVA_RELEASE_TARGET=17` 参数化，补丁文件同名对应。
- 每次都会把**两文件 `--release` 实测值**打进日志；修补后做「补丁逆应用 dry-run」对账。
- 构建树 `webrtc-build/t5-build.sh` 同源部署，并指向仓内补丁路径。

### 给 verifier 的提示（哈希链对账，务必按此核对）
**jar 的 sha256 经四代演进**，**只有最后一代是现行交付**：

| 代 | jar 版本 | sha256 | 状态 |
|---|---|---|---|
| 原始 | v69（javac 25 默认 `--release 25`） | `ad54a0a2…` | t5 产出；备查文件 `libwebrtc-java.jar.orig-jdk25`；**不可被 AGP 8.5/D8 消费** |
| 临时 | v55（**只改版本字节**，`fix_jar_class_version.sh`） | `138cf12d…` | env-installer 权宜手段；**已退役**；**re-zip 产物、字节已不可得** |
| 中途原生 | v55（`--release 11`） | `ee792522…` | **已归档**于 `libwebrtc-java.jar.v55-java11`（字节仍在磁盘）；captain 裁定作废，非最终态 |
| 原生 v61 基线 | **v61（`--release 17`）** | `d98939bbf0c0cd071baff19004a4dc2f602997b70e33e0fc3669c5fccb5f6543` | t16/t17 交付（1 048 264 B，453 class 全 major 61）；**t23 后降级为「内容基线/历史值」**，仍作为「原始 453 类守恒」的比对基准 |
| **现行（t23）** | **v61 + jni_zero `*Jni` 绑定类** | **`7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`** | **✅ 最终交付**（1 187 970 B，**508 class**，48 `*Jni` + 48 `*Natives`，全 ≤ major 61）；AAR 内 `classes.jar` 同步同哈希 —— 详见 **§10** |

**备查文件的存放位置（登记，均已实测存在于磁盘）**：`third_party/libwebrtc/java/` 下的
- `libwebrtc-java.jar.orig-jdk25`（**v69 原始**，`ad54a0a2…`）
- `libwebrtc-java.jar.v55-java11`（**中途 v55，已归档**，`ee792522…`，1 048 264 B，453 class 全 major 55）
- `libwebrtc-java.jar.v61-java17`（**t23 前的内容基线副本**，`d98939bb…`，453 类，仍用于守恒比对）
- `libwebrtc-arm64.aar.pre-t23`（t23 前的 AAR，`456e3f2f…`）、`../libwebrtc-arm64.aar.orig`（t5 原始 AAR，内含 major 69 的 classes.jar）、`../libwebrtc-arm64.aar.prev-v61`
> 注意：`third_party/libwebrtc/` 被 `.gitignore` **整体忽略** ⇒ 上述备查文件**不入库**，仅本地备查；此处登记路径以便复核。
> **关于 v55 一代的字节是否仍在磁盘**：✅ **在**。归档文件 `libwebrtc-java.jar.v55-java11` 实测存在且内容正确（major 55、sha256 `ee792522…`）；其字节亦曾出现于 `libwebrtc-arm64.aar.prev-v61` 之前的一版 AAR 中。故哈希表中**没有任何一行落空**。

**AAR 内 `classes.jar` 与现行 jar 同哈希（t23 后 = `7dbe8400…`）**，故契约 §4.3 第 348 行「= AAR 内 classes.jar」成立，**无需修改契约**。

**可复现性实测**：以 `--release 17` 重跑 `ninja -C out/Release-arm64 -j2 sdk/android:libwebrtc`（168 步 / 约 6 分钟）→ 产出**逐字节相同**的 v61 基线 jar（`d98939bb…`）✅；`.so` 全程未变（`757cef81…`）。
> t23 的现行 jar 在 v61 基线上再做**确定性合并**（§10.6 给出可复现命令）；t23 合并过程不涉及 CXX/CC/SOLINK 步骤。

---

## 9. AAR 产出方式：为何偏离官方脚本 + 等价性论证（verifier 按 §4.3 核对用）

### 9.1 官方脚本在本机不可用（两个独立原因）

`tools_webrtc/android/build_aar.py` 的行为（已读源码核对）：

| 行 | 内容 | 后果 |
|---|---|---|
| `_RunNinja()` L120-129 | `cmd = [SRC/third_party/siso/cipd/siso, 'ninja', '-C', output_directory]` | 本机 **siso 缺 `backend_config/backend.star`** ⇒ `Error: cannot load ./backend_config/backend.star` |
| `Build()` L204 | `_RunGN(...)` 用 **`build_aar.py` 自带的 GN 参数**在 `<build-dir>/<arch>` **另建目录** | 该目录**不含我们授权的 `rtc_dlog_always_on=true`** ⇒ 会丢掉 D6 要求的 `-DDLOG_ALWAYS_ON` |

⇒ 若强行用官方脚本，要么失败，要么产出**丢失日志能力**的 AAR，与已授权并登记为 C29/R10 的 GN 参数不一致。

### 9.2 采用的等价路径

```bash
# ① 用系统 ninja 编**完全相同的官方 target**（契约 §4.3 指定的两个）
ninja -C out/Release-arm64 sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so
# ② 取官方脚本所引用的同一批输出文件（TARGETS/JAR_FILE/NEEDED_SO_FILES 与之逐一对应）
#    JAR_FILE      = out/Release-arm64/lib.java/sdk/android/libwebrtc.jar
#    NEEDED_SO     = out/Release-arm64/libjingle_peerconnection_so.so
# ③ 按 AAR 规范手工打包
zip -qr libwebrtc-arm64.aar classes.jar jni/arm64-v8a/libjingle_peerconnection_so.so AndroidManifest.xml
```

**等价性论证（逐项对齐 `build_aar.py` 的常量）**：
| 官方脚本常量 | 官方值 | 本路径值 | 一致 |
|---|---|---|---|
| `TARGETS` | `['sdk/android:libwebrtc','sdk/android:libjingle_peerconnection_so']` | 同 | ✅ |
| `JAR_FILE` | `lib.java/sdk/android/libwebrtc.jar` | 同 | ✅ |
| `NEEDED_SO_FILES` | `['libjingle_peerconnection_so.so']` | 同 | ✅ |
| AAR 内 jar 位置 | `classes.jar` | 同 | ✅ |
| AAR 内 so 位置 | `jni/arm64-v8a/` | 同 | ✅ |

### 9.3 AAR 内部结构清单（`unzip -l` 原始输出，实测）

```
Archive:  /opt/dsh-workspaces/code/webrtc-demo/third_party/libwebrtc/java/libwebrtc-arm64.aar
  Length      Date    Time    Name
---------  ---------- -----   ----
  1051957  2026-09-13 17:02   classes.jar
        0  2026-09-13 17:02   jni/
        0  2026-09-13 17:02   jni/arm64-v8a/
 12946912  2026-09-13 17:02   jni/arm64-v8a/libjingle_peerconnection_so.so
      629  2026-09-13 17:02   AndroidManifest.xml
---------                     -------
 13999498                     5 files
```

**AAR 内部结构清单（t23 后重打包，`unzip -l` 实测）**：

```
Archive:  /opt/dsh-workspaces/code/webrtc-demo/third_party/libwebrtc/java/libwebrtc-arm64.aar
  Length      Date    Time    Name
---------  ---------- -----   ----
  1187970  ...             classes.jar            ← = 现行 jar，sha256 7dbe8400…
        0  ...             jni/
        0  ...             jni/arm64-v8a/
 12946912  ...             jni/arm64-v8a/libjingle_peerconnection_so.so   ← 757cef81…（逐字节未变）
      629  ...             AndroidManifest.xml
```

**`classes.jar` 内 `org.webrtc.*` 抽样**（t23 后 508 个 class；关键类见 §7.1）：
`org/webrtc/VideoEncoder.class`、`VideoEncoderFactory.class`、`VideoCodecInfo.class`、`EncodedImage.class`、
`SurfaceViewRenderer.class`、`SurfaceTextureHelper.class`、`Camera2Capturer.class`、`audio/JavaAudioDeviceModule.class`。

**一致性（t23 现行交付值）**：AAR 内 `classes.jar` 与交付的 `libwebrtc-java.jar` **sha256 全 64 位相同**（**`7dbe840049e239fb…`**，508 class，全 ≤ major 61）；
AAR 内 `.so` 与交付的 `jni/arm64-v8a/*.so` 相同（**`757cef8128bf9151`**，逐字节未变）。

---

## 10. t23：补齐 jni_zero `*Jni` 绑定类（现行交付更新）

### 10.1 缺陷（t23 前）

t5/t16/t17 交付的 jar 内 **`*Jni.class` 数量 = 0**，而构建树里 jni_zero 生成的 `*Jni.java` 源共 **48 个**。
根因（已定位，非猜测）：
- `libwebrtc.jar` 由 `zip.py --depfile gen/sdk/android/libwebrtc.d --output lib.java/sdk/android/libwebrtc.jar --no-compress --input-zips=@FileArg(gen/sdk/android/libwebrtc.build_config.json:dist_classpath)` 生成；
- 其 `dist_classpath` 为 `direct_deps_only=true` 的**直接依赖**集合，**不包含** `generated_*_jni_java` 这些独立 target；
- 48 个 `*Jni.java` 落在各 target 的 `input_srcjars`，被编成 **18 个 `generated_*_jni_java.javac.jar`**（其中 4 个为空 jar）；
- 另有 3 个类（`LoggingJni`、`CommonApisJni`、`JniZeroJni`）**从未被编译**，整个 `out/` 内均无对应 `.class`。

后果：`org.webrtc.*` 的公开 Java API（`PeerConnectionFactory`、`AudioTrack`…）在运行时通过 `*Jni` 绑定类下调 native；jar 缺这些类 ⇒ **`NoClassDefFoundError`**，D1 采集/渲染不可避免。

### 10.2 修复方案（受控合并，不重编 native）

新增 `scripts/build_java_sdk_with_jni.sh`（幂等，7 步 + 校验），**只对 jar 做类合并**，不触碰任何 CXX/CC/SOLINK 目标：

| 步 | 动作 |
|---|---|
| 0 | 前置断言：构建树 `compile_java.py` 与 `turbine.py` 的 `--release` **实测值必须为 `17`**（否则 FATAL 拒绝合并） |
| 1 | 从 t17 基线 jar 提取原始 **453 个 class** |
| 2 | 从 14 个非空 `generated_*_jni_java.javac.jar` 取出 **45 个 `*Jni.class`**，以 `unzip -oq -n`（**永不覆盖**）并入 |
| 3 | 单独编译 3 个缺失类：`LoggingJni`（用 `obj/rtc_base/base_java_jni_java.compliment.jar`）、`CommonApisJni`/`JniZeroJni`（用 `obj/third_party/jni_zero/generate_jni_java.compliment.jar`） |
| 4b | 再从所有 `*.compliment.jar` 以 `-n` 合并其余缺失 `org/*` 类（**排除** `org/jni_zero/GEN_JNI.class`） |
| 5 | 合成**并集 `GEN_JNI`**（194 个 native 方法），由 python3 生成器补全 `argN` 形参名 |
| 6 | 打包：`zip -q -X -0`（**STORED**，与官方 `--no-compress` 一致） |
| 6b | 校验原始 **453 个 class 逐字节未变**；7：跑链路完整性检查器 |

**关键设计约束（踩坑后固化）**：
1. **`*Jni` 类自身不声明 native**（jni_zero 设计）：`*Jni.get()` 返回 Natives 实现，具体 native 声明都在 `GEN_JNI` 里。故不能靠「`*Jni` 里有 native」来判真伪。
2. **每个 `generated_*_jni_java` 的 `.compliment.jar` 里都只有「部分」`GEN_JNI`**，构建树中**不存在**任何并集 `GEN_JNI` ⇒ 必须由脚本合成并集，否则运行期 `UnsatisfiedLinkError`（方法缺失）。
3. **Java 语法要求 native 方法声明必须带形参名** ⇒ 生成并集 `GEN_JNI` 时必须补 `argN`，否则 javac 报 632 处 `<identifier> expected`。
4. **`unzip -n` 而非覆盖**：早期版本用覆盖式解包，**改写了 453 个原始类中的 174 个**；已修为 `-n` 并加 6b 步守恒断言。

### 10.3 现行交付值（t23 后）

| 项 | 值 |
|---|---|
| `libwebrtc-java.jar` sha256 | **`7dbe840049e239fbbd18d7f921b3d3cfc6ea6c1b61026bd8d75261c0551c98d1`** |
| 大小 / class 数 | **1 187 970 B** / **508**（453 基线 + 55 新增） |
| `*Jni.class` | **48**（= `gen/**/input_srcjars` 下 `*Jni.java` 的 48，**差集为空**） |
| `*Natives.class` | **48** |
| class 版本分布 | **`0x0037`(major 55) × 51 + `0x003d`(major 61) × 457** —— **全部 ≤ 61** ✅ |
| 原始 453 类守恒 | **相同 453 / 不同 0 / 缺失 0** ✅ |
| `libwebrtc-arm64.aar` sha256 | **`4878509a9a0bce254d71f728fb1ff6635b7fea8e310342216b454672f8dfe028`**（6 489 348 B） |
| AAR 内 `classes.jar` | = 现行 jar（**同哈希 `7dbe8400…`**，内含 48 `*Jni`） |
| AAR 内 `.so` / 目录 `.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e`（**未变**） |
| `libvpx/lib/libvpx.a` | `e280b11bcc9eff8c…`，**1 929 142 B**（**未变**） |

### 10.4 验收命令与原始输出（3 条，逐条实测）

```console
$ unzip -l third_party/libwebrtc/java/libwebrtc-java.jar | grep -c 'Jni.class'
48                                    # 修复前 = 0

$ javap -p -cp third_party/libwebrtc/java/libwebrtc-java.jar org.webrtc.PeerConnectionFactoryJni
Compiled from "PeerConnectionFactoryJni.java"
class org.webrtc.PeerConnectionFactoryJni implements org.webrtc.PeerConnectionFactory$Natives {
  private static org.jni_zero.JniTestInstanceHolder sOverride;
  org.webrtc.PeerConnectionFactoryJni();
  public static org.webrtc.PeerConnectionFactory$Natives get();     ← ✅ 存在
  public static void setInstanceForTesting(org.webrtc.PeerConnectionFactory$Natives);
  public long createAudioSource(long, org.webrtc.MediaConstraints);
  public long createAudioTrack(long, java.lang.String, long);
  ... (共 25 个方法)

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

### 10.5 链路完整性检查器（新工具）

`scripts/check_jar_link_integrity.py`（sha256 `fec0bc43dc196ad97fc10633f13d1cfa3c6822e3fe2fffe456a3c320dcce8228`，11 786 B）：
- 解析 jar 内**全部 class 的常量池**，把所有 `org/webrtc/*Jni` 与 `org/jni_zero/*Jni` 引用做**严格**检查：**引用了但 jar 内无定义 ⇒ 缺失计数 +1**；退出码非 0 即失败。
- 对全部 `org/**` 引用做 **INFO** 报告（排除平台前缀 `android/ java/ javax/ dalvik/ sun/ jdk/`），不参与判定。
- 断言 `org/webrtc/PeerConnectionFactoryJni` **存在** + 有 `public static get()` + 调用 `GEN_JNI`；断言 `GEN_JNI` 存在且 native 声明数 = 194。
- **实测：严格缺失 = 0、INFO 缺失 = 0、rc = 0** —— 即「jar 内每个被引用的 `*Jni` 类都能在 jar 内找到」，满足验收项 2。

### 10.6 可复现命令（含 `--release` 实测值与补丁证据）

```bash
# ① 前置：确认构建树 --release 实测值 = 17（脚本 step 0 会自行断言）
#    build/android/gyp/compile_java.py  L708-711:  '--release',  …  '17'
#    build/android/gyp/turbine.py       L110:      javac_cmd = ['--release', '17']
#    两文件均无残留 '25'（grep "'25'" ⇒ 空）
# ② 执行确定性合并（幂等；--apply 才写回 third_party，默认只演算）
bash scripts/build_java_sdk_with_jni.sh --apply
# ③ 复核
python3 scripts/check_jar_link_integrity.py third_party/libwebrtc/java/libwebrtc-java.jar
```

**补丁与脚本指纹**（可复现性锚点）：

| 文件 | 大小 | sha256 |
|---|---|---|
| `scripts/build_java_sdk_with_jni.sh` | 13 336 B | `538aae2cd5f63ad7876d5684081595bcb70fa398887fed3738635d05eed7f220` |
| `scripts/check_jar_link_integrity.py` | 11 786 B | `fec0bc43dc196ad97fc10633f13d1cfa3c6822e3fe2fffe456a3c320dcce8228` |
| `scripts/patches/libwebrtc-java-release17.patch` | 983 B | `a51236ac4b869f1b62b9872c522afee9545dd60c38bd02c9f4d3851405347837` |

补丁正文把 `compile_java.py:711` 的 `'25' → '17'`、`turbine.py:110` 的 `['--release','25'] → ['--release','17']`；以 `patch -p1` 从 `<src>` 应用。`scripts/t5-libwebrtc-libvpx-build.sh` 内置 `ensure_java_release_patch()` 守卫（`phase_gn`/`phase_ninja` 均调用；`JAVA_RELEASE_TARGET=17`），两文件同为 17 ⇒ 跳过；同为 25 ⇒ 打整补丁；混合 ⇒ 逐文件 sed；其它 ⇒ FATAL rc=1。

### 10.7 未改动项与遗留（如实记录）

1. **`out/` 未改动**：t23 的合并发生在 `out/` 之外，`out/Release-arm64/lib.java/sdk/android/libwebrtc.jar` 仍为 `d98939bb…`（453 类）。因此**该文件与现行交付 jar 不再同哈希**（§7 已标注）。jar 版本字节、`.so`、AAR 内 `.so` 均未受影响。
2. **`is_debug`**：**仍为 `false`**；本任务未改为 `is_debug=true`，也未新增/删减 GN 参数（日志能力维持 t5 登记的 `rtc_dlog_always_on=true`，C29/R10）。
3. **`libvpx.a` 与 `.so` 未变**（哈希 + 字节数双重确认），未触碰 `app/**`、`doc/**`、`third_party/libvpx/**`。
4. **遗留观察（超出 t23 范围，供上游判断）**：交付 `.so` 内含 **193 个 `Java_J_N_M*` + 156 个 `Java_J_N_*` 混淆 JNI 符号、0 个可读 `org_webrtc_*` / `Java_org_webrtc_*` 符号**，而 Java 侧 codegen 走**可读名**（`GEN_JNI.org_webrtc_Logging_log`），且 Java 侧**不含任何混淆 `J_N_*` 类** —— 存在 jni_zero 混淆名不匹配的可能，**本次未验证运行期链接**，建议 app 侧首次联调时优先验证。

