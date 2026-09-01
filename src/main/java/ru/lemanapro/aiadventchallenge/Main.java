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
 * Advent task: the same question with two levels of response control.
 *
 * Run 1 — bare request (no constraints).
 * Run 2 — the same question plus:
 *   - explicit response format description (system instruction),
 *   - max_tokens limit,
 *   - stop sequence (###STOP###) + instruction to end with it.
 * Prints both answers and a short comparison.
 *
 * Environment (defaults in parentheses):
 *   HINDSIGHT_API_LLM_PROVIDER   "openai" (OpenAI-compatible)
 *   HINDSIGHT_API_LLM_BASE_URL   https://gpustack.data.lmru.tech/v1
 *   HINDSIGHT_API_LLM_MODEL      qwen3.6-27b
 *   LLM_API_KEY                  auth key
 *
 * Usage:
 *   mvn -q exec:java
 *   mvn -q exec:java -Dexec.args="Ваш вопрос"
 */
public final class Main {

    private static final String DEFAULT_BASE_URL = "https://gpustack.data.lmru.tech/v1";
    private static final String DEFAULT_MODEL = "qwen3.6-27b";
    private static final String STOP_SEQUENCE = "###STOP###";
    private static final int MAX_TOKENS = 100;
    private static final String DEFAULT_QUESTION = "Что такое квантовые компьютеры?";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String provider = env("HINDSIGHT_API_LLM_PROVIDER", "openai");
        if (!provider.equalsIgnoreCase("openai")) {
            System.err.println("ERROR: only provider 'openai' (OpenAI-compatible) is supported, got '" + provider + "'.");
            System.exit(2);
            return;
        }

        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: no API key. Set LLM_API_KEY.");
            System.err.println("Usage: mvn -q exec:java [-Dexec.args=\"prompt words\"]");
            System.exit(2);
            return;
        }

        String baseUrl = env("HINDSIGHT_API_LLM_BASE_URL", DEFAULT_BASE_URL);
        String model = env("HINDSIGHT_API_LLM_MODEL", DEFAULT_MODEL);
        String question = args.length > 0 ? String.join(" ", args) : DEFAULT_QUESTION;

        System.out.println("Вопрос: " + question);
        System.out.println("Модель: " + model + " @ " + baseUrl);

        try (HttpClient client = HttpClient.newHttpClient()) {
            // --- 1) Без ограничений: только вопрос, без служебных параметров ---
            System.out.println("\n=== 1) БЕЗ ограничений ===");
            JsonNode bare = chat(client, apiKey, baseUrl, model,
                    List.of(message("user", question)), null, null);
            String bareText = content(bare);
            System.out.println(bareText);
            System.out.println("[finish_reason=" + finishReason(bare) + ", символов: " + bareText.length() + "]");

            // --- 2) С ограничениями: формат ответа + max_tokens + stop-последовательность ---
            String formatInstruction = "Формат ответа: ровно один короткий абзац (не более 2 предложений), "
                    + "без вступлений, списков и комментариев. Заверши ответ маркером " + STOP_SEQUENCE + ".";
            System.out.println("\n=== 2) С ограничениями (формат + max_tokens=" + MAX_TOKENS + " + stop=[" + STOP_SEQUENCE + "]) ===");
            System.out.println("Инструкция о формате: " + formatInstruction);
            JsonNode controlled = chat(client, apiKey, baseUrl, model,
                    List.of(message("system", formatInstruction), message("user", question)),
                    MAX_TOKENS, List.of(STOP_SEQUENCE));
            String controlledText = content(controlled);
            System.out.println(controlledText);
            System.out.println("[finish_reason=" + finishReason(controlled) + ", символов: " + controlledText.length() + "]");

            // --- Сравнение ---
            System.out.println("\n=== Сравнение ===");
            System.out.println("Без ограничений:  " + bareText.length() + " симв., finish_reason=" + finishReason(bare));
            System.out.println("С ограничениями:  " + controlledText.length() + " симв., finish_reason=" + finishReason(controlled));
            System.out.println("Экономия: " + Math.max(0, bareText.length() - controlledText.length()) + " симв.");
        }
    }

    /**
     * Sends one chat completion request.
     *
     * @param maxTokens optional max_tokens API parameter (null = no limit)
     * @param stop      optional stop sequences (null = none)
     */
    private static JsonNode chat(HttpClient client, String apiKey, String baseUrl, String model,
                                 List<Map<String, String>> messages, Integer maxTokens, List<String> stop)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (stop != null) {
            body.put("stop", stop);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
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

    private static Map<String, String> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private static String content(JsonNode response) {
        return response.path("choices").path(0).path("message").path("content").asText();
    }

    private static String finishReason(JsonNode response) {
        return response.path("choices").path(0).path("finish_reason").asText("?");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
