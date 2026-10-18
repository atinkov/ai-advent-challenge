# День 32. Автоматизация ревью кода

> Сгенерировано `Task32` (`mvn -q compile exec:java -Ptask32`) 2026-10-19 01:18.

## Вердикт: ✅ задание выполнено

| Проверка | Итог |
|---|---|
| 1. Пайплайн (GitHub Action) запускается на PR и вызывает ассистента | ✅ |
| 2. Ассистент получает diff и изменённые файлы | ✅ |
| 3. Ассистент возвращает ревью: баги, архитектурные проблемы, рекомендации (RAG по документации и коду) | ✅ |

## Как устроено

```
PR открыт / обновлён
   │  GitHub Actions: .github/workflows/ai-review.yml
   ▼
checkout (fetch-depth 0) → JDK 25 → mvn … -Ptask32 review --base <sha> --head <sha> --out target/ai-review.md
   │
   ▼  CodeReviewer
   ├─ git diff base...head, --numstat, --name-status, git show head:<файл>   → diff + изменённые файлы
   ├─ RAG: README.md + docs/ + AGENTS.md + src/main/java → task32-review-index.json
   │       запросы по каждому файлу (путь + идентификаторы добавленных строк) + запрос о соглашениях
   ├─ LLM → JSON {summary, verdict, bugs[], architecture[], recommendations[]}
   └─ проверка в коде: файл из PR, строка — добавленная, ссылки [n] — из контекста → Markdown
   │
   ▼
sticky-комментарий «🤖 AI-ревью PR» в PR (обновляется при новых коммитах)
```

## Проверки

### ✅ 1. Пайплайн (GitHub Action) запускается на PR и вызывает ассистента

- ✅ workflow `.github/workflows/ai-review.yml` есть в репозитории
- ✅ запускается на событие `pull_request` (opened, synchronize, …)
- ✅ шаг запускает `Task32 review` с SHA базы и головы PR: `review --base ${{ github.event.pull_request.base.sha }} --head ${{ github.event.pull_request.head.sha }} --out target/ai-review.md`
- ✅ ревью публикуется комментарием в PR (`pull-requests: write`, sticky comment из `target/ai-review.md`)
- ✅ checkout с полной историей (`fetch-depth: 0`) — нужен для `diff base...head`
- ✅ команда workflow выполнена на демо-PR и записала `target/ai-review.md`

### ✅ 2. Ассистент получает diff и изменённые файлы

- ✅ изменённые файлы PR: [README.md, src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java]
- ✅ diff `base...head` получен: +30 −0, 2025 символов, содержит добавленные строки
- ✅ номера добавленных строк посчитаны: 26 из 26
- ✅ новые версии изменённых файлов получены (`git show head:path`)

### ✅ 3. Ассистент возвращает ревью: баги, архитектурные проблемы, рекомендации (RAG по документации и коду)

- ✅ RAG: в контексте 5 фрагм. документации и 5 фрагм. кода (docs/architecture.md, AGENTS.md, src/main/java/ru/lemanapro/aiadventchallenge/week1/Task2.java, src/main/java/ru/lemanapro/aiadventchallenge/week1/Task1.java, src/main/java/ru/lemanapro/aiadventchallenge/week1/Task3.java, src/main/java/ru/lemanapro/aiadventchallenge/LlmClient.java)
- ✅ в ревью три раздела, все непустые: багов 5, архитектурных 3, рекомендаций 2
- ✅ найден заложенный баг: «JSON-инъекция через неэкранированный city»
- ✅ найдено заложенное нарушение архитектуры: «Нарушение трёхслойной архитектуры: дублирование транспорта»
- ✅ все замечания в ревью относятся к файлам PR

## Демо-PR

Локальный клон `target/task32-demo-pr`, ветка `demo/ai-review-pr`, `056218e...c0ce09c`. Новый класс с заложенными дефектами (ключ в коде, свой HttpClient и JSON вместо `LlmClient`, `args[0]` без проверки, разбор ответа через `split` без проверки статуса) и правка README.

```java
package ru.lemanapro.aiadventchallenge.week7;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Demo: asks the LLM about the weather in a city. */
public final class DemoWeatherTask {

    private static final String API_KEY = "sk-demo-0123456789abcdef";

    public static void main(String[] args) throws Exception {
        String city = args[0];
        HttpClient http = HttpClient.newHttpClient();
        String body = "{\"model\":\"qwen3.8-27b\",\"messages\":[{\"role\":\"user\",\"content\":\"Какая погода в " + city + "?\"}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://gpustack.data.lmru.tech/v1/chat/completions"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String answer = response.body().split("\"content\":\"")[1].split("\"")[0];
        System.out.println("Ответ: " + answer);
    }
}
```

## Ревью, которое получит PR

### 🤖 AI-ревью PR

**Изменения:** 2 файл(ов), +30 −0 (`056218e...c0ce09c`) · модель `qwen3.8-27b` · RAG: 5 фрагм. документации, 5 фрагм. кода

**Итог:** PR добавляет демо-класс DemoWeatherTask в week7 и секцию в README. Код дублирует транспортный слой LlmClient, содержит хардкод API-ключа, JSON-инъекцию и хрупкий парсинг ответа — требует переработки.  
**Вердикт:** ❌ нужны исправления

#### 🐞 Потенциальные баги

1. **[high]** **Хардкод API-ключа в исходном коде** — `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java:11`
   Константа API_KEY = "sk-demo-0123456789abcdef" зашита в класс. Даже если ключ «демо», это нарушает соглашение проекта: «Ключи — только в env или в .env» (README, строка 89) и AGENTS.md: «key LLM_API_KEY (user's canonical name — no fallbacks)». Ключ попадёт в git-историю и будет виден всем.
   _Как исправить:_ Удалить константу. Использовать LlmClient.fromEnv(), который читает LLM_API_KEY из process env / .env. Если класс вообще не нужен — см. архитектурные замечания.
   _Основание:_ [3] `AGENTS.md › CONVENTIONS (часть 1/2) (строки 159-163)`, [9] `src/main/java/ru/lemanapro/aiadventchallenge/LlmClient.java › LlmClient (объявление) (строки 23-39)`
2. **[high]** **JSON-инъекция через неэкранированный city** — `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java:16`
   Строка city вставляется в JSON-строку конкатенацией без экранирования. Аргумент вида Москва"} или любой текст с кавычками/бэкслашами сломает JSON или позволит подменить поля запроса (model, role и т. д.).
   _Как исправить:_ Собирать JSON через Jackson (ObjectMapper / ObjectNode), как это делают все Task-классы проекта (Task1, Task2, Task3 импортируют com.fasterxml.jackson.databind.JsonNode).
   _Основание:_ [6] `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task2.java › package / imports (строки 1-7)`, [7] `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task1.java › package / imports (строки 1-7)`, [8] `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task3.java › package / imports (строки 1-7)`
3. **[medium]** **Хрупкий парсинг ответа через String.split** — `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java:23`
   response.body().split("\"content\":\"")[1].split("\"")[0] упадёт с ArrayIndexOutOfBoundsException, если сервер вернёт ошибку (401, 500) или ответ в другом формате. Также некорректно обрабатывает экранированные кавычки внутри content. Проект использует Jackson для JSON — это прямое нарушение соглашения.
   _Как исправить:_ Разобрать ответ через ObjectMapper.readTree(response.body()) и извлечь .get("choices").get(0).get("message").get("content").textValue().
   _Основание:_ [3] `AGENTS.md › CONVENTIONS (часть 1/2) (строки 159-163)`
4. **[medium]** **Отсутствие проверки HTTP-статуса** — `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java:22`
   Код не проверяет response.statusCode(). При 401/404/500 попытка распарсить тело приведёт к непонятному исключению вместо понятного сообщения об ошибке.
   _Как исправить:_ Добавить if (response.statusCode() != 200) { throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body()); } до парсинга.
5. **[low]** **Нет валидации аргументов** — `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java:14`
   args[0] выбросит ArrayIndexOutOfBoundsException, если пользователь не передаст город. Нет сообщения об использовании.
   _Как исправить:_ Добавить if (args.length < 1) { System.err.println("Использование: DemoWeatherTask <город>"); return; }

#### 🏗 Архитектурные проблемы

1. **[high]** **Нарушение трёхслойной архитектуры: дублирование транспорта** — `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java:15`
   Класс сам создаёт HttpClient, строит HttpRequest, задаёт заголовки и парсит JSON — всё это уже делает LlmClient (chat(), send(), newHttpClient(), fromEnv()). Архитектурный документ прямо запрещает: «Логику LLM/env/JSON не копировать в Task-классы» (README, строка 88) и «Транспорт — LlmClient. Новую HTTP/env-логику кладут сюда» (docs/architecture.md).
   _Как исправить:_ Удалить класс целиком или переписать как тонкий UI-слой: LlmClient client = LlmClient.fromEnv(); String answer = client.chat("Какая погода в " + city + "?"); System.out.println(answer);
   _Основание:_ [1] `docs/architecture.md › Архитектура проекта + Слои (строки 1-20)`, [2] `docs/architecture.md › Общие компоненты › LlmClient … week5/RagPipeline (строки 21-33)`, [9] `src/main/java/ru/lemanapro/aiadventchallenge/LlmClient.java › LlmClient (объявление) (строки 23-39)`
2. **[medium]** **Не соответствует конвенции именования и структуры дня** — `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java:1`
   Согласно README (строка 87) и AGENTS.md: «Новый день = новый weekN/TaskN.java + профиль <profile id="taskN"> в pom.xml». Класс назван DemoWeatherTask, не TaskN; Maven-профиль не добавлен; в pom.xml его нет. Кроме того, неделя 7 по тематике — «ассистент разработчика: RAG + MCP», а не погода.
   _Как исправить:_ Если это учебное демо — переименовать в Task32 (или следующий свободный номер), добавить профиль в pom.xml, либо вынести в отдельный пакет/каталог для демо, не смешивая с днями челленджа.
   _Основание:_ [4] `AGENTS.md › STRUCTURE (часть 2/12) (строки 10-27)`, [5] `AGENTS.md › COMMANDS (часть 2/6) (строки 172-188)`
3. **[medium]** **Некорректная команда запуска в README** — `README.md:101`
   Указано java DemoWeatherTask Москва — это не сработает: нужен classpath (Jackson, если будет), а проект запускается через Maven exec:java с профилем. Все остальные дни запускаются как mvn -q compile exec:java -PtaskN.
   _Как исправить:_ Заменить на корректную команду: mvn -q compile exec:java -Ptask32 -Dexec.args="Москва" (после добавления профиля).
   _Основание:_ [5] `AGENTS.md › COMMANDS (часть 2/6) (строки 172-188)`

#### 💡 Рекомендации

1. **HttpClient не закрывается** — `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java:15`
   HttpClient реализует AutoCloseable (Java 21+). Для CLI-скрипта, который завершается сразу, критичности нет, но лучше закрыть ресурс или использовать try-with-resources.
   _Как исправить:_ Обернуть в try (HttpClient http = HttpClient.newHttpClient()) { … } или вызвать http.close() в finally.
2. **Секция «Демо: погода» не вписывается в структуру README** — `README.md:99`
   README структурирован по дням/неделям и соглашениям. Разрозненная секция про демо-погоду без привязки к номеру дня и без ссылки на профиль выглядит чужеродно. Если демо действительно нужно — лучше добавить строку в таблицу недель или в AGENTS.md в раздел COMMANDS.
   _Как исправить:_ Перенести описание в AGENTS.md (таблица WHERE TO LOOK / COMMANDS) или привязать к конкретному номеру дня в README.
   _Основание:_ [4] `AGENTS.md › STRUCTURE (часть 2/12) (строки 10-27)`, [5] `AGENTS.md › COMMANDS (часть 2/6) (строки 172-188)`

<details><summary>Изменённые файлы и контекст RAG</summary>

- `README.md` изменён +4 −0
- `src/main/java/ru/lemanapro/aiadventchallenge/week7/DemoWeatherTask.java` добавлен +26 −0

Контекст:
- [1] `docs/architecture.md › Архитектура проекта + Слои (строки 1-20)`
- [2] `docs/architecture.md › Общие компоненты › LlmClient … week5/RagPipeline (строки 21-33)`
- [3] `AGENTS.md › CONVENTIONS (часть 1/2) (строки 159-163)`
- [4] `AGENTS.md › STRUCTURE (часть 2/12) (строки 10-27)`
- [5] `AGENTS.md › COMMANDS (часть 2/6) (строки 172-188)`
- [6] `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task2.java › package / imports (строки 1-7)`
- [7] `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task1.java › package / imports (строки 1-7)`
- [8] `src/main/java/ru/lemanapro/aiadventchallenge/week1/Task3.java › package / imports (строки 1-7)`
- [9] `src/main/java/ru/lemanapro/aiadventchallenge/LlmClient.java › LlmClient (объявление) (строки 23-39)`
- [10] `src/main/java/ru/lemanapro/aiadventchallenge/LlmAgent.java › package / imports (строки 1-13)`

</details>

_Сгенерировано `Task32` (день 32): diff PR + RAG по документации и коду проекта. Ревью от модели — проверяйте перед применением._

## Workflow

```yaml
# День 32: автоматическое AI-ревью кода на каждый pull request.
# Шаги: checkout всей истории → JDK 25 → Task32 review (diff base...head + RAG по документации и коду → LLM)
#       → ревью публикуется комментарием в PR (один комментарий, обновляется при новых коммитах).
# Нужно в настройках репозитория (Settings → Secrets and variables → Actions):
#   Secrets:   LLM_API_KEY — ключ LLM; LLM_BASE_URL, HINDSIGHT_API_LLM_MODEL — необязательно (по умолчанию как в LlmClient)
#   Variables: AI_REVIEW_RUNNER — необязательно, например self-hosted, если LLM-шлюз доступен только из внутренней сети
#              (только Variable: контекст secrets в runs-on недоступен)
name: AI code review

on:
  pull_request:
    types: [opened, synchronize, reopened, ready_for_review]

permissions:
  contents: read
  pull-requests: write

concurrency:
  group: ai-review-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  ai-review:
    # черновики не ревьюим; PR из форков не получают секреты — для них ревью не запускается
    if: github.event.pull_request.draft == false && github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ${{ vars.AI_REVIEW_RUNNER || 'ubuntu-latest' }}
    timeout-minutes: 20
    steps:
      - name: Checkout (вся история — нужна для git merge-base / diff base...head)
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: JDK 25
        uses: actions/setup-java@v4
        with:
          distribution: corretto
          java-version: '25'
          cache: maven

      - name: AI review (Task32)
        env:
          LLM_API_KEY: ${{ secrets.LLM_API_KEY }}
          HINDSIGHT_API_LLM_BASE_URL: ${{ secrets.LLM_BASE_URL }}
          HINDSIGHT_API_LLM_MODEL: ${{ secrets.HINDSIGHT_API_LLM_MODEL }}
        run: mvn -q -B compile exec:java -Ptask32 -Dexec.args="review --base ${{ github.event.pull_request.base.sha }} --head ${{ github.event.pull_request.head.sha }} --out target/ai-review.md"

      - name: Публикация ревью в PR
        if: always() && hashFiles('target/ai-review.md') != ''
        uses: marocchino/sticky-pull-request-comment@v2
        with:
          header: ai-review
          path: target/ai-review.md
```
