// web/app.js — 页面装配层：信令 + 媒体 + 六区观测面板 + 导出 + 控制台日志
//
// 依据 reports/70-browser-call-demo-requirements.md：
//   §3 B-1…B-12（行为）、§4 I-1…I-6（ICE/TURN）、§5 M-1…M-6（媒体）、§6 O-1…O-8（观测面）、
//   §7 F-1…F-7（操作与流程矩阵）。
//
// 硬约束（captain 补强）：
//   * 纯静态、无构建、无 npm/CDN；
//   * **不依赖任何自定义响应头**（不用 COOP/COEP、不用 crossOriginIsolated/SharedArrayBuffer）；
//   * 可在宿主机「零 node」环境下用 `python3 -m http.server 8081 --directory web` 打开；
//   * 观测面只有浏览器控制台 + 页面面板，不引入 App/服务端日志通道。
//
// 控制台前缀（O-8）：[sig] 信令、[ice] 媒体/连接、[stats] 统计、[err] 错误、[ui] 页面动作。

import { SignalingClient, DEFAULT_SIGNALING_URL, signalingUrl, normalizeRoomId, isValidRoomId, MAX_REJOIN_ATTEMPTS, PONG_FAIL_AFTER_MS, MESSAGE_TYPES } from './lib/signaling.js';
import { Timeline } from './lib/timeline.js';
import { PeerConnection, MAX_ICE_RESTARTS, createSyntheticStream, RELAY_PORT_RANGE } from './lib/peer.js';
import { MACHINES, MACHINE_LABELS, StatsSeries, parseSdp, mediaEvidence } from './lib/observe.js';
import { createSelftest } from './lib/selftest.js';

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

function log(prefix, level, message, data) {
  const fn = level === 'error' ? console.error : (level === 'warn' ? console.warn : console.log);
  if (data === undefined) fn(`[${prefix}] ${message}`);
  else fn(`[${prefix}] ${message}`, data);
}

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmtMs(value) {
  return Number.isFinite(value) ? String(Math.round(value)) : '—';
}

function fmtKbps(bps) {
  return Number.isFinite(bps) ? (bps / 1000).toFixed(1) : '—';
}

function fmtPair(pair) {
  if (pair === null || pair === undefined) return '（未选出候选对）';
  const side = (s) => {
    if (s === null || s === undefined) return '?';
    const addr = typeof s.address === 'string' && s.address !== '' ? s.address : '（mDNS/未披露）';
    return `${addr}:${s.port ?? '?'}/${s.candidateType ?? '?'}`;
  };
  return `${side(pair.local)} → ${side(pair.remote)}`;
}

function nowMs() {
  return typeof performance !== 'undefined' && typeof performance.now === 'function' ? performance.now() : Date.now();
}

function download(filename, text, mime = 'text/plain') {
  const blob = new Blob([text], { type: `${mime};charset=utf-8` });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
  log('ui', 'info', `已导出 ${filename}（${text.length} B）`);
}

function stamp() {
  return new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
}

// ---------------------------------------------------------------------------
// Endpoint：一个独立的「通话端」（信令 + 媒体 + 观测）
// ---------------------------------------------------------------------------

/** 一个独立参与方：自己的 WS、自己的 RTCPeerConnection、自己的时间线与面板数据。 */
export class Endpoint {
  /**
   * @param {object} options
   * @param {string} options.label 显示名（如 `main` / `selftest-a`）。
   * @param {string} [options.url] 信令地址。
   * @param {boolean} [options.synthetic] 是否强制使用合成媒体（无摄像头环境）。
   * @param {(event: {level: string, text: string}) => void} [options.onEvent] 事件回调。
   * @param {() => void} [options.onUpdate] 数据变化回调（面板刷新用）。
   * @param {boolean} [options.quiet] 是否关闭控制台输出（自测第二端用）。
   */
  constructor(options = {}) {
    this.label = options.label ?? 'main';
    this.quiet = options.quiet === true;
    this.synthetic = options.synthetic === true;
    this.onEvent = typeof options.onEvent === 'function' ? options.onEvent : () => {};
    this.onUpdate = typeof options.onUpdate === 'function' ? options.onUpdate : () => {};

    this.timeline = new Timeline();
    this.events = [];
    this.stats = new StatsSeries();
    this.eventsSeen = 0;

    this.roomId = null;
    this.peerId = null;
    this.host = false;
    this.offerSdp = null;
    this.answerSdp = null;
    this.offerParsed = null;
    this.answerParsed = null;
    this.localStream = null;
    this.remoteStream = null;
    this.mediaSource = 'none';
    this.localAttached = false;
    this.localAttachPromise = null;
    this.sawBothDirections = false;
    this.statsTimer = null;
    this.lastSnapshot = null;
    this.lastEvidence = null;

    const logger = this.quiet ? null : console;
    this.client = new SignalingClient({ url: signalingUrl(options.url ?? DEFAULT_SIGNALING_URL), timeline: this.timeline, logger });
    this.peer = new PeerConnection({ logger, timeline: this.timeline, onEvent: (e) => this.onPeerEvent(e) });
    this.wireClient();
    this.wireMessages();
  }

  get url() {
    return this.client.url;
  }

  // -------------------------------------------------------------- 事件记录

  /**
   * 记录一条事件（面板 ⑥ + 控制台）。
   *
   * @param {'info'|'warn'|'error'} level 级别。
   * @param {string} text 文本。
   * @param {object} [data] 结构化数据。
   */
  note(level, text, data) {
    this.events.push({ atMs: Math.round(nowMs()), iso: new Date().toISOString(), level, text });
    if (this.events.length > 500) this.events.shift();
    if (!this.quiet) log(level === 'error' ? 'err' : 'ui', level, `${this.label}: ${text}`, data);
    this.onEvent({ level, text });
    this.onUpdate();
  }

  /**
   * PeerConnection 事件 → 信令发送 / 面板记录。
   *
   * 关键接线：本端候选（`ice-candidate`, side=local）必须经信令 `ice` 消息发给对端，
   * 否则两端各自收集候选却永不交换（Chrome 实测：local=4 / remote=0，ICE 永远停在 new）。
   *
   * @param {{name: string, payload: object}} event 事件。
   */
  onPeerEvent(event) {
    const name = event.name;
    const payload = event.payload ?? {};
    if (name === 'ice-candidate' && payload.side === 'local' && payload.init !== undefined) {
      const sent = this.client.sendIce(payload.init.candidate, payload.init.sdpMid, payload.init.sdpMLineIndex);
      if (!sent) this.note('warn', `ICE 候选未能发出（未入房或 socket 未打开）：${payload.entry ? payload.entry.address : ''}`);
    } else if (name === 'remote-track') {
      this.attachRemoteTrack(payload);
    } else if (name === 'ice-loopback-dropped') {
      this.note('warn', `loopback 候选已丢弃（${payload.side} ${payload.entry ? payload.entry.address : ''}）`);
    } else if (name === 'ice-gathering-complete') {
      this.note('info', `ICE 收集完成（本端候选 ${this.peer.counters.localCandidates} 条，loopback 丢弃 ${this.peer.counters.localLoopbackDropped} 条）`);
    } else if (name === 'ice-add-failed' || name === 'ice-candidate-error' || name === 'ice-restart-refused') {
      this.note('error', `peer.${name}：${JSON.stringify(payload).slice(0, 200)}`);
    } else if (name === 'negotiation-needed') {
      this.note('info', `negotiation-needed（role=${payload.role ?? '—'}；offer 责任只在房主，不自动发 offer）`);
    }
    this.onUpdate();
  }

  /** 远端轨道 → MediaStream（供页面渲染）。 */
  attachRemoteTrack(payload) {
    if (payload.track === null || payload.track === undefined) return;
    if (Array.isArray(payload.streams) && payload.streams.length > 0) this.remoteStream = payload.streams[0];
    else {
      if (this.remoteStream === null) this.remoteStream = new MediaStream();
      this.remoteStream.addTrack(payload.track);
    }
    this.note('info', `收到远端轨道 kind=${payload.kind ?? '—'}`);
    if (typeof this.onRemoteStream === 'function') this.onRemoteStream(this.remoteStream);
  }

  wireClient() {
    this.client.onEvent('open', () => this.note('info', `WS 已连接 ${this.url}`));
    this.client.onEvent('close', (p) => this.note(p.code === 1000 ? 'info' : 'warn', `WS 关闭 code=${p.code} reason=${p.reason || '-'}`));
    this.client.onEvent('closed', (p) => this.note('info', `连接终态（code=${p.code}${p.reconnect === false ? '，不重连' : ''}）`));
    this.client.onEvent('pongMiss', (p) => this.note('warn', `pong 丢失 ${p.count}/${p.tolerance}（阈值 ${p.failAfterMs} ms）`));
    this.client.onEvent('linkFailure', (p) => this.note('error', `链路失效：${p.reason}（misses=${p.count}）`));
    this.client.onEvent('reconnectScheduled', (p) => this.note('warn', `重连排程 attempt=${p.attempt}/${p.maxAttempts} delay=${p.delayMs} ms reason=${p.reason}`));
    this.client.onEvent('reconnectGiveUp', (p) => this.note('error', `重连耗尽 attempts=${p.attempts} budget=${p.budgetMs} ms（服务端仍保留席位 ${90000} ms 宽限期）`));
    this.client.onEvent('reconnectSkipped', (p) => this.note('warn', `重连被跳过：${p.why}`));
    this.client.onEvent('rejoinScheduled', (p) => this.note('warn', `ROOM_FULL 有界重试 attempt=${p.attempt} delay=${p.delayMs} ms（保留房间意图）`));
    this.client.onEvent('rejoinGiveUp', (p) => this.note('error', `ROOM_FULL 重试耗尽 attempts=${p.attempts}`));
    this.client.onEvent('dropSimulated', (p) => this.note('warn', `模拟掉线：仅关闭 WS（不发 leave），room=${p.roomId ?? '-'}`));
    this.client.onEvent('msgBlocked', (p) => this.note('warn', `未入房，已拦截 ${p.type}`));
    this.client.onEvent('dropped', (p) => this.note('warn', `发送被丢弃 ${p.type}：${p.reason}`));
    this.client.onEvent('bufferOverflow', (p) => this.note('warn', `监听缓冲溢出，丢弃最旧 ${p.dropped}（上限 ${p.limit}）`));
    this.client.onEvent('protocolError', (p) => this.note('error', `协议错误：${p.phase}${p.code ? ` code=${p.code}` : ''}${p.reason ? ` reason=${p.reason}` : ''}`));
    this.client.onEvent('terminal', (p) => this.note('error', `终态：${p.reason}`));
    this.client.onEvent('stateChanged', (p) => {
      this.peer.states.set('signaling', p.to);
      this.onUpdate();
    });
  }

  wireMessages() {
    // B-4：一律按 type 分派（14 类全覆盖）。
    this.client.on('created', (msg) => {
      this.host = true;
      this.roomId = msg.roomId;
      this.peer.setRole('offerer');
      this.applyHandout(msg);
      this.note('info', `建房成功 room=${msg.roomId}（浏览器为房主/offerer，等待 App 或第二个标签页 Join）`);
    });
    this.client.on('joined', (msg) => {
      this.host = false;
      this.roomId = msg.roomId;
      this.peerId = msg.peerId;
      this.peer.setRole('answerer');
      this.applyHandout(msg);
      this.note('info', `入房成功 room=${msg.roomId} peerId=${msg.peerId}（本端为 answerer，等待房主 offer）`);
    });
    this.client.on('peerJoined', (msg) => {
      this.note('info', `对端加入 peer=${msg.peerId}`);
      if (this.host) this.sendOffer('peerJoined');
      else this.note('warn', '非房主不发起 offer（B-10：offer 责任只在房主）');
    });
    this.client.on('peerLeft', (msg) => this.note('warn', `对端离开 peer=${msg.peerId}（面板回到「等待对端」，连接本身仍健康）`));
    this.client.on('offer', (msg) => { this.handleOffer(msg).catch((err) => this.note('error', `处理 offer 失败：${err.message}`)); });
    this.client.on('answer', (msg) => { this.handleAnswer(msg).catch((err) => this.note('error', `处理 answer 失败：${err.message}`)); });
    this.client.on('ice', (msg) => { this.peer.addRemoteCandidate(msg).catch((err) => this.note('error', `添加远端候选失败：${err.message}`)); });
    this.client.on('natType', (msg) => this.note('info', `对端 NAT 类型 = ${msg.natType}`));
    this.client.on('error', (msg) => this.note('error', `服务端 error：code=${msg.code} message=${msg.message}`));
    // 其余 4 类（create/join/leave/ping）都是 C→S 方向，服务端不会下发；pong 在客户端内部消费。
  }

  /**
   * 用服务端下发字段建立 ICE 配置（I-1：唯一来源）。
   *
   * @param {object} msg created/joined 消息。
   */
  applyHandout(msg) {
    const summary = this.peer.applyHandout(msg);
    this.note('info', `ICE 配置来自服务端下发：stun=${summary.stunUrl} turn=${summary.turnUrl} tcp回退=${summary.turnTcpFallbackActive ? '已加' : '未加'}（共 ${summary.serverCount} 条）`);
    if (this.peer.pc === null) {
      this.peer.create();
      this.peer.setIceTransportPolicy(this.iceTransportPolicy ?? 'all');
    } else {
      // 重连后服务端会再次下发 joined：**复用**既有 RTCPeerConnection，媒体不中断（B-8/F-2）。
      this.note('info', '重连复用既有 RTCPeerConnection（不重建媒体栈）');
    }
    if (this.host) {
      this.attachLocalStreamAsOfferer().catch((err) => this.note('error', `本地媒体挂载失败：${err.message}`));
    }
    this.onUpdate();
  }

  // -------------------------------------------------------------- 媒体

  /** 确保本地媒体流可用（优先真实设备，失败则合成媒体）。 */
  async ensureLocalMedia() {
    if (this.localStream !== null) return this.localStream;
    if (this.synthetic) {
      this.localStream = createSyntheticStream({ label: `${this.label}-合成` });
      this.mediaSource = 'synthetic';
      this.note('warn', '本端使用合成媒体（canvas 视频 + 振荡器音频）');
      return this.localStream;
    }
    try {
      if (typeof navigator === 'undefined' || !navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== 'function') {
        throw new Error('navigator.mediaDevices 不可用（需 http://localhost 安全上下文）');
      }
      this.localStream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 } },
        audio: true,
      });
      this.mediaSource = 'device';
      const tracks = this.localStream.getTracks().map((t) => `${t.kind}:${t.label}`);
      this.note('info', `getUserMedia 成功：${tracks.join(', ')}`);
    } catch (err) {
      this.note('warn', `getUserMedia 失败（${err.name}: ${err.message}）→ 改用合成媒体（无摄像头也能跑通媒体面）`);
      this.localStream = createSyntheticStream({ label: `${this.label}-合成` });
      this.mediaSource = 'synthetic';
    }
    return this.localStream;
  }

  /** 房主路径：本地流先建 m-line，再发 offer（M-3/M-4）。并发调用共享同一个 Promise（幂等）。 */
  async attachLocalStreamAsOfferer() {
    if (this.localAttachPromise !== null && this.localAttachPromise !== undefined) return this.localAttachPromise;
    this.localAttachPromise = (async () => {
      const stream = await this.ensureLocalMedia();
      this.peer.setLocalStream(stream, { preferVp9: true });
      this.localAttached = true;
      this.onUpdate();
      return stream;
    })();
    return this.localAttachPromise;
  }

  /** 设置或切换本地流的静音状态。 */
  setMuted(muted) {
    if (this.localStream === null) return false;
    for (const track of this.localStream.getTracks()) {
      if (track.kind === 'audio') track.enabled = !muted;
    }
    this.note(muted ? 'warn' : 'info', muted ? '本地音频已静音（track.enabled=false）' : '本地音频已恢复');
    return true;
  }

  // -------------------------------------------------------------- 信令动作

  /** 建房（浏览器为房主）。 */
  createRoom() {
    this.host = true;
    this.note('info', '发送 create（浏览器建房）');
    return this.client.createRoom();
  }

  /** 入房（默认路径：App 建房 → 浏览器 Join）。 */
  joinRoom(roomId) {
    const normalized = normalizeRoomId(roomId);
    if (!isValidRoomId(normalized)) {
      this.note('error', `房间号非法：'${roomId}' → '${normalized}'（需 6 位，字符集排除 I/L/O/0/1）`);
      return false;
    }
    this.note('info', `发送 join room=${normalized}`);
    const ok = this.client.joinRoom(normalized);
    if (ok) this.roomId = normalized;
    return ok;
  }

  /** 主动离开（B-9）。 */
  leave() {
    this.stopStats();
    const sent = this.client.leave();
    this.note('info', sent ? '已发送 leave，随后正常关闭（不重连）' : 'leave：本地已复位（无活动连接）');
    this.peer.close();
    this.onUpdate();
    return sent;
  }

  /**
   * 房主发 offer（B-10：只有房主可发；ICE restart = true 时用于 B-11）。
   *
   * @param {string} reason 触发原因（日志用）。
   * @param {{iceRestart?: boolean}} [options] 选项。
   */
  async sendOffer(reason, options = {}) {
    if (!this.host) {
      this.note('warn', `非房主不发起 offer（${reason}）`);
      return null;
    }
    await this.ensureLocalMedia();
    if (this.host) await this.attachLocalStreamAsOfferer();
    const description = await this.peer.createOffer({ iceRestart: options.iceRestart === true });
    await this.peer.setLocalDescription(description);
    this.offerSdp = this.peer.pc && this.peer.pc.localDescription ? this.peer.pc.localDescription.sdp : description.sdp;
    this.offerParsed = parseSdp(this.offerSdp);
    this.note('info', `已发 offer（${reason}，${this.offerSdp.length} B）`);
    this.client.sendOffer(this.offerSdp);
    this.startStats();
    this.onUpdate();
    return this.offerSdp;
  }

  /** answerer：应用远端 offer → 挂本地轨 → 回 answer。 */
  async handleOffer(msg) {
    this.offerSdp = msg.sdp;
    this.offerParsed = parseSdp(msg.sdp);
    this.note('info', `收到 offer（${msg.sdp.length} B，${this.offerParsed.mediaCount} 个 m= 段）`);
    await this.peer.applyRemoteDescription({ type: 'offer', sdp: msg.sdp });
    const stream = await this.ensureLocalMedia();
    await this.peer.attachToRemoteTransceivers(stream);
    const answer = await this.peer.createAnswer();
    await this.peer.setLocalDescription(answer);
    this.answerSdp = this.peer.pc && this.peer.pc.localDescription ? this.peer.pc.localDescription.sdp : answer.sdp;
    this.answerParsed = parseSdp(this.answerSdp);
    this.client.sendAnswer(this.answerSdp);
    this.note('info', `已回 answer（${this.answerSdp.length} B）`);
    this.startStats();
    this.onUpdate();
  }

  /** offerer：应用远端 answer。 */
  async handleAnswer(msg) {
    this.answerSdp = msg.sdp;
    this.answerParsed = parseSdp(msg.sdp);
    this.note('info', `收到 answer（${msg.sdp.length} B）`);
    await this.peer.applyRemoteDescription({ type: 'answer', sdp: msg.sdp });
    this.onUpdate();
  }

  /** ICE restart（B-11：只允许房主，≤2 次）。 */
  async iceRestart() {
    const result = await this.peer.startIceRestart();
    if (!result.ok) {
      this.note('error', `ICE restart 被拒绝：${result.reason === 'not_offerer' ? '本端不是房主（B-11）' : `已用满 ${MAX_ICE_RESTARTS} 次`}`);
      return result;
    }
    this.offerSdp = result.description.sdp;
    this.offerParsed = parseSdp(this.offerSdp);
    this.client.sendOffer(this.offerSdp);
    this.note('warn', `ICE restart #${this.peer.counters.iceRestarts}/${MAX_ICE_RESTARTS} 已发出（剩余 ${result.restartsLeft} 次）`);
    return result;
  }

  /** 重协商（F-7：只允许房主）。 */
  async renegotiate() {
    const result = await this.peer.renegotiate();
    if (!result.ok) {
      this.note('error', 'Renegotiate 被拒绝：本端不是房主（F-7）');
      return result;
    }
    this.offerSdp = result.description.sdp;
    this.offerParsed = parseSdp(this.offerSdp);
    this.client.sendOffer(this.offerSdp);
    this.note('info', `Renegotiate #${this.peer.counters.renegotiations} 已发出`);
    return result;
  }

  /** 模拟掉线（B-8：只关 WS，不发 leave；观察 90 s 保座与重连成功）。 */
  simulateDrop() {
    const ok = this.client.simulateDrop();
    this.note('warn', ok
      ? '模拟掉线：已关闭 WS（未发 leave）→ 服务端保留席位 90 s；重连后应拿回原 peerId 且对端未收到 peerLeft'
      : '模拟掉线：当前没有活动连接');
    return ok;
  }

  /** 切换 iceTransportPolicy（I-3）。 */
  setRelayOnly(relayOnly) {
    this.iceTransportPolicy = relayOnly ? 'relay' : 'all';
    const applied = this.peer.setIceTransportPolicy(this.iceTransportPolicy);
    this.note('warn', `ICE 传输策略 = ${this.iceTransportPolicy}${applied ? '' : '（当前无连接，将在下次建连生效）'}`);
    return applied;
  }

  // -------------------------------------------------------------- 统计

  startStats() {
    if (this.statsTimer !== null) return;
    this.statsTimer = setInterval(() => { this.sample().catch(() => {}); }, 1000);
    this.sample().catch(() => {});
  }

  stopStats() {
    if (this.statsTimer !== null) clearInterval(this.statsTimer);
    this.statsTimer = null;
  }

  async sample() {
    if (this.peer.pc === null) return null;
    const snapshot = await this.peer.sampleStats();
    if (snapshot === null) return null;
    this.stats.add(snapshot);
    if (this.lastSnapshot !== null) {
      const ev = mediaEvidence(this.lastSnapshot, snapshot);
      if (ev.both) this.sawBothDirections = true;
      this.lastEvidence = ev;
    }
    this.lastSnapshot = snapshot;
    if (!this.quiet) {
      log('stats', 'info', `${this.label} in=${fmtKbps(snapshot.totals.inboundBitrateBps)}kbps out=${fmtKbps(snapshot.totals.outboundBitrateBps)}kbps rtt=${fmtMs(snapshot.totals.rttMs)}ms vp9=${snapshot.vp9 ? 'yes' : 'no'} dir=${snapshot.mediaDirection}`);
    }
    this.onUpdate();
    return snapshot;
  }

  /** 是否已观察到双向字节增长（M-6）。 */
  mediaEvidenceOk() {
    return this.sawBothDirections === true;
  }

  // -------------------------------------------------------------- 汇总 / 导出

  /** 面板与导出的统一数据源。 */
  summary() {
    const latest = this.stats.latest;
    return {
      label: this.label,
      url: this.url,
      roomId: this.roomId,
      peerId: this.peerId,
      role: this.host ? 'offerer(host)' : 'answerer',
      signalingState: this.client.state,
      statsSamples: this.stats.count,
      latest,
      candidates: this.peer.candidates.rows,
      transitions: this.peer.states.toJSON(),
      events: this.events,
      mediaSource: this.mediaSource,
    };
  }

  /** 完整导出对象（O-7）。 */
  exportJSON() {
    return {
      generatedAt: new Date().toISOString(),
      pageUrl: typeof location !== 'undefined' ? location.href : '',
      signalingUrl: this.url,
      label: this.label,
      roomId: this.roomId,
      peerId: this.peerId,
      role: this.host ? 'offerer(host)' : 'answerer',
      iceTransportPolicy: this.peer.iceTransportPolicy,
      relayPortRange: RELAY_PORT_RANGE,
      client: this.client.snapshot(),
      peer: this.peer.snapshot(),
      iceSummary: this.peer.snapshot().iceSummary,
      sdp: { offer: this.offerSdp, answer: this.answerSdp, offerParsed: this.offerParsed, answerParsed: this.answerParsed },
      candidates: this.peer.candidates.toJSON(),
      states: this.peer.states.toJSON(),
      stats: this.stats.toJSON({ url: typeof location !== 'undefined' ? location.href : '' }),
      timeline: this.timeline.toJSON({ url: typeof location !== 'undefined' ? location.href : '', roomId: this.roomId }),
      events: this.events.slice(),
      media: {
        source: this.mediaSource,
        localTracks: this.localStream === null ? [] : this.localStream.getTracks().map((t) => ({ kind: t.kind, enabled: t.enabled, label: t.label })),
        remoteTracks: this.remoteStream === null ? 0 : this.remoteStream.getTracks().length,
        evidence: this.lastEvidence,
        bothDirectionsSeen: this.sawBothDirections === true,
      },
    };
  }
}

// ---------------------------------------------------------------------------
// 主页面装配
// ---------------------------------------------------------------------------

const params = new URLSearchParams(typeof location !== 'undefined' ? location.search : '');
const initialUrl = params.get('signaling') || DEFAULT_SIGNALING_URL;
const main = new Endpoint({
  label: 'main',
  url: initialUrl,
  synthetic: params.get('media') === 'synthetic',
  onUpdate: () => scheduleRender(),
});

let selftestLast = null;
const selftest = createSelftest({
  createEndpoint: (opts) => new Endpoint({ ...opts, quiet: true, onUpdate: () => {} }),
  getUrl: () => $('signaling-url').value.trim() || DEFAULT_SIGNALING_URL,
  note: (level, text) => main.note(level, text),
});
window.selftest = selftest;
window.__webDemo = { Endpoint, main, selftest };

// ---- 环境自检（M-1/M-2） ----

function renderEnvironment() {
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';
  const isChrome = /Chrome\//.test(ua) && !/Edg\/|OPR\/|SamsungBrowser|Firefox\//.test(ua);
  const secure = typeof window !== 'undefined' && window.isSecureContext === true;
  const hasPc = typeof RTCPeerConnection !== 'undefined';
  const hasMedia = typeof navigator !== 'undefined' && !!navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function';
  const lines = [];
  lines.push(`来源: ${location.origin} · 安全上下文: ${secure ? '是' : '否'} · RTCPeerConnection: ${hasPc ? '有' : '无'} · getUserMedia: ${hasMedia ? '有' : '无'}`);
  lines.push(`浏览器: ${isChrome ? 'Chrome（受支持）' : '非 Chrome（仅承诺 Chrome 桌面版，其余浏览器不保证可用）'}`);
  if (!secure) lines.push('⚠ 非安全上下文：请经 http://localhost:<port> 打开（VSCode PORTS 转发 8081），不要用 file:// 或局域网 IP 的明文来源。');
  $('env-notice').className = `notice${secure && hasPc ? '' : ' bad'}`;
  $('env-notice').innerHTML = `${lines.map(esc).join('\n')}`;
  $('foot-hint').textContent = [
    '宿主机启动静态服务（工作区根 = /opt/dsh-workspaces/code/webrtc-demo）：',
    '  python3 -m http.server 8081 --directory web     # 零依赖，无需 node',
    '  bash scripts/serve-web-demo.sh start            # 若宿主机已装 node >= 22',
    '然后在 VSCode 的 PORTS 面板转发 8081，用 Chrome 打开 http://localhost:8081/',
    '信令仍走 ws://47.238.144.66:8443/ws（明文，不需要转发 8443）；?signaling= 可覆盖。',
  ].join('\n');
}

// ---- 面板渲染（O-1…O-8） ----

let renderQueued = false;
function scheduleRender() {
  if (renderQueued) return;
  renderQueued = true;
  setTimeout(() => { renderQueued = false; render(); }, 250);
}

function render() {
  const rows = main.timeline.entries;
  $('timeline-count').textContent = `${rows.length} 帧`;
  const recent = rows.slice(-200).reverse();
  $('timeline-body').innerHTML = recent.map((e) => `<tr class="level-${esc(e.level)}">
    <td class="num">${e.seq}</td><td class="num">${e.atMs}</td><td class="dir-${esc(e.direction)}">${esc(e.direction)}</td>
    <td class="type">${esc(e.type)}</td><td>${esc(e.summary)}</td><td>${esc(e.raw.length > 220 ? `${e.raw.slice(0, 220)}…` : e.raw)}</td></tr>`).join('');

  const offer = main.offerSdp;
  const answer = main.answerSdp;
  $('sdp-count').textContent = `offer ${offer ? offer.length : 0} B · answer ${answer ? answer.length : 0} B`;
  $('sdp-offer').textContent = offer ?? '（尚未产生）';
  $('sdp-answer').textContent = answer ?? '（尚未产生）';
  const parsed = main.answerParsed ?? main.offerParsed;
  if (parsed === null) {
    $('sdp-summary').innerHTML = '<dt>状态</dt><dd>尚未协商</dd>';
    $('sdp-body').innerHTML = '';
  } else {
    const fp = parsed.fingerprint;
    $('sdp-summary').innerHTML = [
      ['字节数', parsed.bytes], ['行数', parsed.lines],
      ['m= 段', `${parsed.mediaCount}（audio ${parsed.audioCount} / video ${parsed.videoCount}）`],
      ['BUNDLE', parsed.session.bundleGroups.map((g) => g.join('+')).join(' | ') || '—'],
      ['ICE ufrag', parsed.iceUfrag ?? '—'], ['ICE pwd', parsed.icePwd ?? '—'],
      ['DTLS 指纹', fp === null ? '—' : `${fp.algorithm} ${fp.value}`],
      ['setup', parsed.setup ?? '—'], ['候选行数', parsed.candidateLineCount],
    ].map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
    $('sdp-body').innerHTML = parsed.media.map((m) => `<tr>
      <td>${m.index}</td><td>${esc(m.kind)}</td><td>${esc(m.mid ?? '—')}</td><td>${esc(m.direction)}</td>
      <td>${esc(m.codecs.map((c) => `${c.payload}→${c.name ?? '静态'}${c.clockRate ? `/${c.clockRate}` : ''}`).join(', '))}</td>
      <td class="num">${m.candidateCount}</td></tr>`).join('');
  }

  const cands = main.peer.candidates.rows;
  $('ice-count').textContent = `${cands.length} 条（本端 ${main.peer.counters.localCandidates} / 远端 ${main.peer.counters.remoteCandidates}；loopback 丢弃 本端 ${main.peer.counters.localLoopbackDropped} / 远端 ${main.peer.counters.remoteLoopbackDropped}）`;
  const summary = main.peer.handout === null ? null : main.peer.snapshot().iceSummary;
  $('ice-summary').innerHTML = summary === null
    ? '<dt>ICE 配置</dt><dd>尚未收到 created/joined 下发</dd>'
    : [
      ['STUN', summary.stunUrl ?? '—'], ['TURN(UDP 优先)', summary.turnUrl ?? '—'],
      ['TURN(TCP 回退)', summary.turnTcpFallbackActive ? summary.turnTcpUrl : '未启用'],
      ['account', summary.turnUsername ?? '—'], ['credential', summary.credentialPresent ? '（已下发，不回显）' : '—'],
      ['policy', main.peer.iceTransportPolicy], ['relay 端口区间', `${RELAY_PORT_RANGE.min}-${RELAY_PORT_RANGE.max}`],
    ].map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
  const pair = main.stats.latest === null ? null : main.stats.latest.selectedPair;
  $('pair-summary').innerHTML = [
    ['选中候选对', fmtPair(pair)],
    ['RTT', pair === null || !Number.isFinite(pair.currentRoundTripTime) ? '—' : `${(pair.currentRoundTripTime * 1000).toFixed(1)} ms`],
    ['收发字节', pair === null ? '—' : `sent ${pair.bytesSent ?? '—'} / recv ${pair.bytesReceived ?? '—'}`],
    ['请求/响应', pair === null ? '—' : `${pair.requestsSent ?? '—'}/${pair.responsesReceived ?? '—'}`],
  ].map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
  $('ice-body').innerHTML = cands.map((c) => `<tr>
    <td>${esc(c.source)}</td>
    <td><span class="badge ${c.type === 'relay' ? 'relay' : ''}">${esc(c.type)}</span></td>
    <td>${esc(c.protocol)}${c.tcpType ? `/${esc(c.tcpType)}` : ''}</td>
    <td>${esc(c.address)}:${esc(c.port)}${c.loopback ? ' <span class="badge warn">loopback</span>' : ''}</td>
    <td class="num">${esc(c.priority)}</td><td>${esc(c.foundation)}</td><td class="num">${c.count}</td></tr>`).join('');

  const st = main.peer.states.toJSON();
  $('state-count').textContent = `${st.transitions.length} 次跃迁`;
  $('state-current').innerHTML = MACHINES.map((m) => `<dt>${esc(MACHINE_LABELS[m])}</dt><dd>${esc(st.current[m] ?? '—')}</dd>`).join('');
  $('state-body').innerHTML = st.transitions.slice(-80).reverse().map((t) => `<tr>
    <td class="num">${t.seq}</td><td class="num">${t.atMs}</td><td>${esc(MACHINE_LABELS[t.machine] ?? t.machine)}</td>
    <td>${esc(t.from ?? '—')}</td><td>${esc(t.to)}</td></tr>`).join('');

  const latest = main.stats.latest;
  $('stats-count').textContent = `${main.stats.count} 个样本`;
  if (latest === null) {
    $('stats-latest').innerHTML = '<dt>状态</dt><dd>尚未采样（连接建立后每秒一次）</dd>';
    $('stats-body').innerHTML = '';
  } else {
    const video = latest.outbound.find((r) => r.kind === 'video') ?? latest.inbound.find((r) => r.kind === 'video') ?? null;
    $('stats-latest').innerHTML = [
      ['码率', `in ${fmtKbps(latest.totals.inboundBitrateBps)} kbps / out ${fmtKbps(latest.totals.outboundBitrateBps)} kbps`],
      ['分辨率', video && video.frameWidth ? `${video.frameWidth}x${video.frameHeight}` : '—'],
      ['fps', video && Number.isFinite(video.framesPerSecond) ? video.framesPerSecond.toFixed(1) : '—'],
      ['jitter', latest.inbound.length ? (latest.inbound[0].jitter ?? '—') : '—'],
      ['丢包 / NACK / PLI / FIR', `${latest.totals.packetsLost} / ${latest.totals.nackCount} / ${latest.totals.pliCount} / ${latest.totals.firCount}`],
      ['RTT', `${fmtMs(latest.totals.rttMs)} ms`],
      ['传输状态', `ice=${latest.transport ? latest.transport.iceState : '—'} dtls=${latest.transport ? latest.transport.dtlsState : '—'}`],
      ['媒体方向', latest.mediaDirection],
      ['VP9 判定', latest.vp9 ? `是（${latest.videoMimeTypes.join(', ')}）` : `否（已见 ${latest.videoMimeTypes.join(', ') || '无 video codec'}）`],
      ['双向字节增长', main.sawBothDirections === true ? '已观察到' : '尚未观察到'],
    ].map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
    const samples = main.stats.samples.slice(-60);
    const body = samples.map((s) => {
      const v = s.outbound.find((r) => r.kind === 'video') ?? s.inbound.find((r) => r.kind === 'video') ?? null;
      return `<tr class="level-${s.vp9 ? 'info' : 'warn'}">
        <td class="num">${s.atMs}</td>
        <td class="num">${fmtKbps(s.totals.inboundBitrateBps)}</td>
        <td class="num">${fmtKbps(s.totals.outboundBitrateBps)}</td>
        <td>${v && v.frameWidth ? `${v.frameWidth}x${v.frameHeight}` : '—'}</td>
        <td class="num">${v && Number.isFinite(v.framesPerSecond) ? v.framesPerSecond.toFixed(1) : '—'}</td>
        <td class="num">${s.inbound.length && Number.isFinite(s.inbound[0].jitter) ? s.inbound[0].jitter.toFixed(4) : '—'}</td>
        <td class="num">${s.totals.packetsLost}</td><td class="num">${s.totals.nackCount}</td>
        <td class="num">${s.totals.pliCount}</td><td class="num">${s.totals.firCount}</td>
        <td class="num">${fmtMs(s.totals.rttMs)}</td>
        <td>${esc(fmtPair(s.selectedPair))}</td><td>${esc(s.mediaDirection)}</td>
        <td>${s.vp9 ? 'VP9' : '—'}</td></tr>`;
    });
    $('stats-body').innerHTML = body.reverse().join('');
  }

  $('event-count').textContent = `${main.events.length} 条`;
  $('events-list').innerHTML = main.events.slice(-120).reverse().map((e) => `<li class="${esc(e.level)}">${e.atMs}ms ${esc(e.level.toUpperCase())} ${esc(e.text)}</li>`).join('');

  $('btn-leave').disabled = main.client.state === 'idle' || main.client.state === 'closed';
  $('btn-offer').disabled = !main.host || main.roomId === null;
  $('btn-restart').disabled = !main.host || main.roomId === null;
  $('btn-renegotiate').disabled = !main.host || main.roomId === null;
  $('caption-local').textContent = `本地（local） · ${main.mediaSource === 'device' ? '摄像头/麦克风' : (main.mediaSource === 'synthetic' ? '合成媒体' : '未开始')}`;
  $('caption-remote').textContent = `远端（remote） · ${main.remoteStream === null ? '未连接' : `${main.remoteStream.getTracks().length} 条轨道`}`;
}

// ---- 媒体元素挂载 ----

function attachStreams() {
  const localVideo = $('video-local');
  const remoteVideo = $('video-remote');
  if (main.localStream !== null && localVideo.srcObject !== main.localStream) {
    localVideo.srcObject = main.localStream;
    localVideo.muted = true;
    localVideo.play().catch(() => {});
  }
  if (main.remoteStream !== null && remoteVideo.srcObject !== main.remoteStream) {
    remoteVideo.srcObject = main.remoteStream;
    remoteVideo.play().catch(() => {});
  }
}

// 远端轨道到达时立即挂到 <video>（否则依赖 1 s 轮询）。
main.onRemoteStream = () => { attachStreams(); render(); };

// ---- 按钮绑定 ----

$('signaling-url').value = signalingUrl(initialUrl);
if (params.get('room')) $('room-id').value = normalizeRoomId(params.get('room'));

$('btn-apply-url').addEventListener('click', () => {
  const next = signalingUrl($('signaling-url').value.trim());
  if (next === main.client.url) { main.note('info', `信令地址未变：${next}`); return; }
  main.client.url = next;
  $('signaling-url').value = next;
  main.note('warn', `信令地址已切换为 ${next}（下次连接生效）`);
});

$('btn-create').addEventListener('click', () => {
  main.client.url = signalingUrl($('signaling-url').value.trim());
  main.createRoom();
  main.client.connect();
});

$('btn-join').addEventListener('click', () => {
  main.client.url = signalingUrl($('signaling-url').value.trim());
  if (main.joinRoom($('room-id').value)) main.client.connect();
});

$('btn-leave').addEventListener('click', () => main.leave());
$('btn-offer').addEventListener('click', () => { main.sendOffer('手动再发 offer').catch((err) => main.note('error', err.message)); });
$('btn-restart').addEventListener('click', () => { main.iceRestart().catch((err) => main.note('error', err.message)); });
$('btn-renegotiate').addEventListener('click', () => { main.renegotiate().catch((err) => main.note('error', err.message)); });
$('chk-relay').addEventListener('change', (e) => main.setRelayOnly(e.target.checked));
$('chk-mute').addEventListener('change', (e) => {
  if (main.localStream === null) main.ensureLocalMedia().then(() => main.setMuted(e.target.checked)).catch(() => {});
  else main.setMuted(e.target.checked);
});
$('btn-drop').addEventListener('click', () => main.simulateDrop());

$('btn-export-json').addEventListener('click', () => {
  const payload = { ...main.exportJSON(), selftest: selftestLast };
  download(`web-demo-${stamp()}.json`, JSON.stringify(payload, null, 2), 'application/json');
});
$('btn-export-timeline').addEventListener('click', () => download(`web-demo-timeline-${stamp()}.csv`, main.timeline.toCSV(), 'text/csv'));
$('btn-export-ice').addEventListener('click', () => download(`web-demo-candidates-${stamp()}.csv`, main.peer.candidates.toCSV(), 'text/csv'));
$('btn-export-stats').addEventListener('click', () => download(`web-demo-stats-${stamp()}.csv`, main.stats.toCSV(), 'text/csv'));

$('btn-selftest').addEventListener('click', async () => {
  main.note('info', '开始运行自测矩阵（可在控制台看 console.table 与 PASS/FAIL）…');
  try {
    selftestLast = await selftest.run({ url: signalingUrl($('signaling-url').value.trim()) });
    main.note(selftestLast.fail === 0 ? 'info' : 'error', `自测矩阵完成：PASS ${selftestLast.pass} / FAIL ${selftestLast.fail}`);
  } catch (err) {
    main.note('error', `自测矩阵异常：${err.message}`);
  }
});

// 本地媒体尽早准备，便于操作时即时有画面
main.ensureLocalMedia().then(() => { attachStreams(); render(); }).catch(() => {});

// 远端轨道 → 媒体元素（每次渲染时同步一次，避免漏挂）
setInterval(attachStreams, 1000);

renderEnvironment();
render();

// ---- 手机模拟器标签页模式（?role=phone&room=XXXX） ----
// 用途：把第二个标签页当作「手机模拟器」（reports/70 §8 A-4）。自动化自测默认走单页双端，
// 不依赖弹窗；该模式供人工/半自动联调使用。
async function runPhoneMode() {
  const room = normalizeRoomId(params.get('room') ?? '');
  main.note('info', `手机模拟器模式启动（room=${room || '—'}，媒体源按 ?media=synthetic 决定）`);
  if (!isValidRoomId(room)) {
    main.note('error', '手机模拟器模式需要 ?room=<6 位房间号>');
    return;
  }
  main.client.url = signalingUrl(initialUrl);
  await main.ensureLocalMedia();
  main.joinRoom(room);
  main.client.connect();
  window.__phone = main;
  // 房主（自测/人工）通过 postMessage 指挥本标签页：目前只有「显式 leave 后关闭」。
  window.addEventListener('message', (event) => {
    const data = event && event.data;
    if (data === null || typeof data !== 'object' || data.source !== 'web-demo-host') return;
    if (data.event === 'leave') {
      main.note('info', '收到房主指令：执行 leave()（而非直接关窗，避免走 90 s 保座路径）');
      main.leave();
      setTimeout(() => { try { window.close(); } catch { /* 浏览器可能拒绝 */ } }, 300);
    }
  });
  if (typeof window.opener !== 'undefined' && window.opener !== null) {
    try { window.opener.postMessage({ source: 'web-demo-phone', event: 'ready', room }, '*'); } catch { /* 跨源忽略 */ }
  }
}

if (params.get('role') === 'phone') {
  runPhoneMode().catch((err) => main.note('error', `手机模拟器模式失败：${err.message}`));
}

log('ui', 'info', `页面已加载：${location.href} · 信令默认 ${DEFAULT_SIGNALING_URL} · 自测入口 window.selftest.run()`);
log('ui', 'info', `常量：心跳 ${15000} ms / pong 窗口 ${5000} ms / 判活阈值 ${PONG_FAIL_AFTER_MS} ms / 重连上限 ${MAX_REJOIN_ATTEMPTS} 次 / ICE restart 上限 ${MAX_ICE_RESTARTS} 次 / 消息类型 ${MESSAGE_TYPES.length} 类`);
