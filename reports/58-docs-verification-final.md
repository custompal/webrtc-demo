# 58 — Final re-binding verification (t27)

Scope: the final re-binding check after the captain landed t23's three deliverables directly (root `README.md`,
`doc/design/README.md`, and the augmentation of the freeze manifest), on the frozen checker revision.
Owner: `verifier`, task **t27** (replacement for the deadlocked t26/t23 chain; this report reflects **attempt 2**
measurements, after the manifest augmentation landed).
Baselines: round 1 `reports/53-docs-verification.md` → `needs_revision`; round 2
`reports/56-docs-verification-r2.md` → `needs_revision` (finding **R2-02**: manifest row for `11` stale).

**Verdict: `pass`.**

Every acceptance criterion is met: the gate is green, the checker is bound three ways, the augmented manifest
verifies **19/19 rows with 0 DRIFT** — including the two rows named in the acceptance (`11` = `b79928d3…`/18 869 B/
237 lines, `09` = `f87b1299…`/23 937 B/215 lines) and the two new artefacts (root `README.md`,
`doc/design/README.md`) — and **R2-02 is closed**. No open findings remain.

| area | result |
|---|---|
| full gate | **PASS (2466 checks, 2 warnings)**, raw exit 0, failures 0 |
| frozen triple | content digest `676d075a…` both ways + bearing commit `8fdb222`; run-time HEAD `099d46d` recorded |
| manifest row-by-row | **19 OK / 0 DRIFT** (manifest `eabab22e…`, 8922 B, 19 rows) |
| R2-02 | **CLOSED** — `11` row now `b79928d3…`/18 869 B/237 lines |
| prior findings | R2-01, R2-02, R3-01, R3-02 **all closed** |

Read-only: the only write is this report; no document, script, manifest or other report was modified.

---

## 1. Full gate (acceptance 1) — met

```
doc-verify.sh: PASS (2466 checks, 2 warnings)
raw exit code 0, failures 0, stderr 0 bytes, 18 advisory NOTE lines
```

The two warnings are the deliberate L-9 negative example (allowed advisory, explained per instance — warning level
only, never affecting the exit code, `reports/55` §2:4/§6 and SPEC A10):

```
WARN doc/design/09-verification-and-limitations.md:138 → typo-suspect (gitignored path absent): `app/build/nope.apk`
WARN doc/design/09-verification-and-limitations.md:145 → typo-suspect (gitignored path absent): `app/build/nope.apk`
```

Supporting per-document runs: root `README.md` → `PASS (30 checks, 0 warnings)`; `doc/design/README.md` →
`PASS (25 checks, 0 warnings)`; `doc/design/_generated` → `PASS (2721 checks, 0 warnings)`.

**Transient observation, recorded for honesty (not a defect in the delivered set):** during attempt 1 the same gate
reported `FAIL (1 failures, 2 warnings, 2442 checks)` with `README.md:29 → missing repo path:
\`doc/design/README.md\`` — the index file did not exist at that instant and landed while verification was running
(mtime 19:41:22). The re-run above is the final state, with the check count risen 2442 → 2466 as the new artefacts
landed. This is recorded because it is precisely the race the freeze manifest exists to catch; the delivered set as
measured now is consistent.

## 2. Frozen triple (acceptance 2) — met

| element | expected | measured |
|---|---|---|
| content digest, working tree | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` | identical ✓ |
| content digest, `git show HEAD:` | same | identical ✓ |
| carrying commit blob, `git show 8fdb222:` | same | identical ✓ |
| size / lines | 26 545 B / 628 | 26 545 B / 628 ✓ |
| run-time `HEAD` | may differ from `8fdb222` | `099d46d` — recorded, **not** a mismatch (§12.3) |
| digest before/after the run | equal | equal ✓ |

No `BLOCKED: checker revision mismatch` condition arose; `6cc5a382…` remains outside the binding surface.

## 3. Manifest row-by-row re-verification (acceptance 3) — met: 19/19, 0 DRIFT

The manifest has been augmented: `reports/54-docs-freeze-manifest.md` is now
sha256 `eabab22e062f8b487989a93b3079e290f82235c9d3243a1d9891db9b2772b8b8`, 8922 B, **19 rows** (previously
`345de855…` / 8346 B / 17 rows), and it is committed (clean in `git status`). Every row was re-measured for full
sha256 + bytes + lines against the live files:

**19 OK / 0 DRIFT.**

The rows this task's acceptance names:

| # | artefact | manifest row | live | result |
|---|---|---|---|---|
| 10 | `doc/design/09-verification-and-limitations.md` | `f87b1299b8cc092369ae575d0b8dd5f3e3022889c9986374e35ab632a83e4b70` / 23 937 B / 215 | identical | ✓ |
| 12 | `doc/design/11-coding-standards.md` | `b79928d353482e0389bd4caf7924840eb61cf93c4f20623c09837add8a881dcc` / 18 869 B / 237 | identical | ✓ |
| 18 | `README.md` (repository root) | `3f424ccc7f5e37e86f8dba4719f05a2e532ea28af6359e634a9ed2d6d2050604` / 3189 B / 59 | identical | ✓ |
| 19 | `doc/design/README.md` | `b385223eaa13dd41a6bb14317b00d4a84c33b03ca971c8a89b07ef03ab95181e` / 2383 B / 41 | identical | ✓ |

The remaining 15 rows (SPEC, chapters 01–11, the four `_generated/` tables, `doc/README.md`) are also OK. The
manifest's former §6 "known gap" text has been replaced with a statement that the root `README.md` and the
document-set index landed with t23 and that no artefact of the documented set is missing — which the live check
above confirms.

## 4. R2-02 closure (acceptance 4) — closed

R2-02 was: the manifest row for `11-coding-standards.md` is stale. The row now records
`b79928d3…` / 18 869 B / 237 lines, identical to the live file, and the file itself carries the corrected pin
(`:203-204` = the digest **plus** `carried by commit 8fdb222 (recompute: git show 8fdb222:scripts/doc-verify.sh |
sha256sum)`, with the line/byte counts removed) — i.e. round-1 finding F-02 / R2-01 remains closed as well. The same
refresh also fixed the `09` row and added the two missing rows, which closes **R3-01** (the un-augmented manifest),
and the two new deliverables plus the manifest are now **committed** (clean `git status`), which closes **R3-02**.

## 5. Findings

No open findings. Closed during this task:

| id | origin | status | closure evidence |
|---|---|---|---|
| F-01 / F-03 … F-08 | round 1 (`reports/53`) | closed | recorded in `reports/56` §4 and re-confirmed by the green gate |
| R2-01 | round 2 (`reports/56`) | closed | `11` pin carries the bearing commit and no counts |
| **R2-02** | round 2 | **closed** | manifest row 12 = live `11` = `b79928d3…`/18 869 B/237 lines |
| **R3-01** | this task, attempt 1 | **closed** | manifest augmented to 19 rows, 0 DRIFT, §6 refreshed |
| **R3-02** | this task, attempt 1 | **closed** | root `README.md`, `doc/design/README.md` and the manifest are committed |

## 6. Verdict

**`pass`.**

The documentation set is released under this verification:

* the machine-checkable delivery gate passes — `bash scripts/doc-verify.sh` → `PASS (2466 checks, 2 warnings)`, raw
  exit 0, **failures 0**, with the two warnings explained above as intentional advisories;
* the checker is bound three ways to the frozen content digest `676d075a…` with carrying commit `8fdb222`, the
  run-time `HEAD` (`099d46d`) recorded separately, and no digest movement across the run;
* the augmented freeze manifest describes the delivered set exactly: **19/19 rows, 0 DRIFT**, including the two
  artefacts added by t23 and the two rows that were previously stale;
* all findings from rounds 1 and 2 (F-01…F-08, R2-01, R2-02) and both findings raised during this task (R3-01,
  R3-02) are closed.

*Bindings: checker content digest `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` (26 545 B /
628 lines; carrying commit `8fdb222`; run-time `HEAD` `099d46d`). Manifest examined: `reports/54-docs-freeze-manifest.md`
at `eabab22e…` (19 rows, committed). Prior records: `reports/53-docs-verification.md` (round 1) and
`reports/56-docs-verification-r2.md` (round 2).*
