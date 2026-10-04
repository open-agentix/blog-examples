import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ToolRegistry, httpGetTool, readFileTool, validate } from '../src/index.js';
import { tmp } from '../support/helpers.js';

const schema = {
  type: 'object',
  properties: { s: { type: 'string', maxLength: 3 }, n: { type: 'number' }, i: { type: 'integer' }, b: { type: 'boolean' }, e: { type: 'string', enum: ['x', 'y'] } },
  required: ['s'],
  additionalProperties: false,
};

test('validate accepts good arguments', () => {
  assert.deepEqual(validate(schema, { s: 'abc', n: 1.5, i: 2, b: true, e: 'x' }), []);
});

test('validate reports each kind of problem', () => {
  assert.deepEqual(validate(schema, {}), ['missing "s"']);
  assert.match(validate(schema, { s: 1 })[0], /must be string/);
  assert.match(validate(schema, { s: 'abcd' })[0], /too long/);
  assert.match(validate(schema, { s: 'a', e: 'z' })[0], /one of/);
  assert.match(validate(schema, { s: 'a', n: NaN })[0], /must be number/);
  assert.match(validate(schema, { s: 'a', i: 1.5 })[0], /must be integer/);
  assert.match(validate(schema, { s: 'a', extra: 1 })[0], /unknown argument/);
  for (const bad of [null, [], 'x']) assert.deepEqual(validate(schema, bad), ['arguments must be an object']);
});

test('registry lists specs without the run function', () => {
  const reg = new ToolRegistry().register(readFileTool(tmp()));
  assert.equal(reg.get('read_file').name, 'read_file');
  assert.equal(reg.get('nope'), undefined);
  assert.deepEqual(Object.keys(reg.specs()[0]), ['name', 'description', 'schema']);
});

test('read_file reads inside the sandbox and refuses to leave it', async () => {
  const root = tmp(); const outside = tmp();
  mkdirSync(join(root, 'notes'));
  writeFileSync(join(root, 'notes', 'a.txt'), 'hello world');
  writeFileSync(join(outside, 'secret.txt'), 'secret');
  symlinkSync(join(outside, 'secret.txt'), join(root, 'notes', 'link.txt'));
  const tool = readFileTool(root, 5);
  assert.equal(await tool.run({ path: 'notes/a.txt' }), 'hello'); // capped at maxBytes
  await assert.rejects(tool.run({ path: '../' + outside.split('/').pop() + '/secret.txt' }), /escapes/);
  await assert.rejects(tool.run({ path: join(outside, 'secret.txt') }), /escapes/);
  await assert.rejects(tool.run({ path: 'notes/link.txt' }), /escapes/);
  await assert.rejects(tool.run({ path: 'notes/missing.txt' }), /ENOENT/);
});

test('http_get uses the injected fetch and caps the body', async () => {
  const seen = [];
  const fetchImpl = async (url, opts) => { seen.push([url, opts.redirect]); return { status: 200, text: async () => 'x'.repeat(100) }; };
  const out = await httpGetTool({ fetchImpl, maxBytes: 10 }).run({ url: 'https://example.org/' });
  assert.equal(out, `200\n${'x'.repeat(10)}`);
  assert.deepEqual(seen, [['https://example.org/', 'error']]);
});
