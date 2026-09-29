package io.github.hronosin.miracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code miracle.lock}: what every mod patched, pinned. Each start compares against it, so a mod
 * update that starts patching something new (or stops, or starts cancelling where it used to
 * only read) shows up as a diff, in the log and in version control, instead of as a mystery.
 *
 * <p>Modes, by {@code -Dmiracle.lock}:
 * <ul>
 *   <li>{@code update} (default): print what changed, then pin the new state;</li>
 *   <li>{@code strict}: refuse to start while anything changed; delete the file (or run once with
 *       {@code update}) to accept it. For servers whose owners read diffs;</li>
 *   <li>{@code off}: no lock at all.</li>
 * </ul>
 * The file sits next to {@code mods/}, or wherever {@code -Dmiracle.lockFile} says.
 */
final class MiracleLock {

    /** One pinned state: the game it was pinned on, and per mod its version and what it patches. */
    record State(String game, Map<String, Pinned> mods) {
    }

    record Pinned(String version, List<String> patches) {
    }

    private MiracleLock() {
    }

    /**
     * Compares, reports, pins. Throws MiracleFailure in strict mode when something changed.
     *
     * @param now the state of this start: mod id to its version and patch lines
     */
    static void check(Path file, String mode, State now) {
        mode = mode.toLowerCase(Locale.ROOT);
        if (mode.equals("off")) {
            return;
        }
        if (!mode.equals("update") && !mode.equals("strict")) {
            throw new MiracleFailure("-Dmiracle.lock=" + mode + ": that's not a mode. update, strict or off.");
        }
        State before;
        try {
            before = Files.isRegularFile(file) ? read(Files.readString(file, StandardCharsets.UTF_8)) : null;
        } catch (IOException | RuntimeException e) {
            Log.warn("miracle.lock: couldn't read " + file + " (" + e.getMessage() + "); pinning afresh.");
            before = null;
        }
        if (before == null) {
            write(file, now);
            Log.info("miracle.lock: pinned what " + now.mods().size() + " mod(s) patch, in " + file
                    + ". Next time, changes show up as a diff.");
            return;
        }
        if (!before.game().equals(now.game())) {
            write(file, now);
            Log.info("miracle.lock: it was pinned on " + before.game() + ", this is " + now.game()
                    + ". Other game, other names: pinned afresh.");
            return;
        }
        List<String> diff = diff(before, now);
        if (diff.isEmpty()) {
            if (!render(before).equals(render(now))) {
                write(file, now); // versions moved on, patches didn't: nothing to say, but keep it current
            }
            Log.info("miracle.lock: every mod patches exactly what it did when pinned.");
            return;
        }
        String report = "miracle.lock: what the mods patch has changed since it was pinned:\n    "
                + String.join("\n    ", diff);
        if (mode.equals("strict")) {
            throw new MiracleFailure(report + "\n\nmiracle.lock is strict (-Dmiracle.lock=strict), so nothing starts until"
                    + " someone looks at this. To accept it, delete " + file + " or start once with -Dmiracle.lock=update.");
        }
        Log.warn(report);
        write(file, now);
        Log.warn("miracle.lock: pinned the new state. If that wasn't expected, the mods above are where to look.");
    }

    /** Human lines describing what changed, empty if the patches are the same. */
    static List<String> diff(State before, State now) {
        List<String> out = new ArrayList<>();
        TreeSet<String> ids = new TreeSet<>(before.mods().keySet());
        ids.addAll(now.mods().keySet());
        for (String id : ids) {
            Pinned was = before.mods().get(id);
            Pinned is = now.mods().get(id);
            List<String> old = was == null ? List.of() : was.patches();
            List<String> neu = is == null ? List.of() : is.patches();
            if (old.equals(neu)) {
                continue;
            }
            String head;
            if (was == null) {
                head = id + " " + is.version() + " (new)";
            } else if (is == null) {
                head = id + " " + was.version() + " (gone)";
            } else if (!was.version().equals(is.version())) {
                head = id + " " + was.version() + " -> " + is.version();
            } else {
                head = id + " " + is.version() + " (same version, different patches: a setting?)";
            }
            out.add(head);
            for (String line : old) {
                if (!neu.contains(line)) {
                    out.add("  - " + line);
                }
            }
            for (String line : neu) {
                if (!old.contains(line)) {
                    out.add("  + " + line);
                }
            }
        }
        return out;
    }

    static String render(State s) {
        StringBuilder sb = new StringBuilder();
        sb.append("# miracle.lock: what every mod patched when this was pinned. MiracleLoader compares each\n");
        sb.append("# start against it and prints what changed. Keep it in version control and a mod update that\n");
        sb.append("# patches something new shows up as a diff. Delete it to pin afresh.\n");
        sb.append("game ").append(s.game()).append('\n');
        for (var e : new TreeMap<>(s.mods()).entrySet()) {
            sb.append("mod ").append(e.getKey()).append(' ').append(e.getValue().version()).append('\n');
            for (String p : e.getValue().patches()) {
                sb.append("  ").append(p).append('\n');
            }
        }
        return sb.toString();
    }

    static State read(String text) {
        String game = null;
        Map<String, Pinned> mods = new LinkedHashMap<>();
        String current = null;
        String version = null;
        List<String> patches = null;
        for (String raw : text.split("\n")) {
            if (raw.isBlank() || raw.startsWith("#")) {
                continue;
            }
            if (raw.startsWith("game ")) {
                game = raw.substring(5).strip();
            } else if (raw.startsWith("mod ")) {
                if (current != null) {
                    mods.put(current, new Pinned(version, List.copyOf(patches)));
                }
                String[] parts = raw.substring(4).strip().split(" ", 2);
                current = parts[0];
                version = parts.length > 1 ? parts[1] : "?";
                patches = new ArrayList<>();
            } else if (raw.startsWith("  ") && patches != null) {
                patches.add(raw.strip());
            } else {
                throw new IllegalArgumentException("line doesn't belong in a lock: " + raw);
            }
        }
        if (current != null) {
            mods.put(current, new Pinned(version, List.copyOf(patches)));
        }
        if (game == null) {
            throw new IllegalArgumentException("no 'game' line");
        }
        return new State(game, mods);
    }

    private static void write(Path file, State s) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Files.writeString(file, render(s), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("miracle.lock: couldn't write " + file + ": " + e.getMessage());
        }
    }
}
