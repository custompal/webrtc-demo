// web/lib/observe.js — 观测面纯逻辑（SDP 解析 / 候选分类 / 状态机跃迁 / 1Hz stats 归约 / 导出）
//
// 依据 reports/70-browser-call-demo-requirements.md §6（O-1…O-8）与 §4 I-4、§5 M-5/M-6。
//
// 设计约束（H-3 + captain 补强）：纯 ES Module、无 npm 依赖、无构建；**不依赖任何自定义响应头**
// （无 COOP/COEP、无 crossOriginIsolated、无 SharedArrayBuffer），可在宿主机
// `python3 -m http.server 8081 --directory web` 下开箱即用。
//
// 本模块**只做纯计算**：不访问 DOM、不创建 RTCPeerConnection，因此可在浏览器与 node 中同时加载与单测。

import { csvCell } from './timeline.js';

/** 五条状态机（O-4）。 */
export const MACHINES = Object.freeze([
  'signaling', 'iceGathering', 'iceConnection', 'connection', 'dtls',
]);

/** 面板用机器名（中英并列，避免把中文当标识符）。 */
export const MACHINE_LABELS = Object.freeze({
  signaling: 'signaling（信令）',
  iceGathering: 'iceGathering（候选收集）',
  iceConnection: 'iceConnection（ICE 连接）',
  connection: 'connection（连接）',
  dtls: 'dtls（DTLS）',
});

// ============================================================================
// SDP 解析（O-2）
// ============================================================================

function splitLine(line) {
  const i = line.indexOf('=');
  if (i < 0) return [line, ''];
  return [line.slice(0, i), line.slice(i + 1)];
}

function parseRtpmap(value) {
  // <payload> <encoding>/<clockRate>[/<channels>]
  const m = /^(\d+)\s+([^/]+)\/(\d+)(?:\/(\d+))?/.exec(value);
  if (m === null) return null;
  return {
    payload: Number(m[1]),
    name: m[2],
    clockRate: Number(m[3]),
    channels: m[4] === undefined ? null : Number(m[4]),
  };
}

function applyMediaAttribute(media, value) {
  if (value.startsWith('mid:')) { media.mid = value.slice(4); return; }
  if (value.startsWith('ice-ufrag:')) { media.iceUfrag = value.slice(10); return; }
  if (value.startsWith('ice-pwd:')) { media.icePwd = value.slice(8); return; }
  if (value.startsWith('fingerprint:')) {
    const [algorithm, ...rest] = value.slice(12).trim().split(/\s+/);
    media.fingerprints.push({ algorithm, value: rest.join(' ') });
    return;
  }
  if (value.startsWith('setup:')) { media.setup = value.slice(6); return; }
  if (value.startsWith('rtpmap:')) {
    const parsed = parseRtpmap(value.slice(7).trim());
    if (parsed !== null) media.rtpmap.set(parsed.payload, parsed);
    return;
  }
  if (value.startsWith('fmtp:')) {
    const sp = value.indexOf(' ');
    if (sp > 0) {
      const pt = Number(value.slice(5, sp));
      const params = value.slice(sp + 1).trim();
      if (Number.isFinite(pt)) media.fmtp.set(pt, params);
    }
    return;
  }
  if (value.startsWith('candidate:')) { media.candidateLines.push(value.slice(10)); return; }
  if (value.startsWith('rtcp-fb:')) { media.rtcpFb.push(value.slice(8).trim()); return; }
  if (value.startsWith('extmap:')) { media.extmaps.push(value.slice(7).trim()); return; }
  if (value.startsWith('ssrc:')) { media.ssrcs.push(value.slice(5).trim()); return; }
  if (value.startsWith('msid:')) { media.msid = value.slice(5).trim(); return; }
  if (value.startsWith('rtcp-mux')) { media.rtcpMux = true; return; }
  if (value === 'sendonly' || value === 'recvonly' || value === 'sendrecv' || value === 'inactive') {
    media.direction = value;
    return;
  }
  if (value.startsWith('rtcp:')) { media.rtcp = value.slice(5).trim(); return; }
  media.otherAttributes.push(value);
}

function buildMediaReport(media) {
  const codecs = [];
  for (const pt of media.payloadTypes) {
    const rtpmap = media.rtpmap.get(pt);
    if (rtpmap === undefined) {
      // 静态 payload type 或 rtx/red 等无 rtpmap 的条目：只记 payload 号
      codecs.push({ payload: pt, name: null, clockRate: null, channels: null, parameters: media.fmtp.get(pt) ?? null });
      continue;
    }
    codecs.push({
      payload: pt,
      name: rtpmap.name,
      clockRate: rtpmap.clockRate,
      channels: rtpmap.channels,
      parameters: media.fmtp.get(pt) ?? null,
    });
  }
  return {
    index: media.index,
    kind: media.kind,
    mid: media.mid,
    port: media.port,
    protocol: media.protocol,
    direction: media.direction,
    payloadTypes: media.payloadTypes.slice(),
    codecs,
    iceUfrag: media.iceUfrag,
    icePwd: media.icePwd,
    setup: media.setup,
    fingerprints: media.fingerprints.slice(),
    candidateLines: media.candidateLines.slice(),
    candidateCount: media.candidateLines.length,
    rtcpMux: media.rtcpMux,
    rtcpFbCount: media.rtcpFb.length,
    extmapCount: media.extmaps.length,
    ssrcCount: media.ssrcs.length,
    msid: media.msid,
    connection: media.connection,
  };
}

/**
 * 解析 SDP 原文（O-2）：m= 段清单、codec 与 payload type、ICE ufrag/pwd、DTLS 指纹、候选行数、字节数。
 *
 * @param {string} sdp SDP 原文（offer 或 answer）。
 * @returns {object} 结构化解析结果（全部字段可 JSON 序列化）。
 */
export function parseSdp(sdp) {
  const text = typeof sdp === 'string' ? sdp : '';
  const rawLines = text.split(/\r\n|\r|\n/).filter((l) => l !== '');
  const session = {
    version: null, origin: null, sessionName: null, timing: null,
    iceUfrag: null, icePwd: null, fingerprints: [], setup: null,
    bundleGroups: [], groupLines: [], extmapCount: 0, otherAttributes: [],
  };
  const mediaList = [];
  let current = null;
  for (const line of rawLines) {
    const [key, value] = splitLine(line);
    if (key === 'v') { session.version = value; continue; }
    if (key === 'o') { session.origin = value; continue; }
    if (key === 's') { session.sessionName = value; continue; }
    if (key === 't') { session.timing = value; continue; }
    if (key === 'm') {
      const parts = value.trim().split(/\s+/);
      current = {
        index: mediaList.length, kind: parts[0] ?? '', port: Number(parts[1] ?? 0),
        protocol: parts[2] ?? '', payloadTypes: parts.slice(3).map(Number).filter(Number.isFinite),
        mid: null, direction: 'sendrecv', connection: null,
        iceUfrag: null, icePwd: null, setup: null, fingerprints: [],
        payloads: [], codecs: [],
        rtpmap: new Map(), fmtp: new Map(), candidateLines: [], rtcpFb: [], extmaps: [],
        ssrcs: [], msid: null, rtcpMux: false, rtcp: null, otherAttributes: [],
      };
      mediaList.push(current);
      continue;
    }
    if (key === 'c') { if (current) current.connection = value; continue; }
    if (key !== 'a') continue;
    if (current === null) {
      if (value.startsWith('ice-ufrag:')) session.iceUfrag = value.slice(10);
      else if (value.startsWith('ice-pwd:')) session.icePwd = value.slice(8);
      else if (value.startsWith('fingerprint:')) {
        const [algorithm, ...rest] = value.slice(12).trim().split(/\s+/);
        session.fingerprints.push({ algorithm, value: rest.join(' ') });
      } else if (value.startsWith('setup:')) session.setup = value.slice(6);
      else if (value.startsWith('group:')) {
        session.groupLines.push(value.slice(6).trim());
        const parts = value.slice(6).trim().split(/\s+/);
        if (parts[0] === 'BUNDLE') session.bundleGroups.push(parts.slice(1));
      } else if (value.startsWith('extmap:')) session.extmapCount += 1;
      else session.otherAttributes.push(value);
      continue;
    }
    applyMediaAttribute(current, value);
  }

  const media = mediaList.map(buildMediaReport);
  const codecs = [];
  for (const m of media) {
    for (const c of m.codecs) codecs.push({ kind: m.kind, mid: m.mid, ...c });
  }
  const candidateLineCount = media.reduce((n, m) => n + m.candidateCount, 0);
  return {
    bytes: text.length,
    lines: rawLines.length,
    session: {
      version: session.version,
      origin: session.origin,
      sessionName: session.sessionName,
      timing: session.timing,
      iceUfrag: session.iceUfrag,
      icePwd: session.icePwd,
      fingerprints: session.fingerprints,
      setup: session.setup,
      bundleGroups: session.bundleGroups,
      groupLines: session.groupLines,
      extmapCount: session.extmapCount,
    },
    media,
    codecs,
    mediaCount: media.length,
    audioCount: media.filter((m) => m.kind === 'audio').length,
    videoCount: media.filter((m) => m.kind === 'video').length,
    candidateLineCount,
    // 便捷取值：媒体段各有 ufrag 时优先取第一个媒体段（Chrome 的单段 BUNDLE 场景二者同值）
    iceUfrag: session.iceUfrag ?? (media[0] ? media[0].iceUfrag : null),
    icePwd: session.icePwd ?? (media[0] ? media[0].icePwd : null),
    fingerprint: session.fingerprints[0] ?? (media[0] ? media[0].fingerprints[0] : null) ?? null,
    setup: session.setup ?? (media[0] ? media[0].setup : null),
  };
}

// ============================================================================
// ICE 候选分类（O-3 / I-4）
// ============================================================================

/**
 * 是否为回环地址（I-4：本端不发送、远端丢弃）。
 *
 * @param {string} address 候选地址（IPv4 或 IPv6 字面量）。
 * @returns {boolean}
 */
export function isLoopbackAddress(address) {
  const host = String(address ?? '').replace(/^\[|\]$/g, '').trim();
  if (host === '') return false;
  if (host === '::1' || host === '0:0:0:0:0:0:0:1') return true;
  if (/^127\./.test(host)) return true;
  return false;
}

/**
 * 解析并分类一条 ICE 候选（O-3）。
 *
 * @param {string|{candidate: string, sdpMid?: string, sdpMLineIndex?: number}} input 候选文本或 candidate init。
 * @param {'local'|'remote'} [source] 来源（本端/远端）。
 * @returns {{raw: string, foundation: string, component: string, protocol: string, priority: number, address: string, port: number, type: string, relatedAddress: string, relatedPort: number|null, tcpType: string, sdpMid: string|null, sdpMLineIndex: number|null, source: string, loopback: boolean, key: string}}
 */
export function classifyCandidate(input, source = 'local') {
  const raw = typeof input === 'string' ? input : String((input && (input.candidate ?? input.raw)) ?? '');
  const sdpMid = typeof input === 'object' && input !== null ? (input.sdpMid ?? null) : null;
  const sdpMLineIndex = typeof input === 'object' && input !== null ? (input.sdpMLineIndex ?? null) : null;
  const body = raw.replace(/^(a=)?candidate:/, '').trim();
  const parts = body === '' ? [] : body.split(/\s+/);
  const out = {
    raw,
    foundation: parts[0] ?? '',
    component: parts[1] ?? '',
    protocol: (parts[2] ?? '').toLowerCase(),
    priority: Number(parts[3] ?? 0),
    address: parts[4] ?? '',
    port: Number(parts[5] ?? 0),
    type: 'unknown',
    relatedAddress: '',
    relatedPort: null,
    tcpType: '',
    sdpMid,
    sdpMLineIndex,
    source,
    loopback: false,
    key: `${source}:${raw}`,
  };
  for (let i = 6; i + 1 < parts.length; i += 2) {
    const k = parts[i];
    const v = parts[i + 1];
    if (k === 'typ') out.type = v;
    else if (k === 'raddr') out.relatedAddress = v;
    else if (k === 'rport') out.relatedPort = Number(v);
    else if (k === 'tcptype') out.tcpType = v;
  }
  out.address = out.address.replace(/^\[|\]$/g, '');
  out.loopback = isLoopbackAddress(out.address);
  return out;
}

/** 候选类型 → 面板排序权重（relay 优先展示）。 */
export const CANDIDATE_TYPE_ORDER = Object.freeze(['relay', 'srflx', 'prflx', 'host', 'unknown']);

/**
 * ICE 候选表（O-3）：按来源+原文去重，保留首次出现顺序，可导出 CSV。
 */
export class CandidateTable {
  constructor() {
    this._byKey = new Map();
  }

  /**
   * 添加一条候选。
   *
   * @param {string|object} input 候选文本或 candidate init。
   * @param {'local'|'remote'} [source] 来源。
   * @returns {object} 归一化后的候选条目。
   */
  add(input, source = 'local') {
    const entry = classifyCandidate(input, source);
    const existing = this._byKey.get(entry.key);
    if (existing !== undefined) {
      existing.count += 1;
      return existing;
    }
    entry.count = 1;
    this._byKey.set(entry.key, entry);
    return entry;
  }

  /** 全部条目（插入顺序）。 */
  get rows() {
    return [...this._byKey.values()];
  }

  get size() {
    return this._byKey.size;
  }

  /** 统计：{local: n, remote: n, loopbackDropped: n} 由调用方补 loopback 计数。 */
  counts() {
    const out = { local: 0, remote: 0 };
    for (const r of this._byKey.values()) out[r.source] = (out[r.source] ?? 0) + 1;
    return out;
  }

  clear() {
    this._byKey.clear();
    return this;
  }

  /** CSV（列顺序即导出契约）。 */
  toCSV() {
    const header = ['source', 'type', 'protocol', 'tcpType', 'address', 'port', 'priority', 'foundation', 'component', 'relatedAddress', 'relatedPort', 'sdpMid', 'sdpMLineIndex', 'count', 'loopback', 'raw'];
    const lines = [header.join(',')];
    for (const r of this.rows) {
      lines.push(header.map((k) => csvCell(r[k])).join(','));
    }
    return `${lines.join('\n')}\n`;
  }

  toJSON() {
    return this.rows.map((r) => ({ ...r }));
  }
}

// ============================================================================
// 状态机跃迁（O-4）
// ============================================================================

/**
 * 五条状态机的跃迁记录器（O-4：每次跃迁记 旧值 → 新值 + 时刻）。
 */
export class StateTracker {
  /**
   * @param {object} [options]
   * @param {() => number} [options.now] 单调时钟。
   * @param {() => number} [options.wallClock] 墙上时钟。
   */
  constructor(options = {}) {
    this.now = typeof options.now === 'function' ? options.now : defaultNow;
    this.wallClock = typeof options.wallClock === 'function' ? options.wallClock : Date.now;
    this.current = {};
    this.transitions = [];
    for (const machine of MACHINES) this.current[machine] = null;
  }

  /**
   * 设置某条状态机的当前值（相同则忽略）。
   *
   * @param {string} machine 状态机名（{@link MACHINES}）。
   * @param {string|null} value 新值。
   * @returns {boolean} 是否发生了跃迁。
   */
  set(machine, value) {
    const next = value === undefined || value === null ? null : String(value);
    const prev = this.current[machine] ?? null;
    if (next === null) return false;
    if (prev === next) return false;
    this.current[machine] = next;
    this.transitions.push({
      seq: this.transitions.length + 1,
      atMs: round3(this.now()),
      iso: new Date(this.wallClock()).toISOString(),
      machine,
      from: prev,
      to: next,
    });
    return true;
  }

  /** 某条状态机的当前值。 */
  get(machine) {
    return this.current[machine] ?? null;
  }

  /** 是否有过跃迁记录。 */
  get size() {
    return this.transitions.length;
  }

  clear() {
    this.transitions = [];
    for (const machine of MACHINES) this.current[machine] = null;
    return this;
  }

  toJSON() {
    return {
      current: { ...this.current },
      transitions: this.transitions.map((t) => ({ ...t })),
    };
  }

  toCSV() {
    const header = ['seq', 'at_ms', 'iso', 'machine', 'from', 'to'];
    const lines = [header.join(',')];
    for (const t of this.transitions) {
      lines.push([t.seq, t.atMs, t.iso, t.machine, t.from, t.to].map(csvCell).join(','));
    }
    return `${lines.join('\n')}\n`;
  }
}

// ============================================================================
// 1Hz stats 归约（O-5）
// ============================================================================

function round3(value) {
  return Math.round(value * 1000) / 1000;
}

/** 取第一个非空字符串（Chrome 的 mDNS 候选会把 address 置空而把名字放在 ip 字段）。 */
function firstNonEmpty(...values) {
  for (const value of values) {
    if (typeof value === 'string' && value !== '') return value;
  }
  return null;
}

function defaultNow() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') return performance.now();
  return Date.now();
}

function toReportMap(report) {
  if (report && typeof report.forEach === 'function' && typeof report.get === 'function') return report;
  const map = new Map();
  if (report && typeof report === 'object') {
    for (const [id, stat] of Object.entries(report)) map.set(id, stat);
  }
  return map;
}

/**
 * codecId → mimeType（M-5：必须能解析到 codec 统计，只看到 codecId 不算通过）。
 *
 * @param {object} report `getStats()` 结果或 id→stat 映射。
 * @param {string} codecId codec 统计 id。
 * @returns {string|null}
 */
export function codecMimeFor(report, codecId) {
  if (typeof codecId !== 'string' || codecId === '') return null;
  const map = toReportMap(report);
  const stat = typeof map.get === 'function' ? map.get(codecId) : undefined;
  if (stat === undefined || stat === null) return null;
  return typeof stat.mimeType === 'string' ? stat.mimeType : null;
}

function rate(prevValue, nextValue, deltaSeconds, isCounter) {
  if (!Number.isFinite(prevValue) || !Number.isFinite(nextValue)) return null;
  if (!isCounter) return null;
  if (!(deltaSeconds > 0)) return null;
  return round3(Math.max(0, (nextValue - prevValue) / deltaSeconds));
}

/**
 * 归约一次 `getStats()`（O-5）：RTP/RTCP + 选中候选对 + transport + codec。
 *
 * @param {object} report `RTCPeerConnection.getStats()` 结果（RTCStatsReport 或普通对象）。
 * @param {object|null} [prev] 上一次归约结果，用于算码率/帧率（可选）。
 * @param {{atMs?: number, wallMs?: number}} [options] 时刻覆盖。
 * @returns {object} 扁平可序列化的快照。
 */
export function reduceStats(report, prev = null, options = {}) {
  const map = toReportMap(report);
  const atMs = Number.isFinite(options.atMs) ? options.atMs : round3(defaultNow());
  const wallMs = Number.isFinite(options.wallMs) ? options.wallMs : Date.now();
  const prevAtMs = prev !== null && Number.isFinite(prev.atMs) ? prev.atMs : null;
  const deltaSeconds = prevAtMs === null ? 0 : Math.max(0, (atMs - prevAtMs) / 1000);
  const prevById = new Map();
  if (prev !== null) {
    for (const group of ['inbound', 'outbound', 'remoteInbound']) {
      for (const row of prev[group] ?? []) prevById.set(row.id, row);
    }
  }

  const codecs = [];
  const inbound = [];
  const outbound = [];
  const remoteInbound = [];
  const candidatePairs = [];
  let transport = null;
  let selectedPairId = null;
  const iterable = typeof map.forEach === 'function' ? map : null;
  if (iterable !== null) {
    map.forEach((stat, id) => {
      const sid = stat.id ?? id;
      if (stat.type === 'codec') {
        codecs.push({
          id: sid, mimeType: stat.mimeType ?? null, clockRate: stat.clockRate ?? null,
          channels: stat.channels ?? null, payloadType: stat.payloadType ?? null,
        });
        return;
      }
      if (stat.type === 'inbound-rtp') {
        const p = prevById.get(sid);
        inbound.push({
          id: sid, kind: stat.kind ?? null, codecId: stat.codecId ?? null,
          isRemote: stat.isRemote ?? null,
          bytesReceived: stat.bytesReceived ?? null, packetsReceived: stat.packetsReceived ?? null,
          packetsLost: stat.packetsLost ?? null, jitter: stat.jitter ?? null,
          framesDecoded: stat.framesDecoded ?? null, framesPerSecond: stat.framesPerSecond ?? null,
          frameWidth: stat.frameWidth ?? null, frameHeight: stat.frameHeight ?? null,
          nackCount: stat.nackCount ?? null, pliCount: stat.pliCount ?? null, firCount: stat.firCount ?? null,
          bitrateBps: p === undefined ? null : rate(p.bytesReceived, stat.bytesReceived, deltaSeconds, true),
        });
        return;
      }
      if (stat.type === 'outbound-rtp') {
        const p = prevById.get(sid);
        const framesDelta = p === undefined ? null : rate(p.framesEncoded, stat.framesEncoded, deltaSeconds, true);
        outbound.push({
          id: sid, kind: stat.kind ?? null, codecId: stat.codecId ?? null,
          bytesSent: stat.bytesSent ?? null, packetsSent: stat.packetsSent ?? null,
          framesEncoded: stat.framesEncoded ?? null,
          framesPerSecond: stat.framesPerSecond ?? framesDelta,
          frameWidth: stat.frameWidth ?? null, frameHeight: stat.frameHeight ?? null,
          nackCount: stat.nackCount ?? null, pliCount: stat.pliCount ?? null, firCount: stat.firCount ?? null,
          qualityLimitationReason: stat.qualityLimitationReason ?? null,
          bitrateBps: p === undefined ? null : rate(p.bytesSent, stat.bytesSent, deltaSeconds, true),
        });
        return;
      }
      if (stat.type === 'remote-inbound-rtp') {
        remoteInbound.push({
          id: sid, kind: stat.kind ?? null,
          packetsLost: stat.packetsLost ?? null, jitter: stat.jitter ?? null,
          roundTripTime: stat.roundTripTime ?? null, fractionLost: stat.fractionLost ?? null,
        });
        return;
      }
      if (stat.type === 'candidate-pair') {
        candidatePairs.push({
          id: sid, state: stat.state ?? null, nominated: stat.nominated ?? null,
          local: { address: stat.localCandidateId ?? null },
          remote: { address: stat.remoteCandidateId ?? null },
          currentRoundTripTime: stat.currentRoundTripTime ?? null,
          totalRoundTripTime: stat.totalRoundTripTime ?? null,
          availableOutgoingBitrate: stat.availableOutgoingBitrate ?? null,
          bytesSent: stat.bytesSent ?? null, bytesReceived: stat.bytesReceived ?? null,
          requestsSent: stat.requestsSent ?? null, responsesReceived: stat.responsesReceived ?? null,
        });
        return;
      }
      if (stat.type === 'transport') {
        transport = {
          dtlsState: stat.dtlsState ?? null, iceState: stat.iceState ?? null,
          selectedCandidatePairId: stat.selectedCandidatePairId ?? null,
          bytesSent: stat.bytesSent ?? null, bytesReceived: stat.bytesReceived ?? null,
        };
        return;
      }
    });
  }

  const candidates = new Map();
  if (iterable !== null) {
    map.forEach((stat, id) => {
      if (stat.type === 'local-candidate' || stat.type === 'remote-candidate') {
        candidates.set(stat.id ?? id, {
          source: stat.type === 'local-candidate' ? 'local' : 'remote',
          candidateType: stat.candidateType ?? null,
          protocol: stat.protocol ?? null,
          address: firstNonEmpty(stat.address, stat.ip),
          port: stat.port ?? null,
          priority: stat.priority ?? null,
          foundation: stat.foundation ?? null,
        });
      }
    });
  }
  const decoratedPairs = candidatePairs
    .filter((p) => p.id !== undefined && p.id !== null)
    .map((pair) => ({
      id: pair.id,
      state: pair.state,
      nominated: pair.nominated,
      local: candidates.get(pair.local.address) ?? null,
      remote: candidates.get(pair.remote.address) ?? null,
      currentRoundTripTime: pair.currentRoundTripTime,
      totalRoundTripTime: pair.totalRoundTripTime,
      availableOutgoingBitrate: pair.availableOutgoingBitrate,
      bytesSent: pair.bytesSent,
      bytesReceived: pair.bytesReceived,
      requestsSent: pair.requestsSent,
      responsesReceived: pair.responsesReceived,
    }))
    .sort((a, b) => {
      if (a.nominated !== b.nominated) return a.nominated ? -1 : 1;
      return String(a.id).localeCompare(String(b.id));
    });

  if (transport !== null && typeof transport.selectedCandidatePairId === 'string') {
    selectedPairId = transport.selectedCandidatePairId;
  }
  let selectedPair = decoratedPairs.find((p) => p.id === selectedPairId) ?? null;
  if (selectedPair === null) selectedPair = decoratedPairs.find((p) => p.nominated === true) ?? null;

  const codecById = new Map(codecs.map((c) => [c.id, c]));
  for (const row of [...inbound, ...outbound]) {
    const codec = row.codecId === null ? undefined : codecById.get(row.codecId);
    row.mimeType = codec === undefined ? null : codec.mimeType;
  }

  const videoMimes = new Set();
  for (const row of [...inbound, ...outbound]) {
    if ((row.kind === 'video' || row.mimeType === null) && typeof row.mimeType === 'string' && row.mimeType.toLowerCase().startsWith('video/')) {
      videoMimes.add(row.mimeType);
    }
  }
  const vp9 = [...videoMimes].some((m) => m.toLowerCase() === 'video/vp9');

  const totals = {
    bytesSent: transport !== null ? transport.bytesSent : sum(outbound, 'bytesSent'),
    bytesReceived: transport !== null ? transport.bytesReceived : sum(inbound, 'bytesReceived'),
    packetsLost: sum(inbound, 'packetsLost') + sum(remoteInbound, 'packetsLost'),
    nackCount: sum(inbound, 'nackCount') + sum(outbound, 'nackCount'),
    pliCount: sum(inbound, 'pliCount') + sum(outbound, 'pliCount'),
    firCount: sum(inbound, 'firCount') + sum(outbound, 'firCount'),
    rttMs: selectedPair !== null && Number.isFinite(selectedPair.currentRoundTripTime)
      ? round3(selectedPair.currentRoundTripTime * 1000)
      : (sumFinite(remoteInbound, 'roundTripTime') !== null ? round3(sumFinite(remoteInbound, 'roundTripTime') * 1000) : null),
    inboundBitrateBps: sumFinite(inbound, 'bitrateBps'),
    outboundBitrateBps: sumFinite(outbound, 'bitrateBps'),
  };

  return {
    atMs,
    wallMs,
    iso: new Date(wallMs).toISOString(),
    deltaSeconds: round3(deltaSeconds),
    codecs,
    inbound,
    outbound,
    remoteInbound,
    candidatePairs: decoratedPairs,
    selectedPairId,
    selectedPair,
    transport,
    totals,
    mediaDirection: directionOf(inbound, outbound),
    vp9,
    videoMimeTypes: [...videoMimes],
  };
}

function sum(rows, key) {
  let total = 0;
  let seen = false;
  for (const row of rows) {
    if (Number.isFinite(row[key])) { total += row[key]; seen = true; }
  }
  return seen ? total : 0;
}

function sumFinite(rows, key) {
  let total = 0;
  let seen = false;
  for (const row of rows) {
    if (Number.isFinite(row[key])) { total += row[key]; seen = true; }
  }
  return seen ? total : null;
}

function directionOf(inbound, outbound) {
  const hasIn = inbound.some((r) => Number.isFinite(r.bytesReceived) && r.bytesReceived > 0);
  const hasOut = outbound.some((r) => Number.isFinite(r.bytesSent) && r.bytesSent > 0);
  if (hasIn && hasOut) return 'both';
  if (hasIn) return 'in-only';
  if (hasOut) return 'out-only';
  return 'none';
}

/** 单帧统计 → 一行扁平 CSV 记录（O-5/O-7）。 */
export function statsRow(snapshot) {
  const video = snapshot.outbound.find((r) => r.kind === 'video') ?? snapshot.inbound.find((r) => r.kind === 'video') ?? null;
  const pair = snapshot.selectedPair;
  return {
    t_ms: snapshot.atMs,
    iso: snapshot.iso,
    in_bitrate_bps: snapshot.totals.inboundBitrateBps,
    out_bitrate_bps: snapshot.totals.outboundBitrateBps,
    bytes_sent: snapshot.totals.bytesSent,
    bytes_received: snapshot.totals.bytesReceived,
    resolution: video !== null && Number.isFinite(video.frameWidth) && Number.isFinite(video.frameHeight)
      ? `${video.frameWidth}x${video.frameHeight}` : '',
    fps: video !== null && Number.isFinite(video.framesPerSecond) ? round3(video.framesPerSecond) : '',
    jitter: snapshot.inbound.reduce((acc, r) => (Number.isFinite(r.jitter) ? Math.max(acc ?? 0, r.jitter) : acc), null),
    packets_lost: snapshot.totals.packetsLost,
    nack: snapshot.totals.nackCount,
    pli: snapshot.totals.pliCount,
    fir: snapshot.totals.firCount,
    rtt_ms: snapshot.totals.rttMs,
    pair: pair === null ? '' : `${pair.local && pair.local.address ? pair.local.address : '?'}:${pair.local && pair.local.port ? pair.local.port : '?'} → ${pair.remote && pair.remote.address ? pair.remote.address : '?'}:${pair.remote && pair.remote.port ? pair.remote.port : '?'}`,
    pair_types: pair === null ? '' : `${pair.local && pair.local.candidateType ? pair.local.candidateType : '?'}/${pair.remote && pair.remote.candidateType ? pair.remote.candidateType : '?'}`,
    ice_state: snapshot.transport === null ? '' : (snapshot.transport.iceState ?? ''),
    dtls_state: snapshot.transport === null ? '' : (snapshot.transport.dtlsState ?? ''),
    media_direction: snapshot.mediaDirection,
    vp9: snapshot.vp9,
  };
}

/** stats CSV 表头（列顺序即导出契约）。 */
export const STATS_CSV_HEADER = Object.freeze(Object.keys(statsRow({
  atMs: 0, iso: '', totals: {}, inbound: [], outbound: [], selectedPair: null, transport: null, mediaDirection: 'none', vp9: false,
})));

/**
 * 1 Hz 采样序列（O-5/O-7）：保存快照、算码率、导出 CSV/JSON。
 */
export class StatsSeries {
  /**
   * @param {object} [options]
   * @param {() => number} [options.now] 单调时钟。
   * @param {() => number} [options.wallClock] 墙上时钟。
   * @param {number} [options.capacity] 最大保留样本数（0 = 不限）。
   */
  constructor(options = {}) {
    this.now = typeof options.now === 'function' ? options.now : defaultNow;
    this.wallClock = typeof options.wallClock === 'function' ? options.wallClock : Date.now;
    this.capacity = Number.isFinite(options.capacity) ? options.capacity : 0;
    this.samples = [];
  }

  get latest() {
    return this.samples.length ? this.samples[this.samples.length - 1] : null;
  }

  get count() {
    return this.samples.length;
  }

  /**
   * 保存一份**已归约**的快照（供 `peer.sampleStats()` 这类外部归约方使用）。
   *
   * @param {object} snapshot {@link reduceStats} 结果。
   * @returns {object} 同一快照。
   */
  add(snapshot) {
    this.samples.push(snapshot);
    if (this.capacity > 0) {
      while (this.samples.length > this.capacity) this.samples.shift();
    }
    return snapshot;
  }

  /**
   * 归约并保存一次采样。
   *
   * @param {object} report `getStats()` 结果。
   * @returns {object} 本次快照。
   */
  reduce(report) {
    const snapshot = reduceStats(report, this.latest, { atMs: this.now(), wallMs: this.wallClock() });
    return this.add(snapshot);
  }

  clear() {
    this.samples = [];
    return this;
  }

  toJSON(meta = {}) {
    return {
      generatedAt: new Date(this.wallClock()).toISOString(),
      count: this.samples.length,
      meta,
      samples: this.samples.map((s) => JSON.parse(JSON.stringify(s))),
    };
  }

  toCSV() {
    const header = [...STATS_CSV_HEADER];
    const lines = [header.join(',')];
    for (const s of this.samples) {
      const row = statsRow(s);
      lines.push(header.map((k) => csvCell(row[k])).join(','));
    }
    return `${lines.join('\n')}\n`;
  }
}

/** 判定「双向媒体是否成立」（M-6）：返回每方向的字节增长证据。 */
export function mediaEvidence(prevSnapshot, nextSnapshot) {
  const inboundDelta = deltaOf(prevSnapshot, nextSnapshot, 'inbound', 'bytesReceived');
  const outboundDelta = deltaOf(prevSnapshot, nextSnapshot, 'outbound', 'bytesSent');
  return {
    inboundDelta,
    outboundDelta,
    inboundGrowing: inboundDelta !== null && inboundDelta > 0,
    outboundGrowing: outboundDelta !== null && outboundDelta > 0,
    both: inboundDelta !== null && outboundDelta !== null && inboundDelta > 0 && outboundDelta > 0,
  };
}

function deltaOf(prev, next, group, key) {
  if (prev === null || prev === undefined || next === null || next === undefined) return null;
  const a = sumFinite(prev[group] ?? [], key);
  const b = sumFinite(next[group] ?? [], key);
  if (a === null || b === null) return null;
  return round3(b - a);
}

export default {
  MACHINES, MACHINE_LABELS, parseSdp, classifyCandidate, isLoopbackAddress,
  CandidateTable, StateTracker, StatsSeries, reduceStats, statsRow, codecMimeFor, mediaEvidence,
};
