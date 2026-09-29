package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code miracle bonfire} (Dark Souls) / {@code miracle grace} (Elden Ring) / {@code miracle backup}:
 * checkpoints for the worlds in {@code run/}. Light one before testing something that might
 * wreck the world; rest at it to get the world back as it was.
 *
 * <pre>
 * miracle bonfire              light a bonfire: copy the world(s) of every run/ folder
 * miracle bonfire list         the bonfires you've lit
 * miracle bonfire rest [name]  go back to a bonfire (the newest by default)
 * </pre>
 *
 * Resting keeps the world you're leaving as a bonfire too, so nothing is ever lost. Close the
 * game first: a running game keeps writing to its world.
 */
final class Bonfire {

    /** How it talks: bonfire (Dark Souls) or grace (Elden Ring). */
    record Voice(String lit, String rested, String none, String noun) {
        static final Voice BONFIRE = new Voice("BONFIRE LIT", "You rested at the bonfire. Enemies have respawned.",
                "No bonfires lit yet. Light one: miracle bonfire", "bonfire");
        static final Voice GRACE = new Voice("SITE OF GRACE DISCOVERED", "You rested at the site of grace. The world is as it was.",
                "No grace found yet. Touch some: miracle grace", "site of grace");
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Bonfire() {
    }

    static int run(Path dir, List<String> args, Voice voice) throws IOException {
        Project p = Project.find(dir);
        String action = args.isEmpty() ? "light" : args.getFirst();
        List<Path> sides = sides(p);
        if (sides.isEmpty()) {
            throw new Miracle.Heresy("No worlds yet: run/ is empty. Pray first (miracle pray client|server).");
        }
        return switch (action) {
            case "light" -> light(p, sides, voice);
            case "list" -> list(p, sides, voice);
            case "rest" -> rest(p, sides, args.size() > 1 ? args.get(1) : null, voice);
            default -> throw new Miracle.Heresy("bonfire what? light, list or rest");
        };
    }

    /** run/client-26.3, run/server-1.21.11, ...: those with a world in them. */
    private static List<Path> sides(Project p) throws IOException {
        Path run = p.dir().resolve("run");
        if (!Files.isDirectory(run)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(run)) {
            return s.filter(d -> Files.isDirectory(world(d))).sorted().toList();
        }
    }

    /** The server keeps its world in world/, the client keeps all of them in saves/. */
    private static Path world(Path side) {
        return side.resolve(side.getFileName().toString().startsWith("client") ? "saves" : "world");
    }

    private static Path bonfires(Path side) {
        return side.resolve("bonfires");
    }

    private static int light(Project p, List<Path> sides, Voice voice) throws IOException {
        String name = LocalDateTime.now().format(STAMP);
        for (Path side : sides) {
            Path to = bonfires(side).resolve(name);
            copy(world(side), to);
            System.out.println("  " + p.dir().relativize(to) + "  (" + Rituals.human(Rituals.size(to)) + ")");
        }
        System.out.println(voice.lit());
        return 0;
    }

    private static int list(Project p, List<Path> sides, Voice voice) throws IOException {
        boolean any = false;
        for (Path side : sides) {
            if (!Files.isDirectory(bonfires(side))) {
                continue;
            }
            try (Stream<Path> s = Files.list(bonfires(side))) {
                for (Path b : s.filter(Files::isDirectory).sorted().toList().reversed()) {
                    System.out.println("  " + side.getFileName() + "  " + b.getFileName());
                    any = true;
                }
            }
        }
        if (!any) {
            System.out.println(voice.none());
        }
        return 0;
    }

    private static int rest(Project p, List<Path> sides, String name, Voice voice) throws IOException {
        List<String> done = new ArrayList<>();
        for (Path side : sides) {
            Path from = pick(side, name);
            if (from == null) {
                continue;
            }
            // The world being left becomes a bonfire of its own: resting never loses anything.
            Path keep = bonfires(side).resolve(LocalDateTime.now().format(STAMP) + "_before-rest");
            Files.createDirectories(keep.getParent());
            Files.move(world(side), keep);
            copy(from, world(side));
            done.add(side.getFileName() + " <- " + from.getFileName() + " (the world you left is " + keep.getFileName() + ")");
        }
        if (done.isEmpty()) {
            System.out.println(name == null ? voice.none() : "No " + voice.noun() + " named " + name + ".");
            return 1;
        }
        done.forEach(d -> System.out.println("  " + d));
        System.out.println(voice.rested());
        return 0;
    }

    /** The named bonfire, or the newest that wasn't made by resting. */
    private static Path pick(Path side, String name) throws IOException {
        if (!Files.isDirectory(bonfires(side))) {
            return null;
        }
        if (name != null) {
            Path b = bonfires(side).resolve(name);
            return Files.isDirectory(b) ? b : null;
        }
        try (Stream<Path> s = Files.list(bonfires(side))) {
            return s.filter(Files::isDirectory)
                    .filter(b -> !b.getFileName().toString().endsWith("_before-rest"))
                    .max(Path::compareTo).orElse(null);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : s.toList()) {
                Path dest = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(dest);
                } else if (!f.getFileName().toString().equals("session.lock")) {
                    Files.createDirectories(dest.getParent());
                    Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
