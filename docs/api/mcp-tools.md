# Каталог MCP-серверов и инструментов

Все серверы — собственные stdio MCP-серверы проекта на официальном MCP Java SDK 2.0.1 (`McpServer.sync` + `StdioServerTransportProvider`).
Клиент запускает их дочерним JVM-процессом (`week4/McpLaunch.javaServer`). Результаты — текст для LLM + `structuredContent`; ошибки — `isError=true`.

| Сервер | Класс | День | Инструменты |
|---|---|---|---|
| `git-mcp-server` | `week4/Task17.GitMcpServer` | 17, 20 | `git_log`, `git_commit_details`, `git_branches` |
| scheduler | `week4/Task18.SchedulerMcpServer` | 18, 20 | `schedule_job`, `list_jobs`, `cancel_job`, `get_summary` |
| docs pipeline | `week4/Task19.DocsPipelineMcpServer` | 19, 20 | `search_docs`, `summarize`, `save_to_file` |
| `project-mcp-server` | `week7/ProjectMcpServer` | 31 | `git_current_branch`, `git_status`, `list_files`, `git_diff` |

## project-mcp-server (день 31, ассистент разработчика)

Только чтение рабочей копии: `git` с `GIT_OPTIONAL_LOCKS=0`, пути — только относительные внутри репозитория (без `..`, `/…`, `-…`), ref проверяется регуляркой.

| Инструмент | Параметры | Возвращает |
|---|---|---|
| `git_current_branch` | — | `branch`, `detached`, `head` (короткий хеш), `headSubject`, `upstream`, `ahead`, `behind`, `repository` |
| `git_status` | — | `branchLine`, `staged[]`, `modified[]`, `deleted[]`, `untracked[]`, `clean` |
| `list_files` | `path` (папка/файл), `glob` (например `**/*.md`, `docs/**`), `limit` 1–500 (200), `include_untracked` (true) | `total`, `files[]`, `truncated`, `byDirectory{папка: число}` — по `git ls-files`, `.gitignore` учитывается |
| `git_diff` | `path`, `staged` (false), `ref` (например `main`, `HEAD~1`), `stat_only` (false), `max_chars` 500–60000 (12000) | `files[{path, added, deleted, binary}]`, `totalAdded`, `totalDeleted`, `staged`, `ref`, `truncated` + текст патча |

## git-mcp-server (день 17)

| Инструмент | Параметры | Возвращает |
|---|---|---|
| `git_log` | `limit` 1–50 (10), `ref`, `author`, `path` | коммиты: `sha`, `short_sha`, `author`, `email`, `date`, `subject` |
| `git_commit_details` | `sha` (обязателен) | автор, дата, сообщение, файлы с `added` / `deleted`, итоги |
| `git_branches` | `include_remote` (false) | ветки по свежести коммита, `current` |

## scheduler (день 18)

| Инструмент | Параметры | Смысл |
|---|---|---|
| `schedule_job` | `name`*, `kind`* (`git_snapshot` / `reminder`), `interval_seconds`* 5–86400, `message`, `once` | создать или обновить фоновое задание |
| `list_jobs` | — | все задания: тип, период, активность, число запусков |
| `cancel_job` | `name`* | остановить задание (данные остаются) |
| `get_summary` | `since_minutes` (60), `job` | агрегированная сводка собранных данных |

Хранилище — `task18-scheduler.json`, формат [../schemas/scheduler-store.schema.json](../schemas/scheduler-store.schema.json).

## docs pipeline (день 19)

Цепочка `search_docs → summarize → save_to_file`, шаги связаны идентификаторами результатов.

| Инструмент | Параметры | Смысл |
|---|---|---|
| `search_docs` | `query`*, `limit` 1–10 (5) | полнотекстовый поиск по *.md / *.java проекта, возвращает `result_id` |
| `summarize` | `source_id` (result_id) или `text`, `focus`, `max_points` 2–12 (6) | краткая выжимка, возвращает `summary_id` |
| `save_to_file` | `summary_id` или `content`, `filename`* | сохраняет Markdown в `task19-output/` (день 20 — `task20-output/`) |

## Оркестрация (день 20)

`week4/McpRegistry` поднимает три сервера (`git`, `docs`, `scheduler`) и публикует их инструменты под именами `<server>__<tool>`
(например `git__git_log`), описание каждого начинается с `[<server>: <назначение>]`. Неизвестное имя → ошибка инструмента со списком доступных.
