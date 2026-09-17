# Host/workspace command inventory

> **GENERATED — do not edit.** Regenerate with `bash scripts/gen-doc-tables.sh`.
> Generator: `scripts/gen-doc-tables.sh` (sha256 `e7951ef4e950858588103cb1e5714d390612fd8db625f570759c20e5c8c156c5`)
> Deterministic: no timestamp is embedded, so repeated runs are byte-identical.
> Sources (sha256 of the exact revision this table was built from):
> - `scripts/build_app.sh` — sha256 `aafd4e48f19889dcea4b3f809b8a529bc5b7883dfcf96190fdc664d237314f39`
> - `deploy/signaling.service` — sha256 `8380191c4bd4e4f17094eac06e707614eb0dc47919eee4563b482fd56b047b1f`

> **Provenance classes** (see `doc/design/SPEC.md` §7 and the t2 task contract): `in-repo` = a repository script or
> deploy unit; `report:<file>:<line>` = an engineering report recording a historical invocation;
> `workspace-only, outside git repo` = `../tmp/*.sh` at the workspace root (not part of the repository).
> A document that cites a name whose only provenance is `workspace-only` must also cite an in-repo
> `reports/<file>:<line>`; `scripts/doc-verify.sh` enforces this.

## 1. Names provable from repository scripts (`in-repo`)

| Token | Kind | Provenance |
|---|---|---|
| `--check-only` | flag | `scripts/build_app.sh:18` |
| `--check-only` | flag | `scripts/build_app.sh:45` |
| `--no-daemon` | flag | `scripts/build_app.sh:327` |
| `--no-daemon` | flag | `scripts/build_app.sh:330` |
| `--no-daemon` | flag | `scripts/build_app.sh:363` |
| `--only` | flag | `scripts/doc-verify.sh:55` |
| `--skip-go` | flag | `scripts/build_app.sh:19` |
| `--skip-go` | flag | `scripts/build_app.sh:46` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `scripts/build_app.sh:24` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `scripts/build_app.sh:317` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `scripts/build_app.sh:320` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `scripts/build_app.sh:324` |
| `:app:compileDebugKotlin` | gradle-task | `scripts/build_app.sh:327` |
| `:app:compileDebugKotlin` | gradle-task | `scripts/build_app.sh:330` |
| `assembleDebug` | gradle-task | `scripts/build_app.sh:363` |

## 2. Names recorded in reports (`report:<file>:<line>`)

| Token | Kind | Provenance |
|---|---|---|
| `--allow-root` | flag | `report:reports/37-t76-build-publish.md:100` |
| `--allow-root` | flag | `report:reports/37-t76-build-publish.md:129` |
| `--allow-root` | flag | `report:reports/37-t81-build-publish.md:101` |
| `--allow-root` | flag | `report:reports/41-apk-http-ownership.md:226` |
| `--apk` | flag | `report:reports/37-t76-build-publish.md:100` |
| `--apk` | flag | `report:reports/37-t81-build-publish.md:101` |
| `--apk` | flag | `report:reports/37-t84-build-publish.md:105` |
| `--apk` | flag | `report:reports/41-apk-http-ownership.md:204` |
| `--apk` | flag | `report:reports/41-apk-http-ownership.md:226` |
| `--check-only` | flag | `report:reports/04-env-install.md:462` |
| `--check-only` | flag | `report:reports/04-env-install.md:487` |
| `--check-only` | flag | `report:reports/04-env-install.md:641` |
| `--check-only` | flag | `report:reports/10-app-build.md:1002` |
| `--check-only` | flag | `report:reports/10-app-build.md:1159` |
| `--check-only` | flag | `report:reports/10-app-build.md:123` |
| `--check-only` | flag | `report:reports/10-app-build.md:639` |
| `--check-only` | flag | `report:reports/10-app-build.md:881` |
| `--check-only` | flag | `report:reports/15-java-jar-rebuild.md:828` |
| `--check-only` | flag | `report:reports/37-t69-build-publish.md:23` |
| `--check-only` | flag | `report:reports/37-t72-build-publish.md:64` |
| `--check-only` | flag | `report:reports/37-t76-build-publish.md:41` |
| `--check-only` | flag | `report:reports/37-t81-build-publish.md:41` |
| `--check-only` | flag | `report:reports/37-t84-build-publish.md:45` |
| `--check-only` | flag | `report:reports/37-t86-build-publish.md:44` |
| `--check-only` | flag | `report:reports/37-t90-build-publish.md:48` |
| `--check-only` | flag | `report:reports/37-t93-build-publish.md:40` |
| `--check-only` | flag | `report:reports/99-final-report.md:1758` |
| `--console` | flag | `report:reports/99-final-report.md:538` |
| `--dry-run` | flag | `report:reports/08-android-dev.md:317` |
| `--dry-run` | flag | `report:reports/08-android-dev.md:319` |
| `--dry-run` | flag | `report:reports/10-app-build.md:156` |
| `--log` | flag | `report:reports/37-t76-build-publish.md:100` |
| `--log` | flag | `report:reports/37-t81-build-publish.md:101` |
| `--log` | flag | `report:reports/37-t84-build-publish.md:105` |
| `--log` | flag | `report:reports/41-apk-http-ownership.md:171` |
| `--log` | flag | `report:reports/41-apk-http-ownership.md:204` |
| `--log` | flag | `report:reports/41-apk-http-ownership.md:226` |
| `--log` | flag | `report:reports/41-apk-http-ownership.md:29` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:1003` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:1005` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:1040` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:1085` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:1160` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:1162` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:1166` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:202` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:354` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:50` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:645` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:655` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:753` |
| `--no-build-cache` | flag | `report:reports/10-app-build.md:971` |
| `--no-build-cache` | flag | `report:reports/20-encode-resize-crash.md:88` |
| `--no-build-cache` | flag | `report:reports/37-t69-build-publish.md:24` |
| `--no-build-cache` | flag | `report:reports/37-t69-build-publish.md:25` |
| `--no-build-cache` | flag | `report:reports/37-t69-build-publish.md:26` |
| `--no-build-cache` | flag | `report:reports/37-t72-build-publish.md:65` |
| `--no-build-cache` | flag | `report:reports/37-t72-build-publish.md:66` |
| `--no-build-cache` | flag | `report:reports/37-t72-build-publish.md:67` |
| `--no-build-cache` | flag | `report:reports/37-t76-build-publish.md:42` |
| `--no-build-cache` | flag | `report:reports/37-t76-build-publish.md:43` |
| `--no-build-cache` | flag | `report:reports/37-t76-build-publish.md:44` |
| `--no-build-cache` | flag | `report:reports/37-t81-build-publish.md:42` |
| `--no-build-cache` | flag | `report:reports/37-t81-build-publish.md:43` |
| `--no-build-cache` | flag | `report:reports/37-t81-build-publish.md:44` |
| `--no-build-cache` | flag | `report:reports/37-t84-build-publish.md:46` |
| `--no-build-cache` | flag | `report:reports/37-t84-build-publish.md:47` |
| `--no-build-cache` | flag | `report:reports/37-t84-build-publish.md:48` |
| `--no-build-cache` | flag | `report:reports/37-t86-build-publish.md:45` |
| `--no-build-cache` | flag | `report:reports/37-t86-build-publish.md:46` |
| `--no-build-cache` | flag | `report:reports/37-t86-build-publish.md:47` |
| `--no-build-cache` | flag | `report:reports/37-t90-build-publish.md:49` |
| `--no-build-cache` | flag | `report:reports/37-t90-build-publish.md:50` |
| `--no-build-cache` | flag | `report:reports/37-t90-build-publish.md:51` |
| `--no-build-cache` | flag | `report:reports/37-t93-build-publish.md:41` |
| `--no-build-cache` | flag | `report:reports/37-t93-build-publish.md:42` |
| `--no-build-cache` | flag | `report:reports/37-t93-build-publish.md:43` |
| `--no-build-cache` | flag | `report:reports/99-final-report.md:1679` |
| `--no-build-cache` | flag | `report:reports/99-final-report.md:1686` |
| `--no-build-cache` | flag | `report:reports/99-final-report.md:179` |
| `--no-build-cache` | flag | `report:reports/99-final-report.md:340` |
| `--no-build-cache` | flag | `report:reports/99-t34-appendix.md:116` |
| `--no-daemon` | flag | `report:reports/04-env-install.md:458` |
| `--no-daemon` | flag | `report:reports/04-env-install.md:503` |
| `--no-daemon` | flag | `report:reports/07-native-dev.md:368` |
| `--no-daemon` | flag | `report:reports/07-native-dev.md:607` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1050` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1095` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1159` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1395` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1477` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1502` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1508` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1522` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:1533` |
| `--no-daemon` | flag | `report:reports/08-android-dev.md:941` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:1003` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:1005` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:1040` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:107` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:1085` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:1160` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:1162` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:1166` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:128` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:129` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:160` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:202` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:237` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:274` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:354` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:366` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:436` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:49` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:50` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:645` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:655` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:753` |
| `--no-daemon` | flag | `report:reports/10-app-build.md:971` |
| `--no-daemon` | flag | `report:reports/16-foreground-black-preview.md:156` |
| `--no-daemon` | flag | `report:reports/20-encode-resize-crash.md:88` |
| `--no-daemon` | flag | `report:reports/21-remote-message-race.md:148` |
| `--no-daemon` | flag | `report:reports/37-t69-build-publish.md:24` |
| `--no-daemon` | flag | `report:reports/37-t69-build-publish.md:25` |
| `--no-daemon` | flag | `report:reports/37-t69-build-publish.md:26` |
| `--no-daemon` | flag | `report:reports/37-t72-build-publish.md:65` |
| `--no-daemon` | flag | `report:reports/37-t72-build-publish.md:66` |
| `--no-daemon` | flag | `report:reports/37-t72-build-publish.md:67` |
| `--no-daemon` | flag | `report:reports/37-t76-build-publish.md:42` |
| `--no-daemon` | flag | `report:reports/37-t76-build-publish.md:43` |
| `--no-daemon` | flag | `report:reports/37-t76-build-publish.md:44` |
| `--no-daemon` | flag | `report:reports/37-t81-build-publish.md:42` |
| `--no-daemon` | flag | `report:reports/37-t81-build-publish.md:43` |
| `--no-daemon` | flag | `report:reports/37-t81-build-publish.md:44` |
| `--no-daemon` | flag | `report:reports/37-t84-build-publish.md:46` |
| `--no-daemon` | flag | `report:reports/37-t84-build-publish.md:47` |
| `--no-daemon` | flag | `report:reports/37-t84-build-publish.md:48` |
| `--no-daemon` | flag | `report:reports/37-t86-build-publish.md:45` |
| `--no-daemon` | flag | `report:reports/37-t86-build-publish.md:46` |
| `--no-daemon` | flag | `report:reports/37-t86-build-publish.md:47` |
| `--no-daemon` | flag | `report:reports/37-t90-build-publish.md:49` |
| `--no-daemon` | flag | `report:reports/37-t90-build-publish.md:50` |
| `--no-daemon` | flag | `report:reports/37-t90-build-publish.md:51` |
| `--no-daemon` | flag | `report:reports/37-t93-build-publish.md:41` |
| `--no-daemon` | flag | `report:reports/37-t93-build-publish.md:42` |
| `--no-daemon` | flag | `report:reports/37-t93-build-publish.md:43` |
| `--no-daemon` | flag | `report:reports/99-final-report.md:1679` |
| `--no-daemon` | flag | `report:reports/99-final-report.md:1686` |
| `--no-daemon` | flag | `report:reports/99-final-report.md:340` |
| `--no-daemon` | flag | `report:reports/99-t34-appendix.md:116` |
| `--no-public` | flag | `report:reports/41-apk-http-ownership.md:171` |
| `--offline` | flag | `report:reports/99-final-report.md:488` |
| `--porcelain` | flag | `report:reports/13-device-defect-fix.md:169` |
| `--rehearsal` | flag | `report:reports/37-t76-build-publish.md:100` |
| `--rehearsal` | flag | `report:reports/37-t81-build-publish.md:101` |
| `--rehearsal` | flag | `report:reports/41-apk-http-ownership.md:171` |
| `--rehearsal` | flag | `report:reports/41-apk-http-ownership.md:226` |
| `--rehearsal` | flag | `report:reports/41-apk-http-ownership.md:29` |
| `--rerun` | flag | `report:reports/99-final-report.md:289` |
| `--rerun` | flag | `report:reports/99-final-report.md:488` |
| `--rerun` | flag | `report:reports/99-final-report.md:538` |
| `--rerun-tasks` | flag | `report:reports/08-android-dev.md:1395` |
| `--rerun-tasks` | flag | `report:reports/10-app-build.md:1040` |
| `--rerun-tasks` | flag | `report:reports/10-app-build.md:1166` |
| `--rerun-tasks` | flag | `report:reports/10-app-build.md:655` |
| `--rerun-tasks` | flag | `report:reports/10-app-build.md:753` |
| `--rerun-tasks` | flag | `report:reports/10-app-build.md:971` |
| `--rerun-tasks` | flag | `report:reports/37-t69-build-publish.md:26` |
| `--rerun-tasks` | flag | `report:reports/37-t72-build-publish.md:67` |
| `--rerun-tasks` | flag | `report:reports/37-t76-build-publish.md:44` |
| `--rerun-tasks` | flag | `report:reports/37-t81-build-publish.md:44` |
| `--rerun-tasks` | flag | `report:reports/37-t84-build-publish.md:48` |
| `--rerun-tasks` | flag | `report:reports/37-t86-build-publish.md:47` |
| `--rerun-tasks` | flag | `report:reports/37-t90-build-publish.md:51` |
| `--rerun-tasks` | flag | `report:reports/37-t93-build-publish.md:43` |
| `--rerun-tasks` | flag | `report:reports/99-final-report.md:1679` |
| `--rerun-tasks` | flag | `report:reports/99-final-report.md:1686` |
| `--rerun-tasks` | flag | `report:reports/99-final-report.md:179` |
| `--rerun-tasks` | flag | `report:reports/99-final-report.md:289` |
| `--rerun-tasks` | flag | `report:reports/99-final-report.md:548` |
| `--rerun-tasks` | flag | `report:reports/99-final-report.md:674` |
| `--rerun-tasks` | flag | `report:reports/99-final-report.md:697` |
| `--rerun-tasks` | flag | `report:reports/99-t34-appendix.md:116` |
| `--skip-go` | flag | `report:reports/10-app-build.md:124` |
| `--version` | flag | `report:reports/99-final-report.md:1690` |
| `-P...=daemon` | gradle-property | `report:reports/14-android-skeleton.md:256` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/04-env-install.md:503` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/04-env-install.md:513` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/04-env-install.md:517` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/04-env-install.md:522` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/04-env-install.md:866` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/08-android-dev.md:1477` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/08-android-dev.md:1723` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/08-android-dev.md:271` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/08-android-dev.md:286` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/08-android-dev.md:292` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/08-android-dev.md:317` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/08-android-dev.md:899` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/08-android-dev.md:941` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/10-app-build.md:1003` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/10-app-build.md:1160` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/10-app-build.md:128` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/10-app-build.md:160` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/14-android-skeleton.md:257` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/14-android-skeleton.md:298` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/14-android-skeleton.md:304` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/14-android-skeleton.md:375` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t69-build-publish.md:24` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t72-build-publish.md:65` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t76-build-publish.md:42` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t81-build-publish.md:42` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t84-build-publish.md:46` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t86-build-publish.md:45` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t86-build-publish.md:49` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t90-build-publish.md:49` |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `report:reports/37-t93-build-publish.md:41` |
| `:admin` | gradle-task | `report:reports/37-t76-build-publish.md:139` |
| `:admin` | gradle-task | `report:reports/37-t81-build-publish.md:136` |
| `:admin` | gradle-task | `report:reports/37-t84-build-publish.md:141` |
| `:admin` | gradle-task | `report:reports/37-t86-build-publish.md:117` |
| `:admin` | gradle-task | `report:reports/37-t90-build-publish.md:116` |
| `:app:assembleDebug` | gradle-task | `report:reports/08-android-dev.md:287` |
| `:app:assembleDebug` | gradle-task | `report:reports/08-android-dev.md:317` |
| `:app:assembleDebug` | gradle-task | `report:reports/08-android-dev.md:319` |
| `:app:assembleDebug` | gradle-task | `report:reports/14-android-skeleton.md:299` |
| `:app:assembleRelease` | gradle-task | `report:reports/99-final-report.md:485` |
| `:app:clean` | gradle-task | `report:reports/37-t69-build-publish.md:25` |
| `:app:clean` | gradle-task | `report:reports/37-t72-build-publish.md:66` |
| `:app:clean` | gradle-task | `report:reports/37-t76-build-publish.md:43` |
| `:app:clean` | gradle-task | `report:reports/37-t81-build-publish.md:43` |
| `:app:clean` | gradle-task | `report:reports/37-t84-build-publish.md:47` |
| `:app:clean` | gradle-task | `report:reports/37-t86-build-publish.md:46` |
| `:app:clean` | gradle-task | `report:reports/37-t90-build-publish.md:50` |
| `:app:clean` | gradle-task | `report:reports/37-t93-build-publish.md:42` |
| `:app:clean` | gradle-task | `report:reports/99-final-report.md:179` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/02-interface-contract.md:1170` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/04-env-install.md:503` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/08-android-dev.md:1477` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/08-android-dev.md:1723` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/08-android-dev.md:286` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/08-android-dev.md:941` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/10-app-build.md:1003` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/10-app-build.md:1160` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/10-app-build.md:128` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/14-android-skeleton.md:298` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/14-android-skeleton.md:375` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/37-t69-build-publish.md:24` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/37-t72-build-publish.md:65` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/37-t76-build-publish.md:42` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/37-t81-build-publish.md:42` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/37-t84-build-publish.md:46` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/37-t86-build-publish.md:45` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/37-t90-build-publish.md:49` |
| `:app:compileDebugKotlin` | gradle-task | `report:reports/37-t93-build-publish.md:41` |
| `:app:externalNativeBuildDebug` | gradle-task | `report:reports/07-native-dev.md:368` |
| `:app:externalNativeBuildDebug` | gradle-task | `report:reports/07-native-dev.md:607` |
| `:app:externalNativeBuildDebug` | gradle-task | `report:reports/08-android-dev.md:999` |
| `:app:externalNativeBuildDebug` | gradle-task | `report:reports/10-app-build.md:107` |
| `:app:processDebugResources` | gradle-task | `report:reports/08-android-dev.md:1606` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1050` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1095` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1159` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1395` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1477` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1502` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1508` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1522` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1533` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:1720` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:466` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:633` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/08-android-dev.md:941` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/10-app-build.md:1040` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/10-app-build.md:1166` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/10-app-build.md:366` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/10-app-build.md:436` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/10-app-build.md:655` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/10-app-build.md:753` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/10-app-build.md:971` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/16-foreground-black-preview.md:156` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/21-remote-message-race.md:148` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/37-t69-build-publish.md:26` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/37-t72-build-publish.md:67` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/37-t76-build-publish.md:44` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/37-t81-build-publish.md:44` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/37-t84-build-publish.md:48` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/37-t86-build-publish.md:47` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/37-t90-build-publish.md:51` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/37-t93-build-publish.md:43` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:1679` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:1686` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:179` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:289` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:488` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:538` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:548` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:674` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-final-report.md:697` |
| `:app:testDebugUnitTest` | gradle-task | `report:reports/99-t34-appendix.md:116` |
| `:root` | gradle-task | `report:reports/04-env-install.md:641` |
| `:root` | gradle-task | `report:reports/10-app-build.md:1061` |
| `:root` | gradle-task | `report:reports/41-apk-http-ownership.md:170` |
| `:root` | gradle-task | `report:reports/41-apk-http-ownership.md:194` |
| `:root` | gradle-task | `report:reports/41-apk-http-ownership.md:222` |
| `:root` | gradle-task | `report:reports/41-apk-http-ownership.md:24` |
| `:root` | gradle-task | `report:reports/41-apk-http-ownership.md:37` |
| `:root` | gradle-task | `report:reports/41-apk-http-ownership.md:97` |
| `:scripts` | gradle-task | `report:reports/10-app-build.md:774` |
| `:scripts` | gradle-task | `report:reports/99-final-report.md:1627` |
| `assembleDebug` | gradle-task | `report:reports/10-app-build.md:1005` |
| `assembleDebug` | gradle-task | `report:reports/10-app-build.md:1085` |
| `assembleDebug` | gradle-task | `report:reports/10-app-build.md:1162` |
| `assembleDebug` | gradle-task | `report:reports/10-app-build.md:129` |
| `assembleDebug` | gradle-task | `report:reports/10-app-build.md:354` |
| `assembleDebug` | gradle-task | `report:reports/10-app-build.md:645` |
| `assembleDebug` | gradle-task | `report:reports/20-encode-resize-crash.md:88` |
| `clean` | gradle-task | `report:reports/10-app-build.md:1005` |
| `clean` | gradle-task | `report:reports/10-app-build.md:1085` |
| `clean` | gradle-task | `report:reports/10-app-build.md:1162` |
| `clean` | gradle-task | `report:reports/10-app-build.md:160` |
| `clean` | gradle-task | `report:reports/10-app-build.md:202` |
| `clean` | gradle-task | `report:reports/10-app-build.md:237` |
| `clean` | gradle-task | `report:reports/10-app-build.md:274` |
| `clean` | gradle-task | `report:reports/10-app-build.md:354` |
| `clean` | gradle-task | `report:reports/10-app-build.md:49` |
| `clean` | gradle-task | `report:reports/10-app-build.md:50` |
| `clean` | gradle-task | `report:reports/10-app-build.md:645` |
| `clean` | gradle-task | `report:reports/20-encode-resize-crash.md:88` |
| `clean` | gradle-task | `report:reports/37-t69-build-publish.md:25` |
| `clean` | gradle-task | `report:reports/37-t72-build-publish.md:66` |
| `clean` | gradle-task | `report:reports/37-t76-build-publish.md:43` |
| `clean` | gradle-task | `report:reports/37-t81-build-publish.md:43` |
| `clean` | gradle-task | `report:reports/37-t84-build-publish.md:47` |
| `clean` | gradle-task | `report:reports/37-t86-build-publish.md:46` |
| `clean` | gradle-task | `report:reports/37-t90-build-publish.md:50` |
| `clean` | gradle-task | `report:reports/37-t93-build-publish.md:42` |
| `clean` | gradle-task | `report:reports/99-final-report.md:340` |

## 3. Names seen only in workspace scripts (`workspace-only, outside git repo`)

| Token | Kind | Provenance |
|---|---|---|
| `--apk` | flag | `../../tmp/t77-publish_apk.sh:13` (workspace-only, outside git repo) |
| `--apk` | flag | `../../tmp/t77-publish_apk.sh:50` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t42-captain-build.sh:36` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t42-captain-build.sh:37` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t47-captain-build.sh:36` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t47-captain-build.sh:37` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t47b-captain-build.sh:36` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t47b-captain-build.sh:37` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t69-build.sh:22` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t69-build.sh:23` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t72-build.sh:32` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t72-build.sh:33` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t76-build.sh:32` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t76-build.sh:33` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t81-build.sh:33` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t81-build.sh:34` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t84-build.sh:33` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t84-build.sh:34` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t86-build.sh:44` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t86-build.sh:45` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t88-build.sh:44` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t88-build.sh:45` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t90-build.sh:44` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t90-build.sh:45` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t93-build.sh:44` (workspace-only, outside git repo) |
| `--check-only` | flag | `../../tmp/t93-build.sh:45` (workspace-only, outside git repo) |
| `--log` | flag | `../../tmp/t77-publish_apk.sh:13` (workspace-only, outside git repo) |
| `--log` | flag | `../../tmp/t77-publish_apk.sh:50` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t42-captain-build.sh:44` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t42-captain-build.sh:52` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t42-captain-build.sh:69` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t47-captain-build.sh:44` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t47-captain-build.sh:52` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t47-captain-build.sh:69` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t47b-captain-build.sh:44` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t47b-captain-build.sh:52` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t47b-captain-build.sh:70` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t69-build.sh:30` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t69-build.sh:37` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t69-build.sh:53` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t72-build.sh:101` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t72-build.sh:65` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t72-build.sh:73` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t76-build.sh:59` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t76-build.sh:67` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t76-build.sh:93` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t81-build.sh:60` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t81-build.sh:68` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t81-build.sh:94` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t84-build.sh:60` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t84-build.sh:68` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t84-build.sh:94` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t86-build.sh:118` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t86-build.sh:71` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t86-build.sh:79` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t88-build.sh:118` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t88-build.sh:71` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t88-build.sh:79` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t90-build.sh:118` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t90-build.sh:71` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t90-build.sh:79` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t93-build.sh:118` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t93-build.sh:71` (workspace-only, outside git repo) |
| `--no-build-cache` | flag | `../../tmp/t93-build.sh:79` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t42-captain-build.sh:44` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t42-captain-build.sh:52` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t42-captain-build.sh:69` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t47-captain-build.sh:44` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t47-captain-build.sh:52` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t47-captain-build.sh:69` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t47b-captain-build.sh:44` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t47b-captain-build.sh:52` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t47b-captain-build.sh:70` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t69-build.sh:30` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t69-build.sh:37` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t69-build.sh:53` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t72-build.sh:101` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t72-build.sh:65` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t72-build.sh:73` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t76-build.sh:59` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t76-build.sh:67` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t76-build.sh:93` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t81-build.sh:60` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t81-build.sh:68` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t81-build.sh:94` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t84-build.sh:60` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t84-build.sh:68` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t84-build.sh:94` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t86-build.sh:118` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t86-build.sh:71` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t86-build.sh:79` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t88-build.sh:118` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t88-build.sh:71` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t88-build.sh:79` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t90-build.sh:118` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t90-build.sh:71` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t90-build.sh:79` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t93-build.sh:118` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t93-build.sh:71` (workspace-only, outside git repo) |
| `--no-daemon` | flag | `../../tmp/t93-build.sh:79` (workspace-only, outside git repo) |
| `--no-public` | flag | `../../tmp/t77-publish_apk.sh:13` (workspace-only, outside git repo) |
| `--rehearsal` | flag | `../../tmp/t77-publish_apk.sh:13` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t42-captain-build.sh:69` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t47-captain-build.sh:69` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t47b-captain-build.sh:70` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t69-build.sh:53` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t72-build.sh:101` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t76-build.sh:93` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t81-build.sh:94` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t84-build.sh:94` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t86-build.sh:118` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t88-build.sh:118` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t90-build.sh:118` (workspace-only, outside git repo) |
| `--rerun-tasks` | flag | `../../tmp/t93-build.sh:118` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t42-captain-build.sh:44` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t47-captain-build.sh:44` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t47b-captain-build.sh:44` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t69-build.sh:30` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t72-build.sh:65` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t76-build.sh:59` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t81-build.sh:60` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t84-build.sh:60` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t86-build.sh:71` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t88-build.sh:71` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t90-build.sh:71` (workspace-only, outside git repo) |
| `-PwebrtcDemo.skipNative=true` | gradle-property | `../../tmp/t93-build.sh:71` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t42-captain-build.sh:44` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t47-captain-build.sh:44` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t47b-captain-build.sh:44` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t69-build.sh:30` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t72-build.sh:65` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t76-build.sh:59` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t81-build.sh:60` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t84-build.sh:60` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t86-build.sh:71` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t88-build.sh:71` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t90-build.sh:71` (workspace-only, outside git repo) |
| `:app:compileDebugKotlin` | gradle-task | `../../tmp/t93-build.sh:71` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t42-captain-build.sh:69` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t47-captain-build.sh:69` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t47b-captain-build.sh:70` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t69-build.sh:53` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t72-build.sh:101` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t76-build.sh:93` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t81-build.sh:94` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t84-build.sh:94` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t86-build.sh:118` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t88-build.sh:118` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t90-build.sh:118` (workspace-only, outside git repo) |
| `:app:testDebugUnitTest` | gradle-task | `../../tmp/t93-build.sh:118` (workspace-only, outside git repo) |
| `:root` | gradle-task | `../../tmp/t42-captain-build.sh:84` (workspace-only, outside git repo) |
| `:root` | gradle-task | `../../tmp/t47-captain-build.sh:84` (workspace-only, outside git repo) |
| `:root` | gradle-task | `../../tmp/t47b-captain-build.sh:85` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t42-captain-build.sh:52` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t47-captain-build.sh:52` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t47b-captain-build.sh:52` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t69-build.sh:37` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t72-build.sh:73` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t76-build.sh:67` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t81-build.sh:68` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t84-build.sh:68` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t86-build.sh:79` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t88-build.sh:79` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t90-build.sh:79` (workspace-only, outside git repo) |
| `assembleDebug` | gradle-task | `../../tmp/t93-build.sh:79` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t42-captain-build.sh:52` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t47-captain-build.sh:52` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t47b-captain-build.sh:52` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t69-build.sh:37` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t72-build.sh:73` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t76-build.sh:67` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t81-build.sh:68` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t84-build.sh:68` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t86-build.sh:79` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t88-build.sh:79` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t90-build.sh:79` (workspace-only, outside git repo) |
| `clean` | gradle-task | `../../tmp/t93-build.sh:79` (workspace-only, outside git repo) |

## 4. Host-side publish chain (`publish_apk.sh`)

`publish_apk.sh` is **not in the repository** — it is the host script HOST: `/opt/apk-http/publish_apk.sh` (not
visible in the container). It must therefore only ever be cited as `HOST:` with report provenance, never as a
repository-relative path. In-repo evidence:

| Item | Evidence |
|---|---|
| `命令：`su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh --apk <构建输出> --log reports/10-t81-build.log"`（**不带** `--rehearsal`` | `reports/37-t81-build-publish.md:101` |
| `命令：`su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh --apk <构建输出> --log reports/10-t76-build.log"`（正式模式，**不带*` | `reports/37-t76-build-publish.md:100` |
| `| 7 | **发布演练（uid 1000 全程）** | `su -s /bin/bash admin -c "bash /opt/apk-http/publish_apk.sh --rehearsal --log /opt/dsh-workspaces/tmp/t` | `reports/41-apk-http-ownership.md:29` |
| `2. **身份守卫（t77 ②，本轮生效）**：`publish_apk.sh` 在参数解析后拒绝 `id -u != 1000`（除非显式 `--allow-root`）。本轮�` | `reports/37-t76-build-publish.md:129` |
| `1. **身份守卫（t77 ②，本轮再次生效）**：正式发布以 `su -s /bin/bash admin -c …` 运行 ⇒ uid=1000 正常放行；root 直跑�` | `reports/37-t81-build-publish.md:129` |
| `**关键事实**：该次执行**未触碰** `/opt/apk-http/**` 与 `artifacts/`（publish 步骤从不属于其脚本），故 `served`/`parts`/`SOUR` | `reports/10-app-build.md:1093` |

## 5. Machine-readable token set (for `doc-verify.sh`)

| Token | Class |
|---|---|
| `--allow-root` | report |
| `--apk` | report |
| `--check-only` | in-repo |
| `--console` | report |
| `--dry-run` | report |
| `--log` | report |
| `--no-build-cache` | report |
| `--no-daemon` | in-repo |
| `--no-public` | report |
| `--offline` | report |
| `--only` | in-repo |
| `--porcelain` | report |
| `--rehearsal` | report |
| `--rerun` | report |
| `--rerun-tasks` | report |
| `--skip-go` | in-repo |
| `--version` | report |
| `-P...=daemon` | report |
| `-PwebrtcDemo.skipNative=true` | in-repo |
| `:admin` | report |
| `:app:assembleDebug` | report |
| `:app:assembleRelease` | report |
| `:app:clean` | report |
| `:app:compileDebugKotlin` | in-repo |
| `:app:externalNativeBuildDebug` | report |
| `:app:processDebugResources` | report |
| `:app:testDebugUnitTest` | report |
| `:root` | report |
| `:scripts` | report |
| `assembleDebug` | in-repo |
| `clean` | report |
