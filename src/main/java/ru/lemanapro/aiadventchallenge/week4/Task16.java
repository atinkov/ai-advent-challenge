package ru.lemanapro.aiadventchallenge.week4;

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

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * Day 16: connecting to MCP (Model Context Protocol).
 *
 * The minimal thing the task asks for — and nothing more:
 *   1. start an MCP server as a child process and open a stdio transport to it;
 *   2. perform the MCP handshake (initialize) — this is "the connection is established";
 *   3. ask the server for its tools (tools/list, following nextCursor pagination);
 *   4. print them and check the result is well-formed.
 *
 * Uses the official MCP Java SDK (io.modelcontextprotocol.sdk:mcp) — no hand-written
 * JSON-RPC. No LLM is involved on this day: MCP is a separate protocol layer that
 * later days will plug into the agent.
 *
 * Which server is used:
 *   - MCP_SERVER_COMMAND (env or .env-free process env) — any stdio MCP server command line,
 *     e.g. "npx -y @modelcontextprotocol/server-filesystem /tmp";
 *   - otherwise, if `npx` is on PATH — the official reference server
 *     "npx -y @modelcontextprotocol/server-everything" (needs Node.js; first run downloads it);
 *   - otherwise (or with -Dexec.args="local") — a tiny MCP server embedded in this class
 *     (Task16.LocalServer), started as a separate JVM, so the demo works without Node.js.
 *     It is still a real MCP server speaking the protocol over stdio, not a mock.
 *
 * Writes task16-mcp-report.md.
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask16
 *   mvn -q compile exec:java -Ptask16 -Dexec.args="local"
 *   MCP_SERVER_COMMAND="npx -y @modelcontextprotocol/server-filesystem /tmp" mvn -q compile exec:java -Ptask16
 */
public final class Task16 {

    private static final Path REPORT_FILE = Path.of("task16-mcp-report.md");
    private static final Duration TIMEOUT = Duration.ofSeconds(90); // first npx run downloads the package

    private Task16() {
    }

    public static void main(String[] args) throws Exception {
        boolean forceLocal = Arrays.asList(args).contains("local");
        ServerParameters params = chooseServer(forceLocal);
        String commandLine = (params.getCommand() + " " + String.join(" ", params.getArgs()))
                .replaceAll("-cp \\S+", "-cp <classpath проекта>");

        System.out.println("=== День 16. Подключение MCP ===");
        System.out.println("MCP-сервер (stdio): " + abbreviate(commandLine, 160));
        System.out.println();

        McpJsonMapper json = McpJsonDefaults.getMapper();
        StdioClientTransport transport = new StdioClientTransport(params, json);
        List<String> serverStderr = new ArrayList<>();
        transport.setStdErrorHandler(line -> {
            synchronized (serverStderr) {
                serverStderr.add(line);
            }
        });

        List<String> checks = new ArrayList<>();
        long started = System.nanoTime();

        try (McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("ai-advent-task16", "1.0.0"))
                .initializationTimeout(TIMEOUT)
                .requestTimeout(TIMEOUT)
                .build()) {

            // 1. Handshake: initialize request -> InitializeResult -> notifications/initialized
            McpSchema.InitializeResult init = client.initialize();
            long handshakeMs = (System.nanoTime() - started) / 1_000_000;
            check(checks, client.isInitialized(), "соединение установлено (initialize завершён, isInitialized=true)");
            check(checks, init.serverInfo() != null && notBlank(init.serverInfo().name()),
                    "сервер представился: serverInfo.name задан");
            check(checks, notBlank(init.protocolVersion()), "согласована версия протокола: " + init.protocolVersion());
            check(checks, init.capabilities() != null && init.capabilities().tools() != null,
                    "сервер объявил capability \"tools\"");

            System.out.println("[OK] Соединение установлено за " + handshakeMs + " мс");
            System.out.println("     Сервер:    " + init.serverInfo().name() + " " + init.serverInfo().version());
            System.out.println("     Протокол:  " + init.protocolVersion());
            System.out.println("     Возможности сервера: " + describeCapabilities(init.capabilities()));
            System.out.println();

            // 2. Liveness check on the open session
            client.ping();
            check(checks, true, "ping после установки соединения прошёл");

            // 3. tools/list with pagination
            List<McpSchema.Tool> tools = new ArrayList<>();
            int pages = 0;
            String cursor = null;
            do {
                McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
                tools.addAll(page.tools());
                cursor = page.nextCursor();
                pages++;
            } while (cursor != null && !cursor.isBlank() && pages < 100);

            Set<String> names = tools.stream().map(McpSchema.Tool::name).collect(Collectors.toCollection(LinkedHashSet::new));
            check(checks, !tools.isEmpty(), "tools/list вернул непустой список (" + tools.size() + " шт., страниц: " + pages + ")");
            check(checks, tools.stream().allMatch(t -> notBlank(t.name())), "у каждого инструмента есть имя");
            check(checks, names.size() == tools.size(), "имена инструментов уникальны");
            check(checks, tools.stream().allMatch(t -> t.inputSchema() != null
                            && "object".equals(String.valueOf(t.inputSchema().get("type")))),
                    "у каждого инструмента есть inputSchema с type=object");

            System.out.println("Доступные инструменты (" + tools.size() + "):");
            for (int i = 0; i < tools.size(); i++) {
                McpSchema.Tool t = tools.get(i);
                System.out.printf("  %2d. %-32s %s%n", i + 1, t.name(), abbreviate(oneLine(t.description()), 90));
                String p = describeParams(t);
                if (!p.isEmpty()) {
                    System.out.println("      параметры: " + p);
                }
            }
            System.out.println();

            boolean allOk = checks.stream().allMatch(c -> c.startsWith("[OK]"));
            System.out.println("Проверки:");
            checks.forEach(c -> System.out.println("  " + c));
            System.out.println();
            System.out.println(allOk ? "ИТОГ: соединение с MCP работает, список инструментов получен корректно."
                    : "ИТОГ: есть непройденные проверки — см. выше.");

            writeReport(commandLine, init, handshakeMs, tools, pages, checks, allOk);
            System.out.println("Отчёт: " + REPORT_FILE.toAbsolutePath());
            client.closeGracefully();
            if (!allOk) {
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("[FAIL] Не удалось подключиться к MCP-серверу: " + e);
            synchronized (serverStderr) {
                if (!serverStderr.isEmpty()) {
                    System.err.println("stderr сервера (последние строки):");
                    serverStderr.stream().skip(Math.max(0, serverStderr.size() - 15))
                            .forEach(l -> System.err.println("  | " + l));
                }
            }
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ server selection

    private static ServerParameters chooseServer(boolean forceLocal) {
        String custom = System.getenv("MCP_SERVER_COMMAND");
        if (!forceLocal && custom != null && !custom.isBlank()) {
            List<String> parts = Arrays.asList(custom.trim().split("\\s+"));
            return ServerParameters.builder(parts.getFirst()).args(parts.subList(1, parts.size())).build();
        }
        if (!forceLocal) {
            String npx = findOnPath(isWindows() ? "npx.cmd" : "npx");
            if (npx != null) {
                return ServerParameters.builder(npx)
                        .args("-y", "@modelcontextprotocol/server-everything")
                        .build();
            }
            System.out.println("npx не найден в PATH — использую встроенный MCP-сервер (Task16.LocalServer).");
        }
        String javaBin = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        return ServerParameters.builder(javaBin)
                .args("-cp", runtimeClasspath(), LocalServer.class.getName())
                .build();
    }

    /**
     * Under `mvn exec:java` the project classes and dependencies live in a child URLClassLoader,
     * not in java.class.path — so collect URLs from the loader chain to start the child JVM.
     */
    private static String runtimeClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        for (ClassLoader cl = Task16.class.getClassLoader(); cl != null; cl = cl.getParent()) {
            if (cl instanceof URLClassLoader ucl) {
                for (URL u : ucl.getURLs()) {
                    try {
                        entries.add(Path.of(u.toURI()).toString());
                    } catch (Exception ignored) {
                        // non-file URL, skip
                    }
                }
            }
        }
        entries.addAll(Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        entries.removeIf(String::isBlank);
        return String.join(File.pathSeparator, entries);
    }

    private static String findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            Path p = Path.of(dir, exe);
            if (Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ------------------------------------------------------------------ embedded fallback server

    /**
     * A minimal but real MCP server over stdio (used when Node.js/npx is unavailable).
     * stdout is reserved for JSON-RPC, so nothing else may be printed there.
     */
    public static final class LocalServer {
        private LocalServer() {
        }

        public static void main(String[] args) throws InterruptedException {
            McpJsonMapper json = McpJsonDefaults.getMapper();
            CountDownLatch stdinClosed = new CountDownLatch(1);
            // Exit as soon as the client closes our stdin (end of session), instead of waiting to be killed.
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
                    .serverInfo("ai-advent-local-server", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .toolCall(tool(json, "echo", "Возвращает переданный текст без изменений",
                                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"description\":\"Текст\"}},\"required\":[\"text\"]}"),
                            (ex, req) -> text(String.valueOf(req.arguments().get("text"))))
                    .toolCall(tool(json, "add", "Складывает два числа",
                                    "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"}},\"required\":[\"a\",\"b\"]}"),
                            (ex, req) -> text(String.valueOf(((Number) req.arguments().get("a")).doubleValue()
                                    + ((Number) req.arguments().get("b")).doubleValue())))
                    .toolCall(tool(json, "current_time", "Текущие дата и время сервера (ISO-8601)",
                                    "{\"type\":\"object\",\"properties\":{}}"),
                            (ex, req) -> text(LocalDateTime.now().toString()))
                    .build();
            stdinClosed.await();
            server.closeGracefully();
            System.exit(0);
        }

        private static McpSchema.Tool tool(McpJsonMapper json, String name, String description, String schema) {
            return McpSchema.Tool.builder().name(name).description(description).inputSchema(json, schema).build();
        }

        private static McpSchema.CallToolResult text(String s) {
            return McpSchema.CallToolResult.builder().addTextContent(s).isError(false).build();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void check(List<String> checks, boolean ok, String what) {
        checks.add((ok ? "[OK]   " : "[FAIL] ") + what);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @SuppressWarnings("unchecked")
    private static String describeParams(McpSchema.Tool t) {
        if (t.inputSchema() == null || !(t.inputSchema().get("properties") instanceof Map<?, ?> props) || props.isEmpty()) {
            return "";
        }
        List<String> required = t.inputSchema().get("required") instanceof List<?> r
                ? (List<String>) r : List.of();
        return props.entrySet().stream().map(e -> {
            String type = e.getValue() instanceof Map<?, ?> m && m.get("type") != null ? String.valueOf(m.get("type")) : "any";
            String name = String.valueOf(e.getKey());
            return name + ":" + type + (required.contains(name) ? "*" : "");
        }).collect(Collectors.joining(", "));
    }

    private static String describeCapabilities(McpSchema.ServerCapabilities c) {
        if (c == null) {
            return "—";
        }
        List<String> out = new ArrayList<>();
        if (c.tools() != null) out.add("tools");
        if (c.resources() != null) out.add("resources");
        if (c.prompts() != null) out.add("prompts");
        if (c.logging() != null) out.add("logging");
        if (c.completions() != null) out.add("completions");
        return out.isEmpty() ? "—" : String.join(", ", out);
    }

    private static void writeReport(String commandLine, McpSchema.InitializeResult init, long handshakeMs,
                                    List<McpSchema.Tool> tools, int pages, List<String> checks, boolean allOk) {
        StringBuilder md = new StringBuilder();
        md.append("# День 16. Подключение MCP\n\n");
        md.append("_Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("_ — `mvn -q compile exec:java -Ptask16`\n\n");
        md.append("## Соединение\n\n");
        md.append("| Параметр | Значение |\n|---|---|\n");
        md.append("| SDK | `io.modelcontextprotocol.sdk:mcp` (официальный Java SDK), транспорт stdio |\n");
        md.append("| Команда сервера | `").append(commandLine.replace("|", "\\|")).append("` |\n");
        md.append("| Сервер | ").append(init.serverInfo().name()).append(" ").append(init.serverInfo().version()).append(" |\n");
        md.append("| Версия протокола | ").append(init.protocolVersion()).append(" |\n");
        md.append("| Возможности сервера | ").append(describeCapabilities(init.capabilities())).append(" |\n");
        md.append("| Время рукопожатия | ").append(handshakeMs).append(" мс |\n\n");
        md.append("## Инструменты (").append(tools.size()).append(", страниц tools/list: ").append(pages).append(")\n\n");
        md.append("| # | Имя | Описание | Параметры (* — обязательный) |\n|---|---|---|---|\n");
        for (int i = 0; i < tools.size(); i++) {
            McpSchema.Tool t = tools.get(i);
            md.append("| ").append(i + 1).append(" | `").append(t.name()).append("` | ")
                    .append(abbreviate(oneLine(t.description()), 140).replace("|", "\\|")).append(" | ")
                    .append(describeParams(t).replace("|", "\\|")).append(" |\n");
        }
        md.append("\n## Проверки\n\n");
        checks.forEach(c -> md.append("- ").append(c.trim()).append("\n"));
        md.append("\n**Итог:** ").append(allOk ? "соединение устанавливается, список инструментов возвращается корректно."
                : "есть непройденные проверки.").append("\n");
        try {
            Files.writeString(REPORT_FILE, md.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Не удалось записать отчёт: " + e.getMessage());
        }
    }
}
