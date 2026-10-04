package ru.lemanapro.aiadventchallenge.week5;

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
import java.util.stream.Collectors;

/**
 * Day 23: reranking and filtering — a second stage after the vector search, plus query rewrite.
 *
 * Four modes of the same pipeline are run on the control set (10 questions of the base + 3 questions the
 * base cannot answer) and compared:
 *   A baseline   — top-5 by cosine, nothing else (day 22);
 *   B threshold  — top-20 -> drop cosine below the threshold -> top-5;
 *   C heuristic  — B + heuristic reranker (cosine + IDF-weighted share of query terms in the chunk);
 *   D full       — query rewrite (LLM) -> top-20 over all queries -> LLM reranker (0..10 per chunk,
 *                  cut below 5) -> top-5.
 *
 * The similarity threshold is not guessed: before the comparison it is CALIBRATED on the control set — a
 * sweep over thresholds, picking the one that empties the context for unanswerable questions while still
 * keeping the relevant chunk for at least TASK23_MIN_RECALL (0.8) of the answerable ones. The result is saved to task23-rag-settings.json and becomes the
 * default configuration of days 24-25 (as long as the index/embedder stay the same).
 *
 * Metrics: relevant chunk in the final context, its rank (MRR), share of relevant chunks in the context,
 * context size, share of expected facts in the answer; for unanswerable questions — whether the context
 * came out empty (the model then is not called at all).
 * Writes task23-rerank-report.md.
 *
 * Env: RAG_STRATEGY, RAG_TOP_K_BEFORE (20), RAG_TOP_K (5), RAG_RELATIVE_CUT (0.6), RAG_MIN_RERANK (0.5),
 *      TASK23_MIN_RECALL (0.8), RAG_MIN_SIMILARITY (skips the calibration result and uses this threshold).
 * Usage:
 *   mvn -q compile exec:java -Ptask23
 *   mvn -q exec:java -Ptask23 -Dexec.args="Ваш вопрос"    one question through all four modes (no report)
 */
public final class Task23 {

    private static final Path REPORT_FILE = Path.of("task23-rerank-report.md");
    private static final Pattern ADMITS = Pattern.compile(
            "не знаю|нет (точн\\S+ )?(информаци|данных|сведени|ответа)|не (могу|имею|располагаю|содерж|найден|указан|упомина)|недостаточно|отсутству",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final double MIN_RECALL = Double.parseDouble(LlmClient.env("TASK23_MIN_RECALL", "0.8"));
    static final String EMPTY_CONTEXT_ANSWER = "В базе знаний нет релевантных фрагментов — контекст пуст, модель не вызывалась.";

    record Mode(String id, String title, RagPipeline.Settings settings) {
    }

    record Cell(Mode mode, ControlSet.Question q, RagPipeline.Retrieval r, String answer, String error) {
        int relevantRank() {
            for (int i = 0; r != null && i < r.after().size(); i++) {
                if (q.relevant(r.after().get(i).chunk())) {
                    return i + 1;
                }
            }
            return 0;
        }

        double precision() {
            return r == null || r.after().isEmpty() ? 0 : r.after().stream().filter(c -> q.relevant(c.chunk())).count() / (double) r.after().size();
        }

        double score() {
            return error != null ? 0 : q.score(answer);
        }

        int kept() {
            return r == null ? 0 : r.after().size();
        }
    }

    record SweepRow(double threshold, int relevantKept, int answerable, int rejected, double avgKept) {
        double objective(int offTopic) {
            return (answerable == 0 ? 0 : (double) relevantKept / answerable) + (offTopic == 0 ? 0 : (double) rejected / offTopic);
        }
    }

    private Task23() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        HttpClient http = LlmClient.newHttpClient();
        RagIndex index = RagIndex.open(RagIndex.Strategy.of(LlmClient.env("RAG_STRATEGY", "structure")), http, cfg);
        RagPipeline pipeline = new RagPipeline(http, cfg, index);

        System.out.println("=== День 23. Реранкинг и фильтрация ===");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println("Индекс: " + index.meta().strategy() + ", " + index.chunks().size() + " чанков, эмбеддер " + index.meta().embedder());

        // defaults + env, but NOT a previously saved calibration: this run produces a fresh one
        RagPipeline.Settings base = RagPipeline.Settings.defaultsFor(index);

        // ---------------- 1. calibrate the similarity threshold (vector search only, no LLM)
        System.out.println("\n--- 1. Калибровка порога similarity (top-" + base.topKBefore() + " → top-" + base.topKAfter() + ", без LLM) ---");
        RagPipeline.Settings wide = RagPipeline.Settings.baseline(base.topKBefore());
        Map<String, RagPipeline.Retrieval> raw = new LinkedHashMap<>();
        for (ControlSet.Question q : all()) {
            raw.put(q.id(), pipeline.retrieve(q.question(), wide));
        }
        List<SweepRow> sweep = sweep(raw, base);
        SweepRow chosen = choose(sweep);
        double threshold = LlmClient.env("RAG_MIN_SIMILARITY", "").isBlank() ? chosen.threshold() : base.minSimilarity();
        for (SweepRow row : sweep) {
            System.out.printf(Locale.ROOT, "  cos ≥ %.2f: релевантный чанк сохранён %d/%d, вопросы вне базы отсечены %d/%d, в среднем чанков %.1f%s%n",
                    row.threshold(), row.relevantKept(), row.answerable(), row.rejected(), ControlSet.OFF_TOPIC.size(), row.avgKept(),
                    row.threshold() == chosen.threshold() ? "   ← выбран" : "");
        }
        System.out.printf(Locale.ROOT, "Порог: %.2f%s%n", threshold, threshold == chosen.threshold() ? "" : " (задан через RAG_MIN_SIMILARITY)");

        RagPipeline.Settings tuned = new RagPipeline.Settings(base.topKBefore(), base.topKAfter(), RagPipeline.Rerank.LLM, threshold, base.relativeCut(),
                base.minRerank(), true, index.meta().embedder(), index.meta().strategy(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        List<Mode> modes = List.of(
                new Mode("A", "baseline: top-" + base.topKAfter() + " по косинусу", RagPipeline.Settings.baseline(base.topKAfter())),
                new Mode("B", "порог similarity", tuned.with(RagPipeline.Rerank.THRESHOLD, false)),
                new Mode("C", "порог + эвристический реранкер", tuned.with(RagPipeline.Rerank.HEURISTIC, false)),
                new Mode("D", "query rewrite + LLM-реранкер", tuned));

        if (args.length > 0) {
            single(String.join(" ", args), pipeline, modes);
            return;
        }

        // ---------------- 2. the four modes on the control set
        List<Cell> cells = new ArrayList<>();
        for (Mode mode : modes) {
            System.out.println("\n--- Режим " + mode.id() + ": " + mode.title() + " [" + mode.settings().describe() + "] ---");
            for (ControlSet.Question q : all()) {
                Cell cell = run(pipeline, mode, q);
                cells.add(cell);
                boolean offTopic = q.facts().isEmpty();
                System.out.printf("  %-3s чанков %d→%d, %s%s%n", q.id(), cell.r() == null ? 0 : cell.r().before().size(), cell.kept(),
                        offTopic ? (cell.kept() == 0 ? "вне базы: отсечён" : "вне базы: НЕ отсечён")
                                : "релевантный: " + (cell.relevantRank() == 0 ? "нет" : "№" + cell.relevantRank())
                                + ", фактов " + Math.round(cell.score() * q.facts().size()) + "/" + q.facts().size(),
                        cell.error() == null ? "" : "  [ошибка: " + cell.error() + "]");
            }
        }

        tuned.save();
        System.out.println("\nНастройки режима D сохранены в " + RagPipeline.SETTINGS_FILE + " — их используют дни 24–25.");
        System.out.println("\nИТОГ:");
        for (Mode mode : modes) {
            List<Cell> in = of(cells, mode, false);
            List<Cell> off = of(cells, mode, true);
            System.out.printf(Locale.ROOT, "  %s %-34s релевантный в контексте %d/%d, MRR %.2f, фактов %.0f %%, вне базы отсечено %d/%d%n", mode.id(), mode.title(),
                    in.stream().filter(c -> c.relevantRank() > 0).count(), in.size(), mrr(in), 100 * avgScore(in),
                    off.stream().filter(c -> c.kept() == 0).count(), off.size());
        }
        writeReport(cfg, index, pipeline, modes, cells, sweep, chosen, threshold, tuned);
        System.out.println("Отчёт: " + REPORT_FILE.toAbsolutePath());
    }

    private static List<ControlSet.Question> all() {
        List<ControlSet.Question> all = new ArrayList<>(ControlSet.QUESTIONS);
        all.addAll(ControlSet.OFF_TOPIC);
        return all;
    }

    private static Cell run(RagPipeline pipeline, Mode mode, ControlSet.Question q) {
        RagPipeline.Retrieval r = null;
        try {
            r = pipeline.retrieve(q.question(), mode.settings());
            String answer = r.after().isEmpty() ? EMPTY_CONTEXT_ANSWER : pipeline.answerWithContext(q.question(), r);
            return new Cell(mode, q, r, answer, null);
        } catch (Exception e) {
            return new Cell(mode, q, r, "(ошибка: " + RagPipeline.brief(e) + ")", RagPipeline.brief(e));
        }
    }

    private static void single(String question, RagPipeline pipeline, List<Mode> modes) {
        System.out.println("\nВопрос: " + question);
        for (Mode mode : modes) {
            System.out.println("\n--- Режим " + mode.id() + ": " + mode.title() + " [" + mode.settings().describe() + "] ---");
            try {
                RagPipeline.Retrieval r = pipeline.retrieve(question, mode.settings());
                if (r.queries().size() > 1) {
                    System.out.println("Запросы: " + r.queries());
                }
                System.out.println("До фильтрации: " + r.before().size() + " чанков, после: " + r.after().size());
                for (RagPipeline.Candidate c : r.after()) {
                    System.out.printf(Locale.ROOT, "  cos %.3f%s  %s%n", c.cosine(), Double.isNaN(c.score()) ? "" : String.format(Locale.ROOT, ", оценка %.2f", c.score()), c.chunk().ref());
                }
                r.notes().forEach(n -> System.out.println("  примечание: " + n));
                System.out.println(r.after().isEmpty() ? EMPTY_CONTEXT_ANSWER : pipeline.answerWithContext(question, r));
            } catch (Exception e) {
                System.out.println("(ошибка: " + RagPipeline.brief(e) + ")");
            }
        }
    }

    // ---------------------------------------------------------------- threshold calibration

    private static List<SweepRow> sweep(Map<String, RagPipeline.Retrieval> raw, RagPipeline.Settings base) {
        double maxTop = raw.values().stream().mapToDouble(RagPipeline.Retrieval::bestCosine).max().orElse(1);
        List<SweepRow> rows = new ArrayList<>();
        int steps = 16;
        for (int i = 0; i <= steps; i++) {
            double threshold = Math.round(maxTop * i / steps * 100) / 100.0;
            if (!rows.isEmpty() && rows.getLast().threshold() == threshold) {
                continue;
            }
            int relevantKept = 0;
            int answerable = 0; // questions whose relevant chunk is in the context when there is no threshold at all
            int rejected = 0;
            double kept = 0;
            for (ControlSet.Question q : ControlSet.QUESTIONS) {
                RagPipeline.Retrieval r = raw.get(q.id());
                List<RagPipeline.Candidate> after = filter(r, threshold, base);
                answerable += filter(r, 0, base).stream().anyMatch(c -> q.relevant(c.chunk())) ? 1 : 0;
                relevantKept += after.stream().anyMatch(c -> q.relevant(c.chunk())) ? 1 : 0;
                kept += after.size();
            }
            for (ControlSet.Question q : ControlSet.OFF_TOPIC) {
                rejected += filter(raw.get(q.id()), threshold, base).isEmpty() ? 1 : 0;
            }
            rows.add(new SweepRow(threshold, relevantKept, answerable, rejected, kept / ControlSet.QUESTIONS.size()));
        }
        return rows;
    }

    private static List<RagPipeline.Candidate> filter(RagPipeline.Retrieval r, double threshold, RagPipeline.Settings base) {
        double floor = Math.max(threshold, base.relativeCut() * r.bestCosine());
        return r.before().stream().filter(c -> c.cosine() >= floor).limit(base.topKAfter()).toList();
    }

    /**
     * The filter must first of all not break what the search already finds: only thresholds that keep the
     * relevant chunk in at least MIN_RECALL of the answerable questions are considered. Among them — the best
     * sum (relevant kept + off-topic rejected); among equals — the middle of the plateau, for margin on both sides.
     */
    private static SweepRow choose(List<SweepRow> sweep) {
        int off = ControlSet.OFF_TOPIC.size();
        List<SweepRow> safe = sweep.stream().filter(r -> r.answerable() == 0 || (double) r.relevantKept() / r.answerable() >= MIN_RECALL).toList();
        if (safe.isEmpty()) {
            return sweep.getFirst();
        }
        double best = safe.stream().mapToDouble(r -> r.objective(off)).max().orElse(0);
        List<SweepRow> top = safe.stream().filter(r -> r.objective(off) >= best - 1e-9).toList();
        return top.get(top.size() / 2);
    }

    // ---------------------------------------------------------------- metrics

    private static List<Cell> of(List<Cell> cells, Mode mode, boolean offTopic) {
        return cells.stream().filter(c -> c.mode() == mode && c.q().facts().isEmpty() == offTopic).toList();
    }

    private static double mrr(List<Cell> cells) {
        return cells.stream().mapToDouble(c -> c.relevantRank() > 0 ? 1.0 / c.relevantRank() : 0).average().orElse(0);
    }

    private static double avgScore(List<Cell> cells) {
        return cells.stream().mapToDouble(Cell::score).average().orElse(0);
    }

    // ---------------------------------------------------------------- report

    private static void writeReport(LlmClient.Config cfg, RagIndex index, RagPipeline pipeline, List<Mode> modes, List<Cell> cells,
                                    List<SweepRow> sweep, SweepRow chosen, double threshold, RagPipeline.Settings tuned) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 23. Реранкинг и фильтрация\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask23`, модель ").append(cfg.model()).append(", индекс `").append(index.meta().strategy())
                .append("` (").append(index.chunks().size()).append(" чанков, эмбеддер `").append(index.meta().embedder()).append("`)\n\n");
        md.append("```\nвопрос ─▶ [query rewrite] ─▶ векторный поиск: top-").append(tuned.topKBefore()).append(" ─▶ ЭТАП 2: фильтр / реранкер ─▶ top-")
                .append(tuned.topKAfter()).append(" ─▶ LLM\n                                                             пустой результат = «в базе этого нет»\n```\n\n");

        md.append("## Режимы\n\n| Режим | Что делает | Настройки |\n|---|---|---|\n");
        for (Mode m : modes) {
            md.append("| **").append(m.id()).append("** | ").append(m.title()).append(" | ").append(m.settings().describe()).append(" |\n");
        }
        md.append("\n- **Порог similarity** — чанк остаётся, если его косинус ≥ max(порог, ").append(String.format(Locale.ROOT, "%.0f %%", tuned.relativeCut() * 100))
                .append(" от лучшего косинуса по этому вопросу).\n")
                .append("- **Эвристический реранкер** — оценка = 0.5 × (cos / лучший cos) + 0.5 × доля терминов запроса, найденных в чанке (с весами IDF: редкие идентификаторы важнее частых слов); чанки с оценкой < ")
                .append(String.format(Locale.ROOT, "%.2f", tuned.minRerank())).append(" отбрасываются.\n")
                .append("- **LLM-реранкер** — модель одним вызовом ставит каждому из top-").append(tuned.topKBefore()).append(" чанков оценку 0–10; остаются чанки с оценкой ≥ ")
                .append(String.format(Locale.ROOT, "%.0f", tuned.minRerank() * 10)).append(". Шкала не зависит от эмбеддера, поэтому порог не нужно перекалибровывать.\n")
                .append("- **Query rewrite** — модель превращает вопрос в 2–3 поисковых запроса (русский + английский с идентификаторами); ищем по всем, объединяем по лучшему косинусу.\n\n");

        md.append("## Настройка порога similarity\n\n");
        md.append("Порог подбирался на контрольном наборе: 10 вопросов по базе и 3 вопроса, на которые база ответить не может. ")
                .append("Хороший порог сохраняет релевантный чанк там, где поиск его нашёл, и оставляет пустой контекст для вопросов вне базы.\n\n");
        md.append("| Порог cos | Релевантный чанк сохранён ¹ | Вопросы вне базы отсечены | Чанков в контексте (среднее) | |\n|---|---|---|---|---|\n");
        for (SweepRow row : sweep) {
            md.append(String.format(Locale.ROOT, "| %.2f | %d/%d | %d/%d | %.1f | %s |%n", row.threshold(), row.relevantKept(), row.answerable(),
                    row.rejected(), ControlSet.OFF_TOPIC.size(), row.avgKept(), row.threshold() == chosen.threshold() ? "**← выбран**" : ""));
        }
        md.append("\n¹ Знаменатель — вопросы, где релевантный чанк попадает в контекст вообще без порога (порог не может вернуть то, чего поиск не поднял в top-")
                .append(tuned.topKAfter()).append(").\n\n");
        md.append(String.format(Locale.ROOT, "Правило выбора: среди порогов, которые сохраняют релевантный чанк не менее чем в %.0f %% вопросов (фильтр не должен ломать то, что поиск уже находит; `TASK23_MIN_RECALL`), "
                + "берётся лучший по сумме «сохранено + отсечено», при равенстве — середина плато. ", 100 * MIN_RECALL));
        md.append(String.format(Locale.ROOT, "Итоговый порог: **%.2f**%s. ", threshold, threshold == chosen.threshold() ? "" : " (задан через `RAG_MIN_SIMILARITY`)"));
        if (chosen.rejected() < ControlSet.OFF_TOPIC.size() && threshold == chosen.threshold()) {
            md.append("При нём отсекаются не все вопросы вне базы: распределения близости для вопросов по базе и вне базы у этого эмбеддера перекрываются, одним числом их не разделить. ");
        }
        md.append("Лучшая близость по вопросам: ");
        md.append(all().stream().map(q -> q.id() + " " + String.format(Locale.ROOT, "%.2f", cells.stream()
                .filter(c -> c.mode() == modes.getFirst() && c.q() == q && c.r() != null).mapToDouble(c -> c.r().bestCosine()).findFirst().orElse(0)))
                .collect(Collectors.joining(", "))).append(".\n\n");
        md.append("Порог калибровался на тех же вопросах, на которых затем измеряется качество, поэтому оценка режимов B и C слегка оптимистична; ")
                .append("абсолютное значение порога привязано к эмбеддеру и после смены модели эмбеддингов подбирается заново (перезапуск дня 23).\n\n");

        md.append("## Сравнение режимов\n\n");
        md.append("| Метрика | ").append(modes.stream().map(m -> m.id() + ". " + m.title()).collect(Collectors.joining(" | "))).append(" |\n|---|")
                .append("---|".repeat(modes.size())).append("\n");
        metric(md, "Вопросы по базе: релевантный чанк в контексте", modes, cells, false,
                in -> in.stream().filter(c -> c.relevantRank() > 0).count() + "/" + in.size());
        metric(md, "MRR релевантного чанка в контексте", modes, cells, false, in -> String.format(Locale.ROOT, "%.2f", mrr(in)));
        metric(md, "Доля релевантных чанков в контексте", modes, cells, false,
                in -> String.format(Locale.ROOT, "%.0f %%", 100 * in.stream().mapToDouble(Cell::precision).average().orElse(0)));
        metric(md, "Чанков в контексте (среднее)", modes, cells, false,
                in -> String.format(Locale.ROOT, "%.1f", in.stream().mapToInt(Cell::kept).average().orElse(0)));
        metric(md, "Размер контекста, знаков (среднее)", modes, cells, false,
                in -> String.format(Locale.ROOT, "%.0f", in.stream().mapToInt(c -> c.r() == null ? 0 : c.r().contextChars()).average().orElse(0)));
        metric(md, "Доля ожидаемых фактов в ответе", modes, cells, false, in -> String.format(Locale.ROOT, "%.0f %%", 100 * avgScore(in)));
        metric(md, "Ответов со всеми фактами", modes, cells, false, in -> in.stream().filter(c -> c.score() >= 1).count() + "/" + in.size());
        metric(md, "Вопросы вне базы: контекст пуст (модель не вызывается)", modes, cells, true,
                off -> off.stream().filter(c -> c.kept() == 0).count() + "/" + off.size());
        metric(md, "Вопросы вне базы: чанков в контексте (среднее)", modes, cells, true,
                off -> String.format(Locale.ROOT, "%.1f", off.stream().mapToInt(Cell::kept).average().orElse(0)));
        metric(md, "Дополнительных вызовов LLM на вопрос", modes, cells, false,
                in -> in.isEmpty() ? "—" : String.valueOf((in.getFirst().mode().settings().rewrite() ? 1 : 0)
                        + (in.getFirst().mode().settings().rerank() == RagPipeline.Rerank.LLM ? 1 : 0)));

        md.append("\n## По вопросам\n\nВ ячейке: чанков после этапа 2 · позиция релевантного чанка (— если его нет) · найдено фактов.\n\n");
        md.append("| # | Вопрос | ").append(modes.stream().map(Mode::id).collect(Collectors.joining(" | "))).append(" |\n|---|---|").append("---|".repeat(modes.size())).append("\n");
        for (ControlSet.Question q : all()) {
            md.append("| ").append(q.id()).append(" | ").append(Task21.esc(Chunker.clip(q.question(), 70))).append(" | ");
            for (Mode m : modes) {
                Cell c = cells.stream().filter(x -> x.mode() == m && x.q() == q).findFirst().orElseThrow();
                md.append(q.facts().isEmpty()
                        ? c.kept() + " · " + (c.kept() == 0 ? "отсечён ✅" : ADMITS.matcher(c.answer()).find() ? "не отсечён, модель сказала «нет в контексте»" : "не отсечён, модель ответила ❌")
                        : c.kept() + " · " + (c.relevantRank() == 0 ? "—" : "№" + c.relevantRank()) + " · " + Math.round(c.score() * q.facts().size()) + "/" + q.facts().size())
                        .append(c.error() == null ? "" : " (ошибка)").append(" | ");
            }
            md.append("\n");
        }

        // one question in detail: preferably one where the second stage changed the outcome
        Mode a = modes.getFirst();
        Mode d = modes.getLast();
        ControlSet.Question example = ControlSet.QUESTIONS.stream()
                .filter(q -> rank(cells, a, q) != rank(cells, d, q) && rank(cells, d, q) > 0).findFirst().orElse(ControlSet.QUESTIONS.getFirst());
        Cell exA = cells.stream().filter(x -> x.mode() == a && x.q() == example).findFirst().orElseThrow();
        Cell exD = cells.stream().filter(x -> x.mode() == d && x.q() == example).findFirst().orElseThrow();
        md.append("\n## Пример: ").append(example.id()).append("\n\n**Вопрос:** ").append(example.question()).append("\n\n");
        if (exD.r() != null) {
            md.append("**Запросы после rewrite:**\n");
            exD.r().queries().forEach(q -> md.append("- ").append(Task21.esc(q)).append("\n"));
            md.append("\n**A. Контекст без второго этапа:** ").append(chunkList(exA, example)).append("\n\n");
            md.append("**D. До этапа 2** (top-").append(exD.r().before().size()).append(" по косинусу): ")
                    .append(exD.r().before().stream().map(c -> String.format(Locale.ROOT, "`%s` %.2f%s", c.chunk().chunkId(), c.cosine(), example.relevant(c.chunk()) ? " ✅" : ""))
                            .collect(Collectors.joining(", "))).append("\n\n");
            md.append("**D. После LLM-реранкера:** ").append(chunkList(exD, example)).append("\n\n");
            exD.r().notes().forEach(n -> md.append("_Примечание: ").append(n).append("_\n\n"));
            md.append("**Ответ A:**\n\n").append(Task22.quote(exA.answer())).append("\n\n**Ответ D:**\n\n").append(Task22.quote(exD.answer())).append("\n\n");
        }

        md.append("## Выводы\n\n");
        List<Cell> inA = of(cells, a, false);
        List<Cell> inD = of(cells, d, false);
        List<Cell> offA = of(cells, a, true);
        md.append(String.format(Locale.ROOT, "1. **Без второго этапа контекст всегда полон — даже когда ответа в базе нет.** В режиме A на вопросы вне базы в контекст попало в среднем %.1f чанка (отсечено %d/%d): "
                        + "top-K возвращает «ближайшее», а не «релевантное», и судьба ответа целиком зависит от того, признает ли модель, что во фрагментах ответа нет.%n",
                offA.stream().mapToInt(Cell::kept).average().orElse(0), offA.stream().filter(c -> c.kept() == 0).count(), offA.size()));
        md.append("2. **Порог similarity — дешёвый, но хрупкий фильтр.** ");
        Mode b = modes.get(1);
        List<Cell> inB = of(cells, b, false);
        List<Cell> offB = of(cells, b, true);
        long rejectedB = offB.stream().filter(c -> c.kept() == 0).count();
        md.append(String.format(Locale.ROOT, "Режим B: вопросы вне базы отсечены %d/%d, релевантный чанк в контексте %d/%d (в A — %d/%d), контекст в среднем %.1f чанка вместо %.1f. ",
                rejectedB, offB.size(), inB.stream().filter(c -> c.relevantRank() > 0).count(), inB.size(),
                inA.stream().filter(c -> c.relevantRank() > 0).count(), inA.size(), inB.stream().mapToInt(Cell::kept).average().orElse(0),
                inA.stream().mapToInt(Cell::kept).average().orElse(0)));
        md.append(rejectedB == offB.size() ? "Все вопросы вне базы отсечены без единого вызова LLM. "
                : rejectedB > 0 ? "Часть вопросов вне базы отсечена без вызовов LLM, но не все: поднять порог выше нельзя, не потеряв нужные чанки. "
                : "Отделить вопросы вне базы одним порогом не удалось: их близость к базе не ниже, чем у части настоящих вопросов. ");
        md.append("Значение порога привязано к эмбеддеру и к набору вопросов: слишком низкий пропускает мусор, слишком высокий срезает нужное.\n");
        List<Cell> inC = of(cells, modes.get(2), false);
        md.append(String.format(Locale.ROOT, "3. **Реранкер переупорядочивает и чистит контекст.** MRR релевантного чанка: A %.2f → C %.2f (эвристика) → D %.2f (rewrite + LLM); "
                        + "чанков в контексте: A %.1f → D %.1f. ", mrr(inA), mrr(inC), mrr(inD),
                inA.stream().mapToInt(Cell::kept).average().orElse(0), inD.stream().mapToInt(Cell::kept).average().orElse(0)));
        md.append(mrr(inD) > mrr(inA) + 0.02 ? "Нужный чанк поднимается выше, а постороннего вокруг него меньше — модели проще ответить.\n"
                : "На этом прогоне выигрыш — в отсечении лишнего, а не в порядке: позиция нужного чанка почти не изменилась.\n");
        md.append(String.format(Locale.ROOT, "4. **Качество ответов.** Доля ожидаемых фактов: A %.0f %% → B %.0f %% → C %.0f %% → D %.0f %%; релевантный чанк в контексте: A %d/%d → D %d/%d. ",
                100 * avgScore(inA), 100 * avgScore(inB), 100 * avgScore(inC), 100 * avgScore(inD),
                inA.stream().filter(c -> c.relevantRank() > 0).count(), inA.size(), inD.stream().filter(c -> c.relevantRank() > 0).count(), inD.size()));
        List<String> gained = ControlSet.QUESTIONS.stream().filter(q -> rank(cells, a, q) == 0 && rank(cells, d, q) > 0).map(ControlSet.Question::id).toList();
        List<String> lost = ControlSet.QUESTIONS.stream().filter(q -> rank(cells, a, q) > 0 && rank(cells, d, q) == 0).map(ControlSet.Question::id).toList();
        md.append(gained.isEmpty() ? "Новых вопросов с найденным релевантным чанком режим D не добавил" : "Режим D вернул релевантный чанк для " + String.join(", ", gained))
                .append(lost.isEmpty() ? ".\n" : "; при этом потерял его для " + String.join(", ", lost) + " (реранкер или порог отсекли нужное — цена фильтрации).\n");
        md.append("5. **Цена режима D** — два дополнительных вызова LLM на вопрос (rewrite и реранкинг ").append(tuned.topKBefore())
                .append(" чанков). Rewrite особенно важен, когда вопрос и документы на разных языках или вопрос сформулирован не теми словами, что документация.\n");
        md.append("6. **Что дальше.** Настройки режима D сохранены в `").append(RagPipeline.SETTINGS_FILE).append("` и используются днями 24–25; пустой контекст после второго этапа — ")
                .append("сигнал для правила «не знаю» (день 24).\n");
        long failed = cells.stream().filter(c -> c.error() != null).count();
        if (failed > 0) {
            md.append("\n> ⚠️ ").append(failed).append(" вызовов завершились ошибкой — соответствующие ячейки занижены.\n");
        }
        md.append("\n_Вызовов LLM: ").append(pipeline.llmCalls()).append(", токенов: ").append(pipeline.tokens()).append("._\n");
        Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
    }

    private static int rank(List<Cell> cells, Mode m, ControlSet.Question q) {
        return cells.stream().filter(x -> x.mode() == m && x.q() == q).findFirst().map(Cell::relevantRank).orElse(0);
    }

    private static String chunkList(Cell cell, ControlSet.Question q) {
        if (cell.r() == null || cell.r().after().isEmpty()) {
            return "пусто";
        }
        return cell.r().after().stream().map(c -> String.format(Locale.ROOT, "`%s` (cos %.2f%s)%s", Task21.esc(c.chunk().ref()), c.cosine(),
                Double.isNaN(c.score()) ? "" : String.format(Locale.ROOT, ", оценка %.2f", c.score()), q.relevant(c.chunk()) ? " ✅" : "")).collect(Collectors.joining("; "));
    }

    private interface Metric {
        String of(List<Cell> cells);
    }

    private static void metric(StringBuilder md, String name, List<Mode> modes, List<Cell> cells, boolean offTopic, Metric metric) {
        md.append("| ").append(name).append(" | ");
        for (Mode m : modes) {
            md.append(metric.of(of(cells, m, offTopic))).append(" | ");
        }
        md.append("\n");
    }
}
