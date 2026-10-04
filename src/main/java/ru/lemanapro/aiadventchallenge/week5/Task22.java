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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Day 22: the first RAG request.
 *
 * RagAgent answers the same question in two modes:
 *   NO_RAG — question -> LLM;
 *   RAG    — question -> top-K relevant chunks of the day-21 index -> chunks + question -> LLM.
 * This day uses the plain pipeline on purpose: top-K by cosine, no filtering, no reranking, no query
 * rewrite (those are day 23), free-text answers (citations are day 24).
 *
 * Control set (ControlSet): 10 questions about the knowledge base; for each one the expectation (what the
 * answer must contain) and the sources that hold the answer are fixed in advance. Each answer is scored
 * automatically: share of expected facts found in the text; for RAG also whether a relevant chunk and an
 * expected source made it into the context. For NO_RAG the report notes whether the model admitted it does
 * not know or answered anyway (i.e. guessed / hallucinated).
 * Writes task22-rag-report.md.
 *
 * Env: RAG_STRATEGY (structure | fixed — which day-21 index to use), RAG_TOP_K (default 5).
 * Usage:
 *   mvn -q compile exec:java -Ptask22                              10 control questions, both modes, report
 *   mvn -q exec:java -Ptask22 -Dexec.args="Ваш вопрос"             one question, both modes
 *   mvn -q exec:java -Ptask22 -Dexec.args="rag Ваш вопрос"         one question, only with RAG (norag — only without)
 */
public final class Task22 {

    private static final Path REPORT_FILE = Path.of("task22-rag-report.md");
    private static final Pattern ADMITS = Pattern.compile(
            "не знаю|нет (точн\\S+ )?(информаци|данных|сведени|доступа)|не (могу|имею|располагаю|обладаю)|недостаточно|мне неизвест|не указан|нет ответа|уточнит",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    record Row(ControlSet.Question q, RagAgent.Reply noRag, RagAgent.Reply rag) {
        double noRagScore() {
            return noRag.failed() ? 0 : q.score(noRag.answer());
        }

        double ragScore() {
            return rag.failed() ? 0 : q.score(rag.answer());
        }

        boolean relevantInContext() {
            return rag.retrieval() != null && rag.retrieval().after().stream().anyMatch(c -> q.relevant(c.chunk()));
        }

        boolean sourceInContext() {
            return rag.retrieval() != null && rag.retrieval().after().stream().anyMatch(c -> q.sourceMatches(c.chunk().source()));
        }

        boolean noRagAdmits() {
            return ADMITS.matcher(noRag.answer()).find();
        }
    }

    private Task22() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        HttpClient http = LlmClient.newHttpClient();
        RagIndex index = RagIndex.open(RagIndex.Strategy.of(LlmClient.env("RAG_STRATEGY", "structure")), http, cfg);
        RagPipeline pipeline = new RagPipeline(http, cfg, index);
        int topK = Integer.parseInt(LlmClient.env("RAG_TOP_K", "5"));
        RagAgent agent = new RagAgent(pipeline, RagPipeline.Settings.baseline(topK));

        System.out.println("=== День 22. Первый RAG-запрос ===");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println("Индекс: " + index.meta().strategy() + ", " + index.chunks().size() + " чанков, эмбеддер " + index.meta().embedder()
                + "; в контекст идут top-" + topK + " по косинусу");

        if (args.length > 0) {
            List<String> words = new ArrayList<>(List.of(args));
            String first = words.getFirst().toLowerCase(Locale.ROOT);
            boolean onlyRag = first.equals("rag");
            boolean onlyNoRag = first.equals("norag");
            if (onlyRag || onlyNoRag) {
                words.removeFirst();
            }
            String question = String.join(" ", words).strip();
            if (question.isEmpty()) {
                System.out.println("Укажите вопрос: -Dexec.args=\"rag Ваш вопрос\"");
                return;
            }
            if (!onlyRag) {
                print(agent.ask(question, RagAgent.Mode.NO_RAG));
            }
            if (!onlyNoRag) {
                print(agent.ask(question, RagAgent.Mode.RAG));
            }
            return;
        }

        List<Row> rows = new ArrayList<>();
        for (ControlSet.Question q : ControlSet.QUESTIONS) {
            System.out.println("\n" + q.id() + ". " + q.question());
            System.out.println("    ожидание: " + q.expectation());
            RagAgent.Reply noRag = agent.ask(q.question(), RagAgent.Mode.NO_RAG);
            RagAgent.Reply rag = agent.ask(q.question(), RagAgent.Mode.RAG);
            Row row = new Row(q, noRag, rag);
            rows.add(row);
            System.out.printf("    без RAG: %s (%s) — %s%n", facts(q, row.noRagScore()), row.noRagAdmits() ? "признал, что не знает" : "ответил уверенно",
                    Chunker.clip(noRag.answer().replaceAll("\\s+", " "), 150));
            System.out.printf("    с RAG:   %s, релевантный чанк в контексте: %s — %s%n", facts(q, row.ragScore()), row.relevantInContext() ? "да" : "нет",
                    Chunker.clip(rag.answer().replaceAll("\\s+", " "), 150));
        }
        double avgNo = rows.stream().mapToDouble(Row::noRagScore).average().orElse(0);
        double avgRag = rows.stream().mapToDouble(Row::ragScore).average().orElse(0);
        System.out.printf("%nИТОГ: средняя доля ожидаемых фактов в ответе — без RAG %.0f %%, с RAG %.0f %%; вызовов LLM: %d%n",
                100 * avgNo, 100 * avgRag, pipeline.llmCalls());
        writeReport(cfg, index, topK, rows, pipeline);
        System.out.println("Отчёт: " + REPORT_FILE.toAbsolutePath());
    }

    private static void print(RagAgent.Reply reply) {
        System.out.println("\n--- " + reply.mode().label + " (" + reply.millis() + " мс) ---");
        if (reply.retrieval() != null) {
            System.out.println("Найденные чанки:");
            for (RagPipeline.Candidate c : reply.retrieval().after()) {
                System.out.printf(Locale.ROOT, "  %.3f  %s%n", c.cosine(), c.chunk().ref());
            }
        }
        System.out.println(reply.answer());
    }

    private static String facts(ControlSet.Question q, double score) {
        return Math.round(score * q.facts().size()) + "/" + q.facts().size();
    }

    // ---------------------------------------------------------------- report

    private static void writeReport(LlmClient.Config cfg, RagIndex index, int topK, List<Row> rows, RagPipeline pipeline) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 22. Первый RAG-запрос\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask22`, модель ").append(cfg.model()).append(", индекс `").append(index.meta().strategy())
                .append("` (").append(index.chunks().size()).append(" чанков, эмбеддер `").append(index.meta().embedder()).append("`)\n\n");
        md.append("```\nбез RAG:  вопрос ───────────────────────────────────────────────▶ LLM ─▶ ответ\n")
                .append("с RAG:    вопрос ─▶ эмбеддинг ─▶ top-").append(topK).append(" чанков из индекса ─▶ [чанки + вопрос] ─▶ LLM ─▶ ответ\n```\n\n");
        md.append("Агент `RagAgent` имеет два режима (`NO_RAG` / `RAG`) над одной и той же моделью. В этот день пайплайн намеренно простой: ")
                .append("top-").append(topK).append(" по косинусу без фильтрации, реранкинга и переформулировки запроса (это день 23).\n\n");
        md.append("**Как оценивается.** Для каждого вопроса заранее зафиксированы ожидание и источники (`ControlSet`). ")
                .append("Оценка ответа — доля ожидаемых фактов, найденных в тексте (регулярные выражения по идентификаторам, числам, ключевым словам). ")
                .append("Это дешёвый прокси корректности, а не судья: он не ловит лишние неверные утверждения.\n\n");

        md.append("## Сводка\n\n| # | Вопрос | Без RAG | С RAG | Релевантный чанк в контексте | Без RAG модель… |\n|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            md.append("| ").append(r.q().id()).append(" | ").append(Task21.esc(Chunker.clip(r.q().question(), 80))).append(" | ")
                    .append(facts(r.q(), r.noRagScore())).append(" | ").append(facts(r.q(), r.ragScore())).append(" | ")
                    .append(r.relevantInContext() ? "✅" : r.sourceInContext() ? "⚠️ источник есть, нужного места нет" : "❌").append(" | ")
                    .append(r.noRag().failed() ? "ошибка" : r.noRagAdmits() ? "признала, что не знает" : r.noRagScore() >= 1 ? "ответила верно" : "ответила уверенно, но мимо")
                    .append(" |\n");
        }
        double avgNo = rows.stream().mapToDouble(Row::noRagScore).average().orElse(0);
        double avgRag = rows.stream().mapToDouble(Row::ragScore).average().orElse(0);
        long better = rows.stream().filter(r -> r.ragScore() > r.noRagScore() + 1e-9).count();
        long worse = rows.stream().filter(r -> r.ragScore() < r.noRagScore() - 1e-9).count();
        long fullNo = rows.stream().filter(r -> r.noRagScore() >= 1).count();
        long fullRag = rows.stream().filter(r -> r.ragScore() >= 1).count();
        long relevant = rows.stream().filter(Row::relevantInContext).count();
        long guessed = rows.stream().filter(r -> !r.noRag().failed() && !r.noRagAdmits() && r.noRagScore() < 1).count();
        md.append(String.format("%n| Метрика | Без RAG | С RAG |%n|---|---|---|%n| Средняя доля ожидаемых фактов | %.0f %% | %.0f %% |%n"
                        + "| Вопросов со всеми фактами | %d/%d | %d/%d |%n| Релевантный чанк попал в контекст | — | %d/%d |%n%n",
                100 * avgNo, 100 * avgRag, fullNo, rows.size(), fullRag, rows.size(), relevant, rows.size()));

        md.append("## Вопросы и ответы\n\n");
        for (Row r : rows) {
            ControlSet.Question q = r.q();
            md.append("### ").append(q.id()).append(". ").append(q.question()).append("\n\n");
            md.append("- **Ожидание:** ").append(q.expectation()).append("\n");
            md.append("- **Источники, где есть ответ:** ").append(Task21.joinSources(q.sources())).append("\n\n");
            md.append("**Без RAG** — фактов ").append(facts(q, r.noRagScore())).append(missing(q, r.noRag())).append("\n\n")
                    .append(quote(r.noRag().answer())).append("\n\n");
            md.append("**С RAG** — фактов ").append(facts(q, r.ragScore())).append(missing(q, r.rag())).append("\n\n");
            if (r.rag().retrieval() != null) {
                md.append("Контекст (cos — чанк): ").append(r.rag().retrieval().after().stream()
                        .map(c -> String.format(Locale.ROOT, "%.2f — `%s`%s", c.cosine(), Task21.esc(c.chunk().ref()), q.relevant(c.chunk()) ? " ✅" : ""))
                        .collect(Collectors.joining("; "))).append("\n\n");
            }
            md.append(quote(r.rag().answer())).append("\n\n");
        }

        md.append("## Выводы\n\n");
        md.append(String.format("1. **RAG меняет качество на вопросах по частной базе.** Средняя доля ожидаемых фактов: %.0f %% без RAG → %.0f %% с RAG; "
                        + "RAG лучше в %d вопросах из %d, хуже — в %d.%n", 100 * avgNo, 100 * avgRag, better, rows.size(), worse));
        md.append("2. **Без RAG модель не может знать факты проекта.** ");
        md.append(guessed > 0
                ? "В " + guessed + " вопросах из " + rows.size() + " она ответила уверенно, но ожидаемых фактов в ответе нет или они неполны — это выдумывание правдоподобных деталей (имена переменных, числа, названия файлов)."
                : "Ни одного уверенного, но неверного ответа не зафиксировано: модель либо признавала незнание, либо отвечала верно.");
        md.append(" Честно признала незнание в ").append(rows.stream().filter(r -> !r.noRag().failed() && r.noRagAdmits()).count()).append(" вопросах.\n");
        md.append("3. **Качество RAG упирается в поиск.** Релевантный чанк оказался в контексте в ").append(relevant).append(" вопросах из ").append(rows.size()).append(". ");
        List<String> missedIds = rows.stream().filter(r -> !r.relevantInContext()).map(r -> r.q().id()).toList();
        md.append(missedIds.isEmpty()
                ? "Простого top-" + topK + " по косинусу здесь хватило для всех вопросов.\n"
                : "Там, где его нет (" + String.join(", ", missedIds) + "), модель отвечает по соседним фрагментам или сообщает, что ответа в контексте нет, — "
                + "это задача для реранкинга, фильтрации и переформулировки запроса (день 23).\n");
        List<String> lost = rows.stream().filter(r -> r.relevantInContext() && r.ragScore() < 1).map(r -> r.q().id()).toList();
        md.append("4. **Найти — ещё не значит ответить.** ").append(lost.isEmpty()
                ? "Во всех вопросах, где нужный чанк был в контексте, ответ содержит все ожидаемые факты.\n"
                : "В " + String.join(", ", lost) + " нужный чанк был в контексте, но ответ неполон: часть фактов лежит в других чанках либо модель не вытащила их из длинного фрагмента.\n");
        if (index.meta().embedder().startsWith("local-")) {
            md.append("5. **Оговорка.** Индекс построен локальным лексическим эмбеддером: он находит чанки по общим словам и идентификаторам, ")
                    .append("а не по смыслу. С настоящей embedding-моделью (`EMBEDDING_MODEL`, затем `-Ptask21`) поиск по русским вопросам к английским документам станет заметно лучше.\n");
        }
        md.append("\n_Вызовов LLM: ").append(pipeline.llmCalls()).append(", токенов: ").append(pipeline.tokens()).append("._\n");
        Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
    }

    private static String missing(ControlSet.Question q, RagAgent.Reply reply) {
        if (reply.failed()) {
            return " (вызов не удался)";
        }
        List<String> missing = q.missing(reply.answer());
        return missing.isEmpty() ? "" : "; не найдено: " + missing.stream().map(m -> "`" + Task21.esc(m) + "`").collect(Collectors.joining(", "));
    }

    static String quote(String text) {
        return Chunker.clip(text.strip(), 1200).lines().map(l -> "> " + l).collect(Collectors.joining("\n"));
    }
}
