package ru.lemanapro.aiadventchallenge.week4;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Day 18: scheduler and background jobs behind an MCP tool + an agent that runs 24/7.
 *
 * SchedulerMcpServer (stdio MCP server, official Java SDK) owns a background ScheduledExecutorService.
 * Jobs and every job run are persisted to a JSON file (task18-scheduler.json by default), so the
 * schedule survives restarts: on start the server reloads the file and re-arms every active job,
 * taking the time of its last run into account (no double runs after a quick restart).
 *
 * Job kinds:
 *   git_snapshot — periodic data collection: a snapshot of a git repository
 *                  (HEAD, commit count, branches -> sha, number of uncommitted files);
 *   reminder     — periodic or one-shot (once=true) reminder with a text message.
 *
 * Tools:
 *   schedule_job(name, kind, interval_seconds, message?, once?) — create or update (upsert by name)
 *   list_jobs()                                                  — jobs with runs / last / next run
 *   cancel_job(name)                                             — stop a job (history is kept)
 *   get_summary(since_minutes?, job?)                            — AGGREGATED result over a time window:
 *        snapshot count, commit count delta, the new commits themselves, branch changes
 *        (added / removed / moved), uncommitted files min/max/last, reminders fired, errors.
 *
 * Agent (Task18.main): starts the server, asks the LLM (McpToolAgent, tool calling through MCP) to set
 * up the jobs itself, then loops forever: every TASK18_SUMMARY_SECONDS it asks the LLM for a summary
 * of the last period — the LLM calls get_summary and writes a short human summary, which is printed
 * and appended to task18-summaries.md. A failing LLM call never stops the loop: the agent prints the
 * aggregated tool result directly instead; a dead MCP server is restarted. Stop with Ctrl+C.
 *
 * Env:
 *   TASK18_GIT_REPO          repository to monitor (default: current directory)
 *   TASK18_COLLECT_SECONDS   snapshot interval (default 60)
 *   TASK18_REMINDER_SECONDS  reminder interval (default 300)
 *   TASK18_SUMMARY_SECONDS   how often the agent prints a summary (default 120)
 *   TASK18_CYCLES            stop after N summaries (default 0 = run forever)
 *   TASK18_STORE             JSON store file (default task18-scheduler.json)
 *   TASK18_TOOL_MODE         auto | native | prompt (see McpToolAgent)
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask18
 *   TASK18_COLLECT_SECONDS=10 TASK18_SUMMARY_SECONDS=30 TASK18_CYCLES=3 mvn -q compile exec:java -Ptask18
 */
public final class Task18 {

    private static final Path SUMMARIES_FILE = Path.of("task18-summaries.md");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Task18() {
    }

    // =====================================================================================
    //  MCP SERVER: scheduler with background jobs + JSON persistence
    // =====================================================================================

    public static final class SchedulerMcpServer {

        private static final int MAX_EVENTS = 20_000;
        private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        /** One scheduled job. Mutable on purpose: runs/lastRunAt change after every execution. */
        public static final class Job {
            public String id;
            public String name;
            public String kind;
            public long intervalSeconds;
            public String message;
            public boolean once;
            public boolean active = true;
            public String createdAt;
            public String lastRunAt;
            public long runs;
        }

        /** One execution of a job: what was collected (snapshot) or what fired (reminder). */
        public static final class Event {
            public String jobId;
            public String jobName;
            public String kind;
            public String at;
            public Map<String, Object> data = new LinkedHashMap<>();
        }

        /** The whole persisted state (the JSON file). */
        public static final class Store {
            public int version = 1;
            public long nextJobNo = 1;
            public List<Job> jobs = new ArrayList<>();
            public List<Event> events = new ArrayList<>();
        }

        private final Path storeFile;
        private final Path repo;
        private final Store store;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });
        private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

        private SchedulerMcpServer(Path storeFile, Path repo) {
            this.storeFile = storeFile;
            this.repo = repo;
            this.store = load(storeFile);
        }

        public static void main(String[] args) throws Exception {
            Path storeFile = Path.of(args.length > 0 ? args[0] : "task18-scheduler.json").toAbsolutePath();
            Path repo = Path.of(args.length > 1 ? args[1] : ".").toAbsolutePath().normalize();
            System.err.println("[scheduler-mcp-server] MCP-сервер планировщика запущен, хранилище " + storeFile
                    + ". Сам по себе он ничего не выводит — его запускает агент: Task18.main или mvn -q compile exec:java -Ptask18");

            SchedulerMcpServer s = new SchedulerMcpServer(storeFile, repo);
            s.rearmAll();
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
                    .serverInfo("scheduler-mcp-server", "1.0.0")
                    .instructions("Планировщик фоновых заданий: периодический сбор данных о git-репозитории "
                            + repo.getFileName() + " и напоминания. Данные сохраняются в JSON, сводка — get_summary.")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .toolCall(McpSchema.Tool.builder()
                                    .name("schedule_job")
                                    .title("Запланировать задание")
                                    .description("Создаёт или обновляет (по имени) фоновое задание, которое сервер выполняет сам по "
                                            + "расписанию. kind=git_snapshot — периодический сбор данных о git-репозитории "
                                            + "(первый снимок сразу); kind=reminder — напоминание с текстом message "
                                            + "(once=true — один раз через interval_seconds).")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{
                                               "name":{"type":"string","description":"Уникальное имя задания, например repo-monitor"},
                                               "kind":{"type":"string","enum":["git_snapshot","reminder"],"description":"Тип задания"},
                                               "interval_seconds":{"type":"integer","minimum":5,"maximum":86400,
                                                                   "description":"Период выполнения в секундах (для once — задержка)"},
                                               "message":{"type":"string","description":"Текст напоминания (для kind=reminder)"},
                                               "once":{"type":"boolean","default":false,"description":"Выполнить один раз, а не периодически"}
                                             },
                                             "required":["name","kind","interval_seconds"],
                                             "additionalProperties":false}""")
                                    .build(),
                            (ex, req) -> s.safe(() -> s.scheduleJob(req.arguments())))
                    .toolCall(McpSchema.Tool.builder()
                                    .name("list_jobs")
                                    .title("Список заданий")
                                    .description("Все задания планировщика: тип, период, активно ли, сколько раз выполнено, "
                                            + "когда было последнее и когда будет следующее выполнение.")
                                    .inputSchema(json, "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                                    .build(),
                            (ex, req) -> s.safe(s::listJobs))
                    .toolCall(McpSchema.Tool.builder()
                                    .name("cancel_job")
                                    .title("Остановить задание")
                                    .description("Останавливает задание по имени. Собранные данные остаются в хранилище.")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{"name":{"type":"string","description":"Имя задания"}},
                                             "required":["name"],
                                             "additionalProperties":false}""")
                                    .build(),
                            (ex, req) -> s.safe(() -> s.cancelJob(req.arguments())))
                    .toolCall(McpSchema.Tool.builder()
                                    .name("get_summary")
                                    .title("Сводка за период")
                                    .description("Агрегированная сводка по данным, собранным фоновыми заданиями за последние "
                                            + "since_minutes минут: число снимков, прирост коммитов и сами новые коммиты, "
                                            + "изменения веток, незакоммиченные файлы (мин/макс/сейчас), сработавшие напоминания, ошибки.")
                                    .inputSchema(json, """
                                            {"type":"object",
                                             "properties":{
                                               "since_minutes":{"type":"number","minimum":0.1,"maximum":10080,"default":60,
                                                                "description":"Окно сводки в минутах (можно дробное, например 0.5)"},
                                               "job":{"type":"string","description":"Только по этому заданию (имя); по умолчанию — по всем"}
                                             },
                                             "additionalProperties":false}""")
                                    .build(),
                            (ex, req) -> s.safe(() -> s.summary(req.arguments())))
                    .build();

            stdinClosed.await();
            s.scheduler.shutdownNow();
            s.save();
            server.closeGracefully();
            System.exit(0);
        }

        // ---------------------------------------------------------------- scheduling

        /** On start: re-arm every active job, continuing from its last run instead of starting over. */
        private synchronized void rearmAll() {
            for (Job j : store.jobs) {
                if (j.active) arm(j);
            }
            System.err.println("[scheduler-mcp-server] восстановлено активных заданий: "
                    + store.jobs.stream().filter(j -> j.active).count() + ", событий в истории: " + store.events.size());
        }

        private synchronized void arm(Job j) {
            ScheduledFuture<?> old = futures.remove(j.id);
            if (old != null) old.cancel(false);
            long delay = initialDelaySeconds(j);
            ScheduledFuture<?> f = j.once
                    ? scheduler.schedule(() -> runJob(j.id), delay, TimeUnit.SECONDS)
                    : scheduler.scheduleAtFixedRate(() -> runJob(j.id), delay, j.intervalSeconds, TimeUnit.SECONDS);
            futures.put(j.id, f);
        }

        private static long initialDelaySeconds(Job j) {
            if (j.lastRunAt == null) {
                return "git_snapshot".equals(j.kind) && !j.once ? 0 : j.intervalSeconds;
            }
            long sinceLast = Duration.between(Instant.parse(j.lastRunAt), Instant.now()).toSeconds();
            return Math.max(0, j.intervalSeconds - sinceLast);
        }

        /** Executed on the scheduler thread. Never throws: a failed run is recorded as an event with "error". */
        private void runJob(String jobId) {
            Job j;
            synchronized (this) {
                j = store.jobs.stream().filter(x -> x.id.equals(jobId) && x.active).findFirst().orElse(null);
            }
            if (j == null) return;
            Event e = new Event();
            e.jobId = j.id;
            e.jobName = j.name;
            e.kind = j.kind;
            e.at = Instant.now().toString();
            try {
                if ("git_snapshot".equals(j.kind)) {
                    e.data.putAll(gitSnapshot());
                } else {
                    e.data.put("message", j.message == null ? "" : j.message);
                }
            } catch (Exception ex) {
                e.data.put("error", String.valueOf(ex.getMessage()));
            }
            synchronized (this) {
                store.events.add(e);
                if (store.events.size() > MAX_EVENTS) {
                    store.events.subList(0, store.events.size() - MAX_EVENTS).clear();
                }
                j.runs++;
                j.lastRunAt = e.at;
                if (j.once) {
                    j.active = false;
                    futures.remove(j.id);
                }
                save();
            }
            System.err.println("[scheduler-mcp-server] выполнено " + j.name + " (" + j.kind + ") в " + e.at);
        }

        // ---------------------------------------------------------------- tools

        private synchronized McpSchema.CallToolResult scheduleJob(Map<String, Object> a) {
            String name = str(a, "name");
            String kind = str(a, "kind");
            long interval = (long) num(a, "interval_seconds", 60);
            if (name == null) return error("name обязателен");
            if (!"git_snapshot".equals(kind) && !"reminder".equals(kind)) return error("kind должен быть git_snapshot или reminder");
            if (interval < 5 || interval > 86_400) return error("interval_seconds должен быть в диапазоне 5..86400");

            Job j = store.jobs.stream().filter(x -> x.name.equals(name)).findFirst().orElse(null);
            boolean created = j == null;
            if (created) {
                j = new Job();
                j.id = "j" + store.nextJobNo++;
                j.name = name;
                j.createdAt = Instant.now().toString();
                store.jobs.add(j);
            }
            j.kind = kind;
            j.intervalSeconds = interval;
            j.message = str(a, "message");
            j.once = bool(a, "once");
            j.active = true;
            arm(j);
            save();
            String text = (created ? "Создано" : "Обновлено") + " задание " + j.name + " (" + j.id + "): " + describe(j)
                    + ". Следующее выполнение: " + nextRun(j) + ".";
            return ok(text, jobView(j));
        }

        private synchronized McpSchema.CallToolResult listJobs() {
            StringBuilder text = new StringBuilder("Заданий: " + store.jobs.size() + "\n");
            List<Map<String, Object>> list = new ArrayList<>();
            for (Job j : store.jobs) {
                text.append("- ").append(j.name).append(" [").append(j.active ? "активно" : "остановлено").append("] ")
                        .append(describe(j)).append("; выполнено ").append(j.runs).append(" раз; последнее ")
                        .append(j.lastRunAt == null ? "—" : local(j.lastRunAt)).append("; следующее ").append(nextRun(j)).append('\n');
                list.add(jobView(j));
            }
            return ok(text.toString(), Map.of("jobs", list));
        }

        private synchronized McpSchema.CallToolResult cancelJob(Map<String, Object> a) {
            String name = str(a, "name");
            Job j = store.jobs.stream().filter(x -> x.name.equals(name)).findFirst().orElse(null);
            if (j == null) return error("Задание не найдено: " + name);
            j.active = false;
            ScheduledFuture<?> f = futures.remove(j.id);
            if (f != null) f.cancel(false);
            save();
            return ok("Задание " + name + " остановлено (выполнено " + j.runs + " раз, данные сохранены).", jobView(j));
        }

        /** The aggregation: raw events of the window -> numbers, deltas and lists. */
        private McpSchema.CallToolResult summary(Map<String, Object> a) throws Exception {
            double minutes = num(a, "since_minutes", 60);
            String onlyJob = str(a, "job");
            Instant to = Instant.now();
            Instant from = to.minusMillis((long) (minutes * 60_000));
            List<Event> window;
            Event baseline; // last successful snapshot BEFORE the window: changes are measured from it,
                            // so a commit made between two summaries is never lost at the window boundary
            synchronized (this) {
                window = store.events.stream()
                        .filter(e -> !Instant.parse(e.at).isBefore(from))
                        .filter(e -> onlyJob == null || onlyJob.equals(e.jobName))
                        .toList();
                baseline = store.events.stream()
                        .filter(e -> Instant.parse(e.at).isBefore(from))
                        .filter(e -> "git_snapshot".equals(e.kind) && !e.data.containsKey("error"))
                        .filter(e -> onlyJob == null || onlyJob.equals(e.jobName))
                        .reduce((x, y) -> y).orElse(null);
            }
            List<Event> snaps = window.stream().filter(e -> "git_snapshot".equals(e.kind) && !e.data.containsKey("error")).toList();
            List<Event> reminders = window.stream().filter(e -> "reminder".equals(e.kind)).toList();
            List<Event> errors = window.stream().filter(e -> e.data.containsKey("error")).toList();

            Map<String, Object> agg = new LinkedHashMap<>();
            agg.put("from", from.toString());
            agg.put("to", to.toString());
            agg.put("window_minutes", minutes);
            agg.put("events", window.size());
            agg.put("snapshots", snaps.size());
            agg.put("reminders_fired", reminders.size());
            agg.put("errors", errors.size());

            StringBuilder text = new StringBuilder();
            text.append("Сводка за последние ").append(fmtMinutes(minutes)).append(" мин (").append(local(from.toString()))
                    .append(" — ").append(local(to.toString())).append(")").append(onlyJob != null ? ", задание " + onlyJob : "").append('\n');
            text.append("Событий: ").append(window.size()).append(" (снимков репозитория: ").append(snaps.size())
                    .append(", напоминаний: ").append(reminders.size()).append(", ошибок: ").append(errors.size()).append(")\n");

            if (!snaps.isEmpty()) {
                Event first = baseline != null ? baseline : snaps.getFirst();
                Event last = snaps.getLast();
                long c0 = ((Number) first.data.get("commit_count")).longValue();
                long c1 = ((Number) last.data.get("commit_count")).longValue();
                String h0 = String.valueOf(first.data.get("head_sha"));
                String h1 = String.valueOf(last.data.get("head_sha"));
                List<Map<String, Object>> newCommits = new ArrayList<>();
                if (!h0.equals(h1)) {
                    try {
                        for (String line : git(List.of("log", "--pretty=format:%h\u001f%an\u001f%s", h0 + ".." + h1)).split("\n")) {
                            String[] f = line.split("\u001f", -1);
                            if (f.length == 3) newCommits.add(Map.of("short_sha", f[0], "author", f[1], "subject", f[2]));
                        }
                    } catch (Exception ex) {
                        // e.g. history rewritten: the old HEAD is no longer reachable — keep the counts only
                    }
                }
                Map<String, String> b0 = branches(first);
                Map<String, String> b1 = branches(last);
                List<String> added = b1.keySet().stream().filter(b -> !b0.containsKey(b)).toList();
                List<String> removed = b0.keySet().stream().filter(b -> !b1.containsKey(b)).toList();
                List<String> moved = b1.keySet().stream().filter(b -> b0.containsKey(b) && !b0.get(b).equals(b1.get(b)))
                        .map(b -> b + " " + b0.get(b) + "→" + b1.get(b)).toList();
                List<Integer> dirty = snaps.stream().map(e -> ((Number) e.data.get("dirty_files")).intValue()).toList();

                Map<String, Object> git = new LinkedHashMap<>();
                git.put("baseline_snapshot", first.at);
                git.put("baseline_is_before_window", baseline != null);
                git.put("last_snapshot", last.at);
                git.put("current_branch", last.data.get("current_branch"));
                git.put("head", last.data.get("head_short") + " " + last.data.get("head_subject"));
                git.put("commit_count_start", c0);
                git.put("commit_count_end", c1);
                git.put("new_commits_count", c1 - c0);
                git.put("new_commits", newCommits);
                git.put("branches_total", b1.size());
                git.put("branches_added", added);
                git.put("branches_removed", removed);
                git.put("branches_moved", moved);
                git.put("dirty_files_min", dirty.stream().mapToInt(Integer::intValue).min().orElse(0));
                git.put("dirty_files_max", dirty.stream().mapToInt(Integer::intValue).max().orElse(0));
                git.put("dirty_files_now", dirty.getLast());
                agg.put("git", git);

                text.append("Репозиторий: ветка ").append(last.data.get("current_branch")).append(", HEAD ")
                        .append(git.get("head")).append('\n');
                text.append("Коммитов в HEAD: ").append(c0).append(" → ").append(c1).append(" (новых: ").append(c1 - c0)
                        .append(baseline != null ? ", относительно снимка " + local(first.at) + " до начала периода" : "")
                        .append(")\n");
                for (Map<String, Object> c : newCommits) {
                    text.append("  + ").append(c.get("short_sha")).append(" ").append(c.get("subject"))
                            .append(" (").append(c.get("author")).append(")\n");
                }
                text.append("Ветки: всего ").append(b1.size())
                        .append(added.isEmpty() ? "" : "; новые: " + String.join(", ", added))
                        .append(removed.isEmpty() ? "" : "; удалены: " + String.join(", ", removed))
                        .append(moved.isEmpty() ? "" : "; сдвинулись: " + String.join(", ", moved))
                        .append(added.isEmpty() && removed.isEmpty() && moved.isEmpty() ? " (без изменений)" : "").append('\n');
                text.append("Незакоммиченных файлов: сейчас ").append(git.get("dirty_files_now")).append(", мин ")
                        .append(git.get("dirty_files_min")).append(", макс ").append(git.get("dirty_files_max")).append('\n');
            } else {
                text.append("Снимков репозитория за период нет.\n");
            }

            if (!reminders.isEmpty()) {
                Map<String, List<String>> byText = new LinkedHashMap<>();
                for (Event e : reminders) {
                    byText.computeIfAbsent(String.valueOf(e.data.get("message")), k -> new ArrayList<>()).add(local(e.at));
                }
                List<Map<String, Object>> rs = new ArrayList<>();
                text.append("Напоминания:\n");
                byText.forEach((msg, times) -> {
                    text.append("  • «").append(msg).append("» — ").append(times.size()).append(" раз, последнее в ")
                            .append(times.getLast()).append('\n');
                    rs.add(Map.of("message", msg, "count", times.size(), "last", times.getLast()));
                });
                agg.put("reminders", rs);
            }
            if (!errors.isEmpty()) {
                text.append("Ошибки: ").append(errors.getLast().data.get("error")).append('\n');
            }
            Map<String, Object> perJob = new LinkedHashMap<>();
            for (Event e : window) perJob.merge(e.jobName, 1, (x, y) -> (Integer) x + (Integer) y);
            agg.put("runs_per_job", perJob);
            return ok(text.toString(), agg);
        }

        // ---------------------------------------------------------------- data collection

        private Map<String, Object> gitSnapshot() throws Exception {
            Map<String, Object> d = new LinkedHashMap<>();
            String[] head = git(List.of("log", "-1", "--date=iso-strict", "--pretty=format:%H\u001f%h\u001f%s\u001f%ad")).split("\u001f", -1);
            d.put("head_sha", head[0]);
            d.put("head_short", head[1]);
            d.put("head_subject", head[2]);
            d.put("head_date", head[3]);
            d.put("commit_count", Long.parseLong(git(List.of("rev-list", "--count", "HEAD")).strip()));
            d.put("current_branch", git(List.of("branch", "--show-current")).strip());
            Map<String, String> branches = new LinkedHashMap<>();
            for (String line : git(List.of("for-each-ref", "--format=%(refname:short) %(objectname:short)", "refs/heads")).split("\n")) {
                String[] p = line.strip().split(" ");
                if (p.length == 2) branches.put(p[0], p[1]);
            }
            d.put("branches", branches);
            d.put("dirty_files", git(List.of("status", "--porcelain")).lines().filter(l -> !l.isBlank()).count());
            return d;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, String> branches(Event e) {
            Object b = e.data.get("branches");
            return b instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
        }

        private String git(List<String> args) throws Exception {
            List<String> cmd = new ArrayList<>(List.of("git", "-C", repo.toString(), "--no-pager"));
            cmd.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
            Process p = pb.start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("git не ответил за 30 с");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("git " + args.getFirst() + ": " + (err.isEmpty() ? "код " + p.exitValue() : err));
            }
            return new String(out, StandardCharsets.UTF_8);
        }

        // ---------------------------------------------------------------- persistence

        private static Store load(Path file) {
            if (!Files.isRegularFile(file)) return new Store();
            try {
                return JSON.readValue(file.toFile(), Store.class);
            } catch (Exception e) {
                System.err.println("[scheduler-mcp-server] не удалось прочитать " + file + " (" + e.getMessage() + ") — начинаю с пустого");
                return new Store();
            }
        }

        /** Atomic write: temp file + move, so a crash mid-write never leaves a broken JSON. */
        private synchronized void save() {
            try {
                Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
                JSON.writeValue(tmp.toFile(), store);
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                System.err.println("[scheduler-mcp-server] не удалось сохранить " + storeFile + ": " + e.getMessage());
            }
        }

        // ---------------------------------------------------------------- helpers

        interface Handler {
            McpSchema.CallToolResult run() throws Exception;
        }

        McpSchema.CallToolResult safe(Handler h) {
            try {
                return h.run();
            } catch (Exception e) {
                return error(String.valueOf(e.getMessage()));
            }
        }

        private String nextRun(Job j) {
            if (!j.active) return "—";
            ScheduledFuture<?> f = futures.get(j.id);
            if (f == null) return "—";
            return local(Instant.now().plusMillis(Math.max(0, f.getDelay(TimeUnit.MILLISECONDS))).toString());
        }

        private static String describe(Job j) {
            return j.kind + (j.once ? ", однократно через " : ", каждые ") + j.intervalSeconds + " с"
                    + (j.message != null ? ", «" + j.message + "»" : "");
        }

        private Map<String, Object> jobView(Job j) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.id);
            m.put("name", j.name);
            m.put("kind", j.kind);
            m.put("interval_seconds", j.intervalSeconds);
            m.put("once", j.once);
            m.put("active", j.active);
            m.put("message", j.message == null ? "" : j.message);
            m.put("runs", j.runs);
            m.put("last_run_at", j.lastRunAt == null ? "" : j.lastRunAt);
            m.put("next_run_at", nextRun(j));
            return m;
        }

        private static McpSchema.CallToolResult ok(String text, Object structured) {
            return McpSchema.CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(false).build();
        }

        private static McpSchema.CallToolResult error(String message) {
            return McpSchema.CallToolResult.builder().addTextContent("Ошибка: " + message).isError(true).build();
        }

        private static String str(Map<String, Object> a, String k) {
            Object v = a == null ? null : a.get(k);
            return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
        }

        private static double num(Map<String, Object> a, String k, double def) {
            Object v = a == null ? null : a.get(k);
            if (v instanceof Number n) return n.doubleValue();
            try {
                return v == null ? def : Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static boolean bool(Map<String, Object> a, String k) {
            Object v = a == null ? null : a.get(k);
            return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
        }
    }

    // =====================================================================================
    //  AGENT 24/7
    // =====================================================================================

    private static final String AGENT_PROMPT = """
            Ты фоновый агент-наблюдатель за git-репозиторием. У тебя есть MCP-сервер scheduler-mcp-server с планировщиком:
            он сам по расписанию собирает данные о репозитории и шлёт напоминания, всё сохраняет, а get_summary отдаёт агрегат.
            Правила:
            - Факты (числа, хеши, ветки, напоминания) бери ТОЛЬКО из результатов инструментов, не выдумывай.
            - Не создавай дубликаты заданий: schedule_job с тем же name обновляет существующее.
            - Отвечай по-русски, кратко.""";

    private static final class Session implements AutoCloseable {
        final McpSyncClient mcp;
        final McpToolAgent agent;

        Session(LlmClient.Config cfg, ServerParameters params, String toolMode) {
            this.mcp = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                    .clientInfo(new McpSchema.Implementation("ai-advent-task18-agent", "1.0.0"))
                    .requestTimeout(Duration.ofSeconds(15))
                    .build();
            McpSchema.InitializeResult init = mcp.initialize();
            System.out.println("[" + now() + "] [OK] MCP-соединение: " + init.serverInfo().name() + " "
                    + init.serverInfo().version() + ", протокол " + init.protocolVersion());
            this.agent = new McpToolAgent(cfg, mcp, toolMode, AGENT_PROMPT);
        }

        McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
            return mcp.callTool(new McpSchema.CallToolRequest(tool, args));
        }

        @Override
        public void close() {
            try {
                mcp.closeGracefully();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        Path repo = Path.of(LlmClient.env("TASK18_GIT_REPO", ".")).toAbsolutePath().normalize();
        Path store = Path.of(LlmClient.env("TASK18_STORE", "task18-scheduler.json")).toAbsolutePath();
        int collect = Integer.parseInt(LlmClient.env("TASK18_COLLECT_SECONDS", "60"));
        int reminder = Integer.parseInt(LlmClient.env("TASK18_REMINDER_SECONDS", "300"));
        int summaryEvery = Integer.parseInt(LlmClient.env("TASK18_SUMMARY_SECONDS", "120"));
        int cycles = Integer.parseInt(LlmClient.env("TASK18_CYCLES", "0"));
        String toolMode = LlmClient.env("TASK18_TOOL_MODE", "auto");
        ServerParameters params = McpLaunch.javaServer(SchedulerMcpServer.class, store.toString(), repo.toString());

        System.out.println("=== День 18. Планировщик и фоновые задачи (MCP) — агент 24/7 ===");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println("Репозиторий: " + repo);
        System.out.println("Хранилище заданий и данных: " + store);
        System.out.println("Сбор данных каждые " + collect + " с, напоминание каждые " + reminder + " с, сводка каждые "
                + summaryEvery + " с" + (cycles > 0 ? ", сводок: " + cycles : ", без ограничения (Ctrl+C — остановить)"));
        System.out.println();

        Session[] session = {new Session(cfg, params, toolMode)};
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[" + now() + "] Агент остановлен. Задания и данные сохранены в " + store
                    + " — при следующем запуске планировщик продолжит с того же места.");
            session[0].close();
        }));

        // ---- 1. the agent sets the jobs up itself, through MCP tools
        System.out.println("--- Настройка фоновых заданий (агент) ---");
        String setup = "Настрой фоновые задания. Сначала посмотри текущие через list_jobs. Затем через schedule_job создай или обнови: "
                + "1) name=repo-monitor, kind=git_snapshot, interval_seconds=" + collect + "; "
                + "2) name=commit-reminder, kind=reminder, interval_seconds=" + reminder
                + ", message=\"Проверь незакоммиченные изменения и закоммить готовое\". Кратко подтверди, что настроено.";
        try {
            McpToolAgent.AgentAnswer a = session[0].agent.ask(setup);
            System.out.println("Агент: " + a.text().strip());
        } catch (Exception e) {
            System.out.println("[" + now() + "] LLM недоступна при настройке (" + e + ")");
        }
        ensureJob(session[0], "repo-monitor", "git_snapshot", collect, null);
        ensureJob(session[0], "commit-reminder", "reminder", reminder, "Проверь незакоммиченные изменения и закоммить готовое");
        System.out.println(McpToolAgent.textOf(session[0].call("list_jobs", Map.of())).strip());

        appendSummary("\n## Запуск " + LocalDateTime.now().format(DATE_TIME) + "\n\nРепозиторий `" + repo.getFileName()
                + "`, сбор каждые " + collect + " с, напоминание каждые " + reminder + " с, сводка каждые " + summaryEvery + " с.\n");

        // ---- 2. 24/7 loop: every period the agent produces a summary from the aggregated tool result
        double windowMinutes = summaryEvery / 60.0;
        for (int n = 1; cycles == 0 || n <= cycles; n++) {
            Thread.sleep(summaryEvery * 1000L);
            System.out.println("\n=== [" + now() + "] Сводка #" + n + " ===");
            try {
                session[0].mcp.ping(); // cheap liveness check: a dead server is restarted before the LLM spends a call on it
            } catch (Exception dead) {
                System.out.println("   [агент] MCP-сервер не отвечает на ping (" + dead.getMessage() + ") — перезапускаю его");
                session[0].close();
                session[0] = new Session(cfg, params, toolMode);
            }
            String question = "Сделай сводку за последний период: вызови get_summary с since_minutes=" + fmtMinutes(windowMinutes)
                    + ". Коротко (3-6 строк): сколько снимков собрано, появились ли новые коммиты (хеши и заголовки), "
                    + "что изменилось в ветках, сколько незакоммиченных файлов, какие напоминания сработали. "
                    + "Если изменений не было — так и скажи.";
            String text;
            String source;
            try {
                McpToolAgent.AgentAnswer a = session[0].agent.ask(question);
                boolean usedTool = a.calls().stream().anyMatch(c -> c.tool().equals("get_summary") && !c.isError());
                if (!usedTool) throw new IllegalStateException("LLM не вызвала get_summary");
                text = a.text().strip();
                source = "LLM (" + a.calls().size() + " вызов(а) MCP)";
            } catch (Exception e) {
                System.out.println("   [агент] сводка через LLM не получилась: " + e.getMessage() + " — вывожу агрегат инструмента напрямую");
                try {
                    text = McpToolAgent.textOf(session[0].call("get_summary", Map.of("since_minutes", windowMinutes))).strip();
                    source = "get_summary напрямую (без LLM)";
                } catch (Exception mcpDown) {
                    System.out.println("   [агент] MCP-сервер не отвечает (" + mcpDown.getMessage() + ") — перезапускаю соединение");
                    session[0].close();
                    session[0] = new Session(cfg, params, toolMode);
                    n--;
                    continue;
                }
            }
            System.out.println(text);
            appendSummary("\n### " + LocalDateTime.now().format(DATE_TIME) + " — сводка #" + n + " (" + source + ")\n\n" + text + "\n");
        }
        System.out.println("\n[" + now() + "] Выполнено сводок: " + cycles + ". Журнал: " + SUMMARIES_FILE.toAbsolutePath());
        System.exit(0);
    }

    /** Safety net: if the LLM didn't create a job during setup, the application creates it directly. */
    @SuppressWarnings("unchecked")
    private static void ensureJob(Session s, String name, String kind, int interval, String message) {
        Object sc = s.call("list_jobs", Map.of()).structuredContent();
        List<Map<String, Object>> jobs = sc instanceof Map<?, ?> m ? (List<Map<String, Object>>) m.get("jobs") : List.of();
        boolean ok = jobs.stream().anyMatch(j -> name.equals(j.get("name")) && Boolean.TRUE.equals(j.get("active"))
                && kind.equals(j.get("kind")) && ((Number) j.get("interval_seconds")).intValue() == interval);
        if (ok) return;
        System.out.println("[" + now() + "] задание " + name + " не настроено агентом — приложение создаёт его напрямую");
        Map<String, Object> a = new LinkedHashMap<>(Map.of("name", name, "kind", kind, "interval_seconds", interval));
        if (message != null) a.put("message", message);
        s.call("schedule_job", a);
    }

    private static void appendSummary(String md) {
        try {
            if (!Files.exists(SUMMARIES_FILE)) {
                Files.writeString(SUMMARIES_FILE, "# День 18. Сводки фонового агента\n\nЖурнал дописывается агентом "
                        + "(`mvn -q compile exec:java -Ptask18`) при каждой сводке.\n", StandardCharsets.UTF_8);
            }
            Files.writeString(SUMMARIES_FILE, md, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Не удалось записать " + SUMMARIES_FILE + ": " + e.getMessage());
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TIME);
    }

    static String local(String isoInstant) {
        return Instant.parse(isoInstant).atZone(ZoneId.systemDefault()).toLocalTime().withNano(0).toString();
    }

    static String fmtMinutes(double m) {
        return m == Math.rint(m) ? String.valueOf((long) m) : String.format(java.util.Locale.ROOT, "%.2f", m);
    }
}
