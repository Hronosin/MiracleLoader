package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** {@code miracle confess}: lists everything wrong with your setup, then absolves you anyway. */
final class Confess {

    private final List<String> sins = new ArrayList<>();

    private Confess() {
    }

    static int run() {
        Confess c = new Confess();
        System.out.println("Forgive me, Father, for I have built mods. Let's see.\n");
        c.java();
        c.toolchain();
        c.cache();
        c.project();
        c.display();
        System.out.println();
        if (c.sins.isEmpty()) {
            System.out.println("No sins found. Go forth and mod.");
            return 0;
        }
        System.out.println(c.sins.size() + " sin(s) confessed:");
        c.sins.forEach(s -> System.out.println("  - " + s));
        System.out.println("Your sins are forgiven. They're still there, though.");
        return 1;
    }

    private void virtue(String what) {
        System.out.println("  [ok]  " + what);
    }

    private void sin(String what, String penance) {
        System.out.println("  [!!]  " + what);
        sins.add(what + ". " + penance);
    }

    private void java() {
        int v = Runtime.version().feature();
        String home = System.getProperty("java.home");
        if (v >= 25) {
            virtue("Java " + Runtime.version() + " (" + home + ")");
        } else {
            sin("Java " + v + " is running the toolchain; MiracleLoader needs 25", "Install JDK 25 and point JAVA_HOME at it");
        }
        if (javax.tools.ToolProvider.getSystemJavaCompiler() == null) {
            sin("this Java has no compiler (a JRE?)", "Install the full JDK (Fedora: java-25-openjdk-devel)");
        } else {
            virtue("javac available");
        }
    }

    private void toolchain() {
        try {
            virtue("MiracleLoader at " + Miracle.loaderJar());
        } catch (Miracle.Heresy h) {
            sin("loader jar not found", h.getMessage());
        }
        try {
            virtue("MiracleToolChain library at " + Miracle.toolchainJar());
        } catch (Miracle.Heresy h) {
            // Only a sin for projects that use it; ascetic mods do fine without.
            System.out.println("  (no MiracleToolChain library next to the toolchain: only --ascetic mods can be baked)");
        }
    }

    private void cache() {
        Path home = Mojang.home();
        virtue("cache at " + home + " (" + size(home) + ")");
        Path dicts = Mojang.dictionaries();
        try (Stream<Path> s = Files.isDirectory(dicts) ? Files.list(dicts) : Stream.empty()) {
            List<String> have = s.map(p -> p.getFileName() + (Files.isRegularFile(p.resolve("mappings.txt")) ? " (obfuscated)" : ""))
                    .sorted().toList();
            if (!have.isEmpty()) {
                virtue("dictionaries: " + String.join(", ", have));
            }
        } catch (IOException e) {
            sin("can't read " + dicts, e.getMessage());
        }
    }

    private void project() {
        Project p;
        try {
            p = Project.find(Path.of(""));
        } catch (Miracle.Heresy | IOException e) {
            System.out.println("  [--]  not inside a mod project (that's fine outside one)");
            return;
        }
        virtue("project " + p.id() + " " + p.version() + " in " + p.dir());
        if (p.usesToolchain()) {
            try {
                Miracle.toolchainJar();
                virtue("uses the MiracleToolChain library");
            } catch (Miracle.Heresy h) {
                sin("the project depends on miracle-toolchain, but the library isn't here", h.getMessage());
            }
        }
        try {
            Mojang.Version primary = Mojang.version(p.minecraft());
            if (primary.obfuscated()) {
                sin("minecraft = \"" + p.minecraft() + "\" is obfuscated", "Compile against 26.1+ and list " + p.minecraft() + " in targets");
            } else {
                virtue("compiles against Minecraft " + p.minecraft());
            }
            for (String t : p.targets()) {
                Mojang.Version v = Mojang.version(t);
                virtue("target " + t + (v.obfuscated() ? " (obfuscated: gets a baked variant)" : ""));
            }
        } catch (IOException e) {
            sin("can't look up the project's versions: " + e.getMessage(), "Check your internet connection, or the version names");
        }
        if (Files.isRegularFile(p.jar())) {
            virtue("last bake: " + p.dir().relativize(p.jar()));
        }
    }

    private void display() {
        if (System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null) {
            sin("no DISPLAY or WAYLAND_DISPLAY: 'pray client' has nowhere to open a window",
                    "Run from a desktop session, or use 'pray server' (or xvfb-run for the brave)");
        } else {
            virtue("a display to open the game window on");
        }
    }

    private static String size(Path dir) {
        if (!Files.isDirectory(dir)) {
            return "empty";
        }
        try (Stream<Path> s = Files.walk(dir)) {
            long bytes = s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
            return bytes / (1024 * 1024) + " MB";
        } catch (IOException e) {
            return "?";
        }
    }
}
