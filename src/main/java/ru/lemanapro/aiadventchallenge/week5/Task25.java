package ru.lemanapro.aiadventchallenge.week5;

import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Day 25: a mini-chat with RAG and memory (production-like).
 *
 * A thin CLI over RagChat: the chat keeps the dialogue history, searches the knowledge base on every
 * message, answers from what it found and always prints the sources. On top of the history it keeps a
 * "task memory" (RagChat.TaskState): the goal of the dialogue, what the user has clarified, the fixed
 * constraints and terms. The dialogue (state + history) is persisted to task25-chat.json and survives a
 * restart.
 *
 * The default run is a fully automated demonstration — no manual input: two long scripted scenarios
 * (12 messages each) are played through the chat, and every turn is checked:
 *   - the sources are printed (base chunks with quotes / task memory / an explicit "nothing relevant");
 *   - the goal recorded at the first message is still the goal in memory (it is never lost or drifted);
 *   - the constraint fixed at the start is honoured in the answer (no code / at most 4 list items);
 *   - turn-specific expectations: the search query resolves a pronoun or a user term, the memory picked
 *     up a clarification, the answer names the fact, an out-of-base question gets "не знаю".
 * Writes task25-chat-report.md.
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask25                           automated demo: two scripted scenarios + report
 *   mvn -q exec:java -Ptask25 -Dexec.args="chat"                interactive chat (exit / reset / state)
 *   mvn -q exec:java -Ptask25 -Dexec.args="Ваш вопрос"          one message into the persisted chat
 * Env: TASK25_CHAT_FILE (default task25-chat.json), RAG_STRATEGY and the RAG_* settings of RagPipeline.
 */
public final class Task25 {

    private static final Path REPORT_FILE = Path.of("task25-chat-report.md");
    /** A fenced block, or an indented line that looks like a statement: has '(' or '=' and ends with ';' or '{'. */
    private static final Pattern CODE_BLOCK = Pattern.compile("```|^[ \\t]{4,}[^-*•\\d\\s][^\\n]*[(=][^\\n]*[;{][ \\t]*$", Pattern.MULTILINE);
    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+\\S");

    /** What a turn of a scenario must show; null / empty = not checked. */
    record Step(String message, RagChat.Kind kind, List<String> answerHas, String queryHas, String stateHas) {
        static Step of(String message) {
            return new Step(message, null, List.of(), null, null);
        }

        Step kind(RagChat.Kind k) {
            return new Step(message, k, answerHas, queryHas, stateHas);
        }

        Step answer(String... regex) {
            return new Step(message, kind, List.of(regex), queryHas, stateHas);
        }

        Step query(String regex) {
            return new Step(message, kind, answerHas, regex, stateHas);
        }

        Step state(String regex) {
            return new Step(message, kind, answerHas, queryHas, regex);
        }
    }

    record Scenario(String id, String title, String goalRegex, String constraint, Predicate<String> honoursConstraint, List<Step> steps) {
    }

    /** tag: sources | goal | constraint | kind | query | state | answer. */
    record Check(String tag, boolean ok, String text) {
        String line() {
            return (ok ? "[OK]   " : "[FAIL] ") + text;
        }
    }

    record Played(Step step, RagChat.Turn turn, List<Check> checks) {
        boolean ok(String tag) {
            return checks.stream().anyMatch(c -> c.tag().equals(tag)) && checks.stream().filter(c -> c.tag().equals(tag)).allMatch(Check::ok);
        }

        long failed() {
            return checks.stream().filter(c -> !c.ok()).count();
        }
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("S1", "Онбординг-памятка по MCP-части проекта", "памятк|онбординг", "ответы без примеров кода",
                    answer -> !CODE_BLOCK.matcher(answer).find(),
                    List.of(
                            Step.of("Привет! Моя цель — подготовить короткую онбординг-памятку для нового разработчика по MCP-части проекта (дни 16–20). "
                                    + "Отвечай по-русски, без примеров кода, не длиннее пяти предложений. И договоримся о термине: «реестр» — это класс McpRegistry.")
                                    .kind(RagChat.Kind.MEMORY).state("McpRegistry"),
                            Step.of("Что делает реестр и какие серверы в нём зарегистрированы в день 20?")
                                    .kind(RagChat.Kind.KNOWLEDGE).query("McpRegistry").answer("git", "docs", "scheduler"),
                            Step.of("По какому правилу он называет инструменты?")
                                    .kind(RagChat.Kind.KNOWLEDGE).query("McpRegistry|реестр").answer("__"),
                            Step.of("А что будет, если вызвать инструмент, которого нет ни у одного сервера?")
                                    .kind(RagChat.Kind.KNOWLEDGE).answer("ошибк|isError|error"),
                            Step.of("Уточнение: у новичка на машине нет Node.js. Как ему тогда запустить день 16?")
                                    .kind(RagChat.Kind.KNOWLEDGE).state("Node").answer("local|встроенн|LocalServer"),
                            Step.of("Какие инструменты есть у git-сервера дня 17?")
                                    .kind(RagChat.Kind.KNOWLEDGE).answer("git_log", "git_commit_details", "git_branches"),
                            Step.of("Покажи, как выглядит прямой вызов git_log из приложения.")
                                    .kind(RagChat.Kind.KNOWLEDGE),
                            Step.of("Отвлечёмся на минуту: что делает планировщик дня 18 после перезапуска?")
                                    .kind(RagChat.Kind.KNOWLEDGE).answer("task18-scheduler|json|lastRunAt|перезапуск|задани"),
                            Step.of("А какими переменными окружения настраиваются его интервалы?")
                                    .kind(RagChat.Kind.KNOWLEDGE).query("планировщик|scheduler|Task18|TASK18").answer("TASK18_(COLLECT|SUMMARY|REMINDER)_SECONDS"),
                            Step.of("Напомни, какая у нас цель и какие ограничения я задал?")
                                    .kind(RagChat.Kind.MEMORY).answer("памятк|онбординг", "код"),
                            Step.of("С учётом моего уточнения про окружение новичка — какой командой ему запускать день 16?")
                                    .kind(RagChat.Kind.KNOWLEDGE).query("Node|local|день 16|Task16|task16").answer("local"),
                            Step.of("Собери итог: план памятки из пяти пунктов, по одному на каждый день с 16 по 20.")
                                    .kind(RagChat.Kind.KNOWLEDGE).answer("16", "20", "MCP"))),
            new Scenario("S2", "Выбор стратегии контекста и памяти для бота поддержки", "поддержк|стратеги", "ответ — список не более чем из 4 пунктов",
                    answer -> LIST_ITEM.matcher(answer).results().count() <= 4,
                    List.of(
                            Step.of("Цель: выбрать, какую стратегию управления контекстом и какую модель памяти из этого проекта взять за основу для чат-бота поддержки. "
                                    + "Отвечай списком не более чем из 4 пунктов.")
                                    .kind(RagChat.Kind.MEMORY).state("4"),
                            Step.of("Какие стратегии управления контекстом сравнивались в день 10?")
                                    .kind(RagChat.Kind.KNOWLEDGE).answer("sliding|скользящ|окн", "facts|факт"),
                            Step.of("И к какому выводу пришли в том сравнении?")
                                    .kind(RagChat.Kind.KNOWLEDGE).query("день 10|Task10|стратеги|strateg"),
                            Step.of("Договоримся о термине: «окно» — это число последних сообщений, которые агент отправляет без сжатия. Чему оно равно по умолчанию?")
                                    .kind(RagChat.Kind.KNOWLEDGE).state("окно").answer("(?<!\\d)6(?!\\d)"),
                            Step.of("Как его изменить?")
                                    .kind(RagChat.Kind.KNOWLEDGE).query("AGENT_KEEP_RECENT|окно|последних сообщени").answer("AGENT_KEEP_RECENT"),
                            Step.of("Важное уточнение: диалоги у нас длинные, по 50 и более сообщений, экономия токенов критична. Что показал эксперимент дня 9 про экономию?")
                                    .kind(RagChat.Kind.KNOWLEDGE).state("50|длинн").answer("28|74|токен"),
                            Step.of("А качество ответов при сжатии не пострадало?")
                                    .kind(RagChat.Kind.KNOWLEDGE).query("сжати|compress").answer("5\\s*/\\s*5|5 из 5|не (пострада|потеря)|все (5|пять)"),
                            Step.of("Какие слои памяти есть в модели дня 11?")
                                    .kind(RagChat.Kind.KNOWLEDGE).answer("SHORT_TERM|краткосрочн", "WORKING|рабоч", "LONG_TERM|долговременн|долгосрочн"),
                            Step.of("Какой из них переживает перезапуск?")
                                    .kind(RagChat.Kind.KNOWLEDGE).query("памят|memory|LONG_TERM|слои|слой").answer("LONG_TERM|долговременн|долгосрочн"),
                            Step.of("К какой цели мы идём и что я уже уточнил про наши диалоги?")
                                    .kind(RagChat.Kind.MEMORY).answer("поддержк|стратеги", "50|длинн"),
                            Step.of("Есть ли в проекте готовая интеграция с Redis для хранения истории диалога?")
                                    .kind(RagChat.Kind.UNKNOWN),
                            Step.of("Подведи итог: что из проекта брать за основу для нашего бота, с учётом длинных диалогов?")
                                    .kind(RagChat.Kind.KNOWLEDGE).answer("сжати|пересказ|summary|compress", "памят|LONG_TERM"))));

    private Task25() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        HttpClient http = LlmClient.newHttpClient();
        RagIndex index = RagIndex.open(RagIndex.Strategy.of(LlmClient.env("RAG_STRATEGY", "structure")), http, cfg);
        RagPipeline pipeline = new RagPipeline(http, cfg, index);
        RagPipeline.Settings settings = RagPipeline.Settings.forIndex(index);

        System.out.println("=== День 25. Мини-чат с RAG и памятью задачи ===");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println("Индекс: " + index.meta().strategy() + ", " + index.chunks().size() + " чанков, эмбеддер " + index.meta().embedder());

        if (args.length == 0 || args[0].equalsIgnoreCase("demo")) { // the default: no manual input at all
            System.out.println("Режим: автоматическая демонстрация — " + SCENARIOS.size() + " сценария по " + SCENARIOS.getFirst().steps().size()
                    + " сообщений, ввод не требуется. Интерактивный чат: -Dexec.args=\"chat\"");
            demo(cfg, index, pipeline, settings);
            return;
        }

        RagChat chat = new RagChat(pipeline, settings, Path.of(LlmClient.env("TASK25_CHAT_FILE", "task25-chat.json")));
        System.out.println("Поиск: " + chat.settings().describe());
        if (chat.turnCount() > 0) {
            System.out.println("Диалог восстановлен: реплик " + chat.turnCount() + "; память — " + chat.state().oneLine());
        }
        if (!args[0].equalsIgnoreCase("chat")) {
            show(chat.send(String.join(" ", args)));
            return;
        }
        System.out.println("Введите вопрос. Команды: exit — выход, reset — новый диалог, state — показать память задачи.");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("\nВы: ");
                String line = in.readLine();
                if (line == null || line.strip().equalsIgnoreCase("exit") || line.strip().equalsIgnoreCase("quit")) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                if (line.strip().equalsIgnoreCase("reset")) {
                    chat.reset();
                    System.out.println("Диалог и память задачи сброшены.");
                    continue;
                }
                if (line.strip().equalsIgnoreCase("state")) {
                    System.out.println(chat.state().block());
                    continue;
                }
                show(chat.send(line.strip()));
            }
        }
        System.out.println("Чат завершён (реплик: " + chat.turnCount() + "). Диалог сохранён и продолжится при следующем запуске.");
    }

    private static void show(RagChat.Turn turn) {
        System.out.println("\nАссистент: " + turn.render());
        System.out.println("[" + turn.kind().label + " | поиск: «" + Chunker.clip(turn.searchQuery(), 90) + "» → чанков "
                + (turn.retrieval() == null ? 0 : turn.retrieval().before().size()) + "→" + (turn.retrieval() == null ? 0 : turn.retrieval().after().size())
                + " | память — " + turn.state().oneLine() + " | " + turn.millis() + " мс]");
        turn.stateChanges().forEach(c -> System.out.println("  + в память: " + c));
        turn.notes().forEach(n -> System.out.println("  примечание: " + n));
    }

    // ---------------------------------------------------------------- scripted scenarios

    private static void demo(LlmClient.Config cfg, RagIndex index, RagPipeline pipeline, RagPipeline.Settings settings) throws Exception {
        List<List<Played>> all = new ArrayList<>();
        for (Scenario sc : SCENARIOS) {
            System.out.println("\n=== Сценарий " + sc.id() + ": " + sc.title() + " (" + sc.steps().size() + " сообщений) ===");
            Path file = Path.of("task25-scenario-" + sc.id().toLowerCase() + ".json");
            Files.deleteIfExists(file);
            RagChat chat = new RagChat(pipeline, settings, file);
            List<Played> played = new ArrayList<>();
            String goal = null;
            for (Step step : sc.steps()) {
                System.out.println("\n[" + (played.size() + 1) + "] Пользователь: " + step.message());
                RagChat.Turn turn = chat.send(step.message());
                show(turn);
                if (goal == null && !turn.state().goal.isBlank()) {
                    goal = turn.state().goal;
                }
                List<Check> checks = check(sc, step, turn, goal);
                checks.forEach(c -> System.out.println("    " + c.line()));
                played.add(new Played(step, turn, checks));
            }
            all.add(played);
            System.out.printf("%nСценарий %s: источники выведены %d/%d, цель в памяти %d/%d, ограничение соблюдено %d/%d, замечаний %d%n", sc.id(),
                    count(played, "sources"), played.size(), count(played, "goal"), played.size(), count(played, "constraint"), played.size(),
                    played.stream().mapToLong(Played::failed).sum());
        }
        writeReport(cfg, index, pipeline, settings, all);
        System.out.println("Отчёт: " + REPORT_FILE.toAbsolutePath());
    }

    private static long count(List<Played> played, String tag) {
        return played.stream().filter(p -> p.ok(tag)).count();
    }

    private static List<Check> check(Scenario sc, Step step, RagChat.Turn turn, String goal) {
        List<Check> c = new ArrayList<>();
        if (turn.kind() == RagChat.Kind.ERROR) {
            c.add(new Check("sources", false, "источники: вызов не удался — " + turn.reply().answer()));
            return c;
        }
        String answer = turn.reply().answer();
        c.add(new Check("sources", turn.showsSources(), "источники выведены: " + switch (turn.kind()) {
            case KNOWLEDGE -> turn.reply().sources().size() + " из базы, цитат " + turn.reply().quotes().size();
            case MEMORY -> "память задачи";
            default -> "явно сказано, что в базе нет релевантного";
        }));
        c.add(new Check("goal", goal != null && goal.equals(turn.state().goal) && find(sc.goalRegex(), goal),
                "цель в памяти та же, что задана в начале: «" + Chunker.clip(turn.state().goal, 80) + "»"));
        c.add(new Check("constraint", sc.honoursConstraint().test(answer), "ограничение из начала диалога: " + sc.constraint()));
        if (step.kind() != null) {
            c.add(new Check("kind", turn.kind() == step.kind(), "тип ответа: ожидался «" + step.kind().label + "», получен «" + turn.kind().label + "»"));
        }
        if (step.queryHas() != null) {
            c.add(new Check("query", find(step.queryHas(), turn.searchQuery()), "поисковый запрос раскрывает контекст диалога (/" + step.queryHas() + "/): «"
                    + Chunker.clip(turn.searchQuery(), 90) + "»"));
        }
        if (step.stateHas() != null) {
            c.add(new Check("state", find(step.stateHas(), turn.state().block()), "память задачи зафиксировала сказанное (/" + step.stateHas() + "/)"));
        }
        for (String regex : step.answerHas()) {
            c.add(new Check("answer", find(regex, answer), "ответ содержит /" + regex + "/"));
        }
        return c;
    }

    private static boolean find(String regex, String text) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text == null ? "" : text).find();
    }

    // ---------------------------------------------------------------- report

    private static void writeReport(LlmClient.Config cfg, RagIndex index, RagPipeline pipeline, RagPipeline.Settings settings, List<List<Played>> all) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 25. Мини-чат с RAG и памятью задачи\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask25`, модель ").append(cfg.model()).append(", индекс `")
                .append(index.meta().strategy()).append("` (").append(index.chunks().size()).append(" чанков, эмбеддер `").append(index.meta().embedder()).append("`)\n\n");
        md.append("```\nсообщение ─▶ ПЛАН (LLM, JSON) ──┬─▶ поисковый запрос с раскрытым контекстом ─▶ RAG: поиск + этап 2 ─▶ ответ + источники + цитаты\n")
                .append("  история (последние 6) ───────┤                                                 контекст пуст ─▶ «не знаю» + уточнение\n")
                .append("  память задачи ───────────────┴─▶ дельта памяти ─▶ слияние В КОДЕ               вопрос о диалоге ─▶ ответ из памяти задачи\n")
                .append("                                   (цель / уточнения / ограничения / термины) ─▶ JSON-файл, переживает перезапуск\n```\n\n");
        md.append("Поиск: ").append(settings.with(settings.rerank(), false).describe()).append(" (переформулировку делает планировщик чата — с учётом истории).\n\n");
        md.append("**Память задачи** (`RagChat.TaskState`) — отдельная от истории структура: `goal`, `clarified`, `constraints`, `terms`. ")
                .append("Модель предлагает только дельту по текущему сообщению, а сливает её код: списки растут без дублей, уже заданную цель можно заменить ")
                .append("только сообщением, в котором пользователь сам говорит о цели. В модель уходят последние 6 реплик и вся память задачи — ")
                .append("поэтому цель и ограничения не зависят от длины диалога.\n\n");

        md.append("## Итоги по сценариям\n\n| Сценарий | Сообщений | Источники выведены | Цель в памяти сохранена | Ограничение соблюдено | Ответов по базе / из памяти / «не знаю» | Проверок пройдено |\n|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < all.size(); i++) {
            Scenario sc = SCENARIOS.get(i);
            List<Played> p = all.get(i);
            long total = p.stream().mapToLong(x -> x.checks().size()).sum();
            long failed = p.stream().mapToLong(Played::failed).sum();
            md.append("| ").append(sc.id()).append(". ").append(sc.title()).append(" | ").append(p.size()).append(" | ").append(count(p, "sources")).append("/").append(p.size())
                    .append(" | ").append(count(p, "goal")).append("/").append(p.size()).append(" | ").append(count(p, "constraint")).append("/").append(p.size()).append(" | ")
                    .append(kinds(p, RagChat.Kind.KNOWLEDGE)).append(" / ").append(kinds(p, RagChat.Kind.MEMORY)).append(" / ").append(kinds(p, RagChat.Kind.UNKNOWN))
                    .append(" | ").append(total - failed).append("/").append(total).append(" |\n");
        }

        for (int i = 0; i < all.size(); i++) {
            Scenario sc = SCENARIOS.get(i);
            List<Played> played = all.get(i);
            md.append("\n## Сценарий ").append(sc.id()).append(". ").append(sc.title()).append("\n\n");
            md.append("Ограничение, которое проверяется на каждом ответе: **").append(sc.constraint()).append("**.\n\n");
            md.append("| # | Сообщение пользователя | Поисковый запрос (после планировщика) | Ответ | Источников / цитат | Замечания |\n|---|---|---|---|---|---|\n");
            for (Played p : played) {
                RagChat.Turn t = p.turn();
                md.append("| ").append(t.no()).append(" | ").append(Task21.esc(Chunker.clip(t.user(), 90))).append(" | ").append(Task21.esc(Chunker.clip(t.searchQuery(), 90)))
                        .append(" | ").append(t.kind().label).append(" | ").append(t.reply().sources().size()).append(" / ").append(t.reply().quotes().size()).append(" | ")
                        .append(p.failed() == 0 ? "—" : p.checks().stream().filter(c -> !c.ok()).map(c -> "не выполнено: " + Task21.esc(c.text())).collect(Collectors.joining("<br>")))
                        .append(" |\n");
            }
            md.append("\n### Как менялась память задачи\n\n| После сообщения | Что добавлено |\n|---|---|\n");
            for (Played p : played) {
                if (!p.turn().stateChanges().isEmpty()) {
                    md.append("| ").append(p.turn().no()).append(" | ").append(p.turn().stateChanges().stream().map(Task21::esc).collect(Collectors.joining("<br>"))).append(" |\n");
                }
            }
            RagChat.TaskState last = played.getLast().turn().state();
            md.append("\nПамять в конце диалога:\n\n```\n").append(last.block().lines().limit(5).collect(Collectors.joining("\n"))).append("\n```\n\n");
            md.append("<details><summary>Полный диалог</summary>\n\n");
            for (Played p : played) {
                md.append("**[").append(p.turn().no()).append("] Пользователь:** ").append(p.turn().user()).append("\n\n");
                md.append(Task22.quote(p.turn().render())).append("\n\n");
                p.checks().forEach(c -> md.append("- ").append(c.line().trim()).append("\n"));
                p.turn().notes().forEach(n -> md.append("- _").append(n).append("_\n"));
                md.append("\n");
            }
            md.append("</details>\n");
        }

        md.append("\n## Выводы\n\n");
        long turns = all.stream().mapToLong(List::size).sum();
        long sources = all.stream().mapToLong(p -> count(p, "sources")).sum();
        long goals = all.stream().mapToLong(p -> count(p, "goal")).sum();
        long constraints = all.stream().mapToLong(p -> count(p, "constraint")).sum();
        md.append("1. **Источники выводятся всегда: ").append(sources).append("/").append(turns).append(" сообщений.** Для ответов по базе это проверенные чанки с цитатами, ")
                .append("для вопросов о самом диалоге — явная пометка «память задачи», для вопросов без опоры в базе — честное «не знаю» с просьбой уточнить.\n");
        md.append("2. **Цель не теряется: ").append(goals).append("/").append(turns).append(".** Цель записывается в память один раз и дальше защищена кодом: ")
                .append("модель не может переписать её очередным вопросом. ");
        long drift = all.stream().flatMap(List::stream).flatMap(p -> p.turn().notes().stream()).filter(n -> n.contains("сменить цель")).count();
        md.append(drift > 0 ? "За два сценария планировщик " + drift + " раз пытался подменить цель текущим вопросом — код это отклонил.\n"
                : "Попыток подменить цель текущим вопросом за два сценария не было.\n");
        md.append("3. **Ограничения соблюдаются в ").append(constraints).append("/").append(turns).append(" ответов.** Они живут в памяти задачи и попадают в системный промпт каждого ответа, ")
                .append("в том числе через 10+ сообщений после того, как были заданы и ушли из окна истории.").append(constraints < turns
                        ? " Нарушения — там, где требование формата конфликтует с прямой просьбой пользователя или с форматом JSON-ответа; это ограничение «инструкцией», а не жёсткой проверкой.\n" : "\n");
        md.append("4. **Раскрытие контекста в поисковом запросе.** Вопросы вида «как он называет инструменты?» или «как его изменить?» без истории не ищутся: ")
                .append("планировщик превращает их в самодостаточные запросы и подставляет термины пользователя (см. столбец «Поисковый запрос»).\n");
        long failedTotal = all.stream().flatMap(List::stream).mapToLong(Played::failed).sum();
        md.append("5. **Замечаний по всем проверкам: ").append(failedTotal).append(".** ").append(failedTotal == 0
                ? "Оба сценария пройдены без замечаний.\n"
                : "Они перечислены в таблицах сценариев; проверки — регулярные выражения по ответу, поэтому часть замечаний может быть формальной (факт назван другими словами).\n");
        md.append("6. **Цена.** На сообщение уходит до трёх вызовов LLM: планировщик, реранкер, ответ. Всего за прогон: ").append(pipeline.llmCalls())
                .append(" вызовов, ").append(pipeline.tokens()).append(" токенов.\n");
        Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
    }

    private static long kinds(List<Played> played, RagChat.Kind kind) {
        return played.stream().filter(p -> p.turn().kind() == kind).count();
    }
}
