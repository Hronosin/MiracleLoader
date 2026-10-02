package io.github.hronosin.miracle.cli;

import io.github.hronosin.miracle.MiniToml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A mod project: {@code miracle.mod.toml} (what the loader reads) plus
 * {@code miracle.project.toml} (what the toolchain reads).
 *
 * <pre>
 * minecraft = "26.2"                 # written and compiled against (unobfuscated: readable names)
 * targets = ["26.*", "1.21.11"]     # checked by miracle bake; obfuscated ones get a baked variant.
 *                                  # Patterns (26.*, >=1.21.11, latest): see Targets
 * against = ["libs/other-mod.jar"]  # other mods' jars to compile against (for entangles bridges);
 *                                  # not packed into yours; pray puts the mods among them in run/mods
 * </pre>
 */
record Project(Path dir, String id, String name, String version, String minecraft, List<String> targets,
               List<String> depends, List<Path> against) {

    static final String PROJECT_FILE = "miracle.project.toml";
    static final String MOD_FILE = "miracle.mod.toml";

    /** The project in {@code start} or any parent folder. */
    static Project find(Path start) throws IOException {
        for (Path d = start.toAbsolutePath(); d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(PROJECT_FILE))) {
                return load(d);
            }
        }
        throw new Miracle.Heresy("No " + PROJECT_FILE + " here or in any parent folder. "
                + "Start one with: miracle genesis my-mod");
    }

    static Project load(Path dir) throws IOException {
        Map<String, Object> project = toml(dir.resolve(PROJECT_FILE));
        Map<String, Object> mod = toml(dir.resolve(MOD_FILE));
        String minecraft = str(project, "minecraft", PROJECT_FILE);
        List<String> targets = project.get("targets") instanceof List<?> l
                ? l.stream().map(Object::toString).toList() : List.of(minecraft);
        List<String> depends = mod.get("depends") instanceof List<?> l
                ? l.stream().map(Object::toString).toList() : List.of();
        List<Path> against = new java.util.ArrayList<>();
        if (project.get("against") instanceof List<?> l) {
            for (Object o : l) {
                Path jar = dir.resolve(o.toString()).normalize();
                if (!Files.isRegularFile(jar)) {
                    throw new Miracle.Heresy(PROJECT_FILE + ": against = [..., \"" + o + "\"], but there's no such file."
                            + " Put the other mod's jar there (libs/ is a good place).");
                }
                against.add(jar);
            }
        }
        return new Project(dir, str(mod, "id", MOD_FILE), mod.getOrDefault("name", mod.get("id")).toString(),
                mod.getOrDefault("version", "0.0.0").toString(), minecraft, targets, depends, List.copyOf(against));
    }

    /** True if miracle.mod.toml says depends = ["miracle-toolchain", ...], or on Event Horizon, which brings it. */
    boolean usesToolchain() {
        return usesHorizon() || depends.stream().anyMatch(d -> d.strip().matches("miracle-toolchain\\s*(>=.*)?"));
    }

    /** True if miracle.mod.toml says depends = ["event-horizon", ...]. */
    boolean usesHorizon() {
        return depends.stream().anyMatch(d -> d.strip().matches("event-horizon\\s*(>=.*)?"));
    }

    Path buildDir() {
        return dir.resolve("build");
    }

    Path jar() {
        return buildDir().resolve(id + "-" + version + ".jar");
    }

    private static Map<String, Object> toml(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new Miracle.Heresy("Missing " + file);
        }
        try {
            return MiniToml.parse(Files.readString(file));
        } catch (MiniToml.ParseException e) {
            throw new Miracle.Heresy(file.getFileName() + ": " + e.getMessage());
        }
    }

    private static String str(Map<String, Object> toml, String key, String file) {
        if (!(toml.get(key) instanceof String s) || s.isBlank()) {
            throw new Miracle.Heresy(file + " needs " + key + " = \"...\"");
        }
        return s;
    }
}
