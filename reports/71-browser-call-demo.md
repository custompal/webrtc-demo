# 71 — 浏览器视频通话 Demo：交付报告（T6 整合）

> Status: **delivered**（T6 整合产物）· Owner: `writer` · Task: t6 (attempt 1), 2026-09-23
> 上游判据：[reports/70-browser-call-demo-requirements.md](70-browser-call-demo-requirements.md)（T1 需求冻结，v4 只读基线 `967cb530a682fd39b4ab69f85526a99520d67ef74d28fa3bb603af6bb642e293`）与 [reports/72-browser-demo-verification.md](72-browser-demo-verification.md)（T5 独立验证）
> 下游：T7 独立复核 → **reports/73-browser-demo-review.md**
> **判据优先级**：captain 裁定 / 任务契约 > `reports/70` > `reports/72` 的实测结论 > 本报告 > 成员自述。
> **本报告不美化**：`reports/72` 判定为未做/未通过/未证的项，一律在 §8 原样登记；本报告不含任何「作者说通过」当作通过的说法。
> **书写约定**：已存在的仓库路径写反引号；**尚未落地**的路径（**reports/73-browser-demo-review.md**）写**粗体纯文本**；宿主机绝对路径按 `doc/design/SPEC.md` §7.2 记 HOST: 或写进围栏。

---

## 0. 本报告的口径

1. 本报告是**对外交付说明**：用户照 §5 的步骤即可跑起来，照 §9 的命令即可复跑全部判定。
2. 本报告**不重新验证**：所有验证结论来自 T5 的独立复跑（`reports/72`），只做**引用与整合**；引用时连原始证据路径一起给（§6.2）。
3. 本报告**不修改被整合的产物**：`reports/70`、`reports/72`、`web/**`、`scripts/**`、`app/**`、`signaling/**`、`doc/design/**` 的盘面值以 §3 登记的 sha256 为准；本报告自身是新增文件。
4. **已知的三条 `reports/70` 语义不一致照旧存在**（该文件已只读冻结，按 captain 裁定只登记不自改）：见 §8.3。

---

## 1. 目标与范围

**目标**：在真机 Android App 之外，提供一个**纯静态的浏览器视频通话 Demo**（仅桌面 Chrome），与 App 经既有 Go 信令服务真实互拨；并把**每一个通话流程**在浏览器控制台与页面面板上可见、可导出。

| 编号 | 范围 | 落地状态 | 主落点 |
|---|---|---|---|
| S1 | 浏览器信令客户端 + 时间线 | 已交付 | `web/lib/signaling.js`、`web/lib/timeline.js` |
| S2 | 页面 + WebRTC 媒体 + 六区观测面板 + 流程自测矩阵 | 已交付 | `web/index.html`、`web/app.js`、`web/lib/peer.js`、`web/lib/observe.js`、`web/lib/selftest.js`、`web/lib/style.css` |
| S3 | 用户侧静态服务 + 容器内无头验证环境 + 联调手册 | 已交付 | `scripts/serve-web-demo.sh`、`scripts/web-demo-verify.sh`、`web/README.md`、`web/tests/**` |
| S4 | 独立验证与整合文档 | 已交付 | `web/tests/signaling.e2e.mjs`、`web/tests/web-demo-independent.mjs`、`reports/72-browser-demo-verification.md`、本报告 |

**非目标**：不改 `app/**` 与 `signaling/**`；不做 TLS/HTTPS 与 `turns:`；不做移动浏览器适配；不引入 App/服务端日志通道；不做录像与多人会议（房间上限 2 席）。

---

## 2. 架构与落点分工（权威口径）

### 2.1 容器 / 宿主机分工（captain r1，唯一权威口径）

**三个已知的 `reports/70` 不一致以此表为准**（§8.3 逐条登记）：

| 交付物 | 在哪跑 | 作用 | 依赖 |
|---|---|---|---|
| `scripts/web-demo-verify.sh` | **容器内** | 从零自举 `chrome-headless-shell` + 运行库 sysroot 到**交付路径之外**的缓存目录 → 起 Node 静态服务 → 驱动双页/页面断言。**无头媒体验证的主路径** | 容器内 `node ≥ 22`；不依赖宿主机预置、不依赖 `tmp/chrome-env/**` |
| `scripts/serve-web-demo.sh` | **宿主机** | 面向**用户 Chrome** 的静态页（VSCode 端口转发的目标解析在宿主机上） | `python3`（**运行**而非安装）；宿主机无 python3 时回退 node |
| 页面 | 用户浏览器 | `http://localhost:8081/`（`getUserMedia` 的 secure context 来源） | 桌面 Chrome |
| 信令 | 页面直连 | `ws://47.238.144.66:8443/ws`（明文）；容器内工具用 `ws://172.18.0.1:8443/ws` | 8443 **不需要**端口转发 |

* 「宿主机零安装」是硬约束：`serve-web-demo.sh` 的 start/status/stop 与就绪探测都**不要求 node**（探活用 bash `/dev/tcp`，curl/nc/python3 兜底）。
* 容器内的静态服务**只服务于无头断言**，不需要端口转发；面向用户的页面必须由宿主机那一条提供。
* 权威手册是 `web/README.md`（落点分工表在其 §「落点分工」）：`web/README.md:12`、`web/README.md:16`、`web/README.md:17`。

引用：`web/README.md:12`、`web/README.md:16`、`web/README.md:17`、`scripts/serve-web-demo.sh:1`、`scripts/web-demo-verify.sh:1`

### 2.2 数据流

1. 页面加载 `web/index.html`（`web/index.html:1`）→ `web/app.js` 组装 UI 与依赖，并在 `web/app.js:554` 暴露 `window.selftest`、在 `web/app.js:555` 暴露 `window.__webDemo`。
2. 信令：`web/lib/signaling.js` 建单条 WebSocket（`/ws`），按 `type` 分派，14 类消息、心跳 15 s / pong 窗口 5 s / 连续 4 窗口判活（≈20 s）、退避 1/2/4/8 s ≤10 次、未注册监听时缓冲 32 条、close 1000 显式处理、错误码三分类处置。
3. ICE：`created`/`joined` 下发的 `stunUrl`/`turnUrl`/`turnUsername`/`turnCredential` **是唯一来源**；`web/lib/peer.js` 组装 RTCIceServer 列表（TURN UDP 优先 + 同凭据 TCP 回退），过滤本端与远端的 loopback 候选，提供 `iceTransportPolicy` 强制 relay 开关，ICE restart 上限 2 次且**仅房主**可发起。
4. 媒体：默认路径 = 真机 App 建房（App 为 offerer）→ 浏览器 Join 并 answer；浏览器建房模式下浏览器为 offerer（offer 只含 VP9 偏好）。统一 plan、bundle、rtcp-mux。
5. 观测：`web/lib/timeline.js` 记录每一帧（方向/时刻/type/原始 JSON/摘要）；`web/lib/observe.js` 解析 SDP（m= 段、codec/PT、ICE ufrag-pwd、DTLS 指纹、字节数）、归约 1 Hz `getStats()`（inbound/outbound-rtp、remote-inbound-rtp、candidate-pair、transport、codec），并导出 JSON/CSV。
6. 自测：`web/lib/selftest.js` 在第二个标签页充当「手机模拟器」，按序断言 create→join→offer/answer→ice→connected→媒体→leave。

引用（本节的符号锚）：`web/lib/signaling.js:1`、`web/lib/timeline.js:1`、`web/lib/peer.js:1`、`web/lib/observe.js:1`、`web/lib/selftest.js:1`

---

## 3. 文件清单与职责（含盘面摘要）

> 摘要为撰写本报告时实测；T7 复核时应逐项对盘面。

| 文件 | 职责 | sha256（全文） | 行 | 字节 |
|---|---|---|---|---|
| `web/index.html` | 页面骨架与六区面板容器 | `1c29c31c0d4171fb78b5517f36ccc861323cf62c52720c947bffb75018cdb880` | 169 | 6 465 |
| `web/app.js` | UI 装配、按钮/开关、导出入口、`window.selftest` 暴露 | `45df742c7bb677747eba4c7e73f8880518a5b8ef9167eddc9aa4a8daea1b776d` | 819 | 41 393 |
| `web/lib/signaling.js` | WebSocket 信令客户端（14 类消息、心跳、退避、缓冲、close、错误策略） | `158f678f26c34fd14e2fe55049b2e2051f391c03326a8760a1c2b30ecf495b9f` | 1 399 | 53 754 |
| `web/lib/timeline.js` | 逐帧时间线 + JSON/CSV 导出 | `04a5be733a7107345219579f533e2ff5a81f0eeac66c0156eac78e08d1989744` | 279 | 10 327 |
| `web/lib/peer.js` | RTCPeerConnection 封装（下发即 ICE 来源、UDP+TCP、loopback 过滤、强制 relay、ICE restart） | `36c0086dd6d1ce7f198490f5f4a4116e742d590648f68a6e9ac0ca2c3d51d36e` | 707 | 29 966 |
| `web/lib/observe.js` | SDP 解析、候选分类、1 Hz stats 归约、JSON/CSV 导出 | `cea0008a555ec5414bde4eacb3f4f1209bf2dc19d400670197c5e183b974e8b8` | 829 | 31 841 |
| `web/lib/selftest.js` | 流程矩阵自测（第二标签页当「手机模拟器」） | `7a8bbe7a955fcb350013c875bac3e2dac53b566756833013bc8de41e7eca1d26` | 388 | 19 151 |
| `web/lib/style.css` | 面板排版样式 | `6cbb9e2203017e5a255c3fb309f1fbd78febee751fb6b0108634963397a1c287` | 189 | 5 487 |
| `web/tests/signaling.test.mjs` | 信令纯逻辑单测（作者侧） | `244d7a2fe98c640799043bc5d299f04fc55e2a04d1a2665664c35d0b3ec9ff1f` | 923 | 42 660 |
| `web/tests/signaling.e2e.mjs` | 对 live 服务的信令 e2e（T5 新增） | `b50f66e9c7f4d77e0eda04c83a6d242e86d97928a143baa24479314ac80532c2` | 381 | 17 887 |
| `web/tests/web-demo-independent.mjs` | T5 独立判定实现（模块/环境/媒体 35 条） | `60373ce99d5f7bfce9fafac60ccdf671b473ae3b7fc5846e4e2f24c9a32bdba6` | 824 | 49 033 |
| `web/tests/verify-two-page.mjs` | 交付入口 `web-demo-verify.sh` 的双页驱动（t3） | `c1d6a4267e628b1bcaaacf66b71f1ba60a80655f10602904e49d34e9305ed86a` | 645 | 36 817 |
| `web/tests/lib/cdp.mjs` | CDP 传输与 `newPage`（**F8 竞态位置**：`web/tests/lib/cdp.mjs:124`） | `2c896f1add8b9e63a1452e19d6c5257eb190f79c77437e84254470588f988a09` | 189 | 8 184 |
| `web/tests/lib/chrome.mjs` | chrome-headless-shell 启动与 `XDG_DATA_DIRS`/`LD_LIBRARY_PATH` 设置 | `9d2d9ebf3860f890a28622340a2f313ad6be5b3c4bea470934a088123d639d57` | 160 | 7 215 |
| `web/tests/lib/deb.mjs` | Debian `Packages` 索引解析与运行库闭包 | `4544224d2b9e31e2cd9dd10a75ef7bde1e0259b72974339ddfa090f67709014e` | 160 | 7 091 |
| `web/tests/lib/serve.mjs` | 无头验证用 Node 静态服务 | `982e095954795b190fc0f780ab1baf57d5166a8265dbe11b6f1c7eaa6c8f0d62` | 127 | 4 930 |
| `web/tests/lib/zip.mjs` | 自写 ZIP 读取（容器无 `unzip`） | `6ce8c0096467e7d3eb2f4d89998e4dd2d3514f4243a9dafb5b4c0b5928de2ba1` | 82 | 3 528 |
| `scripts/serve-web-demo.sh` | **宿主机**静态服务（start/status/restart/stop） | `63c0d22a1fd4ff7b2f893da8244d3a2b9d40996686c7ed61071b45999da00adb` | 441 | 17 551 |
| `scripts/web-demo-verify.sh` | **容器内**自举 + 双页断言入口（`probe`/`demo`/`auto`） | `139ae5e773a1938bece8b8e1c46a88cb3c23ab49b166cdf961eaa0229d1c07f1` | 258 | 12 006 |
| `web/README.md` | 运行手册、失败矩阵、容器/宿主机落点分工 | `59d419ff8b49e0d746d66d23d51d79a8239219b1e7fb4ea8ef014070345d5eca` | 355 | 37 940 |
| `reports/72-browser-demo-verification.md` | T5 独立验证（含终态后追加 §12；本报告引用其结论） | **不登记 sha/行/字节**：该文件在 T5 终态后仍被其 owner 继续追加 §12，属**动态文档**；以盘面现值为准（撰写本报告期间实测其 mtime 从 22:31 变到 22:34 之后仍在变） | — | — |

引用：`web/app.js:554`、`web/app.js:555`、`web/tests/lib/cdp.mjs:124`、`web/tests/lib/chrome.mjs:1`、`web/tests/lib/deb.mjs:1`

**摘要锚与时效（必须按 mtime 读）**：上表除最后一行外是撰写时快照（`web/**` 与两个脚本 mtime 21:47–22:26；T7 复核须逐项对盘面）。最后一行 `reports/72` **故意不登记 sha**：它在 T5 终态后仍被追加 §12（撰写期间实测被改过两次），任何登记值都会立刻失效，故以盘面为准，本报告只按**章节**引用其结论（§1 五面判定、§12 追加登记）。`reports/72` §12.7 另已声明：**t10 与 t11 两个 repair 落地后会改动 `scripts/web-demo-verify.sh`、`scripts/serve-web-demo.sh`、`web/README.md`、`web/tests/**`**，届时上表对应行的 sha/行/字节与 §6 的 demo·probe·自举结论**必须重跑再判**；**不受影响**的是 §6.1 的 V1（对 live 信令的 e2e）与 T5 的独立双页判定（`web/tests/web-demo-independent.mjs`，绑定当时页面与库的 sha）。

**交付物路径的解析规则**：t3/t8/t9/t10/t11 的修复都可能覆盖 `web/tests/**` 与两个脚本，所以本报告把「已存在且实测」的值与「任务台账」分开写：§3 给盘面值，§6.3/§8.2 给台账状态。

---

## 4. 面板与操作说明

**观测面只有浏览器控制台 + 页面面板**（不引入 App/服务端日志通道）。面板六区（`reports/72` §5.2 逐区实测非空）：

| # | 区域 | 内容 | 面板 id（实测） |
|---|---|---|---|
| ① | 信令时间线 | 每帧 方向/时刻/type/原始 JSON/摘要，可清空 | `#timeline` |
| ② | SDP 原文与解析 | offer/answer 原文 + m= 段/codec/PT/ICE ufrag-pwd/DTLS 指纹/字节数 | `#sdp-offer` |
| ③ | ICE 候选与选中对 | 类型/协议/地址:端口/优先级/foundation/来源 + 选中候选对 RTT 与收发字节 | `#candidates` |
| ④ | 状态机跃迁 | signaling / iceGathering / iceConnection / connection / dtls 五条 | `#states` |
| ⑤ | 1 Hz RTP/RTCP | 码率、分辨率、fps、jitter、丢包、NACK/PLI/FIR、RTT | `#stats` |
| ⑥ | 事件与错误 | error/超时/过滤（含 loopback 计数）统一事件流 | `#events` |

**操作**：Create、Join（填 6 位房号）、Leave、ICE restart、Renegotiate、强制 relay（`iceTransportPolicy=relay`）、静音、**模拟掉线**（只断 WS 不 leave）；导出 JSON 与 CSV（timeline/candidates/states/stats 四份）。关键事件同时 `console.log`，前缀 `[ui]`/`[sig]`/`[ice]`/`[stats]`/`[err]`。

---

## 5. 使用步骤

### 5.1 浏览器↔浏览器（无需真机，最快上手）

1. 宿主机启动静态页：`bash scripts/serve-web-demo.sh start`（默认 8081）。
2. VSCode **PORTS** 面板**只转发 8081**。
3. Chrome 打开 `http://localhost:8081/`（确认地址栏是 `http://localhost`，这是 secure context）。
4. 点 **Create**（浏览器建房）→ 第二个标签页同样打开页面 → 填房间号 **Join**。
5. 观察六区：`peerJoined` → `offer` → `answer` → `ice*` → connected；`stats` 出现 `video/VP9` 且双向字节增长。
6. 点导出 JSON/CSV 留证；或运行页面自测矩阵（`window.selftest.run()`）。

### 5.2 真机联调（App 建房 → 浏览器 Join）

1. **preflight**：`curl -sS http://47.238.144.66:8443/healthz` 必须 200 且含 `roomGraceSec` = 90、`stunUrl`、`turnUrl`。不通即停——那是安全组/网络问题，不是浏览器问题。
2. 宿主机 `bash scripts/serve-web-demo.sh start`；VSCode **PORTS** 只转发 **8081**（信令 8443 **不需要**转发）。
3. 真机 App 建房，记下 6 位房号。
4. Chrome 打开 `http://localhost:8081/`，填房号 → **Join**。
5. 观察六区与 §5.1 相同；`getUserMedia` 报 `NotAllowedError` 说明来源不是 `http://localhost`。
6. 复现失败路径：强制 relay、模拟掉线（≤90 s 内重连拿回原 `peerId`，对端不应收到 `peerLeft`）、对端离开。
7. 导出 JSON/CSV 留证。
8. 收尾：`bash scripts/serve-web-demo.sh stop`。

**失败判定点逐条**见 `web/README.md` §3 与 §5；TL;DR 失败矩阵见本报告 §7。

---

## 6. 验证结论（引用 T5，不重新判定）

### 6.1 T5 判定摘要

| 验收项 | 判定（`reports/72`） | 关键实测 |
|---|---|---|
| V1 信令 e2e（对 live 服务） | **通过** | `node web/tests/signaling.e2e.mjs` → **19/19 PASS，EXIT=0**；14 类消息全覆盖；6 类错误路径；offer/answer/ice/natType **字节级原样转发**（非规范 JSON 用 `===` 比较）；leave 后房间销毁 |
| V2 单测复核 + 纯函数独立断言 | **通过** | 作者单测复跑 **253 PASS / 0 FAIL**（定稿前重跑 258/0，测试文件在窗口内被改已登记）；T5 独立断言 **10/10**（含其自身 3 处期望错误已更正） |
| V3 双页媒体验证 | **通过（另有 1 条 blocker finding F8）** | 交付入口 `demo` **6/6 PASS，EXIT=0**（页面自测 17/17）；T5 独立 CDP 双页 **35/35 PASS，EXIT=0**（DTLS/ICE connected、候选对 succeeded、VP9 经 `codecId`→`video/VP9` 双向、双向 RTP 增长 +155 588 / +146 789 B、帧计数、面板与导出齐备）；`probe` 模式 **flake 3/6 → F8** |
| V4 门禁与越界 | **通过** | `bash scripts/doc-verify.sh` PASS **4797 checks / 4 warnings** EXIT=0；`bash scripts/i18n-audit.sh` PASS **133 checks / 0 warnings** EXIT=0；`git status --porcelain` 仅白名单 |
| V5 真机联调清单 | **通过** | `reports/72` §7 给出用户步骤 + 每步失败判定点 + relay-only 复现路径；**清单本身未由真机执行**（见 §8.2） |

引用：`reports/72-browser-demo-verification.md:58`

### 6.2 原始证据路径（T5 留痕）

证据根目录（T5 声明全部在仓库外、不进 `git status`）：`tmp/verifier-evidence/`

```text
V1 信令 e2e              tmp/verifier-evidence/t5-v1-signaling-e2e.log
V2 作者单测复跑           tmp/verifier-evidence/t5-v2-unit-tests.log
V2 独立纯函数断言         tmp/verifier-evidence/t5-v2-independent-modules.log
V3 独立全套（35/35）      tmp/verifier-evidence/t5-v3-independent-all.log
V3 独立环境一致性（3/3）  tmp/verifier-evidence/t5-v3-independent-env.log
V3 交付入口 demo（6/6）   tmp/verifier-evidence/t5-v3-demo.log + t5-v3-demo.stats.json
V3 probe flake 原始       tmp/verifier-evidence/t5-v3-probe.log、t5-v3-probe-retry1.log…retry5.log
V3 冷/热自举              tmp/verifier-evidence/t5-v3-probe-cold.log、t5-v3-probe-hot.log
V4 门禁原始输出           tmp/verifier-evidence/t5-v4-gates.log
V5 宿主侧脚本复跑         tmp/verifier-evidence/t5-v5-host-serve.log
环境/开关矩阵             tmp/verifier-evidence/t5-snapshot-and-switches.log、t5-switch-matrix.log
用户路径架构取证         tmp/verifier-evidence/user-path-architecture.txt
reports/70 修订链取证     tmp/verifier-evidence/reports70-pre-edit.txt、reports70-v4-214829-full.txt
```

* 交付入口自身的证据落点追加在 §6.2 清单的末两条（容器内默认日志与机器可读 stats JSON，路径形如 /tmp/web-demo-verify.log；宿主机侧另有运行期文件 /tmp/web-demo-serve.log 等）。
* **未使用手工矩阵替代**：`reports/72` §7 明确「本轮两条路均可用，未启用替代路径」。

### 6.3 T5 终态后的追加登记（`reports/72` §12，本报告必须转述的时效与台账）

1. **任务台账现状**：t8（repair，**completed**）、t9（repair，**completed**）；t10、t11（均 pending）。`reports/72` §12.7 把后续 gate 定在 t8 与 t9 终态之后，并列出 t10/t11 落地后需重跑的结论（即 §3 的时效说明）。
2. **t2 指纹作废与 t9 转正**：t2 曾登记的 `web/lib/signaling.js` 指纹已失效，现行 `158f678f26c34fd14e2fe55049b2e2051f391c03326a8760a1c2b30ecf495b9f`（与本报告 §3 一致）。成因是原生定时器 this 绑定（浏览器里抛 `TypeError: Illegal invocation`，使 create/join 永不发出；node 单测因注入虚拟定时器漏检）。**t9（repair，completed）已把该 1 行改动转正并补回归断言**；`reports/72` §12.4 在仓库外 scratch 副本上证明该断言**「能红」**（注入旧写法 → 256 PASS / 2 FAIL，EXIT=1），不是恒绿装饰。
3. **demo 条数口径差异**：T5 在现行 `scripts/web-demo-verify.sh`（`43d2d355…`）上独立跑 `--mode demo` 得 **6/6 PASS**；captain 台账记 5/5 —— 属脚本修订间的断言条数变化（demo 5→6、probe 18→19），不影响「通过」结论（`reports/72` §12.2）。
4. **F5 扩为过程问题合集（medium）**：实例 a = ops/t3 完成前 F1–F4 与 amend 未交付（t8 收口）；实例 b = web-dev/t4 越 inScope 改动 t2 产物（t9 收口）；实例 c = ops 在 t10 待办期间改动 `web/tests/**` 内核且未 claim（**t11 pending**）。重复发生这一事实本身应进 T7 的如实性复核面（`reports/72` §12.3）。
5. **F8（probe 就绪竞态，blocker）当前没有修复任务承接**：t10/t11 的验收面均**不含** `web/tests/lib/cdp.mjs:124` 的 `newPage()` 就绪等待；`reports/72` §12.5 建议加显式验收「`--mode probe` **连续 ≥5 次全部 19/19 PASS 且 EXIT=0**」。**在修复前本报告不把 probe 模式记为稳定通过**。
6. **finding 编号冲突（未裁定）**：`reports/72` 的 F8 = probe 竞态；t10 台账用「F8」指 tmpfs 缓存（即 `reports/72` 的 F7）；t11 用「F9」指孤儿 chrome 进程卫生。**未裁定前一律以 `reports/72` 编号为准并注明映射**（`reports/72` §12.6），本报告即按此执行。

引用：`reports/72-browser-demo-verification.md:498`、`web/tests/lib/cdp.mjs:124`、`web/lib/signaling.js:1`

---

## 7. 失败矩阵（症状 → 根因 → 处置）

完整 15 行版本在 `web/README.md` §5；以下是面向用户的高频子集：

| # | 症状 | 根因 | 处置 |
|---|---|---|---|
| 1 | `healthz` 超时/拒绝连接 | 安全组未放行 8443/tcp | ECS 控制台 → 安全组 → 入方向 → TCP `8443/8443` |
| 2 | WS 连接失败但 healthz 正常 | 误用 https://…:8443 或 wss://…:8443 | 8443 只有明文，改 `ws://47.238.144.66:8443/ws` |
| 3 | HTTP 400 `Client sent an HTTP request to an HTTPS server` | 打到了容器的 127.0.0.1:8443（DSH Harness 的 Caddy） | 容器内改用 `ws://172.18.0.1:8443/ws`；脚本对该地址 `exit 2` |
| 4 | `navigator.mediaDevices` undefined / `getUserMedia` `NotAllowedError` | 页面来源不是 `http://localhost` | 经 VSCode 转发 8081 后用 `http://localhost:8081/` 打开 |
| 5 | 无头脚本 `error while loading shared libraries` | sysroot 不完整（下载中断） | 不要装系统包；用 `bash scripts/web-demo-verify.sh --clean` 后重跑自举 |
| 6 | `serve-web-demo.sh start` 报端口被占 | 上一次未 stop 或别的进程占用 | `status` → `stop`，或换一个端口（命令里传 `--port N`） |
| 7 | 自举阶段 403/404 或下载中断 | 源站不可达 / 缓存半成品 | 重跑；仍失败则用 `bash scripts/web-demo-verify.sh --clean` 重试并检查到 storage.googleapis.com、deb.debian.org 的连通性 |
| 8 | `setCodecPreferences` `InvalidModificationError` | 编解码偏好被设到音频 transceiver | 只对 `sender.track.kind === 'video'` 设置 |
| 9 | 只见 `relay` 候选且 coturn 报 508 | relay 配额/端口池耗尽（total-quota 45、端口池 49152-49200 共 49） | 识别为配额问题而非信令故障；等释放或降并发 |
| 10 | 只见 `host` 候选、无可用候选对 | Chrome mDNS 混淆（`.local`）对端不可解析 | 以 `srflx`/`relay` 候选对为判定依据；登记为已知限制 |
| 11 | `--mode demo` 报 `page.selftest-api` FAIL | 页面未暴露 `window.selftest.run()` | 由页面实现方补齐；`probe` 模式不受影响 |
| 12 | 仅单向 RTP 增长 | 一端未 `addTrack` 或远端轨未解码 | 两侧 bytesSent/bytesReceived 必须同时增长（5 s 窗口）；只增一侧判 FAIL |

---

## 8. 已知限制与 open items（不美化）

### 8.1 已知限制

1. **只支持桌面 Chrome**：不承诺 Safari/Firefox/移动端。
2. **页面必须来自可信来源**：仅 `http://localhost:<转发端口>`（secure context）；IP、`file://`、非转发端口都会让 `getUserMedia` 失败。
3. **mDNS 候选**：Chrome 对 host 候选做 mDNS 混淆，真机侧可能无法解析；判定以大方 `srflx`/`relay` 候选对为准（未验证项，见 §8.2）。
4. **TURN relay 端口与配额**：端口区间 49152-49200（共 49）、`total-quota` 45、`user-quota` 8；配额类失败必须识别为配额问题，不是信令故障（relay-only 端到端未验证，见 §8.2）。
5. **无 App 侧日志通道**：观测面只有浏览器控制台 + 页面面板；真机侧证据只能来自 App 自身导出，本 Demo 不采集服务端/App 日志。

### 8.2 未做 / 未通过 / 未证（照抄 `reports/72` §10 与 §8）

| # | 项 | 状态 | 说明 |
|---|---|---|---|
| 1 | **F8（blocker）**：交付入口 `probe` 模式竞态 | **未修复** | 6 次里 3 次失败：`web/tests/lib/cdp.mjs:124` 的 `newPage` 不等待页面就绪即被 `web/tests/verify-two-page.mjs:348` 调用。修复前 `web/README.md:145` 的「19/19 PASS」**不是可靠复现**；V3 主判定证据是 T5 的独立双页（35/35）与 `demo` 模式（6/6） |
| 2 | 真机 App ↔ 浏览器真实互拨 | **未由 T5 执行** | 无 Android 真机与控制台通道（硬约束不引入 App/服务端日志）；§5.2 只给可执行清单，不等于已通过 |
| 3 | `reports/70`「除授权处外零改动」 | **未证项** | T5 的 pre-edit 快照只摘录区域、无整文件拷贝；字节残差 79 B 中 76 B 归授权改写、3 B 落在转录不确定性内（`reports/72` §9.4） |
| 4 | mDNS 候选在真机侧的可解析性 | **未验证** | 容器内双页走 `host/udp` + `srflx/udp` |
| 5 | TURN relay-only 端到端 | **未验证** | 页面提供强制 relay 开关；端口区间与 quota 的实际并发未实测 |
| 6 | 5349/TLS | **未测** | `reports/70` 已声明不在本轮范围 |
| 7 | 打印 sysroot 的开关（`bash scripts/web-demo-verify.sh --print-sysroot`）语义 | 语义澄清 | 返回 sysroot 根目录（消费方自行拼库路径）；print 类开关立即执行并退出，故 `--cache DIR` 必须排在其前 |
| 8 | 宿主 8081 并发 | 已规避 | T5 全程用 8092/8093/8094；多人并行复验需注意 |
| 9 | 现行修订的冷自举 | **未完整复跑** | 冷自举完整证据来自旧修订；现行修订只补了空间预检证据（`reports/72` §5.4） |
| 10 | 验证期间盘面在动 | 已登记 | T5 窗口内 `web/tests/signaling.test.mjs`、`web/README.md`、两个脚本被改动；结论绑定 `reports/72` §11.2 的 sha 锚 |
| 11 | F1/F2/F3/F4/F6 收口 | **已由 t8 收口** | t8（repair，completed）在宿主机零 node 下逐条复验 start/status/stop 与 HTTP 200；F7（默认缓存落在 tmpfs）转 t10（pending） |
| 12 | F5 扩为过程问题合集（medium） | 已登记，含 pending 项 | 实例 a（t3，t8 收口）、b（t4 越 inScope 改 t2 产物，t9 收口）、c（t10 待办期间改 `web/tests/**` 内核且未 claim，**t11 pending**）；重复发生本身进 T7 如实性复核面（`reports/72` §12.3） |
| 13 | T4 披露的 1 行修复 | **已由 t9 转正** | `web/lib/signaling.js` 的原生定时器 this 绑定缺陷已走 t9（repair，completed）；其回归断言经 `reports/72` §12.4 在仓库外 scratch 副本证明「能红」（旧写法 256 PASS / 2 FAIL，EXIT=1）；t2 旧登记指纹已作废，现行 `158f678f…` |
| 14 | **F8（probe 就绪竞态）无任务承接** | **open，风险** | t10/t11 验收面均不含 `web/tests/lib/cdp.mjs` 的 `newPage()` 就绪等待；建议加验收「`--mode probe` 连续 ≥5 次 19/19 PASS 且 EXIT=0」（`reports/72` §12.5） |
| 15 | finding 编号冲突 | **未裁定** | `reports/72` 的 F8 = probe 竞态；t10 台账用「F8」指 tmpfs 缓存（即本体系 F7）；t11 用「F9」指孤儿 chrome。未裁定前以 `reports/72` 编号为准并注明映射（`reports/72` §12.6） |

### 8.3 三条 `reports/70` 已知不一致（只读冻结，只登记不自改）

| # | 位置 | 问题 | 权威口径 |
|---|---|---|---|
| a | `reports/70-browser-call-demo-requirements.md:308`–`:310` | A-3 围栏示例把**容器路径**标成 `HOST:`（`cd /data/dsh/home/workspace/...`），宿主机不存在该路径，用户照抄必 `cd: No such file`；写法形式合规、门禁不可检出 | 以 §2.1 落点分工表为准：用户侧在**宿主机**（`HOST: /opt/dsh-workspaces/code/webrtc-demo`），无头验证在**容器内** |
| b | `reports/70-browser-call-demo-requirements.md:313` | A-3 期望行写证据落盘为 `HOST: /tmp/web-demo-verify.log`，而验证脚本主路径在容器内 | 日志落点按执行侧解释：容器内 `/tmp/web-demo-verify.log`；宿主机侧另有运行期文件 `/tmp/web-demo-serve.*` |
| c | `reports/70-browser-call-demo-requirements.md:374` | §9 H-6 用同一 `HOST: /tmp/web-demo-verify.log` 举例 | 同上；H-6 的「输出卫生」原则不变（临时文件不进仓库） |

引用：`reports/70-browser-call-demo-requirements.md:308`、`reports/70-browser-call-demo-requirements.md:313`、`reports/70-browser-call-demo-requirements.md:374`

---

## 9. 全部可复跑命令

**容器内**（无头验证与逻辑判定）：

```bash
CONTAINER: cd /data/dsh/home/workspace/code/webrtc-demo

# 信令 e2e（对 live 服务）
CONTAINER: node web/tests/signaling.e2e.mjs; echo "EXIT=$?"

# 信令纯逻辑单测
CONTAINER: node web/tests/signaling.test.mjs; echo "EXIT=$?"

# T5 独立全套（模块 + 环境一致性 + 媒体双页）
CONTAINER: node web/tests/web-demo-independent.mjs --phase all --cache <bootstrapped-cache>; echo "EXIT=$?"

# 交付入口：页面自测模式 / 内建探针模式
CONTAINER: bash scripts/web-demo-verify.sh --mode demo --cache <bootstrapped-cache>; echo "EXIT=$?"
CONTAINER: bash scripts/web-demo-verify.sh --mode probe --cache <bootstrapped-cache>; echo "EXIT=$?"   # F8：flake，见 §8.2

# 门禁
CONTAINER: bash scripts/doc-verify.sh; echo "DOC_EXIT=$?"
CONTAINER: bash scripts/i18n-audit.sh; echo "I18N_EXIT=$?"
CONTAINER: bash scripts/doc-verify.sh --only reports/71-browser-call-demo.md; echo "EXIT=$?"

# 自举缓存维护
CONTAINER: bash scripts/web-demo-verify.sh --print-cache
CONTAINER: bash scripts/web-demo-verify.sh --clean
```

**宿主机**（用户侧静态页；宿主机无 node 也成立）：

```bash
HOST: bash scripts/serve-web-demo.sh start       # 默认 127.0.0.1:8081，root = <repo>/web
HOST: bash scripts/serve-web-demo.sh status
HOST: bash scripts/serve-web-demo.sh stop
HOST: curl -sS http://47.238.144.66:8443/healthz
```

* 缓存必须放在能容纳 ≈426 MiB 的卷（自举预检 ≥1.2 GiB）：`export WEB_DEMO_CACHE=/data/dsh/home/workspace/tmp/web-demo-chrome-cache`（容器 `/tmp` 是 256 MiB tmpfs，放不下）。
* 宿主机等价命令：`python3 -m http.server 8081 --bind 127.0.0.1 --directory <repo>/web`。

---

## 10. 交付物与白名单核对

* 本轮新增/修改仅限白名单：`web/**`、`scripts/serve-web-demo.sh`、`scripts/web-demo-verify.sh`、根 `README.md`/`README.en.md` 双语小节、`reports/70`（T1）、`reports/71`（本报告）、`reports/72`（T5）、**reports/73-browser-demo-review.md**（T7）。
* `app/**`、`signaling/**`、`doc/design/**`、`reports/54`–`reports/69`、`reports/70`、`reports/72` 在 T6 中**零改动**。
* 硬约束核对：Chrome-only；页面经 `http://localhost` 打开；观测面 = 控制台 + 面板；两条门禁 `EXIT=0`；新增文档不影响 `scripts/doc-verify.sh` 的 CMD 分类（计数与 `reports/70` §0.2 基线一致：4797/4、133/0）。

---

## 11. t6 终态后的追加登记（T12；追加时刻 2026-09-23 23:46:19 +0800）

> **追加来源**：captain 裁定 `t12-amend-1`（2026-09-23）；t10（completed，23:30:20）、t11（completed，23:32:30）的终态 output；以及本容器在 2026-09-23 23:46:19 +0800 的只读实测。
> **只增不改**：§1–§10 的既有判定与文字**一字未动**；本节只登记 t6 结稿后被后续事实**取代或补齐**的内容。与本节冲突时以本节为准（**限本节覆盖项**）。
> **编号纪律（captain 指令）**：正文以**缺陷名**为主键，字母编号仅作括号内别名、**不得单独引用**；缺陷名与字母的对应以 §11.1 为准。

### 11.1 权威 finding 对照表（captain 裁定，逐条原样登记）

captain 权威表（`reports/71` 的一切引用以该表为准）：

| 权威编号 | `reports/72` 旧称 | 承接任务 |
|---|---|---|
| F7 | F9 | t10（completed） |
| F8 | F7 | t10（completed） |
| F9 | F10 | t11（completed） |
| F10 | captain 表记「无别名」 | t11（completed） |
| F11 | F8 | t11（completed） |
| F12 | 无（本节首次登记） | 本节处置 |

缺陷名注册表（正文主键；字母只作别名）：

| 缺陷名（主键） | 主题 | captain 权威编号 | `reports/72` 旧称 |
|---|---|---|---|
| `RUNDIR-SHARED` | RUN_DIR 共享 `/tmp` 固定名、未按 uid 隔离 | F7 | F9 |
| `CACHE-TMPFS` | `web-demo-verify.sh` 默认缓存落 tmpfs（256 MiB） | F8 | F7 |
| `CHROME-ORPHANS` | 孤儿 chrome 进程 / `profile-*` 打满 tmpfs | F9 | F10 |
| `STATS-COUNT` | `stats.exported` 自报数不一致（19 行 PASS 却写 18/18） | F10 | F11（见下） |
| `PROBE-RACE` | `--mode probe` 就绪竞态（`newPage()` 不等页面就绪） | F11 | F8 |
| `REPO-TMP` | `<repo>/tmp/**` 污染工作树（越界） | F12 | 无 |

**一处与冻结 `reports/72` 的别名差异（如实登记，不改变 captain 的映射）**：captain 表把 F10 记作「无别名」，但冻结的 `reports/72`（现场 sha `598f9add872b5c496b56e56f8d52169a15c54f2e3611885eae7c7700713a101f` / 936 行 / mtime 2026-09-23 22:52:30）在 §13.15 B 表与 §13.16 A 表为「自报数不一致」登记了标签 **F11**：

```text
reports/72-browser-demo-verification.md:858-880   §13.15 B 表：
  | **F11（新增）** | **自报数不一致**：`stats.exported` 原先在 summary 之后记录 ⇒
  |                 控制台 19 行 PASS 却写 18/18（已修，待 t11 转正） | 「**F10**」 |
reports/72-browser-demo-verification.md:900-912   §13.16 A 表：F11 = 自报数不一致 ↔ 台账「F10」
```

⇒ `STATS-COUNT` 的完整别名是「`reports/72` 旧称 **F11**；captain 台账旧称 F10」。因本节以缺陷名为主键，字母别名冲突不影响对象识别；该差异已报 captain（消息 `872b5bab-a30c-412c-95ef-296b6ea10663`），若其改判以 captain 最新裁定为准。

### 11.2 两条被取代的既有陈述（只给指针，不改原文）

**（a）§6.3 第 5 条 / §8.2 第 14 行「`PROBE-RACE` 当前没有修复任务承接」→ 已被取代**：该 finding 现编号 **F11**（`reports/72` 旧称 F8），**已由 t11**（`t11-amend-1`，inScope 含 `web/tests/lib/cdp.mjs`）**承接并终态**。但结论必须按证据写：

* **触发条件已消除**：t11 把运行期目录移出 tmpfs（默认 `<cache>/runtime`）、每次运行开头清理陈旧 `profile-*`/`probe-*`、退出前回收 chrome；实测一次完整运行前后 chrome 进程 = 0、tmpfs 用量不变。
* **根因仍在源码**：冻结后 `web/tests/lib/cdp.mjs`（sha256 `84bda763f09d39522ecaa9656a48db7a8d90d7a85a66a0cecd133f561ef296c1`，mtime 22:18:14，与 t11 output 一致）的 `newPage()`（`web/tests/lib/cdp.mjs:124`）仍只 create/attach/enable、**不等待页面就绪**；`web/tests/verify-two-page.mjs:348`–`web/tests/verify-two-page.mjs:349` 创建两个探针页后，直接在 `web/tests/verify-two-page.mjs:359` evaluate（`window.__probe` 尚未定义即被调用的竞态依然存在）。此点与 `reports/72` §13.14（`reports/72-browser-demo-verification.md:837`–`:857`）的源码结论一致。
* **「连续 5 次 `--mode probe` 19/19 EXIT=0」的证据口径（必须分清时点）**：`reports/72` §13.14 记录过**连续 5 次全部 19/19 EXIT=0**（原始输出 tmp/verifier-evidence/t5-f8-flake5.log），但其被测修订是 **t11 之前**的 `scripts/web-demo-verify.sh`：sha256 `ebb88eac0820ee001dccb08a5b0f9ae4baf5f6de7f7bde055ba576d0cb98d3f4`；t11 output 自己的复跑是**热缓存 1 次 19/19**（容器内 /tmp/t11-warm.log，EXIT=0）与**冷缓存 1 次 19/19**（容器内 /tmp/t11-cold.log，EXIT=0；期间一次瞬时 `TypeError: fetch failed`，经 curl 复核两源 200 后重试成功）。**⇒ 在冻结面（post-t11 sha）上尚无「连续 5 次」的原始证据**，本节把该验收项登记为 open item，交 T7 在 **reports/73-browser-demo-review.md** 复核（`reports/72` §13.16 D 已把「对冻结后的最终 sha 重跑 probe ≥3 次」列入其清单）。
* **本报告不写**「probe 稳定通过」，也不写「独立验证已通过」——后者属 T7 的判定。

**（b）§6.3 第 6 条 / §8.2 第 15 行「finding 编号冲突未裁定」→ 已被取代**：**已裁定**，以 §11.1 的权威表为准；`reports/72` §13.16 A 自述的「以本报告编号为准」建议**被 captain 裁定取代**，由 T7 在 **reports/73-browser-demo-review.md** 中标注。

### 11.3 冻结后的文件 sha 表（替代 §3 的对应摘要行）

> **（2026-09-24 追加标注）本表为 2026-09-23 23:46:19 时点快照，已被 §12 取代**；其六行（`scripts/serve-web-demo.sh`、`scripts/web-demo-verify.sh`、`web/README.md`、`web/tests/lib/cdp.mjs`、`web/tests/verify-two-page.mjs`、`web/tests/lib/chrome.mjs`）**全部**被 00:08–01:44 的 t10/t11 改动取代。引用请改用 §12；本表原样保留，以留存时间差证据（**被取代 ≠ 登记不实**）。

> **§3 中下列六行的值以本表为准**：`scripts/serve-web-demo.sh`、`scripts/web-demo-verify.sh`、`web/README.md`、`web/tests/lib/chrome.mjs` 四行的 §3 原值**已过期、作废**；`web/tests/lib/cdp.mjs` 与 `web/tests/verify-two-page.mjs` 两件**未漂移**（本表与 §3 相同）。§3 其余行不受影响。

| 文件 | sha256（冻结后） | 行 | 字节 | mtime | 与 §3 的关系 |
|---|---|---|---|---|---|
| `scripts/serve-web-demo.sh` | `5818e342df36f0bfcad883312b365e98a12775542f12e91707f1b8d162ec0832` | 439 | 17 325 | 2026-09-23 23:18:09 | 已变（§3 原值作废） |
| `scripts/web-demo-verify.sh` | `0ee83c0057d376ec18b82977da031254607a7f4d18e05923cd2772080cef626f` | 240 | 10 940 | 2026-09-23 23:19:04 | 已变（§3 原值作废） |
| `web/README.md` | `30517d03e468d2f0d671599a927a5afa21c41b901db6e24a95927c34a2f9bcf7` | 339 | 33 376 | 2026-09-23 23:29:49 | 已变（§3 原值作废） |
| `web/tests/lib/chrome.mjs` | `ba54cd145baa6f25b37f144c377e2b20c423f75a0b3af0eb035d67377fcba217` | 154 | 6 897 | 2026-09-23 23:31:21 | 已变（§3 原值作废） |
| `web/tests/lib/cdp.mjs` | `84bda763f09d39522ecaa9656a48db7a8d90d7a85a66a0cecd133f561ef296c1` | 158 | 6 400 | 2026-09-23 22:18:14 | 未变（与 §3 一致） |
| `web/tests/verify-two-page.mjs` | `5970bca458ebd886a2c0758dd735d59913c11c48570e60ed3e7c9c2d7bb9f4a0` | 640 | 36 528 | 2026-09-23 22:25:46 | 未变（与 §3 一致） |

`sha256sum` / `stat` 原始行与取值时刻（本节判定绑定该时刻的盘面）：

```text
CONTAINER: 2026-09-23 23:46:19 +0800
CONTAINER: $ sha256sum scripts/serve-web-demo.sh scripts/web-demo-verify.sh web/README.md web/tests/lib/cdp.mjs web/tests/verify-two-page.mjs web/tests/lib/chrome.mjs
5818e342df36f0bfcad883312b365e98a12775542f12e91707f1b8d162ec0832  scripts/serve-web-demo.sh
0ee83c0057d376ec18b82977da031254607a7f4d18e05923cd2772080cef626f  scripts/web-demo-verify.sh
30517d03e468d2f0d671599a927a5afa21c41b901db6e24a95927c34a2f9bcf7  web/README.md
84bda763f09d39522ecaa9656a48db7a8d90d7a85a66a0cecd133f561ef296c1  web/tests/lib/cdp.mjs
5970bca458ebd886a2c0758dd735d59913c11c48570e60ed3e7c9c2d7bb9f4a0  web/tests/verify-two-page.mjs
ba54cd145baa6f25b37f144c377e2b20c423f75a0b3af0eb035d67377fcba217  web/tests/lib/chrome.mjs
CONTAINER: $ stat -c '%y  %s  %n'  <上述六个文件>
2026-09-23 23:18:09  17325  scripts/serve-web-demo.sh
2026-09-23 23:19:04  10940  scripts/web-demo-verify.sh
2026-09-23 23:29:49  33376  web/README.md
2026-09-23 22:18:14   6400  web/tests/lib/cdp.mjs
2026-09-23 22:25:46  36528  web/tests/verify-two-page.mjs
2026-09-23 23:31:21   6897  web/tests/lib/chrome.mjs
```

**版本定位规则（沿用 captain 统一口径）**：`reports/72` 的 sha **只作版本定位**，其结论绑定「判定当时被测物的 sha」。冻结面现场值（本节 23:46 实测）：

```text
598f9add872b5c496b56e56f8d52169a15c54f2e3611885eae7c7700713a101f  reports/72-browser-demo-verification.md
# 936 行 / 90 770 B / mtime 2026-09-23 22:52:30（captain 已冻结）
```

### 11.4 F12 处置（`REPO-TMP`）

* 现象：`<repo>/tmp/**` 会污染工作树（`git status` 出现 `?? tmp/` 条目），而 `.gitignore` 不在本轮白名单 ⇒ **不能用忽略规则兜底**。
* 处置（两条纪律）：① **显式禁止**把任何运行期/缓存文件写进 `<repo>/tmp/**`；② 收尾自检 `git status --porcelain` **无 `?? tmp/`**。
* 正确示例（**仓库外可写卷**；示例不放进反引号代码跨度，避免门禁把可删目录当作硬存在性路径）：

```text
WEB_DEMO_CACHE=/data/dsh/home/workspace/tmp/tv-final
# 任选仓库外可写卷；当前可用：tv-final / tv-cold / vv-neg
# chrome-env 已不存在，勿引用
```

* 本节取值时刻的自检（原始输出）：

```text
CONTAINER: 2026-09-23 23:46:19 +0800
CONTAINER: $ git status --porcelain
 M README.en.md
 M README.md
?? reports/70-browser-call-demo-requirements.md
?? reports/71-browser-call-demo.md
?? reports/72-browser-demo-verification.md
?? scripts/serve-web-demo.sh
?? scripts/web-demo-verify.sh
?? web/
CONTAINER: $ git status --porcelain | grep -c '^?? tmp/'
0
```

### 11.5 引用与来源

* `reports/72-browser-demo-verification.md`（T5；含 §12/§13 追加）：本节的编号、`PROBE-RACE` 定级与证据口径均引自它；按其冻结盘面**只按章节引用**（sha 仅作版本定位）。
* **reports/73-browser-demo-review.md**（T7 独立复核，尚未创建）：承接 §11.2(a) 的 probe 复跑与 §11.2(b) 的编号取代标注。
* 受理来源：captain `t12-amend-1` 裁定；t10 output（23:30:20）、t11 output（23:32:30）；本容器只读实测 2026-09-23 23:46:19 +0800。

---

## 12. t6 终态后的再冻结登记（T13；追加时刻 2026-09-24 01:51:41 +0800）

> **追加来源**：T7 复核 findings F-T7-1 / F-T7-4 / F-T7-5（`reports/73-browser-demo-review.md`，verdict = needs_revision）；captain 的 t13 派单；本容器 2026-09-24 01:51:41 +0800 的现场实测。
> **只增不改（唯一例外：§3 六行数值同步）**：§1–§10 的判定与叙述**未回改**；本节做三件事 —— ① 以现场重取的表**取代 §11.3**；② **同步 §3 的 6 行**（仅 sha256 / 行 / 字节三项数值）；③ 按 F-T7-4 改写 `PROBE-RACE` 口径。
> **纪律**：任何 sha 引用一律现场 `sha256sum` + 附时刻；结论绑定「判定当时被测物的 sha」。本节与 T7 冻结基准（`reports/73` §14.2）现场复取**逐字一致**。

### 12.1 再冻结 sha 表（现场重取，取代 §11.3）

| 文件 | sha256 | 行 | 字节 | mtime |
|---|---|---|---|---|
| `scripts/serve-web-demo.sh` | `63c0d22a1fd4ff7b2f893da8244d3a2b9d40996686c7ed61071b45999da00adb` | 441 | 17 551 | 2026-09-24 00:08:12 |
| `scripts/web-demo-verify.sh` | `139ae5e773a1938bece8b8e1c46a88cb3c23ab49b166cdf961eaa0229d1c07f1` | 258 | 12 006 | 2026-09-24 01:44:07 |
| `web/README.md` | `59d419ff8b49e0d746d66d23d51d79a8239219b1e7fb4ea8ef014070345d5eca` | 355 | 37 940 | 2026-09-24 01:37:17 |
| `web/tests/verify-two-page.mjs` | `c1d6a4267e628b1bcaaacf66b71f1ba60a80655f10602904e49d34e9305ed86a` | 645 | 36 817 | 2026-09-24 01:08:30 |
| `web/tests/lib/cdp.mjs` | `2c896f1add8b9e63a1452e19d6c5257eb190f79c77437e84254470588f988a09` | 189 | 8 184 | 2026-09-24 01:08:19 |
| `web/tests/lib/chrome.mjs` | `9d2d9ebf3860f890a28622340a2f313ad6be5b3c4bea470934a088123d639d57` | 160 | 7 215 | 2026-09-24 00:45:44 |

原始行与取值时刻：

```text
CONTAINER: 2026-09-24 01:51:41 +0800
CONTAINER: $ sha256sum scripts/serve-web-demo.sh scripts/web-demo-verify.sh web/README.md \
              web/tests/verify-two-page.mjs web/tests/lib/cdp.mjs web/tests/lib/chrome.mjs
63c0d22a1fd4ff7b2f893da8244d3a2b9d40996686c7ed61071b45999da00adb  scripts/serve-web-demo.sh
139ae5e773a1938bece8b8e1c46a88cb3c23ab49b166cdf961eaa0229d1c07f1  scripts/web-demo-verify.sh
59d419ff8b49e0d746d66d23d51d79a8239219b1e7fb4ea8ef014070345d5eca  web/README.md
c1d6a4267e628b1bcaaacf66b71f1ba60a80655f10602904e49d34e9305ed86a  web/tests/verify-two-page.mjs
2c896f1add8b9e63a1452e19d6c5257eb190f79c77437e84254470588f988a09  web/tests/lib/cdp.mjs
9d2d9ebf3860f890a28622340a2f313ad6be5b3c4bea470934a088123d639d57  web/tests/lib/chrome.mjs

CONTAINER: $ wc -lc  +  stat -c '%y %s'  <同六个文件>
441  17551  scripts/serve-web-demo.sh         2026-09-24 00:08:12
258  12006  scripts/web-demo-verify.sh        2026-09-24 01:44:07
355  37940  web/README.md                     2026-09-24 01:37:17
645  36817  web/tests/verify-two-page.mjs     2026-09-24 01:08:30
189   8184  web/tests/lib/cdp.mjs             2026-09-24 01:08:19
160   7215  web/tests/lib/chrome.mjs          2026-09-24 00:45:44
```

* **与 T7 冻结基准一致**：`reports/73` §14.2（01:46:34 实测）六行的 sha / 行 / 字节 / mtime 与本表**逐字相同**；本节 01:51:41 复取，其间无改动。
* **§3 六行已同步**为本表值（仅 sha256 / 行 / 字节三项，叙述与判定未动）。
* 时效说明：`scripts/web-demo-verify.sh` 在 01:44:07 还有一次文案修正（257 → 258 行 / 11 888 → 12 006 B），即 T7 §14.3 登记的 F-T7-2；本表取的是其后稳定值。此后任一件若再变，以**现场 `sha256sum`** 为准并另开再冻结登记。

### 12.2 `PROBE-RACE` 口径改写（F-T7-4；取代 §8.2 第 14 行与 §11.2(a) 的时点口径）

**现版口径**：`PROBE-RACE` 已由 t11 **结构性修复**；严重度由 medium 收敛为「**已修复，保留回归断言**」。

* 修复点（现场逐行核到）：`web/tests/lib/cdp.mjs:153` 的 `waitForReady(timeoutMs)`（轮询 `document.readyState === 'complete'`）、`web/tests/lib/cdp.mjs:167` 的 `waitForGlobal(name, timeoutMs)`、`web/tests/lib/cdp.mjs:187` 对非空白页调用 `waitForReady()`；调用方 `web/tests/verify-two-page.mjs:353` 与 `web/tests/verify-two-page.mjs:354` 增 `waitForGlobal('__probe', 20000)`。
* 绑定 sha：`web/tests/lib/cdp.mjs` = `2c896f1add8b9e63…`（189 行）；`web/tests/verify-two-page.mjs` = `c1d6a4267e628b1b…`（645 行）。
* 连绿证据：`reports/73` §14.4 记录现行 revision（`scripts/web-demo-verify.sh` @ `139ae5e7…`）上 `--mode probe` ×3 = **3/3 `19/19 passed`、rc=0**；ops 另报 `01:22:17Z–01:22:47Z` 连续 5 次 `19/19`（`FAILED_RUNS=0`）。两组均属**交付/复核证据**，不构成本报告的新独立判定。
* **保留回归断言**：`newPage()` 的就绪门必须长期存在；§11.2(a) 原文保留为 23:46 时点的准确记录，引用一律以本 §12.2 为准。

### 12.3 `REPO-TMP` 与 `chrome-env` 语义（现场）

* `REPO-TMP` 处置不变：**显式禁止**把运行期/缓存写进 `<repo>/tmp/**`；收尾自检 `git status --porcelain` 无 `?? tmp/`（本节取值时刻实测 = **0**）。
* `chrome-env` 语义更新：工作区内的 chrome-env 路径现为**空目录 + RECIPE.md（01:39 重建，3 643 B）**，**无缓存内容** ⇒ 只能作「素材路径」引用，**不可当作可用 Chrome 缓存**；可用缓存仍为仓库外可写卷（`tv-final` / `tv-cold` / `vv-neg`）。

### 12.4 两条 `reports/70` 相关登记（只登记，不自改）

`reports/70-browser-call-demo-requirements.md` 已冻结（`967cb530a682fd39…` / 396 行 / 44 037 B），本节**不修改**它：

1. **`--only reports/70` 的易失依赖**（T7 F-T7-3）：`reports/70` 第 315 行引用了工作区素材路径 tmp/chrome-env/RECIPE.md（P2 硬校验）。该文件被清理时 `--only reports/70` 曾 `EXIT=1`；01:39 重建后现场复测 **PASS (258 checks, 0 warnings) EXIT=0**。captain 二选一：(a) 把该素材路径材料化为运行前提（后续清理不再删除它）；(b) 授权对 `reports/70` 该行做有界修正。**不阻塞本轮**。
2. **A-3 围栏标签错误**（登记不自改）：`reports/70` §8 A-3 的围栏把**容器路径**标成 `HOST:`（容器 /data/dsh/home/workspace/code/webrtc-demo 与宿主机 /opt/dsh-workspaces/code/webrtc-demo 并非同一路径），属**标签错误**：形式过门禁但语义失败，用户照抄会 `cd: No such file or directory`。正确口径见本报告 §2.1 的落点分工表。

### 12.5 时点快照纪律与来源

* **时点快照纪律**：§11.3、本章各表，以及 `reports/72` / `reports/73` 的登记表**均为时点快照**；任何结论绑定「判定当时被测物的 sha」，引用前一律现场 `sha256sum` + 附时刻。
* 冻结基准来源：`reports/73-browser-demo-review.md` §14.2（T7 现场表，01:46:34）+ 本节 01:51:41 现场复取（逐字一致）。
* 受理来源：captain t13 派单；T7 findings F-T7-1 / F-T7-4 / F-T7-5；`reports/73` §10 / §11 / §14。
* 本节之后若交付面再变，按同一模式追加下一节，不改既有章节。
