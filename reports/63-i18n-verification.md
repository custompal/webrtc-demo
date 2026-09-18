# 63 — 第三轮复验：终审记录（task t17）

> Status: **pass** · Owner: `verifier` · Task: t17 (attempt 1), 2026-09-18
> 完整 A1–A7 逐项证据见 `reports/61-i18n-verification.md`（已定稿，sha256 `1ed8cd80258768a5a44ca69e9f484e49dc404719b480236e4ec65f954a2e951a`）。
> 本文件是**简短终审记录**：只登记结论、绑定与消解状态，不与主报告重复；所有数值均为现场实测。

---

## 1. verdict

**`pass`**（取值仅限 pass / needs_revision / reject）。绑定于下方联合指纹；冻结集任何后续写入都会使本裁决失效。

## 2. 绑定

| 项 | 实测值 |
|---|---|
| checker sha256 | `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` |
| 行数 / 字节 | 919 / 41673 |
| mtime | 2026-09-18 22:17:57 +0800 |
| 权限 | `-r--r--r--`（八进制 444，物理只读） |
| bearing commit | `543d94153f982fe93cb6f08bc886b7fdce48505b`（blob `a253c5c8c40a8f2d7cf5df003d19ff633222f81c`） |

独立复核：`git show HEAD:scripts/doc-verify.sh | sha256sum` = `6c62591a…` = 工作树实测值 ⇒ 被绑定 revision 已入库、可持久找回；旧绑定 `64081ef5…`（921 行）不再使用。

## 3. 17 个工件摘要与联合指纹

* 本轮由 t16 更新的两件：
  * `doc/design/zh-CN/01-requirements.md` = `5af1985bfad01df02d834ef0c325fd01d9e60263791301d4688b6bff5dbb1e19` / 333 行 / 25754 B（原 `41688ed3…`）
  * `doc/design/zh-CN/02-architecture.md` = `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42` / 219 行 / 15877 B（原 `cfcea123…`）
* 其余 15 件与 t11 基线 sha256 完全相同（`doc/design/SPEC.md` `a5724a40…`、`doc/design/zh-CN/GLOSSARY.md` `51e3eb34…` 均未改）；17 件全值见 `reports/61-i18n-verification.md` §3 差异表与 §6.1。
* **联合指纹**（checker + 17 工件）= `3bb8914931742191aab5e7f9e49d3b4f`。
  * **跑前 2026-09-18 22:27:25 = 跑后 22:27:29**；mtime 联指纹 `161bd4e2c80cb748` 亦一致（PRE/POST 签名文件 `diff` 为 IDENTICAL）。
* 门禁：`bash scripts/doc-verify.sh` → **raw exit 0**，**PASS (3912 checks, 2 warnings)**，0 FAIL（19 NOTE + 2 WARN 均为基线既有项）。追加 5 次重跑亦 rc=0。

## 4. F1–F4 消解

* **F1（绑定失效）** — 消解：由 captain 以终版 `6c62591a…` 重新基准化，并提交为 bearing commit `543d9415…` + 置只读 444；跑前跑后指纹一致。
* **F2** `doc/design/zh-CN/01-requirements.md:112` — 闭合为 `未验证（unverified）`。
* **F3** `doc/design/zh-CN/01-requirements.md:328` — 闭合为 `已证伪（disproven）`（连续定译串存在，captain 裁定合规；「被……已证伪」的读感记为纯风格观察，不计缺陷）。
* **F4** `doc/design/zh-CN/02-architecture.md:194` — 闭合为 `已否决（rejected）`。
* 全量状态词扫描 **44/44 合规、0 偏离**（t11 时为 41/3）；表内状态单元格 **16/16、0 problem** ⇒ 未引入新偏离。

## 5. G1 处置（流程定性）

`zh-CN/01`、`02` 于 2026-09-18 22:23:38 / 22:23:40 的两次写入，经台账核实即 **t16（captain 为修复 F2–F4 而授权、translator-a 执行的修复单）** 的实现动作 ⇒ 定为**流程时序冲突**（同一时间窗口内既声明冻结、又授权必须写文档集的修复单），**非越权 churn**；captain 先前的 17 工件公告以这两件新值（`5af1985b…` / `716552cd…`）为准。checker 侧 churn 已由 bearing commit 固化并施加只读。

## 6. determinism 与物理锁定复核

* determinism：我方独立抽样累计 **16 次全量全 PASS、0 FAIL**（t11 窗口 10 次 + 本轮主跑 1 次 + 追加 5 次）；未复现任何瞬时失败。
* 物理锁定：checker 实测 `-r--r--r--`（444），抽测期间指纹零漂移 ⇒ 无流程 finding。

## 7. A1–A7 结论（逐项证据见 `reports/61-i18n-verification.md`）

| # | 结论 | 摘要 |
|---|---|---|
| A1 | pass | raw exit 0 / PASS (3912 checks, 2 warnings) / 0 FAIL；跑前跑后 sha256+mtime 一致 |
| A2 | pass | 334 条去重引用，333/333 可达且行号在界内（唯一噪声为 IPv6 字面量） |
| A3 | pass | §6 表 74 条对照；状态词 44/44、表内单元格 16/16；F2–F4 已闭合 |
| A4 | pass | 7 对配对第 1 行独立成行、半角、双向可达且指向正确对端；两个禁建文件不存在 |
| A5 | pass | 逐字声明恰在 6 个 Y-1 文件第 2 行、GLOSSARY 豁免；语义抽样 562 处零相反 |
| A6 | pass | SPEC v1.7.0 身份未变；V14/V15 在册、§7.6 读法、E1 豁免、§9 按语言分治；被引 35/35 编号在册 |
| A7 | pass | checker + bearing commit + 17 工件实测值已登记；与 t11 差异恰 2 件变化、15 件不变 |

## 8. 复现

```bash
cd code/webrtc-demo
git rev-parse HEAD                                     # 543d94153f982fe93cb6f08bc886b7fdce48505b
git show HEAD:scripts/doc-verify.sh | sha256sum         # 6c62591a…1eb59
sha256sum scripts/doc-verify.sh && bash scripts/doc-verify.sh; echo "EXIT=$?"
```

本报告自身通过门禁：`bash scripts/doc-verify.sh --only reports/63-i18n-verification.md`。
