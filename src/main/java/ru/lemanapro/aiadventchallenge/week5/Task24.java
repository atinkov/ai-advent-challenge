package ru.lemanapro.aiadventchallenge.week5;

import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Day 24: citations, sources and anti-hallucination.
 *
 * Every answer of the RAG now consists of three mandatory parts:
 *   - the answer;
 *   - the list of sources: source + section + chunk_id;
 *   - quotes: verbatim excerpts of the retrieved chunks.
 * "Mandatory" is enforced by code, not by the prompt alone (RagPipeline.answerCited): the model returns JSON,
 * the code keeps only chunk ids that were in the context and only quotes found verbatim in a chunk, asks the
 * model to repair once, and as the last resort cuts a quote from the cited chunk itself.
 *
 * The "не знаю" rule: if after the second stage (day 23: rerank + threshold) no chunk is relevant enough,
 * the assistant does not call the model at all and answers "не знаю" with a request to clarify. The model
 * may also declare known=false when the context turned out not to contain the answer.
 *
 * Checked on the 10 control questions:
 *   - are there sources in every answer; are there quotes in every answer;
 *   - is every quote really a verbatim excerpt of the chunk it refers to (re-checked here against the index,
 *     independently of the pipeline);
 *   - does the meaning of the answer match the quotes (a separate LLM call — a judge that sees only the
 *     question, the answer and the quotes);
 * and on 3 questions the base cannot answer: the assistant must say "не знаю" and ask to clarify.
 * Writes task24-citations-report.md.
 *
 * Settings: task23-rag-settings.json (calibrated by day 23) or the defaults; see RagPipeline.
 * Usage:
 *   mvn -q compile exec:java -Ptask24
 *   mvn -q exec:java -Ptask24 -Dexec.args="Ваш вопрос"     one answer with sources and quotes
 */
public final class Task24 {

    private static final Path REPORT_FILE = Path.of("task24-citations-report.md");

    /** judge: null if the judge was not asked (no answer) or failed. */
    record Row(ControlSet.Question q, RagPipeline.Retrieval r, RagPipeline.Cited cited, RagPipeline.Judge judge, int verbatim, String error) {
        boolean answered() {
            return error == null && cited != null && cited.known();
        }

        boolean allVerbatim() {
            return answered() && cited.hasQuotes() && verbatim == cited.quotes().size();
        }

        boolean expectedSourceCited() {
            return answered() && cited.sources().stream().anyMatch(c -> q.sourceMatches(c.source()));
        }

        long autoQuotes() {
            return cited == null ? 0 : cited.quotes().stream().filter(RagPipeline.Quote::auto).count();
        }
    }

    private Task24() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        HttpClient http = LlmClient.newHttpClient();
        RagIndex index = RagIndex.open(RagIndex.Strategy.of(LlmClient.env("RAG_STRATEGY", "structure")), http, cfg);
        RagPipeline pipeline = new RagPipeline(http, cfg, index);
        RagPipeline.Settings settings = RagPipeline.Settings.forIndex(index);

        System.out.println("=== День 24. Цитаты, источники и анти-галлюцинации ===");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println("Индекс: " + index.meta().strategy() + ", " + index.chunks().size() + " чанков, эмбеддер " + index.meta().embedder());
        System.out.println("Поиск: " + settings.describe() + (settings.calibrated().isBlank() ? "  (значения по умолчанию; день 23 их калибрует)"
                : "  (откалибровано днём 23: " + settings.calibrated() + ")"));

        if (args.length > 0) {
            String question = String.join(" ", args);
            RagPipeline.Retrieval r = pipeline.retrieve(question, settings);
            System.out.println("\nВопрос: " + question + "\n\n" + pipeline.answerCited(question, r).render());
            return;
        }

        List<Row> rows = new ArrayList<>();
        System.out.println("\n--- Вопросы по базе: ответ + источники + цитаты ---");
        for (ControlSet.Question q : ControlSet.QUESTIONS) {
            Row row = run(pipeline, index, settings, q, true);
            rows.add(row);
            print(row);
        }
        System.out.println("\n--- Вопросы вне базы: ожидается «не знаю» и просьба уточнить ---");
        for (ControlSet.Question q : ControlSet.OFF_TOPIC) {
            Row row = run(pipeline, index, settings, q, false);
            rows.add(row);
            print(row);
        }

        List<Row> in = rows.stream().filter(r -> !r.q().facts().isEmpty()).toList();
        List<Row> off = rows.stream().filter(r -> r.q().facts().isEmpty()).toList();
        List<Row> answered = in.stream().filter(Row::answered).toList();
        System.out.printf("%nИТОГ: ответов %d/%d; с источниками %d/%d; с цитатами %d/%d; все цитаты дословны %d/%d; смысл подтверждён цитатами %d/%d; «не знаю» вне базы %d/%d%n",
                answered.size(), in.size(), answered.stream().filter(r -> r.cited().hasSources()).count(), answered.size(),
                answered.stream().filter(r -> r.cited().hasQuotes()).count(), answered.size(), answered.stream().filter(Row::allVerbatim).count(), answered.size(),
                answered.stream().filter(r -> r.judge() != null && r.judge().supported()).count(), answered.size(),
                off.stream().filter(r -> r.cited() != null && !r.cited().known()).count(), off.size());
        writeReport(cfg, index, pipeline, settings, in, off);
        System.out.println("Отчёт: " + REPORT_FILE.toAbsolutePath());
    }

    private static Row run(RagPipeline pipeline, RagIndex index, RagPipeline.Settings settings, ControlSet.Question q, boolean withJudge) {
        RagPipeline.Retrieval r = null;
        RagPipeline.Cited cited = null;
        try {
            r = pipeline.retrieve(q.question(), settings);
            cited = pipeline.answerCited(q.question(), r);
        } catch (Exception e) {
            return new Row(q, r, cited, null, 0, RagPipeline.brief(e));
        }
        // independent re-check: the quote must be an exact substring of the chunk stored in the index
        int verbatim = 0;
        for (RagPipeline.Quote quote : cited.quotes()) {
            RagIndex.Chunk chunk = index.byId(quote.chunkId());
            verbatim += chunk != null && chunk.text().contains(quote.text()) ? 1 : 0;
        }
        RagPipeline.Judge judge = null;
        if (withJudge && cited.known()) {
            try {
                judge = pipeline.judge(q.question(), cited);
            } catch (Exception e) {
                judge = null;
            }
        }
        return new Row(q, r, cited, judge, verbatim, null);
    }

    private static void print(Row row) {
        System.out.println("\n" + row.q().id() + ". " + row.q().question());
        if (row.error() != null) {
            System.out.println("  (ошибка: " + row.error() + ")");
            return;
        }
        System.out.println(row.cited().render().lines().map(l -> "  " + l).collect(Collectors.joining("\n")));
        if (row.cited().known()) {
            System.out.printf("  → источники: %s | цитаты: %d (дословно %d, отклонено проверкой %d%s) | смысл: %s%n",
                    row.cited().hasSources() ? "есть" : "НЕТ", row.cited().quotes().size(), row.verbatim(), row.cited().rejectedQuotes(),
                    row.autoQuotes() > 0 ? ", подобрано кодом " + row.autoQuotes() : "",
                    row.judge() == null ? "не проверен" : row.judge().supported() ? "совпадает" : "НЕ совпадает — " + row.judge().comment());
        }
    }

    // ---------------------------------------------------------------- report

    private static void writeReport(LlmClient.Config cfg, RagIndex index, RagPipeline pipeline, RagPipeline.Settings settings,
                                    List<Row> in, List<Row> off) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 24. Цитаты, источники и анти-галлюцинации\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask24`, модель ").append(cfg.model()).append(", индекс `").append(index.meta().strategy())
                .append("` (").append(index.chunks().size()).append(" чанков, эмбеддер `").append(index.meta().embedder()).append("`)\n\n");
        md.append("Поиск: ").append(settings.describe()).append(settings.calibrated().isBlank() ? " — значения по умолчанию.\n\n"
                : " — настройки дня 23 (`" + RagPipeline.SETTINGS_FILE + "`).\n\n");

        md.append("## Как устроена гарантия\n\n");
        md.append("```\nвопрос ─▶ поиск + этап 2 ─┬─ контекст пуст ───────────────────────────▶ «Не знаю» + просьба уточнить   (LLM не вызывается)\n")
                .append("                          └─ контекст есть ─▶ LLM возвращает JSON ─▶ ПРОВЕРКА В КОДЕ ─▶ ответ + источники + цитаты\n")
                .append("                                              {known, answer,         • source ∈ чанки контекста\n")
                .append("                                               sources, quotes,       • цитата дословно есть в чанке\n")
                .append("                                               clarify}               • нет валидных цитат → 1 попытка исправить → цитата из чанка кодом\n```\n\n");
        md.append("- **Источники** печатает код по метаданным индекса (`source › section [chunk_id]`, строки файла) — модель называет только `chunk_id`, ")
                .append("и только из тех, что были в контексте; придумать несуществующий файл она не может.\n");
        md.append("- **Цитаты** проверяются на дословное вхождение в текст чанка (без учёта пробелов, переносов и markdown-разметки) и печатаются ")
                .append("заново вырезанными из чанка. Цитата-пересказ отбрасывается.\n");
        md.append("- **«Не знаю»** срабатывает в двух местах: в коде — если после этапа 2 не осталось ни одного достаточно релевантного чанка; ")
                .append("и в модели — `known=false`, если фрагменты нашлись, но ответа в них нет. В обоих случаях ассистент просит уточнить вопрос.\n\n");

        List<Row> answered = in.stream().filter(Row::answered).toList();
        md.append("## Проверка на 10 вопросах\n\n");
        md.append("| # | Вопрос | Ответ дан | Источники | Цитаты | Все цитаты дословны | Смысл ответа = цитатам | Ожидаемый источник процитирован | Фактов |\n|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : in) {
            md.append("| ").append(r.q().id()).append(" | ").append(Task21.esc(Chunker.clip(r.q().question(), 60))).append(" | ");
            if (r.error() != null) {
                md.append("ошибка | — | — | — | — | — | — |\n");
                continue;
            }
            if (!r.cited().known()) {
                md.append("«не знаю» | — | — | — | — | — | 0/").append(r.q().facts().size()).append(" |\n");
                continue;
            }
            md.append("да | ").append(r.cited().hasSources() ? "✅ " + r.cited().sources().size() : "❌").append(" | ")
                    .append(r.cited().hasQuotes() ? "✅ " + r.cited().quotes().size() + (r.autoQuotes() > 0 ? " (кодом: " + r.autoQuotes() + ")" : "") : "❌").append(" | ")
                    .append(r.allVerbatim() ? "✅" : "❌ " + r.verbatim() + "/" + r.cited().quotes().size()).append(" | ")
                    .append(r.judge() == null ? "не проверен" : r.judge().supported() ? "✅" : "❌").append(" | ")
                    .append(r.expectedSourceCited() ? "✅" : "❌").append(" | ")
                    .append(Math.round(r.q().score(r.cited().answer()) * r.q().facts().size())).append("/").append(r.q().facts().size()).append(" |\n");
        }
        long withSources = answered.stream().filter(r -> r.cited().hasSources()).count();
        long withQuotes = answered.stream().filter(r -> r.cited().hasQuotes()).count();
        long verbatim = answered.stream().filter(Row::allVerbatim).count();
        long judged = answered.stream().filter(r -> r.judge() != null).count();
        long supported = answered.stream().filter(r -> r.judge() != null && r.judge().supported()).count();
        long rejected = answered.stream().mapToLong(r -> r.cited().rejectedQuotes()).sum();
        long auto = answered.stream().mapToLong(Row::autoQuotes).sum();
        long repaired = answered.stream().filter(r -> r.cited().repaired()).count();
        long totalQuotes = answered.stream().mapToLong(r -> r.cited().quotes().size()).sum();
        md.append("\n| Проверка | Результат |\n|---|---|\n");
        md.append("| Ответ дан (не «не знаю») | ").append(answered.size()).append("/").append(in.size()).append(" |\n");
        md.append("| Источники есть в каждом ответе | ").append(withSources).append("/").append(answered.size()).append(" |\n");
        md.append("| Цитаты есть в каждом ответе | ").append(withQuotes).append("/").append(answered.size()).append(" |\n");
        md.append("| Все цитаты ответа — дословные фрагменты чанков (повторная проверка по индексу) | ").append(verbatim).append("/").append(answered.size()).append(" |\n");
        md.append("| Смысл ответа совпадает с цитатами (LLM-судья) | ").append(supported).append("/").append(judged)
                .append(judged < answered.size() ? " (судья не ответил: " + (answered.size() - judged) + ")" : "").append(" |\n");
        md.append("| Процитирован ожидаемый источник | ").append(answered.stream().filter(Row::expectedSourceCited).count()).append("/").append(answered.size()).append(" |\n");
        md.append("| Цитат всего / отклонено проверкой как недословные / подобрано кодом / ответов с исправлением | ").append(totalQuotes).append(" / ")
                .append(rejected).append(" / ").append(auto).append(" / ").append(repaired).append(" |\n\n");

        md.append("## Режим «не знаю» (вопросы вне базы)\n\n| # | Вопрос | Чанков до → после этапа 2 | Лучший cos | Результат | Кто отказал |\n|---|---|---|---|---|---|\n");
        for (Row r : off) {
            md.append("| ").append(r.q().id()).append(" | ").append(Task21.esc(r.q().question())).append(" | ");
            if (r.error() != null || r.r() == null) {
                md.append("— | — | ошибка | — |\n");
                continue;
            }
            md.append(r.r().before().size()).append(" → ").append(r.r().after().size()).append(" | ").append(String.format(Locale.ROOT, "%.2f", r.r().bestCosine())).append(" | ")
                    .append(!r.cited().known() ? "✅ «не знаю» + уточнение" : "❌ ответила").append(" | ")
                    .append(r.cited().known() ? "—" : r.r().after().isEmpty() ? "код (контекст пуст, LLM не вызывалась)" : "модель (`known=false`)").append(" |\n");
        }
        long refused = off.stream().filter(r -> r.cited() != null && !r.cited().known()).count();
        List<Row> wronglyRefused = in.stream().filter(r -> r.error() == null && !r.cited().known()).toList();

        md.append("\n## Ответы\n\n");
        for (Row r : in) {
            detail(md, r);
        }
        for (Row r : off) {
            detail(md, r);
        }

        md.append("## Выводы\n\n");
        md.append("1. **Источники и цитаты есть в каждом данном ответе: ").append(withSources).append("/").append(answered.size()).append(" и ").append(withQuotes).append("/")
                .append(answered.size()).append(".** Это свойство конструкции, а не удача: ответ не выходит из `answerCited` без проверенного источника и проверенной цитаты.\n");
        md.append("2. **Дословность цитат: ").append(verbatim).append("/").append(answered.size()).append(" ответов.** ");
        md.append(rejected + auto == 0
                ? "Модель ни разу не выдала за цитату пересказ — все " + totalQuotes + " цитат прошли проверку с первого раза.\n"
                : "Проверка отклонила " + rejected + " «цитат» модели, которых дословно нет в чанке (пересказ, перевод, склейка), " + repaired
                + " ответов исправлено повторным запросом, " + auto + " цитат подобрано кодом из процитированного чанка. Без проверки эти «цитаты» ушли бы пользователю как настоящие.\n");
        md.append("3. **Смысл ответа и цитаты: ").append(supported).append("/").append(judged).append(" подтверждено судьёй.** ");
        List<String> unsupported = answered.stream().filter(r -> r.judge() != null && !r.judge().supported()).map(r -> r.q().id() + " (" + r.judge().comment() + ")").toList();
        md.append(unsupported.isEmpty() ? "Расхождений между ответом и его цитатами не найдено.\n"
                : "Расхождения: " + String.join("; ", unsupported) + " — дословная цитата гарантирует, что текст взят из базы, но не что из него сделан верный вывод; это ловит только отдельная проверка.\n");
        md.append("4. **Режим «не знаю»: ").append(refused).append("/").append(off.size()).append(" вопросов вне базы.** ");
        md.append(refused == off.size() ? "На все вопросы без опоры в базе ассистент ответил «не знаю» и попросил уточнить, вместо того чтобы отвечать из общих знаний модели.\n"
                : "Там, где отказа не случилось, второй этап пропустил в контекст посторонние чанки, а модель по ним ответила — порог стоит ужесточить (`RAG_MIN_RERANK`, `RAG_MIN_SIMILARITY`).\n");
        md.append("5. **Обратная сторона строгого порога.** ").append(wronglyRefused.isEmpty()
                ? "Ни на один из 10 вопросов по базе ассистент не ответил «не знаю» зря.\n"
                : "На " + wronglyRefused.size() + " вопросов по базе (" + wronglyRefused.stream().map(r -> r.q().id()).collect(Collectors.joining(", "))
                + ") ассистент ответил «не знаю», хотя ответ в базе есть: поиск или реранкер не довели нужный чанк до контекста. Это осознанный размен — лучше честный отказ с просьбой уточнить, чем уверенный ответ без опоры.\n");
        md.append("6. **Что гарантия не покрывает.** Цитата подтверждает происхождение текста, а не полноту ответа: модель может процитировать верный фрагмент и упустить часть фактов ")
                .append("(столбец «Фактов»). Полнота зависит от поиска (дни 21–23).\n");
        md.append("\n_Вызовов LLM: ").append(pipeline.llmCalls()).append(", токенов: ").append(pipeline.tokens()).append("._\n");
        Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
    }

    private static void detail(StringBuilder md, Row r) {
        md.append("### ").append(r.q().id()).append(". ").append(r.q().question()).append("\n\n");
        md.append("_Ожидание: ").append(r.q().expectation()).append("_\n\n");
        if (r.error() != null) {
            md.append("Ошибка: ").append(r.error()).append("\n\n");
            return;
        }
        RagPipeline.Cited c = r.cited();
        md.append("**Ответ:** ").append(c.answer().strip().replace("\n", " ")).append("\n\n");
        if (!c.known()) {
            md.append("**Уточнение:** ").append(c.clarify()).append("\n\n");
        }
        md.append("**Источники:**").append(c.sources().isEmpty() ? " —\n" : "\n");
        for (RagIndex.Chunk s : c.sources()) {
            md.append("- `").append(s.source()).append("` › ").append(Task21.esc(s.section())).append(" — `").append(s.chunkId()).append("`, строки ")
                    .append(s.startLine()).append("–").append(s.endLine()).append("\n");
        }
        md.append("\n**Цитаты:**").append(c.quotes().isEmpty() ? " —\n" : "\n");
        for (RagPipeline.Quote q : c.quotes()) {
            md.append("- «").append(q.text().strip().replaceAll("\\s+", " ")).append("» — `").append(q.chunkId()).append("`")
                    .append(q.auto() ? " _(подобрана кодом)_" : "").append("\n");
        }
        if (r.judge() != null) {
            md.append("\n**Судья:** ").append(r.judge().supported() ? "ответ следует из цитат" : "ответ НЕ следует из цитат")
                    .append(r.judge().comment().isBlank() ? "" : " — " + r.judge().comment()).append("\n");
        }
        if (!c.notes().isEmpty()) {
            md.append("\n_").append(String.join("; ", c.notes())).append("_\n");
        }
        md.append("\n");
    }
}
