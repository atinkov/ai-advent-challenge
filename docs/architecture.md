# Архитектура проекта

## Слои

Каждый день построен из трёх слоёв, и код не дублируется между днями:

```
UI / сценарий      weekN/TaskN.java         — main(), CLI или HTTP-сервер, демо-сценарий, генерация отчёта
        │
агент / движок     LlmAgent, McpToolAgent,  — промпты, история, память, RAG, цикл вызова инструментов
                   RagPipeline, DevAssistant
        │
транспорт          LlmClient                — env/.env-конфиг, HTTP к OpenAI-совместимому /chat/completions,
                   OllamaClient (week6)       /embeddings, /models; нативный API Ollama
```

- **UI** — классы `TaskN`. Они тонкие: печатают, разбирают аргументы, пишут отчёт `taskN-*-report.md`.
- **Агент** — общие классы в корне пакета (`LlmAgent`) и в пакетах недель (`week4/McpToolAgent`, `week5/RagPipeline`, `week7/DevAssistant`).
- **Транспорт** — `LlmClient` (все облачные вызовы) и `week6/OllamaClient` (локальная модель). Новую HTTP/env-логику кладут сюда.

## Общие компоненты

| Класс | Назначение | Используют |
|---|---|---|
| `LlmClient` | конфиг (`fromEnv()`: process env > `.env` > значения по умолчанию), `send()`, `chat()`, `sendWithTools()` (function calling), `embed()`, `listModels()`, `newHttpClient()` (учитывает `LLM_INSECURE_TLS`) | все дни |
| `LlmAgent` | агент с персоной, историей, сжатием (rolling summary), стратегиями контекста и ветками диалога; хранит состояние в `agent-context.json` | дни 6–10 |
| `week4/McpToolAgent` | LLM + инструменты из MCP: превращает `tools/list` в OpenAI `tools`, выполняет `tool_calls` через `tools/call`; режимы `auto` / `native` / `prompt` (`<tool_call>{…}</tool_call>` для моделей без function calling) | дни 17–20, 31 |
| `week4/McpRegistry` | несколько MCP-серверов за одним каталогом инструментов `<server>__<tool>` + маршрутизация | день 20 |
| `week4/McpLaunch` | запуск MCP-сервера этого проекта дочерним JVM-процессом с тем же classpath | дни 17–20, 31–34 |
| `week5/Chunker` | нарезка: фиксированный размер и структурная (заголовки Markdown, строки таблиц, члены Java-классов) | дни 21–25, 27, 31 |
| `week5/Embedder` | эмбеддинги: `Api` (OpenAI-совместимый `/embeddings`) и `LocalHash` (офлайн лексический запасной вариант) | дни 21–25, 27, 31 |
| `week5/RagIndex` | индекс: чанки + метаданные + векторы, JSON-файл, косинусный поиск | дни 21–25, 31 |
| `week5/RagPipeline` | retrieve (rewrite → search → rerank/фильтр) + ответы с проверкой цитат | дни 22–25 |
| `week6/OllamaClient` | нативный API Ollama: версии, модели, `/api/chat` со стримингом и метриками | дни 26–30 |
| `week7/ProjectDocs` | RAG-корпус по путям (документация и/или код) с индексом и автопересборкой по sha256; поиск с фильтром по источнику | дни 31–32 |
| `week7/ProjectFiles` | песочница над файлами проекта: список, чтение, поиск, анализ Markdown-ссылок, git log; изменения в оверлее → unified diff (`git apply`) | день 34 |
| `week7/CodeReviewer` | ревью PR: diff `base...head` + изменённые файлы → RAG по документации и коду → LLM (JSON) → проверка замечаний → Markdown-комментарий | день 32 |

## Пакеты по неделям

- `week1` — дни 1–5, прямые вызовы `LlmClient`.
- `week2` — дни 6–10, всё через `LlmAgent`.
- `week3` — дни 11–15, самостоятельные модели памяти/состояния (намеренно не расширяют `LlmAgent`).
- `week4` — дни 16–20, MCP: клиент на официальном MCP Java SDK, собственные stdio-серверы (`GitMcpServer`, `SchedulerMcpServer`, `DocsPipelineMcpServer`).
- `week5` — дни 21–25, RAG по самому проекту.
- `week6` — дни 26–30, локальная LLM: приложение «Наставник НТ» (порт 8027), приватный AI-сервис (порт 8030).
- `week7` — день 31+: ассистент разработчика (`ProjectMcpServer`, `ProjectDocs`, `DevAssistant`, `Task31`) AI-ревью PR (`CodeReviewer`, `Task32` + `.github/workflows/ai-review.yml`) ассистент поддержки (`CrmMcpServer`, `SupportKnowledge`, `SupportAssistant`, `Task33`) и ассистент по файлам проекта (`ProjectFiles`, `ProjectFilesMcpServer`, `FilesAssistant`, `Task34`).

## Поток данных ассистента разработчика (день 31)

```
/help <вопрос>
   │
   ├─► MCP tools/call git_current_branch ──► ProjectMcpServer (дочерний процесс, stdio) ──► git CLI (read-only)
   ├─► ProjectDocs.retrieve(вопрос) ──► task31-docs-index.json (README + docs/ + AGENTS.md)
   └─► McpToolAgent: system prompt + [ветка + фрагменты 1..N + вопрос]
            │  модель может вызвать git_status / list_files / git_diff через MCP
            ▼
       ответ со ссылками [n] → проверка ссылок в коде → «Источники» из метаданных индекса
```

## MCP в проекте

Все собственные MCP-серверы — классы с `main()`, работают по stdio (stdout занят JSON-RPC, логи — только в stderr),
стартуют дочерним процессом через `McpLaunch.javaServer(...)`, завершаются, когда клиент закрывает stdin.
Инструменты регистрируются через `McpServer.sync(...).toolCall(Tool, handler)`: у каждого есть JSON Schema входных параметров,
результат возвращается и текстом (для LLM), и `structuredContent` (для приложения). Ошибки — `isError=true`, сервер не падает.
Каталог инструментов — [api/mcp-tools.md](api/mcp-tools.md).

## Конфигурация

Порядок поиска значения: переменная окружения процесса → файл `.env` в корне → значение по умолчанию в коде.
Основные переменные: `LLM_API_KEY`, `HINDSIGHT_API_LLM_BASE_URL`, `HINDSIGHT_API_LLM_MODEL`, `HINDSIGHT_API_LLM_PROVIDER` (только `openai`),
`EMBEDDING_BASE_URL` / `EMBEDDING_API_KEY` / `EMBEDDING_MODEL` / `EMBEDDING_PROVIDER` (`auto` | `api` | `local`), `LLM_INSECURE_TLS`, `OLLAMA_HOST`.
Переменные отдельных дней имеют префикс `TASKN_` (например `TASK17_TOOL_MODE`, `TASK30_PORT`, `TASK31_TOP_K`).

## Поток данных ассистента по файлам (день 34)

```
цель словами ─► Task34 ─► FilesAssistant ─► McpToolAgent (LLM, tool calling) ─┐
                                   ▲  Recorder: каждый вызов + structuredContent │ tools/call
                                   │                                              ▼
                     pending_changes ◄── ProjectFilesMcpServer (stdio, дочерний JVM) ── ProjectFiles(корень проекта)
                                   │                          dry-run: оверлей в памяти │ --apply: запись на диск
                                   ▼
         ответ + task34-output/*.patch + проверки по эталону из того же ProjectFiles → task34-files-assistant-report.md
```
