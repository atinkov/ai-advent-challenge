package ru.lemanapro.aiadventchallenge.week7;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Day 31: stdio MCP server "project-mcp-server" — the developer assistant's live view of the repository.
 *
 * Read-only tools (the git CLI of one repo, GIT_OPTIONAL_LOCKS=0, every path/ref validated):
 *   git_current_branch()                               — current branch, HEAD, upstream, ahead/behind (the minimum of day 31)
 *   git_status()                                       — staged / modified / untracked files
 *   list_files(path, glob, limit, include_untracked)   — project files (git ls-files: .gitignore is respected)
 *   git_diff(path, staged, ref, stat_only, max_chars)  — what changed: --stat summary + the patch (truncated)
 *
 * Every tool returns text (for the LLM) + structuredContent (for the application). Errors become
 * isError=true results, the server never crashes. stdout is reserved for JSON-RPC.
 *
 * Start: java -cp <classpath> ru.lemanapro.aiadventchallenge.week7.ProjectMcpServer [repo]
 * (Task31 starts it as a child process through McpLaunch).
 */
public final class ProjectMcpServer {

    static final String NAME = "project-mcp-server";

    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/@{}~^-]{1,200}");
    private static final Pattern SAFE_PATH = Pattern.compile("[\\p{L}\\p{N}._/ *?\\[\\]{},+@-]{1,300}");
    private static final int DEFAULT_DIFF_CHARS = 12_000;

    private final Path repo;

    ProjectMcpServer(Path repo) {
        this.repo = repo;
    }

    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        ProjectMcpServer p = new ProjectMcpServer(repo);
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
                .serverInfo(NAME, "1.0.0")
                .instructions("Read-only доступ к рабочей копии проекта " + repo.getFileName()
                        + ": текущая ветка, статус, список файлов, diff.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(McpSchema.Tool.builder()
                                .name("git_current_branch")
                                .title("Текущая ветка")
                                .description("Текущая git-ветка рабочей копии: имя ветки (или detached HEAD), короткий хеш и "
                                        + "заголовок HEAD-коммита, upstream и на сколько коммитов ветка впереди/позади него.")
                                .inputSchema(json, """
                                        {"type":"object","properties":{},"additionalProperties":false}""")
                                .annotations(readOnly())
                                .build(),
                        (ex, req) -> p.safe(p::currentBranch))
                .toolCall(McpSchema.Tool.builder()
                                .name("git_status")
                                .title("Статус рабочей копии")
                                .description("Незакоммиченные изменения: файлы в индексе (staged), изменённые (modified), "
                                        + "удалённые и неотслеживаемые (untracked), плюс текущая ветка.")
                                .inputSchema(json, """
                                        {"type":"object","properties":{},"additionalProperties":false}""")
                                .annotations(readOnly())
                                .build(),
                        (ex, req) -> p.safe(p::status))
                .toolCall(McpSchema.Tool.builder()
                                .name("list_files")
                                .title("Файлы проекта")
                                .description("Список файлов проекта под git (.gitignore учитывается): можно ограничить папкой "
                                        + "и glob-маской, например path=src/main/java, glob=**/week7/*.java. Возвращает пути и "
                                        + "сводку по папкам.")
                                .inputSchema(json, """
                                        {"type":"object",
                                         "properties":{
                                           "path":{"type":"string","description":"Папка или файл относительно корня репозитория (по умолчанию весь проект)"},
                                           "glob":{"type":"string","description":"glob-маска по относительному пути, например **/*.md или docs/**"},
                                           "limit":{"type":"integer","minimum":1,"maximum":500,"default":200,"description":"Максимум путей в ответе"},
                                           "include_untracked":{"type":"boolean","default":true,"description":"Добавить новые файлы, ещё не добавленные в git (кроме игнорируемых)"}
                                         },
                                         "additionalProperties":false}""")
                                .annotations(readOnly())
                                .build(),
                        (ex, req) -> p.safe(() -> p.listFiles(req.arguments())))
                .toolCall(McpSchema.Tool.builder()
                                .name("git_diff")
                                .title("Изменения (diff)")
                                .description("Что изменено: сводка --stat и сам патч. По умолчанию — незакоммиченные изменения "
                                        + "рабочей копии; staged=true — только индекс; ref — сравнить с веткой/коммитом "
                                        + "(например main или HEAD~1). Патч обрезается до max_chars.")
                                .inputSchema(json, """
                                        {"type":"object",
                                         "properties":{
                                           "path":{"type":"string","description":"Ограничить файлом или папкой"},
                                           "staged":{"type":"boolean","default":false,"description":"Только изменения в индексе (git diff --cached)"},
                                           "ref":{"type":"string","description":"С чем сравнивать рабочую копию: ветка, тег или коммит"},
                                           "stat_only":{"type":"boolean","default":false,"description":"Только сводка по файлам, без патча"},
                                           "max_chars":{"type":"integer","minimum":500,"maximum":60000,"default":12000,"description":"Лимит длины патча"}
                                         },
                                         "additionalProperties":false}""")
                                .annotations(readOnly())
                                .build(),
                        (ex, req) -> p.safe(() -> p.diff(req.arguments())))
                .build();

        stdinClosed.await();
        server.closeGracefully();
        System.exit(0);
    }

    private static McpSchema.ToolAnnotations readOnly() {
        return new McpSchema.ToolAnnotations(null, true, false, true, false, null);
    }

    // ---------------------------------------------------------------- tools

    McpSchema.CallToolResult currentBranch() throws Exception {
        String branch = git(List.of("branch", "--show-current")).strip();
        boolean detached = branch.isEmpty();
        String head = git(List.of("log", "-1", "--format=%h\u001f%s\u001f%ad", "--date=iso-strict")).strip();
        String[] h = head.split("\u001f", -1);
        String upstream = "";
        int ahead = 0, behind = 0;
        if (!detached) {
            try {
                upstream = git(List.of("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")).strip();
                String[] ab = git(List.of("rev-list", "--left-right", "--count", "HEAD..." + upstream)).strip().split("\\s+");
                ahead = Integer.parseInt(ab[0]);
                behind = Integer.parseInt(ab[1]);
            } catch (Exception noUpstream) {
                upstream = "";
            }
        }
        StringBuilder text = new StringBuilder();
        text.append("Текущая ветка: ").append(detached ? "(detached HEAD)" : branch).append('\n');
        if (h.length >= 3) {
            text.append("HEAD: ").append(h[0]).append(" «").append(h[1]).append("» (").append(h[2]).append(")\n");
        }
        text.append("Upstream: ").append(upstream.isEmpty() ? "нет" : upstream + " (впереди на " + ahead + ", позади на " + behind + ")");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("branch", detached ? "" : branch);
        s.put("detached", detached);
        s.put("head", h.length >= 1 ? h[0] : "");
        s.put("headSubject", h.length >= 2 ? h[1] : "");
        s.put("upstream", upstream);
        s.put("ahead", ahead);
        s.put("behind", behind);
        s.put("repository", repo.getFileName().toString());
        return ok(text.toString(), s);
    }

    McpSchema.CallToolResult status() throws Exception {
        String out = git(List.of("status", "--porcelain=v1", "-b", "--untracked-files=all"));
        String branchLine = "";
        List<String> staged = new ArrayList<>(), modified = new ArrayList<>(), deleted = new ArrayList<>(), untracked = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) continue;
            if (line.startsWith("## ")) {
                branchLine = line.substring(3);
                continue;
            }
            if (line.length() < 4) continue;
            char x = line.charAt(0), y = line.charAt(1);
            String file = line.substring(3);
            if (x == '?' && y == '?') {
                untracked.add(file);
                continue;
            }
            if (x != ' ') staged.add(x + " " + file);
            if (y == 'M') modified.add(file);
            if (y == 'D') deleted.add(file);
        }
        StringBuilder text = new StringBuilder("Ветка: ").append(branchLine).append('\n');
        appendList(text, "В индексе (staged)", staged);
        appendList(text, "Изменены, не в индексе", modified);
        appendList(text, "Удалены, не в индексе", deleted);
        appendList(text, "Неотслеживаемые", untracked);
        if (staged.isEmpty() && modified.isEmpty() && deleted.isEmpty() && untracked.isEmpty()) {
            text.append("Рабочая копия чистая.\n");
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("branchLine", branchLine);
        s.put("staged", staged);
        s.put("modified", modified);
        s.put("deleted", deleted);
        s.put("untracked", untracked);
        s.put("clean", staged.isEmpty() && modified.isEmpty() && deleted.isEmpty() && untracked.isEmpty());
        return ok(text.toString(), s);
    }

    McpSchema.CallToolResult listFiles(Map<String, Object> args) throws Exception {
        String path = checkPath(strArg(args, "path"));
        String glob = checkPath(strArg(args, "glob"));
        int limit = Math.max(1, Math.min(500, intArg(args, "limit", 200)));
        boolean untracked = boolArg(args, "include_untracked", true);

        List<String> cmd = new ArrayList<>(List.of("ls-files", "--cached"));
        if (untracked) cmd.addAll(List.of("--others", "--exclude-standard"));
        cmd.add("--");
        if (path != null) cmd.add(path);
        PathMatcher matcher = glob == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<String> all = new ArrayList<>();
        for (String f : git(cmd).split("\n")) {
            if (f.isBlank()) continue;
            if (matcher != null && !matcher.matches(Path.of(f)) && !matcher.matches(Path.of(f).getFileName())) continue;
            if (!all.contains(f)) all.add(f);
        }
        all.sort(String::compareTo);
        Map<String, Integer> byDir = new TreeMap<>();
        for (String f : all) {
            int slash = f.lastIndexOf('/');
            byDir.merge(slash < 0 ? "." : f.substring(0, slash), 1, Integer::sum);
        }
        List<String> shown = all.subList(0, Math.min(limit, all.size()));
        StringBuilder text = new StringBuilder("Файлов: ").append(all.size())
                .append(path != null ? " в " + path : "").append(glob != null ? " по маске " + glob : "")
                .append(all.size() > shown.size() ? " (показаны первые " + shown.size() + ")" : "").append('\n');
        shown.forEach(f -> text.append(f).append('\n'));
        text.append("\nПо папкам:\n");
        byDir.forEach((d, n) -> text.append("  ").append(d).append(": ").append(n).append('\n'));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", all.size());
        s.put("files", shown);
        s.put("truncated", all.size() > shown.size());
        s.put("byDirectory", byDir);
        return ok(text.toString(), s);
    }

    McpSchema.CallToolResult diff(Map<String, Object> args) throws Exception {
        String path = checkPath(strArg(args, "path"));
        boolean staged = boolArg(args, "staged", false);
        String ref = strArg(args, "ref");
        boolean statOnly = boolArg(args, "stat_only", false);
        int maxChars = Math.max(500, Math.min(60_000, intArg(args, "max_chars", DEFAULT_DIFF_CHARS)));

        List<String> base = new ArrayList<>(List.of("diff", "--no-color", "--no-ext-diff"));
        if (staged) base.add("--cached");
        if (ref != null) base.add(checkRef(ref));
        List<String> tail = new ArrayList<>(List.of("--"));
        if (path != null) tail.add(path);

        List<String> numstatCmd = new ArrayList<>(base);
        numstatCmd.add("--numstat");
        numstatCmd.addAll(tail);
        List<Map<String, Object>> files = new ArrayList<>();
        int add = 0, del = 0;
        for (String line : git(numstatCmd).split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 3) continue;
            boolean bin = p[0].equals("-");
            int a = bin ? 0 : Integer.parseInt(p[0]), d = bin ? 0 : Integer.parseInt(p[1]);
            add += a;
            del += d;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", p[2]);
            f.put("added", a);
            f.put("deleted", d);
            f.put("binary", bin);
            files.add(f);
        }
        String what = (staged ? "staged-изменения" : "незакоммиченные изменения") + (ref != null ? " относительно " + ref : "")
                + (path != null ? " в " + path : "");
        StringBuilder text = new StringBuilder("Diff: ").append(what).append(" — файлов ").append(files.size())
                .append(", +").append(add).append(" −").append(del).append('\n');
        for (Map<String, Object> f : files) {
            text.append("  ").append(f.get("path")).append("  +").append(f.get("added")).append(" −").append(f.get("deleted"))
                    .append(Boolean.TRUE.equals(f.get("binary")) ? " (binary)" : "").append('\n');
        }
        boolean truncated = false;
        if (!statOnly && !files.isEmpty()) {
            List<String> patchCmd = new ArrayList<>(base);
            patchCmd.add("--unified=2");
            patchCmd.addAll(tail);
            String patch = git(patchCmd);
            truncated = patch.length() > maxChars;
            text.append("\n").append(truncated ? patch.substring(0, maxChars) + "\n… (патч обрезан: " + patch.length() + " символов, лимит " + maxChars + ")" : patch);
        }
        if (files.isEmpty()) {
            text.append("Изменений нет. (Новые неотслеживаемые файлы в diff не входят — см. git_status.)\n");
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("files", files);
        s.put("totalAdded", add);
        s.put("totalDeleted", del);
        s.put("staged", staged);
        s.put("ref", ref == null ? "" : ref);
        s.put("truncated", truncated);
        return ok(text.toString(), s);
    }

    // ---------------------------------------------------------------- plumbing

    interface Handler {
        McpSchema.CallToolResult run() throws Exception;
    }

    McpSchema.CallToolResult safe(Handler h) {
        try {
            return h.run();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder().addTextContent("Ошибка: " + e.getMessage()).isError(true).build();
        }
    }

    String git(List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString(), "--no-pager", "-c", "core.quotepath=off"));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0"); // read-only: never take the index lock
        Process proc = pb.start();
        proc.getOutputStream().close();
        byte[] out = proc.getInputStream().readAllBytes();
        String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IllegalStateException("git не ответил за 30 с");
        }
        if (proc.exitValue() != 0) {
            throw new IllegalArgumentException("git " + args.getFirst() + ": " + (err.isEmpty() ? "код " + proc.exitValue() : err));
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static void appendList(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) return;
        sb.append(title).append(" (").append(items.size()).append("):\n");
        items.stream().limit(100).forEach(i -> sb.append("  ").append(i).append('\n'));
        if (items.size() > 100) sb.append("  … ещё ").append(items.size() - 100).append('\n');
    }

    static String checkRef(String ref) {
        if (!SAFE_REF.matcher(ref).matches() || ref.startsWith("-")) {
            throw new IllegalArgumentException("Недопустимый ref: " + ref);
        }
        return ref;
    }

    /** Relative paths / globs inside the repository only: no absolute paths, no "..", no options. */
    static String checkPath(String p) {
        if (p == null) return null;
        String n = p.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        if (n.isEmpty() || n.equals(".")) return null;
        if (!SAFE_PATH.matcher(n).matches() || n.startsWith("-") || n.startsWith("/") || n.contains("..") || n.contains(":")) {
            throw new IllegalArgumentException("Недопустимый путь: " + p + " (нужен относительный путь внутри репозитория)");
        }
        return n;
    }

    private static McpSchema.CallToolResult ok(String text, Object structured) {
        return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(false).build();
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
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v).strip());
    }
}
