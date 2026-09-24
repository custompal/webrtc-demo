# reports/73 — T7 独立复核：`reports/71` 与整轮交付的一致性/可复现性/如实性

> **复核对象**：`reports/71-browser-call-demo.md`（t6/t12 交付）与整轮交付面。
> **纪律**：只读复核；**未修改任何被复核文件**；所有测量均带「被测 sha + 时刻 + 原始输出路径」。
> **测量时刻**：本报告的全部盘面读数取自 **2026-09-24 01:36–01:41 (+0800)** 的现场实测；此后若盘面再变，按「报告 sha 只用于版本定位」原则重取。

---

## 1. Verdict 摘要

| 项 | 结论 |
|---|---|
| **verdict** | **needs_revision** |
| 主因 | `reports/71` 的**登记值与盘面不一致**：§3 有 **6** 行、§11.3 有 **6** 行被**后续合法改动取代**（合计 **12** 行）；且 `web/README.md` 在本次复核进行中**再次变动**（01:36:07 → 01:37:17） |
| 定性 | **「被取代」而非「登记不实」**：t12 明确标注取值时刻为 `2026-09-23 23:46:19`，其后 00:08–01:26 的改动是 t10/t11 的合法收口；writer 已请 captain 开 **t13**（再冻结登记） |
| requiredFix | 见 §11.1：由 **t13** 在 `reports/71` 追加 **§12「t6 终态后的再冻结登记」**，以**现场重取的 sha 表取代 §11.3**并同步 §3 的 6 行；同时在 §11.3 标注「本表为 23:46:19 时点快照，已被 §12 取代」 |
| 其余五轴 | 门禁三连 **全 EXIT=0**、可复现抽检 **5 项全通过**（含 3 项媒体/ICE）、越界 **零违规**、如实性 **登记齐备**、白名单 **干净** |

**findings 索引**：F-T7-1 登记值被取代（§11.1，主因，**high**）｜F-T7-2 复核期间盘面仍在变（§11.2，medium）｜F-T7-3 `reports/70` 子集门禁 EXIT=1（§11.3，medium）｜F-T7-4 `PROBE-RACE` 的严重度与承接状态需按现版改写（§11.4，low）｜F-T7-5 `reports/71` §3 的 `web/tests/**` 三行同属被取代（已并入 F-T7-1，记录用）。

> **§14 = 第二回增量复核（01:46–01:48）**：判据与首测逐字相同，结论不变（12 处不一致）；**F-T7-2 → low（已落地）、F-T7-3 → low（当前 PASS，易失依赖）**；其现场 sha 表 **取代 §10**，**t13 再冻结以 §14.2 为准**。

---

## 2. 登记值 vs 盘面（核心验收项）

### 2.1 口径

* 逐行比对我**现场实测**（`sha256sum` + `wc -l` + `stat -c %s`）与 `reports/71` §3（21 行登记表）及 §11.3（t12 的 6 行冻结表）。
* 比较基准：完整 sha256 前缀 16 位 + 行数 + 字节。原始输出：`tmp/verifier-evidence/t7-registry-vs-disk.log`。

### 2.2 §3 登记表：**14 行一致 / 6 行被取代**（另 1 行为故意不登记的 `reports/72`）

| 文件 | §3 登记 | 盘面（01:40 实测） | 判定 |
|---|---|---|---|
| `web/tests/verify-two-page.mjs` | `5970bca4…` 640 行 36 528 B | **`c1d6a426…` 645 行 36 817 B**（mtime 01:08:30） | **被取代** |
| `web/tests/lib/cdp.mjs` | `84bda763…` 158 行 6 400 B | **`2c896f1a…` 189 行 8 184 B**（mtime 01:08:19） | **被取代** |
| `web/tests/lib/chrome.mjs` | `d084a99a…` 149 行 6 547 B | **`9d2d9ebf…` 160 行 7 215 B**（mtime 00:45:44） | **被取代** |
| `scripts/serve-web-demo.sh` | `b4df4bb4…` 432 行 17 017 B | **`63c0d22a…` 441 行 17 551 B**（mtime 00:08:12） | **被取代** |
| `scripts/web-demo-verify.sh` | `43d2d355…` 185 行 8 474 B | **`977de48b…` 257 行 11 888 B**（mtime 01:26:22） | **被取代** |
| `web/README.md` | `c9052486…` 261 行 24 601 B | **`59d419ff…` 355 行 37 940 B**（mtime 01:37:17） | **被取代** |
| 其余 14 行（`web/index.html`、`web/app.js`、`web/lib/*` 6 件、`web/tests/signaling.test.mjs`、`web/tests/signaling.e2e.mjs`、`web/tests/web-demo-independent.mjs`、`web/tests/lib/{deb,serve,zip}.mjs`） | — | 与登记**逐字一致** | 一致 |

### 2.3 §11.3 冻结表：**0 行一致 / 6 行全部被取代**

| 文件 | §11.3 登记（取值时刻 23:46:19） | 盘面（01:40 实测） |
|---|---|---|
| `scripts/serve-web-demo.sh` | `5818e342…` 439 行 17 325 B | **`63c0d22a…` 441 行 17 551 B** |
| `scripts/web-demo-verify.sh` | `0ee83c00…` 240 行 10 940 B | **`977de48b…` 257 行 11 888 B** |
| `web/README.md` | `30517d03…` 339 行 33 376 B | **`59d419ff…` 355 行 37 940 B** |
| `web/tests/lib/chrome.mjs` | `ba54cd14…` 154 行 6 897 B | **`9d2d9ebf…` 160 行 7 215 B** |
| `web/tests/lib/cdp.mjs` | `84bda763…` 158 行 6 400 B | **`2c896f1a…` 189 行 8 184 B** |
| `web/tests/verify-two-page.mjs` | `5970bca4…` 640 行 36 528 B | **`c1d6a426…` 645 行 36 817 B** |

### 2.4 结论

* 按 t7 契约「任一不一致即为 finding」⇒ **存在 12 处不一致**，故 verdict = **needs_revision**。
* 但**不存在登记不实**：t12 的 §11.3 已声明取值时刻，且 §3 的 6 行与 §11.3 的 6 行**互相同源于同一批后续改动**（t10/t11 在 00:08–01:26 落地）。修复是**登记动作**（t13 追加 §12 再冻结），**不是**实现缺陷。
* 另注：`reports/71` §3 **故意不登记** `reports/72` 的 sha/行/字节（该文件在 T5 终态后仍被追加）—— 这一处理**正确**，本报告予以确认。

---

## 3. 门禁与测试复跑（t7 验收第 2 条）

| 命令 | 结果 | EXIT |
|---|---|---|
| `bash scripts/doc-verify.sh` | `PASS (4803 checks, 4 warnings)` | **0** |
| `bash scripts/i18n-audit.sh` | `PASS (133 checks, 0 warnings)` | **0** |
| `bash scripts/doc-verify.sh --only reports/71-browser-call-demo.md` | `PASS (116 checks, 0 warnings)` | **0** |
| `node web/tests/signaling.test.mjs` | `=== PASS 258, FAIL 0 ===` | **0** |
| `node web/tests/signaling.e2e.mjs` | `19/19 passed`（对 live `ws://47.238.144.66:8443/ws`） | **0** |

被测 sha：`scripts/serve-web-demo.sh` @ `63c0d22a…`、`scripts/web-demo-verify.sh` @ `977de48b…`、`reports/71-browser-call-demo.md` @ `256ad4c5…`。原始输出：`tmp/verifier-evidence/t7-gates-and-tests.log`。

---

## 4. 可复现性抽检（t7 验收第 3 条：≥4 条，含 1 条媒体/ICE）

| # | 复跑的结论 | 命令/入口 | 结果 | 原始输出 |
|---|---|---|---|---|
| 1 | **媒体/ICE** 双页端到端（§6 引 T5 的 V3） | `bash scripts/web-demo-verify.sh --mode probe --cache <仓库外缓存>` ×3 | **3/3 次 `19/19 passed`、`result: PASS`、EXIT=0** | `tmp/verifier-evidence/t7-probe3.log` |
| 2 | **媒体/ICE** 独立判定（§6 引 T5 的 35 条） | `node web/tests/web-demo-independent.mjs --phase all --cache <仓库外缓存>` | **`35/35 passed` EXIT=0**（含 DTLS/ICE connected、VP9 经 codecId 双向、双向字节增长、面板与导出） | `tmp/verifier-evidence/t7-indep-harness.log` |
| 3 | **B-8 保座 90 s**（媒体/ICE 类硬约束） | 仓库外 scratch CDP 脚本（复用交付 harness 的 CDP plumbing，**断言自写**）：`simulateDrop()` 只断 WS 不发 leave | **9/9 PASS**：重连后 peerId **不变**（`peer-002`）、`state=in_room`、**在线侧时间线 peerLeft 计数 = 0**、B 端 inbound video 仍有 **130 443 B** | `tmp/verifier-evidence/t7-b8-grace.log` |
| 4 | 宿主机 `serve-web-demo.sh` 全序列（§6 引 T5 的 F1–F4） | 经 SSH 以 `admin`（**零 node**，非 8081）：`start --port 8093`→裸 `status`→`curl`→跨端口 `start`→`stop` | `START=0`、`STATUS=0`（显示真实端口 8093 + 「you asked about 8081」）、`HTTP_lib=200`、`CROSS=1`（含真实端口）、`STOP=0`（释放已核） | `tmp/verifier-evidence/t7-host-serve.log` |
| 5 | **宿主机 `python3` 整页启动**（t7 补做项） | 同上，`curl http://127.0.0.1:8093/` → `200` 且返回 `<!doctype html>` / `<html lang="zh-CN">` | 通过（整页 JS 执行仍以容器内无头为准） | `tmp/verifier-evidence/t7-host-serve.log` |

**补充（`PROBE-RACE` 的现版核验）**：`web/tests/lib/cdp.mjs` 新增 `waitForReady()`（`:153`，轮询 `document.readyState==='complete'`）与 `waitForGlobal(name)`（`:167`），并在 `:187` 对非空白页调用 `waitForReady()`；调用方 `web/tests/verify-two-page.mjs:353/:354` 增加 `waitForGlobal('__probe', 20000)`。⇒ 竞态**结构性修复**；叠加抽检 #1 的 3/3 绿（另有 ops 报的 5 连绿，属其交付证据）。

---

## 5. 越界检查（t7 验收第 4 条）

```
 M README.en.md
 M README.md
?? reports/70-browser-call-demo-requirements.md
?? reports/71-browser-call-demo.md
?? reports/72-browser-demo-verification.md
?? scripts/serve-web-demo.sh
?? scripts/web-demo-verify.sh
?? web/
```
* 共 **8** 条，**全部在白名单内**（`M` 两条为 t6 的根 README 双语小节）；**无 `?? tmp/`**（`REPO-TMP` 自检通过）。
* `reports/70-browser-call-demo-requirements.md` = `967cb530…`、`reports/72-browser-demo-verification.md` = `598f9add…` ⇒ **均未被改动**（与 T5/T6 记录一致）。
* `reports/54–69`、`app/**`、`signaling/**`、`doc/design/**` 均未出现在变更面 ⇒ **零越界**。

---

## 6. 如实性检查（t7 验收第 5 条）

| 检查点 | 结果 |
|---|---|
| 未验证/未通过项是否登记 | ✅ `reports/71` §8 共 **15** 项（含 F8/PROBE-RACE blocker、真机互拨未执行、`reports/70`「无越界改动」未证、现行修订冷自举未完整复跑等） |
| V3 的替代路径声明是否与 `reports/72` 一致 | ✅ 两侧均声明**环境可用、未走手工矩阵**；`reports/71` 明确「不得把替代路径写成已通过」的口径 |
| 硬口径①：**未逐字复制** `reports/70` §8 A-3 围栏 | ✅ 全文无「容器路径标 `HOST:`」的三行围栏（grep 0 命中） |
| 硬口径②：引用 `reports/72` 结论**连带 §10 open items** | ✅ 报告头部与 §6 均声明「`reports/72` 判定为未做/未通过/未证的项一律在 §8 原样登记」，§10/open item 提及 4 处 |
| 硬口径③：**t4 根因**与 `reports/70`「未证项」登记齐备 | ✅ §11.1② 登记 t2 指纹作废与 t9 转正（`158f678f…`）；§8 第 3 行登记 `reports/70`「除授权处外零改动」= **未证项**（残差 79 B） |
| 是否存在美化/省略 | ✅ 未发现；`reports/71` 主动登记了 T5 终态后盘面变动与「不重新验证」的边界 |

---

## 7. 缺陷名注册表与三列对照表（captain 指令，逐字收录）

> **编号权威 = 缺陷名注册表。字母一律只是别名，任何字母都不得单独引用**（因为已出现 5 个同号不同义，字母体系整体失效）。

| 缺陷名（权威引用） | `reports/72` 旧称 | captain 台账旧称 | 主题 | 承接/状态 |
|---|---|---|---|---|
| `CACHE-TMPFS` | F7 | F8 | 默认缓存落 tmpfs（256 MiB）→ 必然 ENOSPC | t10 **已闭环** |
| `RUNDIR-SHARED` | F9 | F7 | 运行目录共享 `/tmp` 固定名、无 per-user 隔离 | t10 **已闭环** |
| `REPO-TMP` | （无别名） | F12 | `<repo>/tmp/**` 污染工作树 | t10/t11 自检 + README 禁止 |
| `PROBE-RACE` | F8 | F11 | `--mode probe` 就绪竞态（`newPage()`） | t11 **已结构性修复**（见 §4 补充） |
| `CHROME-ORPHANS` | F10 | F9 | 孤儿 chrome 进程 + profile 残留 | t11 已转正 |
| `STATS-COUNT` | F11 | F10 | `stats.exported` 计数不一致 | t11 已转正（现 summary/逐行/JSON 三数一致） |
| F1–F6（历史） | 同 | 同 | 宿主 node 门禁 / `port_open` / status 假端口 / 跨端口假绿 / 过程性 / 用户页面服务只能在宿主机 | t8 已收口 |

---

## 8. F1–F4 措辞更正（captain 指定，落于本报告）

> 「t3 terminal 版未闭环 → t3 后修订修复 → **t8 内收口** → verifier 独立复跑通过（宿主机零 node：`start/status/stop` 全 EXIT=0、既有静态文件 HTTP 200、`--engine python3` 不触碰 node、裸 `status` 显示真实端口、跨端口 start 非 0）。」

本报告 §4 #4/#5 即为**在现行 revision（`scripts/serve-web-demo.sh` @ `63c0d22a…`）上的独立复跑**，故该措辞在本轮复核中**成立**。

---

## 9. `reports/72` 的双 sha 与修订链（captain 指定登记）

* **两个 sha 并存**：完成时点 **`7af8407e…`（22:31，t5 终态 output 记录值）** → 现行 **`598f9add872b5c496b56e56f…`（936 行 / 90 770 B / mtime 22:52:30）**。
* **性质**：`reports/72` = **t5 交付正文 + captain 书面指令下的追加**（追加类别：① gate 变更；② 编号映射；③ 缓存坑与空间预检；④ 门禁语义缺陷；⑤ t7 清单与冻结 sha 表）。
* **引用规则**：报告文件 sha **只用于版本定位**；结论一律绑定「判定当时被测物的 sha」。
* 该文件自 22:52:30 起未再变动（T7 实测 `598f9add…` 未变）。

---

## 10. 冻结 sha 表（T7 现场实测，供 t13 再冻结与后续引用）

| 文件 | sha256（前 16） | 行 | 字节 | mtime |
|---|---|---|---|---|
| `scripts/serve-web-demo.sh` | `63c0d22a1fd4ff7b` | 441 | 17 551 | 2026-09-24 00:08:12 |
| `scripts/web-demo-verify.sh` | `977de48b6d04fc3d` | 257 | 11 888 | 2026-09-24 01:26:22 |
| `web/README.md` | `59d419ff8b49e0d7` | 355 | 37 940 | 2026-09-24 01:37:17 |
| `web/tests/verify-two-page.mjs` | `c1d6a4267e628b1b` | 645 | 36 817 | 2026-09-24 01:08:30 |
| `web/tests/lib/cdp.mjs` | `2c896f1add8b9e63` | 189 | 8 184 | 2026-09-24 01:08:19 |
| `web/tests/lib/chrome.mjs` | `9d2d9ebf3860f890` | 160 | 7 215 | 2026-09-24 00:45:44 |
| `reports/71-browser-call-demo.md` | `256ad4c5ff7219e9` | 437 | 40 589 | 2026-09-23 23:48:05 |
| `reports/70-browser-call-demo-requirements.md` | `967cb530a682fd39` | 396 | 44 037 | 2026-09-23 21:48:29 |
| `reports/72-browser-demo-verification.md` | `598f9add872b5c49` | 936 | 90 770 | 2026-09-23 22:52:30 |
| `web/lib/signaling.js` | `158f678f26c34fd1` | 1 399 | 53 754 | 2026-09-23 22:01:21 |
| `web/tests/signaling.test.mjs` | `244d7a2fe98c6407` | 923 | 42 660 | 2026-09-23 22:10:50 |

全字段快照（含全部 22 件交付面文件）：`tmp/verifier-evidence/t7-live-baseline.log`。

---

## 11. findings（每条含可执行 requiredFix）

### F-T7-1 `reports/71` 的登记值与盘面不一致（**high**）

* 事实：§3 **6** 行 + §11.3 **6** 行 = **12** 处被取代（明细见 §2.2/§2.3）。
* 成因（**非登记不实**）：t12 于 23:46:19 取值并交付；其后 00:08–01:26 t10/t11 合法改动六个文件。
* **requiredFix**：由 **t13** 在 `reports/71` **追加 §12「t6 终态后的再冻结登记」**，以**现场重取**的 sha 表取代 §11.3、并同步 §3 的 6 行；同时在 §11.3 标注「本表为 2026-09-23 23:46:19 时点快照，已被 §12 取代」。**不得**回改 §1–§10 正文。

### F-T7-2 复核期间目标仍在变动（**medium**）

* 事实：`web/README.md` 在我复核过程中**两次变化**（`94d37ed4…` 01:36:07 → `59d419ff…` 01:37:17）；其 mtime 距我第一次快照仅 **40 秒**。
* 影响：任何「登记值 == 盘面」的判定在冻结前都不可能稳定；本报告的 §2/§10 均为**时点快照**。
* **requiredFix**：先完成**冻结**（t13 落地 + captain 宣布冻结），再以其为基准做一次**增量复核**（只需重跑 §2 对比与 §3 门禁；§4 的可复现抽检已在本 revision 通过）。

### F-T7-3 `reports/70` 的子集门禁当前 **EXIT=1**（**medium**）

* 事实：`bash scripts/doc-verify.sh --only reports/70-browser-call-demo-requirements.md` → `reports/70:315 → missing workspace path: tmp/chrome-env/RECIPE.md (P2 hard-checked)` ⇒ `FAIL (1 failures, 258 checks) EXIT=1`。
* 成因：该行**为说明「不得把非交付物当判定逻辑」而引用了该路径**，而 staging 目录被 `--clean` 删除（若已重建为空目录，仍属「无内容」；P2 只校验存在性，恢复空目录即可回绿——需实测确认）。
* 另注**门禁模式差异**：**全量** `doc-verify` 仍 `PASS (4803/4)`，而 `--only reports/70` `FAIL`。
* **requiredFix**：captain 三选一 —— (a) 恢复被引用的路径（最小侵入）；(b) 授权对 `reports/70:315` 做有界修正；(c) 接受为**已知门禁回归**并在此登记。**t1 的该项验收在当前盘面不再可复现**。

### F-T7-4 `PROBE-RACE` 的严重度与承接需按现版改写（**low**）

* 事实：竞态已**结构性修复**（`waitForReady`/`waitForGlobal` + 调用方就绪门），且抽检 3/3 绿；`reports/71` §8 仍以「F8 blocker / 无承接」口径登记（属**时点快照**）。
* **requiredFix**：在 t13 的 §12 中把该条改写为「`PROBE-RACE`：t11 已结构性修复（附 sha 与 3/5 连绿证据）；严重度由 medium 收敛为『已修复，保留回归断言』」。

### F-T7-5（记录用，并入 F-T7-1）`reports/71` §3 的 `web/tests/**` 三行同属被取代

* `web/tests/verify-two-page.mjs`、`web/tests/lib/cdp.mjs`、`web/tests/lib/chrome.mjs` 的 §3 登记值均被 t11 改动取代（明细见 §2.2），与 F-T7-1 同源同修。

---

## 12. 复现命令汇总（T7 实测入口）

```bash
# 0) 现场取 sha（任何结论前必做）
cd /data/dsh/home/workspace/code/webrtc-demo
sha256sum scripts/serve-web-demo.sh scripts/web-demo-verify.sh web/README.md
wc -l scripts/serve-web-demo.sh            # 期望 441
grep -c require_node_for_probe scripts/serve-web-demo.sh   # 期望 0

# 1) 门禁三连 + 测试
bash scripts/doc-verify.sh; bash scripts/i18n-audit.sh
bash scripts/doc-verify.sh --only reports/71-browser-call-demo.md
node web/tests/signaling.test.mjs; node web/tests/signaling.e2e.mjs

# 2) 媒体/ICE（容器内，仓库外缓存）
bash scripts/web-demo-verify.sh --mode probe --cache /data/dsh/home/workspace/tmp/tv-final
node web/tests/web-demo-independent.mjs --phase all --cache /data/dsh/home/workspace/tmp/verifier-evidence/cache

# 3) 宿主机侧（零 node，经 SSH 以 admin，非 8081 端口）
HOST: cd /opt/dsh-workspaces/code/webrtc-demo && bash scripts/serve-web-demo.sh start --port 8093
HOST: bash scripts/serve-web-demo.sh status; curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8093/
HOST: bash scripts/serve-web-demo.sh start --port 8092   # 期望 EXIT=1 且打印真实 8093
HOST: bash scripts/serve-web-demo.sh stop --port 8093

# 4) 门禁语义守卫（在仓库根运行；输出为空 = 无放宽）
live=$(grep -hoE '^[[:space:]]*--[a-z][a-z0-9-]*\)' scripts/*.sh scripts/*.py | tr -d ' )' | sort -u)
s5=$(awk '/^## 5\. Machine-readable/{f=1;next} f && /^## /{exit} f && /^\| `/{print}' doc/design/_generated/host-commands.md \
     | awk -F'|' '{t=$2;c=$3;gsub(/^ +| +$/,"",t);gsub(/^ +| +$/,"",c);gsub(/`/,"",t); if(c!="in-repo") print t}' | sort -u)
comm -12 <(printf '%s\n' "$live") <(printf '%s\n' "$s5")
```

门禁语义守卫实测：`live=18` 个标签、§5 非 in-repo 且以 `--` 开头 **13** 个、**交集为空**（`--log` 未被劫持）。（token 分类表：`doc/design/_generated/host-commands.md` §5。）

---

## 13. 未做/未验证项（如实登记）

1. **增量复核已在 §14 执行**（第二回，01:46–01:48）：因 F-T7-2 触发（`scripts/web-demo-verify.sh` 于 01:44:07 再变），已重跑 §2 比对与 §3/§14.5 门禁，并在现行 revision 上复跑 probe ×3 与 `--phase env`；**仍未对「t13 落地后的最终冻结 sha」做第三次比对**——该项待 captain 宣布冻结后按 §14.1 的最小清单（registry 比对 + 门禁）执行。
2. **真机 App ↔ 浏览器互拨未执行**（硬约束③不引入 App/服务端日志通道）——沿用 `reports/71` §8 的登记，不写成已通过。
3. **relay-only（49152–49200、quota 45）端到端未复跑**；`mDNS` 候选行为未验证。
4. **`reports/70` 的「除授权处外零改动」仍为未证项**（残差 79 B，源于 T5 的 pre-edit 快照只摘录区域）。
5. **`--print-cache size: 4.6M` 的旧观察**为自举中/清理后的瞬时态，已在 `reports/72` §13.9 记为观察；现默认缓存为完整 426 M。
6. 本报告未修改任何被复核文件；`reports/70`、`reports/71`、`reports/72` 的 sha 与 T7 开始前一致（§5）。

---

## 14. 增量复核（第二回：2026-09-24 01:46–01:48 +0800）

> **触发**：`scripts/web-demo-verify.sh` 在本报告 §2/§10 取值的 **01:41:37 之后又变了一次**（mtime **01:44:07**）——这正是 F-T7-2 要求的「冻结后重跑 §2 与 §3」。
> **效力**：本节读数**取代 §2.2/§2.3、§3、§10 的时点值**；§1 的判定口径与 **verdict 不变**。

### 14.1 §2 重跑：登记值 vs 盘面（判据与首测逐字相同）

| 维度 | 01:40 首测 | **01:47 复测** | 结论 |
|---|---|---|---|
| `reports/71` §3（登记表） | 14 一致 / **6 被取代** | 14 一致 / **6 被取代** | 不变量 |
| `reports/71` §11.3（t12 冻结表 6 行） | 0 一致 / **6 全部被取代** | 0 一致 / **6 全部被取代** | 不变量 |
| **不一致合计** | **12** | **12** | **verdict 仍为 needs_revision** |

* 该表由**机器逐行比对**生成（从登记行提取 64 位十六进制 + 反引号路径 → 现场 `sha256sum` + `wc -l`），非人工誊写。原始输出：`tmp/verifier-evidence/t7b-registry-compare.log`。
* **与首测的唯一差异**：被取代行里 `scripts/web-demo-verify.sh` 的「盘面值」由 `977de48b…`（257 行）→ **`139ae5e7…`（258 行）**。⇒ **t13 再冻结必须以 §14.2 的表为准**，不得沿用 §10 的表。

### 14.2 现场 sha 表（2026-09-24 01:46:34 +0800 实测，取代 §10）

| 文件 | sha256（前 16） | 行 | 字节 | mtime |
|---|---|---|---|---|
| `scripts/serve-web-demo.sh` | `63c0d22a1fd4ff7b` | 441 | 17 551 | 2026-09-24 00:08:12 |
| `scripts/web-demo-verify.sh` | **`139ae5e773a1938b`** | **258** | **12 006** | **2026-09-24 01:44:07** |
| `web/README.md` | `59d419ff8b49e0d7` | 355 | 37 940 | 2026-09-24 01:37:17 |
| `web/tests/verify-two-page.mjs` | `c1d6a4267e628b1b` | 645 | 36 817 | 2026-09-24 01:08:30 |
| `web/tests/lib/cdp.mjs` | `2c896f1add8b9e63` | 189 | 8 184 | 2026-09-24 01:08:19 |
| `web/tests/lib/chrome.mjs` | `9d2d9ebf3860f890` | 160 | 7 215 | 2026-09-24 00:45:44 |
| `web/tests/web-demo-independent.mjs` | `60373ce99d5f7bfc` | 824 | 49 033 | 2026-09-23 22:24:54 |
| `web/tests/signaling.e2e.mjs` | `b50f66e9c7f4d77e` | 381 | 17 887 | 2026-09-23 22:09:30 |
| `reports/70-browser-call-demo-requirements.md` | `967cb530a682fd39` | 396 | 44 037 | 2026-09-23 21:48:29 |
| `reports/71-browser-call-demo.md` | `256ad4c5ff7219e9` | 437 | 40 589 | 2026-09-23 23:48:05 |
| `reports/72-browser-demo-verification.md` | `598f9add872b5c49` | 936 | 90 770 | 2026-09-23 22:52:30 |

全字段快照（22 件交付面，含 `git status` 与 `stat`）：`tmp/verifier-evidence/t7b-snapshot.log`。字节数经 `stat -c %s` 与 `wc -c` **双路核对一致**：`tmp/verifier-evidence/t7b-bytes.log`。

* **（追加标注，2026-09-24 18:03Z 现场机器复核）本表为 01:46:34 时点快照，非永久基准**：11 行中 **10 行在 t13 后仍与盘面 `sha256sum`/`wc -l` 一致**（含六件交付面全部字段）；**唯一被取代的是 `reports/71-browser-call-demo.md` 一行**（本表 `256ad4c5ff7219e9…` / 437 行 → t13 后 **`594f00f85b07de9b…` / 512 行 / 48 128 B / 01:53:21**）。**被取代 ≠ 登记不实**：其继承值与完整 revision 链见 **§15** 开头与 §15.1；引用 `reports/71` 一律以 §15 的冻结值为准。原始输出：`tmp/verifier-evidence/t14-s142-recheck.log`。

### 14.3 F-T7-2 状态更新：medium → **low（已落地，无 requiredFix）**

* **01:44:07 的改动是什么**：`scripts/web-demo-verify.sh` 的 `--cache` usage 文案修正（257 → 258 行 / 11 888 → 12 006 B）。修后 `:31`–`:32` 与实现 `:73 CACHE="${WEB_DEMO_CACHE:-$(default_cache_dir)}"` 一致（不再让读者以为默认落 tmpfs）；交付脚本中**没有** §5 已登记的 host-only 日志开关的 case 标签（`grep -c` = 0，两脚本同）。
* **自纠（本节首版误写，01:51 +0800 改正）**：上面该实现的**行号**首版写作 `:72`，实为 **`:73`**（现场 `grep -n 'CACHE="${WEB_DEMO_CACHE'` → 73）；只改行号，结论与其余数值不变。本报告修订链：`a45a6a74…`（247 行，01:41:37，t7 首版）→ `0ff06dc0…`（342 行，01:49:50，追加 §14）→ **本次自纠后的现值**（`sha256sum` 现场重取；本报告不自我登记自身 sha）。
* **如实登记一处取证限制**：上一版字节**未留存**，无法做字节级 diff ⇒ 本次只能按「行数 / 字节 / 文案前后对照」核，不能声称「纯注释改动」。
* **影响面已实测排除**：在现行 `139ae5e7…` 上 probe ×3 仍 **3/3 `19/19 passed`**（§14.4），独立 harness 的 `--phase env`（会**真调**交付脚本的 `--print-chrome-path` / `--print-sysroot` 做两套自举交叉核对）**3/3 PASS**（§14.4）。
* **纪律结论**：ops 在 `17:38:23Z` 给出的「六件最终 sha」在 **6 分钟后被其自身的文案修正取代**。这不是个人过失，而是**交付面仍在收口**的必然结果 ⇒ 再次印证「引用 sha 一律现场重取 + 附时刻」这条硬纪律；本条无 requiredFix。
* **另注（证据卫生，低）**：ops 的**消息正文**曾把 `web/README.md` 记作 38 064 B（其已更正），而**其自己归档的清单**（`tmp/ops-evidence/` 内的 FINAL-SHA 类清单，`stat`/`wc` 段）与现场实测均为 **37 940 B**（sha 与行数一致）。⇒ 以现场 `sha256sum`/`stat` 为准，结论不受影响。**该清单是 living document（头部自注「NOT a freeze marker」），行号会随重生成漂移，故本报告只引其文件名与字段名、不引其行号。**

### 14.4 现行 revision 上的复跑（绑定 sha 明确）

| # | 复跑项 | 绑定 sha | 结果 | 原始输出 |
|---|---|---|---|---|
| 1 | `bash scripts/web-demo-verify.sh --cache <仓库外缓存> --mode probe` ×3 | `web-demo-verify.sh` @ `139ae5e7…` | **3/3 `19/19 passed` · `result: PASS` · rc=0**；缓存 427 M；`/tmp` 前后 107 M / 42% **逐字相同**；残留 chrome **0** | `tmp/verifier-evidence/t7b-probe3.log` |
| 2 | `node web/tests/web-demo-independent.mjs --phase env --cache <仓库外缓存>` | `web-demo-independent.mjs` @ `60373ce9…` | **`3/3 passed` EXIT=0**（含 `env.cross-bootstrap-consistent`：真调交付脚本的两个 print 开关，两套自举版本/库路径一致） | `tmp/verifier-evidence/t7b-indep-env.log` |
| 3 | §4 #2 的 `35/35`（媒体/ICE 独立判定） | 其绑定文件（`cdp.mjs` / `verify-two-page.mjs` / `chrome.mjs` / `web-demo-independent.mjs`）**自首测起 sha 未变** | 结论**对本 revision 继续有效**，不重跑 | `tmp/verifier-evidence/t7-indep-harness.log` |
| 4 | `CHROME-ORPHANS` 冻结后核验 | 本次运行之后现场 | `chrome-headless-shell` 进程数 **0**；8081/8092/8093 监听数 **0** | `tmp/verifier-evidence/t7b-delta.log`、`t7b-delta2.log` |

### 14.5 门禁复跑（全 EXIT=0）与 CMD 分类守卫

| 命令 | 01:46–01:47 复测 | EXIT |
|---|---|---|
| `bash scripts/doc-verify.sh` | `PASS (4803 checks, 4 warnings)` | **0** |
| `bash scripts/i18n-audit.sh` | `PASS (133 checks, 0 warnings)` | **0** |
| `--only reports/70-browser-call-demo-requirements.md` | `PASS (258 checks, 0 warnings)` | **0** |
| `--only reports/71-browser-call-demo.md` | `PASS (116 checks, 0 warnings)` | **0** |
| `--only reports/72-browser-demo-verification.md` | `PASS (181 checks, 0 warnings)` | **0** |
| `--only reports/73-browser-demo-review.md` | `PASS (56 checks, 0 warnings)`（本次追加前） | **0** |

* 原始输出：`tmp/verifier-evidence/t7b-gates.log`、`t7b-only.log`。
* **CMD 分类守卫复现**（§12 的片段逐字重跑，`17:48:40Z`）：

```
live case labels (scripts/*.sh scripts/*.py)      = 18
§5 non-in-repo tokens                             = 24（其中以 -- 开头 13）
intersection                                      = 0     ← 无放宽、无劫持
```
* ⇒ 新脚本**没有任何** case 标签与 `doc/design/_generated/host-commands.md` §5 令牌同名；且 §5 表本身 `git status` 无 `M`，令牌集逐字未变。
* **一路近似名登记（非缺陷，供长期约束）**：`--log-file` 是交付脚本的真实开关且**不在 §5**；§5 中登记为 report 类的同名用途日志开关在交付脚本里**没有** case 标签。⇒ 建议 captain 的长期约束写成「**§5 已登记的 host-only 开关，不得在交付脚本中出现同名 case 标签**」；`--log-file` 不违反（异名），但若将来 §5 收录该名就会立刻违反。

### 14.6 F-T7-3 状态更新：medium → **low（当前 PASS，属易失依赖）**

* **01:46:40 复测**：`--only reports/70-browser-call-demo-requirements.md` → **`PASS (258 checks, 0 warnings)` EXIT=0**；首测的 `FAIL (1 failures)` 不再复现。
* **成因**：`reports/70` 引用的那个**仓库外工作区素材路径**已于 **01:39:02** 被重建（3 643 B，sha256 前缀 `f9f5769c…`）。现场读数见 `tmp/verifier-evidence/t7b-recipe.log`。
* **残留风险（必须登记）**：该路径**不在交付面**（在仓库之外的工作区 `tmp/` 下），而 `reports/70` 已冻结、不可改 ⇒ 只要它再被清理，`--only reports/70` 会**再次 EXIT=1**，而**全量** `doc-verify` 仍会 PASS（首测已证门禁的模式差异）。
* **status**：本项**不阻塞本轮**；requiredFix 降为**运营前提**，captain 二选一 —— **(a)** 把该素材路径材料化为运行前提（后续 `--clean` 不再删除它）；**(b)** 授权对 `reports/70` 该行做有界修正。
* **另注（标签错误，登记不自改）**：`reports/70` 的 A-3 围栏把**容器路径**标成 `HOST:`（容器 `/data/dsh/home/workspace/code/webrtc-demo` 与宿主机 `/opt/dsh-workspaces/code/webrtc-demo` 并非同一路径）。因 `reports/70` 冻结，本轮**只登记不修改**。

### 14.7 越界 / 白名单复测（与首测一致）

* `git status --porcelain` 共 **9** 条，全部在白名单内；**无 `?? tmp/`**（`REPO-TMP` 自检继续通过）。
* `reports/70` = `967cb530…`、`reports/71` = `256ad4c5…`、`reports/72` = `598f9add…`，与 T7 开始时**逐字未变**。
* `app/**`、`signaling/**`、`doc/design/**`、`reports/54–69` 均未出现在变更面 ⇒ **零越界**（原始输出：`tmp/verifier-evidence/t7b-snapshot.log`）。

### 14.8 本轮复跑结论

**verdict 不变：`needs_revision`。** 唯一 blocker 仍是 **F-T7-1**（12 处登记值被取代，其中 1 行的盘面值在本报告首测后**又**被取代）。修复路径不变：由 **t13** 在 `reports/71` 追加 §12 再冻结、同步 §3 的 6 行、并在 §11.3 标注「23:46:19 时点快照，已被 §12 取代」；**冻结基准取 §14.2 表**。F-T7-2 / F-T7-3 降级为 **low**（已落地 / 易失依赖），F-T7-4 仍交 t13 改写措辞，F-T7-5 并入 F-T7-1。

---

## 15. review r2（T14）：对 t13 后的 `reports/71` 的独立复核

> **revision 链**：`256ad4c5…`（437 行，23:48:05，t12 终态）→ **`594f00f85b07de9b26b56277f68b69f1e4e3557f1ab6ba23e6d55e648bf9eda2`（512 行 / 48 128 B / 2026-09-24 01:53:21，t13 终态）**。本节全部读数绑定后者；若其后任一件再变，按 §14/§15.5 的纪律现场重取。
> **本节产出为新章节、不回改 §1–§14**（§14 的 verdict 是 T7 时点判定，保留原样）。

### 15.1 验收① 登记值 vs 盘面（机器逐行比对，判据同 T7）

| 区域 | 登记行数 | MATCH | 判定 |
|---|---|---|---|
| `reports/71` §3 文件清单登记表 | 20 | **20** | **通过**（另含 行/字节 两列逐行比对：0 处不符） |
| `reports/71` §12.1 再冻结表（权威） | 6 | **6** | **通过** |
| `reports/71` §11.3 快照表（历史） | 6 | 0（历史值） | **通过**——该表**已就地标注**「本表为 2026-09-23 23:46:19 时点快照，已被 §12 取代」（`reports/71` §11.3 顶部 366 行），符合 t13 契约；其 6 行**不删不改**以留存时间差证据 |

* 方法：从登记行提取 64 位十六进制 + 反引号路径 → 现场 `sha256sum` / `wc -l` / `stat -c %s` 三方比对。原始输出：`tmp/verifier-evidence/t14-registry-compare.log`。
* 六件交付面在 t13 期间**未再变动**：`scripts/serve-web-demo.sh` `63c0d22a…`/441、`scripts/web-demo-verify.sh` `139ae5e7…`/258、`web/README.md` `59d419ff…`/355、`web/tests/verify-two-page.mjs` `c1d6a426…`/645、`web/tests/lib/cdp.mjs` `2c896f1a…`/189、`web/tests/lib/chrome.mjs` `9d2d9ebf…`/160。
* **F-T7-1 的 requiredFix 三项全部落地**：§12 追加 ✓、§3 六行同步 ✓（现 20/20 一致）、§11.3 时点标注 ✓。**F-T7-4 亦落地**（§12.2 已把 `PROBE-RACE` 改写为「t11 已结构性修复，保留回归断言」，并附绑定 sha 与连绿口径）。

### 15.2 验收② 门禁复跑（原始输出可查）

| 命令 | 结果 | EXIT |
|---|---|---|
| `bash scripts/doc-verify.sh --only reports/71-browser-call-demo.md` | `PASS (140 checks, 0 warnings)` | **0** |
| `bash scripts/doc-verify.sh` | `PASS (4803 checks, 4 warnings)` | **0** |
| `bash scripts/i18n-audit.sh` | `PASS (133 checks, 0 warnings)` | **0** |
| `--only reports/70-browser-call-demo-requirements.md` | `PASS (258 checks, 0 warnings)` | **0** |
| `--only reports/73-browser-demo-review.md` | `PASS (93 checks, 0 warnings)`（本节追加前） | **0** |

原始输出：`tmp/verifier-evidence/t14-gates.log`。**但必须附上 F-T14-1**：同一门禁在**同一盘面**上会间歇性给出**假红**（见 §15.3），因此单次红灯**不构成**结论，须按「重跑确认」纪律处置。

### 15.3 **F-T14-1：门禁 `doc-verify.sh` 自身可产生间歇假红（根因已证，pre-existing）**

**现象**：同一盘面、同一命令，间歇 `FAIL (1 failures, 0 warnings, N checks) EXIT=1`，报文形如

```
doc/design/SPEC.md:239 → `--rehearsal` is host/workspace-only and is cited without in-repository evidence → …(SPEC §7.3)  [token: doc/design/_generated/host-commands.md §5]
reports/72-browser-demo-verification.md:260 → `--version` is host/workspace-only and is cited without in-repository evidence → …
```

**实测频率**（全部为同盘面重复运行）：

| 命令 | 失败次数 | 备注 |
|---|---|---|
| `--only reports/72-browser-demo-verification.md` | **2/30**（另有 1/10 与 10 连测中的 1 次） | 报文行 260 的 `--version` |
| `bash scripts/doc-verify.sh`（全量） | **2/15** | 两次都命中的是**未改动的** `doc/design/SPEC.md:239`（一次报 `--rehearsal`、一次报 `--log`；token 分类见 `doc/design/_generated/host-commands.md` §5） |
| 微观基准：`sed -n 1,260p … \| grep -qE …` ×200 | **8/200（4%）非 0**；**去掉 `pipefail` 后 0/200** | 直接复现 |

原始输出：`tmp/verifier-evidence/t14-r72-stability.log`、`t14-fullrun-flake.log`、`t14-rootcause-proof.log`、`t14-flake-trace.log`、`t14-pipefail-rootcause.log`。

**根因（两条叠加，均为 `scripts/doc-verify.sh` 既有实现，非本轮交付物）**：

1. **mawk 不支持区间表达式** ⇒ 「本节起点」永远塌缩到第 1 行：
   ```
   $ awk -W version            → mawk 1.3.4 20200120
   $ awk 'NR<=260 && /^#{1,6} /{c++} END{print c+0}' reports/72-…md   → 0      # 区间写法零命中
   $ awk 'NR<=260 && /^#+ /{c++} END{print c+0}'     reports/72-…md   → 27     # 等价写法命中
   ```
   即 §7.3 的「同段落内举证」在 mawk 环境下退化为「自第 1 行到引用行的全文含 `reports/…` 即可」。
2. **`set -uo pipefail`（`scripts/doc-verify.sh:89`）+ `sed -n "$sec_start,$line"p | grep -q`（`:867`）**：`grep -q` 命中即退出，`sed` 仍在写 → SIGPIPE 141 → `pipefail` 把管道判为非 0 → `if` 条件当作「无证据」→ **假红**。命中点越靠前（如 `reports/72` 第 1 行即含 `reports/…`）越容易触发；系统负载会放大窗口（全量 15 连测中的两次假红即发生在并发运行期）。

**影响与处置**：
* **本报告自身也命中过该 flake（自证）**：`--only reports/73-browser-demo-review.md` 10 连测中出现 **1 次**假红，报文指向本节表格那行（`doc/design/SPEC.md:239` 的 report 类开关引用）——与 `doc/design/SPEC.md:239` 的 2/15 同根因。处置：凡引用 §5 中 **report 类** token 的行，**同行**附举证指针（本节相关行均已补 `doc/design/_generated/host-commands.md` §5 指针），使其不再依赖「自第 1 行起全文扫描」这条退化路径；加固后 30 连测见 `tmp/verifier-evidence/t14-r73-gate3.log`。
* 影响面**不限本轮交付物**：全量的两次假红都落在**未改动**的 `doc/design/SPEC.md`（该文件 `git status` 无变更）⇒ 属 **pre-existing 门禁缺陷**，`reports/54–69` 时代的门禁同样受影响。
* **本报告所有「门禁 EXIT=0」的证据仍然成立**（都是**真绿**运行的原始输出），但**反向不成立**：单次红灯必须重跑确认后才可判为缺陷。§14.5 与 §15.2 的绿值即按此口径给出（`--only reports/72` 在 01:46:41 曾绿、在 17:55:40 曾红，17:56 起 10 连绿 + 30 连绿中仅 2 红 ⇒ **判定为 flake，非内容回归**）。
* **requiredFix（需 captain 裁定，属既有门禁脚本、当前不在交付白名单内）**：(a) 采纳「红灯重跑确认」纪律并登记为已知门禁缺陷（本节即登记）；(b) 若允许越界修复，最小改法有二——把 `awk` 区间改为 `#+` 形状的等价写法（恢复真正的段落范围），并把 `sed … | grep -q` 改为先落变量再匹配（`out=$(sed -n …); case "$out" in *reports/*|*host-commands.md*) …`）以消除 SIGPIPE；二者都在 `scripts/doc-verify.sh`，需 `reports/54–69` 之外的授权。

### 15.4 验收③ 可复现抽检（5 项，含 1 项媒体/ICE）

| # | 结论 | 命令 | 结果 |
|---|---|---|---|
| 1 | **媒体/ICE**（`reports/71` §6 引 T5 的 V3） | `bash scripts/web-demo-verify.sh --cache <仓库外缓存> --mode probe` ×3 | **3/3 `19/19 passed`、`result: PASS`、rc=0** |
| 2 | 信令 e2e（对 live 服务） | `node web/tests/signaling.e2e.mjs` | **`19/19 passed`、`result: PASS (exit 0)`**（含 `signaling.all-14-types-observed` 14/14） |
| 3 | 信令纯逻辑单测 | `node web/tests/signaling.test.mjs` | **`=== PASS 258, FAIL 0 ===`** rc=0 |
| 4 | 两套自举一致性 | `node web/tests/web-demo-independent.mjs --phase env --cache <仓库外缓存>` | **`3/3 passed` rc=0**（真调交付脚本的 print 开关） |
| 5 | 残留/临时面 | `df -h /tmp` 与进程清点 | `/tmp` 107 M / 42%（前后逐字相同）；运行后无 chrome 残留 |

原始输出：`tmp/verifier-evidence/t14-repro.log`。被测物 sha：`scripts/web-demo-verify.sh` @ `139ae5e7…`、`web/tests/signaling.e2e.mjs` @ `b50f66e9…`、`web/tests/lib/cdp.mjs` @ `2c896f1a…`。

### 15.5 验收④ 越界/白名单（与 T7 一致，零违规）

* `git status --porcelain` = **9** 条，全部在白名单内；`?? tmp/` = **0**。
* `git status --porcelain -- doc/design` = **0**、`-- app signaling` = **0**；`reports/54–69` 零改动。
* `reports/70` = `967cb530…`、`reports/72` = `598f9add…` 与其冻结值**逐字一致**（t13 未触碰）。
* **§1–§10 未被回改的旁证**：`reports/71` §11 标题仍在**第 312 行**（与 t12 时点相同），§12 起于**第 443 行**；t13 的三处动作（§3 六行数值、§11.3 顶部标注、§12 追加）都在 §11.3 之后与 §3 表格内，未见正文改写迹象。
* CMD 分类守卫复跑：全仓 case 标签 **18** × `host-commands.md` §5 非 in-repo 令牌 **24** ⇒ **交集 0**（`doc/design` 无变更也证明 §5 令牌集逐字未变）。原始输出：`tmp/verifier-evidence/t14-scope.log`。

### 15.6 验收⑤ 如实性

* `reports/71` §8 的 15 项未做/未证登记**原样保留**；§12.4 把「`--only reports/70` 的易失依赖」与「`reports/70` A-3 围栏 `HOST:` 误标」**只登记不自改**，与我的 F-T7-3/§15.7 一致。
* §12.2 对连绿证据**明确标注来源**（T7 抽检 3/3 属复核证据、5 连绿属交付证据），未把成员自报写成独立判定 —— 与我的口径一致。
* 本报告 §14.3 原有一处保留（「上一版字节未留存 ⇒ 无法字节级 diff，故不称『纯注释改动』」）**现已可关闭**：ops 提出「按旧文案还原后重算 sha」的方法，我**独立复现成功**（见 §15.7），因此 `scripts/web-demo-verify.sh` 的 `977de48b… → 139ae5e7…` 增量可判定为**恰好 `:31`–`:32` 两行 usage 注释、可执行语句零改动**。

### 15.7 附：ops 字节级增量证明的独立复现（升级 §14.3 的保留）

```
$ # 把现行 :31-32 两行替换为下列旧单行（注意 DIR 后 7 个空格、default 后无冒号），重算 sha
#   --cache DIR       cache directory (default ${WEB_DEMO_CACHE:-${TMPDIR:-/tmp}/web-demo-chrome-cache})
$ awk 'NR==31{print "<上面这一行逐字>"; next} NR==32{next} {print}' scripts/web-demo-verify.sh | sha256sum
977de48b6d04fc3dd628a8f38b7de0dddf8a59eb14238255df49e5ffa107585b   ← 与 T7 在 01:41 观测到的时点值完全一致
```
变体对照（证伪了「随便写一行都能命中」）：`DIR` 后两空格 → `bf3ad929…`、一空格 → `408d94e1…`、**7 空格 → 命中 `977de48b…`**。原始输出：`tmp/verifier-evidence/t7e-delta-proof.log`。

### 15.8 review r2 结论

**verdict = `pass`**（t13 对 F-T7-1 / F-T7-4 / F-T7-5 的 requiredFix 已全部落地并被我独立复跑确认；验收①–⑤ 全部通过）。

findings（均**不**阻塞 pass）：

| id | 严重度 | 问题 | requiredFix |
|---|---|---|---|
| **F-T14-1** | **medium** | `scripts/doc-verify.sh` 自身可产生间歇假红（mawk 区间表达式失效 + `pipefail` 与 `sed \| grep -q` 的 SIGPIPE 竞态）：`--only reports/72` 2/30、全量 2/15（后者命中**未改动**的 `doc/design/SPEC.md:239`） | captain 二选一：(a) 采纳「红灯重跑确认」纪律并登记为已知门禁缺陷；(b) 授权越界修复，最小改法见 §15.3 |
| **F-T14-2** | low | `reports/71` §11.3 顶部已标注「已被 §12 取代」，但其内部第二段仍写「§3 中下列六行的值以本表为准」 | 属「原样保留历史证据」的既有权衡；下次再冻结时加一行指针即可，**本轮不需动作** |
| F-T14-3 | low | `reports/70` 的易失依赖（F-T7-3 承接）仍待 captain (a)/(b) 裁定 | 同 F-T7-3，不阻塞 |
