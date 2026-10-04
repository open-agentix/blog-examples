package si.openagentix.harness;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Pluggable model interface. Implementations: {@link ScriptedModel} (default) and {@link OpenAiCompatModel}. */
public interface Model {
    record Call(String id, String name, Map<String, Object> args) {}

    /** role is "user", "assistant" or "tool". */
    record Message(String role, String content, List<Call> calls, String toolCallId) {}

    record Reply(String text, List<Call> calls, int tokens) {
        public static Reply text(String text) { return new Reply(text, List.of(), 10); }
        public static Reply call(String id, String name, Map<String, Object> args) { return new Reply("", List.of(new Call(id, name, args)), 10); }
    }

    Reply next(List<Message> messages, List<Tool> tools, Duration timeout) throws Exception;
}
