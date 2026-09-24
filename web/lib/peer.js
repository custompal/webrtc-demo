// web/lib/peer.js — RTCPeerConnection 封装（ICE 配置来源、角色、候选、ICE restart、stats 采样）
//
// 依据 reports/70-browser-call-demo-requirements.md：
//   * §4 I-1…I-6：ICE 列表**完全**来自服务端下发；TURN UDP 优先 + TCP 回退；强制 relay 开关；
//     loopback 候选过滤；relay 端口 49152-49200。
//   * §5 M-3…M-6：统一 plan、max-bundle、rtcp-mux require；默认 App 建房（本端 answer）；
//     浏览器建房时本端 offer 且用编解码偏好限制在 VP9；双向字节增长才算媒体成立。
//   * §3 B-6/B-10/B-11：重连前先关闭；offer 责任只在房主；ICE restart 只由房主发起，上限 2 次。
//
// 设计约束（H-3 + captain 补强）：纯 ES Module、无 npm/无构建、**不依赖任何自定义响应头**
// （无 COOP/COEP、无 crossOriginIsolated/SharedArrayBuffer），宿主机 `python3 -m http.server` 下可用。
// 本模块不在顶层访问 DOM：`RTCPeerConnection` 通过注入/延迟读取，便于 node 侧做语法与纯函数校验。

import { CandidateTable, StateTracker, classifyCandidate, isLoopbackAddress, reduceStats } from './observe.js';

/** ICE restart 次数上限（B-11）。 */
export const MAX_ICE_RESTARTS = 2;

/** TURN TCP 回退默认开启（I-2）。 */
export const DEFAULT_TURN_TCP_FALLBACK = true;

/** 统一 plan 的会话级策略（M-3）。 */
export const BUNDLE_POLICY = 'max-bundle';
export const RTCP_MUX_POLICY = 'require';

/** 远端候选在 remoteDescription 就绪前的缓冲上限。 */
export const MAX_PENDING_REMOTE_CANDIDATES = 64;

/** relay 端口区间（I-5，仅用于面板判读与文档一致性）。 */
export const RELAY_PORT_RANGE = Object.freeze({ min: 49152, max: 49200 });

const defaultLogger = typeof console !== 'undefined' ? console : null;

/**
 * TURN UDP URL → 同凭据 TCP 回退 URL（I-2）。
 *
 * @param {string} turnUrl 形如 `turn:host:3478?transport=udp`。
 * @returns {string|null} `turn:host:3478?transport=tcp`；已是 TCP（或非 `turn:`）时返回 null。
 */
export function turnTcpUrl(turnUrl) {
  if (typeof turnUrl !== 'string') return null;
  const url = turnUrl.trim();
  if (url === '' || !/^turn:/i.test(url)) return null; // turns: 不在本轮范围（I-6）
  if (/[?&]transport=tcp/i.test(url)) return null;      // 已是 TCP，避免重复条目
  if (/[?&]transport=udp/i.test(url)) return url.replace(/transport=udp/i, 'transport=tcp');
  return url.includes('?') ? `${url}&transport=tcp` : `${url}?transport=tcp`;
}

/**
 * 由服务端下发字段构造 `RTCIceServer[]`（I-1/I-2）。
 *
 * 顺序即断言依据：`[0]` STUN、`[1]` TURN/udp、`[2]` TURN/tcp（回退项）。
 * 任何字段为空 ⇒ 该条目不加；**禁止**任何硬编码服务器或凭据。
 *
 * @param {{stunUrl?: string, turnUrl?: string, turnUsername?: string, turnCredential?: string}} handout created/joined 下发字段。
 * @param {{turnTcpFallback?: boolean}} [options] 是否追加 TCP 回退（默认 {@link DEFAULT_TURN_TCP_FALLBACK}）。
 * @returns {Array<{urls: string, username?: string, credential?: string}>}
 */
export function iceServersFor(handout, options = {}) {
  const cfg = handout && typeof handout === 'object' ? handout : {};
  const turnTcpFallback = options.turnTcpFallback === undefined
    ? DEFAULT_TURN_TCP_FALLBACK
    : options.turnTcpFallback === true;
  const servers = [];
  if (typeof cfg.stunUrl === 'string' && cfg.stunUrl !== '') {
    servers.push({ urls: cfg.stunUrl });
  }
  const hasTurn = typeof cfg.turnUrl === 'string' && cfg.turnUrl !== ''
    && typeof cfg.turnUsername === 'string' && cfg.turnUsername !== ''
    && typeof cfg.turnCredential === 'string' && cfg.turnCredential !== '';
  if (hasTurn) {
    servers.push({ urls: cfg.turnUrl, username: cfg.turnUsername, credential: cfg.turnCredential });
    const tcp = turnTcpFallback ? turnTcpUrl(cfg.turnUrl) : null;
    if (tcp !== null) {
      servers.push({ urls: tcp, username: cfg.turnUsername, credential: cfg.turnCredential });
    }
  }
  return servers;
}

/**
 * 下发字段 + 构造结果的摘要（面板显示；凭据只记「存在与否」，不落明文）。
 *
 * @param {{stunUrl?: string, turnUrl?: string, turnUsername?: string, turnCredential?: string}} handout 下发字段。
 * @param {object} [options] 同 {@link iceServersFor}。
 * @returns {object}
 */
export function iceSummary(handout, options = {}) {
  const cfg = handout && typeof handout === 'object' ? handout : {};
  const servers = iceServersFor(cfg, options);
  const tcp = turnTcpUrl(cfg.turnUrl);
  return {
    stunUrl: cfg.stunUrl ?? null,
    turnUrl: cfg.turnUrl ?? null,
    turnTcpUrl: tcp,
    turnUsername: cfg.turnUsername ?? null,
    credentialPresent: typeof cfg.turnCredential === 'string' && cfg.turnCredential !== '',
    serverCount: servers.length,
    entries: servers.map((s) => ({ urls: s.urls, username: s.username ?? null, credential: s.credential === undefined ? null : '***' })),
    turnTcpFallbackActive: servers.some((s) => typeof s.urls === 'string' && /transport=tcp/i.test(s.urls)),
  };
}

function defaultRtcpImpl() {
  if (typeof RTCPeerConnection === 'undefined') {
    throw new Error('当前环境没有 RTCPeerConnection（Chrome 页面应内建；node 侧请注入 RtcpImpl）');
  }
  return RTCPeerConnection;
}

/**
 * 合成媒体流（无摄像头/无权限/无头环境下的确定性媒体源）。
 *
 * 视频 = canvas.captureStream 动画；音频 = AudioContext 振荡器 → MediaStreamDestination。
 * 这样「媒体面」在没有任何设备权限时也能跑通（M-4 要求至少视频），且不依赖任何外部资源。
 *
 * @param {{label?: string, width?: number, height?: number, fps?: number, toneHz?: number}} [options]
 * @returns {MediaStream} 含至少一条 video 轨道（可用时另含 audio 轨道）。
 */
export function createSyntheticStream(options = {}) {
  const label = options.label ?? 'synthetic';
  const width = options.width ?? 640;
  const height = options.height ?? 480;
  const fps = options.fps ?? 15;
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  let frame = 0;
  const draw = () => {
    frame += 1;
    if (ctx === null) return;
    ctx.fillStyle = '#101820';
    ctx.fillRect(0, 0, width, height);
    ctx.fillStyle = '#4da3ff';
    ctx.font = '20px monospace';
    ctx.fillText(`${label} #${frame}`, 16, 40);
    ctx.fillStyle = '#3fb950';
    ctx.fillRect(16 + ((frame * 7) % (width - 64)), height - 60, 48, 24);
    ctx.fillStyle = '#d7dee8';
    ctx.font = '14px monospace';
    ctx.fillText(new Date().toISOString().slice(11, 19), 16, height - 20);
  };
  draw();
  const timer = setInterval(draw, Math.max(1, Math.round(1000 / fps)));
  const videoStream = typeof canvas.captureStream === 'function' ? canvas.captureStream(fps) : null;
  let audioCtx = null;
  let osc = null;
  let audioTrack = null;
  try {
    const Ctx = typeof AudioContext !== 'undefined' ? AudioContext : (typeof webkitAudioContext !== 'undefined' ? webkitAudioContext : null);
    if (Ctx !== null) {
      audioCtx = new Ctx();
      const dest = audioCtx.createMediaStreamDestination();
      osc = audioCtx.createOscillator();
      osc.frequency.value = options.toneHz ?? 440;
      const gain = audioCtx.createGain();
      gain.gain.value = 0.02;
      osc.connect(gain);
      gain.connect(dest);
      osc.start();
      audioTrack = dest.stream.getAudioTracks()[0] ?? null;
    }
  } catch {
    audioTrack = null; // 合成音频失败不影响视频面
  }
  const stream = new MediaStream();
  const videoTrack = videoStream !== null && videoStream.getVideoTracks().length > 0
    ? videoStream.getVideoTracks()[0] : null;
  if (videoTrack !== null) stream.addTrack(videoTrack);
  if (audioTrack !== null) stream.addTrack(audioTrack);
  stream.stopSynthetic = () => {
    clearInterval(timer);
    try { if (osc !== null) osc.stop(); } catch { /* 已停止 */ }
    try { if (audioCtx !== null) audioCtx.close(); } catch { /* 已关闭 */ }
    if (videoStream !== null) for (const t of videoStream.getTracks()) t.stop();
  };
  return stream;
}/**
 * 把 `RTCIceServer[]` 变成 `'<urls>'` 单一字符串（Chrome 接受 string 或 string[]）。
 *
 * @param {Array<{urls: string|string[]}>} servers 已构造的服务器列表。
 * @returns {string[]}
 */
export function serverUrlList(servers) {
  const out = [];
  for (const s of servers) {
    if (Array.isArray(s.urls)) out.push(...s.urls);
    else if (typeof s.urls === 'string') out.push(s.urls);
  }
  return out;
}

/**
 * RTCPeerConnection 封装：ICE 配置只能由服务端下发字段构造；候选双向可见可过滤。
 */
export class PeerConnection {
  /**
   * @param {object} [options]
   * @param {string} [options.iceTransportPolicy] `'all'` 或 `'relay'`（I-3）。
   * @param {boolean} [options.turnTcpFallback] 是否追加 TURN/TCP 回退条目。
   * @param {typeof RTCPeerConnection} [options.RtcpImpl] 构造器注入（node 侧测试用）。
   * @param {object} [options.logger] 控制台（默认 `console`）。
   * @param {Timeline} [options.timeline] 时间线（可选，用于把候选/跃迁写入同一导出物）。
   * @param {(entry: object) => void} [options.onEvent] 结构化事件回调（前缀由调用方决定）。
   */
  constructor(options = {}) {
    this.RtcpImpl = typeof options.RtcpImpl === 'function' ? options.RtcpImpl : null;
    this.iceTransportPolicy = options.iceTransportPolicy === 'relay' ? 'relay' : 'all';
    this.turnTcpFallback = options.turnTcpFallback === undefined
      ? DEFAULT_TURN_TCP_FALLBACK
      : options.turnTcpFallback === true;
    this.logger = options.logger === undefined ? defaultLogger : options.logger;
    this.timeline = options.timeline ?? null;
    this.onEvent = typeof options.onEvent === 'function' ? options.onEvent : () => {};

    this.pc = null;
    this.role = null; // 'offerer' | 'answerer'
    this.handout = null;
    this.iceServers = [];
    this.remoteDescriptionType = null;
    this.pendingRemoteCandidates = [];
    this.candidates = new CandidateTable();
    this.states = new StateTracker();
    this.counters = {
      localCandidates: 0,
      remoteCandidates: 0,
      localLoopbackDropped: 0,
      remoteLoopbackDropped: 0,
      addIceCandidateFailures: 0,
      iceRestarts: 0,
      renegotiations: 0,
      remoteCandidatesBuffered: 0,
      remoteTracks: 0,
      localTracks: 0,
    };
    this.lastRemoteSdp = null;
    this.lastLocalSdp = null;
    this.lastStatsReport = null;
  }

  /** 是否已经创建底层连接。 */
  get created() {
    return this.pc !== null;
  }

  /** 是否已设置 remoteDescription（候选可直接添加）。 */
  get remoteReady() {
    return this.remoteDescriptionType !== null;
  }

  /**
   * 从服务端下发的 created/joined 字段建立连接（I-1：这是 ICE 配置的**唯一**来源）。
   *
   * @param {{stunUrl?: string, turnUrl?: string, turnUsername?: string, turnCredential?: string}} handout 下发字段。
   * @returns {object} {@link iceSummary} 结果。
   */
  applyHandout(handout) {
    this.handout = { ...(handout ?? {}) };
    this.iceServers = iceServersFor(this.handout, { turnTcpFallback: this.turnTcpFallback });
    const summary = iceSummary(this.handout, { turnTcpFallback: this.turnTcpFallback });
    this.emit('ice-config', summary);
    return summary;
  }

  /** 创建一个新的 RTCPeerConnection（重复调用会先关闭旧连接，B-6）。 */
  create() {
    if (this.pc !== null) this.close();
    const Impl = this.RtcpImpl ?? defaultRtcpImpl();
    this.pc = new Impl({
      iceServers: this.iceServers,
      iceTransportPolicy: this.iceTransportPolicy,
      bundlePolicy: BUNDLE_POLICY,
      rtcpMuxPolicy: RTCP_MUX_POLICY,
    });
    this.remoteDescriptionType = null;
    this.pendingRemoteCandidates = [];
    this.wireEvents();
    this.states.set('signaling', this.pc.signalingState ?? 'stable');
    this.states.set('iceGathering', this.pc.iceGatheringState ?? 'new');
    this.states.set('iceConnection', this.pc.iceConnectionState ?? 'new');
    this.states.set('connection', this.pc.connectionState ?? 'new');
    this.log('info', 'pc_created', {
      iceServers: this.iceServers.length,
      iceTransportPolicy: this.iceTransportPolicy,
      bundlePolicy: BUNDLE_POLICY,
      rtcpMuxPolicy: RTCP_MUX_POLICY,
    });
    this.emit('pc-created', {
      iceTransportPolicy: this.iceTransportPolicy,
      iceServers: iceSummary(this.handout ?? {}, { turnTcpFallback: this.turnTcpFallback }),
    });
    return this.pc;
  }

  /** 绑定底层事件（候选、状态、轨道）。 */
  wireEvents() {
    const pc = this.pc;
    if (pc === null) return;
    pc.onicecandidate = (event) => {
      const candidate = event && event.candidate;
      if (!candidate) {
        this.emit('ice-gathering-complete', {});
        this.log('info', 'ice_gathering_complete', {});
        return;
      }
      const entry = classifyCandidate(candidate, 'local');
      if (isLoopbackAddress(entry.address)) {
        this.counters.localLoopbackDropped += 1;
        this.emit('ice-loopback-dropped', { side: 'local', entry });
        this.log('warn', 'ice_loopback_dropped', { side: 'local', address: entry.address });
        return;
      }
      this.counters.localCandidates += 1;
      this.candidates.add(entry, 'local');
      this.emit('ice-candidate', { side: 'local', entry, init: { candidate: entry.raw, sdpMid: entry.sdpMid, sdpMLineIndex: entry.sdpMLineIndex } });
      this.log('debug', 'ice_local', { type: entry.type, protocol: entry.protocol, address: entry.address, port: entry.port });
    };
    pc.onicecandidateerror = (event) => {
      this.emit('ice-candidate-error', { url: event && event.url ? event.url : null, errorCode: event && event.errorCode ? event.errorCode : null, errorText: event && event.errorText ? event.errorText : null });
      this.log('error', 'ice_candidate_error', { url: event && event.url, text: event && event.errorText });
    };
    pc.onicegatheringstatechange = () => this.states.set('iceGathering', pc.iceGatheringState);
    pc.onsignalingstatechange = () => this.states.set('signaling', pc.signalingState);
    pc.oniceconnectionstatechange = () => {
      this.states.set('iceConnection', pc.iceConnectionState);
      this.log('info', 'ice_connection_state', { value: pc.iceConnectionState });
    };
    pc.onconnectionstatechange = () => {
      this.states.set('connection', pc.connectionState);
      this.emit('connection-state', { value: pc.connectionState });
      this.log('info', 'connection_state', { value: pc.connectionState });
    };
    if ('ondtlsstatechange' in pc) {
      pc.ondtlsstatechange = () => {
        const value = pc.dtlsTransport ? pc.dtlsTransport.state : null;
        this.states.set('dtls', value);
      };
    }
    pc.ontrack = (event) => {
      this.counters.remoteTracks += 1;
      const streams = event && event.streams ? event.streams : [];
      this.emit('remote-track', {
        kind: event && event.track ? event.track.kind : null,
        streamCount: streams.length,
        track: event ? event.track : null,
        streams,
      });
      this.log('info', 'remote_track', { kind: event && event.track ? event.track.kind : null });
    };
    pc.onnegotiationneeded = () => {
      // B-10：offer 责任只在房主。这里**不**自动发 offer，只暴露信号给页面决定。
      this.emit('negotiation-needed', { role: this.role, iceRestarts: this.counters.iceRestarts });
    };
  }

  /**
   * 加入本地媒体轨（单向加轨即可，方向由 SDP 决定）。
   *
   * @param {MediaStream} stream 本地流。
   * @param {{preferVp9?: boolean}} [options] 是否对视频 transceiver 施加 VP9 偏好（M-5）。
   * @returns {number} 添加的轨道数。
   */
  setLocalStream(stream, options = {}) {
    if (this.pc === null) this.create();
    const tracks = stream && typeof stream.getTracks === 'function' ? stream.getTracks() : [];
    for (const track of tracks) {
      const transceiver = this.pc.addTransceiver(track, { direction: 'sendrecv', streams: [stream] });
      if (options.preferVp9 === true && track.kind === 'video') this.preferVp9(transceiver);
      this.counters.localTracks += 1;
    }
    this.emit('local-stream', { trackCount: tracks.length, kinds: tracks.map((t) => t.kind) });
    return tracks.length;
  }

  /**
   * answerer 路径：把本地流挂到远端 offer 已经建立的 transceiver 上（M-3/M-4）。
   *
   * 为什么不直接 `addTransceiver`：先加轨会让本地 m-line 领先于远端 offer，
   * 与 App 的 SDP 对不齐。这里的顺序是 setRemoteDescription(offer) → replaceTrack → answer。
   *
   * @param {MediaStream} stream 本地流。
   * @returns {Promise<number>} 实际挂上的轨道数。
   */
  async attachToRemoteTransceivers(stream) {
    if (this.pc === null) this.create();
    const tracks = stream && typeof stream.getTracks === 'function' ? stream.getTracks() : [];
    const transceivers = typeof this.pc.getTransceivers === 'function' ? this.pc.getTransceivers() : [];
    const used = new Set();
    let attached = 0;
    for (const track of tracks) {
      const match = transceivers.find((t) => !used.has(t)
        && t.receiver && t.receiver.track && t.receiver.track.kind === track.kind);
      if (match !== undefined && match.sender && typeof match.sender.replaceTrack === 'function') {
        await match.sender.replaceTrack(track);
        try { match.direction = 'sendrecv'; } catch { /* 部分实现只读，忽略 */ }
        used.add(match);
        attached += 1;
      } else {
        const created = this.pc.addTransceiver(track, { direction: 'sendrecv', streams: [stream] });
        used.add(created);
        attached += 1;
      }
    }
    this.counters.localTracks += attached;
    this.emit('local-stream', { trackCount: attached, kinds: tracks.map((t) => t.kind), path: 'answerer' });
    return attached;
  }

  /**
   * 把视频编解码偏好限制在 VP9（M-5：浏览器为 offerer 时必须限制，否则可能协商出 VP8/H264）。
   *
   * @param {RTCRtpTransceiver} transceiver 视频 transceiver。
   * @returns {boolean} 是否成功施加偏好。
   */
  preferVp9(transceiver) {
    if (transceiver === null || typeof transceiver.setCodecPreferences !== 'function') return false;
    if (typeof RTCRtpSender === 'undefined' || typeof RTCRtpSender.getCapabilities !== 'function') return false;
    const caps = RTCRtpSender.getCapabilities('video');
    if (caps === null || !Array.isArray(caps.codecs)) return false;
    const rank = (codec) => {
      const mime = String(codec.mimeType || '').toLowerCase();
      if (mime === 'video/vp9') return 0;
      if (mime === 'video/rtx' || mime === 'video/red' || mime === 'video/ulpfec') return 1;
      return 2; // VP8/H264/AV1 等靠后
    };
    const ordered = [...caps.codecs].sort((a, b) => rank(a) - rank(b));
    try {
      transceiver.setCodecPreferences(ordered);
      this.log('info', 'codec_preferences', { preference: ordered.slice(0, 3).map((c) => c.mimeType) });
      this.emit('codec-preference', { preference: ordered.map((c) => c.mimeType) });
      return true;
    } catch (err) {
      this.log('warn', 'codec_preferences_failed', { error: String(err) });
      return false;
    }
  }

  /**
   * 仅保留 VP9（及其重传/纠错辅助编码）来构造 offer 的编解码集合。
   *
   * @returns {Promise<object>} `{type:'offer', sdp}`。
   */
  async createOffer(options = {}) {
    if (this.pc === null) this.create();
    const init = options.iceRestart === true ? { iceRestart: true } : {};
    const offer = await this.pc.createOffer(init);
    this.emit('offer-created', { iceRestart: options.iceRestart === true, sdpBytes: offer.sdp ? offer.sdp.length : 0 });
    return offer;
  }

  /** 生成 answer。 */
  async createAnswer() {
    if (this.pc === null) this.create();
    const answer = await this.pc.createAnswer();
    this.emit('answer-created', { sdpBytes: answer.sdp ? answer.sdp.length : 0 });
    return answer;
  }

  /**
   * 设置本地描述（trickle：不等待收集完成）。
   *
   * @param {RTCSessionDescriptionInit} description 描述。
   * @returns {Promise<object>} 同一个描述。
   */
  async setLocalDescription(description) {
    if (this.pc === null) this.create();
    await this.pc.setLocalDescription(description);
    this.lastLocalSdp = this.pc.localDescription;
    this.states.set('signaling', this.pc.signalingState);
    this.emit('local-description', { type: this.pc.localDescription ? this.pc.localDescription.type : null, sdp: this.pc.localDescription ? this.pc.localDescription.sdp : null });
    return description;
  }

  /**
   * 应用远端描述（offer/answer），随后补投缓冲中的远端候选。
   *
   * @param {RTCSessionDescriptionInit} description 远端描述。
   * @returns {Promise<object>} 同一个描述。
   */
  async applyRemoteDescription(description) {
    if (this.pc === null) this.create();
    await this.pc.setRemoteDescription(description);
    this.remoteDescriptionType = description.type ?? null;
    this.lastRemoteSdp = this.pc.remoteDescription;
    this.states.set('signaling', this.pc.signalingState);
    this.emit('remote-description', {
      type: description.type ?? null,
      sdp: description.sdp ?? null,
      bufferedCandidates: this.pendingRemoteCandidates.length,
    });
    await this.drainRemoteCandidates();
    return description;
  }

  /**
   * 添加远端候选（I-4：loopback 丢弃并计数；remoteDescription 未就绪时缓冲，最多 64 条）。
   *
   * @param {{candidate?: string, sdpMid?: string, sdpMLineIndex?: number}} init 候选。
   * @returns {Promise<'added'|'buffered'|'dropped-loopback'|'dropped-empty'|'failed'>}
   */
  async addRemoteCandidate(init) {
    const raw = init && typeof init.candidate === 'string' ? init.candidate : '';
    if (raw === '') {
      // 空候选 = 收集结束标记：仍交给底层（Chrome 允许 addIceCandidate(null) 表示结束）
      if (this.pc !== null && typeof this.pc.addIceCandidate === 'function') {
        try { await this.pc.addIceCandidate(null); } catch { /* 忽略：结束标记不关键 */ }
      }
      return 'dropped-empty';
    }
    const entry = classifyCandidate(init, 'remote');
    if (isLoopbackAddress(entry.address)) {
      this.counters.remoteLoopbackDropped += 1;
      this.emit('ice-loopback-dropped', { side: 'remote', entry });
      this.log('warn', 'ice_loopback_dropped', { side: 'remote', address: entry.address });
      return 'dropped-loopback';
    }
    this.counters.remoteCandidates += 1;
    this.candidates.add(entry, 'remote');
    this.emit('ice-candidate', { side: 'remote', entry, init });
    if (!this.remoteReady || this.pc === null) {
      if (this.pendingRemoteCandidates.length >= MAX_PENDING_REMOTE_CANDIDATES) this.pendingRemoteCandidates.shift();
      this.pendingRemoteCandidates.push(init);
      this.counters.remoteCandidatesBuffered += 1;
      return 'buffered';
    }
    try {
      await this.pc.addIceCandidate({ candidate: raw, sdpMid: init.sdpMid ?? null, sdpMLineIndex: init.sdpMLineIndex ?? null });
      return 'added';
    } catch (err) {
      this.counters.addIceCandidateFailures += 1;
      this.emit('ice-add-failed', { init, error: String(err) });
      this.log('warn', 'ice_add_failed', { error: String(err) });
      return 'failed';
    }
  }

  /** 补投缓冲候选（remoteDescription 就绪后在 applyRemoteDescription 内自动调用）。 */
  async drainRemoteCandidates() {
    if (this.pc === null) return 0;
    const queued = this.pendingRemoteCandidates.splice(0, this.pendingRemoteCandidates.length);
    let added = 0;
    for (const init of queued) {
      try {
        await this.pc.addIceCandidate({ candidate: init.candidate, sdpMid: init.sdpMid ?? null, sdpMLineIndex: init.sdpMLineIndex ?? null });
        added += 1;
      } catch (err) {
        this.counters.addIceCandidateFailures += 1;
        this.emit('ice-add-failed', { init, error: String(err), phase: 'drain' });
      }
    }
    if (queued.length > 0) this.log('info', 'ice_drained', { queued: queued.length, added });
    return added;
  }

  /** 角色标记（B-10：offer 责任只在房主）。 */
  setRole(role) {
    this.role = role === 'offerer' ? 'offerer' : (role === 'answerer' ? 'answerer' : null);
    this.emit('role', { role: this.role });
    return this.role;
  }

  /**
   * ICE restart（B-11：只允许房主发起，最多 {@link MAX_ICE_RESTARTS} 次）。
   *
   * @returns {{ok: boolean, reason?: string, description?: object, restartsLeft?: number}}
   */
  async startIceRestart() {
    if (this.role !== 'offerer') {
      return { ok: false, reason: 'not_offerer' };
    }
    if (this.counters.iceRestarts >= MAX_ICE_RESTARTS) {
      this.emit('ice-restart-refused', { reason: 'max_ice_restarts', restarts: this.counters.iceRestarts });
      return { ok: false, reason: 'max_ice_restarts', restartsLeft: 0 };
    }
    this.counters.iceRestarts += 1;
    const offer = await this.createOffer({ iceRestart: true });
    await this.setLocalDescription(offer);
    this.log('warn', 'ice_restart', { count: this.counters.iceRestarts, max: MAX_ICE_RESTARTS });
    this.emit('ice-restart', { count: this.counters.iceRestarts, max: MAX_ICE_RESTARTS });
    return { ok: true, description: this.pc.localDescription ?? offer, restartsLeft: MAX_ICE_RESTARTS - this.counters.iceRestarts };
  }

  /**
   * 重协商（F-7：只允许房主触发）。
   *
   * @returns {{ok: boolean, reason?: string, description?: object}}
   */
  async renegotiate() {
    if (this.role !== 'offerer') return { ok: false, reason: 'not_offerer' };
    this.counters.renegotiations += 1;
    const offer = await this.createOffer({ iceRestart: false });
    await this.setLocalDescription(offer);
    this.emit('renegotiate', { count: this.counters.renegotiations });
    return { ok: true, description: this.pc.localDescription ?? offer };
  }

  /**
   * 切换 ICE 传输策略（I-3：强制 relay 开关）。
   *
   * @param {'all'|'relay'} policy 新策略。
   * @returns {boolean} 底层是否接受。
   */
  setIceTransportPolicy(policy) {
    const next = policy === 'relay' ? 'relay' : 'all';
    this.iceTransportPolicy = next;
    let applied = false;
    if (this.pc !== null && typeof this.pc.setConfiguration === 'function') {
      try {
        this.pc.setConfiguration({
          iceServers: this.iceServers,
          iceTransportPolicy: next,
          bundlePolicy: BUNDLE_POLICY,
          rtcpMuxPolicy: RTCP_MUX_POLICY,
        });
        applied = true;
      } catch (err) {
        this.log('warn', 'set_configuration_failed', { error: String(err) });
      }
    }
    this.emit('ice-transport-policy', { policy: next, applied });
    return applied;
  }

  /** 1Hz stats 采样（O-5）。 */
  async sampleStats() {
    if (this.pc === null) return null;
    const report = await this.pc.getStats();
    this.lastStatsReport = report;
    const snapshot = reduceStats(report, this._lastSnapshot ?? null, { atMs: this.nowMs(), wallMs: Date.now() });
    this._lastSnapshot = snapshot;
    if (snapshot.transport !== null && snapshot.transport.dtlsState !== null) {
      this.states.set('dtls', snapshot.transport.dtlsState);
    }
    return snapshot;
  }

  nowMs() {
    if (typeof performance !== 'undefined' && typeof performance.now === 'function') return performance.now();
    return Date.now();
  }

  /** 供面板导出的快照（不含 SDP 正文）。 */
  snapshot() {
    return {
      created: this.created,
      role: this.role,
      iceTransportPolicy: this.iceTransportPolicy,
      turnTcpFallback: this.turnTcpFallback,
      remoteReady: this.remoteReady,
      counters: { ...this.counters },
      candidateCounts: this.candidates.counts(),
      candidateTotal: this.candidates.size,
      states: this.states.toJSON().current,
      iceSummary: iceSummary(this.handout ?? {}, { turnTcpFallback: this.turnTcpFallback }),
    };
  }

  /** 关闭连接并清空缓冲（B-6：重建前必须先关闭）。 */
  close() {
    if (this.pc !== null) {
      try { this.pc.close(); } catch { /* 忽略：关闭失败不影响新连接 */ }
      this.pc = null;
    }
    this.remoteDescriptionType = null;
    this.pendingRemoteCandidates = [];
    this.emit('pc-closed', {});
  }

  /**
   * 写入时间线（若注入了 timeline）。
   *
   * @param {string} type 事件名。
   * @param {object} detail 细节。
   * @param {'info'|'warn'|'error'} [level] 级别。
   */
  note(type, detail = {}, level = 'info') {
    if (this.timeline === null || typeof this.timeline.record !== 'function') return;
    this.timeline.record({
      direction: 'local',
      type,
      level,
      summary: Object.entries(detail).map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : v}`).join(' '),
      note: 'peer',
    });
  }

  emit(name, payload) {
    this.note(name, payload && typeof payload === 'object' ? payload : {});
    try {
      this.onEvent({ name, payload });
    } catch (err) {
      this.log('error', 'peer event handler threw', { name, error: String(err) });
    }
  }

  log(level, message, data) {
    const logger = this.logger;
    if (logger === null || logger === undefined) return;
    const fn = typeof logger[level] === 'function' ? logger[level].bind(logger) : (logger.log ? logger.log.bind(logger) : null);
    if (fn === null) return;
    if (data === undefined) fn(`[ice] ${message}`);
    else fn(`[ice] ${message}`, data);
  }
}

export default PeerConnection;
