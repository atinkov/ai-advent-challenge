package ru.lemanapro.aiadventchallenge.week7;

import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;
import ru.lemanapro.aiadventchallenge.week4.McpToolAgent;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Day 31: «Ассистент разработчика» — a CLI that knows THIS project.
 *
 *   RAG  — ProjectDocs: README.md + docs/ (architecture, data schemas, API descriptions) + AGENTS.md
 *   MCP  — ProjectMcpServer (stdio child process): git_current_branch (always called), git_status, list_files, git_diff
 *   LLM  — DevAssistant: documentation fragments + current branch in the prompt, MCP tools in the tool loop,
 *          citations [n] verified in code
 *
 * DEFAULT RUN = automatic check of the assignment, condition by condition (no manual input):
 *   Условие 1. RAG подключён к документации проекта: README, project/docs, схемы данных / API-описания
 *   Условие 2. Ассистент подключён к проекту через MCP: минимум — текущая git-ветка; по желанию — файлы, diff
 *   Условие 3. Команда /help: отвечает на вопросы о проекте, используя документацию и контекст проекта
 * Every check runs through the same code paths a developer uses (the command dispatcher handle(), the MCP
 * server over stdio, the real index); the result + a final verdict go to task31-dev-assistant-report.md.
 *
 * Commands (interactive mode, `chat` argument):
 *   /help                 — the list of commands + where the knowledge comes from
 *   /help <вопрос>        — answer a question about the project from the docs and the repository state
 *   <вопрос>              — same as /help <вопрос>
 *   /branch               — current git branch (MCP git_current_branch)
 *   /status               — uncommitted changes (MCP git_status)
 *   /files [маска|папка]  — project files (MCP list_files), e.g. /files docs/**
 *   /diff [путь]          — uncommitted diff (MCP git_diff)
 *   /docs                 — what is in the knowledge base (files, groups, chunks)
 *   /reindex              — rebuild the documentation index
 *   /tools                — MCP tools of the project server
 *   /exit                 — quit
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask31                                      # automatic check of the assignment + report
 *   mvn -q exec:java -Ptask31 -Dexec.args="chat"                          # interactive assistant
 *   mvn -q exec:java -Ptask31 -Dexec.args="/help как запустить день 30?"  # one command
 *
 * Env: TASK31_REPO (project root, default "."), TASK31_DOC_PATHS, TASK31_TOP_K, TASK31_TOOL_MODE (auto|native|prompt),
 *      plus the usual LLM_API_KEY / HINDSIGHT_API_LLM_* and EMBEDDING_* (see ProjectDocs / LlmClient).
 */
public final class Task31 {

    private static final Path REPORT = Path.of("task31-dev-assistant-report.md");

    /** /help questions of the check: expected doc source, a regex the answer must match, an expected MCP tool. */
    record Probe(String question, String expectSource, Pattern expectAnswer, String expectTool, boolean expectBranch, String why) {
    }

    /** Mandatory questions — they prove condition 3. */
    private static final List<Probe> REQUIRED_PROBES = List.of(
            new Probe("Какая структура у проекта: какие есть папки и пакеты и что лежит в каждом?",
                    "README.md|docs/architecture.md", Pattern.compile("week\\d|src/main"), null, false,
                    "вопрос о структуре проекта → ответ по README/docs со ссылками"),
            new Probe("В какой ветке я сейчас работаю и по какому правилу в проекте называют ветки?",
                    "AGENTS.md|README.md|docs/", null, null, true,
                    "контекст проекта: ветка из MCP + правило из документации"),
            new Probe("Какие файлы сейчас изменены или добавлены в рабочей копии, но ещё не закоммичены?",
                    null, null, "git_status|git_diff", false,
                    "контекст проекта: модель сама вызывает MCP (статус/diff)"));

    /** Extra questions — documentation coverage, they are reported but do not decide the verdict. */
    private static final List<Probe> EXTRA_PROBES = List.of(
            new Probe("Какие поля у файла task23-rag-settings.json и что означает minSimilarity?",
                    "docs/schemas/|docs/data-schemas.md", Pattern.compile("minSimilarity"), null, false, "схема данных"),
            new Probe("Какие эндпоинты у приватного AI-сервиса дня 30 и какие коды ошибок он возвращает?",
                    "docs/api/", Pattern.compile("/v1/chat/completions"), null, false, "API-описание (OpenAPI)"),
            new Probe("Какой рецепт борща описан в документации проекта?",
                    null, Pattern.compile("(?i)нет|не (описан|найден|содерж)"), null, false, "вопрос не по теме → честное «этого нет»"));

    /** The last /help answer printed by handle() — the check reads it after running the real command. */
    private static DevAssistant.HelpAnswer lastAnswer;

    private Task31() {
    }

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(LlmClient.env("TASK31_REPO", ".")).toAbsolutePath().normalize();
        LlmClient.Config cfg = LlmClient.env("LLM_API_KEY", null) == null ? null : LlmClient.fromEnv();
        String argLine = String.join(" ", args).strip();

        try (DevAssistant assistant = new DevAssistant(repo, cfg)) {
            if (argLine.isEmpty() || argLine.equalsIgnoreCase("check")) {
                check(assistant, repo);
                return;
            }
            System.out.println(banner(assistant));
            if (!argLine.equalsIgnoreCase("chat")) {
                handle(assistant, argLine);
                return;
            }
            System.out.println("Введите /help — список команд, или сразу вопрос о проекте. /exit — выход.\n");
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                System.out.print("dev> ");
                System.out.flush();
                String line = in.readLine();
                if (line == null) break;
                line = line.strip();
                if (line.isEmpty()) continue;
                if (!handle(assistant, line)) break;
            }
        }
    }

    // ---------------------------------------------------------------- commands

    /** @return false to quit */
    static boolean handle(DevAssistant a, String line) {
        String cmd = line.startsWith("/") ? line.split("\\s+", 2)[0].toLowerCase() : "";
        String arg = line.startsWith("/") ? (line.contains(" ") ? line.substring(line.indexOf(' ') + 1).strip() : "") : line;
        try {
            switch (cmd) {
                case "/exit", "/quit" -> {
                    return false;
                }
                case "/help", "" -> {
                    if (arg.isEmpty()) System.out.println(usage(a));
                    else printAnswer(a.help(arg));
                }
                case "/branch" -> System.out.println(a.callText("git_current_branch", Map.of()));
                case "/status" -> System.out.println(a.callText("git_status", Map.of()));
                case "/files" -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    if (!arg.isEmpty()) {
                        if (arg.contains("*") || arg.contains("?")) p.put("glob", arg);
                        else p.put("path", arg);
                    }
                    System.out.println(a.callText("list_files", p));
                }
                case "/diff" -> System.out.println(a.callText("git_diff", arg.isEmpty() ? Map.of() : Map.of("path", arg)));
                case "/docs" -> System.out.println(docsTable(a));
                case "/reindex" -> {
                    a.docs().reindex();
                    System.out.println(a.docs().buildNote());
                }
                case "/tools" -> {
                    for (McpSchema.Tool t : a.tools()) System.out.println("  " + t.name() + " — " + t.description());
                }
                default -> System.out.println("Неизвестная команда " + cmd + ". /help — список команд.");
            }
        } catch (Exception e) {
            System.out.println("Ошибка: " + e.getMessage());
        }
        System.out.println();
        return true;
    }

    static String banner(DevAssistant a) throws Exception {
        DevAssistant.BranchInfo b = a.branch();
        a.docs().index();
        return """
                ══ Ассистент разработчика AIAdventChallenge (день 31) ══
                MCP: %s %s, инструментов %d | ветка: %s
                Документация: %s
                LLM: %s
                """.formatted(a.serverInit().serverInfo().name(), a.serverInit().serverInfo().version(), a.tools().size(),
                b.detached() ? "(detached " + b.head() + ")" : b.branch(), a.docs().buildNote(),
                a.hasLlm() ? a.model() : "не настроен (нет LLM_API_KEY): /help покажет найденные фрагменты без ответа модели");
    }

    static String usage(DevAssistant a) throws Exception {
        DevAssistant.BranchInfo b = a.branch();
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (ProjectDocs.SourceStat s : a.docs().sources()) groups.merge(s.group(), 1, Integer::sum);
        StringBuilder g = new StringBuilder();
        groups.forEach((k, v) -> g.append(g.isEmpty() ? "" : ", ").append(k).append(" — ").append(v));
        return """
                Команды ассистента:
                  /help <вопрос>        ответ на вопрос о проекте по документации и состоянию репозитория
                  <вопрос>              то же самое без /help
                  /branch               текущая git-ветка (MCP git_current_branch)
                  /status               незакоммиченные изменения (MCP git_status)
                  /files [маска|папка]  файлы проекта (MCP list_files), например /files docs/**
                  /diff [путь]          diff рабочей копии (MCP git_diff)
                  /docs                 что лежит в базе знаний
                  /reindex              перестроить индекс документации
                  /tools                инструменты MCP-сервера проекта
                  /exit                 выход

                Откуда ассистент знает о проекте:
                  документация (RAG): %s
                  репозиторий (MCP):  ветка %s, сервер %s
                Примеры: /help как добавить новый день?   /help какие поля у task23-rag-settings.json?   /help что я поменял?"""
                .formatted(g, b.detached() ? "(detached)" : b.branch(), a.serverInit().serverInfo().name());
    }

    static String docsTable(DevAssistant a) throws Exception {
        StringBuilder sb = new StringBuilder("База знаний (" + a.docs().embedderId() + "):\n");
        for (ProjectDocs.SourceStat s : a.docs().sources()) {
            sb.append(String.format("  %-48s %-26s %3d фрагм.%n", s.source(), s.group(), s.chunks()));
        }
        return sb.toString();
    }

    static void printAnswer(DevAssistant.HelpAnswer r) {
        lastAnswer = r;
        System.out.println("[MCP git_current_branch] " + (r.branch().detached() ? "(detached HEAD)" : r.branch().branch())
                + " · " + r.branch().millis() + " мс");
        System.out.println("[RAG] фрагментов: " + r.fragments().size());
        for (McpToolAgent.ToolCallTrace t : r.toolCalls()) {
            System.out.println("[MCP вызов модели] " + t.tool() + " " + t.argumentsJson() + (t.isError() ? " — ошибка" : ""));
        }
        System.out.println();
        if (r.error() != null) {
            System.out.println("⚠ " + r.error());
            if (!r.fragments().isEmpty()) {
                System.out.println("\nНайденные фрагменты документации:");
                for (ProjectDocs.Fragment f : r.fragments()) {
                    System.out.printf("  [%d] %s  (%.3f)%n      %s%n", f.n(), f.label(), f.score(),
                            DevAssistant.clip(f.chunk().text().strip().replaceAll("\\s+", " "), 220));
                }
            }
            return;
        }
        System.out.println(r.answer());
        System.out.println();
        if (!r.cited().isEmpty()) {
            System.out.println("Источники:");
            for (ProjectDocs.Fragment f : r.cited()) System.out.println("  [" + f.n() + "] " + f.label());
        } else if (!r.fragments().isEmpty()) {
            System.out.println("Контекст (модель не сослалась на фрагменты):");
            for (ProjectDocs.Fragment f : r.fragments()) System.out.println("  [" + f.n() + "] " + f.label());
        }
        if (!r.invalidCitations().isEmpty()) {
            System.out.println("  ⚠ ссылки на несуществующие фрагменты отброшены: " + r.invalidCitations());
        }
        System.out.println("(" + r.millis() / 1000.0 + " с, запросов к LLM: " + r.llmRequests() + ")");
    }

    // ---------------------------------------------------------------- automatic check of the assignment

    /** One check. required=false — «по желанию» / extra coverage: reported, does not decide the verdict. */
    record Check(String condition, String what, boolean required, boolean ok, String details) {
    }

    static final String C1 = "1. RAG по документации проекта";
    static final String C2 = "2. MCP-подключение к проекту";
    static final String C3 = "3. Команда /help";

    /** Runs a command through the real dispatcher and returns what it printed (and still prints it). */
    static String run(DevAssistant a, String line) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                buf.write(b);
                original.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                buf.write(b, off, len);
                original.write(b, off, len);
            }
        }, true, StandardCharsets.UTF_8);
        lastAnswer = null;
        System.setOut(tee);
        try {
            original.println("dev> " + line);
            handle(a, line);
        } finally {
            System.out.flush();
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    static void check(DevAssistant a, Path repo) throws Exception {
        List<Check> checks = new ArrayList<>();
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        System.out.println("══ День 31: автоматическая проверка задания ══\n");

        // ============ Условие 1: RAG по README, project/docs, схемам / API-описаниям
        System.out.println("── " + C1);
        a.docs().index();
        System.out.println("   " + a.docs().buildNote());
        List<ProjectDocs.SourceStat> sources = a.docs().sources();
        List<String> indexed = sources.stream().map(ProjectDocs.SourceStat::source).toList();

        boolean readme = indexed.contains("README.md");
        checks.add(new Check(C1, "README.md подключён к RAG (есть в индексе)", true, readme,
                readme ? chunksOf(sources, "README.md") + " фрагм." : "README.md нет в индексе"));

        List<String> docsOnDisk = docsOnDisk(repo);
        List<String> docsMissing = docsOnDisk.stream().filter(f -> !indexed.contains(f)).toList();
        checks.add(new Check(C1, "Папка docs/ подключена к RAG: все её файлы в индексе", true,
                !docsOnDisk.isEmpty() && docsMissing.isEmpty(),
                docsOnDisk.isEmpty() ? "папки docs/ нет или она пуста"
                        : "в docs/ файлов " + docsOnDisk.size() + ", в индексе " + (docsOnDisk.size() - docsMissing.size())
                        + (docsMissing.isEmpty() ? "" : "; нет: " + String.join(", ", docsMissing))));

        long schemas = sources.stream().filter(s -> s.group().equals("схемы данных")).count();
        long apis = sources.stream().filter(s -> s.group().equals("API")).count();
        checks.add(new Check(C1, "Схемы данных или API-описания в индексе", true, schemas + apis > 0,
                "схем данных: " + schemas + " (" + join(sources, "схемы данных") + "); API: " + apis + " (" + join(sources, "API") + ")"));

        List<ProjectDocs.Fragment> probe = a.docs().retrieve(REQUIRED_PROBES.getFirst().question());
        boolean retrieves = probe.stream().anyMatch(f -> f.chunk().source().equals("README.md") || f.chunk().source().startsWith("docs/"));
        checks.add(new Check(C1, "Поиск по вопросу о структуре находит README / docs", true, retrieves,
                String.join("; ", probe.stream().map(f -> "[" + f.n() + "] " + f.label() + String.format(" %.3f", f.score())).toList())));

        // ============ Условие 2: MCP — минимум git branch, по желанию файлы / diff
        System.out.println("── " + C2);
        List<String> names = a.tools().stream().map(McpSchema.Tool::name).toList();
        checks.add(new Check(C2, "MCP handshake (initialize) с " + ProjectMcpServer.NAME, true, a.serverInit() != null,
                "протокол " + a.serverInit().protocolVersion() + ", " + a.serverCommand()));
        checks.add(new Check(C2, "tools/list содержит git_current_branch", true, names.contains("git_current_branch"), String.join(", ", names)));

        String branchOut = run(a, "/branch");
        DevAssistant.BranchInfo b = a.branch();
        String direct = gitDirect(repo, "branch", "--show-current");
        boolean branchOk = !b.detached() ? b.branch().equals(direct) && branchOut.contains(direct) : direct.isEmpty();
        checks.add(new Check(C2, "Текущая git-ветка через MCP = `git branch --show-current` (команда /branch)", true, branchOk,
                "MCP: «" + (b.detached() ? "(detached)" : b.branch()) + "», git: «" + direct + "», " + b.millis() + " мс"));

        String filesOut = run(a, "/files docs/**");
        boolean filesOk = !docsOnDisk.isEmpty() && docsOnDisk.stream().allMatch(filesOut::contains);
        checks.add(new Check(C2, "По желанию: список файлов (MCP list_files, команда /files)", false, filesOk,
                DevAssistant.clip(filesOut.lines().filter(l -> l.startsWith("Файлов")).findFirst().orElse(filesOut.strip()), 120)));

        String diffOut = run(a, "/diff");
        boolean diffOk = diffOut.contains("Diff:");
        checks.add(new Check(C2, "По желанию: diff (MCP git_diff, команда /diff)", false, diffOk,
                DevAssistant.clip(diffOut.lines().filter(l -> l.startsWith("Diff:")).findFirst().orElse(diffOut.strip()), 140)));

        // ============ Условие 3: /help отвечает на вопросы о проекте по документации и контексту проекта
        System.out.println("── " + C3);
        String usageOut = run(a, "/help");
        boolean usageOk = usageOut.contains("/help <вопрос>") && usageOut.contains("/branch");
        checks.add(new Check(C3, "Команда /help без аргументов показывает справку по командам", true, usageOk,
                usageOk ? "список команд + источники знаний (документация, ветка " + b.branch() + ")" : DevAssistant.clip(usageOut, 120)));

        boolean llm = a.hasLlm();
        if (!llm) {
            checks.add(new Check(C3, "LLM настроен для ответов /help", true, false,
                    "нет LLM_API_KEY (env или .env) — ответы модели проверить нельзя"));
        }

        List<String[]> rows = new ArrayList<>();
        StringBuilder answers = new StringBuilder();
        int n = 0;
        for (Probe p : concat(REQUIRED_PROBES, EXTRA_PROBES)) {
            n++;
            boolean required = REQUIRED_PROBES.contains(p);
            String out = run(a, "/help " + p.question());
            DevAssistant.HelpAnswer r = lastAnswer;
            List<String> verdicts = new ArrayList<>();
            boolean ok = r != null && r.error() == null;
            if (r == null) {
                verdicts.add("❌ команда /help не вернула ответ");
            } else {
                if (r.error() != null) verdicts.add("❌ " + r.error());
                boolean mcpBranch = !r.branch().branch().isEmpty() || r.branch().detached();
                verdicts.add((mcpBranch ? "✅" : "❌") + " контекст проекта: ветка `" + r.branch().branch() + "` из MCP в промпте");
                ok &= mcpBranch;
                if (p.expectSource() != null) {
                    Pattern src = Pattern.compile(p.expectSource());
                    boolean hit = r.fragments().stream().anyMatch(f -> src.matcher(f.chunk().source()).find());
                    boolean cited = r.cited().stream().anyMatch(f -> src.matcher(f.chunk().source()).find());
                    verdicts.add((hit ? "✅" : "❌") + " RAG нашёл `" + p.expectSource() + "`");
                    verdicts.add((cited ? "✅" : "❌") + " ответ ссылается на документацию: " + r.cited().size() + " ссыл.");
                    ok &= hit && cited && out.contains("Источники:");
                }
                if (p.expectAnswer() != null) {
                    boolean m = r.answer() != null && p.expectAnswer().matcher(r.answer()).find();
                    verdicts.add((m ? "✅" : "❌") + " ответ содержит `" + p.expectAnswer().pattern() + "`");
                    ok &= m;
                }
                if (p.expectBranch()) {
                    boolean m = r.answer() != null && !b.branch().isEmpty() && r.answer().contains(b.branch());
                    verdicts.add((m ? "✅" : "❌") + " в ответе текущая ветка `" + b.branch() + "`");
                    ok &= m;
                }
                if (p.expectTool() != null) {
                    Pattern tp = Pattern.compile(p.expectTool());
                    boolean called = r.toolCalls().stream().anyMatch(t -> tp.matcher(t.tool()).matches() && !t.isError());
                    verdicts.add((called ? "✅" : "❌") + " модель вызвала MCP `" + p.expectTool() + "`");
                    ok &= called;
                }
                if (!r.invalidCitations().isEmpty()) verdicts.add("⚠ отброшены ссылки " + r.invalidCitations());
            }
            rows.add(new String[]{String.valueOf(n), required ? "обяз." : "доп.", p.question(), p.why(), ok ? "✅" : "❌", String.join("<br>", verdicts)});
            checks.add(new Check(C3, (required ? "" : "Доп.: ") + "/help «" + DevAssistant.clip(p.question(), 60) + "» — " + p.why(),
                    required, ok, String.join("; ", verdicts).replaceAll("[✅❌]\\s*", "")));
            appendAnswer(answers, n, p, r, b);
        }

        // ============ verdict + report
        Map<String, Boolean> byCondition = new LinkedHashMap<>();
        for (String c : List.of(C1, C2, C3)) {
            byCondition.put(c, checks.stream().filter(x -> x.condition().equals(c) && x.required()).allMatch(Check::ok));
        }
        boolean done = byCondition.values().stream().allMatch(Boolean::booleanValue);
        long passed = checks.stream().filter(Check::ok).count();

        StringBuilder md = new StringBuilder();
        md.append("# День 31. Ассистент разработчика: RAG по документации + MCP к репозиторию\n\n")
                .append("> Сгенерировано `Task31` (`mvn -q compile exec:java -Ptask31`) ").append(now)
                .append(". Ветка `").append(b.branch()).append("`, модель `").append(a.model()).append("`, эмбеддер `")
                .append(a.docs().embedderId()).append("`.\n\n")
                .append("## Вердикт: ").append(done ? "✅ задание выполнено" : "❌ задание не выполнено").append("\n\n")
                .append("| Условие задания | Итог |\n|---|---|\n");
        byCondition.forEach((c, ok) -> md.append("| ").append(c).append(" | ").append(ok ? "✅" : "❌").append(" |\n"));
        md.append("\nПройдено проверок: ").append(passed).append(" из ").append(checks.size())
                .append(" (обязательные решают вердикт; «по желанию» и «доп.» — для полноты).\n\n");
        md.append("""
                ## Условие задания

                - Используйте RAG для подключения к документации проекта: README, project/docs, схемы данных или API-описания.
                - Через MCP подключите ассистента к проекту: минимум — получение текущей git-ветки; по желанию — список файлов или diff.
                - Реализуйте команду /help: ассистент отвечает на вопросы о проекте, использует документацию и контекст проекта.

                ## Как устроено

                ```
                разработчик: /help <вопрос>
                      │
                      ▼
                Task31 (CLI) ──► DevAssistant
                                   ├─ MCP (stdio) ► ProjectMcpServer: git_current_branch — всегда, приложением;
                                   │                git_status / list_files / git_diff — по решению модели
                                   ├─ RAG ► ProjectDocs: README.md + docs/ (архитектура, схемы, API) + AGENTS.md
                                   │        → task31-docs-index.json (пересборка при любом изменении документации)
                                   └─ LLM ◄ ветка + фрагменты [1..N] + вопрос; ссылки [n] проверяются в коде
                ```

                """);
        md.append("## Проверки\n\n| Условие | Проверка | Обяз. | Итог | Детали |\n|---|---|---|---|---|\n");
        for (Check c : checks) {
            md.append("| ").append(c.condition().substring(0, 1)).append(" | ").append(cell(c.what())).append(" | ")
                    .append(c.required() ? "да" : "нет").append(" | ").append(c.ok() ? "✅" : "❌").append(" | ")
                    .append(cell(c.details())).append(" |\n");
        }
        md.append("\n## База знаний (RAG)\n\n").append(a.docs().buildNote()).append("\n\n| Файл | Группа | Фрагментов |\n|---|---|---|\n");
        for (ProjectDocs.SourceStat s : sources) md.append("| `").append(s.source()).append("` | ").append(s.group()).append(" | ").append(s.chunks()).append(" |\n");
        md.append("\n## Инструменты MCP-сервера `").append(ProjectMcpServer.NAME).append("`\n\n| Инструмент | Описание |\n|---|---|\n");
        for (McpSchema.Tool t : a.tools()) md.append("| `").append(t.name()).append("` | ").append(cell(t.description())).append(" |\n");
        md.append("\n## Вопросы /help\n\n| # | Тип | Вопрос | Что проверяет | Итог | Детали |\n|---|---|---|---|---|---|\n");
        for (String[] r : rows) {
            md.append("| ").append(r[0]).append(" | ").append(r[1]).append(" | ").append(cell(r[2])).append(" | ").append(cell(r[3]))
                    .append(" | ").append(r[4]).append(" | ").append(r[5]).append(" |\n");
        }
        md.append("\n## Ответы\n\n").append(answers);
        Files.writeString(REPORT, md.toString(), StandardCharsets.UTF_8);

        System.out.println("\n══ Итог проверки задания ══");
        for (String c : List.of(C1, C2, C3)) {
            System.out.println((byCondition.get(c) ? "✅ " : "❌ ") + c);
            for (Check x : checks) {
                if (x.condition().equals(c)) {
                    System.out.println("    " + (x.ok() ? "✅" : x.required() ? "❌" : "⚠") + " " + x.what() + " — " + DevAssistant.clip(x.details(), 160));
                }
            }
        }
        System.out.println("\n" + (done ? "✅ ЗАДАНИЕ ВЫПОЛНЕНО" : "❌ ЗАДАНИЕ НЕ ВЫПОЛНЕНО") + " — проверок " + passed + "/" + checks.size() + " → " + REPORT);
    }

    private static void appendAnswer(StringBuilder answers, int n, Probe p, DevAssistant.HelpAnswer r, DevAssistant.BranchInfo b) {
        answers.append("### ").append(n).append(". ").append(p.question()).append("\n\n");
        if (r == null) {
            answers.append("> ⚠ нет ответа\n\n");
            return;
        }
        answers.append("- MCP `git_current_branch` (приложение): `").append(r.branch().branch()).append("`\n");
        answers.append("- RAG: ").append(r.fragments().size()).append(" фрагм. — ");
        answers.append(String.join("; ", r.fragments().stream().map(f -> "[" + f.n() + "] `" + f.label() + "` " + String.format("%.3f", f.score())).toList())).append("\n");
        answers.append("- MCP-вызовы модели: ").append(r.toolCalls().isEmpty() ? "—" : String.join(", ",
                r.toolCalls().stream().map(t -> "`" + t.tool() + " " + t.argumentsJson() + "`" + (t.isError() ? " (ошибка)" : "")).toList())).append("\n");
        answers.append("- ").append(r.millis() / 1000.0).append(" с, запросов к LLM: ").append(r.llmRequests()).append("\n\n");
        if (r.error() != null) {
            answers.append("> ⚠ ").append(r.error()).append("\n\n");
            return;
        }
        answers.append(r.answer().strip().lines().map(l -> "> " + l).reduce((x, y) -> x + "\n" + y).orElse("")).append("\n\n");
        if (!r.cited().isEmpty()) {
            answers.append("Источники (проверены в коде): ").append(String.join("; ",
                    r.cited().stream().map(f -> "[" + f.n() + "] `" + f.label() + "`").toList())).append("\n\n");
        }
    }

    /** Doc files under docs/ that ProjectDocs should index (same extensions). */
    static List<String> docsOnDisk(Path repo) throws Exception {
        Path docs = repo.resolve("docs");
        if (!Files.isDirectory(docs)) return List.of();
        try (Stream<Path> s = Files.walk(docs)) {
            return s.filter(Files::isRegularFile)
                    .map(f -> repo.relativize(f).toString().replace('\\', '/'))
                    .filter(f -> f.matches("(?i).*\\.(md|json|ya?ml|txt)$"))
                    .sorted().toList();
        }
    }

    private static int chunksOf(List<ProjectDocs.SourceStat> sources, String source) {
        return sources.stream().filter(s -> s.source().equals(source)).mapToInt(ProjectDocs.SourceStat::chunks).sum();
    }

    private static String join(List<ProjectDocs.SourceStat> sources, String group) {
        return String.join(", ", sources.stream().filter(s -> s.group().equals(group)).map(ProjectDocs.SourceStat::source).toList());
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    static String gitDirect(Path repo, String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString()));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor(20, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}
