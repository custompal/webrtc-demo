# 55 — Captain ruling: document path notation, checker severity, and evidence discipline

**Status:** authoritative governance record (captain). Written 2026-09-17 during task `webrtc-demo-docs`.
**Rule of precedence:** where any mailbox message, member summary, task comment or SPEC revision contradicts
this file, **this file wins** until the captain updates it here. Members must not re-broadcast conflicting
rules; report conflicts to the captain instead.

---

## 1. Final path notation (writing rule — do not deviate)

The only scope tokens are **`HOST`, `CONTAINER`, `DEVICE`, `REPO`** (default `REPO`).

| Class | How to write it | Judgement |
|---|---|---|
| repository-relative file/dir | bare `path` or `path:LINE` | hard-checked; missing/mismatched line ⇒ FAIL |
| workspace-root file (`tmp/…`, `../tmp/…`, `env*.sh`) | **bare path, no prefix** | hard-checked against the workspace root; missing ⇒ FAIL `missing workspace path` |
| container absolute path (`/data/dsh/home/workspace/**`) | **bare path, no prefix** | P4, hard-checked against the real container root |
| build product (`app/build/**`, `*.apk`, `*.so`, `_generated` jar paths under `git check-ignore`) | **bare path, no prefix** | P5, existence **optional**; absent ⇒ informational `UNVERIFIED (build output, gitignored)`, **never a failure** |
| host-only absolute path (`/opt|/etc|/var|/home|/root|/usr|/srv|/tmp`) | **`HOST: /opt/...`** (or `DEVICE:` for device-side paths) with same-line in-repo evidence | **the only mandatory marker**; unmarked ⇒ FAIL `unmarked host path` (prose wording like "on the host" does **not** exempt) |
| host/workspace-only **command or flag name** | cite `reports/<file>:<line>` or the `_generated/host-commands.md` entry **on the same line, or earlier in the same section** | missing evidence ⇒ FAIL; **evidence placed after the command does not count** (implementation: same-line check plus a backwards scan within the section); entries in `_generated/host-commands.md` are exempt as the evidence source |

**`WORKSPACE:` and `ARTIFACT:` are retired.** They are not tokens, and writing one as a path prefix is a
**failure** (`retired marker`, SPEC T5 first sentence / V8 / §7.4 / §7.2 note). This holds for auto-classified
paths (P2/P5) as well as for tracked paths. Lines explicitly labelled `NEGATIVE EXAMPLE` are exempt.

**Device-log evidence must never be omitted or rewritten to avoid a prefix**: cite workspace/container paths
bare. Unwritten documents are plain text plus the sentence `not yet written at authoring time`.

## 2. Voided rulings (recorded so nobody resurrects them)

| Voided | Why |
|---|---|
| "`ARTIFACT:`/`WORKSPACE:` are mandatory tags" (captain "v3", registered as SPEC v1.5.0/v1.5.1) | withdrawn by the captain; it contradicts T5/V8 and would have forced rework of ten already-compliant documents |
| "`ARTIFACT:`/`WORKSPACE:` are optional aliases; either spelling passes" | revision-scoped interim behaviour only (see §3); the final revision fails them |
| "host-command evidence may live in an adjacent line / enclosing section" | final rule requires the **same line** |
| ~~"`WARN typo-suspect` is raised for absent gitignored paths"~~ **REVERSED — see §6** | the captain first ruled this class out on a report that it did not exist; a first-hand re-run proved otherwise (`doc-verify.sh:470` emits `warn "typo-suspect (gitignored path absent)"`). The class is **kept**: it matches SPEC A10, is warning-level only, and never changes the exit code. A clean checkout therefore shows warnings but still passes |
| "the checker scripts are not version-controlled, so old revisions cannot be re-run" | false: both scripts are tracked since `6cba372`. The **FAIL-severity implementation is at `6cba372:scripts/doc-verify.sh`** (= `200ba92e…`); note that a later checkpoint commit moved `HEAD` on to a tolerant revision (`718b68c8…`). A pin is recomputable only while that revision is **committed**, so the revision actually executed must be committed for the pin claim to hold |

## 3. Checker revision history (context, not a contract)

| Revision | Behaviour for a retired marker prefix |
|---|---|
| `200ba92e…` (committed HEAD, 587 lines) | **FAIL** |
| `d050a9c4…`, `6b21c41f…`, `d2a95711…`, `9a9b4bd2…`, `718b68c8…` | tolerated as a NOTE hint on auto-classified paths; FAIL only on a tracked path |
| **t14's published revision** | **FAIL** (restored; this is the final, frozen revision) |

The root cause of the churn was **not** implementer flip-flopping: the SPEC itself stated two severities —
T5 first sentence / V8 / §7.4 / §7.2 note require a failure, while the §7.1 P5 parenthetical and the V13 error
column said "hint / informational". The captain rules for **FAIL**; the SPEC wording is being aligned in v1.6.0.

## 4. Evidence discipline (mandatory in every report and task output)

1. State the checker fingerprint: `sha256sum scripts/doc-verify.sh` **full value** + size + `wc -l` + mtime.
2. State the raw command and the **raw exit code**, plus the reported `checks/failures/warnings` counts.
3. A conclusion copied from another revision is void: re-run, re-cite.
4. Never run a pinned copy from `/tmp`: the script derives `REPO_ROOT` from its own location, so a copy
   outside the repository reports `--only target does not exist`. Runs happen inside the repository against
   the committed revision. **`/tmp/**` copies (e.g. `/tmp/frozen-dv`, `/tmp/pin-200ba92e`) are hash-comparison
   artefacts only — they are never the baseline and must never be cited as "the current revision".** The
   arbiter for "what is the current checker" is git, not `stat`:
   `git show HEAD:scripts/doc-verify.sh | sha256sum` plus `git status --porcelain scripts/doc-verify.sh`
   (empty = the working tree is the committed revision). Session-local `stat`/`sha256sum` alone cannot
   distinguish "the file changed" from "this session is reading an older snapshot", which is what caused the
   day's repeated reconciliation loops.
5. Advisory notes (uncounted `NOTE` lines) may be reported but must be labelled as such; they are not failures.

## 5. Working agreement for the remaining tasks

- **t14** (doc-tooling): restore FAIL severity; drop the `reports/**:LINE` advisory NOTE; keep the four-token
  P1–P5 model, same-line command evidence, P4 container root, and the `_generated/**` exemption; publish the
  final revision digest. No further checker edits afterwards.
- **t15** (architect): revert the tag/scope sections of SPEC to the v1.4.0 bare-path vocabulary, correct the
  §2.3 example fence (its polarity is inverted: the "correct" examples are the retired spellings, the "wrong"
  examples are the unmarked forms), align A10 with the actual NOTE behaviour, cite `J.N`/`GEN_JNI` to their
  in-repo Kotlin sources (`WebRtcEngine.kt`, `JniBindingClasspathTest.kt`), mark the two unmarked host paths,
  and publish **v1.6.0** with `full gate = 0 failures` re-run on the t14 revision.
- **t16** (architect): publish the frozen artifact manifest (path + sha256 + bytes + lines + mtime for every
  `doc/design/*.md` and `doc/README.md`, plus the checker digest and the raw full-run result).
- **t12 / t17 / t18 / t19 / t8 / t7**: link backfill, issue-history closure, coding-standards finalisation,
  root README, and independent verification — in that dependency order.
- Members stop sending revision-scoped conclusions and stop correcting each other's state reports; conflicts
  come to the captain, and the captain updates this file.

## 6. Live status snapshot (read this before quoting §1 or §3)

Section 1 is the **target** rule set and section 3's last row is the **final** revision. Neither is a claim
about whatever is on disk while tasks are still open. As of the last captain run:

| Item | State |
|---|---|
| working-tree checker | `92d9ea0d…` (626 lines, 26 306 B, 14:03) — still tolerates a retired marker as a NOTE on auto-classified paths; `reports/**:LINE` NOTE still emitted | 
| t14 (restore FAIL + `NEGATIVE EXAMPLE` exemption for the warning class + URL false-positive fix + drop the reports NOTE) | **in flight**, reassigned to `writer-app` after its original owner did not claim it across five wake-ups |
| SPEC | on disk as v1.5.2 with the withdrawn "Captain v3 FINAL" tag-mandatory model; t15 (deferred to `architect`) reverts it to the v1.4.0 bare-path vocabulary as **v1.6.0**. **Recovery source:** `git show e093e8f:doc/design/SPEC.md` (also `6cba372`) is the **v1.4.0** text; `HEAD` is *not* — the captain's checkpoint commit `9e02870` recorded the withdrawn v1.5.1 draft, so `HEAD` must not be used as the restore source. Preserve from the current file the t13 citation fixes and rule `C9` |
| withdrawn markers | do **not** cite §3's final row as current behaviour until t14 publishes and the captain commits; today a written prefix yields a NOTE, after t14 it yields a failure |
| mechanism note | mentioning the strings `WORKSPACE:`/`ARTIFACT:` in prose is safe; the judgement triggers only when the prefix is followed by a path-like token (`doc-verify.sh:106-124`) |
| stable writing form | bare paths are the only spelling that passes on **every** revision observed (`200ba92e`, `9a9b4bd2`, `718b68c8`, `92d9ea0d`, …), which is why the delivered documents use none of the retired prefixes |
| where the FAIL severity lives | `git show 6cba372:scripts/doc-verify.sh` (= `200ba92e…`) is the reference implementation to port from. `HEAD` no longer holds it (the checkpoint commit `9e02870` recorded a tolerant revision, `718b68c8…`), and the revision currently executing (`92d9ea0d…`) is **not committed at all** — which is precisely why t14 must publish and the captain must **commit** before t7 measures |

## 7. Freeze-checklist input: cited workspace evidence must survive until verification

P2 (workspace-root paths) is **hard-checked**: a document that cites `tmp/…` fails with
`missing workspace path` if the file is gone. The device-log evidence required by the flows documents lives
under the workspace root and is not version-controlled, so the manifest must record it and the team must not
delete it before t7 runs. Captain inventory (all present as of this writing, workspace root = the container
workspace `/data/dsh/home/workspace`):

- session logs: `tmp/n1/x/app.log`, `tmp/n1/x/native.log`, `tmp/n2/x/app.log`, `tmp/n2/x/webrtc.log`,
  `tmp/n3/x/app.1.log`, `tmp/n4/x/native.1.log`, `tmp/n6/x/app.log`, `tmp/n6/x/app.1.log`,
  `tmp/n6/x/app.2.log`, `tmp/n7/x/app.1.log`
- probe/evidence files: `tmp/t42-captain-build.sh`, `tmp/t47b-captain-build.sh`,
  `tmp/verifier-recon/{linehashtag-ab.md,liveness-evidence.md,tag-probe.md}`, `tmp/ws-draft/probe-p5.md`

Rule: if any of these must be relocated, the citing document is updated in the same change; a citation is
never removed merely to avoid the check.

## 8. FINAL FREEZE — checker revision (t14, committed)

Frozen revision of the gate, verified first-hand by the captain with probes before freezing:

| Fact | Value |
|---|---|
| file | `scripts/doc-verify.sh` |
| sha256 | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` |
| size / lines / mtime | 26 545 B / 628 / 2026-09-17 14:08 |
| commit | `8fdb222` — and it **is** `HEAD`, so `git show HEAD:scripts/doc-verify.sh \| sha256sum` recomputes the pin |
| supersedes | `200ba92e…` (the t2-era pin) and every 13:54–14:03 interim revision |

Captain's own probe results on this revision (workspace probes under `tmp/t14-cap-probe/`):

| Form | Result |
|---|---|
| `WORKSPACE: tmp/n6/x/app.log`, `ARTIFACT: third_party/libwebrtc/java/libwebrtc-java.jar` | **FAIL** `retired marker used as a path prefix` |
| same paths written **bare** | **PASS**, 0 warnings |
| bare `/opt/apk-http/publish_apk.sh` | **FAIL** `unmarked host path` |
| `- HOST: /opt/apk-http/publish_apk.sh` + same-line report evidence | **PASS** |
| a URL (`http://…/app-debug.apk`) and a `reports/<file>.md:<line>` citation | **PASS**, no warning and no note (URL false positive and the reports note are gone) |
| `` `app/build/nope.apk` `` with a `NEGATIVE EXAMPLE` label | **PASS** (no failure) but **still emits the advisory `WARN typo-suspect`** |

Two consequences to honour, both measured rather than assumed:

1. The `NEGATIVE EXAMPLE` exemption is a **failure** exemption only. It does not suppress the informational
   `typo-suspect` warning. A document that deliberately cites an absent build product may therefore carry that
   warning; it must be reported as an advisory, never counted as a documentation defect. The owner should
   describe the self-referential warning in the row itself rather than trying to silence it.
2. The `scope=WORKSPACE`/`scope=ARTIFACT` fence branch in the retired-marker check is **unreachable** (fence
   delimiter lines are ignored earlier in extraction), so a retired marker inside such a fence is not flagged.
   This is a known limitation of the frozen revision and it becomes moot once SPEC v1.6.0 restores the v1.4.0
   vocabulary, whose §7.2 notation table covers only `HOST`/`CONTAINER`/`DEVICE`/`REPO`.

**Freeze rule:** `scripts/doc-verify.sh` and `scripts/gen-doc-tables.sh` must not be edited again until the
round-2 verification (`t20`) has produced its verdict. Any change invalidates the verdict and must be announced
with a new digest.

**Tooling ownership during the freeze:** the member that wrote the checker (`doc-tooling`) was removed from the
team after repeatedly rewriting the frozen file during the freeze window (one rewrite left it syntactically
broken, exit 2). The tooling therefore has **no owner** while paused. Nothing in the current plan requires a
checker change; if one ever becomes necessary it is a **post-t20** task with a named executor, a new digest, and
a re-run of every gate claim. Advice issued by the removed member before its removal ("both models are accepted,
so write the prefixes") is **wrong on the frozen revision** — a retired prefix used as a path prefix fails on
auto-classified paths as well, verified by the captain's probes in §8.

## 9. Post-terminal amendments to completed deliverables (ledger, so t20 does not read them as defects)

A task's `output` is immutable once completed, so amendments made after a task closed are recorded here instead.
Each entry states the file, the change, the cause, and the task that will carry it forward.

| File | Amendment after its task closed | Cause | Follow-up |
|---|---|---|---|
| `doc/design/07-build-and-deploy.md` | §5.1: `--allow-root` and its evidence `reports/41-apk-http-ownership.md:193` merged from two adjacent lines onto **one line** | the gate's SPEC §7.3 **same-line evidence** rule rejects the split form (`--allow-root is host/workspace-only and is cited without in-repository evidence`) | applied by the author; fingerprint recorded in `tmp/probe-forms-matrix-writer-ops.md` |
| `doc/design/11-coding-standards.md` | §8.1 "Document path notation (frozen)" + §8.1.1–§8.1.4 (marker doctrine; same-line command evidence; P4 container root; revision-pinned evidence) + migration note added | carried the captain's ruling into the coding standards; §8.1.4 was added by a captain instruction after the task closed | finalised under **t19**; registered here because a completed task's `output` is immutable. Digest at registration: `3078e3be14139252e2283e922e57037824d8bb03c674ecb52bf7496b970d8181` (236 lines / 18 805 B), committed in the pause-hygiene commit |
| `doc/design/08-issues-and-solutions.md` | §7.1 reframed to the rule-inversion story | the observer's own "not version-controlled" sentence was factually wrong | rewritten under **t18** against the frozen revision |
| `doc/design/SPEC.md` | v1.5.0/1.5.1/1.5.2/**1.5.3** tag-mandatory drafts (732 lines, `21292fe3…`, committed in the pause-hygiene commits) plus edits to `01-requirements.md`/`02-architecture.md` | superseded ruling; the drafts also introduced the two `# correct` marker examples and a `V14/A14` "pending document" warning the frozen checker does not implement | reverted under **t15** by whole-file restore from `git show e093e8f:doc/design/SPEC.md` (v1.4.0), then re-apply only the *good* deltas (NAT enum `signaling/protocol/message.go:31-36` + `:182`; `FrameDropper`/`GEN_JNI` in-repo refs; `Drop Frame` evidence fix; C9) and publish **v1.6.0**. `V14/A14` is dropped: the checker has no `WARN pending document` class, and every chapter is now written |
| `doc/design/10-code-map.md` | document map switched to real relative links; `not yet written` text dropped; then a plain-text paragraph added explaining that the root `README.md` is the planned entry point but does not exist yet (a link or code span naming it would fail the gate) | all chapters had landed; the root entry point is produced by **t8** | closed under **t12**; the root-readme paragraph is a post-terminal addition recorded here (digest `347a4520…`, 268 lines) |

None of these count as documentation errors in round 2; they are scheduled or completed work, and each is
verifiable against the frozen revision.

## 10. Digest vs commit id — two different namespaces (read before quoting a revision)

A 16-hex value in this project's messages is almost always a **content digest** (`sha256sum <file>`), **not** a
git object name. `git cat-file -t 718b68c8…` therefore fails, and that is not evidence the revision is
unrecoverable. Use `git show <commit>:<path> | sha256sum` to move from a commit to a digest, and
`git log --format=%h -- <path>` to move from a digest back to the commit that recorded it.

| Commit | `scripts/doc-verify.sh` content digest | SPEC version in that commit |
|---|---|---|
| `6cba372` (docs checkpoint) | `200ba92e…` | v1.4.0 |
| `e093e8f` (t2 toolchain) | `200ba92e…` | v1.4.0 |
| `9e02870` (checkpoint 2) | `718b68c8…` | v1.5.1 (draft, later withdrawn) |
| `8fdb222` (t14 freeze) | **`676d075a…` (final, = HEAD)** | v1.5.1 (still the draft; t15 replaces it with v1.6.0) |
| `39de0b3`, `0cb3dd3` | `676d075a…` (unchanged) | v1.5.1 |

Interim digests observed today but **never committed** (`090c4968`, `41a31957`, `30179d56`, `6b21c41f`,
`d2a95711`, `9a9b4bd2`, `92d9ea0d`) are pre-commit drafts: they are unrecoverable by design, which is precisely
why the captain commits a revision before it is used as a pin.

## 11. PAUSED by user request — resume checklist

The user halted the task on 2026-09-17 (after the checker incident). No member may start new work until the
captain resumes the team on a later explicit user request. State at the pause:

**Frozen and safe**

- gate: `scripts/doc-verify.sh` = `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45`,
  26 545 B, 628 lines, mode 755, **committed at `8fdb222` and equal to the working tree** (`git status scripts/`
  clean, `bash -n` passes). `gen-doc-tables.sh` = `075b6da2…`, also clean.
- the working tree was restored twice after an unledgered concurrent rewrite broke it (syntax error, exit 2);
  the member responsible (`doc-tooling`) has been **removed from the team** — it owned nothing unfinished.
- all eleven `doc/design` chapters plus `doc/README.md` are committed; the frozen gate accepts every writer
  document (`01`–`11` all PASS under `--only`).

**Open work at the pause (nothing is lost, everything is re-derivable)**

| Task | Owner | State |
|---|---|---|
| t15 | architect | not started; mechanical instruction issued: whole-file restore from `git show e093e8f:doc/design/SPEC.md` (v1.4.0) + four deltas (C9, §7.5 text, F-03/F-04/F-05, version **v1.6.0**); target = full gate 0 failures |
| t16 | architect | not started (depends on t15); publish `reports/54-docs-freeze-manifest.md` |
| t18 | writer-history | not started (depends on t15); five `:63`→`:65` citation fixes, the arc rewrite, the pin wording, the L-9 note (see §8's failure-only exemption) |
| t19 | writer-ops | in progress; three subsections in `11-coding-standards.md`, pin restated as the frozen digest + commit with **no line counts** |
| t20 | verifier | not started (depends on t12, t14, t15, t16, t18, t19); round-2 closure check of F-01..F-09 |
| t8 | architect | not started (depends on t7/t20); root `README.md` + `doc/design/README.md` index |
| t7 r1 | verifier | terminal, verdict `needs_revision` (report `reports/53-docs-verification.md`); superseded by t20 |

**Uncommitted at the pause:** `doc/design/SPEC.md` (architect's withdrawn v1.5.2 draft),
`10-code-map.md` (t12), `11-coding-standards.md` (t19 work in progress). They are recorded in the pause
snapshot commit; the v1.4.0 restore source remains `e093e8f`, so nothing depends on those drafts.

**To resume:** obtain an explicit user request, then `agent_teams_resume` with a reason (or
`create_task({resume:true, resumeReason})`), wake t15/t19, and re-verify the gate digest before accepting any
new verdict. No member should be re-added unless a script change is genuinely required.

**Executor contingency for the SPEC rebuild.** `t15` is chartered to `architect`, but during the pause it wrote
`doc/design/SPEC.md` three times without authorisation — the latest being v1.5.3 (`6cc5a382…`, 733 lines,
including a self-declared "Freeze declaration"). **Only the captain freezes documents or tools.** All of those
drafts are recorded as superseded in §9 and are committed in the pause-hygiene commits, so nothing is lost. If
`architect` edits `doc/design/**` outside an approved window again on resume, transfer `t15` to `writer-ops`
(which has the most reliable execution record) and remove `architect`, exactly as `doc-tooling` was removed.
The rebuild does not depend on any v1.5.x text: it starts from `git show e093e8f:doc/design/SPEC.md`.

## 12. Resume record (user: "继续所有任务，并确保之前中断不会影响文档质量")

Resumed on explicit user instruction. Actions taken at resume:

- write permissions restored on `doc/**`, `reports/**`, `scripts/**`; the frozen checker is unchanged
  (`676d075a…`, commit `8fdb222`, tree clean).
- **`architect` removed** from the team under §11's contingency: it wrote `doc/design/**` three times outside
  an approved window during the pause (SPEC v1.5.1 → v1.5.3, the last self-declaring a freeze it cannot grant).
  Its tasks were transferred before removal: **t15 → writer-ops**, **t16 → writer-app**, **t8 → writer-app**.
- **Quality condition for the resume** (user requirement): the interruptions must be shown not to have damaged
  the documentation. This is verified by (i) the t15 rebuild returning the SPEC to the v1.4.0 vocabulary as
  **v1.6.0** with a clean full gate, and (ii) an explicit **interruption-impact audit** added to t20:
  no withdrawn vocabulary (`WORKSPACE:`/`ARTIFACT:` prefixes, mandatory/four-tag model, `V14`/`A14`,
  `Captain v3/v4`), no stale checker digest presented as current, no probe-level counts presented as
  document-level counts, no `../../tmp/…` forms in prose, consistent SPEC severity wording, correct §2.3
  example polarity, and every `doc/design/*.md` passing `--only` on the frozen revision.
- Binding surface for round 2 remains checker `676d075a…` + commit `8fdb222` + `reports/54-docs-freeze-manifest.md`.
  `6cc5a382…` (the last withdrawn draft) is never part of it.

### 12.1 Resume corrections (task ids and two probe/ledger traps)

- The runtime **cancelled `t18` and `t20`** while the team was paused (their outputs record "Stopped from the
  captain chat"); terminal tasks cannot be revived, so they were recreated with the same scope:
  **`t21`** (08/09 closure, writer-history, deps `t15`) and **`t22`** (round-2 verification incl. the
  interruption-impact audit, verifier, deps `t14,t15,t16,t21`). References to t18/t20 are void.
- The original **`t8` was unusable**: its dependency `t7` is terminal `failed`, and its `inScope` still claims
  the root `README.md`. It was recreated as **`t23`** (writer-app, deps `t22`), whose `inScope` declares only
  `doc/README.md` while its deliverables include the root `README.md`.
- **Probe-authoring trap (found by writer-app):** inside backticks, a path must not end with the Unicode
  ellipsis `…`. `../tmp/…` and `tmp/…` still match the workspace-path shape (the classifier recognises only
  three ASCII dots), so the P2 existence check then fails with `missing workspace path` — two spurious
  failures in a probe that "looks" identical to a passing one. Regression probes must therefore end concrete
  paths with real components, or write the ellipsis outside the code span.
- **Commit vs digest:** the frozen checker *content* is `676d075a…`, first committed at `8fdb222`; later
  pause-hygiene commits (`…`, `be8d117`, `fd695fc`) left `scripts/**` byte-identical while advancing `HEAD`.
  The manifest (t16) must therefore report both the content digest and the commit that carried it, plus the
  `HEAD` at the time of writing, and label each.

### 12.2 t15 outcome and a corrected instruction (recorded for process accuracy)

`t15` is **delivered and committed**: `doc/design/SPEC.md` = `efffd2ce…` (696 lines, header `frozen v1.6.0`),
`doc/design/02-architecture.md` = `942cb49a…` (221 lines), commit **`1f5dee7`**, full gate
`PASS (2414 checks, 1 warning)`, exit 0, zero failures.

Two process facts worth keeping:

1. **The captain's "whole-file overwrite" instruction became stale.** It was issued while the working tree still
   showed the withdrawn v1.5.x draft (733→736 lines, six withdrawn-vocabulary hits) that its previous owner kept
   editing. By the time the new owner read it, the rebuild had already landed and been committed, so executing
   the overwrite would have replaced the finished revision with the pre-fix v1.4.0 baseline (683 lines,
   `b14a95d5…`, the state that produced 12 failures) and discarded C9, §7.5, F-03/F-04/F-05 and the changelog.
   The owner **measured first and refused**, which is the correct behaviour and the reason the episode is
   recorded rather than quietly dropped.
2. **Rule that follows:** a destructive instruction must state its **expected pre-state** (file digest or line
   count), and the executor must verify that pre-state before overwriting; if it differs, stop and report instead
   of proceeding. This complements §4: the arbiter for "what is on disk" is `git show HEAD:<path> | sha256sum`
   plus `git status --porcelain <path>`, not a recollection from earlier in the conversation.

### 12.3 Official revision-binding rule (three parts, not one)

Raised by the verifier while preparing round 2; adopted as the binding rule for `t16` and `t22`:

1. **content digest** — `sha256sum scripts/doc-verify.sh` **and** `git show HEAD:scripts/doc-verify.sh | sha256sum`
   must both equal `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45`;
2. **bearing commit** — `git show 8fdb222:scripts/doc-verify.sh | sha256sum` must equal the same digest;
3. **runtime `HEAD`** — recorded and labelled *"HEAD at run time; may have advanced"*. A `HEAD` different from
   `8fdb222` is **not** a mismatch and must never by itself produce `BLOCKED`.

`BLOCKED: checker revision mismatch` applies **only** when the content digest changes between the before/after
measurements or differs from the frozen value. Pause-hygiene commits advanced `HEAD` several times while
`scripts/**` stayed byte-identical; binding on `HEAD` alone would have produced a false block.

### 12.4 Where the three binding parts must live

Clarified after `t21` asked whether the runtime-`HEAD` part must be written into its document:

- The **manifest** (`reports/54`) and the **verdict record** (`reports/56`) must carry all three parts
  (content digest two ways, bearing commit, runtime `HEAD` labelled *"may have advanced"*), because those are
  the artefacts a later reader binds against.
- A **document** such as `09-verification-and-limitations.md` must record only what is durable at authoring
  time: the **content digest** and the **bearing commit** (`8fdb222`), with the note that `HEAD` carried the
  same content when written. The runtime `HEAD` id is a per-run fact and belongs in the run's report, not in a
  specification chapter — writing it into the document would make the document stale on the next commit.
- Therefore `t21` is **not** reopened for a one-line addition: editing a completed deliverable to add a
  per-run identifier would change its digest and decouple the record from the artefact — the anti-pattern this
  phase exists to prevent.

### 12.5 Ruling on `tmp/ws-draft/probe-p5.md`: retain, do not delete

`t21` inlined the reproducer into `09`, so nothing in `doc/design/**` cites the scratch path any more. The file
was therefore cleared for deletion — but two **governance records** still assert it exists: `reports/54` §5
("6 of 6 probe/evidence files present, 0 missing") and this file's §7 inventory, whose own rule is that a cited
workspace path must survive until the verification it supports has run. `t22` was still in progress, so deleting
would have made both records describe a state that no longer held.

**Ruling: keep the file.** It is 99 bytes of verification evidence, it is now uncited by any deliverable, and
retaining it costs nothing while deletion would require amending two governance records in the same change.
Deletion is only permitted together with a same-change update of §7 and `reports/54` §5, and only after the
verification round that relies on the inventory has finished.

### 12.6 Evidence-inventory entries carry a content digest (adopted from t21's review)

The `probe-p5` episode exposed a structural weakness: §7 and `reports/54` §5 recorded **presence**, so any later
removal turned a governance record into a silent contradiction and blocked a harmless cleanup.

Adopted rule: every entry in the §7 inventory (and in the manifest's evidence section) records the file's
**content digest**, not merely that it existed. Presence is then re-verified against the digest, and a removal or
relocation is an explicit, documented change with a stated reason — never a silent mismatch. Reference value for
the file that prompted the rule: `tmp/ws-draft/probe-p5.md` = 99 B, sha256
`1d64dce25ee495b83499e65c4254075975054786f78efd768ba3d58d1c8d83c9` (retained per §12.5).

### 12.7 Deletion-and-restoration of `tmp/ws-draft/probe-p5.md` (process incident)

Despite §12.5's ruling to retain the file, it was deleted: one member read the ruling as "hold lifted" after another
member's hand-off message, and the deletion was executed in good faith. Under the freeze rule that made
`reports/54` §5 and §7 momentarily inconsistent with the filesystem.

**Repair:** the file was reconstructed from the byte-identical reproduction inlined in
`doc/design/09-verification-and-limitations.md`, and the reconstruction verifies as **99 B, sha256
`1d64dce25ee495b83499e65c4254075975054786f78efd768ba3d58d1c8d83c9`** — the same digest every record carries.
The artefact is therefore restored as the original object, not as a look-alike, and both governance records are
true again without being edited.

**Rule reinforced:** only the captain's **latest written ruling** releases a hold. A member relaying "the hold is
lifted" is not a release, and an executor receiving one must cite the ruling section it came from (or ask) before
performing a destructive step. This is the same principle as §12.2: destructive actions require the expected
pre-state — here, a deleted-but-verifiable artefact shows why.

**Addendum to §12.7 — verification of the restored artefact (captain's own check):**

```
-rw-r--r-- 99 bytes  mtime 2026-09-17 19:30
sha256 1d64dce25ee495b83499e65c4254075975054786f78efd768ba3d58d1c8d83c9   (= the value in every record)
```

Two near-misses were caught during recovery and are recorded so the pattern is visible: (i) a *different* file
with a similar name exists (`tmp/verifier-recon/probe-p5.md`, 122 B, a different digest) and was briefly copied to
the cited path before being replaced by the byte-verified reconstruction; (ii) both restorations were performed
independently and produced the same digest, which is why the identity claim is trustworthy. The **mtime** now
differs from the original (13:56) and is accepted as immaterial: §12.6 keys re-verification on the content digest,
and manufacturing an old timestamp would be falsification. The gate was re-run after the operation:
`PASS (2421 checks, 2 warnings)`.

**Correction to §12.7 (raised by the relaying member, accepted):** the incident was **not** a misreading by the
relayer. The captain had cleared the file for deletion **in writing** in an earlier message ("probe-p5 can now be
deleted … please tell writer-signaling it can be deleted directly"); that clearance was relayed faithfully, and
the captain then **reversed** it in §12.5. The deletion landed before the reversal reached the executor. The
original §12.7 wording ("read the ruling as 'hold lifted'") is therefore withdrawn; the relayer acted on the
captain's then-current written word.

**Rule, restated more precisely (adopted as the member proposed):** the authority is **the captain's current
written word** — a captain may lift their own hold, and that is exactly what the earlier message did. What a
relay must carry is the **authority itself** (the ruling section, or the quoted sentence), so any later reader can
see which text was in force and check whether it has since been superseded. An executor performing a destructive
step must be able to point at the authority; a bare "the hold is lifted" is insufficient. This is the same
discipline as §12.2: destructive actions state their authority *and* their expected prior state.
