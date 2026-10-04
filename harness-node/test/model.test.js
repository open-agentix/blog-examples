import test from 'node:test';
import assert from 'node:assert/strict';
import { OpenAICompatModel, ScriptedModel } from '../src/index.js';

test('ScriptedModel replays its script and repeats the last reply', async () => {
  const m = new ScriptedModel([{ text: 'one' }, { text: 'two' }]);
  assert.equal((await m.next()).text, 'one');
  assert.equal((await m.next()).text, 'two');
  const last = await m.next();
  assert.equal(last.text, 'two');
  assert.deepEqual(last.toolCalls, []);
  assert.equal(last.usage.tokens, 10);
});

const SECRET = 'sk-test-secret-123';
const make = (fetchImpl) => new OpenAICompatModel({ baseUrl: 'http://llm.test/v1/', apiKey: SECRET, model: 'm1', fetchImpl });

test('adapter builds an OpenAI-style request and parses tool calls', async () => {
  let req;
  const fetchImpl = async (url, opts) => {
    req = { url, opts, body: JSON.parse(opts.body) };
    return { ok: true, json: async () => ({ choices: [{ message: { content: 'hi', tool_calls: [{ id: 'c1', function: { name: 'read_file', arguments: '{"path":"notes/a"}' } }, { id: 'c2', function: { name: 'x', arguments: '{oops' } }] } }], usage: { total_tokens: 42 } }) };
  };
  const messages = [
    { role: 'user', content: 'go' },
    { role: 'assistant', content: '', toolCalls: [{ id: 'c1', name: 'read_file', args: { path: 'notes/a' } }] },
    { role: 'tool', toolCallId: 'c1', content: 'data' },
  ];
  const reply = await make(fetchImpl).next(messages, [{ name: 'read_file', description: 'd', schema: { type: 'object' } }], undefined);
  assert.equal(req.url, 'http://llm.test/v1/chat/completions');
  assert.equal(req.opts.headers.authorization, `Bearer ${SECRET}`);
  assert.equal(req.body.model, 'm1');
  assert.equal(req.body.tools[0].function.name, 'read_file');
  assert.equal(req.body.messages[1].tool_calls[0].function.arguments, '{"path":"notes/a"}');
  assert.deepEqual(req.body.messages[2], { role: 'tool', tool_call_id: 'c1', content: 'data' });
  assert.deepEqual(reply.toolCalls[0], { id: 'c1', name: 'read_file', args: { path: 'notes/a' } });
  assert.equal(reply.toolCalls[1].args, null); // unparsable arguments become null and are then denied
  assert.equal(reply.usage.tokens, 42);
  assert.equal(reply.text, 'hi');
});

test('adapter tolerates an empty answer', async () => {
  const reply = await make(async () => ({ ok: true, json: async () => ({}) })).next([], [], undefined);
  assert.deepEqual(reply, { text: '', toolCalls: [], usage: { tokens: 0 } });
});

test('errors never contain the key, and the key is hidden from logging', async () => {
  const m = make(async () => ({ ok: false, status: 401 }));
  await assert.rejects(m.next([], [], undefined), (e) => /HTTP 401/.test(e.message) && !e.message.includes(SECRET));
  assert.ok(!JSON.stringify(m).includes(SECRET));
  assert.ok(!JSON.stringify(Object.entries(m)).includes(SECRET));
});

test('fromEnv needs all three variables', () => {
  assert.throws(() => OpenAICompatModel.fromEnv({}), /HARNESS_BASE_URL/);
  const m = OpenAICompatModel.fromEnv({ HARNESS_BASE_URL: 'http://x/v1', HARNESS_API_KEY: 'k', HARNESS_MODEL: 'm' });
  assert.equal(m.model, 'm');
});
