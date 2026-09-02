package ru.lemanapro.aiadventchallenge;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.util.List;

/**
 * Advent task: different ways of reasoning.
 *
 * One problem is solved four ways through the API:
 *   1. direct answer (no extra instructions),
 *   2. "solve step by step" instruction,
 *   3. model first writes a solving prompt, then that prompt is used,
 *   4. a panel of experts (analyst, engineer, critic) each answers, then they agree.
 * Prints all four answers and a comparison (length, correctness, best method).
 *
 * The built-in problem is the bat-and-ball puzzle; correctness is auto-checked
 * against the known answer ($0.05). A custom problem via args disables the
 * auto-correctness check.
 *
 * Usage:
 *   mvn -q exec:java -Dexec.mainClass=ru.lemanapro.aiadventchallenge.Task3
 *   mvn -q exec:java -Dexec.mainClass=ru.lemanapro.aiadventchallenge.Task3 -Dexec.args="Ваша задача"
 */
public final class Task3 {

    private static final String DEFAULT_TASK =
            "Бита и мяч вместе стоят 1,10 $. Бита дороже мяча на 1,00 $. Сколько стоит мяч?";
    private static final List<String> CORRECT_MARKERS =
            List.of("0.05", "0,05", "5 цент", "5 cent", "$0.05");

    private Task3() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        boolean autoCheck = args.length == 0;
        String task = args.length > 0 ? String.join(" ", args) : DEFAULT_TASK;

        System.out.println("Задача: " + task);
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        if (!autoCheck) {
            System.out.println("(автопроверка верности отключена: задана своя задача)");
        }
        System.out.println();

        try (HttpClient client = HttpClient.newHttpClient()) {
            // --- 1) Прямой ответ, без дополнительных инструкций ---
            String m1 = ask(client, cfg, "1) Прямой ответ", task);

            // --- 2) Пошаговое решение ---
            String m2 = ask(client, cfg, "2) Пошагово",
                    task + "\n\nРешай пошагово: сначала проанализируй условие, выполни промежуточные "
                            + "вычисления, и только в конце дай краткий финальный ответ.");

            // --- 3) Мета-промпт: модель сама составляет промпт, затем он используется ---
            String metaPrompt = ask(client, cfg, "3а) Составление промпта",
                    "Составь полный промпт для решения этой задачи. Промпт должен содержать саму задачу "
                            + "и чёткие инструкции, как к ней подступиться, чтобы получить точный ответ. "
                            + "Выведи только текст промпта, без комментариев.\n\nЗадача: " + task);
            System.out.println();
            String m3 = ask(client, cfg, "3б) Решение по составленному промпту", metaPrompt.trim());

            // --- 4) Группа экспертов ---
            String m4 = ask(client, cfg, "4) Группа экспертов",
                    "Вы — группа из трёх экспертов: аналитик, инженер и критик. Каждый по очереди "
                            + "приводит своё решение, затем они обсуждают расхождения и приходят к одному "
                            + "общему финальному ответу. В конце явно укажите итоговый ответ.\n\nЗадача: " + task);

            // --- Сравнение ---
            System.out.println("\n=== Сравнение ===");
            String[] names = {"1) прямой", "2) пошагово", "3) мета-промпт", "4) эксперты"};
            String[] answers = {m1, m2, m3, m4};
            int distinct = 0;
            int correctCount = 0;
            for (int i = 0; i < answers.length; i++) {
                boolean correct = autoCheck && hasCorrect(answers[i]);
                if (correct) {
                    correctCount++;
                }
                System.out.printf("%-16s | %5d симв. | верный ответ: %s%n",
                        names[i], answers[i].trim().length(),
                        autoCheck ? (correct ? "ДА" : "нет") : "—");
            }
            for (int i = 1; i < answers.length; i++) {
                if (!answers[i].trim().equals(answers[0].trim())) {
                    distinct++;
                }
            }
            System.out.println("Ответы различаются: " + (distinct == 0 ? "НЕТ (все одинаковые)"
                    : "ДА (" + (distinct + 1) + " из 4 уникальных текста)"));
            if (autoCheck) {
                System.out.println("Способов с верным ответом: " + correctCount + " из 4");
                System.out.println(bestMethod(names, answers));
            }
        }
    }

    private static String bestMethod(String[] names, String[] answers) {
        StringBuilder correctOnes = new StringBuilder();
        int firstCorrect = -1;
        for (int i = 0; i < answers.length; i++) {
            if (hasCorrect(answers[i])) {
                if (firstCorrect < 0) {
                    firstCorrect = i;
                }
                if (!correctOnes.isEmpty()) {
                    correctOnes.append(", ");
                }
                correctOnes.append(names[i]);
            }
        }
        if (firstCorrect < 0) {
            return "Наиболее точный: ни один способ явно не дал верный ответ — сравните вручную.";
        }
        return "Наиболее точный: " + names[firstCorrect]
                + " (верный ответ дали: " + correctOnes + ")";
    }

    private static boolean hasCorrect(String text) {
        String lower = text.toLowerCase();
        return CORRECT_MARKERS.stream().anyMatch(lower::contains);
    }

    /** Prints a labelled section, sends one request, prints the answer + stats, returns the text. */
    private static String ask(HttpClient client, LlmClient.Config cfg, String label, String prompt)
            throws Exception {
        System.out.println("=== " + label + " ===");
        System.out.println("Промпт: " + (prompt.length() > 300 ? prompt.substring(0, 300) + "…" : prompt));
        JsonNode response = LlmClient.chat(client, cfg, List.of(LlmClient.message("user", prompt)), null, null);
        String text = LlmClient.content(response);
        System.out.println(text);
        System.out.println("[finish_reason=" + LlmClient.finishReason(response)
                + ", символов: " + text.length() + "]");
        System.out.println();
        return text;
    }
}
