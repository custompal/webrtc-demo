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
| "`WARN typo-suspect` is raised for absent gitignored paths" | not implemented and not adopted; the tool emits an uncounted NOTE only (a clean checkout must not fail, and `git check-ignore` cannot distinguish "mistyped" from "not built yet") |
| "the checker scripts are not version-controlled, so old revisions cannot be re-run" | false: both scripts are tracked since commit `6cba372`; `git show HEAD:scripts/doc-verify.sh \| sha256sum` recomputes a pinned revision |

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
