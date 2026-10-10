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
 * Resting keeps the world you're leaving as a bonfire too, so nothing is ever lost. A running
 * game keeps writing to its world, so both refuse while one has it open (lighting can be told
 * {@code --anyway}).
 */
final class Bonfire {

    /** How it talks: bonfire (Dark Souls) or grace (Elden Ring). */
    record Voice(String lit, String rested, String none, String noun, String command) {
        static final Voice BONFIRE = new Voice("BONFIRE LIT", "You rested at the bonfire. Enemies have respawned.",
                "No bonfires lit yet. Light one: miracle bonfire", "bonfire", "bonfire");
        static final Voice GRACE = new Voice("SITE OF GRACE DISCOVERED", "You rested at the site of grace. The world is as it was.",
                "No grace found yet. Touch some: miracle grace", "site of grace", "grace");
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private Bonfire() {
    }

    static int run(Path dir, List<String> args, Voice voice) throws IOException {
        Project p = Project.find(dir);
        boolean anyway = args.contains("--anyway");
        List<String> words = args.stream().filter(a -> !a.startsWith("--")).toList();
        String action = words.isEmpty() ? "light" : words.getFirst();
        List<Path> sides = sides(p);
        if (sides.isEmpty()) {
            throw new Miracle.Heresy("No worlds yet: run/ is empty. Pray first (miracle pray client|server).");
        }
        if (!action.equals("list")) {
            List<Path> busy = inUse(sides);
            if (!busy.isEmpty()) {
                String which = String.join(", ", busy.stream().map(w -> p.dir().relativize(w).toString()).toList());
                if (action.equals("rest")) {
                    throw new Miracle.Heresy("The game is running in " + which + ". Close it first: a running game"
                            + " would save over the world you rest at.");
                }
                if (!anyway) {
                    throw new Miracle.Heresy("The game is running in " + which + ", and still writing to it: a copy"
                            + " now may be torn. Close it first, or light it anyway: miracle " + voice.command()
                            + " --anyway (after /save-all, it's usually fine).");
                }
                System.out.println("  the game is running in " + which + ": lighting anyway, as asked");
            }
        }
        return switch (action) {
            case "light" -> light(p, sides, voice);
            case "list" -> list(p, sides, voice);
            case "rest" -> rest(p, sides, words.size() > 1 ? words.get(1) : null, voice);
            default -> throw new Miracle.Heresy("bonfire what? light, list or rest");
        };
    }

    /**
     * The worlds a running game has open. The game holds a lock on each open world's
     * {@code session.lock}, which another process sees: if we can't take it, the game has it.
     */
    static List<Path> inUse(List<Path> sides) throws IOException {
        List<Path> busy = new ArrayList<>();
        for (Path side : sides) {
            Path w = world(side);
            List<Path> worlds;
            if (side.getFileName().toString().startsWith("client")) {
                try (Stream<Path> s = Files.list(w)) {
                    worlds = s.filter(Files::isDirectory).sorted().toList();
                }
            } else {
                worlds = List.of(w);
            }
            for (Path world : worlds) {
                if (locked(world.resolve("session.lock"))) {
                    busy.add(world);
                }
            }
        }
        return busy;
    }

    private static boolean locked(Path lock) {
        if (!Files.isRegularFile(lock)) {
            return false;
        }
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock l = ch.tryLock();
            if (l == null) {
                return true;
            }
            l.release();
            return false;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;       // can't tell (read-only, say): don't stand in the way
        }
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
            Path to = fresh(bonfires(side), name);
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
            Path keep = fresh(bonfires(side), LocalDateTime.now().format(STAMP) + "_before-rest");
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

    /** dir/name, or dir/name-2, -3... if a bonfire was already lit that second. */
    private static Path fresh(Path dir, String name) {
        Path p = dir.resolve(name);
        for (int i = 2; Files.exists(p); i++) {
            p = dir.resolve(name + "-" + i);
        }
        return p;
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
