package io.github.hronosin.miracle;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * What a mod's code reaches for outside the game: processes, native code, the network, files,
 * classes made from bytes, Unsafe, private members, OpenGL or Vulkan directly, ending the game.
 * Read from the constant pools of its classes (fallbacks included), so it sees what the code
 * names, not what it does: a mod can hide more behind reflection, and naming something isn't
 * misusing it. A label, not a verdict; and no sandbox: there is none in Java 25.
 *
 * <p>Not API: used by the loader (its startup line and the raw graphics rule) and by
 * {@code miracle zandatsu}.
 */
public final class Reach {

    /** The kinds, in the order they're reported. */
    public enum Kind {
        PROCESSES("starts processes", true),
        NATIVE("loads native code", true),
        NETWORK("uses the network", true),
        DEFINES_CLASSES("makes classes from bytes", true),
        UNSAFE("uses Unsafe", true),
        RAW_GRAPHICS("calls OpenGL or Vulkan directly", true),
        EXITS("can end the game itself (System.exit, halt)", true),
        WRITES_FILES("writes, moves or deletes files", false),
        REFLECTION("opens private members (reflection)", false);

        public final String says;
        /** Worth a line at startup; the others are ordinary for mods. */
        public final boolean notable;

        Kind(String says, boolean notable) {
            this.says = says;
            this.notable = notable;
        }

        /** The short name, for one-line summaries. */
        public String shortName() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        }
    }

    /** One thing found: what kind, in which class, by naming what. */
    public record Find(Kind kind, String where, String what) {
    }

    private Reach() {
    }

    /** Everything a jar's classes reach for, each (kind, class, thing) once. */
    public static List<Find> scan(Path jar) throws IOException {
        Set<Find> out = new LinkedHashSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry e : Collections.list(jf.entries())) {
                String n = e.getName();
                if (!n.endsWith(".class") || n.endsWith("module-info.class")
                        || (n.startsWith("META-INF/") && !n.startsWith("META-INF/miracle/fallback/"))) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(e)) {
                    out.addAll(scanClass(in.readAllBytes()));
                } catch (IllegalArgumentException broken) {
                    // not a class file we can read; the loader will say so if it matters
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** What one class file reaches for. */
    public static List<Find> scanClass(byte[] bytes) {
        ClassModel cm = ClassFile.of().parse(bytes);
        String self = cm.thisClass().asInternalName().replace('/', '.');
        Set<Find> out = new LinkedHashSet<>();
        for (PoolEntry p : cm.constantPool()) {
            if (p instanceof ClassEntry c) {
                Kind k = byClass(c.asInternalName());
                if (k == Kind.RAW_GRAPHICS && self.startsWith("org.lwjgl.")) {
                    continue;   // LWJGL itself (shaded into a jar) isn't the one calling
                }
                if (k != null) {
                    out.add(new Find(k, self, c.asInternalName().replace('/', '.')));
                }
            } else if (p instanceof MemberRefEntry m) {
                String owner = m.owner().asInternalName();
                Kind k = byMember(owner, m.name().stringValue());
                if (k == Kind.RAW_GRAPHICS && self.startsWith("org.lwjgl.")) {
                    continue;
                }
                if (k != null) {
                    out.add(new Find(k, self, owner.replace('/', '.') + "." + m.name().stringValue()));
                }
            }
        }
        // a class named only because its member is: keep the member, it says more
        out.removeIf(f -> out.stream().anyMatch(g -> g != f && g.kind() == f.kind() && g.where().equals(f.where())
                && g.what().startsWith(f.what() + ".")));
        return new ArrayList<>(out);
    }

    /** The kinds found, each with the classes that reach for it, in report order. */
    public static Map<Kind, Set<String>> byKind(List<Find> finds) {
        Map<Kind, Set<String>> m = new EnumMap<>(Kind.class);
        for (Find f : finds) {
            m.computeIfAbsent(f.kind(), k -> new LinkedHashSet<>()).add(f.where());
        }
        return m;
    }

    private static Kind byClass(String c) {
        if (c.startsWith("org/lwjgl/opengl/") || c.startsWith("org/lwjgl/opengles/") || c.startsWith("org/lwjgl/vulkan/")) {
            return Kind.RAW_GRAPHICS;
        }
        if (c.equals("java/lang/ProcessBuilder")) {
            return Kind.PROCESSES;
        }
        if (c.equals("java/net/Socket") || c.equals("java/net/ServerSocket") || c.equals("java/net/DatagramSocket")
                || c.equals("java/net/URLConnection") || c.equals("java/net/HttpURLConnection") || c.startsWith("java/net/http/")
                || c.equals("java/nio/channels/SocketChannel") || c.equals("java/nio/channels/ServerSocketChannel")
                || c.equals("java/nio/channels/DatagramChannel")) {
            return Kind.NETWORK;
        }
        if (c.equals("sun/misc/Unsafe") || c.equals("jdk/internal/misc/Unsafe")) {
            return Kind.UNSAFE;
        }
        if (c.equals("java/lang/foreign/Linker") || c.equals("java/lang/foreign/SymbolLookup")) {
            return Kind.NATIVE;
        }
        if (c.equals("java/net/URLClassLoader")) {
            return Kind.DEFINES_CLASSES;
        }
        if (c.equals("java/io/FileOutputStream") || c.equals("java/io/FileWriter") || c.equals("java/io/RandomAccessFile")) {
            return Kind.WRITES_FILES;
        }
        return null;
    }

    private static Kind byMember(String owner, String name) {
        switch (owner) {
            case "java/lang/Runtime" -> {
                return switch (name) {
                    case "exec" -> Kind.PROCESSES;
                    case "load", "loadLibrary" -> Kind.NATIVE;
                    case "exit", "halt" -> Kind.EXITS;
                    default -> null;
                };
            }
            case "java/lang/System" -> {
                return switch (name) {
                    case "load", "loadLibrary" -> Kind.NATIVE;
                    case "exit" -> Kind.EXITS;
                    default -> null;
                };
            }
            case "java/net/URL" -> {
                return name.equals("openConnection") || name.equals("openStream") ? Kind.NETWORK : null;
            }
            case "java/nio/file/Files" -> {
                return switch (name) {
                    case "write", "writeString", "delete", "deleteIfExists", "move", "copy", "newOutputStream",
                         "newBufferedWriter", "createFile", "createDirectory", "createDirectories", "createLink",
                         "createSymbolicLink", "setAttribute", "setPosixFilePermissions" -> Kind.WRITES_FILES;
                    default -> null;
                };
            }
            case "java/io/File" -> {
                return switch (name) {
                    case "delete", "renameTo", "createNewFile", "mkdir", "mkdirs", "setExecutable", "setWritable" -> Kind.WRITES_FILES;
                    default -> null;
                };
            }
            case "java/lang/ClassLoader" -> {
                return name.equals("defineClass") ? Kind.DEFINES_CLASSES : null;
            }
            case "java/lang/invoke/MethodHandles$Lookup" -> {
                return name.equals("defineClass") || name.equals("defineHiddenClass") ? Kind.DEFINES_CLASSES : null;
            }
            case "java/lang/invoke/MethodHandles" -> {
                return name.equals("privateLookupIn") ? Kind.REFLECTION : null;
            }
            case "java/lang/reflect/AccessibleObject", "java/lang/reflect/Field", "java/lang/reflect/Method",
                 "java/lang/reflect/Constructor" -> {
                return name.equals("setAccessible") || name.equals("trySetAccessible") ? Kind.REFLECTION : null;
            }
            default -> {
                Kind k = byClass(owner);
                return k == Kind.RAW_GRAPHICS ? k : null;
            }
        }
    }
}
