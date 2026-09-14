# reports/99-t34-appendix.md — t34 独立复验附录（新 APK 产物级复验 + verdict）

- **任务**：t34（r2 复核：路线 A 落地 + 新 APK 产物级复验 + verdict），由 captain 于 2026-09-14 直接指示 `native-dev` 执行。
- **执行者**：`native-dev`（uid 1000）；复验对象与执行者无利益冲突（t33 实现由 captain 完成，本附录作者未参与该次构建）。
- **执行说明（如实登记）**：执行时团队任务表里 `t34` 的 assignee 仍显示 `verifier`（`agent_teams_claim_task` 被拒：`assigned to "verifier"`），故本附录作为**独立第二方复验**落盘并回报 captain；正式 verdict 归属由 captain 裁定。
- **本文件为新增文件**，不改 `reports/99-final-report.md`（verifier 维护）、不改任何交付产物、未重编 APK、未触碰 jar/AAR。

## 0. 结论（verdict）

**verdict = pass（无 finding）**。被验 APK 的产物级特征与路线 A（B 形态）交付基线逐项一致：
四载荷哈希、四 `.so` `p_align=0x4000`、`J/N`/`GEN_JNI` 类指纹、`J.N` 195 方法/193 native、
`GEN_JNI` 195 方法/0 native/声明 AV1 桩、`PeerConnectionFactoryJni` 25 方法/0 native、
单测 46/0/0 均符合。**唯一未核项**：宿主侧 `/opt/apk-http/served/app-debug.apk`（容器内不可见）、
以及**真机首调**（运行期终极判据，本容器无法执行）。

## 1. 被验对象实体

```
path   = code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk
sha256 = 30c41ac9d3363cab249c9a1702958993fcfd965cf7ebbfeba5349435ab059be2
size   = 33 309 445 B
mtime  = 2026-09-14 19:05:19 (+0800)   ctime = 19:07:29   ino = 3144249   mode = 644   owner = 1000:1000
entries= 165（zip）
```
另两处同哈希引用的核验：
```
artifacts/app-debug-30c41ac9.apk        = 同 sha256，33 309 445 B，cmp 与上者**逐字节相同** ✅
/opt/apk-http/served/app-debug.apk      = 宿主路径，容器内 /opt/dsh-workspaces 不可见 ⇒ **本容器无法核（未核项）**
```
> 说明：`mtime` 为**保留元数据**（19:05:19），落位时刻由 `ctime=19:07:29` 体现；该件与 18:41:59 快照逐字节相同，
> 结合 `ef29e00c…`（同输入、整包 sha 不同）可判定为**由冻结件还原**，而非再构建（详见 §6）。

## 2. 逐 dex 定义级（captain 第 2 项）

命令：`dexdump <classes*.dex>` → 解析 `Class descriptor` 段（方法/字段/access 标志）。
> 方法学留痕：首版解析按 4/6 空格写死缩进 ⇒ 全 0；实际格式为 `Class descriptor` 2 空格、方法条目 6 空格。
> 按"先打 raw 样本再解析"修正后得下表（`t34_dexdefs.py`）。

| 类描述符 | 定义所在 dex | 方法数 | native | 字段数 | 声明 AV1 桩 |
|---|---|---|---|---|---|
| `LJ/N;` | **classes.dex** | **195** | **193** | 0 | True（`org_webrtc_LibaomAv1Encoder_create`，非 native） |
| `Lorg/jni_zero/GEN_JNI;` | **classes13.dex** | **195** | **0** | 0 | True |
| `Lorg/webrtc/PeerConnectionFactoryJni;` | **classes14.dex** | **25** | **0** | 1 | — |

两类**各只在一个 dex 定义**。字符串/描述符**引用次数**（≥1 口径）：
```
LJ/N;                        合计=3   分布 classes.dex 2 / classes13.dex 1
Lorg/jni_zero/GEN_JNI;       合计=3   分布 classes13.dex 2 / classes14.dex 1
PeerConnectionFactoryJni     裸串=4（classes11 1 + classes14 3） ／ descriptor 形式=2（classes14）
stub_msg('Native method not present')  合计=2  分布 classes.dex 1 / classes13.dex 1
org_webrtc_LibaomAv1Encoder_create     合计=3  分布 classes.dex 1 / classes13.dex 1 / classes14.dex 1
```
> **判别意义**：`LJ/N;` 存在对 A/B 两者都成立（A 亦有 193 native、只是无 AV1 桩与 194 方法），
> 因此"含 `LJ/N;`"**不能**判 A/B；本表的价值是**定义级形态**与 captain 规格逐项吻合。

## 3. 载荷与 16 KB 页对齐（captain 第 3 项）

```
libjingle_peerconnection_so.so   757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e  12 946 912 B  p_align=0x4000
libc++_shared.so                 c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36   1 356 968 B  p_align=0x4000
libwebrtcdemo_native.so          95c44e5ab9ff6f851e5e1de26b9d28810c09017264909424e64985b57f821bc0   1 231 512 B  p_align=0x4000
libandroidx.graphics.path.so     41e9a793c43a0f4fddb19e33f346bace464f30f888ba7b9eaf96294ea115bfb6      10 096 B  p_align=0x4000
libjingle 逐 LOAD 段 align：0x4000 / 0x4000 / 0x4000（段数 3）
APK libjingle      vs app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so : 逐字节相同 = True
APK libjingle      vs third_party/libwebrtc/java/jni/arm64-v8a/…so                  : 逐字节相同 = True
APK libc++_shared  vs app/src/main/jniLibs/arm64-v8a/libc++_shared.so               : 逐字节相同 = True
（jniLibs 目录仅 2 个 .so：libc++_shared.so / libjingle_peerconnection_so.so）
```
> 注：另两个载荷（自有库、androidx）来自构建中间件/依赖 AAR，`jniLibs/` 内无对应文件（t34 只做产物级核对，不改动）。

## 4. 单测（captain 第 4 项）

直接引用 `app/build/test-results/testDebugUnitTest/`（**未重跑**，产物自带 XML）：

| XML | tests | failures | errors | skipped | mtime |
|---|---|---|---|---|---|
| `…AppConfigUrlTest.xml` | 8 | 0 | 0 | 0 | 19:07:05.899 |
| `…NativeInterfaceContractTest.xml` | 4 | 0 | 0 | 0 | 19:07:05.900 |
| `…SignalingErrorPolicyTest.xml` | 17 | 0 | 0 | 0 | 19:07:05.899 |
| `…SignalingIdentityTest.xml` | 11 | 0 | 0 | 0 | 19:07:05.901 |
| `…JniBindingClasspathTest.xml` | **6** | 0 | 0 | 0 | 19:07:05.900 |
| **合计（5 份）** | **46** | **0** | **0** | **0** | — |

## 5. 判据 ①②③④ 与类指纹（支撑证据）

```
jar  sha256 = 0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757
.so  sha256 = 757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e
① J.N static native            = 193
② GEN_JNI static native        = 0
③ GEN_JNI public static 方法数  = 194
④ .so Java_J_N_* 动态导出数     = 193
J.N 非 native 的 public static = 1（AV1 桩）   ⇒ 与 A 形态（193/194/无桩）可区分
J/N.class     = 1ff8d3ff4032643339ad271f552475740d735dddf06ae42e507bb657f98a8932  (6 924 B)
GEN_JNI.class = a6e7edcf9b90a4f7a15273de580bf7faf35ac7f818a4345c9618fd75fea40f08  (24 910 B)
```

## 6. 变体 `ef29e00c…` 的登记复核（非交付、非复验对象）

按 captain 口径（"语义等价变体：仅 7 个次级 dex 分片不同、类描述符集合完全相同 26195/26195"）**独立复算**：
```
交付 30c41ac9 vs 变体 ef29e00c：dex 分片各 14
逐 dex sha256 不同的分片数 = 7 ：classes3 / classes5 / classes6 / classes9 / classes11 / classes12 / classes14
逐 dex sha256 相同的分片   = 7（**含 classes.dex 与 classes13.dex —— JNI 面所在的两个分片**）
类描述符集合：A=26195  B=26195 ；仅 A 有 0 ；仅 B 有 0 ⇒ 集合完全相同 = True
四 .so 载荷与 classes.dex/classes13.dex 均逐字节相同 ⇒ JNI/ELF 结论对两件同值
```
⇒ 登记口径成立；该件**不作为交付件**，仅作"整包不可复现"的实证（同输入、双钉 OK，整包 sha 不同）。

## 7. 未核项（不得读作通过）

1. **宿主侧 `/opt/apk-http/served/app-debug.apk`**：容器内不可见（`/opt/dsh-workspaces` 不可见），本容器无法核；
   若需闭环请在宿主执行 `sha256sum /opt/apk-http/served/app-debug.apk` 并附时刻。
2. **真机首调**：运行期 A/B 绑定与 `UnsatisfiedLinkError` 是否绝迹的**唯一决定性判据**，静态复验不能替代。
3. **单测未重跑**：本附录引用产物自带 XML（19:07:05）。若需现场重跑，命令：
   `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest`（须 uid 1000、且输出落工作区）。

## 8. 命令与原始输出（可复现）

工具链：容器内 `cpython3 3.11.9`（`webrtc-build/src/third_party/cpython3/host/bin/python3`）、
JDK `javap/jar 25.0.4.1`、NDK r26 `dexdump`/`llvm-nm`/`llvm-readelf`。`TMPDIR` 指向工作区（容器 `/tmp` 仅 256 M）。

```
python3  webrtc-build/t34/t34_verify.py  <apk> webrtc-build/t34/work      # §1–§4（APK/载荷/dex 分片/引用计数/XML）
dexdump  <work>/classes*.dex > <work>/classes*.dex.dexdump.txt           # 定义级输入
python3  webrtc-build/t34/t34_dexdefs.py <work>/<dex>.dexdump.txt         # §2 定义级表
bash     webrtc-build/t34/t34_elf_gates.sh                                # §3/§5（p_align/cmp/判据①②③④/类指纹）
python3  webrtc-build/t34/t34_variant_diff.py <交付apk> <变体apk> <wa> <wb> # §6 逐 dex + 类描述符集合
```
原始输出汇编：`webrtc-build/t34/verify.log`
（137 行；sha256 `a44260a22d4c40af2ccdd4a90f7661970d12d753984ac4c9d3ee273eb7a8c6aa`）
分项日志：`verify-raw.log` / `elf-gates.log` / `variant-diff.log` / `xml-meta.log`（同目录）。
脚本：`t34_verify.py` / `t34_dexdefs.py` / `t34_elf_gates.sh` / `t34_variant_diff.py`（同目录，各自 sha256 见 verify.log §N）。

## 9. 复现性备注（对终报的影响）

- **APK 整包不可复现**：同输入、T0/T1 双钉（jar `0c776934…` / AAR `8e8f2baf…`）均 OK，仍产出 `ef29e00c…` ≠ `30c41ac9…` ⇒
  终报应写「byte-reproducibility = **false**」，并把稳定判据落在**载荷**。
- **载荷钉集合 = 6 件**（`classes.dex` + **`classes13.dex`** + 四 `.so`；**一律从 APK 内解出后计算**，不要取
  `app/build/intermediates/**` —— `merged_native_libs/…` 与 `cxx/…/obj/…` 都是未剥离件 `115aa211…`，
  而 APK 内是 stripped 件 `95c44e5a…`）。下表为交付件与变体 `ef29e00c` 实测（逐件相同）：

| 载荷 | sha256（交付 `30c41ac9` = 变体 `ef29e00c`） |
|---|---|
| `classes.dex` | `a1b2ebdceec4f1fd11f78df7b22ca0133c50768c5f0e8dfee68429f63941028d` |
| `classes13.dex` | `a1f35bd51c0e5a30ceb2c3f453da59bdfa8054e56f3f761c79541d3f42a98a16` |
| `lib/arm64-v8a/libjingle_peerconnection_so.so` | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` |
| `lib/arm64-v8a/libc++_shared.so` | `c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36` |
| `lib/arm64-v8a/libwebrtcdemo_native.so` | `95c44e5ab9ff6f851e5e1de26b9d28810c09017264909424e64985b57f821bc0` |
| `lib/arm64-v8a/libandroidx.graphics.path.so` | `41e9a793c43a0f4fddb19e33f346bace464f30f888ba7b9eaf96294ea115bfb6` |

  **为何含 `classes13.dex`**：两个 JNI 关键类分居两个 dex —— `J/N` 在 `classes.dex`、`GEN_JNI` 在 `classes13.dex`；
  只钉前者会漏掉后者。**不要钉全部 14 个 dex**：其中 7 个（`classes3/5/6/9/11/12/14`）在两份构建间字节不同，会假红。
  `classes13.dex` 的现有一致性是**两次构建的数据点（经验性稳定）**，非证明。
- **mtime 不能作证据**：`app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` 曾被"内容不变地重写"至少两次
  （18:47:54、19:02:06，`mtime==ctime`，inode 与 third_party 副本不同）⇒ 门禁只认 sha。

## 10. v2 门禁复跑（落位后单一起源版，captain 裁定锚点 = `30c41ac9…`）

门禁文件：`webrtc-build/t30/logs/t34-gate-post-landing.md` = `c87bb1cd1d22a9ea5c0951d5c3a84e4efcf3f2b72cc841462b75457d2d755958`
（v1 `t34-gate.md` = `6d1112dd…` 仅作历史）。脚本：`webrtc-build/t34/t34_v2_gate.sh`；
原始输出：`webrtc-build/t34/v2-gate.log`（29 行 / sha256 `50d7d298a74cc5325c15712b932b79ec75e682695e12a76cca373f6d17440e99`）。

```
[v2-0] 钉死值
  jar  0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757  → OK
  AAR  8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099  → OK
  jar 条目数 = 509（期望 509）→ OK
[v2-1] 两个 class 指纹（v2 差异点）
  J/N.class      1ff8d3ff4032643339ad271f552475740d735dddf06ae42e507bb657f98a8932   6 924 B
  GEN_JNI.class  a6e7edcf9b90a4f7a15273de580bf7faf35ac7f818a4345c9618fd75fea40f08  24 910 B  → OK（v2：只认此值，其它值 STOP）
  J/ 前缀断言（jar 条目 ^J/ 计数，v2 期望 = 1）= 1  → OK（0 视为回归失败）
[v2-2] 判据 ①②③④（v1 口径不变）
  ① J.N static native = 193    ② GEN_JNI static native = 0
  ③ GEN_JNI public static 方法数 = 194（193 ⇒ 读到 A 件，立即报警）    ④ .so Java_J_N_* 导出 = 193
[v2-3] APK 护栏（被验 APK = 30c41ac9d3363cab249c9a1702958993fcfd965cf7ebbfeba5349435ab059be2）
  APK libjingle_peerconnection_so.so = 757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e  → OK
  APK libc++_shared.so              = c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36  → OK
  目录件 .so                        = 757cef81…（与 APK 内逐字节相同）
  AAR 内 classes.jar == 交付 jar（逐字节）= True
[v2-4] 双钉（以 v2 钉死值为期望对现值校验）
  sha256sum -c → 两项 OK（EXIT=0）
```
**v2 门禁结论：全项通过（无 STOP、无回归失败）** ⇒ 与本文 §1–§5 的产物级复验一致，**verdict 维持 `pass`**。
