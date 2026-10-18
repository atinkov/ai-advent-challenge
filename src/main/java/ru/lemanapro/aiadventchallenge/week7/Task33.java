package ru.lemanapro.aiadventchallenge.week7;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.lemanapro.aiadventchallenge.LlmClient;
import ru.lemanapro.aiadventchallenge.week4.McpToolAgent;

import java.awt.Desktop;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Day 33: ассистент поддержки пользователей — мини-сервис поддержки вымышленного продукта TaskFlow.
 *   CRM через MCP — CrmMcpServer над support/crm.json (пользователи + тикеты): get_ticket, get_user, list_tickets;
 *   RAG          — SupportKnowledge над support/kb (FAQ + документация);
 *   LLM          — SupportAssistant через общий LlmClient: ответ с учётом данных тикета.
 *
 *   mvn -q compile exec:java -Ptask33                                                   → веб-интерфейс http://localhost:8033
 *   mvn -q exec:java -Ptask33 -Dexec.args="ask T-1001 Почему не работает авторизация?" → ответ в консоли
 *   mvn -q exec:java -Ptask33 -Dexec.args="check"                                      → 3 проверки задания → task33-support-report.md
 * Env: TASK33_PORT (8033), TASK33_OPEN_BROWSER (1).
 */
public final class Task33 {

    static final Path CRM = Path.of("support/crm.json");
    static final Path KB = Path.of("support/kb");
    static final Path REPORT = Path.of("task33-support-report.md");
    static final String DEMO_QUESTION = "Почему не работает авторизация?";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "web" : args[0];
        LlmClient.Config cfg = LlmClient.env("LLM_API_KEY", null) == null ? null : LlmClient.fromEnv();
        try (SupportAssistant a = new SupportAssistant(CRM, KB, cfg)) {
            switch (mode) {
                case "ask" -> {
                    if (args.length < 3) {
                        System.out.println("Использование: ask <тикет> <вопрос>");
                        return;
                    }
                    print(a.ask(args[1], String.join(" ", List.of(args).subList(2, args.length))));
                }
                case "check" -> check(a);
                default -> web(a);
            }
        }
    }

    // ================================================================ веб-интерфейс

    static void web(SupportAssistant a) throws Exception {
        int port = Integer.parseInt(LlmClient.env("TASK33_PORT", "8033"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                if (path.equals("/")) {
                    try (InputStream in = Task33.class.getResourceAsStream("/task33/index.html")) {
                        send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
                    }
                } else if (path.equals("/api/tickets")) {
                    sendJson(ex, 200, a.callCrm("list_tickets", Map.of()).structuredContent());
                } else if (path.equals("/api/ask") && ex.getRequestMethod().equals("POST")) {
                    JsonNode body = JSON.readTree(ex.getRequestBody());
                    SupportAssistant.Answer r = a.ask(body.path("ticketId").asText(""), body.path("question").asText(DEMO_QUESTION));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("answer", r.answer());
                    out.put("error", r.error());
                    out.put("ticket", r.ticketJson());
                    out.put("millis", r.millis());
                    out.put("sources", r.sources().stream().map(h -> h.section().ref()).toList());
                    sendJson(ex, 200, out);
                } else {
                    send(ex, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                send(ex, 500, "text/plain; charset=utf-8", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        String url = "http://localhost:" + port;
        System.out.println("Ассистент поддержки TaskFlow: " + url + "  (MCP: " + CrmMcpServer.NAME + " " + a.tools()
                + ", база знаний: " + a.knowledge().sections().size() + " разделов, LLM: "
                + (a.hasLlm() ? a.model() : "не настроен — нет LLM_API_KEY") + ")");
        System.out.println("Ctrl+C — остановить.");
        try {
            if (!"0".equals(LlmClient.env("TASK33_OPEN_BROWSER", "1")) && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ignored) {
            // без браузера — откройте ссылку вручную
        }
        Thread.currentThread().join();
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws java.io.IOException {
        send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    // ================================================================ консоль

    static void print(SupportAssistant.Answer r) {
        System.out.println("Тикет: " + r.ticketId() + " | вопрос: " + r.question());
        System.out.println("[MCP get_ticket] " + (r.ticketJson().isBlank() ? "—" : r.ticketJson().replaceAll("\\s+", " ").substring(0, Math.min(160, r.ticketJson().replaceAll("\\s+", " ").length())) + "…"));
        System.out.println("[RAG] " + String.join("; ", r.sources().stream().map(h -> h.section().ref()).toList()));
        System.out.println();
        System.out.println(r.error() != null ? "⚠ " + r.error() : r.answer());
        System.out.println("\n(" + r.millis() / 1000.0 + " с)");
    }

    // ================================================================ проверка задания

    record Case(String ticket, Pattern mustMention, String expectedSection, String what) {
    }

    static void check(SupportAssistant a) throws Exception {
        List<String[]> rows = new ArrayList<>();
        StringBuilder answers = new StringBuilder();

        // 1. MCP: CRM подключена, данные тикета приходят из JSON
        List<String> tools = a.tools();
        JsonNode db = JSON.readTree(CRM.toFile());
        var r1001 = a.callCrm("get_ticket", Map.of("ticket_id", "T-1001"));
        String t = McpToolAgent.textOf(r1001);
        boolean mcpOk = tools.containsAll(List.of("get_ticket", "get_user", "list_tickets"))
                && t.contains("\"locked\"") && t.contains(db.path("users").get(0).path("email").asText());
        rows.add(new String[]{"1. CRM подключена через MCP", mcpOk ? "✅" : "❌",
                CrmMcpServer.NAME + ": " + tools + "; get_ticket(T-1001) вернул тикет и пользователя U-001 (accountStatus=locked) из support/crm.json"});

        // 2. RAG: база знаний из FAQ и документации, поиск находит нужный раздел
        long faq = a.knowledge().sections().stream().filter(x -> x.source().equals("faq.md")).count();
        long product = a.knowledge().sections().stream().filter(x -> x.source().equals("product.md")).count();
        var hits = a.knowledge().search("ошибка входа SSO SAML signature is invalid", 3);
        boolean ragOk = faq > 0 && product > 0 && !hits.isEmpty() && hits.getFirst().section().title().contains("SSO");
        rows.add(new String[]{"2. RAG по FAQ и документации", ragOk ? "✅" : "❌",
                "разделов: faq.md — " + faq + ", product.md — " + product + "; поиск «ошибка входа SSO…» → " + (hits.isEmpty() ? "—" : hits.getFirst().section().ref())});

        // 3. Один и тот же вопрос — разные ответы в зависимости от тикета
        List<Case> cases = List.of(
                new Case("T-1001", Pattern.compile("(?i)заблок|блокир|30 минут"), "заблокирован", "пароль: аккаунт заблокирован"),
                new Case("T-1002", Pattern.compile("(?i)сертификат"), "SSO", "SSO: истёк сертификат IdP"),
                new Case("T-1003", Pattern.compile("(?i)врем|синхрон|час"), "2FA", "2FA: расхождение времени"));
        boolean ctxOk = a.hasLlm();
        List<String> details = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Case c : cases) {
            SupportAssistant.Answer r = a.ask(c.ticket(), DEMO_QUESTION);
            boolean mentions = r.error() == null && c.mustMention().matcher(r.answer()).find();
            boolean source = r.sources().stream().anyMatch(h -> h.section().title().contains(c.expectedSection()));
            boolean cites = r.answer() != null && r.answer().matches("(?s).*\\[\\d].*");
            ctxOk &= mentions && source && cites;
            texts.add(r.answer());
            details.add(c.ticket() + " (" + c.what() + "): " + (mentions ? "✅" : "❌") + " ответ про `" + c.mustMention().pattern().replace("(?i)", "")
                    + "`, " + (source ? "✅" : "❌") + " RAG нашёл раздел «" + c.expectedSection() + "», " + (cites ? "✅" : "❌") + " есть ссылки [n]"
                    + (r.error() != null ? " — " + r.error() : ""));
            answers.append("### ").append(c.ticket()).append(" — «").append(DEMO_QUESTION).append("»\n\n")
                    .append("RAG: ").append(String.join("; ", r.sources().stream().map(h -> h.section().ref()).toList())).append("\n\n")
                    .append(r.error() != null ? "⚠ " + r.error() : r.answer()).append("\n\n");
        }
        ctxOk &= texts.stream().distinct().count() == texts.size();
        rows.add(new String[]{"3. Ответ учитывает данные тикета (один вопрос — три тикета)", ctxOk ? "✅" : "❌", String.join("<br>", details)});

        boolean done = rows.stream().allMatch(r -> r[1].equals("✅"));
        StringBuilder md = new StringBuilder("# День 33. Ассистент поддержки пользователей — проверка\n\n")
                .append("> ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(", модель `").append(a.model()).append("`\n\n")
                .append("## Вердикт: ").append(done ? "✅ задание выполнено" : "❌ задание не выполнено").append("\n\n")
                .append("| Проверка | Итог | Детали |\n|---|---|---|\n");
        rows.forEach(r -> md.append("| ").append(r[0]).append(" | ").append(r[1]).append(" | ").append(r[2]).append(" |\n"));
        md.append("\n## Ответы ассистента\n\n").append(answers);
        Files.writeString(REPORT, md.toString(), StandardCharsets.UTF_8);

        System.out.println(answers);
        System.out.println("══ Проверка задания ══");
        rows.forEach(r -> System.out.println(r[1] + " " + r[0] + "\n    " + r[2].replace("<br>", "\n    ")));
        System.out.println("\n" + (done ? "✅ ЗАДАНИЕ ВЫПОЛНЕНО" : "❌ ЗАДАНИЕ НЕ ВЫПОЛНЕНО") + " → " + REPORT);
    }
}
