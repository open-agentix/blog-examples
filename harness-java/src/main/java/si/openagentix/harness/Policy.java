package si.openagentix.harness;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * The policy gate: a plain, deterministic function. No model, no I/O, no randomness.
 * Deny by default: a call is allowed only if a rule says so and all its argument constraints hold.
 */
public final class Policy {
    public enum Effect { DENY, APPROVE, ALLOW } // order = precedence: the strictest matching effect wins

    public record Decision(Effect effect, String reason) {}

    private final List<Map<String, Object>> rules;

    @SuppressWarnings("unchecked")
    public Policy(Map<String, Object> doc) {
        if (!(doc.get("rules") instanceof List<?> l)) throw new IllegalArgumentException("policy file needs a \"rules\" array");
        this.rules = (List<Map<String, Object>>) l;
    }

    public static Policy load(Path file) throws IOException { return new Policy(Json.parseObject(Files.readString(file))); }

    public Decision decide(String tool, Map<String, Object> args) {
        List<Map<String, Object>> hits = rules.stream().filter(r -> tool.equals(r.get("tool")) && matches(r.get("args"), args)).toList();
        for (Effect effect : Effect.values()) {
            for (Map<String, Object> rule : hits) {
                if (effect.name().equalsIgnoreCase(String.valueOf(rule.get("effect")))) {
                    return new Decision(effect, rule.get("reason") instanceof String r ? r : "rule: " + effect.name().toLowerCase() + " " + tool);
                }
            }
        }
        return new Decision(Effect.DENY, "no matching rule (deny by default)");
    }

    // Unknown constraint types fail closed.
    private static boolean matches(Object constraints, Map<String, Object> args) {
        if (constraints == null) return true;
        for (var arg : ((Map<?, ?>) constraints).entrySet()) {
            for (var c : ((Map<?, ?>) arg.getValue()).entrySet()) {
                if (!check((String) c.getKey(), args.get(arg.getKey()), c.getValue())) return false;
            }
        }
        return true;
    }

    private static boolean check(String kind, Object value, Object want) {
        return switch (kind) {
            case "prefix" -> safeRel(value) instanceof String p && p.startsWith((String) want);
            case "oneOf" -> ((List<?>) want).contains(value);
            case "hostIn" -> value instanceof String url && host(url) != null && ((List<?>) want).contains(host(url));
            default -> false;
        };
    }

    // Normalise a relative path; null if it is absolute or climbs out ("..").
    static String safeRel(Object v) {
        if (!(v instanceof String s) || s.indexOf('\0') >= 0 || s.startsWith("/")) return null;
        Deque<String> parts = new ArrayDeque<>();
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (parts.isEmpty()) return null; parts.removeLast(); } else parts.addLast(part);
        }
        return String.join("/", parts) + (s.endsWith("/") ? "/" : "");
    }

    private static String host(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() != null && u.getScheme().matches("(?i)https?") ? u.getHost() : null;
        } catch (IllegalArgumentException e) { return null; }
    }
}
