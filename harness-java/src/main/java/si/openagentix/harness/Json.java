package si.openagentix.harness;

import java.util.*;

/** Minimal JSON: objects are Map, arrays are List, numbers are Long or Double. Output has sorted keys. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw p.fail("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (parse(text) instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("JSON object expected");
    }

    /** Canonical form: keys sorted, no whitespace. Same bytes as the Node example's `canonical()`. */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        switch (v) {
            case null -> sb.append("null");
            case String str -> quote(str, sb);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (var e : new TreeMap<>(m).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote((String) e.getKey(), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int k = 0; k < l.size(); k++) { if (k > 0) sb.append(','); write(l.get(k), sb); }
                sb.append(']');
            }
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            default -> throw new IllegalArgumentException("cannot serialise " + v.getClass());
        }
    }

    private static void quote(String str, StringBuilder sb) {
        sb.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw fail("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return true; }
        if (s.startsWith("false", i)) { i += 5; return false; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        do {
            ws();
            String k = string();
            ws(); expect(':');
            m.put(k, value());
            ws();
        } while (take(','));
        expect('}');
        return m;
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek(']')) { i++; return l; }
        do l.add(value()); while (take(',') );
        expect(']');
        return l;
    }

    private String string() {
        if (!peek('"')) throw fail("string expected");
        StringBuilder sb = new StringBuilder();
        for (i++; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { i++; return sb.toString(); }
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(++i);
            switch (e) {
                case 'n' -> sb.append('\n'); case 't' -> sb.append('\t'); case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b'); case 'f' -> sb.append('\f');
                case '"', '\\', '/' -> sb.append(e);
                case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                default -> throw fail("bad escape");
            }
        }
        throw fail("unterminated string");
    }

    private Number number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(start, i);
        try {
            return t.matches("-?\\d+") ? (Number) Long.parseLong(t) : (Number) Double.parseDouble(t);
        } catch (NumberFormatException e) { throw fail("bad value"); }
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
    private boolean take(char c) { ws(); if (peek(c)) { i++; return true; } return false; }
    private void expect(char c) { if (!take(c)) throw fail("expected '" + c + "'"); }
    private IllegalArgumentException fail(String why) { return new IllegalArgumentException("JSON: " + why + " at " + i); }
}
