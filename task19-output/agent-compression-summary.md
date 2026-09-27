# Выжимка: сжатие истории диалога compression

_Пайплайн search_docs → summarize → save_to_file, 2026-09-28 01:34:46 · summary_id m7 · source_id s6 · метод llm_

Источники: `task9-compression-report.md`, `AGENTS.md`, `task8-token-report.md`, `src/main/java/ru/lemanapro/aiadventchallenge/week2/Task9.java`, `src/main/java/ru/lemanapro/aiadventchallenge/LlmAgent.java`

- Запрос модели собирается как `[system, пересказ (сообщением от "user" с заголовком «Пересказ предыдущего диалога: …»), последние 6 сообщений]`; вся старая история в запрос не уходит (task9-compression-report.md).
- Сжатие — rolling summary, персистится в `agent-context.json` v3 вместе с summary и token stats (AGENTS.md).
- Демо (Task9.java): один и тот же 14-ходовый сценарный диалог прогоняется дважды — «БЕЗ сжатия» (baseline) и «СО сжатием» (rolling summary on, window/batch из `AGENT_KEEP_RECENT`), результат — таблица сравнения (AGENTS.md, Task9.java).
- Сжатие истории работает только для стратегии FULL (LlmAgent.java).
- Параметры сжатия задаются в `Settings(Strategy strategy, int window, boolean compressionEnabled, …)` (LlmAgent.java).
- Токены сохранённой истории оцениваются эвристикой «символы / 2.5» (`LlmClient.estimateTokens`); отклонение от точного значения ≈ 5 % (1081 против 1140) (task8-token-report.md).
- Пример статистики сессии: запрос = 1140, ответ = 475, история ≈ 1081, сессия = 12 174 токенов (task8-token-report.md).
- Отчёт по сжатию (task9-compression-report.md) содержит live-сравнение сжатия on/off и обновляется при повторном тестировании (AGENTS.md).
