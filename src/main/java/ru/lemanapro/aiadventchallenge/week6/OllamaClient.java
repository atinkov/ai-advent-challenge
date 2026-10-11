package ru.lemanapro.aiadventchallenge.week6;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Week 6 shared: thin client for the NATIVE Ollama HTTP API (http://localhost:11434/api/...).
 *
 * The OpenAI-compatible part of Ollama (/v1/chat/completions) is served by the project's
 * {@link LlmClient} — see {@link #openAiConfig(String)}; this class covers what only the native API
 * gives: server version, installed models with their metadata, models loaded into memory,
 * pulling a model, and chat with timing metrics (tokens, load / prompt / generation time).
 *
 * Env: OLLAMA_HOST (default http://localhost:11434; "127.0.0.1:11434" without a scheme is accepted).
 */
public final class OllamaClient implements AutoCloseable {

    public static final String DEFAULT_HOST = "http://localhost:11434";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    public OllamaClient(String baseUrl) {
        this.baseUrl = normalize(baseUrl);
    }

    public static OllamaClient fromEnv() {
        return new OllamaClient(LlmClient.env("OLLAMA_HOST", DEFAULT_HOST));
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Config for {@link LlmClient#send}: Ollama's OpenAI-compatible endpoint needs no key, any string works. */
    public LlmClient.Config openAiConfig(String model) {
        return new LlmClient.Config("ollama", baseUrl + "/v1", model);
    }

    // ---------------------------------------------------------------- server / models

    /** GET /api/version; throws ConnectException when nothing listens on the port. */
    public String version() throws Exception {
        return get("/api/version", Duration.ofSeconds(5)).path("version").asText("?");
    }

    /** true if the server answers at all. */
    public boolean isUp() {
        try {
            version();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Installed model (GET /api/tags). */
    public record ModelInfo(String name, long sizeBytes, String family, String parameterSize,
                            String quantization, String format, String modifiedAt) {
    }

    public List<ModelInfo> models() throws Exception {
        List<ModelInfo> out = new ArrayList<>();
        for (JsonNode m : get("/api/tags", Duration.ofSeconds(10)).path("models")) {
            JsonNode d = m.path("details");
            out.add(new ModelInfo(m.path("name").asText(), m.path("size").asLong(),
                    d.path("family").asText("?"), d.path("parameter_size").asText("?"),
                    d.path("quantization_level").asText("?"), d.path("format").asText("?"),
                    m.path("modified_at").asText("")));
        }
        return out;
    }

    /** Model currently loaded into memory (GET /api/ps). sizeVram = 0 means it runs on the CPU. */
    public record LoadedModel(String name, long sizeBytes, long sizeVram, int contextLength, String expiresAt) {
    }

    public List<LoadedModel> loaded() throws Exception {
        List<LoadedModel> out = new ArrayList<>();
        for (JsonNode m : get("/api/ps", Duration.ofSeconds(10)).path("models")) {
            out.add(new LoadedModel(m.path("name").asText(), m.path("size").asLong(),
                    m.path("size_vram").asLong(), m.path("context_length").asInt(0), m.path("expires_at").asText("")));
        }
        return out;
    }

    /** "qwen2.5:1.5b" matches an installed "qwen2.5:1.5b"; "qwen2.5" matches "qwen2.5:latest". */
    public ModelInfo find(String model) throws Exception {
        String wanted = model.contains(":") ? model : model + ":latest";
        for (ModelInfo m : models()) {
            if (m.name().equals(wanted) || m.name().equals(model)) {
                return m;
            }
        }
        return null;
    }

    /** POST /api/pull (blocking, no progress stream). Same as `ollama pull <model>`. */
    public void pull(String model) throws Exception {
        JsonNode r = post("/api/pull", Map.of("model", model, "stream", false), Duration.ofMinutes(30));
        if (!"success".equals(r.path("status").asText())) {
            throw new IllegalStateException("pull " + model + ": " + r);
        }
    }

    // ---------------------------------------------------------------- chat

    /**
     * Answer + metrics from the native /api/chat (stream=false). Durations come from the server in ns.
     */
    public record ChatResult(String content, String doneReason, int promptTokens, int outputTokens,
                             long totalNs, long loadNs, long promptEvalNs, long evalNs, long wallMs) {

        /** Generation speed in tokens/s (output tokens / generation time). */
        public double tokensPerSecond() {
            return evalNs <= 0 ? 0 : outputTokens / (evalNs / 1e9);
        }

        public double totalSeconds() {
            return totalNs / 1e9;
        }

        public double loadSeconds() {
            return loadNs / 1e9;
        }
    }

    /**
     * @param numPredict max output tokens (Ollama option num_predict; null = model default)
     * @param temperature null = model default
     * @param seed fixed seed for reproducible answers (null = random)
     */
    public ChatResult chat(String model, List<Map<String, String>> messages,
                           Integer numPredict, Double temperature, Integer seed) throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        if (numPredict != null) {
            options.put("num_predict", numPredict);
        }
        if (temperature != null) {
            options.put("temperature", temperature);
        }
        if (seed != null) {
            options.put("seed", seed);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        if (!options.isEmpty()) {
            body.put("options", options);
        }
        long t0 = System.nanoTime();
        JsonNode r = post("/api/chat", body, Duration.ofMinutes(10));
        long wallMs = (System.nanoTime() - t0) / 1_000_000;
        return new ChatResult(
                r.path("message").path("content").asText(),
                r.path("done_reason").asText("?"),
                r.path("prompt_eval_count").asInt(0),
                r.path("eval_count").asInt(0),
                r.path("total_duration").asLong(0),
                r.path("load_duration").asLong(0),
                r.path("prompt_eval_duration").asLong(0),
                r.path("eval_duration").asLong(0),
                wallMs);
    }

    // ---------------------------------------------------------------- transport

    private JsonNode get(String path, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout).GET().build();
        return send(req);
    }

    private JsonNode post(String path, Object body, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        return send(req);
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new ConnectException("Ollama не отвечает на " + baseUrl + " (запущен ли `ollama serve`?)");
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Ollama " + req.method() + " " + req.uri().getPath()
                    + " → HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readTree(resp.body());
    }

    private static String normalize(String host) {
        String h = host.trim().replaceAll("/+$", "");
        if (!h.startsWith("http://") && !h.startsWith("https://")) {
            h = "http://" + h;
        }
        if (h.matches("https?://0\\.0\\.0\\.0(:\\d+)?")) {
            h = h.replace("0.0.0.0", "127.0.0.1"); // OLLAMA_HOST=0.0.0.0 is a bind address, not a target
        }
        if (!h.matches("https?://[^/]+:\\d+.*")) {
            h = h + ":11434";
        }
        return h;
    }

    @Override
    public void close() {
        http.close();
    }
}
