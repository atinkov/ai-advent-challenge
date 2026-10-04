package ru.lemanapro.aiadventchallenge.week5;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Week 5 (RAG), shared: splits a document into chunks. Two strategies (day 21 compares them):
 *
 *   fixed(doc, size, overlap)   — a sliding window of `size` characters with `overlap`; knows nothing about
 *                                 the document, only snaps a cut to the nearest whitespace so words survive.
 *   structure(doc, maxChars)    — follows the document's own structure:
 *                                   Markdown: one chunk per heading section; a section larger than maxChars
 *                                             is split by its blocks (table rows, list items, paragraphs,
 *                                             fenced code), an oversized block — by sentences;
 *                                   Java:     one chunk per member (method / field group / nested type),
 *                                             found by a small lexer that tracks brace depth outside
 *                                             strings, text blocks and comments; a large nested type is
 *                                             split recursively, a method longer than maxChars — by lines;
 *                                   other:    paragraphs packed up to maxChars.
 *
 * Every chunk text is a VERBATIM slice of the document (start/end offsets), so a quote taken from a chunk
 * is always a quote from the source. The extra context a structural chunk needs (table header of a split
 * table) travels separately in Piece.lead.
 *
 * outline(doc, maxChars) returns the leaf structural units (sections / members) — used for the `section` metadata of
 * fixed chunks and for the "how many units were torn apart" metric of the comparison.
 */
public final class Chunker {

    public static final String SEP = " › ";
    /** A member of at least this size becomes its own chunk; smaller neighbours are grouped. */
    private static final int SOLO_MEMBER = 400;
    /** Chunks shorter than this are merged into a neighbour (a lone heading, a lone closing brace). */
    private static final int TINY = 200;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s{0,6})([-*+]|\\d+[.)])\\s+\\S.*");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}.*");
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern ANNOTATION = Pattern.compile("@\\w+(\\.\\w+)*(\\s*\\([^)]*\\))?");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");

    /** kind: "md" | "java" | "text". */
    public record Doc(String source, String title, String kind, String text) {
    }

    /** One chunk before embedding. Offsets are char offsets in Doc.text (end exclusive); lines are 1-based. */
    public record Piece(String section, String lead, int start, int end, int startLine, int endLine, String text) {
    }

    /** Leaf structural unit of a document: a markdown section or a java member. */
    public record Unit(String label, int start, int end) {
    }

    private Chunker() {
    }

    // ================================================================ strategy 1: fixed size

    public static List<Piece> fixed(Doc doc, int size, int overlap) {
        if (size < 50 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("fixed chunking: need size >= 50 and 0 <= overlap < size");
        }
        Lines lines = new Lines(doc.text());
        List<Unit> outline = outline(doc, size);
        String t = doc.text();
        int n = t.length();
        int slack = Math.max(10, size / 10);
        List<Range> out = new ArrayList<>();
        int pos = 0;
        while (pos < n) {
            int end = Math.min(n, pos + size);
            if (end < n) {
                for (int i = end; i > end - slack; i--) { // do not cut a word in half
                    if (Character.isWhitespace(t.charAt(i - 1))) {
                        end = i;
                        break;
                    }
                }
            }
            if (!t.substring(pos, end).isBlank()) {
                out.add(new Range(sectionAt(doc, outline, pos), "", pos, end));
            }
            if (end >= n) {
                break;
            }
            int next = Math.max(end - overlap, pos + 1);
            for (int i = next; i < Math.min(end, next + slack); i++) { // start on a word boundary too
                if (Character.isWhitespace(t.charAt(i - 1))) {
                    next = i;
                    break;
                }
            }
            pos = next;
        }
        return toPieces(lines, out);
    }

    /** Label of the unit that contains the offset; before the first unit of a java file — its package/imports. */
    private static String sectionAt(Doc doc, List<Unit> outline, int offset) {
        String label = null;
        for (Unit u : outline) {
            if (u.start() > offset) {
                break;
            }
            label = u.label();
        }
        if (label != null) {
            return label;
        }
        return outline.isEmpty() ? "" : doc.kind().equals("java") ? "package / imports" : outline.getFirst().label();
    }

    // ================================================================ strategy 2: document structure

    public static List<Piece> structure(Doc doc, int maxChars) {
        if (maxChars < 200) {
            throw new IllegalArgumentException("structure chunking: need maxChars >= 200");
        }
        Lines lines = new Lines(doc.text());
        List<Range> ranges = new ArrayList<>();
        switch (doc.kind()) {
            case "md" -> {
                for (Sec sec : mdSections(lines, doc.title())) {
                    packMarkdown(lines, sec, maxChars, ranges);
                }
            }
            case "java" -> {
                JavaScan scan = scanJava(lines);
                javaRanges(lines, scan, 0, lines.count(), 0, "", maxChars, ranges, new ArrayList<>());
            }
            default -> packPlain(lines, maxChars, ranges);
        }
        return toPieces(lines, mergeTiny(lines, ranges, maxChars));
    }

    /**
     * Leaf structural units: markdown sections, java members (a nested type larger than maxChars is opened
     * into its own members — the same rule structure() uses), paragraphs of plain text.
     */
    public static List<Unit> outline(Doc doc, int maxChars) {
        Lines lines = new Lines(doc.text());
        List<Unit> units = new ArrayList<>();
        switch (doc.kind()) {
            case "md" -> {
                for (Sec sec : mdSections(lines, doc.title())) {
                    units.add(new Unit(sec.path(), lines.start(sec.from()), lines.start(sec.to())));
                }
            }
            case "java" -> javaRanges(lines, scanJava(lines), 0, lines.count(), 0, "", maxChars, new ArrayList<>(), units);
            default -> {
                for (Block b : plainBlocks(lines, 0, lines.count())) {
                    units.add(new Unit("абзац", b.start(), b.end()));
                }
            }
        }
        return units;
    }

    // ---------------------------------------------------------------- markdown

    private record Sec(String path, int from, int to) {
    }

    private record Heading(int level, String name) {
    }

    /** kind: head | row | thead | item | code | para. key = first cell of a table row; lead = header of its table. */
    private record Block(int start, int end, String kind, String key, String lead) {
        int length() {
            return end - start;
        }
    }

    private static List<Sec> mdSections(Lines lines, String title) {
        List<Sec> out = new ArrayList<>();
        List<Heading> stack = new ArrayList<>();
        boolean fence = false;
        int curStart = 0;
        String curPath = "(начало документа)";
        for (int i = 0; i < lines.count(); i++) {
            String raw = lines.line(i);
            String s = raw.strip();
            if (opensFence(s)) {
                fence = !fence;
                continue;
            }
            if (fence) {
                continue; // a "# comment" inside a code block is not a heading
            }
            Matcher m = HEADING.matcher(raw);
            if (!m.matches()) {
                continue;
            }
            if (!lines.slice(curStart, i).isBlank()) {
                out.add(new Sec(curPath, curStart, i));
                curStart = i;
            }
            int level = m.group(1).length();
            while (!stack.isEmpty() && stack.getLast().level() >= level) {
                stack.removeLast();
            }
            stack.add(new Heading(level, cleanInline(m.group(2))));
            curPath = headingPath(stack, title);
        }
        if (curStart < lines.count()) {
            out.add(new Sec(curPath, curStart, lines.count()));
        }
        return out;
    }

    private static String headingPath(List<Heading> stack, String title) {
        List<String> names = new ArrayList<>();
        for (Heading h : stack) {
            names.add(h.name());
        }
        if (names.size() > 1 && stack.getFirst().level() == 1 && names.getFirst().equals(title)) {
            names.removeFirst(); // the document title is already in the `title` metadata
        }
        return String.join(SEP, names);
    }

    private static void packMarkdown(Lines lines, Sec sec, int maxChars, List<Range> out) {
        int start = lines.start(sec.from());
        int end = lines.start(sec.to());
        if (end - start <= maxChars) {
            out.add(new Range(sec.path(), "", start, end));
            return;
        }
        List<Block> blocks = mdBlocks(lines, sec.from(), sec.to());
        List<List<Block>> groups = new ArrayList<>();
        List<Block> cur = new ArrayList<>();
        int size = 0;
        for (Block b : blocks) {
            if (b.length() > maxChars) {
                if (!cur.isEmpty()) {
                    groups.add(cur);
                    cur = new ArrayList<>();
                    size = 0;
                }
                List<int[]> parts = splitLong(lines.text, b.start(), b.end(), maxChars);
                for (int p = 0; p < parts.size(); p++) { // a keyless part is numbered below, together with its neighbours
                    String partKey = b.key() == null ? null : b.key() + " (часть " + (p + 1) + "/" + parts.size() + ")";
                    groups.add(List.of(new Block(parts.get(p)[0], parts.get(p)[1], b.kind(), partKey, b.lead())));
                }
                continue;
            }
            if (!cur.isEmpty() && size + b.length() > maxChars) {
                groups.add(cur);
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(b);
            size += b.length();
        }
        if (!cur.isEmpty()) {
            groups.add(cur);
        }
        for (int g = 0; g < groups.size(); g++) {
            List<Block> group = groups.get(g);
            String firstKey = null;
            String lastKey = null;
            for (Block b : group) {
                if (b.key() != null && b.kind().equals("row")) {
                    firstKey = firstKey == null ? b.key() : firstKey;
                    lastKey = b.key();
                }
            }
            String label = sec.path();
            if (firstKey != null) {
                label += SEP + firstKey + (lastKey.equals(firstKey) ? "" : " … " + lastKey);
            } else if (groups.size() > 1) {
                label += " (часть " + (g + 1) + "/" + groups.size() + ")";
            }
            Block first = group.getFirst();
            String lead = first.kind().equals("row") && first.lead() != null ? first.lead() : "";
            out.add(new Range(label, lead, first.start(), group.getLast().end()));
        }
    }

    /** Splits lines [from, to) of one markdown section into contiguous blocks covering the whole range. */
    private static List<Block> mdBlocks(Lines lines, int from, int to) {
        List<Block> out = new ArrayList<>();
        String tableLead = null;
        int i = from;
        int pending = from; // first line not yet covered by a block (leading blank lines join the next block)
        while (i < to) {
            String raw = lines.line(i);
            String s = raw.strip();
            if (s.isEmpty()) {
                i++;
                continue;
            }
            int first = i;
            String kind;
            String key = null;
            if (opensFence(s)) {
                kind = "code";
                i++;
                while (i < to && !lines.line(i).strip().startsWith(s.substring(0, 3))) {
                    i++;
                }
                i = Math.min(to, i + 1);
                tableLead = null;
            } else if (HEADING.matcher(raw).matches() && first == from) {
                kind = "head";
                i++;
            } else if (s.startsWith("|")) {
                boolean headerRow = i + 1 < to && TABLE_SEPARATOR.matcher(lines.line(i + 1)).matches() && lines.line(i + 1).strip().startsWith("|");
                if (headerRow) {
                    kind = "thead";
                    tableLead = clip(s, 300);
                    i += 2;
                } else {
                    kind = "row";
                    key = firstCell(s);
                    i++;
                }
            } else if (LIST_ITEM.matcher(raw).matches()) {
                kind = "item";
                int indent = indentOf(raw);
                i++;
                while (i < to && !lines.line(i).isBlank() && !startsOtherBlock(lines.line(i), indent)) {
                    i++;
                }
                tableLead = null;
            } else {
                kind = "para";
                i++;
                while (i < to && !lines.line(i).isBlank() && !startsOtherBlock(lines.line(i), -1)) {
                    i++;
                }
                tableLead = null;
            }
            int last = i; // exclusive; trailing blank lines stay with this block
            while (last < to && lines.line(last).isBlank()) {
                last++;
            }
            out.add(new Block(lines.start(pending), lines.start(last), kind, key, kind.equals("row") ? tableLead : null));
            pending = last;
            i = last;
        }
        if (out.isEmpty()) {
            out.add(new Block(lines.start(from), lines.start(to), "para", null, null));
        } else if (pending < to) {
            Block b = out.removeLast();
            out.add(new Block(b.start(), lines.start(to), b.kind(), b.key(), b.lead()));
        }
        return out;
    }

    /** A fence line; "```inline code```" on one line opens nothing. */
    private static boolean opensFence(String stripped) {
        return (stripped.startsWith("```") && stripped.indexOf("```", 3) < 0) || (stripped.startsWith("~~~") && stripped.indexOf("~~~", 3) < 0);
    }

    private static boolean startsOtherBlock(String raw, int itemIndent) {
        String s = raw.strip();
        if (s.startsWith("```") || s.startsWith("~~~") || s.startsWith("|")) {
            return true;
        }
        Matcher m = LIST_ITEM.matcher(raw);
        return m.matches() && (itemIndent < 0 || indentOf(raw) <= itemIndent);
    }

    private static int indentOf(String raw) {
        int n = 0;
        while (n < raw.length() && raw.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String firstCell(String row) {
        String[] cells = row.replaceFirst("^\\|", "").split("\\|", 2);
        return clip(cleanInline(cells[0]), 70);
    }

    private static String cleanInline(String s) {
        return s.replace("`", "").replace("**", "").replace("__", "").strip();
    }

    // ---------------------------------------------------------------- plain text

    private static void packPlain(Lines lines, int maxChars, List<Range> out) {
        List<Block> blocks = plainBlocks(lines, 0, lines.count());
        List<int[]> parts = new ArrayList<>();
        int start = -1;
        int end = -1;
        for (Block b : blocks) {
            if (b.length() > maxChars) {
                if (start >= 0) {
                    parts.add(new int[]{start, end});
                    start = -1;
                }
                parts.addAll(splitLong(lines.text, b.start(), b.end(), maxChars));
                continue;
            }
            if (start >= 0 && b.end() - start > maxChars) {
                parts.add(new int[]{start, end});
                start = -1;
            }
            if (start < 0) {
                start = b.start();
            }
            end = b.end();
        }
        if (start >= 0) {
            parts.add(new int[]{start, end});
        }
        for (int p = 0; p < parts.size(); p++) {
            out.add(new Range("часть " + (p + 1) + "/" + parts.size(), "", parts.get(p)[0], parts.get(p)[1]));
        }
    }

    private static List<Block> plainBlocks(Lines lines, int from, int to) {
        List<Block> out = new ArrayList<>();
        int pending = from;
        int i = from;
        while (i < to) {
            if (lines.line(i).isBlank()) {
                i++;
                continue;
            }
            while (i < to && !lines.line(i).isBlank()) {
                i++;
            }
            while (i < to && lines.line(i).isBlank()) {
                i++;
            }
            out.add(new Block(lines.start(pending), lines.start(i), "para", null, null));
            pending = i;
        }
        if (out.isEmpty() && to > from) {
            out.add(new Block(lines.start(from), lines.start(to), "para", null, null));
        }
        return out;
    }

    // ---------------------------------------------------------------- java

    /** Per-line facts about a java source, computed outside strings / text blocks / comments. */
    private record JavaScan(int[] depthEnd, char[] lastSig, String[] code) {
    }

    private record Member(int from, int to, String name, boolean type) {
    }

    private static JavaScan scanJava(Lines lines) {
        String t = lines.text;
        int count = lines.count();
        int[] depthEnd = new int[count];
        char[] lastSig = new char[count];
        String[] code = new String[count];
        Arrays.fill(code, "");
        final int CODE = 0, LINE_COMMENT = 1, BLOCK_COMMENT = 2, STRING = 3, CHAR = 4, TEXT_BLOCK = 5;
        int state = CODE;
        int depth = 0;
        int line = 0;
        StringBuilder sb = new StringBuilder();
        char sig = 0;
        for (int i = 0; i <= t.length(); i++) {
            char c = i < t.length() ? t.charAt(i) : '\n';
            if (c == '\n') {
                if (line < count) {
                    depthEnd[line] = depth;
                    lastSig[line] = sig;
                    code[line] = sb.toString();
                }
                line++;
                sb.setLength(0);
                sig = 0;
                if (state == LINE_COMMENT) {
                    state = CODE;
                }
                continue;
            }
            char next = i + 1 < t.length() ? t.charAt(i + 1) : 0;
            switch (state) {
                case CODE -> {
                    if (c == '/' && next == '/') {
                        state = LINE_COMMENT;
                        i++;
                    } else if (c == '/' && next == '*') {
                        state = BLOCK_COMMENT;
                        i++;
                    } else if (c == '"' && t.startsWith("\"\"\"", i)) {
                        state = TEXT_BLOCK;
                        i += 2;
                        sb.append('"');
                        sig = '"';
                    } else if (c == '"') {
                        state = STRING;
                        sb.append('"');
                        sig = '"';
                    } else if (c == '\'') {
                        state = CHAR;
                        sb.append('\'');
                        sig = '\'';
                    } else {
                        if (c == '{') {
                            depth++;
                        } else if (c == '}') {
                            depth--;
                        }
                        sb.append(c);
                        if (!Character.isWhitespace(c)) {
                            sig = c;
                        }
                    }
                }
                case LINE_COMMENT -> {
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        state = CODE;
                        i++;
                    }
                }
                case STRING -> {
                    if (c == '\\') {
                        i += next == '\n' ? 0 : 1; // never swallow a line break: the per-line arrays must stay in step
                    } else if (c == '"') {
                        state = CODE;
                        sb.append('"');
                    }
                }
                case CHAR -> {
                    if (c == '\\') {
                        i += next == '\n' ? 0 : 1;
                    } else if (c == '\'') {
                        state = CODE;
                        sb.append('\'');
                    }
                }
                default -> { // TEXT_BLOCK
                    if (c == '\\') {
                        i += next == '\n' ? 0 : 1; // "\<newline>" is a legal line continuation in a text block
                    } else if (c == '"' && t.startsWith("\"\"\"", i)) {
                        state = CODE;
                        i += 2;
                        sb.append('"');
                    }
                }
            }
        }
        return new JavaScan(depthEnd, lastSig, code);
    }

    /** Members declared directly at `depth` within lines [from, to): each ends on a ';' or '}' that returns to `depth`. */
    private static List<Member> members(JavaScan scan, int from, int to, int depth) {
        List<Member> out = new ArrayList<>();
        int start = from;
        for (int i = from; i < to; i++) {
            boolean closes = scan.depthEnd()[i] == depth && !scan.code()[i].isBlank()
                    && (scan.lastSig()[i] == ';' || scan.lastSig()[i] == '}');
            if (closes) {
                out.add(describe(scan, start, i + 1));
                start = i + 1;
            }
        }
        if (start < to) { // trailing comments / an enum body without ';'
            if (out.isEmpty()) {
                out.add(describe(scan, start, to));
            } else {
                Member last = out.removeLast();
                out.add(new Member(last.from(), to, last.name(), last.type()));
            }
        }
        return out;
    }

    private static Member describe(JavaScan scan, int from, int to) {
        StringBuilder sig = new StringBuilder();
        for (int i = from; i < to; i++) {
            sig.append(scan.code()[i]).append(' ');
            // an annotation may carry braces of its own — @SuppressWarnings({"a", "b"}) — and may span lines:
            // look for the declaration's '{' / ';' only outside annotations and only once parentheses are balanced
            String bare = ANNOTATION.matcher(sig).replaceAll(" ");
            long opened = sig.chars().filter(ch -> ch == '(').count();
            long closed = sig.chars().filter(ch -> ch == ')').count();
            if (opened <= closed && (bare.indexOf('{') >= 0 || bare.indexOf(';') >= 0)) {
                break;
            }
        }
        String c = ANNOTATION.matcher(sig).replaceAll(" ");
        int cut = firstOf(c, '{', ';');
        if (cut >= 0) {
            c = c.substring(0, cut);
        }
        String s = c.strip();
        if (s.startsWith("package ") || s.startsWith("import ")) {
            return new Member(from, to, "import", false);
        }
        int eq = c.indexOf('=');
        Matcher type = TYPE_DECL.matcher(c);
        if (type.find() && (eq < 0 || type.start() < eq)) {
            return new Member(from, to, type.group(2), true);
        }
        if (eq >= 0) {
            c = c.substring(0, eq);
        }
        int par = c.indexOf('(');
        String name = lastIdentifier(par >= 0 ? c.substring(0, par) : c);
        if (name == null) {
            name = s.startsWith("static") ? "static {}" : "…";
        } else if (par >= 0) {
            name += "()";
        }
        return new Member(from, to, name, false);
    }

    private static int firstOf(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        return ia < 0 ? ib : ib < 0 ? ia : Math.min(ia, ib);
    }

    private static String lastIdentifier(String s) {
        Matcher m = IDENT.matcher(s);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    private static void javaRanges(Lines lines, JavaScan scan, int from, int to, int depth, String prefix, int maxChars,
                                   List<Range> out, List<Unit> units) {
        List<Member> group = new ArrayList<>();
        int groupSize = 0;
        for (Member m : members(scan, from, to, depth)) {
            int start = lines.start(m.from());
            int end = lines.start(m.to());
            int len = end - start;
            String qualified = prefix.isEmpty() ? m.name() : prefix + "." + m.name();
            if (m.type() && len > maxChars) {
                groupSize = flushGroup(lines, group, prefix, out);
                int open = m.from();
                while (open < m.to() - 1 && scan.depthEnd()[open] <= depth) {
                    open++; // the line with the type's opening brace
                }
                int close = m.to() - 1;
                while (close > open && scan.code()[close].isBlank()) {
                    close--; // the line with the type's closing brace
                }
                int headEnd = lines.start(open + 1);
                units.add(new Unit(qualified + " (объявление)", start, headEnd));
                List<int[]> headParts = headEnd - start > maxChars ? splitByLines(lines, m.from(), open + 1, maxChars) : List.of(new int[]{start, headEnd});
                for (int p = 0; p < headParts.size(); p++) {
                    out.add(new Range(qualified + " (объявление" + (headParts.size() > 1 ? ", часть " + (p + 1) + "/" + headParts.size() : "") + ")",
                            "", headParts.get(p)[0], headParts.get(p)[1]));
                }
                if (open + 1 < close) {
                    javaRanges(lines, scan, open + 1, close, depth + 1, qualified, maxChars, out, units);
                }
                Range tail = out.removeLast(); // the closing brace joins the last chunk of the type
                out.add(new Range(tail.label(), tail.lead(), tail.start(), end));
                continue;
            }
            if (!m.name().equals("import") || !prefix.isEmpty()) {
                units.add(new Unit(qualified, start, end));
            }
            if (len > maxChars) {
                groupSize = flushGroup(lines, group, prefix, out);
                List<int[]> parts = splitByLines(lines, m.from(), m.to(), maxChars);
                for (int p = 0; p < parts.size(); p++) {
                    out.add(new Range(qualified + " (часть " + (p + 1) + "/" + parts.size() + ")", "", parts.get(p)[0], parts.get(p)[1]));
                }
            } else if (len >= SOLO_MEMBER) {
                groupSize = flushGroup(lines, group, prefix, out);
                out.add(new Range(qualified, "", start, end));
            } else {
                if (!group.isEmpty() && groupSize + len > maxChars) {
                    groupSize = flushGroup(lines, group, prefix, out);
                }
                group.add(m);
                groupSize += len;
            }
        }
        flushGroup(lines, group, prefix, out);
    }

    /** Emits the pending group of small members as one chunk; returns 0 (the new group size). */
    private static int flushGroup(Lines lines, List<Member> group, String prefix, List<Range> out) {
        if (group.isEmpty()) {
            return 0;
        }
        List<String> names = new ArrayList<>();
        for (Member m : group) {
            if (!names.contains(m.name())) {
                names.add(m.name());
            }
        }
        String label;
        if (names.equals(List.of("import"))) {
            label = "package / imports";
        } else {
            names.remove("import");
            String list = String.join(", ", names.subList(0, Math.min(4, names.size()))) + (names.size() > 4 ? ", …" : "");
            label = prefix.isEmpty() ? list : names.size() == 1 ? prefix + "." + list : prefix + ": " + list;
        }
        out.add(new Range(label, "", lines.start(group.getFirst().from()), lines.start(group.getLast().to())));
        group.clear();
        return 0;
    }

    /** Splits lines [from, to) into parts of at most maxChars, preferring to cut at a blank line. */
    private static List<int[]> splitByLines(Lines lines, int from, int to, int maxChars) {
        List<int[]> parts = new ArrayList<>();
        int partStart = from;
        int lastBlank = -1;
        for (int i = from; i < to; i++) {
            int sizeWithLine = lines.start(i + 1) - lines.start(partStart);
            if (sizeWithLine > maxChars && i > partStart) {
                int cut = lastBlank > partStart && lines.start(lastBlank + 1) - lines.start(partStart) >= maxChars / 2 ? lastBlank + 1 : i;
                parts.add(new int[]{lines.start(partStart), lines.start(cut)});
                partStart = cut;
                lastBlank = -1;
                i = Math.max(i, cut) - 1; // re-check the current line against the new part
                continue;
            }
            if (lines.line(i).isBlank()) {
                lastBlank = i;
            }
        }
        if (partStart < to) {
            parts.add(new int[]{lines.start(partStart), lines.start(to)});
        }
        List<int[]> out = new ArrayList<>();
        for (int[] p : parts) { // a single line longer than maxChars
            if (p[1] - p[0] > maxChars * 2) {
                out.addAll(splitLong(lines.text, p[0], p[1], maxChars));
            } else {
                out.add(p);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- shared helpers

    private record Range(String label, String lead, int start, int end) {
    }

    /** Splits [start, end) into parts of at most maxChars, cutting after a sentence end, else at whitespace. */
    private static List<int[]> splitLong(String t, int start, int end, int maxChars) {
        List<int[]> parts = new ArrayList<>();
        int pos = start;
        while (end - pos > maxChars) {
            int limit = pos + maxChars;
            int cut = -1;
            for (int i = limit; i > pos + maxChars / 3; i--) {
                char prev = t.charAt(i - 1);
                boolean afterSentence = prev == '\n' || (Character.isWhitespace(prev) && i >= 2 && ".;!?".indexOf(t.charAt(i - 2)) >= 0);
                if (afterSentence) {
                    cut = i;
                    break;
                }
            }
            if (cut < 0) {
                for (int i = limit; i > pos + maxChars / 3; i--) {
                    if (Character.isWhitespace(t.charAt(i - 1))) {
                        cut = i;
                        break;
                    }
                }
            }
            if (cut < 0) {
                cut = limit;
            }
            parts.add(new int[]{pos, cut});
            pos = cut;
        }
        if (pos < end) {
            parts.add(new int[]{pos, end});
        }
        return parts;
    }

    /** Merges a tiny chunk (lone heading, lone brace) into the next one, or into the previous if it is the last. */
    private static List<Range> mergeTiny(Lines lines, List<Range> ranges, int maxChars) {
        List<Range> out = new ArrayList<>();
        Range carry = null;
        for (Range r : ranges) {
            if (lines.text.substring(r.start(), r.end()).isBlank()) {
                if (!out.isEmpty() && carry == null) {
                    Range prev = out.removeLast();
                    out.add(new Range(prev.label(), prev.lead(), prev.start(), r.end()));
                } else {
                    carry = carry == null ? r : new Range(carry.label(), carry.lead(), carry.start(), r.end());
                }
                continue;
            }
            if (carry != null) {
                boolean fits = r.end() - carry.start() <= maxChars * 13 / 10;
                if (fits || lines.text.substring(carry.start(), carry.end()).isBlank()) {
                    r = new Range(mergedLabel(carry.label(), r.label()), r.lead(), carry.start(), r.end());
                } else {
                    out.add(carry);
                }
                carry = null;
            }
            if (lines.text.substring(r.start(), r.end()).strip().length() < TINY) {
                carry = r;
            } else {
                out.add(r);
            }
        }
        if (carry != null) {
            if (!out.isEmpty() && carry.end() - out.getLast().start() <= maxChars * 13 / 10) {
                Range prev = out.removeLast();
                out.add(new Range(prev.label(), prev.lead(), prev.start(), carry.end()));
            } else if (!lines.text.substring(carry.start(), carry.end()).isBlank()) {
                out.add(carry);
            }
        }
        return out;
    }

    private static final Pattern PART_SUFFIX = Pattern.compile("\\s*\\(часть \\d+/\\d+\\)$");

    /** "Parent" + "Parent › Child" -> the child's path; two unrelated neighbours -> "A + B". */
    private static String mergedLabel(String tiny, String next) {
        String tinyBase = PART_SUFFIX.matcher(tiny).replaceFirst("");
        if (tinyBase.isBlank() || next.startsWith(tinyBase) || tiny.equals("package / imports")) {
            return next;
        }
        int sep = next.lastIndexOf(SEP);
        int cut = Math.max(sep < 0 ? -1 : sep + SEP.length() - 1, next.endsWith(")") ? -1 : next.lastIndexOf('.'));
        return tinyBase + " + " + (cut > 0 && cut < next.length() - 1 ? next.substring(cut + 1).strip() : next);
    }

    private static List<Piece> toPieces(Lines lines, List<Range> ranges) {
        List<Piece> out = new ArrayList<>();
        for (Range r : ranges) {
            String text = lines.text.substring(r.start(), r.end());
            if (text.isBlank()) {
                continue;
            }
            out.add(new Piece(r.label(), r.lead(), r.start(), r.end(),
                    lines.lineOf(r.start()) + 1, lines.lineOf(Math.max(r.start(), r.end() - 1)) + 1, text));
        }
        return out;
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Line index over a text: start offset of every line; start(count()) == text length. */
    private static final class Lines {
        final String text;
        private final int[] starts;

        Lines(String text) {
            this.text = text;
            int[] tmp = new int[16];
            int n = 0;
            tmp[n++] = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n' && i + 1 < text.length()) {
                    if (n == tmp.length) {
                        tmp = Arrays.copyOf(tmp, n * 2);
                    }
                    tmp[n++] = i + 1;
                }
            }
            this.starts = Arrays.copyOf(tmp, n);
        }

        int count() {
            return starts.length;
        }

        int start(int line) {
            return line >= starts.length ? text.length() : starts[line];
        }

        String line(int i) {
            String s = text.substring(start(i), start(i + 1));
            return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        }

        String slice(int fromLine, int toLine) {
            return text.substring(start(fromLine), start(toLine));
        }

        int lineOf(int offset) {
            int idx = Arrays.binarySearch(starts, offset);
            return idx >= 0 ? idx : -idx - 2;
        }
    }
}
