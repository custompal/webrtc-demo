# 28 — 4G↔WiFi 中继不可用诊断与修复：`CREATE_PERMISSION 403 Forbidden IP` + `local_relay=0`

- 任务：t58（attempt 2 / attempt_id `c48ab1a2-af01-43f0-b695-ef047ad68c07`）
- 责任人：coturn-installer
- 时间：2026-09-15 20:35–20:50 CST
- 宿主机：`root@172.21.0.219:5766`（公网 47.238.144.66；coturn 4.6.1）
- 证据来源：`tmp/di-a/x`、`tmp/di-b/x`（真机日志）、`journalctl -u coturn`、`/etc/turnserver.conf`

## 0. 结论（先看这里）

| 问题 | 结论 |
|---|---|
| `403 Forbidden IP` 的对象 | **只可能是 `0.0.0.0/8`（this-network）或 `127.0.0.0/8`（loopback）** —— 这是 coturn 4.6.1 的**内建默认拒绝表**（我们的 `/etc/turnserver.conf` 里此前没有任何 `denied-peer-ip`/`allowed-peer-ip`）。实测矩阵：其余全部类别（RFC1918 私网、CGNAT 100.64/10、link-local/元数据 169.254/16、组播、公网）**一律放行**。 |
| 真机为什么发这些地址 | 两台手机的 libwebrtc 都把 **loopback 候选**（`127.0.0.1` / `::1`）写进 SDP 并互发给对端（di-a 日志 63 处 `addr=127.0.0.1`；di-b 日志 176 处 `127.0.0.1` + 177 处 `::1`）。对端 ICE 于是向 TURN 服务器为这些候选申请权限 → coturn 拒绝 → 日志里的 403。 |
| 403 是否是黑屏主因 | **不是**。它只杀掉「loopback↔loopback」这条本来无用的候选对（中继若真去连 `127.0.0.1` 等于让 relay 打本机自己 = SSRF）。**因此正确处置不是放行 loopback**，见 §2。 |
| `local_relay=0`（失败会话无中继候选）归属 | **客户端侧**：失败会话在 15 s 看门狗触发时 `local_candidates=host=5, local_relay=0`，同一个 App 在**上一次成功会话**里 relay 候选在 gathering 开始后 **+372 ms** 就到位（§3.2）。服务端同期 `ALLOCATE processed, success` 32 次、`error` **0** 次、`508/Insufficient` 0 次 ⇒ 不是服务端拒绝或配额耗尽，而是该次 TURN 分配在 App 的 15 s 窗口内没有完成（同一会话里 STUN 与 TURN 请求都是 `code=701 … timed_out`）。 |
| 已落地的服务端修复 | ①显式固化「拒绝 loopback/0.0.0.0、放行私网/CGNAT/link-local」策略；②配额与中继端口池对齐（`total-quota 100→45`、新增 `user-quota=8`、`max-allocate-lifetime=600`）；③修掉无效配置项 `use-fingerprint`（coturn 4.6.1 不识别，实际从未生效）→ 改为 `fingerprint`；④备份 + restart + 全量复测（§4、§5）。 |
| 需要 App 侧改动 | 见 §7 的 5 条精确清单（**本任务未改 Kotlin**）。 |

---

## 1. 服务端侧取证（第一手）

### 1.1 coturn 配置（改动前，实测）

```
$ grep -nE "total-quota|allowed-peer-ip|denied-peer-ip|no-multicast-peers|verbose" /etc/turnserver.conf
15:total-quota=100
19:# captain 2026-09-14: 真机连通性定因期间开启 verbose（可回滚：/etc/turnserver.conf.bak-pre-verbose）
20:verbose
```
⇒ **没有任何** `denied-peer-ip` / `allowed-peer-ip` / `no-multicast-peers` 显式配置 ⇒ 403 只能来自 coturn 内建默认表。

### 1.2 403 日志原文（含会话与用户，但不含 IP）

```
$ journalctl -u coturn --since "-90 min" --no-pager | grep -aiE "403|Forbidden|CREATE_PERMISSION" | tail
Sep 15 20:30:14 turnserver[737683]: 77646: : session 003000000000000066: realm <webrtc-demo> user <demo>: incoming packet CREATE_PERMISSION processed, error 403: Forbidden IP
Sep 15 20:30:14 turnserver[737683]: 77646: : session 003000000000000066: realm <webrtc-demo> user <demo>: incoming packet message processed, error 403: Forbidden IP
Sep 15 20:31:13 turnserver[737683]: 77924: : session 001000000000000082: realm <webrtc-demo> user <demo>: incoming packet CREATE_PERMISSION processed, error 403: Forbidden IP
...
Sep 15 20:32:38 turnserver[737683]: 77964: : session 001000000000000084: realm <webrtc-demo> user <demo>: incoming packet CREATE_PERMISSION processed, error 403: Forbidden IP
```
**coturn 的 403 行不带 peer 地址**（即使 `verbose` 也不带）⇒ 我另建探针把「哪个地址会被拒」测出来（§2.2）。原始日志留档：`/tmp/t58.log`（宿主机，近 90 分钟全量）。

### 1.3 90 分钟统计（改动前）

| 指标 | 计数 |
|---|---|
| `ALLOCATE processed, success` | **32** |
| `ALLOCATE processed, error` | **0** |
| `CREATE_PERMISSION processed, success` | 82 |
| `CREATE_PERMISSION processed, error 403` | **18** |
| `401: Unauthorized`（长凭证挑战，正常） | 32 |
| `allocation timeout`（会话回收） | 24 |
| `allocation watchdog determined stale session state` | 24 |
| `508` / `Insufficient Capacity` | **0** |
| `Cannot create socket` | **0** |
| 活动 relay socket（`ss -lunp`，3478/5349 之外） | 12 / 端口池 49 |

## 2. ① 403 的成因（IP 归类 + 判定依据）

### 2.1 真机侧：两台设备都在广播 loopback 候选

```
# di-a（Xiaomi 24117RK2CC / Android16）app.log
2026-09-15T10:27:06.969Z INFO kotlin pc ice_candidate_local evt=10 idx=0 local=type=host_proto=udp_addr=127.0.0.1_port=37452 mid=0 session=s1
2026-09-15T10:27:07.071Z INFO kotlin pc ice_candidate_local evt=13 idx=0 local=type=host_proto=tcp_addr=127.0.0.1_port=50653 mid=0 session=s1
2026-09-15T10:27:07.388Z INFO kotlin pc ice_candidate_remote evt=18 remote=type=host_proto=udp_addr=127.0.0.1_port=41029 session=s1
2026-09-15T10:27:46.725Z WARN kotlin pc pc_ice_candidate_error address=127.0.0.x code=701 port=37452 text=STUN_binding_request_timed_out. url=stun:47.238.144.66:3478
2026-09-15T10:27:46.785Z WARN kotlin pc pc_ice_candidate_error address=127.0.0.x code=701 port=37452 text=TURN_allocate_request_timed_out. url=turn:47.238.144.66:3478?transport=udp

# di-b（Mi 10 Pro / Android13）app.log
2026-09-15T12:30:31.186Z WARN kotlin pc ice_deferred evt=2 queued=1 remote=type=host_proto=udp_addr=127.0.0.1_port=58575 session=s1
2026-09-15T12:30:31.189Z WARN kotlin pc ice_deferred evt=4 queued=3 remote=type=host_proto=udp_addr=::1_port=53878 session=s1
2026-09-15T12:30:31.199Z INFO kotlin pc ice_candidate_remote evt=14 remote=type=relay_proto=udp_addr=47.238.144.66_port=49196 session=s1
```

地址分布（`grep -o "addr=[0-9.:]*" app.log | sort | uniq -c`）：

| 设备 | `127.0.0.1` | `::1` | 其它（真实私网/公网/relay） |
|---|---|---|---|
| di-a | 63 | 4（`addr=2409…`1 处等） | 47.238.144.66×30、192.168.1.100×25、120.233.71.120×18、223.104.67.15×10… |
| di-b | 176 | 177 | 47.238.144.66×119、192.168.10.85/86、120.230.119.148、111.55.211.195、2409:895… |

libwebrtc 原生日志里同一批候选（末位打码）：
```
di-a webrtc.1.log: candidate:_Cand[:3477044805:1:udp:2122063615:127.0.0.x:58575:host::0:...]
di-a webrtc.2.log: candidate:_Cand[:1123209997:1:tcp:1518149375:127.0.0.x:34751:host::0:...]
di-b webrtc.1.log: candidate:_Cand[:71943624:1:tcp:1518149375:127.0.0.x:47999:host::0:...]
```
⇒ 对端 SDP 里带着 `127.0.0.1` / `::1` 的 host 候选，本端 ICE 就会为其向 TURN 申请权限 → **403**。

### 2.2 探针矩阵（决定性证据，官方等价探针）

判据：coturn 对 CREATE_PERMISSION 的成功响应类型是 `0x0108`、错误响应是 `0x0118`（error-code 属性 0x0009）。探针脚本：`deploy/turnperm_probe.mjs`（零依赖，容器侧运行；宿主机无 node）。

**改动前（20:42:55）**——修复前 403 复现：
```
$ node deploy/turnperm_probe.mjs 172.21.0.219 3478 demo demopass 0.0.0.0 127.0.0.1 10.0.0.5 172.16.0.1 172.21.0.219 192.168.1.101 169.254.169.254 100.64.0.1 224.0.0.1 239.255.255.255 255.255.255.255 8.8.8.8 47.238.144.66
TURN 172.21.0.219:3478  user=demo  ALLOCATE ok  relay=47.238.144.66:49161

peer_ip            result        err  class                verdict
-----------------  ------------  ---  -------------------  -------------------------------
0.0.0.0            ERROR         403  this-network 0/8     CreatePermission 被拒 (403)
127.0.0.1          ERROR         403  loopback 127/8       CreatePermission 被拒 (403)
10.0.0.5           SUCCESS       -    RFC1918 私网           CreatePermission 通过
172.16.0.1         SUCCESS       -    RFC1918 私网           CreatePermission 通过
172.21.0.219       SUCCESS       -    RFC1918 私网           CreatePermission 通过
192.168.1.101      SUCCESS       -    RFC1918 私网           CreatePermission 通过
169.254.169.254    SUCCESS       -    link-local/元数据       CreatePermission 通过
100.64.0.1         SUCCESS       -    CGNAT 100.64/10      CreatePermission 通过
224.0.0.1          SUCCESS       -    multicast 224/4      CreatePermission 通过
239.255.255.255    SUCCESS       -    multicast 224/4      CreatePermission 通过
255.255.255.255    SUCCESS       -    reserved 240/4       CreatePermission 通过
8.8.8.8            SUCCESS       -    public 公网            CreatePermission 通过
47.238.144.66      SUCCESS       -    public 公网            CreatePermission 通过
```

**判定依据（coturn 的规则来源）**：
- `/etc/turnserver.conf` 改动前**无**任何 peer-ip 规则（§1.1，file lines 1–15）⇒ 适用 coturn **内建默认**：
  `denied-peer-ip = 0.0.0.0-0.255.255.255`（this-network）与 `127.0.0.0-127.255.255.255`（loopback）；`no-multicast-peers` 默认开但实测组播仍被放行（coturn 4.6.1 的该默认只作用于 `ChannelBind`-无关路径，见矩阵中 `224.0.0.1 SUCCESS`）。
- man 页语义（`man turnserver`，第 654–662 行）：
  > `--denied-peer-ip=<IPaddr[-IPaddr]>` / `--allowed-peer-ip=<IPaddr[-IPaddr]>` … **If an ip address is specified as both allowed and denied, then the ip address is considered to be allowed.**
  ⇒ 放行必须用 `allowed-peer-ip` 显式覆盖；我们**没有**对 loopback 做覆盖（理由见 §2.3）。
- 归类：真机 403 的对象 = **回环地址（`127.0.0.0/8`，IPv6 对应 `::1`）**；`0.0.0.0/8` 属"未指定/this-network"。**均非 RFC1918**——RFC1918 私网地址实测是被放行的（这对 4G↔WiFi 很关键）。

### 2.3 为什么**不**放行 loopback（安全边界，务必带走）

若把 `allowed-peer-ip=127.0.0.0-127.255.255.255` 写进配置，任何能连上 TURN 的客户端都可以让中继去访问 **coturn 宿主机自己的回环服务**：本机 `127.0.0.1:8080`（APK 下载服务）、`127.0.0.1:5766`（sshd）、`127.0.0.1:8443`（信令）等 ⇒ 典型 SSRF。对 WebRTC 而言 loopback 对等地址也**毫无用处**（对端的 127.0.0.1 不是本机能到达的对端）。
⇒ **保持拒绝**；真正要改的是客户端不要再发这些候选（§7）。

## 3. ③ 配额与分配残留复核

### 3.1 实测数据（90 分钟窗口，改动前）

| 项 | 值 | 说明 |
|---|---|---|
| `total-quota` | 100 | 全局并发分配上限 |
| 中继端口池 | 49152–49200 = **49** | 真正的硬上限比配额小一半 |
| `ALLOCATE error` / `508` / `Cannot create socket` | **0 / 0 / 0** | **没有**发生「因配额或端口耗尽而拒绝分配」 |
| 活动 relay socket | 12 / 49 | 余量充足 |
| `allocation timeout` / `stale session state` | 24 / 24 | 均为**会话被回收**（客户端不再刷新），属正常清理；被回收的分配会释放端口 |

### 3.2 结论

- **不存在**「反复测试导致旧分配累积、新分配被拒」的实测证据：90 分钟内 32 次分配全部成功，0 次配额/端口类失败，端口池用量峰值 <25%。
- 但**机制上**存在隐患：`total-quota(100) > 端口池(49)`，意味着永远先撞端口池、再撞配额；一旦 49 个端口被占，客户端拿到的是模糊的失败而非明确的配额拒绝。**故做参数收敛**（§4）：`total-quota=45`（留 4 个余量）+ `user-quota=8`（避免单客户端重连风暴占满池子）+ `max-allocate-lifetime=600`（默认 3600 s；缩短后废弃分配最多 10 分钟归还端口）。
- 另外：`401 Unauthorized` 32 次 = STUN 长凭证的**正常挑战**（每次新客户端首次请求），不是错误。

## 4. ② 修复与落地（配置 diff / 备份 / restart / 复测）

### 4.1 配置 diff（`/etc/turnserver.conf`）

备份：`/etc/turnserver.conf.bak-t58-20260915T204512`（611 B，改动前原文；另有早前 `/etc/turnserver.conf.bak-pre-verbose`）。

```diff
-# use-fingerprint
+# use-fingerprint   # t58: coturn 4.6.1 不认识该名（启动日志 Bad configuration format），实际未生效；正确名为 fingerprint
+fingerprint
-total-quota=100
+total-quota=45
 
+# ===== t58 (2026-09-15) 4G↔WiFi 中继诊断：对等端地址策略与配额 =====
+# ① 显式固化拒绝（与内建默认一致，仅作文档化；不要放行 loopback = SSRF）
+denied-peer-ip=0.0.0.0-0.255.255.255
+denied-peer-ip=127.0.0.0-127.255.255.255
+# ② 显式放行 4G↔WiFi 真正需要的对等地址段（默认已允许，固化防止默认表变化）
+allowed-peer-ip=10.0.0.0-10.255.255.255
+allowed-peer-ip=172.16.0.0-172.31.255.255
+allowed-peer-ip=192.168.0.0-192.168.255.255
+allowed-peer-ip=100.64.0.0-100.127.255.255
+allowed-peer-ip=169.254.0.0-169.254.255.255
+# ③ 配额与端口池对齐
+user-quota=8
+max-allocate-lifetime=600
```

说明：
1. `use-fingerprint` **在 coturn 4.6.1 里不生效**（每次启动 3 行 `Bad configuration format: use-fingerprint`，改动前就有）；正确选项名是 `fingerprint`。改后启动无该告警，且**实测不要求客户端携带 FINGERPRINT**（无 FINGERPRINT 的探针与 `turnutils_uclient` 均正常）⇒ 零兼容风险。
2. `denied-peer-ip` 两条与内建默认完全一致（**行为零变化**，只是把策略写进配置，避免"403 到底谁拒的"再成谜）。
3. `allowed-peer-ip` 五条覆盖 RFC1918 三段 + CGNAT + link-local（4G 侧出现在 `10.1.146.251`、WiFi 侧 `192.168.1.x`；`169.254/16` 覆盖云元数据段——**注意**：此处放行是因为对等端地址由客户端给出，若担心 SSRF 到云元数据（169.254.169.254）可去掉这一条，代价是手机若产生 link-local 候选会 403；本 demo 保留并在 §8 记为待评估项）。

### 4.2 重启与状态

```
$ systemctl restart coturn && sleep 3
$ systemctl is-active coturn ; systemctl is-enabled coturn
active
enabled
$ grep -nE "total-quota|allowed-peer-ip|denied-peer-ip|no-multicast-peers|verbose" /etc/turnserver.conf
15:total-quota=45
20:verbose
28:denied-peer-ip=0.0.0.0-0.255.255.255
29:denied-peer-ip=127.0.0.0-127.255.255.255
31:allowed-peer-ip=10.0.0.0-10.255.255.255
32:allowed-peer-ip=172.16.0.0-172.31.255.255
33:allowed-peer-ip=192.168.0.0-192.168.255.255
34:allowed-peer-ip=100.64.0.0-100.127.255.255
35:allowed-peer-ip=169.254.0.0-169.254.255.255
```
（`fingerprint`/`user-quota`/`max-allocate-lifetime` 不在该 grep 模式内，见 §4.1 diff 与 §6 附录全文。）

### 4.3 复测：策略矩阵（改动后，20:47:20）

```
TURN 172.21.0.219:3478  user=demo  ALLOCATE ok  relay=47.238.144.66:49182

0.0.0.0            ERROR         403  this-network 0/8     CreatePermission 被拒 (403)     ← 保持拒绝（设计）
127.0.0.1          ERROR         403  loopback 127/8       CreatePermission 被拒 (403)     ← 保持拒绝（SSRF 边界）
10.0.0.5 / 172.16.0.1 / 172.21.0.219 / 192.168.1.101      SUCCESS   RFC1918 私网
169.254.169.254    SUCCESS       -    link-local/元数据
100.64.0.1         SUCCESS       -    CGNAT 100.64/10
224.0.0.1          SUCCESS       -    multicast 224/4
8.8.8.8 / 47.238.144.66            SUCCESS       -    public 公网
```
⇒ **4G↔WiFi 相关的全部地址类别均被放行**；唯一被拒的 loopback/0.0.0.0 是刻意保留的安全边界。

## 5. ④ 端到端复测（含私网 peer 的真实数据往返，官方客户端）

```
$ turnutils_peer -p 3480 &                     # 宿主机上的 echo peer（0.0.0.0:3480）
$ turnutils_uclient -v -u demo -w demopass -p 3478 -n 5 -m 1 -e 172.21.0.219 -r 3480 172.21.0.219
1: : success
1: : channel bind sent
1: : cb response received:
1: : success: 0x5543
...
5: : start_mclient: tot_send_msgs=10, tot_recv_msgs=10
5: : start_mclient: tot_send_bytes ~ 1000, tot_recv_bytes ~ 1000
5: : Total lost packets 0 (0.000000%), total send dropped 0 (0.000000%)

# 服务端同一会话：
$ journalctl -u coturn --since "-2min" | grep -aE "CREATE_PERMISSION|403"
... session 000000000000000001: incoming packet CREATE_PERMISSION processed, success   (×6)
```
**peer 地址是私网 `172.21.0.219`，CreatePermission 全部成功，中继数据 10/10 往返、丢包 0%** ⇒ 私网对等端的中继路径在服务端完全可用。

对照：`-e 192.168.1.101`（该地址无 echo 服务）→ CreatePermission **success**、数据无回程（100% loss，符合预期，因为对端不存在）。
`-e 127.0.0.1` → CreatePermission **403**（改动前后一致，见 §2.2/§4.3）。

## 6. ⑤ `local_relay=0` 的归属判定与 App 侧清单

### 6.1 失败会话时间线（di-b / Mi 10 Pro，joiner）

```
12:31:52.290 pc_starting evt=1 force_relay=false ice_servers=stun+turn session=s1
12:31:53.169 ice_candidate_remote evt=20 remote=type=relay proto=udp addr=47.238.144.66 port=49168   ← 对端 relay 候选已到
12:32:02.164 ERROR kotlin ui preview_recover_give_up attempts=2
12:32:08.055 WARN kotlin pc ice_not_connected after_ms=15000 ice_state=CHECKING
             local_candidates=host=5 local_relay=0 remote_candidates=host=6,srflx=1,relay=1 remote_desc_set=true transport=CONNECTING
12:32:08.375 stats_sample avail_bps=0 down_bps=0 impl=- local=host mode=RELAY remote=relay up_bps=0
12:32:08.664 ERROR kotlin pc pc_connection_state dtls=false state=FAILED
```
- 15 s 内**本端一个 relay 候选都没有**（`local_relay=0`），而**对端 relay 候选早已到达** ⇒ 只要本端也有 relay 候选就能形成 relay↔relay 路径。
- 同一设备**上一次成功会话**（12:31:12 起，s2）：
```
12:31:12.713 pc_ice_gathering_state state=GATHERING
12:31:12.852 ice_candidate_local evt=25 local=type=relay proto=udp addr=47.238.144.66 port=49181   ← +372 ms 就拿到 relay
12:31:13.380 selected_candidate_pair mode=RELAY reason=candidate_pair_state_changed
```
- 失败会话里 App 自己的 STUN/TURN 请求同时超时（`code=701`，且是 loopback 终点 127.0.0.x 的那条 socket）。

### 6.2 服务端同期表现（排除"服务端拒绝/配额"）

§1.3：`ALLOCATE success 32 / error 0`；`508/Insufficient 0`；`Cannot create socket 0`；端口池 12/49。
⇒ 该会话的 `local_relay=0` **不是**服务端返回错误造成的，而是**客户端在该 15 s 窗口内没有完成 TURN 分配/gathering**（网络切换期间的 UDP 抖动、或该 socket 的请求超时），而 App 的看门狗把「15 s 内没连上」直接判为失败。

### 6.3 App 侧改动清单（交 captain / android-dev；本任务未改 `app/**`）

| # | 位置 | 问题 | 建议改动 | 判据/证据 |
|---|---|---|---|---|
| A1 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:88`（`ICE_WARN_MS = 15_000L`）、`:879-896`（看门狗调度）、`:936`（`ice_not_connected`） | 15 s 固定窗口对「4G↔WiFi 切换 + 走中继」过短；失败会话正是在 15 s 触发 | ①把 relay 场景的窗口放宽到 **30–45 s**；②或把看门狗改为「**必须先看到至少一个 relay 候选**或 `iceGatheringState==COMPLETE`」才起算；③超时后先做 **ICE restart**（重发 offer）而不是直接 FAILED | di-b 成功会话 relay 候选 +372 ms 到位、失败会话 15 s 时 `local_relay=0` |
| A2 | `CallSession.kt:712`（`onIceCandidate` → 发送本端候选） | 会把 `127.0.0.1`/`::1` loopback host 候选发给对端，诱发对端 403、浪费信令 | 发送前过滤：`address` 属 `127.0.0.0/8`、`::1` 的候选**直接丢弃**（两机日志里这类候选合计 63+176+177+4 条） | coturn 403 = loopback；探针矩阵 |
| A3 | `CallSession.kt:512-543`（`addIceCandidate` / `ice_deferred` 重放） | 会把对端的 loopback 候选灌进 libwebrtc，libwebrtc 随即为其建权限 → 403 | 接收侧同样过滤 `127.0.0.0/8` 与 `::1`（可保留 `::1` 之外的 IPv6） | di-a/di-b 日志中 remote 候选含 `127.0.0.1`、`::1` |
| A4 | `CallSession.kt:771`（`onIceCandidateError`） | 目前只打日志：`code=701 TURN_allocate_request_timed_out` 后不重试，最终 FAILED | 对 `turn:` URL 的 701 错误：计次并触发**重新 gathering**（重建 PC 或 ICE restart）；同时把「本会话 relay 候选数」计入失败诊断字段（已有 `local_relay`） | 失败会话同端口 STUN+TURN 双超时 |
| A5 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:51-80` | 只有 `turn:…?transport=udp`；UDP 被运营商劣化时无退路 | **可选**：追加 `turn:47.238.144.66:3478?transport=tcp` 作为备选（注意：**阿里云安全组目前只放行 UDP 3478，TCP 3478 未放行**，需用户先开；见 §8） | SG 实测 TCP 3478 不可达 |

> 说明：`WebRtcConfig.kt:15` 注明本版 jar 无 `iceTransportPolicy`，只有 `iceTransportsType=ALL`；不必改成 relay-only（会牺牲同网直连）。

## 7. 未验证项 / 待办

1. **真机 4G↔WiFi 复测**：本任务只能证明「服务端策略与中继数据路径可用」，最终成功率需要用户两台手机按 A1/A2/A3 改版后实测（无设备）。
2. **TCP 3478 未放行**：`47.238.144.66:3478` TCP 从外部实测 FAIL（安全组只放行 UDP 3478/49152-49200 与 TCP 8443）⇒ A5 的 TCP 回退需用户开通。
3. `allowed-peer-ip=169.254.0.0-169.254.255.255` 是否保留待评估：它让中继可访问 link-local（含云元数据 169.254.169.254）。当前保留是为覆盖手机可能产生的 link-local 候选；若安全优先可删除该行（代价：这类候选会 403，不影响正常私网/公网路径）。
4. `fingerprint` 选项已在宿主生效且不强制客户端；建议同步修订 `doc/01-cloud-infra.md` §5（把 `use-fingerprint` 改为 `fingerprint`）——**本任务未改 doc**。

## 8. 附录：复跑命令与产物

```bash
# ① 配置与状态
ssh … 'grep -nE "total-quota|allowed-peer-ip|denied-peer-ip|no-multicast-peers|verbose" /etc/turnserver.conf; systemctl is-active coturn; exit'
# ② 403 现状（近 60 分钟）
ssh … 'journalctl -u coturn --since "-60 min" --no-pager | grep -aiE "403|Forbidden|CREATE_PERMISSION" | tail -8; exit'
# ③ 策略矩阵（容器侧，需 node）
node code/webrtc-demo/deploy/turnperm_probe.mjs 172.21.0.219 3478 demo demopass 0.0.0.0 127.0.0.1 10.0.0.5 192.168.1.101 100.64.0.1 169.254.169.254 8.8.8.8
# ④ 私网 peer 端到端（宿主机）
ssh … 'turnutils_peer -p 3480 & sleep 1; turnutils_uclient -v -u demo -w demopass -p 3478 -n 5 -m 1 -e 172.21.0.219 -r 3480 172.21.0.219; exit'
# ⑤ 回滚
ssh … 'cp -a /etc/turnserver.conf.bak-t58-20260915T204512 /etc/turnserver.conf && systemctl restart coturn; exit'
```

产物：
- 宿主机：`/etc/turnserver.conf`（改动后）、`/etc/turnserver.conf.bak-t58-20260915T204512`（改动前）、`/tmp/turnserver.conf.pre-t58`（同一快照）
- 仓库：`reports/28-turn-permission-403.md`（本文）、`deploy/turnserver.conf`（与宿主机逐字节相同的副本）、`deploy/turnperm_probe.mjs`（探针脚本）
- 未改动：`app/**`、`doc/**`、`signaling/**`、`third_party/**`、`/opt/apk-http/**`

### 附：改动后 `/etc/turnserver.conf` 全文

见 `deploy/turnserver.conf`（与其逐字节一致，`diff` 无输出）。

---

## 附录 B（t58 完成后的补充证据，2026-09-15 20:5x 追加；正文结论不变）

captain 提供了**同一时段、另一轮**真机日志（新路径 `tmp/dj-a/x`、`tmp/dj-b/x`；旧证据 `tmp/di-*` 见正文）。我逐条只读核对，结论与正文一致，并据此补充两条 App 侧条目。

### B.1 `local=- / mode=-` = 没有选中的候选对（编号已核对）

```
# dj-b（Mi 10 Pro）app.log：pc_starting 后每 2 s 一条，持续 ≥56 s
2026-09-14T16:18:35.244Z INFO kotlin pc    pc_starting force_relay=false ice_servers=stun+turn
2026-09-14T16:18:35.247Z INFO kotlin pc    rtc_config … turn=turn:47.238.144.66:3478?transport=udp
2026-09-14T16:18:37.399Z INFO kotlin stats stats_sample avail_bps=0 down_bps=0 impl=- local=- mode=- remote=- up_bps=0
…
2026-09-14T16:19:31.433Z INFO kotlin stats stats_sample avail_bps=0 down_bps=0 impl=- local=- mode=- remote=- up_bps=0
```
- `local=- / mode=- / remote=-` ⇒ **没有选中的候选对**；`avail_bps=0` ⇒ GCC 估计为 0 ⇒ 编码器被压到下限。
- `encoder_bitrate.csv`（dj-b）尾部与之一致：`ts_kbps=14、rc_target_kbps=14、encoded_bytes=75–185、qp=224（320x240）`。
  ⇒ 用户观感"只出一帧就卡住"= **连接从未建立**，而不是"编码器坏/渲染坏"。

### B.2 同一 App 连通时统计正常（说明 `local=-` 是"未连通"而非统计缺陷）

```
# dj-a（平板）
2026-09-15T10:26:56 … 10:27:06  stats_sample … impl=- local=- mode=- remote=- up_bps=0     ← gathering 未完成
2026-09-15T10:27:07.082 INFO kotlin pc ice_candidate_local evt=15 local=type=relay proto=udp addr=47.238.144.66 port=49189
2026-09-15T10:27:07.553 INFO kotlin pc pc_connection_state dtls=false state=CONNECTING
2026-09-15T10:31:49.788 INFO kotlin stats stats_sample avail_bps=4039566 down_bps=7675056 impl=SelfVp9Libvpx local=host mode=P2P remote=host up_bps=7028466
```
⇒ 同一台设备在真正选到候选对后 `mode=P2P`、`up/down_bps` 正常；`local=-` 只出现在**未连通**期间（dj-a 约 13 s 后才拿到 relay 候选，与正文 §6.1 的"relay 到达晚于窗口"一致）。

### B.3 dj-* 日志里同样可见正文的两类现象（复核一致）

```
dj-a: ice_candidate_local … addr=127.0.0.1_port=37452 / addr=::1_port=37058        ← 回环候选（正文 §2.1）
dj-a: pc_ice_candidate_error address=127.0.0.x code=701 port=37452 text=TURN_allocate_request_timed_out. url=turn:47.238.144.66:3478?transport=udp
dj-b: 同一轮未见 CREATE_PERMISSION 403（该轮未建到申请权限的阶段）；服务端 403 证据见正文 §1.2
```
⇒ 与正文 §2/§6 的判断一致：**403 的对象是回环地址**；本轮失败的直接表现是"没连上 + 无选中候选对"，属同一根因域（中继不可用/分配未及时完成）。

### B.4 App 侧清单追加两条（承接 captain 要求）

| # | 位置 | 要求 | 与已有工作的关系 |
|---|---|---|---|
| **A6** | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/`（`ConnectionStatus.kt` 状态机 + `CallScreen.kt` 连接中遮罩/失败提示 + `CallViewModel.kt` 一键重试） | 当 **dtls 未 CONNECTED 或 `local=-`（无选中候选对）持续 N 秒**时，UI **不得继续停留在最后一帧静止画面**，必须显式呈现"连接中（含已用时长）/失败"+ 一键重试 | **已由在途 t59 覆盖**（工作区已有 `ConnectionStatus.kt` + `ConnectionStatusTrackerTest.kt`，`CallScreen.kt:449-498` 有"连接中/失败 + 点击重试"，`CallViewModel.kt:131-142` 有 tracker 与世代化重试）。本条登记为"已覆盖"，仅建议：判活判据严格用 **`mode=P2P\RELAY` 或 `down_bps>0`**（t59 注释已如此设计，且明确指出**不能用 `up_bps`**，因为 dj-b 实测无候选对时 `up_bps` 仍≈49 kbps） |
| **A7** | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt`（`:712` 本端候选、`:771` `onIceCandidateError`、`:879-896` 看门狗） | 检测到**「有 TURN 配置但 `local_relay==0`」**（未 gather 到中继候选）时，应触发**重新 gather / ICE restart / 重建 PC**，而不是等 15 s 判失败；并打点重试次数与结果 | **仍开放**：t59 的 tracker 只有 `TIMEOUT_NO_PAIR / CONNECTION_LOST / REMOTE_FRAME_STALLED / SESSION_START_FAILED` 四类原因，`ConnectionStatusTracker` 不含"中继未 gather"的自动重试；建议把它作为 `TIMEOUT_NO_PAIR` 的一个子原因或独立原因并入 t59/t60 |

> 上述两条**不改变** t58 的服务端结论：403 仅针对回环地址、私网/CGNAT/公网对等端一律放行、配额无耗尽证据；服务端已无阻塞项，剩余风险在客户端 gather 时序与 UI 可见性。
> 新证据路径：`/opt/dsh-workspaces/tmp/dj-a/x`、`/opt/dsh-workspaces/tmp/dj-b/x`（本轮）；`di-a/di-b`（正文）、`dl-*`（旧）。
