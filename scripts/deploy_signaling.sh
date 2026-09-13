#!/usr/bin/env bash
# deploy_signaling.sh — 把 Go 信令服务部署到宿主机并以 systemd 常驻（t12 产物，可复跑/幂等）
#
# 用法（在宿主机上，root 或 sudo 执行）：
#   bash scripts/deploy_signaling.sh
#   PUBLIC_IP=47.238.144.66 TURN_USER=demo TURN_PASS=demopass bash scripts/deploy_signaling.sh
#
# 依赖：<workspace>/code/webrtc-demo/signaling/dist/signaling-linux-amd64（t9 产物）
# 依据：doc/12 §12.2、doc/13 阶段 2.1、doc/01 §6
set -euo pipefail

# ---------------------------------------------------------------- 可覆盖变量
PUBLIC_IP="${PUBLIC_IP:-47.238.144.66}"        # coturn 实测公网 IP（reports/06-coturn.md §2）
STUN_PORT="${STUN_PORT:-3478}"
TURN_PORT="${TURN_PORT:-3478}"
TURN_TRANSPORT="${TURN_TRANSPORT:-udp}"
TURN_USER="${TURN_USER:-demo}"
TURN_PASS="${TURN_PASS:-demopass}"
LISTEN_ADDR="${LISTEN_ADDR:-:8443}"
LOG_FILE="${LOG_FILE:-/var/log/signaling/signaling.log}"
BIN_DST="${BIN_DST:-/opt/signaling/signaling}"
UNIT="${UNIT:-/etc/systemd/system/signaling.service}"
WS_PATH="${WS_PATH:-/ws}"
HEALTH_PATH="${HEALTH_PATH:-/healthz}"

# 工作区定位（宿主机路径；容器内同一目录是 /data/dsh/home/workspace）
WORKSPACE="${WORKSPACE:-/opt/dsh-workspaces}"
SRC_BIN="${SRC_BIN:-${WORKSPACE}/code/webrtc-demo/signaling/dist/signaling-linux-amd64}"

log() { printf '[deploy_signaling] %s\n' "$*"; }
die() { printf '[deploy_signaling] ERROR: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "需要 root（或 sudo bash $0）"
[ -f "$SRC_BIN" ] || die "找不到信令二进制: $SRC_BIN（先完成 t9）"

# ------------------------------------------------------------------ 1) 二进制
log "安装二进制: $SRC_BIN -> $BIN_DST"
mkdir -p "$(dirname "$BIN_DST")" "$(dirname "$LOG_FILE")"
install -m 0755 "$SRC_BIN" "$BIN_DST"
sha256sum "$BIN_DST"
"$BIN_DST" -version || true

# ------------------------------------------------------------------ 2) unit 文件
log "写入 systemd unit: $UNIT"
cat > "$UNIT" <<EOF
[Unit]
Description=WebRTC Demo Signaling Server
After=network.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$(dirname "$BIN_DST")
ExecStart=${BIN_DST} -addr ${LISTEN_ADDR} -stun stun:${PUBLIC_IP}:${STUN_PORT} -turn turn:${PUBLIC_IP}:${TURN_PORT}?transport=${TURN_TRANSPORT} -user ${TURN_USER}:${TURN_PASS} -log ${LOG_FILE}
Restart=always
RestartSec=3
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

# ------------------------------------------------------------------ 3) 启停
systemctl daemon-reload
systemctl enable signaling
systemctl restart signaling
sleep 3

# ------------------------------------------------------------------ 4) 验收
log "systemctl is-active  = $(systemctl is-active signaling)"
log "systemctl is-enabled = $(systemctl is-enabled signaling)"
systemctl --no-pager --full status signaling | head -12
ss -lntp | grep -F "${LISTEN_ADDR#:}" || die "端口 ${LISTEN_ADDR} 未监听"
curl -s -o /dev/null -w "curl http://127.0.0.1${LISTEN_ADDR}${WS_PATH} -> %{http_code} (期望 400 升级要求)\n" \
     --max-time 5 "http://127.0.0.1${LISTEN_ADDR}${WS_PATH}"
curl -s --max-time 5 "http://127.0.0.1${LISTEN_ADDR}${HEALTH_PATH}" | head -3
ls -l "$(dirname "$LOG_FILE")"
tail -3 "$LOG_FILE"
log "完成。ICE 配置: stun:${PUBLIC_IP}:${STUN_PORT} / turn:${PUBLIC_IP}:${TURN_PORT}?transport=${TURN_TRANSPORT} (${TURN_USER}:${TURN_PASS})"
log "端到端验证: node code/webrtc-demo/scripts/verify_signal_e2e.mjs ws://${PUBLIC_IP}${LISTEN_ADDR}/ws"
