// web/lib/selftest.js — 流程矩阵自测（reports/70 §8 A-4）
//
// 断言 create → join → offer/answer → ice → connected（ICE+DTLS）→ 媒体 → leave，
// 输出 `console.table` + PASS/FAIL 表 + 可导出 JSON。
//
// 两种模式：
//   * `'tab'`（默认，自动化路径）：**同一个页面**里跑两个独立端点（各自的 SignalingClient +
//     RTCPeerConnection + 合成媒体），经真实信令服务完成整条流程。单页自足，不依赖弹窗、
//     不依赖任何自定义响应头，可在无头 Chrome 中由 `window.selftest.run()` 直接驱动。
//   * `'popup'`（人工/半自动路径）：本页当房主，另开一个标签页
//     `?role=phone&room=<房间号>` 作为「手机模拟器」，仍走真实信令；断言从房主侧可观测的事实。
//
// 依赖注入：`createEndpoint({label, url, synthetic, quiet})` 与 `getUrl()` 由页面（app.js）提供，
// 因此本模块不 import app.js（避免循环依赖），也可以在页面之外被复用。

import { mediaEvidence } from './observe.js';
import { MAX_ICE_RESTARTS } from './peer.js';

/** 单步等待超时（毫秒）。 */
export const STEP_TIMEOUT_MS = 20000;

/** 整条矩阵的兜底截止时间（毫秒）——保证 `run()` 一定 resolve，不把调用方挂死。 */
export const MATRIX_DEADLINE_MS = 90000;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function roomIdLooksValid(roomId) {
  return typeof roomId === 'string' && /^[A-HJ-KM-NP-Z2-9]{6}$/.test(roomId);
}

/**
 * 创建自测矩阵执行器。
 *
 * @param {object} options
 * @param {(opts: object) => object} options.createEndpoint 端点工厂（app.js 注入 `Endpoint`）。
 * @param {() => string} [options.getUrl] 当前信令地址。
 * @param {(level: string, text: string) => void} [options.note] 事件记录回调。
 * @returns {{run: (opts?: object) => Promise<object>, last: object|null, exportJSON: () => object}}
 */
export function createSelftest(options = {}) {
  const createEndpoint = options.createEndpoint;
  if (typeof createEndpoint !== 'function') {
    throw new TypeError('createSelftest 需要 createEndpoint({label,url,synthetic,quiet})');
  }
  const getUrl = typeof options.getUrl === 'function' ? options.getUrl : () => undefined;
  const note = typeof options.note === 'function' ? options.note : () => {};
  let last = null;

  /**
   * 执行矩阵。
   *
   * @param {{mode?: 'tab'|'popup', url?: string, timeoutMs?: number}} [runOptions]
   * @returns {Promise<{pass: number, fail: number, rows: object[], mode: string, url: string, roomId: string|null, generatedAt: string, durationMs: number, ok: boolean}>}
   */
  async function run(runOptions = {}) {
    const mode = runOptions.mode === 'popup' ? 'popup' : 'tab';
    const url = runOptions.url ?? getUrl();
    const started = Date.now();
    const deadline = started + (Number.isFinite(runOptions.timeoutMs) ? runOptions.timeoutMs : MATRIX_DEADLINE_MS);
    const rows = [];
    const endpoints = [];
    let phoneWindow = null;
    let roomId = null;

    const record = (id, expect, actual, pass, detail = '') => {
      rows.push({ id, expect, actual, pass: pass === true, detail });
      const line = `${pass === true ? 'PASS' : 'FAIL'}  ${id} :: 期望 ${expect} / 实际 ${actual}${detail ? ` (${detail})` : ''}`;
      if (pass === true) console.log(line); else console.error(line);
      return pass === true;
    };

    /** 轮询等待条件成立。 */
    const waitFor = async (label, predicate, timeoutMs = STEP_TIMEOUT_MS) => {
      const end = Math.min(Date.now() + timeoutMs, deadline);
      for (;;) {
        let value = null;
        try { value = predicate(); } catch { value = null; }
        if (value) return value;
        if (Date.now() >= end) throw new Error(`等待超时：${label}`);
        await sleep(120);
      }
    };

    const hasFrame = (ep, type, direction) => ep.timeline.entries
      .some((e) => e.type === type && (direction === undefined || e.direction === direction));

    const newEndpoint = (label, synthetic) => {
      const ep = createEndpoint({ label, url, synthetic, quiet: true });
      endpoints.push(ep);
      return ep;
    };

    const host = newEndpoint('selftest-host', true);
    let joiner = null;

    try {
      note('info', `[selftest] 开始（mode=${mode} url=${url}）`);

      // ---- 1. create → created -------------------------------------------------
      host.createRoom();
      host.client.connect();
      try {
        await waitFor('created', () => (host.host === true && roomIdLooksValid(host.roomId) ? host.roomId : null));
        roomId = host.roomId;
        record('create->created', '收到 created 且 roomId 为 6 位 [A-Z2-9]', `roomId=${roomId}`, true);
      } catch (err) {
        record('create->created', '收到 created 且 roomId 为 6 位 [A-Z2-9]', err.message, false);
        throw err;
      }

      // ---- 2. ICE 配置来自服务端下发 --------------------------------------------
      const iceInfo = host.peer.snapshot().iceSummary;
      record(
        'ice-config-from-handout',
        'STUN 与 TURN 均来自 created 下发，且 TURN 有 UDP 与 TCP 两条',
        `stun=${iceInfo.stunUrl} turn=${iceInfo.turnUrl} tcp=${iceInfo.turnTcpUrl} entries=${iceInfo.serverCount}`,
        typeof iceInfo.stunUrl === 'string' && iceInfo.stunUrl !== ''
          && typeof iceInfo.turnUrl === 'string' && iceInfo.turnUrl !== ''
          && iceInfo.credentialPresent === true
          && iceInfo.serverCount >= 2,
      );
      record(
        'turn-udp-first-then-tcp',
        'ICE 条目顺序 = [STUN, TURN/udp, TURN/tcp]',
        iceInfo.entries.map((e) => e.urls).join(' | '),
        iceInfo.serverCount >= 3 && /transport=udp/i.test(iceInfo.entries[1] ? iceInfo.entries[1].urls : '')
          && /transport=tcp/i.test(iceInfo.entries[2] ? iceInfo.entries[2].urls : ''),
      );

      // ---- 3. join → joined（对端） ---------------------------------------------
      if (mode === 'popup') {
        const phoneUrl = new URL(location.href);
        phoneUrl.searchParams.set('role', 'phone');
        phoneUrl.searchParams.set('room', roomId);
        phoneUrl.searchParams.set('signaling', url);
        phoneWindow = window.open(phoneUrl.toString(), 'webDemoPhone');
        record('phone-tab-opened', '第二个标签页（手机模拟器）已打开', phoneWindow === null ? 'window.open 返回 null' : phoneUrl.toString(), phoneWindow !== null);
        if (phoneWindow === null) throw new Error('window.open 被拦截：请允许弹窗，或改用默认 tab 模式');
        await waitFor('phoneJoined', () => hasFrame(host, 'peerJoined', 'recv'));
        record('join->joined（手机模拟器）', '房主收到 peerJoined', 'peerJoined 已到达', true, '来自第二个标签页');
      } else {
        joiner = newEndpoint('selftest-joiner', true);
        joiner.joinRoom(roomId);
        joiner.client.connect();
        try {
          await waitFor('joined', () => (joiner.peerId === null ? null : joiner.peerId));
          record('join->joined', '收到 joined 且带 peerId', `peerId=${joiner.peerId}`, true);
        } catch (err) {
          record('join->joined', '收到 joined 且带 peerId', err.message, false);
          throw err;
        }
        try {
          await waitFor('peerJoined@host', () => hasFrame(host, 'peerJoined', 'recv'));
          record('peerJoined@host', '房主收到 peerJoined', '已到达', true);
        } catch (err) {
          record('peerJoined@host', '房主收到 peerJoined', err.message, false);
        }
      }

      // ---- 4. offer / answer ----------------------------------------------------
      try {
        await waitFor('offer', () => (host.offerSdp !== null ? host.offerParsed : null));
        const offer = host.offerParsed;
        const videoSection = offer.media.find((m) => m.kind === 'video') ?? null;
        const realCodecs = videoSection === null ? [] : videoSection.codecs.filter((c) => !/^(rtx|red|ulpfec)$/i.test(c.name ?? ''));
        const hasVp9 = realCodecs.some((c) => (c.name ?? '').toUpperCase() === 'VP9');
        record('offer-created', '房主已产生 offer', `${offer.bytes} B / ${offer.mediaCount} 个 m= 段`, true);
        record(
          'offer-bundle',
          'offer 含 a=group:BUNDLE（M-3 统一 plan/BUNDLE）',
          JSON.stringify(offer.session.bundleGroups),
          offer.session.bundleGroups.length > 0,
        );
        record(
          'offer-vp9-present',
          'offer 的视频段包含 VP9（M-5；偏好由 setCodecPreferences 置顶）',
          realCodecs.map((c) => c.name).join(',') || '无',
          hasVp9,
        );
        if (realCodecs.length > 0) {
          // 信息行（**不计入 PASS/FAIL**）：Chrome 的 setCodecPreferences 只能重排，不能裁掉其它编码。
          rows.push({
            id: 'offer-vp9-first(info)',
            expect: 'VP9 是视频编码中的第一项（偏好排序）',
            actual: realCodecs.map((c) => c.name).join(' > '),
            pass: (realCodecs[0].name ?? '').toUpperCase() === 'VP9',
            info: true,
            detail: 'Chrome 只重排不裁剪；App 侧仅提供 VP9 编码器，最终协商由 stats 证明',
          });
        }
      } catch (err) {
        record('offer-created', '房主已产生 offer', err.message, false);
      }

      if (mode !== 'popup') {
        try {
          await waitFor('answer', () => (joiner.answerSdp !== null && host.answerSdp !== null ? host.answerParsed : null));
          record('answer-created', '对端回 answer 且房主已应用', `answer=${host.answerParsed.bytes} B`, true);
        } catch (err) {
          record('answer-created', '对端回 answer 且房主已应用', err.message, false);
        }
      }

      // ---- 5. ICE 交换 ----------------------------------------------------------
      const peers = mode === 'popup' ? [host] : [host, joiner];
      try {
        await waitFor('ice-exchange', () => peers.every((ep) => ep.peer.counters.localCandidates > 0 && ep.peer.counters.remoteCandidates > 0));
        const detail = peers.map((ep) => `${ep.label}: local=${ep.peer.counters.localCandidates} remote=${ep.peer.counters.remoteCandidates}`).join(' · ');
        record('ice-exchange', '两端各自发出并收到 ICE 候选', detail, true);
      } catch (err) {
        const detail = peers.map((ep) => `${ep.label}: local=${ep.peer.counters.localCandidates} remote=${ep.peer.counters.remoteCandidates}`).join(' · ');
        record('ice-exchange', '两端各自发出并收到 ICE 候选', detail, false, err.message);
      }

      // ---- 6. connected（ICE + DTLS） -------------------------------------------
      try {
        await waitFor('ice-connected', () => peers.every((ep) => {
          const ice = ep.peer.states.get('iceConnection');
          const conn = ep.peer.states.get('connection');
          return (ice === 'connected' || ice === 'completed') && (conn === 'connected' || conn === 'completed');
        }), 30000);
        record(
          'ice-connected',
          'iceConnectionState 与 connectionState 均 connected（或 completed）',
          peers.map((ep) => `${ep.label}: ice=${ep.peer.states.get('iceConnection')} conn=${ep.peer.states.get('connection')}`).join(' · '),
          true,
        );
      } catch (err) {
        record(
          'ice-connected',
          'iceConnectionState 与 connectionState 均 connected（或 completed）',
          peers.map((ep) => `${ep.label}: ice=${ep.peer.states.get('iceConnection')} conn=${ep.peer.states.get('connection')}`).join(' · '),
          false,
          err.message,
        );
      }

      try {
        await waitFor('dtls-connected', () => peers.every((ep) => ep.stats.latest !== null
          && ep.stats.latest.transport !== null
          && ep.stats.latest.transport.dtlsState === 'connected'), 30000);
        record('dtls-connected', 'transport.dtlsState = connected', peers.map((ep) => `${ep.label}: dtls=${ep.stats.latest.transport.dtlsState}`).join(' · '), true);
      } catch (err) {
        record('dtls-connected', 'transport.dtlsState = connected', peers.map((ep) => `${ep.label}: dtls=${ep.stats.latest && ep.stats.latest.transport ? ep.stats.latest.transport.dtlsState : '—'}`).join(' · '), false, err.message);
      }

      // ---- 7. 选中候选对 --------------------------------------------------------
      try {
        await waitFor('selected-pair', () => peers.every((ep) => ep.stats.latest !== null && ep.stats.latest.selectedPair !== null), 20000);
        record(
          'selected-candidate-pair',
          '每端都选出候选对（含 RTT 与收发字节）',
          peers.map((ep) => {
            const p = ep.stats.latest.selectedPair;
            const side = (s) => {
              if (s === null || s === undefined) return '?';
              const addr = typeof s.address === 'string' && s.address !== '' ? s.address : '（mDNS/未披露）';
              return `${s.candidateType}/${s.protocol} ${addr}:${s.port ?? '?'}`;
            };
            return `${ep.label}: ${side(p.local)} → ${side(p.remote)} rtt=${p.currentRoundTripTime ?? '—'}s`;
          }).join(' · '),
          true,
        );
      } catch (err) {
        record('selected-candidate-pair', '每端都选出候选对（含 RTT 与收发字节）', err.message, false);
      }

      // ---- 8. 媒体：双向字节增长（M-6）------------------------------------------
      const before = new Map();
      for (const ep of peers) {
        try { before.set(ep.label, await ep.sample()); } catch { before.set(ep.label, null); }
      }
      await sleep(2600);
      const after = new Map();
      for (const ep of peers) {
        try { after.set(ep.label, await ep.sample()); } catch { after.set(ep.label, null); }
      }
      const evidences = peers.map((ep) => ({ label: ep.label, ev: mediaEvidence(before.get(ep.label), after.get(ep.label)) }));
      record(
        'media-bidirectional',
        '每端 outbound bytesSent 与 inbound bytesReceived 均在增长（≥2.6 s）',
        evidences.map((e) => `${e.label}: out+${e.ev.outboundDelta ?? '—'} in+${e.ev.inboundDelta ?? '—'}`).join(' · '),
        evidences.every((e) => e.ev.both === true),
      );

      // ---- 9. VP9（M-5：由 stats codecId → mimeType 证明）------------------------
      const vp9Detail = peers.map((ep) => {
        const s = ep.stats.latest;
        return `${ep.label}: ${s === null ? '无样本' : (s.vp9 ? `VP9（${s.videoMimeTypes.join(',')}）` : `未见 VP9（${s.videoMimeTypes.join(',') || '无 video codec'}）`)}`;
      }).join(' · ');
      record('codec-vp9-from-stats', 'getStats() 的 codecId 能解析到 video/VP9', vp9Detail, peers.some((ep) => ep.stats.latest !== null && ep.stats.latest.vp9 === true));

      // ---- 10. leave → peerLeft -------------------------------------------------
      if (mode === 'popup') {
        // 注意：直接 window.close() 只是「掉线」——服务端按 90 s 宽限期**不发** peerLeft（B-8）。
        // 因此先通过 postMessage 让手机模拟器标签页显式 leave()，再关闭窗口。
        let left = false;
        try {
          if (phoneWindow !== null && !phoneWindow.closed) {
            phoneWindow.postMessage({ source: 'web-demo-host', event: 'leave' }, '*');
            left = await waitFor('peerLeft(popup)', () => hasFrame(host, 'peerLeft', 'recv'), 15000)
              .then(() => true)
              .catch(() => false);
          }
        } finally {
          if (phoneWindow !== null && !phoneWindow.closed) {
            try { phoneWindow.close(); } catch { /* 浏览器可能拒绝关闭 */ }
          }
        }
        record(
          'leave->peerLeft',
          '手机模拟器标签页 leave 后房主收到 peerLeft',
          left ? 'peerLeft 已到达' : '未在 15 s 内收到 peerLeft',
          left,
          'popup 模式：房主 postMessage 令手机模拟器执行 leave()（直接关窗口只会掉线、保座 90 s）',
        );
      } else {
        try {
          const left = joiner.leave();
          await waitFor('peerLeft', () => hasFrame(host, 'peerLeft', 'recv'));
          record('leave->peerLeft', '离开方发送 leave，房主收到 peerLeft', `leaveSent=${left} peerLeft=已到达`, true);
        } catch (err) {
          record('leave->peerLeft', '离开方发送 leave，房主收到 peerLeft', err.message, false);
        }
      }

      // ---- 11. 清理 -------------------------------------------------------------
      if (mode !== 'popup' && joiner !== null) {
        record(
          'joiner-socket-closed',
          'leave 后离开方连接进入 closed 且不再重连',
          `state=${joiner.client.state} reconnectExhausted=${joiner.client.reconnectExhausted}`,
          joiner.client.state === 'closed' && joiner.client.reconnectExhausted === false,
        );
      }
      host.leave();
    } catch (err) {
      record('matrix-harness', '矩阵完整执行', err && err.message ? err.message : String(err), false);
      note('error', `[selftest] 异常终止：${err && err.message ? err.message : String(err)}`);
    } finally {
      for (const ep of endpoints) {
        try { ep.stopStats(); } catch { /* 忽略 */ }
        try { ep.peer.close(); } catch { /* 忽略 */ }
        try { if (ep.localStream !== null && typeof ep.localStream.stopSynthetic === 'function') ep.localStream.stopSynthetic(); } catch { /* 忽略 */ }
      }
      if (phoneWindow !== null && !phoneWindow.closed) {
        try { phoneWindow.close(); } catch { /* 忽略 */ }
      }
    }

    // 只有非 info 行计入 PASS/FAIL（info 行是观察性记录，如 Chrome 的编码顺序）。
    const scored = rows.filter((r) => r.info !== true);
    const pass = scored.filter((r) => r.pass === true).length;
    const fail = scored.filter((r) => r.pass !== true).length;
    const result = {
      generatedAt: new Date().toISOString(),
      pageUrl: typeof location !== 'undefined' ? location.href : '',
      mode,
      url,
      roomId,
      durationMs: Date.now() - started,
      maxIceRestarts: MAX_ICE_RESTARTS,
      pass,
      fail,
      total: scored.length,
      infoRows: rows.filter((r) => r.info === true).length,
      ok: fail === 0,
      rows,
    };
    last = result;
    console.table(rows.map((r) => ({ id: r.id, pass: r.pass, actual: String(r.actual).slice(0, 90) })));
    console.log(`[selftest] === PASS ${pass}, FAIL ${fail} ===（mode=${mode}，用时 ${result.durationMs} ms）`);
    note(fail === 0 ? 'info' : 'error', `[selftest] PASS ${pass} / FAIL ${fail}（mode=${mode}）`);
    return result;
  }

  return {
    run,
    get last() { return last; },
    exportJSON() {
      return last === null ? { generatedAt: new Date().toISOString(), rows: [], pass: 0, fail: 0 } : { ...last };
    },
  };
}

export default createSelftest;
