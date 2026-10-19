package ru.lemanapro.aiadventchallenge.week7;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;
import ru.lemanapro.aiadventchallenge.week4.McpToolAgent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 34: ассистент для работы с файлами проекта (по умолчанию — репозиторий курса lmru--qacc--performance-qa-course).
 *   MCP  — ProjectFilesMcpServer: list_files, read_file, search, check_links, git_log, write_file, edit_file, pending_changes;
 *   LLM  — FilesAssistant (McpToolAgent): сам выбирает файлы и инструменты под цель;
 *   итог — ответ + unified diff (task34-output/*.patch, применяется `git apply`), с --apply — запись прямо в файлы.
 *
 *   mvn -q compile exec:java -Ptask34                                         → 4 сценария + проверки → task34-files-assistant-report.md
 *   mvn -q exec:java -Ptask34 -Dexec.args="chat"                             → интерактивный режим (files>)
 *   mvn -q exec:java -Ptask34 -Dexec.args="Найди все места, где используется feed"   → одна цель (dry-run, diff в task34-output/)
 *   mvn -q exec:java -Ptask34 -Dexec.args="--apply Сгенерируй CHANGELOG.md"   → то же, но с записью в файлы проекта
 *   mvn -q exec:java -Ptask34 -Dexec.args="--root ../другой-проект chat"     → любой другой проект
 *   mvn -q exec:java -Ptask34 -Dexec.args="tools"                            → список инструментов MCP-сервера (без LLM)
 * Env: TASK34_ROOT (корень проекта), TASK34_TOOL_MODE (auto|native|prompt), TASK34_MAX_STEPS (30),
 *      TASK34_ONLY (например "s1,s3") — перезапустить часть сценариев: результат каждого хранится в task34-output/sN-result.json,
 *      отчёт собирается из всех сохранённых.
 */
public final class Task34 {

    static final Path REPORT = Path.of("task34-files-assistant-report.md");
    static final Path OUT = Path.of("task34-output");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(List.of(argv));
        boolean apply = args.remove("--apply");
        String rootArg = null;
        int ri = args.indexOf("--root");
        if (ri >= 0 && ri + 1 < args.size()) {
            rootArg = args.get(ri + 1);
            args.remove(ri + 1);
            args.remove(ri);
        }
        Path root = ProjectFiles.resolveRoot(rootArg);
        if (!Files.isDirectory(root)) {
            System.out.println("Папка проекта не найдена: " + root + " — задайте TASK34_ROOT или --root <папка>");
            System.exit(1);
        }
        String mode = args.isEmpty() ? "check" : args.getFirst();
        LlmClient.Config cfg = LlmClient.env("LLM_API_KEY", null) == null ? null : LlmClient.fromEnv();
        if (cfg == null && !mode.equals("tools")) {
            System.out.println("Нет LLM_API_KEY (.env или переменная окружения) — ассистенту нужна LLM. Без LLM доступно: tools");
            System.exit(1);
        }
        Files.createDirectories(OUT);
        switch (mode) {
            case "check" -> check(root, cfg);
            case "chat" -> chat(root, apply, cfg);
            case "tools" -> {
                try (FilesAssistant a = new FilesAssistant(root, false, null)) {
                    System.out.println("Проект: " + root + "\nMCP " + ProjectFilesMcpServer.NAME + ":");
                    for (McpSchema.Tool t : a.tools()) System.out.println("  " + t.name() + " — " + t.description().split("\\. ")[0]);
                }
            }
            default -> goal(root, apply, cfg, String.join(" ", args));
        }
    }

    // ================================================================ одна цель / чат

    static void goal(Path root, boolean apply, LlmClient.Config cfg, String goal) throws Exception {
        try (FilesAssistant a = new FilesAssistant(root, apply, cfg)) {
            banner(a);
            System.out.println("Цель: " + goal + "\n");
            FilesAssistant.Result r = a.run(goal);
            printResult(r);
            savePatch(a, r.diff(), "goal-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        }
    }

    static void chat(Path root, boolean apply, LlmClient.Config cfg) throws Exception {
        try (FilesAssistant a = new FilesAssistant(root, apply, cfg)) {
            banner(a);
            System.out.println("Ставьте цель словами («найди…», «проверь…», «сгенерируй…»). Команды: /diff — изменения, /save — patch в "
                    + OUT + "/, /tools, /exit\n");
            McpToolAgent.Conversation conv = a.conversation();
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                System.out.print("files> ");
                String line = in.readLine();
                if (line == null || line.strip().equals("/exit")) break;
                line = line.strip();
                if (line.isEmpty()) continue;
                switch (line) {
                    case "/diff" -> {
                        String d = a.pending().diff();
                        System.out.println(d.isEmpty() ? "изменений нет" : d);
                    }
                    case "/save" -> savePatch(a, a.pending().diff(), "chat-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
                    case "/tools" -> a.tools().forEach(t -> System.out.println("  " + t.name()));
                    default -> printResult(a.run(line, conv));
                }
            }
        }
    }

    static void banner(FilesAssistant a) {
        System.out.println("Ассистент по файлам проекта: " + a.root());
        System.out.println("MCP " + ProjectFilesMcpServer.NAME + ": " + a.tools().stream().map(McpSchema.Tool::name).toList());
        System.out.println("LLM: " + a.model() + " | режим: " + (a.applyMode() ? "apply — запись в файлы проекта" : "dry-run — изменения только в diff"));
    }

    static void printResult(FilesAssistant.Result r) {
        System.out.println();
        System.out.println(r.error() != null ? "⚠ " + r.error() : r.answer());
        System.out.println("\n── вызовов инструментов: " + r.calls().size() + " " + r.toolsUsed() + ", запросов к LLM: " + r.llmRequests()
                + ", прочитано файлов: " + r.filesRead().size() + ", найдено поиском/анализом: " + r.filesFound().size()
                + ", изменено: " + r.contents().keySet() + ", " + r.millis() / 1000.0 + " с");
    }

    static Path savePatch(FilesAssistant a, String diff, String name) throws Exception {
        if (diff.isEmpty()) return null;
        Path p = OUT.resolve(name + ".patch");
        Files.writeString(p, diff, StandardCharsets.UTF_8);
        System.out.println("diff → " + p + (a.applyMode() ? " (изменения уже записаны в проект)"
                : "  — применить: git -C \"" + a.root() + "\" apply \"" + p.toAbsolutePath() + "\""));
        return p;
    }

    // ================================================================ сценарии и проверка задания

    record Check(String name, boolean ok, String details) {
    }

    /** minFiles — сколько файлов проекта должно побывать «в работе» (прочитаны / найдены / изменены). */
    record Scenario(String id, String slug, String title, String goal, int minFiles) {
    }

    static final String SEARCH_API = "exitHereIfFailed";

    static final List<Scenario> SCENARIOS = List.of(
            new Scenario("s1", "usages", "Найти все места, где используется API",
                    "Найди все места в курсе, где используется " + SEARCH_API + " из Gatling. Для каждого урока или задания укажи файл, "
                            + "номера строк и зачем там применяется этот вызов. Файлы не меняй.", 3),
            new Scenario("s2", "rules", "Проверить соответствие правилам и исправить нарушения",
                    "Проверь, что курс соблюдает правила навигации, которые описаны в самом курсе: каждый урок, задание и вспомогательный "
                            + "материал из папок курса доступен из общего списка уроков, а все ссылки между файлами рабочие. Нарушения исправь "
                            + "правкой списка или ссылок; текст уроков не переписывай. В ответе перечисли найденные нарушения и что исправлено.", 3),
            new Scenario("s3", "readme", "Сгенерировать README",
                    "У репозитория курса нет README. Создай README.md в корне: что это за курс и для кого, таблица модулей "
                            + "(название со ссылкой на первый урок модуля, сколько в модуле уроков и сколько заданий — посчитай по файлам; "
                            + "файл считается заданием, если в его имени есть слово «Задание» или «Задания»), с чего начать "
                            + "(карта курса и инструкция по развёртыванию окружения) и как устроены задания.", 2),
            new Scenario("s4", "changelog", "Сгенерировать CHANGELOG по истории git",
                    "Подготовь CHANGELOG.md курса по истории git-репозитория: разделы по датам (новые сверху), в каждом — пункты "
                            + "«что изменилось» с коротким хешем коммита в скобках, сгруппированные по модулям, где это видно. "
                            + "Включи все коммиты, ничего не выдумывай.", 1));

    /** Result of one scenario, saved to task34-output/<id>-result.json — the report is built from all saved results. */
    record Outcome(String id, String title, boolean ok, boolean selfInitiated, boolean untouched, int calls,
                   List<String> filesInvolved, List<String> changed, String patch, long millis, String model, String at,
                   String section) {
    }

    static Path outcomeFile(String id) {
        return OUT.resolve(id + "-result.json");
    }

    static void check(Path root, LlmClient.Config cfg) throws Exception {
        Set<String> only = new LinkedHashSet<>(List.of(LlmClient.env("TASK34_ONLY", "s1,s2,s3,s4").split("\\s*,\\s*")));
        ProjectFiles truth = new ProjectFiles(root, false);
        for (Scenario s : SCENARIOS) {
            if (!only.contains(s.id())) continue;
            System.out.println("\n══ " + s.id() + ". " + s.title() + " ══\nЦель: " + s.goal() + "\n");
            String gitBefore = git(root, "status", "--porcelain");
            Files.deleteIfExists(OUT.resolve(s.id() + "-" + s.slug() + ".patch"));
            FilesAssistant.Result r;
            Path patch;
            try (FilesAssistant a = new FilesAssistant(root, false, cfg)) {
                r = a.run(s.goal());
                printResult(r);
                patch = savePatch(a, r.diff(), s.id() + "-" + s.slug());
            }
            boolean untouched = gitBefore.equals(git(root, "status", "--porcelain"));
            List<Check> checks = new ArrayList<>();
            checks.add(new Check("ассистент сам выбрал файлы: ≥ 3 вызова инструментов, ≥ " + s.minFiles() + " файл(а) в работе",
                    r.error() == null && r.calls().size() >= 3 && r.filesInvolved().size() >= s.minFiles(),
                    r.calls().size() + " вызовов " + r.toolsUsed() + "; файлов в работе: " + r.filesInvolved().size()
                            + " (прочитано " + r.filesRead().size() + ", найдено поиском/анализом " + r.filesFound().size() + ", изменено " + r.contents().size() + ")"
                            + (r.error() != null ? "; ошибка: " + r.error() : "")));
            switch (s.id()) {
                case "s1" -> checkUsages(truth, r, checks);
                case "s2" -> checkRules(root, truth, r, checks);
                case "s3" -> checkReadme(root, truth, r, checks);
                case "s4" -> checkChangelog(truth, r, checks);
                default -> {
                }
            }
            if (patch != null) {
                String applyCheck = gitApplyCheck(root, patch);
                checks.add(new Check("diff сохранён и применяется (`git apply --check`)", applyCheck.isEmpty(),
                        "`" + patch + "`" + (applyCheck.isEmpty() ? "" : " — " + applyCheck)));
            }
            checks.add(new Check("dry-run: файлы проекта на диске не изменились", untouched, "`git status --porcelain` до и после " + (untouched ? "совпадает" : "РАЗЛИЧАЕТСЯ")));
            boolean ok = checks.stream().allMatch(Check::ok);
            Outcome o = new Outcome(s.id(), s.title(), ok, checks.getFirst().ok(), untouched, r.calls().size(),
                    new ArrayList<>(r.filesInvolved()), new ArrayList<>(r.contents().keySet()), patch == null ? null : patch.toString(),
                    r.millis(), cfg.model(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    section(s, r, checks, patch));
            JSON.writerWithDefaultPrettyPrinter().writeValue(outcomeFile(s.id()).toFile(), o);
            System.out.println("\nПроверки " + s.id() + ":");
            checks.forEach(c -> System.out.println("  " + (c.ok() ? "✅" : "❌") + " " + c.name() + "\n      " + c.details()));
        }
        boolean done = writeReport(root);
        System.out.println("\n" + (done ? "✅ ЗАДАНИЕ ВЫПОЛНЕНО" : "❌ ЗАДАНИЕ НЕ ВЫПОЛНЕНО") + " → " + REPORT);
    }

    /** The report over the saved result of every scenario (a scenario re-run alone with TASK34_ONLY replaces only its part). */
    static boolean writeReport(Path root) throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Scenario s : SCENARIOS) {
            Path f = outcomeFile(s.id());
            if (Files.exists(f)) outcomes.add(JSON.readValue(f.toFile(), Outcome.class));
            else missing.add(s.id());
        }
        boolean all = missing.isEmpty() && outcomes.stream().allMatch(Outcome::ok);
        boolean self = !outcomes.isEmpty() && outcomes.stream().allMatch(Outcome::selfInitiated);
        boolean untouched = !outcomes.isEmpty() && outcomes.stream().allMatch(Outcome::untouched);
        Set<String> involved = new LinkedHashSet<>();
        outcomes.forEach(o -> involved.addAll(o.filesInvolved()));
        int maxFiles = outcomes.stream().mapToInt(o -> o.filesInvolved().size()).max().orElse(0);
        List<String> patches = outcomes.stream().map(Outcome::patch).filter(p -> p != null).toList();
        Set<String> models = new LinkedHashSet<>();
        outcomes.forEach(o -> models.add(o.model()));
        boolean done = all && self && untouched && maxFiles >= 3 && !patches.isEmpty();

        StringBuilder md = new StringBuilder("# День 34. Ассистент для работы с файлами проекта — проверка\n\n")
                .append("> ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(", модель `").append(String.join("`, `", models)).append("`, проект `").append(root.getFileName()).append("`, режим dry-run\n\n")
                .append("## Вердикт: ").append(done ? "✅ задание выполнено" : "❌ задание не выполнено")
                .append(missing.isEmpty() ? "" : " (не запускались: " + String.join(", ", missing) + ")").append("\n\n")
                .append("| Требование | Итог | Как проверено |\n|---|---|---|\n")
                .append("| Ассистент сам инициирует работу с файлами (цель, а не «открой файл X») | ").append(self ? "✅" : "❌")
                .append(" | в целях сценариев нет путей к существующим файлам; какие файлы открыть, решает модель через MCP — трассы ниже |\n")
                .append("| Читает, ищет по нескольким файлам, анализирует, создаёт/изменяет | ").append(all ? "✅" : "❌").append(" | инструменты ")
                .append("`list_files`, `read_file`, `search`, `check_links`, `git_log`, `edit_file`, `write_file`, `pending_changes`; результат каждого сценария проверен кодом |\n")
                .append("| Минимум 2–3 файла в работе | ").append(maxFiles >= 3 ? "✅" : "❌").append(" | всего в работе ").append(involved.size())
                .append(" разных файлов, максимум в одном сценарии — ").append(maxFiles).append(" |\n")
                .append("| Изменения сохраняются / выводятся как diff | ").append(!patches.isEmpty() ? "✅" : "❌").append(" | ")
                .append(patches.stream().map(p -> "`" + p + "`").reduce((x, y) -> x + ", " + y).orElse("—")).append(" |\n")
                .append("| Результат воспроизводим | ").append(untouched ? "✅" : "❌").append(" | dry-run: `git status` проекта до и после каждого сценария ")
                .append(untouched ? "совпадает" : "ИЗМЕНИЛСЯ").append("; повтор: `mvn -q compile exec:java -Ptask34` (один сценарий: `TASK34_ONLY=s2`), ")
                .append("применить: `git -C <проект> apply task34-output/<сценарий>.patch` |\n\n")
                .append("## Сценарии\n\n| Сценарий | Итог | Вызовов | Файлов в работе | Изменено | Diff | Время | Запуск |\n|---|---|---|---|---|---|---|---|\n");
        for (Outcome o : outcomes) {
            md.append("| ").append(o.id()).append(". ").append(o.title()).append(" | ").append(o.ok() ? "✅" : "❌").append(" | ").append(o.calls())
                    .append(" | ").append(o.filesInvolved().size()).append(" | ").append(o.changed().isEmpty() ? "—" : String.join(", ", o.changed()))
                    .append(" | ").append(o.patch() == null ? "—" : "`" + o.patch() + "`").append(" | ").append(String.format("%.0f с", o.millis() / 1000.0))
                    .append(" | ").append(o.at()).append(" |\n");
        }
        md.append('\n');
        outcomes.forEach(o -> md.append(o.section()));
        Files.writeString(REPORT, md.toString(), StandardCharsets.UTF_8);
        return done;
    }

    // ---- s1: все места использования API

    static void checkUsages(ProjectFiles truth, FilesAssistant.Result r, List<Check> checks) throws Exception {
        Map<String, Integer> expected = truth.search(SEARCH_API, false, null, true, 0).perFile();
        String answer = norm(r.answer());
        List<String> missed = new ArrayList<>();
        for (String f : expected.keySet()) if (!answer.contains(norm(title(f)))) missed.add(f);
        checks.add(new Check("в ответе все файлы с `" + SEARCH_API + "` (эталон — тот же поиск в коде)", missed.isEmpty(),
                (expected.size() - missed.size()) + " из " + expected.size() + (missed.isEmpty() ? "" : "; пропущены: " + String.join("; ", missed))));
        boolean searched = r.calls().stream().anyMatch(c -> c.tool().equals("search") && String.valueOf(c.args().get("query")).contains(SEARCH_API));
        checks.add(new Check("ассистент сам нашёл файлы поиском и ничего не менял", searched && r.contents().isEmpty(),
                "search(«" + SEARCH_API + "»): " + (searched ? "да" : "нет") + "; изменённых файлов: " + r.contents().size()));
    }

    // ---- s2: правила курса

    /** Rules of the course that code can check: every .md is in the course map, and no link is broken. */
    static List<String> ruleViolations(ProjectFiles f) throws Exception {
        List<String> out = new ArrayList<>();
        String mapName = "Карта курса.md";
        if (!f.exists(mapName)) return List.of("нет файла «" + mapName + "»");
        String map = f.read(mapName);
        for (ProjectFiles.FileInfo fi : f.list("*.md")) {
            String p = fi.path();
            if (p.equals(mapName) || !p.contains("/")) continue; // README / CHANGELOG в корне — не уроки
            if (!map.contains("(<" + p + ">)") && !map.contains("(" + p.replace(" ", "%20") + ")")) out.add("нет в карте курса: " + p);
        }
        for (ProjectFiles.BrokenLink b : f.checkLinks(null).broken()) out.add("битая ссылка: " + b.file() + ":" + b.line() + " → " + b.target());
        return out;
    }

    static void checkRules(Path root, ProjectFiles truth, FilesAssistant.Result r, List<Check> checks) throws Exception {
        List<String> before = ruleViolations(truth);
        ProjectFiles after = new ProjectFiles(root, false);
        for (var e : r.contents().entrySet()) after.write(e.getKey(), e.getValue());
        List<String> left = ruleViolations(after);
        String answer = norm(r.answer());
        List<String> notReported = new ArrayList<>();
        for (String v : before) {
            String file = v.replaceFirst("^[^:]+: ", "").replaceFirst(":\\d+ → .*$", "");
            if (!answer.contains(norm(title(file)))) notReported.add(v);
        }
        checks.add(new Check("найдены нарушения, которые видит детерминированная проверка", notReported.isEmpty(),
                "эталон: " + (before.isEmpty() ? "нарушений нет" : String.join("; ", before))
                        + (notReported.isEmpty() ? "" : "; не упомянуты в ответе: " + String.join("; ", notReported))));
        checks.add(new Check("после правок ассистента нарушений нет", left.isEmpty() && (before.isEmpty() || !r.contents().isEmpty()),
                left.isEmpty() ? "осталось 0 (изменены: " + String.join(", ", r.contents().keySet()) + ")" : "осталось: " + String.join("; ", left)));
        List<String> rewritten = new ArrayList<>();
        for (var e : r.contents().entrySet()) {
            if (!truth.exists(e.getKey())) continue;
            int[] pm = ProjectFiles.plusMinus(ProjectFiles.unifiedDiff(e.getKey(), truth.read(e.getKey()), e.getValue()));
            if (pm[1] > 10) rewritten.add(e.getKey() + " (−" + pm[1] + ")");
        }
        checks.add(new Check("правки точечные (≤ 10 удалённых строк на файл, текст уроков не переписан)", rewritten.isEmpty(),
                rewritten.isEmpty() ? "ок" : String.join(", ", rewritten)));
    }

    // ---- s3: README

    record ModuleStat(String dir, String name, int lessons, int tasks) {
    }

    static List<ModuleStat> modules(ProjectFiles f) throws Exception {
        Map<String, int[]> m = new TreeMap<>();
        for (ProjectFiles.FileInfo fi : f.list("*.md")) {
            String p = fi.path();
            if (!p.matches("\\d\\d\\. [^/]+/[^/]+\\.md")) continue;
            int[] c = m.computeIfAbsent(p.substring(0, p.indexOf('/')), k -> new int[2]);
            if (p.substring(p.indexOf('/') + 1).matches(".*Задани[ея].*")) c[1]++;
            else c[0]++;
        }
        List<ModuleStat> out = new ArrayList<>();
        m.forEach((d, c) -> out.add(new ModuleStat(d, d.replaceFirst("^\\d+\\.\\s*", ""), c[0], c[1])));
        return out;
    }

    static void checkReadme(Path root, ProjectFiles truth, FilesAssistant.Result r, List<Check> checks) throws Exception {
        String readme = r.contents().get("README.md");
        checks.add(new Check("создан README.md в корне", readme != null && !readme.isBlank(),
                readme == null ? "README.md не создан; изменены: " + r.contents().keySet() : ProjectFiles.lineCount(readme) + " строк"));
        if (readme == null) return;
        List<ModuleStat> mods = modules(truth);
        String n = norm(readme);
        List<String> missing = new ArrayList<>();
        int counts = 0;
        List<String> countDetails = new ArrayList<>();
        for (ModuleStat m : mods) {
            if (!n.contains(norm(m.name()))) missing.add(m.name());
            String row = readme.lines().filter(l -> norm(l).contains(norm(m.name()))).findFirst().orElse("");
            boolean ok = row.matches(".*\\b" + m.lessons() + "\\b.*") && row.matches(".*\\b" + m.tasks() + "\\b.*");
            if (ok) counts++;
            else countDetails.add(m.name() + ": ожидалось " + m.lessons() + " ур. / " + m.tasks() + " зад.");
        }
        checks.add(new Check("все модули курса в README", missing.isEmpty(),
                (mods.size() - missing.size()) + " из " + mods.size() + (missing.isEmpty() ? "" : "; нет: " + String.join(", ", missing))));
        checks.add(new Check("число уроков и заданий по модулям совпадает с файлами", counts == mods.size(),
                counts + " из " + mods.size() + (countDetails.isEmpty() ? "" : "; " + String.join("; ", countDetails))));
        ProjectFiles after = new ProjectFiles(root, false);
        after.write("README.md", readme);
        List<ProjectFiles.BrokenLink> broken = after.checkLinks("README.md").broken();
        boolean entry = readme.contains("Карта курса.md") || readme.contains("Карта%20курса.md");
        boolean instr = readme.contains("Развертывание тестового окружения") || readme.contains("Развертывание%20тестового%20окружения");
        checks.add(new Check("ссылки README рабочие, есть ссылки на карту курса и инструкцию по окружению", broken.isEmpty() && entry && instr,
                "битых: " + broken.size() + (broken.isEmpty() ? "" : " " + broken.stream().map(ProjectFiles.BrokenLink::target).toList())
                        + "; карта курса: " + (entry ? "да" : "нет") + "; инструкция: " + (instr ? "да" : "нет")));
    }

    // ---- s4: CHANGELOG

    static void checkChangelog(ProjectFiles truth, FilesAssistant.Result r, List<Check> checks) throws Exception {
        String log = r.contents().get("CHANGELOG.md");
        checks.add(new Check("создан CHANGELOG.md", log != null && !log.isBlank(),
                log == null ? "CHANGELOG.md не создан; изменены: " + r.contents().keySet() : ProjectFiles.lineCount(log) + " строк"));
        if (log == null) return;
        List<ProjectFiles.Commit> commits = truth.gitLog(500, null, false);
        Set<String> hashes = new LinkedHashSet<>();
        Set<String> dates = new LinkedHashSet<>();
        commits.forEach(c -> {
            hashes.add(c.hash());
            dates.add(c.date());
        });
        List<String> missedHashes = hashes.stream().filter(h -> !log.contains(h)).toList();
        List<String> missedDates = dates.stream().filter(d -> !log.contains(d)).toList();
        Set<String> invented = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\b[0-9a-f]{7,12}\\b").matcher(log);
        while (m.find()) {
            String h = m.group();
            if (h.chars().anyMatch(Character::isLetter) && hashes.stream().noneMatch(x -> x.startsWith(h) || h.startsWith(x))) invented.add(h);
        }
        checks.add(new Check("все коммиты (по хешу) и все даты на месте", missedHashes.isEmpty() && missedDates.isEmpty(),
                "коммитов " + (hashes.size() - missedHashes.size()) + " из " + hashes.size() + ", дат " + (dates.size() - missedDates.size()) + " из " + dates.size()
                        + (missedHashes.isEmpty() ? "" : "; нет: " + String.join(", ", missedHashes))));
        checks.add(new Check("нет выдуманных коммитов", invented.isEmpty(), invented.isEmpty() ? "все хеши из git log" : "неизвестные хеши: " + invented));
        List<Integer> pos = dates.stream().map(log::indexOf).filter(i -> i >= 0).toList();
        boolean sorted = true;
        for (int i = 1; i < pos.size(); i++) sorted &= pos.get(i) > pos.get(i - 1);
        checks.add(new Check("даты по убыванию (новые сверху)", sorted, String.join(" → ", dates)));
    }

    // ================================================================ report helpers

    static String section(Scenario s, FilesAssistant.Result r, List<Check> checks, Path patch) {
        StringBuilder sb = new StringBuilder("### ").append(s.id()).append(". ").append(s.title()).append("\n\n")
                .append("**Цель (как её получил ассистент):** ").append(s.goal()).append("\n\n")
                .append("| Проверка | Итог | Детали |\n|---|---|---|\n");
        checks.forEach(c -> sb.append("| ").append(c.name()).append(" | ").append(c.ok() ? "✅" : "❌").append(" | ")
                .append(c.details().replace("|", "\\|")).append(" |\n"));
        sb.append("\n<details><summary>Трасса вызовов MCP (").append(r.calls().size()).append(")</summary>\n\n")
                .append("| # | Инструмент | Аргументы | Итог |\n|---|---|---|---|\n");
        int i = 1;
        for (FilesAssistant.ToolCall c : r.calls()) {
            Map<String, Object> shown = new LinkedHashMap<>(c.args() == null ? Map.of() : c.args());
            shown.computeIfPresent("content", (k, v) -> "<" + String.valueOf(v).length() + " символов>");
            shown.computeIfPresent("old_text", (k, v) -> ProjectFiles.clip(String.valueOf(v).replace("\n", "⏎"), 60));
            shown.computeIfPresent("new_text", (k, v) -> ProjectFiles.clip(String.valueOf(v).replace("\n", "⏎"), 60));
            sb.append("| ").append(i++).append(" | `").append(c.tool()).append("` | ").append(ProjectFiles.clip(shown.toString(), 160).replace("|", "\\|"))
                    .append(" | ").append(c.error() ? "ошибка" : "ok").append(" |\n");
        }
        sb.append("\n</details>\n\n");
        sb.append("Прочитаны: ").append(r.filesRead().isEmpty() ? "—" : String.join(", ", r.filesRead().stream().map(x -> "`" + x + "`").toList())).append("\n\n");
        if (patch != null) {
            sb.append("Diff: `").append(patch).append("` (").append(ProjectFiles.lineCount(r.diff())).append(" строк)\n\n```diff\n")
                    .append(ProjectFiles.clip(r.diff(), 4_000)).append(r.diff().endsWith("\n") ? "" : "\n").append("```\n\n");
        }
        sb.append("<details><summary>Ответ ассистента</summary>\n\n").append(r.error() != null ? "⚠ " + r.error() : r.answer()).append("\n\n</details>\n\n");
        return sb.toString();
    }

    /** "04. Моделирование нагрузки/02.1 Задания - закон Литтла.md" → "Задания - закон Литтла" */
    static String title(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.md$", "");
        String t = name.replaceFirst("^\\d+(\\.\\d+)?\\.?\\s+", "");
        return t.isBlank() ? name : t;
    }

    /** lower case, ё→е, only letters and digits — "Задание - проверки" and "задание: проверки" compare equal */
    static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static String git(Path root, String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "core.quotepath=off"));
            cmd.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return "git: " + e.getMessage();
        }
    }

    /** "" = the patch applies cleanly to the project as it is on disk. */
    static String gitApplyCheck(Path root, Path patch) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", root.toString(), "apply", "--check", patch.toAbsolutePath().toString())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor(30, TimeUnit.SECONDS);
            return p.exitValue() == 0 ? "" : out;
        } catch (Exception e) {
            return "git недоступен: " + e.getMessage();
        }
    }
}
