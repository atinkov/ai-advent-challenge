# Проверка ДЗ: Задание: проверки и ассерты

> 2026-10-19 01:50 · студент `student` · задание `02. Первый сценарий на Gatling/12.1 Задание - проверки и ассерты.md` · файлы: AccountEndpoint.java, AuthEndpoint.java, DashboardEndpoint.java, OperationsSimulation.java · модель `qwen3.8-27b`

## Итог: ✅ зачтено — выполнено 9 из 9 требований

Логика проверок и ассертов спроектирована правильно: все четыре запроса имеют check() на статус и содержимое, exitHereIfFailed() стоит после авторизации, глобальные assertions на месте с обоснованием порогов. Однако код не скомпилируется: везде используется jmesPath() вместо jsonPath(), а на строке 36 AccountEndpoint вызов saveAs() после in() некорректен.

| # | Требование | Статус | Где в коде | Комментарий |
|---|---|---|---|---|
| R1 | Для запроса авторизации добавляется проверка кода ответа и проверка на то, что `accessToken` присутствует в от… | ✅ выполнено | `AuthEndpoint.java:15` `.check(status().is(200))` | Статус 200, notNull на accessToken, length > 0, saveAs — всё на месте. exitHereIfFailed() на строке 24 OperationsSimulation. Логика полностью соответствует требованию. |
| R2 | Для запроса получения списка счетов (`/api/v1/dashboard/accounts`) добавляется проверка кода ответа и проверка… | ✅ выполнено | `DashboardEndpoint.java:16` `.check(jmesPath("length(accounts)").ofInt().gt(0))` | Статус 200 + проверка, что список accounts непустой. Логика верная; см. issue по jmesPath/jsonPath. |
| R3 | Для перевода и пополнения добавляется проверка кода ответа и не менее одной проверки содержимого тела ответа, … | ✅ выполнено | `AccountEndpoint.java:27` `.check(jmesPath("status").is("COMPLETED"))` | Deposit: status 200 + проверка amount. Transfer: status 200 + проверка status=COMPLETED. Оба запроса имеют и статус, и проверку содержимого. |
| R4 | Для снятия средств проверка проектируется с учётом того, что запрос с суммой, превышающей баланс счёта, являет… | ✅ выполнено | `AccountEndpoint.java:36` `.check(status().in(200, 400).saveAs("withdrawStatus"))` | Выбран подход с in(200, 400): оба кода засчитываются как OK, 400 не искажает метрику. Условная проверка balance через checkIf — логично. Но saveAs после in() не скомпилируется, см. issues. |
| R5 | На уровне класса `Simulation` добавляются глобальные assertions: | ✅ выполнено | `OperationsSimulation.java:35` `.assertions(` | assertions() объявлены на уровне класса Simulation в инициализационном блоке, внутри setUp(...).protocols(...).assertions(...). |
| R6 | доля успешных запросов по всему прогону не ниже согласованного порога; | ✅ выполнено | `OperationsSimulation.java:36` `global().successfulRequests().percent().gte(99.0)` | Порог 99% — разумно для локального стенда, обосновано комментарием на строке 32. |
| R7 | 95-й перцентиль времени ответа не выше согласованного порога в миллисекундах; | ✅ выполнено | `OperationsSimulation.java:37` `global().responseTime().percentile3().lt(1500)` | percentile3 = 95-й перцентиль, порог 1500 мс. Обоснование в комментарии на строке 33. |
| R8 | отдельное условие на нулевое количество неуспешных запросов авторизации, поскольку сбой на этом шаге всегда яв… | ✅ выполнено | `OperationsSimulation.java:38` `details("Login").failedRequests().count().is(0L)` | Отдельное условие: ноль KO именно по Login. Имя "Login" совпадает с http("Login") в AuthEndpoint. Тип 0L корректен для count(). |
| R9 | Конкретные числовые пороги не заданы намеренно, их нужно определить самостоятельно и обосновать выбор коротким… | ✅ выполнено | `OperationsSimulation.java:32` `// 99%: стенд локальный, сетевых сбоев быть не должно — допускаем един…` | Три комментария (строки 32–34) обосновывают каждый порог: 99%, 1500 мс, ноль для авторизации. Достаточно. |

## Замечания к коду

- `AccountEndpoint.java:36` — status().in(200, 400).saveAs("withdrawStatus") не скомпилируется: in() возвращает Check, а у Check нет метода saveAs(). saveAs() вызывается на экстракторе, до применения матчера. *Подсказка:* Разбей на два отдельных .check(): один — status().in(200, 400) для валидации, второй — status().saveAs("withdrawStatus") для сохранения. В уроке §2.4 показано, что saveAs() присоединяется к экстрактору, а не к результату матчера.
- `AuthEndpoint.java:16` — Используется jmesPath(), которого нет в стандартном API Gatling. В уроке и в HttpDsl метод называется jsonPath(). Аналогичная ошибка во всех файлах (DashboardEndpoint, AccountEndpoint). *Подсказка:* Замени jmesPath на jsonPath и адаптируй синтаксис путей: вместо jmesPath("accessToken") → jsonPath("$.accessToken"). Для проверки длины массива используй jsonPath("$.accounts").ofArray().size().gt(0) или jsonPath("$.accounts[0]").exists(). В уроке §2.2 все примеры построены на jsonPath.
- `DashboardEndpoint.java:16` — Даже если заменить jmesPath на jsonPath, выражение length(accounts) — это JMESPath-синтаксис, а не JSONPath. В JSONPath нет функции length() в таком виде. *Подсказка:* Для проверки непустого массива в JSONPath/Gatling подойдёт jsonPath("$.accounts").ofArray().size().gt(0) либо jsonPath("$.accounts[0]").exists(). Обрати внимание на примеры матчеров в уроке §2.2.

## Что сделать дальше

1. Замени все jmesPath(...) на jsonPath(...) и перепиши пути в JSONPath-синтаксисе (префикс $., для массивов — ofArray().size()).
1. В AccountEndpoint.withdraw() раздели проверку статуса и сохранение: два отдельных .check() — status().in(200, 400) и status().saveAs("withdrawStatus").
1. После исправлений запусти прогон и убедись, что в отчёте Withdraw показывает OK и для 200, и для 400, а Login при сбое не порождает каскад KO на последующих шагах.
