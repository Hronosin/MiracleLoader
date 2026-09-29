package io.github.hronosin.miracle.bake;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Minecraft version's dictionary: every game class and member under its readable (Mojang)
 * name, and what it's called inside that version's jar.
 *
 * <p>For an obfuscated version the readable side comes from Mojang's official mapping file (the
 * one the mod author downloads), the jar side from the obfuscated game jar. For an unobfuscated
 * version (26.1+) both sides are the jar itself: the dictionary is the identity, but it still
 * knows what exists, so references can be checked.
 *
 * <p>All class names here are internal names ({@code net/minecraft/world/entity/LivingEntity}),
 * all descriptors use readable names.
 */
final class VersionDict {

    static final class ClassInfo {
        final String named;
        String obf;
        String superNamed;
        List<String> interfacesNamed = List.of();
        /** name + readable descriptor -> name in the jar */
        final Map<String, String> methods = new HashMap<>();
        /** name + ":" + readable descriptor -> name in the jar */
        final Map<String, String> fields = new HashMap<>();
        /** name -> readable descriptors of every overload declared here */
        final Map<String, List<String>> overloads = new HashMap<>();

        ClassInfo(String named, String obf) {
            this.named = named;
            this.obf = obf;
        }

        void addMethod(String name, String desc, String jarName) {
            methods.put(name + desc, jarName);
            overloads.computeIfAbsent(name, k -> new ArrayList<>()).add(desc);
        }
    }

    final String version;
    final boolean obfuscated;
    final Path gameJar;
    /** readable internal name -> class */
    final Map<String, ClassInfo> classes = new HashMap<>();
    /** jar internal name -> readable internal name */
    final Map<String, String> jarToNamed = new HashMap<>();
    /** The jar's own class hierarchy (jar names), for regenerating stack maps of baked code. */
    private final Map<ClassDesc, ClassDesc> jarSupers = new HashMap<>();
    private final Set<ClassDesc> jarInterfaces = new HashSet<>();

    private VersionDict(String version, boolean obfuscated, Path gameJar) {
        this.version = version;
        this.obfuscated = obfuscated;
        this.gameJar = gameJar;
    }

    ClassHierarchyResolver hierarchy() {
        return ClassHierarchyResolver.of(jarInterfaces, jarSupers);
    }

    private void recordHierarchy(ClassModel cm) {
        ClassDesc self = cm.thisClass().asSymbol();
        if (cm.flags().has(AccessFlag.INTERFACE)) {
            jarInterfaces.add(self);
        } else {
            jarSupers.put(self, cm.superclass().map(ClassEntry::asSymbol).orElse(null));
        }
    }

    ClassInfo get(String named) {
        return classes.get(named);
    }

    /** Readable internal class name -> name in this version's jar, or null if not a game class here. */
    String jarName(String named) {
        ClassInfo c = classes.get(named);
        return c == null ? null : c.obf;
    }

    // --- loading -------------------------------------------------------------------------------

    /** An unobfuscated version: the jar is its own dictionary. */
    static VersionDict ofNative(String version, Path gameJar) throws IOException {
        VersionDict d = new VersionDict(version, false, gameJar);
        forEachClass(gameJar, cm -> {
            d.recordHierarchy(cm);
            String name = cm.thisClass().asInternalName();
            ClassInfo c = new ClassInfo(name, name);
            c.superNamed = cm.superclass().map(ClassEntry::asInternalName).orElse(null);
            c.interfacesNamed = cm.interfaces().stream().map(ClassEntry::asInternalName).toList();
            for (MethodModel m : cm.methods()) {
                c.addMethod(m.methodName().stringValue(), m.methodType().stringValue(), m.methodName().stringValue());
            }
            for (FieldModel f : cm.fields()) {
                c.fields.put(f.fieldName().stringValue() + ":" + f.fieldType().stringValue(), f.fieldName().stringValue());
            }
            d.classes.put(name, c);
            d.jarToNamed.put(name, name);
        });
        return d;
    }

    /** An obfuscated version: Mojang's mapping file says what everything is called. */
    static VersionDict ofObfuscated(String version, Path gameJar, Path mappings) throws IOException {
        VersionDict d = new VersionDict(version, true, gameJar);
        d.readMojangMappings(mappings);
        // The mapping file has names but not the class hierarchy; the jar has the hierarchy.
        forEachClass(gameJar, cm -> {
            d.recordHierarchy(cm);
            String named = d.jarToNamed.get(cm.thisClass().asInternalName());
            if (named == null) {
                return; // not in the mappings (a library shaded into the jar); left as is
            }
            ClassInfo c = d.classes.get(named);
            c.superNamed = cm.superclass().map(e -> d.toNamed(e.asInternalName())).orElse(null);
            c.interfacesNamed = cm.interfaces().stream().map(e -> d.toNamed(e.asInternalName())).toList();
        });
        return d;
    }

    private String toNamed(String jarName) {
        return jarToNamed.getOrDefault(jarName, jarName);
    }

    // Mojang's mappings are in ProGuard format:
    //   net.minecraft.world.entity.LivingEntity -> chl:
    //       2371:2371:float getJumpPower() -> fF
    //       java.util.List activeEffects -> bY
    private static final Pattern CLASS_LINE = Pattern.compile("^(\\S+) -> (\\S+):$");
    private static final Pattern METHOD_LINE = Pattern.compile("^\\s+(?:\\d+:\\d+:)?(\\S+) ([^\\s(]+)\\(([^)]*)\\)(?::\\d+(?::\\d+)?)? -> (\\S+)$");
    private static final Pattern FIELD_LINE = Pattern.compile("^\\s+(\\S+) (\\S+) -> (\\S+)$");

    private record PendingMember(ClassInfo owner, boolean method, String name, String javaReturnOrType,
                                 String javaParams, String jarName) {
    }

    private void readMojangMappings(Path file) throws IOException {
        List<PendingMember> members = new ArrayList<>();
        ClassInfo current = null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                Matcher m;
                if (!Character.isWhitespace(line.charAt(0))) {
                    m = CLASS_LINE.matcher(line);
                    if (!m.matches()) {
                        throw new IOException(file.getFileName() + ":" + lineNo + ": not a class line: " + line);
                    }
                    String named = m.group(1).replace('.', '/');
                    String obf = m.group(2).replace('.', '/');
                    current = new ClassInfo(named, obf);
                    classes.put(named, current);
                    jarToNamed.put(obf, named);
                } else if (current != null && (m = METHOD_LINE.matcher(line)).matches()) {
                    members.add(new PendingMember(current, true, m.group(2), m.group(1), m.group(3), m.group(4)));
                } else if (current != null && (m = FIELD_LINE.matcher(line)).matches()) {
                    members.add(new PendingMember(current, false, m.group(2), m.group(1), null, m.group(3)));
                } else {
                    throw new IOException(file.getFileName() + ":" + lineNo + ": can't read: " + line);
                }
            }
        }
        // Descriptors need every class known first, so members are resolved in a second pass.
        for (PendingMember p : members) {
            if (p.method()) {
                StringBuilder desc = new StringBuilder("(");
                if (!p.javaParams().isEmpty()) {
                    for (String t : p.javaParams().split(",")) {
                        desc.append(javaTypeToDesc(t.strip()));
                    }
                }
                desc.append(')').append(javaTypeToDesc(p.javaReturnOrType()));
                p.owner().addMethod(p.name(), desc.toString(), p.jarName());
            } else {
                p.owner().fields.put(p.name() + ":" + javaTypeToDesc(p.javaReturnOrType()), p.jarName());
            }
        }
    }

    /** "int" -> "I", "java.lang.String[]" -> "[Ljava/lang/String;" (readable names). */
    static String javaTypeToDesc(String t) {
        int dims = 0;
        while (t.endsWith("[]")) {
            dims++;
            t = t.substring(0, t.length() - 2);
        }
        String base = switch (t) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + t.replace('.', '/') + ";";
        };
        return "[".repeat(dims) + base;
    }

    // --- helpers --------------------------------------------------------------------------------

    interface ClassVisitor {
        void visit(ClassModel cm) throws IOException;
    }

    static void forEachClass(Path jar, ClassVisitor v) throws IOException {
        ClassFile cf = ClassFile.of();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(e)) {
                    v.visit(cf.parse(in.readAllBytes()));
                }
            }
        }
    }

    @Override
    public String toString() {
        return version + (obfuscated ? "" : " (unobfuscated)");
    }
}
