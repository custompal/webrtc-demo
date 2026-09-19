# 64 — 本轮后续工作冻结口径（T1：i18n 审计脚本 + C1–C5 收尾）

> Status: **frozen** · Owner: `doc-architect` · 2026-09-19
> 判据来源（全部为冻结件或现场实测）：team goal（B3 / C1–C5）、[doc/design/SPEC.md](../doc/design/SPEC.md) §7.5/§7.6/§9、`doc/design/zh-CN/GLOSSARY.md` v1.1.1 §3/§4/§5.3/§5.4/§5.5/§6/§6.1、[reports/61-i18n-verification.md](61-i18n-verification.md) §3–§4、[reports/62-i18n-delivery.md](62-i18n-delivery.md) §6/§9.5/§10/§13、[reports/60-github-push-runbook.md](60-github-push-runbook.md) §7、[reports/54-docs-freeze-manifest.md](54-docs-freeze-manifest.md) §8。
> 效力：本文件冻结 T2–T7 的验收口径。任务台账描述与本文件冲突时，以本文件 §0 的两条实测证据为准，并由 captain 更正台账；执行者不得自行放宽或扩大范围。
> 写作纪律：本文数值均为 2026-09-19 现场实测；不沿用任何转述值。§2.2 的每一项判据都已用一次性校验器在本仓库上跑过（结果：**0 failure**，基线见 §2.6），因此冻结口径是被证明可满足的。

> **CAPTAIN RULING（2026-09-19，由 captain 直接写入；效力高于本文 §0.1/§4.1/§9.3 中关于 C2 形态与 t4/t8 处置的表述）**
>
> C2 的最终盘面状态（已交付、已由 captain 实测，**不再改动**）：
> * `doc/design/zh-CN/02-architecture.md` 保持 **HEAD 原文**（sha256 `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42`，本轮该文件零净改动）。中程形态 A 曾在 t4 交付后按本文第 6 版的还原指令被撤销；captain **接受该结果**，不再重做（A 与 HEAD 原文均合规）。
> * `doc/design/zh-CN/01-requirements.md:328` 保持**已交付形态**：`| D-4 | 历史文本「旋转已烘焙进 I420」，上游源码已证伪（disproven） | 对采集路径的错误心智模型 |`（sha256 `a4271a402396099f56ca952e6d489100b13027375e884751d89b900644c2c8d5`）。本文 §4.1 的**形态 D 提案未采纳**——D 与已交付形态等价合规（连续定译串 `已证伪（disproven）` 完整、Z4 邻接成立），而 t8 已终态交付，对同一行做第三次改写收益为零且会使验证绑定失效。
> * **t4 与 t8 均不重开、台账文本不改写**；`reports/64` 不再修订（其 §0.1/§4.1/§9.3 的旧表述与上述裁定的差异，由 `reports/65` 与 T7 报告如实记录，含六版修订链与「形态 A 交付后被撤销」这一事实）。
> * **禁止任何成员再对上述两个文件或本文件写入**（T5 只写其验证报告，T7 只写 `reports/54-docs-freeze-manifest.md` 与 `reports/62-i18n-delivery.md`）。本文其余章节（审计脚本检查项、C1、C3、C4、C5）继续有效。

---

## 0. 冻结前提与派单更正（先读；影响 t4/t8/t7）

### 0.1 C2 的唯一落点（captain 2026-09-19 最终裁定：只改 01；02 零改动）

最初派单把「02 篇的 D-4 行」当成一处，这是定位错误。经过一轮中程更正（曾裁定 02:194 用形态 A、01:328 用形态 D）后，captain 给出**最终处置**，本节以最终处置为准：

* **`D-4` 行只在 01 篇。** `grep -rn "D-4" doc/design/zh-CN/` 只有**一条**命中：`doc/design/zh-CN/01-requirements.md:328`；`doc/design/zh-CN/02-architecture.md` 内**没有** `D-4`，也没有串 `被上游源码已证伪（disproven）`（其第 148 行 `已否决（rejected）/已证伪（disproven）` 与第 194 行 `…中已否决（rejected）的…` 都**已经合规**，连续定译串完整）。英文对端同行在 `doc/design/01-requirements.md:325`（`| D-4 | Legacy claim "rotation already baked into I420" is disproven by upstream sources | ... |`）。独立旁证：[reports/62-i18n-delivery.md](62-i18n-delivery.md) §9.5 第 3 条写的也是 01:328。
* **最终处置（唯一口径）：**

| 单 | 交付形态 | 文件 | 是否写文件 |
|---|---|---|---|
| **t4** | **零改动核实**：核实 02 无需改动 + 跑门禁留证，**changedFiles 为空**（`git diff` 对该文件为空） | `doc/design/zh-CN/02-architecture.md` | **否**（不得写） |
| **t8** | C2 的**唯一**实现单：把第 328 行逐字改为冻结形态 **D** | `doc/design/zh-CN/01-requirements.md` | 是（只此一个文件） |

* **中程形态 A（`…中的已否决（rejected）旋转烘焙实验。`）已作废**：captain 说明 t4 的 inScope 在批准后无法改写、而 t4 又是 t5 的形式依赖，故把 t4 收口为零改动核实，02:194 保持 HEAD 原文。**若已有执行者按中程形态改写了 02，必须 `git checkout -- doc/design/zh-CN/02-architecture.md` 还原到 HEAD。**
* **残留美容项（如实记录，不修）**：02:194 的「…中已否决（rejected）的…」句读偏生硬但**合规**（连续定译串完整、全角标点），按最终处置保留原文；该项记入 t7 的「已知美容项」。
* **本节取代本文初版与中程的全部旧结论。** 冻结目标行见 §4；两单各自的验收命令见 §8。

### 0.2 C3 的「最终提交」= 当前 HEAD（本轮不提交）

* 实测 HEAD = `1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae`（2026-09-18T22:55:29+08:00），工作树 clean（`git status --porcelain` 输出为空）。
* 本轮 T2/T3/T4 的改动按任务纪律**不提交**（T5 明确「不得 commit」），因此裸克隆（`git clone`）**只包含 HEAD 提交**、不含本轮改动，克隆内也不会有本轮新增的审计脚本。
* 冻结：C3 实测对象 = 测量当时 `git rev-parse HEAD` 指向的提交；报告必须同时记录 `git status --porcelain` 输出与「克隆不含本轮未提交改动」这一事实。若 captain 决定先提交再测，则以提交后的新 HEAD 重测并**整体替换**该节数值（不得混用两个提交的数值）。

---

## 1. 冻结件与硬约束（对 T2–T7 全体生效）

1. **checker 冻结**：`scripts/doc-verify.sh` = sha256 `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` / 919 行 / 41 673 B / `-r--r--r--`（0444）。本轮**任何**任务不得写它，也不得让审计脚本被它调用或反向调用它。
2. **中文页判据来源**：`doc/design/zh-CN/GLOSSARY.md` **v1.1.1**（术语表 74 条、§3 切换器、§4 声明、§5.5 状态词）。
3. **审计与门禁的关系**：门禁（checker）是唯一交付判据（SPEC §9）；审计脚本是把 [reports/61](61-i18n-verification.md) §2–§3 的手工检查固化成一条可复跑命令的**补充证据**，不替代门禁。两者结论冲突时以门禁为准，并立即上报 captain。
4. **本轮可写文件**（越界即失败）：T2 只写审计脚本；T3 只写 `doc/design/SPEC.md`；**T4 不写任何仓库文件**（零改动核实：changedFiles 为空，`git diff` 对 `doc/design/zh-CN/02-architecture.md` 为空）；T8 只写 `doc/design/zh-CN/01-requirements.md`（第 328 行）；T5 只写本轮验证报告（basename `65-followup-verification.md`）；T6 不写仓库文件（只在仓库外删临时目录）；T7 只写 `reports/54-docs-freeze-manifest.md` 与 `reports/62-i18n-delivery.md`；T1 只写本文件。
5. **零语义变化**：T3/T8 只做措辞/句读修订，判据、编号、表格、链接、数字、code-span、引用一律不动；T4 全程零改动。

---

## 2. T2 冻结：审计脚本口径

### 2.1 位置、调用形态、退出码、输出格式

新增**独立**脚本（仓库 `scripts/` 目录下的 `i18n-audit.sh`；全文用 basename 指代，完整调用形态见下）；不修改、不调用 checker。

```text
bash scripts/i18n-audit.sh                    # 全量审计
bash scripts/i18n-audit.sh --only <path>      # 只校验给定文件自身的义务（可重复，也可一次跟多个路径）
bash scripts/i18n-audit.sh -h | --help       # 打印头部注释并退出 0
```

* 脚本自解析仓库根（等价于 `REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd); cd "$REPO_ROOT"`），从任意 cwd 调用结果一致。
* **退出码语义（冻结）**：`0` = 无 FAIL；`1` = ≥1 FAIL；`2` = 调用或环境错误（未知参数、`--only` 目标不存在、缺少 `doc/design/zh-CN/GLOSSARY.md` 等）。**2 不得退化为 0**；WARN/NOTE 不影响退出码。
* **finding 格式（冻结）**：`file:line → FAIL[<Z-id>] <problem> → <suggested fix>`，一行一条。例：`doc/design/zh-CN/05-protocols.md:1 → FAIL[Z1] switcher label does not match the frozen 中文（默认） form → write the frozen switcher line from GLOSSARY §3.2`。
* **WARN / NOTE 格式**：`WARN file:line → <text>`、`NOTE file:line → <text>`，均不改变退出码。
* **摘要行（stdout 末尾）**：`i18n-audit.sh: PASS (<N> checks, <W> warnings)` 或 `i18n-audit.sh: FAIL (<K> failures, <W> warnings, <N> checks)`。
* **确定性**：同一棵树连续两次运行的 stdout 逐字节相同；finding 按 Z-id 序号、路径、行号排序输出。

### 2.2 检查项清单（Z1–Z9 —— 冻结判据）

| id | 检查项 | 判据（冻结） | 范围 |
|---|---|---|---|
| Z1 `PAIR` | 双向语言切换器 | 每对每侧：前 8 行内**恰有 1 行**切换器、独立成行（除切换器无其他内容）、**半角圆括号**、标签与链接文本逐字为冻结形态：中文侧标签为 `> **中文（默认）** · [English]` 后紧跟半角圆括号包裹的相对路径；英文侧标签以 `> [中文（默认）]` 开头，后接半角圆括号包裹的相对路径，再接 ` · English`。且链接目标经 `realpath -m` 规范化后**恰为对端**且存在，任一项不满足即 FAIL | 全部 7 对（见下） |
| Z2 `DISCLAIMER` | 逐字译文声明 | 6 个 Y-1 文件各在**前 8 行内恰有 1 行**逐字 `> 译文：若与英文原文冲突，以英文原文为准。`（GLOSSARY §4 Y-2/Y-3，不得改写任何标点）；**且**全仓 `*.md` 在**非 fenced 代码块**中该整行恰好出现 6 次、分布在上述 6 个文件（`GLOSSARY.md` §4 的模板在 fenced 块内，不计入） | 6 个 Y-1 文件 + 全仓唯一性 |
| Z3 `TERMS-TABLE` | §6 术语表结构 | `GLOSSARY.md` §6 表恰 **74 行数据行**，行号列 `1..74` 连续，每行恰 **4 个非空单元格**（`#`、英文原词、中文定译、首现说明与边界） | `GLOSSARY.md` |
| Z4 `TERMS-STATUS` | §5.5 状态词邻接 | 对 6 个冻结状态词（`已实现（implemented）`、`部分实现（partially implemented）`、`已知限制（known limitation）`、`未验证（unverified）`、`已否决（rejected）`、`已证伪（disproven）`）扫描 zh-CN 树：英文原词出现在**全角圆括号**内时，其左侧紧邻字符序列必须恰为对应中文定译；**0 偏离**，偏离即 FAIL 并给出 文件:行。命中总数作为 NOTE 输出（冻结基线 44） | `doc/design/zh-CN/**` |
| Z5 `TERMS-DENY` | §6.1 禁用译法（**窄口径**） | (a) 6 个中文页中精确串 `座位`、`会议室` 出现 0 次（非 `NEGATIVE EXAMPLE` 行），命中即 FAIL；(b) §6 表中「首现说明」含「状态词」的行，其**中文定译必须取自** §5.5 的 6 个冻结形态，否则 FAIL | 6 个中文页 + `GLOSSARY.md` |
| Z6 `CITATIONS` | 中英引用集合对等 | 5 对正文页：从**行内 code-span** 抽取 `path:LINE` / `path:L1-L2` 形式且路径以源码类扩展名结尾的引用；按「先相对引用所在文件目录、失败再回退仓库根」规范化成 `<仓库相对路径>:<行式>`；两侧**集合（去重）必须相等**（GLOSSARY §5.4）；且每条引用目标必须存在、行号（或范围端点）在界内。重复数作 NOTE | 5 对正文页 |
| Z7 `CODESPAN` | code-span 多重集对等 | 5 对正文页：行内 code-span（反引号对内容）排序后的列表**逐项相等**（多重集，重复计数敏感） | 5 对正文页 |
| Z8 `STRUCTURE` | 结构计数对等 | 5 对正文页：h2 数、h3 数、表格数据行数（`|` 开头且非分隔行、非 fenced 内）、fenced 代码块数，四项分别相等 | 5 对正文页 |
| Z9 `LAYOUT` | 布局约束 | (a) 语言后缀副本必须不存在：根目录的 `README.zh-CN.md` 与 `doc/design/` 下 `zh-CN/` 目录中的 `README.md`（存在即 FAIL，SPEC R12/§7.6、GLOSSARY §2.1 L4）；(b) `doc/design/**` 与两份根 README 中旧式切换器残留 `语言 / Language:` 出现 0 次 | 文档集（不含 `reports/**`） |

> **Z4 反例（不得出现）**：`已被否决（rejected）` 不满足 Z4——`被` 插在 `已` 与 `否决` 之间，连续定译串 `已否决（rejected）` 被打断（GLOSSARY §5.5 要求「中文定译（英文原词）」紧邻，供机器对照）。同理 `已被上游源码证伪（disproven）` 也不内含连续 `已证伪（disproven）`。C2 的冻结形态因此采用 **D**（`依据上游源码，…已证伪（disproven）`，见 §4.1），不引入会打断连续串的 `被…`；02:194 保持 HEAD 原文（其 `已否决（rejected）` 本来就连续，见 §4.2）。

**7 对配对（Z1 范围，穷举）**：`README.md`↔`README.en.md`；`doc/design/README.md`↔`doc/design/README.en.md`；`doc/design/zh-CN/NN-*.md`↔`doc/design/NN-*.md`（NN = 01–05）。**5 对正文页**（Z6/Z7/Z8 范围）= 上表后 5 对。`doc/design/zh-CN/GLOSSARY.md`、`doc/design/zh-CN/SPEC-guide.md` 不参与双向校验（§3.4 S4），但 SPEC-guide 仍受 Z2 约束。

**两条刻意收窄的边界（不得被当作缺陷）**：

* Z6/Z7/Z8 只覆盖 5 对正文页，与 [reports/61](61-i18n-verification.md) §3 A3/A5 的既有证据同口径。两份 README/索引对的 code-span 与引用计数作为 NOTE 输出但不判 FAIL——实测 `doc/design/README.md` 与对端表格数据行数本就相差 1（13 vs 12），这是索引页的既有差异，不是本轮缺陷。
* Z5 刻意不含 `主机`：语料中 `主机` 合法用于「机器/HOST 平台」义（如「主机 A/B」「主机侧」「同一台主机」），而 §6.1 的禁令针对**对端角色** `host`（定译 `发起方`）；denylist 无法区分两义，故不纳入。歧义词（`host`/`generation`/`relay`/`fallback`/`freeze`/`seat`）的一致性与术语覆盖度仍由门禁与人工作为判据，审计脚本不宣称覆盖。

### 2.3 `--only` 语义（与门禁 SPEC A5 同口径）

* `--only <path>`（文件或目录，可重复）：只对给定文件的**自身义务**判 FAIL；该文件参与的配对，等式类检查（Z6/Z7/Z8）仍**只读**读取对端内容，但失败归因于给定文件；对端的格式义务（对端自己的切换器行等）只有在它也被列出时才断言。
* `--only` 单列 `GLOSSARY.md`：只跑 Z3/Z4（Z1 豁免、Z2 豁免）。
* `--only` 单列 `SPEC-guide.md`：Z1 豁免，跑 Z2 与 Z5。
* `--only` 目标不存在 → 退出码 `2`，stderr 打印 `i18n-audit.sh: --only target does not exist: <path>`。
* 无 `--only`：全量 = 7 对相关文件 + `doc/design/zh-CN/**` 全部 `.md` + 根 `README.md`/`README.en.md`。

### 2.4 探针矩阵（正反例；T2 在任务 output 给出探针脚本与原始输出，T5 独立重做 ≥6 项）

| 探针 | 篡改 | 必须出现的失败 |
|---|---|---|
| P0 | 对照组：未篡改副本 | `i18n-audit.sh: PASS`，退出码 0 |
| P1 | 中文默认页切换器标签改为其它措辞（或整行删除） | `FAIL[Z1]`，指向该中文页第 1 行 |
| P2 | 英文页反向链接（`> [中文（默认）]` 加半角圆括号路径）删除或指向错误文件 | `FAIL[Z1]`，指向该英文页 |
| P3 | 某个 Y-1 文件的逐字声明改写（如「英文原文」→「英文版」）或删除 | `FAIL[Z2]`，指向该文件 |
| P4 | 把某个中文页的一处 `席位` 改为 `座位` | `FAIL[Z5]`，指向该文件:行 |
| P5 | 把一处 `已证伪（disproven）` 的全角括号改成半角 `已证伪(disproven)` | `FAIL[Z4]`（及/或 Z7 左侧不符），指向该文件:行 |
| P6 | 删除一处 `path:LINE` 引用，或把行号改到越界 | `FAIL[Z6]`，指向该文件:行 |
| P7 | 删除一个行内 code-span / 删除一个 h2 标题 | `FAIL[Z7]` 或 `FAIL[Z8]`，指向该文件 |

* 探针必须在**仓库之外**的临时副本上运行（副本由 `git clone` 或 `cp -a` 生成于工作区临时根，见 §7），**不得**改动仓库工作树；每条篡改都必须导致退出码 1。
* 探针副本及其原始输出属于本轮临时产物，纳入 §7 清理范围。

### 2.5 不得放宽判据

* 审计脚本的任一 Z 项不得「发现即跳过」、不得把 FAIL 降级为 WARN 以求全绿；不得把 §2.2 的判据改写为更弱的形式（例如把 Z7 多重集降为集合、把 Z6 集合降为「数量相等」）。
* T5 若发现脚本对某项实际上不判 FAIL（探针不触发），按 finding 报 `needs_revision`，不得替脚本改判据。

### 2.6 冻结基线（T1 用一次性校验器现场实测；用于证明判据可满足，也是 T2 的预期值）

* Z1：7 对全部满足（切换器均在第 1 行、半角、目标可达且为对端）。
* Z2：非 fenced 精确声明整行 = **6 行**，恰好分布在 6 个 Y-1 文件（各 1）。
* Z3：§6 = **74 行 × 4 列**，`#` = 1…74。
* Z4：状态词命中 **44**（implemented 27 / known limitation 6 / partially implemented 3 / disproven 3 / rejected 3 / unverified 2），**偏离 0**（与 [reports/61](61-i18n-verification.md) §3 A3 的「44/44 合规」一致）。
* Z5：`座位`/`会议室` 0 命中；§6 中「首现说明」含「状态词」的行 = 2 行，定译均取自 §5.5。
* Z6：5 对集合相等，引用全部可解析、行号在界（zh 侧实测 73 / 40 / 213 / 206 / 182 条，含重复）。
* Z7：code-span 多重集相等，实测 321 / 155 / 289 / 413 / 314（与 [reports/61](61-i18n-verification.md) §3 A3 表逐格一致）。
* Z8：结构计数相等，实测 01 `7h2·16h3·52行·0块`、02 `10h2·4h3·40行·1块`、03 `9h2·0h3·40行·3块`、04 `9h2·0h3·70行·1块`、05 `9h2·0h3·70行·0块`。**口径提示**：「表格数据行数」按 §2.2 定义（`|` 开头、排除分隔行、排除 fenced 内）计数，与 [reports/61](61-i18n-verification.md) §3 A3 的表格行计数口径不同，数值不可直接比对——Z8 只要求同一口径下 EN == ZH。
* Z9：禁建副本不存在；旧式残留 0（`reports/**` 内 1 处属历史叙述，按 §2.2 范围不计）。

---

## 3. T3 冻结：C1（SPEC v1.7.1，角色式措辞）

### 3.1 精确改动（恰 3 处）

1. **版本行（第 3 行）**：只把 `v1.7.0` 改为 `v1.7.1`；该行其余字符（`**frozen**`、owner、`Task id: t8 (attempt 1)`、日期）**逐字节不变**，行数不变。
2. **§9 第 741 行**（逐字）：
   * 改前：`The independent verification task (t7, owner `verifier`) re-runs the full `doc-verify.sh` plus manual`
   * 改后：`The independent verification task (owner `verifier`) re-runs the full `doc-verify.sh` plus manual`
   * 即删除 `t7, ` 四个字符（`t`、`7`、`,`、空格），保留 `(owner `verifier`)`；第 742 行（`sampling; its verdict is …`）不变。改后 §9 内**不得再出现任何轮次编号**（正则 `t[0-9]+` 在 §9 段落内 0 命中）。
3. **Changelog 追加一行**（表尾、1.7.0 行之后），逐字为：
   `| 1.7.1 | 2026-09-19 | **Editorial revision:** §9's verification-duty sentence is rewritten in role-based form (owner `verifier`, no round number). No rule changes meaning. |`
   * 日期单元格取实际执行日的 ISO 形式（2026-09-19 执行时即上述串）；该行**不含轮次编号**；除该行外 changelog 不得改动。

结果：`doc/design/SPEC.md` 由 **757 行 → 758 行**；除上述 3 处外**零改动**。

### 3.2 零改动证明（T3 output 必须给出）

* `git diff --unified=0 -- doc/design/SPEC.md` 恰 **3 个 hunk**（第 3 行、第 741 行、表尾新增行），无第 4 个 hunk；
* `git diff --numstat -- doc/design/SPEC.md` 预期 **additions = 3 / deletions = 2**（两处改行 + 一行新增）；
* 给出新 sha256 / 字节数 / 行数，以及改动行的「改前 → 改后」逐字对照。

### 3.3 门禁

* `bash scripts/doc-verify.sh --only doc/design/SPEC.md` → **failures 0 / RAW EXIT=0**；
* `bash scripts/doc-verify.sh` → **failures 0 / RAW EXIT=0**（摘要行原样记录，check 数与 warning 数以实测为准）。

---

## 4. C2 冻结：唯一实现单 t8（01:328 → 形态 D）+ t4 零改动核实

最终处置见 §0.1：**02 不得改写**，C2 的实际修复由 **t8** 在 `doc/design/zh-CN/01-requirements.md:328` 落地；t4 以零改动核实收口。

### 4.1 唯一落点（t8）：`doc/design/zh-CN/01-requirements.md` 第 328 行

* 改前（现场实测，即 HEAD 原文，整行）：
  `| D-4 | 历史文本「旋转已烘焙进 I420」被上游源码已证伪（disproven） | 对采集路径的错误心智模型 |`
* 改后（**逐字冻结，唯一形态**，captain 2026-09-19 裁定的候选编号 **D**）：
  `| D-4 | 依据上游源码，历史文本「旋转已烘焙进 I420」已证伪（disproven） | 对采集路径的错误心智模型 |`
* 改动说明：把施事前提化——由「历史文本…被上游源码已证伪」改为「依据上游源码，历史文本…已证伪」，去掉生硬的「已」错位；`，` 为全角（GLOSSARY §5.3，中文行文不得用半角逗号）；状态词保持**连续定译串** `已证伪（disproven）`（§5.5）；表格仍为 3 列、行序不变；英文对端 `doc/design/01-requirements.md:325` 语义不变（`is disproven by upstream sources`）。
* **禁止形态**：不得写成「…已被上游源码证伪（disproven）」或「…被上游源码已证伪（disproven）」——只要 `被上游源码` 插在 `已` 与 `证伪` 之间，连续定译串 `已证伪（disproven）` 就被打断。
* **对在途形态的更正（执行顺序）**：01:328 可能已按本文件**中程版本**的「…，上游源码已证伪（disproven）」落地过一次（工作树实测即为该形态）；captain 已裁定改采 **D**，故 T8 需**再改一次**该行。判定以**相对 HEAD 的净差异**为准：`git diff --unified=0 -- doc/design/zh-CN/01-requirements.md` 必须恰 **1 个 hunk**（第 328 行）；`git diff --numstat` = `1 1`；文件仍 **333 行**。

### 4.2 零改动单（t4）：`doc/design/zh-CN/02-architecture.md`

* 依据 captain 最终处置：t4 的 inScope 在批准后无法改写、且 t4 是 t5 的形式依赖，故 t4 以**零改动核实**收口——**不产生任何改动**。中程形态 A（`…中的已否决（rejected）旋转烘焙实验。`）**作废**。
* 交付内容（changedFiles 为空）：
  1. 核实 02 篇**无需改动**：第 194 行 `  doc/design/08-issues-and-solutions.md 中已否决（rejected）的旋转烘焙实验。` 已满足 GLOSSARY §5.5 连续定译串（`已否决（rejected）` 完整）与全角标点；
  2. 证明零改动：`git diff --quiet -- doc/design/zh-CN/02-architecture.md` → 退出码 `0`（`git diff --numstat` 输出为空），文件 sha256 与 HEAD 版本一致；
  3. 门禁留证：`bash scripts/doc-verify.sh --only doc/design/zh-CN/02-architecture.md` 与全量 `bash scripts/doc-verify.sh` 均 **failures 0 / RAW EXIT=0**；
  4. **若工作树里 02 已被中程形态 A 改写，必须先还原**：`git checkout -- doc/design/zh-CN/02-architecture.md`，再执行上述核实。
* **不做的事**：不采用形态 A/B/C（均作废）；不写 `02-architecture.md`；不把该项当缺陷——02:194 的句读风格记入 t7 的已知美容项（§0.1）。

### 4.3 不变量与门禁（T8 在 output 证明；T4 证明「零改动」）

* T8：`git diff --unified=0 -- doc/design/zh-CN/01-requirements.md` 恰 **1 个 hunk**（第 328 行），additions = 1 / deletions = 1；文件仍 **333 行**、表格 3 列、行序不变；该行无 code-span、无 `path:LINE` 引用，故 code-span 多重集与引用集合不变；输出改前/改后原文 + 新 sha256 / 字节数 / 行数；`bash scripts/doc-verify.sh --only doc/design/zh-CN/01-requirements.md` 与全量 `bash scripts/doc-verify.sh` 均 **failures 0 / RAW EXIT=0**；
* T4：对 `doc/design/zh-CN/02-architecture.md` 的 `git diff` 为空、changedFiles 为空；`--only doc/design/zh-CN/02-architecture.md` 与全量两条门禁命令均 **failures 0 / RAW EXIT=0**。

### 4.4 「必须含连续定译串」的判定（两处目标行共用，可机器复算）

两处目标行的硬约束是同一条：**中文定译与英文原词必须紧邻成串**（GLOSSARY §5.5「状态词写作『中文定译（英文原词）』」），且括号必须是**全角**。判定规则（对 A、D 逐字执行）：

1. 行内必须含连续子串 `已否决（rejected）`（A）／`已证伪（disproven）`（D）——含即为「连续」，全角括号；
2. 行内**不得**出现半角形态 `已否决(rejected)`／`已证伪(disproven)`；
3. 行内英文原词出现在全角圆括号内时，其左侧**紧邻字符序列**必须恰为对应中文定译（即审计脚本 Z4 判据，§2.2）；`…已被否决（rejected）`、`…被上游源码已证伪（disproven）` 这类「被打断」形态一律判不合格；
4. 行内不含 code-span、不含 `path:LINE` 引用；表格列数与行序不变。

参考实现（Node；T8 的冻结行 D 与 02:194 现状均返回 `true`，被打断形态返回 `false`）：

```js
const STATUS = { 已否决: 'rejected', 已证伪: 'disproven' };
function frozenOK(line, zh) {
  const en = STATUS[zh], unit = `${zh}（${en}）`;                 // 连续定译串（全角括号）
  if (!line.includes(unit)) return false;                        // 规则 1
  if (line.includes(`${zh}(${en})`)) return false;               // 规则 2：半角形态
  for (const m of line.matchAll(new RegExp(`（${en}）`, 'g')))    // 规则 3：邻接
    if (!line.slice(0, m.index).endsWith(zh)) return false;
  return true;
}
frozenOK('| D-4 | 依据上游源码，历史文本「旋转已烘焙进 I420」已证伪（disproven） | … |', '已证伪');      // true（t8 冻结行 D）
frozenOK('  doc/design/08-issues-and-solutions.md 中已否决（rejected）的旋转烘焙实验。', '已否决');        // true（02:194 现状，t4 核实用）
frozenOK('  doc/design/08-issues-and-solutions.md 中已被否决（rejected）的旋转烘焙实验。', '已否决');      // false（被 打断）
frozenOK('| D-4 | 历史文本「旋转已烘焙进 I420」已被上游源码证伪（disproven） | … |', '已证伪');            // false（被上游源码 打断）
```

### 4.5 无等价替代形态

T8 逐字采用冻结形态 **D**；02 篇**零改动**（§4.2）。任何更自然或不同的等义写法都须先上报 captain 并更新本文件，不得自行偏离；执行者也不得代改其他单的文件。

---

## 5. T5 冻结：C3（裸克隆门禁实测口径）

### 5.1 命令（工作区临时根 = `/data/dsh/home/workspace/tmp`，仓库 = `/data/dsh/home/workspace/code/webrtc-demo`）

```bash
REPO=/data/dsh/home/workspace/code/webrtc-demo
CLONE=/data/dsh/home/workspace/tmp/c3-bare-clone
rm -rf "$CLONE"
git clone --depth 1 --no-recurse-submodules "$REPO" "$CLONE"
git -C "$REPO"  rev-parse HEAD          # 记录主仓 HEAD
git -C "$REPO"  status --porcelain      # 记录本轮未提交改动（预期非空或已记录为空）
git -C "$CLONE" rev-parse HEAD          # 必须 == 主仓 HEAD
test -e "$CLONE/scripts/i18n-audit.sh"; echo "audit-in-clone=$?"   # 预期 1（不存在）
cd "$CLONE" && bash scripts/doc-verify.sh > /data/dsh/home/workspace/tmp/c3-gate.out 2>&1; echo "RAW EXIT=$?"
```

### 5.2 必须记录的内容（T5 output 与报告）

* 主仓与克隆的 `rev-parse HEAD`、主仓 `git status --porcelain` 原文、克隆内是否存在本轮新增的审计脚本（basename `i18n-audit.sh`）；
* 克隆内门禁的**摘要行原文**与 RAW EXIT；逐条失败清单（`file:line → problem` 原文）+ **总数**；
* 逐条归类为四类之一并给出分类计数：① 工作区证据缺失（P2，`missing workspace path`，含 `tmp/**` 设备日志与 `env*.sh`）；② submodule 内部引用（引用落在 submodule 路径，克隆无内容）；③ `env*.sh` 变体（工作区根回退后重复失败）；④ 其他（逐条说明）；
* 对照：**作者工作区内**同一命令的摘要行与退出码；
* 结论：门禁是**工作区门禁**；该结论**取代** [reports/62](62-i18n-delivery.md) §10 对 [reports/60](60-github-push-runbook.md) §7 旧实测（对象为 `8ac1a14d…`）的引用，并将 reports/62 §13 的 `unverified` 项改为实测值。
* **captain 已确认的边界（如实记录，不得含糊）**：本轮**不提交**，故实测绑定当前 HEAD `1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae`，且克隆**不含本轮改动**（新增审计脚本、SPEC v1.7.1、01:328 行改动、以及任何在途工作树改动）。报告须逐项写明「克隆内不存在」的实测结果（如 `test -e` 退出码）；推送后若需在最终提交上重测，由 captain 在交付总结里说明为**可选后续**，本轮不以该重测为验收前提。
* 所有数值现场实测；**不得沿用** 33/4/2466 等旧提交的转述值。

---

## 6. T5/T7 冻结：C4（联合指纹规范配方）

### 6.1 定义

联合指纹 = `sha256( 排序后的「sha256(file)␠␠file」行序列 )`，输入是**固定 19 个路径**：checker + 17 个 i18n 工件 + 本轮新增审计脚本。**不含 `reports/`**（报告自身边写边变，纳入即不可复现）。

### 6.2 规范配方（唯一命令；输入清单与排序均固定）

```bash
cd /data/dsh/home/workspace/code/webrtc-demo
cat <<'EOF' | LC_ALL=C sort | xargs sha256sum | LC_ALL=C sort | sha256sum
scripts/doc-verify.sh
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
doc/design/zh-CN/GLOSSARY.md
doc/design/zh-CN/SPEC-guide.md
doc/design/zh-CN/01-requirements.md
doc/design/zh-CN/02-architecture.md
doc/design/zh-CN/03-app-architecture.md
doc/design/zh-CN/04-signaling-service.md
doc/design/zh-CN/05-protocols.md
scripts/i18n-audit.sh
EOF
```

### 6.3 配方性质与取值纪律

* 输入先 `LC_ALL=C sort`、输出再 `LC_ALL=C sort`，故与调用者的参数/glob 顺序无关：**T1 现场自检**——把运行时实际存在的 18 个路径（T2 落地前，不含审计脚本）连跑两次、并把输入打乱后重跑，三个摘要完全相同 = `1bf12697c411ba292c62234d5f6fb826eebe98ed8423277668794382b0a5d901`。**这是配方机制自检值，不是 C4 终值**（它既不含审计脚本，也不含 T3/T4 的改动）。
* C4 终值只在 **T2/T3/T4 全部落地后的冻结状态**上测量：由 T5 实测、T7 复核；任何早于该状态的值都不作为 C4 值。
* 报告必须同时给出**19 行 `sha256␠␠path` 明细**（可逐行核对）与聚合值；并在「全量门禁 + 全量审计」跑前、跑后各测一次，两次必须相等。
* 配方可用性要求：命令必须是自包含的（输入清单写在命令内），不得依赖先前生成的临时文件。

### 6.4 旧值处置（不篡改历史）

* `3bb8914931742191aab5e7f9e49d3b4f`（[reports/63](63-i18n-verification.md) 记录的 verifier 旧值，32 位 md5）与 `fff4597c64850695dd6f47db711f04a8`（[reports/62](62-i18n-delivery.md) §6 记录的另一值）均为 **legacy 不可复现值**：其生成命令、输入清单与哈希算法（md5）都未固定，§6.2 的配方**不能**复现它们，也不得据此宣称一致。
* 处置：在 reports/62（及 reports/65）中就地标注「不可复现，已被 §6.2 规范配方取代」，**保留历史记录原文**，不删除、不重写。

---

## 7. T6 冻结：C5（清理范围）

* **目标**：本轮（i18n 审计/验证轮次）留在**工作区临时根**（仓库之外）的临时产物：已知一项为临时根下的 `i18n-checker` 目录（本文件写作时实测 **679 MB / 19 565 个文件**，含模拟工作区 `ws/` 595 MB、`simtest/` 85 MB、checker 旧版副本与探针输出），另加 T2/T5 本轮新建的探针副本与裸克隆目录，以及 **T1 自己产生的临时文件**：`t1-validate.mjs`（Z1–Z9 判据校验器）、`c4-list.txt`（C4 19 路径清单）、`check-A.mjs`、`check-AD.mjs`（冻结行判定自检）。
* **硬约束**：**不得删除仓库内任何文件**（`doc/**`、`reports/**`、`scripts/**`、代码一律不动）；**不得删除**仓库外既有的 P2 证据（设备日志 `tmp/n1…n7/**`、环境脚本 `env*.sh` 等——门禁按 P2 硬校验其存在）。
* **可删判据**：路径位于工作区临时根下，且属于本轮 i18n 产物（目录名以 `i18n-` 为前缀，或为本轮新建的 `c3-bare-clone`、探针/克隆目录）。**无法确认是否本轮产物的一律保留**。
* **清理清单**（T6 output，逐条）：路径 / 大小 / 文件数 / 是否可重建 / 删除依据；保留项写明理由。
* **清理前后**各跑一次 `bash scripts/doc-verify.sh` 与 `bash scripts/i18n-audit.sh`，两者都必须 **0 FAIL / RAW EXIT=0**。
* 报告书写约束（同时适用于 reports/65 与 reports/62 本轮更新）：正文与行内 code-span 中不得写入会被门禁 P2 硬校验的临时路径 token（形如以 `tmp/` 开头、无通配符的相对路径）；需要指代时用目录 basename（如 `i18n-checker`）或写在 fenced 代码块内。

---

## 8. 各任务验收标准与命令汇总

| 任务 | 命令 | 期望 |
|---|---|---|
| T2 | `bash scripts/i18n-audit.sh` | `PASS`，退出码 0 |
| T2 | `bash scripts/doc-verify.sh` | failures 0 / RAW EXIT 0 |
| T3 | `bash scripts/doc-verify.sh --only doc/design/SPEC.md` | failures 0 / RAW EXIT 0 |
| T3 | `bash scripts/doc-verify.sh` | failures 0 / RAW EXIT 0 |
| T4 | `git diff --quiet -- doc/design/zh-CN/02-architecture.md`（须退出码 0） | 零改动（changedFiles 为空） |
| T4 | `bash scripts/doc-verify.sh --only doc/design/zh-CN/02-architecture.md` | failures 0 / RAW EXIT 0 |
| T4 | `bash scripts/doc-verify.sh` | failures 0 / RAW EXIT 0 |
| T8 | `bash scripts/doc-verify.sh --only doc/design/zh-CN/01-requirements.md` | failures 0 / RAW EXIT 0 |
| T8 | `bash scripts/doc-verify.sh` | failures 0 / RAW EXIT 0 |
| T5 | `bash scripts/i18n-audit.sh` + `bash scripts/doc-verify.sh` | 均 failures 0 / RAW EXIT 0；跑前跑后 19 行指纹一致 |
| T6 | 清理前后各一次 audit + gate | 均 failures 0 / RAW EXIT 0 |
| T7 | `bash scripts/doc-verify.sh --only reports/54-docs-freeze-manifest.md` 与 `--only reports/62-i18n-delivery.md` | failures 0 / RAW EXIT 0 |

**C2 两单的身份、T5 的 claim 时机与 T7 的登记范围（captain 最终裁定）：**

* **T4** = **零改动核实单**：inScope `doc/design/zh-CN/02-architecture.md`，但**不写任何文件**（changedFiles 为空）——核实 02 无需改动 + 跑门禁留证（§4.2）。t4 是 t5 的形式依赖，故保留在依赖链中。
* **T8** = C2 的**唯一实现单**：deps = t1，inScope `doc/design/zh-CN/01-requirements.md`，只把第 328 行改为冻结形态 **D**（§4.1）。
* **T5 的 claim 时机** = **T4 与 T8 都 completed**。T5 的形式依赖只列到 t4（t5 deps = t2, t3, t4）；captain 已显式要求按「t4 与 t8 都完成」排序，**该 captain 排序要求覆盖形式依赖**——在 T8 完成前不得派发/claim T5。T5 的验收范围相应包含：01:328 的冻结形态 D 独立复核，以及 02 篇**零改动**的核实（`git diff` 为空）。
* **T7 的登记范围（措辞已由 captain 更正）**：登记 **`zh-CN/01` 新摘要（t8，形态 D）**；**不登记「zh-CN/02 新摘要」**（t4 不产生摘要变化）。t7 还须如实记录：t4 的前提（「02 的 D-4 行」）是 captain 派单时的定位错误、t4 以零改动核实收口、C2 真正落点是 t8；以及 02:194 句读风格作为**已知美容项**保留（§0.1）。

---

## 9. 未决与上报项（供 captain 处置）

1. **C2 最终处置已冻结**（§0.1、§4）：**唯一落点 = `doc/design/zh-CN/01-requirements.md:328`（t8，形态 D）**；**02 篇零改动**（t4 以零改动核实收口，changedFiles 为空）。中程形态 A 作废；若工作树里 02:194 已被改写，须 `git checkout` 还原到 HEAD。
2. **对派单示例的更正（captain 已采纳）**：`已被否决（rejected）` 与 `已被上游源码证伪（disproven）` **都不满足** GLOSSARY §5.5 的连续定译串要求（`被`／`被上游源码` 打断连续串），会被审计脚本 Z4 判 FAIL；判定规则与参考实现见 §4.4。
3. **T8 需按 D 再改一次**：01:328 工作树当前是中程形态「…，上游源码已证伪（disproven）」，captain 已改采 D（§4.1）；净差异仍须是相对 HEAD 的 1 hunk / `numstat = 1 1` / 333 行。
4. **T4 台账文本需更正**：t4 现 objective/acceptance 仍要求「改写 02 的 D-4 行」并引用状态词 `已证伪（disproven）`；按最终处置应改为「**零改动核实**：02 无需改动、changedFiles 为空、跑门禁留证」（§4.2、§8），否则 t4 无法按其契约自证。
5. **t8 台账文本**：其「不得改写 02 篇」与最终处置一致，可保留；对偶关系（t4 = 零改动核实单、t8 = 唯一实现单）已在 §8 写明。
6. **T7 验收措辞已由 captain 更正**：登记「**zh-CN/01 新摘要**（t8，形态 D）」，**删除**「zh-CN/02 新摘要」（t4 不产生摘要变化）；并如实记录 t4 前提的派单定位错误与 02:194 的美容项保留（§0.1、§8）。
7. **T5 的 claim 时机**：T4 与 T8 都 completed 后才可 claim（captain 排序要求覆盖形式依赖），见 §8。
8. **C3 的提交边界**：本轮无提交授权，裸克隆实测绑定 HEAD `1b5bb78d…` 并如实记录「不含本轮改动」；推送后如需在最终提交上重测，由 captain 在交付总结里说明为可选后续（§0.2、§5.2）。
9. **旧联合指纹不可复现**：按 §6.4 处置（标注取代、不删历史）。
10. **审计脚本与门禁的关系**：审计是补充证据；若 captain 希望审计脚本进入门禁调用链（例如 `doc-verify.sh` 末尾调用它），因 checker 已冻结，本轮**不做**，需另开任务并说明 checker 冻结件变更流程。
11. **执行核对点（2026-09-19 现场实测；供 t9 / t11 使用）**：
    * `doc/design/zh-CN/02-architecture.md` **已还原为 HEAD 版本**（219 行 / 15877 B / sha256 `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42`，第 194 行为原文「…中已否决（rejected）的…」，`git diff` 为空）——与 §0.1「02 零改动」一致 ✓。
    * `doc/design/zh-CN/01-requirements.md:328` 目前仍是**中程形态**「…，上游源码已证伪（disproven）」，**尚未**等于 §4.1 的冻结形态 **D**。在它被改为 D 之前：t9 对 C2 的复核必然 FAIL；t11 不得登记 01 的新摘要。**需 captain 重开/补派该行的一次纠正改动**（一行、净差异仍为相对 HEAD 的 1 hunk / `numstat = 1 1` / 333 行）。
    * t4 的终态记录（曾以中程形态 A 落地 02:194）与当前工作树**不符**（该改动已被还原），t11 须按「t4 的改动被还原、02 保持 HEAD」如实登记，不得据 t4 记录宣称 02 有变动。
