package si.openagentix.harness;

import static org.junit.jupiter.api.Assertions.*;
import static si.openagentix.harness.TestSupport.obj;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ToolsTest {
    private static final Map<String, Object> SCHEMA = obj("""
        {"type":"object","properties":{"s":{"type":"string","maxLength":3},"n":{"type":"number"},"i":{"type":"integer"},
         "b":{"type":"boolean"},"e":{"type":"string","enum":["x","y"]},"w":{"type":"weird"}},
         "required":["s"],"additionalProperties":false}""");

    private static List<String> check(String json) { return Tool.validate(SCHEMA, obj(json)); }

    @Test void validateAcceptsGoodArguments() {
        assertEquals(List.of(), check("{\"s\":\"abc\",\"n\":1.5,\"i\":2,\"b\":true,\"e\":\"x\"}"));
    }

    @Test void validateReportsEachKindOfProblem() {
        assertEquals(List.of("missing \"s\""), check("{}"));
        assertTrue(check("{\"s\":1}").get(0).contains("must be string"));
        assertTrue(check("{\"s\":\"abcd\"}").get(0).contains("too long"));
        assertTrue(check("{\"s\":\"a\",\"e\":\"z\"}").get(0).contains("one of"));
        assertTrue(check("{\"s\":\"a\",\"n\":\"x\"}").get(0).contains("must be number"));
        assertTrue(check("{\"s\":\"a\",\"i\":1.5}").get(0).contains("must be integer"));
        assertTrue(check("{\"s\":\"a\",\"b\":1}").get(0).contains("must be boolean"));
        assertTrue(check("{\"s\":\"a\",\"w\":1}").get(0).contains("must be weird"));
        assertTrue(check("{\"s\":\"a\",\"extra\":1}").get(0).contains("unknown argument"));
        for (Object bad : new Object[] {null, List.of(), "x"}) assertEquals(List.of("arguments must be an object"), Tool.validate(SCHEMA, bad));
        assertEquals(List.of(), Tool.validate(Map.of(), Map.of("anything", 1)));
    }

    @Test void registryListsAndFindsTools() throws IOException {
        ToolRegistry reg = new ToolRegistry().register(new ReadFileTool(TestSupport.tmp()));
        assertTrue(reg.get("read_file").isPresent());
        assertTrue(reg.get("nope").isEmpty());
        assertEquals(1, reg.all().size());
        assertEquals("Read a text file from the sandbox directory.", reg.all().get(0).description());
    }

    @Test void readFileStaysInsideTheSandbox() throws Exception {
        Path root = TestSupport.tmp(), outside = TestSupport.tmp();
        Files.createDirectories(root.resolve("notes"));
        Files.writeString(root.resolve("notes/a.txt"), "hello world");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(root.resolve("notes/link.txt"), outside.resolve("secret.txt"));
        ReadFileTool tool = new ReadFileTool(root, 5);
        assertEquals("hello", tool.run(Map.of("path", "notes/a.txt"))); // capped at maxBytes
        assertEquals("hello world", new ReadFileTool(root).run(Map.of("path", "notes/a.txt")));
        for (String p : List.of("../" + outside.getFileName() + "/secret.txt", outside.resolve("secret.txt").toString(), "notes/link.txt")) {
            var e = assertThrows(IOException.class, () -> tool.run(Map.of("path", p)), p);
            assertTrue(e.getMessage().contains("escapes"));
        }
        assertThrows(NoSuchFileException.class, () -> tool.run(Map.of("path", "notes/missing.txt")));
    }

    @Test void httpGetFetchesAndCapsTheBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = "x".repeat(100).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            var tool = new HttpGetTool(java.net.http.HttpClient.newHttpClient(), 10);
            assertEquals("200\n" + "x".repeat(10), tool.run(Map.of("url", url)));
            assertEquals("http_get", new HttpGetTool().name());
            assertEquals("Fetch a URL with HTTP GET.", new HttpGetTool().description());
            assertTrue(new HttpGetTool().schema().containsKey("properties"));
        } finally { server.stop(0); }
    }
}
