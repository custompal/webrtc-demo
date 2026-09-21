# 66 — 第三轮后续口径冻结（A/B/C/D/E/F）

> Status: **frozen**（第三轮唯一判据来源）· Owner: `doc-architect` · Task: t1 (attempt 2), 2026-09-21
> 上游判据：[reports/64](64-followup-requirements.md)（第二轮口径，顶部含 captain 直接写入的 CAPTAIN RULING 段）、[reports/65](65-followup-verification.md)（第二轮独立验证）
> 下游：T2–T13（实现）、T14（独立验证 → **reports/67-followup2-verification.md**）、T15（F 归档清理）、T16（整合）、T17（独立复核 → **reports/68-followup2-review.md**）
> **判据优先级**：captain 当日裁定 / 任务契约（acceptance+verify） > 本文件 > 任何成员的自述与任务 output。
> **本文件不实现任何改动**：不改 `doc/design/SPEC.md`、不改 `doc/design/zh-CN/GLOSSARY.md`、不改 `scripts/**`、不写译文、不改索引/入口、不改 [reports/54](54-docs-freeze-manifest.md) 与 [reports/62](62-i18n-delivery.md)。
>
> **书写约定（为让本文件在第三轮工件落地前即通过自身子集门禁）**：已存在的仓库内路径写反引号；**尚未落地**的路径（六篇中文新页、CI 工作流文件、**reports/67-followup2-verification.md**、**reports/68-followup2-review.md**）写**粗体纯文本**；仓库外/主机路径（/tmp/**、//env*.sh）与**尚不存在的 CLI 开关**（--repo-mode、--numstat）写纯文本；模板中的链接一律用**全角括号**（沿用 `doc/design/zh-CN/GLOSSARY.md` §3.3 的既有先例，见该文件第 100 行的说明）。落地后可由 T16/T17 改为反引号与链接。

---

## 0. 效力、口径与使用方式

1. **默认模式门禁强度不得削弱**（硬约束）。本文件冻结的四处枚举扩展与 --repo-mode 都不得让任何既有判据从 FAIL 变成「不检查」；允许的唯一变化是 --repo-mode 下四类**在工作区外/裸克隆下不适用**的引用降为**显式计数**的 NOTE（§2.2），以及枚举覆盖面扩大。
2. 中文页规则一律按 `doc/design/zh-CN/GLOSSARY.md` 的 **v1.1.1 及其后续版本**（T5 → v1.1.2，T7 → v1.1.3，见 §3.5/§3.4）。
3. **所有工件必须同时通过两个脚本**：`bash scripts/doc-verify.sh`（交付门禁，SPEC §9）与 `bash scripts/i18n-audit.sh`（补充审计）。两者冲突时**以门禁为准**并上报 captain。
4. **行号口径**：本文件中的所有行号均为撰写时 **HEAD = 8bb69d260f6d4f5a5e0961ee980e68592a5816de**（工作树干净，`git status --porcelain` 为空）的行号。任何执行者必须先核对 HEAD；行号漂移时**按站点内容定位**，不得按旧行号盲改（T5 在 §6 插入 24 行后，GLOSSARY 中 §6 之后的站点行号整体 +24，见 §3.4.4）。
5. 每个验收项在 T14 报告中必须**独立复跑**；引用本文件时必须连**实测值与退出码**一起引用，不得只引用结论。
6. 本文件自身的冻结绑定：任一被它冻结的站点（枚举点、D 行、术语行数、计数期望值）发生变化，本文件即失效，必须由 `architect` 重出，而不是就地解释。
7. 本轮报告编号约定：**66 = 本口径文件**（T1）、**67 = 独立验证**（T14）、**68 = 独立复核**（T17）。三份均写入 `reports/`，与 [reports/64](64-followup-requirements.md)、[reports/65](65-followup-verification.md) 构成第三轮闭环。
8. **captain 裁定登记（2026-09-21，直接下达，效力高于本文件其余表述）**：
   * **D 的终局裁定（终值）**：`doc/design/zh-CN/02-architecture.md` 第 194 行终值为**冒号式**「`…中的旋转烘焙实验：已否决（rejected）。`」（= 盘面现值、T4 已交付、门禁全绿、命中预测 sha256 `b4182633…`）。见 §4.2/§4.3。
   * **未采纳的口头提议（禁止实施）**：「`…中记为已否决（rejected）的旋转烘焙实验。`」（预期 sha256 `2e7ce7ed…` / 15 883 B）曾由 captain 在交叉时点提出，**未被采纳**，仅作历史记录；T4 不得实施，T16 不得登记该值。
   * **裁定 2 — C 的四类降级清单（生效）**：就是 §2.2 的 P2 / P4 / SUBMODULE / ENVSLASH 四类，**不增不减**；第五类若出现必须报 captain 裁定并登记；`docs -> doc/design` 是仓库内符号链接，**不列入降级类**。见 §2.2 的 C-2 / C-2b。

### 0.1 撰写时基线（现场实测，2026-09-21）

```text
cd /data/dsh/home/workspace/code/webrtc-demo
git rev-parse HEAD                                   # 8bb69d260f6d4f5a5e0961ee980e68592a5816de
git status --porcelain                               # 空
bash scripts/doc-verify.sh; echo "EXIT=$?"           # PASS (3912 checks, 2 warnings) / EXIT=0
bash scripts/i18n-audit.sh; echo "EXIT=$?"           # PASS (79 checks, 0 warnings) / EXIT=0
```

| 工件 | sha256（撰写时） | 字节 | 行数 | 权限 |
|---|---|---|---|---|
| `scripts/doc-verify.sh` | `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` | 41 673 | 919 | `-r--r--r--` (0444) |
| `scripts/i18n-audit.sh` | `f93da7d5156edad9612740a30b952e5cf11e794eec57c89ec6b2b0d1c29fd82d` | 32 425 | 810 | `-rwxr-xr-x` (0755) |
| `doc/design/SPEC.md` | `b3368522392205dd02073a8adf472566b58fad8234de96fbf69fcbc801c369e5` | 67 976 | 758 | `-rw-------` (0600) |
| `doc/design/zh-CN/GLOSSARY.md` | `51e3eb34776d0f433f6d32cb945852697fe15a1f106857fa878e90f96e851007` | 27 419 | 349 | `-rw-r--r--` (0644) |
| `doc/design/zh-CN/02-architecture.md` | `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42` | 15 877 | 219 | — |
| `doc/design/README.md` | `b652775bc04d244d0ac37e222f01bf8ddd60d8ffc02f33ce4a485d65eac474cb` | 2 854 | 37 | `-rw-------` (0600) |
| `README.md` | `38c7ada64330fee0700f20ab1e2aeadd7d94618085c7187d247c7d34224bbb2c` | 3 358 | 46 | `-rw-------` (0600) |

* `scripts/doc-verify.sh` 是 **0444 只读**：T3/T8 必须先 `chmod u+w` 再改，改完**恢复 0444**（[reports/54](54-docs-freeze-manifest.md) §9.1 登记值）。`scripts/i18n-audit.sh` 保持 0755。
* 上述两个脚本的 sha256 与 [reports/54](54-docs-freeze-manifest.md) §9.1/§9.5 登记值**逐字相等**（本轮撰写时复核），即本轮起点与上一轮登记值 0 DRIFT。
* 仓库外裸克隆（同一容器、无 submodule、落点在**工作区之外**）撰写时实测：

```text
rm -rf /tmp/t1-bare-clone
git clone --depth 1 --no-recurse-submodules \
  /data/dsh/home/workspace/code/webrtc-demo /tmp/t1-bare-clone
cd /tmp/t1-bare-clone && bash scripts/doc-verify.sh; echo "EXIT=$?"
# doc-verify.sh: FAIL (39 failures, 5 warnings, 3912 checks) / EXIT=1
```

  39 条失败的**逐类计数（撰写时实测，供 T3/T14 对齐）**：

| 类别 | 条数 | 消息 / payload 形态（原始输出摘录） |
|---|---|---|
| ① P2 工作区证据缺失 | **29** | missing workspace path：env.sh ×9、env-go.sh ×8、env-container.sh ×8、tmp/t47b-captain-build.sh ×1、tmp/probe-forms-matrix-writer-ops.md ×1、../env.sh ×1、../env-container.sh ×1 |
| ② submodule 内部引用 | **4** | cited file does not exist: third_party/libwebrtc-src/…（`doc/design/08-issues-and-solutions.md` 第 311/314/468 行） |
| ③ 父目录相对 env 引用 | **6** | cited file does not exist: //env.sh ×4、//env-container.sh ×2（`doc/design/07-build-and-deploy.md` 第 23/27/28/90/91/180 行） |
| ④ P4 容器绝对路径缺失 | **0**（本落点） | 本容器内 /data/dsh/home/workspace/** 仍然存在，故本落点为 0；**GitHub runner 上必然 > 0**——这是 --repo-mode 必须处理该类的原因 |
| ⑤ 其他 | **0** | 无 |

* ③ 的原始 payload 是 `` `../env.sh:37` `` 这类**父目录相对引用**；checker 在 CIT 分支把它归一到 WORKSPACE_ROOT 下的 env 脚本名，当落点在**工作区之外**时 WORKSPACE_ROOT 塌缩为根目录 /，于是打印成 //env.sh（两斜杠是字符串拼接产物）。**判据必须按 payload 形状冻结，不能按 // 字面量**（GitHub runner 上会打印成 /home/runner/work/<repo>/env.sh，见 §2.2 的 ENVSLASH 行）。
* 若把克隆放在**工作区内**的 tmp 子目录，上两级恰为真实工作区根，则只剩 ② 的 4 条（[reports/65](65-followup-verification.md) §7.1 的冻结落点值）。**本轮未重测该落点**（未重测即不引用其结论）；上表是本轮**新测**的工作区外落点值。两者表述同一机理：**门禁是工作区门禁，不是仓库门禁**。
* 原始输出留痕（均为仓库外 /tmp，第三轮产物，属 F 的登记与 captain 的 mtime 清扫范围，不在 T15 的删除范围）：HOST: /tmp/t1-clone-gate.txt；HOST: /tmp/t1-gate.txt；HOST: /tmp/t1-audit.txt。

---

## 1. A — `scripts/i18n-audit.sh` 临时目录清理（T2）

### 1.1 缺陷事实（现状，HEAD 8bb69d2）

* 第 146–150 行：`AUDIT_TMP=$(mktemp -d "${TMPDIR:-/tmp}/i18n-audit.XXXXXX")`，随后**只有** `trap 'rm -rf "$AUDIT_TMP"' EXIT`。
* 因此：**正常退出**与显式 `exit N` 会清理；但收到 **SIGTERM / SIGINT / SIGHUP** 等未被 trap 的致命信号时，非交互 bash 直接因信号终止，**EXIT trap 不执行**，/tmp/i18n-audit.XXXXXX 空目录残留。
* 上一轮已登记为「已知限制（本轮不修）」（[reports/62](62-i18n-delivery.md) 第 228 行；实例路径为 /tmp/i18n-audit.uc8cuy，纯文本给出，因为它已被清理、本文件不作存在性断言）。本轮 A 项就是**收口该限制**。

### 1.2 冻结要求（A-1 … A-5）

* **A-1 清理覆盖面**：`scripts/i18n-audit.sh` 的临时目录清理必须覆盖 **EXIT + INT + TERM + HUP** 四个出口。推荐形态（机制不作硬性规定，效果必须等价）：

```bash
cleanup() { [ -n "${AUDIT_TMP:-}" ] && rm -rf -- "$AUDIT_TMP"; AUDIT_TMP=""; }
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM
trap 'cleanup; exit 129' HUP
```

* **A-2 退出码语义保持**：被信号终止时必须**以 128+signum 退出**（INT=130、TERM=143、HUP=129），**不得**变成 0——否则 CI 会把被强杀当成通过。`set -uo pipefail` 的其余行为不变。
* **A-3 幂等与空值守卫**：清理函数必须可重入（EXIT 与信号 trap 可能接连触发），且 AUDIT_TMP 为空时不得对空串执行 `rm -rf`（保留 `--` 与引号）。清理不得因目标已被删除而返回非零（否则会污染退出码）。
* **A-4 SIGKILL 边界（书面声明，必须写进脚本头部注释与 T2 的 output）**：**SIGKILL（9）不可捕获**，任何 trap 都无法覆盖；被 `kill -9`、内核 OOM、或 `timeout --kill-after` 兜底强杀时仍会残留一个空目录。因此 T2 的交付**只能**声明「TERM/INT/HUP 已闭合，SIGKILL 边界保留为不可消除的已知边界」，**不得**声明「任何情况下都不残留」。可选加固（不强制）：在脚本启动时清理**本次运行之前**遗留的同类空目录——如实施，必须说明判定条件（仅空目录、仅匹配本脚本前缀、仅限 TMPDIR 内），并证明不会删除并发运行的另一次审计的临时目录。
* **A-5 只改清理路径，不动判据**：`--only` 语义、Z1–Z9 的判定分支、消息文本、退出码取值（0/1/2）、stdout 的排序与格式**逐字节不变**（证明方法见 §1.4）。头部注释可以把 §1.1 的「已知限制」改写为「TERM/INT/HUP 已覆盖；SIGKILL 不可捕获」，这是**唯一允许的**语义性文字变化。

### 1.3 信号探针（T2 必须提供正面证据）

* **A-6 探针方法**：在**受控 TMPDIR** 下运行脚本，对运行中的进程发信号，再检查残留：

```bash
P=$(mktemp -d /tmp/t2-probe.XXXXXX)          # 受控 TMPDIR（探针自建，用完自删）
for SIG in TERM INT HUP; do
  TMPDIR="$P" bash scripts/i18n-audit.sh >/dev/null 2>&1 & pid=$!
  sleep 1                                     # 让脚本先进入主检查循环
  kill -"$SIG" "$pid"; wait "$pid"; rc=$?
  echo "SIG=$SIG rc=$rc residue=$(find "$P" -maxdepth 1 -name 'i18n-audit.*' | wc -l)"
done
rm -rf "$P"
```

  判据：每个信号下 **residue=0**，且 rc 分别为 143 / 130 / 129。**必须在脚本进入主检查循环之后发信号**，否则测的是「还没建目录就退出」的退化情形。T2 的 output 必须给出三条命令的原始 rc/residue 行。
* **A-7 对照负例**：同一探针在 **pristine 脚本**（`git show HEAD:scripts/i18n-audit.sh`）上必须复现残留（residue=1），否则「修复有效」的结论不成立。若 pristine 偶然未残留（竞态），必须重跑并说明；**不得**用「pristine 也通过了」收口。
* **A-8 SIGKILL 边界证据**：对修复后的脚本发 `kill -9`，如实记录「residue=1 + 空目录」。该条**预期为阳性**（残留），是**边界声明**而非缺陷；T2 不得写成失败，T14 也不得当作 finding。

### 1.4 「除修复外判据与输出不变」的证明（A-9，必须给出可复算证据）

任一方法（**至少一种**，建议同时做前两种）：

1. **pristine 对照（首选）**：`git show HEAD:scripts/i18n-audit.sh > /tmp/audit-pristine.sh`（sha256 必须等于 §0.1 的 f93da7d5 值），在同一工作树、同一参数集下分别运行 pristine 与修复版，`diff` 两份 stdout 必须**为空**、退出码必须**相等**。至少覆盖：全量一次、`--only` 三个文件一次（例如 `doc/design/zh-CN/01-requirements.md doc/design/zh-CN/GLOSSARY.md doc/design/README.md`）。
2. **逐行 diff**：`diff -u` 的 hunk **只允许**落在头部注释与 trap/清理块；每个 hunk 必须在 output 中逐条说明理由。给出 `git diff --numstat` 的结果。
3. **负例再确认**：在**仓库外副本**里制造一个真实违规（例如删掉某中文页第 2 行声明），修复版必须与原版**同样 FAIL、同样 Z-id、同样消息文本**、退出码同为 1。**禁止**在仓库工作树内制造负例。

### 1.5 A 的验收命令与判定

```bash
bash scripts/i18n-audit.sh; echo "EXIT=$?"          # 期望 PASS，EXIT=0，计数与 §0.1 基线一致（79 checks, 0 warnings）
bash scripts/doc-verify.sh; echo "EXIT=$?"          # 期望 PASS (3912 checks, 2 warnings)，EXIT=0
```

判定：A-1…A-9 全部满足；任一条缺证据即 T2 **不得**记 completed。

---

## 2. C — `scripts/doc-verify.sh` 的 --repo-mode 与 CI（T3）

### 2.1 为什么要它（现场事实）

checker 第 80 行 `WORKSPACE_ROOT=$(cd "$REPO_ROOT/../.." && pwd)` 把「仓库根的上两级」当工作区根；P2（第 714–721 行）与 P4（第 697–702 行）是**硬校验**。结果是：**任何裸克隆（含 CI）都无法在默认模式下全绿**（§0.1 实测 39 failures）。因此需要一个**显式、可计数、且不掩蔽真实缺陷**的模式，让 CI 能跑真门禁。

### 2.2 --repo-mode 精确语义（C-1 … C-6）

* **C-1 新增开关**：`bash scripts/doc-verify.sh --repo-mode`，可与 `--only` 组合（--repo-mode --only <path>）；位置无关。未知参数仍 `exit 2`。`-h/--help` 必须列出该开关及其语义。默认模式（不带该开关）**行为逐字节不变**（C-5）。
* **C-2 降级判据（唯一四类，必须按引用形状判定，不得按消息文本或落点判定）**：

| 代号 | 类别 | 判据（必须按此谓词实现） | 默认模式下的消息 |
|---|---|---|---|
| P2 | 工作区证据缺失 | PATH 记录被 path 分类函数判为 p2-workspace（`../` 前缀、`tmp/` 前缀，或名为 env.sh / env-container.sh / env-go.sh）且存在性检查失败 | missing workspace path |
| P4 | 容器绝对路径缺失 | PATH 记录被判为 p4-container（容器根及其子路径）且存在性检查失败 | missing container path |
| SUBMODULE | submodule 内部引用 | CIT/PATH 记录的目标路径落在某个 **gitlink（index 中 mode 160000）**目录下；gitlink 集合必须在运行时从 `git ls-files -s` 求得（本仓库为 `third_party/libvpx-src`、`third_party/libwebrtc-src`），**不得硬编码目录名** | cited file does not exist |
| ENVSLASH | 父目录相对 env 引用 | CIT 记录的 **payload** 形状为「若干 `../` + env 脚本名 + `:LINE`（可选 `-LINE`）」，即 07 篇第 23/27/28/90/91/180 行那一类；在克隆落点会显示为 //env.sh 或 /home/runner/work/…/env.sh | cited file does not exist |

  * ENVSLASH **必须按 payload 形状判定**：GitHub runner 上不会出现 // 字面量，按字面量实现会在 CI 静默失效（这正是「掩蔽」）。类别名沿用 captain 裁定 2 的「双斜杠 env 引用」，但判定谓词按 payload 形状（§0.1 实测），实现里对 `//env*.sh`（工作区塌缩）与 `<工作区根>/env*.sh`（runner 上）两种**显示形态**都要命中。
  * SUBMODULE 必须在**未初始化 submodule** 的 clone 中仍然识别（gitlink 在 index 中，不依赖工作区目录内容）。
* **C-2b 不得新增第五类（captain 裁定 2）**：降级清单**不增不减**，就是上表四类。若实现过程中发现确有第五类情形，**不得**静默加入降级集合——必须**先报 captain 裁定**，并在 [reports/66](66-followup2-requirements.md)、T14 报告与 [reports/62](62-i18n-delivery.md) 中登记该情形、理由与裁定结果。同时**明确排除**：`docs -> doc/design` 是**仓库内符号链接**（克隆后仍然存在，V6 在裸克隆中照样通过），**不列入降级类**；任何把它当降级项的实现在 T14 中即为 finding。
* **C-3 降级形式**：四类在 --repo-mode 下降为 **NOTE**（NOTE 永不改变退出码，SPEC A6），每条 NOTE 必须带**类别代号**与**原 payload**，形态固定为（方括号内的类别为占位）：

```text
NOTE <file>:<line> → UNVERIFIED (repo-mode: <class>) <原 problem 文本> `payload`
```

  且**必须逐条输出**（不得聚合成一句「已跳过 N 条」），使 T14 能逐条核对。
* **C-4 计数汇总（不得掩蔽的关键）**：退出前必须打印一行**机器可读**的汇总，至少含：默认模式下会报的失败总数、四类各自的降级条数、其他类别条数。示例形态（实现细节可调，字段必须齐）：

```text
doc-verify.sh: repo-mode summary (downgraded: P2=29 P4=0 SUBMODULE=4 ENVSLASH=6; default-mode failures=39; remaining failures=0)
```

  要求：① `default-mode failures` 必须等于**同一落点、同一扫描集**下默认模式的失败数（= 降级数 + 仍失败数）；② 该行在任何一次 --repo-mode 运行中都必须出现（即使降级数为 0）；③ 不得因汇总而跳过任何检查——checks 计数必须与默认模式**相等**（§0.1 实测 3912，落点无关）。
* **C-5 默认模式逐字节不变**：
  1. `git show HEAD:scripts/doc-verify.sh > /tmp/doc-verify-pristine.sh`（sha256 必须等于 §0.1 的 6c62591a 值）；
  2. 在同一工作树中，pristine 与修改版各跑一次默认模式全量，**stdout diff 必须为空**，checks/warnings/failures 三元组相等，退出码相等；
  3. 至少再对 `--only` 跑一次（例如 `--only doc/design/SPEC.md` 与 `--only doc/design/zh-CN`），同样要求 stdout 逐字节相同；
  4. 修改版的头部注释可以新增 --repo-mode 说明（不影响 stdout），但**不得**在默认模式下新增任何 NOTE/WARN（warnings 必须保持 2）。
* **C-6 退出码**：--repo-mode 下 `exit 0` ⟺ **降级之外的失败数为 0**；存在任何未降级失败即 `exit 1`，消息与默认模式**逐字相同**（同一 problem / suggested-fix 文本）。调用或环境错误仍 `exit 2`。

### 2.3 不得掩蔽的探针矩阵（C-7，T3 必须逐条给出原始输出）

全部探针在**仓库外副本**中执行（禁止在仓库工作树制造负例）。副本构造：`git clone --depth 1 --no-recurse-submodules <repo> <clone>`；基准正对照先跑一次并记录。

| 探针 | 在被测副本中的动作 | --repo-mode 的**期望** |
|---|---|---|
| P-M0 正对照 | 不改动（未初始化 submodule 的 clone） | `EXIT=0`；降级计数 = SUBMODULE 4（+该落点的 P2/ENVSLASH/P4），remaining failures=0 |
| P-M1 真实缺失 | 删除一个**非** submodule、**非** env 的仓库内被引用文件（例如 `signaling/room/room.go`） | `EXIT=1`，消息含 missing repo path |
| P-M2 引用行越界 | 把某条 path:LINE 引用的行号改到超出文件总行数 | `EXIT=1`，消息形态与默认模式相同 |
| P-M3 切换器缺失 | 删掉某中文页第 1 行切换器 | `EXIT=1`，消息含 missing language switcher |
| P-M4 声明缺失 | 删掉某中文页第 2 行译文声明 | `EXIT=1`，消息含 missing translation disclaimer |
| P-M5 相对链接断裂 | 破坏一处相对 markdown 链接目标 | `EXIT=1`，V4 消息 |
| P-M6 归档 stub 断裂 | 改动一个 `doc/*.md` stub 使其不指向 `doc/archive/` | `EXIT=1`，V5 消息 |
| P-M7 目录约定 | 把 `docs` 符号链接换成真实目录 | `EXIT=1`，V6 消息 |

* P-M1/P-M2 的删除或篡改必须**在副本内**完成，且必须提供「篡改确已生效」的 guard（例如 `git -C <clone> status` 非空或 `test ! -e`）。
* **P4 类的探针（本容器必需）**：本容器 /data/dsh/home/workspace/** 存在，无法用删除法制造 P4 失败，且 `unshare` 不可用（实测 `unshare -rm true` 返回 Operation not permitted）。因此 P4 的计数证据按下列**声明式**方法取得：在**仓库外副本**中对 checker 副本做**一行**改动，仅把 P4 分支的存在性测试指向一个不存在的根（例如把存在性测试的路径前缀换成 /nonexistent-ci 前缀），**不改分类逻辑**；用该副本测得 P4 记录数，再用 --repo-mode 证明同样的记录被降级为 class=P4 的 NOTE 且退出码为 0。该副本**不是交付物**，其改动必须逐字声明，并在 output 与 T14 报告中如实标注「P4 计数来自声明式单行探针副本，非**本容器**真实缺失」。**不得**把容器根常量整体改掉（那会把 P4 记录重分类为 P3，探针结论无效）。
* 探针结果必须给出**逐条原始 stdout**（或至少 grep 出的失败行 + 退出码），并汇总成表。

### 2.4 CI 工作流规范（C-8，文件 **.github/workflows/docs.yml**）

冻结要求（YAML 具体缩进由 T3 决定，下列**逐条可核对**）：

1. **路径固定**：**.github/workflows/docs.yml**（T3 的 inScope 已含该路径；仓库当前**没有** `.github/` 目录，需新建）。
2. **触发**：`push`（`branches: [main]`，本仓库默认分支实测为 main）+ `pull_request` + `workflow_dispatch`。不得只做 workflow_dispatch（那样 PR 不会红）。
3. **权限**：`permissions: contents: read`（不得给写权限）。
4. **runner**：`runs-on: ubuntu-latest`；`timeout-minutes` 显式给出（建议 ≤ 15；两个脚本在本容器实测均为秒级）。
5. **checkout**：`actions/checkout@v4` 且**显式** `with: submodules: false`（否则会去拉两个巨大 submodule，且 SUBMODULE 类将不再出现）。
6. **步骤**：至少两个**独立命名**步骤——先 `bash scripts/i18n-audit.sh`，再 `bash scripts/doc-verify.sh --repo-mode`；使用 bash -euo pipefail（或 set -e），**不得**出现 `continue-on-error: true`、`|| true`、`set +e`、`--only`。
7. **红即红**：任一步非零退出即工作流失败；两个脚本的退出码语义不得在 workflow 内被改写。
8. **不引入新依赖**：不得 apt-get install、不得运行 Gradle、不得依赖 secrets 或网络访问（除 checkout 本身）。
9. **注释要求**：workflow 内必须写明「CI 跑的是 --repo-mode（工作区外/裸克隆语义），**CI 绿 ≠ 工作区门禁绿**；工作区门禁（默认模式）仍由作者本地与 T14 执行」。
10. **验证工具的现实边界（必须如实声明）**：本容器**没有** YAML 解析器（ruby 不存在、node 无 yaml 模块、无 python3）。因此 T3 必须：(a) 只使用 YAML 的朴素子集（映射/序列/字符串，避免锚点与多行折叠）；(b) 自写一个**最小结构校验器**（仓库外，例如 node 脚本逐行校验缩进层级、键名、表达式括号闭合、on/jobs/steps 存在）或等价的严格人工核对；(c) 在 output 与 T14 报告中写明「无完整 YAML 解析器，校验方法 = 最小校验器 + 逐条规范核对」，**不得**写成「YAML 已由解析器验证」。

### 2.5 C 的验收命令与判定

```bash
bash scripts/doc-verify.sh; echo "EXIT=$?"                    # 默认模式：PASS (3912 checks, 2 warnings) / 0
bash scripts/doc-verify.sh --repo-mode; echo "EXIT=$?"        # 工作区内（证据齐全）：0，且降级计数全为 0
bash -n scripts/doc-verify.sh                                 # 语法
# 裸克隆（工作区外、无 submodule）：
bash scripts/doc-verify.sh --repo-mode; echo "EXIT=$?"        # 期望 0 + 汇总行（P2/SUBMODULE/ENVSLASH 计数）
bash scripts/i18n-audit.sh; echo "EXIT=$?"                    # 仓库内仍 PASS / 0
```

判定：C-1…C-8 全部满足，且 §2.3 的探针矩阵逐条给出**原始**输出。**任何「把非四类失败也降级」的实现一律 FAIL**，T3 不得完成；T14 必须独立复跑 P-M1…P-M7。

---

## 3. B — 06–11 六篇翻译、GLOSSARY 与索引（T5/T6/T7/T8/T9/T10–T13）

### 3.1 六篇分篇大纲与对等要求（B-1 … B-5）

* **B-1 分篇归属（已由任务图冻结，不得改）**：T10 = 06-flows + 10-code-map；T11 = 07-build-and-deploy + 08-issues-and-solutions；T12 = 09-verification-and-limitations + 11-coding-standards。每篇同时改**中文新页**与**英文页的一行切换器**（§3.3）。
* **B-2 对等要求（判据以 `scripts/i18n-audit.sh` 的 Z6/Z7/Z8 为准，人工不得放宽）**：
  1. **小节编号与标题层级**：中文页的二级/三级标题与英文页**一一对应**，**不得新增、删除、合并、拆分小节**；标题文字为译文，编号原样。
  2. **表格**：行序、行数、列数一致；英文页每个数据行对应中文页一个数据行（下表 tbl 列为 Z8 的判定基数）。
  3. **代码块与 Mermaid**：数量与顺序一致（blocks / mermaid 列）；围栏 info string 原样（如 bash、mermaid）。
  4. **path:LINE 引用集合**：与英文页**去重后逐元素相等**（Z6），且每个目标文件存在、行号在界内（沿用门禁 V1/A1/A13）。
  5. **code span 多重集**：与英文页相等（Z7）——英文页反引号包住的内容必须原样保留在反引号内（GLOSSARY §5.1 第 4 条）。
  6. **不译清单**：GLOSSARY §5.1 的全部 8 类原样保留（路径、字段名、日志键、命令、证据锚点、产品名等）。
  7. **状态词**：GLOSSARY §5.5 的「中文定译（英文原词）」写法，全角括号，连续定译串不得被打断（禁止把 `被` 插进 `已否决（rejected）`）。
  8. **禁译词**：中文页中不得出现「座位」「会议室」（审计 Z5(a)；正确用词为「席位」「房间」）。
* **B-3 写作时实测基线（HEAD 值，供译前译后自比对；h2/h3/tbl/blocks 用审计 Z8 的同一算法求得，mermaid 为 mermaid 围栏数）**：

| 篇 | 英文页 | 字节/行数 | h2 | h3 | tbl | blocks | mermaid |
|---|---|---|---|---|---|---|---|
| 06 | `doc/design/06-flows.md` | 28 998 / 504 | 10 | 0 | 13 | 23 | **7** |
| 07 | `doc/design/07-build-and-deploy.md` | 11 611 / 204 | 9 | 9 | 36 | 3 | 0 |
| 08 | `doc/design/08-issues-and-solutions.md` | 55 809 / 497 | 10 | 34 | **300** | 0 | 0 |
| 09 | `doc/design/09-verification-and-limitations.md` | 23 937 / 215 | 8 | 4 | 105 | 3 | 0 |
| 10 | `doc/design/10-code-map.md` | 20 285 / 268 | 7 | 6 | 149 | 0 | 0 |
| 11 | `doc/design/11-coding-standards.md` | 18 869 / 237 | 10 | 1 | 61 | 1 | 0 |

  逐篇小节标题清单（**逐字**）见 §3.9 附录。
* **B-4 规模预期**：中文页行数**不要求**等于英文页（中英排版不同），但 h2/h3/tbl/blocks 四项必须相等，且 Z6/Z7 必须相等。**不得**为追求行数相同而增删内容（空行除外）。
* **B-5 只加一行**：英文页的改动**只允许**在文件顶部新增一行切换器，`git diff --numstat` 必须是**纯插入**；**不得**顺手改英文页的任何其他内容（含 Open items 中已过时的说法——那属于历史文本，不改）。

### 3.2 新页页首模板（B-6，逐字）

新中文页（**doc/design/zh-CN/06-flows.md** … **doc/design/zh-CN/11-coding-standards.md**）的第 1、2 行逐字为（以 06 为例，其余同构换名）：

```text
> **中文（默认）** · [English]（../06-flows.md）
> 译文：若与英文原文冲突，以英文原文为准。
```

* 第 1 行 = **语言切换器**（门禁 V14，GLOSSARY §3.2）；第 2 行 = **逐字译文声明**（门禁 V15，GLOSSARY §4 Y-2）。两行都必须独立成行、位于前 8 行内。
* **模板警告（同 GLOSSARY §3.3 第 100 行的既有做法）**：上面第 1 行**故意使用全角括号**，以便本文件在目标页尚未落地时通过自身的链接门禁（V4）；**实际文件必须写半角圆括号**（把模板里的全角左右括号换成半角），链接文字为 `English`，目标为 ../06-flows.md。写成全角括号的页面其链接不可渲染、不可达，门禁直接 FAIL。
* 第 3 行为空行，其后是 `# 06 — <中文标题>` 等正文。**不得**在切换器行或声明行上追加任何其它文本（GLOSSARY §3.1）。
* 既有 01–05 的页首形态就是这一形态，可作为对照。

### 3.3 英文页反向切换行（B-7，逐字）

英文页（`doc/design/06-flows.md` … `doc/design/11-coding-standards.md`）在**文件第 1 行**新增（其余内容不动，原第 1 行下移一行）：

```text
> [中文（默认）]（zh-CN/06-flows.md） · English
```

* 实际文件把模板里的全角左右括号换成**半角**；链接文字为「中文（默认）」，链接目标为相对该英文页自身目录的 zh-CN 同名页，必须可达（V14 校验目标存在）。本模板同样为通过自身 V4 而使用全角括号。
* 插入位置在前 8 行内即可，但**冻结为第 1 行**，理由：与 01–05 的既成形态一致，且 `git diff` 是纯插入（B-5）。
* 六篇的切换行文本必须**只**按文件名替换，其余字符（行首的 `>` 与空格、方括号标签「中文（默认）」、半角圆括号包裹的链接、` · ` 分隔符、结尾的 English 字样）逐字一致。

### 3.4 枚举扩展：四处清单与**全部**站点（B-8 … B-11）

**B-8 必须一致的三份口径**：以下四处枚举必须在**同一轮**内扩到 **NN = 01–11**，且口径必须一致（同一集合、同一排除项）：

| # | 位置 | 任务 | 语义 |
|---|---|---|---|
| 1 | `doc/design/SPEC.md` §7.6（配对清单 + V15 声明范围） | T6 | 规范真值（normative） |
| 2 | `doc/design/zh-CN/GLOSSARY.md` §3.4 + §4 Y-1 | T7 | 中文侧规范 |
| 3 | `scripts/doc-verify.sh` V14 配对 + V15 声明 | T8 | 门禁实现 |
| 4 | `scripts/i18n-audit.sh` Z1 配对 + Z2 声明 + Z6/Z7/Z8 配对索引 | T9 | 审计实现 |

**B-9 集合一致性判据（T14 必查）**：SPEC 的集合 = GLOSSARY 的集合 = checker 的集合 = audit 的集合 = **{根 README 对、doc/design 索引对、zh-CN/NN ↔ EN/NN（NN = 01…11）}** 共 **13 对**（2 入口 + 11 正文）。任一处的集合不同即为 blocker finding。

#### 3.4.1 `doc/design/SPEC.md`（T6，v1.7.2）——逐站点

| 站点（HEAD 行号） | 现值 | 改为 | 说明 |
|---|---|---|---|
| 第 3 行 | v1.7.1 | v1.7.2 | 只改版本 token，owner 与 task id 不动 |
| 第 696 行（§7.6） | for NN = 01–05. | for NN = 01–11. | 配对清单枚举（规范） |
| 第 704 行（§7.6 V15） | for NN = 01–05 plus the | for NN = 01–11 plus the | 声明清单枚举（规范） |
| Changelog（第 758 行之后） | — | 新增一行 1.7.2：枚举扩到 01–11，写明「仅枚举扩展，无规则语义变化」并指向 GLOSSARY §3.4/§4 | 必须显式声明无规则变化 |

* **预期 diff 形状**：`git diff --unified=0 doc/design/SPEC.md` 恰好 **4 个 hunk**（3 处替换 + 1 处新增），numstat 为 `4 3`。
* **零改动区**：§7 规则表 V1–V15、§7.1 P1–P5、§2.3、§6 E1、§2 R12–R14、§9、既有 Changelog 行逐字节不动。
* SPEC 权限为 0600；[reports/54](54-docs-freeze-manifest.md) §9.3 登记过它的上一轮摘要；改后由 T16 重签（§7）。

#### 3.4.2 `scripts/doc-verify.sh`（T8）——逐站点

| 站点（HEAD 行号） | 现值 | 改为 |
|---|---|---|
| 第 41 行（头注释） | NN = 01..05 | NN = 01..11 |
| 第 43 行（头注释） | zh-CN/01-05 | zh-CN/01-11 |
| 第 610 行（注释） | NN = 01-05 | NN = 01-11 |
| 第 611 行（**V14 实现**） | `0[1-5]-*.md) pair_add "$zf" "doc/design/$base" "tree" ;;` | 通配扩为 01–11（见下） |
| 第 818 行（**V15 实现**） | `"$ZH_TREE"/SPEC-guide.md\|"$ZH_TREE"/0[1-5]-*.md) : ;;` | 通配扩为 01–11（见下） |

* 通配写法固定为 `0[1-9]-*.md|1[01]-*.md`（等价于 01–11，且**不会**误吞 12+ 或 SPEC-guide）。
* **不得**改 SWITCHER_WINDOW、不得改任何消息文本、不得在默认模式下新增 NOTE/WARN（C-5）。
* **T8 负例（必须给原始输出，均在仓库外副本内）**：
  1. 删除 **doc/design/zh-CN/06-flows.md** 第 1 行切换器 → `EXIT=1` + missing language switcher；
  2. 删除 **doc/design/zh-CN/11-coding-standards.md** 第 2 行声明 → `EXIT=1` + missing translation disclaimer；
  3. 把 **doc/design/zh-CN/06-flows.md** 第 1 行链接指向 09 篇的英文页 → `EXIT=1`（指向错误文件）；
  4. **正对照**：完整六页落地后全量 `EXIT=0`，且 checks 计数比 §0.1 的 3912 **增大**（新增 12 个配对义务），T8 必须给出新旧计数。
* 文件为 0444：先 `chmod u+w`，改后 `chmod 444` 并核对 `ls -l`。

#### 3.4.3 `scripts/i18n-audit.sh`（T9）——逐站点

| 站点（HEAD 行号） | 现值 | 改为 |
|---|---|---|
| 第 14–16 行（头注释 Z2） | in the 6 Y-1 files / exactly 6 non-fenced | in the 12 Y-1 files / exactly 12 non-fenced |
| 第 17 行（头注释 Z3） | 74 data rows … # = 1..74 | 98 data rows … # = 1..98（= 74 + 24，见 §3.5） |
| 第 30/33/34 行（头注释 Z6/Z7/Z8） | 5 body pairs | 11 body pairs |
| 第 52 行（头注释 --only 表） | zh body page 01–05 | zh body page 01–11 |
| 第 53–56 行 | 已列 SPEC-guide / GLOSSARY / 两个 README 与 EN 页 | 语义不变，无需改（若提及 01–05 必须同步） |
| 第 90–91 行（注释） | 7 frozen pairs；indexes 2..6 are the five body pairs | 13 frozen pairs；indexes 2..12 are the eleven body pairs |
| 第 92–100 行 PAIR_ZH | 7 项 | **+6 项**：06-flows、07-build-and-deploy、08-issues-and-solutions、09-verification-and-limitations、10-code-map、11-coding-standards（顺序 06→11，追加在 05 之后） |
| 第 101–109 行 PAIR_EN | 7 项 | **+6 项**：对应英文页（同序） |
| 第 110 行 BODY_PAIRS | `(2 3 4 5 6)` | `(2 3 4 5 6 7 8 9 10 11 12)`（**11 个正文对**；Z6/Z7/Z8 由该数组驱动，漏改会导致新页**不被对等校验**） |
| 第 111–118 行 Y1 | 6 项 | **+6 项**：zh-CN 的 06…11（Z2、Z5(a) 由该数组驱动） |
| 第 316 行（Z2 全仓唯一性） | expected 6 | expected 12 |
| 第 350 行（Z3 行数） | `if [ "$rows" != 74 ]` | `if [ "$rows" != 98 ]` |
| 第 351 行（Z3 消息） | expected 74 / 74-row | expected 98 / 98-row |
| 第 360 行（Z3 消息文本） | (1..74 in order) | (1..98 in order) |
| 第 441 行（Z5 函数注释） | one of the 6 translated pages | one of the 12 translated pages |

* **不得**改：SWITCHER_WINDOW、DISCLAIMER 文本、FORBIDDEN_COPIES、LEGACY_SWITCHER、STATUS_ZH（**Z4 仍为 6 个冻结状态词**，本次**不得**把「已复现」「静态」等新词加进去——那会改变默认模式的判定面）、Z1/Z2/Z4…Z9 的判定逻辑与消息形态。
* **T9 负例（仓库外副本）**：① 删 09 篇中文页的声明 → FAIL[Z2]（且全仓计数 11 ≠ 12）；② 删 07 篇中文页的切换器 → FAIL[Z1]；③ 把 10 篇中文页的一条引用行号改错 → FAIL[Z6]（证明 BODY_PAIRS 扩展后**新页真的被对等校验**）；④ 正对照全绿。
* **T9 与 T5 的计数耦合（必须遵守）**：Z3 的期望值 = GLOSSARY §6 的**最终行数**，本轮冻结为 **98**（74 + §3.5 的 24 条）。若 T5 未按 §3.5 落地（少列或多列），即以本文件为准回退修正并上报 captain 重派，**不得**由 T9 迁就 T5 的偏差。
* 改后仍为 **0755**、`bash -n` 通过。

#### 3.4.4 行号漂移警告（T7 必读）

T5 在 `doc/design/zh-CN/GLOSSARY.md` 的 §6 末尾（HEAD 第 272 行之后）**插入 24 行**；因此 §6 之后的所有站点行号整体 **+24**（§1–§5 的站点不变）。T7 **必须按站点内容定位**（按 L3、S5、Y-1 等标识与该行完整文本），并在 output 中同时给出「HEAD 行号」与「T5 之后实测行号」，不得按旧行号盲改。

### 3.5 GLOSSARY §6 术语扩充（B-12 … B-14，T5）

* **B-12 规则**：**只新增 §6 术语行 + 递增版本号**；§2–§5、§6.1、§6.2、§7、§8 的规则正文**逐字节不动**（§3.4.4 所列站点属 T7，不在 T5 内）。新增术语必须取自英文原文与源码的**实际用词**，表内「首现说明」必须可核。
* **B-13 数量冻结**：新增 **24 条**（编号 **75–98**），§6 行数 **74 → 98**。该数字同时是 T9 的 Z3 期望值与 T16 的登记值来源。**不得**自行增减（增删即触发 §3.7 的一致性回退）。
* **B-14 版本**：本轮 GLOSSARY 版本链 **v1.1.1 →（T5）v1.1.2 →（T7）v1.1.3**。T5 必须同步改三处：第 4 行标题中的版本、第 6 行 Status 行、文件末尾「附：本文件的自证」一行的版本，并在 Status 行写明本版变更 =「仅新增 §6 术语 24 条，无规则变化」。

**冻结术语清单（T5 照单落地；第三列为中文定译，第四列填入 §6 表的「首现说明与边界」）**

| # | 英文原词 | 中文定译 | 首现说明与边界 |
|---|---|---|---|
| 75 | flow | 流程 | 06 篇标题用词；指端到端业务流程，不译成「流」 |
| 76 | step list | 步骤清单 | 编号步骤 + 引用；与同篇 Mermaid 图一一对应 |
| 77 | device capture | 设备采集 | 真机日志证据；采集文件路径不译（见 §5.1） |
| 78 | evidence index | 证据索引 | 各篇末节的「声明 → 引用 → 证据」表 |
| 79 | open item | 待办事项 | 篇末未闭合项；不译成「开放项」 |
| 80 | root cause | 根因 | 缺陷史条目字段；不译成别的变体 |
| 81 | symptom | 症状 | 缺陷史条目字段；不译成「现象」 |
| 82 | log signature | 日志特征 | 缺陷史条目字段；日志键与事件名原样 |
| 83 | residual risk | 残留风险 | 修复后的剩余风险；不译成「剩余风险」 |
| 84 | counter-pattern | 反例模式 | 规范表列名；指该规则要防止的写法 |
| 85 | build stage | 构建阶段 | 五阶段构建；阶段名与命令不译 |
| 86 | build invariant | 构建不变量 | 构建前后必须成立的条件 |
| 87 | release chain | 发布链 | 冻结 → 打包 → 发布 → 核对的全链 |
| 88 | rollback | 回滚 | 发布失败后的回退动作；不译成「退回」 |
| 89 | identity guard | 身份守卫 | 发布前校验制品身份 |
| 90 | shard | 分片 | 发布产物切分；不译成「碎片」 |
| 91 | reconcile | 对账 | 分片与总清单逐项核对；不译成「调和」 |
| 92 | host service | 主机服务 | HOST 侧 systemd 服务；服务名不译 |
| 93 | toolchain | 工具链 | 版本表标题用词 |
| 94 | verification matrix | 验证矩阵 | 09 篇 §3 主表 |
| 95 | reproduced | 已复现 | 09 篇 §3 图例取值；**不进 §5.5 冻结状态词表**（Z4 仍为 6 词） |
| 96 | static | 静态 | 09 篇 §3 图例取值（仅读源码、未执行）；同上不进 §5.5 |
| 97 | byte-reproducible | 字节可复现 | 08 篇 §6.4 的否定结论用「不可字节复现」 |
| 98 | code map | 代码地图 | 10 篇标题用词；不译成「代码索引」 |

* **触发规避（必须核对）**：第 95 与 96 条的第四列**不得**出现「状态词」三个字。`scripts/i18n-audit.sh` 的 Z5(b) 规定：**§6 行中第四列提到「状态词」的行，其中文定译必须是 §5.5 的 6 个冻结词之一**，否则 FAIL[Z5]。上表已按此措辞（用「图例取值」）。
* T5 必须给出：新 sha256 / 字节 / 行数；§6 旧 74 行的**逐字未改**证据（`git diff` 只显示新增行、numstat 的第二列为 0）；新增 24 条的取材依据（英文页的 path:LINE 或英文原文引文）。

### 3.6 索引与入口去 planned（B-15 … B-17，T13）

* **B-15 `doc/design/README.md`（索引）**：§2 导航表中 06–11 六行的链接**改为中文页**并**删除「待翻译（planned）」**，写法与 01–05 行对齐（第 06 行的行形态 = 编号列 `06`、文档列一个指向 zh-CN/06-flows.md 的半角链接、范围列中文说明；其余五行同构）。范围表述用中文，可意译英文页 §1 的范围句；**不得**新增导航行、不得改 01–05 与 SPEC 行、不得改 §1/§3/§4。
* **B-16 根 `README.md`（入口）**：§1 表中 06–11 六行同法改为中文页链接并删 planned；§3/§4/§5 中对 07/09/11 的**行内提示**（现均带「待翻译（planned）」）必须**一并同步**。**不得**改标题、不得改 §2（生成表）、§6（索引与历史）的语义。
* **B-17 不得留残留**：改完后 `grep -rn "待翻译（planned）" README.md doc/design/README.md` 必须**无输出**；且 `grep -rn planned doc/design/zh-CN/GLOSSARY.md` 的命中只允许出现在 §2.3/§8.2 的**历史叙述**里（T7 已按 §3.4.3 处理 §2.3 与 §5.5；若仍有断言式 planned 语句即为 T7 漏改）。
* 两份文件均为 0600；T13 改后给出新 sha256 / 字节 / 行数与 numstat。

### 3.7 交叉一致性硬约束与本轮并发纪律（B-18）

1. **四处枚举一致**（B-8/B-9）：SPEC、GLOSSARY、checker、audit 的配对与声明集合必须逐元素相同；T14 必须以脚本方式比对四者（例如从四份文件提取 NN 集合后 diff），不得只看「都改过了」。
2. **两个计数一致**：GLOSSARY §6 行数 = audit Z3 期望值 = [reports/54](54-docs-freeze-manifest.md) 登记值 = **98**；Y-1 文件数 = audit Z2 全仓期望值 = **12**。
3. **页面集合一致**：zh-CN 下新增 6 页 ⟺ doc/design 下 6 个英文页新增切换行；缺一侧即 Z1/V14 FAIL。
4. **顺序纪律**：T5 → T6 → T7（T10–T12 的前置）；T2 → T9、T3 → T8。T8/T9 与 T6/T7 之间**无依赖**，但任一先落地都不得让双门禁在**最终交付**时变红——若中途出现「GLOSSARY 已 98 行而 audit 仍期望 74」这类中间态，属**预期**，只能由 T9 收口，且 T14 必须在收口后复跑全量。
5. **不做的事**：不 `git commit`、不新增或删除页面（除 6 个中文新页）、不改 `docs` 符号链接、不改 `doc/design/_generated/**`、不改 01–05 的中文页（除 T4 的 02:194 一行）、不改 `README.en.md` 与 `doc/design/README.en.md`。

### 3.8 B 的验收命令与判定

```bash
bash scripts/doc-verify.sh --only doc/design/zh-CN/06-flows.md   # 每篇自身子集：EXIT=0
bash scripts/doc-verify.sh; echo "EXIT=$?"                       # 全量 0；checks 计数 > 3912
bash scripts/i18n-audit.sh; echo "EXIT=$?"                       # 全量 0；Z2/Z3 计数据实（12 / 98）
bash scripts/i18n-audit.sh --only doc/design/zh-CN/06-flows.md   # 单篇对等：EXIT=0
```

判定：B-1…B-18 全部满足。六篇中的任何一篇若出现「新增或合并小节」「表格行数不等」「code span 多重集不等」「引用集合不等」「planned 残留」，该篇 FAIL，且**不得**以「内容更准确」为由豁免。

### 3.9 附录：06–11 英文页小节标题清单（HEAD 逐字，供 T10–T12 对齐）

```text
06-flows.md: 1. Scope | 2. Flow 1 — Create a room, join, first offer/answer, ICE connectivity | 3. Flow 2 — Transient signalling drop, reconnect, and reclaiming the seat | 4. Flow 3 — Room invalidated (room not found) and the recoverable state | 5. Flow 4 — Peer leaves, waiting for a peer, entering again | 6. Flow 5 — ICE restart and the anti-glare division of labour | 7. Flow 6 — Encoder fallback when the self-built encoder cannot keep up | 8. Flow 7 — Quality degradation and the drop policy | 9. Evidence index | 10. Open items
07-build-and-deploy.md: 1. Scope | 2. Toolchain versions | 3. Build stages and their gates (3.1..3.6) | 4. Build invariants | 5. Release chain (5.1 Identity guard, 5.2 Freeze, shard and reconcile, 5.3 Rollback) | 6. Host services | 7. Incident handling | 8. Evidence index | 9. Open items
08-issues-and-solutions.md: 1. Scope | 2. Module: signaling, rooms and reconnect (2.1..2.9) | 3. Module: ICE, network, NAT and TURN (3.1..3.8) | 4. Module: the custom VP9 encoder (4.1..4.9) | 5. Module: rendering and the call UI state machine (5.1..5.2) | 6. Module: build, release and diagnostics integrity (6.1..6.4) | 7. Module: tooling and environment (7.1..7.2) | 8. Rejected and disproven approaches | 9. Evidence index | 10. Open items
09-verification-and-limitations.md: 1. Scope | 2. Verification method and its own validity (2.1 The gate, 2.2 Known checker limitation: it is not byte-stable, 2.3 The digest this document was verified with, 2.4 Tag-face probes) | 3. Verification matrix | 4. Known limitations | 5. Contract errata (D-1 .. D-6) | 6. Unverified items and how to close them | 7. Evidence index | 8. Open items
10-code-map.md: 1. Scope | 2. Code map (2.1..2.4) | 3. Report index | 4. Document map (4.1..4.2) | 5. Symptom to code | 6. Evidence index | 7. Open items
11-coding-standards.md: 1. Scope | 2. General conventions | 3. Kotlin and Android | 4. C++ and JNI | 5. Go | 6. Scripts and build | 7. Change safety checklist | 8. Documentation and code consistency (8.1 Document path notation (frozen)) | 9. Evidence index | 10. Open items
```

---

## 4. D — `doc/design/zh-CN/02-architecture.md` 第 194 行句读润色（T4）

### 4.1 现状（HEAD 逐字，实测）

```text
193: * **I-5** — 媒体几何（旋转、stride、缓冲容量）在每个方向上只在一处决定；见
194:   doc/design/08-issues-and-solutions.md 中已否决（rejected）的旋转烘焙实验。
```

* 英文对端 `doc/design/02-architecture.md` 第 193–194 行为「… is decided in exactly one place per direction;」与「see the rejected rotation-baking experiment in doc/design/08-issues-and-solutions.md.」
* 第 194 行现形态**合规**（连续定译串完整、全角标点、行首两空格），但存留为「句读偏生硬」的美容项（[reports/64](64-followup-requirements.md) §0.1/§4.2、[reports/62](62-i18n-delivery.md) §14.6）。第三轮的 D 项就是**收口该美容项**。

### 4.2 冻结目标行（D-1，**逐字**；**captain 终局裁定**，2026-09-21；T4 已按此交付）

```text
  doc/design/08-issues-and-solutions.md 中的旋转烘焙实验：已否决（rejected）。
```

* **终局裁定（本条）**：**唯一终值 = 盘面现值 = 上式**。T4 已按该值落盘（sha256 `b4182633a78f9895d0ae3ced1cf0d9555ab764ac2ce4347244c15ded16f116cd` / 15 880 B / 219 行 / numstat `1 1`），三条验收命令全绿。captain 明确：`reports/66` 原始 §D 的这条冻结值即为终值，且 `5c21134b` 消息「D 的冻结行以 reports/66 §D 为准」亦指本条。
* **逐字要求**：行首**两个半角空格**；路径为纯文本（**不加反引号**，英文对端同样无反引号）；全角冒号与全角句号；`已否决（rejected）` 为**连续**定译串（全角括号）；行尾**仅一个**句号，随后换行；行内不得再有其他字符。
* **第 193 行逐字不动**（`* **I-5** — 媒体几何（旋转、stride、缓冲容量）在每个方向上只在一处决定；见`）。
* **禁止的两类写法**：① 写成「已被否决」——`被` 插在 `已` 与 `否决` 之间会打断连续定译串（GLOSSARY §5.5，[reports/64](64-followup-requirements.md) §4.2 的 Z4 反例）；② 改用「`…中记为已否决（rejected）的…`」——那是 captain 在交叉时点的**口头提议，未采纳**（§0 第 8 条），实施即为偏离冻结值。
* **对 02 篇的写权限已关闭**：T4 终态、已完成、已接受；**任何成员不得再对 `doc/design/zh-CN/02-architecture.md` 写入**（含 T16/T17；只读测量不受限）。

### 4.3 量化判据（D-2）

| 项 | 值（实测/预测） |
|---|---|
| 改动行数 | **1 行**（numstat 为 `1 1`；`--unified=0` 为 1 个 hunk） |
| 第 194 行字节 | HEAD 90 → **93** |
| 文件 | HEAD 219 行 / 15 877 B → **219 行 / 15 880 B**（行数不变） |
| 文件 sha256（**终值**，已实测命中） | 716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42 → **b4182633a78f9895d0ae3ced1cf0d9555ab764ac2ce4347244c15ded16f116cd** |
| 未采纳的口头提议（**不得登记、不得实施**） | `2e7ce7edf21090b54cb643f5fb882f4709cf8f30c8f26cf3a8519b68c744c173` / 15 883 B（「记为已否决」式；仅作历史记录） |
| 反引号数 | 0 → 0（不破坏 Z7 多重集对等） |
| 连续定译串 `已否决（rejected）` | 保留（出现 1 次；无 `被` 插入） |
| 引用与结构计数 | Z6/Z8 不变（该行既非 path:LINE 引用，也不影响 h2/h3/表行/围栏） |

> **验收判据**：`sha256sum doc/design/zh-CN/02-architecture.md` 必须等于 **b4182633…f116cd**（已实测命中，T4 交付即此值）；`wc -lc` = 219 / 15 880；numstat = `1 1`。T16 登记该值；**禁止**登记 `2e7ce7ed…`。

### 4.4 D 的验收命令与判定

```bash
sed -n '193,194p' doc/design/zh-CN/02-architecture.md      # 逐字核对 §4.2 两行（194 = 终值的冒号式）
git diff --numstat doc/design/zh-CN/02-architecture.md     # 1  1
sha256sum doc/design/zh-CN/02-architecture.md              # b4182633…f116cd（终值，已实测）
wc -lc doc/design/zh-CN/02-architecture.md                 # 219  15880
bash scripts/doc-verify.sh --only doc/design/zh-CN/02-architecture.md; echo "EXIT=$?"   # 0
bash scripts/doc-verify.sh; echo "EXIT=$?"                 # 0
bash scripts/i18n-audit.sh; echo "EXIT=$?"                 # 0
```

* **治理记录（T16 必须写进 [reports/62](62-i18n-delivery.md) 的新一轮章节）**：本条**取代**第二轮「02:194 保留 HEAD 原文、记为已知美容项」的处置：第三轮的 D 项即为收口，美容项在第三轮**闭合**；[reports/54](54-docs-freeze-manifest.md) §9.4 的「本轮触碰但净差异为零」记录也随之被新一轮重签取代（**净差异为非零**）。第二轮的历史记录（曾以形态 A 交付后又还原）**保留不改**。
* **同一文件的三段形态链（必须如实登记）**：① HEAD 原形态（`中已否决（rejected）的…`，sha256 `716552cd…` / 15 877 B）→ ② **终值**：冒号式（`…中的旋转烘焙实验：已否决（rejected）。`，sha256 `b4182633…` / 15 880 B，T4 已交付）→ ③ captain 交叉时点的口头提议「记为已否决」式（预期 `2e7ce7ed…` / 15 883 B，**未采纳、未落盘**）。T16/T17 只登记 ②；③ 只作为治理事实（交叉与未采纳）记录，**不是**盘面值。
* **另需如实登记**：`reports/66` 本轮被多次事后改写（链为 `f972e352…` → `0baa3c7d…` → `05777639…` → 本次冻结值），最终值由 T16 现场实测登记；该事实属治理记录，不得省略。

---

## 5. E — 独立复核清单（T17 → **reports/68-followup2-review.md**）

复核对象为 [reports/54](54-docs-freeze-manifest.md) 的新一轮增补、[reports/62](62-i18n-delivery.md) 的新一轮章节、本文件、以及 **reports/67-followup2-verification.md**。逐项给结论，**不得**以「与上游一致」代替现场实测：

* **E-1 登记值 vs 现场实测逐项一致**：对 [reports/54](54-docs-freeze-manifest.md) 新一轮登记的**每一行**现场重测 sha256、字节、行数（+权限），逐格比对；不等即 DRIFT finding。**未受影响的既有行**必须逐行核对并给出 0 DRIFT 的证据（循环打印 OK × N，任一不等打印 DRIFT）。
* **E-2 计数自洽**：GLOSSARY §6 行数 = 98 = audit Z3 期望值 = [reports/54](54-docs-freeze-manifest.md) 登记值；Y-1 文件数 = 12 = audit Z2 期望值。两处必须**独立提取**后比对，不得把同一来源引用两次充当两条证据。
* **E-3 C3/C4 结论可复现**：① --repo-mode 在**冻结落点**与**工作区外落点**各跑一次，退出码与降级计数如实登记，并与默认模式的 4 / 39（或本轮实测值）**成对**引用（只引 4 会误导）；② C4 联合指纹按**新一轮配方**独立复算（路径数由 19 → **31**，见 §7.2）并命中登记值；③ 复算前先记录 checker revision（sha256 + mtime）。
* **E-4 关键结论的可复现性**：报告中的每个数值必须给出**可直接粘贴**的命令；抽 3 条以上独立重跑（建议：A 的信号探针、D 的 sha256、六篇译文对等审计的 `--only`）。
* **E-5 治理记录完整**：至少包含——第二轮遗留项引用（[reports/64](64-followup-requirements.md) 的七版链、CAPTAIN RULING 段、t9/t10/t11 不可达、doc-architect 与 translator-b 的成员移除记录引用）、本轮的事实性事件（t1 曾以 attempt 1 派发后重派为 attempt 2；T4 收口 02:194 后美容项状态变化；若发生则记入 F 的清理与恢复）、以及**任何 criteria-vs-applied 差异**（不得美化）。
* **E-6 相对链接 0 MISS**：两份报告内的所有相对 markdown 链接与 path:LINE 引用在盘上可达；给出提取命令与计数（MISS=0）。
* **E-7 verdict 明确**：pass / needs_revision / reject 必须显式给出；needs_revision 或 reject 必须带 id / severity / problem / requiredFix（file、line 可选），且**不得**自证通过。
* **E-8 门禁一致性**：[reports/54](54-docs-freeze-manifest.md)、[reports/62](62-i18n-delivery.md)、本文件、**reports/67-followup2-verification.md** 四份对**同一工件**登记的 sha256 必须相同；任一不同即 blocker。
* **E-9 前置存在性**：T17 开始时 **reports/67-followup2-verification.md** 必须**已存在**（由 T14 产出）；缺失即 blocking finding（不得以「上游未完成」为由记 pass）。
* **E-10 复核自身可复现**：给出复核所用命令与退出码；`bash scripts/doc-verify.sh --only` 两份报告的 `EXIT=0`。

> 复核报告 **reports/68-followup2-review.md** 必须是**独立**的（作者 ≠ T16 的 doc-architect）。T17 不得复用 T16 的测量脚本输出作为唯一证据（可复跑，但必须现场自测）。

---

## 6. F — 归档与清理范围（T15）

### 6.1 归档（F-1 … F-3）

* **F-1 对象**：工作区临时根下的 `tmp/i18n-audit-probe` 目录树（第二轮 captain 裁定保留、最终处置派给本轮；[reports/62](62-i18n-delivery.md) §14.5 记载现场存在）。**默认不删原件**。
* **F-2 动作**：打包为 tar.gz 存于**工作区临时根**（/data/dsh/home/workspace/tmp/），文件名冻结为 i18n-audit-probe 加 HEAD 短哈希加 .tar.gz。命令形态（`-C` 保证归档内相对路径稳定）：

```bash
cd /data/dsh/home/workspace
tar -czf "tmp/i18n-audit-probe-$(git -C code/webrtc-demo rev-parse --short HEAD).tar.gz" \
    -C tmp i18n-audit-probe
```

* **F-3 登记**：T15 的 output 必须给出**归档文件路径 + sha256 + 字节数**、**成员文件数**、**打包用时**，以及「原件保留」的现场证据（目录存在性与占用字节）。**同一组值**由 T16 登记进 [reports/54](54-docs-freeze-manifest.md) 与 [reports/62](62-i18n-delivery.md) 的新一轮章节，两者必须逐字相同（E-8）。

### 6.2 清理（F-4 … F-8）

* **F-4 范围**：**只清理本轮（第三轮）在工作区临时根与工作区根新建的临时条目**。上一轮及更早的条目、无法确认归属的条目**一律保留**（沿用 [reports/64](64-followup-requirements.md) §7 的口径）。
* **F-5 绝对不删清单（P2 硬校验证据，删了会让门禁变红）**：仓库根的 `env.sh`、`env-go.sh`、`env-container.sh`；工作区临时根下的 `tmp/n1` … `tmp/n7`；`tmp/t47b-captain-build.sh`；`tmp/probe-forms-matrix-writer-ops.md`。此外**不得删**：`tmp/i18n-audit-probe`（原件，F-1）、归档 tar.gz（F-2 交付物）、以及任何**非**第三轮产物。
* **F-6 不得删仓库内任何文件**：含 `reports/**`、`doc/**`、`scripts/**`；**不得**执行 `git clean`、不得用 `git checkout` 覆盖任何文件、不得改 `.gitignore`。
* **F-7 方法**：沿用**可逆隔离**——先把待删条目 `mv` 到隔离目录 → 跑双门禁 → **两次都 EXIT=0** 才真删；任一变红立即 `mv` 还原并如实报告。逐条给出：路径、大小、文件数、**删除依据**（谁创建的、可否重建）、以及删除前后 `git status --porcelain` **一致**的证据。清理前后的门禁原始行与退出码必须给出。
* **F-8 范围外（必须写明，不得越界）**：HOST 侧 /tmp 下的第三轮产物（本轮各成员写的 /tmp 文本、克隆副本等）**不在 T15 的删除范围**：第二轮 captain 已裁定 HOST /tmp 的清理属其 mtime 统一清扫；T15 只能**列出**（作为事实记录），**不得** rm 仓库外的其它成员在飞产物。若 captain 要改动该口径，必须显式重派。

### 6.3 F 的验收命令与判定

```bash
ls -l /data/dsh/home/workspace/tmp/i18n-audit-probe*.tar.gz   # 归档存在，记录 sha256 与字节
cd code/webrtc-demo && bash scripts/doc-verify.sh; echo "EXIT=$?"   # 清理后仍 PASS / 0
bash scripts/i18n-audit.sh; echo "EXIT=$?"                          # 清理后仍 PASS / 0
git status --porcelain                                              # 与清理前一致
```

判定：F-1…F-8 全部满足；**任何**「先删后发现门禁变红却只记录不还原」的行为视为失败。

---

## 7. 本轮重签清单与报告编号约定（T16）

### 7.1 [reports/54](54-docs-freeze-manifest.md) 重签清单（逐行分类，不得漏登）

**A. 重签（值必然变化）**

| # | 路径 | 变化来源 |
|---|---|---|
| 1 | `scripts/doc-verify.sh` | T3（--repo-mode）+ T8（V14/V15 枚举） |
| 2 | `scripts/i18n-audit.sh` | T2（trap）+ T9（Z1/Z2/Z3 枚举与计数） |
| 3 | `doc/design/SPEC.md` | T6（v1.7.2） |
| 4 | `doc/design/zh-CN/GLOSSARY.md` | T5（v1.1.2）+ T7（v1.1.3） |
| 5 | `doc/design/zh-CN/02-architecture.md` | T4（§4 目标行，**非零差异**） |
| 6 | `doc/design/README.md` | T13（去 planned + 中文链接） |
| 7 | `README.md` | T13（同） |
| 8–13 | **doc/design/06-flows.md**、**07-build-and-deploy.md**、**08-issues-and-solutions.md**、**09-verification-and-limitations.md**、**10-code-map.md**、**11-coding-standards.md** | T10–T12（各 +1 行切换器） |
| 14 | `reports/66-followup2-requirements.md` | 本文件（报告身份：sha256/字节/行数） |
| 15 | **reports/67-followup2-verification.md** | T14（报告身份） |

**B. 新增行（本轮第一次登记）**

| # | 路径 | 来源 |
|---|---|---|
| 16–21 | **doc/design/zh-CN/06-flows.md**、**07-build-and-deploy.md**、**08-issues-and-solutions.md**、**09-verification-and-limitations.md**、**10-code-map.md**、**11-coding-standards.md** | T10–T12（六篇新中文页） |
| 22 | **.github/workflows/docs.yml** | T3（CI 工作流；与 [reports/54](54-docs-freeze-manifest.md) §9.5 登记 `scripts/i18n-audit.sh` 的先例一致，非文档工件也可登记） |
| 23 | tmp 根下的 i18n-audit-probe 归档 tar.gz（**登记为仓库外工件**，并写明其不可随仓库分发） | T15（F） |

**C. 0 DRIFT（必须逐行现场重测并给出证据）**：基线 = [reports/54](54-docs-freeze-manifest.md) §8.3、§8.4 与 §9.3–§9.6 各行，**减去** A/B 已列出的路径。预计未受影响者：`doc/design/01-requirements.md` 至 `doc/design/05-protocols.md`（英文 5 篇）、`doc/design/_generated/host-commands.md` 与另 3 张生成表、`doc/README.md`、`README.en.md`、`doc/design/README.en.md`、`doc/design/zh-CN/01-requirements.md`、`doc/design/zh-CN/03-app-architecture.md`、`doc/design/zh-CN/04-signaling-service.md`、`doc/design/zh-CN/05-protocols.md`、`doc/design/zh-CN/SPEC-guide.md`。

> **分类完整性判据**：重签数 + 新增数 + 0 DRIFT 数 必须等于基线登记行数（再加新一轮两份报告身份）；**任何既未重签也未列入 0 DRIFT 的行即漏登**，T16 不得收口。

**D. 文内其他必须同步的值**：checker revision 三件套（sha256 + mtime + 原始退出码）；门禁与审计的**新** checks/warnings 计数；GLOSSARY 版本链；C4 联合指纹（§7.2）；本轮报告身份表。

### 7.2 C4 联合指纹配方（路径数 19 → 31）

在 [reports/54](54-docs-freeze-manifest.md) §9.7 的 19 路径配方上**追加 12 条**：doc/design 下 06–11 的六篇英文页与 doc/design/zh-CN 下 06–11 的六篇中文页。命令形态不变（两端 LC_ALL=C sort）。

```bash
cd /data/dsh/home/workspace/code/webrtc-demo
cat <<'EOF' | LC_ALL=C sort | xargs sha256sum | LC_ALL=C sort | sha256sum
scripts/doc-verify.sh
scripts/i18n-audit.sh
README.md
README.en.md
doc/design/README.md
doc/design/README.en.md
doc/design/SPEC.md
doc/design/01-requirements.md
doc/design/02-architecture.md
doc/design/03-app-architecture.md
doc/design/04-signaling-service.md
doc/design/05-protocols.md
doc/design/06-flows.md
doc/design/07-build-and-deploy.md
doc/design/08-issues-and-solutions.md
doc/design/09-verification-and-limitations.md
doc/design/10-code-map.md
doc/design/11-coding-standards.md
doc/design/zh-CN/GLOSSARY.md
doc/design/zh-CN/SPEC-guide.md
doc/design/zh-CN/01-requirements.md
doc/design/zh-CN/02-architecture.md
doc/design/zh-CN/03-app-architecture.md
doc/design/zh-CN/04-signaling-service.md
doc/design/zh-CN/05-protocols.md
doc/design/zh-CN/06-flows.md
doc/design/zh-CN/07-build-and-deploy.md
doc/design/zh-CN/08-issues-and-solutions.md
doc/design/zh-CN/09-verification-and-limitations.md
doc/design/zh-CN/10-code-map.md
doc/design/zh-CN/11-coding-standards.md
EOF
```

* **31 路径终值由 T16 现场实测登记**（本文件不预填）；旧值 40d9fa70db57add32376157e181b1fe57c8fa250fe7a06253905d5a794adfa55（19 路径，[reports/54](54-docs-freeze-manifest.md) §9.7）随之**作废**，仅作历史保留。

### 7.3 [reports/62](62-i18n-delivery.md) 新一轮章节（必须包含）

1. **A 的交付与验证结论**（trap 覆盖面、三信号探针的 rc 与 residue、SIGKILL 边界的书面声明、pristine 对照结果）。
2. **C 的交付与验证结论**（四类降级判据与计数汇总行、P-M0…P-M7 的探针结果、默认模式逐字节不变的对照结果、CI 工作流的存在与红/绿口径、**CI 绿 ≠ 工作区门禁绿** 的告示）。
3. **B 的交付与验证结论**（01–11 四处枚举一致性的脚本化比对证据、GLOSSARY v1.1.1→v1.1.2→v1.1.3、§6 由 74 增至 98、Y-1 由 6 增至 12、六篇译文的门禁与审计结果、索引与入口去 planned 的 grep 证据）。
4. **D 的结论与治理状态变化**（02:194 美容项在本轮**闭合**，取代第二轮「保留原文」的处置）。
5. **F 的清理记录**（归档路径/sha256/字节/成员数；删除项逐条依据与可重建性；保留项理由；清理前后双门禁原始行）。
6. **治理记录（按事实，不美化）**：第二轮遗留项引用（[reports/64](64-followup-requirements.md) 的七版链与 CAPTAIN RULING 段、t9/t10/t11 不可达、成员移除记录）、本轮 t1 的 attempt 1 → attempt 2 重派事实、任何 criteria-vs-applied 差异。
7. **不确定性清单**：本轮新增的 unverified 项必须显式列出（例如「CI 工作流未在真实 GitHub runner 上执行过」——本容器无法验证远端行为，须如实登记，不得写成「CI 已通过」）。

### 7.4 T16 的验收命令与判定

```bash
bash scripts/doc-verify.sh; echo "EXIT=$?"                          # 0
bash scripts/i18n-audit.sh; echo "EXIT=$?"                          # 0
bash scripts/doc-verify.sh --only reports/54-docs-freeze-manifest.md reports/62-i18n-delivery.md; echo "EXIT=$?"   # 0
```

判定：§7.1 的 A/B/C 三类分类完整、§7.3 的七项齐全、两报告对新工件登记**同一** sha256（E-8），且 [reports/54](54-docs-freeze-manifest.md) 明确写出「以现场实测值为登记值」。

---

## 8. 待 captain 裁定的项（若与任务契约冲突，以 captain 裁定为准）

> **裁定 / 批准登记（2026-09-21，captain 直接下达，均已回填本文件）**：
> * **D 终局裁定**：终值 = **盘面冒号式**（sha256 `b4182633…f116cd` / 15 880 B / 219 行 / numstat `1 1`）；「记为已否决」式系交叉时点的口头提议，**未采纳**（§0 第 8 条、§4.2/§4.3）。**禁止再对 `doc/design/zh-CN/02-architecture.md` 写入**（T4 终态）。
> * **裁定 2（C 四类降级清单）**：P2 / P4 / SUBMODULE / ENVSLASH **不增不减**；第五类须报 captain 裁定；`docs -> doc/design` 是仓库内符号链接，**不列入降级类**（§2.2 C-2/C-2b）。
> * **T7 外延 = 批准**：§3.4.4 的 **14 处**断言/清单站点全部纳入 T7，版本 **v1.1.2 → v1.1.3**；T7 输出必须逐处列出改前改后。
> * **T9 外延 = 批准**：§3.4.3 的 **15 个站点**（含 `BODY_PAIRS` 2..6 → 2..12、`Y1` 6 → 12、Z2 全仓唯一性 expected 6 → 12、Z3 74 → 98 且含消息文本 `1..74`）。
> * **T16 口径 = 确认**：`reports/54` 增补新节（不就地改写）；重签 + 新增 + 0 DRIFT；**C4 联合指纹 19 → 31 路径**（旧值 `40d9fa70…` 作废）；`reports/62` 记录「02:194 美容项在第三轮闭合」。
> * **流程留痕（T16/T17 必须如实登记）**：t1 两次派发（attempt 1 → 2）；`reports/66` 本轮**多次事后改写**（`f972e352…` → `0baa3c7d…` → `05777639…` → 本次冻结值）；D 的口径交叉与「记为已否决」提议未采纳；`doc/design/zh-CN/02-architecture.md` 的写权限已关闭。
>
> **改完本节即冻结本文件**：`reports/66` 此后不得再编辑；其最终身份（sha256/字节/行数）由 T16 现场实测登记。

下列 1–2 为**仍未裁定**的项：

1. **CI 是否设为 PR 必需检查（branch protection）**：本文件只冻结 workflow 的行为与红/绿语义；「设为必需检查」属仓库设置，本容器无法验证，须由 captain 在交付总结中显式列为 unverified 或安排后续。
2. **P4 的取证方式**：本容器 `unshare` 不可用（实测），故 §2.3 允许「声明式单行探针副本」。若 captain 不接受该方法，需要提供可在 CI 侧执行 P4 探针的替代环境；否则该条应在 T14 报告中列为 unverified 而不是 pass。

---

## 9. 复现命令（本文件 §0.1、§3.1 与 §4.3 的全部数值）

```bash
cd /data/dsh/home/workspace/code/webrtc-demo
git rev-parse HEAD; git status --porcelain
sha256sum scripts/doc-verify.sh scripts/i18n-audit.sh doc/design/SPEC.md \
          doc/design/zh-CN/GLOSSARY.md doc/design/zh-CN/02-architecture.md \
          doc/design/README.md README.md
wc -lc scripts/doc-verify.sh scripts/i18n-audit.sh doc/design/SPEC.md \
       doc/design/zh-CN/GLOSSARY.md doc/design/zh-CN/02-architecture.md
bash scripts/doc-verify.sh; echo "EXIT=$?"                 # PASS (3912 checks, 2 warnings) / 0
bash scripts/i18n-audit.sh; echo "EXIT=$?"                 # PASS (79 checks, 0 warnings) / 0
# 工作区外裸克隆（无 submodule）：
rm -rf /tmp/t1-bare-clone && git clone --depth 1 --no-recurse-submodules \
  /data/dsh/home/workspace/code/webrtc-demo /tmp/t1-bare-clone
( cd /tmp/t1-bare-clone && bash scripts/doc-verify.sh; echo "EXIT=$?" )   # FAIL (39 failures, 5 warnings, 3912 checks) / 1
# Z8 基线（h2/h3/表行/围栏）：
for f in 06-flows 07-build-and-deploy 08-issues-and-solutions \
         09-verification-and-limitations 10-code-map 11-coding-standards; do
  printf '%-34s ' "$f"
  awk 'FNR==1{infence=0;h2=0;h3=0;tbl=0;blocks=0}
       { if ($0 ~ /^[[:space:]]*(```|~~~)/) { if (!infence) blocks++; infence=!infence; next }
         if (infence) next
         if ($0 ~ /^## /) h2++; if ($0 ~ /^### /) h3++
         if ($0 ~ /^\|/ && $0 !~ /^\|[-: |]+\|[[:space:]]*$/) tbl++ }
       END { printf "h2=%d h3=%d tbl=%d blocks=%d\n", h2, h3, tbl, blocks }' "doc/design/$f.md"
done
# D 项的预测 sha256（等价复算）：
node -e 'const fs=require("fs");const p="doc/design/zh-CN/02-architecture.md";
const t=fs.readFileSync(p,"utf8").split("\n");
t[193]="  doc/design/08-issues-and-solutions.md 中的旋转烘焙实验：已否决（rejected）。";
fs.writeFileSync("/tmp/t1-02-preview.md",t.join("\n"));'
sha256sum /tmp/t1-02-preview.md      # 期望 b4182633a78f9895d0ae3ced1cf0d9555ab764ac2ce4347244c15ded16f116cd（captain 终局裁定的终值）
```

---

## 附：本文件的自证

本文件在撰写时通过自身子集门禁：`bash scripts/doc-verify.sh --only reports/66-followup2-requirements.md`，期望 `EXIT=0`（命令与退出码见 T1 的 output）。本文件自身的 sha256 / 字节 / 行数由 T16 在 [reports/54](54-docs-freeze-manifest.md) 的新一轮中登记（§7.1 的 A-14）。

书写约定（见文首）说明：本文件对**尚未落地**的工件（六篇中文新页、CI 工作流文件、**reports/67-followup2-verification.md**、**reports/68-followup2-review.md**）一律以**粗体纯文本**给出——不写反引号、不写相对链接——以免在它们落地前使 V1/V4 变红。这些目标落地后，T16 或 T17 可以把它们改为反引号与链接并复核可达性（E-6）。
