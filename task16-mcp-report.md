# День 16. Подключение MCP

_Сгенерировано: 2026-09-28 00:43:56_ — `mvn -q compile exec:java -Ptask16`

## Соединение

| Параметр | Значение |
|---|---|
| SDK | `io.modelcontextprotocol.sdk:mcp` (официальный Java SDK), транспорт stdio |
| Команда сервера | `/opt/homebrew/bin/npx -y @modelcontextprotocol/server-everything` |
| Сервер | mcp-servers/everything 2.0.0 |
| Версия протокола | 2025-11-25 |
| Возможности сервера | tools, resources, prompts, logging, completions |
| Время рукопожатия | 4278 мс |

## Инструменты (13, страниц tools/list: 1)

| # | Имя | Описание | Параметры (* — обязательный) |
|---|---|---|---|
| 1 | `echo` | Echoes back the input string | message:string* |
| 2 | `get-annotated-message` | Demonstrates how annotations can be used to provide metadata about content. | messageType:string*, includeImage:boolean |
| 3 | `get-env` | Returns all environment variables, helpful for debugging MCP server configuration |  |
| 4 | `get-resource-links` | Returns up to ten resource links that reference different types of resources | count:number |
| 5 | `get-resource-reference` | Returns a resource reference that can be used by MCP clients | resourceType:string, resourceId:number |
| 6 | `get-structured-content` | Returns structured content along with an output schema for client data validation | location:string* |
| 7 | `get-sum` | Returns the sum of two numbers | a:number*, b:number* |
| 8 | `get-tiny-image` | Returns a tiny MCP logo image. |  |
| 9 | `gzip-file-as-resource` | Compresses a single file using gzip compression. Depending upon the selected output type, returns either the compressed data as a gzipped r… | name:string, data:string, outputType:string |
| 10 | `toggle-simulated-logging` | Toggles simulated, random-leveled logging on or off. |  |
| 11 | `toggle-subscriber-updates` | Toggles simulated resource subscription updates on or off. |  |
| 12 | `trigger-long-running-operation` | Demonstrates a long running operation with progress updates. | duration:number, steps:number |
| 13 | `simulate-research-query` | Simulates a deep research operation that gathers, analyzes, and synthesizes information. Demonstrates MCP task-based operations with progre… | topic:string*, ambiguous:boolean |

## Проверки

- [OK]   соединение установлено (initialize завершён, isInitialized=true)
- [OK]   сервер представился: serverInfo.name задан
- [OK]   согласована версия протокола: 2025-11-25
- [OK]   сервер объявил capability "tools"
- [OK]   ping после установки соединения прошёл
- [OK]   tools/list вернул непустой список (13 шт., страниц: 1)
- [OK]   у каждого инструмента есть имя
- [OK]   имена инструментов уникальны
- [OK]   у каждого инструмента есть inputSchema с type=object

**Итог:** соединение устанавливается, список инструментов возвращается корректно.
