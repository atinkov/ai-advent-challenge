# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-01
**Commit:** (none yet)
**Branch:** master

## OVERVIEW
Maven Java 25 CLI for "AIAdventChallenge" (`ru.lemanapro:AIAdventChallenge:1.0-SNAPSHOT`). Currently a minimal LLM chat client: sends a prompt to an OpenAI-compatible `/chat/completions` endpoint, prints the reply.

## STRUCTURE
```
AIAdventChallenge/
├── pom.xml                  # Java 25, UTF-8; deps: jackson-databind; exec-maven-plugin
├── .mvn/                    # empty (no Maven wrapper)
├── src/main/java/ru/lemanapro/aiadventchallenge/Main.java  # the whole app
├── src/main/resources/      # empty
└── src/test/java/           # empty
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Add a dependency | `pom.xml` | Only dep today: `jackson-databind` |
| App logic | `src/main/java/ru/lemanapro/aiadventchallenge/Main.java` | Single class; package root `ru.lemanapro.aiadventchallenge` is the established convention |
| New test | `src/test/java/` | No test framework declared yet |

## CONVENTIONS
- Maven build, not Gradle. Java 25 both source and target (Corretto 25 is the default `java`).
- LLM calls go through JDK `java.net.http.HttpClient` — no HTTP client library; Jackson only for JSON.
- LLM config via env, all overridable: `HINDSIGHT_API_LLM_PROVIDER` (must be `openai`), `HINDSIGHT_API_LLM_BASE_URL` (default `https://gpustack.data.lmru.tech/v1`), `HINDSIGHT_API_LLM_MODEL` (default `qwen3.6-27b`), key via `HINDSIGHT_API_LLM_API_KEY` or `OPENAI_API_KEY` (gpustack server requires auth).
- IntelliJ project (`.idea/`); `.mvn/wrapper` referenced in `.gitignore` but no wrapper present.

## ANTI-PATTERNS (THIS PROJECT)
- Do not commit `.idea/` or `.omo/` (IDE/agent state) — `.gitignore` only excludes some `.idea` entries.
- No `mvnw` wrapper: do not assume `./mvnw` works; use `mvn` (verify install).
- Do not hardcode API keys in code or commit them — env vars only.

## COMMANDS
```bash
mvn compile
mvn -q exec:java                                    # default prompt
mvn -q exec:java -Dexec.args="Your question here"   # custom prompt
# point at any OpenAI-compatible server:
HINDSIGHT_API_LLM_BASE_URL=http://localhost:11434/v1 HINDSIGHT_API_LLM_MODEL=llama3 mvn -q exec:java
```

## NOTES
- Repo has zero commits; history is empty.
- `.gitignore` includes Eclipse/NetBeans/VS Code blocks from an IDE template — Maven-relevant lines are `target/` and partial `.idea/`.
- `.omo/` holds agent run state, not project code.
