#!/bin/bash
# t5 收尾链：等当前 2-target ninja 结束后 → aar（手工打 AAR）→ extract（产物收集+核验）
# 说明：契约 §4.3 硬交付=jar + so；可选静态库 webrtc.a 默认不做（BUILD_WEBRTC_A=0）。
BUILD=/opt/dsh-workspaces/webrtc-build
L=$BUILD/logs
log() { echo "[$(date -u +%FT%TZ)] [tail] $*" >> "$L/supervisor3.log"; }

log "等待当前 ninja 结束（最多 30h）..."
for i in $(seq 1 10800); do
  if ! pgrep -x ninja >/dev/null 2>&1 && ! pgrep -f '^/opt/dsh-workspaces/webrtc-build/src/third_party/ninja/ninja' >/dev/null 2>&1; then
    log "ninja 已结束 (等待 $((i*10))s)"; break
  fi
  sleep 10
done

log "=== aar（手工组装 Java SDK AAR）==="
bash "$BUILD/t5-build.sh" aar > "$L/03-aar.log" 2>&1; log "aar rc=$?"
log "=== extract（产物收集+AArch64 核验）==="
bash "$BUILD/t5-build.sh" extract > "$L/04-extract.log" 2>&1; log "extract rc=$?"
log "===== TAIL DONE ====="
