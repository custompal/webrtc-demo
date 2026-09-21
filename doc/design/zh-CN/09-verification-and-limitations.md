> **中文（默认）** · [English](../09-verification-and-limitations.md)
> 译文：若与英文原文冲突，以英文原文为准。

# 09 — 验证与限制

> Status: draft · Owner: writer-history · Task: t6
> Evidence base: `reports/42-delivery-verification.md`, `reports/52-release-closure.md`,
> `reports/99-final-report.md` §15.5, `reports/45-turn-exposure-accepted-risk.md`,
> `reports/27-encoder-direction-perf.md`, `reports/51-frame-dropper-and-trusted-rc.md`
> Doc standard: `doc/design/SPEC.md`

---

## 1. 范围

本文件陈述已交付系统实际验证了什么、每一项如何验证、存在哪些证据，以及什么**没有**被验证。它刻意
保守：只读过源码的条目不算已验证，从未在设备上执行过的条目列为未验证（unverified）并附重测方法。

在范围内：验证矩阵、已交付行为的已知限制、未验证条目清单，以及契约勘误汇总。

范围之外：缺陷史与被否决的方案，它们在 doc/design/08-issues-and-solutions.md 中；协议字段表，它们
是生成的；以及无法从容器内读取的主机运维文件。

本文档中每条门禁陈述都点名产出它的检查器摘要，因为检查器在写作阶段被反复修订（见 §2.2）。不带该
摘要的结论不可复现。

## 2. 验证方法及其自身的有效性

### 2.1 门禁

文档集由仓库脚本检查：

```bash
bash scripts/doc-verify.sh
```

编写任务把它限制在自己的文件上运行：

```bash
bash scripts/doc-verify.sh --only docs/08-issues-and-solutions.md docs/09-verification-and-limitations.md
```

检查器把每条 `file:LINE` 引用对当前工作树解析，检查被引用的符号能在被引用文件中 grep 到，验证文档
中使用的协议消息类型存在于生成表格中，解析相对链接，要求历史存根，并强制路径标注。

### 2.2 已知的检查器限制：它不是字节稳定的

| 陈述 | 证据 |
|---|---|
| 检查器在本文档集写作期间约六分钟内被修订三次，同一文档树上的失败数与检查数各不相同（`101 → 73 → 84` 次失败，`576 → 607 → 624` 次检查） | 独立复核者的重建，记录在 `reports/99-final-report.md` 的验证附录与 `reports/42-delivery-verification.md` §3 |
| 更早的一个修订在周围行文恰好含匹配英文词时，会接受一个必需的路径标签，因此普通措辞就能压掉一条发现 | 输入只差一个词的 A/B 探针对，记录在同一附录 |
| 后果：「门禁通过」的陈述只有连同检查器摘要一起才有意义 | 本文件 §2.3 |

### 2.3 本文档验证时使用的摘要

| 项目 | 取值 |
|---|---|
| 检查器 | `scripts/doc-verify.sh` |
| 验证时的摘要 | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` |
| 承载提交 | `git show 8fdb222:scripts/doc-verify.sh` 可重算上面的摘要 |
| 本文档集的结果 | 零失败且原始退出码 0；检查数与警告数随任务输出报告，而不在此引用，因为把计数写进文件会改变产出它的那次运行 |
| 警告 | 由本文档自身的 L-9 行及其内联复现器产生的建议性 `typo-suspect` 发现，已在那里说明；它们不影响退出码 |

若检查器在本文档之后被修订，上面的结果必须重新产出后才能被依赖。

### 2.4 标签面探针

由于路径标签纪律是检查器中最可能变化的部分，作者在声明该判据之前运行四个探针。它们是对当前修订
是否真的执行该纪律的诚实检验。

| 探针 | 期望 | 期望成立时的含义 |
|---|---|---|
| 裸主机路径 | 以未标记主机路径的发现失败 | 主机路径必须带显式主机作用域 |
| 带显式 `HOST:` 标签的主机路径 | 通过 | 正确标注的主机路径被接受 |
| 存在的裸工作区路径 | 通过 | 工作区证据可以按路径引用 |
| 不存在的裸工作区路径 | 以缺失工作区路径的发现失败 | 工作区路径会被存在性检查 |

本任务的探针结果连同原始退出码记录在任务输出中，这是 captain 指示的要求。若某个探针显示该纪律未被
执行，就如实报告，且不计为通过。

## 3. 验证矩阵

验证列的图例：**已复现（reproduced）** 指作者或独立复核者运行了检查并读过输出；**静态（static）**
指只读了源码并引用、没有执行任何东西；**设备（device）** 指证据来自真机采集；**未验证（unverified）**
指尚无证据。

| # | 条目 | 如何验证 | 证据 | 结论 |
|---|---|---|---|---|
| V-1 | 交付的 APK 就是发布记录所指的那一个 | 重算下载制品的摘要并与发布记录比较 | `reports/52-release-closure.md` §1（摘要 `59c75778…`，33 472 645 B，构建输入 `4130ddc`） | 已复现 |
| V-2 | APK 内含四个加载段已对齐的原生库 | 解析打包后的 APK 并读取每个加载段的对齐 | `reports/42-delivery-verification.md` §1.3 | 已复现 |
| V-3 | APK 内含生成的帧丢弃器 trial 字符串 | 统计打包后 dex 文件中的字面量 | `reports/42-delivery-verification.md` §1.3（dex 字面量计数） | 已复现 |
| V-4 | 房间与席位在 WebSocket 断连后存活满宽限期 | 以该宽限期取值运行服务，对一个不带关闭帧的客户端硬断开，并观察房间与 `peerLeft` 计数 | `reports/35-room-grace.md` §4.2, `reports/38-signaling-grace-deploy.md` §5.1 与 §5.2 | 已复现 |
| V-5 | 重连的对端以同一身份取回其席位 | 在宽限期内重连并读取 `joined` 应答 | `reports/35-room-grace.md` §3.2, `reports/38-signaling-grace-deploy.md` §5.2 | 已复现 |
| V-6 | 客户端重连预算至少等于宽限期 | 运行约束该预算的单元测试 | `reports/39-reconnect-budget-ice-restart.md` §4 与 §5.3（63 s 预算，断言低于 75 s） | 已复现 |
| V-7 | ICE 重启在重连提议发出之前被请求 | 阅读源码中的顺序与具名测试 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`, `reports/40-glare-ice-restart-fix.md` §4 | 静态 |
| V-8 | 同一时刻只有一个角色发送提议 | 阅读提议门控与唯一的调用点 | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1343` | 静态 |
| V-9 | 加入方的应答不可能早于连接对象 | 阅读发布顺序与具名测试 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`, `reports/23-session-lifecycle.md` §3 | 已复现（单元测试） |
| V-10 | 远端候选计数与线上一致 | 运行计数单元测试并与设备采集比较 | `reports/46-remote-candidate-counting.md` §4.1 与 §4.3 | 已复现 |
| V-11 | STUN 映射地址正确 | 运行字节序主机测试 | `reports/24-nat-address-endianness.md` §7.1（22 条断言，零失败） | 已复现 |
| V-12 | 候选解析器读取正确的字段 | 在四个候选夹具上运行解析器测试 | `reports/19-ice-candidate-parse.md` §验证 | 已复现 |
| V-13 | 回环候选过滤在两侧都生效 | 阅读过滤器与设备日志行 | `reports/30-ice-relay-robustness.md` §2, `reports/52-release-closure.md` §4 | 设备 |
| V-14 | TURN 权限缺陷已消失 | 针对服务器运行权限探针矩阵 | `reports/28-turn-permission-403.md` §2.2 与 §5 | 已复现 |
| V-15 | ICE 看门狗不再把健康通话报告为失败 | 运行看门狗策略测试，并与显示虚假告警的设备采集比较 | `reports/44-ice-watchdog-false-failure.md` §4.1 与 §4.3 | 已复现 |
| V-16 | 没有选中候选对时连接状态绝不报告已连接 | 运行状态机测试，包括禁止把发送侧速率作为活性输入的测试 | `reports/29-connect-state-ui.md` §5, `reports/31-ui-liveness-a7.md` §1.2 | 已复现 |
| V-17 | 等待状态不运行重试计时器 | 运行等待状态的具名测试 | `reports/33-waiting-peer-no-retry.md` §5 | 已复现 |
| V-18 | 远端帧活性不是一次性信号 | 针对两 tick 规则运行活性测试 | `reports/32-remote-frame-liveness.md` §3 | 已复现 |
| V-19 | 自建编码器在尺寸变化后仍存活 | 阅读尺寸变更路径与主机语法检查 | `app/src/main/cpp/encoder/vp9_encoder.cpp:616`, `reports/20-encode-resize-crash.md` §4 | 静态 |
| V-20 | 交付的编码器库在运行时分派，而不是在构建期绑定 SVE | 运行区分两个制品的验证脚本 | `reports/26-libvpx-runtime-cpu-detect.md` §4.2 与 §4.3（新制品通过、旧制品失败） | 已复现 |
| V-21 | 编码帧长度等于编码尺寸 | 阅读交付路径与修复前的设备量化 | `reports/27-encoder-direction-perf.md` §3.1 与 §3.2 | 设备 |
| V-22 | 编码器不再因帧丢弃器丢失输入帧 | 阅读双方对端上的 trial 字符串与之后的编码器性能行 | `reports/52-release-closure.md` §4（`field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/`, `encoder_perf in_fps=30`） | 设备 |
| V-23 | 码率地板被应用 | 比较中继路径上改前与改后的请求速率与实际速率 | `reports/49-bitrate-allocation-collapse.md` §1 与 §3, `reports/52-release-closure.md` §4 | 设备 |
| V-24 | 编码器吞吐满足帧预算 | 阅读编码器性能行 | `reports/47-vp9-encode-perf.md` §4.1（主机 A/B），`reports/52-release-closure.md` §4（设备） | 已复现（主机）与设备 |
| V-25 | 编码器兜底会切换并保留健康路径 | 运行兜底策略与控制器测试 | `reports/48-encoder-fallback.md` §6 与 §7.3 | 已复现 |
| V-26 | 渲染器不是卡顿原因 | 阅读渲染器计数器与逐帧开销，以及接收侧丢帧计数器 | `reports/50-quality-scaling-and-render-fps.md` §3 | 设备 |
| V-27 | 发布锚点处单元测试套件为绿 | 运行单元测试任务并统计已执行用例 | `reports/52-release-closure.md` §1（24 个类，216 个用例，0 失败） | 已复现 |
| V-28 | 仓库中的部署单元与运行中的服务一致 | 比较两个单元文件的有效指令 | `reports/43-deploy-unit-consistency.md` §3.2 | 已复现 |
| V-29 | 线上服务报告其被配置的宽限期 | 在已部署实例上读取健康端点 | `reports/42-delivery-verification.md` §4.2 | 已复现 |
| V-30 | 发布链发布它构建出的摘要 | 阅读发布记录与归档副本 | `reports/52-release-closure.md` §1 与 §6 | 已复现 |
| V-31 | 本文档集通过文档门禁 | 把门禁限制在这两个文件上运行，并记录检查器摘要 | 上面的 §2.3 | 已复现 |
| V-32 | 路径标签纪律由正在运行的那个门禁执行 | 运行 §2.4 的四个标签面探针 | 本任务的任务输出 | 已复现 |

## 4. 已知限制

| # | 限制 | 后果 | 状态 | 证据 |
|---|---|---|---|---|
| L-1 | 双方对端都在对称 NAT 之后时，媒体依赖中继 | 没有直连路径；延迟是中继延迟，质量受中继与上行限制 | 已知限制（known limitation） | 两个测试对端都在对称 NAT 之后，因此每个会话都走中继路径（`reports/52-release-closure.md` §4 与 §5） |
| L-2 | 严重丢包下的帧率低于采集帧率 | 在约 11.8% 丢包时跟随方渲染约 20 fps；这是接收侧效应，不是发送方缺陷 | 已知限制（known limitation） | `reports/50-quality-scaling-and-render-fps.md` §3 与 §4, `reports/52-release-closure.md` §4 |
| L-3 | 重建的 APK 与已发布的不逐字节相同 | 基于哈希的识别必须使用已发布的摘要，绝不是「同一次构建」 | 已知限制（known limitation） | `reports/52-release-closure.md` §5（dex 分片不稳定） |
| L-4 | 任何持有共享凭证的人都能到达 TURN 端点 | 暴露面经测量后由用户明确决定接受；补偿控制与升级路径已记录 | 已知限制（known limitation）（已接受风险） | `reports/45-turn-exposure-accepted-risk.md` §1 至 §4；24 小时测量未发现未授权的成功分配 |
| L-5 | 下载面的保留策略未启用 | 主机上保留着若干归档的 APK 副本而没有任何策略 | 已知限制（known limitation） | `reports/52-release-closure.md` §5 |
| L-6 | 静默离线对端的恢复仅限于信令恢复 | 在当前运行时无法对既有连接执行完整 ICE 重启路径，因此只验证了信令层 | 已知限制（known limitation） | `reports/99-final-report.md` §0.3 与 §8.5 |
| L-7 | 门禁结论只有连同检查器摘要才有效 | 不带摘要复制的结论无法复现 | 已知限制（known limitation） | 上面的 §2.2 与 §2.3 |
| L-8 | 发布步骤是主机侧手工流程 | 服务端二进制与下载面无法仅凭仓库重建 | 已知限制（known limitation） | `reports/99-final-report.md` §15.5 条目 D-6 |
| L-9 | 缺失的未跟踪构建产物从不使门禁失败 | 门禁为不存在的被 git 忽略路径记录一条未验证（unverified）的构建输出注记，且不让运行失败，因此构建产物是否存在不能成为门禁判据；这是有意的，因为干净检出不含任何构建产物 | 已知限制（known limitation）（按设计如此，不是缺陷） | 一份三行文档，其第三行引用 `app/build/nope.apk`，复现在下面的代码块中，并在冻结检查器上测得退出码 0、`PASS (6 checks, 1 warnings)`，该警告就是这里描述的建议性警告。本行是自指的：示例路径被有意设为不存在，因此门禁发出本行所描述的正是那条 `NOTE`，而冻结修订的失败豁免不被建议性警告路径查阅，所以该警告也会出现；它是警告级别，不影响退出码。规范的 V13 规则陈述相同 |

L-9 的复现器，与它取自的探针逐字节一致（99 B，sha256 `1d64dce25ee495b83499e65c4254075975054786f78efd768ba3d58d1c8d83c9`）：

```
# probe — missing gitignored artifact

A build product that does not exist: `app/build/nope.apk`
```

## 5. 契约勘误（D-1 .. D-6）

这些是旧契约与实现之间已登记的偏差。完整陈述（含逐条证据与影响）是 `reports/99-final-report.md`
§15.5。下面每一行给出偏差、照旧文本行事会发生什么，以及它当前的处置。

| ID | 偏差 | 照旧文本行事的后果 | 处置 |
|---|---|---|---|
| D-1 | 旧文档声明重连等待 3 s、最多 3 次尝试；实现使用 1/2/4/8 s 封顶、十轮、63 s | 客户端会在 90 s 服务端宽限期之前放弃，并丢掉一次本可恢复的通话 | 行为上已闭合，并记录在需求文档 §6；旧文本已被取代 |
| D-2 | 旧文本声称旋转被烤进 I420 像素且编码器输出旋转 0；实现透传旋转，标志 `kBakeRotationInEncoder` 为 false（`app/src/main/cpp/encoder/vp9_encoder.cpp:65`） | 照文本行事的人会把画面旋转两次，这在跟随方上表现为 90 度旋转 | 透传决定是有意的；反向方案在 doc/design/08-issues-and-solutions.md §8 中记录为已否决（rejected） |
| D-3 | 旧文本把 coturn 选项写成 `use-fingerprint`；coturn 4.6.1 接受 `fingerprint`（`deploy/turnserver.conf:12-13`） | coturn 报告配置格式错误，或静默忽略该选项 | 仓库配置使用被接受的拼写；旧名称不得照抄 |
| D-4 | 旧文本声称旋转已经烤进 I420 采集输出；上游采集路径不携带旋转，绘制器在绘制时应用它 | 对采集路径的错误心智模型，会误导该区域的任何改动 | 已证伪（disproven）并登记；正确的模型是 D-2 描述的透传 |
| D-5 | 仓库部署单元此前遗漏了运行中服务所使用的宽限期标志 | 从仓库重新部署会静默回退到旧的掉线行为 | 已闭合：仓库单元现在带有该标志（`deploy/signaling.service`, `reports/43-deploy-unit-consistency.md` §3.2） |
| D-6 | 下载与发布面位于主机上、在仓库之外 | 下载面与服务端二进制无法仅凭仓库重建 | 作为主机运维事实接受；其影响记录在 `reports/99-final-report.md` §15.5 |

## 6. 未验证条目及其闭合方法

每个条目说明它为何未被验证，以及具体的重测方法。这些条目都不得被读作已通过。

| # | 条目 | 为何未被验证 | 重测方法 |
|---|---|---|---|
| U-1 | 设备上的 ICE 重启 | 当前设备日志集的每次采集都缺少重启事件键与重启计数器；只执行了信令路径 | 保持一次通话，强制一次长时间信令断连，然后在日志中确认重启请求与由此建立的连接 |
| U-2 | 超过约 30 分钟的长期稳定性 | 没有采集运行得足够久 | 让通话保持至少 30 分钟，并比较开始与结束时的丢包计数器与帧率趋势 |
| U-3 | 丢包约高于 20% 的弱网极端 | 观测到的最差丢包约为 18% | 在高于 20% 的模拟丢包配置下跑一次通话，并同时记录丢包与帧率 |
| U-4 | 8 s 兜底的触发率 | 该兜底从未被观测到触发 | 在多次重连中统计兜底事件并报告观测到的比率 |
| U-5 | 真实低端设备上的编码器兜底 | 测试设备不是低端机 | 在低端手机上重复采集并读取兜底决定键 |
| U-6 | 相同丢包下默认编码器与自建编码器的对比 | 两条路径从未在相同链路与相同网络条件下比较过 | 在一条链路上先后跑两条路径，并同时比较帧率与丢包 |
| U-7 | 封锁 UDP 的网络上的 TURN-over-TCP 兜底 | TCP 能力是在服务器上验证的，不是在封锁 UDP 的通话中 | 从封锁 UDP 的网络发起一次通话并确认中继路径建立 |
| U-8 | 诊断归档内容的端到端 | 导出器没有端到端测试 | 从设备导出并检查归档内容 |
| U-9 | 超出服务端宽限期的恢复 | 没有通话被保持到跨过整个宽限期 | 把无线模块阻断超过 90 s，并观察通话是被恢复还是结束 |
| U-10 | 位于全锥 NAT 之后的对端 | 两个测试对端都在对称 NAT 之后 | 与一个位于全锥 NAT 之后的对端重跑通话，并记录是否选中直连路径 |

## 7. 证据索引

| 小节 | 引用 | 验证产物 |
|---|---|---|
| §2.1 门禁命令 | `scripts/doc-verify.sh`, `scripts/build_app.sh:45` | 本文件 §2.3 |
| §2.2 检查器不是字节稳定的 | `reports/99-final-report.md` §15, `reports/42-delivery-verification.md` §3 | 附录中记录的 A/B 探针对 |
| §2.3 验证摘要 | `scripts/doc-verify.sh` | 退出码与摘要记录在任务输出中的那次门禁运行 |
| §2.4 标签面探针 | `scripts/doc-verify.sh` | 本任务的任务输出 |
| §3 V-1、V-2、V-3 | `reports/52-release-closure.md` §1, `reports/42-delivery-verification.md` §1.3 | 重算的制品摘要 |
| §3 V-4、V-5 | `signaling/config/config.go:54`, `reports/38-signaling-grace-deploy.md` §5 | 线上断开与重连探针 |
| §3 V-6 | `reports/39-reconnect-budget-ice-restart.md` §5.3 | 预算断言 |
| §3 V-7、V-8 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272` | 顺序测试 |
| §3 V-9 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140` | 会话生命周期测试 |
| §3 V-10 | `reports/46-remote-candidate-counting.md` §4 | 计数测试 |
| §3 V-11 | `reports/24-nat-address-endianness.md` §7.1 | 字节序主机测试 |
| §3 V-12 | `reports/19-ice-candidate-parse.md` §验证 | 解析器夹具 |
| §3 V-13 | `reports/30-ice-relay-robustness.md` §2 | 设备日志行 |
| §3 V-14 | `reports/28-turn-permission-403.md` §2.2 | 权限探针矩阵 |
| §3 V-15 | `reports/44-ice-watchdog-false-failure.md` §4 | 看门狗策略测试 |
| §3 V-16、V-17、V-18 | `reports/31-ui-liveness-a7.md` §1.2, `reports/33-waiting-peer-no-retry.md` §5, `reports/32-remote-frame-liveness.md` §3 | UI 状态测试 |
| §3 V-19、V-20 | `app/src/main/cpp/encoder/vp9_encoder.cpp:616`, `reports/26-libvpx-runtime-cpu-detect.md` §4.2 | 库验证脚本 |
| §3 V-21、V-22、V-23、V-24 | `reports/27-encoder-direction-perf.md` §3.1, `reports/51-frame-dropper-and-trusted-rc.md` §3, `reports/49-bitrate-allocation-collapse.md` §1, `reports/47-vp9-encode-perf.md` §4.1 | 编码器采集与主机 A/B |
| §3 V-25、V-26、V-27 | `reports/48-encoder-fallback.md` §6, `reports/50-quality-scaling-and-render-fps.md` §3, `reports/52-release-closure.md` §1 | 测试套件与渲染器计数器 |
| §3 V-28、V-29、V-30 | `deploy/signaling.service`, `reports/43-deploy-unit-consistency.md` §3.2, `reports/42-delivery-verification.md` §4.2 | 单元对比与健康端点 |
| §3 V-31、V-32 | `scripts/doc-verify.sh` | 门禁运行与标签探针 |
| §4 L-1 .. L-8 | `reports/52-release-closure.md` §5, `reports/45-turn-exposure-accepted-risk.md` §1, `reports/99-final-report.md` §0.3 | 同样的报告 |
| §5 D-1 .. D-6 | `reports/99-final-report.md` §15.5, `deploy/turnserver.conf:12-13`, `deploy/signaling.service` | 同样的报告 |
| §6 U-1 .. U-10 | `reports/52-release-closure.md` §5, `reports/99-final-report.md` §15.7 | 每个条目列出的重测方法 |

## 8. 待办事项

| # | 事项 | 为何仍未闭合 | 如何闭合 |
|---|---|---|---|
| O-1 | 任务输出的探针结果尚未进入本文件 | 探针在执行门禁时运行，而门禁在文档定稿之后运行 | 把探针退出码与检查器摘要粘贴进任务输出；本文档之后的修订可以内联携带它们 |
| O-2 | 这两篇文档尚无独立验证 | 独立验证任务在编写者之后进行 | 验证者重跑门禁并抽样引用，然后记录验证结论 |
| O-3 | 设计文档之间的交叉引用 | 同批章节在本文档写作之后才落地，因此有几处引用被记录为纯文本而不是链接 | 整合任务会补上相对链接；在那之前每处引用都显式写出目标章节 |
