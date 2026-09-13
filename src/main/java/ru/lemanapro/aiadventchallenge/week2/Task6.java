package ru.lemanapro.aiadventchallenge.week2;

import ru.lemanapro.aiadventchallenge.LlmAgent;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Day 6: the first agent.
 *
 * A thin CLI over LlmAgent: reads user input, delegates to the agent, and prints
 * the answer. All LLM logic (persona, memory, request/response) lives in LlmAgent.
 *
 * Day 7: the agent persists its dialogue to agent-context.json (override with
 * AGENT_CONTEXT_FILE), so a restarted process continues the same conversation.
 *
 * Day 8: after every answer the UI prints a token line — exact request/response
 * counts from the API usage, a local estimate of the stored history, and the
 * session total. A request rejected by the server (e.g. context overflow) is
 * reported and the dialogue continues.
 *
 * Usage:
 *   mvn -q exec:java -Ptask6                           (interactive: exit - quit, reset - clear history)
 *   mvn -q exec:java -Ptask6 -Dexec.args="Ваш вопрос"  (single question, then exit)
 */
public final class Task6 {

    private Task6() {
    }

    public static void main(String[] args) throws Exception {
        LlmClient.Config cfg = LlmClient.fromEnv();
        LlmAgent agent = new LlmAgent(cfg);

        System.out.println("Агент запущен (модель: " + cfg.model() + " @ " + cfg.baseUrl() + ")");
        System.out.println("Сжатие истории: " + (agent.compressionEnabled() ? "вкл" : "выкл")
                + " (окно=" + agent.keepRecent() + ", пачка=" + agent.summaryBatch() + ")");
        if (agent.turnCount() > 0) {
            System.out.println("Контекст восстановлен: " + turnsLabel(agent.turnCount()) + ".");
            if (agent.sessionPromptTokens() > 0 || agent.sessionCompletionTokens() > 0) {
                System.out.println("Токены сессии: запрос=" + agent.sessionPromptTokens()
                        + " | ответ=" + agent.sessionCompletionTokens()
                        + " | пик запроса=" + agent.maxPromptTokens());
            }
        }

        if (args.length > 0) {
            printExchange(agent, String.join(" ", args));
            return;
        }

        System.out.println("Введите 'exit' — выход, 'reset' — сброс диалога.");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("Вы: ");
                String line = in.readLine();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }
                if (line.equalsIgnoreCase("reset")) {
                    agent.reset();
                    System.out.println("Агент: история диалога сброшена.");
                    continue;
                }
                printExchange(agent, line);
            }
        }
        System.out.println("Агент завершён (" + turnsLabel(agent.turnCount()) + ").");
    }

    private static String turnsLabel(int n) {
        int mod10 = n % 10;
        int mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) {
            return n + " реплика";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return n + " реплики";
        }
        return n + " реплик";
    }

    private static void printExchange(LlmAgent agent, String question) {
        try {
            String answer = agent.ask(question);
            if (answer == null) {
                System.out.println("Агент: запрос отклонён сервером, диалог не изменён. "
                        + "История может превышать лимит модели — попробуйте 'reset' или более короткое сообщение.");
                return;
            }
            System.out.println("Агент: " + answer);
            System.out.println(tokenLine(agent));
        } catch (Exception e) {
            System.err.println("Агент: запрос не удался: " + e.getMessage());
        }
    }

    private static String tokenLine(LlmAgent agent) {
        LlmClient.Usage usage = agent.lastUsage();
        if (usage == null) {
            return "[токены: ?]";
        }
        return "[токены: запрос=" + tokenValue(usage.promptTokens())
                + " | ответ=" + tokenValue(usage.completionTokens())
                + " | история≈" + agent.historyTokensEstimate()
                + " | сессия=" + (agent.sessionPromptTokens() + agent.sessionCompletionTokens())
                + "]";
    }

    private static String tokenValue(int tokens) {
        return tokens < 0 ? "?" : String.valueOf(tokens);
    }
}
