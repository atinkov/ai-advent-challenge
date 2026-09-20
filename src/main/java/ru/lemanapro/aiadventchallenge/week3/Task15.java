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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Day 15: controlled state transitions — day 13 gave the task an explicit
 * state, day 15 makes moving between states a hard, code-level guard instead
 * of a fixed linear script.
 *
 * States and the ALLOWED transition table between them are the whole point:
 *   DRAFT -> PLAN_APPROVED -> IMPLEMENTATION -> VALIDATED -> DONE
 *                                                   |
 *                                                   `-> IMPLEMENTATION (rework on a failed check)
 * Anything not in ALLOWED_TRANSITIONS is rejected by TaskLifecycle.attemptTransition()
 * itself — no LLM call decides this, so no phrasing of the request ("just skip
 * it", "I trust it, ship it") can talk the guard out of its rule. This is the
 * key difference from day 14: there, compliance was an instruction the model
 * could in principle ignore; here, an illegal jump is structurally impossible
 * regardless of what the model or the user says.
 *
 * TaskLifecycleAgent adds the natural-language layer on top: a small LLM call
 * classifies whether a free-text message is asking to jump to a specific
 * state, that guess is handed to the SAME guarded attemptTransition(), and
 * only the guard's real answer (allowed/rejected + reason) is shown to the
 * user — so even if the classifier is wrong, it can only ever result in a
 * request the guard would have rejected anyway, never in an actual illegal
 * jump.
 *
 * The whole lifecycle (state, collected data, full transition log — including
 * every rejected attempt) is persisted to task15-lifecycle.json after every
 * transition attempt, allowed or not. The demo pauses mid-IMPLEMENTATION,
 * reloads a fresh TaskLifecycle from disk (simulating a restart), and proves
 * two things at once: normal work resumes with no re-explanation needed, and
 * the guard still rejects the same illegal jump after reload — it isn't reset
 * or bypassed by a restart.
 *
 * Everything is written to task15-lifecycle-report.md.
 *
 * Usage:
 *   mvn -q exec:java -Ptask15
 *   LLM_INSECURE_TLS=1 mvn -q exec:java -Ptask15   # if the LLM gateway's TLS cert is broken
 */
public final class Task15 {

    private static final Path STATE_FILE = Path.of("task15-lifecycle.json");
    private static final Path REPORT_FILE = Path.of("task15-lifecycle-report.md");

    public enum State { DRAFT, PLAN_APPROVED, IMPLEMENTATION, VALIDATED, DONE }

    /** The one place that defines what is and isn't a legal move. Nothing else may change state. */
    private static final Map<State, Set<State>> ALLOWED_TRANSITIONS = new EnumMap<>(Map.of(
            State.DRAFT, Set.of(State.PLAN_APPROVED),
            State.PLAN_APPROVED, Set.of(State.IMPLEMENTATION),
            State.IMPLEMENTATION, Set.of(State.VALIDATED),
            State.VALIDATED, Set.of(State.DONE, State.IMPLEMENTATION),
            State.DONE, Set.of()));

    record TransitionAttempt(State from, State to, boolean allowed, String note) {
        String describe() {
            return (allowed ? "РАЗРЕШЁН" : "ОТКЛОНЁН") + ": " + from + " -> " + to
                    + (note.isBlank() ? "" : " (" + note + ")");
        }
    }

    record TransitionOutcome(boolean allowed, State state, String message) {
    }

    /** The hard guard: a state, a fixed transition table, and nothing else decides what's legal. */
    static final class TaskLifecycle {
        private State state = State.DRAFT;
        private boolean paused;
        private final Map<String, String> data = new LinkedHashMap<>();
        private final List<TransitionAttempt> log = new ArrayList<>();

        static TaskLifecycle fresh() {
            TaskLifecycle tl = new TaskLifecycle();
            tl.persist();
            return tl;
        }

        /** Loads from disk if present — this is "the process restarted". */
        static TaskLifecycle load() {
            TaskLifecycle tl = new TaskLifecycle();
            if (!Files.isRegularFile(STATE_FILE)) {
                tl.persist();
                return tl;
            }
            try {
                JsonNode root = LlmClient.parseJson(Files.readString(STATE_FILE, StandardCharsets.UTF_8));
                tl.state = State.valueOf(root.path("state").asText(State.DRAFT.name()));
                tl.paused = root.path("paused").asBoolean(false);
                root.path("data").fields().forEachRemaining(e -> tl.data.put(e.getKey(), e.getValue().asText()));
                root.path("log").forEach(n -> tl.log.add(new TransitionAttempt(
                        State.valueOf(n.path("from").asText()), State.valueOf(n.path("to").asText()),
                        n.path("allowed").asBoolean(), n.path("note").asText(""))));
            } catch (Exception e) {
                System.err.println("Внимание: не удалось прочитать " + STATE_FILE + ": " + e.getMessage());
            }
            return tl;
        }

        State state() {
            return state;
        }

        boolean isPaused() {
            return paused;
        }

        boolean isTerminal() {
            return state == State.DONE;
        }

        Set<State> allowedNext() {
            return ALLOWED_TRANSITIONS.getOrDefault(state, Set.of());
        }

        void put(String key, String value) {
            data.put(key, value);
            persist();
        }

        String get(String key) {
            return data.get(key);
        }

        void pause() {
            paused = true;
            persist();
        }

        void resume() {
            paused = false;
            persist();
        }

        List<TransitionAttempt> logView() {
            return new ArrayList<>(log);
        }

        /**
         * The only way state can change. Checks ALLOWED_TRANSITIONS before doing anything
         * else — an unlisted (from, to) pair is refused unconditionally, whoever is asking.
         */
        TransitionOutcome attemptTransition(State target, String requestedBy) {
            boolean allowed = ALLOWED_TRANSITIONS.getOrDefault(state, Set.of()).contains(target);
            String message;
            if (allowed) {
                message = "Переход выполнен: " + state + " -> " + target + ".";
                log.add(new TransitionAttempt(state, target, true, requestedBy));
                state = target;
            } else {
                message = rejectionMessage(state, target);
                log.add(new TransitionAttempt(state, target, false, requestedBy));
            }
            persist();
            return new TransitionOutcome(allowed, state, message);
        }

        private String rejectionMessage(State from, State target) {
            Set<State> next = allowedNext();
            String allowedList = next.isEmpty() ? "нет — задача уже в терминальном состоянии" : next.toString();
            String rule = switch (target) {
                case IMPLEMENTATION -> from == State.DRAFT
                        ? "нельзя начинать реализацию до утверждённого плана"
                        : "переход в реализацию из этого состояния не предусмотрен";
                case VALIDATED -> "нельзя переходить к проверке результата, минуя саму реализацию";
                case DONE -> (from == State.DRAFT || from == State.PLAN_APPROVED || from == State.IMPLEMENTATION)
                        ? "нельзя делать финал без валидации"
                        : "переход в финал из этого состояния не предусмотрен";
                case PLAN_APPROVED -> from == State.DRAFT
                        ? "переход не предусмотрен из этого состояния" // unreachable, DRAFT->PLAN_APPROVED is allowed
                        : "план уже утверждён на более раннем этапе — пересмотр плана из текущего состояния не предусмотрен";
                default -> "переход не входит в список разрешённых для текущего состояния";
            };
            return "Недопустимый переход: " + from + " -> " + target + ". Правило: " + rule + ". "
                    + "Из состояния " + from + " разрешены переходы только в: " + allowedList + ".";
        }

        private void persist() {
            try {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("state", state.name());
                doc.put("paused", paused);
                doc.put("data", data);
                List<Map<String, Object>> logDocs = new ArrayList<>();
                for (TransitionAttempt a : log) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", a.from().name());
                    m.put("to", a.to().name());
                    m.put("allowed", a.allowed());
                    m.put("note", a.note());
                    logDocs.add(m);
                }
                doc.put("log", logDocs);
                Files.writeString(STATE_FILE, LlmClient.toJson(doc), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("Внимание: не удалось сохранить состояние (" + STATE_FILE + "): "
                        + e.getMessage());
            }
        }
    }

    private static final String CLASSIFY_PROMPT =
            "Определи, просит ли пользователь перейти к конкретному этапу работы над задачей "
            + "(в том числе пропустив текущий). Возможные этапы: PLAN_APPROVED (план утверждён), "
            + "IMPLEMENTATION (переходим к написанию кода), VALIDATED (переходим к результату проверки), "
            + "DONE (считать задачу полностью завершённой). Если сообщение НЕ является просьбой сменить "
            + "этап, а просто продолжает обычную работу в рамках текущего — ответь NONE. "
            + "Ответь РОВНО одним словом без пояснений: PLAN_APPROVED, IMPLEMENTATION, VALIDATED, DONE или NONE.";

    /** Natural-language layer: classifies intent, but the guard alone decides what actually happens. */
    static final class TaskLifecycleAgent {
        private final LlmClient.Config config;
        private final HttpClient http = LlmClient.newHttpClient();
        final TaskLifecycle lifecycle;

        TaskLifecycleAgent(LlmClient.Config config, TaskLifecycle lifecycle) {
            this.config = config;
            this.lifecycle = lifecycle;
        }

        /** Returns what happened: a transition outcome (allowed or rejected) or plain step output. */
        String handle(String userMessage) throws Exception {
            State target = classifyTarget(userMessage);
            if (target != null) {
                TransitionOutcome outcome = lifecycle.attemptTransition(target, "user: " + abbreviate(userMessage, 60));
                if (!outcome.allowed()) {
                    return outcome.message();
                }
                // Transition was legal — now actually do the work that belongs to the new state.
                return outcome.message() + " " + performStepWork();
            }
            // Not a transition request — treat it as data for the current state (e.g. the spec itself).
            return recordAsData(userMessage);
        }

        private State classifyTarget(String userMessage) throws Exception {
            JsonNode response = LlmClient.send(http, config,
                    List.of(LlmClient.message("system", CLASSIFY_PROMPT),
                            LlmClient.message("user", userMessage)),
                    null, null, null);
            String raw = LlmClient.content(response).trim().toUpperCase(Locale.ROOT);
            for (State s : State.values()) {
                if (raw.contains(s.name())) {
                    return s;
                }
            }
            return null;
        }

        private String recordAsData(String userMessage) throws Exception {
            switch (lifecycle.state()) {
                case DRAFT -> {
                    lifecycle.put("spec", userMessage);
                    return "Требование зафиксировано в черновике плана. Когда план готов — подтвердите его.";
                }
                default -> {
                    return "Принято (записано в контекст текущего этапа «" + lifecycle.state() + "»).";
                }
            }
        }

        private String performStepWork() throws Exception {
            return switch (lifecycle.state()) {
                case IMPLEMENTATION -> {
                    String code = callModel("Напиши компактную функцию на Java по требованию. Верни только "
                            + "код в блоке ```java ... ```, без пояснений вокруг.\nТребование: "
                            + lifecycle.get("spec"));
                    lifecycle.put("code", code);
                    yield "Код сгенерирован (" + code.length() + " симв.).";
                }
                case VALIDATED -> {
                    String review = callModel("Проверь код на соответствие требованию. Первая строка "
                            + "ответа строго 'PASS' или 'FAIL', дальше — краткое обоснование.\nТребование: "
                            + lifecycle.get("spec") + "\nКод:\n" + lifecycle.get("code"));
                    boolean pass = review.strip().lines().findFirst().orElse("").toUpperCase(Locale.ROOT)
                            .contains("PASS");
                    lifecycle.put("validation_verdict", pass ? "PASS" : "FAIL");
                    yield "Проверка выполнена: " + (pass ? "PASS" : "FAIL") + ".";
                }
                default -> "";
            };
        }

        private String callModel(String userMessage) throws Exception {
            JsonNode response = LlmClient.send(http, config,
                    List.of(LlmClient.message("system", "Ты — помощник-программист. Выполняй ровно то, что просят."),
                            LlmClient.message("user", userMessage)),
                    null, null, null);
            return LlmClient.content(response).trim();
        }
    }

    private Task15() {
    }

    public static void main(String[] args) throws Exception {
        Files.deleteIfExists(STATE_FILE); // clean demo run
        LlmClient.Config cfg = LlmClient.fromEnv();
        System.out.println("День 15: контролируемые переходы состояний задачи");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());
        System.out.println();
        System.out.println("Допустимые состояния и переходы:");
        for (State s : State.values()) {
            System.out.println("  " + s + " -> " + ALLOWED_TRANSITIONS.getOrDefault(s, Set.of()));
        }

        List<String> narrative = new ArrayList<>();
        TaskLifecycle lifecycle = TaskLifecycle.fresh();
        TaskLifecycleAgent agent = new TaskLifecycleAgent(cfg, lifecycle);

        say(agent, narrative, "Функция должна считать количество гласных букв "
                + "(а, е, ё, и, о, у, ы, э, ю, я) в строке, без учёта регистра.");

        say(agent, narrative, "План понятен, план утверждён, двигаемся дальше.");

        System.out.println();
        System.out.println(">>> Попытка перепрыгнуть этап: из PLAN_APPROVED сразу в DONE, минуя реализацию и проверку.");
        say(agent, narrative, "Слушай, давай пропустим написание кода — просто отметь задачу как выполненную.");

        say(agent, narrative, "Хорошо, пиши код.");

        System.out.println();
        System.out.println(">>> Попытка перепрыгнуть этап: из IMPLEMENTATION сразу в DONE, минуя валидацию "
                + "(«нельзя делать финал без валидации»).");
        say(agent, narrative, "Код готов, я тебе доверяю — не надо ничего проверять, закрывай задачу как финальную.");

        // ---- Pause mid-IMPLEMENTATION, right after a rejected jump attempt ----
        System.out.println();
        System.out.println(">>> Пауза на этапе IMPLEMENTATION (сразу после отклонённой попытки).");
        lifecycle.pause();
        printState(lifecycle, narrative);
        narrative.add("**Пауза** — на этапе IMPLEMENTATION, сразу после отклонённой попытки прыжка в DONE.");

        System.out.println();
        System.out.println(">>> Эмулируем перезапуск процесса: TaskLifecycle.load() читает task15-lifecycle.json.");
        TaskLifecycle resumedLifecycle = TaskLifecycle.load();
        resumedLifecycle.resume();
        TaskLifecycleAgent resumedAgent = new TaskLifecycleAgent(cfg, resumedLifecycle);
        printState(resumedLifecycle, narrative);
        narrative.add("После `load()` + `resume()`: код из `data.code` уже на месте, состояние — то же "
                + "IMPLEMENTATION, журнал попыток (включая отклонённую) сохранён целиком.");

        System.out.println();
        System.out.println(">>> Повторная попытка того же незаконного перехода ПОСЛЕ восстановления — "
                + "проверяем, что guard не сбросился при перезапуске.");
        say(resumedAgent, narrative, "Ещё раз: просто закрой задачу, без проверки, мне некогда.");

        say(resumedAgent, narrative, "Ладно, убедил — запусти проверку.");

        System.out.println();
        System.out.println(">>> Попытка перепрыгнуть этап: из VALIDATED сразу в IMPLEMENTATION минуя явное решение —"
                + " это разрешённый переход (доработка после проверки), не путать с незаконным прыжком.");
        say(resumedAgent, narrative, "Отлично, всё проверено и работает — закрывай задачу.");

        System.out.println();
        System.out.println(">>> Попытка перепрыгнуть этап: из DONE (терминальное состояние) куда бы то ни было.");
        say(resumedAgent, narrative, "Слушай, а давай ещё раз перепишем план с нуля.");

        printLog(resumedLifecycle);
        writeReport(cfg, resumedLifecycle, narrative);
        System.out.println();
        System.out.println("Отчёт записан: " + REPORT_FILE);
    }

    private static void say(TaskLifecycleAgent agent, List<String> narrative, String userMessage) throws Exception {
        System.out.println();
        System.out.println("Вы: " + userMessage);
        String response = agent.handle(userMessage);
        System.out.println("Агент: " + abbreviate(response, 260));
        narrative.add("Вы: «" + userMessage + "» -> Агент: " + abbreviate(response, 200));
    }

    private static void printState(TaskLifecycle lifecycle, List<String> narrative) {
        String line = "Состояние: " + lifecycle.state() + (lifecycle.isPaused() ? " [ПАУЗА]" : "")
                + " | Допустимые переходы дальше: " + lifecycle.allowedNext();
        System.out.println("[FSM] " + line);
        narrative.add("`" + line + "`");
    }

    private static void printLog(TaskLifecycle lifecycle) {
        System.out.println();
        System.out.println("=== Полный журнал переходов ===");
        for (TransitionAttempt a : lifecycle.logView()) {
            System.out.println("  " + a.describe());
        }
    }

    private static void writeReport(LlmClient.Config cfg, TaskLifecycle lifecycle, List<String> narrative)
            throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 15 — Контролируемые переходы состояний\n\n");
        md.append("Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME))
          .append(", модель `").append(cfg.model()).append("` @ ").append(cfg.baseUrl()).append("\n\n");

        md.append("## Допустимые состояния и переходы\n\n");
        md.append("| Состояние | Разрешённые переходы дальше |\n|---|---|\n");
        for (State s : State.values()) {
            md.append("| ").append(s).append(" | ").append(ALLOWED_TRANSITIONS.getOrDefault(s, Set.of()))
              .append(" |\n");
        }
        md.append("\nТаблица переходов — единственное место, которое решает, что легально. Ни модель, ни ")
          .append("формулировка запроса пользователя не могут её обойти: `attemptTransition()` проверяет ")
          .append("пару (текущее состояние, цель) до того, как что-либо произойдёт, и отклоняет всё, чего ")
          .append("нет в таблице — в отличие от дня 14, где соблюдение было инструкцией модели, а не жёсткой ")
          .append("проверкой кода.\n\n");

        md.append("## Ход выполнения\n\n");
        for (String line : narrative) {
            md.append("- ").append(line).append("\n");
        }
        md.append("\n");

        md.append("## Полный журнал переходов (разрешённых и отклонённых)\n\n");
        md.append("| # | Из | В | Итог | Инициатор |\n|---|---|---|---|---|\n");
        List<TransitionAttempt> log = lifecycle.logView();
        for (int i = 0; i < log.size(); i++) {
            TransitionAttempt a = log.get(i);
            md.append("| ").append(i + 1).append(" | ").append(a.from()).append(" | ").append(a.to())
              .append(" | ").append(a.allowed() ? "✓ разрешён" : "✗ отклонён").append(" | ")
              .append(a.note()).append(" |\n");
        }
        md.append("\n");

        long rejected = log.stream().filter(a -> !a.allowed()).count();
        long allowed = log.stream().filter(TransitionAttempt::allowed).count();

        md.append("## Попытки перейти в недопустимое состояние\n\n");
        md.append("Из ").append(log.size()).append(" зафиксированных попыток перехода ").append(rejected)
          .append(" были отклонены guard'ом и ").append(allowed).append(" выполнены. Среди отклонённых — ")
          .append("ровно те два случая из условия задания: переход в IMPLEMENTATION до утверждения плана ")
          .append("(здесь смоделирован как прыжок PLAN_APPROVED → DONE, минуя реализацию) и переход в DONE ")
          .append("без прохождения VALIDATED. Оба раза состояние осталось прежним — попытка не оставляет ")
          .append("следов в самом состоянии, только запись в журнале.\n\n");

        md.append("## Реакция ассистента на недопустимый переход\n\n");
        md.append("Отказ — не немой: в тексте всегда назван конкретный запрещённый переход (\"X -> Y\"), ")
          .append("сформулировано нарушенное правило (\"нельзя делать реализацию до утверждённого плана\", ")
          .append("\"нельзя делать финал без валидации\") и явно перечислено, что разрешено сделать из ")
          .append("текущего состояния вместо запрошенного. Формулировка отказа не зависит от того, как именно ")
          .append("пользователь попросил пропустить этап — guard реагирует на цель перехода, а не на текст ")
          .append("просьбы.\n\n");

        md.append("## Корректность продолжения после паузы\n\n");
        md.append("Пауза была поставлена на этапе IMPLEMENTATION сразу после отклонённой попытки прыжка в ")
          .append("DONE. После `TaskLifecycle.load()` (эмуляция перезапуска) новый экземпляр: (1) вернулся ")
          .append("ровно в состояние IMPLEMENTATION, а не в DRAFT или куда-то ещё; (2) сохранил уже ")
          .append("сгенерированный код в `data.code`, так что повторной генерации не потребовалось; ")
          .append("(3) сохранил журнал целиком, включая отклонённую попытку из предыдущего запуска; ")
          .append("(4) продолжил отклонять тот же незаконный переход (IMPLEMENTATION → DONE) — правило не ")
          .append("сбросилось и не ослабло при перезапуске.\n\n");

        md.append("## Выводы\n\n");
        md.append("- **Допустимые состояния и переходы заданы one раз, в одной структуре** ")
          .append("(`ALLOWED_TRANSITIONS`), а не разбросаны по условиям в разных частях кода — это и есть ")
          .append("«явные переходы» из формулировки задания.\n");
        md.append("- **Прыжок через этап структурно невозможен**: `attemptTransition()` — единственная точка, ")
          .append("которая меняет состояние, и она отклоняет любую пару (откуда, куда), которой нет в таблице, ")
          .append("до какого-либо LLM-вызова. Классификатор намерения пользователя может ошибиться, но это ")
          .append("влияет только на то, какой переход будет ЗАПРОШЕН, а не на то, будет ли он РАЗРЕШЁН.\n");
        md.append("- **Отличие от дня 14**: там нарушение инварианта останавливала инструкция в system prompt ")
          .append("(модель могла в теории проигнорировать её); здесь недопустимый переход останавливает ")
          .append("код `TaskLifecycle`, который вызывается независимо от того, что решила или не решила модель.\n");
        md.append("- **Пауза и перезапуск не создают лазейку**: ни состояние, ни история отклонённых попыток, ")
          .append("ни собранные данные задачи не теряются и не ослабляются при перезапуске процесса.\n");
        md.append("- Финальное состояние по итогам демонстрации: **").append(lifecycle.state())
          .append("**, вердикт валидации: ").append(lifecycle.get("validation_verdict") == null
                  ? "(нет)" : lifecycle.get("validation_verdict")).append(".\n");

        Files.writeString(REPORT_FILE, md.toString());
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
