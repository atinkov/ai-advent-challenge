package ru.lemanapro.aiadventchallenge.week6;

import ru.lemanapro.aiadventchallenge.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Week 6 (day 27): the course on disk — a folder of Markdown lessons grouped into module folders.
 *
 *   <root>/00. Карта курса.md                      → module "Начало", kind MAP
 *   <root>/01. Основы НТ/01. Для чего ….md          → module "01. Основы НТ", kind LESSON
 *   <root>/01. Основы НТ/01.1 Задания - ….md        → kind ASSIGNMENT (name contains "Задани")
 *   <root>/08. …/00. О модуле.md                    → kind INTRO
 *   <root>/01. Основы НТ/images/*.png               → served as files, not lessons
 *
 * A lesson id is its path relative to the root ("01. Основы НТ/01. ….md") — the same string the RAG index
 * stores as a chunk source, so a search hit maps back to a lesson without any lookup table.
 * Order = file-name order (the course numbers its files). Hidden folders (.git, .idea) are skipped.
 *
 * Env: TASK27_COURSE_DIR (default ~/Lemana/lmru--qacc--performance-qa-course, then ../lmru--qacc--performance-qa-course).
 */
public final class CourseLibrary {

    public enum Kind { MAP, INTRO, LESSON, ASSIGNMENT }

    public record Lesson(String id, String module, String number, String title, Kind kind, int chars) {
    }

    public record Module(String name, List<Lesson> lessons) {
    }

    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s+");
    private static final Set<String> FILE_TYPES = Set.of("png", "jpg", "jpeg", "gif", "svg", "webp");

    private final Path root;
    private final List<Module> modules = new ArrayList<>();
    private final Map<String, Lesson> byId = new LinkedHashMap<>();

    private CourseLibrary(Path root) {
        this.root = root;
    }

    public static Path resolveRoot() {
        String configured = LlmClient.env("TASK27_COURSE_DIR", "");
        if (!configured.isBlank()) {
            return Path.of(configured.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
        }
        Path home = Path.of(System.getProperty("user.home"), "Lemana", "lmru--qacc--performance-qa-course");
        if (Files.isDirectory(home)) {
            return home;
        }
        return Path.of("..", "lmru--qacc--performance-qa-course").toAbsolutePath().normalize();
    }

    public static CourseLibrary load(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("папка курса не найдена: " + root + " — задайте TASK27_COURSE_DIR");
        }
        CourseLibrary lib = new CourseLibrary(root);
        List<Lesson> top = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path p : entries.sorted(Comparator.comparing(x -> x.getFileName().toString())).toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    List<Lesson> lessons = new ArrayList<>();
                    try (Stream<Path> files = Files.list(p)) {
                        for (Path f : files.filter(x -> x.toString().endsWith(".md"))
                                .sorted(Comparator.comparing(x -> sortKey(x.getFileName().toString()))).toList()) {
                            lessons.add(lib.read(f, name));
                        }
                    }
                    if (!lessons.isEmpty()) {
                        lib.modules.add(new Module(name, lessons));
                    }
                } else if (name.endsWith(".md")) {
                    top.add(lib.read(p, "Начало"));
                }
            }
        }
        if (!top.isEmpty()) {
            lib.modules.addFirst(new Module("Начало", top));
        }
        for (Module m : lib.modules) {
            for (Lesson l : m.lessons()) {
                lib.byId.put(l.id(), l);
            }
        }
        if (lib.byId.isEmpty()) {
            throw new IOException("в " + root + " нет ни одного .md");
        }
        return lib;
    }

    /** "02.1 Задания…" must come right after "02. Урок…": compare numbers numerically (shorter number first), then the name. */
    private static String sortKey(String name) {
        Matcher m = NUMBER.matcher(name);
        if (!m.find()) {
            return "~" + name;
        }
        StringBuilder key = new StringBuilder();
        for (String part : m.group(1).split("\\.")) {
            key.append(String.format("%04d.", Integer.parseInt(part)));
        }
        return key + "!" + name; // "!" < any digit: "0002.!" (lesson 02) sorts before "0002.0001.!" (02.1)
    }

    private Lesson read(Path file, String module) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String name = file.getFileName().toString().replaceFirst("\\.md$", "");
        Matcher num = NUMBER.matcher(name);
        String number = num.find() ? num.group(1) : "";
        Matcher h1 = H1.matcher(text);
        String title = h1.find() ? h1.group(1).replace("`", "").strip() : name;
        String lower = name.toLowerCase(Locale.ROOT);
        Kind kind = lower.contains("задани") ? Kind.ASSIGNMENT
                : lower.contains("карта курса") ? Kind.MAP
                : lower.contains("о модуле") ? Kind.INTRO
                : Kind.LESSON;
        String id = root.relativize(file).toString().replace('\\', '/');
        return new Lesson(id, module, number, title, kind, text.length());
    }

    public Path root() {
        return root;
    }

    public List<Module> modules() {
        return modules;
    }

    public Lesson lesson(String id) {
        return id == null ? null : byId.get(id);
    }

    public int lessonCount() {
        return byId.size();
    }

    /** Markdown of a lesson (only ids from the course list are accepted — no arbitrary paths). */
    public String text(String id) throws IOException {
        if (lesson(id) == null) {
            throw new IllegalArgumentException("нет урока " + id);
        }
        return Files.readString(root.resolve(id), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** Previous / next lesson in course order (null at the ends). */
    public Lesson neighbour(String id, int step) {
        List<String> ids = new ArrayList<>(byId.keySet());
        int i = ids.indexOf(id);
        int j = i + step;
        return i < 0 || j < 0 || j >= ids.size() ? null : byId.get(ids.get(j));
    }

    /**
     * The lesson an assignment belongs to: same module, number "02.1" → lesson "02"; else the previous lesson.
     * For a lesson returns itself.
     */
    public Lesson parentLesson(String id) {
        Lesson l = lesson(id);
        if (l == null || l.kind() != Kind.ASSIGNMENT) {
            return l;
        }
        String major = l.number().contains(".") ? l.number().substring(0, l.number().indexOf('.')) : l.number();
        for (Module m : modules) {
            for (Lesson x : m.lessons()) {
                if (x.module().equals(l.module()) && x.kind() == Kind.LESSON && x.number().equals(major)) {
                    return x;
                }
            }
        }
        Lesson prev = neighbour(id, -1);
        return prev != null && prev.module().equals(l.module()) ? prev : l;
    }

    /**
     * A static file of the course (lesson images). Path traversal is impossible: the normalized path must stay
     * under the root, and only image types are served. Returns null if not allowed / missing.
     */
    public Path file(String relative) {
        Path p = root.resolve(relative).normalize();
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!p.startsWith(root) || !FILE_TYPES.contains(ext) || !Files.isRegularFile(p)) {
            return null;
        }
        return p;
    }

    /** Text without the course's navigation blocks (<!-- nav:start --> … <!-- nav:end -->) — for the LLM. */
    public static String withoutNav(String markdown) {
        return markdown.replaceAll("(?s)<!--\\s*nav:start\\s*-->.*?<!--\\s*nav:end\\s*-->", "").strip();
    }
}
