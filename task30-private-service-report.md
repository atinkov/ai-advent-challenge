# День 30. Локальная LLM как приватный сервис

> Раздел «Проверка» сгенерирован `Task30` (`mvn -q exec:java -Ptask30 -Dexec.args="check"`) 2026-10-12 00:14.

**Итог:** ✅ приватный AI-сервис работает — модель `qwen3:1.7b` (Ollama 0.40.3), проверено по сетевому адресу `http://172.16.10.3:8030`.

## Как устроено

```
клиенты в сети (браузер, curl, любой OpenAI SDK)
        │  HTTP :8030, Authorization: Bearer <ключ>
        ▼
Task30 — шлюз: ключи → rate limit → лимит контекста / max_tokens → очередь (N слотов)
        │  только localhost
        ▼
Ollama :11434 (не открыта наружу) → qwen3:8b
```

| Адрес | Что делает |
|---|---|
| `GET /` | веб-чат: ключ вводится один раз, история диалога в браузере, ответ печатается по мере генерации |
| `GET /health` | без ключа: статус, модель, версия Ollama, активные запросы, очередь, лимиты |
| `GET /v1/models` | список моделей (OpenAI-формат) |
| `POST /v1/chat/completions` | чат в OpenAI-формате: `stream=false` — JSON, `stream=true` — SSE |

| Ограничение | По умолчанию | Ответ при нарушении |
|---|---|---|
| API-ключ (`TASK30_API_KEYS="ключ:запр_в_мин,…"`) | генерируется при старте | 401 |
| Запросов в минуту на ключ (`TASK30_RPM`) | 20 | 429 + `Retry-After` |
| Одновременных генераций (`TASK30_MAX_CONCURRENT`), очередь (`TASK30_QUEUE_SECONDS`) | 2, 60 с | ждут в очереди; дольше — 503 |
| Промпт (`TASK30_MAX_CONTEXT`) | 6000 токенов (оценка) | 413 |
| Ответ (`TASK30_MAX_TOKENS`) | 1024 токена | обрезается молча |
| Окно модели (`TASK30_NUM_CTX`) | 8192 | — |
| Размер тела запроса | 256 КБ | 413 |
| Модель | только `TASK30_MODEL` | 400 |

## Проверка

| Проверка | Итог | Детали |
|---|---|---|
| Доступ по сети: GET /health с адреса 172.16.10.3 | ✅ | HTTP 200 {"status":"ok","model":"qwen3:1.7b","ollama":"0.40.3","active":0,"queued":0,"served":0,"limits":{"defaultRpm":20,"maxConcurrent":2,"queueSec… |
| Веб-чат отдаётся по сети (GET /) | ✅ | HTTP 200 |
| Без ключа → 401 | ✅ | HTTP 401 |
| Чужой ключ → 401 | ✅ | HTTP 401 |
| С этого же компьютера (127.0.0.1) без ключа → 200 | ✅ | HTTP 200 |
| GET /v1/models с ключом | ✅ | HTTP 200 |
| Чат: POST /v1/chat/completions | ✅ | HTTP 200 за 0.7 с: «Нагрузочное тестирование — это метод проверки системы или приложения под воздействием нагрузки, чтобы определить её способность справиться с ожидаемой нагрузкой…» |
| Чат с историей (несколько сообщений) | ✅ | ответ: «Алексей.» |
| Потоковый ответ (stream=true, SSE) | ✅ | HTTP 200, 81 SSE-чанков, 1.3 с |
| Лимит контекста: промпт > 6000 ток. → 413 | ✅ | HTTP 413 {"error":{"message":"Промпт ≈ 43200 токенов, лимит сервиса 6000","type":"context_length_exceeded","code":"context_length… |
| max_tokens=100000 обрезается до 1024 | ✅ | сгенерировано 1024 ток. |
| Чужая модель → 400 | ✅ | HTTP 400 |
| Битый JSON → 400 | ✅ | HTTP 400 |
| Rate limit: ключ с лимитом 3/мин, 5 запросов подряд | ✅ | коды [200, 200, 200, 429, 429], Retry-After: 60 с |
| Стабильность: 6 параллельных запросов (слотов 2, остальные ждут в очереди) | ✅ | 6 из 6 → 200, верных ответов 6, все за 1.6 с (самый долгий 1.6 с) |
| Сервис жив после нагрузки | ✅ | {"status":"ok","model":"qwen3:1.7b","ollama":"0.40.3","active":0,"queued":0,"served":14,"limits":{"defaultRpm":20,"maxConcurrent":2,"queueSe… |

Параллельные запросы по отдельности: HTTP 200 за 0.5 с, ответ верный; HTTP 200 за 0.3 с, ответ верный; HTTP 200 за 0.8 с, ответ верный; HTTP 200 за 1.0 с, ответ верный; HTTP 200 за 1.3 с, ответ верный; HTTP 200 за 1.6 с, ответ верный.

## Развёртывание

**Домашний сервер / Mac в локальной сети**

```bash
brew services start ollama                 # Ollama слушает только 127.0.0.1:11434 — так и оставить
export TASK30_API_KEYS="ключ-ноутбука:30,ключ-телефона:10"
mvn -q compile exec:java -Ptask30          # шлюз на 0.0.0.0:8030
# с другого устройства: http://<IP-сервера>:8030  (веб-чат)  или  base_url http://<IP>:8030/v1
```

**VPS (Linux)** — Ollama и шлюз на одной машине, наружу только 443 через reverse proxy с TLS:

```bash
curl -fsSL https://ollama.com/install.sh | sh && ollama pull qwen3:8b   # systemd-сервис, 127.0.0.1:11434
TASK30_HOST=127.0.0.1 TASK30_API_KEYS="…" mvn -q exec:java -Ptask30     # или systemd-юнит с этой командой
# Caddyfile:  ai.example.com { reverse_proxy 127.0.0.1:8030 }   — HTTPS-сертификат Caddy получит сам
ufw allow 443 && ufw deny 11434 && ufw deny 8030
```

Модели 8B на CPU-сервере нужно ≥ 8 ГБ RAM и она отвечает медленно (≈ 2–5 ток/с); для живого чата
нужен GPU или Apple Silicon.

**Подключение клиента (Python, OpenAI SDK):**

```python
from openai import OpenAI
client = OpenAI(base_url="http://<IP>:8030/v1", api_key="<ключ>")
print(client.chat.completions.create(model="qwen3:8b",
      messages=[{"role": "user", "content": "Привет!"}]).choices[0].message.content)
```

Любой прошлый день проекта тоже можно направить в сервис:
`HINDSIGHT_API_LLM_BASE_URL=http://<IP>:8030/v1 HINDSIGHT_API_LLM_MODEL=qwen3:8b LLM_API_KEY=<ключ> mvn -q exec:java -Ptask1`.
