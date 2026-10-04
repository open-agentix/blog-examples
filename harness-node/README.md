# harness-node

An extremely lightweight agent harness in Node.js 20+ (ES modules, no runtime dependencies, tests with `node:test`).
See the [top-level README](../README.md) for the design, the limits ("what this is NOT") and how it compares to `harness-java`.

```sh
npm test            # 38 tests, no network, no model
npm run coverage    # same, with the built-in coverage report
npm run demo        # allow, deny, approval-required, audit verify, tamper detection
```

| File | Role |
| --- | --- |
| `src/harness.js` | the loop, hard stops for steps, tokens and time, the gate call site |
| `src/policy.js` | `decide(policy, tool, args)`: deterministic, deny by default |
| `src/audit.js` | hash-chained, append-only log and `verifyFile` / `verifyEntries` |
| `src/tools.js` | registry, argument validation, `read_file`, `http_get` |
| `src/model.js` | `ScriptedModel` (default) and the optional `OpenAICompatModel` |
| `demo/` | the runnable demo, `policy.json`, a sandbox directory |

Use a real model (optional, an OpenAI-compatible endpoint; the key stays in the environment and is never logged):

```js
import { OpenAICompatModel } from './src/index.js';
const model = OpenAICompatModel.fromEnv(); // HARNESS_BASE_URL, HARNESS_API_KEY, HARNESS_MODEL
```
