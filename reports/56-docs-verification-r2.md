# 56 — Round-2 independent verification of the documentation system (t22)

Scope: independent re-verification of `doc/design/**` (SPEC + chapters 01–11 + the four generated tables) and
`doc/README.md` on the single frozen checker revision, plus the **interruption-impact audit** requested by the user
("ensure the earlier interruptions did not affect document quality").
Verifier: `verifier`, task **t22** (rebuilt from the runtime-cancelled `t20`; `t18` → `t21`).
Round-1 baseline: `reports/53-docs-verification.md` (verdict `needs_revision`).

**Verdict: `pass`.**

The full gate exits **0** with **0 failures**; every artefact matches the freeze manifest; F-01, F-03, F-04, F-05,
F-06, F-07 and F-08 are closed; the interruption-impact audit is clean on all six items; and the round-2 sampling
reproduced the round-1 strength (protocol six-way identical, all seven flows verified against raw device logs,
146-citation sample with no mismatch). One **low**-severity residual is recorded as **R2-01** (the pin *form* in
`11-coding-standards.md`); it does not affect the gate outcome — see §8 for the explicit caveat.

Read-only: no document, source, script or existing report was modified; the only write is this report.

---

## 1. Commands and raw results

| # | command | raw result |
|---|---|---|
| 1 | `bash scripts/doc-verify.sh` | `doc-verify.sh: PASS (2421 checks, 2 warnings)` — **exit code 0**, failures 0, stderr 0 bytes, 18 `NOTE` lines |
| 2 | per-document `--only` (all 12 chapters) | each **PASS**, counts listed in §5 |
| 3 | `--only doc/design/_generated` | `PASS (2721 checks, 0 warnings)` |
| 4 | `sha256sum scripts/doc-verify.sh` (before and after run 1) | identical in both measurements |
| 5 | manifest re-verification (17 artefacts) | **17 OK / 0 DRIFT** |
| 6 | `git show HEAD:scripts/doc-verify.sh \| sha256sum` | equals the frozen digest |
| 7 | `git show 8fdb222:scripts/doc-verify.sh \| sha256sum` | equals the frozen digest |

Raw full-run output: `tmp/verifier-recon/t22/full-run.txt` (working scratch outside the repository).

### The two warnings are the deliberate advisory (not defects)

```
WARN doc/design/09-verification-and-limitations.md:138 → typo-suspect (gitignored path absent): `app/build/nope.apk`
WARN doc/design/09-verification-and-limitations.md:145 → typo-suspect (gitignored path absent): `app/build/nope.apk`
```

Both come from the **intentional L-9 negative example** (`09` §4 known-limitation row L-9, plus its reproducer
fence at `:145`). Under the standing ruling this class is an **allowed self-referential advisory**: warning level
only, never changing the exit code (`reports/55` §2:4, §6; SPEC A10). Explained per instance: line 138 states the
limitation, line 145 is the byte-pinned reproducer it points at. Round 1 also had a URL false positive in `02`,
which is **gone** (URL exclusion implemented).

## 2. Frozen-revision binding (triple, per `reports/55` §12.3)

| element | expected | measured | result |
|---|---|---|---|
| content digest (working tree) | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` | identical | ✓ |
| content digest (`git show HEAD:`) | same | identical | ✓ |
| carrying commit blob (`git show 8fdb222:`) | same | identical | ✓ |
| size / lines | 26 545 B / 628 | 26 545 B / 628 | ✓ |
| run-time `HEAD` | may have advanced | `2b1fdcf` ("t21 + t16: 08/09 closure…") | recorded, **not** a mismatch |
| working tree cleanliness | clean for the script | `git status` empty for it | ✓ |

`HEAD ≠ 8fdb222` is explicitly **not** a mismatch under §12.3; only a content-digest change would trigger
`BLOCKED: checker revision mismatch`. No such change occurred: the pre-run and post-run digests are equal.
**`6cc5a382…` does not appear anywhere in this verification's binding surface.**

## 3. Freeze manifest re-verification

`reports/54-docs-freeze-manifest.md` — sha256 `345de855d527f041c1b99c69e84e9b2d0cbed714296f9d599e8b5d7f620b0905`,
8346 B / 133 lines — matches the digest reported by t16.

All **17** artefacts it lists were re-measured against the live files (full sha256, bytes, lines):
**17 OK, 0 DRIFT** — SPEC, chapters 01–11, the four `_generated/` tables, and `doc/README.md`.
(My first pass reported 17 false DRIFTs; that was a bug in my parsing script — I had failed to strip the backticks
from the digest column — and bytes/lines agreed even then. Corrected parse: 17/17 OK.)

Known gap unchanged and still true: the **repository-root `README.md` does not exist** (t23 owns it); the manifest
covers the 17 artefacts above.

## 4. F-01 … F-08 closure

Each row was re-opened and re-measured at the frozen revision; no round-1 text is reused.

| id | status | round-2 evidence |
|---|---|---|
| **F-01** gate exits 1 | **CLOSED** | `bash scripts/doc-verify.sh` → `PASS (2421 checks, 2 warnings)`, **raw exit 0, failures 0**. |
| **F-02** checker drift + stale pin | **CLOSED on the checker/manifest side; residual form issue → R2-01** | digest verified three ways (§2); manifest carries the triple and 17 verified rows (§3). But `11-coding-standards.md:203` still prints only `sha256 676d075a… (628 lines, 26545 bytes)` — **no bearing commit**, and the line/byte counts remain, whereas the agreed fix form was "final revision + commit, no line counts". Recorded as **R2-01** (low). |
| **F-03** `J.N` / `GEN_JNI` citation side | **CLOSED** | `SPEC.md:94` cites `…/webrtc/WebRtcEngine.kt:243` → that line contains `"org.jni_zero.GEN_JNI"`; `SPEC.md:95` cites `…/webrtc/JniBindingClasspathTest.kt:147` → that line contains `const val HASH_NATIVE_CLASS = "J.N"`. Both paths exist. `grep` over `scripts/doc-verify.sh` for `GEN_JNI|J.N|J4|descriptive` finds **no carve-out** → the fix is citation-side only, exactly as required. |
| **F-04** normative text judged as content | **CLOSED** | SPEC is now **v1.6.0** (`Status: **frozen** v1.6.0`, line 3). The P3 enumerations at `:408`, `:444`, `:512` are written **without leading slashes** (`opt`, `etc`, `var`, …), so the rule text no longer trips path classification. R11 (`:627`) now states the retired markers are reported **only when used as a *path prefix***, matching the implementation. §2.3's example polarity is correct: positive examples are bare paths / `HOST:` (`:166` area, `:124`), negatives are explicitly labelled (`:166`, `:169`). The old `SPEC.md:163/:166` failures are gone because the v1.4.0 body was restored. |
| **F-05** unmarked host path in SPEC | **CLOSED** | zero `unmarked host path` failures at the frozen revision; host references in SPEC carry `HOST:` (`:124` shows `HOST: /opt/dsh-workspaces`, `HOST: /opt/apk-http/publish_apk.sh`, …). |
| **F-06** `vp9_encoder.cpp:63` → `:65` | **CLOSED** | `08-issues-and-solutions.md:279` and `:472` both cite `app/src/main/cpp/encoder/vp9_encoder.cpp:65`; line 65 **is** `constexpr bool kBakeRotationInEncoder = false;`. |
| **F-07** `../env.sh` resolvable form | **CLOSED** | the convention is now stated explicitly: `07-build-and-deploy.md:31` ("`../env.sh` resolves against the workspace root (outside the repository)"), `SPEC.md:634` ("P2 resolves against the workspace root (`<repo>/../..`)"), `11-coding-standards.md:139` ("checked against the workspace root"). The cited values are true — `env.sh:42` = `export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"`, supporting the NDK claim. |
| **F-08** `typo-suspect` warnings | **CLOSED as allowed advisory** | the URL false positive in `02` is gone. Two warnings remain, both from the deliberate L-9 negative example (`09:138`, `09:145`); per the ruling they are explained, not defects, and do not change the exit code. |
| F-09 | unchanged | p5 no-failure-path and the un-tightened command-evidence threshold remain documented known limitations. |

## 5. Interruption-impact audit (captain's six items)

### 5.1 Withdrawn vocabulary used as a **path prefix** — clean, with discriminator

Discriminator applied (as agreed): a hit is a violation only if the token occupies a **path position**; rule text
that *describes* the retirement, a table-header definition, or a line labelled `NEGATIVE EXAMPLE` is compliant.

Command: line-prefix and backticked-marker sweep over `doc/design/**` and `doc/README.md`.

Result — **exactly one** marker-as-prefix occurrence in the entire deliverable set:

```
doc/design/SPEC.md:169  WORKSPACE: tmp/t47b-captain-build.sh   <!-- NEGATIVE EXAMPLE — must stay retired on purpose -->
```

It is part of the §2.3 correct/incorrect example block and is explicitly labelled; SPEC:629/668 document that such
a line must not fail, and the extractor **skips** `NEGATIVE EXAMPLE` lines entirely (`if (rh != "" && !NEG[i])`), so
no failure and no note is produced. Compliant. Its sibling `SPEC.md:166`
(`/opt/apk-http/publish_apk.sh <!-- NEGATIVE EXAMPLE — must stay unmarked on purpose -->`) is the negative example
for the unmarked-host rule.

All other occurrences are rule text (SPEC `:119`, `:145`, `:405`, `:439`, `:454`, `:514`, `:529-530`, `:574`, `:627`)
or changelog/arc material — compliant.

Withdrawn *model* wording: `V14` 0 hits, `A14` 0 hits, `Captain v3`/`Captain v4` 0 hits, `five-tag`/`four-tag`
0 hits. `mandatory` appears in three historical/correct contexts only (`08:417` root-cause narrative, `11:141`
"the only mandatory marker" describing the current host rule, `SPEC:696` changelog). `hint` appears only in the
labelled history table (`11:157`) and unrelated prose. Clean.

### 5.2 Stale digests not presented as current — clean

Sweep for `200ba92e`, `6b21c41f`, `d2a95711`, `9a9b4bd2`, `92d9ea0d`, `718b68c8` over the deliverables yields only:

```
11-coding-standards.md:156 | 200ba92e42068c4a | failure
11-coding-standards.md:157 | 6b21c41f7630dd6e, d2a95711dbedabf | accepted as a hint, recorded as a note
11-coding-standards.md:158 | final frozen revision (digest below) | failure again
```

That is the deliberately labelled **historical arc table** (`11` §8.2: "The checker's verdict on those two prefixes
changed twice — failure, then tolerated hint, then failure again at the frozen revision"), each digest **paired with
its verdict** and the final revision pointed at "digest below" (= the `676d075a…` pin at `:203`). Compliant under
`reports/55` §10/§12.1 ("paired and labelled historical = compliant").

### 5.3 No probe-level count passed off as a document-level count; no `../../tmp/…` in prose — clean

* `grep '\.\./\.\./tmp'` over the deliverables → **0 hits**.
* No document quotes a gate-level `N checks)` total as its own claim. The one probe-level figure
  (`09:140`, "The reproducer for L-9 … 99 B, sha256 `1d64dce2…`") is explicitly labelled as the reproducer.
  `09:63` attributes gate counts to the run rather than to the document.

### 5.4 SPEC self-consistency, §2.3 polarity, version — clean

* version: `Status: **frozen** v1.6.0` (line 3) with changelog `1.6.0` describing the v1.4.0 vocabulary restore.
* severity wording agrees across T5 (`:145`), V8 (`:405`), §7.4 (`:439`, `:454`), §7.2 (`:574`), §7.1 P5 (`:514`) and
  V13 (`:410`): retired prefixes are failures; only `NEGATIVE EXAMPLE` lines are exempt.
* §2.3 example polarity: positives = bare path / `HOST:`; negatives = retired prefix and unmarked host path, each
  labelled. Correct.

### 5.5 Per-document `--only` runs — all PASS

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
| 09-verification-and-limitations.md | 115 | 2 (the L-9 advisories) |
| 10-code-map.md | 138 | 0 |
| 11-coding-standards.md | 101 | 0 |
| **`_generated/` (aggregate)** | **2721** | **0** |

### 5.6 Closure against round 1 — see §4

## 6. Round-2 sampling strength (not lower than round 1)

* **Citation spot-check: 146 citations** opened and compared (sample of the **1162** unique
  doc-line ↔ cited-path:line triples, deterministic every-8th). **No mismatch found.** Representative verified
  rows: `01:100 → SignalingClient.kt:145 const val MAX_REJOIN_ATTEMPTS = 10`; `03:179 → SignalingClient.kt:122`
  (the 63 s budget comment); `03:305 → AppConfig.kt:69 const val ICE_POLICY_RELAY = "RELAY"`;
  `04:208 → deploy/signaling.service:29 Restart=always`; `05:179 → CallSession.kt:100 const val MAX_ICE_RESTARTS = 2`;
  `05:185 → WebRtcConfig.kt:105` (`turnTcpFallback` doc-comment); `11:37 → CallSession.kt:812 gate(...)`;
  `SPEC:94 → WebRtcEngine.kt:243 "org.jni_zero.GEN_JNI"`. Two rows deserved a second look and passed:
  `07:27 → ../env.sh:42` (workspace-root convention, claim true) and `08:435 → vp9_encoder.cpp:84-90`
  (range starts on a blank line but covers the `t56` "diagnostic only, no longer rejects" comment block that
  supports the R-5 rejected-experiment claim).
* **Protocol six-way cross-check, independent of the generator:** Go `Type*` values (14), Go `Type*` constants (14),
  Kotlin `@SerialName` (14), generated `signaling-messages.md` §1 value column (14) and constant column (14), §2 (14),
  and `05-protocols.md` §3 catalogue (14). All value sets are **identical** to the Go source
  (`create created join joined peerJoined peerLeft offer answer ice natType leave error ping pong`); the constant set
  matches the Go constants exactly. No side extra or missing.
* **Device-log verification: all seven flows, 29 cited lines**, each opened at its exact line number, **0 missing** —
  e.g. `n1/app.log:40 room_created`, `n2/app.log:1223 ws_reconnect_scheduled … budget_ms=63000`,
  `n7/app.1.log:5991 ws_reconnect_give_up attempts=11 budget_ms=63000`, `n6/app.2.log:13492 room_not_found_action=keep_call`,
  `n1/app.log:129 ice_watchdog_started … timeout_ms=30000 fail_ms=45000`, `n6/app.2.log:6170 field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/`,
  `n4/native.1.log:1307 encoder_rate_floor floor_kbps=30`, `n2/webrtc.log:349 DroppedFrames`. No fabricated evidence.
* **Intensity decision:** `03` is byte-identical to its round-1 digest (`bef3e1b5…`); `06` has a different digest
  from the one t3 reported (`bd00eb2c…` vs `bb587cc7…`) but **identical size (28 998 B) and line count (504)**, i.e.
  an in-place edit rather than a substantial rewrite, so the narrowed scope applies — and the seven-flow verification
  above covers `06` substantively anyway. No escalation to the full round-1 sweep was warranted.

## 7. Known limitations carried forward (not defects)

1. `p5-artifact` has **no failure path**: an unmarked, non-existent gitignored build product can never fail
   (intentional per SPEC V13; `note()` output is not counted). Source of the two advisories in §1.
2. The "host/workspace-only command names need in-document evidence" threshold is not tightened
   (captain-confirmed breadth difference).
3. `doc/design/11-coding-standards.md`'s pin is digest-only — see R2-01.

## 8. Findings

| id | severity | problem | required fix |
|---|---|---|---|
| **R2-01** | **low** | `doc/design/11-coding-standards.md:203` pins the frozen checker as `sha256 676d075a… (628 lines, 26545 bytes)`: it carries **no bearing commit** (`8fdb222`) and it still quotes line/byte counts, whereas the agreed fix form for F-02 was "final revision **+ commit**, no line counts". The digest itself is correct, so nothing is mis-stated — the pin is simply not in the form the ruling specified. | add the bearing commit `8fdb222` to the pin and drop the counts, or record explicitly why the form differs. One-line change; no re-run needed (the digest is unchanged). |

**Verdict caveat, stated explicitly:** every acceptance criterion is met, and criterion 3 permits a residual to be
recorded as a new finding id rather than blocking — which is what R2-01 does. If the captain instead treats the pin
*form* as a release gate, then F-02 is not fully closed and the verdict should be read as `needs_revision` pending
that one-line change. The gate itself, all artefact digests, the audit and the sampling are unaffected either way.

## 9. Verdict

**`pass`.**

* full gate `bash scripts/doc-verify.sh` → **exit 0**, `PASS (2421 checks, 2 warnings)`, **failures 0**;
* frozen revision bound three ways and unchanged across the run; `HEAD` advanced to `2b1fdcf` by hygiene commits
  and that is not a mismatch;
* freeze manifest re-verified: **17/17 artefacts OK**;
* F-01, F-03, F-04, F-05, F-06, F-07, F-08 **closed**; F-02 closed on the checker/manifest side with the pin-form
  residual recorded as **R2-01** (low);
* interruption-impact audit: **clean on all six items** — one `NEGATIVE EXAMPLE` marker prefix (compliant and
  discriminated), stale digests confined to a labelled historical arc, no `../../tmp` in prose and no probe count
  presented as a document-level count, SPEC v1.6.0 self-consistent with correct §2.3 polarity, and all 12 documents
  plus `_generated` (2721 checks) passing individually;
* round-2 sampling not weaker than round 1: 146-citation sample clean, protocol six-way identical, seven flows and
  29 raw log lines verified verbatim.

*Bindings: checker content digest `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` (26 545 B /
628 lines; carrying commit `8fdb222`; run-time `HEAD` `2b1fdcf`) and `reports/54-docs-freeze-manifest.md`
(`345de855…`, 17 artefacts re-verified). `6cc5a382…` was never part of the binding surface.*
