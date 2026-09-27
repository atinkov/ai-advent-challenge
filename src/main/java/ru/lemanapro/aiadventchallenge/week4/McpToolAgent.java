package ru.lemanapro.aiadventchallenge.week4;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Week 4 shared agent: an LLM whose tools come from MCP, not from Java code in the agent
 * (extracted from day 17's Task17; used by days 17-20).
 *
 * Tools come from a {@link ToolRouter}: either one MCP server (the McpSyncClient constructor, days 17-19)
 * or several at once through {@link McpRegistry} (day 20), which namespaces tool names and routes each
 * call to the server that owns the tool. The agent turns the router's tool list into the OpenAI "tools"
 * array (inputSchema passed through as "parameters") and runs a tool loop: LLM tool_calls -> router ->
 * "tool" message -> next LLM call, until a plain answer (at most maxToolSteps tool rounds).
 *
 * ask(question) starts from a fresh dialogue (system prompt + question), so a long-running caller doesn't
 * accumulate history. conversation() keeps one dialogue across several asks (multi-turn flows: the model
 * sees its earlier tool calls and results, e.g. "cancel the reminder you just created").
 *
 * Tool-calling mode ("auto" | "native" | "prompt"): auto = native function calling, switching to the
 * text protocol <tool_call>{"name","arguments"}</tool_call> if the LLM server rejects "tools" with a 4xx;
 * a <tool_call> block in plain content (Qwen without a tool parser) is executed in any mode;
 * <think> blocks are stripped.
 */
final class McpToolAgent implements AutoCloseable {

    static final int MAX_TOOL_STEPS = 6;

    /** Where tools come from and where calls go. */
    interface ToolRouter extends AutoCloseable {
        List<McpSchema.Tool> tools();

        McpSchema.CallToolResult call(String toolName, Map<String, Object> args);

        /** Which server a tool name is routed to (for traces); "" if unknown. */
        default String serverOf(String toolName) {
            return "";
        }

        @Override
        void close();
    }

    record ToolCallTrace(String tool, String argumentsJson, boolean isError, String resultPreview, long millis,
                         String server) {
    }

    record AgentAnswer(String text, List<ToolCallTrace> calls, int llmRequests, String mode) {
    }

    enum Mode { NATIVE, PROMPT }

    private static final Pattern TOOL_CALL_TAG = Pattern.compile("<tool_call>\\s*(\\{.*?})\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final LlmClient.Config cfg;
    private final HttpClient http = LlmClient.newHttpClient();
    private final ToolRouter router;
    private final String basePrompt;
    private final List<McpSchema.Tool> tools;
    private final List<Map<String, Object>> openAiTools = new ArrayList<>();
    private final boolean autoMode;
    private Mode mode;
    private int maxToolSteps = MAX_TOOL_STEPS;

    /** One MCP server (days 17-19). */
    McpToolAgent(LlmClient.Config cfg, McpSyncClient mcp, String modeSetting, String basePrompt) {
        this(cfg, singleServer(mcp), modeSetting, basePrompt);
    }

    /**
     * @param modeSetting "auto" | "native" | "prompt"
     * @param basePrompt  task-specific system prompt (tool descriptions are added automatically in prompt mode)
     */
    McpToolAgent(LlmClient.Config cfg, ToolRouter router, String modeSetting, String basePrompt) {
        this.cfg = cfg;
        this.router = router;
        this.basePrompt = basePrompt;
        this.tools = router.tools();
        // MCP tool definition -> OpenAI function definition: the inputSchema is passed through as-is
        for (McpSchema.Tool t : tools) {
            openAiTools.add(Map.of("type", "function", "function", Map.of(
                    "name", t.name(),
                    "description", t.description() == null ? "" : t.description(),
                    "parameters", t.inputSchema())));
        }
        this.autoMode = "auto".equalsIgnoreCase(modeSetting);
        this.mode = "prompt".equalsIgnoreCase(modeSetting) ? Mode.PROMPT : Mode.NATIVE;
    }

    McpToolAgent maxToolSteps(int n) {
        this.maxToolSteps = n;
        return this;
    }

    List<McpSchema.Tool> tools() {
        return tools;
    }

    private String systemPrompt() {
        if (mode == Mode.NATIVE) {
            return basePrompt;
        }
        StringBuilder sb = new StringBuilder(basePrompt).append("""


                Доступные инструменты (JSON Schema параметров):
                """);
        for (McpSchema.Tool t : tools) {
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

    /** One-shot question in a fresh dialogue. */
    AgentAnswer ask(String question) throws Exception {
        return conversation().ask(question);
    }

    /** A dialogue that keeps its history (including tool calls and results) across asks. */
    Conversation conversation() {
        return new Conversation();
    }

    final class Conversation {
        private final List<Map<String, Object>> messages = new ArrayList<>();

        private Conversation() {
            messages.add(msg("system", systemPrompt()));
        }

        AgentAnswer ask(String question) throws Exception {
            messages.add(msg("user", question));
            return run(messages);
        }

        int size() {
            return messages.size();
        }
    }

    private AgentAnswer run(List<Map<String, Object>> messages) throws Exception {
        List<ToolCallTrace> calls = new ArrayList<>();
        int requests = 0;

        for (int step = 0; step <= maxToolSteps; step++) {
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
                // assistant turn with tool_calls must be echoed back before the tool results
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
                String argsJson = argsNode.isTextual() ? argsNode.asText()
                        : LlmClient.toJson(argsNode.isMissingNode() ? Map.of() : argsNode);
                Executed t = execute(call.path("name").asText(), argsJson);
                calls.add(t.trace());
                messages.add(msg("assistant", m.group(0)));
                messages.add(msg("user", "Результат инструмента " + t.trace().tool() + (t.trace().isError() ? " (ОШИБКА)" : "")
                        + ":\n" + t.fullResult() + "\n\nПродолжай: вызови ещё инструмент или дай итоговый ответ."));
                continue;
            }

            messages.add(msg("assistant", content));
            return new AgentAnswer(content, calls, requests, mode.name().toLowerCase());
        }
        String stopped = "(агент остановлен: превышен лимит вызовов инструментов " + maxToolSteps + ")";
        messages.add(msg("assistant", stopped));
        return new AgentAnswer(stopped, calls, requests, mode.name().toLowerCase());
    }

    /** trace for reports + the full text that goes back to the model */
    private record Executed(ToolCallTrace trace, String fullResult) {
    }

    /** The actual MCP call through the router: tools/call on the owning server, whatever the LLM asked for. */
    private Executed execute(String name, String argsJson) {
        long t0 = System.nanoTime();
        Map<String, Object> args;
        try {
            JsonNode parsed = LlmClient.parseJson(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            args = parsed.isObject() ? JSON.convertValue(parsed, MAP_TYPE) : Map.of();
        } catch (Exception e) {
            args = Map.of();
        }
        String server = router.serverOf(name);
        System.out.println("   [MCP" + (server.isEmpty() ? "" : " " + server) + "] → tools/call " + name + " " + argsJson);
        McpSchema.CallToolResult r;
        try {
            r = router.call(name, args);
        } catch (Exception e) {
            r = McpSchema.CallToolResult.builder().addTextContent("Ошибка вызова MCP: " + e.getMessage()).isError(true).build();
        }
        String text = textOf(r);
        boolean err = Boolean.TRUE.equals(r.isError());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("   [MCP" + (server.isEmpty() ? "" : " " + server) + "] ← " + (err ? "ошибка" : "ok") + ", "
                + text.length() + " символов, " + ms + " мс");
        return new Executed(new ToolCallTrace(name, argsJson, err, preview(text, 1500), ms, server), text);
    }

    @Override
    public void close() {
        router.close();
    }

    // ---- helpers

    private static ToolRouter singleServer(McpSyncClient mcp) {
        return new ToolRouter() {
            private final List<McpSchema.Tool> tools = mcp.listTools().tools();

            @Override
            public List<McpSchema.Tool> tools() {
                return tools;
            }

            @Override
            public McpSchema.CallToolResult call(String toolName, Map<String, Object> args) {
                return mcp.callTool(new McpSchema.CallToolRequest(toolName, args));
            }

            @Override
            public void close() {
                mcp.closeGracefully();
            }
        };
    }

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

    static String preview(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
