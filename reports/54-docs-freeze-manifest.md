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
