package ru.lemanapro.aiadventchallenge.week7;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;
import ru.lemanapro.aiadventchallenge.week4.McpLaunch;
import ru.lemanapro.aiadventchallenge.week4.McpToolAgent;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Day 33: ассистент поддержки TaskFlow. Вопрос клиента + номер тикета →
 *   1. MCP: get_ticket(ticket_id) у CrmMcpServer (support/crm.json) → тикет, события, карточка пользователя;
 *   2. RAG: SupportKnowledge (support/kb: FAQ + документация) по вопросу + теме, описанию и событиям тикета;
 *   3. LLM (общий LlmClient): ответ «Причина / Что сделать» с учётом данных тикета и ссылками [n].
 */
public final class SupportAssistant implements AutoCloseable {

    static final String SYSTEM_PROMPT = """
            Ты — ассистент службы поддержки сервиса TaskFlow. Отвечай клиенту по-русски, вежливо и по делу.
            Используй ТОЛЬКО:
            - данные тикета и пользователя из CRM (события, статус аккаунта, тариф, способ входа) — чтобы понять, что именно случилось у этого клиента;
            - разделы базы знаний [1]..[N] — чтобы объяснить причину и шаги решения. Ставь ссылки [n] после утверждений из базы знаний.
            Структура ответа:
            1) Причина — коротко, с опорой на конкретные данные тикета (что видно в событиях / карточке пользователя).
            2) Что сделать — пронумерованные шаги для клиента.
            Если данных недостаточно — скажи, что уточнить. Не выдумывай функции и настройки, которых нет в базе знаний.""";

    public record Answer(String question, String ticketId, String ticketJson, List<SupportKnowledge.Hit> sources,
                         String answer, long millis, String error) {
    }

    private final McpSyncClient crm;
    private final SupportKnowledge knowledge;
    private final LlmClient.Config cfg;
    private final HttpClient http = LlmClient.newHttpClient();

    public SupportAssistant(Path crmFile, Path kbDir, LlmClient.Config cfg) throws Exception {
        this.knowledge = new SupportKnowledge(kbDir);
        this.cfg = cfg;
        this.crm = McpClient.sync(new StdioClientTransport(
                        McpLaunch.javaServer(CrmMcpServer.class, crmFile.toAbsolutePath().toString()), McpJsonDefaults.getMapper()))
                .clientInfo(new McpSchema.Implementation("support-assistant", "1.0.0"))
                .initializationTimeout(Duration.ofSeconds(60))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        crm.initialize();
    }

    public SupportKnowledge knowledge() {
        return knowledge;
    }

    public boolean hasLlm() {
        return cfg != null;
    }

    public String model() {
        return cfg == null ? "—" : cfg.model();
    }

    public List<String> tools() {
        return crm.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    public McpSchema.CallToolResult callCrm(String tool, Map<String, Object> args) {
        return crm.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    public Answer ask(String ticketId, String question) {
        long t0 = System.nanoTime();
        String ticketJson = "";
        String searchQuery = question;
        if (ticketId != null && !ticketId.isBlank()) {
            McpSchema.CallToolResult r = callCrm("get_ticket", Map.of("ticket_id", ticketId));
            ticketJson = McpToolAgent.textOf(r);
            if (Boolean.TRUE.equals(r.isError())) {
                return new Answer(question, ticketId, ticketJson, List.of(), "", ms(t0), ticketJson);
            }
            if (r.structuredContent() instanceof Map<?, ?> m && m.get("ticket") instanceof Map<?, ?> t) {
                searchQuery += " " + t.get("subject") + " " + t.get("description") + " " + t.get("events");
            }
            if (r.structuredContent() instanceof Map<?, ?> m && m.get("user") instanceof Map<?, ?> u) {
                searchQuery += " " + u.get("authMethod") + " " + u.get("accountStatus") + (Boolean.TRUE.equals(u.get("twoFactor")) ? " 2FA код" : "");
            }
        }
        List<SupportKnowledge.Hit> hits = knowledge.search(searchQuery, 3);
        if (cfg == null) {
            return new Answer(question, ticketId, ticketJson, hits, "", ms(t0), "LLM не настроен: нет LLM_API_KEY (env или .env)");
        }
        StringBuilder user = new StringBuilder();
        user.append("Данные из CRM (MCP get_ticket):\n").append(ticketJson.isBlank() ? "— тикет не указан\n" : ticketJson).append("\n\n");
        user.append("База знаний:\n");
        for (int i = 0; i < hits.size(); i++) {
            SupportKnowledge.Section s = hits.get(i).section();
            user.append("[").append(i + 1).append("] ").append(s.ref()).append("\n").append(s.text()).append("\n\n");
        }
        user.append("Вопрос клиента: ").append(question);
        try {
            String text = LlmClient.content(LlmClient.send(http, cfg, List.of(
                    LlmClient.message("system", SYSTEM_PROMPT), LlmClient.message("user", user.toString())), null, null, 0.2));
            text = text.replaceAll("(?s)<think>.*?</think>", "").strip();
            return new Answer(question, ticketId, ticketJson, hits, text, ms(t0), null);
        } catch (LlmClient.RequestException e) {
            return new Answer(question, ticketId, ticketJson, hits, "", ms(t0), "LLM: HTTP " + e.status() + " " + e.body());
        } catch (Exception e) {
            return new Answer(question, ticketId, ticketJson, hits, "", ms(t0), "LLM: " + e.getMessage());
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Override
    public void close() {
        try {
            crm.closeGracefully();
        } catch (Exception ignored) {
            // сервер уже завершился
        }
    }
}
