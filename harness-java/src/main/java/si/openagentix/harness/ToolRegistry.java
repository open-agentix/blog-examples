package si.openagentix.harness;

import java.util.*;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) { tools.put(tool.name(), tool); return this; }
    public Optional<Tool> get(String name) { return Optional.ofNullable(tools.get(name)); }
    public List<Tool> all() { return List.copyOf(tools.values()); }
}
