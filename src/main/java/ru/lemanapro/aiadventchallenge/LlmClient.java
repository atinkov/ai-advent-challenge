package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared OpenAI-compatible chat client and env config.
 *
 * Environment (user's canonical names, no fallbacks):
 *   HINDSIGHT_API_LLM_PROVIDER   "openai" (OpenAI-compatible)
 *   HINDSIGHT_API_LLM_BASE_URL   https://gpustack.data.lmru.tech/v1
 *   HINDSIGHT_API_LLM_MODEL      qwen3.8-27b
 *   LLM_API_KEY                  auth key
 *
 * Values are resolved in this order: process environment, then the .env file
 * in the working directory, then the defaults above.
 */
public final class LlmClient {

    public static final String DEFAULT_BASE_URL = "https://gpustack.data.lmru.tech/v1";
    public static final String DEFAULT_MODEL = "qwen3.8-27b";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> DOTENV = loadDotenv(Path.of(".env"));

    public record Config(String apiKey, String baseUrl, String model) {
    }

    private LlmClient() {
    }

    /** Reads env config; exits with an error message if invalid. */
    public static Config fromEnv() {
        String provider = env("HINDSIGHT_API_LLM_PROVIDER", "openai");
        if (!provider.equalsIgnoreCase("openai")) {
            System.err.println("ERROR: only provider 'openai' (OpenAI-compatible) is supported, got '" + provider + "'.");
            System.exit(2);
        }
        String apiKey = lookup("LLM_API_KEY");
        if (apiKey == null) {
            System.err.println("ERROR: no API key. Set LLM_API_KEY (process env or .env).");
            System.exit(2);
        }
        return new Config(
                apiKey,
                env("HINDSIGHT_API_LLM_BASE_URL", DEFAULT_BASE_URL),
                env("HINDSIGHT_API_LLM_MODEL", DEFAULT_MODEL));
    }

    /** Thrown when the API returns a non-200 status. */
    public static final class RequestException extends Exception {
        private final int status;
        private final String body;

        RequestException(int status, String body) {
            super("HTTP " + status);
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }

    /**
     * Sends one chat completion request; throws RequestException on a non-200 status.
     *
     * @param maxTokens   optional max_tokens API parameter (null = no limit)
     * @param stop        optional stop sequences (null = none)
     * @param temperature optional sampling temperature (null = server default)
     */
    public static JsonNode send(HttpClient client, Config cfg, List<Map<String, String>> messages,
                                Integer maxTokens, List<String> stop, Double temperature) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("messages", messages);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (stop != null) {
            body.put("stop", stop);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        return post(client, cfg, body);
    }

    /**
     * Chat completion with OpenAI-style function calling (day 17+).
     * Messages are free-form maps because tool calling needs more than role/content:
     * assistant messages carry "tool_calls", tool results are {"role":"tool","tool_call_id",...}.
     *
     * @param tools OpenAI "tools" array ([{type:function, function:{name, description, parameters}}]); null/empty = none
     */
    public static JsonNode sendWithTools(HttpClient client, Config cfg, List<Map<String, Object>> messages,
                                         List<Map<String, Object>> tools, Double temperature) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        return post(client, cfg, body);
    }

    private static JsonNode post(HttpClient client, Config cfg, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RequestException(response.statusCode(), response.body());
        }
        return MAPPER.readTree(response.body());
    }

    /** Sends one request; on a non-200 status prints the error and exits (day-class behavior). */
    public static JsonNode chat(HttpClient client, Config cfg, List<Map<String, String>> messages,
                                Integer maxTokens, List<String> stop) throws Exception {
        return chat(client, cfg, messages, maxTokens, stop, null);
    }

    public static JsonNode chat(HttpClient client, Config cfg, List<Map<String, String>> messages,
                                Integer maxTokens, List<String> stop, Double temperature) throws Exception {
        try {
            return send(client, cfg, messages, maxTokens, stop, temperature);
        } catch (RequestException e) {
            System.err.println("HTTP " + e.status() + ":\n" + e.body());
            System.exit(1);
            throw e;
        }
    }

    /** Rough local estimate (chars / 2.5) — for display only; the exact count comes from the API usage. */
    public static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 2.5));
    }

    /**
     * Builds the HttpClient used for LLM calls. Normal behavior is unchanged
     * (HttpClient.newHttpClient()); when the env/​.env flag LLM_INSECURE_TLS is
     * true/1/on, TLS certificate validation is switched off entirely.
     *
     * This exists only as a stopgap for a broken/expired server certificate on the
     * LLM gateway (seen on gpustack.data.lmru.tech) — it disables a real security
     * check, so use it only for a one-off local run, e.g.
     *   LLM_INSECURE_TLS=1 mvn -q exec:java -PtaskN
     * never leave it set in a shared .env or in anything that talks to servers
     * outside your own trusted network.
     */
    public static HttpClient newHttpClient() {
        if (!parseBool("LLM_INSECURE_TLS", false)) {
            return HttpClient.newHttpClient();
        }
        System.err.println("ВНИМАНИЕ: LLM_INSECURE_TLS включён — проверка TLS-сертификата сервера отключена.");
        try {
            TrustManager[] trustAll = {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            return HttpClient.newBuilder().sslContext(sslContext).build();
        } catch (Exception e) {
            throw new RuntimeException("Не удалось создать HttpClient с отключённой проверкой TLS", e);
        }
    }

    private static boolean parseBool(String name, boolean defaultValue) {
        String raw = env(name, "").trim();
        if (raw.isEmpty()) {
            return defaultValue;
        }
        return raw.equals("1") || raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("on");
    }

    public static Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    public static String toJson(Object value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode parseJson(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    public static String content(JsonNode response) {
        return response.path("choices").path(0).path("message").path("content").asText();
    }

    public static String finishReason(JsonNode response) {
        return response.path("choices").path(0).path("finish_reason").asText("?");
    }

    /** Token usage reported by the API. A value of -1 means the field was absent. */
    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    }

    /** Reads `usage` from the API response (-1 for fields the server did not return). */
    public static Usage usage(JsonNode response) {
        var u = response.path("usage");
        return new Usage(
                u.path("prompt_tokens").asInt(-1),
                u.path("completion_tokens").asInt(-1),
                u.path("total_tokens").asInt(-1));
    }

    public static String env(String name, String defaultValue) {
        String value = lookup(name);
        return value == null ? defaultValue : value;
    }

    /** Resolution order: process env, then .env, else null. */
    private static String lookup(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            return value;
        }
        String fromFile = DOTENV.get(name);
        return fromFile == null || fromFile.isBlank() ? null : fromFile;
    }

    /** Parses KEY=value lines; skips blanks/# comments; strips one pair of surrounding quotes. */
    private static Map<String, String> loadDotenv(Path path) {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            System.err.println("ERROR: cannot read " + path + ": " + e.getMessage());
        }
        return values;
    }
}
