# reports/18-encoder-stall.md —— VP9 编码器管线「只编 1 帧、零产出」定因与修复（t48）

- 任务：t48（native-dev），依赖 t46；本轮 inScope：`app/src/main/cpp/encoder/**`、
  `app/src/main/cpp/jni/vp9_encoder_jni.cpp`、
  `app/src/main/kotlin/com/example/webrtcdemo/encoder/**`、本报告。
- 依据：契约 `doc/14-interface-contract.md`（sha256 `b3b67438…`，本轮未改）、
  `doc/06-webrtc-dynamic-bitrate-internals.md`、`doc/11-native-implementation.md`。
- 真机日志（只读）：宿主 `/opt/dsh-workspaces/tmp/dev-logs-2250/x/` ↔ 容器
  `/data/dsh/home/workspace/tmp/dev-logs-2250/x/`（`app.log`、`native.log`、
  `webrtc.log`、`encoder_bitrate.csv`、`app-fallback.log`、`session-summary.txt`、
  `device-info.txt`，导出时刻 2026-09-14T14:50:18Z）。
- 容器内**未跑 gradle**（无 JDK/SDK）；用 NDK clang++ 做了真实目标语法检查与
  离线宿主自测（§5，均为容器内真实执行）。

---

## 1. 结论摘要

| 项 | 结论 |
| --- | --- |
| 「libwebrtc 认为首帧 pending ⇒ 后续帧被丢弃」 | **证伪**（§3，源码级） |
| 真实机制 | 首帧 `nativeEncode` **没有返回**：其进程在 0.2–0.7 s 内彻底停止记录日志并被新进程取代（§2.4）。故障点在 C++ `Vp9Encoder::Encode` 内部、`vpx_codec_encode()` 及其紧随之后（§2.5） |
| 已修复缺陷 D-1（内存/崩溃类） | `vpx_img_wrap(..., img_data=nullptr)` 让 libvpx **每帧**分配 ~450 KiB 内部图像缓冲且从不释放（§4.1） |
| 已修复缺陷 D-2（功能/契约核心） | SDK 实际下发 **5×4** 矩阵，旧代码按内部上限 **3×3** 校验 ⇒ 每次 `SetRates` 被 `bad_dim` 拒绝，且一旦放宽即会**越界写 11 个 int**（§4.2） |
| 已加固 D-3 | JNI 注册表锁跨 `Encode` 全程持有 ⇒ 慢/卡编码会阻塞挂断路径（真机「首帧后无 `nativeRelease` 日志」的真因之一）（§4.3） |
| 已加取证标记 D-4 | `encode_vpx_begin` / `encode_vpx_done` / `encode_no_packet`，把「崩在 libvpx 内」与「崩在取包/回调层」收敛到一行（§4.4） |
| DTLS | 单列判定见 §6：现有日志**不能**证明 DTLS 有问题（采集面缺 DTLS tag），且三个编码会话都在握手可能完成之前就终止了 |
| 验收边界 | 「`nativeEncode` 连续被调用 + `encoded_frame` + `encoded_bytes>0`」**必须在重编 APK 后用真机确认**（§7）；本轮只能给出代码级修复 + 容器内可复现证据 |

---

## 2. 真机证据（日志事实）

### 2.1 全部会话里只有 3 个会话创建过编码器

`native.log` 中 `jni_call method=nativeCreate` 共 6 次（= 3 会话 × 2 次，原因见 §2.3），
对应进程 `23897`、`24450`、`2340`；其余 10 个会话（`20874`、`32725`、`5861`、
`19095`、`20121`、`24676`、`31569`、`6328`、`7037`、`12229`）**从未创建编码器**
（远端未回 answer ⇒ 不发送视频），其中 `20121` 存活 5 分钟以上、`12229` 正常导出日志。

> 含义：没有编码器的会话能长时间存活；**只有一编码就死**——故障与首帧编码强相关。

### 2.2 每个编码会话的 JNI 序列（以 PID 2340 为例，`native.log:241-252`）

```
14:47:42.705 nativeCreate   handle=-5476376612459501312
14:47:42.705 nativeInit     w=640 h=480 start_bps=300000 max_bps=0 fps=60 s=1 t=3
14:47:42.726 encoder_init   w=640 h=480 s=1 t=3 cpu=8 start_bps=300000 max_bps=0
14:47:42.726 ts_target_kbps l0=120 l1=210 l2=300 rc_target_kbps=300
14:47:43.157 nativeSetRates s=5 t=4 total_bps=231813 fps=20
14:47:43.158 nativeSetRates_rejected reason=bad_dim s=5 t=4      ← D-2
14:47:43.173 nativeRelease
14:47:43.174 nativeCreate                                        ← HandleReturnCode 复位
14:47:43.174 nativeInit
14:47:43.177 encoder_init
14:47:43.182 nativeEncode   w=640 h=480 sy=640 su=640 sv=640 ts_ns=714264735992000 rot=270 key=1
（此后该进程再无任何 native.log 记录）
```
三个会话的序列逐字一致（另两个的 `nativeEncode` 在 `native.log:181`、`:204`）。

### 2.3 `nativeRelease → nativeCreate → nativeInit` 是 D-2 的连带后果（源码级）

`nativeSetRates` 返回 `ERR_PARAMETER`（负值）后，C++ 包装层
`sdk/android/src/jni/video_encoder_wrapper.cc:348-372`
`VideoEncoderWrapper::HandleReturnCode()` 会执行：

```cpp
  // Try resetting the codec.
  if (Release() == WEBRTC_VIDEO_CODEC_OK && InitEncodeInternal(jni) == WEBRTC_VIDEO_CODEC_OK) {
    RTC_LOG(LS_WARNING) << "Reset Java encoder.";
    return WEBRTC_VIDEO_CODEC_ERROR;
  }
```
即在**编码线程**上把 Java 编码器 `release()` + `initEncode()` 复位一次 —— 与日志
中 `nativeRelease` 紧贴首帧前一帧（5–9 ms）完全吻合。

### 2.4 三个会话都在首帧编码后 0.2–0.7 s 内整体停止记录（进程终止）

| PID | 首帧 `nativeEncode` | 该进程最后一条 `webrtc.log` | 该进程最后一条 `app.log` | 新进程首次日志（`app-fallback.log`） |
| --- | --- | --- | --- | --- |
| 23897 | 14:31:49.439 | 14:31:49.991（ICE `connection_cc`） | 14:31:49.122 | 14:31:55.739（+6.3 s） |
| 24450 | 14:32:11.523 | 14:32:11.727（`basic_ice_controller`：27 条候选按 pair 状态重排） | 14:32:11.104 | 14:32:17.434（+5.9 s） |
| 2340 | 14:47:43.182 | 14:47:43.437（ICE `basic_ice_controller`） | 14:47:43.638（`pc_signaling_state CLOSED`） | 14:47:47.049（+3.9 s） |

补充事实：

- `23897`/`24450` **没有** `Leave` / `pc_signaling_state CLOSED`（对照同一份 `app.log`
  中其它正常挂断会话：`msg_sent type=Leave` + `state=CLOSED` 成对出现，
  如 `app.log:5-7`、`:20-22`）⇒ 不是正常挂断，是进程消失。
- `2340` 的 `CLOSED` 出现在首帧后 0.45 s，此后**任何线程**（ICE、网络 CC、GoogCC、
  Kotlin ping；`2340` 的 ping 周期约 13 s，下一个应在 14:47:53 左右）都没有再记录，
  而 `app-fallback.log` 显示 14:47:47 已有一个**新进程**在初始化日志 ⇒ 旧进程已终止。
- 因此「本地预览一直有帧 / ICE 正在成功」与「进程已死」并不矛盾：崩溃发生在
  **编码线程内部**，与采集、渲染、ICE 属于不同线程。

### 2.5 故障点被夹在 `Vp9Encoder::Encode` 内（CSV 行数给出硬证据）

`app/src/main/cpp/encoder/vp9_encoder.cpp` 中 `WriteBitrateCsvLocked()` 只有三个调用点：

| 行 | 调用者 | CSV 行 |
| --- | --- | --- |
| 290 | `Vp9Encoder::Init()` | `WriteBitrateCsvLocked(0, 0, -1)` |
| 350 | `Vp9Encoder::SetRates()` | `WriteBitrateCsvLocked(0, 0, -1)`（本真机运行未走到，见 D-2） |
| 663 | `Vp9Encoder::Encode()` | `WriteBitrateCsvLocked(encoded_bytes, …)` |

> 行号为本轮修复**之后**的当前值（`Encode` 内的调用点从修复前的 615 后移到 663，
> 因为新增了 D-4 的取证标记）；全文件核对确认 `WriteBitrateCsvLocked` 只有这三处。

真机 `encoder_bitrate.csv` 只有 6 行，且时间戳与 6 次 `encoder_init`（= `Init`）**逐条相差 0–3 ms**：

```
csv 1789396308951 / 1789396309437  ←→  encoder_init 14:31:48.951 / 14:31:49.437
csv 1789396331007 / 1789396331522  ←→  encoder_init 14:32:11.006 / 14:32:11.522
csv 1789397262727 / 1789397263178  ←→  encoder_init 14:47:42.726 / 14:47:43.177
```
⇒ **`Encode` 从未走到 CSV 写入点**（否则每个会话会多出一行 `encoded_bytes` 记录），
也从未打出 `encoded_frame`（`vp9_encoder.cpp:651/658`）、`encode_failed`（`:589`）、
`encode_img_wrap_failed`（`:535`）、`encode_bad_frame`（`:412`）、`encoder_resize`（`:459`）。
`Encode` 里每一条「无产出返回」都带日志，**一条都没出现** ⇒ 失败发生在
`vpx_codec_encode(&codec_, &image, …)`（`:582`）内部或紧随其后的取包/量化参数段，
且**没有正常返回**（`nativeCopyEncodedFrame` 的 `jni_return` 日志也从未出现）。

---

## 3. 对「libwebrtc 首帧 pending ⇒ 后续帧被丢弃」的证伪

`third_party/libwebrtc-src/video/video_stream_encoder.cc`：

- `:2205` `const int32_t encode_status = encoder_->Encode(out_frame, &next_frame_types_);`
  `:2206` `was_encode_called_since_last_initialization_ = true;`
  `:2208-2213` 只有 `encode_status < 0` 时才 `RequestEncoderSwitch()` 并返回；
  **非负返回值不会停止后续帧**。
- 该文件里唯一的 `pending_frame_` 逻辑在输入侧（`:1968-2011`：`DropDueToSize()` /
  `EncoderPaused()` 时暂存或丢弃**输入帧**），与「编码器是否回调过 `OnEncodedImage`」
  无关；libwebrtc 的 `VideoEncoder` 契约是「每帧必须回调恰好一次」，流编码器
  并不对「未回调」做门控。
- 结论：如果我们的 `encode()` 正常返回（`OK`/`NO_OUTPUT`/任何状态），下一次
  输入帧必然再次进入 `nativeEncode`，日志里就会有多条 `jni_call method=nativeEncode`。
  实测**每个会话恰好 1 条** ⇒ `encode()` 没有返回。

补充：我们的 Kotlin `Vp9VideoEncoder.encode()`（`Vp9VideoEncoder.kt:156-227`）每条
提前返回路径都有日志（`to_i420_failed`、`encoded_plane_rejected`、
`encoder_encode_failed`），而 `app.log` 中 `kotlin encoder` tag 一行都没有 ——
这一「缺失」已由 **t44 修掉的日志等级过滤 Bug** 解释（当时只有 VERBOSE/DEBUG 能落盘，
见 `device-info.txt: log_level=DEBUG(1)` 与 `app.log` 只有 DEBUG/VERBOSE 行，
且只出现 `signaling/pc/stats` 三个 tag），不是本缺陷的证据。

---

## 4. 代码级缺陷与修复

### 4.1 D-1：`vpx_img_wrap(..., img_data=nullptr)` 每帧泄漏 ~450 KiB（已修）

`third_party/libvpx-src/vpx/src/vpx_image.c`：
- `:108-121` `img_data == NULL` ⇒ 走「自己分配」分支（并对 `d_w/d_h` 做色度取整）；
- `:146-158` `img->img_data = vpx_memalign(buf_align, alloc_size)`，`img_data_owner = 1`；
- 640×480 I420 ⇒ `alloc_size = 480 × 640 × 12/8 = 460800 B`。

旧代码（设备运行版 `vp9_encoder.cpp:445-457`）把 `vpx_image_t` 放**栈上**、传
`nullptr`、随后只覆盖 `planes/stride`，从不调用 `vpx_img_free()` ⇒ 这块内部缓冲
**每帧泄漏一次**，30 fps 下 ≈ **13.8 MB/s**（且 `self_allocd == 0`，连结构体都不会被
libvpx 归还）。参考实现 `libwebrtc-src/modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc`：
`:2118/:2127` 在 rewrap 时用 `img_wrap(nullptr, …)` 分配一次，
`:2182-2187` 每帧只覆盖 `planes/stride`，`:329`/`:2126` 用 `img_free()` 归还。

**修复**（`vp9_encoder.cpp:532-537`）：把**我们自己的 Y 平面指针**作为第 6 个参数传入，
libvpx 即走「外部缓冲」分支（`:108-112` 取 `w=d_w,h=d_h`；`:148` 不再分配；
`img_data_owner` 保持 0）⇒ 分配与所有权归零，平面/stride 仍由我们覆盖（零拷贝语义不变）。
`encode_vpx_begin` 标记里显式打印 `img_owner`，真机可直接核验为 0。

### 4.2 D-2：SDK 矩阵是 5×4，旧校验按 3×3 ⇒ 每次 SetRates 被拒（已修）

- 真机：`nativeSetRates s=5 t=4` → `nativeSetRates_rejected reason=bad_dim s=5 t=4`
  （`native.log:175`、`:198`、`:246`；三个会话各一次）。
- 根因：`layer_bitrate_allocator.h:27-28` 的 `kMaxSpatialLayers=3` /
  `kMaxTemporalLayers=3` 被误当成 **SDK 矩阵维度**；而 SDK 按 libwebrtc
  `api/video_codecs/video_codec.h` 的 `kMaxSpatialLayers = 5`、
  `kMaxTemporalStreams = 4` 分配（`5×4 = 20` 个条目）。
- 连锁后果：① GCC 的目标码率永远到不了 libvpx（CSV 里 `rc_target_kbps` 恒为 300，
  而 GCC 请求 231813 bps → `layer_0_0` 永远是初值）；② §2.3 的编码器复位；
  ③ **潜在越界写**：`NativeSetRates` 旧代码用 `jint values[3*3]`（9 个）接
  `GetIntArrayRegion(length=20)`、再按 `5×4` 双层循环写 `LayerBitrate::layer_bps[3][3]`
  ⇒ 一旦只放宽维度检查就会破坏栈/成员内存（本轮修复同时消除了这个陷阱）。

**修复**：
1. `layer_bitrate_allocator.h:51-52` 新增 `kSdkMaxSpatialLayers = 5`、
   `kSdkMaxTemporalStreams = 4`（引用 libwebrtc 头文件名与常量名）。
2. 新增**纯函数** `FoldSdkLayerMatrix()`（`layer_bitrate_allocator.h:126`、
   `.cpp:219-250`）：把 SDK 展平矩阵折叠进内部 `[3][3]`，被钳掉的空间层**按同一
   时序层求和**（不丢总量），维度越界/空指针返回 `false`（调用方返回 `ERR_PARAMETER`）。
3. `vp9_encoder_jni.cpp:256-263` 维度检查改用 SDK 上限；`:277` 接收缓冲改为
   `values[5×4]`；`:281` 折叠后再交给编码器。
   编码器内部仍以 `last_rates_.configured_spatial/temporal`（= **1/3**，契约 §5.6 的
   L1T3）做 40/30/30 拆分 ⇒ **写回 libvpx 的分层数不变**（`configured_spatial` 恒为 1）。
4. 学习核心日志补齐（契约 §9.4「收到的每层码率与写回 vpx 的值」）：
   `nativeSetRates_fold in_s=… in_t=… len=… out_s=… out_t=… total_bps=…` +
   `nativeSetRates_matrix s0t0=… s0t1=… s0t2=… s0t3=…`（`:290`/`:296`），
   与编码器侧原有的 `setrates` / `layer_bps …` / `ts_target_kbps …` / `vpx_apply …`
   逐层配对。

### 4.3 D-3：JNI 注册表锁跨 `Encode` 全程持有（已加固）

旧 `WithEncoderLocked()` 把 `g_registry_mutex` 一直握到 `encoder->Encode()` 返回：
- 一次慢编码会阻塞**所有** JNI 调用（`nativeGetEncodedFrameSize`、`nativeSetRates`、
  `nativeRelease`…）；
- 真机上首帧 `nativeEncode` 之后再无 `nativeRelease` 日志（三个会话的 `nativeRelease`
  都只来自 §2.3 的复位路径）——与「挂断路径被编码线程堵住」一致。

**修复**（`vp9_encoder_jni.cpp:36-76`（文件头说明）、`:54`（AcquireEncoder）、`:69`（WithEncoder）、`:189`（CopyEncodedFrame）、`:317`（NativeRelease））：注册表改为
`std::map<intptr_t, std::shared_ptr<Vp9Encoder>>`，`AcquireEncoder()` 只在查表时持锁，
调用在锁外执行；`nativeRelease` 只把句柄从注册表摘除，对象在最后一个引用（可能在
`Encode` 中）释放后析构 ⇒ 不再 use-after-free、不再互相阻塞。契约 §6.3 语义不变：
未注册句柄仍返回 `UNINITIALIZED`，`nativeRelease` 仍幂等返回 `OK`。

### 4.4 D-4：把「崩在哪一行」变成一条日志（新增）

`vp9_encoder.cpp`：
- `:567-581` `encode_vpx_begin`（首帧为 INFO，含 `pts_us/dur_us/flags/w/h/stride/img_owner`；
  后续帧 DEBUG）；
- `:583-587` `encode_vpx_done`（`err` + 耗时 µs）；
- `:631-641` `encode_no_packet`（vpx 返回 OK 但无包时 WARN）。

判读方式：**日志停在 `encode_vpx_begin`** ⇒ 崩/卡在 libvpx 内部（`vpx_codec_encode`）；
**停在 `encode_vpx_done`** ⇒ 问题在取包/回调层；**两者都有但仍无 `encoded_frame`** ⇒
看 `encode_no_packet`。

---

## 5. 验证（容器内真实执行）

### 5.1 离线宿主自测：分层码率折叠（新增文件，真实运行）

```bash
NDK=/data/dsh/home/workspace/android-sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin
cd code/webrtc-demo
$NDK/clang++ --target=x86_64-unknown-linux-gnu -nostdlib -static -ffreestanding \
  -fno-exceptions -fno-rtti -std=c++17 -Wall -Wextra \
  -DLAYER_BITRATE_HOST_TEST -DLAYER_BITRATE_FREESTANDING -I app/src/main/cpp \
  app/src/main/cpp/encoder/layer_bitrate_fold_host_test.cpp \
  app/src/main/cpp/encoder/layer_bitrate_allocator.cpp \
  -o <工作区>/t48-work/layer_bitrate_fold_test -Wl,-e,_start -Wl,--build-id=none
<工作区>/t48-work/layer_bitrate_fold_test ; echo "exit=$?"
```
结果（`2026-09-14 23:23:25` 容器内执行，`webrtc-build/t48-work/fold_test.log`
sha256 `cbb4a51ceb9e78831dd2b85cdd13d89f7c6226ddd743bb483c63b3530033eda5`）：

```
  OK   fold(5x4) accepted (was: bad_dim reject)
  OK   fold num_spatial (clamped to 3) = 3        OK   fold num_temporal = 3
  OK   fold total_bps = 231813                    OK   fold layer_bps[0][0] = 231813
  OK   guard before untouched (no OOB)            OK   guard after untouched (no OOB)
  OK   fold(6x4) rejected (s > 5)   OK fold(5x5) rejected (t > 4)   OK fold(nullptr) rejected
  OK   compute configured_spatial = 1   OK compute configured_temporal = 3
  OK   compute rc_target_kbps = 231  OK ts l0/l1/l2 = 92/162/231  OK monotonic
  OK   compute decimator[0..2] = 2/1/1
  OK   init ts l0/l1/l2 = 120/210/300 kbps   OK init rc_target = 300 kbps
== result: failures=0 ==        exit=0   （36 项全通过）
```
其中 `init ts l0/l1/l2 = 120/210/300`、`rc_target = 300` 与真机 `nativeInit` 日志
`ts_target_kbps l0=120 l1=210 l2=300 rc_target_kbps=300` **逐值一致**，同时证明
折叠后的 5×4 输入不会改变 L1T3 的初始分层。

### 5.2 真实目标工具链语法检查（aarch64，`-Wall -Wextra`）

```bash
$NDK/clang++ --target=aarch64-linux-android26 -std=c++17 -fno-exceptions -fno-rtti \
  -Wall -Wextra -I app/src/main/cpp -I third_party/libvpx/include -fsyntax-only <file>
```
| 文件 | exit | 诊断 |
| --- | --- | --- |
| `encoder/vp9_encoder.cpp` | 0 | 无 |
| `encoder/layer_bitrate_allocator.cpp` | 0 | 无 |
| `jni/vp9_encoder_jni.cpp` | 0 | 无 |
| `encoder/layer_bitrate_fold_host_test.cpp` | 0 | 无 |

### 5.3 边界与不变量检查

- **JNI 方法表逐字未改**：`vp9_encoder_jni.cpp:347-363` 的 9 条签名与契约 §6.3 一致
  （本轮只改实现，未动 `JNINativeMethod` 表，也未增删方法）。
- **码率/关键帧语义不变**：`configured_spatial == 1`、`configured_temporal == 3`、
  `ts_rate_decimator = {2,1,1}`、`rc_target_bitrate` 仍由 total 推导（§5.1 已断言）；
  首帧仍 `force_key_frame_` 强制关键帧（`Init:270`），`request_key_frame` 语义未动。
- **两个宿主自测文件都不参与 App 构建**：`app/src/main/cpp/CMakeLists.txt:33-43`
  逐文件列举源（非 GLOB），`i420_rotator_host_test.cpp` 与本轮的
  `layer_bitrate_fold_host_test.cpp` 均未被列举；两文件也都被整文件 `#if` 包住。

---

## 6. DTLS 单列判定（captain 关注的第二项）

**现状**：整份 `webrtc.log` 里与 `dtls|srtp` 相关的行只有 21 条，全部是
`webrtc_webrtc_session_de` 的 `DTLS-SRTP enabled; sending DTLS identity request (key_type: 1)`
（每个会话一条），没有任何握手/transport 完成日志。

**判定：现有日志不能证明 DTLS 有问题**，理由三条：
1. 该 `webrtc.log` 是**按 tag 过滤的 logcat 采集**（22 个 tag，如
   `webrtc_webrtc_session_de`、`webrtc_basic_ice_control`、`webrtc_jsep_transport_co`、
   `webrtc_rtp_transport_con`、`webrtc_network_cc`…），采集面里**没有** DTLS transport
   自己的 tag（例如 `dtls_transport` / `srtp_transport` / `dtls_transport_cc`）⇒
   「没有 DTLS 完成日志」至少部分是**过滤产物**，不是缺失证据。
2. 三个编码会话都只活到首帧后 0.2–0.7 s（§2.4）：DTLS 握手需要 ICE 完成后若干
   RTT，进程在此之前就终止了，握手日志本来也无从产生。
3. 与「远端无画面」的因果链：本缺陷使**上行 0 字节**，即使 DTLS/transport 完全
   正常，对端也必然看不到画面；因此黑屏的主因已由 §2/§4 解释，DTLS 是并列
   待查项而非前置。

**取证方案（重编 APK 后一次真机复测即可判定）**：
1. t44 已加入的诊断会直接落盘：`pc_connection_state`（含 `dtls` 字段）、
   `selected_candidate_pair`、`pc_ice_candidate_error`、`pc_starting`、
   `ice_gathering_complete`（`app.log`，t44 同时修掉了等级过滤 Bug ⇒ 这次 INFO/WARN/ERROR 可见）；
2. 复测时额外抓**完整 logcat**（不再按 tag 过滤）：
   `adb logcat -b all -v threadtime > logcat.txt`，然后在 `webrtc-build`/导出包中
   一并保留，`grep -iE 'DtlsTransport|Dtls|Srtp|Transport.*[Cc]omplete'` 判定；
3. 判据：`pc_connection_state` 到达 `CONNECTED`（t44 的 `dtls` 字段应为
   `CONNECTED`/`true`）⇒ DTLS 正常；若长时间停在 `CONNECTING` 且无
   `DtlsTransport` 日志，则单开缺陷。

---

## 7. 未验证项与假设清单（**必须由重编 APK + 真机确认**）

| # | 未验证项 | 说明 / 下一步判据 |
| --- | --- | --- |
| U1 | 首帧 `Encode` 到底「崩」还是「卡」在 libvpx 内 | 缺少 tombstone（本次导出只有 App 自己的日志）。新标记 D-4 使下一次复测可判定：日志停在 `encode_vpx_begin` = libvpx 内崩溃/卡死 |
| U2 | D-1 的 460 800 B/帧泄漏是否就是首帧终止的直接原因 | 单帧 450 KiB 不足以 OOM，**不认为它是首帧即死的充分原因**；它是必须修的确定性缺陷（长通话必炸） |
| U3 | D-2 修复后 GCC 码率是否真正到达 libvpx | 复测看 `nativeSetRates_fold` + `setrates` + `vpx_apply` 三组日志与 CSV 的 `rc_target_kbps/ts_kbps_*` 是否随 GCC 变化；`SetRates` 不再出现 `bad_dim`/`Reset Java encoder` |
| U4 | 「`nativeEncode` 连续被调用 + `encoded_frame` + `encoded_bytes>0`」 | 契约验收面，必须真机；判据：`native.log` 出现多条 `encode_vpx_done`/`encoded_frame`，CSV `encoded_bytes>0`，对端出画 |
| U5 | `su=sv=640`（U/V stride = width）是否合理 | 真机 `nativeEncode` 日志三次都是 `sy=640 su=640 sv=640`（640×480）。`CheckPlaneCapacity` 通过说明缓冲区确实 ≥153280 B，故不构成越界；但**来源未在容器内证实**（可能来自 SDK 纹理→I420 转换的实现细节）。已在报告中登记，不改代码 |
| U6 | 是否还存在别的首帧崩溃点（如 `VP8E_GET_LAST_QUANTIZER`） | 已在 `libvpx-src/vp9/vp9_cx_iface.c:2260` 确认该 ctrl 存在于 VP9 控制表（`ctrl_get_quantizer`），**不是**崩溃候选；但 U1 未定前不敢宣称穷尽 |
| U7 | `nativeRelease` 不再同步 `delete` 是否满足契约 §6.7 字面要求 | 语义：摘除注册表 + 由 `shared_ptr` 释放；无并发调用时析构仍在 `nativeRelease` 内**同步**完成。若评审要求「返回前必须已 `vpx_codec_destroy`」，需回改（可加 `WaitIdle`） |

**假设（容器内无法验证，逐条声明）**：
- A-1：`nativeSetRates` 收到的 `5×4` 矩阵来自 libwebrtc 的
  `kMaxSpatialLayers × kMaxTemporalStreams`（依据：常量值 + Java `BitrateAllocation`
  以二维数组承载，见 `libwebrtc-src/api/video_codecs/video_codec.h`），未在真机上
  打印矩阵全部 20 个值（新日志 `nativeSetRates_matrix` 只打前 4 个）。
- A-2：`encoder_bitrate.csv` 的 6 行全部来自 `Init`（依据：时间戳与 6 次
  `encoder_init` 逐条相差 0–3 ms，且 `SetRates` 未走到第 350 行）。若 `Init`
  之外还有别的 CSV 写入路径，结论需修正（已在 `vp9_encoder.cpp` 全文件核对：
  仅 290/350/615 三处）。
- A-3：进程终止（而非仅仅编码线程卡死）——依据是「同进程所有 tag 的日志同时
  停止」+「`app-fallback.log` 出现新进程日志」；未取得 tombstone。

---

## 8. 交付清单与最小 diff 说明

| 文件 | 变更 | sha256（本轮结束时） |
| --- | --- | --- |
| `app/src/main/cpp/encoder/vp9_encoder.cpp` | D-1 修复 + D-4 标记 | `e14d28a351449df3f17fa4ebc786c9a27991233434adb9f6d8f1be6339c355f6` |
| `app/src/main/cpp/encoder/layer_bitrate_allocator.h` | 新增 `kSdkMaxSpatialLayers/kSdkMaxTemporalStreams` + `FoldSdkLayerMatrix` 声明 + 维度注释修正 | `f40f69e51e35b0998351a280113d42181fe0f0b44b4bfa269ceddb72e2bb71d1` |
| `app/src/main/cpp/encoder/layer_bitrate_allocator.cpp` | 折叠实现 + 去掉 `<algorithm>` 依赖（为离线自测） | `bc3a03d6c9b0dce9fc986dafea484dd378850e35b85d8aebba603fd01da64c04` |
| `app/src/main/cpp/encoder/layer_bitrate_fold_host_test.cpp` | 新增离线宿主自测（36 断言，`failures=0`） | `078e766307e1d9c8f441fe62d45debefe447b0228282154c2f58e37dfff2cec2` |
| `app/src/main/cpp/jni/vp9_encoder_jni.cpp` | D-2 维度/折叠 + D-3 `shared_ptr` 注册表 | `8b8e83c31fa5778cd80aff1455a29ee3ffab5f92048e132b4150098827e38d1b` |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt` | 注释纠错（3×3 → 5×4，说明 native 侧折叠）；**行为未改** | `05a6f3046fb7232564aad4c1073e110ed11041d95f05df26cdaef083334900fc` |

- 未改：`third_party/**`、`doc/**`、`scripts/**`、`app/src/main/cpp/CMakeLists.txt`、
  JNI 方法表、`app/src/main/kotlin/.../webrtc/**`、`app/src/test/**`（t45 在用）。
- 容器内**不编译** App（无 JDK/SDK）；离线产物写在仓外
  `webrtc-build/t48-work/`（`layer_bitrate_fold_test`、`fold_test.log`）。

## 9. 下一步（给 captain）

1. 合并构建（t44+t45+t46+t48 全部落盘后）出 APK；
2. 真机复测一次通话，导出日志 + **完整 logcat**；
3. 按 §7 U1/U3/U4 判据验收；若 `nativeEncode` 仍只有 1 次且日志停在
   `encode_vpx_begin`，则把工作范围收敛到「libvpx 输入图像/配置」而不是 libwebrtc 侧。
