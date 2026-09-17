# Signalling messages (Go <-> Kotlin)

> **GENERATED — do not edit.** Regenerate with `bash scripts/gen-doc-tables.sh`.
> Generator: `scripts/gen-doc-tables.sh` (sha256 `e7951ef4e950858588103cb1e5714d390612fd8db625f570759c20e5c8c156c5`)
> Deterministic: no timestamp is embedded, so repeated runs are byte-identical.
> Sources (sha256 of the exact revision this table was built from):
> - `signaling/protocol/message.go` — sha256 `35e671787e561d3023c859f578627f36d922c0a9e919d61cb2627429ecc35641`
> - `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` — sha256 `4fd325e1f0c0646b50b719c94750c459467e9957d43151f68c1b104a07f3c9b1`

## 1. Message types (Go side)

Extracted from the `Type*` constants in `signaling/protocol/message.go`.

| `type` value | Go constant | Direction | Source |
|---|---|---|---|
| `TypeCreate` | `create` | C→S | `signaling/protocol/message.go` |
| `TypeCreated` | `created` | S→C | `signaling/protocol/message.go` |
| `TypeJoin` | `join` | C→S | `signaling/protocol/message.go` |
| `TypeJoined` | `joined` | S→C | `signaling/protocol/message.go` |
| `TypeError` | `error` | S→C | `signaling/protocol/message.go` |
| `TypeOffer` | `offer` | C→S→C | `signaling/protocol/message.go` |
| `TypeAnswer` | `answer` | C→S→C | `signaling/protocol/message.go` |
| `TypeIce` | `ice` | C→S→C | `signaling/protocol/message.go` |
| `TypeNatType` | `natType` | C→S→C | `signaling/protocol/message.go` |
| `TypePeerJoined` | `peerJoined` | S→C | `signaling/protocol/message.go` |
| `TypePeerLeft` | `peerLeft` | S→C | `signaling/protocol/message.go` |
| `TypeLeave` | `leave` | C→S | `signaling/protocol/message.go` |
| `TypePing` | `ping` | C→S | `signaling/protocol/message.go` |
| `TypePong` | `pong` | S→C | `signaling/protocol/message.go` |

## 2. Message types (Kotlin side)

Extracted from the `@SerialName` annotations in `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt`.

| `type` value | Kotlin class | Kind | Source |
|---|---|---|---|
| `create` | `Create` | object | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `created` | `Created` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `join` | `Join` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `joined` | `Joined` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `peerJoined` | `PeerJoined` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `peerLeft` | `PeerLeft` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `offer` | `Offer` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `answer` | `Answer` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `ice` | `Ice` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `natType` | `NatTypeMessage` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `leave` | `Leave` | object | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `error` | `ServerError` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `ping` | `Ping` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |
| `pong` | `Pong` | class | `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt` |

## 3. Field-level tables

### `answer`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `SDP` | `string` | `sdp` | no | `sdp` | `String` | no |

### `create`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|

### `created`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `RoomID` | `string` | `roomId` | no | `roomId` | `String` | no |
| `StunURL` | `string` | `stunUrl` | no | `stunUrl` | `String` | no |
| `TurnCredential` | `string` | `turnCredential` | no | `turnCredential` | `String` | no |
| `TurnURL` | `string` | `turnUrl` | no | `turnUrl` | `String` | no |
| `TurnUsername` | `string` | `turnUsername` | no | `turnUsername` | `String` | no |

### `error`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `Code` | `string` | `code` | no | `code` | `String` | no |
| `Message` | `string` | `message` | no | `message` | `String` | no |

### `ice`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `Candidate` | `string` | `candidate` | no | `candidate` | `String` | no |
| `SDPMLineIndex` | `*int` | `sdpMLineIndex` | yes | `sdpMLineIndex` | `Int` | yes |
| `SDPMid` | `string` | `sdpMid` | yes | `sdpMid` | `String` | yes |

### `join`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `RoomID` | `string` | `roomId` | no | `roomId` | `String` | no |

### `joined`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `PeerID` | `string` | `peerId` | no | `peerId` | `String` | no |
| `RoomID` | `string` | `roomId` | no | `roomId` | `String` | no |
| `StunURL` | `string` | `stunUrl` | no | `stunUrl` | `String` | no |
| `TurnCredential` | `string` | `turnCredential` | no | `turnCredential` | `String` | no |
| `TurnURL` | `string` | `turnUrl` | no | `turnUrl` | `String` | no |
| `TurnUsername` | `string` | `turnUsername` | no | `turnUsername` | `String` | no |

### `leave`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|

### `natType`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `NatType` | `string` | `natType` | no | `natType` | `String` | no |

### `offer`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `SDP` | `string` | `sdp` | no | `sdp` | `String` | no |

### `peerJoined`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `PeerID` | `string` | `peerId` | no | `peerId` | `String` | no |

### `peerLeft`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `PeerID` | `string` | `peerId` | no | `peerId` | `String` | no |

### `ping`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `Timestamp` | `int64` | `timestamp` | no | `timestamp` | `Long` | no |

### `pong`

| Field (Go) | Go type | JSON key | omitempty | Field (Kotlin) | Kotlin type | nullable |
|---|---|---|---|---|---|---|
| `Timestamp` | `int64` | `timestamp` | no | `timestamp` | `Long` | no |

## 4. Divergence: Go vs Kotlin type sets

| Divergence | `type` values |
|---|---|
| In Go, missing in Kotlin | _(none)_ |
| In Kotlin, missing in Go | _(none)_ |

**Result: type sets are identical** (14 Go types, 14 Kotlin types).

## 5. NAT type enum

| Go constant | value |
|---|---|
| `NatOpen` | `Open` |
| `NatFullCone` | `FullCone` |
| `NatRestrictedCone` | `RestrictedCone` |
| `NatPortRestrictedCone` | `PortRestrictedCone` |
| `NatSymmetric` | `Symmetric` |
| `NatUnknown` | `Unknown` |

## 6. Field-level divergence (JSON keys per type)

| `type` | Divergence |
|---|---|

_No rows above means every Go JSON key has a matching Kotlin property._
