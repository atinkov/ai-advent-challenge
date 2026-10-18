package ru.lemanapro.aiadventchallenge.week7;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;
import ru.lemanapro.aiadventchallenge.week4.McpLaunch;
import ru.lemanapro.aiadventchallenge.week4.McpToolAgent;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 31: the developer assistant — answers questions about THIS project (the /help command).
 *
 * Two kinds of context are combined for every question:
 *   1. documentation — RAG over README.md, docs/ (architecture, data schemas, API descriptions) and AGENTS.md
 *      (ProjectDocs): numbered fragments [1]..[N] go into the prompt;
 *   2. the live repository — the project MCP server (ProjectMcpServer, a child process over stdio):
 *      the application ALWAYS calls git_current_branch itself and puts the branch into the prompt; the model
 *      may additionally call git_status / list_files / git_diff through MCP when the question is about the
 *      current state (what changed, which files exist) — the tool loop is the shared week4 McpToolAgent.
 *
 * The answer must cite fragments as [n]; citations are checked in code: only numbers of fragments that were
 * actually in the prompt count, and the sources shown to the user are printed from index metadata.
 * Without an LLM key the assistant still works for /branch, /files, /diff and shows the found fragments.
 */
public final class DevAssistant implements AutoCloseable {

    static final String SYSTEM_PROMPT = """
            Ты — ассистент разработчика проекта AIAdventChallenge (команда /help). Отвечаешь на вопросы о проекте:
            как он устроен, как запускать, где что лежит, форматы данных, API, соглашения команды.

            Источники:
            1. Фрагменты документации [1]..[N] в сообщении пользователя (README.md, docs/, схемы данных, описания API, AGENTS.md) — главный источник знаний о проекте.
            2. Состояние репозитория — MCP-сервер project-mcp-server. Текущая ветка уже дана в сообщении (получена через git_current_branch).
               Если вопрос о текущих изменениях, файлах или diff — вызови инструменты git_status, list_files или git_diff.

            Правила:
            - Факты о проекте бери только из фрагментов и результатов инструментов; не выдумывай классы, команды, пути, переменные окружения.
            - После утверждения, взятого из документации, ставь номер фрагмента в квадратных скобках: [1], [2].
            - Если ответа нет ни во фрагментах, ни в инструментах — так и скажи: «В документации проекта этого нет», и подскажи, где искать.
            - Если ответ зависит от ветки (что делается в текущей ветке, куда коммитить) — учитывай текущую ветку.
            - Отвечай по-русски, кратко и по делу; команды, пути и имена классов оформляй как `code`. Список источников в конце не пиши — его добавит приложение.""";

    public record BranchInfo(String text, String branch, String head, boolean detached, long millis) {
    }

    public record HelpAnswer(String question, BranchInfo branch, List<ProjectDocs.Fragment> fragments, String answer,
                             List<ProjectDocs.Fragment> cited, List<Integer> invalidCitations,
                             List<McpToolAgent.ToolCallTrace> toolCalls, int llmRequests, String toolMode, long millis,
                             String error) {
    }

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2}(?:\\s*[,;]\\s*\\d{1,2})*)]");

    private final Path repo;
    private final LlmClient.Config cfg;
    private final HttpClient http = LlmClient.newHttpClient();
    private final ProjectDocs docs;
    private final McpSyncClient mcp;
    private final McpSchema.InitializeResult init;
    private final ServerParameters serverParams;
    private McpToolAgent agent;

    public DevAssistant(Path repo, LlmClient.Config cfg) {
        this.repo = repo.toAbsolutePath().normalize();
        this.cfg = cfg;
        this.docs = new ProjectDocs(this.repo, http, cfg);
        this.serverParams = McpLaunch.javaServer(ProjectMcpServer.class, this.repo.toString());
        this.mcp = McpClient.sync(new StdioClientTransport(serverParams, McpJsonDefaults.getMapper()))
                .clientInfo(new McpSchema.Implementation("ai-advent-dev-assistant", "1.0.0"))
                .initializationTimeout(Duration.ofSeconds(90))
                .requestTimeout(Duration.ofMinutes(2))
                .build();
        this.init = mcp.initialize(); // MCP handshake = the assistant is connected to the project
    }

    public ProjectDocs docs() {
        return docs;
    }

    public boolean hasLlm() {
        return cfg != null;
    }

    public String model() {
        return cfg == null ? "—" : cfg.model();
    }

    public McpSchema.InitializeResult serverInit() {
        return init;
    }

    public String serverCommand() {
        return McpLaunch.display(serverParams);
    }

    public List<McpSchema.Tool> tools() {
        return mcp.listTools().tools();
    }

    // ---------------------------------------------------------------- MCP calls made by the application

    public McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
        return mcp.callTool(new McpSchema.CallToolRequest(tool, args == null ? Map.of() : args));
    }

    public String callText(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = call(tool, args);
        return McpToolAgent.textOf(r);
    }

    @SuppressWarnings("unchecked")
    public BranchInfo branch() {
        long t0 = System.nanoTime();
        McpSchema.CallToolResult r = call("git_current_branch", Map.of());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String text = McpToolAgent.textOf(r);
        if (Boolean.TRUE.equals(r.isError()) || !(r.structuredContent() instanceof Map<?, ?> m)) {
            return new BranchInfo(text, "", "", false, ms);
        }
        Map<String, Object> s = (Map<String, Object>) m;
        return new BranchInfo(text, String.valueOf(s.getOrDefault("branch", "")), String.valueOf(s.getOrDefault("head", "")),
                Boolean.TRUE.equals(s.get("detached")), ms);
    }

    // ---------------------------------------------------------------- /help <question>

    public HelpAnswer help(String question) {
        long t0 = System.nanoTime();
        BranchInfo branch = branch();
        List<ProjectDocs.Fragment> fragments;
        try {
            fragments = docs.retrieve(question);
        } catch (Exception e) {
            return new HelpAnswer(question, branch, List.of(), "", List.of(), List.of(), List.of(), 0, "", ms(t0),
                    "поиск по документации: " + e.getMessage());
        }
        if (cfg == null) {
            return new HelpAnswer(question, branch, fragments, "", List.of(), List.of(), List.of(), 0, "", ms(t0),
                    "LLM не настроен (нет LLM_API_KEY) — показаны только найденные фрагменты документации");
        }
        try {
            McpToolAgent.AgentAnswer a = agent().ask(userMessage(question, branch, fragments));
            Citations c = citations(a.text(), fragments);
            return new HelpAnswer(question, branch, fragments, a.text(), c.valid(), c.invalid(), a.calls(), a.llmRequests(),
                    a.mode(), ms(t0), null);
        } catch (LlmClient.RequestException e) {
            return new HelpAnswer(question, branch, fragments, "", List.of(), List.of(), List.of(), 0, "", ms(t0),
                    "LLM: HTTP " + e.status() + " " + clip(String.valueOf(e.body()).replaceAll("\\s+", " "), 200));
        } catch (Exception e) {
            return new HelpAnswer(question, branch, fragments, "", List.of(), List.of(), List.of(), 0, "", ms(t0),
                    "LLM: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private McpToolAgent agent() {
        if (agent == null) {
            agent = new McpToolAgent(cfg, mcp, LlmClient.env("TASK31_TOOL_MODE", "auto"), SYSTEM_PROMPT).maxToolSteps(4);
        }
        return agent;
    }

    static String userMessage(String question, BranchInfo branch, List<ProjectDocs.Fragment> fragments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Состояние репозитория (MCP git_current_branch):\n").append(branch.text().strip()).append("\n\n");
        if (fragments.isEmpty()) {
            sb.append("Фрагменты документации: по этому вопросу ничего не найдено.\n\n");
        } else {
            sb.append("Фрагменты документации проекта:\n\n");
            for (ProjectDocs.Fragment f : fragments) {
                sb.append("[").append(f.n()).append("] ").append(f.label()).append("\n").append(f.chunk().text().strip()).append("\n\n");
            }
        }
        sb.append("Вопрос разработчика: ").append(question);
        return sb.toString();
    }

    record Citations(List<ProjectDocs.Fragment> valid, List<Integer> invalid) {
    }

    /** [n] / [n, m] in the answer → fragments that really were in the prompt; anything else is reported as invalid. */
    static Citations citations(String answer, List<ProjectDocs.Fragment> fragments) {
        Set<Integer> seen = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(answer == null ? "" : answer);
        while (m.find()) {
            for (String n : m.group(1).split("[,;]")) seen.add(Integer.parseInt(n.strip()));
        }
        Map<Integer, ProjectDocs.Fragment> byN = new LinkedHashMap<>();
        fragments.forEach(f -> byN.put(f.n(), f));
        List<ProjectDocs.Fragment> valid = new ArrayList<>();
        List<Integer> invalid = new ArrayList<>();
        for (int n : seen) {
            if (byN.containsKey(n)) valid.add(byN.get(n));
            else invalid.add(n);
        }
        return new Citations(valid, invalid);
    }

    static String clip(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Override
    public void close() {
        try {
            mcp.closeGracefully();
        } catch (Exception ignored) {
            // server already gone
        }
    }
}
