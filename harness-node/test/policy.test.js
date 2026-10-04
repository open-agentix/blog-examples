import test from 'node:test';
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { decide, loadPolicy } from '../src/index.js';
import { tmp } from '../support/helpers.js';

const policy = {
  rules: [
    { tool: 'read_file', effect: 'allow', args: { path: { prefix: 'notes/' } } },
    { tool: 'read_file', effect: 'approve', args: { path: { prefix: 'reports/' } } },
    { tool: 'http_get', effect: 'allow', args: { url: { hostIn: ['example.org'] } } },
  ],
};
const effect = (tool, args, p = policy) => decide(p, tool, args).effect;

test('allows a call that matches a rule and its constraints', () => {
  assert.equal(effect('read_file', { path: 'notes/a.txt' }), 'allow');
});

test('denies by default: unknown tool, empty policy, missing rules', () => {
  assert.equal(effect('delete_everything', {}), 'deny');
  assert.equal(effect('read_file', { path: 'notes/a' }, { rules: [] }), 'deny');
  assert.equal(effect('read_file', { path: 'notes/a' }, {}), 'deny');
  assert.match(decide(policy, 'x', {}).reason, /deny by default/);
});

test('a default field in the policy cannot turn deny-by-default into allow', () => {
  assert.equal(effect('x', {}, { default: 'allow', rules: [] }), 'deny');
});

test('returns approve for rules that need a human', () => {
  assert.equal(effect('read_file', { path: 'reports/q3.txt' }), 'approve');
});

test('path constraints survive traversal tricks', () => {
  for (const path of ['notes/../reports/q3.txt', '../notes/a.txt', '/etc/passwd', 'notes/../../x', 'notes\0/a', 42, undefined]) {
    assert.notEqual(effect('read_file', { path }), 'allow', String(path));
  }
  assert.equal(effect('read_file', { path: 'notes/./sub/../a.txt' }), 'allow');
});

test('host allowlist: exact host only, http(s) only', () => {
  assert.equal(effect('http_get', { url: 'https://example.org/x' }), 'allow');
  for (const url of ['https://example.org.evil.test/', 'https://evil.test/?h=example.org', 'file:///etc/passwd', 'nonsense', 'https://user@evil.test@example.org.evil.test/']) {
    assert.equal(effect('http_get', { url }), 'deny', url);
  }
});

test('deny beats approve beats allow when several rules match', () => {
  const p = { rules: [
    { tool: 't', effect: 'allow' }, { tool: 't', effect: 'approve' }, { tool: 't', effect: 'deny', reason: 'blocked' },
  ] };
  assert.deepEqual(decide(p, 't', {}), { effect: 'deny', reason: 'blocked' });
  assert.equal(decide({ rules: p.rules.slice(0, 2) }, 't', {}).effect, 'approve');
});

test('unknown constraint types and unknown effects fail closed', () => {
  assert.equal(effect('t', { a: 1 }, { rules: [{ tool: 't', effect: 'allow', args: { a: { magic: true } } }] }), 'deny');
  assert.equal(effect('t', {}, { rules: [{ tool: 't', effect: 'yes please' }] }), 'deny');
});

test('oneOf constraint', () => {
  const p = { rules: [{ tool: 't', effect: 'allow', args: { mode: { oneOf: ['r', 'w'] } } }] };
  assert.equal(effect('t', { mode: 'r' }, p), 'allow');
  assert.equal(effect('t', { mode: 'x' }, p), 'deny');
});

test('loadPolicy reads a file and rejects a malformed one', () => {
  const dir = tmp();
  writeFileSync(join(dir, 'ok.json'), JSON.stringify(policy));
  writeFileSync(join(dir, 'bad.json'), '{}');
  assert.equal(loadPolicy(join(dir, 'ok.json')).rules.length, 3);
  assert.throws(() => loadPolicy(join(dir, 'bad.json')), /rules/);
});
