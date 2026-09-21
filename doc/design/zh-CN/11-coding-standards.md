> **中文（默认）** · [English](../11-coding-standards.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 11 — 编码规范与改动安全

> Status: draft · Owner: writer-ops · Task: t9
> Evidence base: current HEAD source tree, reports/20-encode-resize-crash.md, reports/26-libvpx-runtime-cpu-detect.md, reports/47-vp9-encode-perf.md, reports/51-frame-dropper-and-trusted-rc.md, reports/52-release-closure.md
> Doc standard: `doc/design/SPEC.md`

## 1. 范围

本文件陈述已交付代码已经遵循的约定，使新的改动与它保持一致，并陈述一次改动被认为可以安全落地之前
必须通过的检查。

它刻意不重述行为；那由架构与协议文档负责。它也不发明规则：下面的每条约定都由对当前 HEAD 源码的
引用，或由记录了该约定所防止的缺陷的报告来例示。没有这种引用的规则不属于这里。

## 2. 通用约定

| 规则 | 正例 | 理由 |
|---|---|---|
| 按职责组织代码，每个关注点一个包 | `signaling/server/server.go:47` | server 包只负责传输接线 |
| 让文件的入口显式且可 grep | `signaling/main.go:28`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` | 使文件从第一处声明起就可读 |
| 注释陈述契约或不变量，而不是机制 | `app/src/main/cpp/encoder/i420_rotator.h:95` | 该注释固定了代码所依赖的 stride 契约 |
| 注释引用促成某个非显然选择的缺陷或任务 | `app/src/main/cpp/encoder/vp9_encoder.cpp:67`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:29` | 下一个读者能找到历史 |
| Kotlin 格式遵循官方风格，只配置一次 | `gradle.properties:21` | 风格由配置强制，而不是靠评审 |
| Go 源码经 gofmt 格式化，且工具链版本被固定 | `signaling/go.mod:3` | 一种规范格式、一个编译器版本 |

复述代码的注释是负债：它会静默漂移，然后误导人。反例模式是描述已不存在的机制的注释；上面的引用
风格就是解法。

参考：`signaling/server/server.go`, `app/src/main/cpp/encoder/i420_rotator.h`, `gradle.properties`

## 3. Kotlin 与 Android

| 规则 | 正例 | 它防止的反例模式 |
|---|---|---|
| 状态机的副作用不得取代消息判定；在与门控该决定相同的锁下发布状态 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:366`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:368` | 读者会观察为已就绪的半发布对端连接 |
| 所有触及共享就绪状态的迁移都走同一个门控 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:812` | 使用不同锁的零散同步 |
| 就绪状态边界只保留一把锁并给它命名 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:160` | 必须以固定顺序获取的两把锁 |
| 可空结果在边界处处理，绝不强取 | `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:114`, `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:134` | 遇到畸形输入时崩溃，而不是拒绝该值 |
| 已释放的渲染器必须拒绝再次挂载，而不是被复用 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:230` | surface 渲染器的释放后使用 |
| 渲染器的挂载与释放都带显式决定记录地写入日志 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:236` | 只在黑屏预览时才暴露的静默重复挂载 |
| 日志事件名是稳定键；字段名用 snake_case | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:236`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523` | 无法被机械解析的字段名 |
| 数值字段带单位后缀 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1129`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1170` | 必须猜测单位的裸数字 |
| 高频日志做限流，并报告丢弃了什么 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:24`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:89` | 被淹没并失去诊断窗口的日志汇 |

门控模式是这里承重的规则。在门控之外修改就绪状态的迁移，会与一条按旧状态判定的入站消息交错；该
缺陷类别是「在对端连接发布之前就应答的通话」。抑制同样必须可见：被抑制的迁移会写入日志，而不是
被静默丢弃（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523`）。

参考：`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt`

## 4. C++ 与 JNI

| 规则 | 正例 | 它防止的反例模式 |
|---|---|---|
| 写入前检查直接缓冲的真实容量，使用容量访问器而不是假定的大小 | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:157`, `app/src/main/cpp/jni/vp9_encoder_jni.cpp:199` | 越界写入调用方提供的缓冲 |
| 通过 load 钩子注册 native，并在头文件中陈述注册契约 | `app/src/main/cpp/jni/jni_bridge.cpp:57`, `app/src/main/cpp/jni/jni_bridge.h:9` | Kotlin 声明与导出名之间的符号漂移 |
| 在每份平面描述中保留显式 stride，而不是假定紧凑布局 | `app/src/main/cpp/encoder/i420_rotator.h:43`, `app/src/main/cpp/encoder/i420_rotator.h:121` | 以错误偏移读取旋转或补齐过的帧 |
| 线程数与行级并行度是有记录理由的调优决定 | `app/src/main/cpp/encoder/vp9_encoder.cpp:251`, `app/src/main/cpp/encoder/vp9_encoder.cpp:392` | 默认值变更后静默的性能回归 |
| 每条失败路径都发出带输入的标识性日志行 | `app/src/main/cpp/encoder/vp9_encoder.cpp:395`, `app/src/main/cpp/log/native_log.cpp:61` | 没有可复现特征签名的崩溃 |

已记录两类缺陷，不得重新引入。第一，帧几何变化导致的编码器崩溃，成因是把一个它并不拥有的缓冲交给
libvpx；修复是拷贝进自有的图像，记录在 `reports/20-encode-resize-crash.md` 与
`reports/22-encode-selfowned-image.md`。第二，由构建标志造成的运行时 CPU 分派回归，记录在
`reports/26-libvpx-runtime-cpu-detect.md`；构建标志是契约的一部分，不是优化细节。线程化理由记录在
`reports/47-vp9-encode-perf.md`。

参考：`app/src/main/cpp/jni/vp9_encoder_jni.cpp`, `app/src/main/cpp/encoder/vp9_encoder.cpp`

## 5. Go

| 规则 | 正例 | 它防止的反例模式 |
|---|---|---|
| 包边界是横向的：传输、房间状态、协议词汇、配置、日志 | `signaling/config/config.go:18`, `signaling/protocol/errors.go:5`, `signaling/logging/logging.go:20` | 协议常量泄漏进传输代码 |
| 错误码是字符串常量的封闭词汇表，在一处映射到默认消息 | `signaling/protocol/errors.go:5` | 客户端无法据以分支的临时错误字符串 |
| 房间状态由单一互斥锁守护；每次修改都取它 | `signaling/room/room.go:43`, `signaling/room/room.go:67` | 交错更新席位导致丢失对端 |
| 定时器回调携带世代，世代已推进时回调被丢弃 | `signaling/room/room.go:24`, `signaling/room/room.go:75`, `signaling/room/room.go:89` | 过期回调摧毁一个被合法重连的房间 |
| 行为由紧挨代码的表驱动测试覆盖 | `signaling/util/roomid_test.go:9` | 未测试的解析规则 |

世代规则就是幂等规则：迟到的宽限定时器在行动前必须能证明它属于当前占用（`signaling/room/room.go:24`）。
没有它，一次与过期赛跑的重连会摧毁活着的房间；该修复及其证据记录在 `reports/35-room-grace.md`。

参考：`signaling/room/room.go`, `signaling/protocol/errors.go`, `signaling/util/roomid_test.go`

## 6. 脚本与构建

| 规则 | 正例 | 它防止的反例模式 |
|---|---|---|
| 构建与部署脚本快速失败 | `scripts/build_app.sh:31`, `scripts/deploy_signaling.sh:10` | 某阶段失败后继续并发布陈旧制品 |
| 脚本必须跑完每项检查时，失败记入累加器 | `scripts/build_app.sh:56` | 静默地根本无法运行的检查 |
| 构建窗口是静默的：构建期间 `app/src` 或 `signaling/` 下不得有任何源码变化 | 见构建与部署文档 | 对应不上任何单一修订的制品 |
| 运行结束前把产物归属归一化到 uid 1000 | `scripts/build_app.sh:566` | 下一个用户无法修改的 root 归属构建产物 |
| 工具链制品按哈希固定，且检查该固定值 | `scripts/build_app.sh:467` | 被静默替换的原生库 |
| 生成的表格由生成器产出，绝不手工编辑 | `doc/design/_generated/host-commands.md:3` | 下一次重新生成就会抹掉的手工编辑 |
| 发布工具是主机专用，绝不作为仓库内文件引用 | HOST: `/opt/apk-http/publish_apk.sh` —— 见 `reports/41-apk-http-ownership.md:192` | 承诺一个树中并不存在的脚本的文档 |

`scripts/doc-verify.sh:33` 刻意不使用 `set -e`：它必须跑完每项检查并汇总失败，因此提前退出会隐藏
其余发现。这是对快速失败唯一有意的例外，而它之所以安全，只是因为该脚本不执行任何写入。

参考：`scripts/build_app.sh`, `scripts/doc-verify.sh`, `scripts/deploy_signaling.sh`

## 7. 改动安全清单

改动之前：找出它触及的不变量（门控、库固定值、房间世代）；找到记录了该区域上一次缺陷的报告；
判断该改动是否改变构建标志，因为构建标志就是契约。

改动期间：保持构建窗口静默；不要编辑生成的表格；不要为了让文档满意而「修」代码 —— 应当更新文档
（`doc/design/SPEC.md` §7 A13）；若某条检查必须放宽，记录原因。

改动之后：对受影响的文档运行文档门禁；重新验证指向被改文件的引用；确认 `git status` 只显示预期的
路径；把证据记录在报告中。

## 8. 文档与代码一致性

引用使用 `path/file.ext:LINE` 形式，并按当前 HEAD 源码校验。`reports/**` 中的行号是历史值，绝不
得复用为源码引用；报告按名称与小节引用。生成的表格带有禁止编辑的头与它们的重新生成命令
（`doc/design/_generated/host-commands.md:3`）。

当文档与代码不一致时，代码是真相、文档要被修正；当文档与检查器不一致时，在其它任何东西改变之前
先把分歧报告给该标准的负责人。文档改动的交付门禁是限制在改动文件上的检查器
（`doc/design/SPEC.md` §9）。

### 8.1 文档路径写法（冻结）

路径写法是冻结的并由机器强制。文档集中写下的每个路径都属于五类之一，类别决定标记是必需、可选还是
禁止。

| 路径类别 | 正确形式 | 判定 |
|---|---|---|
| 仓库受跟踪文件 | 带行号的裸仓库相对路径；无标记 | 存在性与行号会被检查；断掉的指针失败 |
| 工作区根文件 | 裸路径，既可以带父目录前缀的仓库相对形式书写，也可以工作区相对形式书写 | 按工作区根检查；缺失文件失败 |
| 构建产物 | 裸路径 | 存在性可选；缺失产物记录为未验证（unverified）且从不失败，因此干净检出永远不会失败 |
| 主机绝对路径 | 同一行上的显式主机作用域标记，连同该行上的仓库内证据 | 未标记的主机路径失败；这是唯一强制的标记 |
| 容器绝对路径 | 裸绝对路径，任何行号写在周围行文中而不是代码跨度内 | 按容器根检查（见 8.1.3） |

#### 8.1.1 标记纪律

除主机与设备作用域标记之外，不要写任何路径前缀标记。本文档集早期草稿使用的两个前缀已退役，规则是
改写成裸路径：检查器会把这两个前缀中的任何一个用作路径前缀都报告出来。

裸形式也是在写作会话的修订反复中唯一存活下来的形式。检查器对这两个前缀的结论变过两次 —— 失败，
随后是容忍的提示，然后在冻结修订上再次失败 —— 而裸路径在观测到的每个修订上都通过：

| 检查器修订 | 对两个退役前缀的结论 |
|---|---|
| 200ba92e42068c4a | 失败 |
| 6b21c41f7630dd6e, d2a95711dbedabf | 作为提示被接受，记录为注记 |
| 最终冻结修订（摘要见下） | 再次失败 |

标记只在两种用途上仍然合法：为主机绝对路径定作用域，以及为设备路径定作用域。其它所有路径都写成
裸形式。

#### 8.1.2 主机命令证据必须在同一行

无法从仓库自身证明的名称 —— 生成命令清单中未列为仓库内的任何东西 —— 必须在与名称**同一行**上
携带其仓库内证据：要么是下文证据索引所用形式的报告位置，要么是生成的
`doc/design/_generated/host-commands.md` 表格中的条目。相邻行上的证据不满足该规则。可从仓库脚本
证明的名称无需证据，生成的表格本身豁免。

这条规则是在交付已进入评审时才加入的，这就是构建与部署文档中有一处引用很长。标志 `--allow-root`
与其报告证据 `reports/41-apk-http-ownership.md` §5 原来在相邻行上，门禁失败并提示该标志被引用却
没有仓库内证据；把它们并入同一行解决了问题，没有增删任何内容。

#### 8.1.3 容器根

容器工作区根是 **P4** 类：其下的绝对路径按真实容器根做存在性检查，且无需标记。这与主机绝对路径
相反 —— 后者从不做存在性检查，却需要主机作用域标记。把两者混淆是「未标记主机路径」误报最常见的
来源。

#### 8.1.4 修订绑定的证据

关于某种形式通过或失败的陈述，是关于检查器某一个修订的证据，绝不是关于规则的陈述。每条此类陈述
都必须携带检查器的完整 SHA-256 摘要及其修改时间、精确的重跑命令，以及原始退出码与失败、警告计数。

不带该指纹引用的结论无效：它不得被复用，也不得被重述为规则。当检查器变化时，在重复任何结论之前
先重跑探针，因为同一形式已被观测到在不同修订之间翻转结论。与其在行文中重述约定，更宜点名探针文件
与其期望结果，使任何读者都能直接对照 `tmp/probe-forms-matrix-writer-ops.md`（在工作区根、仓库
之外）复核该主张。

迁移说明：`reports/**` 下的历史报告与 `doc/archive/**` 下的归档文档含有已退役的路径前缀与不再有效
的 `file:LINE` 形式。不要把它们复制进新文档。按上面所述的裸形式改写每个路径，并按名称与小节引用
报告，而不是把其行号复用为源码引用。

```
final frozen checker revision for this notation:
sha256 676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45
carried by commit 8fdb222 (recompute: git show 8fdb222:scripts/doc-verify.sh | sha256sum)
```

## 9. 证据索引

| 规则领域 | 引用 | 验证产物 |
|---|---|---|
| 就绪状态门控 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:812` | 源码 |
| 渲染器生命周期 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:230` | 源码 |
| 日志限流 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:89` | 源码 |
| 直接缓冲容量 | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:157` | 源码 |
| native 注册 | `app/src/main/cpp/jni/jni_bridge.cpp:57` | 源码 |
| 行级并行度 | `app/src/main/cpp/encoder/vp9_encoder.cpp:392` | `reports/47-vp9-encode-perf.md` |
| 房间世代 | `signaling/room/room.go:75` | `reports/35-room-grace.md` |
| 错误词汇表 | `signaling/protocol/errors.go:5` | 源码 |
| 快速失败脚本 | `scripts/build_app.sh:31` | 源码 |
| 库固定值 | `scripts/build_app.sh:467` | 源码 |
| 归属归一化 | `scripts/build_app.sh:566` | 源码 |
| 发布守卫 | HOST: `/opt/apk-http/publish_apk.sh` | `reports/41-apk-http-ownership.md:192` |
| 文档路径写法（冻结） | `doc/design/SPEC.md` §2.3, `doc/design/SPEC.md` §7.1 | §8.1 陈述的固定检查器修订 |

## 10. 待办事项

- C++ 没有签入的格式化配置：仓库根与应用模块中都没有 clang-format 文件。因此 C++ 风格只是按约定
  从周围的第三方源码继承，这在机械意义上是 `unverified`。加入一份配置会让该规则可强制。
- 用于验证的 Go 测试调用没有被记录在单个脚本中，因此单元测试背后的精确命令集仅凭仓库是
  `unverified`。
- §3 的约定派生自调用路径；未来把就绪状态边界移到另一个文件的重构必须更新这里的引用，因为门控模式
  只有连同定位它的引用一起才有意义。
- 本文件引用当前 HEAD 行号。按该标准，它们是引用指针：移动代码的改动要更新本文件，而不是改代码。
