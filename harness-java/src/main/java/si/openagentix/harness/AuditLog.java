package si.openagentix.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Append-only, hash-chained audit log. Each entry carries the hash of the previous one. */
public final class AuditLog {
    public static final String GENESIS = "0".repeat(64);

    public record Result(boolean ok, int count, String error, String head) {}

    private final List<Map<String, Object>> entries = new ArrayList<>();
    private final Path file;
    private final Supplier<String> clock;

    public AuditLog() { this(null, () -> Instant.now().toString()); }

    public AuditLog(Path file, Supplier<String> clock) {
        this.file = file;
        this.clock = clock;
        try {
            if (file != null && Files.exists(file)) for (String l : Files.readAllLines(file)) if (!l.isBlank()) entries.add(Json.parseObject(l));
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public List<Map<String, Object>> entries() { return List.copyOf(entries); }

    public String head() { return entries.isEmpty() ? GENESIS : (String) entries.getLast().get("hash"); }

    public synchronized void append(String event, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("seq", (long) entries.size() + 1);
        e.put("ts", clock.get());
        e.put("event", event);
        e.put("data", data);
        e.put("prev", head());
        e.put("hash", digest(e));
        entries.add(e);
        if (file != null) {
            try { Files.writeString(file, Json.write(e) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
        }
    }

    public Result verify() { return verify(entries); }

    /** Read a JSON-lines audit file and verify it. */
    public static Result verifyFile(Path file) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        int n = 0;
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            n++;
            try { list.add(Json.parseObject(line)); }
            catch (IllegalArgumentException e) { return new Result(false, 0, "line " + n + ": not valid JSON", null); }
        }
        return verify(list);
    }

    public static Result verify(List<Map<String, Object>> list) {
        String prev = GENESIS;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> e = list.get(i);
            if (!Objects.equals(e.get("seq"), (long) i + 1)) return bad("entry " + (i + 1) + ": unexpected seq " + e.get("seq"));
            if (!prev.equals(e.get("prev"))) return bad("entry " + (i + 1) + ": broken chain (prev mismatch)");
            if (!digest(e).equals(e.get("hash"))) return bad("entry " + (i + 1) + ": hash mismatch (content changed)");
            prev = (String) e.get("hash");
        }
        return new Result(true, list.size(), null, prev);
    }

    private static Result bad(String why) { return new Result(false, 0, why, null); }

    private static String digest(Map<String, Object> e) {
        Map<String, Object> body = new LinkedHashMap<>(e);
        body.remove("hash");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
