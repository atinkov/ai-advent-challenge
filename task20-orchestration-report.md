# День 20. Оркестрация нескольких MCP-серверов

_Сгенерировано: 2026-09-28 01:47:16_ — `mvn -q compile exec:java -Ptask20`, модель qwen3.8-27b

```
                    ┌─ git__*       ─▶ git-mcp-server        (Task17.GitMcpServer)
LLM ⇄ McpToolAgent ⇄ McpRegistry ─┼─ docs__*      ─▶ docs-pipeline-mcp-server (Task19.DocsPipelineMcpServer)
   (один каталог)   (маршрутизация) └─ scheduler__* ─▶ scheduler-mcp-server  (Task18.SchedulerMcpServer)
```

## Реестр серверов

| Сервер | MCP serverInfo | Инструменты (в каталоге агента — `<сервер>__<инструмент>`) |
|---|---|---|
| `git` | git-mcp-server 1.0.0 | `git_log`, `git_commit_details`, `git_branches` |
| `docs` | docs-pipeline-mcp-server 1.0.0 | `search_docs`, `summarize`, `save_to_file` |
| `scheduler` | scheduler-mcp-server 1.0.0 | `schedule_job`, `list_jobs`, `cancel_job`, `get_summary` |

Всего инструментов в каталоге: 10.

## A. Выбор инструмента и маршрутизация

| # | Запрос | Ожидается | Вызовы агента (сервер → инструмент) | Результат |
|---|---|---|---|---|
| 1 | Покажи 3 последних коммита репозитория. | `git__git_log` | git → `git__git_log` | ✅ |
| 2 | Какие ветки есть в репозитории? | `git__git_branches` | git → `git__git_branches` | ✅ |
| 3 | Найди в документации проекта, где описаны инварианты. | `docs__search_docs` | docs → `docs__search_docs` | ✅ |
| 4 | Какие фоновые задания сейчас есть в планировщике? | `scheduler__list_jobs` | scheduler → `scheduler__list_jobs` | ✅ |
| 5 | Поставь одноразовое напоминание через 600 секунд с текстом «выпить чаю», имя задания tea-b… | `scheduler__schedule_job` | scheduler → `scheduler__schedule_job` | ✅ |
| 6 | Сделай краткую выжимку этого текста (не ищи в документации, просто сократи): «MCP — открыт… | `docs__summarize` | docs → `docs__summarize` | ✅ |

Верно выбран и смаршрутизирован первый вызов: **6/6**.

- [OK]   несуществующий инструмент jira__create_issue → ошибка маршрутизации со списком доступных инструментов

## B. Длинный флоу (один диалог, 3 хода)

### Хронология вызовов MCP, выбранных агентом (служебные проверки приложения не включены)

| # | Сервер | Инструмент | Аргументы | Статус | мс |
|---|---|---|---|---|---|
| 1 | git | `git_log` | `{"limit":1}` | ok | 23 |
| 2 | docs | `search_docs` | `{"query":"оркестрация MCP","limit":5}` | ok | 91 |
| 3 | docs | `summarize` | `{"source_id":"s3","focus":"Оркестрация нескольких MCP-серверов (Task20): как регистрируются и используются нез…` | ok | 4687 |
| 4 | docs | `save_to_file` | `{"summary_id":"m4","filename":"status-report.md"}` | ok | 16 |
| 5 | scheduler | `schedule_job` | `{"name":"review-status-report","kind":"reminder","once":true,"interval_seconds":900,"message":"Просмотреть ста…` | ok | 14 |
| 6 | scheduler | `list_jobs` | `{}` | ok | 6 |
| 7 | git | `git_commit_details` | `{"sha":"e544d1c"}` | ok | 39 |
| 8 | scheduler | `cancel_job` | `{"name":"review-status-report"}` | ok | 8 |

### Ход 1

**Пользователь:** Подготовь статус-отчёт по последнему дню работы над проектом: 1) узнай последний коммит репозитория; 2) найди в документации проекта материалы по теме этого коммита; 3) сделай по найденному краткую выжимку; 4) сохрани её в файл status-report.md; 5) поставь одноразовое напоминание через 900 секунд с именем задания review-status-report, в тексте которого должны быть путь к сохранённому файлу и короткий хеш коммита.

Вызовы: `git__git_log` → `docs__search_docs` → `docs__summarize` → `docs__save_to_file` → `scheduler__schedule_job`

**Агент:**

> Готово, статус-отчёт за последний день работы подготовлен:
> 
> 1. **Последний коммит:** `e544d1c` (2026-09-27, atinkov) — «Completed task20 - Оркестрация MCP».
> 2. **Документация:** по теме «оркестрация MCP» найдено 13 файлов, ключевые — `AGENTS.md`, `Task20.java`, `Task17/18/19.java`.
> 3. **Выжимка:** Task20 реализует оркестрацию трёх независимых MCP-серверов (GitMcpServer, scheduler и др.) через `McpRegistry`, каждый — отдельный дочерний процесс; подключение через официальный MCP Java SDK (McpSyncClient, stdio, initialize/ping/tools/list).
> 4. **Файл:** выжимка сохранена в `task20-output/status-report.md` (1905 байт, sha256 `22c1aa99…`).
> 5. **Напоминание:** создано одноразовое задание `review-status-report` (j2) через 900 секунд, с текстом «Просмотреть статус-отчёт: task20-output/status-report.md (коммит e544d1c…)». Следующее выполнение: 02:02:11.

- [OK]   порядок: git_log → search_docs → summarize → save_to_file → schedule_job; фактически [git__git_log, docs__search_docs, docs__summarize, docs__save_to_file, scheduler__schedule_job]
- [OK]   задействованы серверы git, docs и scheduler
- [OK]   git → docs: запрос поиска «оркестрация MCP» по теме последнего коммита «Completed task20 - Оркестрация MCP»
- [OK]   docs: search → summarize по result_id s3
- [OK]   docs: summarize → save_to_file по summary_id m4
- [OK]   файл отчёта создан: task20-output/status-report.md
- [OK]   docs + git → scheduler: напоминание review-status-report содержит путь task20-output/status-report.md и хеш e544d1c

### Ход 2

**Пользователь:** Проверь, что напоминание действительно запланировано, и скажи, когда оно сработает.

Вызовы: `scheduler__list_jobs`

**Агент:**

> Проверил — напоминание действительно запланировано:
> 
> - **Задание:** `review-status-report` (j2), тип reminder, однократное, **активно**
> - **Текст:** «Просмотреть статус-отчёт: task20-output/status-report.md (коммит e544d1c — Completed task20 - Оркестрация MCP)»
> - **Сработает:** в **02:02:11** (через 900 секунд от момента создания), ещё не выполнялось.

- [OK]   агент проверил планировщик: scheduler__list_jobs
- [OK]   в этом ходе вызывались только инструменты scheduler
- [OK]   ответ говорит о напоминании review-status-report
- [OK]   проверка напрямую: задание review-status-report активно

### Ход 3

**Пользователь:** Какой файл сильнее всего изменился в том коммите? После этого отмени напоминание, которое ты создал.

Вызовы: `git__git_commit_details` → `scheduler__cancel_job`

**Агент:**

> Готово:
> 
> 1. **Сильнее всего изменился файл:** `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task20.java` — новый файл, **+409 строк** (всего в коммите 7 файлов, +709 −40). На втором месте `McpRegistry.java` (+155).
> 2. **Напоминание отменено:** задание `review-status-report` остановлено (не успело ни разу выполниться, данные сохранены).

- [OK]   порядок: git_commit_details → cancel_job; фактически [git__git_commit_details, scheduler__cancel_job]
- [OK]   хеш «того коммита» взят из хода 1 (в сообщении его нет): sha=e544d1c, из git_log: e544d1c
- [OK]   имя «созданного напоминания» взято из хода 1: cancel_job(name=review-status-report)
- [OK]   проверка напрямую: задание review-status-report остановлено
- [OK]   ответ называет самый изменённый файл по данным git: src/main/java/ru/lemanapro/aiadventchallenge/week4/Task20.java

- [OK]   во флоу задействованы все три сервера, ошибок маршрутизации нет: [git, docs, scheduler], вызовов MCP: 8

**Итог:** выбор инструмента 6/6; длинный флоу — ходов без замечаний 3/3, вызовов MCP 8.
