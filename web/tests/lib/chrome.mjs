// chrome.mjs — self-bootstrapping headless Chrome for automated verification.
//
// Nothing is installed on the host: the browser and the runtime libraries it
// needs land in a cache directory outside the repository, and the script only
// ever runs them out of that cache. Repeatable, non-interactive, --clean-able.
//
// Export: ensureChrome({cacheDir, version, log}) -> { bin, libPath, env, version }

import { mkdirSync, existsSync, readdirSync, statSync, rmSync, writeFileSync, readFileSync, statfsSync } from 'node:fs';
import { createWriteStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { extractZip } from './zip.mjs';
import { ensureRuntimeLibs, libPathOf } from './deb.mjs';

const execFileAsync = promisify(execFile);

// Pinned: the version this environment was verified against (Chrome for
// Testing 154.0.8037.57, glibc 2.36 host). Override with WEB_DEMO_CHROME_VERSION.
export const PINNED_CHROME_VERSION = '154.0.8037.57';
export const CFT_BASE = 'https://storage.googleapis.com/chrome-for-testing-public';

// Bootstrapping writes ~426 MiB (114.9 MiB zip + 261.4 MiB unpacked browser +
// ~40 MiB of .deb archives/sysroot). A cache directory on a small tmpfs (this
// image has a 256 MiB /tmp) would otherwise fail halfway with a bare ENOSPC.
export const MIN_FREE_BYTES = 1200 * 1024 * 1024;

export function freeBytesAt(dir) {
  try {
    const s = statfsSync(dir);
    return Number(s.bavail) * Number(s.bsize);
  } catch {
    return null;
  }
}

export function cacheDirFromEnv() {
  // WEB_DEMO_CACHE names the cache directory itself. The default must never be
  // under ${TMPDIR:-/tmp} (this image's /tmp is a 256 MiB tmpfs while a
  // bootstrap needs ~426 MiB, t8-amend-7). Prefer the per-user cache directory,
  // but verify it is writable: some sandboxes only allow writes under the
  // workspace, in which case fall back to the repository's parent.
  if (process.env.WEB_DEMO_CACHE) return process.env.WEB_DEMO_CACHE;
  const base = process.env.XDG_CACHE_HOME || (process.env.HOME ? join(process.env.HOME, '.cache') : null);
  const repoParent = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
  const fallback = join(repoParent, 'web-demo-chrome-cache');
  if (base) {
    const candidate = join(base, 'web-demo-chrome-cache');
    try { mkdirSync(candidate, { recursive: true }); return candidate; } catch { /* not writable here */ }
  }
  return fallback;
}

export function dirSizeBytes(dir) {
  if (!existsSync(dir)) return 0;
  let total = 0;
  const stack = [dir];
  while (stack.length) {
    const cur = stack.pop();
    for (const entry of readdirSync(cur, { withFileTypes: true })) {
      const p = join(cur, entry.name);
      if (entry.isDirectory()) stack.push(p);
      else if (entry.isFile()) total += statSync(p).size;
    }
  }
  return total;
}

export function human(bytes) {
  const units = ['B', 'KiB', 'MiB', 'GiB'];
  let v = bytes, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(1)} ${units[i]}`;
}

export async function downloadFile(url, dest, log) {
  if (existsSync(dest) && statSync(dest).size > 0) {
    log(`  cached  ${dest} (${human(statSync(dest).size)})`);
    return dest;
  }
  mkdirSync(dirname(dest), { recursive: true });
  log(`  fetch   ${url}`);
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText} for ${url}`);
  const total = Number(res.headers.get('content-length') || 0);
  await pipeline(res.body, createWriteStream(dest));
  const got = statSync(dest).size;
  if (total && got !== total) throw new Error(`short download: got ${got}/${total} bytes for ${url}`);
  log(`  saved   ${dest} (${human(got)})`);
  return dest;
}

/** Sonames chrome cannot resolve with the libraries present in this image. */
export async function missingSonames(bin, libPath) {
  let out = '';
  try {
    ({ stdout: out } = await execFileAsync('ldd', [bin], { env: { ...process.env, LD_LIBRARY_PATH: libPath || '' } }));
  } catch (err) {
    out = `${err.stdout || ''}${err.stderr || ''}`;
  }
  return [...new Set(out.split('\n').filter((l) => l.includes('not found')).map((l) => l.trim().split(/\s+/)[0]))];
}

export async function ensureChrome({ cacheDir = cacheDirFromEnv(), version = process.env.WEB_DEMO_CHROME_VERSION || PINNED_CHROME_VERSION, log = console.log } = {}) {
  const bin = join(cacheDir, 'chrome-headless-shell-linux64', 'chrome-headless-shell');
  const zips = join(cacheDir, 'dl');

  if (!existsSync(bin)) {
    mkdirSync(cacheDir, { recursive: true });
    const free = freeBytesAt(cacheDir);
    if (free !== null && free < MIN_FREE_BYTES) {
      const err = new Error(
        `cache directory ${cacheDir} has only ${human(free)} free, but bootstrapping needs about ${human(MIN_FREE_BYTES)} ` +
        `(114.9 MiB browser zip + 261.4 MiB unpacked + ~40 MiB runtime libraries). ` +
        `Point the cache at a volume with more room, e.g. WEB_DEMO_CACHE=/data/dsh/home/workspace/tmp/web-demo-chrome-cache ` +
        `(note: /tmp in this image is a ${human(freeBytesAt('/tmp') ?? 0)} tmpfs, so it cannot hold the cache).`
      );
      err.envError = true;
      throw err;
    }
    log(`chrome: bootstrapping ${version} into ${cacheDir} (free ${free === null ? 'unknown' : human(free)})`);
    const zipDest = join(zips, `chrome-headless-shell-${version}-linux64.zip`);
    await downloadFile(`${CFT_BASE}/${version}/linux64/chrome-headless-shell-linux64.zip`, zipDest, log);
    const r = extractZip(zipDest, cacheDir);
    if (!existsSync(bin)) throw new Error(`archive did not contain chrome-headless-shell (extracted ${r.files} files)`);
    writeFileSync(join(cacheDir, 'chrome-version.txt'), version + '\n');
  } else {
    log(`chrome: cache hit ${bin} (${human(dirSizeBytes(join(cacheDir, 'chrome-headless-shell-linux64')))})`);
  }

  const sysroot = join(cacheDir, 'sysroot');
  let libPath = libPathOf(sysroot);
  let missing = await missingSonames(bin, libPath);
  if (missing.length) {
    log(`chrome: ${missing.length} unresolved soname(s): ${missing.join(' ')}`);
    ({ libPath } = await ensureRuntimeLibs({ cacheDir, log }));
    missing = await missingSonames(bin, libPath);
    if (missing.length) throw new Error(`still unresolved after provisioning: ${missing.join(' ')}`);
  } else {
    log(`chrome: runtime libraries already resolvable via ${sysroot}`);
  }

  const env = { LD_LIBRARY_PATH: libPath, XDG_DATA_DIRS: join(sysroot, 'usr/share') + (process.env.XDG_DATA_DIRS ? ':' + process.env.XDG_DATA_DIRS : ':') };
  return { bin, libPath, env, version, cacheDir };
}

export async function chromeVersion(bin, env) {
  const { stdout } = await execFileAsync(bin, ['--version'], { env: { ...process.env, ...env } });
  return stdout.trim();
}

export function cleanCache(cacheDir) {
  if (!existsSync(cacheDir)) return 0;
  const size = dirSizeBytes(cacheDir);
  rmSync(cacheDir, { recursive: true, force: true });
  return size;
}
