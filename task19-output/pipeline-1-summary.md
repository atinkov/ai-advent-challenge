# Выжимка: инварианты

_Пайплайн search_docs → summarize → save_to_file, 2026-09-28 01:34:34 · summary_id m2 · source_id s1 · метод llm_

Источники: `task14-invariants-report.md`, `src/main/java/ru/lemanapro/aiadventchallenge/week3/Task14.java`, `task17-mcp-agent-report.md`, `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task19.java`, `AGENTS.md`

- Инвариант — принятое решение или ограничение проекта, которое ассистент не имеет права нарушать ни при каком запросе (task14-invariants-report.md)
- Инварианты хранятся в отдельном файле `task14-invariants.json`, отдельно от диалога (task14-invariants-report.md)
- `InvariantStore` (вложенный в `Task14.java`) хранит фиксированный список `Invariant(id, category, statement)` с категориями ARCHITECTURE / TECH_DECISION / STACK_CONSTRAINT (AGENTS.md)
- Ассистент обязан начинать ответ строкой «Проверка инвариантов: …» с перечислением id релевантных инвариантов (src/main/java/ru/lemanapro/aiadventchallenge/week3/Task14.java)
- Задача 14 «Инварианты и ограничения состояния» завершена в коммите 9cb24ab, 2026-09-20, автор atinkov (task17-mcp-agent-report.md)
- В Task19.java определён пайплайн «Инварианты и ограничения» с запросом по умолчанию «инварианты» (src/main/java/ru/lemanapro/aiadventchallenge/week4/Task19.java)
