# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-02
**Commit:** 29ca611
**Branch:** task2

## OVERVIEW
Maven Java 25 CLI for "AIAdventChallenge" (`ru.lemanapro:AIAdventChallenge:1.0-SNAPSHOT`). One class per advent day, each a runnable LLM demo against an OpenAI-compatible `/chat/completions` endpoint. Each day class has its own Maven profile to run.

## STRUCTURE
```
AIAdventChallenge/
├── pom.xml                  # Java 25, UTF-8; jackson-databind; exec-maven-plugin + per-task profiles
├── .mvn/                    # empty (no Maven wrapper)
├── src/main/java/ru/lemanapro/aiadventchallenge/
│   ├── LlmClient.java       # shared: env config + chat() + content/finishReason helpers (use this, don't duplicate)
│   ├── Main.java            # INTENTIONAL empty stub (user's choice) — not a day class; do not "restore" it
│   ├── Task1.java           # day 1: minimal single LLM call
│   ├── Task2.java           # day 2: same task bare vs controlled (format+max_tokens+stop)
│   └── Task3.java           # day 3: same task 4 reasoning ways + comparison
├── src/main/resources/      # empty
└── src/test/java/           # empty
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Add a dependency | `pom.xml` | Only dep today: `jackson-databind` |
| Shared LLM call / env config | `LlmClient.java` | All day classes call this; put new HTTP/env logic here |
| Day 1 (minimal call) | `Task1.java` | run with `-Ptask1` |
| Day 2 (response control) | `Task2.java` | run with `-Ptask2` |
| Day 3 (reasoning ways) | `Task3.java` | run with `-Ptask3` |
| New advent day | new `TaskN.java` + matching `<profile>` in `pom.xml` | package root `ru.lemanapro.aiadventchallenge` is the convention |
| New test | `src/test/java/` | No test framework declared yet |

## CONVENTIONS
- Maven build, not Gradle. Java 25 both source and target (Corretto 25 is the default `java`).
- LLM calls go through JDK `java.net.http.HttpClient` — no HTTP client library; Jackson only for JSON.
- LLM config via env, all overridable: `HINDSIGHT_API_LLM_PROVIDER` (must be `openai`), `HINDSIGHT_API_LLM_BASE_URL` (default `https://gpustack.data.lmru.tech/v1`), `HINDSIGHT_API_LLM_MODEL` (default `qwen3.8-27b` — `qwen3.6-27b` 404s on the server), key via `LLM_API_KEY` (user's canonical name — no fallbacks; gpustack server requires auth).
- IntelliJ project (`.idea/`); `.mvn/wrapper` referenced in `.gitignore` but no wrapper present.

## ANTI-PATTERNS (THIS PROJECT)
- Do not commit `.idea/` or `.omo/` (IDE/agent state) — `.gitignore` only excludes some `.idea` entries.
- No `mvnw` wrapper: do not assume `./mvnw` works; use `mvn` (verify install).
- Do not hardcode API keys in code or commit them — env vars only.

## COMMANDS
```bash
mvn compile
mvn -q exec:java -Ptask1                           # day 1 (Task1): minimal call
mvn -q exec:java -Ptask2                           # day 2 (Task2): bare vs controlled
mvn -q exec:java -Ptask3                           # day 3 (Task3): 4 reasoning ways
mvn -q exec:java -PtaskN -Dexec.args="Ваш вопрос"  # custom question/task for that day
# point at any OpenAI-compatible server:
HINDSIGHT_API_LLM_BASE_URL=http://localhost:11434/v1 HINDSIGHT_API_LLM_MODEL=llama3 mvn -q exec:java -Ptask3
```
New day = new `TaskN.java` + a `<profile id="taskN">` overriding `exec-maven-plugin` `mainClass`.
Note: default `mainClass` is the empty `Main` stub, so always pass `-PtaskN`.

## NOTES
- `Main.java` is an intentionally empty stub (confirmed by the user). Day demos live in `TaskN.java`; do not delete or "fix" Main.
- Keep day classes thin: all LLM/env/JSON logic belongs in `LlmClient` — never re-copy it into a Task class.
- `.gitignore` includes Eclipse/NetBeans/VS Code blocks from an IDE template — Maven-relevant lines are `target/` and partial `.idea/`.
- `.omo/` holds agent run state, not project code.
