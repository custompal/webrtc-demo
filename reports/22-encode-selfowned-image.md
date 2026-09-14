# t50b 修复报告：编码器仍崩 → 改为「拷进 libvpx 自持图像」

> 归属：**captain**（native-dev 连续遭遇平台级不可恢复 turn 失败）。
> 前置：t50（`5cdfa2e`）把"尺寸变化改尺寸"从 `vpx_codec_enc_config_set` 换成
> `destroy + vpx_codec_enc_init` **未能消除崩溃**。

## 1. 新版本真机证据（设备 Xiaomi 24117RK2CC / Android 16；解包于宿主 `/opt/dsh-workspaces/tmp/dl-c/x/`）

```
16:53:17.949 native encoder encoder_init w=640 h=480 s=1 t=3 cpu=8 start_bps=300000
16:53:18.776 native jni  jni_call method=nativeEncode … w=640 h=480 sy=640 su=640 sv=640 rot=270 key=1
16:53:18.785 native encoder encoder_reinit w=480 h=640 old_w=640 old_h=480 reason=size_change_rotation_or_adapt
16:53:18.796 native encoder encode_vpx_begin frame=1 pts_us=… flags=1 w=480 h=640 sy=480 su=240 sv=240 img_owner=0
                 ← 该进程再无日志
16:53:24.822 新进程（12258）app_create / native_lib_loaded = App 再次闪退
计数：encoder_reinit=1 / encode_vpx_begin=5 / **encode_vpx_done=0** / encoded_frame=0
```

⇒ t50 的"重建编解码上下文"确实执行了（`encoder_reinit` 已打印），但 **`vpx_codec_encode()` 仍然崩溃**。
说明崩溃根因不是"编解码器尺寸未重建"，而是**我们把外部缓冲/任意 stride 的图像直接交给 libvpx 这件事本身**。

## 2. 复查到的关键差异

- SDK 传来的 I420 平面布局是 `sy=640 su=640 sv=640`（chroma stride = luma 宽度，非常规）；
- t48 的"修掉每帧泄漏"把 `vpx_img_wrap(..., img_data=nullptr)` 改成传外部 Y 指针，
  于是图像变成 **`img_data_owner=0` 的外部缓冲 + 我们自填的 planes/stride**；
- libwebrtc 自己的 `LibvpxVp9Encoder` **从不**把调用方缓冲直接交给 libvpx：它维护一张
  自持图像（`vpx_img_wrap(nullptr…)`，`img_data_owner=1`）并把 I420 **逐行拷贝**进去再编码。

## 3. 本次修复（t50b）

`app/src/main/cpp/encoder/vp9_encoder.{h,cpp}`：

1. 新增自持图像 `vpx_image_t* raw_img_` + `EnsureRawImageLocked()`：
   `vpx_img_wrap(nullptr, VPX_IMG_FMT_I420, width_, height_, 1, nullptr)`（**结构与缓冲都由
   libvpx 分配，`img_data_owner=1`**）；尺寸不变时复用，尺寸变化/`Release()` 时
   `vpx_img_free`（`DestroyCodecLocked()` 内统一归还，**不泄漏**，与 t48 的诉求一致）。
2. 每帧把（可能已旋转的）源平面**逐行拷贝**进自持图像：新增文件内 helper
   `CopyPlaneIntoImage(src, src_stride, dst, dst_stride, w, h)`（stride 相同则整块 memcpy）。
3. `vpx_codec_encode(&codec_, raw_img_, …)` 改用自持图像；`encode_vpx_begin` 日志改打印
   自持图像的 stride 与 `img_owner`（预期 `sy=480 su=240 sv=240 owner=1`）。
4. 保留 t50 的 `destroy + enc_init` 重建路径与 t46 的像素旋转、t48 的取证标记。

## 4. 验证

- **宿主 NDK clang 真实目标语法检查**：`clang++ --target=aarch64-linux-android26 -std=c++17
  -fsyntax-only -Iapp/src/main/cpp -Ithird_party/libvpx/include app/src/main/cpp/encoder/vp9_encoder.cpp`
  → **EXIT=0**。
- 无残留 `image` 引用（`grep -n '\bimage\b' vp9_encoder.cpp` 为空）。

## 5. 真机判据（下一轮复测）

- U1：`native.log` 出现 `encoder_raw_img_alloc w=480 h=640 sy=480 su=240 sv=240 owner=1`；
- U2：出现 **`encode_vpx_done`**（`err=0`）与 **`encoded_frame`**，`encoder_bitrate.csv` 的
  `encoded_bytes > 0`；
- U3：通话 ≥10 秒内 `app.log` 不出现新的 `native_lib_loaded`/`main_activity_create` 重启序列；
- U4：对端出现画面。

## 6. 未验证项

U1–U4 全部待真机；此外若本方案仍崩，则下一轮应改在**宿主侧复现**（用 `third_party/libvpx-src`
构建 x86_64 libvpx + 同一 `Vp9Encoder` 代码的 host harness，用 gdb/ASAN 直接定位），
不再依赖真机黑盒排查。
