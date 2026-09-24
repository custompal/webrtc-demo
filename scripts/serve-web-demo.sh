#!/usr/bin/env bash
# =============================================================================
# serve-web-demo.sh — start/stop/status for the pure-static browser demo page.
#
# The demo page is plain static files (no build, no bundler). It must be opened
# from `http://localhost:8081/`: that origin is a *secure context*, so
# getUserMedia works without TLS, and the port is the only one that needs
# forwarding over VSCode Remote-SSH. The signalling connection goes straight to
# `ws://47.238.144.66:8443/ws` and does **not** need port forwarding
# (reports/70-browser-call-demo-requirements.md §M-2, §2.1.1).
#
#   bash scripts/serve-web-demo.sh start   [--port 8081] [--root web] [--host 127.0.0.1] [--engine auto|node|python3]
#   bash scripts/serve-web-demo.sh status
#   bash scripts/serve-web-demo.sh stop
#   bash scripts/serve-web-demo.sh restart
#
# SSH / non-interactive friendly (t3 supplement): no TTY and no prompts are
# required, the service is detached with setsid+nohup, the pid and the log are
# flat files directly under HOST /tmp, and `start` always prints the address it
# actually listens on. Intended remote invocation:
#
#   ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
#       -i <key> -p <ssh-port> root@<host> \
#       'bash /opt/dsh-workspaces/code/webrtc-demo/scripts/serve-web-demo.sh start'
#
# Zero install (ruling r1): this script only *runs* what the host
# already has (python3). It contains no package-manager or download-and-execute
# step of any kind.
#
# Environment overrides: WEB_DEMO_PORT, WEB_DEMO_ROOT, WEB_DEMO_HOST,
#                        WEB_DEMO_RUN_DIR, WEB_DEMO_ENGINE, WEB_DEMO_NODE.
#
# Exit codes: 0 ok (start ok / running / stopped) · 1 not running, port busy,
# startup failure, or `stop` could not release the port · 2 bad usage.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PORT="${WEB_DEMO_PORT:-8081}"
ROOT="${WEB_DEMO_ROOT:-$REPO_ROOT/web}"
HOST="${WEB_DEMO_HOST:-127.0.0.1}"
ENGINE="${WEB_DEMO_ENGINE:-auto}"
# One run directory per uid under HOST /tmp (t10 F7). Two users on the same host
# must not collide on the same pid/state/log files: with fs.protected_regular=2 a
# stale file owned by another uid cannot be reopened even by root, so a shared
# name makes one user's leftovers break another user's `start`.
RUN_DIR="${WEB_DEMO_RUN_DIR:-${TMPDIR:-/tmp}/web-demo-serve-$(id -u)}"
NODE="${WEB_DEMO_NODE:-node}"

PID_FILE="$RUN_DIR/web-demo-serve.pid"
STATE_FILE="$RUN_DIR/web-demo-serve.state"
LOG_FILE="$RUN_DIR/web-demo-serve.log"
NODE_SERVER_JS="$REPO_ROOT/web/tests/lib/serve.mjs"

usage() { sed -n '3,42p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
die() { echo "serve-web-demo: $*" >&2; exit 2; }

node_ok() {
  command -v "$NODE" >/dev/null 2>&1 || return 1
  local major
  major="$("$NODE" -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)"
  [ "$major" -ge 22 ]
}

python_ok() { command -v python3 >/dev/null 2>&1; }

pick_engine() {
  case "$ENGINE" in
    auto)
      # Host first choice is python3 (`python3 -m http.server`, run not
      # installed — reports/70 §0.2). This container has no python3, so the
      # engine falls back to the bundled node server, which is the documented
      # container path and additionally logs every request.
      if python_ok; then echo python3; return 0; fi
      if node_ok && [ -f "$NODE_SERVER_JS" ]; then echo node; return 0; fi
      die "no usable engine: need python3 or node >= 22 (with web/tests/lib/serve.mjs)"
      ;;
    node)
      node_ok || die "node >= 22 not found in PATH (or WEB_DEMO_NODE is wrong)"
      [ -f "$NODE_SERVER_JS" ] || die "static server module not found: $NODE_SERVER_JS"
      echo node ;;
    python3)
      python_ok || die "python3 not found in PATH"
      echo python3 ;;
    *) die "unknown --engine $ENGINE (expected auto|node|python3)" ;;
  esac
}

pid_alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

# The pid file alone cannot say *which* endpoint an instance serves, so the port
# and host are recorded next to it. Without that, `start --port 8090` while an
# instance runs on 8081 would wrongly answer "already running … 8090" and hide a
# genuinely busy port (t3 supplement §3: a busy port must fail loudly).
state_get() {
  [ -f "$STATE_FILE" ] || return 1
  local value
  value="$(sed -n "s/^$1=//p" "$STATE_FILE" 2>/dev/null | head -n 1)"
  [ -n "$value" ] || return 1
  printf '%s\n' "$value"
}

write_state() {
  printf 'pid=%s\nport=%s\nhost=%s\nengine=%s\nroot=%s\n' "$1" "$PORT" "$HOST" "$2" "$ROOT" > "$STATE_FILE" || return 1
  printf '%s\n' "$1" > "$PID_FILE" || return 1
  return 0
}

clear_state() { rm -f "$PID_FILE" "$STATE_FILE"; }

# /tmp on this host carries fs.protected_regular=2: an existing file owned by
# another uid cannot be opened for writing even by root, while removing it is
# allowed. A stale log from an earlier run must therefore not surface as an
# unexplained "Permission denied" — recover once, loudly, and only then fail.
prepare_runtime() {
  mkdir -p "$RUN_DIR" 2>/dev/null || true
  if ( : >>"$LOG_FILE" ) 2>/dev/null; then return 0; fi
  local owner
  owner="$(stat -c '%U:%G mode=%a' "$LOG_FILE" 2>/dev/null || echo 'unknown')"
  echo "note: $LOG_FILE is not writable (owner $owner); attempting to remove the stale file"
  rm -f "$LOG_FILE" 2>/dev/null || true
  if ( : >>"$LOG_FILE" ) 2>/dev/null; then
    echo "note: stale $LOG_FILE removed (was $owner)"
    return 0
  fi
  echo "serve-web-demo: cannot write $LOG_FILE (owner $owner, uid $(id -u))" >&2
  echo "serve-web-demo: remove it manually, or set WEB_DEMO_RUN_DIR to a writable directory." >&2
  # Environment error, not a runtime failure: an unwritable run directory must
  # be reported as EXIT=2 (never as a bare EACCES, never as a generic failure).
  die "unwritable run directory: $RUN_DIR"
}

# Alive pid of *our* instance (any endpoint); stale files are cleaned up.
running_pid() {
  [ -f "$PID_FILE" ] || return 1
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  pid="${pid//[^0-9]/}"
  if ! pid_alive "$pid"; then clear_state; return 1; fi
  printf '%s\n' "$pid"
}

# ---------------------------------------------------------------------------
# Liveness primitives (t8 F1/F2). The host that serves the page for the user's
# VSCode Remote-SSH session has **no node**, so no probe may depend on node.
# Resolution order: bash /dev/tcp → nc -z → python3 socket → node. The first
# primitive that actually works here is used; if none works, every command that
# needs a probe FAILS LOUDLY with EXIT=2 instead of silently reporting
# "not listening" (t8 acceptance §3).
# ---------------------------------------------------------------------------
PROBE_KIND="${WEB_DEMO_PROBE:-auto}"
PROBE_RESOLVED=""
PROBE_NOTE=""

probe_usable() {
  case "$1" in
    devtcp)
      local err
      err="$( (exec 3<>/dev/tcp/127.0.0.1/1) 2>&1 )" && return 0
      case "$err" in
        *"No such file or directory"*) return 1 ;;   # net redirections unavailable
        *) return 0 ;;                                # refused/timeout => mechanism works
      esac ;;
    nc)
      command -v nc >/dev/null 2>&1 || return 1
      nc -z -w 1 127.0.0.1 1 >/dev/null 2>&1
      case "$?" in 0|1) return 0 ;; *) return 1 ;; esac ;;
    python3)
      command -v python3 >/dev/null 2>&1 || return 1
      python3 -c 'import socket,sys
s=socket.socket(); s.settimeout(1)
try: s.connect(("127.0.0.1",1))
except OSError: sys.exit(1)' >/dev/null 2>&1
      case "$?" in 0|1) return 0 ;; *) return 1 ;; esac ;;
    node)
      command -v "$NODE" >/dev/null 2>&1 || return 1
      "$NODE" -e 'const net=require("net");const s=net.connect(1,"127.0.0.1");s.setTimeout(1000);
s.on("connect",()=>{s.destroy();process.exit(0)});s.on("timeout",()=>{s.destroy();process.exit(1)});
s.on("error",()=>process.exit(1));' >/dev/null 2>&1
      case "$?" in 0|1) return 0 ;; *) return 1 ;; esac ;;
    *)
      return 1 ;;
  esac
}

probe_selftest() {
  if probe_usable "$1"; then return 0; fi
  if [ "$1" = node ] && ! command -v "$NODE" >/dev/null 2>&1; then
    PROBE_NOTE="$PROBE_NOTE node=absent"
  else
    PROBE_NOTE="$PROBE_NOTE $1=unusable"
  fi
  return 1
}

resolve_probe() {
  [ -n "$PROBE_RESOLVED" ] && return 0
  local candidate
  case "$PROBE_KIND" in
    auto)
      for candidate in devtcp nc python3 node; do
        if probe_selftest "$candidate"; then PROBE_RESOLVED="$candidate"; return 0; fi
      done ;;
    devtcp|nc|python3|node)
      if probe_selftest "$PROBE_KIND"; then PROBE_RESOLVED="$PROBE_KIND"; return 0; fi ;;
    *) die "unknown WEB_DEMO_PROBE=$PROBE_KIND (expected auto|devtcp|nc|python3|node)" ;;
  esac
  return 1
}

# 0 = listening, 1 = closed, EXIT=2 = no usable primitive at all.
probe_tcp() {
  local host="$1" port="$2"
  if ! resolve_probe; then
    echo "serve-web-demo: no usable TCP liveness primitive (WEB_DEMO_PROBE=$PROBE_KIND;$PROBE_NOTE)" >&2
    echo "serve-web-demo: none of bash /dev/tcp, nc, python3 or node can probe $host:$port here," >&2
    echo "serve-web-demo: so refusing to guess whether the port is open (that would silently report 'not listening')." >&2
    exit 2
  fi
  case "$PROBE_RESOLVED" in
    devtcp)  (exec 3<>"/dev/tcp/$host/$port") >/dev/null 2>&1 ;;
    nc)      nc -z -w 1 "$host" "$port" >/dev/null 2>&1 ;;
    python3) python3 -c 'import socket,sys
s=socket.socket(); s.settimeout(1)
try: s.connect((sys.argv[1], int(sys.argv[2])))
except OSError: sys.exit(1)' "$host" "$port" >/dev/null 2>&1 ;;
    node)    "$NODE" -e 'const net=require("net");const s=net.connect(Number(process.argv[2]),process.argv[1]);
s.setTimeout(1000);s.on("connect",()=>{s.destroy();process.exit(0)});s.on("timeout",()=>{s.destroy();process.exit(1)});
s.on("error",()=>process.exit(1));' "$host" "$port" >/dev/null 2>&1 ;;
    *) exit 2 ;;
  esac
}

tcp_open() { probe_tcp "$1" "$2"; }
port_open() { tcp_open "$HOST" "$PORT"; }

probe_label() { if resolve_probe; then printf '%s' "$PROBE_RESOLVED"; else printf 'unavailable'; fi; }

# Detach so the service survives the SSH session that started it.
spawn_detached() {
  if command -v setsid >/dev/null 2>&1; then
    setsid nohup "$@" </dev/null >>"$LOG_FILE" 2>&1 &
  else
    nohup "$@" </dev/null >>"$LOG_FILE" 2>&1 &
  fi
  echo $!
}

cmd_start() {
  [ -d "$ROOT" ] || die "document root does not exist: $ROOT"
  local engine
  engine="$(pick_engine)"
  if [ "$ENGINE" = "auto" ]; then
    if [ "$engine" = "node" ] && ! python_ok; then
      echo "note: python3 not found in PATH, so the bundled node static server is used (reports/70 §0.2)"
    elif [ "$engine" = "python3" ]; then
      echo "note: serving with python3 -m http.server (run, not installed)"
    fi
  fi
  prepare_runtime || return 1

  local pid
  if pid="$(running_pid)"; then
    local rport rhost rengine
    rport="$(state_get port || echo '?')"
    rhost="$(state_get host || echo '?')"
    rengine="$(state_get engine || echo '?')"
    if [ "$rport" = "$PORT" ] && [ "$rhost" = "$HOST" ]; then
      echo "already running (pid $pid, engine $rengine) — listening $rhost:$rport"
      echo "url         : http://localhost:$rport/"
      echo "pid file    : $PID_FILE"
      echo "log file    : $LOG_FILE"
      return 0
    fi
    echo "serve-web-demo: an instance is already running (pid $pid) on $rhost:$rport, but you asked for $HOST:$PORT." >&2
    echo "serve-web-demo: refusing to start a second instance behind one pid file." >&2
    echo "serve-web-demo: stop it first (bash scripts/serve-web-demo.sh stop), or set a separate WEB_DEMO_RUN_DIR." >&2
    return 1
  fi
  if port_open; then
    echo "serve-web-demo: $HOST:$PORT is already in use by another process; refusing to start (no silent port change)." >&2
    echo "serve-web-demo: pick another --port, or stop whatever holds it, then retry." >&2
    return 1
  fi

  : > "$LOG_FILE"
  if [ "$engine" = node ]; then
    pid="$(spawn_detached "$NODE" "$NODE_SERVER_JS" --root "$ROOT" --port "$PORT" --host "$HOST")"
  else
    pid="$(spawn_detached python3 -m http.server "$PORT" --bind "$HOST" --directory "$ROOT")"
  fi
  if ! write_state "$pid" "$engine"; then
    echo "serve-web-demo: cannot write $PID_FILE / $STATE_FILE (uid $(id -u))" >&2
    kill "$pid" 2>/dev/null || true
    die "unwritable run directory: $RUN_DIR"
  fi

  local waited=0
  while [ "$waited" -lt 100 ]; do
    if ! pid_alive "$pid"; then
      echo "serve-web-demo: server exited during startup; last log lines:" >&2
      tail -n 20 "$LOG_FILE" >&2 || true
      clear_state
      return 1
    fi
    if port_open; then
      echo "started     : $(cd "$ROOT" && pwd)"
      echo "listening   : $HOST:$PORT"
      echo "url         : http://localhost:$PORT/"
      echo "pid         : $pid (engine $engine)"
      echo "pid file    : $PID_FILE"
      echo "log file    : $LOG_FILE"
      echo "liveness    : $(probe_label) probe on $HOST:$PORT"
      echo "next        : VSCode PORTS 面板转发 $PORT → 在 Chrome 打开 http://localhost:$PORT/"
      echo "signalling  : dialled directly as ws://47.238.144.66:8443/ws — 8443 must NOT be forwarded"
      echo "              (container-local equivalent: ws://172.18.0.1:8443/ws)"
      return 0
    fi
    sleep 0.1
    waited=$((waited + 1))
  done
  echo "serve-web-demo: server did not become ready within 10s; last log lines:" >&2
  tail -n 20 "$LOG_FILE" >&2 || true
  kill "$pid" 2>/dev/null || true
  clear_state
  return 1
}

cmd_stop() {
  local pid rport rhost
  if ! pid="$(running_pid)"; then
    echo "not running (nothing to stop)"
    clear_state
    return 0
  fi
  # Verify release against the endpoint this instance actually recorded.
  rport="$(state_get port || echo "$PORT")"
  rhost="$(state_get host || echo "$HOST")"

  kill "$pid" 2>/dev/null || true
  local waited=0
  while [ "$waited" -lt 50 ] && pid_alive "$pid"; do
    sleep 0.1
    waited=$((waited + 1))
  done
  if pid_alive "$pid"; then
    kill -9 "$pid" 2>/dev/null || true
    waited=0
    while [ "$waited" -lt 20 ] && pid_alive "$pid"; do sleep 0.1; waited=$((waited + 1)); done
  fi
  clear_state

  # Release is verified, not assumed: the port must actually be closed again.
  waited=0
  while [ "$waited" -lt 50 ] && tcp_open "$rhost" "$rport"; do
    sleep 0.1
    waited=$((waited + 1))
  done
  if tcp_open "$rhost" "$rport"; then
    echo "serve-web-demo: pid $pid was killed but $rhost:$rport is still accepting connections (another process holds it?)" >&2
    return 1
  fi
  if pid_alive "$pid"; then
    echo "serve-web-demo: pid $pid is still alive after SIGKILL" >&2
    return 1
  fi
  echo "stopped     : pid $pid"
  echo "released    : $rhost:$rport (verified closed)"
  echo "state files : $PID_FILE, $STATE_FILE (removed)"
  return 0
}

cmd_status() {
  local pid
  if pid="$(running_pid)"; then
    local rport rhost rengine rroot
    rport="$(state_get port || echo "$PORT")"
    rhost="$(state_get host || echo "$HOST")"
    rengine="$(state_get engine || echo '?')"
    rroot="$(state_get root || echo "$ROOT")"
    if tcp_open "$rhost" "$rport"; then
      echo "status      : running"
      echo "pid         : $pid (engine $rengine)"
      echo "listening   : $rhost:$rport"
      echo "url         : http://localhost:$rport/"
      echo "root        : $rroot"
      echo "log file    : $LOG_FILE"
      if [ "$rport" != "$PORT" ] || [ "$rhost" != "$HOST" ]; then
        echo "note        : this instance serves $rhost:$rport (you asked about $HOST:$PORT)"
      fi
      return 0
    fi
    echo "status      : broken (pid $pid alive, but $rhost:$rport does not accept connections)" >&2
    return 1
  fi
  # Never assert "free" without being able to measure it (t8 acceptance §3):
  # if no primitive works, port_open exits 2 with an explicit reason.
  if port_open; then
    echo "status      : not a managed instance, but $HOST:$PORT IS accepting connections (held by another process)" >&2
    return 1
  fi
  echo "status      : not running ($HOST:$PORT free, verified by $(probe_label) probe)"
  return 1
}

# Argument parsing uses `case` so scripts/doc-verify.sh's live inventory
# (reports/70 §G-1: it scans `^[ \t]*--flag)` lines in scripts/*.sh) attributes
# these flags to this repository script — web/README.md documents them and SPEC V7
# requires a documented flag to be attributable. Measured neutral: no gate-scanned
# document cites any of these names, so the full-run checks/warnings are unchanged.
command="${1:-}"
if [ "$#" -gt 0 ]; then shift; fi

while [ "$#" -gt 0 ]; do
  case "$1" in
    --port) PORT="${2:?--port needs a value}"; shift 2 ;;
    --root) ROOT="${2:?--root needs a value}"; shift 2 ;;
    --host) HOST="${2:?--host needs a value}"; shift 2 ;;
    --engine) ENGINE="${2:?--engine needs a value}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

# Fail fast on an unusable probe setting: every command that reports on the
# port needs a primitive, and a typo must not degrade into a silent "free".
case "$PROBE_KIND" in
  auto|devtcp|nc|python3|node) ;;
  *) die "unknown WEB_DEMO_PROBE=$PROBE_KIND (expected auto|devtcp|nc|python3|node)" ;;
esac

case "$command" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  restart) cmd_stop; cmd_start ;;
  ""|--help|-h) usage ;;
  *) die "unknown command: $command (expected start|stop|status|restart)" ;;
esac
