package ru.lemanapro.aiadventchallenge.week6;

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
 * Day 29: optimizing the local LLM for the course tutor (day 27) — three levels of settings, measured automatically.
 *
 *   Level 1 «Базовый»     — what you get without tuning: temperature 0.8, num_ctx 2048 (the context is truncated),
 *                           no output cap, 8 fragments, a generic one-line prompt.
 *   Level 2 «Средний»     — the day-27 app as shipped: temperature 0.3, num_ctx 4096, num_predict 700, 5 fragments,
 *                           the TUTOR prompt with citations and the «нет в материалах» rule.
 *   Level 3 «Оптимальный» — tuned for this case: temperature 0.1 + top_p 0.9 + repeat_penalty 1.1, num_ctx 6144,
 *                           num_predict 450, 4 fragments, a course-specific template (fixed answer structure,
 *                           a one-shot example, explicit citation and refusal rules).
 *   Quantization          — level 3 is also run on another installed qwen3 variant (TASK29_ALT_MODEL, or the first
 *                           qwen3 tag in Ollama other than the main one: e.g. qwen3:8b-q8_0, qwen3:4b), if any.
 *
 * Same local retrieval for all levels (course index of week 6). Questions and quality checks = day 28
 * (facts by regex, citations [n], off-topic refusal). Speed: wall time, first token, tok/s, model load time.
 * Resources: model memory from /api/ps (grows with num_ctx), prompt tokens. Every question × level runs once
 * (TASK29_RUNS to repeat). Writes task29-optimization-report.md with recommended settings for the app.
 *
 * Usage:  mvn -q compile exec:java -Ptask29
 *         TASK29_LIMIT=3 mvn -q exec:java -Ptask29      # first 3 questions only (quick check)
 */
public final class Task29 {

    private static final Path REPORT = Path.of("task29-optimization-report.md");

    record Level(String id, String name, String model, double temperature, Double topP, Double repeatPenalty,
                 int numCtx, int numPredict, int topK, int contextChars, String prompt) {
    }

    record Result(Level level, Task28.Q q, int run, String answer, double facts, boolean cites, boolean ok,
                  boolean truncated, int promptTokens, int outTokens, double tps, long wallMs, long firstMs,
                  long loadMs, String error) {
    }

    record Usage(Level level, long memBytes, long vramBytes) {
    }

    static final String GENERIC = "Ответь на вопрос, используя контекст.";

    static final String OPTIMAL = """
            Ты — наставник курса «Тестирование производительности на Gatling». Отвечаешь студенту по фрагментам курса.

            Шаг 1. Проверь, отвечают ли фрагменты на вопрос. Если вопрос не о нагрузочном тестировании или во фрагментах \
            ответа нет — напиши ровно «В материалах курса этого нет.» и закончи ответ.
            Шаг 2. Иначе отвечай в формате ниже.

            Формат ответа (строго):
            1. Суть — 2–4 предложения простым языком. После каждого факта из фрагмента — его номер: [1], [2].
            2. «Как это в Gatling / на практике» — 1–3 пункта (код или конфигурация — в блоке ```), только если это есть во фрагментах.
            3. Если нужна формула — простым текстом, например L = λ × W, без LaTeX.

            Правила:
            - Используй только фрагменты. Не придумывай цифры, названия уроков и методы Gatling, которых в них нет.
            - Пиши по-русски, без вступлений и без повторения вопроса. Не длиннее 180 слов.

            Пример:
            Вопрос: Что такое think time?
            Ответ: Think time — пауза между действиями пользователя, имитирующая время на чтение и ввод [2]. Он удлиняет \
            сессию и снижает RPS на одного пользователя [2].
            Как это в Gatling: pause(Duration.ofSeconds(5)) между запросами сценария [3].

            Пример, когда во фрагментах ответа нет:
            Вопрос: Как настроить кластер Kubernetes?
            Ответ: В материалах курса этого нет.""";

    private Task29() {
    }

    static List<Level> levels(String model) {
        return List.of(
                new Level("1", "Базовый", model, 0.8, null, null, 2048, -1, 8, 9000, GENERIC),
                new Level("2", "Средний", model, 0.3, null, null, 4096, 700, 5, 6000,
                        StudyAssistant.TUTOR + StudyAssistant.NOT_FOUND_RULE),
                new Level("3", "Оптимальный", model, 0.1, 0.9, 1.1, 6144, 450, 4, 5200, OPTIMAL));
    }

    public static void main(String[] args) throws Exception {
        String model = LlmClient.env("TASK29_MODEL", LlmClient.env("TASK27_MODEL", "qwen3:8b"));
        int runs = Integer.parseInt(LlmClient.env("TASK29_RUNS", "1"));
        int limit = Integer.parseInt(LlmClient.env("TASK29_LIMIT", "99"));
        OllamaClient ollama = OllamaClient.fromEnv();
        String version;
        try {
            version = ollama.version();
        } catch (Exception e) {
            System.err.println("❌ " + e.getMessage() + "\nЗапустите Ollama: brew services start ollama");
            System.exit(1);
            return;
        }
        OllamaClient.ModelInfo main = ollama.find(model);
        if (main == null) {
            System.err.println("❌ Нет модели " + model + ": ollama pull " + model);
            System.exit(1);
            return;
        }
        List<Level> levels = new ArrayList<>(levels(model));
        OllamaClient.ModelInfo alt = altModel(ollama, main);
        if (alt != null) {
            Level l3 = levels.get(2);
            levels.add(new Level("3q", "Оптимальный · " + alt.name(), alt.name(), l3.temperature(), l3.topP(), l3.repeatPenalty(),
                    l3.numCtx(), l3.numPredict(), l3.topK(), l3.contextChars(), l3.prompt()));
        }
        List<Task28.Q> questions = Task28.QUESTIONS.subList(0, Math.min(limit, Task28.QUESTIONS.size()));
        // keep the off-topic check even in a quick run
        if (questions.stream().noneMatch(Task28.Q::offTopic)) {
            questions = new ArrayList<>(questions);
            questions.add(Task28.QUESTIONS.getLast());
        }

        CourseLibrary lib = CourseLibrary.load(CourseLibrary.resolveRoot());
        List<Result> results = new ArrayList<>();
        List<Usage> usage = new ArrayList<>();
        try (HttpClient http = HttpClient.newHttpClient()) {
            StudyAssistant rag = StudyAssistant.open(lib, ollama, model, http);
            System.out.println("Модель " + model + " (" + main.parameterSize() + ", " + main.quantization() + "), Ollama " + version
                    + (alt != null ? "; вариант квантования: " + alt.name() + " (" + alt.quantization() + ")" : "; другого варианта qwen3 нет"));
            for (Level lv : levels) {
                System.out.printf("%n=== Уровень %s «%s»: t=%.1f, num_ctx=%d, num_predict=%s, фрагментов %d ===%n", lv.id(), lv.name(),
                        lv.temperature(), lv.numCtx(), lv.numPredict() > 0 ? lv.numPredict() : "∞", lv.topK());
                boolean firstCall = true;
                for (Task28.Q q : questions) {
                    StudyAssistant.Context ctx = rag.context(q.question(), lv.topK(), lv.contextChars());
                    for (int i = 1; i <= runs; i++) {
                        Result r = ask(ollama, lv, q, ctx, i);
                        if (!firstCall) { // load time belongs to the level, not to each question
                            r = new Result(r.level(), r.q(), r.run(), r.answer(), r.facts(), r.cites(), r.ok(), r.truncated(),
                                    r.promptTokens(), r.outTokens(), r.tps(), r.wallMs(), r.firstMs(), 0, r.error());
                        }
                        firstCall = false;
                        results.add(r);
                        System.out.printf(Locale.ROOT, "  %-10s %s факты %3.0f%%  ссылки %-3s  %5.1f с  %4d ток.  %5.1f ток/с%s%s%n",
                                q.id(), r.ok() ? "✓" : "✗", r.facts() * 100, r.cites() ? "да" : "нет", r.wallMs() / 1000.0,
                                r.outTokens(), r.tps(), r.truncated() ? "  [обрезан]" : "", r.error() != null ? "  ошибка: " + r.error() : "");
                    }
                }
                usage.add(memory(ollama, lv));
            }
        }
        writeReport(version, main, alt, levels, questions, results, usage, runs);
        System.out.println("\nОтчёт: " + REPORT);
    }

    private static OllamaClient.ModelInfo altModel(OllamaClient ollama, OllamaClient.ModelInfo main) throws Exception {
        String wanted = LlmClient.env("TASK29_ALT_MODEL", "");
        if (!wanted.isBlank()) {
            return ollama.find(wanted);
        }
        String family = main.name().split(":")[0];
        return ollama.models().stream().filter(m -> m.name().startsWith(family + ":") && !m.name().equals(main.name()))
                .findFirst().orElse(null);
    }

    private static Result ask(OllamaClient ollama, Level lv, Task28.Q q, StudyAssistant.Context ctx, int run) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("num_ctx", lv.numCtx());
        o.put("temperature", lv.temperature());
        if (lv.numPredict() > 0) {
            o.put("num_predict", lv.numPredict());
        }
        if (lv.topP() != null) {
            o.put("top_p", lv.topP());
        }
        if (lv.repeatPenalty() != null) {
            o.put("repeat_penalty", lv.repeatPenalty());
        }
        String user = lv.id().equals("1")
                ? "Контекст:\n" + ctx.fragments() + "\n\nВопрос: " + q.question()
                : "Фрагменты курса:\n" + ctx.fragments() + "\n\nВопрос: " + q.question();
        List<Map<String, String>> messages = List.of(LlmClient.message("system", lv.prompt()), LlmClient.message("user", user));
        long t0 = System.nanoTime();
        long[] first = {-1};
        try {
            OllamaClient.ChatResult r = ollama.chatStream(lv.model(), messages, o, false, null, d -> {
                if (first[0] < 0 && !d.content().isBlank()) {
                    first[0] = (System.nanoTime() - t0) / 1_000_000;
                }
            });
            String a = Task28.THINK.matcher(r.content()).replaceAll("").strip();
            double facts;
            boolean ok;
            if (q.offTopic()) {
                facts = Task28.NOT_IN_COURSE.matcher(a).find() ? 1 : 0;
                ok = facts == 1;
            } else {
                facts = q.facts().stream().filter(f -> Pattern.compile(f).matcher(a).find()).count() / (double) q.facts().size();
                ok = !a.isEmpty() && facts >= 0.5;
            }
            return new Result(lv, q, run, a, facts, Task28.CITE.matcher(a).find(), ok, "length".equals(r.doneReason()),
                    r.promptTokens(), r.outputTokens(), r.tokensPerSecond(), (System.nanoTime() - t0) / 1_000_000, first[0],
                    r.loadNs() / 1_000_000, null);
        } catch (Exception e) {
            return new Result(lv, q, run, "", 0, false, false, false, 0, 0, 0, (System.nanoTime() - t0) / 1_000_000, -1, 0,
                    e.getMessage());
        }
    }

    private static Usage memory(OllamaClient ollama, Level lv) {
        try {
            for (OllamaClient.LoadedModel m : ollama.loaded()) {
                if (m.name().equals(lv.model()) || m.name().equals(lv.model() + ":latest")) {
                    return new Usage(lv, m.sizeBytes(), m.sizeVram());
                }
            }
        } catch (Exception ignored) {
            // memory column stays empty
        }
        return new Usage(lv, 0, 0);
    }

    // ---------------------------------------------------------------- report

    private static List<Result> of(List<Result> all, Level lv) {
        return all.stream().filter(r -> r.level() == lv).toList();
    }

    private static double quality(List<Result> rs) {
        // 60% facts, 25% citations on course questions, 15% correct refusal off-topic
        List<Result> on = rs.stream().filter(r -> !r.q().offTopic()).toList();
        List<Result> off = rs.stream().filter(r -> r.q().offTopic()).toList();
        double f = on.stream().mapToDouble(Result::facts).average().orElse(0);
        double c = on.stream().filter(Result::cites).count() / (double) Math.max(1, on.size());
        double o = off.isEmpty() ? 1 : off.stream().filter(Result::ok).count() / (double) off.size();
        return 0.6 * f + 0.25 * c + 0.15 * o;
    }

    private static long median(long[] v) {
        if (v.length == 0) {
            return 0;
        }
        java.util.Arrays.sort(v);
        return v[v.length / 2];
    }

    private static void writeReport(String version, OllamaClient.ModelInfo main, OllamaClient.ModelInfo alt, List<Level> levels,
                                    List<Task28.Q> questions, List<Result> results, List<Usage> usage, int runs) throws Exception {
        StringBuilder r = new StringBuilder();
        r.append("# День 29. Оптимизация локальной LLM под наставника курса\n\n");
        r.append("> Сгенерировано `Task29` (`mvn -q compile exec:java -Ptask29`) ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(". Модель `")
                .append(main.name()).append("` (").append(main.parameterSize()).append(", ").append(main.quantization())
                .append("), Ollama ").append(version).append(", ").append(questions.size()).append(" вопросов × ").append(runs)
                .append(" прогон(ов) на уровень. Поиск по курсу одинаковый для всех уровней (индекс недели 6, локально).\n\n");

        r.append("## Уровни настроек\n\n| | ");
        levels.forEach(l -> r.append(l.id()).append(" · ").append(l.name()).append(" | "));
        r.append("\n|---|").append("---|".repeat(levels.size())).append("\n");
        r.append(paramRow("Модель / квантование", levels, l -> "`" + l.model() + "`"));
        r.append(paramRow("temperature", levels, l -> String.valueOf(l.temperature())));
        r.append(paramRow("top_p / repeat_penalty", levels, l -> l.topP() == null ? "по умолчанию" : l.topP() + " / " + l.repeatPenalty()));
        r.append(paramRow("context window (num_ctx)", levels, l -> String.valueOf(l.numCtx())));
        r.append(paramRow("max tokens (num_predict)", levels, l -> l.numPredict() > 0 ? String.valueOf(l.numPredict()) : "без лимита"));
        r.append(paramRow("Фрагментов / символов контекста", levels, l -> l.topK() + " / " + l.contextChars()));
        r.append(paramRow("Промпт", levels, l -> l.id().equals("1") ? "общий: «" + GENERIC + "»"
                : l.id().equals("2") ? "промпт приложения дня 27" : "шаблон под курс: структура, пример, правила"));

        r.append("\n## Результаты\n\n| Метрика | ");
        levels.forEach(l -> r.append(l.id()).append(" · ").append(l.name()).append(" | "));
        r.append("\n|---|").append("---|".repeat(levels.size())).append("\n");
        r.append(metricRow("**Индекс качества** (0–100)", levels, results, rs -> String.format(Locale.ROOT, "**%.0f**", quality(rs) * 100)));
        r.append(metricRow("Верных ответов", levels, results, rs -> rs.stream().filter(Result::ok).count() + " из " + rs.size()));
        r.append(metricRow("Найдено ожидаемых фактов", levels, results, rs -> String.format(Locale.ROOT, "%.0f%%",
                rs.stream().filter(x -> !x.q().offTopic()).mapToDouble(Result::facts).average().orElse(0) * 100)));
        r.append(metricRow("Ответы со ссылками [n]", levels, results, rs -> rs.stream().filter(x -> !x.q().offTopic() && x.cites()).count()
                + " из " + rs.stream().filter(x -> !x.q().offTopic()).count()));
        r.append(metricRow("Вопрос не по теме → отказ", levels, results, rs -> rs.stream().filter(x -> x.q().offTopic() && x.ok()).count()
                + " из " + rs.stream().filter(x -> x.q().offTopic()).count()));
        r.append(metricRow("Обрезано по лимиту / ошибки", levels, results, rs -> rs.stream().filter(Result::truncated).count() + " / "
                + rs.stream().filter(x -> x.error() != null).count()));
        r.append(metricRow("Время ответа (медиана)", levels, results, rs -> String.format(Locale.ROOT, "%.1f с",
                median(rs.stream().filter(x -> x.error() == null).mapToLong(Result::wallMs).toArray()) / 1000.0)));
        r.append(metricRow("Первый токен (медиана)", levels, results, rs -> String.format(Locale.ROOT, "%.1f с",
                median(rs.stream().filter(x -> x.firstMs() >= 0).mapToLong(Result::firstMs).toArray()) / 1000.0)));
        r.append(metricRow("Скорость генерации", levels, results, rs -> String.format(Locale.ROOT, "%.1f ток/с",
                rs.stream().filter(x -> x.tps() > 0).mapToDouble(Result::tps).average().orElse(0))));
        r.append(metricRow("Длина ответа (медиана, токенов)", levels, results, rs -> String.valueOf(
                median(rs.stream().filter(x -> x.error() == null).mapToLong(Result::outTokens).toArray()))));
        r.append(metricRow("Токенов промпта (медиана)", levels, results, rs -> String.valueOf(
                median(rs.stream().filter(x -> x.error() == null).mapToLong(Result::promptTokens).toArray()))));
        r.append(metricRow("Загрузка модели при смене настроек", levels, results, rs -> String.format(Locale.ROOT, "%.1f с",
                rs.stream().mapToLong(Result::loadMs).max().orElse(0) / 1000.0)));
        r.append("| Память модели (/api/ps) | ");
        for (Level l : levels) {
            Usage u = usage.stream().filter(x -> x.level() == l).findFirst().orElse(null);
            r.append(u == null || u.memBytes() == 0 ? "—" : String.format(Locale.ROOT, "%.2f ГБ%s", u.memBytes() / 1e9,
                    u.vramBytes() > 0 ? " (GPU " + Math.round(100.0 * u.vramBytes() / u.memBytes()) + "%)" : " (CPU)")).append(" | ");
        }
        r.append("\n\nИндекс качества = 60% найденные факты + 25% ссылки на источники + 15% правильный отказ на вопрос не по теме.\n");

        r.append("\n## По вопросам\n\n| Вопрос | ");
        levels.forEach(l -> r.append(l.id()).append(" | "));
        r.append("\n|---|").append("---|".repeat(levels.size())).append("\n");
        for (Task28.Q q : questions) {
            r.append("| ").append(q.id()).append(" | ");
            for (Level l : levels) {
                List<Result> rs = results.stream().filter(x -> x.level() == l && x.q() == q).toList();
                r.append(rs.stream().map(x -> (x.ok() ? "✅" : "❌") + String.format(Locale.ROOT, " %.0f%%%s %.1fс", x.facts() * 100,
                        x.cites() ? " [n]" : "", x.wallMs() / 1000.0)).reduce((a, b) -> a + "<br>" + b).orElse("—")).append(" | ");
            }
            r.append("\n");
        }

        // recommendation
        Level best = levels.getFirst();
        double bestScore = -1;
        for (Level l : levels) {
            List<Result> rs = of(results, l);
            double score = quality(rs) * 100 - median(rs.stream().mapToLong(Result::wallMs).toArray()) / 1000.0 * 0.5; // 1 point per 2 s
            if (score > bestScore) {
                bestScore = score;
                best = l;
            }
        }
        r.append("\n## Рекомендации для приложения курса (день 27)\n\n");
        r.append("Лучший баланс качества и скорости в этом прогоне — уровень **").append(best.id()).append(" «").append(best.name())
                .append("»** (индекс качества минус 1 балл за каждые 2 секунды медианного ответа).\n\n");
        r.append("Запуск приложения с этими параметрами:\n\n```bash\n")
                .append("TASK27_MODEL=").append(best.model())
                .append(" TASK27_TEMPERATURE=").append(best.temperature())
                .append(" TASK27_NUM_CTX=").append(Math.max(best.numCtx(), 8192))
                .append(" TASK27_NUM_PREDICT=").append(best.numPredict() > 0 ? best.numPredict() : 700)
                .append(" \\\n  TASK27_TOP_K=").append(best.topK())
                .append(" TASK27_CONTEXT_CHARS=").append(best.contextChars())
                .append(" mvn -q compile exec:java -Ptask27\n```\n\n");
        r.append("""
                Почему так:
                - **temperature 0.1–0.3.** Наставник пересказывает материал курса, а не сочиняет: низкая температура даёт \
                стабильные ответы с теми же фактами, высокая (0.8) — «творческие» перефразы и выдуманные детали.
                - **num_ctx.** 2048 токенов не вмещают системный промпт + 8 фрагментов — Ollama молча обрезает начало \
                контекста, и модель теряет инструкции (ссылки, отказ). Для ответов хватает 6144; в приложении оставьте \
                **8192**, потому что «Конспект» отдаёт модели урок целиком (до 14 000 символов ≈ 5 000 токенов). \
                Больше окно — больше памяти под KV-кэш (строка «Память модели») и дольше первый токен.
                - **num_predict 450–700.** Ограничивает «простыни» и время ответа; при слишком малом лимите растёт \
                число обрезанных ответов (строка «Обрезано по лимиту») — тогда лимит надо поднять.
                - **4–5 фрагментов.** Меньше шума в контексте → короче промпт и быстрее первый токен; при лексическом \
                поиске (без embedding-модели) лучше 5, со смысловым (`ollama pull bge-m3`) хватает 4.
                - **Промпт-шаблон уровня 3** (структура «суть + как в Gatling», пример, жёсткое правило отказа) — \
                переносится в `StudyAssistant.TUTOR`, если по таблице он дал больше ссылок и правильных отказов.
                - **Квантование.** `qwen3:8b` по умолчанию = Q4_K_M — лучший компромисс для 16–24 ГБ памяти. \
                `qwen3:8b-q8_0` точнее, но вдвое больше памяти и медленнее; `qwen3:4b` быстрее, но слабее в рассуждениях.
                """);
        if (alt == null) {
            r.append("\nВ этом прогоне другого варианта qwen3 в Ollama не было. Чтобы сравнить квантование: `ollama pull qwen3:8b-q8_0` "
                    + "(или `qwen3:4b`) и перезапустить — он добавится колонкой «3q».\n");
        }

        r.append("\n## Пример ответа на один вопрос\n");
        Task28.Q sample = questions.getFirst();
        r.append("\nВопрос: «").append(sample.question()).append("»\n");
        for (Level l : levels) {
            results.stream().filter(x -> x.level() == l && x.q() == sample && x.run() == 1).findFirst().ifPresent(x -> {
                r.append("\n**").append(l.id()).append(" · ").append(l.name()).append("** — ").append(x.ok() ? "✅" : "❌")
                        .append(String.format(Locale.ROOT, " %.1f с, %d ток.", x.wallMs() / 1000.0, x.outTokens())).append("\n\n");
                r.append(x.answer().isBlank() ? "> (нет ответа)" : x.answer().lines().map(s -> "> " + s)
                        .reduce((a, b) -> a + "\n" + b).orElse(">")).append("\n");
            });
        }
        Files.writeString(REPORT, r.toString(), StandardCharsets.UTF_8);
    }

    private static String paramRow(String name, List<Level> levels, java.util.function.Function<Level, String> f) {
        StringBuilder sb = new StringBuilder("| ").append(name).append(" | ");
        levels.forEach(l -> sb.append(f.apply(l)).append(" | "));
        return sb.append("\n").toString();
    }

    private static String metricRow(String name, List<Level> levels, List<Result> all,
                                    java.util.function.Function<List<Result>, String> f) {
        StringBuilder sb = new StringBuilder("| ").append(name).append(" | ");
        levels.forEach(l -> sb.append(f.apply(of(all, l))).append(" | "));
        return sb.append("\n").toString();
    }
}
