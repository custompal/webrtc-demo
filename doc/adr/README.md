# Legacy ADR directory (`doc/adr/`)

> Archived 2026-09-17. Owner: architect. Current documentation standard: `doc/design/SPEC.md`.

The seven ADRs were moved verbatim to `doc/archive/adr/`. Every original **file** path keeps a one-line stub
(`ADR-001-…md` … `ADR-007-…md`, one line each) so historical references in `reports/**` and in the archived
documents stay resolvable. This README is a new file and is not a stub for a moved file.

| Legacy path (stub, 1 line) | Archive copy (verbatim) | Current equivalent |
|---|---|---|
| `doc/adr/ADR-001-use-libwebrtc-and-source-build.md` | `doc/archive/adr/ADR-001-use-libwebrtc-and-source-build.md` | `doc/design/02-architecture.md` §7, `doc/design/07-build-and-deploy.md` |
| `doc/adr/ADR-002-custom-video-encoder-for-dynamic-bitrate.md` | `doc/archive/adr/ADR-002-custom-video-encoder-for-dynamic-bitrate.md` | `doc/design/03-app-architecture.md`, `doc/design/08-issues-and-solutions.md` |
| `doc/adr/ADR-003-1to1-p2p-first.md` | `doc/archive/adr/ADR-003-1to1-p2p-first.md` | `doc/design/01-requirements.md`, `doc/design/04-signaling-service.md` |
| `doc/adr/ADR-004-split-runtime-and-build-vms.md` | `doc/archive/adr/ADR-004-split-runtime-and-build-vms.md` | `doc/design/07-build-and-deploy.md` |
| `doc/adr/ADR-005-go-signaling-and-rfc5780-nat-test.md` | `doc/archive/adr/ADR-005-go-signaling-and-rfc5780-nat-test.md` | `doc/design/04-signaling-service.md`, `doc/design/05-protocols.md` |
| `doc/adr/ADR-006-code-design-decisions.md` | `doc/archive/adr/ADR-006-code-design-decisions.md` | `doc/design/03-app-architecture.md`, `doc/design/10-code-map.md` |
| `doc/adr/ADR-007-code-style-and-comment-rules.md` | `doc/archive/adr/ADR-007-code-style-and-comment-rules.md` | `doc/design/11-coding-standards.md` |

Unlike the `doc/*.md` archive copies, the ADR copies received **no** header line: they are byte-identical to
their `git HEAD:doc/adr/<file>` originals (verified by sha256 for all 7 files).
