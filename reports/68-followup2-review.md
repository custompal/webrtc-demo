# 68 — 第三轮 E 项独立复核（T17）

> Status: **needs_revision** · Owner: `verifier` · Task: t17 (attempt 1), 2026-09-21
> 复核对象：[reports/54](54-docs-freeze-manifest.md) §10 增补、[reports/62](62-i18n-delivery.md) §15 增补、[reports/66](66-followup2-requirements.md)（冻结口径）、[reports/67](67-followup2-verification.md)（T14 独立验证）
> 判据：任务契约（review）+ [reports/66](66-followup2-requirements.md) §5 的 E-1…E-10 + captain 追加项（31 路径 C4 指纹、四报告 sha 互校、E-9 前置、修订链、"唯一 1 hunk"）
> 书写约定：已存在的仓库内路径写反引号；`/tmp` 等 HOST 路径、以及不存在的路径/行号写纯文本。
> **本文件的 verdict = needs_revision**（见 §9）；我未修改任何被复核文件（只读复核）。

---

## 0. 重要前提：复核期间被复核文件**仍在被改写**（不是静默树）

T17 进行期间，两份被复核报告被**多次改写**（我逐次现场实测，sha256/行数/字节/mtime）：

| 观测次序 | [reports/54](54-docs-freeze-manifest.md) | [reports/62](62-i18n-delivery.md) |
|---|---|---|
| t16 output 登记值 | `3b9fd1b9…` / 545 行 / 43 475 B | `80fc8fa8…` / 519 行 / 64 711 B |
| 观测 1 | `3b9fd1b9…` / 545 / 43 475 / mtime 14:21:00 | `80fc8fa8…` / 519 / 64 711 / mtime 14:26:38 |
| 观测 2 | `7f6ea929…` / 559 / 46 071 / mtime 14:30:29 | `bda58a14…` / 533 / 67 534 / mtime 14:30:48 |
| 观测 3 | `f79792e0…` / 559 / 46 477 / mtime 14:34:38 | `67dfcd22…` / 534 / 69 281 / mtime 14:35:10 |
| 观测 4 | `f79792e0…` / 559 / 46 477 / mtime 14:34:38 | `5df74bd6…` / 534 / 69 280 / mtime 14:35:10 |
| 观测 5 | `fe4f3b0f…` / 559 / 46 771 / mtime 14:37:11 | `717d031b…` / 534 / 69 597 / mtime 14:37:18 |
| 观测 6 | `0f82c5de…` / 559 / 46 771 / mtime 14:39:59 | `717d031b…` / 534 / 69 597 / mtime 14:37:18 |
| 观测 7 | `d2325eb2…` / 566 / 49 366 / mtime 14:42:18 | `bba07644…` / 534 / 70 184 / mtime 23:14:41 |
| **观测 8（本报告最终绑定）** | **`d2325eb202d4cc1771f10f43537ac2c5008fc060a6b38a5fb16b4cfe090025af` / 566 / 49 366 / mtime 14:42:18** | **`35f8ae692a18bc283b038bcc08516157e5319fb2f4ac1c252840323ca812b730` / 534 / 70 205 / mtime 23:21:03** |

**两条报告各自的修订链（以现场实测为准，逐链登记）**

* [reports/54](54-docs-freeze-manifest.md)：`3b9fd1b9…`(545 / 43 475 / 14:21:00，t16 终态值) → **`f02a1952a23c707acecc3df30b23c6438580192d1977f64f474243dd2680d68a`**(546 / 44 808 / 14:29:07，captain 第二次改写轮) → **`7f6ea929…`**(559 / 46 071 / 14:30:29，captain 第三次改写轮) → `f79792e0…`(559 / 46 477 / 14:34:38) → `fe4f3b0f…`(559 / 46 771 / 14:37:11，captain"绝对冻结"所引值) → `0f82c5de…`(559 / 46 771 / 14:39:59) → **`d2325eb2…`(566 / 49 366 / 14:42:18，现行)**。
* [reports/62](62-i18n-delivery.md)：`80fc8fa8…`(519 / 64 711 / 14:26:38，t16 终态值) → **`1431d9ad9d6da04e1c77888dda3c8421fa53717504f39e0dba774e6811cb03c6`**(519 / 65 854 / 14:29:03，第二次改写轮) → **`bda58a14…`**(533 / 67 534 / 14:30:48，第三次改写轮) → `67dfcd22…`(534 / 69 281 / 14:35:10) → `5df74bd6…`(534 / 69 280 / 14:35:10) → `717d031b…`(534 / 69 597 / 14:37:18，captain"绝对冻结"所引值) → `bba07644…`(534 / 70 184 / 23:14:41) → **`35f8ae69…`(534 / 70 205 / 23:21:03，现行)**。

**⚠️ 冻结基准异常（captain 要求"实测到不同值即报"）**：captain 声明"第 4 次改写后已**绝对冻结**"的基准为 [reports/54](54-docs-freeze-manifest.md) = `fe4f3b0f…` / 559 / 46 771（14:37:11）与 [reports/62](62-i18n-delivery.md) = `717d031b…` / 534 / 69 597（14:37:18）；**盘面实测与该基准不符**：54 = `d2325eb2…` / **566** / **49 366**（14:42:18）、62 = `35f8ae69…` / 534 / **70 205**（23:21:03）。5 秒双采样一致（现盘已静止），故该"冻结"实际落在**更晚的第 8 环**上。⇒ 本报告绑定**盘面现行值（观测 8）**，不绑定该冻结基准；该异常单独列出供 captain 裁定（§9 尾）。

**口径声明（重要）**：captain 通信中先后引用的**四组**"现场值"——t16 output（`3b9fd1b9…`/545、`80fc8fa8…`/519）、第二次改写轮（`f02a1952…`/546、`1431d9ad…`/519）、第三次改写轮（`7f6ea929…`/559、`bda58a14…`/533）、"绝对冻结"轮（`fe4f3b0f…`/559、`717d031b…`/534）——**均已不在盘上**（对现行值以外的各值时点，`grep` 命中均为 0）。故我**不能**把 t17 绑定到其中任何一轮，只能绑定**盘面现行值（观测 8）**；两条链按上表逐链登记，captain 引用过的节点以粗体标注。

**第三条「终态后修订」治理如实性（按 captain 给定形式登记）**：第三次改写由 **captain 的 F-3 逐字指令与 t16 收口交叉**触发、**captain 侧有责**；doc-architect **主动同步、不越权改派**；captain 现场核对改后件后**接受**、**不判 finding**，登记为**第三次流程偏差**，t17 以**现场最终哈希**复核三条链。（本合同条与前两条同构，累计三次"终态后修订"均已如实登记。）

**结论**：captain 的 ⑤ 口径（只判最终静默树）在本轮**不成立**——被复核对象在 t16 标记 completed 之后、且在我的复核进行中持续变化（54 至少 **7** 个可观测修订、62 至少 **7** 个，见上链）。因此：

* **t16 的 output 登记身份（`3b9fd1b9…` / 545 / 43 475；`80fc8fa8…` / 519 / 64 711）已过期**，与盘面不一致；
* 本报告的**绑定值 = 观测 6**（[reports/54](54-docs-freeze-manifest.md) = `0f82c5de…` / 559 / 46 771；[reports/62](62-i18n-delivery.md) = `717d031b…` / 534 / 69 597）；观测 6 在落笔前连续两次实测未变，可视为**已静止**，但任何后续编辑仍会使绑定失效；
* **复核期间被修好的两项（如实记录，不美化也不苛责）**：F1 → [reports/62](62-i18n-delivery.md) 现把 [reports/54](54-docs-freeze-manifest.md) 反向登记为 `fe4f3b0f…`/46 771（复核早期值；**注意 54 其后又变为 `0f82c5de…`，故该反向登记现又落后一环**，见 F1'）；F2 → 同一 `--only` 命令现为 **`PASS (312 checks, 1 warnings) EXIT=0`**（原 3 条约定违规已改）**已消解**；
* **第二条「终态后修订」治理如实性（按 captain 给定措辞登记）**：本次改写由 **captain 的追加指令与 t16 收口交叉**触发、**captain 侧有责**；doc-architect **主动同步、不越权改派**；captain 现场核对改后件后**接受**、**不判 finding**，但登记为**第二次流程偏差**，t17 须以**现场最终哈希**复核上述两条链。
* **本报告须补记的事实**：captain 接受的那一轮是 **14:29**（`f02a1952…` / `1431d9ad…`），但盘面其后又发生 **4 次**改写（14:30:29 / 14:30:48、14:34:38 / 14:35:10、14:37:11 / 14:37:18、14:39:59），均**超出**该轮核对范围 → 属 F3 的组成部分；
* 仍存的 **F4**（陈旧计数/行号）与 **F5**（[reports/62](62-i18n-delivery.md) 自身身份无登记）、以及 **F1'**（62 对 54 的反向登记又落后一环，见 §9）见 §9。

---

## 1. E-1 登记值 vs 现场实测（[reports/54](54-docs-freeze-manifest.md) §10.2 A/B/C 三表）— **通过（0 DRIFT）**

口径：对 §10.2 的 **A 表 15 行 + B 表 8 行 + C 表 11 行 = 34 行**逐行现场重测 `sha256sum` + `wc -c` + `wc -l`（有登记权限者另测 `stat -c %a`），逐格比对。

* 结果：**OK = 34，DRIFT = 0，MISSING = 0**（脚本逐行打印 OK；任一不等会打印 DRIFT）。
* 覆盖：`scripts/doc-verify.sh`（`9c154dbe…` / 46 617 / 1029 / 444）、`scripts/i18n-audit.sh`（`c778c831…` / 45 596 / 1089 / 755）、`doc/design/SPEC.md`（`78787978…` / 68 586 / 759 / 600）、`doc/design/zh-CN/GLOSSARY.md`（`7b38bed5…` / 30 290 / 373 / 644）、`doc/design/zh-CN/02-architecture.md`、`README.md`、`doc/design/README.md`、六篇英文页、六篇中文新页、`.github/workflows/docs.yml`、[reports/66](66-followup2-requirements.md)、[reports/67](67-followup2-verification.md)，以及仓库外归档 `/data/dsh/home/workspace/tmp/i18n-audit-probe-8bb69d2.tar.gz`（`2bda1048…` / 17 507 348 B）。
* C 表 11 行（§8.3/§8.4 未受影响台账行）同样逐行为 **OK**，与 §10.2 声明的「0 DRIFT」一致。

---

## 2. E-2 计数自洽（独立提取）— **通过**

| 项 | 独立提取 | 交叉比对 |
|---|---|---|
| GLOSSARY §6 数据行 | **98**（按 `^| [0-9]` 计数） | 审计实现 `if [ "$rows" != 98 ]`；活树 NOTE `Z3: §6 data rows = 98 … # = 1..98`；[reports/54](54-docs-freeze-manifest.md) §10.2 A4 登记「74 → 98」 |
| Y-1 文件数 | **12**（`Y1` 数组元素计数） | 审计 NOTE `Z2: disclaimer occurrences = 12, expected = 12 (= |Y-1 ∩ disk| of 12 …)`；[reports/62](62-i18n-delivery.md) §15 登记 12 |

两处计数均为**独立来源**提取后比对（文件行数 / 脚本常量 / 运行 NOTE / 报告登记值），一致。

---

## 3. E-3 C3/C4 类结论可复现 — **通过**

* **E-3② C4 联合指纹（31 路径，captain 追加项）**：按 [reports/66](66-followup2-requirements.md) §7.2 的 heredoc 配方独立复算（两端 `LC_ALL=C sort`）得 **`053e23bf5ac07e8f532b8d2f0ad6d7ed987a61ca546836b44d950ddbcc6edc29`**，与 [reports/54](54-docs-freeze-manifest.md) §10.3 登记值**逐字符相同** ✓；旧 19 路径值 `40d9fa70…` 已按登记作废。复算前记录的 checker 修订：门禁 `9c154dbe…`（mtime 2026-09-21 13:41:22）、审计 `c778c831…`（mtime 13:46:30）。
* **E-3① `--repo-mode`（两落点）**：
  | 落点 | 审计默认 | 审计 `--repo-mode` | 门禁默认 | 门禁 `--repo-mode` |
  |---|---|---|---|---|
  | 活工作区（冻结落点） | PASS 133/0 EXIT=0 | PASS 133/0 EXIT=0；summary 四类全 0、remaining 0 | PASS 4795/4 EXIT=0 | PASS 4795/4 EXIT=0；summary 四类全 0、remaining 0 |
  | 仓库外克隆（`WORKSPACE_ROOT=/tmp`） | FAIL 22 failures EXIT=1 | **EXIT=0**；`P2=2 P4=0 SUBMODULE=8 ENVSLASH=12`、`22 = 2+8+12`、remaining 0 | FAIL 59 EXIT=1 | **EXIT=0**；`P2=39 …`、`59 = 39+8+12`、remaining 0 |
  与 [reports/54](54-docs-freeze-manifest.md) §10.5 / [reports/62](62-i18n-delivery.md) §15.3 登记一致 ✓。

---

## 4. E-4 关键数值可复现（抽查 ≥3）— **通过**

| 抽查项 | 复现命令 | 结果 |
|---|---|---|
| A 项信号探针 | `set -m` + 受控 TMPDIR 发 TERM/INT/HUP | **143 / 130 / 129**，residue **0**；SIGKILL **137**、residue **1**（**13 条目非空**）✓ |
| D 项 | `cmp` 冻结行、`sha256sum`、`numstat`、hunk 数 | 冻结行 [reports/66](66-followup2-requirements.md):450 ≡ 盘面 194（cmp 无输出）；`b4182633…`；`numstat 1 1`；**hunk = 1** ✓ |
| 六篇对等 | `i18n-audit.sh --only` 六篇中文页 | **PASS (48 checks, 0 warnings) EXIT=0、FAIL 0** ✓ |
| 索引/入口 | `doc-verify.sh --only README.md doc/design/README.md` | **PASS (59 checks, 0 warnings) EXIT=0** ✓ |

---

## 5. E-5 治理覆盖 — **通过（11 条 + 遗留引用齐备）**

[reports/62](62-i18n-delivery.md) §15.7 为**编号 1–11 条**，逐条覆盖：A 的 trap/SIGKILL 边界与 shell 无关性、C 的 `--repo-mode`/CI/C-8 缺口与 t18 收口（含类别集合与未放水证据）、D 的终局裁定与三形态链、B 的六页/GLOSSARY/SPEC/索引、F 的归档与清理口径、governance 附录 11/12/13/14/15/16/18/19/20（附录 17 交叉指向 [reports/67](67-followup2-verification.md) §0.2）、以及上一轮遗留项引用（reports/64 版本链与 CAPTAIN RULING、t9/t10/t11 不可达、doc-architect 与 translator-b 上一轮移除记录）✓。另：本轮新增的**四值修订链**（`2da3354b…` 首写 → `6fbd0126…` → `715e950c…` → `b43fda65…`）已在 [reports/54](54-docs-freeze-manifest.md) §10.8 登记 ✓（此前三值口径的缺口已被这次增补补上）。

---

## 6. E-6 相对链接 0 MISS — **通过**

以报告自身目录为基准解析（`reports/`）：[reports/54](54-docs-freeze-manifest.md) 唯一相对链接 **5 个，MISS = 0**；[reports/62](62-i18n-delivery.md) 唯一相对链接 **14 个，MISS = 0**（两报告并集 15 个唯一目标，与 t16 的"15 个目标全 OK"一致）。

---

## 7. E-7 verdict 形式 — 见 §9（显式给出，且带 id/severity/problem/requiredFix）

---

## 8. E-8 四报告 sha 互校 / E-9 前置存在性 / "唯一 1 hunk"

* **E-9 前置存在性**：[reports/67](67-followup2-verification.md) 在盘（`b43fda65…` / 325 行 / 34 857 B）✓。
* **工件登记值互校（通过部分）**：所有**工件**（checker/审计/SPEC/GLOSSARY/zh-CN/01–11/英文 01–11/索引/入口/[reports/66](66-followup2-requirements.md)/[reports/67](67-followup2-verification.md)）在四份报告中被登记的 sha256 与现场实测**逐格相同**（§1 的 34 行 + §3 的指纹输入 + 抽查）。
* **报告身份登记值互校（观测 6 时不通过 → F1'）**：[reports/62](62-i18n-delivery.md) 第 464 行反向登记 `reports/54` = **`fe4f3b0f…` / 559 行 / 46 771 B**，而盘面 [reports/54](54-docs-freeze-manifest.md) 已在 14:39:59 变为 **`0f82c5de…` / 559 行 / 46 771 B** ⇒ **反向登记落后一轮**（复核早期曾由 `7f6ea929…` 修到 `fe4f3b0f…` 而"消解"，其后 54 又变，故 F1 以 F1' 形式重现）。
* **"唯一 1 hunk"（T14 修订链）**：以现场文件反向重建 [reports/67](67-followup2-verification.md) 的修订前版本，得 `715e950c…` / 324 行 / 34 380 B，与 t14 终态记录值**逐字节吻合**；与最终件的 `diff` = **恰 1 个 hunk（第 160 行，1 行 → 2 行）** ✓（"唯一改动 = §4.3"可核）。pinning 配方可复现性：我的 pinned 变体与 captain 提供的 pinned 变体**逐字节相同**（两条独立路径、0 差异）✓（两件均为 HOST /tmp 路径，按约定不写反引号）。
* **N2（room.go:999999 的 5 vs 3）**：同克隆、同 checker（`c778c831…`）、同落点（`WORKSPACE_ROOT=/tmp`）、四类降级同值（`P2=2 P4=0 SUBMODULE=8 ENVSLASH=12`）下，差异**只由注入形状决定**：**替换镜像对内既有引用**（EN `doc/design/01-requirements.md:60` 把 room.go:13 改为 room.go:999999）= `27 = 22+5`（1 越界 + Z6 双向镜像 + Z7 双向 code-span）；**单侧追加**（EN `doc/design/07-build-and-deploy.md` 文末追加一条探测引用）= `25 = 22+3`。我按其构造复跑，得到与其原始输出**完全相同的 5 条失败** ⇒ **确定性、非不确定性、非缺陷**（按 captain 裁定登记，不报 finding；以上探测串与 HOST 路径均按约定写纯文本）。

---

### 8.1 已知限制（**我独立复现**）：并发/负载敏感假红 —— 非交付失败、非本轮引入

* **现象**：同一静态树、同一命令，**并发**跑全量门禁时会**间歇**出现 1 条 FAIL，落点均为 CMD 证据分支（`scripts/doc-verify.sh` 约第 866–874 行）：先单行 `sed -n "${line}p"`，再节内 `sed -n "${sec_start},${line}p" | grep -qE 'reports/…|host-commands\.md'`；该次读取瞬时为空即 `ok_ev=0` → 报 `… is host/workspace-only and is cited without in-repository evidence`。`checks` 恒为 4795、`warnings` 恒 4。
* **我的独立复现（3 轮 × 3 并发全量门禁，同一静态树）**：轮 1 = `2 PASS / 1 FAIL`；轮 2 = `3 FAIL`；轮 3 = `3 PASS`。红样本 stdout 与 captain 提供的两个样本**同 sha**：绿 `01ce50b63e25…`、红 `1979a5d42f3c…`、`f83dd87e88e1…`（我另见 `71e153950635…`）。
* **顺序对照**：连续 **6** 次全量门禁 **6/6 PASS**，且 stdout **逐字节相同**（均为 `01ce50b63e25…`）；两个被指文件**隔离跑**亦通过（`--only doc/design/SPEC.md` → PASS (269 checks, 0 warnings)；`--only doc/design/zh-CN/11-coding-standards.md` → PASS）。
* **定性**：本沙箱文件系统在该分支的读取存在瞬时异常（未定位到 I/O 层根因）；t3/t8 只改枚举与 `--repo-mode`，**未触碰该规则** ⇒ **既存限制、本轮未引入、非交付失败**。
* **缓解与纪律**：门禁（含本报告全部复跑）**一律顺序执行**，不得与其他门禁运行并发；CI 每 job 单跑不受影响。若见同类 FAIL，**先顺序复跑确认**再登记。

### 8.2 措辞残留（**非数值不一致**，待 captain 裁定）

* [reports/54](54-docs-freeze-manifest.md) §10.2 的 A-15 行（来源列）仍写「**修订链三值**与『终态后修订』治理登记见 §10.8」，而同文件 §10.8 已写「**四值**并列」（`grep`：`三值` 命中 1、`四值` 命中 4）。A-15 的数值（`b43fda65…` / 34 857 / 325）与盘面**一致**，故这是**纯措辞残留、不是数值不一致**。
* 处置：doc-architect 已按 captain 的「先报我」规则上报，等 captain 二选一 —— **(a)** 授权一字修正（`三值` → `四值`）；**(b)** 保持硬冻结，由本报告按「措辞残留（非数值不一致）、**待 captain 裁定**」登记。**在收到指令前不得改动**（我亦不改，见只读声明）。本报告按 (b) 记录，并**不计入 findings**。

## 9. verdict

**needs_revision**

依据：实质判据（E-1/E-2/E-3/E-4/E-5/E-6/E-9、全量双门禁、指纹、pinning、N2 解释）全部通过；**F2 与 F4 已在复核期内被修好并留痕**；但 **F1'（62 对 54 的反向登记落后两轮）**、**F3（过程/冻结）**、**F5（[reports/62](62-i18n-delivery.md) 自身身份无登记）** 仍未消解，故按契约不予 pass。另见 §8.1 的**已知限制**（并发/负载敏感假红，我独立复现，非交付失败、非本轮引入）。

### findings

| id | severity | status | problem | requiredFix |
|---|---|---|---|---|
| **F1'** | blocker | **未消解（重现）** | [reports/62](62-i18n-delivery.md) 第 464 行反向登记 `reports/54` = `fe4f3b0f…` / 559 / 46 771，而盘面 54（现行）= **`d2325eb2…` / 566 / 49 366** ⇒ 报告身份登记值**落后两轮**，与盘面不一致（E-8）。 | 在 **captain 冻结 54 之后**以现场实测重写该反向登记（现盘 = `d2325eb2…`）；不得绑定已被取代的 `f02a1952…` / `7f6ea929…` / `0f82c5de…`。 |
| **F2** | blocker | **已消解（复核期内被修好）** | 复核早期 `bash scripts/doc-verify.sh --only reports/54-docs-freeze-manifest.md reports/62-i18n-delivery.md` → **FAIL (3 failures, 1 warnings, 317 checks)**：探测串/HOST 路径写在反引号内（越界引用、裸 `room.go`、未标 HOST 的 `/tmp` 路径）⇒ 违反书写约定、E-10 不通过。 | 已由观测 5 的编辑修好：**现为 `PASS (312 checks, 1 warnings) EXIT=0`**；无剩余动作，**仅作留痕**。 |
| **F3** | high | **未消解（过程项）** | 被复核对象在 t16 completed 之后、且在我复核期间**持续被改写**：54 与 62 各 **≥7 个**可观测修订（见 §0 两条链，mtime 14:21→14:42 / 14:26→23:14）；captain 通信先后引用的三组值（t16 output、第二次、第三次改写轮）**均已不在盘上**；**无冻结时刻**。 | captain **正式宣布冻结**（含时刻）并停止写入；T16 以现场实测**重新登记**两份报告身份与「本节绑定」，并明确 t16 output 及前三次引用身份均已过期；随后重开/重派 T17 复测。 |
| **F4** | low | **已消解** | [reports/62](62-i18n-delivery.md) 第 534 行原登记 `PASS (309 checks, 1 warnings)` 且 warning 引 54 第 519 行；**现行版已改为 `PASS (314 checks, 1 warning)` 且引 [reports/54](54-docs-freeze-manifest.md) 第 **532** 行**，与我现场实测（314 checks、warning 在 54:532）**逐项一致**。 | 无需动作，仅作留痕。 |
| **F5** | low | **未消解** | [reports/62](62-i18n-delivery.md) 自身身份（现行 = `bba07644…` / 534 / 70 184）在 [reports/54](54-docs-freeze-manifest.md)/[reports/66](66-followup2-requirements.md)/[reports/67](67-followup2-verification.md) 中**均无登记**（反向登记不对称）。 | 在 [reports/54](54-docs-freeze-manifest.md) §10 增补登记 [reports/62](62-i18n-delivery.md) 身份（sha256/行数/字节/mtime），与 F3 的重新登记一并完成。 |

### 通过项（不构成 finding）

E-1（34 行 0 DRIFT）、E-2（98 / 12 独立提取一致）、E-3（31 路径指纹 `053e23bf…` 逐字符命中；两落点 `--repo-mode` 与登记一致）、E-4（信号 143/130/129 + SIGKILL 137/1/13；D 逐字节 + hunk=1；六对 48/0；索引 59/0）、E-5（治理 11 条 + 遗留引用 + 四值链已补登）、E-6（5 + 14 链接，0 MISS）、E-9（[reports/67](67-followup2-verification.md) 在盘）、"唯一 1 hunk" 可核、pinning 配方可复现、N2 差异已解释（非 finding）、以及**全量双门禁**：`bash scripts/doc-verify.sh` → `PASS (4795 checks, 4 warnings) EXIT=0`、`bash scripts/i18n-audit.sh` → `PASS (133 checks, 0 warnings) EXIT=0`。

> 独立性与只读声明：本轮 A/C/D/B 的实现由其他成员完成；我**未修改任何被复核文件**（仅新增本报告），所有数值来自我自己的现场命令。verdict 之所以为 **needs_revision**，是因为 **F1'（62 未登记 54 现行值，落后两轮以上）+ F3（复核期持续改写 ≥8 轮、captain 通信四组引用身份均已过期、且"绝对冻结"基准与盘面不符）+ F5（[reports/62](62-i18n-delivery.md) 自身身份无登记）**；**F2/F4 已在复核期内被修好**并留痕。**冻结编辑并补完 F1'/F3/F5 后，请重开/重派 T17**——重测通过即可转 pass；本报告对两报告的绑定值为**观测 8**（[reports/54](54-docs-freeze-manifest.md) = `d2325eb2…` / 566 / 49 366 / mtime 14:42:18；[reports/62](62-i18n-delivery.md) = `35f8ae69…` / 534 / 70 205 / mtime 23:21:03）。**已知边界**：§8.1 的并发/负载敏感假红（门禁顺序执行即不受影响）。**异常项**：captain 声明的"绝对冻结"基准（`fe4f3b0f…`/`717d031b…`）**不在盘上**，见 §0 的异常段落。
