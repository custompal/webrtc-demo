# web/ — 浏览器视频通话 Demo：运行、无头验证与联调手册

> Owner: `ops` · Task: t3（attempt 1）· 契约：`reports/70-browser-call-demo-requirements.md`
> 适用对象：要**跑起来**、要**复现**、要**排障**的人。观测面只有浏览器控制台 + 页面面板（不引入 App/服务端日志通道）。

本目录是纯静态 ES Module 站点：**无构建、无 bundler、无 npm 依赖**。本手册覆盖三件事：

1. 怎么把页面跑起来并和真机 App 互拨；
2. 怎么用容器内自举的无头 Chrome 把 §8 A-3 的断言跑成 PASS/FAIL；
3. 出问题时怎么定位（失败矩阵 §5）。

### 落点分工（captain 修订 r1，必须分清）

| 脚本 | 在哪跑 | 作用 | 依赖 |
|---|---|---|---|
| `scripts/web-demo-verify.sh` | **容器内** | 自举 Chrome 到**交付路径之外**的缓存目录 → 起 Node 静态服务 → 驱动双页断言。**无头媒体验证的主路径** | 只需容器内 `node ≥ 22`；**不依赖宿主机预置**，也不依赖任何 `tmp/` 素材（缓存可从零自举） |
| `scripts/serve-web-demo.sh` | **宿主机** | 面向**用户 Chrome** 的静态页（VSCode 端口转发的目标解析在宿主机上） | 只需 `python3`（**运行**而非安装）；宿主机无 python3 时才回退到 node |

* 容器内的静态服务只服务于无头验证，**不需要转发**；面向用户的页面必须由宿主机那一条提供。

#### 为什么用户页面**只能**在宿主机（t8-amend-6，实测）

- 容器**只发布了 `10453->8443`**（信令），**没有任何 8081 的端口发布**。因此在宿主机上访问 `127.0.0.1:8081`、容器 IP `172.18.0.2:8081`、网关 `172.18.0.1:8081` **全部 `curl: (7) Failed to connect`**；只有容器内部 `curl 127.0.0.1:8081/` 才是 200。
- 用户的 VSCode Remote-SSH 连的是**宿主机** sshd，PORTS 转发也只能从宿主机取端口 → 用户浏览器里的 `http://localhost:8081` **必须**由宿主机上的 `scripts/serve-web-demo.sh start`（或等价的 `python3 -m http.server`）提供。
- **容器内起静态服务只用于自动化/无头验证**：无头 Chrome 与页面同在容器内，所以容器 8081 对它们可见；对**用户**却完全不可见。不要以为「在容器里起动一下就行」。
- 因此：**用户路径 = 宿主机**（§1、§3）；**验证路径 = 容器**（§2）。两条路径的服务不通用，端口也不互通。

* 宿主机等价命令（脚本默认解析到仓库内的 `web/`，与 `serve-web-demo.sh start` 等价）：

```bash
python3 -m http.server 8081 --bind 127.0.0.1 --directory <repo>/web
```

* 「宿主机零安装」是硬约束：`serve-web-demo.sh` 的 start/status/stop 与就绪探测都**不要求 node**（探测用 bash `/dev/tcp`，curl 兜底）。

---

## 0. 端口与地址：三件容易搞错的事（先读这一段）

| 用途 | 地址 | 是否经 VSCode 转发 |
|---|---|---|
| 打开页面（secure context 来源） | `http://localhost:8081/` | **是**（PORTS 面板转发 8081） |
| 页面连信令（公网/真机） | `ws://47.238.144.66:8443/ws` | **否，8443 不需要转发** |
| 容器内工具连信令 | `ws://172.18.0.1:8443/ws`（bridge 网关 = 宿主机） | 否 |

* **8443 只有明文 HTTP/WS**：`https://…:8443` 不可用（TLS 握手失败）。页面/脚本里出现 `https://…:8443` 或 `wss://…:8443` 一定是错的。
* **`127.0.0.1:8443` 不是信令服务**：在本容器里该端口是 DSH Harness 自己的 Caddy（`tls internal`，反代 `127.0.0.1:3080`），访问它会得到 `400 Client sent an HTTP request to an HTTPS server`。脚本会直接拒绝该地址（`exit 2`）。
* 上面「两个 localhost」不是同一个东西：**8081 是页面来源，8443 是信令端点**，不要混写。
* **用户侧端口转发只针对 8081**（VSCode PORTS 面板加一条即可）；信令 `ws://47.238.144.66:8443/ws` 由页面直连公网明文端点，**不需要、也不要转发 8443**。静态页由**宿主机**上的 `scripts/serve-web-demo.sh` 提供（见 §1）；无头验证用的容器内静态服务不需要转发。
* **用户侧的一切都在宿主机上完成**：宿主机仓库路径是 `HOST: /opt/dsh-workspaces/code/webrtc-demo`（用户 VSCode Remote-SSH 连的就是这台宿主机的 sshd，其终端就是宿主机 shell）。脚本按自身位置解析仓库根，因此 `bash /opt/dsh-workspaces/code/webrtc-demo/scripts/serve-web-demo.sh …`（即 `HOST: ` 前缀路径）直接可用；容器内的路径与用法只出现在 §2 的容器/CI 小节。

真机联通性 preflight（在**你笔记本的 Chrome** 或任何能上公网的机器上）：

```
http://47.238.144.66:8443/healthz
```

验证口径见 `signaling/server/server.go:117`：期望 200 + JSON，含 `roomGraceSec`（= 90）、`stunUrl`（`stun:47.238.144.66:3478`）、`turnUrl`（`turn:47.238.144.66:3478?transport=udp`）（字段发射点：`signaling/server/server.go`）。**不通就不要继续排查浏览器**：那是阿里云安全组没放行 8443/tcp。加规则路径：ECS 控制台 → 安全组 → 入方向 → 手动添加 → 协议 TCP、端口 `8443/8443`、源 `0.0.0.0/0`（或你的出口 IP）。

---

## 1. 启动静态页（宿主机，面向用户 Chrome）

```bash
bash scripts/serve-web-demo.sh start          # 默认 8081，root = <repo>/web，引擎自动选择
bash scripts/serve-web-demo.sh status
bash scripts/serve-web-demo.sh restart
bash scripts/serve-web-demo.sh stop
```

* 可选参数：`--port N`、`--root DIR`、`--host H`、`--engine auto|node|python3`。
* 运行期目录默认 `HOST: /tmp/web-demo-serve-<uid>/`（按用户隔离，见上）；`WEB_DEMO_RUN_DIR` 可覆盖。
* 引擎 `auto`：**优先 `python3 -m http.server <port> --bind <host> --directory <root>`**（宿主侧零安装，契约 A-1）；本容器没有 python3，会自动落到 `web/tests/lib/serve.mjs`（node，带逐请求日志）并在启动时打印原因。也可显式 `--engine python3|node`。
* **start/status/stop 与就绪探测都不要求 node**：探活原语按 `bash /dev/tcp → nc -z → python3 socket → node` 顺序自动选择第一个真正可用的，`start` 会打印选中的原语（如 `liveness : devtcp probe on 127.0.0.1:8081`）。**四种都不可用时命令以 EXIT=2 明确失败**（打印每种原语的失败原因），绝不静默回报「未监听」；也可用 `WEB_DEMO_PROBE=auto|devtcp|nc|python3|node` 指定（取错值即 EXIT=2）。
* 运行期文件放在**按用户隔离的目录**里（`WEB_DEMO_RUN_DIR` 可覆盖，默认 `HOST: /tmp/web-demo-serve-$(id -u)/`）：`web-demo-serve.pid`（PID）、`web-demo-serve.state`（记录该实例实际服务的 `port/host/engine/root`）、`web-demo-serve.log`（服务输出）。按 uid 隔离是为了避免同一台宿主机上不同用户互相踩到对方的陈旧文件（`fs.protected_regular=2` 时他人属主的文件连 root 都打不开）。
* 退出码：`0` 启动成功 / 正在运行 / 已停止；`1` 未运行、端口被占、启动未就绪、`stop` 未能释放端口；`2` 用法或环境错误。

### 1.1 SSH 非交互调用（t5 用的就是这条通道）

脚本不依赖 TTY、不弹任何提示：服务用 `setsid` + `nohup` 脱离会话（SSH 断开后继续存活），`start` 总会打印**实际监听地址:端口**。

```bash
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -i /home/node/.ssh/id_ed25519 -p 5766 root@172.18.0.1 \
    'bash /opt/dsh-workspaces/code/webrtc-demo/scripts/serve-web-demo.sh start'
```

成功输出（实测，宿主 python3 3.12.3）：

```text
note: serving with python3 -m http.server (run, not installed)
started     : /opt/dsh-workspaces/code/webrtc-demo/web
listening   : 127.0.0.1:8081
url         : http://localhost:8081/
pid         : 2795328 (engine python3)
pid file    : /tmp/web-demo-serve.pid
log file    : /tmp/web-demo-serve.log
next        : VSCode PORTS 面板转发 8081 → 在 Chrome 打开 http://localhost:8081/
signalling  : dialled directly as ws://47.238.144.66:8443/ws — 8443 must NOT be forwarded
```

* `status` 用于独立会话复查（`0` 运行中且端口可连；`1` 未运行或状态不一致）；`stop` 会**核对端口确实已释放**再返回 `0`，否则返回 `1`。
* 端口被占用（无论被谁占用）**一律报错退出 1，绝不静默换端口**；同一脚本实例已在别的端口运行时，`start --port <新端口>` 也会拒绝并提示改用 `WEB_DEMO_RUN_DIR`。
* 硬化处理：宿主 `/tmp` 上 `fs.protected_regular=2` 时，他人所有（或上次以别的身份创建）的 `HOST: /tmp/web-demo-serve.log` 连 root 也打不开；脚本会**先明确提示再删除该陈旧文件**后重试，仍失败才报错退出。

### 1.2 停止与清理

```bash
bash scripts/serve-web-demo.sh stop                  # 停服务（并核对端口已释放）
rm -rf /tmp/web-demo-serve-$(id -u)   # 清理本用户的运行期文件（pid/state/log）
bash scripts/web-demo-verify.sh --clean              # 清理容器内自举缓存（约 426 MiB）
```

---

### 1.3 t3-amend-4 登记：宿主机零 node 实测（原始输出）

宿主机环境（用户 VSCode Remote-SSH 的落点）：`admin@…:5766`，工作区 `HOST: /opt/dsh-workspaces/code/webrtc-demo`（`HOST: /home/admin/webrtc-demo` 为其符号链接）；实测 `node=NONE`、`python3=/usr/bin/python3`、`bash=5.2.21`。

```text
$ bash scripts/serve-web-demo.sh start            # auto 引擎，零 node
note: serving with python3 -m http.server (run, not installed)
listening   : 127.0.0.1:8081    pid 3092279 (engine python3)
liveness    : devtcp probe on 127.0.0.1:8081
START=0
$ bash scripts/serve-web-demo.sh status                        → STATUS=0
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/lib/signaling.js
200
$ bash scripts/serve-web-demo.sh stop
released    : 127.0.0.1:8081 (verified closed)                  → STOP=0

$ bash scripts/serve-web-demo.sh start --engine python3         → START_PY=0
$ curl … /lib/signaling.js                                      → 200
$ bash scripts/serve-web-demo.sh stop                           → STOP_PY=0

# 证明 --engine python3 不触碰 $NODE：把 WEB_DEMO_NODE 指向 marker wrapper
$ WEB_DEMO_NODE=HOST:/tmp/node-marker.sh bash scripts/serve-web-demo.sh start --engine python3
START_PY_MARKER=0 · HTTP 200 · marker 文件未被创建 ⇒ $NODE 从未执行
```

要点（对应裁定 1–5、8）：探活**首选 bash 内建** `/dev/tcp`，回退链 `/dev/tcp → nc -z → python3 socket → node`；`require_node_for_probe` 已删除，`start`/`status` 在零 node 下可用；node 退化为「容器内首选静态引擎（带逐请求日志）」，不是前置条件；帮助与启动提示不再声称需要 node；`--print-chrome-path`/`--print-sysroot` 语义未变（仍为纯值、冷缓存先自举、失败 exit 2）。宿主机侧只**运行**已有的 `python3`，未安装任何东西。

## 2. 无头 Chrome 验证环境 —— **容器内 / 由团队与 CI 执行**（宿主机零改动）

> 本节所有命令都在**容器内**运行，只服务于自动化验证与 CI；**宿主机侧用户流程见 §1**（用户 VSCode Remote-SSH 连的是宿主机 sshd，其终端是宿主机 shell；宿主机**没有 node**，静态页由 `python3` 起）。

**验收项变更登记（captain 裁定 t3-amend-1，逐字）**：

> t3 的验收项「在**宿主机**准备无头 Chrome 验证环境（安装 node 与 Chrome）」**由本裁定取代**为：**在容器内自举**（脚本首次运行下载并解包到可配置缓存目录，可重复、可回退、可 `--clean`）。**不得修改宿主机**（不装 node/Chrome、不动系统包）。理由：已证实可行且避免改动生产主机。

**captain 修订 r1（登记；`reports/70` 不改，修订落在 `reports/71/72/73`）**：

> 1) **t5 的媒体验证主路径 = 容器内**：chrome-headless-shell 自举（缓存在交付路径之外）+ Node 静态服务在容器内 `localhost:<port>` → 无头双页互拨断言。**宿主机路径降级为可选**；手工矩阵只在**容器与宿主机两条路都失败**、且给出原始报错时才允许启用，并必须显式标注未由无头环境验证。
> 2) 两个脚本落点分清：`scripts/web-demo-verify.sh`（**容器内**，自举到交付路径外的缓存、起 Node 静态服务、驱动双页断言，不依赖 `tmp/chrome-env/**`、不依赖宿主机预置）；`scripts/serve-web-demo.sh`（**宿主机**，面向用户 Chrome，`python3 -m http.server 8081 --directory <repo>/web`，属**运行**而非安装）。
> 3) 门禁扰动校验照旧：新增脚本前后 `doc-verify.sh` 全量与 `--only` 的 checks/warnings 一致。

本手册按 r1 执行：文首「落点分工」表把两条路径分开；缓存默认落在 **`${XDG_CACHE_HOME:-$HOME/.cache}/web-demo-chrome-cache`**（本容器为 `HOST: /data/dsh/home/.cache/web-demo-chrome-cache`、宿主机 admin 为 `HOST: /home/admin/.cache/web-demo-chrome-cache`，都在 69 GiB 主盘上）；**绝不**落在 `${TMPDIR:-/tmp}`，因为本镜像 `/tmp` 只有 256 MiB 而完整缓存需 426 MiB。**若该处不可写**（本容器的文件沙箱只允许写工作区，实测 `mkdir` 报 EACCES），或 `HOME`/`XDG_CACHE_HOME` 都未设，则自动回退到 **`<repo>/../web-demo-chrome-cache`** 并在 stderr 打印 `note: … is not writable here; falling back to …`，`WEB_DEMO_CACHE` 可覆盖；**从零自举**已在默认路径实测通过；`serve-web-demo.sh` 的 start/status/stop 与就绪探测均不需要 node。

**容器/CI 小节（本节只讲容器内路径，与用户侧无关）**：容器内仓库路径为 `/data/dsh/home/workspace/code/webrtc-demo`，与宿主机 `HOST: /opt/dsh-workspaces/code/webrtc-demo` 是同一份工作树（bind mount，实测脚本 sha256 相同）。容器无 `python3`、无 root、无 `unzip`，因此本节的自举与断言全部在容器内用 node + 交付路径外的缓存完成；容器内的静态服务只服务于无头断言，**不需要端口转发**。

### 2.1 自举了什么（下载来源、钉版、体积，逐字披露）

| 组件 | 版本 | 来源 | 体积 |
|---|---|---|---|
| chrome-headless-shell | **154.0.8037.57**（钉版，`WEB_DEMO_CHROME_VERSION` 可覆盖） | `https://storage.googleapis.com/chrome-for-testing-public/154.0.8037.57/linux64/chrome-headless-shell-linux64.zip` | 114.9 MiB（解包 261.4 MiB） |
| Debian 运行库索引 | bookworm main amd64 | `https://deb.debian.org/debian/dists/bookworm/main/binary-amd64/Packages.gz` | 12.1 MiB |
| Debian 运行库 | 31 个包（`libnss3`、`libglib2.0-0`、`libgbm1`、`libasound2`、`libx11-6`、`fonts-liberation` 等） | 同一 Debian 镜像的 `.deb` | 7.8 MiB（解包 30 MiB） |
| 缓存总计 | — | `${WEB_DEMO_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/web-demo-chrome-cache}` | 约 426 MiB |

镜像里**没有**任何浏览器，也没有 `unzip`/`python3`/`ar`/root：因此解包用 `node:zlib`（自写 ZIP 读取，见 `web/tests/lib/zip.mjs`），运行库用 `dpkg-deb -x` 解到私有 sysroot，再用 `LD_LIBRARY_PATH` 运行。运行库闭包由 `Packages` 索引解析得到（`web/tests/lib/deb.mjs`），无需人工维护清单。

```bash
bash scripts/web-demo-verify.sh --print-cache  # 缓存路径 / 体积 / 内容 / 钉版号
bash scripts/web-demo-verify.sh --clean         # 删除缓存（幂等，打印释放量）
```

**⚠️ 缓存目录必须能放下 ≈426 MiB（自举前预检 ≥1.2 GiB 可用）**：本镜像的 `/tmp` 是 **256 MiB 的 tmpfs**，因此默认缓存**不在** `/tmp`：优先 `${XDG_CACHE_HOME:-$HOME/.cache}/web-demo-chrome-cache`（容器 `HOST: /data/dsh/home/.cache/…`、宿主机 `HOST: /home/admin/.cache/…`，均在 69 GiB 主盘），不可写时回退 `<repo>/../web-demo-chrome-cache`；`WEB_DEMO_CACHE` / `--cache DIR` 可覆盖。若把缓存手动指到小卷（例如 `/tmp/...`），自举前的空间预检会以 **EXIT=2** 明确拒绝并给出原因，**不会**留下半个缓存、也不会跑成裸 ENOSPC。需要显式指定时：

```bash
export WEB_DEMO_CACHE=$HOME/web-demo-chrome-cache   # 只要在**仓库之外**且所在卷有 ≥1.2 GiB 可用空间即可
```

**🚫 禁止把缓存放进仓库（含 `<repo>/tmp/**`）**：仓库**没有** `tmp/` 的忽略规则（实测 `git check-ignore -v tmp` 空命中、退出码 1），所以把缓存指到 `<repo>/tmp/…` 会立刻在 `git status --porcelain` 里多出 `?? tmp/`（数百 MiB 的 Chrome + sysroot），直接判越界。上面示例落在**用户主目录**（仓库之外），不是 `<repo>/tmp/`；默认值与回退值同样都在仓库之外。收尾自检：`git status --porcelain | grep '^?? tmp/'` 必须为空（本容器实测为空，工作树只有本轮白名单条目）。

实测（同一命令，仅换缓存位置）：缓存指向 `HOST: /tmp/web-demo-chrome-cache` → `FAIL harness.error :: … has only 150.6 MiB free, but bootstrapping needs about 1.2 GiB …`，**EXIT=2**；指向 `/data/...` → 自举成功并 **19/19 PASS，EXIT=0**。

**运行期也不写仓库、不写 `/tmp`**：`<cache>/runtime/`（仓库之外）承载 user-data-dir 与探针页；默认日志与 stats 走 `HOST: /tmp/web-demo-verify.log`、`HOST: /tmp/web-demo-verify.stats.json`（已声明的输出，不是缓存）。收尾判据：`git status --porcelain | grep -c '^?? tmp/'` = 0。

运行期卫生：Chrome 的 user-data-dir 参数与探针页现在放在 `<cache>/runtime/`（不再占用 `/tmp`）；收到 `SIGTERM`/`SIGINT`（`timeout`、Ctrl-C、SSH 断开）时会连带回收浏览器进程；上次硬杀留下的 `profile-*`/`probe-*` 会在下次运行开头自动清理。实测一次完整运行结束后**残留 chrome 进程数为 0**，`/tmp` 无 profile 残留。

### 2.2 跑断言

```bash
bash scripts/web-demo-verify.sh                 # 默认 probe：不依赖被验页面
bash scripts/web-demo-verify.sh --mode demo     # 驱动真实页面 web/index.html
bash scripts/web-demo-verify.sh --mode auto     # web/index.html 存在则用 demo
```

* `probe`（默认）：内建探针页 + **两个无头页经真实信令服务**完成一次通话。这是 §8 A-3 的可执行形态；它只依赖本脚本，因此结论始终可归因于本脚本自身，不会因为别的交付物尚未落地而变红。
* `demo` 的站点根就是 `web/`（`--mode demo/auto` 起服务器时 root 设为 `web/`，URL 仍是 `http://localhost:<port>/`），所以 `web/index.html` 与其资源引用（页面里用相对路径 `app.js`、`lib/**`，落到磁盘即 `web/app.js`、`web/lib/**`）都能命中；`--mode auto` 在 `web/index.html` 存在时选 `demo`。
* `demo`：两个标签页加载 `web/index.html`，调用它自己的矩阵 `window.selftest.run()`（§8 A-4），并断言页面来源是 secure context、信令可达。
* `auto`（可选便利模式）：`web/index.html` 存在则用 `demo`，否则用 `probe`（会打印选择与理由）。
* 退出码（captain 已裁定接受该语义，verifier 在 `reports/72` 记为「语义差异已文档化」）：`0` 全部断言通过；`1` **测了但没过**（含信令端点不可达——可达性正是 `signaling.ping-pong` 在测的东西）；`2` **拒绝按给定输入去测试**（禁用端点 `127.0.0.1:8443` / `https|wss`、未知 `--mode`/flag、无可用探活原语、缓存卷空间不足）。
* 证据（断言行格式见 `web/tests/verify-two-page.mjs:21`）：原始日志 `${WEB_DEMO_LOG:-/tmp/web-demo-verify.log}`（每断言一行 `PASS`/`FAIL`，格式见 `web/tests/verify-two-page.mjs`）与机器可读导出 `HOST: /tmp/web-demo-verify.stats.json`（时间线 + 两次 stats 快照 + 汇总）。

可用选项（两个脚本的 flag 均**不引入新的 CMD 分类**，见 §4）：

| 选项 | 默认 | 说明 |
|---|---|---|
| `--mode probe\|demo\|auto` | `probe` | `probe` 不依赖被验页面；`demo` 驱动 `web/index.html`；`auto` 存在页面时选 `demo` |
| `--signaling ws://HOST:8443/ws` | `ws://47.238.144.66:8443/ws` | 信令端点；容器内可传 `ws://172.18.0.1:8443/ws` |
| `--stun URL` | `stun:47.238.144.66:3478` | 候选收集用的 STUN |
| `--cache DIR` | `${WEB_DEMO_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/web-demo-chrome-cache}` | 自举缓存位置；`--print-cache` 会一并打印该文件系统的**可用空间**与钉版号 |
| `--log-file FILE` | `${TMPDIR:-/tmp}/web-demo-verify.log` | 原始日志落点 |
| `--port N` | `8081` | 仅 `--mode demo` 的静态端口 |
| `--print-cache` / `--clean` | — | 查看 / 清理缓存（均打印结果，退出码 0） |
| `--print-chrome-path` | — | 打印 chrome-headless-shell 可执行文件绝对路径（纯值，stdout 仅一行）；缓存缺失时先自举，自举不可能则 **EXIT=2** |
| `--print-sysroot` | — | 打印自举出的 sysroot **根目录**（纯值，**不是库路径**）；需要设置 LD_LIBRARY_PATH 时自行拼接：`<sysroot>/usr/lib/x86_64-linux-gnu:<sysroot>/lib/x86_64-linux-gnu:<sysroot>/usr/lib`（路径拼接源文件 `web/tests/lib/deb.mjs` 的 `libPathOf()`；环境变量 LD_LIBRARY_PATH 的注入见 `web/tests/lib/chrome.mjs`）；缓存缺失时先自举，不可是空值 + EXIT=0 |

#### t3-amend-3 登记：纯值开关与冷/热缓存原始输出

* **参数顺序敏感（open item 7，已实测）**：「取值即退出」的开关（`--print-cache`、`--print-chrome-path`、`--print-sysroot`、`--clean`）在**解析到它的那一刻**就输出并 `exit 0`，**其后的参数不再生效**。实测：`--cache DIR --print-cache` → 打印 `DIR`；`--print-cache --cache DIR` → 打印**默认**缓存。要指向特定缓存，请把 `--cache DIR` / `--log-file FILE` 放在 print 开关**之前**，或直接用环境变量 `WEB_DEMO_CACHE` / `WEB_DEMO_LOG`。
这两个开关是 captain 裁定 `t3-amend-3` 要求补的 bootstrap 出口，供 t5 做「两套自举一致性」交叉核对。**stdout 只输出一行纯值**（无横幅、无颜色、无日志；诊断一律走 stderr），**只暴露环境、不外泄任何断言逻辑**。

```text
# 热缓存（缓存已存在）
$ bash scripts/web-demo-verify.sh --print-chrome-path
/data/dsh/home/workspace/tmp/tv-final/chrome-headless-shell-linux64/chrome-headless-shell
                                             → EXIT=0 · stdout 90 B / 1 行 · stderr 0 B · 文件可执行
$ bash scripts/web-demo-verify.sh --print-sysroot
/data/dsh/home/workspace/tmp/tv-final/sysroot
                                             → EXIT=0 · stdout 46 B / 1 行 · 目录存在

# 冷缓存（缓存目录不存在 → 触发自举后再输出；全过程在 stderr）
$ WEB_DEMO_CACHE=/data/dsh/home/workspace/tmp/am3-cold bash scripts/web-demo-verify.sh --print-chrome-path
/data/dsh/home/workspace/tmp/am3-cold/chrome-headless-shell-linux64/chrome-headless-shell   → EXIT=0（stdout 1 行）
  stderr: web-demo-verify: chrome not bootstrapped in … yet; bootstrapping now
          chrome: bootstrapping 154.0.8037.57 into … (free 20.6 GiB) … sysroot ready: …
$ … --print-sysroot → /data/dsh/home/workspace/tmp/am3-cold/sysroot                          → EXIT=0（stdout 1 行）
```

失败分支（**绝不出现空值 + EXIT=0**）实测：

```text
$ WEB_DEMO_NODE=/nonexistent WEB_DEMO_CACHE=<空目录> … --print-chrome-path
  stdout=[]（空）· EXIT=2 · stderr: chrome binary unavailable in … and bootstrap failed
$ WEB_DEMO_CACHE=/tmp/web-demo-chrome-cache … --print-chrome-path        # 256 MiB tmpfs 放不下
  stdout=[]（空）· EXIT=2 · stderr: … has only 150.4 MiB free, but bootstrapping needs about 1.2 GiB …
```

交叉核对配套：自举时会把钉版写进 `<cache>/chrome-version.txt`（当前实测为 `154.0.8037.57`），路径可由 `--print-chrome-path` 的祖父目录推出，供两套自举比对「路径 + 钉版」一致性；`--print-cache` 也会打印该版本号。


断言清单（`probe` 模式，**19 条**，含 `stats.exported`）：

`browser.boot`、`page.secure-context`（断言清单实现在 `web/tests/verify-two-page.mjs:375`）、`page.mediadevices`、`signaling.ping-pong`、`signaling.created`、`signaling.joined`、`signaling.peerJoined`（定义于 `signaling/protocol/message.go`）、`capture.fake-device`、`sdp.offer-contains-vp9`、`signaling.offer-answer-relayed`、`signaling.ice-exchanged`、`ice.connected`（含 DTLS）、`media.codec-vp9-getstats`、`media.bidirectional-rtp`（5 s 窗口内双向字节增长）、`media.frames-flowing`、`media.codec-vp9-getstats-inbound`、`media.audio-rtp`、`signaling.leave-peerleft`、`stats.exported`。

### 2.3 验证要点（供 t5 的断言点列表，均为本容器实测值）

下列事实是 t3 在交付 revision 上实测得到的，可直接作为 t5 断言点；每一项都给出「期望值」而不是叙述。

| # | 断言点 | 实测值 / 期望 |
|---|---|---|
| 1 | 信令端点可达性 | 容器内 `ws://172.18.0.1:8443/ws` 与公网 `ws://47.238.144.66:8443/ws` 是**同一服务**，`ping`→`pong`（实测 RTT 2–39 ms） |
| 2 | 健康端点字段 | 200 + JSON，含 `status`=ok、`addr`=:8443、`version`=0.1.0、`maxMessageBytes`=65536、`roomGraceSec`=90、`roomExpirySec`=1800、`stunUrl`、`turnUrl`，另有 `activeConns`/`totalConns`/`roomsCreated`/`roomsDestroyed`/`seatTakeovers`/`graceExpired`/`uptimeSec`/`serverTimeMillis`（字段发射点 `signaling/server/server.go:117`） |
| 3 | 禁用地址归因 | `127.0.0.1:8443` 是容器内 DSH Harness 的 Caddy：HTTPS-only（明文请求 400），配置 `tls internal` + `basic_auth argon2id` + `reverse_proxy 127.0.0.1:3080`；`https://…:8443` 同不可用（8443 仅明文） |
| 4 | 安全组排查路径 | healthz 不通 ⇒ 阿里云安全组未放行 8443/tcp；ECS 控制台 → 安全组 → 入方向 → 手动添加 TCP `8443/8443`、源 `0.0.0.0/0`（或出口 IP） |
| 5 | 信令流程消息 | 一次通话应观测到 create→**created**（roomId 6 位 `[A-Z2-9]`）、join→**joined**（附 `peerId`）、对端 **peerJoined**、**offer**/**answer**（服务端按字节原样转发）、**ice**（双向）、leave→对端 **peerLeft**；未知 type 必须记入时间线并忽略 |
| 6 | ICE 配置来源 | 全部来自 `created`/`joined` 下发的 `stunUrl`/`turnUrl`/`turnUsername`/`turnCredential`；TURN 条目 UDP 在前、同凭据 TCP 回退在后（实测构造出 2 条 `iceServers`） |
| 7 | 假设备采集 | `getUserMedia` 得到假设备标签 fake_device_0，settings 为 **640x480 @ 20fps** |
| 8 | SDP 编解码 | offer SDP 含 **video/VP9/90000**；发送端 codec 偏好被钉在 VP9（实测 `codecPreference=ok`） |
| 9 | 候选与连通 | local 候选出现 **host/udp + host/tcp + srflx/udp**；`candidate-pair` 达 `succeeded`；ICE 与 DTLS 均 `connected`（实例 `dtlsState`=connected） |
| 10 | 双向媒体 | 5 s 窗口内 host→guest 与 guest→host 的 `bytesSent`/`bytesReceived` **同时**增长（实测 ≈214–225 KB/向），`framesEncoded`/`framesDecoded` 0→≈100，音频 `bytesReceived`>0 |
| 11 | VP9 判定口径 | 必须由 `codecId` 解析到 `codec` 统计后再判 video/VP9（**不接受**直接读字符串），发送端与接收端各一条（实现见 `web/tests/verify-two-page.mjs:288`） |
| 12 | stats 可导出 | 运行结束产出 `HOST: /tmp/web-demo-verify.stats.json`（时间线 + 两次 stats 快照 + 汇总），日志尾部为机器可读 summary |

（`getUserMedia` 检测见 `web/tests/verify-two-page.mjs:191`）无头页使用 `--use-fake-device-for-media-stream --use-fake-ui-for-media-stream`（640x480@20fps 假摄像头 + 假麦克风），因此**不需要**真实摄像头与麦克风；页面来源是 `http://localhost:<port>`，属 secure context，`getUserMedia` 无需 TLS。

---

## 3. 真机联调清单（与 App 互拨，**全部在宿主机执行**）

> 本节所有命令都在**宿主机**上运行（用户 VSCode Remote-SSH 的终端即宿主机 shell）；§2 的容器内静态服务**只用于自动化验证**，对用户不可见——用户的 `http://localhost:8081` 必须由宿主机这一侧提供。

1. **preflight**（字段发射点 `signaling/server/server.go:117`）：打开 `http://47.238.144.66:8443/healthz`，确认 200 且含 `roomGraceSec`/`stunUrl`/`turnUrl`（§0）。不通即停，先修安全组。
2. 在宿主机启动静态页：`cd /opt/dsh-workspaces/code/webrtc-demo && bash scripts/serve-web-demo.sh start`（宿主机**没有 node**，脚本会自动改用已有 `python3`，也可显式 `--engine python3`；加 `--root web` 可显式指定站点根）。随后在 VSCode **PORTS 面板只转发 8081**。不要转发 8443（页面直连公网明文地址）；若确实要转发，转发目标必须是**宿主机** 8443，容器 loopback 的 8443 是 Caddy。
3. 真机 App 建房，记下 6 位 roomId。
4. Chrome 打开 `http://localhost:8081/`，填入房号 Join。
5. 观察面板：peerJoined（事件名，定义于 `signaling/protocol/message.go:22`）→ `offer` → `answer` → `ice*` → connected；stats 出现 `video/VP9` 与双向字节增长。
6. 复现失败路径：强制 relay（F-6）、模拟掉线（F-2，只断 WS 不 leave，应在 90 s 内重连拿回原 peerId）、对端离开（F-4）。
7. 导出 JSON/CSV 留证（面板导出按钮）。

只支持桌面 Chrome；不要用 Safari/Firefox/移动端，也不要用非 `localhost` 的明文来源打开页面。

---

## 4. 门禁与不越界（**仓库内 / 由团队与 CI 执行**）

```bash
bash scripts/doc-verify.sh; echo "EXIT=$?"     # 期望 EXIT=0，计数不劣化
bash scripts/i18n-audit.sh; echo "EXIT=$?"     # 期望 EXIT=0
git status --porcelain                          # 只应出现白名单路径
```

本手册与两个脚本都不修改门禁脚本、不新增 `doc/design/**` 页面；新增脚本只使用仓库中已存在的命令/flag 形态，不引入需要登记到 `doc/design/_generated/host-commands.md` 的新 flag。

---

## 5. 失败矩阵（症状 → 根因 → 处置）

| # | 症状（原始报错/现象） | 根因 | 处置 |
|---|---|---|---|
| 1 | `http://47.238.144.66:8443/healthz` 超时 / connection refused | 阿里云安全组未放行 8443/tcp | ECS 控制台 → 安全组 → 入方向 → TCP `8443/8443`；见 §0 |
| 2 | 页面控制台 `WebSocket connection failed`，但 healthz 正常 | 用了 `https://…:8443` / `wss://…:8443` | 改 `ws://47.238.144.66:8443/ws`（8443 只有明文） |
| 3 | 对 8443 发 HTTP 得到 `400 Client sent an HTTP request to an HTTPS server` | 打到了**容器的** `127.0.0.1:8443`（DSH Harness Caddy） | 容器内改用 `ws://172.18.0.1:8443/ws`；脚本已对该地址 `exit 2` |
| 4 | （`web/tests/verify-two-page.mjs:191`）`navigator.mediaDevices` 为 `undefined` / `getUserMedia` 报 NotAllowedError（检测实现见 `web/tests/verify-two-page.mjs`） | 页面来源不是 `http://localhost`（用了 IP、`file://` 或非转发端口） | 经 VSCode 转发 8081 后用 `http://localhost:8081/` 打开 |
| 5 | 无头脚本报 `error while loading shared libraries: libglib-2.0.so.0`（或其它 soname） | 缓存里的 sysroot 不完整（上次下载中断） | **不要**装系统包；`--clean` 后重跑，自举会重建 sysroot |
| 6 | `serve-web-demo.sh start` 报 `already in use by another process`（exit 1） | 端口被占（上一次未 stop，或别的进程） | `status` 查看 → `stop` → 或换 `--port` |
| 7 | 自举阶段 `HTTP 403/404` 或下载中断 | 源站不可达 / 缓存残留半成品 | 重跑；仍失败则 `--clean` 后重试，并检查到 `storage.googleapis.com` 与 `deb.debian.org` 的连通性 |
| 8 | （`web/tests/verify-two-page.mjs:213`）`setCodecPreferences` 抛 `InvalidModificationError: Missing codec from codec capabilities` | 把**视频**编解码偏好设到了**音频** transceiver | 只对 `sender.track.kind === 'video'` 的 transceiver 设置（探针页已按此实现） |
| 9 | ICE 只出现 `relay` 候选且连接失败，coturn 侧 508 | relay 配额/端口池耗尽（`total-quota` = 45、端口池 49152-49200 共 49 个） | 这不是信令故障；等分配释放或降低并发，别去重启信令服务 |
| 10 | 浏览器与真机只见 `host` 候选、无可用候选对 | Chrome 对 host 候选做 mDNS 混淆（`.local`），对端无法解析 | 判据以 `srflx`/`relay` 候选对为准；登记为已知限制，不得当作成功 |
| 11 | `--mode demo` 报 `FAIL page.selftest-api` | 页面未暴露 `window.selftest.run()`（§8 A-4 的契约接口） | 由页面实现方补齐；`probe` 模式不受影响 |
| 12 | `--mode demo` 报 `FAIL signaling.ping-pong`，但 `curl http://47.238.144.66:8443/healthz` 正常 | 转发/代理改写了明文 WS，或页面被以 `https://` 打开 | 确认页面是 `http://localhost:8081/`，且信令地址是 `ws://…:8443/ws` |
| 13 | `ice.connected` FAIL 且两侧候选**数量为 0** | 页面没拿到 ICE 配置就去建 PC（漏用 `created`/`joined` 下发的 `stunUrl/turnUrl`），或候选未经信令转发 | 断言发出的 `ice` 条数（本脚本会打印 `host sent/recv`）；ICE 服务器只能来自服务端下发（§I-1） |
| 14 | （`web/tests/verify-two-page.mjs:311`）`ice.connected` FAIL，候选只有 `host` 且 `dtlsState` 停在 `new`/`connecting` | 只拿到 host 候选而两端无法互通（mDNS/网络隔离），或 DTLS 握手被打断 | 看 `#10` 与 `candidate-pair` 是否 `succeeded`；DTLS 失败时重跑一次确认非抖动，并把 `selectedPair`/`dtlsState` 原始字段留下（实现在 `web/tests/verify-two-page.mjs`） |
| 15 | （`web/tests/verify-two-page.mjs:313`）`media.bidirectional-rtp` FAIL，`media.encoded` 通过但 `media.decoded` 为 0（或反之） | **仅单向媒体**：一端未 `addTrack`（answer 里没有 recvonly/sendrecv），或远端轨未真正解码 | 断言两侧 `bytesSent` 与 `bytesReceived`（`addTrack` 后的 RTP 统计，见 `web/tests/verify-two-page.mjs`）必须同时增长（§M-6，窗口 5 s）；只增一侧即判 FAIL 并在面板标出方向 |
| 16 | 自举阶段出现 ENOSPC: no space left on device（或缓存目录只剩几 MB） | 缓存目录所在卷太小——本镜像 /tmp 是 256 MiB tmpfs，装不下 426 MiB 缓存 | 用 `--cache DIR` 或环境变量 WEB_DEMO_CACHE 指到非 tmpfs 的大卷（默认值 `${XDG_CACHE_HOME:-$HOME/.cache}/web-demo-chrome-cache` 已经如此）；脚本在下载前预检并以 **EXIT=2** 明确拒绝，**看到 ENOSPC 就说明缓存被指到了小卷** |

---

## 5.1 回退：用户侧手工矩阵（**只在两条路都失败时启用**，且**不是**「已通过」）

captain 修订 r1：无头媒体验证的**主路径是容器内**，宿主路径为**可选**。只有在**容器路径与宿主机路径都失败、并各自给出原始报错**时才允许启用手工矩阵。容器路径失败通常有两个原因：容器内无 `node ≥ 22`，或自举的两个公网源（`storage.googleapis.com`、`deb.debian.org`）不可达（`--clean` 后重试仍失败）。此时**不要**静默降级、也不要在宿主机装包；改用下面这套**手工矩阵**，并在任何结论里显式标注「手工矩阵，未由无头环境验证」，同时附上两侧的原始报错：

1. `bash scripts/serve-web-demo.sh start` → 在 VSCode **PORTS** 面板转发 **8081**（只转发这一个）。
2. 笔记本 Chrome 打开 `http://localhost:8081/`，确认地址栏是 `http://localhost`（secure context）。
3. 点 Create（或真机 App 建房后点 Join 并填 6 位房号）。
4. 依次观察面板 6 区：信令时间线 → SDP 原文/解析 → ICE 候选表与选中候选对 → 五条状态机跃迁 → 1 Hz stats → 事件/错误流。
5. 导出 JSON 与 CSV，确认导出物含生成时刻与页面 URL。
6. 与真机 App 互拨：复现 offer/answer/ice、connected、VP9、双向字节增长。
7. 复现失败路径：强制 relay、模拟掉线（只断 WS 不 leave，≤90 s 内重连拿回原 peerId）、对端离开。
8. 把导出物路径与截图留证；报告里必须写明**该路径未经无头 Chrome 验证**。

---

## 6. 已知限制与 open items

1. **只支持 Chrome**：其它浏览器不做兼容承诺。
2. **mDNS 候选**：见失败矩阵 #10。
3. **TURN relay 配额**：并发 relay 分配上限 45，端口池 49152-49200；配额类失败必须识别为配额问题而非信令故障。
4. **5349/TLS 未放行**：本轮只用 `turn:`（UDP 优先，TCP 回退），不依赖 `turns:`。
5. **容器无 python3**（实测 `command -v python3` 为空）：`--engine auto` 的首选是宿主机的 `python3 -m http.server`（契约 A-1）；在本容器会自动落到 `web/tests/lib/serve.mjs`（node）并在启动时打印原因。宿主环境有 python3 时无需任何额外依赖，且 **start/status/stop 不需要 node**（就绪探测走 bash `/dev/tcp`）。这一点按 captain 修订 r1 明确：面向用户的静态服务在**宿主机**跑，无头验证在**容器内**跑，两者落点不得混写（见文首「落点分工」）。
6. **无头二进制的选择**：钉版使用 `chrome-headless-shell`（headless 专用二进制，自举体积更小、无需 GPU/X 栈）。契约列出的 `--headless=new` 仍被逐字传入，对本二进制是 no-op（实测接受且行为不变）；换成完整 `chrome` 二进制时该 flag 才真正生效。
7. **本手册不覆盖页面内部实现**（信令客户端、面板、导出、自测矩阵）——那部分见 `web/lib/**` 与 `reports/70` §S1/S2 的判据。
