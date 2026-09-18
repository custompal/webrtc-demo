> [中文（默认）](README.md) · English

# Documentation set — index

> Status: draft · Owner: writer-app · Task: t23
> Documentation standard: [SPEC.md](SPEC.md)
> Legacy material and the legacy-to-current mapping: [../README.md](../README.md)
> Repository entry point: [../../README.md](../../README.md)

## 1. Writing standard

[SPEC.md](SPEC.md) is normative: directory conventions, the bilingual terminology, the citation format, the
document template, the checker judgement rules, change control and the delivery gate. Read it before editing
any chapter.

## 2. Chapters

| # | Document | Scope |
|---|---|---|
| 01 | [01-requirements.md](01-requirements.md) | Functional and non-functional requirements with citations and status |
| 02 | [02-architecture.md](02-architecture.md) | Components, planes, deployment topology, document map |
| 03 | [03-app-architecture.md](03-app-architecture.md) | Application layers, threads, lifecycle, recovery, switches |
| 04 | [04-signaling-service.md](04-signaling-service.md) | Service layout, room and seat state machine, routing, health |
| 05 | [05-protocols.md](05-protocols.md) | Field-level messages, reconnect policy, ICE/TURN/SDP, JNI contract |
| 06 | [06-flows.md](06-flows.md) | Mermaid flows with step lists and device-log evidence |
| 07 | [07-build-and-deploy.md](07-build-and-deploy.md) | Toolchain, build stages, invariants, release chain, host services |
| 08 | [08-issues-and-solutions.md](08-issues-and-solutions.md) | Defect history, root causes, rejected and disproven alternatives |
| 09 | [09-verification-and-limitations.md](09-verification-and-limitations.md) | Verification matrix, known limitations, errata |
| 10 | [10-code-map.md](10-code-map.md) | Code map, report index, symptom-to-code lookup |
| 11 | [11-coding-standards.md](11-coding-standards.md) | Language conventions, change-safety checklist, gate rules |

## 3. Generated tables

[signaling-messages.md](_generated/signaling-messages.md),
[log-events.md](_generated/log-events.md),
[jni-contract.md](_generated/jni-contract.md) and
[host-commands.md](_generated/host-commands.md) are generated from the code by
`bash scripts/gen-doc-tables.sh` and must not be edited by hand.

## 4. Legacy

Everything under `doc/` outside this directory is archived history kept as one-line stubs so historical
references in reports do not dangle; the full mapping is in [../README.md](../README.md).
