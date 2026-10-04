import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { AuditLog, verifyFile, verifyEntries, GENESIS } from '../src/index.js';
import { tmp } from '../support/helpers.js';

const fill = (log) => { log.append('a', { n: 1 }); log.append('b', { n: 2 }); log.append('c', { n: 3 }); return log; };

test('entries are chained and verify', () => {
  const log = fill(new AuditLog());
  assert.equal(log.entries[0].prev, GENESIS);
  assert.equal(log.entries[1].prev, log.entries[0].hash);
  assert.deepEqual(log.verify(), { ok: true, count: 3, head: log.head });
});

test('empty log verifies and has the genesis head', () => {
  assert.equal(new AuditLog().verify().ok, true);
  assert.equal(new AuditLog().head, GENESIS);
});

test('hashing does not depend on key order', () => {
  const a = new AuditLog({ clock: () => 't' }); a.append('e', { x: 1, y: 2 });
  const b = new AuditLog({ clock: () => 't' }); b.append('e', { y: 2, x: 1 });
  assert.equal(a.head, b.head);
});

test('tampering with a line in the file is detected', () => {
  const file = join(tmp(), 'audit.jsonl');
  fill(new AuditLog({ file }));
  assert.equal(verifyFile(file).ok, true);
  writeFileSync(file, readFileSync(file, 'utf8').replace('"n":2', '"n":99'));
  const result = verifyFile(file);
  assert.equal(result.ok, false);
  assert.match(result.error, /entry 2: hash mismatch/);
});

test('removing a line is detected', () => {
  const file = join(tmp(), 'audit.jsonl');
  fill(new AuditLog({ file }));
  const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean);
  writeFileSync(file, [lines[0], lines[2]].join('\n') + '\n');
  assert.match(verifyFile(file).error, /unexpected seq/);
});

test('rewriting an entry and its hash still breaks the chain', () => {
  const log = fill(new AuditLog());
  const forged = structuredClone(log.entries);
  forged[0].data.n = 7; // attacker also fixes entry 1, but entry 2 still points at the old hash
  assert.match(verifyEntries([{ ...forged[0], hash: 'x' }, ...forged.slice(1)]).error, /hash mismatch/);
  const relinked = new AuditLog(); relinked.append('a', { n: 7 });
  assert.match(verifyEntries([relinked.entries[0], ...log.entries.slice(1)]).error, /broken chain/);
});

test('invalid JSON is reported', () => {
  const file = join(tmp(), 'audit.jsonl');
  writeFileSync(file, '{not json}\n');
  assert.match(verifyFile(file).error, /line 1: not valid JSON/);
});

test('a log resumes from an existing file and stays valid', () => {
  const file = join(tmp(), 'audit.jsonl');
  fill(new AuditLog({ file }));
  const again = new AuditLog({ file });
  again.append('d');
  assert.equal(verifyFile(file).count, 4);
});

test('a log written by the Node example verifies (the Java example checks the same file)', () => {
  const result = verifyFile(new URL('../../testdata/audit-sample.jsonl', import.meta.url).pathname);
  assert.equal(result.ok, true);
  assert.equal(result.count, 3);
});
