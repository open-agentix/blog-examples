import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { AuditLog, ScriptedModel, ToolRegistry, readFileTool } from '../src/index.js';

export const tmp = () => mkdtempSync(join(tmpdir(), 'harness-'));

export const call = (id, name, args) => ({ toolCalls: [{ id, name, args }] });

export function setup(script, { policy, extraTools = [] } = {}) {
  const root = tmp();
  mkdirSync(join(root, 'notes'));
  writeFileSync(join(root, 'notes', 'a.txt'), 'hello');
  const tools = new ToolRegistry().register(readFileTool(root));
  extraTools.forEach((t) => tools.register(t));
  return {
    root,
    tools,
    model: new ScriptedModel(script),
    audit: new AuditLog({ clock: () => '2026-01-01T00:00:00.000Z' }),
    policy: policy ?? { rules: [{ tool: 'read_file', effect: 'allow', args: { path: { prefix: 'notes/' } } }] },
    task: 'test',
  };
}
