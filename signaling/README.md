# signaling — Go WebSocket 信令服务

1:1 WebRTC Demo 的信令后端：按 roomId 中继 SDP/ICE/NAT 类型，管理房间，下发 coturn 的 STUN/TURN 配置。

- 协议权威规格：`doc/09-signaling-protocol-spec.md`（已冻结）
- 接口契约（信令/日志部分对本服务有约束）：`doc/14-interface-contract.md` §8、§9
- 实现指南：`doc/12-backend-implementation.md`
- 自测报告与文档偏差记录：`../reports/09-go-signaling.md`

## 1. 构建与运行

```sh
# 每个新 shell 必须先加载 Go 工具链环境（工具链装在 <工作区根>/go）
. /data/dsh/home/workspace/env-go.sh

cd code/webrtc-demo/signaling
go build -trimpath -ldflags "-s -w" -o signaling .     # 也可 GOOS=linux GOARCH=amd64 交叉编译

# 本地运行（示例）
./signaling -addr :8443 \
    -stun stun:<公网IP>:3478 \
    -turn 'turn:<公网IP>:3478?transport=udp' \
    -user demo:demopass \
    -log logs/signaling.log -log-level debug
```

## 2. 命令行参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `-addr` | `:8443` | 监听地址（Caddy 反代 443 → 8443） |
| `-stun` | `stun:1.2.3.4:3478` | 下发客户端的 STUN URL |
| `-turn` | `turn:1.2.3.4:3478?transport=udp` | 下发客户端的 TURN URL |
| `-user` | `demo:demopass` | TURN 凭据（`用户名:密码`） |
| `-log` | `logs/signaling.log` | 日志文件路径；目录自动创建（2 MiB×3 滚动）；**置空则仅打终端** |
| `-log-level` | `info` | `trace`(VERBOSE)/`debug`/`info`/`warn`/`error` |
| `-room-expiry` | `1800` | 房间过期秒数（30 分钟无人加入即销毁） |
| `-max-message-bytes` | `65536` | 单条消息上限（doc/09 §1） |
| `-write-timeout` | `10s` | 单次写超时 |
| `-pong-wait` | `45s` | 读超时（ping/pong 超时判定，容忍客户端 15s 心跳） |
| `-send-timeout` | `5s` | 对端发送队列满时的投递等待上限 |
| `-version` | — | 打印版本号后退出 |

`-addr/-stun/-turn/-user` 与 `doc/12 §12.2` 的 systemd `ExecStart` 完全兼容；`-log` 为用户新增要求。

## 3. HTTP 端点

| 路径 | 说明 |
|---|---|
| `/ws` | **唯一信令端点**（doc/09 §1；`doc/14` §8.1 冻结，C11 裁定 `/signal` 作废） |
| `/healthz` | JSON 健康检查：房间数、连接数、uptime、STUN/TURN 配置 |
| `/` | 纯文本端点说明；其它路径 → 404 |

客户端连接地址（契约 §8.1 冻结）：`ws://47.238.144.66:8443/ws`（无域名/证书，不用 wss；TLS 为可选增强 U3）。

### 3.1 重连后「谁发 offer」（doc/09 §3.3 + §6 的组合推论）

异常断线**不会销毁房间**（`Manager.Detach` 只在房间空了才删），因此可用原 `roomId` 重连：

1. 重连方发送 `join(原 roomId)` → 服务端回 `joined`（含 `peerId`，**按当前空槽位分配**，不保证与断线前相同）；
2. 同时服务端给房间内仍在线的对端发 `peerJoined`（带重连方的新 `peerId`）；
3. **按 doc/09 §3.3，收到 `peerJoined` 的一方是 offerer，应重新发起 offer**（房间恒 2 人）。
4. 服务端**不参与媒体协商**：重连后若媒体不通，需要 offerer 侧重新协商（必要时 `iceRestart`）。

> 实现提示：只要保证**同一时刻只有一方发 offer** 即可（例如客户端固定用「创建者角色」发 offer），但**不要**用「host 恒为 `peer-001`」这类槽位推断——重连时 peerId 按空槽位重新分配。

**两个已验证的边界（有对应端到端用例，服务端行为确定）**：

| 边界 | 服务端行为 | 客户端应对 |
|---|---|---|
| 旧会话**静默掉线**（TCP 未关、只是不再收发）→ 设备侧立即重连 | 旧会话要等读超时（`-pong-wait`，默认 45s）才被回收；期间新连接 `join` 得 **`ROOM_FULL`**（旧槽位仍被占用）。回收后对端会先收到 `peerLeft`，此后**同一条新连接重试 `join` 即可成功** | 把重连时的 `ROOM_FULL` 当作**可重试**（有界退避）而非终态；或提示用户重试 |
| **双方都断开**（房间变空） | 房间**立即销毁**（doc/09 §7）→ 用原 `roomId` 重连得 **`ROOM_NOT_FOUND`** | 回到首页重新 `create`/`join`；**不要**无限重试旧 roomId |

> 因此「双方同时重连、两人都只收到 `joined`、无人收到 `peerJoined`」这种会让 §3.3 字面规则互等的场景**在本实现中不可达**：要让两个新连接都被接纳，房间必须已空，而房间一空就被销毁（上表第 2 行）。用例：`server/e2e_test.go` 的 `TestE2E_ReconnectStaleSessionCausesRoomFull` 与 `TestE2E_ReconnectAfterBothDisconnectedIsRoomNotFound`。

**实测时序（`TestE2E_ReconnectTimingAfterStaleReap`，把 `-pong-wait` 调成 800ms 以便在测试内观测；3 轮重复稳定）**：

| 量 | 实测（PongWait=800ms） | 生产量级（PongWait=45s） |
|---|---|---|
| 静默掉线 → 服务端回收死会话（对端收到 `peerLeft`） | **801 ms**（≈ PongWait + 1ms 抖动） | ≈ 45 s |
| 房间仍保留（survivor 未挂断）→ 重连方 `join` 成功 | **1 ms**（服务端侧，sub-ms） | ≈ 一次 RTT + 1ms |
| survivor 收到 `peerLeft` 立即 `leave` → 房间销毁 | **0–3 ms**（此后 `join` = `ROOM_NOT_FOUND`） | ≈ 一次 RTT + 3ms |

> **给客户端的关键推论（纯机制说明，不是实施建议）**：房间会不会销毁**完全由"仍在线一方的挂断策略"决定**，服务端不做兜底。
> 若 survivor 一收到 `peerLeft` 就挂断（doc/09 §4 的 `IN_CALL → DISCONNECTED` 直译），窗口只有毫秒级 ⇒ 重连方的秒级重试**必然错过**，最终得 `ROOM_NOT_FOUND`（终态）。
> 反之，survivor 若在 `peerLeft` 后多等约 5 s 再挂断（宽限期），重连方在下一次重试（≤2 s）即可成功恢复——死槽位一旦被回收，服务端侧重入只要 ~1 ms。

**⚠️ 本轮口径（captain 裁定 / 契约 §8.5 + D-7，2026-09-13）：取口径 B（严格字面）——请勿实施口径 A。**
survivor 收到 `peerLeft` 即转 `DISCONNECTED` 并挂断，房间随之销毁，掉线方最终得到 `ROOM_NOT_FOUND`（终态）、双方干脆回首页并明确提示。
口径 A（≥5 s 有界宽限期）**登记为后续增强**，**未经 captain 书面授权不得实施**，且实施时**必须与 ICE restart 一起做**——理由是「宽限期 + 不具备重协商能力」会产出**信令已恢复但媒体是死的僵尸通话**，比干净挂断更糟。
**两种口径下服务端都零改动**（服务端只负责：survivor 未挂断时保留房间、死槽位回收后 ~1 ms 接纳重连方）。
> 口径来源提醒：`reports/02-interface-contract.md` §30 是 architect 的**推荐**（限于报告登记，**已被 captain 裁定覆盖**）；**决策以 `doc/14` §8.5 / D-7 为准 = 口径 B**。go-dev 曾在 captain 定论前把 §30 的 A 推荐转达给 android-dev，导致其按 A 实施后完整回滚（该过程事件已记入 `reports/09-go-signaling.md` §11 的"口径传达更正记录"）；**本文件此处的"请勿实施 A"就是为防止再次发生**。

> **契约 §8.4 已冻结的两条「消息级」义务**（实现者可直接引用；退避时长/次数等**策略**不进契约，登记在 `reports/02-interface-contract.md` §27.4/§30）：
> 1. **`ROOM_FULL` 是暂时性（transient）** → **不应视为终态**；`ROOM_NOT_FOUND` / `ROOM_EXPIRED` 为**终态**（停止重试）。
> 2. **必须按 `type` 分发**每一条收到的消息；**不得**假设「发 `join` 后下一条必是 `joined`」，也不得用"下一条消息"同步状态机（读流里会混入心跳 `pong`）。
>
> 本服务端实现与这两条逐条一致（原样透传 + 服务端只回 `pong`；`ROOM_FULL` 语义见 `room.ErrRoomFull` 与本文 §3.1 上表）。

对 `/ws` 发起非 WebSocket 升级的请求（例如 `curl`）返回 `400` 与明确提示，便于部署探活：

```
$ curl -si http://127.0.0.1:8443/ws | head -5
HTTP/1.1 400 Bad Request
Content-Type: text/plain; charset=utf-8

WebSocket upgrade required (RFC 6455). Connect to ws://127.0.0.1:8443/ws
```

## 4. 日志

契约：`doc/14-interface-contract.md` §9（C24 裁定，三层格式一致）。

- 库：**logrus**（契约 §3.2/C15；用户明确要求），终端与文件双写 `io.MultiWriter(os.Stdout, rollingWriter)`。
- 行格式（§9.1）：`<ts> <LEVEL> <layer> <tag> [<pid>/-] <message>[ key=value ...]`，例如：

```
2026-09-13T07:41:28.812Z INFO    go     room      [25006/-] room_created room=55B5PX peer=peer-001 role=host room_count=1
2026-09-13T07:41:28.816Z INFO    go     signaling [25006/-] offer_forward bytes=76 from=peer-001 room=55B5PX to=peer-002 type=offer
2026-09-13T07:41:51.000Z WARN    go     signaling [25006/-] heartbeat_timeout reason=ping/pong-timeout room=QQ95XW
```

  - `ts` UTC 毫秒（字面 `Z`）；`LEVEL` 定宽 7；`layer` 定宽 6（本服务固定 `go`）；`tag` 为模块名（本服务用 `main`/`signaling`/`room`）；`key=value` 按键字典序、值不含空格。
- 滚动（§9.2）：单文件 **2 MiB**、保留 **3** 个（`signaling.log`、`.1`、`.2`），自实现（无第三方依赖）。
- 降级：目录/文件不可用时只告警并退化为仅终端，**服务照常启动**；`-log` 置空亦为仅终端。
- 关键路径事件名（可 grep）：`server_start`、`listening`、`ws_open`、`ws_close`、`room_created`、`room_joined`、`peer_joined`、`peer_left_sent`、`room_destroyed`、`room_expired`、`join_rejected`、`error_sent`、`offer_forward`/`answer_forward`/`ice_forward`/`nattype_forward`、`heartbeat_ping`、`heartbeat_timeout`、`shutdown_begin`/`shutdown_done`。

## 5. 目录结构

```
signaling/
├── main.go                     # 入口：flag 解析、日志初始化、信号优雅退出
├── go.mod / go.sum             # module webrtcdemo-signaling
├── config/config.go            # 配置 + 默认值 + 校验/告警
├── protocol/message.go         # 全部消息结构（doc/09 §3）
├── protocol/errors.go          # 错误码与 error 消息构造（doc/09 §3.10）
├── protocol/heartbeat.go       # 心跳/重连常量（doc/09 §6）
├── logging/formatter.go        # §9.1 行格式（layer=go/tag/pid/字典序字段）
├── logging/rotating.go         # §9.2 滚动写入（2 MiB×3，自实现）
├── logging/logging.go          # Setup：logrus + MultiWriter + 降级
├── room/peer.go                # Peer：读/写循环、发送队列、超时与关闭
├── room/room.go                # Room：2 槽位、对端查找、过期判定
├── room/manager.go             # 房间表、创建/加入/离开/断线/过期清理
├── server/server.go            # 路由、/healthz、监听与优雅关闭
├── server/ws_handler.go        # 握手、消息路由、转发、错误码
├── util/roomid.go              # 6 位房间号生成/校验/归一化
├── logging/*_test.go           # 日志格式与滚动单测
├── server/e2e_test.go          # 端到端测试（真实 TCP + WebSocket）
├── room/manager_test.go        # 房间/管理器单测
├── util/roomid_test.go         # roomId 单测
├── dist/signaling-linux-amd64  # 交付二进制（linux/amd64，已 strip）
└── logs/                       # 自测证据（日志文件 + 原始输出转录）
```

## 6. 测试

```sh
. /data/dsh/home/workspace/env-go.sh
cd code/webrtc-demo/signaling
go test ./... -count=1            # 25 个用例
go vet ./... && gofmt -l .        # 静态检查

# 打真实监听端口的活体测试（t12 部署后可用）
SIGNALING_WS_URL=ws://<HOST>:8443/ws go test ./server -run TestLive -v -count=1
```

`TestLive_FullCallFlow` 在未设置 `SIGNALING_WS_URL` 时自动跳过；活体模式会逐条打印收发的原始 JSON 报文，可直接贴进验收报告。

## 7. 部署（systemd，doc/12 §12.2 + 契约 §3.2）

```ini
[Unit]
Description=WebRTC Demo Signaling Server
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/signaling
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:<公网IP>:3478 \
    -turn turn:<公网IP>:3478?transport=udp -user demo:demopass \
    -log /var/log/signaling/signaling.log -log-level info
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

> 日志路径：程序默认值是相对路径 `logs/signaling.log`（落在 `WorkingDirectory` 下）；**部署时请显式传绝对路径**。当前冻结值为 **`/var/log/signaling/signaling.log`**（+ `.1`/`.2`，契约 §3.2/§9.2 与 C31 按 t12 部署事实冻结）；目录会自动 `MkdirAll`，2 MiB×3 滚动与终端/journald 双写都与具体路径无关。

## 8. 与文档的偏差（详见 reports/09-go-signaling.md §6）

1. **字段命名以 doc/09 为准**（契约 C12）：`created`/`joined` 使用 `stunUrl/turnUrl/turnUsername/turnCredential`。
2. **日志库用 logrus**（契约 C15 覆盖 `doc/12` §1 的标准库方案）。
3. **端点只有 `/ws`**（契约 C11）：`/signal` 不再注册。
4. `Peer`/`Room` 的字段做了封装（访问器 + 互斥锁），doc/09 §8 的伪代码是导出裸字段。
5. 转发采用「队列满时等待最多 5s」而非「满即丢」：信令丢 ICE candidate 会直接导致建连失败。
6. 部署日志路径为 `/var/log/signaling/signaling.log`（契约 C31，按 t12 部署事实冻结；旧值 `/opt/signaling/logs/…` 作废）。
6. 日志格式器实现 `logrus.Formatter`（而非基于内置 TextFormatter 改参），以精确产出 §9.1 的列式行格式。
