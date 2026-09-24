#!/usr/bin/env node
// =============================================================================
// web-demo-independent.mjs — T5 的**独立**验证工具（captain 裁定 t5-amend-1）
//
// 与交付脚本 scripts/web-demo-verify.sh 的关系（captain 界定的边界）：
//   * 允许复用：环境 bootstrap（Chrome + sysroot 的获取与 LD_LIBRARY_PATH 编排）。
//   * 必须自写：**全部断言与判定逻辑**（本文件不 import 交付脚本的任何判定函数，
//     不读取 web/tests/lib/*.mjs 的断言实现，也不以 tmp/ 素材作为判定依据）。
//
// 阶段：
//   --phase modules  对 web/lib/{signaling,observe,peer,timeline}.js 的纯函数做独立断言
//                    （期望值由 T5 依源码语义自行推导，不是作者单测的复述）
//   --phase env      定位/校验 Chrome 环境；若交付脚本提供 --print-chrome-path /
//                    --print-sysroot，则做「两套自举一致性」交叉核对；未提供则如实记 n/a
//   --phase media    自写 CDP 驱动**真实页面** web/index.html 两个标签页经真实信令互拨，
//                    用自写归约断言：ICE/DTLS connected、选中候选对 succeeded、
//                    双向 RTP 字节增长、outbound/inbound codec 经 codecId 解析为 video/VP9、
//                    帧计数增长、面板非空、JSON/CSV 可导出、控制台有观测输出
//
// 用法：
//   node web/tests/web-demo-independent.mjs [--phase all|modules|env|media]
//        [--signaling ws://47.238.144.66:8443/ws] [--chrome PATH] [--sysroot DIR]
//        [--delivery-script scripts/web-demo-verify.sh] [--verbose]
// 退出码：0 全通过 · 1 有断言失败 · 2 用法/环境错误（例如找不到 Chrome）
// =============================================================================

import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
  ROOM_ID_REGEX, normalizeRoomId, isValidRoomId, signalingUrl, rejoinDelayMs,
  rejoinSchedule, rejoinBudgetMs, pongMissed, pongTimeoutReached, classifyError,
  encodeMessage, decodeMessage, peekType, isKnownType, isServerOnlyType,
  isForwardType, isValidNatType, MAX_PENDING_MESSAGES, MAX_REJOIN_ATTEMPTS,
  SignalingCodecError,
} from '../lib/signaling.js';
import {
  parseSdp, isLoopbackAddress, classifyCandidate, codecMimeFor, reduceStats,
  statsRow, mediaEvidence, STATS_CSV_HEADER,
} from '../lib/observe.js';
import {
  turnTcpUrl, iceServersFor, iceSummary, serverUrlList, MAX_ICE_RESTARTS,
  BUNDLE_POLICY, RTCP_MUX_POLICY, MAX_PENDING_REMOTE_CANDIDATES, RELAY_PORT_RANGE,
} from '../lib/peer.js';
import { parseCandidate, csvCell, summarizeMessage } from '../lib/timeline.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
const WEB_ROOT = path.join(REPO, 'web');

// ---------------------------------------------------------------------------
// 结果收集
// ---------------------------------------------------------------------------
let pass = 0;
let fail = 0;
const failures = [];
function ok(id, detail = '') { pass++; console.log(`PASS ${id}${detail ? ' :: ' + detail : ''}`); }
function bad(id, detail, evidence) {
  fail++; failures.push({ id, detail, evidence });
  console.log(`FAIL ${id}${detail ? ' :: ' + detail : ''}`);
  if (evidence) console.log(`      evidence: ${String(evidence).slice(0, 600)}`);
}
function assertThat(cond, id, detail, evidence) {
  if (cond) ok(id, detail); else bad(id, detail, evidence);
  return cond;
}
async function step(id, fn) {
  try {
    const detail = await fn();
    if (detail !== false) ok(id, typeof detail === 'string' ? detail : '');
  } catch (e) {
    bad(id, e && e.message ? e.message : String(e), e && e.evidence);
  }
}
function need(cond, msg, evidence) {
  if (!cond) { const e = new Error(msg); e.evidence = evidence; throw e; }
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  // 默认缓存/临时目录**不落 /tmp**：本容器的 /tmp 是 256 MiB tmpfs，装不下 Chrome 自举
  // （426 MiB）与 Chrome profile（观测到单个 ~137 MiB），默认落 /tmp 会 ENOSPC。
  const diskTmp = path.resolve(REPO, '..', '..', 'tmp'); // 仓库外的工作区磁盘目录
  const out = {
    phase: 'all',
    signaling: process.env.SIGNALING_URL || 'ws://47.238.144.66:8443/ws',
    chrome: process.env.WEB_DEMO_INDEP_CHROME || '',
    sysroot: process.env.WEB_DEMO_INDEP_LD_LIBRARY_PATH || '',
    deliveryScript: path.join(REPO, 'scripts', 'web-demo-verify.sh'),
    cache: process.env.WEB_DEMO_INDEP_CACHE || path.join(diskTmp, 'web-demo-indep-cache'),
    profileDir: process.env.WEB_DEMO_INDEP_PROFILE_DIR || '',
    verbose: false,
    timeoutMs: 45000,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--phase') out.phase = argv[++i];
    else if (a === '--signaling') out.signaling = argv[++i];
    else if (a === '--chrome') out.chrome = argv[++i];
    else if (a === '--sysroot') out.sysroot = argv[++i];
    else if (a === '--delivery-script') out.deliveryScript = argv[++i];
    else if (a === '--cache') out.cache = argv[++i];
    else if (a === '--profile-dir') out.profileDir = argv[++i];
    else if (a === '--timeout-ms') out.timeoutMs = Number(argv[++i]);
    else if (a === '--verbose') out.verbose = true;
    else if (a === '--help' || a === '-h') {
      console.log('usage: node web/tests/web-demo-independent.mjs [--phase all|modules|env|media] [--signaling URL] [--chrome PATH] [--sysroot DIR] [--cache DIR] [--profile-dir DIR] [--verbose]');
      process.exit(0);
    } else { console.error(`web-demo-independent: unknown argument: ${a}`); process.exit(2); }
  }
  if (!out.profileDir) out.profileDir = path.join(out.cache, 'indep-profiles');
  if (!['all', 'modules', 'env', 'media'].includes(out.phase)) {
    console.error(`web-demo-independent: unknown --phase ${out.phase}`); process.exit(2);
  }
  return out;
}
const args = parseArgs(process.argv.slice(2));

// ---------------------------------------------------------------------------
// phase: modules —— 独立纯函数断言
// ---------------------------------------------------------------------------
async function phaseModules() {
  console.log('## phase modules — 独立纯函数断言（期望值由 T5 依源码语义自行推导）');

  await step('modules.signaling.roomid-rules', () => {
    need(isValidRoomId('AB779W') === true, 'AB779W 应合法');
    need(isValidRoomId('AB779') === false, '5 位应非法');
    need(isValidRoomId('AB779WW') === false, '7 位应非法');
    need(isValidRoomId('AB7790') === false, '含 0 应非法（字符集排除 0/1/I/L/O）');
    need(isValidRoomId('AB779I') === false, '含 I 应非法');
    need(isValidRoomId('OOOOOO') === false, '全 O 应非法');
    need(isValidRoomId('ab779w') === false, '小写应非法（须先 normalize）');
    need(normalizeRoomId('  ab779w ') === 'AB779W', 'normalize 应去空白并大写');
    need(normalizeRoomId('ab-779w!') === 'AB779W', 'normalize 应剔除非法字符');
    need(normalizeRoomId('AB779WXYZ') === 'AB779W', 'normalize 应截断 6 位');
    need(ROOM_ID_REGEX.test('A2B3C4') === true, 'ROOM_ID_REGEX 应接受 A2B3C4');
    return 'isValidRoomId/normalizeRoomId 与 ^[A-HJ-KM-NP-Z2-9]{6}$ 行为一致';
  });

  await step('modules.signaling.rejoin-backoff', () => {
    const seq = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(rejoinDelayMs);
    const want = [1000, 2000, 4000, 8000, 8000, 8000, 8000, 8000, 8000, 8000];
    need(JSON.stringify(seq) === JSON.stringify(want), `退避序列应为 ${want.join(',')} 实得 ${seq.join(',')}`);
    need(JSON.stringify(rejoinSchedule(10)) === JSON.stringify(want), 'rejoinSchedule(10) 应与逐点一致');
    need(rejoinBudgetMs(10) === want.reduce((a, b) => a + b, 0), '预算应为序列之和');
    need(MAX_REJOIN_ATTEMPTS === 10, '重连上限应为 10');
    return `seq=${seq.join('/')} budget=${rejoinBudgetMs(10)}ms attempts=${MAX_REJOIN_ATTEMPTS}`;
  });

  await step('modules.signaling.pong-window', () => {
    // 语义：ping 已发且超过 timeout 且 lastPong 早于本次 ping → 记为丢失
    need(pongMissed(10000, 4000, 1000, 5000) === true, '超窗且未收 pong 应判丢失');
    need(pongMissed(6000, 4000, 1000, 5000) === false, '未超窗不应判丢失');
    need(pongMissed(10000, 4000, 9000, 5000) === false, '本窗已收 pong 不应判丢失');
    need(pongMissed(10000, 0, 0, 5000) === false, '未发 ping 不应判丢失');
    need(pongTimeoutReached(3, 4) === false, '3 次未达 4 次阈值');
    need(pongTimeoutReached(4, 4) === true, '4 次应达阈值（≈20s）');
    return 'pong 窗口 5s × 连续 4 窗 = 20s 判活语义成立';
  });

  await step('modules.signaling.codec-roundtrip', () => {
    const text = encodeMessage('join', { roomId: 'AB779W' });
    need(text === '{"type":"join","roomId":"AB779W"}', `编码结果异常: ${text}`);
    const back = decodeMessage(text);
    need(back.type === 'join' && back.roomId === 'AB779W', '往返应一致');
    // 未知键忽略、可缺省字段 null 不出现
    const t2 = encodeMessage({ type: 'ice', candidate: 'c', sdpMLineIndex: null, extraKey: 1 });
    need(!('extraKey' in JSON.parse(t2)) && !('sdpMLineIndex' in JSON.parse(t2)), `未知键/null 可缺省字段不应出现: ${t2}`);
    // 必填缺失 / 类型错误 / 未知 type
    const err1 = (() => { try { decodeMessage('{"type":"join"}'); return null; } catch (e) { return e; } })();
    need(err1 instanceof SignalingCodecError, 'join 缺 roomId 应抛 SignalingCodecError');
    const err2 = (() => { try { decodeMessage('{"type":"join","roomId":123}'); return null; } catch (e) { return e; } })();
    need(err2 instanceof SignalingCodecError, 'roomId 非字符串应抛错');
    const err3 = (() => { try { decodeMessage('{"type":"nope"}'); return null; } catch (e) { return e; } })();
    need(err3 instanceof SignalingCodecError && err3.recoverable === true && err3.unknownType === true,
      '未知 type 应为可恢复错误（不得中断连接）');
    need(peekType('{"type":"pong","timestamp":1}') === 'pong', 'peekType 应取到 type');
    need(peekType('not json') === '<invalid>', 'peekType 对非法帧应返回 <invalid>');
    need(isKnownType('natType') && !isKnownType('nope'), 'isKnownType 语义正确');
    need(isServerOnlyType('pong') && !isServerOnlyType('ping'), 'isServerOnlyType 语义正确');
    need(isForwardType('ice') && !isForwardType('leave'), 'isForwardType 语义正确');
    need(isValidNatType('Symmetric') && !isValidNatType('Bogus'), 'natType 枚举校验正确');
    return '编码/解码/未知键/必填/未知 type/peekType 全部符合 doc/09 语义';
  });

  await step('modules.signaling.error-classification', () => {
    // 源码语义（T5 读码推导，非复述作者单测）：
    //   TERMINAL_CODES = ROOM_NOT_FOUND / ROOM_EXPIRED / INVALID_MESSAGE / NOT_IN_ROOM
    //   ROOM_FULL 不在终结集：非重连语境 → SURFACE；掉线重连语境 → RETRY_REJOIN（保留房间意图）
    for (const code of ['ROOM_NOT_FOUND', 'ROOM_EXPIRED', 'INVALID_MESSAGE', 'NOT_IN_ROOM']) {
      const r = classifyError(code);
      need(r.clearsRoomIntent === true, `${code} 应清房间意图（终结码）`);
      need(r.action === 'TERMINAL_SUPPRESS', `${code} 应为 TERMINAL_SUPPRESS，实得 ${r.action}`);
    }
    const full = classifyError('ROOM_FULL');
    need(full.clearsRoomIntent === false, 'ROOM_FULL 不应清房间意图（用户可稍后重试）');
    need(full.action === 'SURFACE', `非重连语境的 ROOM_FULL 应为 SURFACE，实得 ${full.action}`);
    const fullRejoin = classifyError('ROOM_FULL', { rejoinAfterDrop: true });
    need(fullRejoin.action === 'RETRY_REJOIN' && fullRejoin.clearsRoomIntent === false,
      `掉线重连语境的 ROOM_FULL 应为 RETRY_REJOIN 且不清意图，实得 ${JSON.stringify(fullRejoin)}`);
    const internal = classifyError('INTERNAL_ERROR');
    need(internal.action === 'SURFACE' && internal.clearsRoomIntent === false,
      `INTERNAL_ERROR 应为 SURFACE 且不清意图，实得 ${JSON.stringify(internal)}`);
    return '终结集(4)与 ROOM_FULL 双分支(SURFACE/RETRY_REJOIN) + INTERNAL_ERROR 处置均正确';
  });

  await step('modules.observe.parse-sdp', () => {
    const sdp = [
      'v=0', 'o=- 1 1 IN IP4 127.0.0.1', 's=-', 't=0 0',
      'a=group:BUNDLE 0 1', 'a=ice-ufrag:ufr', 'a=ice-pwd:pwd',
      'a=fingerprint:sha-256 AA:BB:CC', 'a=setup:actpass',
      'm=audio 9 UDP/TLS/RTP/SAVPF 111', 'a=mid:0', 'a=rtpmap:111 opus/48000/2',
      'a=candidate:1 1 udp 2113937151 10.0.0.1 5000 typ host',
      'm=video 9 UDP/TLS/RTP/SAVPF 98', 'a=mid:1', 'a=rtpmap:98 VP9/90000',
      'a=candidate:2 1 udp 2113937151 10.0.0.2 5001 typ srflx',
    ].join('\r\n');
    const p = parseSdp(sdp);
    need(p.session.iceUfrag === 'ufr', `iceUfrag=${p.session.iceUfrag}`);
    need(p.session.icePwd === 'pwd', `icePwd=${p.session.icePwd}`);
    need(Array.isArray(p.session.fingerprints) && p.session.fingerprints.length === 1, 'fingerprint 应解析 1 条');
    need(p.media.length === 2, `m= 段应为 2，实得 ${p.media.length}`);
    const kinds = p.media.map((m) => m.kind ?? m.media ?? m.mediaType ?? '');
    need(kinds[0] !== kinds[1], `两段应不同（音频/视频），实得 ${JSON.stringify(kinds)}`);
    const videoSeg = p.media[1];
    // 源码语义：m= 段的 payloadTypes 是数字数组；codecs[] 是 {payload,name,clockRate,channels,parameters}
    const pts = videoSeg.payloadTypes ?? [];
    need(Array.isArray(pts) && pts.includes(98), `视频段 payloadTypes 应含 98，实得 ${JSON.stringify(pts)}`);
    const vcodec = (videoSeg.codecs ?? []).find((c) => c.payload === 98);
    need(vcodec !== undefined && String(vcodec.name).toUpperCase() === 'VP9',
      `video PT 98 应映射到 VP9，实得 ${JSON.stringify(vcodec)}`);
    const audioSeg = p.media[0];
    need((audioSeg.payloadTypes ?? []).includes(111), `音频段 payloadTypes 应含 111，实得 ${JSON.stringify(audioSeg.payloadTypes)}`);
    const acodec = (audioSeg.codecs ?? []).find((c) => c.payload === 111);
    need(acodec !== undefined && String(acodec.name).toLowerCase() === 'opus', `audio PT 111 应映射到 opus，实得 ${JSON.stringify(acodec)}`);
    return `m=${p.media.length} ufrag/pwd/fingerprint OK video pts=${JSON.stringify(pts)} codec=${vcodec.name}/${vcodec.clockRate}`;
  });

  await step('modules.observe.candidate-classification', () => {
    const host = classifyCandidate('candidate:1 1 udp 2113937151 10.0.0.5 5000 typ host');
    need(host.type === 'host' && host.protocol === 'udp' && host.address === '10.0.0.5' && host.port === 5000,
      `host 解析错误: ${JSON.stringify(host)}`);
    need(host.loopback === false, '10.0.0.5 非 loopback');
    const relay = classifyCandidate('candidate:3 1 udp 41885439 47.238.144.66 49152 typ relay raddr 10.0.0.9 rport 9', 'remote');
    need(relay.type === 'relay' && relay.relatedAddress === '10.0.0.9' && relay.relatedPort === 9,
      `relay 解析错误: ${JSON.stringify(relay)}`);
    need(relay.source === 'remote', 'source 应记录为 remote');
    const tcp = classifyCandidate({ candidate: 'candidate:4 1 tcp 1518280447 10.0.0.6 9 typ host tcptype active', sdpMid: '0', sdpMLineIndex: 0 });
    need(tcp.protocol === 'tcp' && tcp.tcpType === 'active', `tcp 解析错误: ${JSON.stringify(tcp)}`);
    need(tcp.sdpMid === '0' && tcp.sdpMLineIndex === 0, 'sdpMid/sdpMLineIndex 应保留');
    const lo = classifyCandidate('candidate:5 1 udp 1 127.0.0.1 9 typ host');
    need(lo.loopback === true, '127.0.0.1 应判 loopback');
    need(isLoopbackAddress('::1') === true && isLoopbackAddress('[::1]') === true, '::1 应判 loopback');
    need(isLoopbackAddress('10.0.0.1') === false && isLoopbackAddress('') === false, '非 loopback/空串语义正确');
    return 'host/relay/tcp/loopback 分类与字段提取正确';
  });

  await step('modules.observe.stats-reduction', () => {
    // 手写最小 RTCStatsReport（Map 语义）+ 两个时刻，独立验证归约：codecId→mimeType、字节增长、DTLS/ICE
    const mk = (o) => new Map(Object.entries(o));
    const t0 = mk({
      codec1: { id: 'codec1', type: 'codec', mimeType: 'video/VP9', clockRate: 90000, payloadType: 98 },
      out1: { id: 'out1', type: 'outbound-rtp', kind: 'video', codecId: 'codec1', bytesSent: 1000, packetsSent: 10, framesEncoded: 5 },
      in1: { id: 'in1', type: 'inbound-rtp', kind: 'video', codecId: 'codec1', bytesReceived: 500, packetsReceived: 5, framesDecoded: 3 },
      pair1: { id: 'pair1', type: 'candidate-pair', state: 'succeeded', nominated: true, bytesSent: 1000, bytesReceived: 500, localCandidateId: 'lc', remoteCandidateId: 'rc' },
      tr1: { id: 'tr1', type: 'transport', iceState: 'connected', dtlsState: 'connected', selectedCandidatePairId: 'pair1', bytesSent: 1000, bytesReceived: 500 },
    });
    const s0 = reduceStats(t0, null, { atMs: 1000, wallMs: 1000 });
    need(codecMimeFor(t0, 'codec1') === 'video/VP9', 'codecMimeFor 应解析出 video/VP9');
    need(codecMimeFor(t0, 'nope') === null, '未知 codecId 应返回 null');
    const outRow = s0.outbound.find((r) => r.kind === 'video');
    need(outRow !== undefined && codecMimeFor(t0, outRow.codecId) === 'video/VP9', 'outbound 行的 codecId 应能解析');
    need(s0.transport !== null && s0.transport.dtlsState === 'connected', 'DTLS 状态应归约出来');
    need(s0.selectedPair !== null && s0.selectedPair.state === 'succeeded', '选中候选对应为 succeeded');

    const t1 = mk({
      codec1: { id: 'codec1', type: 'codec', mimeType: 'video/VP9', clockRate: 90000, payloadType: 98 },
      out1: { id: 'out1', type: 'outbound-rtp', kind: 'video', codecId: 'codec1', bytesSent: 5000, packetsSent: 50, framesEncoded: 25 },
      in1: { id: 'in1', type: 'inbound-rtp', kind: 'video', codecId: 'codec1', bytesReceived: 2500, packetsReceived: 25, framesDecoded: 15 },
      pair1: { id: 'pair1', type: 'candidate-pair', state: 'succeeded', nominated: true, bytesSent: 5000, bytesReceived: 2500, localCandidateId: 'lc', remoteCandidateId: 'rc' },
      tr1: { id: 'tr1', type: 'transport', iceState: 'connected', dtlsState: 'connected', selectedCandidatePairId: 'pair1', bytesSent: 5000, bytesReceived: 2500 },
    });
    const s1 = reduceStats(t1, s0, { atMs: 6000, wallMs: 6000 });
    need(s1.totals.bytesSent === 5000, `bytesSent 应取最新值，实得 ${s1.totals.bytesSent}`);
    need(s1.totals.bytesReceived === 2500, `bytesReceived 应取最新值，实得 ${s1.totals.bytesReceived}`);
    need(s1.totals.outboundBitrateBps > 0, `码率应 > 0，实得 ${s1.totals.outboundBitrateBps}`);
    // 记录归约的取值优先级（transport 优先于 outbound 行求和）——真实 Chrome 的 transport 必带字节数
    const noTransport = mk({ out9: { id: 'out9', type: 'outbound-rtp', kind: 'video', codecId: 'codec1', bytesSent: 777 } });
    const sn = reduceStats(noTransport, null, { atMs: 1, wallMs: 1 });
    need(sn.totals.bytesSent === 777, `无 transport 时应回退到 outbound 求和，实得 ${sn.totals.bytesSent}`);
    const ev = mediaEvidence(s0, s1);
    need(ev !== null && typeof ev === 'object', 'mediaEvidence 应返回对象');
    const row = statsRow(s1);
    need(row.vp9 === true, `statsRow.vp9 应为 true，实得 ${row.vp9}`);
    need(row.dtls_state === 'connected' && row.ice_state === 'connected', 'statsRow 应带 ICE/DTLS 状态');
    need(Array.isArray(STATS_CSV_HEADER) && STATS_CSV_HEADER.includes('vp9'), 'CSV 表头应含 vp9');
    return `vp9=${row.vp9} outBitrate=${s1.totals.outboundBitrateBps} dtls=${row.dtls_state} csvCols=${STATS_CSV_HEADER.length}`;
  });

  await step('modules.peer.ice-config-from-handout', () => {
    const handout = {
      stunUrl: 'stun:47.238.144.66:3478',
      turnUrl: 'turn:47.238.144.66:3478?transport=udp',
      turnUsername: 'demo',
      turnCredential: 'demopass',
    };
    const servers = iceServersFor(handout);
    need(servers.length === 3, `应构造 3 条（STUN/UDP/TCP），实得 ${servers.length}`);
    need(servers[0].urls === handout.stunUrl, '第 1 条应为 STUN');
    need(servers[1].urls === handout.turnUrl && servers[1].username === 'demo' && servers[1].credential === 'demopass',
      '第 2 条应为 TURN/UDP 且带凭据');
    need(/transport=tcp/i.test(servers[2].urls), `第 3 条应为 TCP 回退，实得 ${servers[2].urls}`);
    need(turnTcpUrl('turn:h:3478?transport=udp') === 'turn:h:3478?transport=tcp', 'turnTcpUrl 应改写 transport');
    need(turnTcpUrl('turn:h:3478?transport=tcp') === null, '已是 TCP 应返回 null（不重复）');
    need(turnTcpUrl('turns:h:5349') === null, 'turns: 不在本轮范围应返回 null');
    need(iceServersFor({ stunUrl: 's' }).length === 1, '只有 STUN 时应只 1 条');
    need(iceServersFor({ turnUrl: 'turn:h', turnUsername: 'u' }).length === 0, 'TURN 凭据不全时不得加入');
    const sum = iceSummary(handout);
    need(sum.serverCount === 3 && sum.credentialPresent === true && sum.turnTcpFallbackActive === true,
      `iceSummary 异常: ${JSON.stringify(sum)}`);
    need(sum.entries.every((e) => e.credential !== 'demopass'), '摘要不得落明文凭据');
    need(serverUrlList(servers).length === 3, 'serverUrlList 应展开 3 条 URL');
    need(MAX_ICE_RESTARTS === 2, 'ICE restart 上限应为 2');
    need(BUNDLE_POLICY === 'max-bundle' && RTCP_MUX_POLICY === 'require', 'bundle/rtcp-mux 策略应符合统一 plan');
    need(MAX_PENDING_REMOTE_CANDIDATES === 64, '远端候选缓冲应为 64');
    need(RELAY_PORT_RANGE.min === 49152 && RELAY_PORT_RANGE.max === 49200, 'relay 端口区间应为 49152-49200');
    return `servers=${servers.map((s) => s.urls).join(' | ')}`;
  });

  await step('modules.timeline.summary-and-csv', () => {
    const c = parseCandidate('candidate:1 1 udp 2113937151 47.238.144.66 49152 typ srflx raddr 10.0.0.1 rport 9');
    need(c.foundation === '1' && c.protocol === 'udp' && c.address === '47.238.144.66' && c.port === '49152' && c.typ === 'srflx',
      `parseCandidate 异常: ${JSON.stringify(c)}`);
    need(c.relAddr === '10.0.0.1' && c.relPort === '9', 'raddr/rport 应解析');
    need(csvCell('a,b') === '"a,b"', 'CSV 逗号应转义');
    need(csvCell('a"b') === '"a""b"', 'CSV 引号应翻倍');
    need(csvCell(null) === '' && csvCell('ok') === 'ok', 'CSV 空值/普通值语义正确');
    const sCreated = summarizeMessage('created', { roomId: 'AB779W', stunUrl: 's', turnUrl: 't', turnUsername: 'u' });
    need(sCreated.includes('AB779W') && sCreated.includes('turn=t'), `created 摘要缺字段: ${sCreated}`);
    const sOffer = summarizeMessage('offer', { sdp: 'v=0\r\na=1' });
    need(!sOffer.includes('v=0') && /byte|sdp/i.test(sOffer), `offer 摘要不得含整段 SDP: ${sOffer}`);
    const sErr = summarizeMessage('error', { code: 'ROOM_FULL', message: 'Room is full (max 2 peers)' });
    need(sErr.includes('ROOM_FULL'), `error 摘要应含 code: ${sErr}`);
    need(MAX_PENDING_MESSAGES === 32, '未注册监听时的缓冲上限应为 32');
    return `created="${sCreated}" offer="${sOffer}" error="${sErr}"`;
  });
}

// ---------------------------------------------------------------------------
// phase: env —— Chrome 定位 + 两套自举一致性（开关缺失则如实记 n/a）
// ---------------------------------------------------------------------------
function findChrome(explicit, cache) {
  const candidates = [];
  if (explicit) candidates.push(explicit);
  const cacheRoots = [cache, path.join(REPO, 'tmp', 'chrome-env')].filter(Boolean);
  for (const root of cacheRoots) {
    candidates.push(
      path.join(root, 'chrome-headless-shell-linux64', 'chrome-headless-shell'),
      path.join(root, 'chrome', 'chrome-headless-shell'),
      path.join(root, 'chrome-linux64', 'chrome'),
    );
  }
  // 交付脚本缓存目录的常见布局：<cache>/chrome/**/chrome-headless-shell
  for (const root of cacheRoots) {
    if (!fs.existsSync(root)) continue;
    const stack = [root];
    while (stack.length > 0) {
      const dir = stack.pop();
      let entries = [];
      try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { continue; }
      for (const e of entries) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) stack.push(p);
        else if (e.name === 'chrome-headless-shell' || e.name === 'chrome') candidates.push(p);
      }
    }
  }
  for (const c of candidates) {
    try { if (fs.existsSync(c) && fs.statSync(c).isFile()) return c; } catch { /* keep looking */ }
  }
  return null;
}

function chromeVersion(bin, ldPath) {
  const r = spawnSync(bin, ['--version'], {
    env: { ...process.env, ...(ldPath ? { LD_LIBRARY_PATH: ldPath } : {}) },
    encoding: 'utf8', timeout: 20000,
  });
  const text = `${r.stdout ?? ''}${r.stderr ?? ''}`.trim();
  return { code: r.status, text };
}

function deliverySwitch(script, flag) {
  if (!fs.existsSync(script)) return { available: false, reason: 'delivery script absent' };
  // 交付脚本的 print 类开关在解析到该参数时**立即执行并退出**，因此 --cache 必须排在它前面
  // （实测 `--print-chrome-path --cache DIR` 会忽略 cache 并在默认 /tmp 缓存上失败）。
  const r = spawnSync('bash', [script, '--cache', args.cache, flag], { cwd: REPO, encoding: 'utf8', timeout: 60000 });
  return { available: true, code: r.status, out: `${r.stdout ?? ''}`.trim(), err: `${r.stderr ?? ''}`.trim() };
}

async function phaseEnv() {
  console.log('## phase env — 环境定位与两套自举一致性');
  const ld = args.sysroot || [
    path.join(args.cache, 'sysroot', 'usr', 'lib', 'x86_64-linux-gnu'),
    path.join(args.cache, 'sysroot', 'lib', 'x86_64-linux-gnu'),
    path.join(args.cache, 'sysroot', 'usr', 'lib'),
  ].filter((p) => fs.existsSync(p)).join(':');
  const chrome = findChrome(args.chrome, args.cache);
  if (chrome === null) {
    bad('env.chrome-located', '未找到 chrome-headless-shell', `hint: 传 --chrome PATH 或 --cache <已自举缓存目录>（当前 cache=${args.cache}）`);
  } else {
    const mine = chromeVersion(chrome, ld);
    ok('env.chrome-located', `path=${chrome} version="${mine.text}" exit=${mine.code} ld=${ld || '(none)'}`);
    const dataDir = fs.existsSync(path.join(args.cache, 'sysroot', 'usr', 'share'))
      ? path.join(args.cache, 'sysroot', 'usr', 'share') : '';
    globalThis.__indepEnv = { chrome, ld, dataDir, version: mine.text };
  }

  // 两套自举一致性：交付脚本的纯值开关
  const pcp = deliverySwitch(args.deliveryScript, '--print-chrome-path');
  const psr = deliverySwitch(args.deliveryScript, '--print-sysroot');
  const pcpOk = pcp.available && pcp.code === 0 && pcp.out !== '';
  const psrOk = psr.available && psr.code === 0 && psr.out !== '';
  if (!pcpOk || !psrOk) {
    // captain 口径：开关未落位 → n/a + 如实声明（不是 blocker），但必须写清实测行为
    ok('env.cross-bootstrap-consistent :: n/a',
      `开关不可用（--print-chrome-path exit=${pcp.code} out=${JSON.stringify(pcp.out)} err=${JSON.stringify(pcp.err.slice(0, 80))}; `
      + `--print-sysroot exit=${psr.code} out=${JSON.stringify(psr.out)} err=${JSON.stringify(psr.err.slice(0, 80))}）`
      + ' → 交叉核对 n/a，待 t8 补齐后执行');
    return;
  }
  // --print-sysroot 返回的是 **sysroot 根**，不是 LD_LIBRARY_PATH；消费方需按标准三个库目录
  // 自行拼接（与交付 harness 的 libPathOf() 同构）。这就是「可解释一致」的含义。
  const sysrootRoot = psr.out.trim();
  const composeLd = (root) => [
    path.join(root, 'usr', 'lib', 'x86_64-linux-gnu'),
    path.join(root, 'lib', 'x86_64-linux-gnu'),
    path.join(root, 'usr', 'lib'),
  ].filter((p) => fs.existsSync(p)).join(':');
  const deliveryLd = composeLd(sysrootRoot);
  const mineLd = globalThis.__indepEnv ? globalThis.__indepEnv.ld : '';
  const a = chromeVersion(pcp.out.trim(), deliveryLd);
  const b = globalThis.__indepEnv ? chromeVersion(globalThis.__indepEnv.chrome, mineLd) : { text: '' };
  assertThat(a.text !== '' && a.text === b.text, 'env.cross-bootstrap-consistent',
    `delivery="${a.text}" (chrome=${pcp.out.trim()}, sysroot=${sysrootRoot}, ld=${deliveryLd}) vs indep="${b.text}" (ld=${mineLd})`,
    a.text === '' ? `delivery chrome 不可执行（exit=${a.code}）——注意 --print-sysroot 是根目录，需自行拼 LD_LIBRARY_PATH` : '');
  assertThat(deliveryLd === mineLd && mineLd !== '', 'env.ld-composition-consistent',
    `两套自举的 LD_LIBRARY_PATH 拼接一致: ${deliveryLd}`);
}

// ---------------------------------------------------------------------------
// phase: media —— 自写 CDP 驱动真实页面双标签页互拨
// ---------------------------------------------------------------------------
class CDP {
  constructor(wsUrl) {
    this.ws = new WebSocket(wsUrl);
    this.nextId = 1;
    this.pending = new Map();
    this.handlers = new Map();
    this.ready = new Promise((resolve, reject) => {
      const to = setTimeout(() => reject(new Error('CDP connect timeout')), 20000);
      this.ws.onopen = () => { clearTimeout(to); resolve(); };
      this.ws.onerror = (e) => { clearTimeout(to); reject(new Error(`CDP ws error ${e && e.message ? e.message : ''}`)); };
    });
    this.ws.onmessage = (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : String(ev.data));
      if (args.verbose && (msg.id !== undefined || msg.method === 'Target.attachedToTarget')) {
        console.log(`  [cdp] <- ${JSON.stringify(msg).slice(0, 220)}`);
      }
      if (msg.id !== undefined && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(`${msg.error.message} (${JSON.stringify(msg.error.data ?? '')})`));
        else resolve(msg.result);
        return;
      }
      if (msg.method) {
        const list = this.handlers.get(msg.method) ?? [];
        for (const h of list) h(msg.params ?? {}, msg.sessionId ?? null);
        const all = this.handlers.get('*') ?? [];
        for (const h of all) h(msg);
      }
    };
  }
  send(method, params = {}, sessionId) {
    const id = this.nextId++;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP ${method} timeout`)); }, args.timeoutMs);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      this.ws.send(JSON.stringify(payload));
    });
  }
  on(method, handler) {
    const list = this.handlers.get(method) ?? [];
    list.push(handler);
    this.handlers.set(method, list);
  }
  close() { try { this.ws.close(); } catch { /* ignore */ } }
}

function contentType(p) {
  if (p.endsWith('.html')) return 'text/html; charset=utf-8';
  if (p.endsWith('.js') || p.endsWith('.mjs')) return 'text/javascript; charset=utf-8';
  if (p.endsWith('.css')) return 'text/css; charset=utf-8';
  if (p.endsWith('.json')) return 'application/json; charset=utf-8';
  return 'application/octet-stream';
}

function startStaticServer(root) {
  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const url = new URL(req.url, 'http://localhost');
      let rel = decodeURIComponent(url.pathname);
      if (rel === '/' || rel === '') rel = '/index.html';
      const file = path.join(root, path.normalize(rel).replace(/^(\.\.[/\\])+/, ''));
      if (!file.startsWith(root)) { res.writeHead(403); res.end('forbidden'); return; }
      fs.readFile(file, (err, data) => {
        if (err) { res.writeHead(404); res.end('not found'); return; }
        res.writeHead(200, { 'content-type': contentType(file) });
        res.end(data);
      });
    });
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

function launchChrome(chrome, ld, dataDir) {
  fs.mkdirSync(args.profileDir, { recursive: true });
  const profile = fs.mkdtempSync(path.join(args.profileDir, 'profile-'));
  const argv = [
    '--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu', '--headless=new',
    '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
    '--autoplay-policy=no-user-gesture-required',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    'about:blank',
  ];
  // XDG_DATA_DIRS（sysroot 的 /usr/share）必需：缺失时本环境实测渲染器不加载页面模块图，
  // 且 Page.loadEventFired / Runtime.evaluate 均无响应（见 reports/72 环境小节）。
  const child = spawn(chrome, argv, {
    env: {
      ...process.env,
      ...(ld ? { LD_LIBRARY_PATH: ld } : {}),
      ...(dataDir ? { XDG_DATA_DIRS: dataDir } : {}),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const stderrChunks = [];
  const wsUrl = new Promise((resolve, reject) => {
    const to = setTimeout(() => reject(new Error(`chrome DevTools endpoint not found in 20s; stderr tail: ${stderrChunks.join('').slice(-400)}`)), 20000);
    const scan = (buf) => {
      const text = String(buf);
      stderrChunks.push(text);
      const m = text.match(/DevTools listening on (ws:\/\/\S+)/);
      if (m) { clearTimeout(to); resolve(m[1]); }
    };
    child.stderr.on('data', scan);
    child.stdout.on('data', scan);
    child.on('exit', (code) => { clearTimeout(to); reject(new Error(`chrome exited early: ${code}; stderr: ${stderrChunks.join('').slice(-400)}`)); });
  });
  return { child, profile, wsUrl, stderrChunks };
}

async function phaseMedia() {
  console.log('## phase media — 自写 CDP 驱动真实页面（web/index.html）双标签页互拨');
  if (!globalThis.__indepEnv) {
    bad('media.precondition', '环境未就绪（需先运行 --phase env 定位 Chrome）');
    return;
  }
  const { chrome, ld, dataDir } = globalThis.__indepEnv;
  const { server, port } = await startStaticServer(WEB_ROOT);
  const origin = `http://127.0.0.1:${port}`;
  // 两个独立 Chrome 进程（而非同一进程两个 target）：本环境实测 chrome-headless-shell 的
  // 第二个 target 对 session 级命令不响应（Runtime.enable 超时），故按进程隔离。
  const runs = { A: launchChrome(chrome, ld, dataDir), B: launchChrome(chrome, ld, dataDir) };
  const cdps = { A: null, B: null };
  const consoleCounts = { A: 0, B: 0 };
  try {
    const pageUrl = `${origin}/index.html?signaling=${encodeURIComponent(args.signaling)}`;
    const sessions = {};
    for (const label of ['A', 'B']) {
      const wsUrl = await runs[label].wsUrl;
      const cdp = new CDP(wsUrl);
      await cdp.ready;
      cdps[label] = cdp;
      const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
      const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
      sessions[label] = sessionId;
      await cdp.send('Runtime.enable', {}, sessionId);
      await cdp.send('Page.enable', {}, sessionId);
      cdp.on('Runtime.consoleAPICalled', () => { consoleCounts[label]++; });
      // 显式导航 + 等 load 事件：实测「导航进行中直接 evaluate」不返回，故按标准顺序驱动
      const loaded = new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`${label}: Page.loadEventFired timeout`)), args.timeoutMs);
        cdp.on('Page.loadEventFired', (_p, sid) => {
          if (sid === sessionId) { clearTimeout(timer); resolve(true); }
        });
      });
      await cdp.send('Page.navigate', { url: pageUrl }, sessionId);
      await loaded;
    }
    ok('media.chrome-devtools', `2× chrome-headless-shell (独立进程) origin=${origin} ld=${ld ? 'set' : 'unset'} XDG_DATA_DIRS=${dataDir ? 'set' : 'unset'}`);

    const evl = async (label, expression) => {
      const r = await cdps[label].send('Runtime.evaluate', {
        expression, awaitPromise: true, returnByValue: true,
      }, sessions[label]);
      if (r.exceptionDetails) {
        throw new Error(`${label} evaluate[${expression.slice(0, 60)}] threw: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`);
      }
      return r.result.value;
    };
    const waitFor = async (label, expr, description, timeoutMs = args.timeoutMs) => {
      const t0 = Date.now();
      for (;;) {
        const v = await evl(label, expr);
        if (v) return v;
        if (Date.now() - t0 > timeoutMs) throw new Error(`${label}: timeout waiting ${description}`);
        await new Promise((r) => setTimeout(r, 250));
      }
    };

    // 两个页面就绪（ESM 加载完成 → window.__webDemo 出现）
    for (const label of ['A', 'B']) {
      await waitFor(label, 'Boolean(window.__webDemo && window.__webDemo.main && window.__webDemo.main.peer)', 'page bootstrap (window.__webDemo)');
    }
    const readyDetail = await Promise.all(['A', 'B'].map((l) => evl(l, `(() => {
      const m = window.__webDemo.main;
      return { secure: window.isSecureContext, media: m.mediaSource, hasPc: m.peer.pc !== null };
    })()`)));
    assertThat(readyDetail[0].secure === true && readyDetail[1].secure === true, 'media.secure-context',
      `A=${JSON.stringify(readyDetail[0])} B=${JSON.stringify(readyDetail[1])}`,
      'isSecureContext 必须为 true（http://127.0.0.1 属可信来源）');

    // A 建房 → B 入房
    const created = await evl('A', `(async () => {
      const m = window.__webDemo.main;
      m.client.url = ${JSON.stringify(args.signaling)};
      m.createRoom();
      m.client.connect();
      return true;
    })()`);
    need(created === true, 'A createRoom 调用失败');
    const roomId = await waitFor('A', 'window.__webDemo.main.roomId', 'A 收到 created（roomId）');
    assertThat(/^[A-HJ-KM-NP-Z2-9]{6}$/.test(roomId), 'media.create-created', `roomId=${roomId}（A 为房主）`, `roomId=${roomId}`);

    await evl('B', `(() => {
      const m = window.__webDemo.main;
      m.client.url = ${JSON.stringify(args.signaling)};
      const ok = m.joinRoom(${JSON.stringify(roomId)});
      m.client.connect();
      return ok;
    })()`);
    const bPeer = await waitFor('B', 'window.__webDemo.main.peerId ?? window.__webDemo.main.client?.peerId ?? null', 'B 收到 joined（peerId）');
    ok('media.join-joined', `B peerId=${bPeer} room=${roomId}`);

    // 等待 DTLS/ICE connected（两侧）
    const connExpr = `(() => { const pc = window.__webDemo.main.peer.pc; return pc ? pc.connectionState : null; })()`;
    await waitFor('A', `(${connExpr}) === 'connected'`, 'A connectionState=connected');
    await waitFor('B', `(${connExpr}) === 'connected'`, 'B connectionState=connected');
    ok('media.connection-state-connected', 'A 与 B 的 RTCPeerConnection.connectionState 均为 connected');

    const collectStats = `(async () => {
      const pc = window.__webDemo.main.peer.pc;
      const report = await pc.getStats();
      const out = [];
      report.forEach((s) => out.push(JSON.parse(JSON.stringify(s))));
      return out;
    })()`;
    const s0 = { A: await evl('A', collectStats), B: await evl('B', collectStats) };
    await new Promise((r) => setTimeout(r, 5000));
    const s1 = { A: await evl('A', collectStats), B: await evl('B', collectStats) };

    const byId = (rows) => new Map(rows.map((r) => [r.id, r]));
    // 我的独立归约：codecId → codec 表项 → mimeType
    const mimeOf = (rows, codecId) => {
      const codec = byId(rows).get(codecId);
      return codec && typeof codec.mimeType === 'string' ? codec.mimeType : null;
    };
    const pick = (rows, type, kind) => rows.filter((r) => r.type === type && (kind === undefined || r.kind === kind));

    // DTLS/ICE：transport + 选中候选对
    for (const label of ['A', 'B']) {
      const t = pick(s1[label], 'transport')[0] ?? null;
      const pairs = pick(s1[label], 'candidate-pair');
      const succeeded = pairs.filter((p) => p.state === 'succeeded' && (p.nominated === true || p.selected === true));
      assertThat(t !== null && t.dtlsState === 'connected' && t.iceState === 'connected', `media.transport-connected.${label}`,
        `transport dtls=${t ? t.dtlsState : 'n/a'} ice=${t ? t.iceState : 'n/a'}`,
        JSON.stringify(t).slice(0, 400));
      assertThat(succeeded.length >= 1, `media.candidate-pair-succeeded.${label}`,
        `succeeded+nominated pairs=${succeeded.length} states=${pairs.map((p) => p.state).join(',')}`,
        JSON.stringify(pairs.slice(0, 3)).slice(0, 400));
    }

    // VP9：outbound（A）与 inbound（B）都必须由 codecId 解析到 video/VP9
    const aOut = pick(s1.A, 'outbound-rtp', 'video')[0] ?? null;
    const aIn = pick(s1.A, 'inbound-rtp', 'video')[0] ?? null;
    const bOut = pick(s1.B, 'outbound-rtp', 'video')[0] ?? null;
    const bIn = pick(s1.B, 'inbound-rtp', 'video')[0] ?? null;
    assertThat(aOut !== null && mimeOf(s1.A, aOut.codecId) === 'video/VP9', 'media.codec-vp9-outbound.A',
      `A outbound codecId=${aOut ? aOut.codecId : 'n/a'} mime=${aOut ? mimeOf(s1.A, aOut.codecId) : 'n/a'}`);
    assertThat(bIn !== null && mimeOf(s1.B, bIn.codecId) === 'video/VP9', 'media.codec-vp9-inbound.B',
      `B inbound codecId=${bIn ? bIn.codecId : 'n/a'} mime=${bIn ? mimeOf(s1.B, bIn.codecId) : 'n/a'}`);
    assertThat(bOut !== null && mimeOf(s1.B, bOut.codecId) === 'video/VP9', 'media.codec-vp9-outbound.B',
      `B outbound codecId=${bOut ? bOut.codecId : 'n/a'} mime=${bOut ? mimeOf(s1.B, bOut.codecId) : 'n/a'}`);
    assertThat(aIn !== null && mimeOf(s1.A, aIn.codecId) === 'video/VP9', 'media.codec-vp9-inbound.A',
      `A inbound codecId=${aIn ? aIn.codecId : 'n/a'} mime=${aIn ? mimeOf(s1.A, aIn.codecId) : 'n/a'}`);

    // 双向字节增长（5 s 窗口）
    const dOutA = (aOut.bytesSent ?? 0) - ((pick(s0.A, 'outbound-rtp', 'video')[0] ?? {}).bytesSent ?? 0);
    const dInB = (bIn.bytesReceived ?? 0) - ((pick(s0.B, 'inbound-rtp', 'video')[0] ?? {}).bytesReceived ?? 0);
    const dOutB = (bOut.bytesSent ?? 0) - ((pick(s0.B, 'outbound-rtp', 'video')[0] ?? {}).bytesSent ?? 0);
    const dInA = (aIn.bytesReceived ?? 0) - ((pick(s0.A, 'inbound-rtp', 'video')[0] ?? {}).bytesReceived ?? 0);
    assertThat(dOutA > 0 && dInB > 0, 'media.bidirectional-rtp.AtoB', `A→B 5s 增长: bytesSent +${dOutA} / B inbound +${dInB}`);
    assertThat(dOutB > 0 && dInA > 0, 'media.bidirectional-rtp.BtoA', `B→A 5s 增长: bytesSent +${dOutB} / A inbound +${dInA}`);
    const framesEnc = (aOut.framesEncoded ?? 0) - ((pick(s0.A, 'outbound-rtp', 'video')[0] ?? {}).framesEncoded ?? 0);
    const framesDec = (bIn.framesDecoded ?? 0) - ((pick(s0.B, 'inbound-rtp', 'video')[0] ?? {}).framesDecoded ?? 0);
    assertThat(framesEnc > 0 && framesDec > 0, 'media.frames-flowing', `A framesEncoded +${framesEnc} / B framesDecoded +${framesDec}`);

    // 面板与导出（观测面：页面面板 + 控制台）
    const panelExpr = `(() => {
      const q = (s) => document.querySelector(s);
      const n = (s) => { const el = q(s); return el ? (el.children ? el.children.length : 0) : -1; };
      return {
        events: n('#events-list'), timeline: n('#timeline-body'), ice: n('#ice-body'),
        stats: n('#stats-body'), states: n('#state-body'),
        stateCurrent: (q('#state-current')?.textContent ?? '').slice(0, 60),
        sdpSummary: (q('#sdp-summary')?.textContent ?? '').slice(0, 60),
        sdpOffer: (q('#sdp-offer')?.textContent ?? '').length,
        roomShown: (q('#room-id')?.value ?? ''),
      };
    })()`;
    const panel = await evl('A', panelExpr);
    assertThat(panel.events > 0 && panel.timeline > 0 && panel.ice > 0 && panel.stats > 0 && panel.states > 0,
      'media.panels-populated', `A 面板行数 events=${panel.events} timeline=${panel.timeline} ice=${panel.ice} stats=${panel.stats} states=${panel.states}`);
    assertThat(panel.sdpOffer > 100, 'media.panel-sdp-visible', `A #sdp-offer 长度=${panel.sdpOffer} state="${panel.stateCurrent}" summary="${panel.sdpSummary}"`);
    const offerText = await evl('A', `document.querySelector('#sdp-offer').textContent`);
    assertThat(/VP9/.test(offerText), 'media.sdp-offer-contains-vp9', `A offer 文本含 VP9=${/VP9/.test(offerText)} len=${offerText.length}`);

    const exportExpr = `(() => {
      const m = window.__webDemo.main;
      const json = m.exportJSON();
      const size = JSON.stringify(json).length;
      const timelineCsv = m.timeline.toCSV();
      const candCsv = m.peer.candidates.toCSV();
      const statsCsv = m.stats.toCSV();
      return { size, timelineCsv: timelineCsv.length, candCsv: candCsv.length, statsCsv: statsCsv.length,
               keys: Object.keys(json).slice(0, 12) };
    })()`;
    let exp = null;
    try { exp = await evl('A', exportExpr); } catch (e) { bad('media.export-json', `导出调用抛错: ${e.message}`); }
    if (exp !== null) {
      assertThat(exp.size > 1000 && exp.timelineCsv > 100, 'media.export-json-and-csv',
        `exportJSON=${exp.size}B timeline.csv=${exp.timelineCsv}B candidates.csv=${exp.candCsv}B stats.csv=${exp.statsCsv}B keys=${exp.keys.join(',')}`);
    }
    assertThat(consoleCounts.A > 0 && consoleCounts.B > 0, 'media.console-observability',
      `控制台输出条数 A=${consoleCounts.A} B=${consoleCounts.B}`);

    // leave → 对端 peerLeft（页面级）：以**时间线**为准（面板文案是「对端离开 …」，不含 type 字面量）
    const beforeEvents = await evl('A', `document.querySelector('#events-list').children.length`);
    await evl('B', `window.__webDemo.main.leave(); true`);
    let peerLeftSeen = { count: 0, last: null, panel: '' };
    for (let i = 0; i < 16; i++) {
      peerLeftSeen = await evl('A', `(() => {
        const entries = window.__webDemo.main.timeline.toJSON().entries;
        const hit = entries.filter((e) => e.type === 'peerLeft');
        return {
          count: hit.length,
          last: hit.length ? JSON.stringify(hit[hit.length - 1]).slice(0, 220) : null,
          panel: document.querySelector('#events-list').textContent,
        };
      })()`);
      // 面板渲染是 250ms 节流的（app.js scheduleRender），必须轮询等它落地
      if (peerLeftSeen.count > 0 && peerLeftSeen.panel.includes('对端离开')) break;
      await new Promise((r) => setTimeout(r, 400));
    }
    const afterEvents = await evl('A', `document.querySelector('#events-list').children.length`);
    // 面板文案（app.js）：`对端离开 peer=<id>（面板回到「等待对端」…）`；#events-list 显示 main.events 末 120 条
    assertThat(peerLeftSeen.count > 0 && peerLeftSeen.panel.includes('对端离开'), 'media.leave-peerleft',
      `A 时间线 peerLeft 条数=${peerLeftSeen.count}；面板文案含「对端离开」=${peerLeftSeen.panel.includes('对端离开')}（事件行 ${beforeEvents}→${afterEvents}）last=${peerLeftSeen.last}`);
    await evl('A', `window.__webDemo.main.leave(); true`);
  } catch (e) {
    bad('media.harness', e && e.message ? e.message : String(e), e && e.evidence);
  } finally {
    for (const label of ['A', 'B']) {
      try { if (cdps[label]) cdps[label].close(); } catch { /* ignore */ }
      try { runs[label].child.kill('SIGKILL'); } catch { /* ignore */ }
      try { fs.rmSync(runs[label].profile, { recursive: true, force: true }); } catch { /* ignore */ }
    }
    server.close();
  }
}

// ---------------------------------------------------------------------------
async function main() {
  console.log(`# web-demo-independent (T5) phase=${args.phase} signaling=${args.signaling}`);
  console.log(`# node ${process.version} · repo ${REPO} · ${new Date().toISOString()}`);
  if (args.phase === 'all' || args.phase === 'modules') await phaseModules();
  if (args.phase === 'all' || args.phase === 'env') await phaseEnv();
  if (args.phase === 'all' || args.phase === 'media') await phaseMedia();
  console.log('');
  console.log(`summary: ${pass}/${pass + fail} passed   result: ${fail === 0 ? 'PASS' : 'FAIL'} (exit ${fail === 0 ? 0 : 1})`);
  if (fail > 0) {
    console.log('failures:');
    for (const f of failures) console.log(`  - ${f.id}: ${f.detail}`);
  }
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(`web-demo-independent: fatal: ${e && e.stack ? e.stack : e}`);
  process.exit(2);
});
