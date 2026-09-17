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
| host/workspace-only **command or flag name** | cite `reports/<file>:<line>` or the `_generated/host-commands.md` entry **on the same line** | missing same-line evidence ⇒ FAIL |

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
   the committed revision.
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

## 9. Post-terminal amendments to completed deliverables (ledger, so t20 does not read them as defects)

A task's `output` is immutable once completed, so amendments made after a task closed are recorded here instead.
Each entry states the file, the change, the cause, and the task that will carry it forward.

| File | Amendment after its task closed | Cause | Follow-up |
|---|---|---|---|
| `doc/design/07-build-and-deploy.md` | §5.1: `--allow-root` and its evidence `reports/41-apk-http-ownership.md:193` merged from two adjacent lines onto **one line** | the gate's SPEC §7.3 **same-line evidence** rule rejects the split form (`--allow-root is host/workspace-only and is cited without in-repository evidence`) | applied by the author; fingerprint recorded in `tmp/probe-forms-matrix-writer-ops.md` |
| `doc/design/11-coding-standards.md` | §8.1 "Document path notation (frozen)" + §8.1.1–§8.1.4 (marker doctrine; same-line command evidence; P4 container root; revision-pinned evidence) + migration note added | carried the captain's ruling into the coding standards; §8.1.4 was added by a captain instruction after the task closed | finalised under **t19**; registered here because a completed task's `output` is immutable. Digest at registration: `3078e3be14139252e2283e922e57037824d8bb03c674ecb52bf7496b970d8181` (236 lines / 18 805 B), committed in the pause-hygiene commit |
| `doc/design/08-issues-and-solutions.md` | §7.1 reframed to the rule-inversion story | the observer's own "not version-controlled" sentence was factually wrong | rewritten under **t18** against the frozen revision |
| `doc/design/SPEC.md` | v1.5.0/1.5.1/1.5.2 tag-mandatory drafts | superseded ruling; the drafts also introduced the two `# correct` marker examples that the restored FAIL severity then flagged | reverted under **t15** by whole-file restore from `git show e093e8f:doc/design/SPEC.md` (v1.4.0) |
| `doc/design/10-code-map.md` | document map switched to real relative links; `not yet written` text dropped | all chapters had landed | closed under **t12**; the root `README.md` is deliberately not linked yet because it does not exist until **t8** |

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
