package ru.lemanapro.aiadventchallenge.week5;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Week 5 (RAG), shared: the local document index — chunks with metadata and embeddings, persisted as JSON.
 *
 *   loadCorpus(root)                 documents of the knowledge base (this project's own *.md and *.java)
 *   build(strategy, docs, embedder)  chunk (Chunker) -> embed (Embedder) -> index in memory
 *   save(path) / load(path)          one JSON file per strategy: task21-index-<strategy>.json
 *   search(vector, k)                brute-force cosine top-k (vectors are normalized, cosine = dot product)
 *   open(strategy, http, cfg)        load the file, or build + save it if it does not exist yet
 *
 * Chunk metadata: chunkId, source (relative path), title, section (heading path / Class.member),
 * strategy, ordinal within the document, startLine/endLine.
 *
 * Env: RAG_ROOT (corpus root, default "."), RAG_INDEX_DIR (where index files live, default "."),
 *      RAG_FIXED_SIZE (1000) / RAG_FIXED_OVERLAP (150) / RAG_STRUCT_MAX (1500) — chunking parameters.
 */
public final class RagIndex {

    public enum Strategy {
        FIXED("fixed", "f"), STRUCTURE("structure", "s");

        public final String id;
        final String prefix;

        Strategy(String id, String prefix) {
            this.id = id;
            this.prefix = prefix;
        }

        public static Strategy of(String raw) {
            return raw != null && raw.toLowerCase(Locale.ROOT).startsWith("fix") ? FIXED : STRUCTURE;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chunk(String chunkId, String source, String title, String section, String strategy, int ordinal,
                        int startLine, int endLine, String lead, String text, float[] vector) {

        /** "AGENTS.md › WHERE TO LOOK › Day 18 [s-0042]" — how a source is shown to the user. */
        public String ref() {
            return source + (section == null || section.isBlank() ? "" : Chunker.SEP + section) + " [" + chunkId + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(String strategy, String params, String embedder, int dim, String createdAt, int documents,
                       long corpusChars, JsonNode embedderState) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileFormat(int version, Meta meta, List<Chunk> chunks) {
    }

    public record Hit(Chunk chunk, double score) {
    }

    /** Chunk before embedding, with the offsets needed for the day-21 statistics. */
    public record Draft(Chunker.Doc doc, Chunker.Piece piece) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SKIP_DIRS = Set.of("target", ".git", ".idea", ".omo", ".mvn", "node_modules", "task19-output", "task20-output", "week5");
    /** Own outputs of days 21-25 must not be indexed (reports quote the control questions and their answers). */
    private static final Pattern OWN_OUTPUT = Pattern.compile("task2[1-9].*");
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");

    private final Meta meta;
    private final List<Chunk> chunks;

    private RagIndex(Meta meta, List<Chunk> chunks) {
        this.meta = meta;
        this.chunks = chunks;
    }

    public Meta meta() {
        return meta;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public Chunk byId(String chunkId) {
        for (Chunk c : chunks) {
            if (c.chunkId().equals(chunkId)) {
                return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- corpus

    public static Path root() {
        return Path.of(LlmClient.env("RAG_ROOT", ".")).toAbsolutePath().normalize();
    }

    /**
     * Knowledge base = the project itself: every *.md (AGENTS.md + day reports) and *.java under the root.
     * Skipped: build/IDE dirs, generated outputs, and week5 itself — its sources and reports contain the
     * control questions together with the expected answers (indexing them would leak the answers).
     */
    public static List<Chunker.Doc> loadCorpus(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if ((name.endsWith(".md") || name.endsWith(".java") || name.endsWith(".txt")) && !OWN_OUTPUT.matcher(name).matches()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(p -> root.relativize(p).toString()));
        List<Chunker.Doc> docs = new ArrayList<>();
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (text.isBlank()) {
                continue;
            }
            String source = root.relativize(f).toString().replace('\\', '/');
            String name = f.getFileName().toString();
            String kind = name.endsWith(".md") ? "md" : name.endsWith(".java") ? "java" : "text";
            String title = name;
            if (kind.equals("md")) {
                Matcher m = H1.matcher(text);
                if (m.find()) {
                    title = m.group(1).replace("`", "").strip();
                }
            }
            docs.add(new Chunker.Doc(source, title, kind, text));
        }
        return docs;
    }

    // ---------------------------------------------------------------- build

    public static int fixedSize() {
        return Integer.parseInt(LlmClient.env("RAG_FIXED_SIZE", "1000"));
    }

    public static int fixedOverlap() {
        return Integer.parseInt(LlmClient.env("RAG_FIXED_OVERLAP", "150"));
    }

    public static int structMax() {
        return Integer.parseInt(LlmClient.env("RAG_STRUCT_MAX", "1500"));
    }

    public static String params(Strategy strategy) {
        return strategy == Strategy.FIXED
                ? "size=" + fixedSize() + ", overlap=" + fixedOverlap()
                : "maxChars=" + structMax();
    }

    /** Chunking only (no embeddings) — day 21 uses it for the statistics. */
    public static List<Draft> chunk(Strategy strategy, List<Chunker.Doc> docs) {
        List<Draft> out = new ArrayList<>();
        for (Chunker.Doc d : docs) {
            List<Chunker.Piece> pieces = strategy == Strategy.FIXED
                    ? Chunker.fixed(d, fixedSize(), fixedOverlap())
                    : Chunker.structure(d, structMax());
            for (Chunker.Piece p : pieces) {
                out.add(new Draft(d, p));
            }
        }
        return out;
    }

    /**
     * What is embedded. Fixed chunks are embedded as-is (the pure baseline). A structural chunk is embedded
     * with its breadcrumb ("title › section") and, inside a split table, the table header — the context
     * the structure gives for free.
     */
    static String embedText(Strategy strategy, Draft d) {
        if (strategy == Strategy.FIXED) {
            return d.piece().text();
        }
        String head = d.doc().title() + Chunker.SEP + d.piece().section();
        return head + "\n" + (d.piece().lead().isBlank() ? "" : d.piece().lead() + "\n") + d.piece().text();
    }

    public static RagIndex build(Strategy strategy, List<Chunker.Doc> docs, List<Draft> drafts, Embedder embedder) throws Exception {
        List<String> texts = new ArrayList<>();
        for (Draft d : drafts) {
            texts.add(embedText(strategy, d));
        }
        embedder.fit(texts);
        float[][] vectors = embedder.embed(texts);
        List<Chunk> chunks = new ArrayList<>();
        String prevSource = null;
        int ordinal = 0;
        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            ordinal = d.doc().source().equals(prevSource) ? ordinal + 1 : 1;
            prevSource = d.doc().source();
            chunks.add(new Chunk(String.format("%s-%04d", strategy.prefix, i + 1), d.doc().source(), d.doc().title(), d.piece().section(),
                    strategy.id, ordinal, d.piece().startLine(), d.piece().endLine(), d.piece().lead(), d.piece().text(), vectors[i]));
        }
        long chars = docs.stream().mapToLong(x -> x.text().length()).sum();
        Meta meta = new Meta(strategy.id, params(strategy), embedder.id(), embedder.dim(),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), docs.size(), chars,
                MAPPER.valueToTree(embedder.state()));
        return new RagIndex(meta, chunks);
    }

    // ---------------------------------------------------------------- persistence

    public static Path pathFor(Strategy strategy) {
        return Path.of(LlmClient.env("RAG_INDEX_DIR", ".")).resolve("task21-index-" + strategy.id + ".json");
    }

    public void save(Path file) throws IOException {
        List<Chunk> rounded = new ArrayList<>();
        for (Chunk c : chunks) { // 5 decimals are plenty for cosine and halve the file size
            float[] v = new float[c.vector().length];
            for (int i = 0; i < v.length; i++) {
                v[i] = Math.round(c.vector()[i] * 100000f) / 100000f;
            }
            rounded.add(new Chunk(c.chunkId(), c.source(), c.title(), c.section(), c.strategy(), c.ordinal(), c.startLine(), c.endLine(),
                    c.lead(), c.text(), v));
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), new FileFormat(1, meta, rounded));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static RagIndex load(Path file) throws IOException {
        FileFormat f = MAPPER.readValue(file.toFile(), FileFormat.class);
        if (f.chunks() == null || f.chunks().isEmpty()) {
            throw new IOException("индекс " + file + " пуст");
        }
        return new RagIndex(f.meta(), f.chunks());
    }

    /** Loads the index of a strategy, building and saving it first if the file is missing. */
    public static RagIndex open(Strategy strategy, HttpClient http, LlmClient.Config cfg) throws Exception {
        Path file = pathFor(strategy);
        if (Files.isRegularFile(file)) {
            return load(file);
        }
        System.out.println("Индекс " + file + " не найден — строю (день 21: mvn -q compile exec:java -Ptask21)...");
        List<Chunker.Doc> docs = loadCorpus(root());
        RagIndex index = build(strategy, docs, chunk(strategy, docs), Embedder.fromEnv(http, cfg));
        index.save(file);
        return index;
    }

    /** The embedder this index was built with — queries must be embedded by the same one. */
    public Embedder embedder(HttpClient http, LlmClient.Config cfg) {
        return Embedder.restore(meta.embedder(), meta.embedderState(), http, cfg);
    }

    // ---------------------------------------------------------------- search

    public List<Hit> search(float[] query, int topK) {
        List<Hit> hits = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            float[] v = c.vector();
            if (v.length != query.length) {
                throw new IllegalStateException("размерность запроса " + query.length + " ≠ размерности индекса " + v.length
                        + " — индекс построен другим эмбеддером (" + meta.embedder() + "); пересоберите: -Ptask21");
            }
            double dot = 0;
            for (int i = 0; i < v.length; i++) {
                dot += v[i] * query[i];
            }
            hits.add(new Hit(c, dot));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(topK, hits.size()));
    }
}
