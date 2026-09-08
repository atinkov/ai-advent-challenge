package ru.lemanapro.aiadventchallenge.week1;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Day 5: model versions.
 *
 * The same request is sent to a weak, a mid, and a strong model (defaults:
 * glm-5.3-flash / deepseek-v4-flash / qwen3.8-27b — internal gpustack names,
 * remappable via TASK5_MODEL_WEAK / TASK5_MODEL_MID / TASK5_MODEL_STRONG).
 * For each model it measures the wall-clock response time and token usage from
 * the API `usage` field, compares speed / correctness / efficiency, and writes
 * a full report to task5-model-comparison.md.
 *
 * Cost: self-hosted gpustack models are not billed; set TASK5_PRICE_PER_1M_USD
 * to compute a price for paid APIs (applied to total tokens).
 *
 * Usage:
 *   mvn -q exec:java -Ptask5
 *   mvn -q exec:java -Ptask5 -Dexec.args="Ваш вопрос"   (disables auto-correctness)
 */
public final class Task5 {

    private static final String DEFAULT_TASK =
            "Почему небо голубое? Назови эффект и учёного, в честь которого он назван. Ответь в 3-4 предложениях.";
    private static final List<String> CORRECT_MARKERS = List.of("рэлей", "рэлея", "rayleigh");
    private static final Path REPORT = Path.of("task5-model-comparison.md");
    private static final List<String> LINKS = List.of(
            "Qwen (strong): https://huggingface.co/Qwen",
            "GLM, Zhipu/Z.ai: https://huggingface.co/zai-org",
            "DeepSeek: https://huggingface.co/deepseek-ai",
            "Список моделей HuggingFace: https://huggingface.co/models",
            "gpustack docs: https://docs.gpustack.ai");

    private record Tier(String label, String model) {
    }

    private record Result(Tier tier, String text, double seconds, LlmClient.Usage usage,
                          double costUsd, boolean correct) {
    }

    private Task5() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        boolean autoCheck = args.length == 0;
        String task = args.length > 0 ? String.join(" ", args) : DEFAULT_TASK;
        double pricePer1M = parsePrice(LlmClient.env("TASK5_PRICE_PER_1M_USD", ""));

        Tier[] tiers = {
                new Tier("weak", LlmClient.env("TASK5_MODEL_WEAK", "glm-5.3-flash")),
                new Tier("mid", LlmClient.env("TASK5_MODEL_MID", "deepseek-v4-flash")),
                new Tier("strong", LlmClient.env("TASK5_MODEL_STRONG", "qwen3.8-27b")),
        };

        System.out.println("Запрос: " + task);
        System.out.println("Сервер: " + cfg.baseUrl());

        List<Result> results = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (Tier tier : tiers) {
                LlmClient.Config tierCfg = new LlmClient.Config(cfg.apiKey(), cfg.baseUrl(), tier.model());
                System.out.println("\n=== " + tier.label() + ": " + tier.model() + " ===");

                long start = System.nanoTime();
                JsonNode response = LlmClient.chat(client, tierCfg,
                        List.of(LlmClient.message("user", task)), null, null);
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;

                String text = LlmClient.content(response);
                LlmClient.Usage usage = LlmClient.usage(response);
                double cost = pricePer1M > 0 && usage.totalTokens() > 0
                        ? usage.totalTokens() / 1_000_000.0 * pricePer1M
                        : 0;
                boolean correct = autoCheck && hasCorrectAnswer(text);
                results.add(new Result(tier, text, seconds, usage, cost, correct));

                System.out.println(text);
                System.out.printf("[время=%.2f с, токены (p/c/t)=%d/%d/%d, стоимость=%s]%n",
                        seconds, usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        costLabel(cost));
            }

            System.out.println("\n=== Сравнение ===");
            System.out.printf("%-7s | %-22s | %-10s | %-14s | %-19s | %s%n",
                    "уровень", "модель", "время, с", "токены (total)", "стоимость", "верно");
            for (Result r : results) {
                System.out.printf("%-7s | %-22s | %-10s | %-14s | %-19s | %s%n",
                        r.tier().label(), r.tier().model(), String.format("%.2f", r.seconds()),
                        tokenLabel(r.usage().totalTokens()), costLabel(r.costUsd()),
                        autoCheck ? (r.correct() ? "да" : "нет") : "—");
            }

            String conclusion = conclude(results, autoCheck);
            System.out.println("\n=== Вывод ===");
            System.out.println(conclusion);

            System.out.println("\nСсылки:");
            LINKS.forEach(link -> System.out.println("- " + link));

            Files.writeString(REPORT, toMarkdown(task, cfg.baseUrl(), results, conclusion, autoCheck));
            System.out.println("\nОтчёт: " + REPORT.toAbsolutePath());
        }
    }

    private static String conclude(List<Result> results, boolean autoCheck) {
        Result fastest = null;
        Result heaviest = null;
        List<String> correct = new ArrayList<>();
        for (Result r : results) {
            if (fastest == null || r.seconds() < fastest.seconds()) {
                fastest = r;
            }
            if (r.usage().totalTokens() > 0
                    && (heaviest == null || r.usage().totalTokens() > heaviest.usage().totalTokens())) {
                heaviest = r;
            }
            if (r.correct()) {
                correct.add(r.tier().model());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(fastest.tier().model()).append(" — самая быстрая (")
                .append(String.format("%.2f", fastest.seconds())).append(" с).");
        if (heaviest != null) {
            sb.append(" ").append(heaviest.tier().model())
                    .append(" — больше всего токенов (").append(heaviest.usage().totalTokens()).append(").");
        }
        if (autoCheck) {
            sb.append(correct.isEmpty()
                    ? " Ни одна модель не дала верный ответ явно — сравните тексты вручную."
                    : " Верный ответ дали: " + String.join(", ", correct) + ".");
        }
        sb.append("\nОбщее правило: чем меньше модель, тем быстрее и дешевле ответ — это задачи простые и высокая нагрузка;")
                .append(" чем больше модель, тем стабильнее качество на сложном рассуждении.")
                .append(" Выбор — по задаче: скорость/стоимость против точности.");
        return sb.toString();
    }

    private static String toMarkdown(String task, String baseUrl, List<Result> results,
                                     String conclusion, boolean autoCheck) {
        StringBuilder md = new StringBuilder();
        md.append("# День 5: Версии моделей — сравнение\n\n");
        md.append("**Дата:** ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n");
        md.append("**Сервер:** ").append(baseUrl).append("\n");
        md.append("**Запрос:** ").append(task).append("\n\n");
        md.append("## Ответы\n");
        for (Result r : results) {
            md.append("\n### ").append(r.tier().label()).append(" — `").append(r.tier().model()).append("`\n\n");
            md.append(r.text()).append("\n\n");
            md.append("*Время: ").append(String.format("%.2f", r.seconds())).append(" с | токены (p/c/t): ")
                    .append(usageLabel(r.usage())).append(" | стоимость: ").append(costLabel(r.costUsd())).append("*\n");
        }
        md.append("\n## Сравнение\n\n");
        md.append("| уровень | модель | время, с | токены (total) | стоимость | верно |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (Result r : results) {
            md.append("| ").append(r.tier().label())
                    .append(" | `").append(r.tier().model()).append("`")
                    .append(" | ").append(String.format("%.2f", r.seconds()))
                    .append(" | ").append(tokenLabel(r.usage().totalTokens()))
                    .append(" | ").append(costLabel(r.costUsd()))
                    .append(" | ").append(autoCheck ? (r.correct() ? "да" : "нет") : "—")
                    .append(" |\n");
        }
        md.append("\n## Вывод\n\n").append(conclusion).append("\n\n");
        md.append("## Ссылки\n\n");
        for (String link : LINKS) {
            md.append("- ").append(link).append("\n");
        }
        return md.toString();
    }

    private static boolean hasCorrectAnswer(String text) {
        String normalized = text.toLowerCase();
        return CORRECT_MARKERS.stream().anyMatch(normalized::contains);
    }

    private static double parsePrice(String raw) {
        if (raw.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            System.err.println("TASK5_PRICE_PER_1M_USD: ожидалось число, получено: " + raw);
            System.exit(2);
            return 0;
        }
    }

    private static String tokenLabel(int tokens) {
        return tokens < 0 ? "?" : String.valueOf(tokens);
    }

    private static String usageLabel(LlmClient.Usage usage) {
        return usage.promptTokens() + "/" + usage.completionTokens() + "/" + tokenLabel(usage.totalTokens());
    }

    private static String costLabel(double costUsd) {
        return costUsd <= 0 ? "0 (не тарифицируется)" : String.format("$%.6f", costUsd);
    }
}
