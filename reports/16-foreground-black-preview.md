# reports/16-foreground-black-preview.md — 切后台再回前台本地预览黑屏（t45）

- **任务**：t45（implementation round 1）—— 修复真机缺陷「App 切后台再回前台，本地预览黑屏（其余 UI 正常）」。
- **执行者**：android-dev（attempt 1，attempt_id `8942d292-29ef-4068-84b0-93ce9f1e4414`）。
- **基线**：t44 已落地（日志等级过滤 `LogLevel.isEnabledFor` 已由 `<=` 改为 `>=`，本轮以此为准）。
- **范围**：仅 `app/src/main/kotlin/**`（`webrtc/`、`ui/call/`）与 `app/src/test/**`、本报告；**未触碰** `third_party/`、`doc/`、`scripts/`、`signaling/`、`app/src/main/cpp/`、任何 `*.jar/*.aar/*.so/*.apk`。
- **环境**：容器**无 JDK/SDK** ⇒ 本轮**未编译、未跑单测**（`:app:compileDebugKotlin` / `:app:testDebugUnitTest` 由宿主机后续构建任务执行）；**真机是否恢复出画只能由用户复测**（见 §7、§8）。

---

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 现象 | 切后台 → 回前台后，**本地预览黑屏**，UI/远端区域仍在；**不得靠重建 Activity/重进房间恢复** |
| 代码级根因 | `ui/call/CallScreen.kt` 原先**没有任何 onPause/onResume 处理**：sink 只在 `LaunchedEffect(localTrack, localRenderer)` 键变化时挂载（原 `:129-134`），恢复路径既不重挂也不重建渲染器；唯一释放点在 `DisposableEffect.onDispose`（原 `:137-142`）。而 `SurfaceViewRenderer.release()`（`SurfaceViewRenderer.java:96-98` → `eglRenderer.release()`）后实例**永不再出画**，且**原地 `init()` 也无法重建 EGL surface**（`SurfaceViewRenderer.java:239-242` 的 `surfaceCreated` 不会二次触发） |
| 日志级根因 | 真机 `app.log` **最后两行**仍是 `local_preview_resolution_changed`（14:34:42.835 rot=90 / 14:34:44.520 rot=270），而用户看到的是黑屏 ⇒ **"回调还在"不能证明画面正常**（`EglRenderer` 在无 surface 丢帧前同样会回调 `onFrameResolutionChanged`）；同时 `main_activity_pause/resume` 命中 **0**（代码里确实以 INFO 打了：`MainActivity.kt:43-51`）⇒ t44 修掉的过滤 Bug 恰好把生命周期线索全丢了 |
| 修复 | ① 切后台**不 release**（只解除看门狗）；② 回前台：幂等重挂 sink + 采集按需重启 + **3 秒无帧则换新实例重建渲染器**并由 Compose `key(generation)` 重新挂载视图（框架重走 `surfaceCreated`），最多 2 次，之后 `GIVE_UP` 上报；③ 补齐 surface/attach/recover 全链路诊断事件 |
| 状态 | 代码＋静态核验完成；**真机效果未验证**（无设备），列为未验证项 |

---

## 1. 现象与真机日志证据

**设备**：Xiaomi 24117RK2CC / Android 16 / arm64（`device-info.txt`）；应用 `android:screenOrientation="portrait"`。

**证据（宿主 `/opt/dsh-workspaces/tmp/dev-logs-2232/x/`）**

```
$ grep -n 'local_preview_resolution_changed' app.log | tail -6
133:2026-09-14T14:31:33.235Z DEBUG kotlin stats [23897/26928] local_preview_resolution_changed h=480 rot=270 w=640
172:2026-09-14T14:31:57.898Z DEBUG kotlin stats [24450/28025] local_preview_resolution_changed h=480 rot=270 w=640
202:2026-09-14T14:32:19.725Z DEBUG kotlin stats [24676/28350] local_preview_resolution_changed h=480 rot=270 w=640
236:2026-09-14T14:34:03.448Z DEBUG kotlin stats [31569/32548] local_preview_resolution_changed h=480 rot=270 w=640
256:2026-09-14T14:34:42.835Z DEBUG kotlin stats [31569/32548] local_preview_resolution_changed h=480 rot=90  w=640
257:2026-09-14T14:34:44.520Z DEBUG kotlin stats [31569/32548] local_preview_resolution_changed h=480 rot=270 w=640
```

```
$ grep -c 'main_activity_pause\|main_activity_resume' app.log      → 0      ← 代码确实打 INFO（MainActivity.kt:43-51）
$ grep -c 'renderer_attach\|surface_created\|surface_destroyed\|on_pause\|on_resume' app.log → 0   ← 这些事件本轮才新增
$ wc -l app.log native.log                                          → 257 / 228
```

**要点**：
1. 结尾两条 `resolution_changed` 的 **rot 从 270 跳到 90 再回 270**，说明回前台后**采集与渲染器之间的帧通路仍在**（帧继续流到 sink），黑屏发生在**绘制/表面**层；
2. `EglRenderer` 对"无 surface"的处理是**先更新尺寸（回调 `onFrameResolutionChanged`）再丢帧**，所以这两行**不能**当作"画面正常"的证据 —— 这解释了此前"日志看着在跑、屏幕却是黑的"；
3. 生命周期与渲染事件当时**全部不可见**：前者被 t44 修的过滤 Bug 吞掉（INFO 在阈值 DEBUG 下被丢弃），后者当时尚未实现。

---

## 2. 根因（代码级，file:line）

### 2.1 页面没有任何前台/后台生命周期处理（主因）
```
$ grep -rn 'LifecycleEventObserver\|LocalLifecycleOwner' app/src/main/kotlin | （修复前）0 命中
$ grep -n 'onResume\|onPause' app/src/main/kotlin/com/example/webrtcdemo/MainActivity.kt
43:    override fun onResume() { … AppLog.i(MODULE_TAG, "main_activity_resume") }
48:    override fun onPause()  { … AppLog.i(MODULE_TAG, "main_activity_pause") }
```
`MainActivity` 只是**记日志**；`CallScreen` 既没有 `LifecycleEventObserver`，也没有任何 `ON_START/ON_RESUME` 分支。因此回前台时**没有任何代码重新挂 sink 或重建渲染器**。

### 2.2 sink 只在"键变化"时挂载 ⇒ 恢复路径不会重挂
`ui/call/CallScreen.kt`（修复前 `:129-134`）：
```kotlin
LaunchedEffect(localTrack, localRenderer)  { WebRtcEngine.rendererPool()?.attachLocal(localTrack, localRenderer) }
LaunchedEffect(remoteTrack, remoteRenderer){ WebRtcEngine.rendererPool()?.attachRemote(remoteTrack, remoteRenderer) }
```
`localTrack` 与 `localRenderer` 在切后台/回前台过程中**都不变** ⇒ 这两个 effect **不会重跑**。若期间 sink 被移除（或被释放），恢复后无人重新挂载。

### 2.3 唯一的释放点在 onDispose，且释放是"不可逆"的
`ui/call/CallScreen.kt`（修复前 `:137-142`）：
```kotlin
DisposableEffect(Unit) { onDispose { WebRtcEngine.rendererPool()?.detachAndReleaseAll(); viewModel.hangup() } }
```
`webrtc/VideoRendererPool.kt`（修复前 `:146-153`）：
```kotlin
fun detachAndReleaseAll() { val local = localRenderer; val remote = remoteRenderer; detachLocal(); detachRemote(); local?.release(); remote?.release() }
```
而 `SurfaceViewRenderer` 一侧（`third_party/libwebrtc/include/sdk/android/api/org/webrtc/SurfaceViewRenderer.java`）：
```
:96-98   public void release() { eglRenderer.release(); }            ← 该实例此后永不再出画
:239-242 public void surfaceCreated(final SurfaceHolder holder) { surfaceWidth = surfaceHeight = 0; updateSurfaceSize(); }
:246     public void surfaceDestroyed(final SurfaceHolder holder) {}  ← 空实现：上层看不到 surface 消失
:67-68/77-78 注释明确 "It is allowed to call init() to reinitialize the renderer after a previous init()/release() cycle"
```
**结论（两条坑）**：
1. 一旦渲染器被 `release()`，**该实例永久失效**（黑屏即不可逆）；
2. 即便再 `init()`，只要**旧 surface 仍存在**（`surfaceCreated` 不会二次触发），`EglRenderer` 不会重建 EGL surface ⇒ **仍然黑屏**。因此恢复必须**换新实例**（并由 Compose 重新挂视图，让框架重走 `surfaceCreated`）。

### 2.4（次要）surface 生命周期完全不可观测
`SurfaceViewRenderer.surfaceDestroyed()` 是**空实现**，本工程此前也没有在 `holder` 上加自己的回调 ⇒ 无法回答"回前台后 surface 到底回来没有"，这正是本轮要把打点补齐的原因。

---

## 3. 修复设计与 diff 摘要

### 3.1 语义
- **切后台不是离页**（§7.3 的 removeSink→release 只适用于**离开通话页**）：
  `ON_PAUSE` ⇒ 只解除看门狗 + 打点 `on_pause`，**不 release、不 re-init、不动采集**；
- **回前台恢复**（`ON_RESUME`）三步：
  1. **幂等重挂**：`attachLocal/attachRemote`（覆盖"sink 被摘掉"的情形）；
  2. **采集按需重启**：`MediaCapture.resumeIfNeeded()` —— **仅在采集确实已停**时才 `ensureStarted()`（避免重复创建 `SurfaceTextureHelper`/capturer、重复占用摄像头与 EGL 上下文）；
  3. **3 秒看门狗**：若自 `onResume` 起**没有任何新帧**（用渲染池的帧时间戳判定，而不是"回调是否还在"），则**换新实例**重建该侧渲染器 → `renderers = fresh to …` → Compose 以 `key(generation)` **重新挂载视图**（框架重走 `surfaceCreated`）→ 旧实例 `detach + release`；最多 2 次，超过则 `preview_recover_give_up`（ERROR）并停止自动重建。

### 3.2 变更文件

| 文件 | 变更 |
|---|---|
| `webrtc/VideoRendererPool.kt` | 新增：**`RendererRecoveryPolicy` 状态机（并入本文件，避免新增文件越出 inScope）**（`windowNs=3 s`、`maxAttempts=2`，动作 `NONE/RECOVER/GIVE_UP`）；渲染器规格表（重建用）、`recreateRenderer()`、`releaseRenderer()`（含 released 守卫）、`surfaceAlive()`、`lastFrameAtNs()`/`frameSeenSince()`、`isReleased()`；`holder` 追加 surface 回调（`surface_created/changed/destroyed`）；帧回调补 `surface=` 字段；`renderer_created/released/recreate/attach_rejected` 打点 |
| `webrtc/MediaCapture.kt` | 新增：`isCapturing()`、`resumeIfNeeded()`（`capture_resume_noop`/`capture_resume_start`）；幂等分支记 `capture_already_started` |
| `ui/call/CallScreen.kt` | 新增：`LifecycleEventObserver`（`on_pause`/`on_resume`）、恢复看门狗 effect、`localGeneration/remoteGeneration` 并用 `key(...)` 包裹两个 `AndroidView`（`Modifier.align` 在 `key {}` 外求值，因其 block 无 `BoxScope` 接收者）；`onDispose` 仍按 §7.3 释放 |
| `app/src/test/kotlin/.../webrtc/RendererRecoveryPolicyTest.kt` | **新增**：7 个纯 JVM 用例（3 秒窗口、恢复重计时、2 次后 GIVE_UP、出帧解除、切后台静默） |

**未改动**：`doc/14`（冻结）、`third_party/**`、`scripts/**`、`app/src/main/cpp/**`、任何产物字节；**未新增依赖**。

### 3.3 与 t44 修复的交互
- t44 把 `log/LogLevel.kt:53` 的 `<=` 改为 `>=`（阈值 DEBUG ⇒ 输出 DEBUG 及更严重级别）。本报告新增的事件中，**关键路径一律用 `AppLog.i`（INFO）/`AppLog.w`/`AppLog.e`**，因此在"阈值 DEBUG"下**必然落盘**（修复前 INFO 会被丢，这正是本轮此前无法定因的原因）。
- 本轮**没有**修改 `CallSession.kt`/`CallViewModel.kt`（t44 的暂存 offer / 重放逻辑不受影响）；`VideoRendererPool` 的公开方法签名**保持兼容**（`createRenderer/attachLocal/attachRemote/detachLocal/detachRemote/detachAndReleaseAll` 不变，新增方法为追加）。

---

## 4. 新增诊断事件登记（doc/14 §9.1 风格；`doc/14` 为冻结件，未改）

| 事件名 | 级别 | 语义 |
|---|---|---|
| `on_pause` | INFO | 通话页进入后台（`release=no`） |
| `on_resume` | INFO | 通话页回到前台（带 `tick`） |
| `surface_created` / `surface_changed` / `surface_destroyed` | INFO / DEBUG / INFO | `SurfaceViewRenderer` 的 surface 生命周期（`which=local|remote`） |
| `renderer_created` | INFO | 新建渲染器（`which/mirror/tracked`） |
| `renderer_recreate` / `renderer_recreate_skipped` | INFO / WARN | 恢复路径换新实例（或无可复用规格） |
| `renderer_released` | INFO | 渲染器已释放（`which/releasedTotal`） |
| `renderer_attach_rejected` | WARN | 试图挂载已释放实例（防 use-after-release） |
| `renderer_attached` / `renderer_detached` | INFO | 挂/摘 sink（补 `surface=` 字段） |
| `preview_recover_armed` | INFO | 回前台武装看门狗（含两侧 surface/alive/released 状态） |
| `preview_recover_attempt` | WARN | 3 秒无帧 ⇒ 触发重建（含 `attempt/本地是否出帧/surface/released`） |
| `preview_recover_give_up` | ERROR | 2 次仍无帧 ⇒ 停止自动重建（交用户/日志定位） |
| `capture_already_started` / `capture_resume_noop` / `capture_resume_start` | INFO / INFO / WARN | 采集幂等与恢复判据 |

---

## 5. 单测（纯 JVM，容器内不执行）

`app/src/test/kotlin/com/example/webrtcdemo/webrtc/RendererRecoveryPolicyTest.kt`（新增，7 用例）：

| 用例 | 断言 |
|---|---|
| `defaultsMatchAcceptance` | `windowNs == 3 s`、`maxAttempts == 2` |
| `noFrameWithinWindowTriggersRecover` | 2 s 无帧 → NONE；3 s 无帧 → RECOVER |
| `recoverRestartsWindow` | 恢复后立即评估不再动作；再过一个窗口 → 第二次 RECOVER |
| `givesUpAfterMaxAttempts` | 第三次 → GIVE_UP 且解除看门狗 |
| `frameDisarmsWatchdog` | 出帧 ⇒ 解除，此后不再重建（`attempts == 0`） |
| `reportedFrameDisarmsWatchdog` | `onFrame()` 同样解除 |
| `pauseDisarmsUntilNextResume` | 切后台静默；再 `onResume` 后重新计时 |

**实际执行**由宿主机跑 `./gradlew --no-daemon :app:testDebugUnitTest`（预期原有 46 用例 + 本文件 7 用例）。

---

## 6. 本轮静态核验：命令与原始输出

```
$ cd code/webrtc-demo && grep -rn 'removeSink\|release()\|init(\|surfaceCreated\|surfaceDestroyed\|onPause\|onResume\|LifecycleEventObserver' app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt | head -30
app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:21:// 生命周期（硬性）：**离开通话页**时必须先 `track.removeSink(renderer)` 再 `renderer.release()`；
…（共 30 行，含 `:167 renderer.init(...)`、`:175 surfaceCreated`、`:188 surfaceDestroyed`、`:243/260 removeSink`、`:284 renderer.release()`、
   CallScreen.kt:50 `import androidx.lifecycle.LifecycleEventObserver`、`:163 LifecycleEventObserver { … }`、`:166 recoveryPolicy.onPause()`、`:173 recoveryPolicy.onResume(…)`）
--- EXIT=0
```

```
$ cd code/webrtc-demo && grep -rn 'renderer_\|surface_\|on_pause\|on_resume' app/src/main/kotlin/com/example/webrtcdemo/ | head -20
app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:129: AppLog.w(TAG, "renderer_recreate_skipped", …)
:132: AppLog.i(TAG, "renderer_recreate", …)
:177: AppLog.i(TAG, "surface_created", …)
:183: "surface_changed",
:190: AppLog.i(TAG, "surface_destroyed", …)
:198: "renderer_created",
:210/:225: AppLog.w(TAG, "renderer_attach_rejected", …)
:216/:231: AppLog.i(TAG, "renderer_attached", …)
:247/:264: AppLog.i(TAG, "renderer_detached", …)
:286: AppLog.w(TAG, "renderer_release_failed", …)
:292: "renderer_released",
--- EXIT=0
```

```
$ cd code/webrtc-demo && ls -l reports/16-foreground-black-preview.md && git status --porcelain
-rw-r--r-- 1 node node 20752 Sep 14 23:11 reports/16-foreground-black-preview.md
 M app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt           ← 本任务
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/MediaCapture.kt          ← 本任务
 M app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt     ← 本任务（含并入的 RendererRecoveryPolicy）
?? app/src/test/kotlin/com/example/webrtcdemo/webrtc/RendererRecoveryPolicyTest.kt  ← 本任务（新增）
?? reports/10-t47-captain-*.log（4 份）                                      ← 非本任务（构建方日志）
?? reports/16-foreground-black-preview.md                                    ← 本任务（本报告）
--- EXIT=0
```
**范围说明（快照时刻 23:11；`git status` 会随其他成员在途改动继续变化）**：`reports/10-t47-*.log` 属**构建方**；本任务改动 = **3 个修改 + 1 个新增单测 + 本报告**（`RendererRecoveryPolicy` 类**并入** `VideoRendererPool.kt`，不新增独立文件），全部落在 inScope 内。

**附加自检**（括号平衡 / 嵌套注释 / **孤立注释文本**）：
```
VideoRendererPool.kt      {43/43} (163/163) 嵌套注释=0   ← 含并入的 RendererRecoveryPolicy
MediaCapture.kt           {21/21} (91/91)   嵌套注释=0
CallScreen.kt             {64/64} (191/191) 嵌套注释=0
RendererRecoveryPolicyTest.kt {8/8} (56/56) 嵌套注释=0
「*/ 之后紧跟 * 行」孤立注释文本检查：4 个文件全部 **0**
```

### 6.1 ⚠️ 编译期事件（如实登记，已修复）

宿主机构建 `reports/10-t47-captain-build-20260914-225952.log` 中出现一次 **`:app:compileDebugKotlin` FAILED**：

```
> Task :app:compileDebugKotlin FAILED
e: …/webrtc/MediaCapture.kt:119:6 Syntax error: Expecting member declaration.
e: …/webrtc/MediaCapture.kt:120:8 Syntax error: Expected annotation identifier after '@'.
…（共 14 条，全部指向 MediaCapture.kt:119-121）
ASSEMBLE_EXIT=1   T1=23:01:28 → T2=23:03:16   BUILD FAILED in 1m 46s
```

**原因（本任务自身，如实披露）**：该构建窗口与我的编辑窗口重叠 —— 我扩展 `ensureStarted()` 的 KDoc 时，替换文本自带 `*/` 收尾，而原 KDoc 的 `@return` 段落仍留在其后，形成**孤立注释文本**（当时 `MediaCapture.kt:119-121`）。**已修复**：把 `@return` 段落并回同一 KDoc 块（现 `:113-121`），并对 **5 个改动文件**做过"孤立注释文本"专项检查（全部 0，见上）。

**结论**：t47 那次 FAILED 属**编辑中的瞬时状态**，**需要重新编译一次**才能得到本任务改动后的可信编译结论；t47 更早那次 `compileDebugKotlin` SUCCESS（23:01:28 结束）只覆盖当时已写定的部分文件，**不能**作为本任务全量改动的编译证据。

---

## 7. 给用户的一次性复测清单（可执行）

1. **准备**：两台设备都更新到含本修复的 APK（宿主机重编后）。
2. **基础场景**：A 机建会 → B 机加入 → 确认 A 的本地小窗有画面。
3. **切后台/回前台 ×1**：按 Home（或切到别的 App）停留 **≥5 秒** → 回到本 App。
   - **期望**：**3 秒内**本地小窗恢复出画，UI 无需重进房间。
4. **连续 ×3**：重复第 3 步 **3 次**，每次都应在 3 秒内恢复。
5. **若仍黑屏**（定位用）：立刻在**通话页**点「导出日志」，然后检查 `app.log`：
   - `on_resume` 出现后是否紧跟 `preview_recover_armed`？
   - `surface_destroyed` 之后是否有对应的 `surface_created`？
   - 是否出现 `preview_recover_attempt`（含 `localFrame=false`）？其 `localSurface=` 是 true 还是 false？
   - 是否出现 `preview_recover_give_up`（说明 2 次重建仍无帧）？
   - `renderer_released` 是否在切后台期间出现（若出现说明仍有人在后台释放渲染器）。
6. **对照场景（可选，帮助区分表面层 vs 采集层）**：切后台前先把「关闭摄像头」再打开，或在诊断页切换一次前后摄，观察是否与 `rot=90/270` 变化相关（真机日志显示回前台时 rot 发生过 270→90→270 跳变）。

> ⚠️ **不得**用"重进房间/重建 Activity 后恢复"当作通过：验收要求是**不离开通话页**即可恢复。

---

## 8. 仍未验证项（不得读作通过）

1. **真机效果**：容器无设备，**"3 秒内恢复出画、连续 3 次可恢复"未经真机验证**；本轮只保证代码路径与诊断完整性。
2. **编译与单测**：容器无 JDK/SDK，`RendererRecoveryPolicyTest` 与两个 main 文件**未编译**；需宿主机 `:app:compileDebugKotlin` + `:app:testDebugUnitTest`。
3. **surface 是否真的重建**：`surface_destroyed` 之后 `surface_created` 是否触发，需下一轮真机日志确认（本轮只补了观测点）。
4. **采集是否被系统在后台停掉**：由 `capture_resume_noop`（未停）或 `capture_resume_start`（已停并重启）在下一轮日志中判定；本轮按"按需重启"处理。
5. **rot 90/270 跳变的成因**（回前台时）未定因，可能是摄像头/显示方向重建；本轮不改变采集参数。
6. `onDispose → hangup()` 在**系统回收 Activity**时是否会被误触发（并导致通话被结束）本轮未处理/未验证，列为后续观察点。
