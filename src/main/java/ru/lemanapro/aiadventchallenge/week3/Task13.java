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
 * Day 13: task state as an explicit finite state machine.
 *
 * Every task the agent works on has three things that must be tracked, not
 * inferred from re-reading the whole dialogue each time:
 *   - этап задачи   (Stage)       — PLANNING -> EXECUTION -> VALIDATION -> DONE
 *   - текущий шаг   (StepDef)     — one fixed point inside that stage
 *   - ожидаемое действие (expected action) — either "waiting for the user to
 *     answer something" (INPUT) or "the agent must do something itself"
 *     (SYSTEM), spelled out in human words.
 *
 * StateMachine below is a tiny, literal FSM over a fixed script of steps
 * (STEPS): three INPUT steps to gather a spec (PLANNING), one SYSTEM step
 * that writes code (EXECUTION), one SYSTEM step that reviews it (VALIDATION,
 * with a back-edge to EXECUTION on a failed review, capped at MAX_ATTEMPTS),
 * and a TERMINAL step (DONE). submit(...) advances an INPUT step, step()
 * performs exactly one SYSTEM step and nothing more — the caller decides the
 * pace, which is what makes "pause between any two steps" possible instead of
 * the machine running the whole task in one uninterruptible call.
 *
 * The whole state (which step, collected data, log) is persisted to
 * task13-state.json after every transition. pause()/resume() only flip a flag
 * and persist; the real proof of "continue without re-explaining" is that a
 * brand-new StateMachine.load(...) instance — standing in for a restarted
 * process — picks up at the exact same step with the exact same collected
 * data (including an already-generated piece of code), so nothing has to be
 * re-asked or regenerated.
 *
 * Everything is written to task13-state-machine-report.md.
 *
 * Usage:
 *   mvn -q exec:java -Ptask13
 *   LLM_INSECURE_TLS=1 mvn -q exec:java -Ptask13   # if the LLM gateway's TLS cert is broken
 */
public final class Task13 {

    private static final Path STATE_FILE = Path.of("task13-state.json");
    private static final Path REPORT_FILE = Path.of("task13-state-machine-report.md");
    private static final int MAX_ATTEMPTS = 2;

    public enum Stage { PLANNING, EXECUTION, VALIDATION, DONE }

    private enum StepType { INPUT, SYSTEM, TERMINAL }

    private record StepDef(Stage stage, int localIndex, int stageTotal, StepType type,
                           String description, String dataKey) {
    }

    /** The fixed script this demo runs. Index in this list IS the persisted "current step" pointer. */
    private static final List<StepDef> STEPS = List.of(
            new StepDef(Stage.PLANNING, 0, 3, StepType.INPUT,
                    "описание задачи: что должна делать функция", "spec"),
            new StepDef(Stage.PLANNING, 1, 3, StepType.INPUT,
                    "пример входных и выходных данных", "example"),
            new StepDef(Stage.PLANNING, 2, 3, StepType.INPUT,
                    "подтверждение плана пользователем", "confirmation"),
            new StepDef(Stage.EXECUTION, 0, 1, StepType.SYSTEM,
                    "сгенерировать код функции по собранным требованиям", "code"),
            new StepDef(Stage.VALIDATION, 0, 1, StepType.SYSTEM,
                    "проверить код на соответствие требованиям", "validation_verdict"),
            new StepDef(Stage.DONE, 0, 1, StepType.TERMINAL,
                    "задача завершена", null));
    private static final int EXECUTION_INDEX = 3;
    private static final int VALIDATION_INDEX = 4;
    private static final int DONE_INDEX = 5;

    /** The explicit FSM: stage + current step + expected action are always derivable from stepPointer. */
    static final class StateMachine {
        private final LlmClient.Config config;
        private final HttpClient http;
        private int stepPointer;
        private int attempt = 1;
        private boolean paused;
        private final Map<String, String> data = new LinkedHashMap<>();
        private final List<String> log = new ArrayList<>();

        private StateMachine(LlmClient.Config config) {
            this.config = config;
            this.http = LlmClient.newHttpClient();
        }

        static StateMachine fresh(LlmClient.Config config) {
            StateMachine sm = new StateMachine(config);
            sm.log.add("Задача создана: этап=" + sm.stage() + ", шаг=" + sm.currentStep().description());
            sm.persist();
            return sm;
        }

        /** Loads state from disk if present, otherwise starts fresh — this is "the process restarted". */
        static StateMachine load(LlmClient.Config config) {
            StateMachine sm = new StateMachine(config);
            if (!Files.isRegularFile(STATE_FILE)) {
                sm.log.add("Файл состояния не найден — новая задача.");
                sm.persist();
                return sm;
            }
            try {
                JsonNode root = LlmClient.parseJson(Files.readString(STATE_FILE, StandardCharsets.UTF_8));
                sm.stepPointer = root.path("stepPointer").asInt(0);
                sm.attempt = root.path("attempt").asInt(1);
                sm.paused = root.path("paused").asBoolean(false);
                root.path("data").fields().forEachRemaining(e -> sm.data.put(e.getKey(), e.getValue().asText()));
                root.path("log").forEach(n -> sm.log.add(n.asText()));
                sm.log.add("Состояние загружено с диска: этап=" + sm.stage()
                        + ", шаг=" + sm.currentStep().description() + ", paused=" + sm.paused);
            } catch (Exception e) {
                System.err.println("Внимание: не удалось прочитать " + STATE_FILE + ": " + e.getMessage());
            }
            return sm;
        }

        Stage stage() {
            return STEPS.get(stepPointer).stage();
        }

        StepDef currentStep() {
            return STEPS.get(stepPointer);
        }

        boolean isPaused() {
            return paused;
        }

        boolean isDone() {
            return stepPointer == DONE_INDEX;
        }

        boolean isAwaitingUserInput() {
            return currentStep().type() == StepType.INPUT;
        }

        boolean isAwaitingSystemAction() {
            return currentStep().type() == StepType.SYSTEM;
        }

        /** Human-readable "ожидаемое действие" for the current step, without re-explaining the whole task. */
        String expectedAction() {
            StepDef s = currentStep();
            return switch (s.type()) {
                case INPUT -> "ждём от пользователя: " + s.description();
                case SYSTEM -> "агент должен выполнить: " + s.description();
                case TERMINAL -> "ничего — задача завершена";
            };
        }

        String describe() {
            StepDef s = currentStep();
            return "Этап: " + s.stage() + " (шаг " + (s.localIndex() + 1) + "/" + s.stageTotal() + ")"
                    + (paused ? " [ПАУЗА]" : "")
                    + " | Ожидаемое действие: " + expectedAction();
        }

        void pause() {
            paused = true;
            log.add("Пауза на этапе " + stage() + ", шаг «" + currentStep().description() + "».");
            persist();
        }

        /** Only clears the flag; whoever drives the machine decides what to call next. */
        void resume() {
            paused = false;
            log.add("Продолжение с этапа " + stage() + ", шаг «" + currentStep().description()
                    + "» — без повторного объяснения задачи и без повторного сбора уже известных данных.");
            persist();
        }

        /** Consumes one INPUT step. Fails loudly if the machine isn't currently waiting on the user. */
        void submit(String userInput) {
            if (!isAwaitingUserInput()) {
                throw new IllegalStateException("Машина не ждёт ввод пользователя сейчас: " + describe());
            }
            StepDef s = currentStep();
            data.put(s.dataKey(), userInput);
            log.add("[" + s.stage() + "] получен ответ пользователя на «" + s.description() + "»: "
                    + abbreviate(userInput, 100));
            stepPointer++;
            log.add("-> переход: " + describe());
            persist();
        }

        /** Performs exactly one SYSTEM step (one LLM call) and stops — the caller controls the pace. */
        void step() throws Exception {
            if (!isAwaitingSystemAction()) {
                throw new IllegalStateException("Машина не ждёт системного действия сейчас: " + describe());
            }
            if (stepPointer == EXECUTION_INDEX) {
                runExecution();
            } else if (stepPointer == VALIDATION_INDEX) {
                runValidation();
            }
            persist();
        }

        private void runExecution() throws Exception {
            StringBuilder prompt = new StringBuilder("Напиши компактную функцию на Java по требованиям ниже. ")
                    .append("Верни только код в блоке ```java ... ```, без пояснений вокруг.\n");
            prompt.append("Требование: ").append(data.get("spec")).append("\n");
            prompt.append("Пример входных/выходных данных: ").append(data.get("example")).append("\n");
            if (data.containsKey("validation_feedback")) {
                prompt.append("Замечание из предыдущей проверки, обязательно исправь: ")
                      .append(data.get("validation_feedback")).append("\n");
            }
            String code = callModel(prompt.toString());
            data.put("code", code);
            log.add("[EXECUTION] попытка " + attempt + ": код сгенерирован (" + code.length() + " симв.).");
            stepPointer = VALIDATION_INDEX;
            log.add("-> переход: " + describe());
        }

        private void runValidation() throws Exception {
            StringBuilder prompt = new StringBuilder("Проверь код на соответствие требованию. Первая строка ответа "
                    + "строго 'PASS' или 'FAIL', дальше — краткое обоснование (1-2 предложения).\n");
            prompt.append("Требование: ").append(data.get("spec")).append("\n");
            prompt.append("Пример входных/выходных данных: ").append(data.get("example")).append("\n");
            prompt.append("Код:\n").append(data.get("code")).append("\n");
            String review = callModel(prompt.toString());
            String verdictLine = review.strip().lines().findFirst().orElse("").toUpperCase();
            boolean pass = verdictLine.contains("PASS");
            data.put("validation_verdict", pass ? "PASS" : "FAIL");
            data.put("validation_feedback", review);
            log.add("[VALIDATION] попытка " + attempt + ": вердикт=" + (pass ? "PASS" : "FAIL"));
            if (pass) {
                data.remove("validation_feedback");
                stepPointer = DONE_INDEX;
            } else if (attempt < MAX_ATTEMPTS) {
                attempt++;
                stepPointer = EXECUTION_INDEX;
            } else {
                data.put("validation_verdict", "FAIL (лимит попыток исчерпан: " + MAX_ATTEMPTS + ")");
                stepPointer = DONE_INDEX;
            }
            log.add("-> переход: " + describe());
        }

        private String callModel(String userMessage) throws Exception {
            JsonNode response = LlmClient.send(http, config,
                    List.of(LlmClient.message("system", "Ты — помощник-программист. Выполняй ровно то, что просят."),
                            LlmClient.message("user", userMessage)),
                    null, null, null);
            return LlmClient.content(response).trim();
        }

        Map<String, String> dataView() {
            return new LinkedHashMap<>(data);
        }

        List<String> logView() {
            return new ArrayList<>(log);
        }

        int attempt() {
            return attempt;
        }

        private void persist() {
            try {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("version", 1);
                doc.put("stepPointer", stepPointer);
                doc.put("attempt", attempt);
                doc.put("paused", paused);
                doc.put("data", data);
                doc.put("log", log);
                Files.writeString(STATE_FILE, LlmClient.toJson(doc), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("Внимание: не удалось сохранить состояние (" + STATE_FILE + "): "
                        + e.getMessage());
            }
        }
    }

    private Task13() {
    }

    public static void main(String[] args) throws Exception {
        Files.deleteIfExists(STATE_FILE); // clean demo run
        LlmClient.Config cfg = LlmClient.fromEnv();
        System.out.println("День 13: состояние задачи как конечный автомат (Task State Machine)");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());

        List<String> narrative = new ArrayList<>();

        StateMachine sm = StateMachine.fresh(cfg);
        printState(sm, narrative);

        submit(sm, narrative, "Функция должна проверять, является ли переданная строка палиндромом "
                + "(без учёта регистра и пробелов).");

        // ---- Pause #1: mid-PLANNING, before the example has even been given ----
        System.out.println();
        System.out.println(">>> Пауза во время PLANNING (после первого ответа, до второго вопроса).");
        sm.pause();
        printState(sm, narrative);
        narrative.add("**Пауза #1** — на этапе PLANNING, сразу после первого ответа пользователя, "
                + "до того как задан второй вопрос.");

        System.out.println();
        System.out.println(">>> Эмулируем перезапуск процесса: новый StateMachine.load(...) читает task13-state.json.");
        StateMachine resumed1 = StateMachine.load(cfg);
        resumed1.resume();
        printState(resumed1, narrative);
        narrative.add("После `load()` + `resume()` машина сразу показывает шаг 2/3 PLANNING — "
                + "**не** переспрашивая описание задачи, которое уже есть в `data.spec`.");

        submit(resumed1, narrative, "Пример: вход «А роза упала на лапу Азора» → true; вход «Привет» → false.");
        submit(resumed1, narrative, "Да, всё верно, начинай выполнение.");

        System.out.println();
        System.out.println(">>> После подтверждения плана этап сменился на EXECUTION автоматически "
                + "(ожидаемое действие стало системным, а не вопросом к пользователю).");
        printState(resumed1, narrative);

        System.out.println();
        System.out.println(">>> Выполняем системный шаг EXECUTION (один вызов модели).");
        resumed1.step();
        printState(resumed1, narrative);
        System.out.println("Сгенерированный код:");
        System.out.println(abbreviate(resumed1.dataView().get("code"), 300));

        // ---- Pause #2: right after EXECUTION, before VALIDATION runs ----
        System.out.println();
        System.out.println(">>> Пауза сразу после EXECUTION, до запуска VALIDATION.");
        resumed1.pause();
        printState(resumed1, narrative);
        narrative.add("**Пауза #2** — на этапе VALIDATION (сразу после EXECUTION), до того как проверка "
                + "успела запуститься.");

        System.out.println();
        System.out.println(">>> Снова эмулируем перезапуск.");
        StateMachine resumed2 = StateMachine.load(cfg);
        resumed2.resume();
        printState(resumed2, narrative);
        narrative.add("После второго `load()` + `resume()` код из `data.code` уже на месте — "
                + "EXECUTION не выполняется повторно, машина сразу готова выполнить VALIDATION.");

        System.out.println();
        System.out.println(">>> Выполняем системные шаги до DONE (VALIDATION, с возможным возвратом в EXECUTION).");
        while (!resumed2.isDone()) {
            resumed2.step();
            printState(resumed2, narrative);
        }

        System.out.println();
        System.out.println("=== Итог ===");
        System.out.println("Вердикт валидации: " + resumed2.dataView().get("validation_verdict"));
        System.out.println("Финальный код:");
        System.out.println(resumed2.dataView().getOrDefault("code", "(нет)"));

        writeReport(cfg, resumed2, narrative);
        System.out.println();
        System.out.println("Отчёт записан: " + REPORT_FILE);
    }

    private static void submit(StateMachine sm, List<String> narrative, String userInput) {
        System.out.println();
        System.out.println("Вы: " + userInput);
        sm.submit(userInput);
        printState(sm, narrative);
    }

    private static void printState(StateMachine sm, List<String> narrative) {
        String line = sm.describe();
        System.out.println("[FSM] " + line);
        narrative.add("`" + line + "`");
    }

    private static void writeReport(LlmClient.Config cfg, StateMachine sm, List<String> narrative)
            throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# День 13 — Состояние задачи как конечный автомат\n\n");
        md.append("Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME))
          .append(", модель `").append(cfg.model()).append("` @ ").append(cfg.baseUrl()).append("\n\n");

        md.append("## Модель состояния\n\n");
        md.append("Три поля, которые всегда явно выводимы из одного указателя `stepPointer` по фиксированному ")
          .append("сценарию шагов, без пересчёта по истории диалога:\n\n");
        md.append("| # | Этап | Шаг | Тип | Ожидаемое действие |\n");
        md.append("|---|---|---|---|---|\n");
        for (int i = 0; i < STEPS.size(); i++) {
            StepDef s = STEPS.get(i);
            String type = switch (s.type()) {
                case INPUT -> "INPUT (ждём пользователя)";
                case SYSTEM -> "SYSTEM (действует агент)";
                case TERMINAL -> "TERMINAL";
            };
            md.append("| ").append(i).append(" | ").append(s.stage()).append(" | ").append(s.description())
              .append(" | ").append(type).append(" | ").append(s.description()).append(" |\n");
        }
        md.append("\nПереходы: 0→1→2→3→4→5 по порядку; из шага 4 (VALIDATION) при вердикте FAIL и оставшихся ")
          .append("попытках — обратно на шаг 3 (EXECUTION) с обратной связью в `data.validation_feedback` ")
          .append("(лимит ").append(MAX_ATTEMPTS).append(" попытки, иначе принудительно шаг 5, DONE).\n\n");
        md.append("`submit(value)` продвигает только INPUT-шаг; `step()` выполняет ровно один SYSTEM-шаг ")
          .append("(один вызов модели) и останавливается — паузу можно вставить между любыми двумя вызовами, ")
          .append("а не только «между этапами».\n\n");

        md.append("## Ход выполнения\n\n");
        for (String line : narrative) {
            md.append("- ").append(line).append("\n");
        }
        md.append("\n");

        md.append("## Полный журнал переходов (persisted в task13-state.json)\n\n");
        for (String entry : sm.logView()) {
            md.append("- ").append(entry).append("\n");
        }
        md.append("\n");

        md.append("## Собранные данные задачи\n\n");
        sm.dataView().forEach((k, v) -> {
            md.append("### ").append(k).append("\n\n");
            if (v.contains("\n") || v.length() > 200) {
                md.append(quote(v)).append("\n\n");
            } else {
                md.append(v).append("\n\n");
            }
        });

        md.append("## Выводы\n\n");
        md.append("- **Формализованное состояние** (этап + шаг + ожидаемое действие) вместо \"что там было ")
          .append("в диалоге\" даёт машине один однозначный указатель (`stepPointer`), из которого выводится ")
          .append("всё остальное — не нужно перечитывать историю, чтобы понять, что делать дальше.\n");
        md.append("- **Пауза на любом этапе подтверждена дважды**: один раз в середине INPUT-стадии PLANNING ")
          .append("(между двумя вопросами к пользователю) и один раз сразу после SYSTEM-стадии EXECUTION, ")
          .append("до того как выполнилась VALIDATION — оба раза `pause()`/`persist()` сохранили состояние ")
          .append("без потери данных.\n");
        md.append("- **Продолжение без повторных объяснений подтверждено данными, а не только логами**: ")
          .append("после первого возобновления машина не переспросила описание задачи (оно уже было в ")
          .append("`data.spec`), а после второго — не сгенерировала код заново (он уже был в `data.code`), ")
          .append("а сразу перешла к валидации. Ни разу пользователю или модели не пришлось повторно объяснять, ")
          .append("что делает функция.\n");
        md.append("- **Смена типа ожидаемого действия происходит автоматически**: как только собраны все три ")
          .append("INPUT-шага PLANNING, следующий шаг сам становится SYSTEM (EXECUTION) — агенту не нужна ")
          .append("отдельная команда «теперь пиши код», это следствие структуры сценария, а не отдельная ")
          .append("реплика пользователя.\n");
        md.append("- Итоговый вердикт валидации: **").append(sm.dataView().get("validation_verdict"))
          .append("**, потрачено попыток генерации кода: ").append(sm.attempt()).append(" из ")
          .append(MAX_ATTEMPTS).append(".\n");

        Files.writeString(REPORT_FILE, md.toString());
    }

    private static String quote(String text) {
        return "> " + text.replace("\n", "\n> ");
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
