package ru.lemanapro.aiadventchallenge.week6;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Day 26: a local LLM — Ollama + Qwen running on this machine, no cloud and no API key.
 *
 * What the demo proves (each step is a check in the report):
 *   1. the Ollama server runs locally (GET /api/version on localhost);
 *   2. the model is installed (GET /api/tags; pulled automatically if missing, TASK26_AUTO_PULL=0 disables);
 *   3. the model answers through the CLI (`ollama run <model> "<prompt>"` as a child process);
 *   4. the model answers through the HTTP API — native /api/chat (with timing metrics) and the
 *      OpenAI-compatible /v1/chat/completions through the project's own {@link LlmClient};
 *   5. three requests of increasing difficulty (fact → multi-step arithmetic → Java code), each answer is
 *      checked in code: a regex for the fact, the parsed number for the arithmetic, and for the code the
 *      method is extracted, COMPILED with javax.tools and run against test cases;
 *   6. /api/ps — the model is loaded into this machine's memory (CPU or GPU).
 * Writes task26-local-llm-report.md (installation guide + the results of this run).
 *
 * Env: OLLAMA_HOST (http://localhost:11434), TASK26_MODEL (qwen2.5:1.5b), OLLAMA_BIN (ollama),
 *      TASK26_AUTO_PULL (1).
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask26                          # full demo + report
 *   mvn -q exec:java -Ptask26 -Dexec.args="Что такое JVM?"     # one question to the local model
 */
public final class Task26 {

    private static final Path REPORT = Path.of("task26-local-llm-report.md");
    private static final int SEED = 42;
    private static final double TEMPERATURE = 0.0;

    private Task26() {
    }

    // ---------------------------------------------------------------- data

    /** One verified property of the setup. skipped = could not be checked here (not a failure). */
    record Check(String name, boolean ok, boolean skipped, String detail) {
        String mark() {
            return skipped ? "⏭ пропуск" : ok ? "✅" : "❌";
        }
    }

    record Verdict(boolean ok, String detail) {
    }

    /** A request of a given difficulty level with its own answer checker. */
    record Case(String level, String title, String why, String system, String prompt, int numPredict,
                String expectation, Function<String, Verdict> verify) {
    }

    record CaseRun(Case c, OllamaClient.ChatResult result, Verdict verdict, String error) {
    }

    record CliRun(String command, String output, long wallMs, int exitCode, Verdict verdict, String error) {
    }

    record OpenAiRun(String prompt, String answer, LlmClient.Usage usage, String finish, long wallMs,
                     Verdict verdict, String error) {
    }

    // ---------------------------------------------------------------- the three requests

    static final List<Case> CASES = List.of(
            new Case("1 · простой", "Факт из общих знаний",
                    "одна короткая фактическая выдача, без рассуждений",
                    null,
                    "Какая столица Франции? Ответь одним словом.",
                    32,
                    "в ответе есть «Париж» / «Paris»",
                    a -> Pattern.compile("(?iu)пари?ж|paris").matcher(a).find()
                            ? new Verdict(true, "назван Париж")
                            : new Verdict(false, "Париж не назван")),
            new Case("2 · средний", "Задача в несколько шагов",
                    "нужно удержать 3 операции подряд (умножение, вычитание, сложение) и дать итог в заданном формате",
                    null,
                    "У Маши было 3 коробки по 12 яблок. Она отдала соседям 17 яблок, а потом купила ещё 5. "
                            + "Сколько яблок у неё теперь? Реши по шагам, коротко. "
                            + "В последней строке напиши строго «Ответ: <число>».",
                    300,
                    "итоговая строка «Ответ: 24» (3·12 − 17 + 5 = 24)",
                    Task26::checkArithmetic),
            new Case("3 · сложный", "Генерация кода на Java",
                    "нужно понять спецификацию, учесть граничные случаи и выдать компилируемый код — "
                            + "проверяется компиляцией и тестами, а не на глаз",
                    "Ты опытный Java-разработчик. Отвечай только кодом в одном блоке ```java```, без пояснений.",
                    "Напиши на Java метод `public static boolean isPalindrome(String s)`. "
                            + "Метод должен игнорировать регистр и все символы, кроме букв и цифр; "
                            + "пустая строка и null считаются палиндромом. Только сам метод, без класса и без main.",
                    500,
                    "код компилируется и проходит 6 тестов (знаки препинания, регистр, пустая строка, null)",
                    Task26::checkPalindromeCode));

    private static Verdict checkArithmetic(String answer) {
        Matcher m = Pattern.compile("(?iu)ответ\\s*[:：]?\\s*\\**\\s*(-?\\d+)").matcher(answer);
        Integer last = null;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        if (last == null) {
            return new Verdict(false, "нет строки «Ответ: N»");
        }
        return last == 24
                ? new Verdict(true, "Ответ: 24 — верно")
                : new Verdict(false, "Ответ: " + last + " — неверно, ожидалось 24");
    }

    // ---------------------------------------------------------------- code check: compile + run tests

    private static final Object[][] PALINDROME_TESTS = {
            {"A man, a plan, a canal: Panama", true},
            {"race a car", false},
            {"No 'x' in Nixon", true},
            {"", true},
            {null, true},
            {"ab", false},
    };

    static Verdict checkPalindromeCode(String answer) {
        String code = extractCode(answer);
        Path dir = null;
        try {
            dir = Files.createTempDirectory("task26-code");
            List<String> imports = new ArrayList<>();
            StringBuilder body = new StringBuilder();
            for (String line : code.split("\n")) {
                if (line.trim().startsWith("import ") || line.trim().startsWith("package ")) {
                    if (line.trim().startsWith("import ")) {
                        imports.add(line.trim());
                    }
                } else {
                    body.append(line).append('\n');
                }
            }
            Matcher cls = Pattern.compile("\\bclass\\s+(\\w+)").matcher(body);
            String className;
            String source;
            if (cls.find()) {
                className = cls.group(1);
                source = String.join("\n", imports) + "\n" + body;
            } else {
                className = "Solution";
                source = String.join("\n", imports) + "\npublic class Solution {\n" + body + "\n}\n";
            }
            Path file = dir.resolve(className + ".java");
            Files.writeString(file, source, StandardCharsets.UTF_8);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return new Verdict(false, "нет javax.tools компилятора (запуск не на JDK)");
            }
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int rc = compiler.run(null, null, err, "-d", dir.toString(), "-proc:none", "-nowarn", file.toString());
            if (rc != 0) {
                String msg = err.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains("error"))
                        .findFirst().orElse("ошибка компиляции");
                return new Verdict(false, "не компилируется: " + msg.replace(dir + "/", ""));
            }
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},
                    Task26.class.getClassLoader())) {
                Class<?> type = loader.loadClass(className);
                Method method = type.getDeclaredMethod("isPalindrome", String.class);
                method.setAccessible(true);
                Object target = Modifier.isStatic(method.getModifiers()) ? null
                        : type.getDeclaredConstructor().newInstance();
                int passed = 0;
                List<String> failed = new ArrayList<>();
                ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "task26-test");
                    t.setDaemon(true);
                    return t;
                });
                try {
                    for (Object[] t : PALINDROME_TESTS) {
                        String in = (String) t[0];
                        boolean expected = (Boolean) t[1];
                        String label = in == null ? "null" : "\"" + in + "\"";
                        try {
                            Future<Object> f = ex.submit(() -> method.invoke(target, in));
                            Object got = f.get(5, TimeUnit.SECONDS);
                            if (Boolean.valueOf(expected).equals(got)) {
                                passed++;
                            } else {
                                failed.add(label + " → " + got + " (ожидалось " + expected + ")");
                            }
                        } catch (Exception e) {
                            Throwable cause = e.getCause() != null && e.getCause().getCause() != null
                                    ? e.getCause().getCause() : e.getCause() != null ? e.getCause() : e;
                            failed.add(label + " → " + cause.getClass().getSimpleName());
                        }
                    }
                } finally {
                    ex.shutdownNow();
                }
                String detail = "компилируется, тесты " + passed + "/" + PALINDROME_TESTS.length
                        + (failed.isEmpty() ? "" : "; не прошли: " + String.join("; ", failed));
                return new Verdict(failed.isEmpty(), detail);
            }
        } catch (NoSuchMethodException e) {
            return new Verdict(false, "в коде нет метода isPalindrome(String)");
        } catch (Exception e) {
            return new Verdict(false, "проверка упала: " + e);
        } finally {
            deleteQuietly(dir);
        }
    }

    /** First ``` block of the answer (any language tag), else the whole answer. */
    static String extractCode(String answer) {
        Matcher m = Pattern.compile("```[\\w+-]*\\s*\\n(.*?)```", Pattern.DOTALL).matcher(answer);
        return m.find() ? m.group(1) : answer;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temp dir, best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        String model = LlmClient.env("TASK26_MODEL", "qwen2.5:1.5b");
        String ollamaBin = LlmClient.env("OLLAMA_BIN", "ollama");
        boolean autoPull = !LlmClient.env("TASK26_AUTO_PULL", "1").matches("(?i)0|false|off|no");

        try (OllamaClient ollama = OllamaClient.fromEnv()) {
            if (args.length > 0) {
                askOnce(ollama, model, String.join(" ", args));
                return;
            }
            System.exit(runDemo(ollama, model, ollamaBin, autoPull));
        }
    }

    private static void askOnce(OllamaClient ollama, String model, String question) throws Exception {
        System.out.println("> " + question);
        OllamaClient.ChatResult r = ollama.chat(model, List.of(LlmClient.message("user", question)), null, null, null);
        System.out.println(r.content().strip());
        System.out.printf(Locale.ROOT, "[%s | ввод=%d ток. | вывод=%d ток. | %.1f ток/с | %.1f с]%n",
                model, r.promptTokens(), r.outputTokens(), r.tokensPerSecond(), r.totalSeconds());
    }

    private static int runDemo(OllamaClient ollama, String model, String ollamaBin, boolean autoPull) throws Exception {
        List<Check> checks = new ArrayList<>();
        System.out.println("=== День 26: локальная LLM — Ollama + " + model + " ===");
        System.out.println("Сервер: " + ollama.baseUrl());

        // 1. server
        String version;
        try {
            version = ollama.version();
        } catch (Exception e) {
            System.err.println("\n❌ " + e.getMessage());
            System.err.println("Установите и запустите Ollama (см. раздел «Установка» в " + REPORT + "):");
            System.err.println("  macOS:  brew install ollama && brew services start ollama");
            System.err.println("  Linux:  curl -fsSL https://ollama.com/install.sh | sh");
            System.err.println("  затем:  ollama pull " + model);
            return 1;
        }
        String host = URI.create(ollama.baseUrl()).getHost();
        boolean local = host.equals("localhost") || host.startsWith("127.") || host.equals("::1");
        checks.add(new Check("Сервер Ollama запущен локально", local, false,
                "GET " + ollama.baseUrl() + "/api/version → " + version + (local ? "" : " (хост не локальный!)")));
        System.out.println("✓ Ollama " + version);

        // 2. model
        OllamaClient.ModelInfo info = ollama.find(model);
        boolean pulled = false;
        if (info == null && autoPull) {
            System.out.println("… модели " + model + " нет, скачиваю (ollama pull " + model + ")");
            ollama.pull(model);
            info = ollama.find(model);
            pulled = true;
        }
        if (info == null) {
            System.err.println("❌ Модель " + model + " не установлена: ollama pull " + model);
            return 1;
        }
        checks.add(new Check("Модель установлена", true, false,
                info.name() + " — " + info.family() + ", " + info.parameterSize() + ", " + info.quantization()
                        + ", " + mb(info.sizeBytes()) + (pulled ? " (скачана этим запуском)" : "")));
        System.out.println("✓ модель " + info.name() + " (" + info.parameterSize() + ", " + info.quantization() + ", "
                + mb(info.sizeBytes()) + ")");

        // warm-up so that load time is measured separately from the three requests
        System.out.println("… загрузка модели в память");
        OllamaClient.ChatResult warm = ollama.chat(model, List.of(LlmClient.message("user", "Привет! Ответь одним словом.")),
                16, TEMPERATURE, SEED);
        System.out.printf(Locale.ROOT, "✓ модель загружена за %.1f с, ответ: %s%n", warm.loadSeconds(), oneLine(warm.content()));

        // 3. CLI
        CliRun cli = runCli(ollamaBin, model, ollama.baseUrl());
        checks.add(cli.error() != null && cli.error().startsWith("SKIP")
                ? new Check("Ответ через CLI (`ollama run`)", false, true, cli.error().substring(5))
                : new Check("Ответ через CLI (`ollama run`)", cli.verdict() != null && cli.verdict().ok(), false,
                cli.error() != null ? cli.error() : cli.verdict().detail() + ", " + cli.wallMs() + " мс"));
        System.out.println((checks.getLast().ok() ? "✓" : checks.getLast().skipped() ? "⏭" : "✗") + " CLI: "
                + (cli.output() != null ? oneLine(cli.output()) : checks.getLast().detail()));

        // 4a. three requests through the native HTTP API
        List<CaseRun> runs = new ArrayList<>();
        for (Case c : CASES) {
            System.out.println("\n--- Запрос " + c.level() + ": " + c.title() + " ---");
            System.out.println("> " + c.prompt());
            List<Map<String, String>> messages = new ArrayList<>();
            if (c.system() != null) {
                messages.add(LlmClient.message("system", c.system()));
            }
            messages.add(LlmClient.message("user", c.prompt()));
            try {
                OllamaClient.ChatResult r = ollama.chat(model, messages, c.numPredict(), TEMPERATURE, SEED);
                Verdict v = c.verify().apply(r.content());
                runs.add(new CaseRun(c, r, v, null));
                System.out.println(r.content().strip());
                System.out.printf(Locale.ROOT, "[ввод=%d | вывод=%d ток. | %.1f ток/с | %.1f с | %s] %s %s%n",
                        r.promptTokens(), r.outputTokens(), r.tokensPerSecond(), r.totalSeconds(), r.doneReason(),
                        v.ok() ? "✓" : "✗", v.detail());
            } catch (Exception e) {
                runs.add(new CaseRun(c, null, null, e.getMessage()));
                System.out.println("✗ ошибка: " + e.getMessage());
            }
        }
        for (CaseRun r : runs) {
            checks.add(new Check("HTTP /api/chat — запрос " + r.c().level(), r.result() != null
                    && !r.result().content().isBlank(), false,
                    r.result() == null ? r.error()
                            : "ответ получен (" + r.result().outputTokens() + " ток.); качество: "
                            + (r.verdict().ok() ? "верно" : "неверно") + " — " + r.verdict().detail()));
        }

        // 4b. OpenAI-compatible endpoint through the project's LlmClient
        OpenAiRun oa = runOpenAi(ollama, model);
        checks.add(new Check("HTTP /v1/chat/completions (OpenAI-совместимый, через LlmClient)",
                oa.error() == null && oa.verdict().ok(), false,
                oa.error() != null ? oa.error() : oa.verdict().detail() + ", usage " + oa.usage().promptTokens()
                        + "→" + oa.usage().completionTokens() + " ток."));
        System.out.println("\n" + (checks.getLast().ok() ? "✓" : "✗") + " OpenAI-совместимый API: "
                + (oa.answer() != null ? oneLine(oa.answer()) : oa.error()));

        // 5. loaded into local memory
        List<OllamaClient.LoadedModel> loaded = ollama.loaded();
        String installedName = info.name();
        OllamaClient.LoadedModel lm = loaded.stream().filter(m -> m.name().equals(installedName)).findFirst().orElse(null);
        checks.add(new Check("Модель загружена в память этой машины (/api/ps)", lm != null, false,
                lm == null ? "не найдена в /api/ps" : mb(lm.sizeBytes()) + ", " + placement(lm)
                        + ", контекст " + lm.contextLength()));

        boolean allOk = checks.stream().allMatch(c -> c.ok() || c.skipped());
        writeReport(ollama, version, info, warm, cli, runs, oa, lm, checks, allOk);

        System.out.println("\n=== Итог ===");
        for (Check c : checks) {
            System.out.println(c.mark() + " " + c.name() + " — " + c.detail());
        }
        long correct = runs.stream().filter(r -> r.verdict() != null && r.verdict().ok()).count();
        System.out.println("Качество ответов: " + correct + "/" + runs.size() + " верно");
        System.out.println((allOk ? "✅ Локальная LLM запущена и отвечает на запросы." : "❌ Есть непройденные проверки.")
                + " Отчёт: " + REPORT);
        return allOk ? 0 : 1;
    }

    // ---------------------------------------------------------------- CLI + OpenAI-compatible

    private static final String CLI_PROMPT = "Какого цвета небо в ясный день? Ответь одним словом.";

    private static CliRun runCli(String bin, String model, String baseUrl) {
        List<String> cmd = List.of(bin, "run", model, CLI_PROMPT);
        String shown = Path.of(bin).getFileName() + " run " + model + " \"" + CLI_PROMPT + "\"";
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
            pb.environment().put("OLLAMA_HOST", baseUrl);
            pb.environment().put("NO_COLOR", "1");
            long t0 = System.nanoTime();
            Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                return new CliRun(shown, null, 0, -1, null,
                        "SKIP бинарник `" + bin + "` не найден в PATH (задайте OLLAMA_BIN) — HTTP-проверки ниже не зависят от CLI");
            }
            p.getOutputStream().close();
            Future<byte[]> errDrain = Executors.newVirtualThreadPerTaskExecutor()
                    .submit(() -> p.getErrorStream().readAllBytes());
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return new CliRun(shown, null, 0, -1, null, "таймаут 5 мин");
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String text = stripAnsi(new String(out, StandardCharsets.UTF_8)).strip();
            if (p.exitValue() != 0) {
                String err = stripAnsi(new String(errDrain.get(), StandardCharsets.UTF_8)).strip();
                return new CliRun(shown, text, ms, p.exitValue(), null, "код выхода " + p.exitValue() + ": " + oneLine(err));
            }
            Verdict v = Pattern.compile("(?iu)голуб|син|blue").matcher(text).find()
                    ? new Verdict(true, "ответ по делу («" + oneLine(text) + "»)")
                    : new Verdict(!text.isBlank(), "ответ получен, но без ожидаемого цвета («" + oneLine(text) + "»)");
            return new CliRun(shown, text, ms, 0, v, null);
        } catch (Exception e) {
            return new CliRun(shown, null, 0, -1, null, e.toString());
        }
    }

    private static final String OPENAI_PROMPT = "Сколько будет 7 умножить на 8? Ответь только числом.";

    private static OpenAiRun runOpenAi(OllamaClient ollama, String model) {
        LlmClient.Config cfg = ollama.openAiConfig(model);
        try (HttpClient http = HttpClient.newHttpClient()) {
            long t0 = System.nanoTime();
            JsonNode resp = LlmClient.send(http, cfg, List.of(LlmClient.message("user", OPENAI_PROMPT)), 16, null, TEMPERATURE);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String answer = LlmClient.content(resp);
            Verdict v = answer.contains("56") ? new Verdict(true, "«" + oneLine(answer) + "» — верно")
                    : new Verdict(!answer.isBlank(), "«" + oneLine(answer) + "» — ответ есть, но не 56");
            return new OpenAiRun(OPENAI_PROMPT, answer, LlmClient.usage(resp), LlmClient.finishReason(resp), ms, v, null);
        } catch (LlmClient.RequestException e) {
            return new OpenAiRun(OPENAI_PROMPT, null, null, null, 0, null, "HTTP " + e.status() + ": " + e.body());
        } catch (Exception e) {
            return new OpenAiRun(OPENAI_PROMPT, null, null, null, 0, null, e.toString());
        }
    }

    // ---------------------------------------------------------------- report

    private static void writeReport(OllamaClient ollama, String version, OllamaClient.ModelInfo info,
                                    OllamaClient.ChatResult warm, CliRun cli, List<CaseRun> runs, OpenAiRun oa,
                                    OllamaClient.LoadedModel lm, List<Check> checks, boolean allOk) throws IOException {
        String model = info.name();
        StringBuilder r = new StringBuilder();
        r.append("# День 26. Локальная LLM: Ollama + Qwen\n\n");
        r.append("> Сгенерировано `Task26` (`mvn -q compile exec:java -Ptask26`) ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(". ")
                .append("Перезаписывается при каждом запуске.\n\n");
        r.append("**Результат:** ").append(allOk ? "✅ локальная LLM запущена и отвечает на запросы"
                : "❌ есть непройденные проверки").append(" — модель `").append(model).append("` на Ollama ")
                .append(version).append(", ").append(ollama.baseUrl()).append(", без облака и без API-ключа.\n\n");

        r.append("## Проверки\n\n| Проверка | Итог | Детали |\n|---|---|---|\n");
        for (Check c : checks) {
            r.append("| ").append(c.name()).append(" | ").append(c.mark()).append(" | ").append(cell(c.detail())).append(" |\n");
        }

        r.append("\n## Три запроса разной сложности\n\n");
        r.append("Все три — через нативный HTTP API `POST /api/chat`, `temperature=").append(TEMPERATURE)
                .append("`, `seed=").append(SEED).append("` (ответы воспроизводимы). ")
                .append("Ответ каждого проверяется кодом, а не на глаз.\n\n");
        r.append("| Уровень | Задача | Токены ввод → вывод | Время | Скорость | Ответ верен? |\n|---|---|---|---|---|---|\n");
        for (CaseRun run : runs) {
            if (run.result() == null) {
                r.append("| ").append(run.c().level()).append(" | ").append(run.c().title()).append(" | — | — | — | ❌ ")
                        .append(cell(run.error())).append(" |\n");
                continue;
            }
            OllamaClient.ChatResult x = run.result();
            r.append("| ").append(run.c().level()).append(" | ").append(run.c().title()).append(" | ")
                    .append(x.promptTokens()).append(" → ").append(x.outputTokens()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f с", x.totalSeconds())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f ток/с", x.tokensPerSecond())).append(" | ")
                    .append(run.verdict().ok() ? "✅ " : "❌ ").append(cell(run.verdict().detail())).append(" |\n");
        }
        for (CaseRun run : runs) {
            Case c = run.c();
            r.append("\n### Запрос ").append(c.level()).append(": ").append(c.title()).append("\n\n");
            r.append("*Почему этот уровень:* ").append(c.why()).append(".  \n");
            r.append("*Ожидание:* ").append(c.expectation()).append(".\n\n");
            if (c.system() != null) {
                r.append("**System:** ").append(c.system()).append("\n\n");
            }
            r.append("**Запрос:**\n\n").append(quote(c.prompt())).append("\n\n");
            if (run.result() == null) {
                r.append("**Ошибка:** ").append(run.error()).append("\n");
                continue;
            }
            OllamaClient.ChatResult x = run.result();
            r.append("**Ответ модели:**\n\n");
            if (x.content().contains("```")) {
                r.append(x.content().strip()).append("\n\n");
            } else {
                r.append(quote(x.content().strip())).append("\n\n");
            }
            r.append(String.format(Locale.ROOT,
                    "Метрики: ввод %d ток. (%.2f с), вывод %d ток. за %.2f с = **%.1f ток/с**, всего %.2f с, done_reason=`%s`.%n%n",
                    x.promptTokens(), x.promptEvalNs() / 1e9, x.outputTokens(), x.evalNs() / 1e9, x.tokensPerSecond(),
                    x.totalSeconds(), x.doneReason()));
            r.append("**Проверка:** ").append(run.verdict().ok() ? "✅ " : "❌ ").append(run.verdict().detail()).append("\n");
        }

        r.append("\n### Наблюдения\n\n");
        List<CaseRun> ok = runs.stream().filter(x -> x.result() != null).toList();
        if (!ok.isEmpty()) {
            double min = ok.stream().mapToDouble(x -> x.result().tokensPerSecond()).min().orElse(0);
            double max = ok.stream().mapToDouble(x -> x.result().tokensPerSecond()).max().orElse(0);
            r.append(String.format(Locale.ROOT, "- Скорость генерации %.0f–%.0f ток/с на %s; ", min, max,
                    lm != null ? placement(lm) : "этой машине"));
            r.append(String.format(Locale.ROOT, "загрузка модели в память — %.1f с (один раз, дальше она держится в памяти).%n",
                    warm.loadSeconds()));
        }
        long good = runs.stream().filter(x -> x.verdict() != null && x.verdict().ok()).count();
        r.append("- Ответ получен на все ").append(ok.size()).append(" запроса, верных — ").append(good).append(" из ")
                .append(runs.size()).append(".");
        for (CaseRun x : runs) {
            if (x.verdict() != null && !x.verdict().ok()) {
                r.append(" Ошибка на уровне «").append(x.c().level()).append("»: ").append(x.verdict().detail())
                        .append(" — модель на ").append(info.parameterSize())
                        .append(" параметров теряет часть требований спецификации; это видно только потому, что код реально компилируется и прогоняется на тестах.");
            }
        }
        r.append("\n- Для сложных задач (код, многошаговые рассуждения) стоит брать модель крупнее — см. таблицу выбора модели в инструкции ниже.\n");

        r.append("\n## Доступ через CLI\n\n");
        r.append("```bash\n").append(cli.command()).append("\n```\n\n");
        if (cli.output() != null) {
            r.append(quote(cli.output())).append("\n\n");
            r.append("Время: ").append(cli.wallMs()).append(" мс, код выхода ").append(cli.exitCode()).append(". ");
        }
        r.append("Итог: ").append(checks.stream().filter(c -> c.name().startsWith("Ответ через CLI")).findFirst()
                .map(c -> c.mark() + " " + c.detail()).orElse("")).append("\n");

        r.append("\n## Доступ через HTTP API\n\n");
        r.append("**Нативный API Ollama** — `POST ").append(ollama.baseUrl()).append("/api/chat` ")
                .append("(три запроса выше). Прогрев перед ними: загрузка модели в память заняла ")
                .append(String.format(Locale.ROOT, "%.1f с", warm.loadSeconds()))
                .append(" — поэтому время первого запроса после старта сервера заметно больше остальных.\n\n");
        r.append("**OpenAI-совместимый API** — `POST ").append(ollama.baseUrl())
                .append("/v1/chat/completions` через `LlmClient.send` проекта (тот же клиент, что у дней 1–25):\n\n");
        r.append(quote(oa.prompt())).append("\n\n");
        if (oa.error() != null) {
            r.append("Ошибка: ").append(oa.error()).append("\n");
        } else {
            r.append("Ответ: **").append(oneLine(oa.answer())).append("**, finish_reason=`").append(oa.finish())
                    .append("`, usage ").append(oa.usage().promptTokens()).append(" → ").append(oa.usage().completionTokens())
                    .append(" ток., ").append(oa.wallMs()).append(" мс. ")
                    .append(oa.verdict().ok() ? "✅" : "❌").append("\n");
        }

        r.append("\n## Окружение запуска\n\n| Параметр | Значение |\n|---|---|\n");
        r.append("| Сервер | `").append(ollama.baseUrl()).append("` (Ollama ").append(version).append(") |\n");
        r.append("| Модель | `").append(model).append("` — семейство ").append(info.family()).append(", ")
                .append(info.parameterSize()).append(" параметров, квантование ").append(info.quantization())
                .append(", формат ").append(info.format()).append(", ").append(mb(info.sizeBytes())).append(" на диске |\n");
        if (lm != null) {
            r.append("| В памяти | ").append(mb(lm.sizeBytes())).append(", ").append(placement(lm))
                    .append(", контекст ").append(lm.contextLength()).append(" ток. |\n");
        }
        r.append("| ОС / CPU | ").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.arch"))
                .append(", ").append(Runtime.getRuntime().availableProcessors()).append(" ядер |\n");
        r.append("| Java | ").append(System.getProperty("java.version")).append(" |\n");

        r.append("\n").append(INSTALL_GUIDE.replace("{MODEL}", model));
        Files.writeString(REPORT, r.toString(), StandardCharsets.UTF_8);
    }

    private static final String INSTALL_GUIDE = """
            ## Установка и запуск (инструкция)

            ### 1. Установить Ollama

            **macOS** (Apple Silicon — модель считается на GPU через Metal):

            ```bash
            brew install ollama
            brew services start ollama        # сервер в фоне, стартует вместе с системой
            # или: скачать Ollama.app с https://ollama.com/download и запустить — сервер поднимется сам
            ```

            **Linux:**

            ```bash
            curl -fsSL https://ollama.com/install.sh | sh   # ставит бинарник и systemd-сервис
            ```

            Без root (так сделано для прогона этого отчёта) — бинарник из релиза GitHub:

            ```bash
            mkdir -p ~/ollama && cd ~/ollama
            curl -L https://github.com/ollama/ollama/releases/latest/download/ollama-linux-arm64.tar.zst \\
              | zstd -d | tar x --exclude='*cuda*'          # для x86-64: ollama-linux-amd64.tar.zst
            ~/ollama/bin/ollama serve &                      # сервер на http://localhost:11434
            ```

            **Windows:** установщик `OllamaSetup.exe` с https://ollama.com/download.

            ### 2. Скачать модель Qwen

            ```bash
            ollama pull {MODEL}
            ollama list                         # модель должна появиться в списке
            ```

            Какую Qwen выбрать под объём памяти (квантование Q4, по умолчанию в Ollama):

            | Модель | Размер | Когда брать |
            |---|---|---|
            | `qwen2.5:0.5b` | ~0.4 ГБ | совсем слабая машина, проверка «оно вообще работает» |
            | `qwen2.5:1.5b` | ~1 ГБ | **по умолчанию в Task26** — быстро даже на CPU, 3–4 ГБ RAM |
            | `qwen2.5:7b` / `qwen2.5-coder:7b` | ~4.7 ГБ | 16 ГБ RAM: заметно лучше рассуждения и код |
            | `qwen3:8b` | ~5 ГБ | 16 ГБ RAM, «думающая» модель (выводит `<think>…</think>`) |

            ### 3. Проверить вручную

            ```bash
            curl http://localhost:11434/api/version                       # сервер жив
            ollama run {MODEL} "Привет! Ответь одним словом."            # CLI
            curl http://localhost:11434/api/chat -d '{
              "model": "{MODEL}", "stream": false,
              "messages": [{"role": "user", "content": "Столица Франции?"}]
            }'                                                            # нативный HTTP API
            curl http://localhost:11434/v1/chat/completions -H 'Content-Type: application/json' -d '{
              "model": "{MODEL}",
              "messages": [{"role": "user", "content": "Столица Франции?"}]
            }'                                                            # OpenAI-совместимый API
            ```

            ### 4. Запустить демо дня 26

            ```bash
            mvn -q compile exec:java -Ptask26                              # все проверки + этот отчёт
            mvn -q exec:java -Ptask26 -Dexec.args="Что такое JVM?"         # один вопрос локальной модели
            TASK26_MODEL=qwen2.5:7b mvn -q exec:java -Ptask26              # другая модель (скачается сама)
            ```

            Переменные: `OLLAMA_HOST` (по умолчанию `http://localhost:11434`), `TASK26_MODEL` (`qwen2.5:1.5b`),
            `OLLAMA_BIN` (путь к бинарнику `ollama`, если его нет в PATH), `TASK26_AUTO_PULL=0` — не скачивать модель автоматически.

            ### 5. Любой прошлый день — на локальной модели

            Ollama отдаёт OpenAI-совместимый `/v1`, поэтому `LlmClient` работает с ним без изменений:

            ```bash
            HINDSIGHT_API_LLM_BASE_URL=http://localhost:11434/v1 \\
            HINDSIGHT_API_LLM_MODEL={MODEL} LLM_API_KEY=ollama \\
            mvn -q exec:java -Ptask1
            ```

            Ключ Ollama не проверяет — `LLM_API_KEY` нужен только потому, что его требует `LlmClient.fromEnv()`.
            """;

    // ---------------------------------------------------------------- helpers

    private static String placement(OllamaClient.LoadedModel lm) {
        if (lm.sizeVram() <= 0) {
            return "100% CPU";
        }
        if (lm.sizeVram() >= lm.sizeBytes()) {
            return "100% GPU";
        }
        return Math.round(100.0 * lm.sizeVram() / lm.sizeBytes()) + "% GPU";
    }

    private static String mb(long bytes) {
        return bytes >= 1L << 30
                ? String.format(Locale.ROOT, "%.2f ГБ", bytes / (double) (1L << 30))
                : String.format(Locale.ROOT, "%d МБ", bytes >> 20);
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() > 120 ? t.substring(0, 117) + "…" : t;
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String quote(String s) {
        return s.lines().map(l -> "> " + l).reduce((a, b) -> a + "\n" + b).orElse(">");
    }

    /** `ollama run` draws a spinner with ANSI escapes even when stdout is not a terminal. */
    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").replaceAll("[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]", "");
    }
}
