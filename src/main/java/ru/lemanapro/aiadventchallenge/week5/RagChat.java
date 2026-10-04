package ru.lemanapro.aiadventchallenge.week5;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Week 5 (day 25): a chat over the knowledge base with dialogue history and an explicit task memory.
 *
 * Every user message goes through the same steps (send):
 *   1. PLAN (1 LLM call, JSON) — two jobs in one request:
 *        - a self-contained search query: pronouns and references to earlier turns are resolved from the
 *          history, the user's own terms are replaced by what they stand for;
 *        - the delta of the task memory found in THIS message: goal, clarifications, constraints, terms;
 *      and a flag needs_kb=false for messages about the dialogue itself ("remind me the goal").
 *   2. MEMORY UPDATE (code) — TaskState is merged by code, not rewritten by the model:
 *        lists only grow (deduplicated, capped); a goal is recorded only from a message that is not a
 *        question, and an existing goal is replaced ONLY if the message itself states a goal
 *        (GOAL_STATEMENT) and is not a question about it (GOAL_QUESTION) — the model drifting to
 *        "the goal is the last question" or rewording the goal on "remind me" is rejected.
 *   3. RAG — the index is searched on every message (RagPipeline.retrieve: search + second stage);
 *   4. ANSWER —
 *        KNOWLEDGE: context found -> verified answer with sources and quotes (RagPipeline.answerCited);
 *                   the task memory is part of the system prompt, the recent history is passed as messages;
 *        UNKNOWN:   needs the base but the context is empty / has no answer -> "не знаю" + clarification;
 *        MEMORY:    a question about the dialogue -> answered from the task memory and history; the
 *                   sources line says so explicitly.
 *      Sources are printed for every turn, whatever the kind (Turn.render).
 *   5. PERSIST — task state + full history go to a JSON file after every turn; a new RagChat over the
 *      same file continues the conversation.
 *
 * Only the last HISTORY_WINDOW messages are sent to the model; what must survive longer lives in TaskState.
 */
public final class RagChat {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HISTORY_WINDOW = 6;
    private static final int MAX_ITEMS = 12;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS; // \b is ASCII-only without the last flag
    /** The message STATES a goal (sets or changes it) — as opposed to merely mentioning the word. */
    private static final Pattern GOAL_STATEMENT = Pattern.compile(
            "цель\\s*[:—–-]|\\b(моя|наша|новая|главная|основная)\\s+цель\\b|\\b(моей|нашей)\\s+целью\\b|\\bцел(ь|ью|и)\\s+(теперь|стал[аои]?)\\b"
                    + "|новая задача|\\b(сменим|меняем|поменяем|изменим)\\s+(цель|задачу)\\b|\\bтеперь\\s+(я\\s+)?(хочу|нужно|надо)\\b|\\bпередумал", FLAGS);
    /** The message ASKS about the goal ("напомни, какая у нас цель") — it must never change it. */
    private static final Pattern GOAL_QUESTION = Pattern.compile("\\b(напомни\\S*|какая|какой|какую|какова|к какой|что за)\\b[^.?!]*\\bцел", FLAGS);

    private static final String PLAN_PROMPT = """
            Ты — модуль памяти и планирования чат-ассистента по учебному Java-проекту AIAdventChallenge. Тебе дают память задачи, последние реплики диалога и новое сообщение пользователя.
            Пользователю не отвечай. Верни СТРОГО один JSON-объект:
            {"search_query": "<самодостаточный поисковый запрос к базе знаний проекта>",
             "needs_kb": true|false,
             "goal": "<цель диалога, если пользователь сформулировал или изменил её в ЭТОМ сообщении; иначе пустая строка>",
             "clarified": ["<новые факты о ситуации пользователя и уточнения из ЭТОГО сообщения>"],
             "constraints": ["<новые требования к ответам из ЭТОГО сообщения: формат, длина, язык, запреты>"],
             "terms": {"<термин пользователя>": "<что он означает>"}}
            Правила:
            - search_query: раскрой местоимения и отсылки к прошлым репликам («он», «этот сервер», «там», «тот эксперимент») по истории диалога; термины пользователя из памяти замени их значением; добавь идентификаторы (классы, переменные окружения, номер дня), если они известны из диалога. Это запрос для поиска, а не ответ.
            - needs_kb=false — только если сообщение целиком о самом диалоге (напомнить цель, ограничения, что уже выяснили) или не содержит вопроса (приветствие, постановка цели, «спасибо»). Иначе true.
            - В память записывай только то, что сказал пользователь, своими короткими формулировками; не выдумывай и не повторяй уже записанное. Вопрос пользователя — это не цель и не уточнение. Если нового нет — пустые значения.""";

    /** The task memory: what must not be lost however long the dialogue gets. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class TaskState {
        public String goal = "";
        public List<String> clarified = new ArrayList<>();
        public List<String> constraints = new ArrayList<>();
        public Map<String, String> terms = new LinkedHashMap<>();

        public TaskState copy() {
            TaskState c = new TaskState();
            c.goal = goal;
            c.clarified = new ArrayList<>(clarified);
            c.constraints = new ArrayList<>(constraints);
            c.terms = new LinkedHashMap<>(terms);
            return c;
        }

        @JsonIgnore
        public boolean isEmpty() {
            return goal.isBlank() && clarified.isEmpty() && constraints.isEmpty() && terms.isEmpty();
        }

        /** The block that goes into the system prompt of every answer. */
        public String block() {
            StringBuilder sb = new StringBuilder("Память задачи (учитывай её в каждом ответе):\n");
            sb.append("- Цель диалога: ").append(goal.isBlank() ? "пока не задана" : goal).append("\n");
            sb.append("- Что пользователь уже уточнил: ").append(clarified.isEmpty() ? "—" : String.join("; ", clarified)).append("\n");
            sb.append("- Ограничения и требования к ответам: ").append(constraints.isEmpty() ? "—" : String.join("; ", constraints)).append("\n");
            sb.append("- Термины: ");
            if (terms.isEmpty()) {
                sb.append("—");
            }
            terms.forEach((k, v) -> sb.append("«").append(k).append("» = ").append(v).append("; "));
            sb.append("\nОтвечай так, чтобы продвигать пользователя к цели; соблюдай ограничения (они относятся к полю answer); "
                    + "термины пользователя понимай в зафиксированном значении.");
            return sb.toString();
        }

        public String oneLine() {
            return "цель: " + (goal.isBlank() ? "—" : Chunker.clip(goal, 70)) + " | уточнений: " + clarified.size()
                    + " | ограничений: " + constraints.size() + " | терминов: " + terms.size();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Saved {
        public TaskState state = new TaskState();
        public List<Map<String, String>> history = new ArrayList<>();
    }

    public enum Kind {
        KNOWLEDGE("по базе знаний"), MEMORY("из памяти задачи"), UNKNOWN("не знаю"), ERROR("ошибка");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** One exchange. state — a snapshot of the task memory AFTER this turn. */
    public record Turn(int no, String user, String searchQuery, Kind kind, RagPipeline.Retrieval retrieval, RagPipeline.Cited reply,
                       TaskState state, List<String> stateChanges, List<String> notes, long millis) {

        /** The assistant's message as the user sees it: the answer and — always — the sources. */
        public String render() {
            if (kind != Kind.MEMORY) {
                return reply.render();
            }
            return reply.answer().strip() + "\n\nИсточники: память задачи и история диалога (поиск по базе выполнен — найдено чанков: "
                    + (retrieval == null ? 0 : retrieval.after().size()) + ", для этого ответа они не использовались)";
        }

        public boolean showsSources() {
            return render().contains("Источники:")
                    && (kind != Kind.KNOWLEDGE || (reply.hasSources() && reply.hasQuotes()));
        }
    }

    private final RagPipeline pipeline;
    private final RagPipeline.Settings settings;
    private final Path file;
    private TaskState state = new TaskState();
    private final List<Map<String, String>> history = new ArrayList<>();
    private int turns;

    public RagChat(RagPipeline pipeline, RagPipeline.Settings settings, Path file) {
        this.pipeline = pipeline;
        // the planner already produces a history-aware query; a second rewrite would be a wasted LLM call
        this.settings = settings.with(settings.rerank(), false);
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                Saved saved = MAPPER.readValue(file.toFile(), Saved.class);
                state = saved.state == null ? new TaskState() : saved.state;
                history.addAll(saved.history);
                turns = history.size() / 2;
            } catch (Exception e) {
                System.out.println("Не удалось прочитать " + file + " (" + e.getMessage() + ") — начинаю новый диалог.");
            }
        }
    }

    public TaskState state() {
        return state;
    }

    public int turnCount() {
        return turns;
    }

    public RagPipeline.Settings settings() {
        return settings;
    }

    public void reset() throws Exception {
        state = new TaskState();
        history.clear();
        turns = 0;
        Files.deleteIfExists(file);
    }

    public Turn send(String message) {
        long t0 = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();
        Plan plan = plan(message, notes);
        List<String> changes = remember(plan, message, notes);
        String query = plan.searchQuery().isBlank() ? message : plan.searchQuery();

        RagPipeline.Retrieval retrieval = null;
        RagPipeline.Cited reply;
        Kind kind;
        try {
            retrieval = pipeline.retrieve(query, List.of(message), settings); // RAG on every message
            notes.addAll(retrieval.notes());
            if (!plan.needsKb()) {
                reply = fromMemory(message);
                kind = Kind.MEMORY;
            } else {
                reply = pipeline.answerCited(message, retrieval, state.block(), recent());
                kind = reply.known() ? Kind.KNOWLEDGE : Kind.UNKNOWN;
            }
        } catch (Exception e) {
            reply = new RagPipeline.Cited(false, "(ошибка: " + RagPipeline.brief(e) + ")", List.of(), List.of(), "", 0, false, List.of());
            kind = Kind.ERROR;
        }
        if (kind != Kind.ERROR) { // a failed turn leaves the dialogue as it was (the memory update stays: the user did say it)
            history.add(LlmClient.message("user", message));
            history.add(LlmClient.message("assistant", reply.answer() + (reply.known() || reply.clarify().isBlank() ? "" : " " + reply.clarify())));
        }
        int no = turns + 1;
        turns = history.size() / 2; // a failed turn is not part of the dialogue — the same count a reload would restore
        persist(notes);
        return new Turn(no, message, query, kind, retrieval, reply, state.copy(), changes, notes, System.currentTimeMillis() - t0);
    }

    // ---------------------------------------------------------------- step 1: plan

    private record Plan(String searchQuery, boolean needsKb, String goal, List<String> clarified, List<String> constraints, Map<String, String> terms) {
    }

    private Plan plan(String message, List<String> notes) {
        StringBuilder user = new StringBuilder("Память задачи:\n");
        try {
            user.append(MAPPER.writeValueAsString(state));
        } catch (Exception e) {
            user.append("{}");
        }
        user.append("\n\nПоследние реплики диалога:\n");
        List<Map<String, String>> recent = recent();
        if (recent.isEmpty()) {
            user.append("(диалог только начался)\n");
        }
        for (Map<String, String> m : recent) {
            user.append(m.get("role").equals("user") ? "Пользователь: " : "Ассистент: ").append(Chunker.clip(m.get("content").replaceAll("\\s+", " "), 500)).append("\n");
        }
        user.append("\nНовое сообщение пользователя: ").append(message);
        try {
            JsonNode json = pipeline.askJson(PLAN_PROMPT, user.toString());
            if (json == null || !json.has("search_query")) {
                throw new IllegalStateException("планировщик не вернул JSON нужного формата");
            }
            Map<String, String> terms = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : json.path("terms").properties()) {
                if (e.getValue().isTextual() && !e.getValue().asText().isBlank()) {
                    terms.put(e.getKey().strip(), e.getValue().asText().strip());
                }
            }
            return new Plan(json.path("search_query").asText("").strip(), json.path("needs_kb").asBoolean(true), json.path("goal").asText("").strip(),
                    strings(json.path("clarified")), strings(json.path("constraints")), terms);
        } catch (Exception e) {
            notes.add("планировщик не сработал (" + RagPipeline.brief(e) + ") — поиск по исходному сообщению, память не обновлена");
            return new Plan(message, true, "", List.of(), List.of(), Map.of());
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            if (n.isTextual() && !n.asText().isBlank()) {
                out.add(n.asText().strip());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- step 2: memory update (code decides)

    private List<String> remember(Plan plan, String message, List<String> notes) {
        List<String> changes = new ArrayList<>();
        if (!plan.goal().isBlank() && !same(plan.goal(), state.goal)) {
            boolean asks = GOAL_QUESTION.matcher(message).find();
            boolean states = !asks && GOAL_STATEMENT.matcher(message).find();
            if (state.goal.isBlank() && (states || (!asks && !message.strip().endsWith("?")))) {
                state.goal = plan.goal(); // the first goal: any non-question message may set it
                changes.add("цель: " + plan.goal());
            } else if (!state.goal.isBlank() && states) {
                state.goal = plan.goal();
                changes.add("цель изменена: " + plan.goal());
            } else {
                notes.add("планировщик предложил " + (state.goal.isBlank() ? "записать цель" : "сменить цель на") + " «" + Chunker.clip(plan.goal(), 60)
                        + "» — отклонено: пользователь в этом сообщении цель не формулировал");
            }
        }
        for (String c : plan.clarified()) {
            if (add(state.clarified, c)) {
                changes.add("уточнение: " + c);
            }
        }
        for (String c : plan.constraints()) {
            if (add(state.constraints, c)) {
                changes.add("ограничение: " + c);
            }
        }
        plan.terms().forEach((term, meaning) -> {
            if (!meaning.equals(state.terms.get(term)) && (state.terms.size() < MAX_ITEMS || state.terms.containsKey(term))) {
                state.terms.put(term, meaning);
                changes.add("термин: «" + term + "» = " + meaning);
            }
        });
        return changes;
    }

    private static boolean add(List<String> list, String item) {
        for (String existing : list) {
            if (same(existing, item) || key(existing).contains(key(item))) {
                return false;
            }
        }
        if (list.size() >= MAX_ITEMS) {
            list.removeFirst();
        }
        list.add(item);
        return true;
    }

    private static boolean same(String a, String b) {
        return key(a).equals(key(b));
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    // ---------------------------------------------------------------- step 4: answer from memory

    private RagPipeline.Cited fromMemory(String message) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(LlmClient.message("system", "Ты ассистент по учебному Java-проекту AIAdventChallenge. Это сообщение пользователя не требует сведений из базы знаний: "
                + "ответь по памяти задачи и истории диалога. Фактов о проекте не выдумывай. Кратко, по-русски.\n" + state.block()));
        messages.addAll(recent());
        messages.add(LlmClient.message("user", message));
        return new RagPipeline.Cited(true, pipeline.chat(messages), List.of(), List.of(), "", 0, false, List.of());
    }

    private List<Map<String, String>> recent() {
        return List.copyOf(history.subList(Math.max(0, history.size() - HISTORY_WINDOW), history.size()));
    }

    private void persist(List<String> notes) {
        try {
            Saved saved = new Saved();
            saved.state = state;
            saved.history = history;
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), saved);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            notes.add("не удалось сохранить диалог в " + file + ": " + e.getMessage());
        }
    }
}
