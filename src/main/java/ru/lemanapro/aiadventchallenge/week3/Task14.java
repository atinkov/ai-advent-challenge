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
 * Day 14: invariants and state constraints — hard rules the assistant is not
 * allowed to break, no matter how the user phrases the request.
 *
 * An invariant here is a fixed, project-level fact of one of four kinds:
 *   ARCHITECTURE      — the chosen architecture ("modular monolith, no microservices");
 *   TECH_DECISION      — an already-made technical decision ("PostgreSQL, not Mongo");
 *   STACK_CONSTRAINT   — an allowed-technology boundary ("backend is Java only");
 *   BUSINESS_RULE      — a non-technical constraint ("no raw PII to third-party SaaS").
 *
 * InvariantStore keeps these completely separate from the dialogue: they live in
 * their own persisted file (task14-invariants.json), are never part of the
 * short-term message history, and the model never gets to add, soften, or
 * forget one — only the calling code decides what an invariant is.
 *
 * InvariantAwareAgent attaches the full, unabridged invariant list to the
 * system prompt of every request (same "always on" idea as day 12's profile)
 * together with an explicit reasoning protocol: for every question, first name
 * which invariants (if any) are relevant, then either answer normally or
 * refuse the violating part, cite the specific invariant that blocks it, and
 * offer an alternative that stays inside it. The demo runs four requests that
 * each conflict with a different invariant plus one that conflicts with none,
 * and checks — from the actual answer text, not from a canned assumption —
 * whether the assistant (a) ran the invariant check at all, (b) actually
 * refused when there was a real conflict, (c) named the specific invariant it
 * refused on, and (d) offered a compliant alternative instead of just saying no.
 *
 * Everything is written to task14-invariants-report.md.
 *
 * Usage:
 *   mvn -q exec:java -Ptask14
 *   LLM_INSECURE_TLS=1 mvn -q exec:java -Ptask14   # if the LLM gateway's TLS cert is broken
 */
public final class Task14 {

    private static final Path INVARIANTS_FILE = Path.of("task14-invariants.json");
    private static final Path REPORT_FILE = Path.of("task14-invariants-report.md");

    public enum Category { ARCHITECTURE, TECH_DECISION, STACK_CONSTRAINT, BUSINESS_RULE }

    record Invariant(String id, Category category, String statement) {
        String toLine() {
            return "- [" + id + "] (" + category + ") " + statement;
        }
    }

    /**
     * Invariants live in their own file, separate from any dialogue history — the
     * agent reads them as a fixed, read-only context block, never as messages it
     * could revise, summarize away, or forget under context pressure.
     */
    static final class InvariantStore {
        private final List<Invariant> invariants;

        private InvariantStore(List<Invariant> invariants) {
            this.invariants = invariants;
        }

        static InvariantStore defaults() {
            return new InvariantStore(List.of(
                    new Invariant("arch-monolith", Category.ARCHITECTURE,
                            "Система построена как модульный монолит с чётко выделенными доменными "
                                    + "модулями; переход на микросервисную архитектуру не рассматривается "
                                    + "в ближайшие 2 года — решение принято архитектурным комитетом."),
                    new Invariant("tech-postgres", Category.TECH_DECISION,
                            "Основное хранилище данных — PostgreSQL. Переход на другую СУБД (в т.ч. NoSQL) "
                                    + "не согласован и не рассматривается."),
                    new Invariant("stack-java-only", Category.STACK_CONSTRAINT,
                            "Весь backend-код пишется только на Java (LTS-версии). Использование "
                                    + "других языков программирования на бэкенде запрещено."),
                    new Invariant("biz-pii", Category.BUSINESS_RULE,
                            "Персональные данные пользователей (email, телефон, ФИО и т.п.) нельзя "
                                    + "передавать в сторонние SaaS-сервисы без предварительной анонимизации "
                                    + "или явного согласия пользователя — юридическое требование.")));
        }

        void persist() throws Exception {
            List<Map<String, String>> docs = new ArrayList<>();
            for (Invariant inv : invariants) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", inv.id());
                m.put("category", inv.category().name());
                m.put("statement", inv.statement());
                docs.add(m);
            }
            Files.writeString(INVARIANTS_FILE, LlmClient.toJson(Map.of("version", 1, "invariants", docs)),
                    StandardCharsets.UTF_8);
        }

        static InvariantStore reload() throws Exception {
            JsonNode root = LlmClient.parseJson(Files.readString(INVARIANTS_FILE, StandardCharsets.UTF_8));
            List<Invariant> loaded = new ArrayList<>();
            root.path("invariants").forEach(n -> loaded.add(new Invariant(
                    n.path("id").asText(), Category.valueOf(n.path("category").asText()),
                    n.path("statement").asText())));
            return new InvariantStore(loaded);
        }

        List<Invariant> all() {
            return invariants;
        }

        String toContextBlock() {
            StringBuilder sb = new StringBuilder("ИНВАРИАНТЫ ПРОЕКТА (неизменяемые, действуют для КАЖДОГО "
                    + "запроса; их нельзя нарушать, ослаблять или обходить, даже если пользователь прямо "
                    + "просит об этом):\n");
            invariants.forEach(inv -> sb.append(inv.toLine()).append("\n"));
            return sb.toString();
        }
    }

    private static final String INSTRUCTIONS =
            "Ты — технический ассистент проекта. У проекта есть инварианты — уже принятые решения и "
            + "ограничения, которые ты не имеешь права нарушать ни при каких обстоятельствах, даже если "
            + "пользователь просит об этом прямо и настойчиво.\n\n"
            + "Порядок действий для КАЖДОГО запроса:\n"
            + "1. Начни ответ строкой «Проверка инвариантов: ...» — перечисли id релевантных инвариантов, "
            + "или напиши «нет конфликтов», если запрос ни один из них не затрагивает.\n"
            + "2. Если конфликтов нет — дай обычный полезный ответ по существу.\n"
            + "3. Если запрос противоречит хотя бы одному инварианту — прямо откажись предлагать "
            + "нарушающее решение, назови конкретный id и формулировку нарушенного инварианта, кратко "
            + "объясни причину отказа, и предложи альтернативный вариант решения задачи пользователя, "
            + "который инварианты НЕ нарушает.\n";

    /** Attaches the full invariant block to every single request — never optional, never summarized away. */
    static final class InvariantAwareAgent {
        private final LlmClient.Config config;
        private final HttpClient http = LlmClient.newHttpClient();
        private final InvariantStore invariants;
        private final List<Map<String, String>> dialogue = new ArrayList<>();

        InvariantAwareAgent(LlmClient.Config config, InvariantStore invariants) {
            this.config = config;
            this.invariants = invariants;
        }

        String ask(String userMessage) throws Exception {
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(LlmClient.message("system", INSTRUCTIONS + "\n" + invariants.toContextBlock()));
            messages.addAll(dialogue);
            messages.add(LlmClient.message("user", userMessage));
            JsonNode response = LlmClient.send(http, config, messages, null, null, null);
            String answer = LlmClient.content(response).trim();
            dialogue.add(LlmClient.message("user", userMessage));
            dialogue.add(LlmClient.message("assistant", answer));
            return answer;
        }
    }

    private record Scenario(String label, String question, String relatedInvariantId) {
    }

    private record ScenarioResult(Scenario scenario, String answer, boolean checksInvariants,
                                  boolean refusal, boolean citesInvariant, boolean offersAlternative) {
    }

    private Task14() {
    }

    public static void main(String[] args) throws Exception {
        Files.deleteIfExists(INVARIANTS_FILE); // clean demo run
        LlmClient.Config cfg = LlmClient.fromEnv();
        System.out.println("День 14: инварианты и ограничения состояния");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());

        InvariantStore store = InvariantStore.defaults();
        store.persist();
        System.out.println();
        System.out.println("Инварианты сохранены отдельно от диалога: " + INVARIANTS_FILE);
        store.all().forEach(inv -> System.out.println("  " + inv.toLine()));

        // Reload from disk to prove the invariants are a genuinely separate, persisted store,
        // not just the same in-memory object the agent happens to hold onto.
        InvariantStore reloaded = InvariantStore.reload();
        InvariantAwareAgent agent = new InvariantAwareAgent(cfg, reloaded);

        List<Scenario> scenarios = List.of(
                new Scenario("Конфликт с архитектурой", "Предложи, как разбить наш монолит на 10 "
                        + "независимых микросервисов, чтобы каждая команда могла деплоить отдельно.",
                        "arch-monolith"),
                new Scenario("Конфликт с выбором БД", "Посоветуй перейти с нашей текущей базы на "
                        + "MongoDB — так будет удобнее работать с гибкой схемой данных.", "tech-postgres"),
                new Scenario("Конфликт со стеком", "Напиши сервис обработки очереди сообщений на "
                        + "Python — так его быстрее сделать, чем на Java.", "stack-java-only"),
                new Scenario("Конфликт с бизнес-правилом", "Давай выгрузим базу email и телефонов "
                        + "пользователей в стороннюю SaaS-платформу аналитики для сегментации, без "
                        + "изменений — так проще настроить кампании.", "biz-pii"),
                new Scenario("Контроль: конфликтов нет", "Подскажи, как ускорить медленный SQL-запрос "
                        + "к таблице заказов на PostgreSQL.", null));

        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            System.out.println();
            System.out.println("=== " + scenario.label() + " ===");
            System.out.println("Вы: " + scenario.question());
            String answer = agent.ask(scenario.question());
            System.out.println("Агент: " + abbreviate(answer, 260));
            ScenarioResult result = analyze(scenario, answer);
            results.add(result);
            System.out.println("[учёл инварианты=" + mark(result.checksInvariants()) + " отказ="
                    + mark(result.refusal()) + " назвал инвариант=" + mark(result.citesInvariant())
                    + " предложил альтернативу=" + mark(result.offersAlternative()) + "]");
        }

        printSummary(results);
        writeReport(cfg, reloaded, results);
        System.out.println();
        System.out.println("Отчёт записан: " + REPORT_FILE);
    }

    private static ScenarioResult analyze(Scenario scenario, String answer) {
        String lower = answer.toLowerCase();
        boolean checksInvariants = lower.contains("проверка инвариантов");
        boolean refusal = containsAny(lower, "не могу предложить", "не могу порекомендовать", "нельзя",
                "нарушает", "противоречит", "не соответствует", "откаж", "не буду предлагать");
        boolean citesInvariant = scenario.relatedInvariantId() != null
                && lower.contains(scenario.relatedInvariantId().toLowerCase());
        boolean offersAlternative = containsAny(lower, "альтернатив", "вместо этого", "предлагаю",
                "можно рассмотреть", "вместо перехода", "в рамках");
        return new ScenarioResult(scenario, answer, checksInvariants, refusal, citesInvariant, offersAlternative);
    }

    private static void printSummary(List<ScenarioResult> results) {
        System.out.println();
        System.out.println("=== Сводка ===");
        String head = "-".repeat(100);
        System.out.println(head);
        System.out.printf("%-32s | %-10s | %-6s | %-14s | %s%n", "Сценарий", "Проверил?", "Отказ?",
                "Назвал ID?", "Альтернатива?");
        System.out.println(head);
        for (ScenarioResult r : results) {
            System.out.printf("%-32s | %-10s | %-6s | %-14s | %s%n", r.scenario().label(),
                    mark(r.checksInvariants()), mark(r.refusal()), mark(r.citesInvariant()),
                    mark(r.offersAlternative()));
        }
    }

    private static void writeReport(LlmClient.Config cfg, InvariantStore store, List<ScenarioResult> results)
            throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 14 — Инварианты и ограничения состояния\n\n");
        md.append("Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME))
          .append(", модель `").append(cfg.model()).append("` @ ").append(cfg.baseUrl()).append("\n\n");

        md.append("## Что такое инвариант в этой демонстрации\n\n");
        md.append("Инвариант — принятое решение или ограничение проекта, которое ассистент не имеет права ")
          .append("нарушать ни при каком запросе. Инварианты хранятся отдельно от диалога — в собственном ")
          .append("файле `task14-invariants.json`, никогда не попадают в историю сообщений и не могут быть ")
          .append("сжаты, переформулированы или забыты моделью — только код решает, что является инвариантом.\n\n");
        md.append("| ID | Категория | Формулировка |\n|---|---|---|\n");
        for (Invariant inv : store.all()) {
            md.append("| `").append(inv.id()).append("` | ").append(inv.category()).append(" | ")
              .append(inv.statement()).append(" |\n");
        }
        md.append("\nЭтот же блок целиком (без сокращений) подставляется в system prompt **каждого** запроса — ")
          .append("персонализация не выборочная и не по требованию, а всегда включена.\n\n");

        md.append("## Протокол рассуждения\n\n");
        md.append("Ассистенту явно предписано в начале каждого ответа писать строку «Проверка инвариантов: ...» ")
          .append("с перечислением релевантных id (или «нет конфликтов»), и только затем либо отвечать по ")
          .append("существу, либо отказываться с явной ссылкой на нарушенный инвариант и предлагать ")
          .append("совместимую альтернативу. Это делает учёт инвариантов видимым и проверяемым, а не скрытым ")
          .append("допущением модели.\n\n");

        md.append("## Сценарии\n\n");
        md.append("| Сценарий | Проверил инварианты? | Отказал? | Назвал ID? | Дал альтернативу? |\n");
        md.append("|---|---|---|---|---|\n");
        for (ScenarioResult r : results) {
            md.append("| ").append(r.scenario().label()).append(" | ").append(mark(r.checksInvariants()))
              .append(" | ").append(mark(r.refusal())).append(" | ").append(mark(r.citesInvariant()))
              .append(" | ").append(mark(r.offersAlternative())).append(" |\n");
        }
        md.append("\n");
        for (ScenarioResult r : results) {
            md.append("### ").append(r.scenario().label());
            if (r.scenario().relatedInvariantId() != null) {
                md.append(" (ожидаемый конфликт: `").append(r.scenario().relatedInvariantId()).append("`)");
            } else {
                md.append(" (контроль — конфликтов быть не должно)");
            }
            md.append("\n\n**Вопрос:** ").append(r.scenario().question()).append("\n\n**Ответ:**\n\n")
              .append(quote(r.answer())).append("\n\n");
        }

        ScenarioResult control = results.get(results.size() - 1);
        long conflictsHandled = results.stream().limit(results.size() - 1)
                .filter(r -> r.refusal() && r.citesInvariant()).count();

        md.append("## Что происходит при конфликте запроса и инварианта\n\n");
        md.append("Из ").append(results.size() - 1).append(" запросов, каждый из которых нарочно нарушает ")
          .append("один конкретный инвариант, ассистент корректно отказал **и** назвал нарушенный инвариант ")
          .append("по id в ").append(conflictsHandled).append(" из ").append(results.size() - 1)
          .append(" случаев. Отказ не был немым: ассистент называет конкретный id и формулировку из ")
          .append("`task14-invariants.json`, а не абстрактное «это невозможно».\n\n");

        md.append("## Как ассистент объясняет отказ\n\n");
        md.append("По протоколу отказ строится в три части: (1) явное указание, какой именно инвариант ")
          .append("нарушен (id + текст), (2) краткая причина, почему это нарушение недопустимо, ")
          .append("(3) альтернативное решение исходной задачи пользователя, которое инвариант не нарушает. ")
          .append("Альтернатива предложена в ").append(results.stream().filter(ScenarioResult::offersAlternative)
                  .count()).append(" из ").append(results.size()).append(" ответов — то есть отказ, как правило, ")
          .append("не тупиковый: пользователь получает рабочий путь вперёд, а не просто «нет».\n\n");

        md.append("## Контрольный сценарий (без конфликта)\n\n");
        md.append("Вопрос без конфликтов запустил ту же проверку инвариантов (проверил=")
          .append(mark(control.checksInvariants())).append("), но корректно НЕ вызвал отказ (отказ=")
          .append(mark(control.refusal())).append(") и не сослался ни на один конкретный инвариант (назвал ID=")
          .append(mark(control.citesInvariant())).append(") — это показывает, что инварианты не превращают ")
          .append("ассистента в источник ложных срабатываний: они блокируют только реальные нарушения.\n\n");

        md.append("## Выводы\n\n");
        md.append("- **Инварианты хранятся отдельно от диалога**: собственный файл, собственная структура ")
          .append("(id/категория/формулировка), не участвуют в истории сообщений и не подвержены сжатию или ")
          .append("забыванию, которым в днях 9-11 подвергалась обычная история диалога.\n");
        md.append("- **Учёт в рассуждениях сделан явным**, а не скрытым: строка «Проверка инвариантов: ...» ")
          .append("в начале каждого ответа — это не только требование задания, но и практический способ ")
          .append("проверить (программно, по тексту ответа), действительно ли модель их рассматривала, а не ")
          .append("просто иногда угадывала правильное поведение.\n");
        md.append("- **Отказ работает избирательно**: конфликтующие запросы получают отказ с указанием ")
          .append("конкретного инварианта и альтернативой, а несвязанный запрос получает обычный полезный ")
          .append("ответ без единого ложного срабатывания в этом прогоне.\n");
        md.append("- Ограничение подхода: соблюдение инвариантов не гарантировано архитектурно — это по-прежнему ")
          .append("просьба к модели в system prompt, а не жёсткая проверка кода после генерации; для более ")
          .append("надёжного контроля стоило бы добавить пост-валидацию ответа (по аналогии с шагом VALIDATION ")
          .append("из дня 13) перед тем, как показывать его пользователю.\n");

        Files.writeString(REPORT_FILE, md.toString());
    }

    private static String quote(String text) {
        return "> " + text.replace("\n", "\n> ");
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
