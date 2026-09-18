# 62 — i18n 交付报告：中文文档切换能力（task t18）

> Status: **delivered** · Owner: `i18n-architect` · Task: t18（t12 的替代整合单）, 2026-09-18
> 冻结绑定：checker `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` + bearing commit `543d94153f982fe93cb6f08bc886b7fdce48505b` + 17 个 i18n 工件终值（见 §7）
> 验证依据：[reports/61-i18n-verification.md](61-i18n-verification.md)（第三轮主报告，229 行 / 22 425 B / `1ed8cd80258768a5a44ca69e9f484e49dc404719b480236e4ec65f954a2e951a`，**承载完整 A1–A7 证据**，verdict = pass）与 [reports/63-i18n-verification.md](63-i18n-verification.md)（t17 **终审记录**，73 行 / 5 130 B / `d3b80c3a65c4312c76a7845d4891ae40e820a3e5d5f09bfc3856716205bf8aca`：verdict、绑定三元组、17 工件摘要、联合指纹、F1–F4 消解、G1 定性、determinism，并指向 `reports/61`；verdict = pass）
> 冻结清单增补：[reports/54-docs-freeze-manifest.md](54-docs-freeze-manifest.md) §8（本轮 i18n 增补，现场实测）
> 写作纪律：本文只写可核验事实；所有数值均为 2026-09-18 撰写时的现场实测；不确定处显式标注 `unverified`。

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

---

## 10. 门禁口径：**工作区门禁**而非仓库门禁

引用 [reports/60-github-push-runbook.md](60-github-push-runbook.md) §7 的实测（匿名 `git clone --depth 1`，提交 `8ac1a14d…`，不含 submodule）：

```
bash scripts/doc-verify.sh → FAIL (33 failures, 4 warnings, 2466 checks), exit 1
```

失败构成：`missing workspace path`（P2）23 条（文档引用了**故意不纳入版本控制**的工作区证据：`tmp/**` 设备日志与 `env*.sh`）+ submodule 内部引用约 4 条 + workspace 根回退后 `env*.sh` 变体约 6 条。同一命令在**作者工作区**内为 `PASS (2466 checks, 2 warnings)` exit 0。

结论：**门禁只能在作者工作区内全绿**；任何其他检出无法全绿，原因是 P2 证据按设计位于版本控制之外，而不是文档有误。本轮（i18n revision）**未重跑裸克隆实验**——该结论按 `unverified`（对 `6c62591a…` 而言）引用 reports/60 的实测，工作区内的当前实测为 `PASS (3912 checks, 2 warnings)` RAW EXIT=0。

---

## 11. 已知缺口与遗留事项

1. 06–11 与 SPEC 正文未翻译（§2，索引标 planned，不计缺陷）。
2. `doc/design/zh-CN/GLOSSARY.md`（v1.1.1）与 `SPEC-guide.md` 无英文对应物，不参与双向校验。
3. `GLOSSARY.md` 的「规则未变」缺字节级基线（§9.2）。
4. verifier 的联合指纹公式未复现（§6），记录为 `unverified`；两侧绑定事实一致。
5. 裸克隆表现未在本轮 revision 上重测（§10）。
6. t16 改前字节无留档（§9.4）。
7. 三项美容项（§9.5）。

---

## 12. 复现与路径自查

```bash
cd code/webrtc-demo
git rev-parse HEAD                                    # 543d94153f982fe93cb6f08bc886b7fdce48505b
sha256sum scripts/doc-verify.sh                       # 6c62591a…1eb59
git show HEAD:scripts/doc-verify.sh | sha256sum        # 6c62591a…1eb59
bash scripts/doc-verify.sh; echo "EXIT=$?"              # PASS (3912 checks, 2 warnings) / EXIT=0
sha256sum scripts/doc-verify.sh <17 个 i18n 工件> | md5sum   # fff4597c64850695dd6f47db711f04a8
```

**路径可达自查（撰写时）**：本文的每一条 markdown 相对链接与每一个被断言存在的仓库相对路径都在磁盘上校验通过；被断言**不存在**的路径（`README.zh-CN.md`、`zh-CN/` 下的 `README.md`）按构造排除在「存在性断言」之外。自查命令与结果记录在任务 t18 的输出中（`--only` 两份报告 + 链接解析循环，0 失败）。

---

## 13. 不确定性清单（显式 `unverified`）

| 项 | 状态 | 说明 |
|---|---|---|
| verifier 联合指纹 `3bb89149…` 的生成命令 | `unverified` | 三种候选公式均未复现；不影响 failures=0 与 checker 摘要两项绑定事实 |
| 裸克隆在 `6c62591a…` 上的表现 | `unverified` | 引用 reports/60 在 `8ac1a14d…` 上的实测（33/4）；本轮未重跑 |
| GLOSSARY §2–§7 的逐字节不变性 | `unverified`（结构性核验通过） | 未跟踪文件、无 pre-edit 快照；见 §9.2 |
| t16 改前字节 | `unverified` | 无留档；见 §9.4 |
| advisory `NOTE` 行数 | 22（本文实测） vs 19（reports/63 §1.3） | 计数口径差异，非门禁结果差异 |
