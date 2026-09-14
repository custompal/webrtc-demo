# reports/17-vp9-rotation.md — 补齐 VP9 编码器旋转语义（t46）

- **任务**：t46（implementation，attempt 1，执行者 `native-dev`）。
- **目标**：按 doc/14 `:518`/`:635` 与 §6.2/§6.6，把「rotation 90/270 时交换尺寸」的契约**真正实现**，
  并同时把角度**烘进像素**（I420 旋转），使**对端**画面正立。
- **约束遵守**：未改 `third_party/**`、`doc/**`（冻结）、`scripts/**`、`*.jar/*.aar/*.so`；
  容器内**未跑 gradle**（无 JDK/SDK），但用**容器内已有的 NDK clang++** 完成了
  ①离线可执行自测 ②真实 aarch64 目标的语法检查（见 §5）。**真机远端朝向未验证**。

---

## 1. 现状取证（修复前）

- **本工程实现**（修复前）`app/src/main/cpp/encoder/vp9_encoder.cpp:419-426`：
  ```cpp
  if (frame.rotation_degrees != 0 && frame.rotation_degrees != 90 &&
      frame.rotation_degrees != 180 && frame.rotation_degrees != 270) {
    if (!rotation_warned_) { rotation_warned_ = true;
      NLOG_WARN(kTagEncoder, "encode_bad_rotation rot=%d treat_as=0", frame.rotation_degrees); }
  }
  if (frame_width != width_ || frame_height != height_) { ... cfg_.g_w/g_h = frame 尺寸 ... }
  ...
  image.planes[VPX_PLANE_Y] = const_cast<uint8_t*>(frame.y);   // 直接用原像素，未旋转
  ```
  ⇒ **只做校验 + WARN**：既不按 rotation 交换 `g_w`/`g_h`，也不旋转像素。
- **契约要求**：
  - `doc/14-interface-contract.md:518`：「`g_w` / `g_h` | InitEncode 传入（对齐到偶数；**rotation 90/270 时交换**）」；
  - `doc/14-interface-contract.md:635`：「**rotation**：`0|90|180|270`（度）；`VideoFrame.getRotation()` **直接映射**，非法值按 0 处理并记 WARN」；
  - `doc/14:489`（§6.2）：`nativeEncode(..., rotationDegrees(frame.rotation), keyFrame)` —— 取值链路冻结；
  - `doc/14:602`（§6.6）：`nativeEncode` 的 JNI 签名与 direct ByteBuffer 前提冻结。
- **t39 的关系**：t39 让 `FrameNormalizer` **保留 `frame.rotation`**（`webrtc/FrameNormalizer.kt:82`），
  本地预览朝向已在真机确认修正；但**编码侧**当时没有任何旋转处理 ⇒ rotation 元数据虽已到达编码器，
  却只被校验后丢弃 ⇒ **对端解出的画面未旋转/错乱**。本任务补的正是这最后一段。

---

## 2. 判据：为什么"只交换尺寸"不够

1. **VP9 码流不携带 CVO/rotation 元数据**（VP9 的 RTP payload/位流里没有旋转字段；本工程也未启用任何
   rotation 侧信道）⇒ 远端解码器**无从得知**该把画面转多少度。因此角度必须在**发送侧烘进像素**。
2. **只把 `g_w`/`g_h` 交换而不旋转像素**：码流尺寸变成 90/270 的"竖屏"尺寸，但内容是未旋转的
   "横屏"像素 ⇒ 远端看到的是**被拉伸/错乱**的图像（不是正立，也不是原样）。
3. 因此正确语义 = **顺时针旋转 pixel + 同步交换尺寸**：
   - `R=0`：原样直通（**零拷贝**，保持既有行为与 direct ByteBuffer 前提）；
   - `R=90`：4x2 → 2x4，顺时针 90°；
   - `R=180`：尺寸不变，内容 180°；
   - `R=270`：4x2 → 2x4，顺时针 270°；
   - **非法值（非 0/90/180/270）按 0 处理并 WARN 一次**（doc/14:635）。
4. 与 `org.webrtc` 语义一致：`VideoFrame.getRotation()` 表示"为显示正立需顺时针旋转的角度"，
   `VideoFrameDrawer` 用 `renderMatrix.preRotate(frame.getRotation())` 应用；编码端按同一角度顺时针烘入即可。

---

## 3. 方案与取舍

| 决策 | 选择 | 理由 |
|---|---|---|
| 实现位置 | **`app/src/main/cpp/encoder/i420_rotator.h`（header-only 纯 C++）** | 不改 `CMakeLists.txt`（不在本任务 in-scope）、不新增被 App 编译的翻译单元，零构建系统风险；同时可在宿主离线编译执行 |
| 接入口 | `Vp9Encoder::Encode()` 内、**尺寸决策与 `vpx_img_wrap` 之前** | 一次性覆盖"尺寸交换 + 像素旋转"，`SetRates`/关键帧/取帧路径完全不动 |
| `g_w/g_h` 交换路径 | 复用既有 `vpx_codec_enc_config_set` 动态改尺寸路径（把输入尺寸换成**旋转后**尺寸） | doc/14:518 写的是"InitEncode 传入时交换"，但 rotation 可随帧变化（切摄像头/设备旋转），本工程既有的单通+低延迟 `config_set` 路径可安全动态改 `g_w/g_h`（`vp9_cx_iface.c:878`），行为等价且更稳 |
| 缓冲区 | 成员 `rotate_buf_`（`std::vector<uint8_t>`，按旋转后尺寸单调增长复用；`Release()` 释放） | 避免逐帧分配；挂断即归还内存 |
| 越界防御 | `RotatedTotalSize > kMaxRotateBufferBytes（64 MiB）` ⇒ `kVp9ErrSize` + ERROR 日志 | 尺寸异常时快速失败，不尝试分配 |
| 异常策略 | 不抛异常（`-fno-exceptions`）；分配失败即 abort（与既有 `encoded_.assign` 同策略） | 与工程既有约定一致（§5.4） |
| 未采用 | 不引入 CVO / RTP rotation 扩展、不改 SDP | 契约冻结"禁止手改 SDP 文本"，且远端需同步改造 |

**改动清单（diff 摘要）**
```
app/src/main/cpp/encoder/i420_rotator.h              新增（header-only：NormalizeRotationDegrees / IsQuarterTurn /
                                                    RotatedWidth|Height|YSize|UvSize|TotalSize / RotateI420）
app/src/main/cpp/encoder/i420_rotator_host_test.cpp  新增（freestanding 离线自测，默认惰性：#if defined(I420_ROTATOR_HOST_TEST)）
app/src/main/cpp/encoder/vp9_encoder.cpp             +约 95 行：旋转规范化/尺寸交换/scratch 旋转/日志 encoder_rotate；
                                                    Release() 释放 rotate_buf_；新增 kMaxRotateBufferBytes；include rotator
app/src/main/cpp/encoder/vp9_encoder.h               +成员 rotate_buf_；I420Frame.rotation_degrees 注释更新
app/src/main/kotlin/.../encoder/Vp9VideoEncoder.kt   仅注释：说明 EncodedImage.setRotation(0) 在"像素已旋转"前提下语义正确
```
**既有约束保持（逐条）**：`nativeEncode` JNI 签名/参数顺序未动（`doc/14:602` 逐字不变）；
direct ByteBuffer 前提不变（rotation=0 路径仍零拷贝借用原平面，rotation≠0 路径只读入参、写自持缓冲）；
`ERR_PARAMETER` 语义不变（入参校验段未改；仅**新增** `kVp9ErrSize` 用于尺寸异常）；
层码率分配（`SetRates`/`ApplyLayerRatesLocked`）与关键帧请求（`request_key_frame`/`force_key_frame_`）零改动；
`rotationDegrees(frame.rotation)` 链路 `Vp9VideoEncoder.kt:208 → jni/vp9_encoder_jni.cpp:115/123/165 → Vp9Encoder::Encode` 逐字对齐 `doc/14:489`。

---

## 4. 关键实现（现状 file:line）

- `app/src/main/cpp/encoder/i420_rotator.h:60-96`：`NormalizeRotationDegrees` / `IsQuarterTurn` / `Rotated*` 尺寸族；
- `app/src/main/cpp/encoder/i420_rotator.h:100-140`：`RotatePlane`（90：`src=(y', h-1-x')`；180：`(w-1-x', h-1-y')`；270：`(w-1-y', x')`）；
- `app/src/main/cpp/encoder/i420_rotator.h:145-160`：`RotateI420`（Y + 两个色度平面，色度按 2x2 对齐）；
- `app/src/main/cpp/encoder/vp9_encoder.cpp:425-436`：旋转规范化 + 非法值 WARN + `target_width/height`（旋转后尺寸）；
- `app/src/main/cpp/encoder/vp9_encoder.cpp:438-455`：用旋转后尺寸走既有 `config_set` 路径（doc/14:518 生效点）；
- `app/src/main/cpp/encoder/vp9_encoder.cpp:457-520`：`rotation!=0` ⇒ 分配/复用 `rotate_buf_` 并 `RotateI420`，随后 `vpx_img_wrap` 指向旋转缓冲；日志 `encoder_rotate rot=… in=…x… out=…x…`；
- `app/src/main/cpp/encoder/vp9_encoder.cpp:393-401`：`Release()` 释放 `rotate_buf_` 并复位 `rotation_warned_`。

---

## 5. 验证（可复现命令与结果）

### 5.1 离线自测（**容器内真实执行**，freestanding + NDK clang 的 host target）
```bash
NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
cd code/webrtc-demo
$NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
  -fno-exceptions -fno-rtti -std=c++17 -DI420_ROTATOR_HOST_TEST -DI420_ROTATOR_FREESTANDING \
  -I app/src/main/cpp app/src/main/cpp/encoder/i420_rotator_host_test.cpp \
  -o <工作区>/t46-work/i420_rotator_test -Wl,-e,_start -Wl,--build-id=none
<工作区>/t46-work/i420_rotator_test ; echo "exit=$?"
```
**结果（原文摘录）**：
```
  OK   normalize(0/90/180/270) = 0/90/180/270 ； normalize(45)=0 ； normalize(-90)=0 ； normalize(360)=0
  OK   quarter(0)=0  quarter(90)=1  quarter(180)=0  quarter(270)=1
[rotation=0]   4x2 -> 4x2   rotated_y_size=8  rotated_uv_size=2  rotated_total_size=12   （Y/U/V 逐字节 = 源）
[rotation=90]  4x2 -> 2x4   Y = {20,10,21,11,22,12,23,13}（左列自下而上成为首行）
[rotation=180] 4x2 -> 4x2   Y = {23,22,21,20,13,12,11,10}
[rotation=270] 4x2 -> 2x4   Y = {13,23,12,22,11,21,10,20}（右列自下而上成为首行）
== result: failures=0 ==   exit=0
```
自测同时覆盖：**带 padding 的 stride**（源 `stride=8/4`，目标 Y `stride=rot_w+4`、色度 `stride=rot_w/2+2`）
⇒ 只读写有效像素，**目标 padding 保持填充值（0x77）未被越界写**。
> 过程留痕：首版夹具把 4x2 的 Y 误写成"连续 8 字节"（未按 stride=8 分行），自测立刻报 16 项 FAIL；
> 修正夹具后 0 失败 —— 说明该自测**确实能抓到布局错误**（不是空跑）。

### 5.2 真实目标工具链语法检查（仅编译，不链接）
```bash
$NDK/clang++ --target=aarch64-linux-android26 -std=c++17 -fno-exceptions -fno-rtti \
  -I app/src/main/cpp -I third_party/libvpx/include \
  -fsyntax-only app/src/main/cpp/encoder/vp9_encoder.cpp      # exit=0
$NDK/clang++ --target=aarch64-linux-android26 -std=c++17 -fno-exceptions -fno-rtti \
  -I app/src/main/cpp -I third_party/libvpx/include \
  -fsyntax-only app/src/main/cpp/jni/vp9_encoder_jni.cpp      # exit=0（调用方未被破坏）
```

### 5.3 契约 verify（本任务三条）
```bash
cd code/webrtc-demo && grep -n 'rotation\|swap\|g_w\|g_h' app/src/main/cpp/encoder/vp9_encoder.cpp | sed -n '1,40p'
cd code/webrtc-demo && grep -rn 'normalizeRotation\|rotationDegrees' app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt | head -10
cd code/webrtc-demo && ls -l reports/17-vp9-rotation.md && git status --porcelain
```

---

## 6. 未验证项（**不得读作已通过**）

1. **真机对端画面是否正立**：需用户复测（本机 `rot=270`）；本地预览已由 t39 验证，**编码侧效果未经真机验证**。
2. 端到端链路：`pc_connection_state=CONNECTED` 后远端首帧的朝向/宽高（依赖 t44 的连通性结论与用户复测）。
3. 宿主机构建与单测：本任务未新增 Kotlin 单测（旋转逻辑在 native 头文件内，已由 §5.1 的离线可执行自测覆盖）；
   **native 库需由后续宿主构建任务重编**（`libwebrtcdemo_native.so` 变更 ⇒ APK 载荷钉集会变，属新锚点）。
4. 性能：逐帧 I420 旋转的 CPU 成本未在真机测量（1080p 旋转约等于一次全帧 memcpy 级操作；`rotate_buf_` 复用、无逐帧分配）。
5. 切换摄像头/设备旋转导致 rotation 变化的动态路径未真机验证（代码按帧取 `frame.rotation`，理论支持）。
