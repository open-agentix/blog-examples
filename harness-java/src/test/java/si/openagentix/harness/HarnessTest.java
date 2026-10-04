package si.openagentix.harness;

import static org.junit.jupiter.api.Assertions.*;
import static si.openagentix.harness.Model.Reply;
import static si.openagentix.harness.TestSupport.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import si.openagentix.harness.Model.*;

class HarnessTest {
    private static final Map<String, Object> A = Map.of("path", "notes/a.txt");

    private static Harness.Result run(Ctx c, Model m, Policy p, Harness.Approver ap, Harness.Limits l) throws Exception {
        return new Harness(m, c.tools(), p, c.audit(), ap).run("test", l);
    }

    private static List<Object> events(AuditLog a) { return a.entries().stream().map(e -> e.get("event")).toList(); }

    private static Message toolMsg(Harness.Result r, int n) { return r.messages().stream().filter(m -> m.role().equals("tool")).toList().get(n); }

    @Test void runsTheLoop() throws Exception {
        Ctx c = Ctx.create();
        var r = run(c, new ScriptedModel(Reply.call("1", "read_file", A), Reply.text("all done")), policy(), Harness.DENY_ALL, Harness.Limits.defaults());
        assertEquals("done", r.reason());
        assertEquals("all done", r.text());
        assertEquals(2, r.steps());
        assertEquals("hello", toolMsg(r, 0).content());
        assertEquals(List.of("run.start", "tool.decision", "tool.result", "run.end"), events(c.audit()));
        assertTrue(c.audit().verify().ok());
    }

    @Test void aDeniedCallNeverRunsTheToolAndIsAudited() throws Exception {
        Ctx c = Ctx.create();
        boolean[] ran = {false};
        c.tools().register(new Tool() {
            public String name() { return "http_get"; }
            public String description() { return ""; }
            public Map<String, Object> schema() { return new HttpGetTool().schema(); }
            public String run(Map<String, Object> args) { ran[0] = true; return ""; }
        });
        var r = run(c, new ScriptedModel(Reply.call("1", "http_get", Map.of("url", "https://example.org/")), Reply.text("ok")), new Policy(obj("{\"rules\":[]}")), Harness.DENY_ALL, Harness.Limits.defaults());
        assertFalse(ran[0]);
        assertTrue(toolMsg(r, 0).content().startsWith("denied: no matching rule"));
        assertEquals(List.of("deny"), decisions(c.audit()));
        assertFalse(events(c.audit()).contains("tool.result"));
    }

    @Test void approvalRequiredIsDeniedWhenNonInteractive() throws Exception {
        Ctx c = Ctx.create();
        Policy p = new Policy(obj("{\"rules\":[{\"tool\":\"read_file\",\"effect\":\"approve\"}]}"));
        var r = run(c, new ScriptedModel(Reply.call("1", "read_file", A), Reply.text("ok")), p, Harness.DENY_ALL, Harness.Limits.defaults());
        assertTrue(toolMsg(r, 0).content().contains("approval not granted"));
        var approval = c.audit().entries().stream().filter(e -> e.get("event").equals("tool.approval")).findFirst().orElseThrow();
        assertEquals(false, ((Map<?, ?>) approval.get("data")).get("granted"));
    }

    @Test void approvalGrantedByAnApproverLetsTheCallRun() throws Exception {
        Ctx c = Ctx.create();
        Policy p = new Policy(obj("{\"rules\":[{\"tool\":\"read_file\",\"effect\":\"approve\"}]}"));
        List<String> asked = new ArrayList<>();
        var r = run(c, new ScriptedModel(Reply.call("1", "read_file", A), Reply.text("ok")), p, (call, d) -> asked.add(call.name() + ":" + d.effect()), Harness.Limits.defaults());
        assertEquals(List.of("read_file:APPROVE"), asked);
        assertEquals("hello", toolMsg(r, 0).content());
    }

    @Test void unknownToolsAndInvalidArgumentsAreDeniedBeforeThePolicyIsConsulted() throws Exception {
        Ctx c = Ctx.create();
        Policy p = new Policy(obj("{\"rules\":[{\"tool\":\"rm_rf\",\"effect\":\"allow\"},{\"tool\":\"read_file\",\"effect\":\"allow\"}]}"));
        var bad = new Reply("", List.of(new Call("1", "rm_rf", Map.of()), new Call("2", "read_file", Map.of("path", 5L)),
            new Call("3", "read_file", null), new Call("4", null, Map.of())), 10);
        var r = run(c, new ScriptedModel(bad, Reply.text("ok")), p, Harness.DENY_ALL, Harness.Limits.defaults());
        assertEquals(List.of("deny", "deny", "deny", "deny"), decisions(c.audit()));
        assertTrue(toolMsg(r, 0).content().contains("unknown tool"));
        assertTrue(toolMsg(r, 1).content().contains("must be string"));
        assertTrue(toolMsg(r, 2).content().contains("arguments must be an object"));
    }

    @Test void aToolThatThrowsBecomesAnErrorResult() throws Exception {
        Ctx c = Ctx.create();
        var r = run(c, new ScriptedModel(Reply.call("1", "read_file", Map.of("path", "notes/missing.txt")), Reply.text("ok")), policy(), Harness.DENY_ALL, Harness.Limits.defaults());
        assertTrue(toolMsg(r, 0).content().startsWith("error: "));
        assertEquals("done", r.reason());
    }

    @Test void maxStepsIsAHardStop() throws Exception {
        Ctx c = Ctx.create();
        var r = run(c, new ScriptedModel(Reply.call("1", "read_file", A)), policy(), Harness.DENY_ALL, new Harness.Limits(3, 10_000, Duration.ofSeconds(5)));
        assertEquals("max-steps", r.reason());
        assertEquals(3, r.steps());
    }

    @Test void tokenBudgetStopsTheRunBeforeTheOverBudgetReplyIsActedOn() throws Exception {
        Ctx c = Ctx.create();
        var big = new Reply("", List.of(new Call("1", "read_file", A)), 60);
        var r = run(c, new ScriptedModel(big), policy(), Harness.DENY_ALL, new Harness.Limits(8, 100, Duration.ofSeconds(5)));
        assertEquals("budget", r.reason());
        assertEquals(2, r.steps());
        assertEquals(1, decisions(c.audit()).size());
    }

    @Test void timeoutIsAHardStop() throws Exception {
        Ctx c = Ctx.create();
        Model slow = (m, t, d) -> { Thread.sleep(50); return Reply.call("1", "read_file", A); };
        var r = run(c, slow, policy(), Harness.DENY_ALL, new Harness.Limits(8, 10_000, Duration.ofMillis(10)));
        assertEquals("timeout", r.reason());
    }
}
