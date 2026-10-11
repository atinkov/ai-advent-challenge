package ru.lemanapro.aiadventchallenge.week6;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Day 27: a real application on top of the LOCAL LLM — "Наставник НТ", a web app for studying the course
 * lmru--qacc--performance-qa-course with qwen3:8b running in Ollama on this machine.
 *
 * In the browser: course tree with progress (left), the lesson (centre), the tutor (right):
 *   - questions about the course — answer streamed token by token, with numbered sources that open the lesson;
 *   - «Конспект» / «Объясни проще» for the open lesson;
 *   - «Проверь себя» — a 4-question multiple-choice test generated from the lesson;
 *   - «Подсказка» on assignment pages — hints without the ready solution.
 * The model logic lives in {@link StudyAssistant}; this class is only the HTTP layer:
 * JDK com.sun.net.httpserver (no new dependencies), bound to 127.0.0.1, static UI from
 * src/main/resources/task27/, JSON API, and NDJSON streaming for the model output
 * ({type: sources | thinking | token | progress | quiz | done | error} per line). A closed browser tab aborts
 * the Ollama request (the write fails → the stream to Ollama is closed).
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask27                     # start → http://localhost:8027
 *   mvn -q exec:java -Ptask27 -Dexec.args="check"         # no browser: run every tutor feature once,
 *                                                         # verify the results, write task27-study-app-report.md
 * Env: TASK27_MODEL (qwen3:8b), OLLAMA_HOST (http://localhost:11434), TASK27_PORT (8027),
 *      TASK27_COURSE_DIR, TASK27_INDEX (task27-course-index.json), TASK27_NUM_CTX (8192), TASK27_TOP_K (5),
 *      TASK27_THINK (0 — default of the «думать» switch), TASK27_OPEN_BROWSER (1).
 */
public final class Task27 {

    private static final Path REPORT = Path.of("task27-study-app-report.md");
    private static final Map<String, String> ASSETS = Map.of(
            "app.js", "text/javascript; charset=utf-8",
            "style.css", "text/css; charset=utf-8",
            "marked.min.js", "text/javascript; charset=utf-8",
            "favicon.svg", "image/svg+xml");

    private final CourseLibrary lib;
    private final OllamaClient ollama;
    private final StudyAssistant tutor;
    private final String model;
    private final int port;

    private Task27(CourseLibrary lib, OllamaClient ollama, StudyAssistant tutor, String model, int port) {
        this.lib = lib;
        this.ollama = ollama;
        this.tutor = tutor;
        this.model = model;
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        String model = LlmClient.env("TASK27_MODEL", "qwen3:8b");
        int port = Integer.parseInt(LlmClient.env("TASK27_PORT", "8027"));
        OllamaClient ollama = OllamaClient.fromEnv();

        String version;
        try {
            version = ollama.version();
        } catch (Exception e) {
            System.err.println("❌ " + e.getMessage());
            System.err.println("Запустите Ollama: brew services start ollama  (или откройте Ollama.app)");
            System.exit(1);
            return;
        }
        if (ollama.find(model) == null) {
            System.err.println("❌ В Ollama " + version + " нет модели " + model + ". Скачайте: ollama pull " + model);
            System.exit(1);
            return;
        }
        Path root = CourseLibrary.resolveRoot();
        CourseLibrary lib = CourseLibrary.load(root);
        System.out.println("Курс: " + root + " — " + lib.modules().size() + " модулей, " + lib.lessonCount() + " файлов");
        StudyAssistant tutor;
        try (HttpClient http = HttpClient.newHttpClient()) {
            tutor = StudyAssistant.open(lib, ollama, model, http);
        }
        Task27 app = new Task27(lib, ollama, tutor, model, port);
        if (args.length > 0 && args[0].equalsIgnoreCase("check")) {
            System.exit(app.check(version));
        }
        app.serve(version);
    }

    // ================================================================ web server

    private void serve(String version) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
        String url = "http://localhost:" + port;
        System.out.println("✅ Наставник НТ: " + url + "  (модель " + model + ", Ollama " + version + " на " + ollama.baseUrl()
                + ", эмбеддер " + tutor.embedderId() + ")");
        System.out.println("Остановить: Ctrl+C");
        if (!LlmClient.env("TASK27_OPEN_BROWSER", "1").matches("(?i)0|false|off|no")) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(URI.create(url));
                }
            } catch (Exception | Error ignored) {
                // headless / no browser — the URL is printed above
            }
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getRawPath();
            String method = ex.getRequestMethod();
            if (method.equals("POST") && !sameOrigin(ex)) {
                sendJson(ex, 403, Map.of("error", "запрос с чужого сайта отклонён"));
                return;
            }
            switch (method + " " + (path.startsWith("/assets/") ? "/assets/" : path.startsWith("/course-file/") ? "/course-file/" : path)) {
                case "GET /", "GET /index.html" -> sendResource(ex, "index.html", "text/html; charset=utf-8");
                case "GET /assets/" -> {
                    String name = path.substring("/assets/".length());
                    if (ASSETS.containsKey(name)) {
                        sendResource(ex, name, ASSETS.get(name));
                    } else {
                        sendJson(ex, 404, Map.of("error", "нет файла"));
                    }
                }
                case "GET /course-file/" -> sendCourseFile(ex, URLDecoder.decode(path.substring("/course-file/".length()), StandardCharsets.UTF_8));
                case "GET /api/status" -> sendJson(ex, 200, status());
                case "GET /api/course" -> sendJson(ex, 200, course());
                case "GET /api/lesson" -> lesson(ex);
                case "POST /api/ask" -> stream(ex, (body, emit) -> {
                    List<Map<String, String>> history = new ArrayList<>();
                    for (JsonNode m : body.path("history")) {
                        history.add(Map.of("role", m.path("role").asText(), "content", m.path("content").asText()));
                    }
                    StudyAssistant.Mode mode = body.path("mode").asText("ask").equals("hint") ? StudyAssistant.Mode.HINT : StudyAssistant.Mode.ASK;
                    return tutor.ask(body.path("question").asText("").strip(), text(body, "lessonId"), mode, history,
                            body.path("think").asBoolean(false), emit);
                });
                case "POST /api/explain" -> stream(ex, (body, emit) -> tutor.explain(text(body, "lessonId"),
                        body.path("kind").asText("summary").equals("simple") ? StudyAssistant.Explain.SIMPLE : StudyAssistant.Explain.SUMMARY,
                        body.path("think").asBoolean(false), emit));
                case "POST /api/quiz" -> stream(ex, (body, emit) -> {
                    StudyAssistant.Quiz quiz = tutor.quiz(text(body, "lessonId"), Math.clamp(body.path("count").asInt(4), 1, 8),
                            body.path("think").asBoolean(false), emit);
                    emit.accept(Map.of("type", "quiz", "questions", quiz.questions()));
                    return quiz.stats();
                });
                default -> sendJson(ex, 404, Map.of("error", "нет такого адреса: " + method + " " + path));
            }
        } catch (Exception e) {
            System.err.println("HTTP " + ex.getRequestURI() + ": " + e);
        }
    }

    /** POST from a page of another site must not drive the local model: Origin, if present, must be ours. */
    private boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equals("http://localhost:" + port) || origin.equals("http://127.0.0.1:" + port);
    }

    private Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("model", model);
        s.put("ollamaHost", ollama.baseUrl());
        String host = URI.create(ollama.baseUrl()).getHost();
        s.put("local", host.equals("localhost") || host.startsWith("127.") || host.equals("::1"));
        try {
            s.put("ollamaVersion", ollama.version());
            OllamaClient.ModelInfo info = ollama.find(model);
            s.put("modelReady", info != null);
            if (info != null) {
                s.put("modelDetails", info.parameterSize() + ", " + info.quantization());
            }
            s.put("loaded", ollama.loaded().stream().anyMatch(m -> m.name().equals(info == null ? model : info.name())));
        } catch (Exception e) {
            s.put("ollamaVersion", null);
            s.put("error", e.getMessage());
        }
        s.put("embedder", tutor.embedderId());
        s.put("chunks", tutor.chunkCount());
        s.put("lessons", lib.lessonCount());
        s.put("numCtx", tutor.settings().numCtx());
        s.put("thinkDefault", LlmClient.env("TASK27_THINK", "0").matches("(?i)1|true|on|yes"));
        return s;
    }

    private Map<String, Object> course() {
        List<Map<String, Object>> modules = new ArrayList<>();
        for (CourseLibrary.Module m : lib.modules()) {
            modules.add(Map.of("name", m.name(), "lessons", m.lessons()));
        }
        return Map.of("title", "Тестирование производительности на Gatling", "root", lib.root().getFileName().toString(),
                "modules", modules);
    }

    private void lesson(HttpExchange ex) throws IOException {
        String id = query(ex, "id");
        CourseLibrary.Lesson l = lib.lesson(id);
        if (l == null) {
            sendJson(ex, 404, Map.of("error", "урок не найден"));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lesson", l);
        out.put("markdown", lib.text(id));
        CourseLibrary.Lesson prev = lib.neighbour(id, -1);
        CourseLibrary.Lesson next = lib.neighbour(id, 1);
        out.put("prev", prev);
        out.put("next", next);
        sendJson(ex, 200, out);
    }

    @FunctionalInterface
    private interface StreamCall {
        OllamaClient.ChatResult run(JsonNode body, Consumer<Map<String, Object>> emit) throws Exception;
    }

    /** NDJSON response: one JSON event per line, flushed immediately; ends with done (metrics) or error. */
    private void stream(HttpExchange ex, StreamCall call) throws Exception {
        JsonNode body = LlmClient.parseJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        Consumer<Map<String, Object>> emit = ev -> {
            try {
                out.write((LlmClient.toJson(ev) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("браузер закрыл соединение", e);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        try {
            OllamaClient.ChatResult r = call.run(body, emit);
            emit.accept(Map.of("type", "done", "stats", stats(r)));
        } catch (UncheckedIOException e) {
            System.out.println("· генерация остановлена: " + e.getMessage());
        } catch (Exception e) {
            try {
                emit.accept(Map.of("type", "error", "message", String.valueOf(e.getMessage())));
            } catch (Exception ignored) {
                // client is gone
            }
        }
    }

    private static Map<String, Object> stats(OllamaClient.ChatResult r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("promptTokens", r.promptTokens());
        s.put("outputTokens", r.outputTokens());
        s.put("tokensPerSecond", Math.round(r.tokensPerSecond() * 10) / 10.0);
        s.put("seconds", Math.round(r.wallMs() / 100.0) / 10.0);
        s.put("doneReason", r.doneReason());
        return s;
    }

    private void sendCourseFile(HttpExchange ex, String relative) throws IOException {
        Path p = lib.file(relative);
        if (p == null) {
            sendJson(ex, 404, Map.of("error", "нет файла"));
            return;
        }
        String type = Files.probeContentType(p);
        ex.getResponseHeaders().set("Content-Type", type != null ? type : "application/octet-stream");
        ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
        byte[] bytes = Files.readAllBytes(p);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void sendResource(HttpExchange ex, String name, String type) throws IOException {
        try (InputStream in = Task27.class.getResourceAsStream("/task27/" + name)) {
            if (in == null) {
                sendJson(ex, 404, Map.of("error", "ресурс " + name + " не найден — mvn compile"));
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object value) throws IOException {
        byte[] bytes;
        try {
            bytes = LlmClient.toJson(value).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException(e);
        }
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(name)) {
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String text(JsonNode body, String field) {
        String s = body.path(field).asText("");
        return s.isBlank() ? null : s;
    }

    // ================================================================ check mode + report

    record Step(String feature, String request, String answer, List<StudyAssistant.Source> sources,
                OllamaClient.ChatResult stats, boolean ok, String verdict) {
    }

    /** Runs every tutor feature once through the same code the web UI uses, checks the results, writes the report. */
    private int check(String version) throws Exception {
        List<Step> steps = new ArrayList<>();
        CourseLibrary.Lesson little = find("закон литтла на практике", CourseLibrary.Kind.LESSON);
        CourseLibrary.Lesson littleTask = find("закон литтла", CourseLibrary.Kind.ASSIGNMENT);
        CourseLibrary.Lesson metrics = find("метрики производительности", CourseLibrary.Kind.LESSON);

        // 1. question about the course (no lesson open) — a lesson on load models must be among the sources
        attempt(steps, "Вопрос по курсу", () -> runAsk("Вопрос по курсу",
                "Чем открытая модель нагрузки отличается от закрытой и когда какую выбирать?",
                null, StudyAssistant.Mode.ASK, List.of(), "модел", s -> s.lessonTitle().toLowerCase(Locale.ROOT).contains("модел")));
        // 2. question inside the open lesson + a short follow-up resolved through the dialogue history
        String first = "Что такое перцентиль 95 и почему он важнее среднего?";
        attempt(steps, "Вопрос в контексте урока", () -> runAsk("Вопрос в контексте урока", first,
                metrics == null ? null : metrics.id(), StudyAssistant.Mode.ASK, List.of(),
                "перцентил", s -> metrics != null && s.lessonId().equals(metrics.id())));
        String firstAnswer = steps.getLast().answer();
        attempt(steps, "Уточняющий вопрос (история диалога)", () -> runAsk("Уточняющий вопрос (история диалога)",
                "А какой перцентиль обычно берут в SLA?", metrics == null ? null : metrics.id(), StudyAssistant.Mode.ASK,
                List.of(Map.of("role", "user", "content", first), Map.of("role", "assistant", "content", firstAnswer)),
                "перцентил|p9|99|95", s -> true));
        // 3. hint for an assignment — must not reveal the final numbers of the solution
        if (littleTask != null) {
            attempt(steps, "Подсказка к заданию", () -> {
                Step hint = runAsk("Подсказка к заданию", "Не понимаю, с чего начать задание 1 про онлайн-кинотеатр.",
                        littleTask.id(), StudyAssistant.Mode.HINT, List.of(), "λ|лямбд|литтл|сесси|частот|запрос",
                        s -> !s.lessonId().equals(littleTask.id()));
                boolean leaks = hint.answer().contains("61,5") || hint.answer().contains("61.5");
                return new Step(hint.feature(), hint.request(), hint.answer(), hint.sources(), hint.stats(), hint.ok() && !leaks,
                        hint.verdict() + (leaks ? "; выдал итоговое число решения (61,5)" : "; итоговое число решения (61,5 с) не выдано"));
            });
        }
        // 4. explain the lesson
        if (little != null) {
            attempt(steps, "Конспект урока", () -> {
                StringBuilder out = new StringBuilder();
                OllamaClient.ChatResult r = tutor.explain(little.id(), StudyAssistant.Explain.SUMMARY, false, collect(out));
                String text = out.toString();
                boolean ok = text.contains("L") && text.toLowerCase(Locale.ROOT).matches("(?s).*(λ|лямбд|частот).*");
                System.out.printf(Locale.ROOT, "%s конспект: %d ток., %.1f ток/с%n", ok ? "✓" : "✗", r.outputTokens(), r.tokensPerSecond());
                return new Step("Конспект урока", "«" + little.title() + "» → Конспект", text.strip(), List.of(), r, ok,
                        ok ? "конспект содержит формулу закона Литтла (L, λ)" : "в конспекте нет формулы закона Литтла");
            });
        }
        // 5. quiz
        if (little != null) {
            attempt(steps, "Тест по уроку", () -> quizStep(little));
        }

        boolean allOk = !steps.isEmpty() && steps.stream().allMatch(Step::ok);
        writeReport(version, steps, allOk);
        System.out.println();
        for (Step s : steps) {
            System.out.println((s.ok() ? "✅ " : "❌ ") + s.feature() + " — " + s.verdict());
        }
        System.out.println("Отчёт: " + REPORT);
        return allOk ? 0 : 1;
    }

    @FunctionalInterface
    private interface StepCall {
        Step run() throws Exception;
    }

    /** One feature of the check: an exception becomes a failed step in the report instead of aborting the run. */
    private static void attempt(List<Step> steps, String feature, StepCall call) {
        try {
            steps.add(call.run());
        } catch (Exception e) {
            System.out.println("✗ " + feature + ": " + e.getMessage());
            steps.add(new Step(feature, "—", "", List.of(), new OllamaClient.ChatResult("", "error", 0, 0, 0, 0, 0, 0, 0),
                    false, "ошибка: " + e.getMessage()));
        }
    }

    private Step quizStep(CourseLibrary.Lesson little) throws Exception {
        {
            StudyAssistant.Quiz quiz = tutor.quiz(little.id(), 4, false, e -> { });
            StringBuilder q = new StringBuilder();
            for (StudyAssistant.Question question : quiz.questions()) {
                q.append("**").append(question.question()).append("**\n");
                for (int i = 0; i < question.options().size(); i++) {
                    q.append(i == question.correct() ? "- ✅ " : "- ").append(question.options().get(i)).append('\n');
                }
                q.append("  _").append(question.explanation()).append("_\n\n");
            }
            int n = quiz.questions().size();
            System.out.println((n >= 3 ? "✓" : "✗") + " тест: " + n + " вопросов");
            return new Step("Тест по уроку", "«" + little.title() + "» → Проверь себя (4 вопроса)", q.toString().strip(),
                    quiz.sources(), quiz.stats(), n >= 3,
                    n + " валидных вопроса(ов) из 4 запрошенных (JSON по схеме, у каждого 3–5 вариантов и корректный индекс ответа)");
        }
    }

    private Step runAsk(String feature, String question, String lessonId, StudyAssistant.Mode mode,
                        List<Map<String, String>> history, String expect, java.util.function.Predicate<StudyAssistant.Source> goodSource)
            throws Exception {
        StringBuilder out = new StringBuilder();
        List<StudyAssistant.Source> sources = new ArrayList<>();
        Consumer<Map<String, Object>> sink = e -> {
            if ("sources".equals(e.get("type"))) {
                @SuppressWarnings("unchecked")
                List<StudyAssistant.Source> items = (List<StudyAssistant.Source>) e.get("items");
                sources.addAll(items);
            } else if ("token".equals(e.get("type"))) {
                out.append(e.get("text"));
            }
        };
        System.out.println("> " + question);
        OllamaClient.ChatResult r = tutor.ask(question, lessonId, mode, history, false, sink);
        String answer = out.toString().strip();
        boolean cites = answer.matches("(?s).*\\[\\d+].*");
        boolean onTopic = answer.toLowerCase(Locale.ROOT).matches("(?s).*(" + expect + ").*");
        boolean sourceOk = sources.stream().limit(3).anyMatch(goodSource);
        boolean ok = !answer.isBlank() && onTopic && sourceOk;
        String verdict = "ответ " + (onTopic ? "по теме" : "НЕ по теме") + ", ссылки [n] " + (cites ? "есть" : "нет")
                + ", релевантный источник в топ-3: " + (sourceOk ? "да" : "нет");
        System.out.printf(Locale.ROOT, "%s %s (%d ток., %.1f ток/с)%n", ok ? "✓" : "✗", verdict, r.outputTokens(), r.tokensPerSecond());
        return new Step(feature, question, answer, sources, r, ok, verdict);
    }

    private static Consumer<Map<String, Object>> collect(StringBuilder out) {
        return e -> {
            if ("token".equals(e.get("type"))) {
                out.append(e.get("text"));
            }
        };
    }

    private CourseLibrary.Lesson find(String titlePart, CourseLibrary.Kind kind) {
        for (CourseLibrary.Module m : lib.modules()) {
            for (CourseLibrary.Lesson l : m.lessons()) {
                if (l.kind() == kind && l.title().toLowerCase(Locale.ROOT).contains(titlePart)) {
                    return l;
                }
            }
        }
        return null;
    }

    private void writeReport(String version, List<Step> steps, boolean allOk) throws IOException {
        StringBuilder r = new StringBuilder();
        r.append("# День 27. Приложение на локальной LLM: «Наставник НТ»\n\n");
        r.append("> Раздел «Проверка» сгенерирован `Task27` (`mvn -q exec:java -Ptask27 -Dexec.args=\"check\"`) ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" и перезаписывается при каждом запуске проверки.\n\n");
        r.append("Веб-приложение для изучения курса **").append(lib.root().getFileName()).append("** (")
                .append(lib.modules().size()).append(" модулей, ").append(lib.lessonCount())
                .append(" уроков и заданий) с ИИ-наставником. Все ответы генерирует модель `").append(model)
                .append("` в Ollama ").append(version).append(" на этой машине (`").append(ollama.baseUrl())
                .append("`); облачные модели и API-ключи не используются.\n\n");
        r.append(APP_GUIDE.replace("{MODEL}", model));

        r.append("\n## Проверка: каждая функция наставника на живой модели\n\n");
        r.append("Прогон идёт через тот же `StudyAssistant`, что и веб-интерфейс. Модель `").append(model)
                .append("`, эмбеддер `").append(tutor.embedderId()).append("`, индекс — ").append(tutor.chunkCount())
                .append(" фрагментов, `num_ctx=").append(tutor.settings().numCtx()).append("`, `think=false`.\n\n");
        r.append("**Итог:** ").append(allOk ? "✅ все функции работают на локальной модели" : "❌ есть непройденные проверки").append("\n\n");
        r.append("| Функция | Токены ввод → вывод | Время | Скорость | Проверка |\n|---|---|---|---|---|\n");
        for (Step s : steps) {
            r.append("| ").append(s.feature()).append(" | ").append(s.stats().promptTokens()).append(" → ").append(s.stats().outputTokens())
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f с", s.stats().wallMs() / 1000.0))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f ток/с", s.stats().tokensPerSecond()))
                    .append(" | ").append(s.ok() ? "✅ " : "❌ ").append(s.verdict().replace("|", "\\|")).append(" |\n");
        }
        for (Step s : steps) {
            r.append("\n### ").append(s.feature()).append("\n\n");
            r.append("**Запрос:** ").append(s.request()).append("\n\n");
            if (!s.sources().isEmpty()) {
                r.append("**Найденные фрагменты курса:**\n\n");
                for (StudyAssistant.Source src : s.sources()) {
                    r.append("- [").append(src.n()).append("] ").append(src.lessonTitle())
                            .append(src.section().isBlank() ? "" : " › " + src.section())
                            .append(String.format(Locale.ROOT, " (%.3f)", src.score())).append('\n');
                }
                r.append('\n');
            }
            r.append("**Ответ:**\n\n");
            if (s.feature().equals("Тест по уроку")) {
                r.append(s.answer()).append("\n\n");
            } else {
                r.append(s.answer().lines().map(l -> "> " + l).reduce((a, b) -> a + "\n" + b).orElse(">")).append("\n\n");
            }
            r.append("**Проверка:** ").append(s.ok() ? "✅ " : "❌ ").append(s.verdict()).append("\n");
        }
        Files.writeString(REPORT, r.toString(), StandardCharsets.UTF_8);
    }

    private static final String APP_GUIDE = """
            ## Что умеет

            | Где | Что делает | Как устроено |
            |---|---|---|
            | Левая колонка | Дерево курса по модулям, прогресс: пройденные уроки и лучший результат теста | `GET /api/course`; прогресс хранится в браузере (localStorage) |
            | Центр | Урок: Markdown с таблицами, кодом и картинками курса; ссылки между уроками открываются внутри приложения | `GET /api/lesson`, картинки — `GET /course-file/…` (только из папки курса) |
            | Наставник → вопрос | Ответ по материалам курса, печатается по мере генерации, со ссылками [1], [2] на фрагменты — клик открывает урок на нужном разделе | `POST /api/ask`: RAG по курсу (фрагменты открытого урока в приоритете) → qwen3 → поток NDJSON |
            | Наставник → Конспект / Объясни проще | Конспект открытого урока или объяснение «на пальцах» с вопросами для самопроверки | `POST /api/explain`: текст урока целиком (длинный — посекционно ужимается) |
            | Наставник → Проверь себя | 4 вопроса с выбором ответа по уроку, сразу показывает верный ответ и пояснение | `POST /api/quiz`: случайные фрагменты урока → JSON по схеме (structured output Ollama) → проверка в коде |
            | Наставник → Подсказка (на страницах заданий) | Наводящие вопросы и первый шаг без готового решения | `POST /api/ask` с `mode=hint` |
            | Переключатель «Думать» | qwen3 сначала рассуждает (видно в свёрнутом блоке), потом отвечает — дольше, но точнее | `think: true` в `/api/chat` Ollama |

            ## Как запустить

            ```bash
            # 1. Ollama и модель (один раз)
            brew install ollama && brew services start ollama
            ollama pull {MODEL}
            ollama pull bge-m3            # необязательно: смысловой поиск по курсу вместо лексического

            # 2. Приложение
            cd ~/Desktop/AIAdventChallenge
            mvn -q compile exec:java -Ptask27          # откроет http://localhost:8027
            ```

            Курс берётся из `~/Lemana/lmru--qacc--performance-qa-course` (или `TASK27_COURSE_DIR`). При первом запуске
            строится индекс курса `task27-course-index.json`; он пересобирается сам, если поменялся текст курса или эмбеддер.

            | Переменная | По умолчанию | Зачем |
            |---|---|---|
            | `TASK27_MODEL` | `qwen3:8b` | модель Ollama для ответов |
            | `TASK27_PORT` | `8027` | порт веб-приложения (слушает только 127.0.0.1) |
            | `TASK27_COURSE_DIR` | `~/Lemana/lmru--qacc--performance-qa-course` | папка курса |
            | `TASK27_NUM_CTX` | `8192` | окно контекста модели |
            | `TASK27_TOP_K` | `5` | сколько фрагментов курса давать модели |
            | `TASK27_THINK` | `0` | включён ли «Думать» по умолчанию |
            | `EMBEDDING_MODEL` | авто | embedding-модель в Ollama (иначе ищется сама, иначе лексический поиск) |
            | `OLLAMA_HOST` | `http://localhost:11434` | адрес Ollama |

            ## Почему это работает без облака

            - Единственный сетевой собеседник сервера — Ollama на `localhost:11434` (`OllamaClient`): ответы, тест и
              эмбеддинги идут туда. Вызова `LlmClient.fromEnv()` нет, ключ из `.env` не читается.
            - Веб-сервер слушает только `127.0.0.1`; POST с чужого сайта (заголовок `Origin`) отклоняется.
            - Страница не грузит ничего из интернета: Markdown-рендер `marked` лежит в ресурсах, шрифты — системные PT Serif / PT Sans.
            - В шапке приложения видно, какая модель и где отвечает; если Ollama остановить — интерфейс сразу это покажет.

            ## Архитектура

            ```
            браузер ──HTTP──► Task27 (JDK HttpServer, 127.0.0.1:8027)
                                 │  /api/ask · /api/explain · /api/quiz   → NDJSON-поток событий
                                 ▼
                              StudyAssistant ── RAG ──► RagIndex / Chunker / Embedder (неделя 5)
                                 │                          ▲ task27-course-index.json
                                 ▼                          │
                              OllamaClient ──► Ollama localhost:11434 ──► qwen3:8b (+ bge-m3, если есть)
                                 ▲
                              CourseLibrary ── папка курса (Markdown + картинки)
            ```
            """;
}
