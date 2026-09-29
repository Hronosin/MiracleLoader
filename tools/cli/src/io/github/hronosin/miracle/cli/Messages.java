package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code miracle messages} (alias {@code todo}): the notes past you left on the ground for
 * future you, read Elden Ring style. TODO, FIXME, HACK and XXX comments in the project's
 * sources (fallbacks included), one per line, with where to find them.
 */
final class Messages {

    private static final Pattern NOTE = Pattern.compile("(?://|/?\\*+|#)\\s*(TODO|FIXME|HACK|XXX)\\b[:\\s]*(.*)");

    private Messages() {
    }

    static int run(Path dir) throws IOException {
        Path root = projectOrHere(dir);
        List<String> found = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(root.resolve("build")) && !p.startsWith(root.resolve("run")))
                    .sorted().toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = NOTE.matcher(lines.get(i));
                    if (m.find()) {
                        String text = m.group(2).replaceAll("(\\s*(/\\*+|\\*+/))+\\s*$", "").strip();
                        found.add(phrase(m.group(1), text.isEmpty() ? "something" : text)
                                + "\n      " + root.relativize(f) + ":" + (i + 1) + "   Appraised: " + appraisal(text));
                    }
                }
            }
        }
        if (found.isEmpty()) {
            System.out.println("No messages on the ground. Either the code is perfect, or nobody left notes. Likely the latter.");
            return 0;
        }
        System.out.println(found.size() + " message(s) left by past Tarnished:");
        found.forEach(m -> System.out.println("  " + m));
        return 0;
    }

    private static Path projectOrHere(Path dir) throws IOException {
        try {
            return Project.find(dir).dir();
        } catch (Miracle.Heresy notAProject) {
            return dir.toAbsolutePath().normalize();
        }
    }

    /** The templates the game allows, give or take. */
    private static String phrase(String tag, String text) {
        return switch (tag) {
            case "TODO" -> "Try " + text;
            case "FIXME" -> "Be wary of " + text;
            case "HACK" -> "Could this be a hack? " + text;
            default -> text + " ahead";
        };
    }

    /** How many people found this helpful. Deterministic, like all good lies. */
    private static int appraisal(String text) {
        return Math.floorMod(text.hashCode(), 97) + 3;
    }
}
