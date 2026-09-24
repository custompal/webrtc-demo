// deb.mjs — rootless runtime-library provisioning for chrome-headless-shell.
//
// The container runs without root and without any system package manager index,
// so nothing can be installed system-wide; it does have `dpkg-deb`, network
// access and node. So: fetch the Debian `Packages` index, resolve the transitive
// dependency closure of a seed set, stream the .deb files into a cache and unpack
// them into a private sysroot (no package manager, no root, host untouched).
// chrome then runs with LD_LIBRARY_PATH pointing at that sysroot.
//
// Export: ensureRuntimeLibs({cacheDir, log}) -> { libPath, missing, packages }

import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import { createWriteStream, createReadStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { join } from 'node:path';
import { readdirSync } from 'node:fs';

const execFileAsync = promisify(execFile);

export const DEBIAN_SUITE = 'bookworm';
export const DEBIAN_MIRROR = 'https://deb.debian.org/debian';

// Sonames chrome-headless-shell 154 needs and this image does not ship
// (measured with `ldd`: 20 sonames). The closure below is what satisfies them.
export const SEED_PACKAGES = [
  'libnss3', 'libnspr4', 'libatk1.0-0', 'libatk-bridge2.0-0', 'libatspi2.0-0',
  'libasound2', 'libgbm1', 'libglib2.0-0', 'libdbus-1-3', 'libx11-6', 'libxcb1',
  'libxcomposite1', 'libxdamage1', 'libxext6', 'libxfixes3', 'libxrandr2',
  'libxkbcommon0', 'fonts-liberation',
];

// Never taken from the archive: the host already provides them and shadowing
// the loader/glibc through LD_LIBRARY_PATH is unsafe.
export const DENY_PACKAGES = new Set([
  'libc6', 'libc-bin', 'libc-dev-bin', 'libgcc-s1', 'libgcc1', 'libstdc++6', 'gcc-12-base',
  'base-files', 'libcrypt1', 'libcrypt-dev', 'debconf', 'dpkg', 'perl-base', 'libselinux1',
  'libsemanage2', 'libsemanage-common', 'libaudit1', 'libcap-ng0', 'libpam0g', 'libpam-modules',
  'libtinfo6', 'libudev1', 'sysvinit-utils', 'tzdata', 'libuuid1', 'libblkid1', 'libmount1',
  'libsmartcols1', 'libfdisk1', 'login', 'passwd', 'adduser', 'libacl1', 'libattr1', 'libbz2-1.0',
  'liblzma5', 'libzstd1', 'zlib1g', 'libpcre2-8-0', 'libffi8', 'libseccomp2',
  'libdebconfclient0', 'libmd0', 'libbsd0', 'libelf1', 'libcap2', 'libgcrypt20', 'liblz4-1',
  'libsystemd0', 'libgpg-error0', 'libidn2-0', 'libunistring2', 'libtasn1-6', 'libp11-kit0',
  'libhogweed6', 'libnettle8', 'libgmp10', 'libgnutls30', 'libsasl2-2', 'libldap-2.5-0',
  'liblber-2.5-0', 'libkrb5-3', 'libk5crypto3', 'libkrb5support0', 'libkeyutils1', 'libcom-err2',
  'libgssapi-krb5-2', 'libsasl2-modules-db', 'libjson-c5', 'libpsl5', 'libssh2-1', 'librtmp1',
]);

async function download(url, dest, log) {
  if (existsSync(dest) && statSync(dest).size > 0) return { dest, cached: true };
  log(`  fetch ${url}`);
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  mkdirSync(join(dest, '..'), { recursive: true });
  await pipeline(res.body, createWriteStream(dest));
  return { dest, cached: false };
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (true) {
      const i = next++;
      if (i >= items.length) return;
      out[i] = await fn(items[i], i);
    }
  });
  await Promise.all(workers);
  return out;
}

export function parsePackagesIndex(text) {
  const index = new Map();
  for (const block of text.split('\n\n')) {
    if (!block.trim()) continue;
    let name = null, version = null, file = null, depends = '';
    for (const line of block.split('\n')) {
      if (line.startsWith('Package: ')) name = line.slice(9).trim();
      else if (line.startsWith('Version: ')) version = line.slice(9).trim();
      else if (line.startsWith('Filename: ')) file = line.slice(10).trim();
      else if (line.startsWith('Depends: ')) depends = line.slice(9).trim();
    }
    if (name && file) index.set(name, { name, version, file, depends });
  }
  return index;
}

export function resolveClosure(index, seeds, deny = DENY_PACKAGES) {
  const closure = new Map();
  const unresolved = [];
  const queue = [...seeds];
  while (queue.length) {
    const name = queue.shift();
    if (closure.has(name) || deny.has(name)) continue;
    const pkg = index.get(name);
    if (!pkg) { unresolved.push(name); continue; }
    closure.set(name, pkg);
    for (const rawDep of pkg.depends.split(',')) {
      const alts = rawDep.split('|').map((s) => s.trim()).filter(Boolean);
      if (!alts.length) continue;
      let chosen = null;
      for (const alt of alts) {
        const n = alt.replace(/\s*\(.*$/, '').replace(/:.*$/, '').trim();
        if (!n) continue;
        if (closure.has(n) || deny.has(n) || index.has(n)) { chosen = n; break; }
      }
      if (chosen && !closure.has(chosen) && !deny.has(chosen)) queue.push(chosen);
    }
  }
  return { closure, unresolved };
}

export async function ensureRuntimeLibs({ cacheDir, log = console.log }) {
  const sysroot = join(cacheDir, 'sysroot');
  const debDir = join(cacheDir, 'deb');
  const marker = join(sysroot, '.complete');

  if (existsSync(marker)) {
    const libPath = libPathOf(sysroot);
    log(`runtime libraries: cache hit ${sysroot}`);
    return { libPath, sysroot, packages: null, cached: true };
  }

  const indexPath = join(cacheDir, 'dl', `Packages-${DEBIAN_SUITE}.gz`);
  log(`runtime libraries: building sysroot in ${sysroot}`);
  await download(`${DEBIAN_MIRROR}/dists/${DEBIAN_SUITE}/main/binary-amd64/Packages.gz`, indexPath, log);
  const index = parsePackagesIndex(gunzipSync(readFileSync(indexPath)).toString('utf8'));
  if (index.size < 1000) throw new Error(`Debian Packages index looks truncated (${index.size} entries)`);

  const { closure, unresolved } = resolveClosure(index, SEED_PACKAGES);
  if (unresolved.length) throw new Error(`unresolved package names: ${unresolved.join(', ')}`);
  const plan = [...closure.values()];
  log(`  dependency closure: ${plan.length} packages`);

  await mapLimit(plan, 4, async (pkg) => {
    const dest = join(debDir, pkg.file.split('/').pop());
    await download(`${DEBIAN_MIRROR}/${pkg.file}`, dest, () => {});
    return dest;
  });

  mkdirSync(sysroot, { recursive: true });
  for (const entry of readdirSync(debDir).filter((f) => f.endsWith('.deb')).sort()) {
    await execFileAsync('dpkg-deb', ['-x', join(debDir, entry), sysroot], { maxBuffer: 1 << 26 });
  }
  writeFileSync(marker, new Date().toISOString() + '\n');
  const libPath = libPathOf(sysroot);
  log(`  sysroot ready: ${sysroot}`);
  return { libPath, sysroot, packages: plan.length, cached: false };
}

export function libPathOf(sysroot) {
  return [
    join(sysroot, 'usr/lib/x86_64-linux-gnu'),
    join(sysroot, 'lib/x86_64-linux-gnu'),
    join(sysroot, 'usr/lib'),
  ].join(':');
}
