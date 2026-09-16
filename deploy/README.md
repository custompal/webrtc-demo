# deploy/ —— 宿主机部署产物的可复现副本（t6 / t12；t79 与现网重新对齐）

本目录把宿主机上**实际生效**的部署文件落库，目的是让「部署可复现」成为可验收证据（而不是只写在报告正文里）。

> **t79 更新（重要）**：`signaling.service` 原先漏了 `-room-grace 90s`（t70 部署时加到宿主机 unit，仓库副本没跟上），
> 属独立的部署不可复现缺陷（t78 记为 **D-5**）。现已在**有效指令层面**与宿主机逐行对齐并补上该 flag；
> 仓库副本另外增加了 `#` 说明性注释（含「`-room-grace` 不可省略」的理由），因此**原始 md5 与宿主机不同、有效指令相同**——
> 这与 `turnserver.conf` 的处理方式一致。核对方法见 §6。

```
# 有效指令（去注释/空行）逐行对照：无输出 = 一致
$ diff <(ssh … 'cat /etc/systemd/system/signaling.service; exit' | grep -vE '^\s*(#|$)') \
       <(grep -vE '^\s*(#|$)' deploy/signaling.service)
$ # ExecStart 整行逐字节对照：一致
$ ssh … 'grep "^ExecStart" /etc/systemd/system/signaling.service; exit'
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn turn:47.238.144.66:3478?transport=udp -user demo:demopass -log /var/log/signaling/signaling.log -room-grace 90s
$ grep "^ExecStart" deploy/signaling.service
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn turn:47.238.144.66:3478?transport=udp -user demo:demopass -log /var/log/signaling/signaling.log -room-grace 90s

$ md5sum /usr/lib/systemd/system/coturn.service   deploy/coturn.service   # 字节级一致
3c0efb525dd3c43941fb451e30cc1bd9  /usr/lib/systemd/system/coturn.service
3c0efb525dd3c43941fb451e30cc1bd9  deploy/coturn.service
$ md5sum /etc/default/coturn                      deploy/coturn.default   # 字节级一致
625edc7c88847746661568e1ae6dfaa5  /etc/default/coturn
625edc7c88847746661568e1ae6dfaa5  deploy/coturn.default
$ find . -name "*.service"        # 供 V53(a) 使用
./deploy/signaling.service
./deploy/coturn.service
```

| 仓库文件 | 宿主机来源路径 | 指纹 | 说明 | 采集时间 (CST) |
|---|---|---|---|---|
| `signaling.service` | `/etc/systemd/system/signaling.service` | 宿主 `md5 3c4e9e731790f8e4a86486c789ebc390` / `sha256 93123554…6ed71b`（431 B, 0644 root:root）· 仓库副本含 `#` 注释故原始 md5 不同（**有效指令逐行相同**，见 §6） | 信令服务 unit（t12 创建 → t70 追加 `-room-grace 90s` → **t79 仓库对齐**） | 2026-09-16 01:5x |
| `coturn.service` | `/usr/lib/systemd/system/coturn.service` | `3c0efb525dd3c43941fb451e30cc1bd9` | coturn 发行版包自带 unit（**未修改**，来自 `coturn 4.6.1-1build4`，字节级拷贝） | 2026-09-13 16:05 |
| `coturn.default` | `/etc/default/coturn` | `625edc7c88847746661568e1ae6dfaa5` | 包自带内容 + `TURNSERVER_ENABLED=1`（t6 用 sed 覆盖，字节级拷贝） | 2026-09-13 16:05 |
| `turnserver.conf` | `/etc/turnserver.conf` | 宿主机 `35ae6301c257e2ab374171905ff0973e` / 本副本 `d954436133cbb29580458ebca7c0cbb9` | coturn 主配置（t6 重写，原文备份 `/etc/turnserver.conf.orig-pkg`）。本副本多 4 行 `#` 说明性注释（含 relay-ip 修正说明），**有效指令逐行相同**（`diff <(grep -v '^#' host) <(grep -v '^#' repo)` 无差异，见 reports/06-coturn.md §3/§4） | 2026-09-13 15:34 |

## 1. 生效状态与来源（实测）

```
$ systemctl cat signaling          # 只有一份文件，无 drop-in
# /etc/systemd/system/signaling.service
...
$ ls /etc/systemd/system/signaling.service.d/     # No such file or directory

$ systemctl cat coturn             # 只有包自带文件，无 drop-in
# /usr/lib/systemd/system/coturn.service
...
$ ls /etc/systemd/system/coturn.service.d/        # No such file or directory
$ dpkg -S /usr/lib/systemd/system/coturn.service
coturn: /usr/lib/systemd/system/coturn.service
$ dpkg -l coturn | tail -1
ii  coturn  4.6.1-1build4  amd64  TURN and STUN server for VoIP
$ md5sum /usr/lib/systemd/system/coturn.service
3c0efb525dd3c43941fb451e30cc1bd9  /usr/lib/systemd/system/coturn.service

$ ls -l /etc/systemd/system/multi-user.target.wants/ | grep -E 'coturn|signaling'
coturn.service    -> /usr/lib/systemd/system/coturn.service
signaling.service -> /etc/systemd/system/signaling.service
```

- **两个服务都没有 drop-in（`*.d/` 目录不存在）**，因此本目录里的 `*.service` 就是完整生效内容。
- coturn 的 unit 来自发行版包，位于 `/usr/lib/systemd/system/`；我们只改了 `/etc/turnserver.conf`（主配置）与 `/etc/default/coturn`（启用开关），**没有覆盖 unit 本身**。
- coturn 的「自启」是通过 `systemctl enable coturn` 在 `/etc/systemd/system/multi-user.target.wants/` 建立软链实现的（`enable` 时 systemd 提示 `Synchronizing state of coturn.service with SysV service script`，因为包里同时存在 `/etc/init.d/coturn`）。
- 注意：原生 unit **不读取** `/etc/default/coturn`；该文件由 `/etc/init.d/coturn`（第 103 行 `if test "$TURNSERVER_ENABLED" = 1`）读取。systemd 场景下真正决定启动的是软链 + unit。

## 2. 如何从本目录复现部署

```bash
# 信令服务（t12）
install -m 0644 code/webrtc-demo/deploy/signaling.service /etc/systemd/system/signaling.service
install -m 0755 code/webrtc-demo/signaling/dist/signaling-linux-amd64 /opt/signaling/signaling
mkdir -p /var/log/signaling
systemctl daemon-reload && systemctl enable --now signaling
# 或直接跑幂等脚本（推荐，自带验收打印）：
bash code/webrtc-demo/scripts/deploy_signaling.sh

# coturn（t6）
apt-get install -y coturn
install -m 0640 code/webrtc-demo/deploy/turnserver.conf /etc/turnserver.conf
install -m 0644 code/webrtc-demo/deploy/coturn.default  /etc/default/coturn
# 如需在非 Ubuntu 24.04 上重建 unit，可用包自带版本覆盖（本仓库副本与包内一致）：
#   install -m 0644 code/webrtc-demo/deploy/coturn.service /etc/systemd/system/coturn.service
# 证书（自签，SAN 含公网/内网 IP）与 systemd 启停见 reports/06-coturn.md §3
systemctl daemon-reload && systemctl enable --now coturn
```

## 3. 与报告/契约的对应关系

- `reports/06-coturn.md`：coturn 配置全文、STUN/TURN 原始验证输出、安全组实测（附录 A 为 t12 复测更新）。
- `reports/12-deploy-signaling.md`：信令 systemd unit、端到端信令验证、日志双通道、失败与重试记录。
- `reports/12-e2e-raw.log`：公网 EIP 上的端到端信令原始输出。
- `scripts/deploy_signaling.sh`、`scripts/verify_signal_e2e.mjs`：可复跑部署与验证脚本。

## 4. 已知差异/注意点

1. **信令日志路径**：unit 使用 `-log /var/log/signaling/signaling.log`。该取值已被契约 **C31**（`doc/14-interface-contract.md` §3.2/§9.2/C31/V53）冻结为「以部署事实为准」，旧值 `/opt/signaling/logs/signaling.log` 作废；V53 附加验收即核 `grep -n '\-log' /etc/systemd/system/signaling.service`。**无需切换**；若确需换路径，改 `scripts/deploy_signaling.sh` 的 `LOG_FILE` 变量即可（脚本会重写 unit 并重启，路径与滚动/双写无关）。
2. `signaling.service` 里的 `-stun/-turn` 使用 **t6 实测**公网 IP `47.238.144.66`；换机器/换 IP 时请用 `PUBLIC_IP=<新IP> bash scripts/deploy_signaling.sh` 重新生成。
3. 磁盘占用：信令日志单文件上限 2 MiB、最多 3 个（`log_file_open max_bytes=2097152 max_files=3`，程序内滚动），总量 ≲6 MiB。

## 5. coturn 对等端地址策略与诊断探针（t58，2026-09-15）

`turnserver.conf` 已按 t58 更新（与宿主机 `/etc/turnserver.conf` 逐字节一致）：

- **显式固化拒绝**：`denied-peer-ip=0.0.0.0-0.255.255.255`、`denied-peer-ip=127.0.0.0-127.255.255.255`（= coturn 内建默认；**不要**放行 loopback，否则中继可打本机 `127.0.0.1:8080/5766/8443` = SSRF）。
- **显式放行**：`allowed-peer-ip` 覆盖 `10/8`、`172.16/12`、`192.168/16`、`100.64/10`(CGNAT)、`169.254/16`(link-local)——4G↔WiFi 场景真正需要的对等地址段。
- **配额/生命周期**：`total-quota=45`（对齐 49 个 relay 端口）、`user-quota=8`、`max-allocate-lifetime=600`。
- **指纹**：`use-fingerprint` 在 coturn 4.6.1 中是**无效写法**（启动告警 `Bad configuration format`），已改为 `fingerprint`（实测不强制客户端携带 FINGERPRINT）。

诊断探针（零依赖，容器侧运行；宿主机无 node）：

```bash
node code/webrtc-demo/deploy/turnperm_probe.mjs <turnHost> <port> <user> <pass> <peerIP...>
# 例：node … 172.21.0.219 3478 demo demopass 0.0.0.0 127.0.0.1 10.0.0.5 192.168.1.101 8.8.8.8
# 输出：每个 peer 的 SUCCESS / ERROR(code) 与地址类别；403=被 coturn 拒绝
```

背景与完整证据见 `reports/28-turn-permission-403.md`（403 只针对 `0.0.0.0/8` 与 `127.0.0.0/8`；私网/CGNAT/公网对等地址一律放行；`local_relay=0` 归属客户端 15 s 看门狗，见该报告 §6）。

## 6. 与现网 unit 的一致性核对 + 回滚（t79；修 D-5）

### 6.1 为什么要核

`deploy/signaling.service` 是「照仓库即可复现现网部署」的唯一凭据。t78 的 **D-5** 发现：t70 部署时给宿主机 unit 追加了 `-room-grace 90s`，但**仓库副本没跟上** ⇒ 若有人照仓库 unit 重新部署，部署形态与已验收的现网不一致（`-room-grace` 是 t67 房间宽限期修复的行为开关，详见下一节）。已对齐。

### 6.2 核对命令（一条 ssh + 一条 diff）

```bash
cd /data/dsh/home/workspace/code/webrtc-demo

# ① 取宿主机现网 unit 原文（只读；systemctl cat 会带 "# /etc/..." 头，故用 cat 取纯文件）
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -i /home/node/.ssh/id_ed25519 root@172.21.0.219 -p 5766 \
    'cat /etc/systemd/system/signaling.service; exit'

# ② 有效指令逐行对照（忽略仓库侧的 `#` 说明注释）：无输出 = 一致
diff <(ssh ... 'cat /etc/systemd/system/signaling.service; exit' | grep -vE '^\s*(#|$)') \
     <(grep -vE '^\s*(#|$)' deploy/signaling.service) && echo "OK: 有效指令一致"

# ③ ExecStart 整行逐字节对照（最关键的一行）：
ssh ... 'grep "^ExecStart" /etc/systemd/system/signaling.service; exit'
grep "^ExecStart" deploy/signaling.service

# ④ 运行实例的实际参数（比 unit 更硬的一手证据：进程真在用什么参数）
ssh ... 'tr "\0" " " < /proc/$(systemctl show -p MainPID --value signaling)/cmdline; echo; exit'

# ⑤ 生效值自证（服务自称的宽限期，可与 ④ 交叉）
curl -s http://47.238.144.66:8443/healthz | grep roomGraceSec    # 期望 "roomGraceSec": 90
```

> 提示：unit 若被改成含 `#` 注释（本仓库副本即如此），**原始 `md5sum`/`diff` 会报差异**，这是预期的——
> 用 ② 的方式比较"有效指令"。若要求原始字节一致，可把注释挪到本 README（但这种注释正是"别删这个 flag"的现场提醒，故保留）。

### 6.3 ExecStart 参数说明（逐项）

| 参数 | 现值 | 说明 / 是否可省 |
|---|---|---|
| `-addr` | `:8443` | 监听端口；Caddy/安全组按此放行。**显式**（默认值相同，但端口属部署事实） |
| `-stun` | `stun:47.238.144.66:3478` | 下发给客户端的 STUN（t6 实测公网 IP）。换机/换 IP 必须改 |
| `-turn` | `turn:47.238.144.66:3478?transport=udp` | 同上（`transport=udp`；TCP 3478 未放行） |
| `-user` | `demo:demopass` | TURN 凭据（全局共享，`用户名:密码`） |
| `-log` | `/var/log/signaling/signaling.log` | 日志文件；**契约 C31 冻结为部署事实**，V53 据此核对 |
| **`-room-grace`** | **`90s`** | **t67 行为开关，不可省略**（见 6.4）。显式写入 ⇒ 不依赖二进制默认值 |
| `-log-level` | *未写* | 有意省略：二进制默认 `info`（`config.go`），现网日志实测即 info；需要 debug 时临时加 |
| `-room-expiry` | *未写* | 有意省略：默认 `1800s`（30 分钟无人加入即销毁），与契约一致，无部署差异 |
| `-pong-wait` | *未写* | 有意省略：默认 `45s`（=3×15s 心跳）；调小会提高弱网误判风险，需与客户端预算一起评估 |
| `-max-message-bytes` / `-write-timeout` / `-send-timeout` | *未写* | 有意省略：默认值即 doc/09 §1 与设计值（64KB / 10s / 5s），无部署差异 |

> 原则：**只把"部署事实"与"行为开关"写进 unit**；纯默认值参数保持省略，避免同一取值两处维护而漂移。
> `-room-grace` 属前者（t70 已写进现网、且它决定 t67 修复是否生效），因此必须显式。

### 6.4 为什么 `-room-grace 90s` 不可省略（论证与实测）

- **语义**：WS 瞬断后**保留房间与席位 90 s**——期间不回收房间、不给在线对端发 `peerLeft`，断开者可用原 `roomId` 同身份重连（回 `joined`、拿回原 `peerId`）；宽限期满仍未重连才回收席位并只发一次 `peerLeft`。实现见 `signaling/room/manager.go`（`MarkOffline`/`expireGrace`）与 `reports/35-room-grace.md`。
- **显式写入 ⇒ 行为不随二进制默认值漂移**：当前二进制默认值也是 90 s（`config.DefaultRoomGrace`），但省略该 flag 时**行为由二进制决定**；写进 unit 后，即便将来默认值被改动，部署形态仍钉在 90 s。（实测：同版本二进制带/不带该 flag，`/healthz` 均为 `roomGraceSec: 90`。）
- **若部署 t67 之前的二进制**：该二进制不认识此 flag，systemd 启动即失败（`flag provided but not defined: -room-grace`，退出码 2，配合 `Restart=always` 表现为反复重启失败）——**这是"响亮的失败"**；而如果那时 unit 里也**没有**这个 flag，就会**静默退回旧行为**（一人掉线即销毁房间 + 立即 `peerLeft`，即 t67 修掉的真机缺陷）。两者对比，显式 flag 把风险从"静默"变成"可见"。
- 上述两点均有**本机可复跑实测**（不是推断），原始输出见 `reports/43-deploy-unit-consistency.md` §3。

### 6.5 回滚

```bash
# ① 回滚 unit（t70 部署时留有备份；命名形如 signaling.service.bak-t70-<UTC时间戳>）
ssh ... 'ls -l /opt/signaling/signaling.service.bak-* /etc/systemd/system/signaling.service*; exit'
ssh ... 'cp -a /opt/signaling/signaling.service.bak-t70-20260915T234524 /etc/systemd/system/signaling.service \
         && systemctl daemon-reload && systemctl restart signaling && systemctl is-active signaling; exit'

# ② 回滚二进制（t70 部署前旧件备份，t9/t12 版 c298235a…）
ssh ... 'cp -a /opt/signaling/bak-t70-20260915T234518 /opt/signaling/signaling \
         && systemctl restart signaling && sha256sum /opt/signaling/signaling; exit'

# ③ 复核：unit 与服务状态
ssh ... 'systemctl cat signaling | grep ExecStart; systemctl is-active signaling; systemctl is-enabled signaling; exit'
curl -s http://47.238.144.66:8443/healthz | grep -E 'status|roomGraceSec'
```
> ⚠️ **回滚到 t67 之前的二进制时，`ExecStart` 必须同时去掉 `-room-grace 90s`**（旧二进制不认识该 flag，否则服务起不来）。
> 反之，保留 `-room-grace` 就必须配 t67 及以后的二进制——这正是 6.4 的结论。

## 7. TURN 对外暴露面 = **用户已决策「接受风险 (C)」**（t82，2026-09-16）

> **这不是遗漏，也不是待办**：用户于 **2026-09-16** 就 TURN `3478` 对外暴露面明确**选择 (C)「demo 阶段接受暴露并正式记录」**，
> 明确**不采用** 方案 A（安全组限制来源网段）与 方案 B（coturn REST 短时凭据）。
> 完整登记见 **`reports/45-turn-exposure-accepted-risk.md`**（暴露面清单 / 补偿控制实测值 / 复评触发条件 / A/B 方案对比 / 事件响应速查）。

要点速览：

- **对外开放**（实测）：`3478/udp`、`3478/tcp`、`49152-49200/udp`（中继）、`8443/tcp`（信令）；`5349`（TLS/DTLS）**未放行**。
- **凭据来源**：信令在 `created`/`joined` 里下发 `turnUsername`/`turnCredential`（当前 = `demo`/`demopass`，来自 coturn `user=` 与 signaling `-user`），**客户端不硬编码**。
- **外部拿到凭据能做**：取得中继（实测 `ALLOCATE ok`）、为任意非禁止对等地址转发流量 —— 即消耗本机公网带宽与配额（`total-quota=45`、`user-quota=8`、`max-allocate-lifetime=600`）。
- **强制认证仍在**：无凭据者只能拿到 401；24h 实测有 10 个陌生来源（扫描器段 `66.132.x`/`45.205.x`/`151.243.x` 等）接触过 3478，但**均 0 次成功分配**。
- **出现以下信号必须复评并切方案 B**：出现非我方来源的成功分配 / 并发分配 ≥80% / 带宽账单异常 / 对外长期使用或多人共用 / 凭据疑似外泄（命令见报告 §4、§6）。
- **轮换凭据与封禁**：两处同步（coturn `user=` + signaling `-user`，先扩后收）与可直接粘贴的排查命令，见报告 §6。

