# 10 — Code Map

> Status: draft · Owner: writer-ops · Task: t5
> Evidence base: current HEAD source tree, reports/01..52, doc/design/SPEC.md
> Doc standard: `doc/design/SPEC.md`

## 1. Scope

This document is the navigation layer: where the code lives, what each report records, which documents
exist, and how to get from an observed symptom to the code that produces it.

It deliberately does not restate behaviour: the app architecture, the signaling service and the protocol
have their own documents. Claims here are limited to file placement, the entry point declared in each file,
and report provenance.

Line numbers are valid against the current HEAD source. They are pointers, not contracts: if the code moves,
this document is updated, never the reverse.

## 2. Code map

### 2.1 Kotlin application (`app/src/main/kotlin/com/example/webrtcdemo/`)

| Path | Responsibility | Entry point |
|---|---|---|
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt` | call state machine, ICE/negotiation lifecycle, watchdog | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt` | engine singleton and peer connection factory ownership | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:43` |
| `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt` | WebSocket client for the signaling channel | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:35` |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt` | custom VP9 encoder | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59` |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt` | encoder selection and fallback wiring | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:40` |
| `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt` | falls back to the platform encoder when the custom encoder cannot keep up | `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:41` |
| `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt` | renderer attach/detach lifecycle | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:44` |
| `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt` | NAT type state exposed to the UI | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:24` |
| `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt` | call screen state holder and call lifecycle coordination | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:52` |
| `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt` | diagnostic log zip export | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:51` |
| `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt` | runtime configuration switches | `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:22` |

The remaining Kotlin packages are: `log/` (file logging), `model/` (UI and stats models), `nativebridge/`
(thin JNI declarations), `ui/` (Compose screens, navigation, theme) and `webrtc/` (peer connection helpers
such as candidate parsing, stats mapping and frame normalisation).

References: `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt`

### 2.2 Native layer (`app/src/main/cpp/`)

| Path | Responsibility | Entry point |
|---|---|---|
| `app/src/main/cpp/jni/jni_bridge.cpp` | JNI registration | `app/src/main/cpp/jni/jni_bridge.cpp:57` |
| `app/src/main/cpp/jni/jni_bridge.h` | registration contract (register-natives style) | `app/src/main/cpp/jni/jni_bridge.h:9` |
| `app/src/main/cpp/jni/vp9_encoder_jni.cpp` | JNI surface of the VP9 encoder | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:69` |
| `app/src/main/cpp/encoder/vp9_encoder.cpp` | VP9 encoder implementation | `app/src/main/cpp/encoder/vp9_encoder.cpp:77` |
| `app/src/main/cpp/nat/nat_detector.cpp` | RFC 5780 NAT detection | `app/src/main/cpp/nat/nat_detector.cpp:25` |
| `app/src/main/cpp/log/native_log.cpp` | native logging sink | `app/src/main/cpp/log/native_log.cpp:61` |

Subdirectories not listed above: `encoder/` also holds the rate policy, rotator and bitrate allocator plus
their host tests; `nat/` holds the STUN client and address handling; `tests/host/` holds host-compiled tests
with stub headers; `util/` holds JNI and thread helpers. `app/src/main/cpp/CMakeLists.txt` defines the
native library.

References: `app/src/main/cpp/jni/jni_bridge.cpp`

### 2.3 Signaling service (`signaling/`)

| Path | Responsibility | Entry point |
|---|---|---|
| `signaling/main.go` | process entry and wiring | `signaling/main.go:28` |
| `signaling/server/server.go` | HTTP/WebSocket server construction | `signaling/server/server.go:47` |
| `signaling/server/ws_handler.go` | connection handling and peer options | `signaling/server/ws_handler.go:24` |
| `signaling/room/room.go` | room and seat state | `signaling/room/room.go:49` |
| `signaling/room/manager.go` | room lifecycle and expiry | `signaling/room/manager.go:59` |
| `signaling/protocol/message.go` | message type vocabulary | `signaling/protocol/message.go:12` |
| `signaling/protocol/errors.go` | error codes | `signaling/protocol/errors.go:5` |
| `signaling/config/config.go` | configuration constants and parsing | `signaling/config/config.go:18` |
| `signaling/logging/logging.go` | logger construction | `signaling/logging/logging.go:20` |

Also present: `signaling/logging/` rotation and formatter, `signaling/util/` room id generation, and the
`_test.go` files next to the code they exercise. The compiled binary is not tracked; see §4.

References: `signaling/server/server.go`

### 2.4 Tooling and deployment

| Path | Responsibility |
|---|---|
| `scripts/build_app.sh` | host build pipeline and gates |
| `scripts/deploy_signaling.sh` | deploy the signaling binary and restart the unit |
| `scripts/doc-verify.sh` | documentation gate |
| `scripts/gen-doc-tables.sh` | generates `doc/design/_generated/**` |
| `scripts/verify_signal_e2e.mjs` | signaling end-to-end check |
| `scripts/t5-libwebrtc-libvpx-build.sh` | libwebrtc and libvpx build |
| `scripts/t56-libvpx-runtime-cpu-detect-rebuild.sh` | libvpx rebuild helper |
| `scripts/t56-libvpx-verify-runtime-cpu-detect.sh` | libvpx verification helper |
| `scripts/make-libcxx-shared-16k.sh` | C++ runtime library preparation |
| `scripts/check_jn_binding.py` | JNI binding check |
| `scripts/check_jar_link_integrity.py` | Java SDK link check |
| `scripts/build_java_sdk_with_jni.sh` | Java SDK rebuild |
| `deploy/signaling.service` | signaling systemd unit (repository copy) |
| `deploy/coturn.service`, `deploy/coturn.default` | coturn unit and defaults |
| `deploy/turnserver.conf` | coturn configuration |
| `deploy/turnperm_probe.mjs`, `deploy/turnperm_probe_tcp.mjs` | TURN permission probes |
| `deploy/README.md` | deployment reproduction notes |
| `doc/design/_generated/host-commands.md` | generated command inventory |
| `doc/design/_generated/log-events.md` | generated log event inventory |
| `doc/design/_generated/jni-contract.md` | generated JNI contract |
| `doc/design/_generated/signaling-messages.md` | generated signaling message table |

References: `scripts/build_app.sh`, `deploy/signaling.service`

## 3. Report index

Numbering follows the filenames. Several numbers have more than one document; where that happens the topic
column distinguishes them. "Referenced by" names the task the report belongs to, taken from the report's own
title.

| Report | Topic | Key point | Referenced by |
|---|---|---|---|
| 01-host-recon.md | host connectivity, resources, environment | host environment verified before setup | t1 |
| 02-interface-contract.md | interface and architecture contract | the contract the implementation is measured against | t2 |
| 02-webrtc-network-recon.md | source acquisition network bottleneck | measured bottleneck and mitigations | t5 preflight |
| 03-git-submodules.md | git repository and source submodules | libvpx and libwebrtc sources introduced | t3 |
| 04-env-install.md | Android SDK/NDK, JDK 17, cmake/ninja | host toolchain installation record | t4 |
| 05-libwebrtc-build.md | libwebrtc arm64 and libvpx build | the native build record | t5 |
| 06-coturn.md | coturn STUN/TURN deployment | deployment and verification of the relay | t6 |
| 07-native-dev.md | C++ native layer | native layer implementation record | t7 |
| 08-android-dev.md | Kotlin/Compose, signaling and JNI wrapper | app implementation record | t8 |
| 09-go-signaling.md | Go signaling service and end-to-end self-test | service implementation record | t9 |
| 10-app-build.md | APK and Go binary build on the host | delivery build and its anchor | t10 |
| 12-deploy-signaling.md | signaling systemd deployment and coturn integration | deployment verification | t12 |
| 13-device-defect-fix.md | two on-device defects | defect fixes | t39 |
| 13-go-toolchain.md | Go toolchain installation | toolchain verification | t13 |
| 14-android-skeleton.md | contract-independent Android skeleton | Gradle project and resources | pre-t14 |
| 15-connection-defect.md | on-device connectivity defect | defect fix | t15 |
| 15-java-jar-rebuild.md | libwebrtc Java SDK rebuild | Java 17 rebuild | t16/t17 |
| 16-foreground-black-preview.md | black preview after backgrounding | defect fix | t16 |
| 17-vp9-rotation.md | VP9 rotation semantics | encoder rotation completed | t46 |
| 18-encoder-stall.md | encoder emits one frame then stops | defect diagnosis | t47 |
| 19-ice-candidate-parse.md | candidate parsing field misalignment | defect fix | t49 |
| 20-encode-resize-crash.md | crash on encoder resize | defect fix | t50 |
| 21-remote-message-race.md | remote message race | defect fix | t52 |
| 22-encode-selfowned-image.md | encoder crash, self-owned image | defect fix | t50b |
| 23-session-lifecycle.md | joiner answer timing | defect fix | t53 |
| 24-nat-address-endianness.md | native STUN mapped-address byte order | defect fix | t54 |
| 25-encoder-vpx-encode-crash.md | first-frame encoder crash | defect fix | t55 |
| 26-libvpx-runtime-cpu-detect.md | arm64 libvpx runtime CPU detection | runtime dispatch restored | t56 |
| 27-encoder-direction-perf.md | encoder direction and performance | defect and performance fix | t57 |
| 28-turn-permission-403.md | relay unusable between mobile and wifi | create-permission failure diagnosed | t58 |
| 29-connect-state-ui.md | misleading UI when not connected | defect fix | t59 |
| 30-ice-relay-robustness.md | relay robustness, watchdog and loopback candidates | defect fixes | t60 |
| 31-ui-liveness-a7.md | call-screen liveness requirements | defect fixes | t61 |
| 32-remote-frame-liveness.md | remote frame liveness false alarm | defect fix | t63 |
| 33-waiting-peer-no-retry.md | retry prompt while waiting for the peer | defect fix | t64 |
| 34-turn-tcp-fallback.md | TURN over TCP fallback | client fallback added | t65 |
| 35-room-grace.md | room grace period on WebSocket drop | room survives a transient drop | t66 |
| 36-call-survivability.md | pong tolerance and signaling loss | call survivability work | t68 |
| 37-t69-build-publish.md | build and publish run | publish chain run record | t69 |
| 37-t72-build-publish.md | build and publish run | four-way reconciliation and public verification | t72 |
| 37-t76-build-publish.md | build and publish run | four-stage recipe with gate output | t76 |
| 37-t81-build-publish.md | build and publish run | identity guard evidence | t81 |
| 37-t84-build-publish.md | build and publish run | publish chain run record | t84 |
| 37-t86-build-publish.md | build and publish run | publish chain run record | t86 |
| 37-t88-build-publish.md | build and publish run | abort record | t88 |
| 37-t90-build-publish.md | build and publish run | publish chain run record | t90 |
| 37-t93-build-publish.md | build and publish run | publish chain run record | t93 |
| 38-signaling-grace-deploy.md | grace-period fix deployment | compiled and deployed on the host | t67 |
| 39-reconnect-budget-ice-restart.md | reconnect budget and ICE restart | budget aligned with the grace period | t71 |
| 40-glare-ice-restart-fix.md | ICE restart ordering and glare | offer-duty split and fallback | t75 |
| 41-apk-http-ownership.md | publish directory ownership | ownership moved to uid 1000, guard added | t77 |
| 42-delivery-verification.md | delivery verification | independent verification of an anchor | t78 |
| 43-deploy-unit-consistency.md | repository unit vs live host unit | the two were aligned | D-5 fix |
| 44-ice-watchdog-false-failure.md | ICE watchdog false failure | false failure banner fixed | t80 |
| 45-turn-exposure-accepted-risk.md | TURN exposure decision | risk accepted with compensation | decision C |
| 46-remote-candidate-counting.md | remote candidate counting | two defects fixed | t83 |
| 47-vp9-encode-perf.md | encoder stutter | quantified and located | t85 |
| 48-encoder-fallback.md | automatic encoder fallback | fallback shipped | t87 |
| 49-bitrate-allocation-collapse.md | bitrate allocation collapse | diagnosed | t89 |
| 50-quality-scaling-and-render-fps.md | quality scaling and render frame rate | render side is not the bottleneck | t91 |
| 51-frame-dropper-and-trusted-rc.md | frame dropper and trusted rate controller | dropper disabled via field trial | t92 |
| 52-release-closure.md | release closure | final anchor, fixes and known limits | release |
| 99-final-report.md | independent verification and summary | the final summary report | t11 |
| 99-t34-appendix.md | independent re-verification appendix | artifact-level re-verification | t34 |

## 4. Document map

### 4.1 Current documents (`doc/design/`)

All members of the canonical set are present at this revision and are linked below.

| Document | Content |
|---|---|
| [SPEC](SPEC.md) | frozen documentation standard and the checker's judgement rules |
| [01 — Requirements](01-requirements.md) | requirements with implementation and evidence |
| [02 — Architecture](02-architecture.md) | overall architecture, planes, ports and topology |
| [03 — App architecture](03-app-architecture.md) | app layers, thread model, lifecycle, recovery, config switches |
| [04 — Signaling service](04-signaling-service.md) | package layout, room and seat state, routing, configuration |
| [05 — Protocols](05-protocols.md) | field-level message tables, ICE/TURN/SDP behaviour, JNI contract |
| [06 — Flows](06-flows.md) | sequence diagrams for the key flows, with real log lines |
| [07 — Build and deploy](07-build-and-deploy.md) | toolchain, build stages, release chain, host services |
| [08 — Issues and solutions](08-issues-and-solutions.md) | symptom to root cause history, including rejected approaches |
| [09 — Verification and limitations](09-verification-and-limitations.md) | verification matrix, known limitations, errata |
| [10 — Code map](10-code-map.md) | this document |
| [11 — Coding standards](11-coding-standards.md) | conventions and change safety |
| `doc/design/_generated/` | generated tables; never hand-edited |

### 4.2 Legacy documents and decisions (`doc/`)

[doc/README.md](../README.md) explains the legacy-versus-current split and is the entry point for the
archived material.

Every file that previously lived directly under `doc/` was moved verbatim to `doc/archive/` and replaced at
its original path by a one-line stub naming the archive file and the document that supersedes it. The
archived set covers the overview, cloud infrastructure, architecture, implementation plan, glossary, code
design, encoder internals, build guides, protocol specification, UI design, native and backend
implementation, the agent task specification and the interface contract. The stubs exist so that historical
references from `reports/**` do not dangle.

Architecture decision records live under `doc/adr/`:
[ADR-001](../adr/ADR-001-use-libwebrtc-and-source-build.md),
[ADR-002](../adr/ADR-002-custom-video-encoder-for-dynamic-bitrate.md),
[ADR-003](../adr/ADR-003-1to1-p2p-first.md),
[ADR-004](../adr/ADR-004-split-runtime-and-build-vms.md).

## 5. Symptom to code

| Observed symptom | Where to read | Evidence |
|---|---|---|
| A fallback decision was taken | `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:41` | `reports/48-encoder-fallback.md` |
| The peer left / the call was kept alive | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:35`, `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:52` | `reports/35-room-grace.md`, `reports/36-call-survivability.md` |
| Encoder stutter or quality scaling | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59` | `reports/47-vp9-encode-perf.md`, `reports/50-quality-scaling-and-render-fps.md` |
| Frame dropping on the encoder path | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:40` | `reports/51-frame-dropper-and-trusted-rc.md` |
| ICE or relay trouble | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` | `reports/28-turn-permission-403.md`, `reports/44-ice-watchdog-false-failure.md` |
| NAT type looks wrong | `app/src/main/kotlin/com/example/webrtcdemo/nat/NatTypeRepository.kt:24`, `app/src/main/cpp/nat/nat_detector.cpp:25` | `reports/24-nat-address-endianness.md` |
| Diagnostic bundle requested | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:51` | `reports/52-release-closure.md` |
| Room lifecycle behaves unexpectedly | `signaling/room/room.go:49`, `signaling/room/manager.go:59` | `reports/35-room-grace.md`, `reports/38-signaling-grace-deploy.md` |

Two device-side metric families have no source-file occurrence and must be read from the reports rather
than cited to a line: the frame-dropping counters in `reports/50-quality-scaling-and-render-fps.md` and the
bitrate-update observations in `reports/49-bitrate-allocation-collapse.md`. The field-trial switch that
disables the frame dropper is recorded in `reports/51-frame-dropper-and-trusted-rc.md`.

## 6. Evidence index

| Claim | Citation | Verification artifact |
|---|---|---|
| Kotlin call state machine entry point | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` | source |
| Signaling client entry point | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:35` | source |
| Custom encoder entry point | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59` | source |
| Native registration | `app/src/main/cpp/jni/jni_bridge.cpp:57` | source |
| Signaling process entry | `signaling/main.go:28` | source |
| Room state entry | `signaling/room/room.go:49` | source |
| Build recipe with gate output | — | `reports/37-t76-build-publish.md` §3 |
| Publish identity guard | — | `reports/41-apk-http-ownership.md` §5 |
| Legacy stubs are one line | `doc/00-overview.md:1` | stub files |

## 7. Open items

- The document map in §4 is complete at this revision: every member of the canonical set exists and is
  linked. A document added to the set must be added to that table, and any file named there must exist,
  because a link or a backticked path to a missing file fails the documentation gate.
- The report index gives the task a report belongs to, taken from the report's own title. The column
  "Referenced by" therefore records provenance, not an exhaustive inbound-reference scan, which was not
  performed: `unverified`.
- Reports are cited by name and section only; report line numbers are never used, because report numbering
  drifts from the source tree and report line numbers are not source pointers.
- The package-level grouping in §2 is a summary; a file added to a package without updating this document
  will not be noticed by the documentation gate.
