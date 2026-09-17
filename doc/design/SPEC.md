# SPEC — Documentation Standard (frozen)

> Status: **frozen** v1.5.3 (owner: `architect`). Task id: t1 (attempt 1), 2026-09-17. Captain v4 (four tags) is FINAL: no further version changes.
> Scope: every file under `doc/design/**` and the repository root `README.md`.
> This document is normative. Writers must not silently deviate: if a rule is wrong, report it to the
> captain and let the owner (architect) amend this file.
> Change history: see the changelog at the end of this file; the current revision is listed there with its
> date and entries.

---

## 1. Purpose and scope

This repository had 15 legacy documents under `doc/` describing an intended design. The shipped
implementation diverged from parts of them (see doc/design/09-verification-and-limitations.md — planned, not yet written at authoring time — for the
registered errata D-1..D-6). The replacement documentation set has two hard properties:

1. **Faithful** — every design statement is traceable to code (`path/file.ext:LINE`) or to a verification
   artifact (`reports/*.md`, a commit hash, or a build/publish log).
2. **Machine-checkable** — the claims above are extracted and re-validated by `scripts/doc-verify.sh`;
   a mismatch is a build-breaking failure, not a style nit.

Audience: a new engineer who has the repository and the reports but not the chat history.

Non-goals: this SPEC does not document the product (that is `01`..`11`); it documents **how those documents
must be written and checked**.

## 2. Directory conventions (canonical vs legacy vs compatibility)

| Path | Role | May be written by |
|---|---|---|
| `doc/design/**` | **Canonical** current documentation (this set) | the assigned writer of each file |
| `docs` | **Compatibility symlink** → `doc/design` (relative). Exists only because several task contracts spell paths as `docs/<file>.md` | nobody (link only) |
| `doc/*.md` | **Legacy stub**, one line each, kept at the original path | nobody |
| `doc/adr/*.md` | **Legacy ADR stubs**, one line each (ADR-001 … ADR-007) | nobody |
| `doc/README.md`, `doc/adr/README.md` | Legacy vs current explanation and full archive inventory | architect |
| `doc/archive/**` | Legacy documents, moved verbatim (`doc/*.md` gained one header line; `doc/archive/adr/*.md` are byte-identical) | nobody (history) |
| `doc/design/_generated/**` | Machine-generated tables (`GENERATED — do not edit`) | `scripts/gen-doc-tables.sh` only |
| `reports/**` | Engineering reports; **append-only history**, never a doc target | report owner |

Rules:

* **R1** — `docs/` is a symlink to `doc/design`. It must not become a real directory and must not receive
  content that is not part of the canonical set.
* **R2** — Body text and all cross-document links use the canonical path form `doc/design/...`. Only a task
  contract's declared `changedPaths` may use the `docs/...` spelling, and it resolves to the same file.
* **R3** — A legacy source file that was moved to `doc/archive/` leaves behind exactly one stub line at its
  original path: it names `doc/archive/<file>` and the new document that supersedes it. This applies to every
  file found by `find doc -type f` before the move, including nested directories: 15 files under `doc/*.md`
  **and** the 7 ADRs under `doc/adr/*.md` (22 stubs in total; the archive mirrors the relative structure as
  `doc/archive/**` and `doc/archive/adr/**`). Reason: `reports/**` cites historical paths, e.g.
  `doc/14-interface-contract.md` 74 times; those references must not dangle. Verified by `doc-verify.sh` V5,
  and the complete before/after inventory is listed in `doc/README.md` §2.
* **R4** — Archived files are immutable. The 15 `doc/*.md` copies carry exactly one added header line
  (`> **Archived 2026-09-17** — superseded by …`); the 7 `doc/adr/*.md` copies are byte-identical to
  `git HEAD:doc/adr/<file>`. Archives are cited as evidence of *history*, never as current guidance.
* **R5** — Generated files are never hand-edited; the regeneration command is in their header.

### 2.1 JNI contract authority chain (frozen)

Two facts fix the shape of this contract, both measured by doc-tooling (t2 recon) and re-checked here:

* The native binding is **`JNI_OnLoad` + `RegisterNatives`**, not name-mangled exports:
  `app/src/main/cpp/jni/jni_bridge.h:9` states the registration mode, and a byte scan of the built
  `libwebrtcdemo_native.so` finds **0** `Java_*` symbols and 4 `JNI_OnLoad`-related hits. **There are no
  `Java_*` exported symbols to discover.**
* There is **no** project-generated `*_jni.h` in the repository, and that is expected: such headers are CMake
  build products (the only `*_jni.h` files on disk belong to vendored libwebrtc under `third_party/`).

The JNI contract therefore has **three in-repo authorities that must be cross-checked**, plus one optional
build product and one descriptive source:

| # | Authority | Path | Role |
|---|---|---|---|
| 1 | Kotlin declarations | `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/**` | the `external fun` names, signatures and the callback interfaces the native side calls back into |
| 2 | `JNINativeMethod` tables | `app/src/main/cpp/jni/nat_detector_jni.cpp:49`, `app/src/main/cpp/jni/native_log_jni.cpp:71`, `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` | the 15 registered entries (2 + 4 + 9) that map each Java method to its C entry point |
| 3 | C++ prototypes and implementations | `app/src/main/cpp/jni/*.h`, `app/src/main/cpp/jni/*.cpp` | the C function signatures the tables point at |
| 4 | Exported symbols of the built library | `libwebrtcdemo_native.so` **build product** (CMake target `webrtcdemo_native`, `app/src/main/cpp/CMakeLists.txt:32`) | **optional** extra evidence; see J2 |
| 5 | Vendored libwebrtc binding classes | `org.jni_zero.GEN_JNI`, `J.N` inside `ARTIFACT: third_party/libwebrtc/java/libwebrtc-java.jar` (ignored by `.gitignore:12`) | **descriptive only** — the naming evidence is in-repo (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:236-243`, `app/src/test/kotlin/com/example/webrtcdemo/webrtc/JniBindingClasspathTest.kt:24-25`); the classes themselves are build artefacts, never machine-generated by this project |

Binding rules:

* **J1** — `doc/design/_generated/jni-contract.md` is generated from authorities 1, 2 and 3: it lists every
  Kotlin `external fun`, the matching `JNINativeMethod` entry (name, signature, function pointer) and the C
  prototype, and reports each entry as `matched` or `divergent`. An entry missing on any of the three sides is
  a finding.
* **J2** — The exported-symbol check (authority 4) is **optional**: it needs `binutils` and the `.so` present
  in the tree. When the build product is absent — the normal case, since no `.so` is in version control (see
  §2.4) — the tool records `UNVERIFIED (build product absent)` and **must not fail**.
* **J3** — A missing `*_jni.h` is expected and must never be reported as a documentation defect. Documents must
  not cite a project `*_jni.h` path as if it existed.
* **J4** — Upstream `J.N` / `GEN_JNI` details are descriptive. **`J.N` must not be asserted as a source symbol**:
  it exists only inside the gitignored build artefact `ARTIFACT: third_party/libwebrtc/java/libwebrtc-java.jar`
  (`.gitignore:12`), so it is cited with the `ARTIFACT:` tag and the V2 symbol check records it
  `UNVERIFIED (vendored binary)` — **never a failure**. `GEN_JNI` has in-repo evidence and should be cited from
  it (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:236-243`,
  `app/src/test/kotlin/com/example/webrtcdemo/webrtc/JniBindingClasspathTest.kt:24-25`), plus the report evidence
  (`reports/99-t34-appendix.md` §J/N fingerprint, `reports/15-java-jar-rebuild.md` §synthesis). Never generate a
  table for either from this repository.
* **J5** — Because the binding is `RegisterNatives`, a document must not describe this project's native entry
  points as `Java_*` functions.

### 2.2 Generated artifact names (frozen)

`doc/design/_generated/` contains exactly these four files; every other document cross-references them by these
names, and `scripts/gen-doc-tables.sh` must produce them with a `GENERATED — do not edit` header, the
generation command, the source paths and their sha256:

| File | Content | Source of truth |
|---|---|---|
| `doc/design/_generated/signaling-messages.md` | signalling message types and fields, with a Go↔Kotlin divergence section | `signaling/protocol/message.go`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `doc/design/_generated/log-events.md` | log event keys with emitter file and line | `app/src/main/kotlin/com/example/webrtcdemo/log/**`, `app/src/main/cpp/log/*.h` |
| `doc/design/_generated/jni-contract.md` | JNI contract per §2.1 | authorities 1, 2 and 3 (see §2.1), optional 4 |
| `doc/design/_generated/host-commands.md` | command/flag inventory and its evidentiary status per §7 A12 | repository scripts, report line numbers, workspace-only scripts |

### 2.3 Path vocabulary (frozen, captain v4 — final)

Every path written in the documentation set belongs to exactly one of the five path classes of §7.1 (levels
**P1–P5**). The final rule set (captain v3/v4) makes **tagging mandatory**: a path that is neither
repository-relative **nor** an absolute path under the container workspace root carries one of the **four tags**
below, written in any of the four notations of §7.2. An **untagged path is hard-checked** — repository-relative
against the repository root (the mechanism which catches misspelled paths) or container-absolute
(`/data/dsh/home/workspace/**`) against the container root.

| Tag | Meaning | Examples | Judgement (level) |
|---|---|---|---|
| `HOST:` | host absolute path, not visible in the container | `HOST: /opt/dsh-workspaces`, `HOST: /opt/apk-http/publish_apk.sh`, `HOST: /etc/systemd/system/signaling.service` | never existence-checked → `UNVERIFIED (host path)` (**P3**) |
| `WORKSPACE:` | workspace-root file, written in **workspace-relative** form (the container root is `/data/dsh/home/workspace`) | `WORKSPACE: tmp/t42-captain-build.sh`, `WORKSPACE: tmp/n6/x/app.log`, `WORKSPACE: env.sh`, `WORKSPACE: env-container.sh`, `WORKSPACE: env-go.sh` | **must exist** relative to the workspace root → `workspace-only, outside git repo` (**P2**) |
| `ARTIFACT:` | build product, not in VCS | `ARTIFACT: app/build/**`, `ARTIFACT: app-debug.apk`, `ARTIFACT: libwebrtcdemo_native.so` | existence optional; missing → `UNVERIFIED (build product, not in VCS)` (**P5**) |
| `DEVICE:` | device-side path or command that exists only on the test phone | `DEVICE: /sdcard/Android/data/...`, `DEVICE: adb` | never existence-checked; recorded `UNVERIFIED (device path)` (**P3**) |
| *(none)* | **P1** repository-relative path, **or P4** an absolute path under the container workspace root (which needs no tag) | `signaling/protocol/message.go:1`, `deploy/signaling.service:28`, `/data/dsh/home/workspace/tmp/n6/x/app.log` | **hard check**: exists (and covers the cited line when one is given) — `missing repo path` (P1) / `missing container path` (P4) |

Binding rules (**T1–T6**; the captain's path-classification levels keep the separate names **P1–P5** of §7.1):

* **T1** — A path that is neither repository-relative nor an absolute path under the container workspace root
  **must** carry one of the four tags (any of the four notations of §7.2). A missing tag on a host path is a
  **failure** (`unmarked host path`, V11); an untagged repository-relative path is hard-checked (V9) and fails
  when it does not exist; an untagged `/data/dsh/home/workspace/**` path is hard-checked (V12, P4).
* **T2** — In the line-prefix notation the tag is written immediately before the path, inside the same backticks
  or code span, with one space: `` HOST: /opt/signaling ``. The uppercase tag form is the shorthand of
  `scope=`; the scope names are `host`, `workspace`, `artifact`, `device` (§7.2), and tag
  names are case-insensitive.
* **T3** — A tag is a claim about provenance, not a substitute for evidence: `HOST:` and `DEVICE:` statements
  must still be paired with a repository-readable citation (a report line or a source line) when they support a
  design statement.
* **T4** — A `HOST:` path must never be written as if the repository contained it; for example the publish
  script is `HOST: /opt/apk-http/publish_apk.sh` and **no `publish_apk.sh` exists in this repository** (see
  §2.5).
* **T5** — A tag must never be applied to a version-controlled file: `ARTIFACT:` is for untracked build outputs
  only (`.gitignore:29 *.apk`, `.gitignore:56 *.so`, `app/.gitignore:2 /build/`), and a `WORKSPACE:` path must
  not resolve inside the repository.
* **T6** — §2.3 (this subsection) is the normative definition site for the tag vocabulary and T1–T6; §7.1
  states how a path is classified and judged (P1–P5), and §7.2 defines the notation syntax.

Correct and incorrect examples:

```
# correct: repository path, no tag, hard-checked
bash scripts/build_app.sh        # host-only script; see reports/37-t76-build-publish.md §3

# correct: host path tagged + repository-readable evidence in the same statement
HOST: /opt/apk-http/publish_apk.sh   # see reports/37-t76-build-publish.md §7

# correct: workspace-root file, workspace-relative form
WORKSPACE: tmp/t47b-captain-build.sh    # workspace-only, outside git repo

# correct: build output, existence optional
ARTIFACT: app/build/intermediates/cxx/**/libwebrtcdemo_native.so

# WRONG: unmarked host path (fails V11, `unmarked host path`)
/opt/apk-http/publish_apk.sh        <!-- NEGATIVE EXAMPLE — must stay unmarked on purpose -->

# WRONG: ARTIFACT: pointing at a path that neither exists nor matches a product pattern
ARTIFACT: app/build/does-not-exist.so   <!-- NEGATIVE EXAMPLE — triggers WARN typo-suspect, A10 -->
```

The host workspace (`/opt/dsh-workspaces/**`) maps to the container workspace (`/data/dsh/home/workspace/**`);
the container path is the one a reader can actually open.

### 2.4 Build products are not repository facts

Some paths a reader will meet in reports or in a build tree are **build products, not version-controlled
sources**. Documents must never present them as repository facts; they are the P5 class of §7.1 and carry the
`ARTIFACT:` tag when cited as a path.

| Build product | Ignored by | Where it exists |
|---|---|---|
| `libwebrtcdemo_native.so` (own native layer) | `.gitignore:56` (`*.so`) | only under `app/build/intermediates/**` (C++ object, merged and stripped copies) |
| `libjingle_peerconnection_so.so`, `libc++_shared.so` (pinned inputs kept on disk for the build) | `.gitignore:56` (`*.so`) | `app/src/main/jniLibs/arm64-v8a/` on disk, untracked |
| anything under `app/build/**` | `app/.gitignore:2` (`/build/`) | local build output |

Rules:

* **B1** — A document may cite a source path for these artefacts (`app/src/main/cpp/CMakeLists.txt:32`,
  `app/src/main/jniLibs/arm64-v8a/`), or cite the built file with the `ARTIFACT:` tag. It must not write a
  `.so` path inside `app/build/**` as if it were a checked-in repository fact.
* **B2** — `scripts/doc-verify.sh` path-existence (rule V1) applies to **repository sources**. For an
  `ARTIFACT:` path the checker records `UNVERIFIED (build product, not in VCS)` and **must not fail**, whether
  or not the file currently exists on disk (a missing path that also fails the product-pattern match raises
  `WARN typo-suspect`, A10).
* **B3** — Hashes and sizes of build products may be quoted only as report evidence (`reports/**`), never as a
  repository-verifiable claim.

### 2.5 Host-only build and publish flow (frozen)

Two facts change how build and release documentation must be written:

* **The build script is host-only.** `scripts/build_app.sh` assigns the host workspace at
  `scripts/build_app.sh:33` (`WS=/opt/dsh-workspaces`) and sources it at `scripts/build_app.sh:62`
  (`. "$WS/env.sh"`); it therefore **cannot run inside the container**. Documents must tag it
  `HOST:` and present the container-equivalent Gradle steps alongside it (for example
  `./gradlew --no-daemon assembleDebug`, the recipe recorded at `reports/37-t76-build-publish.md:44`). That
  the script is not container-portable is also registered as a known limitation in
  doc/design/09-verification-and-limitations.md (planned, not yet written at authoring time) and as an improvement item (accept a `WS` environment
  override) — no code change in this phase.
* **There is no `publish_apk.sh` in this repository.** Publishing is a *host manual procedure plus report
  evidence*: `HOST: /opt/apk-http/publish_apk.sh` (`reports/37-t72-build-publish.md`,
  `reports/41-apk-http-ownership.md`), and `reports/10-app-build.md:1093` states explicitly that the publish
  step never belonged to the build script. Documents must not treat the script as an in-repo file, must not
  invent its presence, and must cite it only as tagged host evidence.

* **F1** — `scripts/build_app.sh` is cited as `HOST:`; its verification status inside the container is
  `UNVERIFIED (host script)`.
* **F2** — Any documented publish command (`--apk`, `--log`, `--allow-root`, `--rehearsal`) is host-only and
  follows §7 A12: it needs a `doc/design/_generated/host-commands.md` entry **and** a repository-readable report
  citation in the same statement.
* **F3** — A document that asserts an in-repo path for `publish_apk.sh` (or any other host-only file) is
  wrong by construction and fails review.

## 3. Terminology (frozen, bilingual)

Use the **English term** in English prose. The Chinese column is a lookup aid for the legacy documents in
`doc/archive/`; it must not appear in `doc/design/**` prose. This table supersedes
`doc/archive/04-glossary.md`.

| English term | Chinese (legacy) | Meaning / where it is defined in code |
|---|---|---|
| signaling | 信令 | The WebSocket control channel and the Go service behind it (`signaling/`) |
| signaling endpoint | 信令端点 | WebSocket path `/ws` (`signaling/server/server.go:25`) |
| room | 房间 | A 6-character room id holding at most 2 seats (`signaling/room/room.go:13`) |
| room expiry | 房间过期 | A room with nobody joined is destroyed after `-room-expiry` seconds (`signaling/config/config.go:26`) |
| seat | 席位 | A slot in a room holding a peer id, idleness flag and generation (`signaling/room/room.go:26`) |
| grace period | 宽限期 | Window after a WebSocket drop during which the room and seat are kept (`signaling/config/config.go:54`) |
| pending / offline seat | 挂起/离线席位 | Seat marked offline inside the grace period (`signaling/room/room.go:113`) |
| takeover | 接管 | A reconnecting peer reclaiming its seat with the same peer id (`signaling/room/room.go:66`) |
| session generation | 会话世代 | Monotonic per-`CallSession` id, logged as `session=sN` (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`) |
| host | 发起方 | The peer that created the room; sends the first offer |
| joiner | 加入方 | The peer that joined an existing room; answers the offer |
| rejoin | 重连 | Reconnecting to the signaling service and resuming the call after a drop |
| ICE restart | ICE 重启 | Re-gathering candidates on the existing PeerConnection (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`) |
| glare | 冲突（同时重协商） | Both sides renegotiating at once; prevented by giving only the host the offer duty |
| P2P | 点对点直连 | Media over a selected host/srflx candidate pair |
| relay | 中继 | Media through TURN |
| forced relay | 强制中继 | ICE policy `RELAY` (diagnostics toggle) (`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:69`) |
| loopback candidate | 回环候选 | Candidate for a `127/8` or `0/8` address; filtered on both sides |
| NAT type | NAT 类型 | RFC 5780 result (`Open`/`FullCone`/`RestrictedCone`/`PortRestrictedCone`/`Symmetric`/`Unknown`; enum `signaling/protocol/message.go:31-36`, validator `signaling/protocol/message.go:182`) |
| bitrate floor | 码率地板 | Per-resolution minimum from the official VP9 table (30 kbps per step) |
| quality scaling | 质量降级 | `ScalingSettings` that lowers resolution before dropping frames (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:140`)  |
| encoder fallback | 编码兜底 | Switching to the platform encoder when the custom VP9 encoder cannot keep up |
| frame dropper | 帧丢弃器 | libwebrtc `FrameDropper`; disabled here via field trial `WebRTC-FrameDropper/Disabled` (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrial.kt:12`; field-trial plumbing `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:236-243`)  |
| watchdog | 看门狗 | Timed ICE/connectivity checker (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1111`) |
| diagnostics export | 诊断导出 | Log zip built by `LogExporter` (`app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:139`) |
| pinned artifact | 固定件 | Toolchain artifact whose hash must not change across a build (jar/aar/so/libc++) |
| release anchor | 发布锚点 | APK sha256＋byte size recorded by the publish chain |
| build window | 构建窗口 | Wall-clock span of a build during which `app/src` and `signaling/` must not change |
| errata | 契约勘误 | Registered divergence between legacy docs and implementation (`reports/99-final-report.md` §15.5) |

## 4. Citation format (frozen)

**C1 — Code citation.** `` `path/file.ext:LINE` `` or `` `path/file.ext:LINE-LINE` ``. The path is
repository-relative with `/` separators, no leading `./`. Lines are 1-based. A cited file must exist and the
file must have at least `LINE` lines. Cite the *narrowest* range that supports the statement.

**C2 — No bare file paths for code.** Naming a code file without a line is allowed only for orientation
inside a table/list that has at least one `:LINE` citation for the same statement. Every implementation
claim needs at least one C1 citation.

**C3 — Symbols.** Class/function/constant references are written in backticks with the exact source spelling,
e.g. `` `DefaultRoomGrace` ``, `` `rejoinDelayMs()` ``, `` `WebRTC-FrameDropper/Disabled` ``. The token must be
greppable as a literal in the cited file (or in a file cited in the same subsection).

**C4 — Protocol literals.** Message `type` strings, error codes and field names are written exactly as the
code has them (`ROOM_NOT_FOUND`, `sdpMLineIndex`, `peerJoined`). The set of message types used in a protocol
document must equal the generated signalling table in `doc/design/_generated/`. Never invent a type.

**C5 — Log keys.** Log event names and field keys are quoted exactly (`encoder_fallback_decision`,
`room_not_found_action=keep_call`). The key must appear in the code that emits it. Values taken from a real
device log must be quoted as they were observed, with the report number that contains the log.

**C6 — Evidence.** Each requirement/claim carries evidence of one of: `reports/NN-*.md` (number + section),
a commit hash (7+ hex chars, must exist in `git log`), a build/publish log path under `reports/logs/`, or an
explicit `unverified` marker. A claim with no evidence is not allowed.

**C7 — Internal links.** Relative links between documents must resolve on disk, e.g.
`[05-protocols](05-protocols.md)`, `[SPEC](SPEC.md)`, or from the repo root
`[doc/design/05-protocols.md](doc/design/05-protocols.md)`. No dangling links. A reference to a **planned but
not yet written** document (one of the items listed in §5 or §6) may stay unresolved when the same line says so
in prose, e.g. `doc/design/05-protocols.md` (planned, not yet written at authoring time); the checker then
records it as informational rather than dangling. The three link examples in this paragraph are **SPEC
examples** (§7 A13 definition lines) and are exempt from V4/V1 like the other definition rows; every real
document must drop the "planned" marker for its inbound links once the target file exists.

**C8 — Line number drift.** Line numbers in a citation are a *pointer*, not a contract: if code is edited and
the pointer moves, the **document** must be updated (doc-verify fails on out-of-range lines and on missing
symbols). Never "fix" the code to satisfy a document.

**C9 — Cite this SPEC by section, never by line.** Because `SPEC.md` itself is amended frequently (six versions
on 2026-09-17 alone), any line-number anchor into it goes stale within minutes. References to this document use
the **section form** — `doc/design/SPEC.md` §2.3, §7.1, §9 — and never `SPEC.md:LINE`. Rule identifiers
(`T1–T6`, `P1–P5`, `V1–V13`, `A1–A13`, `J1–J5`, `B1–B3`, `F1–F3`, `R1–R11`, `C1–C9`, `E1–E7`) are stable and
preferred over any line pointer. This applies to messages, task outputs and every document in `doc/design/**`.

### 4.1 Machine-readable citation syntax

`scripts/doc-verify.sh` extracts citations with a regular expression, so the syntax must be written exactly.
The binding spellings are:

| Form | Example | Meaning |
|---|---|---|
| single line | `` `signaling/room/room.go:13` `` | line 13 of that file |
| inclusive range | `` `signaling/room/room.go:66-100` `` | lines 66..100, both endpoints validated |
| path plus line in prose | "the room grace default (`signaling/config/config.go:54`)" | the citation is the parenthesised token |
| evidence path | `file:` citations may also point at `reports/NN-*.md` when the cited content is a report | the report must exist |

Rules that follow from this:

* The token is the whole `` `path:LINE` `` string; do not split a path across lines and do not put spaces
  around the colon.
* A `file:`-style citation (for example `` `deploy/turnserver.conf:4` ``) is validated the same way as any
  other: the file must exist and must have at least `LINE` lines.
* Every document must contain at least one `file:`-style citation per implementation claim; a document with
  zero citations cannot pass the delivery gate (§9).
* Citations inside tables are written in backticks, not as markdown links.


## 5. Document template (required sections)

Every document in `doc/design/` starts with this block:

```
# <NN> — <Title>

> Status: <draft | verified | superseded> · Owner: <member> · Task: <task id>
> Evidence base: <reports/commits used>
> Doc standard: `doc/design/SPEC.md`
```

Then it must contain, in order:

| # | Section | Required content |
|---|---|---|
| 1 | Scope | What this document covers and what it deliberately does not |
| 2 | Body (topic-specific) | See the per-document outline below; every claim cited per §4 |
| 3 | Evidence index | Table: claim/ID → citation → verification artifact |
| 4 | Open items | Anything unverified or known to be stale, with the reason |

Per-document bodies:

* `01-requirements.md` — functional requirements (FR-*), non-functional requirements (NFR-*), each row with
  Statement / Implementation / Evidence / Status, then the errata summary.
* `02-architecture.md` — components, planes, ports, deployment topology, document cross-references.
* `03-app-architecture.md` — layers, thread model, lifecycle, recovery, config switches, log vocabulary entry.
* `04-signaling-service.md` — package layout, room/seat state machine, routing and error codes, health fields,
  configuration and unit, coturn relationship.
* `05-protocols.md` — field-level bidirectional message tables (referencing `_generated`), connection and
  reconnect policy, ICE/TURN/SDP behaviour, JNI contract and diagnostic event table.
* `06-flows.md` — Mermaid sequence/flow diagrams, one per flow, each with step list, citations and ≥2 real
  log lines.
* `07-build-and-deploy.md` — toolchain versions, build stages with gates, build invariants, release chain,
  host services, incident handling.
* `08-issues-and-solutions.md` — per module: symptom → log signature → root cause → fix → evidence → residual
  risk; plus a rejected/disproven section.
* `09-verification-and-limitations.md` — verification matrix, known limitations, unverified items + retest
  method, errata D-1..D-6.
* `10-code-map.md` — code map, report index, document map, symptom→code lookup.
* `11-coding-standards.md` — conventions with positive/negative examples cited from real code.

Citation hygiene in the body: every `file:LINE` citation is verified by opening the **current HEAD source**
(§7 A13). Line numbers copied from `reports/**` or from the legacy documents in `doc/archive/**` are treated
as stale by construction (`reports/35-room-grace.md` cites `signaling/server/ws_handler.go:516` for
`sendPeerLeft`, which is `signaling/server/ws_handler.go:512` at the current HEAD). A report must be cited as a
report by section, not as `file:LINE`.

## 6. English writing standard

* **E1** — All prose under `doc/design/**` and the root `README.md` is English. Quoted legacy text, log lines
  and code identifiers keep their original form.
* **E2** — Present tense, active voice, one statement per sentence. State facts, not intentions.
* **E3** — Numbers carry units (`90 s`, `63 s`, `30 kbps`, `33 472 645 B`). Durations inside code-quoted
  identifiers keep the source spelling (`90s`, `8_000`).
* **E4** — Status vocabulary is fixed: `implemented`, `partially implemented`, `known limitation`,
  `unverified`, `rejected`, `disproven`. Do not invent synonyms.
* **E5** — No marketing adjectives ("robust", "seamless"), no unquantified superlatives.
* **E6** — Prefer a table over a paragraph when the content has 3+ parallel items.
* **E7** — Mark uncertainty explicitly (`unverified`, `not measured`) instead of hedging in prose.

## 7. `scripts/doc-verify.sh` judgement rules

The checker is the delivery gate. Its rules, in the order it applies them:

| Rule | Check | Failure means |
|---|---|---|
| V1 | Every `` `path/file.ext:LINE[-LINE]` `` citation: file exists, is not a directory, has ≥ `LINE` lines, `LINE` ≤ last line | broken pointer to code |
| V2 | Every backticked symbol in a code-citation context is greppable as a literal in the cited file | symbol does not exist / was renamed |
| V3 | Every protocol message type mentioned in a protocol document exists in the generated table, and every generated type appears in the document | protocol set divergence |
| V4 | Every relative markdown link resolves on disk | dangling cross-reference |
| V5 | Every legacy stub under `doc/*.md` **and `doc/adr/*.md`** is one line and names a file that exists under `doc/archive/` (the archive mirrors the relative structure) | historical reference dangling |
| V6 | `docs` is a symlink to `doc/design`, and `doc/design/_generated/**` files carry a `GENERATED — do not edit` header when present | convention violated |
| V7 | Every Gradle task name and every build/publish flag used in a document matches a real literal in the file that owns it (see the ownership table below) | non-existent command or flag presented as usable |
| V8 | **Scope notation (A8, §7.2).** Tags are exactly `HOST:`/`WORKSPACE:`/`ARTIFACT:`/`DEVICE:` in four equivalent notations (line prefix, line-end comment, fence `scope=`, section comment; precedence line > fenced > section > default; case-insensitive); every path outside the repository and outside the container workspace root must be tagged, and an unknown tag is a writing violation | `unknown tag` / `untagged non-repository path` |
| V9 | **P1 REPO** — repository-relative path exists and covers the cited line | `missing repo path` |
| V10 | **P2 WORKSPACE** — a `WORKSPACE:` (workspace-relative) path must exist against the container root `/data/dsh/home/workspace`; recorded `workspace-only, outside git repo` | `missing workspace path` |
| V11 | **P3 HOST** — a `HOST:` path under `/opt`, `/etc`, `/var`, `/var/log`, `/home`, `/root`, `/usr`, `/srv`, `/tmp` is never existence-checked (recorded `UNVERIFIED (host path)`); an unmarked host path fails; `DEVICE:` paths are recorded `UNVERIFIED (device path)` and never fail | `unmarked host path` |
| V12 | **P4 CONTAINER** — an **untagged** absolute path under `/data/dsh/home/workspace/**` must exist against the real container root (P4 needs no tag) | `missing container path` |
| V13 | **P5 ARTIFACT** — an `ARTIFACT:` path (`app/build/**`, `**/*.apk`, `**/*.so`, `app/.cxx/**`) has optional existence; when absent it is recorded `UNVERIFIED (build product, not in VCS)` and **never fails**, but a missing path that also does not match a product pattern raises `WARN typo-suspect` (A10) | *(none — warning only)* |
| V14 | **Pending-document downgrade (A14).** A reference to a sibling design document that this SPEC plans but that is not yet on disk (`doc/design/03`–`11` and the like) is downgraded to `WARN pending document` and **never fails**; any other non-existent repository path still fails | `WARN pending document` (warning only) |

Ambiguity handling (binding for both the checker and the writers):

* **A1** — Only citations whose target matches `*.{go,kt,kts,cpp,h,cc,hpp,sh,py,service,conf,mjs,md,json,properties,txt,xml,gradle}` are checked by V1. A `path:LINE`-shaped token pointing at a path outside the
  repository is reported as a **warning**, not a failure. A1 is a **classification rule for the citation
  checker**; it does not exempt the writer from the scope duty in §7.1. On the same reference the two faces
  therefore read: **with the correct scope marker → not a failure** (A1 grades the off-repository citation
  itself as a warning only because there is no file to open), **without a scope marker → failure**
  (`unmarked host path`, V11). There is no contradiction: A1 never fails anything, and the failure is caused
  by the missing marker, not by the citation being off-repository — see the coexistence table in §7.1.
* **A2** — Line ranges are validated on both endpoints.
* **A3** — A citation inside a fenced code block is checked like any other (code samples are evidence too).
* **A4** — Docs that do not exist yet are skipped, except when named explicitly via `--only`.
* **A5** — `--only <files>` restricts the run to the named files; the delivery gate for a writer task is
  `bash scripts/doc-verify.sh --only <its own files>`.
* **A6** — Exit code is `0` only when there are no failures; warnings do not fail the run.
* **A7** — Host absolute paths (`/opt/**`, `/etc/**`, `/var/log/**`) are not visible from the container. Their
  **existence is never a failure** (the checker records `UNVERIFIED (host-only path)`), **but** if the line,
  fence or section containing such a reference carries neither the `HOST` nor the `DEVICE` scope, the run
  **fails** with `unmarked host path` (V11). This is the same judgement as §2.3 T1 and §7.1 P3, and it is the
  only marker-driven failure in the rule set. Repository-relative paths (for example
  `deploy/signaling.service:28`) **are** checked by V1 and must exist.
* **A8** — Scope notation. The tags are exactly `HOST:`, `WORKSPACE:`, `ARTIFACT:`, `DEVICE:`
  (plus the untagged default, which is the repository-relative P1 class), and there are four equivalent
  notations with precedence **line > fenced section > section > default**:
  (1) line prefix (`- HOST: <content>`, `<TAG>: <path>`); (2) **line-end comment**
  (`bash scripts/build_app.sh   # HOST (WS=/opt/dsh-workspaces, scripts/build_app.sh:33)`, the
  captain-recommended form); (3) fenced info string (```` ```bash scope=host ````); (4) section comment
  (`<!-- scope: host -->`, effective until the next `##`/`###` heading). Tag names are case-insensitive, and
  tagging is mandatory for every non-repository path (§2.3 T1). §7.2 is the normative syntax reference.
* **A9** — Path classification P1–P5, enforced by **V9–V13** with the levels of §7.1 and the notation of §7.2:
  **P1** repository-relative paths (no marker) must exist and cover the cited line; **P2** paths starting with
  `../` or `tmp/`, and files named `env.sh` / `env-container.sh` / `env-go.sh`, must be written with the
  `WORKSPACE:` tag in workspace-relative form and must exist against the workspace root (labelled
  `workspace-only, outside git repo`); **P3** absolute paths under `/opt`, `/etc`, `/var`, `/var/log`,
  `/home`, `/root`, `/usr`, `/srv`, `/tmp` are never existence-checked but must carry `HOST:` (or `DEVICE:`),
  otherwise `unmarked host path` fails; **P4** untagged absolute paths under
  `/data/dsh/home/workspace/**` are hard-checked against the real container root; **P5** `ARTIFACT:` build
  outputs (`app/build/**`, `**/*.apk`, `**/*.so`, `app/.cxx/**`) have optional existence and a missing file is
  never a failure. Warnings never become successes; the only marker-driven failure is `unmarked host path`.
* **A10** — Tag totality and `WARN typo-suspect`. Every path outside the repository and the container root carries one of the four tags (T1),
  and an untagged path is hard-checked as repository-relative, which is what catches misspellings. A path
  marked `ARTIFACT:` whose file **does not exist and does not match a build-product pattern** is reported as
  `WARN typo-suspect (artifact path absent and not a product pattern)`; the warning **does not change the exit
  code** (A6). A missing `ARTIFACT:` path that *does* match a product pattern (`app/build/**`, `**/*.apk`,
  `**/*.so`, `app/.cxx/**`) is simply recorded `UNVERIFIED (build product, not in VCS)` with no warning —
  a clean checkout is expected to lack build outputs. (`git check-ignore` returns IGNORED for non-existent
  paths too, which is why the product-pattern match, not existence, decides the warning.)
* **A11** — V7 resolves each command through the file that owns it. Ownership is frozen as follows:

  | Command / flag | Owner | Checkable from the container |
  |---|---|---|
  | `--check-only`, `--skip-go` | `scripts/build_app.sh` | yes (`scripts/build_app.sh:45`) |
  | Gradle tasks `:app:compileDebugKotlin`, `assembleDebug` | `scripts/build_app.sh` | yes (`scripts/build_app.sh:327`, `scripts/build_app.sh:363`) |
  | Gradle wrapper flags `--no-daemon`, `--no-build-cache`, `--rerun-tasks`, `:app:testDebugUnitTest`, `:app:clean`, `assembleDebug` | Gradle itself; the project recipe is recorded in `reports/37-t76-build-publish.md` §3 | partly (a document may cite the report recipe; the literal is then checked in the report, not in a script) |
  | `--allow-root`, `--rehearsal`, `--apk`, `--log` | host-side `/opt/apk-http/publish_apk.sh` | **no** — report `unverified (host script)`; a documented instance is `reports/37-t76-build-publish.md` §7 |

  A document must not present a host-only flag as if it had been executed inside the repository; it must
  attribute it to the host script and cite the report that exercised it.
* **A12** — Command and flag determinacy is split into two classes, and `doc/design/_generated/host-commands.md`
  is the authoritative inventory for the second:

  | Class | Examples | Evidence required by a document |
  |---|---|---|
  | In-repo provable | `--check-only`, `--skip-go` (`scripts/build_app.sh:45`); `:app:compileDebugKotlin` (`scripts/build_app.sh:327`); `assembleDebug` (`scripts/build_app.sh:363`) | the in-repo literal; V7 hard-checks it |
  | Host- or workspace-only | `--rerun-tasks :app:testDebugUnitTest`; `publish_apk.sh --rehearsal` / `--allow-root`; `/etc/systemd/system/*.service` | (a) an entry in `host-commands.md`, whose source is a repository script, a report line number, or a workspace-only script labelled `workspace-only, outside git repo`, **and** (b) a repository-readable citation (a report line number) in the same statement |

  A document that cites a host- or workspace-only command **without** the repository-readable evidence in the
  same statement **fails** V7. Readable evidence for the four-stage build recipe is
  `reports/37-t76-build-publish.md` §3 (stage 4 literal at `reports/37-t76-build-publish.md:44`), mirrored in
  the workspace-only script `/data/dsh/home/workspace/tmp/t47b-captain-build.sh` (`workspace-only, outside git
  repo`); for the publish flags it is `reports/37-t76-build-publish.md` §7. Dependency D-6 still stands: the
  host-side publish files themselves are not verifiable from the container.
* **A13** — Line numbers are validated against the **current HEAD source**, never against a report: a
  `file:LINE` citation in a document must be checked by opening the working-tree file. Historical line numbers
  copied from `reports/**` are not acceptable evidence and must not be reused — `reports/35-room-grace.md`
  cites `signaling/server/ws_handler.go:516` for `sendPeerLeft`, while at the current HEAD that function starts
  at `signaling/server/ws_handler.go:512` and line 516 is a statement inside it. If a report line number
  appears in a document, it must be quoted as a report citation (`reports/35-room-grace.md` §…) and not in
  `file:LINE` form.
* **A14** — `WARN pending document`. A reference to a sibling design document that §5/§6 plan but that is not yet
  on disk (`doc/design/03`–`11`) is reported as a warning-level `pending document` entry and **does not change
  the exit code** (A6); once the file lands, the same reference becomes a normal V1 hard check and must resolve.
  Any other non-existent repository path remains a hard failure. Writers may additionally use the C7 prose marker
  (`planned, not yet written at authoring time`) to make the status explicit to readers.

### 7.1 Writing rule for off-repository references (owner: `architect`)

**Definition in §2.3, not redefined here.** This subsection states how the scope rule of §2.3 is **judged**;
the tag set, the mandatory-tagging duty (T1) and the P1 hard check for untagged repository-relative paths are
defined in §2.3, and the notation syntax is §7.2. §7.1 may not be redefined by an implementation note: a note
added under the §7 extension point contributes only its mechanism (tag regex, message text, exit-code mapping)
and must cite §2.3, §7.1 and §7.2.

**The two judgement faces.** A1 (citation classification) and A6 (exit code) describe *how a citation is
graded*; A10 and the P-level rows describe the *writer's tagging duty*. Definition of both lives in §2.3 plus
the table below — this subsection only records the split: an off-repository token is a `warning` on the
citation face (A1) whether or not it is tagged, while an **untagged** non-repository path is a **failure** on
the duty face (V11 `unmarked host path`), and an untagged repository-relative path is hard-checked (V9 P1).

**Scope tags.** Exactly four: `HOST:`, `WORKSPACE:`, `ARTIFACT:`, `DEVICE:` (plus the untagged
default, which is P1 repository-relative). Tagging is mandatory for every non-repository path (§2.3 T1).

**Canonical writing example** (the captain-recommended line-end comment form; §7.2):

```
bash scripts/build_app.sh        # HOST (WS=/opt/dsh-workspaces, scripts/build_app.sh:33)
```

**Path classification P1–P5 and its judgement** (one row per level; V8 and V9–V13 implement these):

| Level | Scope / marker | Paths | Judgement | Failure message |
|---|---|---|---|---|
| **P1 REPO** | scope `REPO` (default), or no marker | repository-relative paths **that are not gitignored** (`signaling/protocol/message.go:1`, `deploy/signaling.service:28`) | **hard check**: exists and covers the cited line | `missing repo path` |
| **P2 WORKSPACE** | `WORKSPACE:` (workspace-relative form) | `WORKSPACE: tmp/t47b-captain-build.sh`, `WORKSPACE: env.sh` | **must exist** against the real container root; recorded `workspace-only, outside git repo` | `missing workspace path` |
| **P3 HOST** | `HOST:` (or `DEVICE:`) | absolute paths `/opt`, `/etc`, `/var`, `/var/log`, `/home`, `/root`, `/usr`, `/srv`, `/tmp`; device-side `/sdcard/**`, `adb` | **never existence-checked**; recorded `UNVERIFIED (host path)` / `UNVERIFIED (device path)`; an unmarked host path **fails** | `unmarked host path` |
| **P4 CONTAINER** | *(untagged)* | absolute paths under `/data/dsh/home/workspace/**` | **must exist** against the real container root | `missing container path` |
| **P5 ARTIFACT** | `ARTIFACT:` | untracked build outputs: `app/build/**`, `**/*.apk`, `**/*.so`, `app/.cxx/**` | existence **optional**: present → verified; missing → `UNVERIFIED (build product, not in VCS)` and **never a failure**; a missing path that also does not match a product pattern raises `WARN typo-suspect` (A10) | *(none — the warning does not fail)* |

Rules that follow:

* The captain's v3/v4 additions map onto the table above as: `DEVICE:` → **P3** (V11, never existence-checked,
  recorded `UNVERIFIED (device path)`); an **untagged** absolute path under `/data/dsh/home/workspace/**` → **P4**
  (V12, hard check against the container root); `ARTIFACT:` whose path is absent **and** does not match a
  product pattern → **`WARN typo-suspect`** (A10, surfaced by V13), warning only.

* A path belongs to exactly one level. Tag precedence is **line > fenced > section > default**, and every
  non-repository path must be tagged (§2.3 T1): `HOST:`/`DEVICE:` (host/device), `WORKSPACE:`
  (workspace root, must exist), `ARTIFACT:` (build outputs, existence optional). An untagged path is
  repository-relative and hard-checked — P1 when it is not ignored, P5 when `git check-ignore` reports it as
  ignored/untracked *and* it matches a product pattern (`app/.gitignore:2` for `app/build/**`, `.gitignore:29`
  for `*.apk`, `.gitignore:56` for `*.so`).
* P4 takes precedence over P3 by prefix: an untagged path under the container workspace root (for example
  `/data/dsh/home/workspace/tmp/n6/x/app.log`) is hard-checked, while a bare `/tmp/...` outside it is P3.
* A deliberately unmarked negative example in this SPEC, marked in its own line as one, is exempt from the
  P3 failure rule.
* Because P5 has optional existence, a missing `app/build/**` tree or a missing `*.so` in a clean checkout can
  never fail a document; a missing `ARTIFACT:` path that does not match a product pattern raises the
  warning-only `typo-suspect` diagnostic (A10). A tag must never be applied to a version-controlled file (T5).

**Relationship to A1 — the same reference judged on two axes.** A1 is the **citation checker's classification
rule**: a `path:LINE`-shaped token whose target lies outside the repository is graded as a *warning* for the
citation check, because there is no file to open. §7.1 is a **writing rule** enforced on a different judgement
face (V8/V9–V13): the writer's duty to mark the HOST scope. The two coexist and must not be read as
contradicting each other:

| Axis | Judge | A marked P3 reference | An unmarked P3 reference |
|---|---|---|---|
| A1 — citation classification | citation checker | N/A for P3 (nothing to open); P1/P2/P4/P5 are openable | `warning` (no openable target) |
| V8 / §7.1 — writing discipline | writing checker | `pass` | **`fail`** (`unmarked host path`) |

In short: a missing marker is not "an off-repository citation being hard-checked"; it is a **writing
violation**. A document can therefore be warning-free yet fail V8/V11, and the fix is always the same — add
the `HOST` scope.

### 7.2 Scope notation syntax (captain-approved, frozen)

Four equivalent notations declare the scope of a line or block. Precedence: **line > fenced section > section
> default `REPO`**. Tag names are case-insensitive.

| Notation | Form | Example | Applies to |
|---|---|---|---|
| Line prefix | `- <SCOPE>: <content>` on a list item, or `<SCOPE>: ` before the path inside a code span | `- HOST: bash /opt/dsh-workspaces/code/webrtc-demo/scripts/build_app.sh`, `HOST: /opt/apk-http/publish_apk.sh` | that line only |
| **Line-end comment** (the captain-recommended form) | `<command or path>   # <SCOPE> (<evidence anchor>)` | `bash scripts/build_app.sh   # HOST (WS=/opt/dsh-workspaces, scripts/build_app.sh:33)` | that line only |
| Fenced info string | ```` ```bash scope=<scope> ```` | ```` ```bash scope=host ```` | every line in the fence |
| Section comment | `<!-- scope: <scope> -->` | `<!-- scope: host -->` | every line until the next `##`/`###` heading |

The line-end comment form is preferred for command examples because it keeps the evidence anchor next to the
command; the parenthesised anchor is written as a repository path with `:LINE` when one exists.

Scope names in the notations are the lowercase tokens `host`, `container`, `device`, `repo`; the
line-prefix tag form is uppercase `HOST:`, `WORKSPACE:`, `ARTIFACT:`, `DEVICE:`. The two
surfaces are interchangeable and map onto the same path classification P1–P5:

| Notation / token | Classification |
|---|---|
| `scope=repo` or no tag | P1 (repository-relative, hard-checked) |
| `scope=host` or `HOST:` | P3 |
| `scope=device` or `DEVICE:` | P3 (device-side path/command: never existence-checked) |
| `scope=workspace` or `WORKSPACE:` (workspace-relative form) | P2 (must exist against the container root) |
| *(no tag)* for `/data/dsh/home/workspace/**` | P4 (container-absolute, hard-checked; needs no tag) |
| `scope=artifact` or `ARTIFACT:` | P5 (build products; existence optional) |

**The notations are pure mechanical aliases.** `scope=host|workspace|artifact|device` and the uppercase tags
`HOST:`/`WORKSPACE:`/`ARTIFACT:`/`DEVICE:` are the same judgement under two spellings; declaring one does not
create or modify a rule, and a document may mix them freely. Only the tag decides the class; there is no second
judgement surface.

All four tags are **valid and mandatory** for their class (captain v3/v4, final): a path that is neither
repository-relative nor an absolute path under the container workspace root must carry its tag; an untagged
path is hard-checked (repository-relative against the repo root, container-absolute against the container
root, §2.3 T1). The tag set is exactly `HOST:`, `WORKSPACE:`, `ARTIFACT:`, `DEVICE:`.

**`typo-suspect` warning (A10).** An `ARTIFACT:`-tagged path that neither exists nor matches a build-product
pattern is reported as `WARN typo-suspect (artifact path absent and not a product pattern)` to catch mistyped
artifact paths. The warning **does not change the exit code** (A6). A missing `ARTIFACT:` path that does match a
product pattern (`app/build/**`, `**/*.apk`, `**/*.so`, `app/.cxx/**`) is merely recorded
`UNVERIFIED (build product, not in VCS)` with no warning.

Example of a fenced and a section-scoped block:

````
```bash scope=host
bash /opt/dsh-workspaces/code/webrtc-demo/scripts/build_app.sh
```

<!-- scope: repo -->
- deploy/signaling.service:28 pins -room-grace 90s
````

### 7.3 Command-name classification (captain-approved, frozen)

* Names provable from the repository (`scripts/**`, `deploy/**`) are **hard-checked** (A11/V7).
* Host-side names are authoritative via the generated inventory
  `doc/design/_generated/host-commands.md`; a document that cites a host command must carry **in-repository
  evidence**, in either equivalent form: an inline report citation (`reports/<file>:<line>`) or a reference to
  the `host-commands.md` entry that itself carries its origin.
* A `workspace-only` script (`tmp/*.sh`) may never be the sole evidence for a host command.

### 7.4 Facts about this repository's layout (recorded for the checker)

* The repository contains **no `*.cc` files**; `app/src/main/cpp/jni/` holds 7 files
  (`callback_bridge.cpp`, `callback_bridge.h`, `jni_bridge.cpp`, `jni_bridge.h`, `nat_detector_jni.cpp`,
  `native_log_jni.cpp`, `vp9_encoder_jni.cpp`).
* `scripts/build_app.sh` is host-only: it hard-codes `WS=/opt/dsh-workspaces` at `scripts/build_app.sh:33`
  and sources `$WS/env.sh` at `scripts/build_app.sh:62`.
* There is no `publish_apk.sh` in the repository; it is `HOST: /opt/apk-http/publish_apk.sh` and the parameter
  evidence lives in report line numbers (`reports/37-t72-build-publish.md`, `reports/41-apk-http-ownership.md`,
  `reports/10-app-build.md:1093`).

Ownership note: the executable implementation of these rules lives in `scripts/doc-verify.sh`
(task t2, owner `doc-tooling`). If the implementation and this table disagree, the table is authoritative
for *intent*, and the divergence must be reported to `architect` before it is resolved — SPEC changes are
owned by `architect` (see §8).

### 7.5 Implementation notes for the checker (owner: `doc-tooling`, t2 extension point)

Mechanism only: the rules are normative in §2.3 (T1–T6), §7.1 (P1–P5) and §7.2 (notation). Added under R7.

* **Scope detection.** `scripts/doc-verify.sh` tests each line's uppercase form against
  `(^|[^A-Z])(HOST|WORKSPACE|ARTIFACT|DEVICE)[ \t]*[:(]`, so all four notations of §7.2 — line
  prefix, line-end comment, fenced `scope=...` info string, section comment — are recognised with precedence
  line > fence > section > default (repository-relative).
* **Tag totality.** Every path that is neither repository-relative nor an absolute path under the container
  workspace root must carry one of the four tags (§2.3 T1); an untagged path is hard-checked — repository-relative
  against the repo root (the misspelling trap), container-absolute against the container root. Tag names are
  case-insensitive; the `scope=` fence values are `host`, `workspace`, `artifact`, `device`, `repo`.
* **Path classification.** The tag decides the class; for untagged paths `git check-ignore -q` decides the
  P1/P5 split (ignored/untracked **and** matching a product pattern ⇒ P5) and an absolute path under
  `/data/dsh/home/workspace/**` is P4. `WORKSPACE:` is a hard check against the workspace root, `HOST:`/`DEVICE:`
  are never existence-checked, and `ARTIFACT:` has optional existence.
* **P2/P4 existence.** P2/P4 resolve against the workspace root (`<repo>/../..`, i.e. the container root
  `/data/dsh/home/workspace`) and are hard checks, so their failure messages are `missing workspace path` and
  `missing container path`. P5 never fails (absent → `UNVERIFIED (build product, not in VCS)`); a missing
  `ARTIFACT:` path that does not match a product pattern raises `WARN typo-suspect` (A10).
* **Messages and exit codes.** Failures print `file:line → problem → suggested fix`. Failure texts:
  `missing repo path`, `missing workspace path`, `unmarked host path`, `missing container path`,
  `unknown tag`. P3 and P5 emit `NOTE … UNVERIFIED (…)` diagnostics. The exit code is `0` only when no failure
  was recorded (A6); `NOTE`/`WARN` never change it.
* **Reading the two axes.** A P3 reference is graded twice: the citation face (A1, a `WARN` because there is
  nothing to open) and the writing face (V11, a failure only when unmarked) — §7.1 settles their coexistence.
* **V2 scope.** A symbol is accepted when it is greppable in **any** file cited in its own subsection (the
  SPEC V2 wording), not only on its own line; the file set is deduplicated. Documentation vocabulary (E4
  status words, scope tokens, `LINE`/`NAME` placeholders, `_generated`) is not treated as a symbol.
* **Performance.** Subsection citation sets and file line counts are cached; the run is O(lines) per document.

## 8. Change control

* **R6** — Edit only the files in your task's `inScope`. Do not "helpfully" fix another writer's file; report
  it in your task output instead.
* **R7** — `SPEC.md` changes require `architect` approval. A writer may add a subsection *inside the section
  reserved for its task* (currently §7, which is `doc-tooling`'s extension point) and must state so in its
  output.
* **R8** — Directory or naming changes (new document, renamed document, new symlink) require `architect`
  approval and a SPEC update in the same change.
* **R9** — Legacy files move only as a whole-file archive plus a one-line stub (R3/R4).
* **R10** — No source code (`app/**`, `signaling/**`, `third_party/**`, `scripts/build_*`) is modified by a
  documentation task. No Gradle run. No `git commit`.
* **R11** — The v1.4.0 registration of the scope/path-classification rules (§7 A7–A10, §7.1, §7.2) was made by
  `architect` on a captain ruling (task t11, captain message ids `4c02180c` / `6880a985`), because §7 changes
  require the SPEC owner and `scripts/doc-verify.sh` (task t2) has no write access to `SPEC.md`. **Self-reference
  exemption:** a documentation line is exempt from the marker-discipline checks (V8 and V11) when it is a
  **definition row** of the §2.3 vocabulary table or of the §7.1–§7.2 rule tables, or when the line is
  explicitly marked `NEGATIVE EXAMPLE`, or when it cites a **planned but not yet written** document with the
  prose marker of C7, or when the line discusses a symbol whose only definition is a **vendored binary** build
  input (J4). The exemption exists so the standard's own vocabulary, counter-examples and forward references do
  not register as violations of the standard; it must be removed from a document's inbound references once the
  target file exists.

## 9. Delivery gate

A documentation task is complete only when all of the following hold:

1. `bash scripts/doc-verify.sh --only <files in scope>` exits `0`.
2. Every implementation claim in the changed files carries a C1..C6 citation.
3. The changed files are English (E1) and use the §3 terminology.
4. The task output lists the changed paths and the checker's exit code.

The independent verification task (t7, owner `verifier`) re-runs the full `doc-verify.sh` plus manual
sampling; its verdict is the release criterion for the documentation set.

---

## Changelog

| Version | Date | Entries |
|---|---|---|
| 1.0.0 | 2026-09-17 | Initial freeze (t1): §2 directory conventions and stub rules R1–R5, §3 bilingual terminology, §4 citation format C1–C8 and §4.1 machine-readable syntax, §5 template, §6 English rules E1–E7, §7 checker rules V1–V7 and A1–A9, §8 change control, §9 delivery gate |
| 1.1.0 | 2026-09-17 | Captain decision (a): §2.1 JNI contract authority chain (Kotlin `external fun` ↔ `JNINativeMethod` ↔ C prototypes; exported symbols optional, `UNVERIFIED` when absent); §2.2 generated artefact names (`signaling-messages.md`, `log-events.md`, `jni-contract.md`, `host-commands.md`); §2.3 three-level path tag vocabulary (the normative definition site, including the container workspace root clarification); §2.4 build products are not repository facts (B1–B3); §2.5 host-only build and publish flow (F1–F3: `scripts/build_app.sh` is `HOST:`, no `publish_apk.sh` in the repository); §5 citation-hygiene note; §7 A10 tag enforcement, A11 current-HEAD line numbers; **§7.1 writing rule for off-repository references**; V8 added to the §7 rule table |
| 1.2.0 | 2026-09-17 | Captain-approved integration of doc-tooling's frozen scope system: §7.1 carries the five path-classification levels **P1 REPO / P2 WORKSPACE / P3 HOST / P4 CONTAINER / P5 ARTIFACT** with judgement and failure messages, and the A1 two-axis coexistence table; **§7.2** defines the three equivalent scope notations (line prefix, fence `scope=`, section comment; precedence line > fenced > section > default); **§7.3** records the command-name classification; **§7.4** records the layout facts (no `*.cc`; `cpp/jni/` = 7 files; host-only `build_app.sh`; no `publish_apk.sh` in the repository); §7 rule table extended with **V9–V13** (one row per level); §2.3 tag rules renamed **T1–T6** to avoid collision with the P1–P5 names; A10 rewritten to the P1–P5 enforcement model |
| 1.3.0 | 2026-09-17 | Captain revision replacing the v1.2.0 marker vocabulary: the **only scope tokens are `HOST`, `CONTAINER`, `DEVICE`, `REPO`** (default `REPO`) in the four notations of §7.2 (line prefix, line-end comment, fence, section comment; precedence line > fenced > section > default); §2.3 rewritten to that vocabulary and now states that `WORKSPACE:`/`ARTIFACT:` are **retired markers** (T5 — writing them is a failure); §7.1 P2 extended to `tmp/`-prefixed paths and made shape-derived with **no marker**, P3 marker-driven only (`unmarked host path`), P4 shape-derived, and **P5 made auto-classified** via `git check-ignore` + `app/build/**` / `**/*.apk` / `jniLibs/**/*.so` / `app/.cxx/**` with **optional existence, `UNVERIFIED (build output, gitignored)`, never a failure**; §7 rule table V8–V13 updated accordingly (V8 now covers the retired markers, V13 informational); §7.2 mapping table rewritten to the four tokens; the **line-end comment form** `# <SCOPE> (<evidence>)` recognised as a notation (captain-recommended) alongside the line prefix, fence and section comment; A10 and A1 aligned so the A1 warning layer and the V11 `unmarked host path` failure layer cannot be read as contradictory; **no** "missing artifact"/typo-suspect warning exists for P5 (existence is optional and never fails). Content retained from 1.2.0: §7.3 command-name classification, §7.4 layout facts (no `*.cc`; `cpp/jni/` = 7 files; host-only `build_app.sh`; no `publish_apk.sh` in the repository) |
| 1.4.0 | 2026-09-17 | **Captain ruling registered (task t11)**: §7 A-rules renumbered and extended so the final vocabulary has规范效力 — **A7** host-path referencing (existence never fails, but a line/fence/section lacking HOST or DEVICE scope **FAILS** `unmarked host path`), **A8** scope notation (tokens `HOST`/`CONTAINER`/`DEVICE`/`REPO`, four equivalent notations — line prefix, line-end comment, fence `scope=`, section comment — precedence line > fenced > section > default, case-insensitive), **A9** path classification P1–P5, **A10** `WARN typo-suspect (gitignored path absent)` for P5 paths that are gitignored but absent (warning only, no exit-code change, per A6); A1 rewritten to the same口径 as A7 (a correctly marked off-repository citation is not a failure; only the missing marker fails); former A8/A9/A10/A11 became **A11/A12** (command ownership and determinacy) and **A13** (current-HEAD line numbers); V8 restated as scope-notation discipline; C7 gained the "planned, not yet written" prose marker for forward references; §8 gained **R11**, whose self-reference exemption covers definition rows, `NEGATIVE EXAMPLE` lines and C7 forward references so the standard does not violate itself |
| 1.5.0 | 2026-09-17 | **Captain v3 (FINAL) — tag-mandatory model.** Supersedes the v1.3.0 "auto-classified, marker-free" model: **five mandatory tags** — `HOST:` (host absolute `/opt|/etc|/var/log|/usr|…`; never existence-checked → `UNVERIFIED (host path)`), `WORKSPACE:` (workspace-root file written in **workspace-relative** form, e.g. `tmp/t42-captain-build.sh`; **must exist** against the container root `/data/dsh/home/workspace` → `workspace-only, outside git repo`), `ARTIFACT:` (build products `app/build/**`, `*.apk`, `*.so`; existence optional → `UNVERIFIED (build product, not in VCS)`), **new** `DEVICE:` (device-side paths/commands `/sdcard/...`, `adb`; never existence-checked), **new** `CONTAINER:` (absolute form of the workspace-root path, alias of `WORKSPACE:`); an **untagged path is repository-relative and hard-checked** (V9), which is the misspelling trap. §2.3 rewritten (T1–T6); §7.1 P2/P4/P5 rows and the precedence bullet rewritten as tag-driven; §7 rule table V8 (five tags) and V10–V13 updated; §7.2 mapping table extended to the five tags with tag names case-insensitive; **A10 redefined** — `ARTIFACT:`-tagged path that neither exists nor matches a build-product pattern ⇒ `WARN typo-suspect (artifact path absent and not a product pattern)`, warning only, exit code unchanged per A6; §2.4 B1/B2 aligned. Retained unchanged: four notations (line prefix, line-end comment, fence `scope=`, section comment) and their precedence, A11–A13 (command ownership/determinacy, current-HEAD line numbers), C7 forward-reference marker, R11 self-reference exemption |
| 1.5.1 | 2026-09-17 | **C9 added** (writer-ops proposal, adopted): `SPEC.md` must be cited **by section, never by line** (`doc/design/SPEC.md` §2.3 / §7.1 / §9), because the document is amended frequently and line anchors go stale within minutes (the same day saw six revisions). Rule identifiers (`T*`,`P*`,`V*`,`A*`,`J*`,`B*`,`F*`,`R*`,`C*`,`E*`) are stable and preferred; this applies to messages, task outputs and every document under `doc/design/**`. No rule semantics changed. |
| 1.5.2 | 2026-09-17 | **Captain v4 (LAST amendment) — four tags, no `CONTAINER:`.** `CONTAINER:` is **removed from the tag vocabulary** (a container-absolute path is the untagged P4 class and is hard-checked against `/data/dsh/home/workspace`, per the captain's (ii) confirmation); the tag set is exactly `HOST:`/`WORKSPACE:`/`ARTIFACT:`/`DEVICE:` and `DEVICE:` paths are recorded `UNVERIFIED (device path)`. §7.1 now states explicitly that the notations are **pure mechanical aliases** that create no separate judgement (`scope=host|workspace|artifact|device` ↔ the uppercase tags; precedence line > fenced > section > default), and keeps `WARN typo-suspect` for an `ARTIFACT:` path that is absent **and** matches no product pattern (`app/build/**`, `**/*.apk`, `**/*.so`), warning only per A6 (writer-ops' finding: `git check-ignore` returns IGNORED for non-existent paths, so the pattern match — not existence — decides). §2.3 table/T1/T2/T5, §7 V8/V10/V12, §7.1 P2/P3/P4 rows and the §7.5 checker notes updated to the four tags; changelog entries for 1.5.0 retain their historical "five tags" text. |
| 1.5.3 | 2026-09-17 | **t13 supplements + new pending-document rule; SPEC frozen.** (1) New **A14 / V14 `WARN pending document`**: a reference to a planned-but-absent sibling design document is a warning that never changes the exit code, while any other missing repository path still fails (captain-adopted). (2) Citation corrections from verifier's defect report: NAT enum now cites `signaling/protocol/message.go:31-36` **and** the validator `signaling/protocol/message.go:182`; `FrameDropper` additionally cites `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:236-243`; `GEN_JNI` has in-repo evidence (`WebRtcEngine.kt:236-243`, `app/src/test/kotlin/com/example/webrtcdemo/webrtc/JniBindingClasspathTest.kt:24-25`) while **`J.N` is no longer asserted as a source symbol** — it is cited as `ARTIFACT: third_party/libwebrtc/java/libwebrtc-java.jar` (ignored by `.gitignore:12`) and recorded `UNVERIFIED (vendored binary)` per J4; the vendored jar row in §2.1 now carries the `ARTIFACT:` tag. (3) The `WORKSPACE:` tag example set now includes the real device-log path in workspace-relative form (`WORKSPACE: tmp/n6/x/app.log`). (4) `01-requirements.md` replaced the unverifiable literal `Drop Frame` with the verifiable keys `DroppedFrames` (webrtc logs) and `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/` (app logs). |
