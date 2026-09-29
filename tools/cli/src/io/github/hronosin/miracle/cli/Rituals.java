package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Commands nobody needs. Forge's toolchain has everything you need and plenty you don't; this is
 * the "plenty you don't" part, done on purpose. None of them touch anything outside your project
 * and cache, and only {@code exorcise --yes} deletes anything at all.
 *
 * <p>{@code MIRACLE_IMPATIENT=1} skips every pause (for scripts, tests, and the impatient).
 */
final class Rituals {

    private Rituals() {
    }

    // --- miracle gradle -------------------------------------------------------------------------

    /** A faithful reenactment of a Gradle build. It builds nothing. */
    static int gradle(boolean really) throws InterruptedException {
        long scale = really ? 300_000 : 20_000; // total milliseconds: 5 minutes, or 20 seconds of the gist
        String[] phases = {
                "Starting a Gradle Daemon (subsequent builds will be faster)",
                "> Configure project :",
                "> Downloading https://maven.example.invalid/the-entire-internet-1.0.pom",
                "> Resolving dependencies of configuration ':minecraftDeobfuscatedRuntimeElementsForRemapping'",
                "> Remapping Minecraft (this may take a while)",
                "> Decompiling Minecraft (this will take a while)",
                "> Applying 4117 access transformers",
                "> Task :genSourcesWithTheOtherDecompiler",
                "> Task :compileJava",
                "> Task :reobfJar",
                "> Task :downloadAssets",
                "> Idle",
        };
        long start = System.nanoTime();
        for (int i = 0; i < phases.length; i++) {
            System.out.println(phases[i]);
            int pct = (i + 1) * 100 / phases.length;
            // The elapsed time Gradle would have shown: roughly fifteen times the real one.
            long fake = (System.nanoTime() - start) / 1_000_000 * (really ? 1 : 15) + i * 13_000L;
            System.out.println(bar(pct) + " " + pct + "% " + (i < 4 ? "CONFIGURING" : "EXECUTING")
                    + " [" + (fake / 60_000) + "m " + (fake / 1000 % 60) + "s]");
            pause(scale / phases.length);
        }
        System.out.println("""

                Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.0.

                BUILD SUCCESSFUL in 5m 3s
                47 actionable tasks: 47 executed

                ...just kidding. There is no Gradle here, and nothing was built.
                The real thing takes a few seconds: miracle bake
                Forge hammers. Fabric stitches. Miracle just happens.""");
        return 0;
    }

    private static String bar(int pct) {
        int filled = pct * 13 / 100;
        return "<" + "=".repeat(filled) + "-".repeat(13 - filled) + ">";
    }

    // --- miracle forge --------------------------------------------------------------------------

    static int forge() {
        System.out.println("""
                'forge' is not a miracle command. Did you mean:

                    miracle bake      (compile, check, bake: seconds)
                    miracle gradle    (if you miss the waiting)

                Forge hammers. Fabric stitches. Miracle just happens.""");
        return 1;
    }

    // --- miracle heresy -------------------------------------------------------------------------

    private static final Pattern HERESIES = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?(net\\.minecraftforge|net\\.neoforged|net\\.fabricmc|org\\.quiltmc"
                    + "|org\\.spongepowered\\.asm|dev\\.architectury)\\S*;", Pattern.MULTILINE);

    /** Hunts for imports from other loaders in the project's sources, and prescribes penance. */
    static int heresy(Path dir) throws IOException {
        Path root = sourcesRoot(dir);
        List<String> found = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(f);
                Matcher m = HERESIES.matcher(text);
                while (m.find()) {
                    int line = (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                    found.add(root.relativize(f) + ":" + line + "  " + m.group().strip()
                            + "   <- " + penance(m.group(2)));
                }
            }
        }
        if (found.isEmpty()) {
            System.out.println("No heresy found in " + root + ". Suspicious, but congratulations.");
            return 0;
        }
        System.out.println("The Inquisition has found " + found.size() + " heres" + (found.size() == 1 ? "y" : "ies") + ":");
        found.forEach(h -> System.out.println("  " + h));
        System.out.println("Penance: " + found.size() + " Hail Mar" + (found.size() == 1 ? "y" : "ies")
                + ", and a rewrite with RGCT.");
        return 1;
    }

    private static String penance(String pkg) {
        return switch (pkg) {
            case "net.minecraftforge" -> "Forge. The hammer has fallen";
            case "net.neoforged" -> "NeoForge. New hammer, same anvil";
            case "net.fabricmc" -> "Fabric. Stitched, not blessed";
            case "org.quiltmc" -> "Quilt. Fabric, with extra patches";
            case "org.spongepowered.asm" -> "Mixin. Cringe, as foretold";
            default -> "Architectury. Heresy on every platform at once";
        };
    }

    // --- miracle fast ---------------------------------------------------------------------------

    /** The Great Lent of the build: forty seconds of doing nothing, with feeling. */
    static int fast(int seconds) throws InterruptedException {
        System.out.println("The fast begins. No builds, no downloads, no Gradle. Reflect on your code.");
        for (int left = seconds; left > 0; left--) {
            System.out.print("\r  " + left + "s of abstinence left   ");
            pause(1000);
        }
        System.out.println("\rThe fast is over. You have built nothing, and you are better for it.");
        return 0;
    }

    // --- miracle tithe --------------------------------------------------------------------------

    /** Offers a tenth of your cache to the heavens. Deletes nothing; the heavens don't need disk. */
    static int tithe() throws IOException {
        long cache = size(Mojang.home());
        long tenth = cache / 10;
        System.out.println("Your cache: " + human(cache) + " at " + Mojang.home());
        System.out.println("A tithe of " + human(tenth) + " has been offered.");
        System.out.println("(Symbolically. Nothing was deleted. For actual cleaning: miracle exorcise)");
        return 0;
    }

    // --- miracle exorcise -----------------------------------------------------------------------

    /**
     * Casts out build output, logs and crash reports from the project. Worlds, configs and your
     * sources are never touched. Without {@code --yes} it only lists what it would cast out.
     */
    static int exorcise(Path dir, boolean yes) throws IOException {
        Project p = Project.find(dir);
        List<Path> demons = new ArrayList<>();
        if (Files.isDirectory(p.buildDir())) {
            demons.add(p.buildDir());
        }
        Path run = p.dir().resolve("run");
        if (Files.isDirectory(run)) {
            try (Stream<Path> s = Files.list(run)) {
                for (Path side : s.filter(Files::isDirectory).sorted().toList()) {
                    for (String junk : List.of("logs", "crash-reports", "debug")) {
                        if (Files.isDirectory(side.resolve(junk))) {
                            demons.add(side.resolve(junk));
                        }
                    }
                }
            }
        }
        if (demons.isEmpty()) {
            System.out.println("This project is clean. The priest leaves disappointed.");
            return 0;
        }
        long total = 0;
        for (Path d : demons) {
            long s = size(d);
            total += s;
            System.out.println("  " + p.dir().relativize(d) + "  (" + human(s) + ")");
        }
        if (!yes) {
            System.out.println(demons.size() + " demon(s), " + human(total) + ". Say the words to cast them out: "
                    + "miracle exorcise --yes");
            return 0;
        }
        for (Path d : demons) {
            Builder.wipe(d);
        }
        System.out.println("The power of Miracle compels you! " + human(total) + " cast out. Worlds and configs were spared.");
        return 0;
    }

    // --- shared ---------------------------------------------------------------------------------

    static void pause(long millis) throws InterruptedException {
        if (!"1".equals(System.getenv("MIRACLE_IMPATIENT"))) {
            Thread.sleep(millis);
        }
    }

    /** src/ of the project here or above, or this folder if there's no project. */
    static Path sourcesRoot(Path dir) throws IOException {
        try {
            Path src = Project.find(dir).dir().resolve("src");
            if (Files.isDirectory(src)) {
                return src;
            }
        } catch (Miracle.Heresy notAProject) {
            // just look where we are
        }
        return dir.toAbsolutePath().normalize();
    }

    static long size(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            long sum = 0;
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                sum += Files.size(f);
            }
            return sum;
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }

    /** Newest first. */
    static Comparator<Path> newestFirst() {
        return Comparator.comparing((Path p) -> {
            try {
                return Files.getLastModifiedTime(p);
            } catch (IOException e) {
                return java.nio.file.attribute.FileTime.fromMillis(0);
            }
        }).reversed();
    }
}
