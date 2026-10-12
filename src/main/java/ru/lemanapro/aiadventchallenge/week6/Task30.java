package ru.lemanapro.aiadventchallenge.week6;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Day 30: the local LLM as a private service — «Приватный AI-сервис».
 *
 * A gateway in front of Ollama. Ollama itself stays on localhost; only the gateway is exposed to the network:
 *   GET  /                      — chat page (enter the API key once, it is kept in the browser)
 *   GET  /health                — no auth: status, model, Ollama version, current load
 *   GET  /v1/models             — OpenAI-compatible list (the one allowed model)
 *   POST /v1/chat/completions   — OpenAI-compatible chat, stream=false (JSON) or stream=true (SSE), Bearer key
 * Any OpenAI client works with it: base_url = http://<server>:8030/v1, api_key = one of the keys.
 *
 * Limits (all configurable, all returned as proper HTTP errors in the OpenAI error format):
 *   401 — no / wrong key (keys: TASK30_API_KEYS="key1:30,key2:5" = key:requests-per-minute; if unset a random key
 *         is generated and printed);
 *   429 — rate limit: sliding 60 s window per key (default TASK30_RPM=20), header Retry-After;
 *   503 — all generation slots busy (TASK30_MAX_CONCURRENT=2) and the request waited TASK30_QUEUE_SECONDS=60 in the queue;
 *   413 — prompt longer than TASK30_MAX_CONTEXT tokens (estimate; default 6000) or body > 256 KB;
 *   400 — bad JSON / no messages / a model other than TASK30_MODEL;
 *   max_tokens is clamped to TASK30_MAX_TOKENS (1024); num_ctx = TASK30_NUM_CTX (8192); qwen3 «thinking» off.
 * Every request is logged (key prefix, status, tokens, ms).
 *
 * Usage:
 *   mvn -q compile exec:java -Ptask30                     # start the service on 0.0.0.0:8030
 *   mvn -q exec:java -Ptask30 -Dexec.args="check"         # start + test over the network address + report
 * Env: TASK30_MODEL (qwen3:8b), TASK30_HOST (0.0.0.0), TASK30_PORT (8030), OLLAMA_HOST, and the limits above.
 */
public final class Task30 {

    private static final Path REPORT = Path.of("task30-private-service-report.md");
    private static final int MAX_BODY = 256 * 1024;

    record Limits(int defaultRpm, int maxConcurrent, int queueSeconds, int maxContext, int maxTokens, int numCtx) {
        static Limits fromEnv() {
            return new Limits(i("TASK30_RPM", 20), i("TASK30_MAX_CONCURRENT", 2), i("TASK30_QUEUE_SECONDS", 60),
                    i("TASK30_MAX_CONTEXT", 6000), i("TASK30_MAX_TOKENS", 1024), i("TASK30_NUM_CTX", 8192));
        }

        private static int i(String name, int def) {
            return Integer.parseInt(LlmClient.env(name, String.valueOf(def)));
        }
    }

    private final OllamaClient ollama;
    private final String model;
    private final Limits limits;
    private final Map<String, Integer> keys; // key -> requests per minute
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final Semaphore slots;
    /** Requests from this machine itself need no key (Ollama is open on localhost anyway). TASK30_LOCAL_NO_KEY=0 disables. */
    private final boolean localNoKey = !LlmClient.env("TASK30_LOCAL_NO_KEY", "1").matches("(?i)0|false|off|no");
    private static final String LOCAL = "localhost";
    private final AtomicLong active = new AtomicLong();
    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong served = new AtomicLong();
    private HttpServer server;

    Task30(OllamaClient ollama, String model, Limits limits, Map<String, Integer> keys) {
        this.ollama = ollama;
        this.model = model;
        this.limits = limits;
        this.keys = keys;
        this.slots = new Semaphore(limits.maxConcurrent(), true);
    }

    public static void main(String[] args) throws Exception {
        String model = LlmClient.env("TASK30_MODEL", LlmClient.env("TASK27_MODEL", "qwen3:8b"));
        OllamaClient ollama = OllamaClient.fromEnv();
        String version;
        try {
            version = ollama.version();
        } catch (Exception e) {
            System.err.println("❌ " + e.getMessage());
            System.exit(1);
            return;
        }
        if (ollama.find(model) == null) {
            System.err.println("❌ Нет модели " + model + ": ollama pull " + model);
            System.exit(1);
            return;
        }
        boolean check = args.length > 0 && args[0].equalsIgnoreCase("check");
        Limits limits = Limits.fromEnv();
        Map<String, Integer> keys = parseKeys(LlmClient.env("TASK30_API_KEYS", ""), limits.defaultRpm());
        if (keys.isEmpty()) {
            // generated once, then reused: the key does not change between launches
            Path keyFile = Path.of(LlmClient.env("TASK30_KEY_FILE", "task30-api-key.txt"));
            String saved = Files.isRegularFile(keyFile) ? Files.readString(keyFile).strip() : "";
            if (saved.isEmpty()) {
                saved = "sk-local-" + HexFormat.of().formatHex(SecureRandom.getInstanceStrong().generateSeed(12));
                Files.writeString(keyFile, saved + "\n");
            }
            keys.put(saved, limits.defaultRpm());
            System.out.println("API-ключ (из " + keyFile + "): " + saved);
        }
        if (check) {
            keys.put("check-limited", 3); // a key with a tiny limit to prove the rate limiter
        }
        String host = LlmClient.env("TASK30_HOST", "0.0.0.0");
        int port = Integer.parseInt(LlmClient.env("TASK30_PORT", "8030"));
        Task30 svc = new Task30(ollama, model, limits, keys);
        svc.start(host, port);
        System.out.printf("✅ Приватный AI-сервис: http://%s:%d  (модель %s, Ollama %s на %s)%n", host, port, model, version, ollama.baseUrl());
        for (String[] a : interfaces()) {
            System.out.printf("   из сети: http://%s:%d   (%s%s)%n", a[1], port, a[0], a[2]);
        }
        System.out.println("   нет ответа с другого устройства → брандмауэр macOS: разрешите входящие для java; VPN-адрес доступен только из VPN");
        System.out.printf("   лимиты: %d запр/мин на ключ, %d одновременно (очередь %d с), контекст ≤ %d ток., max_tokens ≤ %d%n",
                limits.defaultRpm(), limits.maxConcurrent(), limits.queueSeconds(), limits.maxContext(), limits.maxTokens());
        if (check) {
            int rc = svc.check(version, port, keys);
            svc.server.stop(0);
            System.exit(rc);
        }
    }

    static Map<String, Integer> parseKeys(String raw, int defaultRpm) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            int i = p.lastIndexOf(':');
            if (i > 0 && p.substring(i + 1).matches("\\d+")) {
                out.put(p.substring(0, i), Integer.parseInt(p.substring(i + 1)));
            } else {
                out.put(p, defaultRpm);
            }
        }
        return out;
    }

    /** {interface, IPv4, note}: utun/ppp/ipsec/tun/wg = VPN, en0 on a Mac = Wi-Fi or Ethernet. */
    static List<String[]> interfaces() {
        List<String[]> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String n = ni.getName();
                String note = n.matches("(utun|ppp|ipsec|tun|wg|gpd).*") ? ", VPN — доступен только из VPN"
                        : n.startsWith("bridge") || n.startsWith("docker") || n.startsWith("vmenet") ? ", виртуальная сеть"
                        : ", локальная сеть";
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) {
                        out.add(new String[]{n, a.getHostAddress(), note});
                    }
                }
            }
        } catch (Exception ignored) {
            // no interfaces visible
        }
        // LAN first, VPN/virtual last
        out.sort(java.util.Comparator.comparing(x -> x[2].contains("локальная") ? 0 : 1));
        return out;
    }

    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // no interfaces visible
        }
        return out;
    }

    void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 64);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    // ================================================================ HTTP

    private void handle(HttpExchange ex) {
        long t0 = System.nanoTime();
        String key = "-";
        int status = 500;
        String note = "";
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            if (method.equals("OPTIONS")) {
                status = 204;
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                status = send(ex, 200, "text/html; charset=utf-8", CHAT_PAGE.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (method.equals("GET") && path.equals("/health")) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("status", "ok");
                h.put("model", model);
                try {
                    h.put("ollama", ollama.version());
                } catch (Exception e) {
                    h.put("status", "degraded");
                    h.put("ollama", "unreachable");
                }
                h.put("active", active.get());
                h.put("queued", waiting.get());
                h.put("served", served.get());
                h.put("limits", limits);
                h.put("keyRequired", !(localNoKey && ex.getRemoteAddress().getAddress().isLoopbackAddress()));
                status = json(ex, 200, h);
                return;
            }
            // everything below needs a key — except requests from this machine itself
            key = bearer(ex);
            boolean loopback = ex.getRemoteAddress().getAddress().isLoopbackAddress();
            if ((key == null || key.isEmpty() || !keys.containsKey(key)) && loopback && localNoKey) {
                key = LOCAL;
            } else if (key == null || key.isEmpty()) {
                status = error(ex, 401, "missing_api_key", "Не указан API-ключ. Он напечатан в консоли сервиса при запуске "
                        + "и лежит в файле task30-api-key.txt (или задан в TASK30_API_KEYS).");
                return;
            } else if (!keys.containsKey(key)) {
                status = error(ex, 401, "invalid_api_key", "Неверный API-ключ. Актуальный ключ — в консоли сервиса и в файле task30-api-key.txt.");
                return;
            }
            if (method.equals("GET") && path.equals("/v1/models")) {
                status = json(ex, 200, Map.of("object", "list", "data",
                        List.of(Map.of("id", model, "object", "model", "owned_by", "local"))));
                return;
            }
            if (!(method.equals("POST") && path.equals("/v1/chat/completions"))) {
                status = error(ex, 404, "not_found", "Нет такого адреса: " + method + " " + path);
                return;
            }
            long retry = rateLimit(key);
            if (retry > 0) {
                ex.getResponseHeaders().set("Retry-After", String.valueOf(retry));
                status = error(ex, 429, "rate_limit_exceeded", "Превышен лимит " + keys.getOrDefault(key, limits.defaultRpm()) + " запросов в минуту; повторите через " + retry + " с");
                return;
            }
            byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (raw.length > MAX_BODY) {
                status = error(ex, 413, "request_too_large", "Тело запроса больше " + MAX_BODY / 1024 + " КБ");
                return;
            }
            JsonNode body;
            try {
                body = LlmClient.parseJson(new String(raw, StandardCharsets.UTF_8));
            } catch (Exception e) {
                status = error(ex, 400, "invalid_json", "Тело запроса — не JSON");
                return;
            }
            String asked = body.path("model").asText(model);
            if (!asked.isBlank() && !asked.equals(model)) {
                status = error(ex, 400, "model_not_allowed", "Доступна только модель " + model);
                return;
            }
            List<Map<String, String>> messages = new ArrayList<>();
            int chars = 0;
            for (JsonNode m : body.path("messages")) {
                String role = m.path("role").asText("user");
                String content = m.path("content").isTextual() ? m.path("content").asText() : m.path("content").toString();
                if (!List.of("system", "user", "assistant").contains(role)) {
                    continue;
                }
                messages.add(LlmClient.message(role, content));
                chars += content.length();
            }
            if (messages.isEmpty()) {
                status = error(ex, 400, "invalid_request", "Нужен непустой массив messages");
                return;
            }
            int estimate = LlmClient.estimateTokens(" ".repeat(Math.max(1, chars)));
            if (estimate > limits.maxContext()) {
                status = error(ex, 413, "context_length_exceeded", "Промпт ≈ " + estimate + " токенов, лимит сервиса " + limits.maxContext());
                return;
            }
            int maxTokens = Math.min(body.path("max_tokens").asInt(limits.maxTokens()), limits.maxTokens());
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("num_ctx", limits.numCtx());
            options.put("num_predict", maxTokens);
            if (body.has("temperature")) {
                options.put("temperature", body.path("temperature").asDouble());
            }
            // concurrency: wait for a free generation slot (fair queue), give up after queueSeconds
            waiting.incrementAndGet();
            boolean got;
            try {
                got = slots.tryAcquire(limits.queueSeconds(), TimeUnit.SECONDS);
            } finally {
                waiting.decrementAndGet();
            }
            if (!got) {
                ex.getResponseHeaders().set("Retry-After", "10");
                status = error(ex, 503, "server_busy", "Все " + limits.maxConcurrent() + " слота генерации заняты");
                return;
            }
            active.incrementAndGet();
            try {
                boolean stream = body.path("stream").asBoolean(false);
                OllamaClient.ChatResult r = stream ? streamAnswer(ex, messages, options) : plainAnswer(ex, messages, options);
                status = 200;
                note = r.promptTokens() + "→" + r.outputTokens() + " ток." + (stream ? " stream" : "");
                served.incrementAndGet();
            } finally {
                active.decrementAndGet();
                slots.release();
            }
        } catch (UncheckedIOException e) {
            status = 499;
            note = "клиент отключился";
        } catch (Exception e) {
            note = e.getMessage();
            try {
                status = error(ex, 502, "upstream_error", "Ошибка модели: " + e.getMessage());
            } catch (Exception ignored) {
                // headers already sent (stream) — nothing more to say
            }
        } finally {
            System.out.printf(Locale.ROOT, "%s %s %s key=%s → %d %s (%d мс)%n",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), ex.getRequestMethod(),
                    ex.getRequestURI().getPath(), key == null ? "-" : key.length() > 8 ? key.substring(0, 8) + "…" : key, status, note,
                    (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** Sliding 60-second window per key; returns seconds to wait (0 = allowed, the request is counted). */
    long rateLimit(String key) {
        int rpm = keys.getOrDefault(key, limits.defaultRpm());
        Deque<Long> w = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (w) {
            long now = System.currentTimeMillis();
            while (!w.isEmpty() && now - w.peekFirst() >= 60_000) {
                w.pollFirst();
            }
            if (w.size() >= rpm) {
                return Math.max(1, (60_000 - (now - w.peekFirst()) + 999) / 1000);
            }
            w.addLast(now);
            return 0;
        }
    }

    private OllamaClient.ChatResult plainAnswer(HttpExchange ex, List<Map<String, String>> messages, Map<String, Object> options) throws Exception {
        OllamaClient.ChatResult r = ollama.chatStream(model, messages, options, false, null, d -> { });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "chatcmpl-" + System.nanoTime());
        out.put("object", "chat.completion");
        out.put("created", System.currentTimeMillis() / 1000);
        out.put("model", model);
        out.put("choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", r.content()),
                "finish_reason", "length".equals(r.doneReason()) ? "length" : "stop")));
        out.put("usage", Map.of("prompt_tokens", r.promptTokens(), "completion_tokens", r.outputTokens(),
                "total_tokens", r.promptTokens() + r.outputTokens()));
        json(ex, 200, out);
        return r;
    }

    private OllamaClient.ChatResult streamAnswer(HttpExchange ex, List<Map<String, String>> messages, Map<String, Object> options) throws Exception {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        String id = "chatcmpl-" + System.nanoTime();
        OllamaClient.ChatResult r = ollama.chatStream(model, messages, options, false, null, d -> {
            if (!d.content().isEmpty()) {
                sse(out, Map.of("id", id, "object", "chat.completion.chunk", "model", model,
                        "choices", List.of(Map.of("index", 0, "delta", Map.of("content", d.content())))));
            }
        });
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("id", id);
        last.put("object", "chat.completion.chunk");
        last.put("model", model);
        last.put("choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", "length".equals(r.doneReason()) ? "length" : "stop")));
        last.put("usage", Map.of("prompt_tokens", r.promptTokens(), "completion_tokens", r.outputTokens()));
        sse(out, last);
        try {
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return r;
    }

    private static void sse(OutputStream out, Object value) {
        try {
            out.write(("data: " + LlmClient.toJson(value) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).strip() : null;
    }

    private static int error(HttpExchange ex, int status, String code, String message) throws IOException {
        return json(ex, status, Map.of("error", Map.of("message", message, "type", code, "code", code)));
    }

    private static int json(HttpExchange ex, int status, Object value) throws IOException {
        try {
            return send(ex, status, "application/json; charset=utf-8", LlmClient.toJson(value).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static int send(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        return status;
    }

    // ================================================================ check over the network + report

    record Probe(String name, boolean ok, String detail) {
    }

    private int check(String version, int port, Map<String, Integer> keys) throws Exception {
        String ip = lanAddresses().stream().findFirst().orElse("127.0.0.1");
        String base = "http://" + ip + ":" + port;
        String key = keys.keySet().stream().filter(k -> !k.equals("check-limited")).findFirst().orElseThrow();
        System.out.println("\nПроверка по сетевому адресу " + base);
        List<Probe> probes = new ArrayList<>();
        List<String> parallelLines = new ArrayList<>();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            // 1. network access
            HttpResponse<String> h = get(http, base + "/health", null);
            probes.add(new Probe("Доступ по сети: GET /health с адреса " + ip, h.statusCode() == 200, "HTTP " + h.statusCode() + " " + clip(h.body(), 140)));
            HttpResponse<String> page = get(http, base + "/", null);
            probes.add(new Probe("Веб-чат отдаётся по сети (GET /)", page.statusCode() == 200 && page.body().contains("<textarea"), "HTTP " + page.statusCode()));
            // 2. auth
            HttpResponse<String> noKey = post(http, base, null, chatBody("Привет", 10, false));
            probes.add(new Probe("Без ключа → 401", noKey.statusCode() == 401, "HTTP " + noKey.statusCode()));
            HttpResponse<String> badKey = post(http, base, "wrong-key", chatBody("Привет", 10, false));
            probes.add(new Probe("Чужой ключ → 401", badKey.statusCode() == 401, "HTTP " + badKey.statusCode()));
            HttpResponse<String> local = post(http, "http://127.0.0.1:" + port, null, chatBody("Скажи «да».", 5, false));
            probes.add(new Probe("С этого же компьютера (127.0.0.1) без ключа → 200", local.statusCode() == 200, "HTTP " + local.statusCode()));
            HttpResponse<String> models = get(http, base + "/v1/models", key);
            probes.add(new Probe("GET /v1/models с ключом", models.statusCode() == 200 && models.body().contains(model), "HTTP " + models.statusCode()));
            // 3. chat
            long t = System.nanoTime();
            HttpResponse<String> chat = post(http, base, key, chatBody("Что такое нагрузочное тестирование? Одно предложение.", 120, false));
            String answer = chat.statusCode() == 200 ? LlmClient.parseJson(chat.body()).path("choices").path(0).path("message").path("content").asText() : chat.body();
            probes.add(new Probe("Чат: POST /v1/chat/completions", chat.statusCode() == 200 && !answer.isBlank(),
                    String.format(Locale.ROOT, "HTTP %d за %.1f с: «%s»", chat.statusCode(), (System.nanoTime() - t) / 1e9, clip(answer.strip(), 160))));
            HttpResponse<String> multi = post(http, base, key, "{\"model\":\"" + model + "\",\"max_tokens\":40,\"messages\":["
                    + "{\"role\":\"user\",\"content\":\"Меня зовут Алексей. Запомни.\"},{\"role\":\"assistant\",\"content\":\"Запомнил.\"},"
                    + "{\"role\":\"user\",\"content\":\"Как меня зовут? Одно слово.\"}]}");
            String name = multi.statusCode() == 200 ? LlmClient.parseJson(multi.body()).path("choices").path(0).path("message").path("content").asText() : "";
            probes.add(new Probe("Чат с историей (несколько сообщений)", name.contains("Алекс"), "ответ: «" + clip(name.strip(), 80) + "»"));
            t = System.nanoTime();
            HttpResponse<String> st = post(http, base, key, chatBody("Назови три вида нагрузочных тестов.", 80, true));
            long chunks = st.body().lines().filter(l -> l.startsWith("data: {")).count();
            probes.add(new Probe("Потоковый ответ (stream=true, SSE)", st.statusCode() == 200 && chunks > 3 && st.body().contains("[DONE]"),
                    String.format(Locale.ROOT, "HTTP %d, %d SSE-чанков, %.1f с", st.statusCode(), chunks, (System.nanoTime() - t) / 1e9)));
            // 4. limits
            HttpResponse<String> big = post(http, base, key, chatBody("слово ".repeat(limits.maxContext() * 3), 10, false));
            probes.add(new Probe("Лимит контекста: промпт > " + limits.maxContext() + " ток. → 413", big.statusCode() == 413, "HTTP " + big.statusCode() + " " + clip(big.body(), 120)));
            HttpResponse<String> clamp = post(http, base, key, chatBody("Перечисли 50 метрик производительности с пояснениями.", 100000, false));
            int out = clamp.statusCode() == 200 ? LlmClient.parseJson(clamp.body()).path("usage").path("completion_tokens").asInt() : -1;
            probes.add(new Probe("max_tokens=100000 обрезается до " + limits.maxTokens(), clamp.statusCode() == 200 && out <= limits.maxTokens(),
                    "сгенерировано " + out + " ток."));
            HttpResponse<String> otherModel = post(http, base, key, "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            probes.add(new Probe("Чужая модель → 400", otherModel.statusCode() == 400, "HTTP " + otherModel.statusCode()));
            HttpResponse<String> notJson = post(http, base, key, "{oops");
            probes.add(new Probe("Битый JSON → 400", notJson.statusCode() == 400, "HTTP " + notJson.statusCode()));
            List<Integer> codes = new ArrayList<>();
            String retryAfter = "";
            for (int i = 0; i < 5; i++) {
                HttpResponse<String> r = post(http, base, "check-limited", chatBody("Скажи «да».", 3, false));
                codes.add(r.statusCode());
                retryAfter = r.headers().firstValue("Retry-After").orElse(retryAfter);
            }
            probes.add(new Probe("Rate limit: ключ с лимитом 3/мин, 5 запросов подряд", codes.subList(0, 3).stream().allMatch(c -> c == 200)
                    && codes.subList(3, 5).stream().allMatch(c -> c == 429), "коды " + codes + ", Retry-After: " + retryAfter + " с"));
            // 5. stability under parallel load
            int n = 6;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            List<Future<long[]>> fs = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) {
                int q = i;
                fs.add(pool.submit(() -> {
                    long s = System.nanoTime();
                    HttpResponse<String> r = post(http, base, key, chatBody("Сколько будет " + (q + 2) + " плюс " + (q + 3) + "? Только число.", 20, false));
                    String a = r.statusCode() == 200 ? LlmClient.parseJson(r.body()).path("choices").path(0).path("message").path("content").asText() : "";
                    boolean right = a.contains(String.valueOf(2 * q + 5));
                    return new long[]{r.statusCode(), (System.nanoTime() - s) / 1_000_000, right ? 1 : 0};
                }));
            }
            int ok = 0;
            int correct = 0;
            long maxMs = 0;
            for (Future<long[]> f : fs) {
                long[] v = f.get(10, TimeUnit.MINUTES);
                if (v[0] == 200) {
                    ok++;
                }
                correct += (int) v[2];
                maxMs = Math.max(maxMs, v[1]);
                parallelLines.add("HTTP " + v[0] + " за " + String.format(Locale.ROOT, "%.1f", v[1] / 1000.0) + " с" + (v[2] == 1 ? ", ответ верный" : ""));
            }
            pool.shutdown();
            probes.add(new Probe("Стабильность: " + n + " параллельных запросов (слотов " + limits.maxConcurrent() + ", остальные ждут в очереди)",
                    ok == n, String.format(Locale.ROOT, "%d из %d → 200, верных ответов %d, все за %.1f с (самый долгий %.1f с)",
                    ok, n, correct, (System.nanoTime() - start) / 1e9, maxMs / 1000.0)));
            HttpResponse<String> after = get(http, base + "/health", null);
            probes.add(new Probe("Сервис жив после нагрузки", after.statusCode() == 200, clip(after.body(), 140)));
        }
        boolean all = probes.stream().allMatch(Probe::ok);
        System.out.println();
        probes.forEach(p -> System.out.println((p.ok() ? "✅ " : "❌ ") + p.name() + " — " + p.detail()));
        writeReport(version, base, probes, parallelLines, all);
        System.out.println("Отчёт: " + REPORT);
        return all ? 0 : 1;
    }

    private String chatBody(String text, int maxTokens, boolean stream) throws Exception {
        return LlmClient.toJson(Map.of("model", model, "max_tokens", maxTokens, "stream", stream, "temperature", 0.2,
                "messages", List.of(Map.of("role", "user", "content", text))));
    }

    private static HttpResponse<String> get(HttpClient http, String url, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET();
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient http, String base, String key, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/v1/chat/completions")).timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String clip(String s, int n) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ");
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private void writeReport(String version, String base, List<Probe> probes, List<String> parallel, boolean all) throws IOException {
        StringBuilder r = new StringBuilder();
        r.append("# День 30. Локальная LLM как приватный сервис\n\n");
        r.append("> Раздел «Проверка» сгенерирован `Task30` (`mvn -q exec:java -Ptask30 -Dexec.args=\"check\"`) ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(".\n\n");
        r.append("**Итог:** ").append(all ? "✅ приватный AI-сервис работает" : "❌ есть непройденные проверки")
                .append(" — модель `").append(model).append("` (Ollama ").append(version).append("), проверено по сетевому адресу `")
                .append(base).append("`.\n\n");
        r.append("""
                ## Как устроено

                ```
                клиенты в сети (браузер, curl, любой OpenAI SDK)
                        │  HTTP :8030, Authorization: Bearer <ключ>
                        ▼
                Task30 — шлюз: ключи → rate limit → лимит контекста / max_tokens → очередь (N слотов)
                        │  только localhost
                        ▼
                Ollama :11434 (не открыта наружу) → qwen3:8b
                ```

                | Адрес | Что делает |
                |---|---|
                | `GET /` | веб-чат: ключ вводится один раз, история диалога в браузере, ответ печатается по мере генерации |
                | `GET /health` | без ключа: статус, модель, версия Ollama, активные запросы, очередь, лимиты |
                | `GET /v1/models` | список моделей (OpenAI-формат) |
                | `POST /v1/chat/completions` | чат в OpenAI-формате: `stream=false` — JSON, `stream=true` — SSE |

                | Ограничение | По умолчанию | Ответ при нарушении |
                |---|---|---|
                | API-ключ (`TASK30_API_KEYS="ключ:запр_в_мин,…"`) | генерируется при старте | 401 |
                | Запросов в минуту на ключ (`TASK30_RPM`) | 20 | 429 + `Retry-After` |
                | Одновременных генераций (`TASK30_MAX_CONCURRENT`), очередь (`TASK30_QUEUE_SECONDS`) | 2, 60 с | ждут в очереди; дольше — 503 |
                | Промпт (`TASK30_MAX_CONTEXT`) | 6000 токенов (оценка) | 413 |
                | Ответ (`TASK30_MAX_TOKENS`) | 1024 токена | обрезается молча |
                | Окно модели (`TASK30_NUM_CTX`) | 8192 | — |
                | Размер тела запроса | 256 КБ | 413 |
                | Модель | только `TASK30_MODEL` | 400 |

                """);
        r.append("## Проверка\n\n| Проверка | Итог | Детали |\n|---|---|---|\n");
        for (Probe p : probes) {
            r.append("| ").append(p.name()).append(" | ").append(p.ok() ? "✅" : "❌").append(" | ").append(p.detail().replace("|", "\\|")).append(" |\n");
        }
        if (!parallel.isEmpty()) {
            r.append("\nПараллельные запросы по отдельности: ").append(String.join("; ", parallel)).append(".\n");
        }
        r.append("""

                ## Развёртывание

                **Домашний сервер / Mac в локальной сети**

                ```bash
                brew services start ollama                 # Ollama слушает только 127.0.0.1:11434 — так и оставить
                export TASK30_API_KEYS="ключ-ноутбука:30,ключ-телефона:10"
                mvn -q compile exec:java -Ptask30          # шлюз на 0.0.0.0:8030
                # с другого устройства: http://<IP-сервера>:8030  (веб-чат)  или  base_url http://<IP>:8030/v1
                ```

                **VPS (Linux)** — Ollama и шлюз на одной машине, наружу только 443 через reverse proxy с TLS:

                ```bash
                curl -fsSL https://ollama.com/install.sh | sh && ollama pull qwen3:8b   # systemd-сервис, 127.0.0.1:11434
                TASK30_HOST=127.0.0.1 TASK30_API_KEYS="…" mvn -q exec:java -Ptask30     # или systemd-юнит с этой командой
                # Caddyfile:  ai.example.com { reverse_proxy 127.0.0.1:8030 }   — HTTPS-сертификат Caddy получит сам
                ufw allow 443 && ufw deny 11434 && ufw deny 8030
                ```

                Модели 8B на CPU-сервере нужно ≥ 8 ГБ RAM и она отвечает медленно (≈ 2–5 ток/с); для живого чата
                нужен GPU или Apple Silicon.

                **Подключение клиента (Python, OpenAI SDK):**

                ```python
                from openai import OpenAI
                client = OpenAI(base_url="http://<IP>:8030/v1", api_key="<ключ>")
                print(client.chat.completions.create(model="qwen3:8b",
                      messages=[{"role": "user", "content": "Привет!"}]).choices[0].message.content)
                ```

                Любой прошлый день проекта тоже можно направить в сервис:
                `HINDSIGHT_API_LLM_BASE_URL=http://<IP>:8030/v1 HINDSIGHT_API_LLM_MODEL=qwen3:8b LLM_API_KEY=<ключ> mvn -q exec:java -Ptask1`.
                """);
        Files.writeString(REPORT, r.toString(), StandardCharsets.UTF_8);
    }

    // ================================================================ chat page

    private static final String CHAT_PAGE = """
            <!doctype html>
            <html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Приватный AI</title>
            <style>
            :root{--bg:#F2F4F7;--surface:#fff;--ink:#1B2533;--muted:#5E6A7B;--rule:#D6DCE4;--signal:#2457D6;--soft:#E3EAFB;--bad:#B8402B}
            @media (prefers-color-scheme:dark){:root{--bg:#10151C;--surface:#161D26;--ink:#E3E8EF;--muted:#96A2B3;--rule:#2A3441;--signal:#7EA4FF;--soft:#1C2943;--bad:#F2795F}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 "PT Sans",-apple-system,system-ui,sans-serif;display:flex;flex-direction:column;height:100vh}
            header{display:flex;gap:12px;align-items:center;padding:12px 16px;background:var(--surface);border-bottom:1px solid var(--rule)}
            header b{font-size:17px}header span{color:var(--muted);font-size:14px;flex:1}
            input,textarea,button{font:inherit;color:inherit}
            #key{width:220px;padding:6px 10px;border:1px solid var(--rule);border-radius:4px;background:var(--bg)}
            main{flex:1;overflow:auto;padding:20px 16px}.wrap{max-width:760px;margin:0 auto}
            .m{margin:0 0 14px;padding:10px 14px;border-radius:8px;white-space:pre-wrap}
            .u{background:var(--soft);margin-left:15%}.a{background:var(--surface);border:1px solid var(--rule);margin-right:5%}
            .meta{color:var(--muted);font-size:12.5px;margin-top:4px}.err{color:var(--bad)}
            form{display:flex;gap:8px;padding:12px 16px;background:var(--surface);border-top:1px solid var(--rule)}
            textarea{flex:1;resize:none;padding:9px 11px;border:1px solid var(--rule);border-radius:4px;background:var(--bg)}
            button{border:1px solid var(--signal);background:var(--signal);color:#fff;border-radius:4px;padding:0 18px;font-weight:700;cursor:pointer}
            button.ghost{background:none;color:var(--muted);border-color:var(--rule);font-weight:400}
            </style></head><body>
            <header><b>Приватный AI</b><span id="st">…</span>
            <input id="key" type="password" placeholder="API-ключ" aria-label="API-ключ"><button class="ghost" id="clear" type="button">Новый диалог</button></header>
            <main><div class="wrap" id="log"></div></main>
            <form id="f"><textarea id="q" rows="2" placeholder="Сообщение" aria-label="Сообщение"></textarea><button id="send">Отправить</button></form>
            <script>
            const $=s=>document.querySelector(s);let hist=[],busy=null;
            try{$('#key').value=localStorage.getItem('pa-key')||''}catch{}
            $('#key').oninput=()=>{try{localStorage.setItem('pa-key',$('#key').value.trim())}catch{}};
            let keyRequired=true;
            fetch('/health').then(r=>r.json()).then(h=>{keyRequired=h.keyRequired;$('#st').textContent=h.model+' · Ollama '+h.ollama+' · '+h.limits.defaultRpm+' запр/мин';
              if(!keyRequired){$('#key').placeholder='ключ не нужен на этом компьютере'}}).catch(()=>$('#st').textContent='сервис недоступен');
            function add(cls,t){const d=document.createElement('div');d.className='m '+cls;d.textContent=t;$('#log').append(d);d.scrollIntoView({block:'end'});return d}
            $('#clear').onclick=()=>{hist=[];$('#log').innerHTML=''};
            $('#q').onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();$('#f').requestSubmit()}};
            $('#f').onsubmit=async e=>{e.preventDefault();if(busy){busy.abort();return}
              const text=$('#q').value.trim();if(!text)return;
              if(keyRequired&&!$('#key').value.trim()){add('a err','Введите API-ключ в поле вверху. Он напечатан в консоли сервиса при запуске и лежит в файле task30-api-key.txt.');$('#key').focus();return}$('#q').value='';add('u',text);hist.push({role:'user',content:text});
              const el=add('a','');let out='';const t0=Date.now();busy=new AbortController();$('#send').textContent='Стоп';
              try{const r=await fetch('/v1/chat/completions',{method:'POST',signal:busy.signal,headers:Object.assign({'Content-Type':'application/json'},$('#key').value.trim()?{'Authorization':'Bearer '+$('#key').value.trim()}:{}),body:JSON.stringify({messages:hist.slice(-12),stream:true})});
                if(!r.ok){const j=await r.json().catch(()=>({}));throw new Error((j.error&&j.error.message)||('HTTP '+r.status))}
                const rd=r.body.getReader(),dec=new TextDecoder();let buf='',usage=null;
                for(;;){const{value,done}=await rd.read();if(done)break;buf+=dec.decode(value,{stream:true});let i;
                  while((i=buf.indexOf('\\n\\n'))>=0){const ln=buf.slice(0,i).replace(/^data: /,'');buf=buf.slice(i+2);if(ln==='[DONE]')continue;
                    const j=JSON.parse(ln);const c=j.choices[0].delta.content;if(c){out+=c;el.textContent=out;el.scrollIntoView({block:'end'})}if(j.usage)usage=j.usage}}
                hist.push({role:'assistant',content:out});
                const m=document.createElement('div');m.className='meta';m.textContent=((Date.now()-t0)/1000).toFixed(1)+' с'+(usage?' · '+usage.completion_tokens+' ток.':'');el.append(m)
              }catch(err){if(err.name!=='AbortError'){el.classList.add('err');el.textContent='Ошибка: '+err.message;hist.pop()}}
              finally{busy=null;$('#send').textContent='Отправить'}};
            </script></body></html>
            """;
}
