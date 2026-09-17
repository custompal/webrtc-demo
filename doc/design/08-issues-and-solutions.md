# 08 — Issues and solutions

> Status: draft · Owner: writer-history · Task: t6
> Evidence base: `reports/15-connection-defect.md` .. `reports/52-release-closure.md`, `reports/99-final-report.md` §15,
> `reports/27-encoder-direction-perf.md`, `reports/49-bitrate-allocation-collapse.md`, `reports/51-frame-dropper-and-trusted-rc.md`
> Doc standard: `doc/design/SPEC.md`

---

## 1. Scope

This document is the defect history of the delivered system. For every defect it states the symptom, the log
signature observed on a device, the root cause at `file:LINE` level, the fix, the verification evidence
(report number and key numbers), and the residual risk. It closes with the approaches that were proposed,
attempted or assumed and were **rejected or disproven**, because those decisions explain why the current
implementation looks the way it does.

In scope: defects that produced a wrong, misleading or unusable behaviour, plus the tooling and environment
defects that blocked diagnosis. Out of scope: normal feature evolution, the legacy design documents under
`doc/archive/`, and the contract errata, which are summarised in doc/design/09-verification-and-limitations.md
§5.

Citation rules applied here, per `doc/design/SPEC.md` §4 and §5:

* Code pointers are `file:LINE` citations taken from the **current HEAD** worktree, never copied from a report.
* Reports are cited by **name and section** (`reports/35-room-grace.md` §5.1), never as `reports/<file>:LINE`,
  because report line numbers have drifted.
* Log keys and field keys are quoted exactly as the code emits them. A key that exists only in a device log
  and not in this repository's source is quoted as a report object, not as a `file:LINE` symbol.
* Host paths are marked with a `HOST:` prefix. Build products are written as bare repository-relative paths.

Status vocabulary is fixed by `doc/design/SPEC.md` §6 (E4): `implemented`, `partially implemented`,
`known limitation`, `unverified`, `rejected`, `disproven`.

## 2. Module: signaling, rooms and reconnect

### 2.1 The joiner never sent an answer

| Field | Content |
|---|---|
| Symptom | Two peers joined the same room and neither ever showed video; the call stayed silent on the joiner side |
| Log signature | `offer_received` present, `answer_create` **absent** for the whole session; the server kept the room alive but no SDP ever flowed back |
| Root cause | The joiner's offer handler ran before any session existed, so the incoming offer was dropped. Fixed at the time by queueing, and superseded by the two-level queue of §2.6 |
| Fix | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` creates the session and then answers; the offer is no longer handled outside a live session |
| Evidence | `reports/15-connection-defect.md` §2.2 and §2.3, with the log-level defect of §2.8 as the reason the absence was initially invisible |
| Residual risk | none known; the answer path is covered by the session-lifecycle ordering fix of §2.2 |

### 2.2 The joiner answered before the `PeerConnection` existed

| Field | Content |
|---|---|
| Symptom | One direction carried no media at all: the joiner showed the remote stream but the host never received video. Reproduced intermittently, "sometimes it works" |
| Log signature | `answer_create` appeared **before** `pc_created` in the same session generation; the sender-side statistics stayed at `stats_sample impl=- up_bps=0` for the whole call |
| Root cause | The `PeerConnection` was created asynchronously while the answer was created on the signaling thread; the ordering between `pc_created` and `answer_create` was a race (`reports/23-session-lifecycle.md` §2.1 and §2.2) |
| Fix | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt` publishes the `PeerConnection` only after the local tracks and the encoding preference are ready, so `pc_created` precedes `answer_create`; a start gate plus a generation-scoped call-session slot removes the TOCTOU window (`reports/23-session-lifecycle.md` §3.1 and §3.2) |
| Evidence | `reports/23-session-lifecycle.md` §1.1 (defect session) against §1.2 (healthy session on the same device), and the 18 new unit tests reported in `reports/23-session-lifecycle.md` §5 |
| Residual risk | none known; ordering is asserted by unit test, not by a device capture |

### 2.3 Remote candidates were double-counted and SDP candidates were not counted at all

| Field | Content |
|---|---|
| Symptom | The diagnostics banner reported a remote-candidate count that did not match the wire, which made "is a relay candidate present?" undecidable |
| Log signature | `ice_candidate_remote` appeared twice for a single trickled candidate when the message had been queued and replayed; candidates embedded in the SDP produced **no** count at all, and the summary showed `-` for a non-zero set |
| Root cause | The only counting point was the remote-candidate entry callback, which runs again when a queued candidate is replayed; candidates inside `answer_received` were used for logging only and never entered the counter (`reports/46-remote-candidate-counting.md` §1) |
| Fix | Counting is bound to first arrival (a replay is recognised and not recounted), SDP-embedded candidates are accounted in a separate bucket, and per-candidate logging is rate-limited (`reports/46-remote-candidate-counting.md` §2.1 to §2.3) |
| Evidence | 5 new unit tests (`RemoteCandidateAccountingTest`, `reports/46-remote-candidate-counting.md` §4.1) and the old-red control at `reports/46-remote-candidate-counting.md` §4.3 |
| Residual risk | the fix changes the meaning of a counter that earlier reports quoted; read a candidate count from the current build only (`reports/46-remote-candidate-counting.md` §5) |

### 2.4 A short signaling drop destroyed the room

| Field | Content |
|---|---|
| Symptom | A momentary WebSocket drop ended the call for both peers even though the media path was healthy |
| Log signature | the surviving peer received `peerLeft` immediately after the drop, and a rejoin within seconds returned `ROOM_NOT_FOUND` (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:244`) |
| Root cause | room and seat were reclaimed as soon as the socket closed; there was no window in which a reconnecting peer could reclaim its seat (`reports/35-room-grace.md` §1) |
| Fix | a configurable room grace period keeps the room and the seat, `signaling/config/config.go:54` (`DefaultRoomGrace = 90 * time.Second`); the reconnecting peer takes its seat back at `signaling/room/room.go:66`; the peer is marked pending at `signaling/room/room.go:113` and expired at `signaling/room/room.go:140`; `peerLeft` is sent exactly once, from `signaling/server/ws_handler.go:512` |
| Evidence | `reports/35-room-grace.md` §3 (9 new unit tests, one per acceptance item) and §4.2 (live binary + real TCP probe); the deployment of the same value is `reports/38-signaling-grace-deploy.md` §3 and §5 |
| Residual risk | the value must stay above the client reconnect budget, see §2.5 |

### 2.5 The client reconnect budget was shorter than the server grace period

| Field | Content |
|---|---|
| Symptom | A reconnecting client gave up and left the call while the server was still holding its seat |
| Log signature | rejoin attempts stopped before the grace period expired; the client reported a room error and dropped back to the home screen |
| Root cause | the budget was three fixed attempts, unrelated to the 90 s grace period (`reports/39-reconnect-budget-ice-restart.md` §1) |
| Fix | the budget reuses the existing rejoin delays: 1/2/4/8 s capped, ten rounds, 63 s total, asserted below 75 s (`reports/39-reconnect-budget-ice-restart.md` §2.1); exhaustion is dispatched by media-liveness state (`reports/39-reconnect-budget-ice-restart.md` §2.2) |
| Evidence | `reports/39-reconnect-budget-ice-restart.md` §4 (named unit tests) and §5.3 (re-run with the assertion bound tightened to 75 s) |
| Residual risk | the budget is a compile-time constant; a future grace-period change must be reconciled with it |

### 2.6 Remote messages arriving before the session existed were dropped

| Field | Content |
|---|---|
| Symptom | After a rejoin or a fast join, the offer and the candidates were lost and the call never connected even though both peers were in the room |
| Log signature | `offer_received` earlier than `pc_starting`; the candidate list in the SDP never reached the remote side; a 20 s "peer is not responding" hint appeared |
| Root cause | two windows: `session == null` before `start()` (window A, the object of the fix) and a session whose `PeerConnection` was not ready yet (window B, already covered by the earlier ordering fix) (`reports/21-remote-message-race.md` §2.1 and §2.2) |
| Fix | a single queue with readiness-driven replay: messages arriving early are stored and replayed in order once the session and the `PeerConnection` are ready (`reports/21-remote-message-race.md` §3.1); a 20 s no-response path makes the stall diagnosable (`reports/21-remote-message-race.md` §3.2) |
| Evidence | `reports/21-remote-message-race.md` §1.1 against §1.3 (the replay path working in another session) and the unit tests in `reports/21-remote-message-race.md` §5 |
| Residual risk | replay ordering is asserted by unit test only |

### 2.7 A dropped peer was disconnected locally, and a lost pong destroyed the call

| Field | Content |
|---|---|
| Symptom | A single lost pong escalated to a full call teardown; the call was also ended locally as soon as the remote peer dropped, before the grace period could help |
| Log signature | `onStateChanged(DISCONNECTED)` drove the local hang-up (not a message branch); `room_not_found_action` and `peerLeft` handling both ended the call |
| Root cause | the local state machine treated a transient signaling state as final, and grace-period semantics were implemented server-side only (`reports/36-call-survivability.md` §1.3 and §2.2) |
| Fix | pong tolerance with an effective threshold of at least 20 s, `peerLeft` and `ROOM_NOT_FOUND` no longer end the call, and media-liveness suppresses false ICE failures while media still flows (`reports/36-call-survivability.md` §2.1 to §2.3) |
| Evidence | `reports/36-call-survivability.md` §5.1 (named tests) and §5.3 (old-red / new-green control); the server-side alignment is `reports/36-call-survivability.md` §9 |
| Residual risk | long-outage recovery beyond the grace period stays a limitation, see doc/design/09-verification-and-limitations.md §3 |

### 2.8 The application log filter was inverted, hiding every INFO and WARN line

| Field | Content |
|---|---|
| Symptom | Nothing but DEBUG lines reached the exported log, so the first two defects above could not be diagnosed from a device capture |
| Log signature | level distribution `DEBUG 257 / other 0` in the exported `app.log`; `rtc_config` (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:177`), `pc_created` (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:389`), `pc_ice_connection_state` (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:87`), `answer_create` and `offer_received` (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:457`) and `call_init` (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:586`) all had **zero** hits, while the native log at the same time showed its INFO lines normally |
| Root cause | the threshold test was written the wrong way round — it admitted levels at or below the threshold instead of at or above it |
| Fix | `app/src/main/kotlin/com/example/webrtcdemo/log/LogLevel.kt:53` now tests `level.code >= code`, so level N admits every level at least as severe as N, `OFF` silences everything, and the two sides of the app agree |
| Evidence | `reports/15-connection-defect.md` §4 (the level histogram and the zero-hit list) |
| Residual risk | the Kotlin and native filters are separate implementations; a change to one must be mirrored in the other |

### 2.9 The ICE restart was sent in the wrong order and could collide with the peer

| Field | Content |
|---|---|
| Symptom | After a rejoin the new offer carried no ICE restart, so the media path kept using stale candidates; a simultaneous renegotiation could deadlock |
| Log signature | the rejoin offer lacked the `iceRestart` flag; both sides attempted an offer in the same window |
| Root cause | the offer was sent before `restartIce()` was requested, and both roles were allowed to renegotiate (`reports/40-glare-ice-restart-fix.md` §1) |
| Fix | `restartIce("rejoin")` runs before the offer is created, only the peer-joined side initiates, and an 8 s fallback covers a missing answer (`reports/40-glare-ice-restart-fix.md` §2.1 and §2.2) |
| Evidence | `reports/40-glare-ice-restart-fix.md` §4 (4 new tests) and §5.3 (removing the fallback makes the test red) |
| Residual risk | the restart has never been exercised in any device capture, see doc/design/09-verification-and-limitations.md §4 |

## 3. Module: ICE, network, NAT and TURN

### 3.1 STUN mapped addresses were printed byte-reversed

| Field | Content |
|---|---|
| Symptom | The reported public address did not match the address the peer actually saw; the NAT classification was therefore untrustworthy |
| Log signature | a mapped address whose bytes are the reverse of the real one in the NAT event payload |
| Root cause | a big-endian integer read from the STUN attribute was handed to the address formatter as if it were a network-order byte array (`reports/24-nat-address-endianness.md` §3.1 and §3.2) |
| Fix | a byte-safe parser at `app/src/main/cpp/nat/stun_address.h` handles only network-order bytes and applies the RFC 5389 §15.2 XOR rule byte by byte; `MAPPED`, `RESPONSE-ORIGIN` and `OTHER-ADDRESS` were re-checked on the same path (`reports/24-nat-address-endianness.md` §4.1 and §5) |
| Evidence | `reports/24-nat-address-endianness.md` §7.1 (22 byte-order assertions, zero failures) and §2 (before/after address pairs) |
| Residual risk | `XOR-PEER-ADDRESS` and `XOR-RELAYED-ADDRESS` are not parsed by this path (`reports/24-nat-address-endianness.md` §5) |

### 3.2 ICE candidate strings were parsed one field out of step

| Field | Content |
|---|---|
| Symptom | Diagnostics attributed the wrong protocol, address and port to a candidate, which made "was a relay candidate gathered and selected?" unanswerable |
| Log signature | test failures of the form `expected:<udp> but was:<2122260223>` and `expected:<120.230.119.5> but was:<7627>`; the summary reported protocol `2122260223` and port `0` |
| Root cause | the `candidate:` prefix and the foundation share one token; the parser removed the whole token and shifted every later field by one (`reports/19-ice-candidate-parse.md` §root cause) |
| Fix | only the prefix is stripped and the foundation stays as the first field, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/IceCandidateInfo.kt:61` |
| Evidence | `reports/19-ice-candidate-parse.md` §验证 (4 fixtures: host, srflx with raddr/rport, relay, `a=`-prefixed with extra whitespace; `bad=0`) |
| Residual risk | none known; the parser feeds the candidate diagnostics, so a regression would misreport rather than crash |

### 3.3 coturn rejected relay permissions for loopback candidates

| Field | Content |
|---|---|
| Symptom | On a 4G-to-WiFi call the turn allocation was created but the peer could not be reached; the call fell back to no media |
| Log signature | `CreatePermission` answered `403 Forbidden IP`; the client reported `local_relay=0`, and `ice_turn_error code=701` followed the filtered candidates |
| Root cause | both devices advertised loopback candidates, and coturn's built-in deny list covers `0/8` and `127/8`, so the permission request was refused (`reports/28-turn-permission-403.md` §2.1 and §2.2) |
| Fix | loopback candidates are filtered on both client sides (`ice_candidate_filtered reason=loopback`, `reports/30-ice-relay-robustness.md` §2), and the turn configuration was hardened for quotas and lifetime (`reports/28-turn-permission-403.md` §4.1) |
| Evidence | the probe matrix at `reports/28-turn-permission-403.md` §2.2 and the end-to-end retest with a real peer at `reports/28-turn-permission-403.md` §5 |
| Residual risk | the loopback deny entries are deliberate and must stay; the deployment facts are host-side (`HOST: /etc/turnserver.conf`) |

### 3.4 The ICE watchdog reported failure on a healthy call

| Field | Content |
|---|---|
| Symptom | A red "ICE not connected" banner sat on top of a working video call, and stayed there |
| Log signature | `ice_watchdog_rearmed … elapsed_ms=1789529664872` immediately followed by `ice_timeout after_ms=1789529664872`, while 300 ms later `ice_watchdog_ok elapsed_ms=306` and `pc_ice_connection_state state=CONNECTED` arrived |
| Root cause | the watchdog start time was uninitialised, so the elapsed value was the epoch in milliseconds and the 45 s threshold was always satisfied; the error text then had no clearing path (`reports/44-ice-watchdog-false-failure.md` §1) |
| Fix | a base-value guard and a threshold/duration consistency guard, plus a recovery path that clears the banner (`reports/44-ice-watchdog-false-failure.md` §2.1 to §2.3) |
| Evidence | `reports/44-ice-watchdog-false-failure.md` §4.1 (6 named tests, `IceWatchdogPolicyTest`) and §4.3 (reverting the guards makes them red) |
| Residual risk | the same "uninitialised base" pattern was searched for across all call sites; the search is recorded in `reports/44-ice-watchdog-false-failure.md` §2.4 |

### 3.5 The UI reported "connected" while the call was not connected

| Field | Content |
|---|---|
| Symptom | A frozen frame was presented as a live picture, no state was shown, and there was no way to retry |
| Log signature | the statistics showed the encoder running with **no selected candidate pair**; the session summary was empty; a previous "connected then lost" episode was invisible |
| Root cause | three write points equated "not yet connected" with "connected", and the liveness test used the sender-side rate (`up_bps`) which cannot prove that anything arrived (`reports/29-connect-state-ui.md` §2) |
| Fix | a plain state machine, `app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt`, with a single writer and a liveness test that never uses `up_bps`; a 15 s failure card and a retry path were added (`reports/29-connect-state-ui.md` §3, `reports/31-ui-liveness-a7.md` §1) |
| Evidence | `reports/29-connect-state-ui.md` §5 (unit tests) and `reports/31-ui-liveness-a7.md` §1.2 (the named tests that forbid `up_bps` as a liveness input) |
| Residual risk | the thresholds are policy values; changing them changes user-visible behaviour |

### 3.6 Remote frames were reported missing while the picture was updating

| Field | Content |
|---|---|
| Symptom | The screen showed "waiting for the other side's video" over a live picture, and the status oscillated between connected and connecting |
| Log signature | the renderer was rendering frames while the liveness signal stayed silent, because the only liveness source was a one-shot first-frame callback |
| Root cause | the selected-pair branch returned first and made the downlink-rate channel unreachable, and only a one-shot callback proved that frames had arrived (`reports/32-remote-frame-liveness.md` §1.2) |
| Fix | a per-frame sink wrapper counts frames and records wall-clock time, the downlink rate feeds liveness independently, and a stalled signal must persist for two ticks (`reports/32-remote-frame-liveness.md` §2.1 to §2.3) |
| Evidence | `reports/32-remote-frame-liveness.md` §3 (unit tests) and §5 (the contract checks) |
| Residual risk | the two-tick rule delays a genuine stall report by one tick |

### 3.7 Waiting for the peer was presented as a failing connection

| Field | Content |
|---|---|
| Symptom | Before the second peer joined, the first peer saw "connecting" and then a retry prompt, as if its own connection had failed |
| Log signature | the retry clock ran while no peer had ever joined, so elapsed time grew and the timeout branch fired |
| Root cause | the state machine had no dedicated waiting state, so "no peer yet" and "peer present but unreachable" shared the connecting state and its clock (`reports/33-waiting-peer-no-retry.md` §1.2) |
| Fix | a waiting state (`waiting_peer`) with a stopped clock, `elapsedMs=0`, no timeout evaluation and `canRetry=false`, entered before any peer joins and left when the peer joins (`reports/33-waiting-peer-no-retry.md` §2.1) |
| Evidence | `reports/33-waiting-peer-no-retry.md` §5 (unit tests) and §6 (the contract checks) |
| Residual risk | the state transition depends on `peerJoined`; a missed message would keep the UI waiting, which is the safer failure direction |

### 3.8 TURN over UDP was the only transport that was attempted

| Field | Content |
|---|---|
| Symptom | On a network that blocks UDP, the relay path was never established even though the TURN server also listened on TCP |
| Log signature | the relay candidate list was empty or the relay candidate never became usable, with no TCP relay attempt in the log |
| Root cause | the ICE server list contained a single UDP TURN URL (`reports/34-turn-tcp-fallback.md` §1) |
| Fix | a TCP TURN URL is appended after the UDP entry, with the same credentials, on by default and switchable; the URL construction was extracted into pure functions so a JVM test can assert it (`reports/34-turn-tcp-fallback.md` §2.1) |
| Evidence | `reports/34-turn-tcp-fallback.md` §5 (unit tests, 132 total at that revision) and the pre-check of TCP reachability in `reports/58`-series evidence summarised at `reports/34-turn-tcp-fallback.md` §1 |
| Residual risk | the TCP fallback was not exercised on a device during this phase, see doc/design/09-verification-and-limitations.md §4 |

## 4. Module: the custom VP9 encoder

### 4.1 The first encoded frame killed the process

| Field | Content |
|---|---|
| Symptom | The call connected and then the app died within a second of the first frame; the pattern repeated on every attempt |
| Log signature | the last line before the crash is `encode_vpx_begin frame=1 …`; `encode_vpx_done` **never** appears, and no C++ error line is written, which is the signature of a signal rather than an exception |
| Root cause | the delivered `libvpx` was built with runtime CPU detection disabled, so the generated dispatch table bound its rate-distortion error function directly to the SVE implementation; the test phone has no SVE, so the first key frame raised `SIGILL` (`reports/25-encoder-vpx-encode-crash.md` §4) |
| Fix | the delivered library was rebuilt with runtime CPU detection enabled (`reports/26-libvpx-runtime-cpu-detect.md` §2), and the in-app CPU probe was demoted to a diagnostic that no longer rejects the encoder (`app/src/main/cpp/encoder/vp9_encoder.cpp:84-90`) |
| Evidence | `reports/25-encoder-vpx-encode-crash.md` §1 and §2 (host reproduction and reverse proof), `reports/26-libvpx-runtime-cpu-detect.md` §4 (new artifact passes, old artifact fails the same script) |
| Residual risk | the decoder side is the platform one; only the encoder library is rebuilt here |

### 4.2 Changing the encoder size crashed on the next frame

| Field | Content |
|---|---|
| Symptom | With a rotated camera frame the app crashed as soon as the encoding size changed, which is exactly what happened on a portrait device |
| Log signature | `encoder_resize` reported success and the very next frame produced no output and no error before the process died |
| Root cause | the size was changed through the encoder configuration-update call, which does not rebuild the internal frame buffers in this single-pass low-latency configuration, leaving the image and the codec at different sizes (`reports/20-encode-resize-crash.md` §2) |
| Fix | a size change now destroys and re-creates the codec context and forces a key frame, `app/src/main/cpp/encoder/vp9_encoder.cpp:616`, so the follower can start decoding again immediately |
| Evidence | `reports/20-encode-resize-crash.md` §1 (device evidence) and §4 (verification); the follow-up buffer-ownership defect is §4.3 below |
| Residual risk | the rate-update call still writes the encoder configuration, which is safe because it does not change the size (`reports/20-encode-resize-crash.md` §3) |

### 4.3 The encoder wrapped a caller-owned buffer

| Field | Content |
|---|---|
| Symptom | The size-change fix was not sufficient: the first frame still killed the process, from the same place |
| Log signature | the frame reached the encoder and the process died inside the encode call, with the image described as an externally owned buffer |
| Root cause | the previous fix had removed a leak by wrapping the caller's plane pointers, which left the encoder with an image whose data it did not own and whose chroma stride followed the luma width (`reports/22-encode-selfowned-image.md` §2) |
| Fix | the encoder keeps its own image, allocated by the library, and copies each plane row by row into it; the image is freed with the codec context (`reports/22-encode-selfowned-image.md` §3) |
| Evidence | `reports/22-encode-selfowned-image.md` §1 and §4; the allocation marker reports the owned-image strides at `reports/22-encode-selfowned-image.md` §3 |
| Residual risk | one extra copy per frame remains in the pipeline, which is accounted for in the encoding-latency budget |

### 4.4 The reported frame length was the buffer capacity

| Field | Content |
|---|---|
| Symptom | Severe stalling on the custom encoder: the sender reported several megabits per second while the follower received a few broken frames per second |
| Log signature | `encoder_perf` reported 0.25 to 0.45 encoded frames per second for a 30 fps input, and `up_bps` reached 3 to 5 Mbps while the encoded frame size stayed around 2.9 KB per frame |
| Root cause | the delivered frame reused a 512 KiB direct buffer and moved only its position and limit; the receiving library reads the buffer **capacity**, so every frame was reported as 512 KiB, which is about 63 times the per-frame budget. That drove the frame dropper and made the RTP packer iterate past the real frame (`reports/27-encoder-direction-perf.md` §3.2) |
| Fix | each delivered frame now gets a direct buffer of exactly the encoded size, and the capacity is logged (`reports/27-encoder-direction-perf.md` §3.3) |
| Evidence | `reports/27-encoder-direction-perf.md` §3.1 (the device quantisation) and §3.2 (the upstream read path) |
| Residual risk | none known; the fix also removes a false rate signal that earlier reports quoted |

### 4.5 The custom encoder produced a rotated picture

| Field | Content |
|---|---|
| Symptom | The follower saw the picture rotated 90 degrees counter-clockwise relative to the sender's screen |
| Log signature | the device reported `rot=270` at the input while the picture arrived rotated |
| Root cause | the rotation was applied inside the encoder **and** the frame still carried a rotation annotation, and the input frames were already delivered in display orientation, so the rotation was applied twice in effect (`reports/27-encoder-direction-perf.md` §2.2) |
| Fix | the encoder passes rotation through by default (`app/src/main/cpp/encoder/vp9_encoder.cpp:65`, the flag is false) and the Kotlin side stops claiming that it baked the rotation into the pixels (`reports/27-encoder-direction-perf.md` §2.3) |
| Evidence | `reports/27-encoder-direction-perf.md` §2.1 (corner-case unit test proving the rotator itself is correct, 39 assertions, zero failures) |
| Residual risk | the pass-through decision is documented in §5.2 as a rejected alternative, not a neutral choice |

### 4.6 The bitrate collapsed to a few tens of kilobits per second

| Field | Content |
|---|---|
| Symptom | A custom-encoder call on a relay path collapsed to an unusable picture while a default-encoder call on the same path a few minutes later was fine |
| Log signature | `avail_bps≈0`, `up_bps≈40 kbps` and `total_bps` falling to 37 kbps within a second, with the quantiser pinned at 193 to 224 |
| Root cause | the per-resolution bitrate table declared a minimum of zero for every resolution, which removes the floor from the allocation: once the estimator probes downward there is nothing to recover to (`reports/49-bitrate-allocation-collapse.md` §2, hypothesis H3) |
| Fix | the table now follows the official single-cast VP9 limits with a 30 kbps floor per step and non-zero start bitrates, exposed through `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:367` |
| Evidence | the A/B pair at `reports/49-bitrate-allocation-collapse.md` §1 and the before/after numbers in §3; the closure numbers are in `reports/52-release-closure.md` §3 |
| Residual risk | the same call reached about 1.0 to 1.16 Mbps afterwards on a relay path with 1.6 to 2.2 Mbps available, so the floor is the floor, not a quality guarantee |

### 4.7 The encoder was slow enough to miss the frame budget

| Field | Content |
|---|---|
| Symptom | Visible stutter with a single-threaded encoder even when the network was not the bottleneck |
| Log signature | `encoder_perf` reported a p50 above 15 ms and a p95 near 19 ms for 480x640 frames |
| Root cause | the encoder used one worker thread and no row-based multi-threading, so it could not use the available cores (`reports/47-vp9-encode-perf.md` §2.3) |
| Fix | the worker count became `min(cores - 1, 4)` with row-based multi-threading enabled, and a rate-request policy keeps the applied rate at or above the requested one with a 30 kbps lower bound (`reports/47-vp9-encode-perf.md` §4) |
| Evidence | the host A/B at `reports/47-vp9-encode-perf.md` §4.1 (p50 15.33 ms to 9.34 ms, p95 19.10 ms to 11.76 ms, identical output bytes) and the device numbers at `reports/52-release-closure.md` §4 |
| Residual risk | the thread count is capped to leave cores for capture and rendering; the cap is a policy value |

### 4.8 A Java encoder is never a trusted rate controller, so its input was dropped

| Field | Content |
|---|---|
| Symptom | The frame rate stayed near 20 fps although the capture requested 30 fps, with no network congestion |
| Log signature | `encoder_perf … in_fps=17–23` in `native*.log` while the requested rate was 30, and receiver-side `WebRTC.Video.DroppedFrames.Receiver` / `.Capturer` counters in `webrtc*.log` |
| Root cause | the native wrapper that adapts a Java encoder never sets the trusted-rate-controller flag (upstream `third_party/libwebrtc-src/sdk/android/src/jni/video_encoder_wrapper.cc:124-140`), so the stream encoder keeps its frame dropper enabled for every Java encoder and drops input frames to satisfy the rate (`third_party/libwebrtc-src/video/video_stream_encoder.cc:2016-2019`) |
| Fix | the frame dropper is disabled through the field trial `WebRTC-FrameDropper/Disabled`, applied at `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164`; degradation then moves to resolution and bitrate instead of dropping frames |
| Evidence | the source chain and the pre-fix device counts are in `reports/51-frame-dropper-and-trusted-rc.md` §2 and §3; the fix is confirmed by `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/` on both peers and by `encoder_perf in_fps=30` afterwards (`reports/52-release-closure.md` §4) |
| Residual risk | the literal `Drop Frame:` is a libwebrtc source string (`third_party/libwebrtc-src/video/video_stream_encoder.cc:2022`) that was observed on a device in the pre-fix capture recorded by `reports/27-encoder-direction-perf.md` §3.1 (812 occurrences) and is **absent** from the current seven-device capture set; a low frame rate under packet loss is a receiver-side effect (`reports/50-quality-scaling-and-render-fps.md` §3 and §4), not a regression of this fix |

### 4.9 The encoder could not keep up and there was no fallback

| Field | Content |
|---|---|
| Symptom | When the custom encoder failed to keep up, the call stayed bad; there was no way to switch to the platform encoder without restarting the call |
| Log signature | `encoder_perf` windows with a low input frame rate and a high worst-case encode time, with no switching decision in the log |
| Root cause | no policy existed to hand the stream over to the platform encoder, and no decision log made the situation diagnosable (`reports/48-encoder-fallback.md` §1) |
| Fix | a policy plus a controller switches the encoder inside a live call, with hysteresis and at most one switch per round; a switch is not confirmed within 5 s then takes effect on the next call; the diagnostics screen can force either state (`reports/48-encoder-fallback.md` §2.3) |
| Evidence | `reports/48-encoder-fallback.md` §6 (2 test classes, 15 cases) and §7.3 (reverting the criteria makes them red) |
| Residual risk | the trigger rate on a real low-end phone is not measured, see doc/design/09-verification-and-limitations.md §4 |

## 5. Module: rendering and the call UI state machine

### 5.1 The local preview went black after leaving and re-entering the app

| Field | Content |
|---|---|
| Symptom | After switching to another app and back, the local preview was black even though encoding continued |
| Log signature | no surface lifecycle event around the transition, and no renderer re-attachment after the resume |
| Root cause | the screen had no foreground/background handling at all, the sink was attached only when a key changed so the recovery path never re-attached it, and the only detach point was irreversible (`reports/16-foreground-black-preview.md` §2) |
| Fix | an idempotent recovery path re-attaches the sink and restarts capture as needed, and renderer instances are no longer re-created on resume (`reports/16-foreground-black-preview.md` §3) |
| Evidence | `reports/16-foreground-black-preview.md` §1 (device log) and §3.2 (the changed files) |
| Residual risk | the camera policy can still deny a restart on some devices, which is logged as a camera error rather than hidden |

### 5.2 The renderer was suspected as the stutter cause and was cleared

| Field | Content |
|---|---|
| Symptom | Stutter was attributed to the renderer, which would have justified a change in the rendering path |
| Log signature | receiver-side rendering metrics showing **zero** dropped frames and a render cost of 0.5 to 1.4 ms while the stream itself was arriving slowly |
| Root cause | not a renderer defect: the loss was on the receive/decode side, and the sender frame rate was limited by the rate/congestion policy (`reports/50-quality-scaling-and-render-fps.md` §3 and §4) |
| Fix | no renderer change; the render pipeline is left as the reaching-frames-are-rendered path, and the conclusion is recorded so the attribution is not repeated |
| Evidence | `reports/50-quality-scaling-and-render-fps.md` §3 (drop counters and per-frame cost) and `reports/52-release-closure.md` §4 |
| Residual risk | the receiver-side loss itself remains a network property; the measured loss rate reached 18 percent on one peer |

## 6. Module: build, release and diagnostics integrity

### 6.1 The delivered jar was missing the generated JNI binding classes

| Field | Content |
|---|---|
| Symptom | The app crashed with `NoClassDefFoundError` as soon as the call screen loaded, although compilation, packaging and unit tests had all passed |
| Log signature | a class-not-found error naming a generated binding class, thrown at first use |
| Root cause | the jar was assembled from a direct-dependency-only classpath, so the generated binding classes, which are a transitive input, were systematically excluded (`reports/99-final-report.md` §11.4, item D-1) |
| Fix | the binding classes are added explicitly and the generated dispatcher is synthesised, with a class-existence check in the build (`reports/99-final-report.md` §13.24) |
| Evidence | `reports/99-final-report.md` §12.4 (why static verification missed it) and §13.24 (the repaired artifact and its verification) |
| Residual risk | the static pipeline cannot prove a runtime dependency exists; the class-existence check is the guard |

### 6.2 The native libraries were not aligned for 16 KB pages

| Field | Content |
|---|---|
| Symptom | On a device with 16 KB memory pages the library failed to load, independently of the missing-class defect |
| Log signature | a library-load failure at start-up |
| Root cause | the default maximum page size of the toolchain produced load segments aligned to 4 KB (`reports/99-final-report.md` §11.4, item D-2) |
| Fix | the app libraries are linked with a 16 KB maximum page size and the shared C++ runtime is rebuilt and packaged for the same alignment (`reports/99-final-report.md` §11.4 and §13.26) |
| Evidence | the alignment of every load segment inside the packaged APK is recorded in `reports/42-delivery-verification.md` §1.3 |
| Residual risk | a future toolchain change can reintroduce the default; the alignment check belongs in the release chain |

### 6.3 The repository deployment unit did not match the running service

| Field | Content |
|---|---|
| Symptom | Re-deploying from the repository would have silently reverted the room grace period to the old behaviour |
| Log signature | the service started without the grace flag and reported a different grace value in its health output |
| Root cause | the unit file in the repository lacked the grace flag that the live unit had (`reports/43-deploy-unit-consistency.md` §1) |
| Fix | the flag was added to `deploy/signaling.service` so the repository unit and the live unit are byte-identical in their effective directives (`reports/43-deploy-unit-consistency.md` §2) |
| Evidence | the three comparison outputs (effective directives, full `ExecStart` line, raw diff) are in `reports/43-deploy-unit-consistency.md` §3.2, and the loud-failure behaviour of the old binary is measured in §4.2 |
| Residual risk | the download and publish surface is host-side and stays outside the repository (`reports/99-final-report.md` §11.4, item D-6) |

### 6.4 The APK is not byte-reproducible

| Field | Content |
|---|---|
| Symptom | Two builds of the same source produced different APK hashes, which weakens hash-based release identification |
| Log signature | not a log item; observed as differing digests across builds with identical inputs |
| Root cause | the dex partitioning is not stable across builds (`reports/52-release-closure.md` §5) |
| Fix | none: treated as a known limitation; identity is always the recorded digest plus size, never "the same build" |
| Evidence | `reports/52-release-closure.md` §1 (the release anchor with hash and byte size) and `reports/42-delivery-verification.md` §1 |
| Residual risk | a rebuild cannot be proven identical; the release chain must publish the digest it built |

## 7. Module: tooling and environment

### 7.1 The documentation gate's path discipline inverted between revisions

| Field | Content |
|---|---|
| Symptom | A citation spelling that the gate accepted on one revision was rejected on the next, in both directions, so a green run proved only that the document matched the revision that produced it |
| Log signature | no log; observed as exit code 0 with an empty finding list for probes on one revision and non-zero exit with a marker finding for the same probe text on another |
| Root cause | the rule itself changed, not merely its implementation. The spelling required for a workspace-path citation went through four states: a tag prefix was required in a pre-commit draft revision, then treated as a hard failure, then tolerated as a hint on auto-classified paths, and finally restored to a hard failure in the frozen revision (`reports/55-captain-ruling-path-notation.md` §3). The reason was that the specification stated two severities for the same rule — its tag rules and the V8 rule required a failure, while the P5 row of its scope section and the V13 error column read as a hint — so each re-implementation followed a different sentence of the same document (`reports/55-captain-ruling-path-notation.md` §3). The captain ruled for the failure reading, and the specification wording was aligned to it (`reports/55-captain-ruling-path-notation.md` §2) |
| Fix | the path classification was consolidated to shape-based classes with an explicit scope for host paths, and the specification's two severity statements were aligned to one. This document set was written in the spelling that is accepted in every state observed — bare repository-relative paths for tracked files, bare paths for workspace evidence and build products, and an explicit host scope for host paths |
| Evidence | the four-state history and its root cause are recorded in `reports/55-captain-ruling-path-notation.md` §3, and the voided rulings in §2; the path-tag probes are recorded outside the repository under `/data/dsh/home/workspace/tmp/verifier-recon/`; the amendment to this document is registered in `reports/55-captain-ruling-path-notation.md` §9; the digest binding required by this entry is stated in doc/design/09-verification-and-limitations.md §2 |
| Residual risk | the gate is not byte-stable across revisions, so a verdict is meaningful only when it names the digest that produced it. The frozen revision is recomputable while it is committed: `git show 8fdb222:scripts/doc-verify.sh` yields the pinned digest, and the same content was carried by `HEAD` at the time of writing. The revisions that predate the first commit of the checker scripts cannot be recovered or re-run, which is why every verdict must name the digest of a committed revision rather than a working-tree state |
| Status | `known limitation` (the writing rule is settled and this document set satisfies it under every measured revision; the gate remains revision-sensitive, so each verdict is bound to the digest recorded in doc/design/09-verification-and-limitations.md §2) |

### 7.2 The specification's own example fence taught the retired spelling

| Field | Content |
|---|---|
| Symptom | A reader who followed the example block in the specification's path-vocabulary section would write exactly the spelling that the same specification's rules make a failure |
| Log signature | no log; measured as a gate failure on a document that copies the fence verbatim (`retired marker used as a path prefix`) |
| Root cause | the example fence was rewritten during a revision that made the marker prefixes mandatory, which inverted its polarity: its "correct" lines became the tagged forms and its "wrong" lines became the unmarked forms, the opposite of the rule set the same file states. The defect was introduced in that rewrite rather than inherited: the earlier specification text at `git show e093e8f:doc/design/SPEC.md` labels the bare forms as correct and the unmarked host path as wrong |
| Fix | the specification was restored from its earlier text and its wording aligned, so the example polarity matches the rules again (`reports/55-captain-ruling-path-notation.md` §9) |
| Evidence | the comparison between the current file and the earlier text at `e093e8f` is registered in `reports/55-captain-ruling-path-notation.md` §9; the fence's two "correct" lines were the two failures reported against the specification itself |
| Residual risk | an example block is not checked for consistency with the rules stated beside it, so a future rewrite can invert it again without any gate noticing |
| Status | `implemented` (the specification text was restored and realigned; the class of defect — an example that contradicts its own rule set — is recorded here so a later revision does not repeat it) |

## 8. Rejected and disproven approaches

This section records paths that were argued for and not taken, or that were tested and shown false. Each entry
states the assumption, the evidence that killed it, and what replaced it. A rejected approach that is not
recorded here tends to be re-proposed.

| # | Approach or assumption | Status | Evidence | Replaced by |
|---|---|---|---|---|
| R-1 | Bake the rotation into the I420 pixels inside the encoder | `rejected` | the follower saw a rotated picture: the input frames already arrive in display orientation, so baking rotates them twice (`reports/27-encoder-direction-perf.md` §2.2 and §2.3) | pass the rotation through (`app/src/main/cpp/encoder/vp9_encoder.cpp:65`) |
| R-2 | "The first frame is deferred by libwebrtc, so later frames are dropped" as the explanation of the encoder stall | `disproven` | the upstream logic was read and the stall was reproduced with frame-level markers; the real cause was the buffer-capacity frame length (`reports/18-encoder-stall.md` §3, `reports/27-encoder-direction-perf.md` §3.2) | the exact-capacity buffer fix |
| R-3 | Change the encoding size through the encoder configuration-update call | `rejected` | the call reported success and the next frame killed the process (`reports/20-encode-resize-crash.md` §2) | destroy and re-create the codec context, then force a key frame |
| R-4 | Reduce internal frame copies by wrapping the caller's planes instead of copying | `rejected` | the wrapped image has external ownership and a non-standard chroma stride; the first frame still crashed (`reports/22-encode-selfowned-image.md` §2) | a library-owned image with a row-by-row copy |
| R-5 | Reject the custom encoder at start-up when the CPU lacks SVE2 or dot-product support | `rejected` | the guard was diagnostic only: the real defect was the build flag, and rebuilding the library with runtime CPU detection removed the SIGILL entirely (`reports/26-libvpx-runtime-cpu-detect.md` §1 and §4) | rebuild `libvpx` with runtime CPU detection; keep the probe as a diagnostic (`app/src/main/cpp/encoder/vp9_encoder.cpp:84-90`) |
| R-6 | Disable runtime CPU detection to reduce per-frame overhead | `rejected` | it bound the dispatch table to SVE at build time and killed every call on a device without SVE (`reports/25-encoder-vpx-encode-crash.md` §4) | enable runtime CPU detection (`reports/26-libvpx-runtime-cpu-detect.md` §2) |
| R-7 | Treat "rotation is already baked into the I420 pixels" as a property of the capture path | `disproven` | the upstream frame buffer carries no rotation and the drawer applies the rotation at draw time (`reports/99-final-report.md` §14, item D-4) | the pass-through model recorded in §4.5 |
| R-8 | Recover a silent offline peer through the full ICE restart on every transition | `rejected` | the capability is not available in the current runtime, so the acceptance could only cover signaling recovery (`reports/99-final-report.md` §0.3 and §8.5) | keep the call up and rejoin; record the limitation |
| R-9 | Allow both roles to renegotiate while reconnecting | `rejected` | the two sides could send offers simultaneously, which stalls the renegotiation (`reports/40-glare-ice-restart-fix.md` §1) | only the peer-joined side initiates; the host keeps the offer duty |
| R-10 | Treat the loopback deny list in the TURN server as too strict and relax it | `rejected` | the deny entries are a deliberate boundary: loopback candidates must be filtered by the client, and requesting a permission for a loopback address was the defect (`reports/28-turn-permission-403.md` §2.3) | filter loopback candidates on both client sides (`reports/30-ice-relay-robustness.md` §2) |
| R-11 | Use the sender-side rate as the connectivity-liveness signal | `rejected` | it cannot prove that anything arrived, and it produced both false "connected" and false "failed" states (`reports/31-ui-liveness-a7.md` §1.1, `reports/36-call-survivability.md` §2.3) | candidate-pair state plus remote frame or downlink-rate evidence |
| R-12 | Explain the low frame rate by the capture rate or by the renderer | `disproven` | capture reported zero dropped frames and the renderer dropped zero frames while the stream arrived slowly; the sender was dropping input frames (`reports/50-quality-scaling-and-render-fps.md` §3 and §4, `reports/51-frame-dropper-and-trusted-rc.md` §2) | disable the frame dropper and let resolution and bitrate absorb congestion |
| R-13 | Mark a Java encoder as a trusted rate controller | `rejected` | this libwebrtc revision exposes no entry point for it from the Java encoder path (`reports/51-frame-dropper-and-trusted-rc.md` §4) | disable the frame dropper through the field trial |
| R-14 | Give the turn server its credentials through a short-lived REST scheme as the immediate fix | `rejected` (deferred) | the exposure was measured and the user accepted the risk; the short-lived scheme is the recommended hardening if a trigger condition appears (`reports/45-turn-exposure-accepted-risk.md` §4 and §5) | accept the risk and record the triggers, see doc/design/09-verification-and-limitations.md §3 |
| R-15 | Satisfy a path tag with a word that happens to appear in the sentence | `rejected` | an A/B pair whose probes differ by one word produced different verdicts from the same checker, which made the gate's verdict ambiguous | shape-based path classification with an explicit host scope, bound to the checker digest |

## 9. Evidence index

| Claim or entry | Citation | Verification artifact |
|---|---|---|
| 2.1 joiner never answered | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` | `reports/15-connection-defect.md` §2 |
| 2.2 answer before connection | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140` | `reports/23-session-lifecycle.md` §1 and §3 |
| 2.3 candidate accounting | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1186` | `reports/46-remote-candidate-counting.md` §4 |
| 2.4 room grace | `signaling/config/config.go:54`, `signaling/room/room.go:66`, `signaling/server/ws_handler.go:512` | `reports/35-room-grace.md` §3 and §4 |
| 2.5 reconnect budget | `reports/39-reconnect-budget-ice-restart.md` §2.1 | `reports/39-reconnect-budget-ice-restart.md` §4 |
| 2.6 early remote messages | `reports/21-remote-message-race.md` §3.1 | `reports/21-remote-message-race.md` §1.3 and §5 |
| 2.7 survivability | `reports/36-call-survivability.md` §2 | `reports/36-call-survivability.md` §5 |
| 2.8 log level filter | `app/src/main/kotlin/com/example/webrtcdemo/log/LogLevel.kt:53` | `reports/15-connection-defect.md` §4 |
| 2.9 ICE restart order | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272` | `reports/40-glare-ice-restart-fix.md` §4 |
| 3.1 STUN byte order | `app/src/main/cpp/nat/stun_address.h` | `reports/24-nat-address-endianness.md` §7.1 |
| 3.2 candidate parsing | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/IceCandidateInfo.kt:61` | `reports/19-ice-candidate-parse.md` §验证 |
| 3.3 turn permission | `reports/28-turn-permission-403.md` §2 and §5 | `reports/28-turn-permission-403.md` §2.2 |
| 3.4 watchdog | `reports/44-ice-watchdog-false-failure.md` §2 | `reports/44-ice-watchdog-false-failure.md` §4 |
| 3.5 connection state UI | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt` | `reports/29-connect-state-ui.md` §5, `reports/31-ui-liveness-a7.md` §1.2 |
| 3.6 frame liveness | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt` | `reports/32-remote-frame-liveness.md` §3 |
| 3.7 waiting peer | `reports/33-waiting-peer-no-retry.md` §2.1 | `reports/33-waiting-peer-no-retry.md` §5 |
| 3.8 TURN over TCP | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt` | `reports/34-turn-tcp-fallback.md` §5 |
| 4.1 SVE SIGILL | `third_party/libwebrtc-src/video/video_stream_encoder.cc:2022` | `reports/25-encoder-vpx-encode-crash.md` §1, `reports/26-libvpx-runtime-cpu-detect.md` §4 |
| 4.2 size-change crash | `app/src/main/cpp/encoder/vp9_encoder.cpp:616` | `reports/20-encode-resize-crash.md` §1 |
| 4.3 owned image | `app/src/main/cpp/encoder/vp9_encoder.cpp:186` | `reports/22-encode-selfowned-image.md` §3 |
| 4.4 frame length | `reports/27-encoder-direction-perf.md` §3.2 | `reports/27-encoder-direction-perf.md` §3.1 |
| 4.5 rotation pass-through | `app/src/main/cpp/encoder/vp9_encoder.cpp:65` | `reports/27-encoder-direction-perf.md` §2.1 |
| 4.6 bitrate floor | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:367` | `reports/49-bitrate-allocation-collapse.md` §1 and §3 |
| 4.7 encode performance | `app/src/main/cpp/encoder/vp9_encoder.cpp:906` | `reports/47-vp9-encode-perf.md` §4.1 |
| 4.8 frame dropper | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164` | `reports/51-frame-dropper-and-trusted-rc.md` §3, `reports/52-release-closure.md` §4 |
| 4.9 encoder fallback | `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:618` | `reports/48-encoder-fallback.md` §6 |
| 5.1 black preview | `reports/16-foreground-black-preview.md` §3 | `reports/16-foreground-black-preview.md` §1 |
| 5.2 renderer cleared | `reports/50-quality-scaling-and-render-fps.md` §3 | `reports/50-quality-scaling-and-render-fps.md` §3 and §4 |
| 6.1 missing binding classes | `reports/99-final-report.md` §11.4 item D-1 | `reports/99-final-report.md` §12.4 and §13.24 |
| 6.2 16 KB pages | `reports/99-final-report.md` §11.4 item D-2 | `reports/42-delivery-verification.md` §1.3 |
| 6.3 deployment unit | `deploy/signaling.service` | `reports/43-deploy-unit-consistency.md` §3.2 |
| 6.4 APK reproducibility | `reports/52-release-closure.md` §1 | `reports/99-final-report.md` §0.2 |
| 7.1 gate discipline | `scripts/doc-verify.sh` | `reports/55-captain-ruling-path-notation.md` §2 and §3, doc/design/09-verification-and-limitations.md §2 |
| 7.2 specification example fence | `reports/55-captain-ruling-path-notation.md` §9 | the same section, and the `e093e8f` text it cites |
| R-1 .. R-15 rejected or disproven approaches | the reports named in each row | the same reports |

## 10. Open items

| # | Item | Why it is open | How to close it |
|---|---|---|---|
| I-1 | ICE restart has never been exercised on a device | the restart keys are absent from every file of the current device-log set (for example `../tmp/n6/x/app.log:6170`); only the signaling path was verified | force a reconnect long enough to trigger the restart and confirm the restart request in the log |
| I-2 | Long-outage recovery beyond the server grace period | no session was held open across the grace period | hold a call, block the radio for longer than 90 s, and observe the recovery path |
| I-3 | Weak-network limits (above roughly 20 percent loss) | no capture reached that regime | run a call under an emulated loss profile and record the frame rate and loss together |
| I-4 | The 8 s fallback trigger rate | the fallback has no observed firing | count the fallback event over several reconnects |
| I-5 | The encoder fallback on a genuinely low-end device | the test devices are not low-end | repeat the capture on a low-end phone and read the fallback decision keys |
| I-6 | Default encoder versus custom encoder under the same packet loss | the two paths were never compared under identical loss | run both paths back to back on one link and compare the frame rate |
| I-7 | Every gate verdict is valid only against the digest that produced it | the checker was revised repeatedly while the document set was written, so a verdict copied from another revision is void | cite `sha256` of the checker with every gate statement, as done in doc/design/09-verification-and-limitations.md §2; the frozen revision is recomputable via `git show 8fdb222:scripts/doc-verify.sh` |
