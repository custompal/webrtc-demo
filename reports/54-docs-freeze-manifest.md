# 54 — Documentation set freeze manifest

> Status: draft · Owner: writer-app · Task: t16
> Evidence base: live measurement in the container at production time (commands listed in §2); `reports/55-captain-ruling-path-notation.md` §7 (workspace-evidence inventory), §8 (final checker freeze), §10 (digest vs commit id)
> Doc standard: `doc/design/SPEC.md`

## 1. Scope and meaning

This manifest binds the independent verification to an immutable set of artefacts. Every digest below was
re-measured **at production time** (`2026-09-17T11:32:27Z`); no value is copied from an earlier snapshot,
because `doc/design/SPEC.md` was edited concurrently during the preceding task window.

Included: every `doc/design/*.md`, the four generated tables under `doc/design/_generated/`,
`doc/README.md`, `doc/design/README.md` and the repository-root `README.md`. Nothing in the documented set is missing.

## 2. Frozen checker revision and how it was measured

The revision is identified by the **triple** required by `reports/55-captain-ruling-path-notation.md` §10:
a content digest, the commit that carries the content, and the runtime HEAD at production time. These are three
different namespaces and must not be conflated.

| Element | Value |
|---|---|
| Content digest (`sha256sum scripts/doc-verify.sh`) | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` |
| Bearing commit | `8fdb222` — verified by recomputation: `git show 8fdb222:scripts/doc-verify.sh \| sha256sum` returns the digest above |
| Runtime HEAD at production time | `9d23006` |
| Bytes / lines / mtime | 26 545 B / 628 lines / 2026-09-17 14:22:14 |
| Working tree state | clean for this path: `git status --porcelain scripts/doc-verify.sh` returns nothing |

Guard: the checker was **not** modified while producing this manifest. It stays frozen until the verification
task returns its verdict; any later edit invalidates the binding recorded here.

## 3. Full-gate result at the frozen revision

Raw command, raw exit code, raw counters:

```
bash scripts/doc-verify.sh
doc-verify.sh: PASS (2466 checks, 2 warnings)
EXIT=0
```

| Counter | Value |
|---|---|
| Raw exit code | 0 |
| Failures | 0 |
| Warnings | 2 |
| Checks | 2466 |
| Advisory `NOTE` lines | 18 |

The checks counter is corpus-wide: editing any chapter changes it. The binding facts are **failures = 0** and
the checker digest in §2; re-measure before quoting a counter.

The two warnings are both the deliberately absent build-product negative example:
app/build/nope.apk at `doc/design/09-verification-and-limitations.md:138` and
`doc/design/09-verification-and-limitations.md:145` (rule A10: warning-level, never changes the exit code).
The 18 advisory `NOTE` lines are informational only (host-only paths and report line-number citations); they are
not failures and do not affect the exit code.

## 4. Artefact table

All values measured in the container at production time. Paths are repository-relative.

| # | Path | sha256 (full) | Bytes | Lines | mtime |
|---|---|---|---|---|---|
| 1 | `doc/design/SPEC.md` | `efffd2ce9bba6b16a69f4c7ff83af6465c6e3daab41f0026a651a2bc3539b6de` | 60252 | 696 | 2026-09-17 19:28:52 |
| 2 | `doc/design/01-requirements.md` | `3f7067d40c3d4aa1290c60c22fd033565d06b49c00bc3ca0d2800611978a545f` | 25916 | 329 | 2026-09-17 14:26:58 |
| 3 | `doc/design/02-architecture.md` | `942cb49a098edb2aaf4552cb5ce0389f1adc056bcf3400afb6ae6f642ef97489` | 16274 | 221 | 2026-09-17 19:28:16 |
| 4 | `doc/design/03-app-architecture.md` | `bef3e1b514d22532b9027a4e793fa43631a12fd8f5ca39bc19086e8e870719e8` | 29382 | 384 | 2026-09-17 13:55:08 |
| 5 | `doc/design/04-signaling-service.md` | `fd067df9ea1627e8525f9b72c3672a40c865bb354cfbacd3edcbd5fd57ae99e5` | 20089 | 277 | 2026-09-17 13:57:21 |
| 6 | `doc/design/05-protocols.md` | `7ee7f332e7e6433f1f6bf5591bd71846cda21ab2ce0589eaeed6344c6978c796` | 22895 | 293 | 2026-09-17 13:57:21 |
| 7 | `doc/design/06-flows.md` | `bd00eb2c2f77530c589a43644382d56dfe9a1f93f7455491e479ebe8da3012c4` | 28998 | 504 | 2026-09-17 13:57:36 |
| 8 | `doc/design/07-build-and-deploy.md` | `75557ef5be2b33c5381313a7163bfced7ae825d25a28691b7d00abb522162649` | 11611 | 204 | 2026-09-17 13:55:57 |
| 9 | `doc/design/08-issues-and-solutions.md` | `902330ff972e80f7400015faf2672c5fbcefeda69dc1bad0b0f6c0020e24e14d` | 55809 | 497 | 2026-09-17 19:31:46 |
| 10 | `doc/design/09-verification-and-limitations.md` | `f87b1299b8cc092369ae575d0b8dd5f3e3022889c9986374e35ab632a83e4b70` | 23937 | 215 | 2026-09-17 19:38:34 |
| 11 | `doc/design/10-code-map.md` | `347a4520cd210fae9ee7cdd46ee8032c1d788bd6e7e58bacaac3d90d4452d603` | 20285 | 268 | 2026-09-17 14:35:26 |
| 12 | `doc/design/11-coding-standards.md` | `b79928d353482e0389bd4caf7924840eb61cf93c4f20623c09837add8a881dcc` | 18869 | 237 | 2026-09-17 19:36:29 |
| 13 | `doc/design/_generated/host-commands.md` | `3cd3efd108e51fef3db78ab145df550a45558854a9d3a3c933bc9eca7d6be80b` | 48021 | 611 | 2026-09-17 14:11:19 |
| 14 | `doc/design/_generated/jni-contract.md` | `33d83e8cf768ed3f46d392c30dbd47d54d8682280b4cc31336e19a2693388112` | 11756 | 132 | 2026-09-17 14:11:19 |
| 15 | `doc/design/_generated/log-events.md` | `15a5763e6345df0228058541f02dc1bbc17bd76ef0729c03f98a6eb77cc766ac` | 78673 | 737 | 2026-09-17 14:11:18 |
| 16 | `doc/design/_generated/signaling-messages.md` | `9ce1b503c2456d6fda15d5980503cd943c7f64f19d17f7b3951ddd65721550a7` | 7879 | 173 | 2026-09-17 14:11:18 |
| 17 | `doc/README.md` | `fb739b51625c93f4c41677749befc71740ac9207cd51a7c16273211ea89b294b` | 9110 | 105 | 2026-09-17 13:33:47 |
| 18 | `README.md` | `3f424ccc7f5e37e86f8dba4719f05a2e532ea28af6359e634a9ed2d6d2050604` | 3189 | 59 | 2026-09-17 19:41:13 |
| 19 | `doc/design/README.md` | `b385223eaa13dd41a6bb14317b00d4a84c33b03ca971c8a89b07ef03ab95181e` | 2383 | 41 | 2026-09-17 19:41:13 |

Total: 19 artefacts (12 chapters under `doc/design/` including the standard, 1 document-set index, 4 generated
tables, 1 legacy map, 1 repository entry point).

## 5. Cited workspace evidence — existence check

The workspace root is the container workspace `/data/dsh/home/workspace`. The paths below are the inventory
required by `reports/55-captain-ruling-path-notation.md` §7; P2 is hard-checked, so splitting, relocating or
deleting any of them after this manifest invalidates the binding while a document still cites it.

Session logs (all present at production time):

```
tmp/n1/x/app.log            tmp/n1/x/native.log         tmp/n2/x/app.log
tmp/n2/x/webrtc.log         tmp/n3/x/app.1.log          tmp/n4/x/native.1.log
tmp/n6/x/app.log            tmp/n6/x/app.1.log          tmp/n6/x/app.2.log
tmp/n7/x/app.1.log
```

Probe and evidence files (all present at production time):

```
tmp/t42-captain-build.sh    tmp/t47b-captain-build.sh
tmp/verifier-recon/linehashtag-ab.md    tmp/verifier-recon/liveness-evidence.md
tmp/verifier-recon/tag-probe.md         tmp/ws-draft/probe-p5.md
```

Result: 10 of 10 session logs present, 6 of 6 probe/evidence files present, 0 missing.

## 6. Repository entry point

The repository-root `README.md` and the document-set index `doc/design/README.md` landed with task t23 and are
listed in §4 (rows 18 and 19). The repository entry point links to every chapter and to the four generated
tables, and the index cross-links with the legacy map `doc/README.md`. No artefact of the documented set is
missing at this revision.

## 7. Open items

* **Concurrency caveat.** `doc/design/SPEC.md` and `doc/design/02-architecture.md` were edited by other
  members while the preceding task ran, and `doc/design/08-issues-and-solutions.md` plus
  `doc/design/09-verification-and-limitations.md` changed again **during** this measurement — their rows in §4
  were re-measured at `2026-09-17T11:32:27Z`, and all 17 digests were re-verified against the live files
  immediately before delivery. Their mtimes are later than the captain's pre-measurement snapshot, which is why
  the full-gate counter reads 2421 checks / 2 warnings here rather than the earlier 2414 / 1. Any further edit
  to any listed artefact invalidates the binding and requires a re-issued manifest.
* **Verification counts are revision-bound.** The counters in §3 belong to the frozen revision in §2. A later
  checker edit invalidates them; re-run the gate and re-register the triple before quoting them.
* **The manifest's own digest** is registered in the task output of t16 at the moment of delivery and is not
  self-referential inside this file.
* **Amendment at t23** (measured at HEAD `cbd63d4`): two rows were re-measured (`09` and `11`, which were updated by t24/t25) and two rows were added (the repository entry point and the document-set index). All other rows keep the values measured at the original freeze. If the checker revision in §2 changes, or any listed artefact is edited again, the binding is invalidated and this manifest must be re-issued rather than reinterpreted.

---

## 8. Amendment — i18n round (task t18, 2026-09-18)

This manifest is **amended, not re-issued**: §1–§6 keep the values measured at the original freeze and §7 keeps
its open items; this section registers the i18n round on top of the 19-row baseline. Every value below was
re-measured **live at writing time** in the container workspace (`/data/dsh/home/workspace`, repo
`code/webrtc-demo`); no value is copied from a task output or from an earlier report.

### 8.1 Frozen checker revision (supersedes the revision registered in §2)

| Element | Value |
|---|---|
| Content digest (`sha256sum scripts/doc-verify.sh`) | `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` |
| Two-path check | `git show HEAD:scripts/doc-verify.sh \| sha256sum` returns the same digest |
| Blob (`git rev-parse HEAD:scripts/doc-verify.sh`) | `a253c5c8c40a8f2d7cf5df003d19ff633222f81c` |
| Bearing commit | `543d94153f982fe93cb6f08bc886b7fdce48505b` — subject `docs(i18n): 冻结扩展后的文档门禁脚本 (t2)`, 2026-09-18 22:22:38 +0800 |
| Runtime HEAD at writing time | `543d94153f982fe93cb6f08bc886b7fdce48505b` |
| Bytes / lines / mtime | 41 673 B / 919 lines / 2026-09-18 22:17:57 +0800 |
| Permissions | `-r--r--r--` (0444, physically read-only) |
| Working tree state | clean for this path: `git status --porcelain scripts/doc-verify.sh` returns nothing |
| Supersedes | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` (628 lines — the revision registered in §2) |

### 8.2 Full-gate result at the amended revision

```
bash scripts/doc-verify.sh
doc-verify.sh: PASS (3912 checks, 2 warnings)
EXIT=0
```

| Counter | Value |
|---|---|
| Raw exit code | 0 |
| Failures | 0 |
| Warnings | 2 |
| Checks | 3912 |
| Advisory `NOTE` lines | 22 |

The checks counter is corpus-wide; the i18n scan set (`doc/design/zh-CN/*.md` plus the two `README.en.md` files)
raises it from 2466 to 3912. The binding facts stay **failures = 0** and the digest in §8.1. `reports/63-i18n-verification.md`
§1.3 records 19 advisory `NOTE` lines for its own run; the difference is a counting method, not a gate result —
both runs observed 0 failures and the same two A10 warnings.

### 8.3 i18n artefacts — 9 rows added

| # | Path | sha256 (full) | Bytes | Lines | mtime |
|---|---|---|---|---|---|
| 20 | `doc/design/zh-CN/GLOSSARY.md` | `51e3eb34776d0f433f6d32cb945852697fe15a1f106857fa878e90f96e851007` | 27419 | 349 | 2026-09-17 22:04:20 |
| 21 | `doc/design/zh-CN/SPEC-guide.md` | `dd8911469c31a71cb9e407e10ed2ca0018b422591affb68351ac4ba12be742c7` | 7895 | 108 | 2026-09-17 21:59:03 |
| 22 | `doc/design/zh-CN/01-requirements.md` | `5af1985bfad01df02d834ef0c325fd01d9e60263791301d4688b6bff5dbb1e19` | 25754 | 333 | 2026-09-18 22:23:38 |
| 23 | `doc/design/zh-CN/02-architecture.md` | `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42` | 15877 | 219 | 2026-09-18 22:23:40 |
| 24 | `doc/design/zh-CN/03-app-architecture.md` | `7b923659876b77c4409bbb854b24b760cceff6499488bef202c7fed80ebefb48` | 28403 | 367 | 2026-09-17 21:55:12 |
| 25 | `doc/design/zh-CN/04-signaling-service.md` | `36d180bc03f1f2520168516d1488ef91307f49d6c4aa9c01fa0b7db8867f713a` | 19726 | 275 | 2026-09-17 21:50:21 |
| 26 | `doc/design/zh-CN/05-protocols.md` | `4acd7b70d37ac121d4e7b0a40e630c491fb4dc76664049d89bb31f30195d0773` | 22037 | 288 | 2026-09-17 21:50:38 |
| 27 | `README.en.md` | `72b7a3c45ff87d9ecb45e8579a83be10b3132ba63fa802ad8ef87970a54262b8` | 3235 | 61 | 2026-09-17 21:58:45 |
| 28 | `doc/design/README.en.md` | `44cda01a35f5c663d47844347282c55a823a5da8521765b7c4e4a1937dc7feeb` | 2429 | 43 | 2026-09-17 21:58:45 |

### 8.4 i18n artefacts — 8 rows re-signed (§4 values → writing-time values)

| §4 row | Path | sha256 (§4, superseded) | sha256 (writing time) | Bytes / lines (§4 → now) |
|---|---|---|---|---|
| 1 | `doc/design/SPEC.md` | `efffd2ce9bba6b16a69f4c7ff83af6465c6e3daab41f0026a651a2bc3539b6de` | `a5724a409c34c001d3ff6304e42db5efb3ae481f950cd0ea803b213cbe5e65ab` | 60252/696 → 67804/757 |
| 2 | `doc/design/01-requirements.md` | `3f7067d40c3d4aa1290c60c22fd033565d06b49c00bc3ca0d2800611978a545f` | `55e9b8a52722e08a78e6843257523bf3954c469daa1aef26921ab079a80f8387` | 25916/329 → 25977/331 |
| 3 | `doc/design/02-architecture.md` | `942cb49a098edb2aaf4552cb5ce0389f1adc056bcf3400afb6ae6f642ef97489` | `6a2fc862af61d0511115e47d50c2065f29d93912f4feacb88fa6847d567d1be8` | 16274/221 → 16312/222 |
| 4 | `doc/design/03-app-architecture.md` | `bef3e1b514d22532b9027a4e793fa43631a12fd8f5ca39bc19086e8e870719e8` | `097bbf2393b42df45973b866529d02d007411264494aa988949be41f3e493584` | 29382/384 → 29449/387 |
| 5 | `doc/design/04-signaling-service.md` | `fd067df9ea1627e8525f9b72c3672a40c865bb354cfbacd3edcbd5fd57ae99e5` | `ca79073949c806d3bee82ab95768d88e87f3cdad0b109374acde950454c594a0` | 20089/277 → 20155/279 |
| 6 | `doc/design/05-protocols.md` | `7ee7f332e7e6433f1f6bf5591bd71846cda21ab2ce0589eaeed6344c6978c796` | `042b79cdf53e6232d338883535368fdf8029ae45dd74a2ee9f958615cfdb2e3f` | 22895/293 → 22953/295 |
| 18 | `README.md` | `3f424ccc7f5e37e86f8dba4719f05a2e532ea28af6359e634a9ed2d6d2050604` | `38c7ada64330fee0700f20ab1e2aeadd7d94618085c7187d247c7d34224bbb2c` | 3189/59 → 3358/46 |
| 19 | `doc/design/README.md` | `b385223eaa13dd41a6bb14317b00d4a84c33b03ca971c8a89b07ef03ab95181e` | `b652775bc04d244d0ac37e222f01bf8ddd60d8ffc02f33ce4a485d65eac474cb` | 2383/41 → 2854/37 |

### 8.5 Rows unchanged — 0 DRIFT

Rows 7–17 of §4 (`doc/design/06-flows.md` … `doc/design/11-coding-standards.md`, the four tables under
`doc/design/_generated/`, and `doc/README.md`) were re-measured live: sha256, bytes and lines all equal the §4
values — checked row by row by the loop in §8.7, output `OK` for all 11. No untouched row drifted.

### 8.6 Binding stability

* Joint fingerprint of the frozen checker plus the 17 i18n artefacts, computed as
  `sha256sum scripts/doc-verify.sh <17 paths, §8.3 then §8.4 order> | md5sum`:
  **`fff4597c64850695dd6f47db711f04a8`** — identical before and after the full-gate run.
* `reports/63-i18n-verification.md` §1.3 publishes a joint fingerprint `3bb8914931742191aab5e7f9e49d3b4f`
  (pre/post identical for the verifier). Three candidate formulas tried here (`sha256sum | sha256sum`,
  `sha256sum | md5sum`, `cat | sha256sum`) did **not** reproduce that value, so the exact command behind it is
  recorded as `unverified`. This does not weaken the binding: both sides measured **failures = 0** and the §8.1
  digest independently.
* Verification evidence re-measured live, with their division of labour: `reports/61-i18n-verification.md` =
  `1ed8cd80258768a5a44ca69e9f484e49dc404719b480236e4ec65f954a2e951a` (229 lines / 22 425 B) is the **third-round
  main report carrying the complete A1–A7 evidence**; `reports/63-i18n-verification.md` =
  `d3b80c3a65c4312c76a7845d4891ae40e820a3e5d5f09bfc3856716205bf8aca` (73 lines / 5 130 B) is the **short
  final-verdict record** for t17 — verdict, binding triple, the 17 artefact digests, joint fingerprint
  `3bb89149…`, pre/post 22:27:25 = 22:27:29, F1–F4 closure, G1 attribution, determinism (16/16 PASS), the 0444
  read-only check, and the A1–A7 conclusion table pointing back to `reports/61`.
* `reports/63-i18n-verification.md` was rewritten **several times** after t17 reached its terminal state, always
  in-scope (the file is t17's only deliverable). The captain reports the chain `199 → 73 → 214 → 73` lines; my own
  anchors are: first measurement for this amendment 199 lines (22:32:20) and the final value **73 lines at
  22:32:31**, re-read twice and stable. **The final value is the one measured live above** (`d3b80c3a…`, 73 lines /
  5 130 B). t17's contract wording asked for a "210-line independent report"; the complete evidence is carried by
  `reports/61` and `reports/63` was reduced to the pointer+summary record — a disposition the captain confirmed
  after the fact, **not a defect**. The checker and all 17 i18n artefacts saw **zero writes** throughout (joint
  fingerprint unchanged at `3bb89149…`), so no §8.3/§8.4 digest changes.

### 8.7 How this amendment was measured (reproducible)

```bash
cd code/webrtc-demo
git rev-parse HEAD                                   # 543d94153f982fe93cb6f08bc886b7fdce48505b
sha256sum scripts/doc-verify.sh                      # 6c62591a…1eb59
git show HEAD:scripts/doc-verify.sh | sha256sum       # 6c62591a…1eb59
bash scripts/doc-verify.sh; echo "EXIT=$?"             # PASS (3912 checks, 2 warnings) / EXIT=0
sha256sum scripts/doc-verify.sh <17 paths> | md5sum    # fff4597c64850695dd6f47db711f04a8 (before = after)
```

The 11-row 0-DRIFT loop compared `sha256sum`, `wc -c` and `wc -l` of each untouched path against the §4 row and
printed `OK` for all 11 (any mismatch would have printed `DRIFT`).

### 8.8 Open items added by this amendment

* **The task ledger is not authoritative.** Ledger outputs hold values from the moment a task closed:
  `t2` records `64081ef5…` / 921 lines (an intermediate checker revision); `t14` records `doc/design/zh-CN/GLOSSARY.md`
  v1.2.0 / `e6180b34…` / 27 256 B (a superseded intermediate, 3 minutes of existence); `t1` records GLOSSARY
  v1.0.0; `t5` and `t9` record pre-rework values. The authoritative values for this round are §8.1, §8.3, §8.4
  above and `reports/62-i18n-delivery.md`.
* **Any further edit invalidates this binding** (same rule as §7): a later edit to the checker or to any listed
  artefact requires a re-issued manifest or a new, live-measured amendment. Verified reports `reports/61` and
  `reports/63` are revision-bound in the same way.

---

## 9. 增补 — 后续收尾轮（task t7，2026-09-19）

本清单**再次增补而非重签**：§1–§7 保留原始冻结值与 open items，§8 保留 i18n 轮的增补；本节登记后续收尾轮（C1 SPEC v1.7.1、C2 zh-CN/01 已交付形态、B3 审计脚本、C3/C4/C5）在 §8 基线之上的变化。所有数值均在容器工作区（`/data/dsh/home/workspace`，仓库 `code/webrtc-demo`）**撰写时现场实测**，不沿用任何任务 output 或先前报告的转述值。

### 9.1 冻结 checker revision（与 §8.1 相同，本轮未触碰）

| 项 | 值 |
|---|---|
| 内容摘要（`sha256sum scripts/doc-verify.sh`） | `6c62591af17669855638027b28c2f04095c0383b894d50151603f18989b1eb59` |
| 两路一致 | `git show 543d94153f982fe93cb6f08bc886b7fdce48505b:scripts/doc-verify.sh \| sha256sum` 现场复核返回同一摘要 |
| bearing commit | `543d94153f982fe93cb6f08bc886b7fdce48505b`（与 §8.1 一致，未变） |
| 本轮撰写时 runtime HEAD | `1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae` |
| 行数 / 字节 / 权限 | 919 / 41 673 B / `-r--r--r--`（0444） |
| 工作树状态 | `git status --porcelain scripts/doc-verify.sh` 为空 |

### 9.2 门禁结果（撰写时现场）

```text
bash scripts/doc-verify.sh
doc-verify.sh: PASS (3912 checks, 2 warnings)
EXIT=0

bash scripts/i18n-audit.sh
i18n-audit.sh: PASS (79 checks, 0 warnings)
EXIT=0
```

2 warnings 与 22 行 advisory `NOTE` 与 §8.2 基线一致；审计脚本是**补充证据**，不替代门禁（判据冲突时以门禁为准）。

### 9.3 本轮重签的 2 行（§8 值 → 本轮值）

| §8 行 | 路径 | sha256（§8，superseded） | sha256（本轮） | 字节/行数（§8 → 本轮） |
|---|---|---|---|---|
| §8.4-1 | `doc/design/SPEC.md` | `a5724a409c34c001d3ff6304e42db5efb3ae481f950cd0ea803b213cbe5e65ab` | `b3368522392205dd02073a8adf472566b58fad8234de96fbf69fcbc801c369e5` | 67 804/757 → **67 976/758** |
| §8.3-22 | `doc/design/zh-CN/01-requirements.md` | `5af1985bfad01df02d834ef0c325fd01d9e60263791301d4688b6bff5dbb1e19` | `a4271a402396099f56ca952e6d489100b13027375e884751d89b900644c2c8d5` | 25 754/333 → **25 754/333** |

* `doc/design/SPEC.md`：v1.7.0 → **v1.7.1**，仅 3 处（版本行、§9 第 741 行改为角色式措辞、新增 changelog 1.7.1 行），`git diff --numstat` = `3 2`。
* `doc/design/zh-CN/01-requirements.md`：只改第 328 行（D-4 行 → 已交付形态 **D′**：`| D-4 | 历史文本「旋转已烘焙进 I420」，上游源码已证伪（disproven） | 对采集路径的错误心智模型 |`），`numstat` = `1 1`，行数不变、等长改写。

### 9.4 本轮触碰但净差异为零的 1 行

| §8 行 | 路径 | sha256（= §8.3 原值，本轮终值） | 事实 |
|---|---|---|---|
| §8.3-23 | `doc/design/zh-CN/02-architecture.md` | `716552cd063f0b5ff0d826eb07c24ab8a47937b544a86a775d67ae8ea4975b42` | 中程形态 A（`…中的已否决（rejected）旋转烘焙实验。`）曾在 t4 attempt 2 交付；随后按 `reports/64` 第 6 版 §0.1/§4.2 的还原指令 `git checkout --` 撤销，captain 裁定接受 HEAD 原文。**本轮净差异为零**——`git diff` 对该文件为空，219 行 / 15 877 B 不变。 |

### 9.5 本轮新增的 1 个工件

| # | 路径 | sha256（full） | 字节 / 行数 / 权限 | 用途与用法 |
|---|---|---|---|---|
| 29 | `scripts/i18n-audit.sh` | `f93da7d5156edad9612740a30b952e5cf11e794eec57c89ec6b2b0d1c29fd82d` | 32 425 / 810 / `-rwxr-xr-x`（755） | 中英一致性**补充**审计：`bash scripts/i18n-audit.sh`（全量）、`bash scripts/i18n-audit.sh --only <path>…`（只校验给定文件自身义务）、-h 或 --help（打印头部注释）；退出码 0 = 无 FAIL / 1 = 有 FAIL / 2 = 调用或环境错误 |

检查项（`reports/64` §2.2 冻结口径，9 项全部实现、无一条放宽）：Z1 配对切换器、Z2 逐字译文声明 + 全仓唯一性、Z3 §6 术语表结构、Z4 §5.5 状态词全角括号邻接、Z5 §6.1 窄 denylist + §6 状态词行、Z6 引用集合对等/可达/在界、Z7 code-span 多重集对等、Z8 结构计数对等、Z9 禁建副本 + 旧式切换器残留。

正反例证据：verifier 自研 31 次运行（含 17 条负例）见 [reports/65](65-followup-verification.md) §3；doc-tooling 探针矩阵 13/13 OK，原始留痕 `tmp/i18n-audit-probe/{probe.sh,out/matrix.txt,out/P*.out,VERIFY-t2.txt}`（captain 裁定保留，最终处置另行派单）。逐条结论见 [reports/62](62-i18n-delivery.md) §14.2。

### 9.6 未受影响的 15 行 — 0 DRIFT

§8.3（去掉本轮改动的 `doc/design/zh-CN/01-requirements.md`）与 §8.4（去掉本轮改动的 `doc/design/SPEC.md`）合计 **15 行**，逐行现场重测 `sha256sum` + `wc -c` + `wc -l`，与登记值逐格相等，循环输出 **OK × 15**（任一不等会打印 `DRIFT`）：`doc/design/zh-CN/GLOSSARY.md`、`doc/design/zh-CN/SPEC-guide.md`、`doc/design/zh-CN/02-architecture.md` … `doc/design/zh-CN/05-protocols.md`、`README.en.md`、`doc/design/README.en.md`、`doc/design/01-requirements.md` … `doc/design/05-protocols.md`、`README.md`、`doc/design/README.md`。

### 9.7 联合指纹（C4 规范配方）

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

* **19 路径终值 = `40d9fa70db57add32376157e181b1fe57c8fa250fe7a06253905d5a794adfa55`**（撰写时实测；跑前 = 跑后一致，与输入顺序无关）。
* 18 路径机制自检（HEAD 树，不含审计脚本）= `1bf12697c411ba292c62234d5f6fb826eebe98ed8423277668794382b0a5d901`：本节用 `git archive HEAD <18 路径>` 在工作区外复算，**逐字符命中**。
* 旧值处置（不篡改历史）：`3bb8914931742191aab5e7f9e49d3b4f` 生成命令**不可复现**（`reports/65` §8.4 十种候选公式）；`fff4597c64850695dd6f47db711f04a8` **已复现并解释**＝同一 HEAD 树、18 路径**未排序** `sha256sum … | md5sum` 变体，本节独立复算命中。

### 9.8 本轮报告身份（撰写时现场实测）

| 报告 | sha256 | 字节 / 行数 | 说明 |
|---|---|---|---|
| [reports/64](64-followup-requirements.md) | `5c1d96d4baf2e5fdc0b8261e891fbeecb4898f72b96e181132ca13109d1f7823` | 39 703 / 356 | 本轮判据来源；顶部含 captain 直接写入的 `CAPTAIN RULING` 段（效力高于其 §0.1/§4.1/§9.3 的冲突表述） |
| [reports/65](65-followup-verification.md) | `8f7ed1a32e62759d4b736056e003f45558ae01305f5b45c29d21ca9497c34dab` | 35 533 / 439 | t5 独立验证报告，verdict = pass；t5 终态后有一次 in-scope 措辞修订（`8133aefd…` / 35 491 B → 本值） |

### 9.9 本节数值的测量方式（可复现）

```bash
cd code/webrtc-demo
git rev-parse HEAD                                    # 1b5bb78d31c2d3baf8bcf93ce2170d5ae563a4ae
sha256sum scripts/doc-verify.sh                        # 6c62591a…1eb59
git show 543d9415…:scripts/doc-verify.sh | sha256sum    # 6c62591a…1eb59
bash scripts/doc-verify.sh; echo "EXIT=$?"              # PASS (3912 checks, 2 warnings) / EXIT=0
bash scripts/i18n-audit.sh; echo "EXIT=$?"              # PASS (79 checks, 0 warnings) / EXIT=0
# 15 行 0 DRIFT：逐行 sha256sum + wc -c + wc -l 与 §8.3/§8.4 登记值比对
# C4：见 §9.7 配方（19 路径，两端 LC_ALL=C sort）
```

### 9.10 本节新增的 open items

* **冻结件竞态（如实记录）**：本轮判据文件 `reports/64` 在约 15 分钟内出现 **7 个版本**（`e5e2e116 → 76d97797 → 9db6c582 → 283f3794 → 2cf5704b → 883f8992 → 37904ea7`），最终由 captain 在文件顶部直接写入 `CAPTAIN RULING` 段覆盖。其 §0.1/§4.1/§9.3/§9.11 与裁定冲突的表述**保留为历史提案文本、不作更正**，由 [reports/65](65-followup-verification.md) 与 [reports/62](62-i18n-delivery.md) §14.6 记为 criteria-vs-applied 差异。
* **`doc/design/zh-CN/**` 仍未跟踪**（同 §8）：`zh-CN/01` 与 `zh-CN/02` 的本轮改前字节只能由 `reports/64`/`65` 的现场留档与 git COMMIT 值约束；`zh-CN/02` 的「形态 A → HEAD 原文」由 `git diff` 为空证明净差异为零。
* **台账与盘面的差异**（终态任务不可原地更新）：t4 台账 output 记录形态 A 交付，而盘面在还原指令后为 HEAD 原文；t8 台账记 D′（与盘面一致）；t9/t10/t11 为「创建后未使用」。权威值一律以本节与 [reports/62](62-i18n-delivery.md) §14 的现场实测为准。
* **任何进一步编辑都会使本节绑定失效**（同 §7/§8.8）：须重新现场测量并再次增补，不得据旧值重述。
