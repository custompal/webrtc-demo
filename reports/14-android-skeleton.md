# 14 — Android 契约无关骨架预研（Gradle 工程 + 资源 + 日志设施）

> 任务：t14（android-dev） · 依赖：无
> 文档依据：`doc/14-interface-contract.md`（唯一权威，**全文 1209 行已产出**）§2.1 / §3.1 / §4.1 / §6.1 / §6.6 / §7.6 / §8.1 / **§9 全文** / §12
> 交付定位：**t8 的输入骨架**。t8 只做「补业务逻辑」，不需要搬文件、不需要重配 Gradle、不需要重做日志设施。

---

## 0. 执行摘要（TL;DR）

| 项 | 结论 |
|---|---|
| 状态 | **已完成**；本轮按 captain 指示追加「环境目录约定」，并按**新产出的 §9 重做了日志/导出** |
| 产出 | 27 个文件（Gradle/配置 12 + res 7 + Kotlin 8 共 1356 行）+ 本报告 |
| 契约一致性 | 5 组检查器全绿：XML 8/8、Kotlin 静态 11/11、§2–§8 逐条 **68/68**、**§9 日志 56/56**、**§12 验收 19/19** |
| 运行期验证 | **结构性无法进行**：JDK 只装在宿主机、容器内无 `javac`/Gradle → 容器内不能构建 APK（§3.3/§3.4 有证据） |
| 本轮最重要的事 | **`doc/14` §9 在我首版交付后才写出，而它与我的首版日志/导出有 9 处冲突；已按 §9 重做并复验（§4.5）** |

### 契约时序（为什么会有「重做」）

| 时刻 | `doc/14` 状态 | t14 的动作 |
|---|---|---|
| 开工时 | 不存在 | 先按 D2/D3 + 任务书 D6 落地初稿（`util/FileLogger.kt`、`util/LogExporter.kt`） |
| 中途（622→784 行） | §2.1/§3.1/§4.1/§6/§7/§8 已出，**§9 未出** | 按 §2.1 重排为 `log/` + `diag/` + `WebRtcDemoApp.kt`，对齐全部冻结值 |
| 本轮（1209 行，**全文完成**） | **§9 已出** | 按 §9 重做日志格式/目录/文件/滚动/导出/FileProvider，并逐条执行 §12 验收命令 |

---

## 1. 文件清单（27 个，全部新增，未改动任何他人文件）

### 1.1 仓库根 —— Gradle 骨架与配置（12）

| 文件 | 说明 |
|---|---|
| `settings.gradle.kts` | pluginManagement + dependencyResolutionManagement（google / mavenCentral / **flatDir `third_party/libwebrtc/java`**）+ `include(":app")` |
| `build.gradle.kts` | 插件版本声明（`apply false`）：AGP 8.5.2、Kotlin 2.0.21、Compose Compiler 2.0.21、serialization 2.0.21 |
| `gradle.properties` | 堆/UTF-8/并行、配置缓存关闭、`kotlin.compiler.execution.strategy=in-process`，并**写明缓存与临时目录约定**（§3） |
| `gradlew` / `gradlew.bat` | Gradle 官方 v8.7.0 标签脚本（`gradlew` 已 `chmod +x`） |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl=…gradle-8.7-bin.zip`（冻结值） |
| `gradle/wrapper/gradle-wrapper.jar` | 官方 wrapper jar，43453 B，sha256 `cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8`，含 `GradleWrapperMain.class` |
| `local.properties.template` | 仅模板；按 `<WS>/android-sdk`（宿主/容器两种写法）给出示例 |
| `app/.gitignore` | app 模块忽略规则 |

> 仓库根 `.gitignore` 由 t3 创建且 Android/Gradle 段已完整（`.gradle/`、`**/build/`、`local.properties`、`*.apk`、`.cxx/`），t14 **未改动它**以避免与 env-installer 并发编辑冲突；仅新增 `app/.gitignore`。

### 1.2 `app/` 模块（2）

| 文件 | 说明 |
|---|---|
| `app/build.gradle.kts` | 契约 §4.1 逐条落地（对照表见 §5.1） |
| `app/proguard-rules.pro` | §4.1/§6.1 三条 keep（`org.webrtc.**` / `nativebridge.**` / `native <methods>`）+ serialization + OkHttp |

### 1.3 资源 `app/src/main/res/`（7）

| 文件 | 说明 |
|---|---|
| `xml/file_paths.xml` | §9.5 冻结形态：`<files-path name="logs" path="logs/"/>` + `<cache-path name="cache" path="."/>` |
| `values/strings.xml` | `app_name` + 首页/通话页/日志导出文案（24 条） |
| `values/themes.xml` | `Theme.WebRtcDemo`（继承平台 `Theme.Material.Light.NoActionBar`，不引入 material XML 库） |
| `values/colors.xml` | `window_background`、`ic_launcher_background` |
| `drawable/ic_launcher_foreground.xml` | 纯矢量图标前景（无二进制 PNG） |
| `mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml` | 自适应图标（minSdk 26 全覆盖） |

`app/src/main/AndroidManifest.xml` 见 §5.2。

### 1.4 Kotlin `app/src/main/kotlin/com/example/webrtcdemo/`（8 个，1356 行）

| 文件 | 行数 | 职责（对照 §2.1 冻结命名） |
|---|---|---|
| `log/LogLevel.kt` | 62 | 等级枚举，**数值 0..5**（VERBOSE/DEBUG/INFO/WARN/ERROR/OFF），与 native 一致（§6.6 / §12.6 V31） |
| `log/FileLogger.kt` | 506 | §9.1 行格式 + §9.2 目录/文件/滚动 + §9.3 内存队列/256 行/200 ms/单线程写盘 + logcat 双写 |
| `log/Log.kt` | 160 | §9.3 唯一日志门面 `AppLog`；§9.6 等级持久化（`log_cfg` / `level_kotlin`） |
| `WebRtcDemoApp.kt` | 80 | §9.3 日志初始化 + 全局异常落盘；§9.5 清理旧导出；§9.4 `nativeInit` 调用锚点（注释，留给 t8） |
| `diag/LogExporter.kt` | 292 | §9.5 flush → zip → FileProvider → ACTION_SEND；保留最近 3 个 |
| `MainActivity.kt` | 163 | **占位**：主题 + 骨架说明 + 导出日志按钮（t8 替换为 NavHost） |
| `ui/theme/Theme.kt` / `ui/theme/Color.kt` | 59 / 34 | Material3 主题与配色 |

---

## 2. 边界与「契约无关」的界定

### 2.1 判定为契约无关（t14 做）

1. **Gradle 工程骨架**：只依赖版本矩阵与目录约定（D2/D3），不依赖接口形状。
2. **Manifest / 资源 / 主题**：只依赖权限、FileProvider、配色与图标。
3. **日志设施与导出**：输入输出是「字符串 + 文件」，接口面仅 `AppLog.{v,d,i,w,e,flush,setLevel}` 与 `LogExporter.exportZip(context)`。
4. **Application 骨架**：日志初始化与全局异常落盘（§2.1 明确为其职责）。

### 2.2 明确**未做**（边界复查与任务书一致）

| 未做项 | 原因 |
|---|---|
| `nativebridge/{NativeLoader,NativeLog,NativeVp9Encoder,NativeNatDetector,NativeCallbacks}.kt` | 需逐字实现 §6 的 15 个 native 方法 + 2 个回调，属 t8 |
| `WebRtcNative` / `WebRtcCallbacks` / `WebRtcManager` | **契约 §2.1/§6.5 已取消这三者**（见 §9.4） |
| `encoder/*`、`webrtc/*`、`signaling/*`、`nat/*`、`model/*`、`config/*`、`diag/DiagnosticsScreen.kt`、`ui/{navigation,home,call}` | 依赖 `org.webrtc` API / JNI 表 / 信令字段，属 t8 |
| `webrtc/LibwebrtcLoggable.kt`（§9.7） | 需实现 `org.webrtc.Loggable`，依赖 libwebrtc jar，属 t8 |
| `app/src/main/cpp/**` | 属 t7 |

> t14 **没有任何 native 方法签名，也没有任何依赖 JNI/信令类型的 ViewModel 或 UI 代码**：检查器在剥离注释与字符串后扫描 `WebRtcNative / nativebridge / NativeLog / NativeVp9Encoder / NativeNatDetector / NativeCallbacks / org.webrtc / PeerConnectionFactory / SignalingClient / HomeViewModel / CallViewModel / AppNavHost` 等标识符，**0 命中**。

---

## 3. 环境与目录约定（**captain 指定：t10 与 verifier 沿用同一套**）

### 3.1 权威来源（不是我发明的，是 t4 的产物）

| 文件 | 适用 | 关键内容 |
|---|---|---|
| `<WS>/env.sh` | **宿主机** | `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`、`ANDROID_HOME=<WS>/android-sdk`、`ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125`、`GRADLE_USER_HOME=<WS>/.gradle-home`、`TMPDIR=<WS>/tmp`；末尾 source `env-go.sh` |
| `<WS>/env-container.sh` | **容器内**（仅路径对齐） | `HOST_*` 给出宿主机绝对路径；同样导出 `GRADLE_USER_HOME=<WS>/.gradle-home`、`TMPDIR=<WS>/tmp` |

`<WS>` = `/opt/dsh-workspaces`（宿主机）= `/data/dsh/home/workspace`（容器）。

### 3.2 冻结的目录约定（t8 / t10 / verifier 请一律沿用）

```bash
# 推荐入口（不要手写这些变量）
. /opt/dsh-workspaces/env.sh                  # 宿主机构建（唯一能构建 APK 的地方）
. /data/dsh/home/workspace/env-container.sh   # 容器内路径对齐（不能构建）

WORKSPACE_ROOT    = <WS>
JAVA_HOME         = /usr/lib/jvm/java-17-openjdk-amd64     # 仅宿主机
ANDROID_HOME      = <WS>/android-sdk
ANDROID_SDK_ROOT  = <WS>/android-sdk
ANDROID_NDK_HOME  = <WS>/android-sdk/ndk/26.1.10909125
GRADLE_USER_HOME  = <WS>/.gradle-home      # 必须；否则 Gradle 写 ~/.gradle 直接失败
TMPDIR            = <WS>/tmp               # 必须；编译/解压临时文件
GOCACHE           = <WS>/go/cache ; GOTMPDIR = <WS>/go/tmp   # env-go.sh 已设，勿覆盖
```

**仓库内落点**（全部在 `<WS>` 内，无一处写 `$HOME` 或 `/tmp`）：

| 内容 | 路径 |
|---|---|
| Gradle 缓存 + wrapper 发行版 | `<WS>/.gradle-home`（`wrapper/dists` 存 gradle-8.7） |
| 构建产物 | `code/webrtc-demo/app/build/`、根 `.gradle/` |
| native 中间产物 | `code/webrtc-demo/app/.cxx/` |
| 运行期日志（设备侧，与宿主无关） | `/data/user/0/com.example.webrtcdemo/files/logs/` |

### 3.3 陷阱实测证据（captain 所述两点**均复现**）

| 断言 | 我的实测 | 结论 |
|---|---|---|
| `$HOME=/data/dsh/home` 不可写 | `mkdir -p $HOME/.gradle-test` → `Permission denied`；`touch $HOME/.t14probe` → `Permission denied` | **成立**；必须设 `GRADLE_USER_HOME`/`TMPDIR` |
| `/tmp` 下可执行文件被拒 | `/tmp/t14/exec_probe.sh`（已 `chmod +x`）→ `Permission denied` | **成立**；工具/脚本不要落在 `/tmp` 执行 |
| `<WS>` 可写 | `mkdir -p <WS>/.gradle-home`、`<WS>/tmp` → OK | 成立 |

> 我探测时误建了 `<WS>/.tmp`，**已删除**；TMPDIR 一律用 `<WS>/tmp`。

### 3.4 对 captain 消息的两点更正（直接影响 t10 命令）

1. **JDK 不在 `<WS>/jdk`**。`env.sh` 用的是 apt 路径 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`；工作区根目前**没有** `jdk/` 目录。请以 `env.sh` 为准。
2. **容器内无法构建 APK，且是结构性的**（`env-container.sh` 文件头自述）：JDK/SDK/NDK/cmake/ninja **只装在宿主机**，容器 rootfs 只读、无 `javac`/`gradle`。所以：
   - `./gradlew` 必须在**宿主机**执行；
   - 我的「未运行时验证」不是「等 t4 装完就能跑」，而是**容器内永远跑不了**（§7 给出宿主机命令）。

---

## 4. D6 / §9 日志与导出实现（t8 直接沿用）

### 4.5 本轮按 §9 重做的 9 处（首版实现**与 §9 冲突**，已全部纠正）

`doc/14` §9 在首版交付后才写出，逐条比对发现 9 处不符——这是本轮的主要工作量：

| # | 项 | 首版（错误） | §9 冻结值 | 现状 |
|---|---|---|---|---|
| 1 | 行格式 | `2026-09-13 15:31:02.481+08:00 [INFO] [TAG] [threadName] msg` | `2026-09-13T07:29:17.123Z INFO    kotlin signaling [12345/12360] msg k=v` | ✅ 重写 `formatLine` |
| 2 | 时间戳 | 本地时区 `XXX` | **UTC + 毫秒 + 字面 `Z`** | ✅ `OffsetDateTime.now(ZoneOffset.UTC)` |
| 3 | 定宽/layer/pid-tid/k=v | 无 | LEVEL 7 / layer 6 / tag 9 / `[pid/tid]` / k=v 字典序 | ✅ 全部实现 |
| 4 | 日志目录 | `getExternalFilesDir(null)/logs/` | `<filesDir>/logs/` | ✅ 改为 `context.filesDir` |
| 5 | 文件名 | `app-YYYYMMDD.log`（按天切分） | `app.log` + `app.1.log`/`app.2.log` | ✅ 固定基名 + 序号滚动 |
| 6 | 滚动上限 | 5 MiB × 保留 3 备份 | **2 MiB × 保留 3 个（当前+2 历史）** | ✅ |
| 7 | 写盘模型 | 每条日志一个 executor 任务，无批量 | **内存队列，满 256 行或每 200 ms flush** | ✅ 单线程 + `ReentrantLock`/`Condition` 批量写 |
| 8 | logcat tag | 调用方传入的 TAG | Kotlin 固定 **`WebRtcDemo`** | ✅ 固定；模块名另作行内 `tag` 段 |
| 9 | 导出 + file_paths + authority | `cacheDir/log_exports/…-yyyyMMdd-HHmmss.zip`；3 条文件路径；`${applicationId}.fileprovider` | `<filesDir>/logs/export/webrtcdemo-logs-<yyyyMMdd-HHmmssZ>.zip`（含 device-info/session-summary/CSV），保留 3 个；仅 `<files-path logs/>`+`<cache-path ./>`；**字面量** authority | ✅ 全部重写 |

> **教训（已写入 §10 U4）**：契约未写完时先落的骨架，必须在契约补齐后逐条复验——否则 t8 会继承一份「看起来对、实际违约」的骨架。

### 4.1 行格式（§9.1）

```
2026-09-13T07:29:17.123Z INFO    kotlin signaling [12345/12360] ws_open url=ws://47.238.144.66:8443/ws
```

- `ts`：UTC + `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`。
- `LEVEL` 定宽 7、`layer` 定宽 6（`kotlin`）、`tag` 定宽 9 且 ≤24 字符 snake_case（`sanitizeTag` 规范化 + 截断）。
- `[pid/tid]`：`Process.myPid()` + **调用线程** `Process.myTid()`（§9.1 示例中不同线程 tid 不同，不可固定）。
- `key=value`：`fields.toSortedMap()` 字典序；值内空格转 `_`。
- 异常：堆栈缩进追加在消息后（满足 §9.3 崩溃落盘）。

### 4.2 目录、文件与滚动（§9.2）

| 项 | 值 |
|---|---|
| 日志根 | `<filesDir>/logs/`（设备 `/data/user/0/com.example.webrtcdemo/files/logs/`），Kotlin `mkdirs()`，与 C++/webrtc 共用 |
| Kotlin 文件 | `app.log`（+ `app.1.log`、`app.2.log`） |
| 单文件上限 / 保留 | **2 MiB / 3 个**，超限删最旧 |
| logcat | tag 固定 `WebRtcDemo`（终端保留） |
| 编码 | UTF-8、`\n`、**每条日志一次 `write()`** |

### 4.3 写盘模型（§9.3）

- 业务线程只做「等级过滤 → 格式化 → 入内存队列」，持锁极短，**不阻塞 UI / WebRTC 回调线程**。
- 独立单线程 daemon（`webrtcdemo-log-writer`）：**满 256 行**或**每 200 ms** 唤醒，批量写盘 + flush。
- `flush()` 主动唤醒 writer 并等待队列排空（带超时），供导出前调用。
- 等级：release `INFO` / debug `DEBUG`（§9.6），持久化 `log_cfg:level_kotlin`；数值 0..5 与 native 一致。

### 4.4 导出与 FileProvider（§9.5）

| 项 | 值 |
|---|---|
| authority | `com.example.webrtcdemo.fileprovider`（Manifest 与 `LogExporter.AUTHORITY` 同一字面量） |
| 导出目录 | `<filesDir>/logs/export/` |
| zip 命名 | `webrtcdemo-logs-<yyyyMMdd-HHmmssZ>.zip`（UTC） |
| 内容 | `app*.log`、`native*.log`、`webrtc*.log`、`encoder_bitrate.csv`、`device-info.txt`（型号/SDK/ABI/`BuildConfig.SIGNALING_URL`/日志配置）、`session-summary.txt` |
| 保留 | 最近 3 个；`WebRtcDemoApp.onCreate` 调 `LogExporter.cleanupOldExports()` |
| 流程 | `FileLogger.flush()` → 打包 → `FileProvider.getUriForFile(ctx, AUTHORITY, zip)` → `ACTION_SEND`(application/zip + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION` + `ClipData`) → `createChooser` |
| 无日志 | `Result.NoLogs` → UI 提示「暂无可导出的日志」，**不崩溃** |
| 失败 | `Result.Failure(message, cause)`，删除半成品 zip |
| **留给 t8 的挂点** | `session-summary.txt` 需要 roomId/角色/ICE/`encoderImplementation`（属 t8 领域模型），以 `LogExporter.sessionSummaryProvider: (() -> String)?` 注入，本文件**不引用**契约类型；导出前 t8 还需补 `NativeLog.nativeFlush()` |

---

## 5. 冻结值落地对照

### 5.1 `app/build.gradle.kts` ↔ §4.1

| §4.1 冻结项 | 落地 |
|---|---|
| 4 个插件 | ✅ 逐字一致 |
| `namespace`/`applicationId` = `com.example.webrtcdemo` | ✅ |
| `compileSdk 34` / `minSdk 26` / `targetSdk 34` / `versionCode 1` / `versionName "1.0"` | ✅ |
| `ndkVersion = "26.1.10909125"` | ✅（宿主机实装即此版本） |
| `abiFilters += "arm64-v8a"` | ✅ |
| `-DANDROID_STL=c++_shared`、`-DANDROID_PLATFORM=android-26`、`-DWEBC_THIRD_PARTY=${rootProject.projectDir}/third_party` | ✅ 三条俱全 |
| `buildConfigField SIGNALING_URL` = `ws://47.238.144.66:8443/ws` | ✅ |
| `buildConfigField LIBCAMERA_FACING` = `"front"` | ✅ |
| `buildFeatures { compose; buildConfig }` | ✅ |
| `externalNativeBuild.cmake.path`=`src/main/cpp/CMakeLists.txt`、`version="3.22.1"` | ✅ |
| `packaging { jniLibs { useLegacyPackaging = false } }` | ✅ |
| `compileOptions` 17 / `kotlinOptions.jvmTarget "17"` | ✅ |
| `sourceSets["main"].java.srcDirs("src/main/kotlin")` | ✅（并保留 `src/main/java`，对应 §2 的 org.webrtc 目录） |
| debug `LOG_DEFAULT_DEBUG=true`；release minify+shrink+`LOG_DEFAULT_DEBUG=false` | ✅ |
| 依赖（BOM 2024.10.01 / activity 1.9.2 / navigation 2.7.7 / lifecycle 2.8.6 / core-ktx 1.13.1 / okhttp 4.12.0 / coroutines 1.8.1 / serialization 1.7.3） | ✅ |
| libwebrtc jar：`rootProject.file("third_party/libwebrtc/java/libwebrtc-java.jar")` | ✅（与**已修正**的契约一致，见 §9.2） |
| ProGuard 三条 | ✅ |
| 不使用 `androidx.camera.*` | ✅（**已删除注释里的 CameraX 字样**，使 §12.2 V12 的 grep 为 0 命中） |

**附加项（超出契约原文，均在文件内注释，可随时删除）**：

| 附加项 | 理由 |
|---|---|
| `packaging.jniLibs.pickFirsts += "**/libc++_shared.so"` | 若 AAR 亦自带该 so，避免重复打包直接失败；t10 验 APK 后可删 |
| `packaging.resources.excludes AL2.0/LGPL2.1` | AndroidX 常见重复 META-INF |
| `material-icons-extended`、`ui-tooling-preview`、`debugImplementation ui-tooling`、`junit 4.13.2` | 通话页图标 / Compose 预览 / 单测（BOM 统一版本） |
| `kotlin.compiler.execution.strategy=in-process` | 7.1 GiB 无 swap，少起一个 JVM；可用 `-P...=daemon` 覆盖 |
| `-PwebrtcDemo.skipNative=true` | **默认关闭**；t7 的 `cpp/CMakeLists.txt` 未就绪时用于单独验证 Kotlin 层可编译。⚠️ 属性名**大小写敏感且必须为小写 w**，写错不报错、只静默失效（详见 `reports/08-android-dev.md` §8.1） |
| Manifest 额外 `MODIFY_AUDIO_SETTINGS`、`glEsVersion 2.0`、`roundIcon`、`android:name=".WebRtcDemoApp"` | WebRTC 音频路由 / `SurfaceViewRenderer` EGL / 圆形图标 / §2.1 的 Application 文件 |

### 5.2 `AndroidManifest.xml` ↔ §7.6 + §9.5

CAMERA / RECORD_AUDIO / INTERNET / ACCESS_NETWORK_STATE ✅；`camera required=true` ✅；`usesCleartextTraffic=true` ✅；`allowBackup=false` ✅；`label=@string/app_name` ✅；`.MainActivity` `exported=true` + `screenOrientation=portrait` ✅；`android:name=".WebRtcDemoApp"` ✅；FileProvider（**字面量 authority**、`exported=false`、`grantUriPermissions=true`、`@xml/file_paths`）✅。

---

## 6. 验证证据（可复现；脚本在 `/tmp/t14/`，不入库）

| # | 检查器 | 覆盖 | 结果 |
|---|---|---|---|
| 1 | `xmlcheck.js` | 自研 XML 良构性（注释/CDATA/元素/属性/实体/非法 `--`） | **8/8**；用 3 个故意损坏样本**负测试**确认会报错 |
| 2 | `ktsanity.js` | 括号/引号平衡 + 剥离注释与字符串后扫契约耦合标识符 + `R.string` 存在性 + import 形状 | **11/11**，耦合标识符 0 命中（含负测试） |
| 3 | `contractcheck.js` | §2.1/§3.1/§4.1/§6.1/§6.6/§7.6/§8.1 逐条 | **68/68** |
| 4 | `sect9check.js` | **§9.1–§9.6 逐条** | **56/56** |
| 5 | `v12.sh` | **§12 验收命令逐字执行**（t14 相关 19 项） | **19/19** |

`v12.sh` 摘要：V40 ✅（`android.util.Log` 越界 0）、V41 ✅（1，**命令需补 `-r`**，见 §9.1）、V42a ✅（2）/V42b ✅、V43 ✅（3）、V44 ✅（9）、V46 ✅（2）、V31 ✅（2）、V32 ✅（5）、V05 ✅、V06 ✅、V07 ✅、V08a/V08b ✅、V10 ✅、V12 ✅（0）、V25 ✅（0）、V02 ✅（0）、V20 ✅。
**V45 = INFO**：`setInjectableLogger/Loggable` 命中 1（仅注释）；§9.7 属 t8，t14 边界内不实现。

环境事实核验：宿主机 SDK 的 `ndk/26.1.10909125`、`cmake/3.22.1`、`platforms/android-34`、`build-tools/34.0.0` 与我的冻结值**完全一致**。

---

## 7. 未运行时验证（**如实标注，未伪造**）

| 项 | 状态 |
|---|---|
| `./gradlew ...`（任何 Gradle 任务） | ❌ **未执行**。容器无 JDK（`java`/`javac` 不存在、`/usr/lib/jvm` 不存在），且 `env-container.sh` 明确 JDK/SDK 只在宿主机、容器 rootfs 只读 → **容器内结构性无法构建** |
| Kotlin 编译 / 依赖解析 / 资源合并 | ❌ 未验证；Kotlin 代码**仅通过静态检查**，**不保证零编译错误** |
| APK 内 `.so` 打包、配置缓存 | ❌ 未验证 |
| `app/src/main/cpp/CMakeLists.txt` | ❌ 不存在（t7 负责）；故 `:app:assembleDebug` 目前必然在 CMake 阶段失败 |

**在宿主机应执行的第一条命令**（t7 的 CMakeLists 到位后去掉 `-PwebrtcDemo.skipNative`）：

```bash
ssh -i /home/node/.ssh/id_ed25519 root@172.21.0.219 -p 5766
. /opt/dsh-workspaces/env.sh
cd /opt/dsh-workspaces/code/webrtc-demo
./gradlew :app:compileDebugKotlin -PwebrtcDemo.skipNative=true   # 先验 Kotlin 层
./gradlew :app:assembleDebug                                      # 再全量（含 native）
```

> **注（t8 补记，2026-09-13）**：上面这条命令在 **t14 阶段成立**（当时骨架无 `org.webrtc` 依赖）。
> t8 落地后有 **13 个 Kotlin 文件 `import org.webrtc.*`**，因此 `:app:compileDebugKotlin` **必须等 t5 的
> `third_party/libwebrtc/java/libwebrtc-java.jar` 到位**才能跑；`-PwebrtcDemo.skipNative=true` 只跳过 CMake，
> **不解除** jar 依赖。t10 的第一阶段命令与前置条件见 `reports/08-android-dev.md` §8。

---

## 8. 留给 t8 的 TODO（分级）

### P0 —— 必须

1. **严格按 §9 使用日志设施**（不要另起炉灶）：统一入口 `AppLog.{v,d,i,w,e}(tag, message, fields)`；`tag` 取 §9.1 白名单（`main/ui/signaling/pc/ice/stats/encoder/nat/room/export`）；**事件名复用 §9.1 固定表**（`ws_open`/`room_created`/`setrates`/`ts_target_kbps`/`export_zip`…）。业务代码**禁止**直接调用 `android.util.Log`（V40 会 grep）。
2. `WebRtcDemoApp.onCreate` 补 **§9.4 的 `NativeLog.nativeInit(logDir, "native", level, maxBytesPerFile = 2*1024*1024, maxFiles = 3)`**（本文件已留注释锚点；必须在任何编码器/NAT 调用之前）。
3. 实现 §9.7 的 `webrtc/LibwebrtcLoggable.kt`：`org.webrtc.Loggable` → 写 `webrtc.log`、layer=`webrtc`、logcat 前缀 `webrtc|<level>|`、200 条/秒限流、`PeerConnectionFactory.initialize` 先于一切且只调一次。
4. 等级切换三件套：`AppLog.setLevel(context, level)` + `NativeLog.nativeSetLevel(level.code)` + 写 `log_cfg:level_native`；webrtc 层 UI 提示「需重启生效」（§9.6）。
5. 导出补 `NativeLog.nativeFlush()`，并设置 `LogExporter.sessionSummaryProvider`（roomId/角色/ICE/`encoderImplementation`）。
6. 按 §2.1 补齐其余 Kotlin 文件；把 `MainActivity.kt` 占位替换为 `NavHost("home" / "call/{roomId}/{role}")`；导出入口挂到首页 + 通话页（`diag/DiagnosticsScreen.kt`）。
7. 运行时权限：进入首页请求 CAMERA + RECORD_AUDIO，被拒禁用「创建/加入」（§7.6）。

### P1 —— 联调

8. libwebrtc SDK 就位（t5）后确认 `third_party/libwebrtc/java/libwebrtc-java.jar` 可解析；`.so` 由 t10 放入 `app/src/main/jniLibs/arm64-v8a/`。
9. `strings.xml` 文案按 doc/10 §3 补齐；删除占位屏里的硬编码串。
10. `device-info.txt` 的 `libwebrtc_head` 现为 `unknown`，t5 回填 HEAD SHA 后可改为 `BuildConfig` 字段。

### P2 —— 收尾

11. release 首次 `assembleRelease` 确认 R8 未裁剪 `org.webrtc.**` 与 `nativebridge.**`。
12. 视结论决定是否保留 §5.1 的附加项。

---

## 9. 契约缺陷与观察（供 captain / architect / verifier）

### 9.1 【已闭环】§12.2 V41 的命令曾缺 -r（architect 已修为 grep -rn，verifier 复核通过）

契约原文：`grep -n "nativeInit.*maxBytes" app/src/main/kotlin` —— `app/src/main/kotlin` 是**目录**，`grep -n` 会输出 `grep: app/src/main/kotlin: Is a directory` 并返回 0 行，**无论实现多正确都判失败**。

- 建议改为 `grep -rn "nativeInit.*maxBytes" app/src/main/kotlin`。
- 按 `-rn` 实测：命中 1（`WebRtcDemoApp.kt` 的调用锚点；t8 落地真实调用后同样命中）。
- 同类风险：V31/V43/V02/V12/V25 用的是 `-rn`（正确）。**建议 verifier 对 §12 全表先做一次「命令可执行性」预检**，避免把「命令写错」判成「实现失败」。

### 9.2 【已修复】§4.1 的 `../../third_party` 路径笔误

我上一轮报告提出后，architect **已把契约改为** `implementation(files(rootProject.file("third_party/libwebrtc/java/libwebrtc-java.jar")))`，并加了「笔误修正（2026-09-13）」注记。✅ 无需再跟。

### 9.3 【已消解】§2.1「写 app.log」 vs D6「前缀 app- + 按日期切分」

§9.2 已明确 `<filesDir>/logs/app.log`（固定基名 + 序号滚动），**不再按日期切分**。✅ 已按 §9.2 实现，§2.1 的措辞只是简称。

### 9.4 【需同步给 t8】`WebRtcNative` 已被契约取消

§2.1 / §6.1 / §10（C07/C08）已把 `webrtc/WebRtcManager.kt`、`webrtc/WebRtcCallbacks.kt`、`WebRtcNative` 更名为 `webrtc/WebRtcEngine.kt`、`nativebridge/NativeCallbacks.kt`；JNI 面收窄为 4 类 15 方法 + 2 回调；§6.5 明确**取消** `onIceCandidate` / `onIceConnectionChange` / `onStatsReport` / `onBitrateChanged` / `onRemoteVideoFrameReady` 等旧回调（改由 Kotlin 侧 `org.webrtc` Observer / `RTCStatsCollector` 获得）。

→ **t8 任务书里「`WebRtcNative` 的 native 方法签名必须与契约逐一对应」已过时**，应改为 `nativebridge/*`，否则 t8 会照旧稿写错。

### 9.5 【观察】§9.2 的「总计 ≤ 16 MiB」与各层上限不自洽

按 §9.2：`app`(2×3) + `native`(2×3) + `webrtc`(2×3) + CSV + `export`(3 个 zip，每个含上述内容)，理论上限明显超过 16 MiB。t14 只对 Kotlin 层做 2 MiB×3；**全局 16 MiB 需 t7/t8 共同遵守**，建议在 §9 注明该值是「常规运行值」而非硬上限。

### 9.6 【已同步】环境目录约定

见 §3：`GRADLE_USER_HOME=<WS>/.gradle-home`、`TMPDIR=<WS>/tmp`；**构建只能在宿主机做**（容器无 JDK）。

---

## 10. 未决问题（需 captain/architect 回应）

| # | 问题 | 影响 |
|---|---|---|
| U1 | §12.2 V41 命令缺 `-r`，是否统一修正为 `-rn`（并请 verifier 预检 §12 全表命令可执行性）？ | t11 误判 |
| U2 | t8 任务书是否按 §9.4 更新措辞（`WebRtcNative` → `nativebridge/*`）？ | t8 开工依据 |
| U3 | §9.2「总计 ≤16 MiB」是否改注为「常规运行值」（§9.5）？ | t7/t8 日志策略 |
| U4 | **骨架的编译风险**：容器内无法验证 Kotlin 编译，建议由 t10 在宿主机跑一次 `./gradlew :app:compileDebugKotlin -PwebrtcDemo.skipNative=true`，作为 t14 骨架的编译级验收证据（我目前只能给静态证据）。 | t14→t8 的交接置信度 |
