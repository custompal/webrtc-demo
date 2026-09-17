# 56 — Round-2 independent verification of the documentation system (t22)

Scope: independent re-verification of `doc/design/**` (SPEC + chapters 01–11 + the four generated tables) and
`doc/README.md` on the single frozen checker revision, plus the **interruption-impact audit** requested by the user
("ensure the earlier interruptions did not affect document quality").
Verifier: `verifier`, task **t22** (rebuilt from the runtime-cancelled `t20`). Round-1 baseline:
`reports/53-docs-verification.md` (`needs_revision`). This report reflects **attempt 3** measurements.

**Verdict: `needs_revision`** — not because of document quality, but because the **freeze manifest no longer
describes the delivered artefact set**. `doc/design/11-coding-standards.md` was edited *after*
`reports/54-docs-freeze-manifest.md` was produced (and the edit is still **uncommitted**), so one of the 17 rows of
the binding manifest is stale, which by the manifest's own §7 invalidates the binding for that artefact.

Everything else is clean, and the drift itself is an *improvement*: that same edit **closed round-1 finding F-02's
residual (R2-01)** by adding the bearing commit to the pin and dropping the line/byte counts.

| area | result |
|---|---|
| full gate | `PASS (2421 checks, 2 warnings)`, **raw exit 0, failures 0** |
| checker binding | content digest verified three ways; unchanged across the run |
| freeze manifest | **16/17 artefacts OK; `11-coding-standards.md` DRIFTED** → the blocker |
| F-01 … F-08 | all **closed** (F-02 completed by the t19 edit) |
| interruption-impact audit | **6/6 clean** |
| round-2 sampling | not weaker than round 1 (146 citations, protocol six-way, 7 flows / 29 log lines) |

Read-only: no document, source, script or other report was modified; the only write is this report.

---

## 1. Commands and raw results

| # | command | raw result |
|---|---|---|
| 1 | `bash scripts/doc-verify.sh` | `doc-verify.sh: PASS (2421 checks, 2 warnings)` — **exit 0**, failures 0, stderr 0 bytes, 18 `NOTE` lines |
| 2 | `--only doc/design/11-coding-standards.md` | `PASS (101 checks, 0 warnings)` |
| 3 | per-document `--only` (all 12 chapters) | each **PASS** — counts in §5.5 |
| 4 | `--only doc/design/_generated` | `PASS (2721 checks, 0 warnings)` |
| 5 | `sha256sum scripts/doc-verify.sh` before/after run 1 | identical |
| 6 | manifest re-verification (17 artefacts) | **16 OK / 1 DRIFT** (`11-coding-standards.md`) |
| 7 | `git show HEAD:… \| sha256sum` and `git show 8fdb222:… \| sha256sum` | both equal the frozen digest |

Raw full-run output: `tmp/verifier-recon/t22/full-run-a3.txt`.

### The two warnings are the deliberate advisory (not defects)

```
WARN doc/design/09-verification-and-limitations.md:138 → typo-suspect (gitignored path absent): `app/build/nope.apk`
WARN doc/design/09-verification-and-limitations.md:145 → typo-suspect (gitignored path absent): `app/build/nope.apk`
```

Both stem from the intentional **L-9 negative example** (its known-limitation row and its byte-pinned reproducer
fence). Under the standing ruling this class is an **allowed self-referential advisory**: warning level only, never
changing the exit code (`reports/55` §2:4/§6, SPEC A10). Explained per instance. Round 1's URL false positive in
`02` is gone.

## 2. Frozen-revision binding (triple, `reports/55` §12.3)

| element | expected | measured (attempt 3) | result |
|---|---|---|---|
| content digest, working tree | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` | identical | ✓ |
| content digest, `git show HEAD:` | same | identical | ✓ |
| carrying commit blob, `git show 8fdb222:` | same | identical | ✓ |
| size / lines | 26 545 B / 628 | 26 545 B / 628 | ✓ |
| run-time `HEAD` | may have advanced | `2b1fdcf` → `be3d5a5` → `976cb52` | recorded; **not** a mismatch |
| cleanliness | clean for the script | `git status` empty for it | ✓ |

Only a **content-digest** change would trigger `BLOCKED: checker revision mismatch`; none occurred. `6cc5a382…`
never entered the binding surface. **This criterion is satisfied.**

## 3. Freeze manifest re-verification — the blocker

`reports/54-docs-freeze-manifest.md` digest `345de855d527f041c1b99c69e84e9b2d0cbed714296f9d599e8b5d7f620b0905`
confirmed (8346 B / 133 lines). All 17 listed artefacts were re-measured (full sha256, bytes, lines):

**16 OK, 1 DRIFT — `doc/design/11-coding-standards.md`:**

| | sha256 | bytes | lines |
|---|---|---|---|
| manifest row 12 (recorded) | `3078e3be14139252e2283e922e57037824d8bb03c674ecb52bf7496b970d8181` | 18 805 | 236 |
| live now | `b79928d353482e0389bd4caf7924840eb61cf93c4f20623c09837add8a881dcc` | 18 869 | 237 |

`git status` reports the file as **modified** (` M`), i.e. the edit is **uncommitted**; the manifest's own §7 states
that any later edit to a listed artefact invalidates the binding. So the manifest is currently an accurate record of
16 artefacts and a stale record of one. Recorded as **R2-02**.

(Methodological note, disclosed for honesty: my first pass reported 17 false DRIFTs because my parsing script failed
to strip the backticks from the digest column; bytes and lines agreed even then. The corrected parse above gives
16/1. Attempt 2 had measured 17/17 OK **before** this edit landed.)

Known gap unchanged and still true: the repository-root `README.md` **does not exist** (t23 owns it).

## 4. F-01 … F-08 closure (all closed; no round-1 text reused)

| id | status | evidence at the frozen revision |
|---|---|---|
| **F-01** gate exits 1 | **CLOSED** | `bash scripts/doc-verify.sh` → `PASS (2421 checks, 2 warnings)`, raw exit 0, failures 0. |
| **F-02** drift + stale pin | **CLOSED** | checker identity bound three ways (§2); manifest carries the triple; and the pin at `11-coding-standards.md:202-204` now reads `sha256 676d075a…` **plus `carried by commit 8fdb222 (recompute: git show 8fdb222:scripts/doc-verify.sh \| sha256sum)`** with the line/byte counts **removed** — exactly the agreed fix form. (This is the very edit that makes the manifest row stale, see R2-02.) |
| **F-03** `J.N` / `GEN_JNI` | **CLOSED** | `SPEC.md:94` cites `…/webrtc/WebRtcEngine.kt:243` → that line holds `"org.jni_zero.GEN_JNI"`; `SPEC.md:95` cites `…/webrtc/JniBindingClasspathTest.kt:147` → that line holds `const val HASH_NATIVE_CLASS = "J.N"`. Both paths exist; `grep` over `scripts/doc-verify.sh` for `GEN_JNI\|J.N\|J4\|descriptive` shows **no carve-out** → citation-side fix only. |
| **F-04** normative text judged as content | **CLOSED** | SPEC `Status: **frozen** v1.6.0`; P3 enumerations at `:408`, `:444`, `:512` written **without leading slashes**; R11 `:627` restricts reporting to **path-prefix** use, matching the implementation; §2.3 polarity correct (positives bare/`HOST:`, negatives labelled); the old `:163/:166` failures are gone with the v1.4.0 body restore. |
| **F-05** unmarked host path in SPEC | **CLOSED** | zero `unmarked host path` failures; SPEC host references carry `HOST:` (`:124`). |
| **F-06** `vp9_encoder.cpp:63` → `:65` | **CLOSED** | `08-issues-and-solutions.md:279` and `:472` cite `vp9_encoder.cpp:65` = `constexpr bool kBakeRotationInEncoder = false;`. |
| **F-07** `../env.sh` resolvable form | **CLOSED** | convention stated at `07-build-and-deploy.md:31`, `SPEC.md:634` ("P2 resolves against the workspace root (`<repo>/../..`)") and `11-coding-standards.md:139`; the cited value is true (`env.sh:42` = `export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"`). |
| **F-08** `typo-suspect` | **CLOSED as allowed advisory** | URL false positive gone; the two remaining warnings are the deliberate L-9 example. |
| R2-01 (raised in attempt 2) | **CLOSED** | the pin now includes the bearing commit and no longer quotes counts (§4 F-02 row). |

## 5. Interruption-impact audit (captain's six items) — 6/6 clean

**5.1 Withdrawn vocabulary as a path prefix — clean, with discriminator.** Discriminator applied: a hit violates
only if the token occupies a **path position**; rule text *describing* the retirement, a table-header definition, or
a line labelled `NEGATIVE EXAMPLE` is compliant. Sweep over `doc/design/**` and `doc/README.md` finds **exactly one**
marker-as-prefix occurrence:

```
doc/design/SPEC.md:169  WORKSPACE: tmp/t47b-captain-build.sh   <!-- NEGATIVE EXAMPLE — must stay retired on purpose -->
```

It belongs to the §2.3 correct/incorrect example block and is explicitly labelled; SPEC `:629`/`:668` require such a
line not to fail, and the extractor **skips** `NEGATIVE EXAMPLE` lines (`if (rh != "" && !NEG[i])`), so it produces
neither failure nor note. Compliant. Its sibling `SPEC.md:166` is the negative example for the unmarked-host rule.
Withdrawn model wording: `V14` 0, `A14` 0, `Captain v3` 0, `Captain v4` 0, `five-tag` 0, `four-tag` 0 hits;
`mandatory` only in current-rule or historical narrative (`11:141` "the only mandatory marker", `08:417`, `SPEC:696`).

**5.2 Stale digests not presented as current — clean.** The sweep for `200ba92e`, `6b21c41f`, `d2a95711`,
`9a9b4bd2`, `92d9ea0d`, `718b68c8` yields only `11-coding-standards.md:156-157`, inside the **labelled historical arc
table** (each digest paired with its verdict, final revision pointed at the pin below). Compliant per §10/§12.1.

**5.3 No probe-level count presented as document-level; no `../../tmp/…` in prose — clean.** `../../tmp` → 0 hits.
The one probe-level figure (`09:140`, "The reproducer for L-9 … 99 B, sha256 `1d64dce2…`") is labelled as the
reproducer; `09:63` attributes gate counts to the run, not to the document.

**5.4 SPEC self-consistency, §2.3 polarity, version — clean.** Version `Status: **frozen** v1.6.0`; severity wording
agrees across T5 `:145`, V8 `:405`, §7.4 `:439`/`:454`, §7.2 `:574`, §7.1 P5 `:514`, V13 `:410` (retired prefixes are
failures; only `NEGATIVE EXAMPLE` lines are exempt); §2.3 polarity correct.

**5.5 Per-document `--only` — all PASS.**

| document | checks | warnings |
|---|---|---|
| SPEC.md | 244 | 0 |
| 01-requirements.md | 232 | 0 |
| 02-architecture.md | 127 | 0 |
| 03-app-architecture.md | 265 | 0 |
| 04-signaling-service.md | 373 | 0 |
| 05-protocols.md | 331 | 0 |
| 06-flows.md | 158 | 0 |
| 07-build-and-deploy.md | 103 | 0 |
| 08-issues-and-solutions.md | 267 | 0 |
| 09-verification-and-limitations.md | 115 | 2 (L-9 advisories) |
| 10-code-map.md | 138 | 0 |
| 11-coding-standards.md | 101 | 0 |
| **`_generated/` aggregate** | **2721** | **0** |

**5.6 Closure against round 1** — §4.

## 6. Round-2 sampling (not weaker than round 1)

* **Citations: 146 verified** (deterministic every-8th of the **1162** unique doc-line ↔ cited-path:line triples;
  the count is unchanged from attempt 2). No mismatch. Because `11` gained one line, two of its rows shifted
  (`11:215→216`, `11:221→222`); both were re-opened at the new revision and still support their statements, and all
  other `11` rows (37, 43, 58, 76, 80, 98, 100) re-verified. Representative rows: `01:100 → SignalingClient.kt:145
  const val MAX_REJOIN_ATTEMPTS = 10`; `03:179 → SignalingClient.kt:122` (63 s budget comment); `03:305 →
  AppConfig.kt:69 const val ICE_POLICY_RELAY = "RELAY"`; `05:179 → CallSession.kt:100 const val MAX_ICE_RESTARTS = 2`;
  `11:37 → CallSession.kt:812 gate(...)`; `SPEC:94 → WebRtcEngine.kt:243 "org.jni_zero.GEN_JNI"`. Two borderline rows
  were re-checked and passed: `07:27 → ../env.sh:42` (workspace-root convention, claim true) and
  `08:435 → vp9_encoder.cpp:84-90` (range starts on a blank line but covers the `t56` diagnostic-only comment block
  supporting the R-5 rejected-experiment claim).
* **Protocol six-way, independent of the generator:** Go `Type*` values 14, Go constants 14, Kotlin `@SerialName` 14,
  generated `signaling-messages.md` §1 value column 14 and constant column 14, §2 14, `05-protocols.md` §3 catalogue
  14. All value sets equal the Go source (`create created join joined peerJoined peerLeft offer answer ice natType
  leave error ping pong`); the constant set equals the Go constants. No side extra or missing.
* **Device logs: all seven flows, 29 cited lines** opened at their exact line numbers, **0 missing** — e.g.
  `n1/app.log:40 room_created`, `n2/app.log:1223 ws_reconnect_scheduled … budget_ms=63000`,
  `n7/app.1.log:5991 ws_reconnect_give_up attempts=11 budget_ms=63000`, `n6/app.2.log:13492
  room_not_found_action=keep_call`, `n1/app.log:129 ice_watchdog_started … timeout_ms=30000 fail_ms=45000`,
  `n6/app.2.log:6170 field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/`,
  `n4/native.1.log:1307 encoder_rate_floor floor_kbps=30`, `n2/webrtc.log:349 DroppedFrames`.
* **Intensity:** escalation was not warranted. `03` is byte-identical to its round-1 digest; `06` differs from the
  digest t3 reported but has **identical size (28 998 B) and line count (504)** — an in-place edit rather than a
  substantial rewrite — and the seven-flow verification covers it substantively.

## 7. Known limitations (not defects)

1. `p5-artifact` has **no failure path** (intentional per SPEC V13; `note()` output is uncounted) — the source of the
   two advisories. 2. The host/workspace-only command-evidence threshold is not tightened (captain-confirmed breadth
   difference). 3. Root `README.md` absent until t23.

## 8. Findings

| id | severity | status | problem | required fix |
|---|---|---|---|---|
| **R2-01** | low | **CLOSED at attempt 3** | `11-coding-standards.md`'s pin carried no bearing commit and still quoted line/byte counts. | — (t19 added `carried by commit 8fdb222` and removed the counts.) |
| **R2-02** | **medium** | **OPEN** | `reports/54-docs-freeze-manifest.md` row 12 is **stale**: `doc/design/11-coding-standards.md` was edited after the manifest was produced (`3078e3be…`/18 805 B/236 lines → `b79928d3…`/18 869 B/237 lines) and the edit is **uncommitted** (`git status` = ` M`). Per the manifest's own §7 a later edit invalidates the binding, so the manifest does not currently describe the delivered artefact set — the acceptance requires binding to `reports/54`. Note the edit itself is beneficial: it closes R2-01 and the gate still passes. | (1) **commit** the `11-coding-standards.md` edit so the delivered revision is fixed; (2) have t16 **re-measure and update that one row** (sha256/bytes/lines/mtime) — or re-issue the manifest. Then all 17 rows bind again. |

## 9. Verdict

**`needs_revision`.**

Document quality is **not** the problem — the full gate is green (`PASS (2421 checks, 2 warnings)`, exit 0, failures 0),
the checker binding is exact, F-01…F-08 are all closed (including the round-1 residual R2-01), the interruption-impact
audit is clean on all six items, and the sampling reproduced round-1 strength. The single blocker is **evidence-chain
integrity**: one of the 17 artefacts that the acceptance binds to (`reports/54`) has changed since the manifest was
issued, and the change is not committed — so the delivered set is not the set the manifest describes.

The fix is mechanical and touches no document content: commit the `11` edit, re-measure that one manifest row, and
re-check. When that is done I expect the verdict to flip to **`pass`** with no further document changes; the
re-verification needed is one manifest row plus a confirming `bash scripts/doc-verify.sh` run (I will re-run the full
gate anyway).

*Bindings: checker content digest `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` (26 545 B /
628 lines; carrying commit `8fdb222`; run-time `HEAD` `976cb52`) and `reports/54-docs-freeze-manifest.md`
(`345de855…`). `6cc5a382…` was never part of the binding surface.*
