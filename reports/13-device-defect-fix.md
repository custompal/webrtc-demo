# reports/13-device-defect-fix.md — 真机两缺陷修复（t39）

- **任务**：t39（implementation round 1）—— 修复 2026-09-14 真机反馈的两个缺陷：①本地预览逆时针旋转 90°；②通话页看不到会议号（他人无法加入）。
- **执行者**：android-dev（attempt 2，attempt_id `6322cbbc-a371-42b3-bed7-ae5b95791b8c`）。
- **范围**：仅 Kotlin/资源（`app/src/main/kotlin/**`、`app/src/main/res/values/strings.xml`）与本报告；**未触碰** `doc/`、`third_party/`、`scripts/`、`app/src/main/cpp/`、任何 `*.jar/*.aar/*.so/*.apk`、gradle 配置。
- **环境**：容器内**无 JDK / Android SDK** ⇒ 本轮**不做编译**（`:app:testDebugUnitTest` / `assembleDebug` 由 t41 在宿主机执行）；本轮全部核验为**静态**（grep / 源码对照 / diff）。

---

## 0. 结论摘要

| # | 缺陷 | 根因（一行） | 修复 |
|---|---|---|---|
| ① | 本地预览逆时针旋转 90° | `FrameNormalizer` 在 texture→I420 分支把 `VideoFrame` 的第二实参（rotation）**写死为 0**，而 `toI420()` 只做 YUV 转换、**不旋转** ⇒ 渲染器读 `getRotation()` 得到 0，朝向丢失 | `FrameNormalizer.kt:82` 改为 `VideoFrame(i420, frame.rotation, frame.timestampNs)`；并更正错误注释 |
| ② | 通话页看不到会议号 | `CallScreen` 的会议号 `Text` **只存在于 `if (state.isConnecting)` 遮罩内** ⇒ 连接完成后（或被远端画面覆盖）房间号消失，房主无法告知第二台设备 | 新增**顶部常驻覆盖层**（`Alignment.TopStart` + 半透明底），含复制按钮与 Toast 反馈、`isNotBlank()` 空值守卫、3 条字符串资源 |

**上游证据一句话**：`TextureBufferImpl.toI420()` 无旋转（`TextureBufferImpl.java:111-113`，该文件 `rotation` 命中数 = 0），朝向由渲染器 `VideoFrameDrawer.java:204` 的 `renderMatrix.preRotate(frame.getRotation())` 与编码器直取元数据共同决定 ⇒ **rotation 元数据必须保留**。

---

## 1. 缺陷①：本地预览逆时针 90°

### 1.1 现象
创建会议后可看到摄像头预览，但画面**逆时针旋转 90°**。

### 1.2 根因链（含上游 file:line 证据，均已独立复核）

**证据 1 —— `toI420()` 不做任何旋转**（任务给出的引用**属实**）：

```
文件：third_party/libwebrtc/include/sdk/android/api/org/webrtc/TextureBufferImpl.java
:111  public VideoFrame.I420Buffer toI420() {
:112    return ThreadUtils.invokeAtFrontUninterruptibly(
:113        toI420Handler, () -> yuvConverter.convert(this));
:114  }
```
`grep -c rotation third_party/libwebrtc/include/sdk/android/api/org/webrtc/TextureBufferImpl.java` → **0**
（源码树副本 `third_party/libwebrtc-src/sdk/android/api/org/webrtc/TextureBufferImpl.java` 同为 `:111-113`，同样 0 命中 ⇒ 两份一致。）

**证据 2 —— 朝向由渲染器读 `VideoFrame.getRotation()` 元数据决定**（引用**属实**）：

```
文件：third_party/libwebrtc/include/sdk/android/api/org/webrtc/VideoFrameDrawer.java
:199    renderMatrix.reset();
:200    renderMatrix.preTranslate(0.5f, 0.5f);
:201    if (!isTextureFrame) {
:202      renderMatrix.preScale(1f, -1f); // I420-frames are upside down
:203    }
:204    renderMatrix.preRotate(frame.getRotation());      ← 朝向来源
:205    renderMatrix.preTranslate(-0.5f, -0.5f);
```
该 `drawFrame` 链路对 **I420 与 texture 帧都生效**（`EglRenderer` → `VideoFrameDrawer`），即预览与远端渲染同源。

**证据 3 —— 编码器同样直取元数据**（本工程内 + 契约）：

- `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:208` → `normalizeRotation(frame.rotation)`（作为 `nativeEncode` 的 `rotationDegrees` 实参）；
- `doc/14-interface-contract.md:635`：「**rotation**：`0|90|180|270`（度）；`VideoFrame.getRotation()` **直接映射**，非法值按 0 处理并记 WARN」；
- `doc/14-interface-contract.md:518`：「`g_w` / `g_h` | InitEncode 传入（对齐到偶数；**rotation 90/270 时交换**）」；
- `doc/14-interface-contract.md:489`：编码器契约第 3 步把 `rotationDegrees(frame.rotation)` 传给 native。

**hence**：`FrameNormalizer.kt` 原先构造 `VideoFrame(i420, 0, frame.timestampNs)` ⇒
① 预览渲染矩阵 `preRotate(0)`（朝向丢失，真机表现为逆时针 90° 偏差）；
② 编码器收到 `rotationDegrees = 0` ⇒ 90/270 的宽高交换信号丢失（与 doc/14:518 要求冲突）。

> **与任务给定判断的关系**：任务给定的三条引用**逐条复核属实**，根因判断与之一致；无分歧。

### 1.3 最小修复（`app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt`）

```diff
-        val converted = VideoFrame(i420, 0, frame.timestampNs)
+        // 【t39 修复】rotation 必须保留：`toI420()` 不做旋转（TextureBufferImpl.java:111-113），
+        // 朝向由渲染器/编码器读取 VideoFrame.getRotation() 元数据决定（VideoFrameDrawer.java:204）。
+        val converted = VideoFrame(i420, frame.rotation, frame.timestampNs)
```
同时更正 KDoc 中「rotation 已烘进 I420，故置 0」的错误前提，并把上游 file:line 证据写进注释（便于后人不再回退）。
**I420 直通分支（原 `:39-54`）语义不变**：仍是零拷贝透传、不做任何 release，rotation 元数据随原帧保留。

### 1.4 `rotation` 全仓排查（`grep -rn 'rotation' app/src/main/kotlin`，逐条结论）

| # | 位置 | 内容 | 结论 |
|---|---|---|---|
| 1 | `webrtc/FrameNormalizer.kt:32-33`（修复后行号） | KDoc「rotation 取 `frame.rotation` 原样保留」 | **已修正**（原为"已烘进 I420，故置 0"的错误前提） |
| 2 | `webrtc/FrameNormalizer.kt:43` | KDoc 引用 `normalizeRotation(frame.rotation)` | 保留（说明性注释） |
| 3 | `webrtc/FrameNormalizer.kt:63` | I420 分支日志 `"rot" to frame.rotation.toString()` | **保留**（日志字段名 `rot` 不改，doc/14 §9 冻结） |
| 4 | `webrtc/FrameNormalizer.kt:82` | `VideoFrame(i420, frame.rotation, …)` | **本次修复点** |
| 5 | `webrtc/FrameNormalizer.kt:99` | texture 分支日志 `"rot" to frame.rotation.toString()` | **保留**（同上） |
| 6 | `webrtc/VideoRendererPool.kt:69` | `onFrameResolutionChanged(..., rotation: Int)` 覆写 | 无关（仅接收 `RendererEvents` 回调，**未做任何旋转**） |
| 7 | `webrtc/VideoRendererPool.kt:73` | 日志 `"rot" to rotation.toString()` | 保留（日志字段） |
| 8 | `nativebridge/NativeVp9Encoder.kt:63` | KDoc 形参说明 `rotationDegrees` | 无关（注释；与契约签名一致） |
| 9 | `nativebridge/NativeVp9Encoder.kt:80` | `rotationDegrees: Int` 形参 | 保留（契约 §6 冻结签名） |
| 10 | `encoder/Vp9VideoEncoder.kt:208` | `normalizeRotation(frame.rotation)` | **保留**（正确用法：直取元数据） |
| 11 | `encoder/Vp9VideoEncoder.kt:408-409` | `normalizeRotation()`：非法值→0 | 保留（doc/14:635「非法值按 0 处理」；仅规范化非法值，**非**无条件置 0） |

**结论**：`app/src/main/kotlin` 内**不再有任何位置把 rotation 强制置 0**；`VideoFrame(` 构造点全仓**仅 1 处**（`FrameNormalizer.kt:82`），已修正；渲染器侧**无二次旋转**（`VideoRendererPool` 只 `setMirror(mirror)` 与日志，无 `preRotate`/`setRotation`）。

### 1.5 测试影响
`grep -rn 'rotation' app/src/test` → **无命中**；`grep -rln 'FrameNormalizer\|VideoFrame' app/src/test` → **无命中** ⇒ **不存在**「断言归一化后 rotation == 0」的用例，**无需修订测试**（容器内亦无 JDK 可执行）。实际执行由 t41 在宿主机跑 `:app:testDebugUnitTest` 覆盖（预期仍 46/0/0）。

---

## 2. 缺陷②：通话页看不到会议号

### 2.1 现象
创建会议后，房主看不到/看不到完整会议号，**无法把 6 位房间号告知第二台设备** ⇒ 对端无法 join。

### 2.2 根因
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt` 修复前，会议号**仅**出现在「连接中遮罩」内（修复前 `:245-262`）：

```kotlin
// 连接中遮罩（doc/10 §3.3）
if (state.isConnecting) {
    Column(...) {
        CircularProgressIndicator(...)
        Text(text = stringResource(R.string.call_connecting), ...)
        Text(text = roomId, ...)     // ← 唯一一处显示会议号，且被 isConnecting 包裹
    }
}
```
`isConnecting` 一旦变 `false`（连接完成），该遮罩整体不再参与组合 ⇒ **房间号从界面消失**。

### 2.3 最小修复（`CallScreen.kt`）

1. **顶部常驻覆盖层**（新增，位于根 `Box` 内、远端/本地渲染器之后）：
   `Modifier.align(Alignment.TopStart)` + `padding(12.dp)` + `background(Color(0x99000000), MaterialTheme.shapes.small)`（半透明底，压在视频之上仍可读）+ `clickable { 复制 }`；
   内容 = `Text(stringResource(R.string.call_room_code, roomId))` + `IconButton(Icons.Filled.ContentCopy)`。
   几何上与本地小窗（`TopEnd`，110×150dp）不重叠；与居中连接遮罩亦不重叠 ⇒ **远端画面与连接遮罩同时存在时都可见**。
2. **一键复制 + 可见反馈**：`copyRoomId` 闭包使用**框架 `ClipboardManager`**：
   ```kotlin
   val copyRoomId: () -> Unit = {
       val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
       clipboard.setPrimaryClip(ClipData.newPlainText(roomId, roomId))
       Toast.makeText(context, context.getString(R.string.call_room_code_copied), Toast.LENGTH_SHORT).show()
   }
   ```
   **为何不用 `LocalClipboardManager`**：该 Compose API 在不同 BOM 版本存在弃用差异（本工程 Compose BOM 2024.10.01 / Kotlin 2.0.21），而框架 `ClipboardManager` 是平台稳定 API（`CLIPBOARD_SERVICE` 为核心系统服务，自 API 1 恒存在）⇒ 在**无法本地编译**的前提下取**最稳妥**写法；不引入任何新依赖。`Toast` 为平台类，同样零依赖风险。
3. **空值守卫**：`if (roomId.isNotBlank()) { ... }` ⇒ `roomId` 为空/空白时**不渲染**，不会出现「会议号：」空壳。
4. **不改变对外契约**：未改 `CallUiState` / `CallViewModel`（`roomId` 字段与语义不变，`initCall(roomId, role)` 调用不变），导航路由 `call/{roomId}/{role}` 不变；新增 UI 使用的是屏幕入参 `roomId`（导航参数），与 `CallUiState.roomId` 同源。

### 2.4 字符串资源（`app/src/main/res/values/strings.xml`，新增**三项**）

```xml
<!-- ==================== 通话页：会议号常驻与复制（t39 真机缺陷②） ==================== -->
<!-- 顶部常驻覆盖层文案：显示当前会议号（%1$s = 房间号） -->
<string name="call_room_code">会议号：%1$s</string>
<!-- 复制成功后的可见反馈（Toast） -->
<string name="call_room_code_copied">会议号已复制</string>
<!-- 复制按钮的无障碍描述 -->
<string name="call_room_code_copy_desc">复制会议号</string>
```
仓库只有 `values/`（无 `values-en`），文案与既有中文风格一致（对照现有 `log_export_success` 使用 `%1$d` 的格式化写法）。

---

## 3. 逐文件 diff 摘要

```
$ git diff --numstat
46      0       app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt
21      4       app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt
8       0       app/src/main/res/values/strings.xml
```

| 文件 | 变更 | 要点 |
|---|---|---|
| `webrtc/FrameNormalizer.kt` | +21 / −4 | 修复点 `:82`（rotation 保留）；KDoc 更正错误前提并写入上游 file:line 证据 |
| `ui/call/CallScreen.kt` | +46 / 0 | 新增 import（`ClipData`/`ClipboardManager`/`Context`/`Toast`/`clickable`/`ContentCopy`）；`copyRoomId` 闭包；顶部常驻覆盖层（含 `isNotBlank()` 守卫） |
| `res/values/strings.xml` | +8 / 0 | 新增 3 条字符串（`call_room_code` / `call_room_code_copied` / `call_room_code_copy_desc`） |

**未触碰**（`git status --porcelain` 仅 3 项，见 §5）：`doc/`、`third_party/`、`scripts/`、`app/src/main/cpp/`、任何 `*.jar/*.aar/*.so/*.apk`、gradle 配置（`*.gradle.kts`、`gradle.properties`）、`app/src/test/`。

---

## 4. doc/14 勘误：第 692 行（§7.4 步骤 2）与上游源码矛盾

### 4.1 冻结件现值（**未改动**，仅引用）
```
doc/14-interface-contract.md:692
2. 否则 `val i420 = frame.buffer.toI420()`（**在采集线程执行**…）；构造
   `VideoFrame(i420, 0 /*rotation 已烘进 I420*/, frame.timestampNs)`（所有权转移给 `out`）；
```

### 4.2 矛盾点
该行的前提「**rotation 已烘进 I420**」**被上游源码证伪**：
- `TextureBufferImpl.java:111-113` 的 `toI420()` 只调用 `yuvConverter.convert(this)`，**不做任何旋转**（该文件 `rotation` 命中数 = 0）；
- 朝向的唯一来源是 `VideoFrame.rotation` 元数据（渲染端 `VideoFrameDrawer.java:204`；编码端本工程 `Vp9VideoEncoder.kt:208`）；
- 同文件 `:518`（90/270 交换编码尺寸）与 `:635`（`getRotation()` 直接映射）**要求** rotation 真实传递 ⇒ 与 `:692` 置 0 **自相矛盾**。

### 4.3 可直接并入 `reports/99` 的勘误文本（建议原样粘贴）

> **勘误（t39，2026-09-14 真机缺陷①）**：`doc/14-interface-contract.md:692`（§7.4 步骤 2）原写「构造 `VideoFrame(i420, 0 /*rotation 已烘进 I420*/, frame.timestampNs)`」，其前提"rotation 已烘进 I420"**不成立**：上游 `TextureBufferImpl.java:111-113` 的 `toI420()` 只做 YUV 转换、**不做旋转**（该文件 `rotation` 命中数 = 0）；朝向由渲染器 `VideoFrameDrawer.java:204`（`renderMatrix.preRotate(frame.getRotation())`）与编码器（本工程 `Vp9VideoEncoder.kt:208` 直取 `frame.rotation`）从**元数据**获得。**正确口径 = 必须保留 `frame.rotation`**：`VideoFrame(i420, frame.rotation, frame.timestampNs)`。置 0 会同时造成 ①本地预览朝向错误（真机实测逆时针 90°）②编码器丢失 90/270 尺寸交换信号（与 `:518`/`:635` 冲突）。`doc/14` 为冻结件，本轮**未直接修改**，实际修复落在 `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt`（详见 `reports/13-device-defect-fix.md`）。

---

## 5. 本轮静态核验：命令与**原始输出**（逐字）

> 工作目录 = 工作区根；命令串与交付契约 `verify` 列表**逐字同序**。

### 命令 1
```
$ cd code/webrtc-demo && grep -n 'VideoFrame(i420, frame.rotation' app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt
82:        val converted = VideoFrame(i420, frame.rotation, frame.timestampNs)
--- EXIT=0
```

### 命令 2
```
$ cd code/webrtc-demo && ! grep -rn 'VideoFrame(i420, 0' app/src/main/kotlin/
--- EXIT=0
```

### 命令 3
```
$ cd code/webrtc-demo && grep -n 'frame.rotation' app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt
33:     *   **`rotation` 取 `frame.rotation` 原样保留** → 交给下游 → `out.release()`。
43:     * `nativeEncode(..., normalizeRotation(frame.rotation), ...)`（doc/14 §6.6 第 635 行：
63:                        "rot" to frame.rotation.toString(),
82:        val converted = VideoFrame(i420, frame.rotation, frame.timestampNs)
99:                    "rot" to frame.rotation.toString(),
--- EXIT=0
```

### 命令 4
```
$ cd code/webrtc-demo && grep -n 'call_room_code' app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt app/src/main/res/values/strings.xml
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:100:        Toast.makeText(context, context.getString(R.string.call_room_code_copied), Toast.LENGTH_SHORT).show()
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:180:                    text = stringResource(R.string.call_room_code, roomId),
app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:187:                        contentDescription = stringResource(R.string.call_room_code_copy_desc),
app/src/main/res/values/strings.xml:69:    <string name="call_room_code">会议号：%1$s</string>
app/src/main/res/values/strings.xml:71:    <string name="call_room_code_copied">会议号已复制</string>
app/src/main/res/values/strings.xml:73:    <string name="call_room_code_copy_desc">复制会议号</string>
--- EXIT=0
```

### 命令 5
```
$ cd code/webrtc-demo && git status --porcelain
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt
 M app/src/main/res/values/strings.xml
?? reports/13-device-defect-fix.md
--- EXIT=0
```
⇒ 4 项（3 个源文件改动 + 本报告）**全部位于 inScope**；**未**出现 `doc/`、`third_party/`、`scripts/`、`cpp/`、`*.jar/*.aar/*.so/*.apk`、gradle 配置。
（本段为**提交前**最终状态；提交后 `git status --porcelain` 应为空，见 §7。）

### 附加核验（非契约命令，供复核）
```
$ grep -rn 'rotation' app/src/main/kotlin | wc -l
18          # 修复前 10 处；+8 来自本轮新增的说明性注释与修复注释
$ grep -rn 'VideoFrame(' app/src/main/kotlin
app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt:82:  val converted = VideoFrame(i420, frame.rotation, frame.timestampNs)   ← 全仓唯一构造点
$ grep -rn 'rotation' app/src/test            → 无命中
$ grep -rln 'FrameNormalizer|VideoFrame' app/src/test → 无命中
$ grep -c rotation third_party/libwebrtc/include/sdk/android/api/org/webrtc/TextureBufferImpl.java → 0
$ grep -n 'renderMatrix.preRotate' third_party/libwebrtc/include/sdk/android/api/org/webrtc/VideoFrameDrawer.java
204:    renderMatrix.preRotate(frame.getRotation());
```

### 起草期自纠（如实登记）
首版注释里我**逐字引用**了 doc/14:692 的旧写法（含 `VideoFrame(i420, 0` 字面量），导致**命令 2**（负向 grep `! grep -rn 'VideoFrame(i420, 0' app/src/main/kotlin/`）**首次运行 EXIT=1**。已改写 KDoc 为「原先把第二实参（rotation）写死为 0」的**描述性**表述（报告内仍逐字引用旧文），重跑后命令 2 = EXIT=0。
**口径教训（与团队既有清单同族）**：**负向 grep 会匹配注释文本** —— 在负向断言里规避字面量，或把"引用旧代码"放进报告而非源码注释。

---

## 6. 仍未验证项（不得读作通过）

1. **真机重测（唯一决定性判据）**：
   - ① 预览朝向：创建会议后本地预览应为**正立**（不再逆时针 90°），且远端画面朝向正确；
   - ② 会议号可见 + 复制：连接完成后顶部仍可见「会议号：xxxxxx」，点击文本或复制图标应写入剪贴板并出现「会议号已复制」Toast；对端应能用该 6 位号成功 join。
2. **编译与单测**：容器**无 JDK/SDK**，本轮**未编译**。需 t41 在宿主机执行
   `:app:compileDebugKotlin` + `:app:testDebugUnitTest`（预期仍 46/0/0）与 APK 重编；若 Compose API 细节（如 `MaterialTheme.shapes.small`、`clickable`、`ContentCopy`）有版本差异，以宿主机编译结果为准。
3. **编码器侧 90/270 尺寸交换**：本轮只保证 rotation 元数据被保留并传入 `nativeEncode`；native 侧是否已按 doc/14:518 在 90/270 时交换 `g_w/g_h`，**未在本轮核验**（属 C++ 范围）。
4. **`I420 直通` 分支**：其"rotation 随原帧保留"为本轮**静态推断**（未新构造 `VideoFrame`，故必然保留），真机同路径未单独复测。

---

## 7. 交付物与提交

- 修改：`app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameNormalizer.kt`、`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt`、`app/src/main/res/values/strings.xml`
- 新增报告：`reports/13-device-defect-fix.md`（mode 644）
- 提交：**一次提交**，包含上述 3 个源文件改动 + 本报告（作者/提交者 = 容器内当前用户，未用 root/sudo 改 `.git` 属主）；提交 hash 见 t39 任务回报（本文件无法自引用自身提交 hash）。
