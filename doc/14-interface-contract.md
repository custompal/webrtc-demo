# 14 — 接口与架构契约（唯一权威 / Frozen Interface Contract）

> 本文档由 **architect（t2）** 产出，是本项目 `native-dev`（t7）、`android-dev`（t8）、`go-dev`（t9）、
> `webrtc-builder`（t5）、`env-installer`（t10）与 `verifier`（t11）实现与验收的**唯一权威依据**。
> 凡 `doc/00`–`doc/13` 与 `doc/adr/*` 与本文冲突处，**一律以本文为准**（D5 冻结的 `doc/09` 消息字段除外，
> 那些字段本文只登记、不改动）。冲突逐条登记见 §10。
>
> 契约作者：architect · 版本：v1.0（冻结）· 冻结时间：2026-09-13
> 变更流程：任何成员若发现本文不可实现或与实测环境矛盾，**不得自行偏离**，
> 必须以 `agent_teams_send_message` 通知 captain，由 captain 决定是否让 architect 出 v1.x；
> 未获批准的偏离必须在自己的报告中登记为「未授权偏离」。
>
> **权威文本与指纹（2026-09-13 增补，防快照错位）**：本契约的权威文本 = **工作区磁盘上的
> `code/webrtc-demo/doc/14-interface-contract.md`**。任何消息/报告里引用的 `sha256` 或行数若与磁盘实测不一致，
> **一律以磁盘实际哈希为准**——旧引用只代表**历史版本**，**不**表示契约被改动或回退。
> `verifier` 复跑前先执行 `sha256sum doc/14-interface-contract.md` 并把结果记入报告；与当轮公布值不同时，
> 先按「快照错位」处理（重新取磁盘文本），**不得**据旧指纹判定「契约被改动」或据此判失败。

---

## 0. 读者与用法

| 读者 | 必须先看 | 关键约束 |
|---|---|---|
| native-dev（t7） | §2、§4.2、§5.3–§5.7、§6、§9.3–§9.4 | JNI 符号与线程模型**逐字照抄**，不得改类名/方法名 |
| android-dev（t8） | §2、§3、§4.1、§5.3、§6、§7、§8、§9 | 包名/目录/类名冻结；信令按 `doc/09`；采集**不用 CameraX** |
| go-dev（t9） | §8、§9.3、§10（C20/C21） | 信令按 `doc/09` 冻结字段；日志用 logrus；修 `doc/12` 已知缺陷 |
| webrtc-builder（t5） | §4.3、§9.7、§11 | 产物**必须含 Java SDK（jar+so）**，不只是 `.a`；GN 参数见 §4.3 |
| env-installer（t10） | §3.5、§4.1、§4.5 | 构建命令、SDK/NDK/JDK 版本、Gradle 版本 |
| verifier（t11） | §12 全部 | 逐条验收；偏离登记见 §11 |

---

## 1. 冻结决策（用户已拍板，不再讨论）

| ID | 决策 | 状态 |
|---|---|---|
| **D1** | 采集/渲染/PeerConnection 全部使用 libwebrtc **官方 Android Java SDK**（`org.webrtc.*`，即 `libwebrtc.jar` + `libjingle_peerconnection_so.so`），**不自研**采集/渲染；解码用 SDK 自带 `DefaultVideoDecoderFactory`（MediaCodec 硬解） | 冻结 |
| **D2** | Kotlin 2.0+ / Compose BOM 2024.x / AGP 8.5+（含独立 Compose Compiler 插件）/ JDK 17 | 冻结 |
| **D3** | C++ 放 `app/src/main/cpp/`；Kotlin 根 `app/src/main/kotlin/`；包名 `com.example.webrtcdemo` | 冻结 |
| **D4** | 其余机械性冲突由本契约统一裁定；各 dev 在报告中记录修正 | 冻结 |
| **D5** | `doc/09-signaling-protocol-spec.md` 是信令协议权威规格（消息字段不改动）；`/signal` 与旧字段名作废（见 §8） | 冻结 |
| **D6** | 全链路日志：Go 用 `logrus`（终端 + 文件）；Kotlin 与 C++ 也写日志文件；App 内可从 UI 导出日志 | 冻结 |
| **A1** | **编码器注入路线**：Java 侧实现 `org.webrtc.VideoEncoderFactory` / `VideoEncoder`，逐帧经 **自有 JNI** 调 C++ libvpx VP9 编码器（详见 §5） | 本次冻结 |
| **A2** | 唯一"动手实现"的 WebRTC 扩展点 = 视频编码器；其余（GCC/NACK/FEC/DTLS/SRTP/ICE/PC）只观察 + 读码 | 沿用 ADR-001/002 |

### 1.1 被本契约取代的关键说法（摘要，明细见 §10）

- `doc/05` §1/§4/§5/§7、`doc/11` 全篇的 **C++ PeerConnection / JNI 大接口** 方案 → 由 A1 + D1 取代（JNI 面收窄为编码器 + NAT + 日志，见 §6）。
- `doc/10` §6.1 的 **CameraX** 采集 → 由 D1 取代为 SDK 的 `Camera2Enumerator` + `CameraVideoCapturer`（见 §7.2）。
- `doc/10` §1 的 Kotlin 1.9.22 / AGP 8.3 → 由 D2 取代。
- `ADR-006` §2「不引源码 submodule」→ 由用户任务指令取代（已加 submodule，见 §2）。
- `ADR-007` / `doc/05` §11 风格范围写 `native/` → 随 D3 改为 `app/src/main/cpp/`（Kotlin/Go 同样受中文注释约束）。
- `doc/01` §5 的 `relay-ip=<公网IP>` → t6 实测为**错误配置**（errno 99 / Allocate 508），正确值必须是内网 IP；`doc/01` 已就地加「实测修正」注记（见 §7.7、C28）。

---

## 2. 仓库、目录与模块布局（冻结）

**仓库根（容器视角）**：`/data/dsh/home/workspace/code/webrtc-demo/`
**仓库根（宿主机视角）**：`/opt/dsh-workspaces/code/webrtc-demo/`（两者同一目录，uid 1000；见 `reports/01-host-recon.md` §3.5）
> `doc/05` 中的 `webrt-demo/`（拼写）与 `e:\code\project\webrt-demo`（Windows）**作废**：本项目的构建与运行全部在 Linux 宿主机上完成（t5/t10/t12），不存在 Windows 开发机。

```
code/webrtc-demo/
├── doc/                                  # 设计文档 + 本文（14-interface-contract.md）
│   └── adr/
├── app/                                  # Android 单模块（D3）
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/example/webrtcdemo/        # Kotlin 源码根（D3，不是 java/）
│       ├── cpp/                                   # C++ 源码根（D3，不是仓库根 native/）
│       │   ├── CMakeLists.txt
│       │   ├── jni/ webrtc/ encoder/ nat/ log/ util/
│       ├── java/                                  # 仅放 libwebrtc AAR 暴露的 org.webrtc 类（不改）
│       ├── jniLibs/arm64-v8a/                     # libjingle_peerconnection_so.so（AAR 抽出）
│       └── res/                                   # values/ xml/ mipmap/
├── signaling/                            # Go 信令服务（单二进制）
├── third_party/
│   ├── libwebrtc-src/                    # submodule（源码，只读对照）
│   ├── libvpx-src/                       # submodule（源码，只读对照）
│   ├── libwebrtc/                        # t5 产物（.gitignore）
│   │   ├── java/                         # libwebrtc-arm64.aar / libwebrtc-java.jar / jni/arm64-v8a/*.so
│   │   ├── lib/                          # *.a（本项目**不再链接**进自有 .so，见 §4.4）
│   │   └── include/                      # 公开头文件（对照阅读用）
│   └── libvpx/{lib,include}              # t5 产物（libvpx.a + vpx/*.h，**必须链接**）
├── scripts/                              # build/部署脚本（t5/t10/t12）
├── reports/                              # 各任务中文报告
├── settings.gradle.kts · build.gradle.kts · gradle.properties · gradlew · gradle/
└── .gitignore · .gitmodules
```

**已存在（勿重复创建）**：`scripts/t5-libwebrtc-libvpx-build.sh`（t5）、`.gitmodules`（两个 submodule）、`.gitignore`。

### 2.1 Kotlin 源文件清单（android-dev 按此命名，避免与 native-dev 对不上）

```
app/src/main/kotlin/com/example/webrtcdemo/
├── WebRtcDemoApp.kt                 # Application：日志初始化、全局异常落盘
├── MainActivity.kt                  # 单 Activity + Compose
├── config/AppConfig.kt              # BuildConfig 读取、SharedPreferences（信令 URL / 日志级别 / ICE 策略）
├── log/
│   ├── LogLevel.kt                  # VERBOSE..ERROR/OFF（与 native 数值一致，见 §6.4）
│   ├── Log.kt                       # 统一门面：Kotlin 侧唯一日志入口
│   └── FileLogger.kt                # 写 app.log、滚动、级别过滤
├── diag/
│   ├── DiagnosticsScreen.kt         # 日志级别开关 / NAT 重测 / ICE 策略切换 / 导出按钮
│   └── LogExporter.kt               # zip + FileProvider + ACTION_SEND（§9.5）
├── nativebridge/
│   ├── NativeLoader.kt              # System.loadLibrary("webrtcdemo_native")
│   ├── NativeLog.kt                 # 对应表 A-1
│   ├── NativeVp9Encoder.kt          # 对应表 A-2
│   ├── NativeNatDetector.kt         # 对应表 A-3
│   └── NativeCallbacks.kt           # 对应表 B-1（被 C++ 调用，类名不可改）
├── encoder/
│   ├── Vp9VideoEncoderFactory.kt    # org.webrtc.VideoEncoderFactory（§5.3）
│   └── Vp9VideoEncoder.kt           # org.webrtc.VideoEncoder（§5.3）
├── webrtc/
│   ├── WebRtcEngine.kt              # PeerConnectionFactory/EglBase 生命周期
│   ├── WebRtcConfig.kt              # RTCConfiguration（ICE servers / 策略）
│   ├── CallSession.kt               # 1:1 会话编排（offer/answer/ice/natType）
│   ├── PeerConnectionObserverImpl.kt# PeerConnection.Observer → 事件流
│   ├── MediaCapture.kt              # Camera2 采集 + 归一化层（§7.2）
│   ├── FrameNormalizer.kt           # CapturerObserver 包装：统一 I420（§7.2）
│   ├── VideoRendererPool.kt         # SurfaceViewRenderer 绑定（本地/远端）
│   └── StatsMapper.kt               # RTCStatsReport → model/StatsSnapshot（§7.4）
├── signaling/
│   ├── SignalingClient.kt           # okhttp WebSocket；URL 见 §8
│   ├── SignalingMessage.kt          # kotlinx.serialization，字段严格按 doc/09
│   └── ConnectionState.kt           # doc/09 §4 状态机
├── nat/NatTypeRepository.kt         # 本端（native 探测）+ 对端（信令）NAT 类型
├── model/{IceEvent.kt,NatType.kt,StatsSnapshot.kt,CallUiState.kt}
└── ui/{theme,navigation,home,call}/
```
> `doc/10` §8 的 `webrtc/WebRtcManager.kt`、`WebRtcCallbacks.kt` 更名为上表的 `webrtc/WebRtcEngine.kt`、`nativebridge/NativeCallbacks.kt`（职责不变）。

### 2.2 C++ 源文件清单（native-dev 按此命名）

```
app/src/main/cpp/
├── CMakeLists.txt
├── jni/
│   ├── jni_bridge.h / jni_bridge.cpp        # JNI_OnLoad / RegisterNatives / JavaVM 缓存
│   ├── callback_bridge.h / callback_bridge.cpp  # 调回 NativeCallbacks（表 B-1）
│   ├── native_log_jni.cpp                   # 表 A-1（4 个方法）
│   ├── vp9_encoder_jni.cpp                  # 表 A-2（9 个方法）
│   └── nat_detector_jni.cpp                 # 表 A-3（2 个方法）
├── encoder/
│   ├── vp9_encoder.h / vp9_encoder.cpp      # libvpx VP9 封装（InitEncode/Encode/SetRates/Release）
│   └── layer_bitrate_allocator.h / .cpp     # bps 矩阵 → vpx 分层码率（学习点，§5.6）
├── nat/
│   ├── nat_detector.h / nat_detector.cpp    # RFC5780 判定流程
│   └── stun_client.h / stun_client.cpp      # STUN Binding Request/Response 编解码
├── log/
│   ├── native_log.h / native_log.cpp        # 文件 + logcat 双写、滚动、级别
│   └── log_macros.h                         # NLOG_INFO(tag, fmt, ...) 等宏
└── util/
    ├── jni_util.h / jni_util.cpp            # jstring↔std::string、direct buffer 校验
    └── thread_util.h                        # 线程命名、JavaVM attach/detach RAII
```
> `doc/11` §1 的 `jni/peer_connection_jni.*`、`webrtc/peer_connection_manager.*`、`webrtc/stats_collector.*`、`webrtc/video_sink_adapter.*` **删除**——这些职责已移到 Kotlin（org.webrtc）侧，见 §7。

---

## 3. 技术栈与版本冻结

### 3.1 Android / Kotlin / 构建

| 组件 | 冻结值 | 依据 |
|---|---|---|
| JDK | **17**（`org.gradle.java.home` / `JAVA_HOME` 指向 JDK 17） | D2 |
| Gradle | **8.7**（wrapper，`gradle-8.7-bin.zip`） | AGP 8.5 要求 ≥8.7 |
| AGP | **8.5.2** | D2 |
| Kotlin | **2.0.21** | D2 |
| Compose Compiler | 插件 **`org.jetbrains.kotlin.plugin.compose`，版本 = Kotlin 版本（2.0.21）**；Kotlin 2.0 起**不再用** `composeOptions.kotlinCompilerExtensionVersion` | D2（Kotlin 2.0 必做项） |
| Compose BOM | **2024.10.01** | D2 |
| compileSdk / targetSdk / minSdk | **34 / 34 / 26** | `doc/10` §1（minSdk 26 与 `ANDROID_PLATFORM=android-26` 一致） |
| NDK | **26.1.10909125**（r26b），`app/build.gradle.kts` 写 `ndkVersion` | 供 AGP/CMake 与 t5 编 libvpx 共用 |
| ABI | **仅 `arm64-v8a`** | ADR-006 附带决定 |
| CMake | AGP 内置 **3.22.1**（`externalNativeBuild.cmake.version = "3.22.1"`） | `doc/11` §2 |
| STL | **`c++_shared`**（`-DANDROID_STL=c++_shared`） | 自有 .so 与 libvpx 必须同 STL；见 §4.2 警告 |
| Java/Kotlin 编译目标 | `sourceCompatibility/targetCompatibility = 17`，`jvmTarget = "17"` | D2 |
| 序列化 | kotlinx.serialization **1.7.3**（插件版本 = Kotlin 2.0.21） | `doc/10`（1.6.3 → 升到 2.0.x 兼容版本） |
| WebSocket | okhttp **4.12.0** | `doc/10` §1 |
| 协程 | kotlinx-coroutines-android **1.8.1** | `doc/10` |
| Navigation | androidx.navigation:navigation-compose **2.7.7** | `doc/10` |
| Lifecycle | 2.8.6（viewmodel-compose / runtime-compose） | 兼容 Compose BOM 2024.10 |
| Activity | activity-compose **1.9.2** | 兼容 |
| libwebrtc Java SDK | t5 产出的 `libwebrtc-arm64.aar`（M129 / `branch-heads/6613`） | §4.3 |
| **不使用** | CameraX（`doc/10` §6.1 作废）、Ktor、Glide/Coil | D1 |

### 3.2 Go（信令）

| 组件 | 冻结值 |
|---|---|
| Go | **1.22.x**（实测 `go1.22.12`，路径 `<WS>/go/bin/go`，环境脚本 `<WS>/env-go.sh`） |
| module | `webrtcdemo-signaling` |
| WebSocket | `github.com/gorilla/websocket v1.5.1`（`doc/12` 与 `doc/09` §8 一致） |
| 日志 | **`github.com/sirupsen/logrus`（D6 覆盖 `doc/12` §1 的 `log/slog`）** |
| 部署 | systemd，二进制 `/opt/signaling/signaling`，日志文件 **`/var/log/signaling/signaling.log`**（由 `-log <path>` 指定；旧值 `/opt/signaling/logs/…` 已被 t12 部署事实取代，见 §10 C31） |
| GOPROXY | `https://goproxy.cn,direct`（t13 实测 `proxy.golang.org` 仅 0.28 MB/s） |

### 3.3 宿主机与工具链实测基线（t1，供 t10/t5 引用）

| 项 | 实测值 |
|---|---|
| OS / 内核 | Ubuntu 24.04.2 LTS / 6.8.0-63 x86_64 |
| CPU / 内存 / 磁盘 | 4 vCPU（2 物理核 × 2 HT）/ 7.1 GiB / 可用 61 GB，**无 swap** |
| 公网 IP | `47.238.144.66` |
| SSH | `ssh -i /home/node/.ssh/id_ed25519 root@172.21.0.219 -p 5766`（容器内即 workspace 的宿主） |
| 工作区 | `/opt/dsh-workspaces`（= 容器 `/data/dsh/home/workspace`），uid 1000 |
| libwebrtc 构建目录 | `/opt/dsh-workspaces/webrtc-build`（`src/`、`out/Release-arm64/`、`depot_tools/`、`logs/`） |
| Android SDK | `/opt/dsh-workspaces/android-sdk`（NDK 预期 `/opt/dsh-workspaces/android-sdk/ndk/26.1.10909125`） |
| googlesource 拉取速率 | ≈0.32 MB/s（**t5 最大时间风险**，见 §11 R1） |

---

## 4. 构建契约

### 4.1 Android 构建（Gradle）

必须产物：仓库根 `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradlew`/`gradle/wrapper/*`、`app/build.gradle.kts`。

**settings.gradle.kts**：`pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`，`dependencyResolutionManagement { repositories { google(); mavenCentral(); flatDir { dirs("third_party/libwebrtc/java") } } }`，`include(":app")`。

**app/build.gradle.kts 关键片段（冻结值）**：
```kotlin
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")            // Kotlin 2.0 必需
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  namespace = "com.example.webrtcdemo"                  // D3
  compileSdk = 34
  ndkVersion = "26.1.10909125"

  defaultConfig {
    applicationId = "com.example.webrtcdemo"            // D3
    minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0"
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild {
      cmake {
        arguments += listOf(
          "-DANDROID_STL=c++_shared",
          "-DANDROID_PLATFORM=android-26",
          "-DWEBC_THIRD_PARTY=${rootProject.projectDir}/third_party"   // 冻结：路径由 Gradle 注入
        )
      }
    }
    buildConfigField("String", "SIGNALING_URL", "\"ws://47.238.144.66:8443/ws\"")   // §8
  }
  buildFeatures { compose = true; buildConfig = true }
  externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
  packaging { jniLibs { useLegacyPackaging = false } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildTypes {
    debug   { isMinifyEnabled = false; buildConfigField("boolean", "LOG_DEFAULT_DEBUG", "true") }
    release { isMinifyEnabled = true; isShrinkResources = true
              proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
              buildConfigField("boolean", "LOG_DEFAULT_DEBUG", "false") }
  }
  sourceSets["main"].java.srcDirs("src/main/kotlin")     // Kotlin 根 = src/main/kotlin（D3）
}

dependencies {
  implementation(files(rootProject.file("third_party/libwebrtc/java/libwebrtc-java.jar")))  // org.webrtc.*
  // Compose BOM 2024.10.01 / material3 / activity-compose 1.9.2 / navigation-compose 2.7.7
  // lifecycle 2.8.6 / okhttp 4.12.0 / kotlinx-serialization-json 1.7.3 / coroutines 1.8.1
  implementation("androidx.core:core-ktx:1.13.1")
}
```

**native 库打包（两种其一，冻结为方案 ①）**：
1. `libjingle_peerconnection_so.so` 由 `env-installer`（t10）从 AAR 抽出后放 `app/src/main/jniLibs/arm64-v8a/`；
2. 备选：在依赖里加 `implementation(files(rootProject.file("third_party/libwebrtc/java/libwebrtc-arm64.aar")))` 并依赖 AAR 自带的 `jni/`。若走 ②，必须验证 APK 内 `lib/arm64-v8a/libjingle_peerconnection_so.so` 存在。
   > **笔误修正（2026-09-13）**：此处原文误写为 `files("../../third_party/libwebrtc/java/libwebrtc-arm64.aar")`。`files()` 的相对路径按 `:app` 项目目录解析，`../../` 会落到**仓库外**；必须与主路径（上面的 `rootProject.file("third_party/libwebrtc/java/libwebrtc-java.jar")`，原文第 266 行）保持一致，改用 `rootProject.file("third_party/...")`（或等价的 `../third_party/...`）。

**ProGuard（release）**：必须保留 `org.webrtc.**` 与 **4 个** `nativebridge` 类（`NativeLog` / `NativeVp9Encoder` / `NativeNatDetector` / `NativeCallbacks`，见 §6.1）（否则 `RegisterNatives` 的 `FindClass` 失败）：
```
-keep class org.webrtc.** { *; }
-keep class com.example.webrtcdemo.nativebridge.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
```
**依赖 JNI 的类不可被混淆/改名**：`NativeLog`、`NativeVp9Encoder`、`NativeNatDetector`、`NativeCallbacks` 的**类名与包名冻结**（§6.1）。

**构建命令（t10 使用）**：
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/dsh-workspaces/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
cd /opt/dsh-workspaces/code/webrtc-demo
./gradlew :app:assembleDebug        # 先 debug（不混淆，便于排障）
./gradlew :app:assembleRelease      # 再 release（APK 交付物）
```

### 4.2 CMake 契约（`app/src/main/cpp/CMakeLists.txt`）

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(webrtcdemo_native CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 冻结：由 Gradle 注入 -DWEBC_THIRD_PARTY=<repo>/third_party；无注入时回退相对路径
if(NOT DEFINED WEBC_THIRD_PARTY)
  set(WEBC_THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
endif()
set(LIBVPX_PATH ${WEBC_THIRD_PARTY}/libvpx)

add_library(vpx STATIC IMPORTED)
set_target_properties(vpx PROPERTIES IMPORTED_LOCATION ${LIBVPX_PATH}/lib/libvpx.a)
target_include_directories(vpx INTERFACE ${LIBVPX_PATH}/include)

add_library(webrtcdemo_native SHARED
  jni/jni_bridge.cpp jni/callback_bridge.cpp jni/native_log_jni.cpp
  jni/vp9_encoder_jni.cpp jni/nat_detector_jni.cpp
  encoder/vp9_encoder.cpp encoder/layer_bitrate_allocator.cpp
  nat/nat_detector.cpp nat/stun_client.cpp
  log/native_log.cpp util/jni_util.cpp)

target_include_directories(webrtcdemo_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${LIBVPX_PATH}/include)
target_compile_options(webrtcdemo_native PRIVATE -fvisibility=hidden -fno-exceptions -fno-rtti -Wall -Wextra -Wno-unused-parameter)
target_link_options(webrtcdemo_native PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(webrtcdemo_native vpx log android)
```
**契约要点**
- **不链接 `libwebrtc.a`**：自有 native 库与 libwebrtc 之间只有 JNI 边界（见 §5.1 否决理由）。CMake 中**禁止**出现 `libwebrtc.a` / `libjingle_peerconnection_so.so` 的链接。
- 库名冻结：**`webrtcdemo_native`** → `libwebrtcdemo_native.so`，Kotlin 侧 `System.loadLibrary("webrtcdemo_native")`。
- STL 冻结为 `c++_shared`；libvpx 也必须用同一 NDK/libc++（§4.4）。
- 禁止 C++ 异常与 RTTI（与 libwebrtc 风格一致，ADR-007）。

### 4.3 libwebrtc 产物契约（**回写 t5**）

> `doc/08` §7 只提取 `.a` + 头文件，**不足以支撑 D1**（D1 要官方 Java SDK）。t5 必须额外提供 Java SDK 产物。
> 现有 `scripts/t5-libwebrtc-libvpx-build.sh` 已按此方向编写（含 `phase_aar`），本节把产物**路径与文件名冻结**。

**产物落点（宿主机绝对路径）**：

| 产物 | 路径（必须存在） | 用途 |
|---|---|---|
| Java SDK AAR | `third_party/libwebrtc/java/libwebrtc-arm64.aar` | 归档 + 抽出 jar/so |
| Java 类 jar | `third_party/libwebrtc/java/libwebrtc-java.jar`（= AAR 内 `classes.jar`） | Gradle `files(...)` 依赖，提供 `org.webrtc.*` |
| JNI 共享库 | `third_party/libwebrtc/java/jni/arm64-v8a/libjingle_peerconnection_so.so`（同时放 `app/src/main/jniLibs/arm64-v8a/`） | PeerConnection/ICE/编解码/渲染的 native 实现 |
| 公开头文件 | `third_party/libwebrtc/include/**`（含 `api/`、`sdk/android/`、`rtc_base/`、`modules/`、`third_party/abseil-cpp/absl`、`third_party/libyuv`） | 对照阅读 + 未来 A1 升级路径（§5.7） |
| 静态库 | `third_party/libwebrtc/lib/*.a` | **本项目不链接**，仅作对照/升级路径备料 |
| libvpx | `third_party/libvpx/{lib/libvpx.a, include/vpx/*.h}` | 自由编码器链接（§4.4） |

**GN 参数（冻结，t5 `phase_gn` 的 `ARGS` 必须与之等价）**：
```
target_os="android" target_cpu="arm64" is_debug=false is_component_build=false
rtc_include_tests=false treat_warnings_as_errors=false is_clang=true use_sysroot=true
symbol_level=0 rtc_build_examples=false rtc_build_tools=false
enable_resource_allowlist_generation=false
```
- **`use_custom_libcxx` 不得显式设为 `false`**（Android 目标不支持，t5 脚本注释已记录）：libwebrtc 的 Android 产物静态链接自家 libc++，这也是**否决**"自有 .so 链接 libwebrtc.a"路线的原因之一（§5.1）。
- 分支：锁定 `refs/branch-heads/6613`（M129 stable），失败回退 tip-of-tree；无论哪种，**t5 报告必须写明最终 HEAD SHA 与 `src/` 的 `git rev-parse HEAD`**（可复现性）。
- 并行度：`ninja -j2`（可用内存 ≥4 GiB）／链接阶段 `-j1`；`symbol_level=0` 必须保留（无 swap）。
- 构建目标（**官方打包脚本使用的两个 target，无需全量 `ninja all`**）：
  ```bash
  cd /opt/dsh-workspaces/webrtc-build/src
  ninja -C out/Release-arm64 sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so
  #   Java jar:      out/Release-arm64/lib.java/sdk/android/libwebrtc.jar
  #   JNI 共享库:    out/Release-arm64/libjingle_peerconnection_so.so   （另有 lib.unstripped/ 副本）
  ```
  等价（推荐，逐字节对齐官方产物）：`python3 tools_webrtc/android/build_aar.py --arch arm64-v8a --output <BUILD>/libwebrtc-arm64.aar`
  （官方脚本的 `TARGETS = ['sdk/android:libwebrtc','sdk/android:libjingle_peerconnection_so']`、`JAR_FILE='lib.java/sdk/android/libwebrtc.jar'`、`NEEDED_SO_FILES=['libjingle_peerconnection_so.so']`）。
  **jar 与 so 缺一即为 t5 未完成。**
- `is_component_build` **必须为 `false`**（webrtc 独立构建有 assert）；官方脚本基线可加 `android_static_analysis="off"`。
- 静态库：如仍需 `third_party/libwebrtc/lib/*.a`（仅作对照 / §5.7 备料），另跑 `ninja -C out/Release-arm64 webrtc`；**不得**把它链接进 `libwebrtcdemo_native.so`（§4.2）。
- **日志相关 GN 开关见 §9.7**（这是本文对 t5 的第二处回写）。

### 4.4 libvpx 产物契约（交叉编译）

| 项 | 冻结值 |
|---|---|
| 源码 | submodule `third_party/libvpx-src`（t3 已建）+ t5 内 `$BUILD/libvpx-src` |
| target | `armv8-android-gcc`（失败回退 `arm64-android-gcc`） |
| NDK | **优先 `/opt/dsh-workspaces/android-sdk/ndk/26.1.10909125`**，回退 webrtc 内 toolchain |
| 开关 | `--enable-vp9 --enable-vp9-encoder --enable-vp9-decoder --disable-vp8-* --enable-static --disable-shared --disable-examples --disable-tools --disable-docs --disable-unit-tests --disable-runtime-cpu-detect --enable-pic` |
| 产物 | `third_party/libvpx/lib/libvpx.a` + `third_party/libvpx/include/vpx/*.h` |
| 校验 | `file third_party/libvpx/lib/libvpx.a` 必须含 `aarch64`；头文件必须含 `vpx_codec.h`、`vpx_encoder.h`、`vpx_image.h`、`vp8cx.h`(=VP9 cx 接口头) |

**AArch64 与 STL 警告（硬约束）**：`libvpx.a` 只依赖 libc，不含 C++ 标准库；链接进 `libwebrtcdemo_native.so`（`c++_shared`）无 ABI 冲突。**若 t5 用 webrtc 的 `use_custom_libcxx` 工具链编 libvpx 并开启 C++ 特性，则告警并在报告中记录**。

**实测补充（2026-09-13）**：`third_party/libvpx/lib/libvpx.a`（arm64）**已产出并核验**——`llvm-ar` 解出首个目标文件经 `file` 判为 `ELF 64-bit LSB relocatable, ARM aarch64`；`vpx_codec.h` / `vpx_encoder.h` / `vp8cx.h`（VP9 编码器接口头） / `vpx_image.h` 齐全。**注意**：`doc/08` §9.3 给出的 libvpx 交叉编译接口**已过期**（现代 libvpx 不再接受 `--sdk-path`，`armv8-android-gcc` 亦不可用），本契约 §4.4 的开关列表与 NDK 口径仍有效；可复现命令以 `scripts/t5-libwebrtc-libvpx-build.sh` 与 t5 报告为准，`doc/08` 已加「实测补充」注记（保留原文）。

### 4.5 环境与工具链冻结（t10 执行）

```bash
# JDK / CMake / ninja / autotools（apt 可用，t1 已核验候选版本）
apt-get install -y openjdk-17-jdk cmake ninja-build pkg-config autoconf automake libtool bison flex gperf yasm nasm zip
# Android SDK（cmdline-tools + platform 34 + build-tools 34 + NDK 26.1.10909125 + platform-tools）
# 全部落到 /opt/dsh-workspaces/android-sdk，并导出 ANDROID_HOME/ANDROID_SDK_ROOT/ANDROID_NDK_HOME
```
- 所有由宿主机 root 在共享目录创建的文件/目录，**必须** `chown -R 1000:1000`（t1 §3.5 已固化的约定）。
- `swap`：t1 §7.1 建议 ≥4 GB；是否创建由 captain 决定，本契约不强制。

---

## 5. 编码器注入路线（唯一权威，A1）

### 5.1 结论与备选否决理由

**结论**：采用
> **Kotlin 侧实现 `org.webrtc.VideoEncoderFactory` + `org.webrtc.VideoEncoder` 适配器；逐帧经自有 JNI（`NativeVp9Encoder`）调用 C++ libvpx VP9 编码器（`libwebrtcdemo_native.so`）。**

即"Java VideoEncoderFactory 经 JNI 回调 C++ 编码器"。自研编码器**不实现 `webrtc::VideoEncoder` C++ 接口**（登记为 ADR-002 的受控偏离，理由见 §5.1.1 与 §5.6）。

被否决的两条路线：

**(a) 原生 factory + 帧桥接**（自建 C++ `PeerConnectionFactory`，把 Java 采集帧桥进 C++）——否决：
1. 与 D1 直接冲突：采集/渲染必须来自官方 Java SDK，而 Java 采集器产出的是 Java `VideoFrame`；要进 C++ 需跨 JNI 双向搬运或改用 C++ 采集（后者违反 D1）。
2. 要把 libwebrtc 的 C++ 栈链进自有 `.so`，就会在同一进程出现**两份 libwebrtc**（且各自静态链接一份 libc++：Chromium 的 libc++ 使用 `std::__Cr` 内联命名空间，与 NDK `c++_shared` 不同），`std::string`/`rtc::scoped_refptr` 跨 `.so` 传递属未定义行为；APK 体积也会被放大。

**(b) 扩展 Java SDK 的自有 JNI 入口**（在 webrtc 源码树内加 GN target、自行编译/注册 `webrtc::VideoEncoder`）——否决：
1. 必须把"编产物"升级为"改源码构建"（新增 GN target、随源码树编译），在 **7.1 GiB / 无 swap / googlesource≈0.32 MB/s** 的宿主机上风险过高（见 §11 R1/R2）。
2. 与"只替换编码器、不动 libwebrtc 其余部分"的边界原则冲突；libwebrtc 换版本时自有补丁的同步成本高。

> **事实澄清（重要，纠正任务前提）**：官方 Java SDK **确实**暴露了一个 native 直通口：`org.webrtc.VideoEncoder.createNative(long webrtcEnvRef)` 返回非 0 时，`JavaToNativeVideoEncoder()` 会把该值当作 `webrtc::VideoEncoder*` 直接使用（证据：`sdk/android/src/jni/video_encoder_wrapper.cc` 的 `JavaToNativeVideoEncoder` / `VideoEncoder.java` 的接口注释）。所以"Java SDK 没暴露该入口"并不成立——**存在入口，但其使用前提是与 SDK 同 libc++/absl 环境编译**，因此它属于路线 (b) 的实现方式，本轮否决；契约把它的接口位预留为 §5.7 的升级路径（`createNative(long)` 必须存在且返回 `0L`）。
> **⚠️ 实测修正（2026-09-13，t10 首次真实编译 + 源码复核）**：本节原文写作 `createNativeVideoEncoder()`，**该名称在 libwebrtc 中不存在**；真实方法为 **`default long createNative(long webrtcEnvRef)`**（源码原文：`third_party/libwebrtc-src/sdk/android/api/org/webrtc/VideoEncoder.java` 第 326 行 `default long createNative(long webrtcEnvRef) {`，其注释亦写 "createNative() should return zero"）。**名称已全文修正**（§5.1 / §5.4 / §5.7 及 V16），**语义不变**（本轮返回 `0L`、作为 §5.7 的升级单点）。

#### 5.1.1 受控偏离声明（必须写进 native-dev / android-dev 报告）
| 偏离项 | 原文档 | 契约裁定 | 理由 |
|---|---|---|---|
| 编码器不实现 `webrtc::VideoEncoder` | ADR-002、`doc/11` §5 | 实现同形状的 `Vp9Encoder`（自研 C++）+ `org.webrtc.VideoEncoder` 适配器 | 见 §5.1 (a)/(b) 与 §5.6 的 SDK 硬约束 |
| SVC 空间层降级为 L1T3 | ADR-002、`doc/02` §6、`doc/11` §5.2 | 空间层固定 1、时序层 3；分层分配由本项目策略实现 | §5.6（源码级约束，非选择） |
| `doc/11` 的 C++ `PeerConnectionManager` / `StatsCollector` / `VideoSinkAdapter` | `doc/11` §4/§7 | 删除，改由 Kotlin 用 org.webrtc 实现 | D1 |

### 5.2 调用链（权威实现路径）

```
Camera2Capturer(官方 SDK, Java)
  → FrameNormalizer（CapturerObserver 包装，统一 I420）                 §7.2
  → VideoSource → VideoTrack → PeerConnection(官方 SDK)
        │
        └─ VideoStreamEncoder(SDK 内部, native)
             ├─ SetRates → VideoEncoderWrapper::SetRates
             │     └─ Java_VideoEncoder_setRateAllocation(encoder, BitrateAllocation(3×3 int), framerate)
             │           → Vp9VideoEncoder.setRateAllocation()                       [Kotlin]
             │                 → NativeVp9Encoder.nativeSetRates(handle, int[], S, T, total, fps)
             │                       → Vp9Encoder::SetRates()                        [C++]
             │                             → layer_bitrate_allocator：矩阵 → ss/ts/layer_target_bitrate
             │                             → vpx_codec_enc_config_set()               ★ 学习点
             └─ Encode(VideoFrame, frame_types) → VideoEncoderWrapper::Encode
                   └─ Java_VideoEncoder_encode(encoder, jFrame, EncodeInfo)
                         → Vp9VideoEncoder.encode()                               [Kotlin]
                               → I420 直达平面（direct ByteBuffer）→ NativeVp9Encoder.nativeEncode()
                                     → Vp9Encoder::Encode → vpx_codec_encode()     ★ 学习点
                                     → vpx_codec_get_cx_data → 内部拷贝暂存
                               → NativeVp9Encoder.nativeCopyEncodedFrame(dst, meta)
                               → EncodedImage.builder()…createEncodedImage()
                               → callback.onEncodedFrame(image, CodecSpecificInfoVP9())
                         → VideoEncoderWrapper::OnEncodedFrame → SDK EncodedImageCallback
```
采集 / 渲染 / PeerConnection / ICE / GCC / RTCP / DTLS 全部在官方 SDK 内（D1）；自研代码只出现在"编码这一格"。

### 5.3 所需产物（冻结）

| # | 产物 | 来源 | 必需性 |
|---|---|---|---|
| 1 | `third_party/libvpx/lib/libvpx.a` + `include/vpx/*.h` | t5 | **必需**（自由编码器链接） |
| 2 | `third_party/libwebrtc/java/libwebrtc-java.jar`（含 `org.webrtc.VideoEncoder`/`VideoEncoderFactory`/`VideoCodecInfo`/`EncodedImage`） | t5 | **必需** |
| 3 | `libjingle_peerconnection_so.so`（含 `VideoEncoderWrapper` 的 native 侧） | t5 | **必需** |
| 4 | `libwebrtcdemo_native.so` | 本项目 CMake | **必需** |
| 5 | `third_party/libwebrtc/lib/*.a`、`include/**` | t5 | **不链接**；仅对照阅读 + §5.7 升级备料 |

### 5.4 Kotlin 适配器契约（android-dev）

**`encoder/Vp9VideoEncoderFactory.kt` — `class Vp9VideoEncoderFactory(private val fallback: VideoEncoderFactory? = null) : org.webrtc.VideoEncoderFactory`**

| 方法 | 冻结行为 |
|---|---|
| `createEncoder(info: VideoCodecInfo?): VideoEncoder?` | `info == null` → `null`；`info.name.equals("VP9", ignoreCase = true)` → `Vp9VideoEncoder()`；否则 `fallback?.createEncoder(info)` |
| `getSupportedCodecs(): Array<VideoCodecInfo>` | `arrayOf(VideoCodecInfo("VP9", mapOf("profile-id" to "0")))` + `fallback?.supportedCodecs`（若有） |
| `getImplementations(): Array<VideoCodecInfo>` | 与 `getSupportedCodecs()` 相同 |
| 日志 | 创建时打 `encoder created impl=SelfVp9Libvpx codec=VP9 profile=0` |

**`encoder/Vp9VideoEncoder.kt` — `class Vp9VideoEncoder : org.webrtc.VideoEncoder`**

| 成员 | 冻结行为 |
|---|---|
| `initEncode(settings: Settings, cb: Callback): VideoCodecStatus` | `handle = nativeCreate()`（0 → `ERROR`）；`nativeInit(handle, settings.width, settings.height, settings.startBitrate*1000, 0 /*maxBps 由 SetRates 决定*/, settings.maxFramerate, /*S=*/1, /*T=*/3)`；保存 `cb`；返回 `OK`/`ERROR` |
| `encode(frame: VideoFrame, info: EncodeInfo): VideoCodecStatus` | 1) `buf = frame.buffer`；`i420 = if (buf is VideoFrame.I420Buffer) buf else buf.toI420()`（若为新建对象，调用后必须 `release()`）；2) `keyFrame = info.frameTypes.firstOrNull() == EncodedImage.FrameType.VideoFrameKey`；3) `rc = nativeEncode(handle, i420.dataY, i420.dataU, i420.dataV, i420.width, i420.height, i420.strideY, i420.strideU, i420.strideV, frame.timestampNs, rotationDegrees(frame.rotation), keyFrame)`；4) `rc == OK` → 取回帧并回调（见下），返回 `OK`；`rc == NO_OUTPUT` → `NO_OUTPUT`；其余 → `ERROR` |
| 取回帧 | 若 `nativeGetEncodedFrameSize(handle) > dst.capacity` 则按需扩容 `dst`（上限 8 MiB，超限记 ERROR 并返回 `ERROR`）；`nativeCopyEncodedFrame(handle, dst, meta)`；`EncodedImage.builder().setBuffer(dst, null).setEncodedWidth(meta[0]).setEncodedHeight(meta[1]).setCaptureTimeNs(frame.timestampNs).setFrameType(if (meta[2]==1) VideoFrameKey else VideoFrameDelta).setRotation(0).setQp(meta[5].takeIf { it >= 0 }).createEncodedImage()`；`cb.onEncodedFrame(img, VideoEncoder.CodecSpecificInfoVP9())` |
| `setRateAllocation(allocation: BitrateAllocation, framerate: Int): VideoCodecStatus` | 展平 `allocation.bitratesBbs`（`[spatial][temporal]`，运行时长度可能为 3×3）为 `IntArray`，索引 `s*T+t`，`S=bitratesBbs.size`，`T=bitratesBbs[0].size`；`total = allocation.sum`；`nativeSetRates(handle, layerBps, S, T, total, framerate)`；并把 `total`/`framerate` 记入日志与 UI（`encoderBitrate`） |
| `release(): VideoCodecStatus` | `nativeRelease(handle)`；`handle = 0`；返回 `OK` |
| `getScalingSettings(): ScalingSettings` | `ScalingSettings.OFF`（质量缩放交给本项目策略，避免与 SDK quality scaler 双控制） |
| `isHardwareEncoder()` | `false` |
| `getImplementationName()` | **固定 `"SelfVp9Libvpx"`**（verifier 用它核对 `outbound-rtp.encoderImplementation`） |
| `createNative(long webrtcEnvRef)` | `0L`（本轮保持 Java 路径；§5.7 升级时才返回 native 指针）。**实测修正（2026-09-13）**：真实 API 名是 `createNative`（非 `createNativeVideoEncoder`，见 §5.1 注记） |
| `getResolutionBitrateLimits()` | 按 `doc/11` §5.3：`(320*180, 0, 0, 500_000)`、`(640*360, 0, 0, 1_000_000)`、`(1280*720, 0, 0, 2_000_000)` |

**线程与阻塞约束（硬性）**：上表所有方法都在 **libwebrtc 的编码线程**被调用（`video_encoder_wrapper.cc`：先 `AttachCurrentThreadIfNeeded()` 再调 Java）。因此：
- Kotlin 实现**不得**做文件/网络 I/O、**不得**等待主线程、**不得**加长锁；
- `encode()` 是同步调用，vpx 编码耗时算在这条线程上；实测单帧 >33 ms 时必须记 WARN 并按 §5.5 调 `cpu-used`；
- `setRateAllocation` 与 `release` 可能来自不同线程，Kotlin 侧用 `@Volatile handle` + 单实例锁保护。

### 5.5 C++ 编码器契约（native-dev）

类 `webrtcdemo::Vp9Encoder`（自研，**不**继承 `webrtc::VideoEncoder`，避免 与 SDK 的 C++ ABI 依赖）：

```cpp
struct EncoderConfig { int width, height, start_bitrate_bps, max_bitrate_bps, max_framerate, num_spatial_layers, num_temporal_layers; };
struct I420Frame { const uint8_t* y; const uint8_t* u; const uint8_t* v;
                   int width, height, stride_y, stride_u, stride_v;
                   int rotation_degrees; int64_t capture_time_ns; };
struct LayerBitrate { int32_t layer_bps[3][3]; int num_spatial, num_temporal, total_bps, framerate_fps; };
```

| vpx 配置项 | 冻结值 | 说明 |
|---|---|---|
| `g_w` / `g_h` | InitEncode 传入（对齐到偶数；rotation 90/270 时交换） | |
| `g_lag_in_frames` | `0` | 零延迟 |
| `g_threads` | `1` | 不额外起线程 |
| `g_error_resilient` | `VPX_ERROR_RESILIENT_DEFAULT` | |
| `rc_end_usage` | `VPX_CBR` | |
| `rc_min_quantizer` / `rc_max_quantizer` | `4` / `56` | 与 `doc/11` 一致 |
| `rc_undershoot_pct` / `rc_overshoot_pct` | `50` / `50` | |
| `rc_buf_sz` / `rc_buf_initial_sz` / `rc_buf_optimal_sz` | `600` / `400` / `500`（ms） | |
| `rc_dropframe_thresh` | `0` | 不主动丢帧（丢帧由 GCC 决定） |
| `kf_mode` / `kf_min_dist` / `kf_max_dist` | `VPX_KF_AUTO` / `0` / `3000` | 关键帧上限 3 s |
| `ss_number_layers` | `1`（**固定**，见 §5.6） | |
| `ts_number_layers` | `3`（`L1T3`；可配 1 便于对照实验） | |
| `vpx_codec_control(VP8E_SET_CPUUSED)` | `8` | 移动端实时软编 |
| `vpx_codec_encode` deadline | `VPX_DL_REALTIME` | |

其它冻结行为：
- `Encode` 支持 `request_key_frame` → `VPX_EFLAG_FORCE_KF`；
- 用 `vpx_codec_get_cx_data` 遍历取包，遇 `VPX_CODEC_CX_FRAME_PKT` 即**拷贝**到内部缓冲（vpx 缓冲在下次 encode 后可能被复用，禁止保留指针）；
- 记录 `size` / `is_keyframe`（`pkt->data.frame.flags & VPX_FRAME_IS_KEY`）/ `spatial_layer_id` / `temporal_layer_id` / `qp`（**`VP8E_GET_LAST_QUANTIZER`**，未知为 -1）；
  > **API 名称适配（2026-09-13，t7 实测）**：契约原写 `VP9E_GET_LAST_QUANTIZER`，该宏**在 libvpx 中不存在**；VP9 编码器控制走共用控制表，正确名是 **`VP8E_GET_LAST_QUANTIZER`**（见 `vp9/vp9_cx_iface.c` 的控制表）。属**名称适配、非偏离**（§11.4 D-4 同类登记）。
- 返回值即 §6.5 状态码；`FALLBACK_SOFTWARE(-13)` 只在 vpx 初始化彻底失败时返回，且必须 ERROR 级落日志；
- 编码器对象单线程使用（编码线程），但 `Release` 与 `Encode` 之间用 `std::mutex` 防御；`handle` 失效后任何调用返回 `UNINITIALIZED` 且不崩溃；
- `layer_bitrate_allocator.{h,cpp}` 是**核心学习点文件**：必须逐行中文注释说明"`VideoBitrateAllocation` 的 `[spatial][temporal]` → `ss_target_bitrate[]`/`ts_target_bitrate[]`/`layer_target_bitrate[]`/`rc_target_bitrate`"的换算与累计关系，并注明对照 `modules/video_coding/codecs/vp9/libvpx_vp9_encoder.cc::SetSvcRates()`。

### 5.6 分层码率的现实边界（**冻结，含源码证据**）

经源码核验（`sdk/android/src/jni/video_encoder_wrapper.cc`）：
1. Java 编码器路径使用 `ScalableVideoControllerNoLayering`；`ParseCodecSpecificInfo()` 对 VP9 硬编码
   `info.codecSpecific.VP9.num_spatial_layers = 1;`、`temporal_idx = kNoTemporalIdx;`、`inter_layer_predicted = false;`
   （即"流里只有 1 个空间层、无时序层索引"）。
2. `org.webrtc.EncodedImage` 无 `spatialIndex`/`temporalIndex` 字段（`builder()` 只有 buffer/width/height/captureTimeNs/frameType/rotation/qp）；
   `VideoEncoder.CodecSpecificInfoVP9` 是**空类**；`VideoEncoderWrapper.createEncoderCallback` 的回调甚至只把 `EncodedImage` 传回 native。
3. `org.webrtc.VideoCodecInfo` 只有 `name` + `params`，**没有 `scalabilityMode`**，因此无法通过 Java 工厂在 SDP 协商 `L2T3`。

**因此冻结**：
1. **必做（本项目"分层码率分配"的实现落点）**：时序分层 `L1T3`。Java 侧收到的是完整 3×3 `BitrateAllocation`（SDK 的 `ToJavaBitrateAllocation` 按 `kMaxSpatialLayers(3) × kMaxTemporalStreams(3)` 填），本路线下仅 `[0][0]` 非 0；C++ 侧把该总码率**按本项目策略**分配到 3 个时序层（默认累计 `ts_target_bitrate = {40%, 70%, 100%} × total`，`ts_rate_layer_decimator = {2,1,1}`），再 `vpx_codec_enc_config_set()`。这就是"GCC → 码率分配 → vpx 分层配置 → 输出码率"闭环的可见末端。
2. **禁止**在没有 captain 批准时把 `ss_number_layers` 改为 >1：无法协商 SVC，SDP 与实际打包不一致会导致对端解码异常。
3. 契约保证的**学习目标全部保留**：解析码率矩阵、亲手算 `ss/ts_target_bitrate` 与 `rc_target_bitrate` 的累计关系、`vpx_codec_enc_config_set` 闭环、对照 `LibvpxVp9Encoder::SetSvcRates()`；仅"SVC 空间层信令"降级为文档化边界。
4. **L1T3 下的实际生效路径（2026-09-13，t7 源码级实测；captain 裁决保留）**：
   - **`layer_target_bitrate[]` 只在 `ss_number_layers > 1` 时才被 libvpx 使用**（`vp9/encoder/vp9_svc_layercontext.c`）；因此本项目 **L1T3（`ss_number_layers=1`）下它不是生效字段**，真正生效的是 **`rc_target_bitrate`**（总目标码率），时序分层的帧率/预算由 **`temporal_layering_mode`** 决定。
   - 因此实现侧**必须额外显式设置 `vpx_codec_control(VP8E_SET_TEMPORAL_LAYERING_MODE, …)`**：T=3 → **`VPX_TS_LAYERNUM_…` 对应的 `MODE_0212`**（与 libvpx 自动选择一致，显式设置可防止默认值漂移）。
   - **仍要求完整写入** `ss_target_bitrate[]` / `ts_target_bitrate[]` / `layer_target_bitrate[]` / `rc_target_bitrate`（学习点不缩水，对照 `SetSvcRates()` 时字段一一对应），但 **verifier 不得**用 V21/V22 去"验证 `layer_target_bitrate` 生效"——它在本配置下**本就不生效**（见 §11.4 D-5 与 V21 注记）。

### 5.7 升级路径（本轮不实施，契约预先占位）

若后续要真正的 `webrtc::VideoEncoder` + SVC：
1. t5 保留 `third_party/libwebrtc/{lib,include}`（本契约已要求）；
2. 在 webrtc 源码树内新增 GN `rtc_shared_library` target（与 SDK 同 libc++/absl/编译参数）实现 `webrtc::VideoEncoder`；
3. `Vp9VideoEncoder.createNative(long webrtcEnvRef)` 改为返回该 native 指针（`JavaToNativeVideoEncoder` 会直接使用）。
前置条件：captain 批准 + 宿主机资源升级。契约冻结的接口位：**`createNative(long)` 必须存在，本轮返回 `0L`**（**真实 API 名，2026-09-13 实测修正**，见 §5.1 注记；原文误写 `createNativeVideoEncoder()`）。

---

## 6. JNI 接口契约（冻结）

### 6.1 命名与注册

- 库名：**`webrtcdemo_native`**（`libwebrtcdemo_native.so`），Kotlin `NativeLoader` 内 `System.loadLibrary("webrtcdemo_native")`。
- 注册方式：`JNI_OnLoad` + `RegisterNatives`（ADR-006），返回 `JNI_VERSION_1_6`；缓存 `JavaVM*` 与 4 个类的 global ref。
- 类名（**逐字冻结，禁止改名，ProGuard 必须保留**）：
  - `com/example/webrtcdemo/nativebridge/NativeLog`
  - `com/example/webrtcdemo/nativebridge/NativeVp9Encoder`
  - `com/example/webrtcdemo/nativebridge/NativeNatDetector`
  - `com/example/webrtcdemo/nativebridge/NativeCallbacks`
- Kotlin 侧形式：**必须 `public object NativeXxx { @JvmStatic external fun ... }`**；类名/方法名/签名仍须与 §6.2–§6.5 逐字一致，并靠 ProGuard `-keep`（§4.1）保护。
  > ⚠️ **禁止用 `internal`（2026-09-13，t8 实测）**：Kotlin 会对 `internal` 成员做 **JVM 名字修饰**（如 `nativeInit$webrtcdemo`），而 `RegisterNatives` / `GetStaticMethodID` 是**按字面名查找** → **静默注册失败**（注册不报错，运行时才暴露）。t8 已把 4 个 bridge 类改为 `public object` —— 登记为**受控偏离 §11.4 D-4**，**不是违规**。
- `FindClass` 或 `RegisterNatives` 失败必须 `__android_log_print(ANDROID_LOG_ERROR, "WebRtcDemo", ...)` 并返回 `JNI_ERR`，**不得静默降级**。
- 冻结的 JNI 方法总数 = 4 + 9 + 2 = **15**；C++→Kotlin 回调 = **2**。任何新增/改名都要走 §0 的变更流程。

### 6.2 表 A-1：`NativeLog`（4）

| 方法 | JNI 签名 | 契约 |
|---|---|---|
| `nativeInit` | `(Ljava/lang/String;Ljava/lang/String;IJI)V` | `(logDir, fileNameBase, level, maxBytesPerFile, maxFiles)`；`logDir` 必须存在且可写（Kotlin 保证）；**幂等**（重复调用先关旧文件）；失败只写 logcat 不抛异常；必须在其它 native 调用之前调用一次 |
| `nativeSetLevel` | `(I)V` | `0..5`，线程安全 |
| `nativeFlush` | `()V` | `fflush`+`fsync` 当前日志文件；导出前必调 |
| `nativeShutdown` | `()V` | 关闭文件；之后 native 日志只落 logcat |

### 6.3 表 A-2：`NativeVp9Encoder`（9）

| 方法 | JNI 签名 | 契约 |
|---|---|---|
| `nativeCreate` | `()J` | 返回 `Vp9Encoder*` 句柄（>0）；失败返回 `0` |
| `nativeInit` | `(JIIIIIII)I` | `(handle, width, height, startBitrateBps, maxBitrateBps, maxFramerate, numSpatialLayers, numTemporalLayers)`；宽高向上对齐到偶数；`numSpatialLayers` 只接受 1（§5.6，其它值返回 `ERR_PARAMETER`） |
| `nativeEncode` | `(JLjava/nio/ByteBuffer;Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;IIIIIJIZ)I` | `(handle, y, u, v, width, height, strideY, strideU, strideV, captureTimeNs, rotationDegrees, requestKeyFrame)`；3 个 ByteBuffer 必须为 **direct**（`GetDirectBufferAddress != null`），否则 `ERR_PARAMETER`；C++ 只在本次调用内读取平面并**同步拷贝** |
| `nativeCopyEncodedFrame` | `(JLjava/nio/ByteBuffer;[I)I` | `(handle, dst, outMeta)`；把最近一帧编码结果拷入 `dst`；`outMeta` 长度 ≥6：`[0]=width,[1]=height,[2]=isKeyFrame(0/1),[3]=spatialIndex,[4]=temporalIndex,[5]=qp(-1=未知)`；返回拷贝字节数（>0）、无待取帧 `0`、出错 `-1` |
| `nativeGetEncodedFrameSize` | `(J)I` | 待取帧字节数（无则 `0`），用于按需扩容 `dst` |
| `nativeSetRates` | `(J[IIIII)I` | `(handle, layerBitratesBps, numSpatialLayers, numTemporalLayers, totalBitrateBps, framerateFps)`；数组长度必须 = `S*T`，索引 `s*T+t`；长度不符 → `ERR_PARAMETER` |
| `nativeRequestKeyFrame` | `(J)I` | 下一帧强制关键帧 |
| `nativeRelease` | `(J)I` | 释放句柄；**幂等**（重复调用仍返回 `OK`）；释放后 Kotlin 必须把 `handle` 置 0 |
| `nativeGetImplName` | `()Ljava/lang/String;` | 固定返回 `"SelfVp9Libvpx"` |

### 6.4 表 A-3：`NativeNatDetector`（2）

| 方法 | JNI 签名 | 契约 |
|---|---|---|
| `nativeDetect` | `(Ljava/lang/String;IJ)V` | `(stunHost, stunPort, timeoutMs)`；**立即返回**（探测在自有线程进行）；结果经表 B-1 回调；重复调用先取消失败的上一次 |
| `nativeCancel` | `()V` | 取消并 join 探测线程（幂等） |

### 6.5 表 B-1：`NativeCallbacks`（C++ → Kotlin，2）

> 这类方法是 **C++ 调 Java**；`JNI_OnLoad` 里 `FindClass` + `GetStaticMethodID` 缓存。

| 方法 | JNI 签名 | 契约 |
|---|---|---|
| `onNatTypeDetected` | `(Ljava/lang/String;Ljava/lang/String;)V` | `(natType, detail)`；由 native 探测线程调用（调用前 `AttachCurrentThread`，退出前 `DetachCurrentThread`）；Kotlin 实现必须**立即切主线程**再更新 UI；`detail` ≤512 字节 ASCII 证据串（如 `mapped=1.2.3.4:54321;testI=ok;testII=resp;testIII=timeout`） |
| `onLogEvent` | `(ILjava/lang/String;Ljava/lang/String;)V` | `(level, tag, message)`；**仅低频事件**（`nat_start` / `nat_done` / `encoder_libvpx_init` / `encoder_fallback`），禁止逐帧调用；Kotlin 转成 `log/Log.kt` 的一行 |

**已取消的旧回调**（`doc/05` §4、`doc/11` §3.4、`doc/10` §5.2 中通过 JNI 上来的部分）：`onIceCandidate`、`onIceConnectionChange`、`onIceCandidatePairChanged`、`onConnectionChange`、`onStatsReport`、`onBitrateChanged`、`onNatTypeDetected`(C++ 外的旧签名)、`onRemoteVideoFrameReady`。
→ 这些事件改由 Kotlin 侧 `org.webrtc.PeerConnection.Observer` / `RTCStatsCollector` / `SurfaceViewRenderer` 直接获得（§7.4），**不再跨 JNI**。

### 6.6 状态码与枚举（冻结）

- **状态码**（= `org.webrtc.VideoCodecStatus.getNumber()`）：
  `0 OK`、`1 NO_OUTPUT`、`-1 ERROR`、`-2 LEVEL_EXCEEDED`、`-3 MEMORY`、`-4 ERR_PARAMETER`、`-5 ERR_SIZE`、`-6 TIMEOUT`、`-7 UNINITIALIZED`、`-13 FALLBACK_SOFTWARE`。
- **日志级别**（native 与 Kotlin 数值必须一致）：`0 VERBOSE`、`1 DEBUG`、`2 INFO`、`3 WARN`、`4 ERROR`、`5 OFF`。
- **NAT 类型字符串**（必须与 `doc/09` §3.7 枚举完全一致）：`Open` / `FullCone` / `RestrictedCone` / `PortRestrictedCone` / `Symmetric` / `Unknown`。
- **rotation**：`0|90|180|270`（度）；`VideoFrame.getRotation()` 直接映射，非法值按 0 处理并记 WARN。
- **编码器实现名**：`"SelfVp9Libvpx"`。

### 6.7 句柄与内存所有权（冻结）

| 对象 | 分配方 | 释放方 | 规则 |
|---|---|---|---|
| 编码器句柄（`jlong`） | `nativeCreate`（C++ `new`） | `nativeRelease`（C++ `delete`） | Kotlin 只能调用一次；失效后调用返回 `UNINITIALIZED`，**不得崩溃** |
| 编码结果缓冲 `ByteBuffer` | Kotlin（`ByteBuffer.allocateDirect`，初值 `max(512 KiB, maxBitrateBps/8*2)`，上限 8 MiB） | Kotlin（GC） | C++ 只写不持有；禁止 native 侧 `NewDirectByteBuffer` 长期持有 |
| 输入帧平面（3 个 direct `ByteBuffer`） | SDK / FrameNormalizer | SDK | C++ 仅在 `nativeEncode` 期间读取，必须同步拷贝 |
| `NativeCallbacks` 类引用 / methodID | `JNI_OnLoad` | 进程生命周期 | 进程内不释放 |

其它硬性要求：所有 native 方法必须做空指针与范围校验（`-fno-exceptions` 下不得依赖异常）；`nativeInit`/`nativeEncode` 的宽高必须校验 `>0 && <=4096`。

---

## 7. 采集 / 渲染 / PeerConnection 契约（D1，全部用 `org.webrtc`）

### 7.1 初始化顺序（`webrtc/WebRtcEngine.kt`）

```kotlin
// 1) 日志类库最早初始化（§9.4）
NativeLog.nativeInit(File(context.filesDir, "logs").absolutePath, "native", 2, 2*1024*1024, 3)   // 见 §9.4
// 2) PeerConnectionFactory 全局初始化（必须在任何 factory/PC 之前，且只调一次）
PeerConnectionFactory.initialize(
  PeerConnectionFactory.InitializationOptions.builder(context)
    .setEnableInternalTracer(false)
    .setInjectableLogger(LibwebrtcLoggable,                       // §9.7
        if (BuildConfig.DEBUG) Logging.Severity.LS_VERBOSE else Logging.Severity.LS_INFO)
    .createInitializationOptions())

val eglBase = EglBase.create()                                    // 进程内单例
val factory = PeerConnectionFactory.builder()
  .setOptions(PeerConnectionFactory.Options())
  .setVideoEncoderFactory(Vp9VideoEncoderFactory())               // §5.4，替换掉默认编码器
  .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))   // MediaCodec 硬解
  .createPeerConnectionFactory()
```
- `EglBase` / `PeerConnectionFactory` / `SurfaceTextureHelper` 由 `WebRtcEngine` 单例持有，进程内只创建一次；`hangup` **不销毁**。
- **禁止**把 `DefaultVideoEncoderFactory` 作为主编码器（会走硬件编码、失去学习点）。诊断开关 `USE_DEFAULT_ENCODER=true` 时允许**临时**替换，用于 `doc/03` 阶段 3「先用默认编码器验证链路」的对照实验，且必须在报告登记。

### 7.2 采集链（冻结，Camera2 + I420 归一化层）

```kotlin
val enumerator = Camera2Enumerator(context)
val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.first()
val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
val capturer = enumerator.createCapturer(deviceName, surfaceTextureHelper)   // CameraVideoCapturer
val videoSource = factory.createVideoSource(false /* isScreencast */)
val normalizer = FrameNormalizer(videoSource.capturerObserver)               // 见下
capturer.initialize(surfaceTextureHelper, context, normalizer)
capturer.startCapture(640, 480, 30)
val videoTrack = factory.createVideoTrack("video0", videoSource)
```
**`FrameNormalizer : CapturerObserver`（必做，冻结）**——保证进入编码器的帧恒为 I420：
**所有权约定（硬性）**：入参 `frame` 的所有权属**上游采集器**（`CameraSession` 在 `onFrameCaptured` 返回后会自行 `frame.release()`），因此本层**不得**释放入参；本层自己创建的 `out` 由本层释放。
1. `onFrameCaptured(frame)`：若 `frame.buffer is VideoFrame.I420Buffer` → **原帧直接透传**给下游（零拷贝，不做任何 release）；
2. 否则 `val i420 = frame.buffer.toI420()`（**在采集线程执行**：Camera2 走 `SurfaceTextureHelper`，采集线程持有 GL 上下文，这是本层存在的唯一原因；`toI420()` 返回持有新引用的 buffer）；构造 `VideoFrame(i420, 0 /*rotation 已烘进 I420*/, frame.timestampNs)`（所有权转移给 `out`）；
3. 调下游 `observer.onFrameCaptured(out)`，**随后 `out.release()`**；入参 `frame` 仍由上游释放；
4. 打点：`capture frame buf=texture|i420 convert_us=<n> w=<n> h=<n> rot=<n>`。
> 若设备实测出现 double-free 或帧被复用（即上游并不释放入参），以实测为准：改为由本层释放入参，并在 t8 报告中登记该偏差。
**禁止**：CameraX（`doc/10` §6.1 作废）、`nativeSetVideoSurface` / `setRemoteVideoSink` JNI（`doc/10` §6.1/§7 作废）。
**切换前后摄**：`capturer.switchCamera(null)`；`toggleCamera` 映射到此。
**参数**：初值 `640x480@30`，可在诊断页调整（`R4`：设备弱时可降 `480x360@24`）。

### 7.3 渲染（冻结）

- `SurfaceViewRenderer`（`org.webrtc`）：**`init(eglBase.eglBaseContext, rendererEvents)`** —— **必须传入 `RendererCommon.RendererEvents` 实现**（**不得传 `null`**，见下）；`setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)`；`setEnableHardwareScaler(true)`；本地预览 `setMirror(true)`（远端 `false`）。
- 远端：`remoteVideoTrack.addSink(remoteRenderer)`；本地：`videoTrack.addSink(localRenderer)`（同一个 `videoSource`）。
- Compose 内用 `AndroidView(factory = { SurfaceViewRenderer(ctx).apply { ... } })`。
- 生命周期：`onDestroy/onPause` 必须先 `track.removeSink(renderer)` 再 `renderer.release()`。
- 首帧事件：**实现 `RendererCommon.RendererEvents.onFirstFrameRendered()`** → 置 `CallUiState.isRemoteVideoReady = true`（取代旧 `onRemoteVideoFrameReady` JNI 回调）。
  > **⚠️ 实测修正（2026-09-13，一手源码校正）**：本节原写 `init(eglBase.eglBaseContext, null)`，并把 `SurfaceViewRenderer.onFirstFrameRendered` 当事件源；但 `SurfaceViewRenderer.java:267-269` 的实现是「**仅当 `rendererEvents != null` 时才回调 `rendererEvents.onFirstFrameRendered()`**」，`RendererCommon.java:22-26` 定义了 `RendererEvents.onFirstFrameRendered()`。**故传 `null` 时首帧事件永不触发 → `isRemoteVideoReady` 永为 `false`**（不是编译错，而是**静默不工作**）。正确做法：`init(ctx, object : RendererCommon.RendererEvents { override fun onFirstFrameRendered() { /* isRemoteVideoReady = true */ } override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) { /* 可选 */ } })`（**`RendererEvents` 只有这两个方法**，见 `RendererCommon.java:22-33`）。

### 7.4 事件与统计来源（取代旧 JNI 回调面）

| UI 数据 | 来源（org.webrtc 公共 API） | 频率 |
|---|---|---|
| ICE candidate | `PeerConnection.Observer.onIceCandidate(IceCandidate candidate)` —— **单参数**（`sdpMid` / `sdpMLineIndex` / `sdp` 是 `IceCandidate` 的 public 字段，从中取） | 事件 |
| ICE 连接状态 | `onIceConnectionChange(IceConnectionState)` | 事件 |
| ICE gathering | `onIceGatheringChange(IceGatheringState)` | 事件 |
| 传输模式 P2P/RELAY | stats 中 `type=candidate-pair && selected==true` 的 `localCandidateType`/`remoteCandidateType`；含 `relay` → `RELAY`，否则 `P2P` | 2 s |
| 上行/下行速率 | `outbound-rtp.bytesSent` / `inbound-rtp.bytesReceived` 差分 ÷ Δt | 2 s |
| 可用带宽 | `candidate-pair.availableOutgoingBitrate`（缺失回退 `availableReceiveBitrate`） | 2 s |
| 编码实现名 | `outbound-rtp.encoderImplementation`（验收须 == `SelfVp9Libvpx`） | 2 s |
| 编码目标码率 | `Vp9VideoEncoder.setRateAllocation()` 的 `total`（本地直通，不依赖 stats） | 每次 SetRates |
| 本端 NAT | `NativeCallbacks.onNatTypeDetected`（§6.5） | 一次性 |
| 对端 NAT | 信令 `natType`（`doc/09`） | 一次性 |

- stats 收集：`peerConnection.getStats(object : RTCStatsCollectorCallback { override fun onStatsDelivered(report: RTCStatsReport) { ... } })`（每 2 s 一次；通话结束停止）。
- ICE 事件列表在 App 侧累积，**通话结束才清空**（`doc/02` §5、`doc/03` 阶段 5）。
- 诊断页可选：`iceTransportsType = PeerConnection.IceTransportsType.RELAY` 强制中继，用于复现 RELAY 路径（默认 `ALL`）。（**实测修正（2026-09-13）**：原文写 `iceTransportPolicy = RELAY`，该字段名与枚举**均不存在**；真实字段为 `RTCConfiguration.iceTransportsType`，枚举为 `PeerConnection.IceTransportsType{ALL, RELAY, NOHOST, NONE}`——与 §7.5 同一缺陷的另一处出现。）

### 7.5 RTCConfiguration 与 SDP 策略（冻结）

```kotlin
// ⚠️ 实测修正（2026-09-13，t10 首次真实编译校正）：RTCConfiguration 与 IceServer 均为 PeerConnection 的嵌套类型
val config = PeerConnection.RTCConfiguration(listOf(
    PeerConnection.IceServer.builder(created.stunUrl).createIceServer(),
    PeerConnection.IceServer.builder(created.turnUrl)
        .setUsername(created.turnUsername)
        .setPassword(created.turnCredential)
        .createIceServer()
)).apply {
    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    iceTransportsType = if (forceRelay) PeerConnection.IceTransportsType.RELAY
                        else PeerConnection.IceTransportsType.ALL
}
```

> **⚠️ 实测修正（2026-09-13，t10 首次真实编译校正；源码 `third_party/libwebrtc-src/sdk/android/api/org/webrtc/PeerConnection.java`）**：本节示例原用**不存在的类型**，照抄会**编译失败**（android-dev 已实测踩中，共 26 个 `e:` 错误）。修正点：
> - `RTCConfiguration` 与 `IceServer` **都是 `PeerConnection` 的嵌套类型**（`PeerConnection.RTCConfiguration` 第 471 行；`PeerConnection.IceServer` 第 166 行）——**不存在**顶层 `org.webrtc.RTCConfiguration` / `org.webrtc.IceServer`；
> - 构造入口是 **`PeerConnection.IceServer.builder(String uri)`**（第 273 行）→ `.setUsername()` / `.setPassword()`（第 ~297/302 行）→ **`.createIceServer()`**（第 ~327 行）；
> - **没有 `IceTransportPolicy` 类型**：真实字段是 **`RTCConfiguration.iceTransportsType`**，枚举为 **`PeerConnection.IceTransportsType { NONE, RELAY, NOHOST, ALL }`**（第 374 行）；
> - 其余字段名已逐项核对无误：`sdpSemantics`（`SdpSemantics { PLAN_B, UNIFIED_PLAN }`，第 454 行）、`bundlePolicy`、`rtcpMuxPolicy`、`continualGatheringPolicy`。
> - **只改类型/名称，行为与冻结策略不变**（ICE server 仍只来自 `created`/`joined`；`forceRelay` 语义不变）。
- ICE server **只能**来自 `created`/`joined` 下发（禁止硬编码 `demo:demopass`）。URL 形态、凭据与 realm 见 §7.7。
- track id 冻结：视频 `"video0"`、音频 `"audio0"`；stream id `"stream0"`。
- **禁止手改 SDP 文本**（禁止 `sdp.replace(...)` hack）。VP9 由 `Vp9VideoEncoderFactory.getSupportedCodecs()` 提供，并用 `RtpTransceiver.setCodecPreferences(...)` 把 VP9 置首、移除其它视频编码，从而强制 VP9。
- 音频：使用 SDK 默认 ADM（不额外配置 `JavaAudioDeviceModule`；`U5` 若回声明显再显式配置）。

### 7.6 AndroidManifest 冻结项

```xml
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-feature android:name="android.hardware.camera" android:required="true"/>
<application android:usesCleartextTraffic="true"      <!-- §8.1 必需：信令走 ws:// -->
             android:allowBackup="false"
             android:label="@string/app_name" ...>
  <provider .../>                                       <!-- FileProvider，见 §9.5 -->
  <activity android:name=".MainActivity" android:exported="true" android:screenOrientation="portrait"/>
</application>
```
- 运行时权限：进入 HomeScreen 时请求 `CAMERA` + `RECORD_AUDIO`；被拒则禁用「创建/加入」按钮并提示。
- `.MainActivity` 必须 `android:exported="true"`（targetSdk 31+ 强制）。

### 7.7 coturn 配置与客户端 ICE server 规则（t6 实测冻结）

**服务端配置（宿主机 `/etc/turnserver.conf`；可复现副本 `deploy/turnserver.conf`）**：

```ini
listening-port=3478
listening-ip=172.21.0.219                  # 内网 IP（EIP 不在网卡上，不能 bind）
external-ip=47.238.144.66/172.21.0.219     # 公网/内网 映射（负责对外通告公网 relay 地址）
relay-ip=172.21.0.219                      # ⚠️ 必须内网 IP；写公网 IP → errno 99 / Allocate 508
min-port=49152
max-port=49200
realm=webrtc-demo
server-name=webrtc-demo
use-fingerprint
lt-cred-mech
user=demo:demopass
total-quota=100
cert=/etc/turnserver/cert.pem
pkey=/etc/turnserver/pkey.pem
```

**硬性规则（t6 实测证伪 `doc/01` §5 的 `relay-ip=<公网IP>`；`doc/01` 已就地加「实测修正」注记）**：

| 规则 | 结论 | 实测证据（`reports/06-coturn.md`） |
|---|---|---|
| `relay-ip` | **必须内网 IP**（`172.21.0.219`） | 写 EIP 时 `Trying to bind fd 61 to <47.238.144.66:49189>: errno=99`（EADDRNOTAVAIL）+ 客户端 `Allocate` → **508 Cannot create socket**；改内网 IP 后 4 次 Allocate 全成功 |
| `external-ip` | **必须 `公网/内网`**，公网 relay 地址由它通告 | STUN 返回 `47.238.144.66:35866`；relay 落在 `47.238.144.66:49162–49195` |
| `listening-ip` | 内网 IP | `ss -lunp` 显示 bind 在 `172.21.0.219:3478/5349` |
| `turns:`（5349） | **本 demo 不使用** | 仅有自签证书（CN=webrtc-demo），公网客户端需 CA 证书 |
| 中继端口段 | `49152–49200` 生效 | 12/12、8/8 包经中继往返，丢包 0% |

**客户端 ICE server 形态（冻结，t9/t12 下发、t8 消费，必须逐字一致）**：

```json
{"urls":["stun:47.238.144.66:3478","turn:47.238.144.66:3478?transport=udp"],
 "username":"demo","credential":"demopass"}
```
- URL 冻结：`stun:47.238.144.66:3478`、`turn:47.238.144.66:3478?transport=udp`（可选 `turn:47.238.144.66:3478?transport=tcp`）。
- 凭据：用户名 `demo` / 密码 `demopass`（长期凭据 `lt-cred-mech`）；`realm` = `server-name` = `webrtc-demo`。
- 下发通道：服务端在 `created`/`joined` 的 `stunUrl`/`turnUrl`/`turnUsername`/`turnCredential` 字段（`doc/09` 字段名，见 §8.2）；客户端**不得硬编码**这些值（§7.5）。
- 仅内网联调备用形态：`stun:172.21.0.219:3478`、`turn:172.21.0.219:3478?transport=udp`。
- **公网可达性：已解除（resolved，2026-09-13 约 16:0x）**。历史（t6，15:33）：阿里云安全组入方向未放行，公网不可用——`eth0` 抓包对 3478/8443/49160 只有出包无回包（`reports/06-coturn.md` §7.3）。**用户随后在控制台放行**（宿主机 `ufw` inactive、`iptables`/`nft` 未变，t6→t12 期间仅控制台侧变化）；放行后 **t12 与 captain 各自实测公网可达**：`curl http://47.238.144.66:8443/healthz` → **200**（`/ws` 无 Upgrade 头 → 400，正常）、`turnutils_stunclient -p 3478 47.238.144.66` → **`UDP reflexive addr: 47.238.144.66:53170`**。放行后**无需重启** coturn。**非阻塞残留**：TCP 3478/5349（可选端口）仍未放行——本 demo 用 `turn:…?transport=udp`，不影响。风险登记见 §11.1 **R9（已解除）**。
- verifier 复核命令（`reports/06-coturn.md` §8）：`turnutils_stunclient -p 3478 172.21.0.219`；`turnutils_uclient -v -y -u demo -w demopass -p 3478 -n 3 -m 2 172.21.0.219`；错误密码负例必须失败。验收项见 §12.9。

---

## 8. 信令契约（D5：`doc/09` 权威，本文只引用与登记）

### 8.1 端点（**冻结**）

| 项 | 冻结值 | 备注 |
|---|---|---|
| 路径 | **`/ws`** | `doc/05` §7 与 `ADR-006` 的 `/signal` **作废**（captain 已确认；t9 已按 `/ws` 实现） |
| 客户端 URL | `BuildConfig.SIGNALING_URL` = **`ws://47.238.144.66:8443/ws`** | 无域名、无法为 IP 签发可信证书 → 不用 wss；`usesCleartextTraffic=true` |
| 运行时覆盖 | 诊断页可改并持久化到 SharedPreferences（便于 t12/verifier 联调） | 覆盖值优先 |
| TLS（可选增强） | Caddy 443→8443 反代 | `U3`，默认不装 |
| 传输 | WebSocket 文本帧 UTF-8 JSON；单条 ≤64 KB（`doc/09` §1） | |
| `ping`/`pong` | 间隔 15 s / 超时 5 s / 重连等待 3 s / 最多 3 次（`doc/09` §6） | `pong.timestamp` = **服务端**当前 Unix 毫秒 |
| TURN 凭据 | 由 `created`/`joined` 下发（全局 `demo:demopass`） | 客户端不得硬编码 |

### 8.2 共享字段表（**字段名冻结，取自 `doc/09`**）

| 消息 | 方向 | 字段（拼写不可改） |
|---|---|---|
| `create` | C→S | `type` |
| `created` | S→C | `type, roomId, stunUrl, turnUrl, turnUsername, turnCredential` |
| `join` | C→S | `type, roomId` |
| `joined` | S→C | `type, roomId, stunUrl, turnUrl, turnUsername, turnCredential, peerId` |
| `peerJoined` / `peerLeft` | S→C | `type, peerId` |
| `offer` / `answer` | C→S→C | `type, sdp` |
| `ice` | C→S→C | `type, candidate, sdpMid, sdpMLineIndex`（`sdpMid` 与 `sdpMLineIndex` 至少一个有效） |
| `natType` | C→S→C | `type, natType`（枚举见 §6.6） |
| `leave` | C→S | `type` |
| `error` | S→C | `type, code, message`（码表见 `doc/09` §3.10） |
| `ping` / `pong` | C→S / S→C | `type, timestamp` |

- **作废字段名**（`doc/05` §7）：`stun` / `turn` / `turnUser` / `turnPass` → 必须用 `stunUrl` / `turnUrl` / `turnUsername` / `turnCredential`。
- `roomId`：6 字符，字符集 **`ABCDEFGHJKMNPQRSTUVWXYZ23456789`**（排除易混淆 `I L O 0 1`；采用 `doc/12` 的字符集作为权威，与 `doc/09` §7 规则兼容）。客户端必须 `uppercase()` + 过滤非法字符，校验正则 `^[A-HJ-KM-NP-Z2-9]{6}$`。
- `sdpMLineIndex`：音频/视频的 m-line 顺序由 offer 决定，**不得硬编码**（`doc/09` §3.6 的「0=音频 1=视频」仅示例）。
- 转发语义：服务端对 `offer/answer/ice/natType` **原样透传**（不解析、不改字段、不附加 `roomId`）。
- **本端 `peerId` 的获取（2026-09-13 裁定，**不修改 `doc/09`**）**：`created` **不得**新增 `peerId` 字段（D5 冻结 + `doc/09` §10 schema `additionalProperties:false`）。本端 ID **可确定性推导**，客户端按此实现即可：
  - **joiner**：直接用 `joined.peerId`；
  - **host（发起方）**：收到 `peerJoined.peerId = X` 后反推 —— 本端 = `X == "peer-001" ? "peer-002" : "peer-001"`（房间恒 2 人，doc/09 §7）；在收到 `peerJoined` 之前 host 无需知道自己 ID，UI 显示占位（如 `—`）即可。
  - **禁止**用「host 恒为 peer-001」这类靠槽位顺序的推断（host 离开后新加入者可能占用 slot 0）；只用上面的"对端 ID 取反"规则。
  - 该限制本身已登记为 **§11.4 D-3**（已知限制，low，**不得判失败**）；未来若 UI 确需显示本端 ID，走 §0 变更流程出 v1.x，不在本轮扩字段。
- **Offerer 不变量（2026-09-13 登记为 §11.4 D-6；同日按 t9 两个 E2E 用例修正理由）**：本项目约定「**同一时刻只有一方发 Offer，且由 host 承担**」（实现按 **role=host** 固定，见 `maybeCreateOffer()` 的 `role != ROLE_HOST` 早退）。
  - **与 `doc/09` §3.3 字面的差异**：`doc/09` 写「**收到 `peerJoined` 的一方**是发起方（应发 Offer）」。在**正常流程**与**旧会话静默掉线后重连**两条可达路径下，两者**恰好选中同一方（host）、行为等价**；差异只是"指定依据"不同（角色 vs `peerJoined` 的投递对象）。
  - **⚠️ 已修正的错误论断（保留痕迹）**：v1.0-l 曾称"双方同时重连时无人收到 `peerJoined` → 按字面会互等死锁"。**经 t9 两个端到端用例证伪**：① `TestE2E_ReconnectAfterBothDisconnectedIsRoomNotFound`——双方都断开 → 房间变空 → **立即销毁**（`doc/09` §7）→ 用原 `roomId` 重连得 **`ROOM_NOT_FOUND`**；② `TestE2E_ReconnectStaleSessionCausesRoomFull`——旧会话**静默掉线**（TCP 未关、只是不再收发）时槽位要等读超时（默认 **45 s**）才回收，期间 `join` 得 **`ROOM_FULL`**。要让两个新连接**都**收到 `joined`，房间必须先变空，而**一空即销毁** → 「双 `joined`、无人收到 `peerJoined`」的组合**不可达**。**故 §3.3 字面不会死锁、无需兜底条款**；保留本不变量是为**确定性**（不依赖"谁收到 `peerJoined`"），**不是**为避免死锁。
  - **真正的风险 = 客户端混用两套规则**：服务端只把 `peerJoined` 投给**已在房的一方**；若一端按角色固定、另一端按字面判定，就可能**双方都发**或**都不发**。因此**不要只删掉本不变量**；若要改回字面口径，必须**客户端两侧同时改**并在报告中登记。

### 8.3 Go 侧必须按 `doc/09`（而非 `doc/12`）实现的行为清单（t9 修正任务）

1. `leave` 必须完整执行三步：向同房间对端发 `peerLeft` → 关闭该 WebSocket → 销毁房间（`doc/12` §9 `handleLeave` 只做了第一步）。
2. 未 `join` 就发 `offer/answer/ice/natType/leave` → 返回 `NOT_IN_ROOM`（`doc/12` 只对转发做了 `peer.Room == nil` 判断，`leave` 未判断）。
3. 房间管理：容量 2；过期 = 创建后 30 分钟**无人 join**；2 人全离开立即销毁；`roomId` 冲突重试生成。
4. **已登记（t9 选择，2026-09-13）**：**已在房间时再 `create`**、以及**重复 `join`** → 返回 `INVALID_MESSAGE`（`message` 含 `already in room <roomId>`）+ Warn 日志。**注意**：**未在任何房间时 `create` 是合法主路径**（不得拒绝）；若 t9 实现与该口径不符，以其自测通过的行为为准并在报告中更正。
5. `error.message` 文案以 `doc/09` §3.10 表格为准。
6. **`doc/12` 的已知缺陷必须在 t9 修复并登记**：`errors` 包未 import；`Manager.JoinRoom` 未真正 `AddPeer`（容量校验实际在 `AddPeer`）；`handleCreate` 直接写 `room.Peers[0]` 未加锁（数据竞争）；`RemovePeer`/`GetOtherPeer` 的加锁层级；`main.go` 的 `-user demo:demopass` 未解析。
7. 日志按 §9.3（logrus，终端 + 文件），级别由 `-log-level` 控制。

### 8.4 错误码语义与客户端解析义务（2026-09-13 授权追加，**仅消息级语义**）

> 本轮只登记**消息级协议陈述**；**退避时长、重试次数、宽限期**等属**客户端策略**，一律留在 `reports/02-interface-contract.md`（§27.4、§30），**不进契约**。

**8.4.1 错误码语义（`type=error`）**

| `code` | 语义 | 客户端义务 |
|---|---|---|
| `ROOM_FULL` | **暂时性（transient）**：房间当前已满，槽位可能随对端断线被回收而释放 | **不应视为终态**（是否重试、如何退避属客户端策略） |
| `ROOM_NOT_FOUND` | **终态**：房间不存在（含双方都断开后**已销毁**的情形） | 应**停止重试** |
| `ROOM_EXPIRED` | **终态**：房间已过期（创建后 30 分钟无人 join） | 应**停止重试** |

**8.4.2 客户端解析义务（硬要求，t8）**

读流中可能**混入 `pong`**（心跳与业务消息**同流**）。因此客户端：

- **必须按 `type` 分发**每一条收到的消息；
- **不得**假设「发 `join` 后下一条必是 `joined`」，**也不得**用"下一条消息"做状态机同步。

依据：t9 实测（心跳与业务消息同流）+ t8 的实现与守卫。

### 8.5 重连恢复的已知限制与后续增强（2026-09-13 登记，**不改 `doc/09` 正文**）

**现行口径 = 口径 B（严格，维持 `doc/09` §4 字面）**：`IN_CALL` 收到 `peerLeft` → 客户端**立即转 `DISCONNECTED` 并挂断**；survivor 一挂断即 `leave` → 房间**立即销毁**（§7）→ 掉线方重连得 **`ROOM_NOT_FOUND`（终态）**。

- **结论（登记为已知限制）**：**"静默掉线后可完整恢复"本轮不支持**；双方回到首页并**明确提示**"通话已结束，可重新创建"，**不产生半死不活的状态**。
- **治理理由**（captain 裁定）：完整恢复**还需要重协商（ICE restart）**，而本轮**无真机可验证**；只加重连宽限期而不做重协商，会产出**"信令已重连、媒体是死的"僵尸通话**，比干净挂断更糟。故本轮选择**一致、诚实的失败路径**。
- **后续增强（口径 A，本轮不实施）**：`peerLeft` 后设**有界宽限期**再转 `DISCONNECTED`（期间**不发 `leave`**、房间不销毁），窗口内掉线方以原 `roomId` 重连成功即恢复；**前提是同时实现 ICE restart 重协商**（否则即上述僵尸通话）。若实施，按 **D-6 的角色固定 offerer** 由 host 重发 Offer，重新协商确定性成立。
- **两种口径下服务端都无需改动**——房间的存活时间**完全由 survivor 的挂断策略决定**。

**go-dev 时序实测（`PongWait` 调小以便观测，3 轮稳定；用例 `TestE2E_ReconnectTimingAfterStaleReap`）**：

| 量 | 实测 | 生产换算（`PongWait=45s`） |
|---|---|---|
| 静默掉线 → 服务端回收死会话 + survivor 收 `peerLeft` | 801 ms | ≈ 45 s |
| 房间仍在（survivor 未挂断）→ 重连方 `join` 成功 | 1 ms（服务端 sub-ms） | ≈ RTT + 1 ms |
| survivor 收 `peerLeft` **立即** `leave` → 房间销毁（此后 `join` = `ROOM_NOT_FOUND`） | 0–3 ms | ≈ RTT + 3 ms |

→ 关键推论：掉线方的成功重连**必然发生在 reap 之后**（此前是 `ROOM_FULL`），即**几乎与 survivor 收到 `peerLeft` 同时**；因此"可恢复窗口"**完全由 survivor 是否延迟挂断决定**。证据另见 go-dev 的 `signaling/README.md` §3.1 与 `reports/09-go-signaling.md` §11。

---

## 9. 日志与可观测性契约（D6）

### 9.1 统一行格式（三层必须一致）

```
<ts> <LEVEL> <layer> <tag> [<pid>/<tid>] <message>[ key=value ...]
```

| 段 | 规则 |
|---|---|
| `ts` | `YYYY-MM-DDThh:mm:ss.SSSZ`（**UTC**，毫秒，字面 `Z`） |
| `LEVEL` | `VERBOSE\|DEBUG\|INFO\|WARN\|ERROR`，**左对齐定宽 7** |
| `layer` | `kotlin\|native\|go\|webrtc`，左对齐定宽 6 |
| `tag` | ≤24 字符 `snake_case` 模块名：`main`/`ui`/`signaling`/`pc`/`ice`/`stats`/`encoder`/`nat`/`room`/`export`；**左对齐定宽 9**（超出 9 字符不截断，但会破坏列对齐，允许） |
| `[pid/tid]` | 十进制。Go 无 goroutine id → 允许 `[<pid>/-]`（**登记为格式允许差异**） |
| `key=value` | 行尾追加，`key` 为 `snake_case`，`value` 不得含空格（空格转 `_`），按键字典序 |

> **列宽与笔误修正（2026-09-13）**：字段宽度**一律以本表文字规则为准** —— `ts` 后接 1 空格；`LEVEL` 左对齐定宽 **7**、后接 1 空格；`layer` 左对齐定宽 **6**、后接 1 空格；`tag` 左对齐定宽 **9**、后接 1 空格；随后是 `[<pid>/<tid>]` 与消息正文。
> 下方示例行原先有一处笔误：`go` 行的 `layer` 字段多写了 1 个空格（实测该字段宽 8，而其余 6 行均为 7），**已修正为宽 7**。verifier 请按上述文字规则判定，**不得**按示例字面量判失败。

> **适用范围（2026-09-13 澄清，避免假失败）**：本 k=v 口径**约束全部四层日志行的键名，包括 Go 侧 `logrus` 的 `Fields`**（它正是 `layer=go` 行的 k=v 来源）。
> - Go 字段名必须是 `snake_case`：期望 `stun_url` / `turn_url` / `turn_user` / `log_file`（**不是** `stunUrl` / `turnUrl` / `turnUser` / `logFile`）。
> - 现存 camelCase 字段名（`signaling/main.go` 的启动日志）登记为 **low 级偏差，不判失败**（见 §10 C30、§11.4），由 go-dev 后续对齐。
> - **本口径只约束日志键名，不约束协议 JSON 字段名**：协议字段一律以 `doc/09` 为准，`stunUrl` / `turnUrl` / `turnUsername` / `turnCredential` 是**协议字段，保持 camelCase 不变**（不要为了日志口径去改协议字段）。

示例（同时是 verifier 的 grep 锚点）：
```
2026-09-13T07:29:17.123Z INFO    kotlin signaling [12345/12360] ws_open url=ws://47.238.144.66:8443/ws
2026-09-13T07:29:18.004Z INFO    kotlin signaling [12345/12360] room_created room=A1B2C3 role=host
2026-09-13T07:29:19.881Z INFO    native encoder   [12345/12402] encoder_init w=640 h=480 s=1 t=3 cpu=8
2026-09-13T07:29:19.882Z INFO    native bitrate   [12345/12402] setrates total_bps=750000 fps=30
2026-09-13T07:29:19.883Z INFO    native bitrate   [12345/12402] ts_target_kbps l0=300 l1=525 l2=750 rc_target_kbps=750
2026-09-13T07:29:20.010Z WARN    webrtc ice       [12345/12399] candidate_pair_state state=in_progress nominated=false
2026-09-13T07:29:20.020Z ERROR   go     room      [2301/-] join_rejected code=ROOM_FULL room=A1B2C3
```
固定事件名（新代码必须复用）：`ws_open`、`ws_close`、`room_created`、`room_joined`、`peer_joined`、`offer_sent`、`answer_sent`、`ice_*`、`stats_sample`、`encoder_init`、`setrates`、`ts_target_kbps`、`encoded_frame`、`nat_start`、`nat_done`、`export_zip`。

### 9.2 目录、文件与滚动保留（冻结）

| 层 | 路径 | 说明 |
|---|---|---|
| Kotlin | `<filesDir>/logs/app.log`（+ `app.1.log`、`app.2.log`） | UI/信令/PC/ICE/stats/编码器适配器 |
| C++ | `<filesDir>/logs/native.log`（+ `.1`/`.2`） | 编码器/NAT/JNI/日志模块 |
| C++ | `<filesDir>/logs/encoder_bitrate.csv` | 码率闭环 CSV（§9.4） |
| libwebrtc | `<filesDir>/logs/webrtc.log`（+ `.1`/`.2`） | 经 `Loggable` 捕获（§9.7） |
| 导出 | `<filesDir>/logs/export/webrtcdemo-logs-<ts>.zip` | 保留最近 3 个 |
| Go | **`/var/log/signaling/signaling.log`**（+ `.1`/`.2`） | 服务端全量。**2026-09-13 按部署事实冻结（C31）**：路径由 **`-log <path>`** 命令行参数指定（当前 unit 的 `ExecStart` 含 `-log /var/log/signaling/signaling.log`），目录由程序 `MkdirAll` 自动创建；**滚动（2 MiB × 3）与终端/journald 双写与路径无关**。旧值 `/opt/signaling/logs/signaling.log` **作废**（非偏差）。verifier 可核：`grep -n '\-log' /etc/systemd/system/signaling.service` |

- Android 日志根：`/data/user/0/com.example.webrtcdemo/files/logs/`（Kotlin 与 C++ **共用**；Kotlin 负责 `mkdirs()`）。
- 滚动：**单文件上限 2 MiB，保留 3 个**（当前 + 2 历史），超限删最旧。
  **磁盘占用说明（自洽性澄清）**：滚动日志共 **3 个通道**（`app` / `native` / `webrtc`），每通道 3 文件 × 2 MiB = **6 MiB/通道**，三通道合计**上限 18 MiB**；另加不滚动的 `encoder_bitrate.csv` 与 `export/` 下最多 3 个 zip，**常规运行总占用约 16–25 MiB**。18 MiB 是滚动部分的硬上限；**总占用不是硬上限**（CSV 与导出 zip 不计入），verifier 不得把它当作约束判失败。
- **终端保留（必须）**：Go 同时写 stdout（systemd journal 可见）；Kotlin/C++/webrtc 同时写 logcat（tag：Kotlin `WebRtcDemo`、C++ `WebRtcDemo-Native`、webrtc 由 `Loggable` 加前缀 `webrtc|<级别>|`）。
- 编码 UTF-8、行尾 `\n`；每条日志一次 `write()`（防多线程交错撕裂）。

### 9.3 各层实现要求

- **Kotlin**：`log/Log.kt` 是唯一入口（禁止直接 `android.util.Log`，`Loggable` 转发除外）；`FileLogger` 用独立单线程写盘（`HandlerThread` 或单线程 `Dispatchers.IO`），先入内存队列，满 256 行或每 200 ms flush；`WebRtcDemoApp` 注册 `Thread.setDefaultUncaughtExceptionHandler`，把崩溃栈以 ERROR 写入 `app.log` 后再交给系统。
- **C++**：`log/native_log.cpp` 单例 + `std::mutex`；`NLOG_INFO(tag, fmt, ...)` 宏（不依赖 `std::format`）；文件 + logcat 双写；`nativeInit` 之前只落 logcat。
- **Go（D6 覆盖 `doc/12` §1 的 `log/slog`）**：`github.com/sirupsen/logrus`：
  - 自定义 `TextFormatter`：`DisableColors=true`、`FullTimestamp=true`、`TimestampFormat="2006-01-02T15:04:05.000Z07:00"`、`DisableLevelTruncation=true`；输出形如 §9.1（`layer=go`，`tag` 经 `logrus.Fields{"tag": ...}`）；
  - 输出：`io.MultiWriter(os.Stdout, rollingWriter)`；
  - 滚动：**自实现** `rotatingWriter`（2 MiB × 3），不引第三方（`lumberjack` 等若使用需在报告登记）；
  - 级别：`-log-level trace|debug|info|warn|error`（默认 `info`），systemd unit 显式传参；
  - 高频字段：`room=<id>`、`peer=<peerId>`。

### 9.4 C++ 写文件的 JNI 传参机制（冻结）

**机制**：**一次性把目录 + 文件基名 + 参数传给 C++，由 C++ 自己 `open/write/rename`**；**禁止**每行日志走 JNI 回调（性能与线程安全）。

**时序**：`WebRtcDemoApp.onCreate()`：
```kotlin
NativeLoader.ensureLoaded()
val logDir = File(context.filesDir, "logs").apply { mkdirs() }.absolutePath
NativeLog.nativeInit(logDir, "native", /*level=*/2, /*maxBytesPerFile=*/2*1024*1024, /*maxFiles=*/3)
```
- `nativeInit` 必须在任何 `NativeVp9Encoder` / `NativeNatDetector` 调用之前完成；未初始化时 native 侧仅落 logcat 并在首次调用时打一次 ERROR（不得崩溃）。
- C++ 只打开**已存在**的目录；`ENOENT/EACCES` → 仅 logcat + 一次 ERROR。
- 写文件：`open(O_WRONLY|O_CREAT|O_APPEND, 0644)`，每条一次 `write()`，`fflush` 由每 200 ms 定时或 `nativeFlush()` 触发。
- 滚动：写前判断 `fstat.st_size + line_len > maxBytesPerFile` → 删除 `native.2.log`、`native.1.log → native.2.log`、`native.log → native.1.log`、重开。
- **`encoder_bitrate.csv`**（学习点留痕，verifier 据此画曲线）：
  ```
  ts_ms,total_bps,fps,s,t,layer_0_0,layer_0_1,layer_0_2,ts_kbps_0,ts_kbps_1,ts_kbps_2,rc_target_kbps,encoded_bytes,key,qp
  ```
  在 `SetRates` 与每次成功 `Encode` 后由 C++ **直接写**（不经过 Kotlin）。
- 线程安全：所有写操作在 `native_log` 互斥锁内；编码线程与 NAT 线程可并发调用。

### 9.5 UI 导出机制（冻结）

- **authority**：`com.example.webrtcdemo.fileprovider`（= `${applicationId}.fileprovider`）。
- `app/src/main/res/xml/file_paths.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
  <files-path name="logs"  path="logs/"/>
  <cache-path name="cache" path="."/>
</paths>
```
- `AndroidManifest.xml`：
```xml
<provider android:name="androidx.core.content.FileProvider"
          android:authorities="com.example.webrtcdemo.fileprovider"
          android:exported="false" android:grantUriPermissions="true">
  <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths"/>
</provider>
```
- 导出流程（`diag/LogExporter.kt`）：
  1. `NativeLog.nativeFlush()` + Kotlin `FileLogger.flush()`（webrtc 日志由同一个写盘线程保证已落盘）；
  2. 生成 `logs/export/webrtcdemo-logs-<yyyyMMdd-HHmmssZ>.zip`，内含：`app*.log`、`native*.log`、`encoder_bitrate.csv`、`webrtc*.log`、`device-info.txt`（型号/SDK/ABI/`BuildConfig.SIGNALING_URL`/libwebrtc HEAD SHA）、`session-summary.txt`（roomId、角色、ICE 结果、`encoderImplementation`）；
  3. `FileProvider.getUriForFile(ctx, "com.example.webrtcdemo.fileprovider", zip)` → `Intent(ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(FLAG_GRANT_READ_URI_PERMISSION)` → `Intent.createChooser(...)`；
  4. `export/` 只保留最近 3 个 zip（App 启动时清理更旧的）。
- **打包范围**：仅 `logs/`（含 `export/`）；不含其它任何应用数据。`allowBackup=false`。
- UI 入口：`DiagnosticsScreen` 的「导出日志」按钮（Home / Call 页均可进入）；导出后 Toast 显示路径。
- 可选：`LogViewer`（读 `app.log` 尾部 200 行）用于现场演示。

### 9.6 等级开关（冻结）

| 层 | 默认（release） | 默认（debug） | 运行时可变？ |
|---|---|---|---|
| Kotlin | `INFO` | `DEBUG` | 是（立即） |
| native（C++） | `INFO` | `DEBUG` | 是（`nativeSetLevel`） |
| webrtc（`Loggable`） | `LS_INFO` | `LS_VERBOSE` | **否**（初始化时确定；改级别需重启 App，UI 必须提示「需重启生效」） |
| Go | `info`（`-log-level`） | `info` | 否（服务端 systemd 参数） |

- 持久化：`SharedPreferences("log_cfg")` 的 `level_kotlin` / `level_native` / `level_webrtc`。
- `DiagnosticsScreen` 提供下拉切换；Kotlin 用原子变量，native 调 `NativeLog.nativeSetLevel(level)`。
- 级别数值必须与 §6.6 一致。

### 9.7 libwebrtc 内部日志在 release 下的可用性与 GN 开关（**回写 t5**）

**源码级结论**：
1. `RTC_LOG` **没有被编译掉**（GN 默认 `rtc_disable_logging=false`）；release 下是**运行时抑制**：
   `is_debug=false ⇒ NDEBUG ⇒ kDefaultLoggingSeverity = LS_NONE`，`IsNoop()` 为真时**连 WARNING/ERROR 也被丢弃**。
2. 因此 release 下要拿到 libwebrtc 内部日志，**必须注册日志接收器**。官方公共路径（最实用）：
   `PeerConnectionFactory.InitializationOptions.Builder.setInjectableLogger(Loggable, Severity)`；
   注入后 `Loggable.onLogMessage(msg, severity, tag)` 同时收到 Java 与 native 日志，且注册 sink 会抬高 `g_min_sev`，使 `RTC_LOG` 真正执行。
   （`Logging.injectLoggable` 是包私有，**禁止**直接调用；`Logging.enableLogToDebugOutput` 与注入互斥，会抛异常。）
3. **无需任何新增 GN 参数**。t5 只需保证**不覆盖**以下默认值：
   | GN 参数 | 要求 |
   |---|---|
   | `rtc_disable_logging` | **必须保持默认 `false`**（设为 `true` 会把 `RTC_LOG` 编译掉） |
   | `rtc_dlog_always_on` | 保持默认 `false` —— ⚠️ **已被下方「实测补充（2026-09-13）」覆盖**：captain 授权 t5 本次编译设为 `true`（见 §10 C29） |
   | `is_debug` | `false`（release；日志靠 sink 启用，不靠 debug 构建） |
   | `use_custom_libcxx` | **不显式设置**（Android 默认 true；也正因此否决「自有 .so 链接 libwebrtc.a」，见 §5.1） |

   > **实测补充（2026-09-13，captain 明确授权）— 对上一条「无需任何新增 GN 参数」及上表 `rtc_dlog_always_on` 行的修正**：
   > - **实际编译新增了 `rtc_dlog_always_on=true`**。原因（webrtc-builder 核验源码）：release（`NDEBUG`）下 `RTC_DLOG` 被编译剔除（`RTC_DLOG_IS_ON=0`）；该参数经 GN 定义 **`-DDLOG_ALWAYS_ON`** 由 **`common_inherited_config`** 全局生效，使 `RTC_DLOG` 在 release 下保留。捕获 GCC/NACK/ICE 等内部日志是本项目学习目标，captain 已在给 t5 的指令中**明确授权**此类额外 GN 参数/编译期宏。
   > - **不变量（仍然冻结）**：`is_debug` 仍为 `false`（未改 debug 构建）；默认 `min_severity = LS_INFO`，故 `RTC_LOG(LS_INFO/WARNING/ERROR)` 本就可用；**`rtc_disable_logging` 必须保持 `false`**；**`use_custom_libcxx` 仍不显式设置**。本条补充**不影响**这两条冻结约束。
   > - **性质**：这是**已授权偏差**，不是违规。verifier/实现者**不得**因 t5 的 GN 参数集与 §4.3 表格不完全一致而判失败；登记见 §10 **C29**、§11.1 **R10**、§12 命令约定第 9 条。
4. **实现要求（android-dev）**：
   - `webrtc/LibwebrtcLoggable.kt`：`object LibwebrtcLoggable : org.webrtc.Loggable`，把 `Severity` 映射为 §9.1 LEVEL
     （`LS_VERBOSE→VERBOSE`、`LS_INFO→INFO`、`LS_WARNING→WARN`、`LS_ERROR→ERROR`、`LS_NONE→ERROR`）；
   - 写 `logs/webrtc.log`，`layer=webrtc`、`tag=webrtc/<sanitized>`（截断 24 字符），同时 `Log.println` 到 logcat；
   - 注入级别：`BuildConfig.DEBUG ? LS_VERBOSE : LS_INFO`（§9.6）；
   - **限流**：回调内 200 条/秒上限，超出丢弃并每 5 s 打一条 `webrtc_log_throttled dropped=<n>`；
   - 若某分支无 `setInjectableLogger`：降级 `Logging.enableLogToDebugOutput(LS_INFO)` + 导出时抓 `logcat -d`（**必须登记为降级**）。
   - `PeerConnectionFactory.initialize(...)` 必须先于任何 factory/PC 创建，且只调一次（sink 注册一次性）。

---

## 10. 文档冲突裁定总表（D4）

> 编号 `C..` 供 verifier 与各 dev 报告引用。凡「裁定」列与任何文档冲突，以裁定为准。

| # | 冲突点 | 各文档说法 | **裁定** | 影响 |
|---|---|---|---|---|
| C01 | Java 包名 | `doc/05` `com.webrt.demo` ↔ `doc/10/11/13` `com.example.webrtcdemo` | **`com.example.webrtcdemo`**（D3） | t7/t8 |
| C02 | Kotlin 源根 | `doc/05` `src/main/java` ↔ `doc/10/13` `src/main/kotlin` | **`src/main/kotlin`**（D3，`sourceSets` 显式添加） | t8 |
| C03 | native 位置 | `doc/05` 仓库根 `native/` + `app/src/main/cpp` 软链 ↔ `doc/11/13` `app/src/main/cpp/` | **`app/src/main/cpp/`，无软链**（D3） | t7/t8 |
| C04 | 语言/版本 | `doc/10` Kotlin 1.9.22 / AGP 8.3 / Gradle 8.5 ↔ `doc/05` Kotlin 2.0+ | **Kotlin 2.0.21 / AGP 8.5.2 / Gradle 8.7 / JDK 17**（D2） | t8/t10 |
| C05 | Compose Compiler | `doc/10` §9 `composeOptions.kotlinCompilerExtensionVersion` | **`org.jetbrains.kotlin.plugin.compose` 插件（版本=Kotlin）** | t8 |
| C06 | 采集 | `doc/10` §6.1 **CameraX** ↔ `doc/02/05/ADR-005` SDK `Camera2Capturer` | **SDK `Camera2Enumerator`+`CameraVideoCapturer`**（D1）；CameraX 作废 | t8 |
| C07 | 渲染接线 | `doc/10` §7 `native setLocalVideoSink/addRemoteVideoSink` | **SDK `SurfaceViewRenderer` 直连，无相关 JNI**（D1） | t7/t8 |
| C08 | PeerConnection 归属 | `doc/05` §4/§5、`doc/11` §4 自研 C++ PC + 大 JNI 面 | **Kotlin 侧 `org.webrtc.PeerConnection`**（D1）；JNI 面收窄为 §6 | t7/t8 |
| C09 | 编码器接口 | `ADR-002`、`doc/11` §5 实现 `webrtc::VideoEncoder` + factory 注入 | **Java `VideoEncoderFactory/VideoEncoder` + 自有 JNI**（A1，§5）；登记为受控偏离 | t7/t8 |
| C10 | SVC 空间层 | `doc/02` §6、`doc/11` §5.2 期望 2 空间层 SVC | **空间层固定 1、时序层 3（L1T3）**，分层分配由本项目策略实现（§5.6，源码级硬约束） | t7/t11 |
| C11 | 信令路径 | `doc/05` §7 / `ADR-006` `/signal` ↔ `doc/09` §1 `/ws` | **主端点唯一为 `/ws`**（D5）；`/signal` **不得作为文档/契约中的主端点**出现。**captain 裁决 2026-09-13**：Go 侧把 `/signal` 注册为**显式标注的 deprecated 兼容别名**（如 `PathWSLegacy` + 注释"主端点唯一为 `/ws`"）是**允许的**；Android 客户端**只允许**使用 `/ws`。**不得**据此判失败（见 §11.4 与 V33） | t8/t9/t11 |
| C12 | 信令字段名 | `doc/05` §7 `stun/turn/turnUser/turnPass` ↔ `doc/09` `stunUrl/turnUrl/turnUsername/turnCredential` | **`doc/09` 拼写**（D5） | t8/t9 |
| C13 | 信令传输安全 | `doc/09` §1 `wss://` + Caddy 反代 | **`ws://47.238.144.66:8443/ws` + `usesCleartextTraffic=true`**（无域名/证书）；TLS 为可选增强 | t8/t10/t12 |
| C14 | 源码 submodule | `ADR-006` §2「不引 submodule」↔ `doc/05` §9.0 + 用户指令 | **引入 submodule**（已建 `.gitmodules`）；ADR-006 §2 该句被用户指令取代 | t3 |
| C15 | Go 日志库 | `doc/12` §1 `log/slog` ↔ D6 | **`logrus`**（终端 + 文件） | t9 |
| C16 | Go `leave` 语义 | `doc/12` §9 半实现 ↔ `doc/09` §3.8 | **`doc/09`**：通知对端 → 关连接 → 销房（§8.3） | t9 |
| C17 | roomId 字符集 | `doc/09` §7 排 `O/0/I/1` ↔ `doc/12` 排 `O/0/I/1/L` | **`ABCDEFGHJKMNPQRSTUVWXYZ23456789`**（更严，兼容 doc/09） | t8/t9 |
| C18 | libwebrtc 产物 | `doc/08` 只提取 `.a`+头 ↔ D1 需 Java SDK | **必须产出 AAR/jar/`libjingle_peerconnection_so.so`**（§4.3） | t5 |
| C19 | 构建规格/参数 | `doc/08` 建议 ≥16 GB / ≥100 GB / `ninja` 全量 ↔ 实测 7.1 GiB / 61 GB / 无 swap | **`-j2`（链接 `-j1`）、`symbol_level=0`、只编两个 target、分阶段可重跑**（§4.3） | t5 |
| C20 | `doc/12` 代码缺陷 | `doc/12` 与 `doc/09` 不一致/不可编译 | 按 §8.3 清单修正 | t9 |
| C21 | 构建/开发机 | `doc/03`「Windows Android Studio」、`doc/05` `e:\code\...` ↔ 实况单台 Linux 宿主 | **全部在 Linux 宿主完成**（t5/t10/t12），无 Windows 环节 | t5/t10 |
| C22 | 仓库根名 | `doc/05` `webrt-demo` ↔ 实况 `code/webrtc-demo` | **`/opt/dsh-workspaces/code/webrtc-demo`（容器同路径）** | 全体 |
| C23 | `.gitignore` 误伤 Go 源码 | `.gitignore` 裸模式 `signaling` 会忽略整个 `signaling/` 目录 | **必须改为 `/signaling/signaling`、`/signaling/signaling-linux-amd64`**（并保留源码跟踪） | t9/t10 |
| C24 | 日志格式/目录/导出 | 各文档均未定义 | **§9 全文** | 全体 |
| C25 | 风格约束范围 | `ADR-007`/`doc/05` §11 写 `native/` ↔ D3 | **`app/src/main/cpp/`**（Kotlin/Go 同规则，中文注释必含） | t7/t8/t9 |
| C26 | `doc/11` §7 stats 字段 | `outbound-rtp.bitrate-mean`、`encoder-implementation.target-bitrate` 等字段名不存在于公开 stats | **按 §7.4 的字段名**（`bytesSent` 差分、`availableOutgoingBitrate`、`encoderImplementation`） | t8 |
| C27 | `doc/03` 阶段 4 的「SVC 分层可观察」验收 | 期望 2 空间层曲线 | 改为**时序分层（L1T3）+ 本项目分配策略**可观察（§5.6） | t11 |
| C28 | `doc/01` §5 `relay-ip=<公网IP>` | `doc/01` 写公网 IP ↔ 内核不允许 bind NAT 地址（EIP 不在网卡上） | **`relay-ip` 必须为内网 IP**（`172.21.0.219`）；公网 relay 地址由 `external-ip=47.238.144.66/172.21.0.219` 通告（t6 实测 §7.7；`doc/01` 已加「实测修正」注记保留原文） | t9/t12/t11 |
| C29 | GN 参数集 | 契约 §9.7 表写 `rtc_dlog_always_on`「保持默认 `false`」↔ t5 实际设为 `true` | **以实测为准**：`rtc_dlog_always_on=true` 属 **captain 已授权偏差**（用途：release 下保留 `RTC_DLOG`；机制 `-DDLOG_ALWAYS_ON` → `common_inherited_config`）；`rtc_disable_logging=false` 与 `use_custom_libcxx` 不显式设置两条**仍然冻结**（§9.7「实测补充」、§11.1 R10） | t5/t11 |
| C30 | Go 日志字段名 | 契约 §9.1 要求 k=v 的 `key` 为 `snake_case` ↔ `signaling/main.go` 用 camelCase（`stunUrl`/`turnUrl`/`turnUser`/`logFile`） | **§9.1 适用**（含 logrus `Fields`）→ 记为 **low 级偏差、不判失败**；期望值 `stun_url`/`turn_url`/`turn_user`/`log_file`，由 go-dev 后续对齐（§9.1 适用范围、§11.4）。**状态：2026-09-13 已消解**（t9 已改 snake_case，V34 禁用字段名 0 命中）；本行保留作历史记录 | t9/t11 |
| C31 | Go 日志**文件路径** | 契约 §3.2/§9.2 原写 `/opt/signaling/logs/signaling.log` ↔ 部署事实为 **`/var/log/signaling/signaling.log`**（t12 按其任务契约选用 FHS 标准目录，`ExecStart` 带 `-log /var/log/signaling/signaling.log`；`/opt/signaling/logs` 不存在，`/var/log/signaling/signaling.log` 已正常写入 21904 B） | **以部署事实为准**：契约 §3.2/§9.2 已改为 **`/var/log/signaling/signaling.log`**（+ `.1`/`.2`）。路径经 **`-log <path>`** flag 传入、目录自动 `MkdirAll`；**2 MiB × 3 滚动与终端/journald 双写不变**。**Go 侧无需改动**（t9 已做成 flag）。旧契约值**作废、非偏差**，**verifier 不得据此判失败**；可核 `grep -n '\-log' /etc/systemd/system/signaling.service`（另见 V53） | t9/t12/t11 |

---

## 11. 风险、未决项与回写清单

### 11.1 风险（R）

| # | 风险 | 影响 | 缓解/责任 |
|---|---|---|---|
| **R1** | **t5 工期与可行性**：googlesource 实测 ≈0.32 MB/s，M129 源码数 GB → `gclient sync` 可能 10 h+；`-j2` 且无 swap | jar/so 缺失 → t8/t10 无法构建 APK | t5 分阶段（deps/gn/ninja/aar/extract 可重跑）、t1 watchdog、磁盘 <15 GB 告警；若 `phase_deps` 超时须报 captain |
| **R2** | 官方**无 Maven AAR**；若源码构建不可行，唯一替代是社区 `io.github.webrtc-sdk:android`（**非官方**） | 违反 D1「官方」 | 使用即**未授权偏离**：必须 captain 批准 + 报告登记 + 锁版本 |
| **R3** | Camera2 采集是 texture 帧，归一化层 `toI420()` 依赖采集线程 GL 上下文 | 编码器拿不到 I420 | §7.2 归一化层（在采集线程转换）；若实测失败，备选 `Camera1Enumerator`（I420 路径）或降分辨率；t8 必须在真机验证并记录 |
| **R4** | 编码器 CPU：VP9 软编在低端机可能掉帧 | 帧率/体验 | `cpu-used=8`、`640x480@30` 起步，必要时降 `480x360@24`；帧耗时 >33 ms 记 WARN |
| **R5** | IP 直连无 TLS + 云安全组 UDP 未验证 | `usesCleartextTraffic`；P2P 可能全走 RELAY | 已冻结 `ws://`；UDP 放行由 t6/t12 从外部视角验证（t1 §9.1） |
| **R6** | 无 swap + 7.1 GiB 内存：链接阶段 OOM | t5/t10 失败 | `symbol_level=0`、链接 `-j1`、`-k 0` 续编；swap 由 captain 决定（§11.3 U1） |
| **R7** | `.gitignore` 会忽略 `signaling/` 整个目录（C23） | Go 源码不入库 | t9 提交前修 `.gitignore`（附条件：`/signaling/<binary>` 精确忽略） |
| **R8** | 无真机/无最终 HEAD SHA | 端到端通话无法在本任务内自证 | t5 回填 HEAD SHA；t8/t10 验收降级为「可构建 + 契约一致」；通话验证需用户设备配合 |
| **R9** | ~~t6 实测：阿里云安全组入方向未放行 UDP 3478 / UDP 49152-49200 / TCP 8443~~ → **已解除（resolved，2026-09-13 约 16:0x）** | 原影响：公网客户端无法使用 STUN/TURN/信令。**现状（放行后实测）**：`curl http://47.238.144.66:8443/healthz` → **200**；`turnutils_stunclient -p 3478 47.238.144.66` → **`47.238.144.66:53170`**（t12 与 captain 各自独立实测）；公网路径可用 | **无需再动**；verifier 按 §12.9 **V64 实跑并记「通过」**（**不得**沿用旧的"未验证/阻塞"）。**非阻塞残留**：TCP 3478/5349（可选）未放行，demo 走 `transport=udp` 不受影响；历史取证见 §7.7 与 `reports/06-coturn.md` §7.3 |
| **R10** | t5 编译参数与契约 §4.3/§9.7 表格**不完全一致**（新增 `rtc_dlog_always_on=true`；**captain 已授权**，见 §9.7「实测补充」/C29） | 误判风险：verifier 把已授权偏差当违规；release 下 `RTC_DLOG` 全量输出带来额外日志开销与磁盘写入 | **已授权偏差，不得据此判失败**；开销由 §9.6 级别开关 + §9.7 的 200 条/秒限流兜住；t5 报告需记录该参数与最终 `gn args` |

### 11.2 对 t5（webrtc-builder）的回写清单

1. 产物**必须**包含：`third_party/libwebrtc/java/libwebrtc-arm64.aar`、`.../libwebrtc-java.jar`、`.../jni/arm64-v8a/libjingle_peerconnection_so.so`、`third_party/libvpx/lib/libvpx.a`、`third_party/libvpx/include/vpx/*.h`（§4.3/§4.4）。缺任一项 t8/t10 阻塞。
2. 只编**两个** ninja target，无需全量：
   `ninja -C out/Release-arm64 sdk/android:libwebrtc sdk/android:libjingle_peerconnection_so`
   - jar 路径：`out/Release-arm64/lib.java/sdk/android/libwebrtc.jar`
   - so 路径：`out/Release-arm64/libjingle_peerconnection_so.so`
   - 官方 `build_aar.py` 用的 GN 参数基线：`target_os="android" is_debug=false is_component_build=false rtc_include_tests=false target_cpu="arm64"`（可加 `android_static_analysis="off"`）；`is_component_build` **必须 false**。
3. 日志相关：**不要**设置 `rtc_disable_logging=true`；**本次已授权新增 `rtc_dlog_always_on=true`**（release 下保留 `RTC_DLOG`；见 §9.7「实测补充」、§10 C29、§11.1 R10）；其余按 §9.7。
4. `use_custom_libcxx` **不要显式设置**（Android 默认 true）。
5. 报告必须写：`src/` 最终 `git rev-parse HEAD`、`gn args`、两个 target 的构建结果、产物清单与 `file` 架构核验输出。
6. libvpx 必须与 app 同 NDK（`26.1.10909125`）编译，`--enable-pic`、静态库。
7. **实测补充（2026-09-13）**：本次编译另加 `rtc_dlog_always_on=true`（captain 已授权；用途、机制与不变量见 §9.7「实测补充」及 §10 C29 / §11.1 R10）；除此之外参数与上表一致。

### 11.3 未决项（U，需 captain/用户决策）

| # | 未决项 | 默认处置 |
|---|---|---|
| U1 | 是否创建 4–8 GB swapfile | 不创建（由 captain 决定；风险 R6） |
| U2 | t5 超时后是否允许社区 AAR 兜底 | 不允许（需明确批准；风险 R2） |
| U3 | 是否安装 Caddy 做 TLS | 不安装（用 `ws://`） |
| U4 | 采集分辨率/帧率最终值 | `640x480@30` |
| U5 | 是否显式配置 `JavaAudioDeviceModule` / APM | 不配置（用默认 ADM） |
| U6 | 是否给 libwebrtc 打补丁加 `RTC_LOG` | 不打补丁（用 `Loggable`） |
| U7 | `doc/09` 未定义「重复 join / 已入房再 create」的行为 | **已决议（2026-09-13，t9 登记）**：两者均返回 `INVALID_MESSAGE`（`message` 含 `already in room <roomId>`）+ Warn 日志；**未入房时 `create` 合法**（§8.3 第 4 条） |

### 11.4 已登记的实现级偏差（**不判失败**）

> `D-*` 是**实现级偏差清单**（代码 ↔ 契约），与 §10 的**文档级冲突裁定**（`C*`）分开登记；两者均**不得**作为 t11 的失败判据（除表中明确写「不得」者）。

| # | 偏差 | 位置 | 性质与期望 |
|---|---|---|---|
| **D-1** | `/signal` 作为**活动路由**注册（`PathWSLegacy`） | `signaling/server/server.go`：`mux.HandleFunc(PathWSLegacy, s.HandleWS)` | **captain 裁决（2026-09-13）允许**：属显式标注的 deprecated 兼容别名，不影响「主端点唯一」的意图。**三个前提**：① 代码处有 deprecated/兼容别名注释并写明「主端点唯一为 `/ws`」；② `/ws` 仍是主端点；③ Android 客户端只用 `/ws`。verifier 按 V33 复核，**不得据此判失败**（C11）。**状态：2026-09-13 已消解**（t9 已删除该活动别名，`/signal` 实测 404）；本行保留为"允许但须显式标注"的口径 |
| **D-2** | Go `logrus` 日志字段用 camelCase（`stunUrl`/`turnUrl`/`turnUser`/`logFile`） | `signaling/main.go` 启动日志 | **low 级偏差、不判失败**：§9.1 的 k=v 口径**适用**（含 logrus `Fields`），期望值 `stun_url`/`turn_url`/`turn_user`/`log_file`；由 go-dev 后续对齐（C30）。**状态：2026-09-13 已消解**（t9 已改为 snake_case） |
| **D-3** | **`created` 消息不含本端 `peerId`** —— 发起方（host）在协议层不被告知自己的 ID | `doc/09` §3.1（`created` 仅有 `roomId` + ICE server 四件）；`doc/09` §10 schema `additionalProperties:false` | **已知限制，low，不得判失败**。**captain 裁决（2026-09-13）：不扩展 `created`**，理由：① `doc/09` 是 D5 冻结权威，扩字段破坏 §10 schema，属破坏性变更；② `doc/10` 的 UI 规格无任何位置需要显示本端 peerId（显示房间码/连接模式/NAT/码率）；③ host/joiner 的 offer/answer 职责不依赖本端 ID。**缓解（客户端口径，见 §8.2）**：joiner 用 `joined.peerId`；host 收到 `peerJoined.peerId = X` 后取反推导本端。**未来若 UI 确需**：走 §0 变更流程（须 captain + 用户批准）出 v1.x，**不在本轮扩字段** |
| **D-4** | JNI 目标类用 **`public object`**（非契约原建议的 `internal object`）；`qp` 用 **`VP8E_GET_LAST_QUANTIZER`**（非契约原写的 `VP9E_GET_LAST_QUANTIZER`） | `app/src/main/kotlin/.../nativebridge/*.kt`（t8）；`app/src/main/cpp/encoder/vp9_encoder.cpp`（t7） | **受控偏离 / API 名称适配，不得判失败**。① `internal` 会被 Kotlin 做 JVM 名字修饰（`nativeInit$<module>`），而 `RegisterNatives`/`GetStaticMethodID` 按字面名查找 → **静默注册失败**，故 `public object` 是**正确做法**（§6.1 注记）；② libvpx 无 `VP9E_GET_LAST_QUANTIZER`，VP9 复用共用控制表，正确名 `VP8E_GET_LAST_QUANTIZER`（`vp9_cx_iface.c`） |
| **D-5** | **L1T3 下显式设置 `temporal_layering_mode`**（T=3 → `MODE_0212`）；完整写入 `layer_target_bitrate` 但知其不生效 | `app/src/main/cpp/encoder/*`（t7，captain 裁决保留） | **契约遗漏的实现细节，不得判失败**。源码级结论：`layer_target_bitrate[]` 仅在 `ss_number_layers > 1` 时被 libvpx 使用（`vp9_svc_layercontext.c`）；L1T3 下实际生效的是 `rc_target_bitrate` + `temporal_layering_mode`。**保留显式设置**，否则 T=3 分层不按预期工作；`ss/ts/layer/rc` 四者仍须完整写入（学习点完整）（§5.6 第 4 条、V21 注记） |
| **D-6** | **Offerer 由「角色（host）固定」**，而非 `doc/09` §3.3 字面的「**收到 `peerJoined` 的一方**」 | `app/src/main/kotlin/.../webrtc/CallSession.kt`（`maybeCreateOffer()` 首行 `role != ROLE_HOST` 早退，恒由 host 发；t8 读码，t9 交叉确认） | **受控偏离 / 澄清，low，不得判失败**。**可达路径下两者行为等价**：正常流程与"旧会话静默掉线后重连"都**恰好选中 host**；差异只是"指定依据"不同（角色 vs `peerJoined` 投递对象）。**理由已修正（2026-09-13，t9 两个 E2E 用例证伪原"死锁"论断）**：v1.0-l 曾称字面规则在"双方同时重连"下互等死锁——实测该组合**不可达**（双方都断开 → 房间空 → **立即销毁** → `ROOM_NOT_FOUND`；静默掉线期间 `join` 得 `ROOM_FULL`，读超时 ~45 s 后才回收），故**字面规则不死锁、无需兜底条款**。**保留角色固定是为确定性**（不依赖 `peerJoined` 投递）；**真实风险是客户端混用两套规则**（可能双发或都不发）。`doc/09` 未改动（D5 不变）；不变量与用例见 §8.2 |
| **D-7** | **静默掉线后不可完整恢复**（本轮取**口径 B**：`peerLeft` → 立即 `DISCONNECTED`/挂断 → 房间立即销毁 → 掉线方得 `ROOM_NOT_FOUND`） | `doc/09` §4 字面（`doc/09` **未改动**）；行为与实测见 §8.5 | **已知限制，low，不得判失败**。**captain 裁定（2026-09-13）**：完整恢复**还需要重协商（ICE restart）**，本轮**无真机可验证**；只加宽限期而不做重协商会产生**"信令已重连、媒体是死的"僵尸通话**，**比干净挂断更糟** → 取**一致、诚实的失败路径**（双方回首页 + 明确提示）。**后续增强（口径 A）**已登记 §8.5（有界宽限期 + ICE restart 前提），本轮不实施；**两种口径服务端均无需改动** |

---

## 12. 验收清单（t11/verifier 逐条执行）

> 约定：`R=` 必须通过；`D=` 需要设备/真机（本任务只能静态核验，须在报告中标注「未验证」而不是「通过」）。
> 仓库根：`/opt/dsh-workspaces/code/webrtc-demo/`（容器 `/data/dsh/home/workspace/code/webrtc-demo`）。
>
> **命令执行约定（防「假失败」，2026-09-13 实测修订）**：
> 1. 所有命令**从仓库根执行**，表内路径均为仓库根相对路径。
> 2. `grep` 对**目录**必须带 `-r`；对**文件**不需要。缺 `-r` 会以退出码 **2** 失败（不是「无匹配」）。
> 3. 凡期望「**0 命中**」的检查，`grep` 无匹配时退出码为 **1，这属于通过**；不要用 `|| true` 之类的花招，按「1 = 通过」记录即可。
> 4. 表内命令**不使用管道**、也不使用 `-E` 里的 `|` 交替（会被 Markdown 表格转义污染）。多处匹配一律用 `grep -e A -e B`。
> 5. 不使用 `**` 递归 glob（bash 默认 `globstar` 关闭）；需要递归用 `find`。
> 6. `ls -l a b` 在任一参数缺失时退出码为 2；**产物存在性一律用 `find`**。补充：`find` **只有「搜索路径存在但无匹配」才返回 0**；**搜索路径本身不存在时返回 1**（例如 `find scripts` 而 `scripts/` 不存在）——遇此按「路径不存在」记，或先把候选路径收敛到确实存在的父目录。两条都不构成失败判据。
> 7. 环境变量按 §3.5/§4.5 导出：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`、`ANDROID_HOME=<WS>/android-sdk`、`ANDROID_NDK_HOME=<WS>/android-sdk/ndk/26.1.10909125`；`<WS>` = 宿主机 `/opt/dsh-workspaces`、容器内 `/data/dsh/home/workspace`（**同一目录**），Go 用 `<WS>/go/bin/go`。**Go 另需可写缓存**：`GOCACHE=/tmp/gocache`、`GOMODCACHE=/tmp/gomodcache`（或先 `. <WS>/env-go.sh`，见约定 11）——否则容器内 `HOME=/data/dsh/home` 只读会令 `go build`/`go vet` **必然失败**（verifier 实测：加这两个变量后 `build_rc=0` / `vet_rc=0`）。
> 8. 命令因**环境/工具缺失**（如 `file`、`aapt` 不在 PATH）而无法执行时，记「**未验证**」，不得记「失败」。
> 9. **已授权偏差（不得据此判失败）**：t5 本次编译新增 `rtc_dlog_always_on=true`（captain 授权；见 §9.7「实测补充」、§10 **C29**、§11.1 **R10**）——GN 参数集与 §4.3 表格不完全一致**不构成失败**。`doc/01` §5 与 `doc/08` §9.3 的「实测修正/补充」注记同理，属文档偏差登记，不影响实现判定。
> 10. **递归 `grep` 覆盖 `signaling/`、`app/`（含 `app/build/**`）或仓库根时，必须加 `-I`**（跳过二进制）并附**精确的 `--include` 过滤**；只扫 `app/src/main/**` 源码树的检查可省 `-I`。本仓库存在必然误命的产物/日志：`signaling/signaling`（二进制，会报 `binary file matches`）、`signaling/dist/signaling-linux-amd64`、`signaling/logs/*.log`、`app/build/**`。Go 源码检查用 `--include='*.go'`，Kotlin 用 `--include='*.kt'`。
> 11. **Go 命令必须先 `. <WS>/env-go.sh`**（**POSIX 点号写法**；它设置 `GOCACHE`/`GOMODCACHE`/`GOPROXY`）。**不得使用 bash 专有的 `source` 内建**：宿主机 `/bin/sh -> dash`，实测在 `sh -c` 下该内建**不存在** → **rc=127（`... not found`）**；同一条命令改用 `. ` 写法即正常（`go version go1.22.12`）。容器内直接 `go build` 会因 `HOME=/data/dsh/home` 只读报 `build cache: permission denied` 而**必然失败**——两者都属**环境口径缺失**，不是实现缺陷。
> 12. 标注「**需人工确认**」的检查项属半自动判定（如「只出现在注释中」「赋值为 1」），verifier 必须人工核对，**不得**仅凭 grep 退出码判失败。
> 13. 实现级偏差见 §11.4（`D-1` `/signal` 兼容别名、`D-2` Go 日志 camelCase 键、`D-3` `created` 无本端 `peerId`、`D-4` JNI 类 `public object` + `VP8E_GET_LAST_QUANTIZER`、`D-5` L1T3 显式 `temporal_layering_mode`、`D-6` Offerer 由 host 角色固定、`D-7` 静默掉线后不可完整恢复），均**不得**判失败。
> 14. **日志行格式只按 §9.1 的文字规则判定**：`LEVEL` 左对齐定宽 **7**、`layer` 定宽 **6**、`tag` 定宽 **9**，各字段后接 1 个空格（即实测字段宽度 `8 / 7 / 10`）。§9.1 的示例行**仅作示意**，verifier **不得**按示例逐字比对宽度判失败。对照（t9 真实输出，已符合规则）：
> ```
> 2026-09-13T07:51:29.376Z INFO    go     room      [34310/-] room_created peer=peer-001 role=host room=3ZN8 room_count=1
> ```
> 15. **禁止为「通过验收」而在源码新增字面量/端点硬编码**（例如为满足 V33 而在 Kotlin 里加 `"/ws"`）。端点真源是 `app/build.gradle.kts` 的 `buildConfigField("String","SIGNALING_URL", …)`（§8.1，支持诊断页运行时覆盖）；verifier 若发现此类"为过检而新增"的字面量，应报**偏离**而非放行。

### 12.1 目录 / 命名 / 包名（R）

| # | 检查 | 命令 / 方法 | 期望 |
|---|---|---|---|
| V01 | Kotlin 源根与包名 | `ls app/src/main/kotlin/com/example/webrtcdemo` | 目录存在 |
| V02 | 无旧包名残留 | `grep -rnI --include='*.kt' --include='*.kts' --include='*.go' --include='*.xml' "com\.webrt\.demo" app signaling` | 0 命中（退出码 1 = 通过） |
| V03 | native 目录 | `ls app/src/main/cpp/CMakeLists.txt`；`grep -rnI --include='*.kt' --include='*.kts' -e "symlink" -e "软链" app` | CMakeLists 存在；第二条 0 命中（1 = 通过） |
| V04 | 无仓库根 `native/` 目录 | `test ! -d native && echo OK` | OK |
| V05 | Kotlin 源集声明 | `grep -n "src/main/kotlin" app/build.gradle.kts` | ≥1 |
| V06 | 包名/applicationId | `grep -n -e "namespace" -e "applicationId" app/build.gradle.kts` | 均为 `com.example.webrtcdemo` |

### 12.2 版本与技术栈（R）

| # | 检查 | 期望 |
|---|---|---|
| V07 | `grep -n "compose-bom" app/build.gradle.kts` | `2024.10.01` |
| V08 | `grep -n "org.jetbrains.kotlin.plugin.compose" app/build.gradle.kts` | ≥1；且 `grep -n "kotlinCompilerExtensionVersion" app/build.gradle.kts` 0 命中（1 = 通过） |
| V09 | `grep -n -e "jvmTarget" -e "VERSION_17" app/build.gradle.kts` | 17 |
| V10 | `grep -n "ndkVersion" app/build.gradle.kts` | `26.1.10909125` |
| V11 | `./gradlew -v`（t10 执行） | JVM 17 / Gradle 8.7 |
| V12 | `grep -rnI --include='*.kt' --include='*.kts' -e "CameraX" -e "camera-core" -e "camera-view" app` | 0 命中（C06；退出码 1 = 通过） |

### 12.3 编码器注入路线（R）

| # | 检查 | 命令 | 期望 |
|---|---|---|---|
| V13 | 存在 Java 适配器 | `ls app/src/main/kotlin/com/example/webrtcdemo/encoder/` | `Vp9VideoEncoderFactory.kt`、`Vp9VideoEncoder.kt` |
| V14 | 实现接口 | `grep -rn "VideoEncoderFactory" app/src/main/kotlin/com/example/webrtcdemo/encoder/` | ≥1（`Vp9VideoEncoderFactory` 实现 `org.webrtc.VideoEncoderFactory`） |
| V15 | 实现名冻结 | `grep -rn "SelfVp9Libvpx" app/src/main` | ≥3（Kotlin 常量、C++ `nativeGetImplName`、日志） |
| V16 | `createNative` 返回 0 | `grep -rn "createNative" app/src/main/kotlin` | ≥1；人工确认其为 `override fun createNative(webrtcEnvRef: Long): Long = 0L`（**真实 API 名是 `createNative`，不是 `createNativeVideoEncoder`**——原文的 grep 模式会必然 0 命中，2026-09-13 连带修正） |
| V17 | 无 `webrtc::VideoEncoder` 继承（C09） | `grep -rn "webrtc::VideoEncoder" app/src/main/cpp` | 0 命中（允许注释提到；退出码 1 = 通过） |
| V18 | CMake **不**链接 libwebrtc | `grep -n -e "libwebrtc" -e "libjingle" app/src/main/cpp/CMakeLists.txt` | **需人工确认**：命中行必须全部是注释（`#` 开头）；有任一非注释命中即失败 |
| V19 | CMake 链接 libvpx + 库名 | `grep -n -e "vpx" -e "webrtcdemo_native" app/src/main/cpp/CMakeLists.txt` | 命中 |
| V20 | 第三方路径注入 | `grep -rn "WEBC_THIRD_PARTY" app/src/main/cpp/CMakeLists.txt app/build.gradle.kts` | 两文件均命中 |
| V21 | 码率闭环 4 要素 | `grep -rn -e "ss_target_bitrate" -e "ts_target_bitrate" -e "layer_target_bitrate" -e "vpx_codec_enc_config_set" app/src/main/cpp/encoder/` | 4 者均命中（**只核验"写入存在性"**）。**注记**：L1T3（`ss_number_layers=1`）下 `layer_target_bitrate` **不被 libvpx 使用**，真正生效的是 `rc_target_bitrate` + `temporal_layering_mode`（§5.6 第 4 条 / §11.4 D-5）——**不得**用本项或 V22 去"验证该字段生效"，更不得据此判失败 |
| V22 | 空间层强制 1 | `grep -rn "ss_number_layers" app/src/main/cpp/encoder/` | **需人工确认**：赋值为 1，且邻近有中文注释说明 §5.6 |
| V23 | CSV 表头 | `grep -rn "ts_ms,total_bps,fps" app/src/main/cpp` | ≥1（C++ 侧任意文件均可，§9.4 未限定文件） |
| V24 | 归一化层存在 | `grep -rnI --include='*.kt' -e "FrameNormalizer" -e "CapturerObserver" app/src/main/kotlin` | ≥1 |
| V25 | 旧回调已清除 | `grep -rnI --include='*.kt' --include='*.cpp' --include='*.h' -e "onRemoteVideoFrameReady" -e "nativeSetVideoSurface" -e "setRemoteVideoSink" app` | 0 命中（退出码 1 = 通过） |

### 12.4 JNI 契约（R）

| # | 检查 | 期望 |
|---|---|---|
| V26 | 4 个 bridge 类存在 | `ls app/src/main/kotlin/com/example/webrtcdemo/nativebridge/` 应列出 `NativeLog.kt`/`NativeVp9Encoder.kt`/`NativeNatDetector.kt`/`NativeCallbacks.kt` |
| V27 | native 方法总数 = 15 | `grep -rh "external fun" app/src/main/kotlin/com/example/webrtcdemo/nativebridge/` 的**输出行数 = 15** |
| V28 | C++ 方法表数量 = 15 | `grep -rhE '^[[:space:]]*[{]"' app/src/main/cpp/jni/` 的**输出行数 = 15**（每行一个 `JNINativeMethod` 条目；`[{]` 避免 ERE 把 `{` 当重复量词） |
| V29 | 类名字符串一致 | `grep -rhoE "com/example/webrtcdemo/nativebridge/[A-Za-z]+" app/src/main/cpp` 去重后为 **4 个**唯一类名 |
| V30 | 状态码常量 | `grep -rn -e "NO_OUTPUT" -e "ERR_PARAMETER" -e "UNINITIALIZED" app/src/main/cpp app/src/main/kotlin` ≥1 |
| V31 | 级别常量一致 | `grep -rn "OFF" app/src/main/kotlin/com/example/webrtcdemo/log`；**需人工确认**：存在 `OFF = 5`（或等价映射）且 `VERBOSE..ERROR = 0..4`，数值与 §6.6 一致（不得仅凭 grep 退出码判定） |
| V32 | ProGuard 保留 JNI 类 | `grep -n -e "org.webrtc" -e "nativebridge" -e "native <methods>" app/proguard-rules.pro` ≥3 |

### 12.5 信令（R）

| # | 检查 | 期望 |
|---|---|---|
| V33 | 路径 `/ws` | ① 服务端：`grep -rnI --include='*.go' '"/ws"' signaling` ≥1。② 客户端：`grep -rnI "8443/ws" app` ≥1，**且必须命中端点真源** `grep -c "8443/ws" app/build.gradle.kts` ≥1（即 `buildConfigField("String","SIGNALING_URL", …)`）；另**人工确认**该默认值以 `/ws` 结尾、诊断页覆盖值不改端点。③ `grep -rnI --include='*.go' --include='*.kt' '"/signal"' signaling app` 允许命中，但每个命中处须带 deprecated/legacy/兼容别名标注（**需人工确认**），无标注才判失败；**主端点唯一为 `/ws`**，不得仅因 `/signal` 出现而判失败（C11、§11.4 D-1）。④ **不得**为通过本项而在 Kotlin 新增硬编码 `"/ws"`（约定 15） |
| V34 | 协议字段名（**仅协议层**） | `grep -rnI --include='*.go' -e "turnCredential" -e "stunUrl" signaling/protocol/` ≥4；`grep -rnI --include='*.go' -e '"turnUser"' -e '"turnPass"' signaling/protocol/` 期望 0 命中（1 = 通过）。**范围限定 `signaling/protocol/`**；`signaling/main.go` 的 logrus **日志字段**（`turnUser` 等）**不属协议字段**、不计入本项（§11.4 D-2），不得据此判失败 |
| V35 | roomId 字符集 | `grep -rnI --include='*.go' "ABCDEFGHJKMNPQRSTUVWXYZ23456789" signaling` ≥1 且 `grep -rnI --include='*.kt' "ABCDEFGHJKMNPQRSTUVWXYZ23456789" app` ≥1 |
| V36 | 心跳与错误码 | `grep -rnI --include='*.go' -e "ROOM_FULL" -e "ROOM_EXPIRED" -e "NOT_IN_ROOM" -e "INVALID_MESSAGE" signaling` ≥4；`grep -rnI --include='*.go' -e "ping" -e "heartbeat" signaling` 的输出**需人工确认**含 15 秒心跳常量 |
| V37 | `leave` 三步 | `grep -rnI --include='*.go' -e "peerLeft" -e "RemoveRoom" -e "Close()" signaling`；三者均须命中（文件位置不限，不绑定 `server/ws_handler.go`） |
| V38 | Go 日志 logrus | `grep -rnI --include='*.go' "sirupsen/logrus" signaling` ≥1；`grep -rnI --include='*.go' "log/slog" signaling` 期望 0 命中（1 = 通过）；`--include='*.go'` 用于排除 `signaling/README.md` 的说明文字（否则假失败） |
| V39 | `go build` / `go vet` | `. <WS>/env-go.sh && cd signaling && go build ./...` 与 `. <WS>/env-go.sh && cd signaling && go vet ./...`；两条退出码均须为 0。**必须先 `.` 加载**（POSIX 点号；**不得用 `source`** —— 宿主机 `/bin/sh` 是 dash，`source` → rc=127 `source: not found`，见约定 11）；否则容器内 `HOME` 只读 → `build cache: permission denied`。**质量项（须在宿主机执行）**：`. <WS>/env-go.sh && cd signaling && CGO_ENABLED=1 go test -race -p 1 ./... -count=1`（期望退出码 0、0 竞争报告）；容器内**无 `gcc`**（`-race` 需 CGO）→ 容器内不可执行。**现状（2026-09-13）**：t9 **已在宿主机执行通过**（含 race 编译的真实服务进程 + race 编译客户端走真实 TCP 共 5 轮，均 0 竞争报告、优雅退出），证据 `signaling/logs/t9-race-evidence.log` 与 `reports/09-go-signaling.md` §4.3；**verifier 应独立重跑**以完成独立验证（结果应一致）；若确实无法重跑，可引用 t9 证据并注明**执行者/时间/宿主**——**不得**因"容器内不可执行"而记「未验证」或判失败 |

### 12.6 日志与导出（R）

| # | 检查 | 期望 |
|---|---|---|
| V40 | Kotlin 唯一日志门面 | `grep -rnI --include='*.kt' "android.util.Log" app/src/main/kotlin`；**需人工确认**：命中仅允许出现在 `log/`、`Loggable` 转发与异常处理器三处，其它位置命中即为偏离 |
| V41 | native 日志 JNI | `grep -rn "nativeInit" app/src/main/kotlin` ≥1（人工确认实参含 `maxBytes`/`maxFiles`，允许跨行书写）；`grep -rn "nativeInit" app/src/main/cpp/jni` ≥1 |
| V42 | FileProvider | `grep -rn "com.example.webrtcdemo.fileprovider" app/src/main` ≥2；`grep -rn "files-path name=\"logs\"" app/src/main/res/xml` ≥1 |
| V43 | 滚动 2 MiB × 3 | `grep -rn -e "2097152" -e "2 \* 1024" app/src/main/cpp/log app/src/main/kotlin/com/example/webrtcdemo/log` ≥1 |
| V44 | 导出打包范围 | `grep -rn -e "webrtcdemo-logs-" -e "ACTION_SEND" -e "EXTRA_STREAM" app/src/main/kotlin` ≥3 |
| V45 | libwebrtc 日志注入 | `grep -rn -e "setInjectableLogger" -e "Loggable" app/src/main/kotlin` ≥2 |
| V46 | 崩溃落盘 | `grep -rn "setDefaultUncaughtExceptionHandler" app/src/main/kotlin` ≥1 |

### 12.7 构建与产物（R，t5/t10 完成后）

| # | 检查 | 命令 | 期望 |
|---|---|---|---|
| V47 | libwebrtc Java 产物 | `find third_party/libwebrtc/java -name "libwebrtc-java.jar" -o -name "libjingle_peerconnection_so.so"` | 两条路径均命中（`.jar` 与 `jni/arm64-v8a/*.so`） |
| V48 | libvpx 产物 | `find third_party/libvpx -name "libvpx.a" -o -name "vpx_codec.h"`；架构：`"$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump -f third_party/libvpx/lib/libvpx.a`（**不要接 `head`**；若接了，SIGPIPE 会让退出码变成 74/141，而输出完全正确） | 判定依据 = **输出内容是否含 `architecture: aarch64`，一律不得按退出码**。verifier 已实测：156/156 个成员均为 aarch64，`llvm-readelf -h` 复核 `Machine: AArch64` 亦可。**注意**：`file(1)` 对 `.a` 只报 `current ar archive`（宿主机亦然）、容器内**没有 `file`**——均属正常，不得据此判失败；`file … \| grep aarch64` 判 `.a` 必然假失败 |
| V49 | native 库构建 | `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=<WS>/android-sdk ./gradlew :app:assembleDebug`；产物：`find app/build -name libwebrtcdemo_native.so` | gradle 退出码 0；`find` 至少 1 条（禁用 `**` glob） |
| V50 | APK 内容 | `APK=$(find app/build/outputs/apk/release -name "*.apk" -print -quit)`，再 `unzip -l "$APK"` | 含 `lib/arm64-v8a/libwebrtcdemo_native.so` 与 `lib/arm64-v8a/libjingle_peerconnection_so.so`。**注意**：① **必须用 `-print -quit` 只取一个 APK**——否则 `unzip -l "$(find …)"` 在 `app-release.apk` 与 `app-release-unsigned.apk` 并存时会把第二个当成员名（宿主机实测 `rc=9, cannot find or open`）；② 未配签名时产物是 `app-release-unsigned.apk`，故不得硬编码文件名；③ **容器内无 `unzip`**（宿主机有）→ 经宿主机执行，或记「未验证」 |
| V51 | APK 包名/权限/cleartext | `APK=$(find app/build/outputs/apk/release -name "*.apk" -print -quit)`；`"$ANDROID_HOME"/build-tools/34.0.0/aapt2 dump badging "$APK"`；cleartext：`"$ANDROID_HOME"/build-tools/34.0.0/aapt2 dump xmltree --file AndroidManifest.xml "$APK"` 的输出中查 `cleartext` | 包名 `com.example.webrtcdemo`；CAMERA/RECORD_AUDIO/INTERNET；存在 `usesCleartextTraffic=true`。**注意**：宿主机 PATH **无 `aapt`**，必须用 build-tools 内 **`aapt2` 绝对路径**（`aapt` 缺失不算失败）；同样须 `-print -quit` 避免多 APK；`aapt2` 不可用则记「未验证」 |
| V52 | Go 二进制 | `find signaling -type f -name "signaling*" -not -name "*.log"` | ≥1（实测产物在 **`signaling/dist/signaling-linux-amd64`**，另有 `signaling/signaling`；**不得**用 `-maxdepth 1`，否则漏掉 `dist/`）；`file` 可用时确认 ELF x86-64，不可用则记「未验证」 |
| V53 | systemd unit | 宿主机（**二者等效，任选**）：`systemctl list-unit-files coturn.service signaling.service`，或 `systemctl show -p FragmentPath -p ActiveState coturn signaling`；仓库 `find . -name "*.service" -not -path "./.git/*"` | **两个 unit 均存在且 `enabled`/`active`**；**(a) 仓库有 `.service` 文件、(b) 宿主机 systemctl 可见，满足其一即通过**；**仅写在 t12 报告正文里不算验收证据**（须有 `.service` 文件或 systemctl 可见）。**附加（C31）**：signaling unit 的 `ExecStart` 须含 **`-log /var/log/signaling/signaling.log`**（可核 `grep -n '\-log' /etc/systemd/system/signaling.service`）。**注意**：coturn 的 unit 名是 **`coturn.service`**（发行版包自带：`systemctl show coturn -p FragmentPath` → `/usr/lib/systemd/system/coturn.service`，**不在** `/etc/systemd/system/`）——**不得**写成 `turnserver.service`；也**不得**用 `ls -l a b` 判存在（任一缺失即 rc=2，会掩盖另一个确实存在，正是约定 6 要避免的写法） |

### 12.8 需真机（D，本任务内无法自证，必须标「未验证」）

| # | 检查 |
|---|---|
| V54 | 两端设备 A/B：`create`→`join`→`offer/answer/ice`→视频互通 |
| V55 | `encoderImplementation == SelfVp9Libvpx`（stats） |
| V56 | 限速场景下 `setrates`/`ts_target_kbps` 与 `outbound-rtp` 码率同步下降（CSV + stats） |
| V57 | P2P 与 RELAY 两条路径均可复现（诊断页切换 `RELAY`） |
| V58 | 本端/对端 NAT 类型显示；`Leave` 后双方回 Home |
| V59 | 日志导出 zip 可被系统分享并包含 `app.log`/`native.log`/`encoder_bitrate.csv`/`webrtc.log` |

---

### 12.9 coturn 与 ICE server（R，t6 已交付）

| # | 检查 | 命令 / 方法 | 期望 |
|---|---|---|---|
| V60 | 服务端配置一致（C28） | 宿主机 `grep -n "listening-ip" /etc/turnserver.conf`、`grep -n "external-ip" /etc/turnserver.conf`、`grep -n "relay-ip" /etc/turnserver.conf` 三条分别执行；对照 `deploy/turnserver.conf` | `listening-ip=172.21.0.219`、`external-ip=47.238.144.66/172.21.0.219`、**`relay-ip=172.21.0.219`（内网，不是公网）**。**注意**：不要写成 `grep -nE "a\|b\|c"` —— `-E` 下 `\|` 是字面 `\|`，会 0 命中造成假失败 |
| V61 | 服务与全流程 | 宿主机 `systemctl is-active coturn`；`turnutils_stunclient -p 3478 172.21.0.219`；`turnutils_uclient -v -y -u demo -w demopass -p 3478 -n 3 -m 2 172.21.0.219` | `active`；STUN 返回 `47.238.144.66:<port>`；Allocate 成功、中继丢包 0% |
| V62 | 负例认证 | `turnutils_uclient -v -y -u demo -w WRONGPASS -p 3478 -n 1 -m 1 172.21.0.219` | 失败（`check_stun_auth ... credentials are incorrect`；此命令**期望非 0 退出码**） |
| V63 | 客户端下发/消费一致（§7.7/§8.2） | `grep -rnI --include='*.go' "stun:47.238.144.66:3478" signaling`；`grep -rnI --include='*.go' "turn:47.238.144.66:3478?transport=udp" signaling`；`grep -rnI --include='*.go' --include='*.kt' -e "turnUsername" -e "turnCredential" signaling app` | 服务端下发的 `stunUrl`/`turnUrl`/`turnUsername`/`turnCredential` 与 §7.7 逐字一致，客户端消费同名字段 |
| V64 | 公网可达性（R9 **已解除**） | 任意外网机器执行 `turnutils_stunclient -p 3478 47.238.144.66`；另可 `curl -o /dev/null -w "%{http_code}" http://47.238.144.66:8443/healthz` | **安全组已于 2026-09-13 放行，当前应返回 `47.238.144.66:<port>`（STUN）与 `200`（healthz）→ verifier 应实跑并记「通过」**。仅当**确实未被放行**（超时/无回包）时才标「未验证/阻塞」，且**不得**判为实现失败；历史超时取证见 `reports/06-coturn.md` §7.3 |

## 附录 A：环境事实基线（引用，不可改写）

见 `reports/01-host-recon.md`（t1）与 `reports/13-go-toolchain.md`（t13）。关键值：Ubuntu 24.04.2 / 4 vCPU（2 物理核）/ 7.1 GiB / 可用 61 GB / 无 swap / 公网 `47.238.144.66` / SSH `root@172.21.0.219:5766` / 工作区 `/opt/dsh-workspaces`（= 容器 `/data/dsh/home/workspace`，uid 1000）/ Go `go1.22.12` 于 `<WS>/go/bin/go`。

## 附录 B：契约与代码的锚点字符串（供 grep 与日志对齐）

```
包名                com.example.webrtcdemo
native 库           libwebrtcdemo_native / webrtcdemo_native
JNI 类              com/example/webrtcdemo/nativebridge/{NativeLog,NativeVp9Encoder,NativeNatDetector,NativeCallbacks}
编码器实现名        SelfVp9Libvpx
FileProvider        com.example.webrtcdemo.fileprovider
信令路径            /ws
信令默认 URL        ws://47.238.144.66:8443/ws
日志根              <filesDir>/logs/   （app.log / native.log / webrtc.log / encoder_bitrate.csv）
日志格式            <ts> <LEVEL> <layer> <tag> [<pid>/<tid>] <message>[ k=v ...]
roomId 字符集       ABCDEFGHJKMNPQRSTUVWXYZ23456789
NAT 枚举            Open|FullCone|RestrictedCone|PortRestrictedCone|Symmetric|Unknown
状态码             0 OK / 1 NO_OUTPUT / -1 ERROR / -4 ERR_PARAMETER / -7 UNINITIALIZED / -13 FALLBACK_SOFTWARE
日志级别           0 VERBOSE 1 DEBUG 2 INFO 3 WARN 4 ERROR 5 OFF
```

---

*（契约结束。任何新增断言请在 §10 追加编号并在 §12 追加验收项，不要就地修改已冻结的表格值。）*
