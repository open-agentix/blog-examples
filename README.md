# blog-examples

Small, runnable code examples that accompany posts on the [open-agentix blog](https://blog.openagentix.si).
They are teaching material, not production code.

| Directory | What it is | Accompanies |
| --- | --- | --- |
| [`harness-node/`](harness-node) | An extremely lightweight agent harness in Node.js 20+: no runtime dependencies, ES modules, `node:test`. | [What is an agent harness?](https://blog.openagentix.si/posts/what-is-an-agent-harness/) |
| [`harness-java/`](harness-java) | The same design in Java 21: plain JDK only (`java.net.http`, `MessageDigest`, a small JSON reader/writer). JUnit for tests only. | same post |

Both implement the **same design**, so you can compare them line by line:

- **Agent loop**: messages, model call, tool calls, results, repeat. Hard stops on steps, tokens (a stand-in for cost) and wall-clock time.
- **Tool registry** with JSON-schema-ish argument validation (`type`, `required`, `enum`, `maxLength`, `additionalProperties`).
- **Policy gate** in front of every tool call: a plain, deterministic function over a small JSON policy file, **not** decided by the model. Deny by default, argument constraints (path prefix, allowed hosts), outcomes `allow`, `deny` or `approve`; the strictest matching rule wins. Approval-required is auto-denied when nothing is interactive.
- **Append-only, hash-chained audit log** (SHA-256, each entry contains the previous hash) with a `verify` function. Editing or removing a line is detected. Both examples read each other's logs (see `testdata/`).
- **Pluggable model interface** with a deterministic scripted model (default, used by the demos and tests: no API key, no network) and an optional adapter for an OpenAI-compatible chat completions endpoint, configured only through `HARNESS_BASE_URL`, `HARNESS_API_KEY` and `HARNESS_MODEL`. The key is never logged.
- **Two demo tools**: `read_file` (restricted to a sandbox directory) and `http_get` (denied unless the host is on an allowlist; the default demo policy has no such rule, so the demo shows the denial being audited).

## Try it

```sh
# Node.js 20+
cd harness-node && npm test && npm run demo

# Java 21 + Maven (tests, coverage gate); the demo needs only a JDK
cd harness-java && mvn verify && ./run-demo.sh
```

The demo shows an allowed call, a denied call, an approval-required call (auto-denied, because the demo is non-interactive; pass `--interactive` to answer yourself), a path-traversal attempt, and then verifies the audit log and shows that a tampered copy fails verification.

## Size and tests

Each core is small enough to read in one sitting. Loop, policy gate, audit log and argument validation come to about 210 lines in Node and about 290 in Java (plus a 150-line JSON class, because the JDK has none); the model classes and the two demo tools are on top of that. Tests need no network and no model; both suites cover well over 80 % of the lines, and CI enforces that threshold on Node 20, Node 22 and Java 21.

## What this is NOT

- **Not production software.** There is no sandboxing or isolation: `read_file` checks paths, but the process itself runs with your privileges, and nothing here contains a malicious tool.
- **No real authentication, identity, tenancy or secrets handling.** There is nobody to approve a call except whoever runs the process.
- **The model is simulated by default.** The OpenAI-compatible adapter is a minimal, optional convenience: no streaming, no retries, no rate limiting.
- **The audit log is tamper-evident, not tamper-proof.** It detects edits and removals inside the chain. Someone who can rewrite the whole file, or cut the end off, needs an external anchor (for example publishing the latest hash somewhere else) to be caught.
- **Token counts are whatever the model reports**, and the budget is a step and token cap, not money.
- It is **not a framework**: no chains, graphs, memory, retrieval or multi-agent patterns. That is on purpose; see the post.

## How this maps to open-agentix

[open-agentix](https://github.com/open-agentix/open-agentix) is the platform these ideas come from. The examples show the shape of three of its building blocks (policy gate, hash-chained audit, hard budgets) in a few hundred lines. A platform is for what a teaching example cannot be; check the project's own docs and roadmap for what is shipped today:

- identity, roles and multi-tenancy instead of one anonymous run;
- policies as managed, versioned configuration, with approvals by real people;
- budgets and cost attribution per agent and per tenant, in money rather than a token counter;
- audit storage and export, beyond a local file;
- isolation for tools and code that run, instead of path checks;
- running different harnesses and model providers behind the same control layer.

Questions and discussion: [GitHub Discussions](https://github.com/open-agentix/blog-examples/discussions) and [Issues](https://github.com/open-agentix/blog-examples/issues). General contact: info@openagentix.si.

## License

[Apache License 2.0](LICENSE). Contributions are welcome under the same license; please sign off your commits (`git commit -s`).
