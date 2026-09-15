# t69：用 t67+t68 修复重编交付 APK 并刷新公网下载快照

- 执行者：**env-installer**（attempt 1 / `82d0494b-e9fa-4cf5-bcaf-fa0f4325b999`）
- 执行时刻：**2026-09-16 00:12:49 → 00:22:16 (+0800)**（宿主机）
- 口径来源：照抄 `reports/10-app-build.md §9.16–§9.19` 的四次串行命令；并复用 captain 脚本框架 `/opt/dsh-workspaces/tmp/t47b-captain-{build,publish}.sh`（**已按本轮参数化**：日志名改 `10-t69-*`、`PREV` 不再硬编码 `30c41ac9…` 而取**当时 served 现值**）
- 构建输入：`HEAD=8563add`（t65）＋工作树在途修复（t67/t68 等，`dirty=87 行`）
- 原始日志（仓内，受控路径）：`reports/10-t69-build.log`；主机原始分步日志：`/opt/dsh-workspaces/tmp/t69/{checkonly,compile,assemble,test}-<TS>.log`

## 1. 开工前置（静默门禁）

| 项 | 实测 |
|---|---|
| java/gradle 进程 | **0** |
| `app/src` 近 5 分钟写入 | **0** |
| `signaling/` 近 5 分钟写入 | **0** |
| `app/src` 最新写入 | 23:53:46（`MediaAliveSuppressionTest.kt`，早于 T0 约 19 分钟） |
| `signaling/` 最新写入 | 23:45:10 |

## 2. 四次串行命令（全部 `--no-daemon`；3/4 步 `--no-build-cache`）

| # | 命令 | EXIT | 关键摘要 |
|---|---|---|---|
| 1 | `bash scripts/build_app.sh --check-only` | **0** | 尾部「前置校验全部通过（未构建）」；FAIL 行 0 |
| 2 | `./gradlew --no-daemon --no-build-cache -PwebrtcDemo.skipNative=true :app:compileDebugKotlin` | **0** | `BUILD SUCCESSFUL in 1m 39s`；15 tasks（1 executed / 14 up-to-date）；`^e:` 0 |
| 3 | `./gradlew --no-daemon --no-build-cache clean assembleDebug` | **0** | `BUILD SUCCESSFUL in 2m 52s`；**FROM-CACHE 行数 = 0**；`> Task :app:clean` 出现 = 1；43 tasks（42 executed / 1 up-to-date） |
| 4 | `./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 4m 30s`；**24 actionable tasks: 24 executed**；XML 汇总 **tests=165 / failures=0 / errors=0 / skipped=0**（XML 最新 mtime 00:21:58） |

**构建窗口内写入（T1=00:14:33 → T2=00:17:27）**：`app/src` = **0**；`signaling/` = **0**。

## 3. 固定件 T0/T2 双钉（须逐位不变）

| 固定件 | T0（00:12:49） | T2（00:17:27 构建后） | 判定 |
|---|---|---|---|
| `third_party/libwebrtc/java/libwebrtc-java.jar` | `0c776934…63dc757` | 同值 | ✅ |
| `third_party/libwebrtc/java/libwebrtc-arm64.aar` | `8e8f2baf…8fd4a099` | 同值 | ✅ |
| `jniLibs/arm64-v8a/libjingle_peerconnection_so.so` | `757cef81…c233259e` | 同值 | ✅ |
| `jniLibs/arm64-v8a/libc++_shared.so` | `c9dbf4ec…797d1e36` | 同值 | ✅ |
| 自有 `libwebrtcdemo_native.so`（APK 内） | — | **`d49eafc3e7cdeaa296579f039dfda75b0dc34b76885b173dc61e3c1a426212a5`** | 按设计（含 t45/t46 新 native 代码；与上一版 3190ef73 **相同**，本轮只改 Kotlin） |

## 4. 新 APK（**交付锚点**）

```
路径   : /opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk
sha256 : 8b58b3f0914021f124a0b86bdfca8350311ef4f26c2d297acfa9d041c16629d9
size   : 33,419,885 B
mtime  : 2026-09-16 00:17:26.029582955 +0800
```
（上一版 served = `3190ef73e3a836237580b9dd585795577adf03d51cb5c3aa99372d2f16a4531c`，33,419,885 B）

**APK 内四个 `.so` 的 `p_align` = 全 `0x4000`**（16 KB 页兼容未回退）：

| 条目 | sha256（前 16） | p_align |
|---|---|---|
| `libandroidx.graphics.path.so` | `41e9a793c43a0f4f` | `0x4000` |
| `libc++_shared.so` | `c9dbf4ec15e931f5` | `0x4000` |
| `libjingle_peerconnection_so.so` | `757cef8128bf9151` | `0x4000` |
| `libwebrtcdemo_native.so` | `d49eafc3e7cdeaa2` | `0x4000` |

**与上一版 APK 的条目级差异**：`165 → 165` 条目，**相同 158 / 不同 7**，无新增/删除条目；不同项 = `classes3/5/6/9/11/12/14.dex`（D8 分片字节差 + 本轮 Kotlin 改动）。四 `.so`、`resources.arsc`、`AndroidManifest.xml` 等**逐字节相同**。

## 5. 发布（冻结交付副本 + 分片 + 四方对账）

| 项 | 值 |
|---|---|
| 归档旧 served | `/opt/apk-http/artifacts/app-debug-3190ef73.apk`（`3190ef73…`） |
| 归档旧 parts | `/opt/apk-http/parts-archive/parts-3190ef73-20260916-002212`（6 件） |
| **served 冻结副本** | `/opt/apk-http/served/app-debug.apk` = **`8b58b3f0…629d9`**（等于新 APK，逐字节） |
| artifacts 归档 | `/opt/dsh-workspaces/artifacts/app-debug-8b58b3f0.apk` = **`8b58b3f0…629d9`** |
| `parts/` | part00–02 = 8,388,608 B ×3 + part03 = 8,254,061 B（**合计 33,419,885 B**）；`SHA256SUMS` 自校验 **4/4 OK**；`SOURCE.sha256` = **`8b58b3f0…629d9`** |
| **parts 拼接 hash** | `8b58b3f0914021f124a0b86bdfca8350311ef4f26c2d297acfa9d041c16629d9` → **CONCAT == 新 APK : True** |

**四方一致**：构建输出 = served 副本 = parts 拼接 = `SOURCE.sha256` 声明值 = `8b58b3f0…629d9` ✅

## 6. 服务与公网复验

```
enabled=enabled  active=active  MainPID=664402  启动时刻=Mon 2026-09-14 18:57:09 CST（未重启 ⇒ 刷零停机）
loopback HEAD : code=200
loopback Range: code=206 bytes=1024
public   HEAD : code=200
public   Range: code=206 bytes=1024
public 全量下载 sha256 = 8b58b3f0914021f124a0b86bdfca8350311ef4f26c2d297acfa9d041c16629d9  ✅ 与 served 一致
URL: http://47.238.144.66:8080/app-debug.apk
```

## 7. 属主归一

```
chown -R 1000:1000 app/build app/.cxx .gradle .kotlin
app/build root 条目 = 0 ; .gradle root = 0 ; app/.cxx root = 0
```

## 8. 未验证项（不得读作通过）

1. **真机侧**：本轮未安装/未运行；「不再误报 ICE 失败」「不再自动退房」「房间宽限期重连」均需用户真机复测（t67/t68 各自报告已列 U 项）。
2. **服务端宽限期**：`signaling/` 二进制的部署刷新**不在本任务范围**（本轮只重编 APK；宿主 `/opt/signaling/signaling` 是否已更新为含宽限期版本未验证）。
3. **构建输入的干净性**：本次构建基于 `HEAD=8563add` + **工作树 87 项在途改动**（t67/t68 等未提交）。若后续有人改动同一批文件，本锚点与新工作树不再对应。
4. **APK 非逐字节可复现**（历史已证）：同输入重编会产生不同 sha；本锚点身份以 sha256 为准。
5. 本轮**未做 git 提交**（任务纪律只允许写 `reports/37-t69-build-publish.md` 与 `reports/10-t69-build.log` 两个仓内文件，未授权提交）；如需要入库请另行指示。
