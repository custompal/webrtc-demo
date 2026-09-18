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
