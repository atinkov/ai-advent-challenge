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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Day 21: document indexing.
 *
 * Pipeline: documents (this project's *.md and *.java — the knowledge base of week 5) -> chunking ->
 * embeddings -> local index saved as JSON, one file per chunking strategy:
 *   task21-index-fixed.json      fixed-size windows (RAG_FIXED_SIZE chars, RAG_FIXED_OVERLAP overlap)
 *   task21-index-structure.json  by document structure (markdown headings / table rows, java members)
 * Every chunk carries metadata: chunk_id, source, title, section, strategy, ordinal, line range.
 *
 * The two strategies are compared on
 *   - chunk statistics (count, sizes, duplicated text);
 *   - boundary quality: how many chunks start mid-line, how many structural units (a markdown section,
 *     a java member) that would fit into one chunk were torn across chunks;
 *   - retrieval: for the 10 control questions (ControlSet) — rank of the first RELEVANT chunk in a plain
 *     vector search (Hit@1/3/5, MRR). No LLM is involved on this day.
 * Writes task21-indexing-report.md.
 *
 * Embeddings: Embedder.fromEnv — an embedding model behind the OpenAI-compatible API (EMBEDDING_MODEL or
 * auto-discovered in GET /models), else the local lexical fallback. Works without an API key (local only).
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask21                                    build both indexes + report
 *   mvn -q exec:java -Ptask21 -Dexec.args="search как работает сжатие"   query the saved indexes (top-5 each)
 */
public final class Task21 {

    private static final Path REPORT_FILE = Path.of("task21-indexing-report.md");
    private static final int PAGE_CHARS = 1800;
    private static final int PROBE_DEPTH = 10;

    record Stats(int chunks, double avg, int median, int min, int max, int p95, int tiny, long totalChars, long corpusChars,
                 int midLineStarts, int midLineEnds, int unitsFit, int unitsWhole, int sections, int withSection) {
        double duplication() {
            return corpusChars == 0 ? 0 : 100.0 * (totalChars - corpusChars) / corpusChars;
        }
    }

    record Probe(ControlSet.Question q, int rank, RagIndex.Hit top1, RagIndex.Hit firstRelevant) {
    }

    record Built(RagIndex.Strategy strategy, List<RagIndex.Draft> drafts, Stats stats, RagIndex index, double embedSeconds, long fileBytes,
                 List<Probe> probes) {
        double hitAt(int k) {
            return probes.stream().filter(p -> p.rank() > 0 && p.rank() <= k).count() / (double) probes.size();
        }

        double mrr() {
            return probes.stream().mapToDouble(p -> p.rank() > 0 ? 1.0 / p.rank() : 0).sum() / probes.size();
        }
    }

    private Task21() {
    }

    public static void main(String[] args) throws Exception {
        boolean hasKey = !LlmClient.env("LLM_API_KEY", "").isBlank();
        LlmClient.Config cfg = hasKey ? LlmClient.fromEnv() : null;
        HttpClient http = LlmClient.newHttpClient();

        if (args.length > 0 && args[0].equalsIgnoreCase("search")) {
            String query = String.join(" ", List.of(args).subList(1, args.length)).strip();
            search(query.isEmpty() ? "как работает сжатие истории" : query, http, cfg);
            return;
        }

        System.out.println("=== День 21. Индексация документов ===");
        Path root = RagIndex.root();
        List<Chunker.Doc> docs = RagIndex.loadCorpus(root);
        long corpusChars = docs.stream().mapToLong(d -> d.text().length()).sum();
        System.out.printf("База: %d документов из %s — %d знаков ≈ %d страниц (по %d знаков)%n",
                docs.size(), root, corpusChars, corpusChars / PAGE_CHARS, PAGE_CHARS);
        if (corpusChars < 20L * PAGE_CHARS) {
            System.out.println("ВНИМАНИЕ: в базе меньше 20 страниц текста.");
        }

        Embedder embedder = Embedder.fromEnv(http, cfg);
        System.out.println("Эмбеддер: " + embedder.id() + (embedder.isLocal()
                ? "  (локальный лексический; для настоящей embedding-модели задайте EMBEDDING_MODEL)" : ""));

        List<Built> built = new ArrayList<>();
        for (RagIndex.Strategy strategy : RagIndex.Strategy.values()) {
            System.out.println("\n--- Стратегия " + strategy.id + " (" + RagIndex.params(strategy) + ") ---");
            List<RagIndex.Draft> drafts = RagIndex.chunk(strategy, docs);
            Stats stats = stats(strategy, docs, drafts);
            System.out.printf("Чанков: %d, длина средн. %.0f / медиана %d / мин %d / макс %d%n",
                    stats.chunks(), stats.avg(), stats.median(), stats.min(), stats.max());
            long t0 = System.nanoTime();
            RagIndex index = RagIndex.build(strategy, docs, drafts, embedder);
            double seconds = (System.nanoTime() - t0) / 1e9;
            Path file = RagIndex.pathFor(strategy);
            index.save(file);
            System.out.printf("Эмбеддинги: %d векторов × %d за %.1f с → %s (%.1f МБ)%n",
                    index.chunks().size(), index.meta().dim(), seconds, file, Files.size(file) / 1e6);
            // the probe runs on the index re-read from disk: proves that the saved file is a working index
            RagIndex loaded = RagIndex.load(file);
            List<Probe> probes = probe(loaded, loaded.embedder(http, cfg));
            built.add(new Built(strategy, drafts, stats, loaded, seconds, Files.size(file), probes));
        }

        System.out.println("\n--- Поисковая проверка на 10 контрольных вопросах (ранг первого релевантного чанка в top-" + PROBE_DEPTH + ") ---");
        Built fixed = built.get(0);
        Built structure = built.get(1);
        for (int i = 0; i < ControlSet.QUESTIONS.size(); i++) {
            System.out.printf("%-4s fixed: %-3s structure: %-3s %s%n", ControlSet.QUESTIONS.get(i).id(),
                    rank(fixed.probes().get(i)), rank(structure.probes().get(i)), Chunker.clip(ControlSet.QUESTIONS.get(i).question(), 80));
        }
        for (Built b : built) {
            System.out.printf("%-9s Hit@1 %.0f%% | Hit@3 %.0f%% | Hit@5 %.0f%% | MRR %.2f%n", b.strategy().id,
                    100 * b.hitAt(1), 100 * b.hitAt(3), 100 * b.hitAt(5), b.mrr());
        }

        writeReport(root, docs, corpusChars, embedder, fixed, structure);
        System.out.println("\nОтчёт: " + REPORT_FILE.toAbsolutePath());
    }

    // ---------------------------------------------------------------- statistics

    private static Stats stats(RagIndex.Strategy strategy, List<Chunker.Doc> docs, List<RagIndex.Draft> drafts) {
        int[] lengths = drafts.stream().mapToInt(d -> d.piece().text().length()).sorted().toArray();
        long total = 0;
        int tiny = 0;
        int midStart = 0;
        int midEnd = 0;
        Set<String> sections = new LinkedHashSet<>();
        int withSection = 0;
        Map<String, List<Chunker.Piece>> byDoc = new LinkedHashMap<>();
        for (RagIndex.Draft d : drafts) {
            Chunker.Piece p = d.piece();
            String text = d.doc().text();
            total += p.text().length();
            tiny += p.text().length() < 200 ? 1 : 0;
            midStart += p.start() > 0 && text.charAt(p.start() - 1) != '\n' ? 1 : 0;
            midEnd += p.end() < text.length() && text.charAt(p.end() - 1) != '\n' ? 1 : 0;
            if (!p.section().isBlank()) {
                withSection++;
                sections.add(d.doc().source() + "#" + p.section());
            }
            byDoc.computeIfAbsent(d.doc().source(), k -> new ArrayList<>()).add(p);
        }
        // structural units that fit into one chunk: how many of them are fully inside at least one chunk
        int limit = strategy == RagIndex.Strategy.FIXED ? RagIndex.fixedSize() : RagIndex.structMax();
        int unitsFit = 0;
        int unitsWhole = 0;
        for (Chunker.Doc doc : docs) {
            List<Chunker.Piece> pieces = byDoc.getOrDefault(doc.source(), List.of());
            for (Chunker.Unit u : Chunker.outline(doc, RagIndex.structMax())) {
                int[] core = trim(doc.text(), u.start(), u.end());
                if (core[1] - core[0] > Math.min(limit, RagIndex.fixedSize()) || core[1] <= core[0]) {
                    continue; // judge both strategies on the same units: those small enough for either
                }
                unitsFit++;
                for (Chunker.Piece p : pieces) {
                    if (p.start() <= core[0] && p.end() >= core[1]) {
                        unitsWhole++;
                        break;
                    }
                }
            }
        }
        long corpusChars = docs.stream().mapToLong(d -> d.text().length()).sum();
        int n = lengths.length;
        return new Stats(n, n == 0 ? 0 : (double) total / n, n == 0 ? 0 : lengths[n / 2], n == 0 ? 0 : lengths[0], n == 0 ? 0 : lengths[n - 1],
                n == 0 ? 0 : lengths[(int) Math.min(n - 1, Math.round(n * 0.95))], tiny, total, corpusChars, midStart, midEnd,
                unitsFit, unitsWhole, sections.size(), withSection);
    }

    private static int[] trim(String text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return new int[]{start, end};
    }

    // ---------------------------------------------------------------- retrieval probe

    private static List<Probe> probe(RagIndex index, Embedder embedder) throws Exception {
        List<String> questions = ControlSet.QUESTIONS.stream().map(ControlSet.Question::question).toList();
        float[][] vectors = embedder.embed(questions);
        List<Probe> out = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            ControlSet.Question q = ControlSet.QUESTIONS.get(i);
            List<RagIndex.Hit> hits = index.search(vectors[i], PROBE_DEPTH);
            int rank = 0;
            RagIndex.Hit relevant = null;
            for (int r = 0; r < hits.size(); r++) {
                if (q.relevant(hits.get(r).chunk())) {
                    rank = r + 1;
                    relevant = hits.get(r);
                    break;
                }
            }
            out.add(new Probe(q, rank, hits.isEmpty() ? null : hits.getFirst(), relevant));
        }
        return out;
    }

    private static String rank(Probe p) {
        return p.rank() == 0 ? "—" : String.valueOf(p.rank());
    }

    private static void search(String query, HttpClient http, LlmClient.Config cfg) throws Exception {
        System.out.println("Запрос: " + query);
        for (RagIndex.Strategy strategy : RagIndex.Strategy.values()) {
            Path file = RagIndex.pathFor(strategy);
            if (!Files.isRegularFile(file)) {
                System.out.println("\n" + file + " не найден — сначала постройте индекс: mvn -q compile exec:java -Ptask21");
                continue;
            }
            RagIndex index = RagIndex.load(file);
            float[] v = index.embedder(http, cfg).embed(List.of(query))[0];
            System.out.println("\n--- " + strategy.id + " (" + index.meta().embedder() + ", " + index.chunks().size() + " чанков) ---");
            for (RagIndex.Hit h : index.search(v, 5)) {
                System.out.printf("%.3f  %s  (строки %d–%d)%n        %s%n", h.score(), h.chunk().ref(), h.chunk().startLine(), h.chunk().endLine(),
                        Chunker.clip(h.chunk().text().strip().replaceAll("\\s+", " "), 160));
            }
        }
    }

    // ---------------------------------------------------------------- report

    private static void writeReport(Path root, List<Chunker.Doc> docs, long corpusChars, Embedder embedder, Built fixed, Built structure) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 21. Индексация документов\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask21`, эмбеддер `").append(embedder.id()).append("` (размерность ")
                .append(fixed.index().meta().dim()).append(")\n\n");
        md.append("```\nдокументы (*.md, *.java) ─▶ chunking (2 стратегии) ─▶ эмбеддинги ─▶ JSON-индекс (чанк + метаданные + вектор)\n")
                .append("                               fixed / structure         ").append(Chunker.clip(embedder.id(), 22))
                .append("        task21-index-<стратегия>.json\n```\n\n");
        if (embedder.isLocal()) {
            md.append("> ⚠️ Embedding-модель по API не найдена, использован **локальный лексический эмбеддер** (`").append(embedder.id())
                    .append("`: хешированный мешок основ слов с TF-IDF). Он сопоставляет общие слова и идентификаторы, а не смысл, ")
                    .append("и не связывает русский вопрос с английским текстом. Для настоящих эмбеддингов задайте `EMBEDDING_MODEL` ")
                    .append("(и при необходимости `EMBEDDING_BASE_URL`) и перезапустите день 21.\n\n");
        }

        md.append("## База документов\n\n");
        md.append("База знаний — сам проект: `AGENTS.md`, отчёты дней и исходный код. Корень: `").append(root.getFileName()).append("/`.\n\n");
        md.append("| Тип | Файлов | Знаков | ≈ страниц (").append(PAGE_CHARS).append(" зн.) |\n|---|---|---|---|\n");
        for (String kind : List.of("md", "java", "text")) {
            List<Chunker.Doc> of = docs.stream().filter(d -> d.kind().equals(kind)).toList();
            if (of.isEmpty()) {
                continue;
            }
            long chars = of.stream().mapToLong(d -> d.text().length()).sum();
            md.append("| ").append(kind.equals("md") ? "Markdown (README-подобные документы, отчёты)" : kind.equals("java") ? "Java (код)" : "Текст")
                    .append(" | ").append(of.size()).append(" | ").append(chars).append(" | ").append(chars / PAGE_CHARS).append(" |\n");
        }
        md.append("| **Всего** | **").append(docs.size()).append("** | **").append(corpusChars).append("** | **").append(corpusChars / PAGE_CHARS)
                .append("** |\n\n");
        md.append("Требование «минимум 20–30 страниц» выполнено с запасом. Не индексируются: `target/`, служебные каталоги, сгенерированные ")
                .append("`task19-output/`, `task20-output/` и сам пакет `week5/` с отчётами дней 21–25 — в них лежат контрольные вопросы вместе ")
                .append("с ответами, их индексация была бы утечкой ответов.\n\n");
        md.append("<details><summary>Список документов</summary>\n\n| Источник (source) | Заголовок (title) | Знаков |\n|---|---|---|\n");
        for (Chunker.Doc d : docs) {
            md.append("| `").append(d.source()).append("` | ").append(esc(Chunker.clip(d.title(), 70))).append(" | ").append(d.text().length()).append(" |\n");
        }
        md.append("\n</details>\n\n");

        md.append("## Две стратегии chunking\n\n");
        md.append("- **fixed** (`").append(RagIndex.params(RagIndex.Strategy.FIXED)).append("`) — скользящее окно фиксированного размера с перекрытием; ")
                .append("о документе ничего не знает, только сдвигает разрез к ближайшему пробелу, чтобы не рвать слово. Эмбеддится «как есть».\n");
        md.append("- **structure** (`").append(RagIndex.params(RagIndex.Strategy.STRUCTURE)).append("`) — по структуре документа: Markdown — раздел под заголовком; ")
                .append("раздел больше лимита делится по своим блокам (строки таблицы, пункты списка, абзацы, блоки кода), слишком длинный блок — ")
                .append("по предложениям. Java — член класса (метод, группа полей, вложенный тип), границы ищет маленький лексер по глубине скобок ")
                .append("вне строк и комментариев. Эмбеддится вместе с «хлебными крошками» `title › section` (и шапкой таблицы, если чанк — её строки).\n\n");
        md.append("Текст любого чанка — **дословный срез** исходного документа (хранятся строки начала/конца), поэтому цитата из чанка всегда ")
                .append("является цитатой из источника.\n\n");

        md.append("## Метаданные чанка\n\n");
        md.append("| Поле | Что это |\n|---|---|\n")
                .append("| `chunkId` | идентификатор в индексе: `f-0001…` (fixed), `s-0001…` (structure) |\n")
                .append("| `source` | путь к файлу относительно корня базы |\n")
                .append("| `title` | заголовок документа (H1 для Markdown, имя файла для кода) |\n")
                .append("| `section` | раздел: путь заголовков `A › B`, для строки таблицы — её первый столбец; для кода — `Класс.метод()` |\n")
                .append("| `strategy`, `ordinal` | стратегия и порядковый номер чанка внутри документа |\n")
                .append("| `startLine`, `endLine` | диапазон строк в исходном файле |\n")
                .append("| `lead` | контекст, которого нет в срезе (шапка разрезанной таблицы) |\n")
                .append("| `text`, `vector` | текст и нормированный эмбеддинг |\n\n");
        // the same place of the base as each index stores it: the first relevant structural hit and the fixed chunk over the same lines
        RagIndex.Chunk anchor = structure.probes().stream().filter(p -> p.firstRelevant() != null).map(p -> p.firstRelevant().chunk())
                .findFirst().orElse(structure.index().chunks().getFirst());
        RagIndex.Chunk twin = fixed.index().chunks().stream()
                .filter(c -> c.source().equals(anchor.source()) && c.startLine() <= anchor.startLine() && c.endLine() >= anchor.startLine())
                .findFirst().orElse(fixed.index().chunks().getFirst());
        md.append("Одно и то же место базы (`").append(anchor.source()).append("`, строка ").append(anchor.startLine()).append(") в двух индексах:\n\n");
        for (RagIndex.Chunk c : List.of(twin, anchor)) {
            md.append("```json\n{\"chunkId\": \"").append(c.chunkId()).append("\", \"source\": \"").append(c.source()).append("\", \"title\": \"")
                    .append(jsonEsc(c.title())).append("\",\n \"section\": \"").append(jsonEsc(c.section())).append("\", \"strategy\": \"").append(c.strategy())
                    .append("\", \"ordinal\": ").append(c.ordinal()).append(", \"startLine\": ").append(c.startLine()).append(", \"endLine\": ").append(c.endLine())
                    .append(",\n \"lead\": \"").append(jsonEsc(Chunker.clip(c.lead(), 60))).append("\",\n \"text\": \"")
                    .append(jsonEsc(Chunker.clip(c.text().strip().replaceAll("\\s+", " "), 150))).append("\",\n \"vector\": [")
                    .append(String.format(Locale.ROOT, "%.5f, %.5f, %.5f", c.vector()[0], c.vector()[1], c.vector()[2])).append(", … ").append(c.vector().length)
                    .append(" чисел]}\n```\n\n");
        }

        md.append("## Сравнение стратегий\n\n| Метрика | fixed | structure |\n|---|---|---|\n");
        Stats f = fixed.stats();
        Stats s = structure.stats();
        row(md, "Чанков", f.chunks(), s.chunks());
        row(md, "Длина чанка: средняя / медиана", String.format("%.0f / %d", f.avg(), f.median()), String.format("%.0f / %d", s.avg(), s.median()));
        row(md, "Длина чанка: мин / 95-й перцентиль / макс", f.min() + " / " + f.p95() + " / " + f.max(), s.min() + " / " + s.p95() + " / " + s.max());
        row(md, "Коротких чанков (< 200 знаков)", f.tiny(), s.tiny());
        row(md, "Суммарный текст в индексе, знаков", f.totalChars(), s.totalChars());
        row(md, "Дублирование текста относительно базы", String.format("%+.1f %%", f.duplication()), String.format("%+.1f %%", s.duplication()));
        row(md, "Чанк начинается посреди строки", pct(f.midLineStarts(), f.chunks()), pct(s.midLineStarts(), s.chunks()));
        row(md, "Чанк обрывается посреди строки", pct(f.midLineEnds(), f.chunks()), pct(s.midLineEnds(), s.chunks()));
        row(md, "Единицы структуры (раздел / член класса), целиком попавшие в один чанк ¹", pct(f.unitsWhole(), f.unitsFit()), pct(s.unitsWhole(), s.unitsFit()));
        row(md, "Чанков с заполненным `section`", pct(f.withSection(), f.chunks()), pct(s.withSection(), s.chunks()));
        row(md, "Разных значений `section`", f.sections(), s.sections());
        row(md, "Время эмбеддинга, с", String.format("%.1f", fixed.embedSeconds()), String.format("%.1f", structure.embedSeconds()));
        row(md, "Файл индекса", String.format("`%s` — %.1f МБ", RagIndex.pathFor(RagIndex.Strategy.FIXED).getFileName(), fixed.fileBytes() / 1e6),
                String.format("`%s` — %.1f МБ", RagIndex.pathFor(RagIndex.Strategy.STRUCTURE).getFileName(), structure.fileBytes() / 1e6));
        md.append("\n¹ Считаются только единицы, которые помещаются в чанк любой из стратегий (≤ ").append(RagIndex.fixedSize())
                .append(" знаков): раздел Markdown, метод/группа полей Java. «Целиком» — существует чанк, содержащий единицу полностью.\n\n");

        md.append("## Поисковая проверка\n\n");
        md.append("10 контрольных вопросов (`ControlSet`), чистый векторный поиск по сохранённому и заново прочитанному с диска индексу, без LLM. ")
                .append("Ранг — позиция первого **релевантного** чанка в top-").append(PROBE_DEPTH).append(" (чанк из ожидаемого источника, содержащий искомый факт).\n\n");
        md.append("| # | Вопрос | fixed: ранг | fixed: top-1 | structure: ранг | structure: top-1 |\n|---|---|---|---|---|---|\n");
        for (int i = 0; i < ControlSet.QUESTIONS.size(); i++) {
            Probe pf = fixed.probes().get(i);
            Probe ps = structure.probes().get(i);
            md.append("| ").append(pf.q().id()).append(" | ").append(esc(Chunker.clip(pf.q().question(), 90))).append(" | ").append(rank(pf)).append(" | ")
                    .append(top(pf)).append(" | ").append(rank(ps)).append(" | ").append(top(ps)).append(" |\n");
        }
        md.append("\n| Стратегия | Hit@1 | Hit@3 | Hit@5 | MRR |\n|---|---|---|---|---|\n");
        for (Built b : List.of(fixed, structure)) {
            md.append(String.format("| %s | %.0f %% | %.0f %% | %.0f %% | %.2f |%n", b.strategy().id, 100 * b.hitAt(1), 100 * b.hitAt(3), 100 * b.hitAt(5), b.mrr()));
        }

        md.append("\n## Выводы\n\n");
        md.append("1. **Индекс построен и работает локально.** ").append(docs.size()).append(" документов, ").append(corpusChars).append(" знаков (≈ ")
                .append(corpusChars / PAGE_CHARS).append(" страниц) → ").append(f.chunks()).append(" чанков (fixed) и ").append(s.chunks())
                .append(" чанков (structure); каждый чанк хранится в JSON с метаданными и вектором, поиск — косинус по нормированным векторам.\n");
        md.append("2. **fixed предсказуем по размеру, но слеп к содержанию.** Почти все чанки одной длины (медиана ").append(f.median())
                .append("), зато ").append(pct(f.midLineStarts(), f.chunks())).append(" чанков начинаются посреди строки, а целиком в один чанк попало ")
                .append(pct(f.unitsWhole(), f.unitsFit())).append(" разделов и методов, которые туда помещались бы. Перекрытие частично это лечит ценой ")
                .append(String.format("%+.1f %%", f.duplication())).append(" дублированного текста.\n");
        md.append("3. **structure сохраняет смысловые единицы.** Целыми остались ").append(pct(s.unitsWhole(), s.unitsFit()))
                .append(" единиц, посреди строки начинаются ").append(pct(s.midLineStarts(), s.chunks()))
                .append(" чанков (только части слишком длинных строк таблиц, разрезанные по предложениям). Плата — разброс длины (")
                .append(s.min()).append("–").append(s.max()).append(" знаков) и более сложный код разбиения под каждый тип документа.\n");
        md.append("4. **Метаданные осмысленны только у structure.** У неё `section` — это ").append(s.sections())
                .append(" точных адресов вида `AGENTS.md › WHERE TO LOOK › Day 18` или `McpRegistry.route()`; у fixed раздел — лишь тот, ")
                .append("в котором чанк начался (границы окна с разделами не совпадают). Для ответов со ссылками на источники (день 24) это принципиально.\n");
        md.append("5. **Поиск.** ").append(retrievalVerdict(fixed, structure)).append("\n");
        md.append("6. **Дальше** (дни 22–25) по умолчанию используется индекс `structure` (`RAG_STRATEGY=fixed` переключает на fixed)")
                .append(structure.mrr() + 0.05 < fixed.mrr() ? " — хотя на этой проверке fixed нашёл лучше, structure даёт точные адреса источников для цитирования." : ".").append("\n");
        if (embedder.isLocal()) {
            md.append("7. **Оговорка про эмбеддер.** Цифры поиска получены на локальном лексическом эмбеддере; с настоящей embedding-моделью ")
                    .append("(особенно мультиязычной) абсолютные значения будут другими — перезапустите день 21 с `EMBEDDING_MODEL`.\n");
        }
        Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
    }

    private static String retrievalVerdict(Built fixed, Built structure) {
        String nums = String.format("fixed — Hit@1 %.0f %%, Hit@5 %.0f %%, MRR %.2f; structure — Hit@1 %.0f %%, Hit@5 %.0f %%, MRR %.2f.",
                100 * fixed.hitAt(1), 100 * fixed.hitAt(5), fixed.mrr(), 100 * structure.hitAt(1), 100 * structure.hitAt(5), structure.mrr());
        double diff = structure.mrr() - fixed.mrr();
        String verdict = Math.abs(diff) < 0.05
                ? "На 10 контрольных вопросах стратегии находят нужное примерно одинаково: "
                : diff > 0 ? "На 10 контрольных вопросах structure находит нужный чанк раньше: " : "На 10 контрольных вопросах fixed находит нужный чанк раньше: ";
        List<String> missed = new ArrayList<>();
        for (int i = 0; i < fixed.probes().size(); i++) {
            if (fixed.probes().get(i).rank() == 0 || structure.probes().get(i).rank() == 0) {
                missed.add(fixed.probes().get(i).q().id() + " (" + (fixed.probes().get(i).rank() == 0 ? "fixed" : "")
                        + (fixed.probes().get(i).rank() == 0 && structure.probes().get(i).rank() == 0 ? ", " : "")
                        + (structure.probes().get(i).rank() == 0 ? "structure" : "") + ")");
            }
        }
        return verdict + nums + (missed.isEmpty() ? " Релевантный чанк есть в top-" + PROBE_DEPTH + " для всех вопросов."
                : " Вне top-" + PROBE_DEPTH + " остались: " + String.join(", ", missed) + " — материал для реранкинга и переформулировки запроса (день 23).");
    }

    private static String top(Probe p) {
        if (p.top1() == null) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.2f ", p.top1().score()) + "`" + esc(Chunker.clip(p.top1().chunk().source().replaceAll(".*/", "")
                + Chunker.SEP + p.top1().chunk().section(), 60)) + "`";
    }

    private static void row(StringBuilder md, String name, Object a, Object b) {
        md.append("| ").append(name).append(" | ").append(a).append(" | ").append(b).append(" |\n");
    }

    private static String pct(int part, int total) {
        return total == 0 ? "—" : String.format("%d из %d (%.0f %%)", part, total, 100.0 * part / total);
    }

    static String esc(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String jsonEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String joinSources(List<String> sources) {
        return sources.stream().map(x -> "`" + x + "`").collect(Collectors.joining(", "));
    }
}
