# 70 — 浏览器视频通话 Demo：对接事实冻结与验收矩阵

> Status: **frozen**（本轮唯一判据来源）· Owner: `writer` · Task: t1 (attempt 1), 2026-09-23
> 目标：在既有 `webrtc-demo` 仓库内新增**纯静态浏览器视频通话 Demo**（Chrome），与已交付 Android App 经既有 Go 信令服务（容器内 `ws://172.18.0.1:8443/ws` / 公网 `ws://47.238.144.66:8443/ws`，coturn 3478；地址口径见 §2.1.1）真实互拨，并把**每一个通话流程**在浏览器控制台与页面面板上可见、可导出。
> 上游输入：captain 侦察结论（团队目标文本）、任务契约 t1 的 acceptance + verify。
> 下游：T2/T3/T4（实现）、T5（独立验证 → **reports/72-browser-demo-verification.md**）、T6（整合 → **reports/71-browser-call-demo.md** + 根 README 双语小节）、T7（独立复核 → **reports/73-browser-demo-review.md**）。
> **判据优先级**：captain 当日裁定 / 任务契约（acceptance + verify） > 本文件 > 任何成员的自述与任务 output。
> **本文件不实现任何改动**：不改 `app/**`、`signaling/**`、`doc/design/**`、`scripts/doc-verify.sh`、`scripts/i18n-audit.sh`、既有 `reports/54`–`reports/69`。
>
> **书写约定（为让本文件在目标工件落地前即通过自身子集门禁）**：已存在的仓库路径写反引号并带 `file:LINE` 引用；**尚未落地**的路径（**web/**、**scripts/serve-web-demo.sh**、**scripts/web-demo-verify.sh**、**reports/71**、**reports/72**、**reports/73**、**web/tests/signaling.e2e.mjs**）写**粗体纯文本**，落地后由 T6/T7 改为反引号；容器与宿主机绝对路径按 `doc/design/SPEC.md` §7.2 记 HOST: 或写进围栏。
> **修订记录**：r1（captain 裁定，2026-09-23，依据 verifier 复测）——更正 A-3 的环境前提：**容器内为主路径**（chrome-headless-shell + 依赖 .deb 解包 sysroot + `LD_LIBRARY_PATH`、Node 起静态服务、零 npm 依赖），宿主机侧仅用于面向用户的静态页；回退前提收紧为「容器与宿主机两条路径都失败且有原始报错」。本文件不登记自身 sha256。

---

## 0. 效力、口径与使用方式

1. 本文件是 T2–T7 的**唯一契约来源**。任何实现/验证报告与本文冲突时，以本文为准；若确需偏离，必须由 captain 裁定并在本文件顶部登记，不得就地解释。
2. **事实优先于复述**：本文件第 2 节的每条事实都带当前 HEAD 的 `file:LINE` 引用；引用行号漂移时按站点内容定位，不得按旧行号盲改（§0.2）。
3. **每个验收项在 T5 报告中必须独立复跑**；引用本文件时必须连**实测值与退出码**一起引用，不得只引用结论。
4. **不得美化**：未做/未通过的项在 T5、T6 报告中如实登记为 open item；「环境不可用」只能走 §8 A-3 声明的替代证据路径，且必须显式标注替代，不得写成已通过。
5. 本文件自身的冻结绑定：任一被冻结的事实（消息字段与方向、常量取值、端口/配额、交付物白名单）变化，本文件即失效，须由 captain 重派后重出。
6. 本轮报告编号：**70 = 本契约**（T1）、**71 = 交付报告**（T6）、**72 = 独立验证**（T5）、**73 = 独立复核**（T7）。
7. **术语**：本文件用中文叙述；协议字面量（消息 type、字段名、错误码、日志键、路径）保持源码拼写。中文定译沿用 `doc/design/zh-CN/GLOSSARY.md` §6。

### 0.1 效力边界（不做的事）

* 不引入 App/服务端日志通道；观测面只有浏览器控制台 + 页面面板（§6、H-4）。
* 不引入构建步骤与 npm 依赖；页面为纯静态 ES Module（§9 H-3）。
* 不修改 `app/**`、`signaling/**`、`doc/design/**`、`scripts/doc-verify.sh`、`scripts/i18n-audit.sh`、`reports/54`–`reports/69`（§9 H-1）。
* 不新增/删除 `doc/design/zh-CN/**` 页面，不触碰 `docs` 符号链接（§9 H-5）。

### 0.2 撰写时基线（现场实测，2026-09-23）

```text
cd /data/dsh/home/workspace/code/webrtc-demo
git rev-parse HEAD                                   # 4f2f7e2ecefa5fa8456c3e227c998257fe766d85
git status --porcelain                               # 空（本文件写入前）
bash scripts/doc-verify.sh >/dev/null 2>&1; echo "EXIT=$?"   # EXIT=0
bash scripts/doc-verify.sh 2>&1 | tail -1            # doc-verify.sh: PASS (4797 checks, 4 warnings)
bash scripts/i18n-audit.sh >/dev/null 2>&1; echo "EXIT=$?"   # EXIT=0
bash scripts/i18n-audit.sh 2>&1 | tail -1            # i18n-audit.sh: PASS (133 checks, 0 warnings)
```

| 工件 | sha256（撰写时） | 字节 | 行数 | 权限 |
|---|---|---|---|---|
| `scripts/doc-verify.sh` | `9c154dbe6169f00d4f8fe6e5f3846d11f9e5c7f34eec18870ee73dc3c90454d6` | 46 617 | 1 029 | `-r--r--r--` (0444) |
| `scripts/i18n-audit.sh` | `c778c83127489f3f8b36b72b2ab0e6b1afe381a66e1325b659aea3c29e4c0828` | 45 596 | 1 089 | `-rwxr-xr-x` (0755) |
| `README.md` | `8f42fd8932c6cf2afc93567d88b1ba9bec80a6ff86d9443002c93fff13ce9f65` | 3 211 | 46 | `-rw-------` (0600) |
| `README.en.md` | `72b7a3c45ff87d9ecb45e8579a83be10b3132ba63fa802ad8ef87970a54262b8` | 3 235 | 61 | `-rw-r--r--` (0644) |
| `signaling/protocol/message.go` | `35e671787e561d3023c859f578627f36d922c0a9e919d61cb2627429ecc35641` | — | — | 只读引用 |
| `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt` | `7c32c5db0f6da640c72072e26c2c2394e3956dd7e4bc8ec36f1ad320c36ab49f` | — | — | 只读引用 |
| `deploy/turnserver.conf` | `afdcd79b0168a1d729b2e8a5ae9177af63e44b9e3aa0b85005e2b433744479e5` | — | — | 只读引用 |

* 门禁脚本在 T2–T7 期间**必须保持同值**（`scripts/i18n-audit.sh` 的同类运行值）；任一变化即视为门禁扰动，须报 captain（§10）。
* 容器内工具现状（用于 §8 的可用性判定）：`node` v24.21.0 可用；**无 `python3`**、**无预装 Chrome/Chromium**。**t5 的无头双页媒体验证以容器内为主路径**（容器无 `python3`/预装 Chrome，但可用 **chrome-headless-shell + 依赖 .deb 解包 sysroot + `LD_LIBRARY_PATH`** 自举，Node 起静态服务，且**零 npm 依赖**）；**宿主机侧仅用于面向用户的静态页**（`python3 -m http.server`，运行而非安装）。前提与回退见 §8 A-3。

---

## 1. 目标与范围

**目标**：在浏览器（Chrome）与真机 App 之间建立一次可观测的真实 WebRTC 通话，并让**从信令到媒体**的每一步都可在浏览器侧看到、导出、复跑。

| 编号 | 范围 | 交付物（落地后为反引号） |
|---|---|---|
| S1 | 浏览器信令客户端 + 时间线（纯 ES Module，无构建） | **web/lib/signaling.js**、**web/lib/timeline.js**、**web/tests/signaling.test.mjs** |
| S2 | 页面 + WebRTC 媒体 + 观测面板 + 流程自测矩阵 | **web/index.html**、**web/app.js**、**web/lib/peer.js**、**web/lib/observe.js**、**web/lib/selftest.js**、**web/lib/style.css** |
| S3 | 宿主机运行与无头验证环境 + 联调手册 | **scripts/serve-web-demo.sh**、**scripts/web-demo-verify.sh**、**web/README.md** |
| S4 | 独立验证与整合文档 | **web/tests/signaling.e2e.mjs**、**reports/72-browser-demo-verification.md**、**reports/71-browser-call-demo.md**、**reports/73-browser-demo-review.md** |

**非目标**：不改服务端与 App；不做 TLS/HTTPS 部署；不做移动浏览器适配；不引入 App/服务端日志通道；不做录制/回放与多人会议（房间上限 2 人）。

---

## 2. 信令对接事实（冻结）

### 2.1 端点与帧

* 单一 WebSocket 端点：路径常量 `PathWS` = `/ws`（`signaling/server/server.go:25`）；进程监听 `-addr :8443`（`deploy/signaling.service:28`）。**可用地址只有两个**，等价性、禁止地址与健康检查见 §2.1.1。
* 每帧都是 **UTF-8 JSON 文本帧**，判别字段是 `type`（`signaling/protocol/message.go:41` 的最小信封只解析该字段用于路由）。
* 单条消息上限 `MaxMessageSize` = 64 KiB（`signaling/config/config.go:29`），读循环通过 `SetReadLimit` 施加（`signaling/room/peer.go:221`）；超限时 gorilla 以 1009 关闭连接。
* 客户端编解码语义（浏览器侧必须对齐）：忽略未知键、**不忽略缺失必填键**（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:143`，`ignoreUnknownKeys = true` 在 `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:146`）。

引用：`signaling/server/server.go:25`、`signaling/protocol/message.go:41`、`signaling/config/config.go:29`、`signaling/room/peer.go:221`、`deploy/signaling.service:28`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:143`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:146`

### 2.1.1 信令端点：两个具体地址、等价性与禁止地址（captain 指令，逐字条款）

* **容器内联调**：`ws://172.18.0.1:8443/ws`（= 容器 bridge 网关，即宿主机；撰写时实测 `ping`→`pong` ✅）。
* **公网/真机**：`ws://47.238.144.66:8443/ws`（撰写时实测 `ping`→`pong` ✅）。
* **等价性说明**：二者是**同一个信令服务的两个可达地址**（同一进程、同一 `-addr :8443`）。容器内使用 bridge 网关地址直连宿主机；浏览器页面与真机默认使用公网明文地址；页面输入框可覆盖。**不得**含糊写成「宿主机 :8443」或 `localhost:8443`。
* **明确禁止**：`127.0.0.1:8443`（撰写时实测 HTTP 400 / WS error）——容器内该端口是 **DSH Harness 自己的 Caddy**（`tls internal`，反代 :3080），**不是**信令服务；也不得写 `https://…:8443`（撰写时实测 curl exit 35，8443 仅明文）。
* **健康检查（真机联通性 preflight）**：`http://47.238.144.66:8443/healthz` 返回 JSON，含 `roomGraceSec` / `stunUrl` / `turnUrl`（撰写时实测 HTTP 200、`Content-Length: 454`，`roomGraceSec` = 90、`stunUrl` = `stun:47.238.144.66:3478`、`turnUrl` = `turn:47.238.144.66:3478?transport=udp`）；字段发射点在 `signaling/server/server.go:117`。

引用：`signaling/server/server.go:25`、`signaling/server/server.go:117`、`deploy/signaling.service:28`

### 2.2 14 类消息表（方向 + 字段 + 结构体位置）

type 值一律按源码原样书写（判别字段为 `type`）。方向中 `C→S→C` 表示服务端按 `roomId` 原样转发给同房间对端。

| 结构体（Go） | type 值 | 方向 | 字段（JSON 名） | 引用 |
|---|---|---|---|---|
| `CreateRequest` | create | C→S | type | `signaling/protocol/message.go:46` |
| `CreatedResponse` | created | S→C | type, roomId, stunUrl, turnUrl, turnUsername, turnCredential | `signaling/protocol/message.go:51` |
| `JoinRequest` | join | C→S | type, roomId | `signaling/protocol/message.go:61` |
| `JoinedResponse` | joined | S→C | type, roomId, stunUrl, turnUrl, turnUsername, turnCredential, **peerId（仅此消息有）** | `signaling/protocol/message.go:67` |
| `PeerJoinedNotify` | peerJoined | S→C | type, peerId | `signaling/protocol/message.go:78` |
| `PeerLeftNotify` | peerLeft | S→C | type, peerId | `signaling/protocol/message.go:84` |
| `OfferMessage` | offer | C→S→C | type, sdp（**仅此两项，无其它字段**） | `signaling/protocol/message.go:90` |
| `AnswerMessage` | answer | C→S→C | type, sdp | `signaling/protocol/message.go:96` |
| `IceMessage` | ice | C→S→C | type, candidate, sdpMid（可缺省）, sdpMLineIndex（可缺省，Go 侧为指针以区分「缺失」与「显式 0」） | `signaling/protocol/message.go:106` |
| `NatTypeMessage` | natType | C→S→C | type, natType | `signaling/protocol/message.go:114` |
| `LeaveMessage` | leave | C→S | type | `signaling/protocol/message.go:120` |
| `ErrorMessage` | error | S→C | type, code, message | `signaling/protocol/message.go:125` |
| `PingMessage` | ping | C→S | type, timestamp（Unix 毫秒） | `signaling/protocol/message.go:132` |
| `PongMessage` | pong | S→C | type, timestamp（服务端当前时间） | `signaling/protocol/message.go:138` |

补充冻结：

* **服务端独占类型**（客户端若发送则回 `INVALID_MESSAGE`）：created、joined、error、peerJoined、peerLeft、pong（`signaling/protocol/message.go:145`、`signaling/protocol/message.go:155`）。
* **转发类型**：offer、answer、ice、natType（`signaling/protocol/message.go:171`）。
* **NAT 类型枚举** 6 值：Open、FullCone、RestrictedCone、PortRestrictedCone、Symmetric、Unknown（`signaling/protocol/message.go:31`–`signaling/protocol/message.go:36`），校验函数 `IsValidNatType` 在 `signaling/protocol/message.go:180`。
* 浏览器侧必须**按 `type` 分派**，不得依赖到达顺序；未知类型必须记入时间线并忽略（不得抛异常中断连接）。

引用：`signaling/protocol/message.go:31`、`signaling/protocol/message.go:36`、`signaling/protocol/message.go:46`、`signaling/protocol/message.go:51`、`signaling/protocol/message.go:61`、`signaling/protocol/message.go:67`、`signaling/protocol/message.go:78`、`signaling/protocol/message.go:84`、`signaling/protocol/message.go:90`、`signaling/protocol/message.go:96`、`signaling/protocol/message.go:106`、`signaling/protocol/message.go:114`、`signaling/protocol/message.go:120`、`signaling/protocol/message.go:125`、`signaling/protocol/message.go:132`、`signaling/protocol/message.go:138`、`signaling/protocol/message.go:145`、`signaling/protocol/message.go:155`、`signaling/protocol/message.go:171`、`signaling/protocol/message.go:180`

### 2.3 created/joined 的 ICE 下发与静态账号

* ICE 配置**只能**来自服务端下发：`created` 的 `stunUrl`/`turnUrl`/`turnUsername`/`turnCredential`（`signaling/server/ws_handler.go:226`）与 `joined` 的同四字段（`signaling/server/ws_handler.go:287`）。浏览器侧**禁止硬编码**任何 ICE 服务器或凭据。
* `joined` 额外下发本端分配到的 `peerId`（`signaling/protocol/message.go:74`，形态 peer-001 / peer-002）。
* 真机现网实拨值（captain 侦察结论 + §2.7 原始探针）：`stun:47.238.144.66:3478`；`turn:47.238.144.66:3478?transport=udp`；账号 `demo` / `demopass`（`deploy/signaling.service:28` 的 `-stun`/`-turn`/`-user`）。账号与 URL 均为**服务端下发**，浏览器不得假设其值（不同部署可不同）。
* 房间号：6 字符、字符集 `[A-Z2-9]` 的严格子集（排除 O/0/I/1/L，`signaling/util/roomid.go:18`），长度常量 `RoomIDLength`（`signaling/util/roomid.go:12`）；房间容量 `RoomMaxPeers` = 2（`signaling/room/room.go:13`）。

引用：`signaling/server/ws_handler.go:226`、`signaling/server/ws_handler.go:287`、`signaling/protocol/message.go:74`、`signaling/util/roomid.go:12`、`signaling/util/roomid.go:18`、`signaling/room/room.go:13`、`deploy/signaling.service:28`

### 2.4 转发语义：服务端不改写 SDP

* offer/answer 只校验 `sdp` 非空，然后**按原始字节**转发（`signaling/server/ws_handler.go:326`、`signaling/server/ws_handler.go:336`）；转发入口统一为「原样转发」（`signaling/server/ws_handler.go:379`）。
* ICE 候选与 natType 同样原样转发（`signaling/server/ws_handler.go:340`、`signaling/server/ws_handler.go:359`）；候选缺少媒体标识字段时只告警并继续转发（`signaling/server/ws_handler.go:350`）。
* **推论（冻结）**：编解码选择、bundle/rtcp-mux、重协商全部是**客户端责任**；浏览器侧必须自己保证与 App 的 SDP 兼容（§5）。

引用：`signaling/server/ws_handler.go:326`、`signaling/server/ws_handler.go:336`、`signaling/server/ws_handler.go:340`、`signaling/server/ws_handler.go:350`、`signaling/server/ws_handler.go:359`、`signaling/server/ws_handler.go:379`

### 2.5 错误码与客户端处置

服务端 6 个错误码（`signaling/protocol/errors.go:6`）：

| 错误码 | 默认 message | 触发场景 |
|---|---|---|
| ROOM_NOT_FOUND | Room does not exist | join 不存在的房间（`signaling/server/ws_handler.go:277`） |
| ROOM_FULL | Room is full (max 2 peers) | 第三端 join，或掉线方宽限期内尚未被回收时重连（`signaling/server/ws_handler.go:283`） |
| ROOM_EXPIRED | Room has expired | 建房后 30 分钟无人加入被销毁（`signaling/config/config.go:26`） |
| INVALID_MESSAGE | Invalid message format | JSON 解析失败 / 缺必填字段 / type 非法或为服务端独占类型 |
| NOT_IN_ROOM | You are not in a room | 未 join 就发 offer/answer/ice/leave |
| INTERNAL_ERROR | Internal server error | 服务内部错误 |

浏览器侧处置判据（对齐 App 的纯函数策略，`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:823` 的 `SignalingErrorPolicy`）：

* **终态**（抑制重连、清空房间意图）：`TERMINAL_CODES` = ROOM_NOT_FOUND、ROOM_EXPIRED、INVALID_MESSAGE、NOT_IN_ROOM（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:849`）。
* **暂时性**：`CODE_ROOM_FULL` 在「曾经在房内、掉线后重连」语境下走有界指数退避重试 join，且**必须保留房间意图**（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:862`）。
* 其它/未知码：`Action.SURFACE`，不抑制、不重试，仅呈现给 UI（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:863`）。
* 浏览器面板必须把每个收到的 error 连同 code 显示并写入时间线；终止性错误必须在面板给出「房间已失效」的可读结论。

引用：`signaling/protocol/errors.go:6`、`signaling/server/ws_handler.go:277`、`signaling/server/ws_handler.go:283`、`signaling/config/config.go:26`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:823`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:849`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:862`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:863`

### 2.6 心跳、读超时与保座宽限期

| 参数 | 值 | 依据 |
|---|---|---|
| 客户端 ping 间隔 | 15 s | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79` |
| 单窗口 pong 超时 | 5 s | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82` |
| 连续丢窗口容忍 | 4 | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91` |
| 有效判活阈值 | 20 s | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:94` |
| 服务端读超时 | 45 s（3 × 15 s） | `signaling/config/config.go:38` |
| 席位宽限期 | 90 s | `signaling/config/config.go:54`、`deploy/signaling.service:28`（`-room-grace 90s`） |
| 房间过期 | 1800 s | `signaling/config/config.go:26` |
| 对端发送队列等待 | 5 s | `signaling/config/config.go:42` |

宽限期语义（浏览器必须能复现并观测）：WS 瞬断后房间与席位**保留 90 s**，期间不回收、不给在线对端发 peerLeft，断开方凭原 roomId 同身份重连（回 joined、拿回原 peerId）；宽限期满才回收该席位并**只发一次** peerLeft（`signaling/room/manager.go:225`、`signaling/room/manager.go:258`）。同室掉线方重连属**席位接管**（`signaling/room/manager.go:161`、`signaling/room/manager.go:184`）。

引用：`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:94`、`signaling/config/config.go:26`、`signaling/config/config.go:38`、`signaling/config/config.go:42`、`signaling/config/config.go:54`、`signaling/room/manager.go:161`、`signaling/room/manager.go:184`、`signaling/room/manager.go:225`、`signaling/room/manager.go:258`、`deploy/signaling.service:28`

### 2.7 原始探针证据（撰写时实测）

对 live 信令服务 `ws://47.238.144.66:8443/ws` 的原始往返（用于 T5 对齐，不可当作「已实现」的证据）：

```text
create            → {"type":"created","roomId":"K9JUCD","stunUrl":"stun:47.238.144.66:3478",
                     "turnUrl":"turn:47.238.144.66:3478?transport=udp",
                     "turnUsername":"demo","turnCredential":"demopass"}
ping              → {"type":"pong","timestamp":1790170970723}
join ZZZZZZ       → {"type":"error","code":"ROOM_NOT_FOUND","message":"Room ZZZZZZ does not exist"}
offer(未入房)      → {"type":"error","code":"NOT_IN_ROOM","message":"cannot send offer before create/join"}
type=bogus        → {"type":"error","code":"INVALID_MESSAGE","message":"unknown type \"bogus\""}
```

* 探针脚本只依赖容器内 node（v24.21.0，内置 WebSocket）；原始输出留痕在 **reports/72-browser-demo-verification.md**（T5 必须重跑并给出自己的原始输出，不得直接引用本节）。
* 房间号字符集 `[A-Z2-9]` 与 `roomId` 长度 6 已由上面 `K9JUCD` 佐证；`created` 的字段顺序不构成契约，字段**集合**才是。

---

## 3. 浏览器侧行为判据（B-1 … B-12）

所有条目均为**可判定**；括号内是对齐的 App 常量（浏览器侧必须用同名语义实现，常量值一致）。

* **B-1 端点与帧**：单 WebSocket，连接 `<endpoint>/ws`；只发 UTF-8 JSON 文本帧；判别字段 `type`；未知键忽略、缺失必填键抛错并在面板显示（§2.1）。
* **B-2 心跳**：连接建立后每 **15 s** 发一次 ping（携带本端毫秒时间戳）；每次 ping 开启一个 **5 s** 窗口；窗口内未见 pong 记为一次 miss，**连续 4 次** miss 才判定线路失效（有效阈值 **20 s**）；单次 miss 只记事件、继续发 ping（`PING_INTERVAL_MS`、`PONG_TIMEOUT_MS`、`PONG_MISS_TOLERANCE`、`PONG_FAIL_AFTER_MS`）。
* **B-3 重连退避**：socket 断线与 rejoin **共用同一套**指数退避：1 s、2 s、4 s、8 s、8 s…（单次上限 8 s），最多 **10 次**，累计预算 **63 s**（`rejoinDelayMs`、`MAX_REJOIN_ATTEMPTS`、`rejoinBudgetMs`）；预算须覆盖服务端 45 s 读超时并小于 90 s 宽限期。
* **B-4 分派**：只按 `type` 分派；不得按到达顺序假设（心跳与通话消息共享同一流）。
* **B-5 待发缓冲**：监听器未注册期间最多缓冲 **32** 条（超出丢弃最旧），注册后按序补投（`MAX_PENDING_MESSAGES`）。
* **B-6 close 语义**：显式处理 close code **1000**（正常关闭 → 不重连）；重连前必须先关闭/取消旧 socket，避免双 socket。
* **B-7 错误处置**：按 §2.5 的三分类处理；ROOM_FULL 在重连语境下保留意图并有界重试。
* **B-8 掉线保座**：模拟掉线（页面提供按钮：仅关闭 WS，不 leave）后，服务端在 90 s 内保留席位；浏览器必须在 §8 A-4 的自测中观察到「重连成功 + 拿回原 peerId + 对端未收到 peerLeft」。
* **B-9 主动离开**：Leave 发送 leave 后正常关闭（1000）；面板与时间线记录对端 peerLeft（若本端是留驻方）。
* **B-10 房主职责**：offer 责任**仅在房主**（建房方）。默认路径 = App 建房（App 为房主/offerer）→ 浏览器 Join 后收 offer 并作答；浏览器建房模式 = 浏览器为房主，收到 peerJoined 后主动 `createOffer`（对齐 `createOffer` 的时机）。
* **B-11 ICE restart**：ICE restart 的**发起责任也只在房主**；非房主不得自动 restart；`MAX_ICE_RESTARTS` = **2**，超限后在面板登记并停止（`restartIce`）。
* **B-12 确定性日志**：关键事件（信令收发、状态跃迁、候选、stats、错误）必须同时 `console.log`（前缀固定，便于过滤），并写入页面面板与导出物（§6）。

引用：`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:94`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:104`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:117`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:128`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:156`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163`、`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:170`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:402`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`

---

## 4. ICE / TURN 判据（I-1 … I-6）

* **I-1 配置来源**：ICE 服务器列表**完全**由服务端 `created`/`joined` 下发构造；每个下发字段非空才加入；禁止任何硬编码服务器或凭据（§2.3，对齐 `iceServersFor` 的「`ice == null` ⇒ 空列表」语义）。
* **I-2 UDP 优先 + TCP 回退**：TURN 条目**先**加 `turn:…?transport=udp`，**再**追加同凭据的 `transport=tcp` 条目作为回退；已下发 `turns:` 或非 `turn:` 前缀时不得重复添加（`turnTcpUrl`、`DEFAULT_TURN_TCP_FALLBACK` = true）；回退项是**第二个** RTCIceServer 条目，顺序可断言（`turnTcpFallbackActive`）。
* **I-3 强制 relay 开关**：页面提供开关，打开时以 `iceTransportPolicy = "relay"` 建连（RTCPeerConnection 构造参数），关闭时为 `"all"`；该开关是 relay-only 复现路径的唯一手段（对齐 App 的 RELAY/ALL 切换）。
* **I-4 loopback 过滤**：本端候选为回环地址（127.0.0.0/8、::1）时**不发送**；收到远端回环候选时**丢弃**并计数（`LoopbackCandidates.isLoopback`）；面板显示两个计数。
* **I-5 relay 端口与配额**：relay 端口区间 **49152-49200**（共 49 个，`min-port`/`max-port`）；`total-quota` = **45**、`user-quota` = **8**、`max-allocate-lifetime` = 600 s；因此**并发 relay 分配上限 45**，且单客户端最多 8 个。真实联调中「relay 分配失败 / 508」必须被识别为**配额或端口池耗尽**，而不是信令故障。
* **I-6 coturn 监听事实**：配置声明 `listening-port=3478`、`external-ip=47.238.144.66/172.21.0.219`、`realm=webrtc-demo`、`lt-cred-mech`（长凭据）与 TLS 证书/私钥（`cert`/`pkey`）；但防火墙**只放行** 3478/udp、3478/tcp、49152-49200/udp、8443/tcp，**5349（TLS/DTLS）未放行**。结论：浏览器侧只需支持 `turn:`（UDP/TCP）；不得依赖 `turns:`。

引用：`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:52`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:66`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:95`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:107`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:155`、`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:162`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:605`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:886`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1581`、`deploy/turnserver.conf:4`、`deploy/turnserver.conf:6`、`deploy/turnserver.conf:8`、`deploy/turnserver.conf:9`、`deploy/turnserver.conf:10`、`deploy/turnserver.conf:14`、`deploy/turnserver.conf:16`、`deploy/turnserver.conf:42`、`doc/design/02-architecture.md:63`

---

## 5. 媒体判据（M-1 … M-6）

* **M-1 浏览器**：**只支持 Chrome**（桌面版）。页面必须做能力探测并在非 Chrome 上给出明确提示；不承诺 Safari/Firefox/移动端。
* **M-2 可信来源**：页面经 VSCode Remote-SSH 端口转发以 `http://localhost:<静态服务端口>` 打开（默认 **8081**；免 TLS 的可信来源），getUserMedia/getDisplayMedia 与 RTCPeerConnection 才能正常工作；非 localhost 的明文来源必须被文档明确标为不受支持。**两个 localhost 不得混写**：静态服务在 8081（页面来源，需要转发），信令在 **8443 是另一个端口**，页面默认直连 `ws://47.238.144.66:8443/ws`，**无需转发 8443**（容器内工具用 `ws://172.18.0.1:8443/ws`，见 §2.1.1）。
* **M-3 SDP 语义**：统一 plan（Chrome 默认）；`bundlePolicy` 语义等价 max-bundle、`rtcpMuxPolicy` 等价 require；浏览器 offer 必须含 `a=group:BUNDLE`，answer 必须接受 App 的 bundle（对齐 App 侧 UNIFIED_PLAN/MAXBUNDLE/REQUIRE）。
* **M-4 角色与媒体方向**：**默认路径 = 真机 App 建房（App 为 offerer）→ 浏览器 Join 并 answer**；浏览器必须发送本地音视频轨（至少视频）并接收远端轨并在页面渲染。另需提供**浏览器建房模式**（浏览器 offerer），用于无真机时的浏览器↔浏览器自测与回归。
* **M-5 编解码**：**VP9 是期望编解码**（App 侧编码器工厂只提供 VP9）。判定必须在浏览器侧由 `getStats()` 证明：`outbound-rtp`（以及 `inbound-rtp`）的 codecId 必须能解析到一条 codec 统计，其 mimeType 为 **video/VP9**；只看到 codecId 或只看到 `rtx`/`red` 不算通过。浏览器为 offerer 时必须用编解码偏好把 offer 限制在 VP9（避免协商出 VP8/H264），为 answerer 时不得移除已协商的 VP9。
* **M-6 连接与媒体成立判据**：iceConnectionState/connectionState 达到 connected（或 completed），dtlsState 达到 connected，且**双向** RTP 均有字节增长（本地 `outbound-rtp` bytesSent 增长 + 远端轨道 `inbound-rtp` bytesReceived 增长），至少持续 5 s；单向媒体必须判 FAIL 并在面板给出方向。

引用：`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:13`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:142`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:149`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:150`、`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:151`、`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:35`、`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:96`

---

## 6. 观测面判据（O-1 … O-8）

**唯一观测面 = 浏览器控制台 + 页面面板**；不引入 App/服务端日志通道（H-4）。

* **O-1 信令时间线**：逐帧记录 方向（send/recv）/ 本地时刻（ms，单调）/ type / 原始 JSON / 关键字段摘要；支持清空。
* **O-2 SDP 原文 + 解析**：面板并排显示 offer/answer 原文与解析结果：m= 段清单、codec 与 payload type、ICE ufrag/pwd、DTLS fingerprint、候选行数、SDP 字节数。
* **O-3 ICE 候选表 + 选中候选对**：每行含 类型（host/srflx/prflx/relay）、协议（udp/tcp）、地址:端口、优先级、foundation、来源（本端/远端）；另列选中候选对及其 RTT 与收发字节。
* **O-4 状态机跃迁**：记录 signaling / iceGathering / iceConnection / connection / dtls 五条状态机的每次跃迁（旧值 → 新值 + 时刻）。
* **O-5 1 Hz stats**：每秒采样并归约 RTP/RTCP：码率、分辨率、fps、jitter、丢包、NACK/PLI/FIR 计数、RTT；至少覆盖 inbound-rtp、outbound-rtp、remote-inbound-rtp、candidate-pair、transport、codec。
* **O-6 事件与错误**：所有 error/异常/超时/过滤（含 loopback 过滤计数、配额类失败）必须进入同一事件流并标红色级别。
* **O-7 导出**：面板提供导出按钮，导出 JSON（完整时间线 + 候选 + stats 快照 + 状态跃迁）与 CSV（时间线、候选表、stats 序列）；导出物含生成时刻与页面 URL。
* **O-8 控制台**：关键事件同时 `console.log`，前缀固定（例如 `[sig]`/`[ice]`/`[stats]`/`[err]`），使「打开 DevTools 即可复跑观测」成立。

---

## 7. 操作与流程矩阵（F-1 … F-7）

页面必须提供按钮/开关：Create、Join（输入 roomId）、Leave、ICE restart、Renegotiate、强制 relay、静音、**模拟掉线**（只断 WS，不 leave）。流程矩阵覆盖（对齐 `doc/design/06-flows.md` 的 7 条流程）：

| 编号 | 流程 | 浏览器侧期望观测 |
|---|---|---|
| F-1 | 建房/加入/首个 offer-answer/ICE 连通 | 时间线含 create→created（或 join→joined）→peerJoined→offer/answer→ice*；状态机到 connected；stats 出现 VP9 |
| F-2 | 信令瞬断 → 重连 → 拿回席位 | 掉线期间对端**不收到** peerLeft；重连成功回 joined 且 peerId 不变（≤90 s 内） |
| F-3 | 房间失效（ROOM_NOT_FOUND） | 收到 error 后进入终态：抑制重连、清空房间意图、面板显示「房间已失效」 |
| F-4 | 对端离开 / 等待对端 / 再次加入 | 收到 peerLeft → 面板回「等待对端」；再次 join 同房间可重新协商 |
| F-5 | ICE restart 与防冲突分工 | 只有房主可发起；次数 ≤ MAX_ICE_RESTARTS = 2；非房主发起的按钮禁用或明确提示无效 |
| F-6 | 强制 relay 复现 | 打开强制 relay 后选中候选对必须是 relay；实际地址/端口落在 49152-49200 区间 |
| F-7 | 房主侧重协商 / 编码兜底观测 | Renegotiate 只由房主触发；codec 变化/兜底在事件流可见（无 VP9 时如实登记 open item） |

---

## 8. 验收矩阵（A-1 … A-8，可复跑命令 + 证据形态）

所有命令默认工作目录 `/data/dsh/home/workspace/code/webrtc-demo`。带 **HOST** 标注的在宿主机执行（容器内无 python3/Chrome）。

### A-1 纯模块单测（容器内，必须）

```bash
node web/tests/signaling.test.mjs; echo "EXIT=$?"
```

期望：PASS 汇总（编解码往返、未知键/缺失键、心跳与 pong 超时判定、退避序列 1/2/4/8/8…、缓冲上限 32、分派顺序、close 1000），`EXIT=0`。证据形态：原始 stdout（含 `PASS`/`FAIL` 计数行）。

### A-2 信令层 e2e（容器内，对 live 服务，必须）

```bash
node web/tests/signaling.e2e.mjs; echo "EXIT=$?"
```

期望：对 §2.1.1 的**容器内联调地址** `ws://172.18.0.1:8443/ws`（容器 bridge 网关 = 宿主机）跑通 create/join/peerJoined/offer/answer/ice/leave 全流程与错误路径（ROOM_NOT_FOUND、NOT_IN_ROOM、INVALID_MESSAGE），`EXIT=0` 并打印逐条收发记录；同一脚本对**公网地址** `ws://47.238.144.66:8443/ws` 也必须可跑通（二选一为默认，另一个作为参数/环境变量）。**不得只测本地 mock**；live 服务不可达时必须判 FAIL 并说明，不得静默跳过。**明确禁止**在脚本或文档中把 `127.0.0.1:8443` 当作信令地址（§2.1.1：那是 DSH Harness 的 Caddy），也禁止 `https://…:8443`。

### A-3 无头双页媒体端到端（CONTAINER 优先，宿主机可选）与替代路径

**两个 localhost 端口各自的用途（不得混写）**：

| 端口 | 用途 | 是否经 VSCode 转发 | 说明 |
|---|---|---|---|
| `http://localhost:<静态服务端口>`（默认 **8081**） | 打开页面 | **是**（PORTS 面板转发 8081） | `getUserMedia` 所需的 secure context 来源 |
| `ws://47.238.144.66:8443/ws`（信令） | 页面连信令 | **否，无需转发 8443** | 页面默认直连公网明文地址；容器内工具改用 `ws://172.18.0.1:8443/ws`（§2.1.1） |

```bash
HOST: cd /data/dsh/home/workspace/code/webrtc-demo
HOST: bash scripts/serve-web-demo.sh start
HOST: bash scripts/web-demo-verify.sh; echo "EXIT=$?"
```

期望：两个无头 Chrome 页经真实信令建立通话，断言 §2.2 消息可收发、ICE/DTLS connected、双向 RTP 字节增长、`outbound-rtp` codec 解析为 VP9、stats 可导出；输出 PASS/FAIL 与原始日志（**HOST: /tmp/web-demo-verify.log**）。

**t5 判定入口与交叉核对（captain 指令，逐字条款）**：T5 必须以**交付物自带入口** **scripts/web-demo-verify.sh** 的复跑结果作为**主证据**；并**另写独立 CDP 断言**（Chrome DevTools Protocol，对两个页面做同样的 connected / 双向 RTP / VP9 断言）作为**交叉核对**。**不得**把非交付物 `tmp/chrome-env/**`（`tmp/chrome-env/RECIPE.md`、`tmp/chrome-env/tools/*.mjs`）当作判定逻辑——它们只是 T3 的实现素材，最多可以在报告中作为「素材来源」被引用，且必须在同一句注明其非交付物属性。

**环境不可用时的如实回退（硬约束）**：**仅当容器路径与宿主机路径都失败且有原始报错时**才启用手工矩阵；T5 必须：(a) 在报告中**显式声明** A-3 未执行及其原因（容器与宿主机两侧的原始报错都要给）；(b) 改用「用户侧手工矩阵」作为替代证据——最小点击清单 = 转发 8081 → Chrome 打开 http://localhost:8081 → Create/Join → 观察面板 6 区 → 导出 JSON → 与 App 互拨 → 截图/导出物路径；(c) **不得**把替代路径写成已通过，替代证据必须标注为「手工矩阵，未由无头环境验证」。

### A-4 浏览器↔浏览器自测矩阵（代码内，必须）

```js
// web/lib/selftest.js：第二个标签页作为「手机模拟器」页面
// 页面按钮 / 控制台入口
selftest.run();   // 断言 create→join→offer/answer→ice→connected→媒体→leave，输出 console.table + PASS/FAIL
```

期望：PASS/FAIL 表 + 可导出 JSON；失败项必须列出期望跃迁与实际跃迁。该矩阵是 §7 F-1…F-5 的自动化版本。

### A-5 真机联调清单（用户侧，必须给步骤）

1. **preflight（真机联通性）**：`curl -s http://47.238.144.66:8443/healthz` 必须返回 200 且含 `roomGraceSec` = 90、`stunUrl`、`turnUrl`（§2.1.1）；失败即停，不得把信令不可达误判为浏览器问题。
2. 宿主机 `bash scripts/serve-web-demo.sh start`，在 VSCode PORTS 面板**只转发静态服务端口 8081**；信令在 8443，**无需也不能**作为 localhost 页面来源转发（§2.1.1 的两个 localhost 口径）。
3. 真机 App 建房，记下 6 位 roomId。
4. Chrome 打开 http://localhost:8081（secure context 来源），填入房号 Join。
5. 观察：面板出现 peerJoined → offer → answer → ice* → connected；stats 出现 `video/VP9` 与双向字节增长。
6. 复现失败路径：强制 relay（F-6）、模拟掉线（F-2）、对端离开（F-4）。
7. 导出 JSON/CSV 留证。

### A-6 门禁回归（两条既有门禁，必须）

```bash
bash scripts/doc-verify.sh; echo "doc-verify EXIT=$?"
bash scripts/i18n-audit.sh; echo "i18n-audit EXIT=$?"
```

期望：两条均 `EXIT=0`；`doc-verify.sh` 的 checks/warnings 计数与 §0.2 基线对比必须**不劣化**（新增 `web/**` 与脚本不得改变 CMD 分类或路径分类）。证据形态：改前/改后原始输出 + 退出码。

### A-7 越界检查（必须）

```bash
git status --porcelain
git diff --name-only
```

期望：只含白名单路径（§9 H-2）；`app/**`、`signaling/**`、`doc/design/**`、`reports/54`–`reports/69` 零改动（`reports/70` 除外，属本轮新增）。

### A-8 本文件自身子集门禁（T1 的验收）

```bash
bash scripts/doc-verify.sh --only reports/70-browser-call-demo-requirements.md; echo "EXIT=$?"
```

期望：`EXIT=0`（无 failure；NOTE/WARN 允许，但不得出现 `missing repo path` 等 P1 失败）。

---

## 9. 硬约束与交付物白名单

* **H-1 禁改**：`app/**`、`signaling/**`、`doc/design/**`（含 `zh-CN/**`、`_generated/**`、`SPEC.md`、`docs` 符号链接）、`scripts/doc-verify.sh`、`scripts/i18n-audit.sh`、已交付 `reports/54`–`reports/69`。
* **H-2 白名单（唯一允许的新增/修改）**：**web/**（全部新增文件）、**scripts/serve-web-demo.sh**、**scripts/web-demo-verify.sh**、根 `README.md` 与 `README.en.md` 的双语小节、**reports/70**、**reports/71**、**reports/72**、**reports/73**。越界即 blocker finding。
* **H-3 纯静态**：无构建步骤、无 npm 依赖、无 bundler；浏览器直接以 ES Module 加载；node 仅用于测试（`node:test`/自写断言均可）。
* **H-4 观测面**：只用浏览器控制台 + 页面面板；不新增 App/服务端日志通道，不要求改服务端以增加日志。
* **H-5 文档治理**：不新增/删除 `doc/design/zh-CN/**` 页面；不动既有切换行与生成表；根 README 双语小节遵守 `doc/design/SPEC.md` §7.6 的切换行与生成表约定。
* **H-6 输出卫生**：临时文件只落 HOST `/tmp`（如 **HOST: /tmp/web-demo-verify.log**），不得在仓库内制造未登记的工作树文件（`git status --porcelain` 必须能对上 H-2）。

---

## 10. 与既有门禁的兼容要求（G-1 … G-4）

* **G-1 不扰动 CMD 分类**：`scripts/doc-verify.sh` 会 live 扫描 `scripts/*.sh` 与 `deploy/*` 生成命令清单并按 §7.3 分类。新增脚本必须只用**已在仓库其它脚本中出现**的命令/flag，或把新 flag 的说明写进 `doc/design/_generated/host-commands.md`——但该文件在禁改清单内，故新增脚本**不得**引入需要登记的新 flag；否则会被判 `is host/workspace-only and is cited without in-repository evidence`。T3 必须给出新增脚本前后 checks/warnings 的原始对比。
* **G-2 不扰动路径分类**：新增文档中对 HOST 绝对路径必须标 HOST: 或写进围栏；`tmp/` 前缀路径属 P2，会被核对到工作区根——报告与 README 里不得出现不存在的 `tmp/...` 引用。
* **G-3 双语门禁**：根 `README.md`/`README.en.md` 的切换行（V14）与 doc/design 配对清单不得被触碰；新增小节不得写入「待翻译（planned）」残留。
* **G-4 门禁强度不削弱**：新增脚本或文档不得修改门禁脚本、不得绕过、不得让任何既有 FAIL 变成「不检查」（默认模式行为逐字节不变）。

引用（G-1 的机制来源）：`scripts/doc-verify.sh:456`、`scripts/doc-verify.sh:858`

---

## 11. Open items（本文件不闭合，交由实现/验证阶段登记）

1. **浏览器建房模式的 VP9 约束**：Chrome 为 offerer 时若不限制编解码，可能协商出 VP8/H264；M-5 要求用编解码偏好限制在 VP9。若实测发现 App 侧对浏览器 offer 的 answer 会剔除 VP9，必须在 **reports/72** 如实登记并按 M-5 判定。
2. **mDNS 候选**：Chrome 对 host 候选默认做 mDNS 混淆（`.local`），真机 App 可能无法解析。判定路径应以 srflx/relay 候选对为准；若因此出现「仅 host 候选、无可用候选对」，按 §8 A-3/失败矩阵登记为已知限制，不得伪装为成功。
3. **A-3 宿主机环境**：容器内无 python3/Chrome（§0.2 实测）；宿主机的 node/Chrome 安装与版本、下载来源、体积必须在 T3/T5 的 output 中逐字披露。
4. **5349/TLS**：coturn 配置声明了证书，但防火墙只放行 3478；`turns:` 路径不在本轮范围（I-6）。若用户要求 TLS 中继，另开一轮。
5. **重连预算 63 s vs 宽限期 90 s**：二者关系已冻结（63 s < 90 s），但「重连失败后是否自动重新 join」在本轮只要求**保留意图 + 有界重试**，不做无限重试。
6. **信号量证据口径**：`ping`/`pong` 的 timestamp 仅用于时间线展示，不作为时钟同步依据；RTT 只以 `candidate-pair` 的 `currentRoundTripTime` 为准。
