# reports/25-encoder-vpx-encode-crash.md —— 自研 VP9 首帧 `vpx_codec_encode` 崩溃：根因与修复（t55）

- 任务：t55（native-dev）；范围：`app/src/main/cpp/encoder/**` + 本报告。
- 真机证据（只读）：宿主 `/opt/dsh-workspaces/tmp/dl-a|x`、`dl-c|x`、`dl-d|x`（t55 描述中列出的崩溃现场）。
- 宿主复现环境（captain 备好）：`/opt/dsh-workspaces/tmp/libvpx-host/`（`generic-gnu` x86_64 静态 libvpx，1 758 780 B）。
- 容器内**未跑 gradle**：宿主 harness 通过 SSH 在宿主编译运行；离线自测与语法检查在容器内执行（§6）。

---

## 1. 结论摘要（一句话）

**根因不在我们的配置、不在图像构造、也不在时间基/pts，而在交付的 `libvpx.a` 本身**：它是用
`--disable-runtime-cpu-detect` 构建的（`scripts/t5-libwebrtc-libvpx-build.sh:284`），该开关把 libvpx 的
运行时 SIMD 派发退化成**编译期 `#define` 直连**，而构建时 NDK clang 的特性探测又打开了
**SVE/SVE2/dotprod/i8mm**（`vpx_config.h:22-25`）。于是：

- `vp9_rtcd.h:56` → `#define vp9_block_error vp9_block_error_sve`
- `vp9_rtcd.h:61` → `#define vp9_block_error_fp vp9_block_error_fp_sve`
- `vpx_dsp_rtcd.h` 里 **78 个**函数 → `*_neon_i8mm` / `*_neon_dotprod`

`vp9_block_error()` 是**首帧（关键帧）率失真决策必调**的函数，而真机 CPU 没有 SVE ⇒ 该函数一执行
就触发**非法指令（SIGILL）**，进程当场死亡 —— 日志停在 `encode_vpx_begin`、`encode_vpx_done`
永不出现、几秒后新进程启动，与真机现象**逐条吻合**（t55 描述的 5 次复现）。

**宿主为什么复现不出**：宿主 libvpx 是 `generic-gnu`（无 SIMD，配置里 `HAVE_SVE/SVE2/DOTPROD/I8MM`
全 0）⇒ 同样的 C 逻辑、但没有任何 SVE/i8mm 内核，所以不崩（§2、§3）。
**libwebrtc 默认 VP9 为什么在同一台设备上正常**：它用的是 Chromium 自己那份 libvpx（运行时 CPU 探测
开启，运行时按 CPU 能力选内核），不是我们这份编译期绑定的 `.a`。

**修复**：
1. **本任务内**（`vp9_encoder.cpp`）：初始化前用 `getauxval(AT_HWCAP/AT_HWCAP2)` 自检 CPU 是否满足交付件的
   编译期 SIMD 假定；不满足则**拒绝启用自研编码器并回退默认编码器**（返回 `FALLBACK_SOFTWARE`），
   并打一条可 grep 的判据日志 —— 把「必崩」变成「优雅回退 + 可诊断」。
2. **真正的修复（需你派构建任务，超出本任务 inScope）**：重建 `third_party/libvpx`，**去掉
   `--disable-runtime-cpu-detect`**（或显式 `--disable-neon-dotprod --disable-neon-i8mm --disable-sve --disable-sve2`）。
   重建后自研编码器才能在该设备上真正出帧，届时按 §7 的 U1–U4 复测，并可按 t53 的定案把
   `useDefaultEncoder` 改回 `false`。

---

## 2. 宿主复现实验（按 acceptance 要求，先复现）

### 2.1 harness（逐字段复刻 `Vp9Encoder::Init/Encode`）

- 文件：`webrtc-build/t55-harness/harness.cpp`（仓外，sha256 `a65e49cc…6733`），
  宿主机路径 `/opt/dsh-workspaces/webrtc-build/t55-harness/harness.cpp`。
- 复刻字段：`profile 0`、`g_lag_in_frames=0`、`g_threads=1`、`g_timebase=1/1000000`、`ONE_PASS`、`CBR`、
  `rc_min/max_quantizer=4/56`、`rc_buf_sz/initial/optimal=600/400/500`、`kf_mode=VPX_KF_AUTO`、
  `kf_min_dist=0`、`kf_max_dist=3000`、`ss_number_layers=1`、`ts_number_layers=3`、
  `temporal_layering_mode=0212`、`ts_rate_decimator={2,1,1}`、`ts_target_bitrate/layer_target_bitrate={120,210,300}`、
  `ss_target_bitrate[0]=300`、`VP8E_SET_CPUUSED=8`、`VPX_DL_REALTIME`；
  图像用 libvpx 自持缓冲（`vpx_img_wrap(NULL, …, 1, NULL)`，`img_data_owner=1`）+ 逐行拷贝（复刻 t50b）；
  pts/时长用真机数值（首帧 `pts_us=714264735992`、`dur=50000`）。
- 序列复刻真机：`enc_init(640×480)` →（无任何编码）→ `destroy + enc_init(480×640)` → 首帧 `FORCE_KF`，
  共 10 帧。

编译/运行（宿主机，g++ 12）：
```bash
ssh … root@172.21.0.219 'cd /opt/dsh-workspaces/webrtc-build/t55-harness && \
  g++ -O1 -g -I/opt/dsh-workspaces/tmp/libvpx-host harness.cpp \
      /opt/dsh-workspaces/tmp/libvpx-host/libvpx.a -lpthread -o harness && ./harness'
```

### 2.2 结果：**宿主不崩**

原始输出（`webrtc-build/t55-work/harness-default.log`，sha256 `ef3e4fdd…246`）摘要：
```
enc_init(640x480) rc=0 OK
raw_img alloc(640x480) sy=640 su=320 sv=320 owner=1
-- reinit: destroy + enc_init(480x640)
enc_init(480x640) rc=0 OK
raw_img alloc(480x640) sy=480 su=240 sv=240 owner=1
[rot-size] frame 0: … owner=1 … pkts=1      ← 首帧（关键帧）即出包
[rot-size] frame 1..9: … pkts=1
[rot-size] produced=10 bytes=19870
== harness done ==   RUN_EXIT=0
```
变体矩阵（宿主全部 OK，用于**排除配置类假设**）：
| 变体 | 结果 |
| --- | --- |
| 默认（复刻真机） | OK，出 10 包 |
| `--ts=1`（关时序分层） | OK |
| `--cpu=0`（最慢档，另一套 speed features） | OK |
| `--no-reinit`（先在 Init 尺寸编 1 帧再换尺寸） | OK |
| `--kf=0` | OK |

⇒ **配置字段、图像构造、时间基/pts/duration、时序分层矩阵在 x86 C 版 libvpx 上全部正常**，
崩溃不来自这些（与 captain 建议的二分顺序逐一排除）。

### 2.3 追加对照实验：拆开「尺寸变化」的三种走法（逐条证伪 t50 前的几何假设）

captain 提示的假设是：`640×480 → 480×640` **像素数相同但几何不同**（307200 = 307200），
`vp9/encoder/vp9_encoder.c:2170-2182` 的「尺寸增大才重分配上下文缓冲」判据可能漏掉这种交换，
于是 `update_frame_size` 用新几何 memset `mbmi_ext_base` 而缓冲仍是旧几何 ⇒ 越界。
为此在 harness 里加了 `--path=` 开关，把三条走法**分别**跑 10 帧（宿主、同一份 C libvpx）：

| `--path` | 走法 | 结果 | 日志（`webrtc-build/t55-work/`） |
| --- | --- | --- | --- |
| `reinit`（默认，t50 现行代码） | `enc_init(640×480)` → `destroy`+`enc_init(480×640)` | **OK，produced=10** | `pathreini.log`（sha256 `cf0083778f68…`） |
| `configset`（**pre-t50 原路径**） | `enc_init(640×480)` → **`vpx_codec_enc_config_set(480×640)`** | **OK，produced=10** | `pathconfigse.log`（sha256 `f1c4fffc2f90…`） |
| `direct480` | **直接** `enc_init(480×640)`（完全不改尺寸） | **OK，produced=10** | `pathdirec480.log`（sha256 `3bba721538b7…`） |

原始输出摘要：
```
--path=configset : enc_config_set(480x640) rc=0 OK → 10 帧 pkts=1 → produced=10 bytes=19870
--path=reinit    : enc_init(480x640) rc=0 OK       → 10 帧 pkts=1 → produced=10 bytes=19870
--path=direct480 : enc_init(480x640) rc=0 OK       → 10 帧 pkts=1 → produced=10 bytes=19870
```
**结论（证伪）**：在纯 C 版 libvpx 上，**连 pre-t50 的 `vpx_codec_enc_config_set` 路径也不崩**
（含 `cpi->initial_width/height` 与 `update_frame_size` 的旧几何/新几何组合）。
⇒ 「缓冲未重分配 → 越界」这条几何假设**不成立**（至少不是 x86 C 版上的可复现因素），
进一步把差异收敛到 §3 的**编译期 SIMD 绑定**上；这与 t50（reinit）与 t50b（自持图像）两次
真机失败尝试的结论一致：改的都是几何/图像，而问题在库本身。

---

## 3. 宿主 vs 真机的差异（acceptance 要求：不崩就必须给出差异）

| 维度 | 宿主（本次复现用） | 真机交付件 |
| --- | --- | --- |
| 目标 | `--target=generic-gnu`（x86_64，纯 C） | `--target=arm64-android-gcc`（NDK clang 17） |
| SIMD | `HAVE_NEON/SVE/SVE2/DOTPROD/I8MM` **全 0**（宿主无 yasm，且 generic-gnu） | **`HAVE_SVE=1`、`HAVE_SVE2=1`、`HAVE_NEON_DOTPROD=1`、`HAVE_NEON_I8MM=1`**（`vpx_config.h:22-25`） |
| 运行时派发 | `CONFIG_RUNTIME_CPU_DETECT 0`（无 SIMD 可派发） | `CONFIG_RUNTIME_CPU_DETECT 0`（**关键**：派发被编译期固化） |
| 其它配置差异 | `CONFIG_WEBM_IO 0` vs 真机 `1`；`HAVE_PTHREAD_H 1` vs 真机 `0` | 与编码行为无关（`g_threads=1`，不创建 worker） |
| 编译器/ABI | gcc 12 / x86_64 SysV | clang 17 / AArch64 AAPCS64 |

**决定性差异 = “编译期 SIMD 绑定”**（§4）。真机侧的产物证据（容器内直接读取，`third_party/libvpx/lib/libvpx.a`）：

1. `llvm-nm` 计数：**93** 个 `*_i8mm`/`*_dotprod` 符号（`vpx_convolve8*/convolve12*/variance*/subpel_variance*`），
   另有 **3** 个 `*_sve` 符号：`vp9_block_error_sve`、`vp9_block_error_fp_sve`、`vpx_sum_squares_2d_i16_sve`。
2. `webrtc-build/libvpx-src/vpx_dsp/../*_rtcd.h`（生成物，即编译时真正生效的绑定）：
   ```c
   vpx_dsp_rtcd.h:78 :  #define vpx_convolve8_horiz vpx_convolve8_horiz_neon_i8mm
   vpx_dsp_rtcd.h:979:  #define vpx_variance16x16  vpx_variance16x16_neon_dotprod
   （同类绑定共 78 条）
   vp9_rtcd.h:56      :  #define vp9_block_error     vp9_block_error_sve
   vp9_rtcd.h:61      :  #define vp9_block_error_fp  vp9_block_error_fp_sve
   ```
   —— `#define`（不是函数指针 + `setup_rtcd_internal()`），**没有任何运行时判定**。
3. 反汇编真机 `.a` 内的 `vp9_block_error_sve`（`llvm-ar x` → `llvm-objdump -d`）证明它就是 SVE 代码：
   ```
   10:  sdot   z0.d, z2.h, z2.h      ← z 寄存器 + SVE 的 SDOT
   18:  sdot   z0.d, z3.h, z3.h
   1c:  sabd   v4.8h, v2.8h, v4.8h
   ```
   `z*` 寄存器与 SVE `SDOT` 在**不支持 SVE 的 CPU 上必然触发 SIGILL**。

---

## 4. 根因链（file:line）

1. 构建：`code/webrtc-demo/scripts/t5-libwebrtc-libvpx-build.sh:284`
   `./configure --target=arm64-android-gcc … --disable-runtime-cpu-detect --enable-pic`
   —— 为“减少运行时开销”关掉了能力探测，**同时**让 clang 的特性探测把 SVE/SVE2/dotprod/i8mm 打开
   （`webrtc-build/libvpx-src/vpx_config.h:22-25`，`CONFIG_RUNTIME_CPU_DETECT 0` 在 `:66`）。
2. 生成：libvpx 的 rtcd 生成器在该配置下把 `_rtcd.h` 里的函数名直接 `#define` 到**最佳 SIMD 版本**
   （`vp9_rtcd.h:56/61`、`vpx_dsp_rtcd.h:78/979`，共 78+3 条）。
3. 运行：首帧是关键帧，`vp9_encode_frame → … → vp9_block_error()`（率失真误差计算）从而调用
   `vp9_block_error_sve`；真机 CPU 无 SVE ⇒ **SIGILL**。
4. 现象：进程立即死亡且**不产生任何 C++ 日志**（信号而非异常）⇒ 真机最后一行正是
   `encode_vpx_begin frame=1 …`（该行打印在 `vpx_codec_encode` 之前 `vp9_encoder.cpp:635`），
   `encode_vpx_done`（`:653`）永不出现；App 重启后新的 `encoder_init` 再来一次，循环复现 5 次。
5. 反证（为什么不是我们的配置/代码）：
   - 宿主用**同一份 C 逻辑**、同一组字段、同样的 reinit 与图像构造 → 出 10 包不崩（§2）；
   - 把编码器换成 libwebrtc 默认通路（它那份 libvpx **开启**运行时探测）→ 同一台设备正常出画面；
   - 崩溃点在 `vpx_codec_encode` 内部（begin/done 标记已把范围夹住），而我们的 JNI/图像/日志全在调用之外。

> 结论类别（按 acceptance 归类口径）：**「图像或缓冲构造」「时间基/pts/duration」「时序分层矩阵」
> 「配置字段非法/不一致」全部排除；属于「交付库与运行目标 CPU 的指令集不匹配（构建期 SIMD 绑定）」**。
> 这一结论与 t50/t50b 两次失败尝试一致：它们改的都是配置/图像，而问题在库本身。

---

## 5. 修复

### 5.1 本任务落地（`app/src/main/cpp/encoder/`）

| 文件 | 变更 |
| --- | --- |
| `encoder/libvpx_cpu_guard.h`（新增） | 纯函数 `CheckLibvpxCpu(hwcap, hwcap2)` + 5 个状态与稳定短名（`ok/missing_sve/missing_sve2/missing_dotprod/missing_i8mm`）；常量注解给出内核 uapi 位值（SVE `1<<22`、ASIMDDP `1<<20`、SVE2 `1<<1`、I8MM `1<<13`） |
| `encoder/vp9_encoder.cpp` | `Init()` 开头调用 `DeliveredLibvpxCpuOk()`（`getauxval(AT_HWCAP/AT_HWCAP2)`，结果缓存）：**不满足即 `return kVp9FallbackSoftware`**；满足打 `encoder_cpu_ok`，不满足打 `encoder_cpu_incompatible reason=… hwcap=… hwcap2=…` |
| `encoder/libvpx_cpu_guard_host_test.cpp`（新增） | freestanding 离线单测：穷举 9 种 hwcap 组合 + 位值自洽 + 短名稳定（§6） |

**语义不变声明**：JNI 方法表与参数顺序未动；图像仍为 libvpx 自持缓冲 + 逐行拷贝（t50b）；
t46 的像素旋转与尺寸交换未动；`encode_vpx_begin/done/no_packet`、`encoder_reinit`、
`encoder_raw_img_alloc` 取证标记全部保留；`setRates`/`FoldSdkLayerMatrix`（t48）未动；
额外路径只在 `Init()` 加了一个**提前拒绝**分支（新事件名 `encoder_cpu_ok`/`encoder_cpu_incompatible`，
登记于此报告）。

### 5.2 必须由构建任务完成的部分（超出本任务 inScope，已在 §1 写明）

重建 `third_party/libvpx`（arm64）：
```bash
./configure --target=arm64-android-gcc \
  --enable-vp9 --enable-vp9-encoder --enable-vp9-decoder \
  --disable-vp8-encoder --disable-vp8-decoder \
  --enable-static --disable-shared --disable-examples --disable-tools \
  --disable-docs --disable-unit-tests --enable-pic \
  # ← 去掉 --disable-runtime-cpu-detect（或显式 --disable-neon-dotprod --disable-neon-i8mm --disable-sve --disable-sve2）
```
验收（重建后）：`llvm-nm libvpx.a | grep -c '_sve$'` 对 `vp9_block_error_sve` 不再出现在
`vp9_rtcd.h` 的绑定里；`grep -n 'vp9_block_error ' vp9_rtcd.h` 应指向 `_neon` 或 `_c`。
重建 + 重新打包 APK 后，才可把 `useDefaultEncoder` 改回 `false` 并复测（t53 定案口径）。

---

## 6. 验证（容器内 / 宿主真实执行）

1. **宿主 harness 出包**（§2.2）：`produced=10 bytes=19870`，`RUN_EXIT=0`；
   日志 `webrtc-build/t55-work/harness-default.log`（sha256 `ef3e4fdd…246`）。
2. **离线自测（CPU 自检）**：
```bash
NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
cd code/webrtc-demo
$NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
  -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra -DLIBVPX_CPU_GUARD_HOST_TEST \
  -I app/src/main/cpp app/src/main/cpp/encoder/libvpx_cpu_guard_host_test.cpp \
  -o <工作区>/t55-work/cpu_guard_test -Wl,-e,_start -Wl,--build-id=none
<工作区>/t55-work/cpu_guard_test ; echo "exit=$?"
```
结果（`webrtc-build/t55-work/cpu_guard_test.log`，sha256 `81c43a90…a77`）：
```
  OK   all extensions present -> ok
  OK   typical phone (dotprod only) -> missing_sve      ← 交付件在“普通手机”上必须被拒绝
  OK   missing sve / sve2 / dotprod / i8mm（逐项）
  OK   no extensions at all -> missing_sve
  OK   hwcap bit values ； OK status names stable
== result: failures=0 ==   exit=0
```
3. **真实目标工具链语法检查**：`clang++ --target=aarch64-linux-android26 -std=c++17 -fno-exceptions -fno-rtti
   -Wall -Wextra -I app/src/main/cpp -I third_party/libvpx/include -fsyntax-only`
   对 `encoder/vp9_encoder.cpp`、`jni/vp9_encoder_jni.cpp` **均 exit=0，无诊断**。
4. **产物证据**（§3）：`llvm-nm` 计数 93 + 3；`vp9_rtcd.h:56/61`、`vpx_dsp_rtcd.h:78/979`；
   `vp9_error_sve.c.o` 反汇编含 `sdot z0.d, z2.h, z2.h`。

---

## 7. 真机判据（下一轮只需看这几行）与未验证项

| # | 判据 / 未验证项 | 怎么看（**修好前**，即当前这版） | 怎么看（**libvpx 重建后**） |
| --- | --- | --- | --- |
| U1 | 崩溃是否消失 | `native.log` 不应再出现「`encode_vpx_begin` 之后无任何行 + 进程重启」；应出现 `encoder_cpu_incompatible reason=missing_sve hwcap=… hwcap2=…`（自检拒绝）或 `encoder_cpu_ok`（CPU 其实满足） | 先看 `encoder_cpu_ok`；再要求 `encode_vpx_begin` 后跟 `encode_vpx_done` |
| U2 | 是否出帧 | 自研编码器会被主动拒绝 ⇒ `encoded_bytes` 仍为 0（**这是预期行为**，不是回归） | `encoded_frame` 出现、`encoder_bitrate.csv` 的 `encoded_bytes > 0` |
| U3 | 稳定性 | `app.log` 不再出现新的 `main_activity_create`/`native_lib_loaded` 重启序列（通话 ≥10 s） | 同上，且通话 ≥10 s 无重启 |
| U4 | 双端出画面 | 由默认编码器（`useDefaultEncoder=true`，t53 定案）保证；本缺陷不影响它 | 改回 `false` 后双端出画面（像素旋转 t46 语义同时回归） |

**未验证项（如实声明）**：
- V-1：本轮**没有**在真机验证「拒绝后是否真的优雅回退」；回退路径依赖
  `initEncode` 返回 `FALLBACK_SOFTWARE` → `VideoEncoderWrapper::HandleReturnCode` → 工厂回退，
  但真机行为只能由 U1 判定。
- V-2：设备 CPU 到底缺哪一项（SVE？SVE2？i8mm？）**未在本轮测出**：自检日志 `reason=` 会给答案；
  容器内无法读取真机 `/proc/cpuinfo` 或 `getauxval`。
- V-3：`--disable-runtime-cpu-detect` 之外，`HAVE_PTHREAD_H=0`（真机配置）等差异**未逐一实测**；
  已论证 `g_threads=1` 下不创建 worker 线程，故不构成崩溃因素，但未做实验证伪。
- V-4：重建 libvpx 后的性能代价（运行时探测 + 少用 i8mm/dotprod 内核）未评估。

**与 t53 的关系**：t53 修的是会话/answer 竞态（已真机验证），与本缺陷**独立**；
本缺陷修好（重建 + 自检通过 + U1–U4）之前，交付应继续默认 `useDefaultEncoder=true`。

## 8. 交付清单

| 文件 | 变更 | sha256 |
| --- | --- | --- |
| `app/src/main/cpp/encoder/libvpx_cpu_guard.h` | 新增（CPU 自检纯函数） | `462dfcdff8babdbcf8f2365407d8f2d35085b725411cbd3f17ed73fc72df6e01` |
| `app/src/main/cpp/encoder/vp9_encoder.cpp` | `Init()` 加自检与优雅回退 + 两条日志 | `96227e0a350b1ca974d7a09a46db643df994099e7dc63fd22aa2499b62223ba2` |
| `app/src/main/cpp/encoder/libvpx_cpu_guard_host_test.cpp` | 新增（离线自测，9 断言） | `2006f078de7b93d52ba2b22bcdba7b0943fb03819fe80824eba386b5b6368517` |
| `reports/25-encoder-vpx-encode-crash.md` | 本报告（mode 644） | 见提交记录 |
| 仓外（不入库） | `webrtc-build/t55-harness/harness.cpp`、`webrtc-build/t55-work/*` | 见 §2/§6 |

未改：`third_party/**`（重建需新构建任务）、`nat/**`、`kotlin/**`、`doc/**`、`scripts/**`、
`app/src/main/cpp/CMakeLists.txt`（逐文件列举源，新增自测文件不参与 App 构建）。
