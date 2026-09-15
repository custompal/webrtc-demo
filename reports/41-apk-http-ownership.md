# t77 — `/opt/apk-http` 属主移交 uid 1000 + 发布提交点顺序固化 + uid 1000 发布演练

- 执行者：env-installer（宿主机基础设施与环境工程师）
- 任务：t77（work，deps=[t72]，attempt 1，attempt_id `35ea2964-d7b3-4e14-bc20-d9320178ff46`）
- 时间：2026-09-16 01:04 → 01:07（+0800）
- 触发原因：t72 发布时以 admin 运行，因 `/opt/apk-http/**` 属 root 而无法归档旧 served、无法重切 parts，出现约 4 分钟「served=新 / parts=旧」的半成品窗口（该次已以 root 补齐闭合）。
- 目标：让 t76 及以后**全流程 uid 1000 完成发布**，不再需要 root 补步、不再出现半成品窗口。

---

## 1. 目标与验收面

1. 把 `/opt/apk-http/{served,parts,artifacts,parts-archive}` 属主移交 **uid 1000**；systemd 服务保持现状（root 只读）。
2. **以 admin 身份完整跑一次发布演练**（含归档、分片、served 替换、四方对账、公网复验），不得出现任何 Permission denied。
3. 固化**提交点顺序**：新 served + 新 parts 先落地校验，`SOURCE.sha256` **最后**写。
4. 把权限模型、提交点顺序、回滚命令写进 `/opt/apk-http/README.md`。
5. 报告 `code/webrtc-demo/reports/41-apk-http-ownership.md`。

## 2. 执行步骤与关键命令

| # | 动作 | 命令 / 证据 |
|---|---|---|
| 1 | 探明服务运行身份与权限面 | `systemctl cat apk-http`：ExecStart `/usr/bin/python3 /opt/apk-http/serve_apk.py --apk /opt/apk-http/served/app-debug.apk`；unit **无 `User=`** ⇒ 服务以 **root** 运行（`ps` 实测 PID 664402 user=root）。故数据目录属主移交**不影响**服务读取 |
| 2 | 安装有序发布脚本 | `install -o root -g root -m 0755 /opt/dsh-workspaces/tmp/t77-publish_apk.sh /opt/apk-http/publish_apk.sh` ⇒ `root:root 0755`，8,011 B，sha256 `3f9751d0090787941066efc4a6c15ea644dc374d46223f13808b50befac02148` |
| 3 | 属主移交 | `for d in served parts artifacts parts-archive; do chown -R 1000:1000 /opt/apk-http/$d; done` → `CHOWN_EXIT=0` |
| 4 | 归位核对 | 四个目录 root 属主条目 **全部 0**；`stat` 属主均 `admin:admin`；目录 0755、文件 0644/0664 |
| 5 | admin 写权限实测 | `test -w` ×4：served / parts / artifacts / parts-archive **全部可写** |
| 6 | 服务复验（未重启） | `active`，`MainPID=664402`（启动时刻仍 2026-09-14 18:57:09），`ps` user=**root**；回环与公网 HEAD **200** / Range **206** |
| 7 | **发布演练（uid 1000 全程）** | `su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh --rehearsal --log /opt/dsh-workspaces/tmp/t77-rehearsal.log"` → `REHEARSAL_EXIT=0` |
| 8 | 演练后状态核对 | 四个目录 root 条目仍 **0**；`parts/.new` 无残留；`parts` 与归档副本逐片 sha256 一致；服务仍 664402/root |
| 9 | README 固化 | 备份 `README.md.bak-t77` 后追加「权限模型与发布提交点」章节 ⇒ `README.md` 166 行 / 12,195 B / `root:root 0644` / sha256 `566d3c36dcc285ddddf1901cbdbc95d324e247b726bdaf851be9d49c68a52dc3` |

### 2.1 权限模型（现状实测）

| 路径 | 属主 | 模式 | 角色 |
|---|---|---|---|
| `/opt/apk-http/`（根）、`serve_apk.py`、`make_parts.sh`、`publish_apk.sh`、`README.md` | `root:root` | 0755 / 0644 | 只读资产（uid 1000 可读/可执行，不可改写） |
| `served/` | `admin:admin` | 0755 | 数据目录（uid 1000 写入） |
| `parts/` | `admin:admin` | 0755 | 分片快照（`part00…`、`SHA256SUMS`、`SOURCE.sha256`） |
| `artifacts/` | `admin:admin` | 0755 | 历史 served 归档 |
| `parts-archive/` | `admin:admin` | 0755 | 历史 parts 归档 |

移交前后 root 属主条目对比：

| 目录 | 移交前 root 条目 | 移交后 root 条目 | 总条目 |
|---|---|---|---|
| `served/` | 1 | **0** | 2 |
| `parts/` | 7 | **0** | 7 |
| `artifacts/` | 3 | **0** | 3 |
| `parts-archive/` | 107 | **0** | 114（演练新增 1 个归档目录 7 项） |

### 2.2 提交点顺序（`publish_apk.sh` 强制实现）

| 步 | 动作 | 校验/失败语义 |
|---|---|---|
| ⓪ | 权限预检（4 目录 `-w`）+ 新旧 sha 比较（正式模式同 sha 拒发） | fail-fast，**零写入** |
| ① | 新 parts 切到 `parts/.new/` 并校验：拼接 sha == 新 APK sha、`SHA256SUMS` 自校验 | 失败 `exit 9`，`parts/` 未动 |
| ② | 归档旧 served → `artifacts/app-debug-<旧8>.apk`；旧 parts → `parts-archive/parts-<旧8>-<ts>/` | 仅新增副本 |
| ③ | 安装 parts：逐文件 `mv -f` 原子替换 + 清理多余旧分片 | `SOURCE.sha256` 仍指旧版 ⇒ 未提交 |
| ④ | 安装 served：`served/.app-debug.apk.new` → `mv -f` 原子替换 | 同上 |
| ⑤ | **【提交点·最后一步】** 写 `parts/SOURCE.sha256` = 新 sha256 | 此前任何中断都**不算发布成功** |
| ⑥ | 四方对账 + 回环/公网 200/206 + 公网**全量** sha 校验；打印回滚命令 | 不一致 `exit 9` |

**修正的旧行为**：`make_parts.sh` 的顺序是 `split → SHA256SUMS → SOURCE.sha256 → 合并校验`，即**先写提交标记再做校验**。该脚本仍可用于本地校验，但**不得**再用于发布；已在 README 明确写出。

## 3. 发布演练证据（uid 1000，从 `/opt/dsh-workspaces/tmp/t77-rehearsal.log` 摘录）

```
== apk-http 发布（uid=1000）==
  源 APK : /opt/dsh-workspaces/code/webrtc-demo/app/build/outputs/apk/debug/app-debug.apk
  新件   : f694a103d963f444cf1e0c5780f881967d226254cd68bc082c4309016c239901  (33419885 B)
  现行   : f694a103d963f444cf1e0c5780f881967d226254cd68bc082c4309016c239901
  模式   : 演练 --rehearsal
  权限预检：served/parts/artifacts/parts-archive 均可写 ✔
① 拼接 sha 校验通过 = f694a103…3901（4 片：8388608 8388608 8388608 8254061）；SHA256SUMS 自校验通过
② 演练：新旧同 sha，跳过 served 归档（served 内容不变）；旧 parts → parts-archive/parts-f694a103-20260916-010558-rehearsal（6 件）
③ parts 就位并校验通过（4 片）
④ served 已替换 = f694a103d963f444
⑤ 【提交点·最后一步】写 parts/SOURCE.sha256 = f694a103…3901
⑥ 四方对账：构建输出 = served = 归档 = parts = SOURCE = f694a103…3901 ⇒ 四方一致 ✔
⑦ enabled=enabled active=active MainPID=664402（服务为 root 只读，未重启）
   回环 HEAD 200 / Content-Length 33419885 / Accept-Ranges bytes；回环 Range 206（1024 B）
   公网 HEAD 200 / Content-Length 33419885 / Last-Modified 17:05:59 GMT；公网 Range 206（1024 B）
   公网全量下载 sha = f694a103…3901 ⇒ 公网全量校验 ✔
REHEARSAL_EXIT=0
```

- **无任何 Permission denied**（对照 t72 发布尝试：`cannot create … /opt/apk-http/artifacts/…: Permission denied`、`rm: cannot remove '/opt/apk-http/parts/…': Permission denied`）。
- **内容幂等**：演练模式新=旧（`f694a103…3901`）；演练前后 `parts/part00…part03` 与 `SHA256SUMS` 逐片 sha256 与归档副本一致 ⇒ 演练**未改变现役交付**。
- 演练新增归档：`/opt/apk-http/parts-archive/parts-f694a103-20260916-010558-rehearsal`（6 件）。
- 服务全程未重启（`MainPID 664402`，启动时刻 2026-09-14 18:57:09）。

## 4. 结果与产物绝对路径

| 产物 | 绝对路径 | 状态 |
|---|---|---|
| 有序发布脚本 | `/opt/apk-http/publish_apk.sh` | `root:root 0755`，8,011 B，sha256 `3f9751d0…2148` |
| README（含权限模型/提交点顺序/回滚命令/演练证据） | `/opt/apk-http/README.md` | 166 行 / 12,195 B / `root:root 0644` / sha256 `566d3c36…2dc3` |
| README 备份 | `/opt/apk-http/README.md.bak-t77` | 7,499 B（追加前原文） |
| 演练日志 | `/opt/dsh-workspaces/tmp/t77-rehearsal.log` | 见 §3 |
| 演练归档 | `/opt/apk-http/parts-archive/parts-f694a103-20260916-010558-rehearsal/` | 6 件 |
| 本报告 | `/opt/dsh-workspaces/code/webrtc-demo/reports/41-apk-http-ownership.md` | 容器同路径 |

现役交付未变：`/opt/apk-http/served/app-debug.apk` = `/opt/dsh-workspaces/artifacts/app-debug-f694a103.apk` = parts 拼接 = `SOURCE.sha256` = `f694a103d963f444cf1e0c5780f881967d226254cd68bc082c4309016c239901`。

纪律：未改任何源码（`app/**`、`signaling/**`、`webrtc/**`、`cpp/**`）；未做 git 操作；仓内仅新增本报告。

## 5. 遇到的问题及自行解决的尝试

1. **`/opt/apk-http` 根目录保持 root 属主 ⇒ uid 1000 无法在顶层新建目录**：发布脚本的 staging 改为放在**已移交属主**的 `parts/.new/`（隐藏目录，服务按精确文件名白名单提供分片，不受影响），发布结束即删除 ⇒ 无需改动根目录属主，也不新增可写顶层路径。
2. **`make_parts.sh` 提交点顺序错误**（先写 `SOURCE.sha256` 再校验）：未修改该脚本（它仍被 README 记为本地校验工具），而是新写 `publish_apk.sh` 用正确顺序；README 明确标注「发布不得用 make_parts.sh」。
3. **演练不得改变现役交付**：`--rehearsal` 模式允许新旧同 sha 且跳过 served 归档，最终逐片比对确认 `parts` 内容幂等；演练也不重启服务。
4. 分片文件模式为 `0664`（admin 的 umask 0002）而非 captain 裁定的 `0644`——**已在同日补充中归一处理，见 §7**。

## 6. 未验证与需人工介入项

1. **回滚命令未实际执行**（仅写入 README）：演练为内容幂等，做真实回滚会短暂改变现役交付；建议在 t76 发布前如出现异常时按文档执行并留证。
2. **root 误执行风险仍在**：若有人以 root 运行 `publish_apk.sh`，产物会变 root 属主；README 已给出 `chown -R 1000:1000 …` 归位命令，但**无强制拦截**（如需可在脚本内检测 `id -u != 1000` 直接拒绝——本任务未加，避免改变既有执行习惯）。
3. **`parts-archive` 体积持续增长**：演练后 119 项（2 个演练归档 + 历史各版本）；未做清理策略。
4. 服务端 `/opt/signaling`、coturn 不在本任务范围，未触碰。
5. 后续 t76 使用本脚本时，需以 `--apk <构建输出>` 且**不加** `--rehearsal` 运行（正式模式在同 sha 时会拒发，防止误重发）。

## 7. 补充（同日追加，回应 captain 裁定「目录 0755、文件 0644」）

### 7.1 移交前 / 后原始 `ls -l`（captain 要求）

**移交前（01:05 实测原文）**

```
drwxr-xr-x  6 root root  4096 Sep 16 00:22 /opt/apk-http
drwxr-xr-x  2 root root  4096 Sep 16 01:01 /opt/apk-http/artifacts
drwxr-xr-x  2 root root  4096 Sep 16 01:01 /opt/apk-http/parts
drwxr-xr-x 17 root root  4096 Sep 16 01:01 /opt/apk-http/parts-archive
drwxr-xr-x  2 root root  4096 Sep 14 18:57 /opt/apk-http/served
/opt/apk-http/parts/:
-rw-r--r-- 1 root  root  8388608 Sep 16 01:01 app-debug.apk.part00
-rw-r--r-- 1 root  root  8388608 Sep 16 01:01 app-debug.apk.part01
-rw-r--r-- 1 root  root  8388608 Sep 16 01:01 app-debug.apk.part02
-rw-r--r-- 1 root  root  8254061 Sep 16 01:01 app-debug.apk.part03
-rw-r--r-- 1 root  root      348 Sep 16 01:01 SHA256SUMS
-rw-r--r-- 1 root  root       65 Sep 16 01:01 SOURCE.sha256
/opt/apk-http/served/:
-rw-r--r-- 1 admin admin 33419885 Sep 16 01:01 app-debug.apk      ← t72 以 admin 原地替换留下的唯一 admin 文件
```
（root 属主条目：served 1 / parts 7 / artifacts 3 / parts-archive 107）

**移交 + 模式归一后（01:07 实测原文）**

```
drwxr-xr-x  2 admin admin 4096 Sep 16 01:01 /opt/apk-http/artifacts
drwxr-xr-x  2 admin admin 4096 Sep 16 01:07 /opt/apk-http/parts
drwxr-xr-x 19 admin admin 4096 Sep 16 01:07 /opt/apk-http/parts-archive
drwxr-xr-x  2 admin admin 4096 Sep 16 01:07 /opt/apk-http/served
/opt/apk-http/served/:
-rw-r--r-- 1 admin admin 33419885 Sep 16 01:07 app-debug.apk
/opt/apk-http/parts/:
-rw-r--r-- 1 admin admin 8388608 Sep 16 01:07 app-debug.apk.part00
-rw-r--r-- 1 admin admin 8388608 Sep 16 01:07 app-debug.apk.part01
-rw-r--r-- 1 admin admin 8388608 Sep 16 01:07 app-debug.apk.part02
-rw-r--r-- 1 admin admin 8254061 Sep 16 01:07 app-debug.apk.part03
-rw-r--r-- 1 admin admin     348 Sep 16 01:07 SHA256SUMS
-rw-r--r-- 1 admin admin      65 Sep 16 01:07 SOURCE.sha256
```
复验：四个目录「非常规模式文件 = 0、非常规模式目录 = 0、root 属主条目 = 0」。

### 7.2 模式归一动作与脚本加固

- `for d in served parts artifacts parts-archive; do find /opt/apk-http/$d -type d -exec chmod 0755 {} +; find /opt/apk-http/$d -type f -exec chmod 0644 {} +; done`（`CHMOD_EXIT=0`）。
- `publish_apk.sh` 加 `umask 022`（第 22 行），并在三处显式 `chmod 0644`（归档 served 行 85、parts-archive 行 93、`parts/` 行 107、`SOURCE.sha256` 行 120）⇒ **后续发布产出即成 0644/0755**，无需事后修正。
- 脚本升级：`/opt/apk-http/publish_apk.sh` = `root:root 0755` / 8,320 B / sha256 `01a9c8da8b765f855921881e2796bfb166b182d4120b840c1869d0472e2ab741`（`bash -n` 语法检查 OK）；上一版备份 `/opt/apk-http/publish_apk.sh.bak-t77`（8,011 B / sha256 `3f9751d0090787941066efc4a6c15ea644dc374d46223f13808b50befac02148`）。
- 第二轮 uid 1000 演练：`su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh --rehearsal --no-public --log /opt/dsh-workspaces/tmp/t77-rehearsal2.log"` ⇒ **REHEARSAL2_EXIT=0**；产出模式全部 `0644/0755`；四方一致 `f694a103…3901`；回环 200/206；服务 `MainPID 664402` 未重启；归档 `parts-archive/parts-f694a103-20260916-010739-rehearsal`（6 件）。

### 7.3 文档终态（本报告 §4 表内的 README 数值以此为准）

| 文件 | 行数 | 字节 | 属主/模式 | sha256 |
|---|---|---|---|---|
| `/opt/apk-http/README.md`（两次追加后终态） | 175 | 13,401 | `root:root 0644` | `a8a5e60783dd3b253ee71a920fc213aa0fffd88316362a6b267da5a16551c8b6` |
| `/opt/apk-http/README.md.bak-t77`（追加前原文） | — | 7,499 | `root:root` | — |

README 新增：「权限模型与发布提交点」（§1–3 对应内容，行 110–166）+「补充：文件模式归一 0644/0755 与脚本 umask」（行 168 起）。

现役交付未变：served = `/opt/dsh-workspaces/artifacts/app-debug-f694a103.apk` = parts 拼接 = `SOURCE.sha256` = `f694a103d963f444cf1e0c5780f881967d226254cd68bc082c4309016c239901`；服务 enabled/active、公网 HEAD 200 / Range 206。

补充说明（未决项更新）：§6 第 1 条的结论不变（回滚命令仍未实跑，captain 已同意仅在异常时按 README 执行并留证）。

## 8. 补充 2（同日追加，captain 批准裁定 ②：身份守卫）

### 8.1 改动

`publish_apk.sh` 在参数解析后新增 **fail-fast 身份守卫**：

- `id -u != 1000` 且未显式 `--allow-root` ⇒ 打印「拒绝原因 + 正确用法 + 归位命令」并 **`exit 3`**，**不产生任何写入**。
- 新增逃生开关 `--allow-root`（应急）：越过守卫但打印告警，要求事后 `chown -R 1000:1000 /opt/apk-http/{served,parts,artifacts,parts-archive}`。
- 脚本终态：`/opt/apk-http/publish_apk.sh` = `root:root 0755` / 9,559 B / sha256 `5523222b1b6e49765589daa39a1a3232582fac712303e9b0a4c02eb9f66e0f2c`（`bash -n` OK）；上一版备份 `/opt/apk-http/publish_apk.sh.bak-t77b`（8,320 B / sha256 `01a9c8da8b765f855921881e2796bfb166b182d4120b840c1869d0472e2ab741`）。

### 8.2 双向验证原始输出（完整日志 `/opt/dsh-workspaces/tmp/t77-guard-verification.log`）

**A) root 执行、不带 `--allow-root` ⇒ `A_ROOT_NO_FLAG_EXIT=3`**（被拦，零写入）

```
拒绝执行：publish_apk.sh 必须由 uid 1000(admin) 运行（当前 uid=0，user=root）。
原因：以 root 运行会把 served/parts/artifacts/parts-archive 的产物变成 root 属主，
      之后 uid 1000 无法写入（t72 期间即因此产生「served=新 / parts=旧」半成品窗口）。
正确用法：su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh [--apk PATH] [--log FILE]"
误用 root 后的归位命令：chown -R 1000:1000 /opt/apk-http/{served,parts,artifacts,parts-archive}
应急例外：确需 root 执行时显式加 --allow-root（事后必须执行上述归位命令）。
```

**B) root + `--allow-root` ⇒ `B_ROOT_ALLOW_FLAG_EXIT=9`**：先打印告警，再因 `--apk /nonexistent/app-debug.apk` 报 `ERROR: 找不到 APK` ⇒ 证明开关生效，且该次调用**未写入任何文件**。

**C) uid 1000（admin）+ `--rehearsal --no-public` ⇒ `C_ADMIN_EXIT=0`**：正常放行；四方一致 `f694a103…3901`；归档 `parts-archive/parts-f694a103-20260916-010838-rehearsal`。

### 8.3 验证后复核

四目录仍 `admin:admin 0755`、root 属主条目 **0**、非常规模式文件 **0**；`served` = `f694a103…3901`；`parts/SHA256SUMS` 4/4 OK；服务 `enabled/active`、`MainPID 664402` **未重启**；公网 HEAD 200 / Range 206。

### 8.4 文档终态（覆盖 §7.3 的 README 数值）

| 文件 | 行数 | 字节 | 属主/模式 | sha256 |
|---|---|---|---|---|
| `/opt/apk-http/README.md`（三次追加后终态，含身份守卫章节行 177 起） | 195 | 15,917 | `root:root 0644` | `6a96dcb6de8b0d9d508889655a32a496781d3dad938de3cc885fd3754c4d4eb8` |
| `/opt/apk-http/publish_apk.sh` | — | 9,559 | `root:root 0755` | `5523222b…6e0f2c` |

### 8.5 t76 交接

- t76 发布命令：`bash /opt/apk-http/publish_apk.sh --apk <构建输出> --log reports/10-t76-build.log`（**不带** `--rehearsal`、**不带** `--allow-root`，正式模式同 sha 会拒发）。
- 三次验证原始输出（`/opt/dsh-workspaces/tmp/t77-rehearsal.log`、`t77-rehearsal2.log`、`t77-guard-verification.log`）将按 captain 要求原文写进 t76 报告。
- `parts-archive` 清理策略按 captain 裁定**推迟到 t76 之后**（届时：保留最近 5 份 + 当前，脚本打印被删清单，只删 `parts-archive/**` 与 `artifacts/**`，不动 `served` 当前件）。
