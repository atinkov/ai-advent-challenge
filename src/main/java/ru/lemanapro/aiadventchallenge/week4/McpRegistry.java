package ru.lemanapro.aiadventchallenge.week4;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Week 4 (day 20): a registry of several MCP servers behind one tool catalog.
 *
 * register(name, description, params) starts a server (stdio child process) and connects an MCP client;
 * every tool of that server is re-published under a namespaced name "<server>__<tool>" (OpenAI function
 * names allow [a-zA-Z0-9_-] only, so no dots) and its description is prefixed with "[<server>: <what the
 * server is for>]" so the model sees which system a tool belongs to. Two servers may therefore expose tools
 * with the same name without clashing.
 *
 * call(namespacedName, args) is the router: it looks the name up in the routing table, strips the prefix
 * and sends tools/call to the owning server's client. Unknown names are answered with an MCP tool error
 * (isError=true) listing the available tools — never thrown — so the model can correct itself. Every
 * routed call is appended to a log (server, tool, ok/error, ms) that the orchestrator checks afterwards.
 */
final class McpRegistry implements McpToolAgent.ToolRouter {

    static final String SEP = "__";
    private static final Pattern SERVER_NAME = Pattern.compile("[a-z][a-z0-9-]{0,20}");

    record Server(String name, String description, McpSyncClient client, McpSchema.InitializeResult init,
                  List<McpSchema.Tool> tools, String commandLine) {
    }

    record Route(String server, String tool) {
    }

    /** origin: "agent" = chosen by the LLM, "app" = the application itself (setup / verification). */
    record RoutedCall(int seq, String origin, String server, String tool, Map<String, Object> args, boolean ok, long millis) {
    }

    private final Map<String, Server> servers = new LinkedHashMap<>();
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final List<McpSchema.Tool> catalog = new ArrayList<>();
    private final List<RoutedCall> log = Collections.synchronizedList(new ArrayList<>());

    /** Starts the server, performs the MCP handshake, lists its tools and adds them to the routing table. */
    Server register(String name, String description, ServerParameters params) {
        if (!SERVER_NAME.matcher(name).matches()) throw new IllegalArgumentException("bad server name " + name);
        if (servers.containsKey(name)) throw new IllegalArgumentException("server already registered: " + name);
        McpSyncClient client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .clientInfo(new McpSchema.Implementation("ai-advent-orchestrator", "1.0.0"))
                .initializationTimeout(Duration.ofSeconds(90))
                .requestTimeout(Duration.ofMinutes(3))
                .build();
        McpSchema.InitializeResult init = client.initialize();
        List<McpSchema.Tool> tools = new ArrayList<>();
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
            tools.addAll(page.tools());
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());

        for (McpSchema.Tool t : tools) {
            String ns = name + SEP + t.name();
            routes.put(ns, new Route(name, t.name()));
            catalog.add(McpSchema.Tool.builder()
                    .name(ns)
                    .title(t.title())
                    .description("[" + name + ": " + description + "] " + (t.description() == null ? "" : t.description()))
                    .inputSchema(t.inputSchema())
                    .annotations(t.annotations())
                    .build());
        }
        Server s = new Server(name, description, client, init, List.copyOf(tools), McpLaunch.display(params));
        servers.put(name, s);
        return s;
    }

    List<Server> servers() {
        return List.copyOf(servers.values());
    }

    List<RoutedCall> log() {
        synchronized (log) {
            return List.copyOf(log);
        }
    }

    Map<String, Route> routes() {
        return Collections.unmodifiableMap(routes);
    }

    @Override
    public List<McpSchema.Tool> tools() {
        return List.copyOf(catalog);
    }

    @Override
    public String serverOf(String toolName) {
        Route r = routes.get(toolName);
        return r == null ? "?" : r.server();
    }

    /** The router. */
    @Override
    public McpSchema.CallToolResult call(String toolName, Map<String, Object> args) {
        return route("agent", toolName, args);
    }

    private McpSchema.CallToolResult route(String origin, String toolName, Map<String, Object> args) {
        Route r = routes.get(toolName);
        if (r == null) {
            log.add(new RoutedCall(log.size() + 1, origin, "?", toolName, args, false, 0));
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Ошибка маршрутизации: инструмента «" + toolName + "» нет ни на одном сервере. Доступны: "
                            + String.join(", ", routes.keySet()))
                    .isError(true).build();
        }
        long t0 = System.nanoTime();
        McpSchema.CallToolResult res;
        try {
            res = servers.get(r.server()).client().callTool(new McpSchema.CallToolRequest(r.tool(), args));
        } catch (Exception e) {
            res = McpSchema.CallToolResult.builder().addTextContent("Ошибка вызова сервера " + r.server() + ": " + e.getMessage())
                    .isError(true).build();
        }
        log.add(new RoutedCall(log.size() + 1, origin, r.server(), r.tool(), args, !Boolean.TRUE.equals(res.isError()),
                (System.nanoTime() - t0) / 1_000_000));
        return res;
    }

    /** Direct call by server + original tool name (for the application itself, bypassing the LLM). */
    McpSchema.CallToolResult callDirect(String server, String tool, Map<String, Object> args) {
        return route("app", server + SEP + tool, args);
    }

    @Override
    public void close() {
        for (Server s : servers.values()) {
            try {
                s.client().closeGracefully();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
