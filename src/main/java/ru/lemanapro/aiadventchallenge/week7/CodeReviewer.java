package ru.lemanapro.aiadventchallenge.week7;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 32: AI code review of a pull request.
 *
 *   1. PR input   — git: `diff base...head` (what the PR adds on top of the merge base), the list of changed files
 *                   with +/- and status, and the NEW content of every changed file (`git show head:path`, trimmed);
 *   2. RAG        — ProjectDocs over documentation AND code (README.md, docs/, AGENTS.md, src/main/java) →
 *                   task32-review-index.json; queries are built per changed file (path + identifiers from the added
 *                   lines) + one "conventions" query, each searched SEPARATELY in documentation and in code (so the
 *                   bigger side never crowds the other out); fragments of the changed files themselves are excluded
 *                   (the reviewer already has their new version);
 *   3. LLM        — one request, strict JSON: summary, bugs[], architecture[], recommendations[], verdict;
 *   4. Verification in code — every finding must point at a file of the PR (others are dropped and counted),
 *                   a line is kept only if it is an added line of that file, refs [n] only if fragment n was in
 *                   the prompt; then the review is rendered as Markdown (what the GitHub Action posts to the PR).
 *
 * The reviewer's own sources (Task32 holds the demo PR, CodeReviewer the prompt) are excluded from the context
 * (TASK32_RAG_EXCLUDE) — otherwise the check would be answered from the demo's own description.
 *
 * Env: TASK32_MAX_DIFF_CHARS (40000), TASK32_MAX_FILE_CHARS (6000), TASK32_RAG_FRAGMENTS (10),
 *      TASK32_RAG_PATHS ("README.md,docs,AGENTS.md,src/main/java"), TASK32_INDEX (task32-review-index.json).
 */
public final class CodeReviewer {

    public record ChangedFile(String path, String status, int added, int deleted, boolean binary,
                              Set<Integer> addedLines, String content) {
    }

    public record PrDiff(String base, String head, String mergeBase, List<ChangedFile> files, String diff, boolean diffTruncated,
                         int totalAdded, int totalDeleted) {
        public List<String> paths() {
            return files.stream().map(ChangedFile::path).toList();
        }
    }

    public record Finding(String kind, String severity, String file, Integer line, String title, String details,
                          String suggestion, List<Integer> refs) {
    }

    public record Review(PrDiff pr, List<ProjectDocs.Fragment> context, String summary, String verdict,
                         List<Finding> bugs, List<Finding> architecture, List<Finding> recommendations,
                         List<String> dropped, String model, long millis, String raw, String error) {
        public boolean ok() {
            return error == null;
        }

        public long docFragments() {
            return context.stream().filter(f -> !f.chunk().source().endsWith(".java")).count();
        }

        public long codeFragments() {
            return context.stream().filter(f -> f.chunk().source().endsWith(".java")).count();
        }
    }

    static final String SYSTEM_PROMPT = """
            Ты — опытный ревьюер Java-проекта AIAdventChallenge (Maven, Java 25, CLI-демо LLM по дням челленджа).
            Тебе дают pull request: список изменённых файлов, diff и новые версии файлов, а также фрагменты
            документации и кода проекта [1]..[N] (соглашения, архитектура, существующие общие классы).

            Задача — ревью ТОЛЬКО изменений этого PR:
            - bugs — потенциальные баги: ошибки логики, NPE / выход за границы, утечки ресурсов, неверная обработка ошибок,
              гонки, проблемы безопасности (секреты в коде, инъекции), некорректный JSON/HTTP.
            - architecture — архитектурные проблемы: нарушение соглашений, слоёв и запретов проекта, описанных во фрагментах
              документации; дублирование того, что уже есть в общих классах из фрагментов кода; неверное место для кода, связность.
            - recommendations — рекомендации: читаемость, тесты, документация (README/AGENTS.md/docs), именование, мелкие улучшения.

            Правила:
            - Каждое замечание привязывай к файлу из PR (поле file — путь ровно как в списке) и, если можешь, к номеру
              ДОБАВЛЕННОЙ строки в новой версии файла (поле line, иначе null).
            - Если замечание опирается на документацию или существующий код — укажи номера фрагментов в refs.
            - Не выдумывай проблем: если в разделе нечего сказать — пустой массив.
            - Пиши по-русски, конкретно: что не так и как исправить.

            Ответь ТОЛЬКО JSON без пояснений вокруг:
            {"summary":"1-2 предложения: что делает PR и общее впечатление",
             "verdict":"approve | comment | request_changes",
             "bugs":[{"severity":"high|medium|low","file":"...","line":12,"title":"...","details":"...","suggestion":"...","refs":[1]}],
             "architecture":[{"severity":"high|medium|low","file":"...","line":null,"title":"...","details":"...","suggestion":"...","refs":[2]}],
             "recommendations":[{"file":"...","line":null,"title":"...","details":"...","refs":[]}]}""";

    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/@{}~^-]{1,200}");
    private static final Set<String> JAVA_NOISE = Set.of("public", "private", "static", "final", "return", "String", "class",
            "void", "throws", "Exception", "import", "new", "this", "null", "true", "false", "List", "Map", "int", "else", "while");

    private final Path gitRepo;
    private final LlmClient.Config cfg;
    private final HttpClient http = LlmClient.newHttpClient();
    private final ProjectDocs knowledge;

    /**
     * @param gitRepo       repository with the PR commits (in CI — the checkout)
     * @param knowledgeRoot project root whose docs + code form the RAG knowledge base (usually the same directory)
     */
    public CodeReviewer(Path gitRepo, Path knowledgeRoot, LlmClient.Config cfg) {
        this.gitRepo = gitRepo.toAbsolutePath().normalize();
        this.cfg = cfg;
        List<String> paths = new ArrayList<>();
        for (String p : LlmClient.env("TASK32_RAG_PATHS", "README.md,docs,AGENTS.md,src/main/java").split(",")) {
            if (!p.isBlank()) paths.add(p.strip());
        }
        this.knowledge = new ProjectDocs(knowledgeRoot, http, cfg, paths, LlmClient.env("TASK32_INDEX", "task32-review-index.json"));
    }

    public ProjectDocs knowledge() {
        return knowledge;
    }

    public String model() {
        return cfg == null ? "—" : cfg.model();
    }

    // ================================================================ 1. PR input: diff + changed files

    public PrDiff diff(String base, String head) throws Exception {
        checkRef(base);
        checkRef(head);
        String mergeBase = git("merge-base", base, head).strip();
        String range = base + "..." + head; // three dots: changes of the PR on top of the merge base
        int maxDiff = Integer.parseInt(LlmClient.env("TASK32_MAX_DIFF_CHARS", "40000"));
        int maxFile = Integer.parseInt(LlmClient.env("TASK32_MAX_FILE_CHARS", "6000"));

        Map<String, String> status = new LinkedHashMap<>();
        for (String line : git("diff", "--name-status", "-M", range).split("\n")) {
            String[] p = line.split("\t");
            if (p.length >= 2) status.put(p[p.length - 1], p[0].substring(0, 1));
        }
        String patch = git("diff", "--no-color", "--no-ext-diff", "-M", "--unified=3", range);
        Map<String, Set<Integer>> addedLines = addedLines(patch);

        List<ChangedFile> files = new ArrayList<>();
        int add = 0, del = 0;
        for (String line : git("diff", "--numstat", "-M", range).split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 3) continue;
            String path = p[2].contains(" => ") ? renamedTo(p[2]) : p[2];
            boolean bin = p[0].equals("-");
            int a = bin ? 0 : Integer.parseInt(p[0]), d = bin ? 0 : Integer.parseInt(p[1]);
            add += a;
            del += d;
            String st = status.getOrDefault(path, "M");
            String content = "";
            if (!bin && !st.equals("D")) {
                try {
                    content = git("show", head + ":" + path);
                    if (content.length() > maxFile) content = content.substring(0, maxFile) + "\n… (файл обрезан)";
                } catch (Exception ignored) {
                    content = "";
                }
            }
            files.add(new ChangedFile(path, st, a, d, bin, addedLines.getOrDefault(path, Set.of()), content));
        }
        boolean truncated = patch.length() > maxDiff;
        return new PrDiff(base, head, mergeBase, files, truncated ? patch.substring(0, maxDiff) + "\n… (diff обрезан)" : patch,
                truncated, add, del);
    }

    /** New-file line numbers of every added line, per file (from the unified diff). */
    static Map<String, Set<Integer>> addedLines(String patch) {
        Map<String, Set<Integer>> out = new LinkedHashMap<>();
        String file = null;
        int line = 0;
        for (String l : patch.split("\n")) {
            if (l.startsWith("+++ ")) {
                file = l.startsWith("+++ b/") ? l.substring(6) : null;
                continue;
            }
            Matcher h = HUNK.matcher(l);
            if (h.find()) {
                line = Integer.parseInt(h.group(1));
                continue;
            }
            if (file == null || l.startsWith("--- ") || l.startsWith("diff --git")) continue;
            if (l.startsWith("+")) {
                out.computeIfAbsent(file, k -> new TreeSet<>()).add(line);
                line++;
            } else if (l.startsWith(" ")) {
                line++;
            }
        }
        return out;
    }

    private static String renamedTo(String numstatPath) {
        // "src/{a => b}/X.java" or "a.txt => b.txt"
        Matcher m = Pattern.compile("^(.*)\\{(.*) => (.*)}(.*)$").matcher(numstatPath);
        if (m.find()) return (m.group(1) + m.group(3) + m.group(4)).replace("//", "/");
        return numstatPath.substring(numstatPath.indexOf(" => ") + 4);
    }

    // ================================================================ 2. RAG: docs + code

    public List<ProjectDocs.Fragment> context(PrDiff pr) throws Exception {
        int limit = Integer.parseInt(LlmClient.env("TASK32_RAG_FRAGMENTS", "10"));
        Set<String> changed = new LinkedHashSet<>(pr.paths());
        List<String> exclude = new ArrayList<>();
        for (String e : LlmClient.env("TASK32_RAG_EXCLUDE", "week7/Task32.java,week7/CodeReviewer.java").split(",")) {
            if (!e.isBlank()) exclude.add(e.strip());
        }
        List<String> queries = new ArrayList<>();
        queries.add("соглашения проекта архитектура слои LlmClient конфигурация ключи .env CONVENTIONS ANTI-PATTERNS");
        for (ChangedFile f : pr.files()) {
            Set<String> ids = new LinkedHashSet<>();
            for (String l : addedText(pr.diff(), f.path()).split("\n")) {
                Matcher m = IDENT.matcher(l);
                while (m.find() && ids.size() < 25) {
                    if (!JAVA_NOISE.contains(m.group())) ids.add(m.group());
                }
            }
            queries.add(f.path() + " " + String.join(" ", ids));
        }
        Map<String, ProjectDocs.Fragment> best = new LinkedHashMap<>();
        for (String q : queries) {
            List<ProjectDocs.Fragment> found = new ArrayList<>(knowledge.retrieve(q, 3, 2, src -> !src.endsWith(".java")));
            found.addAll(knowledge.retrieve(q, 3, 1, src -> src.endsWith(".java") && !changed.contains(src)
                    && exclude.stream().noneMatch(src::endsWith)));
            for (ProjectDocs.Fragment f : found) {
                if (changed.contains(f.chunk().source())) continue; // the reviewer already has the new version
                if (exclude.stream().anyMatch(f.chunk().source()::endsWith)) continue; // the reviewer itself / its demo PR
                ProjectDocs.Fragment prev = best.get(f.chunk().chunkId());
                if (prev == null || prev.score() < f.score()) best.put(f.chunk().chunkId(), f);
            }
        }
        List<ProjectDocs.Fragment> sorted = new ArrayList<>(best.values());
        sorted.sort((a, b) -> Double.compare(b.score(), a.score()));
        // keep both kinds of knowledge: at least a few documentation and a few code fragments when available
        List<ProjectDocs.Fragment> docs = sorted.stream().filter(f -> !f.chunk().source().endsWith(".java")).toList();
        List<ProjectDocs.Fragment> code = sorted.stream().filter(f -> f.chunk().source().endsWith(".java")).toList();
        List<ProjectDocs.Fragment> picked = new ArrayList<>();
        int half = Math.max(1, limit / 2);
        picked.addAll(docs.subList(0, Math.min(half, docs.size())));
        picked.addAll(code.subList(0, Math.min(limit - picked.size(), code.size())));
        for (ProjectDocs.Fragment f : sorted) {
            if (picked.size() >= limit) break;
            if (!picked.contains(f)) picked.add(f);
        }
        List<ProjectDocs.Fragment> numbered = new ArrayList<>();
        for (ProjectDocs.Fragment f : picked) numbered.add(new ProjectDocs.Fragment(numbered.size() + 1, f.chunk(), f.score()));
        return numbered;
    }

    private static String addedText(String patch, String path) {
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String l : patch.split("\n")) {
            if (l.startsWith("+++ ")) {
                in = l.equals("+++ b/" + path);
                continue;
            }
            if (in && l.startsWith("+")) sb.append(l, 1, l.length()).append('\n');
        }
        return sb.toString();
    }

    // ================================================================ 3-4. LLM review + verification

    public Review review(String base, String head) throws Exception {
        long t0 = System.nanoTime();
        PrDiff pr = diff(base, head);
        if (pr.files().isEmpty()) {
            return new Review(pr, List.of(), "В PR нет изменений относительно базы.", "approve", List.of(), List.of(), List.of(),
                    List.of(), model(), ms(t0), "", null);
        }
        List<ProjectDocs.Fragment> ctx = context(pr);
        if (cfg == null) {
            return new Review(pr, ctx, "", "", List.of(), List.of(), List.of(), List.of(), model(), ms(t0), "",
                    "LLM не настроен: нет LLM_API_KEY (в CI — секрет репозитория LLM_API_KEY)");
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(LlmClient.message("system", SYSTEM_PROMPT));
        messages.add(LlmClient.message("user", userMessage(pr, ctx)));
        String raw = "";
        JsonNode json = null;
        String error = null;
        for (int attempt = 1; attempt <= 2 && json == null; attempt++) {
            try {
                raw = LlmClient.content(LlmClient.send(http, cfg, messages, 4000, null, 0.1));
                json = parseJson(raw);
                if (json == null && attempt == 1) {
                    messages.add(LlmClient.message("assistant", raw));
                    messages.add(LlmClient.message("user", "Ответ не разобрался как JSON. Верни ТОЛЬКО JSON по схеме из инструкции."));
                }
            } catch (LlmClient.RequestException e) {
                error = "LLM: HTTP " + e.status() + " " + DevAssistant.clip(String.valueOf(e.body()).replaceAll("\\s+", " "), 300);
                break;
            } catch (Exception e) {
                error = "LLM: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                break;
            }
        }
        if (json == null) {
            return new Review(pr, ctx, "", "", List.of(), List.of(), List.of(), List.of(), model(), ms(t0), raw,
                    error != null ? error : "модель не вернула JSON ревью");
        }
        List<String> dropped = new ArrayList<>();
        List<Finding> bugs = findings(json.path("bugs"), "bug", pr, ctx, dropped);
        List<Finding> arch = findings(json.path("architecture"), "architecture", pr, ctx, dropped);
        List<Finding> recs = findings(json.path("recommendations"), "recommendation", pr, ctx, dropped);
        String verdict = json.path("verdict").asText("comment").toLowerCase(Locale.ROOT);
        if (!Set.of("approve", "comment", "request_changes").contains(verdict)) verdict = "comment";
        return new Review(pr, ctx, json.path("summary").asText(""), verdict, bugs, arch, recs, dropped, model(), ms(t0), raw, null);
    }

    static String userMessage(PrDiff pr, List<ProjectDocs.Fragment> ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pull request: ").append(pr.base()).append("...").append(pr.head()).append(", файлов ").append(pr.files().size())
                .append(", +").append(pr.totalAdded()).append(" −").append(pr.totalDeleted()).append("\n\nИзменённые файлы:\n");
        for (ChangedFile f : pr.files()) {
            sb.append("- ").append(f.path()).append(" [").append(statusName(f.status())).append("] +").append(f.added()).append(" −").append(f.deleted())
                    .append(f.addedLines().isEmpty() ? "" : ", добавленные строки " + ranges(f.addedLines())).append('\n');
        }
        sb.append("\nDIFF:\n```diff\n").append(pr.diff()).append("\n```\n");
        sb.append("\nНовые версии изменённых файлов (с номерами строк):\n");
        for (ChangedFile f : pr.files()) {
            if (f.content().isEmpty()) continue;
            sb.append("\n### ").append(f.path()).append("\n```\n");
            int n = 1;
            for (String l : f.content().split("\n", -1)) sb.append(String.format("%4d| %s%n", n++, l));
            sb.append("```\n");
        }
        sb.append("\nКонтекст проекта (RAG: документация и код):\n");
        if (ctx.isEmpty()) sb.append("— ничего не найдено\n");
        for (ProjectDocs.Fragment f : ctx) {
            sb.append("\n[").append(f.n()).append("] ").append(f.label()).append("\n").append(f.chunk().text().strip()).append('\n');
        }
        return sb.toString();
    }

    private List<Finding> findings(JsonNode arr, String kind, PrDiff pr, List<ProjectDocs.Fragment> ctx, List<String> dropped) {
        List<Finding> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        Map<String, ChangedFile> byPath = new LinkedHashMap<>();
        pr.files().forEach(f -> byPath.put(f.path(), f));
        for (JsonNode n : arr) {
            String title = n.path("title").asText("").strip();
            if (title.isEmpty()) title = DevAssistant.clip(n.path("details").asText("").strip(), 80);
            if (title.isEmpty()) continue;
            String file = n.path("file").asText("").strip().replaceFirst("^(a|b)/", "");
            if (!file.isEmpty() && !byPath.containsKey(file)) {
                String asked = file;
                String matched = byPath.keySet().stream().filter(p -> p.endsWith("/" + asked) || p.equals(asked)).findFirst().orElse(null);
                if (matched == null) {
                    dropped.add(kind + ": «" + title + "» — файл " + file + " не из PR");
                    continue;
                }
                file = matched;
            }
            Integer line = n.path("line").canConvertToInt() && n.path("line").asInt() > 0 ? n.path("line").asInt() : null;
            if (line != null && (file.isEmpty() || !byPath.get(file).addedLines().contains(line))) line = null;
            List<Integer> refs = new ArrayList<>();
            for (JsonNode r : n.path("refs")) {
                int k = r.asInt(-1);
                if (k >= 1 && k <= ctx.size() && !refs.contains(k)) refs.add(k);
            }
            String sev = n.path("severity").asText(kind.equals("recommendation") ? "" : "medium").toLowerCase(Locale.ROOT);
            out.add(new Finding(kind, sev, file, line, title, n.path("details").asText("").strip(),
                    n.path("suggestion").asText("").strip(), refs));
        }
        return out;
    }

    static JsonNode parseJson(String raw) {
        String s = THINK.matcher(raw == null ? "" : raw).replaceAll("").strip();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonNode n = LlmClient.parseJson(s.substring(a, b + 1));
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================ Markdown (the PR comment)

    public static String markdown(Review r) {
        StringBuilder md = new StringBuilder("<!-- ai-review -->\n## 🤖 AI-ревью PR\n\n");
        PrDiff pr = r.pr();
        md.append("**Изменения:** ").append(pr.files().size()).append(" файл(ов), +").append(pr.totalAdded()).append(" −").append(pr.totalDeleted())
                .append(" (`").append(shortSha(pr.base())).append("...").append(shortSha(pr.head())).append("`)")
                .append(pr.diffTruncated() ? ", diff обрезан" : "").append(" · модель `").append(r.model()).append("` · RAG: ")
                .append(r.docFragments()).append(" фрагм. документации, ").append(r.codeFragments()).append(" фрагм. кода\n\n");
        if (!r.ok()) {
            md.append("⚠️ Ревью не получено: ").append(r.error()).append("\n");
            return md.toString();
        }
        md.append("**Итог:** ").append(r.summary().isBlank() ? "—" : r.summary()).append("  \n**Вердикт:** ").append(switch (r.verdict()) {
            case "approve" -> "✅ можно мержить";
            case "request_changes" -> "❌ нужны исправления";
            default -> "💬 есть замечания";
        }).append("\n\n");
        section(md, "🐞 Потенциальные баги", r.bugs(), r.context());
        section(md, "🏗 Архитектурные проблемы", r.architecture(), r.context());
        section(md, "💡 Рекомендации", r.recommendations(), r.context());
        md.append("<details><summary>Изменённые файлы и контекст RAG</summary>\n\n");
        for (ChangedFile f : pr.files()) {
            md.append("- `").append(f.path()).append("` ").append(statusName(f.status())).append(" +").append(f.added()).append(" −").append(f.deleted()).append('\n');
        }
        md.append("\nКонтекст:\n");
        for (ProjectDocs.Fragment f : r.context()) md.append("- [").append(f.n()).append("] `").append(f.label()).append("`\n");
        if (!r.dropped().isEmpty()) md.append("\nОтброшено проверкой (не относится к файлам PR): ").append(r.dropped().size()).append('\n');
        md.append("\n</details>\n\n_Сгенерировано `Task32` (день 32): diff PR + RAG по документации и коду проекта. Ревью от модели — проверяйте перед применением._\n");
        return md.toString();
    }

    private static void section(StringBuilder md, String title, List<Finding> items, List<ProjectDocs.Fragment> ctx) {
        md.append("### ").append(title).append("\n\n");
        if (items.isEmpty()) {
            md.append("Не найдено.\n\n");
            return;
        }
        int i = 1;
        for (Finding f : items) {
            md.append(i++).append(". ");
            if (!f.severity().isBlank()) md.append("**[").append(f.severity()).append("]** ");
            md.append("**").append(f.title()).append("**");
            if (!f.file().isEmpty()) md.append(" — `").append(f.file()).append(f.line() != null ? ":" + f.line() : "").append('`');
            md.append('\n');
            if (!f.details().isBlank()) md.append("   ").append(f.details().replace("\n", "\n   ")).append('\n');
            if (!f.suggestion().isBlank()) md.append("   _Как исправить:_ ").append(f.suggestion().replace("\n", "\n   ")).append('\n');
            if (!f.refs().isEmpty()) {
                md.append("   _Основание:_ ").append(String.join(", ", f.refs().stream()
                        .map(k -> "[" + k + "] `" + ctx.get(k - 1).label() + "`").toList())).append('\n');
            }
        }
        md.append('\n');
    }

    // ================================================================ helpers

    private static String statusName(String s) {
        return switch (s) {
            case "A" -> "добавлен";
            case "D" -> "удалён";
            case "R" -> "переименован";
            default -> "изменён";
        };
    }

    private static String ranges(Set<Integer> lines) {
        List<String> out = new ArrayList<>();
        Integer start = null, prev = null;
        for (int l : lines) {
            if (start == null) {
                start = prev = l;
            } else if (l == prev + 1) {
                prev = l;
            } else {
                out.add(start.equals(prev) ? "" + start : start + "-" + prev);
                start = prev = l;
            }
        }
        if (start != null) out.add(start.equals(prev) ? "" + start : start + "-" + prev);
        return String.join(", ", out);
    }

    private static String shortSha(String ref) {
        return ref.matches("[0-9a-f]{40}") ? ref.substring(0, 7) : ref;
    }

    private static void checkRef(String ref) {
        if (ref == null || !SAFE_REF.matcher(ref).matches() || ref.startsWith("-")) {
            throw new IllegalArgumentException("Недопустимый ref: " + ref);
        }
    }

    String git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", gitRepo.toString(), "--no-pager", "-c", "core.quotepath=off"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process p = pb.start();
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readAllBytes();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git не ответил за 60 с");
        }
        if (p.exitValue() != 0) throw new IllegalStateException("git " + args[0] + ": " + err);
        return new String(out, StandardCharsets.UTF_8);
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
