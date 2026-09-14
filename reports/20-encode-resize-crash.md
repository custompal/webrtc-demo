# t50 修复报告：编码器尺寸变化导致的 `vpx_codec_encode` 崩溃（App 闪退）

> 归属：**captain 接管**（原派 native-dev，其 claim 后立即遭遇平台级不可恢复 turn 失败
> `session event "turn/end" carries non-JSON-serializable data`）。修复对象 = t46 引入的
> 「按 rotation 交换编码尺寸」路径与 t48 之后的编码器状态。

## 1. 真机证据（第一手；设备 Xiaomi 24117RK2CC / Android 16 / host；日志解包于宿主 `/opt/dsh-workspaces/tmp/dl-a/x/`）

```
16:24:15.546 pc_connection_state dtls=false state=CONNECTING
16:24:15.591 selected_candidate_pair mode=RELAY local=relay 47.238.144.66:49178 remote=relay 47.238.144.66:49156
16:24:15.668 pc_connection_state dtls=true  state=CONNECTED       ← 连接已建立（中继对 + DTLS 完成）
16:24:15.673 pc_ice_connection_state state=CONNECTED
16:24:15.685 setrates fps=20 s=5 t=4 total_bps=235947              ← t48 的 5×4→3×3 折叠已生效（不再 bad_dim）
16:24:15.686 nativeEncode (首帧, rot=270)
16:24:15.691 encoder_resize w=480 h=640                            ← vpx_codec_enc_config_set 返回 OK
16:24:15.698 encode_vpx_begin frame=1 pts_us=… flags=1 w=480 h=640 sy=480 su=240 sv=240 img_owner=0
                 ← 此后该进程**再无任何日志**
16:24:18.556 新进程启动（native_lib_loaded → main_activity_create）= App 闪退重启
```

同型崩溃在 `16:23:50.410–.426`（进程 `19748` → `20747`）已发生过一次。全程计数：
**`encode_vpx_begin` = 2 / `encode_vpx_done` = 0 / `encode_no_packet` = 0 / `encoded_frame` = 0**
⇒ 崩溃点 100% 落在 **`vpx_codec_encode()` 内部**（进入标记已打、返回标记从未打）。

## 2. 根因

t46 为满足 `doc/14:518`「rotation 90/270 时交换编码尺寸」，在 `Encode()` 里用
`vpx_codec_enc_config_set()` 把 `cfg_.g_w/g_h` 从 Init 时的 `640×480` 改成旋转后的 `480×640`
（代码注释据此声称 `vp9_cx_iface.c:878` 在「单通 + 低延迟」下允许改尺寸）。

真机证据与之矛盾：**`config_set` 返回 OK（本工程 `encoder_resize` 日志已打出），但紧随其后的首帧
`vpx_codec_encode()` 直接导致进程死亡**。合理解释：该路径下 libvpx 的内部帧缓冲/图像尺寸不会随
`g_w/g_h` 重建，于是出现「图像 480×640 vs 编解码器内部 640×480」的尺寸不一致 ⇒ 越界访问。

## 3. 修复（最小、可判定）

`app/src/main/cpp/encoder/vp9_encoder.cpp` 的 `Encode()`：尺寸变化时**重建编解码上下文**，
不再使用 `vpx_codec_enc_config_set` 改尺寸：

```cpp
if (target_width != width_ || target_height != height_) {
  const int old_w = width_, old_h = height_;
  width_ = target_width; height_ = target_height;
  DestroyCodecLocked();                       // vpx_codec_destroy + 清句柄（幂等）
  cfg_.g_w = width_; cfg_.g_h = height_;      // cfg_ 已含冻结配置与分层码率
  if (vpx_codec_enc_init(&codec_, vpx_codec_vp9_cx(), &cfg_, 0) != VPX_CODEC_OK) {
    NLOG_ERROR(kTagEncoder, "encode_resize_reinit_failed …");
    initialized_ = false; return kVp9Error;
  }
  codec_open_ = true; initialized_ = true;
  vpx_codec_control(&codec_, VP8E_SET_CPUUSED, kCpuUsed);
  frame_count_ = 0; last_pts_us_ = -1; force_key_frame_ = true; encoded_.clear();
  NLOG_INFO(kTagEncoder, "encoder_reinit w=… h=… old_w=… old_h=… reason=size_change_rotation_or_adapt");
}
```

要点：

- **新码流必须重新出关键帧**（`force_key_frame_`），否则远端无法起播；
- `SetRates()` 里的 `config_set` **保留**（它只改码率/分层，不改尺寸，安全）；
- t46 的像素旋转（`i420_rotator.h`）与尺寸交换语义**不变**，只是"改尺寸"的手段从
  `config_set` 换成 reinit；
- t48 的取证标记（`encode_vpx_begin/done/no_packet`）保留，日志新增 `encoder_reinit`。

## 4. 验证

- **语法/类型检查（宿主 NDK clang，真实目标）**：
  `clang++ --target=aarch64-linux-android26 -std=c++17 -fsyntax-only -Iapp/src/main/cpp -Ithird_party/libvpx/include app/src/main/cpp/encoder/vp9_encoder.cpp`
  → **EXIT=0**（脚本与命令见 §5）。
- **构建期**：宿主 `clean assembleDebug` 会真正编译该 TU（由随后的构建任务承担）。
- **真机判据（下一轮）**：
  - U1：`native.log` 出现 `encoder_reinit w=480 h=640 … reason=size_change_rotation_or_adapt`；
  - U2：出现 **`encode_vpx_done`** 与 **`encoded_frame`**，且 `encoder_bitrate.csv` 的
    `encoded_bytes > 0`、首帧 `key=1`；
  - U3：通话 ≥ 10 秒内 `app.log` **不再出现新的** `native_lib_loaded`/`main_activity_create`
    重启序列（即不再闪退）；
  - U4：对端画面出现（`pc_connection_state dtls=true state=CONNECTED` 之后远端首帧）。

## 5. 复现命令

```bash
# 宿主语法检查
CL=/opt/dsh-workspaces/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++
"$CL" --target=aarch64-linux-android26 -std=c++17 -fsyntax-only \
  -Iapp/src/main/cpp -Ithird_party/libvpx/include app/src/main/cpp/encoder/vp9_encoder.cpp
# 构建（由构建任务执行）
./gradlew --no-daemon --no-build-cache clean assembleDebug
```

## 6. 未验证项（不得写成已通过）

真机是否不再闪退、是否出画面、`encoded_bytes > 0`、远端首帧朝向与稳定性；
以及"尺寸变化路径"在**多分辨率自适应**（SDK VideoAdapter 下调分辨率）时是否同样安全 ——
本轮只在 rotation 触发的尺寸变化上取得证据，自适应该走同一 reinit 路径（同一代码分支）。
