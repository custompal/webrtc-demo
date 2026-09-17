# 02 — Architecture

> Status: draft · Owner: architect · Task: t1
> Evidence base: `reports/52-release-closure.md`, `reports/99-final-report.md` §15, `deploy/README.md`,
> `reports/06-coturn.md`, `reports/09-go-signaling.md`
> Doc standard: `doc/design/SPEC.md`

---

## 1. Scope

This document is the system-level map: what the components are, how they are deployed, which plane each
concern belongs to, and which specialist document owns the detail. It deliberately does not restate protocol
fields (see `doc/design/05-protocols.md`), App internals (see `doc/design/03-app-architecture.md`), or the
release procedure (see `doc/design/07-build-and-deploy.md`).

## 2. System in one paragraph

An Android application makes a one-to-one video call. Two phones talk to a single Go signaling service over a
WebSocket, exchange SDP and ICE candidates, and then carry the media over WebRTC. The application brings its
own VP9 encoder (libvpx through a JNI boundary) instead of the platform encoder. When NAT traversal fails the
media is relayed by coturn. The built APK is published by a small HTTP file service on the same host. There is
no user account system and no room persistence: a room lives in the signaling process memory.

Citation key: every code pointer below is a `file:`-style citation (`file:LINE`), machine-checkable by
`scripts/doc-verify.sh`.

## 3. Components

| Component | Role | Implementation | Runtime facts |
|---|---|---|---|
| Android app | UI, call state machine, WebRTC session, custom VP9 encoder, diagnostics | `app/src/main/kotlin/**`, `app/src/main/cpp/**` | arm64-v8a only; `applicationId com.example.webrtcdemo` (`app/build.gradle.kts:41`) |
| Signaling service | Room/seat lifecycle, message routing, ICE configuration handout, health endpoint | `signaling/**` (Go 1.22, `signaling/go.mod:3`) | single static binary; listens on `:8443` (`signaling/config/config.go:19`) |
| coturn | STUN and TURN relay | host package, configuration copy `deploy/turnserver.conf` | `listening-port=3478` (`deploy/turnserver.conf:4`), relay pool `49152-49200` (`deploy/turnserver.conf:8-9`) |
| apk-http | Serves the built APK and its checksums | host-side service, outside the repository (errata D-6) | `HOST: /opt/apk-http`; public URL `http://47.238.144.66:8080/app-debug.apk` (`reports/52-release-closure.md` §1) |

## 4. Deployment topology

```
                       47.238.144.66 (public) / 172.21.0.219 (private)   [HOST: all services below]
 ┌───────────────────────────────────────────────────────────────────────────┐
 │  HOST: /etc/systemd/system/signaling.service   :8443/tcp   /ws, /healthz   │
 │  coturn                                        3478/udp+tcp, 49152-49200  │
 │  HOST: /opt/apk-http                           :8080/tcp   /app-debug.apk  │
 └───────────────────────────────────────────────────────────────────────────┘
        ▲ ws://47.238.144.66:8443/ws              ▲ stun:/turn:47.238.144.66:3478
        │  (SDP, ICE, NAT type, heartbeat)        │  (candidate gathering, relay)
   ┌────┴────┐                                ┌────┴────┐
   │ Phone A │◄──── media: P2P if possible, otherwise TURN relay ────►│ Phone B │
   └─────────┘        (DTLS/SRTP, VP9 video, Opus audio)             └─────────┘
```

Facts behind the diagram:

* The signaling service is reached at `/ws` (`signaling/server/server.go:25`); the compiled-in default is
  `ws://47.238.144.66:8443/ws` (`app/build.gradle.kts:69`) and can be overridden at runtime from the
  diagnostics page (`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:106`).
* The STUN/TURN URLs the client uses are not compiled in: the server sends them in `created`/`joined`
  (`signaling/protocol/message.go:54-57`), and they are the values of the `-stun`/`-turn` flags
  (`deploy/signaling.service:28`).
* Only `3478/udp`, `3478/tcp`, `49152-49200/udp` and `8443/tcp` are open; `5349` (TLS/DTLS) is not
  (`deploy/README.md` §TURN exposure). Client-side there is a TURN-over-TCP fallback entry in the ICE server
  list (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:66`).
* Unit and repository copy are kept parameter-identical; the check procedure is in `deploy/README.md` §6 and
  the alignment fix is `reports/43-deploy-unit-consistency.md`.
* The repository provides `scripts/build_app.sh` as documentation of the build sequence, but it is a
  `HOST`-scoped script (it hard-codes `WS=/opt/dsh-workspaces` at `scripts/build_app.sh:33` and sources
  `$WS/env.sh` at `scripts/build_app.sh:62`), so it cannot execute inside the container; see §5.4. `env.sh`,
  `env-container.sh` and `env-go.sh` at the workspace root supply the toolchain variables (auto-classified P2,
  `workspace-only, outside git repo`; §7.1 of `doc/design/SPEC.md`).

## 5. Planes

Each plane has one owner document; this table exists so that a reader can jump straight to the right one.

| Plane | Question it answers | Owner document |
|---|---|---|
| Signaling | How do the peers find each other and exchange SDP/ICE? | `doc/design/04-signaling-service.md`, `doc/design/05-protocols.md` |
| Media | How do the frames and audio get across, and what shapes the bitrate? | `doc/design/03-app-architecture.md`, `doc/design/05-protocols.md` |
| Diagnostics | How do I see what happened? | `doc/design/03-app-architecture.md` §Diagnostics, `doc/design/_generated/` log table |
| Release | How does code become a downloadable APK? | `doc/design/07-build-and-deploy.md` |

### 5.1 Signaling plane

* One WebSocket per peer; JSON text frames discriminated by a `type` field
  (`signaling/protocol/message.go:41`).
* Rooms are in-memory: at most 2 seats (`signaling/room/room.go:13`), a room with nobody joining expires
  after `-room-expiry` 1800 s (`signaling/config/config.go:26`).
* Heartbeat is client-driven: a ping every 15 s (`signaling/protocol/heartbeat.go:11`); the server read
  timeout is 3 ping intervals (`signaling/config/config.go:38`); the client tolerates 4 consecutive misses
  before declaring the link dead (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`).
* A dropped WebSocket does not destroy the room: the seat is kept for the grace period
  (`signaling/config/config.go:54`) and a reconnecting peer with the same identity takes the seat over
  (`signaling/room/room.go:66`).

### 5.2 Media plane

* Capture, encode, packetise, encrypt and render are libwebrtc's; the project supplies the video encoder and
  the observability (`app/src/main/cpp/encoder/vp9_encoder.cpp`,
  `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt`).
* ICE candidates are gathered from the STUN/TURN configuration handed out by the signaling service;
  loopback candidates are filtered on both sides (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1581`).
* A changed network path is recovered with an ICE restart, initiated only by the host side
  (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:127`).
* In the measured deployment both peers were behind symmetric NAT, so every call took the relay path
  (`reports/52-release-closure.md` §5 item 1).

### 5.3 Diagnostics plane

* All three producers (Kotlin, C++ native, webrtc) write into one directory `<filesDir>/logs/`
  (`app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:508`) with 2 MiB × 3 rotation
  (`…FileLogger.kt:502`, `…FileLogger.kt:505`).
* Every event carries `session=sN` plus an event sequence number, so lines from different sessions can be
  separated (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`,
  `…CallSession.kt:173`).
* The user-facing entry point is the diagnostics page (`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt`)
  and the log zip export (`app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:139`).
* The full event-key inventory is generated from the code into `doc/design/_generated/` (never hand-written).

### 5.4 Release plane

* Build happens on the host with a pinned toolchain (JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 2.0.21,
  NDK 26.1.10909125, CMake 3.22.1, Go 1.22).
* `scripts/build_app.sh` is **host-only**: it hard-codes `WS=/opt/dsh-workspaces` at
  `scripts/build_app.sh:33` and sources `. "$WS/env.sh"` at `scripts/build_app.sh:62`, so it cannot run inside
  the container (`HOST`-scoped script; container-equivalent Gradle steps in `doc/design/07-build-and-deploy.md`).
* Publishing is a host manual procedure: `HOST: /opt/apk-http/publish_apk.sh` (no such file exists in this
  repository) — see `doc/design/SPEC.md` §2.5 and `reports/10-app-build.md:1093`.
* The publish chain refuses to overwrite a same-named artifact with different bytes and treats the last-written
  `SOURCE.sha256` as the commit point (`reports/52-release-closure.md` §1, §6).
* The delivered anchor is APK sha256
  `59c75778fb457e8c6d4c7955b349dfa4fde784fa616e852419e3b007e1e41d07`, 33 472 645 B, built from commit
  `4130ddc` (`reports/52-release-closure.md` §1). The file itself is a build output (`app-debug.apk`), never a
  repository path (P5, auto-classified; `doc/design/SPEC.md` §7.1).

## 6. Document map

| Document | One-line purpose |
|---|---|
| `doc/design/SPEC.md` | Frozen writing and citation standard, directory conventions, checker rules |
| `doc/design/01-requirements.md` | Functional and non-functional requirements with citations and status |
| `doc/design/02-architecture.md` | This document |
| `doc/design/03-app-architecture.md` | App layers, threads, lifecycle, recovery, switches |
| `doc/design/04-signaling-service.md` | Go service structure and room/seat state machine |
| `doc/design/05-protocols.md` | Field-level messages, reconnect policy, ICE/TURN/SDP, JNI contract |
| `doc/design/06-flows.md` | Mermaid flows with step lists and real log evidence |
| `doc/design/07-build-and-deploy.md` | Toolchain, build stages, invariants, release chain, host services |
| `doc/design/08-issues-and-solutions.md` | Defect history with rejected/disproven alternatives |
| `doc/design/09-verification-and-limitations.md` | Verification matrix, known limitations, errata |
| `doc/design/10-code-map.md` | Code map, report index, symptom-to-code lookup |
| `doc/design/11-coding-standards.md` | Language conventions and change-safety checklist |
| `doc/design/_generated/signaling-messages.md` | Generated signalling message/field table with Go↔Kotlin divergence |
| `doc/design/_generated/log-events.md` | Generated log event key table (emitter file and line) |
| `doc/design/_generated/jni-contract.md` | Generated JNI contract, cross-checked Kotlin ↔ C++ `RegisterNatives` |
| `doc/design/_generated/host-commands.md` | Generated command/flag inventory with its evidentiary status |
| `doc/README.md` | Legacy vs current explanation, full archive inventory and stub mechanism |

## 7. Decisions carried from the ADRs

The seven ADRs were moved verbatim to `doc/archive/adr/`; each original path keeps a one-line stub
(`doc/adr/ADR-001-use-libwebrtc-and-source-build.md` … `doc/adr/ADR-007-code-style-and-comment-rules.md`, see
`doc/adr/README.md`). Their decisions are still binding; the table records where each one materialised.

| ADR | Decision | Where it is visible today |
|---|---|---|
| ADR-001 | Use libwebrtc and build it from source | `third_party/libwebrtc/`, pinned jar/aar hashes in `reports/99-final-report.md` §15.1 |
| ADR-002 | Custom VP9 encoder to study dynamic bitrate | `app/src/main/cpp/encoder/`, `app/src/main/kotlin/com/example/webrtcdemo/encoder/` |
| ADR-003 | 1:1 P2P first | room capacity 2 (`signaling/room/room.go:13`) |
| ADR-004 | Split runtime and build machines | build runs on the host (`scripts/build_app.sh` header usage note) |
| ADR-005 | Go signaling plus an RFC 5780 NAT test | `signaling/`, `app/src/main/cpp/nat/nat_detector.cpp` |
| ADR-006 | Code design decisions | `doc/design/03-app-architecture.md` |
| ADR-007 | Code style and comment rules | `doc/design/11-coding-standards.md` |

## 8. Cross-plane invariants

* **I-1** — A room never exceeds two seats (`signaling/room/room.go:13`,
  `signaling/room/manager.go:161`).
* **I-2** — Only one side offers at a time: the host proposes, the joiner answers
  (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:127` encodes the offer duty).
* **I-3** — The grace period must exceed the client reconnect budget: 90 s > 63 s
  (`signaling/config/config.go:54`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163`;
  assertion `app/src/test/kotlin/com/example/webrtcdemo/signaling/ReconnectBudgetTest.kt:52`).
* **I-4** — A local liveness judgement must never end a call whose media is still flowing
  (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:105`).
* **I-5** — Media geometry (rotation, stride, buffer capacity) is decided in exactly one place per direction;
  see the rejected rotation-baking experiment in `doc/design/08-issues-and-solutions.md`.

## 8.1 JNI boundary and evidence classes

* The custom native library is CMake target `webrtcdemo_native` (`app/src/main/cpp/CMakeLists.txt:32`). Its
  contract is defined by Kotlin `external fun` declarations under
  `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/**` cross-checked against the `JNINativeMethod`
  tables in `app/src/main/cpp/jni/nat_detector_jni.cpp:49`, `app/src/main/cpp/jni/native_log_jni.cpp:71` and
  `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` (15 registered entries in total), plus the C prototypes in
  `app/src/main/cpp/jni/*.h`. The generated cross-check is `doc/design/_generated/jni-contract.md`
  (`doc/design/SPEC.md` §2.1).
* The binding mode is `JNI_OnLoad` + `RegisterNatives` (`app/src/main/cpp/jni/jni_bridge.h:9`), so the library
  exposes **no** `Java_*` entry points. There is also no project-generated `*_jni.h` in the repository; it is a
  CMake build product.
* The built `libwebrtcdemo_native.so` is a build product only: `.gitignore:56` ignores `*.so` and it lives
  under `app/build/intermediates/**`. Documents must not cite it as a repository fact
  (`doc/design/SPEC.md` §2.4).
* Evidence classes used throughout this documentation set (`doc/design/SPEC.md` §2.3): **repository** paths are
  checkable; **workspace-only** paths such as `/data/dsh/home/workspace/tmp/t47b-captain-build.sh` are readable
  here but outside git and must be labelled as such; **host-only** paths (`/opt/**`, `/etc/**`) are unverifiable
  from the container and are always marked `unverified`.

## 9. Open items

| # | Item | Why it is open |
|---|---|---|
| A-1 | No P2P path in the measured deployment | both peers were behind symmetric NAT (`reports/52-release-closure.md` §5 item 1) |
| A-2 | Host-side publish scripts are not in the repository | errata D-6 |
| A-3 | `deploy/README.md` is written in Chinese | it predates the English-only rule in `doc/design/SPEC.md` §6; the repository copy of the unit and coturn configuration it documents is authoritative for deployment |
