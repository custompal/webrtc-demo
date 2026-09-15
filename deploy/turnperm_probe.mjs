#!/usr/bin/env node
// turnperm.mjs — TURN CreatePermission 探测矩阵（t58 诊断用，零依赖，Node 18+）
//
// 用法: node turnperm.mjs <turnHost> <port> <user> <pass> <peerIP> [peerIP...]
// 在同一 allocation 上对每个 peerIP 做一次 CreatePermission，打印响应码。
// 目的：判定 coturn 对哪些对等地址返回 403 Forbidden IP。
// XOR-PEER-ADDRESS 必须按 RFC5766 §14.4 与 magic cookie 异或（端口 ^0x2112，地址 ^21 12 A4 42）。

import dgram from 'node:dgram';
import crypto from 'node:crypto';

const MAGIC = Buffer.from([0x21, 0x12, 0xa4, 0x42]);
const HOST = process.argv[2] || '172.21.0.219';
const PORT = Number(process.argv[3] || 3478);
const USER = process.argv[4] || 'demo';
const PASS = process.argv[5] || 'demopass';
const PEERS = process.argv.slice(6);
if (!PEERS.length) { console.error('usage: node turnperm.mjs host port user pass peerIP...'); process.exit(2); }

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
  const b = Buffer.alloc(8);
  b[0] = 0; b[1] = 1;
  b.writeUInt16BE(port ^ 0x2112, 2);
  ip.split('.').forEach((o, i) => { b[4 + i] = Number(o) ^ MAGIC[i]; });
  return attr(0x0012, b);
};
const errCode = (attrs) => { const a = attrs.find((x) => x.type === 0x0009); return a ? 100 * (a.value[2] & 0x07) + a.value[3] : null; };
const cls = (ip) => {
  const r = ip.split('.').map(Number);
  if (r[0] === 0) return 'this-network 0/8';
  if (r[0] === 127) return 'loopback 127/8';
  if (r[0] === 10 || (r[0] === 172 && r[1] >= 16 && r[1] <= 31) || (r[0] === 192 && r[1] === 168)) return 'RFC1918 私网';
  if (r[0] === 169 && r[1] === 254) return 'link-local/元数据';
  if (r[0] === 100 && r[1] >= 64 && r[1] <= 127) return 'CGNAT 100.64/10';
  if (r[0] >= 224 && r[0] <= 239) return 'multicast 224/4';
  if (r[0] >= 240) return 'reserved 240/4';
  return 'public 公网';
};

const sock = dgram.createSocket('udp4');
const waiters = [];
sock.on('message', (m, ri) => {
  if (m.length < 20) return;
  const type = m.readUInt16BE(0);
  for (let i = 0; i < waiters.length; i++) {
    if (waiters[i].want.includes(type)) {
      const w = waiters.splice(i, 1)[0];
      w.resolve({ type, attrs: parseAttrs(m), from: ri });
      return;
    }
  }
});
function rpc(packet, want, timeoutMs = 4000) {
  return new Promise((resolve) => {
    const w = { want, resolve: null };
    const timer = setTimeout(() => { const i = waiters.indexOf(w); if (i >= 0) waiters.splice(i, 1); resolve(null); }, timeoutMs);
    w.resolve = (v) => { clearTimeout(timer); resolve(v); };
    waiters.push(w);
    sock.send(packet, PORT, HOST);
  });
}

const txid = crypto.randomBytes(12);
const un = await rpc(msg(0x0003, txid, [attr(0x0019, Buffer.from([17, 0, 0, 0]))]), [0x0113, 0x0103]);
if (!un || un.type !== 0x0113) { console.log(`ALLOCATE unexpected: ${un ? '0x' + un.type.toString(16) : 'timeout'}`); process.exit(1); }
const realm = un.attrs.find((a) => a.type === 0x0014);
const nonce = un.attrs.find((a) => a.type === 0x0015);
const key = crypto.createHash('md5').update(`${USER}:${realm.value.toString()}:${PASS}`).digest();
const alloc = await rpc(withMI(msg(0x0003, txid, [
  attr(0x0019, Buffer.from([17, 0, 0, 0])),
  attr(0x0006, Buffer.from(USER)), attr(0x0014, realm.value), attr(0x0015, nonce.value),
]), key), [0x0103, 0x0113]);
if (!alloc || alloc.type !== 0x0103) { console.log(`ALLOCATE FAILED (${alloc ? 'error ' + errCode(alloc.attrs) : 'timeout'})`); process.exit(1); }
const ra = alloc.attrs.find((a) => a.type === 0x0016).value;
console.log(`TURN ${HOST}:${PORT}  user=${USER}  ALLOCATE ok  relay=${[...ra.subarray(4, 8)].map((b, i) => b ^ MAGIC[i]).join('.')}:${ra.readUInt16BE(2) ^ 0x2112}`);
console.log('');
console.log('peer_ip            result        err  class                verdict');
console.log('-----------------  ------------  ---  -------------------  -------------------------------');
for (const ip of PEERS) {
  const t = crypto.randomBytes(12);
  const base = msg(0x0008, t, [
    attr(0x0006, Buffer.from(USER)), attr(0x0014, realm.value), attr(0x0015, nonce.value), peerAttr(ip),
  ]);
  const resp = await rpc(withMI(base, key), [0x0108, 0x0118]);
  let result = 'NO-RESPONSE', code = '-';
  if (resp && resp.type === 0x0108) result = 'SUCCESS';
  else if (resp) { result = 'ERROR'; code = String(errCode(resp.attrs)); }
  const verdict = result === 'SUCCESS' ? 'CreatePermission 通过' : (resp ? `CreatePermission 被拒 (${code})` : '无响应');
  console.log(`${ip.padEnd(17)}  ${result.padEnd(12)}  ${code.padEnd(3)}  ${cls(ip).padEnd(19)}  ${verdict}`);
}
sock.close();
process.exit(0);
