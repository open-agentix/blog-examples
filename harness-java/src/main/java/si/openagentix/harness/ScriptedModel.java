package si.openagentix.harness;

import java.time.Duration;
import java.util.List;

/** Deterministic model for demos and tests: replays a script, one reply per call. No network. */
public final class ScriptedModel implements Model {
    private final List<Reply> script;
    private int i;

    public ScriptedModel(Reply... script) { this.script = List.of(script); }

    public Reply next(List<Message> messages, List<Tool> tools, Duration timeout) {
        return script.get(Math.min(i++, script.size() - 1)); // the last reply repeats
    }
}
