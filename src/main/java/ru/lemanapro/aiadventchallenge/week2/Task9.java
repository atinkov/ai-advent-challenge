package ru.lemanapro.aiadventchallenge.week2;

import ru.lemanapro.aiadventchallenge.LlmAgent;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Day 9: context management — history compression, live demonstration.
 *
 * Runs the same 14-turn scripted dialogue twice through the agent:
 *   1. "БЕЗ сжатия" — compression disabled, the baseline behaviour of days 6–8;
 *   2. "СО сжатием" — rolling summary on (window/batch from AGENT_KEEP_RECENT /
 *      AGENT_SUMMARY_BATCH, same defaults as LlmAgent).
 *
 * The with-compression phase shows: summary events firing while the dialogue
 * grows ("Агент: история сжата: ..."), request size staying bounded while the
 * baseline keeps growing, and the final "recall everything" answer being
 * produced from the summary alone. Each phase writes its own context file
 * (task9-off.json / task9-on.json, format v3) for inspection.
 *
 * No report file is written: the canonical report is task9-compression-report.md.
 *
 * Usage:
 *   mvn -q exec:java -Ptask9
 *   AGENT_KEEP_RECENT=4 AGENT_SUMMARY_BATCH=6 mvn -q exec:java -Ptask9
 */
public final class Task9 {

    /** Mirrors LlmAgent's defaults; only the "СО сжатием" phase is affected. */
    private static final int DEFAULT_KEEP_RECENT = 6;
    private static final int DEFAULT_SUMMARY_BATCH = 10;

    /** Turns 1–5 plant five facts about the user, 6–13 are fillers, the last turn asks to recall them all. */
    private static final List<String> SCRIPT = List.of(
            "Привет! Меня зовут Дмитрий. Расскажи шутку про программистов.",
            "Я живу в Екатеринбурге. Чем славится этот город?",
            "Мне 17 лет. Какие навыки стоит изучать в этом возрасте?",
            "Мой любимый язык программирования — Python. Он хорош для новичка?",
            "У меня кот, зовут Барсик. Как правильно ухаживать за котом?",
            "А теперь расскажи шутку про котов.",
            "Назови три интересных факта про космос.",
            "А теперь шутку про космос.",
            "Что лучше: эспрессо или капучино? Кратко.",
            "Расскажи анекдот про бухгалтера.",
            "Дай пять идей для вечернего отдыха дома.",
            "Скажи что-нибудь вдохновляющее на завтра.",
            "Короткий совет по организации дня.",
            "Собери всё, что ты запомнил о мне: имя, город, возраст, любимый язык программирования, питомец. Ответь списком.");

    private static final List<String> FACT_MARKERS =
            List.of("дмитрий", "екатеринбург", "17", "python", "барсик");

    private record PhaseStats(String label, int turns, int compressions,
                              int maxPrompt, int sessionPrompt, int sessionCompletion,
                              int historyEstimate, int factsRecalled, String finalAnswer) {
    }

    private Task9() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        int keepRecent = parseEnvInt("AGENT_KEEP_RECENT", DEFAULT_KEEP_RECENT);
        int summaryBatch = parseEnvInt("AGENT_SUMMARY_BATCH", DEFAULT_SUMMARY_BATCH);

        System.out.println("День 9: управление контекстом — сжатие истории");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println("Сценарий: " + SCRIPT.size()
                + " реплик — факты о пользователе в первых пяти, вопрос «собери всё» в последней.");
        System.out.println("Параметры сжатия: окно=" + keepRecent + ", пачка=" + summaryBatch + ".");

        List<PhaseStats> phases = new ArrayList<>();
        phases.add(runPhase(cfg, false, keepRecent, summaryBatch, Path.of("task9-off.json")));
        phases.add(runPhase(cfg, true, keepRecent, summaryBatch, Path.of("task9-on.json")));

        printComparison(phases.get(0), phases.get(1));
    }

    private static PhaseStats runPhase(LlmClient.Config cfg, boolean compression, int keepRecent,
                                       int summaryBatch, Path contextFile) throws Exception {
        Files.deleteIfExists(contextFile);
        LlmAgent agent = new LlmAgent(cfg, compression, keepRecent, summaryBatch, contextFile);

        System.out.println();
        System.out.println("=== Фаза: " + (compression ? "СО сжатием истории" : "БЕЗ сжатия истории")
                + " (контекст: " + contextFile + ") ===");

        String finalAnswer = null;
        for (int i = 0; i < SCRIPT.size(); i++) {
            System.out.println();
            System.out.println("[" + (i + 1) + "/" + SCRIPT.size() + "] Вы: " + abbreviate(SCRIPT.get(i), 100));
            String answer = agent.ask(SCRIPT.get(i));
            if (answer == null) {
                System.out.println("Агент: запрос отклонён сервером, диалог не изменён.");
                continue;
            }
            finalAnswer = answer;
            System.out.println("Агент: " + abbreviate(answer, 120));
            System.out.println(tokenLine(agent));
        }

        return new PhaseStats(compression ? "со сжатием" : "без сжатия",
                agent.turnCount(), agent.compressionsCount(),
                agent.maxPromptTokens(), agent.sessionPromptTokens(), agent.sessionCompletionTokens(),
                agent.historyTokensEstimate(), countFacts(finalAnswer), finalAnswer);
    }

    private static void printComparison(PhaseStats off, PhaseStats on) {
        System.out.println();
        System.out.println("=== Сравнение фаз ===");
        System.out.printf("%-32s | %-14s | %s%n", "Метрика", off.label(), on.label());
        System.out.println("-".repeat(72));
        System.out.printf("%-32s | %-14d | %d%n", "Реплик", off.turns(), on.turns());
        System.out.printf("%-32s | %-14d | %d%n", "Событий сжатия", off.compressions(), on.compressions());
        System.out.printf("%-32s | %-14d | %d%n", "Пик запроса, токены", off.maxPrompt(), on.maxPrompt());
        System.out.printf("%-32s | %-14d | %d%n", "Prompt за сессию", off.sessionPrompt(), on.sessionPrompt());
        System.out.printf("%-32s | %-14d | %d%n", "Сессия всего (p+c)",
                off.sessionPrompt() + off.sessionCompletion(), on.sessionPrompt() + on.sessionCompletion());
        System.out.printf("%-32s | %-14d | %d%n", "История в конце ≈, токены",
                off.historyEstimate(), on.historyEstimate());
        System.out.printf("%-32s | %-14s | %s%n", "Факты воспроизведены",
                off.factsRecalled() + "/" + FACT_MARKERS.size(), on.factsRecalled() + "/" + FACT_MARKERS.size());

        System.out.println();
        System.out.println("Ответ фазы «со сжатием» (на этом месте старые ходы уже в пересказе):");
        System.out.println(on.finalAnswer() == null ? "(нет ответа)" : on.finalAnswer());

        System.out.println();
        System.out.println("Вывод: без сжатия каждый ход пересылает всю историю — пик запроса "
                + off.maxPrompt() + " токенов и рост дальше; со сжатием запрос ограничен окном и пересказом"
                + " — пик " + on.maxPrompt() + ". Prompt за сессию: " + off.sessionPrompt() + " против "
                + on.sessionPrompt() + " (экономия " + percentSaved(off.sessionPrompt(), on.sessionPrompt())
                + "%, с учётом запросов-резюмирований). Факты воспроизведены в фазе со сжатием: "
                + on.factsRecalled() + "/" + FACT_MARKERS.size() + " — скользящий пересказ сохранил главное.");
        System.out.println("Контекст фаз сохранён для разбора: task9-off.json, task9-on.json (формат v3).");
    }

    private static int countFacts(String answer) {
        if (answer == null) {
            return 0;
        }
        String normalized = answer.toLowerCase();
        int count = 0;
        for (String marker : FACT_MARKERS) {
            if (normalized.contains(marker)) {
                count++;
            }
        }
        return count;
    }

    private static String percentSaved(int baseline, int compressed) {
        if (baseline <= 0) {
            return "0";
        }
        return String.format("%.0f", (baseline - compressed) * 100.0 / baseline);
    }

    private static String abbreviate(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    private static String tokenLine(LlmAgent agent) {
        LlmClient.Usage usage = agent.lastUsage();
        if (usage == null) {
            return "[токены: ?]";
        }
        return "[токены: запрос=" + tokenValue(usage.promptTokens())
                + " | ответ=" + tokenValue(usage.completionTokens())
                + " | история≈" + agent.historyTokensEstimate()
                + " | сессия=" + (agent.sessionPromptTokens() + agent.sessionCompletionTokens())
                + "]";
    }

    private static String tokenValue(int tokens) {
        return tokens < 0 ? "?" : String.valueOf(tokens);
    }

    private static int parseEnvInt(String name, int defaultValue) {
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
}
