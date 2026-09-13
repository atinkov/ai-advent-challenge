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
 *
 * Day 9: history compression. The last AGENT_KEEP_RECENT messages are kept as-is;
 * once the unsummarized part reaches AGENT_SUMMARY_BATCH messages it is folded
 * into a rolling summary (via the same model). The summary is stored separately
 * and sent instead of the full history. Toggle with AGENT_COMPRESS (default on).
 */
public final class LlmAgent {

    private static final String SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай кратко и по делу.";
    private static final String DEFAULT_CONTEXT_FILE = "agent-context.json";
    private static final int DEFAULT_KEEP_RECENT = 6;
    private static final int DEFAULT_SUMMARY_BATCH = 10;
    private static final String SUMMARY_INSTRUCTION =
            "Сожми фрагмент диалога в 3-6 предложений. Сохрани факты о пользователе "
            + "(имя, город, предпочтения, числа, имена), решения и открытые вопросы. "
            + "Ответ — только сам пересказ, без вступлений.";

    private final LlmClient.Config config;
    private final HttpClient http;
    private final Path contextFile;
    private final boolean compressionEnabled;
    private final int keepRecent;
    private final int summaryBatch;
    private final List<Map<String, String>> history = new ArrayList<>();

    private String summary;
    private int totalTurns;
    private int compressions;
    private int sessionPromptTokens;
    private int sessionCompletionTokens;
    private int maxPromptTokens;
    private LlmClient.Usage lastUsage;

    public LlmAgent(LlmClient.Config config) {
        this(config,
                parseAgentBool("AGENT_COMPRESS", true),
                parseAgentInt("AGENT_KEEP_RECENT", DEFAULT_KEEP_RECENT),
                parseAgentInt("AGENT_SUMMARY_BATCH", DEFAULT_SUMMARY_BATCH),
                Path.of(LlmClient.env("AGENT_CONTEXT_FILE", DEFAULT_CONTEXT_FILE)));
    }

    /**
     * Creates the agent with explicit compression settings and context file.
     * Day demos that run several agents in one process (e.g. Task9's
     * with/without-compression comparison) use this; Task6 uses the
     * env-based constructor above.
     */
    public LlmAgent(LlmClient.Config config, boolean compressionEnabled, int keepRecent,
                    int summaryBatch, Path contextFile) {
        this.config = config;
        this.http = HttpClient.newHttpClient();
        this.contextFile = contextFile;
        this.compressionEnabled = compressionEnabled;
        this.keepRecent = keepRecent;
        this.summaryBatch = summaryBatch;
        if (!load()) {
            reset();
        }
    }

    /** Returns the model answer, or null if the server rejected the request (dialogue unchanged). */
    public String ask(String userMessage) throws Exception {
        history.add(LlmClient.message("user", userMessage));
        JsonNode response;
        try {
            response = LlmClient.send(http, config, buildRequest(), null, null, null);
        } catch (LlmClient.RequestException e) {
            System.err.println("Ошибка: сервер отклонил запрос (HTTP " + e.status() + "):\n" + e.body());
            return null;
        }
        String answer = LlmClient.content(response);
        history.add(LlmClient.message("assistant", answer));
        lastUsage = LlmClient.usage(response);
        accumulate(lastUsage);
        totalTurns++;
        compressIfNeeded();
        save();
        return answer;
    }

    public void reset() {
        history.clear();
        history.add(LlmClient.message("system", SYSTEM_PROMPT));
        summary = null;
        totalTurns = 0;
        compressions = 0;
        sessionPromptTokens = 0;
        sessionCompletionTokens = 0;
        maxPromptTokens = 0;
        lastUsage = null;
        save();
    }

    public int turnCount() {
        return totalTurns;
    }

    public int compressionsCount() {
        return compressions;
    }

    public boolean compressionEnabled() {
        return compressionEnabled;
    }

    public int keepRecent() {
        return keepRecent;
    }

    public int summaryBatch() {
        return summaryBatch;
    }

    /** Builds the outgoing request: system + rolling summary + the recent window. */
    private List<Map<String, String>> buildRequest() {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(LlmClient.message("system", SYSTEM_PROMPT));
        if (summary != null && !summary.isBlank()) {
            messages.add(LlmClient.message("user", "Пересказ предыдущего диалога: " + summary));
        }
        for (int i = 1; i < history.size(); i++) {
            messages.add(history.get(i));
        }
        return messages;
    }

    /** Folds the unsummarized part of the history into the rolling summary once it reaches summaryBatch. */
    private void compressIfNeeded() {
        if (!compressionEnabled) {
            return;
        }
        int oldCount = history.size() - 1 - keepRecent;
        if (oldCount < summaryBatch) {
            return;
        }
        List<Map<String, String>> toFold = new ArrayList<>(history.subList(1, 1 + oldCount));
        try {
            String merged = summarize(summary, toFold);
            history.subList(1, 1 + oldCount).clear();
            summary = merged;
            compressions++;
            System.out.println("Агент: история сжата: " + oldCount
                    + " сообщений → пересказ (" + LlmClient.estimateTokens(merged) + " токенов).");
        } catch (Exception e) {
            System.err.println("Внимание: не удалось сжать историю, оставляю сообщения как есть: "
                    + e.getMessage());
        }
    }

    private String summarize(String previous, List<Map<String, String>> fragment) throws Exception {
        StringBuilder prompt = new StringBuilder(SUMMARY_INSTRUCTION).append("\n");
        if (previous != null && !previous.isBlank()) {
            prompt.append("Предыдущий пересказ (объедини с фрагментом в один): ").append(previous).append("\n");
        }
        prompt.append("Фрагмент диалога:\n");
        for (Map<String, String> message : fragment) {
            prompt.append(message.get("role")).append(": ").append(message.get("content")).append("\n");
        }
        JsonNode response = LlmClient.send(http, config,
                List.of(LlmClient.message("user", prompt.toString())), null, null, null);
        accumulate(LlmClient.usage(response));
        return LlmClient.content(response).trim();
    }

    private void accumulate(LlmClient.Usage usage) {
        if (usage.promptTokens() > 0) {
            sessionPromptTokens += usage.promptTokens();
            maxPromptTokens = Math.max(maxPromptTokens, usage.promptTokens());
        }
        if (usage.completionTokens() > 0) {
            sessionCompletionTokens += usage.completionTokens();
        }
    }

    private static boolean parseAgentBool(String name, boolean defaultValue) {
        String raw = LlmClient.env(name, "").trim();
        if (raw.isEmpty()) {
            return defaultValue;
        }
        if (raw.equals("1") || raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("on")) {
            return true;
        }
        if (raw.equals("0") || raw.equalsIgnoreCase("false") || raw.equalsIgnoreCase("off")) {
            return false;
        }
        System.err.println(name + ": ожидалось true/false/1/0/on/off, получено: " + raw);
        System.exit(2);
        return defaultValue;
    }

    private static int parseAgentInt(String name, int defaultValue) {
        String raw = LlmClient.env(name, "");
        if (raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.err.println(name + ": ожидалось число, получено: " + raw);
            System.exit(2);
            return defaultValue;
        }
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
        int tokens = summary == null || summary.isBlank() ? 0 : LlmClient.estimateTokens(summary);
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
                totalTurns = statsNode.path("turns").asInt(0);
            }
            if (root.isObject() && root.path("summary").isTextual()) {
                summary = root.path("summary").asText();
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
            doc.put("version", 3);
            doc.put("history", history);
            doc.put("summary", summary);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("prompt_tokens", sessionPromptTokens);
            stats.put("completion_tokens", sessionCompletionTokens);
            stats.put("max_prompt_tokens", maxPromptTokens);
            stats.put("turns", totalTurns);
            doc.put("stats", stats);
            Files.writeString(contextFile, LlmClient.toJson(doc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Внимание: не удалось сохранить контекст (" + contextFile + "): "
                    + e.getMessage());
        }
    }
}
