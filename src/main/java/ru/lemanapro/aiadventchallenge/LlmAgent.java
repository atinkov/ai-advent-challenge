package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 */
public final class LlmAgent {

    private static final String SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай кратко и по делу.";
    private static final String DEFAULT_CONTEXT_FILE = "agent-context.json";

    private final LlmClient.Config config;
    private final HttpClient http;
    private final Path contextFile;
    private final List<Map<String, String>> history = new ArrayList<>();

    public LlmAgent(LlmClient.Config config) {
        this.config = config;
        this.http = HttpClient.newHttpClient();
        this.contextFile = Path.of(LlmClient.env("AGENT_CONTEXT_FILE", DEFAULT_CONTEXT_FILE));
        if (!load()) {
            reset();
        }
    }

    public String ask(String userMessage) throws Exception {
        history.add(LlmClient.message("user", userMessage));
        JsonNode response = LlmClient.chat(http, config, List.copyOf(history), null, null);
        String answer = LlmClient.content(response);
        history.add(LlmClient.message("assistant", answer));
        save();
        return answer;
    }

    public void reset() {
        history.clear();
        history.add(LlmClient.message("system", SYSTEM_PROMPT));
        save();
    }

    public int turnCount() {
        return (history.size() - 1) / 2;
    }

    private boolean load() {
        if (!Files.isRegularFile(contextFile)) {
            return false;
        }
        try {
            JsonNode root = LlmClient.parseJson(Files.readString(contextFile, StandardCharsets.UTF_8));
            if (!root.isArray() || root.isEmpty()) {
                return false;
            }
            List<Map<String, String>> loaded = new ArrayList<>();
            for (JsonNode node : root) {
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
            return true;
        } catch (Exception e) {
            System.err.println("Внимание: не удалось прочитать контекст (" + contextFile + "): "
                    + e.getMessage() + ". Начинаю с чистого диалога.");
            return false;
        }
    }

    private void save() {
        try {
            Files.writeString(contextFile, LlmClient.toJson(history), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Внимание: не удалось сохранить контекст (" + contextFile + "): "
                    + e.getMessage());
        }
    }
}
