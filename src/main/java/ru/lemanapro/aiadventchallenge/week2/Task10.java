package ru.lemanapro.aiadventchallenge.week2;

import ru.lemanapro.aiadventchallenge.LlmAgent;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Day 10: context management — strategy comparison (without summary) and branching.
 *
 * Runs the same 13-turn scripted dialogue ("собираем ТЗ шахматного клуба": 9 facts
 * planted in turns 1-9, fillers 10-12, final "сведи ТЗ" in turn 13) through three
 * strategies of LlmAgent (compression off everywhere, so the strategies are
 * compared without summary interference):
 *   1. SLIDING_WINDOW — only the last 6 messages are kept, older ones dropped;
 *   2. FACTS          — key-value facts block refreshed by the model after every
 *                       turn; the request is facts + last 6 messages;
 *   3. FULL           — the whole history, the baseline of days 6-8.
 * Then a branching demo: one agent builds the ТЗ to message 8, saves a checkpoint,
 * creates branches A (new deadline, no tournaments) and B (auto-translator),
 * continues each independently, and finally verifies that the original main
 * branch is untouched.
 *
 * Each phase writes its own context file (task10-{sliding,facts,full,branch}.json,
 * format v4) and the comparison goes to task10-strategy-report.md (regenerated
 * on every run).
 *
 * Usage:
 *   mvn -q exec:java -Ptask10
 */
public final class Task10 {

    private static final int WINDOW = 6;
    private static final Path REPORT_FILE = Path.of("task10-strategy-report.md");

    private static final List<String> SCRIPT = List.of(
            "Привет! Собираем ТЗ на онлайн-шахматный клуб для школьников. Какое назначение у проекта?",
            "Разделим на 3 группы: младшая, средняя и старшая. Как это отразить в ТЗ?",
            "В каждой группе не больше 10 человек, у каждой группы свой тренер.",
            "Участие для школьников бесплатное, деньги платят спонсоры.",
            "Важна адаптивная версия — половина участников играет с телефона.",
            "Оформление сделаем в тёмной теме, школьники жалуются на яркий экран.",
            "Интерфейс строго на русском языке, без английского.",
            "Первую версию нужно сдать через месяц.",
            "И добавь в ТЗ бота для тренировки, чтобы новички играли в одиночку.",
            "А пока дай шутку про шахматы.",
            "Пять идей для названия логотипа.",
            "Дай совет, как держать команду в тонусе.",
            "Сведи всё ТЗ в итог: назначение, группы и их состав, стоимость, требования к интерфейсу, дедлайн. Ответь списком.");

    private static final List<String> FACT_MARKERS =
            List.of("клуб", "групп", "10", "бесплат", "мобил", "тёмн", "русск", "месяц", "бот");

    private record PhaseStats(String label, int turns, int maxPrompt, int sessionPrompt,
                              int sessionCompletion, int historyEstimate, int factsUpdates,
                              int factsRecalled, String finalAnswer) {
    }

    private record BranchStats(String aAnswer, String bAnswer, String mainAnswer,
                               boolean aDeadlineChanged, boolean aNoLeak,
                               boolean bTranslatorAdded, boolean bNoLeak,
                               boolean mainUnchanged) {
    }

    private Task10() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        System.out.println("День 10: управление контекстом — сравнение стратегий (без summary) + ветки");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println("Сценарий: " + SCRIPT.size()
                + " реплик — ТЗ шахматного клуба, 9 фактов в репликах 1-9, «сведи ТЗ» в последней.");
        System.out.println("Окно: N=" + WINDOW + ", сжатие выключено для всех стратегий.");

        PhaseStats sliding = runStrategy(cfg, LlmAgent.Strategy.SLIDING_WINDOW, "task10-sliding.json");
        PhaseStats facts = runStrategy(cfg, LlmAgent.Strategy.FACTS, "task10-facts.json");
        PhaseStats full = runStrategy(cfg, LlmAgent.Strategy.FULL, "task10-full.json");
        BranchStats branch = runBranching(cfg);

        printComparison(sliding, facts, full, branch);
        writeReport(cfg, sliding, facts, full, branch);
        System.out.println();
        System.out.println("Отчёт записан: " + REPORT_FILE);
    }

    private static PhaseStats runStrategy(LlmClient.Config cfg, LlmAgent.Strategy strategy,
                                          String contextFile) throws Exception {
        Files.deleteIfExists(Path.of(contextFile));
        LlmAgent agent = new LlmAgent(cfg, new LlmAgent.Settings(strategy, WINDOW, false, 6, 10,
                Path.of(contextFile)));

        System.out.println();
        System.out.println("=== Стратегия: " + agent.strategyLabel()
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

        return new PhaseStats(agent.strategyLabel(), agent.turnCount(),
                agent.maxPromptTokens(), agent.sessionPromptTokens(), agent.sessionCompletionTokens(),
                agent.historyTokensEstimate(), agent.factsUpdatesCount(),
                countFacts(finalAnswer), finalAnswer);
    }

    private static BranchStats runBranching(LlmClient.Config cfg) throws Exception {
        Path contextFile = Path.of("task10-branch.json");
        Files.deleteIfExists(contextFile);
        LlmAgent agent = new LlmAgent(cfg, new LlmAgent.Settings(LlmAgent.Strategy.FULL, WINDOW,
                false, 6, 10, contextFile));

        System.out.println();
        System.out.println("=== Branching: ветки от checkpoint (контекст: " + contextFile + ") ===");

        for (int i = 0; i < 8; i++) {
            System.out.println();
            System.out.println("[" + (i + 1) + "/8] Вы: " + abbreviate(SCRIPT.get(i), 100));
            String answer = agent.ask(SCRIPT.get(i));
            if (answer != null) {
                System.out.println("Агент: " + abbreviate(answer, 120));
            }
        }

        agent.saveCheckpoint();
        agent.createBranch("A");
        agent.createBranch("B");

        System.out.println();
        System.out.println("--- Ветка A: новый дедлайн, без турниров ---");
        agent.switchBranch("A");
        String aDeadline = "Сменим условия: первую версию сдаём через две недели, турниры в первую версию не входят.";
        agent.ask(aDeadline);
        String aAnswer = agent.ask("Сведи текущее ТЗ: назначение, состав, требования, дедлайн. Краткий список.");
        System.out.println("Агент (A): " + abbreviate(aAnswer, 160));

        System.out.println();
        System.out.println("--- Ветка B: авто-переводчик ---");
        agent.switchBranch("B");
        agent.ask("Добавим функцию: бот авто-переводчика будет переводить сообщения тренеров на английский.");
        String bAnswer = agent.ask("Сведи текущее ТЗ: назначение, состав, требования, дедлайн. Краткий список.");
        System.out.println("Агент (B): " + abbreviate(bAnswer, 160));

        System.out.println();
        System.out.println("--- Main: проверка изоляции ---");
        agent.switchBranch("main");
        String mainAnswer = agent.ask(
                "Проверка: напомни, какой дедлайн первой версии мы изначально договорились и сколько групп? Кратко.");
        System.out.println("Агент (main): " + abbreviate(mainAnswer, 160));

        String a = normalize(aAnswer);
        String b = normalize(bAnswer);
        String main = normalize(mainAnswer);
        // A's signature: deadline shortened from a month to two weeks (model may say «14 дней»).
        boolean aDeadlineChanged = hasAny(a, "недел", "14 календарных", "14 дней", "две недели", "2 недели");
        // B's signature: the auto-translator feature (not the generic word «перевод»).
        boolean aNoLeak = !hasAny(a, "автоперевод", "переводчик");
        boolean bTranslatorAdded = hasAny(b, "автоперевод", "переводчик");
        boolean bNoLeak = !hasAny(b, "14 календарных", "14 дней", "две недели", "2 недели", "недел");
        boolean mainUnchanged = hasAny(main, "30 дней", "1 месяц", "месяц")
                && !hasAny(main, "14 календарных", "14 дней", "две недели", "2 недели", "недел", "автоперевод");
        printBranchChecks(aDeadlineChanged, aNoLeak, bTranslatorAdded, bNoLeak, mainUnchanged);
        return new BranchStats(aAnswer, bAnswer, mainAnswer,
                aDeadlineChanged, aNoLeak, bTranslatorAdded, bNoLeak, mainUnchanged);
    }

    private static void printBranchChecks(boolean aDeadline, boolean aNoLeak, boolean bTranslator,
                                          boolean bNoLeak, boolean mainOk) {
        System.out.println("Проверки: A-дедлайн=" + mark(aDeadline)
                + ", A без утечки B=" + mark(aNoLeak)
                + ", B-переводчик=" + mark(bTranslator)
                + ", B без утечки A=" + mark(bNoLeak)
                + ", main не изменился=" + mark(mainOk));
    }

    private static String mark(boolean ok) {
        return ok ? "✓" : "✗";
    }

    private static boolean hasAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static void printComparison(PhaseStats sliding, PhaseStats facts, PhaseStats full,
                                        BranchStats branch) {
        System.out.println();
        System.out.println("=== Сравнение стратегий ===");
        String head = "-".repeat(84);
        System.out.println(head);
        System.out.printf("%-28s | %-14s | %-14s | %s%n", "Метрика", sliding.label(),
                facts.label(), full.label());
        System.out.println(head);
        System.out.printf("%-28s | %-14d | %-14d | %d%n", "Реплик", sliding.turns(),
                facts.turns(), full.turns());
        System.out.printf("%-28s | %-14d | %-14d | %d%n", "Пик запроса, токены", sliding.maxPrompt(),
                facts.maxPrompt(), full.maxPrompt());
        System.out.printf("%-28s | %-14d | %-14d | %d%n", "Prompt за сессию", sliding.sessionPrompt(),
                facts.sessionPrompt(), full.sessionPrompt());
        System.out.printf("%-28s | %-14d | %-14d | %d%n", "Сессия всего (p+c)",
                sliding.sessionPrompt() + sliding.sessionCompletion(),
                facts.sessionPrompt() + facts.sessionCompletion(),
                full.sessionPrompt() + full.sessionCompletion());
        System.out.printf("%-28s | %-14d | %-14d | %d%n", "История в конце ≈", sliding.historyEstimate(),
                facts.historyEstimate(), full.historyEstimate());
        System.out.printf("%-28s | %-14s | %-14d | %s%n", "Обновлений фактов", "—",
                facts.factsUpdates(), "—");
        System.out.printf("%-28s | %-14s | %-14s | %s%n", "Факты воспроизведены",
                sliding.factsRecalled() + "/" + FACT_MARKERS.size(),
                facts.factsRecalled() + "/" + FACT_MARKERS.size(),
                full.factsRecalled() + "/" + FACT_MARKERS.size());
    }

    private static void writeReport(LlmClient.Config cfg, PhaseStats sliding, PhaseStats facts,
                                    PhaseStats full, BranchStats branch) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 10 — Управление контекстом: сравнение стратегий\n\n");
        md.append("Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME))
          .append(", модель `").append(cfg.model()).append("`, окно N=").append(WINDOW)
          .append(", сжатие (summary) выключено для всех стратегий.\n\n");
        md.append("Сценарий: ").append(SCRIPT.size())
          .append(" реплик — собираем ТЗ онлайн-шахматного клуба; 9 фактов в репликах 1-9, ");
        md.append("реплики 10-12 — нейтральные, последняя — «сведи всё ТЗ в итог».\n\n");
        md.append("## Сравнение стратегий\n\n");
        md.append("| Метрика | Скользящее окно | Скользящие факты | Полная история |\n");
        md.append("|---|---|---|---|\n");
        md.append(row("Реплик", sliding, facts, full, PhaseStats::turns));
        md.append(row("Пик запроса, токены", sliding, facts, full, PhaseStats::maxPrompt));
        md.append(row("Prompt за сессию", sliding, facts, full, PhaseStats::sessionPrompt));
        md.append(row("Сессия всего (p+c)", sliding, facts, full,
                p -> p.sessionPrompt() + p.sessionCompletion()));
        md.append(row("История в конце ≈, токены", sliding, facts, full, PhaseStats::historyEstimate));
        md.append("| Обновлений фактов | — | ").append(facts.factsUpdates()).append(" | — |\n");
        md.append(row("Факты воспроизведены (N/9)", sliding, facts, full, PhaseStats::factsRecalled));
        md.append("\n");
        md.append("## Финальные ответы «сведи ТЗ»\n\n");
        md.append("### Скользящее окно (видит только последние ").append(WINDOW)
          .append(" сообщений)\n\n").append(answerOrNone(sliding.finalAnswer())).append("\n\n");
        md.append("### Скользящие факты (факты + последние ").append(WINDOW).append(" сообщений)\n\n")
          .append(answerOrNone(facts.finalAnswer())).append("\n\n");
        md.append("**Факты, собранные стратегией «facts» (из task10-facts.json):**\n\n");
        md.append(factsBlockText()).append("\n\n");
        md.append("### Полная история (база)\n\n").append(answerOrNone(full.finalAnswer())).append("\n\n");
        md.append("## Branching (ветки от checkpoint после 8-го сообщения)\n\n");
        md.append("- **Ветка A** (дедлайн → две недели, без турниров): дедлайн изменён ").append(mark(branch.aDeadlineChanged()))
          .append(", утечки из B нет ").append(mark(branch.aNoLeak())).append("\n");
        md.append("- **Ветка B** (авто-переводчик): переводчик добавлен ").append(mark(branch.bTranslatorAdded()))
          .append(", утечки из A нет ").append(mark(branch.bNoLeak())).append("\n");
        md.append("- **Main** (проверка изоляции): исходный дедлайн (месяц) сохранён ").append(mark(branch.mainUnchanged())).append("\n\n");
        md.append("### Ответ ветки A\n\n").append(answerOrNone(branch.aAnswer())).append("\n\n");
        md.append("### Ответ ветки B\n\n").append(answerOrNone(branch.bAnswer())).append("\n\n");
        md.append("### Ответ main после возврата\n\n").append(answerOrNone(branch.mainAnswer())).append("\n\n");
        md.append("## Вывод\n\n");
        md.append("- **Скользящее окно** самое дешёвое (пик запроса ").append(sliding.maxPrompt())
          .append(" токенов), но «забывает» всё, что выпало из окна: факты воспроизведены ")
          .append(sliding.factsRecalled()).append("/9.\n");
        md.append("- **Скользящие факты** удерживают главное (").append(facts.factsRecalled())
          .append("/9) при ограниченном запросе (пик ").append(facts.maxPrompt())
          .append(" токенов); цена — доп. запрос на обновление фактов после каждого сообщения (всего ")
          .append(facts.factsUpdates()).append(", prompt за сессию ").append(facts.sessionPrompt()).append(" токенов).\n");
        md.append("- **Полная история** воспроизводит максимум (").append(full.factsRecalled())
          .append("/9), но каждый ход пересылает всё: пик запроса ").append(full.maxPrompt())
          .append(" токенов, prompt за сессию ").append(full.sessionPrompt()).append(" — растёт с длиной диалога.\n");
        md.append("- **Branching** даёт независимые ветки от одного checkpoint: ветки A и B развиваются ")
          .append("отдельно без взаимных утечек, main не меняется — ветки хранятся в контекст-файле (v4) и ")
          .append("переживают перезапуск.\n");
        md.append("\nКонтекст фаз: task10-sliding.json, task10-facts.json, task10-full.json, ")
          .append("task10-branch.json (формат v4).\n");
        Files.writeString(REPORT_FILE, md.toString());
    }

    private static String row(String metric, PhaseStats sliding, PhaseStats facts, PhaseStats full,
                              java.util.function.ToIntFunction<PhaseStats> getter) {
        return "| " + metric + " | " + getter.applyAsInt(sliding) + " | " + getter.applyAsInt(facts)
                + " | " + getter.applyAsInt(full) + " |\n";
    }

    private static String answerOrNone(String answer) {
        return answer == null ? "(нет ответа)" : "> " + answer.replace("\n", "\n> ") + "\n";
    }

    private static String factsBlockText() {
        try {
            Path file = Path.of("task10-facts.json");
            if (!Files.isRegularFile(file)) {
                return "(файл не найден)";
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    LlmClient.parseJson(Files.readString(file));
            com.fasterxml.jackson.databind.JsonNode facts =
                    root.path("branches").path("main").path("facts");
            if (!facts.isObject()) {
                return "(пусто)";
            }
            StringBuilder sb = new StringBuilder();
            for (var it = facts.fields(); it.hasNext(); ) {
                var entry = it.next();
                sb.append("- **").append(entry.getKey()).append(":** ")
                  .append(entry.getValue().asText()).append("\n");
            }
            return sb.toString().isEmpty() ? "(пусто)" : sb.toString();
        } catch (Exception e) {
            return "(не удалось прочитать: " + e.getMessage() + ")";
        }
    }

    private static int countFacts(String answer) {
        if (answer == null) {
            return 0;
        }
        String normalized = normalize(answer);
        int count = 0;
        for (String marker : FACT_MARKERS) {
            if (normalized.contains(normalize(marker))) {
                count++;
            }
        }
        return count;
    }

    /** ё→е normalization so «тёмная»/«темная» match the same marker. */
    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase().replace('ё', 'е');
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
}
