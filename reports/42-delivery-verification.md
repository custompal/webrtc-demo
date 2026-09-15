# t78 — 最终交付独立验证报告（锚点 `ff93c2e4…` / 提交 `5b7efe0` / 服务端宽限期）

- **任务**：`t78`（kind = verification，round 1，verifier，attempt 1，`attempt_id c7a15167-37d6-4cb1-b257-3fca1d577c91`）
- **验证时间**：2026-09-16 01:39–01:45（容器内只读，除本报告与 `reports/99` 追加节外零写入）
- **交付对象**：APK `ff93c2e4c4037c0ba1746a445edef5344e2f8817ef951b67ad2adb9052ce8871`（33 436 269 B / mtime `2026-09-16 01:35:23.033 +0800`）、提交 `5b7efe0`（96 files, +10030/−317）、现网 signaling `-room-grace 90s`
- **纪律**：未跑 Gradle、未改源码/产物/服务端配置、未 `git commit`；只写本文件与 `reports/99-final-report.md` 的新增节
- **工具口径（容器现状，重要）**：容器内**已无 `python3`/`unzip`/`bc`/`javap`/`java`**；本次改用 **`curl` + Perl（`IO::Uncompress::Unzip`）+ Go 1.22.12 stdlib（`archive/zip`/`debug/elf`/`crypto/sha256`）+ `awk` + `llvm-readelf` + `node v24.21.0`** 独立解析与重放（详见 §9 证据索引）

---

## 1. 独立重算产物身份（**不引用任何成员自述哈希**）

### 1.1 公网拉包（HTTP 协议面）

```
$ curl -sS -I http://47.238.144.66:8080/app-debug.apk
HTTP/1.1 200 OK
Server: apk-http/1.1
Content-Type: application/vnd.android.package-archive
Content-Length: 33436269
Accept-Ranges: bytes
ETag: "1fe326d-18d58f31aa5ebf0e"
Last-Modified: Tue, 15 Sep 2026 17:37:50 GMT
Cache-Control: no-store
Content-Disposition: attachment; filename="app-debug.apk"

$ curl -sS -D rng.hdr -o rng.bin -r 0-1023 http://47.238.144.66:8080/app-debug.apk
HTTP/1.1 206 Partial Content
Content-Length: 1024
Content-Range: bytes 0-1023/33436269        ← 1024 字节实收

$ curl -sS -o pub.apk http://47.238.144.66:8080/app-debug.apk     # 全量 33 436 269 B
$ sha256sum pub.apk
ff93c2e4c4037c0ba1746a445edef5344e2f8817ef951b67ad2adb9052ce8871  pub.apk
```
⇒ **HEAD 200 + Content-Length 正确**、**Range 0-1023 → 206 + Content-Range 正确**、**公网全量下载 sha256 = 锚点**（我第一手）。

### 1.2 四方一致性（可及面 3/4 我第一手，`parts` 面为宿主侧）

| 交付面 | 值 | 我的证据等级 |
|---|---|---|
| 公网 `served`（HTTP 全量下载） | `ff93c2e4…8871` / 33 436 269 B | **第一手** |
| 构建输出 `app/build/outputs/apk/debug/app-debug.apk` | `ff93c2e4…8871` / 33 436 269 B（mtime 01:35:23） | **第一手** |
| 归档 `artifacts/app-debug-ff93c2e4.apk` | `ff93c2e4…8871` / 33 436 269 B（mtime 01:37:50） | **第一手** |
| `parts/` 四片拼接 / `parts/SOURCE.sha256` | 声明 = `ff93c2e4…8871` | **非第一手**：`/opt/apk-http/**` 容器不可见；HTTP 面 `GET /parts/SOURCE.sha256`、`GET /parts/` 均 **404**（服务只暴露 APK 路径），故无法独立复算分片文件本身 |

**分片口径的替代性第一手证据**：按 t76 声明的分片尺寸（8 388 608 ×3 + 8 270 445）用 **Range 请求**独立取回四段并拼接：

```
p0/p1/p2 = 8 388 608 B，p3 = 8 270 445 B（合计 33 436 269 = Content-Length）
$ cat p0 p1 p2 p3 > rec.apk && sha256sum rec.apk
ff93c2e4c4037c0ba1746a445edef5344e2f8817ef951b67ad2adb9052ce8871  rec.apk
```
⇒ **served 面本身确实可由该分片方案无损重组**（分片尺寸/边界自洽）；但**宿主 `parts/` 目录里的实际文件**与 `SOURCE.sha256` 我**未第一手复核**（属未验证面，见 §10）。

### 1.3 APK 内取证（Go stdlib 独立解析，来源 = 公网下载件）

```
$ ./apkscan pub.apk x2        # archive/zip + debug/elf + crypto/sha256
APK=pub.apk 条目=165

### 四 .so LOAD p_align（APK 内实体）
  libandroidx.graphics.path.so             0x4000
  libc++_shared.so                         0x4000
  libjingle_peerconnection_so.so           0x4000
  libwebrtcdemo_native.so                  0x4000        ← 四件全 0x4000（16 KB 页门禁 PASS）

### dex 字面量计数（按 dex 分列）
  signaling_lost             total=2   classes5.dex=2
  restart_ice                total=2   classes5.dex=2
  rejoin_offer_received      total=1   classes5.dex=1
  initiator=host             total=1   classes5.dex=1
  initiator=rejoiner         total=1   classes5.dex=1
  offer_timeout              total=1   classes5.dex=1
  rejoinBudgetMs             total=2   classes9.dex=2
  MAX_RECONNECT_ATTEMPTS     total=0                  ← t71 已删除的平行常量确实不存在
  rejoin_ice_restart         total=0                  ← 旧写法已统一为 restart_ice
```
⇒ 验收要求的五个 dex 字面量**全部存在**（`signaling_lost` / `restart_ice` / `rejoin_offer_received` / `initiator=host` / `initiator=rejoiner`）。

**APK 内 14 个 `classes*.dex` 的 sha256（我第一手重算，登记为 t76 基线）**

| 文件 | 字节 | sha256 |
|---|---|---|
| `classes.dex` | 44 668 428 | `a1b2ebdceec4f1fd11f78df7b22ca0133c50768c5f0e8dfee68429f63941028d` |
| `classes2.dex` | 40 868 | `4f9c752c691ac3a7357ffe918a73b2476869e56cd9bf43b1035fe224f6def58f` |
| `classes3.dex` | 164 764 | `ea5229ce4afd5c26844973a91c3df5919312f19fac6a3068d342b48086cbf150` |
| `classes4.dex` | 6 504 | `b97db179f2146b8df7b76475e2574052dca4646d1b6d74e83e888ecdfdcae0d6` |
| `classes5.dex` | 196 552 | `c297b0672a097d627bbb228cd29ee98b9e610c977ed7cd83ad4b09d826774456` |
| `classes6.dex` | 39 368 | `82a8c74830191feca14adff44863414a23938793a7ff0ecc15bf8578dcd44d0a` |
| `classes7.dex` | 6 584 | `f8f0aedcac7dd84399346dbd1ba4893642562916f04151aafcb17dde6bc74d3b` |
| `classes8.dex` | 12 352 | `7f0c74b0e46df8888edd4f985a770ab9a8dad3c9235da4ff715706553d54bca0` |
| `classes9.dex` | 96 372 | `c3d23ff4f593dfbb5439b50a1cad5652dc47d46de7b773ea3f667863da1e9b96` |
| `classes10.dex` | 21 188 | `5ea4176b781985b0406452e472a7d8fa5a0393d3fb891f200e588e033947aabe` |
| `classes11.dex` | 135 060 | `b24cd9da522cf817075ca26fccf1b307bc489055c10d5ae6342d7a8e9a3b1527` |
| `classes12.dex` | 14 304 | `82256a73d4e010220dc0a033dbbb22ab9358efb315c4d952f3b21746d584fb59` |
| `classes13.dex` | 12 903 480 | `a1f35bd51c0e5a30ceb2c3f453da59bdfa8054e56f3f761c79541d3f42a98a16` |
| `classes14.dex` | 558 976 | `4ce5266df8591b2c77e076849812c7d3bac307a6a6a2f15022cbc87ec8401dca` |

**四 `.so` 与关键条目 sha256**：`libjingle 757cef81…c233259e`（12 946 912 B）、`libc++_shared c9dbf4ec…797d1e36`（1 356 968 B）、`libwebrtcdemo_native d49eafc3…426212a5`（1 275 176 B）、`libandroidx.graphics.path 41e9a793…115bfb6`（10 096 B）、`resources.arsc e5550e42…4eb9a1e7`（440 012 B）、`AndroidManifest.xml bf985c14…31cfff6f`（7 572 B）。

### 1.4 固定件（T0=T2）与包体基线对照

```
$ sha256sum third_party/libwebrtc/java/libwebrtc-java.jar third_party/libwebrtc/java/libwebrtc-arm64.aar \
            app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so app/src/main/jniLibs/arm64-v8a/libc++_shared.so
0c776934c1452b7bf43d57d8174a6c1d8504c43814b8320e8c624a29d63dc757  libwebrtc-java.jar
8e8f2bafce23b4195884002b392c1cf78dabf8abb78196d0bf5a08e08fd4a099  libwebrtc-arm64.aar
757cef8128bf915109864ab92df29984dea17493dfe3417a73cd00fdc233259e  libjingle_peerconnection_so.so
c9dbf4ec15e931f565e32c5a159dec87b27caccde5c2dda14bbae466797d1e36  libc++_shared.so
```
⇒ 与 t72 声明**逐位相同**（jar/aar/libjingle/libc++_shared 未变）。

**t72 `f694a103…` ↔ t76 `ff93c2e4…` 逐条目（我自写 `cmpzip`，Go stdlib）**：

```
A=f694a103… 条目=165   B=ff93c2e4… 条目=165
相同=158 不同=7 仅A=[] 仅B=[]
不同条目=[classes11.dex classes12.dex classes14.dex classes3.dex classes5.dex classes6.dex classes9.dex]
```
⇒ t76 的差异声明**逐项复现**：165/165 条目、无增删，差异**仅 7 个 dex**，四 `.so` / `resources.arsc` / `AndroidManifest.xml` 逐字节相同。

---

## 2. 构建证据复核（我自行解析，不引用构建者结论）

```
$ ls app/build/test-results/testDebugUnitTest/*.xml | wc -l        → 18
$ awk 逐文件 tests/failures/errors/skipped                          → 见下表
$ 合计 tests=180  failures=0  errors=0（skipped 全 0）
```

| 测试类 | tests | failures | errors |
|---|---|---|---|
| `config.AppConfigUrlTest` | 8 | 0 | 0 |
| `log.LogLevelFilterTest` | 6 | 0 | 0 |
| `nativebridge.NativeInterfaceContractTest` | 4 | 0 | 0 |
| `signaling.PongLivenessTest` | 8 | 0 | 0 |
| `signaling.ReconnectBudgetTest` | 5 | 0 | 0 |
| `signaling.SignalingErrorPolicyTest` | 21 | 0 | 0 |
| `signaling.SignalingIdentityTest` | 11 | 0 | 0 |
| `ui.call.CallSessionSlotTest` | 8 | 0 | 0 |
| `ui.call.CallSurvivabilityTest` | 19 | 0 | 0 |
| `ui.call.ConnectionStatusTrackerTest` | 29 | 0 | 0 |
| `ui.call.MediaAliveSuppressionTest` | 10 | 0 | 0 |
| `ui.call.PendingRemoteMessagesTest` | 7 | 0 | 0 |
| `webrtc.IceCandidateInfoTest` | 8 | 0 | 0 |
| `webrtc.JniBindingClasspathTest` | 6 | 0 | 0 |
| `webrtc.LoopbackCandidatesTest` | 5 | 0 | 0 |
| `webrtc.RendererRecoveryPolicyTest` | 7 | 0 | 0 |
| `webrtc.SessionLifecycleTest` | 10 | 0 | 0 |
| `webrtc.TurnTcpFallbackTest` | 8 | 0 | 0 |
| **合计** | **180** | **0** | **0** |

⇒ 闸门（≥18 类且 ≥180 例、失败=0）**PASS**（18/180/0/0）。**注意**：`app/build/**` 属易失目录（P-16 口径），上述读数为 **2026-09-16 01:39 时点值**；其"属于哪一轮构建"由 `reports/37-t76-build-publish.md` 与 `reports/10-*` 日志承担，本报告只作时点登记。

**提交与工作树**：
```
$ git log --oneline -1                → 5b7efe0 fix(call): 消除通话误退房与 ICE 误报(t67/t68/t70/t71/t74/t75)
$ git status --porcelain | wc -l      → 0        （-uall 亦为 0）
$ git show --stat --oneline 5b7efe0 | tail -1
  96 files changed, 10030 insertions(+), 317 deletions(-)
```
⇒ 交付提交 `5b7efe0` 与"96 文件"**核实一致**，工作树 **clean**。

---

## 3. 代码级逐条取证（真实现 vs 仅声称）

| # | 声明 | 我读到的 file:line | 判定 |
|---|---|---|---|
| ① | t75 顺序修复：先 `restartIce("rejoin")` 再发 offer | `ui/call/CallViewModel.kt:1332-1361` `restartIceThenOffer()`：`:1343 val accepted = current.restartIce("rejoin")` → `:1360 sendOfferInternal()` → `:1367 current.createOffer()`；**同一函数体内顺序写死** | **真实现** |
| ② | `Joined` 侧不发起重协商 | `grep -rn maybeRestartIceAfterRejoin app/src signaling` = **0 命中**；`CallViewModel.kt:859-875`（Joined 分支）只置 `:873 awaitingPeerOfferAfterRejoin = role != ROLE_HOST` + `:874` 计时；`:868 maybeCreateOffer()` 内部 `:1298-1301` 有 **`if (role != ROLE_HOST …) return`** 角色闸门 ⇒ 重连入会方实际不发 offer | **真实现** |
| ③ | 8 s 兜底判定 + 既有 1 s 心跳评估点 | `ui/call/CallSurvivability.kt:147 REJOIN_OFFER_FALLBACK_MS = 8_000L`；`:162-173 shouldRejoinerFallbackOffer()`（未等待→false；`waitedMs < 8_000`→false；`mediaAlive && hasSelectedPair`→false；否则 `iceDown \|\| !mediaAlive`）；评估点 `CallViewModel.kt:284-296`（既有心跳内）→ `:1376-1388 evaluateRejoinOfferFallback()`，`:1386` 触发后 `awaitingPeerOfferAfterRejoin = false` 自锁**一次性**，**无新增 sleep/协程** | **真实现**（含一次性自锁） |
| ④ | 63 s 预算复用 `rejoinDelayMs`、断言 `< 75_000` | `signaling/SignalingClient.kt:142 REJOIN_RETRY_MAX_MS = 8_000`、`:145 MAX_REJOIN_ATTEMPTS = 10`、`:156 rejoinDelayMs()`（1/2/4/8 封顶）、`:163 rejoinBudgetMs()`；**生产接线** `:681-705 scheduleRejoinRetry()`：`:683 if (attempt > MAX_REJOIN_ATTEMPTS)`、`:704 rejoinDelayMs(attempt)` ⇒ 非仅测试口径；测试 `signaling/ReconnectBudgetTest.kt:50 assertEquals(63_000L, budget)`、`:52 assertTrue(… budget < 75_000L)`、`:28-33` 逐档退避、`:58 MAX_REJOIN_ATTEMPTS == 10`；`grep 90_000` = 0 | **真实现** |
| ⑤ | t68 `SIGNAL_LOST` 按 `mediaAlive` 分派 + 媒体存活期抑制失败文案 | `CallViewModel.kt:754-782`：`:757 lastDisconnectCause` → `:758 mediaAlive` → `:760 cause == DisconnectCause.SIGNAL_LOST` → `:761 CallSurvivability.signalLostAction(everConnectedInGeneration, mediaAlive)`；判定 `CallSurvivability.kt:105-106`（`everConnected \|\| mediaAlive ⇒ KEEP_CALL`）；抑制 `CallSurvivability.kt:185-190 shouldSurfaceError()`（`mediaAlive` 时对含 "ICE"/"未连通"/"中继" 的文案返回 false）→ 调用点 `CallViewModel.kt:1218-1219` | **真实现** |

---

## 4. 服务端现网独立验证

### 4.1 静态面（容器可及部分第一手，宿主侧标注）

| 项 | 值 | 等级 |
|---|---|---|
| `signaling/dist/signaling-linux-amd64`（构建产物，`/signaling/dist/` 被 `.gitignore:50` 忽略、未入库） | `8708629ee152eb6b70367f32cee793aad16d56b6aaa6db2a648508b3585a62f5`（8 166 823 B） | **第一手**（与 t70 声明的安装件哈希**相同**） |
| 宿主 `/opt/signaling/signaling` | 同一哈希 | **非第一手**：宿主路径容器不可见（`ls /opt/signaling` = No such file） |
| 宿主 unit 含 `-room-grace 90s` | 由 `/healthz` 反证 + t70/reports 38 记录 | **间接第一手**（见下）；**仓库内 `deploy/signaling.service` 不含该 flag** ⇒ 偏差 D-5 |

### 4.2 `/healthz`（公网，两时点对照，均为我第一手）

```
[01:41] {"activeConns":0,"graceExpired":6,"roomGraceSec":90,"rooms":0,"roomsCreated":5,
         "roomsDestroyed":5,"seatTakeovers":3,"totalConns":13,"uptimeSec":6948,"version":"0.1.0"}
[01:42] {"activeConns":0,"graceExpired":6,"roomGraceSec":90,"roomIds":["SA6ABK"],"rooms":1,
         "roomsCreated":6,"roomsDestroyed":5,"seatTakeovers":4,"totalConns":16,"uptimeSec":7027,"version":"0.1.0"}
```
⇒ `roomGraceSec = 90` **成立**（运行实例确以 90 s 宽限期工作）；且我的活体重放使其 **`roomsCreated` 5→6、`seatTakeovers` 3→4、`totalConns` 13→16** ⇒ **`seat_takeover` 由我本次重放触发（服务端计数第一手证据）**。

### 4.3 活体重放（自有最小 RFC6455 客户端，raw `net`；可硬断开且**不发 close 帧**）

```
0.011s [A] send {"type":"create"}
0.016s [A] recv {"type":"created","roomId":"SA6ABK","stunUrl":"stun:47.238.144.66:3478",
                 "turnUrl":"turn:47.238.144.66:3478?transport=udp","turnUsername":"demo",…}
0.023s [B] send {"type":"join","roomId":"SA6ABK"}
0.027s [B] recv {"type":"joined","roomId":"SA6ABK","peerId":"peer-002",…}
0.029s [A] recv {"type":"peerJoined","peerId":"peer-002"}          ← 与 B 的 peerId 一致
0.029s [B] TCP destroy (NO close frame)                            ← 硬断开（无 close 帧）
STEP4 within 12s after hard-kill: A peerLeft count=0               ← 宽限期内在线方收不到 peerLeft
12.040s [B2] send {"type":"join","roomId":"SA6ABK"}
12.044s [B2] recv {"type":"joined","roomId":"SA6ABK","peerId":"peer-002"}   ← 同身份复位（席位保留）
12.045s [A] recv {"type":"peerJoined","peerId":"peer-002"}         ← 在线方收到 peerJoined
RESULT {"steps":[…],"ok":true}
```
⇒ 验收要求的活体四点（create/join、硬断开、宽限期内**无 peerLeft**、同身份 rejoin 得 `joined` 且在线方收 `peerJoined`）**全部第一手通过**；`seat_takeover` 以 §4.2 的计数增量 + 同 `peerId` 复用为证。

---

## 5. 契约偏差（errata）汇总

| ID | 偏差 | 依据（我第一手） | 影响面 |
|---|---|---|---|
| **D-1** | **重连口径**：doc/09 §6 仍写「重连等待 3 秒 / 最大重连次数 3」，实现为 **1/2/4/8 s 封顶 × 10 次 = 63 s** | `doc/09-*.md:386-387` vs `SignalingClient.kt:142-165`、`:681-705`、`ReconnectBudgetTest.kt:28-61` | 文档与实现不一致；按 doc 取值会短于服务端 90 s 宽限期 ⇒ 用户可感知误退房 |
| **D-2** | **旋转语义**：doc/14:489 写 `rotationDegrees(frame.rotation)`、:490 写取回帧 `.setRotation(0)`；实现 **编码器未烘旋转**（`app/src/main/cpp/encoder/vp9_encoder.cpp:63 kBakeRotationInEncoder = false` ⇒ `:531-533 rotation = 0`、`:547-548` 不做宽高交换），而 Kotlin 侧仍传 `normalizeRotation(frame.rotation)`（`Vp9VideoEncoder.kt:204/422`）并对输出帧 `.setRotation(0)`（`:371-374`，注释称"旋转已烘进像素"）。**注**：`doc/14:523-529` 与 native 注释明确"必须烘进像素"，与 flag=false 相反 | **两端语义都丢**：像素未旋转 + 输出帧标注 rotation 0 ⇒ **真机 rotation 非 0 时远端画面可能整体旋转 90/270**（真机未测，见 §6）；属**既有**偏差（t46 引入 flag），t75/t76 未触碰 |
| **D-3** | **coturn 配置名**：doc/01:51 与 doc/14:790 仍写 `use-fingerprint`；实际 4.6.1 只认 `fingerprint` | `deploy/turnserver.conf:12-13`（含"coturn 4.6.1 不认识该名（Bad configuration format）"说明） | 按文档重装 coturn 会**配置报错/不生效**（fingerprint 校验缺失） |
| **D-4** | `doc/14:692`「rotation 已烘进 I420」被上游源码证伪（`TextureBufferImpl.java:110-114` 无旋转；朝向由 `VideoFrameDrawer.java:204 preRotate(frame.getRotation())` 决定） | `doc/14:692`（sha 仍 `b3b67438…`，未改）；已登记于 `reports/99 §14`（t43 轮） | 文档误导采集层实现（t39 已按正确口径修复，doc 未改：**冻结件，只登记**） |
| **D-5** | **部署不可从仓库复现**：仓库内 `deploy/signaling.service` **不含** `-room-grace 90s`，而运行实例以 90 s 工作 | `deploy/signaling.service`（tracked，ExecStart 无该 flag）vs `/healthz roomGraceSec=90` + `reports/38` §3（宿主 unit 追加） | 按仓库 unit 重新部署 ⇒ 回到旧行为（无宽限期）；建议把该 flag 回写仓库 unit |
| **D-6** | **下载面发布件不在仓库**：`parts/`、`SHA256SUMS`、`SOURCE.sha256`、发布脚本与 `/opt/apk-http/README.md` 均在仓外（t77 固化于宿主）；`signaling/dist/` 亦被 `.gitignore:50` 忽略 | HTTP `GET /parts/SOURCE.sha256` = **404**；`git check-ignore signaling/dist/signaling-linux-amd64` 命中 `.gitignore:50` | 下载面与二进制**无法仅凭仓库重建**（依赖宿主状态）；属"可接受但须登记"的复现性缺口 |

---

## 6. 逐任务汇总（本阶段，t67 → t77）

| 任务 | 目标 | 关键改动（file:line） | 我方证据 | 未验证项 |
|---|---|---|---|---|
| **t67** | 服务端房间/席位宽限期，瞬断不退房 | `signaling/main.go:45 flag("room-grace", 默认 90s)`、`:95 room_grace_ms` 日志；`signaling/server/server.go:104 StatsGrace()`、`:117-119 /healthz roomGraceSec/graceExpired`；`signaling/room/manager.go` | **第一手**：`/healthz roomGraceSec=90`；活体硬断开 12 s 内 **peerLeft=0**；同身份 rejoin 成功 | 90 s 期满后的退房时序（我只验证"宽限期内不误报"，未等满 90 s）；`graceExpired` 语义未实测 |
| **t68** | 消除"信令断即退房 / ICE 误报" | `SignalingClient.kt:699/744 disconnectCause = SIGNAL_LOST`、`:808 SIGNAL_LOST(survivable=true)`；`CallSurvivability.kt:105-106 signalLostAction`、`:185-190 shouldSurfaceError`；`CallViewModel.kt:754-782` 分派、`:1218-1219` 抑制 | **第一手**：五条代码级取证之 ⑤（含 `mediaAlive` 门控与文案抑制） | 真机断网 63 s 后 UI 是否停在通话页、是否真的不弹 ICE 失败文案 |
| **t70** | t67 修复编译并部署到宿主机 signaling | 产物 `signaling/dist/signaling-linux-amd64`（`8708629e…`，8 166 823 B）；宿主 `/opt/signaling/signaling` 替换 + unit 追加 `-room-grace 90s` + `/healthz` 新字段 | **第一手**（可及面）：repo dist 哈希 = 声明安装值；`/healthz` 显示 `roomGraceSec=90`、`version 0.1.0`、uptime 递增；**偏差 D-5** | 宿主 `/opt/signaling/signaling` 与 unit 文件本体（容器不可见）；MainPID/重启次数 |
| **t71** | 重连预算复用 rejoin 口径（去平行常量） | `SignalingClient.kt:120-165`（`REJOIN_RETRY_MAX_MS=8_000`、`MAX_REJOIN_ATTEMPTS=10`、`rejoinDelayMs`、`rejoinBudgetMs`）、`:681-705 scheduleRejoinRetry` | **第一手**：63 s 预算算式与生产接线；**APK dex 内 `MAX_RECONNECT_ATTEMPTS`=0**、`rejoinBudgetMs`=2 | 真机长抖动（>63 s）是否确实进入 `SIGNAL_LOST` 而非直接退房 |
| **t74** | 预算断言收紧到 `< 75 s` | `app/src/test/.../ReconnectBudgetTest.kt:50 (63_000)`、`:52 (< 75_000)`、`:58 (10 次)`；`grep 90_000`=0 | **第一手**：断言文本与取值；单测 18/180/0 含该类 5 例 | old-red 对照由 t74 自行提供（我未复跑离线 kotlinc） |
| **t75** | ICE restart 顺序 + 防 glare + 8 s 兜底 | `CallViewModel.kt:1332-1361`（顺序）、`:859-875`（Joined 只等）、`:1298-1301`（host 闸门）、`:284-296` + `:1376-1388`（兜底评估/自锁）；`CallSurvivability.kt:147`、`:162-173` | **第一手**：五条代码级取证之 ①②③；APK dex 内 `initiator=host/rejoiner`、`restart_ice`、`offer_timeout` 均在 | **offer 实测确实携带 `iceRestart`**、双端是否无 glare、8 s 兜底是否误触 —— 需真机/双端抓包 |
| **t76** | 用 t75 源码第三次重编并发布 | 产物 = `ff93c2e4…`（33 436 269 B）；固定件 jar/aar/libjingle/libc++ 不变；四 `.so` p_align 0x4000 | **第一手**：公网拉包 = 构建输出 = 归档 = 锚点；165 条目；14 dex 全表；四 `.so` p_align；`MAX_RECONNECT_ATTEMPTS`=0；18/180/0；`5b7efe0` + clean | 宿主 `parts/`、`SOURCE.sha256`、served 文件本体（**均为非第一手**，见 §10） |
| **t77** | `/opt/apk-http` 属主移交 + 发布提交点固化 | 宿主 `/opt/apk-http/README.md`（t77 记录 sha `566d3c36…`）与发布脚本（仓外）；t76 按"`SOURCE.sha256` 最后写"发布 | **间接第一手**：公网 `served` 已 = 新锚点、服务 HEAD 200/Range 206；**偏差 D-6**（脚本/README 不入仓） | 脚本执行日志与 README 本体（仓外，我不可见） |

---

## 7. 真机待验证清单（**不得写成已验证**）

1. **ICE restart 顺序**：断线重连后 `PeerJoined` 侧发出的 offer **确实携带 `iceRestart`**（抓包/SDP 比对），且旧候选对被正确替换；
2. **无 glare**：双端同时重连时只有在线侧发 offer，重连侧只 answer（无 offer/offer 冲突、无 rollback）；
3. **8 s 兜底不误触**：健康通话（媒体新鲜 + selected pair）期间**不发生**兜底 offer；仅"信令已恢复但画面黑"时触发一次；
4. **长抖动不自退**：断网 30–90 s 后恢复，App **不退房**、`SIGNAL_LOST` 走 `KEEP_CALL` 分支，重连后自动补 offer/answer；
5. **对端退出回等待态**：真实 `leave`/宽限期满后的 `peerLeft` ⇒ 停表、回"等待对端"态，可再次入会；
6. **画面旋转**：远端画面朝向是否正确 —— **D-2 的直接后果**（`kBakeRotationInEncoder=false` + 输出 `setRotation(0)`），必须在真机确认（本地预览朝向、远端画面朝向各测一次）；
7. 既有未验证面（承接 t43）：`JNI_OnLoad` 运行期注册、Camera2 首帧、日志导出、`http://47.238.144.66:8080/app-debug.apk` 在手机上的安装与首呼。

---

## 8. 结论（verdict）

- **verdict = pass**（就本任务验收面）：产物身份、构建证据、五条代码级主张、服务端现网与活体行为**逐条第一手复现**；**未发现"声称与事实不符"**（t76 的差异声明、18/180/0、四方可及面一致性均被独立复现）。
- **登记而非阻断**：§5 的 D-1…D-6 全部为**文档/复现性偏差**，其中 **D-2（旋转语义）**与 **D-5（仓库 unit 缺 `-room-grace`）** 具**实质风险**，建议各开一个小任务（D-2 决定"烘旋转"或"把 rotation 传到输出帧"，D-5 把 flag 回写仓库 unit）。
- **不通过面（未验证）**：§7 全部真机项 + §10 列出的宿主侧不可见面 —— 均不得写作已验证。

---

## 9. 证据索引（可复跑）

| 证据 | 说明 | sha256 |
|---|---|---|
| `tmp/vfy78/pub.apk` | 公网全量下载件 | `ff93c2e4…8871` |
| `tmp/vfy78/head.txt` / `rng.hdr` | HEAD 响应 / Range 206 响应头 | `851714fb…4ab435` / `6903dc14…1203fe` |
| `tmp/vfy78/p0..p3` + `rec.apk` | 四段 Range 取回与拼接（= 锚点） | p0 `bc3837d9…`、p1 `a1c4f880…`、p2 `29958cd0…`、p3 `f127a129…`、rec `ff93c2e4…8871` |
| `tmp/vfy78/apkscan-pub.log`（+ `apkscan.go`） | Go 独立解析：165 条目/14 dex/四 `p_align`/dex 字面量 | `db5b8d8e…d23c413` |
| `tmp/vfy78/cmpt72-t76.log`（+ `cmpzip.go`） | t72↔t76 逐条目对照（158 同 / 7 异） | `7d7caa09…59171f9` |
| `tmp/vfy78/replay.log`（+ `replay.mjs`） | 活体重放（自有 RFC6455 客户端，硬断开） | `d27f75b2…d8434f5` |
| `tmp/vfy78/healthz-after.json` | 重放后 `/healthz`（`seatTakeovers 3→4`） | `3242bb4d…7806b7a` |

**命令口径**：`curl`（HTTP/HEAD/Range）、`perl -MIO::Uncompress::Unzip`（备用解包路径）、`GOFLAGS=-mod=mod GOPATH/GOCACHE/GOTMPDIR 全在工作区内 go build`（`go run` 因 `/tmp` 不可执行而失败，改用先 `go build -o`）、`llvm-readelf`（NDK 外另存于 `webrtc-build/src/third_party/llvm-build/Release+Asserts/bin/llvm-readelf`）、`node v24.21.0`（全局 `WebSocket` 未用于重放，重放用 raw `net`）。

## 10. 限制与不可及面（如实登记）

1. **宿主文件面不可见**：`/opt/apk-http/**`、`/opt/signaling/**`、`/etc/systemd/system/signaling.service`、`/var/log/signaling/**` 在容器内不存在 ⇒ `parts/` 实际分片、`SOURCE.sha256`、`served` 文件本体、安装二进制与 unit 文本**均为非第一手**（仅能用 HTTP 面与 `/healthz` 反证）。
2. **`app/build/**` 为易失目录**：18/180/0 与 XML 属时点读数（01:39）；归属由入库日志承担。
3. **未复跑构建**（任务明令禁止）：四段命令的 EXIT=0 与 `FROM-CACHE=0` 引自 `reports/37-t76-*`（成员产出），本报告只验证其**产物侧结果**（APK 身份、dex 形状、固定件哈希、XML 计数、提交与树）。
4. **未跑离线 kotlinc 复跑**（t74 的 old-red 对照）：接受其报告文本，未独立复现。
5. 容器内**无 `bc`**：契约 `Verify` 命令中的 `paste -sd+ | bc` 无法直接运行，我以 `awk` 求和替代（结果等价：180 / 0）。
