> [中文（默认）](zh-CN/03-app-architecture.md) · English

# 03 — App architecture

> Status: draft · Owner: writer-app · Task: t3
> Evidence base: `reports/27-encoder-direction-perf.md`, `reports/34-turn-tcp-fallback.md`, `reports/35-room-grace.md`, `reports/36-call-survivability.md`, `reports/39-reconnect-budget-ice-restart.md`, `reports/40-glare-ice-restart-fix.md`, `reports/44-ice-watchdog-false-failure.md`, `reports/47-vp9-encode-perf.md`, `reports/48-encoder-fallback.md`, `reports/49-bitrate-allocation-collapse.md`, `reports/50-quality-scaling-and-render-fps.md`, `reports/51-frame-dropper-and-trusted-rc.md`; seven device captures under the container path pattern /data/dsh/home/workspace/tmp/n1…n7/x/.
> Doc standard: `doc/design/SPEC.md`

## 1. Scope

This document describes the Android application side of the demo: its layers, its thread and callback model
across the Kotlin/native boundary, the session lifecycle including the `session=sN` generation scheme, the
failure and recovery strategy, the configuration switches exposed by the diagnostics page, and the entry point
to the log vocabulary.

It does not describe the signaling service internals or the wire protocol (see the signaling service and
protocol documents), the build and release pipeline, the host-only services, or the historical
decision record. Every architectural statement below is anchored to a source location at the current HEAD; a
line number copied from the legacy documents or from `reports/**` is stale by construction and is never used.

## 2. Layer map

The application is a strict downward stack. Each layer only calls the layer below it, and the native boundary
is crossed in exactly one place per direction.

| Layer | Responsibility | Anchor |
|---|---|---|
| Process entry | log facility first, then native library load | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31` |
| Compose UI | screens, navigation, status panel | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:91` |
| Connection state machine | phase/reason/liveness model, UI-facing connection status | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt:26` |
| Call orchestration | session slot, signalling/room actions, recovery decisions, UI state | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:52` |
| Peer connection session | one `PeerConnection`, SDP/ICE handling, watchdog, stats sampling | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53` |
| Engine | process-wide `PeerConnectionFactory`, `EglBase`, native log sink | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:43` |
| Video encode | custom VP9 encoder and its fallback selector | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59` |
| Video capture/render | camera capturer, renderer pool over `SurfaceViewRenderer` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:44` |
| Signalling transport | WebSocket client, reconnect/rejoin budget, disconnect causes | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:127` |
| Native/JNI | VP9 encode, log sink, NAT detection, callbacks | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:1` |

The UI never talks to `CallSession` directly: it observes `StateFlow`s published by the orchestration layer
(`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:57`) and sends user intents back as methods
on that same object. The transport layer never touches the UI: it reports outcomes through callback interfaces
whose implementations live in the orchestration and session layers
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:1`,
`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:1`).

References: `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallScreen.kt:91`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt:26`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:52`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:53`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:43`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:59`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/VideoRendererPool.kt:44`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:127`,
`app/src/main/cpp/jni/vp9_encoder_jni.cpp:1`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/PeerConnectionObserverImpl.kt:1`,
`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:1`

## 3. Thread model and callback boundaries

The application owns a small, named set of threads. Everything else runs on the Android main thread or inside
libwebrtc's own native threads.

| Thread | Owner | Purpose |
|---|---|---|
| main / UI | Android | Compose recomposition, `StateFlow` collection |
| `signaling-timer` | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:180` | ping cadence, rejoin backoff |
| `ice-watchdog` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1113` | ICE connectivity deadline checks |
| `stats-sampler` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1394` | periodic `getStats()` sampling |
| `enc-fallback` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:151` | in-call encoder switch decisions |
| log writer | `app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:101` | single-writer append to the log files |
| export I/O | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:203` | zip creation off the main thread |

Shared mutable state is protected by two mechanisms only — `@Volatile` for fields that are written once per
session or per encoder handle, and an explicit monitor for the compound "is this session ready yet" decisions:

* `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:122` and
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:181` — volatile session flags.
* `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:368` and
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:812` — the ready/teardown monitor
  (`readyLock`).
* `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:629` and
  `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:748` — the pending remote candidate queue.
* `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:97` — the native encoder handle, which
  is written by `initEncode`/`release` and read by `setRateAllocation` from different threads.

UI state is not shared mutable state: it crosses threads as immutable values through `StateFlow`
(`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:57`). Callbacks arriving from libwebrtc
natives threads are therefore never allowed to mutate UI state directly; they are funneled into the session and
then published.

References: `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:180`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:122`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:181`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:368`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:812`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:629`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:748`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1113`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1394`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:97`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/EncoderFallbackController.kt:151`,
`app/src/main/kotlin/com/example/webrtcdemo/log/FileLogger.kt:101`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:203`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:57`

## 4. Session lifecycle and generation semantics

Startup order is fixed and observable in the log. The log facility is initialised before anything else
(`app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:37`), the native library is loaded lazily
(`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:36`), and the engine performs the
libwebrtc global initialisation exactly once:

1. native log sink and library load — `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31`
2. `PeerConnectionFactory` global initialisation — `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:124`
3. `EglBase` singleton — `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:133`
4. encoder-injecting factory — `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:135`
5. readiness announced — `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176`

Each call gets a fresh `CallSession`, and each session carries a monotonically increasing integer id
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`). Every log line emitted from the session
carries two fields: the generation as `session=sN` and a per-session event counter, both injected centrally in
the field mapper (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:173`). This is what makes a
device capture readable across reconnects: the same call can emit several generations, and a stale timer or a
late callback from generation *N-1* is detectable because its `session=` value differs from the current one.

The orchestration layer holds the current generation in a slot object rather than in scattered fields
(`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1938`), and a navigation to a new call
begins a new generation (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1982`). The
peer-connection lifecycle inside a generation is: `pc_starting`
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`) → offer/answer exchange → ICE →
`close()` (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:735`), which also stops the timers
owned by that session.

Observed startup order on device (n1 capture, `app.log` lines 3, 4, 5 and 49):

```text
2026-09-16T03:30:57.417Z INFO    kotlin main      [25397/25397] app_create level=DEBUG log_dir=/data/user/0/com.example.webrtcdemo/files/logs max_bytes=2097152 max_files=3
2026-09-16T03:30:57.430Z INFO    kotlin main      [25397/25397] native_lib_loaded lib=webrtcdemo_native
2026-09-16T03:30:57.431Z INFO    kotlin main      [25397/25397] native_log_init base=native dir=/data/user/0/com.example.webrtcdemo/files/logs level=DEBUG max_bytes=2097152 max_files=3
2026-09-16T03:34:09.322Z INFO    kotlin pc        [2098/2098] engine_ready impl=SelfVp9Libvpx use_default_encoder=false
```

References: `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31`,
`app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:37`,
`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:36`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:124`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:133`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:135`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:140`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:173`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:735`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1938`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1982`

## 5. Failure and recovery strategy

The design rule is that a call is only ended by an explicit user action or by a fatal transport verdict;
everything else becomes a recoverable UI state.

**Disconnect taxonomy.** The transport classifies every disconnect into a `DisconnectCause` that also declares
whether it is survivable (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:790`). Fatal
causes end the call (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:792`); a peer
leaving is survivable and leaves the decision to the orchestration layer
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:795`); a room that was reclaimed
during a reconnect becomes the recoverable "room lost" state
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:798`).

**Reconnect budget.** Liveness uses a ping cadence of 15 s with a 5 s pong timeout and a tolerance of four
consecutive misses before the socket is considered dead
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`). Socket reconnection deliberately
reuses the rejoin backoff instead of defining a parallel constant: base 1 s, capped at 8 s, at most 10 attempts
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`), which the source states as a
total budget of about 63 s — below the server-side grace period
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:122`).

**Neutral waiting state.** Before a peer arrives the UI shows a non-failing waiting phase rather than a retry
counter (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt:33`), and the phase is published
with the same field name that appears in captures
(`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:465`). The status publisher emits
`ui_conn_state` with phase, cause, liveness evidence and generation
(`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475`).

**Peer left.** A leaving peer is survivable; whether the call is kept is decided from whether the call was ever
connected and whether media is still alive (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:912`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:935`). Media liveness uses a 3 s window
(`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:55`).

**Room reclaimed.** A room-lost result during a reconnect keeps the call and offers an explicit rebuild entry
point (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1062`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:691`), and a successful rebuild is
announced as `room_recreated` (`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:837`).

**ICE watchdog and restart.** A single watchdog covers initial connection and later drops: warned at 30 s, failed
at 45 s, with at most two restarts and a 15 s defer step between tiers
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:94`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:97`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:103`). The watchdog is armed when the session
starts (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1111`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1125`), re-armed when a relay candidate
appears (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1336`), stopped when connectivity is
proven (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1376`), and a restart is requested
through a single entry point (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`).

**Anti-glare division of labour.** After a rejoin, only the side that was already in the room renegotiates; the
reconnecting side answers and arms an 8 s fallback offer
(`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:142`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:147`). The rationale is recorded next to
the implementation, including the consequence of both sides offering at once
(`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:875`).

References: `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:122`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:790`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:792`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:795`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:798`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/ConnectionStatus.kt:33`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:465`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:691`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:837`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:875`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:912`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:935`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1062`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:55`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:142`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:147`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:94`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:97`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:103`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1111`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1125`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1336`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1376`

## 6. Configuration and diagnostic switches

All application switches live in one preference-backed object
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:25`): the signalling URL override
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:39`), the ICE policy
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:42`), the default-encoder toggle
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:45`), the low-resolution toggle
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:48`), the encoder override
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:51`) and the encoder-fallback toggle
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:54`).

The encoder override is a three-state switch — automatic, force self-built, force platform default
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:57`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:60`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:63`) — and the ICE policy is a two-state switch
between "all candidates" and "relay only"
(`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:66`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:69`). The diagnostics screen renders those
switches and the per-layer log levels
(`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:65`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:120`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:171`).

TURN over TCP is a build-time default that can be turned off, and the URL derivation is a pure function so it
can be unit-tested: the default is on
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:52`), and the derived TCP URL is produced
from the signalling-provided TURN URL (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:66`).
The effective transport decisions are visible on the `pc_starting` line, which reports the ICE server kinds, the
forced-relay flag and whether TCP fallback was added
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`). Observed on device (n1 capture,
`app.log` line 51):

```text
2026-09-16T03:34:09.325Z INFO    kotlin pc        [2098/2098] pc_starting evt=1 force_relay=false ice_servers=stun+turn+tcp session=s1 turn_tcp=true
```

The frame-dropper field trial is applied at engine start-up and announced once
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164`). Observed on device (n6 capture,
`app.2.log` line 6170):

```text
2026-09-16T15:34:23.898Z INFO    kotlin pc        [20650/20650] field_trials_set frame_dropper=WebRTC-FrameDropper/Disabled/
```

References: `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:25`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:39`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:42`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:45`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:48`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:51`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:54`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:57`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:60`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:63`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:66`,
`app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:69`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:65`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:120`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/DiagnosticsScreen.kt:171`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:52`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:66`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164`

## 7. Diagnostics and log-vocabulary entry point

Kotlin code has exactly one logging entry point (`app/src/main/kotlin/com/example/webrtcdemo/log/Log.kt:28`).
Business code must not call the platform logger directly; the libwebrtc bridge is the only forwarding exception
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:32`). Three channels share one writer
thread and one directory: application events, native events forwarded through the callback bridge, and webrtc
events forwarded by the bridge above.

The authoritative event vocabulary is machine-generated, not hand-written: see the generated log-events table
in `doc/design/_generated/log-events.md`, which lists every Kotlin event key with its defining source location
and field keys, every native event key, and a sorted index. When a document quotes an event name, the name must
be taken from that table so that the key is provably emitted by the current code.

Frequently cited anchors, all taken from the generated table:

| Event | Emitted from | Meaning |
|---|---|---|
| `app_create` | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:40` | log facility ready, first durable line |
| `engine_ready` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176` | factory and `EglBase` ready |
| `pc_starting` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306` | session created, transport decisions taken |
| `call_init` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:584` | orchestration entered a call |
| `ui_conn_state` | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475` | phase/cause/liveness publication |
| `stats_sample` | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/StatsMapper.kt:87` | periodic statistics sample |
| `setrates` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:300` | bitrate target applied |
| `encoder_created` | `app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:61` | encoder instance selected |
| `export_zip` | `app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:179` | diagnostic bundle written |

References: `app/src/main/kotlin/com/example/webrtcdemo/log/Log.kt:28`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/LibwebrtcLoggable.kt:32`,
`app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:40`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:176`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:306`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:584`,
`app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1475`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/StatsMapper.kt:87`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoder.kt:300`,
`app/src/main/kotlin/com/example/webrtcdemo/encoder/Vp9VideoEncoderFactory.kt:61`,
`app/src/main/kotlin/com/example/webrtcdemo/diag/LogExporter.kt:179`

## 8. Evidence index

| Claim | Citation | Verification artifact |
|---|---|---|
| Startup order is log-first, then native load, then engine | `app/src/main/kotlin/com/example/webrtcdemo/WebRtcDemoApp.kt:31` | n1 `app.log` lines 3–5 |
| Engine initialises libwebrtc globally exactly once | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:99` | n1 `app.log` line 49 |
| Every session carries a generation and event counter | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:173` | n1 `app.log` line 95 |
| Reconnect budget ≈63 s, below the server grace window | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:122` | `reports/39-reconnect-budget-ice-restart.md` |
| Rejoin renegotiation is single-sided, 8 s fallback | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallSurvivability.kt:147` | `reports/40-glare-ice-restart-fix.md` |
| ICE watchdog tiers 30 s / 45 s, ≤2 restarts | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:94` | `reports/44-ice-watchdog-false-failure.md` |
| Room-lost keeps the call and offers rebuild | `app/src/main/kotlin/com/example/webrtcdemo/ui/call/CallViewModel.kt:1062` | n6 `app.2.log` line 13492 |
| TURN over TCP fallback defaults on | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:52` | `reports/34-turn-tcp-fallback.md` |
| Frame dropper disabled by field trial | `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcEngine.kt:164` | n6 `app.2.log` line 6170 |
| Log keys are machine-generated with source anchors | `doc/design/_generated/log-events.md:1` | the generated table itself |

## 9. Open items

* **Checker pass recipe.** `scripts/doc-verify.sh` is the delivery gate and changes often. The stable recipe
  used by this document: cite repository sources as bare `path:LINE` against the current HEAD; write host
  absolute paths under an explicit `HOST:` scope with in-repo evidence in the same sentence; write workspace and
  container paths bare (markers such as `WORKSPACE:`/`ARTIFACT:` are retired and now fail); keep commands in
  backtick-free fenced blocks; and cite an unwritten document as plain text plus
  `not yet written at authoring time`. Re-run
  `bash scripts/doc-verify.sh --only docs/03-app-architecture.md docs/06-flows.md`
  after any change to the script or to this document.
* **ICE restart was never exercised in the captured sessions.** The restart counters are zero everywhere in the
  seven device captures; the restart path is therefore documented from code and from watchdog/anti-glare log
  evidence, not from a captured restart. This is a coverage limitation, not a code claim.
* **Renderer-side frame metrics have no source-side key.** Tokens such as the renderer and receiver drop
  counters and the renderer log tag exist only in the binary artifact and in `reports/**`, so they are cited by
  report section rather than by `file:LINE`.
* **SPEC version churn.** The documentation standard moved from v1.3.0 to v1.4.0 during this task; the citation
  rules quoted here are section-level references so that a further revision does not invalidate them.
