package si.openagentix.harness;

import static org.junit.jupiter.api.Assertions.*;
import static si.openagentix.harness.TestSupport.obj;
import static si.openagentix.harness.Policy.Effect.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyTest {
    private final Policy policy = TestSupport.policy();

    private Policy.Effect effect(String tool, String argsJson) { return policy.decide(tool, obj(argsJson)).effect(); }

    @Test void allowsAMatchingCall() { assertEquals(ALLOW, effect("read_file", "{\"path\":\"notes/a.txt\"}")); }

    @Test void deniesByDefault() {
        assertEquals(DENY, effect("delete_everything", "{}"));
        assertEquals(DENY, new Policy(obj("{\"rules\":[]}")).decide("read_file", obj("{\"path\":\"notes/a\"}")).effect());
        assertTrue(policy.decide("x", Map.of()).reason().contains("deny by default"));
    }

    @Test void aDefaultFieldCannotTurnDenyIntoAllow() {
        assertEquals(DENY, new Policy(obj("{\"default\":\"allow\",\"rules\":[]}")).decide("x", Map.of()).effect());
    }

    @Test void returnsApproveForRulesThatNeedAHuman() { assertEquals(APPROVE, effect("read_file", "{\"path\":\"reports/q3.txt\"}")); }

    @Test void pathConstraintsSurviveTraversalTricks() {
        for (String p : List.of("notes/../reports/q3.txt", "../notes/a.txt", "/etc/passwd", "notes/../../x", "notes\\u0000/a")) {
            assertNotEquals(ALLOW, effect("read_file", "{\"path\":\"" + p + "\"}"), p);
        }
        assertEquals(DENY, effect("read_file", "{\"path\":42}"));
        assertEquals(DENY, effect("read_file", "{}"));
        assertEquals(ALLOW, effect("read_file", "{\"path\":\"notes/./sub/../a.txt\"}"));
    }

    @Test void hostAllowlistIsExactAndHttpOnly() {
        assertEquals(ALLOW, effect("http_get", "{\"url\":\"https://example.org/x\"}"));
        for (String u : List.of("https://example.org.evil.test/", "https://evil.test/?h=example.org", "file:///etc/passwd", "nonsense", "https://user@evil.test@example.org.evil.test/", "not a url")) {
            assertEquals(DENY, effect("http_get", "{\"url\":\"" + u + "\"}"), u);
        }
    }

    @Test void denyBeatsApproveBeatsAllow() {
        Policy p = new Policy(obj("""
            {"rules":[{"tool":"t","effect":"allow"},{"tool":"t","effect":"approve"},{"tool":"t","effect":"deny","reason":"blocked"}]}"""));
        assertEquals(new Policy.Decision(DENY, "blocked"), p.decide("t", Map.of()));
        assertEquals(APPROVE, new Policy(obj("{\"rules\":[{\"tool\":\"t\",\"effect\":\"allow\"},{\"tool\":\"t\",\"effect\":\"approve\"}]}")).decide("t", Map.of()).effect());
    }

    @Test void unknownConstraintsAndEffectsFailClosed() {
        Policy p = new Policy(obj("{\"rules\":[{\"tool\":\"t\",\"effect\":\"allow\",\"args\":{\"a\":{\"magic\":true}}},{\"tool\":\"u\",\"effect\":\"yes please\"}]}"));
        assertEquals(DENY, p.decide("t", obj("{\"a\":1}")).effect());
        assertEquals(DENY, p.decide("u", Map.of()).effect());
    }

    @Test void oneOfConstraint() {
        Policy p = new Policy(obj("{\"rules\":[{\"tool\":\"t\",\"effect\":\"allow\",\"args\":{\"mode\":{\"oneOf\":[\"r\",\"w\"]}}}]}"));
        assertEquals(ALLOW, p.decide("t", obj("{\"mode\":\"r\"}")).effect());
        assertEquals(DENY, p.decide("t", obj("{\"mode\":\"x\"}")).effect());
    }

    @Test void loadReadsAFileAndRejectsAMalformedOne() throws Exception {
        Path dir = TestSupport.tmp();
        Files.writeString(dir.resolve("ok.json"), "{\"rules\":[]}");
        Files.writeString(dir.resolve("bad.json"), "{}");
        assertEquals(DENY, Policy.load(dir.resolve("ok.json")).decide("x", Map.of()).effect());
        assertThrows(IllegalArgumentException.class, () -> Policy.load(dir.resolve("bad.json")));
    }

    @Test void theDemoPolicyFileLoads() throws Exception {
        Policy p = Policy.load(Path.of("demo", "policy.json"));
        assertEquals(ALLOW, p.decide("read_file", obj("{\"path\":\"notes/welcome.txt\"}")).effect());
        assertEquals(DENY, p.decide("http_get", obj("{\"url\":\"https://example.org/\"}")).effect());
    }
}
