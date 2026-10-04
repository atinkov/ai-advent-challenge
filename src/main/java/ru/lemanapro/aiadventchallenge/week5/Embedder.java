package ru.lemanapro.aiadventchallenge.week5;

import com.fasterxml.jackson.databind.JsonNode;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Week 5 (RAG), shared: text -> vector. All vectors are L2-normalized, so cosine similarity = dot product.
 *
 * Two implementations:
 *   Api       — a real embedding model behind an OpenAI-compatible POST /embeddings (LlmClient.embed):
 *               batches, input clipping, retries with a smaller clip on HTTP 4xx (models with a short context).
 *   LocalHash — a no-network fallback: hashed bag of word stems with TF-IDF weights (feature hashing with
 *               a sign bit). It is a LEXICAL embedding — it matches shared words and identifiers, not meaning,
 *               and does not cross languages. Needs fit() on the corpus (IDF); the IDF table is stored in
 *               the index (state()) so a query is embedded exactly like the chunks were.
 *
 * fromEnv() picks one — EMBEDDING_PROVIDER = auto (default) | api | local:
 *   auto: EMBEDDING_MODEL if set, else the first model of GET /models whose id looks like an embedding
 *         model (bge / e5 / gte / embed / nomic / minilm ...); a probe request must succeed; otherwise LocalHash.
 * restore() rebuilds the embedder an index was built with (id + state) — queries MUST use the same one.
 */
public interface Embedder {

    /** "api:<model>" or "local-hash-<dim>" — stored in the index. */
    String id();

    int dim();

    /** Learn corpus statistics if the embedder needs them (no-op for API models). */
    default void fit(List<String> corpus) {
    }

    float[][] embed(List<String> texts) throws Exception;

    /** State needed to rebuild this embedder at query time (stored in the index JSON). */
    default Map<String, Object> state() {
        return Map.of();
    }

    default boolean isLocal() {
        return id().startsWith("local-");
    }

    Pattern EMBEDDING_MODEL_ID = Pattern.compile("(?i)(embed|bge|\\be5\\b|e5-|gte|minilm|nomic|arctic|mxbai|jina)");

    static Embedder fromEnv(HttpClient http, LlmClient.Config chatCfg) {
        String provider = LlmClient.env("EMBEDDING_PROVIDER", "auto").toLowerCase(Locale.ROOT);
        int localDim = Integer.parseInt(LlmClient.env("EMBEDDING_LOCAL_DIM", "2048"));
        if (provider.equals("local") || chatCfg == null) {
            return new LocalHash(localDim);
        }
        LlmClient.Config cfg = LlmClient.embeddingConfig(chatCfg);
        String model = cfg.model();
        try {
            if (model.isBlank()) {
                model = LlmClient.listModels(http, cfg).stream()
                        .filter(id -> EMBEDDING_MODEL_ID.matcher(id).find() && !id.toLowerCase(Locale.ROOT).contains("rerank"))
                        .findFirst().orElse("");
                if (model.isBlank()) {
                    throw new IllegalStateException("в GET /models нет модели, похожей на embedding-модель; задайте EMBEDDING_MODEL");
                }
            }
            Api api = new Api(http, new LlmClient.Config(cfg.apiKey(), cfg.baseUrl(), model));
            api.embed(List.of("probe")); // fails fast if the model cannot embed; also learns the dimension
            return api;
        } catch (Exception e) {
            if (provider.equals("api")) {
                throw new IllegalStateException("EMBEDDING_PROVIDER=api, но embedding-модель недоступна: " + describe(e), e);
            }
            System.out.println("Embedding-модель по API недоступна (" + describe(e) + ") — использую локальный эмбеддер local-hash-" + localDim + ".");
            return new LocalHash(localDim);
        }
    }

    /** Rebuilds the embedder recorded in an index. */
    static Embedder restore(String id, JsonNode state, HttpClient http, LlmClient.Config chatCfg) {
        if (id.startsWith("api:")) {
            if (chatCfg == null) {
                throw new IllegalStateException("индекс построен моделью " + id + ", для запросов нужен доступ к API (LLM_API_KEY)");
            }
            LlmClient.Config cfg = LlmClient.embeddingConfig(chatCfg);
            Api api = new Api(http, new LlmClient.Config(cfg.apiKey(), cfg.baseUrl(), id.substring(4)));
            api.dim = state.path("dim").asInt(0);
            return api;
        }
        LocalHash local = new LocalHash(state.path("dim").asInt(2048));
        JsonNode idf = state.path("idf");
        if (idf.isArray() && idf.size() == local.dim) {
            for (int i = 0; i < local.dim; i++) {
                local.idf[i] = (float) idf.get(i).asDouble();
            }
        }
        return local;
    }

    private static String describe(Exception e) {
        if (e instanceof LlmClient.RequestException r) {
            return "HTTP " + r.status() + " " + Chunker.clip(String.valueOf(r.body()).replaceAll("\\s+", " "), 160);
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    static void normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
    }

    // ================================================================ API model

    final class Api implements Embedder {
        private static final int BATCH = 16;
        private final HttpClient http;
        private final LlmClient.Config cfg;
        private int maxChars = Integer.parseInt(LlmClient.env("EMBEDDING_MAX_CHARS", "6000"));
        private int dim;

        Api(HttpClient http, LlmClient.Config cfg) {
            this.http = http;
            this.cfg = cfg;
        }

        @Override
        public String id() {
            return "api:" + cfg.model();
        }

        @Override
        public int dim() {
            return dim;
        }

        @Override
        public Map<String, Object> state() {
            return Map.of("dim", dim);
        }

        @Override
        public float[][] embed(List<String> texts) throws Exception {
            float[][] out = new float[texts.size()][];
            for (int from = 0; from < texts.size(); from += BATCH) {
                List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
                float[][] vectors = embedBatch(batch);
                for (int i = 0; i < vectors.length; i++) {
                    normalize(vectors[i]);
                    out[from + i] = vectors[i];
                }
                dim = vectors[0].length;
            }
            return out;
        }

        private float[][] embedBatch(List<String> batch) throws Exception {
            Exception last = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                List<String> inputs = new ArrayList<>();
                for (String t : batch) {
                    inputs.add(t.isBlank() ? "-" : t.length() > maxChars ? t.substring(0, maxChars) : t);
                }
                try {
                    return LlmClient.embed(http, cfg, inputs);
                } catch (LlmClient.RequestException e) {
                    last = e;
                    if (e.status() == 400 || e.status() == 413 || e.status() == 422) {
                        maxChars = Math.max(500, maxChars / 2); // input longer than the model's context
                    } else if (e.status() == 429 || e.status() >= 500) {
                        Thread.sleep(1500L * (attempt + 1));
                    } else {
                        throw e;
                    }
                } catch (java.io.IOException e) {
                    last = e;
                    Thread.sleep(1500L * (attempt + 1));
                }
            }
            throw last;
        }
    }

    // ================================================================ local fallback

    final class LocalHash implements Embedder {
        private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");
        private static final Pattern CAMEL = Pattern.compile("(?<=[a-zа-я0-9])(?=[A-ZА-Я])|(?<=[A-Z])(?=[A-Z][a-z])|_+");
        private static final Set<String> STOP = Set.of(
                "и", "в", "во", "на", "не", "что", "как", "с", "со", "по", "для", "это", "из", "к", "ко", "а", "но", "или", "ли", "же",
                "от", "до", "при", "за", "о", "об", "у", "то", "так", "его", "её", "их", "он", "она", "они", "мы", "вы", "ты", "я",
                "какой", "какая", "какие", "каких", "какую", "чем", "чего", "где", "когда", "если", "есть", "быть", "был", "была",
                "the", "a", "an", "of", "to", "in", "on", "is", "are", "and", "or", "for", "with", "by", "as", "at", "it", "its", "be",
                "this", "that", "from", "not", "no", "if", "then", "so", "do", "does", "can", "into", "per", "each", "one", "only");
        private final int dim;
        private final float[] idf;

        public LocalHash(int dim) {
            this.dim = dim;
            this.idf = new float[dim];
            java.util.Arrays.fill(idf, 1f);
        }

        @Override
        public String id() {
            return "local-hash-" + dim;
        }

        @Override
        public int dim() {
            return dim;
        }

        @Override
        public void fit(List<String> corpus) {
            int[] df = new int[dim];
            for (String text : corpus) {
                boolean[] seen = new boolean[dim];
                for (String f : features(text)) {
                    int b = bucket(f);
                    if (!seen[b]) {
                        seen[b] = true;
                        df[b]++;
                    }
                }
            }
            for (int i = 0; i < dim; i++) {
                idf[i] = (float) (Math.log((corpus.size() + 1.0) / (df[i] + 1.0)) + 1.0);
            }
        }

        @Override
        public Map<String, Object> state() {
            List<Float> list = new ArrayList<>(dim);
            for (float x : idf) {
                list.add(Math.round(x * 1000f) / 1000f);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dim", dim);
            m.put("idf", list);
            return m;
        }

        @Override
        public float[][] embed(List<String> texts) {
            float[][] out = new float[texts.size()][];
            for (int t = 0; t < texts.size(); t++) {
                float[] tf = new float[dim];
                for (String f : features(texts.get(t))) {
                    tf[bucket(f)] += sign(f);
                }
                for (int i = 0; i < dim; i++) {
                    if (tf[i] != 0) {
                        float a = Math.abs(tf[i]);
                        tf[i] = Math.signum(tf[i]) * (float) (1 + Math.log(a)) * idf[i];
                    }
                }
                normalize(tf);
                out[t] = tf;
            }
            return out;
        }

        private int bucket(String feature) {
            return Math.floorMod(hash(feature), dim);
        }

        private static int sign(String feature) {
            return (hash(feature) >>> 20 & 1) == 0 ? 1 : -1;
        }

        private static int hash(String feature) { // FNV-1a + avalanche: stable across JVMs and runs
            int h = 0x811c9dc5;
            for (byte b : feature.getBytes(StandardCharsets.UTF_8)) {
                h ^= b & 0xff;
                h *= 0x01000193;
            }
            h ^= h >>> 15;
            h *= 0x2c1b3c6d;
            h ^= h >>> 12;
            return h;
        }

        /** Word stems of a text: lower-cased tokens, identifiers split by camelCase/underscore, long words cut to a 5-letter prefix. */
        static List<String> features(String text) {
            List<String> out = new ArrayList<>();
            Matcher m = TOKEN.matcher(text);
            while (m.find()) {
                String token = m.group();
                String[] parts = CAMEL.split(token);
                if (parts.length > 1) { // the identifier as a whole, un-stemmed: AGENT_KEEP_RECENT must not collapse into "agent"
                    out.add(token.toLowerCase(Locale.ROOT));
                }
                for (String p : parts) {
                    add(out, p);
                }
            }
            return out;
        }

        private static void add(List<String> out, String raw) {
            String w = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (w.isEmpty() || STOP.contains(w) || (w.length() == 1 && !Character.isDigit(w.charAt(0)))) {
                return;
            }
            out.add(stem(w));
        }

        /** Crude stemmer: a 5-letter prefix for long words (works for Russian endings), plural "s" off short Latin words. */
        static String stem(String w) {
            if (!w.chars().allMatch(Character::isLetter)) {
                return w;
            }
            if (w.length() > 5) {
                return w.substring(0, 5);
            }
            return w.length() > 3 && w.endsWith("s") && w.charAt(0) < 128 ? w.substring(0, w.length() - 1) : w;
        }
    }
}
