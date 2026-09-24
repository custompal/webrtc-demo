// web/lib/signaling.js — 浏览器端信令客户端（纯静态 ES Module，浏览器与 node 均可加载）
//
// 判据来源（冻结，不得就地解释）：
//   * reports/70-browser-call-demo-requirements.md §2（对接事实）、§3 B-1…B-12（行为判据）
//   * `signaling/protocol/message.go`（14 类消息、服务端独占类型、转发类型、NAT 枚举）
//   * `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingClient.kt`（心跳/退避/缓冲/错误策略）
//   * `app/src/main/kotlin/com/example/webrtcdemo/signaling/SignalingMessage.kt`（编解码语义）
//
// 设计约束（H-3）：无构建步骤、无 npm 依赖、无 bundler。依赖（WebSocket 实现、时钟、定时器）全部注入，
// 以便 node 单测用虚拟时钟与假 socket 精确复现「15 s 心跳 / 5 s pong 窗口 / 1-2-4-8 s 退避」。
//
// 本文件不硬编码任何 ICE 服务器或凭据（§2.3 I-1）：ICE 配置只能来自 created/joined 下发字段。

import { Timeline, DIRECTION, LEVEL, summarizeMessage } from './timeline.js';

// ============================================================================
// 常量（与 App / Go 侧逐字对齐；来源行号见文件头）
// ============================================================================

/** 14 类消息 type（`signaling/protocol/message.go:12`–`signaling/protocol/message.go:27`）。 */
export const MESSAGE_TYPES = Object.freeze([
  'create', 'created', 'join', 'joined', 'peerJoined', 'peerLeft',
  'offer', 'answer', 'ice', 'natType', 'leave', 'ping', 'pong', 'error',
]);

/** 只允许 S→C 的类型；客户端发送会被服务端回 INVALID_MESSAGE（`message.go:145`）。 */
export const SERVER_ONLY_TYPES = Object.freeze([
  'created', 'joined', 'error', 'peerJoined', 'peerLeft', 'pong',
]);

/** 服务端按 roomId 原样转发给对端的类型（`message.go:171`）。 */
export const FORWARD_TYPES = Object.freeze(['offer', 'answer', 'ice', 'natType']);

/** NAT 类型枚举 6 值（`message.go:31`–`message.go:36`）。 */
export const NAT_TYPES = Object.freeze([
  'Open', 'FullCone', 'RestrictedCone', 'PortRestrictedCone', 'Symmetric', 'Unknown',
]);

/** 心跳发送间隔（B-2；`SignalingClient.kt:79`）。 */
export const PING_INTERVAL_MS = 15000;

/** pong 单窗口超时（B-2；`SignalingClient.kt:82`）。 */
export const PONG_TIMEOUT_MS = 5000;

/** 连续 pong 丢失窗口容忍数（B-2；`SignalingClient.kt:91`）。 */
export const PONG_MISS_TOLERANCE = 4;

/** 有效判活阈值 = 5 s × 4 = 20 s（B-2；`SignalingClient.kt:94`）。 */
export const PONG_FAIL_AFTER_MS = PONG_TIMEOUT_MS * PONG_MISS_TOLERANCE;

/** 退避基数 1 s（B-3；`SignalingClient.kt:139`）。 */
export const REJOIN_RETRY_BASE_MS = 1000;

/** 单次退避上限 8 s（B-3；`SignalingClient.kt:142`）。 */
export const REJOIN_RETRY_MAX_MS = 8000;

/** 重连/重试次数上限 10（B-3；`SignalingClient.kt:145`）。 */
export const MAX_REJOIN_ATTEMPTS = 10;

/** 无监听器期间的缓冲上限 32（B-5；`SignalingClient.kt:170`）。 */
export const MAX_PENDING_MESSAGES = 32;

/** 单条消息上限 64 KiB（§2.1；`signaling/config/config.go:29`）。 */
export const MAX_MESSAGE_SIZE = 64 * 1024;

/** 正常关闭码（B-6；`SignalingClient.kt:128`）。 */
export const WS_CLOSE_NORMAL = 1000;

/** `WebSocket.readyState === OPEN`。 */
export const WS_OPEN = 1;

/** `/ws` 路径常量（§2.1；`signaling/server/server.go:25`）。 */
export const PATH_WS = '/ws';

/**
 * 页面默认信令端点（明文 WS，路径固定 `/ws`）。
 *
 * ⚠️ 不是 `127.0.0.1:8443`：容器内该地址是 DSH Harness 自己的 Caddy（`tls internal`），
 * 连它必然得到 TLS/协议错。页面必须允许运行时用 `?signaling=` 或输入框覆盖本默认值。
 */
export const DEFAULT_SIGNALING_URL = 'ws://47.238.144.66:8443/ws';

/** 房间号字符集（`SignalingMessage.kt` 的 `ROOM_ID_CHARSET`，排除 I/L/O/0/1）。 */
export const ROOM_ID_CHARSET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

/** 房间号正则（6 位）。 */
export const ROOM_ID_REGEX = /^[A-HJ-KM-NP-Z2-9]{6}$/;

/** 服务端错误码（`signaling/protocol/errors.go:6`）。 */
export const ERROR_CODES = Object.freeze({
  ROOM_NOT_FOUND: 'ROOM_NOT_FOUND',
  ROOM_FULL: 'ROOM_FULL',
  ROOM_EXPIRED: 'ROOM_EXPIRED',
  INVALID_MESSAGE: 'INVALID_MESSAGE',
  NOT_IN_ROOM: 'NOT_IN_ROOM',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
});

/** 终态错误码：抑制重连 + 清空房间意图（§2.5；`SignalingClient.kt:849`，**不含** ROOM_FULL）。 */
export const TERMINAL_CODES = Object.freeze([
  ERROR_CODES.ROOM_NOT_FOUND,
  ERROR_CODES.ROOM_EXPIRED,
  ERROR_CODES.INVALID_MESSAGE,
  ERROR_CODES.NOT_IN_ROOM,
]);

/** 错误处置动作（§2.5；`SignalingClient.kt` 的 `SignalingErrorPolicy.Action`）。 */
export const ErrorAction = Object.freeze({
  /** 终态：抑制重连并清空房间意图。 */
  TERMINAL_SUPPRESS: 'TERMINAL_SUPPRESS',
  /** 掉线重连语境下的暂时性拒绝：有界指数退避重试 join，保留房间意图。 */
  RETRY_REJOIN: 'RETRY_REJOIN',
  /** 其它：不抑制、不重试，仅呈现给 UI。 */
  SURFACE: 'SURFACE',
});

// ============================================================================
// 纯函数：端点、房间号、退避、判活、错误策略（全部可 node 单测）
// ============================================================================

/**
 * 把端点补全为 `<endpoint>/ws`（已是 `/ws` 结尾则不重复追加）。
 *
 * @param {string} [endpoint] 形如 `ws://host:8443`、`ws://host:8443/ws`、`wss://…`。
 * @returns {string} 可直接交给 `new WebSocket()` 的地址。
 */
export function signalingUrl(endpoint) {
  const base = typeof endpoint === 'string' ? endpoint.trim() : '';
  if (base === '') return DEFAULT_SIGNALING_URL;
  if (/\/ws(\?|$)/.test(base)) return base;
  return `${base.replace(/\/+$/, '')}${PATH_WS}`;
}

/**
 * 规范化用户输入的房间号：去空白 → 转大写 → 剔除非法字符 → 截断 6 位。
 *
 * @param {string} raw 用户输入。
 * @returns {string}
 */
export function normalizeRoomId(raw) {
  return String(raw ?? '')
    .trim()
    .toUpperCase()
    .split('')
    .filter((ch) => ROOM_ID_CHARSET.includes(ch))
    .slice(0, 6)
    .join('');
}

/**
 * 是否为合法 6 位房间号。
 *
 * @param {string} roomId 房间号。
 * @returns {boolean}
 */
export function isValidRoomId(roomId) {
  return typeof roomId === 'string' && ROOM_ID_REGEX.test(roomId);
}

/**
 * 指数退避延迟：1 s、2 s、4 s、8 s、8 s…（单次上限 8 s）。
 *
 * @param {number} attempt 第几次重试（**从 1 开始**，与 App 的 `rejoinDelayMs` 同口径）。
 * @returns {number} 本次等待毫秒数。
 */
export function rejoinDelayMs(attempt) {
  let delay = REJOIN_RETRY_BASE_MS;
  const steps = Math.max(0, Math.floor(attempt) - 1);
  for (let i = 0; i < steps; i += 1) delay = Math.min(delay * 2, REJOIN_RETRY_MAX_MS);
  return delay;
}

/**
 * 退避序列（诊断/断言用）。
 *
 * @param {number} [maxAttempts] 次数上限（默认 {@link MAX_REJOIN_ATTEMPTS}）。
 * @returns {number[]} 每次重试的等待毫秒数。
 */
export function rejoinSchedule(maxAttempts = MAX_REJOIN_ATTEMPTS) {
  const out = [];
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) out.push(rejoinDelayMs(attempt));
  return out;
}

/**
 * 重试的**累计**等待（默认 10 次 = 63 000 ms = 63 s）。
 *
 * 口径要求：> 服务端 45 s 读超时，< 90 s 席位宽限期（B-3）。
 *
 * @param {number} [maxAttempts] 次数上限（默认 {@link MAX_REJOIN_ATTEMPTS}）。
 * @returns {number} 累计毫秒数。
 */
export function rejoinBudgetMs(maxAttempts = MAX_REJOIN_ATTEMPTS) {
  let total = 0;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) total += rejoinDelayMs(attempt);
  return total;
}

/**
 * 某个存活窗口是否已丢 pong（纯函数，与 App 的 `SignalingClient.pongMissed` 同语义）。
 *
 * @param {number} nowMs 当前时刻。
 * @param {number} pingSentAtMs 该窗口发出 ping 的时刻（0 = 无待回应 ping）。
 * @param {number} lastPongAtMs 最近一次收到 pong 的时刻。
 * @param {number} [timeoutMs] 单窗口超时。
 * @returns {boolean}
 */
export function pongMissed(nowMs, pingSentAtMs, lastPongAtMs, timeoutMs = PONG_TIMEOUT_MS) {
  return pingSentAtMs > 0 && nowMs - pingSentAtMs > timeoutMs && lastPongAtMs < pingSentAtMs;
}

/**
 * 连续丢失是否已达断线阈值。
 *
 * @param {number} consecutiveMisses 已连续丢失的窗口数。
 * @param {number} [tolerance] 容忍上限。
 * @returns {boolean}
 */
export function pongTimeoutReached(consecutiveMisses, tolerance = PONG_MISS_TOLERANCE) {
  return consecutiveMisses >= tolerance;
}

/**
 * 服务端错误码 → 处置动作（§2.5，纯函数，可单测）。
 *
 * @param {string} code `error.code`。
 * @param {{rejoinAfterDrop?: boolean}} [context] 是否发生在「曾在房内、掉线后重连」语境。
 * @returns {{action: string, clearsRoomIntent: boolean}}
 */
export function classifyError(code, context = {}) {
  const rejoinAfterDrop = context.rejoinAfterDrop === true;
  if (code === ERROR_CODES.ROOM_FULL && rejoinAfterDrop) {
    return { action: ErrorAction.RETRY_REJOIN, clearsRoomIntent: false };
  }
  if (TERMINAL_CODES.includes(code)) {
    return { action: ErrorAction.TERMINAL_SUPPRESS, clearsRoomIntent: true };
  }
  return { action: ErrorAction.SURFACE, clearsRoomIntent: false };
}

/** 类型守卫：是否属于 14 类消息。 */
export function isKnownType(type) {
  return MESSAGE_TYPES.includes(type);
}

/** 类型守卫：是否服务端独占。 */
export function isServerOnlyType(type) {
  return SERVER_ONLY_TYPES.includes(type);
}

/** 类型守卫：是否需要按 roomId 转发。 */
export function isForwardType(type) {
  return FORWARD_TYPES.includes(type);
}

/** 类型守卫：natType 是否为冻结枚举值。 */
export function isValidNatType(value) {
  return NAT_TYPES.includes(value);
}

// ============================================================================
// 编解码（对齐 Kotlin `SignalingCodec`：ignoreUnknownKeys = true，必填缺失抛错）
// ============================================================================

/**
 * 编解码错误。
 *
 * @property {string} code 协议错误码（恒为 INVALID_MESSAGE，与服务端同名）。
 * @property {boolean} recoverable 是否可恢复（未知 type 为 true：记入时间线并忽略，不中断连接）。
 * @property {boolean} unknownType 是否未知 type。
 * @property {string} type 出错的 type（未知类型时即该未知值）。
 * @property {string} [field] 出错的字段名。
 */
export class SignalingCodecError extends Error {
  constructor(message, options = {}) {
    super(message);
    this.name = 'SignalingCodecError';
    this.code = options.code ?? ERROR_CODES.INVALID_MESSAGE;
    this.recoverable = options.recoverable === true;
    this.unknownType = options.unknownType === true;
    this.type = options.type ?? '';
    this.field = options.field ?? '';
  }
}

const S = (name, required = true) => ({ name, kind: 'string', required });
const I = (name, required = true) => ({ name, kind: 'int', required });

/**
 * 每类消息的字段表（字段名逐字冻结；顺序即编码顺序）。
 *
 * 必填/可选依据 `signaling/protocol/message.go`：ice 的 `sdpMid`/`sdpMLineIndex` 可缺省
 * （Go 侧用指针区分「缺失」与「显式 0」），其余消息字段全部必填。
 */
export const MESSAGE_SCHEMAS = Object.freeze({
  create: { direction: 'c2s', fields: [] },
  created: {
    direction: 's2c',
    fields: [S('roomId'), S('stunUrl'), S('turnUrl'), S('turnUsername'), S('turnCredential')],
  },
  join: { direction: 'c2s', fields: [S('roomId')] },
  joined: {
    direction: 's2c',
    fields: [S('roomId'), S('stunUrl'), S('turnUrl'), S('turnUsername'), S('turnCredential'), S('peerId')],
  },
  peerJoined: { direction: 's2c', fields: [S('peerId')] },
  peerLeft: { direction: 's2c', fields: [S('peerId')] },
  offer: { direction: 'fwd', fields: [S('sdp')] },
  answer: { direction: 'fwd', fields: [S('sdp')] },
  ice: {
    direction: 'fwd',
    fields: [S('candidate'), S('sdpMid', false), I('sdpMLineIndex', false)],
  },
  natType: { direction: 'fwd', fields: [S('natType')] },
  leave: { direction: 'c2s', fields: [] },
  ping: { direction: 'c2s', fields: [I('timestamp')] },
  pong: { direction: 's2c', fields: [I('timestamp')] },
  error: { direction: 's2c', fields: [S('code'), S('message')] },
});

/**
 * 规范化一条消息：只保留该 type 的已知字段（**忽略未知键**），校验必填与类型（**缺失必填报错**）。
 *
 * @param {string|object} typeOrMessage type 值或含 `type` 的完整对象。
 * @param {object} [payload] 字段表（当首参为 type 字符串时使用）。
 * @returns {object} 规范化后的消息对象（键顺序：type 在前，其后按 schema 顺序）。
 * @throws {SignalingCodecError}
 */
export function normalizeMessage(typeOrMessage, payload) {
  const source = typeof typeOrMessage === 'string'
    ? (payload && typeof payload === 'object' ? payload : {})
    : (typeOrMessage && typeof typeOrMessage === 'object' ? typeOrMessage : null);
  if (source === null) {
    throw new SignalingCodecError('消息必须是对象', { recoverable: false });
  }
  const type = typeof typeOrMessage === 'string' ? typeOrMessage : source.type;
  if (typeof type !== 'string' || type === '') {
    throw new SignalingCodecError('缺少 "type" 字段', { recoverable: false });
  }
  const schema = MESSAGE_SCHEMAS[type];
  if (schema === undefined) {
    throw new SignalingCodecError(`未知消息类型 "${type}"`, {
      recoverable: true, unknownType: true, type,
    });
  }
  const out = { type };
  for (const field of schema.fields) {
    const raw = source[field.name];
    const present = Object.prototype.hasOwnProperty.call(source, field.name)
      && raw !== undefined && raw !== null;
    if (!present) {
      if (field.required) {
        throw new SignalingCodecError(`${type}.${field.name} 缺失（必填）`, {
          recoverable: false, type, field: field.name,
        });
      }
      continue; // 可缺省（explicitNulls = false 语义：null/undefined 一律不出现）
    }
    if (field.kind === 'string') {
      if (typeof raw !== 'string') {
        throw new SignalingCodecError(`${type}.${field.name} 必须是字符串（收到 ${typeof raw}）`, {
          recoverable: false, type, field: field.name,
        });
      }
      if (field.required && raw === '') {
        throw new SignalingCodecError(`${type}.${field.name} 不得为空字符串`, {
          recoverable: false, type, field: field.name,
        });
      }
    } else { // 'int'
      if (typeof raw !== 'number' || !Number.isFinite(raw)) {
        throw new SignalingCodecError(`${type}.${field.name} 必须是数字（收到 ${typeof raw}）`, {
          recoverable: false, type, field: field.name,
        });
      }
    }
    out[field.name] = raw;
  }
  return out;
}

/**
 * 编码为 UTF-8 JSON 文本帧。
 *
 * @param {string|object} typeOrMessage type 值或含 `type` 的对象。
 * @param {object} [payload] 字段表。
 * @returns {string} JSON 文本（`explicitNulls = false`：可缺省字段为 null 时不出现）。
 * @throws {SignalingCodecError}
 */
export function encodeMessage(typeOrMessage, payload) {
  return JSON.stringify(normalizeMessage(typeOrMessage, payload));
}

/**
 * 解码 UTF-8 JSON 文本帧。
 *
 * 语义与 Kotlin `SignalingCodec.decode` 对齐：非法 JSON / 缺必填字段 → 抛错；
 * 未知键忽略；**未知 type 抛可恢复错误**（调用方须记入时间线并忽略，不得中断连接，§2.2）。
 *
 * @param {string} raw 文本帧。
 * @returns {object} 规范化后的消息对象。
 * @throws {SignalingCodecError}
 */
export function decodeMessage(raw) {
  if (typeof raw !== 'string') {
    throw new SignalingCodecError('帧必须是 UTF-8 文本', { recoverable: false });
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new SignalingCodecError(`JSON 解析失败：${err.message}`, { recoverable: false });
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new SignalingCodecError('帧顶层必须是对象', { recoverable: false });
  }
  return normalizeMessage(parsed);
}

/**
 * 尽力窥探一段文本帧的 type（用于「解码失败也要把它记进时间线」）。
 *
 * @param {string} raw 文本帧。
 * @returns {string} type 值，或 `<invalid>`。
 */
export function peekType(raw) {
  if (typeof raw !== 'string') return '<invalid>';
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && typeof parsed.type === 'string') return parsed.type;
  } catch {
    /* 忽略：调用方只想要一个可展示的标签 */
  }
  return '<invalid>';
}

/**
 * 把任意 `MessageEvent.data` 转成文本（浏览器文本帧恒为 string；node 亦为 string，
 * 这里为潜在的二进制帧留一条确定性的降级路径）。
 *
 * @param {unknown} data 帧负载。
 * @returns {string}
 */
export function frameToText(data) {
  if (typeof data === 'string') return data;
  if (data instanceof ArrayBuffer) return new TextDecoder().decode(new Uint8Array(data));
  if (ArrayBuffer.isView(data)) return new TextDecoder().decode(data);
  return String(data);
}

// ============================================================================
// 客户端
// ============================================================================

/** 信令状态机取值（t4 的 O-4 会把它映射到面板的 signaling 一列）。 */
export const SIGNALING_STATES = Object.freeze([
  'idle', 'connecting', 'connected', 'in_room', 'in_call', 'reconnecting', 'closed', 'failed',
]);

const defaultSocketFactory = (url) => {
  if (typeof WebSocket === 'undefined') {
    throw new Error('当前环境没有 WebSocket 实现（Chrome 页面应内建；node 侧请注入 socketFactory）');
  }
  return new WebSocket(url);
};

const defaultLogger = typeof console !== 'undefined' ? console : null;

/**
 * 浏览器端信令客户端：单 WS、按 type 分派、15 s 心跳 / 5 s pong 窗口 / 1-2-4-8 s 退避。
 *
 * 依赖注入项（便于无网络单测）：
 *   * `socketFactory(url)` → 形如 WebSocket 的对象（`send`/`close`/`readyState`/`onopen`/…）。
 *   * `now()` → 毫秒时钟（用于 ping.timestamp 与窗口判定）。
 *   * `setTimeout`/`clearTimeout` → 定时器实现。
 *   * `logger` → 控制台（B-12：`[sig]` 前缀）。
 *   * `timeline` → 时间线（默认自建 {@link Timeline}）。
 */
export class SignalingClient {
  /**
   * @param {object} [options]
   * @param {string} [options.url] 信令地址（可用 {@link signalingUrl} 补全 `/ws`）。
   * @param {(url: string) => object} [options.socketFactory] WebSocket 构造器。
   * @param {() => number} [options.now] 毫秒时钟（默认 `Date.now`，与 App 的 ping 时间戳口径一致）。
   * @param {(fn: Function, ms: number) => unknown} [options.setTimeout] 定时器。
   * @param {(handle: unknown) => void} [options.clearTimeout] 定时器取消。
   * @param {Timeline} [options.timeline] 时间线实例。
   * @param {object} [options.logger] 控制台实现（默认 `console`）。
   */
  constructor(options = {}) {
    this.url = signalingUrl(options.url ?? DEFAULT_SIGNALING_URL);
    this.socketFactory = typeof options.socketFactory === 'function'
      ? options.socketFactory
      : defaultSocketFactory;
    this.now = typeof options.now === 'function' ? options.now : Date.now;
    // 定时器默认实现必须**包一层**：若把 window.setTimeout 直接存成属性再以
    // `this.setTimeoutImpl(...)` 调用，this 会被绑成客户端实例 ⇒ 浏览器抛
    // `TypeError: Illegal invocation`（Chrome 实测：handleOpen 内 startHeartbeat 崩，
    // 导致 sendPendingIntent 永不执行、create/join 永不发出）。
    this.setTimeoutImpl = typeof options.setTimeout === 'function'
      ? options.setTimeout
      : (typeof setTimeout === 'function' ? (fn, ms) => setTimeout(fn, ms) : null);
    this.clearTimeoutImpl = typeof options.clearTimeout === 'function'
      ? options.clearTimeout
      : (typeof clearTimeout === 'function' ? (handle) => clearTimeout(handle) : null);
    this.timeline = options.timeline instanceof Timeline ? options.timeline : new Timeline();
    this.logger = options.logger === undefined ? defaultLogger : options.logger;

    /** @type {object|null} 当前 socket（重建前必须先关闭旧 socket，B-6）。 */
    this.socket = null;
    /** @type {string} 信令状态机当前值。 */
    this.state = 'idle';
    /** @type {Map<string, Set<Function>>} 按 message.type 分派的监听器。 */
    this.listeners = new Map();
    /** @type {Map<string, Set<Function>>} 传输/客户端事件监听器。 */
    this.eventListeners = new Map();
    /** @type {Array<{type: string, message: object, raw: string, atMs: number}>} 待投缓冲（B-5）。 */
    this.pending = [];

    this.droppedPending = 0;
    this.reconnectAttempts = 0;
    this.rejoinAttempts = 0;
    this.reconnectSuppressed = false;
    this.reconnectExhausted = false;
    this.closedByUser = false;
    this.dropRequested = false;
    this.rejoinAfterDrop = false;
    this.currentRoomId = null;
    this.peerId = null;
    this.pendingCreate = false;
    this.pendingRoomId = null;
    this.terminalReason = '';
    this.lastError = null;
    this.lastClose = null;
    this.framesSent = 0;
    this.framesReceived = 0;
    this.socketsCreated = 0;
    this.socketsClosed = 0;

    this.pingSentAtMs = 0;
    this.lastPongAtMs = 0;
    this.consecutivePongMisses = 0;

    this.heartbeatTimer = null;
    this.livenessTimer = null;
    this.retryTimer = null;
  }

  // ---------------------------------------------------------------- 观察/事件

  /**
   * 注册**消息**监听器（按 type 分派，B-4）。用 `'*'` 订阅全部类型。
   *
   * 注册后会把缓冲中同 type 的帧按到达顺序补投（B-5）。
   *
   * @param {string} type 消息 type 或 `'*'`。
   * @param {(message: object, raw: string) => void} handler 回调。
   * @returns {() => void} 注销函数。
   */
  on(type, handler) {
    if (typeof handler !== 'function') throw new TypeError('handler 必须是函数');
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(handler);
    this.flushPending(type);
    return () => {
      const set = this.listeners.get(type);
      if (set) set.delete(handler);
    };
  }

  /**
   * 注册**客户端事件**监听器（传输层）。
   *
   * 事件：`open`、`close`、`closed`、`sent`、`message`、`dropped`、`msgBlocked`、
   * `protocolError`、`serverError`、`terminal`、`pongMiss`、`linkFailure`、
   * `reconnectScheduled`、`reconnectSkipped`、`reconnectGiveUp`、`rejoinScheduled`、
   * `rejoinGiveUp`、`dropSimulated`、`bufferOverflow`、`stateChanged`。
   *
   * @param {string} name 事件名。
   * @param {(payload: object) => void} handler 回调。
   * @returns {() => void} 注销函数。
   */
  onEvent(name, handler) {
    if (typeof handler !== 'function') throw new TypeError('handler 必须是函数');
    if (!this.eventListeners.has(name)) this.eventListeners.set(name, new Set());
    this.eventListeners.get(name).add(handler);
    return () => {
      const set = this.eventListeners.get(name);
      if (set) set.delete(handler);
    };
  }

  /** 待投缓冲长度。 */
  get pendingCount() {
    return this.pending.length;
  }

  /** 当前是否已连上（socket OPEN）。 */
  get isOpen() {
    return this.socket !== null && this.socket.readyState === WS_OPEN;
  }

  /** 客户端全景快照（面板与单测用；不含 SDP 正文）。 */
  snapshot() {
    return {
      url: this.url,
      state: this.state,
      isOpen: this.isOpen,
      roomId: this.currentRoomId,
      peerId: this.peerId,
      pendingCreate: this.pendingCreate,
      pendingRoomId: this.pendingRoomId,
      pendingCount: this.pending.length,
      droppedPending: this.droppedPending,
      reconnectAttempts: this.reconnectAttempts,
      rejoinAttempts: this.rejoinAttempts,
      reconnectSuppressed: this.reconnectSuppressed,
      reconnectExhausted: this.reconnectExhausted,
      rejoinAfterDrop: this.rejoinAfterDrop,
      consecutivePongMisses: this.consecutivePongMisses,
      pingSentAtMs: this.pingSentAtMs,
      lastPongAtMs: this.lastPongAtMs,
      framesSent: this.framesSent,
      framesReceived: this.framesReceived,
      socketsCreated: this.socketsCreated,
      socketsClosed: this.socketsClosed,
      lastError: this.lastError,
      lastClose: this.lastClose,
      terminalReason: this.terminalReason,
      timelineCount: this.timeline.size,
    };
  }

  // ---------------------------------------------------------------- 生命周期

  /**
   * 打开连接（已连上则幂等返回 true）。
   *
   * @returns {boolean} 是否发起了连接。
   */
  connect() {
    this.closedByUser = false;
    this.reconnectSuppressed = false;
    if (this.socket !== null) return true;
    return this.openSocket();
  }

  /** 建房：连接（必要时）→ 发 `create`（App 的 `createRoom` 同语义）。 */
  createRoom() {
    this.closedByUser = false;
    this.reconnectSuppressed = false;
    this.reconnectExhausted = false;
    this.rejoinAfterDrop = false;
    this.reconnectAttempts = 0;
    this.rejoinAttempts = 0;
    this.currentRoomId = null;
    this.terminalReason = '';
    this.pendingCreate = true;
    this.pendingRoomId = null;
    if (this.socket === null) this.openSocket();
    else this.sendPendingIntent();
    return true;
  }

  /**
   * 入房：连接（必要时）→ 发 `join`。
   *
   * @param {string} roomId 用户输入的房间号（内部按 App 口径规范化）。
   * @returns {boolean} 房间号是否可用（不可用时不改变现有意图）。
   */
  joinRoom(roomId) {
    const normalized = normalizeRoomId(roomId);
    if (!isValidRoomId(normalized)) {
      this.emitEvent('protocolError', {
        phase: 'join', reason: 'invalid_room_id', input: String(roomId ?? ''), normalized,
      });
      return false;
    }
    this.closedByUser = false;
    this.reconnectSuppressed = false;
    this.reconnectExhausted = false;
    this.rejoinAfterDrop = false;
    this.reconnectAttempts = 0;
    this.rejoinAttempts = 0;
    this.terminalReason = '';
    this.pendingCreate = false;
    this.pendingRoomId = normalized;
    if (this.socket === null) this.openSocket();
    else this.sendPendingIntent();
    return true;
  }

  /**
   * 主动离开：发 `leave` → 关闭（1000）→ 不再重连（B-9）。
   *
   * @returns {boolean} 是否发出了 `leave` 帧。
   */
  leave() {
    this.closedByUser = true;
    this.reconnectSuppressed = false;
    this.reconnectExhausted = false;
    this.rejoinAfterDrop = false;
    this.reconnectAttempts = 0;
    this.rejoinAttempts = 0;
    this.currentRoomId = null;
    this.peerId = null;
    this.pendingCreate = false;
    this.pendingRoomId = null;
    const sent = this.isOpen ? this.send({ type: 'leave' }) : false;
    this.setState('closed', { reason: 'leave' });
    this.closeSocket(WS_CLOSE_NORMAL, 'leave');
    this.emitEvent('closed', { code: WS_CLOSE_NORMAL, reason: 'leave' });
    return sent;
  }

  /** 释放资源：不发 `leave`，只关闭连接与定时器。 */
  shutdown() {
    this.closedByUser = true;
    this.setState('closed', { reason: 'shutdown' });
    this.closeSocket(WS_CLOSE_NORMAL, 'shutdown');
  }

  /**
   * 模拟掉线（B-8 / F-2）：**只关闭 WS，不发 `leave`**，随后必须自动重连并拿回原席位。
   *
   * 与 `leave()` 的区别：不发送任何信令帧 ⇒ 服务端视为异常断线，席位保留 90 s。
   *
   * @returns {boolean} 是否确实断开了一个连接。
   */
  simulateDrop() {
    const socket = this.socket;
    if (socket === null) {
      this.emitEvent('dropSimulated', { closed: false, reason: 'no_socket' });
      return false;
    }
    this.dropRequested = true;
    this.stopHeartbeat();
    this.timeline.record({
      direction: DIRECTION.LOCAL,
      type: 'drop',
      summary: `simulated_drop room=${this.currentRoomId ?? '-'}`,
      level: LEVEL.WARN,
      note: 'simulateDrop: 只关闭 WS，不发送 leave',
    });
    this.setState('reconnecting', { reason: 'simulated_drop' });
    this.emitEvent('dropSimulated', { closed: true, roomId: this.currentRoomId, url: this.url });
    try {
      socket.close();
    } catch (err) {
      this.log('warn', 'drop close failed', { error: String(err) });
    }
    return true;
  }

  // ---------------------------------------------------------------- 发送

  /**
   * 发送一条消息（自动编码 + 记时间线）。
   *
   * @param {string|object} typeOrMessage type 值或含 `type` 的对象。
   * @param {object} [payload] 字段表。
   * @returns {boolean} 是否已写入 socket。
   */
  send(typeOrMessage, payload) {
    let raw;
    let type = '';
    let message = null;
    try {
      message = normalizeMessage(typeOrMessage, payload);
      type = message.type;
      raw = JSON.stringify(message);
    } catch (err) {
      this.log('error', 'encode failed', { type: String(typeOrMessage), error: err.message });
      this.emitEvent('protocolError', { phase: 'encode', error: err, raw: '' });
      return false;
    }
    if (raw.length > MAX_MESSAGE_SIZE) {
      this.log('error', 'frame too large', { type, bytes: raw.length, limit: MAX_MESSAGE_SIZE });
      this.emitEvent('protocolError', { phase: 'encode', reason: 'frame_too_large', type, bytes: raw.length });
      return false;
    }
    const socket = this.socket;
    if (socket === null || socket.readyState !== WS_OPEN) {
      this.log('warn', 'msg dropped: no open socket', { type });
      this.emitEvent('dropped', { reason: 'no_socket', type });
      return false;
    }
    try {
      socket.send(raw);
    } catch (err) {
      this.log('warn', 'msg dropped: send failed', { type, error: String(err) });
      this.emitEvent('dropped', { reason: 'send_failed', type });
      return false;
    }
    this.framesSent += 1;
    this.timeline.record({
      direction: DIRECTION.SEND,
      type,
      raw,
      summary: summarizeMessage(type, message),
    });
    this.log('debug', 'msg_sent', { type, bytes: raw.length });
    this.emitEvent('sent', { type, raw });
    return true;
  }

  /** 发 `create`。 */
  sendCreate() {
    return this.send({ type: 'create' });
  }

  /** 发 `join`。 */
  sendJoin(roomId) {
    const normalized = normalizeRoomId(roomId);
    if (!isValidRoomId(normalized)) return false;
    return this.send('join', { roomId: normalized });
  }

  /** 发 offer（未入房时本地拦截，§8.3）。 */
  sendOffer(sdp) {
    if (!this.requireMediaSignaling('offer')) return false;
    return this.send('offer', { sdp });
  }

  /** 发 answer（未入房时本地拦截，§8.3）。 */
  sendAnswer(sdp) {
    if (!this.requireMediaSignaling('answer')) return false;
    return this.send('answer', { sdp });
  }

  /**
   * 发 ICE 候选（未入房时本地拦截）。
   *
   * @param {string} candidate 候选文本。
   * @param {string|null} [sdpMid] 媒体标识（可空）。
   * @param {number|null} [sdpMLineIndex] 媒体行索引（可空，0 为合法值）。
   */
  sendIce(candidate, sdpMid = null, sdpMLineIndex = null) {
    if (!this.requireMediaSignaling('ice')) return false;
    const payload = { candidate };
    if (sdpMid !== undefined && sdpMid !== null) payload.sdpMid = sdpMid;
    if (sdpMLineIndex !== undefined && sdpMLineIndex !== null) payload.sdpMLineIndex = sdpMLineIndex;
    return this.send('ice', payload);
  }

  /** 发 `natType`（未入房时本地拦截）。 */
  sendNatType(natType) {
    if (!this.requireMediaSignaling('natType')) return false;
    return this.send('natType', { natType });
  }

  /** 发一次心跳 `ping`（正常由心跳定时器驱动）。 */
  sendPing() {
    if (!this.isOpen) return false;
    const sentAt = Math.round(this.now());
    this.pingSentAtMs = sentAt;
    return this.send('ping', { timestamp: sentAt });
  }

  // ---------------------------------------------------------------- 内部：连接

  /** @returns {boolean} */
  openSocket() {
    this.clearRetry();
    // B-6：socket 重建前先关闭/取消旧链接，避免双 socket。
    this.closeSocket(WS_CLOSE_NORMAL, 'replace');
    this.setState('connecting', { url: this.url });
    let socket;
    try {
      socket = this.socketFactory(this.url);
    } catch (err) {
      this.log('error', 'socket create failed', { error: String(err) });
      this.timeline.record({
        direction: DIRECTION.LOCAL, type: 'socket_error', level: LEVEL.ERROR,
        summary: String(err && err.message ? err.message : err), note: 'socket_create_failed',
      });
      this.emitEvent('protocolError', { phase: 'connect', error: err });
      this.scheduleReconnect('socket_create_failed');
      return false;
    }
    this.socketsCreated += 1;
    this.socket = socket;
    socket.onopen = () => this.handleOpen(socket);
    socket.onmessage = (event) => this.handleFrame(socket, event);
    socket.onclose = (event) => this.handleClose(socket, event);
    socket.onerror = (event) => this.handleSocketError(socket, event);
    this.log('info', 'ws_connecting', { url: this.url });
    return true;
  }

  /**
   * 关闭当前 socket 并把引用置空；旧 socket 之后触发的 onclose 会被「陈旧 socket」判定忽略。
   *
   * @param {number} [code] 关闭码。
   * @param {string} [reason] 关闭原因。
   */
  closeSocket(code = WS_CLOSE_NORMAL, reason = '') {
    this.stopHeartbeat();
    const socket = this.socket;
    this.socket = null;
    if (socket === null) return;
    this.socketsClosed += 1;
    try {
      socket.close(code, reason);
    } catch (err) {
      this.log('debug', 'socket close failed', { error: String(err) });
    }
  }

  /** @param {object} socket */
  handleOpen(socket) {
    if (this.socket !== socket) return; // 陈旧 socket
    this.reconnectAttempts = 0;
    // 注意：**不**在这里清 rejoinAfterDrop —— 与 App 的 onOpen 一致，只有「重新拿到
    // created/joined」（onRoomEstablished）才算脱离重连语境。否则重连刚 open 就收到
    // ROOM_FULL（席位尚未回收）会被误判成「首次入房满房」而放弃有界重试。
    this.lastPongAtMs = this.now();
    this.pingSentAtMs = 0;
    this.consecutivePongMisses = 0;
    this.setState('connected', { url: this.url });
    this.timeline.record({
      direction: DIRECTION.LOCAL, type: 'ws_open', summary: `url=${this.url}`,
    });
    this.log('info', 'ws_open', { url: this.url });
    this.emitEvent('open', { url: this.url });
    this.startHeartbeat();
    this.sendPendingIntent();
  }

  /** @param {object} socket @param {object} event */
  handleFrame(socket, event) {
    if (this.socket !== socket) return; // 陈旧 socket
    const raw = frameToText(event && event.data !== undefined ? event.data : event);
    this.framesReceived += 1;
    let message = null;
    let error = null;
    if (raw.length > MAX_MESSAGE_SIZE) {
      error = new SignalingCodecError(`帧超过 ${MAX_MESSAGE_SIZE} 字节上限`, { recoverable: false });
    } else {
      try {
        message = decodeMessage(raw);
      } catch (err) {
        error = err;
      }
    }
    const type = message !== null ? message.type : (error && error.type !== '' ? error.type : peekType(raw));
    this.timeline.record({
      direction: DIRECTION.RECV,
      type,
      raw,
      summary: message !== null ? summarizeMessage(message.type, message) : (error ? error.message : ''),
      level: error !== null ? LEVEL.ERROR : LEVEL.INFO,
      note: error !== null ? error.code : '',
    });
    if (error !== null) {
      // §2.2：未知 type / 非法帧只记录并忽略，**不得**抛异常中断连接。
      this.log('warn', 'frame ignored', { type, code: error.code, error: error.message });
      this.emitEvent('protocolError', { phase: 'decode', error, raw, type });
      return;
    }
    this.log('debug', 'msg_received', { type, bytes: raw.length });
    if (message.type === 'pong') {
      this.lastPongAtMs = this.now();
      this.pingSentAtMs = 0;
      this.consecutivePongMisses = 0;
    }
    this.emitEvent('message', { type: message.type, message, raw });
    this.dispatch(message, raw);
    this.advanceState(message);
  }

  /** @param {object} socket @param {object} event */
  handleSocketError(socket, event) {
    if (this.socket !== socket) return;
    const detail = event && event.message ? String(event.message) : 'socket error';
    this.timeline.record({
      direction: DIRECTION.LOCAL, type: 'socket_error', level: LEVEL.ERROR,
      summary: detail, note: 'ws_error',
    });
    this.log('warn', 'ws_error', { detail });
  }

  /** @param {object} socket @param {object} event */
  handleClose(socket, event) {
    if (this.socket !== socket) return; // 陈旧 socket（已被 closeSocket 主动替换）
    this.socket = null;
    this.stopHeartbeat();
    const code = event && typeof event.code === 'number' ? event.code : 1005;
    const reason = event && typeof event.reason === 'string' ? event.reason : '';
    this.lastClose = { code, reason, wasClean: !!(event && event.wasClean) };
    this.timeline.record({
      direction: DIRECTION.LOCAL, type: 'ws_close', level: code === WS_CLOSE_NORMAL ? LEVEL.INFO : LEVEL.WARN,
      summary: `code=${code} reason=${reason}`, note: 'ws_close',
    });
    this.log('info', 'ws_close', { code, reason });
    this.emitEvent('close', { code, reason, wasClean: this.lastClose.wasClean });
    if (this.closedByUser) {
      this.setState('closed', { reason: 'closed_by_user' });
      this.emitEvent('closed', { code, reason });
      return;
    }
    if (this.dropRequested) {
      // 模拟掉线：无论对端/浏览器报什么 close code，都必须重连（B-8）。
      this.dropRequested = false;
      this.scheduleReconnect('simulated_drop');
      return;
    }
    if (code === WS_CLOSE_NORMAL) {
      // B-6：显式处理 1000 —— 正常关闭不重连。
      this.setState('closed', { reason: 'normal_close' });
      this.emitEvent('closed', { code, reason, reconnect: false });
      return;
    }
    this.scheduleReconnect(`closed:${code}`);
  }

  /** 按 created/joined/peerJoined/peerLeft/error 推进状态机（B-4 之外的 UI 便利，不动协议语义）。 */
  advanceState(message) {
    switch (message.type) {
      case 'created':
      case 'joined':
        this.onRoomEstablished(message);
        break;
      case 'peerJoined':
        this.setState('in_call', { peerId: message.peerId });
        break;
      case 'peerLeft':
        // 对端离开 ≠ 传输层终态（F-4：面板回到「等待对端」）。
        this.peerId = null;
        this.setState('in_room', { reason: 'peer_left' });
        break;
      case 'error':
        this.handleServerError(message);
        break;
      default:
        break;
    }
  }

  /** @param {{roomId: string, peerId?: string}} message */
  onRoomEstablished(message) {
    this.currentRoomId = message.roomId;
    if (message.type === 'joined' && typeof message.peerId === 'string') this.peerId = message.peerId;
    this.rejoinAfterDrop = false;
    this.rejoinAttempts = 0;
    this.pendingCreate = false;
    this.pendingRoomId = message.roomId;
    this.terminalReason = '';
    this.setState('in_room', { roomId: message.roomId, peerId: this.peerId ?? '' });
  }

  /** 服务端 error 的三分类处置（§2.5）。 */
  handleServerError(message) {
    const decision = classifyError(message.code, { rejoinAfterDrop: this.rejoinAfterDrop });
    this.lastError = { code: message.code, message: message.message, atMs: this.now() };
    this.timeline.record({
      direction: DIRECTION.LOCAL, type: 'error_policy', level: LEVEL.ERROR,
      summary: `code=${message.code} action=${decision.action}`,
      note: decision.clearsRoomIntent ? 'clears_room_intent' : 'keeps_room_intent',
    });
    this.emitEvent('serverError', { ...message, action: decision.action });
    if (decision.action === ErrorAction.RETRY_REJOIN) {
      // 保留房间意图，有界退避重试 join（绝不在此清空 pendingRoomId）。
      this.scheduleRejoinRetry(message.code);
      return;
    }
    if (decision.action === ErrorAction.TERMINAL_SUPPRESS) {
      this.reconnectSuppressed = true;
      if (decision.clearsRoomIntent) {
        this.pendingCreate = false;
        this.pendingRoomId = null;
        this.currentRoomId = null;
      }
      this.terminalReason = message.code === ERROR_CODES.ROOM_NOT_FOUND
        ? `房间已失效（${message.code}）：${message.message || 'Room does not exist'}`
        : `连接进入终态（${message.code}）：${message.message || ''}`;
      this.setState('failed', { code: message.code });
      this.emitEvent('terminal', { code: message.code, message: message.message, reason: this.terminalReason });
      return;
    }
    // SURFACE：不抑制、不重试，只把错误码交给 UI（状态机不动）。
    this.emitEvent('protocolError', { phase: 'server', code: message.code, message: message.message });
  }

  // ---------------------------------------------------------------- 内部：意图与心跳

  /** 连接建立后（重）发待执行意图：`create` 或 `join(roomId)`。 */
  sendPendingIntent() {
    if (this.pendingCreate) {
      this.sendCreate();
      return;
    }
    if (typeof this.pendingRoomId === 'string' && this.pendingRoomId !== '') {
      this.sendJoin(this.pendingRoomId);
    }
  }

  /** 未入房时拦截 offer/answer/ice/natType（§8.3 第 2 条）。 */
  requireMediaSignaling(type) {
    if (this.currentRoomId !== null) return true;
    this.log('warn', 'msg_blocked', { type, reason: 'not_in_room' });
    this.emitEvent('msgBlocked', { type, reason: 'not_in_room', state: this.state });
    return false;
  }

  /** 启动心跳与判活（首次 ping 在 15 s 后，与 App 的 scheduleWithFixedDelay 同相位）。 */
  startHeartbeat() {
    this.stopHeartbeat();
    this.scheduleHeartbeatTick();
    this.scheduleLivenessTick();
  }

  scheduleHeartbeatTick() {
    if (this.setTimeoutImpl === null) return;
    this.heartbeatTimer = this.setTimeoutImpl(() => {
      this.heartbeatTimer = null;
      if (this.socket === null) return;
      this.sendPing();
      this.scheduleHeartbeatTick();
    }, PING_INTERVAL_MS);
  }

  scheduleLivenessTick() {
    if (this.setTimeoutImpl === null) return;
    this.livenessTimer = this.setTimeoutImpl(() => {
      this.livenessTimer = null;
      this.checkLiveness();
      if (this.socket !== null) this.scheduleLivenessTick();
    }, PONG_TIMEOUT_MS);
  }

  stopHeartbeat() {
    if (this.heartbeatTimer !== null && this.clearTimeoutImpl !== null) {
      this.clearTimeoutImpl(this.heartbeatTimer);
    }
    if (this.livenessTimer !== null && this.clearTimeoutImpl !== null) {
      this.clearTimeoutImpl(this.livenessTimer);
    }
    this.heartbeatTimer = null;
    this.livenessTimer = null;
  }

  /** 一次存活窗口判定（B-2：单次 miss 只记事件，连续 4 次才断线）。 */
  checkLiveness() {
    const nowMs = this.now();
    if (!pongMissed(nowMs, this.pingSentAtMs, this.lastPongAtMs, PONG_TIMEOUT_MS)) return;
    this.consecutivePongMisses += 1;
    const payload = {
      count: this.consecutivePongMisses,
      tolerance: PONG_MISS_TOLERANCE,
      timeoutMs: PONG_TIMEOUT_MS,
      failAfterMs: PONG_FAIL_AFTER_MS,
    };
    this.timeline.record({
      direction: DIRECTION.LOCAL, type: 'pong_miss', level: LEVEL.WARN,
      summary: `count=${payload.count}/${PONG_MISS_TOLERANCE} timeout_ms=${PONG_TIMEOUT_MS}`,
    });
    this.log('warn', 'pong_miss', payload);
    this.emitEvent('pongMiss', payload);
    if (pongTimeoutReached(this.consecutivePongMisses, PONG_MISS_TOLERANCE)) {
      this.timeline.record({
        direction: DIRECTION.LOCAL, type: 'ws_pong_timeout', level: LEVEL.ERROR,
        summary: `misses=${this.consecutivePongMisses} fail_after_ms=${PONG_FAIL_AFTER_MS}`,
      });
      this.log('warn', 'ws_pong_timeout', payload);
      this.emitEvent('linkFailure', { reason: 'pong_timeout', ...payload });
      this.consecutivePongMisses = 0;
      this.pingSentAtMs = 0;
      this.stopHeartbeat();
      const socket = this.socket;
      this.socket = null; // 先置空 ⇒ 旧 socket 的 onclose 走「陈旧」分支，不会双重重连
      if (socket !== null) {
        this.socketsClosed += 1;
        try {
          socket.close();
        } catch (err) {
          this.log('debug', 'socket close failed', { error: String(err) });
        }
      }
      this.scheduleReconnect('pong_timeout');
      return;
    }
    // 容忍期内：立刻重发一个 ping，把窗口推进到下一段（与 App 的 pong_miss 分支一致）。
    this.sendPing();
  }

  // ---------------------------------------------------------------- 内部：重连

  /**
   * 断线重连（与 rejoin 共用 1-2-4-8 s 退避与 10 次上限，B-3）。
   *
   * @param {string} reason 触发原因。
   */
  scheduleReconnect(reason) {
    if (this.closedByUser) {
      this.emitEvent('reconnectSkipped', { reason, why: 'closed_by_user' });
      return;
    }
    if (this.reconnectSuppressed) {
      this.emitEvent('reconnectSkipped', { reason, why: 'terminal_error' });
      return;
    }
    const inRoomBeforeDrop = this.currentRoomId !== null;
    if (inRoomBeforeDrop) this.rejoinAfterDrop = true;
    const attempt = this.reconnectAttempts + 1;
    this.reconnectAttempts = attempt;
    if (attempt > MAX_REJOIN_ATTEMPTS) {
      this.reconnectExhausted = true;
      this.timeline.record({
        direction: DIRECTION.LOCAL, type: 'ws_reconnect_give_up', level: LEVEL.ERROR,
        summary: `attempts=${attempt} budget_ms=${rejoinBudgetMs()} room=${this.currentRoomId ?? '-'}`,
      });
      this.log('error', 'ws_reconnect_give_up', { attempts: attempt, budgetMs: rejoinBudgetMs() });
      this.emitEvent('reconnectGiveUp', {
        attempts: attempt, budgetMs: rejoinBudgetMs(), inRoomBeforeDrop, roomId: this.currentRoomId,
      });
      this.setState('failed', { reason: 'reconnect_give_up' });
      return;
    }
    const delayMs = rejoinDelayMs(attempt);
    this.timeline.record({
      direction: DIRECTION.LOCAL, type: 'ws_reconnect_scheduled', level: LEVEL.WARN,
      summary: `attempt=${attempt} delay_ms=${delayMs} reason=${reason} room=${this.currentRoomId ?? '-'}`,
    });
    this.log('warn', 'ws_reconnect_scheduled', { attempt, reason, delayMs, maxAttempts: MAX_REJOIN_ATTEMPTS });
    this.emitEvent('reconnectScheduled', {
      attempt, reason, delayMs, budgetMs: rejoinBudgetMs(), maxAttempts: MAX_REJOIN_ATTEMPTS,
    });
    this.setState('reconnecting', { reason, attempt, delayMs });
    this.clearRetry();
    this.retryTimer = this.setTimeoutImpl === null ? null : this.setTimeoutImpl(() => {
      this.retryTimer = null;
      this.openSocket();
    }, delayMs);
    if (this.setTimeoutImpl === null) this.openSocket(); // 无定时器实现时立即重试（测试可注入虚拟时钟）
  }

  /**
   * ROOM_FULL（掉线重连语境）的有界重试：保留房间意图，退避后重开 socket 重新 join。
   *
   * @param {string} code 触发重试的错误码。
   */
  scheduleRejoinRetry(code) {
    const attempt = this.rejoinAttempts + 1;
    this.rejoinAttempts = attempt;
    if (attempt > MAX_REJOIN_ATTEMPTS) {
      this.reconnectSuppressed = true;
      this.pendingCreate = false;
      this.pendingRoomId = null;
      const room = this.currentRoomId;
      this.currentRoomId = null;
      this.timeline.record({
        direction: DIRECTION.LOCAL, type: 'ws_rejoin_give_up', level: LEVEL.ERROR,
        summary: `attempts=${attempt} budget_ms=${rejoinBudgetMs()} room=${room ?? '-'}`,
      });
      this.emitEvent('rejoinGiveUp', { attempts: attempt, budgetMs: rejoinBudgetMs(), roomId: room, code });
      this.setState('failed', { reason: 'rejoin_give_up' });
      return;
    }
    const delayMs = rejoinDelayMs(attempt);
    this.timeline.record({
      direction: DIRECTION.LOCAL, type: 'ws_rejoin_scheduled', level: LEVEL.WARN,
      summary: `attempt=${attempt} delay_ms=${delayMs} code=${code} room=${this.currentRoomId ?? '-'}`,
    });
    this.log('warn', 'ws_rejoin_scheduled', { attempt, code, delayMs });
    this.emitEvent('rejoinScheduled', {
      attempt, code, delayMs, budgetMs: rejoinBudgetMs(), roomId: this.currentRoomId,
    });
    this.setState('reconnecting', { reason: `rejoin:${code}`, attempt, delayMs });
    this.clearRetry();
    this.retryTimer = this.setTimeoutImpl === null ? null : this.setTimeoutImpl(() => {
      this.retryTimer = null;
      this.openSocket();
    }, delayMs);
    if (this.setTimeoutImpl === null) this.openSocket();
  }

  clearRetry() {
    if (this.retryTimer !== null && this.clearTimeoutImpl !== null) {
      this.clearTimeoutImpl(this.retryTimer);
    }
    this.retryTimer = null;
  }

  // ---------------------------------------------------------------- 内部：分派与缓冲

  /** 是否已有该 type 的监听器（`'*'` 视为全类型）。 */
  hasListener(type) {
    const exact = this.listeners.get(type);
    if (exact !== undefined && exact.size > 0) return true;
    const wildcard = this.listeners.get('*');
    return wildcard !== undefined && wildcard.size > 0;
  }

  /**
   * 按 type 分派一条消息；无监听器时入缓冲（上限 32，超出丢弃最旧，B-5）。
   *
   * @param {object} message 已解析消息。
   * @param {string} raw 原始帧。
   */
  dispatch(message, raw) {
    if (this.hasListener(message.type)) {
      this.deliver({ type: message.type, message, raw });
      return;
    }
    this.pending.push({ type: message.type, message, raw, atMs: this.now() });
    while (this.pending.length > MAX_PENDING_MESSAGES) {
      const dropped = this.pending.shift();
      this.droppedPending += 1;
      this.log('warn', 'pending overflow', { dropped: dropped.type, limit: MAX_PENDING_MESSAGES });
      this.emitEvent('bufferOverflow', { dropped: dropped.type, limit: MAX_PENDING_MESSAGES });
    }
  }

  /** 调用某条消息的全部监听器（精确 type + `'*'`），单个监听器抛错不影响其它监听器。 */
  deliver(item) {
    const targets = [];
    const exact = this.listeners.get(item.type);
    if (exact) targets.push(...exact);
    const wildcard = this.listeners.get('*');
    if (wildcard) targets.push(...wildcard);
    for (const handler of targets) {
      try {
        handler(item.message, item.raw);
      } catch (err) {
        this.log('error', 'listener threw', { type: item.type, error: String(err) });
      }
    }
  }

  /** 注册监听器后补投缓冲（B-5：按到达顺序）。 */
  flushPending(type) {
    if (this.pending.length === 0) return;
    const deliver = [];
    const keep = [];
    for (const item of this.pending) {
      if (type === '*' || item.type === type) deliver.push(item);
      else keep.push(item);
    }
    this.pending = keep;
    for (const item of deliver) this.deliver(item);
  }

  // ---------------------------------------------------------------- 内部：杂项

  /**
   * 推进状态机并广播。
   *
   * @param {string} next 新状态（{@link SIGNALING_STATES}）。
   * @param {object} [detail] 附加上下文。
   */
  setState(next, detail = {}) {
    const from = this.state;
    if (from === next) return;
    this.state = next;
    this.timeline.record({
      direction: DIRECTION.LOCAL, type: 'state', summary: `${from} → ${next}`,
      note: typeof detail.reason === 'string' ? detail.reason : '',
    });
    this.log('info', 'state', { from, to: next, ...detail });
    this.emitEvent('stateChanged', { from, to: next, ...detail });
  }

  /**
   * 广播客户端事件。
   *
   * @param {string} name 事件名。
   * @param {object} [payload] 负载。
   */
  emitEvent(name, payload = {}) {
    const set = this.eventListeners.get(name);
    if (set === undefined || set.size === 0) return;
    for (const handler of [...set]) {
      try {
        handler(payload);
      } catch (err) {
        this.log('error', 'event listener threw', { event: name, error: String(err) });
      }
    }
  }

  /**
   * 固定前缀日志（B-12 / O-8：`[sig]`，打开 DevTools 即可复跑观测）。
   *
   * @param {'debug'|'info'|'warn'|'error'} level 级别。
   * @param {string} message 文本。
   * @param {object} [data] 结构化数据。
   */
  log(level, message, data) {
    const logger = this.logger;
    if (logger === null || logger === undefined) return;
    const fn = typeof logger[level] === 'function' ? logger[level].bind(logger) : logger.log?.bind(logger);
    if (typeof fn !== 'function') return;
    if (data === undefined) fn(`[sig] ${message}`);
    else fn(`[sig] ${message}`, data);
  }
}

export default SignalingClient;
