# 38 — t67 房间宽限期修复：编译并在宿主机部署（systemd 替换 + 活体验证）

- 任务：t70（attempt 1 / attempt_id `322dfde0-50f3-4e31-8a2e-b278d437742c`）
- 责任人：coturn-installer
- 时间：2026-09-15 23:45–23:47 CST
- 宿主机：`root@172.21.0.219:5766`；现网服务 `signaling.service`（`:8443`）
- 全程**未改任何源码/app/third_party/doc**，**未做任何 git 提交**；仓内仅新增本报告与 `signaling/logs/t70-deploy-live-evidence.log`

## 0. 结论

| 项 | 结果 |
|---|---|
| 新二进制（t67 修复）编译 | ✅ `gofmt -l .` 无输出、`go vet ./...` 退出码 0、`CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath` 成功 |
| 宿主机替换 | ✅ `/opt/signaling/signaling`：`c298235a…`(5 496 984 B) → **`8708629e…`(8 166 823 B)**；旧件备份 `/opt/signaling/bak-t70-20260915T234518` |
| unit 显式宽限期参数 | ✅ 备份 `/opt/signaling/signaling.service.bak-t70-20260915T234524`，`ExecStart` 追加 `-room-grace 90s`；`daemon-reload` + `restart` 后 **active (running)**，PID 860227 |
| `/healthz` 新字段 | ✅ `"roomGraceSec": 90`、`"seatTakeovers": 0`、`"graceExpired": 0` |
| 活体 a：硬断开后宽限期内 0 次 `peerLeft` | ✅ 官方 `TestLive_GraceReconnect` PASS + 独立探针（`terminate()` → `close 1006`）8 s 内 0 次 |
| 活体 b：同身份重连 `joined`（同 peerId）+ 在线方 `peerJoined` | ✅ 两条途径均 PASS（`peer-002` 席位接管） |
| 活体 c：宽限期满恰好 1 次 `peerLeft` | ✅ 临时实例 `-room-grace 3s`（18443）实测 +3120 ms 收到 1 次，`graceExpired=1`，日志 `peer_left_sent` 恰 1 条 |
| 公网复验 | ✅ `ws://47.238.144.66:8443/ws`：宽限期内 0 次 peerLeft + 同身份重连 PASS；`scripts/verify_signal_e2e.mjs` **19/19 PASS** |
| coturn 未受影响 | ✅ `coturn` 与 `signaling` 均 active + enabled |

**现网 room-grace 生效值 = `90s`**（`-room-grace 90s` 显式写入 unit；`/healthz.roomGraceSec=90`、启动日志 `room_grace_ms=90000`）。

## 1. 前置阅读（flag 形态以实测为准）

- `reports/35-room-grace.md` §4/§5.1/§6：宽限期默认 90 s（`0` 退化为旧行为）、活动日志事件 `ws_close … seat_kept=true` / `room_peer_offline_grace` / `seat_takeover` / `grace_expired*`.
- 源码 `signaling/main.go:45`：`flag.Duration("room-grace", cfg.RoomGrace, …)` ⇒ **`-room-grace` 是 duration**，需写 `90s`（不是裸数字）。
- 新二进制实测 `-h`：
```
$ /opt/signaling/signaling -h | grep -A1 room-grace
  -room-grace duration
```
（`signaling/README.md` 无该 flag 的条目，故以源码 + `-h` 实测为准。）

## 2. 编译（容器内交叉编译 linux/amd64）

```
$ bash -lc 'source /data/dsh/home/workspace/env-go.sh && cd …/code/webrtc-demo/signaling \
    && gofmt -l . && go vet ./... \
    && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o dist/signaling-linux-amd64 .'
=== gofmt -l . ===
(gofmt 无输出=已格式化)
=== go vet ./... ===
(vet 退出码 0)
=== build ===
BUILD OK
-rwxr-xr-x 1 node node 8166823 Sep 15 23:45 dist/signaling-linux-amd64
8708629ee152eb6b70367f32cee793aad16d56b6aaa6db2a648508b3585a62f5  dist/signaling-linux-amd64
```
（`dist/` 属既有构建产物路径且被 `.gitignore` 忽略，未新增仓内跟踪文件。）

## 3. 宿主机替换与 unit 改动

```
# 旧件备份 + 旧哈希
$ cp -a /opt/signaling/signaling /opt/signaling/bak-t70-20260915T234518
OLD  c298235a0c4b1afe0cb8988274d36770da105a03c4f5577cb6fe799b37b4c068   5496984 B

# 安装新件（容器与宿主机共享 /opt/dsh-workspaces，无需 scp）
$ install -m 0755 -o root -g root …/signaling/dist/signaling-linux-amd64 /opt/signaling/signaling
NEW  8708629ee152eb6b70367f32cee793aad16d56b6aaa6db2a648508b3585a62f5   8166823 B
$ file /opt/signaling/signaling
ELF 64-bit LSB executable, x86-64, statically linked, with debug_info, not stripped

# unit：备份后追加 -room-grace 90s
$ cp -a /etc/systemd/system/signaling.service /opt/signaling/signaling.service.bak-t70-20260915T234524
$ diff /opt/signaling/signaling.service.bak-t70-20260915T234524 /etc/systemd/system/signaling.service
9c9
< ExecStart=… -user demo:demopass -log /var/log/signaling/signaling.log
---
> ExecStart=… -user demo:demopass -log /var/log/signaling/signaling.log -room-grace 90s
$ systemctl daemon-reload && systemctl restart signaling
$ systemctl is-active signaling ; systemctl is-enabled signaling
active
enabled
$ systemctl status signaling | head -8
● signaling.service - WebRTC Demo Signaling Server
     Active: active (running) since Tue 2026-09-15 23:45:25 CST
   Main PID: 860227 (signaling)
     CGroup: /system.slice/signaling.service
             └─860227 /opt/signaling/signaling … -log /var/log/signaling/signaling.log -room-grace 90s

# journal：启动参数（room_grace_ms=90000），无 error
$ journalctl -u signaling --since "-20min" | grep -aE "server_start|listening"
… server_start listen_addr=:8443 … room_expiry_s=1800 room_grace_ms=90000 send_timeout_ms=5000 stun_url=stun:47.238.144.66:3478 turn_url=turn:47.238.144.66:3478?transport=udp …
… listening actual_addr=[::]:8443 addr=:8443 health_path=/healthz … ws_path=/ws
（仅有的 WARN 是既有 `config_warning warning=TURN_凭据仍是文档示例值_demo/demopass`，与本任务无关）
```

## 4. `/healthz`（新字段）

```
$ curl -s http://127.0.0.1:8443/healthz
{
  "activeConns": 0, "addr": ":8443", "graceExpired": 0, "maxMessageBytes": 65536,
  "roomExpirySec": 1800, "roomGraceSec": 90, "roomIds": [], "rooms": 0,
  "roomsCreated": 0, "roomsDestroyed": 0, "seatTakeovers": 0,
  "serverTimeMillis": 1789487162834, "status": "ok",
  "stunUrl": "stun:47.238.144.66:3478", "totalConns": 0,
  "turnUrl": "turn:47.238.144.66:3478?transport=udp", "uptimeSec": 37, "version": "0.1.0"
}
```

## 5. 活体验证

### 5.1 官方活体测试（t67 作者提供，打到**已部署**的 8443）

```
$ cd /opt/dsh-workspaces/code/webrtc-demo/signaling && . /opt/dsh-workspaces/env-go.sh
$ SIGNALING_WS_URL=ws://127.0.0.1:8443/ws go test ./server -run "TestLive" -v -count=1
--- PASS: TestLive_FullCallFlow (0.03s)        # create→created→join→joined→peerJoined→offer/answer/ice/natType→ping/pong→leave→peerLeft→close 1000
--- PASS: TestLive_GraceReconnect (0.02s)
    grace_test.go:374: 活体验证通过：房间 6TTAPH 瞬断后席位保留、同身份重连成功、宽限期内无 peerLeft
PASS
ok  	webrtcdemo-signaling/server	0.063s
```

### 5.2 独立 Node 探针（容器 → 现网 8443；`terminate()` 硬断开 = 真机瞬断等价物）

```
$ node t70_grace_probe.cjs ws://172.21.0.219:8443/ws keep 8000
+   130ms [JOINER] ## terminate() 硬断开（不发 close 帧）
+   134ms [JOINER] == closed code=1006               # 1006 = abnormal closure（非正常关闭）
+  8132ms 宽限期内 HOST 收到 peerLeft 次数 = 0        # ← 宽限期生效的直接证据
+  8141ms [REJOIN] => {"type":"join","roomId":"Q62EE4"}
+  8143ms [REJOIN] <= {"type":"joined",…,"peerId":"peer-002"}   # 同身份席位接管
+  8144ms [HOST] <= {"type":"peerJoined","peerId":"peer-002"}
RESULT: PASS — 硬断开后宽限期内 0 次 peerLeft；同身份(peerId=peer-002)重连成功且在线方收到 peerJoined（room=Q62EE4）
```

### 5.3 现网服务端日志（关键事件）

```
$ grep -aE "room_peer_offline_grace|seat_kept|seat_takeover|peer_left_sent" /var/log/signaling/signaling.log | tail
… room_created peer=peer-001 role=host room=Q62EE4
… room_joined  peer=peer-002 peers=2 room=Q62EE4
… ws_close active_conns=1 conn=7 duration_ms=94 error=websocket:_close_1006_(abnormal_closure) grace_ms=90000 peer=peer-002 reason=unexpected-close room=Q62EE4 room_destroyed=false seat_kept=true
… room_peer_offline_grace grace_ms=90000 peer=peer-002 room=Q62EE4
… seat_takeover peer=peer-002 peers=2 prev_remote=172.18.0.2:33074 remote=172.18.0.2:39400 room=Q62EE4 seat_takeover=true
（同一轮官方测试在 6TTAPH 房间留下同样三条：seat_kept=true / room_peer_offline_grace / seat_takeover）
```
⇒ 旧行为（断线即 `peerLeft` + 销毁房间）已消失；断线只进入 `room_peer_offline_grace`，**未出现 `peer_left_sent`**。

### 5.4 宽限期满（临时实例 `-room-grace 3s`，18443；**未改现网参数**）

```
# 临时实例
$ /opt/signaling/signaling -addr :18443 … -log /tmp/t70-temp/signaling.log -room-grace 3s &
$ curl -s http://127.0.0.1:18443/healthz | grep -E "roomGraceSec|graceExpired"
  "graceExpired": 0,   "roomGraceSec": 3,

# 独立探针（expire 模式）
$ node t70_grace_probe.cjs ws://172.21.0.219:18443/ws expire 8000
+   116ms [JOINER] ## terminate() 硬断开（不发 close 帧）
+  3120ms [HOST] <= {"type":"peerLeft","peerId":"peer-002"}     # 3s 宽限期后
+  3156ms HOST 收到 peerLeft 次数 = 1；首条 = {"type":"peerLeft","peerId":"peer-002"}
RESULT: PASS — 宽限期满后恰好 1 次 peerLeft（room=DHRB7V, peerId=peer-002）

# 临时实例 /healthz 与日志
  "graceExpired": 1,  "roomGraceSec": 3,  "rooms": 1, "roomsCreated": 1
… room_peer_offline_grace grace_ms=3000 peer=peer-002 room=DHRB7V
… grace_expired empty=false peer=peer-002 room=DHRB7V
… grace_expired_notify_peer_left grace_ms=3000 peer=peer-002 room=DHRB7V
… peer_left_sent left_peer=peer-002 peer=peer-001 peers=1 room=DHRB7V      ← 恰好 1 条
$ 清理：kill 861106；18443 已释放；现网 signaling 仍 active
```

### 5.5 公网复验（外部 → `47.238.144.66:8443`）

```
$ node t70_grace_probe.cjs ws://47.238.144.66:8443/ws keep 3000
+  3145ms 宽限期内 HOST 收到 peerLeft 次数 = 0
+  3166ms [REJOIN] <= {"type":"joined",…,"peerId":"peer-002"}
RESULT: PASS — 硬断开后宽限期内 0 次 peerLeft；同身份(peerId=peer-002)重连成功且在线方收到 peerJoined（room=6JWMNG）

$ node code/webrtc-demo/scripts/verify_signal_e2e.mjs ws://47.238.144.66:8443/ws
=== 总计: 通过 19 项，失败 0 项 ===
（含「用信令下发的 TURN 凭据 Allocate 成功 :: relay=47.238.144.66:49155」）

$ systemctl is-active coturn signaling
active
active        # coturn 未受影响；signaling MainPID=860227，:8443 与 :3478 监听正常
```

### 5.6 现网 **90 s** 宽限期满的自动回收（无泄漏，端到端实证）

探针会话结束（双方均正常关闭 → 两个席位进入 pending）后，观察现网 `/healthz` 与日志：

```
$ curl -s http://127.0.0.1:8443/healthz | grep -E 'rooms"|roomsCreated|roomsDestroyed|graceExpired|seatTakeovers|activeConns'
  "activeConns": 0,  "graceExpired": 6,  "rooms": 0,
  "roomsCreated": 5, "roomsDestroyed": 5, "seatTakeovers": 3

$ grep -aE "grace_expired|room_destroyed" /var/log/signaling/signaling.log | tail
… grace_expired empty=false peer=peer-002 room=Q62EE4
… grace_expired_notify_peer_left grace_ms=90000 peer=peer-002 room=Q62EE4
… room_destroyed age_s=98 reason=grace-expired-empty room=Q62EE4 room_count=1
… grace_expired_room_destroyed empty=true peer=peer-001 room=Q62EE4
… grace_expired empty=false peer=peer-002 room=6JWMNG
… grace_expired_peer_also_pending empty=false peer=peer-002 room=6JWMNG
… room_destroyed age_s=93 reason=grace-expired-empty room=6JWMNG room_count=0
```
⇒ **现网 90 s 值确实生效**：房间在最后一个席位断开后 ~93–98 s 因 `grace-expired-empty` 被回收，`rooms` 回到 0、`graceExpired=6`，**无房间/席位泄漏**；回收时按设计发 `peerLeft`（`grace_expired_notify_peer_left`）。

## 6. 回滚方法

```bash
# 1) 回滚二进制（旧件 = t67 之前版本）
cp -a /opt/signaling/bak-t70-20260915T234518 /opt/signaling/signaling
# 2) 回滚 unit（去掉 -room-grace 90s）
cp -a /opt/signaling/signaling.service.bak-t70-20260915T234524 /etc/systemd/system/signaling.service
systemctl daemon-reload && systemctl restart signaling
systemctl is-active signaling && curl -s http://127.0.0.1:8443/healthz | head -5
```
（回滚后 `/healthz` 将不再包含 `roomGraceSec/seatTakeovers/graceExpired`，因为那是旧二进制。）

## 7. 未验证项 / 说明

1. **真机端到端**：本任务只验证到「信令服务端宽限期行为」（WS 层）。真机上"瞬断后本端不挂断、收到 `joined`/`peerJoined` 后重协商、必要时 ICE restart"仍取决于客户端（t68/后续任务），无设备无法验证。
2. ~~默认 90 s 的端到端实测~~ → **已补做（§5.6）**：现网 90 s 下房间在最后席位断开后 ~93–98 s 被回收（`grace_expired` → `room_destroyed reason=grace-expired-empty`），并另有 3 s 临时实例的等价验证（§5.4）证明"期满恰好 1 次 peerLeft"。
3. 现网仍使用文档示例凭据 `demo/demopass`（启动即 WARN），属既有残留，与本任务无关。
4. `signaling/dist/signaling-linux-amd64` 为本次构建产物（`/signaling/dist/` 是否被 ignore 以 `.gitignore` 为准；本轮 `git status` 未见其出现）；宿主机部署件以 §3 的 sha256 为准。
5. 本次**未**在本轮做 `go test ./...` 全量回归（t67 已做过；本轮按契约只要求 gofmt/vet/build），活体测试已直接覆盖验收行为。
6. 证据日志 `signaling/logs/t70-deploy-live-evidence.log` 位于 **`.gitignore:51 /signaling/logs/`** 覆盖范围内 ⇒ 文件在磁盘可用但不会被 git 跟踪（与 t67 的 `t67-grace-live-evidence.log` 同等处置）。

## 8. 附：证据文件

- `signaling/logs/t70-deploy-live-evidence.log`：上述全部命令与原始输出（含部署身份、unit diff、systemctl、`/healthz`、官方 TestLive、独立探针 keep/expire、服务端日志、公网复验、终态）。
