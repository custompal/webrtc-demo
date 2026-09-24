// zip.mjs — dependency-free ZIP reader (deflate + stored) for the Chrome
// for Testing archives. The container has no `unzip`, no python3 and no `ar`,
// so extraction is done with node's zlib and the central directory.
//
// Usage:  node zip.mjs <archive.zip> <outDir>
// Export: extractZip(zipPath, outDir) -> { files, bytes }

import { readFileSync, writeFileSync, mkdirSync, chmodSync, rmSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';
import { dirname, join, resolve } from 'node:path';

const EOCD = 0x06054b50;
const CEN = 0x02014b50;
const EOCD64 = 0x06064b50;

export function extractZip(zipPath, outDir) {
  const buf = readFileSync(zipPath);
  let eocd = -1;
  for (let i = buf.length - 22; i >= 0 && i > buf.length - 66000; i--) {
    if (buf.readUInt32LE(i) === EOCD) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`not a zip archive (no end-of-central-directory): ${zipPath}`);

  let count = buf.readUInt16LE(eocd + 10);
  let cdOff = buf.readUInt32LE(eocd + 16);

  // zip64: 0xffffffff sentinels -> read the zip64 EOCD locator/record.
  if (count === 0xffff || cdOff === 0xffffffff) {
    const loc = eocd - 20;
    if (buf.readUInt32LE(loc) !== 0x07064b50) throw new Error('zip64 archive without a locator record');
    const z64 = Number(buf.readBigUInt64LE(loc + 8));
    if (buf.readUInt32LE(z64) !== EOCD64) throw new Error('bad zip64 EOCD record');
    count = Number(buf.readBigUInt64LE(z64 + 32));
    cdOff = Number(buf.readBigUInt64LE(z64 + 48));
  }

  outDir = resolve(outDir);
  let off = cdOff;
  let files = 0;
  let bytes = 0;
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(off) !== CEN) throw new Error(`bad central directory entry at offset ${off}`);
    const method = buf.readUInt16LE(off + 10);
    const csize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const extAttr = buf.readUInt32LE(off + 38);
    const lho = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    off += 46 + nameLen + extraLen + commentLen;

    if (name.endsWith('/')) { mkdirSync(join(outDir, name), { recursive: true }); continue; }
    if (name.includes('..')) throw new Error(`refusing archive entry escaping the target: ${name}`);

    const lnameLen = buf.readUInt16LE(lho + 26);
    const lextraLen = buf.readUInt16LE(lho + 28);
    const dataStart = lho + 30 + lnameLen + lextraLen;
    const raw = buf.subarray(dataStart, dataStart + csize);
    let data;
    if (method === 0) data = raw;
    else if (method === 8) data = inflateRawSync(raw);
    else throw new Error(`unsupported zip compression method ${method} for ${name}`);

    const dest = join(outDir, name);
    mkdirSync(dirname(dest), { recursive: true });
    writeFileSync(dest, data);
    const mode = (extAttr >>> 16) & 0xffff;
    if (mode & 0o111) chmodSync(dest, mode & 0o777); // chrome-headless-shell must stay executable
    files++;
    bytes += data.length;
  }
  return { files, bytes };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [zipPath, outDir] = process.argv.slice(2);
  if (!zipPath || !outDir) { console.error('usage: node zip.mjs <archive.zip> <outDir>'); process.exit(2); }
  rmSync(outDir, { recursive: true, force: true });
  const r = extractZip(zipPath, outDir);
  console.log(`extracted ${r.files} files (${(r.bytes / 1048576).toFixed(1)} MiB) -> ${outDir}`);
}
