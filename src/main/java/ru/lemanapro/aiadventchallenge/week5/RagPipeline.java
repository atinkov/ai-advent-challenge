package ru.lemanapro.aiadventchallenge.week5;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Week 5 (RAG), shared engine used by days 22-25: question -> retrieval -> (rewrite, rerank/filter) ->
 * prompt with context -> LLM -> answer.
 *
 * Retrieval (retrieve):
 *   1. query rewrite (optional, 1 LLM call): the question becomes 2-3 search queries (Russian + English
 *      with identifiers); every query is searched, results are merged by best cosine;
 *   2. vector search: top `topKBefore` candidates;
 *   3. second stage (Settings.rerank):
 *        NONE       — just the first topKAfter (the day-22 baseline);
 *        THRESHOLD  — drop cosine < max(minSimilarity, relativeCut × best cosine);
 *        HEURISTIC  — THRESHOLD + rescoring: 0.5 × cosine/best + 0.5 × IDF-weighted share of query terms
 *                     present in the chunk; drop score < minRerank;
 *        LLM        — the model grades every candidate 0..10 (1 LLM call); drop grade/10 < minRerank.
 *                     Falls back to HEURISTIC if the model's reply cannot be parsed;
 *      then the best topKAfter go into the context. An EMPTY context is a legal result: it is how the
 *      pipeline says "nothing relevant in the base".
 *
 * Generation:
 *   answerNoRag / answerWithContext  — plain text answers (day 22: the two modes of the agent);
 *   answerCited                      — day 24: the model must return JSON {known, answer, sources, quotes,
 *                                      clarify}; the reply is VERIFIED in code: sources must be chunk ids
 *                                      of the context, every quote must be a verbatim excerpt of a chunk
 *                                      (whitespace/markdown-insensitive match; the printed quote is re-cut
 *                                      from the chunk itself). Quotes that fail are dropped; if none is
 *                                      left the model gets one repair attempt, then quotes are extracted
 *                                      from the cited chunks by code (marked auto). With an empty context
 *                                      the model is NOT called: the answer is "не знаю" + a clarifying
 *                                      request — a rule enforced by code, not by the prompt;
 *   judge                            — "does the answer follow from the quotes?" (1 LLM call, day 24 check).
 *
 * LLM calls: temperature 0, <think> blocks stripped, transient failures (network, HTTP 429/5xx) retried twice.
 *
 * Settings: built-in defaults, overridden by task23-rag-settings.json (written by day 23 after threshold
 * calibration, used only if it was calibrated for the same index/embedder), overridden by env
 * RAG_TOP_K_BEFORE, RAG_TOP_K, RAG_RERANK (none|threshold|heuristic|llm), RAG_MIN_SIMILARITY,
 * RAG_RELATIVE_CUT, RAG_MIN_RERANK, RAG_REWRITE (on|off).
 */
public final class RagPipeline {

    public static final Path SETTINGS_FILE = Path.of("task23-rag-settings.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** For model output: tolerates raw newlines inside strings, trailing commas and single quotes. */
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER) // "\d", "\s" copied from code into a quote
            .build();
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)");
    private static final int CONTEXT_CHUNK_CHARS = 1800;
    private static final int RERANK_CHUNK_CHARS = 700;
    /** A "quote" shorter than this (after normalization) proves nothing and is rejected. */
    private static final int MIN_QUOTE_CHARS = 15;

    public enum Rerank {
        NONE, THRESHOLD, HEURISTIC, LLM;

        static Rerank of(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "none", "off" -> NONE;
                case "threshold" -> THRESHOLD;
                case "heuristic" -> HEURISTIC;
                default -> LLM;
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Settings(int topKBefore, int topKAfter, Rerank rerank, double minSimilarity, double relativeCut, double minRerank,
                           boolean rewrite, String embedder, String strategy, String calibrated) {

        /** Day-22 behaviour: plain top-k, no second stage, no rewrite. */
        public static Settings baseline(int k) {
            return new Settings(k, k, Rerank.NONE, 0, 0, 0, false, "", "", "");
        }

        public Settings with(Rerank rerank, boolean rewrite) {
            return new Settings(topKBefore, topKAfter, rerank, minSimilarity, relativeCut, minRerank, rewrite, embedder, strategy, calibrated);
        }

        public Settings withThreshold(double minSimilarity) {
            return new Settings(topKBefore, topKAfter, rerank, minSimilarity, relativeCut, minRerank, rewrite, embedder, strategy, calibrated);
        }

        /** Defaults < task23-rag-settings.json (if calibrated for this index) < env. */
        public static Settings forIndex(RagIndex index) {
            return resolve(index, true);
        }

        /** Defaults < env, ignoring a saved calibration (day 23 starts from here to produce a fresh one). */
        public static Settings defaultsFor(RagIndex index) {
            return resolve(index, false);
        }

        private static Settings resolve(RagIndex index, boolean useSaved) {
            boolean local = index.meta().embedder().startsWith("local-");
            Settings s = new Settings(20, 5, Rerank.LLM, local ? 0.12 : 0.30, 0.6, 0.5, true, index.meta().embedder(), index.meta().strategy(), "");
            if (useSaved && Files.isRegularFile(SETTINGS_FILE)) {
                try {
                    Settings saved = MAPPER.readValue(SETTINGS_FILE.toFile(), Settings.class);
                    if (index.meta().embedder().equals(saved.embedder()) && index.meta().strategy().equals(saved.strategy())) {
                        s = saved;
                    }
                } catch (Exception e) {
                    System.out.println("Не удалось прочитать " + SETTINGS_FILE + " (" + e.getMessage() + ") — беру настройки по умолчанию.");
                }
            }
            return new Settings(
                    Integer.parseInt(LlmClient.env("RAG_TOP_K_BEFORE", String.valueOf(s.topKBefore()))),
                    Integer.parseInt(LlmClient.env("RAG_TOP_K", String.valueOf(s.topKAfter()))),
                    Rerank.of(LlmClient.env("RAG_RERANK", s.rerank().name())),
                    Double.parseDouble(LlmClient.env("RAG_MIN_SIMILARITY", String.valueOf(s.minSimilarity()))),
                    Double.parseDouble(LlmClient.env("RAG_RELATIVE_CUT", String.valueOf(s.relativeCut()))),
                    Double.parseDouble(LlmClient.env("RAG_MIN_RERANK", String.valueOf(s.minRerank()))),
                    isOn(LlmClient.env("RAG_REWRITE", s.rewrite() ? "on" : "off")),
                    s.embedder(), s.strategy(), s.calibrated());
        }

        public void save() throws Exception {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_FILE.toFile(), this);
        }

        public String describe() {
            return "top-K " + topKBefore + "→" + topKAfter + ", этап 2: " + rerank.name().toLowerCase(Locale.ROOT)
                    + (rerank == Rerank.THRESHOLD || rerank == Rerank.HEURISTIC
                    ? String.format(Locale.ROOT, " (cos ≥ %.2f, ≥ %.0f %% от лучшего%s)", minSimilarity, relativeCut * 100,
                    rerank == Rerank.HEURISTIC ? String.format(Locale.ROOT, ", оценка ≥ %.2f", minRerank) : "")
                    : rerank == Rerank.LLM ? String.format(Locale.ROOT, " (оценка ≥ %.0f/10)", minRerank * 10) : "")
                    + ", rewrite: " + (rewrite ? "вкл" : "выкл");
        }

        private static boolean isOn(String raw) {
            return raw.equalsIgnoreCase("on") || raw.equals("1") || raw.equalsIgnoreCase("true");
        }
    }

    /** cosine — vector similarity; score — second-stage score in 0..1 (NaN if the stage did not rescore). */
    public record Candidate(RagIndex.Chunk chunk, double cosine, double score, String query) {
    }

    public record Retrieval(String question, List<String> queries, List<Candidate> before, List<Candidate> after, Settings settings,
                            List<String> notes) {
        public double bestCosine() {
            return before.isEmpty() ? 0 : before.getFirst().cosine();
        }

        public double bestScore() {
            return after.stream().mapToDouble(c -> Double.isNaN(c.score()) ? c.cosine() : c.score()).max().orElse(0);
        }

        public int contextChars() {
            return after.stream().mapToInt(c -> Math.min(CONTEXT_CHUNK_CHARS, c.chunk().text().length())).sum();
        }
    }

    public record Quote(String chunkId, String text, boolean auto) {
    }

    /** A verified answer: sources and quotes are guaranteed to exist in the index (see answerCited). */
    public record Cited(boolean known, String answer, List<RagIndex.Chunk> sources, List<Quote> quotes, String clarify,
                        int rejectedQuotes, boolean repaired, List<String> notes) {

        public boolean hasSources() {
            return !sources.isEmpty();
        }

        public boolean hasQuotes() {
            return !quotes.isEmpty();
        }

        /** Console / chat rendering: the answer, then sources and quotes — always, in every mode. */
        public String render() {
            StringBuilder sb = new StringBuilder(answer.strip());
            if (!known && clarify != null && !clarify.isBlank() && !answer.contains(clarify)) {
                sb.append("\n").append(clarify.strip());
            }
            sb.append("\n\nИсточники:");
            if (sources.isEmpty()) {
                sb.append(" — (в базе знаний нет достаточно релевантных фрагментов)");
            }
            for (RagIndex.Chunk c : sources) {
                sb.append("\n  • ").append(c.ref()).append(", строки ").append(c.startLine()).append("–").append(c.endLine());
            }
            if (!quotes.isEmpty()) {
                sb.append("\nЦитаты:");
                for (Quote q : quotes) {
                    sb.append("\n  «").append(q.text().strip().replaceAll("\\s+", " ")).append("» [").append(q.chunkId()).append("]")
                            .append(q.auto() ? " (подобрана автоматически)" : "");
                }
            }
            return sb.toString();
        }
    }

    public record Judge(boolean supported, String comment) {
    }

    private final HttpClient http;
    private final LlmClient.Config cfg;
    private final RagIndex index;
    private final Embedder embedder;
    private Map<String, Double> idf;
    private int llmCalls;
    private long promptTokens;
    private long completionTokens;

    public RagPipeline(HttpClient http, LlmClient.Config cfg, RagIndex index) {
        this.http = http;
        this.cfg = cfg;
        this.index = index;
        this.embedder = index.embedder(http, cfg);
    }

    public RagIndex index() {
        return index;
    }

    public int llmCalls() {
        return llmCalls;
    }

    public long tokens() {
        return promptTokens + completionTokens;
    }

    // ================================================================ retrieval

    public Retrieval retrieve(String question, Settings s) throws Exception {
        return retrieve(question, List.of(), s);
    }

    /** extraQueries — queries prepared by the caller (the chat passes its history-aware search query here). */
    public Retrieval retrieve(String question, List<String> extraQueries, Settings s) throws Exception {
        List<String> notes = new ArrayList<>();
        Set<String> queries = new LinkedHashSet<>();
        queries.add(question.strip());
        extraQueries.stream().filter(q -> q != null && !q.isBlank()).forEach(q -> queries.add(q.strip()));
        if (s.rewrite()) {
            try {
                queries.addAll(rewrite(question));
            } catch (Exception e) {
                notes.add("rewrite не удался (" + brief(e) + ") — поиск по исходному вопросу");
            }
        }
        List<String> queryList = List.copyOf(queries);
        float[][] vectors = embedder.embed(queryList);
        Map<String, Candidate> merged = new LinkedHashMap<>();
        for (int i = 0; i < queryList.size(); i++) {
            for (RagIndex.Hit h : index.search(vectors[i], s.topKBefore())) {
                Candidate old = merged.get(h.chunk().chunkId());
                if (old == null || h.score() > old.cosine()) {
                    merged.put(h.chunk().chunkId(), new Candidate(h.chunk(), h.score(), Double.NaN, queryList.get(i)));
                }
            }
        }
        List<Candidate> before = merged.values().stream().sorted(Comparator.comparingDouble(Candidate::cosine).reversed())
                .limit(s.topKBefore()).toList();
        List<Candidate> after = secondStage(question, queryList, before, s, notes);
        return new Retrieval(question, queryList, before, after, s, notes);
    }

    private List<Candidate> secondStage(String question, List<String> queries, List<Candidate> before, Settings s, List<String> notes) {
        if (before.isEmpty() || s.rerank() == Rerank.NONE) {
            return before.stream().limit(s.topKAfter()).toList();
        }
        if (s.rerank() == Rerank.LLM) {
            try {
                return llmRerank(question, before, s);
            } catch (Exception e) {
                notes.add("LLM-реранкер не сработал (" + brief(e) + ") — использована эвристика");
                return heuristic(queries, thresholded(before, s), s);
            }
        }
        List<Candidate> kept = thresholded(before, s);
        return s.rerank() == Rerank.THRESHOLD ? kept.stream().limit(s.topKAfter()).toList() : heuristic(queries, kept, s);
    }

    private static List<Candidate> thresholded(List<Candidate> before, Settings s) {
        double floor = Math.max(s.minSimilarity(), s.relativeCut() * before.getFirst().cosine());
        return before.stream().filter(c -> c.cosine() >= floor).toList();
    }

    private List<Candidate> heuristic(List<String> queries, List<Candidate> kept, Settings s) {
        if (kept.isEmpty()) {
            return kept;
        }
        Set<String> terms = new LinkedHashSet<>();
        queries.forEach(q -> terms.addAll(Embedder.LocalHash.features(q)));
        Map<String, Double> weights = idf();
        double total = terms.stream().mapToDouble(t -> weights.getOrDefault(t, maxIdf())).sum();
        double best = kept.getFirst().cosine();
        List<Candidate> scored = new ArrayList<>();
        for (Candidate c : kept) {
            Set<String> have = Set.copyOf(Embedder.LocalHash.features(c.chunk().source() + " " + c.chunk().section() + " " + c.chunk().text()));
            double covered = terms.stream().filter(have::contains).mapToDouble(t -> weights.getOrDefault(t, maxIdf())).sum();
            double score = 0.5 * (best <= 0 ? 0 : c.cosine() / best) + 0.5 * (total <= 0 ? 0 : covered / total);
            scored.add(new Candidate(c.chunk(), c.cosine(), score, c.query()));
        }
        return scored.stream().filter(c -> c.score() >= s.minRerank())
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()).limit(s.topKAfter()).toList();
    }

    private List<Candidate> llmRerank(String question, List<Candidate> before, Settings s) throws Exception {
        StringBuilder user = new StringBuilder("Вопрос: ").append(question).append("\n\nФрагменты:\n");
        for (Candidate c : before) {
            user.append("\n[").append(c.chunk().chunkId()).append("] ").append(c.chunk().source()).append(Chunker.SEP).append(c.chunk().section()).append("\n")
                    .append(Chunker.clip(c.chunk().text().strip(), RERANK_CHUNK_CHARS)).append("\n");
        }
        JsonNode json = askJson("""
                Ты оцениваешь, насколько фрагменты базы знаний проекта полезны для ответа на вопрос.
                Каждому фрагменту поставь оценку от 0 до 10:
                10 — фрагмент прямо содержит ответ или его существенную часть;
                5 — фрагмент по теме вопроса, но ответа в нём нет;
                0 — фрагмент не относится к вопросу.
                Оценивай только по тексту фрагмента, не по названию файла. Не отвечай на сам вопрос.
                Верни СТРОГО один JSON-объект: {"scores": {"<id фрагмента>": <оценка>, ...}} — с оценкой для каждого фрагмента.""",
                user.toString());
        JsonNode scores = json == null ? null : json.path("scores");
        if (scores == null || !scores.isObject() || scores.isEmpty()) {
            throw new IllegalStateException("в ответе нет объекта scores");
        }
        // models write the id as "s-0001", "[s-0001]" or "S-0001", and the grade as 9, "9" or "9/10"
        Map<String, Double> grades = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : scores.properties()) {
            java.util.regex.Matcher m = LEADING_NUMBER.matcher(e.getValue().asText(""));
            if (m.find()) {
                grades.put(cleanId(e.getKey()).toLowerCase(Locale.ROOT), Double.parseDouble(m.group(1)));
            }
        }
        if (before.stream().noneMatch(c -> grades.containsKey(c.chunk().chunkId()))) {
            throw new IllegalStateException("оценки не сопоставились с id фрагментов");
        }
        List<Candidate> scored = new ArrayList<>();
        for (Candidate c : before) {
            double grade = Math.max(0, Math.min(10, grades.getOrDefault(c.chunk().chunkId(), 0.0)));
            scored.add(new Candidate(c.chunk(), c.cosine(), grade / 10.0, c.query()));
        }
        return scored.stream().filter(c -> c.score() >= s.minRerank())
                .sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Comparator.comparingDouble(Candidate::cosine).reversed()))
                .limit(s.topKAfter()).toList();
    }

    /** Query rewrite: 2-3 search queries derived from the question (never answers, never invents facts). */
    public List<String> rewrite(String question) throws Exception {
        JsonNode json = askJson("""
                Ты готовишь поисковые запросы к базе знаний программного проекта: документация на английском и русском, отчёты на русском, исходный код на Java.
                Переформулируй вопрос пользователя в 2–3 коротких поисковых запроса:
                1) суть вопроса на русском, без вводных слов;
                2) тот же вопрос на английском, с терминами и идентификаторами (имена классов, методов, переменных окружения, файлов) — только теми, что есть в вопросе или прямо из него следуют;
                3) необязательно: набор ключевых слов.
                Не отвечай на вопрос и не добавляй фактов, которых в нём нет.
                Верни СТРОГО один JSON-объект: {"queries": ["...", "..."]}""",
                "Вопрос: " + question);
        List<String> out = new ArrayList<>();
        if (json != null) {
            for (JsonNode q : json.path("queries")) {
                if (q.isTextual() && !q.asText().isBlank() && out.size() < 3) {
                    out.add(q.asText().strip());
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("модель не вернула запросы");
        }
        return out;
    }

    private Map<String, Double> idf() {
        if (idf == null) {
            Map<String, Integer> df = new HashMap<>();
            for (RagIndex.Chunk c : index.chunks()) {
                for (String t : Set.copyOf(Embedder.LocalHash.features(c.text()))) {
                    df.merge(t, 1, Integer::sum);
                }
            }
            idf = new HashMap<>();
            int n = index.chunks().size();
            df.forEach((t, d) -> idf.put(t, Math.log((n + 1.0) / (d + 1.0)) + 1.0));
        }
        return idf;
    }

    private double maxIdf() {
        return Math.log(index.chunks().size() + 1.0) + 1.0;
    }

    // ================================================================ prompt building

    /** Context block shown to the model: every chunk with its id and address. */
    public static String context(List<Candidate> chunks) {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : chunks) {
            RagIndex.Chunk ch = c.chunk();
            sb.append("[").append(ch.chunkId()).append("] ").append(ch.source()).append(Chunker.SEP).append(ch.section())
                    .append(" (строки ").append(ch.startLine()).append("–").append(ch.endLine()).append(")\n");
            if (ch.lead() != null && !ch.lead().isBlank()) {
                sb.append(ch.lead()).append("\n");
            }
            sb.append(Chunker.clip(ch.text().strip(), CONTEXT_CHUNK_CHARS)).append("\n\n");
        }
        return sb.toString().strip();
    }

    // ================================================================ generation: plain (day 22)

    public String answerNoRag(String question) throws Exception {
        return chat(List.of(
                LlmClient.message("system", "Ты ассистент по учебному Java-проекту AIAdventChallenge. Отвечай кратко, по-русски."),
                LlmClient.message("user", question)));
    }

    public String answerWithContext(String question, Retrieval r) throws Exception {
        return chat(List.of(
                LlmClient.message("system", """
                        Ты ассистент по учебному Java-проекту AIAdventChallenge. Отвечай на вопрос ТОЛЬКО по фрагментам базы знаний из сообщения пользователя.
                        Если во фрагментах нет ответа — так и скажи, не додумывай. Отвечай кратко, по-русски; идентификаторы, числа и имена файлов приводи точно как во фрагментах."""),
                LlmClient.message("user", "Фрагменты базы знаний:\n\n" + (r.after().isEmpty() ? "(ничего не найдено)" : context(r.after()))
                        + "\n\nВопрос: " + question)));
    }

    // ================================================================ generation: cited (days 24-25)

    private static final String CITED_FORMAT = """
            Верни СТРОГО один JSON-объект без текста вокруг:
            {"known": true|false,
             "answer": "<краткий ответ по-русски>",
             "sources": ["<id фрагмента>", ...],
             "quotes": [{"chunk_id": "<id фрагмента>", "quote": "<дословная выдержка>"}],
             "clarify": "<что уточнить, если known=false; иначе пустая строка>"}
            Правила:
            - Отвечай ТОЛЬКО на основе фрагментов базы знаний; знания вне фрагментов не используй.
            - sources — id только тех фрагментов, на которые реально опирается ответ (id в квадратных скобках перед фрагментом).
            - quotes — от 1 до 4 цитат. Каждая цитата — ДОСЛОВНАЯ непрерывная выдержка из текста указанного фрагмента: копируй символ в символ, без перевода, пересказа, исправлений и многоточий; длина 20–300 знаков. Каждое утверждение ответа должно подтверждаться цитатой.
            - Если во фрагментах нет ответа на вопрос — known=false, answer="Не знаю.", sources и quotes пустые, в clarify напиши, что пользователю стоит уточнить.""";

    public Cited answerCited(String question, Retrieval r) throws Exception {
        return answerCited(question, r, "", List.of());
    }

    /**
     * @param extraSystem extra system instructions (the chat puts the task state here); may be empty
     * @param history     previous dialogue messages (role/content), oldest first; may be empty
     */
    public Cited answerCited(String question, Retrieval r, String extraSystem, List<Map<String, String>> history) throws Exception {
        if (r.after().isEmpty()) {
            return unknown(r); // weak context: the model is not even asked
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(LlmClient.message("system", "Ты ассистент по учебному Java-проекту AIAdventChallenge.\n"
                + (extraSystem.isBlank() ? "" : extraSystem.strip() + "\n") + CITED_FORMAT));
        messages.addAll(history);
        messages.add(LlmClient.message("user", "Фрагменты базы знаний:\n\n" + context(r.after()) + "\n\nВопрос: " + question));

        String raw = chat(messages);
        JsonNode json = citedJson(raw);
        List<String> notes = new ArrayList<>(r.notes());
        if (json == null) {
            messages.add(LlmClient.message("assistant", raw));
            messages.add(LlmClient.message("user", "Это не валидный JSON указанного формата (нужны поля known и answer). Верни только JSON-объект, без пояснений; "
                    + "кавычки внутри строк экранируй как \\\"."));
            raw = chat(messages);
            json = citedJson(raw);
            if (json == null) { // keep the text, but sources and quotes will be provided by code
                notes.add("модель не вернула JSON — источники и цитаты подобраны кодом");
                return fromCode(Chunker.clip(raw.strip(), 1500), r, notes);
            }
        }
        boolean known = json.path("known").asBoolean(true);
        String answer = answerText(json);
        if (!known) {
            String clarify = json.path("clarify").asText("").strip();
            return new Cited(false, "Не знаю: в найденных фрагментах базы знаний нет ответа на этот вопрос.", List.of(), List.of(),
                    clarify.isBlank() ? defaultClarify(r) : clarify, 0, false, notes);
        }
        Verified v = verify(json, r);
        boolean repaired = false;
        if (v.quotes().isEmpty()) {
            messages.add(LlmClient.message("assistant", raw));
            messages.add(LlmClient.message("user", "Цитаты не прошли проверку: каждая должна ДОСЛОВНО (символ в символ) присутствовать в тексте "
                    + "фрагмента с указанным id. Верни тот же JSON с исправленными quotes."));
            JsonNode again = citedJson(chat(messages));
            if (again != null) {
                Verified v2 = verify(again, r);
                if (!v2.quotes().isEmpty()) {
                    v = new Verified(v2.sources(), v2.quotes(), v.rejected() + v2.rejected());
                    repaired = true;
                }
            }
        }
        List<Quote> quotes = new ArrayList<>(v.quotes());
        List<RagIndex.Chunk> sources = new ArrayList<>(v.sources());
        if (quotes.isEmpty()) { // the guarantee: an answer never leaves without a verified quote
            List<RagIndex.Chunk> pool = sources.isEmpty() ? List.of(r.after().getFirst().chunk()) : sources;
            Quote auto = autoQuote(answer + " " + question, pool);
            quotes.add(auto);
            notes.add("цитаты модели не подтвердились дословно — цитата подобрана кодом");
        }
        for (Quote q : quotes) { // a quoted chunk is a source by definition
            RagIndex.Chunk c = index.byId(q.chunkId());
            if (c != null && sources.stream().noneMatch(x -> x.chunkId().equals(c.chunkId()))) {
                sources.add(c);
            }
        }
        return new Cited(true, answer, sources, quotes, "", v.rejected(), repaired, notes);
    }

    /** The reply as JSON, or null if it is not the expected object (no answer text and no explicit known=false). */
    private static JsonNode citedJson(String raw) {
        JsonNode json = extractJson(raw);
        if (json == null) {
            json = salvage(raw);
        }
        if (json == null) {
            return null;
        }
        boolean saysUnknown = json.has("known") && !json.path("known").asBoolean(true);
        return saysUnknown || !answerText(json).isBlank() ? json : null;
    }

    private static final Pattern SALVAGE_ANSWER = Pattern.compile("\"answer\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"(?:sources|quotes|clarify|known)\"", Pattern.DOTALL);
    private static final Pattern SALVAGE_SOURCES = Pattern.compile("\"sources\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern SALVAGE_QUOTE = Pattern.compile("\"chunk_id\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"quote\"\\s*:\\s*\"(.*?)\"\\s*}", Pattern.DOTALL);
    private static final Pattern CHUNK_ID = Pattern.compile("[fs]-\\d{4}");

    /**
     * Almost-JSON (typically an unescaped quote inside "answer" or "quote") is picked apart field by field,
     * so the user never sees raw JSON as the answer. What is recovered goes through the same verification.
     */
    private static JsonNode salvage(String raw) {
        java.util.regex.Matcher answer = SALVAGE_ANSWER.matcher(raw == null ? "" : raw);
        if (!answer.find()) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ObjectNode json = MAPPER.createObjectNode();
        json.put("known", !raw.matches("(?s).*\"known\"\\s*:\\s*false.*"));
        json.put("answer", unescape(answer.group(1)));
        com.fasterxml.jackson.databind.node.ArrayNode sources = json.putArray("sources");
        java.util.regex.Matcher src = SALVAGE_SOURCES.matcher(raw);
        if (src.find()) {
            CHUNK_ID.matcher(src.group(1)).results().forEach(m -> sources.add(m.group()));
        }
        com.fasterxml.jackson.databind.node.ArrayNode quotes = json.putArray("quotes");
        SALVAGE_QUOTE.matcher(raw).results().forEach(m -> quotes.addObject().put("chunk_id", m.group(1)).put("quote", unescape(m.group(2))));
        return json;
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", " ").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** "answer" as text; a model asked to answer with a list may return an array. */
    private static String answerText(JsonNode json) {
        JsonNode a = json.path("answer");
        if (a.isArray()) {
            List<String> items = new ArrayList<>();
            a.forEach(x -> items.add(x.asText("").strip()));
            items.removeIf(String::isBlank);
            return items.isEmpty() ? "" : "- " + String.join("\n- ", items);
        }
        return a.isTextual() || a.isNumber() || a.isBoolean() ? a.asText("").strip() : "";
    }

    /** The "не знаю" answer for a weak context — built by code, no LLM call. */
    public Cited unknown(Retrieval r) {
        Settings s = r.settings();
        String why = r.before().isEmpty() ? "поиск ничего не вернул"
                : s.rerank() == Rerank.LLM
                ? String.format(Locale.ROOT, "ни один из %d найденных фрагментов не получил оценку релевантности ≥ %.0f/10", r.before().size(), s.minRerank() * 10)
                : String.format(Locale.ROOT, "лучшая близость %.2f при пороге %.2f", r.bestCosine(), Math.max(s.minSimilarity(), 0));
        List<String> notes = new ArrayList<>(r.notes());
        notes.add("контекст пуст — LLM не вызывалась");
        return new Cited(false, "Не знаю: в базе знаний нет достаточно релевантной информации для ответа (" + why + ").",
                List.of(), List.of(), defaultClarify(r), 0, false, notes);
    }

    private static String defaultClarify(Retrieval r) {
        Set<String> topics = new LinkedHashSet<>();
        for (Candidate c : r.before()) {
            if (topics.size() < 3) {
                topics.add(c.chunk().source().replaceAll(".*/", "") + Chunker.SEP + Chunker.clip(c.chunk().section(), 50));
            }
        }
        return "Уточните, пожалуйста, вопрос: о каком дне челленджа, классе или файле проекта идёт речь?"
                + (topics.isEmpty() ? "" : " Ближайшее, что есть в базе: " + String.join("; ", topics) + ".");
    }

    private Cited fromCode(String answer, Retrieval r, List<String> notes) {
        RagIndex.Chunk top = r.after().getFirst().chunk();
        return new Cited(true, answer, List.of(top), List.of(autoQuote(answer + " " + r.question(), List.of(top))), "", 0, false, notes);
    }

    private record Verified(List<RagIndex.Chunk> sources, List<Quote> quotes, int rejected) {
    }

    /** Keeps only what can be proven against the context: known chunk ids and verbatim quotes. */
    private Verified verify(JsonNode json, Retrieval r) {
        Map<String, RagIndex.Chunk> context = new LinkedHashMap<>();
        r.after().forEach(c -> context.put(c.chunk().chunkId(), c.chunk()));
        List<RagIndex.Chunk> sources = new ArrayList<>();
        for (JsonNode s : json.path("sources")) {
            RagIndex.Chunk c = context.get(cleanId(s.asText("")));
            if (c != null && !sources.contains(c)) {
                sources.add(c);
            }
        }
        List<Quote> quotes = new ArrayList<>();
        int rejected = 0;
        for (JsonNode q : json.path("quotes")) {
            String text = q.isTextual() ? q.asText() : q.path("quote").asText("");
            String id = cleanId(q.path("chunk_id").asText(""));
            Quote found = null;
            RagIndex.Chunk claimed = context.get(id);
            if (claimed != null) {
                found = locate(text, claimed);
            }
            if (found == null) { // right words, wrong id: look in the other chunks of the context
                for (RagIndex.Chunk c : context.values()) {
                    found = locate(text, c);
                    if (found != null) {
                        break;
                    }
                }
            }
            if (found == null) {
                rejected++;
            } else if (quotes.stream().noneMatch(found::equals) && quotes.size() < 4) {
                quotes.add(found);
            }
        }
        return new Verified(sources, quotes, rejected);
    }

    private static String cleanId(String raw) {
        return raw.replace("[", "").replace("]", "").strip();
    }

    /**
     * Finds the model's quote in the chunk ignoring whitespace, markdown marks and typographic variants;
     * returns the quote RE-CUT from the chunk text (so what is shown is exactly what is stored), or null.
     */
    static Quote locate(String quote, RagIndex.Chunk chunk) {
        if (quote == null) {
            return null;
        }
        Normalized c = normalize(chunk.text());
        // 1) the quote as given; 2) without wrapping quote marks / final punctuation the model added;
        // 3) "a ... b" (the model skipped the middle) -> the longest solid piece
        List<String> variants = new ArrayList<>();
        String whole = normalize(quote).text();
        variants.add(whole);
        variants.add(whole.replaceAll("^[\"\\s]+|[\"\\s.,;:]+$", ""));
        String longest = "";
        for (String part : quote.split("\\s*(?:\\.\\.\\.|…|\\[…]|\\[\\.\\.\\.])\\s*")) {
            String p = normalize(part).text().replaceAll("^[\"\\s]+|[\"\\s.,;:]+$", "");
            if (p.length() > longest.length()) {
                longest = p;
            }
        }
        variants.add(longest);
        for (String q : variants) {
            if (q.length() < MIN_QUOTE_CHARS) {
                continue;
            }
            int at = c.text().indexOf(q);
            if (at >= 0) {
                int from = c.origin()[at];
                int to = c.origin()[at + q.length() - 1] + 1;
                return new Quote(chunk.chunkId(), chunk.text().substring(from, to), false);
            }
        }
        return null;
    }

    private record Normalized(String text, int[] origin) {
    }

    private static Normalized normalize(String s) {
        StringBuilder sb = new StringBuilder();
        int[] origin = new int[s.length()];
        boolean space = true; // collapses whitespace and trims the start
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '`' || ch == '*') {
                continue;
            }
            if (Character.isWhitespace(ch) || ch == ' ') {
                if (!space) {
                    origin[sb.length()] = i;
                    sb.append(' ');
                    space = true;
                }
                continue;
            }
            char n = switch (ch) {
                case '«', '»', '“', '”', '„', '\'' -> '"';
                case '–', '—', '−' -> '-';
                case 'ё' -> 'е';
                case 'Ё' -> 'е';
                default -> Character.toLowerCase(ch);
            };
            origin[sb.length()] = i;
            sb.append(n);
            space = false;
        }
        int len = sb.length();
        while (len > 0 && sb.charAt(len - 1) == ' ') {
            len--;
        }
        return new Normalized(sb.substring(0, len), origin);
    }

    /** Extractive fallback: the line of the cited chunks that shares most terms with the answer. */
    private static Quote autoQuote(String answer, List<RagIndex.Chunk> pool) {
        Set<String> terms = Set.copyOf(Embedder.LocalHash.features(answer));
        Quote best = null;
        double bestScore = -1;
        for (RagIndex.Chunk c : pool) {
            for (String piece : c.text().split("\\n|(?<=[.;!?])\\s+")) {
                String p = piece.strip();
                if (p.length() < 25) {
                    continue;
                }
                String cut = p.length() > 300 ? p.substring(0, 300) : p;
                long shared = Embedder.LocalHash.features(cut).stream().distinct().filter(terms::contains).count();
                if (shared > bestScore) {
                    bestScore = shared;
                    best = new Quote(c.chunkId(), cut, true);
                }
            }
        }
        if (best == null) {
            RagIndex.Chunk c = pool.getFirst();
            String t = c.text().strip();
            best = new Quote(c.chunkId(), t.substring(0, Math.min(300, t.length())), true);
        }
        return best;
    }

    /** Day-24 check: does the answer follow from its quotes? */
    public Judge judge(String question, Cited c) throws Exception {
        StringBuilder user = new StringBuilder("Вопрос: ").append(question).append("\n\nОтвет: ").append(c.answer()).append("\n\nЦитаты:\n");
        for (int i = 0; i < c.quotes().size(); i++) {
            user.append(i + 1).append(") ").append(c.quotes().get(i).text().strip()).append("\n");
        }
        JsonNode json = askJson("""
                Ты проверяешь ответ ассистента на соответствие цитатам из источников.
                supported=true — если ключевые утверждения ответа следуют из цитат (перевод и пересказ допустимы).
                supported=false — если ответ противоречит цитатам или его ключевые факты в цитатах отсутствуют.
                Верни СТРОГО один JSON-объект: {"supported": true|false, "comment": "<пояснение до 20 слов>"}""",
                user.toString());
        if (json == null || !json.has("supported")) {
            throw new IllegalStateException("судья не вернул JSON");
        }
        return new Judge(json.path("supported").asBoolean(false), json.path("comment").asText("").strip());
    }

    // ================================================================ LLM plumbing

    /** One chat call, temperature 0, <think> blocks removed. */
    public String chat(List<Map<String, String>> messages) throws Exception {
        JsonNode response = sendWithRetry(messages);
        llmCalls++;
        LlmClient.Usage usage = LlmClient.usage(response);
        promptTokens += Math.max(0, usage.promptTokens());
        completionTokens += Math.max(0, usage.completionTokens());
        JsonNode node = response.path("choices").path(0).path("message").path("content");
        String stripped = THINK.matcher(node.isTextual() ? node.asText() : "").replaceAll("");
        int close = stripped.indexOf("</think>"); // reasoning cut off without the opening tag
        String text = (close >= 0 ? stripped.substring(close + 8) : stripped).strip();
        if (text.isEmpty()) { // e.g. a reasoning model that filled only reasoning_content
            throw new IllegalStateException("пустой ответ модели (finish_reason=" + LlmClient.finishReason(response) + ")");
        }
        return text;
    }

    /** Transient failures (network, HTTP 429/5xx) are retried twice with a pause; anything else is thrown at once. */
    private JsonNode sendWithRetry(List<Map<String, String>> messages) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return LlmClient.send(http, cfg, messages, null, null, 0.0);
            } catch (LlmClient.RequestException e) {
                if (e.status() != 429 && e.status() < 500) {
                    throw e;
                }
                last = e;
            } catch (java.io.IOException e) {
                last = e;
            }
            Thread.sleep(1000L * (attempt * 2 + 1));
        }
        throw last;
    }

    /** A call that must return a JSON object; one retry on a malformed reply; null if both fail. */
    public JsonNode askJson(String system, String user) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>(List.of(LlmClient.message("system", system), LlmClient.message("user", user)));
        String raw = chat(messages);
        JsonNode json = extractJson(raw);
        if (json == null) {
            messages.add(LlmClient.message("assistant", raw));
            messages.add(LlmClient.message("user", "Это не валидный JSON. Верни только JSON-объект указанного формата, без пояснений."));
            json = extractJson(chat(messages));
        }
        return json;
    }

    /**
     * The first balanced {...} of the text that parses as a JSON object (models like to wrap JSON in prose or ```).
     * A balanced block that does not parse is skipped WHOLE — its nested objects are never returned as the reply.
     */
    static JsonNode extractJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            int end = -1;
            for (int i = start; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (inString) {
                    if (ch == '\\') {
                        i++;
                    } else if (ch == '"') {
                        inString = false;
                    }
                } else if (ch == '"') {
                    inString = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}' && --depth == 0) {
                    end = i;
                    break;
                }
            }
            if (end < 0) {
                return null; // never closed: nothing after this point can be a complete top-level object
            }
            try {
                JsonNode node = LENIENT.readTree(text.substring(start, end + 1));
                if (node.isObject()) {
                    return node;
                }
            } catch (Exception ignored) {
                // not JSON — look for the next object after this block
            }
            start = text.indexOf('{', end + 1);
        }
        return null;
    }

    static String brief(Exception e) {
        if (e instanceof LlmClient.RequestException r) {
            return "HTTP " + r.status() + " " + Chunker.clip(String.valueOf(r.body()).replaceAll("\\s+", " "), 120);
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + Chunker.clip(e.getMessage(), 120));
    }
}
