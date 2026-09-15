# deploy/ —— 宿主机部署产物的可复现副本（t6 / t12）

本目录把宿主机上**实际生效**的部署文件落库，目的是让「部署可复现」成为可验收证据（而不是只写在报告正文里）。
`*.service` / `coturn.default` 三个文件是**字节级拷贝**（`cp` 自宿主机 → 仓库，md5 校验一致），不是凭记忆重写：

```
$ md5sum /etc/systemd/system/signaling.service   deploy/signaling.service
c9235cb9dddca755e90b1f58cf3aa02b  /etc/systemd/system/signaling.service
c9235cb9dddca755e90b1f58cf3aa02b  deploy/signaling.service
$ md5sum /usr/lib/systemd/system/coturn.service   deploy/coturn.service
3c0efb525dd3c43941fb451e30cc1bd9  /usr/lib/systemd/system/coturn.service
3c0efb525dd3c43941fb451e30cc1bd9  deploy/coturn.service
$ md5sum /etc/default/coturn                      deploy/coturn.default
625edc7c88847746661568e1ae6dfaa5  /etc/default/coturn
625edc7c88847746661568e1ae6dfaa5  deploy/coturn.default
$ diff /etc/systemd/system/signaling.service deploy/signaling.service  # 无输出
$ find . -name "*.service"        # 供 V53(a) 使用
./deploy/signaling.service
./deploy/coturn.service
```

| 仓库文件 | 宿主机来源路径 | md5 | 说明 | 采集时间 (CST) |
|---|---|---|---|---|
| `signaling.service` | `/etc/systemd/system/signaling.service` | `c9235cb9dddca755e90b1f58cf3aa02b` | 信令服务 unit（t12 创建，字节级拷贝） | 2026-09-13 16:05 |
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
