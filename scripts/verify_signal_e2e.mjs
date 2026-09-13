#!/usr/bin/env node
// verify_signal_e2e.mjs — 信令服务端到端联调验证（t12 产物）
//
// 用法（容器内，无需 npm 依赖，使用 Node 22+ 内置 global WebSocket）：
//   node scripts/verify_signal_e2e.mjs [wsUrl]
//   node scripts/verify_signal_e2e.mjs ws://47.238.144.66:8443/ws
//   node scripts/verify_signal_e2e.mjs ws://127.0.0.1:8443/ws ./logs/e2e.log
//
// 覆盖（doc/09 协议 + doc/13 阶段 2.1 验收）：
//   两个 WebSocket 连接 → create → created（roomId 与 STUN/TURN 配置）
//   → 第二个 join → joined → 第一个收到 peerJoined
//   → offer / answer / ice（双向）/ natType 原样转发（逐字节比对）
//   → ping/pong 心跳 → leave → peerLeft → 连接正常关闭
//   并校验下发的 stunUrl/turnUrl 与 coturn 实测公网地址一致，
//   最后用下发的 TURN 凭据做一次真实 Allocate（长凭证 + MESSAGE-INTEGRITY）。
//
// 退出码：0 全部通过；1 断言失败；2 环境/连接错误。

import fs from 'node:fs';
import dgram from 'node:dgram';
import crypto from 'node:crypto';

const WS_URL = process.argv[2] || 'ws://47.238.144.66:8443/ws';
const LOG_PATH = process.argv[3] || '';
const EXPECTED_HOST = '47.238.144.66'; // coturn 实测公网 IP（reports/06-coturn.md）
const EXPECTED_STUN = `stun:${EXPECTED_HOST}:3478`;
const EXPECTED_TURN = `turn:${EXPECTED_HOST}:3478?transport=udp`;

const failures = [];
const lines = [];
function log(msg) {
  const line = `[${new Date().toISOString()}] ${msg}`;
  console.log(line);
  lines.push(line);
}
function check(name, ok, detail = '') {
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' :: ' + detail : ''}`);
  if (!ok) failures.push(`${name}${detail ? ' :: ' + detail : ''}`);
}

// ---------------------------------------------------------------- WS client
class Client {
  constructor(name) {
    this.name = name;
    this.inbox = [];
    this.waiters = [];
    this.closedCode = null;
  }
  async connect(url) {
    await new Promise((resolve, reject) => {
      this.ws = new WebSocket(url);
      this.ws.onopen = () => { log(`[${this.name}] == WebSocket connected ${url}`); resolve(); };
      this.ws.onerror = (e) => reject(new Error(`[${this.name}] ws error: ${e.message || e}`));
      this.ws.onmessage = (e) => this._push(String(e.data));
      this.ws.onclose = (e) => { this.closedCode = e.code; this._push(`__CLOSE__:${e.code}`); };
    });
  }
  _push(data) {
    this.inbox.push(data);
    while (this.waiters.length) this.waiters.shift()(this.inbox.shift());
  }
  send(objOrRaw) {
    const raw = typeof objOrRaw === 'string' ? objOrRaw : JSON.stringify(objOrRaw);
    log(`[${this.name}] => ${raw}`);
    this.ws.send(raw);
    return raw;
  }
  recv(timeoutMs = 5000) {
    if (this.inbox.length) return Promise.resolve(this.inbox.shift());
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error(`[${this.name}] recv timeout`)), timeoutMs);
      this.waiters.push((v) => { clearTimeout(t); resolve(v); });
    });
  }
  async expectType(type, timeoutMs = 5000) {
    const raw = await this.recv(timeoutMs);
    log(`[${this.name}] <= ${raw}`);
    const msg = JSON.parse(raw);
    if (msg.type !== type) throw new Error(`[${this.name}] 期望 ${type}，收到 ${msg.type}`);
    return msg;
  }
  close() { try { this.ws.close(); } catch {} }
}

// ------------------------------------------------------- minimal TURN client
const MAGIC = Buffer.from([0x21, 0x12, 0xa4, 0x42]);
function attr(type, value) {
  const pad = (4 - (value.length % 4)) % 4;
  const b = Buffer.alloc(4 + value.length + pad);
  b.writeUInt16BE(type, 0);
  b.writeUInt16BE(value.length, 2);
  value.copy(b, 4);
  return b;
}
function parseAttrs(buf) {
  const out = [];
  let off = 20;
  while (off + 4 <= buf.length) {
    const type = buf.readUInt16BE(off);
    const len = buf.readUInt16BE(off + 2);
    out.push({ type, value: buf.subarray(off + 4, off + 4 + len) });
    off += 4 + len + ((4 - (len % 4)) % 4);
  }
  return out;
}
function stunMsg(type, txid, attrs) {
  const body = Buffer.concat(attrs);
  const h = Buffer.alloc(20);
  h.writeUInt16BE(type, 0);
  h.writeUInt16BE(body.length, 2);
  MAGIC.copy(h, 4);
  txid.copy(h, 8);
  return Buffer.concat([h, body]);
}
function withIntegrity(msg, key) {
  // RFC 5389 §15.4：HMAC-SHA1 覆盖到 MESSAGE-INTEGRITY 之前（不含其属性头），
  // 但报文头的 length 字段要加上 MESSAGE-INTEGRITY（24 字节）。
  // 实测：coturn 采用该约定（把 MI 属性头也算进去会得到 401）。
  const bodyNoMI = msg.subarray(20);
  const h = Buffer.alloc(20);
  msg.copy(h, 0, 0, 20);
  h.writeUInt16BE(bodyNoMI.length + 24, 2);
  const mac = crypto.createHmac('sha1', key).update(Buffer.concat([h, bodyNoMI])).digest();
  return Buffer.concat([h, bodyNoMI, attr(0x0008, mac)]);
}
function xorAddr(buf) {
  const fam = buf[1];
  const port = buf.readUInt16BE(2) ^ 0x2112;
  const ip = [...buf.subarray(4, 8)].map((b, i) => b ^ MAGIC[i]).join('.');
  return { family: fam, ip, port };
}
function turnAllocate(turnHost, turnPort, user, pass, timeoutMs = 6000) {
  return new Promise((resolve) => {
    const s = dgram.createSocket('udp4');
    const key0 = crypto.randomBytes(12);
    let key = null;
    let done = false;
    const fin = (r) => { if (!done) { done = true; try { s.close(); } catch {} resolve(r); } };
    const t = setTimeout(() => fin({ ok: false, err: 'timeout' }), timeoutMs);
    s.on('message', (buf) => {
      if (buf.length < 20) return;
      const type = buf.readUInt16BE(0);
      const attrs = parseAttrs(buf);
      const get = (n) => attrs.find((a) => a.type === n);
      if (type === 0x0113) { // Allocate error
        const realm = get(0x0014), nonce = get(0x0015);
        if (realm && nonce && !key) {
          key = crypto.createHash('md5').update(`${user}:${realm.value.toString()}:${pass}`).digest();
          const auth = stunMsg(0x0003, key0, [
            attr(0x0019, Buffer.from([17, 0, 0, 0])),
            attr(0x0006, Buffer.from(user)),
            attr(0x0014, realm.value),
            attr(0x0015, nonce.value),
          ]);
          s.send(withIntegrity(auth, key), turnPort, turnHost);
        } else {
          const errAttr = get(0x0009);
          clearTimeout(t);
          fin({ ok: false, err: `allocate error (code attr=${errAttr ? errAttr.value.readUInt16BE(2) : '?'})`, stage: 'auth' });
        }
        return;
      }
      if (type === 0x0103) { // Allocate success
        const relayAttr = get(0x0016);
        const mappedAttr = get(0x0020);
        const lifeAttr = get(0x000d);
        clearTimeout(t);
        fin({
          ok: true,
          relay: relayAttr ? xorAddr(relayAttr.value) : null,
          mapped: mappedAttr ? xorAddr(mappedAttr.value) : null,
          lifetime: lifeAttr ? lifeAttr.value.readUInt32BE(0) : null,
        });
      }
    });
    s.send(stunMsg(0x0003, key0, [attr(0x0019, Buffer.from([17, 0, 0, 0]))]), turnPort, turnHost);
  });
}

// ------------------------------------------------------------------- run
function flag(url) { return url.replace(/^turn:/, '').split('?')[0]; }
async function main() {
  log(`=== signaling E2E verification ===`);
  log(`wsUrl = ${WS_URL}`);
  const url = new URL(WS_URL.replace(/^ws/, 'http'));
  const host = url.hostname;
  const port = url.port || '80';

  const a = new Client('HOST');
  const b = new Client('JOINER');
  await a.connect(WS_URL);
  await b.connect(WS_URL);

  // 1) create / created
  a.send({ type: 'create' });
  const created = await a.expectType('created');
  check('created 含 6 位 roomId', /^[A-Z0-9]{6}$/.test(created.roomId), `roomId=${created.roomId}`);
  check('created.stunUrl == coturn 实测地址', created.stunUrl === EXPECTED_STUN, `got=${created.stunUrl}`);
  check('created.turnUrl == coturn 实测地址', created.turnUrl === EXPECTED_TURN, `got=${created.turnUrl}`);
  check('created 含 TURN 凭据', !!created.turnUsername && !!created.turnCredential,
    `${created.turnUsername}/${created.turnCredential}`);

  // 2) join / joined / peerJoined
  b.send({ type: 'join', roomId: created.roomId });
  const joined = await b.expectType('joined');
  check('joined.roomId 与 created 一致', joined.roomId === created.roomId, `${joined.roomId}`);
  check('joined 含 peerId', typeof joined.peerId === 'string' && joined.peerId.length > 0, `peerId=${joined.peerId}`);
  check('joined.stunUrl/turnUrl 与 created 一致',
    joined.stunUrl === created.stunUrl && joined.turnUrl === created.turnUrl,
    `${joined.stunUrl} | ${joined.turnUrl}`);
  const peerJoined = await a.expectType('peerJoined');
  check('发起方收到 peerJoined 且 peerId 匹配', peerJoined.peerId === joined.peerId, `${peerJoined.peerId}`);

  // 3) offer / answer 原样转发（逐字节）
  const offerRaw = '{"type":"offer","sdp":"v=0\\r\\no=- 4611731400430051336 2 IN IP4 127.0.0.1\\r\\ns=-\\r\\nt=0 0\\r\\na=group:BUNDLE 0\\r\\n"}';
  a.send(offerRaw);
  const offerGot = await b.recv();
  log(`[JOINER] <= ${offerGot}`);
  check('offer 原样转发（字节一致）', offerGot === offerRaw);

  const answerRaw = '{"type":"answer","sdp":"v=0\\r\\no=- 7720690197868186841 2 IN IP4 127.0.0.1\\r\\ns=-\\r\\nt=0 0\\r\\na=group:BUNDLE 0\\r\\n"}';
  b.send(answerRaw);
  const answerGot = await a.recv();
  log(`[HOST] <= ${answerGot}`);
  check('answer 原样转发（字节一致）', answerGot === answerRaw);

  // 4) ice 双向
  const iceA = '{"type":"ice","candidate":"candidate:1 1 udp 2122260223 192.168.1.7 51234 typ host generation 0","sdpMid":"0","sdpMLineIndex":0}';
  a.send(iceA);
  const iceGotA = await b.recv();
  log(`[JOINER] <= ${iceGotA}`);
  check('ice A→B 原样转发', iceGotA === iceA);

  const iceB = '{"type":"ice","candidate":"candidate:2 1 udp 1686052607 47.238.144.66 49152 typ srflx raddr 172.21.0.219 rport 49152","sdpMid":"0","sdpMLineIndex":0}';
  b.send(iceB);
  const iceGotB = await a.recv();
  log(`[HOST] <= ${iceGotB}`);
  check('ice B→A 原样转发', iceGotB === iceB);

  // 5) natType 双向
  a.send({ type: 'natType', natType: 'FullCone' });
  const nat1 = await b.expectType('natType');
  check('natType A→B', nat1.natType === 'FullCone', nat1.natType);
  b.send({ type: 'natType', natType: 'PortRestrictedCone' });
  const nat2 = await a.expectType('natType');
  check('natType B→A', nat2.natType === 'PortRestrictedCone', nat2.natType);

  // 6) 心跳
  b.send({ type: 'ping', timestamp: Date.now() });
  const pong = await b.expectType('pong');
  check('ping → pong', typeof pong.timestamp === 'number', `ts=${pong.timestamp}`);

  // 7) leave / peerLeft / 关闭
  a.send({ type: 'leave' });
  const left = await b.expectType('peerLeft');
  check('leave → 对端收到 peerLeft', left.peerId === 'peer-001' || typeof left.peerId === 'string', `peerId=${left.peerId}`);
  const closeMsg = await a.recv(5000);
  check('leave 后发起方连接正常关闭(1000)', closeMsg === '__CLOSE__:1000', closeMsg);

  // 8) 用下发的 TURN 配置做真实 Allocate
  const turn = flag(created.turnUrl);
  const [turnHost, turnPort] = turn.split(':');
  const alloc = await turnAllocate(turnHost, Number(turnPort), created.turnUsername, created.turnCredential);
  if (alloc.ok) {
    check('用信令下发的 TURN 凭据 Allocate 成功', true,
      `relay=${alloc.relay.ip}:${alloc.relay.port} mapped=${alloc.mapped.ip}:${alloc.mapped.port} lifetime=${alloc.lifetime}s`);
    check('relay 地址为 coturn 公网 IP', alloc.relay.ip === EXPECTED_HOST, alloc.relay.ip);
  } else {
    check('用信令下发的 TURN 凭据 Allocate 成功', false, JSON.stringify(alloc));
  }

  a.close(); b.close();
  log(`=== 总计: 通过 ${lines.filter((l) => l.includes('PASS ')).length} 项，失败 ${failures.length} 项 ===`);
  if (LOG_PATH) fs.writeFileSync(LOG_PATH, lines.join('\n') + '\n');
  if (failures.length) {
    log('失败明细:');
    failures.forEach((f) => log(`  - ${f}`));
    process.exit(1);
  }
  process.exit(0);
}

main().catch((e) => {
  log(`ERROR ${e.message}`);
  if (LOG_PATH) fs.writeFileSync(LOG_PATH, lines.join('\n') + '\n');
  process.exit(2);
});
