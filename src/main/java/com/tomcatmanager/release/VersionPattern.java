package com.tomcatmanager.release;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Automatic version labels of an application, built from a pattern when a WAR is uploaded.
 *
 * <p>Tokens:
 * <ul>
 *   <li>{@code {n}} – the next number: one more than the highest number already used by a version of the same
 *       shape (with a date in the pattern, the numbering therefore restarts each day); {@code {n:3}} pads it to
 *       three digits (001);</li>
 *   <li>{@code {yyyy} {yy} {MM} {dd} {HH} {mm} {ss}} – the upload time (display time zone);</li>
 *   <li>{@code {file}} – the version in the WAR file name ({@code portal-2.4.1.war} → 2.4.1);</li>
 *   <li>{@code {manifest}} – the Implementation-Version of the WAR's manifest.</li>
 * </ul>
 * Everything else is literal and may contain letters, digits and {@code . _ + -}. Examples: {@code 1.0.{n}} → 1.0.1,
 * 1.0.2, …; {@code {yyyy}.{MM}.{dd}.{n}} → 2026.11.16.1; {@code {file}-b{n}} → 2.4.1-b1. A label that already exists
 * (possible without {@code {n}}) gets a -2, -3, … suffix, so an upload never fails on a taken version.
 */
public final class VersionPattern {
    public static final int MAX_PATTERN_LENGTH = 60;
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z]+)(?::(\\d))?}");
    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9._+-]*");
    private static final Set<String> DATE_TOKENS = Set.of("yyyy", "yy", "MM", "dd", "HH", "mm", "ss");
    private static final Set<String> TOKENS = Set.of("n", "yyyy", "yy", "MM", "dd", "HH", "mm", "ss", "file", "manifest");

    /** Ready-made patterns offered in the application form: pattern → description. */
    public static final Map<String, String> PRESETS = new java.util.LinkedHashMap<>(Map.of());
    static {
        PRESETS.put("1.0.{n}", "1.0.1, 1.0.2, 1.0.3 …");
        PRESETS.put("{n}", "1, 2, 3 …");
        PRESETS.put("{yyyy}.{MM}.{dd}.{n}", "date and number of the day: 2026.11.16.1, 2026.11.16.2 …");
        PRESETS.put("{yyyy}{MM}{dd}-{HH}{mm}", "upload time: 20261116-0930");
        PRESETS.put("{file}", "version in the WAR file name: portal-2.4.1.war → 2.4.1");
        PRESETS.put("{file}-b{n}", "file version and build number: 2.4.1-b1, 2.4.1-b2 …");
        PRESETS.put("{manifest}", "Implementation-Version of the WAR manifest");
    }

    /** What a pattern is filled with for one upload. A version from the file or manifest may be null when absent. */
    public record Inputs(Instant time, ZoneId zone, String fileVersion, String manifestVersion) { }

    private VersionPattern() { }

    /** Why the pattern cannot be used, or null when it is fine. */
    public static String problem(String pattern) {
        String p = pattern == null ? "" : pattern.strip();
        if (p.isEmpty()) return "Enter a version pattern, e.g. 1.0.{n}.";
        if (p.length() > MAX_PATTERN_LENGTH) return "Use at most " + MAX_PATTERN_LENGTH + " characters.";
        Matcher m = TOKEN.matcher(p);
        int last = 0;
        boolean any = false;
        while (m.find()) {
            if (!LITERAL.matcher(p.substring(last, m.start())).matches()) return literalProblem(p.substring(last, m.start()));
            String name = m.group(1);
            if (!TOKENS.contains(name)) return "Unknown placeholder {" + name + "}. Use {n}, {yyyy}, {MM}, {dd}, {HH}, {mm}, {ss}, {file} or {manifest}.";
            if (m.group(2) != null && !name.equals("n")) return "Only {n} takes a width, e.g. {n:3}.";
            if (m.group(2) != null && (m.group(2).equals("0"))) return "The width of {n:…} is 1 to 9 digits.";
            any = true;
            last = m.end();
        }
        if (!LITERAL.matcher(p.substring(last)).matches()) return literalProblem(p.substring(last));
        if (!any) return "Add at least one placeholder such as {n}; a fixed text would give every upload the same version.";
        String sample = render(p, new Inputs(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"), "2.4.1", "2.4.1"), 1);
        if (!ReleaseService.VERSION_LABEL.matcher(sample).matches())
            return "The version must start with a letter or digit (example result: " + sample + ").";
        return null;
    }

    private static String literalProblem(String text) {
        if (text.contains("{") || text.contains("}")) return "Check the braces: placeholders look like {n} or {yyyy}.";
        return "Outside placeholders use only letters, digits and . _ + - (found \"" + text + "\").";
    }

    public static boolean usesNumber(String pattern) { return tokens(pattern).contains("n"); }
    public static boolean usesFile(String pattern) { return tokens(pattern).contains("file"); }
    public static boolean usesManifest(String pattern) { return tokens(pattern).contains("manifest"); }

    private static List<String> tokens(String pattern) {
        List<String> names = new ArrayList<>();
        Matcher m = TOKEN.matcher(pattern == null ? "" : pattern);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /**
     * The version label for an upload: the pattern filled in, with {@code {n}} one more than the highest number of
     * the existing labels of the same shape; a taken label gets a -2, -3 … suffix.
     *
     * @throws IllegalArgumentException when the pattern needs a version from the file name or manifest and there is none
     */
    public static String next(String pattern, Inputs in, Collection<String> existingLabels) {
        String p = pattern.strip();
        if (usesFile(p) && in.fileVersion() == null)
            throw new IllegalArgumentException("The version pattern " + p + " takes the version from the file name, but this file name has none."
                    + " Name the file like portal-2.4.1.war, or change the application's version pattern.");
        if (usesManifest(p) && in.manifestVersion() == null)
            throw new IllegalArgumentException("The version pattern " + p + " takes the Implementation-Version of the WAR manifest, but this WAR has none."
                    + " Add it to the build, or change the application's version pattern.");
        Set<String> taken = existingLabels.stream().map(l -> l.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        String label;
        if (usesNumber(p)) {
            long n = highestNumber(p, in, existingLabels) + 1;
            label = render(p, in, n);
            while (taken.contains(label.toLowerCase(Locale.ROOT))) label = render(p, in, ++n);
        } else {
            String base = render(p, in, 0);
            label = base;
            for (int i = 2; taken.contains(label.toLowerCase(Locale.ROOT)); i++) label = base + "-" + i;
        }
        if (!ReleaseService.VERSION_LABEL.matcher(label).matches())
            throw new IllegalArgumentException("The version pattern " + p + " gave \"" + label + "\", which is not a valid version label."
                    + " Change the application's version pattern.");
        return label;
    }

    /**
     * What the next version will look like, for screens: the real next label when it depends only on the time and
     * the existing versions; with {@code {file}} or {@code {manifest}} those parts are shown as placeholders.
     */
    public static String preview(String pattern, ZoneId zone, Collection<String> existingLabels) {
        String p = pattern == null ? "" : pattern.strip();
        if (problem(p) != null) return "";
        Inputs now = new Inputs(Instant.now(), zone, null, null);
        if (!usesFile(p) && !usesManifest(p)) return next(p, now, existingLabels);
        String shown = p.replace("{file}", "‹file version›").replace("{manifest}", "‹manifest version›");
        return render(shown, now, 0).replaceAll("\\{n(?::\\d)?}", "‹next number›");
    }

    /** The pattern with every token replaced; {@code {n}} by {@code n} (padded), unless n is 0 (then it is kept for preview). */
    static String render(String pattern, Inputs in, long n) {
        ZonedDateTime t = in.time().atZone(in.zone());
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = switch (name) {
                case "n" -> n <= 0 ? m.group() : pad(n, m.group(2));
                case "file" -> in.fileVersion() == null ? "" : in.fileVersion();
                case "manifest" -> in.manifestVersion() == null ? "" : in.manifestVersion();
                default -> DATE_TOKENS.contains(name) ? DateTimeFormatter.ofPattern(name).format(t) : m.group();
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String pad(long n, String width) {
        String s = Long.toString(n);
        int w = width == null ? 0 : Integer.parseInt(width);
        return s.length() >= w ? s : "0".repeat(w - s.length()) + s;
    }

    /** Highest {n} among the existing labels that match the pattern with this upload's time, file and manifest values. */
    private static long highestNumber(String pattern, Inputs in, Collection<String> existingLabels) {
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(pattern.substring(last, m.start())));
            regex.append(m.group(1).equals("n") ? "(\\d+)" : Pattern.quote(render(m.group(), in, 0)));
            last = m.end();
        }
        regex.append(Pattern.quote(pattern.substring(last)));
        Pattern shape = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
        long highest = 0;
        for (String label : existingLabels) {
            Matcher lm = shape.matcher(label);
            if (!lm.matches()) continue;
            for (int g = 1; g <= lm.groupCount(); g++) {
                try { highest = Math.max(highest, Long.parseLong(lm.group(g))); } catch (NumberFormatException ignored) { }
            }
        }
        return highest;
    }
}
