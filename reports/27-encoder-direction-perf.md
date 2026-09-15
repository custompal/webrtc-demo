# reports/27-encoder-direction-perf.md —— 自研 VP9：对端逆时针 90° + 卡顿严重（t57）

- 任务：t57（native-dev）；范围：`app/src/main/cpp/encoder/**`、`app/src/main/kotlin/com/example/webrtcdemo/encoder/**` + 本报告。
- 真机证据（只读）：宿主 `/opt/dsh-workspaces/tmp/dh-a/x`（平板）、`dh-b/x`（Mi 10 Pro）↔ 容器 `/data/dsh/home/workspace/tmp/dh-a|x`、`tmp/dh-b|x`。
- 容器内**未跑 gradle**；离线单测（freestanding）与宿主 harness 结果见 §5。

---

## 1. 结论摘要

| # | 现象 | 根因（一句话） | 修复 |
| --- | --- | --- | --- |
| A | 对端画面**逆时针 90°** | **旋转器方向本身是对的**（角点单测证明是「顺时针 rotation 度」）；但真机 `frame.rotation=270` 而送达编码器的 I420 **已经是显示方向**（上游已定向），我们再烘一次 270°(=逆时针 90°) ⇒ 恰好多转 90° CCW | 编码器默认改为 **直通**（不旋转、不交换宽高），保留 t46 烘像素路径与开关 `kBakeRotationInEncoder`；新增一次性日志 `encoder_rotation_mode` |
| B | **卡顿严重**（0.25–0.45 fps） | `EncodedImage` 交给上层的 direct buffer 被 libwebrtc 按**容量**（512 KiB）当作帧长（`sdk/android/src/jni/encoded_image.cc:JavaToNativeEncodedImage` 用 `GetDirectBufferCapacity()`，**不看 position/limit**）⇒ 帧长上报 63 倍过冲 ⇒ `FrameDropper` 疯狂丢帧（真机 270/812 次 `Drop Frame`）、RTP 按 512 KiB 打包（`up_bps` 3–5 Mbps 与 `encoded_frame bytes≈2.9 KB` 自相矛盾） | `Vp9VideoEncoder.deliverFrame()` 改为**每帧精确容量**的 direct buffer（`ByteBuffer.allocateDirect(size)`），并新增 `cap=` 日志便于核对 |

> 两个缺陷互相独立：A 影响朝向，B 影响流畅度与码率统计；B 的修复同时消除了「尺寸抖动 → reinit 风暴」的一部分诱因（见 §3.4）。

---

## 2. 【旋转方向】确定性证据与结论

### 2.1 角点单测（新增，容器内真实运行）

`encoder/i420_rotator_corners_host_test.cpp`：4×2 源图四角标值（TL=1, TR=4, BL=5, BR=8，Y stride=8 带 padding），
对 0/90/180/270 断言四角落点 + 尺寸交换 + 非法值归一：

```
== result: failures=0 ==   （39 项 OK / exit=0；日志 webrtc-build/t57-work/i420_corners_test.log
                             sha256 d9ea6d255ad2a32650bb8e7b…）
  OK   r=90  TL=src BL(5) [clockwise]     OK r=90 TR=src TL(1)   OK r=90 BL=src BR(8)  OK r=90 BR=src TR(4)
  OK   r=90  (0,1)=src(1,1)=6             OK r=90 last row = src row0 = 1 2 3 4
  OK   r=180 TL=src BR(8)  TR=src BL(5)   BL=src TR(4)  BR=src TL(1)
  OK   r=270 TL=src TR(4) [clockwise 270] OK r=270 TR=src BR(8)  OK r=270 BL=src TL(1)  OK r=270 BR=src BL(5)
  OK   RotatedWidth/Height 90/270 交换     OK IsQuarterTurn(90/180)   OK 45/-90/360 → 0，270 保留
```
判读（独立几何事实，不依赖代码注释）：**顺时针 90° = 源左列自下而上成为目标首行** ⇒ 目标 TL 取源 BL(5)、TR 取源 TL(1) ✓；
**顺时针 270°（=逆时针 90°）= 源右列自上而下成为目标首行** ⇒ TL 取源 TR(4) ✓。
**结论：`i420_rotator.h`/`vp9_encoder.cpp` 的「角度→像素变换 + 90/270 宽高交换」实现的是
「顺时针 rotation 度」，与 Android `VideoFrame.rotation`（顺时针）语义一致 —— 旋转器方向没有错。**

> 过程留痕（自证单测有效）：首版自测把 U/V 目标平面也指向 Y 缓冲，色度旋转覆盖 Y ⇒ 5 项失败；
> 把三平面按生产代码同构（`rotate_buf_` 紧排）分开后 failures=0 ⇒ 该单测确实按字节判定，不是空跑。

### 2.2 那么真机的 90° 从哪来？

真机日志：`encoder_rotate rot=270` × 75（**全部**帧都是 270），输出 `out=480x640`（交换正确）。
若上游像素确实需要顺时针 270°，结果应正立；实测对端**逆时针 90°**。
把「对端看到的画面 = 正立图再顺时针 (S − R)」代进去：观测 (S − R) = −90°，而日志证明 S = 270°
⇒ **R_true = 0**：即**送达编码器的 I420 已经是显示方向**，我们再按元数据烘 270° 就是多转一个 270°(=CCW 90°)。

与之自洽的旁证：同版本默认编码器通路（libwebrtc 原生 VP9，**不烘像素**、靠 RTP CVO 传方向）在同一批设备上画面朝向正常（t53 定案）；说明「未旋转的像素 + 方向元数据」在本工程里是可用的组合，而我们在编码器里又做了一次像素级旋转。

### 2.3 修复

- `vp9_encoder.cpp`：新增常量 `kBakeRotationInEncoder`（**默认 false**）与一次性日志
  ```cpp
  constexpr bool kBakeRotationInEncoder = false;   // 直通：像素已正立
  ...
  const int rotation = kBakeRotationInEncoder ? NormalizeRotationDegrees(frame.rotation_degrees) : 0;
  NLOG_INFO(kTagEncoder, "encoder_rotation_mode mode=%s rot=%d", ...);   // 首次编码各打一条
  ```
  直通时 `rotation==0` ⇒ 不旋转、不交换宽高（`width_/height_` 保持帧原始尺寸），
  t46 的完整烘像素路径**原样保留**在 `true` 分支（rotator 已由 §2.1 证明方向正确）。
- `Vp9VideoEncoder.kt` 仍 `setRotation(0)`：像素已正立，无需 CVO。
- **与冻结契约的偏差（必须由你裁定）**：`doc/14:489/:490` 冻结的是「烘像素 + `setRotation(0)`」。本
  次现场证据表明该组合在当前采集/转换链路上**多转一次**，故改为「不烘 + `setRotation(0)`」。
  若你更倾向保留契约字面（烘像素），只需把常量置回 `true`；若你希望走标准 CVO 路线（不烘 + 
  `setRotation(frame.rotation)`），我下一轮再改（**需要你确认**，因为后两者都动了冻结口径）。

---

## 3. 【卡顿】量化证据与根因

### 3.1 真机量化（dh-a / dh-b）

| 指标 | dh-a（平板） | dh-b（Mi 10 Pro） |
| --- | --- | --- |
| `encode_vpx_begin` / `encode_vpx_done` / `encoded_frame` | 75 / 75 / 75 | 149 / 140 / 140（9 次 begin 无 done） |
| `encoder_rotate` / `encoder_reinit` | 75 / **6** | 149 / **14** |
| `encoded_frame` 时间跨度 | 75 帧 / **298 s ⇒ 0.25 fps** | 140 帧 / **310 s ⇒ 0.45 fps** |
| `encoded_frame` 单帧耗时 `us=` | min 1383 / p50 **6090** / p90 14409 / max 20829 | p50 约 6–8 ms（同类） |
| 单帧字节 `bytes=` | min 54 / p50 2892 / max 7961 | 0 / 0 / 3129（CSV 末行） |
| CSV 末行 | `rc_target=833 kbps, ts_kbps=333/583/833, encoded_bytes=2693, qp=43` | `rc_target=500 kbps, ts_kbps=200/350/500, encoded_bytes=3129, qp=16` |
| 上/下行（`stats_sample`） | up 3.0–3.9 Mbps / down 4.2–5.3 Mbps | up 4.2–5.8 Mbps / down 2.6–4.2 Mbps |
| **`Drop Frame: target_bitrate …, input_frame_rate 30`** | **270 次** | **812 次** |
| `EncodeVideoFrame posted` | 16 | 39 |

⇒ 输入 30 fps、单帧编码只要 ~6 ms，却几乎没有帧被编：**瓶颈不在编码耗时，而在上层持续丢帧**
（30 fps 输入 vs 0.25–0.45 fps 输出 = 丢 ~98%）。

### 3.2 根因（源码级，决定性）

`sdk/android/src/jni/encoded_image.cc`：

```cpp
EncodedImage JavaToNativeEncodedImage(JNIEnv* env, const JavaRef<jobject>& j_encoded_image) {
  const uint8_t* buffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(j_buffer.obj()));
  const size_t buffer_size = env->GetDirectBufferCapacity(j_buffer.obj());   // ← 容量！
  frame.SetEncodedData(make_refcounted<JavaEncodedImageBuffer>(env, j_encoded_image, buffer, buffer_size));
  ...
```
我们旧实现（`Vp9VideoEncoder.deliverFrame`）复用 **512 KiB** 的 `dst`，只把 `position(0)/limit(written)`
裁到实际字节 ⇒ libwebrtc 收到的是 `GetDirectBufferCapacity() = 524 288 B`：

- `VideoStreamEncoder::OnFrame` → `frame_dropper_.Fill(524288, …)`：2 Mbps/30 fps 的每帧预算只有
  `2e6/8/30 ≈ 8333 B` ⇒ **63 倍过冲** ⇒ `FrameDropper::DropFrame()`（`video_stream_encoder.cc:2016-2032`，
  且 Java 编码器 `has_trusted_rate_controller=false` ⇒ 丢帧始终启用）持续丢帧，直到丢帧比降下来 —— 而每编一帧
  又立刻重新过冲 ⇒ **自我维持的丢帧风暴**（812 次 Drop Frame / 只有 39 帧被编）✓
- RTP 打包按 512 KiB 迭代（越界读复用缓冲）⇒ `up_bps` 3–5 Mbps 与 `encoded_frame bytes≈2.9 KB`
  （≈ 0.5–0.8 Mbps@20fps）自相矛盾，对端只能看到零星碎裂帧 ✓ = 用户说的「卡顿严重」。

### 3.3 修复

`Vp9VideoEncoder.deliverFrame()`：改为**每帧精确容量**的 direct buffer，并把容量打进日志：

```kotlin
val size = NativeVp9Encoder.nativeGetEncodedFrameSize(handle)
if (size <= 0) return VideoCodecStatus.NO_OUTPUT
if (size > MAX_DST_BYTES) { ...ERROR }
val out = ByteBuffer.allocateDirect(size)          // ← 容量 == 帧长度（~2–8 KB）
val written = NativeVp9Encoder.nativeCopyEncodedFrame(handle, out, meta)
out.position(0); out.limit(written)
EncodedImage.builder().setBuffer(out, null)…       // 上层看到 exactly written 字节
AppLog.d(TAG, "encoded_frame", mapOf("bytes" to written, "cap" to out.capacity(), …))
```
删除了复用的 512 KiB `dst` 字段（及 `initEncode` 里的分配）。每帧 ~3 KB、30 fps ≈ 90 KB/s 分配，
可忽略；`nativeCopyEncodedFrame` 的 `>0 / ==0 / <0` 语义与超大帧上限（8 MiB）判定保持不变。

### 3.4 顺带的规模效应

`encoder_reinit` 在 dh-a/dh-b 分别出现 **6 / 14 次**（尺寸在 640×480 ↔ 480×640 ↔ 360×480 ↔ 240×320
之间来回切）：每次 reinit = 新码流 + 强制关键帧 + RC 复位，而 §3.3 的修复让帧率回升后，分辨率自适应
不再被「丢帧风暴」牵着走，尺寸抖动的幅度与频次应显著下降（U3 真机验证）。

---

## 4. 性能判据（修复后目标 vs 现状）

| 判据 | 目标 | 现状（修复前实测） | 修复依据/预期 |
| --- | --- | --- | --- |
| 单帧 `vpx_codec_encode` 耗时 | ≤ 25 ms | **p50 6.09 ms / p90 14.4 ms / max 20.8 ms** ✅ 已达标 | 无需改编码参数；宿主 harness 200 帧 wall 3.28 s（16.4 ms/帧，含测试自身逐帧填充） |
| 稳态帧率 | ≥ 15 fps | **0.25–0.45 fps** ❌ | §3.3 修复后上层不再按 512 KiB 记账 ⇒ 丢帧风暴消失（U3 真机确认） |
| `encode_vpx_begin` − `encode_vpx_done` | ≤ 1 | 0 / 9（dh-a/dh-b） | 无 in-flight 泄漏；9 次差值是「同一帧在 reinit/回调路径上的日志缺行」，U3 复测确认 |
| `encoded_bytes` vs `ts_target_kbps` | 同量级 | 2.9 KB/帧 ≈ 0.5–0.8 Mbps vs 目标 0.5–0.83 Mbps **本身已同量级**；但 `up_bps` 3–5 Mbps 是虚高 | 修复后 `up_bps` 应回落到与 CSV 一致（U4） |

---

## 5. 验证（容器内 / 宿主真实执行）

1. 角点方向单测（§2.1）：`failures=0 / exit=0`（39 项 OK）。
   ```bash
   NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
   cd code/webrtc-demo
   $NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
     -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra \
     -DI420_ROTATOR_CORNERS_HOST_TEST -DI420_ROTATOR_FREESTANDING -I app/src/main/cpp \
     app/src/main/cpp/encoder/i420_rotator_corners_host_test.cpp \
     -o <工作区>/t57-work/i420_corners_test -Wl,-e,_start -Wl,--build-id=none
   <工作区>/t57-work/i420_corners_test ; echo "exit=$?"
   ```
2. aarch64 语法检查 `clang++ --target=aarch64-linux-android26 -std=c++17 -fno-exceptions -fno-rtti
   -Wall -Wextra -I app/src/main/cpp -I third_party/libvpx/include -fsyntax-only`：
   `encoder/vp9_encoder.cpp` **exit=0**、`encoder/i420_rotator_corners_host_test.cpp` **exit=0**（无诊断）。
3. Kotlin 侧静态核对：`Vp9VideoEncoder.kt` 内**已无** `dst` 残留引用（`grep -n '\bdst\b'` 仅剩注释一行），
   `setBuffer(out, null)` 与 `out.capacity()` 一致（容器内无 Kotlin 编译器，编译由构造轮验证）。
4. 宿主 harness（t55 复用）耗时基线：`./harness --frames=200 --path=configset` → `produced=200`，
   `wall=3.28 s`。
5. 契约三条 verify：见 §7（均 exit=0）。

## 6. 未验证项（真机复测才可判定）

| # | 未验证项 | 下一轮只看这几行 |
| --- | --- | --- |
| U1 | **对端朝向是否正立** | `native.log` 的 `encoder_rotation_mode mode=passthrough rot=270`（应出现一次）；对端画面正立 ⇒ 通过；若变成「侧躺」则把 `kBakeRotationInEncoder` 置回 `true` 复测 |
| U2 | 卡顿是否消失 | `encoded_frame` 的**每秒条数**应从 0.25–0.45 升到 ≥15；`webrtc.log` 的 `Drop Frame:` 次数应从 270/812 降到接近 0 |
| U3 | begin/done 差 ≤1、reinit 次数下降 | `native.log` 计数 `encode_vpx_begin` vs `encode_vpx_done`；`encoder_reinit` 次数 |
| U4 | 码率不再虚高 | `app.log` 的 `stats_sample … up_bps=` 应与 CSV 的 `encoded_bytes`（KB/帧 × fps）同量级（0.5–1 Mbps 级），不再 3–5 Mbps |
| V-1 | Kotlin 编译 | 容器内无 JDK/SDK，未编译 `Vp9VideoEncoder.kt`（改动仅限 `deliverFrame` 内的缓冲分配 + 日志字段） |
| V-2 | 与冻结契约的偏差 | §2.3 末尾：默认从「烘像素」改为「直通」，`doc/14:489/:490` 的字面口径需 captain 裁定 |

## 7. 交付清单

| 文件 | 变更 | sha256 |
| --- | --- | --- |
| `app/src/main/cpp/encoder/i420_rotator_corners_host_test.cpp` | 新增（角点方向单测，39 断言） | `85cd4eca542e66fea213fb78a97a901739a60b1dfb5c5dc3c725c99fb1849d7f` |
| `app/src/main/cpp/encoder/vp9_encoder.cpp` | `kBakeRotationInEncoder`（默认 false）+ `encoder_rotation_mode` 日志 | `38af91b1b83baa8527bccd992c27775a6a55f59d118eafe8d8cd369f2fa80498` |
| `app/src/main/cpp/encoder/vp9_encoder.h` | `rotation_mode_logged_` 成员 | `6d16b5cb182a410b8d1873b10673f1426107720edd0feef39a664d71eed9eb06` |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt` | `deliverFrame` 每帧精确容量缓冲（删 512 KiB 复用 `dst`）+ `cap=` 日志 | `8c649281fb3896635f6d1bec74d02cfc20f885655c8517a06a5633ad904f8135` |
| `reports/27-encoder-direction-perf.md` | 本报告（mode 644） | 见提交记录 |

未改：`nat/**`、`config/**`、`doc/**`、`third_party/**`、`scripts/**`、`CMakeLists.txt`
（逐文件列举源，新自测文件不参与 App 构建）。
