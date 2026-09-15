#!/usr/bin/env node
// turnperm_tcp.mjs — TURN over TCP 探针（t58 附录 D 用；零依赖）
//
// 用法: node turnperm_tcp.mjs <host> <port> <user> <pass> [peerIP]
// 流程: TCP 连接 → ALLOCATE(未认证) → 401 → ALLOCATE(带 MI) → 0103 + relay → CreatePermission(peer) → 0108
// RFC5766 §6/§14: TURN over TCP 每条 STUN 消息前加 2 字节长度前缀（不含前 4 字节头）。

import net from 'node:net';
import crypto from 'node:crypto';

const MAGIC = Buffer.from([0x21, 0x12, 0xa4, 0x42]);
const HOST = process.argv[2] || '47.238.144.66';
const PORT = Number(process.argv[3] || 3478);
const USER = process.argv[4] || 'demo';
const PASS = process.argv[5] || 'demopass';
const PEER = process.argv[6] || null;
const TRANSPORT = Number(process.env.XT || 17);   // 17=UDP relay（A5 常用）, 6=TCP relay（RFC6062）

const attr = (type, value) => {
  const pad = (4 - (value.length % 4)) % 4;
  const b = Buffer.alloc(4 + value.length + pad);
  b.writeUInt16BE(type, 0); b.writeUInt16BE(value.length, 2); value.copy(b, 4);
  return b;
};
const parseAttrs = (buf) => {
  const out = []; let off = 20;
  while (off + 4 <= buf.length) {
    const type = buf.readUInt16BE(off), len = buf.readUInt16BE(off + 2);
    out.push({ type, value: buf.subarray(off + 4, off + 4 + len) });
    off += 4 + len + ((4 - (len % 4)) % 4);
  }
  return out;
};
const hdr = (type, len, txid) => {
  const h = Buffer.alloc(20); h.writeUInt16BE(type, 0); h.writeUInt16BE(len, 2);
  MAGIC.copy(h, 4); txid.copy(h, 8); return h;
};
const msg = (type, txid, attrs) => { const b = Buffer.concat(attrs); return Buffer.concat([hdr(type, b.length, txid), b]); };
const withMI = (base, key) => {
  const bodyNoMI = base.subarray(20);
  const h = hdr(base.readUInt16BE(0), bodyNoMI.length + 24, base.subarray(8, 20));
  const mac = crypto.createHmac('sha1', key).update(Buffer.concat([h, bodyNoMI])).digest();
  return Buffer.concat([h, bodyNoMI, attr(0x0008, mac)]);
};
const peerAttr = (ip, port = 9) => {
  const b = Buffer.alloc(8); b[0] = 0; b[1] = 1; b.writeUInt16BE(port ^ 0x2112, 2);
  ip.split('.').forEach((o, i) => { b[4 + i] = Number(o) ^ MAGIC[i]; }); return attr(0x0012, b);
};
const errCode = (attrs) => { const a = attrs.find((x) => x.type === 0x0009); return a ? 100 * (a.value[2] & 0x07) + a.value[3] : null; };

const sock = net.connect({ host: HOST, port: PORT });
sock.setNoDelay(true);
let recv = Buffer.alloc(0);
const queue = [];
sock.on('data', (d) => {
  recv = Buffer.concat([recv, d]);
  while (recv.length >= 20) {
    // STUN over TCP 自定帧：直接用报文头里的 length 字段（RFC5389 §7.2.2），无额外前缀
    const len = recv.readUInt16BE(2);
    if (recv.length < 20 + len) break;
    const m = recv.subarray(0, 20 + len);
    recv = recv.subarray(20 + len);
    const type = m.readUInt16BE(0);
    for (let i = 0; i < queue.length; i++) {
      if (queue[i].want.includes(type)) { const w = queue.splice(i, 1)[0]; w.resolve({ type, attrs: parseAttrs(m) }); break; }
    }
  }
});
function rpc(packet, want, timeoutMs = 5000) {
  return new Promise((resolve) => {
    const w = { want, resolve: null };
    const t = setTimeout(() => { const i = queue.indexOf(w); if (i >= 0) queue.splice(i, 1); resolve(null); }, timeoutMs);
    w.resolve = (v) => { clearTimeout(t); resolve(v); };
    queue.push(w);
    sock.write(packet);                       // 原样发送 STUN 报文（TCP 自定帧）
  });
}

await new Promise((res, rej) => {
  sock.once('connect', res);
  sock.once('error', rej);
  setTimeout(() => rej(new Error('tcp connect timeout')), 5000);
});
console.log(`TCP connected to ${HOST}:${PORT}`);

const txid = crypto.randomBytes(12);
const un = await rpc(msg(0x0003, txid, [attr(0x0019, Buffer.from([TRANSPORT, 0, 0, 0]))]), [0x0113, 0x0103]);
if (!un || un.type !== 0x0113) { console.log(`ALLOCATE(unauth) unexpected: ${un ? '0x' + un.type.toString(16) : 'timeout'}`); process.exit(1); }
console.log(`ALLOCATE(unauth) -> 0x0113 error ${errCode(un.attrs)}（长凭证挑战，预期 401）`);
const realm = un.attrs.find((a) => a.type === 0x0014);
const nonce = un.attrs.find((a) => a.type === 0x0015);
const key = crypto.createHash('md5').update(`${USER}:${realm.value.toString()}:${PASS}`).digest();
// REQUESTED-TRANSPORT=6 (TCP)
const alloc = await rpc(withMI(msg(0x0003, txid, [
  attr(0x0019, Buffer.from([TRANSPORT, 0, 0, 0])),
  attr(0x0006, Buffer.from(USER)), attr(0x0014, realm.value), attr(0x0015, nonce.value),
]), key), [0x0103, 0x0113]);
if (!alloc || alloc.type !== 0x0103) { console.log(`ALLOCATE(TCP relay) FAILED: ${alloc ? 'error ' + errCode(alloc.attrs) : 'timeout'}`); process.exit(1); }
const ra = alloc.attrs.find((a) => a.type === 0x0016).value;
console.log(`ALLOCATE(TCP relay) -> 0x0103 success  relay=${[...ra.subarray(4, 8)].map((b, i) => b ^ MAGIC[i]).join('.')}:${ra.readUInt16BE(2) ^ 0x2112}`);

if (PEER) {
  const t = crypto.randomBytes(12);
  const base = msg(0x0008, t, [
    attr(0x0006, Buffer.from(USER)), attr(0x0014, realm.value), attr(0x0015, nonce.value), peerAttr(PEER),
  ]);
  const resp = await rpc(withMI(base, key), [0x0108, 0x0118]);
  const ok = resp && resp.type === 0x0108;
  console.log(`CreatePermission(${PEER}) -> ${ok ? '0x0108 success' : resp ? 'error ' + errCode(resp.attrs) : 'timeout'}`);
}
sock.end();
process.exit(0);
