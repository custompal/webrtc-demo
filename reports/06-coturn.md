# 06 — coturn（STUN + TURN）部署与验证报告

- 任务：t6《宿主机部署并验证 coturn（STUN+TURN）》，尝试 1，attempt_id `12f4885c-78a9-4658-b81d-e2c4aad2a5f4`
- 责任人：coturn-installer
- 执行时间：2026-09-13 15:30–15:40 CST
- 宿主机：`root@172.21.0.219:5766`（hostname `iZj6cgbzeotp84twpfniy5Z`，Ubuntu 24.04.2 LTS）
- 依据：`code/webrtc-demo/doc/01-cloud-infra.md` §5（配置）、§9（放行清单）、§10（风险）

## 1. 结论（先看这里）

| 验收项 | 结果 |
|---|---|
| apt 安装 coturn 并启用 systemd | ✅ coturn 4.6.1-1build4，`systemctl is-enabled coturn` = **enabled** |
| `/etc/turnserver.conf` 按 doc/01 §5 写入 | ✅ 全文见 §3（含 2 处必要的实测修正，见 §4） |
| `systemctl enable --now coturn` → active(running) | ✅ `is-active` = **active**（PID 132354） |
| STUN Binding 测试返回映射地址 | ✅ `UDP reflexive addr: 47.238.144.66:35866`（原始输出见 §5.3） |
| TURN 中继分配（Allocate）真实成功 | ✅ 4 个 allocation 成功，relay 地址 `47.238.144.66:49162–49195`，ChannelBind 成功，8/8、12/12 数据包经中继往返，丢包 0%（见 §5.4） |
| 认证真实生效（负例） | ✅ 错误密码被拒：`ERROR: check_stun_auth: user demo credentials are incorrect`（§5.5） |
| 宿主机本机防火墙是否阻断 | ✅ 未阻断：`ufw` inactive，iptables/filter INPUT 策略 ACCEPT 且无 drop 规则（§7.1） |
| **公网入方向是否可达** | ❌ **阿里云安全组未放行**：UDP 3478、UDP 49152-49200、TCP 3478/5349/8443 在 EIP 入方向被丢弃（抓包实测证据见 §7.3）。本机回环（hairpin）对照实验证明这是**安全组**而非 coturn 或宿主机防火墙的问题 |

**当前状态**：coturn 已在宿主机上正确部署并可通过**内网地址**（172.21.0.219）完成 STUN/TURN 全流程；但**公网客户端（如 4G/其他网络的 Android 设备）在用户放行阿里云安全组之前无法使用本服务**。这不是 coturn 配置缺陷，已在 §7.4 给出需要用户执行的具体放行规则，并作为阻塞风险上报。

## 2. 实测网络参数（全部实测，非推测）

| 项 | 值 | 实测方式 |
|---|---|---|
| 公网 IP（EIP） | **47.238.144.66** | 阿里云元数据 `http://100.100.100.200/latest/meta-data/eipv4` + `https://ifconfig.me`（两者一致） |
| 内网 IP | **172.21.0.219/18** | `ip -4 addr show eth0` |
| 默认网关 | 172.21.63.253 | `ip route show default` |
| 宿主机发行版 | Ubuntu 24.04.2 LTS | `cat /etc/os-release` |
| coturn 版本 | 4.6.1-1build4 (amd64) | `dpkg -l coturn` |
| realm / server-name | webrtc-demo | 配置文件 |

> external-ip 映射复核：`turnutils_stunclient` 对**内网**监听的 3478 发起请求，返回的反射地址是 **47.238.144.66**，TURN 分配的 relay 地址也是 **47.238.144.66:4916x** —— 证明 `external-ip=47.238.144.66/172.21.0.219` 映射方向正确（doc/01 §10 提示的坑已避开）。

## 3. `/etc/turnserver.conf` 全文（宿主机实际生效文件）

```ini
# ===== webrtc-demo coturn configuration =====
# Spec: code/webrtc-demo/doc/01-cloud-infra.md section 5
# Public IP (measured): 47.238.144.66   Internal IP (measured): 172.21.0.219
listening-port=3478
listening-ip=172.21.0.219
external-ip=47.238.144.66/172.21.0.219
relay-ip=172.21.0.219
min-port=49152
max-port=49200
realm=webrtc-demo
server-name=webrtc-demo
use-fingerprint
lt-cred-mech
user=demo:demopass
total-quota=100
cert=/etc/turnserver/cert.pem
pkey=/etc/turnserver/pkey.pem
```

- 文件权限：`-rw-r----- root:turnserver /etc/turnserver.conf`（640）
- 原包自带配置已备份为 `/etc/turnserver.conf.orig-pkg`
- `/etc/default/coturn` 已写入 `TURNSERVER_ENABLED=1`（Ubuntu 包默认是注释状态；注意本次 `apt install` 时包的 postinst 已自动 enable+start，我用自写配置覆盖后 restart）
- 仓库内可复现副本：`code/webrtc-demo/deploy/turnserver.conf`（内容与宿主机一致）

## 4. 与 doc/01 §5 的差异（2 处，均为实测所迫）

1. **`relay-ip`：doc 写作 `<公网IP>`，实测必须写内网 IP。**
   按文档字面写 `relay-ip=47.238.144.66` 时，coturn 会尝试把中继 socket bind 到 EIP，内核直接报错：

   ```
   Syslog: turnserver[126554]: 52: : Trying to bind fd 61 to <47.238.144.66:49189>: errno=99
   turnserver[126554]: bind: Cannot assign requested address
   turnserver 客户端: 3: : error 508 (Cannot create socket)   # Allocate 失败
   ```
   （errno=99 = EADDRNOTAVAIL：EIP 不在本机网卡上，是 NAT 映射。）
   改为 `relay-ip=172.21.0.219` 后 Allocate 立即成功，且对外通告的 relay 地址仍是公网 IP（由 external-ip 映射负责）。**这是 doc/01 §10「external-ip 映射写错会导致 relay 地址异常」的具体化**；建议 architect 后续把 doc/01 §5 的 `relay-ip` 改成 `<内网IP>`。
2. **`cert`/`pkey`：doc 要求但未给生成步骤。** 我生成了 10 年有效期的自签证书（CN=webrtc-demo，SAN 含 47.238.144.66 / 172.21.0.219 / webrtc-demo），使 5349 TLS/DTLS 监听可用。自签证书**仅能用于本机自测**，公网客户端若要 `turns:` 需要 CA 签发的证书（本 demo 用 `turn:...?transport=udp`，不受影响）。

## 5. 验证证据（原始输出）

### 5.1 服务状态与监听

```
$ systemctl is-enabled coturn ; systemctl is-active coturn
enabled
active
$ systemctl status coturn | head -6
● coturn.service - coTURN STUN/TURN Server
     Loaded: loaded (/usr/lib/systemd/system/coturn.service; enabled; preset: enabled)
     Active: active (running) since Sun 2026-09-13 15:34:03 CST
   Main PID: 132354 (turnserver)
     CGroup: /system.slice/coturn.service
             └─132354 /usr/bin/turnserver -c /etc/turnserver.conf --pidfile=

$ ss -lunp | grep turnserver        # UDP
UNCONN 0  0  172.21.0.219:3478  0.0.0.0:*  users:(("turnserver",pid=132354,fd=43))
UNCONN 0  0  172.21.0.219:3478  0.0.0.0:*  users:(("turnserver",pid=132354,fd=40))
UNCONN 0  0  172.21.0.219:3478  0.0.0.0:*  users:(("turnserver",pid=132354,fd=38))
UNCONN 0  0  172.21.0.219:3478  0.0.0.0:*  users:(("turnserver",pid=132354,fd=36))
UNCONN 0  0  172.21.0.219:5349  0.0.0.0:*  users:(("turnserver",pid=132354,fd=47))
UNCONN 0  0  172.21.0.219:5349  0.0.0.0:*  users:(("turnserver",pid=132354,fd=46))
UNCONN 0  0  172.21.0.219:5349  0.0.0.0:*  users:(("turnserver",pid=132354,fd=45))
UNCONN 0  0  172.21.0.219:5349  0.0.0.0:*  users:(("turnserver",pid=132354,fd=44))
$ ss -lntp | grep turnserver        # TCP
LISTEN 0 1024 172.21.0.219:3478  ... (4 sockets)
LISTEN 0 1024 172.21.0.219:5349  ... (4 sockets)
```

### 5.2 服务端启动与认证日志（journald）

```
$ journalctl -u coturn -n 25 --no-pager
Sep 13 15:34:11 ... turnserver[132354]: 0: : turn server id=0 created
Sep 13 15:34:11 ... turnserver[132354]: 0: : turn server id=1 created
Sep 13 15:34:11 ... turnserver[132354]: 0: : turn server id=2 created
Sep 13 15:34:11 ... turnserver[132354]: 0: : turn server id=3 created
Sep 13 15:34:11 ... turnserver[132354]: 0: : Total General servers: 4
Sep 13 15:34:11 ... turnserver[132354]: 0: : SQLite DB connection success: /var/lib/turn/turndb
Sep 13 15:34:11 ... turnserver[132354]: 7: : ERROR: check_stun_auth: user demo credentials are incorrect   # ← 负例测试（错误密码）产生的日志
```

### 5.3 STUN Binding 测试（turnutils_stunclient）

```
$ turnutils_stunclient -p 3478 172.21.0.219
0: : IPv4. UDP reflexive addr: 47.238.144.66:35866
0: : IPv4. UDP reflexive addr: 47.238.144.66:35866
（exit 0）
```
→ 返回的映射地址是**公网 IP**，证明 NAT 映射与 external-ip 正确。

### 5.4 TURN 中继分配 + 中继数据往返（turnutils_uclient）

命令：`turnutils_uclient -v -y -u demo -w demopass -p 3478 -n 3 -m 2 172.21.0.219`
（`-y` = client-to-client 模式，两个客户端各自 Allocate，互为 peer，完整覆盖 Allocate→CreatePermission→ChannelBind→Send/Data 转发）

```
0: : allocate sent
0: : allocate response received: 
0: : success
0: : IPv4. Received relay addr: 47.238.144.66:49194
0: : refresh sent
0: : refresh response received: 
0: : success
... （共 4 个客户端实例，全部 allocate success）
0: : IPv4. Received relay addr: 47.238.144.66:49195
0: : IPv4. Received relay addr: 47.238.144.66:49178
0: : IPv4. Received relay addr: 47.238.144.66:49179
0: : IPv4. Received relay addr: 47.238.144.66:49188
0: : channel bind sent
0: : cb response received: 
0: : success: 0x577b
0: : success: 0x652f
0: : success: 0x7444
0: : success: 0x6fe5
5: : start_mclient: tot_send_msgs=12, tot_recv_msgs=12
5: : start_mclient: tot_send_bytes ~ 1200, tot_recv_bytes ~ 1200
5: : Total lost packets 0 (0.000000%), total send dropped 0 (0.000000%)
（exit 0）
```

第二次复跑（`-n 2 -m 2`）：

```
0: : IPv4. Received relay addr: 47.238.144.66:49164
0: : IPv4. Received relay addr: 47.238.144.66:49165
0: : IPv4. Received relay addr: 47.238.144.66:49162
0: : IPv4. Received relay addr: 47.238.144.66:49163
0: : IPv4. Received relay addr: 47.238.144.66:49174
7: : start_mclient: tot_send_msgs=8, tot_recv_msgs=8
7: : Total lost packets 0 (0.000000%), total send dropped 0 (0.000000%)
```
→ relay 端口全部落在 `49152-49200` 区间内，配置生效；中继数据真实往返，非仅在控制面成功。

### 5.5 负例控制（证明认证真的生效）

```
$ turnutils_uclient -v -y -u demo -w WRONGPASS -p 3478 -n 1 -m 1 172.21.0.219
0: : ERROR: Cannot complete Allocation        （exit 255）
服务端: ERROR: check_stun_auth: user demo credentials are incorrect
```

### 5.6 TLS/DTLS 监听（5349，附加项）

```
$ openssl s_client -connect 172.21.0.219:5349 -servername webrtc-demo </dev/null
CONNECTED(00000003)
subject=CN = webrtc-demo ; issuer=CN = webrtc-demo
New, TLSv1.2, Cipher is ECDHE-RSA-AES256-GCM-SHA384
Verify return code: 18 (self-signed certificate)
$ openssl x509 -in /etc/turnserver/cert.pem -noout -dates -ext subjectAltName
notBefore=Sep 13 07:30:58 2026 GMT ; notAfter=Sep 10 07:30:58 2036 GMT
X509v3 Subject Alternative Name: IP Address:47.238.144.66, IP Address:172.21.0.219, DNS:webrtc-demo
```

## 6. 客户端可用的 ICE server 配置

- 公网 IP：`47.238.144.66`
- 凭据：`demo` / `demopass`（长期凭据 `lt-cred-mech`，realm `webrtc-demo`）

```
stun:47.238.144.66:3478
turn:47.238.144.66:3478?transport=udp
turn:47.238.144.66:3478?transport=tcp
```
（`turns:47.238.144.66:5349?transport=tcp` 目前是自签证书，公网客户端需 CA 证书才能用，本 demo 不使用。）

⚠️ 上述公网 URL 在**用户放行阿里云安全组之前对公网客户端不可用**（见 §7）。仅内网可用形式：`stun:172.21.0.219:3478` / `turn:172.21.0.219:3478?transport=udp`。

供 Go 信令（t9/t12）下发的形态建议：

```json
{"urls":["stun:47.238.144.66:3478","turn:47.238.144.66:3478?transport=udp"],
 "username":"demo","credential":"demopass"}
```

## 7. 防火墙 / 安全组（实测，含对照实验）

### 7.1 宿主机本机防火墙：无阻断
```
$ ufw status
Status: inactive
$ iptables -S INPUT
-P INPUT ACCEPT            # 无任何 DROP/REJECT 规则
$ nft list ruleset | head     # 仅 Docker 相关 NAT（10453→容器 8443），无入方向过滤
```
安装 coturn 时 `apt install coturn` 之后宿主机的 3478/49152-49200/8443 均为空闲端口，coturn 成功 bind，也侧面证明本机无冲突与无本机层阻断。

### 7.2 用一个反例先证明「回环路径本身是通的」
从容器（172.18.0.2，与宿主机同机不同 netns）向 EIP 发起 TCP 连接：

```
TCP 47.238.144.66:5766  OK    # sshd（已放行）
TCP 47.238.144.66:5866  OK    # 1panel
TCP 47.238.144.66:10453 OK    # Docker DNAT→容器 8443
TCP 47.238.144.66:3478  FAIL  # coturn
TCP 47.238.144.66:5349  FAIL  # coturn TLS
```
→ EIP 的「hairpin 回环」对**已放行端口**是通的。

### 7.3 抓包证据（决定性）

宿主机 `eth0` 上抓包（`tcpdump -ni eth0 'port 3478 or port 5766'`），同时从容器发探测：

```
# 控制组：5766（已放行）——出去又回来，双向可见
15:33:38 eth0 Out IP 172.21.0.219.41686 > 47.238.144.66.5766: Flags [S]
15:33:38 eth0 In  IP 47.238.144.66.41686 > 172.21.0.219.5766: Flags [S.]
15:33:38 eth0 Out IP 172.21.0.219.5766 > 47.238.144.66.41686: Flags [S.]
... TCP 握手完成、SSH banner 正常交换

# 测试组：3478 —— 只有出，没有任何回包
15:33:30 eth0 Out IP 172.21.0.219.34695 > 47.238.144.66.3478: UDP, length 20
15:33:34 eth0 Out IP 172.21.0.219.38320 > 47.238.144.66.3478: Flags [S]   （TCP SYN 重传 4 次）
（3480 之后无任何 eth0 In 回包）
```

再用临时监听器验证 49152-49200 与 8443（抓包文件 `/tmp/sg-cap.txt`）：

```
宿主机本地自测（证明监听器本身正常）:
  UDP 172.21.0.219:49160 -> (b'ECHO:LOCAL-TEST', ('172.21.0.219', 49160))
  TCP 172.21.0.219:8443  -> 200
容器经 EIP 探测:
  UDP 47.238.144.66:49160 -> {"result":"NO-RESPONSE"}
  TCP 47.238.144.66:8443  -> {"result":"NO-RESPONSE"}
eth0 抓包结果（udp port 49160 or tcp port 8443）: 0 packets captured
```
→ 探测包**根本没有到达宿主机网卡**，被 EIP 前置的安全组丢弃。

### 7.4 结论与需要用户执行的操作（阻塞项）

**宿主机侧我已完成**：coturn 配置正确、服务常驻、本机 ufw/iptables 无阻断、监听正确。
**无法在本机自证的**：阿里云安全组入方向规则（本机无凭据，metadata 不暴露安全组，也未安装 aliyun CLI）。抓包证据表明以下端口被丢弃，**必须在阿里云控制台放行**：

| 方向 | 协议 | 端口 | 源 | 用途 |
|---|---|---|---|---|
| 入方向 | UDP | 3478 | 0.0.0.0/0 | STUN / TURN（demo 主路径） |
| 入方向 | UDP | 49152-49200 | 0.0.0.0/0 | TURN 中继端口段（必须，否则中继建立后无法收流） |
| 入方向 | TCP | 3478 | 0.0.0.0/0 | TURN over TCP（可选） |
| 入方向 | TCP | 8443 | 0.0.0.0/0 | Go 信令（t12 需要，已实测当前被丢弃） |
| 入方向 | UDP/TCP | 5349 | 0.0.0.0/0 | TURNS/DTLS（可选，需 CA 证书） |

放行后无需重启 coturn；`turnutils_stunclient` 从外部网络应能返回 `47.238.144.66:<port>`。

## 8. 复跑命令（供 verifier 独立复核）

在宿主机执行（约 1 分钟，全部只读/只测，不改变状态）：

```bash
systemctl is-active coturn
ss -lunp | grep 3478
turnutils_stunclient -p 3478 172.21.0.219
turnutils_uclient -v -y -u demo -w demopass -p 3478 -n 3 -m 2 172.21.0.219
turnutils_uclient -v -y -u demo -w WRONGPASS -p 3478 -n 1 -m 1 172.21.0.219   # 期望失败
journalctl -u coturn -n 20 --no-pager
```

外部可达性复核（放行安全组后才有意义，任意外网机器）：
`turnutils_stunclient -p 3478 47.238.144.66` → 期望返回 `47.238.144.66:<port>`；当前从本机/容器测均为超时（§7.3 已解释）。

## 9. 未决风险 / 交接

| # | 风险 | 影响 | 处置 |
|---|---|---|---|
| R1 | **阿里云安全组未放行 UDP 3478 / UDP 49152-49200** | Android 端在非同一网络下无法完成 ICE 中继，最终 Demo 端到端大概率失败 | 需用户在控制台放行（§7.4 表格）；已上报 captain |
| R2 | 安全组未放行 TCP 8443 | t12 部署后信令公网不可达 | 同上，需一并放行 |
| R3 | doc/01 §5 的 `relay-ip=<公网IP>` 是错的 | 照抄文档会导致 Allocate 508 | 已按实测修正为内网 IP；建议 architect 更新文档 |
| R4 | 自签证书 | `turns:` 不可用于客户端 | 本 demo 用 `turn:?transport=udp`，不受影响 |
| R5 | coturn 与 libwebrtc 编译同机（4 核） | 编译高峰可能影响中继时延 | demo 可接受；必要时错峰 |
| R6 | 诊断过程中安装了 `tcpdump`（宿主机） | 无功能影响 | 保留以便 verifier 复核，如不需要可 `apt purge tcpdump` |

## 10. 变更清单

宿主机：
- 安装包 `coturn 4.6.1-1build4`、`tcpdump`（诊断用）
- `/etc/turnserver.conf`（重写，原文件备份为 `/etc/turnserver.conf.orig-pkg`）
- `/etc/default/coturn`（`TURNSERVER_ENABLED=1`）
- `/etc/turnserver/cert.pem`、`/etc/turnserver/pkey.pem`（自签，10 年，640 turnserver:turnserver）
- `systemctl enable --now coturn`

仓库：
- `code/webrtc-demo/reports/06-coturn.md`（本文）
- `code/webrtc-demo/deploy/turnserver.conf`（宿主机配置的可复现副本）

---

## 附录 A：t12 复测更新（2026-09-13 16:00 CST，由 coturn-installer 追加；正文与原始结论保留不改）

t12 部署信令时对公网可达性做了复测（方法同 §7，从容器经 EIP 探测），结果与 §7.3/§7.4 当时的情况**不同**：

| 端口/协议 | t6 时（15:33） | t12 复测（15:57–16:00） |
|---|---|---|
| UDP 3478（STUN Binding） | 超时 | **可达**：`mapped=47.238.144.66:47460` |
| UDP 49152 / 49176 / 49200 | 不可达 | **可达**：三个端口均收到回包 |
| TCP 8443（信令） | 不可达 | **可达**：`curl http://47.238.144.66:8443/ws -> 400`，eth0 抓包可见完整握手往返 |
| TCP 5766（控制组） | 可达 | 可达 |
| TCP 3478 / TCP 5349 | 不可达 | 仍不可达（demo 用 UDP transport，不影响） |

期间宿主机本机侧无任何改动（`ufw` 始终 inactive、`iptables`/`nft` 规则未变），因此判定为**云安全组规则在 t6 报告之后被放行**（UDP 3478、UDP 49152-49200、TCP 8443）。

**放行主体已由 captain 于 2026-09-13 确认：用户本人在阿里云控制台操作**（captain 独立复测一致：`http://47.238.144.66:8443/healthz` → 200、`/ws` → 400、`turnutils_stunclient -p 3478 47.238.144.66` → `UDP reflexive addr: 47.238.144.66:53170`；TCP 3478 仍不可达）。证据链时间对照保持完整：**15:33（t6）不可达 → 16:0x（t12）可达**，中间宿主机无任何改动。

结论更新：
- §1 表格中「公网入方向是否可达 ❌」与 §7.4 的放行清单 **R1/R2 已解除**：coturn 与信令现在都能被公网客户端访问；
- 仍残留：TCP 3478 / TCP 5349 未放行（可选路径）；
- 端到端交叉验证见 `reports/12-deploy-signaling.md` §9.2，其中「用信令下发的 TURN 凭据 Allocate 成功（relay 47.238.144.66:49169）」证明公网 TURN 中继在真实网络路径上可用。

对 §7.3 的一句自我修正：当时读取 `tcpdump` 输出文件时可能尚未完全刷盘，因此「0 packets captured」不宜单独作为丢弃证据；不过当时探测端两次独立探测同样无响应，所以「当时被丢弃」的结论本身仍成立，只是现在有了更强的正面对照来定位原因（安全组）。
