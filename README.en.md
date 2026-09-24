> [中文（默认）](README.md) · English

# webrtc-demo — documentation entry point

> Status: draft · Owner: writer-app · Task: t23
> Documentation standard: [doc/design/SPEC.md](doc/design/SPEC.md)
> Frozen artefact set: [reports/54-docs-freeze-manifest.md](reports/54-docs-freeze-manifest.md)

An Android one-to-one WebRTC demonstration: a Kotlin application on libwebrtc with a self-built VP9 encoder,
a Go signalling service, and a coturn relay. The current documentation set is `doc/design/`; everything else
under `doc/` is archived history.

## 1. Start here

| Goal | Document |
|---|---|
| What the product must do | [01-requirements.md](doc/design/01-requirements.md) |
| System shape and deployment | [02-architecture.md](doc/design/02-architecture.md) |
| Android application internals | [03-app-architecture.md](doc/design/03-app-architecture.md) |
| Signalling service internals | [04-signaling-service.md](doc/design/04-signaling-service.md) |
| Wire protocol and JNI contract | [05-protocols.md](doc/design/05-protocols.md) |
| Call flows end to end | [06-flows.md](doc/design/06-flows.md) |
| Build, deploy and release | [07-build-and-deploy.md](doc/design/07-build-and-deploy.md) |
| Past defects and rejected fixes | [08-issues-and-solutions.md](doc/design/08-issues-and-solutions.md) |
| What is verified and what is not | [09-verification-and-limitations.md](doc/design/09-verification-and-limitations.md) |
| Where a symptom lives in the code | [10-code-map.md](doc/design/10-code-map.md) |
| Coding rules before changing code | [11-coding-standards.md](doc/design/11-coding-standards.md) |
| How to write documentation here | [SPEC.md](doc/design/SPEC.md) |

## 2. Generated reference tables

Four tables are generated from the code and must never be edited by hand —
[signaling-messages.md](doc/design/_generated/signaling-messages.md),
[log-events.md](doc/design/_generated/log-events.md),
[jni-contract.md](doc/design/_generated/jni-contract.md) and
[host-commands.md](doc/design/_generated/host-commands.md). Regenerate them with
`bash scripts/gen-doc-tables.sh`.

## 3. Build and deployment

The build recipe, its gates, the release chain and the host-only publish step are documented in
[07-build-and-deploy.md](doc/design/07-build-and-deploy.md). The application build is driven by
`scripts/build_app.sh`.

## 4. Coding standards

Language conventions, the change-safety checklist and the documentation gate are in
[11-coding-standards.md](doc/design/11-coding-standards.md).

## 5. Verification

The verification matrix, the known limitations and the retest methods are in
[09-verification-and-limitations.md](doc/design/09-verification-and-limitations.md). The documentation gate
itself is `bash scripts/doc-verify.sh`; it must report zero failures before a document change is accepted.
The frozen checker revision and the frozen artefact set are recorded in
[reports/54-docs-freeze-manifest.md](reports/54-docs-freeze-manifest.md).

## 6. Documentation index and legacy material

The index of the current set is [doc/design/README.md](doc/design/README.md). The archived legacy documents and
the mapping from legacy paths to current chapters are described in [doc/README.md](doc/README.md).

## 7. Browser demo

Besides the Android application, the repository ships a **purely static browser call demo** (desktop Chrome
only) that dials the same signalling service; every step is visible and exportable in the browser console and
on the page panel.

* Get started: on the host run `bash scripts/serve-web-demo.sh start`; forward **only port 8081** in the
  VSCode PORTS panel; open `http://localhost:8081/` in Chrome, then click Create (the browser hosts) or enter
  the 6-character room id shown by the app and click Join.
* Signalling is dialled directly by the page as `ws://47.238.144.66:8443/ws` (plaintext; port 8443 is not
  forwarded).
* Six panel areas: signalling timeline, SDP source and parse, ICE candidates and the selected pair,
  state-machine transitions, 1 Hz RTP/RTCP stats, events and errors; export to JSON/CSV.
* Runbook and failure matrix: [web/README.md](web/README.md). Delivery report and independent verification:
  [reports/71-browser-call-demo.md](reports/71-browser-call-demo.md),
  [reports/72-browser-demo-verification.md](reports/72-browser-demo-verification.md).
* Known limits: desktop Chrome only; the page must be opened over `http://localhost`; mDNS candidates may be
  unresolvable; TURN relay port range 49152-49200 (concurrency cap 45).
