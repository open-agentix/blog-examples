// Append-only, hash-chained audit log. Each entry carries the hash of the previous one,
// so editing or removing any earlier line breaks every hash after it.
import { createHash } from 'node:crypto';
import { appendFileSync, existsSync, readFileSync } from 'node:fs';

export const GENESIS = '0'.repeat(64);

// JSON with sorted keys, so the same entry always hashes the same way.
export const canonical = (value) =>
  JSON.stringify(value, (_, v) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
      : v);

const digest = ({ hash, ...body }) => createHash('sha256').update(canonical(body)).digest('hex');

/** Check a list of entries. Returns { ok, count } or { ok: false, error }. */
export function verifyEntries(entries) {
  let prev = GENESIS;
  for (const [i, e] of entries.entries()) {
    if (e.seq !== i + 1) return { ok: false, error: `entry ${i + 1}: unexpected seq ${e.seq}` };
    if (e.prev !== prev) return { ok: false, error: `entry ${e.seq}: broken chain (prev mismatch)` };
    if (e.hash !== digest(e)) return { ok: false, error: `entry ${e.seq}: hash mismatch (content changed)` };
    prev = e.hash;
  }
  return { ok: true, count: entries.length, head: prev };
}

/** Read a JSON-lines audit file and verify it. */
export function verifyFile(file) {
  const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean);
  const entries = [];
  for (const [i, line] of lines.entries()) {
    try { entries.push(JSON.parse(line)); } catch { return { ok: false, error: `line ${i + 1}: not valid JSON` }; }
  }
  return verifyEntries(entries);
}

export class AuditLog {
  constructor({ file, clock = () => new Date().toISOString() } = {}) {
    this.file = file;
    this.clock = clock;
    this.entries = file && existsSync(file)
      ? readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
      : [];
  }

  get head() { return this.entries.at(-1)?.hash ?? GENESIS; }

  append(event, data = {}) {
    const entry = { seq: this.entries.length + 1, ts: this.clock(), event, data, prev: this.head };
    entry.hash = digest(entry);
    this.entries.push(entry);
    if (this.file) appendFileSync(this.file, JSON.stringify(entry) + '\n');
    return entry;
  }

  verify() { return verifyEntries(this.entries); }
}
