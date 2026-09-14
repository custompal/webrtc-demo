# 07 — C++ Native 层实现报告（t7 / native-dev）

> 依据：`doc/14-interface-contract.md`（唯一权威）§2.2、§4.2、§5、§6、§9.2–§9.4、§10（C03/C07/C08/C09/C10/C17/C22/C25）、
> `doc/11-native-implementation.md`、`doc/06-webrtc-dynamic-bitrate-internals.md`、`doc/05` §11、`ADR-002/005/006/007`。
> 本文中「契约」均指 `doc/14-interface-contract.md`。

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 交付物完整性 | `app/src/main/cpp/` 下契约 §2.2 列出的 21 个源文件 + `CMakeLists.txt` + `.clang-format` 全部就位（另加 `tests/host/` 验证资产，不参与构建） |
| 真实构建 | 用 **t5 已产出的 `third_party/libvpx/lib/libvpx.a`（aarch64）** 走真实 CMake+NDK 工具链构建 → `libwebrtcdemo_native.so`（2,115,168 B，ELF64 AArch64），**0 warning**，导出符号只有 `JNI_OnLoad`/`JNI_OnUnload` |
| JNI 契约 | 15 个 native 方法 + 2 个回调，名称/签名**逐字**与契约一致（§6.2/§6.3/§6.4/§6.5） |
| 编码器注入链路 | 契约 A1：Kotlin 实现 `org.webrtc.VideoEncoderFactory/VideoEncoder`，逐帧经自有 JNI 调 C++ libvpx；本层**不链接 libwebrtc/libjingle**（实测未定义符号 0） |
| 动态码率闭环 | `SetRates` 打印「收到的每层 bps 矩阵」与「写回 vpx 的 ss/ts/layer/rc/decimator」，并逐次写入 `encoder_bitrate.csv`（学习点） |
| 日志（D6） | logcat + 文件双写；行格式逐字符对齐契约 §9.1 示例；滚动 2 MiB × 3；运行时可切级别；`nativeInit` 前只落 logcat |
| NAT（RFC5780） | 四步判定实现并用**本机假 STUN 服务器**驱动验证：Open/FullCone/RestrictedCone/PortRestrictedCone/Symmetric/Unknown 全部正确 |
| 未授权偏离 | **无**（受控偏离与 API 名称适配见 §6、§7） |
| 未验证项 | 真机 JNI 注册/编码/NAT/logcat（无设备）；Kotlin 侧 nativebridge 类已由 t8 产出，**B 侧签名已与本层逐字互验（见 §2.5）** |

---

## 1. 交付物清单

```
app/src/main/cpp/
├── CMakeLists.txt                                  # 契约 §4.2（冻结）
├── .clang-format                                   # WebRTC/Chromium 风格（ADR-007）
├── jni/
│   ├── jni_bridge.h / jni_bridge.cpp               # JNI_OnLoad/OnUnload、JavaVM 与 4 类 global ref
│   ├── callback_bridge.h / callback_bridge.cpp     # 表 B-1（2 个 C++→Java 回调）
│   ├── native_log_jni.cpp                          # 表 A-1（4 个方法）
│   ├── vp9_encoder_jni.cpp                         # 表 A-2（9 个方法）
│   └── nat_detector_jni.cpp                        # 表 A-3（2 个方法）
├── encoder/
│   ├── vp9_encoder.h / vp9_encoder.cpp             # libvpx VP9 封装（Init/Encode/SetRates/Release）
│   └── layer_bitrate_allocator.h / .cpp            # 核心学习点：码率矩阵 → vpx 分层码率
├── nat/
│   ├── nat_detector.h / nat_detector.cpp           # RFC5780 四步判定（自有线程）
│   └── stun_client.h / stun_client.cpp             # STUN Binding/CHANGE-REQUEST 编解码
├── log/
│   ├── native_log.h / native_log.cpp               # logcat+文件双写、滚动、级别、CSV
│   └── log_macros.h                                # NLOG_* 宏（级别短路）
├── util/
│   ├── jni_util.h / jni_util.cpp                   # jstring/direct buffer 校验、异常清理
│   └── thread_util.h                               # ScopedJniEnv（Attach/Detach RAII）
└── tests/host/                                     # **额外验证资产，不参与 CMake/APK**
    ├── README.md · run_host_tests.sh
    ├── layer_bitrate_and_native_log_test.cpp · nat_detector_host_test.cpp
    └── stub/{jni.h, android/log.h}
```

C++ 规模：23 个交付文件、约 3.5k 行（含中文注释）。

> `doc/11` §1 里的 `jni/peer_connection_jni.*`、`webrtc/peer_connection_manager.*`、
> `webrtc/stats_collector.*`、`webrtc/video_sink_adapter.*` 按契约 §2.2 的说明**不创建**
> （职责已由 Kotlin 侧 `org.webrtc` 承担；登记为契约 C07/C08 的落地）。

---

## 2. 与契约的逐项对应

### 2.1 目录与构建（§2.2 / §4.2）

- 全部文件按契约 §2.2 命名与分层；无仓库根 `native/`、无软链（C03）。
- `CMakeLists.txt` 严格按 §4.2：库名 `webrtcdemo_native`、`WEBC_THIRD_PARTY` 注入与相对回退、
  仅链接 `vpx`/`log`/`android`、`-fvisibility=hidden -fno-exceptions -fno-rtti -Wall -Wextra`、
  `-Wl,--gc-sections -Wl,--exclude-libs,ALL`。**不出现** `libwebrtc.a`/`libjingle_peerconnection_so.so` 的链接
  （文件中仅注释提及，契约 V18 期望一致）。

### 2.2 JNI 接口（§6）

| 表 | 数量 | 文件 | 逐字核对 |
|---|---|---|---|
| A-1 `NativeLog` | 4 | `jni/native_log_jni.cpp` | `nativeInit (Ljava/lang/String;Ljava/lang/String;IJI)V`、`nativeSetLevel (I)V`、`nativeFlush ()V`、`nativeShutdown ()V` |
| A-2 `NativeVp9Encoder` | 9 | `jni/vp9_encoder_jni.cpp` | `nativeCreate ()J`、`nativeInit (JIIIIIII)I`、`nativeEncode (JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I`、`nativeCopyEncodedFrame (JLjava/nio/ByteBuffer;[I)I`、`nativeGetEncodedFrameSize (J)I`、`nativeSetRates (J[IIIII)I`、`nativeRequestKeyFrame (J)I`、`nativeRelease (J)I`、`nativeGetImplName ()Ljava/lang/String;` |
| A-3 `NativeNatDetector` | 2 | `jni/nat_detector_jni.cpp` | `nativeDetect (Ljava/lang/String;IJ)V`、`nativeCancel ()V` |
| B-1 `NativeCallbacks` | 2 | `jni/callback_bridge.cpp` | `onNatTypeDetected (Ljava/lang/String;Ljava/lang/String;)V`、`onLogEvent (ILjava/lang/String;Ljava/lang/String;)V` |
| 合计 | **15 + 2** | | 与契约 §6.1 的计数一致（V28 = 15） |

- 类名逐字冻结：`com/example/webrtcdemo/nativebridge/{NativeLog,NativeVp9Encoder,NativeNatDetector,NativeCallbacks}`（V29）。
- 注册：`JNI_OnLoad` → `FindClass + NewGlobalRef`（4 类）→ `RegisterNatives`（3 张表）→ 缓存 2 个 `GetStaticMethodID`；
  任一步失败 `__android_log_print(ERROR, "WebRtcDemo", ...)` 并返回 `JNI_ERR`（契约 §6.1，**不静默降级**）。
- 状态码：`jni`/`encoder` 中严格使用 0/1/-1/-2/-3/-4/-5/-6/-7/-13（契约 §6.6）。

### 2.3 编码器路线（§5，A1）

- **不实现** `webrtc::VideoEncoder`（契约 §5.1.1 受控偏离）；`grep -rn "webrtc::VideoEncoder" app/src/main/cpp` = **0**（V17）。
- 实现名 `SelfVp9Libvpx`（V15 = 4 处：头文件常量、`ImplName()`、JNI 注册日志、`nativeGetImplName`）。
- 空间层硬约束：`cfg_.ss_number_layers = 1` 且有中文注释指向契约 §5.6；`nativeInit` 收到 S≠1 返回 `ERR_PARAMETER`（V22）。
- 码率闭环 4 要素齐备：`ss_target_bitrate` / `ts_target_bitrate` / `layer_target_bitrate` / `vpx_codec_enc_config_set`（V21）。
- CSV 表头 `ts_ms,total_bps,fps,...` 在 `log/native_log.cpp`（V23）。

### 2.4 日志（§9）

- 行格式：`<ts> <LEVEL> <layer> <tag> [<pid>/<tid>] <msg>[ k=v ...]`；
  `LEVEL` 左对齐定宽 7、`layer` 定宽 6（`native`）、`tag` 定宽 10 后紧跟 `[`。
  实测行（与契约 §9.1 示例逐字符同构）：
  ```
  2026-09-13T07:46:50.189Z INFO    native encoder   [155996/155996] encoder_init w=640 h=480 s=1 t=3 cpu=8
  2026-09-13T07:47:25.544Z INFO    native bitrate   [156668/156668] setrates total_bps=750000 fps=30
  ```
- 目录/滚动：`<filesDir>/logs/native.log`（+`.1`/`.2`，2 MiB × 3）与 `encoder_bitrate.csv`（契约 §9.2/§9.4）；
  滚动实测生成 `native.log`/`native.1.log`/`native.2.log`。
- 等级：0..5 与 Kotlin 数值一致；`nativeSetLevel` 运行时切换；非法级别（<0 或 >5）不输出（避免出现 `OFF` 级别行破坏格式）。
- 终端保留：同时 `__android_log_print`（tag `WebRtcDemo-Native`）；`nativeInit` 之前只落 logcat 并打一次 ERROR。

### 2.5 B 侧（t8 nativebridge）签名互验 —— 已双向确认

t8 已产出 4 个 bridge 类；本层逐条比对（`grep -c "external fun" *.kt` 与逐个参数类型核对），结论：**完全一致**。

| 表 | Kotlin 文件 | external fun 数 | 关键签名对照 |
|---|---|---|---|
| A-1 | `nativebridge/NativeLog.kt`（`object` + 4×`@JvmStatic`） | 4 | `nativeInit(String,String,Int,Long,Int)` = `(Ljava/lang/String;Ljava/lang/String;IJI)V`；`nativeSetLevel(Int)`；`nativeFlush()`；`nativeShutdown()` |
| A-2 | `nativebridge/NativeVp9Encoder.kt`（`object` + 9×`@JvmStatic`） | 9 | `nativeCreate():Long`、`nativeInit(Long,Int×7):Int`、`nativeEncode(Long,ByteBuffer×3,Int×5,Long,Int,Boolean):Int`、`nativeCopyEncodedFrame(Long,ByteBuffer,IntArray):Int`、`nativeGetEncodedFrameSize(Long):Int`、`nativeSetRates(Long,IntArray,Int,Int,Int,Int):Int`、`nativeRequestKeyFrame(Long):Int`、`nativeRelease(Long):Int`、`nativeGetImplName():String` |
| A-3 | `nativebridge/NativeNatDetector.kt`（`object` + 2×`@JvmStatic`） | 2 | `nativeDetect(String,Int,Long)`、`nativeCancel()` |
| B-1 | `nativebridge/NativeCallbacks.kt`（`object` + 2×`@JvmStatic`） | 0（回调非 external） | `onNatTypeDetected(String,String)`、`onLogEvent(Int,String,String)` |
| 合计 | | **15** | V27 与 V28 双向吻合（C++ 侧 4+9+2 = 15，Kotlin 侧 4+9+2 = 15） |

- 三者全部是 `object` + `@JvmStatic` → 与 `RegisterNatives`/`GetStaticMethodID` 的类级静态方法要求一致（若为伴生对象的普通方法会被编译成 `nativeInit$<module>`，契约 §6.1 已警示）。
- `NativeLog.ensureInitialized()` 的实际调用：`nativeInit(path, "native", level.code, 2*1024*1024, 3)`（`NativeLog.kt:121`），
  与契约 §9.4 及本层 `nativeInit` 语义**完全一致**；调用点位于 `WebRtcDemoApp.onCreate` 与 `WebRtcEngine.initialize`（创建 factory/编码器之前的硬闸口）。
- 该互验消除了原先"B 侧未产出、签名只能单侧假设"的风险项（原 §7.2 A2 的 Java 侧部分随之闭合）。

### 2.6 运行时接口约定的第二轮互验（2026-09-13，与 t8）

| # | 约定 | t8 侧实现（android-dev 报告 §8.7） | 本层一致性 |
|---|---|---|---|
| 1 | `nativeEncode` 平面前置条件（direct + 容量 `stride*(rows-1)+row_bytes`，亮度/色度分开） | `Vp9VideoEncoder.encode()` 在**调 JNI 之前**预检（`validatePlanes`），原因串与 C++ 判定同名：`non_direct_buffer`/`bad_dimensions`/`plane_capacity_y|u|v`，容量公式同口径 | ✅ 一致：C++ `-4` 之前先打 `nativeEncode_rejected reason=...`；两侧日志字段（w/h/stride_y_u_v/direct/reason）可逐条对照，真机排障无需猜 |
| 2 | 状态码语义 | `Vp9VideoEncoder.statusOf()` 按契约 §6.6 **显式数值映射**：`-4 → ERR_PARAMETER`、`-5 → ERR_SIZE`、`-7 → UNINITIALIZED`（不是统一 `ERROR`） | ✅ 一致：本层所有返回值即 §6.6 数值（`encoder/vp9_encoder.h` 枚举），无需转换 |
| 3 | `nativeSetRates` 形状 | `IntArray(spatial*temporal)`，`flat[s*temporal+t]`，`S=matrix.size`、`T=matrix[0].size`；**运行时矩阵是 3×3 → 传入 S/T = 3/3**（而 `nativeInit` 传 1/3） | ✅ 一致：本层 `nativeSetRates` 允许 S∈[1,3]/T∈[1,3]，把「矩阵维度」用于解析输入、「编码器分层（1/3）」用于换算输出（`layer_bitrate_allocator` 的 `configured_*` 与 `in.num_*` 分离），宿主测试 case1/2/4/5 正是 3×3 输入 |
| 4 | 帧所有权 | `FrameNormalizer` 对 I420 **零拷贝透传**、非 I420 走 `toI420()`；`JavaI420Buffer` 及转换结果为 direct | ✅ 一致：本层只在 `nativeEncode` 调用期内借用平面并**同步拷贝编码结果**，不持有平面指针（契约 §6.7） |
| 5 | 真机排障对照锚点 | `encoded_plane_rejected`（Kotlin） | `nativeEncode_rejected`（C++）——两者同时出现即为帧形态超出预期，带日志字段互查 |

### 2.7 排障日志配对锚点（第三轮确认，2026-09-13）

真机联调按「同一时刻两侧日志并排看」定位，字段已对齐：

| 场景 | Kotlin（t8，`encoder/Vp9VideoEncoder.kt` / `webrtc/FrameNormalizer.kt`） | C++（本层） |
|---|---|---|
| 平面前置条件不满足 | `encoded_plane_rejected`（`reason=non_direct_buffer / bad_dimensions / plane_capacity_y\|u\|v`，附 `w/h/stride_y,u,v/direct`） | `nativeEncode_rejected reason=…`（`jni/vp9_encoder_jni.cpp`，返回 `-4`） |
| 帧格式转换失败（**t8 新增**） | `to_i420_failed`（`Vp9VideoEncoder.encode` 内转换返回 null）/ `frame_convert_failed`（`FrameNormalizer`，`reason=toI420_null`） | 无对应（本层收不到这样的帧；出现即说明上游转换失败） |
| `SetRates` 失败 | `setrates_failed`（`rc/s/t/len/total_bps/fps`） | `nativeSetRates_rejected reason=length_mismatch\|bad_dim`（`<filesDir>/logs/native.log`） |
| 正常闭环 | `setrates`（`total_bps/fps/s/t`，契约 §9.1 固定事件名） | `setrates` + `layer_bps` + `ts_target_kbps l0/l1/l2 rc_target_kbps` + `vpx_apply` |

**引用策略（重要，2026-09-13 起）**：本报告**只按事件名引用 Kotlin 侧锚点，不再写行号**（行号会随 t8 改文件位移，属易碎引用）。
如需行号，仅作当日快照使用 —— t8 在 26 处 Kotlin API 修复中给 `encode()` 加了一个"`toI420()` 可空性"失败分支（+5 行），
故 2026-09-13 快照为：`encoded_plane_rejected` **:183**、`len == S*T` 不变量注释 **:243**（`:245` 为 3/3 说明）、`setrates_failed` **:260**
（旧号 178/238/255 已失效；内容未变，纯位移）。

**结构不变量（t8 已固化）**：`setRateAllocation` 的 `len` 与 `S/T` 取自**同一个矩阵**，故 `len == S*T` 由构造保证
→ 本层的 `nativeSetRates_rejected reason=length_mismatch` **不可能由 t8 触发**；该分支仅作为越界/被篡改输入的最后防线保留（契约 §6.3 要求本层必须校验）。

> 本层独立复核（2026-09-13，两轮）：① `grep -rn "encoded_plane_rejected\|setrates_failed\|to_i420_failed\|frame_convert_failed" app/src/main/kotlin` 全部命中；
> `setrates_failed` 字段确为 `rc/s/t/len/total_bps/fps`；不变量注释与 3/3 说明在位。② t8 的 26 处 Kotlin API 修复**未触碰本层域**：
> `find app/src/main/kotlin/.../nativebridge app/src/main/cpp -newermt <修复窗口>` = **0 文件**；`external fun` 计数仍 **15**；
> `SPATIAL_LAYERS=1`/`TEMPORAL_LAYERS=3` 与 9 处 JNI 调用点形状、`statusOf()` 精确映射（`ERR_PARAMETER`/`UNINITIALIZED`…）均未变；
> `createNative`/`createNativeVideoEncoder` 改名只发生在 t8 自有类内，`nativebridge/**` 无此符号。
> t8 的 `t7iface.sh` 已扩至 **19/19**（新增不变量注释、3/3 说明、`setrates_failed` 字段、成功路径 `setrates`），与本层 §2.5/§2.6 逐条对应。

> 结论：契约 §6 的 A/B 两侧在**签名**（§2.5）、**运行时约定**（§2.6）与**排障锚点**（§2.7）三个层面均已双向闭合。
> t8 另提供守卫脚本 `t7iface.sh`（19/19），覆盖预检存在性/容量公式/原因串/SetRates 形状/状态码映射/归一化不破坏 direct。


---

## 3. 编码器注入链路、JNI 符号与线程模型（契约要求写清）

### 3.1 注入链路（唯一路线 A1）

```
WebRtcEngine.kt(android-dev)
  └─ PeerConnectionFactory.builder().setVideoEncoderFactory(Vp9VideoEncoderFactory())   ← 契约 §7.1
       └─ Vp9VideoEncoderFactory.createEncoder(VideoCodecInfo("VP9")) → Vp9VideoEncoder
            └─ libwebrtc VideoStreamEncoder（native）
                 ├─ SetRates → VideoEncoderWrapper::SetRates
                 │    └─ Java_VideoEncoder_setRateAllocation → Vp9VideoEncoder.setRateAllocation()
                 │         └─ NativeVp9Encoder.nativeSetRates(handle, int[], S, T, total, fps)   ← 表 A-2
                 │              └─ Vp9Encoder::SetRates()                      [本层]
                 │                   ├─ LayerBitrateAllocator::Compute() 矩阵→ss/ts/layer/rc (kbps)
                 │                   ├─ NLOG_INFO("bitrate", setrates/layer_bps/ts_target_kbps/vpx_apply)
                 │                   ├─ vpx_codec_enc_config_set()        ★ 动态码率闭环
                 │                   └─ encoder_bitrate.csv 落一行
                 └─ Encode(VideoFrame, frame_types) → VideoEncoderWrapper::Encode
                      └─ Java_VideoEncoder_encode → Vp9VideoEncoder.encode()
                           ├─ NativeVp9Encoder.nativeEncode(handle, y, u, v, w, h, strides, ts, rot, key)
                           │    └─ Vp9Encoder::Encode → vpx_codec_encode(VPX_DL_REALTIME)
                           │         └─ vpx_codec_get_cx_data → **立即拷贝**到内部缓冲
                           ├─ NativeVp9Encoder.nativeGetEncodedFrameSize(handle)
                           ├─ NativeVp9Encoder.nativeCopyEncodedFrame(handle, dst, meta)
                           └─ EncodedImage.builder()…createEncodedImage() → callback.onEncodedFrame(...)
```

**注入点唯一**：`PeerConnectionFactory.builder().setVideoEncoderFactory(...)`（Kotlin）。本层只提供
「被 JNI 调用的编码器」，**不参与** factory 创建、**不链接** libwebrtc（契约 §4.2/§5.1）。

### 3.2 本层导出的 JNI 符号

`JNI_OnLoad` / `JNI_OnUnload`（`-fvisibility=hidden` 下仅此二者导出，实测 `llvm-nm -D` 输出仅这两行）。
15 个方法通过 `RegisterNatives` 绑定，因此没有 `Java_...` 名字修饰符号。

### 3.3 线程模型

| 调用面 | 线程 | 本层处理 |
|---|---|---|
| `nativeInit/Encode/SetRates/RequestKeyFrame/CopyEncodedFrame/GetEncodedFrameSize` | libwebrtc **编码线程**（`video_encoder_wrapper` 先 `AttachCurrentThreadIfNeeded` 再调 Java） | 全程持 `g_registry_mutex`（句柄注册表锁），单帧同步阻塞；日志/CSV 走 `NativeLogger` 自己的互斥锁 |
| `nativeRelease` | 可能是 Kotlin 侧任意线程 | 与上者同一把注册表锁 → 不会 use-after-free；重复调用返回 `OK`（幂等） |
| `nativeDetect` | JNI 调用线程（**立即返回**） | 探测跑在 `NatDetector` 自有线程（`nat-detect`），`Cancel()` 置取消位并 `join`（幂等） |
| `nativeCancel` | JNI 调用线程 | 同上 |
| 表 B-1 回调 | NAT 探测线程 / 编码线程 | `ScopedJniEnv`：`GetEnv` 成功则不 detach（Java 线程），`JNI_EDETACHED` 才 `Attach` 且析构时 `Detach`（契约 §6.5） |

- 句柄安全：`nativeCreate` 建对象并登记；所有方法先查注册表，未知/已释放句柄返回 `UNINITIALIZED(-7)`
  且不解引用（契约 §6.7「失效句柄不得崩溃」）。
- 不使用 C++ 异常与 RTTI（`-fno-exceptions -fno-rtti`）；`new (std::nothrow)`；无 `dynamic_cast`。

---

## 4. SetRates 动态码率（学习核心）设计与实测

### 4.1 换算规则（`encoder/layer_bitrate_allocator.cpp`，逐行中文注释）

1. 输入是 JNI 展平的 `[spatial][temporal]` bps 矩阵（契约 §6.3）；A1 路线下通常只有 `[0][0]` 非 0。
2. 总码率 `total` = 矩阵求和，全 0 时回退 `total_bps`，再兜底 300 kbps。
3. 若矩阵**带真实时序分层数据**（t>0 列非 0）→ 直接采用；否则按契约 §5.6 冻结策略切分：
   增量份额 `{40%,30%,30%}`，即累计目标 `ts_target_bitrate = {40%,70%,100%}×total`。
4. 逐层累计写入 `ts_target_bitrate[]`（单调不减、末层恰好等于 `rc_target_bitrate`）、
   `layer_target_bitrate[s*T+t]`（libvpx 索引约定 `sl*ts_number_layers+tl`）、`ss_target_bitrate[0]`。
5. `ts_rate_decimator = {2,1,1}`（T=3，契约冻结）；每层最小 4 kbps 防倒挂。

**已用源码核实的两个关键边界（写入报告供 verifier 参考）**
- `ts_rate_decimator = {2,1,1}` 在 `vp9_cx_iface.c:274-277` 的校验下**合法**（只要求 `decimator[T-1]==1`
  且 `decimator[0]==2*decimator[1]`）。
- **`layer_target_bitrate[]` 只在空间 SVC（`ss_number_layers>1`）时被 libvpx 使用**
  （`vp9/encoder/vp9_svc_layercontext.c:131/206/249/277`）；本项目 L1T3 下真正生效的"总闸门"是
  `rc_target_bitrate`，时序层预算由 `temporal_layering_mode` 决定。因此本层**额外显式设置**
  `temporal_layering_mode`（T=3→`MODE_0212`、T=2→`MODE_0101`、T=1→无分层），与 libvpx 在
  `vp9_cx_iface.c:703-708` 的自动选择一致；契约冻结的 ss/ts/layer_target_bitrate 仍完整写入（学习点不缩水）。

### 4.2 日志点（契约 D6「务必打印」）

```
INFO native bitrate setrates total_bps=750000 fps=30 s=3 t=3
INFO native bitrate layer_bps s0t0=750000 s0t1=0 s0t2=0 s1t0=0 ... s2t2=0
INFO native bitrate ts_target_kbps l0=300 l1=525 l2=750 rc_target_kbps=750
INFO native bitrate vpx_apply ss0=750 layer0=300 layer1=525 layer2=750 decimator=2,1,1
```
`encoder_bitrate.csv`（每次 `SetRates` 与每次成功 `Encode` 各一行）：
`ts_ms,total_bps,fps,s,t,layer_0_0,layer_0_1,layer_0_2,ts_kbps_0,ts_kbps_1,ts_kbps_2,rc_target_kbps,encoded_bytes,key,qp`

---

## 5. NAT 探测（RFC5780）

- `StunClient`：Binding Request 组装（magic cookie 0x2112A442、96 位事务 ID）、`CHANGE-REQUEST`
  （change IP=0x02 / change port=0x04，32 位掩码低 16 位）、`XOR-MAPPED-ADDRESS`、
  `MAPPED-ADDRESS`、`RESPONSE-ORIGIN`、`OTHER-ADDRESS` 解析；接收用 100 ms 切片轮询以便及时响应取消。
- `NatDetector`：四步判定（Test I → II → III → IV），**映射行为优先于过滤行为**：
  任一测试映射端口与 Test I 不同 → `Symmetric`；Test II 有响应 → `FullCone`；
  Test III 有响应 → `RestrictedCone`；否则 Test IV 映射一致 → `PortRestrictedCone`；
  STUN 不可达 → `Unknown`；`mapped == 本端地址` → `Open`。
- 证据串 ≤512 字节：`mapped=1.2.3.4:54321;testI=resp;testII=timeout;testIII=resp`，
  每步另有 `nat_request` 日志（请求参数 + 响应地址 + RTT）。
- **健壮性改进（非契约要求，必要）**：`Bind()` 绑定 `INADDR_ANY` 时 `getsockname` 只给出 `0.0.0.0`，
  会导致 `Open` 判定失效；新增 `StunClient::DiscoverLocalAddress()`——用临时 UDP socket
  `connect()` 到 STUN 服务器再 `getsockname()` 取真实出口 IP（不改变端口）。实测该路径生效
  （日志 `stun_local_address ip=127.0.0.1 ... source=route_probe ok=1`）。

---

## 6. 受控偏离登记（契约 §5.1.1 要求 native-dev 写进报告）

| 偏离项 | 原文档 | 契约裁定 | 本层实现 |
|---|---|---|---|
| 编码器不实现 `webrtc::VideoEncoder` | ADR-002、`doc/11` §5 | 自研 `Vp9Encoder` + Kotlin 侧 `org.webrtc.VideoEncoder` 适配器 | ✅ 已按 A1 实现，且不链接 libwebrtc |
| SVC 空间层降级为 L1T3 | ADR-002、`doc/02` §6、`doc/11` §5.2 | `ss_number_layers=1`、`ts_number_layers=3` | ✅ `nativeInit` 拒绝 S≠1；注释指向 §5.6 |
| 删除 C++ `PeerConnectionManager`/`StatsCollector`/`VideoSinkAdapter` | `doc/11` §4/§7 | 改由 Kotlin 用 `org.webrtc` 实现 | ✅ 未创建这些文件 |

**未授权偏离：无**（本层未偏离任何冻结值；下述 API 名称适配属于契约 §11 允许的"按真实版本适配并登记"）。

---

## 7. libwebrtc / libvpx API 假设清单

### 7.1 已用真实头文件/源码核实并适配（**已确认**）

| # | 契约/doc/11 写法 | 真实情况（源码位置） | 本层处理 |
|---|---|---|---|
| 1 | `VP9E_GET_LAST_QUANTIZER`（契约 §5.5、`doc/11`） | libvpx 无此控制；VP9 复用 `VP8E_GET_LAST_QUANTIZER`（`vp9/vp9_cx_iface.c:2260` 控制表） | 改用 `VP8E_GET_LAST_QUANTIZER`，失败记 `qp=-1` |
| 2 | `pkt->data.frame.spatial_layer_id/temporal_layer_id`（`doc/11` §5.2） | 本版本 `vpx_codec_cx_pkt_t.data.frame` **无**这两个字段（只有 `width[]`/`height[]`/`spatial_layer_encoded[]`） | 空间层恒 0（ss=1）；时序层按 `MODE_0212` 帧序 `0,2,1,2` 回推，仅用于 `outMeta[4]`/日志（Kotlin 的 `EncodedImage` 无层索引字段，不影响解码） |
| 3 | `ts_rate_layer_decimator = {2,1,1}`（契约 §5.6） | `vp9_cx_iface.c:274-277` 校验通过 | 按契约使用 |
| 4 | 动态改分辨率 | `vp9_cx_iface.c:878` 允许 `g_lag_in_frames<=1 && pass==ONE_PASS` 时改 `g_w/g_h` | 分辨率变化时 `vpx_codec_enc_config_set` + INFO 日志 |
| 5 | `vpx_img_wrap` + 平面指针 | `VPX_PLANE_*`/`stride[]` 可覆写（`vpx_image.c` 支持 `img_data=nullptr`） | 零拷贝借用平面；`Encode` 内同步读取 |
| 6 | vpx 缓冲复用 | `vpx_codec_get_cx_data` 的 `buf` 下次 encode 会被复用 | 立即 `encoded_.assign()` 拷贝（契约 §5.5） |
| 7 | 编码时间基 | `g_timebase` 默认 1/30 | 设为 `1/1000000`（µs），`pts=capture_time_ns/1000`、`duration=1e6/fps`，pts 严格单调 |
| 8 | `-fno-exceptions` 下的 `new`/`std::thread` | 可能 terminate | `new (std::nothrow)`；`std::thread` 与 libwebrtc 用法一致，未额外兜底（已登记） |

### 7.2 未能验证的假设（**必须在真机/t5 产物到位后复核**）

| # | 假设 | 风险 | 复核方式 |
|---|---|---|---|
| A1 | Kotlin `Vp9VideoEncoder.encode()` 传入的 3 个 `ByteBuffer` 是 **direct**、且 `capacity ≥ stride*(rows-1)+row_bytes` | 不满足时本层返回 `-4`；t8 按 §6.6 **显式数值映射**为 `VideoCodecStatus.ERR_PARAMETER`（**不是**笼统的 `ERROR`，2026-09-13 已与 t8 对齐）；且 t8 已在进 JNI **之前**做同口径预检（`Vp9VideoEncoder.kt` `validatePlanes`），正常路径不会产生"无因 -4" | 真机日志对照：C++ `nativeEncode_rejected reason=non_direct_buffer/bad_dimensions/plane_capacity_y|u|v` ↔ Kotlin `encoded_plane_rejected reason=...`（字段 w/h/stride_y_u_v/direct 一一对应） |
| A2 | `allocation.bitratesBbs` 展平后 `S*T` 与实际传入一致（SDK 运行时给 **3×3**，仅 `[0][0]` 非 0；即 `nativeSetRates` 的 S/T = **3/3**，而 `nativeInit` 的 S/T = 1/3） | 不符则返回 `-4` | **已与 t8 确认**：`flat[s * temporal + t] = matrix[s][t]`、`S=matrix.size`、`T=matrix[0].size`，长度严格 = `S*T`；本层 `NativeSetRates` 接受 S∈[1,3]/T∈[1,3] 并把「矩阵维度」与「编码器分层 1/3」分开处理（见 §4.1），宿主测试 case1/2/4/5 即以 3×3 输入验证 |
| A3 | 编码线程进入 JNI 时已 attach（`ScopedJniEnv` 兼容两种） | 极低 | 无需动作（两种都处理） |
| A4 | `frame.rotation` 由 Kotlin 传入；I420 平面已是采集方向 | 仅信息性：本层不做旋转，`EncodedImage.setRotation(0)`（契约 §5.4/§6.6） | 真机确认画面方向；若需旋转，属 t8 的 `FrameNormalizer` 职责 |
| A5 | 每帧写日志/CSV 的开销不造成 >33 ms 抖动 | 低端机可能 | 本层已把逐帧日志降为 DEBUG（release 默认 INFO 不输出），帧耗时 >33 ms 记 WARN；CSV 每帧一行 |
| A6 | `nativeInit` 只在编码线程、`nativeRelease` 只在会话结束调用 | 低 | 已用注册表锁防御并发 |
| A7 | 真机 logcat 与 `<filesDir>/logs/native.log` 双写路径可写 | 低 | t8 已保证 `mkdirs()`；本层失败只打 ERROR 不崩溃 |

### 7.3 libwebrtc `RTC_LOG` 路由到同一文件（用户 D6 要求说明）

- **原生侧无法路由**：本库**未链接 libwebrtc**（契约 §4.2），进程内的 `RTC_LOG` 属于
  `libjingle_peerconnection_so.so`，其日志接收器只能由 Java 侧注册。
- **正确做法（契约 §9.7，属 t8）**：`PeerConnectionFactory.InitializationOptions.Builder
  .setInjectableLogger(LibwebrtcLoggable, Severity)`；注入 sink 会抬高 `g_min_sev`，使 release
  （`NDEBUG ⇒ LS_NONE`）下 `RTC_LOG` 真正执行，日志写 `<filesDir>/logs/webrtc.log`（`layer=webrtc`）。
- **生效范围**：`webrtc.log` 覆盖 libwebrtc 内部 Java+native 日志；`native.log` 只含本层日志
  （编码器/NAT/JNI/日志设施）。二者**同一目录、同一行格式**，导出 zip 一并打包（契约 §9.5）。
- 本层已按 §9.7 保证：不设 `rtc_disable_logging=true`（该开关属 t5 GN 参数，见 §11.2 回写清单）。

---

## 8. 未决问题（需 captain / t8 / t11 关注）

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| N1 | ~~`FileLogger.kt` 注释写"native 侧前缀 `native-`"~~ | — | **已失效/已解决（2026-09-13，t8 复核确认）**：该注释只存在于 t14 骨架阶段，t8 按契约 §9 重做 `FileLogger` 时已整体改写为「固定基名 + 序号滚动」。本层独立复验：`grep -rn "native-" app/src/main/kotlin` = **0 命中**；`NativeLog.kt:121` 实发 `nativeInit(path, "native", level.code, 2*1024*1024, 3)`；`LogExporter.kt` 打包 `app*.log / native*.log / webrtc*.log / encoder_bitrate.csv`。**verifier 不要再追这条**；本层代码无需改动 |
| N2 | `nativeInit` 的 `fileNameBase` 由 Kotlin 传值：只有传 `"native"` 才得到契约路径 | 传其它值会得到 `<base>.log` | **已闭合**：t8 实传字面量 `"native"`（`NativeLog.kt:121`，基名常量 `BASE_NAME`），与契约 §9.2/§9.4 一致 |
| N3 | `outMeta[4]`（temporalIndex）为帧序回推值，非 vpx 回传 | 仅影响展示 | 若需精确值，升级 libvpx 或用 `VP9E_GET_SVC_LAYER_ID`（本轮不做） |
| N4 | `nativeInit` 前若 Kotlin 调用编码器，日志只落 logcat + 一次 ERROR（契约 §9.4 允许） | 首次排障缺文件日志 | **已闭合**：t8 的 `NativeLog.ensureInitialized` 在 `WebRtcDemoApp.onCreate` 与 `WebRtcEngine.initialize`（创建 factory/编码器之前）双重调用，失败只写 logcat 不抛异常 |
| N5 | ~~t5 的 `libwebrtc-java.jar` / `libjingle_peerconnection_so.so` 尚未产出~~ | — | **已闭合（2026-09-13）**：终版 APK 内已含 `lib/arm64-v8a/libjingle_peerconnection_so.so`（12,946,912 B，sha256 `757cef81…`，本层独立从 APK 抽出复核，见 §9.1.2）；t15 的 26 处 Kotlin 修复经三方核对未触碰 `nativebridge/**` 与 `app/src/main/cpp/**`。本层无需改动 |
| N6 | 本层新增的 `tests/host/`（验证资产）不在契约 §2.2 文件清单内 | 可能引起"超清单文件"疑问 | 已在 `tests/host/README.md` 与本文声明：**不参与 CMake/AGP 构建**，可随时删除 |

---

## 9. 验证证据（命令 + 结果）

### 9.1 真实构建（t5 的 libvpx aarch64 产物已就绪）

```bash
SDK=/data/dsh/home/workspace/android-sdk
REPO=/data/dsh/home/workspace/code/webrtc-demo
$SDK/cmake/3.22.1/bin/cmake -S $REPO/app/src/main/cpp -B /tmp/t7build_real -G Ninja \
  -DCMAKE_MAKE_PROGRAM=$SDK/cmake/3.22.1/bin/ninja \
  -DCMAKE_TOOLCHAIN_FILE=$SDK/ndk/26.1.10909125/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DWEBC_THIRD_PARTY=$REPO/third_party
ninja -C /tmp/t7build_real
```
结果：`CMAKE_CONFIGURE_OK` / `NINJA_OK`；构建日志 warning/error 计数 **0**；
产物 `libwebrtcdemo_native.so` = 2,115,168 B。
`llvm-readelf -h`：`ELF64 / DYN / AArch64`。
`llvm-nm -D --defined-only`：仅 `JNI_OnLoad`、`JNI_OnUnload`。
`llvm-nm -D -u | grep -ciE "webrtc|jingle"` = **0**（契约 §4.2：不依赖 libwebrtc）。
`NEEDED`：`liblog.so`、`libandroid.so`、`libm.so`、`libc++_shared.so`、`libdl.so`、`libc.so`。

> 说明：t5 在本任务执行期间产出 `third_party/libvpx/{lib,include}`；`libwebrtc` 仍未产出（本层不需要）。
> 早期在 t5 产物未就绪时，曾用「假 libvpx 静态库（仅用于配置/编译/链接链路验证）」跑通同一 CMake 流程，
> 结论一致；真实产物到位后已重跑（上表）。

### 9.1.1 AGP 真实构建链路（t10 实测 + 本层独立复验，2026-09-13）

上表是"手工 cmake+ninja"，而**契约 §4.1 的正式路径**是 AGP 的 `externalNativeBuild`
（`app/build.gradle.kts` 注入 `-DWEBC_THIRD_PARTY`）。t10 已实跑该路径并成功：

```bash
./gradlew --no-daemon :app:externalNativeBuildDebug
#> Task :app:configureCMakeDebug[arm64-v8a]
#> Task :app:buildCMakeDebug[arm64-v8a]
#> BUILD SUCCESSFUL in 34s
```

本层对其产物做了独立复核（`app/build/intermediates/cmake/debug/obj/arm64-v8a/libwebrtcdemo_native.so`）：

| 检查 | 结果 |
|---|---|
| 大小 / 时间 | 2,206,528 B（debug，含调试信息；手工 Release 构建为 2,115,160 B，差异符合预期） |
| `llvm-readelf -h` | `ELF64 / DYN / AArch64` |
| 导出符号 | 仅 `JNI_OnLoad`、`JNI_OnUnload` |
| `NEEDED` | `liblog.so, libandroid.so, libm.so, libc++_shared.so, libdl.so, libc.so` |
| 未定义符号含 `webrtc|jingle` | **0** |

> 结论：契约 §4.2 的「自有库不得链接 libwebrtc / libjingle」在 **AGP 真实构建链路的二进制层面**得到证实；
> 同时确认 `JNI_OnLoad` 注册入口在正式构建中导出（`RegisterNatives` 生效前提）。
> 该次 AGP 构建的**失败点只在 Kotlin 层**（t8 与 `libwebrtc-java.jar` 的 API 差异），与本层 C++/CMake/NDK/libvpx 链路无关（t10 判定）。
> t15 修复完成后 t10 已产出终版 APK，证据与"APK 内实体"复核见 §9.1.2。

### 9.1.2 APK 内实体证据（t10 终版，本层独立从 APK 抽出复验，2026-09-13）

t10 完成 `clean assembleDebug`：`app/build/outputs/apk/debug/app-debug.apk` = 33,260,234 B，
sha256 `c72d366706569b6dab5689200bc0902ce94fb7241b238a401e61fa5e745caa96`（用 t16 的 v61 jar）。

**本层独立复验方式**：容器内无 `unzip/bsdtar/7z`，因此用 Node.js 解析 ZIP 中央目录后提取条目（支持 STORED/DEFLATED），
再对提取物做 sha256 与 ELF 检查（命令与脚本见注）。

| 条目 | 大小 | sha256（本层实测） | 与 t10 登记值 |
|---|---|---|---|
| `lib/arm64-v8a/libwebrtcdemo_native.so` | 1,231,512 B | `e9b66cc98d97454c32d535cb670bae251d381c383fff98ea11d922cb798f35f5` | ✅ 一致 |
| `lib/arm64-v8a/libjingle_peerconnection_so.so` | 12,946,912 B | `757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e` | ✅ 一致 |
| `app-debug.apk` | 33,260,234 B | `c72d366706569b6dab5689200bc0902ce94fb7241b238a401e61fa5e745caa96` | ✅ 一致 |

对 **APK 内** `libwebrtcdemo_native.so`（即真正随包发布的产物）的 ELF 检查：

| 检查 | 结果 |
|---|---|
| `llvm-readelf -h` | `ELF64 / DYN / AArch64` |
| `llvm-nm -D --defined-only` | 仅 `JNI_OnLoad`、`JNI_OnUnload` |
| `NEEDED` | `liblog.so, libandroid.so, libm.so, libc++_shared.so, libdl.so, libc.so` |
| 未定义符号含 `webrtc|jingle` | **0** |
| 同包内 `libjingle_peerconnection_so.so` | `ELF64/AArch64`，导出 `JNI_OnLoad`（194 个动态符号）= 官方 SDK native 侧在位（A1 的 `VideoEncoderWrapper` 前提） |

> 与 §9.1.1 的 2,206,528 B **不是矛盾**：那是 `intermediates/cmake/debug/obj/` 下**含调试信息**的中间产物；
> APK 内是经 AGP `stripDebugDebugSymbols` 处理后的 **1,231,512 B**。两者源自同一次构建。
> 至此契约 §4.2「自有库不得链接 libwebrtc/libjingle」拥有**三层证据**：手工 Release 构建（§9.1）、
> AGP `externalNativeBuild` 中间产物（§9.1.1）、**APK 内实体**（本节）。

> 复现命令（容器内）：
> ```bash
> node -e '<解析 ZIP 中央目录并提取指定条目的内联脚本>' app-debug.apk lib/arm64-v8a/libwebrtcdemo_native.so /tmp/apk_native.so
> sha256sum /tmp/apk_native.so app/build/outputs/apk/debug/app-debug.apk
> $NDK_BIN/llvm-nm -D --defined-only /tmp/apk_native.so
> ```



### 9.2 格式与编译

```bash
$SDK/ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64/bin/clang-format \
  --dry-run --Werror --style=file $(find app/src/main/cpp -name '*.cpp' -o -name '*.h')
# → 无输出（CLANG_FORMAT_CLEAN）
```
每个 `.cpp` 以 `-std=c++17 -fno-exceptions -fno-rtti -Wall -Wextra -O2` 单独编译（aarch64-linux-android26）
→ 0 warning / 0 error。

### 9.3 宿主侧逻辑测试（在宿主机 172.21.0.219 用 g++ 13.3 执行共享工作区源码）

```bash
ssh -i ~/.ssh/id_ed25519 root@172.21.0.219 -p 5766 \
  'cd /opt/dsh-workspaces/code/webrtc-demo/app/src/main/cpp/tests/host && \
   bash run_host_tests.sh /opt/dsh-workspaces/code/webrtc-demo'
```

- `layer_bitrate_and_native_log_test`：**failures=0**（26 项 ok）——750 kbps → `ts 300/525/750`、`rc 750`、
  `layer_target` 同步、`ss0==rc`、decimator `{2,1,1}`；10 kbps → 每层 ≥4 kbps 且单调；真实分层矩阵等价；
  T=1；全 0 回退；日志行格式正则全匹配（`bad_lines=0`）、级别切换、2 MiB×3 滚动（生成 `.1`/`.2`）、
  CSV 表头/行正确。
- `nat_detector_host_test`：**failures=0**（9 项 ok）——假 STUN 服务器驱动四步判定：
  `Open` / `FullCone` / `RestrictedCone` / `PortRestrictedCone` / `Symmetric`（Test IV 映射变化）/
  `Symmetric`（Test II 映射变化）/ `Unknown`（不可达）；`CHANGE-REQUEST` 两位实测发出（ip=1, port=2）。

### 9.4 契约验收项自检（native 相关）

| 验收项 | 命令 | 结果 |
|---|---|---|
| V03/V04 | `ls app/src/main/cpp/CMakeLists.txt`；无仓库根 `native/` | ✅ |
| V15 | `grep -rn "SelfVp9Libvpx" app/src/main/cpp \| wc -l` | 4 |
| V17 | `grep -rn "webrtc::VideoEncoder" app/src/main/cpp \| wc -l` | 0 |
| V18 | `grep -n "libwebrtc\|libjingle" CMakeLists.txt` | 仅注释行 |
| V19/V20 | `grep -c "vpx\|webrtcdemo_native"`=15；`WEBC_THIRD_PARTY` 两文件命中 | ✅ |
| V21 | 4 个符号在 `encoder/*.cpp` 命中 | 24 / 24 |
| V22 | `cfg_.ss_number_layers = 1;` + §5.6 中文注释 | ✅ |
| V23 | `grep -c "ts_ms,total_bps,fps" log/native_log.cpp` | 1 |
| V28 | `grep -c '{"' jni/*_jni.cpp` 求和 | **15**（4+9+2） |
| V29 | 4 个冻结类名 | ✅ |
| V30 | `NO_OUTPUT/ERR_PARAMETER/UNINITIALIZED` | ✅ |
| V43 | `2 * 1024 * 1024`（2 MiB × 3） | ✅ |

---

## 10. 给 t8 / t10 / t11 的接口注意事项

1. **t8（android-dev）**
   - `NativeLoader`：`System.loadLibrary("webrtcdemo_native")`；`WebRtcDemoApp.onCreate` 第一步调
     `NativeLog.nativeInit(logDir, "native", level, 2*1024*1024, 3)`（`logDir` 必须已 `mkdirs()`）。
   - `nativeEncode` 的 3 个 ByteBuffer 必须 direct，容量 ≥ `stride*(rows-1)+row_bytes`，否则返回 `-4`。
   - `nativeSetRates` 的 `layerBitratesBps` 长度必须 = `S*T`，索引 `s*T+t`。
   - `nativeRelease` 后把 `handle` 置 0（幂等，但避免误用）。
   - `NativeCallbacks.onNatTypeDetected` 必须在主线程外收到后**立刻切主线程**再更新 UI（契约 §6.5）。
   - ~~`FileLogger.kt` 注释里的 `native-` 前缀与契约不符~~ → **已由 t8 修复并经本层复验，见 §8 N1（已解决）**；
     现行实现是 `native.log` + 序号滚动，与契约 §9.2 一致。
2. **t10（env-installer）**：本层 CMake 由 `externalNativeBuild` 驱动，`-DWEBC_THIRD_PARTY` 已由
   `app/build.gradle.kts` 注入；构建命令见契约 §4.1。本层只需 `third_party/libvpx`（已有）。
3. **t11（verifier）**
   - 可直接复跑 §9.1/§9.2/§9.3 三条命令；
   - `libwebrtcdemo_native.so` 的导出符号应恰为 `JNI_OnLoad`/`JNI_OnUnload`；
   - 编码码率曲线证据 = `<filesDir>/logs/encoder_bitrate.csv`（表头与字段顺序见 §4.2）；
   - 不要按"空间层 SVC 可协商"验收（契约 C10/C27）——本层 `ss_number_layers` 恒为 1。

---

## 11. 追加：与契约 §11.2（回写 t5）的交叉确认

| 契约 §11.2 要求 | 本层视角的确认 |
|---|---|
| 产物必须含 `libvpx.a` + `include/vpx/*.h` | ✅ 已就位（`third_party/libvpx/{lib,include}`，aarch64，含 `vpx_codec.h/vpx_encoder.h/vpx_image.h/vp8cx.h`），本层已真实链接 |
| 不得设 `rtc_disable_logging=true` | 本层不参与 t5 GN；已在 §7.3 记录其与日志契约的关系 |
| libvpx 必须同 NDK（26.1.10909125）+ `--enable-pic` + 静态 | 本层用 NDK 26.1.10909125 工具链链接该 `.a` 成功（无符号/ABI 冲突），反向印证一致 |

## 12. 复现入口速查

```text
源码            app/src/main/cpp/                       （契约 §2.2）
构建            app/src/main/cpp/CMakeLists.txt         （契约 §4.2）
宿主验证        app/src/main/cpp/tests/host/run_host_tests.sh
本报告          reports/07-native-dev.md
```

---

## 13. 报告修订记录

| 版本 | 日期 | 变更 | 触发 |
|---|---|---|---|
| v1.0 | 2026-09-13 | 初版（实现 + 构建 + 宿主测试证据） | t7 完成 |
| v1.1 | 2026-09-13 | ① 新增 §2.5「B 侧 nativebridge 签名互验」（t8 类已产出，15+2 逐字一致、全部 `@JvmStatic`）；② §8 N1 标为**已解决/失效**（原文针对 t14 骨架注释；本层复验 `grep -rn "native-" app/src/main/kotlin` = 0 命中）；③ N2/N4 因 t8 已按契约实现而闭合；④ 更新 §10 给 t8 的注意事项与 §0 未验证项 | android-dev 的复核反馈（2026-09-13） |
| v1.2 | 2026-09-13 | ① **更正 §7.2 A1 的状态码表述**：`-4` 经 t8 的 `statusOf()` 显式映射为 `VideoCodecStatus.ERR_PARAMETER`（此前误写为笼统 `ERROR`）；② A2 补入「运行时矩阵 3×3、`nativeSetRates` 的 S/T=3/3、`nativeInit` 的 S/T=1/3」的已确认事实与宿主测试对应关系；③ 新增 **§2.6 运行时接口约定第二轮互验**（平面预检/状态码映射/SetRates 形状/帧所有权/排障锚点 5 项全部一致） | android-dev 的第二轮互验反馈（预检落地 + 状态码澄清） |
| v1.3 | 2026-09-13 | 新增 **§2.7 排障日志配对锚点**：Kotlin `encoded_plane_rejected`/`setrates_failed`/`setrates` ↔ C++ `nativeEncode_rejected`/`nativeSetRates_rejected`/`setrates`+`ts_target_kbps`；记录 t8 固化的结构不变量（`len == S*T` 由构造保证、运行期 S/T=3/3，故 `length_mismatch` 分支不可能由 B 侧触发，本层仍按 §6.3 保留校验）；t8 守卫脚本 `t7iface.sh` 15→**19/19** | android-dev 的第三轮互验反馈 |
| v1.4 | 2026-09-13 | 新增 **§9.1.1 AGP 真实构建链路**（t10 实跑 `:app:externalNativeBuildDebug` BUILD SUCCESSFUL；本层独立复核产物：2,206,528 B / ELF64 AArch64 / 仅导出 `JNI_OnLoad`+`JNI_OnUnload` / NEEDED 无 libwebrtc·libjingle / 未定义符号含 webrtc\|jingle = 0），把契约 §4.2 的二进制层证据补齐到**正式 AGP 路径** | env-installer（t10）实测通报 |
| v1.5 | 2026-09-13 | ① **§2.7 改为按事件名引用**（行号易碎）：记录 t8 新增失败分支导致的行号位移快照（`encoded_plane_rejected` 178→**183**、不变量注释 238→**243**、`setrates_failed` 255→**260**，内容未变）；② 新增 t8 的两个可诊断事件 `to_i420_failed` / `frame_convert_failed` 入配对表；③ 记录本层对 26 处 Kotlin API 修复的**域隔离复核**（`nativebridge/**` 与 `app/src/main/cpp/**` 0 文件被触碰、`external fun` 仍 15、`SPATIAL_LAYERS=1`/`TEMPORAL_LAYERS=3`、9 处 JNI 调用形状与状态码映射未变、`createNative` 改名只在其自有类内） | android-dev 的边界交代 + 锚点位移通报 |
| v1.6 | 2026-09-13 | 新增 **§9.1.2 APK 内实体证据**：本层用 Node 解析 ZIP 独立从终版 `app-debug.apk`（33,260,234 B / sha256 `c72d3667…`）抽出 `libwebrtcdemo_native.so`（1,231,512 B / sha256 `e9b66cc9…`）与 `libjingle_peerconnection_so.so`（12,946,912 B / sha256 `757cef81…`），三者与 t10 登记值**逐字节一致**；APK 内库仅导出 `JNI_OnLoad`/`JNI_OnUnload`、NEEDED 无 libwebrtc·libjingle、未定义符号含 webrtc\|jingle = 0 → 契约 §4.2 证据升级为**三层**（手工 Release / AGP 中间产物 / APK 内实体）；**§8 N5（jar/so 未产出）标为已闭合** | env-installer（t10）终版 APK 通报 |
| v1.7 | 2026-09-13 | 新增 **§14 跨层协同：t22 真机缺陷的 JNI 侧证据**（本层只读取证）。给出 ① 生成类**已产出且已编译**（48 个 `*Jni.java`、14 个模块 javac jar 共 45 个 `*Jni.class`、`GEN_JNI.class` 仅在各 `*.compliment.jar`），但官方 jar/AAR/部署 jar **均为 0** ⇒ 打包漏类；② `.so` 走 **jni_zero 符号绑定**（194 动态符号 = `JNI_OnLoad`+`JNI_OnUnload`+**193 个 `Java_J_N_*`**，`RegisterNatives`/`*Jni`/`$Natives` 字符串各 0）⇒ 必须由 Java 侧提供 `*Jni`+合并 `GEN_JNI`；③ 同代际同构建（`src` HEAD `5c25072b…`、jar==AAR classes.jar）。**本层二进制无缺陷，无需改动** | android-dev 的 t22 协同请求 |
| v1.8 | 2026-09-14 | 新增 **§15 t24：16 KB 页兼容性**。① 现状表：APK 内 4 个 `.so`，`libandroidx.graphics.path`/`libjingle_peerconnection_so` = **0x4000**，`libwebrtcdemo_native`/`libc++_shared` = **0x1000**；**关键：`libjingle` 不依赖 `libc++_shared`**（其唯一使用者是自有库）。② 最小修复已实施：`CMakeLists.txt` 增补 `-Wl,-z,max-page-size=16384`，AGP 重编后自有库 `p_align=0x4000`（sha256 `115aa211…`）。③ `libc++_shared`（r26=4KB）给出 (a) `c++_static` / (b) 用 r26 静态库自链接 16 KB 同名库（导出符号 2358 = 官方、缺失 0）/ (c) 升级 NDK 三方案与成本，**未实施，待 captain 选择**。④ 结论：当前配置**整包不达标**；(a)/(b) 任一落地后**达标**且**无需升级 NDK** | t24 任务（16 KB 页兼容） |
| v1.9 | 2026-09-14 | captain 裁定 **方案 (b)** 并授权后完成实施，新增 **§15.5 实施记录**：① 新增入库脚本 `scripts/make-libcxx-shared-16k.sh`（NDK 版本 + 输入/输出 sha256 自校验 + `p_align/SONAME/导出 2358/相对官方缺失 0` 自校验）；② 落位 `app/src/main/jniLibs/arm64-v8a/libc++_shared.so`（1,356,968 B / sha256 `c9dbf4ec…`）；③ `packaging.jniLibs.pickFirsts` **原已存在**，本次仅改注释标为**承重行**（无逻辑 diff）；④ 核查 `build_app.sh` stage 5 **不会清空 jniLibs**；⑤ 预验证 `merged_native_libs` 选中我们的件、`stripped_native_libs` 中**四个 .so 全 0x4000**（APK 级复核交 t26） | captain 裁定方案 (b) + 授权触碰 `app/**` |
| v1.10 | 2026-09-14 | 新增 **§16 t23 支撑：jni_zero 映射规则与 194/193 对账**（只读取证）：验证 `hashed_name`+`jni_mangle` 规则（Environment 3 条 boundary 逐一复算命中）；16 个 `GEN_JNI` 分片并集 = **194**、`.so` 边界 = **193**、交集 **193/193**、**唯一未命中 = `org_webrtc_LibaomAv1Encoder_create`**（AV1 未编入本 `.so`）；新增附件 `reports/07-native-dev-jnizio-mapping.tsv`（193 行现成映射表）与可复现命令 | captain 要求把映射规则/符号清单直接交给 webrtc-builder（t23） |
| v1.11 | 2026-09-14 | **新增 §16.5 更正与更新记录**：① **主动更正本层两个错数字** —— "被引用但缺失 `*Jni` = 42" → **47**（旧正则限定 `org/webrtc/<Class>Jni`，漏子包/其它包共 5 个）、"已编译 `*Jni.class` = 45" → **48**（旧统计只扫 14 个 `generated_*` jar，漏 `base_java_jni_java` 与 `third_party/jni_zero/generate_jni_java`），并写入"常量池类内部名"正确扫法与"必须全扫 16 分片"的防重犯说明；② 复核 t23 修复结果：jar `7dbe8400…` 内 `*Jni.class`=48、`GEN_JNI`=1、被引用 `*Jni` 缺失 0、合并 `GEN_JNI` native=**194**（与 16 分片并集逐名一致）、`.so` 所需 193 条**全覆盖**；③ 附件升级为 **194 行 4 列**（新增 `generated_header` 与 `so_exported` 列，AV1 行标 `no`） | captain 采纳 194/193 定性并要求更正 §16 + 我们的自查发现旧值有误 |

> 代码未因 v1.1–v1.7 变更（本层始终按契约 §9.2 输出 `native.log`、按 §6.6 返回状态码数值）。改动仅限本报告文件。

---

## 14. 跨层协同：t22 真机缺陷的 JNI 侧证据（本层只读取证，2026-09-13）

> 归属：缺陷根因与修复归 **t8（Java 侧）/ t5（产物打包）/ t10（APK 重打）**；本节仅提供本层可验证的 **JNI/.so 侧事实**，
> 供 t22 汇报与 verifier 交叉引用。**本层交付物无缺陷，未改动任何代码。**

### 14.1 生成类确实产出并已编译，但未进最终 jar

| 层 | 事实（实测） |
|---|---|
| 生成源码 | `webrtc-build/src/out/Release-arm64/gen/**/input_srcjars/org/webrtc/*Jni.java` 共 **48** 个（含 `PeerConnectionFactoryJni.java`） |
| 已编译类 | **48** 个 `*Jni.class`（全量扫 `obj/**/*.jar`；**旧稿写"14 个 `generated_*` jar 共 45 个"是错的，更正见 §16.5**）；`generated_peerconnection_jni_java.javac.jar` 含 **14** 个（**含 `PeerConnectionFactoryJni.class`**） |
| `GEN_JNI` | 仅存在于 **16 个 `*.compliment.jar`**（须全扫 `obj/**/*.jar`，只扫 `generated_*` 会漏 `base_java_jni_java` 与 `generate_jni_java`；**旧稿写 14 个/合计 187，更正见 §16.5**），且每模块一份（`javap -p` 实测 native 数：peerconnection=121、video=25、base=12…；**16 分片并集 = 194**） |
| 最终 jar | `lib.java/sdk/android/libwebrtc.jar`（sha256 `ee792522…`）、`aar-stage/classes.jar`（`ad54a0a2…`）、部署 `third_party/libwebrtc/java/libwebrtc-java.jar`（`d98939bb…`，**与 AAR 内 `classes.jar` 同 sha256**）：**`*Jni.class` = 0、`GEN_JNI.class` = 0**，仅 47 个 `$Natives` 接口 |

`libwebrtc.jar` 的 mtime（19:55）晚于 `.so`（16:55）却仍为 0 ⇒ **系统性打包漏类**，非陈旧缓存。

### 14.2 本层 .so 的绑定方式（jni_zero 符号绑定，非 RegisterNatives）

- `llvm-nm -D --defined-only libjingle_peerconnection_so.so`：**194** 个动态符号 = `JNI_OnLoad` + `JNI_OnUnLoad` + **193 个 `Java_J_N_<hash>`**
  （生成头可见来源：`JNI_ZERO_BOUNDARY_EXPORT int64_t Java_J_N_M0vTiIkf(JNIEnv*, jclass)`）。
- 该 .so 内字符串：`RegisterNatives` = **0**、`org/webrtc/*Jni` = **0**、`Natives` = **0**（仅有 `org.webrtc.JniHelper` 等 95 个 dot 形态名，供 `class_loader` 使用）
  ⇒ **类名不出现在 .so 中**，Java 侧必须提供声明这些 native 的类。
- 生成 Java 形态（M129 jni_zero）：`class PeerConnectionFactoryJni implements PeerConnectionFactory.Natives { public static Natives get() {...} ... }`，
  每个方法**委托** `GEN_JNI.org_webrtc_PeerConnectionFactory_*(...)`，而 `GEN_JNI` 内是 `public static native`。
  ⇒ 缺 `*Jni` + `GEN_JNI` 时，`PeerConnectionFactory.initialize(...)` 的 `invokestatic PeerConnectionFactoryJni.get()` 必抛
  `NoClassDefFoundError`，与 t8 的真机现象一致。

### 14.3 结论与修复方向（供 t5/t8/t10 决策，本层不擅自改产物）

1. **不是跨代际不匹配**：`src` HEAD `5c25072bda9b8c8d9acab443acef5b330b1588b7`；生成 Java 实现的 `$Natives` 接口就在 jar 内；
   部署 jar 与 AAR 内 `classes.jar` 同 sha256；`.so` 与 jar 的 mtime 差异只是 t5 分阶段重跑。⇒ **jar 与 .so 同代，缺的是"没打包进去"**。
2. **最小修复**：把 14 个模块的 `*Jni.class`（**48 个类**；原写 45，见 §16.5 更正）与**合并后的** `org/jni_zero/GEN_JNI.class` 并入 `classes.jar` / AAR 后重打 APK；
   **`libjingle_peerconnection_so.so` 无需重编**（其 193 个边界符号完整）。
3. 更稳的做法：让 jar 目标本身带上生成 srcjars，或用官方 `tools_webrtc/android/build_aar.py` 复核其 `classes.jar` 是否含这些类。
4. **待 t5 核实**：本层观察到"分包 GEN_JNI native 合计 **187**"与".so 边界 **193**"相差 6；按 jni_zero 设计最终须为**一份合并**的 `GEN_JNI`，
   因此不宜只塞某一模块的 GEN_JNI。本层只保证 ".so 侧 193 个符号清单完整"。
5. 真机 10 秒定案：logcat 搜 `NoClassDefFoundError` + `PeerConnectionFactoryJni`。

> 本层交付物（自有 `.so`/CMake/JNI 表）与上述缺陷无关：APK 内自有库仍为 §9.1.2 记录的 1,231,512 B / sha256 `e9b66cc9…`，仅导出两个符号、无 libwebrtc 依赖。

---

## 15. t24：16 KB 页兼容性（ELF `p_align`）——现状、最小修复与 libc++_shared 方案

> 目标：让 APK 内**全部** `.so` 具备 16 KB 页兼容（`p_align = 0x4000`）。Android 15+（16 KB 页内核）上，
> 任何 4 KB 对齐的 `.so` 都会 `dlopen` 失败，进而触发 Kotlin 的 `native_lib_load_failed` / `engine_init_skipped`。
> **本次只做"自有库最小修复"**；`libc++_shared.so` 的处理方案见 §15.3，**已按任务要求先回报 captain 等待选择，未实施**。

### 15.1 现状表（APK `app-debug.apk`（33,260,234 B / sha256 `b0cddd86…`）内 `lib/arm64-v8a/*.so`，实测）

| `.so` | 大小 (B) | `p_align` | NEEDED | 16 KB 达标 |
|---|---|---|---|---|
| `libandroidx.graphics.path.so` | 10,096 | **0x4000** | libm, libdl, libc | ✅ |
| `libjingle_peerconnection_so.so` | 12,946,912 | **0x4000** | libEGL, libdl, libm, liblog, libc | ✅ |
| `libwebrtcdemo_native.so` | 1,231,512 | **0x1000** ❌ | liblog, libandroid, libm, **libc++_shared**, libdl, libc | ❌ |
| `libc++_shared.so` | 1,330,832 | **0x1000** ❌ | libc, libm, libdl | ❌ |

**关键事实（决定方案空间）**：`libjingle_peerconnection_so.so` **不依赖** `libc++_shared.so`
（它自静态链接 Chromium 的 libc++，与契约 §5.1 的 ABI 墙分析一致）；`libandroidx.graphics.path.so` 也不依赖。
⇒ **APK 内 `libc++_shared.so` 的唯一使用者就是本层自有库**。因此"去掉 `libc++_shared` 依赖"就等于"整包不再需要该库"。

- 4 KB 来源：NDK **26.1.10909125** 的 aarch64 链接器默认 `max-page-size = 0x1000`（工具链无任何 `max-page-size` 预设；
  实测 `aarch64-linux-android26-clang` 默认 → `0x1000`，加 `-Wl,-z,max-page-size=16384` → `0x4000`），
  NDK 自带的 sysroot `libc++_shared.so` 也是 `0x1000`（sha256 `4e843755…`；APK 内为 AGP strip 后副本 sha256 `69e517e6…`）。
- 环境内**只有** NDK r26 一个版本（`android-sdk/ndk/` 下仅 `26.1.10909125`）。

### 15.2 最小修复（已实施并验证）：自有库加 `max-page-size`

`app/src/main/cpp/CMakeLists.txt` 的 `target_link_options` 增补：

```cmake
-Wl,-z,max-page-size=16384
```

重编（AGP 正式路径，宿主机）：`./gradlew --no-daemon :app:externalNativeBuildDebug` → `BUILD SUCCESSFUL in 29s`，
产物 `app/build/intermediates/cmake/debug/obj/arm64-v8a/libwebrtcdemo_native.so`（2,206,528 B，sha256 `115aa211…`）：

```
  LOAD  0x000000 0x0000000000000000 0x0000000000000000 0x129d40 0x129d40 R E 0x4000
  LOAD  0x129d40 0x000000000012dd40 0x000000000012dd40 0x0024f0 0x0024f0 RW  0x4000
  LOAD  0x12c230 0x000000000012e230 0x000000000012e230 0x000068 0x002c58 RW  0x4000
```

导出符号仍仅 `JNI_OnLoad`/`JNI_OnUnload`，`NEEDED` 不变（仍含 `libc++_shared.so`）。
> 契约关系：这是对契约 §4.2 冻结片段的一行**增补**（由 t24 任务指令授权）；建议 architect 折入 §4.2。

### 15.3 `libc++_shared.so`（r26 = 4 KB）三方案（**未实施，待 captain 选择**）

| 方案 | 做法 | 实测结果 | 契约影响 | 成本/风险 |
|---|---|---|---|---|
| **(a) 改 `c++_static`** | 自有库静态链接 libc++（`-DANDROID_STL=c++_static`），APK 随之不再打包 `libc++_shared.so` | 试构建（Release，含 16384 选项）：2,443,920 B（+328,752 B）；`NEEDED` **不含** `libc++_shared`；`p_align=0x4000`；导出仍只 2 个符号。**净 APK 体积 −1,002,080 B**（我的库 +0.33 MB、`libc++_shared` −1.33 MB） | **需改 §3.1/§4.1 的 STL 冻结值**（`c++_shared → c++_static`）+ 改 `app/build.gradle.kts` 的 `-DANDROID_STL`（**不在本层 scope**） | 最干净：少一个库、体积变小、无手搓产物；风险：契约级变更需批准；与 SDK 的 ABI 边界仍安全（JNI 面只用 POD/jstring/ByteBuffer，C++ 类型不跨 `.so`） |
| **(b) 换 16 KB 版 `libc++_shared.so`** ✅ **已实施（captain 裁定）** | 用**冻结的 r26 自带静态库**自链接一个 16 KB 对齐的同名库（完全离线可复现）：<br>`aarch64-linux-android26-clang++ -shared -fuse-ld=lld -nostdlib++ -Wl,-soname,libc++_shared.so -Wl,-z,max-page-size=16384 -Wl,--whole-archive $SYS/libc++_static.a $SYS/libc++abi.a -Wl,--no-whole-archive -lc -lm -ldl -o libc++_shared.so` | 产物：`p_align=0x4000`(4 LOAD 段)、SONAME 正确、**导出符号 2358 = 官方完全一致**（"官方有而候选缺失" = **0**）；strip 后 1,356,968 B（官方 strip 后 1,330,832 B，+26,136 B）；sha256（未 strip）`32cb92c2…`、(strip) `c9dbf4ec…`。**落地细节见 §15.5** | **不改契约**（仍 r26、仍 `c++_shared`） | 已固化为 `scripts/make-libcxx-shared-16k.sh`（含输入/输出 sha256 自校验）；`pickFirsts` 承重行保留 |
| **(c) 升级 NDK r27+** | r27 默认 16 KB 对齐且自带对齐版 `libc++_shared` | 本环境无 r27，未验证 | **契约级**（§3.1 NDK 冻结） | 最重：需装新 NDK + **用新 NDK 重编 libvpx**（t5 产物）+ 全量重验；不建议为单个 demo 承担 |

> 被否决的"取巧"做法：用 objcopy/手工改 `p_align` 或往 ELF 里塞填充——不可靠、不可复现，不采用。

### 15.4 结论（16 KB 页设备上整包是否可达标）

- **当前配置：不达标**。自有库（已修）达标，但 `libc++_shared.so` 仍为 `0x1000`，且被自有库 `NEEDED` ⇒ 16 KB 设备上
  `System.loadLibrary("webrtcdemo_native")` 会因依赖库无法映射而失败（走 Kotlin 的 `native_lib_load_failed` 分支）。
- **落地 (a) 或 (b) 任一后：可达标**。APK 内 4 个 `.so` 将全部为 `0x4000`
  （(a)：`libc++_shared` 直接消失；(b)：其被替换为 `0x4000` 版本）。
- **不需要 NDK 升级**（(c) 非必要）。(b) **不需要任何契约变更**，只需一处打包规则 + t10 的构建脚本动作；
  (a) 需要把 §3.1/§4.1 的 STL 冻结值改为 `c++_static`（**契约变更请求**）。
- **最小契约变更请求（若选 (a)）**：`doc/14` §3.1「STL = `c++_shared`」与 §4.1 的 `-DANDROID_STL=c++_shared`
  → `c++_static`；影响：APK 少一个库、净体积 −1.0 MB；风险：与 SDK 无 ABI 交叉（JNI 面仅 POD），构建脚本同步改一行。
- **本层推荐**：**(b)** 作为"零契约变更、可完全从 r26 复现"的交付路径（配脚本 + sha256 记录）；
  若 captain 愿意接受契约微调，**(a)** 是更干净的长效方案。**两者都需 captain 决策后由 t10/构建 owner 落地**，
  本层未擅自动打包与 Gradle 文件。

**待办（owner）**：① ~~captain 选 (a)/(b)/(c)~~ → **captain 裁定 = (b)**；
② ~~若 (b)：把自链接命令固化为脚本、产物入 `jniLibs`、加 `pickFirsts`~~ → **已完成，见 §15.5**；
③ 之后重打 APK 并复核"APK 内全量 `p_align` 表"（t26）；④ 真机（16 KB 页设备）验证 `System.loadLibrary` 成功。

### 15.5 方案 (b) 实施记录（2026-09-14，captain 授权触碰 `app/**` 两处 + `scripts/`）

**变更清单**

| # | 路径 | 内容 |
|---|---|---|
| 1 | `app/src/main/cpp/CMakeLists.txt` | `target_link_options` 增补 `-Wl,-z,max-page-size=16384`（§15.2） |
| 2 | `scripts/make-libcxx-shared-16k.sh`（**新增，入库**） | 自链接 16 KB `libc++_shared.so`；校验 NDK revision=26.1.10909125、三个输入 sha256、输出 sha256，并自校验 `p_align`/`SONAME`/导出数/相对官方缺失数 |
| 3 | `app/src/main/jniLibs/arm64-v8a/libc++_shared.so`（**新增，被 `.gitignore` 的 `*.so` 忽略，不入库**） | 1,356,968 B / sha256 `c9dbf4ec…` |
| 4 | `app/build.gradle.kts`（`packaging.jniLibs`） | `pickFirsts += setOf("**/libc++_shared.so")` **原本已存在**（t8/t10 时期的"附加保险"），本次**仅改注释**把它标为**承重行**并说明原因；逻辑未变（无逻辑 diff） |

**stage 5 是否会清空 `jniLibs` 的核查（captain 指定的"坑"）**：`scripts/build_app.sh` 只在开头 `mkdir -p "$JNILIBS"`，
stage 5 用 `cp -f "$SO_TP" "$JNILIBS/"` 复制 libjingle，**全脚本对 `JNILIBS` 无任何 `rm`/`git clean`**（`rm -rf` 仅作用于 mktemp 临时目录与 dex 临时目录）
⇒ **不会清掉我们的 `libc++_shared.so`**，无需改 stage 5。

**构建链路预验证（无需等 t26，已实测）**：跑 `:app:mergeDebugNativeLibs` + `:app:stripDebugDebugSymbols` 后检查中间产物：

| 中间产物（arm64-v8a） | sha256 | p_align |
|---|---|---|
| `merged_native_libs/.../libc++_shared.so` | `c9dbf4ec…`（**我们的**，非 NDK 的 `69e517e6…`） | — |
| `stripped_native_libs/.../libandroidx.graphics.path.so` | `41e9a793…` | **0x4000** |
| `stripped_native_libs/.../libc++_shared.so` | `c9dbf4ec…`（= 脚本产物，AGP strip 为幂等） | **0x4000** |
| `stripped_native_libs/.../libjingle_peerconnection_so.so` | `757cef81…`（未变） | **0x4000** |
| `stripped_native_libs/.../libwebrtcdemo_native.so` | `95c44e5a…` | **0x4000** |

⇒ **四个 `.so` 在打包前的最终中间产物上已全部 0x4000**；`pickFirsts` 也确实选中了 `jniLibs` 里的那份（而不是 NDK sysroot 的 4 KB 版）。
`libc++_shared.so` 导出数仍 2358。**APK 级复核由 t26（重编 APK）完成**。

**不变量（本次未改变）**：自有库 `NEEDED` 仍含 `libc++_shared.so`；自有库导出仍**仅** `JNI_OnLoad`+`JNI_OnUnload`；
未改 `doc/14`、`app/src/main/kotlin/**`、jar/AAR、libvpx；`libjingle`/androidx 的 `.so` 原样未动。

---

## 16. t23 支撑：jni_zero 边界符号 ↔ Java native 的映射规则与 194/193 对账（2026-09-14）

> 目的：t23 要"合并 `GEN_JNI`"并与 `.so` 的 193 个 `Java_J_N_*` 逐条对齐；本节给出**已验证的映射规则**、
> **精确对账结果**与**可复现命令**，使 t23 一次做对。**本节只读取证，未改任何产物**（新增附件见 §16.4）。

### 16.1 映射规则（源码级，已验证）

`jni_zero` 的 native 方法名 → JNI 符号名的两步（出处：`third_party/jni_zero/proxy.py::hashed_name()`、`common.py::jni_mangle()`）：

```
hashed = ('M' + base64.b64encode(md5(java_native_method_name).digest(), altchars=b'$_')).rstrip('=')[:8]
symbol = 'Java_J_N_' + jni_mangle(hashed)      # '_'→'_1'，'/'→'_'，'$'→'_00024'
```
> 注意 `_MAX_CHARS_FOR_HASHED_NATIVE_METHODS = 8`；`jni_mangle` 会把 hashed 里的 `_` 转义成 `_1`，
> 所以**表象上**是 9 个字符（如 `M_1YMMZf6`）。

**验证**（生成头 `gen/jni_headers/sdk/android/generated_environment_jni/Environment_jni.h` 里的 3 个 boundary 与复算完全一致）：

| Java native 方法（`GEN_JNI` 内声明名） | 复算符号 | 生成头实际符号 |
|---|---|---|
| `org_webrtc_Environment_create` | `Java_J_N_M4R0A5nM` | `Java_J_N_M4R0A5nM` ✅ |
| `org_webrtc_Environment_currentTimeNanos` | `Java_J_N_M_1YMMZf6` | `Java_J_N_M_1YMMZf6` ✅ |
| `org_webrtc_Environment_free` | `Java_J_N_MlqxOSjz` | `Java_J_N_MlqxOSjz` ✅ |

### 16.2 194 ↔ 193 精确对账（回答 captain 的"1 条差值"）

| 量 | 值 | 来源/命令 |
|---|---|---|
| 生成 `*Jni.java` | **48** | `find gen -name '*Jni.java'` |
| 已编译 `*Jni.class`（全部 jar，去重） | **48** | 全量扫 `obj/**/*.jar`（见 §16.5 更正：旧值 45 只统计了 14 个 `generated_*jni_java` jar） |
| 修复前 jar（`classes.jar` `d98939bb…`）"被引用但缺失"的 `*Jni` | **47**（47/47 全缺） | 常量池**类内部名**扫描（任意包；见 §16.5 更正：旧值 42 因正则限定 `org/webrtc/<Class>Jni` 而少算 5 个） |
| `GEN_JNI` 分片（`*.compliment.jar`） | **16** | 必须**全扫** `obj/**/*.jar` 中含 `org/jni_zero/GEN_JNI.class` 者；只扫 `generated_*` 会漏 `base_java_jni_java` 与 `generate_jni_java` 两个分片（共 7 条 native） |
| 16 分片 native **并集** | **194** | `javap -p … org.jni_zero.GEN_JNI \| grep native`，逐分片取并集（与 captain 的 194 一致） |
| 按 §16.1 规则复算出的符号数 | **194**（无碰撞） | 见 §16.4 附件 |
| `.so` 导出的 `Java_J_N_*` | **193** | `llvm-nm -D --defined-only libjingle_peerconnection_so.so` |
| **交集（194 中命中）** | **193 / 193** ✅ | 每个导出符号都能映射到某条 Java native |
| **唯一未命中** | **1 条**：`org_webrtc_LibaomAv1Encoder_create` → `Java_J_N_M0vTiIkf` | 该 boundary **不在本 `.so` 中** |
| **修复后实测（t23 落盘 jar `7dbe8400…`，508 条目）** | `*Jni.class` = **48**、`GEN_JNI` = **1**、被引用 `*Jni` **缺失 0**；合并 `GEN_JNI` native = **194**（与 16 分片并集逐名一致）；`.so` 所需 193 条**全覆盖（缺失 0）** | 见 §16.5 |

**结论（给 t23/t26）**：
1. 合并后的 `GEN_JNI` 必须声明 **194** 条 native（16 分片并集），而 `.so` 只导出 **193** 条 ⇒ **差值恰为 1 条，且可精确指出是 `org_webrtc_LibaomAv1Encoder_create`**（AV1 编码器边界未编进本 `.so`；本项目只用 VP9，运行期不会创建 AV1 编码器）。
2. 因此跨产物核对的判据应是：**193 个 `Java_J_N_*` 全部能在合并 `GEN_JNI` 中找到对应方法**（§16.4 附件即该映射表），
   且合并 `GEN_JNI` 的 native 数 = **194**；若构建方确认 AV1 未编入，可在测试中把该条目列为**已知豁免**并要求**书面理由**（而不是把期望值改成 193 了事）。
3. **`.so` 不需要重编**：其 193 个边界完整，缺陷在 Java 侧打包（缺 `*Jni` + 缺 `GEN_JNI`）。

### 16.3 可复现命令（t23/t26 可直接用）

```bash
# (1) 导出 .so 的 193 个边界符号
$NDK/bin/llvm-nm -D --defined-only <apk 内 lib/arm64-v8a/libjingle_peerconnection_so.so> \
  | awk '$3 ~ /^Java_J_N_/ {print $3}' | sort > /tmp/so_boundaries.txt ; wc -l < /tmp/so_boundaries.txt   # 193

# (2) 收集 16 个分片的 GEN_JNI native 并集（需 JDK 的 javap + unzip）
for c in $(find <webrtc-build>/src/out/Release-arm64/obj -name '*.jar'); do
  unzip -l $c | grep -q 'org/jni_zero/GEN_JNI.class' || continue
  d=$(mktemp -d); (cd $d && unzip -o -q $c 'org/jni_zero/GEN_JNI.class')
  $JDK/bin/javap -p -classpath $d org.jni_zero.GEN_JNI | grep native \
    | grep -oE '[A-Za-z0-9_]+\(.*\)' | sed 's/(.*//'
  rm -rf $d
done | sort -u > /tmp/gj_all.txt ; wc -l < /tmp/gj_all.txt   # 194

# (3) 用 §16.1 规则把 (2) 映射成符号，与 (1) 求交 → 应为 193/193，仅 1 条未命中（AV1）
node <仓库>/scripts/… 或见附件 TSV（已给出 193 条现成映射）
```

### 16.4 附件

- `reports/07-native-dev-jnizio-mapping.tsv`：**194 行数据**（4 列：`symbol \t java_native_method \t generated_header \t so_exported`），
  即"194 条 native ↔ `Java_J_N_*` ↔ 生成头"的完整对照；`so_exported=yes` 193 条、`no` **1 条**（AV1，见 §16.2）。
  文件头 11 行注释含规则、出处、更正说明与用途。t23/t27 可直接 `join`/`comm` 对账。

### 16.5 更正与更新记录（v1.11，2026-09-14）

**A. 更正我自己的两个错数字（主动披露，影响面已通知 t23/t25/t27）**

| 量 | 旧值（错） | **真值** | 错因 |
|---|---|---|---|
| 修复前"被引用但缺失"的 `*Jni` | 42 | **47**（47/47 全缺） | 旧扫描的正则写死 `org/webrtc/<Class>Jni`，**漏掉子包与其它包**：少算 `org/webrtc/audio/WebRtcAudioRecordJni`、`org/webrtc/audio/WebRtcAudioTrackJni`、`org/webrtc/audio/JavaAudioDeviceModuleJni`、`org/jni_zero/JniZeroJni`、`org/jni_zero/CommonApisJni` 共 5 个 |
| 已编译 `*Jni.class`（去重） | 45 | **48** | 旧统计只扫了 14 个 `obj/sdk/android/generated_*jni_java.javac.jar`；漏 `base_java_jni_java.javac.jar`（`LoggingJni`）与 `third_party/jni_zero/generate_jni_java.javac.jar`（`CommonApisJni`/`JniZeroJni`） |

- **正确扫描方法（防后人重犯）**：不要按包名限定，直接在每个 `.class` 的常量池里抓**类内部名**
  （小写包路径 + 大写类名且以 `Jni` 结尾），例如正则 `/([a-z][a-z0-9_$]*(?:\/[a-z0-9_$]+)*\/[A-Z][A-Za-z0-9_$]*Jni)\b/`；
  按包名限定（`org/webrtc/`）会漏子包。
- **集合对账（真值）**：生成 48 = 编译 48（A−B = ∅、B−A = ∅）；修复前被引用 47（全缺）；
  编译但修复前未被引用的仅 **1** 个 = `Dav1dDecoderJni`（同代际生成件，修复后 jar 亦引用之）。
  ⇒ **建议并全部 48 个 `*Jni.class`**（多包 1 个死代码，无外来类），而不是"只并 42/45"。
- 另更正一处他人自述：webrtc-builder 在 t23 小结中称"另 3 个（`LoggingJni`/`CommonApisJni`/`JniZeroJni`）全树未编译"
  —— 实测**不成立**，它们分别位于上述两个未被其扫到的 jar 中。

**B. t23 修复结果的本层复核（部署 jar `7dbe8400…`，508 条目，2026-09-14 10:53）**

| 检查 | 结果 |
|---|---|
| `*Jni.class` / `org/jni_zero/GEN_JNI` | **48 / 1** |
| 被引用的 `*Jni` 是否缺失 | **缺失 0**（对比修复前 47/47 全缺） |
| 合并 `GEN_JNI` 的 `native` 数（`javap -p \| grep native`） | **194**，与 16 分片并集**逐名一致（comm -3 无差异）** |
| `.so` 所需 193 条是否覆盖 | **缺失 0**（全覆盖） |
| `GEN_JNI` 比 `.so` 多出 | **1 条 = `org_webrtc_LibaomAv1Encoder_create`**（AV1，潜在） |

> ⚠️ 该 jar/AAR 是 t23 在飞行中落盘的；**最终记录以 t26 重编产物为准**（t27 复核时请用最终哈希）。












