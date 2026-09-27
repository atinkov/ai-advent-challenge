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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 17: our own MCP server around a real API (Git), plugged into an LLM agent.
 *
 * Two halves, both in this file:
 *
 *   GitMcpServer — a stdio MCP server (official MCP Java SDK) that wraps the Git CLI of one
 *   repository (read-only). It REGISTERS three tools, each with a JSON Schema DESCRIBING ITS
 *   INPUT PARAMETERS, and RETURNS results both as text (what an LLM reads) and as
 *   structuredContent (what a program reads):
 *     git_log(limit, ref, author, path)   — recent commits
 *     git_commit_details(sha)             — author/date/message + changed files with +/- lines
 *     git_branches(include_remote)        — branches with their last commit, newest first
 *
 *   McpToolAgent — connects to that server as an MCP client, turns the server's tool list
 *   (tools/list) into the OpenAI "tools" array, and runs a tool-calling loop against the LLM:
 *   model asks for a tool -> agent executes it via MCP tools/call -> result goes back into the
 *   dialogue as a "tool" message -> model answers using it (or asks for another tool, e.g.
 *   git_log first to find a sha, then git_commit_details on that sha).
 *   Tool calling mode: native OpenAI function calling; if the server rejects "tools" (HTTP 4xx)
 *   or the model prints a <tool_call>{...}</tool_call> block as plain text (Qwen without a
 *   tool parser), the agent handles that too — see TASK17_TOOL_MODE.
 *
 * Demo:
 *   1. start the server, show registered tools + their input schemas;
 *   2. call a tool directly from the application (no LLM) and use the structured result;
 *   3. ask the agent 3 questions that can only be answered with repository data; each answer is
 *      checked: a tool was called via MCP, the call succeeded, and the final answer actually
 *      contains data from the tool result (a sha / branch name), i.e. the result was used.
 * Writes task17-mcp-agent-report.md.
 *
 * Env:
 *   TASK17_GIT_REPO   repository the server wraps (default: current directory = this project)
 *   TASK17_TOOL_MODE  auto (default) | native | prompt
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask17
 *   mvn -q compile exec:java -Ptask17 -Dexec.args="Кто чаще всех коммитил за последние 20 коммитов?"
 *   LLM_INSECURE_TLS=1 mvn -q compile exec:java -Ptask17   # if the LLM gateway's TLS cert is broken
 */
public final class Task17 {

    private static final Path REPORT_FILE = Path.of("task17-mcp-agent-report.md");
    private static final int MAX_TOOL_STEPS = 6;

    private static final List<String> DEMO_QUESTIONS = List.of(
            "Какие 5 последних коммитов в репозитории? Для каждого укажи короткий хеш, дату и что сделано.",
            "Найди коммит, в котором завершили task15, и перечисли, какие файлы в нём изменились и насколько (строк +/−).",
            "Какие ветки есть в репозитории и в какой из них был самый свежий коммит?");

    private Task17() {
    }

    // =====================================================================================
    //  MCP SERVER: Git API
    // =====================================================================================

    /** stdio MCP server wrapping `git` for one repository. stdout is reserved for JSON-RPC. */
    public static final class GitMcpServer {

        private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/@{}~^-]{1,200}");
        private static final String US = "\u001f"; // field separator in git --format
        private static final String RS = "\u001e"; // record separator

        private final Path repo;

        private GitMcpServer(Path repo) {
            this.repo = repo;
        }

        public static void main(String[] args) throws Exception {
            Path repo = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
            GitMcpServer git = new GitMcpServer(repo);
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
                    .serverInfo("git-mcp-server", "1.0.0")
                    .instructions("Read-only доступ к git-репозиторию " + repo.getFileName()
                            + ": история коммитов, детали коммита, ветки.")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    // ---- tool registration: definition (name, description, input schema) + handler
                    .toolCall(McpSchema.Tool.builder()
                                    .name("git_log")
                                    .title("История коммитов")
                                    .description("Список последних коммитов репозитория (новые сверху): короткий и полный хеш, "
                                            + "автор, дата, заголовок. Можно ограничить веткой/ref, автором и путём к файлу.")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{
                                               "limit":{"type":"integer","minimum":1,"maximum":50,"default":10,
                                                        "description":"Сколько коммитов вернуть (1-50), по умолчанию 10"},
                                               "ref":{"type":"string",
                                                      "description":"Ветка, тег или коммит, с которого смотреть историю; по умолчанию текущий HEAD"},
                                               "author":{"type":"string","description":"Фильтр по автору (подстрока имени или email)"},
                                               "path":{"type":"string","description":"Только коммиты, затрагивающие этот файл/папку"}
                                             },
                                             "additionalProperties":false}""")
                                    .annotations(readOnly())
                                    .build(),
                            (ex, req) -> git.safe(() -> git.log(req.arguments())))
                    .toolCall(McpSchema.Tool.builder()
                                    .name("git_commit_details")
                                    .title("Детали коммита")
                                    .description("Полная информация об одном коммите: автор, дата, сообщение целиком и список "
                                            + "изменённых файлов с числом добавленных/удалённых строк.")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{
                                               "sha":{"type":"string","description":"Хеш коммита (полный или короткий, от 4 символов) или ref"}
                                             },
                                             "required":["sha"],
                                             "additionalProperties":false}""")
                                    .annotations(readOnly())
                                    .build(),
                            (ex, req) -> git.safe(() -> git.commitDetails(req.arguments())))
                    .toolCall(McpSchema.Tool.builder()
                                    .name("git_branches")
                                    .title("Ветки")
                                    .description("Список веток с последним коммитом в каждой, отсортирован по дате коммита "
                                            + "(самая свежая первой). Отмечает текущую ветку.")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{
                                               "include_remote":{"type":"boolean","default":false,
                                                                 "description":"Добавить удалённые ветки (origin/...)"}
                                             },
                                             "additionalProperties":false}""")
                                    .annotations(readOnly())
                                    .build(),
                            (ex, req) -> git.safe(() -> git.branches(req.arguments())))
                    .build();

            stdinClosed.await();
            server.closeGracefully();
            System.exit(0);
        }

        private static McpSchema.ToolAnnotations readOnly() {
            return new McpSchema.ToolAnnotations(null, true, false, true, false, null);
        }

        // ---- tool handlers: each returns text (for the LLM) + structuredContent (for programs)

        private McpSchema.CallToolResult log(Map<String, Object> args) throws Exception {
            int limit = Math.max(1, Math.min(50, intArg(args, "limit", 10)));
            List<String> cmd = new ArrayList<>(List.of("log", "--max-count=" + limit, "--date=iso-strict",
                    "--pretty=format:%H" + US + "%h" + US + "%an" + US + "%ae" + US + "%ad" + US + "%s" + RS));
            String ref = strArg(args, "ref");
            if (ref != null) cmd.add(checkRef(ref));
            String author = strArg(args, "author");
            if (author != null) cmd.add("--author=" + author);
            cmd.add("--");
            String path = strArg(args, "path");
            if (path != null) cmd.add(path);

            List<Map<String, Object>> commits = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (String rec : git(cmd).split(RS)) {
                String[] f = rec.strip().split(US, -1);
                if (f.length < 6) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("sha", f[0]);
                c.put("short_sha", f[1]);
                c.put("author", f[2]);
                c.put("email", f[3]);
                c.put("date", f[4]);
                c.put("subject", f[5]);
                commits.add(c);
                text.append(f[1]).append(" | ").append(f[4]).append(" | ").append(f[2]).append(" | ").append(f[5]).append('\n');
            }
            String header = "Коммитов: " + commits.size() + (ref != null ? " (ref " + ref + ")" : " (HEAD)") + "\n"
                    + "short_sha | дата | автор | заголовок\n";
            return ok(header + text, Map.of("repository", repo.getFileName().toString(),
                    "ref", ref == null ? "HEAD" : ref, "count", commits.size(), "commits", commits));
        }

        private McpSchema.CallToolResult commitDetails(Map<String, Object> args) throws Exception {
            String sha = strArg(args, "sha");
            if (sha == null) return error("Параметр sha обязателен");
            checkRef(sha);
            String head = git(List.of("show", "-s", "--date=iso-strict",
                    "--format=%H" + US + "%an" + US + "%ae" + US + "%ad" + US + "%B", sha, "--"));
            String[] f = head.split(US, -1);
            if (f.length < 5) return error("Не удалось разобрать коммит " + sha);

            List<Map<String, Object>> files = new ArrayList<>();
            int totalAdd = 0, totalDel = 0;
            for (String line : git(List.of("show", "--numstat", "--format=", sha, "--")).split("\n")) {
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                boolean binary = p[0].equals("-");
                int add = binary ? 0 : Integer.parseInt(p[0]);
                int del = binary ? 0 : Integer.parseInt(p[1]);
                totalAdd += add;
                totalDel += del;
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("path", p[2]);
                file.put("added", add);
                file.put("deleted", del);
                file.put("binary", binary);
                files.add(file);
            }
            StringBuilder text = new StringBuilder();
            text.append("Коммит ").append(f[0]).append('\n')
                    .append("Автор: ").append(f[1]).append(" <").append(f[2]).append(">\n")
                    .append("Дата: ").append(f[3]).append('\n')
                    .append("Сообщение:\n").append(f[4].strip()).append("\n\n")
                    .append("Изменённые файлы (").append(files.size()).append(", всего +").append(totalAdd)
                    .append(" −").append(totalDel).append("):\n");
            for (Map<String, Object> file : files) {
                text.append("  ").append(file.get("path")).append("  +").append(file.get("added"))
                        .append(" −").append(file.get("deleted")).append(Boolean.TRUE.equals(file.get("binary")) ? " (binary)" : "")
                        .append('\n');
            }
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("sha", f[0]);
            structured.put("author", f[1]);
            structured.put("date", f[3]);
            structured.put("message", f[4].strip());
            structured.put("files", files);
            structured.put("total_added", totalAdd);
            structured.put("total_deleted", totalDel);
            return ok(text.toString(), structured);
        }

        private McpSchema.CallToolResult branches(Map<String, Object> args) throws Exception {
            boolean remote = boolArg(args, "include_remote", false);
            String current = git(List.of("branch", "--show-current")).strip();
            List<String> cmd = new ArrayList<>(List.of("for-each-ref", "--sort=-committerdate",
                    "--format=%(refname:short)" + US + "%(objectname:short)" + US + "%(committerdate:iso-strict)"
                            + US + "%(subject)" + RS, "refs/heads"));
            if (remote) cmd.add("refs/remotes");

            List<Map<String, Object>> list = new ArrayList<>();
            StringBuilder text = new StringBuilder("Текущая ветка: " + (current.isEmpty() ? "(detached HEAD)" : current)
                    + "\nветка | short_sha | дата последнего коммита | заголовок (самая свежая первой)\n");
            for (String rec : git(cmd).split(RS)) {
                String[] f = rec.strip().split(US, -1);
                if (f.length < 4 || f[0].endsWith("/HEAD")) continue;
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("name", f[0]);
                b.put("short_sha", f[1]);
                b.put("date", f[2]);
                b.put("subject", f[3]);
                b.put("current", f[0].equals(current));
                list.add(b);
                text.append(f[0].equals(current) ? "* " : "  ").append(f[0]).append(" | ").append(f[1]).append(" | ")
                        .append(f[2]).append(" | ").append(f[3]).append('\n');
            }
            return ok(text.toString(), Map.of("current", current, "count", list.size(), "branches", list));
        }

        // ---- plumbing

        interface Handler {
            McpSchema.CallToolResult run() throws Exception;
        }

        /** Any failure becomes an MCP tool error (isError=true) the agent can read — never a crashed server. */
        McpSchema.CallToolResult safe(Handler h) {
            try {
                return h.run();
            } catch (Exception e) {
                return error(e.getMessage());
            }
        }

        private String git(List<String> args) throws Exception {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString(), "--no-pager"));
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("GIT_OPTIONAL_LOCKS", "0"); // read-only: never touch the index lock
            pb.redirectErrorStream(false);
            Process p = pb.start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("git не ответил за 30 с");
            }
            if (p.exitValue() != 0) {
                throw new IllegalArgumentException("git " + args.getFirst() + " завершился с ошибкой: "
                        + (err.isEmpty() ? "код " + p.exitValue() : err));
            }
            return new String(out, StandardCharsets.UTF_8);
        }

        private static String checkRef(String ref) {
            if (!SAFE_REF.matcher(ref).matches() || ref.startsWith("-")) {
                throw new IllegalArgumentException("Недопустимое значение ref/sha: " + ref);
            }
            return ref;
        }

        private static McpSchema.CallToolResult ok(String text, Object structured) {
            return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(false).build();
        }

        private static McpSchema.CallToolResult error(String message) {
            return McpSchema.CallToolResult.builder().addTextContent("Ошибка: " + message).isError(true).build();
        }

        private static String strArg(Map<String, Object> args, String name) {
            Object v = args == null ? null : args.get(name);
            return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
        }

        private static int intArg(Map<String, Object> args, String name, int def) {
            Object v = args == null ? null : args.get(name);
            if (v instanceof Number n) return n.intValue();
            try {
                return v == null ? def : Integer.parseInt(String.valueOf(v).strip());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static boolean boolArg(Map<String, Object> args, String name, boolean def) {
            Object v = args == null ? null : args.get(name);
            if (v instanceof Boolean b) return b;
            return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
        }
    }

    // =====================================================================================
    //  AGENT: LLM + MCP client
    // =====================================================================================

    record ToolCallTrace(String tool, String argumentsJson, boolean isError, String resultPreview, long millis) {
    }

    record AgentAnswer(String text, List<ToolCallTrace> calls, int llmRequests, String mode) {
    }

    enum Mode { NATIVE, PROMPT }

    /** Agent whose tools come from an MCP server, not from Java code in the agent. */
    static final class McpToolAgent implements AutoCloseable {

        private static final Pattern TOOL_CALL_TAG = Pattern.compile("<tool_call>\\s*(\\{.*?})\\s*</tool_call>", Pattern.DOTALL);
        private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

        private final LlmClient.Config cfg;
        private final HttpClient http = LlmClient.newHttpClient();
        private final McpSyncClient mcp;
        private final List<McpSchema.Tool> mcpTools;
        private final List<Map<String, Object>> openAiTools = new ArrayList<>();
        private Mode mode;
        private final boolean autoMode;

        McpToolAgent(LlmClient.Config cfg, McpSyncClient mcp, String modeSetting) {
            this.cfg = cfg;
            this.mcp = mcp;
            this.mcpTools = mcp.listTools().tools();
            // MCP tool definition -> OpenAI function definition: the inputSchema is passed through as-is
            for (McpSchema.Tool t : mcpTools) {
                openAiTools.add(Map.of("type", "function", "function", Map.of(
                        "name", t.name(),
                        "description", t.description() == null ? "" : t.description(),
                        "parameters", t.inputSchema())));
            }
            this.autoMode = "auto".equalsIgnoreCase(modeSetting);
            this.mode = "prompt".equalsIgnoreCase(modeSetting) ? Mode.PROMPT : Mode.NATIVE;
        }

        List<McpSchema.Tool> tools() {
            return mcpTools;
        }

        private String systemPrompt() {
            String base = """
                    Ты ассистент разработчика с доступом к git-репозиторию проекта через инструменты (MCP-сервер git-mcp-server).
                    Правила:
                    - Любые факты о репозитории (коммиты, хеши, файлы, ветки, даты, авторы) бери ТОЛЬКО из результатов инструментов, не выдумывай.
                    - Если для ответа нужно несколько шагов (например, сначала найти коммит через git_log, потом посмотреть его через git_commit_details) — вызывай инструменты последовательно.
                    - Итоговый ответ — на русском, кратко, с конкретными значениями из результатов (короткие хеши, имена веток, файлы, числа).""";
            if (mode == Mode.NATIVE) {
                return base;
            }
            StringBuilder sb = new StringBuilder(base).append("""


                    Доступные инструменты (JSON Schema параметров):
                    """);
            for (McpSchema.Tool t : mcpTools) {
                sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n  parameters: ");
                try {
                    sb.append(LlmClient.toJson(t.inputSchema()));
                } catch (Exception e) {
                    sb.append("{}");
                }
                sb.append('\n');
            }
            sb.append("""

                    Чтобы вызвать инструмент, ответь ТОЛЬКО блоком (без другого текста):
                    <tool_call>{"name": "<имя инструмента>", "arguments": {<параметры>}}</tool_call>
                    Результат придёт следующим сообщением. Когда данных достаточно — дай обычный итоговый ответ без <tool_call>.""");
            return sb.toString();
        }

        AgentAnswer ask(String question) throws Exception {
            List<ToolCallTrace> calls = new ArrayList<>();
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(msg("system", systemPrompt()));
            messages.add(msg("user", question));
            int requests = 0;

            for (int step = 0; step <= MAX_TOOL_STEPS; step++) {
                JsonNode response;
                try {
                    requests++;
                    response = LlmClient.sendWithTools(http, cfg, messages, mode == Mode.NATIVE ? openAiTools : null, 0.2);
                } catch (LlmClient.RequestException e) {
                    if (autoMode && mode == Mode.NATIVE && e.status() >= 400 && e.status() < 500) {
                        System.out.println("   [агент] сервер LLM отклонил параметр tools (HTTP " + e.status()
                                + ") — переключаюсь на текстовый протокол <tool_call>");
                        mode = Mode.PROMPT;
                        messages.set(0, msg("system", systemPrompt()));
                        step--;
                        continue;
                    }
                    throw e;
                }

                JsonNode message = response.path("choices").path(0).path("message");
                String content = THINK.matcher(message.path("content").asText("")).replaceAll("").strip();
                JsonNode nativeCalls = message.path("tool_calls");

                if (nativeCalls.isArray() && !nativeCalls.isEmpty()) {
                    // assistant turn with tool_calls must be echoed back verbatim before the tool results
                    Map<String, Object> assistant = new LinkedHashMap<>();
                    assistant.put("role", "assistant");
                    assistant.put("content", content.isEmpty() ? null : content);
                    List<Map<String, Object>> echoed = new ArrayList<>();
                    for (JsonNode c : nativeCalls) {
                        echoed.add(Map.of("id", c.path("id").asText(), "type", "function", "function", Map.of(
                                "name", c.path("function").path("name").asText(),
                                "arguments", c.path("function").path("arguments").asText("{}"))));
                    }
                    assistant.put("tool_calls", echoed);
                    messages.add(assistant);
                    for (JsonNode c : nativeCalls) {
                        Executed t = execute(c.path("function").path("name").asText(),
                                c.path("function").path("arguments").asText("{}"));
                        calls.add(t.trace());
                        Map<String, Object> toolMsg = new LinkedHashMap<>();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", c.path("id").asText());
                        toolMsg.put("content", t.fullResult());
                        messages.add(toolMsg);
                    }
                    continue;
                }

                Matcher m = TOOL_CALL_TAG.matcher(content);
                if (m.find()) {
                    // text protocol: model wrote <tool_call>{...}</tool_call> (prompt mode, or Qwen without a tool parser)
                    JsonNode call = LlmClient.parseJson(m.group(1));
                    JsonNode argsNode = call.path("arguments");
                    String argsJson = argsNode.isTextual() ? argsNode.asText() : LlmClient.toJson(argsNode.isMissingNode() ? Map.of() : argsNode);
                    Executed t = execute(call.path("name").asText(), argsJson);
                    calls.add(t.trace());
                    messages.add(msg("assistant", m.group(0)));
                    messages.add(msg("user", "Результат инструмента " + t.trace().tool() + (t.trace().isError() ? " (ОШИБКА)" : "")
                            + ":\n" + t.fullResult() + "\n\nПродолжай: вызови ещё инструмент или дай итоговый ответ."));
                    continue;
                }

                return new AgentAnswer(content, calls, requests, mode.name().toLowerCase());
            }
            return new AgentAnswer("(агент остановлен: превышен лимит вызовов инструментов " + MAX_TOOL_STEPS + ")",
                    calls, requests, mode.name().toLowerCase());
        }

        /** trace for the report + the full text that goes back to the model */
        private record Executed(ToolCallTrace trace, String fullResult) {
        }

        /** The actual MCP call: tools/call on the server, whatever the LLM asked for. */
        private Executed execute(String name, String argsJson) {
            long t0 = System.nanoTime();
            Map<String, Object> args;
            try {
                JsonNode parsed = LlmClient.parseJson(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
                args = parsed.isObject() ? JSON.convertValue(parsed, MAP_TYPE) : Map.of();
            } catch (Exception e) {
                args = Map.of();
            }
            System.out.println("   [MCP] → tools/call " + name + " " + argsJson);
            McpSchema.CallToolResult r;
            try {
                r = mcp.callTool(new McpSchema.CallToolRequest(name, args));
            } catch (Exception e) {
                r = McpSchema.CallToolResult.builder().addTextContent("Ошибка вызова MCP: " + e.getMessage()).isError(true).build();
            }
            String text = textOf(r);
            boolean err = Boolean.TRUE.equals(r.isError());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("   [MCP] ← " + (err ? "ошибка" : "ok") + ", " + text.length() + " символов, " + ms + " мс");
            return new Executed(new ToolCallTrace(name, argsJson, err, preview(text, 1500), ms), text);
        }

        @Override
        public void close() {
            mcp.closeGracefully();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> MAP_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    static String textOf(McpSchema.CallToolResult r) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : r.content()) {
            if (c instanceof McpSchema.TextContent t) sb.append(t.text());
        }
        return sb.toString();
    }

    private static String preview(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // =====================================================================================
    //  DEMO
    // =====================================================================================

    record Scenario(String question, AgentAnswer answer, List<String> checks, boolean passed) {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        Path repo = Path.of(LlmClient.env("TASK17_GIT_REPO", ".")).toAbsolutePath().normalize();
        String toolMode = LlmClient.env("TASK17_TOOL_MODE", "auto");
        List<String> questions = args.length > 0 ? List.of(String.join(" ", args)) : DEMO_QUESTIONS;

        ServerParameters params = McpLaunch.javaServer(GitMcpServer.class, repo.toString());
        System.out.println("=== День 17. Свой MCP-сервер (Git API) + агент ===");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl() + ", режим вызова инструментов: " + toolMode);
        System.out.println("MCP-сервер: " + McpLaunch.display(params));
        System.out.println("Репозиторий: " + repo);
        System.out.println();

        McpSyncClient mcp = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .clientInfo(new McpSchema.Implementation("ai-advent-task17-agent", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
        McpSchema.InitializeResult init = mcp.initialize();
        System.out.println("[OK] MCP-соединение: " + init.serverInfo().name() + " " + init.serverInfo().version()
                + ", протокол " + init.protocolVersion());

        try (McpToolAgent agent = new McpToolAgent(cfg, mcp, toolMode)) {
            // ---- 1. registered tools + their input schemas
            System.out.println("\n--- 1. Зарегистрированные инструменты (tools/list) ---");
            for (McpSchema.Tool t : agent.tools()) {
                System.out.println("• " + t.name() + " — " + t.description());
                System.out.println("  inputSchema: " + LlmClient.toJson(t.inputSchema()));
            }

            // ---- 2. direct call from the application, no LLM
            System.out.println("\n--- 2. Прямой вызов из приложения: git_log {limit: 3} ---");
            McpSchema.CallToolResult direct = mcp.callTool(new McpSchema.CallToolRequest("git_log", Map.of("limit", 3)));
            System.out.println(textOf(direct).strip());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> commits = direct.structuredContent() instanceof Map<?, ?> sc
                    ? (List<Map<String, Object>>) ((Map<String, Object>) sc).get("commits") : List.of();
            String lastSha = commits.isEmpty() ? "?" : String.valueOf(commits.getFirst().get("short_sha"));
            System.out.println("→ приложение использует structuredContent: последний коммит = " + lastSha
                    + " («" + (commits.isEmpty() ? "" : commits.getFirst().get("subject")) + "»)");

            // ---- 3. agent: LLM decides which MCP tool to call and uses the result
            System.out.println("\n--- 3. Агент: LLM вызывает MCP-инструменты сам ---");
            List<Scenario> scenarios = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) {
                String q = questions.get(i);
                System.out.println("\n[" + (i + 1) + "/" + questions.size() + "] Вопрос: " + q);
                AgentAnswer a = agent.ask(q);
                System.out.println("Ответ агента (" + a.calls().size() + " вызов(а) MCP, " + a.llmRequests()
                        + " запрос(а) к LLM, режим " + a.mode() + "):");
                System.out.println(a.text().strip().indent(2).stripTrailing());
                Scenario s = evaluate(q, a);
                s.checks().forEach(c -> System.out.println("  " + c));
                scenarios.add(s);
            }

            long passed = scenarios.stream().filter(Scenario::passed).count();
            System.out.println("\nИТОГ: " + passed + "/" + scenarios.size()
                    + " сценариев — агент вызвал MCP-инструмент, получил результат и использовал его в ответе.");
            writeReport(cfg, repo, init, agent.tools(), textOf(direct), lastSha, scenarios);
            System.out.println("Отчёт: " + REPORT_FILE.toAbsolutePath());
        }
    }

    /** Checks from the actual trace and answer text, not assumed. */
    private static Scenario evaluate(String question, AgentAnswer a) {
        List<String> checks = new ArrayList<>();
        boolean called = !a.calls().isEmpty();
        boolean okCall = a.calls().stream().anyMatch(c -> !c.isError());
        // "used the result": the answer contains a concrete token that only the tool result could provide
        List<String> tokens = new ArrayList<>();
        Pattern sha = Pattern.compile("\\b[0-9a-f]{7,40}\\b");
        Pattern branch = Pattern.compile("(?m)^\\*?\\s*([\\w./-]+) \\| [0-9a-f]{7,}");
        Pattern file = Pattern.compile("(?m)^\\s{2}(\\S+)\\s{2}\\+\\d+");
        for (ToolCallTrace c : a.calls()) {
            Matcher m = sha.matcher(c.resultPreview());
            while (m.find()) tokens.add(m.group().substring(0, 7));
            Matcher b = branch.matcher(c.resultPreview());
            while (b.find()) tokens.add(b.group(1));
            Matcher f = file.matcher(c.resultPreview());
            while (f.find()) tokens.add(f.group(1));
        }
        String used = tokens.stream().filter(t -> a.text().contains(t)).findFirst().orElse(null);
        checks.add((called ? "[OK]   " : "[FAIL] ") + "агент вызвал MCP-инструмент: "
                + (called ? a.calls().stream().map(ToolCallTrace::tool).toList() : "нет"));
        checks.add((okCall ? "[OK]   " : "[FAIL] ") + "инструмент вернул результат без ошибки");
        checks.add((used != null ? "[OK]   " : "[FAIL] ") + "ответ использует данные из результата"
                + (used != null ? " (содержит «" + used + "» из ответа инструмента)" : ""));
        return new Scenario(question, a, checks, called && okCall && used != null);
    }

    private static void writeReport(LlmClient.Config cfg, Path repo, McpSchema.InitializeResult init,
                                    List<McpSchema.Tool> tools, String directText, String lastSha,
                                    List<Scenario> scenarios) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 17. Свой MCP-сервер вокруг Git API + агент\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask17`\n\n");
        md.append("| Параметр | Значение |\n|---|---|\n");
        md.append("| MCP-сервер | ").append(init.serverInfo().name()).append(" ").append(init.serverInfo().version())
                .append(" (`Task17.GitMcpServer`, stdio, официальный MCP Java SDK) |\n");
        md.append("| Протокол | ").append(init.protocolVersion()).append(" |\n");
        md.append("| Обёрнутое API | Git CLI (read-only) над репозиторием `").append(repo.getFileName()).append("` |\n");
        md.append("| Модель агента | ").append(cfg.model()).append(" |\n\n");
        md.append("```\nLLM ⇄ McpToolAgent ──MCP tools/call (stdio, JSON-RPC)──▶ GitMcpServer ──▶ git CLI ──▶ репозиторий\n```\n\n");

        md.append("## 1. Регистрация инструментов и описание параметров\n\n");
        for (McpSchema.Tool t : tools) {
            md.append("### `").append(t.name()).append("`\n\n").append(t.description()).append("\n\n```json\n")
                    .append(LlmClient.toJson(t.inputSchema())).append("\n```\n\n");
        }
        md.append("## 2. Прямой вызов из приложения (без LLM)\n\n`git_log {\"limit\": 3}` →\n\n```\n")
                .append(directText.strip()).append("\n```\n\nПриложение прочитало `structuredContent.commits[0].short_sha` = `")
                .append(lastSha).append("`.\n\n");

        md.append("## 3. Агент вызывает MCP-инструменты\n\n");
        for (int i = 0; i < scenarios.size(); i++) {
            Scenario s = scenarios.get(i);
            md.append("### ").append(i + 1).append(". ").append(s.question()).append("\n\n");
            md.append("Режим: ").append(s.answer().mode()).append(", запросов к LLM: ").append(s.answer().llmRequests())
                    .append(", вызовов MCP: ").append(s.answer().calls().size()).append("\n\n");
            for (ToolCallTrace c : s.answer().calls()) {
                md.append("- `").append(c.tool()).append(" ").append(c.argumentsJson()).append("` → ")
                        .append(c.isError() ? "**ошибка**" : "ok").append(", ").append(c.millis()).append(" мс\n");
            }
            md.append("\n**Ответ агента:**\n\n").append(s.answer().text().strip().indent(0).lines()
                    .map(l -> "> " + l).reduce((x, y) -> x + "\n" + y).orElse("> ")).append("\n\n");
            s.checks().forEach(c -> md.append("- ").append(c.trim()).append('\n'));
            md.append('\n');
        }
        long passed = scenarios.stream().filter(Scenario::passed).count();
        md.append("**Итог:** ").append(passed).append("/").append(scenarios.size())
                .append(" — агент сделал вызов к MCP-инструменту, получил результат и использовал его в ответе.\n");
        Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
    }
}
