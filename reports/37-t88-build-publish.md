# t88 — 第七次构建发布（**中止：未发布**）

> **【已作废】**（captain 2026-09-16 20:3x 指令追加）
> 本任务作废，理由：**构建窗口内 t89/t91 于 20:12:34、20:16:05 改动 `app/src`**，输入非冻结集；构建**未发布**，`served` 未被本任务触碰；统一构建改由 **t90**（t87 + t89 + t91 前置）完成，现役锚点 `0f60d90138f6f7bde0f583d0626cbcd0050564287d56fd47d022467a80cafe45`。
> 实测补充（与 20:16:05 的差异，如实登记）：我 20:32:53 在宿主机实测 `app/src/main/kotlin/…/encoder/Vp9VideoEncoder.kt` 的 mtime = **20:15:38.476**（sha256 `2fb3a425d92c9323df573f09520865e63fac607dc0fe21a42f7506d5269e5be2`），未观测到 20:16:05 的另一次写入；两时刻均早于 t90 的构建窗口（20:23:26–20:26:01），故不影响 t90 覆盖该改动。

- 执行者：env-installer｜任务：t88（work，deps=[t87]，attempt 1，attempt_id `178e36fb-0288-4511-abdb-47f3353d0333`）
- 时间：2026-09-16 20:07 → 20:16（+0800）
- 结论：**ABORT —— 构建窗口内源码被第三方改写（3 件），输入不连贯 ⇒ 按纪律不发布；现役锚点未变动。**
- 状态：任务在平台侧为 **failed（终态不可改）**；captain 若需改为 `cancelled` 需 reassign 收回。

---

## 1. 中止原因（一句话）

`clean assembleDebug` 的构建窗口（**T1=20:09:49 → T2=20:12:27**）内，`app/src` 被写入 **3 件**（t89 的码率崩塌修复），且 git HEAD 在同一时段由 `8f51e8e` 前进到 `c1d06cf`（该提交把 **t87 + t89** 合并提交）⇒ 编译阶段读到的是旧树、测试阶段读到的是新树，**本次构建产物不是任何单一输入状态的快照**，不满足本任务验收项「构建窗口内 `app/src`/`signaling/` 写入=0」。

## 2. 时间线（全部为实测）

| 时刻（+0800） | 事件 |
|---|---|
| 20:06:52 | 我采样前置状态：静止（近 5min 写入 0、进程 0）；**T1 界 = 20:00:48**（t87 报告值，实测 `EncoderFallbackControllerTest.kt` 20:00:48.481）；HEAD `8f51e8e`（t85 提交），dirty 12 |
| 20:07:55 | 启动 t88 四段构建（脚本 `/opt/dsh-workspaces/tmp/t88-build.sh`，日志 `reports/10-t88-build.log`）；check-only EXIT=0；**静默断言通过**（非豁免写入 0、豁免 1） |
| 20:09:49 | 第 3 段 `clean assembleDebug` 的 **T1** |
| **20:11:12** | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9BitrateLimits.kt` 落盘（新建；sha256 `93dfba635e91934370d1eb846f15763299146ec9436553d465d9dac37154357d`） |
| **20:11:22** | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt` 落盘（`c1f30f58eae4d834d52eac77c58d4b448546b3768809e9cbb3f97cab7e2040f0`） |
| **20:11:44** | `app/src/test/kotlin/com/example/webrtcdemo/encoder/Vp9BitrateLimitsTest.kt` 落盘（`dcd6c2469b7e96919497b99478842e95f903c9d1036d55d08f8d3cd8f59a3378`） |
| 20:12:27 | 第 3 段 **T2**；脚本打印 **`构建窗口内 app/src 写入(>T1)=3`**（本应=0） |
| 20:12:27→20:15:04 | 第 4 段单测：XML **23 类 / 213 例 / 0 失败**（t87 报告为 **22 类 / 206 例**）⇒ 测试阶段已看到 t89 的新测试（+1 类 +7 例），进一步证明输入在构建中变化 |
| 20:15:20 | 我取证：三件新文件 mtime 均在窗口内；`git log` 显示 HEAD = `c1d06cf`（提交信息含“**t87 … + t89 修视频流码率崩塌根因**”）；`git status` 干净（三件已提交） |
| 20:16 | 决定中止：**不发布** |

## 3. 产物与状态（未发布）

| 项 | 值 |
|---|---|
| 本次构建产物（**未发布、应丢弃**） | `app/build/outputs/apk/debug/app-debug.apk` = **33,472,645 B** / sha256 **`f1eacbf4036c1ade0a5dcb27840cee746eaed776d8a19c440a0c8f47dea20298`** / mtime 20:12:26.394 |
| 混入证据 | 该 APK 的 dex 内 `bitrate_limit` = 1（t89 新增代码的部分字符串已进入），而 `Vp9BitrateLimits` = 0、`requested_bps` = 0 ⇒ 部分类编译、部分未编译，**典型的「编译中源码变动」形态** |
| **现役交付锚点（未变动）** | `/opt/apk-http/served/app-debug.apk` = **`48a04b2fa60f2941f7aec2c493be4cedcffce9432078c4deca00201f78796842`**；`parts/SOURCE.sha256` 同值；`parts/` 仍为 19:08 产出；`artifacts/` 未新增 |
| 服务 | `apk-http` 未被我触碰（本轮**未执行** `publish_apk.sh`） |
| 日志 | `reports/10-t88-build.log` = 105 行 / sha256 `1392b7984d1d7588ff58fe14e0c4745342cd4db549606cf21c5d74f1191d13bc`（含被中止运行的完整输出；注：该文件已被 captain 在 `c1d06cf` 中**提交**为 54 行版本，我的运行使其变为“已修改”） |

## 4. 已完成并通过的闸门（仅记录，不构成“已交付”）

| 检查 | 结果 |
|---|---|
| ① `--check-only` | EXIT=0（FAIL=0） |
| ② `compileDebugKotlin`（skipNative） | EXIT=0（1m49s / 15 tasks: 1 executed+14 up-to-date / 0 错误） |
| ③ `clean assembleDebug` | EXIT=0（2m37s / **FROM-CACHE=0** / `:app:clean` 出现=1 / 43 tasks=42 executed+1 up-to-date；native 痕迹 externalNativeBuild=2、ninja-cmake=2） |
| ④ `--rerun-tasks testDebugUnitTest` | EXIT=0（2m37s / 24 executed）；XML 23 类/213 例/0 失败（**≠ t87 报告 22/206**，正是输入漂移的信号） |
| 构建窗口写入 | ❌ **`app/src` 写入=3（>T1）**、`signaling/`=0 ⇒ **验收项不满足** |
| 静默断言（check-only 后） | ✅ 非豁免写入件数=0、豁免件数=1（jniLibs/libjingle 逐位 `757cef81…`） |
| T0/T2 四钉 | ✅ 逐位不变（jar `0c776934…`、aar `8e8f2baf…`、`libjingle 757cef81…`、`libc++_shared c9dbf4ec…`） |
| native `.so` | `043e851ac358d844…`（1,278,784 B）与上一锚点 `48a04b2f…` 内**逐字节相同** ⇒ t87/t89 均为 Kotlin 侧改动（符合预期；`.so` 内仍含 `encoder_perf`/`encoder_rates`/`encoder_rate_floor`/`encoder_threads`） |
| 四 `.so` p_align | 全 `0x4000` |

## 5. 需 captain 裁定的两件事

1. **重新派单口径**：请在下游静止后重派「第七次构建发布」。工作树现为**已提交的 HEAD `c1d06cf` = t87 + t89 合并提交**，因此重新构建将同时包含 **t87（自动降级兜底）与 t89（码率崩塌修复）** —— 交付标题/闸门需相应更新（t89 的实测预检值请由 t89 报告给出；本机实测基线为 **23 类 / 213 例**）。
2. **提交范围提醒**：`c1d06cf` 把我在跑、尚未完成的 `reports/10-t88-build.log`（54 行中间态）也一并提交了；重跑后该文件会被覆盖为最终版本（我会在重派任务里重新生成并如实标注）。
3. **待丢弃产物**：`app/build/outputs/apk/debug/app-debug.apk`（`f1eacbf4…`）为混入产物，**不得用于发布或真机**；如需我立即删除/覆盖，请指示（重跑时 `clean` 会自动覆盖）。

## 6. 未验证项

- 本轮**无新锚点**：没有可验证的交付物；上述 EXIT=0 与单测数字均来自一次输入漂移的运行，不应作为交付证据。
- 现役锚点仍是 **`48a04b2f…6842`**（t86，含 t83/t85 修复），其真机未验证项与 `reports/37-t86-build-publish.md` §9 相同。
