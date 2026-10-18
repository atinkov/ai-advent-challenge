# Схемы данных

Какие файлы данных пишет проект, кто их пишет и читает. Почти все они в `.gitignore` (это состояние, а не код)
и пересоздаются при запуске. Формальные JSON Schema — в [schemas/](schemas/).

## Сводка файлов

| Файл | Кто пишет / читает | Формат | В git |
|---|---|---|---|
| `agent-context.json` | `LlmAgent` (дни 6–10) | [schemas/agent-context.schema.json](schemas/agent-context.schema.json) — v4: ветки диалога, чекпоинт, статистика токенов | нет |
| `task9-{off,on}.json`, `task10-{sliding,facts,full,branch}.json` | Task9, Task10 | тот же формат, что `agent-context.json` | нет |
| `task11-long-term-memory.json` | Task11 | долговременная память (LONG_TERM) — единственный файл, который переживает запуски Task11 | нет |
| `task12-profile-{alexey,maria,igor}.json` | Task12 | профиль пользователя: `displayName`, `role`, `style`, `format`, `constraints`, `knownFacts` | нет |
| `task13-state.json` | Task13 | состояние FSM задачи: `stepPointer`, `attempt`, `data`, `log` | нет |
| `task14-invariants.json` | Task14 | список инвариантов `{id, category, statement}`, `category` ∈ ARCHITECTURE / TECH_DECISION / STACK_CONSTRAINT / BUSINESS_RULE | нет |
| `task15-lifecycle.json` | Task15 | жизненный цикл: `state` (DRAFT → PLAN_APPROVED → IMPLEMENTATION → VALIDATED → DONE), `paused`, `data`, журнал переходов | нет |
| `task18-scheduler.json`, `task20-scheduler.json` | SchedulerMcpServer (дни 18, 20) | [schemas/scheduler-store.schema.json](schemas/scheduler-store.schema.json) | нет |
| `task21-index-{fixed,structure}.json` | RagIndex (дни 21–25) | [schemas/rag-index.schema.json](schemas/rag-index.schema.json), ~7 МБ каждый | нет |
| `task23-rag-settings.json` | пишет Task23, читают дни 24–25 | [schemas/rag-settings.schema.json](schemas/rag-settings.schema.json) | нет |
| `task25-chat.json` | RagChat (день 25, интерактивный чат) | `{state: TaskState, history: [{role, content}]}` — см. ниже | нет |
| `task27-course-index.json` | StudyAssistant (день 27) | формат RagIndex; пересобирается при изменении курса или эмбеддера | нет |
| `task30-api-key.txt` | Task30 | сгенерированный API-ключ приватного сервиса | нет |
| `task31-docs-index.json` (+ `.sha256`) | ProjectDocs (день 31) | формат RagIndex + отпечаток sha256 документации | нет |
| `taskN-*-report.md` | каждый TaskN | отчёт Markdown, перегенерируется при каждом запуске | да |

## task23-rag-settings.json

Откалиброванные настройки второго этапа retrieval. Приоритет: значения по умолчанию < файл (только если `embedder` и `strategy`
совпадают с индексом) < переменные окружения `RAG_TOP_K_BEFORE`, `RAG_TOP_K`, `RAG_RERANK`, `RAG_MIN_SIMILARITY`,
`RAG_RELATIVE_CUT`, `RAG_MIN_RERANK`, `RAG_REWRITE`.

| Поле | Тип | Смысл |
|---|---|---|
| `topKBefore` | int | кандидатов из векторного поиска (20) |
| `topKAfter` | int | фрагментов в контексте после реранкинга (5) |
| `rerank` | `NONE` / `THRESHOLD` / `HEURISTIC` / `LLM` | второй этап: без фильтра / порог косинуса / порог + эвристика по словам запроса / оценка кандидатов моделью 0–10 |
| `minSimilarity` | number 0..1 | абсолютный порог косинусного сходства; калибруется Task23 и зависит от эмбеддера |
| `relativeCut` | number 0..1 | фрагмент должен набрать ≥ этой доли от лучшего косинуса (0.6) |
| `minRerank` | number 0..1 | порог оценки реранкера (для LLM — доля от 10) |
| `rewrite` | bool | переписывать запрос в 2–3 поисковых (RU + EN) |
| `embedder` | string | для какого эмбеддера откалибровано, например `api:qwen3-vl-embedding-8b` |
| `strategy` | `fixed` / `structure` | стратегия нарезки индекса |
| `calibrated` | string | время калибровки |

Пример:

```json
{"topKBefore": 20, "topKAfter": 5, "rerank": "LLM", "minSimilarity": 0.59, "relativeCut": 0.6,
 "minRerank": 0.5, "rewrite": true, "embedder": "api:qwen3-vl-embedding-8b", "strategy": "structure",
 "calibrated": "2026-10-04 23:36:47"}
```

## agent-context.json (LlmAgent, версия 4)

```json
{
  "version": 4,
  "branches": {"main": {"history": [{"role": "system", "content": "…"}, {"role": "user", "content": "…"}],
                         "summary": "сводка старых сообщений или null", "facts": {"цель": "…"}}},
  "active": "main",
  "checkpoint": {"history": ["…"], "summary": null, "facts": {}},
  "stats": {"prompt_tokens": 0, "completion_tokens": 0, "max_prompt_tokens": 0, "turns": 0}
}
```

Первое сообщение каждой ветки — `system`. Старые форматы (v1 — массив сообщений, v2/v3 — `{history, summary, stats}`) читаются и
мигрируют. Файл задаётся `AGENT_CONTEXT_FILE`.

## task25-chat.json (RagChat)

```json
{
  "state": {"goal": "цель диалога", "clarified": ["что уточнил пользователь"],
            "constraints": ["ограничения к ответам"], "terms": {"термин": "значение"}},
  "history": [{"role": "user", "content": "…"}, {"role": "assistant", "content": "…"}]
}
```

`state` — память задачи (`RagChat.TaskState`), она попадает в system prompt каждого ответа. Команда `reset` удаляет файл.

## Индекс RAG

Один JSON на индекс: `{version: 1, meta: {strategy, params, embedder, dim, createdAt, documents, corpusChars, embedderState},
chunks: [{chunkId, source, title, section, strategy, ordinal, startLine, endLine, lead, text, vector}]}`.
`chunkId` — `f-0001` (fixed) или `s-0001` (structure). Векторы нормализованы, поиск — перебор с косинусом.
Запросы обязательно эмбеддятся тем же эмбеддером, что записан в `meta.embedder`; при смене модели индекс пересобирают.
