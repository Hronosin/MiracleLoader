package io.github.hronosin.miracle.cli;

import io.github.hronosin.miracle.MiniToml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * {@code miracle zandatsu [jar]} (alias {@code inspect}): Blade Mode for mod jars. Cuts one open
 * and lays out what's inside: who it claims to be, what it patches, which parts of the library
 * it uses, which versions it was baked for. Reads the jar; runs nothing.
 */
final class Zandatsu {

    private static final String RGCT = "io/github/hronosin/miracle/rgct/Rgct";
    private static final String LIBRARY = "io/github/hronosin/miracle/toolchain/";
    private static final Set<String> PUBLIC = Set.of("Omens", "Events", "Blessings", "Tweaks", "Sermons",
            "ChatCommands", "Commandments", "Config", "Scripture", "Resources", "Proclamations", "Notices",
            "Creation", "Content", "Being", "Shrine", "Vision", "Telepathy", "Networking", "Gestures", "Keybinds", "Communion", "Handshake");

    private Zandatsu() {
    }

    static int run(Path dir, String jarArg) throws IOException {
        Path jar;
        if (jarArg != null) {
            jar = Path.of(jarArg);
        } else {
            jar = Project.find(dir).jar();
            if (!Files.isRegularFile(jar)) {
                throw new Miracle.Heresy("Nothing to cut: " + jar + " doesn't exist. Bake first, or name a jar.");
            }
        }
        if (!Files.isRegularFile(jar)) {
            throw new Miracle.Heresy("No jar at " + jar + ".");
        }

        System.out.println("BLADE MODE. Cutting " + jar.getFileName() + " (" + Rituals.human(Files.size(jar)) + ")");
        Set<String> targets = new TreeSet<>();
        Set<String> library = new TreeSet<>();
        Set<String> oshi = new TreeSet<>();
        Set<String> baked = new TreeSet<>();
        Set<String> fallbacks = new TreeSet<>();
        int classes = 0;
        Map<String, Object> mod = null;
        String bake = null;
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry e : jf.stream().toList()) {
                String n = e.getName();
                if (n.equals("miracle.mod.toml")) {
                    try {
                        mod = MiniToml.parse(read(jf, e));
                    } catch (MiniToml.ParseException ex) {
                        System.out.println("  miracle.mod.toml is broken: " + ex.getMessage());
                    }
                } else if (n.equals("META-INF/miracle/bake.toml")) {
                    bake = read(jf, e).lines().filter(l -> !l.startsWith("#") && !l.isBlank())
                            .reduce((a, b) -> a + "; " + b).orElse("");
                } else if (n.startsWith("META-INF/miracle/baked/") && n.split("/").length > 3) {
                    baked.add(n.split("/")[3]);
                } else if (n.startsWith("META-INF/miracle/fallback/") && n.split("/").length > 3) {
                    fallbacks.add(n.split("/")[3]);
                } else if (n.endsWith(".class") && !n.startsWith("META-INF/")) {
                    classes++;
                    byte[] bytes;
                    try (InputStream in = jf.getInputStream(e)) {
                        bytes = in.readAllBytes();
                    }
                    cut(ClassFile.of().parse(bytes), targets, library, oshi);
                }
            }
        }

        if (mod == null) {
            System.out.println("  No miracle.mod.toml: this isn't a Miracle mod. Nothing worth taking.");
            return 1;
        }
        System.out.println("  Name:       " + mod.getOrDefault("name", mod.get("id")) + " (" + mod.get("id") + " "
                + mod.getOrDefault("version", "0.0.0") + ")");
        System.out.println("  Spine:      " + mod.getOrDefault("entrypoint", "none (a library)"));
        if (mod.get("icon") instanceof String icon) {
            System.out.println("  Face:       " + icon + (hasEntry(jar, icon) ? "" : "  (missing from the jar!)"));
        }
        if (mod.get("depends") != null) {
            System.out.println("  Depends on: " + mod.get("depends"));
        }
        System.out.println("  Classes:    " + classes);
        System.out.println("  Patches:    " + (targets.isEmpty() ? "nothing directly" : String.join(", ", targets)));
        if (!library.isEmpty()) {
            System.out.println("  Library:    " + String.join(", ", library));
        }
        if (!oshi.isEmpty()) {
            System.out.println("  OSHI:       brings its own hooks (" + String.join(", ", oshi) + ") on " + oshi.size()
                    + " class(es); RGCT can't merge those");
        }
        System.out.println("  Baked for:  " + (baked.isEmpty() ? "no obfuscated versions" : String.join(", ", baked)));
        if (!fallbacks.isEmpty()) {
            System.out.println("  Fallbacks:  " + String.join(", ", fallbacks));
        }
        if (bake != null) {
            System.out.println("  bake.toml:  " + bake);
        } else {
            System.out.println("  bake.toml:  none (never baked: fine on 26.x, blind on obfuscated versions)");
        }
        reach(jar);
        System.out.println("ZANDATSU! " + classes + " class(es) taken. Rules of Nature.");
        return 0;
    }

    /** RGCT targets: the string right before Rgct.target(...). Library parts: calls into it. */
    private static void cut(ClassModel cm, Set<String> targets, Set<String> library, Set<String> oshi) {
        for (MethodModel mm : cm.methods()) {
            var code = mm.code().orElse(null);
            if (code == null) {
                continue;
            }
            String lastString = null;
            for (CodeElement el : code) {
                if (!(el instanceof Instruction ins)) {
                    continue;
                }
                if (ins instanceof InvokeInstruction inv) {
                    String owner = inv.owner().asInternalName();
                    String name = inv.name().stringValue();
                    if (owner.equals(RGCT) && name.equals("target") && lastString != null) {
                        targets.add(lastString.substring(lastString.lastIndexOf('.') + 1));
                    } else if (owner.equals(RGCT + "$ClassTarget") && (name.equals("raw") || name.equals("rawBytes"))) {
                        oshi.add(name);
                    } else if (owner.startsWith(LIBRARY) && !name.startsWith("<") && !owner.contains("$")) {
                        String part = owner.substring(LIBRARY.length());
                        if (PUBLIC.contains(part)) {
                            library.add(part + "." + name);
                        }
                    }
                }
                lastString = ins instanceof ConstantInstruction c && c.constantValue() instanceof String s ? s : null;
            }
        }
    }

    private static boolean hasEntry(Path jar, String name) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getJarEntry(name.replaceFirst("^/+", "")) != null;
        }
    }

    private static String read(JarFile jf, JarEntry e) throws IOException {
        try (InputStream in = jf.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The label: what the code reaches for outside the game, and where. */
    static void reach(Path jar) throws IOException {
        java.util.List<io.github.hronosin.miracle.Reach.Find> finds = io.github.hronosin.miracle.Reach.scan(jar);
        var by = io.github.hronosin.miracle.Reach.byKind(finds);
        if (by.isEmpty()) {
            System.out.println("  Reaches for: nothing outside the game (no processes, native code, network, file writes,"
                    + " classes from bytes, Unsafe, private members, raw graphics or exits named in its code)");
            return;
        }
        System.out.println("  Reaches for (what its code names; reflection can hide more, and naming isn't misusing):");
        for (var e : by.entrySet()) {
            java.util.List<String> what = finds.stream().filter(f -> f.kind() == e.getKey()).map(io.github.hronosin.miracle.Reach.Find::what)
                    .distinct().limit(3).toList();
            java.util.List<String> where = e.getValue().stream().limit(3).toList();
            System.out.println("    " + (e.getKey().notable ? "! " : "  ") + e.getKey().says);
            System.out.println("        in " + String.join(", ", where) + (e.getValue().size() > 3 ? " and " + (e.getValue().size() - 3) + " more" : "")
                    + ": " + String.join(", ", what));
        }
    }
}
