# День 31. Ассистент разработчика: RAG по документации + MCP к репозиторию

> Сгенерировано `Task31` (`mvn -q compile exec:java -Ptask31`) 2026-10-19 00:15. Ветка `week6`, модель `qwen3.8-27b`, эмбеддер `local-hash-2048`.

## Вердикт: ✅ задание выполнено

| Условие задания | Итог |
|---|---|
| 1. RAG по документации проекта | ✅ |
| 2. MCP-подключение к проекту | ✅ |
| 3. Команда /help | ✅ |

Пройдено проверок: 15 из 16 (обязательные решают вердикт; «по желанию» и «доп.» — для полноты).

## Условие задания

- Используйте RAG для подключения к документации проекта: README, project/docs, схемы данных или API-описания.
- Через MCP подключите ассистента к проекту: минимум — получение текущей git-ветки; по желанию — список файлов или diff.
- Реализуйте команду /help: ассистент отвечает на вопросы о проекте, использует документацию и контекст проекта.

## Как устроено

```
разработчик: /help <вопрос>
      │
      ▼
Task31 (CLI) ──► DevAssistant
                   ├─ MCP (stdio) ► ProjectMcpServer: git_current_branch — всегда, приложением;
                   │                git_status / list_files / git_diff — по решению модели
                   ├─ RAG ► ProjectDocs: README.md + docs/ (архитектура, схемы, API) + AGENTS.md
                   │        → task31-docs-index.json (пересборка при любом изменении документации)
                   └─ LLM ◄ ветка + фрагменты [1..N] + вопрос; ссылки [n] проверяются в коде
```

## Проверки

| Условие | Проверка | Обяз. | Итог | Детали |
|---|---|---|---|---|
| 1 | README.md подключён к RAG (есть в индексе) | да | ✅ | 6 фрагм. |
| 1 | Папка docs/ подключена к RAG: все её файлы в индексе | да | ✅ | в docs/ файлов 10, в индексе 10 |
| 1 | Схемы данных или API-описания в индексе | да | ✅ | схем данных: 5 (docs/data-schemas.md, docs/schemas/agent-context.schema.json, docs/schemas/rag-index.schema.json, docs/schemas/rag-settings.schema.json, docs/schemas/scheduler-store.schema.json); API: 3 (docs/api/mcp-tools.md, docs/api/private-ai-service.openapi.yaml, docs/api/study-app-http.md) |
| 1 | Поиск по вопросу о структуре находит README / docs | да | ✅ | [1] README.md › Структура (строки 61-83) 0,144; [2] docs/architecture.md › Общие компоненты › week6/OllamaClient + Пакеты по неделям (строки 34-45) 0,109; [3] docs/architecture.md › Архитектура проекта + Слои (строки 1-20) 0,107; [4] docs/dev-assistant.md › Переменные окружения (строки 65-78) 0,104 |
| 2 | MCP handshake (initialize) с project-mcp-server | да | ✅ | протокол 2025-11-25, /Users/aleksey/Library/Java/JavaVirtualMachines/corretto-25.0.1/Contents/Home/bin/java -cp <classpath проекта> ru.lemanapro.aiadventchallenge.week7.ProjectMcpServer /Users/aleksey/Desktop/AIAdventChallenge |
| 2 | tools/list содержит git_current_branch | да | ✅ | git_current_branch, git_status, list_files, git_diff |
| 2 | Текущая git-ветка через MCP = `git branch --show-current` (команда /branch) | да | ✅ | MCP: «week6», git: «week6», 25 мс |
| 2 | По желанию: список файлов (MCP list_files, команда /files) | нет | ✅ | Файлов: 10 по маске docs/** |
| 2 | По желанию: diff (MCP git_diff, команда /diff) | нет | ✅ | Diff: незакоммиченные изменения — файлов 6, +316 −123 |
| 3 | Команда /help без аргументов показывает справку по командам | да | ✅ | список команд + источники знаний (документация, ветка week6) |
| 3 | /help «Какая структура у проекта: какие есть папки и пакеты и что л…» — вопрос о структуре проекта → ответ по README/docs со ссылками | да | ✅ | контекст проекта: ветка `week6` из MCP в промпте; RAG нашёл `README.md\|docs/architecture.md`; ответ ссылается на документацию: 3 ссыл.; ответ содержит `week\d\|src/main` |
| 3 | /help «В какой ветке я сейчас работаю и по какому правилу в проекте…» — контекст проекта: ветка из MCP + правило из документации | да | ✅ | контекст проекта: ветка `week6` из MCP в промпте; RAG нашёл `AGENTS.md\|README.md\|docs/`; ответ ссылается на документацию: 1 ссыл.; в ответе текущая ветка `week6` |
| 3 | /help «Какие файлы сейчас изменены или добавлены в рабочей копии, н…» — контекст проекта: модель сама вызывает MCP (статус/diff) | да | ✅ | контекст проекта: ветка `week6` из MCP в промпте; модель вызвала MCP `git_status\|git_diff` |
| 3 | Доп.: /help «Какие поля у файла task23-rag-settings.json и что означает m…» — схема данных | нет | ✅ | контекст проекта: ветка `week6` из MCP в промпте; RAG нашёл `docs/schemas/\|docs/data-schemas.md`; ответ ссылается на документацию: 5 ссыл.; ответ содержит `minSimilarity` |
| 3 | Доп.: /help «Какие эндпоинты у приватного AI-сервиса дня 30 и какие коды …» — API-описание (OpenAPI) | нет | ❌ | контекст проекта: ветка `week6` из MCP в промпте; RAG нашёл `docs/api/`; ответ ссылается на документацию: 1 ссыл.; ответ содержит `/v1/chat/completions` |
| 3 | Доп.: /help «Какой рецепт борща описан в документации проекта?» — вопрос не по теме → честное «этого нет» | нет | ✅ | контекст проекта: ветка `week6` из MCP в промпте; ответ содержит `(?i)нет\|не (описан\|найден\|содерж)` |

## База знаний (RAG)

индекс загружен из task31-docs-index.json (документация не менялась)

| Файл | Группа | Фрагментов |
|---|---|---|
| `README.md` | README | 6 |
| `docs/api/mcp-tools.md` | API | 6 |
| `docs/api/private-ai-service.openapi.yaml` | API | 4 |
| `docs/api/study-app-http.md` | API | 1 |
| `docs/architecture.md` | docs | 6 |
| `docs/data-schemas.md` | схемы данных | 7 |
| `docs/dev-assistant.md` | docs | 8 |
| `docs/schemas/agent-context.schema.json` | схемы данных | 2 |
| `docs/schemas/rag-index.schema.json` | схемы данных | 2 |
| `docs/schemas/rag-settings.schema.json` | схемы данных | 2 |
| `docs/schemas/scheduler-store.schema.json` | схемы данных | 2 |
| `AGENTS.md` | база знаний (AGENTS.md) | 56 |

## Инструменты MCP-сервера `project-mcp-server`

| Инструмент | Описание |
|---|---|
| `git_current_branch` | Текущая git-ветка рабочей копии: имя ветки (или detached HEAD), короткий хеш и заголовок HEAD-коммита, upstream и на сколько коммитов ветка впереди/позади него. |
| `git_status` | Незакоммиченные изменения: файлы в индексе (staged), изменённые (modified), удалённые и неотслеживаемые (untracked), плюс текущая ветка. |
| `list_files` | Список файлов проекта под git (.gitignore учитывается): можно ограничить папкой и glob-маской, например path=src/main/java, glob=**/week7/*.java. Возвращает пути и сводку по папкам. |
| `git_diff` | Что изменено: сводка --stat и сам патч. По умолчанию — незакоммиченные изменения рабочей копии; staged=true — только индекс; ref — сравнить с веткой/коммитом (например main или HEAD~1). Патч обрезается до max_chars. |

## Вопросы /help

| # | Тип | Вопрос | Что проверяет | Итог | Детали |
|---|---|---|---|---|---|
| 1 | обяз. | Какая структура у проекта: какие есть папки и пакеты и что лежит в каждом? | вопрос о структуре проекта → ответ по README/docs со ссылками | ✅ | ✅ контекст проекта: ветка `week6` из MCP в промпте<br>✅ RAG нашёл `README.md|docs/architecture.md`<br>✅ ответ ссылается на документацию: 3 ссыл.<br>✅ ответ содержит `week\d|src/main` |
| 2 | обяз. | В какой ветке я сейчас работаю и по какому правилу в проекте называют ветки? | контекст проекта: ветка из MCP + правило из документации | ✅ | ✅ контекст проекта: ветка `week6` из MCP в промпте<br>✅ RAG нашёл `AGENTS.md|README.md|docs/`<br>✅ ответ ссылается на документацию: 1 ссыл.<br>✅ в ответе текущая ветка `week6` |
| 3 | обяз. | Какие файлы сейчас изменены или добавлены в рабочей копии, но ещё не закоммичены? | контекст проекта: модель сама вызывает MCP (статус/diff) | ✅ | ✅ контекст проекта: ветка `week6` из MCP в промпте<br>✅ модель вызвала MCP `git_status|git_diff` |
| 4 | доп. | Какие поля у файла task23-rag-settings.json и что означает minSimilarity? | схема данных | ✅ | ✅ контекст проекта: ветка `week6` из MCP в промпте<br>✅ RAG нашёл `docs/schemas/|docs/data-schemas.md`<br>✅ ответ ссылается на документацию: 5 ссыл.<br>✅ ответ содержит `minSimilarity` |
| 5 | доп. | Какие эндпоинты у приватного AI-сервиса дня 30 и какие коды ошибок он возвращает? | API-описание (OpenAPI) | ❌ | ✅ контекст проекта: ветка `week6` из MCP в промпте<br>✅ RAG нашёл `docs/api/`<br>✅ ответ ссылается на документацию: 1 ссыл.<br>❌ ответ содержит `/v1/chat/completions` |
| 6 | доп. | Какой рецепт борща описан в документации проекта? | вопрос не по теме → честное «этого нет» | ✅ | ✅ контекст проекта: ветка `week6` из MCP в промпте<br>✅ ответ содержит `(?i)нет|не (описан|найден|содерж)` |

## Ответы

### 1. Какая структура у проекта: какие есть папки и пакеты и что лежит в каждом?

- MCP `git_current_branch` (приложение): `week6`
- RAG: 4 фрагм. — [1] `README.md › Структура (строки 61-83)` 0,144; [2] `docs/architecture.md › Общие компоненты › week6/OllamaClient + Пакеты по неделям (строки 34-45)` 0,109; [3] `docs/architecture.md › Архитектура проекта + Слои (строки 1-20)` 0,107; [4] `docs/dev-assistant.md › Переменные окружения (строки 65-78)` 0,104
- MCP-вызовы модели: `list_files {"limit": 100}`
- 5.978 с, запросов к LLM: 2

> ## Структура проекта
> 
> ### Корень репозитория
> - `pom.xml` — Java 25, зависимости: jackson-databind, MCP Java SDK 2.0.1, slf4j-nop; профили `task1…task31` [1]
> - `.env` — конфигурация LLM и ключ (gitignored) [1]
> - `README.md` — основной файл
> - `AGENTS.md` — подробная база знаний проекта: каждый день, соглашения, команды [1]
> - `taskN-*-report.md` — отчёты, которые генерирует каждый день (в корне уже лежат отчёты task10–task24 и JSON-артефакты) [1]
> 
> ### `docs/` — документация для людей и RAG-ассистента [1]
> - `architecture.md` — слои, общие компоненты, пакеты, поток данных
> - `dev-assistant.md` — ассистент разработчика (день 31)
> - `data-schemas.md` — какие JSON-файлы пишет проект и их форматы
> - `schemas/` — JSON Schema файлов данных (`agent-context`, `rag-index`, `rag-settings`, `scheduler-store`)
> - `api/` — HTTP API (OpenAPI) и каталог MCP-инструментов (`mcp-tools.md`, `private-ai-service.openapi.yaml`, `study-app-http.md`)
> 
> ### `src/main/java/ru/lemanapro/aiadventchallenge/` — код [1]
> Общие классы в корне пакета:
> - `LlmClient.java` — общий транспорт: конфиг из env/.env, `chat`/`send`/`sendWithTools`/`embed` [1]
> - `LlmAgent.java` — общий агент: персона, история, сжатие, ветки диалога [1]
> - `Main.java` — намеренно пустая заглушка [1]
> 
> Пакеты по неделям [2]:
> - `week1` (дни 1–5) — прямые вызовы `LlmClient`: `Task1`–`Task5`
> - `week2` (дни 6–10) — всё через `LlmAgent`: `Task6`, `Task8`, `Task9`, `Task10`
> - `week3` (дни 11–15) — самостоятельные модели памяти/состояния (намеренно не расширяют `LlmAgent`): `Task11`–`Task15`
> - `week4` (дни 16–20) — MCP: клиент на MCP Java SDK, собственные stdio-серверы: `McpLaunch`, `McpRegistry`, `McpToolAgent`, `Task16`–`Task20`
> - `week5` (дни 21–25) — RAG по самому проекту: `Chunker`, `ControlSet`, `Embedder`, `RagAgent`, `RagChat`, `RagIndex`, `RagPipeline`, `Task21`–`Task25`
> - `week6` (дни 26–30) — локальная LLM: приложение «Наставник НТ» (порт 8027), приватный AI-сервис (порт 8030): `OllamaClient`, `CourseLibrary`, `StudyAssistant`, `Task26`–`Task30`
> - `week7` (день 31+) — ассистент разработчика: `ProjectMcpServer`, `ProjectDocs`, `DevAssistant`, `Task31`
> 
> ### `src/main/resources/task27/` — веб-интерфейс дня 27 [1]
> `index.html`, `app.js`, `style.css`, `marked.min.js`, `favicon.svg`
> 
> ### Архитектурные слои [3]
> Каждый день построен из трёх слоёв, код не дублируется:
> 1. **UI** — классы `TaskN`: тонкие, печатают, разбирают аргументы, пишут отчёт `taskN-*-report.md`
> 2. **Агент** — `LlmAgent` (корень) и классы недель: `week4/McpToolAgent`, `week5/RagPipeline`, `week7/DevAssistant`
> 3. **Транспорт** — `LlmClient` (облачные вызовы) и `week6/OllamaClient` (локальная модель); новую HTTP/env-логику кладут сюда
> 
> Текущая ветка — `week6`, HEAD: «Completed task 29 - Оптимизация локальной LLM», синхронизирована с `origin/week6`.

Источники (проверены в коде): [1] `README.md › Структура (строки 61-83)`; [2] `docs/architecture.md › Общие компоненты › week6/OllamaClient + Пакеты по неделям (строки 34-45)`; [3] `docs/architecture.md › Архитектура проекта + Слои (строки 1-20)`

### 2. В какой ветке я сейчас работаю и по какому правилу в проекте называют ветки?

- MCP `git_current_branch` (приложение): `week6`
- RAG: 2 фрагм. — [1] `docs/architecture.md › MCP в проекте (строки 59-66)` 0,090; [2] `docs/dev-assistant.md › Автопроверка задания (строки 17-30)` 0,086
- MCP-вызовы модели: —
- 5.113 с, запросов к LLM: 1

> **Текущая ветка:** `week6` (HEAD `7e451a6`, синхронизирована с `origin/week6`).
> 
> **Правило именования:** В предоставленных фрагментах документации явное правило не прописано, но из названия ветки `week6` и структуры проекта (AIAdventChallenge — задания по неделям) видно, что ветки называются по номеру недели: `week1`, `week2`, …, `week6`, … [2]. Фрагмент [2] подтверждает, что «текущая ветка + правило именования» — обязательная часть ответа `/help`, причём ветка берётся из MCP-инструмента `git_current_branch`.
> 
> Если нужно точное формулирование правила (например, что делать с hotfix-ветками или ветками для отдельных задач), его стоит поискать в `AGENTS.md` или `docs/` — в данных фрагментах он не раскрыт.

Источники (проверены в коде): [2] `docs/dev-assistant.md › Автопроверка задания (строки 17-30)`

### 3. Какие файлы сейчас изменены или добавлены в рабочей копии, но ещё не закоммичены?

- MCP `git_current_branch` (приложение): `week6`
- RAG: 1 фрагм. — [1] `docs/api/mcp-tools.md › project-mcp-server (день 31, ассистент разработчика) (строки 13-23)` 0,098
- MCP-вызовы модели: `git_status {}`
- 3.148 с, запросов к LLM: 2

> В рабочей копии ветки `week6` есть незакоммиченные изменения:
> 
> **В индексе (staged, 4 файла)** — новые файлы:
> - `src/main/java/ru/lemanapro/aiadventchallenge/week7/DevAssistant.java`
> - `src/main/java/ru/lemanapro/aiadventchallenge/week7/ProjectDocs.java`
> - `src/main/java/ru/lemanapro/aiadventchallenge/week7/ProjectMcpServer.java`
> - `src/main/java/ru/lemanapro/aiadventchallenge/week7/Task31.java`
> 
> **Изменены, но не добавлены в индекс (6 файлов):**
> - `.gitignore`
> - `AGENTS.md`
> - `pom.xml`
> - `src/main/java/ru/lemanapro/aiadventchallenge/week4/McpLaunch.java`
> - `src/main/java/ru/lemanapro/aiadventchallenge/week4/McpToolAgent.java`
> - `src/main/java/ru/lemanapro/aiadventchallenge/week7/Task31.java` (изменён и в индексе, и в рабочей копии — есть ещё несохранённые правки)
> 
> **Неотслеживаемые (21 файл)** — новые, ещё не добавленные в git:
> - `README.md`
> - Документация: `docs/api/mcp-tools.md`, `docs/api/private-ai-service.openapi.yaml`, `docs/api/study-app-http.md`, `docs/architecture.md`, `docs/data-schemas.md`, `docs/dev-assistant.md`
> - Схемы: `docs/schemas/agent-context.schema.json`, `docs/schemas/rag-index.schema.json`, `docs/schemas/rag-settings.schema.json`, `docs/schemas/scheduler-store.schema.json`
> - JSON-данные задач: `task11-long-term-memory.json`, `task12-profile-alexey.json`, `task12-profile-igor.json`, `task12-profile-maria.json`, `task13-state.json`, `task14-invariants.json`, `task15-lifecycle.json`, `task23-rag-settings.json`
> - Отчёт: `task31-dev-assistant-report.md`
> - Служебный: `.omo/run-continuation/ses_fa148227cffeRDFm4kCupFODWl.json`
> 
> Ветка синхронизирована с `origin/week6` (0 впереди / 0 позади).

### 4. Какие поля у файла task23-rag-settings.json и что означает minSimilarity?

- MCP `git_current_branch` (приложение): `week6`
- RAG: 5 фрагм. — [1] `docs/schemas/rag-settings.schema.json › часть 1/2 (строки 1-14)` 0,271; [2] `docs/data-schemas.md › task23-rag-settings.json (строки 26-52)` 0,225; [3] `AGENTS.md › WHERE TO LOOK › Day 23 (rerank + filter + rewrite) (часть 1/2) (строки 143-143)` 0,220; [4] `docs/data-schemas.md › Сводка файлов › task23-rag-settings.json … taskN-*-report.md (строки 19-25)` 0,182; [5] `docs/schemas/rag-settings.schema.json › часть 2/2 (строки 14-27)` 0,169
- MCP-вызовы модели: —
- 4.485 с, запросов к LLM: 1

> ## Поля `task23-rag-settings.json`
> 
> Файл пишется Task23, читают дни 24–25 [4]. Обязательные поля: `topKBefore`, `topKAfter`, `rerank`, `minSimilarity`, `relativeCut`, `minRerank`, `rewrite`, `embedder`, `strategy` [1].
> 
> | Поле | Тип | Смысл |
> |---|---|---|
> | `topKBefore` | int (default 20) | Сколько кандидатов брать из векторного поиска до второго этапа. Env: `RAG_TOP_K_BEFORE` [1][2] |
> | `topKAfter` | int (default 5) | Сколько фрагментов оставить в контексте после реранкинга. Env: `RAG_TOP_K` [1][2] |
> | `rerank` | `NONE` / `THRESHOLD` / `HEURISTIC` / `LLM` (default `LLM`) | Второй этап: без фильтра / порог косинуса / порог + эвристика по словам запроса / оценка кандидатов моделью 0–10. Env: `RAG_RERANK` [1][2] |
> | `minSimilarity` | number 0..1 (default 0.30) | Абсолютный порог косинусного сходства запроса и фрагмента. Env: `RAG_MIN_SIMILARITY` [1][2] |
> | `relativeCut` | number 0..1 (default 0.6) | Фрагмент должен набрать ≥ этой доли от лучшего косинуса. Env: `RAG_RELATIVE_CUT` [2][5] |
> | `minRerank` | number 0..1 (default 0.5) | Порог оценки реранкера (для LLM — доля от 10). Env: `RAG_MIN_RERANK` [2][5] |
> | `rewrite` | bool (default true) | Переписывать ли запрос LLM-ом в 2–3 поисковых (RU + EN с идентификаторами). Env: `RAG_REWRITE` [2][5] |
> | `embedder` | string | Для какого эмбеддера откалибровано, например `api:qwen3-vl-embedding-8b` или `local-hash-2048` [2][5] |
> | `strategy` | `fixed` / `structure` | Стратегия нарезки индекса [2][5] |
> | `calibrated` | string | Время калибровки, `yyyy-MM-dd HH:mm:ss` [2][5] |
> 
> `additionalProperties: false` — лишних полей быть не должно [5].
> 
> ## Что означает `minSimilarity`
> 
> Абсолютный порог косинусного сходства запроса и фрагмента [1][2]. Используется во втором этапе retrieval:
> 
> - **THRESHOLD** — фрагмент проходит, если косинус ≥ `max(minSimilarity, relativeCut × лучший)` [1][3].
> - **HEURISTIC** — тот же порог плюс эвристика (0.5 × cos/лучший + 0.5 × IDF-доля слов запроса), отсечка по `minRerank` [1][3].
> - **LLM** — модель оценивает кандидатов 0–10, отсечка `minRerank × 10`; при непарсируемом ответе фолбэк на HEURISTIC [3].
> 
> Значение **зависит от эмбеддера**: 0.12 по умолчанию для `local-hash`, 0.30 для API-модели [5]. Калибруется Task23 на контрольном наборе (10 ответимых + 3 `OFF_TOPIC` вопроса): перебор порогов, выбирается лучший по «relevant kept + off-topic rejected» при recall ≥ `TASK23_MIN_RECALL` (0.8) [3].
> 
> Приоритет применения: значения по умолчанию < файл (только если `embedder` и `strategy` совпадают с индексом) < переменные окружения `RAG_*` [1][2][3].

Источники (проверены в коде): [4] `docs/data-schemas.md › Сводка файлов › task23-rag-settings.json … taskN-*-report.md (строки 19-25)`; [1] `docs/schemas/rag-settings.schema.json › часть 1/2 (строки 1-14)`; [2] `docs/data-schemas.md › task23-rag-settings.json (строки 26-52)`; [5] `docs/schemas/rag-settings.schema.json › часть 2/2 (строки 14-27)`; [3] `AGENTS.md › WHERE TO LOOK › Day 23 (rerank + filter + rewrite) (часть 1/2) (строки 143-143)`

### 5. Какие эндпоинты у приватного AI-сервиса дня 30 и какие коды ошибок он возвращает?

- MCP `git_current_branch` (приложение): `week6`
- RAG: 5 фрагм. — [1] `docs/api/mcp-tools.md › docs pipeline (день 19) (строки 43-52)` 0,129; [2] `docs/architecture.md › Общие компоненты › week6/OllamaClient + Пакеты по неделям (строки 34-45)` 0,092; [3] `README.md › Быстрый старт (строки 18-46)` 0,087; [4] `README.md › AIAdventChallenge (строки 1-17)` 0,083; [5] `docs/api/private-ai-service.openapi.yaml › часть 1/5 (строки 1-36)` 0,082
- MCP-вызовы модели: `list_files {"path": "docs/api"}`, `list_files {"path": "src/main/java/week6"}`, `list_files {"path": "src", "limit": 100}`, `git_status {}`
- 7.601 с, запросов к LLM: 4

> По предоставленному фрагменту OpenAPI-спецификации (часть 1/5) видно только начало списка эндпоинтов [5]:
> 
> | Эндпоинт | Метод | Назначение |
> |---|---|---|
> | `/` | GET | Веб-чат (HTML): ключ вводится один раз, история диалога в браузере, ответ печатается по мере генерации. Без авторизации |
> | `/health` | GET | Статус сервиса, без ключа |
> 
> Полный список эндпоинтов и кодов ошибок в предоставленных фрагментах отсутствует — спецификация обрезана после `/health`. В документации проекта этого нет, но полный список есть в файле `docs/api/private-ai-service.openapi.yaml` (он в репозитории, но пока не отслеживается git — лежит в untracked).
> 
> Что можно сказать о характере ошибок из описания сервиса [5]:
> 
> - Порядок обработки запроса: **API-ключ → rate limit → размер тела → лимит контекста / max_tokens → очередь (N слотов) → Ollama** — т.е. ошибки ожидаются на каждом из этих этапов (неверный ключ, превышение RPM, тело > 256 КБ, превышение лимитов контекста/токенов, переполнение очереди).
> - Лимиты задаются env: `TASK30_API_KEYS`, `TASK30_RPM=20`, `TASK30_MAX_CONCURRENT=2`, `TASK30_QUEUE_SECONDS=60`, `TASK30_MAX_CONTEXT=6000`, `TASK30_MAX_TOKENS=1024`, `TASK30_NUM_CTX=8192` [5].
> - Запросы с `127.0.0.1` можно делать без ключа [5].
> 
> Запуск: `mvn -q compile exec:java -Ptask30`, по умолчанию слушает `0.0.0.0:8030` (`TASK30_HOST`, `TASK30_PORT`), проверка — `-Dexec.args="check"` [5].
> 
> Чтобы увидеть точные коды ошибок (401/429/413/503 и т.д.), открой `docs/api/private-ai-service.openapi.yaml` целиком — разделы `responses` в частях 2–5.

Источники (проверены в коде): [5] `docs/api/private-ai-service.openapi.yaml › часть 1/5 (строки 1-36)`

### 6. Какой рецепт борща описан в документации проекта?

- MCP `git_current_branch` (приложение): `week6`
- RAG: 3 фрагм. — [1] `docs/dev-assistant.md › Ассистент разработчика (день 31) (строки 1-7)` 0,131; [2] `docs/dev-assistant.md › Классы (строки 79-88)` 0,129; [3] `README.md › Ассистент разработчика (день 31) (строки 47-60)` 0,090
- MCP-вызовы модели: —
- 1.713 с, запросов к LLM: 1

> В документации проекта AIAdventChallenge рецепта борща нет. Проект — это CLI-ассистент разработчика (RAG + MCP-сервер), и в предоставленных фрагментах описаны только архитектура, классы и команды запуска [1][2][3].
> 
> Если вы искали что-то конкретное в `docs/` или `README.md`, уточните вопрос — помогу найти нужный раздел.

Источники (проверены в коде): [1] `docs/dev-assistant.md › Ассистент разработчика (день 31) (строки 1-7)`; [2] `docs/dev-assistant.md › Классы (строки 79-88)`; [3] `README.md › Ассистент разработчика (день 31) (строки 47-60)`

