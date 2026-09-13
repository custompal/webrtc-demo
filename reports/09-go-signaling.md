# 09 — Go 信令服务实现与端到端自测（t9）

- 任务：t9（work，依赖 t13）— 实现 Go 信令服务（WebSocket 房间管理与消息转发）并端到端自测
- 执行人：go-dev
- 执行时间：2026-09-13 15:31–15:53（容器本地时间 CST，UTC+8）
- 结论速览：**通过**。`doc/13` 阶段 2 的四条验收标准全部实测通过（`go build` 无错、`wscat` create 返回 `created`、join 返回 `joined`、第二连接 join 后第一连接收到 `peerJoined`）。25 个测试用例（含并发压测、200 条 ICE 突发顺序性、日志格式逐字符校验、滚动写入）连续多轮通过。用户附加快要求（logrus + 终端/文件双写 + 关键路径日志）与 `doc/14` 契约 §8/§9 对本服务的全部约束均已落地（见 §7 自查表）。
- 协议基准：`doc/09-signaling-protocol-spec.md`（权威）；接口契约：`doc/14-interface-contract.md` §8（信令，C11/C12/C16/C17/C20 裁定）、§9（日志，C24 裁定）。与 doc/05 §7、doc/12 §1/§9 的冲突按裁定处理，逐条记录于 §6。

> 路径约定：`<WS>` = `/data/dsh/home/workspace`；宿主机 `/opt/dsh-workspaces` 与之同一份（`reports/01-host-recon.md` §4），二进制可直接在宿主机使用。
> 原始证据（本次运行生成，未做手工修饰）：`signaling/logs/t9-live-evidence.log`、`t9-wscat-evidence.log`、`t9-e2e-transcript.log`、`t9-log-degrade-evidence.log`、`signaling-live.log`、`signaling-wscat.log`。

---

## 1. 交付物

| 文件 | 行数 | 说明 |
|---|---|---|
| `signaling/go.mod` / `go.sum` | 13 / 16 | `module webrtcdemo-signaling`；gorilla/websocket v1.5.1 + logrus v1.9.4（契约 §3.2） |
| `signaling/main.go` | 130 | 入口：flag、日志初始化、信号优雅退出 |
| `signaling/config/config.go` | 134 | 配置、默认值、校验、示例值告警 |
| `signaling/protocol/message.go` | 186 | doc/09 §3 全部消息结构 + 类型判定辅助 |
| `signaling/protocol/errors.go` | 45 | 6 个错误码 + `error` 消息构造 |
| `signaling/protocol/heartbeat.go` | 18 | 心跳/重连常量（doc/09 §6：15s/5s/3s/3 次） |
| `signaling/logging/formatter.go` | 161 | doc/14 §9.1 行格式器（layer/tag/pid/字典序字段） |
| `signaling/logging/rotating.go` | 129 | doc/14 §9.2 滚动写入（2 MiB×3，自实现，无第三方） |
| `signaling/logging/logging.go` | 60 | logrus Setup：MultiWriter(终端+滚动文件)、降级、级别 |
| `signaling/room/peer.go` | 268 | Peer：读/写循环、发送队列、超时、幂等关闭 |
| `signaling/room/room.go` | 161 | Room：2 槽位、对端查找、过期判定 |
| `signaling/room/manager.go` | 317 | 房间表：创建/加入/离开/断线/过期清理 |
| `signaling/server/server.go` | 174 | 路由（仅 /ws、/healthz）、监听、优雅关闭 |
| `signaling/server/ws_handler.go` | 519 | 握手、消息路由、原样转发、错误码 |
| `signaling/util/roomid.go` | 58 | roomId 生成/校验/归一化 |
| 测试 5 个文件 | 127+70+250+750+86 | 27 个用例（logging 6 / room 7 / server 11 / util 3） |
| `signaling/README.md` | — | 构建/运行/参数/端点/日志契约/测试/部署速查 |
| `signaling/signaling` | 5496984 B | 交付二进制（linux/amd64，`-trimpath -ldflags "-s -w"`） |
| `signaling/dist/signaling-linux-amd64` | 5496984 B | 同上（供 t10/t12 打包部署） |

二进制 sha256（两者一致）：
```
c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068  signaling
c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068  dist/signaling-linux-amd64
```

`doc/13` 阶段 2 要求的文件清单全部具备；额外新增 `logging/`、`protocol/heartbeat.go` 与 4 个测试文件（契约 §9 与本任务验收要求）。

---

## 2. 验收标准与结果

| 验收项 | 结果 | 证据 |
|---|---|---|
| `go build` 无错误 | ✅ | §4.1（build/vet/gofmt 全干净） |
| `wscat` 测试 create 返回 `created` | ✅ | §5.3 `[PASS] create 返回 created` |
| `wscat` 测试 join 返回 `joined` | ✅ | §5.3 `[PASS] join 返回 joined` |
| 第二个连接 join 后第一个连接收到 `peerJoined` | ✅ | §5.3 `[PASS] 第二个连接 join 后第一个连接收到 peerJoined` |
| （用户附加）logrus 且终端+日志文件双写 | ✅ | §5.2：`stdout-live.log` 与 `signaling-live.log` 均 27 行同内容 |
| （用户附加）关键路径日志 | ✅ | §5.4 逐类对照表 |
| （用户附加）日志打不开时降级不崩 | ✅ | §5.5 三场景实测 |
| （契约 §8.3）按 doc/09 而非 doc/12 实现 | ✅ | §7 自查表 V1–V7 |
| （契约 §9）日志格式/滚动/级别 | ✅ | §5.2、§5.6、§7 自查表 L1–L6 |
| （契约 C30/§11.4 D-2，low）日志键 snake_case 且协议 JSON 保持 camelCase | ✅ | §5.7（并排原始输出 + 脚本断言 + 两道回归守卫） |
| 数据竞争检测（`go test -race`，宿主机） | ✅ | §4.3：全量用例 race 通过 + race 版真实服务进程 5 轮真实 TCP 流程 0 竞争报告 |

---

## 3. 实现要点

### 3.1 并发模型（对 doc/09 §8、doc/12 §6 伪代码的加固）

- **每连接一个读协程 + 一个写协程**：gorilla/websocket 禁止对同一连接并发写，所有写出（应答 + 转发）汇入唯一写协程；其它协程只往 `chan []byte` 投递。
- **Peer/Room 字段私有 + 互斥锁**：伪代码里 `Peer.ID/Room` 是导出裸字段，但读循环、写循环、清理协程会并发访问，裸字段无法加锁。对外协议不变。
- **锁顺序单向**：`Manager.mu → Room.mu`；`Room` 持锁期间**从不**回调 `Manager`，房间变空/过期由 server 在锁外处理，避免死锁与锁重入。
- **状态变化与网络发送分离**：`Manager` 只改状态并返回 `(room, other, destroyed)`，通知由 server 层发送。

### 3.2 转发可靠性

doc/12 §9 的 `forwardToPeer` 在队列满时直接丢弃；信令丢一个 ICE candidate 就可能让通话建不起来。实现改为：

1. 队列有空位 → 立即入队；
2. 队列满 → **最多等待 `-send-timeout`（默认 5s）**让写协程腾出空间（对发送方形成背压）；
3. 超时仍无空间 → 丢弃并记 `forward_dropped reason=send_timeout_or_closed`。

队列容量 256。实测：单房间突发 200 条 ICE candidate **全部到达且顺序保持**（`TestE2E_BurstForwardOrdering`）。

### 3.3 房间生命周期（doc/09 §7、§3.8）

| 事件 | 行为 |
|---|---|
| `create` | 6 位房间号（crypto/rand，字符集 `ABCDEFGHJKMNPQRSTUVWXYZ23456789`），创建者 = `peer-001`，日志 `room_created role=host` |
| `join` | 校验存在/未过期/未满 → 分配 `peer-002` → `joined_sent` + 给对端 `peer_joined` |
| `leave` | 三步：给对端 `peer_left_sent` → 关闭该连接 → 立即销毁房间（`room_destroyed reason=peer-leave`） |
| 异常断线 | 移出 peer；房间空了才销毁（保留房间，支持 doc/09 §6 重连） |
| 过期 | 30 分钟无人加入（且未满员）→ 清理协程销毁 → 给残留 peer 下发 `ROOM_EXPIRED` 并断开 |

过期判定 = 房间未满员 **且** 距最近一次活动超过 `-room-expiry`（通话中超过 30 分钟不会被误杀）。清理周期默认 60s，`-room-expiry` 很短时自动缩短（实测 1s 过期 → 250ms 周期）。

### 3.4 心跳

- 应用层 `ping` → 服务端回 `pong`（`timestamp` = **服务端当前毫秒**，契约 §8.1 明确）。
- 服务端读超时 `-pong-wait` 默认 **45s = 3 × PingInterval（15s）**（`config.DefaultPongWait = 3 * protocol.PingInterval`），每条消息与控制帧 pong 都刷新；超时记 `heartbeat_timeout reason=ping/pong-timeout` 并断开。
- `heartbeat_ping` 在 `debug` 级别逐条记录，超时在 `warn`。

### 3.5 其它

- 端点只有 **`/ws`**（契约 C11：`/signal` 彻底移除，实测返回 404）与 `/healthz`；非升级请求返回 400 + 提示。
- 单条消息上限 64KB（`SetReadLimit`），超限以 1009 关闭并记 `reason=message-too-large`。
- `SIGINT/SIGTERM` 优雅关闭，退出码 0；`-addr/-stun/-turn/-user` 与 doc/12 §12.2 systemd 兼容。

---

## 4. 编译与静态检查（原始输出）

### 4.1 构建

```sh
$ . /data/dsh/home/workspace/env-go.sh
$ go version
go version go1.22.12 linux/amd64
$ go env GOPROXY
https://goproxy.cn,direct                     # doc/14 §3.2 冻结值

$ cd code/webrtc-demo/signaling
$ go build -trimpath -ldflags "-s -w" -o signaling .
$ go build -trimpath -ldflags "-s -w" -o dist/signaling-linux-amd64 .
$ sha256sum signaling dist/signaling-linux-amd64
c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068  signaling
c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068  dist/signaling-linux-amd64

$ go vet ./...        # 无输出
$ gofmt -l .          # 无输出
```

### 4.2 测试清单（`go test ./... -count=1 -v`，27 例）

```
--- PASS: TestFormatterContractLayout   # 逐字符校验 §9.1 行格式
--- PASS: TestFormatterRules            # 字段字典序/空格转下划线/tag 截断/级别名
--- PASS: TestSetupFallback             # 日志不可用时降级为仅终端
--- PASS: TestSourceLogKeysAreSnakeCase # C30：扫描本模块 154 个日志字段键，全部 snake_case
--- PASS: TestRotatingWriter            # 2 MiB×3 滚动与保留策略
--- PASS: TestRotatingWriterCreatesDir  # 多级目录自动创建
ok  	webrtcdemo-signaling/logging	0.013s
--- PASS: TestRoomAddAndRemove / TestRoomExpiry / TestManagerCreateJoinFull
--- PASS: TestManagerLeaveDestroysRoom / TestManagerDetachKeepsRoom
--- PASS: TestManagerSweepExpired / TestManagerJoinExpiredRoom
ok  	webrtcdemo-signaling/room	1.012s
--- PASS: TestE2E_FullCallFlow / TestE2E_ErrorCases
--- PASS: TestE2E_DisconnectNotifiesPeerAndAllowsReconnect / TestE2E_MessageTooLarge
--- PASS: TestE2E_RoomExpiry / TestHTTP_HealthzAndNonUpgrade
--- SKIP: TestLive_FullCallFlow         # 需 SIGNALING_WS_URL，见 §5.2
--- PASS: TestE2E_ConcurrentRooms / TestE2E_BurstForwardOrdering / TestE2E_PingPongTimeout
--- PASS: TestLogKeysSnakeCaseProtocolJSONCamelCase  # C30：日志键 snake_case + 协议 JSON camelCase
ok  	webrtcdemo-signaling/server	2.274s
--- PASS: TestGenerateRoomID / TestIsValidRoomID / TestNormalizeRoomID
ok  	webrtcdemo-signaling/util	0.034s
```

稳定性：`-count=3` / `-count=5` 多轮全通过；期间修掉 3 处用例自身问题（§6 修正 ⑥）。

> 容器内**无 gcc**，`go test -race` 不可用（race detector 需 cgo）。已改在**宿主机**（有 gcc 13.3.0）完成，见 §4.3；容器侧的替代压力证据为 `TestE2E_ConcurrentRooms`（16 房间并行）+ `TestE2E_BurstForwardOrdering`（200 条突发）。

### 4.3 数据竞争检测（宿主机 `go test -race`，2026-09-13 15:57–15:58）

容器无 gcc → 通过 SSH 在宿主机（`root@172.21.0.219:5766`，Ubuntu 24.04 / gcc 13.3.0 / go1.22.12）执行。原始输出：`signaling/logs/t9-race-evidence.log`。

**(1) 全量用例 race 模式**（`-p 1` 以照顾宿主机 7.1 GiB 无 swap + t5 并行编译）：

```
$ . /opt/dsh-workspaces/env.sh && cd /opt/dsh-workspaces/code/webrtc-demo/signaling
$ export CGO_ENABLED=1 && nice -n 10 go test -race -p 1 ./... -count=1
ok  	webrtcdemo-signaling/logging	1.048s
ok  	webrtcdemo-signaling/room	2.026s
ok  	webrtcdemo-signaling/server	3.389s
ok  	webrtcdemo-signaling/util	1.049s
--- race 测试退出码: 0 ---
```

**(2) 更强的一层：race 编译的【真实服务进程】+ race 编译的客户端，走真实 TCP**（进程内测试无法覆盖真实网络路径上的并发）：

```
$ go build -race -o <WS>/go/tmp/signaling-race-verify .        # 10,520,632 B
$ GORACE="halt_on_error=0 log_path=<...>.race" ./signaling-race-verify -addr 127.0.0.1:18449 ... -log-level debug &
healthz: 200  pid=196228
$ SIGNALING_WS_URL=ws://127.0.0.1:18449/ws go test -race ./server -run TestLive_FullCallFlow -count=5
ok  	webrtcdemo-signaling/server	1.069s
$ kill -TERM <pid>
race 报告文件：（无 .race 报告文件 = 未检测到竞争）
stderr/stdout 中的 DATA RACE：0
服务端日志尾部：conn=9/conn=10 两连接 ws_close（leave + 对端 EOF）→ signal_received/shutdown_begin/shutdown_done
```

5 轮（10 条连接）完整流程（create → created → join → joined → peerJoined → offer/answer/ice/natType → ping/pong → leave → peerLeft）在 race 运行时**零竞争报告**，服务端优雅退出；race 运行不改变源码与交付二进制（复检 `sha256 c298235a…c068` 未变）。临时二进制与 race 日志已删除。

结论：**–race 项由「未执行」闭合为「已执行且通过」**（§8 原第 1 条已关闭）。

---

## 5. 端到端验证（原始输出）

### 5.1 进程内端到端（httptest + 真实 TCP，完整建连时序）

`signaling/logs/t9-e2e-transcript.log`：

```
[HOST] => {"type":"create"}
[HOST] <= {"type":"created","roomId":"MQT4BE","stunUrl":"stun:127.0.0.1:3478","turnUrl":"turn:127.0.0.1:3478?transport=udp","turnUsername":"demo","turnCredential":"demopass"}
[JOINER] => {"type":"join","roomId":"MQT4BE"}
[JOINER] <= {"type":"joined","roomId":"MQT4BE",...,"peerId":"peer-002"}
[HOST] <= {"type":"peerJoined","peerId":"peer-002"}
[HOST] => {"type":"offer","sdp":"v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"}
[JOINER] <= 同上（逐字节一致）                    # 原样转发
[JOINER] => {"type":"answer",...}  [HOST] <= 同上（逐字节一致）
[HOST] => {"type":"ice","candidate":"candidate:842163049 1 udp ... typ srflx ...","sdpMid":"0","sdpMLineIndex":0}
[JOINER] <= 同上（逐字节一致）
[HOST] => {"type":"ping","timestamp":1715432100000}
[HOST] <= {"type":"pong","timestamp":1789284943529}      # 服务端当前毫秒
[HOST] => {"type":"leave"}
[JOINER] <= {"type":"peerLeft","peerId":"peer-001"}
[HOST] == 连接已关闭: websocket: close 1000 (normal)
[LATE] => {"type":"join","roomId":"MQT4BE"}
[LATE] <= {"type":"error","code":"ROOM_NOT_FOUND","message":"Room MQT4BE does not exist"}   # 房间确已销毁
```

错误码全覆盖（`TestE2E_ErrorCases`，服务端侧事件名 `join_rejected` / `error_sent`）：

```
[BAD] => {"type":                [BAD] <= INVALID_MESSAGE "invalid JSON: unexpected end of JSON input"
[BAD] => {}                      [BAD] <= INVALID_MESSAGE "missing required field: type"
[BAD] => {"type":"nope"}         [BAD] <= INVALID_MESSAGE "unknown type \"nope\""
[BAD] => {"type":"pong",...}     [BAD] <= INVALID_MESSAGE "type \"pong\" is server-to-client only"
[NOT_IN_ROOM] => offer/answer/ice/natType → NOT_IN_ROOM `"cannot send <type> before create/join"`；leave → NOT_IN_ROOM `"leave received before create/join"`（服务端原文字面量，见 `server/ws_handler.go`）
[HOST] => {"type":"offer"}       [HOST] <= INVALID_MESSAGE "offer requires non-empty field: sdp"
[HOST] => {"type":"ice","sdpMid":"0"}  [HOST] <= INVALID_MESSAGE "ice requires non-empty field: candidate"
[PROBE] => {"type":"join"}       [PROBE] <= INVALID_MESSAGE "missing required field: roomId"
[PROBE] => {"type":"join","roomId":"ABC"}   [PROBE] <= INVALID_MESSAGE "invalid roomId \"ABC\" (expect 6 chars of [A-Z2-9])"
[PROBE] => {"type":"join","roomId":"ZZZZZZ"} [PROBE] <= ROOM_NOT_FOUND "Room ZZZZZZ does not exist"
[J2] => {"type":"join","roomId":"E83FTK"}    [J2] <= ROOM_FULL "Room E83FTK is full (max 2 peers)"
[HOST] => 重复 create/join                    [HOST] <= INVALID_MESSAGE "already in room E83FTK"
```

过期与心跳超时：
```
[HOST] <= {"type":"error","code":"ROOM_EXPIRED","message":"Room QQ95XW has expired"}   # 1s 过期，250ms 清理周期
[SILENT] 静默 0.7s 后服务端按 PongWait 断开连接（事件 heartbeat_timeout）
```

### 5.2 活体二进制验证（真实端口 + 真实日志文件）

`signaling/logs/t9-live-evidence.log`（完整 102 行），关键片段：

```sh
$ ./signaling -addr 127.0.0.1:18443 -stun stun:127.0.0.1:3478 \
      -turn 'turn:127.0.0.1:3478?transport=udp' -user demo:demopass \
      -log logs/signaling-live.log -log-level debug

### 1) curl /healthz → {"status":"ok","version":"0.1.0","rooms":0,"roomExpirySec":1800,"maxMessageBytes":65536,...}
### 2) curl -si /ws → HTTP/1.1 400 + "WebSocket upgrade required (RFC 6455). Connect to ws://127.0.0.1:18443/ws"
### 2b) curl -si /signal → HTTP 404（C11 作废端点已移除）

### 3) SIGNALING_WS_URL=ws://127.0.0.1:18443/ws go test ./server -run TestLive_FullCallFlow -v -count=1
[LIVE-HOST] <= {"type":"created","roomId":"UP292S",...,"turnCredential":"demopass"}
[LIVE-JOINER] <= {"type":"joined",...,"peerId":"peer-002"}
[LIVE-HOST] <= {"type":"peerJoined","peerId":"peer-002"}
... offer/answer/natType/ice 逐字节原样转发 ...
[LIVE-JOINER] <= {"type":"pong","timestamp":1789285834987}
[LIVE-HOST] => {"type":"leave"}   [LIVE-JOINER] <= {"type":"peerLeft","peerId":"peer-001"}
--- PASS: TestLive_FullCallFlow (0.02s)

### 4) SIGTERM → server exit=0
### 6) 双写校验：stdout-live.log 27 行 / signaling-live.log 27 行（完全一致）
```

服务端日志文件全文（`logs/signaling-live.log`，doc/14 §9.1 行格式）：

```
2026-09-13T07:51:27.263Z INFO    go     main      [34310/-] log_file_open log_file=logs/signaling-live.log max_bytes=2097152 max_files=3
2026-09-13T07:51:27.263Z INFO    go     main      [34310/-] server_start listen_addr=127.0.0.1:18443 ... pong_wait_ms=45000 room_expiry_s=1800 version=0.1.0
2026-09-13T07:51:27.263Z WARN    go     main      [34310/-] config_warning warning=TURN_凭据仍是文档示例值_demo/demopass，...
2026-09-13T07:51:27.264Z INFO    go     room      [34310/-] room_cleanup_start interval_ms=60000
2026-09-13T07:51:27.265Z INFO    go     main      [34310/-] listening actual_addr=127.0.0.1:18443 addr=127.0.0.1:18443 health_path=/healthz ws_path=/ws
2026-09-13T07:51:27.361Z WARN    go     signaling [34310/-] ws_upgrade_rejected http_status=400 path=/ws remote=127.0.0.1:36316 upgrade=- user_agent=curl/7.88.1
2026-09-13T07:51:27.387Z WARN    go     signaling [34310/-] http_unknown_path path=/signal remote=127.0.0.1:36318
2026-09-13T07:51:29.374Z INFO    go     signaling [34310/-] ws_open active_conns=1 conn=1 origin=- remote=127.0.0.1:36330 url=/ws user_agent=Go-http-client/1.1
2026-09-13T07:51:29.376Z INFO    go     room      [34310/-] room_created peer=peer-001 remote=127.0.0.1:36330 role=host room=8Y3ZN8 room_count=1
2026-09-13T07:51:29.377Z INFO    go     room      [34310/-] created_sent peer=peer-001 peers=1 ... room=8Y3ZN8 stun_url=... turn_url=...
2026-09-13T07:51:29.378Z INFO    go     room      [34310/-] room_joined peer=peer-002 peers=2 remote=127.0.0.1:36340 room=8Y3ZN8
2026-09-13T07:51:29.378Z INFO    go     room      [34310/-] joined_sent peer=peer-002 peers=2 ... room=8Y3ZN8
2026-09-13T07:51:29.378Z INFO    go     room      [34310/-] peer_joined new_peer=peer-002 peer=peer-001 peers=2 room=8Y3ZN8
2026-09-13T07:51:29.379Z INFO    go     signaling [34310/-] offer_forward bytes=76 from=peer-001 room=8Y3ZN8 to=peer-002 type=offer
2026-09-13T07:51:29.380Z INFO    go     signaling [34310/-] answer_forward bytes=77 from=peer-002 room=8Y3ZN8 to=peer-001 type=answer
2026-09-13T07:51:29.381Z INFO    go     signaling [34310/-] nattype_forward bytes=39 from=peer-001 room=8Y3ZN8 to=peer-002 type=natType
2026-09-13T07:51:29.383Z DEBUG   go     signaling [34310/-] heartbeat_ping client_ts=1715432100000 peer=peer-002 room=8Y3ZN8
2026-09-13T07:51:29.385Z INFO    go     signaling [34310/-] ice_forward bytes=80 from=peer-001 room=8Y3ZN8 to=peer-002 type=ice
2026-09-13T07:51:29.387Z INFO    go     room      [34310/-] room_destroyed age_s=0 reason=peer-leave room=8Y3ZN8 room_count=0
2026-09-13T07:51:29.387Z INFO    go     room      [34310/-] leave_received age_s=0 peer=peer-001 peers_after=1 room=8Y3ZN8 room_destroyed=true
2026-09-13T07:51:29.387Z INFO    go     room      [34310/-] peer_left_sent left_peer=peer-001 peer=peer-002 room=8Y3ZN8
2026-09-13T07:51:29.387Z INFO    go     signaling [34310/-] ws_close active_conns=1 conn=1 duration_ms=13 peer=peer-001 reason=closed-by-server ...
2026-09-13T07:51:29.392Z WARN    go     signaling [34310/-] ws_close active_conns=0 conn=2 duration_ms=17 peer=peer-002 reason=unexpected-close ...
2026-09-13T07:51:29.409Z INFO    go     main      [34310/-] signal_received signal=terminated
2026-09-13T07:51:29.409Z INFO    go     main      [34310/-] shutdown_begin
2026-09-13T07:51:29.409Z INFO    go     main      [34310/-] shutdown_done
```

### 5.3 `doc/13` 验收：wscat（真实第二个客户端实现）

`signaling/logs/t9-wscat-evidence.log`。运行技巧：本容器 npm 全局 prefix 属 root，故把 wscat 6.1.0 装在工作区内（`<WS>/go/npm-tools`，不污染仓库）；wscat 的 readline 遇到 stdin EOF 会立即退出，用命名管道保持 stdin 打开（不影响协议验证）。

```
### 终端 A：wscat -c ws://127.0.0.1:18445/ws -x '{"type":"create"}' -w 10
### 从 A 的 created 响应解析到 roomId = EKH7SD
### 终端 B：wscat -c ws://127.0.0.1:18445/ws -x '{"type":"join","roomId":"EKH7SD"}' -w 4
### A 终端输出:
{"type":"created","roomId":"EKH7SD","stunUrl":"stun:127.0.0.1:3478","turnUrl":"turn:127.0.0.1:3478?transport=udp","turnUsername":"demo","turnCredential":"demopass"}
{"type":"peerJoined","peerId":"peer-002"}
{"type":"peerLeft","peerId":"peer-002"}
### B 终端输出:
{"type":"joined","roomId":"EKH7SD",...,"peerId":"peer-002"}
### 验收判定
  [PASS] create 返回 created
  [PASS] join 返回 joined
  [PASS] 第二个连接 join 后第一个连接收到 peerJoined
### 服务端日志（节选）
2026-09-13T07:51:42.333Z INFO    go     signaling [34629/-] ws_open active_conns=1 conn=1 origin=- remote=127.0.0.1:49778 url=/ws user_agent=-
2026-09-13T07:51:42.344Z INFO    go     room      [34629/-] room_created peer=peer-001 role=host room=EKH7SD room_count=1
2026-09-13T07:51:44.897Z INFO    go     room      [34629/-] room_joined peer=peer-002 peers=2 room=EKH7SD
2026-09-13T07:51:44.899Z INFO    go     room      [34629/-] peer_joined new_peer=peer-002 peer=peer-001 peers=2 room=EKH7SD
2026-09-13T07:51:48.904Z INFO    go     room      [34629/-] peer_left_sent left_peer=peer-002 peer=peer-001 room=EKH7SD
2026-09-13T07:51:52.349Z INFO    go     room      [34629/-] room_destroyed age_s=10 reason=room-empty room=EKH7SD room_count=0
```

### 5.4 关键路径日志覆盖（用户要求逐项对照）

| 要求 | 实测日志（事件名 + 字段） |
|---|---|
| 启动与监听地址 | `server_start listen_addr=127.0.0.1:18443 ... version=0.1.0` + `listening actual_addr=... ws_path=/ws` |
| 房间创建 | `room_created room=8Y3ZN8 peer=peer-001 role=host room_count=1` |
| 房间加入 | `room_joined room=8Y3ZN8 peer=peer-002 peers=2` |
| 房间销毁 | `room_destroyed room=8Y3ZN8 reason=peer-leave age_s=0 room_count=0`（reason 取值：`peer-leave`/`room-empty`/`expired`/`expired-before-join`） |
| peer 连接 | `ws_open conn=1 url=/ws remote=127.0.0.1:36330 origin=- user_agent=... active_conns=1` |
| peer 断开 | `ws_close peer=peer-001 reason=closed-by-server duration_ms=13 room=8Y3ZN8 room_destroyed=true` |
| 消息转发 | `offer_forward type=offer bytes=76 from=peer-001 to=peer-002 room=8Y3ZN8`（另有 `answer_forward`/`ice_forward`/`nattype_forward`，丢弃时 `forward_dropped reason=peer_offline`/`send_timeout_or_closed`） |
| 错误码与异常 | `join_rejected code=ROOM_FULL room=...`、`error_sent code=INVALID_MESSAGE reason=json-parse detail=...`、`room_expired room=... age_s=1 expiry_s=1` |
| ping/pong 超时 | `heartbeat_timeout reason=ping/pong-timeout peer=peer-001 pong_wait_ms=45000`（`heartbeat_ping` 在 debug 级） |

### 5.5 日志降级行为验证（用户要求「打不开也不能让服务起不来」）

`signaling/logs/t9-log-degrade-evidence.log`：

```
### 场景 A：-log /data/dsh/home/nonexistent-dir/signaling.log（目录不可创建）
healthz 状态: 200                                   # 服务照常启动
WARN    go     main      [34834/-] log_file_fallback error=创建日志目录_..._permission_denied log_file=... stdout_only=true
INFO    go     main      [34834/-] server_start ...
INFO    go     main      [34834/-] listening actual_addr=127.0.0.1:18446 ...
server exit=0                                       # SIGTERM 优雅退出

### 场景 B：-log 置空
healthz 状态: 200
WARN    go     main      [34846/-] log_file_disabled stdout_only=true

### 场景 C：-log logs/deep/a/b/signaling.log（多级目录）
healthz 状态: 200
INFO    go     main      [34866/-] log_file_open log_file=logs/deep/a/b/signaling.log max_bytes=2097152 max_files=3
$ ls -l logs/deep/a/b/  →  signaling.log（目录已自动创建，日志正常落盘）
```

### 5.6 日志契约（doc/14 §9）逐项验证

| 契约要求 | 实现与实测 |
|---|---|
| §9.1 行格式 `<ts> <LEVEL> <layer> <tag> [<pid>/<tid>] <message>[ k=v ...]` | `logging/formatter.go`；`TestFormatterContractLayout` 逐字符断言 |
| `ts` UTC 毫秒、字面 `Z` | `entry.Time.UTC().Format("2006-01-02T15:04:05.000Z07:00")` → `2026-09-13T07:51:27.263Z` |
| `LEVEL` 定宽 7、`VERBOSE\|DEBUG\|INFO\|WARN\|ERROR` | `LevelName()`：trace→`VERBOSE`；`TestFormatterRules` 覆盖 5 个级别 |
| `layer` 定宽 6、值为 `go` | 固定 `go`（可经字段覆盖） |
| `tag` ≤24 字符、`logrus.Fields{"tag":...}` | 使用 `main`/`signaling`/`room`；超长截断（测试覆盖） |
| `[<pid>/-]`（Go 允许差异） | `[34310/-]` |
| `key=value` 字典序、值不含空格 | 字段排序 + 空格转 `_`（`detail=Room_MQT4BE_does_not_exist`）；`TestFormatterRules` 断言排序 |
| §9.2 文件 2 MiB×3（`signaling.log`/`.1`/`.2`）、自实现 | `logging/rotating.go`（`DefaultMaxBytes=2*1024*1024`、`DefaultMaxFiles=3`）；`TestRotatingWriter` 断言 |
| §9.2 终端保留（stdout/systemd journal） | `io.MultiWriter(os.Stdout, rotatingWriter)`；实测 stdout 与文件行数一致（27/27） |
| §9.2 每条日志一次 write() | `RotatingWriter.Write` 单次 `file.Write(p)`；`MultiWriter` 对每个目标各一次 |
| §9.3 `-log-level trace\|debug\|info\|warn\|error`（默认 info） | `logging.ParseLevel`（含 `verbose` 兼容）；实测 `-log-level debug` 输出 `DEBUG` 行 |
| §9.3 高频字段 `room=`、`peer=` | 所有 room/peer 相关事件均带 `room=<id>`、`peer=<peerId>` |
| §9.3 不引第三方日志库 | 仅 logrus + 自实现 formatter/writer；`go.mod` 无 lumberjack 等 |

### 5.7 C30 / §11.4 D-2：日志键 snake_case vs 协议 JSON camelCase（captain 追加对齐项）

captain 转达的裁定 **C30 + §11.4 D-2**：§9.1 的 k=v 键名（= logrus `Fields` 的键）必须是 snake_case；但该约束**只**作用于日志键名，**不约束协议 JSON 字段名**（协议必须保持 doc/09 的 `stunUrl`/`turnUrl`/`turnUsername`/`turnCredential`，C12 冻结）。

**结论：该低风险项在 t9 的 §9 日志改造中已天然满足，无需改动协议或业务代码。** 本轮进一步补了两道机械化守卫与一段并排原始证据（改的只有测试文件，**生产二进制与源码字节未变**：`sha256 c298235a…c068`，5496984 B，与前面所有验收记录一致）。

证据文件：`signaling/logs/t9-log-key-evidence.log`（完整原始输出）。

**(1) 并排原始输出（同一次运行：客户端收到的报文 vs 服务端日志）**

```
### 2) 客户端侧：活体端到端，打印实际收到的协议 JSON
[LIVE-HOST] <= {"type":"created","roomId":"EFSBTJ","stunUrl":"stun:127.0.0.1:3478","turnUrl":"turn:127.0.0.1:3478?transport=udp","turnUsername":"demo","turnCredential":"demopass"}
[LIVE-JOINER] <= {"type":"joined","roomId":"EFSBTJ","stunUrl":"stun:127.0.0.1:3478","turnUrl":"turn:127.0.0.1:3478?transport=udp","turnUsername":"demo","turnCredential":"demopass","peerId":"peer-002"}

### 3) 服务端日志侧（生产 §9.1 formatter，snake_case 键）
--- 启动行 ---
2026-09-13T07:55:49.606Z INFO    go     main      [37645/-] server_start listen_addr=127.0.0.1:18446 log_file=logs/signaling-logkey.log log_level=info max_msg_bytes=65536 pid=37645 pong_wait_ms=45000 room_expiry_s=1800 send_timeout_ms=5000 stun_url=stun:127.0.0.1:3478 turn_url=turn:127.0.0.1:3478?transport=udp turn_user=demo version=0.1.0 write_timeout_ms=10000
2026-09-13T07:55:49.607Z INFO    go     main      [37645/-] listening actual_addr=127.0.0.1:18446 addr=127.0.0.1:18446 health_path=/healthz max_msg_bytes=65536 pong_wait_ms=45000 room_expiry_s=1800 version=0.1.0 ws_path=/ws
--- 房间与转发行 ---
2026-09-13T07:55:51.024Z INFO    go     room      [37645/-] room_created peer=peer-001 remote=127.0.0.1:37962 role=host room=EFSBTJ room_count=1
2026-09-13T07:55:51.024Z INFO    go     room      [37645/-] created_sent peer=peer-001 peers=1 remote=127.0.0.1:37962 role=host room=EFSBTJ stun_url=stun:127.0.0.1:3478 turn_url=turn:127.0.0.1:3478?transport=udp user_agent=Go-http-client/1.1
2026-09-13T07:55:51.027Z INFO    go     room      [37645/-] joined_sent peer=peer-002 peers=2 remote=127.0.0.1:37970 room=EFSBTJ user_agent=Go-http-client/1.1
2026-09-13T07:55:51.027Z INFO    go     room      [37645/-] peer_joined new_peer=peer-002 peer=peer-001 peers=2 remote=127.0.0.1:37962 room=EFSBTJ user_agent=Go-http-client/1.1
2026-09-13T07:55:51.029Z INFO    go     signaling [37645/-] offer_forward bytes=76 from=peer-001 peer=peer-001 peers=2 room=EFSBTJ to=peer-002 type=offer user_agent=Go-http-client/1.1
```

**(2) 自动断言结果（同一脚本输出）**

```
协议 JSON 含 camelCase 键 "stunUrl"：2 次（应 >=2）      ✓
协议 JSON 含 camelCase 键 "turnUrl"：2 次（应 >=2）      ✓
协议 JSON 含 snake_case 键 "stun_url"：0 次（应 0）      ✓
服务端日志含 snake_case 键 stun_url=：2 次（应 >=1）      ✓
服务端日志含 camelCase 键 stunUrl=：0 次（应 0）          ✓
服务端日志含 camelCase 键 turnUrl= / logFile= / logLevel= / listenAddr= / turnUser= / roomExpiry= / maxMsgBytes= / pongWait=：0 次（应 0）  ✓
```

**(3) 两道回归守卫（防止以后改回去）**

| 测试 | 内容 |
|---|---|
| `logging.TestSourceLogKeysAreSnakeCase` | 扫描本模块全部非测试源码里的 `logrus.Fields{...}` map 键与 `WithField("...")` 键（**154 个**），任何 camelCase 键即失败（正则 `^[a-z][a-z0-9_]*$`） |
| `server.TestLogKeysSnakeCaseProtocolJSONCamelCase` | 一次真实建连中双向断言：客户端收到的 `created`/`joined` 原始 JSON 必须含 `"stunUrl"`/`"turnUrl"`/`"turnUsername"`/`"turnCredential"`/`"roomId"`/`"peerId"` 且**不得**含 `"stun_url"` 等；服务端日志必须含 `stun_url=` 且**不得**含 `stunUrl=` |

**(4) captain 列的逐一核对（`main.go` 一带的 Fields）**

| 旧（改造前） | 现状 | 位置 |
|---|---|---|
| `stunUrl` | `stun_url` ✅ | `main.go` server_start |
| `turnUrl` | `turn_url` ✅ | `main.go` server_start、`created_sent` |
| `turnUser` | `turn_user` ✅ | `main.go` server_start |
| `logFile` | `log_file` ✅ | `main.go`/`logging.go` |
| `logLevel` | `log_level` ✅ | `main.go` server_start |
| `roomExpiry` | `room_expiry_s` ✅ | `main.go` server_start |
| `maxMsgBytes` | `max_msg_bytes` ✅ | `main.go` server_start |
| `pongWait` | `pong_wait_ms` ✅ | `main.go` server_start |
| `listenAddr` | `listen_addr` ✅ | `main.go` server_start |

> 说明：这三处 camelCase 字符串**保留不动，且属允许范围**（不属于日志键，被 §9.1 排除）：
> ① 协议 JSON 字段：`stunUrl`/`turnUrl`/`turnUsername`/`turnCredential`/`roomId`/`peerId`/`sdpMid`/`sdpMLineIndex`/`natType`；
> ② `/healthz` 的 HTTP 响应体键（`maxMessageBytes`/`roomExpirySec`/`serverTimeMillis`/`activeConns`/`totalConns`/`uptimeSec`/`roomsCreated`/`roomsDestroyed`/`roomIds`）—— 这是我额外加的诊断端点，属 HTTP JSON 而非日志 k=v；
> ③ `/` 端点文案里出现的 `/ws`。若 verifier 按「signaling 目录下不得出现 camelCase」这类过宽 grep 判定，请以 §9.1 的文字范围（**仅日志键名**）为准。

---

## 6. 对文档缺陷/冲突的逐处修正说明

1. **【契约 C12】`created`/`joined` 的 ICE 配置字段名**：doc/05 §7 用 `stun/turn/turnUser/turnPass`，doc/09 §3.1/§3.2 用 `stunUrl/turnUrl/turnUsername/turnCredential`。**按 doc/09 + C12 实现**，且不加字段别名（doc/09 §10 的 schema 是 `additionalProperties:false`）。已通知 android-dev/architect。
2. **【契约 C15 + 用户指令】日志库**：doc/12 §1 写 `log/slog`；裁定用 `github.com/sirupsen/logrus`。版本锁 `v1.9.4`（最新 v1.10.2 要求 Go ≥1.23，与契约 §3.2 的 Go 1.22.x 冲突，见 `reports/13-go-toolchain.md` §4.2）。
3. **【用户新增需求】日志降级**：`MkdirAll` / `OpenFile` 任一步失败只告警并退化为仅终端，服务照常启动；`-log ""` 亦为仅终端。doc/12 未定义，实测见 §5.5。
4. **【契约 C11】端点**：`/ws` 唯一；原先把 doc/03 的 `/signal` 作为兼容别名，**已按 C11 彻底移除**（实测 `/signal` → 404，日志 `http_unknown_path`）。
5. **【doc/12 缺陷】转发队列满时静默丢包**：改为「快路径 + 最多等待 5s 的慢路径」（§3.2），队列 256，实测 200 条突发零丢包且保序。
6. **【doc/12 缺陷】房间状态与连接生命周期**：`handleCreate` 直接写 `room.Peers[0]`（绕过 `AddPeer`、并发不安全）；`handleLeave` 只 `RemovePeer`（不发 `peerLeft`、不关连接、不销毁房间，违反 doc/09 §3.8）；`cleanupLoop` 在 `RLock` 内调 `RemoveRoom`（写锁）。全部重写为 `Room.AddPeer` / `Manager.Leave` / `Manager.Detach` / 锁外回调清理。
7. **【doc/12 缺陷】`errors` 未 import、`-user` 未解析**：`room/room.go` 的伪代码引用未导入的 `protocol`；`main.go` 收 `-user demo:demopass` 却硬编码。实现中 `Config.SetTurnUser` 解析并校验。
8. **【doc/09 §8 伪代码并发问题】**：导出裸字段无法加锁 → 字段私有 + 访问器（§3.1），对外协议不变。
9. **【doc/09 缺口】`created` 无本端 `peerId`**：发起方在协议层不知道自己的 peerId（只有 `joined` 有）。**严格按规格实现**（不加字段），本端 ID 只出现在服务端日志。**architect 已裁定（2026-09-13）：不扩字段**（D-3，low，不得判失败）；本端 ID 改为客户端可确定性推导（joiner 用 `joined.peerId`；host 收到 `peerJoined.peerId = X` 后取反），并禁止「host 恒为 peer-001」的槽位推断（契约 §8.2）。Go 侧无需改动 —— 服务端本来就是按槽位分配 `peer-001`/`peer-002`，从不做身份假设。已通知 android-dev/architect。
10. **【doc/09 歧义，契约已裁定】`pong.timestamp`**：doc/14 §8.1 冻结为「**服务端**当前 Unix 毫秒」，与实现一致（原先列为未决项，现已闭合）。
11. **【doc/09 §3.6 校验力度】**：`ice` 的 `sdpMid`/`sdpMLineIndex` 至少一个有效；实现为 `candidate` 缺失 → `INVALID_MESSAGE`，两个 media 标识都缺 → 记 `ice_missing_media_id forward=true` 后仍转发（避免因客户端序列化差异中断建连）。
12. **【契约 §8.3-4 / U7】未定义状态**：未入房却 `create`、重复 `join` → 回 `INVALID_MESSAGE`（`message=already in room <id>`）并记 `Warn`。已按 U7 在此登记。
13. **【doc/09 §7 vs §3.8 张力】**：§7 说「2 人全离开后销毁」，§3.8 说 leave 即销毁。实现取 §3.8：显式 `leave` 立即销毁房间并清空对端 `Room` 引用；**异常断线**才保留房间（支持 §6 重连）。
14. **【doc 未定义】过期房间的残留 peer**：实现主动下发 `ROOM_EXPIRED` 再断开（比静默超时更可诊断），日志 `room_expired_notify`。
15. **【契约 §9 示例的一处不一致 → 已由 architect 确认为契约笔误】**：§9.1 示例行 `... ERROR   go      room      [2301/-] ...` 中 `go` 后为 6 个空格，与同节文字规则「layer 左对齐定宽 6 + 分隔空格」矛盾（`kotlin/native/webrtc` 三行均符合规则）。实现按**文字规则**（`LEVEL` 7、`layer` 6、`tag` 9，各后接 1 空格）。**architect 已用脚本量宽确认是示例笔误并修正**（新增「列宽与笔误修正（2026-09-13）」注记 + 补全 `tag` 定宽 9 的文字规则，写明 verifier 按文字规则判定、不得按示例字面量判失败）→ **我的常量无需改动，现状即正确口径**。
16. **【环境事实】**：容器无 gcc → 无法 `-race`；`/tmp` 下产物不可执行 → 二进制与日志一律落在 `<WS>` 内。

---

## 7. `doc/14` 对本服务的约束：逐条自查

### 7.1 §8.3 「Go 侧必须按 doc/09 实现的行为清单」

| # | 要求 | 实现 | 证据 |
|---|---|---|---|
| 1 | `leave` 三步（通知对端 → 关连接 → 销毁房间） | `handleLeave` → `Manager.Leave` → `sendPeerLeft` → `peer.Close()` | §5.2 日志 `peer_left_sent`/`room_destroyed reason=peer-leave`/`leave_received`；`TestManagerLeaveDestroysRoom` |
| 2 | 未 join 就发 offer/answer/ice/natType/leave → `NOT_IN_ROOM` | 统一在 `forwardToPeer`/`handleLeave` 判定 | §5.1 五条 `NOT_IN_ROOM` |
| 3 | 容量 2；过期=30 分钟无人 join；2 人全离开立即销毁；roomId 冲突重试 | `Room.AddPeer`/`IsExpired`/`Manager.Detach`/`CreateRoom` 重试 | `TestE2E_RoomExpiry`、`TestManager*` |
| 4 | 未入房 create / 重复 join → 明确行为并登记 | `INVALID_MESSAGE already in room` | §5.1、§6 修正 12 |
| 5 | `error.message` 以 doc/09 §3.10 为准 | `Room <id> does not exist` 等（即 §3.10 示例风格） | §5.1 |
| 6 | doc/12 已知缺陷全部修复并登记 | 见 §6 修正 5/6/7 | 代码 + 测试 |
| 7 | 日志按 §9（logrus，终端+文件），`-log-level` 控制 | `logging` 包 | §5.6 |

### 7.2 §12.5 验收命令自查（V33–V39，2026-09-13 16:3x 按 **v1.0-o 的现行条文**在最终树上复跑）

> 复跑口径按契约「约定 10」：递归 grep 必须加 `-I`（跳过二进制）并附精确 `--include`。
> **计数会随 `-I`/`--include` 变化**（例如不带 `-I` 时二进制 `signaling/dist/…` 会报 `binary file matches` 而虚增命中），因此下表同时给出**命令**与**实测值**，便于 verifier 逐字复现。
> 说明：契约 v1.0-o 把 V33–V39 的**命令范围收窄/细化**了（如 V34 收窄到 `signaling/protocol/`、V37 不绑定单一文件、V38 用 `--include='*.go'` 避免 README 假失败），下表已按现行条文重跑；V33/V35 含客户端部分（属 `app/`，由 t8 负责）。

| # | 命令（现行条文 · 最终树实测所用） | 期望 | 实测 |
|---|---|---|---|
| V33 | 服务端 `grep -rnI --include='*.go' '"/ws"' signaling`（客户端 `8443/ws` 属 `app/`） | 服务端 ≥1 | **2 处**（`const PathWS = "/ws"` + `mux.HandleFunc("/ws", …)`）✅ |
| V34 | `grep -rnI --include='*.go' -e "turnCredential" -e "stunUrl" signaling/protocol/` / `… -e '"turnUser"' -e '"turnPass"' signaling/protocol/` | ≥4 / 0 命中 | **5 处** / **0 处** ✅ |
| V35 | `grep -rnI --include='*.go' "ABCDEFGHJKMNPQRSTUVWXYZ23456789" signaling`（客户端 .kt 属 `app/`） | Go 侧 ≥1 | **1 处**（`util/roomid.go:18`）✅ |
| V36 | `grep -rnI --include='*.go' -e "ROOM_FULL" -e "ROOM_EXPIRED" -e "NOT_IN_ROOM" -e "INVALID_MESSAGE" signaling` + 人工确认 15 s 心跳常量 | ≥4 + 常量存在 | 错误码 **33 处**；`grep -e ping -e heartbeat` 输出 **23 行**，其中 `protocol/heartbeat.go:11: PingInterval = 15 * time.Second`（并被 `config.DefaultPongWait = 3 * PingInterval` 真正使用）✅ |
| V37 | `grep -rnI --include='*.go' -e "peerLeft" -e "RemoveRoom" -e "Close()" signaling` | **三者均须命中** | `peerLeft` **23**、`RemoveRoom` **6**、`Close()` **21** —— 三者全中 ✅ |
| V38 | `grep -rnI --include='*.go' "sirupsen/logrus" signaling` / `… "log/slog" signaling` | ≥1 / 0 命中 | logrus **10 行**；`log/slog` **0 命中** ✅ |
| V39 | `. <WS>/env-go.sh && cd signaling && go build ./...` / `… go vet ./...`（**必须用 POSIX 点号，不得 `source`**） | 两条退出码 0 | **均为 0** ✅（另见 §4.3 的宿主机 `-race` 通过证据） |
| C30 / D-2 | 日志键必须 snake_case；协议 JSON 保持 camelCase（low） | 日志键全 snake_case、协议字段不动 | ✅ §5.7：脚本断言（日志 `stun_url=` 2 次、`stunUrl=` 0 次；协议 `"stunUrl"` 2 次、`"stun_url"` 0 次）+ 两道回归守卫（154 个日志键全扫描；端到端双向断言） |
| C31 / V53 | `grep -n '\-log' /etc/systemd/system/signaling.service` | unit 的 `ExecStart` 含 `-log /var/log/signaling/signaling.log` | ✅ 命中；且部署实测已产生 `signaling.log` + `.1`(2097018 B) + `.2`(2097013 B)，§9.2 滚动在生产验证（§10.3） |
| §8.4-1 / §8.4-2 | `ROOM_FULL` 语义 = transient（服务端事实）/ `pong` 不改变"按 `type` 分发"义务 | 服务端零改动即满足 | ✅ 服务端 `ErrRoomFull` → `ROOM_FULL`（§5.1 报文）；服务端只回 `pong`、不要求客户端顺序处理（§11 有"读流混入 `pong`"的用例证据） |

---

## 8. 未决问题

1. ~~**`-race` 未在容器内执行**（无 gcc）~~ → **已闭合**：2026-09-13 15:57–15:58 通过 SSH 在宿主机执行，`go test -race -p 1 ./... -count=1` 与「race 版真实服务进程 + race 版客户端 5 轮真实 TCP 流程」均 **0 竞争报告、退出码 0**，见 §4.3 与 `signaling/logs/t9-race-evidence.log`。
2. ~~**`created` 不含本端 `peerId`**（§6 修正 9）：需 architect 裁决是否扩字段~~ → **已闭合（architect 裁定 D-3）**：不扩字段，维持 `doc/09` 冻结；本端 ID 由客户端确定性推导（§8.2），Go 侧无需改动。
3. ~~**§9.1 示例行与文字规则的 1 空格差异**（§6 修正 15）~~ → **已闭合（architect 确认示例笔误）**：契约已修正示例并补全 `tag` 定宽 9 的文字规则；我的常量（7/6/9 + 1 空格）即正确口径。
4. **TLS/WSS**：契约 C13 冻结为明文 `ws://47.238.144.66:8443/ws`（不用 wss），本服务只监听明文 8443，与之一致；Caddy/TLS 为可选增强（U3，默认不装）。
5. **`signaling/` 尚未 `git add`**：`.gitignore` 已由 env-installer 修正（C23：只忽略 `/signaling/signaling`、`/signaling/dist/`、`/signaling/logs/`，源码可入库），但源码入库动作属 t3/t10 的提交范围，本任务未执行 `git add/commit`。
   - **入库白名单实测（go-dev 2026-09-13 16:00 复测，与 env-installer 15:59 结论一致）**：`git add --dry-run signaling/` = **23 项 = 20 个 `.go` + `go.mod` + `go.sum` + `README.md`**；二进制 / `dist/` / `logs/` 混入数 **0**。清单：`README.md`、`config/config.go`、`go.mod`、`go.sum`、`logging/{formatter,formatter_test,logging,logkeys_test,rotating,rotating_test}.go`、`main.go`、`protocol/{errors,heartbeat,message}.go`、`room/{manager,manager_test,peer,room}.go`、`server/{e2e_test,server,ws_handler}.go`、`util/{roomid,roomid_test}.go`。
   - **更正**：我在给成员的通报里曾写成「24 项」（重复计了 `logging/logkeys_test.go` 一次），**正确值为 23 项**；env-installer 的 dry-run 两次均为 23（15:55 那次已含该文件），以他的实跑为准。
6. **日志滚动未做跨天/进程重启的额外策略**：按契约仅 2 MiB×3；长期运行需 systemd/journald 或外部 logrotate 配合（当前未配置）。
7. **转发背压极端情形**：对端 >5s 不读时，发送方读循环会被逐条等待拖慢，理论上可能触发其自身 45s 读超时；Demo 规模（2 人、数十条 ICE）不会触发。
8. **（后续增强，本轮不做）口径 A 的服务端可观测项**：若将来实施"≥5s 有界宽限期"，建议同时加一条诊断——转发 `offer`/`answer` 后 N 秒内无新 `ice` 候选时记 `renegotiate_no_ice`（Warn），把"信令全通、媒体不通"的半成功状态显性化（见 §11；本轮口径 B 无重协商场景，故不实施，避免未使用的时间轮带来状态与误报）。
9. **（后续增强，本轮不做）ICE restart 的服务端无关性**：重协商所需的 ICE restart 完全在客户端媒体栈内完成，服务端只做原样透传；若 android-dev 在真机上确认"既存 PC 重协商需要 iceRestart"，**服务端无需任何配合改动**（已与 §8.5/D-7 的"两种口径服务端都不动"一致）。

---

## 9. 复现命令

```sh
# 0) 环境
. /data/dsh/home/workspace/env-go.sh && go version      # go1.22.12 linux/amd64
cd /data/dsh/home/workspace/code/webrtc-demo/signaling

# 1) 编译 + 静态检查 + 全量测试（27 例）
go build -trimpath -ldflags "-s -w" -o signaling .
go vet ./... && gofmt -l .
go test ./... -count=1

# 1b) 数据竞争检测（需 gcc；容器内无 gcc，走宿主机）
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i /home/node/.ssh/id_ed25519 \
    root@172.21.0.219 -p 5766 \
    'cd /opt/dsh-workspaces/code/webrtc-demo/signaling && . /opt/dsh-workspaces/env.sh \
     && export CGO_ENABLED=1 && nice -n 10 go test -race -p 1 ./... -count=1'

# 2) 活体端到端（真实端口 + 真实日志文件）
./signaling -addr 127.0.0.1:18443 -stun stun:127.0.0.1:3478 \
    -turn 'turn:127.0.0.1:3478?transport=udp' -user demo:demopass \
    -log logs/signaling-live.log -log-level debug &
curl -s http://127.0.0.1:18443/healthz
curl -si http://127.0.0.1:18443/ws | head -5
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18443/signal    # 404（C11）
SIGNALING_WS_URL=ws://127.0.0.1:18443/ws go test ./server -run TestLive_FullCallFlow -v -count=1
kill -TERM %1 && wait            # 退出码 0

# 3) wscat 验收（doc/13 阶段 2；stdin 用命名管道保活）
WSCAT=/data/dsh/home/workspace/go/npm-tools/node_modules/.bin/wscat
mkfifo /data/dsh/home/workspace/go/tmp/in && ( sleep 30 > /data/dsh/home/workspace/go/tmp/in ) &
$WSCAT -c ws://127.0.0.1:18443/ws -x '{"type":"create"}' -w 10 < /data/dsh/home/workspace/go/tmp/in

# 4) 宿主机侧复用同一二进制（同一份工作区）
ssh ... 'cd /opt/dsh-workspaces/code/webrtc-demo/signaling && ./signaling -addr :8443 ...'
```

---

## 10. 契约裁定闭环（2026-09-13，architect 第 2 轮裁决后）

architect 已就 t9 的 5 项反馈逐条裁决；下面把「裁定 → 我的实现状态 → 证据」对齐，便于 verifier 直接引用。

| 编号 | 裁定内容 | 我的实现状态 | 证据/位置 |
|---|---|---|---|
| §9.1 列宽 | `LEVEL`=7、`layer`=6、`tag`=9（各后接 1 空格）；示例中 `go` 行多 1 空格是**笔误**，已修正；verifier 按文字规则判定 | **无需改动**（常量即 7/6/9） | `logging/formatter.go` 的 `levelWidth/layerWidth/tagWidth`；`TestFormatterContractLayout` 逐字符断言；契约 §9.1「列宽与笔误修正（2026-09-13）」 |
| §8.2 / D-3 | `created` **不扩** `peerId`；本端 ID 由客户端推导（joiner 用 `joined.peerId`；host 用 `peerJoined.peerId` 取反）；禁止槽位推断 | **无需改动**（服务端本就按槽位分配、从不做身份假设） | `room/room.go` 的 `PeerID1/PeerID2` 槽位分配；`reports/09-go-signaling.md` §6 修正 9 |
| §8.3-4 / U7 | 未在房 `create` 是**合法主路径**（不得拒绝）；已在房再 `create`/重复 `join` → `INVALID_MESSAGE`（含 `already in room <roomId>`）+ Warn | **一致**：`handleCreate` 仅在 `peer.Room() != nil` 时拒绝；`handleJoin` 同 | `server/ws_handler.go` `handleCreate`/`handleJoin`；`TestE2E_FullCallFlow`（新建连接 create→join→peerJoined）+ `TestE2E_ErrorCases`（重复 create/join 各返 `INVALID_MESSAGE`） |
| C30 / §11.4 D-2 | logrus `Fields` 键必须 snake_case；**只约束日志键名**，协议 JSON 保持 `doc/09` camelCase | **已对齐**（§5.7） | §5.7 并排原始输出 + `TestSourceLogKeysAreSnakeCase`（154 键）+ `TestLogKeysSnakeCaseProtocolJSONCamelCase` |
| §11.4 D-1 | `/signal` 别名：允许显式标注的 deprecated 别名，不判失败；bot 现状为**已删除** | **保持删除**（实测 404） | `logs/t9-live-evidence.log` 第 2b 项：`curl /signal → HTTP 404`，服务端日志 `http_unknown_path path=/signal` |
| V39 质量项 | `go test -race`：容器无 gcc 记「未验证」，由 **t10/t11 在宿主机**补跑 | **已由我在宿主机执行并通过**（§4.3），可复现 | `signaling/logs/t9-race-evidence.log`（两段原始输出：全量 race + race 版服务进程 5 轮真实 TCP，0 竞争报告，退出码 0） |

> **给 verifier 的两点**：
> 1. V39 的 race 质量项若要求「由 t10/t11 执行」的严格独立性，可自行重跑（命令见 §4.3，哈希/结果应与本文一致）；若接受既有证据，请引用 `t9-race-evidence.log` 并注明执行者为 go-dev、时间 2026-09-13 15:57–15:58、宿主机 `root@172.21.0.219:5766`。
> 2. `signaling/` 目录下仍存在 camelCase 字符串，但**均非日志键**：协议 JSON 字段（C12 冻结）+ `/healthz` 的 HTTP 响应体键（我额外加的诊断端点，属 HTTP JSON）。请按 §9.1 的文字范围（仅日志键名）判定，避免假失败。

### 10.1 契约文件指纹（go-dev 每次与磁盘实测比对）

**指纹随契约迭代在变，verifier 请按契约 §0 条款在复跑时自行 `sha256sum` 取权威值。** go-dev 实测记录（均为「architect 公布值 vs 磁盘实测」）：

| 轮次 | architect 公布 | go-dev 磁盘实测 | 结论 |
|---|---|---|---|
| 第 2 轮裁决 | 1257 行 / `6f664977…eea6a` | 1264 行 / `621184098a7388f4…a8e9c3` | 快照错位（消息早于磁盘保存），非契约被改 |
| C31 裁定前（v1.0-i） | 1278 行 / `25e18d06…01df` | **一致** ✅ | — |
| C31 裁定后（v1.0-j） | 1279 行 / `d2a90beb…45b996` | **一致** ✅ | §3.2/§9.2 改为 `/var/log/signaling/signaling.log`，新增 C31，V53 追加 `-log` 断言 |
| 重连边界更正（v1.0-m） | 1284 行 / `f0201bce1bd753ff38339d2efae5d8feee920f69c6e128c47bb69d53f2dc490a` | **一致** ✅（08:19:38Z 实测） | 采纳本文 §11 的用例结论：**撤回** v1.0-l「双方同时重连会互等」的错误论断（改为引用本文两个用例名，逐字一致）；**§3.3 保持原样、不加兜底条款**；D-6 保留但理由更正为「为确定性」；`ROOM_FULL` 重试属客户端策略，仅登记在契约报告 §27.4、不改契约 |
| **口径定论落位（v1.0-o，D-1…D-7 冻结）** | 1325 行 / `894f15bffefda1e80d75f1fbd7e3fa4da27158d41709af5010398baa950904dd` | **一致** ✅（16:25:02+08:00 实测） | 新增 **§8.4**（`ROOM_FULL` transient 不视为终态；**必须按 `type` 分发**、不得假定消息顺序）与 **§8.5/D-7**（**本轮口径 B**；A 为后续增强且**需与 ICE restart 一起做**）；§8.5 引用本文 §11 实测表与用例名（逐字一致）。**这是现行契约**——v1.0-m 的 `1284/f0201bce…` 已随之失效 |

契约 §0 已写入止血条款（权威文本 = 磁盘文件；引用哈希/行数不一致时以磁盘实测为准；旧引用只代表历史版本；verifier 不一致时按「快照错位」处理，**不得**判「契约被改动」），§15.3 为指纹历史表（v1.0-i/j/m/o）。**注意**：本文 §11「阶段 1」曾按 v1.0-m 时点写"契约本轮零改动 / 1284 / `f0201bce…`"——该快照已在 v1.0-o 失效，**以本表与 §11「阶段 2/契约终版落位」为准**。

对 t9 的影响：**无**（§9.1 列宽、V39、§8.2/§8.3、§10 C30/C31/D-1/D-2/D-3 均已逐条比对一致；t9 实现与交付物哈希从未变过）。

**本报告完稿时的磁盘实测值（按团队约定的「磁盘实测 + 时间戳」记法，不再复述消息里可能过期的哈希）**：

```
2026-09-13T16:08:01+08:00  sha256sum doc/14-interface-contract.md
d2a90bebd8a7a58a1d9a8fe8ac650b8f97f95f90d60b7b3dc4178337ad45b996  doc/14-interface-contract.md   (1279 行，v1.0-j，冻结)
```

约定（architect 已登记在 `reports/02-interface-contract.md` §24；**该约定登记时契约未改，其后 v1.0-o 已改契约，见上表最新一行**）：契约侧以 §0「权威文本 = 磁盘」为唯一兜底、不再逐轮对账；成员报告只写**磁盘实测哈希 + 时间戳**、消息里不复述可能过期的哈希；verifier 复跑前先 `sha256sum`，不一致按「快照错位」处理，**不得**判「契约被改动」。

### 10.2 部署态事实与日志路径 → **已由契约 C31 裁定（方案 1：以部署事实为准）**

t12（coturn-installer）完成部署后，go-dev 做了一次**只读**交叉复核（未改宿主机任何文件/配置），发现契约 §3.2/§9.2 的 Go 路径（`/opt/signaling/logs/signaling.log`）与部署事实（`/var/log/signaling/signaling.log`）不一致，遂呈报 architect 裁定。

**裁定结果（2026-09-13，采纳方案 1）**：契约 **§3.2 部署行**与 **§9.2 表格 Go 行**均改为 **`/var/log/signaling/signaling.log`**（+ `.1`/`.2`），并写明「由 `-log <path>` 指定、目录自动 `MkdirAll`、滚动与双写与路径无关、**旧值 `/opt/signaling/logs/…` 作废（非偏差）**」；**新增 C31**（含部署证据与「verifier 不得据此判失败」）；**V53 追加**「signaling unit 的 `ExecStart` 须含 `-log /var/log/signaling/signaling.log` 」（未新增 V 编号）。

**t9 侧零代码改动**（`-log` 本就是 flag）；仅把 `signaling/README.md` §7 systemd 示例与 §8 偏差清单同步为 `/var/log/signaling/signaling.log`，使交付物文档与冻结契约一致。

只读复核原始输出（裁定依据）：

```
$ systemctl is-active signaling ; systemctl is-enabled signaling
active
enabled
$ systemctl cat signaling | grep -E '^ExecStart|^WorkingDirectory|^Restart'
WorkingDirectory=/opt/signaling
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn turn:47.238.144.66:3478?transport=udp -user demo:demopass -log /var/log/signaling/signaling.log
Restart=always ; RestartSec=3
$ sha256sum /opt/signaling/signaling
c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068  /opt/signaling/signaling     # 与 t9 交付物逐字节一致
$ ls -l /var/log/signaling/ ; ls -ld /opt/signaling/logs
-rw-r--r-- 1 root root 21904 ... signaling.log
ls: cannot access '/opt/signaling/logs': No such file or directory
$ curl 状态：healthz=200  /ws=400  /signal=404
```

**当时发现的差异（low）与最终处置**：契约 §3.2/§9.2 原写 `/opt/signaling/logs/signaling.log`（+`.1`/`.2`），而 t12 按其任务描述部署为 **`/var/log/signaling/signaling.log`**（systemd unit 显式 `-log`）。

- **Go 侧无需改动**：`-log` 是命令行参数，目录自动创建、2 MiB×3 滚动、终端+journald 双写都与具体路径无关（t12 实测文件 21904 B 正常写入、journald 同源）。
- **已裁定（architect，2026-09-13，采纳「以部署事实为准」）**：契约 §3.2/§9.2 改为 `/var/log/signaling/signaling.log`，新增 **C31** 记录依据与「verifier 不得据此判失败」，**V53 追加** `ExecStart` 须含 `-log /var/log/signaling/signaling.log`；旧值 `/opt/signaling/logs/…` 标记为**作废（非偏差）**。详见本节开头 §10.2 的裁定说明。
- verifier 若对日志路径做校验：以**冻结契约（C31 后的 `/var/log/signaling/signaling.log`）+ 部署事实**为准；**不得**因 t9 而判失败（t9 交付的是 flag 化功能，路径由部署决定）。

> 另：t12 另交付了一个独立实现的零依赖 Node 客户端 `scripts/verify_signal_e2e.mjs`，从公网与内网入口各跑 19/19 PASS（create→created→join→joined→peerJoined→offer/answer/ice 字节级原样转发→natType→ping/pong→leave→peerLeft→close 1000），并用 `created` 下发的 TURN 凭据真实 Allocate 成功。这是对 t9 协议实现的**第三方独立验证**（非 gorilla/Go 栈），可作为 §5.1/§5.3 之外的补充证据（出处：`reports/12-deploy-signaling.md`）。

### 10.3 生产环境实测：§9.2 滚动在真实部署中生效（go-dev 只读复核 2026-09-13 16:0x）

t12 部署的服务已实际产生过滚动，**自研 `RotatingWriter`（2 MiB × 3）在真实运行中得到端到端验证**：

```
$ ls -l /var/log/signaling/
total 4512
-rw-r--r-- 1 root root  421710 Sep 13 16:04 signaling.log      # 当前文件
-rw-r--r-- 1 root root 2097018 Sep 13 16:03 signaling.log.1    # 2097018 B < 2 MiB = 2097152 B
-rw-r--r-- 1 root root 2097013 Sep 13 16:02 signaling.log.2    # 2097013 B（最旧）

$ grep -n '\-log' /etc/systemd/system/signaling.service        # 契约 V53 的核对命令
9:ExecStart=/opt/signaling/signaling ... -log /var/log/signaling/signaling.log

$ systemctl is-active signaling ; curl 状态
active
healthz=200  ws=400  signal=404
```

要点：
- 两个历史文件都**恰好停在 2 MiB 上限之下**（2097018 / 2097013 ≤ 2097152），说明「写前判断 + 先滚动后写」的策略在真实负载下正确，没有出现超限文件；保留数正好 3（当前 + `.1` + `.2`），与 §9.2 冻结值一致（单元测试用小上限覆盖过快路径，这里是**生产实测**）。
- **V53（C31 追加项）已满足**：unit 的 `ExecStart` 含 `-log /var/log/signaling/signaling.log`，即契约新冻结路径，**t12 无需任何改动**。
- 端点行为与 t9 自测一致（`/healthz`=200、`/ws`=400、`/signal`=404）。
- 出处：本节的原始输出由 go-dev 通过只读 SSH 复核采集（未改宿主机任何文件）；t12 的部署与验收细节见 `reports/12-deploy-signaling.md`。

---

## 11. 与 Android 客户端的协议对齐记录（交叉核对，2026-09-13）

按 `doc/14` §8 的口径，go-dev 把服务端契约要点（端点/字段名/`pong` 语义/错误码/roomId 规则/原样转发/`leave` 与重连/45s 读超时）发给 android-dev 逐条比对；对方回执：**8 条中 5 条本就一致、3 条客户端真缺陷已修**，服务端**零改动**。

- 客户端已修：① 心跳发送时未记录 `pingSentAtMs`（导致 doc/09 §6 的「5s 未收到 pong 视为断线」永不触发）；② 断线重连时仍走 `create`（会新建房间、丢掉对端）→ 改为收到 `created`/`joined` 后把连接意图统一切为 `join(原 roomId)`（host 也不例外），与 doc/09 §6.4 一致；③ 收到 `ROOM_EXPIRED` 等终态错误后仍重连 → 增加终态码抑制表。
- 客户端确认：不依赖 `created.peerId`（UI 也不显示本端 ID，故 D-3 对其无阻塞）；不对 `pong.timestamp` 做配对/差值运算（RTT 取 stats 的 `currentRoundTripTime`），与 §8.1「服务端时间戳」语义零耦合。
- **一处需要双方共同守住的口径（go-dev 复核后提出）**：重连后**谁发 offer**。契约 §3.3 的规则是「**收到 `peerJoined` 的一方是 offerer**」；android-dev 目前用「`role == host` 的一方发 offer」。在 1:1 场景下两者都只让一方发 offer，**当前可用**；但两条规则不等价（host 断线重连时，收到 `peerJoined` 的其实是留在房内的 joiner）。**安全不变量是「同一时刻只有一方发 offer」**，实现只要满足它即可；已建议对方在 `CallViewModel` 里显式注释该不变量，避免后续改角色判定时双方互等。已同步写入 `signaling/README.md` §3.1。
- 客户端已知限制：重连后的 offer **暂未做 ICE restart**（待 t5 的 `libwebrtc-java.jar` 到位后核对 `iceRestart` 重载形态）。服务端不介入媒体协商，故 t9 侧无需改动；若联调观测到「重连后信令恢复但媒体不通」，由客户端补 `iceRestart`。
- 证据（对方提供）：`/tmp/t14/godevcheck.sh` 25/25（容器无 JDK，均为静态核对）；出处 `reports/08-android-dev.md` §8.3。

**后续（android-dev 第 2 轮回执：D-3 已在客户端真正实现）**：对方不是"知悉"，而是落地了实现：

- 新增 `SignalingIdentity`（置于契约 §2.1 冻结的 `signaling/SignalingClient.kt` 内，不新增文件）：joiner/重连者用 `joined.peerId`（**权威值，可覆盖旧值**）；host 在 `peerJoined` 到达且**本端未知时**才 `invert(peerId)`；`invert()` 显式 `peer-001 ↔ peer-002`，无法识别返回 null（**不做槽位顺序推断**）；`PeerLeft` 清对端、新会话 `reset()` 清两侧。更新点放在**收帧处**而非 ViewModel，避免页面切换丢失更新。
- 落地为可见 UI（`CallUiState.selfPeerId/remotePeerId` → 通话页「对端身份」行 + 诊断页），不是"加了没人用的字段"。
- 对方对我的 `doc/09 §5.1` 报文样例做了**逐条交叉核对**：create / created（**无 `peerId`**）/ joined / peerJoined / peerLeft / offer / answer / ice / ping / pong / error 全部与本文 §5.1 一致；`error` 五个码按码处理并抑制重连；`sendOffer/sendIce` 有"未入房不发媒体信令"前置守卫（避免触发服务端 `NOT_IN_ROOM`）；`ice` 的 `sdpMid/sdpMLineIndex` 为 null 时按 `explicitNulls=false` 不序列化，与「至少一个有效」一致。
- 与服务端语义的两条互相确认：客户端**从不主动发 `pong`**（服务端对该类型回 `INVALID_MESSAGE`，符合 §2.1「S→C only」）；`offer requires non-empty field: sdp` / `ice requires non-empty field: candidate` 由其发送前守卫保证不会触发。
- 对方验证：11 组检查器全绿（新增 `d3check.sh` 15/15，含「剔除注释后无槽位顺序推断模式」的代码级断言）；仍为**静态证据**（容器无 JDK，未做运行时验证）。
- **服务端结论：零改动**（交付物 `c298235a…c068` 未变）；本文 §3.1 的「同一时刻只有一方发 offer」不变量与对方实现相容。

**后续（go-dev 用两个新用例把「重连边界」变成可复现事实，2026-09-13）**：android-dev 给出的「四场景表」中第 3 行（*双方同时重连 → 双方都只收到 `joined`、无人收到 `peerJoined` → 若严格按 §3.3 字面规则会双方互等*）是**对通用信令服务的合理担忧，但在本实现中不可达**。我为此补了两个端到端用例并据此更正/细化结论：

| 用例 | 验证的事实 |
|---|---|
| `TestE2E_ReconnectStaleSessionCausesRoomFull` | 旧会话**静默掉线**（TCP 未关、只是不再收发）时，服务端要等读超时（`-pong-wait`，默认 45s）才回收其槽位；期间设备侧「立即重连」的 `join` 得 **`ROOM_FULL`**；回收后仍在线的一方**先收到 `peerLeft`**，此后**同一条新连接重试 `join` 即可成功**（按空槽位再拿到 `peer-002`）。→ **重连时的 `ROOM_FULL` 是可重试状态，不是终态。** |
| `TestE2E_ReconnectAfterBothDisconnectedIsRoomNotFound` | 双方都断开 → 房间变空 → **立即销毁** → 用原 `roomId` 重连得 **`ROOM_NOT_FOUND`**。→ 要让两个新连接都被接纳，房间必须已空，而房间一空即销毁；**故「双方都收到 `joined` 且无人收到 `peerJoined`」的组合不可达**，§3.3 字面规则不会因此产生「双方互等」死锁，**契约无需为此加兜底条款**。 |

- 用例 1 的实现细节（对后续复用有价值）：为了让「只有静默掉线的旧会话被回收」，测试把 `PongWait` 调到 600ms，并给**需要存活**的连接加 `startHeartbeat()`（250ms 一次应用层 ping）；由于心跳会带回 `pong` 混入读流，新增了 `expectTypeWithin(type, timeout)`（跳过非目标消息）与 `writeRaw()`（互斥写，满足 gorilla「单写者」约束）。这一发现也说明：**带心跳的客户端读流里本来就会混入 `pong`**，客户端解析必须按 `type` 分发而不能假设"下一条一定是 X"。
- 用例总数 27 → **29**（`go test ./... -count=2` 全通过）；**生产代码零改动**，交付二进制仍 `c298235a…c068`。
- 已同步到 `signaling/README.md` §3.1（新增「两个已验证的边界」表）并告知 android-dev（其客户端当前把 `ROOM_FULL` 归入终态抑制重连，建议按上表改为**有界重试**）。

**后续（go-dev 实测「静默掉线→回收→销毁」时序，2026-09-13）**：android-dev 推理出「静默掉线后完整恢复只在房间被销毁前可行」，并向我索取实测时序。我新增用例 `TestE2E_ReconnectTimingAfterStaleReap`（把 `-pong-wait` 调成 800ms 以便在测试内观测；3 轮稳定）测得：

| 量 | 实测（PongWait=800ms） | 换算到生产（PongWait=45s） |
|---|---|---|
| 静默掉线 → 服务端回收死会话、survivor 收到 `peerLeft` | **801 ms**（reap 与 peerLeft 之差仅调度抖动） | ≈ 45 s |
| 房间仍保留（survivor 未挂断）→ 重连方 `join` 成功 | **1 ms**（服务端 sub-ms） | ≈ RTT + 1ms |
| survivor 收到 `peerLeft` **立即** `leave` → 房间销毁（此后 `join` = `ROOM_NOT_FOUND`） | **0–3 ms** | ≈ RTT + 3ms |

- **推论**：房间是否销毁**完全由"仍在线一方的挂断策略"决定**，服务端不做兜底。若 survivor 严格按 doc/09 §4「`peerLeft` → `DISCONNECTED`」立即挂断，可恢复窗口只有毫秒级 ⇒ 秒级重试必然错过 ⇒ 最终 `ROOM_NOT_FOUND`（终态）；若 survivor 给 ~5 s 宽限期，重连方下一次重试（≤2 s）即可成功，服务端侧重入仅 ~1 ms。
- 已把该裁定需求（口径 A 宽限期 / 口径 B 严格）连同实测表提交 **architect**；**两种口径下服务端都无需改动**。我明确不建议"服务端空房保留 N 秒"（与 §7「2 人都离开后立即销毁」冲突，且扩大房间码被占用窗口）。
- 用例数 29 → **30**，`go test ./... -count=2` 全绿；生产代码零改动，交付二进制仍 `c298235a…c068`。

**阶段 1（architect 的技术推荐，**已被 captain 裁定覆盖，仅作留痕**）**：architect 曾在 `reports/02-interface-contract.md` **§30** 推荐**口径 A（有界宽限期）**、口径 B 为合规替代，并按 captain 的冻结纪律**只登记在报告**。该时刻 `doc/14` 确实未改（当时实测 `1284 行 / f0201bce…dc490a`，16:23:15+08:00）——**但这个快照随后失效**（见下"阶段 2"与 §10.1 的 v1.0-o 行），**切勿再把 1284/`f0201bce…` 当作现行契约**。

> ⚠️ **口径传达更正记录（对应 android-dev 报告 §8.10 的过程事件 E-1）**：go-dev 曾把 §30 的 A 推荐**先于 captain 定论**转达给 android-dev，导致其按 A 实施（`PeerLeftGrace.kt` 等），随后 captain 裁定 B、我方发出更正、对方按"契约优先"核对后**完整回滚**并交付口径 B。
> **最终口径以 §8.5 / D-7 为准 = 本轮 B**；**口径 A 未经 captain 书面授权不得实施**，且实施时必须**与 ICE restart 一起做**（见下"最终口径"段与 §8.5）。本条记录的目的就是**防止再有人据 §30 的推荐去实施 A**。

**阶段 2（captain 定论，**现行有效**）**：

> **决策依据 = `doc/14` 现行契约 v1.0-o 的 §8.5 + §11.4 D-7**（磁盘实测 1325 行 / `894f15bf…04dd`，16:25:02+08:00）。本文及 `signaling/README.md` 中凡提到 `reports/02` **§30** 之处，一律是 architect 的**技术推荐/留痕**，**不是决策**；**口径 A = 后续增强，需与 ICE restart 一起做，本轮不实施**。

**最终口径（captain 裁定，2026-09-13；**以本条为准**，覆盖上文 architect §30 的 A 推荐）**：

- **本轮取口径 B（严格字面）**：survivor 收到 `peerLeft` 立即转 `DISCONNECTED` 并挂断 → 房间销毁 → 掉线方最终得 `ROOM_NOT_FOUND`（终态）→ 双方干脆回首页并明确提示，**不产生半死活状态**。
- **口径 A（≥5 s 有界宽限期）登记为后续增强**，且必须**与 ICE restart 一起做**。captain 的理由（第二条为其关键依据）：① 我的时序实测把"宽限期"从猜测变成有数据支撑的选项（房间是否销毁完全由 survivor 挂断策略决定，立即挂断窗口 0–3 ms、给 5 s 则下一次重试即可恢复）；② **但**"静默掉线后完整恢复"还依赖重协商，而 android-dev 已登记"重连后 offer 暂未做 ICE restart"、本轮**无真机可验证**——只加宽限期会得到"信令已重连、媒体是死的"僵尸通话，比干净挂断更糟；③ 故选一致、诚实的失败路径。
- captain 同时确认：**服务端保持零改动是正确的**；`ROOM_FULL` 移出终态码 + 有界退避（含"仅掉线重连语境退避、首次入房仍即时提示"的语境区分）保留；`pong` 混流须"按 `type` 分发"已由 captain 要求 architect 写入**契约 §8**（消息级协议义务）。
- **t9 侧动作：零代码改动**（两种口径下服务端行为相同；交付物 `c298235a…c068` 未变）。已把 `signaling/README.md` §3.1 从"A 推荐"更正为"**本轮 B**、A 为后续增强"，并**主动更正了此前发给 android-dev 的口径消息**（避免其按 A 改错方向）。

**契约终版落位（architect，2026-09-13，**v1.0-o，D-1…D-7 正式冻结**）**：磁盘实测 **1325 行 / `894f15bffefda1e80d75f1fbd7e3fa4da27158d41709af5010398baa950904dd`（16:25:02+08:00）**，与 architect 公布值逐字符一致 ✅。本轮新增两条**消息级**义务（进契约 §8.4）与一条裁定（§8.5/D-7），go-dev 逐条核对：

| 契约条目 | 内容 | 与 t9 的关系 |
|---|---|---|
| §8.4-1 | **`ROOM_FULL` = 暂时性（transient）→ 不应视为终态**；`ROOM_NOT_FOUND`/`ROOM_EXPIRED` = 终态（停止重试）。策略（退避时长/次数）不进契约，留在报告 §27.4/§30 | 与我 §11 给 android-dev 的建议一致；服务端 `ErrRoomFull` → `ROOM_FULL` 语义不变，**零改动** |
| §8.4-2 | **必须按 `type` 分发**每条消息；**不得**假设「发 `join` 后下一条必是 `joined`」，不得用"下一条消息"同步状态机 | 我在 `TestE2E_ReconnectStaleSessionCausesRoomFull` 里发现的"读流混入 `pong`"即此条的现实来源；服务端行为不变 |
| §8.5 / **D-7** | 本轮取**口径 B**（`peerLeft` → 立即 `DISCONNECTED`/挂断 → 房间销毁 → 掉线方 `ROOM_NOT_FOUND` 终态）；**口径 A 作为后续增强**（需**与 ICE restart 一起做**）；**两种口径服务端都无需改动** | **零改动**；§8.5 引用了我的时序表（801 ms / ≈45 s / 0–3 ms / ≈RTT+3 ms）与用例名 `TestE2E_ReconnectTimingAfterStaleReap`（逐字一致，已核对） |

- architect 说明：其报告 §30 保留"推荐 A"的分析（依据未被推翻，即"分析留痕、决策明确"），但**决策以 §8.5/D-7 为准**。我在本文 §11 与 `signaling/README.md` §3.1 均已按 **B** 收口，并新增了 §8.4 两条消息级义务的引用块（便于 verifier/实现者直接引契约条文）。
- **t9 结论：契约终版对本服务端零要求**（D-1…D-7 全部与既有实现一致）；交付物 `c298235a…c068` 未变，用例 30 个 `-count=2` 全绿。

**客户端侧的两条实现事实（android-dev 回执，值得与 §8.5/D-7 对照留档）**：

1. **恢复钩子两侧都已存在**（所以"只要宽限期保住房间，两种角色组合都能恢复"）：`PeerConnectionObserver.onPeerJoined → maybeCreateOffer()`（`CallViewModel.kt:262-266`，实现在 `:365`，**仅 `ROLE_HOST` 主动 offer**）；若 survivor 是 joiner，则由**重入的 host** 在其 `joined` 分支（`:255-259`）发 offer ⇒ 与 **D-6 角色固定 offerer** 一致，**不需要新写信令**。
2. ⚠️ **关键坑（与 captain 的裁定理由完全同源）**：在**既存 `PeerConnection`** 上重新 `createOffer()`，SDP 会更新但 **ICE 代际未变**，媒体路径**不保证重建** → 很可能需要 **ICE restart**；否则会出现"信令层面 `join/joined/peerJoined/offer/answer` 全顺、但画面始终是黑的"这种**半成功状态**，比直接 `ROOM_NOT_FOUND` 更难定位。
   → 这正是 captain 本轮取**口径 B** 的第二条理由（"只加宽限期不做重协商 = 僵尸通话，比干净挂断更糟"）的客户端侧独立佐证；android-dev 也已把该坑提给 architect，并明确"在裁定前不动 `CallViewModel.kt:268-272`"。**双方结论一致，无需任何一方改动。**

**给"口径 A 后续增强"预先备好的服务端可观测项（本轮不实施）**：口径 A 若实施，服务端可加一条**把"信令全通、媒体不通"半成功状态显性化**的诊断——在转发 `offer`/`answer` 后若 **N 秒内没有新的 `ice` 候选**到达，记一条 `renegotiate_no_ice room=… peer=… seconds=…` 的 Warn 日志（"重协商未伴随 ICE 涓流"的指纹）。
- 为什么本轮不做：口径 B 下不产生重协商场景，加未使用的时间轮会增加状态与误报风险；且服务端只能看到信令，不能断言媒体状态，这条只是**相关性提示**而非判据。
- 为什么值得预先登记：将来做口径 A 时，这条日志能把最难定位的"半成功"状态从"画面黑、日志全绿"变成一条可直接 grep 的线索。若届时 android-dev/architect 需要，我按一行配置开关加上（不影响既有行为）。

---

## 12. 协作纪律（go-dev 采纳，2026-09-13，captain 明示）

**背景（本文 §11「口径传达更正记录」）**：go-dev 曾把 architect 的**技术推荐**（`reports/02` §30 = 口径 A）当"已裁定"转达给 android-dev，而 captain 的裁定 B 与 `doc/14` §8.5/D-7 落盘晚于该转达 → 对方按 A 实施后自行回查契约、完整回滚（其报告 §8.10 记为过程事件 E-1）。改动未进 t10，损失有限，但方向性错误属必须避免的类型。

**采纳的规则（此后一律照做；第 1 条为 captain 要求正式化的防呆标记）**：

1. **【待定 / 勿实施】标记（强制）**：转达任何**尚未定论**的事项时，必须：
   - 在转达文本**开头加显式标记 `【待定 / 勿实施】`**；
   - 并**写明性质**，二选一、不得含混：
     - 「这是 **X 的推荐**（未经 captain 裁定 / 尚未落入 `doc/14`）」；
     - 「这是 **X 的裁定 / 契约已落盘（`doc/14` §…）」。
   - 只有当事项**已被 captain 裁定或已落入 `doc/14`** 时，才可省略该标记。**根因**：本次 E-1 不是"转达了错误信息"，而是**"推荐"在传递中丢掉了"未定论"这一性质**，接收方因此无法判断该不该动手；加一个标记几乎零成本，却直接堵死该失效模式。
2. **接收方在动手前必须回到 `doc/14` 原文核对**（本次 android-dev 正是这样发现方向相反并及时回滚）。
3. **先发的不含定性的转达若发现已被覆盖，立即补发更正**（本次更正窗口仅数分钟，但**更正不能替代前置标注**）。
4. 涉及口径/契约的转达，**引用现行契约条文号 + 磁盘实测哈希/时间戳**（按 §10.1 的记法），不引用可能过期的消息内容。

**关联登记（供 verifier 交叉引用，2026-09-13 闭环）**：
- architect 已按其报告 **§33.1** 固化：「**"可实现 A" 必须同时具备 ① captain 明示授权 ② `doc/14` §8.5/D-7 的升级，二者缺一不可**」；**§33.3** 登记 A 的将来硬约束：**必须包含"允许对既存 `PeerConnection` 做 ICE restart"**，且本容器无真机 → 验收只能放宽到"仅验信令恢复"层。
- captain 裁定：**不做纪律处分**；要求即为本条规则 1 的正式化（已写入）。
- 本文 §11 保留推荐（§30）与裁定（§8.5/D-7）的分层记录；`signaling/README.md` §3.1 保留"请勿实施口径 A"警示与"纯机制说明、非实施建议"的区分。三处口径一致。

Go 侧执行情况：`signaling/README.md` §3.1 已加"**请勿实施口径 A**"加粗警示与来源提醒；本文 §11 已按「阶段 1（推荐·留痕）/ 阶段 2（裁定·现行）」二分；口径 A 一律标注"后续增强、需 ICE restart、本轮不实施"。

### 4.4 构建可复现性与 **VCS 标记**（t10/t11 必读的防假失败结论）

**背景**：go-dev 做了一次 t10/t11 飞行前检查——把 `git add --dry-run signaling/` 的 **23 个被跟踪文件**单独复制到非 git 目录（`<WS>/go/tmp/signaling-fresh`）构建，结果**哈希与交付物不同**。定位如下（全部实测）：

| 构建位置 | flags | sha256 | `go version -m` 的 vcs 字段 |
|---|---|---|---|
| **仓库内**（`code/webrtc-demo/signaling`，交付物同源） | `-trimpath -ldflags "-s -w"` | `c298235a…c068` ✅ 与交付物一致 | `vcs=git`、`vcs.revision=1a9d3ff69667f4198cb7952d8ecd015fc9965052`、`vcs.time=2026-09-13T07:31:55Z`、**`vcs.modified=true`** |
| 非 git 目录（同样的 23 个文件） | 同上 | `1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa` | 无 `vcs.*` 字段 |
| 非 git 目录 | **`-buildvcs=false`** + 同上 | `1b333208…43aa` | 无 `vcs.*` |
| 仓库内 | **`-buildvcs=false`** + 同上 | `1b333208…43aa`（与上一条逐字节 `cmp` 相同） | 无 `vcs.*` |

**结论与给 t10/t11 的规则**（重要，否则会追一个不存在的"源码被改"）：

1. **`c298235a…c068` 的可复现前提是"在同一个 git 工作区内、且 VCS 状态相同"**——Go 会把 `vcs.revision` + `vcs.modified` 编进二进制。**同一位置、同一 git 状态重建可逐字节复现**（上表第 1 行已复现 ✅），但改过 git 状态（例如 **t10 提交 `signaling/` 后 `vcs.modified` 翻转、或 HEAD 前进**）就会得到**不同哈希，即使源码一字未改**。
2. 因此 **t10 阶段 8 在提交后用 `cmp` 对比 `c298235a…` 出现差异属预期**，**不是**源码/产物异常。判定"源码是否变了"请用二者之一：
   - `go version -m <二进制> | grep vcs`：只允许 `vcs.revision` / `vcs.modified` 不同；
   - **两侧都用 `-buildvcs=false` 重建后再 `cmp`**：本报告实测两侧同为 `1b333208…43aa`（源码不变时必然逐字节相同）。
3. **不建议现在改交付物**：`c298235a…c068` 已被 t12 部署到宿主机 `/opt/signaling/signaling` 并完成端到端联调（`reports/12-deploy-signaling.md`），重编会造成"源码相同但哈希不同"的额外解释成本。**规则 2 足以消除假失败**，等 t10 实际提交后再按规则 2 复核一次即可。
4. 附带确认：**只靠 23 个被跟踪文件即可完整构建与测试**（干净目录 `go build` 成功、`go test ./...` 四包全绿）——说明 t10 的提交**不含未跟踪依赖**，提交内容自洽。

### 4.5 ⚠️ 对 §4.4「判据 1」的**修正**：`go version -m` 元数据**不能**证明源码未变（实测）

env-installer 已把 §4.4 的两条判据同时实现进 t10 阶段 8（差异时先做元数据归一化比对，再做 `-buildvcs=false` 确定性重建）。但 **go-dev 用实验证明「元数据比对」作为"源码未变"的**唯一**依据是不成立的**——`go version -m` 的元数据（`mod`/`build`/`vcs` 行）**不包含被编译的代码**：

| 实验 | 源码 | `-buildvcs=false` 构建的 sha256 | `go version -m` 输出（去首行路径） |
|---|---|---|---|
| 基线 | 原样（`const Version = "0.1.0"`） | `1b333208d29110f8ebd61e49…`（= §4.4 的非 VCS 基线） | 14 行 |
| 改 1 处源码 | `const Version = "0.1.1"`（`server/server.go:22`） | **`74a37d0e5040bb5d1c244b7e…`（不同 ✅ 说明确实重编出不同产物）** | **与基线逐行相同（差异 0 行）** |

⇒ 结论：**哈希变了，但元数据一模一样**。若按「元数据只差 vcs 行 ⇒ 源码未变」判定，**一次真实的源码改动会被误判为"仅 VCS 戳差异"→ 给出假 OK**（比假 WARN 更危险）。

**修正后的判据（建议 t10 阶段 8 采用）**：

1. **同 VCS 状态**（未提交/未改 git）→ 直接 `cmp` 相等即 OK（原路径，依旧有效）。
2. **不同 VCS 状态**（例如提交后）→ **用 `-buildvcs=false` 重建，并与"交付时记录的非 VCS 基线"逐字节比较**：
   **期望值 `1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa`**（§4.4 已记录、env-installer 亦独立复现）。
   - 相等 ⇒ **源码可证明未变**（该产物完全不依赖 git 状态，确定性已实测：连续两次构建逐字节相同）；
   - 不等 ⇒ **源码（或工具链/环境）确实变了**：需要人工核对。
3. 元数据比对可**保留为辅助信号**（它能证明 module/构建参数/工具链未变），但**不得**单独用于"源码未变"的判定。
4. **顺序建议**：先做第 2 条（决定性、零歧义），元数据比对只用于补充说明差异来源。

> 附 env-installer 实现时踩到的一个坑（对任何做类似过滤的人有用）：GNU grep 的 **BRE 不把 `\t` 当制表符**，`grep -v 'build\tvcs\.'` 会把 vcs 行留下导致误判；改用 `sed '1d' | grep -v -e 'vcs=' -e 'vcs\.'` 才正确。

> 另：本节实验的临时目录与产物已全部清理（`<WS>/go/tmp/` 现为空），交付物哈希未受影响（仍 `c298235a…c068`）。

### 4.6 §4.4/§4.5 的闭环：t10 阶段 8 **最终规则**（env-installer 实现 + 三场景实测）

env-installer 独立复现了 §4.5 的反向实验（同一改动得到同一哈希 `74a37d0e5040bb5d1c244b7e14f3414c4d2320521a35d7e3f4332b46b6365ff2`），并把**判据 2 升为决定性主判据**、判据 1（元数据）降级为辅助信号。其阶段 8 最终逻辑（已实现）：

```
① cmp 相同                          → OK「t9 产物可复现」
② cmp 不同 → 在 signaling/ 内 -buildvcs=false 重建两次（h2/h3）：
     h2 == h3 且 h2 == NOVCS_BASELINE → OK「哈希变化已确证仅由 VCS 戳引起」
     h2 != h3                         → FAIL「构建不可确定性」
     h2 != NOVCS_BASELINE             → FAIL「源码/工具链确变，不得放行」   ← 拦假 OK
③ 元数据比对仅作辅助，脚本打印「[辅助] …（0 不代表源码未变）」
```

`NOVCS_BASELINE` = **`1b333208d29110f8ebd61e4916b11bd77997b5832137e460269e6bbb6da743aa`**（本文 §4.4 记录、env-installer F1-D 独立复现；已做成可用环境变量覆盖，便于将来重交付时更换基线）。

**env-installer 的三场景实测（宿主机）**：A 源码未变（= 提交后真实情形）→ VCS-free 重建 == 基线 → OK「差异仅 VCS 戳」✅；B 源码真改（`Version` 0.1.0→0.1.1）→ 重建 `74a37d0e…` ≠ 基线 → **FAIL（假 OK 被拦住）** ✅；C 辅助元数据在源码改动后差异行数 = 0 → 证实其只能作辅助 ✅。

**结论**：t10 阶段 8 与 t11 的重建复核现已**零歧义**——"提交后哈希变化"可被证明为 VCS 戳所致，**且真实源码改动不可能蒙混通过**。交付物**不重编**（`c298235a…c068` 仍为部署并联调过的版本）。env-installer 报告附录 **F2（旧规则）/F4（修正）** 两段并存，与本文 §4.4/§4.5/§4.6 互相引用，保留了"判据被对抗测试证伪并替换"的过程。
