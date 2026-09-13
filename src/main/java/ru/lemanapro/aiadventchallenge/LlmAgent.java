package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A simple LLM agent: persona, conversation memory, and encapsulated
 * request/response logic on top of LlmClient.
 *
 * The agent is a standalone entity: it owns the system prompt and the dialogue
 * history, and every ask() sends the conversation to the model (multi-turn)
 * and stores the answer in the history.
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
 * Day 9: history compression (FULL strategy only). The last AGENT_KEEP_RECENT
 * messages are kept as-is; once the unsummarized part reaches AGENT_SUMMARY_BATCH
 * messages it is folded into a rolling summary (via the same model). The summary
 * is stored separately and sent instead of the full history. Toggle with
 * AGENT_COMPRESS (default on).
 *
 * Day 10: context strategies (no summary), switched via AGENT_STRATEGY
 * (full | sliding | facts, window size via AGENT_WINDOW):
 *   FULL           — the whole history (optionally with day-9 compression);
 *   SLIDING_WINDOW — only the last N messages are kept, the rest is dropped;
 *   FACTS          — a key-value block of important facts, refreshed by the model
 *                    after every message; the request is facts + last N messages.
 * Plus Branching: saveCheckpoint() / createBranch(name) / switchBranch(name) —
 * independent dialogue branches from a checkpoint; all branches and the
 * checkpoint are persisted in the context file (format v4).
 */
public final class LlmAgent {

    /** Context management strategy (day 10). */
    public enum Strategy {
        FULL, SLIDING_WINDOW, FACTS
    }

    /** Explicit agent settings (day 10); Settings.fromEnv() reads the AGENT_* variables. */
    public record Settings(Strategy strategy, int window, boolean compressionEnabled,
                           int keepRecent, int summaryBatch, Path contextFile) {

        public static Settings fromEnv() {
            String raw = LlmClient.env("AGENT_STRATEGY", "full").trim().toLowerCase(Locale.ROOT);
            Strategy strategy = switch (raw) {
                case "full" -> Strategy.FULL;
                case "sliding", "sliding-window" -> Strategy.SLIDING_WINDOW;
                case "facts", "sticky-facts" -> Strategy.FACTS;
                default -> {
                    System.err.println("AGENT_STRATEGY: ожидалось full/sliding/facts, получено: " + raw);
                    System.exit(2);
                    yield null;
                }
            };
            return new Settings(strategy,
                    parseAgentInt("AGENT_WINDOW", DEFAULT_WINDOW),
                    parseAgentBool("AGENT_COMPRESS", true),
                    parseAgentInt("AGENT_KEEP_RECENT", DEFAULT_KEEP_RECENT),
                    parseAgentInt("AGENT_SUMMARY_BATCH", DEFAULT_SUMMARY_BATCH),
                    Path.of(LlmClient.env("AGENT_CONTEXT_FILE", DEFAULT_CONTEXT_FILE)));
        }
    }

    private static final String SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай кратко и по делу.";
    private static final String DEFAULT_CONTEXT_FILE = "agent-context.json";
    private static final String MAIN_BRANCH = "main";
    private static final int DEFAULT_WINDOW = 6;
    private static final int DEFAULT_KEEP_RECENT = 6;
    private static final int DEFAULT_SUMMARY_BATCH = 10;
    private static final String SUMMARY_INSTRUCTION =
            "Сожми фрагмент диалога в 3-6 предложений. Сохрани факты о пользователе "
            + "(имя, город, предпочтения, числа, имена), решения и открытые вопросы. "
            + "Ответ — только сам пересказ, без вступлений.";
    private static final String FACTS_INSTRUCTION =
            "Веди список важных фактов из диалога в формате ключ-значение: цель, назначение, "
            + "функции, ограничения, предпочтения, решения, договорённости. "
            + "Добавляй новые факты, обновляй изменившиеся, убирай устаревшие. "
            + "Верни только JSON-объект {\"ключ\": \"значение\"} без комментариев и markdown.";

    /** Immutable snapshot of one dialogue branch (history entries are immutable Map.of). */
    private record BranchState(List<Map<String, String>> history, String summary, Map<String, String> facts) {
        BranchState copy() {
            return new BranchState(new ArrayList<>(history), summary, new LinkedHashMap<>(facts));
        }
    }

    private final LlmClient.Config config;
    private final HttpClient http;
    private final Path contextFile;
    private final Strategy strategy;
    private final int window;
    private final boolean compressionEnabled;
    private final int keepRecent;
    private final int summaryBatch;
    private final List<Map<String, String>> history = new ArrayList<>();
    private final Map<String, String> facts = new LinkedHashMap<>();
    private final Map<String, BranchState> branches = new LinkedHashMap<>();

    private String summary;
    private String activeBranch = MAIN_BRANCH;
    private BranchState checkpoint;
    private int totalTurns;
    private int compressions;
    private int factsUpdates;
    private int sessionPromptTokens;
    private int sessionCompletionTokens;
    private int maxPromptTokens;
    private LlmClient.Usage lastUsage;

    public LlmAgent(LlmClient.Config config) {
        this(config, Settings.fromEnv());
    }

    public LlmAgent(LlmClient.Config config, Settings settings) {
        this.config = config;
        this.http = HttpClient.newHttpClient();
        this.contextFile = settings.contextFile();
        this.strategy = settings.strategy();
        this.window = settings.window();
        this.compressionEnabled = settings.compressionEnabled();
        this.keepRecent = settings.keepRecent();
        this.summaryBatch = settings.summaryBatch();
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
        switch (strategy) {
            case SLIDING_WINDOW -> trimSlidingWindow();
            case FACTS -> updateFacts(userMessage, answer);
            case FULL -> compressIfNeeded();
        }
        save();
        return answer;
    }

    public void reset() {
        history.clear();
        history.add(LlmClient.message("system", SYSTEM_PROMPT));
        summary = null;
        facts.clear();
        factsUpdates = 0;
        branches.clear();
        checkpoint = null;
        activeBranch = MAIN_BRANCH;
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

    public Strategy strategy() {
        return strategy;
    }

    /** Human-readable label of the active strategy for UI banners. */
    public String strategyLabel() {
        return switch (strategy) {
            case FULL -> "полная история (full" + (compressionEnabled ? " + сжатие" : "") + ")";
            case SLIDING_WINDOW -> "скользящее окно (sliding, N=" + window + ")";
            case FACTS -> "скользящие факты (facts, окно=" + window + ")";
        };
    }

    public int window() {
        return window;
    }

    public Map<String, String> facts() {
        return new LinkedHashMap<>(facts);
    }

    public int factsUpdatesCount() {
        return factsUpdates;
    }

    public String activeBranchName() {
        return activeBranch;
    }

    public List<String> branchNames() {
        List<String> names = new ArrayList<>();
        names.add(activeBranch);
        names.addAll(branches.keySet());
        return names;
    }

    /** Snapshots the current branch state so new branches can be created from it. */
    public void saveCheckpoint() {
        checkpoint = captureState();
        System.out.println("Агент: checkpoint сохранён (ветка '" + activeBranch + "', "
                + (history.size() - 1) + " сообщений).");
        save();
    }

    /** Creates an independent branch from the last checkpoint. */
    public void createBranch(String name) {
        if (checkpoint == null) {
            System.err.println("Агент: нет checkpoint — сначала saveCheckpoint().");
            return;
        }
        if (name.equals(activeBranch) || branches.containsKey(name)) {
            System.err.println("Агент: ветка '" + name + "' уже есть или это текущая ветка.");
            return;
        }
        branches.put(name, checkpoint.copy());
        System.out.println("Агент: ветка '" + name + "' создана от checkpoint ("
                + (checkpoint.history().size() - 1) + " сообщений).");
        save();
    }

    /** Switches the live dialogue to another branch (the current one is stored first). */
    public void switchBranch(String name) {
        if (name.equals(activeBranch)) {
            return;
        }
        if (!branches.containsKey(name)) {
            System.err.println("Агент: нет ветки '" + name + "'.");
            return;
        }
        branches.put(activeBranch, captureState());
        applyState(branches.remove(name));
        activeBranch = name;
        System.out.println("Агент: переключение на ветку '" + name + "' ("
                + (history.size() - 1) + " сообщений).");
        save();
    }

    /** Builds the outgoing request according to the active strategy. */
    private List<Map<String, String>> buildRequest() {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(LlmClient.message("system", SYSTEM_PROMPT));
        switch (strategy) {
            case SLIDING_WINDOW -> appendRange(messages, 1);
            case FACTS -> {
                if (!facts.isEmpty()) {
                    messages.add(LlmClient.message("user", formatFacts()));
                }
                appendRange(messages, Math.max(1, history.size() - window));
            }
            case FULL -> {
                if (summary != null && !summary.isBlank()) {
                    messages.add(LlmClient.message("user", "Пересказ предыдущего диалога: " + summary));
                }
                appendRange(messages, 1);
            }
        }
        return messages;
    }

    private void appendRange(List<Map<String, String>> messages, int from) {
        for (int i = from; i < history.size(); i++) {
            messages.add(history.get(i));
        }
    }

    private void trimSlidingWindow() {
        while (history.size() - 1 > window) {
            history.remove(1);
        }
    }

    /** Refreshes the key-value facts block via the model; on failure the previous list is kept. */
    private void updateFacts(String userMessage, String answer) {
        StringBuilder prompt = new StringBuilder(FACTS_INSTRUCTION).append("\n");
        prompt.append("Текущий список фактов: ").append(facts.isEmpty() ? "(пусто)" : facts).append("\n");
        prompt.append("Новое сообщение пользователя: ").append(userMessage).append("\n");
        prompt.append("Ответ агента: ").append(answer).append("\n");
        try {
            JsonNode response = LlmClient.send(http, config,
                    List.of(LlmClient.message("user", prompt.toString())), null, null, null);
            accumulate(LlmClient.usage(response));
            Map<String, String> updated = parseFacts(LlmClient.content(response));
            if (updated != null) {
                facts.clear();
                facts.putAll(updated);
                factsUpdates++;
            }
        } catch (Exception e) {
            System.err.println("Внимание: не удалось обновить факты, сохраняю прежний список: "
                    + e.getMessage());
        }
    }

    private static Map<String, String> parseFacts(String raw) throws Exception {
        String json = raw.trim();
        if (json.startsWith("```")) {
            int firstBreak = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstBreak >= 0 && lastFence > firstBreak) {
                json = json.substring(firstBreak + 1, lastFence).trim();
            }
        }
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode node = LlmClient.parseJson(json.substring(start, end + 1));
        if (!node.isObject()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var it = node.fields(); it.hasNext(); ) {
            var entry = it.next();
            result.put(entry.getKey(), entry.getValue().asText());
        }
        return result;
    }

    private String formatFacts() {
        StringBuilder sb = new StringBuilder("Важные факты из диалога (ключ: значение):\n");
        facts.forEach((key, value) -> sb.append("- ").append(key).append(": ").append(value).append("\n"));
        return sb.toString();
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

    private BranchState captureState() {
        return new BranchState(new ArrayList<>(history), summary, new LinkedHashMap<>(facts));
    }

    private void applyState(BranchState state) {
        history.clear();
        history.addAll(state.history());
        summary = state.summary();
        facts.clear();
        facts.putAll(state.facts());
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
        for (String value : facts.values()) {
            tokens += LlmClient.estimateTokens(value);
        }
        return tokens;
    }

    /** Reads v4 (branches + checkpoint) or migrates the legacy v1 array / v2 / v3 formats. */
    private boolean load() {
        if (!Files.isRegularFile(contextFile)) {
            return false;
        }
        try {
            JsonNode root = LlmClient.parseJson(Files.readString(contextFile, StandardCharsets.UTF_8));
            JsonNode branchesNode = root.path("branches");
            if (root.isObject() && root.path("version").asInt(0) >= 4 && branchesNode.isObject()) {
                Map<String, BranchState> loaded = new LinkedHashMap<>();
                for (var it = branchesNode.fields(); it.hasNext(); ) {
                    var entry = it.next();
                    BranchState state = readBranchState(entry.getValue());
                    if (state != null) {
                        loaded.put(entry.getKey(), state);
                    }
                }
                if (loaded.isEmpty()) {
                    return false;
                }
                String active = root.path("active").asText(MAIN_BRANCH);
                if (!loaded.containsKey(active)) {
                    active = loaded.keySet().iterator().next();
                }
                applyState(loaded.remove(active));
                branches.putAll(loaded);
                activeBranch = active;
                JsonNode cp = root.path("checkpoint");
                if (cp.isObject()) {
                    checkpoint = readBranchState(cp);
                }
            } else {
                JsonNode historyNode = root.isArray() ? root : root.path("history");
                if (!historyNode.isArray() || historyNode.isEmpty()) {
                    return false;
                }
                List<Map<String, String>> loadedHistory = new ArrayList<>();
                for (JsonNode node : historyNode) {
                    String role = node.path("role").asText("");
                    String content = node.path("content").asText("");
                    if (role.isEmpty() || content.isEmpty()) {
                        return false;
                    }
                    loadedHistory.add(Map.of("role", role, "content", content));
                }
                if (!"system".equals(loadedHistory.get(0).get("role"))) {
                    return false;
                }
                history.addAll(loadedHistory);
                if (root.isObject() && root.path("summary").isTextual()) {
                    summary = root.path("summary").asText();
                }
            }
            JsonNode statsNode = root.isObject() ? root.path("stats") : null;
            if (statsNode != null && statsNode.isObject()) {
                sessionPromptTokens = statsNode.path("prompt_tokens").asInt(0);
                sessionCompletionTokens = statsNode.path("completion_tokens").asInt(0);
                maxPromptTokens = statsNode.path("max_prompt_tokens").asInt(0);
                totalTurns = statsNode.path("turns").asInt(0);
            }
            return true;
        } catch (Exception e) {
            System.err.println("Внимание: не удалось прочитать контекст (" + contextFile + "): "
                    + e.getMessage() + ". Начинаю с чистого диалога.");
            return false;
        }
    }

    private static BranchState readBranchState(JsonNode node) {
        JsonNode historyNode = node.path("history");
        if (!historyNode.isArray() || historyNode.isEmpty()) {
            return null;
        }
        List<Map<String, String>> history = new ArrayList<>();
        for (JsonNode message : historyNode) {
            String role = message.path("role").asText("");
            String content = message.path("content").asText("");
            if (role.isEmpty() || content.isEmpty()) {
                return null;
            }
            history.add(Map.of("role", role, "content", content));
        }
        if (!"system".equals(history.get(0).get("role"))) {
            return null;
        }
        String branchSummary = node.path("summary").isTextual() ? node.path("summary").asText() : null;
        Map<String, String> branchFacts = new LinkedHashMap<>();
        JsonNode factsNode = node.path("facts");
        if (factsNode.isObject()) {
            for (var it = factsNode.fields(); it.hasNext(); ) {
                var entry = it.next();
                branchFacts.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return new BranchState(history, branchSummary, branchFacts);
    }

    private void save() {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("version", 4);
            Map<String, Object> branchesNode = new LinkedHashMap<>();
            branchesNode.put(activeBranch, branchNode(history, summary, facts));
            for (Map.Entry<String, BranchState> entry : branches.entrySet()) {
                BranchState state = entry.getValue();
                branchesNode.put(entry.getKey(), branchNode(state.history(), state.summary(), state.facts()));
            }
            doc.put("branches", branchesNode);
            doc.put("active", activeBranch);
            if (checkpoint != null) {
                doc.put("checkpoint", branchNode(checkpoint.history(), checkpoint.summary(), checkpoint.facts()));
            }
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

    private static Map<String, Object> branchNode(List<Map<String, String>> history, String summary,
                                                  Map<String, String> facts) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("history", history);
        node.put("summary", summary);
        node.put("facts", facts);
        return node;
    }
}
