# 07 — Build and Deploy

> Status: draft · Owner: writer-ops · Task: t5
> Evidence base: reports/37-t76-build-publish.md, reports/37-t72-build-publish.md, reports/41-apk-http-ownership.md, reports/26-libvpx-runtime-cpu-detect.md, reports/10-app-build.md, doc/design/_generated/host-commands.md
> Doc standard: `doc/design/SPEC.md`

## 1. Scope

This document covers how the Android application and the Go signaling binary are built, how the build is
proven to be a real build, and how the resulting APK is released to the host services.

It deliberately does not cover: the design of the application layers (see the app architecture document),
the signaling protocol (see the protocols document), or the defect history (see the issues document).

A recurring distinction in this document is **where a command runs**. The build script is a host-only
script; the container has no host workspace tree (HOST: `/opt/dsh-workspaces`). Every command below
therefore states its execution location.

## 2. Toolchain versions

| Component | Version | Citation |
|---|---|---|
| JDK | 17 | `../env.sh:37` |
| Gradle wrapper | 8.7 | `gradle/wrapper/gradle-wrapper.properties:3` |
| Android Gradle Plugin | 8.5.2 | `build.gradle.kts:7` |
| Kotlin | 2.0.21 | `build.gradle.kts:8` |
| Android NDK | 26.1.10909125 | `../env.sh:42` |
| CMake | 3.22.1 | `../env.sh:45` |
| Go | 1.22 | `signaling/go.mod:3` |

`../env.sh` resolves against the workspace root (outside the repository). The Gradle wrapper version is
pinned in the wrapper properties file, so `./gradlew` does not depend on a system Gradle.

The libvpx build enables runtime CPU dispatch explicitly rather than relying on the configure default.
The configure line enables runtime CPU detection explicitly at
`scripts/t5-libwebrtc-libvpx-build.sh:295` rather than relying on the default; the rationale is recorded in
`reports/26-libvpx-runtime-cpu-detect.md`.

## 3. Build stages and their gates

The four stages below are the release recipe. Stages 3 and 4 are quoted with their observed gate output in
`reports/37-t76-build-publish.md:43` and `reports/37-t76-build-publish.md:44`.

### 3.1 Stage 1 — preflight only

- HOST: `bash scripts/build_app.sh --check-only`
  `--check-only` is defined at `scripts/build_app.sh:18` and performs the preflight checks without building.

### 3.2 Stage 2 — Kotlin early failure

```
./gradlew --no-daemon :app:compileDebugKotlin -PwebrtcDemo.skipNative=true
```

`-PwebrtcDemo.skipNative=true` is set at `scripts/build_app.sh:317`, and the task is invoked at
`scripts/build_app.sh:327` and `scripts/build_app.sh:330`. This stage exists so that Kotlin errors surface
before the expensive native build. A failure here stops the pipeline: stage 3 is skipped when a prior stage
failed (`scripts/build_app.sh:360`).

### 3.3 Stage 3 — packaging with the cache disabled

```
./gradlew --no-daemon --no-build-cache clean assembleDebug
```

Gate value: `BUILD SUCCESSFUL`, **zero** `FROM-CACHE` lines, 43 tasks (42 executed + 1 up-to-date), and a
non-zero count of native compile tasks. Note that `assembleDebug` alone is at `scripts/build_app.sh:363`;
the `clean` and `--no-build-cache` parts are the recipe recorded in the publisher report.

### 3.4 Stage 4 — unit tests, all tasks re-run

```
./gradlew --no-daemon --no-build-cache --rerun-tasks :app:testDebugUnitTest
```

Gate value: `BUILD SUCCESSFUL` with **24 tasks, 24 executed** — no task may be served from cache.

### 3.5 Stage 5 — Go signaling binary

The script builds the signaling service for `linux/amd64` (`scripts/build_app.sh:377`). The Go toolchain is
Go 1.22 (`signaling/go.mod:3`). `--skip-go` (`scripts/build_app.sh:19`) skips this stage.

### 3.6 Host-only script, container-equivalent steps

`scripts/build_app.sh:33` hard-codes the host workspace root (HOST: `/opt/dsh-workspaces`), and the script
sources the environment from that root at `scripts/build_app.sh:62`. The container workspace root is a
different directory, so **the script does not run in the container**; it is a host script.

The container-equivalent steps for stages 2–4 are the three Gradle commands above, executed after
`../env-container.sh` provides `ANDROID_HOME` and `GRADLE_USER_HOME` (`../env-container.sh:31`,
`../env-container.sh:38`).

## 4. Build invariants

| Invariant | Enforcement | Citation |
|---|---|---|
| The JNI bridge library must match its pinned hash | expected sha256 constants | `scripts/build_app.sh:467`, `scripts/build_app.sh:468` |
| The C++ runtime library must match its pinned hash | same pin pair loop | `scripts/build_app.sh:469` |
| The libraries inside the APK must be byte-identical to the expected hashes | unzip and hash comparison | `scripts/build_app.sh:469`, `scripts/build_app.sh:473` |
| Build outputs must be owned by uid 1000 | recursive chown at the end of the run | `scripts/build_app.sh:566`, `scripts/build_app.sh:567` |
| A build is invalid if `app/src` or `signaling/` changed during the build window | void the build; do not publish | see the release chain report |

The ownership normalisation runs last, after all products are on disk, because the earlier stages create
files as the current user (`scripts/build_app.sh:566`).

References: `scripts/build_app.sh`

## 5. Release chain

The publish tool is a host-side script and is **not** part of this repository. The release records state this
directly: the publish step was never part of the build script (`reports/10-app-build.md:1093`).

- HOST: `bash /opt/apk-http/publish_apk.sh --apk <build output> --log <log file>`
  The canonical invocation, including the identity guard, is recorded in `reports/37-t76-build-publish.md:100`.

### 5.1 Identity guard

The publisher refuses to run as a user other than uid 1000. Running as root without the explicit escape flag
prints the reason and exits with status 3, producing no writes (`reports/41-apk-http-ownership.md:192`).
The escape flag `--allow-root` bypasses the guard but prints a warning and requires a follow-up ownership fix (`reports/41-apk-http-ownership.md:193`).
The observed exit codes are recorded in the report: root without the flag exited 3 (`reports/41-apk-http-ownership.md:198`).

### 5.2 Freeze, shard and reconcile

The chain freezes the served copy, splits the APK into fixed-size parts with a `SHA256SUMS` manifest and a
`SOURCE.sha256` committed last, then reconciles four independent hash readings
(`reports/37-t72-build-publish.md:136`). The four-way reconciliation is
`reports/37-t72-build-publish.md:138`; the served copy, the concatenated parts and `SOURCE.sha256` must all
equal the freshly built APK.

The public download endpoint is verified against the same anchor: a full download plus a ranged request,
where the range returns HTTP 206 with the requested byte count (`reports/37-t72-build-publish.md:156`).

### 5.3 Rollback

Rollback restores both layers: the served copy is replaced from the frozen archive and the standard build
output path is restored from the same artifact, then the reconciliation is re-run. The concrete rollback
commands and the anchor hashes are recorded in `reports/52-release-closure.md` and
`reports/37-t72-build-publish.md`. Because the artifacts themselves are untracked build products, the
repository can only pin their hashes and the reports that recorded them — not the bytes.

## 6. Host services

Three services run on the host. Only the repository copies are readable from the container.

| Service | Unit or configuration | Key detail | Citation |
|---|---|---|---|
| signaling | `deploy/signaling.service` | `ExecStart` pins the grace period to `90s` | `deploy/signaling.service:28` |
| coturn | `deploy/turnserver.conf`, `deploy/coturn.service` | STUN/TURN on port 3478 | `deploy/turnserver.conf:4` |
| apk-http | host-side static file service | publish replaces the served file in place, no restart | see the release report |

The grace period is written explicitly into the unit rather than relying on the binary default, so the
deployed behaviour cannot drift when the default changes (`deploy/README.md:175`).

coturn enforces a peer-address policy: loopback and link-local ranges are denied explicitly
(`deploy/turnserver.conf:28`, `deploy/turnserver.conf:30`), the long-term credential mechanism is enabled
with a named user (`deploy/turnserver.conf:14`, `deploy/turnserver.conf:15`), and the total quota is
reduced to 45 (`deploy/turnserver.conf:16`). TLS is not enabled on the listener.

References: `deploy/signaling.service`, `deploy/turnserver.conf`, `deploy/README.md`

## 7. Incident handling

| Incident | Handling | Evidence |
|---|---|---|
| Source under `app/src` or `signaling/` was modified inside the build window | the build is void and must not be published; rebuild from a clean window | see the release closure report |
| Mixed or foreign artifacts appear in the tree during a build | isolate them and hash-record both sides instead of overwriting | see the release closure report |
| The served copy and the parts snapshot disagree | re-run the shard step and the four-way reconciliation; the public download stays available | `reports/37-t72-build-publish.md:180` |
| Publish runs as the wrong user | the guard exits 3 with no writes; re-run as uid 1000 | `reports/41-apk-http-ownership.md:192` |
| A published anchor must be withdrawn | restore the served copy and the build output from the frozen artifact, then re-reconcile | see the release closure report |

The mixed-artifact and ownership incidents are documented at length in `reports/52-release-closure.md`,
including the case where a partial publish left the served copy ahead of the parts snapshot
(`reports/37-t72-build-publish.md:180`).

## 8. Evidence index

| Claim | Citation | Verification artifact |
|---|---|---|
| Toolchain versions | `../env.sh:37`, `build.gradle.kts:7`, `signaling/go.mod:3` | environment and Gradle scripts |
| Early-failure stage | `scripts/build_app.sh:330` | build script |
| Packaging gate (zero cache hits) | `scripts/build_app.sh:363` | `reports/37-t76-build-publish.md:43` |
| Test gate (all tasks executed) | — | `reports/37-t76-build-publish.md:44` |
| Library hash pins | `scripts/build_app.sh:467` | build script |
| Ownership normalisation | `scripts/build_app.sh:566` | build script |
| Publish is not a repository script | — | `reports/10-app-build.md:1093` |
| Identity guard | — | `reports/41-apk-http-ownership.md:192` |
| Shards and reconciliation | — | `reports/37-t72-build-publish.md:136` |
| Public range request | — | `reports/37-t72-build-publish.md:156` |
| Grace period pinned in the unit | `deploy/signaling.service:28` | `deploy/README.md:175` |

## 9. Open items

- `scripts/build_app.sh` cannot run in the container because the workspace root is hard-coded at
  `scripts/build_app.sh:33`. This is a known limitation, not a defect of the build; the container-equivalent
  Gradle steps are given in §3.6. A follow-up improvement would be to accept the workspace root from the
  environment.
- The publish script is host-only and invisible from the container, so its behaviour is documented from the
  reports rather than from source. The claims in §5 are therefore `unverified` in the container and are
  labelled as such.
- The pinned library hashes in §4 are those recorded at the time of writing; a toolchain refresh changes
  them and requires a documentation update (`scripts/build_app.sh:467`).
- Tier-2 evidence for the release chain comes from a specific publish run; later runs are recorded in the
  other `reports/37-*` files and must be consulted for the current anchor.
