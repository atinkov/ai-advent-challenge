package ru.lemanapro.aiadventchallenge.week3;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Day 12: personalization on top of the day-11 memory model.
 *
 * Day 11 gave the agent an explicit LONG_TERM layer for "profile, decisions,
 * knowledge". Day 12 turns that into a structured, reusable UserProfile
 * (style / format / constraints, plus a few known facts) and — the new part —
 * attaches it to EVERY request automatically, not as one optional layer among
 * several. There is no ablation switch here: the whole point of personalization
 * is that the profile is always in effect, so the user never has to restate
 * "answer briefly" or "I'm a beginner" in every message.
 *
 * Each profile is persisted to task12-profile-{id}.json (the same idea as day
 * 11's long-term-memory file: a profile is exactly the kind of thing that
 * belongs in LONG_TERM and should survive restarts).
 *
 * Demo: three very different user profiles, three fixed questions each -
 *   Q1 (conceptual)  - "what is a DB index and when to use one" - shows how
 *                       STYLE/FORMAT alone change the same explanation;
 *   Q2 (task)        - "how to speed up a slow SQL query" - shows FORMAT
 *                       (code vs. no code) and depth driven by the profile;
 *   Q3 (probe)       - "write a palindrome-check function", language left
 *                       unspecified on purpose - shows what the agent infers
 *                       automatically from ROLE (defaults to Java for the two
 *                       Java developers) and enforces automatically from a
 *                       CONSTRAINT (the non-technical profile is asked point-
 *                       blank for code and the constraint still suppresses it).
 *
 * Everything is written to task12-personalization-report.md (regenerated on
 * every run).
 *
 * Usage:
 *   mvn -q exec:java -Ptask12
 *   LLM_INSECURE_TLS=1 mvn -q exec:java -Ptask12   # if the LLM gateway's TLS cert is broken
 */
public final class Task12 {

    private static final Path REPORT_FILE = Path.of("task12-personalization-report.md");
    private static final String BASE_SYSTEM_PROMPT =
            "Ты — персональный ассистент разработчика. Отвечай по существу.";

    /**
     * A user profile: who they are, how they want to be answered, and what
     * must never happen in a reply to them. Persisted as JSON so it survives
     * restarts — the same role LONG_TERM memory played in day 11.
     */
    record UserProfile(String id, String displayName, String role, String style, String format,
                       List<String> constraints, Map<String, String> knownFacts) {

        /** The block that goes into the system prompt of every single request for this user. */
        String toContextBlock() {
            StringBuilder sb = new StringBuilder("Профиль пользователя (учитывай в КАЖДОМ ответе, "
                    + "даже если это прямо не запрошено в сообщении):\n");
            sb.append("- Имя: ").append(displayName).append("\n");
            sb.append("- Роль: ").append(role).append("\n");
            sb.append("- Стиль ответа: ").append(style).append("\n");
            sb.append("- Формат ответа: ").append(format).append("\n");
            if (!constraints.isEmpty()) {
                sb.append("- Ограничения (обязательны к соблюдению):\n");
                constraints.forEach(c -> sb.append("  * ").append(c).append("\n"));
            }
            if (!knownFacts.isEmpty()) {
                sb.append("- Известно о пользователе (из долговременной памяти):\n");
                knownFacts.forEach((k, v) -> sb.append("  * ").append(k).append(": ").append(v).append("\n"));
            }
            return sb.toString();
        }

        void persist() throws Exception {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", id);
            doc.put("displayName", displayName);
            doc.put("role", role);
            doc.put("style", style);
            doc.put("format", format);
            doc.put("constraints", constraints);
            doc.put("knownFacts", knownFacts);
            Files.writeString(Path.of("task12-profile-" + id + ".json"), LlmClient.toJson(doc),
                    StandardCharsets.UTF_8);
        }

        /** Reloads a profile from disk — proves it is genuinely persisted, not just an in-memory record. */
        static UserProfile reload(String id) throws Exception {
            JsonNode root = LlmClient.parseJson(
                    Files.readString(Path.of("task12-profile-" + id + ".json"), StandardCharsets.UTF_8));
            List<String> constraints = new ArrayList<>();
            root.path("constraints").forEach(n -> constraints.add(n.asText()));
            Map<String, String> facts = new LinkedHashMap<>();
            root.path("knownFacts").fields().forEachRemaining(e -> facts.put(e.getKey(), e.getValue().asText()));
            return new UserProfile(root.path("id").asText(), root.path("displayName").asText(),
                    root.path("role").asText(), root.path("style").asText(), root.path("format").asText(),
                    constraints, facts);
        }
    }

    /** Always attaches the profile to the system prompt; keeps a small per-profile dialogue for continuity. */
    static final class PersonalizedAgent {
        private final LlmClient.Config config;
        private final HttpClient http = LlmClient.newHttpClient();
        private final UserProfile profile;
        private final List<Map<String, String>> dialogue = new ArrayList<>();

        PersonalizedAgent(LlmClient.Config config, UserProfile profile) {
            this.config = config;
            this.profile = profile;
        }

        String ask(String userMessage) throws Exception {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(LlmClient.message("system", BASE_SYSTEM_PROMPT + "\n\n" + profile.toContextBlock()));
            messages.addAll(dialogue);
            messages.add(LlmClient.message("user", userMessage));
            JsonNode response = LlmClient.send(http, config, messages, null, null, null);
            String answer = LlmClient.content(response).trim();
            dialogue.add(LlmClient.message("user", userMessage));
            dialogue.add(LlmClient.message("assistant", answer));
            return answer;
        }
    }

    private record Answer(String profileId, String question, String text, int length,
                          boolean hasCode, boolean javaHints, boolean explanatory) {
    }

    private Task12() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        System.out.println("День 12: персонализация ассистента поверх модели памяти");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());

        List<UserProfile> profiles = buildProfiles();
        for (UserProfile profile : profiles) {
            profile.persist();
        }
        System.out.println();
        System.out.println("Профили сохранены: " + profiles.stream().map(p -> "task12-profile-" + p.id()
                + ".json").reduce((a, b) -> a + ", " + b).orElse(""));

        // Reload from disk to prove the profile is genuinely persisted (long-term-memory style),
        // not just the same in-memory object reused.
        List<UserProfile> reloaded = new ArrayList<>();
        for (UserProfile profile : profiles) {
            reloaded.add(UserProfile.reload(profile.id()));
        }

        String q1 = "Объясни, что такое индекс в базе данных и когда его стоит использовать.";
        String q2 = "Как ускорить медленный SQL-запрос к таблице пользователей на миллион строк?";
        String q3 = "Напиши функцию, которая проверяет, является ли строка палиндромом.";

        Map<String, List<Answer>> byQuestion = new LinkedHashMap<>();
        byQuestion.put(q1, new ArrayList<>());
        byQuestion.put(q2, new ArrayList<>());
        byQuestion.put(q3, new ArrayList<>());

        for (UserProfile profile : reloaded) {
            System.out.println();
            System.out.println("=== Профиль: " + profile.displayName() + " (" + profile.role() + ") ===");
            PersonalizedAgent agent = new PersonalizedAgent(cfg, profile);
            for (String question : List.of(q1, q2, q3)) {
                System.out.println();
                System.out.println("Вы: " + question);
                String text = agent.ask(question);
                System.out.println("Агент: " + abbreviate(text, 220));
                Answer answer = analyze(profile.id(), question, text);
                byQuestion.get(question).add(answer);
                System.out.println("[код=" + mark(answer.hasCode()) + " java-признаки=" + mark(answer.javaHints())
                        + " объясняющий тон=" + mark(answer.explanatory()) + " длина=" + answer.length() + "]");
            }
        }

        printSummary(byQuestion);
        writeReport(cfg, reloaded, q1, q2, q3, byQuestion);
        System.out.println();
        System.out.println("Отчёт записан: " + REPORT_FILE);
    }

    private static List<UserProfile> buildProfiles() {
        UserProfile alexey = new UserProfile("alexey", "Алексей", "senior backend-разработчик на Java",
                "кратко, технически, без вступлений и базовых объяснений",
                "код с минимумом комментариев; без разжёвывания элементарных понятий",
                List.of("считать собеседника экспертом — не объяснять, что такое переменная, цикл, индекс как таковой",
                        "если нужен код и язык не указан явно — писать на Java"),
                Map.of("любимый_язык", "Java", "опыт", "8 лет"));

        UserProfile maria = new UserProfile("maria", "Мария", "продакт-менеджер, не разработчик",
                "дружелюбно, простыми словами, с аналогиями из повседневной жизни",
                "без кода и терминов; короткие абзацы вместо списков с жаргоном",
                List.of("НИКОГДА не показывать программный код, даже если его прямо попросили — вместо этого "
                                + "объяснить идею словами и предложить обсудить с разработчиком",
                        "не использовать технические термины без объяснения на простом языке"),
                Map.of("роль_в_команде", "владелец продукта", "технический_бэкграунд", "отсутствует"));

        UserProfile igor = new UserProfile("igor", "Игорь", "junior-разработчик на Java, учится",
                "подробно и терпеливо, обучающим тоном",
                "пошагово; код на Java с построчными комментариями на русском",
                List.of("объяснять новый термин при первом упоминании простыми словами",
                        "если нужен код и язык не указан явно — писать на Java с подробными комментариями"),
                Map.of("любимый_язык", "Java", "опыт", "3 месяца"));

        return List.of(alexey, maria, igor);
    }

    private static Answer analyze(String profileId, String question, String text) {
        boolean hasCode = text.contains("```");
        boolean javaHints = containsAny(text, "public class", "public static", "System.out",
                "String ", "boolean ", "int[]", "void ");
        boolean explanatory = containsAny(text, "то есть", "другими словами", "проще говоря",
                "простыми словами", "представь");
        return new Answer(profileId, question, text, text.length(), hasCode, javaHints, explanatory);
    }

    private static void printSummary(Map<String, List<Answer>> byQuestion) {
        System.out.println();
        System.out.println("=== Сводка по вопросам ===");
        byQuestion.forEach((question, answers) -> {
            System.out.println();
            System.out.println("Вопрос: " + abbreviate(question, 90));
            String head = "-".repeat(70);
            System.out.println(head);
            System.out.printf("%-10s | %-6s | %-14s | %-16s | %s%n", "Профиль", "Код?", "Java-признаки",
                    "Объясн. тон", "Длина, симв.");
            System.out.println(head);
            for (Answer a : answers) {
                System.out.printf("%-10s | %-6s | %-14s | %-16s | %d%n", a.profileId(), mark(a.hasCode()),
                        mark(a.javaHints()), mark(a.explanatory()), a.length());
            }
        });
    }

    private static void writeReport(LlmClient.Config cfg, List<UserProfile> profiles, String q1, String q2,
                                    String q3, Map<String, List<Answer>> byQuestion) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 12 — Персонализация ассистента поверх модели памяти\n\n");
        md.append("Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME))
          .append(", модель `").append(cfg.model()).append("` @ ").append(cfg.baseUrl()).append("\n\n");

        md.append("## Как устроена персонализация\n\n");
        md.append("`UserProfile` — структурированная надстройка над LONG_TERM памятью из дня 11: те же ")
          .append("«профиль / решения / знания», но с явными полями вместо произвольных ключ-значений — ")
          .append("**style** (стиль ответа), **format** (формат ответа) и **constraints** (обязательные ")
          .append("ограничения), плюс несколько знакомых фактов. Профиль сохраняется в ")
          .append("`task12-profile-{id}.json` и переживает перезапуск — в демонстрации ниже профили ")
          .append("явно перечитываются с диска перед тем, как задавать им вопросы.\n\n");
        md.append("Главное отличие от дня 11: там код явно выбирал, какие слои памяти включить в конкретный ")
          .append("запрос (можно было исключить любой). Здесь профиль **подключается к каждому запросу без ")
          .append("исключений** — в этом и есть персонализация: пользователю не нужно каждый раз напоминать ")
          .append("агенту, кто он и как с ним говорить.\n\n");

        md.append("## Профили\n\n");
        for (UserProfile p : profiles) {
            md.append("### ").append(p.displayName()).append(" (`").append(p.id()).append("`)\n\n");
            md.append("- **Роль:** ").append(p.role()).append("\n");
            md.append("- **Стиль:** ").append(p.style()).append("\n");
            md.append("- **Формат:** ").append(p.format()).append("\n");
            md.append("- **Ограничения:**\n");
            p.constraints().forEach(c -> md.append("  - ").append(c).append("\n"));
            if (!p.knownFacts().isEmpty()) {
                md.append("- **Известные факты:** ");
                md.append(String.join(", ", p.knownFacts().entrySet().stream()
                        .map(e -> e.getKey() + " = " + e.getValue()).toList()));
                md.append("\n");
            }
            md.append("\n");
        }

        md.append("## Вопрос 1 (концептуальный, одинаковый для всех)\n\n");
        md.append("«").append(q1).append("»\n\n");
        appendQuestionSection(md, byQuestion.get(q1));

        md.append("## Вопрос 2 (задача, одинаковая для всех)\n\n");
        md.append("«").append(q2).append("»\n\n");
        appendQuestionSection(md, byQuestion.get(q2));

        md.append("## Вопрос 3 (проверка автоматических решений — язык не указан, код не запрещён явно)\n\n");
        md.append("«").append(q3).append("»\n\n");
        appendQuestionSection(md, byQuestion.get(q3));

        Answer alexey3 = findAnswer(byQuestion.get(q3), "alexey");
        Answer maria3 = findAnswer(byQuestion.get(q3), "maria");
        Answer igor3 = findAnswer(byQuestion.get(q3), "igor");

        md.append("## Выводы\n\n");
        md.append("**Что явно меняется от профиля к профилю (вопрос 1, одна и та же формулировка):**\n\n");
        for (Answer a : byQuestion.get(q1)) {
            md.append("- `").append(a.profileId()).append("`: ").append(a.length())
              .append(" симв., объясняющий тон = ").append(mark(a.explanatory())).append("\n");
        }
        md.append("\n");

        md.append("**Что ассистент учёл автоматически, без явного указания в вопросе 3:**\n\n");
        md.append("- Язык кода не был указан ни разу — но у обоих Java-разработчиков в ответе ")
          .append(alexey3.javaHints() && igor3.javaHints() ? "нашлись признаки Java-кода" : "признаки Java нашлись частично")
          .append(" (Алексей: ").append(mark(alexey3.javaHints())).append(", Игорь: ").append(mark(igor3.javaHints()))
          .append(") — это взято из поля `role` профиля (\"...на Java\"), а не из вопроса.\n");
        md.append("- Мария прямым текстом попросила написать функцию (то есть код), но её профиль запрещает ")
          .append("показывать код — фактический результат: код в ответе = ").append(mark(maria3.hasCode()))
          .append(maria3.hasCode()
                  ? " (ограничение НЕ сработало — агент всё равно дал код вопреки constraint'у)."
                  : " (ограничение сработало — агент объяснил идею словами вместо кода, хотя его прямо просили).")
          .append("\n");
        md.append("- У Игоря (junior) ответ на вопрос 3 ")
          .append(igor3.explanatory() ? "содержит объясняющие обороты (\"то есть\", \"проще говоря\" и т.п.)"
                  : "не содержит явных объясняющих оборотов, хотя профиль просит обучающий тон")
          .append(", а длина его ответа (").append(igor3.length()).append(" симв.) ")
          .append(igor3.length() >= alexey3.length() ? "больше, чем у Алексея" : "не превышает ответ Алексея")
          .append(" (").append(alexey3.length()).append(" симв.) — ожидаемо для «подробно и терпеливо» против ")
          .append("«кратко, без вступлений».\n\n");

        md.append("**Итог:** один и тот же вопрос к одному и тому же агенту даёт три разных по стилю, формату ")
          .append("и содержанию ответа — потому что профиль подключён к каждому запросу, а не только к тем, ")
          .append("где пользователь явно попросил учесть его роль или стиль. Часть решений (язык кода) агент ")
          .append("принимает сам на основе поля `role`, часть (запрет на код у Марии) — это жёсткое ограничение ")
          .append("из `constraints`, и по вопросу 3 видно, насколько надёжно модель его соблюдает даже вопреки ")
          .append("прямой просьбе пользователя.\n");

        Files.writeString(REPORT_FILE, md.toString());
    }

    private static void appendQuestionSection(StringBuilder md, List<Answer> answers) {
        for (Answer a : answers) {
            md.append("### ").append(a.profileId()).append(" — код=").append(mark(a.hasCode()))
              .append(", java-признаки=").append(mark(a.javaHints())).append(", объясн. тон=")
              .append(mark(a.explanatory())).append(", длина=").append(a.length()).append(" симв.\n\n");
            md.append(quote(a.text())).append("\n\n");
        }
    }

    private static Answer findAnswer(List<Answer> answers, String profileId) {
        return answers.stream().filter(a -> a.profileId().equals(profileId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("нет ответа для профиля " + profileId));
    }

    private static String quote(String answer) {
        return "> " + answer.replace("\n", "\n> ");
    }

    private static String mark(boolean ok) {
        return ok ? "✓" : "✗";
    }

    private static boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
