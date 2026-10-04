package si.openagentix.harness;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class TestSupport {
    static Path tmp() throws IOException { return Files.createTempDirectory("harness-"); }

    static Map<String, Object> obj(String json) { return Json.parseObject(json); }

    static Policy policy() {
        return new Policy(obj("""
            {"rules":[
              {"tool":"read_file","effect":"allow","args":{"path":{"prefix":"notes/"}}},
              {"tool":"read_file","effect":"approve","args":{"path":{"prefix":"reports/"}}},
              {"tool":"http_get","effect":"allow","args":{"url":{"hostIn":["example.org"]}}}]}"""));
    }

    /** A sandbox with notes/a.txt, a registry with read_file, and a fresh in-memory audit log. */
    record Ctx(Path root, ToolRegistry tools, AuditLog audit) {
        static Ctx create() throws IOException {
            Path root = tmp();
            Files.createDirectories(root.resolve("notes"));
            Files.writeString(root.resolve("notes/a.txt"), "hello");
            return new Ctx(root, new ToolRegistry().register(new ReadFileTool(root)), new AuditLog(null, () -> "2026-01-01T00:00:00Z"));
        }
    }

    static List<Object> decisions(AuditLog audit) {
        return audit.entries().stream().filter(e -> e.get("event").equals("tool.decision"))
            .<Object>map(e -> ((Map<?, ?>) e.get("data")).get("effect")).toList();
    }
}
