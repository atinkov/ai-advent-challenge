package ru.lemanapro.aiadventchallenge.week7;

import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Day 34: «рабочая копия» файлов проекта для ассистента — чтение, поиск, анализ ссылок, история git и изменения.
 *
 * Песочница: все пути относительные от корня проекта; выход за корень, .git/.idea/target/node_modules и .env запрещены.
 * Изменения копятся в оверлее (path → исходный текст + текущий текст): чтение и поиск видят уже изменённые файлы,
 * pendingDiff() отдаёт unified diff всех изменений, который принимает `git apply`.
 *   dry-run (по умолчанию) — диск не трогаем, результат = patch-файл;
 *   apply                 — каждое изменение сразу записывается на диск (diff всё равно считается от исходника).
 *
 * Один класс используется и MCP-сервером (ProjectFilesMcpServer), и проверками дня (Task34) — «эталон» проверок
 * считается тем же кодом, что видит ассистент.
 */
public final class ProjectFiles {

    static final Set<String> SKIP_DIRS = Set.of(".git", ".idea", ".omo", ".mvn", "target", "build", "node_modules", ".gradle", ".vscode");
    static final Set<String> TEXT_EXT = Set.of("md", "txt", "java", "kt", "scala", "json", "yml", "yaml", "xml", "properties",
            "sql", "csv", "html", "css", "js", "ts", "sh", "conf", "toml", "gitignore", "dockerfile", "mermaid");
    static final int MAX_READ_LINES = 250;
    static final int MAX_READ_CHARS = 14_000;
    static final int MAX_FILE_BYTES = 2_000_000;
    private static final Pattern MD_LINK = Pattern.compile("(?<!!)\\[([^\\]]*)]\\((<[^>]+>|[^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*]\\((<[^>]+>|[^)\\s]+)\\)");

    private final Path root;
    private final boolean apply;
    /** relative path → change; insertion order = order of the agent's edits */
    private final Map<String, Change> changes = new LinkedHashMap<>();

    record Change(String original, String current) {
        boolean created() {
            return original == null;
        }
    }

    public record FileInfo(String path, long bytes, int lines, boolean text) {
    }

    public record Match(String path, int line, String text) {
    }

    public record SearchResult(List<Match> matches, Map<String, Integer> perFile, int total, boolean truncated) {
    }

    public record BrokenLink(String file, int line, String target, String reason) {
    }

    public record LinkReport(int filesChecked, int linksChecked, List<BrokenLink> broken, List<String> orphans) {
    }

    public record Commit(String hash, String date, String author, String subject, List<String> files) {
    }

    public ProjectFiles(Path root, boolean apply) {
        this.root = root.toAbsolutePath().normalize();
        this.apply = apply;
    }

    public Path root() {
        return root;
    }

    public boolean applyMode() {
        return apply;
    }

    /** TASK34_ROOT > ../lmru--qacc--performance-qa-course (рядом с проектом) > ~/Lemana/lmru--qacc--performance-qa-course. */
    public static Path resolveRoot(String explicit) {
        String configured = explicit != null && !explicit.isBlank() ? explicit : LlmClient.env("TASK34_ROOT", "");
        if (!configured.isBlank()) {
            return Path.of(configured.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
        }
        Path sibling = Path.of("..", "lmru--qacc--performance-qa-course").toAbsolutePath().normalize();
        if (Files.isDirectory(sibling)) return sibling;
        return Path.of(System.getProperty("user.home"), "Lemana", "lmru--qacc--performance-qa-course");
    }

    // ================================================================ paths

    /** Relative, normalized, '/'-separated path inside the root, or an exception with a message for the model. */
    String rel(String path) throws IOException {
        if (path == null || path.isBlank() || path.equals(".") || path.equals("/")) return "";
        String p = path.strip().replace('\\', '/');
        if (p.startsWith("<") && p.endsWith(">")) p = p.substring(1, p.length() - 1);
        p = p.replaceFirst("^\\./", "").replaceFirst("^/+", "");
        Path abs = root.resolve(p).normalize();
        if (!abs.startsWith(root)) throw new IOException("путь вне корня проекта: " + path);
        String r = root.relativize(abs).toString().replace('\\', '/');
        for (String part : r.split("/")) {
            if (SKIP_DIRS.contains(part)) throw new IOException("служебная папка недоступна: " + part);
        }
        String name = abs.getFileName() == null ? "" : abs.getFileName().toString();
        if (name.equals(".env") || name.startsWith(".env.")) throw new IOException("файлы .env недоступны (секреты)");
        if (Files.exists(abs) && !abs.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("ссылка ведёт за пределы проекта: " + path);
        }
        return r;
    }

    static boolean isText(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1).toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? name : name.substring(dot + 1);
        return TEXT_EXT.contains(ext) || name.equals("dockerfile") || name.equals("makefile") || name.equals("license");
    }

    // ================================================================ listing

    /** Every file of the project (disk + files created in the overlay), sorted. */
    public List<FileInfo> list(String glob) throws IOException {
        PathMatcher m = matcher(glob);
        TreeMap<String, FileInfo> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String r = root.relativize(p).toString().replace('\\', '/');
                if (skipped(r) || !matches(m, r)) continue;
                boolean text = isText(r);
                out.put(r, new FileInfo(r, Files.size(p), text ? lineCount(read(r)) : 0, text));
            }
        }
        for (var e : changes.entrySet()) {
            if (matches(m, e.getKey())) {
                String c = e.getValue().current();
                out.put(e.getKey(), new FileInfo(e.getKey(), c.getBytes(StandardCharsets.UTF_8).length, lineCount(c), true));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static boolean skipped(String rel) {
        for (String part : rel.split("/")) {
            if (SKIP_DIRS.contains(part) || part.equals(".DS_Store")) return true;
        }
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return name.equals(".env") || name.startsWith(".env.");
    }

    /**
     * Glob, forgiving for an LLM: no glob → everything; "*.md" (no '/') → by file name in any folder;
     * "dir" or "dir/" → everything below dir; "**&#47;x" also matches x at the top level.
     */
    PathMatcher matcher(String glob) throws IOException {
        if (glob == null || glob.isBlank() || glob.equals("*") || glob.equals("**")) return null;
        String g = glob.strip().replace('\\', '/').replaceFirst("^\\./", "");
        if (!g.contains("*") && !g.contains("?") && !g.contains("{")) {
            String dir = g.endsWith("/") ? g.substring(0, g.length() - 1) : g;
            if (Files.isDirectory(root.resolve(dir))) g = dir + "/**";
        }
        final String pattern = g;
        try {
            PathMatcher full = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            PathMatcher top = pattern.startsWith("**/") ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3)) : null;
            PathMatcher byName = pattern.contains("/") ? null : full;
            return p -> full.matches(p) || (top != null && top.matches(p))
                    || (byName != null && p.getFileName() != null && byName.matches(p.getFileName()));
        } catch (IllegalArgumentException e) {
            throw new IOException("неверная маска: " + glob);
        }
    }

    private static boolean matches(PathMatcher m, String rel) {
        return m == null || m.matches(Path.of(rel));
    }

    // ================================================================ reading

    public boolean exists(String path) throws IOException {
        String r = rel(path);
        return changes.containsKey(r) || Files.isRegularFile(root.resolve(r));
    }

    /** Current text of a file (overlay first). */
    public String read(String path) throws IOException {
        String r = rel(path);
        Change c = changes.get(r);
        if (c != null) return c.current();
        Path abs = root.resolve(r);
        if (Files.isDirectory(abs)) throw new IOException("это папка, а не файл: " + r + " — используйте list_files");
        if (!Files.isRegularFile(abs)) throw new IOException("файл не найден: " + r);
        if (!isText(r)) throw new IOException("не текстовый файл: " + r);
        if (Files.size(abs) > MAX_FILE_BYTES) throw new IOException("файл слишком большой: " + r);
        try {
            return Files.readString(abs, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IOException("файл не в UTF-8: " + r);
        }
    }

    /** Numbered lines [from, to] (1-based, inclusive), capped by MAX_READ_LINES / MAX_READ_CHARS. */
    public String readNumbered(String path, Integer from, Integer to) throws IOException {
        String r = rel(path);
        List<String> lines = lines(read(r));
        int n = lines.size();
        int a = Math.max(1, from == null ? 1 : from);
        int b = Math.min(n, to == null ? n : to);
        if (n == 0) return r + " — пустой файл";
        if (a > n) throw new IOException("в файле " + n + " строк, start_line=" + a + " за концом");
        StringBuilder sb = new StringBuilder();
        int shown = a - 1;
        for (int i = a; i <= b && i - a < MAX_READ_LINES; i++) {
            String line = lines.get(i - 1);
            if (sb.length() + line.length() > MAX_READ_CHARS && i > a) break;
            sb.append(String.format("%4d| ", i)).append(line).append('\n');
            shown = i;
        }
        String head = r + " — строки " + a + "–" + shown + " из " + n
                + (changes.containsKey(r) ? " (с несохранёнными изменениями ассистента)" : "") + "\n";
        String tail = shown < b ? "… обрезано; продолжение: read_file(path, start_line=" + (shown + 1) + ")\n" : "";
        return head + sb + tail;
    }

    // ================================================================ search

    /**
     * Search across files: literal text (default) or a regex, case-insensitive by default.
     * invert=true → the files that do NOT contain the query (handy for checking rules).
     */
    public SearchResult search(String query, boolean regex, String glob, boolean caseSensitive, int max) throws IOException {
        if (query == null || query.isEmpty()) throw new IOException("пустой запрос");
        Pattern p;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            p = Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            throw new IOException("неверное регулярное выражение: " + e.getDescription());
        }
        List<Match> out = new ArrayList<>();
        Map<String, Integer> perFile = new LinkedHashMap<>();
        int total = 0;
        for (FileInfo f : list(glob)) {
            if (!f.text()) continue;
            List<String> lines = lines(read(f.path()));
            for (int i = 0; i < lines.size(); i++) {
                if (p.matcher(lines.get(i)).find()) {
                    total++;
                    perFile.merge(f.path(), 1, Integer::sum);
                    if (out.size() < max) out.add(new Match(f.path(), i + 1, clip(lines.get(i).strip(), 220)));
                }
            }
        }
        return new SearchResult(out, perFile, total, total > out.size());
    }

    public List<String> filesWithout(String query, boolean regex, String glob, boolean caseSensitive) throws IOException {
        SearchResult r = search(query, regex, glob, caseSensitive, 0);
        List<String> out = new ArrayList<>();
        for (FileInfo f : list(glob)) {
            if (f.text() && !r.perFile().containsKey(f.path())) out.add(f.path());
        }
        return out;
    }

    // ================================================================ analysis: Markdown links

    /**
     * Relative links in Markdown files: [text](target) and ![img](target), targets with spaces in &lt;...&gt;.
     * broken = target file does not exist; orphans = .md files no other .md links to (entry points excluded by caller).
     */
    public LinkReport checkLinks(String glob) throws IOException {
        List<FileInfo> all = list(null);
        Set<String> existing = new TreeSet<>();
        Set<String> dirs = new TreeSet<>();
        for (FileInfo f : all) {
            existing.add(f.path());
            for (String d = parent(f.path()); !d.isEmpty(); d = parent(d)) dirs.add(d);
        }
        Set<String> linked = new LinkedHashSet<>();
        List<BrokenLink> broken = new ArrayList<>();
        int files = 0;
        int links = 0;
        PathMatcher m = matcher(glob);
        for (FileInfo f : all) {
            if (!f.path().toLowerCase().endsWith(".md")) continue;
            boolean inScope = matches(m, f.path());
            List<String> lines = lines(read(f.path()));
            if (inScope) files++;
            boolean fence = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.strip().startsWith("```")) {
                    fence = !fence;
                    continue;
                }
                if (fence) continue;
                for (Pattern pt : List.of(MD_LINK, MD_IMAGE)) {
                    Matcher mm = pt.matcher(line);
                    while (mm.find()) {
                        String raw = mm.group(pt == MD_LINK ? 2 : 1);
                        String target = raw.startsWith("<") ? raw.substring(1, raw.length() - 1) : raw;
                        if (target.matches("(?i)^[a-z][a-z0-9+.-]*:.*") || target.startsWith("#") || target.startsWith("//")) continue;
                        String file = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                        if (file.isEmpty()) continue;
                        String decoded = file;
                        if (!raw.startsWith("<") || file.contains("%")) {
                            try {
                                decoded = URLDecoder.decode(file.replace("+", "%2B"), StandardCharsets.UTF_8);
                            } catch (IllegalArgumentException ignored) {
                                // keep raw
                            }
                        }
                        String resolved = Path.of(parent(f.path()).isEmpty() ? "." : parent(f.path())).resolve(decoded).normalize()
                                .toString().replace('\\', '/');
                        if (inScope) links++;
                        if (resolved.startsWith("..")) {
                            if (inScope) broken.add(new BrokenLink(f.path(), i + 1, raw, "ведёт за пределы проекта"));
                            continue;
                        }
                        if (existing.contains(resolved)) {
                            if (!resolved.equals(f.path())) linked.add(resolved);
                        } else if (!dirs.contains(resolved) && !Files.isDirectory(root.resolve(resolved))) {
                            String reason = !raw.startsWith("<") && target.contains("%20") ? "файл не найден"
                                    : !raw.startsWith("<") && mm.group(0).contains(" ") ? "файл не найден (путь с пробелами без <...>?)" : "файл не найден";
                            if (inScope) broken.add(new BrokenLink(f.path(), i + 1, raw, reason + ": " + resolved));
                        }
                    }
                }
            }
        }
        List<String> orphans = new ArrayList<>();
        for (FileInfo f : all) {
            if (f.path().toLowerCase().endsWith(".md") && matches(m, f.path()) && !linked.contains(f.path())) orphans.add(f.path());
        }
        return new LinkReport(files, links, broken, orphans);
    }

    // ================================================================ git history (read-only)

    public List<Commit> gitLog(int limit, String path, boolean withFiles) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "core.quotepath=off", "log",
                "--date=short", "--format=@@%h%x09%ad%x09%an%x09%s", "-n", String.valueOf(Math.max(1, Math.min(limit, 500)))));
        if (withFiles) cmd.add("--name-only");
        if (path != null && !path.isBlank()) {
            cmd.add("--");
            cmd.add(rel(path));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process pr = pb.start();
        String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!pr.waitFor(30, TimeUnit.SECONDS)) pr.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (pr.exitValue() != 0) throw new IOException("git log: " + out.strip());
        List<Commit> commits = new ArrayList<>();
        String[] cur = null;
        List<String> files = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.startsWith("@@")) {
                if (cur != null) commits.add(new Commit(cur[0], cur[1], cur[2], cur[3], List.copyOf(files)));
                cur = line.substring(2).split("\t", 4);
                files.clear();
            } else if (!line.isBlank() && cur != null) {
                files.add(line.strip());
            }
        }
        if (cur != null) commits.add(new Commit(cur[0], cur[1], cur[2], cur.length > 3 ? cur[3] : "", List.copyOf(files)));
        return commits;
    }

    // ================================================================ changes

    /** Create or overwrite a file. Returns the diff of this file against its original. */
    public String write(String path, String content) throws IOException {
        String r = rel(path);
        if (r.isEmpty() || r.endsWith("/")) throw new IOException("нужен путь к файлу");
        if (Files.isDirectory(root.resolve(r))) throw new IOException("это папка: " + r);
        if (!isText(r)) throw new IOException("записывать можно только текстовые файлы (" + String.join(", ", new TreeSet<>(TEXT_EXT)) + ")");
        if (content == null) throw new IOException("нет content");
        String original = changes.containsKey(r) ? changes.get(r).original()
                : Files.isRegularFile(root.resolve(r)) ? read(r) : null;
        if (original != null && original.equals(content)) {
            changes.remove(r);
        } else {
            changes.put(r, new Change(original, content));
        }
        if (apply) {
            Path abs = root.resolve(r);
            Files.createDirectories(abs.getParent());
            Files.writeString(abs, content, StandardCharsets.UTF_8);
        }
        return diffOf(r);
    }

    /** Exact replacement of old_text (must occur once unless replaceAll). Returns the file's diff. */
    public String edit(String path, String oldText, String newText, boolean replaceAll) throws IOException {
        String r = rel(path);
        if (oldText == null || oldText.isEmpty()) throw new IOException("пустой old_text");
        String text = read(r);
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String o = oldText.replace("\r\n", "\n").replace("\n", eol);
        String n = newText == null ? "" : newText.replace("\r\n", "\n").replace("\n", eol);
        int count = count(text, o);
        if (count == 0) {
            String hint = closestLine(text, oldText);
            throw new IOException("old_text не найден в " + r + " (копируйте фрагмент дословно, без номеров строк «12| »)"
                    + (hint == null ? "" : ". Похожая строка: «" + hint + "»"));
        }
        if (count > 1 && !replaceAll) {
            throw new IOException("old_text встречается в " + r + " " + count + " раз — добавьте окружающих строк или replace_all=true");
        }
        return write(r, replaceAll ? text.replace(o, n) : text.replaceFirst(Pattern.quote(o), Matcher.quoteReplacement(n)));
    }

    public List<String> changedFiles() {
        return new ArrayList<>(changes.keySet());
    }

    public boolean created(String rel) {
        Change c = changes.get(rel);
        return c != null && c.created();
    }

    /** Unified diff of every change (git-apply compatible). */
    public String pendingDiff() {
        StringBuilder sb = new StringBuilder();
        for (String r : changes.keySet()) sb.append(diffOf(r));
        return sb.toString();
    }

    public String stat() {
        if (changes.isEmpty()) return "изменений нет";
        StringBuilder sb = new StringBuilder();
        for (var e : changes.entrySet()) {
            int[] pm = plusMinus(diffOf(e.getKey()));
            sb.append(e.getValue().created() ? "создан  " : "изменён ").append(e.getKey())
                    .append("  +").append(pm[0]).append(" −").append(pm[1]).append('\n');
        }
        return sb.toString();
    }

    static int[] plusMinus(String diff) {
        int plus = 0;
        int minus = 0;
        for (String l : diff.split("\n")) {
            if (l.startsWith("+") && !l.startsWith("+++")) plus++;
            if (l.startsWith("-") && !l.startsWith("---")) minus++;
        }
        return new int[]{plus, minus};
    }

    String diffOf(String r) {
        Change c = changes.get(r);
        return c == null ? "" : unifiedDiff(r, c.original(), c.current());
    }

    // ================================================================ unified diff (LCS over lines, 3 lines of context)

    static String unifiedDiff(String path, String before, String after) {
        List<String> a = before == null ? List.of() : lines(before);
        List<String> b = lines(after);
        boolean aNoEol = before != null && !before.isEmpty() && !before.endsWith("\n");
        boolean bNoEol = !after.isEmpty() && !after.endsWith("\n");
        // the last line without a trailing newline differs from the same text with one (git shows both)
        List<String> ka = new ArrayList<>(a);
        List<String> kb = new ArrayList<>(b);
        if (aNoEol) ka.set(ka.size() - 1, ka.getLast() + "\u0000");
        if (bNoEol) kb.set(kb.size() - 1, kb.getLast() + "\u0000");
        // ops: ' ' keep, '-' delete, '+' insert ; each op = {type, indexA, indexB}
        List<int[]> ops = diffOps(ka, kb);
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        if (before == null) sb.append("new file mode 100644\n");
        sb.append("--- ").append(before == null ? "/dev/null" : "a/" + path).append('\n');
        sb.append("+++ b/").append(path).append('\n');
        int ctx = 3;
        int i = 0;
        while (i < ops.size()) {
            while (i < ops.size() && ops.get(i)[0] == ' ') i++;
            if (i >= ops.size()) break;
            int start = Math.max(0, i - ctx);
            int end = i;
            // extend the hunk while changes are closer than 2*ctx lines apart
            int lastChange = i;
            while (end < ops.size()) {
                if (ops.get(end)[0] != ' ') lastChange = end;
                else if (end - lastChange > 2 * ctx) break;
                end++;
            }
            end = Math.min(ops.size(), lastChange + ctx + 1);
            int aStart = ops.get(start)[1];
            int bStart = ops.get(start)[2];
            int aLen = 0;
            int bLen = 0;
            StringBuilder body = new StringBuilder();
            for (int k = start; k < end; k++) {
                int[] op = ops.get(k);
                char t = (char) op[0];
                String line = t == '+' ? b.get(op[2]) : a.get(op[1]);
                body.append(t).append(line).append('\n');
                if (t != '+') aLen++;
                if (t != '-') bLen++;
                if ((t != '+' && aNoEol && op[1] == a.size() - 1) || (t == '+' && bNoEol && op[2] == b.size() - 1)) {
                    body.append("\\ No newline at end of file\n");
                }
            }
            sb.append("@@ -").append(aLen == 0 ? aStart : aStart + 1).append(aLen == 1 ? "" : "," + aLen)
                    .append(" +").append(bLen == 0 ? bStart : bStart + 1).append(bLen == 1 ? "" : "," + bLen).append(" @@\n")
                    .append(body);
            i = end;
        }
        return sb.toString();
    }

    /** Edit script via LCS on the middle part (common prefix/suffix trimmed first). */
    static List<int[]> diffOps(List<String> a, List<String> b) {
        int pre = 0;
        while (pre < a.size() && pre < b.size() && a.get(pre).equals(b.get(pre))) pre++;
        int suf = 0;
        while (suf < a.size() - pre && suf < b.size() - pre && a.get(a.size() - 1 - suf).equals(b.get(b.size() - 1 - suf))) suf++;
        int n = a.size() - pre - suf;
        int m = b.size() - pre - suf;
        List<int[]> ops = new ArrayList<>();
        for (int k = 0; k < pre; k++) ops.add(new int[]{' ', k, k});
        if ((long) n * m > 25_000_000L) {
            for (int k = 0; k < n; k++) ops.add(new int[]{'-', pre + k, pre});
            for (int k = 0; k < m; k++) ops.add(new int[]{'+', pre + n, pre + k});
        } else {
            int[][] dp = new int[n + 1][m + 1];
            for (int x = n - 1; x >= 0; x--) {
                for (int y = m - 1; y >= 0; y--) {
                    dp[x][y] = a.get(pre + x).equals(b.get(pre + y)) ? dp[x + 1][y + 1] + 1 : Math.max(dp[x + 1][y], dp[x][y + 1]);
                }
            }
            int x = 0;
            int y = 0;
            while (x < n || y < m) {
                if (x < n && y < m && a.get(pre + x).equals(b.get(pre + y))) {
                    ops.add(new int[]{' ', pre + x, pre + y});
                    x++;
                    y++;
                } else if (y < m && (x == n || dp[x][y + 1] >= dp[x + 1][y])) {
                    ops.add(new int[]{'+', pre + x, pre + y});
                    y++;
                } else {
                    ops.add(new int[]{'-', pre + x, pre + y});
                    x++;
                }
            }
        }
        for (int k = 0; k < suf; k++) ops.add(new int[]{' ', a.size() - suf + k, b.size() - suf + k});
        return ops;
    }

    // ================================================================ helpers

    static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        String t = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        List<String> out = new ArrayList<>();
        for (String l : t.split("\n", -1)) out.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        return out;
    }

    static int lineCount(String text) {
        return lines(text).size();
    }

    static String parent(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static int count(String text, String part) {
        int c = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) c++;
        return c;
    }

    /** The line of the file sharing the most words with the first non-blank line of the fragment. */
    private static String closestLine(String text, String fragment) {
        String first = fragment.strip().split("\n")[0].replaceFirst("^\\s*\\d+\\|\\s?", "").strip();
        if (first.isEmpty()) return null;
        Set<String> words = new TreeSet<>(List.of(first.toLowerCase().split("\\W+")));
        String best = null;
        int bestScore = 0;
        for (String l : lines(text)) {
            int s = 0;
            for (String w : l.toLowerCase().split("\\W+")) if (w.length() > 2 && words.contains(w)) s++;
            if (s > bestScore) {
                bestScore = s;
                best = l.strip();
            }
        }
        return best == null ? null : clip(best, 200);
    }

    /** Sorted by path — stable output for reports. */
    static Comparator<FileInfo> byPath() {
        return Comparator.comparing(FileInfo::path);
    }
}
