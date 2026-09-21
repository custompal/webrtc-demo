> **中文（默认）** · [English](../07-build-and-deploy.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 07 — 构建与部署

> Status: draft · Owner: writer-ops · Task: t5
> Evidence base: reports/37-t76-build-publish.md, reports/37-t72-build-publish.md, reports/41-apk-http-ownership.md, reports/26-libvpx-runtime-cpu-detect.md, reports/10-app-build.md, doc/design/_generated/host-commands.md
> Doc standard: `doc/design/SPEC.md`

## 1. 范围

本文件说明 Android 应用与 Go 信令（signaling）二进制如何构建、如何证明该构建是真实构建，以及产出的
APK 如何发布到主机服务。

它刻意不覆盖：应用各层的设计（见应用架构文档）、信令（signaling）协议（见协议文档），或缺陷史（见
问题文档）。

本文件反复出现的一个区别是**命令在哪里运行**。构建脚本是主机专用脚本；容器里没有主机工作区树
（HOST: `/opt/dsh-workspaces`）。因此下文每条命令都标明其执行位置。

## 2. 工具链版本

| 组件 | 版本 | 引用 |
|---|---|---|
| JDK | 17 | `../env.sh:37` |
| Gradle wrapper | 8.7 | `gradle/wrapper/gradle-wrapper.properties:3` |
| Android Gradle Plugin | 8.5.2 | `build.gradle.kts:7` |
| Kotlin | 2.0.21 | `build.gradle.kts:8` |
| Android NDK | 26.1.10909125 | `../env.sh:42` |
| CMake | 3.22.1 | `../env.sh:45` |
| Go | 1.22 | `signaling/go.mod:3` |

`../env.sh` 相对工作区根解析（在仓库之外）。Gradle wrapper 版本固定在 wrapper 属性文件中，因此
`./gradlew` 不依赖系统 Gradle。

libvpx 构建显式启用运行时 CPU 分派，而不是依赖 configure 默认值。configure 行在
`scripts/t5-libwebrtc-libvpx-build.sh:295` 显式启用运行时 CPU 检测，而不是依赖默认值；其理由记录在
`reports/26-libvpx-runtime-cpu-detect.md`。

## 3. 构建阶段及其门禁

下面四个阶段就是发布配方。阶段 3 与阶段 4 连同实测到的门禁输出，引在
`reports/37-t76-build-publish.md:43` 与 `reports/37-t76-build-publish.md:44`。

### 3.1 阶段 1 —— 仅预检

- HOST: `bash scripts/build_app.sh --check-only`
  `--check-only` 定义在 `scripts/build_app.sh:18`，只做预检而不构建。

### 3.2 阶段 2 —— Kotlin 提前失败

```
./gradlew --no-daemon :app:compileDebugKotlin -PwebrtcDemo.skipNative=true
```

`-PwebrtcDemo.skipNative=true` 设在 `scripts/build_app.sh:317`，任务调用在
`scripts/build_app.sh:327` 与 `scripts/build_app.sh:330`。本阶段存在的意义是让 Kotlin 错误在昂贵的
原生构建之前暴露。此处失败会中止流水线：前一阶段失败时跳过阶段 3
（`scripts/build_app.sh:360`）。

### 3.3 阶段 3 —— 禁用缓存的打包

```
./gradlew --no-daemon --no-build-cache clean assembleDebug
```

门禁值：`BUILD SUCCESSFUL`、**零**行 `FROM-CACHE`、43 个任务（42 个已执行 + 1 个最新），以及非零
的原生编译任务数。注意单独的 `assembleDebug` 在 `scripts/build_app.sh:363`；`clean` 与
`--no-build-cache` 两部分是发布报告记录的配方。

### 3.4 阶段 4 —— 单元测试，全部任务重跑

```
./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest
```

门禁值：`BUILD SUCCESSFUL`，**24 个任务、24 个已执行** —— 不得有任务取自缓存。

### 3.5 阶段 5 —— Go 信令二进制

脚本为 `linux/amd64` 构建信令（signaling）服务（`scripts/build_app.sh:377`）。Go 工具链是
Go 1.22（`signaling/go.mod:3`）。`--skip-go`（`scripts/build_app.sh:19`）跳过本阶段。

### 3.6 主机专用脚本，与容器等价的步骤

`scripts/build_app.sh:33` 硬编码主机工作区根（HOST: `/opt/dsh-workspaces`），脚本在该根下于
`scripts/build_app.sh:62` source 环境。容器的工作区根是另一个目录，因此**该脚本不在容器中运行**；
它是主机脚本。

阶段 2–4 的与容器等价步骤就是上面三条 Gradle 命令，在 `../env-container.sh` 提供 `ANDROID_HOME` 与
`GRADLE_USER_HOME` 之后执行（`../env-container.sh:31`、`../env-container.sh:38`）。

## 4. 构建不变量

| 不变量 | 执行方式 | 引用 |
|---|---|---|
| JNI 桥接库必须与其固定哈希一致 | 期望的 sha256 常量 | `scripts/build_app.sh:467`, `scripts/build_app.sh:468` |
| C++ 运行时库必须与其固定哈希一致 | 同一固定值对循环 | `scripts/build_app.sh:469` |
| APK 内的库必须与期望哈希逐字节一致 | 解压并比对哈希 | `scripts/build_app.sh:469`, `scripts/build_app.sh:473` |
| 构建产物必须归 uid 1000 所有 | 运行结束时递归 chown | `scripts/build_app.sh:566`, `scripts/build_app.sh:567` |
| 构建窗口内 `app/src` 或 `signaling/` 发生变化，则该构建无效 | 作废构建；不得发布 | 见发布链报告 |

归属归一化最后运行，在所有产物落盘之后，因为较早的阶段以当前用户创建文件
（`scripts/build_app.sh:566`）。

参考：`scripts/build_app.sh`

## 5. 发布链

发布工具是主机侧脚本，**不**属于本仓库。发布记录直接说明了这一点：发布步骤从来不属于构建脚本
（`reports/10-app-build.md:1093`）。

- HOST: `bash /opt/apk-http/publish_apk.sh --apk <build output> --log <log file>`
  规范调用（含身份守卫）记录在 `reports/37-t76-build-publish.md:100`。

### 5.1 身份守卫

发布器拒绝以非 uid 1000 的用户运行。以 root 运行且不带显式豁免标志时，它打印原因并以状态 3
退出，不产生任何写入（`reports/41-apk-http-ownership.md:192`）。
豁免标志 `--allow-root` 绕过守卫，但会打印警告并要求后续修复归属
（`reports/41-apk-http-ownership.md:193`）。实测退出码记录在报告中：不带该标志的 root 退出码为 3
（`reports/41-apk-http-ownership.md:198`）。

### 5.2 冻结、分片与对账

该链冻结对外提供的副本，把 APK 切成固定大小的分片并附 `SHA256SUMS` 清单，最后提交
`SOURCE.sha256`，然后对四份独立哈希读数做对账（`reports/37-t72-build-publish.md:136`）。四方对账见
`reports/37-t72-build-publish.md:138`；对外提供的副本、拼接后的分片与 `SOURCE.sha256` 都必须等于
刚构建出的 APK。

公开下载端点按同一锚点验证：一次完整下载加一次范围请求，其中范围请求返回 HTTP 206 与所请求的
字节数（`reports/37-t72-build-publish.md:156`）。

### 5.3 回滚

回滚恢复两层：从冻结归档替换对外提供的副本，并从同一制品恢复标准构建输出路径，然后重跑对账。
具体的回滚命令与锚点哈希记录在 `reports/52-release-closure.md` 与
`reports/37-t72-build-publish.md`。因为制品本身是未跟踪的构建产物，仓库只能固定它们的哈希与记录
它们的报告 —— 而不是字节。

## 6. 主机服务

主机上运行三个服务。从容器内只能读取仓库中的副本。

| 服务 | 单元或配置 | 关键细节 | 引用 |
|---|---|---|---|
| signaling | `deploy/signaling.service` | `ExecStart` 把宽限期固定为 `90s` | `deploy/signaling.service:28` |
| coturn | `deploy/turnserver.conf`, `deploy/coturn.service` | STUN/TURN 在端口 3478 | `deploy/turnserver.conf:4` |
| apk-http | 主机侧静态文件服务 | 发布就地替换对外提供的文件，不重启 | 见发布报告 |

宽限期被显式写进单元，而不是依赖二进制默认值，因此默认值变化时已部署行为不会漂移
（`deploy/README.md:175`）。

coturn 强制执行对端地址策略：显式拒绝回环与链路本地网段（`deploy/turnserver.conf:28`、
`deploy/turnserver.conf:30`），启用带具名用户的长期凭证机制（`deploy/turnserver.conf:14`、
`deploy/turnserver.conf:15`），并把总配额降到 45（`deploy/turnserver.conf:16`）。监听端未启用 TLS。

参考：`deploy/signaling.service`, `deploy/turnserver.conf`, `deploy/README.md`

## 7. 事件处置

| 事件 | 处置 | 证据 |
|---|---|---|
| 构建窗口内 `app/src` 或 `signaling/` 下的源码被修改 | 构建作废、不得发布；从干净窗口重建 | 见发布收尾报告 |
| 构建期间树中出现混合或外来制品 | 隔离它们并记录两侧哈希，而不是覆盖 | 见发布收尾报告 |
| 对外提供的副本与分片快照不一致 | 重跑分片步骤与四方对账；公开下载保持可用 | `reports/37-t72-build-publish.md:180` |
| 发布以错误的用户运行 | 守卫以退出码 3 退出且不写入；以 uid 1000 重跑 | `reports/41-apk-http-ownership.md:192` |
| 已发布的锚点必须撤回 | 从冻结制品恢复对外提供的副本与构建输出，然后重新对账 | 见发布收尾报告 |

混合制品与归属两类事件在 `reports/52-release-closure.md` 中有详细记录，包括一次部分发布使对外提供的
副本领先于分片快照的情形（`reports/37-t72-build-publish.md:180`）。

## 8. 证据索引

| 声明 | 引用 | 验证产物 |
|---|---|---|
| 工具链版本 | `../env.sh:37`, `build.gradle.kts:7`, `signaling/go.mod:3` | 环境与 Gradle 脚本 |
| 提前失败阶段 | `scripts/build_app.sh:330` | 构建脚本 |
| 打包门禁（零缓存命中） | `scripts/build_app.sh:363` | `reports/37-t76-build-publish.md:43` |
| 测试门禁（全部任务已执行） | — | `reports/37-t76-build-publish.md:44` |
| 库哈希固定值 | `scripts/build_app.sh:467` | 构建脚本 |
| 归属归一化 | `scripts/build_app.sh:566` | 构建脚本 |
| 发布不是仓库内脚本 | — | `reports/10-app-build.md:1093` |
| 身份守卫 | — | `reports/41-apk-http-ownership.md:192` |
| 分片与对账 | — | `reports/37-t72-build-publish.md:136` |
| 公开范围请求 | — | `reports/37-t72-build-publish.md:156` |
| 宽限期固定在单元中 | `deploy/signaling.service:28` | `deploy/README.md:175` |

## 9. 待办事项

- `scripts/build_app.sh` 无法在容器中运行，因为工作区根硬编码在
  `scripts/build_app.sh:33`。这是已知限制（known limitation），不是构建的缺陷；与容器等价的
  Gradle 步骤见 §3.6。一个后续改进是接受来自环境的工作区根。
- 发布脚本是主机专用且在容器内不可见，因此它的行为来自报告而非源码。§5 中的声明因此在容器内是
  `unverified`，并已如此标注。
- §4 中固定的库哈希是写作时记录的值；工具链刷新会改变它们，并需要更新文档
  （`scripts/build_app.sh:467`）。
- 发布链的二级证据来自某次具体发布运行；之后的运行记录在其它 `reports/37-*` 文件中，查询当前
  锚点时必须查阅它们。
