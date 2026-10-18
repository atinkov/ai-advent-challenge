package ru.lemanapro.aiadventchallenge.week7;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Day 33, RAG-база знаний поддержки: все *.md из папки support/kb/ (FAQ и документация), разрезанные по заголовкам ## / ###.
 * Поиск лексический (без внешних сервисов): слова запроса приводятся к основе (первые 5 букв — работает
 * и для русского), каждый раздел получает сумму IDF совпавших основ; заголовок раздела весит вдвое больше.
 */
public final class SupportKnowledge {

    public record Section(String source, String title, String text) {
        public String ref() {
            return source + " › " + title;
        }
    }

    public record Hit(Section section, double score) {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{2,3})\\s+(.+)$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP = Set.of("почем", "как", "что", "это", "не", "на", "для", "или", "мой", "меня",
            "мне", "мож", "можно", "есть", "the", "and", "через", "после", "если", "при", "все", "что-т", "так");

    private final List<Section> sections = new ArrayList<>();
    private final Map<String, Double> idf = new HashMap<>();

    public SupportKnowledge(Path docsDir) throws IOException {
        try (Stream<Path> files = Files.list(docsDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                split(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        Map<String, Integer> df = new HashMap<>();
        for (Section s : sections) {
            for (String t : new HashSet<>(stems(s.title() + " " + s.text()))) df.merge(t, 1, Integer::sum);
        }
        df.forEach((t, n) -> idf.put(t, Math.log(1 + (double) sections.size() / n)));
    }

    public List<Section> sections() {
        return sections;
    }

    public List<Hit> search(String query, int k) {
        Set<String> q = new HashSet<>(stems(query));
        List<Hit> hits = new ArrayList<>();
        for (Section s : sections) {
            Set<String> title = new HashSet<>(stems(s.title()));
            Set<String> body = new HashSet<>(stems(s.text()));
            double score = 0;
            for (String t : q) {
                double w = idf.getOrDefault(t, 0.0);
                if (title.contains(t)) score += 2 * w;
                else if (body.contains(t)) score += w;
            }
            if (score > 0) hits.add(new Hit(s, score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(k, hits.size()));
    }

    private void split(String source, String md) {
        String title = source;
        String parent = "";
        StringBuilder buf = new StringBuilder();
        for (String line : md.split("\n")) {
            Matcher m = HEADING.matcher(line);
            if (m.find()) {
                flush(source, title, buf);
                String h = m.group(2).strip();
                if (m.group(1).length() == 2) {
                    parent = h;
                    title = h;
                } else {
                    title = parent.isEmpty() ? h : parent + " › " + h;
                }
                buf.setLength(0);
            } else if (!line.startsWith("# ")) {
                buf.append(line).append('\n');
            }
        }
        flush(source, title, buf);
    }

    private void flush(String source, String title, StringBuilder buf) {
        if (!buf.toString().isBlank()) sections.add(new Section(source, title, buf.toString().strip()));
    }

    static List<String> stems(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        while (m.find()) {
            String w = m.group();
            if (w.length() < 2) continue;
            String stem = w.length() > 5 ? w.substring(0, 5) : w;
            if (!STOP.contains(stem)) out.add(stem);
        }
        return out;
    }
}
