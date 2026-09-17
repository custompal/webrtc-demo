# 53 — Independent verification of the documentation system

Scope: the machine-checkable consistency of `doc/design/**` (SPEC, 01–11) with the implementation, plus the
compatibility symlink `docs -> doc/design`, the legacy stubs, and the generated tables.
Verifier: `verifier` (task t7). Read-only: no document, source file, or script was modified; no Gradle run; no commit.

**Verdict: `needs_revision`** — the document set does not yet satisfy its own delivery gate, and the checker
revision that the documents pin is not the checker revision that ships. The textual substance, however, verified
clean: every sampled citation, the protocol type set, all seven flow logs, the release-closure numbers and the
errata set were reproduced from source or from the raw device logs.

---

## 1. Commands and raw results

| # | command | result |
|---|---|---|
| 1 | `bash scripts/doc-verify.sh` | **exit 1** — `FAIL (5 failures, 2 warnings, 2334 checks)`, stderr 0 bytes |
| 2 | `bash scripts/gen-doc-tables.sh` | **exit 0** — regenerates all four tables, stderr 0 bytes |
| 3 | generator determinism (run twice, sha256 both times) | identical → deterministic |
| 4 | generator output vs on-disk `_generated/*` | **identical** → the committed tables are current, not stale |
| 5 | `sha256sum scripts/doc-verify.sh` (before/after the run) | equal during each run; **different between runs** (see F-02) |

Full-run output is preserved at `tmp/verifier-recon/t7/t7-final-run.txt` (working scratch, outside the repository).

### Checker revision actually exercised

| revision | lines | when | full-run result |
|---|---|---|---|
| `200ba92e…` | 587 | frozen by captain ruling A; pinned by `doc/design/11-coding-standards.md:156` | not on disk any more |
| `9a9b4bd2…` | 616 | 13:56 | FAIL 6 failures / 2316 checks |
| `92d9ea0d…` | 626 | final run | FAIL 5 failures / 2 warnings / 2334 checks |

`git status` reports ` M scripts/doc-verify.sh` throughout: the working tree diverged from the frozen revision.
The drift is visible inside a single pair of runs of the same command on unchanged documents: `2334 checks /
2 warnings` then `2333 checks / 1 warnings`, with a further new `NOTE … retired marker accepted as a hint` class
appearing at `SPEC.md:163` and `:166`, plus `NOTE … historical evidence, may drift` on report citations.
Counts and note classes are therefore **not reproducible across runs**, which is the concrete harm behind F-02.

### Every reported problem adjudicated

| doc | severity | adjudication |
|---|---|---|
| `SPEC.md:79`, `:92` — symbol `J.N` not in the cited C++ JNI files | **tool false positive** | `J.N` lives inside the gitignored `third_party/libwebrtc/java/libwebrtc-java.jar`. `SPEC.md:79` marks it *"descriptive only — referenced from reports"* and `SPEC.md:92` (J4) requires it be referenced from report evidence. The checker asserts it against project sources, contradicting the carve-out the SPEC itself declares. |
| `SPEC.md:92` — symbol `GEN_JNI` | **tool false positive, but cheaply fixable** | `GEN_JNI` does exist in project source: `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt` and `.../webrtc/JniBindingClasspathTest.kt`. The failure is a citation-target problem. |
| `SPEC.md:448` — unmarked host path `/var/log` | **tool false positive** | the line is the normative **P3 rule text** enumerating `/opt`, `/etc`, `/var`, `/var/log`, …; the rule's own prose is judged as a bare host path. |
| `SPEC.md:191` (earlier run) — `ARTIFACT:` applied to a version-controlled path: `tag.` | **tool false positive** | a sentence that *describes* the `ARTIFACT:` tag was parsed as marker + path (`tag.` is the following prose word). |
| `SPEC.md:469` — unmarked host path `/opt/apk-http/publish_apk.sh` | **genuine defect (low)** | a real table row citing a host path with no marker, which SPEC T1/V11 itself makes mandatory. |
| `WARN 02-architecture.md:35` — typo-suspect `http://47.238.144.66:8080/app-debug.apk` | **tool false positive (warning only)** | a URL is classified as an absent gitignored path. Does not affect the exit code. |
| `WARN 09-verification-and-limitations.md:137` — typo-suspect `app/build/nope.apk` | **tool false positive (warning only)** | a deliberately illustrative, intentionally absent path. |

Net: **1 genuine documentation defect**, 5 checker-side false positives, 2 warning-only false positives.
The gate exit code is nonetheless 1, so the delivery gate is objectively not met.

## 2. Manual citation spot-check (criterion: >= 20 %, floor 30)

Population: 1157 inline `file:line` citations; **475 distinct** `path:line` pairs across 83 files.
Method: a deterministic screening sheet (`every 20th` citation, statement + cited line side by side) plus targeted
checks on the substantive numbers. **285 citations were opened and compared by hand** (>= 20 % of 1157; ~3x the floor).

Result: **no material discrepancy in 285/285 sampled citations.** Representative verified examples:

* `SPEC.md:234` "WebSocket path `/ws`" → `signaling/server/server.go:25` = `const PathWS = "/ws"` ✓
* `SPEC.md:236` "room expiry `-room-expiry`" → `signaling/config/config.go:26` = `DefaultRoomExpirySec = 1800` ✓
* `SPEC.md:238` "grace period" → `signaling/config/config.go:54` = `DefaultRoomGrace = 90 * time.Second` ✓
* `SPEC.md:375/488` "line 516 is a statement inside the function starting at 512" → `ws_handler.go:512` is
  `func (s *Server) sendPeerLeft(...)` and `:516` is `if other.Closed() {` ✓ (the report-vs-HEAD drift argument holds)
* `01-requirements.md:106` "63 000 ms" → `ReconnectBudgetTest.kt:50` = `assertEquals(63_000L, budget)` ✓
* `01-requirements.md:107` "< 75 000 ms" → `ReconnectBudgetTest.kt:52` = `assertTrue("总预算必须 < 75 s…")` ✓
* `02-architecture.md:90` "3 ping intervals" → `config.go:38` = `DefaultPongWait = 3 * protocol.PingInterval` ✓
* `02-architecture.md:89` "ping every 15 s" → `heartbeat.go:11` = `PingInterval = 15 * time.Second` ✓
* `03-app-architecture.md:177` "`MAX_REJOIN_ATTEMPTS = 10` → 63 s budget" → `SignalingClient.kt:145` =
  `const val MAX_REJOIN_ATTEMPTS = 10`, and `:122` carries the comment stating the total 63 s budget ✓
* `04-signaling-service.md:268` "declared-only, no call site" → `heartbeat.go:17` `MaxReconnectAttempts = 3`; a
  repository-wide grep found **only the declaration** — the claim is exactly right ✓
* `05-protocols.md:165` "TURN over UDP first, TCP appended as fallback" → `WebRtcConfig.kt:105` doc-comment
  `@param turnTcpFallback 是否追加 turn:…?transport=tcp 回退` ✓
* `07-build-and-deploy.md:23` "JDK 17" → `../env.sh:37` = `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` ✓
* `07-build-and-deploy.md:90` "`ANDROID_HOME`" → `../env-container.sh:31` = `export ANDROID_HOME="$WORKSPACE_ROOT/android-sdk"` ✓
* `10-code-map.md` entries resolve to the claimed entry points (e.g. `vp9_encoder.cpp:77`, `errors.go:5`) ✓
* `11-coding-standards.md` rules carry real positive examples: `CallSession.kt:812` `gate(...)`,
  `:160` `readyLock`, `VideoRendererPool.kt:230` `renderer_attach_rejected` / `:236` `renderer_attached`,
  `LibwebrtcLoggable.kt:24` throttle note, `SignalingClient.kt:523` `ws_reconnect_suppressed`,
  `Vp9VideoEncoder.kt:367` `getResolutionBitrateLimits()` ✓

One low-severity citation slip found (see F-06).

## 3. Protocol cross-check (independent of the generator)

Sets extracted by hand from source, then compared:

| side | extraction | count |
|---|---|---|
| Go | `signaling/protocol/message.go` — `Type* = "…"` values | 14 |
| Go constants | the same file's `Type*` identifiers | 14 |
| Kotlin | `@SerialName("…")` in `.../signaling/SignalingMessage.kt` | 14 |
| generated table §1 (`signaling-messages.md`) | JSON value column / constant column | 14 / 14 |
| generated table §2 | Kotlin side | 14 |
| `doc/design/05-protocols.md` §3 catalogue (lines 48–61) | JSON value column | 14 |

All six sets are **identical**: `create created join joined peerJoined peerLeft offer answer ice natType leave
error ping pong`. No side has an extra or missing type. No divergence.

## 4. Flows vs raw device logs (criterion: >= 3 flows)

All **seven** flows in `06-flows.md` were checked against the raw logs; **20 cited lines** were opened at their
exact line numbers and every one matched verbatim. Container evidence root: `/data/dsh/home/workspace/tmp/n*/x/`.

| flow | citation | raw line (truncated) | verdict |
|---|---|---|---|
| 1 create/join/offer | `n1/x/app.log:40,89,95,99,102` | `room_created room=2C5MZN` / `peer_joined peer=peer-002` / `offer_created … sdp_bytes=2528` / `offer_sent sdp_bytes=2528` / `ice_candidate_local … type=host` | MATCH |
| 1 answer side | `n2/x/app.log:13057` | `answer_created … sdp_bytes=2450 session=s2` | MATCH |
| 2 drop/reclaim | `n2/x/app.log:1222,1223,1244,1246` | `ws_close reason=failure` / `ws_reconnect_scheduled attempt=1 budget_ms=63000` / `room_joined` / `rejoined` | MATCH |
| 2 exhausted | `n7/x/app.1.log:5991` | `ws_reconnect_give_up attempts=11 budget_ms=63000` | MATCH |
| 3 room lost | `n6/x/app.2.log:13492` | `room_not_found_action=keep_call code=ROOM_NOT_FOUND` | MATCH |
| 3 rebuild | `n7/x/app.1.log:6624,6634` | `room_recreate_invoked … role=joiner room=FANFQ2` / `room_recreated … session=s5` | MATCH |
| 4 peer left / waiting | `n2/x/app.log:7103,7104`, `n1/x/app.log:47` | `peer_left peer=peer-002` / `disconnect_cause cause=peer_left` / `ui_conn_state` | MATCH |
| 5 watchdog / anti-glare | `n1/x/app.log:129,115`, `n3/x/app.1.log:47` | `ice_watchdog_started … timeout_ms=30000 fail_ms=45000` / `ice_watchdog_rearmed` / `offer_deferred reason=pc_not_ready` | MATCH |
| 6 dropper / rates / renderer | `n6/x/app.2.log:6170`, `n4/x/native.1.log:1,105,1307`, `n1/x/native.log:17`, `n2/x/webrtc.log:349` | `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/` / `encoder_rates` / `encoder_perf` / `encoder_rate_floor floor_kbps=30` / `ts_target_kbps l0=120 l1=210 l2=300` / `DroppedFrames` | MATCH |
| 7 encoder fallback | `n6/x/app.1.log:5438` | `encoder_fallback_switch_signal … mech=in_call_switch` | MATCH |

**No fabricated log evidence was found**: every quoted line exists byte-for-byte at the cited line number.
The previously circulating literal `Drop Frame` does not appear in the docs; the correct tokens
(`DroppedFrames`, `frame_dropper=WebRTC-FrameDropper/Disabled/`) are the ones used.

## 5. Consistency with `reports/52-release-closure.md` and `reports/99-final-report.md` §15

| asserted fact | source of truth | result |
|---|---|---|
| grace window 90 s | `config.go:54` `DefaultRoomGrace = 90 * time.Second`; `deploy/signaling.service:28` `-room-grace 90s`; reports/52 | consistent |
| reconnect budget 63 s, assertion < 75 s | `ReconnectBudgetTest.kt:50/52`; `SignalingClient.kt:122`; raw log `budget_ms=63000`; reports/52 | consistent |
| rotation passed through (baking disproven) | `vp9_encoder.cpp:65` `kBakeRotationInEncoder = false`; `01` D-2/D-4, `08` R-1 rejected / R-7 disproven, `09` D-2/D-4; reports/52 | consistent |
| bitrate floor 30 kbps per step | `08:290`, `SPEC.md:255`, raw `encoder_rate_floor floor_kbps=30`, `Vp9VideoEncoder.kt:367`; reports/52 | consistent |
| start bitrates 120 / 190 kbps | `08:290` "non-zero start bitrates"; `06:456` `l0=120`; no doc asserts a conflicting value | no contradiction |
| FrameDropper disabled via field trial | `SPEC.md:258`, `06:438` block, raw `field_dropper=WebRTC-FrameDropper/Disabled/`; reports/52 | consistent |
| errata D-1..D-6 | `01-requirements.md` and `09-verification-and-limitations.md` tables agree with each other and with reports/99 §15 (reconnect text, rotation, coturn `fingerprint`, `-room-grace`, host download surface) | consistent |

## 6. Structure, stubs, language, symlink

* `docs -> doc/design` is a real symlink and resolves to a directory ✓
* all **15** legacy `doc/*.md` stubs are exactly **one line** each, English, and every one names a
  `doc/archive/...` target that **exists** ✓ (16 files under `doc/*.md`; `doc/README.md` is the intended
  105-line legacy→current mapping page, and `doc/adr/README.md` is the ADR index)
* English: all design documents are English. Chinese characters occur only in (a) the **intentional bilingual
  terminology table** (SPEC §3, ~30 entries), (b) verbatim report section-heading citations (`§验证` in 08 and 09),
  (c) one changelog phrase (`SPEC.md:714`). No Chinese prose body text.
* terminology matches `SPEC.md` §3 (the bilingual table is the vocabulary source; spot-checks of signalling,
  seat, grace period, takeover, glare, bitrate floor, frame dropper all follow it).

## 7. Findings

| id | severity | problem | required fix |
|---|---|---|---|
| **F-01** | **blocker** | the delivery gate fails: `bash scripts/doc-verify.sh` exits **1** (`FAIL 5 failures, 2 warnings, 2334 checks`), so SPEC §9 is not satisfied. | resolve the five failures (F-03, F-04, F-05) and re-run until exit 0. |
| **F-02** | **high** | checker revision instability: `11-coding-standards.md:156` pins `sha256 200ba92e… (587 lines, 24018 bytes)` while the shipped checker is `92d9ea0d…` (626 lines); at least four different checker revisions were observed in ~15 minutes and the working tree stayed modified (`git status` = ` M`) after the freeze. Tool-run evidence is therefore revision-bound. | declare one governing revision, restore the tree to it (or re-freeze and re-verify), update the pin in `11-coding-standards.md` §8.1, and re-run the gate on that revision. |
| **F-03** | **high** | checker contradicts the SPEC's own carve-out: `J.N` / `GEN_JNI` (SPEC §J3/J4, "descriptive only") are asserted against project JNI C++ sources and fail. | implement the J3/J4 carve-out (skip descriptive/third-party symbols, or accept a report citation), **and** cite `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt` / `.../webrtc/JniBindingClasspathTest.kt` for `GEN_JNI`. |
| **F-04** | **medium** | normative text is judged as content: `SPEC.md:448` (P3 prefix list `/opt /etc /var /var/log …`) fails as an unmarked host path, and the earlier `SPEC.md:191` parsed "the `ARTIFACT:` tag" as marker + path. | exempt normative rule text — e.g. allow a `scope=` fence or a documented "rule text" marker on such lines, or reword the SPEC to avoid bare host paths in the rule body. |
| **F-05** | **medium** | genuine defect: `SPEC.md:469` cites host path `/opt/apk-http/publish_apk.sh` with no marker, which SPEC T1/V11 itself mandates. | write `HOST: /opt/apk-http/publish_apk.sh` (or add `# HOST (evidence)` / a `scope=host` fence). |
| **F-06** | **low** | citation off by two: `08-issues-and-solutions.md:279` and `:460` cite `app/src/main/cpp/encoder/vp9_encoder.cpp:63` for "the flag is false"; `:63` is the explanatory comment, the flag `constexpr bool kBakeRotationInEncoder = false;` is at `:65`. | cite `:65` (or the range `63-65`). |
| **F-07** | **low** | the `../env.sh` / `../env-container.sh` notation is a workspace-root **label**, not a resolving relative path (from the repo root the real path is `../../env.sh`); the checker normalises it, so it passes while a reader may be misled. The underlying claims were verified true. | state in SPEC §7.1 / `11` §8.1 that the parent prefix is a label normalised against the workspace root. |
| **F-08** | **low** | `WARN typo-suspect` false positives: `02-architecture.md:35` (a URL `http://…/app-debug.apk`) and `09-verification-and-limitations.md:137` (a deliberately illustrative `app/build/nope.apk`). Warnings do not affect the exit code. | exempt URLs, and/or let an explicit illustrative marker suppress the warning. |
| **F-09** | **info** | Known limitations confirmed rather than defects: `p5-artifact` has no failure path (a missing build product can never fail — intentional per SPEC V13, and `note()` output is **not counted** in the summary); the host/workspace-only command-name evidence threshold is not tightened (captain-confirmed breadth difference). | record as known limitations / gate-v2 backlog; no document change. |

## 8. Verdict

**`needs_revision`.**

The textual substance is in good order — 285 sampled citations, the six-way protocol set, all seven flows against
raw device logs, the release-closure numbers and the errata tables all reproduced exactly, with one low-severity
citation slip (F-06) and one low-severity notation ambiguity (F-07). Nothing in the sampled evidence contradicts
`reports/52-release-closure.md` or `reports/99-final-report.md` §15.

The release condition is nonetheless not met: the machine-checkable gate that this document system exists to
provide **exits 1** (F-01), one genuine marker defect sits in the SPEC (F-05), two checker-side rule conflicts
make a green gate unreachable (F-03, F-04), and the checker revision the documents pin is no longer the checker
that runs (F-02). F-03/F-04/F-05 are all small, well-localised fixes; once applied and re-run on a single frozen
revision, a `pass` verdict is realistic.

*Verification is bound to checker sha256 `92d9ea0d…` (626 lines) for the tool runs and to the raw device logs and
source files listed above; the repository content read was the working tree at the time of verification.*
