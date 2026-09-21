# 69 — 第三轮 round-2 独立复审（T20）

> Status: **pass** · Owner: `verifier` · Task: t20 (attempt 2), 2026-09-21
> 复核对象：[reports/54](54-docs-freeze-manifest.md) 与 [reports/62](62-i18n-delivery.md) 的**登记值 vs 盘面**、治理记录完整性、关键结论可复现性；以及两文件自行指定的身份载体（task t19 的 output、交付提交信息、本文件）。
> 上游：[reports/68](68-followup2-review.md)（T17 verdict = needs_revision，findings F1…F5）→ t19（repair-round-2）→ 本单。
> 判据：任务契约 t20 的四项 acceptance；[reports/66](66-followup2-requirements.md) §5 的 E-1…E-10；captain 的 t19 派发单（F4 / F5 / F1' / F3 / 授权措辞项）。
> 书写约定：已存在的仓库内路径写反引号；/tmp 等 HOST 路径与不存在的路径/行号写纯文本。

---

## 0. 结论（verdict = pass）

* **契约四项 acceptance 全部通过**（§1–§4），**F1'…F5 与授权措辞项逐条闭合**（§5），**0 处未解释的登记不一致**（§1）。
* 独立复核期间**零写入**：复核窗口 2026-09-21 23:27:53 → 23:32:10，19 个路径的前后快照除表头时间戳外逐字节相同（§7）。唯一写入 = 本文件。
* **8 项观察（非 finding）**登记于 §6，其中 **O1 需 captain 收口**：已推送的交付提交信息里 `reports/68` 的身份是中途旧值，与提交 blob 实测不一致。
* 判 pass 的理由：盘面与登记值一致、门禁在交付提交上可复现且默认模式强度未被削弱、F 清单全部按登记/措辞口径闭合；未闭合项均为**载体或表述层**，不改变任何被复核报告的技术结论，且已在 §6 给出可执行收口路径。

---

## 1. 验收项①：登记值 vs 现场 `sha256sum`（逐项）

方法：逐行扫描两份报告所有含 64 位十六进制值的表格行，对被引用路径现场实测 `sha256sum` 比对；不一致项再做三层归因（同一行是否登记了后继值 / 是否等于交付提交或其父提交的 blob / 是否落在明示的历史表中）。

| 归因类别 | 处数 | 说明 |
|---|---|---|
| = 现场实测值（现值） | **54** | 现值在每个相关章节均有登记 |
| = 交付提交或父提交的 blob | **17** | `git show 19e2c333:<路径>` 或 `git show 8bb69d2:<路径>` |
| 明示历史值（同行登记后继值，或位于上一轮表内） | **23** | [reports/54](54-docs-freeze-manifest.md) §4「Artefact table」与 §8.3（mtime 2026-09-17 / 2026-09-18 的第二轮值）、§9.3「sha256（§8，superseded）」列、§9.4「= §8.3 原值」列 |
| 无可解析路径的裸值（逐一人工解析） | **8** | 见下表 |
| **未解释的不一致** | **0** | — |

无路径的 8 处，逐个解析结果：

| 值（前 16 位） | 解析结果 |
|---|---|
| 5c1d96d4baf2e5fd | = `reports/64-followup-requirements.md` 实测值 ✓ |
| 8f7ed1a32e62759d | = `reports/65-followup-verification.md` 实测值 ✓ |
| 676d075a067e869e | i18n 改造前的 `scripts/doc-verify.sh`（628 行），报告中标注为「Supersedes」的历史值 ✓ |
| 6c62591af1766985 | = `git show 8bb69d2:scripts/doc-verify.sh`（919 行 / 41 673 B），即交付提交父提交里的 checker 基线值 ✓ |
| d3b80c3a65c4312c | = `reports/63-i18n-verification.md` 实测值 ✓ |
| 1ed8cd80258768a5 | = `reports/61-i18n-verification.md` 实测值 ✓ |
| 2bda1048c8cec8b2 | = 归档件 sha256（`2bda1048c8cec8b2a86b890c767c2a0c68278ec7df02b59a932322d49d32241a` / 17 507 348 B），与工作区归档件实测一致 ✓ |
| 8bb69d260f6d4f5a | = 交付提交的父提交（报告中的「vs HEAD」框架，见 O3）✓ |

**联合指纹 C4（31 路径）独立复现**：按 [reports/66](66-followup2-requirements.md) §7.2 配方（两端 LC_ALL=C sort）在交付树上重跑，得 `053e23bf5ac07e8f532b8d2f0ad6d7ed987a61ca546836b44d950ddbcc6edc29`，与 [reports/54](54-docs-freeze-manifest.md) §10.3 登记值**逐字节相同**；31 条路径逐路径 sha256 前 20 位比对 **0 DRIFT**。

**链接可达性（E-6）**：`reports/54` 15 处相对链接 / 5 个不同目标 / **0 MISS**；`reports/62` 56 处 / 14 个不同目标 / **0 MISS**（T14 记录的「5 + 14」为**不同目标数**，与本次计数口径一致）。

## 2. 验收项②：治理记录覆盖

| 本轮事件 | [reports/54](54-docs-freeze-manifest.md) | [reports/62](62-i18n-delivery.md) |
|---|---|---|
| A：i18n-audit.sh 临时目录残留（mktemp / trap / SIGKILL 边界） | trap ×1 | mktemp ×2、trap ×3、SIGKILL ×6 |
| B：06–11 六篇译文、GLOSSARY §6、配对/声明枚举、索引去 planned | 06–11 ×4、GLOSSARY ×9、planned ×1 | 06–11 ×7、GLOSSARY ×25、planned ×5 |
| C：`--repo-mode` 与 GitHub Actions（`.github/workflows/docs.yml`） | repo-mode ×4、docs.yml ×3 | repo-mode ×12、docs.yml ×2 |
| D：`doc/design/zh-CN/02-architecture.md` 第 194 行润色 | 14 处引用 | 10 处引用 |
| F：归档与清理（`tmp/i18n-audit-probe` → tar.gz） | 归档 ×4、i18n-audit-probe ×7 | 归档 ×5、i18n-audit-probe ×9 |
| 上一轮遗留治理项（reports/64、CAPTAIN RULING、t9/t10/t11 替代链、成员移除记录） | reports/64 ×5、CAPTAIN RULING ×2 | reports/64 ×13、CAPTAIN RULING ×5、stale ×2 |
| 流程偏差：终态后修订 | ×2 | ×2 |

结论：**逐项命中**。唯一未进入两份报告正文的本轮事件 = 平台 `Insufficient Balance (QUOTA)` 中断与 captain 接管落盘；其披露位置为交付提交信息与 t19 的 output（登记为 O7，非阻断）。

## 3. 验收项③：关键结论可复现（4 项，全部现场重跑）

1. **报告对子集门禁**：`bash scripts/doc-verify.sh --only reports/54-docs-freeze-manifest.md reports/62-i18n-delivery.md` → **EXIT=0**，`doc-verify.sh: PASS (314 checks, 1 warnings)`；唯一 warning = `reports/54-docs-freeze-manifest.md:532`（该行 L-9 自指样例的仓库外构建产物路径，typo-suspect、advisory）——与 [reports/62](62-i18n-delivery.md) 第 534 行的登记逐字一致。
2. **全量默认模式门禁**：`bash scripts/doc-verify.sh` → **EXIT=0**，`PASS (4795 checks, 4 warnings)`（4 条均为 `doc/design/09-verification-and-limitations.md` 第 140/147 行与 `doc/design/zh-CN/09-verification-and-limitations.md` 第 134/141 行的既有 advisory typo-suspect）；`bash scripts/i18n-audit.sh` → **EXIT=0**，`PASS (133 checks, 0 warnings)`（Z1 armed 13 / skipped 0、Z2 12 = 12、Z6/Z7/Z8 body armed 11 / skipped 0）。
3. **活树 `--repo-mode`**：`bash scripts/doc-verify.sh --repo-mode` → EXIT=0；`bash scripts/i18n-audit.sh --repo-mode` → EXIT=0。
4. **交付提交的忠实克隆（CI 类比，最强一项）**：`git clone` 交付提交 `19e2c333` 到 /tmp/t20-clone（2 个未初始化 gitlink：`third_party/libvpx-src`、`third_party/libwebrtc-src`，工作树 0 脏项）后顺序执行：
   * `doc-verify.sh --repo-mode` → **EXIT=0**，`repo-mode summary (downgraded: P2=32 P4=0 SUBMODULE=8 ENVSLASH=12; default-mode failures=52; remaining failures=0)`，`PASS (4795 checks, 7 warnings)`；**32 + 8 + 12 = 52 恰好闭合**。
   * `i18n-audit.sh --repo-mode` → **EXIT=0**，`repo-mode summary (downgraded: P2=2 P4=0 SUBMODULE=8 ENVSLASH=12; default-mode failures=22; remaining failures=0)`，`PASS (133 checks, 0 warnings)`；**2 + 8 + 12 = 22 恰好闭合**。
   * 克隆内**默认模式仍硬失败**：`doc-verify.sh` → FAIL (52 failures, 7 warnings, 4795 checks) / EXIT=1；`i18n-audit.sh` → FAIL (22 failures, 0 warnings, 133 checks) / EXIT=1 ⇒ **门禁默认强度未被削弱，降级不构成掩蔽**（`remaining failures = 0` 且降级四类之和 = 默认失败总数）。

## 4. 交付提交与盘面一致（`19e2c333`）

* 提交含 **24 个路径**；抽查 7 个 blob 与工作树逐字节相同：`reports/54`、`reports/62`、`reports/66`、`reports/67`、`reports/68`、`scripts/doc-verify.sh`、`scripts/i18n-audit.sh`（均 SAME）。
* `HEAD^ = 8bb69d26…`；`git diff --numstat 8bb69d2 19e2c333` = `123 13`（doc-verify）/ `312 33`（audit），与 [reports/54](54-docs-freeze-manifest.md) §10.7 登记值**逐字相同** ⇒ 报告中的「vs HEAD」框架可判定为**该交付提交的父提交**（见 O3）。
* 提交信息与 /tmp/cap-commit-msg.txt 逐字节相同；其中 `reports/54` = `d2325eb2…`、`reports/62` = `35f8ae69…`、`reports/66` = `a9dc8d1d…`、`reports/67` = `b43fda65…` 与 blob 实测**全部一致**；`reports/68` 的登记值不一致（O1）。
* `git status` 仅 1 项：`.github/` 未跟踪（提交信息已如实披露因 token 缺 `workflow` scope 而未随提交推送，见 O6）；`origin/main = 19e2c333`（已推送）。

## 5. F 清单闭合判定

| ID | 判定 | 证据 |
|---|---|---|
| **F2** | 闭合 | §3 第 1 项：`--only` PASS (314 checks, 1 warnings) / EXIT=0 |
| **F4** | 闭合 | [reports/62](62-i18n-delivery.md) 第 534 行登记 `PASS (314 checks, 1 warnings)` / `EXIT=0`、warning 落在 `reports/54-docs-freeze-manifest.md:532`；旧值 `309` 命中 **0**、`519` 命中 **0**。**注意**：captain 派发单里的「309 → 312」两个数字均**未**出现在盘面（盘面 `312` 的 2 处命中位于 `reports/63` 的 sha256 字面量内部，与门禁计数无关），实际规则 = **登记值等于冻结瞬时实测值，当前为 314** |
| **F1'** | 闭合 | [reports/62](62-i18n-delivery.md) 内不再内嵌 `reports/54` 的任何哈希字面量（0 命中；邻近的 5 个 sha 分别为 `reports/61`、`reports/63`、历史 `3bb89149…`、旧 C4 `40d9fa70…`、基线提交 `8bb69d2…`）；改为指针式登记（第 464 行） |
| **F5** | 闭合（指针式） | [reports/54](54-docs-freeze-manifest.md) §10.9 第 2 条登记 `reports/62` 的**登记时刻值** `717d031b…` / 534 行 / 69 597 B / mtime 14:37:18，并显式声明该值随后失效、最终值以 task t19 的 output 与 reports/69 为准；`reports/62` 第 464 行作对称声明。**互嵌哈希在数学上不可同时成立**（一方先落盘即失效），captain 已裁定采用指针式耐久登记 |
| **F3** | 闭合 | [reports/54](54-docs-freeze-manifest.md) §10.9 第 1 条与 [reports/62](62-i18n-delivery.md) 第 464 行各有冻结时刻与绑定声明；§10.9 第 3 条逐条登记被取代的 t16 output 身份与 R1→W1→W2→W3→R4→R5 时序链 |
| **措辞项** | 闭合（含 1 处自引用残留，见 O2） | [reports/54](54-docs-freeze-manifest.md) 第 419 行「变化来源」列已按授权写为「四值」；`三值` 在 `reports/62` 命中 **0**，在 `reports/54` 命中 **1**（第 566 行的复核句自身，属自引用型测度） |

## 6. 观察项（非 finding；供 captain 收口）

* **O1（需收口，唯一涉及已推送工件）** — 提交信息登记 `reports/68` = `61d963b6…` / 147 行 / 21 367 B，但**盘面与提交 blob 实测** = `4d96218d1f878da39bfae24a72f69df3edf277863a785a3ffaac36cb14979a82` / 152 行 / 22 228 B。`61d963b6…` 经邮件快照比对为 T17 进行中的**中途版本**（我在 T17 期间向 captain 通报过该值）。该提交已推送到 origin/main。建议：以 amend 或后续补提交/说明修正该身份（不涉及任何被复核报告的结论）。
* **O2（低）** — [reports/54](54-docs-freeze-manifest.md) 第 566 行的断言「R4 之后两份报告该词命中 0」按字面实测为 **1 处**，即该句自身（句中引用了被检索的词）。语义残留确为 0；建议将来改为「1 处（本节自引用）」或不内嵌该词。
* **O3（低）** — HEAD 框架位移：报告撰写时 HEAD = `8bb69d26…`，交付提交后 HEAD = `19e2c333…`，故 §10.7 类「`git diff --numstat` 直接复现」命令在 `19e2c333` 上会返回空。**按身份闭合**：`HEAD^ = 8bb69d26…`，且 `git diff --numstat 8bb69d2 19e2c333` 复现登记值 `123 13` / `312 33`。
* **O4（低）** — t19 的 output 登记 `reports/62` = `bba07644…` / 534 行 / 70 184 B / mtime 23:14:41，其后 **23:21:03 又发生一次写入**（+21 B，行数不变）成为终值 `35f8ae69…` / 534 行 / 70 205 B ⇒ 该 output 的「最终身份」是**测量时刻值**，非终值。终值已由交付提交信息与**本文件**双载（指针式登记的指定载体）。
* **O5（低）** — `reports/68` mtime 推进到 2026-09-21 23:23:25 而 sha256 与提交 blob 均未变（字节等同，内容影响为零），属冻结测量期间的等价重写/触碰。
* **O6（低）** — CI 工作流 `.github/workflows/docs.yml`（`a2d9818c…` / 45 行 / 1397 B）存在于工作树但**未随提交推送**（远端暂无该文件，CI 尚未在 GitHub 上生效）；提交信息已如实披露。本地忠实克隆上的双 `--repo-mode` 类比为绿（§3 第 4 项）。
* **O7（低）** — 平台 QUOTA 中断与 captain 接管仅在交付提交信息 + t19 output 披露，未进 [reports/54](54-docs-freeze-manifest.md) / [reports/62](62-i18n-delivery.md) 正文（两份报告的冻结早于该事件）。
* **O8（低）** — 「指针式耐久登记」这一措辞只出现在 [reports/62](62-i18n-delivery.md)（1 处）；[reports/54](54-docs-freeze-manifest.md) §10.9 第 2 条以「登记时刻值 / 最终值」等价表述，语义一致、无冲突。

## 7. 复核期间零写入证明

复核窗口内对 19 个路径取前后两次快照（`sha256sum` + 行数 + 字节 + mtime）：

* 快照时点：**前 = 2026-09-21 23:27:53**（任何门禁/比对执行**之前**）；**后 = 2026-09-21 23:32:10**（全部复核动作之后）。
* 结果：两次快照**除表头时间戳外逐字节相同**（diff 仅第 1 行差异）。
* 窗口内**零写入被复核文件**；本轮唯一写入 = 本文件（`reports/69-followup2-review-round2.md`）。
* 被复核报告在复核窗口内身份稳定：`reports/54` = `d2325eb2…` / 566 行 / 49 366 B / mtime 14:42:18；`reports/62` = `35f8ae69…` / 534 行 / 70 205 B / mtime 23:21:03。

## 8. 本轮身份（供最终登记引用）

| 路径 | sha256 | 行 / 字节 |
|---|---|---|
| `reports/54-docs-freeze-manifest.md` | `d2325eb202d4cc1771f10f43537ac2c5008fc060a6b38a5fb16b4cfe090025af` | 566 / 49 366 |
| `reports/62-i18n-delivery.md` | `35f8ae692a18bc283b038bcc08516157e5319fb2f4ac1c252840323ca812b730` | 534 / 70 205 |
| `reports/66-followup2-requirements.md` | `a9dc8d1d1ce2b2ea178984de3c6581806e184e3d1f34cfd459817e4922a01c44` | 705 / 70 589 |
| `reports/67-followup2-verification.md` | `b43fda6536982b78dd04ba09d7c7165683787b35a56f9075c9fcdc49e8fc6739` | 325 / 34 857 |
| `reports/68-followup2-review.md` | `4d96218d1f878da39bfae24a72f69df3edf277863a785a3ffaac36cb14979a82` | 152 / 22 228 |
| `scripts/doc-verify.sh` | `9c154dbe6169f00d4f8fe6e5f3846d11f9e5c7f34eec18870ee73dc3c90454d6` | 1029 / 46 617 |
| `scripts/i18n-audit.sh` | `c778c83127489f3f8b36b72b2ab0e6b1afe381a66e1325b659aea3c29e4c0828` | 1089 / 45 596 |
| `.github/workflows/docs.yml` | `a2d9818c23b7b8790c6da8e01ac0028f220f364bab1ed752e4a7fb9619534578` | 45 / 1397 |
| 交付提交 | `19e2c333a83c81cc0f00d14b3f3491dba6de7ae5`（父 `8bb69d260f6d4f5a5e0961ee980e68592a5816de`，push 后 `origin/main` 相同） | 24 路径 / +3818 −79 |

**本文件自身的 sha256 / 行数 / 字节不在本文件内自指**（自指悖论），由 t20 的 output 与交付提交信息登记。

## 9. 复现命令（顺序执行，勿并发）

```bash
cd /data/dsh/home/workspace/code/webrtc-demo
git rev-parse HEAD; git rev-parse HEAD^                  # 19e2c333… / 8bb69d2…
git diff --numstat 8bb69d2 HEAD -- scripts/doc-verify.sh scripts/i18n-audit.sh   # 123 13 / 312 33
bash scripts/doc-verify.sh --only reports/54-docs-freeze-manifest.md reports/62-i18n-delivery.md; echo "EXIT=$?"   # 314 checks / 1 warnings / 0
bash scripts/doc-verify.sh; echo "EXIT=$?"               # 4795 checks / 4 warnings / 0
bash scripts/i18n-audit.sh; echo "EXIT=$?"               # 133 checks / 0 warnings / 0
bash scripts/doc-verify.sh --repo-mode; echo "EXIT=$?"   # 0
bash scripts/i18n-audit.sh --repo-mode; echo "EXIT=$?"   # 0
# 忠实克隆（CI 类比）：clone 后顺序跑两条 --repo-mode，并对照默认模式仍硬失败
# C4 31 路径：按 [reports/66](66-followup2-requirements.md) §7.2 配方（两端 LC_ALL=C sort）→ 053e23bf…
```
