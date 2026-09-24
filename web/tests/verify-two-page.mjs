#!/usr/bin/env node
// verify-two-page.mjs — headless verification for the browser video-call demo.
//
// Two modes:
//   --mode probe  (default)
//       Serves an embedded probe page and drives TWO headless Chrome pages
//       through the LIVE signalling service: create/created -> join/joined ->
//       peerJoined -> offer/answer/ice -> ICE+DTLS connected -> VP9 via
//       getStats -> bidirectional RTP growth over 5 s -> leave/peerLeft.
//       This is the executable form of requirement A-3 (reports/70 §8 A-3),
//       and it depends on nothing but this script.
//   --mode demo
//       Loads the real demo page in two tabs and runs its own matrix
//       (web/lib/selftest.js, reports/70 §8 A-4), plus page-load hygiene and
//       signalling reachability from the page origin.
//   --mode auto   opt-in: pick demo when web/index.html exists, else probe;
//                 the choice and the reason are printed. The default is probe
//                 so this script never depends on another deliverable being
//                 finished to produce an honest verdict for T3.
//
// Every assertion prints one `PASS <id> <detail>` / `FAIL <id> <detail>` line;
// the canonical raw log is written to --log (default /tmp/web-demo-verify.log)
// and a machine-readable stats export next to it.
//
// Exit codes: 0 all assertions passed · 1 an assertion failed · 2 usage/env error.

import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync, readFileSync, statSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ensureChrome, chromeVersion, human, cacheDirFromEnv, dirSizeBytes } from './lib/chrome.mjs';
import { launch, newPage } from './lib/cdp.mjs';
import { startServer } from './lib/serve.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..', '..');
const DEMO_PAGE = join(REPO_ROOT, 'web', 'index.html');

const DEFAULTS = {
  // Live signalling: plain ws:// on 8443, path /ws. There are exactly two valid
  // addresses (reports/70 §2.1.1): the public one below and the container-local
  // equivalent ws://172.18.0.1:8443/ws. 127.0.0.1:8443 is the DSH harness Caddy
  // inside this container and must never be used as the signalling endpoint.
  signaling: process.env.WEB_DEMO_SIGNALING || 'ws://47.238.144.66:8443/ws',
  stun: process.env.WEB_DEMO_STUN || 'stun:47.238.144.66:3478',
  cacheDir: cacheDirFromEnv(),
  logFile: process.env.WEB_DEMO_LOG || join(tmpdir(), 'web-demo-verify.log'),
  mode: 'probe',
  pageUrl: null,
  staticPort: 8081,
};

function parseArgs(argv) {
  const opts = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--signaling') opts.signaling = argv[++i];
    else if (arg === '--stun') opts.stun = argv[++i];
    else if (arg === '--cache') opts.cacheDir = argv[++i];
    else if (arg === '--log') opts.logFile = argv[++i];
    else if (arg === '--mode') opts.mode = argv[++i];
    else if (arg === '--page') { opts.pageUrl = argv[++i]; opts.mode = 'demo'; }
    else if (arg === '--port') opts.staticPort = Number(argv[++i]);
    else if (arg === '--help' || arg === '-h') {
      console.log('usage: node verify-two-page.mjs [--mode auto|probe|demo] [--page URL]');
      console.log('       [--signaling ws://host:8443/ws] [--stun URL] [--cache DIR] [--log FILE] [--port 8081]');
      process.exit(0);
    } else { console.error(`unknown argument: ${arg}`); process.exit(2); }
  }
  for (const mode of ['auto', 'probe', 'demo']) {
    if (opts.mode === mode) return opts;
  }
  console.error(`unknown --mode ${opts.mode} (expected auto|probe|demo)`);
  process.exit(2);
}

const results = [];
const raw = [];
function record(id, ok, detail = '') {
  results.push({ id, ok, detail });
  const line = `${ok ? 'PASS' : 'FAIL'}  ${id}${detail ? '  :: ' + detail : ''}`;
  console.log(line);
  raw.push(`${new Date().toISOString()} ${line}`);
}
function note(text) { console.log(`      ${text}`); raw.push(`${new Date().toISOString()} ${text}`); }

// ---------------------------------------------------------------------------
// Embedded probe page: a minimal signalling client plus a WebRTC call driver.
// It is deliberately independent of web/lib/**: the verifier must not depend on
// the implementation it verifies, and T3 must be runnable before the page lands.
// ---------------------------------------------------------------------------
const PROBE_PAGE = `<!doctype html>
<meta charset="utf-8"><title>web-demo probe</title>
<body><pre id="state">idle</pre>
<script>
window.__probe = (function () {
  var timeline = [], inbox = [], waiters = [], listeners = [];
  var ws = null, pc = null, stream = null, iceServers = [];
  var pendingRemoteIce = [], remoteReady = false;
  var lastStats = null;

  function stamp() { return Math.round(performance.now()); }
  function event(kind, dir, type, payload, note) {
    timeline.push({ t: stamp(), kind: kind, dir: dir, type: type, payload: payload === undefined ? null : payload, note: note || null });
  }
  function emit(msg) {
    for (var i = 0; i < listeners.length; i++) { try { listeners[i](msg); } catch (e) { event('handler-error', 'local', msg.type, String(e)); } }
  }
  function offerWaiting(type) {
    for (var i = 0; i < waiters.length; i++) {
      if (waiters[i].type === type && !waiters[i].done) { waiters[i].done = true; clearTimeout(waiters[i].timer); waiters[i].resolve(waiters[i].msg); }
    }
  }
  function deliver(msg) {
    for (var i = 0; i < waiters.length; i++) { if (waiters[i].type === msg.type && !waiters[i].done) { waiters[i].done = true; clearTimeout(waiters[i].timer); waiters[i].resolve(msg); return; } }
    inbox.push(msg);
  }
  function waitMsg(type, timeoutMs) {
    for (var i = 0; i < inbox.length; i++) { if (inbox[i].type === type) { return Promise.resolve(inbox.splice(i, 1)[0]); } }
    return new Promise(function (resolvePromise, rejectPromise) {
      var w = { type: type, done: false, resolve: resolvePromise, msg: null };
      w.timer = setTimeout(function () { if (!w.done) { w.done = true; rejectPromise(new Error('timeout waiting for "' + type + '" after ' + timeoutMs + 'ms')); } }, timeoutMs);
      waiters.push(w);
    });
  }
  function messageCount(type) { var n = 0; for (var i = 0; i < timeline.length; i++) { if (timeline[i].type === type && timeline[i].payload) n++; } return n; }
  function directionCount(type, dir) { var n = 0; for (var i = 0; i < timeline.length; i++) { if (timeline[i].type === type && timeline[i].dir === dir) n++; } return n; }

  function connect(url, timeoutMs) {
    return new Promise(function (resolvePromise, rejectPromise) {
      ws = new WebSocket(url);
      var timer = setTimeout(function () { rejectPromise(new Error('websocket open timeout after ' + timeoutMs + 'ms')); }, timeoutMs);
      ws.onopen = function () { clearTimeout(timer); event('ws', 'local', 'open', null, url); resolvePromise({ url: url }); };
      ws.onerror = function () { clearTimeout(timer); event('ws', 'local', 'error', null, url); rejectPromise(new Error('websocket error connecting to ' + url)); };
      ws.onclose = function (e) { event('ws', 'local', 'close', { code: e.code, reason: e.reason }); };
      ws.onmessage = function (e) {
        var msg = null;
        try { msg = JSON.parse(String(e.data)); } catch (err) { event('recv', 'recv', 'unparsable', String(e.data).slice(0, 200)); deliver({ type: 'unparsable' }); return; }
        event('recv', 'recv', msg.type, msg);
        emit(msg);
        deliver(msg);
      };
    });
  }
  function send(obj) { event('send', 'send', obj.type, obj); ws.send(JSON.stringify(obj)); return obj; }
  function ping(timeoutMs) {
    var started = stamp();
    send({ type: 'ping', timestamp: Date.now() });
    return waitMsg('pong', timeoutMs).then(function (pong) { return { ok: true, rttMs: stamp() - started, reply: pong }; });
  }
  function iceServersFrom(cfg) {
    // reports/70 §I-1: ICE config comes only from the server handout.
    // reports/70 §I-2: TURN UDP first, then the same credentials over TCP.
    var servers = [];
    if (cfg && cfg.stunUrl) servers.push({ urls: cfg.stunUrl });
    if (cfg && cfg.turnUrl && cfg.turnUsername && cfg.turnCredential) {
      var urls = [cfg.turnUrl];
      if (/[?&]transport=udp/.test(cfg.turnUrl)) urls.push(cfg.turnUrl.replace('transport=udp', 'transport=tcp'));
      servers.push({ urls: urls, username: cfg.turnUsername, credential: cfg.turnCredential });
    }
    return servers;
  }
  function serverConfig(cfg) { return { stunUrl: cfg.stunUrl || null, turnUrl: cfg.turnUrl || null, turnUsername: cfg.turnUsername || null, turnCredentialPresent: !!cfg.turnCredential }; }

  function newPeerConnection(label) {
    var pcLocal = new RTCPeerConnection({ iceServers: iceServers });
    pcLocal.onicecandidate = function (e) { if (e.candidate) send({ type: 'ice', candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex }); };
    pcLocal.onconnectionstatechange = function () { event('state', label, 'connection', { value: pcLocal.connectionState }); };
    pcLocal.oniceconnectionstatechange = function () { event('state', label, 'iceConnection', { value: pcLocal.iceConnectionState }); };
    pcLocal.ondtlsstatechange = function () { event('state', label, 'dtls', { value: pcLocal.dtlsTransport && pcLocal.dtlsTransport.state ? pcLocal.dtlsTransport.state : null }); };
    pcLocal.ontrack = function (e) { event('track', label, 'remote-track', { kind: e.track.kind }); };
    pc = pcLocal;
    return pcLocal;
  }
  function applyIce(msg) {
    var init = { candidate: msg.candidate, sdpMid: msg.sdpMid, sdpMLineIndex: msg.sdpMLineIndex };
    return pc.addIceCandidate(init).catch(function (err) { event('ice-error', 'local', 'ice', String(err && err.message)); });
  }
  function onMessage(fn) { listeners.push(fn); }
  function drainRemoteIce() {
    remoteReady = true;
    var queued = pendingRemoteIce.splice(0, pendingRemoteIce.length);
    // ICE that arrived before the remote description was applied is still in the
    // inbox (deliver() only parks messages nobody is waiting for); take it too.
    for (var i = inbox.length - 1; i >= 0; i--) { if (inbox[i].type === 'ice') { queued.push(inbox.splice(i, 1)[0]); } }
    return Promise.all(queued.map(applyIce));
  }
  function handleIce(msg) { if (!remoteReady) { pendingRemoteIce.push(msg); return Promise.resolve(); } return applyIce(msg); }

  function startMedia() {
    return navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 480 }, audio: true }).then(function (s) {
      stream = s;
      var track = s.getVideoTracks()[0];
      event('media', 'local', 'gum', { label: track.label, settings: track.getSettings() });
      return { label: track.label, settings: track.getSettings(), tracks: s.getTracks().map(function (t) { return t.kind; }) };
    });
  }
  function preferVp9(pcLocal) {
    // reports/70 §M-5: the browser must pin the video codec preference to VP9.
    // Only video transceivers may receive video codec preferences — passing the
    // video list to the audio transceiver throws InvalidModificationError.
    if (typeof RTCRtpSender.getCapabilities !== 'function') return 'unsupported';
    var codecs = RTCRtpSender.getCapabilities('video').codecs;
    var vp9 = codecs.filter(function (c) { return /VP9/i.test(c.mimeType); });
    var rest = codecs.filter(function (c) { return !/VP9/i.test(c.mimeType); });
    if (!vp9.length) return 'no-vp9-capability';
    var transceivers = pcLocal.getTransceivers();
    var applied = 0;
    for (var i = 0; i < transceivers.length; i++) {
      var kind = (transceivers[i].sender && transceivers[i].sender.track && transceivers[i].sender.track.kind) ||
                 (transceivers[i].receiver && transceivers[i].receiver.track && transceivers[i].receiver.track.kind);
      if (kind !== 'video') continue;
      if (!transceivers[i].setCodecPreferences) continue;
      transceivers[i].setCodecPreferences(vp9.concat(rest));
      applied++;
    }
    event('codec', 'local', 'prefer-vp9', { vp9Codecs: vp9.length, transceivers: applied });
    return applied > 0 ? 'ok' : 'no-video-transceiver';
  }

  function hostCreate() {
    send({ type: 'create' });
    return waitMsg('created', 10000).then(function (created) {
      iceServers = iceServersFrom(created);
      return { roomId: created.roomId, iceServers: iceServers, server: serverConfig(created), peerId: null };
    });
  }
  function guestJoin(roomId) {
    send({ type: 'join', roomId: roomId });
    return waitMsg('joined', 10000).then(function (joined) {
      iceServers = iceServersFrom(joined);
      return { roomId: joined.roomId, peerId: joined.peerId, iceServers: iceServers, server: serverConfig(joined) };
    });
  }
  function waitPeerJoined(timeoutMs) {
    return waitMsg('peerJoined', timeoutMs).then(function (m) { return { peerId: m.peerId }; });
  }
  function hostOffer() {
    return startMedia().then(function (media) {
      var pcLocal = newPeerConnection('host');
      stream.getTracks().forEach(function (t) { pcLocal.addTrack(t, stream); });
      var preference = preferVp9(pcLocal);
      return pcLocal.createOffer().then(function (offer) { return pcLocal.setLocalDescription(offer).then(function () {
        send({ type: 'offer', sdp: pcLocal.localDescription.sdp });
        return { sdp: pcLocal.localDescription.sdp, type: pcLocal.localDescription.type, media: media, codecPreference: preference };
      }); });
    });
  }
  function guestAnswer() {
    return startMedia().then(function (media) {
      var pcLocal = newPeerConnection('guest');
      stream.getTracks().forEach(function (t) { pcLocal.addTrack(t, stream); });
      onMessage(function (msg) { if (msg.type === 'ice') handleIce(msg); });
      return waitMsg('offer', 20000).then(function (offer) {
        return pcLocal.setRemoteDescription({ type: 'offer', sdp: offer.sdp })
          .then(function () { return drainRemoteIce(); })
          .then(function () { return pcLocal.createAnswer(); })
          .then(function (answer) { return pcLocal.setLocalDescription(answer); })
          .then(function () {
            send({ type: 'answer', sdp: pcLocal.localDescription.sdp });
            return { sdp: pcLocal.localDescription.sdp, type: pcLocal.localDescription.type, media: media };
          });
      });
    });
  }
  function hostFinish() {
    onMessage(function (msg) { if (msg.type === 'ice') handleIce(msg); });
    return waitMsg('answer', 20000).then(function (answer) {
      return pc.setRemoteDescription({ type: 'answer', sdp: answer.sdp }).then(function () { return drainRemoteIce(); });
    });
  }
  function waitConnected(timeoutMs) {
    return new Promise(function (resolvePromise) {
      if (pc.connectionState === 'connected' || pc.connectionState === 'completed') return resolvePromise(pc.connectionState);
      var timer = setTimeout(function () { resolvePromise('timeout:' + pc.connectionState); }, timeoutMs);
      pc.addEventListener('connectionstatechange', function () {
        if (pc.connectionState === 'connected' || pc.connectionState === 'completed') { clearTimeout(timer); resolvePromise(pc.connectionState); }
        if (pc.connectionState === 'failed') { clearTimeout(timer); resolvePromise('failed'); }
      });
    });
  }
  function codecOf(stats, report, kind, dir) {
    var entries = [];
    stats.forEach(function (s) { if (s.type === dir + '-rtp' && s.kind === kind) entries.push(s); });
    if (!entries.length) return null;
    var entry = entries[0];
    var codec = null;
    stats.forEach(function (s) { if (s.type === 'codec' && s.id === entry.codecId) codec = s; });
    return { codecId: entry.codecId, mimeType: codec ? codec.mimeType : null, clockRate: codec ? codec.clockRate : null, payloadType: codec ? codec.payloadType : null };
  }
  function sample() {
    return pc.getStats().then(function (stats) {
      function pick(type, kind) { var out = []; stats.forEach(function (s) { if (s.type === type && (!kind || s.kind === kind)) out.push(s); }); return out; }
      var transports = pick('transport');
      var pairs = pick('candidate-pair').filter(function (p) { return p.state === 'succeeded' && p.nominated !== false; });
      var selected = pairs.length ? pairs[0] : null;
      var localCand = null, remoteCand = null;
      stats.forEach(function (s) {
        if (selected && s.type === 'local-candidate' && s.id === selected.localCandidateId) localCand = s;
        if (selected && s.type === 'remote-candidate' && s.id === selected.remoteCandidateId) remoteCand = s;
      });
      var outboundVideo = pick('outbound-rtp', 'video')[0] || null;
      var inboundVideo = pick('inbound-rtp', 'video')[0] || null;
      var outboundAudio = pick('outbound-rtp', 'audio')[0] || null;
      var inboundAudio = pick('inbound-rtp', 'audio')[0] || null;
      var snapshot = {
        t: stamp(),
        connectionState: pc.connectionState,
        iceConnectionState: pc.iceConnectionState,
        iceGatheringState: pc.iceGatheringState,
        dtlsState: transports.length ? transports[0].dtlsState : null,
        signalingState: pc.signalingState,
        outboundVideo: outboundVideo ? { bytesSent: outboundVideo.bytesSent, framesEncoded: outboundVideo.framesEncoded, width: outboundVideo.frameWidth, height: outboundVideo.frameHeight } : null,
        inboundVideo: inboundVideo ? { bytesReceived: inboundVideo.bytesReceived, framesDecoded: inboundVideo.framesDecoded, width: inboundVideo.frameWidth, height: inboundVideo.frameHeight } : null,
        outboundAudio: outboundAudio ? { bytesSent: outboundAudio.bytesSent } : null,
        inboundAudio: inboundAudio ? { bytesReceived: inboundAudio.bytesReceived } : null,
        outboundCodec: codecOf(stats, null, 'video', 'outbound'),
        inboundCodec: codecOf(stats, null, 'video', 'inbound'),
        selectedPair: selected ? { state: selected.state, rtt: selected.currentRoundTripTime === undefined ? null : selected.currentRoundTripTime, bytesSent: selected.bytesSent, bytesReceived: selected.bytesReceived, requestsSent: selected.requestsSent, responseReceived: selected.responsesReceived, localType: localCand ? localCand.candidateType : null, localProtocol: localCand ? localCand.protocol : null, remoteType: remoteCand ? remoteCand.candidateType : null, remoteProtocol: remoteCand ? remoteCand.protocol : null } : null,
        candidates: {
          local: pick('local-candidate').map(function (c) { return c.candidateType + '/' + c.protocol + '/' + c.address + ':' + c.port; }),
          remote: pick('remote-candidate').map(function (c) { return c.candidateType + '/' + c.protocol + '/' + c.address + ':' + c.port; })
        }
      };
      lastStats = snapshot;
      return snapshot;
    });
  }
  function leave() { send({ type: 'leave' }); return true; }
  function closeSocket(code) { ws.close(code === undefined ? 1000 : code); event('ws', 'local', 'closing', { code: code }); }
  function snapshotForExport() { return { timeline: timeline, lastStats: lastStats }; }

  return {
    connect: connect, send: send, ping: ping, waitMsg: waitMsg, messageCount: messageCount, directionCount: directionCount,
    hostCreate: hostCreate, guestJoin: guestJoin, waitPeerJoined: waitPeerJoined, hostOffer: hostOffer, guestAnswer: guestAnswer,
    hostFinish: hostFinish, waitConnected: waitConnected, sample: sample, leave: leave, closeSocket: closeSocket,
    startMedia: startMedia, timeline: function () { return timeline; }, snapshotForExport: snapshotForExport
  };
})();
</script>`;

const PROBE_STATS_TIMEOUT = 25000;

async function runProbeMode(ctx) {
  const { conn, chrome, opts, pageUrl } = ctx;

  // ---- page hygiene + signalling reachability ----------------------------
  const host = await newPage(conn, pageUrl);
  const guest = await newPage(conn, pageUrl);
  // t11-amend-1 (F11): explicit, diagnosable readiness gate — the probe page
  // must have executed its inline script (which defines window.__probe) before
  // any evaluate below touches it.
  await host.waitForGlobal('__probe', 20000);
  await guest.waitForGlobal('__probe', 20000);
  const facts = await host.evaluate(() => ({
    secureContext: window.isSecureContext,
    origin: location.origin,
    hasMediaDevices: typeof navigator.mediaDevices === 'object' && typeof navigator.mediaDevices.getUserMedia === 'function',
    ua: navigator.userAgent,
  }));
  record('page.secure-context', facts.secureContext === true, `origin=${facts.origin}`);
  record('page.mediadevices', facts.hasMediaDevices === true, facts.ua);

  await host.evaluate((url) => window.__probe.connect(url, 8000), opts.signaling);
  const pong = await host.evaluate((ms) => window.__probe.ping(ms), 8000);
  record('signaling.ping-pong', pong.ok === true, `rtt=${pong.rttMs}ms reply=${JSON.stringify(pong.reply)}`);

  // ---- room lifecycle through the live service ---------------------------
  const created = await host.evaluate(() => window.__probe.hostCreate());
  record('signaling.created', typeof created.roomId === 'string' && created.roomId.length === 6 && !!created.server.stunUrl && !!created.server.turnUrl && created.server.turnCredentialPresent === true,
    `roomId=${created.roomId} server=${JSON.stringify(created.server)}`);
  note(`host iceServers=${JSON.stringify(created.iceServers)}`);

  await guest.evaluate((url) => window.__probe.connect(url, 8000), opts.signaling);
  const joined = await guest.evaluate((roomId) => window.__probe.guestJoin(roomId), created.roomId);
  record('signaling.joined', joined.roomId === created.roomId && /^peer-\d+$/.test(String(joined.peerId)),
    `peerId=${joined.peerId} roomId=${joined.roomId}`);

  const peerJoined = await host.evaluate((ms) => window.__probe.waitPeerJoined(ms), 10000);
  record('signaling.peerJoined', /^peer-\d+$/.test(String(peerJoined.peerId)) && peerJoined.peerId === joined.peerId,
    `host saw peerJoined peerId=${peerJoined.peerId}, guest joined as ${joined.peerId} (must match)`);

  // ---- offer / answer / ICE over the live service ------------------------
  const guestAnswerPromise = guest.evaluate(() => window.__probe.guestAnswer());
  const offer = await host.evaluate(() => window.__probe.hostOffer());
  record('capture.fake-device', offer.media.settings.width === 640 && offer.media.settings.height === 480,
    `label=${offer.media.label} settings=${JSON.stringify(offer.media.settings)}`);
  record('sdp.offer-contains-vp9', /VP9\/90000/.test(offer.sdp), `codecPreference=${offer.codecPreference} (video/VP9/90000 required for App interop)`);

  const guestAnswer = await guestAnswerPromise;
  record('signaling.offer-answer-relayed', guestAnswer.type === 'answer' && guestAnswer.sdp.length > 0, `answer bytes=${guestAnswer.sdp.length}`);
  await host.evaluate(() => window.__probe.hostFinish());

  const hostState = await host.evaluate((ms) => window.__probe.waitConnected(ms), PROBE_STATS_TIMEOUT);
  const guestState = await guest.evaluate((ms) => window.__probe.waitConnected(ms), PROBE_STATS_TIMEOUT);
  const first = await host.evaluate(() => window.__probe.sample());
  const guestFirst = await guest.evaluate(() => window.__probe.sample());
  const iceCounts = await host.evaluate(() => ({
    sent: window.__probe.directionCount('ice', 'send'),
    recv: window.__probe.directionCount('ice', 'recv'),
  }));
  const guestIce = await guest.evaluate(() => ({ sent: window.__probe.directionCount('ice', 'send'), recv: window.__probe.directionCount('ice', 'recv') }));
  record('signaling.ice-exchanged', iceCounts.sent > 0 && iceCounts.recv > 0 && guestIce.sent > 0 && guestIce.recv > 0,
    `host sent=${iceCounts.sent} recv=${iceCounts.recv}; guest sent=${guestIce.sent} recv=${guestIce.recv}`);
  record('ice.connected', hostState === 'connected' && guestState === 'connected' && first.dtlsState === 'connected' && guestFirst.dtlsState === 'connected',
    `host=${hostState} guest=${guestState} dtls=${first.dtlsState}/${guestFirst.dtlsState} localPair=${JSON.stringify(first.selectedPair)}`);

  record('media.codec-vp9-getstats', first.outboundCodec && /VP9/i.test(String(first.outboundCodec.mimeType)),
    `host outbound-rtp codec=${JSON.stringify(first.outboundCodec)}`);

  // ---- M-6: bidirectional RTP byte growth sustained over 5 s -------------
  note('sampling stats for 5 s to prove sustained bidirectional media (reports/70 §M-6)');
  await new Promise((r) => setTimeout(r, 5000));
  const second = await host.evaluate(() => window.__probe.sample());
  const guestSecond = await guest.evaluate(() => window.__probe.sample());
  const grown = (a, b, warn) => Number(b?.[warn] || 0) - Number(a?.[warn] || 0);
  const hostToGuestBytes = grown(first.outboundVideo, second.outboundVideo, 'bytesSent');
  const guestToHostBytes = grown(guestFirst.outboundVideo, guestSecond.outboundVideo, 'bytesSent');
  const guestInboundBytes = grown(guestFirst.inboundVideo, guestSecond.inboundVideo, 'bytesReceived');
  const hostInboundBytes = grown(first.inboundVideo, second.inboundVideo, 'bytesReceived');
  record('media.bidirectional-rtp', hostToGuestBytes > 0 && guestToHostBytes > 0 && guestInboundBytes > 0 && hostInboundBytes > 0,
    `host->guest bytesSent +${hostToGuestBytes}, guest inbound +${guestInboundBytes}; guest->host bytesSent +${guestToHostBytes}, host inbound +${hostInboundBytes}`);
  record('media.frames-flowing', (second.outboundVideo?.framesEncoded || 0) > (first.outboundVideo?.framesEncoded || 0) && (guestSecond.inboundVideo?.framesDecoded || 0) > (guestFirst.inboundVideo?.framesDecoded || 0),
    `host framesEncoded ${first.outboundVideo?.framesEncoded}->${second.outboundVideo?.framesEncoded}, guest framesDecoded ${guestFirst.inboundVideo?.framesDecoded}->${guestSecond.inboundVideo?.framesDecoded}`);
  // reports/70 §M-5: the receive side must also resolve its RTP codec to VP9.
  // Sampled after the 5 s window because inbound-rtp only exists once media flows.
  record('media.codec-vp9-getstats-inbound', guestSecond.inboundCodec && /VP9/i.test(String(guestSecond.inboundCodec.mimeType)),
    `guest inbound-rtp codec=${JSON.stringify(guestSecond.inboundCodec)}`);
  record('media.audio-rtp', grown(first.outboundAudio, second.outboundAudio, 'bytesSent') > 0 && grown(guestFirst.inboundAudio, guestSecond.inboundAudio, 'bytesReceived') > 0,
    `audio bytes host->guest +${grown(first.outboundAudio, second.outboundAudio, 'bytesSent')}, guest received +${grown(guestFirst.inboundAudio, guestSecond.inboundAudio, 'bytesReceived')}`);

  // ---- leave / peerLeft ---------------------------------------------------
  await guest.evaluate(() => window.__probe.leave());
  const peerLeft = await host.evaluate((ms) => window.__probe.waitMsg('peerLeft', ms).then((m) => m).catch((e) => ({ error: String(e.message) })), 10000);
  record('signaling.leave-peerleft', !!peerLeft && peerLeft.type === 'peerLeft', JSON.stringify(peerLeft));
  await guest.evaluate(() => window.__probe.closeSocket(1000));

  // ---- export -------------------------------------------------------------
  const exportData = {
    generatedAt: new Date().toISOString(),
    pageUrl,
    signaling: opts.signaling,
    stun: opts.stun,
    room: { roomId: created.roomId, hostPeerId: joined.peerId, hostSawPeerJoined: peerJoined.peerId },
    iceServers: created.iceServers,
    assertions: () => undefined,
    host: { timeline: await host.evaluate(() => window.__probe.timeline()), samples: [first, second] },
    guest: { timeline: await guest.evaluate(() => window.__probe.timeline()), samples: [guestFirst, guestSecond] },
  };
  delete exportData.assertions;
  ctx.exportData = exportData;

  await host.close();
  await guest.close();
}

async function runDemoMode(ctx) {
  const { conn, opts, pageUrl } = ctx;
  const tabA = await newPage(conn, pageUrl);
  await tabA.goto(pageUrl).catch(() => {}); // wait for the module graph to load
  // The console entry point may be attached a moment after load; give it 10 s
  // before declaring the contract interface missing.
  const facts = await tabA.evaluate(async () => {
    for (let i = 0; i < 100; i++) {
      if (window.selftest && typeof window.selftest.run === 'function') break;
      await new Promise((r) => setTimeout(r, 100));
    }
    return {
      secureContext: window.isSecureContext,
      origin: location.origin,
      hasSelftest: !!(window.selftest && typeof window.selftest.run === 'function'),
      hasSelftestButton: !!document.querySelector('#btn-selftest'),
      globals: Object.keys(window).filter((k) => /selftest|selfTest|webDemo/i.test(k)).slice(0, 10),
    };
  });
  record('page.secure-context', facts.secureContext === true, `origin=${facts.origin}`);

  const pong = await tabA.evaluate(async (url) => {
    return await new Promise((done) => {
      let ws;
      try { ws = new WebSocket(url); } catch (e) { return done({ ok: false, error: String(e) }); }
      const timer = setTimeout(() => { done({ ok: false, error: 'timeout 8000ms' }); try { ws.close(); } catch {} }, 8000);
      ws.onopen = () => ws.send(JSON.stringify({ type: 'ping', timestamp: Date.now() }));
      ws.onmessage = (e) => { clearTimeout(timer); let p = null; try { p = JSON.parse(String(e.data)); } catch {} ws.close(); done({ ok: p && p.type === 'pong', reply: p, error: p ? null : String(e.data).slice(0, 120) }); };
      ws.onerror = () => { clearTimeout(timer); done({ ok: false, error: 'websocket error' }); };
    });
  }, opts.signaling);
  record('signaling.ping-pong', pong.ok === true, JSON.stringify(pong.reply || pong.error));

  record('page.selftest-api', facts.hasSelftest === true,
    facts.hasSelftest
      ? 'window.selftest.run present (reports/70 §8 A-4)'
      : `console entry point missing: window.selftest.run() not exposed (button #btn-selftest present=${facts.hasSelftestButton}, matching globals=${JSON.stringify(facts.globals)}) — reports/70 §8 A-4 requires both the button and the console entry point`);
  if (facts.hasSelftest) {
    const table = await tabA.evaluate(async () => {
      const result = await window.selftest.run();
      if (result && typeof result === 'object') return JSON.parse(JSON.stringify(result));
      return { raw: String(result) };
    });
    const failed = Number(table.fail ?? table.failed ?? (Array.isArray(table.rows) ? table.rows.filter((r) => !r.pass).length : NaN));
    const passed = Number(table.pass ?? (Array.isArray(table.rows) ? table.rows.filter((r) => r.pass).length : NaN));
    record('selftest.run', Number.isFinite(failed) ? failed === 0 : Object.keys(table).length > 0,
      `pass=${passed} fail=${failed} :: ${JSON.stringify(table).slice(0, 600)}`);
    ctx.exportData = { generatedAt: new Date().toISOString(), pageUrl, signaling: opts.signaling, selftest: table };
  }
  await tabA.close();
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const logDir = dirname(resolve(opts.logFile));
  mkdirSync(logDir, { recursive: true });
  raw.push(`web-demo-verify ${new Date().toISOString()}`);

  const runtimeDir = join(opts.cacheDir, 'runtime'); // profiles + probe page: never the 256 MiB /tmp tmpfs
    // Reap profiles left by an earlier run that was killed hard (timeout/SIGKILL):
    // without this they accumulate and fill the volume.
    try {
      for (const entry of readdirSync(runtimeDir)) {
        if (entry.startsWith("profile-") || entry.startsWith("probe-")) rmSync(join(runtimeDir, entry), { recursive: true, force: true });
      }
    } catch { /* nothing to reap */ }

  let mode = opts.mode;
  if (mode === 'auto') {
    mode = existsSync(DEMO_PAGE) ? 'demo' : 'probe';
    note(`mode=auto -> ${mode} (web/index.html ${existsSync(DEMO_PAGE) ? 'present' : 'absent'})`);
  }
  note(`mode=${mode}`);
  note(`signaling=${opts.signaling}`);
  note(`stun=${opts.stun}`);
  note(`cache=${opts.cacheDir} (${existsSync(opts.cacheDir) ? human(dirSizeBytes(opts.cacheDir)) + ' present' : 'absent, will bootstrap'})`);

  let chrome = null, server = null, conn = null, pageRoot = null;
  let envError = false;
  const ctx = { opts, exportData: null };
  try {
    chrome = await ensureChrome({ cacheDir: opts.cacheDir, log: note });
    const version = await chromeVersion(chrome.bin, chrome.env);
    record('browser.boot', /Chrome for Testing/.test(version), version);

    let pageUrl;
    if (mode === 'probe') {
      mkdirSync(runtimeDir, { recursive: true });
      pageRoot = mkdtempSync(join(runtimeDir, 'probe-'));
      writeFileSync(join(pageRoot, 'index.html'), PROBE_PAGE);
      server = await startServer({ root: pageRoot, port: 0, quiet: true });
      pageUrl = server.url;
    } else {
      // The demo site root is web/ (that is what scripts/serve-web-demo.sh
      // serves too), so `/` resolves to web/index.html and its relative
      // imports (./app.js, ./lib/**) stay inside the site.
      const url = new URL(opts.pageUrl || `http://localhost:${opts.staticPort}/`);
      server = await startServer({ root: join(REPO_ROOT, 'web'), port: opts.staticPort, quiet: true });
      pageUrl = url.href;
    }
    note(`page=${pageUrl}`);
    ctx.pageUrl = pageUrl;

    conn = await launch({
      bin: chrome.bin,
      env: { ...chrome.env, runtimeDir },
      args: [
        // The contract (reports/70 §8 A-3) names --headless=new. The pinned
        // binary is chrome-headless-shell, which is headless by construction and
        // accepts the flag as a no-op; it is passed verbatim so a reader can
        // match flag-for-flag, and it becomes meaningful if the full chrome
        // binary is bootstrapped instead.
        '--headless=new',
        '--use-fake-device-for-media-stream',
        '--use-fake-ui-for-media-stream',
        '--autoplay-policy=no-user-gesture-required',
        '--allow-running-insecure-content',
      ],
    });
    ctx.conn = conn; ctx.chrome = chrome;

    if (mode === 'probe') await runProbeMode(ctx);
    else await runDemoMode(ctx);
  } catch (err) {
    envError = !!(err && err.envError);
    record('harness.error', false, err && err.stack ? err.stack.split('\n').slice(0, 3).join(' | ') : String(err));
    raw.push('--- error detail ---');
    raw.push(err && err.stack ? err.stack : String(err));
  } finally {
    if (conn) {
      if (conn.consoleLines?.length) {
        raw.push('--- page console (last 120) ---');
        for (const line of conn.consoleLines.slice(-120)) raw.push(line);
      }
      if (conn.stderrLines?.length) {
        raw.push('--- chrome stderr (last 30) ---');
        for (const line of conn.stderrLines.slice(-30)) raw.push(line);
      }
      await conn.dispose();
    }
    if (server) await server.close();
    if (pageRoot) rmSync(pageRoot, { recursive: true, force: true });
  }

  // ---- evidence -----------------------------------------------------------
  // The export assertion is recorded BEFORE the summary is computed, so the
  // printed count matches the number of PASS/FAIL lines a reader can count, and
  // the log file ends with a summary that includes it.
  const exportPath = resolve(opts.logFile).replace(/\.log$/, '') + '.stats.json';
  if (ctx.exportData) {
    writeFileSync(exportPath, JSON.stringify(ctx.exportData, null, 1) + '\n');
    record('stats.exported', statSync(exportPath).size > 0, exportPath);
  }

  const shutdown = async (signal) => {
    if (conn) { try { await conn.dispose(); } catch { /* already gone */ } }
    console.error(`web-demo-verify: ${signal} received; browser disposed`);
    process.exit(2);
  };
  process.on("SIGTERM", () => { shutdown("SIGTERM"); });
  process.on("SIGINT", () => { shutdown("SIGINT"); });

  const failed = results.filter((r) => !r.ok);
  const summary = {
    generatedAt: new Date().toISOString(),
    mode,
    signaling: opts.signaling,
    chrome: chrome?.version ?? null,
    cacheDir: opts.cacheDir,
    passed: results.length - failed.length,
    total: results.length,
    assertions: results,
  };
  if (ctx.exportData) {
    writeFileSync(exportPath, JSON.stringify({ ...ctx.exportData, summary }, null, 1) + '\n');
  }
  raw.push('');
  raw.push(`summary: ${summary.passed}/${summary.total} passed`);
  raw.push(JSON.stringify(summary, null, 2));
  writeFileSync(resolve(opts.logFile), raw.join('\n') + '\n');

  console.log('');
  console.log(`summary: ${summary.passed}/${summary.total} passed`);
  console.log(`raw log : ${resolve(opts.logFile)} (${human(statSync(resolve(opts.logFile)).size)})`);
  if (ctx.exportData) console.log(`export  : ${exportPath}`);
  process.exit(envError ? 2 : (failed.length ? 1 : 0));
}

main();
