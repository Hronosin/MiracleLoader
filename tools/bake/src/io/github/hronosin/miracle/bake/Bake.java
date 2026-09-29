package io.github.hronosin.miracle.bake;

import io.github.hronosin.miracle.bake.Remapper.ModClass;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * miracle-bake: checks a mod against the dictionaries of several Minecraft versions at once and
 * bakes a ready-made variant for every obfuscated version that has everything the mod needs.
 *
 * <pre>
 * java -jar miracle-bake.jar \
 *     --native 26.2=minecraft-26.2-client.jar \
 *     --obf 1.21.11=minecraft-1.21.11-client.jar,client-1.21.11-mappings.txt \
 *     my-mod.jar [more-mods.jar...]
 * </pre>
 *
 * <p>The mod is compiled once, against an unobfuscated version (readable names). For every
 * {@code --native} version its references are only checked. For every {@code --obf} version they
 * are checked and translated with that version's dictionary, and the result goes into the jar
 * under {@code META-INF/miracle/baked/<version>/}. The loader picks the variant that matches the
 * running game; nothing is remapped at runtime.
 *
 * <p>Jars are rewritten in place. Mapping files are only read, never copied into the mod.
 */
public final class Bake {

    static final String BAKED_DIR = "META-INF/miracle/baked/";
    static final String BAKE_INFO = "META-INF/miracle/bake.toml";
    static final String NAMES_FILE = "rgct-names.txt";

    private Bake() {
    }

    public static void main(String[] args) throws Exception {
        List<VersionDict> versions = new ArrayList<>();
        List<Path> mods = new ArrayList<>();
        boolean strict = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--native" -> {
                    String[] kv = split(args[++i], "--native");
                    versions.add(VersionDict.ofNative(kv[0], Path.of(kv[1])));
                }
                case "--obf" -> {
                    String[] kv = split(args[++i], "--obf");
                    String[] files = kv[1].split(",", 2);
                    if (files.length != 2) {
                        usage("--obf needs VERSION=GAME_JAR,MAPPINGS_TXT");
                    }
                    versions.add(VersionDict.ofObfuscated(kv[0], Path.of(files[0]), Path.of(files[1])));
                }
                case "--strict" -> strict = true;
                case "-h", "--help" -> usage(null);
                default -> {
                    if (args[i].startsWith("--")) {
                        usage("unknown option " + args[i]);
                    }
                    mods.add(Path.of(args[i]));
                }
            }
        }
        if (versions.isEmpty() || mods.isEmpty()) {
            usage("need at least one version and one mod jar");
        }

        Set<String> universe = new HashSet<>();
        versions.forEach(v -> universe.addAll(v.classes.keySet()));

        boolean anyFailed = false;
        for (Path mod : mods) {
            anyFailed |= !bake(mod, versions, universe);
        }
        if (anyFailed && strict) {
            System.exit(1);
        }
    }

    /** Returns false if some version couldn't be baked or checked. */
    static boolean bake(Path jar, List<VersionDict> versions, Set<String> universe) throws IOException {
        Map<String, byte[]> entries = readJar(jar);
        entries.keySet().removeIf(n -> n.startsWith("META-INF/miracle/"));

        ClassFile cf = ClassFile.of();
        Map<String, ClassModel> classes = new LinkedHashMap<>();
        Map<String, ModClass> modClasses = new LinkedHashMap<>();
        for (var e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && !e.getKey().startsWith("META-INF/")) {
                ClassModel cm = cf.parse(e.getValue());
                String name = cm.thisClass().asInternalName();
                classes.put(e.getKey(), cm);
                Set<String> methods = new HashSet<>();
                for (MethodModel m : cm.methods()) {
                    methods.add(m.methodName().stringValue() + m.methodType().stringValue());
                }
                Set<String> fields = new HashSet<>();
                for (FieldModel f : cm.fields()) {
                    fields.add(f.fieldName().stringValue() + ":" + f.fieldType().stringValue());
                }
                modClasses.put(name, new ModClass(name,
                        cm.superclass().map(ClassEntry::asInternalName).orElse(null),
                        cm.interfaces().stream().map(ClassEntry::asInternalName).toList(),
                        methods, fields, cm.flags().has(AccessFlag.INTERFACE)));
            }
        }

        String modName = jar.getFileName().toString();
        System.out.println("[bake] " + modName + " (" + classes.size() + " classes)");
        List<String> baked = new ArrayList<>();
        List<String> checkedOk = new ArrayList<>();
        Map<String, byte[]> out = new LinkedHashMap<>(entries);
        boolean allOk = true;

        for (VersionDict v : versions) {
            Remapper r = new Remapper(v, universe, modClasses, v.hierarchy());
            Map<String, byte[]> variant = new LinkedHashMap<>();
            for (var e : classes.entrySet()) {
                variant.put(e.getKey(), r.remap(e.getValue()));
            }
            String label = String.format("         %-9s %-14s", v.version, v.obfuscated ? "obfuscated" : "unobfuscated");
            if (!r.missing.isEmpty()) {
                allOk = false;
                System.out.println(label + "MISSING " + r.missing.size() + ":");
                r.missing.forEach(m -> System.out.println("             - " + m));
                System.out.println("             " + (v.obfuscated ? "not baked." : "not marked as checked.")
                        + " Drop this version, or add a fallback for what's missing.");
                continue;
            }
            String extra = r.unverified.isEmpty() ? "" : ", " + r.unverified.size() + " into libraries unchecked";
            if (v.obfuscated) {
                variant.forEach((path, bytes) -> out.put(BAKED_DIR + v.version + "/" + path, bytes));
                StringBuilder names = new StringBuilder("# RGCT targets in this variant: jar name = readable name\n");
                r.targetNames.forEach((k, n) -> names.append(k).append(" = ").append(n).append('\n'));
                out.put(BAKED_DIR + v.version + "/" + NAMES_FILE, names.toString().getBytes(StandardCharsets.UTF_8));
                baked.add(v.version);
                System.out.println(label + "ok, " + r.checked.size() + " game references translated" + extra + ", baked");
            } else {
                checkedOk.add(v.version);
                System.out.println(label + "ok, " + r.checked.size() + " game references present" + extra);
            }
        }

        out.put(BAKE_INFO, ("# Written by miracle-bake. Which game versions this jar is ready for.\n"
                + "baked = " + tomlList(baked) + "\n"
                + "checked = " + tomlList(checkedOk) + "\n").getBytes(StandardCharsets.UTF_8));
        writeJar(jar, out);
        return allOk;
    }

    private static String tomlList(List<String> items) {
        return items.stream().map(s -> "\"" + s + "\"").toList().toString();
    }

    private static Map<String, byte[]> readJar(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(e)) {
                    entries.put(e.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        Path tmp = jar.resolveSibling(jar.getFileName() + ".baking");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp))) {
            Set<String> dirs = new HashSet<>();
            // The manifest goes first, as JarInputStream expects.
            List<String> names = new ArrayList<>(entries.keySet());
            names.sort((a, b) -> Boolean.compare(!a.equals("META-INF/MANIFEST.MF"), !b.equals("META-INF/MANIFEST.MF")));
            for (String name : names) {
                for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                    String dir = name.substring(0, slash + 1);
                    if (dirs.add(dir)) {
                        out.putNextEntry(new JarEntry(dir));
                        out.closeEntry();
                    }
                }
                out.putNextEntry(new JarEntry(name));
                out.write(entries.get(name));
                out.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String[] split(String arg, String option) {
        int eq = arg.indexOf('=');
        if (eq <= 0) {
            usage(option + " needs VERSION=...");
        }
        return new String[] {arg.substring(0, eq), arg.substring(eq + 1)};
    }

    private static void usage(String error) {
        if (error != null) {
            System.err.println("miracle-bake: " + error);
        }
        System.err.println("""
                usage: miracle-bake [--strict] VERSIONS... MOD.jar...
                  --native VERSION=GAME_JAR              an unobfuscated version (26.1+): check only
                  --obf VERSION=GAME_JAR,MAPPINGS_TXT    an obfuscated version: check and bake
                                                         (MAPPINGS_TXT: Mojang's official mappings)
                  --strict                               exit 1 if any version has missing references""");
        System.exit(2);
    }
}
