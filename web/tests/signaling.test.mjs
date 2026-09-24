#!/usr/bin/env node
// web/tests/signaling.test.mjs — 信令客户端与时间线的纯模块单测（容器内 `node`，无需网络/依赖）
//
// 运行：`node web/tests/signaling.test.mjs`（工作目录 = 仓库根）
// 覆盖（reports/70 §8 A-1）：编解码往返、未知键/缺失键、心跳与 pong 超时判定、
// 退避序列（1/2/4/8/8…、10 次 ≈63 s）、缓冲上限 32、按 type 分派顺序、close 1000 语义、
// 错误三分类、模拟掉线重连、时间线 JSON/CSV 导出。
//
// 输出：每条断言一行 PASS/FAIL，末尾一行 `=== PASS n, FAIL m ===`；有失败则退出码 1。
// 无 npm 依赖、无构建（H-3）；使用自写断言与虚拟时钟，使 15 s/5 s/1-2-4-8 s 全部可瞬时复现。

import {
  SignalingClient,
  SignalingCodecError,
  DEFAULT_SIGNALING_URL,
  ERROR_CODES,
  ErrorAction,
  MESSAGE_TYPES,
  MESSAGE_SCHEMAS,
  MAX_MESSAGE_SIZE,
  MAX_PENDING_MESSAGES,
  MAX_REJOIN_ATTEMPTS,
  NAT_TYPES,
  PING_INTERVAL_MS,
  PONG_FAIL_AFTER_MS,
  PONG_MISS_TOLERANCE,
  PONG_TIMEOUT_MS,
  REJOIN_RETRY_BASE_MS,
  REJOIN_RETRY_MAX_MS,
  SERVER_ONLY_TYPES,
  FORWARD_TYPES,
  WS_CLOSE_NORMAL,
  classifyError,
  decodeMessage,
  encodeMessage,
  frameToText,
  isForwardType,
  isKnownType,
  isValidNatType,
  isValidRoomId,
  isServerOnlyType,
  normalizeMessage,
  normalizeRoomId,
  peekType,
  pongMissed,
  pongTimeoutReached,
  rejoinBudgetMs,
  rejoinDelayMs,
  rejoinSchedule,
  signalingUrl,
} from '../lib/signaling.js';
import { Timeline, DIRECTION, LEVEL, csvCell, parseCandidate, summarizeMessage } from '../lib/timeline.js';

// ------------------------------------------------------------------ 断言框架

let passCount = 0;
let failCount = 0;
const failures = [];
let groupName = '';

function group(name) {
  groupName = name;
  console.log(`\n# ${name}`);
}

function check(name, ok, detail = '') {
  const label = groupName ? `${groupName} / ${name}` : name;
  if (ok) {
    passCount += 1;
    console.log(`PASS  ${label}${detail ? ` :: ${detail}` : ''}`);
  } else {
    failCount += 1;
    failures.push(label);
    console.log(`FAIL  ${label}${detail ? ` :: ${detail}` : ''}`);
  }
}

function deepEqual(a, b) {
  if (a === b) return true;
  if (typeof a !== typeof b) return false;
  if (a === null || b === null) return a === b;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((v, i) => deepEqual(v, b[i]));
  }
  if (typeof a === 'object') {
    const ka = Object.keys(a);
    const kb = Object.keys(b);
    if (ka.length !== kb.length) return false;
    return ka.every((k) => deepEqual(a[k], b[k]));
  }
  return false;
}

function eq(name, actual, expected) {
  check(name, deepEqual(actual, expected), `actual=${JSON.stringify(actual)} expected=${JSON.stringify(expected)}`);
}

function throws(name, fn, predicate = () => true) {
  let error = null;
  try {
    fn();
  } catch (err) {
    error = err;
  }
  check(name, error !== null && predicate(error), error === null ? '未抛错' : `${error.name}: ${error.message}`);
  return error;
}

function ok(name, value, detail = '') {
  check(name, value === true, detail || `value=${String(value)}`);
}

// ------------------------------------------------------------------ 虚拟时钟 / 假 socket

/** 虚拟毫秒时钟：`advance(ms)` 按「时刻升序、同刻按注册顺序」执行到期定时器。 */
class VirtualClock {
  constructor(start = 1_700_000_000_000) {
    this.t = start;
    this.seq = 0;
    this.timers = new Map();
    this.now = () => this.t;
    this.setTimeout = (fn, ms) => {
      const id = ++this.seq;
      this.timers.set(id, { at: this.t + Math.max(0, ms), fn, seq: id });
      return id;
    };
    this.clearTimeout = (id) => {
      this.timers.delete(id);
    };
  }

  get pending() {
    return this.timers.size;
  }

  /** 推进到 `t + ms`，途中执行所有到期定时器（含执行中新增的）。 */
  advance(ms) {
    const target = this.t + ms;
    for (;;) {
      let pickId = null;
      let pick = null;
      for (const [id, timer] of this.timers) {
        if (timer.at > target) continue;
        if (pick === null || timer.at < pick.at || (timer.at === pick.at && timer.seq < pick.seq)) {
          pick = timer;
          pickId = id;
        }
      }
      if (pick === null) break;
      this.timers.delete(pickId);
      this.t = pick.at;
      pick.fn();
    }
    this.t = target;
  }
}

/** 假 WebSocket：测试显式驱动 open/message/close 事件，以精确控制时序。 */
class FakeSocket {
  constructor(url) {
    this.url = url;
    this.readyState = 0; // CONNECTING
    this.sent = [];
    this.closeCalls = 0;
    this.closed = null;
    this.onopen = null;
    this.onmessage = null;
    this.onclose = null;
    this.onerror = null;
  }

  send(raw) {
    if (this.readyState !== 1) throw new Error('FakeSocket: not open');
    this.sent.push(raw);
  }

  close(code = WS_CLOSE_NORMAL, reason = '') {
    this.closeCalls += 1;
    this.readyState = 3;
    this.closed = { code, reason };
  }

  // ---- 测试驱动 ----
  fireOpen() {
    this.readyState = 1;
    if (this.onopen) this.onopen({});
  }

  fireMessage(raw) {
    if (this.onmessage) this.onmessage({ data: raw });
  }

  fireClose(code = 1006, reason = '', wasClean = code === WS_CLOSE_NORMAL) {
    this.readyState = 3;
    if (this.onclose) this.onclose({ code, reason, wasClean });
  }

  fireError(message = 'boom') {
    if (this.onerror) this.onerror({ message });
  }

  frames() {
    return this.sent.map((raw) => JSON.parse(raw));
  }
}

/** 建一个可观测的客户端：返回 client + 场景对象（sockets、事件记录、时钟）。 */
function makeClient(options = {}) {
  const clock = new VirtualClock(options.startAt);
  const sockets = [];
  const events = {};
  for (const name of [
    'open', 'close', 'closed', 'sent', 'message', 'dropped', 'msgBlocked', 'protocolError',
    'serverError', 'terminal', 'pongMiss', 'linkFailure', 'reconnectScheduled', 'reconnectSkipped',
    'reconnectGiveUp', 'rejoinScheduled', 'rejoinGiveUp', 'dropSimulated', 'bufferOverflow', 'stateChanged',
  ]) {
    events[name] = [];
  }
  const record = (name) => (payload) => {
    events[name].push(payload);
  };
  const client = new SignalingClient({
    url: options.url ?? 'ws://signal.test:8443/ws',
    socketFactory: (url) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket;
    },
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    logger: null,
    timeline: options.timeline,
  });
  for (const name of [
    'open', 'close', 'closed', 'sent', 'message', 'dropped', 'msgBlocked', 'protocolError',
    'serverError', 'terminal', 'pongMiss', 'linkFailure', 'reconnectScheduled', 'reconnectSkipped',
    'reconnectGiveUp', 'rejoinScheduled', 'rejoinGiveUp', 'dropSimulated', 'bufferOverflow', 'stateChanged',
  ]) {
    client.onEvent(name, record(name));
  }
  const listen = (type, handler = () => {}) => client.on(type, handler);
  return { client, clock, sockets, events, record, listen };
}

// ============================================================================
// 1. 常量与纯函数
// ============================================================================

group('constants');

eq('PING_INTERVAL_MS = 15000', PING_INTERVAL_MS, 15000);
eq('PONG_TIMEOUT_MS = 5000', PONG_TIMEOUT_MS, 5000);
eq('PONG_MISS_TOLERANCE = 4', PONG_MISS_TOLERANCE, 4);
eq('PONG_FAIL_AFTER_MS = 20000', PONG_FAIL_AFTER_MS, 20000);
eq('REJOIN_RETRY_BASE_MS = 1000', REJOIN_RETRY_BASE_MS, 1000);
eq('REJOIN_RETRY_MAX_MS = 8000', REJOIN_RETRY_MAX_MS, 8000);
eq('MAX_REJOIN_ATTEMPTS = 10', MAX_REJOIN_ATTEMPTS, 10);
eq('MAX_PENDING_MESSAGES = 32', MAX_PENDING_MESSAGES, 32);
eq('MAX_MESSAGE_SIZE = 64 KiB', MAX_MESSAGE_SIZE, 65536);
eq('WS_CLOSE_NORMAL = 1000', WS_CLOSE_NORMAL, 1000);
eq('MESSAGE_TYPES 长度 = 14', MESSAGE_TYPES.length, 14);
eq('MESSAGE_SCHEMAS 覆盖全部 14 类', Object.keys(MESSAGE_SCHEMAS).sort(), [...MESSAGE_TYPES].sort());
eq('NAT 枚举 6 值', NAT_TYPES.length, 6);
eq('服务端独占类型', SERVER_ONLY_TYPES, ['created', 'joined', 'error', 'peerJoined', 'peerLeft', 'pong']);
eq('转发类型', FORWARD_TYPES, ['offer', 'answer', 'ice', 'natType']);
ok('isKnownType("offer")', isKnownType('offer'));
ok('!isKnownType("bogus")', isKnownType('bogus') === false);
ok('isServerOnlyType("pong")', isServerOnlyType('pong'));
ok('isForwardType("ice")', isForwardType('ice'));
ok('isValidNatType("PortRestrictedCone")', isValidNatType('PortRestrictedCone'));
ok('!isValidNatType("Nope")', isValidNatType('Nope') === false);

group('endpoint + room id');
eq('signalingUrl 补 /ws', signalingUrl('ws://47.238.144.66:8443'), 'ws://47.238.144.66:8443/ws');
eq('signalingUrl 幂等', signalingUrl('ws://47.238.144.66:8443/ws'), 'ws://47.238.144.66:8443/ws');
eq('signalingUrl 去尾斜杠', signalingUrl('ws://host:8443///'), 'ws://host:8443/ws');
eq('signalingUrl 默认值', signalingUrl(''), DEFAULT_SIGNALING_URL);
eq('默认端点不是 127.0.0.1', DEFAULT_SIGNALING_URL.includes('127.0.0.1'), false);
eq('normalizeRoomId 去杂字并大写', normalizeRoomId(' k9-ju cd '), 'K9JUCD');
eq('normalizeRoomId 截断 6 位', normalizeRoomId('ABCDEFGHJK'), 'ABCDEF');
eq('normalizeRoomId 剔除 I/L/O/0/1', normalizeRoomId('I1L0O23'), '23');
ok('isValidRoomId("K9JUCD")', isValidRoomId('K9JUCD'));
ok('!isValidRoomId("K9JUC")', isValidRoomId('K9JUC') === false);

group('rejoin backoff');
eq('退避序列 1/2/4/8/8…', [1, 2, 3, 4, 5, 6, 11].map(rejoinDelayMs), [1000, 2000, 4000, 8000, 8000, 8000, 8000]);
eq('退避序列（10 次）', rejoinSchedule(), [1000, 2000, 4000, 8000, 8000, 8000, 8000, 8000, 8000, 8000]);
eq('累计预算 = 63000 ms（63 s）', rejoinBudgetMs(), 63000);
ok('预算 > 服务端 45 s 读超时', rejoinBudgetMs() > 45000);
ok('预算 < 90 s 席位宽限期', rejoinBudgetMs() < 90000);
ok('单次退避 <= 8 s', rejoinSchedule().every((d) => d <= REJOIN_RETRY_MAX_MS));

group('pong liveness');
ok('窗口内未超时不判 miss', pongMissed(15000 + 5000, 15000, 0) === false);
ok('超过窗口且无 pong 判 miss', pongMissed(15000 + 5001, 15000, 0) === true);
ok('已收到本窗口 pong 则不判 miss', pongMissed(15000 + 9000, 15000, 20000) === false);
ok('无待回应 ping 不判 miss', pongMissed(99999, 0, 0) === false);
ok('pongTimeoutReached(3) = false', pongTimeoutReached(3) === false);
ok('pongTimeoutReached(4) = true', pongTimeoutReached(4) === true);

group('error policy');
eq('ROOM_NOT_FOUND → TERMINAL', classifyError(ERROR_CODES.ROOM_NOT_FOUND).action, ErrorAction.TERMINAL_SUPPRESS);
eq('ROOM_EXPIRED → TERMINAL', classifyError(ERROR_CODES.ROOM_EXPIRED).action, ErrorAction.TERMINAL_SUPPRESS);
eq('INVALID_MESSAGE → TERMINAL', classifyError(ERROR_CODES.INVALID_MESSAGE).action, ErrorAction.TERMINAL_SUPPRESS);
eq('NOT_IN_ROOM → TERMINAL', classifyError(ERROR_CODES.NOT_IN_ROOM).action, ErrorAction.TERMINAL_SUPPRESS);
eq('ROOM_FULL + 掉线重连 → RETRY_REJOIN', classifyError(ERROR_CODES.ROOM_FULL, { rejoinAfterDrop: true }).action, ErrorAction.RETRY_REJOIN);
eq('ROOM_FULL 首次入房 → SURFACE', classifyError(ERROR_CODES.ROOM_FULL, { rejoinAfterDrop: false }).action, ErrorAction.SURFACE);
eq('未知码 → SURFACE', classifyError('WHATEVER').action, ErrorAction.SURFACE);
ok('RETRY_REJOIN 保留房间意图', classifyError(ERROR_CODES.ROOM_FULL, { rejoinAfterDrop: true }).clearsRoomIntent === false);
ok('TERMINAL_SUPPRESS 清空房间意图', classifyError(ERROR_CODES.ROOM_NOT_FOUND).clearsRoomIntent === true);

// ============================================================================
// 2. 编解码
// ============================================================================

group('codec: round trip 14 类');

const SAMPLES = {
  create: { type: 'create' },
  created: { type: 'created', roomId: 'K9JUCD', stunUrl: 'stun:47.238.144.66:3478', turnUrl: 'turn:47.238.144.66:3478?transport=udp', turnUsername: 'demo', turnCredential: 'demopass' },
  join: { type: 'join', roomId: 'K9JUCD' },
  joined: { type: 'joined', roomId: 'K9JUCD', stunUrl: 'stun:47.238.144.66:3478', turnUrl: 'turn:47.238.144.66:3478?transport=udp', turnUsername: 'demo', turnCredential: 'demopass', peerId: 'peer-002' },
  peerJoined: { type: 'peerJoined', peerId: 'peer-002' },
  peerLeft: { type: 'peerLeft', peerId: 'peer-002' },
  offer: { type: 'offer', sdp: 'v=0\r\na=group:BUNDLE 0\r\n' },
  answer: { type: 'answer', sdp: 'v=0\r\na=group:BUNDLE 0\r\n' },
  ice: { type: 'ice', candidate: 'candidate:1 1 udp 2122260223 192.168.1.7 51234 typ host', sdpMid: '0', sdpMLineIndex: 0 },
  natType: { type: 'natType', natType: 'FullCone' },
  leave: { type: 'leave' },
  ping: { type: 'ping', timestamp: 1790170970723 },
  pong: { type: 'pong', timestamp: 1790170970723 },
  error: { type: 'error', code: 'ROOM_NOT_FOUND', message: 'Room ZZZZZZ does not exist' },
};

for (const type of MESSAGE_TYPES) {
  const sample = SAMPLES[type];
  const encoded = encodeMessage(sample);
  const decoded = decodeMessage(encoded);
  eq(`${type}: encode→decode 往返一致`, decoded, normalizeMessage(sample));
}

eq('create 编码 = {"type":"create"}', encodeMessage({ type: 'create' }), '{"type":"create"}');
eq('leave 编码 = {"type":"leave"}', encodeMessage({ type: 'leave' }), '{"type":"leave"}');
eq('ping 编码字段顺序 type→timestamp', encodeMessage('ping', { timestamp: 123 }), '{"type":"ping","timestamp":123}');
eq('join 编码字段顺序 type→roomId', encodeMessage('join', { roomId: 'K9JUCD' }), '{"type":"join","roomId":"K9JUCD"}');
eq('created 字段名逐字一致', Object.keys(decodeMessage(encodeMessage(SAMPLES.created))), ['type', 'roomId', 'stunUrl', 'turnUrl', 'turnUsername', 'turnCredential']);
eq('joined 含 peerId', Object.keys(decodeMessage(encodeMessage(SAMPLES.joined))), ['type', 'roomId', 'stunUrl', 'turnUrl', 'turnUsername', 'turnCredential', 'peerId']);
eq('ice 仅 candidate（可缺省字段全空）', decodeMessage('{"type":"ice","candidate":"c:1"}'), { type: 'ice', candidate: 'c:1' });
eq('ice sdpMLineIndex=0 保留（不等于缺失）', decodeMessage('{"type":"ice","candidate":"c:1","sdpMLineIndex":0}'), { type: 'ice', candidate: 'c:1', sdpMLineIndex: 0 });
eq('ice sdpMid 单独存在', decodeMessage('{"type":"ice","candidate":"c:1","sdpMid":"0"}'), { type: 'ice', candidate: 'c:1', sdpMid: '0' });
eq('ice null 等价于缺省', decodeMessage('{"type":"ice","candidate":"c:1","sdpMid":null,"sdpMLineIndex":null}'), { type: 'ice', candidate: 'c:1' });

group('codec: 未知键 / 缺失键 / 非法帧');

eq('未知键被忽略（不进入结果）', decodeMessage('{"type":"joined","roomId":"K9JUCD","stunUrl":"s","turnUrl":"t","turnUsername":"u","turnCredential":"p","peerId":"peer-002","extra":1}'), { type: 'joined', roomId: 'K9JUCD', stunUrl: 's', turnUrl: 't', turnUsername: 'u', turnCredential: 'p', peerId: 'peer-002' });
eq('非本 type 的已知字段也被忽略', decodeMessage('{"type":"offer","sdp":"x","roomId":"K9JUCD"}'), { type: 'offer', sdp: 'x' });
throws('join 缺 roomId → 抛错', () => decodeMessage('{"type":"join"}'), (e) => e instanceof SignalingCodecError && e.field === 'roomId');
throws('created 缺 turnCredential → 抛错', () => decodeMessage('{"type":"created","roomId":"K9JUCD","stunUrl":"s","turnUrl":"t","turnUsername":"u"}'), (e) => e.code === ERROR_CODES.INVALID_MESSAGE);
throws('offer 缺 sdp → 抛错', () => decodeMessage('{"type":"offer"}'));
throws('ice 缺 candidate → 抛错', () => decodeMessage('{"type":"ice","sdpMid":"0"}'));
throws('error 缺 message → 抛错', () => decodeMessage('{"type":"error","code":"ROOM_NOT_FOUND"}'));
throws('缺 type → 抛错', () => decodeMessage('{"roomId":"K9JUCD"}'));
throws('type 非字符串 → 抛错', () => decodeMessage('{"type":123}'));
throws('顶层是数组 → 抛错', () => decodeMessage('[{"type":"create"}]'));
throws('顶层是字符串 → 抛错', () => decodeMessage('"create"'));
throws('非法 JSON → 抛错', () => decodeMessage('{oops'));
throws('字段类型错误（roomId 为数字）→ 抛错', () => decodeMessage('{"type":"join","roomId":123}'));
throws('字段为空字符串（sdp=""）→ 抛错', () => decodeMessage('{"type":"offer","sdp":""}'));
throws('timestamp 非数字 → 抛错', () => decodeMessage('{"type":"ping","timestamp":"now"}'));

const unknownErr = throws('未知 type → 可恢复错误', () => decodeMessage('{"type":"bogus"}'), (e) => e.unknownType === true);
ok('未知 type 标记 recoverable（不得中断连接）', unknownErr !== null && unknownErr.recoverable === true);
ok('未知 type 使用 INVALID_MESSAGE 码', unknownErr !== null && unknownErr.code === ERROR_CODES.INVALID_MESSAGE);

eq('peekType 可取到未知 type', peekType('{"type":"bogus"}'), 'bogus');
eq('peekType 非法 JSON → <invalid>', peekType('{oops'), '<invalid>');
eq('frameToText 透传字符串', frameToText('{"type":"create"}'), '{"type":"create"}');
eq('frameToText 解码 ArrayBuffer', frameToText(new TextEncoder().encode('{"type":"leave"}').buffer), '{"type":"leave"}');

// ============================================================================
// 3. 客户端：连接 / 分派 / 缓冲
// ============================================================================

group('client: connect 与状态机');

{
  const { client, sockets, events } = makeClient();
  ok('初始状态 idle', client.state === 'idle');
  ok('connect() 返回 true', client.connect() === true);
  eq('只创建一个 socket', sockets.length, 1);
  eq('连接中状态 connecting', client.state, 'connecting');
  sockets[0].fireOpen();
  eq('打开后状态 connected', client.state, 'connected');
  eq('open 事件一次', events.open.length, 1);
  eq('stateChanged 序列', events.stateChanged.map((e) => `${e.from}→${e.to}`), ['idle→connecting', 'connecting→connected']);

  ok('createRoom 发 create', client.createRoom() === true);
  eq('socket 收到 create', sockets[0].frames(), [{ type: 'create' }]);

  sockets[0].fireMessage(JSON.stringify(SAMPLES.created));
  eq('created → in_room', client.state, 'in_room');
  eq('记录 roomId', client.currentRoomId, 'K9JUCD');
  eq('sent 事件 1 条', events.sent.length, 1);

  sockets[0].fireMessage(JSON.stringify(SAMPLES.peerJoined));
  eq('peerJoined → in_call', client.state, 'in_call');
}

group('client: 按 type 分派（不依赖到达顺序）');

{
  const { client, sockets, listen } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  client.joinRoom('K9JUCD');
  sockets[0].fireMessage(JSON.stringify(SAMPLES.joined));

  const order = [];
  listen('offer', () => order.push('offer'));
  listen('ice', () => order.push('ice'));
  listen('peerLeft', () => order.push('peerLeft'));

  // 刻意交错到达并让 offer 后于 ice 出现
  sockets[0].fireMessage(JSON.stringify({ type: 'ice', candidate: 'c:1' }));
  sockets[0].fireMessage(JSON.stringify({ type: 'offer', sdp: 'v=0' }));
  sockets[0].fireMessage(JSON.stringify({ type: 'ice', candidate: 'c:2' }));
  sockets[0].fireMessage(JSON.stringify(SAMPLES.peerLeft));
  sockets[0].fireMessage(JSON.stringify({ type: 'offer', sdp: 'v=0-2' }));

  eq('分派顺序 = 到达顺序（各 type 独立）', order, ['ice', 'offer', 'ice', 'peerLeft', 'offer']);
  eq('offer 两次都到达', order.filter((x) => x === 'offer').length, 2);

  // 'joined' 此前无监听器 ⇒ 仍在缓冲里；注册通配符时应被补投，随后新帧继续到达
  const wildTypes = [];
  client.on('*', (message) => wildTypes.push(message.type));
  eq('通配符注册时补投缓冲里的 joined', wildTypes, ['joined']);
  sockets[0].fireMessage(JSON.stringify(SAMPLES.natType));
  eq('通配符随后收到新帧', wildTypes, ['joined', 'natType']);
}

group('client: 待发缓冲（上限 32，丢最旧，注册后按序补投）');

{
  const { client, sockets } = makeClient();
  client.connect();
  sockets[0].fireOpen();

  for (let i = 1; i <= 40; i += 1) {
    sockets[0].fireMessage(JSON.stringify({ type: 'natType', natType: i % 2 === 0 ? 'FullCone' : 'Open', marker: i }));
  }
  eq('缓冲长度上限 = 32', client.pendingCount, 32);
  eq('丢弃计数 = 8', client.droppedPending, 8);

  const seen = [];
  client.on('natType', (message) => seen.push(message.natType));
  eq('注册后一次性补投 32 条', seen.length, 32);
  eq('补投后缓冲清空', client.pendingCount, 0);
  eq('保留的是最后 32 条（下标 9..40）', (() => {
    // marker 不在 schema 中，会被忽略；改用顺序断言：第 9..40 条为奇数/偶数交替，首条应为 marker=9 → Open
    return seen[0];
  })(), 'Open');

  // 逐条到达顺序不可分辨时，用 offer 的 sdp 内容断言顺序
  const order = [];
  client.on('offer', (_m, raw) => order.push(JSON.parse(raw).sdp));
  sockets[0].fireMessage(JSON.stringify({ type: 'offer', sdp: 's1' }));
  sockets[0].fireMessage(JSON.stringify({ type: 'offer', sdp: 's2' }));
  sockets[0].fireMessage(JSON.stringify({ type: 'offer', sdp: 's3' }));
  eq('已注册监听器按到达顺序直投', order, ['s1', 's2', 's3']);

  const overflow = [];
  client.onEvent('bufferOverflow', (p) => overflow.push(p));
  for (let i = 0; i < 40; i += 1) sockets[0].fireMessage(JSON.stringify({ type: 'pong', timestamp: i }));
  eq('未注册类型的消息进入缓冲', client.pendingCount, 32);
  eq('溢出事件 = 8 次', overflow.length, 8);
}

group('client: 未入房拦截媒体信令');

{
  const { client, sockets, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  ok('未入房 sendOffer 返回 false', client.sendOffer('v=0') === false);
  ok('未入房 sendIce 返回 false', client.sendIce('c:1') === false);
  eq('msgBlocked 事件（offer/ice）', events.msgBlocked.map((e) => e.type), ['offer', 'ice']);
  eq('未写入 socket', sockets[0].sent.length, 0);
  client.joinRoom('K9JUCD');
  sockets[0].fireMessage(JSON.stringify(SAMPLES.joined));
  ok('入房后 sendOffer 成功', client.sendOffer('v=0') === true);
  eq('offer 帧内容', sockets[0].frames()[1], { type: 'offer', sdp: 'v=0' });
  ok('入房后 sendIce(sdpMLineIndex=0) 成功', client.sendIce('c:1', null, 0) === true);
  eq('ice 帧保留 mLineIndex 0', sockets[0].frames()[2], { type: 'ice', candidate: 'c:1', sdpMLineIndex: 0 });
}

group('client: 未知帧/非法帧不中断连接');

{
  const { client, sockets, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  const seen = [];
  client.on('pong', (m) => seen.push(m.timestamp));
  sockets[0].fireMessage('{not json');
  sockets[0].fireMessage(JSON.stringify({ type: 'bogus', x: 1 }));
  eq('协议错误事件 2 条', events.protocolError.length, 2);
  ok('连接仍为 connected', client.state === 'connected' || client.state === 'in_room');
  sockets[0].fireMessage(JSON.stringify({ type: 'pong', timestamp: 42 }));
  eq('后续合法帧仍被分派', seen, [42]);
  ok('未知帧记入时间线', client.timeline.entries.some((e) => e.type === 'bogus' && e.level === LEVEL.ERROR));
}

// ============================================================================
// 4. 心跳与判活
// ============================================================================

group('heartbeat: 15 s ping / 5 s 窗口 / 连续 4 次才断线');

{
  const { client, sockets, clock, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  eq('打开后尚未发 ping', sockets[0].frames().length, 0);

  clock.advance(14999);
  eq('t=14.999s 仍未发 ping', sockets[0].frames().length, 0);
  clock.advance(1);
  eq('t=15s 发第一个 ping', sockets[0].frames(), [{ type: 'ping', timestamp: clock.t }]);

  // 回 pong ⇒ 计数归零
  sockets[0].fireMessage(JSON.stringify({ type: 'pong', timestamp: clock.t }));
  eq('pong 后 lastPongAtMs 更新', client.lastPongAtMs, clock.t);
  eq('pong 后连续 miss = 0', client.consecutivePongMisses, 0);

  clock.advance(15000);
  eq('t=30s 发第二个 ping', sockets[0].frames().length, 2);
}

{
  const { client, sockets, clock, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  clock.advance(25000); // 15s ping → 20s 窗口未超时 → 25s 首次 miss
  eq('第一次 miss 计数 = 1', client.consecutivePongMisses, 1);
  eq('pongMiss 事件 1 次', events.pongMiss.length, 1);
  eq('单次 miss 不判链路失效', events.linkFailure.length, 0);
  ok('仍为 connected（只记事件）', client.state === 'connected');

  clock.advance(15000); // 40s → 第二次 miss
  eq('第二次 miss 计数 = 2', client.consecutivePongMisses, 2);
  eq('仍未判失效', events.linkFailure.length, 0);

  clock.advance(15000); // 55s → 第三次 miss
  eq('第三次 miss 计数 = 3', client.consecutivePongMisses, 3);
  eq('仍未判失效（容忍 4）', events.linkFailure.length, 0);

  clock.advance(15000); // 70s → 第四次 miss ⇒ 判失效
  eq('第四次 miss 触发 linkFailure', events.linkFailure.length, 1);
  eq('失效原因 = pong_timeout', events.linkFailure[0].reason, 'pong_timeout');
  eq('失效时 misses = 4', events.linkFailure[0].count, 4);
  eq('失效阈值字段 = 20000', events.linkFailure[0].failAfterMs, 20000);
  eq('失效后进入重连', client.state, 'reconnecting');
  eq('失效后已排程一次重连', events.reconnectScheduled.length, 1);
  eq('重连退避 = 1 s', events.reconnectScheduled[0].delayMs, 1000);
}

{
  const { client, sockets, clock, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  clock.advance(25000);
  eq('先制造一次 miss', client.consecutivePongMisses, 1);
  sockets[0].fireMessage(JSON.stringify({ type: 'pong', timestamp: clock.t }));
  eq('pong 清零连续 miss', client.consecutivePongMisses, 0);
  clock.advance(15000); // 40s：lastPongAt=25s < 30s 的 ping ⇒ 重新从 1 开始
  eq('pong 后重新计数（=1，而非 2）', client.consecutivePongMisses, 1);
  eq('链路未失效', events.linkFailure.length, 0);
}

// ============================================================================
// 5. 重连 / close 语义 / 错误处置
// ============================================================================

group('reconnect: 模拟掉线 → 退避重连 → 拿回席位');

{
  const { client, sockets, clock, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  client.createRoom();
  sockets[0].fireMessage(JSON.stringify(SAMPLES.created));
  eq('建房后 in_room', client.state, 'in_room');

  ok('simulateDrop() 断开', client.simulateDrop() === true);
  eq('掉线只关闭 WS（不发 leave）', sockets[0].frames(), [{ type: 'create' }]);
  eq('drop 触发一次 socket.close', sockets[0].closeCalls, 1);
  eq('dropSimulated 事件', events.dropSimulated.length, 1);

  sockets[0].fireClose(1006, 'abnormal');
  eq('异常关闭触发重连排程', events.reconnectScheduled.length, 1);
  eq('第 1 次退避 = 1000 ms', events.reconnectScheduled[0].delayMs, 1000);
  ok('掉线前在房内 ⇒ rejoinAfterDrop', client.rejoinAfterDrop === true);

  clock.advance(1000);
  eq('退避后创建新 socket', sockets.length, 2);
  eq('新 socket 已建立（尚未 open）', sockets[1].readyState, 0);
  sockets[1].fireOpen();
  eq('重连后 socket 打开 = connected', client.state, 'connected');
  eq('重连后用原 roomId 重新 join', JSON.parse(sockets[1].sent[0]), { type: 'join', roomId: 'K9JUCD' });
  ok('重连语境保持到重新拿到 joined 为止', client.rejoinAfterDrop === true);
  sockets[1].fireMessage(JSON.stringify(SAMPLES.joined));
  eq('重连成功再次 joined → in_room', client.state, 'in_room');
  eq('拿回原 peerId', client.peerId, 'peer-002');
  eq('成功连接后重连计数归零', client.reconnectAttempts, 0);
  eq('拿到 joined 后脱离重连语境', client.rejoinAfterDrop, false);
}

group('reconnect: close 1000 正常关闭不重连（B-6）');

{
  const { client, sockets, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  sockets[0].fireMessage(JSON.stringify(SAMPLES.created));
  sockets[0].fireClose(1000, 'normal', true);
  eq('1000 → 状态 closed', client.state, 'closed');
  eq('1000 → 不排程重连', events.reconnectScheduled.length, 0);
  eq('closed 事件带 reconnect=false', events.closed[0].reconnect, false);
}

group('reconnect: 上限 10 次 + 累计预算 63 s');

{
  const { client, sockets, clock, events } = makeClient();
  const start = clock.t;
  client.connect();
  sockets[0].fireOpen();
  client.simulateDrop();
  sockets[0].fireClose(1006);

  // 第 1 次已排程（1 s）；此后每次开的新 socket 都立刻异常关闭 ⇒ 推进到第 11 次尝试触发放弃
  for (let attempt = 2; attempt <= 11; attempt += 1) {
    const delay = rejoinDelayMs(attempt - 1);
    clock.advance(delay);
    const socket = sockets[sockets.length - 1];
    if (socket.readyState !== 3) socket.fireClose(1006);
  }

  eq('共创建 11 个 socket（1 原始 + 10 次重连）', sockets.length, 11);
  eq('放弃事件 1 次', events.reconnectGiveUp.length, 1);
  eq('放弃时尝试序号 = 11', events.reconnectGiveUp[0].attempts, 11);
  eq('放弃时预算字段 = 63000', events.reconnectGiveUp[0].budgetMs, 63000);
  eq('放弃后状态 failed', client.state, 'failed');
  ok('reconnectExhausted 置真', client.reconnectExhausted === true);
  eq('实际消耗时间 = 63 s', clock.t - start, 63000);
  eq('重连次数不超过 10 次', events.reconnectScheduled.length, 10);
}

group('reconnect: 重建前先关闭旧 socket（B-6）');

{
  const { client, sockets, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  const first = sockets[0];
  ok('直接重建（openSocket）', client.openSocket() === true);
  eq('旧 socket 被关闭一次', first.closeCalls, 1);
  eq('新 socket 已创建', sockets.length, 2);
  eq('当前 socket 指向新连接', client.socket === sockets[1], true);
  first.fireClose(1006); // 陈旧 socket 的 close 事件
  eq('陈旧 socket 的 close 不影响当前连接', events.reconnectScheduled.length, 0);
  eq('陈旧 close 不改变状态', client.state, 'connecting');
}

group('errors: 终态 / ROOM_FULL 有界重试 / SURFACE');

{
  const { client, sockets, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  client.joinRoom('ZZZZZZ');
  sockets[0].fireMessage(JSON.stringify({ type: 'error', code: 'ROOM_NOT_FOUND', message: 'Room ZZZZZZ does not exist' }));
  eq('终态事件 1 次', events.terminal.length, 1);
  eq('终态码', events.terminal[0].code, 'ROOM_NOT_FOUND');
  ok('终态：抑制重连', client.reconnectSuppressed === true);
  ok('终态：清空房间意图', client.pendingRoomId === null && client.currentRoomId === null);
  ok('终态：可读结论', client.terminalReason.includes('房间已失效'), client.terminalReason);
  eq('终态后状态 failed', client.state, 'failed');

  sockets[0].fireClose(1006);
  eq('终态后断线不再重连', events.reconnectScheduled.length, 0);
  eq('重连被跳过事件', events.reconnectSkipped.length, 1);
  eq('跳过原因 = terminal_error', events.reconnectSkipped[0].why, 'terminal_error');
}

{
  const { client, sockets, clock, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  client.createRoom();
  sockets[0].fireMessage(JSON.stringify(SAMPLES.created));
  client.simulateDrop();
  sockets[0].fireClose(1006);
  eq('掉线后进入重连语境', client.rejoinAfterDrop, true);

  // 第 10 次重连的 socket 收到 ROOM_FULL ⇒ 有界重试 join
  clock.advance(1000);
  const second = sockets[1];
  second.fireOpen();
  second.fireMessage(JSON.stringify({ type: 'error', code: 'ROOM_FULL', message: 'Room is full (max 2 peers)' }));
  eq('ROOM_FULL（重连语境）→ 排程 rejoin 重试', events.rejoinScheduled.length, 1);
  eq('rejoin 退避 = 1000 ms', events.rejoinScheduled[0].delayMs, 1000);
  ok('保留房间意图（pendingRoomId 不变）', client.pendingRoomId === 'K9JUCD');
  ok('未抑制重连', client.reconnectSuppressed === false);
  eq('未清空 currentRoomId', client.currentRoomId, 'K9JUCD');

  clock.advance(1000);
  sockets[sockets.length - 1].fireOpen();
  eq('重试后再次 join 原房间', JSON.parse(sockets[sockets.length - 1].sent[0]), { type: 'join', roomId: 'K9JUCD' });
}

{
  const { client, sockets, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  client.joinRoom('K9JUCD');
  sockets[0].fireMessage(JSON.stringify({ type: 'error', code: 'ROOM_FULL', message: 'Room is full (max 2 peers)' }));
  eq('首次入房遇 ROOM_FULL → 仅呈现', events.serverError.length, 1);
  eq('首次入房不重试', events.rejoinScheduled.length, 0);
  ok('不抑制重连', client.reconnectSuppressed === false);

  sockets[0].fireMessage(JSON.stringify({ type: 'error', code: 'INTERNAL_ERROR', message: 'Internal server error' }));
  eq('未知/内部码 → 仅呈现（第 2 条）', events.serverError.length, 2);
  eq('SURFACE 动作', events.serverError[1].action, ErrorAction.SURFACE);
  ok('不推进为 failed', client.state !== 'failed', client.state);
}

group('leave: 发 leave → 关闭 → 不重连（B-9）');

{
  const { client, sockets, events } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  client.joinRoom('K9JUCD');
  sockets[0].fireMessage(JSON.stringify(SAMPLES.joined));
  ok('leave() 发出 leave 帧', client.leave() === true);
  eq('socket 收到 leave', sockets[0].frames().pop(), { type: 'leave' });
  eq('leave 后关闭 socket', sockets[0].closeCalls, 1);
  eq('leave 后状态 closed', client.state, 'closed');
  eq('leave 后不重连', events.reconnectScheduled.length, 0);
  sockets[0].fireClose(1006);
  eq('leave 后陈旧 close 也不再重连', events.reconnectScheduled.length, 0);
}

// ============================================================================
// 6. 时间线
// ============================================================================

group('timeline: 记录 / JSON / CSV / 清空');

{
  const tl = new Timeline({ now: monotonicStub(), wallClock: () => 1700000000000 });
  const a = tl.record({ direction: DIRECTION.SEND, type: 'join', raw: '{"type":"join","roomId":"K9JUCD"}', summary: summarizeMessage('join', { type: 'join', roomId: 'K9JUCD' }) });
  const b = tl.record({ direction: DIRECTION.RECV, type: 'joined', raw: '{"type":"joined","roomId":"K9JUCD","peerId":"peer-002"}', summary: summarizeMessage('joined', { roomId: 'K9JUCD', peerId: 'peer-002' }) });
  eq('seq 递增', [a.seq, b.seq], [1, 2]);
  eq('size = 2', tl.size, 2);
  ok('时刻单调递增', b.atMs >= a.atMs);
  eq('join 摘要', a.summary, 'room=K9JUCD');
  eq('joined 摘要', b.summary, 'room=K9JUCD peer=peer-002');

  const json = tl.toJSON({ url: 'http://localhost:8081', roomId: 'K9JUCD' });
  eq('toJSON count', json.count, 2);
  eq('toJSON entries 长度', json.entries.length, 2);
  eq('toJSON meta 合并', json.meta.roomId, 'K9JUCD');
  ok('toJSON 含 generatedAt', typeof json.generatedAt === 'string' && json.generatedAt.endsWith('Z'));
  ok('toJSON 可序列化', typeof JSON.stringify(json) === 'string');

  const csv = tl.toCSV();
  const lines = csv.trimEnd().split('\n');
  eq('CSV 行数 = 表头 + 2', lines.length, 3);
  eq('CSV 表头', lines[0], 'seq,at_ms,iso,direction,type,level,summary,raw,note');
  ok('CSV raw 含引号转义', lines[1].includes('"{""type"":""join""'));
  ok('CSV 以换行结尾', csv.endsWith('\n'));

  tl.clear();
  eq('clear 后 size = 0', tl.size, 0);
  eq('clear 后 seq 归零', tl.record({ direction: DIRECTION.LOCAL, type: 'state' }).seq, 1);
}

group('timeline: 摘要与 CSV 转义细节');

eq('offer 摘要只记字节数', summarizeMessage('offer', { type: 'offer', sdp: 'v=0\r\n' }), 'sdp_bytes=5');
eq('ice 摘要解析候选', summarizeMessage('ice', { type: 'ice', candidate: 'candidate:1 1 udp 2122260223 47.238.144.66 49152 typ srflx raddr 10.0.0.1 rport 9', sdpMid: '0' }), 'typ=srflx proto=udp addr=47.238.144.66:49152 foundation=1 sdpMid=0');
eq('error 摘要含 code', summarizeMessage('error', { code: 'ROOM_FULL', message: 'Room is full (max 2 peers)' }), 'code=ROOM_FULL message=Room is full (max 2 peers)');
eq('ping 摘要含时间戳', summarizeMessage('ping', { timestamp: 7 }), 'ts=7');
eq('parseCandidate 提取端口', parseCandidate('candidate:2 1 tcp 1 1.2.3.4 3478 typ relay').port, '3478');
eq('csvCell 逗号转义', csvCell('a,b'), '"a,b"');
eq('csvCell 引号翻倍', csvCell('a"b'), '"a""b"');
eq('csvCell 换行转义', csvCell('a\nb'), '"a\nb"');
eq('csvCell 普通值原样', csvCell('ok'), 'ok');

function monotonicStub() {
  let t = 0;
  return () => {
    t += 1.5;
    return t;
  };
}

group('client: 时间线联动（收发双向 + 关键摘要）');

{
  const { client, sockets } = makeClient();
  client.connect();
  sockets[0].fireOpen();
  client.createRoom();
  sockets[0].fireMessage(JSON.stringify(SAMPLES.created));
  const entries = client.timeline.entries;
  ok('时间线含发送的 create', entries.some((e) => e.direction === DIRECTION.SEND && e.type === 'create'));
  ok('时间线含接收的 created', entries.some((e) => e.direction === DIRECTION.RECV && e.type === 'created'));
  const createdEntry = entries.find((e) => e.type === 'created' && e.direction === DIRECTION.RECV);
  ok('created 摘要含 roomId 与 turnUrl', createdEntry.summary.includes('room=K9JUCD') && createdEntry.summary.includes('turn='));
  ok('created 原始 JSON 完整保留', createdEntry.raw === JSON.stringify(SAMPLES.created));
  ok('时间线含状态跃迁', entries.some((e) => e.type === 'state' && e.summary === 'connected → in_room'));
  const csv = client.timeline.toCSV();
  ok('客户端时间线可导出 CSV', csv.startsWith('seq,at_ms,iso,direction,type,level,summary,raw,note\n'));
}

// ============================================================================
// 7. 回归：原生定时器不得以实例作 this 调用（t9 / sourceFinding T2-timer-illegal-invocation）
// ----------------------------------------------------------------------------
// 缺陷：旧写法 `this.setTimeoutImpl = setTimeout`（直接存原生全局函数）随后以
// `this.setTimeoutImpl(...)` 调用 ⇒ 浏览器把 this 绑成客户端实例，而原生定时器是
// brand-checked 的 ⇒ `TypeError: Illegal invocation`；该异常在
// `handleOpen → startHeartbeat → scheduleHeartbeatTick` 抛出，中断 handleOpen，
// 使其后的 `sendPendingIntent()` 永不执行 ⇒ create/join 永不发出。
// node 的 setTimeout 不做 brand check，所以「调用一次看抛不抛」在 node 里抓不到；
// 因此本组断言改为**接收者泄漏**检测：把全局定时器换成探针，看客户端调用默认实现时
// 探针观察到的 this 是不是客户端实例。旧写法下必然泄漏 ⇒ 断言必红。
// 探针机制本身也在「旧写法样本」上做一次反向验证，证明这些断言确实有牙齿。
// ============================================================================

group('regression: 原生定时器 this 绑定（t9）');

{
  const realSetTimeout = globalThis.setTimeout;
  const realClearTimeout = globalThis.clearTimeout;
  let seenThis = 'unset';
  // 探针：记录接收者，但不改变行为（真正的定时器仍被调用）
  globalThis.setTimeout = function probingSetTimeout(fn, ms) {
    seenThis = this;
    return realSetTimeout.call(undefined, fn, ms);
  };
  globalThis.clearTimeout = function probingClearTimeout(handle) {
    seenThis = this;
    return realClearTimeout.call(undefined, handle);
  };

  const capture = {
    defaultTimeoutThis: 'unset',
    defaultClearThis: 'unset',
    legacyTimeoutThis: 'unset',
    legacyClearThis: 'unset',
  };
  let client = null;
  let legacy = null;
  try {
    // 不注入 setTimeout/clearTimeout ⇒ 走构造器里的默认实现（即被修复的那条分支）
    client = new SignalingClient({
      url: 'ws://timer.test:8443/ws',
      socketFactory: () => new FakeSocket('ws://timer.test:8443/ws'),
      logger: null,
    });
    // 旧写法样本：等价于修复前的 `this.setTimeoutImpl = setTimeout`
    legacy = { setTimeoutImpl: globalThis.setTimeout, clearTimeoutImpl: globalThis.clearTimeout };

    client.setTimeoutImpl(() => {}, 1);
    capture.defaultTimeoutThis = seenThis;
    const handle = client.setTimeoutImpl(() => {}, 1);
    client.clearTimeoutImpl(handle);
    capture.defaultClearThis = seenThis;

    legacy.setTimeoutImpl(() => {}, 1);
    capture.legacyTimeoutThis = seenThis;
    legacy.clearTimeoutImpl(0);
    capture.legacyClearThis = seenThis;
  } finally {
    globalThis.setTimeout = realSetTimeout;
    globalThis.clearTimeout = realClearTimeout;
  }

  ok('默认 setTimeoutImpl 不是原生全局函数本身（结构性）', client.setTimeoutImpl !== realSetTimeout, `same=${client.setTimeoutImpl === realSetTimeout}`);
  ok('默认 clearTimeoutImpl 不是原生全局函数本身（结构性）', client.clearTimeoutImpl !== realClearTimeout, `same=${client.clearTimeoutImpl === realClearTimeout}`);
  ok(
    '默认实现调用原生 setTimeout 时不把实例泄漏为 this（行为性）',
    capture.defaultTimeoutThis !== client && (capture.defaultTimeoutThis === undefined || capture.defaultTimeoutThis === globalThis),
    `seenThis=${capture.defaultTimeoutThis === client ? '客户端实例（泄漏）' : String(capture.defaultTimeoutThis)}`,
  );
  ok(
    '默认实现调用原生 clearTimeout 时不把实例泄漏为 this（行为性）',
    capture.defaultClearThis !== client,
    `seenThis=${capture.defaultClearThis === client ? '客户端实例（泄漏）' : String(capture.defaultClearThis)}`,
  );
  ok(
    '探针有牙齿：旧写法样本恰好被判为「接收者=属性宿主」（说明上面两条在旧写法下会红）',
    capture.legacyTimeoutThis === legacy && capture.legacyClearThis === legacy,
    `legacyTimeoutThis=${capture.legacyTimeoutThis === legacy ? 'legacy 实例' : String(capture.legacyTimeoutThis)}`,
  );
}

// ============================================================================
// 汇总
// ============================================================================

console.log(`\n=== PASS ${passCount}, FAIL ${failCount} ===`);
if (failCount > 0) {
  console.log('失败明细:');
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
process.exit(0);
