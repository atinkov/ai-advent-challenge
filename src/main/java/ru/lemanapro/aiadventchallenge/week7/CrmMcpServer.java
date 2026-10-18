package ru.lemanapro.aiadventchallenge.week7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Day 33: MCP-сервер «CRM» поверх JSON-файла с пользователями и тикетами (support/crm.json). Работает по stdio,
 * stdout занят JSON-RPC. Инструменты (только чтение):
 *   get_ticket(ticket_id)        — тикет + данные пользователя, который его создал
 *   get_user(user_id | email)    — карточка пользователя
 *   list_tickets(status)         — список тикетов (для выбора в интерфейсе)
 * Файл перечитывается при каждом вызове — правки в crm.json видны без перезапуска.
 */
public final class CrmMcpServer {

    static final String NAME = "crm-mcp-server";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;

    CrmMcpServer(Path file) {
        this.file = file;
    }

    public static void main(String[] args) throws Exception {
        CrmMcpServer crm = new CrmMcpServer(Path.of(args.length > 0 ? args[0] : "support/crm.json").toAbsolutePath());
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
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(McpSchema.Tool.builder().name("get_ticket")
                                .description("Тикет поддержки по номеру (например T-1001): тема, описание, статус, приоритет, события + карточка пользователя (тариф, способ входа, статус аккаунта).")
                                .inputSchema(json, """
                                        {"type":"object","properties":{"ticket_id":{"type":"string","description":"Номер тикета, например T-1001"}},
                                         "required":["ticket_id"],"additionalProperties":false}""").build(),
                        (ex, req) -> crm.safe(() -> crm.getTicket(String.valueOf(req.arguments().get("ticket_id")))))
                .toolCall(McpSchema.Tool.builder().name("get_user")
                                .description("Карточка пользователя по id (U-001) или email: тариф, компания, способ входа, 2FA, статус аккаунта, блокировка.")
                                .inputSchema(json, """
                                        {"type":"object","properties":{"user_id":{"type":"string"},"email":{"type":"string"}},"additionalProperties":false}""").build(),
                        (ex, req) -> crm.safe(() -> crm.getUser(req.arguments())))
                .toolCall(McpSchema.Tool.builder().name("list_tickets")
                                .description("Список тикетов: номер, тема, статус, пользователь. Можно отфильтровать по статусу open/closed.")
                                .inputSchema(json, """
                                        {"type":"object","properties":{"status":{"type":"string","enum":["open","closed"]}},"additionalProperties":false}""").build(),
                        (ex, req) -> crm.safe(() -> crm.listTickets(req.arguments())))
                .build();
        stdinClosed.await();
        server.closeGracefully();
        System.exit(0);
    }

    // ---- tools

    McpSchema.CallToolResult getTicket(String id) throws Exception {
        JsonNode db = load();
        for (JsonNode t : db.path("tickets")) {
            if (t.path("id").asText().equalsIgnoreCase(id.strip())) {
                JsonNode user = findUser(db, t.path("userId").asText(), null);
                Map<String, Object> s = Map.of("ticket", plain(t), "user", user == null ? Map.of() : plain(user));
                return ok(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(s), s);
            }
        }
        return error("Тикет " + id + " не найден");
    }

    McpSchema.CallToolResult getUser(Map<String, Object> args) throws Exception {
        Object id = args == null ? null : args.get("user_id");
        Object email = args == null ? null : args.get("email");
        JsonNode user = findUser(load(), id == null ? null : id.toString(), email == null ? null : email.toString());
        if (user == null) return error("Пользователь не найден");
        return ok(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(user), plain(user));
    }

    McpSchema.CallToolResult listTickets(Map<String, Object> args) throws Exception {
        JsonNode db = load();
        Object status = args == null ? null : args.get("status");
        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode t : db.path("tickets")) {
            if (status != null && !t.path("status").asText().equals(status.toString())) continue;
            JsonNode u = findUser(db, t.path("userId").asText(), null);
            String userName = u == null ? "?" : u.path("name").asText();
            out.add(Map.of("id", t.path("id").asText(), "subject", t.path("subject").asText(),
                    "status", t.path("status").asText(), "priority", t.path("priority").asText(), "user", userName));
            text.append(t.path("id").asText()).append(" | ").append(t.path("status").asText()).append(" | ")
                    .append(userName).append(" | ").append(t.path("subject").asText()).append('\n');
        }
        return ok(text.toString(), Map.of("tickets", out));
    }

    // ---- plumbing

    /** structuredContent сериализует Jackson 3 из MCP SDK — отдаём ему обычные Map/List, а не узлы Jackson 2. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> plain(JsonNode node) {
        return JSON.convertValue(node, Map.class);
    }

    private JsonNode load() throws IOException {
        return JSON.readTree(file.toFile());
    }

    private static JsonNode findUser(JsonNode db, String id, String email) {
        for (JsonNode u : db.path("users")) {
            if (id != null && u.path("id").asText().equalsIgnoreCase(id.strip())) return u;
            if (email != null && u.path("email").asText().equalsIgnoreCase(email.strip())) return u;
        }
        return null;
    }

    interface Handler {
        McpSchema.CallToolResult run() throws Exception;
    }

    McpSchema.CallToolResult safe(Handler h) {
        try {
            return h.run();
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    private static McpSchema.CallToolResult ok(String text, Object structured) {
        return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(false).build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent("Ошибка: " + message).isError(true).build();
    }
}
