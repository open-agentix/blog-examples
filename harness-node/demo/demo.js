// Runnable demo: node demo/demo.js   (add --interactive to answer approval prompts yourself)
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline/promises';
import { fileURLToPath } from 'node:url';
import { AuditLog, ScriptedModel, ToolRegistry, httpGetTool, loadPolicy, readFileTool, runAgent, verifyFile } from '../src/index.js';

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
const out = here('./out');
rmSync(out, { recursive: true, force: true });
mkdirSync(out);
const auditFile = `${out}/audit.jsonl`;

const call = (id, name, args) => ({ toolCalls: [{ id, name, args }] });
const model = new ScriptedModel([ // pretends to be a model; no network, no key
  call('1', 'read_file', { path: 'notes/welcome.txt' }),          // allowed
  call('2', 'http_get', { url: 'https://example.org/' }),         // denied: no rule, deny by default
  call('3', 'read_file', { path: 'reports/q3.txt' }),             // approval required
  call('4', 'read_file', { path: 'notes/../../etc/passwd' }),     // denied: path escapes
  { text: 'Done: one read allowed, three calls refused.' },
]);

const tools = new ToolRegistry().register(readFileTool(here('./sandbox'))).register(httpGetTool());
let approve; // default in runAgent: deny everything that needs approval
if (process.argv.includes('--interactive')) {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  approve = async (c) => /^y/i.test(await rl.question(`approve ${c.name} ${JSON.stringify(c.args)}? [y/N] `));
}

const audit = new AuditLog({ file: auditFile });
const result = await runAgent({ model, tools, policy: loadPolicy(here('./policy.json')), audit, approve, task: 'Read my notes and my report.' });

for (const e of audit.entries) {
  if (e.event === 'tool.decision') console.log(`${e.data.effect.padEnd(8)} ${e.data.tool} ${JSON.stringify(e.data.args)}  (${e.data.reason})`);
  if (e.event === 'tool.approval') console.log(`         -> approval ${e.data.granted ? 'granted' : 'not granted'}`);
}
console.log(`\nrun ended: ${result.reason} after ${result.steps} steps; ${result.text}`);
console.log('audit verify:', verifyFile(auditFile));

const tampered = `${out}/audit-tampered.jsonl`; // flip one decision in a copy and verify again
copyFileSync(auditFile, tampered);
writeFileSync(tampered, readFileSync(tampered, 'utf8').replace('"effect":"deny"', '"effect":"allow"'));
console.log('tampered copy:', verifyFile(tampered));
