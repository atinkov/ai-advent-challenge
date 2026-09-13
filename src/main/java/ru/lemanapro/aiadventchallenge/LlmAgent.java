package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A simple LLM agent: persona, conversation memory, and encapsulated
 * request/response logic on top of LlmClient.
 *
 * The agent is a standalone entity: it owns the system prompt and the dialogue
 * history, and every ask() sends the whole conversation to the model (multi-turn)
 * and stores the answer in the history. No history limit.
 *
 * Day 7: the history is persisted to a JSON file (agent-context.json, override
 * with AGENT_CONTEXT_FILE) after every turn and restored on construction, so the
 * dialogue continues across restarts as if the agent never stopped.
 *
 * Day 8: token accounting. Exact per-request counts come from the API usage
 * field (prompt = the whole request, completion = the model answer); session
 * totals and the peak request size are tracked and persisted in the context
 * file. A rejected request (e.g. context overflow) returns null without
 * changing the dialogue.
 */
public final class LlmAgent {

    private static final String SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай кратко и по делу.";
    private static final String DEFAULT_CONTEXT_FILE = "agent-context.json";

    private final LlmClient.Config config;
    private final HttpClient http;
    private final Path contextFile;
    private final List<Map<String, String>> history = new ArrayList<>();

    private int sessionPromptTokens;
    private int sessionCompletionTokens;
    private int maxPromptTokens;
    private LlmClient.Usage lastUsage;

    public LlmAgent(LlmClient.Config config) {
        this.config = config;
        this.http = HttpClient.newHttpClient();
        this.contextFile = Path.of(LlmClient.env("AGENT_CONTEXT_FILE", DEFAULT_CONTEXT_FILE));
        if (!load()) {
            reset();
        }
    }

    /** Returns the model answer, or null if the server rejected the request (dialogue unchanged). */
    public String ask(String userMessage) throws Exception {
        history.add(LlmClient.message("user", userMessage));
        JsonNode response;
        try {
            response = LlmClient.send(http, config, List.copyOf(history), null, null, null);
        } catch (LlmClient.RequestException e) {
            System.err.println("Ошибка: сервер отклонил запрос (HTTP " + e.status() + "):\n" + e.body());
            return null;
        }
        String answer = LlmClient.content(response);
        history.add(LlmClient.message("assistant", answer));
        lastUsage = LlmClient.usage(response);
        if (lastUsage.promptTokens() > 0) {
            sessionPromptTokens += lastUsage.promptTokens();
            maxPromptTokens = Math.max(maxPromptTokens, lastUsage.promptTokens());
        }
        if (lastUsage.completionTokens() > 0) {
            sessionCompletionTokens += lastUsage.completionTokens();
        }
        save();
        return answer;
    }

    public void reset() {
        history.clear();
        history.add(LlmClient.message("system", SYSTEM_PROMPT));
        sessionPromptTokens = 0;
        sessionCompletionTokens = 0;
        maxPromptTokens = 0;
        lastUsage = null;
        save();
    }

    public int turnCount() {
        return (history.size() - 1) / 2;
    }

    public LlmClient.Usage lastUsage() {
        return lastUsage;
    }

    public int sessionPromptTokens() {
        return sessionPromptTokens;
    }

    public int sessionCompletionTokens() {
        return sessionCompletionTokens;
    }

    public int maxPromptTokens() {
        return maxPromptTokens;
    }

    public int historyTokensEstimate() {
        int tokens = 0;
        for (Map<String, String> message : history) {
            tokens += LlmClient.estimateTokens(message.get("content"));
        }
        return tokens;
    }

    private boolean load() {
        if (!Files.isRegularFile(contextFile)) {
            return false;
        }
        try {
            JsonNode root = LlmClient.parseJson(Files.readString(contextFile, StandardCharsets.UTF_8));
            JsonNode historyNode = root.isArray() ? root : root.path("history");
            JsonNode statsNode = root.isObject() ? root.path("stats") : null;
            if (!historyNode.isArray() || historyNode.isEmpty()) {
                return false;
            }
            List<Map<String, String>> loaded = new ArrayList<>();
            for (JsonNode node : historyNode) {
                String role = node.path("role").asText("");
                String content = node.path("content").asText("");
                if (role.isEmpty() || content.isEmpty()) {
                    return false;
                }
                loaded.add(Map.of("role", role, "content", content));
            }
            if (!"system".equals(loaded.get(0).get("role"))) {
                return false;
            }
            history.addAll(loaded);
            if (statsNode != null && statsNode.isObject()) {
                sessionPromptTokens = statsNode.path("prompt_tokens").asInt(0);
                sessionCompletionTokens = statsNode.path("completion_tokens").asInt(0);
                maxPromptTokens = statsNode.path("max_prompt_tokens").asInt(0);
            }
            return true;
        } catch (Exception e) {
            System.err.println("Внимание: не удалось прочитать контекст (" + contextFile + "): "
                    + e.getMessage() + ". Начинаю с чистого диалога.");
            return false;
        }
    }

    private void save() {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("version", 2);
            doc.put("history", history);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("prompt_tokens", sessionPromptTokens);
            stats.put("completion_tokens", sessionCompletionTokens);
            stats.put("max_prompt_tokens", maxPromptTokens);
            doc.put("stats", stats);
            Files.writeString(contextFile, LlmClient.toJson(doc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Внимание: не удалось сохранить контекст (" + contextFile + "): "
                    + e.getMessage());
        }
    }
}
