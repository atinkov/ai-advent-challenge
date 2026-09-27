# День 19. Композиция MCP-инструментов

_Сгенерировано: 2026-09-28 01:34:47_ — `mvn -q compile exec:java -Ptask19`

MCP-сервер: docs-pipeline-mcp-server 1.0.0 (`Task19.DocsPipelineMcpServer`), модель: qwen3.8-27b, выжимка: llm

```
search_docs ──result_id──▶ summarize ──summary_id──▶ save_to_file ──▶ task19-output/*.md
   text_sha256 ═════ input_sha256   output_sha256 ═════ input_sha256   file_sha256 ═ файл на диске
```

## 1. Пайплайн «Инварианты и ограничения»

| Шаг | Инструмент | Аргументы (после подстановки) | Результат | мс |
|---|---|---|---|---|
| search | `search_docs` | `(query="инварианты", limit=5)` | result_id=s1, файлов 5 | 108 |
| sum | `summarize` | `(max_points=6, source_id="s1")` | summary_id=m2, llm | 4888 |
| save | `save_to_file` | `(summary_id="m2", filename="pipeline-1-summary.md")` | `task19-output/pipeline-1-summary.md`, 1751 байт | 8 |

- [OK]   все 3 шага выполнены автоматически, без ошибок
- [OK]   search_docs нашёл данные: 5 файл(ов)
- [OK]   summarize получил именно результат поиска: source_id s1 = result_id s1
- [OK]   summarize обработал ровно найденный текст: input_sha256 = text_sha256 поиска (8f069d6b3c0b…)
- [OK]   save_to_file получил именно эту выжимку: summary_id m2
- [OK]   save_to_file записал ровно выжимку: input_sha256 = output_sha256 summarize (4a093ed39c81…)
- [OK]   файл task19-output/pipeline-1-summary.md на диске совпадает (file_sha256) и содержит выжимку целиком

<details><summary>Выжимка</summary>

- Инвариант — принятое решение или ограничение проекта, которое ассистент не имеет права нарушать ни при каком запросе (task14-invariants-report.md)
- Инварианты хранятся в отдельном файле `task14-invariants.json`, отдельно от диалога (task14-invariants-report.md)
- `InvariantStore` (вложенный в `Task14.java`) хранит фиксированный список `Invariant(id, category, statement)` с категориями ARCHITECTURE / TECH_DECISION / STACK_CONSTRAINT (AGENTS.md)
- Ассистент обязан начинать ответ строкой «Проверка инвариантов: …» с перечислением id релевантных инвариантов (src/main/java/ru/lemanapro/aiadventchallenge/week3/Task14.java)
- Задача 14 «Инварианты и ограничения состояния» завершена в коммите 9cb24ab, 2026-09-20, автор atinkov (task17-mcp-agent-report.md)
- В Task19.java определён пайплайн «Инварианты и ограничения» с запросом по умолчанию «инварианты» (src/main/java/ru/lemanapro/aiadventchallenge/week4/Task19.java)

</details>

## 2. Пайплайн «MCP в проекте»

| Шаг | Инструмент | Аргументы (после подстановки) | Результат | мс |
|---|---|---|---|---|
| find | `search_docs` | `(query="MCP сервер инструменты", limit=4)` | result_id=s3, файлов 4 | 52 |
| digest | `summarize` | `(max_points=5, source_id="s3", focus="MCP")` | summary_id=m4, llm | 4003 |
| store | `save_to_file` | `(summary_id="m4", filename="pipeline-2-mcp.md")` | `task19-output/pipeline-2-mcp.md`, 1481 байт | 2 |

- [OK]   все 3 шага выполнены автоматически, без ошибок
- [OK]   search_docs нашёл данные: 4 файл(ов)
- [OK]   summarize получил именно результат поиска: source_id s3 = result_id s3
- [OK]   summarize обработал ровно найденный текст: input_sha256 = text_sha256 поиска (cc0d6412b812…)
- [OK]   save_to_file получил именно эту выжимку: summary_id m4
- [OK]   save_to_file записал ровно выжимку: input_sha256 = output_sha256 summarize (137bab29ab9c…)
- [OK]   файл task19-output/pipeline-2-mcp.md на диске совпадает (file_sha256) и содержит выжимку целиком

<details><summary>Выжимка</summary>

- Task16 реализует MCP-подключение через официальный MCP Java SDK-клиент по stdio: initialize handshake, ping, tools/list с пагинацией, self-checks, вывод списка инструментов (AGENTS.md)
- Task17 создаёт собственный MCP-сервер поверх Git API (GitMcpServer: git_log / git_commit_details / git_branches) и McpToolAgent, позволяющий LLM вызывать инструменты через MCP tools/call (AGENTS.md)
- Task18 реализует MCP-сервер-планировщик с фоновыми задачами (git_snapshot, reminders), персистентностью в JSON и агрегацией get_summary; 24/7-агент печатает периодические LLM-сводки (AGENTS.md)
- Task17, Task18 и Task19 импортируют классы MCP Java SDK-клиента: McpClient, McpSyncClient, McpJsonDefaults (src/main/java/ru/lemanapro/aiadventchallenge/week4/Task17.java, Task18.java, Task19.java)

</details>

## 3. Контроль: сломанная передача данных

Шаг 2 получил несуществующий `source_id` (s999) → Ошибка: неизвестный source_id s999 — сначала вызовите search_docs

- [OK]   пайплайн остановился на шаге 2, файл must-not-exist.md не создан

## 4. Агент сам составляет цепочку

**Цель:** Найди в документации проекта всё про сжатие истории диалога (compression), сделай краткую выжимку и сохрани её в файл agent-compression-summary.md.

- `search_docs {"query": "сжатие истории диалога compression", "limit": 5}` → ok
- `summarize {"source_id": "s6", "focus": "Механизм сжатия истории диалога: как работает, параметры, хранение, демо", "max_points": 8}` → ok
- `save_to_file {"filename": "agent-compression-summary.md", "summary_id": "m7"}` → ok

**Ответ агента:**

> Готово. Что найдено:
> 
> - **Механизм**: сжатие — это rolling summary. Запрос модели собирается как `[system, пересказ (сообщением от "user" с заголовком «Пересказ предыдущего диалога: …»), последние 6 сообщений]` — старая история в запрос не уходит.
> - **Хранение**: summary персистится в `agent-context.json` v3 вместе с token stats.
> - **Параметры**: `Settings(Strategy, int window, boolean compressionEnabled, …)`; работает только для стратегии FULL; окно задаётся через `AGENT_KEEP_RECENT`.
> - **Демо**: `Task9.java` прогоняет один 14-ходовый диалог дважды (без сжатия / со сжатием) и строит таблицу сравнения; отчёт — `task9-compression-report.md`.
> - **Оценка токенов**: эвристика «символы / 2.5» (`LlmClient.estimateTokens`), отклонение ≈ 5 %.
> 
> Файл сохранён: `task19-output/agent-compression-summary.md` (2167 байт, sha256 `e0111817…`).

- [OK]   агент выполнил цепочку по порядку: [search_docs, summarize, save_to_file]
- [OK]   summarize получил result_id поиска: source_id=s6, result_id=s6
- [OK]   save_to_file получил summary_id выжимки: m7
- [OK]   в файл записана ровно выжимка (sha256 совпадают)
- [OK]   файл существует: task19-output/agent-compression-summary.md

**Итог:** пайплайны 2/2, контроль сломанной цепочки — OK, агент — OK.
