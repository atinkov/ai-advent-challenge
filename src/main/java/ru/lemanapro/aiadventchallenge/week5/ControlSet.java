package ru.lemanapro.aiadventchallenge.week5;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Week 5 (RAG), shared: the control set — 10 questions about the knowledge base (this project) used by
 * days 21-24, plus 3 questions the base cannot answer (days 23-24: filtering and the "не знаю" rule).
 *
 * For every question the set fixes
 *   expectation — what a correct answer must contain (human-readable, goes into the reports);
 *   facts       — the same expectation as machine checks: each fact is a regex (case-insensitive) that must
 *                 be found in the answer; score = found / total. It is a cheap proxy of correctness, not a judge;
 *   sources     — files that contain the answer (any of them is enough);
 *   evidence    — a regex a chunk must contain to count as RELEVANT for this question (retrieval metrics:
 *                 a chunk of AGENTS.md about a different day is not a hit just because the file matches).
 *
 * Do not index this file: it holds the questions together with the answers (RagIndex skips week5/).
 */
public final class ControlSet {

    public record Question(String id, String question, String expectation, List<String> facts, List<String> sources, String evidence) {

        /** Share of expected facts found in the answer, 0..1. */
        public double score(String answer) {
            return facts.isEmpty() ? 0 : (double) (facts.size() - missing(answer).size()) / facts.size();
        }

        public List<String> missing(String answer) {
            List<String> out = new ArrayList<>();
            for (String f : facts) {
                if (!Pattern.compile(f, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(answer == null ? "" : answer).find()) {
                    out.add(f);
                }
            }
            return out;
        }

        public boolean sourceMatches(String source) {
            return sources.stream().anyMatch(s -> source.equals(s) || source.endsWith("/" + s));
        }

        /** Relevant chunk = from an expected source AND actually carrying the evidence. */
        public boolean relevant(RagIndex.Chunk chunk) {
            return sourceMatches(chunk.source())
                    && Pattern.compile(evidence, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(chunk.text()).find();
        }
    }

    private static final String NUM6 = "(?<![\\d.,])6(?![\\d.,]\\d)";
    private static final String NUM10 = "(?<![\\d.,])10(?![\\d.,]\\d)";

    public static final List<Question> QUESTIONS = List.of(
            new Question("Q1",
                    "Сколько последних сообщений диалога LlmAgent отправляет модели без сжатия и сколько сообщений должно накопиться, "
                            + "чтобы он свернул их в пересказ? Какими переменными окружения это настраивается?",
                    "Окно — 6 последних сообщений (AGENT_KEEP_RECENT); пачка — 10 сообщений (AGENT_SUMMARY_BATCH).",
                    List.of(NUM6, NUM10, "AGENT_KEEP_RECENT", "AGENT_SUMMARY_BATCH"),
                    List.of("AGENTS.md", "LlmAgent.java", "task9-compression-report.md"),
                    "AGENT_KEEP_RECENT|AGENT_SUMMARY_BATCH"),
            new Question("Q2",
                    "Почему в pom.xml проекта версия jackson-annotations зафиксирована на 2.21?",
                    "MCP Java SDK тянет Jackson 3, которому нужны jackson-annotations ≥ 2.20; с 2.19 (из jackson-databind) "
                            + "падает NoClassDefFoundError: JsonSerializeAs.",
                    List.of("MCP", "Jackson\\s*3", "JsonSerializeAs|2\\.20|NoClassDefFoundError"),
                    List.of("AGENTS.md"),
                    "jackson-annotations|JsonSerializeAs"),
            new Question("Q3",
                    "Какие инструменты публикует GitMcpServer и можно ли через них что-то изменить в репозитории?",
                    "Три инструмента: git_log, git_commit_details, git_branches; сервер только читает (read-only).",
                    List.of("git_log", "git_commit_details", "git_branches",
                            "read-?only|только (на |для )?чтени|нельзя|не (может|могут|позволя|изменя|предусмотр)|нет,"),
                    List.of("AGENTS.md", "Task17.java", "task17-mcp-agent-report.md"),
                    "git_commit_details"),
            new Question("Q4",
                    "По какому правилу McpRegistry формирует имена инструментов в общем каталоге и почему выбран именно такой разделитель?",
                    "Имя = <сервер>__<инструмент> (двойное подчёркивание), потому что имена функций OpenAI допускают только [a-zA-Z0-9_-].",
                    List.of("__", "OpenAI|a-zA-Z0-9"),
                    List.of("AGENTS.md", "McpRegistry.java", "task20-orchestration-report.md"),
                    "<server>__<tool>|<сервер>__<инструмент>|SEP = \"__\"|a-zA-Z0-9_-"),
            new Question("Q5",
                    "Через какие состояния проходит задача в жизненном цикле дня 15 и можно ли из IMPLEMENTATION сразу перейти в DONE?",
                    "DRAFT → PLAN_APPROVED → IMPLEMENTATION → VALIDATED → DONE; прыжок IMPLEMENTATION → DONE запрещён (нужна валидация).",
                    List.of("DRAFT", "PLAN_APPROVED", "VALIDATED",
                            "нельзя|невозможн|запрещ|отклон|не (допуска|разреш|получится|может)|нет[,.]"),
                    List.of("AGENTS.md", "Task15.java", "task15-lifecycle-report.md"),
                    "PLAN_APPROVED"),
            new Question("Q6",
                    "Как планировщик дня 18 после перезапуска восстанавливает задания и почему задание не выполняется дважды?",
                    "Задания и запуски хранятся в task18-scheduler.json; при старте активные задания перезапускаются с задержкой "
                            + "initialDelay = max(0, interval − время с lastRunAt).",
                    List.of("task18-scheduler|json", "lastRunAt|последн\\S+ (запуск|выполнени)", "initialDelay|max\\(0|задержк|оставш"),
                    List.of("AGENTS.md", "Task18.java"),
                    "lastRunAt|initialDelay"),
            new Question("Q7",
                    "Какие три слоя памяти выделены в модели памяти дня 11, какой из них сохраняется между сессиями и в какой файл?",
                    "SHORT_TERM, WORKING, LONG_TERM; между сессиями живёт только LONG_TERM — в task11-long-term-memory.json.",
                    List.of("SHORT_TERM|краткосрочн", "WORKING|рабоч", "LONG_TERM|долговременн|долгосрочн", "task11-long-term-memory"),
                    List.of("AGENTS.md", "Task11.java", "task11-memory-report.md"),
                    "LONG_TERM"),
            new Question("Q8",
                    "Что показал эксперимент дня 9: на сколько процентов сжатие истории сократило prompt-токены за сессию "
                            + "и сколько фактов из пяти модель воспроизвела со сжатием?",
                    "Prompt-токены за сессию −28 %; факты воспроизведены 5/5 (качество не потеряно).",
                    List.of("28\\s*%|28 процент", "5\\s*/\\s*5|5 из 5|все (5|пять)"),
                    List.of("task9-compression-report.md"),
                    "28\\s*%"),
            new Question("Q9",
                    "Сколько шагов вызова инструментов максимум делает McpToolAgent на один вопрос и как он работает, "
                            + "если LLM-сервер не поддерживает нативный function calling?",
                    "До 6 шагов (MAX_TOOL_STEPS); в режиме prompt инструменты описываются в системном промпте, модель отвечает "
                            + "блоком <tool_call>{...}</tool_call>; в auto переключение происходит при HTTP 4xx.",
                    List.of(NUM6, "tool_call", "prompt|системн\\S+ промпт|текстов\\S+ протокол"),
                    List.of("AGENTS.md", "McpToolAgent.java"),
                    "MAX_TOOL_STEPS|<tool_call>"),
            new Question("Q10",
                    "Как в пайплайне дня 19 проверяется, что данные между шагами search_docs → summarize → save_to_file переданы без искажений?",
                    "Шаги передают идентификаторы (result_id → source_id, summary_id), а целостность сверяется по sha256: "
                            + "input_sha256 = text_sha256, save.input_sha256 = summarize.output_sha256, файл = file_sha256.",
                    List.of("sha-?256|хеш|хэш", "result_id|source_id", "summary_id"),
                    List.of("AGENTS.md", "Task19.java", "task19-pipeline-report.md"),
                    "sha256"));

    /** Questions the knowledge base cannot answer: the right behaviour is an empty context and "не знаю". */
    public static final List<Question> OFF_TOPIC = List.of(
            new Question("N1", "Какая столица Австралии и сколько в ней жителей?",
                    "В базе этого нет (общие знания) → «не знаю» + просьба уточнить.", List.of(), List.of(), "$^"),
            new Question("N2", "Как в этом проекте настроен деплой в Kubernetes и какой Helm-чарт используется?",
                    "Похоже на вопрос по проекту, но в базе такого нет → «не знаю» + просьба уточнить.", List.of(), List.of(), "$^"),
            new Question("N3", "Какой размер скидки даёт промокод на первую покупку в интернет-магазине?",
                    "Посторонняя тема → «не знаю» + просьба уточнить.", List.of(), List.of(), "$^"));

    private ControlSet() {
    }
}
