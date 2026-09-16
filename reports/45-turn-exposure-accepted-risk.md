# 45 — TURN 对外暴露面：**接受风险（决策 C）** 登记与补偿控制清单

> ## ⚠️ 这是**用户决策**，不是遗漏
> **用户于 2026-09-16 就 TURN `3478` 对外暴露面的三个选项做出决策：选择 (C) —— demo 阶段接受该暴露并正式记录在案。**
> 明确**不选**：
> - **方案 A**（用云安全组把来源限制到固定网段）
> - **方案 B**（coturn REST 短时凭据 / HMAC）
>
> 本文是该决策的**正式登记**：暴露面清单、现状补偿控制实测值、复评触发条件、A/B 加固方案对比、事件响应速查。
> **本文档不构成任何现网变更**（未改配置、未重启服务、未动安全组、未 git commit）。

- 任务：t82（attempt 1 / attempt_id `070bd468-4c1f-4e34-bf92-759f3167778e`）
- 责任人：coturn-installer
- 取证时间：2026-09-16 11:45–11:52 CST
- 宿主机：`root@172.21.0.219:5766`（公网 47.238.144.66）
- 相关报告：`reports/06-coturn.md`（部署与验证）、`reports/28-turn-permission-403.md`（403 定因与对等端地址策略、附录 D TCP 可达性）

---

## 1. 暴露面清单（现网实测，非文档抄写）

### 1.1 对外可达性矩阵（探测源 = 与本机同宿主机的容器网络命名空间，走公网 EIP）

| 端口/协议 | 用途 | 外部实测（47.238.144.66） | 依据 |
|---|---|---|---|
| **3478/udp** | STUN + TURN（UDP 传输） | ✅ **开放** | TURN ALLOCATE 成功：`relay=47.238.144.66:49169`；STUN 反射地址 `47.238.144.66:49836` |
| **3478/tcp** | TURN over TCP | ✅ **开放** | `TCP 47.238.144.66:3478 OK`；TURN-over-TCP ALLOCATE 成功 `relay=47.238.144.66:49199` + CreatePermission `0x0108 success`（见 `reports/28` 附录 D） |
| 49152-49200/udp | 中继端口段（按需分配） | ✅ 开放（实测 49152/49176/49200 回包） | `reports/28` §D 与 t58 复测 |
| **5349/tcp + 5349/udp** | TURN over TLS / DTLS | ❌ **未放行** | `TCP 47.238.144.66:5349 FAIL`；`openssl s_client -dtls -connect …:5349` 无响应（内网 5349 可握手，见 `reports/28` 附录 D） |
| **8443/tcp** | Go 信令 WebSocket（下发 TURN 凭据） | ✅ **开放** | 公网 `ws://47.238.144.66:8443/ws` create/join 成功；`verify_signal_e2e.mjs` 19/19 PASS |
| 8080/tcp | APK 下载服务（非 TURN，但同机暴露） | ✅ 开放 | `TCP 47.238.144.66:8080 OK`（`reports/20/21/35`） |
| 5766/tcp | sshd（运维） | ✅ 开放 | `TCP 47.238.144.66:5766 OK` |

**coturn 本机监听（`ss`）**：
```
UDP  172.21.0.219:3478            # STUN/TURN（多线程各自 socket）
UDP  172.21.0.219:5349            # DTLS（SG 未放行）
UDP  172.21.0.219:49168/49169/49179/49187（示例）  # 按需中继端口，范围 49152-49200
TCP  172.21.0.219:3478  (×4)
TCP  172.21.0.219:5349  (×4)
TCP  *:8443                       # signaling（systemd, pid 860227）
```
（`listening-ip=172.21.0.219`；`external-ip=47.238.144.66/172.21.0.219` 负责公网地址映射。）

### 1.2 凭据来源（客户端不硬编码）

- coturn 侧：`lt-cred-mech`（长凭证）+ `realm=webrtc-demo` + `user=demo:demopass`（`/etc/turnserver.conf` 第 10/11 行）。
- 信令侧：systemd `ExecStart` 携带 `-user demo:demopass`；客户端 `create`/`join` 的响应里由服务端下发：
```
{"type":"created","roomId":"…","stunUrl":"stun:47.238.144.66:3478",
 "turnUrl":"turn:47.238.144.66:3478?transport=udp",
 "turnUsername":"demo","turnCredential":"demopass"}
```
- ⇒ **任何能连上 `8443` 的人**只要 create/join 一次即可拿到凭据（无需账号体系，房间码即"秘密"）；客户端本身不写死凭据（这是设计如此，也意味着凭据的保密性 ≈ 信令端点的开放性）。

### 1.3 外部人员拿到凭据后能做什么（实测已证明）

| 能力 | 实测证据 | 边界 |
|---|---|---|
| 获取中继分配（把本机当 TURN 中继） | 外部（容器网络）`ALLOCATE ok relay=47.238.144.66:49169`（UDP）；`relay=…:49199`（TCP） | 每个 allocation 占 `total-quota=45` 中 1 个名额 + `49152-49200` 中 1 个端口 |
| 为任意对等地址建权限并转发流量 | `CreatePermission(8.8.8.8) → 0x0108 success` | 受 `denied-peer-ip`（0.0.0.0/8、127.0.0.0/8、169.254.0.0/16）与 `allowed-peer-ip`（10/8、172.16/12、192.168/16、100.64/10）约束 |
| 长期占用 | `max-allocate-lifetime=600`，客户端可 `REFRESH` 续期 | 续期需继续持有凭据（当前凭据长期有效，除非轮换） |
| 消耗本机公网带宽 | 实例公网带宽实测 ≈ 233–355 KB/s（`reports/21/35`）；中继流量走本机 EIP | 拿满 45 个 allocation 即接近上限，会直接影响真机通话质量 |
| 探测内网服务 | 可对 `allowed-peer-ip` 覆盖的私网段发起中继（如 `10/8`、`192.168/16`、`172.16/12`） | **loopback 与 link-local 已被显式拒绝**（否则可打本机 8080/5766/8443 = SSRF；见 `reports/28` §2.3/附录 C） |

---

## 2. 补偿控制现状（逐项实测值）

来源：宿主机 `/etc/turnserver.conf`（`md5 07baf30bd86a60cb5ed5933d2a51454b`，与仓库 `deploy/turnserver.conf` **逐字节一致**）、`dpkg -l coturn`、`ss -l*`。

| 控制项 | 实测值 | 作用 |
|---|---|---|
| coturn 版本 | **4.6.1-1build4**（Ubuntu 24.04 包，`turnserver --version` = `4.6.1`） | — |
| `listening-port` | `3478`（UDP+TCP 同端口；TLS/DTLS 默认 5349） | 固定入口，便于封禁/限制 |
| `lt-cred-mech` + `realm=webrtc-demo` + `user=demo:demopass` | 已启用 | **强制认证**：无凭据者只能拿到 401（24h 内 153 次 401 挑战） |
| `fingerprint` | 已启用（t58 修正了此前无效的 `use-fingerprint`） | 消息指纹校验，提升协议健壮性 |
| `total-quota` | **45**（t58 由 100 收紧，对齐端口池 49） | 全局并发分配上限 ⇒ 单点滥用最多占 45 个分配 |
| `user-quota` | **8** | 单个用户名并发分配上限 ⇒ 同一凭据最多占 8 个 |
| `max-allocate-lifetime` | **600 s** | 限制单次分配最长生命周期（需 REFRESH 续期） |
| `min-port`/`max-port` | `49152`–`49200`（**49** 个端口） | 中继端口池规模 = 并发中继能力的硬上限 |
| `denied-peer-ip` | `0.0.0.0-0.255.255.255`、`127.0.0.0-127.255.255.255`、`169.254.0.0-169.254.255.255` | 阻止中继打本机回环 / this-network / 云元数据（SSRF） |
| `allowed-peer-ip` | `10.0.0.0-10.255.255.255`、`172.16.0.0-172.31.255.255`、`192.168.0.0-192.168.255.255`、`100.64.0.0-100.127.255.255` | 4G↔WiFi 中继所需的对等地址段 |
| `verbose` | 已开启 | 提供 `usage:` / `peer usage:` / `closed … reason:` 等审计行（事件响应依赖） |
| CLI（telnet admin） | **关闭**（启动日志 `CONFIG ERROR: Empty cli-password … telnet cli interface is disabled`） | 无远程管理面可被利用 |
| 配置备份 | `/etc/turnserver.conf.bak-t58-20260915T204512`（t58 前）、`…bak-t58b-20260915T205146`、`…bak-t58c-20260915T205217`、`…bak-pre-verbose` | 回滚/对比基线 |
| 服务状态 | `coturn` active+enabled、`signaling` active+enabled（PID 860227） | — |

**控制项与暴露面的对应**：认证（挡住无凭据者）→ 配额/端口池（限制滥用规模上限）→ peer 白黑名单（限制可打的目标）→ 生命周期（限制占用时长）→ verbose 日志（提供可观测性）。**当前缺的是"凭据可撤销/可限时"**（即方案 B 提供的能力）。

---

## 3. 现状用量与「是否已被滥用」（近 24h 实测）

```
ALLOCATE processed, success : 145
401 Unauthorized            : 153          （认证挑战，含合法客户端的首次挑战）
CREATE_PERMISSION 403       : 92           （回环/link-local 对等地址，见 reports/28）
出现过的来源 IP（去重）      : 19
中继流量（单会话 peer usage rb 最大）: ≈1.38 MB
当前活动中继 socket          : 4
```

按"是否成功认证（`user <demo>`）"分类 24h 内的来源：

| 类别 | 来源 IP | 数量 |
|---|---|---|
| **认证成功**（合法使用；均可归属为我方测试或用户真机） | `120.233.71.120`(44 行)、`223.104.67.15/67.210/76.218/78.148/88.194`(中国移动 4G)、`47.238.144.66`(EIP 回环=我方探针)、`172.18.0.2`/`172.21.0.219`(容器/宿主机自测) | 9 |
| **仅未认证接触**（探测/扫描嫌疑；均 **0 次成功分配**） | `151.243.11.240/241/242`、`45.205.1.231/232/233`、`47.98.184.228`、`64.62.197.73`、`66.132.186.211`、`66.132.224.82` | 10 |

**结论（截至 2026-09-16 11:52）**：
- 暴露面**确实正在被互联网扫描**（典型的 `66.132.x.x`（Censys/ZoomEye 类扫描器）、`45.205.x`/`151.243.x`（主机商段）反复触达 3478/tcp；这些会话均以 `user <>` 结束，被 `allocation watchdog determined stale session state` 回收）。
- **尚未观察到未授权方成功认证/取得中继**：24h 内所有成功分配都能归属到我方测试或用户真机；无凭据者只拿到 401 挑战。
- ⇒ 现状与"接受风险 (C)"的假设一致：**风险存在且已可观测，但影响面目前被认证 + 配额 + 端口池限制住**。

---

## 4. 复评触发条件（出现任一即需重新评估，建议切 B）

| # | 触发信号 | 判定方式（现成命令见 §6.3） | 建议动作 |
|---|---|---|---|
| T1 | **出现非我方来源的成功分配** | 按来源聚合"认证成功"行，出现不认识的 IP | **立即切 B**（或先临时封禁该来源 + 轮换凭据） |
| T2 | 并发分配长期 ≥80%（≥36/45）或中继端口 ≥40/49 | `ss -lunp \| grep turnserver \| grep -vcE ':(3478\|5349)'` | 先限流/封禁，再切 B |
| T3 | 公网带宽/账单异常增长（相对基线） | 云监控出网带宽曲线 | 切 B + 缩短 TTL |
| T4 | **对外长期使用 / 多人共用**（超出 demo 范围） | 产品/用户侧决定 | 切 B（必要时同时上 A 的固定出口白名单） |
| T5 | 安全组或证书策略变化（如要求可撤销凭据、审计合规） | 运维/合规要求 | 切 B |
| T6 | 发现凭据外泄（仓库/APK/日志/聊天记录） | 人工发现 | **立即轮换凭据**（§6.1）+ 切 B |
| T7 | coturn 出现新 CVE 或 `check_stun_auth` 异常失败率 | 版本公告 / `journalctl` 计数 | 升级 coturn + 复评 |

> 说明：**当前未触发任何 T1–T7**；一旦触发，按上表处置并回写本报告（新增"复评记录"小节）。

---

## 5. 两个加固方案（A/B）对比

### 方案 A：云安全组限制来源网段

| 项 | 内容 |
|---|---|
| 改动点 | 阿里云控制台 → 安全组入方向：把 `UDP 3478`、`TCP 3478`、`UDP 49152-49200` 的源从 `0.0.0.0/0` 收紧为固定网段；可选保留 8443 公开 |
| 改动范围 | **零代码**；仅控制台规则（回滚=改回 0.0.0.0/0） |
| 预计工作量 | 5–10 分钟（含验证） |
| 主要风险 | ①**4G/移动网络出口 IP 动态且大量 NAT 共享**（实测真机出口为 `223.104.67.15/210`、`120.233.71.120` 等，随时变化）⇒ 白名单会**误伤真实用户**，且需持续维护；②CGNAT 下同一 IP 可能是陌生人；③WiFi 侧公网出口同样动态 |
| 适用 | **仅固定出口**场景（企业专线/单一办公网），或作为 T1 的**临时止血**（封禁单个滥用 IP） |
| 结论 | 对"手机 4G↔WiFi"这个 demo 的主要场景**不实用**（这正是用户未选 A 的原因之一） |

### 方案 B：coturn REST 短时凭据（HMAC）—— **推荐在触发后采用**

| 项 | 内容 |
|---|---|
| coturn 侧改动 | `/etc/turnserver.conf`：去掉 `user=demo:demopass`，改为 `use-auth-secret` + `static-auth-secret=<强随机>`（**已在 4.6.1 实测存在这两个选项**：`turnserver --help` → `--use-auth-secret`、`--static-auth-secret <secret>`）；重启 coturn |
| 信令侧改动点 | `signaling/main.go`：新增 `-turn-secret` / `-turn-ttl`（默认建议 3600s），删除/保留 `-user` 过渡；`created`/`joined` 下发时把 `turnUsername` 置为 `"<expiry-unix-ts>:<roomId|peerId>"`、`turnCredential = base64(HMAC-SHA1(secret, turnUsername))`。改动面约 30–60 行 + 单测。部署：unit 的 `-user demo:demopass` 换成 `-turn-secret <secret> -turn-ttl 3600` |
| **客户端改动** | **零改动**：wire 字段名与位置不变（`turnUsername`/`turnCredential`），客户端仍只是"把服务端给的凭据填进 ICE servers 配置" |
| 预计工作量 | 半天内（coturn 配置 10 分钟 + 信令实现与单测 2–3 小时 + 部署与端到端复测 1 小时） |
| 风险/注意 | ①**时钟必须同步**（阿里云 NTP；凭证有效期由时间戳决定）；②TTL 必须 ≥ 最长通话时长（建议 3600s，或客户端在通话中通过信令刷新）；③旧客户端兼容（字段不变，天然兼容）；④轮换变为"换 secret 即失效全部旧凭据"（比改 `user=` 更快更干净）；⑤secret 需妥善保存（systemd 环境变量或 `0600` 文件，避免进日志） |
| 收益 | 凭据**限时可撤销**、无法长期共享；配合 `user-quota`/`total-quota` 可显著降低 T1–T6 的影响面 |

**切换建议**：触发 T1/T2/T3 时按 B 落地；若同时需要"对外长期稳定使用"（T4），可 A+B 并用（A 收紧到已知出口集合，B 提供凭据时效）。

---

## 6. 事件响应速查（可直接粘贴执行）

> 以下命令均为**宿主机**执行；`SSH` 约定见团队契约。执行前先确认影响面（会中断在途通话）。

### 6.1 快速轮换凭据（两处同步，注意顺序）

凭据同时存在于 **coturn 配置** 与 **signaling 的 systemd unit**，必须**先扩后收**以避免中断：

```bash
# ① coturn：先同时接受新旧两个 user（保留旧行，避免在途会话/旧客户端立刻失败）
cp -a /etc/turnserver.conf /etc/turnserver.conf.bak-rotate-$(date +%Y%m%dT%H%M%S)
#  在 /etc/turnserver.conf 的 user=demo:demopass 下方追加一行（新密码）：
#  user=demo:<NEWPASS>
systemctl restart coturn && systemctl is-active coturn

# ② signaling：把 unit 的 -user 换成新凭据并重启（新会话从此拿新密码）
cp -a /etc/systemd/system/signaling.service /opt/signaling/signaling.service.bak-rotate-$(date +%Y%m%dT%H%M%S)
sed -i 's/-user demo:demopass/-user demo:<NEWPASS>/' /etc/systemd/system/signaling.service
systemctl daemon-reload && systemctl restart signaling
curl -s http://127.0.0.1:8443/healthz | head -5      # 确认存活
# 用真机/探针验证一次 create→join 后能 ALLOCATE（reports/12 的 verify_signal_e2e.mjs 可复用）

# ③ 观察数分钟后，删除 coturn 里的旧 user 行并再次 restart，完成收敛
sed -i '/^user=demo:demopass$/d' /etc/turnserver.conf
systemctl restart coturn && systemctl is-active coturn
```
> 轮换后旧凭据仍可能被在途 allocation 用到最多 `max-allocate-lifetime=600`（10 分钟）；紧急场景可 `systemctl restart coturn` 直接清空全部会话（会中断通话）。

### 6.2 临时封禁某个来源

```bash
# 方式一（推荐，云侧生效、不改本机）：阿里云控制台 → 安全组入方向 → 对 UDP/TCP 3478 加一条"拒绝/优先级更高"规则，源=该 IP
# 方式二（本机临时，重启即失效；注意本机 ufw 当前 inactive）：
iptables -I INPUT -s <ABUSE_IP> -j DROP
iptables -S INPUT | head -3          # 确认规则已插到最前
# 撤销：
iptables -D INPUT -s <ABUSE_IP> -j DROP
```

### 6.3 判定是否被滥用（实测可用）

```bash
# ① 近 1h 成功分配次数（基线：正常测试期 ~几十次/天）
journalctl -u coturn --since "-1h" --no-pager | grep -ac "ALLOCATE processed, success"

# ② 按来源 IP 聚合会话（找出陌生/高频来源）
journalctl -u coturn --since "-1h" --no-pager | grep -aoE "remote [0-9.]+:[0-9]+" | awk '{print $2}' | cut -d: -f1 | sort | uniq -c | sort -rn | head -10

# ③ 区分「认证成功」与「仅未认证接触」——有 user <demo> 行者才是拿到凭据的人
journalctl -u coturn --since "-24h" --no-pager | grep -a "user <demo>" | grep -aoE "remote [0-9.]+:" | awk '{print $2}' | cut -d: -f1 | sort | uniq -c | sort -rn

# ④ 中继字节量（单会话 peer usage 的 rb/sb 即收/发字节）与回收原因
journalctl -u coturn --since "-1h" --no-pager | grep -a "peer usage:" | tail -20
journalctl -u coturn --since "-1h" --no-pager | grep -aE "closed \(2nd stage\)" | grep -aoE "reason: .*" | sort | uniq -c

# ⑤ 当前中继占用（并发能力水位：端口池 49 / 配额 45）
ss -lunp | grep turnserver | grep -vcE ":(3478|5349)\b"

# ⑥ 认证失败/挑战频次（异常升高=有人在爆破或探测）
journalctl -u coturn --since "-1h" --no-pager | grep -ac "401: Unauthorized"
```

### 6.4 紧急止血（影响面从大到小）

```bash
systemctl stop coturn                     # ① 全停 TURN（所有中继通话中断，最快）
# ② 或收紧安全组：删除 UDP 3478 / UDP 49152-49200 的 0.0.0.0/0 放行（控制台，秒级生效）
# ③ 或临时换端口（改 listening-port + 安全组 + signaling 下发 URL；改动面大，慎用）
```

---

## 7. 登记与复评记录

| 日期 | 事项 | 结论 |
|---|---|---|
| 2026-09-16 | 用户就 TURN 3478 暴露面**决策 (C)：接受风险并正式记录**（不选 A / 不选 B） | 本文即该决策的正式登记 |
| 2026-09-16 11:52 | 首次取证（暴露面矩阵、补偿控制、24h 用量、扫描观测） | 未触发 T1–T7；无未授权成功使用迹象 |
| （待） | 若触发 §4 任一信号 | 按 §5 方案 B 加固，并在本表追加复评记录 |

**复评责任人/时机**：由 captain 在以下时点组织复评 —— ①每次对外演示前；②出现 §4 任一信号时；③demo 阶段结束后转入正式使用前。

---

## 8. 附录：原始证据摘要

```
# 暴露面（外部 TCP 矩阵）
TCP 47.238.144.66:3478 OK      TCP 47.238.144.66:5349 FAIL
TCP 47.238.144.66:8443 OK      TCP 47.238.144.66:8080 OK      TCP 47.238.144.66:5766 OK

# TURN 外部可用性（公开凭据 demo/demopass）
TURN 47.238.144.66:3478  user=demo  ALLOCATE ok  relay=47.238.144.66:49169
peer 8.8.8.8  CreatePermission 通过
TCP: ALLOCATE -> 0x0103 success relay=47.238.144.66:49199 ; CreatePermission(8.8.8.8) -> 0x0108 success
STUN 反射地址 = 47.238.144.66:49836

# 补偿控制（/etc/turnserver.conf 有效行，md5 07baf30bd86a60cb5ed5933d2a51454b）
listening-port=3478 / listening-ip=172.21.0.219 / external-ip=47.238.144.66/172.21.0.219 / relay-ip=172.21.0.219
min-port=49152 / max-port=49200 / realm=webrtc-demo / server-name=webrtc-demo
fingerprint / lt-cred-mech / user=demo:demopass / total-quota=45 / verbose
denied-peer-ip=0.0.0.0-0.255.255.255 / 127.0.0.0-127.255.255.255 / 169.254.0.0-169.254.255.255
allowed-peer-ip=10/8, 172.16/12, 192.168/16, 100.64/10
user-quota=8 / max-allocate-lifetime=600

# 24h 用量
ALLOCATE success = 145 ；401 Unauthorized = 153 ；CREATE_PERMISSION 403 = 92
来源 IP（去重）= 19 ；其中认证成功 9（全部可归属我方/真机），仅未认证接触 10（0 成功分配）
```

**安全组配置需用户在阿里云控制台侧确认**（本报告只能证明"从外部实测可达/不可达"，无法读取安全组规则本身）。
