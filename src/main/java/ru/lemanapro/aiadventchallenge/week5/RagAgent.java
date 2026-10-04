package ru.lemanapro.aiadventchallenge.week5;

/**
 * Week 5 (day 22): an agent with two modes over the same model —
 *   NO_RAG: the question goes to the LLM as is;
 *   RAG:    question -> search of relevant chunks in the index -> chunks + question in one prompt -> LLM.
 * All the work is in RagPipeline; the agent only chooses the path and reports what it did.
 */
public final class RagAgent {

    public enum Mode {
        NO_RAG("без RAG"), RAG("с RAG");

        public final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** retrieval is null in NO_RAG mode; error is null unless the LLM call failed. */
    public record Reply(Mode mode, String answer, RagPipeline.Retrieval retrieval, long millis, String error) {
        public boolean failed() {
            return error != null;
        }
    }

    private final RagPipeline pipeline;
    private final RagPipeline.Settings settings;

    public RagAgent(RagPipeline pipeline, RagPipeline.Settings settings) {
        this.pipeline = pipeline;
        this.settings = settings;
    }

    public RagPipeline.Settings settings() {
        return settings;
    }

    public Reply ask(String question, Mode mode) {
        long t0 = System.currentTimeMillis();
        RagPipeline.Retrieval retrieval = null;
        try {
            String answer;
            if (mode == Mode.RAG) {
                retrieval = pipeline.retrieve(question, settings);
                answer = pipeline.answerWithContext(question, retrieval);
            } else {
                answer = pipeline.answerNoRag(question);
            }
            return new Reply(mode, answer, retrieval, System.currentTimeMillis() - t0, null);
        } catch (Exception e) {
            String error = RagPipeline.brief(e);
            return new Reply(mode, "(ошибка: " + error + ")", retrieval, System.currentTimeMillis() - t0, error);
        }
    }
}
