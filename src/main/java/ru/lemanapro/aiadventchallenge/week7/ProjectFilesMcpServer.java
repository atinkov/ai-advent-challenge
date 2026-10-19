package ru.lemanapro.aiadventchallenge.week7;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Day 34: MCP-сервер «файлы проекта» (stdio; stdout занят JSON-RPC) поверх {@link ProjectFiles}.
 *
 *   java ... ProjectFilesMcpServer <корень проекта> [--apply]
 *
 * Чтение и анализ: list_files, read_file, search, check_links, git_log.
 * Изменения:       write_file, edit_file — в dry-run копятся в памяти сервера, pending_changes отдаёт unified diff;
 *                  с --apply каждое изменение сразу записывается на диск.
 * Каждый вызов stateless по аргументам, но оверлей изменений живёт, пока жив процесс сервера (одна сессия ассистента).
 */
public final class ProjectFilesMcpServer {

    static final String NAME = "project-files-mcp-server";
    /** the text of one search result the model sees; per-file counts are always complete */
    static final int MAX_SEARCH_CHARS = 12_000;

    private final ProjectFiles files;

    ProjectFilesMcpServer(ProjectFiles files) {
        this.files = files;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: ProjectFilesMcpServer <root> [--apply]");
            System.exit(2);
        }
        boolean apply = List.of(args).contains("--apply");
        ProjectFilesMcpServer s = new ProjectFilesMcpServer(new ProjectFiles(Path.of(args[0]), apply));
        McpJsonMapper json = McpJsonDefaults.getMapper();
        CountDownLatch stdinClosed = new CountDownLatch(1);
        InputStream in = new FilterInputStream(System.in) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n < 0) stdinClosed.countDown();
                return n;
            }
        };
        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(json, in, System.out))
                .serverInfo(NAME, "1.0.0")
                .instructions("Файлы проекта " + s.files.root().getFileName() + "; режим " + (apply ? "apply (запись на диск)" : "dry-run (изменения только в diff)"))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(tool(json, "list_files", """
                                Список файлов проекта (пути от корня, размер, число строк). Служебные папки (.git, .idea, target) скрыты. \
                                glob: «*.md» — по имени в любой папке, «02. Первый сценарий на Gatling» — всё в папке, «**/*Задани*» — маска. \
                                Начинайте работу с этого инструмента, чтобы узнать структуру проекта.""",
                        """
                                {"type":"object","properties":{"glob":{"type":"string","description":"маска или папка; пусто — все файлы"}},"additionalProperties":false}""", true),
                        (ex, req) -> s.safe(() -> s.listFiles(req.arguments())))
                .toolCall(tool(json, "read_file", """
                                Прочитать текстовый файл с номерами строк («  12| текст»). Номера — только для ориентира, в edit_file их не копировать. \
                                Длинные файлы отдаются частями: start_line / end_line (за раз до 250 строк).""",
                        """
                                {"type":"object","properties":{"path":{"type":"string","description":"путь от корня проекта"},
                                 "start_line":{"type":"integer","minimum":1},"end_line":{"type":"integer","minimum":1}},
                                 "required":["path"],"additionalProperties":false}""", true),
                        (ex, req) -> s.safe(() -> s.readFile(req.arguments())))
                .toolCall(tool(json, "search", """
                                Поиск по содержимому всех текстовых файлов (или по маске glob): строка (по умолчанию) или регулярное выражение, \
                                без учёта регистра по умолчанию. Возвращает «путь:строка: текст» и число совпадений по файлам. \
                                files_without=true — вместо совпадений список файлов, где запроса НЕТ (для проверки правил «в каждом файле должно быть …»).""",
                        """
                                {"type":"object","properties":{"query":{"type":"string"},"regex":{"type":"boolean"},
                                 "glob":{"type":"string"},"case_sensitive":{"type":"boolean"},"files_without":{"type":"boolean"},
                                 "max_results":{"type":"integer","minimum":1,"maximum":300}},
                                 "required":["query"],"additionalProperties":false}""", true),
                        (ex, req) -> s.safe(() -> s.search(req.arguments())))
                .toolCall(tool(json, "check_links", """
                                Анализ Markdown-ссылок: битые относительные ссылки (файл не существует) и «сироты» — .md-файлы, на которые \
                                не ссылается ни один другой .md. Учитывает несохранённые изменения — после правок вызовите ещё раз.""",
                        """
                                {"type":"object","properties":{"glob":{"type":"string","description":"какие файлы проверять; пусто — все .md"}},"additionalProperties":false}""", true),
                        (ex, req) -> s.safe(() -> s.checkLinks(req.arguments())))
                .toolCall(tool(json, "git_log", """
                                История git репозитория проекта (только чтение): короткий хеш, дата, автор, сообщение коммита; \
                                with_files=true — ещё и изменённые файлы; path — история одного файла/папки.""",
                        """
                                {"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":500},"path":{"type":"string"},
                                 "with_files":{"type":"boolean"}},"additionalProperties":false}""", true),
                        (ex, req) -> s.safe(() -> s.gitLog(req.arguments())))
                .toolCall(tool(json, "write_file", """
                                Создать новый файл или полностью перезаписать существующий (только текстовые форматы). \
                                Для точечной правки существующего файла используйте edit_file. Возвращает diff файла.""",
                        """
                                {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string","description":"полное новое содержимое"}},
                                 "required":["path","content"],"additionalProperties":false}""", false),
                        (ex, req) -> s.safe(() -> s.writeFile(req.arguments())))
                .toolCall(tool(json, "edit_file", """
                                Точечная правка: заменить фрагмент old_text на new_text. old_text копируйте из read_file дословно, \
                                без номеров строк; он должен встречаться в файле ровно один раз (или replace_all=true). Возвращает diff файла.""",
                        """
                                {"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"},
                                 "replace_all":{"type":"boolean"}},"required":["path","old_text","new_text"],"additionalProperties":false}""", false),
                        (ex, req) -> s.safe(() -> s.editFile(req.arguments())))
                .toolCall(tool(json, "pending_changes", """
                                Все изменения этой сессии одним unified diff (формат git apply) + сводка «создан / изменён, +строк −строк». \
                                Вызовите перед итоговым ответом, чтобы проверить, что изменено только нужное.""",
                        """
                                {"type":"object","properties":{},"additionalProperties":false}""", true),
                        (ex, req) -> s.safe(s::pendingChanges))
                .build();
        stdinClosed.await();
        server.closeGracefully();
        System.exit(0);
    }

    private static McpSchema.Tool tool(McpJsonMapper json, String name, String description, String schema, boolean readOnly) {
        return McpSchema.Tool.builder().name(name).description(description).inputSchema(json, schema)
                .annotations(new McpSchema.ToolAnnotations(null, readOnly, readOnly ? null : false, readOnly ? true : null, false, null))
                .build();
    }

    // ================================================================ tools

    McpSchema.CallToolResult listFiles(Map<String, Object> a) throws Exception {
        List<ProjectFiles.FileInfo> list = files.list(str(a, "glob"));
        StringBuilder sb = new StringBuilder(list.size() + " файлов" + (str(a, "glob") == null ? "" : " по маске «" + str(a, "glob") + "»") + ":\n");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectFiles.FileInfo f : list) {
            sb.append(f.path()).append(f.text() ? "  (" + f.lines() + " строк)" : "  (" + f.bytes() + " байт, не текст)").append('\n');
            out.add(Map.of("path", f.path(), "lines", f.lines(), "bytes", f.bytes(), "text", f.text()));
        }
        return ok(ProjectFiles.clip(sb.toString(), 30_000), Map.of("files", out));
    }

    McpSchema.CallToolResult readFile(Map<String, Object> a) throws Exception {
        String path = req(a, "path");
        return ok(files.readNumbered(path, integer(a, "start_line"), integer(a, "end_line")), Map.of("path", files.rel(path)));
    }

    McpSchema.CallToolResult search(Map<String, Object> a) throws Exception {
        String q = req(a, "query");
        boolean regex = bool(a, "regex");
        String glob = str(a, "glob");
        boolean cs = bool(a, "case_sensitive");
        if (bool(a, "files_without")) {
            List<String> without = files.filesWithout(q, regex, glob, cs);
            return ok("Файлы БЕЗ «" + q + "» (" + without.size() + "):\n" + String.join("\n", without),
                    Map.of("query", q, "files_without", without));
        }
        Integer max = integer(a, "max_results");
        ProjectFiles.SearchResult r = files.search(q, regex, glob, cs, max == null ? 80 : max);
        StringBuilder sb = new StringBuilder("«" + q + "»: " + r.total() + " совпадений в " + r.perFile().size() + " файлах\n");
        r.perFile().forEach((p, n) -> sb.append("  ").append(n).append(" × ").append(p).append('\n'));
        sb.append('\n');
        int shown = 0;
        for (ProjectFiles.Match m : r.matches()) {
            if (sb.length() > MAX_SEARCH_CHARS) break;
            sb.append(m.path()).append(':').append(m.line()).append(": ").append(m.text()).append('\n');
            shown++;
        }
        if (shown < r.total()) {
            sb.append("… показаны ").append(shown).append(" из ").append(r.total())
                    .append(" строк (число совпадений по файлам выше — полное); уточните query / glob, чтобы увидеть остальные\n");
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (ProjectFiles.Match m : r.matches()) matches.add(Map.of("path", m.path(), "line", m.line(), "text", m.text()));
        return ok(sb.toString(), Map.of("query", q, "total", r.total(), "per_file", new LinkedHashMap<>(r.perFile()), "matches", matches));
    }

    McpSchema.CallToolResult checkLinks(Map<String, Object> a) throws Exception {
        ProjectFiles.LinkReport r = files.checkLinks(str(a, "glob"));
        StringBuilder sb = new StringBuilder("Проверено файлов: " + r.filesChecked() + ", ссылок: " + r.linksChecked() + "\n");
        sb.append("Битые ссылки: ").append(r.broken().isEmpty() ? "нет" : r.broken().size()).append('\n');
        for (ProjectFiles.BrokenLink b : r.broken()) {
            sb.append("  ").append(b.file()).append(':').append(b.line()).append(" → ").append(b.target()).append(" — ").append(b.reason()).append('\n');
        }
        sb.append("Файлы, на которые не ссылается ни один другой .md: ").append(r.orphans().isEmpty() ? "нет" : r.orphans().size()).append('\n');
        r.orphans().forEach(o -> sb.append("  ").append(o).append('\n'));
        List<Map<String, Object>> broken = new ArrayList<>();
        for (ProjectFiles.BrokenLink b : r.broken()) broken.add(Map.of("file", b.file(), "line", b.line(), "target", b.target(), "reason", b.reason()));
        return ok(sb.toString(), Map.of("files", r.filesChecked(), "links", r.linksChecked(), "broken", broken, "orphans", r.orphans()));
    }

    McpSchema.CallToolResult gitLog(Map<String, Object> a) throws Exception {
        Integer limit = integer(a, "limit");
        List<ProjectFiles.Commit> log = files.gitLog(limit == null ? 50 : limit, str(a, "path"), bool(a, "with_files"));
        StringBuilder sb = new StringBuilder(log.size() + " коммитов (новые сверху):\n");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectFiles.Commit c : log) {
            sb.append(c.hash()).append(' ').append(c.date()).append(' ').append(c.author()).append(": ").append(c.subject()).append('\n');
            for (String f : c.files()) sb.append("    ").append(f).append('\n');
            out.add(Map.of("hash", c.hash(), "date", c.date(), "author", c.author(), "subject", c.subject(), "files", c.files()));
        }
        return ok(ProjectFiles.clip(sb.toString(), 30_000), Map.of("commits", out));
    }

    McpSchema.CallToolResult writeFile(Map<String, Object> a) throws Exception {
        String path = req(a, "path");
        boolean existed = files.exists(path);
        String diff = files.write(path, a.get("content") == null ? null : String.valueOf(a.get("content")));
        return changed((existed ? "Перезаписан " : "Создан ") + files.rel(path), diff);
    }

    McpSchema.CallToolResult editFile(Map<String, Object> a) throws Exception {
        String path = req(a, "path");
        String diff = files.edit(path, req(a, "old_text"), a.get("new_text") == null ? "" : String.valueOf(a.get("new_text")), bool(a, "replace_all"));
        return changed("Изменён " + files.rel(path), diff);
    }

    McpSchema.CallToolResult pendingChanges() {
        String diff = files.pendingDiff();
        String text = "Режим: " + (files.applyMode() ? "apply — изменения уже записаны на диск" : "dry-run — на диск ничего не записано")
                + "\n" + files.stat() + (diff.isEmpty() ? "" : "\n" + ProjectFiles.clip(diff, 30_000));
        // contents — итоговый текст каждого изменённого файла: хосту (Task34) для проверок; модели уходит только text
        Map<String, Object> contents = new LinkedHashMap<>();
        for (String p : files.changedFiles()) {
            try {
                contents.put(p, files.read(p));
            } catch (IOException e) {
                contents.put(p, "");
            }
        }
        return ok(text, Map.of("mode", files.applyMode() ? "apply" : "dry-run", "files", files.changedFiles(), "diff", diff,
                "contents", contents));
    }

    private McpSchema.CallToolResult changed(String what, String diff) {
        int[] pm = ProjectFiles.plusMinus(diff);
        String text = what + (files.applyMode() ? " (записано на диск)" : " (dry-run: в diff, на диск не записано)")
                + ", +" + pm[0] + " −" + pm[1] + " строк\n" + ProjectFiles.clip(diff, 6_000);
        return ok(text, Map.of("diff", diff, "files", files.changedFiles()));
    }

    // ================================================================ plumbing

    private static String str(Map<String, Object> a, String key) {
        Object v = a == null ? null : a.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static String req(Map<String, Object> a, String key) throws IOException {
        String v = a == null || a.get(key) == null ? null : String.valueOf(a.get(key));
        if (v == null || v.isEmpty()) throw new IOException("не передан параметр " + key);
        return v;
    }

    private static boolean bool(Map<String, Object> a, String key) {
        Object v = a == null ? null : a.get(key);
        return v instanceof Boolean b ? b : v != null && String.valueOf(v).equalsIgnoreCase("true");
    }

    private static Integer integer(Map<String, Object> a, String key) {
        Object v = a == null ? null : a.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? null : Integer.parseInt(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    interface Handler {
        McpSchema.CallToolResult run() throws Exception;
    }

    McpSchema.CallToolResult safe(Handler h) {
        try {
            return h.run();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder().addTextContent("Ошибка: " + e.getMessage()).isError(true).build();
        }
    }

    private static McpSchema.CallToolResult ok(String text, Map<String, Object> structured) {
        return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(false).build();
    }
}
