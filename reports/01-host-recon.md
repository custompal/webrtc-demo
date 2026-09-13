# 01 宿主机连通性、资源与环境核验（t1）

- 任务：t1（work，无依赖）— 宿主机连通性、资源与环境核验（SSH 前置检查）
- 执行人：env-installer
- 执行时间：2026-09-13 15:2x–15:3x（宿主机本地时间 CST，UTC+8）
- 结论速览：**有条件可以编译**（见 §8）。SSH 连通、网络可达性良好、apt 源可用；主要短板是 **4 vCPU(2 物理核+HT) / 7.1 GiB 内存 / 无 swap / 61 GB 可用磁盘**，均低于 `doc/01-cloud-infra.md` §3 与 `doc/08-libwebrtc-android-build.md` 的 ≥16 GB 内存、≥100 GB 磁盘建议值。

> 路径约定：宿主机 `/opt/dsh-workspaces` ＝ 容器内 `/data/dsh/home/workspace`（本报告 §4 再次实测确认）。下文以 `<WS>` 代表该目录；容器侧绝对路径为 `/data/dsh/home/workspace`。

---

## 1. 目标

在写任何代码前完成宿主机的正式环境核验：SSH 契约、OS/内核/CPU/内存/磁盘、工具链现状、网络可达性与实测下载速度、apt 源可用性、共享目录属主与权限，并复核对 captain 的初步探测，最后给出 libwebrtc 编译可行性结论与建议参数。

## 2. 执行步骤与关键命令

本报告所有数据均为 2026-09-13 实测输出（无推测值），命令分两组：

**A. 从容器内执行**（会话工作区，uid=1000 node）：

```bash
pwd; id; ls -la /data/dsh/home/workspace/
touch /data/dsh/home/workspace/code/webrtc-demo/.t1_write_probe   # 写权限探测
```

**B. 通过 SSH 在宿主机执行**（root@172.21.0.219:5766）：

```bash
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -i /home/node/.ssh/id_ed25519 root@172.21.0.219 -p 5766 '<cmd>'
```

关键探测命令清单：

```bash
id; hostname; uname -a; grep PRETTY_NAME /etc/os-release
lscpu | grep -E '^Model name|^CPU\(s\)|^Thread|^Core|^Socket|Vendor'
free -h; swapon --show
df -h / /opt/dsh-workspaces; df -i /; findmnt -T /opt/dsh-workspaces -o TARGET,SOURCE,FSTYPE,OPTIONS
for c in java javac go python3 git cmake ninja curl wget unzip zip tar xz clang gcc g++ pkg-config \
         autoconf automake libtool bison flex gperf make patch perl kotlin kotlinc gradle sudo; do ...; done
apt-cache policy openjdk-17-jdk cmake ninja-build autoconf automake libtool bison flex gperf pkg-config
curl -sS -m 15 -o /dev/null -w '%{http_code}' <url>                    # 连通性
curl -sSL -m 60 -o /dev/null -w 'speed=%{speed_download} B/s' -r 0-20000000 <url>   # 实测吞吐
getent passwd 1000; ss -lntup; ip -4 addr show scope global; curl -sS https://api.ipify.org
# 共享目录同一性：宿主机写标记 → 容器读；容器写标记 → 宿主机读
mkdir -p /opt/dsh-workspaces/code/webrtc-demo/reports
chown -R 1000:1000 /opt/dsh-workspaces/code/webrtc-demo/reports
```

## 3. 实测结果

### 3.1 SSH 与主机身份

| 项 | 实测值 |
|---|---|
| SSH 命令 | `ssh -i /home/node/.ssh/id_ed25519 root@172.21.0.219 -p 5766` ✅ 成功（每次均显式 `exit 0` 收尾） |
| 登录身份 | `uid=0(root) gid=0(root) groups=0(root)` |
| hostname | `iZj6cgbzeotp84twpfniy5Z` |
| 公网 IPv4 | `47.238.144.66`（`curl https://api.ipify.org`） |
| 内网 IPv4 | `172.21.0.219/18`（eth0, dynamic）；另有 docker0 `172.17.0.1/16`、br-390e82958ba3 `172.18.0.1/16` |
| 时区 | CST（UTC+8），`Sun Sep 13 15:29:17 PM CST 2026`；UTC 07:29:17 |
| ulimit -n | 65535 |

### 3.2 OS / 内核 / CPU / 内存 / 磁盘

| 项 | 实测值 | 评价 |
|---|---|---|
| OS | `Ubuntu 24.04.2 LTS`（noble） | 高于 doc 建议的 22.04，可用；但注意 `doc/01` 的 `apt install coturn` 等步骤以 24.04 包版本为准 |
| 内核 | `6.8.0-63-generic`，`x86_64` | 正常 |
| CPU | Intel(R) Xeon(R) Platinum，4 `CPU(s)`＝**2 物理核 × 2 线程(HT)**，1 socket | ⚠️ 建议值 ≥4 核（8 核更佳）；实际物理并行度仅 2 |
| 内存 | total **7.1 GiB**、available 5.8 GiB、**Swap 0 B** | ⚠️ 低于 doc/08 的 ≥16 GB 内存要求；**无 swap** 是链接阶段 OOM 的主要风险 |
| 磁盘 | `/dev/vda3` 69 G，已用 4.8 G，**可用 61 G**，ext4，`/` 与 `<WS>` 同一文件系统 | ⚠️ 低于 doc/01 的 ≥100 GB；61 G 对 libwebrtc 源码+SDK 属"够用但紧" |
| inode | 4570384 总量，已用 156363（**4%**） | 充足 |
| 挂载 | `findmnt -T /opt/dsh-workspaces` → TARGET `/`, SOURCE `/dev/vda3`, FSTYPE `ext4`, OPTIONS `rw,relatime` | 无独立挂载/配额，与根分区共享 |

实测命令原始输出（节选）：

```
CPU(s): 4
Vendor ID: GenuineIntel
Model name: Intel(R) Xeon(R) Platinum
Thread(s) per core: 2 ; Core(s) per socket: 2 ; Socket(s): 1
Mem: total 7.1Gi  used 1.3Gi  free 1.8Gi  buff/cache 4.2Gi  available 5.8Gi
Swap: 0B
/dev/vda3  69G  4.8G  61G  8%  /
```

### 3.3 工具链现状（宿主机）

| 工具 | 状态 |
|---|---|
| **已装** | git 2.43.0、python3 3.12.3、curl 8.5.0、wget 1.21.4、unzip、tar 1.35、xz 5.4.5、gcc/g++ 13.3.0、make、patch、perl、sudo（`/usr/bin/sudo`，root 本身已是 root）、apt（可用） |
| **缺失** | java/javac（JDK）、go、cmake、ninja、clang、pkg-config、autoconf、automake、libtool、bison、flex、gperf、yasm、nasm、kotlin/kotlinc、gradle、node/npm、zip |

> 说明：Go 工具链由 t13 负责（go-dev 已在容器内完成，实测 `<WS>/go/bin/go version` → `go version go1.22.12 linux/amd64`，`<WS>/env-go.sh` 已存在）。JDK/Android SDK/NDK/cmake/ninja 属 t4 范围。kotlin/gradle 由 Android 工程的 Gradle Wrapper 自带（无需系统安装），此处仅记录现状。

**apt 源可用性**：`apt-get` 可用，源为 `mirrors.cloud.aliyuncs.com/ubuntu noble{,-updates,-backports?} main/universe/...`（阿里云内网镜像，实测 `dists/noble/Release` 吞吐 1.54 MB/s）。候选版本实测：

```
openjdk-17-jdk = 17.0.20+8-1~24.04       cmake      = 3.28.3-1build7
ninja-build    = 1.11.1-2                pkg-config = 1.8.1-2build1
autoconf 2.71-3   automake 1.16.5-1.3ubuntu1   libtool 2.4.7-7build1
bison 3.8.2   flex 2.6.4   gperf 3.1-1    yasm 1.3.0-4   nasm 2.16.01-1
```

结论：**t4 所需组件全部有 apt 可装版本**（cmake 3.28.3 ≥ doc/05 要求的 3.22；ninja 1.11.1），JDK 17 亦可 `apt install openjdk-17-jdk`，无需外部下载。

### 3.4 网络可达性（HTTP 状态码实测）

| 目标 | 结果 |
|---|---|
| https://webrtc.googlesource.com/ | **200** |
| https://chromium.googlesource.com/ | **200** |
| https://dl.google.com/ | 302（正常重定向；实测下载 24 MB/s） |
| https://repo1.maven.org/maven2/ | **200** |
| https://proxy.golang.org/ | **200** |
| https://storage.googleapis.com/ | 400（裸请求无 bucket，属预期；非故障） |
| https://github.com/ | **200** |
| DNS 解析 | `webrtc.googlesource.com` → `2404:6800:4008:c06::52`（IPv6，可解析）；`dl.google.com` → `2404:6800:4005:805::200e` |

**实测吞吐（10–30 MB 分片，`%{speed_download}`）**：

| 源 | 吞吐 | 说明 |
|---|---|---|
| dl.google.com（`go1.22.12.linux-amd64.tar.gz`，30 MB 分片） | **20.2 MB/s** | 极佳；Android SDK/NDK/cmdline-tools 走同一 CDN |
| golang.google.cn（同 tarball） | 21.6 MB/s | 备用镜像同样快 |
| dl.google.com（cmdline-tools zip，20 MB 分片） | **24.4 MB/s** | Android SDK 下载无压力 |
| codeload.github.com（libvpx v1.14.1 tar.gz，20 MB） | 2.2 MB/s | 可用 |
| mirrors.cloud.aliyuncs.com（apt Release） | 1.54 MB/s | apt 可用 |
| goproxy.cn（模块 zip，20 MB） | 1.32 MB/s | 比官方代理快 |
| **proxy.golang.org（模块 zip，20 MB）** | **0.28 MB/s** | ⚠️ 很慢（t13 需注意；实测 HTTP 200 但吞吐低） |
| chromium.googlesource.com git clone（`chromium/src` shallow+blob:none，60 s 超时） | 60 s 仅得 **19 MB**（≈0.32 MB/s） | ⚠️ **本机最大的时间瓶颈**：libwebrtc `fetch webrtc` 需拉取数 GB 元数据/对象，按此速率可能耗时十小时级 |

**入方向/监听端口**：`ss -lntup` 显示宿主机仅监听 `5766`（sshd，本 SSH 通道）、`5866`（1panel-core）、`10453`（docker-proxy → DSH 容器 UI）、53/68/323。**3478/49152-49200/8443 均未占用**，coturn 与 Go 信令可安全绑定。`ufw status` → **inactive**；`iptables -t nat PREROUTING` 仅有 Docker 链，无自行添加的阻断规则。⚠️ 云厂商安全组在宿主机外，**UDP 3478 与 relay 段 49152-49200 能否从公网进入无法在本机验证**，需 t6/t12 用外部视角确认（列入 §9 未决项）。

### 3.5 共享目录同一性与属主（关键运维结论）

- 宿主机根目录列表（`/opt/dsh-workspaces/`）：`.agent-teams/`、`android-sdk/`、`code/`、`.dl/`、`env-go.sh`、`go/`；属主均为 `admin:admin`（= **uid 1000**，`getent passwd 1000` → `admin:x:1000:1000`）。
- 容器内同目录看到的是 `node:node`（uid 1000）——两侧是同一目录、同一 uid，属主显示名不同只是两个系统里的用户名不同。
- **同一性实测**：宿主机写入 `code/webrtc-demo/reports/.write_test_host.txt` → 容器内 `cat` 立即读到内容 `host-write-1789284509` ✅；容器内后续在 `reports/` 下写入成功 ✅（`REPORTS_WRITE_OK`）；标记文件已删除。
- ⚠️ **属主陷阱（已复现并已修复）**：宿主机以 root 执行 `mkdir -p .../reports` 后，该目录在容器内属主为 `root:root`，容器内 `node` 写入**被拒**（`Permission denied` + DSH 沙箱提示 `file access denied`）。已按契约执行 `chown -R 1000:1000 /opt/dsh-workspaces/code/webrtc-demo/reports && chmod 775` 后恢复正常。
  → 后续所有"宿主机 root 在共享区建目录/文件"的操作**必须**紧跟 `chown -R 1000:1000 <路径>`。

## 4. 对 captain 初步探测项的复核结论

| captain 探测项 | 复核结果 |
|---|---|
| SSH 连通已成功 | ✅ **一致** |
| hostname `iZj6cgbzeotp84twpfniy5Z` | ✅ 一致 |
| x86_64 / nproc=4 | ✅ 一致（补充：4 = 2 物理核 × 2 线程） |
| 内存 total 7GiB / available ≈5GiB | ✅ 一致（实测 total 7.1 GiB，available 5.8 GiB） |
| 磁盘 /dev/vda3 69G / 可用 62G | ✅ 一致（实测可用 61 G，随使用波动） |
| 工作区根 `/opt/dsh-workspaces`，其下 android-sdk/ | ✅ 一致，并**再次用标记文件实测**同一性 |
| 已装 git 2.43.0 / python3 3.12.3 / curl 8.5.0 / wget 1.21.4 / unzip | ✅ 一致 |
| 缺少 cmake / ninja / java / go | ✅ 一致（补充缺少项：clang、pkg-config、autoconf、automake、libtool、bison、flex、gperf、yasm、nasm、kotlin、gradle、zip） |
| webrtc.googlesource / dl.google / repo1.maven / proxy.golang **均 200** | ✅ 状态码一致；⚠️ **补充校正**：`proxy.golang.org` 虽 200 但实测吞吐仅 0.28 MB/s，仅凭 200 判断"可用"会误导（t13 已选 go1.22.12 官方 tarball，走 dl.google.com 20 MB/s，实际不受影响） |
| 公网 IP 47.238.144.66 | ✅ 一致 |

## 5. 遇到的问题及自行解决的尝试

1. **容器内写 `reports/` 被拒（Permission denied）**：原因为宿主机 root 创建的目录属主 root:root。解决：宿主机 `chown -R 1000:1000` + `chmod 775`，复测写入成功。已固化为 §3.5 的约定。
2. **首次在宿主机创建 `reports/` 时未同步属主**：同上，属流程性失误，已修正且未留下 root 属主残留（目录现为 `node:node`，`drwxrwxr-x`）。
3. **`proxy.golang.org` 吞吐异常低**：用同一模块 zip 分别测 proxy.golang.org（0.28 MB/s）与 goproxy.cn（1.32 MB/s）、并用 dl.google.com 测大文件（20–24 MB/s）做对照，确认是**该代理链路慢**而非整机网络差；已把结论写进报告供 go-dev / webrtc-builder 参考。
4. **chromium 源码 clone 速率需专门评估**：用 `git clone --depth 1 --filter=blob:none --no-checkout https://chromium.googlesource.com/chromium/src /tmp/src-test` 限时 60 s 实测得 19 MB，换算 ≈0.32 MB/s，据此给出 §8 的时间预估（而非凭猜测）。测试目录已 `rm -rf`，未占用磁盘。

以上问题均自行解决，无阻塞，无失败重试消耗。

## 6. 结果与产物

| 交付物 | 绝对路径 |
|---|---|
| 本报告 | `/data/dsh/home/workspace/code/webrtc-demo/reports/01-host-recon.md`（宿主机侧 `/opt/dsh-workspaces/code/webrtc-demo/reports/01-host-recon.md`） |
| 修正后的报告目录属主 | `/data/dsh/home/workspace/code/webrtc-demo/reports/` → `node:node 775` |

未产生其它文件变更（写权限探测/标记文件均已删除）。

## 7. 资源红线与需人工介入项

1. **无 swap + 7.1 GiB 内存**：libwebrtc 链接阶段（`libwebrtc.a`/`libjingle_peerconnection_so.so`，单条 lld 命令峰值可达数 GB）有 OOM 风险。**建议在 t4/t5 前创建 4–8 GB swapfile**（`fallocate -l 6G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`）。⚠️ 宿主机根盘仅 61 GB 可用，swapfile 会占用等量磁盘，需权衡（6 GB ≈ 10% 余量）。**此项涉及宿主机全局资源，请 captain 决定是否授权执行**。
2. **云安全组入方向未验证**：UDP 3478 与 49152-49200（TURN relay）、TCP 8443（Go 信令）是否放行由云控制台决定，本机无法自证。t6/t12 需用宿主机外的视角（另一台机器 / 公网 STUN 探测）验证 UDP 可达性。
3. **磁盘水位**：libwebrtc 完整源码+deps（含 android_toolchain prebuilts、clang、SDK）预估占用 20–30 GB，NdK/SDK 另 5–8 GB。**建议警戒线：剩余空间 < 15 GB 时暂停并清理**（可删 `out/`、`src/third_party/*/tmp`、`.git` 里的 pack 冗余）。若 t5 失败重编，需重新评估。
4. **宿主机承载生产性组件**：本机同时跑着 `1panel-core`（:5866，公网可达）、DSH 容器（:10453）与本 SSH 通道（:5766）。t4 的 apt 安装、t6 的 coturn、t12 的 systemd 服务**不要改动/重启** sshd、1panel、docker 相关配置，避免自断通道。

## 8. 可行性结论：**有条件可以编译**

**判定依据**

- 支持项：SSH 稳定；apt 源与 dl.google.com（20–24 MB/s）可用，JDK/SDK/NDK/cmake/ninja 全部可得；磁盘 61 GB + inode 4% 足以容纳源码与产物；内核 6.8/Ubuntu 24.04 对 depot_tools + NDK 无已知障碍；关键源站均可达。
- 限制项：**内存 7.1 GiB 且无 swap**，低于 doc/08 的 ≥16 GB；**磁盘 61 GB**，低于 doc/01 的 ≥100 GB；**CPU 有效并行度 2 核**，编译时间将显著长于文档中"几小时"的预期；**chromium.googlesource 拉取速率 ≈0.32 MB/s**，`gclient sync` 阶段可能是数小时到十小时级的最长环节。

**建议参数（交 t5/webrtc-builder 执行）**

| 参数 | 建议值 | 理由 |
|---|---|---|
| `ninja -j` | **先 `-j2` 编译；链接阶段单独 `-j1`**（`ninja -C out/arm64 -j2`；如需链接用 `-j1`） | 无 swap 时 4 路并行峰值会触顶 7.1 GiB；宁慢不 OOM |
| `is_debug` | `false` | doc/08 一致 |
| `symbol_level` | **0** | 显著降低 `.o`/链接内存与磁盘占用 |
| `is_component_build` | 保持默认（`false`，Android 静态/单体 `libjingle_peerconnection_so.so`）；如需降低单条链接内存可选 `true`，但会产出大量 `.so`，与 doc/05 的 `libwebrtc.a` 链接方式不兼容，**须先与 architect 确认** | 避免与 CMake 链接契约冲突 |
| `rtc_use_h264` / `proprietary_codecs` | 按 doc/06 契约 | 由 architect 冻结 |
| `target_os/target_cpu` | `android` / `arm64` | doc/08 |
| `gclient sync` | 加 `--no-history`（shallow）；失败再退 `--shallow` | 降低拉取量，缓解 0.32 MB/s 瓶颈 |
| swap | 建议 ≥4 GB | 见 §7.1，需 captain 授权 |
| 磁盘监控 | 每 10 min 记录 `df -h /`，<15 GB 告警 | 见 §7.3 |
| OOM 兜底 | 若发生 OOM：降至 `-j1`、确认 `symbol_level=0`、清理 `out/` 重来（增量重跑可行） | doc/08 的排障表 |

**风险等级**：中。若接受"编译时长可能 6–15 小时 + 需人工盯 OOM/磁盘"，则可行性成立；否则需升级宿主机规格（≥8 核 / ≥16 GB / ≥100 GB）。

## 9. 未解决 / 需人工介入

1. 云安全组 UDP（3478、49152-49200）与 TCP 8443 的放行状态——需 captain/用户在云控制台确认，或由 t6/t12 从外部视角实测。
2. swapfile 是否创建（消耗 ~6 GB 磁盘换 OOM 保险）——需 captain 决策（§7.1）。
3. 宿主机规格（4 vCPU/7.1 GiB/61 GB）低于 doc 建议值属既定事实，若对编译时长有硬性要求，需用户升级实例规格；本报告不做建议变更实例的结论，仅记录实测。
4. `doc/01` 要求 Ubuntu 22.04，实测 24.04.2；`apt install coturn` 在 24.04 上包名为 `coturn`（t6 验证）——属文档与实测的偏差记录，供 architect/coturn-installer 参考。

---

# 修订（2026-09-13 15:39 CST，by env-installer）

> 本节由 captain 决策后**追加**，原正文（§1–§9）一字未改，以保证 t1 已验收结论的可追溯性。原 §7.1「建议创建 swapfile，需授权」与 §9.2「是否创建 swap 待决策」在本节已被实际执行结果取代。

## R1. 修正：libwebrtc/libvpx 源码拉取时间的测量误差（重要）

**原文表述**（§3.4 与 §8）："chromium.googlesource git clone 仅 ≈0.32 MB/s（60 s 得 19 MB）→ `gclient sync` 预计是最长环节（数小时～十小时级）"。

**修正**：该 0.32 MB/s 来自 **`git clone --filter=blob:none --no-checkout`（部分克隆/部分取 blob）** 的测量，**属于测量方法误差**——部分克隆在 checkout 阶段才按需回源逐个取 blob，吞吐极低，不能代表普通克隆速率。

> **R1 补充（2026-09-13 16:15，by env-installer）— 因果归因存在异议，以本条为准：**
> webrtc-builder 复测后提出了**另一种解释**：它在**不加 `--filter` 的纯 `git clone --depth 1`** 上也曾实测到 460 KB/30 s，随后重测又得 37 MB/<60 s。据此它认为真实原因是 **超大仓吞吐波动 + 其自身并发测试占用了带宽**，而非 `--filter=blob:none` 本身；它已在 `reports/02-webrtc-network-recon.md` 顶部更正了自己的测量结论。
> **我的立场**：我保留自己那次的原始测量事实（t3 的 `--depth 1` 实测 2.2 s / 4.8 s，见下），但在**因果归因上接受存在不确定性**——单次测量既可能是部分克隆的 blob 回源开销，也可能是带宽波动/并发干扰，**本报告不做定论**。
> **对后续任务的操作结论（不受争议影响）**：① 不必为网络做额外优化；② `--no-history` / shallow 仍保留，但理由改为**省磁盘**（caps 契约与 t5 现状一致）；③ 任何"数小时级"的时间预估都应按"不可靠"对待，以 t5 的实时进度为准。

**t3 实测（同一台宿主机、同一网络，2026-09-13 15:31）**：

```
$ git submodule add --depth 1 https://chromium.googlesource.com/webm/libvpx third_party/libvpx-src
real 0m2.188s    Total 1344 (delta 97), 5.87 MiB | 5.79 MiB/s   → 工作树 27 MB

$ git submodule add --depth 1 https://webrtc.googlesource.com/src third_party/libwebrtc-src
real 0m4.834s    Total 8136 (delta 642)                          → 工作树 120 MB（≈25 MB/s）
```

**结论**：`--depth 1` 浅拉取下，libwebrtc 源码本体约 **120 MB / 4.8 s ≈ 25 MB/s**，与 dl.google.com 的量级一致；**源码拉取不是瓶颈**。`gclient sync` 仍需拉取 DEPS 依赖（`android_toolchain` prebuilts、clang、其余 third_party），其耗时**不能**用本次数字外推，`--no-history` 策略依然建议保留（§8 参数表其余项不变）。
（详见 `reports/03-git-submodules.md` §2、§3。）

## R2. 已执行：4 GB swapfile 创建与启用（captain 授权，用户同意）

```bash
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab
```

实测证据（2026-09-13 15:38 CST）：

```
$ swapon --show
NAME      TYPE SIZE USED PRIO
/swapfile file   4G   0B   -2

$ free -h
               total   used   free  shared  buff/cache  available
Mem:           7.1Gi  1.4Gi  236Mi   7.6Mi       5.7Gi      5.7Gi
Swap:          4.0Gi     0B   4.0Gi

$ cat /proc/swaps
Filename        Type  Size      Used  Priority
/swapfile       file  4194300   0     -2

$ grep swapfile /etc/fstab
/swapfile none swap sw 0 0

$ df -h /   →  69G  11G  56G  16% /      # 扣掉 4 GB swap 后仍余 56 GB
```

- **`vm.swappiness=10` 已执行并持久化**（captain 2026-09-13 15:41 指令；本节原写的"未执行、维持 0"已作废，以本行为准）：`/etc/sysctl.d/99-webrtc-demo.conf` 写入 `vm.swappiness = 10`；实测 `sysctl vm.swappiness` → `vm.swappiness = 10`、`/proc/sys/vm/swappiness` → `10`。
  - ⚠️ **覆写陷阱（已解决）**：阿里云默认 `/etc/sysctl.conf` 第 1 行硬编码 `vm.swappiness = 0`，且 `sysctl --system` 把该文件排在最后读取 → **覆盖** drop-in 的 10，首次改动会被打回 0。已备份 `/etc/sysctl.conf.bak-webrtc-demo-20260913-154136` 后把该行注释（其余内核参数未动），`sysctl --system` 后稳定为 10。回滚方法见 `reports/04-env-install.md` §A1.1。
  - 说明：`swappiness=0` 在 Linux 上并非"完全禁用 swap"，但会让内核在内存尖峰时**优先 OOM kill 而非换出匿名页**，削弱 4 GB swap 的保险意义；设为 10 后优先换出。当前 `free -h` 显示 Swap 已用 524Ki，证明换出路径可用。
- fstab 已写入，重启后自动启用。

## R3. 新增：工作区内缓存/临时目录约定（写入 env.sh / env-container.sh）

**背景（captain 转述的实测）**：容器 `$HOME=/data/dsh/home` 在 workspace-write 沙箱下**不可写**（go-dev 实测默认缓存直接 permission denied）；且 `/tmp` 下生成的可执行文件会被拒执行。

**已在两个环境脚本中显式导出（可用同名环境变量覆盖）**：

| 变量 | 值 | 说明 |
|---|---|---|
| `GRADLE_USER_HOME` | `<WS>/.gradle-home` | 已建目录（1000:1000）；宿主机 t10 与容器内共用同一约定 |
| `TMPDIR` | `<WS>/tmp` | 已建目录（1000:1000） |
| `GOCACHE` | `<WS>/go/cache` | 由 `env-go.sh`（t13）导出，本脚本不覆盖 |
| `GOTMPDIR` | `<WS>/go/tmp` | 由 `env-go.sh`（t13）导出，本脚本不覆盖 |

实测（宿主机，以 uid 1000 身份）：

```
$ sudo -u admin -H bash -lc ". /opt/dsh-workspaces/env.sh >/dev/null; \
    mkdir -p \$GRADLE_USER_HOME/caches && test -w \$GRADLE_USER_HOME/caches && echo GRADLE_CACHE_WRITABLE=yes; \
    touch \$TMPDIR/probe && echo TMPDIR_WRITABLE=yes"
GRADLE_CACHE_WRITABLE=yes
TMPDIR_WRITABLE=yes

$ bash -lc '. /opt/dsh-workspaces/env.sh --print'   # 摘要
GRADLE_USER_HOME = /opt/dsh-workspaces/.gradle-home
GOCACHE          = /opt/dsh-workspaces/go/cache   GOTMPDIR = /opt/dsh-workspaces/go/tmp
TMPDIR           = /opt/dsh-workspaces/tmp
```

容器内同理（`env-container.sh --print`）：`GRADLE_USER_HOME=/data/dsh/home/workspace/.gradle-home`、`TMPDIR=/data/dsh/home/workspace/tmp`。

## R4. 本节同时确认的其它事实

- **swap 结论对 §8 参数表的影响**：§8 中 "swap | 建议 ≥4 GB" 已落实为 4 GB；其余参数（`ninja -j2`、链接 `-j1`、`is_debug=false`、`symbol_level=0`、磁盘 <15 GB 告警）**维持不变**。
- **云安全组**：用户正在阿里云控制台放行 UDP 3478 / 49152-49200 与 TCP 8443；本报告 §3.4 的"入方向无法本机自证"结论仍然有效，最终以 t6/t12 的外部实测为准。
- **属主**：`/swapfile` 为 `root:root 600`（swap 文件不参与共享区属主约定，属 root 专属，无需 chown）。

