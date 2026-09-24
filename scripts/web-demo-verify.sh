#!/usr/bin/env bash
# =============================================================================
# web-demo-verify.sh — headless-Chrome verification for the browser demo.
#
# Everything is bootstrapped *inside the container*: the script downloads a
# pinned Chrome for Testing build plus the Debian runtime libraries it needs
# into a cache directory, then drives two headless pages. Nothing is installed
# on the host — no system packages, no browser — and `web/**` never contains a
# binary. This is the executable form of reports/70 §8 A-3.
#
#   bash scripts/web-demo-verify.sh                    # probe mode (default)
#   bash scripts/web-demo-verify.sh --mode probe       # same, spelled out
#   bash scripts/web-demo-verify.sh --mode demo        # drive web/index.html
#   bash scripts/web-demo-verify.sh --mode auto        # demo if web/index.html exists
#   bash scripts/web-demo-verify.sh --print-cache      # cache path, size, contents
#   bash scripts/web-demo-verify.sh --clean            # remove the cache
#
# Modes:
#   probe  (default) two headless pages run a real call through the LIVE
#          signalling service (create/created, join/joined, peerJoined,
#          offer/answer/ice, ICE+DTLS connected, VP9 by getStats, bidirectional
#          RTP over 5 s, leave/peerLeft) — no dependency on the page under test,
#          so the verdict is attributable to this script alone
#   demo   loads web/index.html in two tabs and runs its own matrix
#          (web/lib/selftest.js, reports/70 §8 A-4) plus origin hygiene
#   auto   opt-in convenience: demo when web/index.html exists, else probe
#
# Options:
#   --signaling URL   signalling endpoint (default ws://47.238.144.66:8443/ws)
#   --stun URL        STUN URL used for candidate gathering (default stun:47.238.144.66:3478)
#   --cache DIR       cache directory (default: ${XDG_CACHE_HOME:-$HOME/.cache}/web-demo-chrome-cache,
#                     falling back to <repo>/../web-demo-chrome-cache when that is not writable; never ${TMPDIR:-/tmp})
#   --log-file FILE   raw log file (default ${TMPDIR:-/tmp}/web-demo-verify.log)
#   --port N          static port for --mode demo (default 8081)
#
# Exit codes: 0 all assertions passed · 1 an assertion failed · 2 usage/env error.
#
# Scope: CONTAINER. The cache lives outside the repository; the host is never
# modified. Endpoint guard: 127.0.0.1:8443 and https/wss on 8443 are rejected —
# inside this container that port is the DSH harness Caddy, not signalling
# (reports/70-browser-call-demo-requirements.md §2.1.1).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SIGNALING="${WEB_DEMO_SIGNALING:-ws://47.238.144.66:8443/ws}"
STUN="${WEB_DEMO_STUN:-stun:47.238.144.66:3478}"
# Default cache: NOT under ${TMPDIR:-/tmp}. This image's /tmp is a 256 MiB tmpfs
# while a full bootstrap needs ~426 MiB, so the old default (${TMPDIR}/…) could
# only ever fail with ENOSPC and once filled the tmpfs for unrelated processes
# (t10 F8). The default is the repository's parent directory — outside the
# delivered paths, on the same volume as the workspace.
REPO_PARENT="$(cd "$REPO_ROOT/.." && pwd)"
# Default cache (t8-amend-7): NEVER under ${TMPDIR:-/tmp} — this image's /tmp is a
# 256 MiB tmpfs and a bootstrap needs ~426 MiB, which used to fill the tmpfs and
# break unrelated processes. Prefer the per-user cache directory; fall back to
# the repository's parent so the default still lands on the workspace volume when
# HOME/XDG_CACHE_HOME are unset (e.g. `env -i`).
default_cache_dir() {
  local base="${XDG_CACHE_HOME:-}"
  if [ -z "$base" ] && [ -n "${HOME:-}" ]; then base="$HOME/.cache"; fi
  if [ -n "$base" ]; then
    local cand="$base/web-demo-chrome-cache"
    if mkdir -p "$cand" 2>/dev/null; then printf '%s\n' "$cand"; return 0; fi
    # e.g. this sandbox only permits writes under the workspace, so $HOME/.cache
    # is not writable even though it is owned by the current user.
    echo "note: $cand is not writable here; falling back to $REPO_PARENT/web-demo-chrome-cache" >&2
  fi
  printf '%s\n' "$REPO_PARENT/web-demo-chrome-cache"
}
CACHE="${WEB_DEMO_CACHE:-$(default_cache_dir)}"
LOG_FILE="${WEB_DEMO_LOG:-${TMPDIR:-/tmp}/web-demo-verify.log}"
MODE="probe"
STATIC_PORT="${WEB_DEMO_PORT:-8081}"
NODE="${WEB_DEMO_NODE:-node}"
# Bootstrapping writes ~426 MiB (114.9 MiB zip + 261.4 MiB unpacked browser +
# ~40 MiB .deb archives/sysroot); require head-room before downloading anything.
MIN_FREE_KB=1258291   # ≈1.2 GiB

usage() { sed -n '3,42p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
die() { echo "web-demo-verify: $*" >&2; exit 2; }

human_kb() {
  awk -v kb="$1" 'BEGIN { printf (kb >= 1048576) ? "%.1f GiB" : "%.1f MiB", (kb >= 1048576) ? kb/1048576 : kb/1024 }'
}

# Available space of the filesystem holding $1 (empty when undeterminable).
# Available space of the filesystem holding $1. When the directory does not exist
# yet, walk up to the nearest existing ancestor (no side effects) so the answer
# still describes the volume the cache would be created on.
fs_free_kb() {
  local d="$1"
  while [ ! -d "$d" ] && [ "$d" != "/" ] && [ -n "$d" ]; do d="$(dirname "$d")"; done
  df -Pk "$d" 2>/dev/null | awk 'NR==2 { print $4 }'
}

# t10 acceptance 2: refuse before any download when the target volume is too
# small, with an explicit message and EXIT=2 (never a bare ENOSPC, never a
# half-finished cache).
check_cache_space() {
  mkdir -p "$CACHE" 2>/dev/null || true
  local kb
  kb="$(fs_free_kb "$CACHE")"
  [ -n "${kb:-}" ] || return 0
  case "$kb" in ''|*[!0-9]*) return 0 ;; esac
  if [ "$kb" -lt "$MIN_FREE_KB" ]; then
    echo "web-demo-verify: cache dir $CACHE has only $(human_kb "$kb") free, but bootstrapping needs about 1.2 GiB (114.9 MiB browser zip + 261.4 MiB unpacked + ~40 MiB runtime libraries)." >&2
    echo "web-demo-verify: nothing was downloaded. Point WEB_DEMO_CACHE at a volume with more room; note this image's ${TMPDIR:-/tmp} is a 256 MiB tmpfs and cannot hold the cache." >&2
    exit 2
  fi
}

require_node() {
  command -v "$NODE" >/dev/null 2>&1 || die "$NODE not found in PATH (node >= 22 required)"
  local major
  major="$("$NODE" -p 'process.versions.node.split(".")[0]')"
  [ "$major" -ge 22 ] || die "node >= 22 required (found $("$NODE" -v))"
}

cache_size() { if [ -d "$1" ]; then du -sh "$1" 2>/dev/null | awk '{print $1}'; else echo "absent"; fi; }

cmd_print_cache() {
  echo "cache dir : $CACHE"
  echo "size      : $(cache_size "$CACHE")"
  local kb
  kb="$(fs_free_kb "$CACHE" 2>/dev/null || true)"
  if [ -n "${kb:-}" ]; then
    echo "free space: $(human_kb "$kb") available on the cache filesystem (needs about 1.2 GiB before a bootstrap)"
  else
    echo "free space: (filesystem not reachable — cache dir will be created on first bootstrap)"
  fi
  echo "log file  : $LOG_FILE"
  echo "signaling : $SIGNALING"
  if [ -d "$CACHE" ]; then
    echo "contents  :"
    find "$CACHE" -maxdepth 2 -mindepth 1 -printf '  %y %10s  %p\n' 2>/dev/null | sort -k3 | head -n 40 || true
    if [ -f "$CACHE/chrome-version.txt" ]; then echo "chrome    : $(cat "$CACHE/chrome-version.txt")"; fi
  else
    echo "contents  : (not bootstrapped yet)"
  fi
}

cmd_clean() {
  if [ ! -d "$CACHE" ]; then echo "cache already absent: $CACHE"; return 0; fi
  local size
  size="$(cache_size "$CACHE")"
  rm -rf "$CACHE"
  echo "removed cache $CACHE (freed $size)"
}

# t3-amend-3 / t8: pure-value switches for T5's independent bootstrap check.
# `--print-chrome-path` and `--print-sysroot` print exactly one non-empty path on
# stdout (diagnostics go to stderr) and exit 0; if the cache is missing they
# bootstrap first, and if that is impossible they exit 2 — never an empty value
# with exit 0.
ensure_bootstrapped() {
  local bin="$CACHE/chrome-headless-shell-linux64/chrome-headless-shell"
  [ -x "$bin" ] && return 0
  check_cache_space
  echo "web-demo-verify: chrome not bootstrapped in $CACHE yet; bootstrapping now" >&2
  WEB_DEMO_CACHE="$CACHE" "$NODE" -e '
    const { ensureChrome } = await import(process.argv[1]);
    const chrome = await ensureChrome({ cacheDir: process.env.WEB_DEMO_CACHE, log: (m) => console.error(m) });
    console.error("web-demo-verify: bootstrapped " + chrome.bin);
  ' "file://$REPO_ROOT/web/tests/lib/chrome.mjs" || return 1
  [ -x "$bin" ]
}

cmd_print_chrome_path() {
  ensure_bootstrapped || die "chrome binary unavailable in $CACHE and bootstrap failed"
  local bin="$CACHE/chrome-headless-shell-linux64/chrome-headless-shell"
  [ -x "$bin" ] || die "chrome binary missing after bootstrap: $bin"
  printf '%s\n' "$bin"
}

cmd_print_sysroot() {
  ensure_bootstrapped || die "chrome unavailable in $CACHE, so no sysroot was provisioned"
  local sysroot="$CACHE/sysroot"
  [ -d "$sysroot" ] || die "sysroot absent in $CACHE (this build resolved its libraries from the host); sysroot path: $sysroot"
  printf '%s\n' "$sysroot"
}

# Argument parsing uses `case` so scripts/doc-verify.sh's live inventory
# (reports/70 §G-1: it scans `^[ \t]*--flag)` lines inside scripts/*.sh)
# attributes these flags to this repository script — web/README.md documents them
# and SPEC V7 requires a documented flag to be attributable. Measured neutral: no
# gate-scanned document cites any of these names. The log option is spelled
# `--log-file` because bare `--log` is a host-only publish flag in
# doc/design/_generated/host-commands.md and must keep that classification.
while [ "$#" -gt 0 ]; do
  case "$1" in
    --signaling) SIGNALING="${2:?--signaling needs a value}"; shift 2 ;;
    --stun) STUN="${2:?--stun needs a value}"; shift 2 ;;
    --cache) CACHE="${2:?--cache needs a value}"; shift 2 ;;
    --log-file) LOG_FILE="${2:?--log-file needs a value}"; shift 2 ;;
    --mode) MODE="${2:?--mode needs a value}"; shift 2 ;;
    --page) MODE="demo"; PAGE_URL="${2:?--page needs a value}"; shift 2 ;;
    --port) STATIC_PORT="${2:?--port needs a value}"; shift 2 ;;
    --print-cache) cmd_print_cache; exit 0 ;;
    --print-chrome-path) cmd_print_chrome_path; exit 0 ;;
    --print-sysroot) cmd_print_sysroot; exit 0 ;;
    --clean) cmd_clean; exit 0 ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$MODE" in
  auto|probe|demo) ;;
  *) die "unknown --mode $MODE (expected auto|probe|demo)" ;;
esac

require_node

case "$SIGNALING" in
  *127.0.0.1:8443*)
    die "127.0.0.1:8443 is the DSH harness Caddy inside this container, not the signalling service. Use ws://47.238.144.66:8443/ws (or ws://172.18.0.1:8443/ws from inside the container)." ;;
  https://*|wss://*)
    die "the signalling service only speaks plain http/ws on 8443 (https/wss fails with a TLS error). Use ws://47.238.144.66:8443/ws (no TLS, no certificate)." ;;
esac

VERIFY_JS="$REPO_ROOT/web/tests/verify-two-page.mjs"
[ -f "$VERIFY_JS" ] || die "verification harness missing: $VERIFY_JS"

echo "== web-demo-verify =="
echo "repo      : $REPO_ROOT"
echo "node      : $("$NODE" -v)"
echo "cache     : $CACHE ($(cache_size "$CACHE") before)"
echo "log       : $LOG_FILE"
echo "signaling : $SIGNALING"
echo "stun      : $STUN"
echo "mode      : $MODE"
echo ""

set +e
# Fail fast (EXIT=2, nothing downloaded) when the cache volume cannot hold a
# bootstrap; a warm cache is never re-checked.
if [ ! -x "$CACHE/chrome-headless-shell-linux64/chrome-headless-shell" ]; then
  check_cache_space
fi

VERIFY_ARGS=(--mode "$MODE" --signaling "$SIGNALING" --stun "$STUN" --cache "$CACHE" --log "$LOG_FILE" --port "$STATIC_PORT")
if [ "$MODE" = "demo" ] && [ -n "${PAGE_URL:-}" ]; then VERIFY_ARGS+=(--page "$PAGE_URL"); fi
"$NODE" "$VERIFY_JS" "${VERIFY_ARGS[@]}"
status=$?
set -e

echo ""
echo "cache     : $CACHE ($(cache_size "$CACHE") after)"
echo "raw log   : $LOG_FILE"
if [ "$status" -eq 0 ]; then
  echo "result    : PASS"
else
  echo "result    : FAIL (exit $status)"
fi
exit "$status"
