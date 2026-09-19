# 65 — 后续工作独立验证报告（T5：审计脚本 + SPEC v1.7.1 + C2 句读 + C3/C4 测量）

> **verdict：pass**
> 验证者：`verifier`（独立于 T2/T3/T4/T8 执行方）· 2026-09-19 · 仓库 `code/webrtc-demo`，HEAD `1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae`（本轮**不 commit**，全部为工作树改动）
> 写作纪律：本文数值全部为本轮现场实测（未沿用任何转述值）；临时产物路径只写在 fenced 代码块内（[reports/64-followup-requirements.md](64-followup-requirements.md) §7 的 P2 书写约束）。
> 判据来源（优先级从高到低）：**captain 终局处置（口述）+ [reports/64-followup-requirements.md](64-followup-requirements.md) 顶部 `CAPTAIN RULING` 段** ＞ 该文件 §2（审计脚本 Z1–Z9）、§3（C1）、§5（C3）、§6（C4）、§7（C5）；与该文件 §0.1/§4.1/§9.3/§9.11 的旧表述冲突时，按 `CAPTAIN RULING` 与**盘面实测**判定。

---

## 0. 结论摘要

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 审计脚本独立复核 + ≥6 项正反例探针 + 判据未放宽 | **pass**（全量 `PASS (79 checks, 0 warnings)` exit 0；自研 **31 次探针运行**，其中 **17 条负例**全部按预期 FAIL 且指向正确 `file:line` 与 `Z-id`） |
| 2 | SPEC v1.7.1 自洽与锚点 | **pass**（版本行/changelog 一致；§9 无轮次编号；V1–V15 在册；checker 抽取的 **35/35** 规则编号在册；E1/§7.6/§5 锚点逐条命中） |
| 3 | 全量门禁与审计 0 FAIL + 跑前跑后指纹一致 | **pass**（`doc-verify.sh: PASS (3912 checks, 2 warnings)` exit 0；`i18n-audit.sh: PASS (79 checks, 0 warnings)` exit 0；19 路径 sha256+mtime 全量一致） |
| 4 | C3 裸克隆门禁实测 | **pass（含一条方法论观察）**：冻结命令落点 → `FAIL (4 failures, 5 warnings, 3912 checks)` exit 1；落点控制在同一工作区外 → `FAIL (39 failures, …)`。结论：门禁是**工作区门禁**，裸克隆在任何落点都无法全绿 |
| 5 | C4 联合指纹规范配方与实测值 | **pass**：19 路径终值 `40d9fa70db57add32376157e181b1fe57c8fa250fe7a06253905d5a794adfa55`（跑前=跑后）；18 路径机制自检值 `1bf12697c411ba292c62234d5f6fb826eebe98ed8423277668794382b0a5d901`；旧值 `3bb89149…` 标注**不可复现**，另一个旧值 `fff4597c…` 本轮**已复现并解释**（见 §8.4） |
| 6 | 产出本报告 | **pass**（本文件即唯一写入；写入后门禁与审计复跑仍 0 FAIL） |

无 blocking finding。4 条观察项见 §9，均**不影响** verdict。

---

## 1. 判据与治理链（如实记录）

### 1.1 C2 的最终盘面（captain 终局处置）

* `doc/design/zh-CN/02-architecture.md` **相对 HEAD 零差异（中程改动已还原）**：`git diff --quiet` 退出码 0、numstat 无输出、sha256 = `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42` / 219 行 / 15877 B。
  * 中程形态 A（`…中的已否决（rejected）旋转烘焙实验。`）**曾被 t4 交付**（sha256 `96a733a4a2c5b237e8726ba242d7aef460ee0bb7dcdd226898790c5336525a0b`），随后按 `reports/64` **第 6 版**（`883f8992695b1828462531f740b8195bdaed0e052491d9672d35505b22e9056d` / 344 行）§4.2 第 4 条与 §10 第 1 条的 `git checkout -- doc/design/zh-CN/02-architecture.md` 还原指令被撤销回 HEAD 原文；captain **接受该结果**、不再重做（A 与 HEAD 原文均合规）。
  * → 这是「t4 台账称形态 A / 盘面为 HEAD 原文」的 **criteria-vs-applied 差异（已由 captain 裁定、保留）**观察项，见 §9.1。
* `doc/design/zh-CN/01-requirements.md:328` 保持**已交付形态 D′**：`| D-4 | 历史文本「旋转已烘焙进 I420」，上游源码已证伪（disproven） | 对采集路径的错误心智模型 |`（sha256 `a4271a402396099f56ca952e6d489100b13027375e884751d89b900644c2c8d5` / 333 行 / 25754 B；改前 = HEAD 版 `5af1985bfad01df02d834ef0c325fd01d9e60263791301d4688b6bff5dbb1e19`）。
  * `reports/64` 定版 §4.2 的**形态 D**（`| D-4 | 依据上游源码，历史文本…已证伪（disproven） | …`）**未采纳**；captain 裁定不再对同一行做第三次改写。D 与 D′ 均满足 §4.4 的连续定译串判据。
  * → 「D′ vs §4.2 形态 D」的差异按 captain 指示记 **criteria-vs-applied 观察项**，见 §9.2；**不**记为偏离/finding，verdict 不受影响。
* t4/t8 均**不重开**；`reports/64` 停改。

### 1.2 `reports/64` 修订链（写入治理记录，保留历史不篡改）

```text
e5e2e116…(284 行) → 76d97797…(305 行) → 9db6c582…(307 行) → 283f3794…(342 行)
→ 2cf5704b… → 883f8992…(344 行) → 37904ea7…（+ captain 顶部 CAPTAIN RULING 段）
```

* 本轮 T5 claim 时盘上定版实测 = `5c1d96d4baf2e5fdc0b8261e891fbeecb4898f72b96e181132ca13109d1f7823` / 356 行 / 39703 B（含顶部 `CAPTAIN RULING`）。
* 其他治理事实：**t4 曾以 blocker failed 收口**（原始派单把「02 篇的 D-4 行」当成一处；`doc/design/zh-CN/02-architecture.md` 内无 `D-4`），后经 `reports/64` 修订版 §0.1/§4.1 把 02:194 纳入 C2 并**重开**落地；t8 全程保持已交付形态；**t9/t10/t11 创建后未使用**（t4 重开后 t5/t6/t7 依赖链恢复）。

---

## 2. 盘面冻结（T0）

claim 时刻（2026-09-19T15:22:00Z）实测：

```text
git rev-parse HEAD = 1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae
git status --porcelain:
 M doc/design/SPEC.md
 M doc/design/zh-CN/01-requirements.md
?? reports/64-followup-requirements.md
?? scripts/i18n-audit.sh
```

19 路径（checker + 17 i18n 工件 + 本轮新增审计脚本）的 sha256 明细（跑前 = 跑后，逐行可核）：

```text
6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59  scripts/doc-verify.sh
38c7ada64330fee0700f20ab1e2aeadd7d94618085c7187d247c7d34224bbb2c  README.md
72b7a3c45ff87d9ecb45e8579a83be10b3132ba63fa802ad8ef87970a54262b8  README.en.md
b652775bc04d244d0ac37e222f01bf8ddd60d8ffc02f33ce4a485d65eac474cb  doc/design/README.md
44cda01a35f5c663d47844347282c55a823a5da8521765b7c4e4a1937dc7feeb  doc/design/README.en.md
b3368522392205dd02073a8adf472566b58fad8234de96fbf69fcbc801c369e5  doc/design/SPEC.md
55e9b8a52722e08a78e6843257523bf3954c469daa1aef26921ab079a80f8387  doc/design/01-requirements.md
6a2fc862af61d0511115e47d50c2065f29d93912f4feacb88fa6847d567d1be8  doc/design/02-architecture.md
097bbf2393b42df45973b866529d02d007411264494aa988949be41f3e493584  doc/design/03-app-architecture.md
ca79073949c806d3bee82ab95768d88e87f3cdad0b109374acde950454c594a0  doc/design/04-signaling-service.md
042b79cdf53e6232d338883535368fdf8029ae45dd74a2ee9f958615cfdb2e3f  doc/design/05-protocols.md
51e3eb34776d0f433f6d32cb945852697fe15a1f106857fa878e90f96e851007  doc/design/zh-CN/GLOSSARY.md
dd8911469c31a71cb9e407e10ed2ca0018b422591affb68351ac4ba12be742c7  doc/design/zh-CN/SPEC-guide.md
a4271a402396099f56ca952e6d489100b13027375e884751d89b900644c2c8d5  doc/design/zh-CN/01-requirements.md
716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42  doc/design/zh-CN/02-architecture.md
7b923659876b77c4409bbb854b24b760cceff6499488bef202c7fed80ebefb48  doc/design/zh-CN/03-app-architecture.md
36d180bc03f1f2520168516d1488ef91307f49d6c4aa9c01fa0b7db8867f713a  doc/design/zh-CN/04-signaling-service.md
4acd7b70d37ac121d4e7b0a40e630c491fb4dc76664049d89bb31f30195d0773  doc/design/zh-CN/05-protocols.md
f93da7d5156edad9612740a30b952e5cf11e794eec57c89ec6b2b0d1c29fd82d  scripts/i18n-audit.sh
```

**硬约束复核**：`scripts/doc-verify.sh` = sha256 `6c62591a…1eb59` / **919 行 / 41673 B / mode 444**，跑前跑后逐字节不变；本轮无任何成员改写 checker。审计脚本 `scripts/i18n-audit.sh` = `f93da7d5…fd82d` / 32425 B / 810 行 / mode 755，与 T2 交付自述一致。

---

## 3. 审计脚本独立复核（验收项 1）

### 3.1 全量

```text
$ bash scripts/i18n-audit.sh
i18n-audit.sh: PASS (79 checks, 0 warnings)      # exit 0
```

* 两次连续运行 stdout **逐字节相同**（sha256 `1af26729ee6eec9681b471a13cdf5a4536415324222a4eaef823bc36d607a203`），与 T2 的确定性声明一致。
* 全量输出共 39 行 NOTE，0 WARN，0 FAIL；其中 Z4 命中合计 **44**（01:34 / 02:4 / 03–05:0 / GLOSSARY:6 / SPEC-guide:0），与 [reports/64](64-followup-requirements.md) §2.6 基线一致；Z3 = 74 行 × 4 列；Z5 状态词行 = 2。
* 审计脚本的临时目录取自 `mktemp -d "${TMPDIR:-/tmp}/i18n-audit.XXXXXX"` 并 `trap ... EXIT` 清理，**不向仓库写入任何文件**（实测跑前跑后 19 路径 sha256 与 mtime 全等，见 §6）。

### 3.2 自研探针矩阵（31 次运行；全部在仓库外的临时副本上）

副本构造：`git clone --depth 1 --no-hardlinks --no-recurse-submodules` + 覆盖工作树的 SPEC.md 与 zh-CN/01，**对照组 P0 = PASS / exit 0**，证明副本与仓库同判。每条负例都有「篡改确已生效」guard。

| 探针 | 篡改 | 期望 | 实测（exit / FAIL 行） | 判定 |
|---|---|---|---|---|
| P0 | 无（对照） | PASS / 0 | 0 / `PASS (79 checks, 0 warnings)` | ✅ |
| P1 | 中文默认页切换器标签改措辞 | `FAIL[Z1]` @zh-CN/05:1 | 1 / `doc/design/zh-CN/05-protocols.md:1 → FAIL[Z1] switcher label does not match the frozen 中文（默认） form` | ✅ |
| P2 | 英文页反向链接指向别的文件 | `FAIL[Z1]` @en/02:1 | 1 / `doc/design/02-architecture.md:1 → FAIL[Z1] switcher target resolves to doc/design/zh-CN/03-app-architecture.md, not the counterpart …` | ✅ |
| P16 | 中文页切换器链接指向错误 | `FAIL[Z1]` @zh-CN/04:1 | 1 / `…zh-CN/04-signaling-service.md:1 → FAIL[Z1] switcher target resolves to doc/design/05-protocols.md, not the counterpart …` | ✅ |
| P3 | Y-1 文件逐字声明改写（英文原文→英文版） | `FAIL[Z2]` | 1 / `…zh-CN/03-app-architecture.md:1 → FAIL[Z2] translation disclaimer is not present exactly once …` + GLOSSARY 全仓计数 5≠6 | ✅ |
| P14 | 非 Y-1 文件新增第 7 处声明 | `FAIL[Z2]` | 1 / `README.md:2 → FAIL[Z2] translation disclaimer appears outside the §4 Y-1 list` + 全仓 7≠6 | ✅ |
| P4 | `席位`→`座位` | `FAIL[Z5]` @zh-CN/04:12 | 1 / `…zh-CN/04-signaling-service.md:12 → FAIL[Z5] banned translation 《座位》 appears in a translated page` | ✅ |
| P5b | `（disproven）`→`(disproven)` | `FAIL[Z4]` | 1 / `…zh-CN/01-requirements.md:328 → FAIL[Z4] a status word in Chinese prose must use full-width parentheses` | ✅ |
| P5c | `已被上游源码证伪（disproven）`（`已`/`证伪` 被分开） | `FAIL[Z4]` | 1 / `…:328 → FAIL[Z4] status word inside full-width parentheses is not preceded by the frozen translation 《已证伪》` | ✅ |
| P17 | `已证伪（disproven）`→`证伪（disproven）` | `FAIL[Z4]` | 1 / 同上邻接判据命中 | ✅ |
| P6a | 行号改越界（`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:858` → 99999） | `FAIL[Z6]` | 1 / `…zh-CN/01-requirements.md:48 → FAIL[Z6] citation … is out of bounds (target has 1045 lines)` | ✅ |
| P6b | 删除一处 `path:LINE` 引用 | `FAIL[Z6]` | 1 / `…zh-CN/01-requirements.md:48 → FAIL[Z6] citation … is missing from the counterpart` | ✅ |
| P7a | 删除行内 code-span 反引号 | `FAIL[Z7]` | 1 / `doc/design/04-signaling-service.md:11 → FAIL[Z7] inline code span \`signaling/\` is missing from the counterpart` | ✅ |
| P7b | 删除一个 h2 | `FAIL[Z8]` | 1 / `doc/design/05-protocols.md:1 → FAIL[Z8] h2 headings count is 9, the counterpart … has 8` | ✅ |
| P18 | §6 术语表删一行（74→73） | `FAIL[Z3]` | 1 / `…zh-CN/GLOSSARY.md:1 → FAIL[Z3] §6 table has 73 data rows, expected 74` | ✅ |
| P19 | 插入旧式切换器残留 `语言 / Language:` | `FAIL[Z9]` | 1 / `…zh-CN/05-protocols.md:3 → FAIL[Z9] legacy switcher 《语言 / Language:》 still present` | ✅ |
| P15 | 根目录建 `README.zh-CN.md` | `FAIL[Z9]` | 1 / `README.zh-CN.md:1 → FAIL[Z9] language-suffixed copy of an entry/index page must not exist` | ✅ |
| P8b | `--only` 列出自身被篡改 | `FAIL[Z2]` 归因被列文件 | 1 / `…zh-CN/03-app-architecture.md:1 → FAIL[Z2] …` | ✅ |

负例合计 **17 条**，覆盖验收要求的全部类别：切换器标签（P1/P16）、反向链接（P2）、译文声明（P3/P14）、术语用词与状态词邻接（P4/P5b/P5c/P17/P18）、`path:LINE` 引用（P6a/P6b）、code-span 与结构计数（P7a/P7b/P19/P15）。

行为类（不要求 FAIL）：

| 探针 | 场景 | 实测 | 判定 |
|---|---|---|---|
| P8 | `--only zh-CN/03`，对端 05 被篡改 | 0 / `PASS (8 checks, 0 warnings)` | ✅ 隔离成立 |
| P23 | `--only README.md`，英文对端被篡改 | 0 / `PASS (7 checks, 0 warnings)` | ✅ |
| P10 | `--only doc/design/zh-CN`（目录递归） | 0 / `PASS (45 checks, 0 warnings)` | ✅ |
| P20 | `--only GLOSSARY.md` | 0 / `PASS (2 checks, 0 warnings)`（Z1/Z2 豁免） | ✅ |
| P21 | `--only SPEC-guide.md` | 0 / `PASS (3 checks, 0 warnings)`（Z1 豁免、Z2/Z5 生效） | ✅ |
| P22 | 移走 `GLOSSARY.md`（环境错误） | **2** / stderr `i18n-audit.sh: missing required normative source: doc/design/zh-CN/GLOSSARY.md` | ✅ 2 未退化为 0 |
| P9/P9b | `--only` 目标不存在 | **2** / stderr `i18n-audit.sh: --only target does not exist: …nope.md` | ✅ |
| P11 | 未知参数（`bash scripts/i18n-audit.sh --bogus`） | **2** / stderr `i18n-audit.sh: unknown argument: --bogus` | ✅ |
| P12 | 帮助选项（`bash scripts/i18n-audit.sh -h`） | 0 / 打印 `# Usage` | ✅ |
| P13 | 确定性（连续两次全量） | 两次 sha256 相同 | ✅ |

### 3.3 「判据未被放宽」的独立核对

* 9 个检查项全部有 `fail Z<id>` 出口：Z1×10、Z2×3、Z3×3、Z4×1、Z5×2、Z6×4、Z7×2、Z8×2、Z9×2 处（逐处探针已证可达）。
* 无「发现即跳过」、无 FAIL→WARN 降级辅助函数（脚本内只有 `fail`/`warn`/`note` 三个出口，warn/note 不参与退出码）；全量退出码仅由 `FAILURES > 0` 决定（`exit 1`），否则 `exit 0`。
* Z7 用 `cut -f1 | sort` + `comm` 做**多重集**比较（重复项成对匹配），Z6 为集合比较 + 目标存在 + 行号在界，Z8 为四项计数逐项比较——与冻结判据同口径，且负例 P6b/P7a 证明删除任一侧差异必 FAIL。
* 一条**观察**（非缺陷）：`被上游源码已证伪（disproven）` 中 `已证伪（disproven）` 仍连续，Z4 邻接判据成立 → 脚本（正确地）不判 FAIL（P5d，exit 0）。`reports/64` §4.2/§2.2 Z4 注释把这串称为「禁止形态／被打断」，措辞不精确；真正被 Z4 抓到的是 `已被上游源码证伪（disproven）` 一类（P5c）。见 §9.3。

---

## 4. SPEC v1.7.1 自洽与锚点（验收项 2）

### 4.1 身份与改动面

* `doc/design/SPEC.md` = sha256 `b3368522392205dd02073a8adf472566b58fad8234de96fbf69fcbc801c369e5` / **758 行** / 67976 B（757 → 758）。
* `git diff --unified=0 -- doc/design/SPEC.md` 恰 **3 个 hunk**；numstat = `3 2`：
  1. 第 3 行 `v1.7.0` → `v1.7.1`（同行的 `**frozen**`、owner、`Task id: t8 (attempt 1)`、日期逐字节不变）；
  2. 第 741 行删除 `t7, `：`The independent verification task (t7, owner \`verifier\`) …` → `The independent verification task (owner \`verifier\`) …`；第 742 行不变；
  3. 第 758 行（新增 changelog 行）经 `cmp` 与冻结文本**逐字节相同**：
     `| 1.7.1 | 2026-09-19 | **Editorial revision:** §9's verification-duty sentence is rewritten in role-based form (owner \`verifier\`, no round number). No rule changes meaning. |`

### 4.2 自洽性逐条

| 检查 | 实测 |
|---|---|
| 版本行 vs changelog | 头部 `**frozen** v1.7.1`；changelog 末行 = 1.7.1（2026-09-19），无 1.7.2；一致 |
| §9 无轮次编号 | §9（第 730–742 行）内 `t[0-9]+` 命中 **0**；措辞为角色式 `(owner \`verifier\`)` |
| V1–V15 全部在册 | §7 规则表 `^\| V<n> ` 唯一命中 V1…V15 各 1 次（V8 另在 §7.4 归属表出现 1 次，非重复注册） |
| 被引规则编号在册（35 项） | 从冻结 checker 抽取 `\b[VRCETPBAJ][0-9]{1,2}\b` 唯一编号 = **35 个**（A1 A11 A2 A5 A6 C3 C4 C7 C8 E4 P1 P2 P4 P5 R1 R11 R12 R3 R5 R8 T1 T5 T6 V1 V11 V13 V14 V15 V2 V3 V4 V5 V6 V7 V8）；在 SPEC v1.7.1 中 **35/35 存在**，无未登记引用 |
| E1 中文豁免 | 第 407–411 行：E1 限定 `doc/design/**` 英文文档；中文默认入口/索引与 `doc/design/zh-CN/**` **豁免**，其写作规则为 `doc/design/zh-CN/GLOSSARY.md` |
| §7.6 读法 | 第 688 行 `### 7.6 Bilingual checks V14/V15 …` 仍在；V14/V15 注册于第 440/441 行，失败文本 `missing language switcher` / `missing translation disclaimer` 逐字在位 |
| §5 模板位移 | 第 365 行 `**Language-switcher offset.** A language switcher line may precede the block above: it must sit inside the file's …` 仍在；R12/R13/R14 位于第 66/72/75 行 |
| §9 第 3 条按语言分治 | 第 736–738 行：英文文档走 E1 + §3 术语；中文默认页与 `zh-CN/**` 走 GLOSSARY 且满足 V14/V15 |

### 4.3 版本递增后的**锚点位移**说明

v1.7.0 → v1.7.1 只改第 3 行、第 741 行（**同行长度变化，行数不变**），新增行在文件**末尾**（758）——因此第 1–757 行**全部保持原位**，§5/§6/§7.6/§9 等任何**按行号引用**的锚点位移为 **0**。工程上仍以章节号引用为准（SPEC C9：以章节而非行号引用本规范），故本轮版本递增不产生锚点漂移。

---

## 5. 四个改动对象：改前 → 改后摘要与验收结论

| # | 对象 | 改前 | 改后 | 相对 HEAD | 结论 |
|---|---|---|---|---|---|
| 1 | `scripts/i18n-audit.sh`（T2） | 不存在 | 新增 `f93da7d5…fd82d` / 32425 B / 810 行 / mode 755 | untracked（新增） | **pass**：全量 PASS 79/0 exit 0；31 次探针含 17 负例全部按预期 FAIL；`--only`/退出码/输出格式/确定性均符合 §2.1–§2.3 |
| 2 | `doc/design/SPEC.md`（T3） | `a5724a40…` / 757 行 / 67804 B（v1.7.0） | `b3368522…` / 758 行 / 67976 B（v1.7.1） | 3 hunk / numstat `3 2` | **pass**：§3.1 三处逐字落地；§9 无轮次编号；35/35 编号在册；锚点无位移 |
| 3 | `doc/design/zh-CN/02-architecture.md`（T4，终局为**相对 HEAD 零差异（中程改动已还原）**） | `716552cd…` / 219 行 / 15877 B | 同左（HEAD 原文；`git diff` 为空） | 0 hunk / numstat 空 | **pass（按 captain 终局处置）**：02:194 = `  doc/design/08-issues-and-solutions.md 中已否决（rejected）的旋转烘焙实验。`，连续定译串 `已否决（rejected）` 完整、全角标点；中程形态 A 已按第 6 版还原指令撤销（§1.1、§9.1） |
| 4 | `doc/design/zh-CN/01-requirements.md:328`（T8） | `5af1985b…` / 333 行 / 25754 B | `a4271a40…` / 333 行 / 25754 B | 1 hunk / numstat `1 1` | **pass**：逐字为 `\| D-4 \| 历史文本「旋转已烘焙进 I420」，上游源码已证伪（disproven） \| 对采集路径的错误心智模型 \|`；`，` 全角；连续定译串 `已证伪（disproven）` 完整（Z4 邻接成立）；行序/列数/行数不变 |

---

## 6. 全量门禁与跑前跑后指纹（验收项 3）

```text
$ bash scripts/doc-verify.sh
doc-verify.sh: PASS (3912 checks, 2 warnings)     # RAW EXIT=0
$ bash scripts/i18n-audit.sh
i18n-audit.sh: PASS (79 checks, 0 warnings)       # exit 0
```

* 2 条 warning 为基线既有 A10 示例（被文档引用的构建产物示例路径的 typo-suspect），与本轮改动无关。
* 跑前（claim 时）与跑后（全量门禁 + 全量审计各跑完、本报告写完之后）**19 路径逐个 sha256 + mtime + 字节数完全一致**（§2 明细），联合指纹亦一致（§8.1）。
* 本报告自身也经门禁校验：`bash scripts/doc-verify.sh --only reports/65-followup-verification.md` → `PASS (50 checks, 0 warnings)` / RAW EXIT=0。
* 说明：本报告本身不在 19 路径内（`reports/**` 按 [reports/64](64-followup-requirements.md) §6.1 刻意排除——报告边写边变，纳入即不可复现）。

---

## 7. C3 裸克隆门禁实测（验收项 4）

### 7.1 冻结命令（[reports/64](64-followup-requirements.md) §5.1 指定落点）

```bash
REPO=/data/dsh/home/workspace/code/webrtc-demo
CLONE=/data/dsh/home/workspace/tmp/c3-bare-clone
rm -rf "$CLONE"
git clone --depth 1 --no-recurse-submodules "$REPO" "$CLONE"
git -C "$REPO"  rev-parse HEAD
git -C "$REPO"  status --porcelain
git -C "$CLONE" rev-parse HEAD
test -e "$CLONE/scripts/i18n-audit.sh"; echo "audit-in-clone=$?"
cd "$CLONE" && bash scripts/doc-verify.sh; echo "RAW EXIT=$?"
```

实测：

```text
MAIN_HEAD  = 1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae
CLONE_HEAD = 1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae     # 相等
main status --porcelain:
 M doc/design/SPEC.md
 M doc/design/zh-CN/01-requirements.md
?? reports/64-followup-requirements.md
?? scripts/i18n-audit.sh
audit-in-clone=1                                          # 克隆内无本轮新增审计脚本（未提交）
doc-verify.sh: FAIL (4 failures, 5 warnings, 3912 checks)
RAW EXIT=1
```

对照（同一命令在作者工作区）：`doc-verify.sh: PASS (3912 checks, 2 warnings)` / RAW EXIT=0。

逐条失败（4 条，全部为**类别 ② submodule 内部引用**）：

```text
doc/design/08-issues-and-solutions.md:311 → cited file does not exist: `third_party/libwebrtc-src/sdk/android/src/jni/video_encoder_wrapper.cc`
doc/design/08-issues-and-solutions.md:311 → cited file does not exist: `third_party/libwebrtc-src/video/video_stream_encoder.cc`
doc/design/08-issues-and-solutions.md:314 → cited file does not exist: `third_party/libwebrtc-src/video/video_stream_encoder.cc`
doc/design/08-issues-and-solutions.md:468 → cited file does not exist: `third_party/libwebrtc-src/video/video_stream_encoder.cc`
```

归类依据：`git ls-files -s third_party/libwebrtc-src third_party/libvpx-src` → 两条 `160000` gitlink（`be0e9008…` / `d2413e2c…`）；克隆时未初始化 submodule，只有空目录，故所有落在 submodule 内的引用必然「不存在」。分类统计（冻结落点）：**① 工作区证据缺失 0 ／ ② submodule 内部引用 4 ／ ③ env\*.sh 变体 0 ／ ④ 其他 0**，合计 4（+5 warnings）。

### 7.2 落点控制（**重要方法论观察**）

把同一 HEAD 克隆到工作区**外**再跑同一门禁：

```bash
rm -rf /tmp/c3-iso-clone
git clone --depth 1 --no-recurse-submodules /data/dsh/home/workspace/code/webrtc-demo /tmp/c3-iso-clone
cd /tmp/c3-iso-clone && bash scripts/doc-verify.sh; echo "RAW EXIT=$?"
```

```text
doc-verify.sh: FAIL (39 failures, 5 warnings, 3912 checks)   # RAW EXIT=1
```

39 条失败的确定性归类：

| 类别 | 条数 | 证据形态 |
|---|---|---|
| ① 工作区证据缺失（P2 `missing workspace path`） | 29 | `fix the path (workspace root = /)`；payload 分布：`env.sh` ×9、`env-go.sh` ×8、`env-container.sh` ×8、`tmp/t47b-captain-build.sh` ×1、`tmp/probe-forms-matrix-writer-ops.md` ×1、`../env.sh` ×1、`../env-container.sh` ×1 |
| ② submodule 内部引用 | 4 | `cited file does not exist: third_party/libwebrtc-src/…` |
| ③ env\*.sh 变体（工作区根回退后重复失败） | 33 | 27 条 P2（上表 env\* 系列，含上级目录形式）+ 6 条 `cited file does not exist`（07-build-and-deploy.md 的 23/27/28/90/91/180 行；原始 payload 见下方 fenced 块） |
| ④ 其他 | 0 | 无 |

③ 那 6 条 `cited file does not exist` 的原始形态（payload 为「双斜杠 + env 脚本名」的绝对路径型引用，故被规则判为引用失败）：

```text
doc/design/07-build-and-deploy.md:23,27,28,180 → cited file does not exist: 双斜杠 + env.sh
doc/design/07-build-and-deploy.md:90,91        → cited file does not exist: 双斜杠 + env-container.sh
```

> 注：① 与 ③ 在口径上重叠（env\*.sh 既是 P2 证据缺失、又按类别 ③ 单列）；去重后的确定性划分 = ② 4 + ③ 33 + 「非 env 的 ①」2 + ④ 0 = 39。冻结落点那次 = ② 4 + 其余 0 = 4。

**机理（实测解释，非推测）**：checker 第 80 行 `WORKSPACE_ROOT=$(cd "$REPO_ROOT/../.." && pwd)`，即以「仓库根的上两级」为工作区根。工作区真实仓库位于 `…/workspace/code/webrtc-demo`，上两级 = `…/workspace`（＝真实工作区根）；而把克隆放在 `…/workspace/tmp/c3-bare-clone` 时，其上两级**同样是** `…/workspace`，于是 P2 证据（`env*.sh`、`tmp/**`）恰好存在 → P2 全过、只剩 4 条 submodule 失败。克隆落到工作区外（如系统临时目录）时上两级退化为 `/`，P2 证据全部缺失 → 29 条 P2 失败并连带 6 条 `//env*.sh` 引用失败。

### 7.3 结论（取代 [reports/62](62-i18n-delivery.md) §10 对旧实测的引用）

* 门禁是**工作区门禁**，不是仓库门禁：任何**裸克隆**（只含 HEAD 提交、不含本轮未提交改动、不含 submodule 内容、不含仓库外 P2 证据）都**无法全绿**。
* 在 HEAD `1b5bb78d…` / checker `6c62591a…` 上，本轮实测值（**取代** [reports/60](60-github-push-runbook.md) §7 在 `8ac1a14d…` 上的 33/4/2466 转述值）：
  * 冻结落点（[reports/64](64-followup-requirements.md) §5.1）：**4 failures / 5 warnings / 3912 checks，exit 1**，全部为 submodule 内部引用；
  * 工作区外落点：**39 failures / 5 warnings / 3912 checks，exit 1**（① 29 + ② 4 + ③ 6）。
* 两个落点**都**证明同一结论；差异只来自 checker 的 `WORKSPACE_ROOT` 定义。报告下游（[reports/62](62-i18n-delivery.md) §13 的 `unverified` 项）应改用**冻结落点值**并把落点敏感性写明，以免读者误以为「裸克隆只剩 4 条」是普遍结论。

---

## 8. C4 联合指纹规范配方（验收项 5）

### 8.1 定义与配方（[reports/64](64-followup-requirements.md) §6.1/§6.2）

联合指纹 = `sha256( 排序后的「sha256(file)␠␠file」行序列 )`，输入为**固定 19 个路径**（checker + 17 i18n 工件 + 本轮新增审计脚本），**不含 `reports/**`**：

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

### 8.2 实测值（跑前 = 跑后）

```text
C4 终值（19 路径，跑前） = 40d9fa70db57add32376157e181b1fe57c8fa250fe7a06253905d5a794adfa55
C4 终值（19 路径，跑后） = 40d9fa70db57add32376157e181b1fe57c8fa250fe7a06253905d5a794adfa55
```

* 跑前 = 跑后 = **逐字符一致**；且把输入清单打乱后用同一配方复跑，仍得同一值（配方与参数顺序无关，实测两次）。
* 19 行 `sha256␠␠path` 明细已由 §2 表逐行给出，可逐行核对。
* 该配方**自包含**（输入清单写在命令内），不依赖任何先前生成的临时文件。

### 8.3 18 路径机制自检值（对照）

在 T3/T4 落地**之前**（SPEC 仍 v1.7.0、zh-CN/01 仍 HEAD 版）用同一配方对 18 路径（不含尚待新增的审计脚本）实测：

```text
18 路径（预设清单顺序） = 1bf12697c411ba292c62234d5f6fb826eebe98ed8423277668794382b0a5d901
18 路径（输入打乱顺序）= 1bf12697c411ba292c62234d5f6fb826eebe98ed8423277668794382b0a5d901
```

与 [reports/64](64-followup-requirements.md) §6.3 登记的机制自检值**逐字符一致**，独立证明配方机制正确、且与输入顺序无关。**该值不是 C4 终值**（不含审计脚本、不含 T3/T4 改动）。

### 8.4 旧值处置（不篡改历史）

* `3bb8914931742191aab5e7f9e49d3b4f`（[reports/63](63-i18n-verification.md) 记录的旧联合指纹）：**不可复现**。生成命令未文档化、输入清单未固定、算法为 md5。本轮用 **10 种候选公式**（未排序/排序的 `sha256sum|md5sum`、绝对路径、只取摘要、摘要拼接、文件内容拼接、`./` 前缀、去掉 SPEC 的 17 路径、加 reports/54 的 20 路径、`sha256sum|sha256sum`）在同一 18 路径**改动前**树上逐一试算，**无一命中**。就地标注「不可复现，已被规范配方取代」，历史记录原文保留。
* `fff4597c64850695dd6f47db711f04a8`（[reports/62](62-i18n-delivery.md) §6 记录的另一值）：**本轮已复现并解释**——它等于「在同一 18 路径**改动前**树上、按 §6.2 清单的**未排序**顺序执行 `sha256sum … | md5sum`」，实测命中（`fff4597c64850695dd6f47db711f04a8`）。作为对照，在同一树上按**规范配方**（两端 `LC_ALL=C sort` + **sha256**）得到的却是 `1bf12697…`；在当前（改动后）树上该 md5 公式得 `b2864942e217c0169543be42bc460401`——即该 md5 值**只属于改动前树**，与 C4 终值无关。
* 两者**均不**被 §6.2 规范配方复现；规范配方不能、也不得据以宣称与旧值一致。因此 [reports/62](62-i18n-delivery.md) §13「联合指纹生成命令 `unverified`」可收窄为：**仅 `3bb89149…` 生成命令不可复现；`fff4597c…` 已解释（非规范配方的 md5 变体，属改动前树）**。这不改写历史值，只更新解释。

---

## 9. 观察项（全部不影响 verdict）

### 9.1 形态 A「交付后被撤销」（criteria-vs-applied，已由 captain 裁定）

**criteria**：[reports/64](64-followup-requirements.md) 中程第 4 版（`283f3794…`）§4.1 冻结形态 A 并要求 T4 逐字采用，t4（attempt 2）据此**已交付** `96a733a4…`（02:194 = `…中的已否决（rejected）旋转烘焙实验。`）。
**applied**：第 6 版（`883f8992…`）§4.2 第 4 条 / §10 第 1 条改为「02 零改动、须 `git checkout` 还原」，随后盘面回到 HEAD 原文 `716552cd…`。
**处置**：captain 终局接受现状（02 保持 HEAD 原文、不再重做），两种形态均合规。本报告据盘面判定 02 **pass**，并将该差异记为观察项，**不判** needs_revision。

### 9.2 D′ 与 §4.2 形态 D 的差异（criteria-vs-applied，已由 captain 裁定）

**criteria**：[reports/64](64-followup-requirements.md) §4.2 写明落点 B 冻结形态 **D**（`| D-4 | 依据上游源码，历史文本「旋转已烘焙进 I420」已证伪（disproven） | … |`），并称「T8 需再改一次」。
**applied**：盘面为 **D′**（t8 已终态交付的形态：`…」，上游源码已证伪（disproven）…`）。
**裁定**：captain 明确 **D 提案未采纳**、D′ 判合规通过、不再对同一行改写；D 与 D′ 均满足 §4.4 的连续定译串判据（`已证伪（disproven）` 完整、Z4 邻接成立）。故记录为观察项，**不判** needs_revision，也不记为偏离。

### 9.3 `被上游源码已证伪（disproven）` 的措辞不精确（文档措辞，非脚本缺陷）

[reports/64](64-followup-requirements.md) §4.2 与 §2.2 的 Z4 反例注释把 `被上游源码已证伪（disproven）` 称为「被打断」的禁止形态。实测（探针 P5d）：该串中 `已证伪` 与 `（disproven）` 仍**紧邻**，Z4 邻接判据成立，脚本**正确地**不判 FAIL（exit 0）；真正被 Z4 抓到的是 `已被上游源码证伪（disproven）` 一类把 `已`/`证伪` 分开的形态（探针 P5c，FAIL）。这是**判据文本的表述问题**，不是脚本放宽判据（§3.3 已逐项核对）。建议后续修订（本轮不改冻结件）把该表述拆成「打断定译串（Z4 可判 FAIL）」与「语序生硬（风格项，不判 FAIL）」两类。

### 9.4 Z6 NOTE 计数与 [reports/64](64-followup-requirements.md) §2.6 登记值不完全一致（NOTE 口径，非判据）

实测 Z6 去重/出现数：01 `69/76`、02 `39/45`、03 `98/213`、04 `113/243`、05 `87/182`；§2.6 登记为 01/02/04/05 = `73/40/213/206/182`。03 的 213 与 05 的 182 吻合，01/02/04 不吻合；T2 也报告无法用任何候选口径复现 `73/40/206`。Z6 的**冻结判据**（集合相等 + 每条目标存在 + 行号在界）已由探针 P6a/P6b 证明可触发且未被放宽，NOTE 只是信息输出、不影响退出码，故记为观察项。

---

## 10. 复现命令（全部自包含）

```bash
cd /data/dsh/home/workspace/code/webrtc-demo

# 0) 盘面与硬约束
git rev-parse HEAD ; git status --porcelain
sha256sum scripts/doc-verify.sh ; wc -lc scripts/doc-verify.sh ; stat -c '%a' scripts/doc-verify.sh
sha256sum scripts/i18n-audit.sh

# 1) 审计脚本 + 门禁（均须 0 FAIL）
bash scripts/i18n-audit.sh ; echo "AUDIT=$?"
bash scripts/doc-verify.sh ; echo "GATE=$?"

# 2) SPEC v1.7.1 自洽
sed -n '3p' doc/design/SPEC.md ; tail -1 doc/design/SPEC.md
sed -n '730,742p' doc/design/SPEC.md | grep -nE 't[0-9]+' || echo "§9 无轮次编号"
grep -oE '\b[VRCETPBAJ][0-9]{1,2}\b' scripts/doc-verify.sh | sort -u | wc -l   # 35
git diff --unified=0 -- doc/design/SPEC.md | grep -c '^@@'                     # 3
git diff --numstat -- doc/design/SPEC.md                                       # 3 2

# 3) C2 两处落点
sed -n '194p' doc/design/zh-CN/02-architecture.md
sed -n '328p' doc/design/zh-CN/01-requirements.md
git diff --numstat -- doc/design/zh-CN/02-architecture.md doc/design/zh-CN/01-requirements.md

# 4) 探针（verifier 自研，仓库外副本）
bash /data/dsh/home/workspace/tmp/t5-verify/probe.sh
bash /data/dsh/home/workspace/tmp/t5-verify/probe2.sh

# 5) C3 裸克隆（冻结落点 + 工作区外对照）
REPO=/data/dsh/home/workspace/code/webrtc-demo
CLONE=/data/dsh/home/workspace/tmp/c3-bare-clone
rm -rf "$CLONE" && git clone --depth 1 --no-recurse-submodules "$REPO" "$CLONE"
( cd "$CLONE" && bash scripts/doc-verify.sh ) ; echo "RAW EXIT=$?"
rm -rf /tmp/c3-iso-clone && git clone --depth 1 --no-recurse-submodules "$REPO" /tmp/c3-iso-clone
( cd /tmp/c3-iso-clone && bash scripts/doc-verify.sh ) ; echo "RAW EXIT=$?"

# 6) C4 联合指纹（见 §8.1 的 19 路径命令）
```

---

## 11. 不确定性清单（显式标注）

| 项 | 状态 | 说明 |
|---|---|---|
| `3bb89149…` 的生成命令 | `unverified` | 10 种候选公式未命中；不改写历史，就地标注不可复现（§8.4） |
| `fff4597c…` 的「官方」生成命令 | 部分闭合 | 本轮已复现为「18 路径 × 未排序 × md5」变体（改动前树）；其原始作者语境仍无留档 |
| 探针副本的绝对字节级环境 | 已控制 | 副本为 `git clone --depth 1` + 工作树覆盖；P0 对照组与仓库同判，故差异只来自篡改本身 |
| C3 落点敏感性 | 已实测 | 4 vs 39 的差异机理已由 checker 第 80 行 `WORKSPACE_ROOT` 定义解释，非推测 |
| `reports/64` 后续版本 | 已冻结声明 | claim 时定版 `5c1d96d4…`；captain 声明该文件停改。若日后又被改写，本报告数值不随之失效（判据为盘面实测） |

---

**verdict：pass** — 四项验收全部满足；两条 captain 已裁定的 criteria-vs-applied 差异按观察项记录，不构成 needs_revision；无 blocking finding。
