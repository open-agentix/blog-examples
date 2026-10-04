package si.openagentix.harness;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuditLogTest {
    private static AuditLog fill(AuditLog log) {
        log.append("a", Map.of("n", 1L)); log.append("b", Map.of("n", 2L)); log.append("c", Map.of("n", 3L));
        return log;
    }

    @Test void entriesAreChainedAndVerify() {
        AuditLog log = fill(new AuditLog());
        assertEquals(AuditLog.GENESIS, log.entries().get(0).get("prev"));
        assertEquals(log.entries().get(0).get("hash"), log.entries().get(1).get("prev"));
        var r = log.verify();
        assertTrue(r.ok());
        assertEquals(3, r.count());
        assertEquals(log.head(), r.head());
    }

    @Test void emptyLogVerifies() {
        assertTrue(new AuditLog().verify().ok());
        assertEquals(AuditLog.GENESIS, new AuditLog().head());
    }

    @Test void tamperingWithALineInTheFileIsDetected() throws Exception {
        Path file = TestSupport.tmp().resolve("audit.jsonl");
        fill(new AuditLog(file, () -> "t"));
        assertTrue(AuditLog.verifyFile(file).ok());
        Files.writeString(file, Files.readString(file).replace("\"n\":2", "\"n\":99"));
        var r = AuditLog.verifyFile(file);
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("entry 2: hash mismatch"), r.error());
    }

    @Test void removingALineIsDetected() throws Exception {
        Path file = TestSupport.tmp().resolve("audit.jsonl");
        fill(new AuditLog(file, () -> "t"));
        List<String> lines = Files.readAllLines(file);
        Files.write(file, List.of(lines.get(0), lines.get(2)));
        assertTrue(AuditLog.verifyFile(file).error().contains("unexpected seq"));
    }

    @Test void relinkingAnEditedEntryStillBreaksTheChain() {
        AuditLog log = fill(new AuditLog());
        AuditLog other = new AuditLog();
        other.append("a", Map.of("n", 7L));
        List<Map<String, Object>> forged = new ArrayList<>(log.entries());
        forged.set(0, other.entries().get(0));
        assertTrue(AuditLog.verify(forged).error().contains("broken chain"));
    }

    @Test void invalidJsonIsReported() throws Exception {
        Path file = TestSupport.tmp().resolve("audit.jsonl");
        Files.writeString(file, "{not json}\n");
        assertEquals("line 1: not valid JSON", AuditLog.verifyFile(file).error());
    }

    @Test void aLogResumesFromAnExistingFile() throws Exception {
        Path file = TestSupport.tmp().resolve("audit.jsonl");
        fill(new AuditLog(file, () -> "t"));
        new AuditLog(file, () -> "t").append("d", Map.of());
        assertEquals(4, AuditLog.verifyFile(file).count());
    }

    @Test void readsALogWrittenByTheNodeExample() throws Exception {
        var r = AuditLog.verifyFile(Path.of("..", "testdata", "audit-sample.jsonl"));
        assertTrue(r.ok(), r.error());
        assertEquals(3, r.count());
    }
}
