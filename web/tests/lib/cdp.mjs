// cdp.mjs — minimal Chrome DevTools Protocol client.
//
// Node >= 22 ships a global WebSocket, so the whole driver is dependency-free:
// no puppeteer, no playwright, nothing to install. That keeps `web/**` pure
// static (no build, no node_modules) and makes the verification reproducible.
//
// Export: launch(), connect(), newPage()

import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export async function launch({ bin, args = [], env = {}, startupTimeoutMs = 30000, stderrSink = null }) {
  // Profiles are large-ish and are created/removed constantly; keep them next to
  // the cache (a real volume) instead of the image's small /tmp tmpfs.
  const runtimeBase = env.runtimeDir || tmpdir();
  mkdirSync(runtimeBase, { recursive: true });
  const userDataDir = env.userDataDir || mkdtempSync(join(runtimeBase, 'web-demo-profile-'));
  const child = spawn(bin, [
    '--no-sandbox',
    '--disable-dev-shm-usage',
    '--disable-gpu',
    '--remote-debugging-port=0',
    `--user-data-dir=${userDataDir}`,
    ...args,
  ], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });

  const stderr = [];
  child.stderr.on('data', (d) => { stderr.push(d.toString().trimEnd()); stderrSink?.push(d.toString()); });

  let wsUrl;
  try {
    wsUrl = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no DevTools endpoint within ${startupTimeoutMs}ms\n${stderr.join('\n')}`)), startupTimeoutMs);
      let buf = '';
      child.stderr.on('data', (d) => {
        buf += d.toString();
        const m = buf.match(/DevTools listening on (ws:\/\/\S+)/);
        if (m) { clearTimeout(timer); resolve(m[1]); }
      });
      child.once('exit', (code, signal) => { clearTimeout(timer); reject(new Error(`chrome exited before startup (code=${code} signal=${signal})\n${stderr.join('\n')}`)); });
      child.once('error', (err) => { clearTimeout(timer); reject(err); });
    });
  } catch (err) {
    child.kill('SIGKILL');
    throw err;
  }

  const conn = await connect(wsUrl);
  conn.child = child;
  conn.userDataDir = userDataDir;
  conn.wsUrl = wsUrl;
  conn.stderrLines = stderr;
  conn.dispose = async () => {
    try { conn.ws.close(); } catch { /* already closed */ }
    await new Promise((resolve) => {
      if (child.exitCode !== null || child.signalCode !== null) return resolve();
      const t = setTimeout(() => { child.kill('SIGKILL'); resolve(); }, 3000);
      child.once('exit', () => { clearTimeout(t); resolve(); });
      child.kill('SIGTERM');
    });
    if (!env.userDataDir) rmSync(userDataDir, { recursive: true, force: true });
  };
  return conn;
}

export async function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = () => reject(new Error(`cannot open CDP websocket ${wsUrl}`));
    ws.onclose = () => reject(new Error(`CDP websocket closed during handshake ${wsUrl}`));
  });

  let nextId = 0;
  const pending = new Map();
  const listeners = new Set();
  const consoleLines = [];

  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id !== undefined) {
      const waiter = pending.get(msg.id);
      if (!waiter) return;
      pending.delete(msg.id);
      if (msg.error) waiter.reject(new Error(`CDP ${waiter.method} failed: ${msg.error.message} (${msg.error.code})`));
      else waiter.resolve(msg.result);
      return;
    }
    if (msg.method === 'Runtime.consoleAPICalled') {
      consoleLines.push(`console.${msg.params.type}: ${msg.params.args.map((a) => a.value ?? a.description ?? a.type).join(' ')}`);
    }
    for (const listener of listeners) listener(msg);
  };

  const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
    const id = ++nextId;
    pending.set(id, { resolve, reject, method });
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });

  return {
    send,
    consoleLines,
    listeners,
    close: () => ws.close(),
    waitForEvent(method, timeoutMs = 10000, sessionId) {
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => { listeners.delete(listener); reject(new Error(`timed out waiting for ${method}`)); }, timeoutMs);
        const listener = (msg) => {
          if (msg.method !== method) return;
          if (sessionId && msg.sessionId !== sessionId) return;
          clearTimeout(timer);
          listeners.delete(listener);
          resolve(msg.params);
        };
        listeners.add(listener);
      });
    },
  };
}

export async function newPage(conn, url = 'about:blank') {
  const { targetId } = await conn.send('Target.createTarget', { url });
  const { sessionId } = await conn.send('Target.attachToTarget', { targetId, flatten: true });
  await conn.send('Runtime.enable', {}, sessionId);
  await conn.send('Page.enable', {}, sessionId);

  const page = {
    targetId,
    sessionId,
    send: (method, params) => conn.send(method, params, sessionId),
    consoleLines: () => conn.consoleLines,
    on: (fn) => conn.listeners.add(fn),
    off: (fn) => conn.listeners.delete(fn),
    async evaluate(fn, ...args) {
      const expression = `(${fn.toString()})(${args.map((a) => JSON.stringify(a)).join(',')})`;
      // userGesture:true — the demo page opens its "phone simulator" tab with
      // window.open(), which a gesture-less evaluation lets the browser block.
      // It is harmless for every other evaluation.
      const res = await conn.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true }, sessionId);
      if (res.exceptionDetails) {
        const detail = res.exceptionDetails.exception?.description || res.exceptionDetails.text;
        throw new Error(`page evaluate failed: ${detail}`);
      }
      return res.result.value;
    },
    // t11-amend-1 (F11): a freshly created target is not usable the instant
    // Target.createTarget/attach returns — the document may still be parsing, so
    // an immediate Runtime.evaluate can race the page's own script and throw
    // "Cannot read properties of undefined". Wait for the load to finish first.
    async waitForReady(timeoutMs = 15000) {
      const deadline = Date.now() + timeoutMs;
      let lastError = null;
      while (Date.now() < deadline) {
        try {
          const res = await conn.send('Runtime.evaluate', { expression: 'document.readyState', returnByValue: true }, sessionId);
          if (res.result && res.result.value === 'complete') return true;
        } catch (err) { lastError = err; }
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      throw new Error(`page never reached readyState=complete within ${timeoutMs}ms for ${url}${lastError ? ': ' + lastError.message : ''}`);
    },
    // Wait until a page global exists (used by callers that need their own
    // script to have executed); never a bare TypeError on timeout.
    async waitForGlobal(name, timeoutMs = 15000) {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        try {
          const res = await conn.send('Runtime.evaluate', { expression: `typeof ${name}`, returnByValue: true }, sessionId);
          const kind = res.result && res.result.value;
          if (kind && kind !== 'undefined') return true;
        } catch { /* execution context not created yet */ }
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      throw new Error(`window.${name} never appeared within ${timeoutMs}ms on ${url}`);
    },
    async goto(url) {
      const load = this.waitFor('Page.loadEventFired', 20000);
      await conn.send('Page.navigate', { url }, sessionId);
      await load;
    },
    waitFor(method, timeoutMs) { return conn.waitForEvent(method, timeoutMs, sessionId); },
    async close() { try { await conn.send('Target.closeTarget', { targetId }); } catch { /* already gone */ } },
  };
  if (url && url !== 'about:blank') await page.waitForReady();
  return page;
}
