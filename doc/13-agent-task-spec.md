# 13 — dsh Agent 任务规格

> 本文档定义给 DeepSeek Harness 的执行计划。
> 用户输入 prompt："先阅读并理解 doc 目录下的文档，然后制定计划并执行"。
> dsh 应按本文档的顺序和验收标准执行。

## 1. 前置条件检查

在开始任何代码生成前，agent 应检查以下条件：

| 条件 | 检查方式 | 缺失时处理 |
|---|---|---|
| third_party/libwebrtc/ 存在 | `ls third_party/libwebrtc/lib/libwebrtc.a` | 提示用户先执行 doc/08 中的编译流程 |
| third_party/libvpx/ 存在 | `ls third_party/libvpx/lib/libvpx.a` | 提示用户先执行 doc/08 中的编译流程 |
| Android SDK 已安装 | 检查 `ANDROID_HOME` 环境变量 | 提示用户安装 |
| NDK 已安装 | 检查 `ANDROID_NDK_HOME` 或 SDK Manager | 提示用户安装 NDK 26+ |
| CMake 已安装 | 检查 `cmake --version` | 提示用户安装 3.22+ |

**如果 third_party 产物不存在**：agent 应**跳过 native 层编译**，先生成 app 的 Kotlin 层和 signaling 的 Go 层代码（这两层不依赖 .a 文件），最后再处理 native 层。

## 2. 执行阶段与依赖

```
阶段 0: 环境检查
    │
    ├──→ 阶段 1: Git 仓库 + Submodule  ──────────── (无依赖)
    │
    ├──→ 阶段 2: Go 信令服务  ───────────────────── (无依赖)
    │      │
    │      └──→ 阶段 2.1: 部署到云主机  ────────── (依赖 2 编译完成)
    │
    ├──→ 阶段 3: C++ Native 层  ─────────────────── (依赖 third_party 产物)
    │
    ├──→ 阶段 4: Android App (Kotlin)  ──────────── (依赖 3 的 JNI 接口定义)
    │
    └──→ 阶段 5: 集成测试  ──────────────────────── (依赖 2+3+4 全部完成)
```

**可并行**：阶段 1 和 阶段 2 完全独立，可并行。
**不可并行**：阶段 4 依赖 阶段 3 的 JNI 方法签名（`05-code-design.md` 第 4 节已定义，agent 可直接引用）。

## 3. 各阶段详细规格

### 阶段 0: 环境检查

**输入**：无
**输出**：环境检查报告（打印到控制台）
**验收**：所有前置条件满足或已提示用户

### 阶段 1: Git 仓库 + Submodule

**输入文档**：`05-code-design.md` 第 9.0 节
**输出文件**：
- `.git/` 目录初始化完成
- `.gitmodules` 文件
- `third_party/libvpx-src/` 和 `third_party/libwebrtc-src/` submodule
- `doc/` 目录已有文档提交

**执行步骤**：
1. `git init`
2. `git add doc/ && git commit -m "初始化项目文档"`
3. `git submodule add https://chromium.googlesource.com/webm/libvpx third_party/libvpx-src`
4. `git submodule add https://webrtc.googlesource.com/src third_party/libwebrtc-src`
5. `git commit -m "引入 libvpx 和 libwebrtc 源码 submodule"`

**验收**：`git submodule status` 显示两个 submodule

### 阶段 2: Go 信令服务

**输入文档**：`12-backend-implementation.md`、`09-signaling-protocol-spec.md`
**输出目录**：`signaling/`
**输出文件清单**：

| 文件 | 说明 |
|---|---|
| `signaling/go.mod` | Go module 定义 |
| `signaling/main.go` | 入口 |
| `signaling/server/server.go` | HTTP 服务 |
| `signaling/server/ws_handler.go` | WebSocket 消息处理 |
| `signaling/room/manager.go` | 房间管理器 |
| `signaling/room/room.go` | 房间结构 |
| `signaling/room/peer.go` | Peer 结构 |
| `signaling/protocol/message.go` | 消息类型 |
| `signaling/protocol/errors.go` | 错误码 |
| `signaling/config/config.go` | 配置 |
| `signaling/util/roomid.go` | roomId 生成 |

**执行步骤**：
1. 创建 `signaling/` 目录
2. `go mod init webrtcdemo-signaling`
3. 按文档生成所有 Go 文件
4. `go mod tidy`（下载 gorilla/websocket）
5. `go build -o signaling .` 验证编译通过
6. 用 `wscat` 本地测试 create → join → peerJoined 流程

**验收标准**：
- `go build` 无错误
- `wscat` 测试 create 返回 `created` 消息
- `wscat` 测试 join 返回 `joined` 消息
- 第二个连接 join 后第一个连接收到 `peerJoined`

### 阶段 2.1: 部署信令服务到云主机

**输入文档**：`12-backend-implementation.md` 第 12 节、`01-cloud-infra.md`
**执行步骤**：
1. SSH 到云主机
2. 上传 `signaling` 二进制到 `/opt/signaling/`
3. 创建 systemd service 文件
4. `systemctl enable signaling && systemctl start signaling`
5. `systemctl status signaling` 确认运行

**验收**：`curl http://<CLOUD_IP>:8443/ws` 返回 WebSocket upgrade 响应

### 阶段 3: C++ Native 层

**输入文档**：`11-native-implementation.md`、`06-webrtc-dynamic-bitrate-internals.md`
**输出目录**：`app/src/main/cpp/`
**输出文件清单**：

| 文件 | 说明 |
|---|---|
| `cpp/CMakeLists.txt` | CMake 构建 |
| `cpp/jni/jni_bridge.cpp` | JNI_OnLoad |
| `cpp/jni/peer_connection_jni.cpp` | PeerConnection JNI |
| `cpp/jni/native_callbacks.h` | C++ → Java 回调 |
| `cpp/webrtc/peer_connection_manager.h` | PCM 头文件 |
| `cpp/webrtc/peer_connection_manager.cpp` | PCM 实现 |
| `cpp/webrtc/stats_collector.h` | Stats 收集器 |
| `cpp/webrtc/stats_collector.cpp` | Stats 实现 |
| `cpp/webrtc/video_sink_adapter.h` | 视频渲染适配 |
| `cpp/encoder/vp9_encoder_factory.h` | 编码器工厂 |
| `cpp/encoder/vp9_encoder_factory.cpp` | 工厂实现 |
| `cpp/encoder/vp9_encoder.h` | VP9 编码器头 |
| `cpp/encoder/vp9_encoder.cpp` | VP9 编码器实现 |
| `cpp/nat/nat_detector.h` | NAT 探测头 |
| `cpp/nat/nat_detector.cpp` | NAT 探测实现 |
| `cpp/util/jni_util.h` | JNI 辅助 |
| `cpp/util/jni_util.cpp` | JNI 辅助实现 |

**执行步骤**：
1. 创建目录结构
2. 生成 `CMakeLists.txt`
3. 生成 JNI 桥接层
4. 生成 PeerConnectionManager
5. 生成 VP9 编码器（核心，需对照 `06-webrtc-dynamic-bitrate-internals.md`）
6. 生成 NAT 探测模块
7. 生成 Stats 收集器
8. 生成辅助工具

**代码风格约束**：
- 所有自研 C++ 代码**必须包含中文注释**
- 遵循 WebRTC/Chromium 代码风格（见 `05-code-design.md` 第 11 节）

**验收标准**：
- 所有文件已生成
- CMake 配置文件路径正确（指向 `third_party/libwebrtc/` 和 `third_party/libvpx/`）
- 关键方法签名与 `05-code-design.md` 第 4 节 JNI 接口一致

### 阶段 4: Android App (Kotlin)

**输入文档**：`10-android-ui-design.md`、`09-signaling-protocol-spec.md`、`05-code-design.md` 第 4 节
**输出目录**：`app/`
**输出文件清单**：

| 文件 | 说明 |
|---|---|
| `app/build.gradle.kts` | Gradle 构建配置 |
| `app/src/main/AndroidManifest.xml` | 清单文件 |
| `app/src/main/cpp/CMakeLists.txt` | Native 构建配置 |
| `app/src/main/kotlin/.../MainActivity.kt` | 单 Activity |
| `app/src/main/kotlin/.../ui/theme/Theme.kt` | Material3 主题 |
| `app/src/main/kotlin/.../ui/theme/Color.kt` | 颜色定义 |
| `app/src/main/kotlin/.../ui/navigation/AppNavHost.kt` | 导航 |
| `app/src/main/kotlin/.../ui/home/HomeScreen.kt` | 首页 |
| `app/src/main/kotlin/.../ui/home/HomeViewModel.kt` | 首页 VM |
| `app/src/main/kotlin/.../ui/call/CallScreen.kt` | 通话界面 |
| `app/src/main/kotlin/.../ui/call/CallViewModel.kt` | 通话 VM |
| `app/src/main/kotlin/.../ui/call/StatusPanel.kt` | 状态面板 |
| `app/src/main/kotlin/.../signaling/SignalingClient.kt` | WebSocket 客户端 |
| `app/src/main/kotlin/.../signaling/SignalingMessage.kt` | 消息序列化 |
| `app/src/main/kotlin/.../signaling/ConnectionState.kt` | 状态机 |
| `app/src/main/kotlin/.../webrtc/WebRtcManager.kt` | JNI 调用封装 |
| `app/src/main/kotlin/.../webrtc/WebRtcCallbacks.kt` | C++ 回调接口 |
| `app/src/main/res/values/strings.xml` | 字符串资源 |

**执行步骤**：
1. 创建目录结构
2. 生成 `build.gradle.kts`（含 Compose、CameraX、OkHttp 等依赖）
3. 生成 `AndroidManifest.xml`（含权限声明）
4. 生成 Kotlin 各文件
5. Kotlin 代码中 `WebRtcNative` 类的 native 方法签名必须与 `05-code-design.md` 第 4 节一致
6. `SignalingClient` 的消息类型必须与 `09-signaling-protocol-spec.md` 一致

**验收标准**：
- Gradle sync 成功
- Kotlin 代码无编译错误（不含 native 编译）
- JNI 方法签名与 C++ 层一致

### 阶段 5: 集成测试

**前提**：阶段 2-4 全部完成

**测试步骤**：
1. 在云主机上确认 coturn 运行
2. 在云主机上确认 signaling 服务运行
3. 在 Android 设备上安装 app
4. 设备 A 创建会议，记录 roomId
5. 设备 B 输入 roomId 加入会议
6. 验证双方视频互通
7. 验证状态面板显示 NAT 类型、连接模式、传输速率
8. 验证静音/摄像头切换功能
9. 验证挂断后双方回到首页

## 4. 文档交叉引用表

agent 生成代码时应同时参照以下文档：

| 生成目标 | 主要文档 | 辅助文档 |
|---|---|---|
| Go 信令服务 | `12-backend-implementation.md` | `09-signaling-protocol-spec.md`, `01-cloud-infra.md` |
| C++ JNI 层 | `11-native-implementation.md` | `05-code-design.md` §4, `06-webrtc-dynamic-bitrate-internals.md` |
| C++ VP9 编码器 | `11-native-implementation.md` §5 | `06-webrtc-dynamic-bitrate-internals.md`, `04-glossary.md` |
| C++ NAT 探测 | `11-native-implementation.md` §6 | `04-glossary.md` (ICE/STUN 条目) |
| Android UI | `10-android-ui-design.md` | `09-signaling-protocol-spec.md` |
| Android 信令客户端 | `10-android-ui-design.md` §5 | `09-signaling-protocol-spec.md` |
| libwebrtc 编译 | `08-libwebrtc-android-build.md` | `01-cloud-infra.md` |
| coturn 部署 | `01-cloud-infra.md` §5 | — |
| 代码风格 | `05-code-design.md` §11 | `ADR-007` |

## 5. 错误处理策略

agent 执行过程中遇到错误时：

| 错误类型 | 处理方式 |
|---|---|
| Go 编译错误 | 修复代码后重试，最多 3 次 |
| CMake 配置错误 | 检查 third_party 路径，修复后重试 |
| Gradle sync 失败 | 检查 SDK/NDK 版本，修复后重试 |
| submodule clone 失败（网络） | 提示用户检查网络/代理，不阻塞后续 Kotlin/Go 代码生成 |
| SSH 部署失败 | 打印错误，提示用户检查 SSH 连接 |

## 6. 完整文件树（最终状态）

```
webrt-demo/
├── doc/                                    # 全部文档
│   ├── 00-overview.md
│   ├── 01-cloud-infra.md
│   ├── 02-architecture.md
│   ├── 03-implementation-plan.md
│   ├── 04-glossary.md
│   ├── 05-code-design.md
│   ├── 06-webrtc-dynamic-bitrate-internals.md
│   ├── 07-deepseek-harness-guide.md
│   ├── 08-libwebrtc-android-build.md
│   ├── 09-signaling-protocol-spec.md
│   ├── 10-android-ui-design.md
│   ├── 11-native-implementation.md
│   ├── 12-backend-implementation.md
│   ├── 13-agent-task-spec.md               # 本文档
│   └── adr/
│       ├── ADR-001 ~ ADR-007
├── signaling/                              # Go 信令服务
│   ├── go.mod
│   ├── main.go
│   ├── server/
│   ├── room/
│   ├── protocol/
│   ├── config/
│   └── util/
├── app/                                    # Android App
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/example/webrtcdemo/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ui/
│   │   │   ├── signaling/
│   │   │   └── webrtc/
│   │   ├── cpp/                             # C++ Native 层
│   │   │   ├── CMakeLists.txt
│   │   │   ├── jni/
│   │   │   ├── webrtc/
│   │   │   ├── encoder/
│   │   │   ├── nat/
│   │   │   └── util/
│   │   └── res/
│   └── proguard-rules.pro
├── third_party/
│   ├── libwebrtc/                           # 编译产物
│   ├── libvpx/                              # 编译产物
│   ├── libwebrtc-src/                       # submodule 源码
│   └── libvpx-src/                          # submodule 源码
├── scripts/
│   └── build_libwebrtc.sh                  # 编译脚本
├── .gitignore
├── .gitmodules
└── settings.gradle.kts                      # (可选) Gradle 多模块
```
