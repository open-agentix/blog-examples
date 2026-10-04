package si.openagentix.harness;

import static org.junit.jupiter.api.Assertions.*;
import static si.openagentix.harness.TestSupport.obj;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import si.openagentix.harness.Model.*;

class ModelTest {
    private static final String SECRET = "sk-test-secret-123";

    @Test void scriptedModelReplaysAndRepeatsTheLastReply() {
        var m = new ScriptedModel(Reply.text("one"), Reply.text("two"));
        assertEquals("one", m.next(List.of(), List.of(), null).text());
        assertEquals("two", m.next(List.of(), List.of(), null).text());
        var last = m.next(List.of(), List.of(), null);
        assertEquals("two", last.text());
        assertTrue(last.calls().isEmpty());
    }

    private record Stub(HttpServer server, AtomicReference<String> body, AtomicReference<String> auth) {
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"; }
    }

    private static Stub stub(int status, String response) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("authorization"));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return new Stub(server, body, auth);
    }

    @Test void adapterBuildsAnOpenAiStyleRequestAndParsesToolCalls() throws Exception {
        Stub s = stub(200, """
            {"choices":[{"message":{"content":"hi","tool_calls":[
              {"id":"c1","function":{"name":"read_file","arguments":"{\\"path\\":\\"notes/a\\"}"}},
              {"id":"c2","function":{"name":"x","arguments":"{oops"}}]}}],"usage":{"total_tokens":42}}""");
        try {
            var model = new OpenAiCompatModel(s.url(), SECRET, "m1");
            List<Message> messages = List.of(
                new Message("user", "go", List.of(), null),
                new Message("assistant", "", List.of(new Call("c1", "read_file", Map.of("path", "notes/a"))), null),
                new Message("tool", "data", List.of(), "c1"));
            Reply reply = model.next(messages, List.of(new ReadFileTool(TestSupport.tmp())), Duration.ofSeconds(5));
            assertEquals("Bearer " + SECRET, s.auth().get());
            Map<String, Object> sent = obj(s.body().get());
            assertEquals("m1", sent.get("model"));
            assertTrue(s.body().get().contains("\"tool_call_id\":\"c1\""));
            assertTrue(s.body().get().contains("\"name\":\"read_file\""));
            assertEquals("hi", reply.text());
            assertEquals(42, reply.tokens());
            assertEquals(new Call("c1", "read_file", Map.of("path", "notes/a")), reply.calls().get(0));
            assertNull(reply.calls().get(1).args()); // unparsable arguments become null and are then denied
        } finally { s.server().stop(0); }
    }

    @Test void adapterToleratesAnEmptyAnswer() throws Exception {
        Stub s = stub(200, "{}");
        try {
            Reply r = new OpenAiCompatModel(s.url(), SECRET, "m").next(List.of(), List.of(), Duration.ofSeconds(5));
            assertEquals(new Reply("", List.of(), 0), r);
        } finally { s.server().stop(0); }
    }

    @Test void errorsNeverContainTheKey() throws Exception {
        Stub s = stub(401, "{\"error\":\"bad key\"}");
        try {
            var model = new OpenAiCompatModel(s.url(), SECRET, "m");
            var e = assertThrows(IOException.class, () -> model.next(List.of(), List.of(), Duration.ofSeconds(5)));
            assertTrue(e.getMessage().contains("HTTP 401"));
            assertFalse(e.getMessage().contains(SECRET));
            assertFalse(model.toString().contains(SECRET));
        } finally { s.server().stop(0); }
    }

    @Test void fromEnvNeedsAllThreeVariables() {
        assertThrows(IllegalStateException.class, () -> OpenAiCompatModel.fromEnv(Map.of()));
        var m = OpenAiCompatModel.fromEnv(Map.of("HARNESS_BASE_URL", "http://x/v1/", "HARNESS_API_KEY", "k", "HARNESS_MODEL", "m"));
        assertEquals("OpenAiCompatModel[http://x/v1, m]", m.toString());
    }
}
