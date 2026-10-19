package ru.lemanapro.aiadventchallenge.week7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Day 35: AI-наставник курса НТ (lmru--qacc--performance-qa-course) — три реальные задачи преподавателя:
 *
 *   review(задание, код студента)  — проверка домашнего задания: требования задания разбираются КОДОМ из файла курса
 *                                    (R1…Rn), LLM оценивает каждое по коду студента и обязана привести цитату-доказательство;
 *                                    цитата проверяется в файлах студента, «выполнено» без подтверждённой цитаты понижается,
 *                                    вердикт считается кодом, а не берётся у модели;
 *   hint(задание, вопрос, код?)    — подсказка 1–3 уровня без готового решения: фрагменты кода ≤ 3 строк проверяются,
 *                                    ссылка на раздел урока должна быть реальным заголовком урока;
 *   generateTask(урок, уровень)    — новое задание в формате курса (Чему научимся / Требования / Критерии), только с
 *                                    эндпоинтами стенда, которые есть в курсе, не дубль существующего; слабые места студента
 *                                    из прошлых проверок становятся фокусом задачи.
 * История (проверки, подсказки, задачи) — mentor-output/progress.json; отчёты и задачи — mentor-output/.
 */
public final class CourseMentor {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path OUT = Path.of("mentor-output");
    static final int MAX_LESSON_CHARS = 10_000;
    static final int MAX_CODE_CHARS = 40_000;
    static final int MAX_HINT_CODE_LINES = 3;
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Pattern ENDPOINT = Pattern.compile("/api/v1/[A-Za-z0-9_/#{}.-]+");

    private final ProjectFiles course;
    private final LlmClient.Config cfg;
    private final HttpClient http = LlmClient.newHttpClient();
    private final String student;

    public CourseMentor(Path courseRoot, LlmClient.Config cfg, String student) {
        this.course = new ProjectFiles(courseRoot, false);
        this.cfg = cfg;
        this.student = student;
    }

    public Path courseRoot() {
        return course.root();
    }

    public String model() {
        return cfg == null ? "—" : cfg.model();
    }

    // ================================================================ course structure (no LLM)

    /** optional = «(дополнительно)» step: shown and graded, but not needed for «зачтено». */
    public record Requirement(String id, String text, boolean optional) {
        Requirement(String id, String text) {
            this(id, text, text.matches("(?is).*\\(дополнительно\\).*"));
        }
    }

    /**
     * kind: "practice" (code), "theory" (answer as text). text = what the student sees (no reference answers);
     * reference = reference answers / «для проверяющего» blocks of the course — for grading only, never for hints.
     */
    public record Assignment(String path, String title, String lessonPath, String kind, List<String> goals, List<String> preconditions,
                             List<Requirement> requirements, List<String> criteria, String text, String reference) {
        public boolean practice() {
            return kind.equals("practice");
        }
    }

    /** Files of the course that are assignments: «… Задание …» / «… Задания …» inside a numbered module. */
    public List<String> assignmentPaths() throws IOException {
        return course.list("*.md").stream().map(ProjectFiles.FileInfo::path)
                .filter(p -> p.matches("\\d\\d\\. [^/]+/[^/]*Задани[ея][^/]*\\.md")).toList();
    }

    public List<String> lessonPaths() throws IOException {
        return course.list("*.md").stream().map(ProjectFiles.FileInfo::path)
                .filter(p -> p.matches("\\d\\d\\. [^/]+/\\d+\\. [^/]+\\.md") && !p.matches(".*Задани[ея].*")).toList();
    }

    /** "12.1", "02/12.1", "проверки и ассерты" or a path → the assignment file. */
    public String resolveAssignment(String query) throws IOException {
        return resolve(query, assignmentPaths(), "задание");
    }

    public String resolveLesson(String query) throws IOException {
        List<String> all = new ArrayList<>(lessonPaths());
        all.addAll(assignmentPaths());
        return resolve(query, all, "урок");
    }

    private static String resolve(String query, List<String> paths, String what) throws IOException {
        String q = query.strip();
        if (paths.contains(q)) return q;
        Matcher num = Pattern.compile("^(?:(\\d\\d)\\s*/\\s*)?(\\d+(?:\\.\\d+)?)$").matcher(q);
        List<String> hits = new ArrayList<>();
        for (String p : paths) {
            String file = p.substring(p.indexOf('/') + 1);
            if (num.matches()) {
                boolean module = num.group(1) == null || p.startsWith(num.group(1) + ".");
                if (module && file.startsWith(num.group(2) + " ") || module && file.startsWith(num.group(2) + ". ")) hits.add(p);
            } else if (norm(p).contains(norm(q))) {
                hits.add(p);
            }
        }
        if (hits.size() == 1) return hits.getFirst();
        if (hits.isEmpty()) throw new IOException(what + " «" + query + "» не найдено в курсе");
        throw new IOException(what + " «" + query + "» неоднозначно: " + String.join("; ", hits) + " — уточните модуль, например 02/" + q);
    }

    /**
     * Parses the assignment file into gradable requirements R1…Rn:
     *   steps — «## / ### Шаг N», «## Задание N», «## Часть N» sections: one requirement per top-level numbered item of the
     *           section, or one per section when it has none («Шаг 8 (дополнительно)» → optional);
     *   else «Требования» — numbered + bulleted items + paragraphs that demand something (02/12.1);
     *   else «Критерии …» items.
     * When steps exist, «Требования» are preconditions («стенд развёрнут»), passed as context, not graded.
     * Reference answers ({% cut %}, <details>, «Ответы для самопроверки», «Для проверяющего») are cut from the student-facing
     * text and kept for the reviewer.
     */
    public Assignment assignment(String path) throws IOException {
        String raw = course.read(path);
        StringBuilder ref = new StringBuilder();
        String text = raw.replaceAll("(?s)<!-- nav:start -->.*?<!-- nav:end -->", "");
        text = cut(text, Pattern.compile("(?s)\\{%\\s*cut[^%]*%}(.*?)\\{%\\s*endcut\\s*%}"), ref);
        text = cut(text, Pattern.compile("(?s)<details>(.*?)</details>"), ref);
        text = cut(text, Pattern.compile("(?ms)^\\*\\*Ответы?\\*\\*\\s*\\n(.*?)(?=^#{2,3} |\\z)"), ref);
        text = cut(text, Pattern.compile("(?ms)^## (?:Ответы для самопроверки|Для проверяющего[^\\n]*)\\n(.*?)(?=^## |\\z)"), ref);
        String title = raw.lines().filter(l -> l.startsWith("# ")).findFirst().orElse(path).replaceFirst("^#\\s+", "");
        Matcher lm = Pattern.compile("\\*\\*К уроку:\\*\\*\\s*\\[[^\\]]*]\\(<?([^)>]+)>?\\)").matcher(raw);
        String lesson = null;
        if (lm.find()) lesson = Path.of(ProjectFiles.parent(path)).resolve(lm.group(1)).normalize().toString().replace('\\', '/');
        if (lesson == null || !course.exists(lesson)) lesson = parentLesson(path);
        Map<String, String> sections = sections(text);
        List<String> goals = items(sections.getOrDefault("Чему научимся по итогу прохождения урока", ""));
        List<String> criteria = items(firstSection(sections, "Критерии успешного прохождения", "Критерии готовности"));
        List<Requirement> reqs = new ArrayList<>();
        int n = 1;
        for (String[] step : steps(text)) {
            List<String> top = topItems(step[1]);
            // «Шаг N» of a practice task = one gradable unit; «Задание N» / «Часть N» of a theory task = one per question
            if (top.isEmpty() || step[0].startsWith("Шаг")) {
                String body = step[1].replaceAll("(?s)```.*?```", " ").replaceAll("(?m)^\\|.*$", "").replaceAll("(?m)^#+.*$", "")
                        .replaceAll("\\s+", " ").strip();
                reqs.add(new Requirement("R" + n++, step[0] + ": " + ProjectFiles.clip(body, 350)));
            } else {
                for (String it : top) reqs.add(new Requirement("R" + n++, step[0] + " — " + it, step[0].matches("(?i).*\\(дополнительно\\).*")));
            }
        }
        String reqText = firstSection(sections, "Требования");
        List<String> pre = new ArrayList<>();
        for (String block : reqText.split("\\n\\s*\\n")) {
            String b = block.strip();
            if (b.isEmpty()) continue;
            List<String> list = items(b);
            if (!list.isEmpty()) pre.addAll(list);
            else if (b.matches("(?s).*(нужно|необходимо|должн|обоснова|добавля).*") && !b.startsWith("|")) pre.add(b.replaceAll("\\s+", " "));
        }
        if (reqs.isEmpty()) {
            for (String p : pre) reqs.add(new Requirement("R" + n++, p));
            pre = List.of();
        }
        if (reqs.isEmpty()) for (String c : criteria) reqs.add(new Requirement("R" + n++, c));
        String kind = sections.containsKey("Требования") || text.contains("```java") || raw.contains("Шаги задания") ? "practice" : "theory";
        return new Assignment(path, title, lesson, kind, goals, pre, reqs, criteria, text.strip(), ref.toString().strip());
    }

    private static String cut(String text, Pattern p, StringBuilder ref) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            ref.append(m.group(1).strip()).append("\n\n");
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** «## / ### Шаг|Задание|Часть N …» → [title, body up to the next heading of the same or higher level]. */
    static List<String[]> steps(String text) {
        List<String[]> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        Pattern h = Pattern.compile("^(#{2,3})\\s+((?:Шаг|Задание|Часть)\\s+\\d+.*)$");
        boolean fence = false;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().startsWith("```")) fence = !fence;
            Matcher m = h.matcher(lines[i]);
            if (fence || !m.matches()) continue;
            int level = m.group(1).length();
            StringBuilder body = new StringBuilder();
            boolean f2 = false;
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].strip().startsWith("```")) f2 = !f2;
                Matcher hh = Pattern.compile("^(#{1,6})\\s").matcher(lines[j]);
                if (!f2 && hh.find() && hh.group(1).length() <= level) break;
                body.append(lines[j]).append('\n');
            }
            out.add(new String[]{m.group(2).replace("\\", "").strip(), body.toString()});
        }
        return out;
    }

    private String parentLesson(String assignmentPath) throws IOException {
        String file = assignmentPath.substring(assignmentPath.indexOf('/') + 1);
        Matcher m = Pattern.compile("^(\\d+)\\.").matcher(file);
        if (!m.find()) return null;
        String dir = ProjectFiles.parent(assignmentPath);
        for (String p : lessonPaths()) if (p.startsWith(dir + "/" + m.group(1) + ". ")) return p;
        return null;
    }

    /** "## Заголовок" → text (nav blocks removed). */
    static Map<String, String> sections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String clean = text.replaceAll("(?s)<!-- nav:start -->.*?<!-- nav:end -->", "");
        String cur = "";
        StringBuilder sb = new StringBuilder();
        boolean fence = false;
        for (String line : clean.split("\n", -1)) {
            if (line.strip().startsWith("```")) fence = !fence;
            if (!fence && line.startsWith("## ")) {
                out.put(cur, sb.toString());
                cur = line.substring(3).strip().replace("\\", "");
                sb.setLength(0);
            } else {
                sb.append(line).append('\n');
            }
        }
        out.put(cur, sb.toString());
        return out;
    }

    private static String firstSection(Map<String, String> s, String... names) {
        for (String n : names) if (s.containsKey(n)) return s.get(n);
        return "";
    }

    /** Top-level numbered items only («1. **Описание.** …»), nested bullets folded into their item; tables skipped. */
    static List<String> topItems(String block) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        boolean fence = false;
        for (String line : block.split("\n")) {
            if (line.strip().startsWith("```")) fence = !fence;
            if (fence) continue;
            Matcher m = Pattern.compile("^(\\d+)\\.\\s+(.*)").matcher(line);
            if (m.matches()) {
                if (cur != null) out.add(ProjectFiles.clip(cur.toString().strip(), 400));
                cur = new StringBuilder(m.group(2));
            } else if (cur != null && line.matches("^\\s{2,}[*-]\\s+.*")) {
                cur.append("; ").append(line.strip().replaceFirst("^[*-]\\s+", ""));
            } else if (line.startsWith("#")) {
                if (cur != null) out.add(ProjectFiles.clip(cur.toString().strip(), 400));
                cur = null;
            }
        }
        if (cur != null) out.add(ProjectFiles.clip(cur.toString().strip(), 400));
        return out;
    }

    /** Numbered and bulleted list items (continuation lines joined). */
    static List<String> items(String block) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String line : block.split("\n")) {
            Matcher m = Pattern.compile("^\\s{0,3}(?:\\d+\\.|[*-])\\s+(.*)").matcher(line);
            if (m.matches()) {
                if (cur != null) out.add(cur.toString().strip());
                cur = new StringBuilder(m.group(1));
            } else if (cur != null && !line.isBlank() && line.startsWith("   ")) {
                cur.append(' ').append(line.strip());
            } else if (cur != null && line.isBlank()) {
                out.add(cur.toString().strip());
                cur = null;
            }
        }
        if (cur != null) out.add(cur.toString().strip());
        return out;
    }

    /** Lesson text without nav blocks, trimmed to MAX_LESSON_CHARS (sections with code are kept first). */
    String lessonExcerpt(String lessonPath) throws IOException {
        if (lessonPath == null) return "";
        String text = course.read(lessonPath).replaceAll("(?s)<!-- nav:start -->.*?<!-- nav:end -->", "");
        if (text.length() <= MAX_LESSON_CHARS) return text;
        StringBuilder sb = new StringBuilder();
        for (var e : sections(text).entrySet()) {
            String part = (e.getKey().isEmpty() ? "" : "## " + e.getKey() + "\n") + e.getValue();
            if (sb.length() + part.length() > MAX_LESSON_CHARS) {
                sb.append("## ").append(e.getKey()).append("\n(раздел сокращён)\n\n");
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    List<String> lessonHeadings(String lessonPath) throws IOException {
        if (lessonPath == null) return List.of();
        return sections(course.read(lessonPath)).keySet().stream().filter(k -> !k.isEmpty()).toList();
    }

    /** Endpoints of the course stand mentioned anywhere in the course (normalized, ≥ 3 path segments). */
    public Set<String> knownEndpoints() throws IOException {
        Set<String> out = new TreeSet<>();
        for (ProjectFiles.FileInfo f : course.list("*.md")) {
            Matcher m = ENDPOINT.matcher(course.read(f.path()));
            while (m.find()) {
                String e = normEndpoint(m.group());
                if (e.split("/").length >= 4) out.add(e);
            }
        }
        return out;
    }

    static String normEndpoint(String e) {
        String s = e.replaceAll("#\\{[^}]*}|\\{[^}]*}", "").replaceAll("/\\d+", "").replaceAll("/+", "/").replaceAll("[/.]+$", "");
        int q = s.indexOf('?');
        return q < 0 ? s : s.substring(0, q);
    }

    // ================================================================ student code

    public record SourceFile(String name, String text) {
    }

    /** All *.java / *.kt / *.scala / *.conf / *.csv / *.properties under a folder (or one file). */
    public static List<SourceFile> loadSolution(Path dir) throws IOException {
        List<SourceFile> out = new ArrayList<>();
        if (Files.isRegularFile(dir)) {
            out.add(new SourceFile(dir.getFileName().toString(), Files.readString(dir, StandardCharsets.UTF_8)));
            return out;
        }
        if (!Files.isDirectory(dir)) throw new IOException("нет папки с решением: " + dir);
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (rel.matches("(^|.*/)(target|build|\\.git|\\.idea|node_modules)/.*")) continue;
                if (!rel.matches("(?i).*\\.(java|kt|scala|conf|properties|csv|md|txt)$")) continue;
                out.add(new SourceFile(rel, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        if (out.isEmpty()) throw new IOException("в " + dir + " нет файлов решения (*.java, *.kt, *.scala, *.md, *.txt)");
        return out;
    }

    /** "// FILE: Name.java" separators in pasted text → files (no separator = one file Solution.java). */
    public static List<SourceFile> parsePasted(String text) {
        List<SourceFile> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^//\\s*FILE:\\s*(\\S+)\\s*$").matcher(text);
        List<int[]> marks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            names.add(m.group(1));
        }
        if (marks.isEmpty()) return List.of(new SourceFile(looksLikeCode(text) ? "Solution.java" : "Ответ.md", text));
        for (int i = 0; i < marks.size(); i++) {
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
            out.add(new SourceFile(names.get(i), text.substring(marks.get(i)[1], end).strip() + "\n"));
        }
        return out;
    }

    static boolean looksLikeCode(String text) {
        return Pattern.compile("(?m)^\\s*(package |import |public |class |private |\\.check\\(|setUp\\()").matcher(text).find();
    }

    static String numbered(List<SourceFile> files) {
        StringBuilder sb = new StringBuilder();
        for (SourceFile f : files) {
            sb.append("=== ").append(f.name()).append(" ===\n");
            String[] lines = f.text().split("\n", -1);
            for (int i = 0; i < lines.length; i++) sb.append(String.format("%4d| ", i + 1)).append(lines[i]).append('\n');
            if (sb.length() > MAX_CODE_CHARS) {
                sb.append("… (код обрезан)\n");
                break;
            }
        }
        return sb.toString();
    }

    // ================================================================ 1. homework review

    public record Evidence(String file, int line, String quote, boolean verified) {
    }

    public record ReqResult(String id, String text, String status, String modelStatus, Evidence evidence, String comment) {
    }

    public record Issue(String file, int line, String problem, String hint) {
    }

    public record Review(String assignmentPath, String title, List<String> files, String verdict, int met, int total,
                         List<ReqResult> requirements, List<Issue> issues, List<String> nextSteps, String summary,
                         List<String> notes, long millis, String error) {
        public boolean passed() {
            return "зачтено".equals(verdict);
        }
    }

    static final String REVIEW_PROMPT = """
            Ты — преподаватель курса нагрузочного тестирования на Gatling (Java) и проверяешь домашнее задание студента.
            Решение — код (практика) или текстовый ответ (теоретическое задание: таблицы, расчёты, рассуждения).
            Оцени КАЖДОЕ требование задания (R1…Rn) по решению студента:
              met     — выполнено полностью и по существу верно; обязательно приведи доказательство: файл, номер строки и ДОСЛОВНУЮ цитату строки решения;
              partial — выполнено частично (скажи, чего не хватает);
              missing — нет в коде.
            Требования, которые проверяются только прогоном (поведение отчёта), оценивай по тому, обеспечивает ли его код.
            В текстовом ответе проверяй и содержание: расчёт верен, обоснование опирается на урок, формат (например таблица) соблюдён.
            Не выдумывай код, которого нет. Комментарии — коротко, по-русски, на «ты», доброжелательно.
            Ошибки (issues) — только реальные проблемы в коде студента, с файлом и строкой. В hint не пиши готовый код —
            подскажи, какую конструкцию Gatling использовать и где об этом сказано в уроке.
            Ответ — ТОЛЬКО JSON:
            {"summary": "2–3 предложения", "requirements": [{"id": "R1", "status": "met|partial|missing",
              "evidence": {"file": "...", "line": 12, "quote": "дословная строка кода"}, "comment": "..."}],
             "issues": [{"file": "...", "line": 10, "problem": "...", "hint": "..."}], "next_steps": ["..."]}""";

    public Review review(String assignmentQuery, List<SourceFile> files) throws Exception {
        long t0 = System.nanoTime();
        Assignment a = assignment(resolveAssignment(assignmentQuery));
        List<String> notes = new ArrayList<>();
        if (a.requirements().isEmpty()) throw new IOException("в задании " + a.path() + " не нашлось ни требований, ни критериев — проверять не по чему");
        if (files.stream().allMatch(f -> f.text().isBlank())) throw new IOException("решение пустое — вставь код или текст ответа");
        StringBuilder user = new StringBuilder("# Задание: ").append(a.title()).append(" (").append(a.practice() ? "практика" : "теория").append(")\n\n");
        if (!a.preconditions().isEmpty()) {
            user.append("## Предусловия (не оцениваются)\n");
            a.preconditions().forEach(p -> user.append("- ").append(p).append('\n'));
            user.append('\n');
        }
        user.append("## Требования для оценки\n");
        a.requirements().forEach(r -> user.append(r.id()).append(". ").append(r.text()).append(r.optional() ? " (дополнительно)" : "").append('\n'));
        user.append("\n## Полный текст задания\n").append(ProjectFiles.clip(a.text(), 8000)).append('\n');
        if (!a.reference().isEmpty()) {
            user.append("\n## Эталонные ответы курса (только для проверяющего: сверяй с ними расчёты и выводы, не пересказывай целиком)\n")
                    .append(ProjectFiles.clip(a.reference(), 6000)).append('\n');
        }
        user.append("\n## Критерии успешного прохождения\n");
        a.criteria().forEach(c -> user.append("- ").append(c).append('\n'));
        user.append("\n## Урок (для контекста: как это делается в курсе)\n").append(lessonExcerpt(a.lessonPath()))
                .append("\n\n## Решение студента (номера строк — для ссылок)\n").append(numbered(files));
        JsonNode j;
        try {
            j = askJson(REVIEW_PROMPT, user.toString(), 0.1);
        } catch (Exception e) {
            return new Review(a.path(), a.title(), names(files), "не проверено", 0, a.requirements().size(), List.of(), List.of(),
                    List.of(), "", notes, ms(t0), e.getMessage());
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode r : j.path("requirements")) byId.putIfAbsent(r.path("id").asText().strip().toUpperCase(Locale.ROOT), r);
        List<ReqResult> results = new ArrayList<>();
        int met = 0;
        for (Requirement req : a.requirements()) {
            JsonNode r = byId.get(req.id());
            if (r == null) {
                notes.add(req.id() + ": модель не оценила требование — считается невыполненным");
                results.add(new ReqResult(req.id(), req.text(), "missing", "—", null, "не оценено"));
                continue;
            }
            String model = r.path("status").asText("missing").strip().toLowerCase(Locale.ROOT);
            if (!Set.of("met", "partial", "missing").contains(model)) model = "missing";
            Evidence ev = verifyEvidence(files, r.path("evidence"));
            String status = model;
            if (model.equals("met") && (ev == null || !ev.verified())) {
                status = "partial";
                notes.add(req.id() + ": «выполнено» без подтверждённой цитаты из кода → понижено до «частично»");
            }
            if (status.equals("met")) met++;
            results.add(new ReqResult(req.id(), req.text(), status, model, ev, r.path("comment").asText("")));
        }
        List<Issue> issues = new ArrayList<>();
        for (JsonNode i : j.path("issues")) {
            String file = i.path("file").asText("");
            int line = i.path("line").asInt(0);
            SourceFile sf = find(files, file);
            if (sf == null) {
                notes.add("замечание к несуществующему файлу «" + file + "» отброшено");
                continue;
            }
            int lines = sf.text().split("\n", -1).length;
            issues.add(new Issue(sf.name(), line >= 1 && line <= lines ? line : 0, i.path("problem").asText(""), noLongCode(i.path("hint").asText(""))));
        }
        List<String> next = new ArrayList<>();
        j.path("next_steps").forEach(x -> next.add(noLongCode(x.asText())));
        // verdict is computed here, not taken from the model
        long required = a.requirements().stream().filter(q -> !q.optional()).count();
        long requiredMet = results.stream().filter(q -> q.status().equals("met"))
                .filter(q -> a.requirements().stream().anyMatch(x -> x.id().equals(q.id()) && !x.optional())).count();
        String verdict = requiredMet == required ? "зачтено" : "нужна доработка";
        Review rv = new Review(a.path(), a.title(), names(files), verdict, met, a.requirements().size(), results, issues, next,
                j.path("summary").asText(""), notes, ms(t0), null);
        record(rv);
        return rv;
    }

    /** The quote must be (whitespace-insensitively) in the cited file; the line is re-located if the model got it wrong. */
    static Evidence verifyEvidence(List<SourceFile> files, JsonNode ev) {
        if (ev == null || ev.isMissingNode() || ev.isNull()) return null;
        String quote = ev.path("quote").asText("").strip().replaceFirst("^\\d+\\|\\s?", "");
        String fileName = ev.path("file").asText("");
        int line = ev.path("line").asInt(0);
        if (quote.length() < 4) return new Evidence(fileName, line, quote, false);
        String q = squash(quote);
        List<SourceFile> order = new ArrayList<>();
        SourceFile named = find(files, fileName);
        if (named != null) order.add(named);
        files.stream().filter(f -> f != named).forEach(order::add);
        for (SourceFile f : order) {
            String[] lines = f.text().split("\n", -1);
            if (line >= 1 && line <= lines.length && squash(lines[line - 1]).contains(q)) return new Evidence(f.name(), line, quote, true);
            for (int i = 0; i < lines.length; i++) {
                if (!squash(lines[i]).isEmpty() && squash(lines[i]).contains(q)) return new Evidence(f.name(), i + 1, quote, true);
            }
            if (squash(f.text()).contains(q)) return new Evidence(f.name(), line, quote, true); // multi-line quote
        }
        return new Evidence(fileName, line, quote, false);
    }

    static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    static SourceFile find(List<SourceFile> files, String name) {
        if (name == null || name.isBlank()) return null;
        for (SourceFile f : files) if (f.name().equals(name)) return f;
        for (SourceFile f : files) if (f.name().endsWith("/" + name) || name.endsWith("/" + f.name()) || name.endsWith(f.name())) return f;
        return null;
    }

    public String reviewMarkdown(Review r) {
        StringBuilder sb = new StringBuilder("# Проверка ДЗ: ").append(r.title()).append("\n\n")
                .append("> ").append(now()).append(" · студент `").append(student).append("` · задание `").append(r.assignmentPath())
                .append("` · файлы: ").append(String.join(", ", r.files())).append(" · модель `").append(model()).append("`\n\n");
        if (r.error() != null) return sb.append("⚠ Проверка не выполнена: ").append(r.error()).append('\n').toString();
        sb.append("## Итог: ").append(r.passed() ? "✅ зачтено" : "🔧 нужна доработка").append(" — выполнено ")
                .append(r.met()).append(" из ").append(r.total()).append(" требований\n\n").append(r.summary()).append("\n\n")
                .append("| # | Требование | Статус | Где в решении | Комментарий |\n|---|---|---|---|---|\n");
        for (ReqResult q : r.requirements()) {
            String where = q.evidence() == null ? "—" : (q.evidence().verified() ? "" : "⚠ не найдено: ") + "`" + q.evidence().file()
                    + (q.evidence().line() > 0 ? ":" + q.evidence().line() : "") + "` " + "`" + ProjectFiles.clip(q.evidence().quote(), 70).replace("|", "\\|") + "`";
            sb.append("| ").append(q.id()).append(q.optional() ? " (доп.)" : "").append(" | ").append(ProjectFiles.clip(q.text(), 110).replace("|", "\\|")).append(" | ")
                    .append(icon(q.status())).append(" | ").append(where).append(" | ").append(q.comment().replace("|", "\\|").replace("\n", " ")).append(" |\n");
        }
        if (!r.issues().isEmpty()) {
            sb.append("\n## Замечания к решению\n\n");
            r.issues().forEach(i -> sb.append("- `").append(i.file()).append(i.line() > 0 ? ":" + i.line() : "").append("` — ")
                    .append(i.problem()).append(i.hint().isBlank() ? "" : " *Подсказка:* " + i.hint()).append('\n'));
        }
        if (!r.nextSteps().isEmpty()) {
            sb.append("\n## Что сделать дальше\n\n");
            r.nextSteps().forEach(s -> sb.append("1. ").append(s).append('\n'));
        }
        if (!r.notes().isEmpty()) {
            sb.append("\n<details><summary>Проверки ответа модели кодом</summary>\n\n");
            r.notes().forEach(n -> sb.append("- ").append(n).append('\n'));
            sb.append("\n</details>\n");
        }
        return sb.toString();
    }

    static String icon(String status) {
        return switch (status) {
            case "met" -> "✅ выполнено";
            case "partial" -> "🟡 частично";
            default -> "❌ нет";
        };
    }

    // ================================================================ 2. hints

    public record Hint(String assignmentPath, int level, String question, String text, String lessonPath, String section,
                       boolean sectionVerified, int maxCodeLines, boolean repaired, Set<String> unknownCalls, long millis, String error) {
    }

    static final String HINT_PROMPT = """
            Ты — наставник курса нагрузочного тестирования на Gatling (Java). Студент делает задание и просит подсказку.
            НЕЛЬЗЯ давать готовое решение: никаких полных методов, классов или цепочек запросов. Фрагмент кода — максимум %d строки,
            только чтобы показать синтаксис одной конструкции. Уровень подсказки: %d
              1 — наводящий вопрос и направление (что проверить, о чём подумать), без кода;
              2 — какая конструкция Gatling нужна и почему, со ссылкой на раздел урока;
              3 — план решения по шагам (псевдокод словами) + не более одного короткого фрагмента синтаксиса.
            Если в коде студента есть ошибка, связанная с вопросом, — укажи файл и строку, но не исправляй за него.
            Последней строкой обязательно: «Где посмотреть: <точное название раздела урока из списка>».
            Разделы урока: %s""";

    public Hint hint(String assignmentQuery, String question, List<SourceFile> files, int level) throws Exception {
        long t0 = System.nanoTime();
        int lvl = Math.max(1, Math.min(3, level));
        Assignment a = assignment(resolveAssignment(assignmentQuery));
        List<String> headings = lessonHeadings(a.lessonPath());
        String system = HINT_PROMPT.formatted(MAX_HINT_CODE_LINES, lvl, String.join(" | ", headings));
        StringBuilder user = new StringBuilder("# Задание (без эталонных ответов — их студенту не раскрывать)\n").append(a.text())
                .append("\n\n# Урок\n").append(lessonExcerpt(a.lessonPath()));
        if (files != null && !files.isEmpty()) user.append("\n\n# Текущий код студента\n").append(numbered(files));
        user.append("\n\n# Вопрос студента\n").append(question);
        List<Map<String, String>> msgs = new ArrayList<>(List.of(LlmClient.message("system", system), LlmClient.message("user", user.toString())));
        String text;
        try {
            text = chat(msgs, 0.3);
        } catch (Exception e) {
            return new Hint(a.path(), lvl, question, "", a.lessonPath(), "", false, 0, false, Set.of(), ms(t0), e.getMessage());
        }
        boolean repaired = false;
        Set<String> unknown = unknownCalls(text, files);
        if (maxCodeBlockLines(text) > MAX_HINT_CODE_LINES || sectionOf(text, headings) == null || !unknown.isEmpty()) {
            msgs.add(LlmClient.message("assistant", text));
            msgs.add(LlmClient.message("user", "Перепиши подсказку: " + (maxCodeBlockLines(text) > MAX_HINT_CODE_LINES
                    ? "в ней фрагмент кода длиннее " + MAX_HINT_CODE_LINES + " строк — это уже готовое решение; оставь только направление и синтаксис одной конструкции. " : "")
                    + (unknown.isEmpty() ? "" : "в коде есть вызовы, которых нет ни в Gatling Java DSL курса, ни в коде студента: " + String.join(", ", unknown)
                    + " — используй только конструкции из урока (например doIfOrElse, checkIf, status().in). ")
                    + "Последняя строка — «Где посмотреть: …» с точным названием раздела из списка: " + String.join(" | ", headings)));
            try {
                text = chat(msgs, 0.2);
                repaired = true;
            } catch (Exception ignored) {
                // keep the first version
            }
        }
        if (maxCodeBlockLines(text) > MAX_HINT_CODE_LINES) text = cutLongCode(text);
        String section = sectionOf(text, headings);
        Hint h = new Hint(a.path(), lvl, question, text, a.lessonPath(), section == null ? "" : section, section != null,
                maxCodeBlockLines(text), repaired, unknownCalls(text, files), ms(t0), null);
        recordHint(h);
        return h;
    }

    private Set<String> courseCalls;

    /** Every `name(` used anywhere in the course — the Gatling DSL vocabulary the course teaches. */
    synchronized Set<String> courseCalls() throws IOException {
        if (courseCalls == null) {
            Set<String> out = new TreeSet<>();
            for (ProjectFiles.FileInfo f : course.list("*.md")) {
                Matcher m = CALL.matcher(course.read(f.path()));
                while (m.find()) out.add(m.group(1));
            }
            courseCalls = out;
        }
        return courseCalls;
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "return", "synchronized", "new", "super", "this");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    /** Calls in the code blocks of a hint that are neither in the course nor in the student's code (invented API). */
    Set<String> unknownCalls(String text, List<SourceFile> files) {
        Set<String> known;
        try {
            known = new TreeSet<>(courseCalls());
        } catch (IOException e) {
            return Set.of();
        }
        if (files != null) for (SourceFile f : files) {
            Matcher m = CALL.matcher(f.text());
            while (m.find()) known.add(m.group(1));
        }
        Set<String> out = new TreeSet<>();
        Matcher b = Pattern.compile("(?s)```[^\\n]*\\n(.*?)```").matcher(text);
        while (b.find()) {
            String code = b.group(1);
            Matcher m = CALL.matcher(code);
            while (m.find()) {
                String name = m.group(1);
                if (JAVA_KEYWORDS.contains(name) || known.contains(name)) continue;
                // obj.method() — a method of the student's own class (may be one the hint proposes to write); only bare and
                // chained DSL calls — name( / ).name( / newline .name( — must be real Gatling API from the course
                int i = m.start(1) - 1;
                boolean onObject = i >= 1 && code.charAt(i) == '.' && Character.isJavaIdentifierPart(code.charAt(i - 1));
                if (!onObject) out.add(name);
            }
            // a Java keyword used like a DSL call — if("#{flag}")… — is an invented Gatling API (the DSL has doIf / doIfOrElse)
            Matcher k = Pattern.compile("\\b(if|switch|while|for)\\s*\\(\\s*\"").matcher(b.group(1));
            while (k.find()) out.add(k.group(1) + "(\"…\")");
        }
        return out;
    }

    static int maxCodeBlockLines(String text) {
        int max = 0;
        Matcher m = Pattern.compile("(?s)```[^\\n]*\\n(.*?)```").matcher(text);
        while (m.find()) max = Math.max(max, (int) m.group(1).lines().filter(l -> !l.isBlank()).count());
        return max;
    }

    static String cutLongCode(String text) {
        Matcher m = Pattern.compile("(?s)```[^\\n]*\\n(.*?)```").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long lines = m.group(1).lines().filter(l -> !l.isBlank()).count();
            m.appendReplacement(sb, Matcher.quoteReplacement(lines > MAX_HINT_CODE_LINES
                    ? "*(фрагмент кода скрыт наставником — попробуй написать его сам по подсказке выше)*" : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The lesson heading named in «Где посмотреть: …», or null. */
    static String sectionOf(String text, List<String> headings) {
        Matcher m = Pattern.compile("(?i)Где посмотреть:\\s*(.+)").matcher(text);
        String tail = null;
        while (m.find()) tail = m.group(1);
        if (tail == null) return null;
        String t = norm(tail);
        String best = null;
        for (String h : headings) if (t.contains(norm(h)) && (best == null || h.length() > best.length())) best = h;
        return best;
    }

    static String noLongCode(String s) {
        return maxCodeBlockLines(s) > MAX_HINT_CODE_LINES ? cutLongCode(s) : s;
    }

    // ================================================================ 3. task generator

    public record GeneratedTask(String lessonPath, String level, String focus, String markdown, Path file, List<String> problems,
                                List<String> endpoints, double maxSimilarity, String similarTo, boolean repaired, long millis, String error) {
        public boolean valid() {
            return error == null && problems.isEmpty();
        }
    }

    static final String TASK_PROMPT = """
            Ты — автор курса нагрузочного тестирования на Gatling (Java). Составь НОВОЕ практическое задание к уроку для студента.
            Оно должно отличаться от существующих заданий курса, выполняться на стенде курса (банковское демо-приложение) и
            использовать ТОЛЬКО эндпоинты из списка — других API у стенда нет. Уровень: %s.
            Формат — Markdown, строго такие разделы:
            # Задание: <название>
            ## Чему научимся по итогу прохождения урока   (2–3 пункта списком)
            ## Требования                                 (3–6 нумерованных пунктов, конкретно и проверяемо)
            ## Критерии успешного прохождения             (3–5 пунктов списком: что видно в коде / отчёте Gatling)
            ## Подсказки                                  (2–3 пункта: куда смотреть в уроке, без готового кода)
            Готового решения и кода длиннее 3 строк не давай. Пиши по-русски, в стиле примера.""";

    public GeneratedTask generateTask(String lessonQuery, String level, String extraFocus) throws Exception {
        long t0 = System.nanoTime();
        String lessonPath = resolveLesson(lessonQuery);
        String lvl = level == null || level.isBlank() ? "средний" : level;
        List<String> weak = weakSpots();
        String focus = extraFocus != null && !extraFocus.isBlank() ? extraFocus
                : weak.isEmpty() ? "" : "слабые места студента по прошлым проверкам: " + String.join("; ", weak);
        Set<String> endpoints = knownEndpoints();
        String example = exampleAssignment(lessonPath);
        List<String> existing = assignmentPaths();
        StringBuilder user = new StringBuilder("# Урок\n").append(lessonExcerpt(lessonPath))
                .append("\n\n# Эндпоинты стенда (других нет)\n").append(String.join("\n", endpoints))
                .append("\n\n# Существующие задания курса (не повторять)\n").append(String.join("\n", existing.stream().map(CourseMentor::titleOf).toList()))
                .append("\n\n# Пример оформления задания курса\n").append(example);
        if (!focus.isEmpty()) user.append("\n\n# Фокус задания\n").append(focus);
        List<Map<String, String>> msgs = new ArrayList<>(List.of(LlmClient.message("system", TASK_PROMPT.formatted(lvl)),
                LlmClient.message("user", user.toString())));
        String md;
        try {
            md = stripFence(chat(msgs, 0.7));
        } catch (Exception e) {
            return new GeneratedTask(lessonPath, lvl, focus, "", null, List.of(), List.of(), 0, "", false, ms(t0), e.getMessage());
        }
        List<String> problems = validateTask(md, endpoints);
        boolean repaired = false;
        if (!problems.isEmpty()) {
            msgs.add(LlmClient.message("assistant", md));
            msgs.add(LlmClient.message("user", "Исправь задание, сохранив формат. Проблемы: " + String.join("; ", problems)));
            try {
                md = stripFence(chat(msgs, 0.4));
                repaired = true;
                problems = validateTask(md, endpoints);
            } catch (Exception ignored) {
                // keep the first version with its problems
            }
        }
        double maxSim = 0;
        String similar = "";
        Set<String> words = words(md);
        for (String p : existing) {
            double s = jaccard(words, words(course.read(p)));
            if (s > maxSim) {
                maxSim = s;
                similar = p;
            }
        }
        if (maxSim > 0.5) problems = append(problems, "слишком похоже на существующее задание «" + titleOf(similar) + "» (сходство " + String.format("%.2f", maxSim) + ")");
        List<String> used = new ArrayList<>();
        Matcher m = ENDPOINT.matcher(md);
        while (m.find()) used.add(normEndpoint(m.group()));
        Path file = OUT.resolve("tasks").resolve(slug(titleOf(md.lines().filter(l -> l.startsWith("# ")).findFirst().orElse("# задание"))) + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, md + "\n\n---\n\n*Сгенерировано наставником для `" + student + "` к уроку [" + titleOf(lessonPath) + "]("
                + "<" + courseRoot().resolve(lessonPath) + ">), уровень: " + lvl + (focus.isEmpty() ? "" : "; фокус: " + focus) + "*\n", StandardCharsets.UTF_8);
        GeneratedTask t = new GeneratedTask(lessonPath, lvl, focus, md, file, problems, used.stream().distinct().toList(), maxSim, similar, repaired, ms(t0), null);
        recordTask(t);
        return t;
    }

    /** Format and stand checks of a generated task (in code). */
    List<String> validateTask(String md, Set<String> endpoints) {
        List<String> problems = new ArrayList<>();
        Map<String, String> s = sections(md);
        if (md.lines().noneMatch(l -> l.matches("#\\s+Задание:.*"))) problems.add("нет заголовка «# Задание: …»");
        for (String need : List.of("Чему научимся по итогу прохождения урока", "Требования", "Критерии успешного прохождения")) {
            if (!s.containsKey(need)) problems.add("нет раздела «## " + need + "»");
        }
        if (s.containsKey("Требования") && items(s.get("Требования")).size() < 3) problems.add("в «Требованиях» меньше 3 пунктов");
        if (s.containsKey("Критерии успешного прохождения") && items(s.get("Критерии успешного прохождения")).size() < 2) problems.add("критериев меньше 2");
        Matcher m = ENDPOINT.matcher(md);
        Set<String> unknown = new LinkedHashSet<>();
        while (m.find()) {
            String e = normEndpoint(m.group());
            boolean ok = endpoints.stream().anyMatch(k -> k.equals(e) || k.startsWith(e + "/") || e.startsWith(k + "/") || e.startsWith(k));
            if (!ok) unknown.add(e);
        }
        if (!unknown.isEmpty()) problems.add("эндпоинтов нет на стенде курса: " + String.join(", ", unknown));
        if (maxCodeBlockLines(md) > MAX_HINT_CODE_LINES) problems.add("есть код длиннее " + MAX_HINT_CODE_LINES + " строк — это готовое решение");
        return problems;
    }

    /** The lesson's own assignment if it has one (same module, same number), else 02/12.1 as the canonical example. */
    private String exampleAssignment(String lessonPath) throws IOException {
        String dir = ProjectFiles.parent(lessonPath);
        String file = lessonPath.substring(lessonPath.lastIndexOf('/') + 1);
        Matcher m = Pattern.compile("^(\\d+)").matcher(file);
        for (String p : assignmentPaths()) {
            if (m.find(0) && p.startsWith(dir + "/" + m.group(1) + ".") && !p.equals(lessonPath)) return clean(p);
        }
        for (String p : assignmentPaths()) if (p.contains("12.1")) return clean(p);
        return "";
    }

    private String clean(String p) throws IOException {
        return ProjectFiles.clip(course.read(p).replaceAll("(?s)<!-- nav:start -->.*?<!-- nav:end -->", "").strip(), 5000);
    }

    // ================================================================ progress (mentor-output/progress.json)

    Path progressFile() {
        return OUT.resolve("progress.json");
    }

    public ObjectNode progress() {
        try {
            if (Files.exists(progressFile())) {
                JsonNode n = JSON.readTree(progressFile().toFile());
                if (n instanceof ObjectNode o) return o;
            }
        } catch (IOException ignored) {
            // corrupted → start over
        }
        ObjectNode o = JSON.createObjectNode();
        o.put("student", student);
        o.putArray("reviews");
        o.putArray("hints");
        o.putArray("tasks");
        return o;
    }

    private synchronized void save(ObjectNode p) {
        try {
            Files.createDirectories(OUT);
            JSON.writerWithDefaultPrettyPrinter().writeValue(progressFile().toFile(), p);
        } catch (IOException e) {
            System.out.println("⚠ не удалось сохранить прогресс: " + e.getMessage());
        }
    }

    private void record(Review r) {
        ObjectNode p = progress();
        ObjectNode o = ((ArrayNode) p.withArray("reviews")).addObject();
        o.put("at", now());
        o.put("assignment", r.assignmentPath());
        o.put("verdict", r.verdict());
        o.put("met", r.met());
        o.put("total", r.total());
        ArrayNode gaps = o.putArray("gaps");
        r.requirements().stream().filter(q -> !q.status().equals("met")).forEach(q -> gaps.add(q.id() + " " + ProjectFiles.clip(q.text(), 160)));
        save(p);
    }

    private void recordHint(Hint h) {
        ObjectNode p = progress();
        ObjectNode o = ((ArrayNode) p.withArray("hints")).addObject();
        o.put("at", now());
        o.put("assignment", h.assignmentPath());
        o.put("level", h.level());
        o.put("question", h.question());
        save(p);
    }

    private void recordTask(GeneratedTask t) {
        ObjectNode p = progress();
        ObjectNode o = ((ArrayNode) p.withArray("tasks")).addObject();
        o.put("at", now());
        o.put("lesson", t.lessonPath());
        o.put("level", t.level());
        o.put("file", t.file().toString());
        o.put("focus", t.focus());
        save(p);
    }

    /** Gaps of the latest review of every assignment that is not passed yet. */
    public List<String> weakSpots() {
        Map<String, JsonNode> last = new LinkedHashMap<>();
        for (JsonNode r : progress().path("reviews")) last.put(r.path("assignment").asText(), r);
        List<String> out = new ArrayList<>();
        for (JsonNode r : last.values()) {
            if (r.path("verdict").asText().equals("зачтено")) continue;
            for (JsonNode g : r.path("gaps")) out.add(ProjectFiles.clip(g.asText().replaceFirst("^R\\d+ ", ""), 120));
        }
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    public String progressMarkdown() {
        ObjectNode p = progress();
        StringBuilder sb = new StringBuilder("# Прогресс: ").append(student).append("\n\n| Когда | Задание | Итог |\n|---|---|---|\n");
        for (JsonNode r : p.path("reviews")) {
            sb.append("| ").append(r.path("at").asText()).append(" | ").append(titleOf(r.path("assignment").asText())).append(" | ")
                    .append(r.path("verdict").asText()).append(" (").append(r.path("met").asInt()).append("/").append(r.path("total").asInt()).append(") |\n");
        }
        sb.append("\nПодсказок: ").append(p.path("hints").size()).append(", сгенерировано задач: ").append(p.path("tasks").size()).append('\n');
        List<String> weak = weakSpots();
        if (!weak.isEmpty()) {
            sb.append("\nСлабые места (из последних проверок):\n");
            weak.forEach(w -> sb.append("- ").append(w).append('\n'));
        }
        return sb.toString();
    }

    // ================================================================ LLM plumbing

    String chat(List<Map<String, String>> messages, double temperature) throws Exception {
        if (cfg == null) throw new IllegalStateException("LLM не настроен — нет LLM_API_KEY");
        JsonNode resp = LlmClient.send(http, cfg, messages, null, null, temperature);
        JsonNode content = resp.path("choices").path(0).path("message").path("content");
        String text = content.isNull() || content.isMissingNode() ? "" : THINK.matcher(content.asText()).replaceAll("").strip();
        if (text.isEmpty()) throw new IOException("модель вернула пустой ответ (finish_reason=" + LlmClient.finishReason(resp) + ")");
        return text;
    }

    /** JSON answer with one repair turn if it does not parse. */
    JsonNode askJson(String system, String user, double temperature) throws Exception {
        List<Map<String, String>> msgs = new ArrayList<>(List.of(LlmClient.message("system", system), LlmClient.message("user", user)));
        String text = chat(msgs, temperature);
        JsonNode j = extractJson(text);
        if (j != null) return j;
        msgs.add(LlmClient.message("assistant", text));
        msgs.add(LlmClient.message("user", "Ответ должен быть ТОЛЬКО JSON-объектом по схеме из инструкции, без текста вокруг."));
        j = extractJson(chat(msgs, 0.0));
        if (j == null) throw new IOException("модель не вернула JSON");
        return j;
    }

    /** First balanced {...} of the text (strings respected). */
    static JsonNode extractJson(String text) {
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean str = false;
            boolean esc = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    str = !str;
                } else if (!str && c == '{') {
                    depth++;
                } else if (!str && c == '}' && --depth == 0) {
                    try {
                        return JSON.readTree(text.substring(start, i + 1));
                    } catch (IOException e) {
                        break;
                    }
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    // ================================================================ helpers

    static String stripFence(String s) {
        String t = s.strip();
        if (t.startsWith("```")) t = t.replaceFirst("^```[a-zA-Z]*\\n", "").replaceFirst("\\n```\\s*$", "");
        return t.strip();
    }

    static String titleOf(String pathOrHeading) {
        String s = pathOrHeading.substring(pathOrHeading.lastIndexOf('/') + 1).replaceFirst("\\.md$", "").replaceFirst("^#\\s+", "");
        return s.replaceFirst("^\\d+(\\.\\d+)?\\.?\\s+", "");
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static Set<String> words(String s) {
        Set<String> out = new TreeSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (w.length() > 3) out.add(w.length() > 6 ? w.substring(0, 6) : w);
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new TreeSet<>(a);
        inter.retainAll(b);
        Set<String> union = new TreeSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    static String slug(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
        return (t.length() > 60 ? t.substring(0, 60) : t) + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmmss"));
    }

    static List<String> append(List<String> list, String item) {
        List<String> out = new ArrayList<>(list);
        out.add(item);
        return out;
    }

    static List<String> names(List<SourceFile> files) {
        return files.stream().map(SourceFile::name).toList();
    }

    static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
