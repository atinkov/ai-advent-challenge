# Выжимка: MCP сервер инструменты

_Пайплайн search_docs → summarize → save_to_file, 2026-09-28 01:34:38 · summary_id m4 · source_id s3 · метод llm_

Источники: `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task17.java`, `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task19.java`, `AGENTS.md`, `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task18.java`

- Task16 реализует MCP-подключение через официальный MCP Java SDK-клиент по stdio: initialize handshake, ping, tools/list с пагинацией, self-checks, вывод списка инструментов (AGENTS.md)
- Task17 создаёт собственный MCP-сервер поверх Git API (GitMcpServer: git_log / git_commit_details / git_branches) и McpToolAgent, позволяющий LLM вызывать инструменты через MCP tools/call (AGENTS.md)
- Task18 реализует MCP-сервер-планировщик с фоновыми задачами (git_snapshot, reminders), персистентностью в JSON и агрегацией get_summary; 24/7-агент печатает периодические LLM-сводки (AGENTS.md)
- Task17, Task18 и Task19 импортируют классы MCP Java SDK-клиента: McpClient, McpSyncClient, McpJsonDefaults (src/main/java/ru/lemanapro/aiadventchallenge/week4/Task17.java, Task18.java, Task19.java)
