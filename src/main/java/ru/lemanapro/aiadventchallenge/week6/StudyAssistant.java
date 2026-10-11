package ru.lemanapro.aiadventchallenge.week6;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;
import ru.lemanapro.aiadventchallenge.week5.Chunker;
import ru.lemanapro.aiadventchallenge.week5.Embedder;
import ru.lemanapro.aiadventchallenge.week5.RagIndex;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Week 6 (day 27): the study assistant behind the web app — everything the LOCAL model does with the course.
 *
 *   ask()     — question about the course: RAG over the whole course (chunks of the open lesson get a boost),
 *               numbered fragments in the prompt, the answer cites them as [n]; the sources are sent to the
 *               page BEFORE the answer so it can show where the answer comes from. Mode HINT (assignments):
 *               the assignment text is in the prompt and the model is told not to give the ready solution.
 *   explain() — the open lesson itself: SUMMARY (конспект) or SIMPLE (объясни проще). Long lessons are
 *               shrunk section by section to TASK27_LESSON_CHARS, so every section is represented.
 *   quiz()    — N multiple-choice questions from random fragments of the lesson; Ollama structured output
 *               (JSON schema in `format`), then validated in code: 3-5 options, a valid correct index,
 *               options shuffled (small models like to put the right answer first).
 *
 * Retrieval reuses week 5 as is: Chunker.structure (markdown sections) → RagIndex → Embedder. The embedder is
 * picked by Embedder.fromEnv against Ollama's /v1: an embedding model pulled into Ollama (bge-m3,
 * nomic-embed-text …) is found automatically, otherwise the offline lexical LocalHash is used. The index is
 * cached in TASK27_INDEX and rebuilt when the course text or the embedder changes.
 *
 * Nothing here talks to a cloud: the only network peer is the Ollama server (OllamaClient).
 */
public final class StudyAssistant {

    public enum Mode { ASK, HINT }

    public enum Explain { SUMMARY, SIMPLE }

    /** A fragment shown to the model as [n] and to the user as a source chip. */
    public record Source(int n, String lessonId, String lessonTitle, String section, double score) {
    }

    public record Question(String question, List<String> options, int correct, String explanation, String section) {
    }

    public record Quiz(List<Question> questions, List<Source> sources, OllamaClient.ChatResult stats) {
    }

    public record Settings(String model, int numCtx, int topK, int contextChars, int lessonChars) {
        static Settings fromEnv(String model) {
            return new Settings(model,
                    Integer.parseInt(LlmClient.env("TASK27_NUM_CTX", "8192")),
                    Integer.parseInt(LlmClient.env("TASK27_TOP_K", "5")),
                    Integer.parseInt(LlmClient.env("TASK27_CONTEXT_CHARS", "7000")),
                    Integer.parseInt(LlmClient.env("TASK27_LESSON_CHARS", "14000")));
        }
    }

    private static final String TUTOR = """
            Ты — наставник курса «Тестирование производительности на Gatling» (Java, банковское приложение \
            на стенде курса). Помогаешь студенту разобраться в материале.
            Правила:
            - Отвечай по-русски, понятно и по делу. Формулы пиши простым текстом (L = λ × W), без LaTeX; конфигурацию и код — в блоках ``` .
            - Опирайся на фрагменты курса из сообщения. После каждого утверждения из фрагмента обязательно ставь его номер в квадратных скобках: [1], [2].
            - Не придумывай названия уроков, ссылки и цифры, которых нет во фрагментах.""";

    /** Only for questions: in HINT mode a small model latches onto this line even with the assignment in front of it. */
    private static final String NOT_FOUND_RULE = """

            - Если во фрагментах ответа нет, так и скажи: «В материалах курса этого нет», и при необходимости \
            ответь кратко из общих знаний, пометив такой ответ «(вне курса)».""";

    private static final String HINT_RULES = """

            Сейчас студент решает задание (его текст ниже). Твоя роль — подсказывать, а не решать:
            - НЕ давай готового решения и итоговых чисел, даже если они есть в тексте задания.
            - Дай наводящие вопросы, напомни понятие или формулу из курса со ссылкой [n], подскажи первый шаг.
            - Если студент прислал своё решение — проверь его и укажи, где ошибка, но не переписывай целиком.
            - Полное решение — только если студент прямо пишет «покажи решение».
""";

    private final CourseLibrary lib;
    private final OllamaClient ollama;
    private final Settings settings;
    private final RagIndex index;
    private final Embedder embedder;

    private StudyAssistant(CourseLibrary lib, OllamaClient ollama, Settings settings, RagIndex index, Embedder embedder) {
        this.lib = lib;
        this.ollama = ollama;
        this.settings = settings;
        this.index = index;
        this.embedder = embedder;
    }

    /** Loads the cached course index, or (re)builds it when the course or the embedder changed. */
    public static StudyAssistant open(CourseLibrary lib, OllamaClient ollama, String model, HttpClient http) throws Exception {
        Settings settings = Settings.fromEnv(model);
        LlmClient.Config cfg = ollama.openAiConfig(model);
        Path file = Path.of(LlmClient.env("TASK27_INDEX", "task27-course-index.json"));
        List<Chunker.Doc> docs = RagIndex.loadCorpus(lib.root());
        long chars = docs.stream().mapToLong(d -> d.text().length()).sum();
        Embedder current = Embedder.fromEnv(http, cfg);

        RagIndex index = null;
        if (Files.isRegularFile(file)) {
            RagIndex cached = RagIndex.load(file);
            RagIndex.Meta m = cached.meta();
            if (m.documents() == docs.size() && m.corpusChars() == chars && m.embedder().equals(current.id())) {
                index = cached;
                System.out.println("Индекс курса: " + file + " (" + cached.chunks().size() + " фрагментов, " + m.embedder() + ")");
            }
        }
        if (index == null) {
            System.out.println("Индексирую курс: " + docs.size() + " файлов, " + chars + " символов, эмбеддер " + current.id() + " …");
            long t0 = System.currentTimeMillis();
            index = RagIndex.build(RagIndex.Strategy.STRUCTURE, docs, RagIndex.chunk(RagIndex.Strategy.STRUCTURE, docs), current);
            index.save(file);
            System.out.println("Индекс готов: " + index.chunks().size() + " фрагментов за "
                    + (System.currentTimeMillis() - t0) / 1000.0 + " с → " + file);
        }
        return new StudyAssistant(lib, ollama, settings, index, index.embedder(http, cfg));
    }

    public Settings settings() {
        return settings;
    }

    public String embedderId() {
        return index.meta().embedder();
    }

    public int chunkCount() {
        return index.chunks().size();
    }

    // ---------------------------------------------------------------- retrieval

    /**
     * Top-k fragments for the query. With a lesson open its own fragments get a ×1.25 boost; the course map
     * (a list of every lesson title) matches almost any query lexically, so it is weighted ×0.7.
     */
    List<RagIndex.Hit> retrieve(String query, String lessonId, int k) throws Exception {
        float[] q = embedder.embed(List.of(query))[0];
        List<RagIndex.Hit> all = new ArrayList<>(index.search(q, index.chunks().size()));
        all.replaceAll(h -> {
            CourseLibrary.Lesson l = lib.lesson(h.chunk().source());
            double w = h.chunk().source().equals(lessonId) ? 1.25 : l != null && l.kind() == CourseLibrary.Kind.MAP ? 0.7 : 1.0;
            return w == 1.0 ? h : new RagIndex.Hit(h.chunk(), h.score() * w);
        });
        all.sort(Comparator.comparingDouble(RagIndex.Hit::score).reversed());
        List<RagIndex.Hit> out = new ArrayList<>();
        for (RagIndex.Hit h : all) {
            if (h.chunk().text().strip().length() >= 80) {
                out.add(h);
            }
            if (out.size() == k) {
                break;
            }
        }
        return out;
    }

    private List<Source> sources(List<RagIndex.Hit> hits) {
        List<Source> out = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            RagIndex.Chunk c = hits.get(i).chunk();
            CourseLibrary.Lesson l = lib.lesson(c.source());
            out.add(new Source(i + 1, c.source(), l != null ? l.title() : c.title(), cleanSection(c.section()),
                    Math.round(hits.get(i).score() * 1000) / 1000.0));
        }
        return out;
    }

    /** Numbered fragments for the prompt, within the char budget (each fragment clipped evenly). */
    private String fragments(List<RagIndex.Hit> hits, List<Source> sources, int budget) {
        int per = Math.max(600, budget / Math.max(1, hits.size()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Source s = sources.get(i);
            sb.append('[').append(s.n()).append("] Урок «").append(s.lessonTitle()).append('»');
            if (!s.section().isBlank()) {
                sb.append(" › ").append(s.section());
            }
            sb.append('\n').append(clip(hits.get(i).chunk().text().strip(), per)).append("\n\n");
        }
        return sb.toString().strip();
    }

    static String cleanSection(String section) {
        return section == null ? "" : section.replace("\\", "").replace("**", "").strip();
    }

    // ---------------------------------------------------------------- ask / hint

    /**
     * @param history previous turns of this chat (role user/assistant, plain text), oldest first
     * @param emit    receives {type:sources|thinking|token, …} events while the answer is generated
     */
    public OllamaClient.ChatResult ask(String question, String lessonId, Mode mode, List<Map<String, String>> history,
                                       boolean think, Consumer<Map<String, Object>> emit) throws Exception {
        CourseLibrary.Lesson lesson = lib.lesson(lessonId);
        // a short follow-up ("а почему?") is searched together with the previous question
        String query = question;
        List<String> prevUser = history.stream().filter(m -> "user".equals(m.get("role"))).map(m -> m.get("content")).toList();
        if (question.length() < 60 && !prevUser.isEmpty()) {
            query = prevUser.getLast() + "\n" + question;
        }
        if (lesson != null) {
            query = lesson.title() + "\n" + query;
        }
        boolean hint = mode == Mode.HINT && lesson != null && lesson.kind() == CourseLibrary.Kind.ASSIGNMENT;
        String assignment = hint ? shrink(CourseLibrary.withoutNav(lib.text(lessonId)), settings.lessonChars() / 2) : null;
        List<RagIndex.Hit> hits;
        if (hint) {
            // the assignment itself goes into the prompt in full; fragments should explain the theory behind it:
            // search by the assignment text, prefer the lesson it belongs to (02.1 → 02), skip the assignment itself
            CourseLibrary.Lesson parent = lib.parentLesson(lessonId);
            String q = question + "\n" + clip(assignment, 1500);
            hits = new ArrayList<>(retrieve(q, parent.id(), settings.topK() + 3).stream()
                    .filter(h -> !h.chunk().source().equals(lessonId)).limit(settings.topK()).toList());
        } else {
            hits = retrieve(query, lessonId, settings.topK());
        }
        List<Source> sources = sources(hits);
        emit.accept(Map.of("type", "sources", "items", sources));

        StringBuilder system = new StringBuilder(TUTOR).append(hint ? "" : NOT_FOUND_RULE);
        if (lesson != null) {
            system.append("\nСтудент сейчас открыл: «").append(lesson.title()).append("» (модуль «").append(lesson.module()).append("»).");
        }
        if (hint) {
            system.append(HINT_RULES);
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(LlmClient.message("system", system.toString()));
        int from = Math.max(0, history.size() - 6);
        for (Map<String, String> m : history.subList(from, history.size())) {
            String role = m.get("role");
            String content = m.get("content");
            if (("user".equals(role) || "assistant".equals(role)) && content != null && !content.isBlank()) {
                messages.add(LlmClient.message(role, clip(content, 2000)));
            }
        }
        StringBuilder user = new StringBuilder();
        if (hint) {
            user.append("Текст задания:\n\"\"\"\n").append(assignment).append("\n\"\"\"\n\n");
        }
        user.append("Фрагменты курса:\n").append(fragments(hits, sources, settings.contextChars()))
                .append("\n\n").append(hint ? "Сообщение студента: " : "Вопрос: ").append(question)
                .append(hint ? "" : "\n\n(Ответь по фрагментам, ставь ссылки [n].)");
        messages.add(LlmClient.message("user", user.toString()));
        return generate(messages, think, 0.3, null, emit);
    }

    // ---------------------------------------------------------------- explain the open lesson

    public OllamaClient.ChatResult explain(String lessonId, Explain kind, boolean think,
                                           Consumer<Map<String, Object>> emit) throws Exception {
        CourseLibrary.Lesson lesson = lib.lesson(lessonId);
        if (lesson == null) {
            throw new IllegalArgumentException("нет урока " + lessonId);
        }
        String text = shrink(CourseLibrary.withoutNav(lib.text(lessonId)), settings.lessonChars());
        emit.accept(Map.of("type", "sources", "items",
                List.of(new Source(1, lessonId, lesson.title(), "весь урок", 1.0))));
        String task = switch (kind) {
            case SUMMARY -> """
                    Сделай конспект урока ниже по-русски, только по его тексту:
                    1. О чём урок — 2 предложения.
                    2. Ключевые идеи — 5–8 пунктов.
                    3. Термины и формулы с коротким пояснением (если есть в уроке).
                    4. Типичная ошибка, от которой предостерегает урок (если есть).
                    Пиши компактно, без вступлений.""";
            case SIMPLE -> """
                    Объясни главную идею урока ниже простыми словами — для человека, который только начинает \
                    заниматься нагрузочным тестированием:
                    1. Аналогия из обычной жизни.
                    2. Как это выглядит на практике в Gatling / на стенде курса (по тексту урока).
                    3. Три вопроса для самопроверки (без ответов).
                    По-русски, без вступлений.""";
        };
        List<Map<String, String>> messages = List.of(
                LlmClient.message("system", TUTOR + NOT_FOUND_RULE),
                LlmClient.message("user", task + "\n\nУрок «" + lesson.title() + "»:\n\"\"\"\n" + text + "\n\"\"\""));
        return generate(messages, think, 0.3, null, emit);
    }

    // ---------------------------------------------------------------- quiz

    private static final Map<String, Object> QUIZ_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("questions", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "question", Map.of("type", "string"),
                                    "options", Map.of("type", "array", "items", Map.of("type", "string")),
                                    "correct", Map.of("type", "integer"),
                                    "explanation", Map.of("type", "string"),
                                    "fragment", Map.of("type", "integer")),
                            "required", List.of("question", "options", "correct", "explanation", "fragment")))),
            "required", List.of("questions"));

    public Quiz quiz(String lessonId, int count, boolean think, Consumer<Map<String, Object>> emit) throws Exception {
        CourseLibrary.Lesson lesson = lib.lesson(lessonId);
        if (lesson == null) {
            throw new IllegalArgumentException("нет урока " + lessonId);
        }
        // random fragments of this lesson (a new set of questions every time), shown in document order
        List<RagIndex.Chunk> own = new ArrayList<>(index.chunks().stream()
                .filter(c -> c.source().equals(lessonId) && c.text().strip().length() >= 300).toList());
        if (own.isEmpty()) {
            own = new ArrayList<>(index.chunks().stream().filter(c -> c.source().equals(lessonId)).toList());
        }
        Collections.shuffle(own, new Random());
        List<RagIndex.Chunk> picked = new ArrayList<>();
        int budget = 6000;
        for (RagIndex.Chunk c : own) {
            if (budget <= 0) {
                break;
            }
            picked.add(c);
            budget -= Math.min(c.text().length(), 2200);
        }
        picked.sort(Comparator.comparingInt(RagIndex.Chunk::ordinal));
        List<RagIndex.Hit> hits = picked.stream().map(c -> new RagIndex.Hit(c, 1.0)).toList();
        List<Source> sources = sources(hits);
        emit.accept(Map.of("type", "sources", "items", sources));

        // ask for 2 spare questions: validation may drop some, the user still gets `count`
        String prompt = "Составь " + (count + 2) + " вопросов с выбором ответа по фрагментам урока «" + lesson.title() + "».\n"
                + "- Вопрос проверяет понимание (расчёт, выбор, причина), а не дословное запоминание текста.\n"
                + "- Ровно 4 варианта ответа, верный один; неверные — правдоподобные ошибки студента.\n"
                + "- correct — номер верного варианта, считая с 0.\n"
                + "- explanation — 1–2 предложения, почему ответ верный.\n"
                + "- fragment — номер фрагмента, на котором основан вопрос.\n"
                + "- Только по фрагментам, по-русски. Верни JSON {\"questions\": [...]}.\n\n"
                + "Фрагменты:\n" + fragments(hits, sources, 6600);
        List<Map<String, String>> messages = List.of(
                LlmClient.message("system", "Ты — методист курса по нагрузочному тестированию. Составляешь тесты для самопроверки."),
                LlmClient.message("user", prompt));
        OllamaClient.ChatResult r = generate(messages, think, 0.7, QUIZ_SCHEMA, d -> {
            if ("thinking".equals(d.get("type"))) {
                emit.accept(d);
            } else {
                emit.accept(Map.of("type", "progress", "chars", ((String) d.get("text")).length()));
            }
        });
        List<Question> questions;
        try {
            questions = parseQuiz(r.content(), sources, count);
        } catch (Exception e) {
            questions = List.of();
        }
        if (questions.size() < count) { // visible in the server console — what exactly the model returned
            System.out.println("· тест: из ответа модели принято " + questions.size() + " из " + count
                    + " вопросов; ответ модели: " + clip(r.content().replaceAll("\\s+", " "), 1500));
        }
        return new Quiz(questions, sources, r);
    }

    /** Lenient parse + validation of the model's quiz JSON. Invalid questions are dropped, not "fixed". */
    static List<Question> parseQuiz(String json, List<Source> sources, int max) throws Exception {
        JsonNode root = LlmClient.parseJson(extractJson(json));
        JsonNode arr = root.isArray() ? root : root.path("questions");
        List<Question> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Random rnd = new Random();
        for (JsonNode q : arr) {
            String text = q.path("question").asText("").strip();
            List<String> options = new ArrayList<>();
            for (JsonNode o : q.path("options")) {
                String s = o.asText("").strip().replaceFirst("^[A-DА-Га-гa-d1-4][).]\\s+", "");
                if (!s.isEmpty() && !options.contains(s)) {
                    options.add(s);
                }
            }
            int correct = q.path("correct").asInt(-1);
            if (text.isEmpty() || options.size() < 3 || options.size() > 5 || correct < 0 || correct >= options.size()
                    || !seen.add(text.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String right = options.get(correct);
            String explanation = q.path("explanation").asText("").strip();
            if (contradicts(explanation, options, correct)) {
                continue; // the explanation argues for another option than the one marked correct
            }
            Collections.shuffle(options, rnd);
            int frag = q.path("fragment").asInt(0);
            String section = sources.stream().filter(s -> s.n() == frag).map(s -> s.section().isBlank() ? s.lessonTitle() : s.section())
                    .findFirst().orElse("");
            out.add(new Question(text, options, options.indexOf(right), explanation, section));
            if (out.size() == max) {
                break;
            }
        }
        return out;
    }

    /**
     * The explanation argues for another option than the one marked correct — the model contradicts itself.
     * Two signals: (1) the explanation quotes a wrong option verbatim and never the marked one; (2) by word stems
     * (first 5 letters of words of 3+ letters/digits, so word order and endings do not matter) a wrong option is
     * covered by the explanation clearly better (≥ 0.6 and +0.15) than the marked one.
     */
    static boolean contradicts(String explanation, List<String> options, int correct) {
        String e = explanation.toLowerCase(Locale.ROOT);
        String right = options.get(correct).toLowerCase(Locale.ROOT);
        boolean namesRight = e.contains(right);
        Set<String> expl = stems(e);
        double rightCover = cover(stems(right), expl);
        for (int i = 0; i < options.size(); i++) {
            if (i == correct) {
                continue;
            }
            String o = options.get(i).toLowerCase(Locale.ROOT);
            if (o.length() >= 2 && e.contains(o) && !right.contains(o) && !namesRight) {
                return true;
            }
            double c = cover(stems(o), expl);
            if (c >= 0.6 && c > Math.max(rightCover, 0) + 0.15) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        java.util.regex.Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        while (m.find()) {
            String w = m.group();
            out.add(w.length() > 5 ? w.substring(0, 5) : w);
        }
        return out;
    }

    /** Share of the option's stems present in the explanation; -1 when the option has no stems (e.g. "L = λ × W"). */
    private static double cover(Set<String> option, Set<String> explanation) {
        if (option.isEmpty()) {
            return -1;
        }
        return option.stream().filter(explanation::contains).count() / (double) option.size();
    }

    private static String extractJson(String s) {
        String t = s.replaceAll("(?s)<think>.*?</think>", "").strip();
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        return a >= 0 && b > a ? t.substring(a, b + 1) : t;
    }

    // ---------------------------------------------------------------- shared

    private OllamaClient.ChatResult generate(List<Map<String, String>> messages, boolean think, double temperature,
                                             Object format, Consumer<Map<String, Object>> emit) throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("num_ctx", settings.numCtx());
        options.put("temperature", temperature);
        return ollama.chatStream(settings.model(), messages, options, think, format, d -> {
            if (!d.thinking().isEmpty()) {
                emit.accept(Map.of("type", "thinking", "text", d.thinking()));
            }
            if (!d.content().isEmpty()) {
                emit.accept(Map.of("type", "token", "text", d.content()));
            }
        });
    }

    private static final Pattern H2 = Pattern.compile("(?m)^(?=##\\s)");

    /**
     * Fits a long lesson into maxChars without dropping whole sections: every "## " section keeps its
     * heading and an equal share of the budget (cut at a line end), marked with "[…]".
     */
    static String shrink(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String[] parts = H2.split(text);
        int per = Math.max(300, maxChars / parts.length);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.length() <= per) {
                sb.append(p);
            } else {
                int cut = p.lastIndexOf('\n', per);
                sb.append(p, 0, cut > per / 2 ? cut : per).append("\n[…]\n\n");
            }
        }
        return sb.toString();
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
