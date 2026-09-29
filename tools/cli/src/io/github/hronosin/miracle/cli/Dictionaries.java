package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code miracle dictionary <version|pattern>...} (alias {@code mappings}): fetches what baking
 * needs for those versions (the client jar, and Mojang's mappings where the version is
 * obfuscated) into the cache. {@code bake} does this by itself for a project's targets; this is
 * for build scripts and for the curious. {@code --list} shows what's cached.
 */
final class Dictionaries {

    private Dictionaries() {
    }

    static int run(List<String> args) throws IOException {
        if (args.remove("--list") || args.isEmpty()) {
            return list();
        }
        List<String> versions = Targets.expand(args);
        for (String v : versions) {
            Mojang.Version mv = Mojang.version(v);
            Path d = Mojang.dictionary(mv);
            System.out.println("  " + v + (mv.obfuscated() ? "  obfuscated, with mappings" : "  unobfuscated") + "  " + d);
        }
        System.out.println(versions.size() + " dictionar" + (versions.size() == 1 ? "y" : "ies") + " ready.");
        return 0;
    }

    private static int list() throws IOException {
        Path root = Mojang.dictionaries();
        if (!Files.isDirectory(root)) {
            System.out.println("No dictionaries yet. They arrive on the first bake, or: miracle dictionary 26.* 1.21.11");
            return 0;
        }
        try (Stream<Path> s = Files.list(root)) {
            List<Path> dirs = s.filter(d -> Files.isRegularFile(d.resolve("client.jar")))
                    .sorted((a, b) -> Targets.compare(b.getFileName().toString(), a.getFileName().toString())).toList();
            for (Path d : dirs) {
                boolean obf = Files.isRegularFile(d.resolve("mappings.txt"));
                System.out.println("  " + d.getFileName() + (obf ? "  obfuscated" : "") + "  (" + Rituals.human(Rituals.size(d)) + ")");
            }
            if (dirs.isEmpty()) {
                System.out.println("No dictionaries yet.");
            }
        }
        return 0;
    }
}
