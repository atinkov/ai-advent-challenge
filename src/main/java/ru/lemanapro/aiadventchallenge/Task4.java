package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Day 4: temperature.
 *
 * The same request is sent RUNS_PER_TEMP times at each of three temperatures
 * (0.0, 0.7, 1.2), then compared by:
 *   - точность: does the answer contain the known correct value (default task only),
 *   - разнообразие: how many distinct replies a temperature produced,
 *   - length: average reply length.
 * Ends with a conclusion on when each temperature is the right choice.
 *
 * The built-in task is "how many seconds are in a week" (answer: 604 800).
 * A custom task via args disables the auto-correctness check.
 *
 * Config via env — see {@link LlmClient#fromEnv()}.
 *
 * Usage:
 *   mvn -q exec:java -Ptask4
 *   mvn -q exec:java -Ptask4 -Dexec.args="Ваш вопрос"
 */
public final class Task4 {

    private static final String DEFAULT_TASK = "Сколько секунд в неделе? Покажи вычисления.";
    private static final List<String> CORRECT_MARKERS = List.of("604800", "604 800", "604,800");
    private static final double[] TEMPERATURES = {0.0, 0.7, 1.2};
    private static final int RUNS_PER_TEMP = 3;

    private Task4() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        boolean autoCheck = args.length == 0;
        String task = args.length > 0 ? String.join(" ", args) : DEFAULT_TASK;

        System.out.println("Запрос: " + task);
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println("Запусков на каждую температуру: " + RUNS_PER_TEMP);
        if (!autoCheck) {
            System.out.println("(автопроверка точности отключена: задана своя задача)");
        }

        String[][] answers = new String[TEMPERATURES.length][RUNS_PER_TEMP];
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (int t = 0; t < TEMPERATURES.length; t++) {
                double temperature = TEMPERATURES[t];
                System.out.println("\n=== temperature = " + temperature + " ===");
                for (int run = 0; run < RUNS_PER_TEMP; run++) {
                    JsonNode response = LlmClient.chat(client, cfg,
                            List.of(LlmClient.message("user", task)), null, null, temperature);
                    String text = LlmClient.content(response);
                    answers[t][run] = text;
                    System.out.println("\n-- run " + (run + 1) + " --");
                    System.out.println(text);
                    System.out.println("[finish_reason=" + LlmClient.finishReason(response) + "]");
                }
            }

            // --- Сравнение ---
            System.out.println("\n=== Сравнение ===");
            System.out.printf("%-14s | %-16s | %-14s | %s%n",
                    "температура", "разнообразие", "точность", "средн. длина");
            for (int t = 0; t < TEMPERATURES.length; t++) {
                Set<String> unique = new HashSet<>();
                int correct = 0;
                long totalLength = 0;
                for (String text : answers[t]) {
                    unique.add(text.trim());
                    totalLength += text.length();
                    if (autoCheck && hasCorrect(text)) {
                        correct++;
                    }
                }
                System.out.printf("%-14s | %-16s | %-14s | %d симв.%n",
                        String.valueOf(TEMPERATURES[t]),
                        unique.size() + "/" + RUNS_PER_TEMP,
                        autoCheck ? correct + "/" + RUNS_PER_TEMP : "—",
                        totalLength / RUNS_PER_TEMP);
            }

            // --- Выводы ---
            System.out.println("\n=== Выводы: когда какая температура ===");
            System.out.println("temperature=0   — детерминированный, воспроизводимый результат: код, JSON/структурированные");
            System.out.println("                  форматы, математика, классификация, извлечение данных.");
            System.out.println("temperature=0.7 — баланс точности и вариативности: обычный текст, диалоги,");
            System.out.println("                  резюме, объяснения (рабочий дефолт для большинства задач).");
            System.out.println("temperature=1.2 — максимальное разнообразие: брейнштормы, названия, идеи,");
            System.out.println("                  креативные тексты; выше риск неточностей и нескладных формулировок.");
        }
    }

    private static boolean hasCorrect(String text) {
        String lower = text.toLowerCase();
        return CORRECT_MARKERS.stream().anyMatch(lower::contains);
    }
}
