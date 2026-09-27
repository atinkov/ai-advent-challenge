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
 * Week 4 shared agent: an LLM whose tools come from an MCP server, not from Java code in the agent
 * (extracted from day 17's Task17 so day 18+ can reuse it).
 *
 * Turns the server's tools/list into the OpenAI "tools" array (inputSchema passed through as
 * "parameters") and runs a tool loop: LLM tool_calls -> MCP tools/call -> "tool" message -> next LLM
 * call, until a plain answer (at most MAX_TOOL_STEPS tool rounds). Every ask() starts from a fresh
 * dialogue (system prompt + question), so a long-running caller doesn't accumulate history.
 *
 * Tool-calling mode ("auto" | "native" | "prompt"): auto = native function calling, switching to the
 * text protocol <tool_call>{"name","arguments"}</tool_call> if the LLM server rejects "tools" with a 4xx;
 * a <tool_call> block in plain content (Qwen without a tool parser) is executed in any mode;
 * <think> blocks are stripped.
 */
final class McpToolAgent implements AutoCloseable {

    static final int MAX_TOOL_STEPS = 6;

    record ToolCallTrace(String tool, String argumentsJson, boolean isError, String resultPreview, long millis) {
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
    private final McpSyncClient mcp;
    private final String basePrompt;
    private final List<McpSchema.Tool> mcpTools;
    private final List<Map<String, Object>> openAiTools = new ArrayList<>();
    private final boolean autoMode;
    private Mode mode;

    /**
     * @param modeSetting "auto" | "native" | "prompt"
     * @param basePrompt  task-specific system prompt (tool descriptions are added automatically in prompt mode)
     */
    McpToolAgent(LlmClient.Config cfg, McpSyncClient mcp, String modeSetting, String basePrompt) {
        this.cfg = cfg;
        this.mcp = mcp;
        this.basePrompt = basePrompt;
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

    McpSyncClient mcp() {
        return mcp;
    }

    private String systemPrompt() {
        if (mode == Mode.NATIVE) {
            return basePrompt;
        }
        StringBuilder sb = new StringBuilder(basePrompt).append("""


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

            return new AgentAnswer(content, calls, requests, mode.name().toLowerCase());
        }
        return new AgentAnswer("(агент остановлен: превышен лимит вызовов инструментов " + MAX_TOOL_STEPS + ")",
                calls, requests, mode.name().toLowerCase());
    }

    /** trace for reports + the full text that goes back to the model */
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

    // ---- helpers

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
