# harness-java

The same design as [`harness-node`](../harness-node), in Java 21 with only the JDK at runtime (`java.net.http`,
`MessageDigest`, and a small JSON class written for this example). JUnit 5 is used for tests only.
See the [top-level README](../README.md) for the design and the limits ("what this is NOT").

```sh
mvn verify          # tests + JaCoCo coverage gate (build fails below 80 % of lines)
./run-demo.sh       # needs only a JDK (javac), no Maven: allow, deny, approval-required, audit verify, tamper detection
```

| File | Role |
| --- | --- |
| `Harness.java` | the loop, hard stops for steps, tokens and time, the gate call site |
| `Policy.java` | `decide(tool, args)`: deterministic, deny by default |
| `AuditLog.java` | hash-chained, append-only log and `verify` / `verifyFile` |
| `Tool.java`, `ToolRegistry.java` | tool interface, argument validation, registry |
| `ReadFileTool.java`, `HttpGetTool.java` | the two demo tools |
| `Model.java`, `ScriptedModel.java`, `OpenAiCompatModel.java` | model interface, default script, optional adapter |
| `Json.java` | minimal JSON reader and canonical writer (sorted keys, same bytes as the Node example) |
| `Demo.java` | the runnable demo (policy and sandbox in `demo/`) |

Use a real model (optional): `OpenAiCompatModel.fromEnv(System.getenv())` reads `HARNESS_BASE_URL`, `HARNESS_API_KEY`
and `HARNESS_MODEL`; the key is only sent as a header and never logged.
