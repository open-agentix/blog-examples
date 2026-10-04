package si.openagentix.harness;

import java.time.Duration;
import java.util.*;
import si.openagentix.harness.Model.*;
import si.openagentix.harness.Policy.Effect;

/** The agent loop: messages -> model -> tool calls -> results -> repeat, with hard limits. */
public final class Harness {
    /** Each limit is a hard stop. */
    public record Limits(int maxSteps, int maxTokens, Duration timeout) {
        public static Limits defaults() { return new Limits(8, 10_000, Duration.ofSeconds(30)); }
    }

    public record Result(String reason, String text, int steps, int tokens, List<Message> messages) {}

    /** Asked only for "approve" decisions. */
    public interface Approver { boolean approve(Call call, Policy.Decision decision); }

    public static final Approver DENY_ALL = (call, decision) -> false; // non-interactive default

    private final Model model;
    private final ToolRegistry tools;
    private final Policy policy;
    private final AuditLog audit;
    private final Approver approver;

    public Harness(Model model, ToolRegistry tools, Policy policy, AuditLog audit, Approver approver) {
        this.model = model; this.tools = tools; this.policy = policy; this.audit = audit; this.approver = approver;
    }

    public Result run(String task, Limits limits) throws Exception {
        long deadline = System.nanoTime() + limits.timeout().toNanos();
        List<Message> messages = new ArrayList<>(List.of(new Message("user", task, List.of(), null)));
        int tokens = 0, step = 0;
        audit.append("run.start", Map.of("task", task, "limits", Map.of("maxSteps", (long) limits.maxSteps(), "maxTokens", (long) limits.maxTokens(), "timeoutMs", limits.timeout().toMillis())));
        String reason = "max-steps", text = "";
        while (step < limits.maxSteps()) {
            long left = deadline - System.nanoTime();
            if (left <= 0) { reason = "timeout"; break; }
            step++;
            Reply reply = model.next(messages, tools.all(), Duration.ofNanos(left));
            tokens += reply.tokens();
            if (tokens > limits.maxTokens()) { reason = "budget"; break; } // stop before acting on an over-budget reply
            messages.add(new Message("assistant", reply.text(), reply.calls(), null));
            if (reply.calls().isEmpty()) { reason = "done"; text = reply.text(); break; }
            for (Call call : reply.calls()) messages.add(execute(call));
        }
        audit.append("run.end", Map.of("reason", reason, "steps", (long) step, "tokens", (long) tokens));
        return new Result(reason, text, step, tokens, messages);
    }

    private Message execute(Call call) {
        String name = String.valueOf(call.name()); // a model may send junk, so never assume a name
        Optional<Tool> tool = tools.get(name);
        List<String> errors = tool.isPresent() ? Tool.validate(tool.get().schema(), call.args()) : List.of("unknown tool");
        Policy.Decision decision = errors.isEmpty()
            ? policy.decide(name, call.args())
            : new Policy.Decision(Effect.DENY, String.join("; ", errors));
        audit.append("tool.decision", Map.of("tool", name, "args", call.args() == null ? Map.of() : call.args(),
            "effect", decision.effect().name().toLowerCase(), "reason", decision.reason()));
        boolean granted = decision.effect() == Effect.ALLOW;
        if (decision.effect() == Effect.APPROVE) {
            granted = approver.approve(call, decision);
            audit.append("tool.approval", Map.of("tool", name, "granted", granted));
        }
        String content;
        if (!granted) content = "denied: " + (decision.effect() == Effect.APPROVE ? "approval not granted" : decision.reason());
        else {
            try { content = tool.get().run(call.args()); } catch (Exception e) { content = "error: " + e.getMessage(); }
            audit.append("tool.result", Map.of("tool", name, "bytes", (long) content.length()));
        }
        return new Message("tool", content, List.of(), call.id());
    }
}
