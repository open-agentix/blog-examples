// The policy gate: a plain, deterministic function. No model, no I/O, no randomness.
// Deny by default: a call is allowed only if a rule says so and all its argument constraints hold.
import { readFileSync } from 'node:fs';
import { posix } from 'node:path';

// Normalise a relative path; null if it is absolute or climbs out ("..").
const safeRel = (p) => {
  if (typeof p !== 'string' || p.includes('\0') || p.startsWith('/')) return null;
  const n = posix.normalize(p);
  return n === '..' || n.startsWith('../') ? null : n;
};

const CONSTRAINTS = {
  prefix: (v, want) => safeRel(v)?.startsWith(want) ?? false,
  oneOf: (v, want) => want.includes(v),
  hostIn: (v, want) => {
    try { const u = new URL(v); return /^https?:$/.test(u.protocol) && want.includes(u.hostname); } catch { return false; }
  },
};

// Unknown constraint types fail closed.
const matches = (constraints = {}, args) =>
  Object.entries(constraints).every(([arg, spec]) =>
    Object.entries(spec).every(([kind, want]) => CONSTRAINTS[kind]?.(args[arg], want) ?? false));

/** @returns {{effect: 'allow'|'deny'|'approve', reason: string}} */
export function decide(policy, tool, args) {
  const hits = (policy.rules ?? []).filter((r) => r.tool === tool && matches(r.args, args));
  for (const effect of ['deny', 'approve', 'allow']) { // strictest effect wins
    const rule = hits.find((r) => r.effect === effect);
    if (rule) return { effect, reason: rule.reason ?? `rule: ${effect} ${tool}` };
  }
  return { effect: 'deny', reason: 'no matching rule (deny by default)' };
}

export function loadPolicy(file) {
  const policy = JSON.parse(readFileSync(file, 'utf8'));
  if (!Array.isArray(policy.rules)) throw new Error('policy file needs a "rules" array');
  return policy;
}
