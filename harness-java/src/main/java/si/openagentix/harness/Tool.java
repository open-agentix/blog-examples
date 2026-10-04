package si.openagentix.harness;

import java.util.*;

/** A tool the model may ask for. Argument checking uses a JSON-schema-ish map; see {@link #validate}. */
public interface Tool {
    String name();
    String description();
    Map<String, Object> schema();
    String run(Map<String, Object> args) throws Exception;

    /** Supports: type, required, enum, maxLength, additionalProperties:false. Returns a list of errors. */
    static List<String> validate(Map<String, Object> schema, Object args) {
        if (!(args instanceof Map<?, ?> a)) return List.of("arguments must be an object");
        List<String> errors = new ArrayList<>();
        Map<?, ?> props = schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        if (schema.get("required") instanceof List<?> req) for (Object k : req) if (!a.containsKey(k)) errors.add("missing \"" + k + "\"");
        for (var e : a.entrySet()) {
            if (!(props.get(e.getKey()) instanceof Map<?, ?> spec)) {
                if (Boolean.FALSE.equals(schema.get("additionalProperties"))) errors.add("unknown argument \"" + e.getKey() + "\"");
                continue;
            }
            Object v = e.getValue();
            String type = String.valueOf(spec.get("type"));
            boolean ok = switch (type) {
                case "string" -> v instanceof String;
                case "number" -> v instanceof Number;
                case "integer" -> v instanceof Long || v instanceof Integer;
                case "boolean" -> v instanceof Boolean;
                default -> false;
            };
            if (!ok) errors.add("\"" + e.getKey() + "\" must be " + type);
            else if (spec.get("enum") instanceof List<?> en && !en.contains(v)) errors.add("\"" + e.getKey() + "\" must be one of " + en);
            else if (spec.get("maxLength") instanceof Number max && v instanceof String s && s.length() > max.intValue()) errors.add("\"" + e.getKey() + "\" is too long");
        }
        return errors;
    }
}
