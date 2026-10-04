// The agent loop: messages -> model -> tool calls -> results -> repeat, with hard limits.
import { decide } from './policy.js';
import { validate } from './tools.js';

const denyAll = async () => false; // default approver: non-interactive, so approval-required means denied

/**
 * @param model   { next(messages, toolSpecs, signal) -> { text, toolCalls: [{id,name,args}], usage: {tokens} } }
 * @param limits  { maxSteps, maxTokens, timeoutMs }: each one is a hard stop
 * @param approve async (call, decision) => boolean, asked only for "approve" decisions
 */
export async function runAgent({ model, tools, policy, audit, task, approve = denyAll, limits = {} }) {
  const { maxSteps = 8, maxTokens = 10_000, timeoutMs = 30_000 } = limits;
  const deadline = Date.now() + timeoutMs;
  const messages = [{ role: 'user', content: task }];
  let tokens = 0;
  let step = 0;

  const finish = (reason, text = '') => {
    audit.append('run.end', { reason, steps: step, tokens });
    return { reason, text, steps: step, tokens, messages };
  };

  async function execute(call) {
    const tool = tools.get(call.name);
    const errors = tool ? validate(tool.schema, call.args) : ['unknown tool'];
    const decision = errors.length ? { effect: 'deny', reason: errors.join('; ') } : decide(policy, call.name, call.args);
    audit.append('tool.decision', { tool: call.name, args: call.args, ...decision });
    let granted = decision.effect === 'allow';
    if (decision.effect === 'approve') {
      granted = await approve(call, decision);
      audit.append('tool.approval', { tool: call.name, granted });
    }
    let content;
    if (!granted) content = `denied: ${decision.effect === 'approve' ? 'approval not granted' : decision.reason}`;
    else {
      try { content = String(await tool.run(call.args)); } catch (err) { content = `error: ${err.message}`; }
      audit.append('tool.result', { tool: call.name, bytes: content.length });
    }
    return { role: 'tool', toolCallId: call.id, content };
  }

  audit.append('run.start', { task, limits: { maxSteps, maxTokens, timeoutMs } });
  while (step < maxSteps) {
    const left = deadline - Date.now();
    if (left <= 0) return finish('timeout');
    step++;
    const reply = await model.next(messages, tools.specs(), AbortSignal.timeout(left));
    tokens += reply.usage?.tokens ?? 0;
    if (tokens > maxTokens) return finish('budget'); // stop before acting on an over-budget reply
    messages.push({ role: 'assistant', content: reply.text ?? '', toolCalls: reply.toolCalls ?? [] });
    if (!reply.toolCalls?.length) return finish('done', reply.text);
    for (const call of reply.toolCalls) messages.push(await execute(call));
  }
  return finish('max-steps');
}
