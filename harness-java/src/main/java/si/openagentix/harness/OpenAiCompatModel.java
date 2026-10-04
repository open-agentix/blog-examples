package si.openagentix.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Optional adapter for an OpenAI-compatible /chat/completions endpoint.
 * Config comes from the environment; the key is only sent as a header and is never logged or printed.
 */
public final class OpenAiCompatModel implements Model {
    private final String baseUrl, apiKey, model;
    private final HttpClient client = HttpClient.newHttpClient();

    public OpenAiCompatModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.model = model;
    }

    public static OpenAiCompatModel fromEnv(Map<String, String> env) {
        String url = env.get("HARNESS_BASE_URL"), key = env.get("HARNESS_API_KEY"), model = env.get("HARNESS_MODEL");
        if (url == null || key == null || model == null) throw new IllegalStateException("set HARNESS_BASE_URL, HARNESS_API_KEY and HARNESS_MODEL");
        return new OpenAiCompatModel(url, key, model);
    }

    @Override public String toString() { return "OpenAiCompatModel[" + baseUrl + ", " + model + "]"; } // no key

    public Reply next(List<Message> messages, List<Tool> tools, Duration timeout) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(OpenAiCompatModel::toWire).toList());
        body.put("tools", tools.stream().map(t -> Map.of("type", "function", "function",
            Map.of("name", t.name(), "description", t.description(), "parameters", t.schema()))).toList());
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(timeout)
            .header("content-type", "application/json").header("authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IOException("model endpoint returned HTTP " + res.statusCode()); // never echo headers or body
        return parse(Json.parseObject(res.body()));
    }

    @SuppressWarnings("unchecked")
    static Reply parse(Map<String, Object> body) {
        Map<String, Object> msg = Map.of();
        if (body.get("choices") instanceof List<?> choices && !choices.isEmpty()) msg = (Map<String, Object>) ((Map<String, Object>) choices.getFirst()).getOrDefault("message", Map.of());
        List<Call> calls = new ArrayList<>();
        if (msg.get("tool_calls") instanceof List<?> tcs) {
            for (Object o : tcs) {
                Map<String, Object> fn = (Map<String, Object>) ((Map<String, Object>) o).get("function");
                Map<String, Object> args = null; // unparsable arguments stay null, fail validation and are denied
                try { args = Json.parseObject((String) fn.get("arguments")); } catch (RuntimeException ignored) { }
                calls.add(new Call((String) ((Map<String, Object>) o).get("id"), (String) fn.get("name"), args));
            }
        }
        Object usage = body.get("usage");
        int tokens = usage instanceof Map<?, ?> u && u.get("total_tokens") instanceof Number n ? n.intValue() : 0;
        return new Reply(msg.get("content") instanceof String s ? s : "", calls, tokens);
    }

    private static Map<String, Object> toWire(Message m) {
        if (m.role().equals("tool")) return Map.of("role", "tool", "tool_call_id", m.toolCallId(), "content", m.content());
        if (m.calls().isEmpty()) return Map.of("role", m.role(), "content", m.content());
        return Map.of("role", "assistant", "content", m.content(), "tool_calls", m.calls().stream().map(c -> Map.of(
            "id", c.id(), "type", "function", "function", Map.of("name", c.name(), "arguments", Json.write(c.args())))).toList());
    }
}
