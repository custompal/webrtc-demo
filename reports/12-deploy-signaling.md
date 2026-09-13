# 12 — Go 信令服务 systemd 部署与 coturn 联调验证报告

- 任务：t12《部署 Go 信令服务到宿主机 systemd 并与 coturn 联调验证》，尝试 1，attempt_id `e9fd3678-1e42-4267-9b7a-5db6999690da`
- 责任人：coturn-installer
- 执行时间：2026-09-13 15:54–16:01 CST
- 宿主机：`root@172.21.0.219:5766`（`iZj6cgbzeotp84twpfniy5Z`，Ubuntu 24.04.2）
- 依据：doc/12 §12、doc/13 阶段 2.1、doc/01 §6；前置 t9（二进制）与 t6（coturn 实测公网 IP 47.238.144.66）

## 1. 结论（验收项逐条）

| 验收要求 | 结果 | 证据 |
|---|---|---|
| `systemctl is-active signaling` = active | ✅ | `active`；`enabled`；MainPID 200623（§4.1） |
| TCP 8443 确实监听 | ✅ | `ss -lntp` → `*:8443 users:(("signaling",pid=200623,fd=7))`（§4.2） |
| `curl http://<CLOUD_IP>:8443/ws` 返回 WebSocket upgrade 响应（doc/13 阶段 2.1） | ✅ | 本机 400 + `WebSocket upgrade required (RFC 6455)`；经 EIP 亦 400（原样来自服务，§4.3） |
| 端到端信令流程通过（create→created→join→joined→peerJoined→offer/answer/ice/natType→leave→peerLeft） | ✅ | 两套独立实现均通过：仓库 Go 活体测试（§5.1）+ 自研 Node 客户端 19/19（§5.2、§5.3） |
| 下发的 stunUrl/turnUrl 与 coturn 实测公网地址一致 | ✅ | 均为 `stun:47.238.144.66:3478` / `turn:47.238.144.66:3478?transport=udp`（§6）；并用该凭据真实 Allocate 成功 |
| 日志文件与 journald 双通道均有输出 | ✅ | `/var/log/signaling/signaling.log`（21 KB，含 room_created/peer_joined/leave_received 等）与 `journalctl -u signaling` 同源同内容（§7） |
| `-log` 指定路径生效、Restart=always 常驻 | ✅ | kill -9 后 6 s 内自动重启（PID 186858 → 198694，仍 active）（§8） |
| 可复跑脚本 | ✅ | `code/webrtc-demo/scripts/deploy_signaling.sh`，已实测二次运行（幂等）成功（§3） |

## 2. 实测 IP 与 ICE 配置（沿用 t6 实测值，未猜）

- 公网 IP（EIP）**47.238.144.66**，内网 IP **172.21.0.219**（reports/06-coturn.md §2 实测）
- 下发配置：`stun:47.238.144.66:3478`、`turn:47.238.144.66:3478?transport=udp`、凭据 `demo` / `demopass`（realm `webrtc-demo`）

## 3. 部署过程（命令与结果）

```bash
# 1) t9 产物确认（sha256 与 t9 上报一致）
ls -l <WS>/code/webrtc-demo/signaling/dist/signaling-linux-amd64   # 5496984 B
sha256sum .../signaling-linux-amd64    # c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068
file .../signaling-linux-amd64         # ELF 64-bit LSB executable, x86-64, statically linked, stripped
/opt/signaling/signaling -version      # webrtcdemo-signaling 0.1.0 (go build)

# 2) 安装
mkdir -p /opt/signaling /var/log/signaling
install -m 0755 <WS>/code/webrtc-demo/signaling/dist/signaling-linux-amd64 /opt/signaling/signaling
sha256sum /opt/signaling/signaling     # 与源文件一致

# 3) unit + 启动
cat > /etc/systemd/system/signaling.service <<'EOF'
[Unit]
Description=WebRTC Demo Signaling Server
After=network.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/signaling
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn turn:47.238.144.66:3478?transport=udp -user demo:demopass -log /var/log/signaling/signaling.log
Restart=always
RestartSec=3
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now signaling
```

说明：
- `ExecStart` 按 doc/12 §12.2 的多行写法合并为单行（systemd 语义等价），**额外加了 `-log /var/log/signaling/signaling.log`**（本任务要求）与 `WorkingDirectory`、`LimitNOFILE`。
- 复跑脚本 `scripts/deploy_signaling.sh` 用同样内容生成 unit（可用 `PUBLIC_IP=`/`TURN_USER=` 等环境变量覆盖），并自带验收打印；已实测在宿主机上二次执行成功（幂等，输出见 §4.4）。

## 4. 服务状态与 HTTP 验收（原始输出）

### 4.1 systemctl

```
$ systemctl is-active signaling ; systemctl is-enabled signaling
active
enabled
$ systemctl status signaling | head -12
● signaling.service - WebRTC Demo Signaling Server
     Loaded: loaded (/etc/systemd/system/signaling.service; enabled; preset: enabled)
     Active: active (running) since Sun 2026-09-13 16:00:12 CST; 3s ago
   Main PID: 200623 (signaling)
      Tasks: 8 (limit: 8646)
     Memory: 1.4M (peak: 1.7M)
        CPU: 13ms
     CGroup: /system.slice/signaling.service
             └─200623 /opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn "turn:47.238.144.66:3478?transport=udp" -user demo:demopass -log /var/log/signaling/signaling.log
（ExecStart 与预期逐字一致；systemd 把含 ? 的 turn URL 用引号展示，属正常显示）
```

### 4.2 监听

```
$ ss -lntp | grep 8443
LISTEN 0 4096 *:8443 *:* users:(("signaling",pid=200623,fd=7))
```
（绑定 `[::]:8443`，IPv4/IPv6 双栈；日志 `listening actual_addr=[::]:8443 addr=:8443 ws_path=/ws health_path=/healthz`）

### 4.3 HTTP 端点（doc/13 阶段 2.1 验收）

```
$ curl -i http://127.0.0.1:8443/ws
HTTP/1.1 400 Bad Request
Content-Type: text/plain; charset=utf-8
Content-Length: 73

WebSocket upgrade required (RFC 6455). Connect to ws://127.0.0.1:8443/ws

$ curl -s http://127.0.0.1:8443/healthz
{
  "activeConns": 0, "addr": ":8443", "maxMessageBytes": 65536, "roomExpirySec": 1800,
  "roomIds": [], "rooms": 0, "roomsCreated": 1, "roomsDestroyed": 1,
  "serverTimeMillis": 1789286423073, "status": "ok",
  "stunUrl": "stun:47.238.144.66:3478",
  "turnUrl": "turn:47.238.144.66:3478?transport=udp",
  "totalConns": 2, "uptimeSec": 10, "version": "0.1.0"
}

$ curl -s -o /dev/null -w "EIP /ws -> %{http_code}\n" http://47.238.144.66:8443/ws
EIP /ws -> 400        # 经公网 IP 也可达，响应来自服务本身（不是被网络挡掉）
```
`/ws` 的 400 就是「要求 WebSocket 升级」的标准响应，符合 doc/13 阶段 2.1 的验收语义（服务日志同时记录 `ws_upgrade_rejected http_status=400 path=/ws remote=... user_agent=curl/8.5.0`）。

### 4.4 复跑脚本（幂等）

```
$ cd /opt/dsh-workspaces && bash code/webrtc-demo/scripts/deploy_signaling.sh
[deploy_signaling] 安装二进制: .../dist/signaling-linux-amd64 -> /opt/signaling/signaling
c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068  /opt/signaling/signaling
webrtcdemo-signaling 0.1.0 (go build)
[deploy_signaling] 写入 systemd unit: /etc/systemd/system/signaling.service
[deploy_signaling] systemctl is-active  = active
[deploy_signaling] systemctl is-enabled = enabled
LISTEN 0 4096 *:8443 *:* users:(("signaling",pid=200623,fd=7))
curl http://127.0.0.1:8443/ws -> 400 (期望 400 升级要求)
[deploy_signaling] 完成。ICE 配置: stun:47.238.144.66:3478 / turn:47.238.144.66:3478?transport=udp (demo:demopass)
script-exit=0
```

## 5. 端到端信令验证（两套独立实现）

### 5.1 仓库自带活体测试（Go，`ws://127.0.0.1:8443/ws`）

```
$ . <WS>/env-go.sh && cd <WS>/code/webrtc-demo/signaling
$ SIGNALING_WS_URL=ws://127.0.0.1:8443/ws go test ./server -run TestLive -v
=== RUN   TestLive_FullCallFlow
    [LIVE-HOST] == WebSocket 已连接 ws://127.0.0.1:8443/ws
    [LIVE-JOINER] == WebSocket 已连接 ws://127.0.0.1:8443/ws
    [LIVE-HOST] => {"type":"create"}
    [LIVE-HOST] <= {"type":"created","roomId":"XYR87W","stunUrl":"stun:47.238.144.66:3478","turnUrl":"turn:47.238.144.66:3478?transport=udp","turnUsername":"demo","turnCredential":"demopass"}
    [LIVE-JOINER] => {"type":"join","roomId":"XYR87W"}
    [LIVE-JOINER] <= {"type":"joined","roomId":"XYR87W","stunUrl":"stun:47.238.144.66:3478","turnUrl":"turn:47.238.144.66:3478?transport=udp","turnUsername":"demo","turnCredential":"demopass","peerId":"peer-002"}
    [LIVE-HOST] <= {"type":"peerJoined","peerId":"peer-002"}
    [LIVE-HOST] => {"type":"offer", ...}
    [LIVE-JOINER] <= {"type":"offer", ...}          # 原样转发
    [LIVE-JOINER] => {"type":"answer", ...}
    [LIVE-HOST] <= {"type":"answer", ...}           # 原样转发
    [LIVE-HOST] => {"type":"natType","natType":"FullCone"}
    [LIVE-JOINER] <= {"type":"natType","natType":"FullCone"}
    [LIVE-JOINER] => {"type":"ping","timestamp":1715432100000}
    [LIVE-JOINER] <= {"type":"pong","timestamp":1789286328659}
    [LIVE-HOST] => {"type":"ice","candidate":"candidate:1 1 udp 1 1.2.3.4 1 typ host","sdpMid":"0"}
    [LIVE-JOINER] <= {"type":"ice", ...}
    e2e_test.go:534: 房间号 XYR87W，joined.peerId=peer-002，活体流程全部通过
    [LIVE-HOST] => {"type":"leave"}
    [LIVE-JOINER] <= {"type":"peerLeft","peerId":"peer-001"}
    [LIVE-HOST] == 连接已关闭: websocket: close 1000 (normal)
--- PASS: TestLive_FullCallFlow (0.01s)
PASS
ok  webrtcdemo-signaling/server  0.015s
```

### 5.2 自研 Node 客户端（独立实现，走**公网 IP** `ws://47.238.144.66:8443/ws`）

完整原始输出保存在 `reports/12-e2e-raw.log`（45 行）。摘要（19/19 通过）：

```
[HOST]   == WebSocket connected ws://47.238.144.66:8443/ws
[JOINER] == WebSocket connected ws://47.238.144.66:8443/ws
[HOST]   <= {"type":"created","roomId":"RPR9QX","stunUrl":"stun:47.238.144.66:3478","turnUrl":"turn:47.238.144.66:3478?transport=udp","turnUsername":"demo","turnCredential":"demopass"}
PASS  created 含 6 位 roomId :: roomId=RPR9QX
PASS  created.stunUrl == coturn 实测地址 :: got=stun:47.238.144.66:3478
PASS  created.turnUrl == coturn 实测地址 :: got=turn:47.238.144.66:3478?transport=udp
PASS  created 含 TURN 凭据 :: demo/demopass
[JOINER] <= {"type":"joined",...,"peerId":"peer-002"}
PASS  joined.roomId 与 created 一致 :: RPR9QX
PASS  joined 含 peerId :: peerId=peer-002
PASS  joined.stunUrl/turnUrl 与 created 一致
[HOST]   <= {"type":"peerJoined","peerId":"peer-002"}
PASS  发起方收到 peerJoined 且 peerId 匹配 :: peer-002
PASS  offer 原样转发（字节一致）
PASS  answer 原样转发（字节一致）
PASS  ice A→B 原样转发 / PASS ice B→A 原样转发
PASS  natType A→B :: FullCone / PASS natType B→A :: PortRestrictedCone
PASS  ping → pong :: ts=1789286351901
[JOINER] <= {"type":"peerLeft","peerId":"peer-001"}
PASS  leave → 对端收到 peerLeft :: peerId=peer-001
PASS  leave 后发起方连接正常关闭(1000) :: __CLOSE__:1000
PASS  用信令下发的 TURN 凭据 Allocate 成功 :: relay=47.238.144.66:49169 mapped=47.238.144.66:47303 lifetime=600s
PASS  relay 地址为 coturn 公网 IP :: 47.238.144.66
=== 总计: 通过 19 项，失败 0 项 ===   (exit 0)
```

> 「用信令下发的 TURN 凭据 Allocate 成功」这一项把**信令 ↔ coturn 联调**闭环：客户端拿到的 `turnUrl/turnUsername/turnCredential` 真实可用，且分配到的 relay 地址是 coturn 实测公网 IP、端口落在 49152-49200 内。

三条路径全部复跑通过（脚本：`scripts/verify_signal_e2e.mjs`）：

| 入口 | 结果 | 说明 |
|---|---|---|
| `ws://127.0.0.1:8443/ws` | 19/19（Go 活体测试另计） | 服务本机 |
| `ws://172.21.0.219:8443/ws` | 19/19，exit 0 | 内网 |
| `ws://47.238.144.66:8443/ws` | 19/19，exit 0 | 公网 IP（SDK 客户端实际使用的形态） |

### 5.3 一次完整会话的原始 JSON（leave 之后重启前后均通过）

```
created  : {"type":"created","roomId":"HTBSWG","stunUrl":"stun:47.238.144.66:3478","turnUrl":"turn:47.238.144.66:3478?transport=udp","turnUsername":"demo","turnCredential":"demopass"}
joined   : {"type":"joined","roomId":"HTBSWG",...,"peerId":"peer-002"}
peerJoined:{"type":"peerJoined","peerId":"peer-002"}
peerLeft : {"type":"peerLeft","peerId":"peer-001"}
```

## 6. 下发的 STUN/TURN URL 与 coturn 实测地址一致性校验

| 来源 | stunUrl | turnUrl |
|---|---|---|
| coturn 实测（reports/06-coturn.md §2/§6） | `stun:47.238.144.66:3478` | `turn:47.238.144.66:3478?transport=udp` |
| 信令 `created` 下发 | `stun:47.238.144.66:3478` | `turn:47.238.144.66:3478?transport=udp` |
| 信令 `joined` 下发 | 同上 | 同上 |
| `/healthz` 暴露 | 同上 | 同上 |
| 用该配置实际 Allocate | — | relay = `47.238.144.66:49169/49160/49159`（全部 49152-49200） |

一致 ✅。注意：这里的 URL 之所以正确，是因为 systemd 启动参数用了 **t6 实测**的公网 IP；doc/01 §10 提示的 relay 地址异常坑（relay-ip 写公网 IP）已在 t6 中修正并记录。

## 7. 日志核验（文件 + journald 双通道）

- 日志文件绝对路径：**`/var/log/signaling/signaling.log`**（root:root 0644，2 MiB × 3 滚动由程序自实现）
- 启动即写入：`log_file_open log_file=/var/log/signaling/signaling.log max_bytes=2097152 max_files=3`

文件内容（节选，含完整生命周期事件）：

```
2026-09-13T07:54:55.999Z INFO    go     main      [186858/-] server_start listen_addr=:8443 log_file=/var/log/signaling/signaling.log log_level=info stun_url=stun:47.238.144.66:3478 turn_url=turn:47.238.144.66:3478?transport=udp turn_user=demo version=0.1.0 pid=186858
2026-09-13T07:54:56.001Z INFO    go     main      [186858/-] listening actual_addr=[::]:8443 addr=:8443 health_path=/healthz ws_path=/ws
2026-09-13T07:59:43.756Z INFO    go     room      [186858/-] room_created peer=peer-001 remote=47.238.144.66:59994 role=host room=HTBSWG room_count=1
2026-09-13T07:59:43.761Z INFO    go     room      [186858/-] peer_joined new_peer=peer-002 peer=peer-001 peers=2 remote=47.238.144.66:59994 room=HTBSWG user_agent=node
2026-09-13T07:59:43.782Z INFO    go     room      [186858/-] peer_left_sent left_peer=peer-001 peer=peer-002 remote=47.238.144.66:60006 room=HTBSWG user_agent=node
2026-09-13T08:00:18.601Z INFO    go     room      [200623/-] leave_received age_s=0 peer=peer-001 peers_after=1 remote=47.238.144.66:47632 room=EKXQJ5 room_destroyed=true user_agent=node
2026-09-13T08:00:18.601Z INFO    go     signaling [200623/-] ws_close active_conns=1 conn=1 duration_ms=62 peer=peer-001 reason=closed-by-server remote=47.238.144.66:47632
2026-09-13T08:00:18.628Z INFO    go     signaling [200623/-] ws_close active_conns=0 conn=2 duration_ms=63 peer=peer-002 reason=normal-close remote=47.238.144.66:47634
2026-09-13T08:00:23.062Z WARN    go     signaling [200623/-] ws_upgrade_rejected http_status=400 path=/ws remote=127.0.0.1:45316 user_agent=curl/8.5.0
```

journald 同源输出（`journalctl -u signaling`，同一事件同一行，仅前缀 systemd 时间戳/标识）：

```
Sep 13 16:00:18 iZj6cgbzeotp84twpfniy5Z signaling[200623]: 2026-09-13T08:00:18.601Z INFO    go     room      [200623/-] leave_received age_s=0 peer=peer-001 peers_after=1 remote=47.238.144.66:47632 room=EKXQJ5 room_destroyed=true user_agent=node
Sep 13 16:00:18 iZj6cgbzeotp84twpfniy5Z signaling[200623]: 2026-09-13T08:00:18.601Z INFO    go     signaling [200623/-] ws_close active_conns=1 conn=1 ... reason=closed-by-server remote=47.238.144.66:47632
```

→ 双通道均实际写入，且内容一致（文件 85 行时 journald 对应行完全匹配）。

### 7.1 日志滚动实测（systemd 场景，真实负载，2026-09-13 16:02–16:03）

契约 §9.2 的 2 MiB×3 滚动是**程序内实现**（与启动方式无关），但为确认在 systemd 下确实生效，做了一次压力实测：用 64 并发 keep-alive 连接向 `/ws` 发 30000 个非升级请求（每个产生 1 行 `ws_upgrade_rejected` WARN）。

```
BEFORE: -rw-r--r-- 1 root root    22180 /var/log/signaling/signaling.log        (32K 目录)
BURST : requests=30000 done=30000 failed=0 elapsed_ms=20544
AFTER : -rw-r--r-- 1 root root   418149 /var/log/signaling/signaling.log
        -rw-r--r-- 1 root root  2097018 /var/log/signaling/signaling.log.1
        -rw-r--r-- 1 root root  2097013 /var/log/signaling/signaling.log.2
        total: 4.5M；systemctl is-active signaling = active
        /dev/vda3 69G 34G 32G 52% /
```

→ 单文件在 ~2 MiB（2097018 / 2097013 B）处滚动，保留 3 个文件（`.log` + `.log.1` + `.log.2`），总量被限制在 ≲6 MiB，服务全程 active。滚动在 systemd 场景下确实生效。

两个历史文件**恰好停在 2 MiB（2097152 B）之下**（差 134 B / 139 B）、保留数正好 3 个，说明滚动阈值与保留策略按契约执行；该生产负载证据已被 go-dev 收录进 `reports/09-go-signaling.md` §10.3（其滚动写入器此前只有小上限单元测试覆盖）。

## 8. 常驻性验证（Restart=always）

```
$ OLD=$(systemctl show signaling -p MainPID --value); echo old=$OLD   # 186858
$ kill -9 186858 ; sleep 6
$ systemctl show signaling -p MainPID --value                        # 198694
$ systemctl is-active signaling                                      # active
$ tail -3 /var/log/signaling/signaling.log
2026-09-13T07:59:54.810Z INFO go main [198694/-] log_file_open log_file=/var/log/signaling/signaling.log ...
2026-09-13T07:59:54.812Z INFO go main [198694/-] listening actual_addr=[::]:8443 ...
```
被强杀后 systemd 6 s 内自动拉起（新 PID），日志文件继续追加，重启后再次跑 E2E 仍 19/19（§5.2 的 final 轮即重启后的实例）。

## 9. 防火墙 / 安全组（复测，更新 t6 结论）

### 9.1 本机层
`ufw status` = inactive；`iptables -S INPUT` 策略 ACCEPT 且无 drop 规则；nft 仅有 Docker 相关 NAT（10453→容器 8443）与空 1PANEL 链。→ 宿主机本机不阻断 8443。

### 9.2 公网入方向复测（与 t6 当时的结论相比有变化）

| 探测（从容器经 EIP，模拟外部客户端） | t6 时（15:33） | t12 复测（15:57~16:00） |
|---|---|---|
| TCP 5766（sshd 控制组） | OK | OK |
| TCP 8443（信令） | FAIL（当时用临时 python 监听器） | **OK**（`curl EIP/ws -> 400`；eth0 抓包可见完整握手往返） |
| UDP 3478（STUN Binding） | 超时 | **OK**（`mapped=47.238.144.66:47460`） |
| UDP 49152 / 49176 / 49200（临时 echo 监听器） | 未测/不可达 | **OK**（三者均收到 `ECHOP49152/49176/49200` 回包） |
| TCP 3478 / TCP 5349 | FAIL | 仍 FAIL（demo 不用 TCP transport，不影响） |

eth0 抓包（t12）证明 8443 的往返真实经过公网 NAT：

```
15:57:55 IP 172.21.0.219.35398 > 47.238.144.66.8443: Flags [S]
15:57:55 IP 47.238.144.66.35398 > 172.21.0.219.8443: Flags [S]      # 回程 DNAT 到内网 IP
15:57:55 IP 172.21.0.219.8443 > 47.238.144.66.35398: Flags [S.]
...（SYN/SYN-ACK/HTTP 请求响应/双向 FIN 完整）
```

**判定**：安全组在 t6 报告（15:41 提交）之后被放行了 **UDP 3478、UDP 49152-49200、TCP 8443**；本机侧期间无任何改动（ufw/iptables/nft 未变），因此判定为控制台侧规则变更（请 captain/用户确认是否为本人操作）。t6 报告 §7 的 R1/R2 阻塞风险就此**解除**（TCP 3478/5349 未放行属于可选路径残留）。

## 10. 失败与重试记录（按时间顺序，真实记录）

| # | 现象 | 定位 | 处置 |
|---|---|---|---|
| 1 | 自研 Node TURN Allocate 首次返回 401（STUN error code 1025） | 我把 MESSAGE-INTEGRITY 属性头也计入 HMAC 输入；coturn 按 RFC 5389 §15.4 的字面约定：HMAC 覆盖到 MI 之前（长度字段含 MI 的 24 字节，但输入不含 MI 属性头）。实验对比 A/B 两种算法，A→401、B→成功 | 改为 B（`withIntegrity` 已注释说明），复跑 19/19 通过。**同一问题 1 次重试即修复** |
| 2 | t6 时对 UDP 49160 / TCP 8443 的 EIP 探测「0 包到达 eth0」 | 当时同时存在两条相互印证的证据（探测端无响应 + 抓包无包），但安全组规则在 t6 后发生变化，故该结论已过时 | 本次复测（§9.2）更新结论，并已向 captain 说明 t6 报告 §7 需按最新实测理解 |
| 3 | 8443 一度被临时 python 监听器占用做探测 | 探测结束后按 PID 精确 kill，未用 `pkill -f`（此前 `pkill -f` 误杀了自己的 SSH 会话，已改用端口→PID 方式） | 已清理，`ss` 确认端口释放后再部署正式服务 |

## 11. 未决风险 / 交接

| # | 风险 | 影响 | 建议 |
|---|---|---|---|
| R1 | TCP 3478 / TCP 5349 仍被安全组丢弃 | 若 Android 端强制 `transport=tcp` 或需要 `turns:`，会失败 | demo 用 `?transport=udp`，可不处理；如需 TCP，请用户在安全组加 TCP 3478/5349 |
| R2 | 服务以 root 运行（照 doc/12 §12.2 未加 `User=`） | 安全加固项（非功能缺陷） | **本轮决定：记录不修**（captain 2026-09-13 裁定——改 systemd user 会牵连 `/var/log/signaling` 属主与 `-log` 路径权限，demo 阶段引入变数不划算）。后续硬化建议（三处一起改）：<br>① `useradd --system --no-create-home --shell /usr/sbin/nologin signaling`；<br>② unit 增加 `User=signaling` / `Group=signaling` **与 `LogsDirectory=signaling`**（systemd 会创建 `/var/log/signaling` 并 chown 给该用户，正好覆盖当前 `-log` 路径；若不改路径也可用 `LogsDirectory=` 替代手工 `mkdir`+`chown`）；<br>③ `chown -R signaling:signaling /opt/signaling`，随后 `daemon-reload` + `restart`，并复跑 `scripts/deploy_signaling.sh` 与 E2E 冒烟 |
| R3 | `/var/log/signaling/signaling.log` 无 logrotate 接管（程序自实现 2 MiB×3 滚动） | 长期运行最大占用 ~6 MiB，可控 | 本轮**不修**（滚动已在真实负载下实测，见 §7.1）；如需可加 logrotate 或交由 journald |
| R4 | 凭据 `demo/demopass` 为文档示例值（服务启动即打印 WARN） | 公网暴露时任何人可用该 TURN 中继 | 本轮**保留**（demo 阶段已知可接受）；正式使用请通过 `-user` 更换并与 coturn `user=` 同步 |
| R5 | 无 TLS（ws:// 明文，无 Caddy 443 反代） | 明文信令 | 本轮**记录不修**，与契约 §8.1 冻结的 `ws://47.238.144.66:8443/ws` 一致；doc/12 §12.3 的 Caddy 443 为可选项，无域名时不阻塞 |
| R6 | ~~日志路径口径差异~~ → **已由契约 C31 消解（无需任何改动）** | — | architect 已裁定「**以部署事实为准**」并冻结为 **C31**（`doc/14-interface-contract.md` 终版 1279 行 / sha256 `d2a90bebd8a7a58a1d9a8fe8ac650b8f97f95f90d60b7b3dc4178337ad45b996`）：§3.2 部署行与 §9.2 Go 行现值均为 **`/var/log/signaling/signaling.log`**（+`.1`/`.2`），旧值 `/opt/signaling/logs/…` 标记为**作废（非偏差）**，verifier 不得据此判失败；**新增 V53 附加条款**要求 unit 的 `ExecStart` 含 `-log /var/log/signaling/signaling.log`。本部署 unit 第 9 行逐字满足：<br>`9:ExecStart=/opt/signaling/signaling ... -user demo:demopass -log /var/log/signaling/signaling.log`<br>（`grep -n '\-log' /etc/systemd/system/signaling.service`；仓库副本 `deploy/signaling.service` 字节相同）。**未改 unit、未重跑验收** |

### 11.1 日志路径「与路径无关」的旁路实测（C31 已裁定无需切换，此项留作补充证据）

裁定出来前，为确认万一需要切换的零风险性，另起一个**临时实例**（端口 18443，独立进程，不动 systemd 服务）验证备用路径：

```
$ rm -rf /opt/signaling/logs
$ /opt/signaling/signaling -addr :18443 ... -log /opt/signaling/logs/switch-test.log   # 后台
$ ls -l /opt/signaling/logs/
-rw-r--r-- 1 root root 1005 switch-test.log              # 目录自动创建成功
$ head -1 /opt/signaling/logs/switch-test.log
2026-09-13T08:04:43.126Z INFO go main [203612/-] log_file_open log_file=/opt/signaling/logs/switch-test.log max_bytes=2097152 max_files=3
$ systemctl is-active signaling        # 正式服务未受影响
active          # pid 200623 仍在 *:8443；临时实例 pid 203612 在 :18443
```
验证后已 kill 临时实例并删除测试日志（`/opt/signaling/logs/` 目录保留为空目录）。
→ 结论（与 C31 表述一致）：日志路径完全由 `-log` 参数决定、目录自动创建，**滚动与双写与路径无关**；正式部署按契约取 `/var/log/signaling/signaling.log`，无需切换（历史回退命令：`LOG_FILE=/opt/signaling/logs/signaling.log bash code/webrtc-demo/scripts/deploy_signaling.sh`，**不必执行**）。

## 12. 交叉复核记录

- **go-dev（t9 作者）对本部署做了只读交叉复核**（2026-09-13 16:0x）：`/opt/signaling/signaling` 的 sha256 `c298235a…c068` 与其交付物逐字节一致；`is-active/enabled` 正常；`WorkingDirectory=/opt/signaling` + `Restart=always/RestartSec=3`；端点 `healthz=200 / ws=400 / signal=404`（C11 落地）。结论一致、无异议。
- 本报告 §5.2 的独立 Node 客户端 19/19 + TURN 真实 Allocate 被 go-dev 采纳为 t9 的**第三方独立验证**证据，记录在 `reports/09-go-signaling.md` §10.2（并标注出处为本报告）。
- go-dev 另把我这里 §7.1 的滚动实测数据收录进 `reports/09-go-signaling.md` §10.3，作为其滚动写入器在**真实负载**（而非仅小上限单元测试）下的验证证据（出处标注为本部署）。
- **契约裁定 C31 已核**（我不依赖转述，直接在冻结文档上复核）：`doc/14-interface-contract.md` 1279 行、sha256 `d2a90bebd8a7a58a1d9a8fe8ac650b8f97f95f90d60b7b3dc4178337ad45b996`；§3.2 第 202 行、§9.2 第 909 行现值均为 `/var/log/signaling/signaling.log`，第 1058 行 C31 写明「以部署事实为准、旧值作废（非偏差）、verifier 不得据此判失败；可核 `grep -n '\-log' /etc/systemd/system/signaling.service`」，第 1230 行 V53 追加了同一条附加验收。本部署满足：`grep -n '\-log'` → `9:ExecStart=/opt/signaling/signaling ... -log /var/log/signaling/signaling.log`。

## 13. 变更清单与复跑命令

宿主机新增/修改：
- `/opt/signaling/signaling`（0755，sha256 c298235a…c068，来自 t9 产物）
- `/etc/systemd/system/signaling.service`（内容见 §3）
- `/var/log/signaling/signaling.log`（由服务写入）
- `/opt/signaling/logs/`（§11.1 旁路验证 R6 备用日志路径时创建，现为空目录，无副作用）
- `systemctl enable --now signaling`（multi-user.target.wants 软链）
- 诊断用临时文件 `/tmp/udpecho*.py`、`/tmp/*-cap.txt`（不影响服务）

仓库新增：
- `code/webrtc-demo/scripts/deploy_signaling.sh`（可复跑部署脚本）
- `code/webrtc-demo/scripts/verify_signal_e2e.mjs`（独立 E2E 验证客户端，零依赖，Node 22+ 内置 WebSocket）
- `code/webrtc-demo/reports/12-deploy-signaling.md`（本文）
- `code/webrtc-demo/reports/12-e2e-raw.log`（公网 E2E 原始输出）
- `code/webrtc-demo/reports/06-coturn.md`（追加「附录 A：t12 复测更新」，不修改原结论）
- `code/webrtc-demo/deploy/signaling.service`、`deploy/coturn.service`、`deploy/coturn.default`、`deploy/README.md`
  （宿主机**实际生效**的 systemd unit 与 `/etc/default/coturn` 副本，用 `systemctl cat` 采集；两服务均无 drop-in。
  采集时间 2026-09-13 16:05；coturn unit 来自包 `coturn 4.6.1-1build4`，md5 `3c0efb525dd3c43941fb451e30cc1bd9`）

复跑（宿主机）：
```bash
systemctl is-active coturn signaling
ss -lntp | grep 8443
curl -i http://127.0.0.1:8443/ws          # 期望 400 + WebSocket upgrade required
curl -s http://127.0.0.1:8443/healthz     # 期望 status=ok 且 stunUrl/turnUrl 为 47.238.144.66
tail -20 /var/log/signaling/signaling.log
journalctl -u signaling -n 20 --no-pager
bash code/webrtc-demo/scripts/deploy_signaling.sh     # 幂等重部署
```
复跑（容器内，独立 E2E）：
```bash
node code/webrtc-demo/scripts/verify_signal_e2e.mjs ws://47.238.144.66:8443/ws
```
