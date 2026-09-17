# 05 — Protocols and interactions

> Status: draft · Owner: writer-signaling · Task: t4
> Evidence base: reports/09-go-signaling.md, reports/35-room-grace.md, reports/39-reconnect-budget-ice-restart.md, reports/40-glare-ice-restart-fix.md, reports/06-coturn.md
> Doc standard: `doc/design/SPEC.md`

## 1. Scope

This document describes the wire contract between the Android client and the signaling service, the
connection and reconnect policy, the ICE, TURN and SDP behaviour of the client, the JNI contract
between the Kotlin layer and the native library, and the diagnostic event vocabulary.

It does not describe service internals (see [04-signaling-service.md](04-signaling-service.md)), the application layers (see
[03-app-architecture.md](03-app-architecture.md)), or the end-to-end flows (see [06-flows.md](06-flows.md)).

Field-level message tables are machine-generated and authoritative; this document cross-references
them and never restates a type set of its own. The generated artifacts live in
`doc/design/_generated/` (contract spelling `docs/_generated/`, the same files through the `docs`
compatibility link) and are produced by `scripts/gen-doc-tables.sh`.

## 2. Transport and framing

Signaling runs over one WebSocket endpoint, `PathWS` (`signaling/server/server.go:25`). Every frame is
a UTF-8 JSON text frame, and the discriminator field is `type`.

The envelope is parsed into `Message` (`signaling/protocol/message.go:41`); only the discriminator is
read for routing, and forwarding types are passed on as the original bytes
(`signaling/server/ws_handler.go:379`). A frame larger than the configured limit is rejected by the
read limit, which is `MaxMessageSize` (65 536 B, `signaling/config/config.go:29`) applied by the read
loop (`signaling/room/peer.go:221`).

The client encodes and decodes with `SignalingCodec`
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:143`), which ignores
unknown keys but not missing required ones
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:146`).

References: `signaling/server/server.go:25`, `signaling/protocol/message.go:41`, `signaling/server/ws_handler.go:379`, `signaling/config/config.go:29`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:143`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:146`, `signaling/room/peer.go:221`

## 3. Message catalogue

Both sides implement the same 14 message types. The generated table
[signaling messages](_generated/signaling-messages.md) reports the Go constant, the JSON
value, the direction and the field list of each one; §4 of that file states that the two type sets
are identical and §6 states that every JSON key has a matching Kotlin property.

| Go constant | JSON value | Direction | Kotlin class |
|---|---|---|---|
| `TypeCreate` | create | C→S | `Create` |
| `TypeCreated` | created | S→C | `Created` |
| `TypeJoin` | join | C→S | `Join` |
| `TypeJoined` | joined | S→C | `Joined` |
| `TypePeerJoined` | peerJoined | S→C | `PeerJoined` |
| `TypePeerLeft` | peerLeft | S→C | `PeerLeft` |
| `TypeOffer` | offer | C→S→C | `Offer` |
| `TypeAnswer` | answer | C→S→C | `Answer` |
| `TypeIce` | ice | C→S→C | `Ice` |
| `TypeNatType` | natType | C→S→C | `NatTypeMessage` |
| `TypeLeave` | leave | C→S | `Leave` |
| `TypeError` | error | S→C | `ServerError` |
| `TypePing` | ping | C→S | `Ping` |
| `TypePong` | pong | S→C | `Pong` |

All 14 constants are declared in one block (`signaling/protocol/message.go:12`), and all 14 Kotlin
classes carry explicit serial names
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:19`).

Behaviour beyond the field list:

| Message | Contract |
|---|---|
| `TypeCreate` | creates the room and returns `TypeCreated` with the ICE server list |
| `TypeJoin` | joins by room id and returns `TypeJoined` with the assigned peer id |
| `TypePeerJoined` | tells the peer already in the room that the other side arrived |
| `TypePeerLeft` | tells the remaining peer that the other side left; sent once per departure |
| `TypeOffer`, `TypeAnswer` | carry a non-empty session description, forwarded unchanged |
| `TypeIce` | carries a candidate; the media identification fields are optional |
| `TypeNatType` | carries the local NAT type; an unknown value is forwarded with a warning |
| `TypeLeave` | the server notifies the peer, closes the socket and destroys the room |
| `TypePing`, `TypePong` | application-level heartbeat; the reply carries the server time |
| `TypeError` | carries a code and a human-readable message |

Two field details are implementation-visible. First, the media identification index is a pointer on
the Go side, so a missing field is distinguishable from an explicit zero
(`signaling/protocol/message.go:106`). Second, a candidate without either media identification field
is warned about and still forwarded, deliberately accepting a serialization difference
(`signaling/server/ws_handler.go:350`).

References: `signaling/protocol/message.go:12`, `signaling/protocol/message.go:106`, `signaling/server/ws_handler.go:350`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt:19`

## 4. Connection and reconnect policy

The client heartbeat is 15 s (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`)
and a single pong window is 5 s
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`). A single missed window
no longer drops the connection: up to `PONG_MISS_TOLERANCE` consecutive misses are tolerated
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`), giving an effective
liveness threshold of `PONG_FAIL_AFTER_MS` = 20 s
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:94`). The two predicates are
`pongMissed` (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:104`) and
`pongTimeoutReached` (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:117`).

Reconnection uses one exponential backoff for both socket loss and rejoin: `REJOIN_RETRY_BASE_MS` is
1 s (`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`),
`REJOIN_RETRY_MAX_MS` caps a single delay at 8 s
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`) and
`MAX_REJOIN_ATTEMPTS` is 10
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`).
`rejoinDelayMs` yields 1 s, 2 s, 4 s and 8 s thereafter
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:156`), and `rejoinBudgetMs`
sums the schedule to 63 s
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163`). That budget exceeds
the server read timeout and stays below the server grace period, as reported in
reports/39-reconnect-budget-ice-restart.md §3.

On the server side the read timeout is three ping intervals (`signaling/config/config.go:38`) and the
grace period that keeps a dropped seat is `DefaultRoomGrace` = 90 s
(`signaling/config/config.go:54`). Because the seat survives a drop, a reconnect inside the grace
window is accepted with the original peer id (see [04-signaling-service.md](04-signaling-service.md)); outside it the seat is
reclaimed and the surviving peer is told through `peerLeft`.

Two obligations follow for any client:

| Obligation | Reason |
|---|---|
| dispatch every inbound frame by its discriminator, never by arrival order | heartbeat replies share the stream with call messages |
| treat a full-room error as transient, and not-found or expired as terminal | a seat can be reclaimed while the room survives |

While no listener is registered the client buffers at most `MAX_PENDING_MESSAGES` = 32 messages
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:170`), and a socket close
with the normal close code 1000 is handled explicitly
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:128`). Before a reconnect is
scheduled the previous socket is closed or cancelled
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:424`,
`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:655`), and the reconnect
itself is driven by `scheduleReconnect`
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:721`).

References: `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:79`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:82`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:91`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:94`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:104`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:117`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:128`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:139`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:142`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:145`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:156`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:170`, `signaling/config/config.go:38`, `signaling/config/config.go:54`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:424`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:655`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:721`

## 5. ICE, TURN and SDP behaviour

ICE server configuration reaches the client only through signaling responses: the STUN URL, the TURN
URL and the TURN credentials are attached to the room-created response
(`signaling/server/ws_handler.go:226`) and to the join response
(`signaling/server/ws_handler.go:287`). The TURN deployment behind those values is described in
[04-signaling-service.md](04-signaling-service.md); its relay address rule and its measured failure mode are in
reports/06-coturn.md §5.

The server never rewrites a session description. An offer or answer is validated for a non-empty body
and then forwarded as raw bytes (`signaling/server/ws_handler.go:326`); the same holds for candidates
(`signaling/server/ws_handler.go:340`) and NAT types (`signaling/server/ws_handler.go:359`). The
server does not inspect the session description, so codec selection and any renegotiation are client
concerns.

The NAT type exchange carries one of six strings, declared as constants
(`signaling/protocol/message.go:30`): `Open`, `FullCone`, `RestrictedCone`, `PortRestrictedCone`,
`Symmetric` and `Unknown`. The server forwards the value unchanged and logs a warning for a string
outside the set (`signaling/server/ws_handler.go:369`).

Media transport selection is a client-side outcome of ICE, expressed as either a direct path or a
relayed path. Three client behaviours shape it:

| Behaviour | Implementation |
|---|---|
| TURN over UDP first, with a TCP entry appended as fallback | `WebRtcConfig` rewrites the TURN URL to `transport=tcp` and adds it unless the server already provided one (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:105`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:174`); the fallback is on by default (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:165`) |
| loopback candidates are filtered in both directions | locally gathered `127.0.0.0/8` and `::1` candidates are not sent (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:886`) and remote ones are dropped on receipt (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:605`) through `LoopbackCandidates.isLoopback` (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1581`) |
| forced relay for reproducing a relayed call | the diagnostics switch selects `iceTransportsType` RELAY instead of ALL (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:156`, `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:162`) |

A symmetric NAT leaves no direct path, so such a call can complete only through a relay; the forced
relay switch above is how that path is reproduced (see reports/30-ice-relay-robustness.md).

Renegotiation is bounded by two registered behaviours. First, the offer duty belongs to the room
creator only, which removes simultaneous-renegotiation conflicts
(reports/40-glare-ice-restart-fix.md §2). Second, an ICE restart marks the next offer for
renegotiation rather than sending one immediately (`restartIce`,
`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`): the call marks the
connection (`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1286`) and the offerer
re-offers afterwards, with at most `MAX_ICE_RESTARTS` = 2 attempts
(`app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`). A silent drop with no
renegotiation is a registered limitation, documented in [09-verification-and-limitations.md](09-verification-and-limitations.md).

`app/src/main/jniLibs/arm64-v8a/libjingle_peerconnection_so.so` supplies the ICE and DTLS/SRTP
implementation used by the client; it is a pinned build input and is not in version control.

References: `signaling/server/ws_handler.go:226`, `signaling/server/ws_handler.go:287`, `signaling/server/ws_handler.go:326`, `signaling/server/ws_handler.go:340`, `signaling/server/ws_handler.go:359`, `signaling/server/ws_handler.go:369`, `signaling/protocol/message.go:30`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:105`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:156`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:165`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/WebRtcConfig.kt:174`, `app/src/main/kotlin/com/example/webrtcdemo/config/AppConfig.kt:162`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:100`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:605`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:886`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1272`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1286`, `app/src/main/kotlin/com/example/webrtcdemo/webrtc/CallSession.kt:1581`

## 6. JNI contract

The native library is `webrtcdemo_native`, loaded once by `NativeLoader`
(`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:17`). Binding uses
`JNI_OnLoad` with `RegisterNatives` (`app/src/main/cpp/jni/jni_bridge.h:9`,
`app/src/main/cpp/jni/jni_bridge.cpp:57`) and returns `JNI_VERSION_1_6`
(`app/src/main/cpp/jni/jni_bridge.cpp:56`). The project has no generated header and no name-mangled
entry points, so no `Java_*` symbol exists to discover.

Four Kotlin classes are registered, with 15 native methods in total:

| Class | Methods | Registration table |
|---|---|---|
| `NativeLog` | 4 | `app/src/main/cpp/jni/native_log_jni.cpp:71` |
| `NativeVp9Encoder` | 9 | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` |
| `NativeNatDetector` | 2 | `app/src/main/cpp/jni/nat_detector_jni.cpp:49` |
| `NativeCallbacks` | 2 (native to Kotlin) | `app/src/main/cpp/jni/jni_bridge.cpp:56` |

The authoritative cross-check of Kotlin declarations, registration tables and C prototypes is the
generated table [jni contract](_generated/jni-contract.md); its §7 reports the divergence
result per entry, and its §6 records the optional exported-symbol check, which is
`unverified (build product absent)` when the library has not been built.

Method names and signatures are frozen. On the Kotlin side the declarations are split across
`NativeLog` (`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:32`),
`NativeVp9Encoder` (`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:21`),
`NativeNatDetector` (`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeNatDetector.kt:11`)
and `NativeCallbacks` (`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:25`).
The two callbacks the native layer invokes are `onNatTypeDetected`
(`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:36`) and `onLogEvent`
(`app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:52`).

`NativeVp9Encoder` returns an opaque handle that the Kotlin side must release exactly once; failure
modes are reported through return codes rather than exceptions, because the native code is built
without exceptions.

`libwebrtcdemo_native.so` is the build product of the CMake target `webrtcdemo_native`
(`app/src/main/cpp/CMakeLists.txt:32`); it is produced at build time and is not in version control.

References: `app/src/main/cpp/jni/jni_bridge.h:9`, `app/src/main/cpp/jni/jni_bridge.cpp:56`, `app/src/main/cpp/jni/jni_bridge.cpp:57`, `app/src/main/cpp/jni/native_log_jni.cpp:71`, `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347`, `app/src/main/cpp/jni/nat_detector_jni.cpp:49`, `app/src/main/cpp/CMakeLists.txt:32`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLoader.kt:17`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeLog.kt:32`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeVp9Encoder.kt:21`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeNatDetector.kt:11`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:25`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:36`, `app/src/main/kotlin/com/example/webrtcdemo/nativebridge/NativeCallbacks.kt:52`

## 7. Diagnostic events

Event keys are the contract for log-driven diagnosis. The machine-generated inventory
[log events](_generated/log-events.md) lists the Kotlin and native event keys with their
emitter file and line, plus a sorted index (§3); it is authoritative for those two layers.

The signaling service emits its own keys, which are not part of that generated file and are therefore
cited directly from the emitting source:

| Event key | Emitter |
|---|---|
| `server_start` | `signaling/main.go:101` |
| `listening` | `signaling/server/server.go:157` |
| `ws_open` | `signaling/server/ws_handler.go:72` |
| `ws_close` | `signaling/server/ws_handler.go:118` |
| `heartbeat_timeout` | `signaling/server/ws_handler.go:114` |
| `room_created` | `signaling/room/manager.go:150` |
| `room_joined` | `signaling/room/manager.go:210` |
| `seat_takeover` | `signaling/room/manager.go:207` |
| `room_destroyed` | `signaling/room/manager.go:367` |
| `room_expired` | `signaling/room/manager.go:430` |
| `grace_expired` | `signaling/room/manager.go:285` |
| `peer_joined` | `signaling/server/ws_handler.go:322` |
| `peer_left_sent` | `signaling/server/ws_handler.go:535` |
| `join_rejected` | `signaling/server/ws_handler.go:553` |
| `error_sent` | `signaling/server/ws_handler.go:551` |
| `offer_forward` | `signaling/server/ws_handler.go:423` |
| `answer_forward` | `signaling/server/ws_handler.go:425` |
| `ice_forward` | `signaling/server/ws_handler.go:427` |
| `nattype_forward` | `signaling/server/ws_handler.go:429` |
| `forward_dropped` | `signaling/server/ws_handler.go:395` |
| `heartbeat_ping` | `signaling/server/ws_handler.go:473` |

Client-side keys that matter for protocol diagnosis are the connection lifecycle keys `ws_open`
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:272`), `ws_close`
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:313`), `ws_pong_timeout`
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:647`),
`ws_reconnect_scheduled`
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:751`) and
`ws_reconnect_suppressed`
(`app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523`).

References: `signaling/main.go:101`, `signaling/server/server.go:157`, `signaling/server/ws_handler.go:72`, `signaling/server/ws_handler.go:114`, `signaling/server/ws_handler.go:118`, `signaling/server/ws_handler.go:322`, `signaling/server/ws_handler.go:395`, `signaling/server/ws_handler.go:423`, `signaling/server/ws_handler.go:425`, `signaling/server/ws_handler.go:427`, `signaling/server/ws_handler.go:429`, `signaling/server/ws_handler.go:473`, `signaling/server/ws_handler.go:535`, `signaling/server/ws_handler.go:551`, `signaling/server/ws_handler.go:553`, `signaling/room/manager.go:150`, `signaling/room/manager.go:207`, `signaling/room/manager.go:210`, `signaling/room/manager.go:285`, `signaling/room/manager.go:367`, `signaling/room/manager.go:430`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:272`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:313`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:523`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:647`, `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:751`

## 8. Evidence index

| Claim | Citation | Verification artifact |
|---|---|---|
| one WebSocket endpoint | `signaling/server/server.go:25` | reports/09-go-signaling.md §3 |
| type sets are identical on both sides | `doc/design/_generated/signaling-messages.md` | generated table §4 |
| forwarding is byte-for-byte | `signaling/server/ws_handler.go:379` | reports/09-go-signaling.md §4 |
| reconnect budget 63 s | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt:163` | reports/39-reconnect-budget-ice-restart.md §3 |
| grace period 90 s | `signaling/config/config.go:54` | reports/35-room-grace.md §2.1 |
| ICE values are delivered by signaling only | `signaling/server/ws_handler.go:287` | reports/09-go-signaling.md §3 |
| glare removed by a single offer duty | reports/40-glare-ice-restart-fix.md §2 | reports/40-glare-ice-restart-fix.md |
| JNI binding is registration based | `app/src/main/cpp/jni/jni_bridge.h:9` | `doc/design/_generated/jni-contract.md` §1 |
| 15 native methods | `app/src/main/cpp/jni/vp9_encoder_jni.cpp:347` | `doc/design/_generated/jni-contract.md` §3 |

## 9. Open items

1. The exported-symbol check of the native library is `unverified`, because no build product is kept
   in version control (see `doc/design/_generated/jni-contract.md` §6).
2. The Go event keys in section 7 are cited from source rather than from the generated log inventory,
   which covers the Kotlin and native layers only. This is a coverage gap, not a divergence.
3. The client-side drain of buffered messages after a socket loss is `not measured` in this document;
   only the buffer capacity is cited.
