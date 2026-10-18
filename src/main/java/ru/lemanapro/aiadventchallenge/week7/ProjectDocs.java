package ru.lemanapro.aiadventchallenge.week7;

import ru.lemanapro.aiadventchallenge.LlmClient;
import ru.lemanapro.aiadventchallenge.week5.Chunker;
import ru.lemanapro.aiadventchallenge.week5.Embedder;
import ru.lemanapro.aiadventchallenge.week5.RagIndex;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Day 31: RAG over the project's DOCUMENTATION (not its code) — the knowledge base of the /help command.
 *
 * Corpus (TASK31_DOC_PATHS, comma-separated, relative to the project root):
 *   README.md                     — what the project is, quick start, commands
 *   docs/                         — architecture, data schemas (docs/schemas/*.schema.json), API descriptions
 *                                   (docs/api/*.yaml — OpenAPI, MCP tool catalog)
 *   AGENTS.md                     — the project knowledge base (structure, conventions, every day)
 * Files: *.md (structure-aware chunking by headings), *.json / *.yaml / *.yml / *.txt (paragraph chunking).
 *
 * Index: week5 machinery (Chunker.structure → Embedder → RagIndex), stored in task31-docs-index.json
 * (gitignored) next to a fingerprint file; rebuilt automatically when any doc changes (sha256 of the corpus)
 * or the embedding model changes, so `/help` never answers from stale docs.
 *
 * Retrieval: cosine top-K_BEFORE → keep score ≥ max(TASK31_MIN_SCORE, TASK31_RELATIVE_CUT × best) → top TASK31_TOP_K,
 * at most TASK31_PER_SOURCE fragments from one file (so README/docs are not crowded out by the big AGENTS.md).
 */
public final class ProjectDocs {

    public record Fragment(int n, RagIndex.Chunk chunk, double score) {
        /** "docs/api.md › MCP › project-mcp-server (строки 10-42)" */
        public String label() {
            return chunk.source() + (chunk.section() == null || chunk.section().isBlank() ? "" : Chunker.SEP + chunk.section())
                    + " (строки " + chunk.startLine() + "-" + chunk.endLine() + ")";
        }
    }

    public record SourceStat(String source, String group, int chunks) {
    }

    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final List<String> EXT = List.of(".md", ".json", ".yaml", ".yml", ".txt", ".java");

    private final Path root;
    private final Path indexFile;
    private final List<String> paths;
    private final HttpClient http;
    private final LlmClient.Config cfg;
    private RagIndex index;
    private Embedder embedder;
    private String buildNote = "";

    /** Day 31: documentation only (TASK31_DOC_PATHS, TASK31_INDEX). */
    public ProjectDocs(Path root, HttpClient http, LlmClient.Config cfg) {
        this(root, http, cfg, docPaths(), LlmClient.env("TASK31_INDEX", "task31-docs-index.json"));
    }

    /**
     * Any corpus: paths (files / folders relative to root) and the index file name. Day 32 passes docs + src/main/java
     * — *.java files are chunked by class members (Chunker "java" kind).
     */
    public ProjectDocs(Path root, HttpClient http, LlmClient.Config cfg, List<String> paths, String indexName) {
        this.root = root.toAbsolutePath().normalize();
        this.indexFile = this.root.resolve(indexName);
        this.http = http;
        this.cfg = cfg;
        this.paths = List.copyOf(paths);
    }

    public List<String> paths() {
        return paths;
    }

    public static List<String> docPaths() {
        List<String> out = new ArrayList<>();
        for (String p : LlmClient.env("TASK31_DOC_PATHS", "README.md,docs,AGENTS.md").split(",")) {
            if (!p.isBlank()) out.add(p.strip());
        }
        return out;
    }

    // ---------------------------------------------------------------- corpus

    public List<Chunker.Doc> loadCorpus() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String p : paths) {
            Path base = root.resolve(p).normalize();
            if (!base.startsWith(root) || !Files.exists(base)) continue;
            if (Files.isDirectory(base)) {
                try (Stream<Path> s = Files.walk(base)) {
                    s.filter(Files::isRegularFile).filter(ProjectDocs::isDoc).sorted().forEach(f -> {
                        if (!files.contains(f)) files.add(f);
                    });
                }
            } else if (isDoc(base) && !files.contains(base)) {
                files.add(base);
            }
        }
        List<Chunker.Doc> docs = new ArrayList<>();
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (text.isBlank()) continue;
            String source = root.relativize(f).toString().replace('\\', '/');
            String name = f.getFileName().toString();
            boolean md = name.endsWith(".md");
            boolean java = name.endsWith(".java");
            String title = name;
            if (md) {
                Matcher m = H1.matcher(text);
                if (m.find()) title = m.group(1).replace("`", "").strip();
            }
            docs.add(new Chunker.Doc(source, title, md ? "md" : java ? "java" : "text", text));
        }
        return docs;
    }

    private static boolean isDoc(Path f) {
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXT.stream().anyMatch(n::endsWith);
    }

    /** README / docs / schema / api / knowledge base — for the corpus table. */
    public static String groupOf(String source) {
        String s = source.toLowerCase(Locale.ROOT);
        if (s.equals("readme.md")) return "README";
        if (s.endsWith(".java")) return "код";
        if (s.contains("schema") || s.endsWith(".json")) return "схемы данных";
        if (s.contains("/api") || s.endsWith(".yaml") || s.endsWith(".yml")) return "API";
        if (s.startsWith("docs/")) return "docs";
        if (s.equals("agents.md")) return "база знаний (AGENTS.md)";
        return "другое";
    }

    // ---------------------------------------------------------------- index

    /** Opens the index: loads it if the fingerprint matches the docs and the embedder, otherwise rebuilds. */
    public synchronized RagIndex index() throws Exception {
        if (index != null) return index;
        List<Chunker.Doc> docs = loadCorpus();
        if (docs.isEmpty()) {
            throw new IllegalStateException("Нет документации для индекса: ни одного файла из " + paths + " в " + root);
        }
        String fingerprint = fingerprint(docs);
        Path fpFile = indexFile.resolveSibling(indexFile.getFileName() + ".sha256");
        if (Files.isRegularFile(indexFile) && Files.isRegularFile(fpFile)
                && Files.readString(fpFile).strip().equals(fingerprint)) {
            try {
                index = RagIndex.load(indexFile);
                embedder = index.embedder(http, cfg);
                buildNote = "индекс загружен из " + indexFile.getFileName() + " (документация не менялась)";
                return index;
            } catch (Exception e) {
                System.out.println("Индекс документации не читается (" + e.getMessage() + ") — перестраиваю.");
            }
        }
        return rebuild(docs, fingerprint, fpFile);
    }

    public synchronized RagIndex reindex() throws Exception {
        index = null;
        List<Chunker.Doc> docs = loadCorpus();
        return rebuild(docs, fingerprint(docs), indexFile.resolveSibling(indexFile.getFileName() + ".sha256"));
    }

    private RagIndex rebuild(List<Chunker.Doc> docs, String fingerprint, Path fpFile) throws Exception {
        long t0 = System.nanoTime();
        Embedder e = Embedder.fromEnv(http, cfg);
        int max = Integer.parseInt(LlmClient.env("TASK31_CHUNK_MAX", "1500"));
        List<RagIndex.Draft> drafts = new ArrayList<>();
        for (Chunker.Doc d : docs) {
            for (Chunker.Piece p : Chunker.structure(d, max)) drafts.add(new RagIndex.Draft(d, p));
        }
        RagIndex built = RagIndex.build(RagIndex.Strategy.STRUCTURE, docs, drafts, e);
        built.save(indexFile);
        Files.writeString(fpFile, fingerprint + "\n");
        index = built;
        embedder = built.embedder(http, cfg);
        buildNote = "индекс построен заново: " + docs.size() + " файлов → " + drafts.size() + " фрагментов, эмбеддер "
                + e.id() + ", " + (System.nanoTime() - t0) / 1_000_000 + " мс";
        return built;
    }

    /** sha256 over (path, content) of every doc + the embedding setting — any change means a rebuild. */
    private String fingerprint(List<Chunker.Doc> docs) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(("embedding=" + LlmClient.env("EMBEDDING_PROVIDER", "auto") + "|" + LlmClient.env("EMBEDDING_MODEL", "")
                + "|" + LlmClient.env("EMBEDDING_BASE_URL", "") + "|key=" + (cfg != null) + "|max=" + LlmClient.env("TASK31_CHUNK_MAX", "1500"))
                .getBytes(StandardCharsets.UTF_8));
        for (Chunker.Doc d : docs) {
            md.update(d.source().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(d.text().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public String buildNote() {
        return buildNote;
    }

    public List<SourceStat> sources() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RagIndex.Chunk c : index().chunks()) counts.merge(c.source(), 1, Integer::sum);
        List<SourceStat> out = new ArrayList<>();
        counts.forEach((s, n) -> out.add(new SourceStat(s, groupOf(s), n)));
        return out;
    }

    // ---------------------------------------------------------------- retrieval

    public List<Fragment> retrieve(String question) throws Exception {
        return retrieve(question, Integer.parseInt(LlmClient.env("TASK31_TOP_K", "5")),
                Integer.parseInt(LlmClient.env("TASK31_PER_SOURCE", "2")));
    }

    public List<Fragment> retrieve(String question, int topK, int perSource) throws Exception {
        return retrieve(question, topK, perSource, source -> true);
    }

    /** Search only among chunks whose source passes the filter (day 32: documentation and code searched separately). */
    public List<Fragment> retrieve(String question, int topK, int perSource, java.util.function.Predicate<String> sourceFilter) throws Exception {
        RagIndex idx = index();
        double minScore = Double.parseDouble(LlmClient.env("TASK31_MIN_SCORE", embedder.isLocal() ? "0.08" : "0.3"));
        double relCut = Double.parseDouble(LlmClient.env("TASK31_RELATIVE_CUT", "0.6"));

        float[] q = embedder.embed(List.of(question))[0];
        List<RagIndex.Hit> hits = new ArrayList<>(idx.search(q, Math.max(200, topK * 4)));
        hits.removeIf(h -> !sourceFilter.test(h.chunk().source()));
        if (hits.isEmpty()) return List.of();
        double best = hits.getFirst().score();
        double cut = Math.max(minScore, relCut * best);
        Map<String, Integer> perFile = new LinkedHashMap<>();
        List<Fragment> out = new ArrayList<>();
        for (RagIndex.Hit h : hits) {
            if (h.score() < cut || out.size() >= topK) break;
            int used = perFile.getOrDefault(h.chunk().source(), 0);
            if (used >= perSource) continue;
            perFile.put(h.chunk().source(), used + 1);
            out.add(new Fragment(out.size() + 1, h.chunk(), h.score()));
        }
        return out;
    }

    public String embedderId() throws Exception {
        index();
        return embedder.id();
    }
}
