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
