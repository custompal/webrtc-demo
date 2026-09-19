# 62 — i18n 交付报告：中文文档切换能力（task t18）

> Status: **delivered** · Owner: `i18n-architect` · Task: t18（t12 的替代整合单）, 2026-09-18
> 冻结绑定：checker `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` + bearing commit `543d94153f982fe93cb6f08bc886b7fdce48505b` + 17 个 i18n 工件终值（见 §7）；后续收尾轮（2026-09-19）的绑定见 §14.1（19 路径联合指纹 `40d9fa70…`）
> 验证依据：[reports/61-i18n-verification.md](61-i18n-verification.md)（第三轮主报告，229 行 / 22 425 B / `1ed8cd80258768a5a44ca69e9f484e49dc404719b480236e4ec65f954a2e951a`，**承载完整 A1–A7 证据**，verdict = pass）与 [reports/63-i18n-verification.md](63-i18n-verification.md)（t17 **终审记录**，73 行 / 5 130 B / `d3b80c3a65c4312c76a7845d4891ae40e820a3e5d5f09bfc3856716205bf8aca`：verdict、绑定三元组、17 工件摘要、联合指纹、F1–F4 消解、G1 定性、determinism，并指向 `reports/61`；verdict = pass）
> 冻结清单增补：[reports/54-docs-freeze-manifest.md](54-docs-freeze-manifest.md) §8（i18n 轮增补）与 §9（后续收尾轮，2026-09-19）
> 后续收尾轮增补（task t7，2026-09-19）：见 §14 —— C1 SPEC v1.7.1、C2 已交付形态 D′、B3 `scripts/i18n-audit.sh`、C3 裸克隆实测、C4 规范配方与终值、C5 清理记录、治理记录（含 `reports/64` 修订链与 `CAPTAIN RULING`）
> 写作纪律：本文只写可核验事实；§1–§13 的数值为 2026-09-18 撰写时的现场实测，§14 的数值为 2026-09-19 撰写时的现场实测；不确定处显式标注 `unverified`。

---

## 0. 结论

中文文档切换能力已交付并通过第三轮独立验证（t11 → t16 修复 → t17 复验 `pass`）。冻结的三件事：

1. **规范**：`doc/design/zh-CN/GLOSSARY.md` **v1.1.1**（布局、切换器、译文声明、翻译规则、74 条术语表）是中文页的判据来源；`doc/design/SPEC.md` **v1.7.0** 是唯一英文规范（E1 中文豁免、§9 门槛按语言分治、§7 登记 V14/V15）。
2. **交付物**：中文默认入口 + 中文索引 + 核心 5 篇译文 + SPEC 中文导读 + 英文侧切换行，共 17 个 i18n 工件。
3. **门禁**：扩展后的 checker（`6c62591a…`，919 行，已入库 commit `543d9415…`，物理只读 0444）在作者工作区 **PASS (3912 checks, 2 warnings)，RAW EXIT=0，0 FAIL**。

---

## 1. 交付范围

| 类别 | 文件 | 语言 | 说明 |
|---|---|---|---|
| 中文默认入口 | [README.md](../README.md) | 中文 | GitHub 首页默认显示；第 1 行切换器指向 `README.en.md` |
| 英文原文入口 | [README.en.md](../README.en.md) | 英文 | 改写前英文 `README.md` 的逐字节原文 + 第 1 行切换器 |
| 中文默认索引 | [doc/design/README.md](../doc/design/README.md) | 中文 | 12 行导航：01–05 → `zh-CN/` 中文页，06–11 → 英文页并标「待翻译（planned）」，SPEC → 英文规范 + 中文导读 |
| 英文原文索引 | [doc/design/README.en.md](../doc/design/README.en.md) | 英文 | 改写前英文索引的逐字节原文 + 第 1 行切换器 |
| 核心 5 篇译文 | `doc/design/zh-CN/01-requirements.md` … `05-protocols.md` | 中文 | 文件名与英文页镜像同名 |
| 中文写作规范 | [doc/design/zh-CN/GLOSSARY.md](../doc/design/zh-CN/GLOSSARY.md) | 中文 | v1.1.1（冻结）；原始规范 v1.0.0，依 captain 裁定 A 修订为 v1.1.0，t14 仅编辑性同步 §8.2 → v1.1.1 |
| SPEC 中文导读 | [doc/design/zh-CN/SPEC-guide.md](../doc/design/zh-CN/SPEC-guide.md) | 中文 | 覆盖 SPEC §2/§4/§5/§7/§8/§9 与 P1–P5；明确「英文 SPEC 为唯一规范」 |
| 英文侧切换行 | `doc/design/01-requirements.md` … `05-protocols.md` | 英文 | 每篇第 1 行加反向中文链接；设计陈述与引用零改动（T5 时 `git diff --numstat` 逐文件 `2/0`） |
| 门禁扩展 | `scripts/doc-verify.sh` | — | 新增 zh-CN 扫描集、V14/V15；修正 V3 逐协议文档完整性 |

**明确不交付**（按 captain 裁定 A / SPEC R12 + §7.6）：根 `README.zh-CN.md` 与 `zh-CN/` 下的 `README.md` **不存在、不得创建**；撰写时实测两者均不存在。

---

## 2. 未翻译篇章（已知缺口，不计缺陷）

* `doc/design/06-flows.md` … `doc/design/11-coding-standards.md`（6 篇）与 `doc/design/SPEC.md` 正文本次**不翻译**。
* 中文索引对 06–11 指向英文页并标注「待翻译（planned）」；SPEC 行指向英文规范并给出中文导读入口。
* `doc/design/zh-CN/GLOSSARY.md` 与 `doc/design/zh-CN/SPEC-guide.md` 无英文对应物，按 GLOSSARY §3.4 S4 不参与双向切换校验（其中 SPEC-guide 仍需译文声明）。

---

## 3. 术语表

* 位置：`doc/design/zh-CN/GLOSSARY.md` §6，**74 条**（写「英文原词 → 中文定译 → 首现说明」三列），另含 §6.1 一词多义 9 行、§6.2 使用规则。
* 覆盖：信令、房间、席位、中继、ICE、DTLS、看门狗、世代、回退、编解码等核心概念；`host`/`HOST`、`generation`、`relay`、`fallback` 等歧义词在 §6.1 逐条消歧。
* 一致性核验（verifier 独立执行，见 [reports/63](63-i18n-verification.md) §4 A3）：§6 表 74 行逐条对照；**状态词 44/44 合规、0 偏离**；表内状态单元格 16/16；五对中英文页的 code-span 多重集、引用集合、标题/表格/代码块计数**完全相等**。

---

## 4. 切换器机制与配对

**格式（GLOSSARY §3，SPEC §7.6）**：切换器位于文件**前 8 行内、独立成行**（实现上位于第 1 行），只认半角真实括号。

* 中文默认页：`> **中文（默认）** · [English]` 后接半角圆括号包裹的相对路径（模板示意用全角括号，见下注）
* 英文页：`> [中文（默认）]` 后接半角圆括号包裹的相对路径，再接 ` · English`（模板示意用全角括号，见下注）

> **模板说明**：上面两条是**模板示意**，为避免被链接检查误判而用全角括号；**实际页面必须写半角圆括号**，其可复制示例在 [doc/design/zh-CN/GLOSSARY.md](../doc/design/zh-CN/GLOSSARY.md) §3.2/§3.3。若把全角括号抄进页面，链接不可渲染、V14 直接 FAIL。

**三组配对（双向校验，GLOSSARY §3.4 S1–S3；共 7 对页面）**

| 中文默认页 | 英文页 |
|---|---|
| 根 `README.md` | 根 `README.en.md` |
| `doc/design/README.md` | `doc/design/README.en.md` |
| `doc/design/zh-CN/01-requirements.md` … `05-protocols.md`（5 篇） | `doc/design/01-requirements.md` … `05-protocols.md` |

**豁免（§3.4 S4）**：`doc/design/zh-CN/GLOSSARY.md` 与 `doc/design/zh-CN/SPEC-guide.md`（及任何无英文对应物的文件）不参与双向校验；`doc/design/SPEC.md` 无切换器。未翻译的英文页 06–11 本次不加切换器（§3.4 S5）。

**门禁规则**：V14 `missing language switcher`（配对任一方向缺失/不可达即 FAIL）；V15 `missing translation disclaimer`（下节清单逐字声明缺失即 FAIL）。verifier 复核：7 对第 1 行独立成行、半角、双向可达且指向正确对端（[reports/63](63-i18n-verification.md) §4 A4）。

---

## 5. 译文声明适用范围（6 个 Y-1 文件）

逐字文本：`> 译文：若与英文原文冲突，以英文原文为准。`（GLOSSARY §4 Y-2）——**不是**早期 v1.0.0 的逗号措辞。

清单恰为 6 个文件：`doc/design/zh-CN/01-requirements.md`、`02-architecture.md`、`03-app-architecture.md`、`04-signaling-service.md`、`05-protocols.md`、`SPEC-guide.md`。

豁免（§4 Y-4）：`doc/design/zh-CN/GLOSSARY.md`（中文原创规范）、根 `README.md`、`doc/design/README.md` 与任何无英文对应物的文件。verifier 复核该串**只出现在这 6 个文件**（[reports/63](63-i18n-verification.md) §4 A5）。

---

## 6. 冻结 checker 与 revision 谱系

**终版（权威）**：`scripts/doc-verify.sh`

| 项 | 实测值 |
|---|---|
| sha256 | `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` |
| 两路一致 | `git show HEAD:scripts/doc-verify.sh \| sha256sum` 返回同一摘要 |
| bearing commit | `543d94153f982fe93cb6f08bc886b7fdce48505b`（`docs(i18n): 冻结扩展后的文档门禁脚本 (t2)`，2026-09-18 22:22:38 +0800） |
| 行数 / 字节 / mtime | 919 / 41 673 B / 2026-09-18 22:17:57 +0800 |
| 权限 | `-r--r--r--`（0444，物理只读） |
| 取代 | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45`（628 行，i18n 前） |

**revision 谱系**（同一路径的连续修订，均经现场观测）：
`676d075a…`(628) → `89607884…`(901) → `53f89372…`(930) → `2b47cca2…`(928，含后已作废的 V16) → `f43b1a10…`(909，V16 移除) → `172c5018…`(922) → `633d83ac…`(923) → `64081ef5…`(921，t2 台账终态旧值) → **`6c62591a…`(919，权威终值)**。

**跑前跑后指纹校验**：verifier 在 t17 记录联合指纹（checker + 17 工件）`3bb8914931742191aab5e7f9e49d3b4f` 于 22:27:25 = 22:27:29 一致。本报告作者在同一 17 项上独立计算（`sha256sum scripts/doc-verify.sh <17 paths> | md5sum`，§8.3→§8.4 顺序）得 **`fff4597c64850695dd6f47db711f04a8`**，全量门禁跑前 = 跑后一致；verifier 所用命令未复现其值，记为 `unverified`（不影响绑定事实：两侧独立测得 failures = 0 与同一 checker 摘要）。

---

## 7. 全部 i18n 工件终值（撰写时现场实测）

**9 个新增**：根 `README.en.md`、`doc/design/README.en.md`、`doc/design/zh-CN/GLOSSARY.md`、`doc/design/zh-CN/SPEC-guide.md`、`doc/design/zh-CN/01-requirements.md` … `05-protocols.md`（5 篇）。
**8 个修改后重签**：`doc/design/SPEC.md`、根 `README.md`、`doc/design/README.md`、英文 `doc/design/01-requirements.md` … `05-protocols.md`（5 篇）。

逐项 sha256（全值）/ 字节 / 行数 / mtime 登记在 [reports/54-docs-freeze-manifest.md](54-docs-freeze-manifest.md) **§8.3（新增）与 §8.4（重签）**；§8.5 另证明未受影响的 11 行**逐行 0 DRIFT**。

与 t17 的差异表（[reports/63](63-i18n-verification.md) §3）：17 项中恰 2 项在 t16 中变化（`zh-CN/01` F2+F3、`zh-CN/02` F4），其余 15 项 sha256 完全相同。

---

## 8. 验收证据

* **全量门禁（撰写时）**：`bash scripts/doc-verify.sh` → `doc-verify.sh: PASS (3912 checks, 2 warnings)`，**RAW EXIT=0**，0 FAIL 行（2 warnings 为基线既有的 A10 示例 app/build/nope.apk；advisory `NOTE` 22 行）。
* **verifier 结论**：t17 verdict **pass**，A1–A7 全绿（引用抽查 333/333 可达、术语 74 条、状态词 44/44、双向 7 对 100%、语义抽样 562 处零相反、SPEC 35/35 规则编号在册）；t11 主报告 [reports/61](61-i18n-verification.md) 亦为 `pass`（含一条治理 finding）。
* **绑定复核**：`git show HEAD:scripts/doc-verify.sh | sha256sum` = 工作树 = `6c62591a…`；`git status --porcelain scripts/doc-verify.sh` 为空。
* **两份报告自查**：见 §12。

---

## 9. 治理记录（按事实，不美化）

### 9.1 台账旧值 vs 权威值

下列任务台账中保存的是**终态旧值**，权威值一律以本报告与磁盘实测为准（[reports/54](54-docs-freeze-manifest.md) §8）：

| 任务 | 台账 output 记录 | 权威值（本轮） |
|---|---|---|
| `t2` | checker `64081ef5…` / 921 行（关闭时的中间态） | `6c62591a…` / 919 行 / 41 673 B |
| `t14` | GLOSSARY **v1.2.0** / `e6180b34…` / 27 256 B（存在约 3 分钟的中间版） | GLOSSARY **v1.1.1** / `51e3eb34…` / 27 419 B / 349 行 |
| `t1` | GLOSSARY v1.0.0 摘要 | 同上（v1.1.1） |
| `t5` / `t9` | 返工前的入口/索引/切换行值 | 见 [reports/54](54-docs-freeze-manifest.md) §8.3/§8.4 |

（终态任务不可原地更新，这是台账与现场的差异来源；captain 裁定不为此重开终态单。）

### 9.2 GLOSSARY 版本链与取证边界

版本链：**v1.0.0**（t1 冻结）→ **v1.1.0**（captain 裁定 A 重写：前 8 行内切换器、GLOSSARY 豁免、不创建语言后缀副本）→ **v1.1.1**（t14 仅**编辑性同步** §8.2 到 SPEC v1.7.0）。

披露：**v1.1.1 只改 §8.2 记录与版本/自证行，§2–§7 规则正文未变**（术语表仍 74 条、§3.4 仍三组配对 + 两处豁免、§2.2/§3/§4/§5/§6/§7 小节标题与 t1 基线一致）。**但无字节级基线可证**：`doc/design/zh-CN/` 是**未跟踪目录**，没有 pre-edit 快照；主仓松散对象 `02ff6242…` 经核查实为 **SPEC v1.5.2**（65 259 B），不是 GLOSSARY v1.1.0；tooling 克隆内该路径仅 141 B 占位、其 git 历史不含该文件。因此「规则未变」= **作者改动清单 + 结构性核验**，不是逐字节证明。自本轮起该文件的 digest 已登记进冻结清单，**未来的不可变性可获字节级保护**（既往不可追溯）。

### 9.3 治理事件

1. **t11（t6 替代）→ needs_revision / failed**：F1 = 其绑定的 checker revision 在验证窗口内被改写、且当时未入库（工作树漂移）；F2–F4 = 三处 §5.5 状态词偏离（`zh-CN/01:112`、`zh-CN/01:328`、`zh-CN/02:194`）。
2. **t16 修复**：三处状态词改为冻结定译（等长替换，01 仍 25 754 B/333 行、02 仍 15 877 B/219 行）→ 终值 `5af1985b…`、`716552cd…`。
3. **t17 复验 `pass`**：**完整独立验证证据由 `reports/61`（229 行，第三轮主报告）承载**；`reports/63`（73 行）是 t17 的**终审记录**，A1–A7 结论表在其 §7 并指向 `reports/61`。
4. **成员 `translator-b` 被移除**：captain 认定其在冻结窗口内长跑改写 checker。旁注：verifier 在 [reports/63](63-i18n-verification.md) §3.1 明确更正了 G1 的定性——`zh-CN/01`、`02` 在 22:23:38/22:23:40 的两次写入是**已授权的 t16 修复动作**，属**同一窗口既声明冻结又授权写文档集的流程时序冲突**，**不是越权写入**；写入内容为改进（闭合 F2–F4）。
5. **t4 作废**：其旧契约要求创建被裁定 A / SPEC R12 + §7.6 明令禁止的 `README.zh-CN.md` 与 `zh-CN/` 下的 `README.md`；译文内容无缺陷，由 **t10** 收敛（两文件终值 `36d180bc…`、`4acd7b70…`）。
6. **`reports/63-i18n-verification.md` 终值与多次 in-scope 改写（登记值按现场实测）**：盘上**终值** = sha256 `d3b80c3a65c4312c76a7845d4891ae40e820a3e5d5f09bfc3856716205bf8aca` / **73 行 / 5 130 B**（mtime 2026-09-18 22:32:31，连读两次稳定）；`reports/61-i18n-verification.md` = sha256 `1ed8cd80258768a5a44ca69e9f484e49dc404719b480236e4ec65f954a2e951a` / 229 行 / 22 425 B（不变）。**分工**：完整独立验证证据由 `reports/61`（第三轮主报告，verdict = pass）承载；`reports/63` 是 t17 的**终审记录**（verdict = pass、绑定三元组、17 工件摘要、联合指纹 `3bb8914931742191aab5e7f9e49d3b4f`、跑前 22:27:25 = 跑后 22:27:29、F1–F4 消解、G1 定性、determinism 16/16 全 PASS、只读 444 复核、A1–A7 结论表并指向 `reports/61`）。t17 契约原措辞要求「210 行规模的独立报告」（t17 description 原文）；实际由 `reports/61` 承载完整证据、`reports/63` 收缩为指针＋摘要——这是 **captain 事后确认的处置，不算缺陷**。`reports/63` 在 t17 终态（completed/pass）后有过**多次** in-scope 改写（该文件即 t17 的唯一 deliverable）：captain 记录的链为 `199 → 73 → 214 → 73` 行；我方可核验的锚点是首测 199 行（22:32:20）与终值 **73 行（22:32:31，连读两次稳定）**，**登记以该现场实测值为准**。其间 checker 与 17 个 i18n 工件**零写入**，联合指纹仍为 `3bb8914931742191aab5e7f9e49d3b4f`，故 §7 各项摘要不变；verifier 已接 captain 绝对停笔令，该值为终值。[reports/54-docs-freeze-manifest.md](54-docs-freeze-manifest.md) §8.6 的登记值已同步。

### 9.4 取证边界（如实披露）

`doc/design/zh-CN/` 为未跟踪目录，t16 的改前字节无法从 git 取回，故「仅三处词形变化」由四项独立证据共同约束（t11 窗口内的改前实测留档、字节/行数完全不变、状态词 44/44 合规 0 偏离、五对页面 code-span/引用/结构全等），**非逐字节全等证明**——属**留档流程缺口**，非内容缺陷。

### 9.5 已知美容项（不修，记录备查）

1. `doc/design/SPEC.md` §9 第 **741** 行仍写 `the independent verification task (t7, owner verifier)`（本轮验证为 t11/t17）→ 建议角色式措辞，**交付后可选修订**；不改的理由是 SPEC v1.7.0 已被 verifier 按具体行号独立验证（440/441、688–708、407–411、736–738、707–708），此刻编辑会使锚点位移。
2. `doc/design/SPEC.md` 第 **723** 行与 changelog 1.4.0 行的 `t11` 指**上一轮文档集项目**的 t11（路径记法裁定），与本轮验证单 t11 重名——属历史事实，非缺陷。
3. `doc/design/zh-CN/01-requirements.md:328`「…被上游源码已证伪（disproven）」语序偏生硬：机械合规、纯风格（[reports/63](63-i18n-verification.md) §5）。

**后续收尾轮（2026-09-19）处置**：第 1 项**已闭合**——`doc/design/SPEC.md` 升到 **v1.7.1**，§9 第 741 行改为角色式措辞（保留 `owner `verifier``、去掉轮次编号），changelog 增 1.7.1 行；第 3 项**已闭合**——`zh-CN/01:328` 改为已交付形态 **D′**（`| D-4 | 历史文本「旋转已烘焙进 I420」，上游源码已证伪（disproven） | 对采集路径的错误心智模型 |`，连续定译串保留）；第 2 项（`t11` 重名）为历史事实，保留不改。另新增一项：`doc/design/zh-CN/02-architecture.md:194` 的句读偏生硬但合规（形态 A 曾交付后按第 6 版还原指令撤销，captain 裁定接受 HEAD 原文），记为 criteria-vs-applied 观察项（§14.6）。

---

## 10. 门禁口径：**工作区门禁**而非仓库门禁（2026-09-19 重测并取代旧引用）

**本轮（后续收尾轮，task t5/t7）已在当前 revision 上重跑裸克隆实验**，取代下文中对 [reports/60-github-push-runbook.md](60-github-push-runbook.md) §7 的转述引用：

| 落点 | 命令 | 实测 | 归类 |
|---|---|---|---|
| 工作区内的临时落点（工作区 tmp 下的 c3-bare-clone，`reports/64` §5.1 指定；该克隆目录已于 C5 清理） | `git clone --depth 1 --no-recurse-submodules` + `bash scripts/doc-verify.sh` | **FAIL (4 failures, 5 warnings, 3912 checks)，exit 1** | ② submodule 内部引用 4；① 工作区证据缺失 0；③ env\*.sh 变体 0；④ 其他 0 |
| 工作区外的落点（HOST: /tmp/c3-iso-clone，落点控制；该目录已清理） | 同上 | **FAIL (39 failures, 5 warnings, 3912 checks)，exit 1** | 去重后 = ② 4 + ③ 33（27 条 env\* P2 + 6 条 `//env*.sh` 引用失败）+「非 env 的 ①」2；④ 0 |
| 作者工作区（对照） | `bash scripts/doc-verify.sh` | **PASS (3912 checks, 2 warnings)，exit 0** | — |

**机理（实测）**：checker 第 80 行 `WORKSPACE_ROOT=$(cd "$REPO_ROOT/../.." && pwd)` 以「仓库根的上两级」为工作区根。真实仓库位于 `…/workspace/code/webrtc-demo`，上两级 = `…/workspace`；把克隆放在 `…/workspace/tmp/` 时上两级**同样是** `…/workspace`，P2 证据（`env*.sh`、`tmp/**`）恰好存在 → 只剩 4 条 submodule 失败；克隆落到工作区外时上两级退化为 `/` → 29 条 P2 失败并连带 6 条 `//env*.sh` 引用失败。

**结论（必须连同落点敏感性一起引用）**：门禁是**工作区门禁**，任何**裸克隆**（只含 HEAD 提交、不含本轮未提交改动、不含 submodule 内容、不含仓库外 P2 证据）都**无法全绿**；只写「4 条」会误导读者。旧引用（`8ac1a14d…` 上 33/4/2466）**已被取代**，不再是当前判据。逐条失败清单、归类依据与原始输出见 [reports/65-followup-verification.md](65-followup-verification.md) §7 与本报告 §14.3。

---

## 11. 已知缺口与遗留事项

1. 06–11 与 SPEC 正文未翻译（§2，索引标 planned，不计缺陷）。
2. `doc/design/zh-CN/GLOSSARY.md`（v1.1.1）与 `SPEC-guide.md` 无英文对应物，不参与双向校验。
3. `GLOSSARY.md` 的「规则未变」缺字节级基线（§9.2）。
4. verifier 的联合指纹**生成命令**未复现（§6）——**已由后续收尾轮收窄**：仅 `3bb89149…` 的生成命令不可复现，`fff4597c…` 已复现并解释（§14.4）。
5. 裸克隆表现未在本轮 revision 上重测（§10）——**已闭合**：本轮在当前 revision 上双重测（工作区内 4 failures / 工作区外 39 failures），见 §10 与 §14.3。
6. t16 改前字节无留档（§9.4）。
7. 三项美容项（§9.5）——后续收尾轮**闭合两项**（SPEC §9:741 角色式措辞、`zh-CN/01:328` D′），保留 `t11` 重名一项，另新增 `zh-CN/02:194` criteria-vs-applied 观察项（§9.5、§14.6）。

---

## 12. 复现与路径自查

```bash
cd code/webrtc-demo
git rev-parse HEAD                                    # 2026-09-18 撰写时 = 543d9415…；2026-09-19 收尾轮 = 1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae
sha256sum scripts/doc-verify.sh                       # 6c62591a…1eb59（两个时点相同）
git show 543d9415…:scripts/doc-verify.sh | sha256sum    # 6c62591a…1eb59（bearing commit 复核）
bash scripts/doc-verify.sh; echo "EXIT=$?"              # PASS (3912 checks, 2 warnings) / EXIT=0
bash scripts/i18n-audit.sh; echo "EXIT=$?"              # PASS (79 checks, 0 warnings) / EXIT=0（收尾轮新增）
sha256sum scripts/doc-verify.sh <17 个 i18n 工件> | md5sum   # fff4597c64850695dd6f47db711f04a8（仅代表 2026-09-18 的改动前树，见 §14.4）
# C4 规范配方（19 路径）与其终值见 §14.4 / reports/54 §9.7：40d9fa70…
```

**路径可达自查（撰写时）**：本文的每一条 markdown 相对链接与每一个被断言存在的仓库相对路径都在磁盘上校验通过；被断言**不存在**的路径（`README.zh-CN.md`、`zh-CN/` 下的 `README.md`）按构造排除在「存在性断言」之外。自查命令与结果记录在任务 t18 的输出中（`--only` 两份报告 + 链接解析循环，0 失败）。

---

## 13. 不确定性清单（显式 `unverified`）

| 项 | 状态 | 说明 |
|---|---|---|
| verifier 联合指纹 `3bb89149…` 的生成命令 | `unverified`（**已收窄**） | 10 种候选公式均未复现（[reports/65](65-followup-verification.md) §8.4）；另一旧值 `fff4597c…` **已复现并解释**（改动前树 18 路径未排序 `sha256sum … \| md5sum` 变体），故 `unverified` 仅剩前者；不影响 failures=0 与 checker 摘要两项绑定事实。见 §14.4 |
| 裸克隆在 `6c62591a…` 上的表现 | **已闭合**（2026-09-19 现场重测） | 冻结落点 FAIL (4 failures, 5 warnings, 3912 checks) exit 1（② submodule 4）；工作区外落点 FAIL (39/5/3912) exit 1（② 4 + ③ 33 + 非 env ① 2）；工作区对照 PASS (3912/2) exit 0。见 §10、§14.3 |
| GLOSSARY §2–§7 的逐字节不变性 | `unverified`（结构性核验通过） | 未跟踪文件、无 pre-edit 快照；见 §9.2 |
| t16 改前字节 | `unverified` | 无留档；见 §9.4 |
| advisory `NOTE` 行数 | 22（本文实测） vs 19（reports/63 §1.3） | 计数口径差异，非门禁结果差异 |

---

## 14. 后续收尾轮增补（task t7，2026-09-19）

本节把后续收尾轮（C1 C1 SPEC v1.7.1 / C2 句读修订 / B3 审计脚本 / C3 裸克隆 / C4 规范配方 / C5 清理）的现场实测结果整合进本交付报告。所有数值均为 **2026-09-19 撰写时**在容器工作区（`/data/dsh/home/workspace`，仓库 `code/webrtc-demo`）现场实测，不沿用任务 output 或先前报告的转述值。

### 14.1 本轮终值与绑定

| 工件 | sha256（full） | 字节 / 行数 | 备注 |
|---|---|---|---|
| `scripts/doc-verify.sh` | `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` | 41 673 / 919 | 未变；`-r--r--r--`（0444）；`git show 543d9415…:scripts/doc-verify.sh \| sha256sum` 现场复核同值 |
| bearing commit | `543d94153f982fe93cb6f08bc886b7fdce48505b` | — | 未变（仍是承载 checker 的提交） |
| runtime HEAD（本轮） | `1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae` | — | 本轮无提交授权，工作树含未提交改动 |
| `doc/design/SPEC.md` | `b3368522392205dd02073a8adf472566b58fad8234de96fbf69fcbc801c369e5` | 67 976 / 758 | **v1.7.1**（原 v1.7.0 / `a5724a40…` / 757） |
| `doc/design/zh-CN/01-requirements.md` | `a4271a402396099f56ca952e6d489100b13027375e884751d89b900644c2c8d5` | 25 754 / 333 | 已交付形态 **D′**（只改第 328 行，`numstat 1 1`） |
| `doc/design/zh-CN/02-architecture.md` | `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42` | 15 877 / 219 | **02 相对 HEAD 零差异（中程改动已还原）**，`git diff` 为空 |
| `scripts/i18n-audit.sh` | `f93da7d5156edad9612740a30b952e5cf11e794eec57c89ec6b2b0d1c29fd82d` | 32 425 / 810 | 新增；`-rwxr-xr-x`（755） |
| [reports/64](64-followup-requirements.md) | `5c1d96d4baf2e5fdc0b8261e891fbeecb4898f72b96e181132ca13109d1f7823` | 39 703 / 356 | 本轮判据来源；顶部含 captain 直接写入的 `CAPTAIN RULING` 段 |
| [reports/65](65-followup-verification.md) | `8f7ed1a32e62759d4b736056e003f45558ae01305f5b45c29d21ca9497c34dab` | 35 533 / 439 | t5 独立验证报告，verdict = **pass**（终态后一次 in-scope 措辞修订：`8133aefd…` / 35 491 B → 本值） |

**19 路径联合指纹（C4 规范配方）= `40d9fa70db57add32376157e181b1fe57c8fa250fe7a06253905d5a794adfa55`**（跑前 = 跑后、与输入顺序无关；配方见 §14.4）。登记明细同步写入 [reports/54](54-docs-freeze-manifest.md) **§9**。

### 14.2 B3 审计脚本：检查项、用法与正反例结论

* 身份：`scripts/i18n-audit.sh` = `f93da7d5…` / 810 行 / 32 425 B / mode 755；纯 bash + awk + grep + sed + realpath（无 node/python 依赖，`LC_ALL=C`），自解析仓库根，可从任意 cwd 调用；**不修改也不调用** checker（仅头部注释提及）。
* 用法：`bash scripts/i18n-audit.sh`（全量，**PASS (79 checks, 0 warnings) / RAW EXIT=0**）、`bash scripts/i18n-audit.sh --only <path>…`（可重复/多路径；目录递归 `*.md`；与门禁 SPEC A5 同口径）、-h 或 --help（打印头部注释）。退出码 **0 = 无 FAIL / 1 = 有 FAIL / 2 = 调用或环境错误**；finding 格式 `file:line → FAIL[Z<id>] problem → fix`，按 Z-id/路径/行号排序，输出逐字节确定。
* 检查项（9 项，无一条放宽）：**Z1** 配对切换器、**Z2** 逐字译文声明 + 全仓唯一性、**Z3** §6 术语表结构、**Z4** §5.5 状态词全角括号邻接、**Z5** §6.1 窄 denylist + §6 状态词行、**Z6** 引用集合对等/可达/在界、**Z7** code-span 多重集对等、**Z8** 结构计数对等、**Z9** 禁建副本 + 旧式切换器残留。
* 冻结基线逐格复现：Z2 = 6 文件；Z3 = 74 行 × 4 列；Z4 = 44 命中 / 0 偏离；Z5 = `座位`/`会议室` 0 + 状态词行 2；Z7 = 321/155/289/413/314；Z8 = 7·16·52·0 / 10·4·40·1 / 9·0·40·3 / 9·0·70·1 / 9·0·70·0。
* **正反例结论**：verifier 自研 **31 次运行（含 17 条负例）** 全部按预期 FAIL 且指向正确 `file:line` 与 Z-id，9 项判据均有 fail 出口、无 skip、无 FAIL→WARN 降级（[reports/65](65-followup-verification.md) §3、§3.3）；doc-tooling 探针矩阵 **13/13 OK**（P0 对照 PASS/exit 0；P1–P9 + P3b + P3c 全部 exit 1 且归因正确），原始留痕 `tmp/i18n-audit-probe/{probe.sh,out/matrix.txt,out/P*.out,VERIFY-t2.txt}`（captain 裁定保留）。典型负例：去掉声明行行首 `> ` → `doc/design/zh-CN/02-architecture.md:1 → FAIL[Z2] … (found 0)` + 全仓 `5 ≠ 6`，exit 1。
* 关系声明：审计是**补充证据**，不替代门禁（SPEC §9）；两者冲突时以门禁为准并上报。

### 14.3 C3 裸克隆门禁实测（取代 §10 的旧引用）

命令与逐条证据见 [reports/65](65-followup-verification.md) §7（冻结落点：工作区 tmp 下的 c3-bare-clone；落点控制：HOST: /tmp/c3-iso-clone；两处 `CLONE_HEAD = MAIN_HEAD = 1b5bb78d…`；克隆内不含 `scripts/i18n-audit.sh`，`audit-in-clone=1`）：

| 落点 | 实测 | 归类（去重后） |
|---|---|---|
| 冻结落点（工作区内 `tmp/`） | **FAIL (4 failures, 5 warnings, 3912 checks)，exit 1** | ② submodule 内部引用 **4**（`08-issues-and-solutions.md:311/314/468` 引用 `third_party/libwebrtc-src/…`；submodule 为 `160000` gitlink，克隆未初始化） |
| 工作区外落点 | **FAIL (39 failures, 5 warnings, 3912 checks)，exit 1** | ② 4 + ③ 33（27 条 env\* P2 + 6 条 `//env*.sh` 引用失败，见 `07-build-and-deploy.md:23/27/28/90/91/180`）+ 非 env 的 ① 2 |
| 作者工作区（对照） | **PASS (3912 checks, 2 warnings)，exit 0** | — |

机理：checker 第 80 行 `WORKSPACE_ROOT=$(cd "$REPO_ROOT/../.." && pwd)`；克隆落点决定上两级是否等于真实工作区根，从而决定 P2 证据是否可见。**结论：门禁是工作区门禁，任何裸克隆都无法全绿；引用时必须写明落点敏感性**（只写「4 条」会误导）。旧值（reports/60 §7 在 `8ac1a14d…` 上的 33/4/2466）已取代。

### 14.4 C4 联合指纹规范配方（唯一命令）

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

* 定义：`sha256(排序后的「sha256(file)␠␠file」行序列)`；输入为**固定 19 个路径**（checker + 17 个 i18n 工件 + 本轮新增审计脚本），**不含 `reports/**`**；两端 `LC_ALL=C sort` ⇒ 与输入顺序无关，且命令自包含（清单写在命令内）。
* **终值 = `40d9fa70db57add32376157e181b1fe57c8fa250fe7a06253905d5a794adfa55`**（2026-09-19 现场实测；跑前 = 跑后一致；与 [reports/65](65-followup-verification.md) §8.2 一致）。
* 18 路径机制自检值 = `1bf12697c411ba292c62234d5f6fb826eebe98ed8423277668794382b0a5d901`（不含审计脚本、取自 HEAD 树）；本轮用 `git archive HEAD <18 路径>` 在工作区外**独立复算命中**。
* 旧值处置（**不篡改历史**）：`3bb8914931742191aab5e7f9e49d3b4f`（[reports/63](63-i18n-verification.md) 记录的旧联合指纹）**生成命令不可复现**——10 种候选公式在同一改动前树上逐一试算均未命中，就地标注「不可复现，已被规范配方取代」，原文保留；`fff4597c64850695dd6f47db711f04a8`（本报告 §6 记录）**已复现并解释**＝同一 HEAD 树、18 路径**未排序** `sha256sum … | md5sum` 变体（本轮独立复算命中）；两者**均不是**规范配方值，规范配方不能也不得据以宣称与旧值一致。

### 14.5 C5 清理记录（task t6，现场实测）

**删除**（仓库外临时产物，合计 **610 249 145 B / 21 552 文件**；下表路径以**纯文本**给出——它们是**已删除**的仓库外产物，本文不对其作存在性断言）：

| 路径 | 大小(B) | 文件数 | 可重建性 |
|---|---|---|---|
| tmp/i18n-checker/ | 587 807 703 | 19 565 | 可重建（浅克隆 + 重跑 checker 模拟工作区脚本），无独立价值 |
| tmp/c3-bare-clone/ | 22 387 924 | 1 982 | 可重建（`git clone --depth 1 --no-recurse-submodules` 一条命令）；测量值已由 [reports/65](65-followup-verification.md) §7 与本报告 §14.3 承载 |
| tmp/verifier-t5-notes.md | 10 171 | 1 | 内容已进 [reports/65](65-followup-verification.md) |
| tmp/t1-validate.mjs | 9 099 | 1 | 判据与结论已冻结进 [reports/64](64-followup-requirements.md) §2.6 |
| tmp/check-A.mjs | 2 230 | 1 | 同上 |
| tmp/check-AD.mjs | 1 485 | 1 | 同上 |
| tmp/c4-list.txt | 533 | 1 | 配方已内联在 [reports/64](64-followup-requirements.md) §6.2 |

**保留**（2026-09-19 t7 撰写时现场复核；路径以纯文本列出，避免对已清理路径作存在性断言）：`tmp/i18n-audit-probe/`（22 517 618 B / 2 005 文件，**现场存在**；t7 引用原始 `tmp/i18n-audit-probe/out/matrix.txt`、`tmp/i18n-audit-probe/VERIFY-t2.txt` 等；captain 裁定保留、最终处置另行派单）、工作区根 `env.sh` / `env-go.sh` / `env-container.sh`（**现场存在**）、tmp/n1、tmp/n2、tmp/n3、tmp/n4、tmp/n6、tmp/n7 与 tmp/dev-logs-0019、tmp/dev-logs-2232、tmp/dev-logs-2250（**现场存在**），以及**非本轮条目**（如 `tmp/t56`、`tmp/t56-libvpx.sh`、`tmp/verifier-recon` 等，按 [reports/64](64-followup-requirements.md) §7「无法确认或非本轮产物一律保留」不动）。**清理完成后本轮在工作区临时根只剩被保留的 `tmp/i18n-audit-probe/`**（`ls tmp | grep '^i18n-'` 仅此一项，t7 现场核对）。

> **增补清理（同一可逆方法；取代 t6 主单里「保留 t5-verify」的记录）**：t6 主单之后又执行了一次增补清理，**删除 20 个顶层条目（2 目录 + 18 文件）**：
>
> | 条目（纯文本，均已删除） | 大小(B) | 文件数 |
> |---|---|---|
> | tmp/t5-verify/ | 22 552 918 | 2 051 |
> | tmp/t5-legacy/ | 407 886 | 18 |
> | t5-*.out（t5-audit-{t0,r1,r2,final,final2}.out、t5-gate-{t0,report,report2,report3,report4,final,final2}.out、t5-c3-gate.out、t5-c3-gate-ws.out） | — | 14 |
> | t5-fp-*.txt（t5-fp-t0.txt、t5-fp-19-detail.txt、t5-fp-19-lines.txt） | — | 3 |
> | t5-c3-clone.log | 133 | 1 |
>
> 因此 t7 现场核对时 tmp/t5-verify/ **不存在**（`test -d` 为假、`find` 3 层内无命中）——这不是「清单与盘面不一致」，而是**增补删除的结果**；`tmp/t5*` 现仅剩非本轮条目 `tmp/t56`、`tmp/t56-libvpx.sh`。工作区外的 HOST: /tmp/c3-iso-clone 属验证后已清理的落点，未列入保留项。t5 的冻结基线与签名文件的判据结论已由 [reports/65](65-followup-verification.md) 完整承载，故**不影响本轮绑定**。

**方法（t6 记录；t7 现场复核可验证部分）**：采用**可逆隔离**——先 mktemp -d 暂存 → mv 移出 → 跑双门禁 → 全绿才 rm；**清理前、隔离中（等价删除后）、删除后三轮**均 gate `EXIT=0`（PASS 3912 checks, 2 warnings）、audit `EXIT=0`（PASS 79 checks, 0 warnings）；`--only` 于 reports/54、62、64、65 全部 `EXIT=0`。

**证据完整性（补齐两份额外能力留痕）**：补齐 `tmp/i18n-audit-probe/out/Z2-uniqueness.out` 与 `tmp/i18n-audit-probe/out/Z9-forbidden-copy.out` 时**未重跑** `tmp/i18n-audit-probe/probe.sh`；t6 以 before → after 逐文件 `sha256` 对比证明「除新增两份外其余 20 个证据文件逐字节不变」，并声明**绑定版本 = after 最终捕获、此前预览值作废**。t7 现场复核一致：两份新证据存在（`tmp/i18n-audit-probe/out/Z2-uniqueness.out` = `004e3ed336e397a54bd4e9e6dc65664e76699e46e31acba684179b203aa77996` / 42 行；`tmp/i18n-audit-probe/out/Z9-forbidden-copy.out` = `060d369079a28fa65932f03ecc0937c8b80d3ca4284c28dbbafa7ffd41c6f2c9` / 43 行），`tmp/i18n-audit-probe/probe.sh` 仍 `0f9b246fc9bf616db7a73b466a43f266924c34bb75da6f1ee016cb37b2a2a52c` / 9 573 B、`tmp/i18n-audit-probe/VERIFY-t2.txt` 仍 `44c31185aad23ab59e650aee6e4a6db589f0a75454e51f8803e247fe00d5cf0b`——与补齐前的预览值一致，符合「未重跑 probe.sh」。注：[reports/65](65-followup-verification.md) 在 fenced 代码块内引用了 c3-bare-clone 路径（第 217/416 行），该目录删除后 `--only reports/65` 仍 PASS（fenced 块不计入路径存在性断言）。

**仓库内零改动**：清理前后 `git status --porcelain` 一致；清理前后门禁 `doc-verify.sh` **PASS (3912/2) exit 0**、`i18n-audit.sh` **PASS (79/0) exit 0**；删除后 `--only` reports/54、62、64、65 全部 `EXIT=0`。

### 14.6 治理记录（按事实，不美化）

1. **判据文件竞态**：本轮判据 `reports/64` 在约 15 分钟内出现 **7 个版本**（`e5e2e116 → 76d97797 → 9db6c582 → 283f3794 → 2cf5704b → 883f8992 → 37904ea7`），最终由 captain 在文件**顶部直接写入 `CAPTAIN RULING` 段**，其效力高于该文件 §0.1/§4.1/§9.3/§9.11 中关于 C2 形态与 t4/t8 处置的一切表述。冲突文本**保留为历史提案、不作更正**。
2. **C2 的最终盘面（criteria-vs-applied）**：`doc/design/zh-CN/02-architecture.md` 保持 **HEAD 原文**（形态 A 曾在 t4 attempt 2 交付，随后按第 6 版 §0.1/§4.2 的还原指令撤销；captain 接受）；`doc/design/zh-CN/01-requirements.md:328` 保持**已交付形态 D′**（`reports/64` §4.1 的「形态 D」**未采纳**——D 与 D′ 均满足 GLOSSARY §5.5 连续定译串与 §4.4 判定，第三次改写收益为零）。t4 的终态记录（形态 A）与盘面不符，t4/t8 均**不重开、台账不改写**。
3. **C1**：`doc/design/SPEC.md` v1.7.0 → **v1.7.1**，3 处改动（版本行、§9 第 741 行角色式措辞、changelog 1.7.1 行），`numstat 3 2`；§9 段内已无轮次编号。
4. **休眠替代链（t9/t10/t11）**：三者均在 t4 以 blocker failed 的窗口内创建，用于替代 t5/t6/t7。t4 随后由 `reports/64` 修订版重开并以 attempt 2 完成（其形态 A 交付后被第 6 版还原指令撤销，captain 接受 02 保持 HEAD 原文），原链 t5/t6/t7 因此恢复为有效链。**t9 在调度器自动派发后由 verifier 依 captain 的显式暂停指令置为 `cancelled`（superseded，理由：其交付物 `reports/65` 已由 t5 完整交付并通过门禁，且其契约文本已过期）；t10（deps t9）与 t11（deps t9/t10）因依赖已 cancelled 而永久不可达，captain 尝试接管作废时被平台拒绝（`blocked by unfinished dependencies`），故三者全程未使用。**
5. **成员移除**：`translator-b`（上一轮已移除：captain 认定其在冻结窗口内长跑改写 checker）与 `doc-architect`（本轮移除：约 15 分钟内对同一冻结件产出 7 个版本、其第 6 版「必须 `git checkout` 还原 02」的指令直接导致已交付形态 A 被撤销、在 captain 三次终局裁定后仍持续发出冲突指令、对有效链与暂停链持续混淆）。doc-architect 的有效交付保留计入：`reports/64`（含顶部 `CAPTAIN RULING`，现 `5c1d96d4…`）与 `doc/design/SPEC.md` v1.7.1（`b3368522…` / 758 行 / 3 hunk / `numstat 3 2`）。
6. **§9 台账文本差异**：`t8` 的验收文本仍写「不得改写 `02-architecture.md`（`reports/64` §0.1 明确禁止）」，而实际落点是 t8 只写 01、t4 只写 02，**互不代改**；该冲突为文本层面，按实际执行记录（t8 只改 01，02 由 t4 触碰后还原）。
7. **`reports/65` 的 in-scope 措辞修订**：t5 终态后 verifier 把 02 的表述改为「相对 HEAD 零差异（中程改动已还原）」，报告身份 `8133aefd…`（35 491 B）→ **`8f7ed1a3…`（35 533 B）**；改后复验 `--only reports/65` PASS 50 checks、全量 PASS (3912/2) exit 0、`i18n-audit.sh` PASS (79/0) exit 0、19 路径指纹仍 `40d9fa70…`、checker 仍 `6c62591a…`。

### 14.7 未受影响的 15 行 — 0 DRIFT

[reports/54](54-docs-freeze-manifest.md) §8.3（去掉本轮改动的 `doc/design/zh-CN/01-requirements.md`）与 §8.4（去掉本轮改动的 `doc/design/SPEC.md`）合计 **15 行**逐行现场重测 `sha256sum` + `wc -c` + `wc -l`，与登记值逐格相等（循环输出 **OK × 15**，任一不等会打印 `DRIFT`）：`doc/design/zh-CN/GLOSSARY.md`、`doc/design/zh-CN/SPEC-guide.md`、`doc/design/zh-CN/02-architecture.md` … `doc/design/zh-CN/05-protocols.md`、`README.en.md`、`doc/design/README.en.md`、`doc/design/01-requirements.md` … `doc/design/05-protocols.md`、`README.md`、`doc/design/README.md`。checker `6c62591a…` 与 bearing commit `543d9415…` 保持不变。

### 14.8 本节复现命令

```bash
cd /data/dsh/home/workspace/code/webrtc-demo
git rev-parse HEAD                                     # 1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae
sha256sum scripts/doc-verify.sh scripts/i18n-audit.sh   # 6c62591a…1eb59 / f93da7d5…fd82d
git show 543d9415…:scripts/doc-verify.sh | sha256sum     # 6c62591a…1eb59
bash scripts/doc-verify.sh; echo "EXIT=$?"               # PASS (3912 checks, 2 warnings) / EXIT=0
bash scripts/i18n-audit.sh; echo "EXIT=$?"               # PASS (79 checks, 0 warnings) / EXIT=0
bash scripts/doc-verify.sh --only reports/54-docs-freeze-manifest.md   # PASS / EXIT=0
bash scripts/doc-verify.sh --only reports/62-i18n-delivery.md          # PASS / EXIT=0
# C4：见 §14.4 配方；C3：见 reports/65 §7；15 行 0 DRIFT：逐行 sha256sum + wc -c + wc -l
```

### 14.9 本轮更新/新增的不确定项

* `3bb89149…` 生成命令：仍 `unverified`（已收窄，见 §13）。
* 裸克隆表现：**已闭合**（§14.3 现场重测）。
* 形态 A 撤销后 `zh-CN/02` 的「改前字节」：由 `git diff` 为空（净差异为零）证明，无需字节级快照；`doc/design/zh-CN/**` 未跟踪的既有边界（§9.2）不变。
* 本轮报告的绑定同 §7/§8.8：任何进一步编辑（含对 01/02/`reports/64`）都会使 §14.1 的登记值失效，须重新现场测量。
