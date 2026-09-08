package ru.lemanapro.aiadventchallenge.week1;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.util.List;

/**
 * Day 1: minimal LLM call.
 *
 * Sends one prompt to the configured OpenAI-compatible endpoint and prints
 * the assistant reply to the console.
 *
 * Config via env — see {@link LlmClient#fromEnv()}.
 *
 * Usage:
 *   mvn -q exec:java -Ptask1
 *   mvn -q exec:java -Ptask1 -Dexec.args="What is the capital of France?"
 */
public final class Task1 {

    private static final String DEFAULT_PROMPT = "Say hello in one short sentence."
            + "\n"
            + "Tell me, which model am I using right now?";

    private Task1() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        String prompt = args.length > 0 ? String.join(" ", args) : DEFAULT_PROMPT;

        System.out.println("> " + prompt);
        try (HttpClient client = HttpClient.newHttpClient()) {
            JsonNode response = LlmClient.chat(client, cfg,
                    List.of(LlmClient.message("user", prompt)), null, null);
            System.out.println(LlmClient.content(response));
        }
    }
}
