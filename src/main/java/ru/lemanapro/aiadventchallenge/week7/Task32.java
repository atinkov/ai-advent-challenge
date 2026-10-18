package ru.lemanapro.aiadventchallenge.week7;

import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 32: automatic AI code review of a pull request.
 *
 *   .github/workflows/ai-review.yml — on every PR (opened / synchronize / reopened / ready_for_review) runs
 *       mvn -q -B compile exec:java -Ptask32 -Dexec.args="review --base <base sha> --head <head sha> --out target/ai-review.md"
 *   and posts target/ai-review.md as a sticky PR comment.
 *   CodeReviewer — diff base...head + changed files → RAG over docs AND code → LLM → verified findings → Markdown
 *   with three sections: потенциальные баги / архитектурные проблемы / рекомендации.
 *
 * DEFAULT RUN = check of the assignment with 3 checks (no GitHub needed): a demo PR is created in a local clone
 * (target/task32-demo-pr: a new class with planted bugs + a README change), then the EXACT review command of the
 * workflow is executed against it (the ${{ github.event.pull_request.*.sha }} placeholders replaced by the demo SHAs):
 *   1. Пайплайн запускается на PR       — the workflow triggers on pull_request, runs Task32 review with the PR SHAs,
 *                                         posts the result to the PR; its command really runs and writes the review file
 *   2. Ассистент получает diff и файлы  — exactly the PR's files, the diff with the planted lines, added-line numbers,
 *                                         new contents of the changed files
 *   3. Ревью: баги / архитектура / рекомендации — all three sections, RAG context from documentation AND code,
 *                                         the planted bug and the planted architecture violation are found, every
 *                                         finding points at a file of the PR
 * → task32-code-review-report.md (with the full review text).
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask32                                                     # 3 checks + report
 *   mvn -q exec:java -Ptask32 -Dexec.args="review --base main --head HEAD"               # review the current branch vs main
 *   mvn -q exec:java -Ptask32 -Dexec.args="review --base main --head HEAD --out r.md"    # … and save the comment text
 */
public final class Task32 {

    static final Path WORKFLOW = Path.of(".github/workflows/ai-review.yml");
    private static final Path REPORT = Path.of("task32-code-review-report.md");
    private static final Path DEMO_DIR = Path.of("target/task32-demo-pr");
    static final String DEMO_FILE = "src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java";

    /** The demo PR: a new day-like class with planted defects + a docs change. */
    private static final String DEMO_CODE = """
            package ru.lemanapro.aiadventchallenge.week7;

            import java.net.URI;
            import java.net.http.HttpClient;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;

            /** Demo: asks the LLM about the weather in a city. */
            public final class DemoWeatherTask {

                private static final String API_KEY = "sk-demo-0123456789abcdef";

                public static void main(String[] args) throws Exception {
                    String city = args[0];
                    HttpClient http = HttpClient.newHttpClient();
                    String body = "{\\"model\\":\\"qwen3.8-27b\\",\\"messages\\":[{\\"role\\":\\"user\\",\\"content\\":\\"Какая погода в " + city + "?\\"}]}";
                    HttpRequest request = HttpRequest.newBuilder(URI.create("https://gpustack.data.lmru.tech/v1/chat/completions"))
                            .header("Authorization", "Bearer " + API_KEY)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    String answer = response.body().split("\\"content\\":\\"")[1].split("\\"")[0];
                    System.out.println("Ответ: " + answer);
                }
            }
            """;

    /** What the reviewer is expected to catch in the demo PR. */
    static final Pattern PLANTED_BUG = Pattern.compile(
            "(?i)args\\[0]|ArrayIndexOutOfBounds|без аргумент|split|парсинг|разбор|statusCode|код ответа|HTTP-статус|экраниров|инъекц|injection");
    static final Pattern PLANTED_ARCH = Pattern.compile(
            "(?i)LlmClient|дублир|сло[йяе]|транспорт|Task-класс|\\.env|соглашени|API_KEY|ключ");

    record Check(String what, boolean ok, List<String> criteria) {
    }

    private Task32() {
    }

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (!a.isEmpty() && a.getFirst().equals("review")) {
            System.exit(runReview(a.subList(1, a.size()), null) == null ? 2 : 0);
        }
        check();
    }

    // ================================================================ review mode (what the GitHub Action runs)

    record ReviewRun(CodeReviewer.Review review, String markdown, Path out) {
    }

    /**
     * review --base <ref> --head <ref> [--out <file>] [--repo <git dir>]
     *
     * @param repoOverride used by the check to point the same command at the demo clone
     */
    static ReviewRun runReview(List<String> args, Path repoOverride) throws Exception {
        Map<String, String> opt = options(args);
        if (!opt.containsKey("base") || !opt.containsKey("head")) {
            System.err.println("Использование: review --base <ref> --head <ref> [--out <файл>] [--repo <папка git>]");
            return null;
        }
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path repo = repoOverride != null ? repoOverride : Path.of(opt.getOrDefault("repo", ".")).toAbsolutePath().normalize();
        LlmClient.Config cfg = LlmClient.env("LLM_API_KEY", null) == null ? null : LlmClient.fromEnv();
        CodeReviewer reviewer = new CodeReviewer(repo, root, cfg);
        System.out.println("AI-ревью: " + opt.get("base") + "..." + opt.get("head") + " в " + repo);
        CodeReviewer.Review r = reviewer.review(opt.get("base"), opt.get("head"));
        String md = CodeReviewer.markdown(r);
        Path out = null;
        if (opt.containsKey("out")) {
            out = Path.of(opt.get("out"));
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, md, StandardCharsets.UTF_8);
        }
        System.out.println(md);
        if (out != null) System.out.println("Ревью сохранено: " + out);
        if (!r.ok()) System.err.println("Ревью не получено: " + r.error());
        return new ReviewRun(r, md, out);
    }

    static Map<String, String> options(List<String> args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).startsWith("--")) m.put(args.get(i).substring(2), args.get(++i));
        }
        return m;
    }

    // ================================================================ check: 3 checks of the assignment

    static void check() throws Exception {
        Path root = Path.of(".").toAbsolutePath().normalize();
        System.out.println("══ День 32: проверка задания «Автоматизация ревью кода» ══\n");

        // ---- demo PR in a local clone (the user's repository is not touched)
        DemoPr pr = demoPr(root);
        System.out.println("Демо-PR: " + pr.base().substring(0, 7) + "..." + pr.head().substring(0, 7) + " в " + DEMO_DIR
                + " (файлы: " + String.join(", ", pr.files()) + ")\n");

        // ---- 1. the workflow: trigger, command, publication + run its command
        List<String> c1 = new ArrayList<>();
        String wf = Files.isRegularFile(WORKFLOW) ? Files.readString(WORKFLOW, StandardCharsets.UTF_8) : "";
        c1.add(mark(!wf.isEmpty(), "workflow `" + WORKFLOW + "` есть в репозитории"));
        boolean trigger = Pattern.compile("(?ms)^on:\\s*\\n\\s+pull_request:\\s*\\n\\s+types:\\s*\\[[^]]*opened[^]]*synchronize[^]]*]").matcher(wf).find();
        c1.add(mark(trigger, "запускается на событие `pull_request` (opened, synchronize, …)"));
        Matcher run = Pattern.compile("-Ptask32\\s+-Dexec\\.args=\"([^\"]+)\"").matcher(wf);
        String cmd = run.find() ? run.group(1) : "";
        boolean usesPrShas = cmd.contains("--base ${{ github.event.pull_request.base.sha }}")
                && cmd.contains("--head ${{ github.event.pull_request.head.sha }}");
        c1.add(mark(cmd.startsWith("review") && usesPrShas, "шаг запускает `Task32 review` с SHA базы и головы PR: `" + cmd + "`"));
        String outPath = options(List.of(cmd.split("\\s+"))).getOrDefault("out", "");
        boolean publishes = wf.contains("pull-requests: write") && wf.contains("sticky-pull-request-comment")
                && !outPath.isEmpty() && wf.contains("path: " + outPath);
        c1.add(mark(publishes, "ревью публикуется комментарием в PR (`pull-requests: write`, sticky comment из `" + outPath + "`)"));
        boolean fetchAll = wf.contains("fetch-depth: 0");
        c1.add(mark(fetchAll, "checkout с полной историей (`fetch-depth: 0`) — нужен для `diff base...head`"));

        String localCmd = cmd.replace("${{ github.event.pull_request.base.sha }}", pr.base())
                .replace("${{ github.event.pull_request.head.sha }}", pr.head());
        System.out.println("── Запуск команды из workflow: " + localCmd + "\n");
        Files.deleteIfExists(outPath.isEmpty() ? Path.of("target/ai-review.md") : Path.of(outPath));
        List<String> argv = new ArrayList<>(List.of(localCmd.split("\\s+")));
        ReviewRun rr = cmd.isEmpty() ? null : runReview(argv.subList(1, argv.size()), root.resolve(DEMO_DIR));
        boolean ran = rr != null && rr.out() != null && Files.isRegularFile(rr.out())
                && Files.readString(rr.out()).startsWith("<!-- ai-review -->") && rr.review().ok();
        c1.add(mark(ran, "команда workflow выполнена на демо-PR и записала `" + outPath + "`"
                + (rr != null && !rr.review().ok() ? " — ошибка: " + rr.review().error() : "")));
        Check ch1 = new Check("1. Пайплайн (GitHub Action) запускается на PR и вызывает ассистента", allOk(c1), c1);

        // ---- 2. diff and changed files
        List<String> c2 = new ArrayList<>();
        CodeReviewer.PrDiff d = rr == null ? null : rr.review().pr();
        if (d == null) {
            c2.add(mark(false, "ревью не запускалось"));
        } else {
            Set<String> got = new TreeSet<>(d.paths());
            Set<String> expected = new TreeSet<>(pr.files());
            c2.add(mark(got.equals(expected), "изменённые файлы PR: " + got + (got.equals(expected) ? "" : " (ожидались " + expected + ")")));
            c2.add(mark(d.diff().contains("+    private static final String API_KEY") && d.totalAdded() > 0,
                    "diff `base...head` получен: +" + d.totalAdded() + " −" + d.totalDeleted() + ", " + d.diff().length() + " символов, содержит добавленные строки"));
            CodeReviewer.ChangedFile demo = d.files().stream().filter(f -> f.path().equals(DEMO_FILE)).findFirst().orElse(null);
            int codeLines = DEMO_CODE.split("\n").length;
            c2.add(mark(demo != null && demo.addedLines().size() == codeLines,
                    "номера добавленных строк посчитаны: " + (demo == null ? "—" : demo.addedLines().size() + " из " + codeLines)));
            c2.add(mark(demo != null && demo.content().contains("class DemoWeatherTask"), "новые версии изменённых файлов получены (`git show head:path`)"));
        }
        Check ch2 = new Check("2. Ассистент получает diff и изменённые файлы", allOk(c2), c2);

        // ---- 3. review text: bugs / architecture / recommendations, RAG docs + code
        List<String> c3 = new ArrayList<>();
        CodeReviewer.Review r = rr == null ? null : rr.review();
        if (r == null || !r.ok()) {
            c3.add(mark(false, "ревью не получено" + (r != null ? ": " + r.error() : "")));
        } else {
            c3.add(mark(r.docFragments() > 0 && r.codeFragments() > 0, "RAG: в контексте " + r.docFragments()
                    + " фрагм. документации и " + r.codeFragments() + " фрагм. кода (" + sourcesOf(r) + ")"));
            boolean sections = rr.markdown().contains("### 🐞 Потенциальные баги") && rr.markdown().contains("### 🏗 Архитектурные проблемы")
                    && rr.markdown().contains("### 💡 Рекомендации");
            c3.add(mark(sections && !r.bugs().isEmpty() && !r.architecture().isEmpty() && !r.recommendations().isEmpty(),
                    "в ревью три раздела, все непустые: багов " + r.bugs().size() + ", архитектурных " + r.architecture().size()
                            + ", рекомендаций " + r.recommendations().size()));
            String bugHit = firstMatch(r.bugs(), PLANTED_BUG);
            c3.add(mark(bugHit != null, "найден заложенный баг: " + (bugHit == null ? "нет" : "«" + bugHit + "»")));
            String archHit = firstMatch(r.architecture(), PLANTED_ARCH);
            c3.add(mark(archHit != null, "найдено заложенное нарушение архитектуры: " + (archHit == null ? "нет" : "«" + archHit + "»")));
            Set<String> prFiles = Set.copyOf(r.pr().paths());
            boolean anchored = java.util.stream.Stream.of(r.bugs(), r.architecture(), r.recommendations()).flatMap(List::stream)
                    .allMatch(f -> f.file().isEmpty() || prFiles.contains(f.file()));
            c3.add(mark(anchored, "все замечания в ревью относятся к файлам PR"
                    + (r.dropped().isEmpty() ? "" : " (проверкой отброшено замечаний о файлах вне PR: " + r.dropped().size() + ")")));
        }
        Check ch3 = new Check("3. Ассистент возвращает ревью: баги, архитектурные проблемы, рекомендации (RAG по документации и коду)", allOk(c3), c3);

        List<Check> checks = List.of(ch1, ch2, ch3);
        boolean done = checks.stream().allMatch(Check::ok);
        writeReport(checks, done, pr, rr, wf);

        System.out.println("\n══ Итог проверки ══");
        for (Check c : checks) {
            System.out.println((c.ok() ? "✅ " : "❌ ") + c.what());
            c.criteria().forEach(x -> System.out.println("    " + x));
        }
        System.out.println("\n" + (done ? "✅ ЗАДАНИЕ ВЫПОЛНЕНО" : "❌ ЗАДАНИЕ НЕ ВЫПОЛНЕНО") + " — проверок "
                + checks.stream().filter(Check::ok).count() + "/3 → " + REPORT);
    }

    record DemoPr(String base, String head, List<String> files) {
    }

    /** A local clone of the project + branch demo/ai-review-pr with one commit = the demo PR. */
    static DemoPr demoPr(Path root) throws Exception {
        Path dir = root.resolve(DEMO_DIR);
        deleteTree(dir);
        Files.createDirectories(dir.getParent());
        git(root, "clone", "-q", "--no-hardlinks", root.toString(), dir.toString());
        String base = git(dir, "rev-parse", "HEAD").strip();
        git(dir, "checkout", "-q", "-b", "demo/ai-review-pr");
        Path code = dir.resolve(DEMO_FILE);
        Files.createDirectories(code.getParent());
        Files.writeString(code, DEMO_CODE, StandardCharsets.UTF_8);
        Path readme = dir.resolve("README.md");
        String old = Files.isRegularFile(readme) ? Files.readString(readme, StandardCharsets.UTF_8) : "# AIAdventChallenge\n";
        Files.writeString(readme, old + "\n## Демо: погода\n\n`DemoWeatherTask` — спрашивает у LLM погоду в городе: `java DemoWeatherTask Москва`.\n",
                StandardCharsets.UTF_8);
        git(dir, "add", DEMO_FILE, "README.md");
        git(dir, "-c", "user.name=AI Review Demo", "-c", "user.email=demo@example.com", "commit", "-q", "-m", "Demo PR: weather task");
        String head = git(dir, "rev-parse", "HEAD").strip();
        return new DemoPr(base, head, List.of("README.md", DEMO_FILE));
    }

    // ================================================================ report

    private static void writeReport(List<Check> checks, boolean done, DemoPr pr, ReviewRun rr, String wf) throws IOException {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        StringBuilder md = new StringBuilder();
        md.append("# День 32. Автоматизация ревью кода\n\n")
                .append("> Сгенерировано `Task32` (`mvn -q compile exec:java -Ptask32`) ").append(now).append(".\n\n")
                .append("## Вердикт: ").append(done ? "✅ задание выполнено" : "❌ задание не выполнено").append("\n\n")
                .append("| Проверка | Итог |\n|---|---|\n");
        for (Check c : checks) md.append("| ").append(c.what()).append(" | ").append(c.ok() ? "✅" : "❌").append(" |\n");
        md.append("""

                ## Как устроено

                ```
                PR открыт / обновлён
                   │  GitHub Actions: .github/workflows/ai-review.yml
                   ▼
                checkout (fetch-depth 0) → JDK 25 → mvn … -Ptask32 review --base <sha> --head <sha> --out target/ai-review.md
                   │
                   ▼  CodeReviewer
                   ├─ git diff base...head, --numstat, --name-status, git show head:<файл>   → diff + изменённые файлы
                   ├─ RAG: README.md + docs/ + AGENTS.md + src/main/java → task32-review-index.json
                   │       запросы по каждому файлу (путь + идентификаторы добавленных строк) + запрос о соглашениях
                   ├─ LLM → JSON {summary, verdict, bugs[], architecture[], recommendations[]}
                   └─ проверка в коде: файл из PR, строка — добавленная, ссылки [n] — из контекста → Markdown
                   │
                   ▼
                sticky-комментарий «🤖 AI-ревью PR» в PR (обновляется при новых коммитах)
                ```

                ## Проверки

                """);
        for (Check c : checks) {
            md.append("### ").append(c.ok() ? "✅ " : "❌ ").append(c.what()).append("\n\n");
            c.criteria().forEach(x -> md.append("- ").append(x).append('\n'));
            md.append('\n');
        }
        md.append("## Демо-PR\n\nЛокальный клон `").append(DEMO_DIR).append("`, ветка `demo/ai-review-pr`, `")
                .append(pr.base(), 0, 7).append("...").append(pr.head(), 0, 7).append("`. Новый класс с заложенными дефектами ")
                .append("(ключ в коде, свой HttpClient и JSON вместо `LlmClient`, `args[0]` без проверки, разбор ответа через `split` без проверки статуса) и правка README.\n\n```java\n")
                .append(DEMO_CODE).append("```\n\n");
        md.append("## Ревью, которое получит PR\n\n");
        md.append(rr == null ? "_ревью не запускалось_\n" : rr.markdown().replace("<!-- ai-review -->\n", "")
                .replaceAll("(?m)^## ", "### ").replaceAll("(?m)^### (🐞|🏗|💡)", "#### $1")).append('\n');
        md.append("## Workflow\n\n```yaml\n").append(wf).append("```\n");
        Files.writeString(REPORT, md.toString(), StandardCharsets.UTF_8);
    }

    // ================================================================ helpers

    private static String mark(boolean ok, String text) {
        return (ok ? "✅ " : "❌ ") + text;
    }

    private static boolean allOk(List<String> criteria) {
        return criteria.stream().allMatch(c -> c.startsWith("✅"));
    }

    private static String firstMatch(List<CodeReviewer.Finding> items, Pattern p) {
        for (CodeReviewer.Finding f : items) {
            if (p.matcher(f.title() + " " + f.details() + " " + f.suggestion()).find()) return f.title();
        }
        return null;
    }

    private static String sourcesOf(CodeReviewer.Review r) {
        return String.join(", ", r.context().stream().map(f -> f.chunk().source()).distinct().limit(6).toList());
    }

    static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + out.strip());
        }
        return out;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                file.toFile().setWritable(true);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
