# 60 — Pushing this repository to GitHub (runbook)

> Target: `https://github.com/custompal/webrtc-demo.git`
> Repo: `code/webrtc-demo` · branch `main` · 274 commits
> Status: **remote configured, push blocked on credentials** (this environment holds no GitHub credentials)

## 1. Reconnaissance (measured)

| Item | Value |
|---|---|
| Branch / commits | `main`, 274 commits |
| Working tree | clean |
| Main-repo object size | in-pack **2.29 MiB** (19 packs, 1 434 objects) — the push itself is tiny |
| Tracked files | 428; largest tracked file `reports/99-final-report.md` = 0.33 MB (no GitHub size limits involved) |
| Build outputs | NOT tracked: `app/build/**`, `third_party/libwebrtc/`, `third_party/libvpx/`, `*.apk`, `*.so` are all in `.gitignore` |
| Submodules | `third_party/libvpx-src` → `d2413e2c…`, `third_party/libwebrtc-src` → `be0e9008…` |

## 2. What was already done

```bash
git remote add origin https://github.com/custompal/webrtc-demo.git
git push -u origin main          # attempted
# fatal: could not read Username for 'https://github.com': terminal prompts disabled
```

Everything except authentication is in place: the remote is configured, the branch is `main`, and the repository
is small. `https://github.com/git/git.git` resolves fine from here, so outbound HTTPS to GitHub works; the
failure is purely "no credentials present" (no `~/.git-credentials`, no `~/.ssh`, no `GH_TOKEN`/`GITHUB_TOKEN`,
`gh` not installed).

## 3. Finish the push — pick one

**(a) Fine-grained personal access token (fastest).** Create a token limited to `custompal/webrtc-demo` with
*Contents: Read and write*. Then either

```bash
# one-shot, token not written to disk:
git push https://<TOKEN>@github.com/custompal/webrtc-demo.git main
```

or store it for later pushes:

```bash
git remote set-url origin https://<TOKEN>@github.com/custompal/webrtc-demo.git
git push -u origin main
```

Afterwards, **revoke or rotate the token** — a token pasted into a chat is visible in the transcript.

**(b) SSH deploy key.** Generate a key here, add the public half to the repository's *Deploy keys* (with write
access), then:

```bash
ssh-keygen -t ed25519 -C "webrtc-demo push" -f ~/.ssh/id_ed25519 -N ""
cat ~/.ssh/id_ed25519.pub          # paste into GitHub → Settings → Deploy keys (allow write)
git remote set-url origin git@github.com:custompal/webrtc-demo.git
git push -u origin main
```

**(c) Offline bundle.** A self-contained bundle is prepared at
`/data/dsh/home/workspace/tmp/webrtc-demo-main.bundle` (all 274 commits). Anyone with it can push from a machine
that has credentials:

```bash
git clone webrtc-demo-main.bundle webrtc-demo
cd webrtc-demo && git remote set-url origin https://github.com/custompal/webrtc-demo.git && git push -u origin main
```

## 4. Do the submodules get pushed? — No.

Pushing the parent repository uploads **only**:

* the file `.gitmodules`, which records the two upstream URLs, and
* two **gitlink** entries of mode `160000`, which record commit ids, not content:

```
160000 commit d2413e2ca11039724ca33bb4d661ca2c94cb501e   third_party/libvpx-src
160000 commit be0e900885631e028972f18ed682d6ddb13be637   third_party/libwebrtc-src
```

Consequences:

* The submodule histories and sources (≈ 277 MB of working tree) are **not** in your GitHub repository and do not
  consume your GitHub storage. A clone gets two empty directories until submodules are fetched.
* `git clone --recurse-submodules https://github.com/custompal/webrtc-demo.git` (or `git submodule update --init
  --recursive` after a normal clone) fetches them from the URLs in `.gitmodules`:
  `https://chromium.googlesource.com/webm/libvpx` and `https://webrtc.googlesource.com/src`.
* That works because both submodules are **pristine upstream checkouts** — measured: 0 commits beyond
  `origin/main` in either. The recorded commit ids therefore exist upstream and stay fetchable.
* Neither GitHub's ZIP download nor `git archive` includes submodule contents; only a real clone with
  `--recurse-submodules` does.
* If you ever *do* want your own copies on GitHub (e.g. to pin locally patched libwebrtc), you must push each
  submodule to its own repository and then update `.gitmodules` (`git submodule set-url`) plus commit the new
  gitlink. Nothing in the current state requires that: our patches are applied at build time by
  `scripts/build_java_sdk_with_jni.sh` and `scripts/patches/libwebrtc-java-release17.patch`, not committed into
  the submodules.
* The build outputs are also excluded: `third_party/libwebrtc/`, `third_party/libvpx/`, `app/build/**`, `*.apk`
  and `*.so` are gitignored, so the compiled libwebrtc/libvpx and the APK are not pushed either.

## 5. After the push — verify

```bash
git ls-remote https://github.com/custompal/webrtc-demo.git | head        # expect refs/heads/main
git log --oneline -1 origin/main                                        # expect 32ee83e or later
# clean-room clone test (submodules included):
git clone --recurse-submodules https://github.com/custompal/webrtc-demo.git /tmp/verify-clone
cd /tmp/verify-clone && bash scripts/doc-verify.sh | tail -1             # expect PASS (2466 checks, 2 warnings), exit 0
```

The last check is the meaningful one: it proves the published tree still passes its own documentation gate, with
all citations resolving against the code that was pushed.
