> **Archived 2026-09-17** — superseded by `doc/design/`; archived verbatim as `doc/archive/01-cloud-infra.md`. This copy is history, not current guidance.

# 云基础设施方案（单台大档）

## 1. 总览
**一台国内/香港大档云主机**，既编译 libwebrtc Android，又跑 coturn(STUN+TURN) + Go 信令。省去“编译机→运行机”拷贝部署的麻烦。

预算从原 ¥50 提到约 ¥100-112/周：单台需 8GB/100GB+ 才能扛编译，原 ¥50 档（2C2G/40GB）扛不住。详见 `adr/ADR-004.md`。

## 2. 选型候选
| 提供商 | 套餐 | 地域 | 价格 | 计费 | 备注 |
|---|---|---|---|---|---|
| 腾讯云轻量 | 8C8G/100GB/某档 | 香港 | ~¥100+/月 | 月付 | 香港免备案、低延迟、放行 UDP 待确认 |
| 阿里云轻量 | 高档 | 香港 | ~¥100+/月 | 月付 | 同上 |
| Hetzner Cloud | CPX32(4C8G/160GB) | 新加坡 | $0.0929/hr | 小时 | 一周≈¥112，海外延迟略高 |
| Hetzner Cloud | CPX32 | 德国/芬兰 | $0.0673/hr | 小时 | 一周≈¥81，最便宜但延迟高 |

推荐：**国内/香港大档（如腾讯轻量香港 8C8G 档，~¥100+/月）**——低延迟、免备案、便于持续学习与多次重编；若不在意延迟可选 Hetzner SG 按小时（一周 ~¥112，编译完不销毁也能继续跑 coturn/信令）。

## 3. 机器规格要求
- CPU ≥ 4 核（8 核更佳，libwebrtc 编译几小时 vs 十几小时）。
- RAM ≥ 8GB（链接阶段内存峰值高）。
- 磁盘 ≥ 100GB（libwebrtc 源码 ~50GB + libvpx + NDK + 产物）。
- 公网 IPv4；安全组放行 UDP 3478、relay 端口段（49152-49200）、信令 TCP 443/8443。
- 系统：Ubuntu 22.04 LTS。

## 4. 同机部署形态
```
┌──────────── 单台云主机（Ubuntu 22.04）────────────┐
│  编译侧： depot_tools + Android NDK + libvpx 源码   │
│           → libwebrtc Android arm64 静态库/.so + 头  │
│           → 自研 VP9 VideoEncoder 产物              │
│  运行侧： coturn (UDP 3478 + relay 段)              │
│           Go 信令 (TCP 8443, systemd)               │
└──────────────────────────────────────────────────┘
       ▲ 信令 + coturn 配置下发          ▲ 产物 scp 回 Windows
       │                                  │
   Android 客户端                       Windows 开发机
```
注：libwebrtc 的 `.a/.so` + 头文件仍需拷回 Windows 链接进 Android app（app 用 Android Studio 在 Windows 构建）。所谓“省去拷贝部署”指**coturn/信令/编译环境同在一台**，不再为运行单独租一台小机；编译产物回 Windows 是 Android 工具链必须，不在此省。

## 5. coturn 部署（同机）
安装 `apt install coturn`。`/etc/turnserver.conf`：
```
listening-port=3478
listening-ip=<内网IP>
external-ip=<公网IP>/<内网IP>
relay-ip=<公网IP>            # ← ⚠️ 实测修正（t6，2026-09-13）：此行写法错误，必须填「内网 IP」，详见下方注记
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

> **实测修正（t6，2026-09-13）— 配置块中的 `relay-ip=<公网IP>` 是错的，必须写内网 IP**
> - 正确写法：`relay-ip=<内网IP>`（本例 `172.21.0.219`）；对外通告的公网 relay 地址由 `external-ip=<公网IP>/<内网IP>` 映射负责（本例 `external-ip=47.238.144.66/172.21.0.219`）。EIP 是 NAT 映射地址、不在本机网卡上，内核不允许 bind。
> - 写错时的实测现象：coturn 日志 `Trying to bind fd 61 to <47.238.144.66:49189>: errno=99`（EADDRNOTAVAIL）+ `bind: Cannot assign requested address`，客户端 `Allocate` 返回 **508 (Cannot create socket)**。这也是本节第 10 节「external-ip 映射写错会导致 relay 地址异常」的具体化。
> - 修正后实测：STUN 返回 `UDP reflexive addr: 47.238.144.66:35866`；TURN 4 次 Allocate 成功、relay 落在 `49152–49200`、12/12 与 8/8 数据包经中继往返、丢包 0%、错误密码被正确拒绝。
> - 可复现配置：`code/webrtc-demo/deploy/turnserver.conf`（宿主机 `/etc/turnserver.conf` 的实际副本）；完整原始证据见 `reports/06-coturn.md` §4/§5。
> - 客户端 ICE server 规则（URL 形态/凭据/realm）与公网可达性阻塞项见接口契约 `doc/14-interface-contract.md` §7.7 与 C28。

`systemctl enable --now coturn`。

## 6. Go 信令部署（同机）
单二进制 `/opt/signaling/signaling`，systemd：
```
ExecStart=/opt/signaling/signaling -addr :8443 -turn turn:<公网IP>:3478 -user demo:demopass
```
监听 8443（建议 Caddy 反代上 TLS）。协议、消息、room 规则见 `02-architecture.md`。

## 7. 编译环境（同机）
- depot_tools（gclient/ninja/gn）。
- Android NDK r25c+、JDK 17。
- libvpx 源码（编 arm64 静态库给自研编码器链接）。
- 构建 libwebrtc Android arm64：`gn gen out/arm64 --args='target_os="android" target_cpu="arm64" is_debug=false'` → `ninja -C out/arm64`。
- 产物：`libwebrtc.a`（或分库 `.a`）+ 头文件，打包 scp 回 Windows。

## 8. 成本（一周）
| 项 | 花费 |
|---|---|
| 国内/香港大档（月付，用一周） | ~¥100+ |
| 域名（可选，用 IP 可省） | ¥0 |
| 合计 | ~¥100-112 |

## 9. 操作清单
1. 开国内/香港大档机，配安全组（UDP 3478 + 49152-49200，TCP 443/8443）。
2. SSH 登录，装 depot_tools + NDK + JDK + libvpx，编译 libwebrtc + 自研编码器，产物 scp 回 Windows。
3. 同机 `apt install coturn`，写配置，启动，测 STUN/TURN。
4. 编译 Go 信令，systemd 起服务。
5. 客户端连信令，验证 ICE / 媒体连通。

## 10. 风险与注意
- 务必确认厂商放行 UDP（部分国内云对 UDP 默认限制；香港轻量一般可配）。
- external-ip 映射写对，否则 relay 地址异常。
- 单机既编译又运行，编译时 CPU 占满会短暂影响 coturn/信令（学习 demo 可接受；若担心可错峰编译）。
- 后续改 libwebrtc 源码重编，直接 SSH 上这台即可，无需另租构建机。
