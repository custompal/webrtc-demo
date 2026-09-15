# reports/26 — arm64 libvpx 重建：开启运行时 CPU 探测（修真机 SIGILL）

- 任务：**t56**（webrtc-builder），执行时间 **2026-09-15 12:23:53 → 12:27:05 (+08)**
- 一行结论：**已按新口径重建并落位交付目录**；`third_party/libvpx/lib/libvpx.a` = **`2607d1cfd38fc34ab5772d85d4dbcb735746adc2baca68be24ca4a150f22b323`**（**1 956 632 B**）；
  新口径 = **`--enable-runtime-cpu-detect`（显式）且 `--disable-sve --disable-sve2`（显式）**；
  本项目验收脚本自跑 = **PASS**；同一脚本对**旧件 `e280b11b…`** 与**并发落位件 `a983dba3…`** 均 **FAIL**（对照，见 §4.3）。
- **未验证项（真机运行期）见 §7**；本轮**未**编 APK、**未**改 `app/src/**`、**未**碰 `third_party/libwebrtc/**`（§5 给出未受影响哈希）。

---

## 1. 根因复核（第一手，全部命令原始输出见 `reports/26-t56-evidence.log`）

### 1.1 构建期配置（旧口径 = t5 原样）
`webrtc-build/libvpx-src/vpx_config.h`（旧 configure 产物，已归档 `webrtc-build/t56-evidence/old-vpx_config.h`）：

```
22:#define HAVE_NEON_DOTPROD 1
23:#define HAVE_NEON_I8MM 1
24:#define HAVE_SVE 1
25:#define HAVE_SVE2 1
66:#define CONFIG_RUNTIME_CPU_DETECT 0      ← 关键：运行时派发被关闭
```

`vp9_rtcd.h`（旧）：`56:#define vp9_block_error vp9_block_error_sve`、`61:#define vp9_block_error_fp vp9_block_error_fp_sve`
⇒ 运行时派发退化为**编译期 #define 直连**。

**直连绑定普查（旧件，`grep -c 'define .*<ext>$'`）**

| rtcd 头 | 直连 `_sve` | 直连 `_neon_i8mm` | 直连 `_neon_dotprod` | 合计 |
|---|---|---|---|---|
| `vp9/vp9_rtcd.h` | 2 | 3 | 0 | 5 |
| `vpx_dsp/vpx_dsp_rtcd.h` | 1 | 6 | **72** | **79** |

> t55 报告曾记 `vpx_dsp_rtcd.h` “78 条”，本轮逐条复算为 **79 条（1+6+72）**，差异 1 条，以本节实测为准。

### 1.2 交付 .a（旧件 `e280b11b…`，1 929 142 B，156 成员）内的直连证据

```
ar members matching 'sve': 2      → sum_squares_sve.c.o / vp9_error_sve.c.o
nm lines matching 'sve': 10
nm vp9_block_error*:
    U vp9_block_error_sve        ← 调用方直接引用（编译期绑定，非派发）
    T vp9_block_error_sve
    T vp9_block_error_neon / _c
调用方（`llvm-nm -A | grep ' U ' | grep sve`）：
    vp9_encodemb.c.o   U vpx_sum_squares_2d_i16_sve
    vp9_pickmode.c.o   U vp9_block_error_fp_sve
    vp9_rdopt.c.o      U vp9_block_error_sve（+1 条）
    vp9_tpl_model.c.o  U vp9_block_error_sve
SVE z 寄存器指令数（llvm-objdump -d 全档）：16
   例：38: 44c10020  sdot z0.d, z1.h, z1.h
```

⇒ **core encoder TU（`vp9_rdopt.c.o` 等）直接调用 `*_sve`**；首帧为关键帧 ⇒ RD → `vp9_block_error` ⇒ 无 SVE 的真机 CPU 执行即 **SIGILL**（信号而非 C++ 异常 ⇒ 零日志，恰好停在 `encode_vpx_begin` 之后），与 t55 定位及 5 次真机复现吻合。

### 1.3 修复成立的机制依据（源码）
`vpx_ports/aarch64_cpudetect.c` 的 `__linux__` 分支（Android 亦走此分支，`VPX_USE_ANDROID_CPU_FEATURES` 未定义）用
`getauxval(AT_HWCAP)`（ASIMDDP=`1<<20`、SVE=`1<<22`）与 `AT_HWCAP2`（SVE2=`1<<1`、I8MM=`1<<13`）探测，并有
“SVE 需 dotprod+i8mm、SVE2 需 SVE”的约束链 ⇒ **开启运行时探测后，任何变体只在 CPU 实际具备该扩展时才被选用**。

---

## 2. 重建口径与理由（固化进 `scripts/t5-libwebrtc-libvpx-build.sh`）

```diff
-      --disable-docs --disable-unit-tests --disable-runtime-cpu-detect --enable-pic \
+      --disable-docs --disable-unit-tests --enable-runtime-cpu-detect \
+      --disable-sve --disable-sve2 --enable-pic \
```

其它口径与 t5 **逐字保持一致**：`--target=arm64-android-gcc`、`--enable-vp9/--enable-vp9-encoder/--enable-vp9-decoder`、
`--disable-vp8-encoder --disable-vp8-decoder`、`--enable-static --disable-shared`、`--disable-examples/-tools/-docs/-unit-tests`、
`--enable-pic`；NDK **26.1.10909125**、`API=21`、`CHOST/CROSS/CC/CXX/AR/AS/LD/STRIP` 与 t5 相同；`make -j4`。

**为什么两项一起做**（acceptance 允许“二者择一”，此处取**并集**并说明）：
1. `--enable-runtime-cpu-detect`：**必须项**——恢复 rtcd 派发。旧件的问题不止 SVE：`vpx_dsp_rtcd.h` 里 **72 条 `_neon_dotprod` + 6 条 `_neon_i8mm`** 也是**编译期直连**，同样会在不支持 dotprod/i8mm 的 CPU 上 SIGILL；只关 SVE 不解决这一类。
2. `--disable-sve --disable-sve2`：**加固项**——SVE/SVE2 在现行 Android 手机上无收益（无 CDD 要求、机型极少），却是本事故的爆炸半径；关闭后 `HAVE_SVE=HAVE_SVE2=0`，产物内 SVE 成员/符号/指令**一律为 0**，可**静态验收**（不需要真机即可判）。
3. 保留 `neon_dotprod`/`neon_i8mm`（不关）：它们现在经 HWCAP 运行时选择；关掉会白白损失新机型性能。
4. `vp9_block_error(_fp)` 在新件里是 `#define ... _neon`：因为关闭 SVE 后该函数只剩 `c`/`neon` 两个变体，而 **NEON(ASIMD) 是 AArch64 的强制基线**（ARMv8-A 起必选），故直连 NEON 是安全的；多扩展变体的函数（如 `vpx_convolve8_horiz`）则一律走 `RTCD_EXTERN` 指针表 + `vpx_dsp_rtcd()` 初始化。

脚本同时新增**口径自检**（不合规即 `FATAL` 返回非 0，不再把带 SVE 直连的库当成功产物放行）：
`SVE 成员数 == 0` 且 `CONFIG_RUNTIME_CPU_DETECT == 1`；`phase_extract` 另把构建期 `vpx_config.h` 落进交付目录 `third_party/libvpx/include/vpx_config.h`（libvpx 的 `make install` 不安装该文件）。

---

## 3. 构建过程与产物落位

| 项 | 值 |
|---|---|
| 源码 | `third_party/libvpx-src` submodule，HEAD **`d2413e2ca11039724ca33bb4d661ca2c94cb501e`**（libvpx **v1.17.0**） |
| 纯净源取法 | `git archive HEAD` → `tmp/t56/libvpx-src-d2413e2c.tar`（sha256 `cb748da13a9383920e5c8aa2bd8650bd47777c64543e4dcc17f5235f984c0ee5`，1 343 条目 / 1 258 个受跟踪文件）——避免复用旧 configure 产物 |
| 构建树 | `webrtc-build/t56-libvpx-src`（本轮新建，**未**在 submodule 内构建） |
| 输出 | `webrtc-build/t56-libvpx-output`（`make install`，INSTALL_RC=0） |
| 耗时 | configure `12:23:53→12:23:59`（6 s，RC=0）；`make -j4`+`make install` `12:23:59→12:24:57`（58 s，RC=0）；**合计 64 s** |
| 磁盘 | 构建前后 `/opt/dsh-workspaces` 可用 25 GB，无水位告警（libvpx 构建量级 << 1 GB） |
| 新件 | `third_party/libvpx/lib/libvpx.a` = **`2607d1cf…`**，**1 956 632 B**，154 成员，owner `admin:admin`(uid 1000)，mtime `2026-09-15 12:27:05.777685382` |
| 新件（新增） | `third_party/libvpx/include/vpx_config.h` = **`8ea46c68af0f2ab518edde4bcc435eee25b2135a912c75296f24c41ab7d69305`**，3 214 B |
| `include/vpx/*.h`（11 个） | 与旧交付**逐字节相同**（`SAME` ×11）⇒ 未动，改动面最小 |
| `lib/pkgconfig/vpx.pc` | 仅 `prefix=` 不同（构建期路径 vs 交付路径），**保留交付件原样** |
| 落位方式 | `cp -f <输出> <交付>`；`cmp` 双件与构建产物**逐字节一致**；`chown -R 1000:1000 third_party/libvpx` |

**旧件归档（不覆盖历史证据）**

| 归档件 | 路径（容器可见：`/data/dsh/home/workspace/...` ≙ 宿主 `/opt/dsh-workspaces/...`） | sha256 | 大小 |
|---|---|---|---|
| t5 原始件（含 SVE 直连，真机 SIGILL 的那个） | `tmp/t56/libvpx.a.pre-t56-e280b11b`（另存 `tmp/libvpx-old-20260915-122204.a`、`webrtc-build/libvpx-src/libvpx.a`） | `e280b11bcc9eff8c5be20f35023c79319eeb374a5277d1972c0eb310fe3215f7` | 1 929 142 |
| 并发写者落位件（非本轮，见 §6） | `tmp/t56/libvpx.a.pre-t56-a983dba3` | `a983dba3828d9673ec9cd8736b2e2125cc14c472d3d23a1f00189278720a2a6d` | 1 961 000 |

---

## 4. 验收证据

### 4.1 新件口径（`reports/26-t56-evidence.log`）

```
ar members: 154        ar members matching 'sve': 0        nm lines matching 'sve': 0
SVE z 寄存器指令数: 0        (旧件 16)
nm vp9_block_error*: T vp9_block_error_c / T vp9_block_error_neon（_sve 变体不存在）
非分派器直连 *_sve 引用数: 0  (旧件 5)
分派器在位: T vpx_dsp_rtcd / T vp9_rtcd / T arm_cpu_caps
vpx_dsp_rtcd.h 直连 _sve/_neon_i8mm/_neon_dotprod = 0 / 0 / 0   (旧件 1 / 6 / 72)
vpx_convolve8_horiz: RTCD_EXTERN void (*vpx_convolve8_horiz)(…)  ⇒ 指针表派发（vpx_dsp_rtcd.c.o 内 B vpx_convolve8_horiz）
vpx_config.h: CONFIG_RUNTIME_CPU_DETECT 1 / HAVE_SVE 0 / HAVE_SVE2 0 / HAVE_NEON_DOTPROD 1 / HAVE_NEON_I8MM 1
```

引用 i8mm/dotprod 变体的成员（新件）：`vpx_dsp_rtcd.c.o` 84 · `vp9_rtcd.c.o` 6 · `subpel_variance_neon_dotprod.c.o` 8 · `vp9_temporal_filter_neon_{dotprod,i8mm}.c.o` 3+3
⇒ **仅分派器 + 变体内部**引用，无“非分派器直连”（旧件则见 §1.2 的 5 条 core TU 直连 `*_sve`）。

### 4.2 验收脚本（新增 `scripts/t56-libvpx-verify-runtime-cpu-detect.sh`，输出 `reports/26-t56-verify.log`）

```
### 1) 交付件 = t56 新件 2607d1cf
PASS  CONFIG_RUNTIME_CPU_DETECT=1 = 1     PASS  HAVE_SVE=0 = 1     PASS  HAVE_SVE2=0 = 1
PASS  SVE 成员数 = 0                      PASS  符号中 sve 行数 = 0
PASS  非分派器直连 *_sve 引用数 = 0        PASS  SVE z 寄存器指令数 = 0
PASS  vpx_dsp_rtcd.h 直连 _sve/_neon_i8mm/_neon_dotprod = 0/0/0     PASS  vp9_rtcd.h 直连 _sve = 0
RESULT: PASS   (EXIT=0)
```

### 4.3 对照组（同一脚本，用于证明判据有区分力）

```
### 2) 旧件 e280b11b（t5 原样）     → RESULT: FAIL (EXIT=1)
FAIL CONFIG_RUNTIME_CPU_DETECT=1 = 0 ; HAVE_SVE=0 = 0 ; HAVE_SVE2=0 = 0
FAIL SVE 成员数 = 2 ; 符号中 sve 行数 = 10 ; 非分派器直连引用 = 5 ; SVE z 指令 = 16
FAIL vpx_dsp_rtcd.h 直连 = 1 / 6 / 72 ; vp9_rtcd.h 直连 _sve = 2
### 3) 并发落位件 a983dba3（12:22:38，非本轮）→ RESULT: FAIL (EXIT=1)
FAIL SVE 成员数 = 2 ; 符号中 sve 行数 = 8 ; 非分派器直连引用 = 3 ; SVE z 指令 = 16
```

> 第 3 组说明：该件**开了运行时探测**（其 nm 有 `B vp9_block_error` 指针表），但**没有关 SVE**（仍带 2 个 SVE 成员 / 16 条 SVE 指令）；其“配置”行是**借用旧 `vpx_config.h`** 比对（该件未附带自身配置），故本组只以**产物级事实**为准。

### 4.4 复现命令

```bash
# 1) 重建（宿主机 root；脚本 = 本轮实跑件，与 scripts/t56-libvpx-runtime-cpu-detect-rebuild.sh 逐字节相同）
ssh … root@172.21.0.219 -p 5766 'bash /opt/dsh-workspaces/webrtc-build/t56-build.sh'
# 2) 验收（宿主机；第 3 参数给构建期 rtcd 目录以核验“直连绑定=0”）
ssh … 'bash /opt/dsh-workspaces/code/webrtc-demo/scripts/t56-libvpx-verify-runtime-cpu-detect.sh \
        /opt/dsh-workspaces/code/webrtc-demo/third_party/libvpx/lib/libvpx.a \
        /opt/dsh-workspaces/code/webrtc-demo/third_party/libvpx/include/vpx_config.h \
        /opt/dsh-workspaces/webrtc-build/t56-libvpx-src'
# 3) 一行验收 grep（与任务 verify 命令一致）
ssh … 'cd /opt/dsh-workspaces/code/webrtc-demo && sha256sum third_party/libvpx/lib/libvpx.a \
        && grep -n CONFIG_RUNTIME_CPU_DETECT third_party/libvpx/include/vpx_config.h; exit'
```

> **关于任务 verify 命令②（如实说明，勿记为“通过”）**：它 grep 的是 `third_party/libvpx-src/vp9/vp9_rtcd.h`，该路径**不存在**，原因有二（本轮均实测）：
> ① **目录层级不符**：libvpx 的生成物落在**源码树根**（不是 `vp9/` 子目录）——旧构建树 `webrtc-build/libvpx-src/vp9_rtcd.h` 与本轮构建树 `webrtc-build/t56-libvpx-src/vp9_rtcd.h` 均如此（前者 `56:#define vp9_block_error vp9_block_error_sve`，后者 `55:#define vp9_block_error vp9_block_error_neon`）。
> ② **对象树不是构建树**：`third_party/libvpx-src` 是 pristine submodule；它内部现在确有 **12:22 由 attempt 1（并发写者）**留下的生成物（`vp9_rtcd.h` = `7ea42efe…`、`vpx_config.h` = `fd2b9d42…`、`HAVE_SVE 1` + `CONFIG_RUNTIME_CPU_DETECT 1`，其 `vp9_block_error` 为指针表故无 `#define` 行），但那是**另一次执行**的中间产物，**不代表本轮交付**——交付判据只能落在**现行 `third_party/libvpx/lib/libvpx.a`**（`2607d1cf…`）与本轮构建树。
> **等价验收命令（本轮实跑，退出码 0）**：`grep -n "define vp9_block_error " /opt/dsh-workspaces/webrtc-build/t56-libvpx-src/vp9_rtcd.h` → `55:#define vp9_block_error vp9_block_error_neon`（**不再指向 `_sve`**）；全文与旧件对照见 `reports/26-t56-evidence.log` 与 `webrtc-build/t56-evidence/{new,old}-vp9_rtcd.h`。

---

## 5. 影响面

1. **必须重链才有修复效果**：APK 内 `lib/arm64-v8a/libwebrtcdemo_native.so` 是**静态**链入旧 `libvpx.a` 的；只替换 `.a` 不会改变已装 APK。⇒ 需 captain 的**合并构建窗口**重编 APK（本轮未编 APK、未跑 gradle）。
2. **`third_party/libwebrtc/**` 未受影响（本轮实测哈希）**：`libwebrtc-java.jar` `0c776934…`、`libwebrtc-arm64.aar` `8e8f2baf…`、`libjingle_peerconnection_so.so` `757cef81…`、`jniLibs/arm64-v8a/libc++_shared.so` `c9dbf4ec…`。
3. **native-dev 的 t55 CPU 自检（`app/src/main/cpp/encoder/libvpx_cpu_guard.h`）**：新库 `HAVE_SVE=0` ⇒ SVE 分支不会触发；但该 guard 仍**要求 dotprod+i8mm**，会在**不支持 dotprod/i8mm 的老设备（ARMv8.0/8.1）上把自研编码器回退成默认编码器** —— 新库已能安全运行这些设备，建议下一轮放宽/移除该 guard（属 `app/src/**`，本轮**不动**，仅提示）。
4. **体积/性能**：`.a` 1 929 142 → 1 956 632 B（**+27 490 B，+1.4%**，来自派发指针表与初始化代码）；SVE 路径移除（Android 手机普遍无 SVE，实际无损失）；dotprod/i8mm 保留并由 HWCAP 运行时选择；`vp9_block_error(_fp)` 直连 NEON（AArch64 基线，安全）。
5. **交付目录自证**：新增 `include/vpx_config.h`（3 214 B）使 `.a` 的口径可被静态验收（verify 命令即 grep 它）。

---

## 6. 并发写者与竞态（如实披露）

轮次时间线（本轮实测）：

```
12:21:40  我复读交付路径：libvpx.a = e280b11b…（1 929 142 B，mtime 09-13 17:03）
12:22:04  他人（root）把旧件归档到 /opt/dsh-workspaces/tmp/libvpx-old-20260915-122204.a
12:22:38  他人（root）把交付路径换成 a983dba3…（1 961 000 B：runtime-detect 开、SVE 仍开）
12:23:53  本轮 t56 开始（纯净源 tar 解包 → configure → make → install）
12:27:05  本轮件落位（2607d1cf…），并把 e280b11b 与 a983dba3 双双归档到 tmp/t56/
```

⇒ **在我接管 t56（attempt 2）前后，另有执行者在写同一路径**（其产物仅部分修复：SVE 未关）。本轮件已在 12:27:05 覆盖为标准路径；**若该作者仍在运行，存在再次覆盖的风险** —— 建议 captain 明确「`third_party/libvpx/**` 本轮只由 webrtc-builder 写」。

**该并发写者的身份线索（第一手）**：仓内留有它自己的日志 `reports/10-t56-libvpx-rebuild.log`（14 205 B，mtime 12:22，root 属主）：

```
# t56 libvpx 重建（开启运行时 CPU 探测）  2026-09-15T12:22:04+08:00
# 旧件归档: /opt/dsh-workspaces/tmp/libvpx-old-20260915-122204.a
  enabling runtime_cpu_detect
  enabling sve          ← 未关 SVE/SVE2
  enabling sve2
CONFIGURE_OK
… [INSTALL] …/third_party/libvpx/lib/libvpx.a
a983dba3828d9673ec9cd8736b2e2125cc14c472d3d23a1f00189278720a2a6d  …/libvpx.a
```

⇒ 可判定其为 **t56 的另一次执行（本任务 attempt 1）**，口径 = **仅** `--enable-runtime-cpu-detect`（保留 SVE/SVE2 走派发）。
两种口径**都能消除 SIGILL**；区别在于：本轮的并集口径额外把 SVE 代码从产物中移除，使“无 SVE 残留”可**静态验收**（`nm`/`objdump` 即可判定），而仅开派发的口径仍需依赖 rtcd 初始化正确、产物内仍含 SVE 指令。
**两件都在盘、可随 captain 裁定切换**：attempt 1 件 = `tmp/t56/libvpx.a.pre-t56-a983dba3`（`a983dba3…`，1 961 000 B，SVE 成员 2 / SVE z 指令 16）；本轮件 = 现行交付路径（`2607d1cf…`，SVE 成员 0 / SVE z 指令 0）。

---

## 7. 未验证项（不得写成通过）

| 编号 | 未验证内容 | 判定方式 |
|---|---|---|
| U1 | 真机首帧不再 SIGILL | 用户复测：`native.log` 出现 `encode_vpx_done`、`encoded_frame`、`encoded_bytes>0` |
| U2 | 通话稳定性 | 通话 ≥10 s 无进程重启、双端出画面（含 RELAY 路径） |
| U3 | 重链后的 APK 行为 | 需 captain 合并构建窗口重编 APK（本轮未编）；重编前 APK 内仍是旧库 |
| U4 | t55 CPU guard 的放宽决策 | 产品/下一轮任务（无 dotprod 老设备当前会回退默认编码器） |
| U5 | 指令级 aarch64 运行验证 | 本机**无** qemu-user（实测 `qemu-aarch64`/`qemu-aarch64-static` MISSING）⇒ 本轮只有**静态**证据；如需可加装 `qemu-user-static` 后 `qemu-aarch64 -cpu cortex-a53` 做差分烟测（旧件应 SIGILL、新件应通过） |
| U6 | app 侧单测/gradle | 本轮未跑（容器不跑 gradle；本轮不改 `app/src/**`） |

---

## 8. 本轮变更文件与哈希（仓库内）

| 文件 | 状态 | sha256 |
|---|---|---|
| `scripts/t5-libwebrtc-libvpx-build.sh` | **M**（configure 参数 + 口径自检 + `vpx_config.h` 入库） | `541567064f83f134d2d1c25117028de7edd99ecf3014faf68b472374c05b4084` |
| `scripts/t56-libvpx-runtime-cpu-detect-rebuild.sh` | 新增（本轮实跑重建脚本，逐字节相同） | `d0ee6196f15653e6fc3cc1ca9bb029a290d62c4062c9f2fdda5f9226e6b025dc` |
| `scripts/t56-libvpx-verify-runtime-cpu-detect.sh` | 新增（验收脚本） | `74112f4c5efd44fbc8ac9d0eb7a956a272938773221529839bf3f88b123a5ac8` |
| `reports/26-t56-build.log` | 新增（377 行，构建原始输出） | `007ecbac8f042a966bf40fd7bd753254963dd462588e4b3051bc35e640c44efc` |
| `reports/26-t56-evidence.log` | 新增（252 行，证据合集：三件对照 + 调用方普查 + 落位核验） | `0ab390c6375c41a3f173eb21bfc1b86e0861064ede7c6eb7add3ee731decb169` |
| `reports/26-t56-verify.log` | 新增（验收脚本三次运行 + PASS/FAIL） | `2ae0136101eea1a8ef29930201668cdb65a9972ca2acc5057d7e22070a3f84d6` |
| `reports/26-libvpx-runtime-cpu-detect.md` | 新增（本报告；自哈希随本行迭代，**以 `sha256sum` 现读为准**，见完成回执） | 现读为准 |
| `reports/05-libwebrtc-build.md` | **M**（§5 configure 行加 t56 修订标注 + 追加 §18） | `7fa6600017a3d089e2ba2b29aa0a5f03f796892cdd8dfd4aad02da866d8a1605` |
| `third_party/libvpx/lib/libvpx.a` | **替换**（交付件；`.gitignore` 忽略，属工作区产物） | `2607d1cfd38fc34ab5772d85d4dbcb735746adc2baca68be24ca4a150f22b323` |
| `third_party/libvpx/include/vpx_config.h` | 新增（交付件；同上被忽略） | `8ea46c68af0f2ab518edde4bcc435eee25b2135a912c75296f24c41ab7d69305` |

- `git status --porcelain`（容器内 uid 1000，实测 12:30:23）：本轮的 `M reports/05-libwebrtc-build.md`、`M scripts/t5-libwebrtc-libvpx-build.sh` + 新文件 `reports/26-libvpx-runtime-cpu-detect.md`、`reports/26-t56-{build,evidence,verify}.log`、`scripts/t56-libvpx-{runtime-cpu-detect-rebuild,verify-runtime-cpu-detect}.sh`；**本轮未 commit**（按任务收尾要求停手等 captain 合并构建窗口）。
- 属主：以上文件全部 **node:node（uid 1000）**；交付目录 `third_party/libvpx/**` 已 `chown -R 1000:1000`。
- 构建中间件（仓外，可清理）：`webrtc-build/t56-libvpx-src`、`t56-libvpx-output`、`t56-{build,evidence,evidence2,evidence3}.sh`、`t56-evidence*/`、`tmp/t56/`（**归档件请保留**：`libvpx.a.pre-t56-e280b11b`、`libvpx.a.pre-t56-a983dba3`、`libvpx-src-d2413e2c.tar`）。
