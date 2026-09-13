#!/bin/bash
# t5 supervisor: 等 gclient runhooks 结束后，串行执行 gn gen -> ninja -> aar -> extract
# 设计：每阶段独立日志（build/logs/0X-*.log），完全脱离 SSH 会话，可安全断线
BUILD=/opt/dsh-workspaces/webrtc-build
L=$BUILD/logs
mkdir -p "$L"
log() { echo "[$(date -u +%FT%TZ)] [sup] $*" >> "$L/supervisor.log"; }

log "等待 runhooks 进程结束..."
for i in $(seq 1 240); do   # 最多 4h
  if ! pgrep -f "gclient.py runhooks" >/dev/null 2>&1; then log "runhooks 已结束 (等待 ${i}0s)"; break; fi
  sleep 10
done
pgrep -f "gclient.py runhooks" >/dev/null 2>&1 && log "WARN runhooks 超时未结束，仍继续（可能已实质完成）"

log "=== 阶段 1/4: gn gen ==="
bash "$BUILD/t5-build.sh" gn > "$L/01-gn.log" 2>&1
grc=$?
log "gn gen rc=$grc"
[ "$grc" != 0 ] && { log "FATAL gn gen 失败，终止"; exit 1; }

log "=== 阶段 2/4: ninja (Java SDK + webrtc .a) ==="
bash "$BUILD/t5-build.sh" build > "$L/02-build.log" 2>&1
log "build rc=$?"

log "=== 阶段 3/4: aar (build_aar.py) ==="
bash "$BUILD/t5-build.sh" aar > "$L/03-aar.log" 2>&1
log "aar rc=$?"

log "=== 阶段 4/4: extract (产物收集+核验) ==="
bash "$BUILD/t5-build.sh" extract > "$L/04-extract.log" 2>&1
log "extract rc=$?"

log "===== SUPERVISOR DONE ====="
