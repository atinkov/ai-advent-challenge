package ru.lemanapro.aiadventchallenge.week4;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Day 20: orchestration of several MCP servers.
 *
 * McpRegistry registers three independent MCP servers (each its own child process, built on earlier days)
 * and publishes all their tools as one catalog with namespaced names "<server>__<tool>":
 *   git       — Task17.GitMcpServer:            git_log, git_commit_details, git_branches
 *   docs      — Task19.DocsPipelineMcpServer:   search_docs, summarize, save_to_file (-> task20-output/)
 *   scheduler — Task18.SchedulerMcpServer:      schedule_job, list_jobs, cancel_job, get_summary
 *   (+ optional external server from TASK20_EXTRA_SERVER="name=command args", e.g.
 *      everything=npx -y @modelcontextprotocol/server-everything)
 * The orchestrating agent (McpToolAgent with the registry as its ToolRouter) sees one tool list; the
 * model CHOOSES a tool, the registry ROUTES the call to the owning server.
 *
 * Checks:
 *   A. Tool selection / routing — six one-intent requests, each must hit exactly the expected server+tool.
 *   B. Long flow — one multi-turn conversation that needs all three servers and passes data between them:
 *        turn 1: git_log -> docs.search_docs (on the topic of the last commit) -> docs.summarize
 *                -> docs.save_to_file -> scheduler.schedule_job (reminder whose text carries the saved
 *                file path and the commit hash — data from two other servers);
 *        turn 2: scheduler.list_jobs — the reminder really exists;
 *        turn 3: git_commit_details on "that commit" + scheduler.cancel_job of "the reminder you created" —
 *                neither the hash nor the job name is repeated in the message: the agent must take them
 *                from earlier turns of the dialogue.
 *      Every turn is checked for the servers used, the ORDER of calls and the values handed from one
 *      server to the next (ids, hash, path), plus side effects verified directly (file on disk, job state).
 * Writes task20-orchestration-report.md.
 *
 * Env: TASK20_GIT_REPO (default "."), TASK20_ROOT (docs root, default "."), TASK20_TOOL_MODE,
 *      TASK19_SUMMARY_MODE (llm | extractive), TASK20_EXTRA_SERVER.
 * Usage: mvn -q compile exec:java -Ptask20
 */
public final class Task20 {

    private static final Path REPORT_FILE = Path.of("task20-orchestration-report.md");
    private static final Path SCHEDULER_STORE = Path.of("task20-scheduler.json");
    private static final String OUT_DIR = "task20-output";
    private static final String REMINDER = "review-status-report";
    private static final String REPORT_NAME = "status-report.md";

    private static final String PROMPT = """
            Ты агент-оркестратор. Тебе доступны инструменты НЕСКОЛЬКИХ MCP-серверов; имя инструмента = <сервер>__<инструмент>:
            - git__*       — история git-репозитория проекта (коммиты, детали коммита, ветки);
            - docs__*      — документация проекта: поиск (search_docs) → выжимка (summarize) → сохранение в файл (save_to_file);
            - scheduler__* — планировщик фоновых заданий и напоминаний (schedule_job, list_jobs, cancel_job, get_summary).
            Правила:
            - Выбирай инструмент того сервера, который отвечает за нужные данные; не угадывай данные — получай их инструментами.
            - Длинные задачи выполняй целиком сам, шаг за шагом, без вопросов и подтверждений.
            - Между шагами передавай значения из результатов: result_id → summarize(source_id), summary_id → save_to_file(summary_id), хеши и пути — как есть.
            - Если пользователь ссылается на то, что было раньше в диалоге («тот коммит», «созданное напоминание»), бери значения из предыдущих результатов.
            - Итог — кратко по-русски, с конкретными значениями (хеши, пути, имена заданий, время).""";

    record RoutingCase(String prompt, String expectedServer, String expectedTool) {
    }

    record RoutingResult(RoutingCase c, List<McpToolAgent.ToolCallTrace> calls, boolean selectedOk, boolean onlyExpectedServer,
                         String answer) {
    }

    record Turn(int no, String message, McpToolAgent.AgentAnswer answer, List<String> checks) {
        boolean passed() {
            return checks.stream().allMatch(c -> c.startsWith("[OK]"));
        }
    }

    private Task20() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        Path repo = Path.of(LlmClient.env("TASK20_GIT_REPO", ".")).toAbsolutePath().normalize();
        Path root = Path.of(LlmClient.env("TASK20_ROOT", ".")).toAbsolutePath().normalize();
        String toolMode = LlmClient.env("TASK20_TOOL_MODE", "auto");
        String summaryMode = LlmClient.env("TASK19_SUMMARY_MODE", "llm");
        Files.deleteIfExists(SCHEDULER_STORE); // clean scheduler state for a reproducible demo
        Files.deleteIfExists(root.resolve(OUT_DIR).resolve(REPORT_NAME));

        System.out.println("=== День 20. Оркестрация нескольких MCP-серверов ===");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());

        McpRegistry registry = new McpRegistry();
        registry.register("git", "история git-репозитория проекта",
                McpLaunch.javaServer(Task17.GitMcpServer.class, repo.toString()));
        registry.register("docs", "поиск по документации проекта, выжимки, сохранение в файл",
                McpLaunch.javaServer(Task19.DocsPipelineMcpServer.class, root.toString(), summaryMode, OUT_DIR));
        registry.register("scheduler", "планировщик фоновых заданий и напоминаний",
                McpLaunch.javaServer(Task18.SchedulerMcpServer.class, SCHEDULER_STORE.toAbsolutePath().toString(), repo.toString()));
        String extra = LlmClient.env("TASK20_EXTRA_SERVER", "");
        if (extra.contains("=")) {
            String name = extra.substring(0, extra.indexOf('=')).strip();
            List<String> cmd = Arrays.asList(extra.substring(extra.indexOf('=') + 1).strip().split("\\s+"));
            try {
                registry.register(name, "внешний MCP-сервер " + name,
                        ServerParameters.builder(cmd.getFirst()).args(cmd.subList(1, cmd.size())).build());
            } catch (Exception e) {
                System.out.println("Внешний сервер " + name + " не подключён: " + e.getMessage());
            }
        }

        System.out.println("\nЗарегистрированные MCP-серверы:");
        for (McpRegistry.Server s : registry.servers()) {
            System.out.printf("  %-10s %s %s, %d инстр.: %s%n", s.name(), s.init().serverInfo().name(), s.init().serverInfo().version(),
                    s.tools().size(), s.tools().stream().map(McpSchema.Tool::name).collect(Collectors.joining(", ")));
        }
        System.out.println("  Всего инструментов в каталоге агента: " + registry.tools().size());

        try (McpToolAgent agent = new McpToolAgent(cfg, registry, toolMode, PROMPT).maxToolSteps(12)) {

            // ---------------- A. tool selection and routing
            System.out.println("\n--- A. Выбор инструмента и маршрутизация (6 запросов с одним намерением) ---");
            List<RoutingCase> cases = List.of(
                    new RoutingCase("Покажи 3 последних коммита репозитория.", "git", "git_log"),
                    new RoutingCase("Какие ветки есть в репозитории?", "git", "git_branches"),
                    new RoutingCase("Найди в документации проекта, где описаны инварианты.", "docs", "search_docs"),
                    new RoutingCase("Какие фоновые задания сейчас есть в планировщике?", "scheduler", "list_jobs"),
                    new RoutingCase("Поставь одноразовое напоминание через 600 секунд с текстом «выпить чаю», имя задания tea-break.",
                            "scheduler", "schedule_job"),
                    new RoutingCase("Сделай краткую выжимку этого текста (не ищи в документации, просто сократи): "
                            + "«MCP — открытый протокол, который стандартизирует подключение инструментов и данных к LLM. "
                            + "Сервер публикует инструменты со схемами параметров, клиент их вызывает через tools/call.»",
                            "docs", "summarize"));
            List<RoutingResult> routing = new ArrayList<>();
            for (RoutingCase c : cases) {
                System.out.println("\n» " + c.prompt());
                McpToolAgent.AgentAnswer a;
                try {
                    a = agent.ask(c.prompt());
                } catch (Exception e) {
                    a = new McpToolAgent.AgentAnswer("(ошибка LLM: " + e + ")", List.of(), 0, "-");
                }
                String expected = c.expectedServer() + McpRegistry.SEP + c.expectedTool();
                boolean selected = !a.calls().isEmpty() && a.calls().getFirst().tool().equals(expected) && !a.calls().getFirst().isError();
                boolean onlyServer = !a.calls().isEmpty() && a.calls().stream().allMatch(t -> t.server().equals(c.expectedServer()));
                System.out.println("  " + check(selected, "первый вызов = " + expected
                        + (a.calls().isEmpty() ? " (вызовов не было)" : ", фактически " + a.calls().getFirst().tool()))
                        + (onlyServer ? "" : "  [примечание: были вызовы других серверов]"));
                routing.add(new RoutingResult(c, a.calls(), selected, onlyServer, a.text()));
            }
            registry.callDirect("scheduler", "cancel_job", Map.of("name", "tea-break")); // tidy up after case 5

            // a tool that no registered server owns must come back as a routing error, not an exception
            McpSchema.CallToolResult unknown = registry.call("jira__create_issue", Map.of("title", "x"));
            String unknownCheck = check(Boolean.TRUE.equals(unknown.isError()) && McpToolAgent.textOf(unknown).contains("git__git_log"),
                    "несуществующий инструмент jira__create_issue → ошибка маршрутизации со списком доступных инструментов");
            System.out.println("\n" + unknownCheck);

            // ---------------- B. long multi-server flow in one conversation
            System.out.println("\n--- B. Длинный флоу: один диалог, три сервера, передача данных между ними ---");
            int logStart = registry.log().size();
            McpToolAgent.Conversation dialog = agent.conversation();
            List<Turn> turns = new ArrayList<>();

            String t1 = "Подготовь статус-отчёт по последнему дню работы над проектом: "
                    + "1) узнай последний коммит репозитория; "
                    + "2) найди в документации проекта материалы по теме этого коммита; "
                    + "3) сделай по найденному краткую выжимку; "
                    + "4) сохрани её в файл " + REPORT_NAME + "; "
                    + "5) поставь одноразовое напоминание через 900 секунд с именем задания " + REMINDER
                    + ", в тексте которого должны быть путь к сохранённому файлу и короткий хеш коммита.";
            turns.add(turn(dialog, 1, t1, a -> checkTurn1(a, root)));
            String hash = extractHeadShort(turns.getFirst().answer());
            turns.add(turn(dialog, 2, "Проверь, что напоминание действительно запланировано, и скажи, когда оно сработает.",
                    a -> checkTurn2(a, registry)));
            turns.add(turn(dialog, 3, "Какой файл сильнее всего изменился в том коммите? После этого отмени напоминание, которое ты создал.",
                    a -> checkTurn3(a, hash, registry)));

            List<McpRegistry.RoutedCall> flowLog = registry.log().subList(logStart, registry.log().size()).stream()
                    .filter(rc -> rc.origin().equals("agent")).toList();
            Set<String> serversUsed = flowLog.stream().map(McpRegistry.RoutedCall::server).collect(Collectors.toCollection(LinkedHashSet::new));
            String flowSummary = check(serversUsed.containsAll(List.of("git", "docs", "scheduler")) && !serversUsed.contains("?"),
                    "во флоу задействованы все три сервера, ошибок маршрутизации нет: " + serversUsed
                            + ", вызовов MCP: " + flowLog.size());
            System.out.println("\n" + flowSummary);

            long selOk = routing.stream().filter(RoutingResult::selectedOk).count();
            long turnsOk = turns.stream().filter(Turn::passed).count();
            System.out.println("\nИТОГ: выбор инструмента " + selOk + "/" + routing.size() + ", длинный флоу — ходов без замечаний "
                    + turnsOk + "/" + turns.size() + ", вызовов MCP во флоу: " + flowLog.size() + " на " + serversUsed.size() + " серверах");
            writeReport(cfg, registry, routing, unknownCheck, turns, flowLog, flowSummary);
            System.out.println("Отчёт: " + REPORT_FILE.toAbsolutePath());
        }
    }

    interface Checker {
        List<String> check(McpToolAgent.AgentAnswer a);
    }

    private static Turn turn(McpToolAgent.Conversation dialog, int no, String message, Checker checker) {
        System.out.println("\n[ход " + no + "] Пользователь: " + message);
        McpToolAgent.AgentAnswer a;
        try {
            a = dialog.ask(message);
        } catch (Exception e) {
            a = new McpToolAgent.AgentAnswer("(ошибка LLM: " + e + ")", List.of(), 0, "-");
        }
        System.out.println("[ход " + no + "] Агент: " + a.text().strip());
        List<String> checks = a.calls().isEmpty() && a.text().startsWith("(ошибка")
                ? List.of("[FAIL] LLM недоступна") : checker.check(a);
        checks.forEach(c -> System.out.println("    " + c));
        return new Turn(no, message, a, checks);
    }

    // ---------------------------------------------------------------- checks of the long flow

    private static List<String> checkTurn1(McpToolAgent.AgentAnswer a, Path root) {
        List<String> c = new ArrayList<>();
        List<McpToolAgent.ToolCallTrace> ok = a.calls().stream().filter(t -> !t.isError()).toList();
        int iLog = firstIndex(ok, "git__git_log");
        int iSearch = firstIndex(ok, "docs__search_docs");
        int iSum = lastIndex(ok, "docs__summarize");
        int iSave = lastIndex(ok, "docs__save_to_file");
        int iSched = lastIndex(ok, "scheduler__schedule_job");
        c.add(check(iLog >= 0 && iSearch > iLog && iSum > iSearch && iSave > iSum && iSched > iSave,
                "порядок: git_log → search_docs → summarize → save_to_file → schedule_job; фактически "
                        + ok.stream().map(McpToolAgent.ToolCallTrace::tool).toList()));
        c.add(check(Set.copyOf(ok.stream().map(McpToolAgent.ToolCallTrace::server).toList()).containsAll(List.of("git", "docs", "scheduler")),
                "задействованы серверы git, docs и scheduler"));
        String head = iLog >= 0 ? find(ok.get(iLog).resultPreview(), "(?m)^([0-9a-f]{7,12}) \\|") : null;
        String subject = iLog >= 0 ? find(ok.get(iLog).resultPreview(), "(?m)^[0-9a-f]{7,12} \\|[^|]*\\|[^|]*\\| (.+)$") : null;
        String query = iSearch >= 0 ? find(ok.get(iSearch).argumentsJson(), "\"query\"\\s*:\\s*\"([^\"]+)\"") : null;
        boolean onTopic = subject != null && query != null && overlaps(subject, query);
        c.add(check(onTopic, "git → docs: запрос поиска «" + query + "» по теме последнего коммита «" + subject + "»"));
        String resultId = iSearch >= 0 ? find(ok.get(iSearch).resultPreview(), "result_id: (\\S+)") : null;
        String sourceArg = iSum >= 0 ? find(ok.get(iSum).argumentsJson(), "\"source_id\"\\s*:\\s*\"([^\"]+)\"") : null;
        c.add(check(resultId != null && resultId.equals(sourceArg), "docs: search → summarize по result_id " + resultId));
        String summaryId = iSum >= 0 ? find(ok.get(iSum).resultPreview(), "summary_id: (\\S+)") : null;
        String summaryArg = iSave >= 0 ? find(ok.get(iSave).argumentsJson(), "\"summary_id\"\\s*:\\s*\"([^\"]+)\"") : null;
        c.add(check(summaryId != null && summaryId.equals(summaryArg), "docs: summarize → save_to_file по summary_id " + summaryId));
        String path = iSave >= 0 ? find(ok.get(iSave).resultPreview(), "Сохранено: (\\S+)") : null;
        c.add(check(path != null && path.endsWith(REPORT_NAME) && Files.isRegularFile(root.resolve(path)),
                "файл отчёта создан: " + path));
        String schedArgs = iSched >= 0 ? ok.get(iSched).argumentsJson() : "";
        c.add(check(schedArgs.contains(REMINDER) && path != null && schedArgs.contains(path) && head != null && schedArgs.contains(head),
                "docs + git → scheduler: напоминание " + REMINDER + " содержит путь " + path + " и хеш " + head));
        return c;
    }

    private static List<String> checkTurn2(McpToolAgent.AgentAnswer a, McpRegistry registry) {
        List<String> c = new ArrayList<>();
        boolean listed = a.calls().stream().anyMatch(t -> t.tool().equals("scheduler__list_jobs") && !t.isError());
        c.add(check(listed, "агент проверил планировщик: scheduler__list_jobs"));
        c.add(check(a.calls().stream().allMatch(t -> t.server().equals("scheduler")), "в этом ходе вызывались только инструменты scheduler"));
        c.add(check(a.text().contains(REMINDER) || a.text().contains("напомин"), "ответ говорит о напоминании " + REMINDER));
        c.add(check(jobActive(registry, REMINDER), "проверка напрямую: задание " + REMINDER + " активно"));
        return c;
    }

    private static List<String> checkTurn3(McpToolAgent.AgentAnswer a, String hash, McpRegistry registry) {
        List<String> c = new ArrayList<>();
        List<McpToolAgent.ToolCallTrace> ok = a.calls().stream().filter(t -> !t.isError()).toList();
        int iDet = firstIndex(ok, "git__git_commit_details");
        int iCancel = lastIndex(ok, "scheduler__cancel_job");
        String shaArg = iDet >= 0 ? find(ok.get(iDet).argumentsJson(), "\"sha\"\\s*:\\s*\"([^\"]+)\"") : null;
        c.add(check(iDet >= 0 && iCancel > iDet, "порядок: git_commit_details → cancel_job; фактически "
                + ok.stream().map(McpToolAgent.ToolCallTrace::tool).toList()));
        c.add(check(hash != null && shaArg != null && (shaArg.startsWith(hash) || hash.startsWith(shaArg)),
                "хеш «того коммита» взят из хода 1 (в сообщении его нет): sha=" + shaArg + ", из git_log: " + hash));
        String nameArg = iCancel >= 0 ? find(ok.get(iCancel).argumentsJson(), "\"name\"\\s*:\\s*\"([^\"]+)\"") : null;
        c.add(check(REMINDER.equals(nameArg), "имя «созданного напоминания» взято из хода 1: cancel_job(name=" + nameArg + ")"));
        c.add(check(!jobActive(registry, REMINDER), "проверка напрямую: задание " + REMINDER + " остановлено"));
        String top = iDet >= 0 ? topFile(ok.get(iDet).resultPreview()) : null;
        c.add(check(top != null && a.text().contains(top.substring(top.lastIndexOf('/') + 1)),
                "ответ называет самый изменённый файл по данным git: " + top));
        return c;
    }

    @SuppressWarnings("unchecked")
    private static boolean jobActive(McpRegistry registry, String name) {
        Object sc = registry.callDirect("scheduler", "list_jobs", Map.of()).structuredContent();
        List<Map<String, Object>> jobs = sc instanceof Map<?, ?> m ? (List<Map<String, Object>>) m.get("jobs") : List.of();
        return jobs.stream().anyMatch(j -> name.equals(j.get("name")) && Boolean.TRUE.equals(j.get("active")));
    }

    private static String extractHeadShort(McpToolAgent.AgentAnswer a) {
        return a.calls().stream().filter(t -> t.tool().equals("git__git_log") && !t.isError()).findFirst()
                .map(t -> find(t.resultPreview(), "(?m)^([0-9a-f]{7,12}) \\|")).orElse(null);
    }

    /** "  path  +N −M" lines of git_commit_details -> the path with the largest N+M. */
    private static String topFile(String details) {
        Matcher m = Pattern.compile("(?m)^\\s{2}(\\S+)\\s{2}\\+(\\d+) −(\\d+)").matcher(details);
        String best = null;
        int bestN = -1;
        while (m.find()) {
            int n = Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
            if (n > bestN) {
                bestN = n;
                best = m.group(1);
            }
        }
        return best;
    }

    /** Topic overlap between commit subject and search query: a shared word stem (4+ letters, not boilerplate). */
    private static boolean overlaps(String subject, String query) {
        Set<String> stop = Set.of("completed", "task", "день");
        Set<String> a = stems(subject, stop);
        return stems(query, stop).stream().anyMatch(a::contains);
    }

    private static Set<String> stems(String s, Set<String> stop) {
        return Pattern.compile("[\\p{L}\\p{N}]+").matcher(s.toLowerCase(Locale.ROOT)).results().map(java.util.regex.MatchResult::group)
                .filter(w -> w.length() >= 3 && !stop.contains(w) && !w.matches("task\\d+|\\d+"))
                .map(w -> w.length() > 5 ? w.substring(0, 5) : w)
                .collect(Collectors.toSet());
    }

    private static int firstIndex(List<McpToolAgent.ToolCallTrace> calls, String tool) {
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).tool().equals(tool)) return i;
        return -1;
    }

    private static int lastIndex(List<McpToolAgent.ToolCallTrace> calls, String tool) {
        for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).tool().equals(tool)) return i;
        return -1;
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : null;
    }

    private static String check(boolean ok, String what) {
        return (ok ? "[OK]   " : "[FAIL] ") + what;
    }

    // ---------------------------------------------------------------- report

    private static void writeReport(LlmClient.Config cfg, McpRegistry registry, List<RoutingResult> routing, String unknownCheck, List<Turn> turns,
                                    List<McpRegistry.RoutedCall> flowLog, String flowSummary) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 20. Оркестрация нескольких MCP-серверов\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask20`, модель ").append(cfg.model()).append("\n\n");
        md.append("```\n                    ┌─ git__*       ─▶ git-mcp-server        (Task17.GitMcpServer)\n")
                .append("LLM ⇄ McpToolAgent ⇄ McpRegistry ─┼─ docs__*      ─▶ docs-pipeline-mcp-server (Task19.DocsPipelineMcpServer)\n")
                .append("   (один каталог)   (маршрутизация) └─ scheduler__* ─▶ scheduler-mcp-server  (Task18.SchedulerMcpServer)\n```\n\n");
        md.append("## Реестр серверов\n\n| Сервер | MCP serverInfo | Инструменты (в каталоге агента — `<сервер>__<инструмент>`) |\n|---|---|---|\n");
        for (McpRegistry.Server s : registry.servers()) {
            md.append("| `").append(s.name()).append("` | ").append(s.init().serverInfo().name()).append(" ")
                    .append(s.init().serverInfo().version()).append(" | ")
                    .append(s.tools().stream().map(t -> "`" + t.name() + "`").collect(Collectors.joining(", "))).append(" |\n");
        }
        md.append("\nВсего инструментов в каталоге: ").append(registry.tools().size()).append(".\n\n");

        md.append("## A. Выбор инструмента и маршрутизация\n\n| # | Запрос | Ожидается | Вызовы агента (сервер → инструмент) | Результат |\n|---|---|---|---|---|\n");
        int i = 1;
        for (RoutingResult r : routing) {
            md.append("| ").append(i++).append(" | ").append(esc(clip(r.c().prompt(), 90))).append(" | `")
                    .append(r.c().expectedServer()).append(McpRegistry.SEP).append(r.c().expectedTool()).append("` | ")
                    .append(r.calls().isEmpty() ? "—" : r.calls().stream().map(t -> t.server() + " → `" + t.tool() + "`")
                            .collect(Collectors.joining("<br>")))
                    .append(" | ").append(r.selectedOk() ? "✅" : "❌").append(r.onlyExpectedServer() ? "" : " (+ другие серверы)").append(" |\n");
        }
        long selOk = routing.stream().filter(RoutingResult::selectedOk).count();
        md.append("\nВерно выбран и смаршрутизирован первый вызов: **").append(selOk).append("/").append(routing.size()).append("**.\n\n- ").append(unknownCheck.trim()).append("\n\n");

        md.append("## B. Длинный флоу (один диалог, 3 хода)\n\n");
        md.append("### Хронология вызовов MCP, выбранных агентом (служебные проверки приложения не включены)\n\n| # | Сервер | Инструмент | Аргументы | Статус | мс |\n|---|---|---|---|---|---|\n");
        int k = 1;
        for (McpRegistry.RoutedCall rc : flowLog) {
            md.append("| ").append(k++).append(" | ").append(rc.server()).append(" | `").append(rc.tool()).append("` | `")
                    .append(esc(clip(LlmClient.toJson(rc.args()), 110))).append("` | ").append(rc.ok() ? "ok" : "ошибка")
                    .append(" | ").append(rc.millis()).append(" |\n");
        }
        for (Turn t : turns) {
            md.append("\n### Ход ").append(t.no()).append("\n\n**Пользователь:** ").append(t.message()).append("\n\n");
            md.append("Вызовы: ").append(t.answer().calls().isEmpty() ? "—" : t.answer().calls().stream()
                    .map(c -> "`" + c.tool() + "`" + (c.isError() ? " (ошибка)" : "")).collect(Collectors.joining(" → "))).append("\n\n");
            md.append("**Агент:**\n\n").append(t.answer().text().strip().lines().map(l -> "> " + l).collect(Collectors.joining("\n"))).append("\n\n");
            t.checks().forEach(c -> md.append("- ").append(c.trim()).append('\n'));
        }
        md.append("\n- ").append(flowSummary.trim()).append("\n");
        long turnsOk = turns.stream().filter(Turn::passed).count();
        md.append("\n**Итог:** выбор инструмента ").append(selOk).append("/").append(routing.size())
                .append("; длинный флоу — ходов без замечаний ").append(turnsOk).append("/").append(turns.size())
                .append(", вызовов MCP ").append(flowLog.size()).append(".\n");
        Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String esc(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
