package ru.lemanapro.aiadventchallenge.week6;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

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
import java.util.regex.Pattern;

/**
 * Day 28: a RAG system that runs fully locally — and how it compares with the cloud model.
 *
 * Pipeline (all on this machine):
 *   index      — the week 6 course index (task27-course-index.json, built by day 27; rebuilt here if missing):
 *                Chunker.structure + a LOCAL embedder (an embedding model in Ollama, else the offline LocalHash);
 *   retrieval  — cosine search in that index inside this JVM (StudyAssistant.prepare), no network;
 *   generation — the local model in Ollama (TASK28_LOCAL_MODEL, default qwen3:8b).
 * For comparison the SAME messages (same fragments, same prompt) go to the cloud model from .env
 * (HINDSIGHT_API_LLM_BASE_URL / HINDSIGHT_API_LLM_MODEL / LLM_API_KEY) — skipped if no key or unreachable.
 *
 * Measured per answer: quality = share of the expected facts found (regexes) + citations [n] present;
 * for the off-topic question — whether the model said the course does not cover it; speed = wall time,
 * time to the first token (local), tokens/s; stability = every question is asked TASK28_RUNS times
 * (default 2): errors, empty answers, spread of the fact score between runs.
 * Writes task28-local-rag-report.md.
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask28
 *   TASK28_RUNS=3 mvn -q exec:java -Ptask28
 *   TASK28_CLOUD=0 mvn -q exec:java -Ptask28          # local only
 */
public final class Task28 {

    private static final Path REPORT = Path.of("task28-local-rag-report.md");
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?(</think>|$)");
    private static final Pattern CITE = Pattern.compile("\\[\\d{1,2}]");
    private static final Pattern NOT_IN_COURSE = Pattern.compile("(?iu)в материалах курса (этого )?нет|нет в материалах|курс[а-я]* не (содерж|рассматр|охват)|вне курса");

    /** Control question: facts = regexes the answer must contain; source = regex over the expected lesson file. */
    record Q(String id, String question, List<String> facts, String source, boolean offTopic) {
    }

    static final List<Q> QUESTIONS = List.of(
            new Q("little", "Как связаны число одновременных пользователей, интенсивность и время по закону Литтла?",
                    List.of("L\\s*=\\s*λ\\s*[×xх*·]\\s*W", "(?iu)одновремен|в системе"), "Закон Литтла", false),
            new Q("models", "Чем открытая модель нагрузки отличается от закрытой?",
                    List.of("(?iu)частот|интенсивн|приход|constantUsersPerSec|в секунду",
                            "(?iu)фиксирован.{0,40}(пользовател|числ)|числ.{0,30}пользовател.{0,30}(фиксир|постоян)|constantConcurrentUsers"),
                    "Модели тестирования", false),
            new Q("pacing", "Что такое pacing в Gatling и зачем он нужен?",
                    List.of("(?iu)фиксирован.{0,30}частот|частот.{0,30}итерац|раз в", "(?iu)pace|цикл"), "Pacing", false),
            new Q("percentile", "Что показывает 95-й перцентиль времени отклика?",
                    List.of("95\\s*(%|процент)", "(?iu)быстрее|меньше|ниже"), "Метрики производительности", false),
            new Q("random", "Как воспроизвести профиль нагрузки с разными операциями через randomSwitch?",
                    List.of("(?iu)randomSwitch", "(?iu)процент|вероятност|дол[яию]|вес"), "randomSwitch", false),
            new Q("soak", "Зачем нужен soak-тест (тест стабильности) и что он выявляет?",
                    List.of("(?iu)длительн|долг|час", "(?iu)утечк|деградац|накоплен|памят"), "Soak", false),
            new Q("offtopic", "Как приготовить борщ?", List.of(), "", true));

    record Run(String engine, Q q, int run, String answer, double factScore, boolean cites, boolean ok,
               long wallMs, long firstTokenMs, int outTokens, double tokPerSec, String error) {
    }

    record Retrieval(Q q, List<StudyAssistant.Source> sources, boolean expectedFound, long ms) {
    }

    private Task28() {
    }

    public static void main(String[] args) throws Exception {
        String localModel = LlmClient.env("TASK28_LOCAL_MODEL", LlmClient.env("TASK27_MODEL", "qwen3:8b"));
        int runs = Integer.parseInt(LlmClient.env("TASK28_RUNS", "2"));
        boolean cloudOn = !LlmClient.env("TASK28_CLOUD", "1").matches("(?i)0|false|off|no")
                && !LlmClient.env("LLM_API_KEY", "").isBlank();
        LlmClient.Config cloud = cloudOn ? new LlmClient.Config(LlmClient.env("LLM_API_KEY", ""),
                LlmClient.env("HINDSIGHT_API_LLM_BASE_URL", LlmClient.DEFAULT_BASE_URL),
                LlmClient.env("HINDSIGHT_API_LLM_MODEL", LlmClient.DEFAULT_MODEL)) : null;

        OllamaClient ollama = OllamaClient.fromEnv();
        String version;
        try {
            version = ollama.version();
        } catch (Exception e) {
            System.err.println("❌ " + e.getMessage() + "\nЗапустите Ollama: brew services start ollama");
            System.exit(1);
            return;
        }
        if (ollama.find(localModel) == null) {
            System.err.println("❌ Нет локальной модели " + localModel + ": ollama pull " + localModel);
            System.exit(1);
            return;
        }
        CourseLibrary lib = CourseLibrary.load(CourseLibrary.resolveRoot());
        try (HttpClient http = LlmClient.newHttpClient()) {
            StudyAssistant rag = StudyAssistant.open(lib, ollama, localModel, http);
            System.out.println("Локально: индекс " + rag.chunkCount() + " фрагментов (" + rag.embedderId() + "), модель " + localModel
                    + " в Ollama " + version + (cloud != null ? "; облако: " + cloud.model() + " (" + cloud.baseUrl() + ")" : "; облако: выключено"));

            List<Retrieval> retrievals = new ArrayList<>();
            List<Run> results = new ArrayList<>();
            Map<String, Object> opts = new LinkedHashMap<>();
            opts.put("num_ctx", rag.settings().numCtx());
            opts.put("temperature", 0.3);
            boolean cloudReachable = cloud != null;

            for (Q q : QUESTIONS) {
                StudyAssistant.RagRequest req = rag.prepare(q.question());
                boolean found = q.offTopic() || req.sources().stream().anyMatch(s ->
                        s.lessonTitle().toLowerCase(Locale.ROOT).contains(q.source().toLowerCase(Locale.ROOT))
                                || s.lessonId().toLowerCase(Locale.ROOT).contains(q.source().toLowerCase(Locale.ROOT)));
                retrievals.add(new Retrieval(q, req.sources(), found, req.retrievalMs()));
                System.out.printf("%n> %s  [поиск %d мс, нужный урок в топ-%d: %s]%n", q.question(), req.retrievalMs(),
                        req.sources().size(), q.offTopic() ? "—" : found ? "да" : "нет");
                for (int i = 1; i <= runs; i++) {
                    results.add(print(runLocal(ollama, localModel, req, opts, q, i)));
                    if (cloudReachable) {
                        Run c = print(runCloud(http, cloud, req, q, i));
                        results.add(c);
                        if (c.error() != null && c.error().startsWith("нет связи")) {
                            cloudReachable = false; // do not wait for timeouts on every question
                            System.out.println("  облако недоступно — дальше только локальная модель");
                        }
                    }
                }
            }
            writeReport(version, localModel, cloud, rag, runs, retrievals, results);
            System.out.println("\nОтчёт: " + REPORT);
        }
    }

    // ---------------------------------------------------------------- one answer

    private static Run runLocal(OllamaClient ollama, String model, StudyAssistant.RagRequest req, Map<String, Object> opts,
                                Q q, int run) {
        long t0 = System.nanoTime();
        long[] first = {-1};
        try {
            OllamaClient.ChatResult r = ollama.chatStream(model, req.messages(), opts, false, null, d -> {
                if (first[0] < 0 && !d.content().isBlank()) {
                    first[0] = (System.nanoTime() - t0) / 1_000_000;
                }
            });
            return score("локально", q, run, r.content(), (System.nanoTime() - t0) / 1_000_000, first[0],
                    r.outputTokens(), r.tokensPerSecond(), null);
        } catch (Exception e) {
            return score("локально", q, run, "", (System.nanoTime() - t0) / 1_000_000, -1, 0, 0, e.getMessage());
        }
    }

    private static Run runCloud(HttpClient http, LlmClient.Config cfg, StudyAssistant.RagRequest req, Q q, int run) {
        long t0 = System.nanoTime();
        try {
            JsonNode r = LlmClient.send(http, cfg, req.messages(), 1500, null, 0.3);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int out = LlmClient.usage(r).completionTokens();
            return score("облако", q, run, LlmClient.content(r), ms, -1, Math.max(out, 0),
                    out > 0 ? out / (ms / 1000.0) : 0, null);
        } catch (LlmClient.RequestException e) {
            return score("облако", q, run, "", (System.nanoTime() - t0) / 1_000_000, -1, 0, 0,
                    "HTTP " + e.status() + ": " + clip(e.body().replaceAll("\\s+", " "), 160));
        } catch (Exception e) {
            return score("облако", q, run, "", (System.nanoTime() - t0) / 1_000_000, -1, 0, 0,
                    "нет связи: " + e.getClass().getSimpleName() + " " + clip(String.valueOf(e.getMessage()), 120));
        }
    }

    private static Run score(String engine, Q q, int run, String raw, long ms, long firstMs, int out, double tps, String error) {
        String answer = THINK.matcher(raw == null ? "" : raw).replaceAll("").strip();
        double facts;
        boolean ok;
        if (q.offTopic()) {
            facts = NOT_IN_COURSE.matcher(answer).find() ? 1 : 0;
            ok = error == null && facts == 1;
        } else {
            long found = q.facts().stream().filter(f -> Pattern.compile(f).matcher(answer).find()).count();
            facts = q.facts().isEmpty() ? 1 : found / (double) q.facts().size();
            ok = error == null && !answer.isEmpty() && facts >= 0.5;
        }
        return new Run(engine, q, run, answer, facts, CITE.matcher(answer).find(), ok, ms, firstMs, out, tps, error);
    }

    private static Run print(Run r) {
        System.out.printf(Locale.ROOT, "  %-9s #%d  %s  факты %.0f%%  ссылки %s  %.1f с  %s%n", r.engine(), r.run(),
                r.ok() ? "✓" : "✗", r.factScore() * 100, r.cites() ? "да" : "нет", r.wallMs() / 1000.0,
                r.error() != null ? "ошибка: " + r.error() : String.format(Locale.ROOT, "%d ток., %.1f ток/с", r.outTokens(), r.tokPerSec()));
        return r;
    }

    // ---------------------------------------------------------------- report

    private static void writeReport(String version, String localModel, LlmClient.Config cloud, StudyAssistant rag, int runs,
                                    List<Retrieval> retrievals, List<Run> results) throws Exception {
        StringBuilder r = new StringBuilder();
        r.append("# День 28. Локальная LLM + RAG: сравнение с облачной моделью\n\n");
        r.append("> Сгенерировано `Task28` (`mvn -q compile exec:java -Ptask28`) ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(".\n\n");
        r.append("## Что работает локально\n\n| Звено | Где выполняется |\n|---|---|\n");
        r.append("| Индекс | `task27-course-index.json` — индекс курса недели 6: ").append(rag.chunkCount())
                .append(" фрагментов, эмбеддер `").append(rag.embedderId()).append("` ")
                .append(rag.embedderId().startsWith("api:") ? "(embedding-модель в локальной Ollama)" : "(офлайн, без сети)").append(" |\n");
        r.append("| Retrieval | косинусный поиск внутри JVM (`StudyAssistant.prepare`), без сети |\n");
        r.append("| Генерация | `").append(localModel).append("` в Ollama ").append(version).append(" на этом компьютере |\n");
        r.append("| Для сравнения | ").append(cloud == null ? "облачная модель выключена (нет `LLM_API_KEY` или `TASK28_CLOUD=0`)"
                : "`" + cloud.model() + "` (" + cloud.baseUrl() + ") — получает ТОТ ЖЕ промпт с теми же фрагментами").append(" |\n\n");

        r.append("## Итог\n\n| Критерий | Локально | Облако |\n|---|---|---|\n");
        Map<String, List<Run>> by = new LinkedHashMap<>();
        for (Run x : results) {
            by.computeIfAbsent(x.engine(), k -> new ArrayList<>()).add(x);
        }
        List<Run> loc = by.getOrDefault("локально", List.of());
        List<Run> cl = by.getOrDefault("облако", List.of());
        r.append(row("Верных ответов", ok(loc), ok(cl)));
        r.append(row("Найдено ожидаемых фактов (среднее)", pct(loc), pct(cl)));
        r.append(row("Ответы со ссылками [n]", cites(loc), cites(cl)));
        r.append(row("Вопрос не по теме → «в курсе этого нет»", offTopic(loc), offTopic(cl)));
        r.append(row("Время ответа: медиана / максимум", time(loc), time(cl)));
        r.append(row("Первый токен (медиана)", first(loc), "— (ответ целиком)"));
        r.append(row("Скорость генерации", tps(loc), tps(cl)));
        r.append(row("Ошибки / пустые ответы", errors(loc), errors(cl)));
        r.append(row("Разброс качества между " + runs + " прогонами", spread(loc), spread(cl)));
        long hit = retrievals.stream().filter(x -> !x.q().offTopic() && x.expectedFound()).count();
        long total = retrievals.stream().filter(x -> !x.q().offTopic()).count();
        r.append("\nRetrieval (общий для обеих моделей): нужный урок среди найденных фрагментов в ").append(hit).append(" из ")
                .append(total).append(" вопросов, поиск ").append(retrievals.stream().mapToLong(Retrieval::ms).max().orElse(0))
                .append(" мс максимум.\n");

        r.append("\n## По вопросам\n\n| Вопрос | Модель | Прогон | Верно | Факты | Ссылки | Время | Ток/с |\n|---|---|---|---|---|---|---|---|\n");
        for (Run x : results) {
            r.append("| ").append(x.q().id()).append(" | ").append(x.engine()).append(" | ").append(x.run()).append(" | ")
                    .append(x.ok() ? "✅" : "❌").append(" | ").append(String.format(Locale.ROOT, "%.0f%%", x.factScore() * 100))
                    .append(" | ").append(x.cites() ? "да" : "нет").append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f с", x.wallMs() / 1000.0)).append(" | ")
                    .append(x.error() != null ? "ошибка" : String.format(Locale.ROOT, "%.1f", x.tokPerSec())).append(" |\n");
        }

        r.append("\n## Ответы (первый прогон)\n");
        for (Retrieval rt : retrievals) {
            r.append("\n### ").append(rt.q().question()).append("\n\n");
            r.append("Найденные фрагменты: ");
            List<String> src = new ArrayList<>();
            for (StudyAssistant.Source s : rt.sources()) {
                src.add("[" + s.n() + "] " + s.lessonTitle() + (s.section().isBlank() ? "" : " › " + s.section()));
            }
            r.append(String.join("; ", src)).append("\n");
            for (Run x : results) {
                if (x.q() == rt.q() && x.run() == 1) {
                    r.append("\n**").append(x.engine()).append("** — ").append(x.ok() ? "✅" : "❌")
                            .append(x.error() != null ? " ошибка: " + x.error() : "").append("\n\n");
                    r.append(x.answer().isBlank() ? "> (нет ответа)" : x.answer().lines().map(l -> "> " + l)
                            .reduce((a, b) -> a + "\n" + b).orElse(">")).append("\n");
                }
            }
        }
        r.append("\n## Как запустить\n\n```bash\nollama pull ").append(localModel)
                .append("\nmvn -q compile exec:java -Ptask28             # локально + облако из .env\n")
                .append("TASK28_CLOUD=0 mvn -q exec:java -Ptask28       # только локально\n")
                .append("TASK28_RUNS=3 mvn -q exec:java -Ptask28        # больше повторов для оценки стабильности\n```\n");
        Files.writeString(REPORT, r.toString(), StandardCharsets.UTF_8);
    }

    private static String row(String name, String a, String b) {
        return "| " + name + " | " + a + " | " + b + " |\n";
    }

    private static String ok(List<Run> l) {
        return l.isEmpty() ? "—" : l.stream().filter(Run::ok).count() + " из " + l.size();
    }

    private static String pct(List<Run> l) {
        List<Run> on = l.stream().filter(x -> !x.q().offTopic()).toList();
        return on.isEmpty() ? "—" : String.format(Locale.ROOT, "%.0f%%", on.stream().mapToDouble(Run::factScore).average().orElse(0) * 100);
    }

    private static String cites(List<Run> l) {
        List<Run> on = l.stream().filter(x -> !x.q().offTopic() && x.error() == null).toList();
        return on.isEmpty() ? "—" : on.stream().filter(Run::cites).count() + " из " + on.size();
    }

    private static String offTopic(List<Run> l) {
        List<Run> off = l.stream().filter(x -> x.q().offTopic()).toList();
        return off.isEmpty() ? "—" : off.stream().filter(Run::ok).count() + " из " + off.size();
    }

    private static String time(List<Run> l) {
        long[] t = l.stream().filter(x -> x.error() == null).mapToLong(Run::wallMs).sorted().toArray();
        return t.length == 0 ? "—" : String.format(Locale.ROOT, "%.1f с / %.1f с", t[t.length / 2] / 1000.0, t[t.length - 1] / 1000.0);
    }

    private static String first(List<Run> l) {
        long[] t = l.stream().filter(x -> x.firstTokenMs() >= 0).mapToLong(Run::firstTokenMs).sorted().toArray();
        return t.length == 0 ? "—" : String.format(Locale.ROOT, "%.1f с", t[t.length / 2] / 1000.0);
    }

    private static String tps(List<Run> l) {
        double avg = l.stream().filter(x -> x.tokPerSec() > 0).mapToDouble(Run::tokPerSec).average().orElse(0);
        return avg == 0 ? "—" : String.format(Locale.ROOT, "%.1f ток/с", avg);
    }

    private static String errors(List<Run> l) {
        return l.isEmpty() ? "—" : l.stream().filter(x -> x.error() != null || x.answer().isBlank()).count() + " из " + l.size();
    }

    /** Max difference of the fact score between runs of the same question, averaged over questions. */
    private static String spread(List<Run> l) {
        Map<String, List<Double>> byQ = new LinkedHashMap<>();
        for (Run x : l) {
            if (x.error() == null) {
                byQ.computeIfAbsent(x.q().id(), k -> new ArrayList<>()).add(x.factScore());
            }
        }
        double s = byQ.values().stream().filter(v -> v.size() > 1)
                .mapToDouble(v -> v.stream().mapToDouble(d -> d).max().orElse(0) - v.stream().mapToDouble(d -> d).min().orElse(0))
                .average().orElse(-1);
        return s < 0 ? "—" : String.format(Locale.ROOT, "%.0f п.п.", s * 100);
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
