# 13 — Go 工具链安装与验证（t13）

- 任务：t13（work，无依赖）— 在工作区根目录安装 Go 1.22+ 工具链并配置 GOPROXY
- 执行人：go-dev
- 执行时间：2026-09-13 15:27–15:31（容器本地时间 CST，UTC+8）
- 结论速览：**通过**。工具链落在 `<WS>/go`（`go version go1.22.12 linux/amd64`），环境脚本 `<WS>/env-go.sh` 可用（bash/dash 双验证），`github.com/sirupsen/logrus` 实测 `go get` + 编译 + 运行成功（终端与日志文件双写均验证）。原始证据见 `<WS>/go/verify-t13/t13-evidence.log`。

> 路径约定：容器内 `<WS>` = `/data/dsh/home/workspace`；据 `reports/01-host-recon.md` §4，该目录与宿主机 `/opt/dsh-workspaces` 是**同一份共享目录**，因此本工具链在宿主机上同样可见（宿主机为 Ubuntu 24.04 / x86_64，与 linux-amd64 归档一致）。

---

## 1. 验收标准与结果

| 验收项（来自 t13 执行指引） | 结果 | 证据 |
|---|---|---|
| 从 go.dev/dl 下载 Go 1.22+ linux-amd64 tarball | ✅ go1.22.12（1.22 线末版补丁） | §2.1 |
| 校验 sha256 | ✅ 实测 = 官方值 | §2.1 |
| 解压到 `<工作区根>/go/` | ✅ `<WS>/go/bin/go` | §2.2 |
| 写 `<工作区根>/env-go.sh`（GOROOT/GOPATH/PATH/GOPROXY） | ✅ 含 12 个变量，bash 与 dash 双验证 | §3 |
| 用 `go get github.com/sirupsen/logrus` 实测验证 | ✅ 编译+运行+双写日志全部成功 | §4 |

命令退出码：全部 0（无失败重试残留；仅 1 次依赖版本适配，见 §4.2）。

---

## 2. 安装过程与原始输出

### 2.1 下载与校验

版本选择依据：`doc/12-backend-implementation.md` §1 技术栈表写「语言 Go 1.22」、§11 `go.mod` 写 `go 1.22`；`doc/05-code-design.md` §2 写「Go 1.22+」。故取 **1.22 系列最后一个官方补丁版 go1.22.12**。版本与校验值取自官方 JSON 接口
`https://go.dev/dl/?mode=json&include=all`（`linux-amd64` 条目的 `sha256`/`size` 字段）：

```
$ curl -sS 'https://go.dev/dl/?mode=json&include=all'   # 过滤得：
go1.22.12 4fa4f869b0f7fc6bb1eb2660e74657fbf04cdd290b5aef905585c86051b34d43 68995422 True

$ curl -fL --retry 3 --max-time 600 -o go1.22.12.linux-amd64.tar.gz \
      https://go.dev/dl/go1.22.12.linux-amd64.tar.gz
100 65.7M  100 65.7M    0     0  23.7M      0 --:--:-- --:--:-- --:--:-- 35.5M

$ sha256sum go/downloads/go1.22.12.linux-amd64.tar.gz
4fa4f869b0f7fc6bb1eb2660e74657fbf04cdd290b5aef905585c86051b34d43  go/downloads/go1.22.12.linux-amd64.tar.gz

$ stat -c '%s bytes' go/downloads/go1.22.12.linux-amd64.tar.gz
68995422 bytes
```

与官方 `sha256` 逐字符一致 ✅；字节数与官方 `size` 一致 ✅。（`go.dev/dl/...` 先 302 到 `dl.google.com`，该 CDN 在 `reports/01-host-recon.md` §3.6 实测 20–24 MB/s。）

### 2.2 解压与版本确认

```
$ tar -C /data/dsh/home/workspace -xzf /data/dsh/home/workspace/go/downloads/go1.22.12.linux-amd64.tar.gz
$ ls -l /data/dsh/home/workspace/go/bin
-rwxr-xr-x 1 node node 12688957 Feb  1  2025 go
-rwxr-xr-x 1 node node  2614997 Feb  1  2025 gofmt

$ GOROOT=/data/dsh/home/workspace/go /data/dsh/home/workspace/go/bin/go version
go version go1.22.12 linux/amd64
```

归档原件保留在 `<WS>/go/downloads/go1.22.12.linux-amd64.tar.gz`（便于宿主机离线复用与复核）。工具链总占用 `372M`。

---

## 3. env-go.sh 与环境变量

`<WS>/env-go.sh` 以**脚本自身所在目录**推导工作区根（不写死绝对路径），可由 bash/dash 直接 `source`：

```
$ bash -c '. ./env-go.sh --print'
GOROOT     = /data/dsh/home/workspace/go
GOPATH     = /data/dsh/home/workspace/go/gopath
GOBIN      = /data/dsh/home/workspace/go/gopath/bin
GOENV      = /data/dsh/home/workspace/go/env
GOPROXY    = https://goproxy.cn,https://proxy.golang.org,direct
GOTOOLCHAIN= local
PATH       = /data/dsh/home/workspace/go/gopath/bin:/data/dsh/home/workspace/go/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
go version go1.22.12 linux/amd64

$ go env GOROOT GOPATH GOBIN GOENV GOCACHE GOTMPDIR GOPROXY GOSUMDB GOTOOLCHAIN GOVERSION
/data/dsh/home/workspace/go
/data/dsh/home/workspace/go/gopath
/data/dsh/home/workspace/go/gopath/bin
/data/dsh/home/workspace/go/env
/data/dsh/home/workspace/go/cache
/data/dsh/home/workspace/go/tmp
https://goproxy.cn,https://proxy.golang.org,direct
sum.golang.org
local
go1.22.12
```

除要求的三项外，另有三处**必要**补充（均有实测理由，见 §5）：

| 变量 | 值 | 为什么必须设 |
|---|---|---|
| `GOCACHE` | `<WS>/go/cache` | 容器 `$HOME`=`/data/dsh/home` 在 workspace-write 沙箱下**不可写**，默认 `$HOME/.cache/go-build` 会让**任何** go 命令直接失败（§5.1） |
| `GOTMPDIR` | `<WS>/go/tmp` | 同上，避免 go 向 `/tmp`/`$HOME` 写临时目录时被拒 |
| `GOENV` | `<WS>/go/env` | 默认 `$HOME/.config/go/env` 不可写；同时使 `go env -w` 的持久化配置不污染容器 `$HOME` |
| `GOTOOLCHAIN` | `local` | 禁止 go 自动下载/切换工具链，避免受限网络下不可预测的失败（配合 §4.2 的依赖选版） |

`CGO_ENABLED=0`（信令服务纯 Go，不依赖 gcc）、`GOSUMDB=sum.golang.org`、`GOTELEMETRY=off`（go1.22 尚无该特性，为前向兼容保留，实测 `go env GOTELEMETRY` 返回空值，无害）。

**shell 兼容性**：`bash -c '. env-go.sh && ...'` 与 `sh -c '. env-go.sh; ...'`（dash）均验证通过；`env -i`（清空环境）下亦可正常工作：

```
$ env -i PATH=/usr/bin:/bin HOME=/tmp bash -c 'cd /data/dsh/home/workspace && . ./env-go.sh && go version && go env GOPROXY GOPATH'
go version go1.22.12 linux/amd64
https://goproxy.cn,https://proxy.golang.org,direct
/data/dsh/home/workspace/go/gopath
```

> ⚠️ 新 shell **必须**先 source 才可用 go；未 source 时 `go: command not found`（容器 PATH 无全局安装，因 `/usr/local/bin` 不在可写范围且属主为 root）。用法：
> `sh -c '. /data/dsh/home/workspace/env-go.sh && go build ./...'`

---

## 4. logrus 实测验证

### 4.1 验证程序

`<WS>/go/verify-t13/`（独立 module `webrtcdemo-verify-t13`，不污染 `signaling/`），程序同时演示「终端 + 日志文件」双写（即信令服务将采用的日志形态）：

```go
logrus.SetOutput(io.MultiWriter(os.Stdout, f))
logrus.WithFields(logrus.Fields{"check": "t13", "dep": "logrus"}).Info("logrus works: stdout + file")
```

```
$ go get github.com/sirupsen/logrus@v1.9.4
go: downloading github.com/sirupsen/logrus v1.9.4
go: downloading golang.org/x/sys v0.13.0
go: added github.com/sirupsen/logrus v1.9.4
go: added golang.org/x/sys v0.13.0

$ go build -o ./verify-t13 . && ./verify-t13
time="2026-09-13T15:30:18+08:00" level=info msg="logrus works: stdout + file" check=t13 dep=logrus

$ cat logs/verify.log            # 文件侧同样写入成功
time="2026-09-13T15:30:18+08:00" level=info msg="logrus works: stdout + file" check=t13 dep=logrus

$ go mod graph
webrtcdemo-verify-t13 github.com/sirupsen/logrus@v1.9.4
webrtcdemo-verify-t13 golang.org/x/sys@v0.13.0
...
```

另外验证了交叉编译与静态检查（t9 产出 linux-amd64 二进制、t10/t12 部署需要）：

```
$ GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o signaling-linux-amd64 .
-rwxr-xr-x 1 node node 1228952 ... signaling-linux-amd64

$ go vet ./... && gofmt -l . && echo VET_FMT_OK
VET_FMT_OK
```

### 4.2 依赖选版（1 次失败 → 修正，属规格内重试，未超 3 次上限）

第一次按要求直接 `go get github.com/sirupsen/logrus`（不加版本），解析到 **v1.10.2**，在 Go 1.22 下失败：

```
go: downloading github.com/sirupsen/logrus v1.10.2
go: github.com/sirupsen/logrus@v1.10.2 requires go >= 1.23 (running go 1.22.12; GOTOOLCHAIN=local)
```

核查各版本 `go` 指令（`https://goproxy.cn/github.com/sirupsen/logrus/@v/<ver>.mod`）：

| 版本 | `go` 指令 | 是否可用 |
|---|---|---|
| v1.10.2（最新）/ v1.10.0 | `go 1.23` | ❌ |
| **v1.9.4** | `go 1.17` | ✅ 采用 |
| v1.9.3 | `go 1.13` | ✅ 备选 |

**修正**：锁定 `github.com/sirupsen/logrus v1.9.4`（1.9 线最新补丁版），保持 Go 1.22 工具链不动——因为 doc/12 §1/§11 明确以 Go 1.22 为基准，升工具链到 1.23+ 会偏离已冻结的规格。`signaling/go.mod` 将写 `require github.com/sirupsen/logrus v1.9.4`。

---

## 5. 对文档缺陷/环境陷阱的修正说明（逐处）

1. **【环境陷阱，doc 未提】容器 `$HOME` 不可写 → 必须重定向 GOCACHE/GOTMPDIR/GOENV。**
   证据（首轮真实失败）：
   ```
   failed to initialize build cache at /data/dsh/home/.cache/go-build: mkdir /data/dsh/home/.cache/go-build: permission denied
   [sandbox: file access denied under workspace-write mode]
   ```
   在 workspace-write 沙箱下 `/data/dsh/home` 与 `/tmp` 均不可写（`/tmp` 下生成的可执行文件亦被拒执行）。修正：`env-go.sh` 把 `GOCACHE`/`GOTMPDIR`/`GOENV` 全部指向 `<WS>/go/` 下，编译产物也一律写在 `<WS>` 内。**这是本任务最关键的一条环境结论**，所有需要在容器内跑 go 的成员都适用。
2. **【文档冲突】doc/12 §1 指定日志库为 `log/slog`（标准库），与用户明确要求 `github.com/sirupsen/logrus` 冲突。**
   处理：按用户指令优先 → 信令服务使用 logrus；`doc/12` 的依赖表相应视为已修正（将在 `reports/09-go-signaling.md` 与 `signaling/` 代码注释中标注）。
3. **【文档缺口】doc/12 §11 只写 `gorilla/websocket v1.5.1`，未说明依赖对 Go 版本的约束，也未给出 GOPROXY/国内镜像建议。**
   实测：`proxy.golang.org` 虽 HTTP 200，但吞吐仅 0.28 MB/s（`reports/01-host-recon.md` §3.6）；`goproxy.cn` 1.32 MB/s。修正：`GOPROXY=https://goproxy.cn,https://proxy.golang.org,direct`（国内镜像优先、官方兜底、最后直连）。`gorilla/websocket v1.5.1` 的 `go 1.20` 指令与 Go 1.22 兼容（v1.5.3 为 `go 1.12`），t9 将按 doc 指定使用 v1.5.1。

   > **后续对齐（t2 契约冻结后，2026-09-13）**：`doc/14-interface-contract.md` §3.2 把 GOPROXY 冻结为单值 **`https://goproxy.cn,direct`**。`env-go.sh` 已改为该冻结值（去掉 `proxy.golang.org` 兜底）；本报告 §3/§4 的原始输出是改动前（15:30）的实测记录，按「原始输出不改写」原则保留，`proxy.golang.org` 已被验证为低速链路，去掉不影响可用性。
4. **【文档不一致】doc/01 §7 与 doc/13 阶段 2 均未规定 Go 工具链的安装位置/版本号**（只写 `go build`）。
   修正：确立 `<WS>/go`（GOROOT）+ `<WS>/go/gopath`（GOPATH）+ `<WS>/env-go.sh` 为团队约定；宿主机的 `/opt/dsh-workspaces` 与本目录同一，故宿主机侧 t10 构建 Go 二进制时可直接复用同一路径与脚本。
5. **【环境事实，供他线参考】`GOTOOLCHAIN=local`** 意味着任何 `go` 指令 >1.22 的依赖都会**硬失败**（不会自动下载新工具链）。若后续确需 logrus ≥v1.10 或其它新模块，需 captain 决定是否升级工具链版本。

---

## 6. 未决问题（不在 t13 范围内，提交 captain/相关成员）

1. **是否需要给宿主机全局 `go` 命令？** 目前仅在容器内 source `env-go.sh` 生效；宿主机侧若直接 `go build` 需先 `. /opt/dsh-workspaces/env-go.sh`（Ubuntu 24.04 x86_64 与 linux-amd64 归档一致，可直接用）。是否要写 `/etc/profile.d/go.sh`（需 root）由 t10/t12 决定——**我未执行任何宿主机改动**。
2. **`signaling/` 二进制交付形态**：doc/13 阶段 2.1 要求「上传 signaling 二进制到 /opt/signaling/」，而 t12 由 coturn-installer 负责部署。二进制构建命令（`GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w"`）已在 t13 验证，t9 将产出可执行文件供 t10/t12 复用。
3. **工具链升级路径**：若 verifier 或 t10 发现 logrus v1.9.4 不满足需求，可平滑切换到 go1.27.1（最新 stable，sha256 `63d339f0da5ab53635a56f2490a7984dfe12dfcff22ad749f63edaf590168445`，70553950 B），但需同步修改 `signaling/go.mod` 的 `go` 指令并重跑 t9 验证；当前不执行。

---

## 7. 交付物清单

| 路径 | 说明 |
|---|---|
| `<WS>/go/` | Go 1.22.12 linux-amd64 工具链（GOROOT） |
| `<WS>/go/bin/go`、`<WS>/go/bin/gofmt` | 可执行文件 |
| `<WS>/go/gopath/` | GOPATH（模块缓存 + GOBIN），缓存模块 logrus v1.9.4、golang.org/x/sys v0.13.0 |
| `<WS>/go/cache/`、`<WS>/go/tmp/` | GOCACHE / GOTMPDIR（沙箱下必需） |
| `<WS>/go/downloads/go1.22.12.linux-amd64.tar.gz` | 官方归档（可复核/离线复用） |
| `<WS>/go/TOOLCHAIN.md` | 工具链速查（版本/校验/用法/依赖版本约束） |
| `<WS>/go/verify-t13/` | logrus 验证 module + `t13-evidence.log` + 交叉编译产物 |
| `<WS>/env-go.sh` | 团队环境脚本（source 即用） |
| 本文件 | t13 报告 |
