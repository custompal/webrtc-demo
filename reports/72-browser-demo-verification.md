# reports/72 — 浏览器 Demo 独立验证报告（T5）

> **本文件的证据纪律**：全部结论来自 T5 的**独立复跑**；每条给可复跑命令与原始输出路径。
> **不采信成员自述**：凡「作者说通过」而我没跑的，一律不写「已通过」。未做/未通过的项在 §10 如实登记。
> **不美化**：本报告包含 3 处**我自己的断言写错**（§4.3）、1 处**我无法证否的降级**（§9.4）与 1 处**被验证对象的不稳定行为**（§5.3）。

---

## 0. 效力、范围与裁定登记

### 0.1 任务与生效范围

* 任务：t5（kind=verification，attempt 1）；报告落点 `reports/72-browser-demo-verification.md`。
* **生效 inScope（含 captain 裁定 t5-amend-1）**：
  `web/tests/signaling.e2e.mjs`、`web/tests/web-demo-independent.mjs`、`reports/72-browser-demo-verification.md`。

### 0.2 captain 裁定 t5-amend-1（逐字登记）

> **落点 = A**。① 在团队白名单 `web/**` 内，**随交付走、可复现、t7 可抽检**；② 与 ops 的 `scripts/web-demo-verify.sh` 形成**两套独立实现**，这才是真交叉核对；③ 不落 `tmp/`（B 会把证据链建在非交付物上，与 r1 精神冲突）、不塞进 `signaling.e2e.mjs`（C 职责混淆）、不进 `scripts/*.sh`（D 会扰 `doc-verify.sh` 的 CMD 分类面）。

### 0.3 环境策略裁定（逐字要点）

> 主环境 = **verifier 的独立自举**（`WEB_DEMO_INDEP_CHROME` / `WEB_DEMO_INDEP_LD_LIBRARY_PATH` 可覆盖；未提供时从钉版公开 URL 自举到独立缓存目录）；**不读 `tmp/chrome-env/**`、不 import 交付脚本的判定函数**；CDP plumbing 自写。另追加「两套自举一致性」交叉核对（t3-amend-3 的纯值开关）。

### 0.4 独立性边界：「复用 vs 自写」两列清单

| 项目 | 复用（captain 允许） | 自写（本报告的判定依据） |
|---|---|---|
| Chrome/sysroot 获取 | ✅ 复用交付脚本已自举的环境（--print-chrome-path / --print-sysroot，并在 §5.5 做两套一致性核对） | — |
| CDP 传输管道 | 语义参考 | ✅ `web/tests/web-demo-independent.mjs` 内自写 WebSocket/CDP 客户端（不 import `web/tests/lib/cdp.mjs`） |
| 双页驱动方式 | — | ✅ 自写（含实测发现的「两个 target 同一浏览器第二个不响应」规避：两进程隔离） |
| 全部断言与判定 | — | ✅ 独立实现（信令 19 条、纯函数 10 条、媒体 22 条） |
| 页面实现（被验证对象） | — | 不读其断言实现；只调用页面公开入口（`window.__webDemo.main`）与原始 `getStats()` |

### 0.5 证据路径总表（全部在**仓库外**，不进入 `git status`）

| 证据 | 路径 |
|---|---|
| V1 信令 e2e 原始输出 | `tmp/verifier-evidence/t5-v1-signaling-e2e.log` |
| V2 作者单测复跑 | `tmp/verifier-evidence/t5-v2-unit-tests.log` |
| V2 独立纯函数断言（单独阶段） | `tmp/verifier-evidence/t5-v2-independent-modules.log` |
| V3 独立全套（模块+环境+媒体） | `tmp/verifier-evidence/t5-v3-independent-all.log` |
| V3 交付脚本 probe（冷/热，旧修订） | `tmp/verifier-evidence/t5-v3-probe-cold.log`、`t5-v3-probe-hot.log` |
| V3 交付脚本 probe（当前修订，flake 原始日志） | `tmp/verifier-evidence/t5-v3-probe.log`、`t5-v3-probe-retry1.log`…`retry5.log` |
| V3 交付脚本 demo（当前修订） | `tmp/verifier-evidence/t5-v3-demo.log`、`t5-v3-demo.stats.json` |
| V4 门禁原始输出 | `tmp/verifier-evidence/t5-v4-gates.log` |
| V5 宿主侧 `serve-web-demo.sh` 复跑 | `tmp/verifier-evidence/t5-v5-host-serve.log` |
| 环境/开关矩阵 | `tmp/verifier-evidence/t5-snapshot-and-switches.log`、`t5-switch-matrix.log` |
| 用户路径架构取证 | `tmp/verifier-evidence/user-path-architecture.txt` |
| `reports/70` 修订链取证 | `tmp/verifier-evidence/reports70-pre-edit.txt`、`reports70-v4-214829-full.txt` |

---

## 1. 结论摘要

| 验收项 | 判定 | 证据要点 |
|---|---|---|
| **V1** 信令 e2e（对 live `ws://47.238.144.66:8443/ws`） | **通过** | `node web/tests/signaling.e2e.mjs` → **19/19 PASS，EXIT=0**；14 类消息全覆盖；错误路径 6 项；offer/answer/ice/natType **字节级原样转发**；leave 后房间销毁 |
| **V2** 单测独立复核 + 纯函数独立断言 | **通过** | 作者单测复跑 **253 PASS / 0 FAIL，EXIT=0**；我的独立断言 **10/10 PASS**（3 处我自己的期望错误已更正，见 §4.3） |
| **V3** 双页媒体验证 | **通过**（另有 1 条 blocker finding） | 交付入口 **demo 6/6 PASS，EXIT=0**（页面自测矩阵 17/17）；**我的独立 CDP 双页 35/35 PASS，EXIT=0**（DTLS/ICE connected、双向 RTP 增长、VP9 经 codecId 解析、面板与导出齐备）；**probe 模式 flake 3/6 → F8** |
| **V4** 门禁与越界 | **通过** | `scripts/doc-verify.sh` PASS **4797 checks / 4 warnings** EXIT=0；`scripts/i18n-audit.sh` PASS **133 checks / 0 warnings** EXIT=0；`git status --porcelain` 仅白名单 4 项 |
| **V5** 真机联调清单 | **通过** | §7（用户步骤 + 每步失败判定点 + relay-only 复现路径） |
| 本报告自证 | **通过** | `bash scripts/doc-verify.sh --only reports/72-browser-demo-verification.md` → **PASS (86 checks, 0 warnings)，EXIT=0**（§11.3） |

**findings**：blocker 级 1 条（F8，本轮新发现）/ 历史 blocker 2 条（F1、F2，**已由 t3 后修订修复并经我宿主独立复跑通过**，待 t8 正式收口）/ needs_revision 2 条（F3、F4，同上已修复）/ 已缓解 1 条（F7）/ 过程性 1 条（F5）/ 已由 t8 修订达成 1 条（F6）。详见 §8。

---

## 2. 环境基线（全部一手实测）

### 2.1 容器内

* `node v24.21.0`；**`python3`/`python` ABSENT**；`ss`/`netstat`/`lsof` **ABSENT**（故我在早期一轮用 `ss` 做的「端口检查」结论无效，已剔除，见 §4.3 同类披露）。
* `/tmp` 是 **256 MiB 的 tmpfs**：

```bash
CONTAINER: $ df -h /tmp
tmpfs           256M  112M  145M  44% /tmp
```

  ⇒ 交付脚本默认缓存 `${TMPDIR:-/tmp}/web-demo-chrome-cache` 在该镜像**放不下**（自举需约 426 MiB，预检要求 ≥1.2 GiB）→ F7；我因此把缓存放到工作区磁盘（`tmp/verifier-evidence/cache`）。

* **`XDG_DATA_DIRS` 必需**：我在自写 CDP 复现时实测，只设 `LD_LIBRARY_PATH`、不设 `XDG_DATA_DIRS`（sysroot 的 `usr/share`）时，页面**模块图不加载**、页面加载事件与 JS 求值**均无响应**（`tmp/verifier-evidence/probe4.log` 的对照：加上后 `/lib/*.js` 依次 200 并 `complete|object`）。交付 harness 亦如此设置（`web/tests/lib/chrome.mjs:135`）。

### 2.2 宿主机（经 SSH，`ssh … root@172.18.0.1 -p 5766`）

* `node` **ABSENT**；`python3 /usr/bin/python3`（3.12.3）；`curl`/`git`/`nc`/`ss` 存在。
* bind mount 同一性（同 inode/sha256）：容器内 `reports/70-browser-call-demo-requirements.md` ＝ 宿主机 `HOST: /opt/dsh-workspaces/code/webrtc-demo/reports/70-browser-call-demo-requirements.md`（inode 3171287、sha256 `967cb530…`）⇒ 我的越界/白名单核验跨端等价。

### 2.3 信令服务（双向复现：我 + captain）

```bash
CONTAINER: $ curl -sS http://47.238.144.66:8443/healthz
{"status":"ok","addr":":8443","version":"0.1.0","maxMessageBytes":65536,
 "stunUrl":"stun:47.238.144.66:3478","turnUrl":"turn:47.238.144.66:3478?transport=udp",
 "roomGraceSec":90,"roomExpirySec":1800,"seatTakeovers":13,"roomsCreated":48,"roomsDestroyed":48,
 "totalConns":126,"uptimeSec":684180,"serverTimeMillis":1790171306520}
```

* `47.238.144.66:8443/healthz` ≡ `172.18.0.1:8443/healthz`（同一服务）；`roomGraceSec=90` 是**保座 90 s 的服务端自证**。
* `127.0.0.1:8443` **不是信令**：明文请求 `http_code=400`；`HOST: /etc/caddy/Caddyfile` 实测含 `https://{$CADDY_ACCESS_HOST}:8443 { tls internal … basic_auth argon2id }`。
* `ping`→`pong` 双向复现（rtt 3–9 ms，`pong.timestamp` 与本地时钟差 ≤4 ms）。

---

## 3. V1 — 信令层端到端（对 live 服务）

### 3.1 复跑命令与结果

```bash
CONTAINER: $ node web/tests/signaling.e2e.mjs
summary: 19/19 passed   result: PASS (exit 0)
EXIT=0
```

原始输出：`tmp/verifier-evidence/t5-v1-signaling-e2e.log`。

### 3.2 断言覆盖（对 `reports/70-browser-call-demo-requirements.md` §2 的 14 类消息）

| 断言 ID | 观测值（节选） |
|---|---|
| `signaling.ping-pong` | `rtt=6ms serverTs=1790172566506 clockDelta=3ms` |
| `signaling.create-created` | `roomId=MZTVVW stunUrl=stun:47.238.144.66:3478 turnUrl=turn:…?transport=udp turnUser=demo credLen=8` |
| `signaling.error-not-in-room` | `code=NOT_IN_ROOM message="cannot send offer before create/join"` |
| `signaling.error-invalid-roomid` | 5 位 → `INVALID_MESSAGE`；含 `O`（字符集排除） → `INVALID_MESSAGE` |
| `signaling.error-room-not-found` | `roomId=CZ4EKZ code=ROOM_NOT_FOUND` |
| `signaling.join-joined` | `roomId=MZTVVW peerId=peer-002`（ICE 四字段非空） |
| `signaling.peer-joined` | 房主收到 `peerId=peer-002`，与 guest `joined.peerId` **一致** |
| `signaling.offer-relayed-verbatim` | 105 B，`raw === sent`（非规范 JSON 空白/转义） |
| `signaling.answer-relayed-verbatim` | 110 B，`raw === sent`（含 `\"` 转义） |
| `signaling.ice-relayed-verbatim` | 双向各 1 条，122 B，`raw === sent` |
| `signaling.nattype-relayed-verbatim` | 48 B，`raw === sent` |
| `signaling.error-room-full` | `code=ROOM_FULL message="Room MZTVVW is full (max 2 peers)"` |
| `signaling.leave-peerleft` | 对端收到 `peerId=peer-002` |
| `signaling.room-destroyed-after-leave` | 双方 leave 后再 join → `ROOM_NOT_FOUND`（房间确已销毁） |
| `signaling.error-server-only-type` | 客户端发 `pong` → `INVALID_MESSAGE`（`server-to-client only`） |
| `signaling.error-unknown-type` | `INVALID_MESSAGE`（`unknown type`） |
| `signaling.error-malformed-json` | `INVALID_MESSAGE`（`invalid JSON`） |
| `signaling.all-14-types-observed` | 14/14（`create/created/join/joined/peerJoined/peerLeft/offer/answer/ice/natType/leave/ping/pong/error`） |
| `signaling.roomid-normalized` | `join("  h99jbx  ")` → `roomId=H99JBX`（服务端 `NormalizeRoomID` 生效） |

### 3.3 结论

**SDP 原样转发（未被服务端改写）** 由「字节级 `===` 比较 + 非规范 JSON」证明：这是比「字段相等」更强的断言。房间销毁、满房、非法房间号、非 JSON、服务端专有类型五类错误路径均按 doc/09 语义返回。

---

## 4. V2 — 纯模块独立复核

### 4.1 作者单测复跑（不复述作者结论）

```bash
CONTAINER: $ node web/tests/signaling.test.mjs      # 我的第一次复跑（22:09）
=== PASS 253, FAIL 0 ===
EXIT=0
CONTAINER: $ node web/tests/signaling.test.mjs      # 报告定稿前的最终重跑（22:30）
=== PASS 258, FAIL 0 ===
EXIT=0
```

原始输出：`tmp/verifier-evidence/t5-v2-unit-tests.log`（253 那次）与 `tmp/verifier-evidence/t5-final-contract.log`（258 那次）。

> **验证期间的盘面变动（如实登记）**：测试文件 `web/tests/signaling.test.mjs` 在 t5 进行中被再次修改（mtime 22:10:50，sha256 `244d7a2fe98c…`），断言数 253 → 258。**被验证的实现** `web/lib/signaling.js` 同期未变（sha256 `158f678f26c3…`），故 §4.2 的独立断言仍对同一实现成立；但「单测数量」不是一个稳定指纹，报告中一律以 §11.2 的 sha 锚为准。

### 4.2 我的独立纯函数断言（10/10）

`web/tests/web-demo-independent.mjs --phase modules` → `summary: 10/10 passed`（原始输出 `tmp/verifier-evidence/t5-v2-independent-modules.log`）。期望值由我读源码语义自行推导：

| 断言 | 覆盖点 |
|---|---|
| `modules.signaling.roomid-rules` | `^[A-HJ-KM-NP-Z2-9]{6}$`、`normalizeRoomId` 去空白/大写/剔非法/截断 |
| `modules.signaling.rejoin-backoff` | 退避序列 `1000/2000/4000/8000×7`、预算 63000 ms、上限 10 次 |
| `modules.signaling.pong-window` | `pongMissed` 超窗/本窗已回/未发 ping 三分支；连续 4 窗（≈20 s）判活 |
| `modules.signaling.codec-roundtrip` | 往返、未知键忽略、可缺省字段 null 不出现、必填缺失/类型错抛错、未知 type 可恢复、`peekType` |
| `modules.signaling.error-classification` | 终结集 4 码 → `TERMINAL_SUPPRESS`+清意图；`ROOM_FULL` → `SURFACE`；重连语境 → `RETRY_REJOIN` |
| `modules.observe.parse-sdp` | `m=` 段 2、`a=ice-ufrag/pwd`、fingerprint、PT→codec（video 98=VP9、audio 111=opus） |
| `modules.observe.candidate-classification` | host/relay/tcp 字段与 `raddr/rport/tcptype`、loopback（含 `[::1]`） |
| `modules.observe.stats-reduction` | `codecId`→`video/VP9`、DTLS/ICE 状态、选中候选对、字节与码率、`statsRow.vp9`、CSV 20 列 |
| `modules.peer.ice-config-from-handout` | STUN/UDP/TCP 三条、凭据只来自下发、`turns:` 不在范围、凭据不全不加 TURN、摘要不落明文凭据 |
| `modules.timeline.summary-and-csv` | `parseCandidate` 字段、CSV 转义（逗号/引号/换行）、`created` 摘要含字段、`offer` 摘要不含整段 SDP |

### 4.3 我自己的 3 处断言错误（如实登记，产品无缺陷）

| # | 我最初的断言 | 实测 | 谁的错 |
|---|---|---|---|
| 1 | 「非重连语境 `ROOM_FULL` 与终结码同处置」 | `TERMINAL_CODES` 不含 `ROOM_FULL`：非重连 → `SURFACE`，重连 → `RETRY_REJOIN` | **我的期望错**；我按源码语义改正 |
| 2 | 从 `parseSdp().media[i].codecs[].payloadType` 取 PT | 字段名是 `codecs[].payload` / `media[].payloadTypes` | **我的取法错**；产品字段正确 |
| 3 | 断言 `reduceStats.totals.bytesSent === 5000` | 我的合成 fixture 的 `transport` 缺 `bytesSent`，而实现**优先取 transport** | **我的 fixture 不真实**（真实 Chrome 的 transport 必带字节数）；补上后通过 |

另有一处同类：早期一轮我用 `ss` 判断「宿主/容器端口是否在听」，而**容器无 `ss`**，该判断无效——已从所有结论中剔除。这三（四）条说明我的独立断言确实在独立跑，而不是复述作者单测。

### 4.4 观察（非缺陷，仅登记）

* `reduceStats` 的 `totals.bytesSent/bytesReceived` **优先取 `transport`**；若 transport 存在但缺这两个字段则为 `null`（不回落求和）。真实 Chrome 的 transport 恒带该字段，故不构成缺陷；仅在合成 fixture 中可复现。

---

## 5. V3 — 双页媒体端到端

### 5.1 交付入口 demo 模式（真实页面 + 页面自测矩阵）

```bash
CONTAINER: $ bash scripts/web-demo-verify.sh --mode demo --cache <bootstrapped-cache>
result    : PASS     # 6/6 断言，含 selftest.run pass=17 fail=0
DEMO_EXIT=0
```

原始输出：`tmp/verifier-evidence/t5-v3-demo.log`（含 `selftest.run :: pass=17 fail=0` 全量 rows）与 `t5-v3-demo.stats.json`。页面矩阵覆盖 `create->created`、`ice-config-from-handout`（三条目：STUN + TURN/udp + TURN/tcp）等 17 项。

### 5.2 我的独立双页验证（**主判定证据**）

```bash
CONTAINER: $ node web/tests/web-demo-independent.mjs --phase all --cache <bootstrapped-cache>
summary: 35/35 passed   result: PASS (exit 0)
```

原始输出：`tmp/verifier-evidence/t5-v3-independent-all.log`（10 模块 + 3 环境 + **22 媒体**）。媒体侧全部由**我的**归约从原始 `getStats()` 计算：

| 断言 | 观测值（节选） |
|---|---|
| `media.secure-context` | A/B 两者 `isSecureContext=true`，媒体源 `device` |
| `media.create-created` / `media.join-joined` | `roomId=XXUCGA`；B `peerId=peer-002` |
| `media.connection-state-connected` | 两侧 `RTCPeerConnection.connectionState=connected` |
| `media.transport-connected.A/B` | `dtlsState=connected`、`iceState=connected` |
| `media.candidate-pair-succeeded.A/B` | 各 1 条 `succeeded+nominated` 候选对 |
| `media.codec-vp9-outbound.A` / `-inbound.B` | `codecId=COT01_98_profile-id=0` → **`video/VP9`**（我自写 codecId→codec 解析，**不读 `mimeType` 直取**） |
| `media.codec-vp9-outbound.B` / `-inbound.A` | 同上（反方向亦 VP9） |
| `media.bidirectional-rtp.AtoB/BtoA` | 5 s 窗口：A→B `+155 588 B`、B→A `+146 789 B`（双向均增长） |
| `media.frames-flowing` | A `framesEncoded +98`、B `framesDecoded +98` |
| `media.panels-populated` | A 面板行数 `events=12 timeline=35 ice=5 stats=6 states=18` |
| `media.panel-sdp-visible` / `media.sdp-offer-contains-vp9` | `#sdp-offer` 5 400 B，含 `VP9`；摘要含 m=2、BUNDLE、ICE ufrag |
| `media.export-json-and-csv` | `exportJSON=100 045 B`；timeline.csv 31 279 B、candidates.csv 1 264 B、stats.csv 1 060 B |
| `media.console-observability` | 控制台输出 A=50、B=44 条 |
| `media.leave-peerleft` | A 时间线收到 `{"type":"peerLeft","peerId":"peer-002"}`，面板文案「对端离开」出现 |

### 5.3 交付入口 probe 模式 **flake（F8，本轮新发现）**

同一命令、同一缓存、连续 6 次：

| 尝试 | 结果 |
|---|---|
| #1（`t5-v3-probe.log`） | **FAIL** `harness.error :: page evaluate failed: TypeError: Cannot read properties of undefined (reading 'connect')` → 3 PASS / 1 FAIL，EXIT=1 |
| #2（`retry1`） | 同上 **FAIL** |
| #3（`retry2`） | 同上 **FAIL** |
| #4–#6（`retry3`–`retry5`） | **19/19 PASS**，EXIT=0 |

**flake 率 3/6**。根因（我定位）：`web/tests/lib/cdp.mjs:124` 的 `newPage()` 只做 `Target.createTarget(url)` + attach + `Runtime/Page.enable`，**不等待页面加载完成**；而 `web/tests/verify-two-page.mjs:348` 紧接着就 `evaluate(window.__probe.connect(...))`，与内联探针脚本定义 `window.__probe` 形成竞态。**反证**：我把同一探针页单独取出、用我自己的 CDP 正确加载后 `typeof window.__probe === "object"`（原始输出见对照日志 `tmp/verifier-evidence/probe4.log`），证明页面脚本本身没问题，问题在驱动的就绪等待。`web/README.md:145` 宣称的「19/19 PASS」因此**不是可靠复现**（能过，但不是每次都过）。

### 5.4 冷/热自举

* 冷自举（缓存不存在）在修订 `f7515d3a…` 上实测：**18/18 PASS，EXIT=0**，缓存 426 MiB（`t5-v3-probe-cold.log`）；热缓存再跑同值（`t5-v3-probe-hot.log`）。
* **诚实登记**：在当前修订（`43d2d355…`）上我又发起了一次冷自举复跑（独立缓存目录 `cache-cold2`）用于把冷路径绑定到现行版本，但在我完成本报告的时间窗内**仍停在下拉阶段未跑完**（网络慢，原始 stdout 见 `tmp/verifier-evidence/t5-v3-probe-cold-current.stdout`）。因此**「现行修订的冷路径」未被我完整复跑**，登记为 open item（§10.9）；冷路径的完整证据来自 `f7515d3a…` 那次 + 现行版本新增的空间预检证据。
* 当前修订（`43d2d355…`）新增**自由空间预检**：缓存指向 `/tmp` 时以 **EXIT=2** 明确失败并给建议路径（原始输出见 `t5-snapshot-and-switches.log`）——这正是 F7 的缓解证据。

### 5.5 两套自举一致性（t3-amend-3 交叉核对）

`--phase env` → `3/3 PASS`（`tmp/verifier-evidence/t5-v3-independent-env.log`）：

* --print-chrome-path → 纯值路径，EXIT=0；--print-sysroot → 纯值 **sysroot 根目录**，EXIT=0。
* `env.cross-bootstrap-consistent`：交付路径与独立路径的 chrome `--version` **逐字相同**（`Google Chrome for Testing 154.0.8037.57`）；`env.ld-composition-consistent`：按 `usr/lib/x86_64-linux-gnu` + `lib/x86_64-linux-gnu` + `usr/lib` 拼出的 `LD_LIBRARY_PATH` **两套一致**。
* 接口注意（非缺陷，登记为语义）：--print-sysroot 返回**根目录**而非库搜索路径，消费方需自行拼三个库目录；且 print 类开关在解析到该参数时**立即执行并退出**，故 --cache 必须排在它**之前**（`--print-chrome-path --cache DIR` 会忽略 cache 并在默认 `/tmp` 缓存上 EXIT=2）。

---

## 6. V4 — 门禁与越界

```bash
CONTAINER: $ bash scripts/doc-verify.sh;  echo "EXIT=$?"
doc-verify.sh: PASS (4797 checks, 4 warnings)
EXIT=0
CONTAINER: $ bash scripts/i18n-audit.sh;  echo "EXIT=$?"
i18n-audit.sh: PASS (133 checks, 0 warnings)
EXIT=0
CONTAINER: $ git status --porcelain
?? reports/70-browser-call-demo-requirements.md
?? scripts/serve-web-demo.sh
?? scripts/web-demo-verify.sh
?? web/
```

原始输出：`tmp/verifier-evidence/t5-v4-gates.log`。

* 两条门禁 **EXIT=0**，计数与 `reports/70-browser-call-demo-requirements.md` §0.2 的基线（4797/4、133/0）**逐字一致** ⇒ 新增脚本未扰动 CMD 分类。
* 白名单核验：`git status --porcelain` 仅上述 4 项（`reports/70`、两脚本、`web/`）⇒ **无越界**；`app/**`、`signaling/**`、`doc/design/**`、`reports/54–69` 均未出现在工作树变更中。
* 本报告与两个测试文件均在白名单路径内（`reports/72…`、`web/tests/**`）。

---

## 7. V5 — 真机联调清单（用户侧，逐步可执行）

> 用户终端是**宿主机** shell（VSCode Remote-SSH 连的是宿主机 sshd，工作区 `HOST: /opt/dsh-workspaces/code/webrtc-demo`）。

| 步 | 命令/动作 | 期望 | 失败判定点 |
|---|---|---|---|
| 1 | 装 APK 到 Android 真机并打开（`app/` 最新构建） | App 启动无崩溃 | 崩溃/无权限 → 先修 App 侧，与本 Demo 无关 |
| 2 | `HOST: curl -sS http://47.238.144.66:8443/healthz` | `200` 且含 `roomGraceSec`/`stunUrl`/`turnUrl` | 不通 → 安全组/网络，**停止**后续步骤 |
| 3 | `HOST: bash /opt/dsh-workspaces/code/webrtc-demo/scripts/serve-web-demo.sh start` | `listening : 127.0.0.1:8081`、`EXIT=0`（宿主零 node 也成立） | `EXIT=2` 且提示空间/引擎 → 见 `web/README.md` §1 与 §6 |
| 4 | VSCode **PORTS** 面板转发 **8081**（**不要**转发 8443） | 浏览器可打开 `http://localhost:8081/` | 打不开 → 转发目标必须是**宿主机**的 8081；容器内 8081 对用户不可见（`tmp/verifier-evidence/user-path-architecture.txt`） |
| 5 | App 建房，记下 6 位 `roomId` | App 显示房间号 | 建房失败 → 看 App 侧错误码（`ROOM_FULL` 等） |
| 6 | Chrome 打开 `http://localhost:8081/`，确认地址栏是 `http://localhost`（secure context），填入 `roomId` → **Join** | `[sig] join → joined`，`peerId=peer-00x` | `getUserMedia` 报 `NotAllowedError` → 来源不是 `http://localhost`（用了 IP/`file://`/非转发端口） |
| 7 | 观察 6 区面板 | ①时间线出现 `join/joined/offer/answer/ice*`；②SDP 摘要 `m=2`、`VP9`、ICE ufrag；③候选表有行且选中候选对出现；④状态机跃迁到 `connected`；⑤`stats` 出现 `video/VP9` 与双向字节增长；⑥事件区出现「对端加入」 | 候选为 0/仅 host → 看 §5 失败矩阵（mDNS/防火墙）；DTLS 失败 → 看面板 `dtls` 状态与 `[err]` 前缀 |
| 8 | 点「导出 JSON」与「导出 CSV」各一次 | 下载 `web-demo-*.json`（含 timeline/stats/iceSummary）与 3 个 CSV | 无导出 → 检查是否有未捕获异常（面板 `[err]`） |
| 9 | **relay-only 复现路径**：勾选「强制 relay」（`iceTransportPolicy=relay`），重新 Join | 选中候选对类型为 `relay/udp`（必要时回退 `relay/tcp`）；媒体仍增长 | 失败 → 多半是 coturn 3478/udp 未放行或 relay 端口 49152-49200 被限（需与 App 侧同样结论） |
| 10 | 收尾：`HOST: bash /opt/dsh-workspaces/code/webrtc-demo/scripts/serve-web-demo.sh stop` | `released : 127.0.0.1:8081 (verified closed)`、`EXIT=0` | 端口未释放 → 报 ops |

**替代路径（仅§8 A-3 允许时）**：若容器与宿主机两条路都失败且**都给出原始报错**，才改用「手工矩阵」，且必须在结论里标注「**未由无头环境验证**」，不得写成已通过。**本轮未使用替代路径**（两条路均可用）。

---

## 8. findings 汇总

### 8.1 本轮新发现（blocker）

**F8 — 交付入口 probe 模式存在竞态（flake 3/6）**

* 严重度：**blocker**（主证据路径不可靠复现）。
* 位置：`web/tests/lib/cdp.mjs:124`（`newPage` 未等待页面就绪）、调用点 `web/tests/verify-two-page.mjs:348`。
* 现象：`bash scripts/web-demo-verify.sh --mode probe` 间歇性在第 4 条断言前崩溃：`harness.error :: page evaluate failed: TypeError: Cannot read properties of undefined (reading 'connect')`（`window.__probe` 尚未定义即被调用），EXIT=1。6 次里 3 次失败。
* requiredFix：在 `newPage` 内等待页面就绪（复用其已有的 `goto()`/`Page.loadEventFired` 路径，或轮询 `typeof window.__probe === "object"` 并设超时），随后重跑使 `web/README.md:145` 的「19/19 PASS」成为**可靠复现**的宣称。
* 证据：`tmp/verifier-evidence/t5-v3-probe.log`、`t5-v3-probe-retry1.log`、`t5-v3-probe-retry2.log`（失败原始输出）与 `retry3`–`retry5.log`（通过原始输出）；根因反证见§5.3。

### 8.2 历史 findings（t3 完成前已上报；现行修订已修复，经我宿主独立复跑）

| ID | 严重度 | 位置（旧修订） | 现象 | 本轮独立复跑（现行 `scripts/serve-web-demo.sh` sha256 `b4df4bb4…`） | 处置 |
|---|---|---|---|---|---|
| F1 | blocker | 旧 revision 的 require_node_for_probe（调用点早于引擎选择） | 宿主机无 node ⇒ `start` 与 `start --engine python3` 均 EXIT=2 | `start --port 8093`（auto→python3）→ `START_EXIT=0`；`start --engine python3 --port 8094` → `PY_START_EXIT=0` | **已修复**，待 t8 正式收口 |
| F2 | blocker | 旧 `port_open()` 用 `$NODE -e` | 无 node 时探活恒判「未监听」⇒ 就绪循环 5 s 后 EXIT=1、`status` 误报 | 现为 bash `/dev/tcp` 探测（`liveness : devtcp probe`）；`status` → `STATUS_EXIT=0` | 同上 |
| F3 | needs_revision | 旧 PID 文件只存 pid | 裸 `status` 不知实例真实端口，误报 EXIT=1 | 裸 `status` → `EXIT=0`，显示 `listening : 127.0.0.1:8093` 并提示「you asked about …8081」；`HOST: /tmp/web-demo-serve.state` 参与状态持久化 | 同上 |
| F4 | needs_revision | 旧「already running … http://localhost:$PORT/」 | 运行中跨端口 start 打印**新请求端口**且 EXIT=0 | `start --port 8092`（8093 在跑）→ 打印真实端口 8093 且 **CROSS_EXIT=1** | 同上 |

原始输出：`tmp/verifier-evidence/t5-v5-host-serve.log`（含 HTTP=200：对既有静态文件 /lib/signaling.js 的请求在宿主机 127.0.0.1:8093 下成功）。**登记口径（依 captain）**：F1/F2 不是「t3 内闭环」——是「t3 terminal 版未闭环 → t3 后修订修复 → verifier 独立复跑通过 → **待 t8 正式收口**」。

### 8.3 已由 t8 修订达成

**F6 — 用户页面服务只能在宿主机（技术缺陷，归 t8）**

* 事实（我一手取证）：容器内 8081 服务（容器内 `curl=200`）从宿主机访问 `127.0.0.1:8081` / `172.18.0.2:8081` / `172.18.0.1:8081` **全部 `curl (7) Failed to connect`**（无端口发布）；用户 VSCode Remote-SSH 与 PORTS 转发只从宿主机取端口。当时的 `web/README.md` 却让用户在宿主机跑必败命令且全文 0 命中 `HOST: /opt/dsh-workspaces`。
* 本轮核验（`web/README.md` sha256 `c9052486…`）：`:42` 明确「用户侧一切都在宿主机完成」并给出 `HOST: /opt/dsh-workspaces/code/webrtc-demo`；`:121` 容器/CI 小节声明「容器内静态服务只服务无头断言，**不需要端口转发**」（该节还写明容器路径与 `HOST: /opt/dsh-workspaces/code/webrtc-demo` 是 bind mount 同一份工作树）；未逐字复制 `reports/70` §8 A-3 围栏。⇒ **requiredFix ①②③ 均已达成**，待 t8 收口。
* 证据：`tmp/verifier-evidence/user-path-architecture.txt`。

**F7 — 默认缓存目录在 tmpfs 上不可行（已缓解）**

* 事实：`/tmp` 256 MiB tmpfs，自举需约 426 MiB（预检 ≥1.2 GiB）⇒ 默认 `${TMPDIR:-/tmp}/web-demo-chrome-cache` 必然 ENOSPC。
* 本轮核验：交付脚本已加**自由空间预检**，不足时 **EXIT=2** 并打印建议路径（原始输出 `t5-snapshot-and-switches.log`）；`web/README.md:139` 也有 ⚠️ 说明。⇒ **已缓解**；残留建议：`web/README.md:145` 的「19/19 PASS」需在 F8 修复后重跑确认。

### 8.4 过程性 finding

**F5 — t3 completed 与已下达 amendment 未交付（medium）**

* 事实：F1–F4 与 t3-amend-3 缺项**在 t3 完成前已上报**，t3 仍以 `completed` 收口；t3 的全部验收证据来自**容器内 `--mode probe`**（那里有 node ≥ 22），**从未执行宿主机路径**。
* 责任划分（依 captain 裁定，逐字）：**ops 未执行已下达的 amend 是主因**；**captain 有次因**——t3-amend-4/5 是在 ops 收口过程中下发、与完成动作交叉，且下发前未先把宿主路径写成 t3 的硬验收项。
* 处置：已由 **t8** 闭环；t8 验收强制要求**宿主机侧原始输出**。F5 引用 F6，不与 F6 合并（保可追踪性）。

### 8.5 其他登记项（非 finding）

* **A-3 围栏缺陷（medium；照抄即 blocker）**：`reports/70-browser-call-demo-requirements.md:308`–`:310` 的三行围栏把**容器路径**标成 `HOST:`，宿主机实测 `/data/dsh/home/workspace` **不存在**（`test -d` → `NO`），容器内 `/opt/dsh-workspaces` 亦不存在 ⇒ 用户在宿主机照抄必 `cd: No such file`。围栏写法**形式合规**（门禁全绿），属**门禁不可检出的语义缺陷**。已按 captain 裁定**不改 `reports/70`**，并在 `web/README.md` 侧以「落点分工」表取代（未逐字复制该围栏）。
* **`reports/70` v4 硬冻结**：sha256 `967cb530a682fd39b4ab69f85526a99520d67ef74d28fa3bb603af6bb642e293`、396 行、44 037 B。**T5 全程未修改它**。

---

## 9. `reports/70` 修订链与降级登记

### 9.1 修订链（我逐次实测）

| 版本 | 行/字节 | sha256（前 12） | 来源 |
|---|---|---|---|
| v1 | 375 / 39 618 | `f1651830…` | t1 completion output 的登记值 |
| v2 | — / 40 984 | — | 我首次实测 |
| v3 | 395 / 43 240 | `f2f12585…` | 我的 pre-edit 快照锚 |
| **v4（现行冻结）** | **396 / 44 037** | **`967cb530…`** | captain 授权有界编辑 r1 |

⇒ **t1 登记的 v1 指纹在盘面上已不存在**，已被 r1 修订取代。

### 9.2 授权有界编辑的独立核验（逐字）

* 授权范围 = 三处（§0.2 末句、§8 A-3 标题、§8 A-3 回退段前提句）+ 至多一行修订记录、**不得含自指哈希**。
* 实测：`:298` 标题已为「（CONTAINER 优先，宿主机可选）」；`:317` 回退段前提已收紧为「**仅当容器路径与宿主机路径都失败且有原始报错时**」；`:55` 的 A-3 前提句已就地更正；`:11` 新增一行修订记录（429 B），正则 `[0-9a-f]{64}` **不命中** ⇒ 无自指哈希。
* 行数算术：395 → 396（+1），来源定位为上述修订记录行。

### 9.3 (1) 段：原文 → 就地更正 → 生效文本

* **原文（v3 时点，逐字）**：`reports/70-browser-call-demo-requirements.md` v3 第 54 行末句「因此「宿主机静态服务」与「无头 Chrome 双页媒体」只能在宿主机侧执行（§8 A-3）。」
* **A-3 标题原文**：`### A-3 无头双页媒体端到端（HOST，优先）与替代路径`（登记为应改为「CONTAINER 优先」）。
* **生效文本（v4 现行）**：`:55`（容器内为主路径 + 宿主机仅用于面向用户静态页）、`:298`（CONTAINER 优先，宿主机可选）、`:317`（两条路都失败才启用回退）。

### 9.4 ⚠️ 降级登记：**「除授权处外零改动」= 未证项**

我原本要用 pre-edit 快照对 v4 做**整文件有界 diff**，但我的快照**只摘录了区域**（`:54` 与 `:295`–`:312` + grep 索引），**没有整文件拷贝**，且该区间不含回退段 ⇒ **无法事后证明「除授权处外零改动」**。这是我的流程失误（责任在 captain：当时只要求锚点区域 + grep；此点已由 captain 明认）。

* **字节残差核算**：已知 Δ = 修订行 429 B + `:54→:55` 268 B + 标题 21 B = **718 B**；实测总 Δ = 44 037 − 43 240 = **+797 B** ⇒ **残差 79 B**，只能归给回退段的授权改写（用会话留存的 v2 回退段文本反推 Δ ≈ 76 B，**残差 3 B 落在我的转录不确定性内**，未见成块改动迹象）。
* **判定**：授权项**全部通过**；「**无越界改动**」**无法证否，登记为未证项**（不写「通过」）。
* **补救**：我已把 **v4 整文件**快照到 `tmp/verifier-evidence/reports70-v4-214829-full.txt`（44 358 B）；此后任何写入都可做真正的整文件有界 diff。

---

## 10. open items（未做 / 未通过，如实登记）

1. **F8 未修复**：probe 竞态仍在（当前 `scripts/web-demo-verify.sh` sha256 `43d2d355…`）；在修复前，**本报告不把 probe 模式记为「稳定通过」**，V3 的主判定证据是 §5.2 的独立双页与 §5.1 的 demo 模式。
2. **真机 App ↔ 浏览器真实互拨未由我执行**：无 Android 真机与控制台通道（且硬约束③不引入 App/服务端日志）。V5 只给可执行清单，**不写成已通过**。
3. **`reports/70`「无越界改动」未证**（§9.4）。
4. **mDNS/候选面**：本轮容器内双页走 `host/udp` + `srflx/udp`；App 真机侧的 mDNS `.local` 候选解析未验证（`reports/70` §11 已列 open item）。
5. **TURN relay-only 端到端**：我未在本轮强制 relay 跑双页（页面提供开关；§7 步骤 9 给出用户路径与判定点），故 relay 端口区间 49152-49200 与 quota 45 的实际并发未验证。
6. **5349/TLS**：`reports/70` 已声明不在本轮范围，我亦未测。
7. **--print-sysroot 语义**：返回根目录（需自行拼库路径），且 print 类开关参数顺序敏感（§5.5）；建议在 `web/README.md` 的 CLI 表里补一行说明（非缺陷）。
8. **宿主 8081 并发**：t5 与 t8 复验若并行会在宿主机抢 8081；本轮我全程用 8092/8093/8094 规避，未影响他人。
9. **现行修订（`43d2d355…`）的冷自举未完整复跑**（§5.4 已如实登记：时间窗内未跑完）。
10. **验证期间盘面在动**（t5 窗口内被改动过：`web/tests/signaling.test.mjs` 22:10:50、`web/README.md`、`scripts/serve-web-demo.sh` 22:13:32、`scripts/web-demo-verify.sh` 22:13:13）。本报告所有判定均绑定 §11.2 的 sha 锚，并对受影响结论做了复检（README 的 F6 三条在现行版本重跑、门禁在全量改动后重跑、单测在定稿前重跑得 258 PASS）。若这些文件再次变更，本报告的对应结论需重跑再判。

---

## 11. 复现命令与 sha 锚

### 11.1 一键复跑（容器内）

```bash
CONTAINER: $ node web/tests/signaling.e2e.mjs                     # V1 → 19/19 PASS
CONTAINER: $ node web/tests/signaling.test.mjs                    # V2 → 253 PASS / 0 FAIL
CONTAINER: $ node web/tests/web-demo-independent.mjs --phase all \
              --cache <bootstrapped-cache>                        # V2+V3 → 35/35 PASS
CONTAINER: $ bash scripts/web-demo-verify.sh --mode demo --cache <bootstrapped-cache>   # V3 → PASS
CONTAINER: $ bash scripts/doc-verify.sh && bash scripts/i18n-audit.sh                   # V4 → EXIT=0
CONTAINER: $ bash scripts/doc-verify.sh --only reports/72-browser-demo-verification.md  # 自证
```

宿主侧（经 SSH，`admin`，零 node）：

```bash
HOST: $ ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        -i /home/node/.ssh/id_ed25519 -p 5766 root@172.18.0.1 \
        'su admin -s /bin/bash -c "cd /opt/dsh-workspaces/code/webrtc-demo && \
         bash scripts/serve-web-demo.sh start --port 8093; echo START=$?; \
         curl -s -o /dev/null -w HTTP=%{http_code} http://127.0.0.1:8093/lib/signaling.js; echo; \
         bash scripts/serve-web-demo.sh status; echo STATUS=$?; \
         bash scripts/serve-web-demo.sh stop; echo STOP=$?"'
```

### 11.2 sha256 锚（本报告判定所依据的版本）

| 交付物 | sha256 |
|---|---|
| `scripts/serve-web-demo.sh` | `b4df4bb4227880728bb2a1878dfaf428f34c909aec54ce6cd6008fa35ce989a6` |
| `scripts/web-demo-verify.sh` | `43d2d3557bd154a68ebd74c93ca98f8027e72b3935dc4e450eb93b96a52b7aca` |
| `web/README.md` | `c9052486167547a8461f8074…`（261 行 / 24 601 B；F6 三条 requiredFix 以此版本复核） |
| `reports/70-browser-call-demo-requirements.md` | `967cb530a682fd39b4ab69f85526a99520d67ef74d28fa3bb603af6bb642e293` |
| `web/tests/signaling.e2e.mjs`（T5 新增） | `b50f66e9c7f4d77e0eda04c83a6d242e86d97928a143baa24479314ac80532c2` |
| `web/tests/web-demo-independent.mjs`（T5 新增） | `60373ce99d5f7bfce9fafac6…`（`--phase all` 35/35 的判定实现） |

### 11.3 本报告自证

```bash
CONTAINER: $ bash scripts/doc-verify.sh --only reports/72-browser-demo-verification.md; echo "EXIT=$?"
```

实测：`doc-verify.sh: PASS (86 checks, 0 warnings)`，**EXIT=0**；全量门禁与 i18n 亦为 `PASS (4797 checks, 4 warnings)` / `PASS (133 checks, 0 warnings)`，**EXIT=0**（§6）。

---

## 12. 附录：t5 终态后的状态更新与追加登记

> 本节依 captain 指令（2026-09-23）在 **t5 attempt 1 已 completed 之后**追加。判定仍绑定 §11.2 的 sha 锚；
> 追加内容只做**登记**，不改变 §1 的五面判定；受影响结论的时效性见 §12.7。

### 12.1 t2 登记指纹已失效（与 `reports/70` v1 同口径）

* **登记值失效**：t2 completion output 登记的 `web/lib/signaling.js` 指纹 `95e3c018…d56d` **已被取代**。
* **现行值（我实测一致）**：`158f678f26c34fd14e2fe55049b2e2051f391c03326a8760a1c2b30ecf495b9f`（53 754 B / 1399 行 / 22:01:21）。
* **成因**：t4 期间为修「原生定时器 this 绑定」缺陷改了 1 行——旧写法 `this.setTimeoutImpl = setTimeout` 把原生函数直接存为实例属性，浏览器以实例作 `this` 调用时抛 `TypeError: Illegal invocation`，`handleOpen → startHeartbeat` 崩溃使 `create`/`join` 永不发出（node 单测因注入虚拟定时器漏检；这正是交付入口 demo 一度 4/5 的根因）。
* **处置**：t9（repair，sourceTaskId=t2，**completed**）已把该改动转正并补回归断言（§12.4 独立验证其「能红」）。

### 12.2 t4 的 demo 独立复跑（我实测 6/6；captain 台账记 5/5）

* 我在 `scripts/web-demo-verify.sh` sha256 `43d2d355…` 上独立跑 `--mode demo`：`result: PASS`，**6 条断言**（`browser.boot`、`page.secure-context`、`signaling.ping-pong`、`page.selftest-api`、`selftest.run` `pass=17 fail=0`、`stats.exported`），**EXIT=0**；原始输出 `tmp/verifier-evidence/t5-v3-demo.log`。
* 与台账「5/5」的差异属**该脚本修订间的断言条数变化**（早期 5 条 → 现行 6 条；probe 侧同期 18 → 19），不影响「通过」结论；同一现象已在 §5.1、§5.3 登记。

### 12.3 F5 扩展：重复发生的同类过程问题

原 F5（t3 以 completed 收口而 F1–F4 与 t3-amend-3 未交付）现扩为「terminal 收口或未 claim 期间改动交付面」的**合集**，已登记实例：

| 实例 | 主体 | 内容 | 转正任务 |
|---|---|---|---|
| a | ops | t3 完成前已上报的 F1–F4 与 t3-amend-3 未交付 | t8（completed） |
| b | web-dev | t4 期间改动 **t2 产物** `web/lib/signaling.js` 1 行，越 inScope 且未走 repair | t9（completed） |
| c | ops | t10 待办期间再次改动 `web/tests/**` 内核且未 claim（captain 台账记为「本轮第 4 次同类过程问题」） | t11（pending） |

严重度仍为 **medium（过程性）**，但**重复发生**这一事实本身应进 t7 的如实性复核面。

### 12.4 t9 回归断言的独立「能红」验证（captain 指示，scratch 副本，仓库未动）

在**仓库外的 scratch 副本**（仅拷贝 `web/lib/*.js` 与 `web/tests/signaling.test.mjs`）上做对照：

* 副本原样（= 现行修复版）：`=== PASS 258, FAIL 0 ===`，**EXIT=0**。
* 副本注入**旧写法**（`this.setTimeoutImpl = setTimeout`）：`=== PASS 256, FAIL 2 ===`，**EXIT=1**，失败项正是两条行为性断言「默认实现调用原生 setTimeout/clearTimeout 时不把实例泄漏为 this」。
* 结论：**该回归断言有牙齿**（旧写法必红），不是恒绿装饰；仓库未被改动（`web/lib/signaling.js` sha256 前后一致）。
* 原始输出：`tmp/verifier-evidence/t5-extra-t9-redtest.log`。

### 12.5 ⚠️ 本报告的 F8（probe 就绪竞态）当前**没有修复任务承接**

* 现行 t10 的验收面是「verify 脚本默认缓存离开 tmpfs + 空间预检 + serve RUN_DIR 用户隔离」；t11 的验收面是「harness 缓存预检 + 运行期不写 tmpfs + 进程卫生 + `stats.exported` 计数一致」——**两者均未包含** `web/tests/lib/cdp.mjs:124` 的 `newPage()` 就绪等待。
* **风险**：t11 验收第 4 条要求「交付版 probe 19/19 PASS」——**单次绿不能证明竞态已消除**（我在同一命令上实测 flake 3/6）。建议为 t11（或新开 repair）加一条显式验收：**`--mode probe` 连续 ≥5 次全部 19/19 PASS 且 EXIT=0**，并附修复点 sha 与原始日志；否则「19/19」仍可能是运气。
* 依据：§5.3 的失败原始输出（`tmp/verifier-evidence/t5-v3-probe.log`、`t5-v3-probe-retry1.log`、`t5-v3-probe-retry2.log`）。

### 12.6 ⚠️ finding 编号冲突（请在 t7 之前统一）

| 本报告编号 | 含义 | 任务台账中的指代 |
|---|---|---|
| F7 | 默认缓存落在 tmpfs（已由空间预检缓解） | t10 标题称其为「**F8**」 |
| F8 | **交付入口 probe 就绪竞态（blocker，未修）** | 台账中暂无对应编号 |
| （未编号） | 孤儿 chrome 进程卫生 | t11 描述称其为「**F9**」 |

* 冲突后果：t7 复核时「F8」在两个体系里指**不同缺陷**（tmpfs 缓存 vs probe 竞态），会直接错位追责。
* 建议裁定：**以本报告编号为准**（t10 应称 F7；本报告 F8 是 probe 竞态），或在 t10/t11 的 output 中显式写明「本任务 F8/F9 ↔ `reports/72` 的 F7/新增 F9」的映射。此事**未裁定前**我在 t7 中一律用「reports/72 编号」并注明映射。

### 12.7 t5 gate 与结论时效

* 本报告完成于 t8/t9 **terminal 之前**；captain 已把后续 gate 定在 **t8 与 t9 都终态之后**。当前盘面：**t8 completed、t9 completed、t10/t11 pending**。
* **需在 t10/t11 落地后重跑的结论**：§5.1/§5.3 的 demo/probe 断言条数与结果、§5.4 冷/热自举与空间预检、§6 门禁计数、§11.2 中 `scripts/serve-web-demo.sh`、`scripts/web-demo-verify.sh`、`web/README.md` 的 sha。
* **不受影响**：§3（对 live 信令的 e2e，与被改文件无关）、§4.2（对 `web/lib/signaling.js` 的独立断言——其 sha 未变，且 §12.4 已证明 t9 未改语义）、§5.2（我的独立双页，绑定当时页面与库的 sha）。
* 若需在全部 repair 终态后**重跑并出具追加验证**，请开一个新的 verification 任务；**不建议**直接改写已终态 t5 的判定。

---

## 13. 附录二：冻结前 gate、编号映射与「证据带 sha」流程要求

> 依 captain 指令（2026-09-23，要求把 t5 降档为准备阶段）追加。本节**不新增任何终局判定**；
> 只登记状态、编号映射与流程要求，以及两处**与 captain 观测不一致的盘面事实**（供其核对）。

### 13.1 t5 的平台状态（事实，非我的选择）

* `t5` 在 **22:31:56** 已由我提交为 **completed（attempt 1）**，**早于** captain「降档为准备阶段」的指令到达。
* 平台约束：terminal 结果不可由成员自改（我的任何 `update_task` 都会因 attempt 失效被拒）。若确需把 t5 退回非终态，**须由 captain 用 reassign/重开机制处理**；我无法也不应自改。
* 因此我按如下方式遵守 gate 精神：**自本条起，t5 不再作任何终局判定、不再跑宿主机 start / demo / probe 的判定性运行**，直到 captain 明确「已冻结」。
* 建议（避免终态被反复改写）：冻结后**新开一个 verification 任务**做终局复跑；本报告 §1 的判定则按 §12.7/§13.6 的时效表理解。

### 13.2 新增 finding：F9（共享 `/tmp` 固定运行目录，medium）

* captain 指令称其为「F7」；**本报告的 F7 已被「默认缓存落 tmpfs」占用**（captain 的 t10 标题称该条为 F8），为免冲突，本条登记为 **F9**，映射见 §13.3。
* 现象（captain 报告）：`scripts/serve-web-demo.sh` 的 pid/state/log 直接落在**共享** `/tmp` 且用**固定文件名** ⇒ 存在他属主（如 root）残留时，admin 的 start 会失败（captain 观测：裸 EACCES + EXIT=1）。
* 我**源码层**核对（现行 sha256 `b4df4bb4…`）：
  * `:47` `RUN_DIR="${WEB_DEMO_RUN_DIR:-${TMPDIR:-/tmp}}"`；`:50`–`:52` 固定名 `web-demo-serve.pid` / `.state` / `.log` ⇒ **无 per-user 隔离**（与 F9 的前提一致）。
  * 但现行版本**已有针对性处理**：`:115`–`:129` 的 `prepare_runtime()` 先试写，失败时打印 note、`rm -f` 陈旧文件后重试，仍失败才给出**两条明确诊断**（含 owner 与 uid）并返回 1 ⇒ 「裸 EACCES 且无解释」这一半在现行版本已缓解。
* **未做（依 gate 暂缓）**：我尚未复跑「他属主残留文件」场景（需在宿主机临时制造 root 属主残留，属判定性运行）。冻结后按 t10 验收第 4 条复跑：admin 的 start 必须 **EXIT=0**（不再需人工删文件）。
* requiredFix（与 t10 验收一致）：`RUN_DIR` 含用户标识（如 `id -u`）使 pid/state/log 隔离；不可写时给出**明确 EXIT=2**而非 1；README 同步说明。
* 关联：本报告 §8.3 的 F7（默认缓存落 tmpfs）与本题**不是同一缺陷**，勿混。

### 13.3 finding 编号映射（请 t7 复核以此表为准）

| 本报告编号 | 含义 | captain/台账中的指代 | 状态 |
|---|---|---|---|
| F7 | 默认缓存目录落在 tmpfs（空间预检已缓解） | t10 标题里的「**F8**」 | 已缓解 |
| F8 | **交付入口 probe 就绪竞态（blocker）** | 台账暂无编号（t11 要求「probe 19/19」但未含就绪等待） | **未修**（§12.5） |
| **F9** | **共享 `/tmp` 固定运行目录（RUN_DIR 无用户隔离）** | captain 本次指令里的「**F7**」 | 源码层已部分缓解，场景复跑待冻结后 |
| F10 | 孤儿 chrome 进程卫生（tmpfs 被打满的主因之一） | t11 描述里的「**F9**」 | 由 t11 承接（pending） |

⇒ **同一个「F7」在两边指不同缺陷，同一个「F8」在两边也指不同缺陷**；t7 复核与报告引用必须以本表消歧。

### 13.4 流程要求（自本条起强制）：每条证据必须带被测 sha

* 本报告从 §0.5/§11.2 起已对关键结论带 sha；自本条起**一律**按下述格式给证据：
  「`<相对路径>` @ `sha256前12…`：<原始观测>（<原始输出路径>）」
* 举例（我 t5 的实际格式）：「`scripts/serve-web-demo.sh` @ `b4df4bb4…`：宿主机 admin 零 node 下 `START_EXIT=0`、既有静态文件 HTTP 200、裸 status `EXIT=0`（`tmp/verifier-evidence/t5-v5-host-serve.log`）」。
* t9 相关证据必须同时带 `web/lib/signaling.js` 的 sha（我 t5 的证据即为 `158f678f…`，与 t9 output 一致，见 §13.5）。
* 未带 sha 的证据一律视为**不可引用**（含我此前登记的、盘面已变的条目，一律以 §13.6 的时效表决定是否重跑）。

### 13.5 两处与 captain 观测不一致的盘面事实（请核对）

以 2026-09-23 22:36 的实测为准：

1. **两个纯值开关并未缺失**：`bash scripts/web-demo-verify.sh --cache <cache> --print-chrome-path` → 打印 chrome 路径、**EXIT=0**；--print-sysroot → 打印 sysroot 根、**EXIT=0**（`scripts/web-demo-verify.sh` @ `43d2d355…`）。captain 记录的「grep 仍 0」在该 sha 上**不成立**；可能是 grep 模式（脚本用 `elif [ "$1" = "--print-chrome-path" ]` 形式，而非 `--flag)` 用例标签）导致的误判——**该形式也正是不被 doc-verify V7 认出的原因**（§10.7 已登记）。
2. **README 的宿主机路径已存在**：`grep -c "/opt/dsh-workspaces" web/README.md` → **4**（`web/README.md` @ `c9052486…`），captain 记录的「grep 仍 0」在该 sha 上不成立。
3. 另：captain 提到的 `scripts/serve-web-demo.sh` 最新 sha `2389e41a…` **在 22:36 的盘面上不存在**；现值为 `b4df4bb4…`，恰是**我 t5 验证时的那一个**（且与 captain 自测 F1–F4 通过时你记录的能力一致）。若你希望我以 `2389e41a…` 为准，请给该文件的获取路径或确认它被写回了上一版本。

### 13.6 我 t5 结论所绑 sha 与 22:36 盘面的对照（时效表）

| 结论 | 我验证时的 sha | 22:36 盘面 | 是否仍成立 |
|---|---|---|---|
| §3 V1 信令 e2e（19/19） | 与被改文件无关（对 live 服务） | — | **是**（不受此次变动影响） |
| §4.2 独立纯函数断言（10/10） | `web/lib/signaling.js` @ `158f678f…` | `158f678f…` **未变** | **是**（§12.4 另证 t9 未改语义） |
| §5.1 demo PASS | `scripts/web-demo-verify.sh` @ `43d2d355…` | `43d2d355…` **未变** | **是** |
| §5.2 独立双页 35/35 | `web/index.html` @ `1c29c31c…`、库文件 shas | 未变 | **是** |
| §5.5 两套自举一致性 | 同上 verify 脚本 | 未变 | **是** |
| §6 门禁 4797/4、133/0 | 全量工作树 | `reports/71` 新增（t6 进行中）→ 22:36 实测 **4803 checks / 4 warnings**、`i18n-audit` 133/0，**均 EXIT=0** | 数值变化、**不变式（EXIT=0、warnings 不增）仍成立**；冻结后重跑并登记新计数 |
| §8.2 F1–F4 宿主复跑 | `scripts/serve-web-demo.sh` @ `b4df4bb4…` | `b4df4bb4…` **未变** | **是**（冻结后建议再跑一次以绑定冻结 sha） |
| §12.1 t2 指纹 | `web/lib/signaling.js` @ `158f678f…` | 未变 | **是** |
| §4.1 单测计数 | `web/tests/signaling.test.mjs` @ `244d7a2f…`（258 PASS） | `244d7a2f…`（t9 已收口） | **是** |

⇒ 结论：captain 担心的「t5 证据会在冻结后大面积作废」**在上述对照下并未发生**——除门禁计数需重跑外，我 t5 的关键证据所绑 sha 与 22:36 盘面一致。真正需要重跑的触发条件只有：`scripts/serve-web-demo.sh`、`scripts/web-demo-verify.sh`、`web/lib/signaling.js`、`web/index.html`、`web/lib/{peer,observe,selftest,style}.{js,css}` 或 `web/tests/signaling.test.mjs` 在冻结前再变。

### 13.7 冻结前新增登记：白名单快照更新与「缓存落进仓库」风险

**A. `git status` 快照更新（22:40，供 t7 的白名单核验参照）**

```
 M README.md
 M README.en.md
?? reports/70-browser-call-demo-requirements.md
?? reports/71-browser-call-demo.md
?? reports/72-browser-demo-verification.md
?? scripts/serve-web-demo.sh
?? scripts/web-demo-verify.sh
?? web/
```

* `M README.md` / `M README.en.md` 是 **t6 的双语小节**改动，属白名单「根 README 双语小节」⇒ **不算越界**；t7 核验时不应把这两条当作违规。
* §6 当时的快照（4 项 `??`）已被本条取代；其余 `??` 项仍在白名单内。

**B. 风险：缓存若被指到 `<repo>/tmp/**`，会污染工作树并触发越界判定（medium，流程风险）**

* 事实（web-dev 反馈 + 我核对）：仓库 `.gitignore` **没有** `tmp/` 规则（`git check-ignore -v tmp` 空命中），而**`.gitignore` 不在本轮交付白名单内** ⇒ **不能用「加忽略规则」来兜底**，只能靠文档与纪律。
* 触发条件：有人把 `WEB_DEMO_CACHE`（或命令行 cache 选项）指向 `<repo>/tmp/**` 再跑验证脚本 → 会在仓库工作树内落 115 MiB 级 Chrome 与 sysroot，`git status` 多出 `?? tmp/` → 直接被 t7 的越界检查判违规；同时可能撞上 `/tmp` 之外的空间/权限问题。
* **本轮是否发生**：**未发生**。我 t5 的全部运行都显式把缓存放在**仓库外**（`CONTAINER: /data/dsh/home/workspace/tmp/verifier-evidence/cache`，427 MiB），22:40 实测**仓库内不存在 `tmp/`**，`git status` 亦无 `?? tmp/`。
* requiredFix（建议写进 t10/t11 的验收或 README 的复跑说明）：所有缓存目录必须在仓库之外（README §116 已如此表述，但需**显式禁止** `<repo>/tmp/**`），并在复验收尾时核对 `git status` 无 `?? tmp/`。
* 关联：`web/README.md:116` 已写明「自举到交付路径外的缓存、不依赖 `tmp/chrome-env/**`」；`reports/70-browser-call-demo-requirements.md:315` 已禁止把 `tmp/chrome-env/**` 当判定逻辑 —— 二者方向正确，缺的是对 `<repo>/tmp/**` 的**显式禁止**与 t10/t11 的自检项。

### 13.8 冻结前新增登记：盘面 sha 复核（captain 观测 vs 我实测）+ 否定实验 v2

> 依 §13.4 的流程要求（证据必须带被测 sha），本节把 captain 22:3x 的状态更新与我在 **22:40:05** 的实测逐条对照。

| 项目 | captain 报告 | 我 22:40:05 实测 | 判定 |
|---|---|---|---|
| `scripts/serve-web-demo.sh` | `db4fdb1e…` | **`b4df4bb4227880728bb2a187…`**（432 行） | **不一致** |
| `scripts/web-demo-verify.sh` | `f7515d3a…`（据此判 t3-amend-3 未交付） | **`43d2d3557bd154a68ebd74c9…`**（185 行） | **不一致** |
| 两个纯值开关 | `grep -c 'print-chrome-path\|print-sysroot'` = **0** | `print-chrome-path` **2 次**、`print-sysroot` **2 次**（脚本用 `elif [ "$1" = "--print-chrome-path" ]` 形式） | **不一致** |
| `web/README.md` | `06337660…`，`HOST: /opt/dsh-workspaces` = 3 | **`c9052486167547a8461f8074…`**，命中 **4** | **不一致** |
| `web/lib/signaling.js` | `158f678f…5b9f` | **`158f678f26c34fd14e2fe550…`** | 一致 |
| `web/tests/signaling.test.mjs` | `244d7a2f…ff1f` | **`244d7a2fe98c640799043bc5…`** | 一致 |
| `RUN_DIR` 固定名（F9/F7 根因） | 未闭环 | `:47` `${TMPDIR:-/tmp}` + `:50`–`:52` 固定名 ⇒ **一致（未闭环）** | 一致 |

**开关存在性的直接证据（@ `43d2d355…`）**：

```bash
CONTAINER: $ bash scripts/web-demo-verify.sh --cache <repo-external-cache> --print-chrome-path
<repo-external-cache>/chrome-headless-shell-linux64/chrome-headless-shell      # EXIT=0
CONTAINER: $ bash scripts/web-demo-verify.sh --cache <repo-external-cache> --print-sysroot
<repo-external-cache>/sysroot                                                  # EXIT=0
```

⇒ 「t3-amend-3 未交付」这一判定**在现行 `43d2d355…` 上不成立**；captain 观测到的 `f7515d3a…`（我 22:12 记录过该值）是**更早的修订**，说明其读数与本节的读数之间存在**盘面漂移**（`scripts/**`、`web/README.md` 在 22:12–22:40 间被多次改写；与 t10 待办期间「未 claim 改动」的记录一致）。

**对 t5 证据时效的影响**：22:40 的 `scripts/serve-web-demo.sh` = `b4df4bb4…`、`scripts/web-demo-verify.sh` = `43d2d355…`、`web/README.md` = `c9052486…`，**与我 t5 验证时的 sha 相同** ⇒ §13.6 的结论**仍成立**（我的 F1–F4/F6 证据绑定这些 sha）。

**否定实验 v2（captain 指定变体，scratch 副本，仓库未动）**：

| 变体 | 结果 |
|---|---|
| 副本原样（现行修复版） | `=== PASS 258, FAIL 0 ===` **EXIT=0** |
| 变体 1（直接存原生函数） | `=== PASS 256, FAIL 2 ===` **EXIT=1** |
| 变体 2（保留 typeof 守卫的 `? setTimeout : null`） | `=== PASS 256, FAIL 2 ===` **EXIT=1** |

两个变体的失败项**完全相同**，即 t9 新增的两条行为性断言（「默认实现调用原生 setTimeout/clearTimeout 时不把实例泄漏为 this」）⇒ 断言对两种旧写法都有牙齿。原始输出 `tmp/verifier-evidence/t5-extra-t9-redtest-v2.log`；仓库 `web/lib/signaling.js` sha 前后一致。

**F9（共享 `/tmp` 运行目录）severity 收录 captain 口径**：**medium，根因未闭环**（captain 报告其植入他属主陈旧文件后 `START_EXIT=1`、用户路径仍启动失败）。我尚未独立复跑该场景（gate 待命中）——**冻结后按 t10 验收复跑**，期望「admin start EXIT=0」。

**给流程的建议**：state 更新与裁定也应遵守 §13.4 的 sha 纪律——**每个读数都给出「文件 @ sha + 时刻 + 原始命令」**，否则在连续改盘的窗口里，裁定会建立在过期读数上（本节即为一例：读数相差约 20–28 分钟）。

### 13.9 冻结前新增登记：F7 升为 blocker、默认路径断言、负控红灯澄清与一次**失效触发**

**A. F7（默认缓存落 tmpfs）severity 升为 blocker —— 与 captain 的「F8」是同一件事**

* 编号再声明：captain 本次称其为「**F8**」；**本报告的 F8 是「probe 就绪竞态」**，故本条**仍是 F7**，只升 severity 并补证据（勿增重复条目）。
* 我 22:45 在**新**修订上独立复核（`scripts/web-demo-verify.sh` @ `0ef89189a95068a7834beadc…`）：

```bash
CONTAINER: $ env -u WEB_DEMO_CACHE bash scripts/web-demo-verify.sh --print-cache
cache dir : /tmp/web-demo-chrome-cache      # 默认仍在 tmpfs
size      : 0                                # 尚未自举
log file  : /tmp/web-demo-verify.log
（未打印文件系统可用空间 ← 与 t8-amend-7 的要求不符）
EXIT=0
CONTAINER: $ df -h /tmp   → tmpfs 256M, avail 151M
CONTAINER: $ du -sm <repo-external-cache> → 427 MB（完整缓存）
```

  ⇒ 默认入口（不带 `WEB_DEMO_CACHE`）**必然 ENOSPC**；该路径此前**从未被验证**（此前冷/热自举都在我指定的仓库外缓存上完成）——与 F1/F2 同类：「验证绕开了真实默认路径」。**F7 = blocker**，归 t8（t8-amend-7）。

**B. 新增 t5 断言（写入断言清单，冻结后执行）**

* `cache.default-path`：**不带** `WEB_DEMO_CACHE` 时，`bash scripts/web-demo-verify.sh --print-chrome-path`（或 `--mode probe`）必须**要么成功（非空 + EXIT=0）**，**要么**给出**明确可执行**的消息（含建议路径 + `EXIT=2`）；**不允许**裸 ENOSPC、不允许静默空值 + `EXIT=0`。
* `cache.report-free-space`：print-cache 开关必须打印该文件系统的可用空间（当前**不打印**，见 A）。
* `repo.no-tmp-pollution`：复跑结束后 `git status --porcelain` **不得**出现 `?? tmp/`（缓存一律在仓库之外）。

**C. 仓库污染坑（合并登记，并纠正「4 条」口径）**

* 我 22:45 的 `git status --porcelain` 为 **8 条**：`M README.md`、`M README.en.md`、`?? reports/70`、`?? reports/71`、`?? reports/72`、`?? scripts/serve-web-demo.sh`、`?? scripts/web-demo-verify.sh`、`?? web/`。
* 其中 **`M README.md` / `M README.en.md` 是 t6 的双语小节**（白名单内），**`?? reports/71` 是 t6 的报告**（白名单内）⇒ **「只有 4 条白名单项」是 t6 之前的口径，已过时**；t7 必须按 8 条核验，否则会误报越界。
* 仓库内**无 `tmp/`**（无污染）；缓存一律在仓库外。

**D. 否定实验（web-dev 要求，我已在 §12.4/§13.8 做过两遍）**

| 变体 | 结果 |
|---|---|
| scratch 副本原样（现行修复版） | `PASS 258, FAIL 0` EXIT=0 |
| 变体 1 直接存原生函数 | `PASS 256, FAIL 2` EXIT=1 |
| 变体 2 保留 typeof 守卫的 `? setTimeout : null` | `PASS 256, FAIL 2` EXIT=1 |

失败项均为两条行为断言（「默认实现调用原生 setTimeout/clearTimeout 时不把实例泄漏为 this」）⇒ 断言对两种旧写法都有牙齿。证据：`tmp/verifier-evidence/t5-extra-t9-redtest.log`、`t5-extra-t9-redtest-v2.log`。

**E. 过时红灯口径（供 t7 采信规则）**

* **规则**：任何**早于** `web/lib/signaling.js` @ `158f678f…`（22:01:21）产生的红日志，都是**修复前证据**，不得作为现状引用。captain 提到的 `13:59:13Z`（= 本机 21:59:13）**早于**该修复时刻 ⇒ 按规则自动作废；现行树上同一命令在我实测（verify 脚本 @ `43d2d355…`）为 **6/6 PASS**（`selftest.run pass=17 fail=0`）。
* **额外警告（我在 /tmp 实际找到的红灯）**：`HOST: /tmp/web-demo-verify.log`（14:26:15Z）有 1 条 FAIL，但它是一次**故意的负对照**——`signaling=ws://127.0.0.2:8443/ws`（不可达端点）⇒ 该 FAIL 是**预期行为**，t7 **不得**把它当成回归证据。引用任何红灯前必须看命令头（`signaling=`/`cache=`/`mode=`）。

**F. ⚠️ 一次失效触发已发生：`scripts/web-demo-verify.sh` 又变了**

* 我 t5 的 demo/probe/开关/两套自举证据都绑在 `43d2d3557bd154a68ebd74c9…`；22:45 实测已成 **`0ef89189a95068a7834beadc…`** ⇒ **§5.1、§5.3、§5.5、§12.2 与 §13.8 中依赖该脚本的结论，须在冻结后用新 sha 重跑**（这正是 §13.4「证据带 sha」与 §13.6「时效表」要解决的问题）。
* 仍与我 t5 验证时一致（结论继续成立）：`scripts/serve-web-demo.sh` @ `b4df4bb4…`（F1–F4）、`web/README.md` @ `c9052486…`（F6）、`web/lib/signaling.js` @ `158f678f…`（§4.2）、`web/tests/signaling.test.mjs` @ `244d7a2f…`（§4.1）。

### 13.10 冻结前新增登记：sha 复核（第三次）、开关的 tmpfs 自举、与门禁语义缺陷 #2

**A. sha 复核（我 22:47 自己跑 `sha256sum`，不采信任何粘贴值）**

| 文件 | 22:47 实测（前 24 位） | 与我 t5 验证时 | 备注 |
|---|---|---|---|
| `scripts/serve-web-demo.sh` | **`5a9aad0e19b3a0f4d6efcbd6…`** | **已变**（我验证时 `b4df4bb4…`） | t8 正在改；captain 观测过 `db4fdb1e`、`b4df4bb4`，两者都不是现值 |
| `scripts/web-demo-verify.sh` | **`ebb88eac0820ee001dccb08a…`** | **已变**（我验证时 `43d2d355…`） | captain 本轮的 `43d2d355` 在其发出时刻后即被取代 |
| `web/lib/signaling.js` | `158f678f26c34fd14e2fe550…` | 未变 | 与 captain 权威值一致 |
| `web/tests/signaling.test.mjs` | `244d7a2fe98c640799043bc5…` | 未变 | 与 captain 权威值一致 |
| `web/README.md` | `c9052486167547a8461f8074…` | 未变 | — |

* 记录一条流程事实（与 captain 的判断一致）：**成员粘贴的 sha 不可采信**——我在本报告里一律用自己 `sha256sum` 的结果；凡引用他人 sha 必注明来源与时刻。本轮 captain 报的 `scripts/serve-web-demo.sh = db4fdb1e…` 与 `scripts/web-demo-verify.sh = 43d2d355…` **在对应的对照时刻都不是盘面值**。
* **失效触发扩面**：由于**两个 `scripts/*` 都已变**，须在冻结后按新 sha 重跑的 t5 结论扩为：§5.1 demo、§5.3 probe（含 F8 flake）、§5.4 冷/热自举与空间预检、§5.5 两套自举一致性、§8.2 F1–F4 宿主机复跑、§13.7 的缓存坑复现。**仍不受影响**：§3（live 信令 e2e）、§4.2（对 `web/lib/signaling.js` 的独立断言）、§4.1（单测 258/0）、§5.2（我的独立双页，绑页面/库 sha）。

**B. 登记项 A：两个纯值开关在 `43d2d355…` 上的行为（captain 报告，我源码/行为层一致）**

* 带 `--cache <仓库外完整缓存>` 时：两开关打印**纯值**且 `EXIT=0`（我 §13.8 的实测）。
* 不带任何 cache 覆盖（即 `env -u WEB_DEMO_CACHE`）时：两开关会**尝试把自举做进 tmpfs 的默认路径**，随后 `EXIT=2`（captain 实测；其消息含 `bootstrapping now …` / `cache hit … (22.0 MiB)`）。按 t3-amend-3 的字面口径，`EXIT=2` **不算**「空值 + exit 0」⇒ **形式上未违规**，但**自举目标正是 F7 的 tmpfs 默认路径** ⇒ 问题从「开关缺失」转移为「开关默认行为落在错误卷」。
* **残缺缓存的处理**：captain 报告在 `HOST: /tmp/web-demo-chrome-cache` 留下约 22 MiB 残缺缓存；我在 **22:47** 实测该目录**存在但为 0 字节**（可能已被清理）。无论残留与否，**t5 一律使用仓库外完整缓存**（`CONTAINER: /data/dsh/home/workspace/tmp/verifier-evidence/cache`，427 MB），并在报告中注明该残目录的来源（captain/t8 的一次默认路径自举尝试），**不把它当作有效缓存**。

**C. 登记项 B：门禁语义缺陷 #2 —— `doc-verify.sh` 的 `in-repo` 分类优先且不回退（G-1 观察，不判失败）**

* 源码层核对（`scripts/doc-verify.sh`，我实测行号）：`:481` 起对 `scripts/*.sh scripts/*.py deploy/*` 做 live 扫描并直接置 `CMDCLASS[$t]="in-repo"`（`:483`）；随后合并 `doc/design/_generated/host-commands.md` 时用 `case "${CMDCLASS[$t]:-}" in`（`:488`）—— `in-repo) ;;`（`:489`）**命中即跳过**，只有未命中才采用表里的值（`:491`）。
* 后果：若有人在新的交付脚本里写出 `case … --log)` 这类标签，`--log` 会被**静默改写为 `in-repo`**（**计数不变**，但 `host-commands.md` 里 `flag` 类条目所承载的「host-only 证据约束」被放宽）。
* 定性：这是「**形式合规、门禁不可检出、语义被放宽**」的**第二个实例**（第一个是 §8.4 的 A-3 围栏把容器路径标 `HOST:`）。本条**不判失败**，但列入 **t7 检查项**：`grep` 新增脚本中是否存在会遮蔽 host 命令表的 `case … --flag)` 写法（重点是其中的日志开关）。
* 说明：我**没有**做「加一个脚本触发该改写」的活体演示——那需要在仓库 `scripts/` 内新增文件（越界），而这类不可检出缺陷恰恰无法在不越界的前提下活体演示；故本条为**源码层结论**，按 §13.4 附 `scripts/doc-verify.sh` 的行号与 sha。

**D. F7 的证据合并**：captain 实测 `df -h /tmp` avail **137M**、完整缓存 **441M**；我实测 avail **151M**、完整缓存 **427 MB**（皆为不同时刻的体积口径）——**结论一致**：默认路径不可行。两套数字均记录，避免 t7 采信单一数字时误判为矛盾。

### 13.11 冻结前登记：F7 的系统级副作用、t4 红灯根因闭环与我的一次双复跑

**A. F7（= captain 的「F8」，默认缓存落 tmpfs）升为 blocker 的**系统级**证据**

* captain 实测（我采信并登记，注明来源）：默认缓存 `/tmp/web-demo-chrome-cache` 需解包 261 MB，而 `HOST: /tmp` 是 **256 MB 的 tmpfs** ⇒ 一次默认路径自举**把 tmpfs 打到 100%（avail 0）**；**连累无关写入**——web-dev 在一次 heredoc 写入上吃到 `cat: write error: No space left on device`。captain 随后清掉无效残缺缓存（144 MB：`--print-cache` 自报 `size: absent`，仅 `dl/` + 半个 `chrome-headless-shell-linux64/`），tmpfs 回到约 44%。
* 我 22:49 复核：`HOST: /tmp` = `tmpfs 256M 106M 106M 151M 42%`（已恢复）；`/tmp/web-demo-chrome-cache` **存在但 0 字节**（残目录已清空）。
* **定性**：F7 不只是「命令失败」，而是**系统级副作用**（挤占同一 tmpfs 上其它进程的写入）——与 F1/F2 同类「验证绕开真实默认路径」的缺陷相比，**影响面更大**，故 severity=**blocker** 维持。
* **处置要求（逐字，归 t8 / t8-amend-7）**：① 默认缓存路径必须落在**非 tmpfs**（如 `${XDG_CACHE_HOME:-$HOME/.cache}`）；② 加**空间 preflight**：可用空间不足（阈值约 700 MB）→ **明确消息 + EXIT=2**，**绝不允许**先下载再 ENOSPC；③ `--print-cache` 打印该文件系统的可用空间；④ 默认路径冷自举 + 小 tmpfs 下的 preflight，各给原始输出。
* **t5 复跑纪律（写入断言清单）**：一律**显式指定仓库外缓存**（我使用 `CONTAINER: /data/dsh/home/workspace/tmp/verifier-evidence/cache`，427 MB，含 chrome + sysroot），**绝不使用默认路径**。captain 建议的 `CONTAINER: /data/dsh/home/workspace/tmp/chrome-env` 我 22:49 实测**不存在**（`du` 无输出）——若有人照该路径执行，会触发一次新的 441 MB 自举；请改用实际存在的完整缓存。

**B. t4 红灯根因闭环（captain 的结论 + 我的独立复跑）**

* **根因**：`web/lib/signaling.js:496–505` 把原生定时器直接存为属性（`this.setTimeoutImpl = setTimeout`），`this.setTimeoutImpl(...)` 调用时 `this` 被绑成客户端实例 ⇒ `TypeError: Illegal invocation`，异常抛在 `handleOpen → startHeartbeat`，**中断 handleOpen** ⇒ 其后的 `sendPendingIntent()` 不执行 ⇒ **`create` 帧从未发出**（不是「发了没回」）。该缺陷由 **t9** 覆盖修复（现行 sha `158f678f…`）。
* captain 的三条驳回证据（我采信其表述，并注明来源）：① 上线帧逐字 `{"type":"create"}`、`MESSAGE_SCHEMAS.create.fields=[]`、未知键剔除、缺必填抛错；② 连接前已注册 9 个 type 监听器，修复后帧序 `seq4 send create → seq5 recv created`，**修复前时间线里连 `recv created` 都没有**；③ `socketsCreated=1 / socketsClosed=0`、`lifecycleEvents=[]`、单 socket 零重连 ⇒ 排除「重连/多 socket」假设。
* **我的独立双复跑（captain 指定；本次为**非冻结**证据——`scripts/web-demo-verify.sh` 仍在变）**：

| 运行 | 命令要点 | 结果 |
|---|---|---|
| RUN 1 | `--mode demo`（默认 8081），`--cache <仓库外完整缓存>` | origin=http://localhost:8081、`selftest.run pass=17 fail=0`、**`summary: 6/6 passed`**、`result: PASS`、**EXIT=0**、roomId D2G3TW |
| RUN 2 | `--mode demo --port 8094`，同一缓存 | origin=http://localhost:8094、`selftest.run pass=17 fail=0`、**`summary: 6/6 passed`**、`result: PASS`、**EXIT=0**、roomId PV9FY6 |

  被测 sha：`scripts/web-demo-verify.sh` @ `ebb88eac…`、`web/index.html` @ `1c29c31c…`、`web/lib/signaling.js` @ `158f678f…`。原始输出 `tmp/verifier-evidence/t5-demo-rerun-both.log`（含 `t5-demo-rerun-8081.log`、`t5-demo-rerun-8094.log`）。
* **tmpfs 无害证明**：两次运行前后 `HOST: /tmp` 均为 `256M 106M used / 151M avail / 42%` —— 显式指定仓库外缓存后**对 tmpfs 零影响**，可作为「复跑纪律有效」的证据。
* **计数口径**：我两次均为 **6/6**（captain 台账记 **5/5**）——与 §12.2 同一现象（该脚本断言条数在修订间增长），不影响 PASS 结论。
* **过时红灯口径（t7 采信规则）**：`ops` 那份 FAIL 日志为 **13:59:13Z 的修复前旧日志**（早于 `web/lib/signaling.js` @ `158f678f…`（22:01:21））⇒ 按 §13.9 E 的规则**自动作废**，不得作为现状引用。

**C. 对 t6 的要求（captain 已接受）**：上述根因与**改后原始输出**须由 t6 在 reports/71-browser-demo-call-demo.md（注：实际文件名为 `reports/71-browser-call-demo.md`）中以**一节**登记；t7 复核该项是否落地。

### 13.12 t5 gate 变更与 t10 复开登记（含缓存盘点与复跑纪律）

**A. gate 变更（依 captain 指令）**：t5 的 gate 由「t8 终态」改为 **t10 终态**（t8 虽已 terminal，但两项未闭环 ⇒ 复开 t10）。`reports/72` 中两条状态改写为「**t8 未闭环 → t10 复开**」，t10 终态后再按最终 sha 改写为「t10 内闭环 / 仍未闭环」。

**B. 编号映射（第四版，务必以此消歧）**

| 本报告编号 | 含义 | 本轮 captain 指令中的称呼 |
|---|---|---|
| F7 | `web-demo-verify.sh` 默认缓存落 tmpfs（blocker，含系统级副作用） | 「**F8**」（blocker） |
| **F8** | **交付入口 probe 就绪竞态（blocker，仍无 repair 承接）** | **本轮指令未提及** |
| F9 | `serve-web-demo.sh` 的运行目录（RUN_DIR）共享 `/tmp`、未按用户隔离（medium，根因未闭环） | 「**F7**」（medium） |

⇒ captain 的 F7/F8 与本报告的 F7/F8 **交叉相反**（其 F7 = 我的 F9，其 F8 = 我的 F7）。t7 若按任一方口头编号复核会**必然错位**；本表为唯一口径。

**C. 缓存盘点（我 22:52 实测，供 t10/t11 规划）**

* `CONTAINER: /data/dsh/home/workspace/tmp/chrome-env` → **不存在**（已被删；captain 的记录一致）。
* `HOST: /tmp/web-demo-chrome-cache` → 目录存在但 **0 字节**（残目录已清空）。
* **仍在**的工作区缓存（426 MB 级，非交付物）：`CONTAINER: /data/dsh/home/workspace/tmp/vv-neg`（426M）、`tv-final`（426M）、`tv-cold`（426M）⇒ 合计约 **1.28 GB**；captain 所述「删除两个 426 MB」在当前盘面上**只部分成立**（另有三份仍在）。删除共享缓存未走请求流程一事，已并入 **F5 过程性合集**作为实例 (d)。
* `HOST: /tmp` 当前 `256M / 106M used / 151M avail / 42%`（健康）。

**D. t5 终局复跑纪律（写入断言清单，t10 终态后执行）**

1. **两次冷自举**：① 我**独立自举**那份（`WEB_DEMO_INDEP_*`）——我的 harness 默认缓存**已经是非 tmpfs** 路径（`web/tests/web-demo-independent.mjs:89` 取 `<repo>/../../tmp`，即 `CONTAINER: /data/dsh/home/workspace/tmp/web-demo-indep-cache`），**不会**用 `${TMPDIR:-/tmp}`；② 交付脚本那份用 `WEB_DEMO_CACHE=<workspace>/tmp/chrome-env`（当前不存在 ⇒ 正好做一次**真冷自举**）。两次都要留**耗时 / 体积 / 原始输出**。
2. **每次跑交付脚本前后各取一次 `df -h /tmp`**；若 tmpfs 用量上涨 ⇒ 判 **F7（= captain 的 F8）未修**。
3. **重取最终 sha**，并把本轮所有中间 sha 标注为「**非冻结**」；判定只绑最终 sha。

**E. 可引用的参照物（都在仓库外，非交付物）**

* `HOST: /tmp/t2base/`：`doc-verify.{before,after,final}.txt`、`i18n-audit.{before,after,final}.txt`、`tests.after.txt`（合计 80 KB）——t7 做门禁基线对照可直接引用；**若这些文件丢失，t7 必须自行重跑重取**（它们不在白名单内，不保证留存）。
* `CONTAINER: /data/dsh/home/workspace/tmp/web-demo-sha256.txt`：登记 `web/lib/signaling.js` 与 `web/tests/signaling.test.mjs` 两条 sha256，**与我 22:47 实测逐字一致**。

### 13.13 退出码语义的**实测确认**、断言清单、运行卫生的源码核实（含 ops 引用 sha 已过期的第三次记录）

**A. 退出码语义：以 ops 的实现为准（实测确认，不判为缺陷）**

我在现行修订（`scripts/web-demo-verify.sh` @ `ebb88eac…`，仓库外完整缓存）上实测三条：

| 输入 | 实测 EXIT | 原始输出要点 |
|---|---|---|
| `--signaling ws://127.0.0.2:8443/ws`（格式合法但**不可达**） | **1** | `FAIL harness.error :: … websocket error connecting to ws://127.0.0.2:8443/ws`；`summary: 3/4 passed`；`result: FAIL (exit 1)` |
| `--signaling ws://127.0.0.1:8443/ws`（禁用地址守卫） | **2** | `web-demo-verify: 127.0.0.1:8443 is the DSH harness Caddy inside this container…`（**在任何网络 I/O 之前**拒绝） |
| 未知参数 | **2** | `web-demo-verify: unknown argument: --definitely-not-a-flag` |

* **生效契约**：`exit 2 = 拒绝按给定输入测试（用法/环境问题）`；`exit 1 = 已测试且未通过（**含端点不可达**——可达性正是 `signaling.ping-pong` 等断言的被测对象）`。
* 我早期曾提议「不可达 = exit 2」，与实现不一致 ⇒ **改以 ops 的契约为准**，本条登记为**语义差异已消解**（不是缺陷，也不需要改动）。
* 附带确认：对不可达端点交付版是 **fail-fast**（connect 是后续断言前提）⇒ `3/4` 而非早期原型的 `10/11`，属**提前止步**而非断言减少。
* 原始输出：`tmp/verifier-evidence/t5-sem-unreachable.stdout`、`t5-sem-guard.stdout`、`t5-sem-unknownflag.stdout`。
* **tmpfs 对照**：两条 probe 前后 `HOST: /tmp` 均 `256M / 106M used / 151M avail / 42%` ⇒ 显式指定仓库外缓存时**零 tmpfs 影响**。

**B. 断言清单（与实现一致）**

* probe 模式 **19 条**（我通过运行实测 19/19；ID 顺序与 ops 所列一致）：`browser.boot`、`page.secure-context`、`page.mediadevices`、`signaling.ping-pong`、`signaling.created`、`signaling.joined`、`signaling.peerJoined`、`capture.fake-device`、`sdp.offer-contains-vp9`、`signaling.offer-answer-relayed`、`signaling.ice-exchanged`、`ice.connected`、`media.codec-vp9-getstats`、`media.bidirectional-rtp`、`media.frames-flowing`、`media.codec-vp9-getstats-inbound`、`media.audio-rtp`、`signaling.leave-peerleft`、`stats.exported`。
* demo 模式 **6 条**：`browser.boot`、`page.secure-context`、`signaling.ping-pong`、`page.selftest-api`、`selftest.run`、`stats.exported`（我两次复跑均 6/6）。
* 计数口径差（captain 台账 5/5 vs 我的 6/6）已解释：断言条数随修订增长，非结果差异。
* 双向 RTP：交付版自带 `media.bidirectional-rtp`（5 s 窗口内**双向**字节同时增长）⇒ 我不再建议补 `media.reverse-*`；我的独立实现另有等价断言（§5.2 的 `media.bidirectional-rtp.AtoB/BtoA`）。
* codec 判定：交付版确为 `codecId → codec` 表项再判 `mimeType`（非直读），与我的独立做法一致。

**C. 运行卫生与 preflight：源码层核实「已实现」的部分**

* **空间 preflight 已实现（在 JS 内核，不在 shell）**：`web/tests/lib/chrome.mjs` 在 bootstrapping 前检查可用空间，不足时抛出形如 `… has only 150.6 MiB free, but bootstrapping needs about 1.2 GiB …` 并最终以 **EXIT=2** 结束（我在 §13.10 B 观测到同形输出）。
* **运行期不再写 `/tmp`（在 JS 内核）**：`web/tests/verify-two-page.mjs:510` 设 `runtimeDir = join(opts.cacheDir, 'runtime')`（注释即写明「never the 256 MiB /tmp tmpfs」），`:514–:515` 开头清理陈旧 `profile-*`/`probe-*`，`:539–:540` 探针页也落在该目录；`web/tests/lib/cdp.mjs:17` 尊重传入的 `runtimeDir`。
* **仍然未改的是「默认路径」本身**：`scripts/web-demo-verify.sh:49` 现行为 `CACHE="${WEB_DEMO_CACHE:-${TMPDIR:-/tmp}/web-demo-chrome-cache}"` ⇒ **默认入口仍落 tmpfs**。故 F7（= captain 的 F8）的**核心缺陷仍在**，t10 的验收重点应表述为「**默认路径改到非 tmpfs**」（preflight 与运行卫生已在 JS 内核达成，可作为已完成项核对，避免重复劳动）。
* 可用热缓存（仓库外，非交付物）：`CONTAINER: /data/dsh/home/workspace/tmp/tv-final`（426 MB）与我的 `CONTAINER: /data/dsh/home/workspace/tmp/verifier-evidence/cache`（427 MB）；`HOST: /tmp/web-demo-chrome-cache` 已不存在。

**D. sha 纪律：ops 引用的两个 shell sha 在对照时刻已过期（第三次）**

* ops 本轮报：`web-demo-verify.sh` = `43d2d355…`、`serve-web-demo.sh` = `b4df4bb4…`，并称「这两个 shell 未变」。
* 我 22:47 实测：`web-demo-verify.sh` = **`ebb88eac0820ee001dccb08a…`**、`serve-web-demo.sh` = **`5a9aad0e19b3a0f4d6efcbd6…`** ⇒ 两个 shell **都已变**。
* 这是**第三次**同类偏差（前两次见 §13.8、§13.10）。再次强调 §13.4 的规则：引用 sha 必须现场 `sha256sum` 并附时刻；否则会出现「按过期 sha 判缺陷已修/未修」的错位。
* 本条同时作为 **t7 检查项**：核对 `reports/71`/README 中引用的 sha 是否与最终冻结值一致。

### 13.14 F8（probe 就绪竞态）**重新定级：blocker → medium（潜在缺陷，未再观测到失败）**

**新证据（我 22:52 实测，同一台机、同一仓库外缓存）**

* 现行修订 `scripts/web-demo-verify.sh` @ `ebb88eac…`：**连续 5 次** probe 全部 `summary: 19/19 passed` / `result: PASS` / **EXIT=0**（5/5，零失败）。原始输出 `tmp/verifier-evidence/t5-f8-flake5.log`（每轮另有 `t5-f8-run1..5.log`）。
* 对照（更早修订 @ `43d2d355…`，见 §5.3）：同一命令 **6 次里 3 次失败**，失败形态为 `harness.error :: … Cannot read properties of undefined (reading 'connect')`。
* 5 次全绿在早前 50% 失败率下的巧合概率约 3% ⇒ 更可能是**触发条件被消除**，而非纯运气。

**根因是否消除？—— 没有，源码层仍在**

* `web/tests/lib/cdp.mjs:124` 的 `newPage()` **仍只做** `Target.createTarget(url)` + attach + `Runtime/Page.enable`，**不等待页面就绪**；`web/tests/verify-two-page.mjs:359` 仍紧接着 `evaluate(window.__probe.connect(…))` ⇒ **竞态在源码里仍然存在**。
* 为何现在不再复现：`web/tests/verify-two-page.mjs:510` 已把运行目录改为 `<cache>/runtime/`（注释即「never the 256 MiB /tmp tmpfs」），`:514–:515` 开头清理陈旧 `profile-*`/`probe-*`，`:539–:540` 探针页同址 ⇒ **页面加载不再受 tmpfs 压力拖慢**，就绪窗口在实践中够用。换句话说：**症状被环境修复掩盖，缺陷本体未修**。

**定级与建议**

* severity：**blocker → medium（潜在竞态）**。理由：在当前修订上**未再观测到失败**（5/5），且触发条件（tmpfs 压力/慢 profile）已被移除；但根因未消除，在更慢的机器或更高负载下仍可能复现。
* 建议（归 t10/t11 或新 repair，属于**低成本加固**）：`newPage()` 内等待页面就绪（复用其 `goto()` 的 load 事件路径，或轮询 `typeof window.__probe === 'object'` 并带超时）；并把 **「连续 ≥5 次 19/19」** 保留为验收项——我已在现行修订上提供 5/5 的原始数据，可直接采用。
* **tmpfs 对照**：这 5 次运行前后 `HOST: /tmp` 均 `256M / 106M used / 151M avail / 42%` ⇒ 与 F7 无关（显式外部缓存）。

> 定性说明（避免误解）：本条**不是**「把 blocker 悄悄降级」——是**基于新增的 5/5 反证**重新定级，并**同时保留**「根因未消除」的源码证据与加固建议。若 t7 或 captain 认为应按「缺陷本体未修」维持 blocker，我按裁定改回，只需一句话。

### 13.15 t10∧t11 双终态 gate、F9/F10/F11 登记与并发风险（含一次实测限制说明）

**A. gate 更新**：t5 的 gate 由「t10 终态」改为 **t10 ∧ t11 双终态**（t10 = shell 脚本默认路径/运行目录/README；t11 = harness 内核转正）。`reports/72` 的最终判定与改写留待两者终态。

**B. 编号映射（第 5 版，含本轮新增项）**

| 本报告编号 | 含义 | captain 本轮称呼 |
|---|---|---|
| F7 | `web-demo-verify.sh` 默认缓存落 tmpfs | （此前称 F8） |
| **F8** | **probe 就绪竞态（已重定级 medium，待裁定）** | 本轮未提及 |
| F9 | `serve-web-demo.sh` 运行目录共享 `/tmp`、未按用户隔离 | （此前称 F7） |
| F10 | 孤儿 chrome 进程 / `profile-*` 把 tmpfs 打满（主因） | 「**F9**」（blocker 级，ops 已自修，待 t11 转正） |
| **F11（新增）** | **自报数不一致**：`stats.exported` 原先在 summary 之后记录 ⇒ 控制台 19 行 PASS 却写 18/18（已修，待 t11 转正） | 「**F10**」 |

**C. F10（孤儿进程 / tmpfs 主因）登记 + 我的一次实测限制说明**

* 采信 captain：tmpfs 被打满的**主因是 62 个孤儿 chrome 进程**（被 `timeout` 打断未回收）及其 `/tmp/web-demo-profile-*`，而非缓存路径本身；修复为 `SIGTERM/SIGINT` 回收 + 开头清理陈旧 `profile-*`/`probe-*` + Chrome 的 user-data-dir 移入 `<cache>/runtime/`。
* 我 22:55 用 `/proc/*/cmdline` 扫描时**匹配到 5 个含 chrome 字样的进程，但它们全是 ops 当次 `--print-chrome-path` 运行（含一次冷自举 `am3-cold`）的 bash/node 包装进程**，不是孤儿 chrome 二进制 ⇒ **该次计数不能作为「孤儿进程数」的证据**；真正的孤儿计数必须在**无并发运行**时、按 chrome 二进制路径（`chrome-headless-shell`）匹配。
* 因此该项的 t5 断言 `proc.no-orphan-chrome`（一次完整运行后残留 chrome 进程 = 0）**尚未独立复跑**，登记为冻结后执行；同时把「先探测是否有并发运行再开冷自举」写入 t5 纪律（见 E）。

**D. F11（自报数不一致）登记**：采信 captain（`stats.exported` 先记录再汇总；修复后 19/19）。t5 断言 `count.triple-agreement`：**逐行数 PASS 行 == 日志尾部 summary 计数 == 导出 JSON 的 `total`**，三者必须一致；且**不得**出现「控制台 N 行 PASS 而 summary 写 M≠N」。

**E. 退出码语义（captain 已裁定：接受，记为「已文档化的语义差异」，不记缺陷）+ 我的独立复核**

* 生效契约：**`exit 2` = 拒绝按给定输入测试**（禁用端点 / 未知 flag / 无可用探活原语 / 缓存空间不足）；**`exit 1` = 测了但没过（信令不可达属 1）**。与 `reports/70` §8 A-3「不可达必须判 FAIL 并说明，不得静默跳过」一致。
* **独立复核（我 22:52 实测，非引用 ops 自述）**：`--signaling ws://127.0.0.2:8443/ws` → **EXIT=1**，且日志含**明确的** `FAIL harness.error :: … websocket error connecting to …`、`summary: 3/4 passed`、`result: FAIL (exit 1)` ⇒ **不是静默跳过**（有失败断言行 + 汇总 + 退出码三重体现）。`ws://127.0.0.1:8443/ws` 与未知参数 → **EXIT=2** 且在任何网络 I/O 之前拒绝。原始输出 `tmp/verifier-evidence/t5-sem-unreachable.stdout`、`t5-sem-guard.stdout`、`t5-sem-unknownflag.stdout`。
* 要求 README 写明该语义（归 t10）；本条在 `reports/72` 按**实际行为**记录，并标注「**已文档化的语义差异**」。

**F. t11 转正对象已核对：captain 引用的三个内核 sha 与我 22:55 实测**逐字一致**

| 文件 | captain 引用 | 我 22:55 实测 |
|---|---|---|
| `web/tests/verify-two-page.mjs` | `5970bca4…` | **`5970bca458ebd886a2c0758d…`** ✓ |
| `web/tests/lib/chrome.mjs` | `d084a99a…` | **`d084a99aafc3d29369a82b6b…`** ✓ |
| `web/tests/lib/cdp.mjs` | `84bda763…` | **`84bda763f09d39522ecaa965…`** ✓ |

「内核改动在未 claim 任务的情况下完成」并入 **F5 过程性合集**作为实例 (e)（captain 记为本轮第 4 次同类过程问题）。

**G. 并发风险（本轮实测到，写入 t5 纪律）**：22:55 我扫描进程时发现**ops 正在跑 `--print-chrome-path` 且在做一次冷自举**（缓存目录 `am3-cold`）⇒ 若我同时开冷自举/headless 运行，会出现资源竞争与「谁的缓存/谁占用 8081」的相互干扰。故 t5 纪律追加：**开跑前先扫 `/proc/*/cmdline` 确认无并发验证任务**，并在报告中记下「本任务独占运行」的事实。

**H. 缓存策略（不变）**：交付脚本侧至少一次**冷自举**（F7/F10 的证据）；我的独立自举用 `WEB_DEMO_INDEP_*` 且目录在**非 tmpfs**（harness 默认 `<repo>/../../tmp/web-demo-indep-cache`）；可复用热缓存 `CONTAINER: /data/dsh/home/workspace/tmp/tv-final`（426 MB，ops 提供）或我自备的 `CONTAINER: /data/dsh/home/workspace/tmp/verifier-evidence/cache`（427 MB，独立性优先）；每次运行前后各记 `df -h /tmp`。

### 13.16 编号冲突的**修复方案**（含 captain 要求的「F8 = F11」注释如何写才不撞号）、t7 追加清单与 reports/72 自身 sha 的时效说明

**A. 编号映射（第 6 版）——请严格按本表读**

| 本报告编号 | 含义 | captain 台账编号 | 备注 |
|---|---|---|---|
| F7 | `web-demo-verify.sh` 默认缓存落 tmpfs | （此前称 F8） | t10 承接 |
| **F8** | **probe 就绪竞态** | **「F11」**（本轮新编号） | **t11 + t11-amend-1 承接**（inScope 含 `web/tests/lib/cdp.mjs`） |
| F9 | `serve-web-demo.sh` 运行目录共享 `/tmp` | （此前称 F7） | t10 承接 |
| F10 | 孤儿 chrome / `profile-*` 打满 tmpfs | 「F9」 | t11 承接 |
| **F11** | **自报数不一致（19 PASS vs 18/18）** | 「**F10**」 | t11 承接（其描述即写「F10 自报数不一致」） |

* ⚠️ **captain 要求在本报告里把 F8 注为「= F11」——若照字面写会与本报告的 F11（自报数不一致）撞号**。故本报告采用**别名标注**，逐字如下（并已在本附录生效）：
  > `F8（probe 就绪竞态）= captain 台账的「F11」（**别名**）；**勿**与本报告的 F11「自报数不一致」混淆。`
* 建议终局口径：**以本报告编号为准**；他方编号只作为别名出现。这样 t7 复核不会再出现「同一个 F11 指两件事」。

**B. F1–F4 措辞更正 → 作为 **t7** 的交付项（不回改本报告）**

* 过期表述：本报告 §8.2 与 §12.3 写的「待 t8 正式收口」（t8 当时未终态）。
* **现行正确表述**（t7 在 **reports/73-browser-demo-review.md** 落地）：
  > 「t3 terminal 版未闭环 → t3 后修订修复 → **t8 内收口** → verifier 独立复跑通过（宿主机零 node、`start/status/stop` 全 EXIT=0、既有静态文件 HTTP 200、`--engine python3` 不触碰 node、裸 `status` 显示真实端口、跨端口 start 非 0）。」
* captain 指示不回改本报告，我照办；本条仅作**更正登记**，实际修正在 t7 报告。

**C. ⚠️ 本报告自身 sha 的时效（第三次同类问题，这次是引用方引用报告本身）**

* captain 本条称「`reports/72` 已交付且 sha `7af8407e…`」——那是我 **22:31 完成时点**的值；此后依 captain 的多轮指令追加了 §12–§13.16，**现行为 `8da1181b329189a1fcffce48…`（898 行）**。
* 因此：**引用 `reports/72` 时必须现场重取 sha**（本报告结论仍以「当时判定的被测 sha」为准，而不是以报告文件 sha 为准；报告 sha 只用于版本定位）。
* `reports/70-browser-call-demo-requirements.md` 全程未改：sha 仍 `967cb530a682fd39b4ab69f85526a99520d67ef74d28fa3bb603af6bb642e293`（与 captain 复核一致）。

**D. t7 追加清单（已按 captain 指示记在我名下；t7 的 inScope 为 **reports/73-browser-demo-review.md**，以下结果写进该文件）**

1. 对**冻结后的最终 sha** 重跑 probe **≥3 次**（验证 F8 修复后确实不 flake），逐次记录 **sha + 时刻 + summary**。
2. 复核 `web/README.md` 关于 **probe 稳定性**的断言是否与实测一致（若 README 写「稳定 19/19」，需有连续多次证据支撑）。
3. 复核 `reports/71-browser-call-demo.md` 是否违反三条硬口径：① 未逐字复制 `reports/70` §8 A-3 围栏；② 引用 `reports/72` 时必须**连 §10 open items 一并引**；③ t4 红灯根因与 `reports/70` 的「**未证项**」登记齐备。
4. 顺带带出 §B 的 F1–F4 措辞更正。

**E. 保留的 open item**：现行修订的**冷自举**因网络未跑完（§5.4）。t11 的验收要求**冷缓存 19/19**，届时会再覆盖一次；**若仍失败，按环境问题登记并附原始报错，绝不写成通过**（依 captain 口径）。
