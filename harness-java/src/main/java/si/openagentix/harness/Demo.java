package si.openagentix.harness;

import java.io.Console;
import java.nio.file.*;
import java.util.*;
import si.openagentix.harness.Model.Reply;

/** Runnable demo (from the harness-java directory): see run-demo.sh. Add --interactive to answer approval prompts. */
public final class Demo {
    public static void main(String[] args) throws Exception {
        Path demo = Path.of("demo"), out = demo.resolve("out");
        Files.createDirectories(out);
        Path auditFile = out.resolve("audit.jsonl");
        Files.deleteIfExists(auditFile);

        Model model = new ScriptedModel( // pretends to be a model; no network, no key
            Reply.call("1", "read_file", Map.of("path", "notes/welcome.txt")),        // allowed
            Reply.call("2", "http_get", Map.of("url", "https://example.org/")),       // denied: no rule, deny by default
            Reply.call("3", "read_file", Map.of("path", "reports/q3.txt")),           // approval required
            Reply.call("4", "read_file", Map.of("path", "notes/../../etc/passwd")),   // denied: path escapes
            Reply.text("Done: one read allowed, three calls refused."));

        ToolRegistry tools = new ToolRegistry().register(new ReadFileTool(demo.resolve("sandbox"))).register(new HttpGetTool());
        Harness.Approver approver = Harness.DENY_ALL;
        if (List.of(args).contains("--interactive") && System.console() != null) {
            Console c = System.console();
            approver = (call, d) -> Optional.ofNullable(c.readLine("approve %s %s? [y/N] ", call.name(), Json.write(call.args()))).orElse("").toLowerCase().startsWith("y");
        }

        AuditLog audit = new AuditLog(auditFile, () -> java.time.Instant.now().toString());
        Harness.Result result = new Harness(model, tools, Policy.load(demo.resolve("policy.json")), audit, approver)
            .run("Read my notes and my report.", Harness.Limits.defaults());

        for (var e : audit.entries()) {
            @SuppressWarnings("unchecked") var d = (Map<String, Object>) e.get("data");
            if (e.get("event").equals("tool.decision")) System.out.printf("%-8s %s %s  (%s)%n", d.get("effect"), d.get("tool"), Json.write(d.get("args")), d.get("reason"));
            if (e.get("event").equals("tool.approval")) System.out.println("         -> approval " + (Boolean.TRUE.equals(d.get("granted")) ? "granted" : "not granted"));
        }
        System.out.printf("%nrun ended: %s after %d steps; %s%n", result.reason(), result.steps(), result.text());
        System.out.println("audit verify: " + AuditLog.verifyFile(auditFile));

        Path tampered = out.resolve("audit-tampered.jsonl"); // flip one decision in a copy and verify again
        Files.writeString(tampered, Files.readString(auditFile).replace("\"effect\":\"deny\"", "\"effect\":\"allow\""));
        System.out.println("tampered copy: " + AuditLog.verifyFile(tampered));
    }
}
