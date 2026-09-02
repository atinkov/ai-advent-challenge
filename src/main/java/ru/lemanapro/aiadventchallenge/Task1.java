package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal CLI LLM client.
 * <p>
 * Sends a prompt to an OpenAI-compatible /chat/completions endpoint and prints
 * the assistant reply to the console. Defaults to the Hindsight gpustack endpoint.
 * <p>
 * Environment (all optional, defaults in parentheses):
 * HINDSIGHT_API_LLM_PROVIDER   openai-compatible provider ("openai")
 * HINDSIGHT_API_LLM_BASE_URL   endpoint base (https://gpustack.data.lmru.tech/v1)
 * HINDSIGHT_API_LLM_MODEL      model id (qwen3.6-27b)
 * HINDSIGHT_API_LLM_API_KEY    auth key (fallback: OPENAI_API_KEY)
 * <p>
 * Usage:
 * mvn -q exec:java
 * mvn -q exec:java -Dexec.args="What is the capital of France?"
 */
public class Task1 {

    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_BASE_URL = "https://gpustack.data.lmru.tech/v1";
    private static final String DEFAULT_MODEL = "qwen3.6-27b";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String provider = env("HINDSIGHT_API_LLM_PROVIDER", DEFAULT_PROVIDER);
        if (!provider.equalsIgnoreCase("openai")) {
            System.err.println("ERROR: only provider 'openai' (OpenAI-compatible) is supported, got '" + provider + "'.");
            System.exit(2);
            return;
        }

        String apiKey = firstNonBlank(
                System.getenv("LLM_API_KEY"),
                System.getenv("OPENAI_API_KEY"));
        if (apiKey == null) {
            System.err.println("ERROR: no API key. Set HINDSIGHT_API_LLM_API_KEY (or OPENAI_API_KEY).");
            System.err.println("Usage: mvn -q exec:java [-Dexec.args=\"prompt words\"]");
            System.exit(2);
            return;
        }

        String baseUrl = env("HINDSIGHT_API_LLM_BASE_URL", DEFAULT_BASE_URL);
        String model = env("HINDSIGHT_API_LLM_MODEL", DEFAULT_MODEL);
        String prompt = args.length > 0 ? String.join(" ", args) : "Say hello in one short sentence." +
                "\n" +
                "Tell me, which model am I using right now?";

        String body = MAPPER.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        System.out.println("> " + prompt);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("HTTP " + response.statusCode() + ":\n" + response.body());
                System.exit(1);
                return;
            }
            JsonNode root = MAPPER.readTree(response.body());
            System.out.println(root.path("choices").path(0).path("message").path("content").asText());
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
