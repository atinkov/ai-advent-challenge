package ru.lemanapro.aiadventchallenge.week7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.awt.Desktop;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Day 35 «Реальная задача»: AI-наставник курса НТ — помогает учиться, проверяет домашние задания (код Gatling),
 * подсказывает без готового решения и генерирует новые задачи по запросу ученика. Работает на реальном курсе
 * (../lmru--qacc--performance-qa-course) и реальной LLM из .env.
 *
 *   mvn -q compile exec:java -Ptask35                                              → веб-интерфейс http://localhost:8035
 *   mvn -q exec:java -Ptask35 -Dexec.args="assignments"                          → задания курса и их требования (без LLM)
 *   mvn -q exec:java -Ptask35 -Dexec.args="review 12.1 ../my-gatling/src"        → проверка ДЗ → mentor-output/reviews/*.md
 *   mvn -q exec:java -Ptask35 -Dexec.args="review 12.1 src --out review.md --strict"  → для CI: код выхода 1, если не зачтено
 *   mvn -q exec:java -Ptask35 -Dexec.args="hint 12.1 --level 2 Как не считать 400 ошибкой?"   (+ --code <папка>)
 *   mvn -q exec:java -Ptask35 -Dexec.args="task 02/13 --level сложный"            → новое задание → mentor-output/tasks/*.md
 *   mvn -q exec:java -Ptask35 -Dexec.args="progress"                             → история ученика и слабые места
 *   mvn -q exec:java -Ptask35 -Dexec.args="check"                                → проверка задания дня → task35-mentor-report.md
 * Env: TASK35_COURSE_DIR (по умолчанию как у дня 34), TASK35_STUDENT ("student"), TASK35_PORT (8035), TASK35_OPEN_BROWSER (1).
 */
public final class Task35 {

    static final Path REPORT = Path.of("task35-mentor-report.md");
    static final Path SAMPLES = Path.of("mentor/samples");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>(List.of(argv));
        Path root = ProjectFiles.resolveRoot(LlmClient.env("TASK35_COURSE_DIR", ""));
        if (!Files.isDirectory(root)) {
            System.out.println("Курс не найден: " + root + " — задайте TASK35_COURSE_DIR");
            System.exit(1);
        }
        LlmClient.Config cfg = LlmClient.env("LLM_API_KEY", null) == null ? null : LlmClient.fromEnv();
        CourseMentor m = new CourseMentor(root, cfg, LlmClient.env("TASK35_STUDENT", "student"));
        String mode = args.isEmpty() ? "web" : args.removeFirst();
        if (cfg == null && !Set.of("assignments", "progress").contains(mode)) {
            System.out.println("Нет LLM_API_KEY (.env) — наставнику нужна LLM. Без неё доступны: assignments, progress");
            System.exit(1);
        }
        switch (mode) {
            case "assignments" -> assignments(m);
            case "review" -> review(m, args);
            case "hint" -> hint(m, args);
            case "task" -> task(m, args);
            case "progress" -> System.out.println(m.progressMarkdown());
            case "check" -> check(m);
            default -> web(m);
        }
    }

    // ================================================================ CLI

    static String opt(List<String> args, String name, String def) {
        int i = args.indexOf(name);
        if (i < 0 || i + 1 >= args.size()) return def;
        String v = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        return v;
    }

    static void assignments(CourseMentor m) throws Exception {
        System.out.println("Курс: " + m.courseRoot());
        for (String p : m.assignmentPaths()) {
            CourseMentor.Assignment a = m.assignment(p);
            System.out.println("\n" + p + "\n  " + (a.practice() ? "практика" : "теория") + ", урок: " + a.lessonPath() + "\n  требований: "
                    + a.requirements().size() + ", предусловий: " + a.preconditions().size() + ", критериев: " + a.criteria().size()
                    + ", эталон: " + (a.reference().isEmpty() ? "нет" : a.reference().length() + " символов"));
            a.requirements().forEach(r -> System.out.println("    " + r.id() + (r.optional() ? "*" : "") + ". " + ProjectFiles.clip(r.text(), 110)));
        }
    }

    static void review(CourseMentor m, List<String> args) throws Exception {
        String out = opt(args, "--out", null);
        boolean strict = args.remove("--strict");
        if (args.size() < 2) {
            System.out.println("Использование: review <задание: 12.1 | 02/12.1 | часть названия> <папка или файл с решением> [--out файл] [--strict]");
            System.exit(2);
        }
        System.out.println("Проверяю " + args.get(1) + " по заданию «" + args.getFirst() + "»…");
        CourseMentor.Review r = m.review(args.get(0), CourseMentor.loadSolution(Path.of(args.get(1))));
        String md = m.reviewMarkdown(r);
        System.out.println("\n" + md);
        Path file = out != null ? Path.of(out) : CourseMentor.OUT.resolve("reviews").resolve(CourseMentor.slug(r.title()) + ".md");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, md, StandardCharsets.UTF_8);
        System.out.println("→ " + file);
        if (strict && !r.passed()) System.exit(1);
    }

    static void hint(CourseMentor m, List<String> args) throws Exception {
        int level = Integer.parseInt(opt(args, "--level", "1"));
        String code = opt(args, "--code", null);
        if (args.size() < 2) {
            System.out.println("Использование: hint <задание> [--level 1|2|3] [--code папка] <вопрос>");
            System.exit(2);
        }
        String a = args.removeFirst();
        CourseMentor.Hint h = m.hint(a, String.join(" ", args), code == null ? null : CourseMentor.loadSolution(Path.of(code)), level);
        System.out.println(h.error() != null ? "⚠ " + h.error() : h.text());
        System.out.println("\n── уровень " + h.level() + ", раздел урока: " + (h.sectionVerified() ? h.section() : "не указан") + ", код ≤ " + h.maxCodeLines() + " строк"
                + (h.unknownCalls().isEmpty() ? "" : ", ⚠ вызовы не из курса: " + h.unknownCalls()));
    }

    static void task(CourseMentor m, List<String> args) throws Exception {
        String level = opt(args, "--level", "средний");
        String focus = opt(args, "--focus", null);
        if (args.isEmpty()) {
            System.out.println("Использование: task <урок: 02/13 | часть названия> [--level лёгкий|средний|сложный] [--focus «что потренировать»]");
            System.exit(2);
        }
        CourseMentor.GeneratedTask t = m.generateTask(String.join(" ", args), level, focus);
        if (t.error() != null) {
            System.out.println("⚠ " + t.error());
            return;
        }
        System.out.println(t.markdown());
        System.out.println("\n── " + (t.valid() ? "✅ формат и стенд проверены" : "⚠ " + String.join("; ", t.problems())) + " → " + t.file());
    }

    // ================================================================ web

    static void web(CourseMentor m) throws Exception {
        int port = Integer.parseInt(LlmClient.env("TASK35_PORT", "8035"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                if (path.equals("/")) {
                    try (InputStream in = Task35.class.getResourceAsStream("/task35/index.html")) {
                        send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
                    }
                    return;
                }
                if (ex.getRequestMethod().equals("POST")) {
                    String origin = ex.getRequestHeaders().getFirst("Origin");
                    if (origin != null && !origin.matches("http://(localhost|127\\.0\\.0\\.1):" + port)) {
                        send(ex, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                }
                Object out = switch (path) {
                    case "/api/meta" -> meta(m);
                    case "/api/review" -> {
                        JsonNode b = JSON.readTree(ex.getRequestBody());
                        CourseMentor.Review r = m.review(b.path("assignment").asText(), CourseMentor.parsePasted(b.path("code").asText()));
                        yield Map.of("markdown", m.reviewMarkdown(r), "passed", r.passed(), "met", r.met(), "total", r.total());
                    }
                    case "/api/hint" -> {
                        JsonNode b = JSON.readTree(ex.getRequestBody());
                        String code = b.path("code").asText("");
                        CourseMentor.Hint h = m.hint(b.path("assignment").asText(), b.path("question").asText(),
                                code.isBlank() ? null : CourseMentor.parsePasted(code), b.path("level").asInt(1));
                        yield Map.of("markdown", h.error() != null ? "⚠ " + h.error() : h.text(), "section", h.section());
                    }
                    case "/api/task" -> {
                        JsonNode b = JSON.readTree(ex.getRequestBody());
                        CourseMentor.GeneratedTask t = m.generateTask(b.path("lesson").asText(), b.path("level").asText("средний"), b.path("focus").asText(""));
                        yield Map.of("markdown", t.error() != null ? "⚠ " + t.error() : t.markdown(), "problems", t.problems(),
                                "file", t.file() == null ? "" : t.file().toString(), "focus", t.focus());
                    }
                    case "/api/progress" -> Map.of("markdown", m.progressMarkdown());
                    default -> null;
                };
                if (out == null) send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                else send(ex, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(out));
            } catch (Exception e) {
                try {
                    send(ex, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(Map.of("markdown", "⚠ " + e.getMessage())));
                } catch (Exception ignored) {
                    // client gone
                }
            }
        });
        server.start();
        String url = "http://localhost:" + port;
        System.out.println("Наставник курса НТ: " + url + "  (курс: " + m.courseRoot() + ", LLM: " + m.model() + ")\nCtrl+C — остановить.");
        try {
            if (!"0".equals(LlmClient.env("TASK35_OPEN_BROWSER", "1")) && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            // open manually
        }
        Thread.currentThread().join();
    }

    static Map<String, Object> meta(CourseMentor m) throws Exception {
        List<Map<String, Object>> as = new ArrayList<>();
        for (String p : m.assignmentPaths()) {
            CourseMentor.Assignment a = m.assignment(p);
            boolean practice = a.practice();
            as.add(Map.of("path", p, "title", p.substring(0, 2) + " · " + a.title(), "requirements", a.requirements().size(),
                    "kind", practice ? "практика: код" : "теория: ответ текстом"));
        }
        List<Map<String, Object>> ls = new ArrayList<>();
        for (String p : m.lessonPaths()) ls.add(Map.of("path", p, "title", p.substring(0, 2) + " · " + CourseMentor.titleOf(p)));
        return Map.of("assignments", as, "lessons", ls, "model", m.model(), "weak", m.weakSpots());
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    // ================================================================ check of the day's assignment

    record Check(String name, boolean ok, String details) {
    }

    /** Requirements the buggy sample breaks on purpose (see mentor/samples/README.md). */
    static final Map<String, String> PLANTED = new LinkedHashMap<>(Map.of(
            "R1", "нет проверки accessToken и exitHereIfFailed после логина",
            "R3", "перевод проверяет только код ответа, без поля тела",
            "R4", "снятие сверх баланса (400) считается ошибкой",
            "R8", "нет условия на ноль неуспешных авторизаций",
            "R9", "пороги assertions не обоснованы комментарием"));

    static void check(CourseMentor m) throws Exception {
        List<Check> rows = new ArrayList<>();
        StringBuilder details = new StringBuilder();
        String assignment = "02/12.1";

        // 0. задание курса разобрано кодом
        CourseMentor.Assignment a = m.assignment(m.resolveAssignment(assignment));
        rows.add(new Check("0. Задание курса разобрано в требования (без LLM)", a.requirements().size() >= 8 && a.lessonPath() != null,
                a.path() + ": " + a.requirements().size() + " требований, " + a.criteria().size() + " критериев, урок `" + a.lessonPath() + "`"));

        // 1. проверка ДЗ: верное решение и решение с заложенными ошибками
        CourseMentor.Review good = m.review(assignment, CourseMentor.loadSolution(SAMPLES.resolve("12.1-good")));
        CourseMentor.Review bad = m.review(assignment, CourseMentor.loadSolution(SAMPLES.resolve("12.1-buggy")));
        Files.createDirectories(CourseMentor.OUT.resolve("reviews"));
        Files.writeString(CourseMentor.OUT.resolve("reviews/check-12.1-good.md"), m.reviewMarkdown(good), StandardCharsets.UTF_8);
        Files.writeString(CourseMentor.OUT.resolve("reviews/check-12.1-buggy.md"), m.reviewMarkdown(bad), StandardCharsets.UTF_8);
        List<String> caught = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        for (var e : PLANTED.entrySet()) {
            boolean notMet = bad.requirements().stream().anyMatch(q -> q.id().equals(e.getKey()) && !q.status().equals("met"));
            (notMet ? caught : missed).add(e.getKey() + " (" + e.getValue() + ")");
        }
        long falseAlarms = good.requirements().stream().filter(q -> !q.status().equals("met")).count();
        long verified = good.requirements().stream().filter(q -> q.evidence() != null && q.evidence().verified()).count();
        rows.add(new Check("1a. Верное решение: зачтено, у каждого «выполнено» — цитата, найденная в коде", good.error() == null && good.passed(),
                good.verdict() + ", " + good.met() + "/" + good.total() + "; подтверждённых цитат: " + verified
                        + (falseAlarms > 0 ? "; не зачтены: " + good.requirements().stream().filter(q -> !q.status().equals("met")).map(q -> q.id() + " — " + q.comment()).toList() : "")
                        + (good.error() != null ? "; ошибка: " + good.error() : "")));
        rows.add(new Check("1b. Решение с ошибками: не зачтено, найдены все 5 заложенных ошибок", bad.error() == null && !bad.passed() && missed.isEmpty(),
                bad.verdict() + ", " + bad.met() + "/" + bad.total() + "; найдены: " + String.join("; ", caught)
                        + (missed.isEmpty() ? "" : "; ПРОПУЩЕНЫ: " + String.join("; ", missed)) + (bad.error() != null ? "; ошибка: " + bad.error() : "")));
        boolean issuesAnchored = bad.issues().stream().allMatch(i -> i.line() > 0);
        rows.add(new Check("1c. Замечания привязаны к файлу и строке кода студента", !bad.issues().isEmpty() && issuesAnchored,
                bad.issues().size() + " замечаний: " + bad.issues().stream().map(i -> i.file() + ":" + i.line()).toList()));
        details.append("### Проверка ДЗ — решение с ошибками\n\n").append(m.reviewMarkdown(bad).replaceFirst("^# ", "#### ")).append("\n\n")
                .append("### Проверка ДЗ — верное решение\n\n").append(m.reviewMarkdown(good).replaceFirst("^# ", "#### ")).append("\n\n");

        // 2. подсказка без готового решения
        CourseMentor.Hint h = m.hint(assignment, "Снятие сверх баланса у меня падает в KO и портит процент успешных. Как это исправить?",
                CourseMentor.loadSolution(SAMPLES.resolve("12.1-buggy")), 2);
        boolean noSolution = h.error() == null && h.maxCodeLines() <= CourseMentor.MAX_HINT_CODE_LINES && !h.text().contains("checkIf(session");
        rows.add(new Check("2. Подсказка уровня 2: без готового решения, без выдуманного API, со ссылкой на реальный раздел урока",
                noSolution && h.sectionVerified() && h.unknownCalls().isEmpty(),
                "код в подсказке ≤ " + h.maxCodeLines() + " строк; вызовов не из курса: " + (h.unknownCalls().isEmpty() ? "нет" : h.unknownCalls())
                        + "; раздел: " + (h.sectionVerified() ? "«" + h.section() + "»" : "не найден")
                        + (h.repaired() ? "; первая версия переписана по замечанию" : "") + (h.error() != null ? "; ошибка: " + h.error() : "")));
        details.append("### Подсказка (уровень 2)\n\n> ").append(h.question()).append("\n\n").append(h.error() != null ? "⚠ " + h.error() : h.text()).append("\n\n");

        // 3. новая задача по слабым местам
        List<String> weak = m.weakSpots();
        CourseMentor.GeneratedTask t = m.generateTask("02/13", "средний", null);
        rows.add(new Check("3. Новое задание: формат курса, только эндпоинты стенда, не дубль, по слабым местам", t.valid() && !t.focus().isEmpty(),
                (t.error() != null ? "ошибка: " + t.error() : "файл `" + t.file() + "`; эндпоинты: " + t.endpoints() + "; макс. сходство с заданиями курса "
                        + String.format("%.2f", t.maxSimilarity()) + "; фокус: " + ProjectFiles.clip(t.focus(), 200)
                        + (t.problems().isEmpty() ? "" : "; проблемы: " + t.problems()) + (t.repaired() ? "; исправлено со второй попытки" : ""))));
        details.append("### Сгенерированное задание (к уроку 02/13, фокус — слабые места из проверки)\n\n").append(t.markdown().replaceAll("(?m)^#", "###")).append("\n\n");

        boolean done = rows.stream().allMatch(Check::ok);
        StringBuilder md = new StringBuilder("# День 35. Реальная задача: AI-наставник курса НТ — проверка\n\n")
                .append("> ").append(CourseMentor.now()).append(", модель `").append(m.model()).append("`, курс `").append(m.courseRoot().getFileName()).append("`\n\n")
                .append("""
                        ## Задача

                        Курс по нагрузочному тестированию (`lmru--qacc--performance-qa-course`) — это ~60 уроков и заданий, практика — код
                        Gatling. Узкое место — преподаватель: проверить каждое ДЗ по всем требованиям, ответить на «застрял» без выдачи
                        решения и дать ученику ещё практики по теме, где он ошибся. Наставник берёт эти три работы на себя.

                        ## Как участвует AI и что делает код

                        | Шаг | Код (детерминированно) | LLM |
                        |---|---|---|
                        | Проверка ДЗ | разбирает задание курса на требования R1…Rn, нумерует строки кода студента, проверяет цитаты-доказательства, считает вердикт, пишет историю | оценивает каждое требование по коду, объясняет ошибки, советует следующий шаг |
                        | Подсказка | проверяет, что кода ≤ 3 строк и раздел урока существует; иначе — переписать | наводящая подсказка уровня 1–3 по заданию, уроку и коду |
                        | Новая задача | собирает урок, пример оформления, эндпоинты стенда, слабые места; проверяет формат, эндпоинты, похожесть | составляет задание в формате курса |

                        """)
                .append("## Вердикт: ").append(done ? "✅ задание выполнено" : "❌ задание не выполнено").append("\n\n| Проверка | Итог | Детали |\n|---|---|---|\n");
        rows.forEach(r -> md.append("| ").append(r.name()).append(" | ").append(r.ok() ? "✅" : "❌").append(" | ").append(r.details().replace("|", "\\|")).append(" |\n"));
        md.append("\nСлабые места, переданные генератору: ").append(weak.isEmpty() ? "—" : String.join("; ", weak)).append("\n\n").append(details);
        Files.writeString(REPORT, md.toString(), StandardCharsets.UTF_8);
        System.out.println("══ Проверка задания дня 35 ══");
        rows.forEach(r -> System.out.println((r.ok() ? "✅ " : "❌ ") + r.name() + "\n    " + r.details()));
        System.out.println("\n" + (done ? "✅ ЗАДАНИЕ ВЫПОЛНЕНО" : "❌ ЗАДАНИЕ НЕ ВЫПОЛНЕНО") + " → " + REPORT);
    }
}
