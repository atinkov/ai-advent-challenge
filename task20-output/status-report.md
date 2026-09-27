# Выжимка: оркестрация MCP

_Пайплайн search_docs → summarize → save_to_file, 2026-09-28 01:47:09 · summary_id m4 · source_id s3 · метод llm_

Источники: `AGENTS.md`, `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task17.java`, `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task20.java`, `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task19.java`, `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task18.java`

- Task20 реализует оркестрацию нескольких MCP-серверов: класс McpRegistry регистрирует три независимых MCP-сервера, каждый из которых запускается как отдельный дочерний процесс и построен на задачах предыдущих дней (Task20.java).
- Среди оркестрируемых серверов — GitMcpServer (инструменты git_log / git_commit_details / git_branches) и scheduler MCP server с фоновыми задачами (git_snapshot, reminders) и агрегацией get_summary (AGENTS.md).
- Подключение к MCP-серверам выполняется через официальный MCP Java SDK: McpSyncClient поверх stdio с handshake initialize, ping и tools/list с пагинацией (AGENTS.md).
- В Task20.java для работы со схемой MCP импортируется McpSchema из io.modelcontextprotocol.spec (Task20.java).
- В Task17, Task18 и Task19 для взаимодействия с MCP-серверами используются McpClient, McpSyncClient и McpJsonDefaults из пакета io.modelcontextprotocol (Task17.java, Task18.java, Task19.java).
- McpToolAgent позволяет LLM вызывать инструменты MCP-сервера через tools/call (AGENTS.md).
