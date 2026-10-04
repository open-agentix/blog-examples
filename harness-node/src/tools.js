// Tool registry with JSON-schema-ish argument validation, plus two demo tools.
import { readFileSync, realpathSync } from 'node:fs';
import { relative, resolve, isAbsolute } from 'node:path';

const TYPES = {
  string: (v) => typeof v === 'string',
  number: (v) => typeof v === 'number' && Number.isFinite(v),
  integer: Number.isInteger,
  boolean: (v) => typeof v === 'boolean',
};

/** Supports: type, required, enum, maxLength, additionalProperties:false. Returns a list of errors. */
export function validate(schema, args) {
  if (args === null || typeof args !== 'object' || Array.isArray(args)) return ['arguments must be an object'];
  const errors = [];
  for (const key of schema.required ?? []) if (!(key in args)) errors.push(`missing "${key}"`);
  for (const [key, value] of Object.entries(args)) {
    const spec = schema.properties?.[key];
    if (!spec) { if (schema.additionalProperties === false) errors.push(`unknown argument "${key}"`); continue; }
    if (!TYPES[spec.type]?.(value)) errors.push(`"${key}" must be ${spec.type}`);
    else if (spec.enum && !spec.enum.includes(value)) errors.push(`"${key}" must be one of ${spec.enum}`);
    else if (spec.maxLength && value.length > spec.maxLength) errors.push(`"${key}" is too long`);
  }
  return errors;
}

export class ToolRegistry {
  #tools = new Map();
  register(tool) { this.#tools.set(tool.name, tool); return this; }
  get(name) { return this.#tools.get(name); }
  specs() { return [...this.#tools.values()].map(({ name, description, schema }) => ({ name, description, schema })); }
}

/** read_file: reads a UTF-8 file, but only inside `root` (symlinks resolved, size capped). */
export const readFileTool = (root, maxBytes = 65536) => ({
  name: 'read_file',
  description: 'Read a text file from the sandbox directory.',
  schema: { type: 'object', properties: { path: { type: 'string', maxLength: 256 } }, required: ['path'], additionalProperties: false },
  async run({ path }) {
    const base = realpathSync(root);
    const full = realpathSync(resolve(base, path));
    const rel = relative(base, full);
    if (rel.startsWith('..') || isAbsolute(rel)) throw new Error('path escapes the sandbox');
    return readFileSync(full, 'utf8').slice(0, maxBytes);
  },
});

/** http_get: plain GET. Which hosts are allowed is NOT decided here but by the policy gate. */
export const httpGetTool = ({ fetchImpl = fetch, maxBytes = 65536 } = {}) => ({
  name: 'http_get',
  description: 'Fetch a URL with HTTP GET.',
  schema: { type: 'object', properties: { url: { type: 'string', maxLength: 2048 } }, required: ['url'], additionalProperties: false },
  async run({ url }) {
    const res = await fetchImpl(url, { signal: AbortSignal.timeout(5000), redirect: 'error' });
    return `${res.status}\n${(await res.text()).slice(0, maxBytes)}`;
  },
});
