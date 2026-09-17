> **Archived 2026-09-17** — superseded by `doc/design/`; archived verbatim as `doc/archive/10-android-ui-design.md`. This copy is history, not current guidance.

# 10 — Android UI 设计与实现

> 本文档定义 Android app 的界面、交互流程和状态管理。
> Agent 按此文档生成 `app/` 目录下全部 Kotlin/Compose 代码。

## 1. 技术栈

| 项目 | 版本/技术 |
|---|---|
| 语言 | Kotlin 1.9.22 |
| UI 框架 | Jetpack Compose (BOM 2024.10.01) |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 34 (Android 14) |
| 架构 | 单 Activity + Compose Navigation |
| 序列化 | kotlinx.serialization 1.6.3 |
| 异步 | Kotlin Coroutines + Flow |
| 相机 | CameraX 1.4.0 |
| WebSocket | okhttp 4.12.0 (WebSocket support) |
| 构建 | Gradle 8.5 + AGP 8.3 |

## 2. 界面清单

共 3 个界面（Screen）：

| # | 界面 | 说明 |
|---|---|---|
| 1 | HomeScreen | 首页：创建会议 / 加入会议 |
| 2 | CallScreen | 通话中：本地视频、远端视频、状态面板 |
| 3 | LoadingScreen | 连接中过渡（非独立界面，叠加在 CallScreen 上） |

## 3. 界面详细设计

### 3.1 HomeScreen — 首页

```
┌─────────────────────────┐
│                         │
│    WebRTC Demo           │
│                         │
│    ┌───────────────┐    │
│    │ 创建会议       │    │  ← Button，点击后 connecting
│    └───────────────┘    │
│                         │
│    ┌───────────────┐    │
│    │ A1B2C3        │    │  ← TextField，输入 6 位房间 ID
│    └───────────────┘    │
│    ┌───────────────┐    │
│    │ 加入会议       │    │  ← Button，点击后 connecting
│    └───────────────┘    │
│                         │
│    NAT 类型: 检测中...   │  ← 本端 NAT 类型显示（异步更新）
│                         │
└─────────────────────────┘
```

**Composable 结构**：
```
HomeScreen
├── Column(center)
│   ├── Text("WebRTC Demo")           // title
│   ├── Button(onClick = onCreate)    // 创建会议
│   ├── Row
│   │   ├── TextField(roomIdInput)     // 房间 ID 输入框
│   │   └── Button(onClick = onJoin)   // 加入会议
│   └── Text(natTypeText)              // NAT 类型
```

**交互逻辑**：
- "创建会议" → ViewModel.onCreate() → 连接 WSS → 发送 `create` → 收到 `created` → 导航到 CallScreen
- "加入会议" → 需输入 6 位 roomId → ViewModel.onJoin(roomId) → 连接 WSS → 发送 `join` → 收到 `joined` → 导航到 CallScreen
- 房间 ID 输入框：大写字母+数字，最多 6 字符，`visualTransformation` 全大写
- NAT 类型：app 启动后在后台异步探测（通过 JNI 调 native 模块），结果更新到 UI

### 3.2 CallScreen — 通话界面

```
┌─────────────────────────────┐
│                             │
│  ┌───────────┐  ┌───────────┐ │
│  │           │  │           │ │
│  │  远端视频  │  │  本地视频  │ │  ← SurfaceView/SurfaceProvider
│  │  (大)     │  │  (小,PIP) │ │
│  │           │  │           │ │
│  └───────────┘  └───────────┘ │
│                             │
│  连接状态: P2P / RELAY       │  ← 连接模式显示
│  传输速率: ↑350kbps ↓800kbps │  ← 实时速率
│  本端NAT: Symmetric         │  ← NAT 类型
│  对端NAT: FullCone          │  ← 对端 NAT 类型
│  编码码率: 500kbps           │  ← 当前编码器目标码率
│                             │
│  ┌─────┐  ┌─────┐  ┌─────┐ │
│  │ 静音 │  │ 挂断 │  │ 摄像 │ │  ← 三个控制按钮
│  └─────┘  └─────┘  └─────┘ │
│                             │
└─────────────────────────────┘
```

**Composable 结构**：
```
CallScreen
├── Box(fillMaxSize)
│   ├── SurfaceView(remoteVideo)       // 远端视频，全屏
│   ├── SurfaceView(localVideo)         // 本地视频，右上角小窗
│   ├── Column(bottomBar)
│   │   ├── StatusPanel                  // 状态信息面板
│   │   │   ├── Text(connectionType)     // P2P / RELAY
│   │   │   ├── Text(bitrateInfo)        // 上下行速率
│   │   │   ├── Text(localNatType)
│   │   │   ├── Text(remoteNatType)
│   │   │   └── Text(encoderBitrate)     // 编码器目标码率
│   │   └── Row
│   │       ├── IconButton(micToggle)    // 静音切换
│   │       ├── IconButton(hangup)        // 挂断
│   │       └── IconButton(cameraToggle) // 摄像头切换
│   └── if (isConnecting)
│       └── LoadingOverlay                // 连接中遮罩
```

**状态面板数据源**：

| 显示项 | 数据来源 | 更新频率 |
|---|---|---|
| 连接状态 | `getStats()` → `candidatePairType` | 每 2 秒 |
| 传输速率 | `getStats()` → `availableBitrate` + `bytesSent/Received` | 每 2 秒 |
| 本端 NAT | `onNatTypeDetected` 回调 | 一次性 |
| 对端 NAT | 信令 `natType` 消息 | 一次性 |
| 编码码率 | `onBitrateChanged` 回调 | 每帧/每秒 |

### 3.3 连接中遮罩（LoadingOverlay）

```
┌─────────────────────────────┐
│                             │
│        ●●●●●●●              │  ← CircularProgressIndicator
│                             │
│      正在连接会议...          │
│      A1B2C3                  │
│                             │
└─────────────────────────────┘
```

显示时机：从导航到 CallScreen 到收到 `peerJoined` / `offer` 之前。

## 4. 导航图

```
NavHost(startDestination = "home") {
    composable("home") { HomeScreen(...) }
    composable("call/{roomId}/{role}") { backStackEntry ->
        val roomId = backStackEntry.arguments?.getString("roomId")
        val role = backStackEntry.arguments?.getString("role") // "host" | "joiner"
        CallScreen(...)
    }
}
```

导航动作：
- `onCreate` 成功 → `navController.navigate("call/$roomId/host")`
- `onJoin` 成功 → `navController.navigate("call/$roomId/joiner")`
- `onHangup` → `navController.popBackStack("home", inclusive=false)`

## 5. ViewModel 设计

### 5.1 HomeViewModel

```kotlin
data class HomeUiState(
    val roomIdInput: String = "",           // 用户输入的房间 ID
    val isConnecting: Boolean = false,      // 正在连接
    val natType: String = "检测中...",       // NAT 类型
    val error: String? = null               // 错误信息
)

class HomeViewModel : ViewModel() {
    val uiState = MutableStateFlow(HomeUiState())

    // 创建会议
    fun onCreate() { ... }

    // 加入会议
    fun onJoin(roomId: String) { ... }

    // NAT 探测结果回调
    fun onNatTypeDetected(type: String) { ... }
}
```

### 5.2 CallViewModel

```kotlin
data class CallUiState(
    val roomId: String = "",
    val role: String = "",                  // "host" | "joiner"
    val connectionState: String = "连接中",  // ICE 状态
    val connectionType: String = "",        // "P2P" | "RELAY" | ""
    val upBitrate: Int = 0,                 // 上行 bps
    val downBitrate: Int = 0,              // 下行 bps
    val localNatType: String = "",
    val remoteNatType: String = "",
    val encoderBitrate: Int = 0,           // 编码器目标码率 bps
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = true,
    val isRemoteVideoReady: Boolean = false,
    val error: String? = null
)

class CallViewModel : ViewModel() {
    val uiState = MutableStateFlow(CallUiState())

    fun initCall(roomId: String, role: String) { ... }  // 调 JNI 创建 PeerConnection
    fun onPeerJoined() { ... }                           // 收到 peerJoined → 发 offer
    fun onOffer(sdp: String) { ... }                     // 收到 offer → 发 answer
    fun onAnswer(sdp: String) { ... }
    fun onIceCandidate(candidate: String, sdpMid: String, mLineIdx: Int) { ... }
    fun onNatType(type: String) { ... }                  // 对端 NAT 类型
    fun toggleMute() { ... }
    fun toggleCamera() { ... }
    fun hangup() { ... }                                 // 调 JNI hangup → 发 leave → 导航回 home
    fun onStatsReport(statsJson: String) { ... }         // 定时解析 getStats
    fun onBitrateChanged(bps: Int) { ... }
    fun onIceConnectionChange(state: String) { ... }
}
```

## 6. 相机与音频采集

### 6.1 相机（CameraX）

```kotlin
// 在 CallScreen 中通过 JNI 将 CameraX 的 Surface 传给 native 层
// native 层用 SurfaceTextureArrayer 或 libwebrtc 的 AndroidVideoTrackSource

// 初始化
val cameraProvider = ProcessCameraProvider.getInstance(context).get()
val preview = Preview.Builder().build()
val videoCapture = VideoCapture.Builder()
    .setTargetRotation(Surface.ROTATION_0)
    .build()

// 绑定
cameraProvider.bindToLifecycle(
    lifecycleOwner,
    CameraSelector.DEFAULT_FRONT_CAMERA,
    preview,
    videoCapture
)

// 将 Surface 传给 JNI
preview.setSurfaceProvider { request ->
    val surface = request.provideSurface()
    nativeSetVideoSurface(surface)  // JNI 调用
}
```

### 6.2 音频

音频采集由 libwebrtc 内部管理，app 只需在 JNI `createPeerConnection` 时传入 `audio=true`。libwebrtc 使用 `android.media.audiorecord.AudioRecord` 采集。

**权限**：AndroidManifest.xml 需声明：
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

**运行时权限请求**：在 HomeScreen 创建/加入前检查并请求 CAMERA + RECORD_AUDIO 权限。

## 7. 视频渲染

使用 libwebrtc 的 `SurfaceViewRenderer`（通过 JNI 桥接到 C++ 层）。

```kotlin
// 在 Compose 中嵌入
AndroidView(
    factory = { context ->
        SurfaceViewRenderer(context).apply {
            init(eglBase.eglBaseContext, rendererCommon)
        }
    },
    modifier = Modifier.fillMaxSize()
)

// 本地预览
SurfaceViewRenderer → native 层 setLocalVideoSink

// 远端渲染
SurfaceViewRenderer → native 层 addRemoteVideoSink
```

## 8. 文件清单

Agent 需生成的文件：

```
app/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   ├── kotlin/com/example/webrtcdemo/
│   │   ├── MainActivity.kt                    // 单 Activity
│   │   ├── ui/
│   │   │   ├── theme/Theme.kt                  // Material3 主题
│   │   │   ├── theme/Color.kt
│   │   │   ├── navigation/AppNavHost.kt       // 导航
│   │   │   ├── home/HomeScreen.kt
│   │   │   ├── home/HomeViewModel.kt
│   │   │   ├── call/CallScreen.kt
│   │   │   ├── call/CallViewModel.kt
│   │   │   └── call/StatusPanel.kt             // 状态面板组件
│   │   ├── signaling/
│   │   │   ├── SignalingClient.kt              // WebSocket 客户端
│   │   │   ├── SignalingMessage.kt             // 消息序列化
│   │   │   └── ConnectionState.kt              // 状态机枚举
│   │   └── webrtc/
│   │       ├── WebRtcManager.kt                // JNI 调用封装
│   │       └── WebRtcCallbacks.kt              // C++ 回调接口
│   └── res/
│       ├── values/strings.xml
│       └── mipmap/                             // app icon
├── src/androidTest/                             // (可选) UI 测试
└── proguard-rules.pro
```

## 9. build.gradle.kts 关键配置

```kotlin
android {
    namespace = "com.example.webrtcdemo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.webrtcdemo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26"
                )
            }
        }
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // CameraX
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // kotlinx.serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
```
