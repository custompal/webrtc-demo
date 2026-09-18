# 61 — 第三轮独立验证：i18n 中文文档集（task t11，第 2 次终结复核）

> Status: **pass**（绑定指纹见 §1，任何后续写入即失效）· Owner: `verifier` · Task: t11 (attempt 2), 2026-09-18
> Verdict: **pass**（A1–A7 全绿） + 1 条治理 finding（冻结窗口内文档集被写，非内容缺陷）
> 范围：只读验证；除本报告外未修改任何文件。绑定对象：checker `6c62591a…`（终版）+ 17 个文档工件 + SPEC v1.7.0。
> 路径写法说明：页面内相对链接目标一律用正文文字而非反引号；示意行格式一律用全角括号并就地标注（与 GLOSSARY §3.3 同一防自检机制）。

任务 id 谱系：t6（因依赖终态 `failed` 的 t4 永久不可 claim）→ **t11**。本报告为 t11 的**终结复核版**，取代我上一版（当时 verdict=`needs_revision`）：上一版的两类问题现均已消解 —— F1（checker 绑定）由 captain 以终版 `6c62591a…` 重新基准化；F2–F4（3 处 §5.5 状态词偏离）已在**当前文档集**中被修正（见 §4）。

---

## 0. 结论

| # | 验收项 | 结果 |
|---|---|---|
| A1 | 全量门禁在绑定 revision 下 0 failures / RAW EXIT=0，跑前跑后指纹一致 | **通过** — 跑前=跑后（checker + 17 工件联合指纹 `3bb89149…`），PASS (3912 checks, 2 warnings)，RAW EXIT=0 |
| A2 | ≥60 处中英 `path:LINE` 引用抽查，全部命中且行覆盖成立 | **通过** — 334 条去重，333/333 真实引用可达且行号在界内（英文页与中文页均覆盖） |
| A3 | GLOSSARY §6 术语表 74 条抽检 ≥20 条一致；未译 token 保持原样 | **通过** — 74 条逐条对照；状态词 44/44 合规、表内状态单元格 16/16 正确；code-span/引用集合与英文页完全相等 |
| A4 | V14 三组配对双向 100% + 两个禁建文件不存在 | **通过** — 7 对第 1 行独立成行、半角、双向可达且指向正确对端；禁建文件均不存在 |
| A5 | ≥20 处中英语义抽样与英文断言一致；§4 声明只出现在 6 个 Y-1 文件 | **通过** — 562 处零相反；声明恰在 6 个 Y-1 文件、其余 0 处 |
| A6 | SPEC v1.7.0 自洽（§7 注册 V14/V15、§7.6 读法、§6 E1 豁免、§9 按语言分治） | **通过** — 35/35 被引规则编号在册，四处置信点逐条命中 |
| A7 | 冻结三元组复核并登记实测值 | **通过** — §1.2/§8 登记 checker + 17 工件的 sha256/bytes/行数实测值 |

**总体裁定：`pass`。** 该裁决**绑定**于 §1.3 的联合指纹 `3bb8914931742191aab5e7f9e49d3b4f`；若 checker 或任一工件此后再次被写入，本裁决立即失效，须重跑。
补充：determinism 抽测 **10/10 全 PASS**（rc=0 / fails=0，见 §1.6）；checker 已物理只读（`-r--r--r--`/444），抽测跑前跑后联合指纹未变。

---

## 1. 绑定与指纹

### 1.1 任务/绑定状态

* 本轮验证任务 t11 为**终态** `failed`（attempt 2，verdict 曾为 `needs_revision`）；captain 随后以终版 checker 重新基准化并指示继续复核。**本报告的内容裁决 supersede 上一版**，但 t11 的**任务台账状态**需 captain 以 reassign（新 attempt）或新单才能在台账上反映本 `pass` —— 终态结果不可原地更新，我未试图改写它。
* **台账旧值 vs 权威绑定值（如实记录）**：t2 的台账已在 attempt 8 关闭，其 output 登记的三元组是**关闭时值** `64081ef5…` / 921 行 / 41543 B（终态旧值）；而**权威绑定值为 `6c62591a…` / 919 行 / 41673 B**（本报告 §1.2）。差异来源是冻结窗口内的一次收尾写入（谱系见 §1.2），内容更严格、**不属内容缺陷**；T12 与任何下游绑定一律以权威值为准。

### 1.2 checker（`scripts/doc-verify.sh`，终版，绑定）

| 项 | 实测值 |
|---|---|
| sha256 | `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` |
| 行数 | 919 |
| 字节 | 41673 |
| mtime | 2026-09-18 22:17:57 +0800 |

**revision 谱系（如实记录 churn，作为过程事件）：**
`676d075a…`（i18n 前基线，628 行）→ `89607884…` / `53f89372…` / `2b47cca2…` / `f43b1a10…` / `3b0f18bd…` / `172c5018…` / `633d83ac…` / `64081ef5…`（921 行 / 41543 B / mtime 2026-09-18 12:30:46，曾为 captain 公告的冻结值）→ **`6c62591a…`（终版，919 行 / 41673 B / mtime 2026-09-18 22:17:57）**。

终版在**冻结公告之后**落盘，属冻结窗口内的收尾写入。经我方独立复核，其内容**更严格、非内容缺陷**，且不改变 A1–A7 任何判据：
* `V16` 命中 **0**、`captain-appended` 命中 **0**、`duplicate entry page drift` 命中 **0** —— 未注册的 FAIL 能力确已移除；
* 重复页诊断降级为**纯 NOTE 永不失败**：脚本第 47 行注释 `advisory only, never a failure`、第 55 行起说明，实现在第 136 行使用 `note`（非 `fail`），且运行时**触发 0 次**（两个禁建文件均不存在）；
* 头部 normative source 更正为 `doc/design/zh-CN/GLOSSARY.md` **v1.1.1 §3 and §4 (Y-1..Y-4)**（脚本第 26–31 行），并明示早先 v1.0.0 措辞已作废、不得重新实现；
* 行为常量与 SPEC §7.6 一致：`SWITCHER_WINDOW=8`、`DISCLAIMER='译文：若与英文原文冲突，以英文原文为准。'`、三组 `pair_add` 配对、Y-1 清单（zh-CN 下 01–05 与 SPEC-guide.md）。
* **物理锁定（captain 于本轮施加）**：实测 `stat` = `-r--r--r--`（八进制 444），sha256 仍为 `6c62591a…`；`git diff --summary` **无 mode 变更行**。此后任何再写入都需显式 `chmod +w`，故跑前跑后指纹若发生变化即为明确的流程 finding（本轮实测未发生，见 §1.6）。

### 1.3 稳定窗口（跑前/跑后）

* 联合指纹 = `sha256sum scripts/doc-verify.sh <17 个工件>` 的 sha256：
  * **跑前 22:23:58** = `3bb8914931742191aab5e7f9e49d3b4f`
  * **跑后 22:24:02** = `3bb8914931742191aab5e7f9e49d3b4f` —— **一致，无漂移**。
* 该次全量运行：`bash scripts/doc-verify.sh` → **PASS (3912 checks, 2 warnings)**，**RAW EXIT=0**，0 FAIL。

### 1.4 文档集：2 个工件与 captain 公告基准不符（冻结窗口内被写）

captain 公告的 17 工件清单中，**15 个逐字节一致**，但下列 2 个在**冻结公告之后**被写入：

| 工件 | captain 公告值 | 现场实测值 | mtime（写入时刻） |
|---|---|---|---|
| `doc/design/zh-CN/01-requirements.md` | `41688ed3…` / 333 行 / 25754 B | `5af1985bfad01df02d834ef0c325fd01d9e60263791301d4688b6bff5dbb1e19` / 333 行 / 25754 B | **2026-09-18 22:23:38** |
| `doc/design/zh-CN/02-architecture.md` | `cfcea123…` / 219 行 / 15877 B | `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42` / 219 行 / 15877 B | **2026-09-18 22:23:40** |

* 两次写入发生在我**上一轮完成提交之后、本轮门禁运行之前**，即落在「checker 与文档集自此刻起不得有任何写入」的冻结窗口内（captain 已声明"已通知其余成员停笔"）。**非我所为**，我未修改任何工件。
* 变更内容为 §5.5 状态词的**同长度替换**（字节数与行数均未变，故与公告值的差异只在 sha256）：见 §4。该变更**修正了**我上一版报告的 F2–F4，使 A3 由 `needs_revision` 转为通过 —— 属内容改进，但**以破坏冻结窗口的方式落地**，故记为治理 finding（G1）。

### 1.5 HEAD 与工作树（含冻结提交）

* **HEAD = `543d94153f982fe93cb6f08bc886b7fdce48505b`**（`docs(i18n): 冻结扩展后的文档门禁脚本 (t2)`，author `dsh-captain`，date 2026-09-18 22:22:38，`scripts/doc-verify.sh` +307/−16）。其父提交为我此前记录的 `e34d5be3…`。
* 该提交的 blob 为 `a253c5c8c40a8f2d7cf5df003d19ff633222f81c`，与工作树 `git hash-object` **完全相同**、大小同为 41673 B ⇒ 终版 checker `6c62591a…` **已被提交固化**，因此本报告的绑定可经 git 持久找回（`git cat-file -p 543d941:scripts/doc-verify.sh`），不再是我上一版所述"未提交、不可找回"的状态。
* `git status --porcelain` 显示 `scripts/doc-verify.sh` **不再列为已修改**（内容已入库）；其余改动仍是文档集（`README.md`、`doc/design/*.md` 修改，`README.en.md`、`doc/design/README.en.md`、`doc/design/zh-CN/` 未跟踪），**无 `app/**`、`signaling/**` 源码改动**，故 A2 在 HEAD 源与工作树源上等价。

### 1.6 determinism 抽测（本轮独立实测）

* 在绑定 revision `6c62591a…`（只读 444）上**连跑 10 次全量** `bash scripts/doc-verify.sh`：**rc=0 / fails=0，10/10 全 PASS**，每次均为 `PASS (3912 checks, 2 warnings)`；跑前=跑后联合指纹 `3bb89149…` 未变。
* 历史瞬时失败（外部输入，非我方观测）：captain 侧记录 i18n-tooling 曾在约 12 次运行中观测到 **1 次** `doc/design/SPEC.md:239 → --log 缺同句证据`；当时另一名成员正在**并发改写 checker 本身**（该成员已被移除），"边写边执行脚本"足以解释该瞬时态；`doc/design/SPEC.md` 自 2026-09-17 21:53:01 起未改动（sha256 `a5724a40…`，本轮实测一致）。
* 我方判定：**10/10 PASS，未复现该瞬时失败**；按"并发写入条件下的瞬时态"记为**过程事件，不计缺陷**。同时如实声明残余不确定性：有限次抽样无法证明该瞬时态不存在；**若后续在任何只读冻结环境下复现任何失败**（含该行），我会按事实记为 finding 并给 `needs_revision`/`reject`，不会因本条说明而放宽判据。

---

## 2. 方法与独立工具

不依赖被验 checker 的自述，用独立脚本（node，UTF-8 安全）与纯 shell 重算：

1. V14 切换器：前 8 行内、独立成行、整行锚定，目标用 `realpath -m` 规范化后判定"可达"与"指向正确对端"。
2. V15 声明：`grep -nxF` 精确整行匹配。
3. 引用（A2）：path:LINE 抽取 + 存在性 + 行号界内，剔除 IPv6 字面量噪声。
4. 术语（A3）：§6 表逐行解析 + §5.5 状态词"中文前缀必须在（英文原词）之前"的精确判定 + 表内状态单元格按行序对齐复核。
5. 语义（A5）：五对页面 code-span 多重集、引用集合、标题层级、表格行数、代码块数逐一比对；表格按行序逐单元格比对数字多重集；正文按空行分块对齐后比对数字与 code-span 多重集。

---

## 3. 验收逐项证据

### A2 — 引用抽查（通过）

334 条去重 path:LINE，**333/333** 真实引用目标存在且行号（或范围端点）在界内；唯一"不通过"项 `::1` 是 IPv6 回环字面量而非引用。分页：01=69、02=39、03=98、04=113、05=88；GLOSSARY.md 与 SPEC-guide.md 为 0（辅助页无引用义务）。英文页侧同为 69/39/98/113/88，中英引用集合**相等**。

### A3 — 术语与未译 token（通过）

* §6 表 **74 行**逐条对照；状态词 §5.5 精确形式 **44/44 合规、0 偏离**；表内状态单元格 **16/16** 正确（EN 裸词 ↔ ZH"定译（英文原词）"）。
* 74 条中 56 条定译在中文正文逐字出现，15 条其英文原词在英文语料中不出现（无使用场景），其余按下述判定：
  * `P2P` → 中文保留 `P2P` 不译，合于 §6 row 20 边界「协议缩写不译」；
  * `verdict` → 英文 03 篇 "a fatal transport verdict" 译「致命传输判定」，属传输层判决义（非 §6 row 68 的评审取值义），一词多义；
  * `glare` → 英文正文仅以定语 anti-glare 出现（03 篇 212、381 行；05 篇 176 行本为 "simultaneous-renegotiation conflicts"），中文一致作「防冲突」；§6 row 34 的禁令针对名词性简写，此处无名词性用法。
* 未译 token 硬约束：五对页面 **code-span 多重集完全相等**，引用集合相等，标题/表格/代码块计数一致：

  | 页 | code-span EN/ZH | 引用 EN/ZH | 标题 EN/ZH | 表格行 EN/ZH | 代码块 EN/ZH |
  |---|---|---|---|---|---|
  | 01 | 321/321 | 69/69 | 7 h2·16 h3 | 58/58 | 0/0 |
  | 02 | 155/155 | 39/39 | 10 h2·4 h3 | 45/45 | 2/2 |
  | 03 | 289/289 | 98/98 | 9 h2·0 h3 | 44/44 | 6/6 |
  | 04 | 413/413 | 113/113 | 9 h2·0 h3 | 78/78 | 2/2 |
  | 05 | 314/314 | 88/88 | 9 h2·0 h3 | 77/77 | 0/0 |

### A4 — V14 双向切换（通过）

7 对配对（根 README ↔ README.en.md；doc/design/README.md ↔ doc/design/README.en.md；zh-CN 下 01–05 ↔ doc/design 下同名页）每对两侧各命中 **1** 行切换器，均在**第 1 行**、独立成行、半角真实括号，标签与链接文本精确。下表相对目标按定义只在该页目录下成立，故不写反引号：

| 中文页 | 切到英文页目标 | 可达 | 正确对端 | 英文页切到中文页目标 | 可达 | 正确 |
|---|---|---|---|---|---|---|
| `README.md` | README.en.md | 是 | 是 | README.md | 是 | 是 |
| `doc/design/README.md` | README.en.md | 是 | 是 | README.md | 是 | 是 |
| `doc/design/zh-CN/01-requirements.md` | ../01-requirements.md | 是 | 是 | zh-CN/01-requirements.md | 是 | 是 |
| `doc/design/zh-CN/02-architecture.md` | ../02-architecture.md | 是 | 是 | zh-CN/02-architecture.md | 是 | 是 |
| `doc/design/zh-CN/03-app-architecture.md` | ../03-app-architecture.md | 是 | 是 | zh-CN/03-app-architecture.md | 是 | 是 |
| `doc/design/zh-CN/04-signaling-service.md` | ../04-signaling-service.md | 是 | 是 | zh-CN/04-signaling-service.md | 是 | 是 |
| `doc/design/zh-CN/05-protocols.md` | ../05-protocols.md | 是 | 是 | zh-CN/05-protocols.md | 是 | 是 |

豁免侧：`doc/design/zh-CN/GLOSSARY.md` 与 `doc/design/zh-CN/SPEC-guide.md` 第 1 行指向 SPEC.md（不参与双向校验）；`doc/design/SPEC.md` 无切换器。旧口径 `语言 / Language:` 残留计数 = 0。
禁建文件：根 README.zh-CN.md、doc/design/zh-CN/README.md **均不存在**（`test -e` 失败）；SPEC §7.6 第 707–708 行与 GLOSSARY §2.1 第 43 行/L4 亦规定其不得存在。

### A5 — V15 声明与语义抽样（通过）

* 逐字声明 `> 译文：若与英文原文冲突，以英文原文为准。` 位于**第 2 行**（前 8 行内）：zh-CN 下 01–05 与 SPEC-guide.md 全部命中；`doc/design/zh-CN/GLOSSARY.md` **未命中**（§4 Y-4 豁免）。全仓计数：该串**只出现在这 6 个 Y-1 文件**。
* **语义抽样 562 处，零相反**：260 个正文分块（块数一致、数字与 code-span 多重集差异 0）+ 302 个表格行逐单元格（数字多重集差异 0）+ 16 处表内状态单元格。

### A6 — SPEC v1.7.0 自洽（通过）

* 身份：`a5724a409c34c001d3ff6304e42db5efb3ae481f950cd0ea803b213cbe5e65ab` / 757 行 / 67804 B，头部 frozen v1.7.0。
* §7 注册 **V14/V15**（第 440/441 行，失败文本 `missing language switcher` / `missing translation disclaimer` 逐字存在）；§7.6 第 688–708 行固定读法（配对清单穷举、两条行格式、免责清单与逐字文本、全角模板警告、No extra copies）；R12/R13/R14 第 66/72/75 行；**§6 E1** 第 407–411 行限定英文文档并豁免中文默认入口/索引与整个 zh-CN 树；**§9 第 3 条** 第 736–738 行按语言分治。
* 从不被绑 checker 抽取的 **35 个规则编号**（V/R/C/E/T/P/B/J/A 系列）在 SPEC 中 **35/35 存在**，无未登记引用；checker 注释亦声明 V1–V15 为注册范围、重复页诊断不带规则编号。
* 已知美容项（按 captain 裁定不改 SPEC 以保锚点）：§9 第 741 行仍写 "the independent verification task (t7, owner verifier)"，本轮编号为 t11；建议交付后改为角色式表述，记入 T12。

### A7 — 冻结三元组复核（通过）

checker 与 17 个工件的 sha256/bytes/行数实测值已在 §1.2 与 §8 全量登记；captain 公告值与本报告实测值的差异点仅 §1.4 的 2 个工件。

---

## 4. 上一版 findings 的消解情况

| id | 严重度 | 位置 | 状态 | 证据 |
|---|---|---|---|---|
| F1 | blocker | `scripts/doc-verify.sh` | **已消解** | captain 以终版 `6c62591a…` 重新基准化；跑前=跑后联合指纹一致 |
| F2 | low | `doc/design/zh-CN/01-requirements.md:112` | **已修正** | 现为「未验证（unverified）」，合 §5.5/§6 row 73 |
| F3 | low | `doc/design/zh-CN/01-requirements.md:328` | **已修正** | 现为「…被上游源码已证伪（disproven）」，含定译「已证伪（disproven）」 |
| F4 | low | `doc/design/zh-CN/02-architecture.md:194` | **已修正** | 现为「…中已否决（rejected）的旋转烘焙实验」，合 §5.5 |

---

## 5. 本轮 findings

| id | 严重度 | 位置 | 问题 | 必需的修复 |
|---|---|---|---|---|
| G1 | medium（治理，非内容缺陷） | `doc/design/zh-CN/01-requirements.md`、`doc/design/zh-CN/02-architecture.md` | **冻结窗口内文档集被写入**：两文件 mtime 为 2026-09-18 22:23:38 / 22:23:40，即落在 captain 声明"已通知其余成员停笔"的冻结窗口之内；导致 captain 公告的 17 工件基准中 2 项（`41688ed3…`、`cfcea123…`）与现场不符（现为 `5af1985b…`、`716552cd…`）。变更内容为 §5.5 状态词的同长度替换（修正了 F2–F4），内容更合规，但**破坏了冻结前提**。 | 由 captain 裁定谁在冻结窗口写入并重申冻结；把 §1.4 的两个新 sha256 作为终值登记进 T12 冻结清单（勿沿用公告旧值）。若认为窗口内写入不可接受，需重跑本轮验证。本报告裁决绑定 §1.3 联合指纹 `3bb89149…`，任何后续写入即自动失效。 |

**G1 的处置进展（事后核实）**：checker 那一段 churn（`64081ef5…` → `6c62591a…`）已由 captain 于 2026-09-18 22:22:38 以提交 `543d9415…` **固化入库**，并被 captain 施加物理只读（444），故 checker 侧绑定现已持久且不可静默漂移；**文档集那两次写入（22:23:38 / 22:23:40）仍未提交**，其终值即 §1.4 与 §8.1 登记的两个新 sha256。建议 T12 以该终值入册，并在其报告中记录本轮冻结窗口的两次写入（checker 一次、文档集一次）。

---

## 6. 观察（不计缺陷）

1. `doc/design/zh-CN/01-requirements.md:328` 修正后为「…被上游源码**已证伪**（disproven）」，机械合规（含 §5.5 定译），但「被……已证伪」语序略生硬；纯语言风格问题，规范未禁止，**不计缺陷**，供后续语言润色参考。
2. `640×360`（英文 U+00D7）在中文页作 `640x360`（半角 x）：该串非 code span，§5.2/§5.3 未规定该符号，语义中性，**不计缺陷**。
3. `anti-glare`→「防冲突」、`verdict`→「判定」属一词多义/定语用法，**不计缺陷**但留痕（见 §3 A3）。
4. 终版 checker 的重复页诊断永不失败（NOTE-only），与 SPEC §7 只注册 V1–V15 一致；本轮触发 0 次。

---

## 7. 已知缺口（按契约不计缺陷）

* `doc/design/06-flows.md` … `doc/design/11-coding-standards.md` 与 SPEC 正文本次不翻译：中文索引标注「待翻译（planned）」，SPEC §7.6 S5 与 GLOSSARY §2.3 入册，**不计缺陷**。
* GLOSSARY.md、SPEC-guide.md 无英文对应物：§3.4 S4 明确不参与双向校验（前者另豁免译文声明），**不计缺陷**。

---

## 8. 治理记录与复现

* **任务 id**：t6 → t11；t11 为终态 `failed`（attempt 2，曾 verdict=`needs_revision`），本报告为其**终结复核**，内容裁决为 `pass`；台账状态若需反映 `pass`，须由 captain reassign（新 attempt）或新单 —— 终态不可原地更新，我未试图改写。
* **账本不一致（供 T12）**：t14 的 output 登记 `doc/design/zh-CN/GLOSSARY.md` 为 **v1.2.0 / 349 行 / 27256 B / `e6180b34…`**，并引用 checker `53f89372…`；而磁盘与 captain 冻结基准均为 **v1.1.1 / 349 行 / 27419 B / `51e3eb34…`**。T12 冻结清单请以磁盘实测值为准。
* **本轮为只读验证**：除本报告外未创建或修改任何文件；未运行 Gradle；未 commit。
* 复现：

```bash
cd code/webrtc-demo
sha256sum scripts/doc-verify.sh && bash scripts/doc-verify.sh; echo "EXIT=$?"
```

### 8.1 17 个工件实测指纹（绑定值）

| 文件 | sha256 | bytes | lines |
|---|---|---|---|
| `README.md` | `38c7ada64330fee0700f20ab1e2aeadd7d94618085c7187d247c7d34224bbb2c` | 3358 | 46 |
| `README.en.md` | `72b7a3c45ff87d9ecb45e8579a83be10b3132ba63fa802ad8ef87970a54262b8` | 3235 | 61 |
| `doc/design/README.md` | `b652775bc04d244d0ac37e222f01bf8ddd60d8ffc02f33ce4a485d65eac474cb` | 2854 | 37 |
| `doc/design/README.en.md` | `44cda01a35f5c663d47844347282c55a823a5da8521765b7c4e4a1937dc7feeb` | 2429 | 43 |
| `doc/design/SPEC.md` | `a5724a409c34c001d3ff6304e42db5efb3ae481f950cd0ea803b213cbe5e65ab` | 67804 | 757 |
| `doc/design/01-requirements.md` | `55e9b8a52722e08a78e6843257523bf3954c469daa1aef26921ab079a80f8387` | 25977 | 331 |
| `doc/design/02-architecture.md` | `6a2fc862af61d0511115e47d50c2065f29d93912f4feacb88fa6847d567d1be8` | 16312 | 222 |
| `doc/design/03-app-architecture.md` | `097bbf2393b42df45973b866529d02d007411264494aa988949be41f3e493584` | 29449 | 387 |
| `doc/design/04-signaling-service.md` | `ca79073949c806d3bee82ab95768d88e87f3cdad0b109374acde950454c594a0` | 20155 | 279 |
| `doc/design/05-protocols.md` | `042b79cdf53e6232d338883535368fdf8029ae45dd74a2ee9f958615cfdb2e3f` | 22953 | 295 |
| `doc/design/zh-CN/01-requirements.md` | `5af1985bfad01df02d834ef0c325fd01d9e60263791301d4688b6bff5dbb1e19` | 25754 | 333 |
| `doc/design/zh-CN/02-architecture.md` | `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42` | 15877 | 219 |
| `doc/design/zh-CN/03-app-architecture.md` | `7b923659876b77c4409bbb854b24b760cceff6499488bef202c7fed80ebefb48` | 28403 | 367 |
| `doc/design/zh-CN/04-signaling-service.md` | `36d180bc03f1f2520168516d1488ef91307f49d6c4aa9c01fa0b7db8867f713a` | 19726 | 275 |
| `doc/design/zh-CN/05-protocols.md` | `4acd7b70d37ac121d4e7b0a40e630c491fb4dc76664049d89bb31f30195d0773` | 22037 | 288 |
| `doc/design/zh-CN/GLOSSARY.md` | `51e3eb34776d0f433f6d32cb945852697fe15a1f106857fa878e90f96e851007` | 27419 | 349 |
| `doc/design/zh-CN/SPEC-guide.md` | `dd8911469c31a71cb9e407e10ed2ca0018b422591affb68351ac4ba12be742c7` | 7895 | 108 |

**联合指纹（checker + 上表 17 项）**：`3bb8914931742191aab5e7f9e49d3b4f`（跑前 22:23:58 = 跑后 22:24:02）。
