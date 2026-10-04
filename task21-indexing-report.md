# День 21. Индексация документов

_Сгенерировано: 2026-10-04 23:33:00_ — `mvn -q compile exec:java -Ptask21`, эмбеддер `api:qwen3-vl-embedding-8b` (размерность 4096)

```
документы (*.md, *.java) ─▶ chunking (2 стратегии) ─▶ эмбеддинги ─▶ JSON-индекс (чанк + метаданные + вектор)
                               fixed / structure         api:qwen3-vl-embedding…        task21-index-<стратегия>.json
```

## База документов

База знаний — сам проект: `AGENTS.md`, отчёты дней и исходный код. Корень: `AIAdventChallenge/`.

| Тип | Файлов | Знаков | ≈ страниц (1800 зн.) |
|---|---|---|---|
| Markdown (README-подобные документы, отчёты) | 15 | 205921 | 114 |
| Java (код) | 25 | 401588 | 223 |
| **Всего** | **40** | **607509** | **337** |

Требование «минимум 20–30 страниц» выполнено с запасом. Не индексируются: `target/`, служебные каталоги, сгенерированные `task19-output/`, `task20-output/` и сам пакет `week5/` с отчётами дней 21–25 — в них лежат контрольные вопросы вместе с ответами, их индексация была бы утечкой ответов.

<details><summary>Список документов</summary>

| Источник (source) | Заголовок (title) | Знаков |
|---|---|---|
| `AGENTS.md` | PROJECT KNOWLEDGE BASE | 46841 |
| `src/main/java/ru/lemanapro/aiadventchallenge/LlmAgent.java` | LlmAgent.java | 25330 |
| `src/main/java/ru/lemanapro/aiadventchallenge/LlmClient.java` | LlmClient.java | 15165 |
| `src/main/java/ru/lemanapro/aiadventchallenge/Main.java` | Main.java | 109 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task1.java` | Task1.java | 1271 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task2.java` | Task2.java | 3378 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task3.java` | Task3.java | 6471 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task4.java` | Task4.java | 4825 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task5.java` | Task5.java | 9888 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week2/Task10.java` | Task10.java | 19592 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week2/Task6.java` | Task6.java | 4879 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week2/Task8.java` | Task8.java | 70 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week2/Task9.java` | Task9.java | 9567 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week3/Task11.java` | Task11.java | 25444 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week3/Task12.java` | Task12.java | 20139 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week3/Task13.java` | Task13.java | 22256 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week3/Task14.java` | Task14.java | 20819 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week3/Task15.java` | Task15.java | 25452 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week4/McpLaunch.java` | McpLaunch.java | 2361 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week4/McpRegistry.java` | McpRegistry.java | 6785 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week4/McpToolAgent.java` | McpToolAgent.java | 13564 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task16.java` | Task16.java | 17180 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task17.java` | Task17.java | 30225 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task18.java` | Task18.java | 43984 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task19.java` | Task19.java | 45633 |
| `src/main/java/ru/lemanapro/aiadventchallenge/week4/Task20.java` | Task20.java | 27201 |
| `task10-strategy-report.md` | День 10 — Управление контекстом: сравнение стратегий | 22697 |
| `task11-memory-report.md` | День 11 — Модель памяти агента (short-term / working / long-term) | 10744 |
| `task12-personalization-report.md` | День 12 — Персонализация ассистента поверх модели памяти | 51494 |
| `task13-state-machine-report.md` | День 13 — Состояние задачи как конечный автомат | 7094 |
| `task14-invariants-report.md` | День 14 — Инварианты и ограничения состояния | 21736 |
| `task15-lifecycle-report.md` | День 15 — Контролируемые переходы состояний | 6495 |
| `task16-mcp-report.md` | День 16. Подключение MCP | 2813 |
| `task17-mcp-agent-report.md` | День 17. Свой MCP-сервер вокруг Git API + агент | 5625 |
| `task18-summaries.md` | День 18. Сводки фонового агента | 1863 |
| `task19-pipeline-report.md` | День 19. Композиция MCP-инструментов | 6277 |
| `task20-orchestration-report.md` | День 20. Оркестрация нескольких MCP-серверов | 7015 |
| `task5-model-comparison.md` | День 5: Версии моделей — сравнение | 2936 |
| `task8-token-report.md` | День 8: Работа с токенами — результаты и выводы | 5327 |
| `task9-compression-report.md` | День 9: Управление контекстом — сжатие истории | 6964 |

</details>

## Две стратегии chunking

- **fixed** (`size=1000, overlap=150`) — скользящее окно фиксированного размера с перекрытием; о документе ничего не знает, только сдвигает разрез к ближайшему пробелу, чтобы не рвать слово. Эмбеддится «как есть».
- **structure** (`maxChars=1500`) — по структуре документа: Markdown — раздел под заголовком; раздел больше лимита делится по своим блокам (строки таблицы, пункты списка, абзацы, блоки кода), слишком длинный блок — по предложениям. Java — член класса (метод, группа полей, вложенный тип), границы ищет маленький лексер по глубине скобок вне строк и комментариев. Эмбеддится вместе с «хлебными крошками» `title › section` (и шапкой таблицы, если чанк — её строки).

Текст любого чанка — **дословный срез** исходного документа (хранятся строки начала/конца), поэтому цитата из чанка всегда является цитатой из источника.

## Метаданные чанка

| Поле | Что это |
|---|---|
| `chunkId` | идентификатор в индексе: `f-0001…` (fixed), `s-0001…` (structure) |
| `source` | путь к файлу относительно корня базы |
| `title` | заголовок документа (H1 для Markdown, имя файла для кода) |
| `section` | раздел: путь заголовков `A › B`, для строки таблицы — её первый столбец; для кода — `Класс.метод()` |
| `strategy`, `ordinal` | стратегия и порядковый номер чанка внутри документа |
| `startLine`, `endLine` | диапазон строк в исходном файле |
| `lead` | контекст, которого нет в срезе (шапка разрезанной таблицы) |
| `text`, `vector` | текст и нормированный эмбеддинг |

Одно и то же место базы (`task9-compression-report.md`, строка 7) в двух индексах:

```json
{"chunkId": "f-0721", "source": "task9-compression-report.md", "title": "День 9: Управление контекстом — сжатие истории",
 "section": "День 9: Управление контекстом — сжатие истории", "strategy": "fixed", "ordinal": 1, "startLine": 1, "endLine": 13,
 "lead": "",
 "text": "# День 9: Управление контекстом — сжатие истории **Дата:** 2026-09-13 **Модель:** qwen3.8-27b @ https://gpustack.data.lmru.tech/v1 **Настройки:** окно…",
 "vector": [0.03401, -0.01113, 0.01757, … 4096 чисел]}
```

```json
{"chunkId": "s-0657", "source": "task9-compression-report.md", "title": "День 9: Управление контекстом — сжатие истории",
 "section": "Как работает сжатие", "strategy": "structure", "ordinal": 2, "startLine": 7, "endLine": 15,
 "lead": "",
 "text": "## Как работает сжатие - Последние `AGENT_KEEP_RECENT` (6) сообщений отправляются как есть. - Как только сообщений вне пересказа накопилось `AGENT_SUM…",
 "vector": [0.04088, -0.00906, 0.01510, … 4096 чисел]}
```

## Сравнение стратегий

| Метрика | fixed | structure |
|---|---|---|
| Чанков | 729 | 664 |
| Длина чанка: средняя / медиана | 969 / 997 | 915 / 916 |
| Длина чанка: мин / 95-й перцентиль / макс | 70 / 1000 / 1000 | 70 / 1497 / 1678 |
| Коротких чанков (< 200 знаков) | 5 | 2 |
| Суммарный текст в индексе, знаков | 706401 | 607509 |
| Дублирование текста относительно базы | +16,3 % | +0,0 % |
| Чанк начинается посреди строки | 577 из 729 (79 %) | 23 из 664 (3 %) |
| Чанк обрывается посреди строки | 676 из 729 (93 %) | 23 из 664 (3 %) |
| Единицы структуры (раздел / член класса), целиком попавшие в один чанк ¹ | 507 из 604 (84 %) | 604 из 604 (100 %) |
| Чанков с заполненным `section` | 729 из 729 (100 %) | 664 из 664 (100 %) |
| Разных значений `section` | 364 | 663 |
| Время эмбеддинга, с | 39,1 | 36,0 |
| Файл индекса | `task21-index-fixed.json` — 26,0 МБ | `task21-index-structure.json` — 23,6 МБ |

¹ Считаются только единицы, которые помещаются в чанк любой из стратегий (≤ 1000 знаков): раздел Markdown, метод/группа полей Java. «Целиком» — существует чанк, содержащий единицу полностью.

## Поисковая проверка

10 контрольных вопросов (`ControlSet`), чистый векторный поиск по сохранённому и заново прочитанному с диска индексу, без LLM. Ранг — позиция первого **релевантного** чанка в top-10 (чанк из ожидаемого источника, содержащий искомый факт).

| # | Вопрос | fixed: ранг | fixed: top-1 | structure: ранг | structure: top-1 |
|---|---|---|---|---|---|
| Q1 | Сколько последних сообщений диалога LlmAgent отправляет модели без сжатия и сколько сообще… | 1 | 0.84 `task9-compression-report.md › День 9: Управление контекстом …` | 1 | 0.85 `task9-compression-report.md › Как работает сжатие` |
| Q2 | Почему в pom.xml проекта версия jackson-annotations зафиксирована на 2.21? | — | 0.51 `AGENTS.md › STRUCTURE` | 4 | 0.51 `AGENTS.md › CONVENTIONS (часть 1/2)` |
| Q3 | Какие инструменты публикует GitMcpServer и можно ли через них что-то изменить в репозитори… | 1 | 0.73 `Task17.java › package / imports` | 5 | 0.75 `Task17.java › Task17.GitMcpServer (объявление)` |
| Q4 | По какому правилу McpRegistry формирует имена инструментов в общем каталоге и почему выбра… | 2 | 0.65 `AGENTS.md › WHERE TO LOOK` | 1 | 0.70 `McpRegistry.java › McpRegistry (объявление)` |
| Q5 | Через какие состояния проходит задача в жизненном цикле дня 15 и можно ли из IMPLEMENTATIO… | 1 | 0.76 `task15-lifecycle-report.md › Ход выполнения` | 1 | 0.76 `task15-lifecycle-report.md › Ход выполнения (часть 2/2)` |
| Q6 | Как планировщик дня 18 после перезапуска восстанавливает задания и почему задание не выпол… | 3 | 0.67 `task15-lifecycle-report.md › Выводы` | 1 | 0.69 `AGENTS.md › WHERE TO LOOK › Day 18 (scheduler + background j…` |
| Q7 | Какие три слоя памяти выделены в модели памяти дня 11, какой из них сохраняется между сесс… | 1 | 0.77 `task11-memory-report.md › День 11 — Модель памяти агента (sh…` | 1 | 0.80 `task11-memory-report.md › День 11 — Модель памяти агента (sh…` |
| Q8 | Что показал эксперимент дня 9: на сколько процентов сжатие истории сократило prompt-токены… | 1 | 0.80 `task9-compression-report.md › Сценарий: 14 ходов, 5 фактов о…` | 1 | 0.86 `task9-compression-report.md › Сценарий: 14 ходов, 5 фактов о…` |
| Q9 | Сколько шагов вызова инструментов максимум делает McpToolAgent на один вопрос и как он раб… | 1 | 0.83 `AGENTS.md › WHERE TO LOOK` | 1 | 0.82 `McpToolAgent.java › McpToolAgent: ToolCallTrace, AgentAnswer…` |
| Q10 | Как в пайплайне дня 19 проверяется, что данные между шагами search_docs → summarize → save… | 1 | 0.77 `task19-pipeline-report.md › 4. Агент сам составляет цепочку` | 1 | 0.78 `Task19.java › Task19.verifyHandoffs() (часть 1/2)` |

| Стратегия | Hit@1 | Hit@3 | Hit@5 | MRR |
|---|---|---|---|---|
| fixed | 70 % | 90 % | 90 % | 0,78 |
| structure | 80 % | 80 % | 100 % | 0,85 |

## Выводы

1. **Индекс построен и работает локально.** 40 документов, 607509 знаков (≈ 337 страниц) → 729 чанков (fixed) и 664 чанков (structure); каждый чанк хранится в JSON с метаданными и вектором, поиск — косинус по нормированным векторам.
2. **fixed предсказуем по размеру, но слеп к содержанию.** Почти все чанки одной длины (медиана 997), зато 577 из 729 (79 %) чанков начинаются посреди строки, а целиком в один чанк попало 507 из 604 (84 %) разделов и методов, которые туда помещались бы. Перекрытие частично это лечит ценой +16,3 % дублированного текста.
3. **structure сохраняет смысловые единицы.** Целыми остались 604 из 604 (100 %) единиц, посреди строки начинаются 23 из 664 (3 %) чанков (только части слишком длинных строк таблиц, разрезанные по предложениям). Плата — разброс длины (70–1678 знаков) и более сложный код разбиения под каждый тип документа.
4. **Метаданные осмысленны только у structure.** У неё `section` — это 663 точных адресов вида `AGENTS.md › WHERE TO LOOK › Day 18` или `McpRegistry.route()`; у fixed раздел — лишь тот, в котором чанк начался (границы окна с разделами не совпадают). Для ответов со ссылками на источники (день 24) это принципиально.
5. **Поиск.** На 10 контрольных вопросах structure находит нужный чанк раньше: fixed — Hit@1 70 %, Hit@5 90 %, MRR 0,78; structure — Hit@1 80 %, Hit@5 100 %, MRR 0,85. Вне top-10 остались: Q2 (fixed) — материал для реранкинга и переформулировки запроса (день 23).
6. **Дальше** (дни 22–25) по умолчанию используется индекс `structure` (`RAG_STRATEGY=fixed` переключает на fixed).
