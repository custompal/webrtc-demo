#!/usr/bin/env node
// =============================================================================
// signaling.e2e.mjs — T5 独立信令层端到端验证（对 **live** 服务，不用 mock）
//
// 运行（容器内 node v24，无 npm 依赖，只用内置 WebSocket）：
//   node web/tests/signaling.e2e.mjs
//   node web/tests/signaling.e2e.mjs --signaling ws://172.18.0.1:8443/ws
//   SIGNALING_URL=ws://... node web/tests/signaling.e2e.mjs
//
// 退出码：0 = 全部断言通过 · 1 = 有断言失败 · 2 = 用法/环境错误
//
// 覆盖（doc/09 §2.1 的 14 类消息）：create/created、join/joined、
// peerJoined、offer、answer、ice、natType、leave、peerLeft、ping/pong、error。
// 断言要点：
//   * created/joined 的 stunUrl/turnUrl/turnUsername/turnCredential 非空，
//     turnUrl 为 UDP 传输；joined 另有 peerId；第三方 join 时房主收到 peerJoined
//   * offer/answer/ice/natType **字节级原样转发**（含非规范 JSON 空白/转义，
//     用严格 === 比较，证明服务端不改写）
//   * ping → pong 且带服务端时间
//   * leave → 对端收到 peerLeft；双方 leave 后房间销毁（再 join 得 ROOM_NOT_FOUND）
//   * 错误路径：非法房间号 → INVALID_MESSAGE；不存在的房间 → ROOM_NOT_FOUND；
//     未入房发 offer → NOT_IN_ROOM；满房 → ROOM_FULL；服务端专有类型/未知类型/
//     非 JSON → INVALID_MESSAGE
//
// 本脚本为 T5 独立实现，不引用任何成员自述；原始输出即证据。
// =============================================================================

const DEFAULT_SIGNALING = "ws://47.238.144.66:8443/ws";
const ROOM_ID_CHARSET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const ROOM_ID_RE = new RegExp(`^[${ROOM_ID_CHARSET}]{6}$`);
const ALL_TYPES = [
  "create", "created", "join", "joined", "peerJoined", "peerLeft",
  "offer", "answer", "ice", "natType", "leave", "ping", "pong", "error",
];

function parseArgs(argv) {
  const out = { signaling: process.env.SIGNALING_URL || DEFAULT_SIGNALING, timeoutMs: 8000, verbose: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--signaling") out.signaling = argv[++i];
    else if (a.startsWith("--signaling=")) out.signaling = a.slice("--signaling=".length);
    else if (a === "--timeout-ms") out.timeoutMs = Number(argv[++i]);
    else if (a === "--verbose") out.verbose = true;
    else if (a === "--help" || a === "-h") { console.log("usage: node web/tests/signaling.e2e.mjs [--signaling URL] [--timeout-ms N] [--verbose]"); process.exit(0); }
    else { console.error(`signaling.e2e: unknown argument: ${a}`); process.exit(2); }
  }
  return out;
}

const args = parseArgs(process.argv.slice(2));
if (!/^wss?:\/\//.test(args.signaling)) {
  console.error(`signaling.e2e: --signaling must be a ws:// or wss:// URL, got ${args.signaling}`);
  process.exit(2);
}

const results = [];
let passCount = 0;
let failCount = 0;
const observedTypes = new Set();

function record(ok, id, detail, rawEvidence) {
  if (ok) { passCount++; results.push({ ok, id, detail, rawEvidence }); }
  else { failCount++; results.push({ ok, id, detail, rawEvidence }); }
  console.log(`${ok ? "PASS" : "FAIL"} ${id}${detail ? " :: " + detail : ""}`);
  if (!ok && rawEvidence) console.log(`       evidence: ${rawEvidence}`);
}

async function check(id, fn) {
  try {
    const r = await fn();
    record(r !== false, id, typeof r === "string" ? r : (r && r.detail) || "");
  } catch (e) {
    record(false, id, e && e.message ? e.message : String(e), e && e.rawEvidence);
  }
}

function assert(cond, msg, rawEvidence) {
  if (!cond) {
    const err = new Error(msg);
    err.rawEvidence = rawEvidence;
    throw err;
  }
}

class Client {
  constructor(label) {
    this.label = label;
    this.frames = [];
    this.waiters = [];
    // cursor：只允许匹配「尚未被消费」的帧，避免后续断言误命中早先的错误帧
    this.cursor = 0;
    this.closed = false;
    this.ws = new WebSocket(args.signaling);
    this.ready = new Promise((resolve, reject) => {
      const to = setTimeout(() => reject(new Error(`${label}: open timeout`)), args.timeoutMs);
      this.ws.onopen = () => { clearTimeout(to); resolve(); };
      this.ws.onerror = (ev) => { clearTimeout(to); reject(new Error(`${label}: ws error ${ev && ev.message ? ev.message : ""}`)); };
    });
    this.ws.onmessage = (ev) => {
      const raw = typeof ev.data === "string" ? ev.data : String(ev.data);
      let json = null;
      try { json = JSON.parse(raw); } catch { /* 非 JSON 也留痕 */ }
      if (json && json.type) observedTypes.add(json.type);
      const frame = { dir: "in", raw, json, at: Date.now() };
      this.frames.push(frame);
      if (args.verbose) console.log(`  [${this.label}] <- ${raw.slice(0, 200)}`);
      for (const w of [...this.waiters]) {
        if (w.pred(frame)) {
          this.waiters.splice(this.waiters.indexOf(w), 1);
          clearTimeout(w.timer);
          const idx = this.frames.indexOf(frame);
          if (idx + 1 > this.cursor) this.cursor = idx + 1;
          w.resolve(frame);
        }
      }
    };
    this.ws.onclose = () => { this.closed = true; };
  }

  sendRaw(text) {
    try {
      const j = JSON.parse(text);
      if (j && j.type) observedTypes.add(j.type);
    } catch { /* 非 JSON 发送帧不计类型 */ }
    if (args.verbose) console.log(`  [${this.label}] -> ${text.slice(0, 200)}`);
    this.ws.send(text);
  }

  send(obj) { this.sendRaw(JSON.stringify(obj)); }

  /** 等待第一条**尚未被消费**且满足 pred 的入帧；超时抛错（附已收帧摘要作为证据） */
  wait(pred, label, timeoutMs = args.timeoutMs) {
    for (let i = this.cursor; i < this.frames.length; i++) {
      if (pred(this.frames[i])) {
        const f = this.frames[i];
        this.cursor = i + 1;
        return Promise.resolve(f);
      }
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== waiter);
        const seen = this.frames.slice(this.cursor).map((f) => `${f.json && f.json.type ? f.json.type : "?"}`).join(",") || "(none)";
        reject(new Error(`${this.label}: timeout waiting ${label} (unconsumed: ${seen})`));
      }, timeoutMs);
      const waiter = { pred, resolve, timer };
      this.waiters.push(waiter);
    });
  }

  waitType(type, timeoutMs) {
    return this.wait(type === "*" ? () => true : ((f) => f.json && f.json.type === type), `type=${type}`, timeoutMs);
  }

  close() { try { this.ws.close(); } catch { /* ignore */ } }
}

function randomValidRoomId() {
  let s = "";
  for (let i = 0; i < 6; i++) s += ROOM_ID_CHARSET[Math.floor(Math.random() * ROOM_ID_CHARSET.length)];
  return s;
}

async function main() {
  console.log(`# signaling e2e (T5 independent) against ${args.signaling}`);
  console.log(`# node ${process.version} · started ${new Date().toISOString()}`);
  console.log("");

  const host = new Client("host");
  const guest = new Client("guest");
  const third = new Client("third");
  const lone = new Client("lone");
  await Promise.all([host.ready, guest.ready, third.ready, lone.ready]);

  let roomId = null;
  let hostPeerId = null;
  let guestPeerId = null;
  const nonCanonicalOffer = `{"type" : "offer",  "sdp" : "v=0\\r\\no=- 1 1 IN IP4 0.0.0.0\\r\\ns=-\\r\\nt=0 0\\r\\na=ice-ufrag:AAA/BBB==\\r\\n"}`;
  const nonCanonicalAnswer = `{ "type" : "answer" , "sdp" : "v=0\\r\\no=- 2 2 IN IP4 0.0.0.0\\r\\ns=-\\r\\nt=0 0\\r\\na=ice-pwd:pwd\\"quoted\\"\\r\\n" }`;
  const nonCanonicalIce = `{"type" : "ice", "candidate" : "candidate:1 1 udp 2113937151 10.0.0.1 5000 typ host", "sdpMid" : "0", "sdpMLineIndex" : 0}`;
  const nonCanonicalNat = `{"type" : "natType" ,  "natType" : "Symmetric" }`;

  // ---- 1) ping → pong（无房间，14 类中的 ping/pong） ----
  await check("signaling.ping-pong", async () => {
    const t0 = Date.now();
    lone.send({ type: "ping", timestamp: t0 });
    const f = await lone.waitType("pong");
    assert(typeof f.json.timestamp === "number" && f.json.timestamp > 0, `pong.timestamp 非法: ${JSON.stringify(f.json)}`);
    const rtt = Date.now() - t0;
    return `rtt=${rtt}ms serverTs=${f.json.timestamp} clockDelta=${f.json.timestamp - t0}ms`;
  });

  // ---- 2) create → created ----
  await check("signaling.create-created", async () => {
    host.send({ type: "create" });
    const f = await host.waitType("created");
    const j = f.json;
    assert(ROOM_ID_RE.test(j.roomId), `roomId 格式不符 ^[A-Z2-9]{6}$: ${JSON.stringify(j.roomId)}`);
    for (const k of ["stunUrl", "turnUrl", "turnUsername", "turnCredential"]) {
      assert(typeof j[k] === "string" && j[k].trim() !== "", `created.${k} 缺失/空: ${JSON.stringify(j)}`);
    }
    assert(j.turnUrl.includes("transport=udp"), `turnUrl 非 UDP 传输: ${j.turnUrl}`);
    roomId = j.roomId;
    return `roomId=${j.roomId} stunUrl=${j.stunUrl} turnUrl=${j.turnUrl} turnUser=${j.turnUsername} credLen=${j.turnCredential.length}`;
  });

  // ---- 3) NOT_IN_ROOM（未入房发 offer；且 payload 合法，确保命中的是房间检查） ----
  await check("signaling.error-not-in-room", async () => {
    lone.sendRaw(nonCanonicalOffer);
    const f = await lone.waitType("error");
    assert(f.json.code === "NOT_IN_ROOM", `期望 NOT_IN_ROOM，实得 ${f.json.code}`);
    return `code=${f.json.code} message=${JSON.stringify(f.json.message)}`;
  });

  // ---- 4) 非法房间号 → INVALID_MESSAGE ----
  await check("signaling.error-invalid-roomid", async () => {
    lone.send({ type: "join", roomId: "ABCDE" });
    const f1 = await lone.waitType("error");
    assert(f1.json.code === "INVALID_MESSAGE", `短 roomId 期望 INVALID_MESSAGE，实得 ${f1.json.code}`);
    lone.send({ type: "join", roomId: "OOOOOO" });   // O/0/I/1/L 不在字符集
    const f2 = await lone.wait((fr) => fr.json && fr.json.type === "error" && fr !== f1, "error#2");
    assert(f2.json.code === "INVALID_MESSAGE", `含 O 的 roomId 期望 INVALID_MESSAGE，实得 ${f2.json.code}`);
    return `short->${f1.json.code} charset->${f2.json.code}`;
  });

  // ---- 5) 合法格式但不存在的房间 → ROOM_NOT_FOUND ----
  await check("signaling.error-room-not-found", async () => {
    const ghost = randomValidRoomId();
    lone.send({ type: "join", roomId: ghost });
    const f = await lone.waitType("error");
    assert(f.json.code === "ROOM_NOT_FOUND", `期望 ROOM_NOT_FOUND（${ghost}），实得 ${f.json.code}`);
    return `roomId=${ghost} code=${f.json.code}`;
  });

  // ---- 6) join → joined（含 peerId 与 ICE 字段） ----
  await check("signaling.join-joined", async () => {
    guest.send({ type: "join", roomId });
    const f = await guest.waitType("joined");
    const j = f.json;
    assert(j.roomId === roomId, `joined.roomId=${j.roomId} != created.roomId=${roomId}`);
    assert(typeof j.peerId === "string" && j.peerId.length > 0, `joined.peerId 缺失: ${JSON.stringify(j)}`);
    for (const k of ["stunUrl", "turnUrl", "turnUsername", "turnCredential"]) {
      assert(typeof j[k] === "string" && j[k].trim() !== "", `joined.${k} 缺失/空`);
    }
    guestPeerId = j.peerId;
    return `roomId=${j.roomId} peerId=${j.peerId} turnUrl=${j.turnUrl}`;
  });

  // ---- 7) 房主收到 peerJoined，且 peerId 与 guest 的 joined.peerId 一致 ----
  await check("signaling.peer-joined", async () => {
    const f = await host.waitType("peerJoined");
    assert(f.json.peerId === guestPeerId, `peerJoined.peerId=${f.json.peerId} != guest.joined.peerId=${guestPeerId}`);
    // 房主身份由 create 决定；host 的 peerId 需从后续 peerLeft/服务端分配推断，这里仅记录
    return `host saw peerId=${f.json.peerId} (== guest joined.peerId)`;
  });

  // ---- 8) offer 字节级原样转发 ----
  await check("signaling.offer-relayed-verbatim", async () => {
    host.sendRaw(nonCanonicalOffer);
    const f = await guest.waitType("offer");
    assert(f.raw === nonCanonicalOffer, `转发被改写:\n  sent=${JSON.stringify(nonCanonicalOffer)}\n  recv=${JSON.stringify(f.raw)}`);
    return `bytes=${Buffer.byteLength(f.raw)} verbatim=true`;
  });

  // ---- 9) answer 字节级原样转发 ----
  await check("signaling.answer-relayed-verbatim", async () => {
    guest.sendRaw(nonCanonicalAnswer);
    const f = await host.waitType("answer");
    assert(f.raw === nonCanonicalAnswer, `转发被改写:\n  sent=${JSON.stringify(nonCanonicalAnswer)}\n  recv=${JSON.stringify(f.raw)}`);
    return `bytes=${Buffer.byteLength(f.raw)} verbatim=true`;
  });

  // ---- 10) ice 双向字节级原样转发 ----
  await check("signaling.ice-relayed-verbatim", async () => {
    host.sendRaw(nonCanonicalIce);
    const f1 = await guest.wait((fr) => fr.json && fr.json.type === "ice", "ice@guest");
    assert(f1.raw === nonCanonicalIce, `host->guest ice 被改写: ${JSON.stringify(f1.raw)}`);
    guest.sendRaw(nonCanonicalIce);
    const f2 = await host.wait((fr) => fr.json && fr.json.type === "ice", "ice@host");
    assert(f2.raw === nonCanonicalIce, `guest->host ice 被改写: ${JSON.stringify(f2.raw)}`);
    return `both directions verbatim (${Buffer.byteLength(nonCanonicalIce)} B)`;
  });

  // ---- 11) natType 原样转发（14 类中的最后一类） ----
  await check("signaling.nattype-relayed-verbatim", async () => {
    host.sendRaw(nonCanonicalNat);
    const f = await guest.waitType("natType");
    assert(f.raw === nonCanonicalNat, `natType 被改写: ${JSON.stringify(f.raw)}`);
    return `verbatim (${Buffer.byteLength(f.raw)} B)`;
  });

  // ---- 12) 满房 → ROOM_FULL ----
  await check("signaling.error-room-full", async () => {
    third.send({ type: "join", roomId });
    const f = await third.waitType("error");
    assert(f.json.code === "ROOM_FULL", `期望 ROOM_FULL，实得 ${f.json.code} (message=${f.json.message})`);
    return `code=${f.json.code} message=${JSON.stringify(f.json.message)}`;
  });

  // ---- 13) leave → 对端收到 peerLeft ----
  await check("signaling.leave-peerleft", async () => {
    guest.send({ type: "leave" });
    const f = await host.waitType("peerLeft");
    assert(f.json.peerId === guestPeerId, `peerLeft.peerId=${f.json.peerId} != ${guestPeerId}`);
    return `peerId=${f.json.peerId}`;
  });

  // ---- 14) 双方离开后房间销毁：再 join → ROOM_NOT_FOUND ----
  await check("signaling.room-destroyed-after-leave", async () => {
    host.send({ type: "leave" });
    await new Promise((r) => setTimeout(r, 300));
    const probe = new Client("probe");
    try {
      await probe.ready;
      probe.send({ type: "join", roomId });
      const f = await probe.waitType("error");
      assert(f.json.code === "ROOM_NOT_FOUND", `房间未销毁：join 返回 ${f.json.code} (${f.json.message})`);
      return `code=${f.json.code}（房间 ${roomId} 已销毁）`;
    } finally { probe.close(); }
  });

  // ---- 15) 服务端专有类型 → INVALID_MESSAGE ----
  await check("signaling.error-server-only-type", async () => {
    lone.send({ type: "pong", timestamp: 1 });
    const f = await lone.wait((fr) => fr.json && fr.json.type === "error", "server-only error");
    assert(f.json.code === "INVALID_MESSAGE", `期望 INVALID_MESSAGE，实得 ${f.json.code}`);
    return `code=${f.json.code} message=${JSON.stringify(f.json.message)}`;
  });

  // ---- 16) 未知类型 → INVALID_MESSAGE ----
  await check("signaling.error-unknown-type", async () => {
    lone.send({ type: "definitely-not-a-type" });
    const f = await lone.wait((fr) => fr.json && fr.json.type === "error", "unknown-type error");
    assert(f.json.code === "INVALID_MESSAGE", `期望 INVALID_MESSAGE，实得 ${f.json.code}`);
    return `code=${f.json.code} message=${JSON.stringify(f.json.message)}`;
  });

  // ---- 17) 非 JSON 帧 → INVALID_MESSAGE ----
  await check("signaling.error-malformed-json", async () => {
    lone.sendRaw("this is not json");
    const f = await lone.wait((fr) => fr.json && fr.json.type === "error", "malformed error");
    assert(f.json.code === "INVALID_MESSAGE", `期望 INVALID_MESSAGE，实得 ${f.json.code}`);
    return `code=${f.json.code} message=${JSON.stringify(f.json.message)}`;
  });

  // ---- 18) 14 类消息全部出现 ----
  await check("signaling.all-14-types-observed", async () => {
    const missing = ALL_TYPES.filter((t) => !observedTypes.has(t));
    assert(missing.length === 0, `未观测到的类型: ${missing.join(",")}`);
    return `observed ${ALL_TYPES.length - missing.length}/14: ${ALL_TYPES.join(",")}`;
  });

  // ---- 19) join 房间号大小写/空白归一化（服务端 NormalizeRoomID） ----
  await check("signaling.roomid-normalized", async () => {
    const a = new Client("norm-a");
    const b = new Client("norm-b");
    try {
      await a.ready; await b.ready;
      a.send({ type: "create" });
      const c = await a.waitType("created");
      const rid = c.json.roomId;
      b.send({ type: "join", roomId: `  ${rid.toLowerCase()}  ` });
      const j = await b.waitType("joined");
      assert(j.json.roomId === rid, `归一化失败: joined.roomId=${j.json.roomId} != ${rid}`);
      b.send({ type: "leave" }); a.send({ type: "leave" });
      return `created=${rid} join("  ${rid.toLowerCase()}  ") -> roomId=${j.json.roomId}`;
    } finally { a.close(); b.close(); }
  });

  for (const c of [host, guest, third, lone]) c.close();
  await new Promise((r) => setTimeout(r, 250));

  console.log("");
  console.log(`summary: ${passCount}/${passCount + failCount} passed   result: ${failCount === 0 ? "PASS" : "FAIL"} (exit ${failCount === 0 ? 0 : 1})`);
  process.exit(failCount === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(`signaling.e2e: fatal: ${e && e.stack ? e.stack : e}`);
  process.exit(2);
});
