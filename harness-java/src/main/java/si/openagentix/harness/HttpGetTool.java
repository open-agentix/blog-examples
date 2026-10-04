package si.openagentix.harness;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** http_get: plain GET. Which hosts are allowed is NOT decided here but by the policy gate. */
public record HttpGetTool(HttpClient client, int maxBytes) implements Tool {
    public HttpGetTool() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build(), 65536);
    }

    public String name() { return "http_get"; }
    public String description() { return "Fetch a URL with HTTP GET."; }
    public Map<String, Object> schema() {
        return Json.parseObject("""
            {"type":"object","properties":{"url":{"type":"string","maxLength":2048}},"required":["url"],"additionalProperties":false}""");
    }

    public String run(Map<String, Object> args) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create((String) args.get("url"))).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = res.body();
        return res.statusCode() + "\n" + body.substring(0, Math.min(body.length(), maxBytes));
    }
}
