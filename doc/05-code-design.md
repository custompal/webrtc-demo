# 代码设计方案

> 在 `00-overview.md` 架构与云方案锁定后，本文细化代码层：目录、构建、技术栈、模块边界、部署、协议、代码风格与注释规范。所有决定为拷问共识。

## 1. 代码组织：单仓多模块
```
webrt-demo/                       # 单 git 仓
├── doc/                          # 设计文档（已有）
├── app/                          # Android app（Kotlin/Compose）
│   ├── src/main/
│   │   ├── java/com/webrt/demo/
│   │   │   ├── ui/               # Compose UI：发起/加入/预览/远端/状态面板
│   │   │   ├── signaling/        # WebSocket 客户端（Ktor/OkHttp）
│   │   │   ├── webrtc/           # PeerConnectionClient（调 native）
│   │   │   └── model/            # UI 状态：IceEvent/NatType/Stats
│   │   ├── cpp/ → 软链 ../native
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts          # AGP + externalNativeBuild 引 native/CMakeLists.txt
├── native/                       # C++ 自研 + JNI
│   ├── jni/                      # 手写 JNI：JNIEnv/RegisterNatives
│   │   ├── peer_connection_jni.cc
│   │   ├── encoder_jni.cc
│   │   └── nat_jni.cc
│   ├── encoder/                  # 自研 VP9 VideoEncoder（封装 libvpx）
│   │   ├── vp9_encoder.h/.cc     # 实现 VideoEncoder 接口
│   │   └── vp9_encoder_factory.cc
│   ├── nat/                      # RFC5780 NAT 探测（独立 socket）
│   │   └── nat_detector.h/.cc
│   ├── stats/                   # getStats 采集 + observer 回调桥
│   ├── CMakeLists.txt           # 链 libwebrtc.a + libvpx.a → libnative.so
│   └── jni_bridge.h             # 对外 JNI 符号声明
├── third_party/
│   ├── libwebrtc/               # 云主机编译产物（.a + 头）
│   │   ├── lib/                  # libwebrtc.a 等
│   │   └── include/             # api/ / rtc_base/ / modules/ 头
│   ├── libwebrtc-src/           # libwebrtc 源码（submodule，对照阅读）
│   ├── libvpx/
│   │   ├── lib/                 # libvpx.a（arm64）
│   │   └── include/             # vpx_codec.h 等
│   └── libvpx-src/              # libvpx 源码（submodule，对照阅读）
├── signaling/                    # Go 信令服务（单二进制）
│   ├── main.go                   # WebSocket /signal
│   ├── room.go                  # room 管理（6 位码、上限 2 人）
│   ├── hub.go                   # 连接分发 / room 路由
│   └── go.mod
└── scripts/
    ├── build_libwebrtc.sh        # 云主机上 depot_tools 编译
    ├── build_libvpx.sh          # 云主机上编 libvpx arm64
    ├── pack_artifacts.sh        # 打包 .a + 头 scp 回 Windows
    └── deploy_signaling.sh      # scp + systemd reload
```

## 2. 技术栈与版本
| 组件 | 选型 | 版本 |
|---|---|---|
| Android UI | Kotlin + Jetpack Compose | Kotlin 2.0+，Compose BOM 2024.x+ |
| 构建系统 | AGP + CMake + NDK | AGP 8.x，CMake 3.22+，NDK r25c+ |
| libwebrtc | Google 源码 depot_tools | 锁定 M_release 分支（如 M125，可复现） |
| libvpx | 源码编译 arm64 | 锁定稳定 tag（如 v1.14+） |
| 自研编码器 | C++ 实现 libwebrtc VideoEncoder 接口 | — |
| 解码 | libwebrtc 内置 Android MediaCodec | — |
| 采集 | libwebrtc Camera2Capturer + JavaAudioDeviceModule + SurfaceTextureHelper | — |
| 信令服务 | Go + gorilla/websocket 或 nhooyr/websocket | Go 1.22+ |
| 信令协议 | JSON 文本帧（WebSocket） | — |
| coturn | apt coturn | 系统包 |

## 3. native 构建（CMake + AGP）
`native/CMakeLists.txt` 关键片段：
```cmake
cmake_minimum_required(VERSION 3.22)
project(native)

# libwebrtc + libvpx 预编译产物
set(LIBWEBRTC_PATH ${CMAKE_SOURCE_DIR}/../third_party/libwebrtc)
set(LIBVPX_PATH    ${CMAKE_SOURCE_DIR}/../third_party/libvpx)

include_directories(
    ${LIBWEBRTC_PATH}/include
    ${LIBVPX_PATH}/include
    ${CMAKE_SOURCE_DIR}/encoder
    ${CMAKE_SOURCE_DIR}/nat
    ${CMAKE_SOURCE_DIR}/jni
    ${CMAKE_SOURCE_DIR}/stats
)

add_library(native SHARED
    jni/peer_connection_jni.cc
    jni/encoder_jni.cc
    jni/nat_jni.cc
    encoder/vp9_encoder.cc
    encoder/vp9_encoder_factory.cc
    nat/nat_detector.cc
    stats/stats_bridge.cc
)

target_link_libraries(native
    ${LIBWEBRTC_PATH}/lib/libwebrtc.a
    ${LIBVPX_PATH}/lib/libvpx.a
    log android
)
```
`app/build.gradle.kts` 用 `externalNativeBuild { cmake { path = file("../native/CMakeLists.txt") } }` + `abiFilters("arm64-v8a")`。

## 4. JNI 界面（手写）
**Java → C++（RegisterNatives 注册）**：
```cpp
// peer_connection_jni.cc
JNICALL createPeerConnection(iceServers, turnUser, turnPass)
JNICALL createOffer()
JNICALL createAnswer()
JNICALL setLocalDescription(sdp)
JNICALL setRemoteDescription(sdp, type)
JNICALL addIceCandidate(sdp, sdpMid, sdpMLineIndex)
JNICALL toggleMute(audio, video)
JNICALL hangup()
JNICALL startNatTest(stunHost, stunPort)
JNICALL getStats()                  // 定时拉取
```
**C++ → Java（observer 回调投到 Java）**：
```cpp
onIceCandidate(sdp, sdpMid, mLineIdx)
onIceConnectionChange(state)
onIceCandidatePairChanged(local, remote, state, nominated)
onConnectionChange(state)
onStatsReport(json)                 // candidate pair/bitrate/可用带宽
onBitrateChanged(bps)               // 编码器码率回调
onNatTypeDetected(type)              // RFC5780 探测结果
onRemoteVideoFrameReady()           // 渲染就绪
```
注册方式：`JNI_OnLoad` 里 `FindClass + RegisterNatives`，避免 `JNIEXPORT` 名字依赖。

## 5. 自研 VP9 编码器集成点
```
app 启动
  → JNI createPeerConnection
    → C++ PeerConnectionFactory
      → 注入自研 VideoEncoderFactory（含 VP9EncoderFactory）
    → PeerConnection
通话中
  → GCC 估带宽 → BitrateAllocator
  → VP9Encoder::SetRates(VideoBitrateAllocation)
      解析 GetSpatialLayer(i).GetBitrateBps(j)
      → 更新 vpx_codec_enc_config_t:
           ss_target_bitrate[i]
           layer_target_bitrate[k]
           rc_target_bitrate
      → vpx_codec_enc_config_set
  → EncodeFrame(VideoFrame) → vpx_codec_encode → 返回 EncodedImage
  → onBitrateChanged 回调投 UI
```
对照点：libwebrtc 官方 VP9 实现在 `modules/video_coding/codecs/vp9/`，可读可对照。

## 6. NAT 探测（独立模块，RFC5780）
- 独立 C++ 模块，直用 socket，不经 PeerConnection。
- 流程：发 STUN Binding Request 到 STUN 服务器（coturn 3478）→ 测“变更 IP/端口行为”→ 判定 NAT 类型（Full-Cone / Restricted / Symmetric 等）。
- 结果经 `onNatTypeDetected` 投 UI；对端类型经信令 JSON 交换。

## 7. 信令协议（JSON 文本帧）
```json
// createRoom
{"type":"create"}
// 响应
{"type":"created","roomId":"A1B2C3","stun":"stun:1.2.3.4:3478","turn":"turn:1.2.3.4:3478","turnUser":"demo","turnPass":"demopass"}
// join
{"type":"join","roomId":"A1B2C3"}
// offer/answer/ice
{"type":"offer","sdp":"..."}
{"type":"answer","sdp":"..."}
{"type":"ice","candidate":"...","sdpMid":"...","sdpMLineIndex":0}
// natType 交换
{"type":"natType","natType":"Symmetric"}
// leave
{"type":"leave"}
```
Go 侧用 `map[roomId]*Room`，每 room 最多 2 连接，消息按 `roomId` 广播给同 room 对端。

## 8. 部署形态（systemd 直接跑）
云主机上两个 systemd unit：
```
# /etc/systemd/system/coturn.service   (apt 自带，略)
# /etc/systemd/system/signaling.service
[Unit]
After=network.target
[Service]
ExecStart=/opt/signaling/signaling -addr :8443 -turn turn:<IP>:3478 -user demo:demopass
Restart=always
[Install]
WantedBy=multi-user.target
```
建议 Caddy 反代 8443 → 443 上 TLS（域名可选，IP 直连也行）。

## 9. 构建与产物流程

### 9.0 仓库初始化（Git + Submodule）
在开始构建前，先在项目根目录初始化 Git 仓库，并以 submodule 方式引入 libvpx 和 libwebrtc 源码，方便对照阅读与修改。

**步骤 1：初始化空 Git 仓库**
```bash
cd e:\code\project\webrt-demo
git init
git add doc/
git commit -m "初始化项目文档"
```

**步骤 2：以 submodule 引入 libvpx 与 libwebrtc 源码**
```bash
# 引入 libvpx（VP9 编解码库，自研编码器封装目标）
git submodule add https://chromium.googlesource.com/webm/libvpx third_party/libvpx-src
git commit -m "引入 libvpx 源码 submodule"

# 引入 libwebrtc 源码（对照阅读 + 可选编译）
git submodule add https://webrtc.googlesource.com/src third_party/libwebrtc-src
git commit -m "引入 libwebrtc 源码 submodule"
```

> 注意：
> - `third_party/libvpx-src` 和 `third_party/libwebrtc-src` 是**源码目录**（对照阅读 / 可选自编译），与 `third_party/libvpx`、`third_party/libwebrtc`（云主机编译产物的 `.a` + 头文件）区分开。
> - libwebrtc 源码约 50GB+，clone 耗时较长；若只读码不编译，可在云主机上做 shallow clone（`--depth 1`）后本地拉取。
> - `.gitmodules` 会自动生成；clone 项目时需 `git submodule update --init --recursive`。

### 9.1 编译与部署
1. 云主机跑 `scripts/build_libwebrtc.sh`（depot_tools + gn + ninja，arm64）。
2. 云主机跑 `scripts/build_libvpx.sh`（libvpx arm64 静态库）。
3. `scripts/pack_artifacts.sh` 打包 `.a` + 头，scp 回 Windows `third_party/`。
4. Windows Android Studio 打开项目，AGP 引 `native/CMakeLists.txt`，构建 APK。
5. `scripts/deploy_signaling.sh` scp Go 二进制到云主机 + systemd reload。

## 10. 模块边界原则（重申）
- libwebrtc 内部（GCC/NACK/FEC/SRTP）不重写，靠读源码 + getStats/observer 观察。
- 唯一动手指 libwebrtc 的点 = `VideoEncoder`（自研 VP9）。
- JNI 薄层只做边界转换，不含业务逻辑。
- 采集 / 解码用 libwebrtc 内置，不自研。
- NAT 探测独立 socket 模块，学 STUN 协议本身。

## 11. 代码风格与注释规范

### 11.1 C++ 代码风格
自研 C++ 代码（`native/` 下所有 `.h/.cc`）遵循 **WebRTC C++ 代码风格规范**，该规范主要参考 Chromium 与 Google C++ 风格指南，要点：

| 规则 | 要求 |
|---|---|
| 命名 | 文件名 `snake_case`；类/结构体 `CamelCase`；函数 `CamelCase`；变量/参数 `snake_with_underscores_`（成员变量尾部下划线）；常量 `kCamelCase` |
| 头文件 | `#pragma once`（或传统 `#ifndef` 守卫）；include 顺序：对应头 → C 系统头 → C++ 系统头 → 其他库 → 本项目 |
| 缩进 | 2 空格，不用 Tab |
| 大括号 | 行末左括号（函数/类/条件/循环均行末） |
| 行宽 | 80 字符（与 libwebrtc 一致） |
| 注释 | `//` 优先；块注释用 `/* */` |
| 异常 | 不使用 C++ 异常（与 libwebrtc 一致） |
| RTTI | 不使用 `dynamic_cast`（与 libwebrtc 一致） |

可运行 `cpplint`（Chromium 版）或 `clang-format`（用 WebRTC 的 `.clang-format`）做格式校验。

### 11.2 注释语言规范
**自研代码必须包含中文注释**，具体要求：

1. **文件头注释**：每个 `.h/.cc` 文件顶部用中文注释说明文件职责、模块归属、关键设计点。可搭配英文简述。
2. **函数注释**：公共接口函数（尤其 JNI 层、编码器接口、NAT 探测接口）用中文说明参数、返回值、调用时机。可保留英文补充。
3. **关键逻辑注释**：动态码率分配（`SetRates`）、ICE 状态机回调、NAT 类型判定逻辑等核心学习点，用中文注释解释"为什么这么做"和"对应哪个 WebRTC 技术点"。
4. **行内注释**：libvpx API 调用、vpx 编码配置项等不直观处，用中文行内注释解释含义。
5. **英文注释保留**：可同步保留英文注释，但不可只有英文而无中文。中文注释为主，英文为辅。

示例：
```cpp
// vp9_encoder.cc
// VP9 自研编码器：封装 libvpx 实现 libwebrtc VideoEncoder 接口。
// 核心学习点：通过 SetRates 接收 GCC 分配的分层码率，更新 vpx 编码配置，
// 实现"拥塞控制 → 编码器 → 输出码率"的动态码率闭环。
// Implements libwebrtc VideoEncoder interface via libvpx VP9.

void VP9Encoder::SetRates(const VideoBitrateAllocation& rates) {
  // 遍历每个空间层，提取 GCC 分配的目标码率
  for (int i = 0; i < num_spatial_layers_; ++i) {
    // GetBitrateBps: 获取该空间层 i 在时序层 j 的码率分配
    int bitrate = rates.GetSpatialLayer(i).GetBitrateBps(0);
    // 更新 libvpx 的每空间层目标码率
    cfg_.ss_target_bitrate[i] = bitrate / 1000;  // vpx 单位为 kbps
  }
  ...
}
```

### 11.3 Kotlin / Go 代码注释
- **Kotlin**（app 层）：公共类/函数用中文 KDoc 注释；关键 UI 状态流转用中文行注释。
- **Go**（信令）：公共函数用中文注释；room 管理逻辑用中文说明。
- 原则同 C++：中文为主，英文可辅。
