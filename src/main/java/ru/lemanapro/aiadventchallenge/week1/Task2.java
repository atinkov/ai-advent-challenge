package ru.lemanapro.aiadventchallenge.week1;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.util.List;

/**
 * Day 2: the same question with two levels of response control.
 *
 * Run 1 — bare request (no constraints).
 * Run 2 — the same question plus:
 *   - explicit response format description (system instruction),
 *   - max_tokens limit,
 *   - stop sequence (###STOP###) + instruction to end with it.
 * Prints both answers and a short comparison.
 *
 * Config via env — see {@link LlmClient#fromEnv()}.
 *
 * Usage:
 *   mvn -q exec:java -Ptask2
 *   mvn -q exec:java -Ptask2 -Dexec.args="Ваш вопрос"
 */
public final class Task2 {

    private static final String STOP_SEQUENCE = "###STOP###";
    private static final int MAX_TOKENS = 100;
    private static final String DEFAULT_QUESTION = "Что такое квантовые компьютеры?";

    private Task2() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        String question = args.length > 0 ? String.join(" ", args) : DEFAULT_QUESTION;

        System.out.println("Вопрос: " + question);
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());

        try (HttpClient client = HttpClient.newHttpClient()) {
            // --- 1) Без ограничений: только вопрос, без служебных параметров ---
            System.out.println("\n=== 1) БЕЗ ограничений ===");
            JsonNode bare = LlmClient.chat(client, cfg,
                    List.of(LlmClient.message("user", question)), null, null);
            String bareText = LlmClient.content(bare);
            System.out.println(bareText);
            System.out.println("[finish_reason=" + LlmClient.finishReason(bare) + ", символов: " + bareText.length() + "]");

            // --- 2) С ограничениями: формат ответа + max_tokens + stop-последовательность ---
            String formatInstruction = "Формат ответа: ровно один короткий абзац (не более 2 предложений), "
                    + "без вступлений, списков и комментариев. Заверши ответ маркером " + STOP_SEQUENCE + ".";
            System.out.println("\n=== 2) С ограничениями (формат + max_tokens=" + MAX_TOKENS + " + stop=[" + STOP_SEQUENCE + "]) ===");
            System.out.println("Инструкция о формате: " + formatInstruction);
            JsonNode controlled = LlmClient.chat(client, cfg,
                    List.of(LlmClient.message("system", formatInstruction), LlmClient.message("user", question)),
                    MAX_TOKENS, List.of(STOP_SEQUENCE));
            String controlledText = LlmClient.content(controlled);
            System.out.println(controlledText);
            System.out.println("[finish_reason=" + LlmClient.finishReason(controlled) + ", символов: " + controlledText.length() + "]");

            // --- Сравнение ---
            System.out.println("\n=== Сравнение ===");
            System.out.println("Без ограничений:  " + bareText.length() + " симв., finish_reason=" + LlmClient.finishReason(bare));
            System.out.println("С ограничениями:  " + controlledText.length() + " симв., finish_reason=" + LlmClient.finishReason(controlled));
            System.out.println("Экономия: " + Math.max(0, bareText.length() - controlledText.length()) + " симв.");
        }
    }
}
