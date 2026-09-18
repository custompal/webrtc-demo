> [中文（默认）](zh-CN/01-requirements.md) · English

# 01 — Requirements

> Status: draft · Owner: architect · Task: t1
> Evidence base: `reports/52-release-closure.md`, `reports/99-final-report.md` §15, `reports/35-room-grace.md`,
> `reports/42-delivery-verification.md`
> Doc standard: `doc/design/SPEC.md`

---

## 1. Scope

This document states what the delivered system must do, where each requirement is implemented, and what
evidence exists for it. "Delivered" means the release anchored at APK
`59c75778fb457e8c6d4c7955b349dfa4fde784fa616e852419e3b007e1e41d07` (33 472 645 B), built from commit
`4130ddc` (`reports/52-release-closure.md` §1).

Out of scope: internal WebRTC behaviour (ICE/DTLS/GCC/NACK are used, not reimplemented), the legacy design
documents under `doc/archive/`, and anything already registered as errata (summarised in §7).

Status vocabulary is fixed by `doc/design/SPEC.md` §6 (E4): `implemented`, `partially implemented`,
`known limitation`, `unverified`.

Citation key: every code pointer below is a `file:`-style citation (`file:LINE`), so the
requirement → implementation → evidence chain is machine-checkable by `scripts/doc-verify.sh`.

## 2. Functional requirements

| ID | Requirement | Status |
|---|---|---|
| FR-1 | A caller can create a 1:1 room and gets a short room id plus ICE credentials | implemented |
| FR-2 | A second peer can join with that room id; the room holds at most 2 peers | implemented |
| FR-3 | Both peers exchange SDP and ICE candidates over the signaling channel and establish audio+video | implemented |
| FR-4 | A short signaling disconnection (< grace period) must not end the call; the peer rejoins and reclaims its seat | implemented |
| FR-5 | When the remote peer leaves (or its grace period expires) the local side returns to a waiting state instead of hanging up | implemented |
| FR-6 | When the room is gone (`ROOM_NOT_FOUND`/`ROOM_EXPIRED`) the call stays recoverable instead of being torn down | implemented |
| FR-7 | The user can force media through TURN from the diagnostics page | implemented |
| FR-8 | The user can export diagnostics (log zip) from the app | implemented |

### FR-1 — Create a room

* **Statement.** The host sends `create` over the WebSocket; the server answers `created` with a 6-character
  room id and the STUN/TURN configuration it wants the client to use.
* **Implementation.** Wire format `signaling/protocol/message.go:46` (`CreateRequest`) and
  `signaling/protocol/message.go:51` (`CreatedResponse`); room id generation
  `signaling/util/roomid.go` (`Generate`); room creation `signaling/room/manager.go:117`; client request
  `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:858` region (`createRoom`).
  Room ids use the confusion-free alphabet `ABCDEFGHJKMNPQRSTUVWXYZ23456789`
  (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:172`).
* **Evidence.** `signaling/room/manager_test.go`; `signaling/server/e2e_test.go` (end-to-end create/join);
  live replay in `reports/99-final-report.md` §15.4 (`create` → `roomId=SA6ABK`).
* **Status.** implemented.

### FR-2 — Join a room, at most two peers

* **Statement.** A second peer sends `join{roomId}`; the server answers `joined` with its assigned `peerId`
  (`peer-001`/`peer-002`) or an `error` when the room is unknown or full.
* **Implementation.** `JoinRequest` `signaling/protocol/message.go:61`, `JoinedResponse`
  `signaling/protocol/message.go:67`; capacity constant `signaling/room/room.go:13` (`RoomMaxPeers = 2`);
  join path `signaling/room/manager.go:161`; slot assignment `signaling/room/room.go:102`
  (`peerIDAt`); error codes `signaling/protocol/errors.go:6-11`.
* **Evidence.** `signaling/room/manager_test.go`; `signaling/server/e2e_test.go`;
  `reports/99-final-report.md` §15.4 (`join peerId=peer-002`, online peer receives `peerJoined`).
* **Status.** implemented.

### FR-3 — Bidirectional audio and video

* **Statement.** The two peers negotiate one audio and one video track and exchange media until hang-up.
* **Implementation.** Session start and track wiring
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:277` (`start`); offer creation
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:402`; remote offer
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:454`; remote answer
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:542`; remote ICE
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:588`; codec preference VP9
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1427`; custom encoder factory
  `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt`;
  renderer pool `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt`.
* **Evidence.** `reports/52-release-closure.md` §4 (real-device call: `encoder_perf in_fps` p50 30,
  `eglrenderer Dropped=0`, up ≈1.0–1.16 Mbps); unit tests `app/src/test/kotlin/com/example/webrtcdemo/webrtc/SessionLifecycleTest.kt`.
  The JNI boundary inside this path is contract-checked by `doc/design/_generated/jni-contract.md`, generated
  from the Kotlin `external fun` declarations (`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/**`),
  the `JNINativeMethod` tables (`app/src/main/cpp/jni/nat_detector_jni.cpp:49`,
  `app/src/main/cpp/jni/native_log_jni.cpp:71`, `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` — 15 entries)
  and the C prototypes in `app/src/main/cpp/jni/*.h`. The binding is `JNI_OnLoad` + `RegisterNatives`
  (`app/src/main/cpp/jni/jni_bridge.h:9`), so there are no `Java_*` exported symbols and no project-generated
  `*_jni.h`; both absences are expected (`doc/design/SPEC.md` §2.1).
* **Status.** implemented.

### FR-4 — Survive a short signaling disconnection

* **Statement.** If the WebSocket drops and the client reconnects within the grace period, the call continues:
  the room and the seat are kept, no `peerLeft` is sent, the peer reclaims its `peerId`, and media is
  re-established (ICE restart when needed) without the user hanging up.
* **Implementation.** Server grace period default `signaling/config/config.go:54` (`DefaultRoomGrace = 90 s`)
  wired from the `-room-grace` flag `signaling/main.go:45`; offline marking
  `signaling/room/manager.go:225` (`MarkOffline`), seat pending state `signaling/room/room.go:113`
  (`MarkPending`), grace expiry `signaling/room/manager.go:258` (`expireGrace`), takeover
  `signaling/room/room.go:66` (`AddPeer` returns `takenOver`); client pong tolerance
  `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`
  (`PONG_MISS_TOLERANCE = 4`), reconnect budget
  `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`
  (`MAX_REJOIN_ATTEMPTS = 10`), backoff `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:156` (`rejoinDelayMs`);
  ICE restart decision `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:127`
  (`shouldRestartIceOnRejoin`).
* **Evidence.** `reports/99-final-report.md` §15.4 (hard socket close: online peer saw `peerLeft` count 0
  within 12 s; same-identity rejoin got `joined` with the same `peerId`); budget test
  `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt:50` (63 000 ms) and
  `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt:52` (< 75 000 ms); `reports/35-room-grace.md`, `reports/39-reconnect-budget-ice-restart.md`.
* **Status.** implemented, with an unverified margin: the real-device behaviour of a 30–90 s outage and the
  8 s offer fallback are not measured (`reports/52-release-closure.md` §5, item 6).

### FR-5 — Remote peer leaves → waiting state

* **Statement.** After the remote peer leaves (explicit `leave`, or its grace period ended), the local client
  must keep the call page, stop the retry clock and return to a waiting state, so the pair can call again.
* **Implementation.** Peer-left decision `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:76`
  (`peerLeftAction`, keep the call when the generation ever connected); waiting state
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:448` (phase `waiting_peer`, no timer,
  no retry prompt); clock reset `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:903` (`resetRetryClock("peer_left")`);
  keep-call branch `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:914` (`peer_left action=keep_call`); waiting state is not treated as a
  retry/failure condition `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:460`.
* **Evidence.** `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt`;
  `reports/33-waiting-peer-no-retry.md`; `reports/36-call-survivability.md`.
* **Status.** implemented.

### FR-6 — Room gone → recoverable state

* **Statement.** `ROOM_NOT_FOUND` (and `ROOM_EXPIRED`) must not automatically end the call: while in a call
  or while media is still flowing, the client stays on the call page and offers an explicit "recreate room"
  entry point.
* **Implementation.** Decision `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:88`
  (`roomLostAction`); keep-call branch with the `room_not_found action=keep_call` log
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1052-1064`; signal-lost decision
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:105` (`signalLostAction`) and its branch
  `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:757-772`.
* **Evidence.** `app/src/test/kotlin/com/example/webrtcdemo/signaling/SignalingErrorPolicyTest.kt`;
  `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt`;
  `reports/52-release-closure.md` §4 lists `room_not_found_action=keep_call` as confirmed design behaviour.
* **Status.** implemented.

### FR-7 — Forced relay (diagnostics toggle)

* **Statement.** The user can force all media through TURN; the setting persists and is applied to every new
  session.
* **Implementation.** Policy constant `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:69`
  (`ICE_POLICY_RELAY`) and getter `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:162` (`forceRelay`); mapped to
  `PeerConnection.IceTransportsType.RELAY` in `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:156`;
  applied when starting a session `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:561`;
  re-applied on ICE restart `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1294-1295`.
* **Evidence.** `app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt`;
  `reports/30-ice-relay-robustness.md`; `reports/52-release-closure.md` §4 (call observed over `RELAY`).
* **Status.** implemented.

### FR-8 — Diagnostics export

* **Statement.** The user can export the app logs as a zip and share it; the export contains device info and a
  session summary and keeps at most 3 archives.
* **Implementation.** `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:139`
  (`exportBlocking`); zip name prefix `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:60` (`ZIP_PREFIX`), device info entry
  `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:66`, keep-at-most `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:72` (`MAX_EXPORTS = 3`), old-export trimming
  `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:248`; `FileProvider` authority `app/src/main/AndroidManifest.xml:67`.
* **Evidence.** `reports/07-native-dev.md` §15 (log pipeline and export); `reports/52-release-closure.md`
  §5 (log evidence was collected through this path). No unit test covers the zip content end to end.
* **Status.** implemented; the archive *content* is `unverified`.

## 3. Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-1 | Encoding latency p95 stays inside the 33 ms frame budget at 640×360 | implemented |
| NFR-2 | Frame rate tracks the requested 30 fps; the encoder never silently halves it | implemented |
| NFR-3 | Latency/RTT stays in the observed 54–217 ms range on the relay path | known limitation |
| NFR-4 | A call is stable over long runs and the grace semantics are testable | partially implemented |
| NFR-5 | Every failure is diagnosable from the exported logs | implemented |
| NFR-6 | The TURN exposure surface is a recorded, accepted risk | known limitation |
| NFR-7 | The toolchain and pinned artifacts are reproducible anchors | implemented |
| NFR-8 | The release artifact is identified by hash and can be rolled back | implemented |

### NFR-1 — Encoding latency

* **Statement.** `encode_ms_p95` must stay well below the 33 ms budget at 30 fps for 640×360.
* **Implementation.** Thread count and row-mt tuning `app/src/main/cpp/encoder/vp9_encoder.cpp`;
  rate policy `app/src/main/cpp/encoder/encoder_rate_policy.h`; bitrate floor table
  `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9BitrateLimits.kt`.
* **Evidence.** `reports/52-release-closure.md` §4: `encode_ms_p95` p50 9.8 ms, p90 15.2 ms, max 33.5 ms;
  `reports/47-vp9-encode-perf.md` (host A/B p50 15.33 → 9.34 ms, p95 19.10 → 11.76 ms, identical output bytes).
* **Status.** implemented.

### NFR-2 — Frame rate

* **Statement.** With a 30 fps source the encoder must deliver ≈30 fps; received frames must not be dropped by
  the local FrameDropper.
* **Implementation.** Field trial `app/src/main/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrial.kt`;
  quality scaling and per-frame direct-buffer sizing in `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt`.
* **Evidence.** `reports/52-release-closure.md` §4 (`DroppedFrames` counted in the webrtc logs, frame-drop field trial `field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/` in the app logs; `encoder_perf in_fps` p50 30);
  `reports/51-frame-dropper-and-trusted-rc.md`; `app/src/test/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrialTest.kt`.
* **Status.** implemented.

### NFR-3 — Latency and RTT

* **Statement.** RTT should stay low enough for conversational video.
* **Evidence.** `reports/52-release-closure.md` §4: n6 RTT 54–65 ms and loss 0; n7 RTT 128–217 ms with loss up
  to 11.8%. The spread is network-side, not a defect.
* **Status.** known limitation: both peers were behind symmetric NAT, so media always took the relay path
  (`reports/52-release-closure.md` §5 item 1). No P2P comparison exists.

### NFR-4 — Stability and grace semantics

* **Statement.** The grace period must be larger than the client reconnect budget, and the room/seat state
  machine must be deterministic and idempotent.
* **Implementation.** `DefaultRoomGrace = 90 s` (`signaling/config/config.go:54`), warning threshold
  `signaling/config/config.go:57` (`MinRecommendedRoomGrace`); idempotent expiry `signaling/room/manager.go:258`
  (`expireGrace`) and `signaling/room/room.go:140` (`ExpirePending`); deployment pins the value
  `deploy/signaling.service:28` (`-room-grace 90s`).
* **Evidence.** `signaling/room/grace_test.go`, `signaling/server/grace_test.go`;
  `reports/35-room-grace.md`, `reports/43-deploy-unit-consistency.md`;
  live `/healthz` reading `roomGraceSec = 90` (`reports/99-final-report.md` §15.4).
* **Status.** partially implemented: calls longer than ~30 minutes, heavy loss (>20%) and the 8 s offer
  fallback trigger rate were never measured on a device (`reports/52-release-closure.md` §5 item 6).

### NFR-5 — Diagnosability

* **Statement.** Every failure mode must leave a log event with enough fields to localise it; logs rotate so
  they cannot fill the device.
* **Implementation.** Rolling file logger `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:502`
  (`MAX_FILE_BYTES = 2 MiB`), `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:505` (`MAX_FILES = 3`), log directory
  `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:508` (`logs`); critical events bypass the async queue
  `app/src/main/kotlin/com/example/webrtcdemo/log/Log.kt:226` (`critical`); native log init contract
  `app/src/main/kotlin/com/example/webrtcdemo/log/Log.kt:212`; generated key list `doc/design/_generated/` (log event table).
* **Evidence.** `reports/07-native-dev.md` §15; `reports/83`-style diagnostic enhancements recorded in
  `reports/46-remote-candidate-counting.md`; the device log lines quoted in `reports/52-release-closure.md` §4.
* **Status.** implemented.

### NFR-6 — Security: TURN exposure

* **Statement.** The TURN service must require authentication and not be an open relay; residual exposure is
  accepted explicitly.
* **Implementation.** `deploy/turnserver.conf` (quotas, denied peer ranges, fingerprint setting);
  STUN/TURN handed to clients by the signaling service `signaling/protocol/message.go:54-57`.
* **Evidence.** `reports/45-turn-exposure-accepted-risk.md` (user decision (C): accept and record; 24 h
  observation with zero unauthorised allocations); `reports/28-turn-permission-403.md`.
* **Status.** known limitation, accepted by decision (C).

### NFR-7 — Reproducibility anchors

* **Statement.** Toolchain versions and pinned artifacts are fixed, so a rebuild is attributable.
* **Implementation.** Versions frozen in `app/build.gradle.kts:38` (`ndkVersion`), `:104`/`:105`
  (`VERSION_17`), `:110` (`jvmTarget = "17"`), Gradle wrapper
  `gradle/wrapper/gradle-wrapper.properties` (8.7), Go toolchain `signaling/go.mod:3` (go 1.22).
* **Evidence.** `reports/52-release-closure.md` §1 (anchor + unit-test counts);
  `reports/99-final-report.md` §15.1 (`jar 0c776934…`, `aar 8e8f2baf…`, `libjingle 757cef81…`,
  `libc++_shared c9dbf4ec…` unchanged T0 = T2); `reports/04-env-install.md`.
* **Status.** implemented. APK bytes are **not** reproducible (D8 dex partitioning) —
  `reports/52-release-closure.md` §5 item 2.

### NFR-8 — Release artifact identification and rollback

* **Statement.** The published APK is identified by sha256; the publish chain refuses to overwrite a
  different artifact with the same name, and a rollback command is printed.
* **Implementation.** Publish script `HOST: /opt/apk-http/publish_apk.sh` (host-side, outside the repository,
  errata D-6); the `SOURCE.sha256` marker is written last as the commit point. **The script itself is not
  visible from the container**, so every statement about its internals is `unverified (host script)`; what is
  verified is the observable release surface quoted below.
* **Evidence.** `reports/52-release-closure.md` §1 (anchor, size, download URL), §6 (rollback command and the
  anchor list); `reports/99-final-report.md` §15.1 (public `HEAD` 200, `Range` 206 with matching sha256);
  `reports/37-t76-build-publish.md` §7 (identity guard: `exit 3` without `--allow-root`, `--rehearsal` path
  leaves the served file untouched). Command/flag names are inventoried in
  `doc/design/_generated/host-commands.md`, and every host-only command cited here carries this
  repository-readable evidence in the same statement (`doc/design/SPEC.md` §7 A9).
* **Status.** implemented; the publish script body and the host `parts/` directory are `unverified`.

## 4. Known limitations

The product limitations are stated per requirement (NFR-3, NFR-4, NFR-6). The **tooling** limitations below
also affect how a reader can reproduce the delivery, and each is registered as an open item in §6:

| # | Limitation | Evidence | Impact | Improvement (not done in this phase) |
|---|---|---|---|---|
| L-1 | `scripts/build_app.sh` is host-only and cannot run in the container | it hard-codes `WS=/opt/dsh-workspaces` at `scripts/build_app.sh:33` and sources `. "$WS/env.sh"` at `scripts/build_app.sh:62` | a reader without the host cannot execute the documented full sequence; only the Gradle steps are portable | accept a `WS` environment override so the script resolves the workspace portably (O-6) |
| L-2 | The publish step has no in-repo implementation | there is no `publish_apk.sh` in this repository; it is `HOST: /opt/apk-http/publish_apk.sh`, and `reports/10-app-build.md:1093` states the publish step never belonged to the build script | the release cannot be reproduced from the repository alone (errata D-6) | move a portable publish script into the repository, or keep per-release report evidence (O-7) |
| L-3 | `HOST`-scoped paths are unverifiable from the container | `doc/design/SPEC.md` §2.5; `HOST: /opt/signaling`, `HOST: /etc/systemd/system/signaling.service` | live-service facts rest on report evidence rather than first-hand inspection | re-run the host checks from a session that can reach the host |
| L-4 | Device-level items remain unmeasured | `reports/52-release-closure.md` §5 item 6 | long-call stability, weak-network limits and the 8 s fallback rate are unknown | see O-1 and O-3 |

## 5. Requirement-to-test map

| Requirement | Primary verification |
|---|---|
| FR-1, FR-2 | `signaling/room/manager_test.go`, `signaling/server/e2e_test.go`, `reports/99-final-report.md` §15.4 |
| FR-3 | `app/src/test/kotlin/com/example/webrtcdemo/webrtc/SessionLifecycleTest.kt`, `reports/52-release-closure.md` §4 |
| FR-4 | `signaling/room/grace_test.go`, `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt`, `reports/99-final-report.md` §15.4 |
| FR-5 | `app/src/test/kotlin/com/example/webrtcdemo/ui/call/CallSurvivabilityTest.kt`, `reports/33-waiting-peer-no-retry.md` |
| FR-6 | `app/src/test/kotlin/com/example/webrtcdemo/signaling/SignalingErrorPolicyTest.kt` |
| FR-7 | `app/src/test/kotlin/com/example/webrtcdemo/webrtc/TurnTcpFallbackTest.kt`, `reports/30-ice-relay-robustness.md` |
| FR-8 | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:139` (no automated test) |
| NFR-1 | `reports/47-vp9-encode-perf.md`, `reports/52-release-closure.md` §4 |
| NFR-2 | `app/src/test/kotlin/com/example/webrtcdemo/webrtc/FrameDropperFieldTrialTest.kt`, `reports/51-frame-dropper-and-trusted-rc.md` |
| NFR-4 | `signaling/server/grace_test.go`, `reports/43-deploy-unit-consistency.md` |
| NFR-7 | `reports/99-final-report.md` §15.1 |
| NFR-7, NFR-8 | four-stage build recipe `reports/37-t76-build-publish.md` §3 (gates in §3.1), publish and four-way hash reconciliation §5, public re-verification §6 |
| NFR-4, NFR-5 | `/healthz` field checks `deploy/README.md` §Health (host-side observation; the repository copy of the unit is `deploy/signaling.service:28`) |

## 6. Open items

| # | Item | Why it is open | How to close it |
|---|---|---|---|
| O-1 | 30–90 s outage on a real device | only short drops were exercised | keep a call up and toggle the radio for 60 s; expect `KEEP_CALL` and a rejoin |
| O-2 | Offer carries `iceRestart` | verified in code (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272` and its call site `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1343`), not on the wire | capture the rejoin offer SDP |
| O-3 | 8 s fallback trigger rate | no device session long enough | instrument `offer_timeout` count over several reconnects |
| O-4 | Diagnostics zip content | `LogExporter` has no end-to-end test | inspect an exported zip on a device |
| O-5 | P2P vs relay quality | both test peers were behind symmetric NAT | repeat with a peer on full-cone NAT |
| O-6 | `scripts/build_app.sh` cannot run in the container | it hard-codes `WS=/opt/dsh-workspaces` at `scripts/build_app.sh:33` and sources `. "$WS/env.sh"` at `scripts/build_app.sh:62` (a `HOST:` script) | improvement item for a later phase: accept a `WS` environment override so the script becomes portable; no code change in the documentation phase. Container-equivalent Gradle steps are documented in `doc/design/07-build-and-deploy.md` |
| O-7 | No reproducibility from the repository alone for the publish step | publishing is a host manual procedure: there is **no** `publish_apk.sh` in this repository (`HOST: /opt/apk-http/publish_apk.sh`, `reports/10-app-build.md:1093`) | keep per-release report evidence (`reports/37-*-build-publish.md`), or move a portable publish script into the repository in a later phase |

## 7. Errata summary (D-1..D-6)

The legacy contract `doc/archive/14-interface-contract.md` diverges from the implementation in six registered
places (`reports/99-final-report.md` §15.5):

| ID | Divergence | Consequence if the legacy text is followed |
|---|---|---|
| D-1 | Legacy reconnect text says 3 s / 3 attempts; implementation uses 1/2/4/8 s ×10 = 63 s | a client would give up before the 90 s grace period and drop the call |
| D-2 | Legacy text says rotation is baked into the I420 pixels; the implementation passes rotation through (`app/src/main/cpp/encoder/vp9_encoder.cpp` flag `kBakeRotationInEncoder = false`) | a follower of the text would change pixel orientation |
| D-3 | coturn option is `fingerprint`, not `use-fingerprint`, in coturn 4.6.1 | coturn refuses to start or silently ignores the option |
| D-4 | Legacy claim "rotation already baked into I420" is disproven by upstream sources | wrong mental model of the capture path |
| D-5 | Repository `deploy/signaling.service` previously lacked `-room-grace 90s` while the live unit had it | redeploying from the repository would silently revert to the old drop behaviour |
| D-6 | Download surface (`parts/`, `SHA256SUMS`, `SOURCE.sha256`, publish script) lives on the host, outside the repository | the server side cannot be rebuilt from the repository alone |

D-1, D-2 and D-5 are now closed in code or in the repository copy; D-3 and D-6 are accepted as host
operational facts. The full statement, evidence and impact of each item is maintained in
doc/design/09-verification-and-limitations.md.
