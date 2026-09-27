# День 11 — Модель памяти агента (short-term / working / long-term)

Сгенерировано: 22:07:25.65233, модель `qwen3.8-27b` @ https://gpustack.data.lmru.tech/v1

## Модель памяти

Три отдельных хранилища вместо одной истории диалога, каждое со своим временем жизни и своим правилом, кто и когда в него пишет:

| Слой | Что хранит | Кто пишет | Живёт |
|---|---|---|---|
| **SHORT_TERM** | сырые реплики текущего диалога | код (`addDialogue`) после каждого ответа | до конца сессии, нигде не сохраняется |
| **WORKING** | данные текущей задачи (ключ-значение) | код (`setWorkingFact`), очищается `startTask()` | пока активна конкретная задача |
| **LONG_TERM** | профиль, решения, знания | код (`commitLongTerm`), явный вызов | всегда, персистентно (`task11-long-term-memory.json`) |

Важно: ни один слой не заполняется моделью автоматически — какие данные и в какой слой попадают, решает вызывающий код explicit-вызовом нужного метода, и при сборке запроса код явно указывает, какие слои включить (`MemoryStore.assemble(...)`).

## Сессия 1 — знакомство + задача «код-ревью»

Реплик в диалоге: 3.

**Что попало в LONG_TERM** (сохранено явным вызовом `commitLongTerm`, переживёт сессию):

- имя: Алексей
- роль: бэкенд-разработчик на Java
- стиль_ответов: короткие, конкретные, без вступлений

**Что попало в WORKING** (актуально только для задачи «Чек-лист код-ревью»):

- язык: Java
- предмет: чек-лист для код-ревью pull request'а
- акценты: null-safety, unit-тесты, обработка исключений

**Финальный чек-лист** (ответ построен из LONG_TERM + WORKING + SHORT_TERM):

> ## Чек-лист код-ревью Java PR (акцент: null-safety, тесты, exceptions)
> 
> ---
> 
> ### 1. Null-safety
> 
> - [ ] Публичный API: все параметры/возвраты аннотированы `@NonNull` / `@Nullable` (JSR-305, JetBrains, Spring `@Nullable`)
> - [ ] `Objects.requireNonNull(param, "message")` — **на входе** метода, не по ходу логики
> - [ ] `Optional` — только для **возвратных** значений; никогда в параметрах, по полях, в коллекциях
> - [ ] `Optional.orElse(null)` / `.get()` без `isPresent` — красный флаг
> - [ ] `Map`: `ConcurrentHashMap` / `Hashtable` не принимают `null` key/value — проверить, что не вставляется
> - [ ] `List`/`Set`: не вставлять `null` в коллекции, передаваемые наружу; использовать `Collections.singletonList` вместо `new ArrayList` + add(null)
> - [ ] Stream-цепочки: `filter(Objects::nonNull)` перед `map`, если источник может дать null
> - [ ] JPA: `@ManyToOne` с `FetchType.EAGER` → результат **может быть null**; явно обработать или переключить на `LAZY` + `@ManyToOne(optional = false)`
> - [ ] Деструктуризация: `response.getBody().getItems().get(0)` — каждое звено проверить или заменить на safe-access
> - [ ] Lombok `@NonNull` на полях конструктора — автогенерирует `requireNonNull`, не дублировать вручную
> - [ ] Конфигурация: `@ConfigurationProperties` с `@Validated` + Bean Validation (`@NotBlank` vs `@NotNull`)
> - [ ] JSON-дезерикализация: `ObjectMapper` настроен на `FAIL_ON_NULL_PROPERTIES` или явный `@JsonInclude(NON_NULL)`; `@JsonProperty` с `required = true`
> 
> ---
> 
> ### 2. Обработка исключений
> 
> - [ ] **Нет** `catch (Exception e) { }` — пустой блок или `e.printStackTrace()`
> - [ ] **Нет** `catch (Throwable t)` без явной причины (Agent, OOM)
> - [ ] Checked exceptions: либо обрабатываются, либо **обоснованно** пробрасываются; не конвертировать в `RuntimeException` "чтобы не аннотировать throws"
> - [ ] Cause-chain сохранена: `throw new ServiceEx("msg", e)` — оригинал в `cause`, а не `e.getMessage()` в строке
> - [ ] Domain-исключения ≠ Technical-исключения: `PaymentDeclinedException` vs `JdbcConnectionException` — разные слои, разные политики
> - [ ] `try-with-resources` для всех `AutoCloseable` (Statement, Connection, FileInputStream, `HttpClient`)
> - [ ] `finally` — только для того, что **не** закрывает ресурс (логирование, сброс состояния)
> - [ ] `@ControllerAdvice` / `@ExceptionHandler`: маппинг domain → HTTP статус (400/409/422/500), не бросать 500 на валидацию
> - [ ] Логи: `log.error("Failed to process order {}", orderId, e)` — exception **последним** аргументом, StackTrace не хардкодить в message
> - [ ] Нет PII / секретов в `e.getMessage()`, который уходит в лог/отвечает клиенту
> - [ ] Retry-логика **не** внутри `catch`; вынесена в `RetryTemplate` / Spring Retry / circuit breaker на уровне оркестрации
> - [ ] `InterruptedException`: после `Thread.sleep` / `latch.await` — **восстановить** флаг: `Thread.currentThread().interrupt()`
> - [ ] Custom exception: `serialVersionUID` если сериализуется; `@Getter` на cause; не наследовать от `RuntimeException`, если это checked
> 
> ---
> 
> ### 3. Unit-тесты
> 
> - [ ] Каждый новый/изменённый public-метод покрыт ≥ 1 тестом на **happy path**
> - [ ] Отдельные тесты на **null-пути**: `null` аргумент, пустая коллекция, `Optional.empty`
> - [ ] Отдельные тесты на **исключения**: `assertThrows(ServiceEx.class, ...)` + проверка `getMessage()` / `getCause()`
> - [ ] Структура: **Arrange → Act → Assert**; один тест = одно поведение
> - [ ] Мокаются **только внешние зависимости** (DB, HTTP, Kafka, Clock); сам тестируемый класс — `new`, без PowerMockito
> - [ ] `when(...).thenReturn(...)` — только для вызовов, которые **реально** произойдут в данном сценарии (нет "на всякий случай")
> - [ ] `verify(mock, times(1)).method()` — для side-effects; `verifyNoMoreInteractions(mock)` для строгих сценариев
> - [ ] `@ParameterizedTest` + `@ValueSource` / `@CsvSource` / `@MethodSource` — вместо 5 копипаст-тестов
> - [ ] Test data: Builder / factory method, не `new Order(1L, null, null, null, null, ...)`
> - [ ] `@BeforeEach` минимален; общий setup — в конструкторе тест-класса
> - [ ] Тест **детерминированный**: нет `Thread.sleep`, нет `Random` без seed, нет зависимости от порядка
> - [ ] Время: класс тестов < 1 s; медленно → экстрагировать в интеграционный (`@SpringBootTest`, `@DataJpaTest`)
> - [ ] `@Disabled` / `@Ignore` — **не** в merged PR; либо коммит с reason + issue-линк
> - [ ] Имя теста: `methodName_whenCondition_thenExpectation` (или эквивалент по конвенции)
> - [ ] Coverage-отчёт: смотреть **diff-coverage** (новые строки), а не total; цель ≥ 90% на изменённый код
> 
> ---
> 
> ### 4. Остальное (кратко)
> 
> - [ ] PR ≤ 400 строк diff, атомарные коммиты
> - [ ] N+1 queries, блокирующие I/O в веб-потоке, `String +` в цикле
> - [ ] Секреты в коде, SQL-инъекция, input validation на границе
> - [ ] Логирование: уровень адекватный, traceId пробрасывается
> - [ ] Новые зависимости: версия зафиксирована, `dependency:tree` без конфликтов
> - [ ] CI зелёный: build + tests + SAST
> 
> ---
> 
> Хочешь — уберу в `.md`-файл или адаптирую под Spring Boot / gRPC / Kafka отдельно.

## Сессия 2 — новая задача, новый диалог (эмуляция перезапуска)

Новый `MemoryStore` тем же файлом LONG_TERM: профиль восстановлен из `task11-long-term-memory.json` (имя, роль, стиль_ответов), а WORKING и SHORT_TERM стартуют пустыми — предыдущая задача к новой не относится.

Новая задача «Сообщение коммита», WORKING:

- модуль: оплата (payment)
- тип_изменения: рефакторинг без изменения поведения

**Проверка изоляции.** Вопрос: «Напомни, пожалуйста, что мы обсуждали в прошлый раз про код-ревью — какие акценты я просил сделать в чек-листе?»

Ответ:

> В моей памяти нет записей об обсуждении код-ревью или чек-листе — ни в долговременной, ни в рабочей. Возможно, это было в другой сессии, которая не сохранилась.
> 
> Если напомнишь основные пункты — зафиксирую в профиль, чтобы下次 не потерялось.

Изоляция подтвердилась: в ответе нет акцентов из чек-листа сессии 1 (null-safety / unit-тесты / обработка исключений) — WORKING и SHORT_TERM сессии 1 не пережили смену задачи и сессии, как и задумано.

## Эксперимент: один вопрос, разные слои памяти

Реплика «*Кстати...*» (детали дедупликации кода) в этой сессии была сказана только в диалоге и никогда не записывалась в WORKING — это специально, чтобы у SHORT_TERM был свой, не дублирующийся с WORKING вклад в ответ.

Вопрос: «Составь итоговое сообщение коммита с учётом всего, что мы обсудили, в моём обычном стиле.»

| Включённые слои | Упомянут модуль «оплата» | Упомянута дедупликация | Длина ответа, симв. |
|---|---|---|---|
| Все три слоя | ✓ | ✗ | 276 |
| Без LONG_TERM (нет профиля) | ✓ | ✗ | 525 |
| Без WORKING (нет данных задачи) | ✓ | ✗ | 242 |
| Без SHORT_TERM (нет текущего диалога) | ✓ | ✗ | 130 |

### Все три слоя

> ```
> refactor(payment): extract duplicated validation into BillingService
> 
> No behavioral change.
> 
> - Consolidated payment validation duplicated across 3 call sites
>   into a single BillingService.validatePayment() method
> - Replaced manual state checks with PaymentStatus enum
> ```

### Без LONG_TERM (нет профиля / стиля ответов)

> ```
> refactor(payment): extract shared billing logic into BillingService
> 
> No behavioral change.
> 
> - Extracted duplicate payment validation (3 call sites) into
>   BillingService.validatePayment()
> - Replaced manual state checks with PaymentStatus enum
> ```
> 
> При этом честное предупреждение: «твой обычный стиль» у меня в памяти не сохранён — ни в этой сессии, ни в профиле. Если ты, например, пишешь body на русском, без conventional-commits префикса, с тикетами в заголовке или с разделителем `---` — скажи, пересоберу за секунду.

### Без WORKING (нет данных задачи)

> ```
> refactor(payment): extract shared billing logic into BillingService
> 
> No behavioral change.
> 
> - Moved duplicated validation from 3 call sites into
>   BillingService.validatePayment()
> - Replaced manual state checks with PaymentStatus enum
> ```

### Без SHORT_TERM (нет текущего диалога)

> ```
> refactor(payment): переработать внутреннюю структуру модуля оплаты
> 
> Без изменения публичного API и наблюдаемого поведения.
> ```

## Выводы

- **LONG_TERM** отвечает за то, *как* агент говорит и что он помнит о пользователе поперёк задач и сессий: профиль (имя, роль, предпочтение по стилю) пережил перезапуск сессии, а без него ответ стал заметно длиннее (525 против 276 символов) — модель перестаёт получать инструкцию «отвечай кратко».
- **WORKING** отвечает за факты конкретной задачи: без него модуль «оплата» всё равно попал в ответ (мог остаться в SHORT_TERM) — упоминание модуля = ✓, тогда как со всеми слоями = ✓.
- **SHORT_TERM** отвечает за то, что было сказано только что и нигде больше не записано: деталь про дедупликацию кода упомянута ✗ при включённом SHORT_TERM и ✗ без него — это единственный слой, где эта деталь вообще хранится.
- Разделение на три слоя даёт контроль: код явно решает, что писать в WORKING (текущая задача), что — в LONG_TERM (навсегда), и какие слои включать в конкретный запрос, вместо того чтобы слать модели всё подряд или полагаться на неё в выборе, что важно запомнить.
