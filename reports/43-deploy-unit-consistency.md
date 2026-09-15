# 43 — 修复 D-5：仓库 deploy/signaling.service 与现网 unit 对齐（补齐 `-room-grace 90s`）

- 任务：**t79**（implementation，attempt 1，attempt_id `17ae1075-8251-4230-b01c-c585e69250a9`）
- 执行人：go-dev
- 执行时间：2026-09-16 01:4x–01:5x（容器本地时间 CST，UTC+8）
- 结论：**通过**。仓库 tracked 的 `deploy/signaling.service` 已与宿主机现网运行 unit **有效指令逐行一致**（唯一差异是仓库侧新增的 `#` 说明注释），`ExecStart` **整行逐字节相同**（含 `-room-grace 90s`）；`deploy/README.md` 增补了「一致性核对 + 参数说明 + 回滚」一节；本任务**未改任何源码、未动宿主机 unit、未重启服务、未跑 Gradle、未 commit**。

---

## 1. D-5 缺陷与影响

- **来源**：t78 独立验证的 **D-5**（部署不可复现）。
- **事实**：t70 部署（`reports/38-signaling-grace-deploy.md`）把 `-room-grace 90s` 追加进宿主机 `/etc/systemd/system/signaling.service` 的 `ExecStart`，但**仓库 tracked 副本未同步** ⇒ 仓库 unit 与现网不一致。
- **为什么必须修**：`-room-grace` 是 t67「房间宽限期」修复的**行为开关**。照仓库 unit 重新部署（尤其与 t67 之前的二进制组合）会**静默退回旧行为**：一人 WS 掉线即销毁房间 + 立即下发 `peerLeft`（真机表现为"视频在流却自动退出房间"，即 t67 修掉的缺陷）。这与 `deploy/` 目录存在的意义（"部署可复现"）直接冲突。

---

## 2. 差异清单（before → after，逐参数）

宿主机现网 unit 原文（**只读**取得，2026-09-16 01:45 CST）：

```
# /etc/systemd/system/signaling.service          （systemctl cat signaling）
[Unit]
Description=WebRTC Demo Signaling Server
After=network.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/signaling
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn turn:47.238.144.66:3478?transport=udp -user demo:demopass -log /var/log/signaling/signaling.log -room-grace 90s
Restart=always
RestartSec=3
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
```

| 段/参数 | 仓库（before） | 仓库（after） | 宿主机现网 | 判定 |
|---|---|---|---|---|
| `[Unit]` 三行 | `Description`/`After`/`Wants` | 同 | 同 | 一致 |
| `Type` | `simple` | `simple` | `simple` | 一致 |
| `WorkingDirectory` | `/opt/signaling` | 同 | 同 | 一致 |
| `ExecStart` 参数 `-addr` | `:8443` | 同 | 同 | 一致 |
| `-stun` | `stun:47.238.144.66:3478` | 同 | 同 | 一致 |
| `-turn` | `turn:47.238.144.66:3478?transport=udp` | 同 | 同 | 一致 |
| `-user` | `demo:demopass` | 同 | 同 | 一致 |
| `-log` | `/var/log/signaling/signaling.log` | 同 | 同 | 一致 |
| **`-room-grace`** | **缺失** ❌ | **`90s`** ✅ | **`90s`** | **本次修复点** |
| `Restart` / `RestartSec` | `always` / `3` | 同 | 同 | 一致 |
| `LimitNOFILE` | `65536` | 同 | 同 | 一致 |
| `[Install]` | `WantedBy=multi-user.target` | 同 | 同 | 一致 |
| 文件头 `#` 说明注释 | 无 | **新增 18 行**（`-room-grace` 语义 + 不可省略理由 + 依据） | 无 | **仅仓库侧注释**（systemd 忽略；2305 B（含 18 行注释） 的两种比较方式） |

**原始 diff（改动前，证明差异只有一处）**：

```
$ diff -u <(ssh … 'cat /etc/systemd/system/signaling.service; exit') deploy/signaling.service
@@ -6,7 +6,7 @@
 [Service]
 Type=simple
 WorkingDirectory=/opt/signaling
-ExecStart=/opt/signaling/signaling … -log /var/log/signaling/signaling.log -room-grace 90s
+ExecStart=/opt/signaling/signaling … -log /var/log/signaling/signaling.log
 Restart=always
 RestartSec=3
 LimitNOFILE=65536
```

**字节差异与"仅差 16 字节"的旁证**：宿主机 431 B / 仓库（改前）415 B，差 **16 B = `" -room-grace 90s"` 的长度**（含前导空格）——与"唯一缺失项就是该 flag"吻合。

**指纹（改前/改后/宿主）**：

| 对象 | md5 | sha256 | 大小 | 属性/时间 |
|---|---|---|---|---|
| 宿主机 `/etc/systemd/system/signaling.service` | `3c4e9e731790f8e4a86486c789ebc390` | `931235543db0763d324791ed6bcb9512b63ee49b4c74f3a93d7335b6856ed71b` | 431 B | 0644 root:root，mtime 2026-09-15 23:45:24 +0800（t70 修改） |
| 仓库 `deploy/signaling.service`（改前） | `c9235cb9dddca755e90b1f58cf3aa02b` | `31740adeee66e547b4dba9cc9b9afde75e3ed2fff4876884d57c98fef93b95dd` | 415 B | 0664 node:node |
| 仓库 `deploy/signaling.service`（改后） | `d64595a64fbd1771a06d48ed46738d3c`（含 18 行注释，故与宿主原始 md5 不同） | `8380191c4bd4e4f17094eac06e707614eb0dc47919eee4563b482fd56b047b1f` | 2305 B | 0664 node:node |

---

## 3. 对照原始输出（本次实跑，宿主机只读）

### 3.1 取现网 unit 原文 + 运行实例实际参数 + 生效值

```
$ ssh … 'systemctl cat signaling; echo ---; tr "\0" " " < /proc/$(systemctl show -p MainPID --value signaling)/cmdline; echo; curl -s http://127.0.0.1:8443/healthz; exit'
# /etc/systemd/system/signaling.service
[Unit] … （全文见 §2）
ExecStart=… -log /var/log/signaling/signaling.log -room-grace 90s
---
/opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn turn:47.238.144.66:3478?transport=udp -user demo:demopass -log /var/log/signaling/signaling.log -room-grace 90s
---
{"status":"ok","roomGraceSec":90,"seatTakeovers":4,"graceExpired":8,"rooms":0,"roomsCreated":6,"roomsDestroyed":6,"uptimeSec":7194,…}
```

> 三条独立证据互证：**unit 文件**、**进程真实 cmdline**、**服务自报的 `/healthz` 生效值** 都是 90 s。

### 3.2 对齐后的比对（两种口径）

```
# 口径 A：有效指令（去注释/空行）逐行对照 —— 无输出 = 一致
$ diff <(ssh … 'cat /etc/systemd/system/signaling.service; exit' | grep -vE '^\s*(#|$)') \
       <(grep -vE '^\s*(#|$)' deploy/signaling.service)
（无输出）  ⇒ EFFECTIVE_LINES_IDENTICAL ✅

# 口径 B：ExecStart 整行逐字节
$ ssh … 'grep "^ExecStart" /etc/systemd/system/signaling.service; exit'
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn turn:47.238.144.66:3478?transport=udp -user demo:demopass -log /var/log/signaling/signaling.log -room-grace 90s
$ grep "^ExecStart" deploy/signaling.service
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:47.238.144.66:3478 -turn turn:47.238.144.66:3478?transport=udp -user demo:demopass -log /var/log/signaling/signaling.log -room-grace 90s
（两行逐字节相同 ✅）

# 口径 C：原始 diff（含注释）——仅显示仓库侧新增的 `#` 行
$ diff <(ssh … 'cat …') deploy/signaling.service
0a1,18
> # 仓库侧副本（deploy/signaling.service）——与宿主机现网 unit 逐参数一致（t79 对齐）
> …（18 行说明注释）…
```

### 3.3 仓库副本改后的指纹（用于后续核对）

```
$ md5sum deploy/signaling.service ; sha256sum deploy/signaling.service ; stat -c '%a %U:%G %s bytes' deploy/signaling.service
d64595a64fbd1771a06d48ed46738d3c  deploy/signaling.service
8380191c4bd4e4f17094eac06e707614eb0dc47919eee4563b482fd56b047b1f  deploy/signaling.service
664 node:node 2305 bytes
```

> 与宿主机原始 md5 不同是**预期**：仓库副本含 18 行 `#` 说明注释（systemd 忽略）。这与本目录既有的 `turnserver.conf` 处理方式一致（README 表内同样注明"有效指令逐行相同"）。核对请用 §3.2 口径 A/B。

---

## 4. 论证：「按仓库 unit 重新部署后行为不回退」

### 4.1 `-room-grace` 的取值来源与默认值差异

| 维度 | 事实 | 影响 |
|---|---|---|
| unit 现值 | 显式 `-room-grace 90s`（与现网一致） | 部署行为**钉在 90 s**，不随二进制默认值变化 |
| 二进制默认值 | `config.DefaultRoomGrace = 90 * time.Second`（`signaling/config/config.go:54`） | 当前二者同值；但**若省略 flag，行为就由二进制决定**，一旦默认值被改动/用了别的构建，部署形态即漂移 |
| 契约侧 | 宽限期属"部署/客户端策略"层面（`doc/14` §8.4 注记把退避/宽限期归为策略）；`-log` 路径则由 **C31** 冻结为部署事实 | 因此 unit 必须自己写清楚，不能靠默认值"碰巧一致" |

### 4.2 省略该 flag 会怎样（本机可复跑实测，非推断）

容器内同时存在**两个二进制**，正好构成对照实验：

| 实验 | 二进制 | 命令 | 观察结果 |
|---|---|---|---|
| A | **t67 之前**的旧件 `signaling/signaling`（`sha256 c298235a…`，5 496 984 B） | `./signaling -addr … -room-grace 90s` | **`flag provided but not defined: -room-grace`**，打印 usage，**退出码 2** ⇒ 服务起不来（配合 `Restart=always/RestartSec=3` 会反复失败，`systemctl status`/journal 可见）——**响亮的失败** |
| B | **现网同版本** `signaling/dist/signaling-linux-amd64`（`sha256 8708629e…`，与宿主机 `/opt/signaling/signaling` 同哈希） | 不带 `-room-grace` 启动 → `curl /healthz` | `roomGraceSec: 90`（= 二进制默认） |
| B2 | 同上 | **带** `-room-grace 90s` 启动 → `curl /healthz` | `roomGraceSec: 90` |

**结论（两条）**：
1. **显式 flag 把行为从"二进制默认值"解耦**：将来即使默认值变化，照仓库 unit 重部署仍是 90 s。
2. **D-5 描述的最坏路径已被消除**：
   - 若用 t67+ 的二进制（仓库 `dist` 即 `8708629e…`）：unit 有 flag、行为 90 s，与现网一致；
   - 若有人误用 t67 之前的二进制：unit 里**有** flag ⇒ 启动即失败（可见），而**不会**像"unit 里没有 flag"那样静默运行在旧语义（一人掉线即销毁房间 + 立即 `peerLeft`）。
   ⇒ 无论哪种组合，都**不存在"照仓库重部署后静默退回旧行为"的路径**。

---

## 5. 变更清单（本任务只改 3 个文件）

| 文件 | 变更 |
|---|---|
| `code/webrtc-demo/deploy/signaling.service` | ① `ExecStart` 末尾补 `-room-grace 90s`（与现网逐字节一致）；② 文件头新增 18 行 `#` 说明注释（`-room-grace` 语义、不可省略的理由、依据与核对入口） |
| `code/webrtc-demo/deploy/README.md` | ① 头部"字节级拷贝"表述更正为"有效指令一致（本副本含注释）"并刷新对照输出/指纹表；② 新增 **§6 与现网 unit 的一致性核对 + 回滚**（核对命令、ExecStart 参数逐项说明、6.4 不可省略的论证、6.5 回滚步骤与"旧二进制必须同时去掉该 flag"的警告） |
| `code/webrtc-demo/reports/43-deploy-unit-consistency.md` | 本报告 |

**未触碰**：`app/**`、`signaling/**`（Go 源码与二进制）、`doc/**`、宿主机 `/etc/systemd/system/signaling.service` 与任何 systemd 操作（**未 daemon-reload、未 restart**）；未跑 Gradle；未 `git commit`。

---

## 6. 未验证项 / 已知限制

1. **未在宿主机上"真的照仓库 unit 重部署"**：本任务纪律禁止动宿主机 unit/服务，因此 §4.2 的两个对照实验是在**容器内用同样两个二进制**做的（`-room-grace` 未知 flag 的行为与部署形态无关）。"按仓库 unit 安装后 systemd 实际启动成功"属下一次部署窗口（t69/t70 类似流程）的验证项。
2. **注释导致原始 md5 不同**：仓库副本与宿主机 unit 的**原始字节不一致**（差 18 行注释）。若后续验收要求"原始字节一致"，需把注释移到 README（当前选择保留注释，因为它正是"别删这个 flag"的现场提醒；两种比对口径已在 §3.2 给出）。
3. **`-pong-wait` / `-room-expiry` 等仍依赖二进制默认值**：有意为之（见 README §6.3 的理由）。若将来出现"默认值变更导致部署漂移"的实例，应按同一方法把它们也显式化。
4. **宿主机 unit 的 mtime/备份**：现网 unit 的备份（t70 留下）未被本任务核对是否存在及内容，仅按命名约定写进回滚步骤；实际回滚前应先 `ls` 确认（README §6.5 第①步即为此）。
5. **`systemctl cat signaling` 输出带 `# /etc/...` 头**：核对脚本若直接用其输出比较会产生 1 行差异，故 README/本报告统一用 `cat /etc/systemd/system/signaling.service` 取纯文件。

---

## 7. 补充证据（本轮追加实跑）

### 7.1 宿主机侧自证：有效指令一致 + systemd 语法校验通过

```
$ ssh … 'diff <(grep -vE "^\s*(#|$)" /etc/systemd/system/signaling.service) \
              <(grep -vE "^\s*(#|$)" /opt/dsh-workspaces/code/webrtc-demo/deploy/signaling.service)'
（无输出）⇒ EFFECTIVE_IDENTICAL_ON_HOST
```
> 注意：这是在**宿主机自己的视角**下比较"现网 unit"与"仓库副本（同一共享目录）"，与 §3.2 的容器侧比较互为印证。

```
$ ssh … 'systemd-analyze verify /opt/dsh-workspaces/code/webrtc-demo/deploy/signaling.service'
/etc/systemd/system/1panel-core.service:12: Unknown key name 'StartLimitIntervalSec' in section 'Service', ignoring.
/etc/systemd/system/1panel-agent.service:12: Unknown key name 'StartLimitIntervalSec' in section 'Service', ignoring.
verify_rc=0
```
> `verify_rc=0`：**我们这份 unit（含新增的 18 行 `#` 注释与补齐后的 ExecStart）通过 systemd 解析**。
> 输出的两条 warning 属宿主机上**无关** unit（`1panel-*`，与本任务无关），未被本任务修改。

### 7.2 纪律证据：宿主机服务未被本任务触碰

```
$ ssh … 'systemctl is-active signaling; systemctl show -p ActiveEnterTimestamp --value signaling; date -Is'
active
Tue 2026-09-15 23:45:25 CST        ← 上一次启动时间（= t70 部署时那次 restart）
2026-09-16T01:47:35+08:00          ← 本任务执行时刻
```
> 服务仍 `active`，且 `ActiveEnterTimestamp` 早于本任务 2 小时（t70 的时间点），**证明本任务未 daemon-reload、未 restart、未改宿主机 unit**。

### 7.3 对 §6.1「未在宿主机真的重部署」的收敛

| 环节 | 状态 |
|---|---|
| unit **语法/语义被 systemd 接受** | ✅ 已在本任务验证（§7.1 `systemd-analyze verify` rc=0） |
| unit **有效指令与现网一致** | ✅ 已双向印证（§3.2 容器侧、§7.1 宿主机侧） |
| `ExecStart` 的二进制与 flag **组合可启动** | ⚠️ 部分验证：现网同版本二进制 `8708629e…` 带/不带该 flag 均可启动并报 `roomGraceSec: 90`（§4.2 实验 B/B2）；"经 systemd `install` + `enable --now` 完整走一遍"仍留待下次部署窗口 |
