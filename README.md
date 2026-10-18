# AIAdventChallenge

Учебный проект «AI Advent Challenge»: по одному LLM-демо на каждый день челленджа. Maven, Java 25, CLI-приложение
(`ru.lemanapro:AIAdventChallenge:1.0-SNAPSHOT`). Каждый день — отдельный класс `weekN/TaskN.java` и свой Maven-профиль `-PtaskN`.

Темы по неделям:

| Неделя | Дни | Тема | Пакет |
|---|---|---|---|
| 1 | 1–5 | первый вызов LLM, управление ответом, способы рассуждения, температура, сравнение моделей | `week1` |
| 2 | 6–10 | агент с памятью диалога, токены, сжатие истории, стратегии контекста, ветки диалога | `week2` |
| 3 | 11–15 | модель памяти, персонализация, состояние задачи (FSM), инварианты, жизненный цикл задачи | `week3` |
| 4 | 16–20 | MCP: подключение, свой Git MCP-сервер, планировщик, композиция инструментов, оркестрация нескольких серверов | `week4` |
| 5 | 21–25 | RAG: индексация, первый RAG-запрос, реранкинг, цитаты, чат с RAG и памятью задачи | `week5` |
| 6 | 26–30 | локальная LLM (Ollama + Qwen): приложение, локальный RAG, оптимизация, приватный AI-сервис | `week6` |
| 7 | 31– | ассистент разработчика: RAG по документации + MCP к репозиторию, команда `/help`; автоматическое AI-ревью PR (GitHub Action); ассистент поддержки пользователей (CRM через MCP + RAG по FAQ) | `week7` |

## Быстрый старт

Требования: JDK 25 (`java -version`), Maven 3.9+ (`mvn -v`), git. Для дней 26–30 — запущенная Ollama.

1. Создайте в корне файл `.env` (он в `.gitignore`, ключи в код и в git не попадают):

   ```bash
   LLM_API_KEY=ваш-ключ
   HINDSIGHT_API_LLM_PROVIDER=openai
   HINDSIGHT_API_LLM_BASE_URL=https://gpustack.data.lmru.tech/v1
   HINDSIGHT_API_LLM_MODEL=qwen3.8-27b
   ```

   Подойдёт любой OpenAI-совместимый сервер, например локальная Ollama:
   `HINDSIGHT_API_LLM_BASE_URL=http://localhost:11434/v1 HINDSIGHT_API_LLM_MODEL=qwen3:8b`.

2. Соберите и запустите нужный день:

   ```bash
   mvn compile
   mvn -q compile exec:java -Ptask1                    # день 1
   mvn -q exec:java -Ptask6 -Dexec.args="Ваш вопрос"  # свой вопрос/аргументы для дня
   ```

   `exec:java` без `compile` **не перекомпилирует** код — после правок запускайте `mvn -q compile exec:java -PtaskN`.
   Профиль по умолчанию указывает на пустой `Main` — всегда передавайте `-PtaskN`.

3. Если у LLM-шлюза сломан TLS-сертификат: `LLM_INSECURE_TLS=1 mvn -q compile exec:java -PtaskN`.

## Ассистент разработчика (день 31)

CLI, который знает этот проект: отвечает на вопросы по документации (RAG по `README.md`, `docs/`, `AGENTS.md`)
и по живому состоянию репозитория (MCP-сервер проекта: текущая ветка, статус, файлы, diff).

```bash
mvn -q compile exec:java -Ptask31                                       # автопроверка задания по условию → task31-dev-assistant-report.md
mvn -q exec:java -Ptask31 -Dexec.args="chat"                           # интерактивный режим
mvn -q exec:java -Ptask31 -Dexec.args="/help как добавить новый день?"  # одна команда
```

Команды: `/help`, `/help <вопрос>` (или просто вопрос), `/branch`, `/status`, `/files [маска|папка]`, `/diff [путь]`,
`/docs`, `/reindex`, `/tools`, `/exit`. Подробно — [docs/dev-assistant.md](docs/dev-assistant.md).

## AI-ревью pull request (день 32)

На каждый PR GitHub Action (`.github/workflows/ai-review.yml`) запускает `Task32 review`: diff и изменённые файлы PR +
RAG по документации и коду проекта → LLM → комментарий в PR с разделами «Потенциальные баги», «Архитектурные проблемы»,
«Рекомендации». Для работы в репозитории нужен секрет `LLM_API_KEY`.

```bash
mvn -q compile exec:java -Ptask32                                        # проверка задания → task32-code-review-report.md
mvn -q exec:java -Ptask32 -Dexec.args="review --base main --head HEAD"  # ревью своей ветки локально
```

Подробно — [docs/code-review.md](docs/code-review.md).

## Ассистент поддержки пользователей (день 33)

Веб-сервис поддержки вымышленного продукта TaskFlow: вопрос клиента + тикет → данные тикета из CRM через MCP
(`support/crm.json`) + RAG по FAQ и документации (`support/kb/`) → ответ с учётом ситуации клиента.

```bash
mvn -q compile exec:java -Ptask33                  # веб-интерфейс http://localhost:8033
mvn -q exec:java -Ptask33 -Dexec.args="check"      # проверка задания → task33-support-report.md
```

Подробно — [docs/support-assistant.md](docs/support-assistant.md).

## Структура

```
AIAdventChallenge/
├── .github/workflows/       ai-review.yml — AI-ревью на каждый pull request (день 32)
├── pom.xml                  Java 25; jackson-databind, MCP Java SDK 2.0.1, slf4j-nop; профили task1…task33
├── .env                     конфигурация LLM и ключ (gitignored)
├── README.md                этот файл
├── AGENTS.md                подробная база знаний проекта: каждый день, соглашения, команды
├── docs/                    документация для людей и для RAG ассистента
│   ├── architecture.md      слои, общие компоненты, пакеты, поток данных
│   ├── dev-assistant.md     ассистент разработчика (день 31)
│   ├── code-review.md       автоматическое AI-ревью PR (день 32)
│   ├── support-assistant.md ассистент поддержки пользователей (день 33)
│   ├── data-schemas.md      какие JSON-файлы пишет проект и их форматы
│   ├── schemas/             JSON Schema файлов данных
│   └── api/                 HTTP API (OpenAPI) и каталог MCP-инструментов
├── src/main/java/ru/lemanapro/aiadventchallenge/
│   ├── LlmClient.java       общий транспорт: конфиг из env/.env, chat/send/sendWithTools/embed
│   ├── LlmAgent.java        общий агент: персона, история, сжатие, ветки диалога
│   ├── Main.java            намеренно пустая заглушка
│   └── week1 … week7/       классы дней и общие компоненты недели
├── src/main/resources/task27/  веб-интерфейс дня 27
├── src/main/resources/task33/  страница ассистента поддержки (день 33)
├── support/                 день 33: crm.json (пользователи и тикеты) + kb/ (FAQ и документация TaskFlow)
└── taskN-*-report.md        отчёты, которые генерирует каждый день
```

## Соглашения

- Сборка — Maven (не Gradle), без wrapper `mvnw`. HTTP — только `java.net.http.HttpClient`, JSON — Jackson.
- Новый день = новый `weekN/TaskN.java` + профиль `<profile id="taskN">` в `pom.xml`, переопределяющий `mainClass` у `exec-maven-plugin`.
- Классы дней тонкие: UI (`TaskN`) → агент → транспорт (`LlmClient`). Логику LLM/env/JSON не копировать в Task-классы.
- Ключи — только в env или в `.env`. Не коммитить `.idea/`, `.omo/`, `.env`, индексы и файлы состояния (`*.json` из `.gitignore`).
- Git: ветки по дням `weekN/taskN` (недели 3–4) и по неделям `week5`, `week6`, … (с 5-й недели); сообщение коммита — `Completed taskN - <тема>`.

## Где искать подробности

- [AGENTS.md](AGENTS.md) — таблица «WHERE TO LOOK» по каждому дню, все команды запуска, переменные окружения.
- [docs/architecture.md](docs/architecture.md) — как устроен код.
- [docs/data-schemas.md](docs/data-schemas.md) и [docs/schemas/](docs/schemas/) — форматы файлов данных.
- [docs/api/](docs/api/) — HTTP API дней 27 и 30, каталог MCP-серверов и инструментов.
