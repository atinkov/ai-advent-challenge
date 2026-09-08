package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
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
 */
public final class LlmAgent {

    private static final String SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай кратко и по делу.";

    private final LlmClient.Config config;
    private final HttpClient http;
    private final List<Map<String, String>> history = new ArrayList<>();

    public LlmAgent(LlmClient.Config config) {
        this.config = config;
        this.http = HttpClient.newHttpClient();
        reset();
    }

    public String ask(String userMessage) throws Exception {
        history.add(LlmClient.message("user", userMessage));
        JsonNode response = LlmClient.chat(http, config, List.copyOf(history), null, null);
        String answer = LlmClient.content(response);
        history.add(LlmClient.message("assistant", answer));
        return answer;
    }

    public void reset() {
        history.clear();
        history.add(LlmClient.message("system", SYSTEM_PROMPT));
    }

    public int turnCount() {
        return (history.size() - 1) / 2;
    }
}
