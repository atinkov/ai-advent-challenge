# Автоматическое AI-ревью PR (день 32)

На каждый pull request GitHub Action запускает ревьюера проекта. Он берёт diff и изменённые файлы PR, подтягивает через RAG
документацию и код проекта и публикует в PR комментарий «🤖 AI-ревью PR» с тремя разделами: потенциальные баги,
архитектурные проблемы, рекомендации.

## Пайплайн

Файл `.github/workflows/ai-review.yml`:

| Шаг | Что делает |
|---|---|
| триггер | `pull_request`: `opened`, `synchronize`, `reopened`, `ready_for_review`; черновики и PR из форков пропускаются (форкам не выдаются секреты) |
| checkout | `fetch-depth: 0` — вся история, нужна для `git merge-base` и `diff base...head` |
| JDK 25 | `actions/setup-java` (corretto 25, кэш Maven) |
| AI review | `mvn -q -B compile exec:java -Ptask32 -Dexec.args="review --base <base.sha> --head <head.sha> --out target/ai-review.md"` |
| публикация | `marocchino/sticky-pull-request-comment` — один комментарий на PR, при новых коммитах он обновляется |

Настройки репозитория (Settings → Secrets and variables → Actions):

| Где | Имя | Обязательно | Смысл |
|---|---|---|---|
| Secrets | `LLM_API_KEY` | да | ключ OpenAI-совместимого LLM-сервера |
| Secrets | `LLM_BASE_URL` | нет | адрес сервера, по умолчанию как в `LlmClient` |
| Secrets | `HINDSIGHT_API_LLM_MODEL` | нет | модель, по умолчанию как в `LlmClient` |
| Variables | `AI_REVIEW_RUNNER` | нет | раннер, например `self-hosted`, если LLM-шлюз доступен только из внутренней сети. Только Variable: в `runs-on` секреты недоступны |

Права job: `contents: read`, `pull-requests: write`.

## Как ревьюер работает (`week7/CodeReviewer`)

1. **Вход PR** — `git diff base...head` (три точки: только то, что PR добавляет к merge base), `--numstat` и
   `--name-status` (файлы, +/−, добавлен/изменён/удалён/переименован), номера добавленных строк из hunk-заголовков,
   новые версии файлов `git show head:<путь>` (до `TASK32_MAX_FILE_CHARS`). Diff обрезается до `TASK32_MAX_DIFF_CHARS`.
2. **RAG** — индекс `task32-review-index.json` по `README.md`, `docs/`, `AGENTS.md` и `src/main/java` (Java нарезается
   по членам классов). Запросы: один про соглашения проекта + по одному на каждый изменённый файл (путь + идентификаторы
   из добавленных строк). Каждый запрос ищется отдельно в документации и отдельно в коде, затем лучшие фрагменты
   объединяются (до `TASK32_RAG_FRAGMENTS`, половина — документация). Фрагменты самих изменённых файлов и исходники
   ревьюера (`TASK32_RAG_EXCLUDE`) в контекст не попадают.
3. **LLM** — один запрос, ответ строго JSON: `summary`, `verdict` (`approve` / `comment` / `request_changes`),
   `bugs[]`, `architecture[]`, `recommendations[]`; у замечания `severity`, `file`, `line`, `title`, `details`,
   `suggestion`, `refs` (номера фрагментов контекста). Если JSON не разобрался — одна повторная попытка.
4. **Проверка в коде** — замечание о файле не из PR отбрасывается (и считается); номер строки остаётся, только если это
   добавленная строка этого файла; `refs` — только номера фрагментов, которые были в промпте; основание печатается из
   метаданных индекса.
5. **Markdown** — текст комментария: изменения и объём контекста, итог и вердикт, три раздела, под спойлером —
   изменённые файлы и список фрагментов RAG.

## Запуск

```bash
mvn -q compile exec:java -Ptask32                                                   # проверка задания (3 проверки) → task32-code-review-report.md
mvn -q exec:java -Ptask32 -Dexec.args="review --base main --head HEAD"             # ревью текущей ветки относительно main
mvn -q exec:java -Ptask32 -Dexec.args="review --base main --head HEAD --out r.md"  # то же + сохранить текст комментария
```

Проверка задания не требует GitHub: создаёт в `target/task32-demo-pr` локальный клон проекта с веткой
`demo/ai-review-pr` (один коммит — новый класс и правка README), берёт из workflow ту же команду `review`, подставляет
SHA демо-PR вместо `${{ github.event.pull_request.*.sha }}` и выполняет её. Ваш репозиторий не меняется.

| Проверка | Что подтверждает |
|---|---|
| 1. Пайплайн | workflow есть; триггер `pull_request`; шаг вызывает `Task32 review` с SHA PR; ревью публикуется в PR; полная история; команда из workflow реально выполнилась и записала файл ревью |
| 2. Diff | ровно файлы PR; diff `base...head` с добавленными строками; номера добавленных строк; новые версии файлов |
| 3. Ревью | RAG дал фрагменты и документации, и кода; три непустых раздела; найдены заложенный баг и заложенное архитектурное нарушение; все замечания о файлах PR |

## Переменные окружения

| Переменная | По умолчанию | Смысл |
|---|---|---|
| `TASK32_RAG_PATHS` | `README.md,docs,AGENTS.md,src/main/java` | что индексировать |
| `TASK32_INDEX` | `task32-review-index.json` | файл индекса (в `.gitignore`, пересобирается при изменении файлов) |
| `TASK32_RAG_FRAGMENTS` | `10` | фрагментов контекста в промпте |
| `TASK32_RAG_EXCLUDE` | `week7/Task32.java,week7/CodeReviewer.java` | исходники, которые не попадают в контекст |
| `TASK32_MAX_DIFF_CHARS` | `40000` | лимит diff в промпте |
| `TASK32_MAX_FILE_CHARS` | `6000` | лимит новой версии одного файла |
