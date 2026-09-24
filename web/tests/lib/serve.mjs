// serve.mjs — static file server for the browser demo (no dependencies).
//
// The demo page must be opened from `http://localhost` because that origin is a
// *secure context* (getUserMedia works without TLS) while the page itself is
// plain static files. VSCode Remote-SSH forwards this port to the laptop.
//
// CLI:    node serve.mjs --root <dir> --port 8081 [--host 127.0.0.1] [--quiet]
// Export: startServer({root, port, host}) -> { url, port, close() }

import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';

export const DEFAULT_PORT = 8081;
export const DEFAULT_HOST = '127.0.0.1';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

export function createStaticHandler(root) {
  const rootAbs = resolve(root);
  return async (req, res) => {
    const started = Date.now();
    let status = 200;
    let note = '';
    try {
      const url = new URL(req.url, 'http://localhost');
      if (req.method !== 'GET' && req.method !== 'HEAD') {
        status = 405;
        res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', allow: 'GET, HEAD' }).end('method not allowed');
        return;
      }
      let rel = normalize(decodeURIComponent(url.pathname)).replace(/^(\.\.[/\\])+/, '');
      if (rel.includes('..')) { status = 403; res.writeHead(status).end('forbidden'); return; }
      let file = join(rootAbs, rel);
      if (!file.startsWith(rootAbs)) { status = 403; res.writeHead(status).end('forbidden'); return; }
      try {
        const st = await stat(file);
        if (st.isDirectory()) {
          file = join(file, 'index.html');
          note = ' (index)';
        }
      } catch {
        status = 404;
        res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' }).end(`404 not found: ${url.pathname}`);
        return;
      }
      let body;
      try {
        body = await readFile(file);
      } catch (err) {
        if (err.code === 'ENOENT' || err.code === 'EISDIR') {
          status = 404;
          res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' }).end(`404 not found: ${url.pathname}`);
          return;
        }
        throw err;
      }
      res.writeHead(status, {
        'content-type': MIME[extname(file)] || 'application/octet-stream',
        'content-length': body.length,
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff',
      });
      if (req.method === 'HEAD') res.end(); else res.end(body);
    } catch (err) {
      status = 500;
      res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8' }).end(`500 ${err.message}`);
    } finally {
      const stamp = new Date().toISOString();
      console.log(`${stamp} ${req.socket.remoteAddress} ${req.method} ${req.url} -> ${status}${note} ${Date.now() - started}ms`);
    }
  };
}

export async function startServer({ root, port = DEFAULT_PORT, host = DEFAULT_HOST, quiet = false } = {}) {
  const server = createServer(createStaticHandler(root));
  server.on('clientError', (err, socket) => socket.end('HTTP/1.1 400 Bad Request\r\n\r\n'));
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, resolve);
  });
  const actualPort = server.address().port;
  const url = `http://localhost:${actualPort}/`;
  if (!quiet) console.log(`serving ${resolve(root)} on ${url} (bind ${host}:${actualPort})`);
  return {
    url,
    port: actualPort,
    host,
    server,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

function parseArgs(argv) {
  const out = { root: process.cwd(), port: DEFAULT_PORT, host: DEFAULT_HOST, quiet: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--root') out.root = argv[++i];
    else if (arg === '--port') out.port = Number(argv[++i]);
    else if (arg === '--host') out.host = argv[++i];
    else if (arg === '--quiet') out.quiet = true;
    else if (arg === '--help' || arg === '-h') { console.log('usage: node serve.mjs --root <dir> --port 8081 [--host 127.0.0.1] [--quiet]'); process.exit(0); }
    else { console.error(`unknown argument: ${arg}`); process.exit(2); }
  }
  return out;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const opts = parseArgs(process.argv.slice(2));
  const handle = await startServer(opts);
  const shutdown = async () => { await handle.close(); process.exit(0); };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
