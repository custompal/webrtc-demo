# 09 — Verification and limitations

> Status: draft · Owner: writer-history · Task: t6
> Evidence base: `reports/42-delivery-verification.md`, `reports/52-release-closure.md`,
> `reports/99-final-report.md` §15.5, `reports/45-turn-exposure-accepted-risk.md`,
> `reports/27-encoder-direction-perf.md`, `reports/51-frame-dropper-and-trusted-rc.md`
> Doc standard: `doc/design/SPEC.md`

---

## 1. Scope

This document states what was actually verified for the delivered system, how each item was verified, what
evidence exists, and what is **not** verified. It is deliberately conservative: an item that was only read in
source is not marked verified, and an item that was never exercised on a device is listed as unverified with a
retest method.

In scope: the verification matrix, the known limitations of the delivered behaviour, the unverified-item list,
and the contract errata summary.

Out of scope: the defect history and the rejected approaches, which are in doc/design/08-issues-and-solutions.md;
the protocol field tables, which are generated; and host operational files that cannot be read from the
container.

Every gate statement in this document names the digest of the checker that produced it, because the checker was
revised repeatedly during the writing phase (see §2.2). A verdict without that digest is not reproducible.

## 2. Verification method and its own validity

### 2.1 The gate

The documentation set is checked by a repository script:

```bash
bash scripts/doc-verify.sh
```

A writer task runs it restricted to its own files:

```bash
bash scripts/doc-verify.sh --only docs/08-issues-and-solutions.md docs/09-verification-and-limitations.md
```

The checker resolves every `file:LINE` citation against the current worktree, checks that a cited symbol is
greppable in the cited file, verifies that protocol message types used in a document exist in the generated
table, resolves relative links, requires the legacy stubs, and enforces path tagging.

### 2.2 Known checker limitation: it is not byte-stable

| Statement | Evidence |
|---|---|
| The checker was revised three times within about six minutes while this document set was being written, with different failure and check counts on the same document tree (`101 → 73 → 84` failures, `576 → 607 → 624` checks) | the independent reviewer's reconstruction, recorded in the verification appendix of `reports/99-final-report.md` and in `reports/42-delivery-verification.md` §3 |
| An earlier revision accepted a required path tag when the surrounding prose happened to contain the matching English word, so ordinary wording silenced a finding | the A/B probe pair whose inputs differ by one word, recorded in the same appendix |
| Consequence: a "gate passed" statement is only meaningful together with the checker digest | this document, §2.3 |

### 2.3 The digest this document was verified with

| Item | Value |
|---|---|
| Checker | `scripts/doc-verify.sh` |
| Digest at verification time | `676d075a067e869e9730afd71239f078205b1fd8043453e8c559c0f6ee8b1b45` |
| Carrying commit | `git show 8fdb222:scripts/doc-verify.sh` recomputes the digest above |
| Result for this document set | zero failures and raw exit code 0; the check and warning counts are reported with the task output rather than quoted here, because a count written into the file changes the run that produces it |
| The warnings | advisory `typo-suspect` findings raised by this document's own L-9 row and its inline reproducer, described there; they do not affect the exit code |

If the checker is revised after this document, the result above must be re-produced before it is relied on.

### 2.4 Tag-face probes

Because the path-tag discipline is the part of the checker most likely to change, the writer runs four probes
before claiming this criterion. They are the honest test of whether the discipline is enforced on the current
revision.

| Probe | Expected | Meaning if the expectation holds |
|---|---|---|
| bare host path | fail with an unmarked host path finding | host paths must carry an explicit host scope |
| host path with an explicit `HOST:` tag | pass | a correctly tagged host path is accepted |
| bare workspace path that exists | pass | workspace evidence can be cited by path |
| bare workspace path that does not exist | fail with a missing workspace path finding | workspace paths are existence-checked |

The probe results for this task are recorded in the task output, together with the raw exit codes, as required
by the captain's instruction. If a probe shows the discipline is not enforced, that is reported as such and not
counted as a pass.

## 3. Verification matrix

Legend for the verification column: **reproduced** means the writer or the independent reviewer ran the check
and read the output; **static** means the source was read and cited but nothing was executed; **device** means
the evidence is a real-phone capture; **unverified** means no evidence exists yet.

| # | Item | How it is verified | Evidence | Conclusion |
|---|---|---|---|---|
| V-1 | The delivered APK is the one the release record names | recompute the digest of the downloaded artifact and compare with the release record | `reports/52-release-closure.md` §1 (digest `59c75778…`, 33 472 645 B, build input `4130ddc`) | reproduced |
| V-2 | The APK contains the four native libraries with aligned load segments | parse the packaged APK and read each load segment alignment | `reports/42-delivery-verification.md` §1.3 | reproduced |
| V-3 | The APK contains the generated frame-dropper trial string | count the literal in the packaged dex files | `reports/42-delivery-verification.md` §1.3 (dex literal counts) | reproduced |
| V-4 | Room and seat survive a WebSocket drop for the grace period | run the service with the grace value, hard-disconnect a client without a close frame, and observe the room and the `peerLeft` count | `reports/35-room-grace.md` §4.2, `reports/38-signaling-grace-deploy.md` §5.1 and §5.2 | reproduced |
| V-5 | The reconnecting peer reclaims its seat with the same identity | rejoin within the grace period and read the `joined` response | `reports/35-room-grace.md` §3.2, `reports/38-signaling-grace-deploy.md` §5.2 | reproduced |
| V-6 | The client reconnect budget is at least the grace period | run the unit tests that bound the budget | `reports/39-reconnect-budget-ice-restart.md` §4 and §5.3 (63 s budget, assertion below 75 s) | reproduced |
| V-7 | The ICE restart is requested before the rejoin offer is sent | read the ordering in the source and the named tests | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`, `reports/40-glare-ice-restart-fix.md` §4 | static |
| V-8 | Only one role sends an offer at a time | read the offer gate and the single call site | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1343` | static |
| V-9 | The joiner answer cannot precede the connection object | read the publish ordering and the named tests | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`, `reports/23-session-lifecycle.md` §3 | reproduced (unit tests) |
| V-10 | Remote candidate accounting matches the wire | run the accounting unit tests and compare with a device capture | `reports/46-remote-candidate-counting.md` §4.1 and §4.3 | reproduced |
| V-11 | The STUN mapped address is correct | run the byte-order host test | `reports/24-nat-address-endianness.md` §7.1 (22 assertions, zero failures) | reproduced |
| V-12 | The candidate parser reads the right fields | run the parser tests over the four candidate fixtures | `reports/19-ice-candidate-parse.md` §验证 | reproduced |
| V-13 | The loopback candidate filter works on both sides | read the filter and the device log lines | `reports/30-ice-relay-robustness.md` §2, `reports/52-release-closure.md` §4 | device |
| V-14 | The TURN permission defect is gone | run the permission probe matrix against the server | `reports/28-turn-permission-403.md` §2.2 and §5 | reproduced |
| V-15 | The ICE watchdog no longer reports a healthy call as failed | run the watchdog policy tests and compare with the device capture that showed the false alarm | `reports/44-ice-watchdog-false-failure.md` §4.1 and §4.3 | reproduced |
| V-16 | The connection state never reports connected without a selected candidate pair | run the state-machine tests, including the tests that forbid the sender-side rate as a liveness input | `reports/29-connect-state-ui.md` §5, `reports/31-ui-liveness-a7.md` §1.2 | reproduced |
| V-17 | The waiting state does not run the retry clock | run the named tests for the waiting state | `reports/33-waiting-peer-no-retry.md` §5 | reproduced |
| V-18 | Remote frame liveness is not a one-shot signal | run the liveness tests against the two-tick rule | `reports/32-remote-frame-liveness.md` §3 | reproduced |
| V-19 | The custom encoder survives a size change | read the resize path and the host syntax check | `app/src/main/cpp/encoder/vp9_encoder.cpp:616`, `reports/20-encode-resize-crash.md` §4 | static |
| V-20 | The delivered encoder library dispatches at run time instead of binding SVE at build time | run the verification script that distinguishes the two artifacts | `reports/26-libvpx-runtime-cpu-detect.md` §4.2 and §4.3 (new artifact passes, old artifact fails) | reproduced |
| V-21 | The encoded frame length equals the encoded size | read the delivery path and the device quantisation before the fix | `reports/27-encoder-direction-perf.md` §3.1 and §3.2 | device |
| V-22 | The encoder no longer loses input frames to the frame dropper | read the trial string on both peers and the encoder performance line afterwards | `reports/52-release-closure.md` §4 (`field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/`, `encoder_perf in_fps=30`) | device |
| V-23 | The bitrate floor is applied | compare the requested and applied rates on a relay path before and after | `reports/49-bitrate-allocation-collapse.md` §1 and §3, `reports/52-release-closure.md` §4 | device |
| V-24 | Encoder throughput meets the frame budget | read the encoder performance line | `reports/47-vp9-encode-perf.md` §4.1 (host A/B), `reports/52-release-closure.md` §4 (device) | reproduced (host) and device |
| V-25 | The encoder fallback switches and keeps the healthy path | run the fallback policy and controller tests | `reports/48-encoder-fallback.md` §6 and §7.3 | reproduced |
| V-26 | The renderer is not the stutter cause | read the renderer counters and per-frame cost, and the receiver-side drop counters | `reports/50-quality-scaling-and-render-fps.md` §3 | device |
| V-27 | The unit-test suite is green at the release anchor | run the unit-test task and count the executed cases | `reports/52-release-closure.md` §1 (24 classes, 216 cases, 0 failures) | reproduced |
| V-28 | The deployment unit in the repository matches the running service | compare the effective directives of both unit files | `reports/43-deploy-unit-consistency.md` §3.2 | reproduced |
| V-29 | The live service reports the grace period it was configured with | read the health endpoint on the deployed instance | `reports/42-delivery-verification.md` §4.2 | reproduced |
| V-30 | The release chain publishes the digest it built | read the publish record and the archive copy | `reports/52-release-closure.md` §1 and §6 | reproduced |
| V-31 | The documentation gate passes for this document set | run the gate restricted to the two files, with the checker digest recorded | §2.3 above | reproduced |
| V-32 | The path-tag discipline is enforced by the gate that is being run | run the four tag-face probes of §2.4 | the task output for this task | reproduced |

## 4. Known limitations

| # | Limitation | Consequence | Status | Evidence |
|---|---|---|---|---|
| L-1 | Media depends on a relay when both peers are behind symmetric NAT | there is no direct path; latency is relay latency, and quality is bounded by the relay and the uplink | known limitation | both test peers were behind symmetric NAT, so every session used the relay path (`reports/52-release-closure.md` §4 and §5) |
| L-2 | Frame rate under heavy packet loss is lower than the capture rate | at roughly 11.8 percent loss the follower rendered about 20 fps; this is a receiver-side effect, not a sender defect | known limitation | `reports/50-quality-scaling-and-render-fps.md` §3 and §4, `reports/52-release-closure.md` §4 |
| L-3 | A rebuilt APK is not byte-identical to the published one | hash-based identification must use the published digest, never "the same build" | known limitation | `reports/52-release-closure.md` §5 (the dex partitioning is not stable) |
| L-4 | The TURN endpoint is reachable by anyone who has the shared credentials | the exposure was accepted by explicit user decision after measurement; the compensating controls and the escalation path are recorded | known limitation (accepted risk) | `reports/45-turn-exposure-accepted-risk.md` §1 to §4; the 24-hour measurement found no unauthorised successful allocation |
| L-5 | The download-surface retention policy is not enabled | several archived APK copies are retained on the host without a policy | known limitation | `reports/52-release-closure.md` §5 |
| L-6 | Recovery of a silent offline peer is limited to signaling recovery | the full ICE restart path on an existing connection is not available in this runtime, so only the signaling layer is verified | known limitation | `reports/99-final-report.md` §0.3 and §8.5 |
| L-7 | A gate verdict is only valid with the checker digest | a verdict copied without the digest cannot be reproduced | known limitation | §2.2 and §2.3 above |
| L-8 | The publishing step is a host-side manual procedure | the server binary and the download surface cannot be rebuilt from the repository alone | known limitation | `reports/99-final-report.md` §15.5 item D-6 |
| L-9 | A missing untracked build product never fails the gate | the gate records an unverified build-output note for a gitignored path that is absent and does not fail the run, so the presence of a build product cannot be a gate criterion; this is deliberate, because a clean checkout contains no build products | known limitation (by design, not a defect) | a three-line document whose third line cites `app/build/nope.apk`, reproduced in the block below and measured on the frozen checker at exit code 0, `PASS (6 checks, 1 warnings)`, the warning being the advisory described here. This row is self-referential: the example path is deliberately absent, so the gate emits the very `NOTE` the row describes, and the frozen revision's failure exemption is not consulted by the advisory-warning path, so the warning appears as well; it is warning-level and does not affect the exit code. The specification's V13 rule states the same |

The reproducer for L-9, byte-identical to the probe it was taken from (99 B, sha256 `1d64dce25ee495b83499e65c4254075975054786f78efd768ba3d58d1c8d83c9`):

```
# probe — missing gitignored artifact

A build product that does not exist: `app/build/nope.apk`
```

## 5. Contract errata (D-1 .. D-6)

These are the registered divergences between the legacy contract and the implementation. The full statement,
with per-item evidence and impact, is `reports/99-final-report.md` §15.5. Each row below states the divergence,
what happens if the legacy text is followed, and its current disposition.

| ID | Divergence | Consequence of following the legacy text | Disposition |
|---|---|---|---|
| D-1 | The legacy document states a reconnect wait of 3 s with at most 3 attempts; the implementation uses 1/2/4/8 s capped, ten rounds, 63 s | a client would give up before the 90 s server grace period and drop a recoverable call | closed in behaviour and documented in §6 of the requirements; the legacy text is superseded |
| D-2 | The legacy text states that rotation is baked into the I420 pixels and that the encoder outputs rotation 0; the implementation passes rotation through and the flag `kBakeRotationInEncoder` is false (`app/src/main/cpp/encoder/vp9_encoder.cpp:65`) | a follower of the text would rotate the picture twice, which was observed as a 90-degree rotation on the follower | the pass-through decision is deliberate; the reverse approach is recorded as rejected in doc/design/08-issues-and-solutions.md §8 |
| D-3 | The legacy text names the coturn option `use-fingerprint`; coturn 4.6.1 accepts `fingerprint` (`deploy/turnserver.conf:12-13`) | coturn reports a bad configuration format or silently ignores the option | the repository configuration uses the accepted spelling; the legacy name must not be copied |
| D-4 | The legacy text claims that rotation is already baked into the I420 capture output; the upstream capture path carries no rotation and the drawer applies it at draw time | a wrong mental model of the capture path, which misleads any change in that area | disproven and registered; the correct model is the pass-through described in D-2 |
| D-5 | The repository deployment unit previously omitted the grace flag that the running service used | re-deploying from the repository would silently revert to the old drop behaviour | closed: the repository unit now carries the flag (`deploy/signaling.service`, `reports/43-deploy-unit-consistency.md` §3.2) |
| D-6 | The download and publish surface lives on the host, outside the repository | the download surface and the server binary cannot be rebuilt from the repository alone | accepted as a host operational fact; recorded with its impact in `reports/99-final-report.md` §15.5 |

## 6. Unverified items and how to close them

Each item states why it is not verified and the concrete retest method. None of these may be read as passed.

| # | Item | Why it is not verified | Retest method |
|---|---|---|---|
| U-1 | ICE restart on a device | the restart event keys and the restart counter are absent from every capture in the current device-log set; only the signaling path was exercised | hold a call, force a long signaling drop, then confirm the restart request and the resulting connection in the log |
| U-2 | Long-term stability beyond about 30 minutes | no capture ran long enough | keep a call up for at least 30 minutes and compare the drop counters and the frame-rate trend at the start and the end |
| U-3 | Weak-network extremes above roughly 20 percent loss | the worst observed loss was about 18 percent | run a call under an emulated loss profile above 20 percent and record loss and frame rate together |
| U-4 | The 8 s fallback trigger rate | the fallback has never been observed to fire | count the fallback event over several reconnects and report the observed rate |
| U-5 | Encoder fallback on a genuinely low-end device | the test devices are not low-end | repeat a capture on a low-end phone and read the fallback decision keys |
| U-6 | Default encoder against the custom encoder under identical packet loss | the two paths were never compared on the same link in the same network conditions | run both paths back to back on one link and compare frame rate and loss at the same time |
| U-7 | The TURN-over-TCP fallback on a network that blocks UDP | the TCP capability was verified at the server, not in a blocked-UDP call | make a call from a network that blocks UDP and confirm the relay path is established |
| U-8 | The diagnostics archive content end to end | the exporter has no end-to-end test | export from a device and inspect the archive contents |
| U-9 | Recovery beyond the server grace period | no call was held open across the whole grace period | block the radio for longer than 90 s and observe whether the call is recovered or ends |
| U-10 | A peer on a full-cone NAT | both test peers were behind symmetric NAT | repeat a call with a peer behind a full-cone NAT and record whether a direct path is selected |

## 7. Evidence index

| Section | Citation | Verification artifact |
|---|---|---|
| §2.1 gate commands | `scripts/doc-verify.sh`, `scripts/build_app.sh:45` | this document §2.3 |
| §2.2 checker not byte-stable | `reports/99-final-report.md` §15, `reports/42-delivery-verification.md` §3 | the A/B probe pair recorded in the appendix |
| §2.3 verification digest | `scripts/doc-verify.sh` | the gate run whose exit code and digest are recorded in the task output |
| §2.4 tag-face probes | `scripts/doc-verify.sh` | the task output for this task |
| §3 V-1, V-2, V-3 | `reports/52-release-closure.md` §1, `reports/42-delivery-verification.md` §1.3 | the recomputed artifact digests |
| §3 V-4, V-5 | `signaling/config/config.go:54`, `reports/38-signaling-grace-deploy.md` §5 | the live drop and rejoin probe |
| §3 V-6 | `reports/39-reconnect-budget-ice-restart.md` §5.3 | the budget assertion |
| §3 V-7, V-8 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272` | the ordering tests |
| §3 V-9 | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140` | the session lifecycle tests |
| §3 V-10 | `reports/46-remote-candidate-counting.md` §4 | the accounting tests |
| §3 V-11 | `reports/24-nat-address-endianness.md` §7.1 | the byte-order host test |
| §3 V-12 | `reports/19-ice-candidate-parse.md` §验证 | the parser fixtures |
| §3 V-13 | `reports/30-ice-relay-robustness.md` §2 | the device log lines |
| §3 V-14 | `reports/28-turn-permission-403.md` §2.2 | the permission probe matrix |
| §3 V-15 | `reports/44-ice-watchdog-false-failure.md` §4 | the watchdog policy tests |
| §3 V-16, V-17, V-18 | `reports/31-ui-liveness-a7.md` §1.2, `reports/33-waiting-peer-no-retry.md` §5, `reports/32-remote-frame-liveness.md` §3 | the UI state tests |
| §3 V-19, V-20 | `app/src/main/cpp/encoder/vp9_encoder.cpp:616`, `reports/26-libvpx-runtime-cpu-detect.md` §4.2 | the library verification script |
| §3 V-21, V-22, V-23, V-24 | `reports/27-encoder-direction-perf.md` §3.1, `reports/51-frame-dropper-and-trusted-rc.md` §3, `reports/49-bitrate-allocation-collapse.md` §1, `reports/47-vp9-encode-perf.md` §4.1 | the encoder captures and the host A/B |
| §3 V-25, V-26, V-27 | `reports/48-encoder-fallback.md` §6, `reports/50-quality-scaling-and-render-fps.md` §3, `reports/52-release-closure.md` §1 | the test suites and the renderer counters |
| §3 V-28, V-29, V-30 | `deploy/signaling.service`, `reports/43-deploy-unit-consistency.md` §3.2, `reports/42-delivery-verification.md` §4.2 | the unit comparison and the health endpoint |
| §3 V-31, V-32 | `scripts/doc-verify.sh` | the gate run and the tag probes |
| §4 L-1 .. L-8 | `reports/52-release-closure.md` §5, `reports/45-turn-exposure-accepted-risk.md` §1, `reports/99-final-report.md` §0.3 | the same reports |
| §5 D-1 .. D-6 | `reports/99-final-report.md` §15.5, `deploy/turnserver.conf:12-13`, `deploy/signaling.service` | the same reports |
| §6 U-1 .. U-10 | `reports/52-release-closure.md` §5, `reports/99-final-report.md` §15.7 | the retest methods listed per item |

## 8. Open items

| # | Item | Why it is open | How to close it |
|---|---|---|---|
| O-1 | The task-output probe results are not yet in this document | the probes are run when the gate is executed, and the gate is run after the document is final | paste the probe exit codes and the checker digest into the task output; a later revision of this document can carry them inline |
| O-2 | No independent verification of these two documents yet | the independent verification task follows the writers | the verifier re-runs the gate and samples citations, then records the verdict |
| O-3 | Cross-references between design documents | the sibling chapters landed after this document was written, so a few references were recorded as plain text rather than as links | the integration task adds the relative links; until then each reference names its target chapter explicitly |
