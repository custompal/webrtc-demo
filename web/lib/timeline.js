// web/lib/timeline.js — 信令/媒体时间线（纯静态 ES Module，浏览器与 node 均可加载）
//
// 依据：reports/70-browser-call-demo-requirements.md §6 O-1（逐帧记录 方向/时刻/type/原始 JSON/
// 关键字段摘要，支持清空）与 O-7（可导出 JSON/CSV）。
//
// 设计约束（H-3）：无构建、无 npm 依赖、无 bundler；本文件不 import 任何东西，
// 因此可被 `web/lib/signaling.js`、页面面板与 node 单测同时加载。
//
// 时刻口径（O-1）：`atMs` 是**单调**毫秒（performance.now / hrtime / Date.now 逐级回退），
// `iso` 是同一时刻的墙上时钟（UTC，仅用于导出可读性）。两者都记，避免把墙上时钟当排序依据。

/** 帧方向（O-1）。 */
export const DIRECTION = Object.freeze({
  /** 本端 → 服务端。 */
  SEND: 'send',
  /** 服务端 → 本端。 */
  RECV: 'recv',
  /** 本地事件（非线上帧），如状态跃迁、过滤计数。 */
  LOCAL: 'local',
});

/** 事件级别（O-6：error/异常/超时/过滤标红）。 */
export const LEVEL = Object.freeze({
  INFO: 'info',
  WARN: 'warn',
  ERROR: 'error',
});

/** 默认时间线容量：0 = 不淘汰（Demo 单次通话的帧数远小于内存关切）。 */
export const DEFAULT_CAPACITY = 0;

/**
 * 单调时钟（毫秒）。优先 `performance.now()`；node 无 DOM 时回退 `process.hrtime.bigint()`；
 * 最后回退 `Date.now()`（不单调，仅为最终兜底）。
 *
 * @returns {number} 单调递增的毫秒数（浮点）。
 */
export function monotonicNow() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now();
  }
  if (typeof process !== 'undefined' && process.hrtime && typeof process.hrtime.bigint === 'function') {
    return Number(process.hrtime.bigint() / 1000n) / 1000;
  }
  return Date.now();
}

function round3(value) {
  return Math.round(value * 1000) / 1000;
}

/**
 * 从候选行提取类型/协议/地址（O-3 的摘要口径，供时间线单行展示）。
 *
 * 输入形如 `candidate:842163049 1 udp 1677729535 203.0.113.7 51234 typ srflx raddr ... rport ...`。
 *
 * @param {string} candidate ICE 候选文本。
 * @returns {{foundation:string, component:string, protocol:string, priority:string, address:string, port:string, typ:string, relAddr:string, relPort:string}}
 */
export function parseCandidate(candidate) {
  const out = {
    foundation: '', component: '', protocol: '', priority: '',
    address: '', port: '', typ: '', relAddr: '', relPort: '',
  };
  if (typeof candidate !== 'string' || candidate === '') return out;
  const parts = candidate.replace(/^(a=)?candidate:/, '').trim().split(/\s+/);
  out.foundation = parts[0] ?? '';
  out.component = parts[1] ?? '';
  out.protocol = parts[2] ?? '';
  out.priority = parts[3] ?? '';
  out.address = parts[4] ?? '';
  out.port = parts[5] ?? '';
  for (let i = 6; i + 1 < parts.length; i += 2) {
    if (parts[i] === 'typ') out.typ = parts[i + 1];
    else if (parts[i] === 'raddr') out.relAddr = parts[i + 1];
    else if (parts[i] === 'rport') out.relPort = parts[i + 1];
  }
  return out;
}

/**
 * 关键字段摘要（O-1）：一行人类可读文本，永不包含整段 SDP（只记字节数）。
 *
 * @param {string} type 消息 type。
 * @param {object} message 已解析消息（或任意对象）。
 * @returns {string} 摘要文本。
 */
export function summarizeMessage(type, message) {
  const m = message && typeof message === 'object' ? message : {};
  switch (type) {
    case 'created':
    case 'joined': {
      const bits = [`room=${m.roomId ?? '?'}`];
      if (m.peerId !== undefined) bits.push(`peer=${m.peerId}`);
      if (m.stunUrl !== undefined) bits.push(`stun=${m.stunUrl}`);
      if (m.turnUrl !== undefined) bits.push(`turn=${m.turnUrl}`);
      if (m.turnUsername !== undefined) bits.push(`turnUser=${m.turnUsername}`);
      return bits.join(' ');
    }
    case 'join':
      return `room=${m.roomId ?? '?'}`;
    case 'peerJoined':
    case 'peerLeft':
      return `peer=${m.peerId ?? '?'}`;
    case 'offer':
    case 'answer':
      return `sdp_bytes=${typeof m.sdp === 'string' ? m.sdp.length : '?'}`;
    case 'ice': {
      const c = parseCandidate(m.candidate);
      const bits = [];
      if (c.typ) bits.push(`typ=${c.typ}`);
      if (c.protocol) bits.push(`proto=${c.protocol}`);
      if (c.address) bits.push(`addr=${c.address}:${c.port}`);
      if (c.foundation) bits.push(`foundation=${c.foundation}`);
      if (m.sdpMid !== undefined && m.sdpMid !== null) bits.push(`sdpMid=${m.sdpMid}`);
      if (m.sdpMLineIndex !== undefined && m.sdpMLineIndex !== null) bits.push(`mLine=${m.sdpMLineIndex}`);
      return bits.length ? bits.join(' ') : `candidate_bytes=${typeof m.candidate === 'string' ? m.candidate.length : '?'}`;
    }
    case 'natType':
      return `nat=${m.natType ?? '?'}`;
    case 'error':
      return `code=${m.code ?? '?'} message=${m.message ?? '?'}`;
    case 'ping':
    case 'pong':
      return `ts=${m.timestamp ?? '?'}`;
    case 'create':
    case 'leave':
      return '—';
    default:
      return m.type === undefined ? '—' : `type=${m.type}`;
  }
}

/**
 * 兜底摘要：未提供 summary 时用（截断的原始 JSON）。
 *
 * @param {string} type 消息 type（可为 '<invalid>'）。
 * @param {string} raw 原始帧文本。
 * @returns {string}
 */
export function defaultSummary(type, raw) {
  if (typeof raw !== 'string' || raw === '') return `type=${type}`;
  return raw.length > 80 ? `${raw.slice(0, 77)}...` : raw;
}

/**
 * CSV 单元格转义（RFC 4180）：含逗号/引号/换行时整体加引号，内部引号翻倍。
 *
 * @param {unknown} value 任意值。
 * @returns {string}
 */
export function csvCell(value) {
  const s = value === undefined || value === null ? '' : String(value);
  if (/[",\r\n]/.test(s)) return `"${s.replace(/"/g, '""')}"`;
  return s;
}

/** 时间线 CSV 表头（字段顺序即导出契约）。 */
export const CSV_HEADER = Object.freeze([
  'seq', 'at_ms', 'iso', 'direction', 'type', 'level', 'summary', 'raw', 'note',
]);

/**
 * 时间线：逐帧 append-only 记录 + JSON/CSV 导出（O-1、O-7）。
 *
 * 用法：
 * ```js
 * const t = new Timeline();
 * t.record({ direction: DIRECTION.SEND, type: 'join', raw: '{"type":"join","roomId":"K9JUCD"}' });
 * t.toJSON(); t.toCSV(); t.clear();
 * ```
 */
export class Timeline {
  /**
   * @param {object} [options]
   * @param {number} [options.capacity] 最大保留条目数，0 = 不淘汰（默认）。
   * @param {() => number} [options.now] 单调时钟（默认 {@link monotonicNow}）。
   * @param {() => number} [options.wallClock] 墙上时钟（默认 `Date.now`）。
   */
  constructor(options = {}) {
    this.capacity = Number.isFinite(options.capacity) ? options.capacity : DEFAULT_CAPACITY;
    this.now = typeof options.now === 'function' ? options.now : monotonicNow;
    this.wallClock = typeof options.wallClock === 'function' ? options.wallClock : Date.now;
    this._entries = [];
    this._seq = 0;
  }

  /** 已记录条目数。 */
  get size() {
    return this._entries.length;
  }

  /** 条目快照（浅拷贝数组，防止外部无意改动内部顺序）。 */
  get entries() {
    return this._entries.slice();
  }

  /**
   * 记录一条时间线条目。
   *
   * @param {object} entry
   * @param {string} entry.direction 方向（{@link DIRECTION}）。
   * @param {string} entry.type 消息 type 或本地事件名。
   * @param {string} [entry.raw] 原始 JSON 帧文本（本地事件可省略）。
   * @param {string} [entry.summary] 关键字段摘要；缺省时按 type 派生。
   * @param {string} [entry.level] 级别（{@link LEVEL}）。
   * @param {string} [entry.note] 附注（如错误码、原因）。
   * @param {number} [entry.atMs] 覆盖时刻（默认 `now()`）。
   * @param {string} [entry.iso] 覆盖墙上时刻。
   * @returns {object} 实际入队的条目（含 seq）。
   */
  record(entry = {}) {
    const atMs = Number.isFinite(entry.atMs) ? entry.atMs : this.now();
    const type = typeof entry.type === 'string' && entry.type !== '' ? entry.type : '<unknown>';
    const row = {
      seq: ++this._seq,
      atMs: round3(atMs),
      iso: typeof entry.iso === 'string' && entry.iso !== ''
        ? entry.iso
        : new Date(this.wallClock()).toISOString(),
      direction: typeof entry.direction === 'string' && entry.direction !== '' ? entry.direction : DIRECTION.LOCAL,
      type,
      level: typeof entry.level === 'string' && entry.level !== '' ? entry.level : LEVEL.INFO,
      summary: typeof entry.summary === 'string' && entry.summary !== ''
        ? entry.summary
        : defaultSummary(type, entry.raw),
      raw: typeof entry.raw === 'string' ? entry.raw : '',
      note: typeof entry.note === 'string' ? entry.note : '',
    };
    this._entries.push(row);
    if (this.capacity > 0) {
      while (this._entries.length > this.capacity) this._entries.shift();
    }
    return row;
  }

  /** `record` 的别名（面板代码可读性）。 */
  push(entry) {
    return this.record(entry);
  }

  /** 清空（O-1「支持清空」）。seq 归零，使导出物自洽。 */
  clear() {
    this._entries = [];
    this._seq = 0;
    return this;
  }

  /**
   * 导出 JSON（O-7）。`meta` 由调用方补充（生成时刻、页面 URL、候选表、stats 等）。
   *
   * @param {object} [meta] 额外元数据，合并进 `meta` 字段。
   * @returns {object} 可 `JSON.stringify` 的导出对象。
   */
  toJSON(meta = {}) {
    return {
      generatedAt: new Date(this.wallClock()).toISOString(),
      monotonicMs: round3(this.now()),
      count: this._entries.length,
      meta: meta && typeof meta === 'object' ? meta : {},
      entries: this._entries.slice(),
    };
  }

  /**
   * 导出 CSV（O-7）。表头见 {@link CSV_HEADER}；`raw` 含逗号/引号/换行时按 RFC 4180 转义。
   *
   * @returns {string} 带结尾换行的 CSV 文本。
   */
  toCSV() {
    const lines = [CSV_HEADER.join(',')];
    for (const e of this._entries) {
      lines.push(CSV_HEADER.map((k) => csvCell(e[k])).join(','));
    }
    return `${lines.join('\n')}\n`;
  }
}

export default Timeline;
