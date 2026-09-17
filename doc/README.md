# Legacy documentation (`doc/`)

> Archived 2026-09-17. Owner: architect. Current documentation standard: `doc/design/SPEC.md`.

`doc/` is **legacy history**, not current guidance.

## 1. Path roles

| Path | Meaning |
|---|---|
| `doc/design/**` | **Current** documentation set (canonical). Reached through the repository-relative symlink `docs -> doc/design` (see §4). |
| `doc/*.md`, `doc/adr/*.md` | One-line **stubs** kept at every original legacy path so historical references in `reports/**` do not dangle. Each stub names its archive copy and the current replacement. |
| `doc/archive/*.md` | The 15 legacy `doc/*.md` documents, moved **verbatim**. A single line was added at the top of each file: `> **Archived 2026-09-17** — superseded by doc/design/; …`. Nothing else changed. |
| `doc/archive/adr/*.md` | The 7 legacy ADRs (ADR-001 … ADR-007), moved **byte-identical** (no header line added). |
| `doc/README.md`, `doc/adr/README.md` | New index files; they are not stubs for moved files. |
| `doc/design/_generated/**` | Machine-generated tables (fixed names: `signaling-messages.md`, `log-events.md`, `jni-contract.md`, `host-commands.md`); never hand-edited. |

Why the stubs exist: `reports/**` cites legacy paths heavily (for example `doc/14-interface-contract.md` is
referenced 74 times). Removing the files would make those citations unresolvable, and the reports are
append-only evidence. Each stub keeps the path alive while pointing the reader at the current document.

## 2. Archive inventory — before and after (complete)

The legacy set was enumerated with `find doc -type f` before any move. It contains **only Markdown**: 15 files
directly under `doc/` plus 7 files under `doc/adr/` (22 total). There are no `doc/*.json`, `doc/*.txt` or other
non-Markdown files, and no other nested directories besides `adr/`.

| # | Before (legacy, tracked) | After (verbatim archive copy) | Stub left at the original path |
|---|---|---|---|
| 1 | `doc/00-overview.md` | `doc/archive/00-overview.md` | `doc/00-overview.md` |
| 2 | `doc/01-cloud-infra.md` | `doc/archive/01-cloud-infra.md` | `doc/01-cloud-infra.md` |
| 3 | `doc/02-architecture.md` | `doc/archive/02-architecture.md` | `doc/02-architecture.md` |
| 4 | `doc/03-implementation-plan.md` | `doc/archive/03-implementation-plan.md` | `doc/03-implementation-plan.md` |
| 5 | `doc/04-glossary.md` | `doc/archive/04-glossary.md` | `doc/04-glossary.md` |
| 6 | `doc/05-code-design.md` | `doc/archive/05-code-design.md` | `doc/05-code-design.md` |
| 7 | `doc/06-webrtc-dynamic-bitrate-internals.md` | `doc/archive/06-webrtc-dynamic-bitrate-internals.md` | `doc/06-webrtc-dynamic-bitrate-internals.md` |
| 8 | `doc/07-deepseek-harness-guide.md` | `doc/archive/07-deepseek-harness-guide.md` | `doc/07-deepseek-harness-guide.md` |
| 9 | `doc/08-libwebrtc-android-build.md` | `doc/archive/08-libwebrtc-android-build.md` | `doc/08-libwebrtc-android-build.md` |
| 10 | `doc/09-signaling-protocol-spec.md` | `doc/archive/09-signaling-protocol-spec.md` | `doc/09-signaling-protocol-spec.md` |
| 11 | `doc/10-android-ui-design.md` | `doc/archive/10-android-ui-design.md` | `doc/10-android-ui-design.md` |
| 12 | `doc/11-native-implementation.md` | `doc/archive/11-native-implementation.md` | `doc/11-native-implementation.md` |
| 13 | `doc/12-backend-implementation.md` | `doc/archive/12-backend-implementation.md` | `doc/12-backend-implementation.md` |
| 14 | `doc/13-agent-task-spec.md` | `doc/archive/13-agent-task-spec.md` | `doc/13-agent-task-spec.md` |
| 15 | `doc/14-interface-contract.md` | `doc/archive/14-interface-contract.md` | `doc/14-interface-contract.md` |
| 16 | `doc/adr/ADR-001-use-libwebrtc-and-source-build.md` | `doc/archive/adr/ADR-001-use-libwebrtc-and-source-build.md` | `doc/adr/ADR-001-use-libwebrtc-and-source-build.md` |
| 17 | `doc/adr/ADR-002-custom-video-encoder-for-dynamic-bitrate.md` | `doc/archive/adr/ADR-002-custom-video-encoder-for-dynamic-bitrate.md` | `doc/adr/ADR-002-custom-video-encoder-for-dynamic-bitrate.md` |
| 18 | `doc/adr/ADR-003-1to1-p2p-first.md` | `doc/archive/adr/ADR-003-1to1-p2p-first.md` | `doc/adr/ADR-003-1to1-p2p-first.md` |
| 19 | `doc/adr/ADR-004-split-runtime-and-build-vms.md` | `doc/archive/adr/ADR-004-split-runtime-and-build-vms.md` | `doc/adr/ADR-004-split-runtime-and-build-vms.md` |
| 20 | `doc/adr/ADR-005-go-signaling-and-rfc5780-nat-test.md` | `doc/archive/adr/ADR-005-go-signaling-and-rfc5780-nat-test.md` | `doc/adr/ADR-005-go-signaling-and-rfc5780-nat-test.md` |
| 21 | `doc/adr/ADR-006-code-design-decisions.md` | `doc/archive/adr/ADR-006-code-design-decisions.md` | `doc/adr/ADR-006-code-design-decisions.md` |
| 22 | `doc/adr/ADR-007-code-style-and-comment-rules.md` | `doc/archive/adr/ADR-007-code-style-and-comment-rules.md` | `doc/adr/ADR-007-code-style-and-comment-rules.md` |

Invariants a reviewer can check (used by `scripts/doc-verify.sh` rule V5):

1. Every path in column 1 has a one-line stub at the same path (22 stubs).
2. Every stub names `doc/archive/<same file>`, and that archive file exists.
3. Archive content is verbatim: for items 1–15, `sha256(tail -n +3 archive)` equals
   `sha256(git show HEAD:doc/<file>)`; for items 16–22 the whole file is byte-identical to
   `git show HEAD:doc/adr/<file>`. All 22 comparisons were run and matched.
4. `find doc -type f` contains no other legacy file (no non-Markdown legacy file exists).

## 3. Where the old content went

| Legacy document | Current replacement |
|---|---|
| `doc/00-overview.md` | `doc/design/01-requirements.md`, `doc/design/02-architecture.md` |
| `doc/01-cloud-infra.md` | `doc/design/07-build-and-deploy.md`, `doc/design/09-verification-and-limitations.md` |
| `doc/02-architecture.md` | `doc/design/02-architecture.md`, `doc/design/03-app-architecture.md`, `doc/design/04-signaling-service.md` |
| `doc/03-implementation-plan.md` | `doc/design/01-requirements.md` (status per requirement), `doc/design/10-code-map.md` |
| `doc/04-glossary.md` | `doc/design/SPEC.md` §3 |
| `doc/05-code-design.md` | `doc/design/02-architecture.md`, `doc/design/03-app-architecture.md`, `doc/design/10-code-map.md`, `doc/design/11-coding-standards.md` |
| `doc/06-webrtc-dynamic-bitrate-internals.md` | `doc/design/03-app-architecture.md` §Encoder, `doc/design/08-issues-and-solutions.md` |
| `doc/07-deepseek-harness-guide.md` | `doc/design/07-build-and-deploy.md` |
| `doc/08-libwebrtc-android-build.md` | `doc/design/07-build-and-deploy.md` §Toolchain, §Build invariants |
| `doc/09-signaling-protocol-spec.md` | `doc/design/05-protocols.md`, `doc/design/_generated/` |
| `doc/10-android-ui-design.md` | `doc/design/03-app-architecture.md` §UI layer, `doc/design/06-flows.md` |
| `doc/11-native-implementation.md` | `doc/design/02-architecture.md` §Native, `doc/design/08-issues-and-solutions.md` |
| `doc/12-backend-implementation.md` | `doc/design/04-signaling-service.md` |
| `doc/13-agent-task-spec.md` | `doc/design/09-verification-and-limitations.md`, `doc/design/10-code-map.md` |
| `doc/14-interface-contract.md` | `doc/design/SPEC.md` §4, `doc/design/05-protocols.md`, `doc/design/09-verification-and-limitations.md` (errata D-1..D-6) |
| `doc/adr/ADR-001-use-libwebrtc-and-source-build.md` | `doc/design/02-architecture.md` §7, `doc/design/07-build-and-deploy.md` |
| `doc/adr/ADR-002-custom-video-encoder-for-dynamic-bitrate.md` | `doc/design/03-app-architecture.md`, `doc/design/08-issues-and-solutions.md` |
| `doc/adr/ADR-003-1to1-p2p-first.md` | `doc/design/01-requirements.md`, `doc/design/04-signaling-service.md` |
| `doc/adr/ADR-004-split-runtime-and-build-vms.md` | `doc/design/07-build-and-deploy.md` |
| `doc/adr/ADR-005-go-signaling-and-rfc5780-nat-test.md` | `doc/design/04-signaling-service.md`, `doc/design/05-protocols.md` |
| `doc/adr/ADR-006-code-design-decisions.md` | `doc/design/03-app-architecture.md`, `doc/design/10-code-map.md` |
| `doc/adr/ADR-007-code-style-and-comment-rules.md` | `doc/design/11-coding-standards.md` |

The per-ADR view (with the same mapping) is also in `doc/adr/README.md`.

## 4. Canonical path and the compatibility symlink

* **Canonical**: `doc/design/**`. Body text and cross-document links use this form.
* **Compatibility**: the repository-relative symlink `docs -> doc/design`, created because several task
  contracts were frozen with the `docs/<file>.md` spelling and their `inScope` could not be edited after
  dispatch. Both spellings resolve to the same files; `docs/` must not become a real directory and must not
  hold content that is not part of the canonical set (`doc/design/SPEC.md` §2, rules R1/R2).
* A later cleanup can replace the symlink by renaming the directory and updating the contract text; until then
  the symlink is intentional and documented here.

## 5. Divergence from the legacy text

Known divergence between the legacy documents and the shipped implementation is registered as errata D-1..D-6
in `reports/99-final-report.md` §15.5 and summarised in `doc/design/09-verification-and-limitations.md`.
**Where a legacy document and the code disagree, the code and the reports win.**
