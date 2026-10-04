// Pluggable model interface: next(messages, toolSpecs, signal) -> { text, toolCalls, usage }.

/** Deterministic model for demos and tests: replays a script, one reply per call. No network. */
export class ScriptedModel {
  constructor(script) { this.script = script; this.i = 0; }
  async next() {
    const reply = this.script[Math.min(this.i++, this.script.length - 1)];
    return { text: '', toolCalls: [], usage: { tokens: 10 }, ...reply };
  }
}

/**
 * Optional adapter for an OpenAI-compatible /chat/completions endpoint.
 * Config comes from the environment; the key is only put in the Authorization header and is never logged.
 */
export class OpenAICompatModel {
  constructor({ baseUrl, apiKey, model, fetchImpl = fetch }) {
    Object.assign(this, { baseUrl: baseUrl.replace(/\/$/, ''), model, fetchImpl });
    this.apiKey = apiKey;
    Object.defineProperty(this, 'apiKey', { enumerable: false }); // keeps it out of console.log / JSON
  }

  static fromEnv(env = process.env) {
    const { HARNESS_BASE_URL: baseUrl, HARNESS_API_KEY: apiKey, HARNESS_MODEL: model } = env;
    if (!baseUrl || !apiKey || !model) throw new Error('set HARNESS_BASE_URL, HARNESS_API_KEY and HARNESS_MODEL');
    return new OpenAICompatModel({ baseUrl, apiKey, model });
  }

  async next(messages, toolSpecs, signal) {
    const res = await this.fetchImpl(`${this.baseUrl}/chat/completions`, {
      method: 'POST',
      signal,
      headers: { 'content-type': 'application/json', authorization: `Bearer ${this.apiKey}` },
      body: JSON.stringify({
        model: this.model,
        messages: messages.map(toWire),
        tools: toolSpecs.map((t) => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.schema } })),
      }),
    });
    if (!res.ok) throw new Error(`model endpoint returned HTTP ${res.status}`); // never echo headers or body
    const body = await res.json();
    const msg = body.choices?.[0]?.message ?? {};
    return {
      text: msg.content ?? '',
      toolCalls: (msg.tool_calls ?? []).map((c) => ({ id: c.id, name: c.function.name, args: safeJson(c.function.arguments) })),
      usage: { tokens: body.usage?.total_tokens ?? 0 },
    };
  }
}

const safeJson = (s) => { try { return JSON.parse(s); } catch { return null; } }; // null fails validation, so it is denied

const toWire = (m) =>
  m.role === 'tool' ? { role: 'tool', tool_call_id: m.toolCallId, content: m.content }
  : m.toolCalls?.length ? { role: 'assistant', content: m.content, tool_calls: m.toolCalls.map((c) => ({ id: c.id, type: 'function', function: { name: c.name, arguments: JSON.stringify(c.args) } })) }
  : { role: m.role, content: m.content };
