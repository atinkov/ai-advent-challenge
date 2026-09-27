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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Day 11: an explicit memory model for the agent — three separate layers instead
 * of one flat dialogue history:
 *
 *   SHORT_TERM — the current dialogue (raw user/assistant turns of the running
 *                session only; never persisted, gone as soon as the session ends);
 *   WORKING    — data of the current task (a small key-value scratchpad the code
 *                fills in explicitly; wiped every time a new task starts);
 *   LONG_TERM  — profile, decisions and knowledge that outlive any single task or
 *                session (persisted to task11-long-term-memory.json, loaded back
 *                on the next run).
 *
 * The point of the exercise: nothing is auto-extracted by the model. The calling
 * code always decides which layer a piece of information belongs to
 * (MemoryStore.commitLongTerm / setWorkingFact / addDialogue), and when building
 * a request it explicitly decides which layers to include
 * (MemoryStore.assemble(...)). That is what "explicit" means here, as opposed to
 * day 9/10's LlmAgent, where a rolling summary or a facts block is produced BY
 * the model.
 *
 * Demo (two simulated sessions of the same user + a layer-ablation experiment):
 *   Session 1 — user introduces themselves (-> LONG_TERM) and starts task
 *               "код-ревью" (-> WORKING); agent builds a checklist.
 *   Session 2 — a fresh MemoryStore instance reloads LONG_TERM from disk (profile
 *               survives), but WORKING and SHORT_TERM start empty (new task, new
 *               dialogue) — a probe question shows the checklist details from
 *               session 1 are gone, only the profile remains.
 *   Ablation  — the same final question is sent four times with a different
 *               subset of layers included each time, to see in the actual answers
 *               what each layer contributes.
 *
 * Everything is written to task11-memory-report.md (regenerated on every run).
 *
 * Usage:
 *   mvn -q exec:java -Ptask11
 */
public final class Task11 {

    private static final Path LONG_TERM_FILE = Path.of("task11-long-term-memory.json");
    private static final Path REPORT_FILE = Path.of("task11-memory-report.md");
    private static final String BASE_SYSTEM_PROMPT =
            "Ты — персональный ассистент разработчика. Отвечай по существу.";

    /** The three memory layers this agent keeps separate. */
    public enum Layer {
        SHORT_TERM, WORKING, LONG_TERM
    }

    /**
     * Holds the three layers as separate data structures and explicitly assembles
     * a request from whichever subset of them the caller asks for. Nothing here
     * is written to a layer implicitly — every write is a named method call from
     * the outside.
     */
    static final class MemoryStore {

        /** Current dialogue only. Not persisted, not shared between sessions. */
        private final List<Map<String, String>> shortTerm = new ArrayList<>();
        /** Scratch data of whatever task is active right now. Wiped by startTask(). */
        private final Map<String, String> working = new LinkedHashMap<>();
        /** Profile / decisions / knowledge. Persisted to longTermFile, survives restarts. */
        private final Map<String, String> longTerm = new LinkedHashMap<>();
        private final Path longTermFile;
        private String currentTask = "(нет активной задачи)";

        MemoryStore(Path longTermFile) {
            this.longTermFile = longTermFile;
            loadLongTerm();
        }

        // ---- SHORT_TERM: explicit writes only, no auto-summarization ----

        void addDialogue(String role, String content) {
            shortTerm.add(LlmClient.message(role, content));
        }

        void clearShortTerm() {
            shortTerm.clear();
        }

        List<Map<String, String>> shortTermView() {
            return new ArrayList<>(shortTerm);
        }

        // ---- WORKING: scoped to "the task at hand", reset on task switch ----

        void startTask(String name) {
            working.clear();
            currentTask = name;
        }

        void setWorkingFact(String key, String value) {
            working.put(key, value);
        }

        Map<String, String> workingView() {
            return new LinkedHashMap<>(working);
        }

        String currentTask() {
            return currentTask;
        }

        // ---- LONG_TERM: explicit commit, persisted immediately ----

        void commitLongTerm(String key, String value) {
            longTerm.put(key, value);
            saveLongTerm();
        }

        Map<String, String> longTermView() {
            return new LinkedHashMap<>(longTerm);
        }

        /** Builds the message list for exactly the requested layers, plus the new question. */
        List<Map<String, String>> assemble(Set<Layer> included, String newUserMessage) {
            StringBuilder sys = new StringBuilder(BASE_SYSTEM_PROMPT);
            if (included.contains(Layer.LONG_TERM) && !longTerm.isEmpty()) {
                sys.append("\n\nДолговременная память (профиль пользователя, решения, знания — ")
                   .append("накоплены за всё время работы с этим пользователем):\n");
                longTerm.forEach((k, v) -> sys.append("- ").append(k).append(": ").append(v).append("\n"));
            }
            if (included.contains(Layer.WORKING) && !working.isEmpty()) {
                sys.append("\n\nРабочая память (данные текущей задачи \"").append(currentTask)
                   .append("\", актуальны только для неё):\n");
                working.forEach((k, v) -> sys.append("- ").append(k).append(": ").append(v).append("\n"));
            }
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(LlmClient.message("system", sys.toString()));
            if (included.contains(Layer.SHORT_TERM)) {
                messages.addAll(shortTerm);
            }
            messages.add(LlmClient.message("user", newUserMessage));
            return messages;
        }

        private void loadLongTerm() {
            if (!Files.isRegularFile(longTermFile)) {
                return;
            }
            try {
                JsonNode root = LlmClient.parseJson(Files.readString(longTermFile, StandardCharsets.UTF_8));
                JsonNode profile = root.path("profile");
                if (profile.isObject()) {
                    for (var it = profile.fields(); it.hasNext(); ) {
                        var entry = it.next();
                        longTerm.put(entry.getKey(), entry.getValue().asText());
                    }
                }
            } catch (Exception e) {
                System.err.println("Внимание: не удалось прочитать долговременную память ("
                        + longTermFile + "): " + e.getMessage());
            }
        }

        private void saveLongTerm() {
            try {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("version", 1);
                doc.put("profile", longTerm);
                Files.writeString(longTermFile, LlmClient.toJson(doc), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("Внимание: не удалось сохранить долговременную память ("
                        + longTermFile + "): " + e.getMessage());
            }
        }
    }

    /** Thin wrapper around LlmClient that always goes through MemoryStore.assemble(). */
    static final class MemoryAgent {
        private final LlmClient.Config config;
        private final HttpClient http = LlmClient.newHttpClient();
        final MemoryStore memory;

        MemoryAgent(LlmClient.Config config, MemoryStore memory) {
            this.config = config;
            this.memory = memory;
        }

        /** Normal turn: all three layers included, the exchange is appended to SHORT_TERM. */
        String ask(String userMessage) throws Exception {
            List<Map<String, String>> messages = memory.assemble(EnumSet.allOf(Layer.class), userMessage);
            String answer = send(messages);
            memory.addDialogue("user", userMessage);
            memory.addDialogue("assistant", answer);
            return answer;
        }

        /**
         * A read-only "what-if" query for the ablation experiment: builds the request from
         * only the given layers and does NOT touch SHORT_TERM, so repeated calls with
         * different layer subsets stay comparable and don't pollute each other.
         */
        String askWithLayers(String question, Set<Layer> included) throws Exception {
            return send(memory.assemble(included, question));
        }

        private String send(List<Map<String, String>> messages) throws Exception {
            JsonNode response = LlmClient.send(http, config, messages, null, null, null);
            return LlmClient.content(response).trim();
        }
    }

    private record AblationRun(String label, Set<Layer> layers, String answer,
                               boolean mentionsModule, boolean mentionsDedupe, int length) {
    }

    private Task11() {
    }

    public static void main(String[] args) throws Exception {
        Files.deleteIfExists(LONG_TERM_FILE); // clean demo: show exactly what gets written this run
        LlmClient.Config cfg = LlmClient.fromEnv();
        System.out.println("День 11: модель памяти агента — short-term / working / long-term");
        System.out.println("Модель: " + cfg.model() + " @ " + cfg.baseUrl());

        // ---------------- Session 1 ----------------
        System.out.println();
        System.out.println("=== Сессия 1: знакомство + задача «код-ревью» ===");
        MemoryStore store1 = new MemoryStore(LONG_TERM_FILE);
        MemoryAgent agent1 = new MemoryAgent(cfg, store1);

        String intro = "Привет! Меня зовут Алексей, я бэкенд-разработчик на Java. "
                + "Обычно предпочитаю короткие и конкретные ответы, без лишних вступлений.";
        System.out.println();
        System.out.println("Вы: " + intro);
        String introAnswer = agent1.ask(intro);
        System.out.println("Агент: " + abbreviate(introAnswer, 160));
        // Explicit code decision: this belongs in LONG_TERM (profile), not in the task's WORKING memory.
        store1.commitLongTerm("имя", "Алексей");
        store1.commitLongTerm("роль", "бэкенд-разработчик на Java");
        store1.commitLongTerm("стиль_ответов", "короткие, конкретные, без вступлений");
        System.out.println("[записано в LONG_TERM: имя, роль, стиль_ответов]");

        store1.startTask("Чек-лист код-ревью");
        store1.setWorkingFact("язык", "Java");
        store1.setWorkingFact("предмет", "чек-лист для код-ревью pull request'а");
        String task1a = "Помоги составить чек-лист для код-ревью Java pull request'а.";
        System.out.println();
        System.out.println("Вы: " + task1a);
        System.out.println("[записано в WORKING: язык, предмет; задача = «Чек-лист код-ревью»]");
        String answerA = agent1.ask(task1a);
        System.out.println("Агент: " + abbreviate(answerA, 200));

        store1.setWorkingFact("акценты", "null-safety, unit-тесты, обработка исключений");
        String task1b = "Особый акцент сделай на null-safety, unit-тестах и обработке исключений.";
        System.out.println();
        System.out.println("Вы: " + task1b);
        System.out.println("[записано в WORKING: акценты]");
        String checklistAnswer = agent1.ask(task1b);
        System.out.println("Агент: " + abbreviate(checklistAnswer, 240));

        Map<String, String> session1Long = store1.longTermView();
        Map<String, String> session1Working = store1.workingView();
        int session1Turns = store1.shortTermView().size() / 2;

        // ---------------- Session 2 ----------------
        System.out.println();
        System.out.println("=== Сессия 2: новая задача, новый диалог (эмулируем перезапуск) ===");
        // Fresh instance — proves persistence: LONG_TERM is reloaded from disk, WORKING and
        // SHORT_TERM start empty because a new session/task has nothing to do with the old one.
        MemoryStore store2 = new MemoryStore(LONG_TERM_FILE);
        MemoryAgent agent2 = new MemoryAgent(cfg, store2);

        store2.startTask("Сообщение коммита");
        store2.setWorkingFact("модуль", "оплата (payment)");
        store2.setWorkingFact("тип_изменения", "рефакторинг без изменения поведения");
        String task2a = "Помоги сформулировать сообщение коммита для рефакторинга модуля оплаты.";
        System.out.println();
        System.out.println("Вы: " + task2a);
        System.out.println("[LONG_TERM восстановлена из файла: " + session1Long.keySet()
                + "; WORKING и SHORT_TERM — пустые, задача новая]");
        agent2.ask(task2a);

        // This detail lives ONLY in this session's dialogue (SHORT_TERM) — it is never written
        // to WORKING, so the ablation experiment below can show SHORT_TERM's own contribution.
        String dedupeNote = "Кстати, в рамках этого рефакторинга я также убрал дублирование кода "
                + "в трёх разных местах — учти это для итогового сообщения коммита.";
        System.out.println();
        System.out.println("Вы: " + dedupeNote);
        String dedupeAck = agent2.ask(dedupeNote);
        System.out.println("Агент: " + abbreviate(dedupeAck, 160));

        String probe = "Напомни, пожалуйста, что мы обсуждали в прошлый раз про код-ревью — "
                + "какие акценты я просил сделать в чек-листе?";
        System.out.println();
        System.out.println("Вы: " + probe);
        String probeAnswer = agent2.ask(probe);
        System.out.println("Агент: " + abbreviate(probeAnswer, 200));
        boolean isolationHeld = !containsAny(probeAnswer, "null-safety", "unit-тест", "обработк");

        // ---------------- Ablation: same question, different layers included ----------------
        System.out.println();
        System.out.println("=== Эксперимент: одна и та же реплика, разные слои памяти ===");
        String finalQuestion = "Составь итоговое сообщение коммита с учётом всего, что мы обсудили, "
                + "в моём обычном стиле.";
        System.out.println("Вопрос: " + finalQuestion);

        List<AblationRun> runs = new ArrayList<>();
        runs.add(runAblation(agent2, "Все три слоя", EnumSet.allOf(Layer.class), finalQuestion));
        runs.add(runAblation(agent2, "Без LONG_TERM (нет профиля)",
                EnumSet.of(Layer.WORKING, Layer.SHORT_TERM), finalQuestion));
        runs.add(runAblation(agent2, "Без WORKING (нет данных задачи)",
                EnumSet.of(Layer.LONG_TERM, Layer.SHORT_TERM), finalQuestion));
        runs.add(runAblation(agent2, "Без SHORT_TERM (нет текущего диалога)",
                EnumSet.of(Layer.LONG_TERM, Layer.WORKING), finalQuestion));

        printAblationTable(runs);

        writeReport(cfg, session1Long, session1Working, session1Turns, checklistAnswer,
                store2, task2a, dedupeNote, probeAnswer, isolationHeld, finalQuestion, runs);
        System.out.println();
        System.out.println("Отчёт записан: " + REPORT_FILE);
    }

    private static AblationRun runAblation(MemoryAgent agent, String label, Set<Layer> layers,
                                           String question) throws Exception {
        String answer = agent.askWithLayers(question, layers);
        boolean mentionsModule = containsAny(answer, "оплат", "payment");
        boolean mentionsDedupe = containsAny(answer, "дублир", "дубликат", "повтор");
        System.out.println();
        System.out.println("--- " + label + " ---");
        System.out.println(abbreviate(answer, 220));
        System.out.println("[модуль=" + mark(mentionsModule) + " дедупликация=" + mark(mentionsDedupe)
                + " длина=" + answer.length() + " симв.]");
        return new AblationRun(label, layers, answer, mentionsModule, mentionsDedupe, answer.length());
    }

    private static void printAblationTable(List<AblationRun> runs) {
        System.out.println();
        System.out.println("=== Сводка ===");
        String head = "-".repeat(78);
        System.out.println(head);
        System.out.printf("%-32s | %-8s | %-14s | %s%n", "Слои", "Модуль", "Дедупликация", "Длина, симв.");
        System.out.println(head);
        for (AblationRun run : runs) {
            System.out.printf("%-32s | %-8s | %-14s | %d%n", run.label(),
                    mark(run.mentionsModule()), mark(run.mentionsDedupe()), run.length());
        }
    }

    private static void writeReport(LlmClient.Config cfg, Map<String, String> session1Long,
                                    Map<String, String> session1Working, int session1Turns,
                                    String checklistAnswer, MemoryStore store2, String task2a,
                                    String dedupeNote, String probeAnswer, boolean isolationHeld,
                                    String finalQuestion, List<AblationRun> runs) throws Exception {
        AblationRun all = runs.get(0);
        AblationRun noLongTerm = runs.get(1);
        AblationRun noWorking = runs.get(2);
        AblationRun noShortTerm = runs.get(3);

        StringBuilder md = new StringBuilder();
        md.append("# День 11 — Модель памяти агента (short-term / working / long-term)\n\n");
        md.append("Сгенерировано: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME))
          .append(", модель `").append(cfg.model()).append("` @ ").append(cfg.baseUrl()).append("\n\n");

        md.append("## Модель памяти\n\n");
        md.append("Три отдельных хранилища вместо одной истории диалога, каждое со своим временем жизни ")
          .append("и своим правилом, кто и когда в него пишет:\n\n");
        md.append("| Слой | Что хранит | Кто пишет | Живёт |\n");
        md.append("|---|---|---|---|\n");
        md.append("| **SHORT_TERM** | сырые реплики текущего диалога | код (`addDialogue`) после каждого ответа | до конца сессии, нигде не сохраняется |\n");
        md.append("| **WORKING** | данные текущей задачи (ключ-значение) | код (`setWorkingFact`), очищается `startTask()` | пока активна конкретная задача |\n");
        md.append("| **LONG_TERM** | профиль, решения, знания | код (`commitLongTerm`), явный вызов | всегда, персистентно (`task11-long-term-memory.json`) |\n\n");
        md.append("Важно: ни один слой не заполняется моделью автоматически — какие данные и в какой слой ")
          .append("попадают, решает вызывающий код explicit-вызовом нужного метода, и при сборке запроса код ")
          .append("явно указывает, какие слои включить (`MemoryStore.assemble(...)`).\n\n");

        md.append("## Сессия 1 — знакомство + задача «код-ревью»\n\n");
        md.append("Реплик в диалоге: ").append(session1Turns).append(".\n\n");
        md.append("**Что попало в LONG_TERM** (сохранено явным вызовом `commitLongTerm`, переживёт сессию):\n\n");
        session1Long.forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append("\n"));
        md.append("\n**Что попало в WORKING** (актуально только для задачи «Чек-лист код-ревью»):\n\n");
        session1Working.forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append("\n"));
        md.append("\n**Финальный чек-лист** (ответ построен из LONG_TERM + WORKING + SHORT_TERM):\n\n")
          .append(quote(checklistAnswer)).append("\n\n");

        md.append("## Сессия 2 — новая задача, новый диалог (эмуляция перезапуска)\n\n");
        md.append("Новый `MemoryStore` тем же файлом LONG_TERM: профиль восстановлен из ")
          .append("`task11-long-term-memory.json` (").append(session2LongLine(store2)).append("), ")
          .append("а WORKING и SHORT_TERM стартуют пустыми — предыдущая задача к новой не относится.\n\n");
        md.append("Новая задача «Сообщение коммита», WORKING:\n\n");
        store2.workingView().forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append("\n"));
        md.append("\n**Проверка изоляции.** Вопрос: «").append(probe2Text())
          .append("»\n\nОтвет:\n\n").append(quote(probeAnswer)).append("\n\n");
        md.append(isolationHeld
                ? "Изоляция подтвердилась: в ответе нет акцентов из чек-листа сессии 1 "
                  + "(null-safety / unit-тесты / обработка исключений) — WORKING и SHORT_TERM сессии 1 "
                  + "не пережили смену задачи и сессии, как и задумано.\n\n"
                : "Внимание: ответ всё же упомянул детали чек-листа сессии 1 — модель могла "
                  + "додумать типичные пункты код-ревью сама, это не значит, что данные реально сохранились "
                  + "в WORKING/SHORT_TERM (они были явно очищены).\n\n");

        md.append("## Эксперимент: один вопрос, разные слои памяти\n\n");
        md.append("Реплика «*Кстати...*» (детали дедупликации кода) в этой сессии была сказана только в диалоге ")
          .append("и никогда не записывалась в WORKING — это специально, чтобы у SHORT_TERM был свой, ")
          .append("не дублирующийся с WORKING вклад в ответ.\n\n");
        md.append("Вопрос: «").append(finalQuestion).append("»\n\n");
        md.append("| Включённые слои | Упомянут модуль «оплата» | Упомянута дедупликация | Длина ответа, симв. |\n");
        md.append("|---|---|---|---|\n");
        for (AblationRun run : runs) {
            md.append("| ").append(run.label()).append(" | ").append(mark(run.mentionsModule()))
              .append(" | ").append(mark(run.mentionsDedupe())).append(" | ").append(run.length()).append(" |\n");
        }
        md.append("\n");
        md.append("### Все три слоя\n\n").append(quote(all.answer())).append("\n\n");
        md.append("### Без LONG_TERM (нет профиля / стиля ответов)\n\n").append(quote(noLongTerm.answer())).append("\n\n");
        md.append("### Без WORKING (нет данных задачи)\n\n").append(quote(noWorking.answer())).append("\n\n");
        md.append("### Без SHORT_TERM (нет текущего диалога)\n\n").append(quote(noShortTerm.answer())).append("\n\n");

        md.append("## Выводы\n\n");
        md.append("- **LONG_TERM** отвечает за то, *как* агент говорит и что он помнит о пользователе поперёк ")
          .append("задач и сессий: профиль (имя, роль, предпочтение по стилю) пережил перезапуск сессии, ")
          .append("а без него ответ ").append(noLongTerm.length() > all.length() ? "стал заметно длиннее"
                  : "не стал короче").append(" (").append(noLongTerm.length()).append(" против ")
          .append(all.length()).append(" символов) — модель перестаёт получать инструкцию «отвечай кратко».\n");
        md.append("- **WORKING** отвечает за факты конкретной задачи: без него модуль «оплата» ")
          .append(noWorking.mentionsModule() ? "всё равно попал в ответ (мог остаться в SHORT_TERM)"
                  : "пропал из ответа").append(" — упоминание модуля = ").append(mark(noWorking.mentionsModule()))
          .append(", тогда как со всеми слоями = ").append(mark(all.mentionsModule())).append(".\n");
        md.append("- **SHORT_TERM** отвечает за то, что было сказано только что и нигде больше не записано: ")
          .append("деталь про дедупликацию кода упомянута ").append(mark(all.mentionsDedupe()))
          .append(" при включённом SHORT_TERM и ").append(mark(noShortTerm.mentionsDedupe()))
          .append(" без него — это единственный слой, где эта деталь вообще хранится.\n");
        md.append("- Разделение на три слоя даёт контроль: код явно решает, что писать в WORKING (текущая задача), ")
          .append("что — в LONG_TERM (навсегда), и какие слои включать в конкретный запрос, вместо того чтобы ")
          .append("слать модели всё подряд или полагаться на неё в выборе, что важно запомнить.\n");

        Files.writeString(REPORT_FILE, md.toString());
    }

    private static String session2LongLine(MemoryStore store2) {
        return String.join(", ", store2.longTermView().keySet());
    }

    private static String probe2Text() {
        return "Напомни, пожалуйста, что мы обсуждали в прошлый раз про код-ревью — "
                + "какие акценты я просил сделать в чек-листе?";
    }

    private static String quote(String answer) {
        return answer == null ? "(нет ответа)" : "> " + answer.replace("\n", "\n> ");
    }

    private static String mark(boolean ok) {
        return ok ? "✓" : "✗";
    }

    private static boolean containsAny(String text, String... markers) {
        String normalized = text.toLowerCase();
        for (String marker : markers) {
            if (normalized.contains(marker.toLowerCase())) {
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
