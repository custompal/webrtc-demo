# 11 — Coding Standards and Change Safety

> Status: draft · Owner: writer-ops · Task: t9
> Evidence base: current HEAD source tree, reports/20-encode-resize-crash.md, reports/26-libvpx-runtime-cpu-detect.md, reports/47-vp9-encode-perf.md, reports/51-frame-dropper-and-trusted-rc.md, reports/52-release-closure.md
> Doc standard: `doc/design/SPEC.md`

## 1. Scope

This document states the conventions that the shipped code already follows, so that new changes stay
consistent with it, and the checks that must pass before a change is considered safe to land.

It deliberately does not restate behaviour; the architecture and protocol documents own that. It also does
not invent rules: every convention below is illustrated by a citation into the current HEAD source, or by a
report that records the defect the convention prevents. A rule without such a citation does not belong here.

## 2. General conventions

| Rule | Positive example | Why |
|---|---|---|
| Organise code by responsibility, one package per concern | `signaling/server/server.go:47` | the server package owns transport wiring only |
| Keep the entry point of a file explicit and greppable | `signaling/main.go:28`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` | makes a file readable from its first declaration |
| Comments state the contract or invariant, not the mechanics | `app/src/main/cpp/encoder/i420_rotator.h:95` | the comment fixes the stride contract the code relies on |
| Comments reference the defect or task that motivated a non-obvious choice | `app/src/main/cpp/encoder/vp9_encoder.cpp:67`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:29` | the next reader can find the history |
| Kotlin formatting follows the official style, configured once | `gradle.properties:21` | style is enforced by configuration, not by review |
| Go sources are gofmt-formatted and the toolchain version is pinned | `signaling/go.mod:3` | one canonical formatting, one compiler version |

A comment that restates the code is a liability: it drifts silently and then misleads. The counter-pattern
is a comment describing a mechanism that no longer exists; the reference style above is the fix.

References: `signaling/server/server.go`, `app/src/main/cpp/encoder/i420_rotator.h`, `gradle.properties`

## 3. Kotlin and Android

| Rule | Positive example | Counter-pattern it prevents |
|---|---|---|
| A state machine's side effects must not substitute for message judgement; publish state under the same lock that gates the decision | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:366`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:368` | a half-published peer connection that readers observe as ready |
| All transitions that touch shared ready-state go through the single gate | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:812` | scattered synchronisation with different locks |
| Keep one lock for the ready-state boundary and name it | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:160` | two locks that must be taken in a fixed order |
| Nullable results are handled at the boundary, never forced | `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:114`, `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:134` | a crash on malformed input instead of a rejected value |
| A released renderer must reject further attach instead of being reused | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:230` | use-after-release of a surface renderer |
| Renderer attach and release are logged with an explicit decision record | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:236` | silent double-attach that only shows up as a black preview |
| Log event name is a stable key; field names are snake_case | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:236`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523` | field names that cannot be parsed mechanically |
| Numeric fields carry a unit suffix | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1129`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1170` | a bare number whose unit must be guessed |
| High-frequency logs are rate limited and report what was dropped | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:24`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:89` | a log sink that floods and loses the diagnostic window |

The gate pattern is the load-bearing rule here. A transition that mutates ready-state outside the gate can
interleave with an inbound message that was judged against the old state; the defect class is a call that
answers before the peer connection is published. Suppression must likewise be visible: a suppressed
transition is logged rather than silently dropped (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523`).

References: `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt`

## 4. C++ and JNI

| Rule | Positive example | Counter-pattern it prevents |
|---|---|---|
| Check the real capacity of a direct buffer before writing, using the capacity accessor rather than an assumed size | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:157`, `app/src/main/cpp/jni/vp9_encoder_jni.cpp:199` | an out-of-bounds write into a caller-provided buffer |
| Register natives through the load hook, with the registration contract stated in the header | `app/src/main/cpp/jni/jni_bridge.cpp:57`, `app/src/main/cpp/jni/jni_bridge.h:9` | symbol drift between Kotlin declarations and exported names |
| Keep an explicit stride in every plane description instead of assuming a tight layout | `app/src/main/cpp/encoder/i420_rotator.h:43`, `app/src/main/cpp/encoder/i420_rotator.h:121` | rotated or padded frames read at the wrong offset |
| Thread count and row-level parallelism are tuning decisions with a recorded rationale | `app/src/main/cpp/encoder/vp9_encoder.cpp:251`, `app/src/main/cpp/encoder/vp9_encoder.cpp:392` | a silent performance regression after a defaults change |
| Every failure path emits an identifying log line with its inputs | `app/src/main/cpp/encoder/vp9_encoder.cpp:395`, `app/src/main/cpp/log/native_log.cpp:61` | a crash with no reproducible signature |

Two defect classes are recorded and must not be reintroduced. First, encoder crashes on frame geometry
changes were caused by handing libvpx a buffer it did not own; the fix copies into a self-owned image and is
recorded in `reports/20-encode-resize-crash.md` and `reports/22-encode-selfowned-image.md`. Second, the
runtime CPU dispatch regression caused by a build flag is recorded in
`reports/26-libvpx-runtime-cpu-detect.md`; build flags are part of the contract, not an optimisation detail.
The threading rationale is recorded in `reports/47-vp9-encode-perf.md`.

References: `app/src/main/cpp/jni/vp9_encoder_jni.cpp`, `app/src/main/cpp/encoder/vp9_encoder.cpp`

## 5. Go

| Rule | Positive example | Counter-pattern it prevents |
|---|---|---|
| Package boundaries are horizontal: transport, room state, protocol vocabulary, configuration, logging | `signaling/config/config.go:18`, `signaling/protocol/errors.go:5`, `signaling/logging/logging.go:20` | protocol constants leaking into transport code |
| Error codes are a closed vocabulary of string constants, mapped to default messages in one place | `signaling/protocol/errors.go:5` | ad-hoc error strings that clients cannot branch on |
| Room state is guarded by a single mutex; every mutation takes it | `signaling/room/room.go:43`, `signaling/room/room.go:67` | interleaved seat updates losing a peer |
| Timer callbacks carry a generation and are discarded when the generation has moved on | `signaling/room/room.go:24`, `signaling/room/room.go:75`, `signaling/room/room.go:89` | an expired callback destroying a room that was legitimately rejoined |
| Behaviour is covered by table-driven tests next to the code | `signaling/util/roomid_test.go:9` | untested parsing rules |

The generation rule is the idempotency rule: a grace timer that fires late must be able to prove it belongs
to the current occupancy before it acts (`signaling/room/room.go:24`). Without it, a rejoin racing an expiry
destroys a live room; the fix and its evidence are recorded in `reports/35-room-grace.md`.

References: `signaling/room/room.go`, `signaling/protocol/errors.go`, `signaling/util/roomid_test.go`

## 6. Scripts and build

| Rule | Positive example | Counter-pattern it prevents |
|---|---|---|
| Build and deploy scripts fail fast | `scripts/build_app.sh:31`, `scripts/deploy_signaling.sh:10` | continuing after a failed stage and publishing a stale artifact |
| Failures are recorded in an accumulator when the script must run every check | `scripts/build_app.sh:56` | a check that silently cannot run |
| A build window is silent: no source under `app/src` or `signaling/` may change during a build | see the build and deploy document | an artifact that corresponds to no single revision |
| Outputs are normalised to uid 1000 before the run ends | `scripts/build_app.sh:566` | root-owned build output that the next user cannot modify |
| Toolchain artifacts are pinned by hash and the pin is checked | `scripts/build_app.sh:467` | a silently swapped native library |
| Generated tables are produced by the generator, never edited | `doc/design/_generated/host-commands.md:3` | a hand edit that the next regeneration erases |
| The publish tool is host-only and is never cited as a repository file | HOST: `/opt/apk-http/publish_apk.sh` — see `reports/41-apk-http-ownership.md:192` | a document promising a script that does not exist in the tree |

`scripts/doc-verify.sh:33` deliberately does not use `set -e`: it must run every check and aggregate
failures, so an early exit would hide the remaining findings. That is the one intentional exception to
fail-fast, and it is only safe because the script performs no writes.

References: `scripts/build_app.sh`, `scripts/doc-verify.sh`, `scripts/deploy_signaling.sh`

## 7. Change safety checklist

Before a change: identify the invariants it touches (the gate, the library pins, the room generation); find
the report that recorded the last defect in that area; decide whether the change alters a build flag, since
build flags are contract.

During a change: keep the build window silent; do not edit generated tables; do not "fix" code to satisfy a
document — the document is updated instead (`doc/design/SPEC.md` §7 A13); if a check must be relaxed, record
why.

After a change: run the documentation gate on the affected documents; re-verify the citations that point into
the changed files; confirm `git status` shows only the intended paths; record the evidence in a report.

## 8. Documentation and code consistency

Citations use the form `path/file.ext:LINE` and are validated against the current HEAD source. Line numbers
in `reports/**` are historical and must never be reused as source citations; reports are referenced by name
and section. Generated tables carry a do-not-edit header and their regeneration command
(`doc/design/_generated/host-commands.md:3`).

When a document and the code disagree, the code is the truth and the document is corrected; when the
document and the checker disagree, the divergence is reported to the owner of the standard before anything
else changes. The delivery gate for a documentation change is the checker restricted to the changed files
(`doc/design/SPEC.md` §9).

### 8.1 Document path notation (frozen)

Path notation is frozen and mechanically enforced. Every path written in the documentation set falls into one
of five classes, and the class decides whether a marker is required, optional or forbidden.

| Path class | Correct form | Judgement |
|---|---|---|
| repository-tracked file | a bare repository-relative path with its line; no marker | existence and line are checked; a broken pointer fails |
| workspace-root file | a bare path, written either repository-relative with the parent prefix or workspace-relative | checked against the workspace root; a missing file fails |
| build product | a bare path | existence is optional; a missing product is recorded as unverified and never fails, so a clean checkout is never a failure |
| host absolute path | an explicit host scope marker on the same line, together with in-repository evidence on that line | an unmarked host path fails; this is the only mandatory marker |
| container absolute path | a bare absolute path, with any line number written in the surrounding prose rather than inside the code span | checked against the container root (see 8.1.3) |

#### 8.1.1 Marker discipline

Write no path-prefix marker other than the host and device scope markers. The two prefixes used by earlier
drafts of this document set are retired, and the rule is to write the bare path instead: the checker reports
any use of either prefix as a path prefix.

Bare is also the only form that survived the revision churn of the authoring session. The checker's verdict
on those two prefixes changed twice — failure, then tolerated hint, then failure again at the frozen
revision — while bare paths passed at every revision observed:

| Checker revision | Verdict on the two retired prefixes |
|---|---|
| 200ba92e42068c4a | failure |
| 6b21c41f7630dd6e, d2a95711dbedabf | accepted as a hint, recorded as a note |
| final frozen revision (digest below) | failure again |

A marker remains legitimate for exactly two purposes: scoping a host absolute path, and scoping a device
path. Every other path is written bare.

#### 8.1.2 Host command evidence must be on the same line

A name that is not provable from the repository itself — anything not listed as in-repo in the generated
command inventory — must carry its in-repository evidence on the **same line** as the name: either a report
location of the form used in the evidence index below, or an entry of the generated
`doc/design/_generated/host-commands.md` table. Evidence on an adjacent line does not satisfy the rule.
Names provable from repository scripts need no evidence, and the generated tables are themselves exempt.

This rule was added while the delivery was already in review, which is why one citation in the build and
deploy document is long. The flag `--allow-root` and its report evidence `reports/41-apk-http-ownership.md` §5
were on adjacent lines, and the gate failed with a message saying the flag was cited without in-repository
evidence; joining them onto one line resolved it, with no content added or removed.

#### 8.1.3 Container root

The container workspace root is class **P4**: an absolute path under it is checked for existence against the
real container root and needs no marker. This is the opposite of a host absolute path, which is never
existence-checked but does require the host scope marker. Conflating the two is the most common source of a
false "unmarked host path" report.

#### 8.1.4 Revision-pinned evidence

A statement that a given form passes or fails is evidence about one revision of the checker, never a
statement about the rule. Every such statement must carry the checker's full SHA-256 digest and its
modification time, the exact re-run command, and the raw exit code together with the failure and warning
counts.

A conclusion quoted without that fingerprint is void: it may not be reused, and it may not be restated as a
rule. When the checker changes, the probes are re-run before any conclusion is repeated, because the same
form has been observed to flip verdict across revisions. Prefer naming a probe file and its expected result
over restating the convention in prose, so that any reader can re-check the claim directly against
`tmp/probe-forms-matrix-writer-ops.md` (at the workspace root, outside the repository).

Migration note: legacy reports under `reports/**` and the archived documents under `doc/archive/**` contain
retired path prefixes and `file:LINE` forms that are no longer valid. Do not copy them into new documents.
Rewrite every path in the bare form described above, and cite a report by its name and section rather than
reusing its line numbers as source citations.

```
final frozen checker revision for this notation:
sha256 676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45
carried by commit 8fdb222 (recompute: git show 8fdb222:scripts/doc-verify.sh | sha256sum)
```

## 9. Evidence index

| Rule area | Citation | Verification artifact |
|---|---|---|
| Ready-state gate | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:812` | source |
| Renderer lifecycle | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:230` | source |
| Log rate limiting | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:89` | source |
| Direct buffer capacity | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:157` | source |
| Native registration | `app/src/main/cpp/jni/jni_bridge.cpp:57` | source |
| Row-level parallelism | `app/src/main/cpp/encoder/vp9_encoder.cpp:392` | `reports/47-vp9-encode-perf.md` |
| Room generation | `signaling/room/room.go:75` | `reports/35-room-grace.md` |
| Error vocabulary | `signaling/protocol/errors.go:5` | source |
| Fail-fast scripts | `scripts/build_app.sh:31` | source |
| Library pins | `scripts/build_app.sh:467` | source |
| Ownership normalisation | `scripts/build_app.sh:566` | source |
| Publish guard | HOST: `/opt/apk-http/publish_apk.sh` | `reports/41-apk-http-ownership.md:192` |
| Document path notation (frozen) | `doc/design/SPEC.md` §2.3, `doc/design/SPEC.md` §7.1 | the pinned checker revision stated in §8.1 |

## 10. Open items

- No formatter configuration is checked in for C++: there is no clang-format file at the repository root or
  in the app module. The C++ style is therefore inherited from the surrounding third-party sources by
  convention only, which is `unverified` mechanically. Adding a configuration would make the rule
  enforceable.
- The Go test invocation used for verification is not recorded in a single script, so the exact command set
  behind the unit tests is `unverified` from the repository alone.
- The conventions in §3 are derived from the call path; a future refactor that moves the ready-state boundary
  into another file must update the citations here, because the gate pattern is only meaningful together with
  the citations that locate it.
- This document cites current HEAD line numbers. Per the standard they are pointers: a change that moves code
  updates this document rather than the code.
