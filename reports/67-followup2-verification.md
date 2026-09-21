# 67 — 第三轮独立验证（T14）

> Status: **final** · Owner: `verifier` · Task: t14 (attempt 1), 2026-09-21
> 上游判据：[reports/66](66-followup2-requirements.md)（冻结口径 `a9dc8d1d…`）、[reports/54](54-docs-freeze-manifest.md)、[reports/62](62-i18n-delivery.md)、captain 当日裁定（见 §7）
> 下游：T15（F 归档与清理）、T16（[reports/54](54-docs-freeze-manifest.md)/[reports/62](62-i18n-delivery.md) 重签）、T17（独立复核 → reports/68-review）
> 判据优先级：captain 当日裁定 / 任务契约 > [reports/66](66-followup2-requirements.md) > 本文件 > 任何成员自述
> 书写约定（沿用 [reports/66](66-followup2-requirements.md)）：**已存在的仓库内路径写反引号**；**HOST /tmp 路径、以及一切不存在的路径/行号写纯文本**（避免 V1/V4/V9 把示例当成事实断言）。
> 本文件自身的 sha256 / 字节 / 行数由 T16 在 [reports/54](54-docs-freeze-manifest.md) 新一轮中现场实测登记（撰写完成时刻的实测值见 T14 的 task output）。

---

## 0. 范围、方法与证据

**判定对象**：本轮 A（审计临时目录清理）、B（06–11 六篇译文 + GLOSSARY/SPEC/索引）、C（`doc-verify.sh --repo-mode` + CI）、D（`zh-CN/02:194` 句读）与 E/F 的前置工件。**判据分列**：

* **(i) t9 完成判据**（脚本自身正确性）：`scripts/i18n-audit.sh` 在所有**已武装**对上判定正确、负例不放过、活树 EXIT=0。与 09/11 是否落盘无关（captain 裁定，见 §7.5）。
* **(ii) b1 交付态断言**（属本任务，只在**最终静默树**上判）：`armed pairs = 13/13`、`armed body pairs = 11/11`、`skipped = 0`、`Z2 expected = 12`。

**方法**：全部结论来自**我自己的现场实测**；成员提供的证据只作为**交叉核对**并标注来源。每个数值都给出可直接粘贴的命令与**当次原始输出**；原始输出留存于 /tmp/vfy-base/evidence/（HOST /tmp，**禁删**，captain 裁定 /tmp/vfy-base* 列入排除模式），其 `MANIFEST.txt` sha256 = `38ab9c83d0449a4b04efa798a5a4a5ea751cb8f96afb42186888aca693f1f207`（76 条目）。**落点纪律**：所有计数类断言**连同 WORKSPACE_ROOT 一起写**（附录 19）。

### 0.1 多段并列基准（不写单一数字）

| 工件 | 起点（HEAD `8bb69d2`） | 中间态 | 终态 |
|---|---|---|---|
| `scripts/doc-verify.sh` | `6c62591a…` / 919 行 / 41 673 B | `fa2ce899…`(t3) / 1029 行 / 46 584 B | **`9c154dbe…`(t8) / 1029 行 / 46 617 B / 0444** |
| `scripts/i18n-audit.sh` | `f93da7d5…` / 810 行 / 32 425 B | `316bb2db…`(T2) / 829 行 / 33 631 B；`944d2aff…`(T9) / 989 行 / 41 635 B | **`c778c831…`(t18) / 1089 行 / 45 596 B / 755**（md5 `ab41d994…`） |
| `.github/workflows/docs.yml` | —（不存在） | `34a5bc0d…`(t3) / 45 行 / 1395 B / 644 | **`a2d9818c…`(t18) / 45 行 / 1397 B / 644** |
| `doc/design/SPEC.md` | `b3368522…` / 758 行 / 67 976 B | — | **`78787978…` / 759 行 / 68 586 B / v1.7.2 / numstat `4 3`** |
| `doc/design/zh-CN/GLOSSARY.md` | `51e3eb34…` / 349 行 / 27 419 B | `d6dee165…`(T5) / 373 行 / 29 557 B | **`7b38bed5…`(T7) / 373 行 / 30 290 B / v1.1.3 / numstat `38 14`** |
| `reports/66-followup2-requirements.md` | —（本轮新建） | `f972e352…` → `0baa3c7d…` → `05777639…` | **`a9dc8d1d…` / 705 行 / 70 589 B（冻结，此后不得编辑）** |

### 0.2 四落点（同一 checker、不同 WORKSPACE_ROOT；详见附录 19）

| 落点（WORKSPACE_ROOT） | 文档集 | 默认模式失败 | `--repo-mode` |
|---|---|---|---|
| `/`（t3-bare-ws 克隆） | HEAD | **39**（P2=29 / SM=4 / ES=6） | —（该克隆早于 T9/t18） |
| `/tmp`（HEAD 文档集克隆） | HEAD | **46**（P2=36 / SM=4 / ES=6） | — |
| `/tmp`（交付树文档） | 交付树 | **59**（P2=39 / SM=8 / ES=12） | EXIT=0、remaining=0、checks 4795 |
| runner 类比（交付树文档） | 交付树 | **59**（P2=39 / SM=8 / ES=12） | EXIT=0、remaining=0、checks 4795 |

落点差异的**全部**成因 = 7 条散文 token `` `tmp/` ``（`doc/design/SPEC.md` 六处 + `doc/design/zh-CN/SPEC-guide.md` 一处，payload 级 `comm` 比对「仅一处有」= 上述 7 条、「仅另一处有」= 0）；在 `WORKSPACE_ROOT=/` 下该 token 解析为 /tmp/（**存在**）故不失败。

---

## 1. (i) T9 完成判据 — 通过

### 1.1 工件身份与语法

| 项 | 实测 |
|---|---|
| `scripts/i18n-audit.sh` | sha256 `c778c83127489f3f8b36b72b2ab0e6b1afe381a66e1325b659aea3c29e4c0828` / 1089 行 / 45 596 B / mode 755 / md5 `ab41d994219e50bb2dd2963b96ca8fb1` |
| `bash -n scripts/i18n-audit.sh` | rc=0 |
| -h | 提及 --repo-mode（2 处）；未知参数 rc=**2** |

### 1.2 默认模式逐字节不变（精确配对，非归因法）

四段同树（pinned 版仅改 REPO_ROOT 一行，`diff` 各 2 行）：

| 段 | EXIT | 三元组 |
|---|---|---|
| `f93da7d5…`（HEAD pristine） | 1 | FAIL (8 failures, 0 warnings, 91 checks) |
| `316bb2db…`（T2） | 1 | FAIL (8 failures, 0 warnings, 91 checks) |
| `944d2aff…`（T9，/tmp/t18-frozen.sh 逐字副本） | 0 | PASS (133 checks, 0 warnings) |
| `c778c831…`（t18 终态） | 0 | PASS (133 checks, 0 warnings) |

* **配对①** `f93da7d5…` ↔ `316bb2db…`：**stdout 逐字节相同**（`cmp` 无输出）⇒ **T2 对默认模式零输出影响**（A 项的 C-5 式精确证据，无需归因法）。
* **配对②** `316bb2db…` ↔ `944d2aff…`：**+40 / −9**，新增行**全部**是六个新对的 Z6/Z7/Z8 NOTE（例：`doc/design/zh-CN/06-flows.md:1` → Z6 = 53 (119)、Z7 = 169、Z8 = `10 0 13 23`）⇒ 与 T9 授权的枚举/存在性武装完全对应。
* **配对③** `944d2aff…` ↔ `c778c831…`：**stdout 逐字节相同**（全量 PASS 133/0 与 `--only doc/design/zh-CN/GLOSSARY.md` PASS 2/0 两组均 `cmp` 无输出、rc 相同）⇒ **t18 对默认模式零输出影响**。
* 旧修订在当前树上 FAIL 属**时点效应**：其 Z3 期望仍为 74、Z2 仍为 6，而盘面 GLOSSARY 已 98 行、Y-1 已 12 页；T9 之所以 PASS 是因为同步了这两处期望。**不是**旧修订缺陷。

### 1.3 b2（Z6/P2 归一）的独立复核 — PASS

* 改前（`23f9ff44…`，我现场读取）：Z6 只有 `fs="$REPO_ROOT/$target"`，无条件按仓库根判存在性与行界。
* 改后（`944d2aff…`）：`is_p2_payload()`（`../*` / `tmp/*` / `*env.sh` / `*env-container.sh` / `*env-go.sh`）+ `p2_target_path()`（剥净前导 `../` → `$WORKSPACE_ROOT`），**存在性与行界都用解析后的根**；`[ ! -e "$fs" ]` 之后仍 `fail Z6 …; continue`，**无任何分支把「找不到/越界」变成通过**；zh/en 走同一段代码（对称）。
* 12 条红→绿为真转绿：改后 0 条 FAIL，且英文与中文 07 的 `../env.sh:37/42/45`、`../env-container.sh:31/38` **仍在盘**，英文 07 `numstat` = `2 0`（仅切换器两行）。
* 负例（仓库外孪生树独立复跑，原始输出 /tmp/vfy-neg-probe.txt；探测串按约定写纯文本，不写反引号）：../definitely-missing.sh:1 → FAIL[Z6] 不存在；tmp/nope/missing.log:1 → FAIL[Z6] 不存在；../env.sh:999 → FAIL[Z6] out of bounds（env.sh 实测 81 行）；我另加 env-go.sh:999 → out of bounds（73 行）。共 8 条 = 4 探针 × 2 侧，**全部 Z6、无其他 Z 副作用**（镜像等式未破）。
* 正对照（同一探测行内）：../env.sh:81、tmp/n6/x/app.log:6170、env-go.sh:73 **无任何失败** ⇒ 排除「位置误伤」的替代解释。

### 1.4 信号性质与 A-7/A-8（captain 裁定，见 §7.3）

* T2 性质在最终修订上复测（`set -m`，受控 TMPDIR）：TERM rc=143 / INT rc=130 / HUP rc=129，**residue 全 0**；SIGKILL rc=137、residue=1、**13 个条目（非空目录）**。
* 普通 `&` 启动时子进程 `SigIgn=0x0000000000000006`（INT+QUIT）⇒ `kill -INT` 不投递、INT 行退化为整跑；`set -m` 下才投递。
* 未捕获 TERM/INT/HUP 下 **bash 会先跑 EXIT trap** 再以 128+signum 退出（最小例 rc=143 + 已打印 EXITTRAP-RAN + 目录已删）⇒ [reports/66](66-followup2-requirements.md) §1.1 的「EXIT trap 不执行」在 bash 上不成立、§1.3 A-7「pristine 必残留」**不可复现**（18 次有效探针 residue 全 0）；`timeout -s TERM|INT 1` 亦 residue=0。修复的正当性是**shell 无关性**：`/bin/sh`（本容器 = dash）对未捕获 TERM **不**执行 EXIT trap（实测 rc=143、trap_ran=0、目录残留）。

### 1.5 活树终值与不变量

```
bash scripts/i18n-audit.sh   → PASS (133 checks, 0 warnings)   EXIT=0
  NOTE Z1: armed pairs = 13, skipped = 0 of 13 registered pairs
  NOTE Z2: disclaimer occurrences = 12, expected = 12 (= |Y-1 ∩ disk| of 12 registered Y-1 pages)
  NOTE Z6/Z7/Z8: armed body pairs = 11, skipped = 0 of 11 registered body pairs
  NOTE doc/design/zh-CN/GLOSSARY.md:1 → Z3: §6 data rows = 98, each with 4 non-empty cells and # = 1..98
```

### 1.6 T9 十五站点（按 `reports/66` §3.4.3，逐条按内容核对）

Z2 头注释（存在性武装 + `|Y-1 ∩ 盘|`、12 页齐备时 = 12）、Z3 头注释（98 行 / `# = 1..98`）、Z6/Z7/Z8 头注释（`11 body pairs`）、`--only` 表（`zh body page 01–11`）、注释（`13 frozen pairs`；`Indexes 0..1 … 2..12 are the eleven body pairs`；armed pair 对手缺席 = FAIL[Z1]）、PAIR_ZH = 13、PAIR_EN = 13、`BODY_PAIRS=(2 3 4 5 6 7 8 9 10 11 12)`、Y1 = 12、Z2 per-file「expected exactly 1」+ 聚合「expected = |Y-1 ∩ disk|」、Z3 `!= 98` 与消息「expected 98」/「(1..98 in order)」、Z5 注释「one of the 12 translated pages」。**逐条命中**；（Z2 的字面「expected 12」按 captain 裁定 b1 由存在性武装取代，见 §7.4/§8.1。）

---

## 2. (ii) b1 交付态断言 — 在最终静默树通过

### 2.1 零跳过与计数

`armed pairs = 13` / `skipped = 0 of 13`；`armed body pairs = 11` / `skipped = 0 of 11`；`Z2 occurrences = 12, expected = 12`；`Z3 = 98`（§1.5 原始输出）。

### 2.2 六页存在与英文首行（B-7）

| 中文页（sha256 / 字节 / 行） | 英文页 `numstat` | 英文第 1 行（B-7）sha256 前 16 位 |
|---|---|---|
| `doc/design/zh-CN/06-flows.md` `3b604521…` / 28 184 / 423 | `2 0` | `e7e7719a5f832ab2` |
| `doc/design/zh-CN/07-build-and-deploy.md` `c5a863df…` / 10 707 / 199 | `2 0` | `3ab9760e7b540a87` |
| `doc/design/zh-CN/08-issues-and-solutions.md` `9e895fa7…` / 50 211 / 496 | `2 0` | `e615f70179d44ab4` |
| `doc/design/zh-CN/09-verification-and-limitations.md` `62cfa5df…` / 21 544 / 210 | `2 0` | `fe5f660ba702efe5` |
| `doc/design/zh-CN/10-code-map.md` `bdf8d97a…` / 18 987 / 242 | `2 0` | `e0357869eaaa55c6` |
| `doc/design/zh-CN/11-coding-standards.md` `d0731f3a…` / 17 074 / 218 | `2 0` | `d8e29f6acfcbe4a0` |

六条英文首行**逐字命中** B-7 冻结形态（半角括号）：`> [中文（默认）]（zh-CN/NN-<name>.md） · English`（模板用全角括号以避免自证时的链接检查；实际文件为半角）。

### 2.3 计数与告警

* `bash scripts/doc-verify.sh` → **PASS (4795 checks, 4 warnings) EXIT=0、FAIL=0**；checks **4795 > 3912**（起点基线），差值 +883 来自六篇新英文页/中文页入扫描集与 V14/V15 义务。
* 4 条 WARN **全部**是 09 篇既有否例的 advisory（`doc/design/09-verification-and-limitations.md:140`、`:147`、`doc/design/zh-CN/09-verification-and-limitations.md:134`、`:141`，app/build/nope.apk typo-suspect，永不判 failures）。**T8 前那 6 条 `not in the frozen V14 pair list` 已随 T8 全部消失（计数 0）** ⇒ `warnings = 4`（**不是 0**）才是 T8 后的正确期望。

### 2.4 双跑指纹一致（契约项）

* 指纹口径：`git ls-files` 中 `scripts/**`、`doc/**`、`.github/**` 与根 `README*`，外加六个未跟踪的中文新页 —— 共 **102 个文件**，逐件记录 `sha256` 与 `mtime`。
* 连续跑 `bash scripts/doc-verify.sh` 与 `bash scripts/i18n-audit.sh` 各一次后复测：**102 件 sha256 + mtime 全部不变**（`cmp` 两份快照无输出）⇒ 两个门禁**只读、零写入**，不存在「跑门禁改动工件」的隐式副作用。
* 同时确认本任务只新增了一个文件：`reports/67-followup2-verification.md`；未跑 Gradle、未 commit。

---

## 3. A 项（审计临时目录清理）— 通过

* **判据**（captain 裁定 A-7 作废）：A-1…A-6 + A-8 + A-9 + **shell 无关性**。
* **A-1/A-2/A-3**：交付块为 `cleanup() { [ -n "${AUDIT_TMP:-}" ] && rm -rf -- "$AUDIT_TMP"; AUDIT_TMP=""; }` + `trap cleanup EXIT` + `trap 'cleanup; exit 130' INT` / `'…143' TERM` / `'…129' HUP`（幂等、空值守卫、尾部赋值保证状态 0）。
* **A-6**（`set -m`）：143 / 130 / 129，residue=0（多修订多轮复跑一致）。
* **A-8**：SIGKILL rc=137 / residue=1 / **13 条目非空** —— [reports/66](66-followup2-requirements.md) §1.3 的「空目录」措辞记为**勘误**（§8.2）。
* **A-9**（输出中性，隔离证明）：以 HEAD 版 `f93da7d5…` 为输入，**仅**把单行 `trap 'rm -rf "$AUDIT_TMP"' EXIT` 替换为交付版 5 行清理块，得合成件 `55410f7200c7487c64aabe88e4d858e8817d5b5532ca661623c960a25f031b9f`（`diff -u` = **1 删 5 增**、`bash -n` 通过）；该合成件与 pristine 在同一树上：绿树 PASS (79 checks, 0 warnings) rc=0、仓库外红树 FAIL[Z2]×2 rc=1、`--only`（3 路径与单页）—— stdout **均逐字节相同**、rc 相等。
* **A-4 边界声明**：脚本头注释如实声明 SIGKILL 不可捕获、残留仅存于写入首条记录前的短窗口。
* 另证（配对①，§1.2）：`f93da7d5…` ↔ `316bb2db…` 默认模式 **stdout 逐字节相同** ⇒ T2 的清理改动对默认模式零输出影响。

---

## 4. C 项（`--repo-mode` + CI）— 通过

### 4.1 workflow 对 C-8 十条（最小结构校验器 + 条款逐条核对）

`node /tmp/t3-yaml-check.js .github/workflows/docs.yml` → **STRUCTURE OK (32 clauses passed, 0 failed)**。逐条：路径 `.github/workflows/docs.yml` ✓；触发 push(`branches: [main]`)+`pull_request`+`workflow_dispatch` ✓；`permissions: contents: read` ✓；`ubuntu-latest` + `timeout-minutes: 15` ✓；`actions/checkout@v4` 且显式 `submodules: false` ✓；两个**独立命名**步骤且顺序正确（先审计、后门禁，均 `set -euo pipefail`）✓；无 `continue-on-error`、`|| true`、`set +e`、`--only` ✓；无 apt-get/gradle/secrets ✓；第 9 条注释写明「CI 绿 ≠ 工作区门禁绿，工作区门禁由作者与独立验证执行」✓；无 tab / 锚点 / 别名，`run: |` 为朴素块标量 ✓。
**声明（必须如实）**：本容器**没有**完整 YAML 解析器（无 python3、无 ruby、node 无 yaml 模块），故校验方法 = **最小结构校验器 + 条款逐条核对**，**不得**写成「YAML 已由解析器验证」。

### 4.2 `--repo-mode` 语义

* -h 列出该开关及其语义（C-1）；未知参数 rc=**2**（门禁与审计均实测）；可与 `--only` 组合、位置无关。
* **降级恰四类**：P2 / P4 / SUBMODULE / ENVSLASH，逐条带类别代号的 NOTE + 机器可读汇总；**非该类失败一律不降级**（§4.7）。
* **默认模式逐字节不变**：见 §1.2 配对③（审计）与 §4.4（门禁三段）。

### 4.3 C-4 算术（同一落点成对引用）

* **审计活树成对**（`c778c831…`，同一落点 = 活工作区）：默认 `PASS (133 checks, 0 warnings) EXIT=0`；`--repo-mode` `PASS (133 checks, 0 warnings) EXIT=0`，summary `downgraded: P2=0 P4=0 SUBMODULE=0 ENVSLASH=0; default-mode failures=0; remaining failures=0` ⇒ **checks 相等（133 = 133）**、算术 `0 = 0+0` ✓。活树内四类本就可解析、无需降级，故此处只证明**语义与算术成立**，不构成掩蔽证据（掩蔽证据见 §4.6/§4.7 的外落点克隆）。
* **门禁活树成对**：默认 `PASS 4795` 与 `--repo-mode` `PASS 4795`，四类计数全 0 —— 同上，**平凡成立**，不构成掩蔽证据。
* **交付树 + `/tmp` 落点（真实证据）**：默认 `FAIL (59 failures, 7 warnings, 4795 checks)`；`--repo-mode` `EXIT=0`、`PASS (4795 checks, 7 warnings)`，summary `downgraded: P2=39 P4=0 SUBMODULE=8 ENVSLASH=12; default-mode failures=59; remaining failures=0` ⇒ **checks 相等（4795 = 4795）**、warnings 相等（7 = 7）、算术 `39+0+8+12 = 59` ✓、`remaining 0` ✓。
* **runner 类比落点**：同值（59 / P2=39 / remaining 0）。

### 4.4 C-5 门禁三段同树对照（checker）

| 段 | EXIT | 三元组 |
|---|---|---|
| `6c62591a…`（HEAD） | 0 | PASS (4777 checks, 10 warnings) |
| `fa2ce899…`（T3-only） | 0 | PASS (4777 checks, 10 warnings) |
| `9c154dbe…`（T8 终态） | 0 | PASS (4795 checks, 4 warnings) |

* **HEAD ↔ T3-only：三种运行（全量、`--only doc/design/SPEC.md`、`--only doc/design/zh-CN`）stdout 均逐字节相同**、rc 相同、三元组相同（全量 `4777/10`；`--only` SPEC `269/6`；`--only` zh-CN `2217/8`）⇒ C-5② 的**精确证据**。
* T3-only ↔ T8：+1 / −7（6 条 V14 配对 WARN 消失 + summary 行变化）⇒ 差异**恰好**是 T8 授权的枚举扩展。

### 4.5 --help 属 C-1 强制差异

旧（`sed -n '2,73p'`）72 行 / rc=0 → 新（awk 到头部结束行）87 行 / rc=0；`diff` = **新增 16 / 删除 1**，**唯一删除行 = 被改写的 Usage 行**，它在新增侧以 `… [--repo-mode]` 形式存在；除该行外旧帮助**每一行**都逐字存在（`grep -Fx` 逐行核对，漏失计数 = 1 即该行）；新增 15 行 = --repo-mode 说明块 + 1 行 `#`。判据/失败消息未受波及（`fail` 行 27→27、三条默认失败消息逐字节 identical）。

### 4.6 t18 忠实克隆端到端（**CI 可绿**的证明）

克隆含 .git、submodule 目录空、WORKSPACE_ROOT 无 `env.sh`：

| 步骤 | 默认模式 | `--repo-mode` |
|---|---|---|
| `i18n-audit.sh` | EXIT=1 / **22 × FAIL[Z6]** | **EXIT=0** / PASS (133 checks, 0 warnings)；summary `P2=2 P4=0 SUBMODULE=8 ENVSLASH=12; default-mode failures=22; remaining failures=0`（**2+8+12 = 22** ✓）；类别 NOTE = **12 ENVSLASH / 2 P2 / 8 SUBMODULE**；**checks 133 = 133** ✓ |
| `doc-verify.sh` | EXIT=1 / 59 failures | **EXIT=0** / PASS (4795 checks, 7 warnings)；summary `P2=39 … default 59; remaining 0`（59→0） |

### 4.7 六类不可降级负例 + 真缺失 P2（证明未放水）

`--repo-mode` 下四类降级恒为 `P2=2 / P4=0 / SM=8 / ES=12`，其余失败**一律保留**（`default = 降级之和 + remaining`）：

| 探针（仓库外副本） | rc | FAIL 数 | 算术 |
|---|---|---|---|
| N1 仓库内路径缺失（in-repo 幽灵路径） | 1 | 3 | 25 = 22+3 ✓ |
| N2 `path:LINE` 越界（room.go 999999，目标 306 行） | 1 | 3 | 25 = 22+3 ✓ |
| N3 缺切换器（Z1） | 1 | 1 | 23 = 22+1 ✓ |
| N4 缺声明（Z2） | 1 | 3 | 25 = 22+3 ✓ |
| N5 Z7 code-span 多重集不等 | 1 | 1 | 23 = 22+1 ✓ |
| N6 Z8 结构计数不等（h2 10 ↔ 11） | 1 | 2 | 24 = 22+2 ✓ |
| 复原后 | 0 | 0 | 22 = 22+0 ✓ |

* **克隆落点注入真缺失 P2**（中英各一）：默认 **FAIL 24**（22 + 2，注入项确有 FAIL[Z6]）；`--repo-mode` EXIT=0、**P2=4**、`24 = 4+8+12`、`remaining 0` ✓。
* **工作区落点**（孪生树置于 `<ws>/code/…`，WORKSPACE_ROOT = 真实工作区）：注入真缺失 P2 → **默认模式仍 FAIL[Z6]**（实测 4 failures = **2 条 harness 伪影** + 2 条注入命中）。**伪影如实披露**：该孪生树未拷 `.gitignore`，触发 `doc/design/02-architecture.md:208` 对 .gitignore:56 的引用缺失；**与注入无关，实质结论不变**（见 §8.6）。
* **类别集判断（我同意 doc-tooling）**：审计降级集恰为 {P2, P4, SUBMODULE, ENVSLASH}（与门禁同名同谓词）；基线 2 条 P2 就是 `doc/design/08-issues-and-solutions.md:493`（EN）与 `doc/design/zh-CN/08-issues-and-solutions.md:490`（ZH）的 `../tmp/n6/x/app.log:6170`，而**门禁因 `ext_checked()` 白名单不含 `log` 从不报告**二者 ⇒ **没有放宽门禁类别、没有任何"工作区内会失败却被免责"的项**（与附录 18 一致）。

### 4.8 门禁/审计检查面差异（附录 18）

`scripts/doc-verify.sh:429`–`scripts/doc-verify.sh:434` 的 `ext_checked()` 白名单 = `go|kt|kts|cpp|h|cc|hpp|sh|py|service|conf|mjs|md|json|properties|txt|xml|gradle`，**无 `log`**；CIT 处理处 `ext_checked "$path" || continue` ⇒ `../tmp/n6/x/app.log:6170` 在两套模式与「普通段落」形态下**均 0 记录**（另测 6 个变体亦全不产出）；审计无白名单故会检查该引用。该白名单与 HEAD **逐字节相同** ⇒ **既存检查面差异，本轮未引入、不动**。

---

## 5. D 项（`doc/design/zh-CN/02-architecture.md:194` 句读）— 通过

* 冻结行在 [reports/66](66-followup2-requirements.md) **第 450 行**；`cmp <(sed -n '450p' reports/66-followup2-requirements.md) <(sed -n '194p' doc/design/zh-CN/02-architecture.md)` → **无输出、exit 0**。
* 行内容（去换行）sha256 = `8a5fee5bad48193c0466a9be8db2818dc250a50c40f3a21e330097e3aa402a4d`；文件 sha256 = `b4182633a78f9895d0ae3ced1cf0d9555ab764ac2ce4347244c15ded16f116cd` / **219 行 / 15 880 B** / `git diff -U0` 的 hunk 数 = **1** / `numstat` = `1 1`；第 193 行与 HEAD **相同**。
* 形态链：① HEAD `716552cd…` → ② **终值（冒号式）** `b4182633…` → ③ 「记为已否决」式 `2e7ce7ed…`（**未采纳、未落盘、禁登记**）。
* 证据分层：**主证据 = 我的独立实测**（含直读见证工件）；旁证 = doc-architect 复核与 translator-a 复跑（两件 /tmp/ta-a.txt、/tmp/ta-b.txt 各 94 B，内容即该冻结行，经我直读核对）。
* 治理：本项**取代**第二轮「保留 HEAD 原文、记为已知美容项」的处置 —— 美容项在第三轮**闭合**；[reports/54](54-docs-freeze-manifest.md) §9.4 的「净差异为零」记录由 T16 新一轮重签取代（**净差异非零**）。第二轮历史记录保留不改。

---

## 6. B 项（06–11 六篇 + GLOSSARY/SPEC/索引）— 通过

* 六篇中文页与六篇英文页成对落地（§2.2），英文页各 `numstat 2 0`（纯插入：切换行 + 空行）。
* **对等**：审计 `Z6/Z7/Z8 armed body pairs = 11 / skipped 0`，`--only` 与全量均 0 FAIL；定向复跑六个新对（`bash scripts/i18n-audit.sh --only` 六篇中文页）→ **PASS (48 checks, 0 warnings) EXIT=0、FAIL 0**，即六对的 `path:LINE` 引用集合、code-span 多重集、以及 h2/h3/表行/围栏结构计数**逐对相等**（中文侧比英文侧多 1 条 Z2 NOTE，属声明义务而非对等项）。四对新页的 NOTE 计数（两侧相等）由译者复跑与我的一致（07 Z6 45(67)、08 Z6 28(40)、09 Z6 8(12)、11 Z6 48(65)；Z7/Z8 逐对相等）。
* **索引/入口去 planned**：`grep -rn "待翻译（planned）"` 对 `README.md` 与 `doc/design/README.md` **无输出（0）**；两文件改指中文页（`README.md` sha256 `8f42fd89…` / 46 行 / 3 211 B；`doc/design/README.md` `c64ec28f…` / 37 行 / 2 830 B）；定向门禁 `bash scripts/doc-verify.sh --only README.md doc/design/README.md` → **PASS (59 checks, 0 warnings) EXIT=0**（含全部相对链接可达 ⇒ **0 MISS**）。
* **GLOSSARY（T5+T7）**：`7b38bed5…` / 373 行 / 30 290 B / v1.1.3 / `numstat 38 14` / **15 hunk**（4/6/20/41/49/56/62/85/114/120/124/191/(272→+24)/338/373）＝ **11 内容行 + 3 版本行 = 14 处站点** + §6 的 24 行插入；算术 `38 = 24+3+11`、`14 = 3+11` ✓；§6 = **98 行**、编号 1..98 连续；`grep planned` 唯一命中为第 56 行 §2.3 的**规范句**（允许）。
* **SPEC（T6）**：`78787978…` / 759 行 / 68 586 B / v1.7.2 / `numstat 4 3` / 4 hunk（3、696、704、+759）；696/704 行 `for NN = 01–11`；Changelog 1.7.2 行含「No rule changes meaning」。
* **T9 十五站点**：§1.6 逐条命中。

---

## 7. 治理记录

### 7.1 D 终局裁定（captain，一行裁定）
终值 = **形态②（冒号式）**，盘面不动、T4 保持 completed；形态③ = captain 早期口头提议、**未采纳**；[reports/66](66-followup2-requirements.md) 现版已按此回退并冻结（`a9dc8d1d…` / 705 行 / 70 589 B）。`reports/66` 的改写链：`f972e352…` → `0baa3c7d…` → `05777639…` → **`a9dc8d1d…`（终值）**。

### 7.2 审计/门禁修订链
审计 `f93da7d5…`(HEAD) → `316bb2db…`(T2) → `944d2aff…`(T9) → **`c778c831…`(t18 终态)**；门禁 `6c62591a…`(HEAD) → `fa2ce899…`(t3) → **`9c154dbe…`(t8 终态)**；workflow `34a5bc0d…`(t3) → **`a2d9818c…`(t18 终态)**。

### 7.3 A-7 / A-8 / A-6（captain 裁定）
**A-7 作废**（不可满足且配方有缺陷）：T2 验收 = A-1…A-6 + A-8 + A-9 + **shell 无关性**；正当性改写为「`/bin/sh`（本容器 dash）对未捕获 TERM 不跑 EXIT trap」；出处 = 本文件的独立复核；由 T16 在 [reports/54](54-docs-freeze-manifest.md)/[reports/62](62-i18n-delivery.md) 登记（[reports/66](66-followup2-requirements.md) 本身冻结、不重出）。**A-8** SIGKILL 残留目录**非空**（13 条目）= 勘误。**A-6** SIGINT 分支必须 `set -m`（普通 `&` 下子进程继承 SIG_IGN）。

### 7.4 T9 收口边界（captain 裁定，含对我 ⑦ 的处置）
**(i) t9 完成判据** = 脚本自身在所有**已武装**对上判定正确 + 负例不放过 + 活树 EXIT=0（与 09/11 落盘无关）；**(ii) 交付态零跳过断言** = **本任务（T14）在最终静默树上的断言**，若最终树 `skipped ≠ 0` 则记 T14 的 blocker finding，**不回溯** t9。零跳过不可省的理由（我补的实证，附录 14）：把中文 11 删除**并**去掉英文 11 前两行切换器（两侧皆无标记）→ **EXIT=0 / PASS (124 checks)**，仅 NOTE `armed 12 … skipped 1`、`body armed 10 … skipped 1` ⇒ **EXIT=0 不足以证明交付态**。

### 7.5 Z2 存在性武装（captain 裁定 b1，逐字登记）
> captain 裁定 b1：追认 `scripts/i18n-audit.sh` 的 Z1/Z2/Z6/Z7/Z8 **存在性武装**语义与相应 NOTE 行，**窄范围覆盖** [reports/66](66-followup2-requirements.md) §3.4.3 第 349 行「不得改判定逻辑与消息形态」；覆盖对象仅限：Z1 武装条件（中文页存在 / 任一侧带冻结切换行 / 两个入口对的英文侧存在）、Z2 的聚合期望 = |Y-1 ∩ 盘面| 与新增的 per-file「恰好 1 次」分支与 NOTE、以及 Z6/Z7/Z8「两侧齐备才比较」的跳过规则。**不覆盖**：Z3/Z4/Z5/Z9 的判定逻辑与消息、`SWITCHER_WINDOW`、DISCLAIMER 文本、`FORBIDDEN_COPIES`、`LEGACY_SWITCHER`、`STATUS_ZH`。交付态必须 `armed pairs = 13`、`body armed = 11`、`skipped = 0`、`Z2 expected = 12`。

出处（消息 id）：`b1c180c8-f28f-41b4-8987-5ea7bda15dcd`、`cd5f2e73-e0cd-436d-9895-2360d836f7b2`、`7e933d70-eb99-4e6b-a83a-62e773cd9587`（我已在 mailbox 内独立核到三者存在，并核对到「该计数口径由 captain 裁定取代 [reports/66](66-followup2-requirements.md) §3.4.3 里『Z2 expected 6→12』的字面表述」的原文）。**保留的我的判断**：终态结论一致、**中间态会自降期望**、消息形态亦变 —— 这正是需要**窄范围追认**而非「无偏差」的原因。

### 7.6 b2（Z6/P2 归一）与 t18（审计 `--repo-mode`）
b2 = captain 裁定采纳我 ① 号建议（审计向门禁对齐），(b)/(c) 不采纳；改前 12 条 `FAIL[Z6]` → 0 条（§1.3）。t18 = 我为 CI blocker 报的 **C-8 需求缺口**（冻结条文只要求门禁有 `--repo-mode`，从未给审计对应语义 ⇒ 「CI 跑审计」在忠实克隆下必红），**非 t3 实现缺陷**；t3 保持 completed 不回溯，缺口登记为**治理第 11 条**；t18 已落地并按 §4.6/§4.7 独立复核通过。

### 7.7 并发红窗口口径与相关事实
**口径**（captain 裁定）：共享工作树，T14 只判**最终静默树**；红若来自该任务**自身交付物**才算其 finding，来自他人半落地者记 **concurrency observation**。本轮亲历窗口序列：`79/0`(HEAD) → `12×Z6 FAIL / 106` → `115/0` → `124/0` → `133/0`（另有 `101 checks / 12 FAIL` 一次）；成员另报 `2×Z7 / 128 checks + skipped 1` 与 `4679/9`（**非我亲测**，来源标注为 doc-tooling / doc-architect）。其他事实：t1 曾以 attempt 1 派发后重派为 attempt 2；**t2 已终态（immutable）**，其 output 不追改，A-9 的 T2-only 参照即 §3 的合成件。

### 7.8 证据保全与自删
* **禁删模式**（captain 裁定）：/tmp/vfy-base*；另列入 /tmp/vfy-t2-probe2.sh、/tmp/keep/doc-verify-t3.sh、/tmp/keep/i18n-audit-T2.sh、/tmp/t3-bare-ws/scripts/doc-verify.sh、/tmp/t18-frozen.sh。证据包目录 /tmp/vfy-base/evidence/（我按 captain 授权并入）现 **76 条目**；`MANIFEST.txt` sha256 = `38ab9c83d0449a4b04efa798a5a4a5ea751cb8f96afb42186888aca693f1f207`。capture 未被触碰：`i18n-audit-fixed.sh` = `044d9dd8…`（**唯一存世的 t9 中间修订**）、`i18n-audit.sh`(基线副本) = `f93da7d5…`。
* **附录 16（自删事实）**：T9 的原始输出（/tmp/t9-evidence.log 与 /tmp/t9-*.out）已于 13:36 前后由作者在清理 scratch 时删除；其数值仅存于该任务的 output 与本文件的独立复跑中；b2 负例可在深度忠实的副本中重跑（副本须置于 `<ws>/code/demo` 形态以保证 WORKSPACE_ROOT 解析）。
* **附录 20（配方缺陷）**：[reports/66](66-followup2-requirements.md) §1.4 的 pristine 取法是 `git show HEAD:… > /tmp/x.sh` 后直接运行 —— 该副本的 REPO_ROOT 会解析为 `/`，脚本在建临时目录**之前**即以 rc=2（missing required normative source）退出 ⇒ 按冻结配方跑 A-7 必然「假通过」。正确做法 = pin REPO_ROOT（本文件做法，附 `diff` 证明只改 1 行）或把副本放在父目录为仓库根的路径下（我另用 `git archive HEAD` 构建独立基线树）。

### 7.9 附录编号（本轮定稿）
12（Z6 判定面边界：抽取器仅收「反引号内 + `path:LINE` + 带扩展名」且同一抽取结果同时驱动存在性与行界）；13（交付树行号位移：六篇英文页 +2 行，示例 `doc/design/08-issues-and-solutions.md:493` ↔ HEAD 491）；14（零跳过盲区实证，§7.4）；16（自删事实，§7.8）；17（冻结锚 vs 交付树重算，§0.2）；18（门禁/审计**检查面**差异：`ext_checked()` 无 `log`，既存，§4.8）；19（落点相关计数与「计数断言必须连同落点」纪律，§0.2）；20（pristine 配方缺陷，§7.8）。

---

## 8. criteria-vs-applied 与差异披露（不作 finding）

1. **Z2 存在性武装**：与 [reports/66](66-followup2-requirements.md) §3.4.3 字面「expected 6→12」不同，属 captain 裁定 b1 的**窄范围修正**（§7.5），**不计为偏差**；我保留「中间态自降期望、消息形态改变」的判断。
2. **[reports/66](66-followup2-requirements.md) §1.3「空目录」措辞**：SIGKILL 残留实为**非空**（13 条目），勘误（§3）。
3. **§1.4 pristine 配方缺陷**：附录 20。
4. **「复原后 EXIT=0」需加模式限定**：仓库外克隆**默认模式**复原后仍 `FAIL (59 failures, 7 warnings, 4795 checks)`（P2/P4 在外落点硬检 = C-5 设计）；复原为绿的条件是 ① `--repo-mode` EXIT=0 或 ② 工作区落点副本。属**文档精确性说明**，非缺陷。
5. **落点相关计数**：附录 19；一切计数断言连同 WORKSPACE_ROOT 书写。
6. **harness 伪影（如实披露）**：§4.7 工作区落点探针的 2 条基线失败来自我孪生树缺 `.gitignore`（引 .gitignore:56），与注入项无关、实质结论不变。
7. **门禁不报告的 `.log` 引用**：附录 18；审计比门禁多覆盖一类（`.log`），**不是**放宽。
8. **旧修订在当前树 FAIL**：§1.2 的时点效应说明。
9. **t18 的 checks 计数**：clone 内与默认模式相等（133 = 133），换到交付树亦与门禁无关；不构成强度变化。

---

## 9. verdict

**pass**。

* **(i) t9 完成判据**：满足（§1.1–§1.6；默认模式四段链、b2 负例、信号性质、活树 EXIT=0、十五站点）。
* **(ii) b1 交付态断言**：满足（§2；`13/13`、`11/11`、`skipped 0`、`Z2 12/12`、`Z3 98`、六页在盘、六条英文首行逐字 B-7、checks 4795 > 3912、warnings = 4）。
* **A / C / D / B 四项**：全部通过（§3–§6）；**C 的 CI 缺口**在 t18 落地后由我端到端复核为**可绿**（§4.6），并独立复核其六类负例与真缺失 P2 未被放过（§4.7）。
* **无 blocking finding**；§8 的 9 条差异均为**已裁定/已披露**的观察项，不改变 verdict。
* 未了事项归属：`reports/68-review`（T17 独立复核）、T15（F 归档清理）、T16（[reports/54](54-docs-freeze-manifest.md)/[reports/62](62-i18n-delivery.md) 重签与新章节）。

---

## 10. 复现命令（全部可粘贴；输出见 §1–§6 与证据包）

```bash
cd /data/dsh/home/workspace/code/webrtc-demo

# (ii) 交付态：零跳过与计数
bash scripts/i18n-audit.sh; echo "EXIT=$?"
bash scripts/doc-verify.sh; echo "EXIT=$?"

# A：信号性质（必须 set -m，否则 SIGINT 不投递）
P=$(mktemp -d); for S in TERM INT HUP; do ( set -m; TMPDIR="$P" bash scripts/i18n-audit.sh >/dev/null 2>&1 & p=$!; sleep 1.5; kill -"$S" "$p"; wait "$p"; echo "$S rc=$? residue=$(find "$P" -maxdepth 1 -name 'i18n-audit.*' | wc -l)" ); done; rm -rf "$P"

# C：默认模式逐字节（HEAD / T3-only / 当版，pinned REPO_ROOT）
bash /tmp/vfy-pv-head.sh > /tmp/h.out 2>&1; bash /tmp/vfy-pv-t3.sh > /tmp/t.out 2>&1; cmp /tmp/h.out /tmp/t.out && echo IDENTICAL

# C：忠实克隆端到端（须置于父目录两级之上、无工作区）
git clone --depth 1 --no-recurse-submodules file://$PWD /tmp/x/repo
( cd /tmp/x/repo && cp -a "$OLDPWD/doc" "$OLDPWD/scripts" . && bash scripts/i18n-audit.sh --repo-mode; echo "EXIT=$?" )

# D：冻结行逐字节
cmp <(sed -n '450p' reports/66-followup2-requirements.md) <(sed -n '194p' doc/design/zh-CN/02-architecture.md); echo "EXIT=$?"

# 门禁子集（本报告自身）
bash scripts/doc-verify.sh --only reports/67-followup2-verification.md; echo "EXIT=$?"
```

---

## 附：本文件的自证

本文件按 [reports/66](66-followup2-requirements.md) 的书写约定撰写，并通过自身子集门禁：`bash scripts/doc-verify.sh --only reports/67-followup2-verification.md` → 期望 `EXIT=0`（原始输出见 T14 的 task output）。本文件自身的 sha256 / 字节 / 行数由 T16 在 [reports/54](54-docs-freeze-manifest.md) 新一轮中现场实测登记；**verdict = pass**（§9）。
