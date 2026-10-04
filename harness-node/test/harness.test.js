import test from 'node:test';
import assert from 'node:assert/strict';
import { httpGetTool, runAgent } from '../src/index.js';
import { call, setup } from '../support/helpers.js';

const events = (audit) => audit.entries.map((e) => e.event);
const decisions = (audit) => audit.entries.filter((e) => e.event === 'tool.decision').map((e) => e.data.effect);

test('runs the loop: allowed tool call, result fed back, final answer', async () => {
  const ctx = setup([call('1', 'read_file', { path: 'notes/a.txt' }), { text: 'all done' }]);
  const result = await runAgent(ctx);
  assert.equal(result.reason, 'done');
  assert.equal(result.text, 'all done');
  assert.equal(result.steps, 2);
  assert.deepEqual(result.messages.filter((m) => m.role === 'tool').map((m) => m.content), ['hello']);
  assert.deepEqual(events(ctx.audit), ['run.start', 'tool.decision', 'tool.result', 'run.end']);
  assert.equal(ctx.audit.verify().ok, true);
});

test('a denied call never runs the tool and is audited', async () => {
  let ran = false;
  const spy = { ...httpGetTool(), run: async () => { ran = true; return ''; } };
  const ctx = setup([call('1', 'http_get', { url: 'https://example.org/' }), { text: 'ok' }], { extraTools: [spy] });
  const result = await runAgent(ctx);
  assert.equal(ran, false);
  assert.match(result.messages.find((m) => m.role === 'tool').content, /^denied: no matching rule/);
  assert.deepEqual(decisions(ctx.audit), ['deny']);
  assert.ok(!events(ctx.audit).includes('tool.result'));
});

test('approval-required is denied when no approver is given (non-interactive)', async () => {
  const policy = { rules: [{ tool: 'read_file', effect: 'approve' }] };
  const ctx = setup([call('1', 'read_file', { path: 'notes/a.txt' }), { text: 'ok' }], { policy });
  const result = await runAgent(ctx);
  assert.match(result.messages.find((m) => m.role === 'tool').content, /approval not granted/);
  const approval = ctx.audit.entries.find((e) => e.event === 'tool.approval');
  assert.equal(approval.data.granted, false);
});

test('approval granted by an approver lets the call run', async () => {
  const policy = { rules: [{ tool: 'read_file', effect: 'approve' }] };
  const asked = [];
  const approve = async (c, d) => { asked.push([c.name, d.effect]); return true; };
  const ctx = setup([call('1', 'read_file', { path: 'notes/a.txt' }), { text: 'ok' }], { policy });
  const result = await runAgent({ ...ctx, approve });
  assert.deepEqual(asked, [['read_file', 'approve']]);
  assert.equal(result.messages.find((m) => m.role === 'tool').content, 'hello');
});

test('unknown tools and invalid arguments are denied before the policy is consulted', async () => {
  const ctx = setup([
    { toolCalls: [{ id: '1', name: 'rm_rf', args: {} }, { id: '2', name: 'read_file', args: { path: 5 } }, { id: '3', name: 'read_file', args: null }] },
    { text: 'ok' },
  ], { policy: { rules: [{ tool: 'rm_rf', effect: 'allow' }, { tool: 'read_file', effect: 'allow' }] } });
  const result = await runAgent(ctx);
  assert.deepEqual(decisions(ctx.audit), ['deny', 'deny', 'deny']);
  assert.match(result.messages[2].content, /unknown tool/);
  assert.match(result.messages[3].content, /must be string/);
});

test('a tool that throws becomes an error result, not a crash', async () => {
  const ctx = setup([call('1', 'read_file', { path: 'notes/missing.txt' }), { text: 'ok' }]);
  const result = await runAgent(ctx);
  assert.match(result.messages.find((m) => m.role === 'tool').content, /^error: /);
  assert.equal(result.reason, 'done');
});

test('max-steps is a hard stop', async () => {
  const ctx = setup([call('1', 'read_file', { path: 'notes/a.txt' })]); // the script repeats its last reply forever
  const result = await runAgent({ ...ctx, limits: { maxSteps: 3 } });
  assert.equal(result.reason, 'max-steps');
  assert.equal(result.steps, 3);
  assert.equal(ctx.audit.entries.at(-1).data.reason, 'max-steps');
});

test('token budget stops the run before the over-budget reply is acted on', async () => {
  const ctx = setup([{ ...call('1', 'read_file', { path: 'notes/a.txt' }), usage: { tokens: 60 } }]);
  const result = await runAgent({ ...ctx, limits: { maxTokens: 100 } });
  assert.equal(result.reason, 'budget');
  assert.equal(result.steps, 2);
  assert.equal(decisions(ctx.audit).length, 1); // the second reply (120 tokens total) was not executed
});

test('timeout is a hard stop', async () => {
  const slow = { async next() { await new Promise((r) => setTimeout(r, 30)); return { toolCalls: [{ id: '1', name: 'read_file', args: { path: 'notes/a.txt' } }] }; } };
  const result = await runAgent({ ...setup([]), model: slow, limits: { timeoutMs: 10 } });
  assert.equal(result.reason, 'timeout');
});
