package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 */
public final class LlmClient {

    public static final String DEFAULT_BASE_URL = "https://gpustack.data.lmru.tech/v1";
    public static final String DEFAULT_MODEL = "qwen3.8-27b";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: no API key. Set LLM_API_KEY.");
            System.exit(2);
        }
        return new Config(
                apiKey,
                env("HINDSIGHT_API_LLM_BASE_URL", DEFAULT_BASE_URL),
                env("HINDSIGHT_API_LLM_MODEL", DEFAULT_MODEL));
    }

    /**
     * Sends one chat completion request.
     *
     * @param maxTokens optional max_tokens API parameter (null = no limit)
     * @param stop      optional stop sequences (null = none)
     */
    public static JsonNode chat(HttpClient client, Config cfg, List<Map<String, String>> messages,
                                Integer maxTokens, List<String> stop) throws Exception {
        return chat(client, cfg, messages, maxTokens, stop, null);
    }

    /**
     * Sends one chat completion request.
     *
     * @param maxTokens   optional max_tokens API parameter (null = no limit)
     * @param stop        optional stop sequences (null = none)
     * @param temperature optional sampling temperature (null = server default)
     */
    public static JsonNode chat(HttpClient client, Config cfg, List<Map<String, String>> messages,
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("HTTP " + response.statusCode() + ":\n" + response.body());
            System.exit(1);
        }
        return MAPPER.readTree(response.body());
    }

    public static Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
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
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
