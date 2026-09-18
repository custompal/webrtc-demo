> [中文（默认）](zh-CN/04-signaling-service.md) · English

# 04 — Signaling service

> Status: draft · Owner: writer-signaling · Task: t4
> Evidence base: reports/09-go-signaling.md, reports/12-deploy-signaling.md, reports/35-room-grace.md, reports/38-signaling-grace-deploy.md
> Doc standard: `doc/design/SPEC.md`

## 1. Scope

This document describes the Go signaling service in `signaling/`: its package layout, the room and
seat state machine, message routing and error codes, the health endpoint, its configuration and
systemd unit, and its relationship to the coturn server.

It does not describe the Android client (see [03-app-architecture.md](03-app-architecture.md)), the wire format itself
(see [05-protocols.md](05-protocols.md)), or the call flows (see [06-flows.md](06-flows.md)).

The service exposes one WebSocket endpoint and one health endpoint. The endpoint path is
`PathWS` (`signaling/server/server.go:25`) and the health path is `PathHealthz`
(`signaling/server/server.go:28`); the HTTP routes are registered in `Routes`
(`signaling/server/server.go:76`).

References: `signaling/server/server.go:25`, `signaling/server/server.go:28`, `signaling/server/server.go:76`

## 2. Package layout

The service is a single binary built from module `webrtcdemo-signaling`. Entry point `main` calls
`run` (`signaling/main.go:28`, `signaling/main.go:34`), which parses flags, builds the logger, and
hands off to the server.

| Package | Files | Responsibility |
|---|---|---|
| `main` | `signaling/main.go` | flag parsing, logger setup, signal handling, lifecycle |
| `config` | `signaling/config/config.go` | defaults, validation, non-fatal warnings |
| `protocol` | `signaling/protocol/*.go` | message structs, error codes, heartbeat constants |
| `room` | `signaling/room/room.go`, `signaling/room/manager.go`, `signaling/room/peer.go` | rooms, seats, peers, expiry |
| `server` | `signaling/server/server.go`, `signaling/server/ws_handler.go` | HTTP routing, upgrade, message dispatch |
| `logging` | `signaling/logging/*.go` | log line format and file rotation |
| `util` | `signaling/util/roomid.go` | room id generation, validation, normalisation |

The manager is constructed by `NewManager` (`signaling/room/manager.go:59`) and the server by
`NewServer` (`signaling/server/server.go:47`), which registers the room-expiry callback
`SetExpiredHandler` (`signaling/room/manager.go:74`) and the grace-expiry callback
`SetGraceExpiredHandler` (`signaling/room/manager.go:82`).

References: `signaling/main.go:28`, `signaling/main.go:34`, `signaling/room/manager.go:59`, `signaling/server/server.go:47`, `signaling/room/manager.go:74`, `signaling/room/manager.go:82`

## 3. Room and seat state machine

A room holds at most `RoomMaxPeers` seats (`signaling/room/room.go:13`). The two seat identifiers are
`PeerID1` and `PeerID2` (`signaling/room/room.go:17`, `signaling/room/room.go:18`) and are selected by
seat index through `peerIDAt` (`signaling/room/room.go:102`).

A seat is the unit of state: it holds the current peer, an optional grace timer and a generation
counter (`signaling/room/room.go:26`). A seat is in exactly one of three states:

| State | Condition | Meaning |
|---|---|---|
| free | no peer assigned | the slot accepts a new connection |
| occupied | peer assigned, no grace timer | the connection is live |
| pending | peer assigned, grace timer armed | the connection dropped; the seat is reserved for reconnect |

State transitions:

| Transition | Function | Behaviour |
|---|---|---|
| free → occupied | `AddPeer` (`signaling/room/room.go:66`) | assigns the first free seat and increments the generation |
| occupied → pending | `MarkPending` (`signaling/room/room.go:113`) | arms the grace timer; a repeated call resets it |
| pending → occupied | `AddPeer` (`signaling/room/room.go:66`) | takes over the pending seat and keeps its peer id |
| pending → free | `ExpirePending` (`signaling/room/room.go:140`) | reclaims the seat only if peer and generation still match |
| occupied → free | `RemovePeer` (`signaling/room/room.go:159`) | explicit leave, or a drop with the grace window disabled |

The path from a dropped WebSocket is `MarkOffline` (`signaling/room/manager.go:225`). With a positive
grace period it marks the seat pending and returns without notifying the peer; with the grace period
disabled it removes the seat immediately. When the timer fires, `expireGrace`
(`signaling/room/manager.go:258`) reclaims the seat through `ExpirePending` and, if the room is not
empty, notifies the still-connected peer exactly once. The generation counter is what makes this
idempotent: after a takeover the old timer's generation no longer matches, so the callback returns
without notifying, as reported in reports/35-room-grace.md §2.2.

An explicit leave is handled by `Leave` (`signaling/room/manager.go:311`): it removes the peer,
destroys the room, and clears the room reference of the other peer. `Detach`
(`signaling/room/manager.go:333`) is the immediate-removal variant used when the grace window is off.

Counters surface the same mechanism: `seatTakeovers` (`signaling/room/manager.go:55`) and
`graceExpired` (`signaling/room/manager.go:54`) are read through `StatsGrace`
(`signaling/room/manager.go:303`). Liveness queries used by the caller are `PendingCount`
(`signaling/room/room.go:243`), `IsPending` (`signaling/room/room.go:258`) and `LivePeers`
(`signaling/room/room.go:212`).

Partners are resolved by `OtherPeer` (`signaling/room/room.go:181`); a pending seat still counts as
present, so a reconnecting peer can be paired with the peer that stayed online.

Expiry is evaluated by `IsExpired` (`signaling/room/room.go:295`): a room that is not full and has
seen no activity for the configured window is destroyed. `cleanupInterval`
(`signaling/room/manager.go:375`) computes the sweep period: 60 s by default, a quarter of the expiry
window when that is shorter, and 200 ms as a floor; `cleanupLoop`
(`signaling/room/manager.go:388`) drives it, and `sweep`
(`signaling/room/manager.go:407`) collects the expired rooms before calling the callback outside the
lock. `RemoveRoom` (`signaling/room/manager.go:348`) deletes the room and counts the destruction.

References: `signaling/room/room.go:13`, `signaling/room/room.go:17`, `signaling/room/room.go:18`, `signaling/room/room.go:26`, `signaling/room/room.go:66`, `signaling/room/room.go:102`, `signaling/room/room.go:113`, `signaling/room/room.go:140`, `signaling/room/room.go:159`, `signaling/room/room.go:181`, `signaling/room/room.go:212`, `signaling/room/room.go:243`, `signaling/room/room.go:258`, `signaling/room/room.go:295`, `signaling/room/manager.go:54`, `signaling/room/manager.go:55`, `signaling/room/manager.go:225`, `signaling/room/manager.go:258`, `signaling/room/manager.go:303`, `signaling/room/manager.go:311`, `signaling/room/manager.go:333`, `signaling/room/manager.go:348`, `signaling/room/manager.go:375`, `signaling/room/manager.go:388`, `signaling/room/manager.go:407`

## 4. Connection handling and routing

`HandleWS` (`signaling/server/ws_handler.go:34`) owns one connection from upgrade to cleanup. A
non-upgrade request is rejected with HTTP 400 and a plain-text hint
(`signaling/server/ws_handler.go:40`). After a successful upgrade it starts exactly one write loop
(`signaling/room/peer.go:196`) and one read loop (`signaling/room/peer.go:220`); all writes funnel
through the send queue, whose capacity is `PeerSendQueueSize` (`signaling/room/peer.go:23`).

`ReadLoop` sets the read limit to the configured maximum message size and refreshes the read deadline
on every frame, including control pongs (`signaling/room/peer.go:220`). Delivery uses `SendRaw`
(`signaling/room/peer.go:169`): the fast path enqueues immediately, the slow path waits up to the
send timeout and returns `ErrPeerBufferFull` (`signaling/room/peer.go:28`) rather than dropping
silently.

`handleMessage` (`signaling/server/ws_handler.go:174`) parses only the envelope and dispatches by
message type. The routing table:

| Incoming message | Handler | Forwarded |
|---|---|---|
| `create` | `handleCreate` (`signaling/server/ws_handler.go:212`) | no |
| `join` | `handleJoin` (`signaling/server/ws_handler.go:247`) | no |
| `offer`, `answer` | `forwardSDP` (`signaling/server/ws_handler.go:326`) | yes, verbatim |
| `ice` | `forwardIce` (`signaling/server/ws_handler.go:340`) | yes, verbatim |
| `natType` | `forwardNatType` (`signaling/server/ws_handler.go:359`) | yes, verbatim |
| `leave` | `handleLeave` (`signaling/server/ws_handler.go:435`) | sends `peerLeft` first |
| `ping` | `handlePing` (`signaling/server/ws_handler.go:457`) | replies with `pong` |
| server-only or unknown | `sendError` (`signaling/server/ws_handler.go:541`) | no |

Forwarded frames are not re-encoded: `forwardToPeer` (`signaling/server/ws_handler.go:379`) passes the
original bytes to `SendRaw`, and the log event name is chosen by `forwardEventName`
(`signaling/server/ws_handler.go:420`). The type classification helpers are `IsServerOnlyType`
(`signaling/protocol/message.go:155`), `IsKnownType` (`signaling/protocol/message.go:160`) and
`IsForwardType` (`signaling/protocol/message.go:171`).

Error responses are built by `NewErrorWithDetail` (`signaling/protocol/errors.go:31`) from the code
table (`signaling/protocol/errors.go:6`):

| Code | Trigger | Client duty |
|---|---|---|
| `ROOM_NOT_FOUND` | unknown room id (`signaling/server/ws_handler.go:272`) | terminal: stop retrying |
| `ROOM_FULL` | no free and no pending seat (`signaling/server/ws_handler.go:278`) | transient: retry with backoff |
| `ROOM_EXPIRED` | expiry window elapsed (`signaling/server/ws_handler.go:275`) | terminal |
| `INVALID_MESSAGE` | malformed JSON, missing field, bad room id, server-only type (`signaling/server/ws_handler.go:177`) | fix the client |
| `NOT_IN_ROOM` | forwarding before a successful create or join (`signaling/server/ws_handler.go:382`) | fix the client |
| `INTERNAL_ERROR` | room creation or join failure (`signaling/server/ws_handler.go:221`) | retry later |

`sendError` (`signaling/server/ws_handler.go:541`) logs `join_rejected` for join failures and
`error_sent` otherwise.

Connection teardown is classified by `classifyReadError` (`signaling/server/ws_handler.go:145`), which
distinguishes a server-initiated close from a read timeout, a read-limit overflow and a peer close.
The teardown then calls `MarkOffline` and closes the peer (`signaling/server/ws_handler.go:87`).

References: `signaling/server/ws_handler.go:34`, `signaling/server/ws_handler.go:40`, `signaling/server/ws_handler.go:87`, `signaling/server/ws_handler.go:145`, `signaling/server/ws_handler.go:174`, `signaling/server/ws_handler.go:177`, `signaling/server/ws_handler.go:212`, `signaling/server/ws_handler.go:221`, `signaling/server/ws_handler.go:247`, `signaling/server/ws_handler.go:272`, `signaling/server/ws_handler.go:275`, `signaling/server/ws_handler.go:278`, `signaling/server/ws_handler.go:326`, `signaling/server/ws_handler.go:340`, `signaling/server/ws_handler.go:359`, `signaling/server/ws_handler.go:379`, `signaling/server/ws_handler.go:382`, `signaling/server/ws_handler.go:420`, `signaling/server/ws_handler.go:435`, `signaling/server/ws_handler.go:457`, `signaling/server/ws_handler.go:541`, `signaling/room/peer.go:23`, `signaling/room/peer.go:28`, `signaling/room/peer.go:169`, `signaling/room/peer.go:196`, `signaling/room/peer.go:220`, `signaling/protocol/message.go:155`, `signaling/protocol/message.go:160`, `signaling/protocol/message.go:171`, `signaling/protocol/errors.go:6`, `signaling/protocol/errors.go:31`

## 5. Health endpoint

`HandleHealthz` (`signaling/server/server.go:102`) returns a JSON object. Its fields:

| Field | Source |
|---|---|
| `status` | constant `ok` |
| `version` | `Version` (`signaling/server/server.go:22`) |
| `addr` | configured listen address |
| `uptimeSec` | process uptime in seconds |
| `rooms` | `Count` (`signaling/room/manager.go:94`) |
| `roomIds` | `RoomIDs` (`signaling/room/manager.go:101`) |
| `activeConns` | current connections |
| `totalConns` | cumulative connections |
| `roomsCreated`, `roomsDestroyed` | `Stats` (`signaling/room/manager.go:112`) |
| `roomExpirySec` | configured expiry window |
| `roomGraceSec` | configured grace period in seconds |
| `seatTakeovers`, `graceExpired` | `StatsGrace` (`signaling/room/manager.go:303`) |
| `maxMessageBytes` | configured frame limit |
| `stunUrl`, `turnUrl` | ICE server configuration handed to clients |
| `serverTimeMillis` | current server time in Unix milliseconds |

References: `signaling/server/server.go:22`, `signaling/server/server.go:102`, `signaling/room/manager.go:94`, `signaling/room/manager.go:101`, `signaling/room/manager.go:112`, `signaling/room/manager.go:303`

## 6. Configuration and unit

Defaults live in `config` (`signaling/config/config.go:19`): `DefaultListenAddr` is `:8443`
(`signaling/config/config.go:19`), `DefaultRoomExpirySec` is 1800 s (`signaling/config/config.go:26`),
`MaxMessageSize` is 65 536 B (`signaling/config/config.go:29`), `DefaultWriteTimeout` is 10 s
(`signaling/config/config.go:32`), `DefaultPongWait` is three ping intervals
(`signaling/config/config.go:38`), `DefaultSendTimeout` is 5 s (`signaling/config/config.go:42`) and
`DefaultRoomGrace` is 90 s (`signaling/config/config.go:54`). `MinRecommendedRoomGrace`
(`signaling/config/config.go:57`) is the warning threshold, and `PlaceholderHost`
(`signaling/config/config.go:60`) detects the documentation example address.

Flags are declared in `signaling/main.go:38` and applied in the same function (`signaling/main.go:59`).
The service accepts `-addr`, `-stun`, `-turn`, `-user`, `-log`, `-log-level`, `-room-expiry`,
`-room-grace`, `-max-message-bytes`, `-write-timeout`, `-pong-wait`, `-send-timeout` and `-version`.
TURN credentials are parsed from the `-user` value by `SetTurnUser` (`signaling/config/config.go:99`);
validation is `Validate` (`signaling/config/config.go:110`) and the non-fatal advisories are `Warnings`
(`signaling/config/config.go:146`). Startup logs the effective configuration as `server_start`
(`signaling/main.go:101`), including `room_grace_ms` (`signaling/main.go:95`).

The unit in the repository is `deploy/signaling.service`. Its `ExecStart`
(`deploy/signaling.service:28`) pins the grace period explicitly:

```sh
ExecStart=/opt/signaling/signaling -addr :8443 -stun stun:<host>:3478 -turn 'turn:<host>:3478?transport=udp' -user <user>:<pass> -log /var/log/signaling/signaling.log -room-grace 90s
```

`WorkingDirectory` is `deploy/signaling.service:27`, and restart behaviour is `Restart=always` with
`RestartSec=3` (`deploy/signaling.service:29`, `deploy/signaling.service:30`). Graceful shutdown is
`Shutdown` (`signaling/server/server.go:166`), driven by SIGINT or SIGTERM
(`signaling/main.go:113`).

HOST: `/opt/signaling/signaling` is the installed binary path used by the unit; the repository copy
matches the deployed unit parameter for parameter (see `deploy/signaling.service:1` and
reports/12-deploy-signaling.md §4). HOST: `/var/log/signaling/signaling.log` is the log target set on
`deploy/signaling.service:28`; the rotating writer creates its directory and defaults to 2 MiB per
file with 3 files kept (`signaling/logging/rotating.go:14`, `signaling/logging/rotating.go:16`,
`signaling/logging/rotating.go:43`), and it is installed by `Setup`
(`signaling/logging/logging.go:20`).

References: `signaling/main.go:38`, `signaling/main.go:59`, `signaling/main.go:95`, `signaling/main.go:101`, `signaling/main.go:113`, `signaling/config/config.go:19`, `signaling/config/config.go:26`, `signaling/config/config.go:29`, `signaling/config/config.go:32`, `signaling/config/config.go:38`, `signaling/config/config.go:42`, `signaling/config/config.go:54`, `signaling/config/config.go:57`, `signaling/config/config.go:60`, `signaling/config/config.go:99`, `signaling/config/config.go:110`, `signaling/config/config.go:146`, `deploy/signaling.service:1`, `deploy/signaling.service:27`, `deploy/signaling.service:28`, `deploy/signaling.service:29`, `deploy/signaling.service:30`, `signaling/server/server.go:166`, `signaling/logging/logging.go:20`, `signaling/logging/rotating.go:14`, `signaling/logging/rotating.go:16`, `signaling/logging/rotating.go:43`

## 7. Coturn relationship

The service does not relay media. It only hands the client an ICE server list: the STUN URL, the TURN
URL and the TURN credentials are read from configuration and sent in the `created` response
(`signaling/server/ws_handler.go:226`) and in the `joined` response
(`signaling/server/ws_handler.go:287`).

The TURN server itself is configured outside the repository. The repository copy is
`deploy/turnserver.conf` and the frozen values are:

| Setting | Value | Line |
|---|---|---|
| listening port | 3478 | `deploy/turnserver.conf:4` |
| listening address | internal address | `deploy/turnserver.conf:5` |
| external address | public/internal pair | `deploy/turnserver.conf:6` |
| relay address | internal address | `deploy/turnserver.conf:7` |
| relay port range | 49152-49200 | `deploy/turnserver.conf:8`, `deploy/turnserver.conf:9` |
| realm, server name | webrtc-demo | `deploy/turnserver.conf:10`, `deploy/turnserver.conf:11` |
| long-term credentials | enabled | `deploy/turnserver.conf:14` |
| user | demo | `deploy/turnserver.conf:15` |
| total quota | 45 | `deploy/turnserver.conf:16` |

The fingerprint option is inert here: `deploy/turnserver.conf:12` records that coturn 4.6.1 rejects
the `use-fingerprint` spelling and that the effective name is `fingerprint`. Relay-address selection
is the one configuration value with a measured failure mode (an external address in the relay field
yields bind failures and allocation error 508); the evidence is reports/06-coturn.md §5.

References: `signaling/server/ws_handler.go:226`, `signaling/server/ws_handler.go:287`, `deploy/turnserver.conf:4`, `deploy/turnserver.conf:5`, `deploy/turnserver.conf:6`, `deploy/turnserver.conf:7`, `deploy/turnserver.conf:8`, `deploy/turnserver.conf:9`, `deploy/turnserver.conf:10`, `deploy/turnserver.conf:11`, `deploy/turnserver.conf:12`, `deploy/turnserver.conf:14`, `deploy/turnserver.conf:15`, `deploy/turnserver.conf:16`

## 8. Evidence index

| Claim | Citation | Verification artifact |
|---|---|---|
| endpoint paths and routes | `signaling/server/server.go:76` | reports/12-deploy-signaling.md §3 |
| room capacity is two seats | `signaling/room/room.go:13` | reports/09-go-signaling.md §3 |
| drop keeps the seat for the grace window | `signaling/room/manager.go:225` | reports/35-room-grace.md §2.2 |
| grace expiry notifies the peer once | `signaling/room/manager.go:258` | reports/35-room-grace.md §3.4 |
| forwarding is byte-for-byte | `signaling/server/ws_handler.go:379` | reports/09-go-signaling.md §4 |
| error code table | `signaling/protocol/errors.go:6` | reports/09-go-signaling.md §5 |
| health fields | `signaling/server/server.go:102` | reports/38-signaling-grace-deploy.md §3 |
| grace period pinned in the unit | `deploy/signaling.service:28` | reports/38-signaling-grace-deploy.md §2 |
| TURN relay settings | `deploy/turnserver.conf:7` | reports/06-coturn.md §5 |

## 9. Open items

1. `ReconnectDelay` (`signaling/protocol/heartbeat.go:15`) and `MaxReconnectAttempts`
   (`signaling/protocol/heartbeat.go:17`) are declared-only: they have no call site in the repository.
   The client-side reconnect policy is the one measured in [05-protocols.md](05-protocols.md), and these two constants
   are `unverified` as behaviour.
2. The Go log event names in this document are cited directly from the emitting source, because the
   generated [log-events.md](_generated/log-events.md) covers the Kotlin and native layers only. That is a coverage gap in the
   generated artifact, not a divergence.
3. The `room_grace_ms` startup field and the health counters are verified from source only; no
   deployed instance was queried while writing this document.

References: `signaling/protocol/heartbeat.go:15`, `signaling/protocol/heartbeat.go:17`, `signaling/main.go:95`
