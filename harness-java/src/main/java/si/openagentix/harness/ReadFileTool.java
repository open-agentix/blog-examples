package si.openagentix.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** read_file: reads a UTF-8 file, but only inside {@code root} (symlinks resolved, size capped). */
public record ReadFileTool(Path root, int maxBytes) implements Tool {
    public ReadFileTool(Path root) { this(root, 65536); }

    public String name() { return "read_file"; }
    public String description() { return "Read a text file from the sandbox directory."; }
    public Map<String, Object> schema() {
        return Json.parseObject("""
            {"type":"object","properties":{"path":{"type":"string","maxLength":256}},"required":["path"],"additionalProperties":false}""");
    }

    public String run(Map<String, Object> args) throws IOException {
        Path base = root.toRealPath();
        Path full = base.resolve((String) args.get("path")).toRealPath();
        if (!full.startsWith(base)) throw new IOException("path escapes the sandbox");
        byte[] bytes = Files.readAllBytes(full);
        return new String(bytes, 0, Math.min(bytes.length, maxBytes), StandardCharsets.UTF_8);
    }
}
