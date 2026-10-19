package ru.lemanapro.aiadventchallenge.week7;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;
import ru.lemanapro.aiadventchallenge.week4.McpLaunch;
import ru.lemanapro.aiadventchallenge.week4.McpToolAgent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Day 34: ассистент для работы с файлами проекта. Цель на уровне задачи («найди…», «проверь…», «сгенерируй…») →
 * LLM сама выбирает инструменты MCP-сервера {@link ProjectFilesMcpServer} (list_files / search / read_file /
 * check_links / git_log / edit_file / write_file / pending_changes) → итоговый ответ + diff изменений.
 *
 * Каждый вызов инструмента проходит через {@link Recorder}: он запоминает аргументы и structuredContent,
 * поэтому хост точно знает, какие файлы ассистент прочитал, нашёл поиском и изменил (а не верит тексту ответа).
 */
public final class FilesAssistant implements AutoCloseable {

    static final String SYSTEM_PROMPT = """
            Ты — ассистент, который выполняет задачи над файлами проекта «%s». Доступ к проекту — только через инструменты.
            Режим записи: %s.

            Как работать:
            1. Сам решай, какие файлы нужны: начни со структуры (list_files) и поиска (search), затем читай нужные файлы (read_file).
               Пользователь ставит цель, а не называет файлы — не проси его открыть файл.
            2. Факты о проекте бери только из результатов инструментов: не выдумывай пути, названия, числа, хеши коммитов.
               Если считаешь что-то (уроки, файлы, совпадения) — считай по выводу инструментов.
            3. Правила оформления проекта ищи в самих файлах (карта/README/описание структуры) и проверяй поиском, а не чтением каждого файла:
               search(files_without=true) находит файлы, где чего-то нет; check_links — битые ссылки и файлы, на которые никто не ссылается.
               Читай файл, только когда нужно его содержание, и только нужный фрагмент (start_line / end_line по номерам из search).
            4. Изменения: точечно — edit_file (old_text дословно из read_file, без номеров строк «12| »), новый файл — write_file.
               Меняй только то, что нужно для цели; содержание, которое не просили менять, не переписывай.
            5. Ссылки в Markdown — относительные от файла, где стоит ссылка; путь с пробелами пиши в угловых скобках: [текст](<папка/файл.md>).
               После правок Markdown вызови check_links и исправь, если что-то сломал.
            6. Если менял файлы — перед ответом вызови pending_changes и проверь diff.

            Итоговый ответ — по-русски, Markdown:
            ## Результат
            суть: что найдено / что нарушено / что сделано (конкретно, с путями файлов и номерами строк, где уместно)
            ## Файлы
            - прочитаны/найдены: …
            - изменены/созданы: … (или «нет»)""";

    static final String CONTINUE = """
            Итогового ответа не было. Продолжи работу над той же целью с того места, где остановился: если нужны изменения — \
            внеси их инструментами (edit_file / write_file), проверь (check_links, pending_changes), затем дай итоговый ответ \
            в формате «## Результат / ## Файлы».""";

    static boolean unfinished(String text) {
        return text == null || text.isBlank() || text.startsWith("(агент остановлен");
    }

    /** One recorded tools/call: what the model asked for and what the server returned (structured part). */
    public record ToolCall(String tool, Map<String, Object> args, boolean error, Map<String, Object> structured) {
    }

    public record Result(String goal, String answer, List<McpToolAgent.ToolCallTrace> traces, List<ToolCall> calls,
                         int llmRequests, String mode, String diff, Map<String, String> contents,
                         Set<String> filesRead, Set<String> filesFound, long millis, String error) {

        public Set<String> filesInvolved() {
            Set<String> s = new LinkedHashSet<>(filesRead);
            s.addAll(filesFound);
            s.addAll(contents.keySet());
            return s;
        }

        public List<String> toolsUsed() {
            return calls.stream().map(ToolCall::tool).distinct().toList();
        }
    }

    private final Path root;
    private final boolean apply;
    private final McpSyncClient mcp;
    private final Recorder recorder;
    private final McpToolAgent agent;
    private final LlmClient.Config cfg;

    public FilesAssistant(Path root, boolean apply, LlmClient.Config cfg) {
        this.root = root.toAbsolutePath().normalize();
        this.apply = apply;
        this.cfg = cfg;
        List<String> args = new ArrayList<>(List.of(this.root.toString()));
        if (apply) args.add("--apply");
        this.mcp = McpClient.sync(new StdioClientTransport(
                        McpLaunch.javaServer(ProjectFilesMcpServer.class, args.toArray(String[]::new)), McpJsonDefaults.getMapper()))
                .clientInfo(new McpSchema.Implementation("files-assistant", "1.0.0"))
                .initializationTimeout(Duration.ofSeconds(60))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
        mcp.initialize();
        this.recorder = new Recorder(mcp);
        String prompt = SYSTEM_PROMPT.formatted(this.root.getFileName(),
                apply ? "apply — изменения сразу записываются на диск" : "dry-run — изменения собираются в diff, на диск не пишутся");
        this.agent = cfg == null ? null : new McpToolAgent(cfg, recorder, LlmClient.env("TASK34_TOOL_MODE", "auto"), prompt)
                .maxToolSteps(Integer.parseInt(LlmClient.env("TASK34_MAX_STEPS", "30")))
                .extraBody(thinking(LlmClient.env("TASK34_THINK", "")));
    }

    /**
     * TASK34_THINK: "" (default) — as the server decides; "off" / "on" — Qwen3 thinking switch for vLLM / SGLang
     * (chat_template_kwargs.enable_thinking). Off is faster per request, but qwen3.8-27b without thinking was seen looping
     * on the same search for the rules scenario — keep the default unless the model handles it.
     */
    static Map<String, Object> thinking(String setting) {
        String s = setting == null ? "" : setting.strip().toLowerCase();
        if (s.isEmpty() || s.equals("auto")) return null;
        boolean on = s.equals("on") || s.equals("1") || s.equals("true");
        return Map.of("chat_template_kwargs", Map.of("enable_thinking", on));
    }

    public Path root() {
        return root;
    }

    public boolean applyMode() {
        return apply;
    }

    public boolean hasLlm() {
        return agent != null;
    }

    public String model() {
        return cfg == null ? "—" : cfg.model();
    }

    public List<McpSchema.Tool> tools() {
        return recorder.tools();
    }

    /** Direct call by the application (not the model) — not recorded as the assistant's work. */
    public McpSchema.CallToolResult callDirect(String tool, Map<String, Object> args) {
        return mcp.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    /** One goal in a fresh dialogue (the server's change overlay is kept between goals of the same assistant). */
    public Result run(String goal) {
        return run(goal, null);
    }

    public Result run(String goal, McpToolAgent.Conversation conversation) {
        if (agent == null) throw new IllegalStateException("LLM не настроен — нет LLM_API_KEY");
        int from = recorder.calls.size();
        long t0 = System.nanoTime();
        McpToolAgent.Conversation conv = conversation == null ? agent.conversation() : conversation;
        McpToolAgent.AgentAnswer a = null;
        String error = null;
        List<McpToolAgent.ToolCallTrace> traces = new ArrayList<>();
        int requests = 0;
        // a reasoning model sometimes ends with an empty message (only <think>), hits the step limit, or one request times out —
        // the dialogue (with every tool result so far) is kept, so ask it to continue instead of starting over or returning nothing
        String message = goal;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                a = conv.ask(message);
                traces.addAll(a.calls());
                requests += a.llmRequests();
                error = null;
                if (!unfinished(a.text())) break;
                System.out.println("   [ассистент] пустой/незавершённый ответ — прошу довести цель до конца");
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                boolean transient_ = e instanceof java.net.http.HttpTimeoutException || e instanceof java.io.IOException
                        || (e instanceof LlmClient.RequestException re && re.status() >= 500);
                if (!transient_) break;
                System.out.println("   [ассистент] " + error + " — продолжаю тот же диалог");
            }
            message = CONTINUE;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        List<ToolCall> calls = new ArrayList<>(recorder.calls.subList(from, recorder.calls.size()));
        Set<String> read = new LinkedHashSet<>();
        Set<String> found = new LinkedHashSet<>();
        for (ToolCall c : calls) {
            if (c.error() || c.structured() == null) continue;
            switch (c.tool()) {
                case "read_file" -> read.add(String.valueOf(c.structured().get("path")));
                case "search" -> {
                    if (c.structured().get("per_file") instanceof Map<?, ?> m) m.keySet().forEach(k -> found.add(String.valueOf(k)));
                    if (c.structured().get("files_without") instanceof List<?> l) l.forEach(x -> found.add(String.valueOf(x)));
                }
                case "check_links" -> {
                    if (c.structured().get("orphans") instanceof List<?> l) l.forEach(x -> found.add(String.valueOf(x)));
                    if (c.structured().get("broken") instanceof List<?> l) {
                        l.forEach(x -> {
                            if (x instanceof Map<?, ?> b) found.add(String.valueOf(b.get("file")));
                        });
                    }
                }
                case "git_log" -> {
                    if (c.structured().get("commits") instanceof List<?> l) {
                        l.forEach(x -> {
                            if (x instanceof Map<?, ?> cm && cm.get("files") instanceof List<?> fl) fl.forEach(y -> found.add(String.valueOf(y)));
                        });
                    }
                }
                default -> {
                }
            }
        }
        // git_log with_files also lists files that were renamed or deleted since — keep only what exists now
        found.removeIf(f -> !java.nio.file.Files.isRegularFile(root.resolve(f)));
        Pending p = pending();
        if (error == null && a != null && unfinished(a.text())) error = "ассистент не дал итогового ответа";
        return new Result(goal, a == null ? "" : a.text(), traces, calls, requests, a == null ? "-" : a.mode(),
                p.diff(), p.contents(), read, found, ms, error);
    }

    public McpToolAgent.Conversation conversation() {
        if (agent == null) throw new IllegalStateException("LLM не настроен — нет LLM_API_KEY");
        return agent.conversation();
    }

    public record Pending(String diff, Map<String, String> contents) {
    }

    /** All changes of this session (diff + final text of every changed file), asked by the host directly. */
    @SuppressWarnings("unchecked")
    public Pending pending() {
        McpSchema.CallToolResult r = callDirect("pending_changes", Map.of());
        Map<String, Object> s = r.structuredContent() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, String> contents = new LinkedHashMap<>();
        if (s.get("contents") instanceof Map<?, ?> c) c.forEach((k, v) -> contents.put(String.valueOf(k), String.valueOf(v)));
        return new Pending(s.get("diff") == null ? "" : String.valueOf(s.get("diff")), contents);
    }

    @Override
    public void close() {
        mcp.closeGracefully();
    }

    /** ToolRouter that forwards to the MCP client and records every call made by the model. */
    static final class Recorder implements McpToolAgent.ToolRouter {
        private final McpSyncClient mcp;
        private final List<McpSchema.Tool> tools;
        final List<ToolCall> calls = new ArrayList<>();

        Recorder(McpSyncClient mcp) {
            this.mcp = mcp;
            this.tools = mcp.listTools().tools();
        }

        @Override
        public List<McpSchema.Tool> tools() {
            return tools;
        }

        @Override
        @SuppressWarnings("unchecked")
        public McpSchema.CallToolResult call(String toolName, Map<String, Object> args) {
            McpSchema.CallToolResult r = mcp.callTool(new McpSchema.CallToolRequest(toolName, args));
            Map<String, Object> structured = r.structuredContent() instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            calls.add(new ToolCall(toolName, args, Boolean.TRUE.equals(r.isError()), structured));
            return r;
        }

        @Override
        public String serverOf(String toolName) {
            return "files";
        }

        @Override
        public void close() {
            // the MCP client is closed by FilesAssistant
        }
    }
}
