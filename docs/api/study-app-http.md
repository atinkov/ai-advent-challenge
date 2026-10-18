# HTTP API «Наставник НТ» (день 27)

Локальное веб-приложение для изучения курса по нагрузочному тестированию на локальной модели (`qwen3:8b` через Ollama).
Запуск: `mvn -q compile exec:java -Ptask27` → `http://localhost:8027` (JDK `HttpServer` на 127.0.0.1:8027; `TASK27_OPEN_BROWSER=0` — не открывать браузер).
Классы: `week6/Task27` (сервер), `week6/StudyAssistant` (логика модели), `week6/CourseLibrary` (модули и уроки курса). UI — `src/main/resources/task27/`.

| Метод и путь | Что делает | Тело / параметры |
|---|---|---|
| `GET /`, `GET /index.html` | страница приложения | — |
| `GET /assets/<файл>` | статика UI (app.js, style.css, marked.min.js, favicon.svg) | — |
| `GET /course-file/<путь>` | картинка из папки курса (безопасная выдача, только внутри курса) | — |
| `GET /api/status` | статус: модель, Ollama, индекс | — |
| `GET /api/course` | дерево курса: модули и уроки (`id` урока = относительный путь = `source` фрагмента RAG) | — |
| `GET /api/lesson` | текст урока | `?id=<lessonId>` |
| `POST /api/ask` | вопрос по курсу с RAG, потоковый ответ | `{question, lessonId?, mode: "ask" \| "hint", history: [{role, content}], think: bool}` |
| `POST /api/explain` | конспект урока или «объясни проще», потоковый ответ | `{lessonId, kind: "summary" \| "simple", think: bool}` |
| `POST /api/quiz` | тест по уроку (structured output + валидация) | `{lessonId, count: 1..8 (4), think: bool}` |

Потоковые ответы — поток JSON-событий (текст по мере генерации, в конце — статистика; у quiz — событие `{type: "quiz", questions}`).
Неизвестный адрес → `404 {"error": "нет такого адреса: …"}`.
