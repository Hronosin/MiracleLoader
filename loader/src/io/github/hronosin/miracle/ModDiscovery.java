package io.github.hronosin.miracle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Finds mod jars in the mods folder and reads their {@code miracle.mod.toml}. */
final class ModDiscovery {

    static final String METADATA_FILE = "miracle.mod.toml";

    static final String BAKE_INFO = "META-INF/miracle/bake.toml";

    /**
     * {@code entrypoint} is null for a library mod: it only brings classes for other mods.
     * {@code bake} is null for a mod that never went through miracle-bake.
     */
    record ModInfo(String id, String name, String version, String entrypoint, List<String> authors, Path jar,
                   BakeInfo bake, List<Dependencies.Requirement> depends, String icon) {
        String display() {
            return name + " (" + id + " " + version + ")";
        }

        boolean library() {
            return entrypoint == null;
        }
    }

    /** Written by miracle-bake: versions with a ready-made variant, and unobfuscated versions it was checked on. */
    record BakeInfo(List<String> baked, List<String> checked) {
    }

    static final class DiscoveryException extends Exception {
        DiscoveryException(String msg) {
            super(msg);
        }
    }

    private ModDiscovery() {
    }

    /** Mods sorted by id, so load order never depends on the file system's mood. */
    static List<ModInfo> discover(Path modsDir) throws IOException, DiscoveryException {
        if (!Files.isDirectory(modsDir)) {
            Log.info("No mods folder at " + modsDir.toAbsolutePath() + ", creating an empty one.");
            Files.createDirectories(modsDir);
            return List.of();
        }

        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }

        List<ModInfo> mods = new ArrayList<>();
        Map<String, Path> seen = new HashMap<>();
        for (Path jar : jars) {
            ModInfo info = read(jar);
            if (info == null) {
                continue;
            }
            Path clash = seen.putIfAbsent(info.id(), jar);
            if (clash != null) {
                throw new DiscoveryException("Two mods claim the id '" + info.id() + "': "
                        + clash.getFileName() + " and " + jar.getFileName() + ". Delete one of them.");
            }
            mods.add(info);
        }
        mods.sort(Comparator.comparing(ModInfo::id));
        return mods;
    }

    private static ModInfo read(Path jar) throws IOException, DiscoveryException {
        String text;
        String bakeText = null;
        try (JarFile jf = new JarFile(jar.toFile())) {
            var entry = jf.getJarEntry(METADATA_FILE);
            if (entry == null) {
                Log.warn(jar.getFileName() + " has no " + METADATA_FILE + ": not a Miracle mod, skipping.");
                return null;
            }
            try (InputStream in = jf.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            var bakeEntry = jf.getJarEntry(BAKE_INFO);
            if (bakeEntry != null) {
                try (InputStream in = jf.getInputStream(bakeEntry)) {
                    bakeText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }

        BakeInfo bake = null;
        if (bakeText != null) {
            try {
                Map<String, Object> b = MiniToml.parse(bakeText);
                bake = new BakeInfo(optionalList(b, "baked"), optionalList(b, "checked"));
            } catch (MiniToml.ParseException e) {
                throw new DiscoveryException(jar.getFileName() + ": broken " + BAKE_INFO + ", " + e.getMessage());
            }
        }

        Map<String, Object> toml;
        try {
            toml = MiniToml.parse(text);
        } catch (MiniToml.ParseException e) {
            throw new DiscoveryException(jar.getFileName() + ": broken " + METADATA_FILE + ", " + e.getMessage());
        }

        String id = requireString(toml, "id", jar);
        if (!id.matches("[a-z][a-z0-9_-]{1,63}")) {
            throw new DiscoveryException(jar.getFileName() + ": mod id '" + id
                    + "' must be 2-64 chars of a-z, 0-9, '-' or '_', starting with a letter");
        }
        if (id.equals("miracle")) {
            throw new DiscoveryException(jar.getFileName() + ": the id 'miracle' belongs to the loader itself."
                    + " Pick another; humility is a virtue.");
        }
        // No entrypoint: a library, which only brings classes for other mods. Say so explicitly
        // (library = true), so a forgotten entrypoint doesn't silently turn a mod into a library.
        boolean library = Boolean.TRUE.equals(toml.get("library"));
        String entrypoint = library ? null : requireString(toml, "entrypoint", jar);
        String name = optionalString(toml, "name", id);
        String version = optionalString(toml, "version", "0.0.0");
        List<String> authors = optionalList(toml, "authors");
        List<Dependencies.Requirement> depends = new ArrayList<>();
        for (String d : optionalList(toml, "depends")) {
            try {
                depends.add(Dependencies.Requirement.parse(d));
            } catch (IllegalArgumentException e) {
                throw new DiscoveryException(jar.getFileName() + ": " + METADATA_FILE + ", depends: " + e.getMessage());
            }
        }
        String icon = optionalString(toml, "icon", null);
        if (icon != null) {
            icon = icon.replaceFirst("^/+", "");
            try (JarFile jf = new JarFile(jar.toFile())) {
                if (jf.getJarEntry(icon) == null) {
                    Log.warn(jar.getFileName() + ": icon = \"" + icon + "\", but the jar has no such file. Faceless, then.");
                    icon = null;
                }
            }
        }
        return new ModInfo(id, name, version, entrypoint, authors, jar, bake, List.copyOf(depends), icon);
    }

    private static String requireString(Map<String, Object> toml, String key, Path jar) throws DiscoveryException {
        Object v = toml.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new DiscoveryException(jar.getFileName() + ": " + METADATA_FILE + " needs " + key + " = \"...\"");
        }
        return s;
    }

    private static String optionalString(Map<String, Object> toml, String key, String fallback) {
        return toml.get(key) instanceof String s && !s.isBlank() ? s : fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> optionalList(Map<String, Object> toml, String key) {
        return toml.get(key) instanceof List<?> l ? (List<String>) l : List.of();
    }
}
