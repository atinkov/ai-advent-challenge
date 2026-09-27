package ru.lemanapro.aiadventchallenge.week4;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Day 19: composition of MCP tools into an automatic pipeline: search -> summarize -> save_to_file.
 *
 * DocsPipelineMcpServer (stdio MCP server, official Java SDK) exposes three tools that are designed
 * to be chained:
 *   search_docs(query, limit)                — GETS data: full-text search over the project's docs and
 *                                              sources (*.md, *.java); returns ranked matches with snippets
 *                                              and a handle result_id;
 *   summarize(source_id | text, focus?)      — PROCESSES it: takes the search result BY HANDLE (the server
 *                                              looks it up — no copying through the model), makes a summary
 *                                              with the LLM (fallback: extractive), returns summary_id;
 *   save_to_file(summary_id | content, filename) — STORES it: writes a Markdown file into task19-output/
 *                                              (file name sanitized — no path traversal), returns path + hashes.
 *
 * Data hand-off is verifiable: every tool returns sha256 of what it produced (output_sha256) and of what
 * it actually consumed (input_sha256), so "step N+1 got exactly what step N produced" is a byte-level
 * check, not a guess: search.text_sha256 == summarize.input_sha256, summarize.output_sha256 ==
 * save.input_sha256, and the file re-read from disk contains the summary byte-for-byte.
 *
 * The chain runs automatically in two ways:
 *   1. Pipeline — a tiny declarative engine in this class: a list of steps {id, tool, args}, where an arg
 *      may reference an earlier step's structured result ("$search.result_id"); the runner resolves the
 *      references, calls the tools one after another over MCP and stops on the first failed step.
 *   2. Agent — McpToolAgent (LLM + MCP tools) gets one goal in natural language and composes the same
 *      chain itself; the trace is checked for the right order and for handles passed between calls.
 *
 * Writes task19-pipeline-report.md; pipeline outputs go to task19-output/.
 *
 * Env: TASK19_ROOT (search root, default "."), TASK19_SUMMARY_MODE (llm | extractive, default llm),
 *      TASK19_TOOL_MODE (auto | native | prompt).
 * Usage:
 *   mvn -q compile exec:java -Ptask19
 *   mvn -q compile exec:java -Ptask19 -Dexec.args="сжатие истории"      # custom query for pipeline 1
 */
public final class Task19 {

    private static final Path REPORT_FILE = Path.of("task19-pipeline-report.md");

    private Task19() {
    }

    // =====================================================================================
    //  MCP SERVER: search -> summarize -> save_to_file
    // =====================================================================================

    public static final class DocsPipelineMcpServer {

        private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
        private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}._-]{1,80}");
        private static final Set<String> EXCLUDED = Set.of("target", ".git", ".idea", ".omo", ".mvn", "task19-output", "node_modules");

        record Match(String path, int score, List<String> snippets) {
        }

        record SearchResult(String id, String query, List<Match> matches, String text, String sha) {
        }

        record Summary(String id, String sourceId, String query, List<String> sources, String text, String method,
                       String inputSha, String sha) {
        }

        private final Path root;
        private final Path outDir;
        private final String summaryMode;
        private final Map<String, SearchResult> searches = new ConcurrentHashMap<>();
        private final Map<String, Summary> summaries = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();

        private DocsPipelineMcpServer(Path root, String summaryMode) {
            this.root = root;
            this.outDir = root.resolve("task19-output");
            this.summaryMode = summaryMode;
        }

        public static void main(String[] args) throws Exception {
            Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
            String mode = args.length > 1 ? args[1] : "llm";
            System.err.println("[docs-pipeline-mcp-server] MCP-сервер запущен (корень " + root + "), ждёт JSON-RPC на stdin. "
                    + "Сам по себе ничего не выводит — его запускает Task19.main (mvn -q compile exec:java -Ptask19).");
            DocsPipelineMcpServer s = new DocsPipelineMcpServer(root, mode);
            McpJsonMapper json = McpJsonDefaults.getMapper();

            CountDownLatch stdinClosed = new CountDownLatch(1);
            InputStream in = new FilterInputStream(System.in) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n < 0) stdinClosed.countDown();
                    return n;
                }

                @Override
                public int read() throws IOException {
                    int n = super.read();
                    if (n < 0) stdinClosed.countDown();
                    return n;
                }
            };

            McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(json, in, System.out))
                    .serverInfo("docs-pipeline-mcp-server", "1.0.0")
                    .instructions("Инструменты для цепочки: search_docs (найти) -> summarize (обработать, по source_id) "
                            + "-> save_to_file (сохранить, по summary_id). Передавай между шагами идентификаторы, а не текст.")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .toolCall(McpSchema.Tool.builder()
                                    .name("search_docs")
                                    .title("Поиск по документации проекта")
                                    .description("Шаг 1 цепочки. Полнотекстовый поиск по документации и коду проекта (*.md, *.java). "
                                            + "Возвращает самые релевантные файлы с фрагментами и идентификатор result_id — "
                                            + "его нужно передать в summarize как source_id.")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{
                                               "query":{"type":"string","description":"Что искать: слова или фраза, например «сжатие истории»"},
                                               "limit":{"type":"integer","minimum":1,"maximum":10,"default":5,"description":"Сколько файлов вернуть"}
                                             },
                                             "required":["query"],
                                             "additionalProperties":false}""")
                                    .build(),
                            (ex, req) -> s.safe(() -> s.search(req.arguments())))
                    .toolCall(McpSchema.Tool.builder()
                                    .name("summarize")
                                    .title("Сделать выжимку")
                                    .description("Шаг 2 цепочки. Делает краткую выжимку. Источник: source_id (result_id из search_docs — "
                                            + "предпочтительно, текст берётся на сервере без искажений) или произвольный text. "
                                            + "Возвращает summary_id — его нужно передать в save_to_file.")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{
                                               "source_id":{"type":"string","description":"result_id, полученный от search_docs"},
                                               "text":{"type":"string","description":"Текст для выжимки, если нет source_id"},
                                               "focus":{"type":"string","description":"На чём сфокусировать выжимку (необязательно)"},
                                               "max_points":{"type":"integer","minimum":2,"maximum":12,"default":6,"description":"Максимум пунктов"}
                                             },
                                             "additionalProperties":false}""")
                                    .build(),
                            (ex, req) -> s.safe(() -> s.summarize(req.arguments())))
                    .toolCall(McpSchema.Tool.builder()
                                    .name("save_to_file")
                                    .title("Сохранить в файл")
                                    .description("Шаг 3 цепочки. Сохраняет результат в Markdown-файл в папке task19-output/ проекта. "
                                            + "Источник: summary_id (из summarize — предпочтительно) или content. "
                                            + "Возвращает путь, размер и sha256 записанного.")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{
                                               "summary_id":{"type":"string","description":"summary_id, полученный от summarize"},
                                               "content":{"type":"string","description":"Произвольный текст, если нет summary_id"},
                                               "filename":{"type":"string","description":"Имя файла без пути, например compression-summary.md"}
                                             },
                                             "required":["filename"],
                                             "additionalProperties":false}""")
                                    .build(),
                            (ex, req) -> s.safe(() -> s.saveToFile(req.arguments())))
                    .build();

            stdinClosed.await();
            server.closeGracefully();
            System.exit(0);
        }

        // ---------------------------------------------------------------- step 1: search

        private McpSchema.CallToolResult search(Map<String, Object> a) throws Exception {
            String query = str(a, "query");
            if (query == null) return error("query обязателен");
            int limit = (int) Math.max(1, Math.min(10, num(a, "limit", 5)));
            List<String> terms = stems(query);
            if (terms.isEmpty()) return error("в запросе нет слов для поиска");

            List<Match> matches = new ArrayList<>();
            try (Stream<Path> files = Files.walk(root, 8)) {
                for (Path f : files.filter(Files::isRegularFile).filter(this::searchable).toList()) {
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    } catch (Exception unreadable) {
                        continue;
                    }
                    int score = 0;
                    List<String> hits = new ArrayList<>();
                    for (String line : lines) {
                        int lineScore = 0;
                        List<String> words = WORD.matcher(line.toLowerCase(Locale.ROOT)).results().map(java.util.regex.MatchResult::group).toList();
                        for (String t : terms) {
                            for (String w : words) if (w.startsWith(t)) lineScore++;
                        }
                        // lines that contain ALL terms are worth more — rewards the phrase, not scattered words
                        if (lineScore > 0 && terms.stream().allMatch(t -> words.stream().anyMatch(w -> w.startsWith(t)))) {
                            lineScore += 3 * terms.size();
                        }
                        if (lineScore > 0) {
                            score += lineScore;
                            if (hits.size() < 3) hits.add(clip(line.strip(), 220));
                        }
                    }
                    if (f.getFileName().toString().endsWith(".md")) score = score * 3 / 2; // docs before code
                    if (score > 0) matches.add(new Match(root.relativize(f).toString().replace('\\', '/'), score, hits));
                }
            }
            matches.sort((x, y) -> y.score() - x.score());
            List<Match> top = matches.stream().limit(limit).toList();

            StringBuilder text = new StringBuilder();
            for (Match m : top) {
                text.append("### ").append(m.path()).append(" (релевантность ").append(m.score()).append(")\n");
                m.snippets().forEach(sn -> text.append("- ").append(sn).append('\n'));
                text.append('\n');
            }
            String id = "s" + seq.incrementAndGet();
            SearchResult r = new SearchResult(id, query, top, text.toString(), sha256(text.toString()));
            searches.put(id, r);

            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("result_id", id);
            sc.put("query", query);
            sc.put("files_scanned", "*.md, *.java под " + root.getFileName());
            sc.put("total_matches", matches.size());
            sc.put("matches", top.stream().map(m -> Map.of("path", m.path(), "score", m.score(), "snippets", m.snippets())).toList());
            sc.put("text_sha256", r.sha());
            String head = "result_id: " + id + "\nЗапрос: «" + query + "», найдено файлов: " + matches.size()
                    + ", показано: " + top.size() + "\ntext_sha256: " + r.sha() + "\n\n";
            return ok(head + (top.isEmpty() ? "Ничего не найдено." : text.toString()), sc);
        }

        private boolean searchable(Path f) {
            Path rel = root.relativize(f);
            for (Path part : rel) {
                if (EXCLUDED.contains(part.toString())) return false;
            }
            String n = f.getFileName().toString();
            return (n.endsWith(".md") || n.endsWith(".java")) && !n.equals("task19-pipeline-report.md");
        }

        /** Crude stemming that works for Russian and English: lowercase, cut to 5 letters (памяти/память -> памят). */
        static List<String> stems(String q) {
            return WORD.matcher(q.toLowerCase(Locale.ROOT)).results().map(java.util.regex.MatchResult::group)
                    .filter(w -> w.length() >= 3)
                    .map(w -> w.length() > 6 ? w.substring(0, 5) : w)
                    .distinct().toList();
        }

        // ---------------------------------------------------------------- step 2: summarize

        private McpSchema.CallToolResult summarize(Map<String, Object> a) throws Exception {
            String sourceId = str(a, "source_id");
            String input;
            String query = null;
            List<String> sources = List.of();
            if (sourceId != null) {
                SearchResult r = searches.get(sourceId);
                if (r == null) return error("неизвестный source_id " + sourceId + " — сначала вызовите search_docs");
                if (r.matches().isEmpty()) return error("результат " + sourceId + " пуст — нечего суммировать");
                input = r.text();
                query = r.query();
                sources = r.matches().stream().map(Match::path).toList();
            } else {
                input = str(a, "text");
                if (input == null) return error("нужен source_id (из search_docs) или text");
            }
            String focus = str(a, "focus");
            int maxPoints = (int) Math.max(2, Math.min(12, num(a, "max_points", 6)));

            String summary = null;
            String method = "extractive";
            if ("llm".equalsIgnoreCase(summaryMode)) {
                try {
                    summary = llmSummary(input, focus != null ? focus : query, maxPoints);
                    method = "llm";
                } catch (Exception e) {
                    System.err.println("[docs-pipeline-mcp-server] LLM недоступна для summarize (" + e.getMessage() + ") — извлекающая выжимка");
                }
            }
            if (summary == null || summary.isBlank()) {
                summary = extractiveSummary(input, focus != null ? focus : query, maxPoints);
                method = "extractive";
            }
            String id = "m" + seq.incrementAndGet();
            Summary sm = new Summary(id, sourceId, query, sources, summary, method, sha256(input), sha256(summary));
            summaries.put(id, sm);

            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("summary_id", id);
            sc.put("source_id", sourceId == null ? "" : sourceId);
            sc.put("method", method);
            sc.put("input_sha256", sm.inputSha());
            sc.put("output_sha256", sm.sha());
            sc.put("summary", summary);
            sc.put("sources", sources);
            return ok("summary_id: " + id + "\nsource_id: " + (sourceId == null ? "—" : sourceId) + "\nметод: " + method
                    + "\ninput_sha256: " + sm.inputSha() + "\noutput_sha256: " + sm.sha() + "\n\n" + summary, sc);
        }

        private String llmSummary(String input, String focus, int maxPoints) throws Exception {
            String key = LlmClient.env("LLM_API_KEY", null);
            if (key == null) throw new IllegalStateException("нет LLM_API_KEY");
            LlmClient.Config cfg = new LlmClient.Config(key,
                    LlmClient.env("HINDSIGHT_API_LLM_BASE_URL", LlmClient.DEFAULT_BASE_URL),
                    LlmClient.env("HINDSIGHT_API_LLM_MODEL", LlmClient.DEFAULT_MODEL));
            HttpClient http = LlmClient.newHttpClient();
            String prompt = "Сделай краткую выжимку найденных фрагментов документации проекта"
                    + (focus != null ? " по теме «" + focus + "»" : "") + ": не больше " + maxPoints
                    + " пунктов-маркеров «- », по-русски, только факты из фрагментов, в каждом пункте укажи файл-источник в скобках. "
                    + "Без вступления и заключения.\n\nФрагменты:\n" + input;
            JsonNode resp = LlmClient.send(http, cfg, List.of(LlmClient.message("user", prompt)), null, null, 0.2);
            return LlmClient.content(resp).replaceAll("(?s)<think>.*?</think>", "").strip();
        }

        /** No-LLM fallback: the highest-scoring snippet lines, each tagged with its file. */
        static String extractiveSummary(String input, String focus, int maxPoints) {
            List<String> terms = focus == null ? List.of() : stems(focus);
            List<String[]> candidates = new ArrayList<>();
            String file = "?";
            for (String line : input.split("\n")) {
                if (line.startsWith("### ")) {
                    file = line.substring(4).replaceAll(" \\(релевантность \\d+\\)$", "");
                } else if (line.startsWith("- ")) {
                    candidates.add(new String[]{line.substring(2).replaceAll("[*`#|>]+", "").strip(), file});
                }
            }
            if (candidates.isEmpty()) {
                return Arrays.stream(input.split("(?<=[.!?])\\s+")).limit(maxPoints).map(s -> "- " + s.strip())
                        .collect(Collectors.joining("\n"));
            }
            return candidates.stream()
                    .sorted((x, y) -> Long.compare(hits(y[0], terms), hits(x[0], terms)))
                    .filter(c -> c[0].length() > 15)
                    .limit(maxPoints)
                    .map(c -> "- " + clip(c[0], 200) + " (" + c[1] + ")")
                    .collect(Collectors.joining("\n"));
        }

        private static long hits(String s, List<String> terms) {
            String l = s.toLowerCase(Locale.ROOT);
            return terms.stream().filter(l::contains).count();
        }

        // ---------------------------------------------------------------- step 3: save

        private McpSchema.CallToolResult saveToFile(Map<String, Object> a) throws Exception {
            String filename = str(a, "filename");
            if (filename == null || !SAFE_NAME.matcher(filename).matches() || filename.startsWith(".") || filename.contains("..")) {
                return error("недопустимое имя файла «" + filename + "»: только имя без пути (буквы, цифры, . _ -)");
            }
            if (!filename.contains(".")) filename += ".md";
            String summaryId = str(a, "summary_id");
            String body;
            StringBuilder header = new StringBuilder();
            if (summaryId != null) {
                Summary sm = summaries.get(summaryId);
                if (sm == null) return error("неизвестный summary_id " + summaryId + " — сначала вызовите summarize");
                body = sm.text();
                header.append("# Выжимка: ").append(sm.query() != null ? sm.query() : "текст").append("\n\n")
                        .append("_Пайплайн search_docs → summarize → save_to_file, ")
                        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                        .append(" · summary_id ").append(sm.id()).append(" · source_id ").append(sm.sourceId())
                        .append(" · метод ").append(sm.method()).append("_\n\n");
                if (!sm.sources().isEmpty()) {
                    header.append("Источники: ").append(sm.sources().stream().map(p -> "`" + p + "`").collect(Collectors.joining(", ")))
                            .append("\n\n");
                }
            } else {
                body = str(a, "content");
                if (body == null) return error("нужен summary_id (из summarize) или content");
            }
            Files.createDirectories(outDir);
            Path target = outDir.resolve(filename).normalize();
            if (!target.startsWith(outDir)) return error("путь вне task19-output запрещён");
            String fileText = header + body + "\n";
            Files.writeString(target, fileText, StandardCharsets.UTF_8);

            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("path", root.relativize(target).toString().replace('\\', '/'));
            sc.put("bytes", fileText.getBytes(StandardCharsets.UTF_8).length);
            sc.put("summary_id", summaryId == null ? "" : summaryId);
            sc.put("input_sha256", sha256(body));
            sc.put("file_sha256", sha256(fileText));
            return ok("Сохранено: " + sc.get("path") + " (" + sc.get("bytes") + " байт)\nsummary_id: "
                    + (summaryId == null ? "—" : summaryId) + "\ninput_sha256: " + sc.get("input_sha256")
                    + "\nfile_sha256: " + sc.get("file_sha256"), sc);
        }

        // ---------------------------------------------------------------- helpers

        interface Handler {
            McpSchema.CallToolResult run() throws Exception;
        }

        McpSchema.CallToolResult safe(Handler h) {
            try {
                return h.run();
            } catch (Exception e) {
                return error(String.valueOf(e.getMessage()));
            }
        }

        private static McpSchema.CallToolResult ok(String text, Object structured) {
            return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(false).build();
        }

        private static McpSchema.CallToolResult error(String message) {
            return McpSchema.CallToolResult.builder().addTextContent("Ошибка: " + message).isError(true).build();
        }

        private static String str(Map<String, Object> a, String k) {
            Object v = a == null ? null : a.get(k);
            return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
        }

        private static double num(Map<String, Object> a, String k, double def) {
            Object v = a == null ? null : a.get(k);
            if (v instanceof Number n) return n.doubleValue();
            try {
                return v == null ? def : Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static String clip(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max) + "…";
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // =====================================================================================
    //  PIPELINE ENGINE: declarative steps, references to earlier results, automatic run
    // =====================================================================================

    /** One step: an id to reference it by, a tool, and args where "$<stepId>.<field>" is resolved at run time. */
    record Step(String id, String tool, Map<String, Object> args) {
    }

    record StepRun(Step step, Map<String, Object> resolvedArgs, boolean ok, Map<String, Object> result, String text, long millis) {
    }

    record PipelineRun(String name, List<StepRun> steps, List<String> checks, boolean passed) {
    }

    private static final Pattern REF = Pattern.compile("^\\$([\\w-]+)\\.([\\w-]+)$");

    @SuppressWarnings("unchecked")
    static PipelineRun runPipeline(McpSyncClient mcp, Path root, String name, List<Step> steps) {
        System.out.println("\n▶ Пайплайн «" + name + "»: " + steps.stream().map(Step::tool).collect(Collectors.joining(" → ")));
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        List<StepRun> runs = new ArrayList<>();
        for (Step st : steps) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : st.args().entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s) {
                    Matcher m = REF.matcher(s);
                    if (m.matches()) {
                        Map<String, Object> prev = results.get(m.group(1));
                        v = prev == null ? null : prev.get(m.group(2));
                        if (v == null) {
                            System.out.println("  ✗ " + st.id() + ": ссылка " + s + " не разрешилась — пайплайн остановлен");
                            runs.add(new StepRun(st, resolved, false, Map.of(), "unresolved " + s, 0));
                            return new PipelineRun(name, runs, List.of("[FAIL] ссылка " + s + " не разрешилась"), false);
                        }
                    }
                }
                resolved.put(e.getKey(), v);
            }
            long t0 = System.nanoTime();
            McpSchema.CallToolResult r = mcp.callTool(new McpSchema.CallToolRequest(st.tool(), resolved));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = !Boolean.TRUE.equals(r.isError());
            Map<String, Object> sc = r.structuredContent() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String text = McpToolAgent.textOf(r);
            runs.add(new StepRun(st, resolved, ok, sc, text, ms));
            System.out.println("  " + (ok ? "✓" : "✗") + " " + st.id() + " = " + st.tool() + argsForLog(resolved) + "  (" + ms + " мс)");
            if (!ok) {
                System.out.println("    " + text);
                return new PipelineRun(name, runs, List.of("[FAIL] шаг " + st.id() + " вернул ошибку: " + text), false);
            }
            results.put(st.id(), sc);
        }
        List<String> checks = verifyHandoffs(runs, root);
        checks.forEach(c -> System.out.println("    " + c));
        return new PipelineRun(name, runs, checks, checks.stream().allMatch(c -> c.startsWith("[OK]")));
    }

    /** Byte-level hand-off checks between search -> summarize -> save, plus the file on disk. */
    private static List<String> verifyHandoffs(List<StepRun> runs, Path root) {
        List<String> checks = new ArrayList<>();
        Map<String, Object> search = byTool(runs, "search_docs");
        Map<String, Object> sum = byTool(runs, "summarize");
        Map<String, Object> save = byTool(runs, "save_to_file");
        checks.add(check(runs.size() == 3 && runs.stream().allMatch(StepRun::ok), "все 3 шага выполнены автоматически, без ошибок"));
        checks.add(check(!((List<?>) search.getOrDefault("matches", List.of())).isEmpty(),
                "search_docs нашёл данные: " + ((List<?>) search.getOrDefault("matches", List.of())).size() + " файл(ов)"));
        checks.add(check(sameValue(sum.get("source_id"), search.get("result_id")),
                "summarize получил именно результат поиска: source_id " + sum.get("source_id") + " = result_id " + search.get("result_id")));
        checks.add(check(sameValue(sum.get("input_sha256"), search.get("text_sha256")),
                "summarize обработал ровно найденный текст: input_sha256 = text_sha256 поиска (" + shortSha(search.get("text_sha256")) + ")"));
        checks.add(check(sameValue(save.get("summary_id"), sum.get("summary_id")),
                "save_to_file получил именно эту выжимку: summary_id " + save.get("summary_id")));
        checks.add(check(sameValue(save.get("input_sha256"), sum.get("output_sha256")),
                "save_to_file записал ровно выжимку: input_sha256 = output_sha256 summarize (" + shortSha(sum.get("output_sha256")) + ")"));
        String fileCheck;
        try {
            Path p = root.resolve(String.valueOf(save.get("path")));
            String onDisk = Files.readString(p, StandardCharsets.UTF_8);
            boolean same = sha256(onDisk).equals(save.get("file_sha256")) && onDisk.contains(String.valueOf(sum.get("summary")));
            fileCheck = check(same, "файл " + save.get("path") + " на диске совпадает (file_sha256) и содержит выжимку целиком");
        } catch (Exception e) {
            fileCheck = check(false, "файл не прочитан: " + e.getMessage());
        }
        checks.add(fileCheck);
        return checks;
    }

    private static Map<String, Object> byTool(List<StepRun> runs, String tool) {
        return runs.stream().filter(r -> r.step().tool().equals(tool)).map(StepRun::result).findFirst().orElse(Map.of());
    }

    private static boolean sameValue(Object a, Object b) {
        return a != null && String.valueOf(a).equals(String.valueOf(b));
    }

    private static String check(boolean ok, String what) {
        return (ok ? "[OK]   " : "[FAIL] ") + what;
    }

    private static String shortSha(Object s) {
        String v = String.valueOf(s);
        return v.length() > 12 ? v.substring(0, 12) + "…" : v;
    }

    private static String argsForLog(Map<String, Object> args) {
        return args.entrySet().stream().map(e -> e.getKey() + "=" + clipArg(e.getValue()))
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static String clipArg(Object v) {
        String s = String.valueOf(v);
        return s.length() > 40 ? "\"" + s.substring(0, 40) + "…\"" : (v instanceof String ? "\"" + s + "\"" : s);
    }

    // =====================================================================================
    //  DEMO
    // =====================================================================================

    private static final String AGENT_PROMPT = """
            Ты агент-исследователь документации проекта. У тебя есть MCP-инструменты, которые составляются в цепочку:
            search_docs (найти) -> summarize (обработать) -> save_to_file (сохранить).
            Правила:
            - Выполни всю цепочку сам, не спрашивая подтверждений.
            - Передавай между шагами ИДЕНТИФИКАТОРЫ: result_id из search_docs -> summarize(source_id=...), summary_id из summarize -> save_to_file(summary_id=...). Не копируй текст вручную.
            - В конце кратко по-русски: что найдено, путь сохранённого файла.""";

    record AgentRun(String goal, McpToolAgent.AgentAnswer answer, List<String> checks, boolean passed) {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        Path root = Path.of(LlmClient.env("TASK19_ROOT", ".")).toAbsolutePath().normalize();
        String summaryMode = LlmClient.env("TASK19_SUMMARY_MODE", "llm");
        String toolMode = LlmClient.env("TASK19_TOOL_MODE", "auto");
        String query1 = args.length > 0 ? String.join(" ", args) : "инварианты";

        ServerParameters params = McpLaunch.javaServer(DocsPipelineMcpServer.class, root.toString(), summaryMode);
        System.out.println("=== День 19. Композиция MCP-инструментов: search → summarize → save_to_file ===");
        System.out.println("Модель: " + cfg.model() + ", выжимка: " + summaryMode + ", корень поиска: " + root);

        McpSyncClient mcp = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .clientInfo(new McpSchema.Implementation("ai-advent-task19", "1.0.0"))
                .requestTimeout(Duration.ofMinutes(3))
                .build();
        McpSchema.InitializeResult init = mcp.initialize();
        System.out.println("[OK] MCP: " + init.serverInfo().name() + " " + init.serverInfo().version() + ", протокол " + init.protocolVersion());

        try (McpToolAgent agent = new McpToolAgent(cfg, mcp, toolMode, AGENT_PROMPT)) {
            System.out.println("\nИнструменты: " + agent.tools().stream().map(McpSchema.Tool::name).collect(Collectors.joining(", ")));

            // ---- 1-2. declarative pipelines, run automatically by the engine
            List<PipelineRun> pipelines = new ArrayList<>();
            pipelines.add(runPipeline(mcp, root, "Инварианты и ограничения", List.of(
                    new Step("search", "search_docs", Map.of("query", query1, "limit", 5)),
                    new Step("sum", "summarize", Map.of("source_id", "$search.result_id", "max_points", 6)),
                    new Step("save", "save_to_file", Map.of("summary_id", "$sum.summary_id", "filename", "pipeline-1-summary.md")))));
            pipelines.add(runPipeline(mcp, root, "MCP в проекте", List.of(
                    new Step("find", "search_docs", Map.of("query", "MCP сервер инструменты", "limit", 4)),
                    new Step("digest", "summarize", Map.of("source_id", "$find.result_id", "focus", "MCP", "max_points", 5)),
                    new Step("store", "save_to_file", Map.of("summary_id", "$digest.summary_id", "filename", "pipeline-2-mcp.md")))));

            // ---- negative check: a broken hand-off must stop the chain, not produce a file
            System.out.println("\n▶ Контроль: цепочка с неверной передачей данных должна остановиться");
            PipelineRun broken = runPipeline(mcp, root, "Сломанная передача", List.of(
                    new Step("search", "search_docs", Map.of("query", "память агента")),
                    new Step("sum", "summarize", Map.of("source_id", "s999")),
                    new Step("save", "save_to_file", Map.of("summary_id", "$sum.summary_id", "filename", "must-not-exist.md"))));
            boolean brokenStopped = !broken.passed() && broken.steps().size() == 2
                    && !Files.exists(root.resolve("task19-output/must-not-exist.md"));
            System.out.println("    " + check(brokenStopped, "ошибка на шаге 2 остановила пайплайн, шаг 3 не выполнялся, файл не создан"));

            // ---- 3. agent composes the chain itself from one goal
            System.out.println("\n▶ Агент: LLM сама составляет цепочку из одной цели");
            String goal = "Найди в документации проекта всё про сжатие истории диалога (compression), сделай краткую выжимку "
                    + "и сохрани её в файл agent-compression-summary.md.";
            System.out.println("Цель: " + goal);
            AgentRun agentRun;
            try {
                McpToolAgent.AgentAnswer a = agent.ask(goal);
                System.out.println("Ответ агента: " + a.text().strip());
                agentRun = verifyAgent(goal, a, root);
            } catch (Exception e) {
                System.out.println("Агент не выполнил цель: " + e);
                agentRun = new AgentRun(goal, new McpToolAgent.AgentAnswer("(ошибка: " + e + ")", List.of(), 0, "-"),
                        List.of("[FAIL] LLM недоступна: " + e), false);
            }
            agentRun.checks().forEach(c -> System.out.println("    " + c));

            long ok = pipelines.stream().filter(PipelineRun::passed).count();
            System.out.println("\nИТОГ: пайплайны " + ok + "/" + pipelines.size() + ", контроль сломанной цепочки: "
                    + (brokenStopped ? "OK" : "FAIL") + ", агент: " + (agentRun.passed() ? "OK" : "FAIL"));
            writeReport(cfg, init, summaryMode, pipelines, broken, brokenStopped, agentRun);
            System.out.println("Отчёт: " + REPORT_FILE.toAbsolutePath() + ", результаты: " + root.resolve("task19-output"));
        }
    }

    /** The agent's trace must show search -> summarize -> save in order, with each handle passed on. */
    private static AgentRun verifyAgent(String goal, McpToolAgent.AgentAnswer a, Path root) {
        List<String> checks = new ArrayList<>();
        List<McpToolAgent.ToolCallTrace> calls = a.calls().stream().filter(c -> !c.isError()).toList();
        List<String> order = calls.stream().map(McpToolAgent.ToolCallTrace::tool).toList();
        int iS = order.indexOf("search_docs");
        int iM = order.lastIndexOf("summarize");
        int iF = order.lastIndexOf("save_to_file");
        checks.add(check(iS >= 0 && iM > iS && iF > iM, "агент выполнил цепочку по порядку: " + order));
        String resultId = iS >= 0 ? find(calls.get(iS).resultPreview(), "result_id: (\\S+)") : null;
        String sourceArg = iM >= 0 ? find(calls.get(iM).argumentsJson(), "\"source_id\"\\s*:\\s*\"([^\"]+)\"") : null;
        checks.add(check(resultId != null && resultId.equals(sourceArg),
                "summarize получил result_id поиска: source_id=" + sourceArg + ", result_id=" + resultId));
        String summaryId = iM >= 0 ? find(calls.get(iM).resultPreview(), "summary_id: (\\S+)") : null;
        String summaryArg = iF >= 0 ? find(calls.get(iF).argumentsJson(), "\"summary_id\"\\s*:\\s*\"([^\"]+)\"") : null;
        checks.add(check(summaryId != null && summaryId.equals(summaryArg),
                "save_to_file получил summary_id выжимки: " + summaryArg));
        String outSha = iM >= 0 ? find(calls.get(iM).resultPreview(), "output_sha256: (\\S+)") : null;
        String inSha = iF >= 0 ? find(calls.get(iF).resultPreview(), "input_sha256: (\\S+)") : null;
        checks.add(check(outSha != null && outSha.equals(inSha), "в файл записана ровно выжимка (sha256 совпадают)"));
        String path = iF >= 0 ? find(calls.get(iF).resultPreview(), "Сохранено: (\\S+)") : null;
        checks.add(check(path != null && Files.isRegularFile(root.resolve(path)), "файл существует: " + path));
        return new AgentRun(goal, a, checks, checks.stream().allMatch(c -> c.startsWith("[OK]")));
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : null;
    }

    private static void writeReport(LlmClient.Config cfg, McpSchema.InitializeResult init, String summaryMode,
                                    List<PipelineRun> pipelines, PipelineRun broken, boolean brokenStopped,
                                    AgentRun agentRun) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 19. Композиция MCP-инструментов\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask19`\n\n");
        md.append("MCP-сервер: ").append(init.serverInfo().name()).append(" ").append(init.serverInfo().version())
                .append(" (`Task19.DocsPipelineMcpServer`), модель: ").append(cfg.model()).append(", выжимка: ").append(summaryMode).append("\n\n");
        md.append("```\nsearch_docs ──result_id──▶ summarize ──summary_id──▶ save_to_file ──▶ task19-output/*.md\n")
                .append("   text_sha256 ═════ input_sha256   output_sha256 ═════ input_sha256   file_sha256 ═ файл на диске\n```\n\n");
        int n = 1;
        for (PipelineRun p : pipelines) {
            md.append("## ").append(n++).append(". Пайплайн «").append(p.name()).append("»\n\n");
            md.append("| Шаг | Инструмент | Аргументы (после подстановки) | Результат | мс |\n|---|---|---|---|---|\n");
            for (StepRun r : p.steps()) {
                md.append("| ").append(r.step().id()).append(" | `").append(r.step().tool()).append("` | `")
                        .append(argsForLog(r.resolvedArgs()).replace("|", "\\|")).append("` | ")
                        .append(r.ok() ? resultCell(r) : "ошибка").append(" | ").append(r.millis()).append(" |\n");
            }
            md.append('\n');
            p.checks().forEach(c -> md.append("- ").append(c.trim()).append('\n'));
            Object summary = byTool(p.steps(), "summarize").get("summary");
            if (summary != null) {
                md.append("\n<details><summary>Выжимка</summary>\n\n").append(summary).append("\n\n</details>\n");
            }
            md.append('\n');
        }
        md.append("## ").append(n++).append(". Контроль: сломанная передача данных\n\n")
                .append("Шаг 2 получил несуществующий `source_id` (s999) → ")
                .append(broken.steps().getLast().text().replace("\n", " ")).append("\n\n- ")
                .append(check(brokenStopped, "пайплайн остановился на шаге 2, файл must-not-exist.md не создан").trim()).append("\n\n");
        md.append("## ").append(n).append(". Агент сам составляет цепочку\n\n**Цель:** ").append(agentRun.goal()).append("\n\n");
        for (McpToolAgent.ToolCallTrace c : agentRun.answer().calls()) {
            md.append("- `").append(c.tool()).append(" ").append(c.argumentsJson()).append("` → ")
                    .append(c.isError() ? "ошибка" : "ok").append('\n');
        }
        md.append("\n**Ответ агента:**\n\n").append(agentRun.answer().text().strip().lines().map(l -> "> " + l)
                .collect(Collectors.joining("\n"))).append("\n\n");
        agentRun.checks().forEach(c -> md.append("- ").append(c.trim()).append('\n'));
        long ok = pipelines.stream().filter(PipelineRun::passed).count();
        md.append("\n**Итог:** пайплайны ").append(ok).append("/").append(pipelines.size())
                .append(", контроль сломанной цепочки — ").append(brokenStopped ? "OK" : "FAIL")
                .append(", агент — ").append(agentRun.passed() ? "OK" : "FAIL").append(".\n");
        Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
    }

    private static String resultCell(StepRun r) {
        Map<String, Object> s = r.result();
        return switch (r.step().tool()) {
            case "search_docs" -> "result_id=" + s.get("result_id") + ", файлов " + ((List<?>) s.get("matches")).size();
            case "summarize" -> "summary_id=" + s.get("summary_id") + ", " + s.get("method");
            case "save_to_file" -> "`" + s.get("path") + "`, " + s.get("bytes") + " байт";
            default -> "ok";
        };
    }
}
