package io.github.hronosin.miracle.toolchain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The Prophecy: reads a mod's classes (without loading them) and foresees which parts of
 * MiracleToolChain it will use, so the right game methods can be patched at startup, before any
 * of them load. A heuristic, as prophecies go: a call written in the mod counts, whether or not
 * it ever runs. That only costs a hook that never fires.
 */
final class Prophecy {

    private static final String PACKAGE = Prophecy.class.getPackageName().replace('.', '/') + "/";

    /** What a mod will need. */
    record Foresight(Set<Omens.Omen> omens, Set<Blessings.Key> blessings, boolean sermons, boolean scripture,
                     boolean creation, boolean telepathy, boolean gestures, List<String> doubts) {

        boolean empty() {
            return omens.isEmpty() && blessings.isEmpty() && !sermons && !scripture && !creation && !telepathy && !gestures;
        }

        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            omens.forEach(o -> parts.add(o.method));
            for (Blessings.Key k : blessings()) {
                parts.add(k.value().method + ":" + k.op().name().toLowerCase(java.util.Locale.ROOT)
                        + (k.op() == Blessings.Op.SET ? "@" + k.priority() : ""));
            }
            if (sermons) {
                parts.add("sermons");
            }
            if (scripture) {
                parts.add("scripture");
            }
            if (creation) {
                parts.add("creation");
            }
            if (telepathy) {
                parts.add("telepathy");
            }
            if (gestures) {
                parts.add("gestures");
            }
            return String.join(", ", parts);
        }
    }

    private Prophecy() {
    }

    /** Reads every class in the jar (baked variants aside: they make the same calls). */
    static Foresight read(Path jar) {
        Set<Omens.Omen> omens = EnumSet.noneOf(Omens.Omen.class);
        Set<Blessings.Value> values = EnumSet.noneOf(Blessings.Value.class);
        Set<Blessings.Key> keys = new LinkedHashSet<>();
        Set<Blessings.Key> loose = new LinkedHashSet<>();
        boolean[] flags = new boolean[5];
        List<String> doubts = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry e : jf.stream().toList()) {
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = jf.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                ClassModel cm = ClassFile.of().parse(bytes);
                for (MethodModel mm : cm.methods()) {
                    CodeModel code = mm.code().orElse(null);
                    if (code != null) {
                        scan(code, cm.thisClass().asInternalName() + "." + mm.methodName().stringValue(),
                                omens, values, keys, loose, flags, doubts);
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        // A change whose value we couldn't follow (the blessing came from a field, a parameter...):
        // prepare it for every value the mod names anywhere. Costs a hook or two that never fire.
        for (Blessings.Key k : loose) {
            values.forEach(v -> keys.add(new Blessings.Key(v, k.op(), k.priority())));
        }
        return new Foresight(omens, keys, flags[0], flags[1], flags[2], flags[3], flags[4], doubts);
    }

    /**
     * Follows each chain as the compiler lays it out: {@code Blessings.jumpPower()}, then any
     * narrowing, an optional {@code priority(n)}, and the change, all in one method.
     */
    private static void scan(CodeModel code, String where, Set<Omens.Omen> omens, Set<Blessings.Value> values,
                             Set<Blessings.Key> keys, Set<Blessings.Key> loose, boolean[] flags, List<String> doubts) {
        Instruction previous = null;
        Blessings.Value current = null;
        int priority = 0;
        for (CodeElement el : code) {
            if (!(el instanceof Instruction ins)) {
                continue;
            }
            if (ins instanceof InvokeInstruction inv && inv.owner().asInternalName().startsWith(PACKAGE)) {
                String owner = inv.owner().asInternalName().substring(PACKAGE.length());
                String name = inv.name().stringValue();
                switch (owner) {
                    case "Omens", "Events" -> {
                        Omens.Omen o = Omens.Omen.byMethod(name);
                        if (o != null) {
                            omens.add(o);
                        }
                    }
                    case "Blessings", "Tweaks" -> {
                        Blessings.Value v = Blessings.Value.byMethod(name);
                        if (v != null) {
                            values.add(v);
                            current = v;
                            priority = 0;
                        }
                    }
                    case "Blessing" -> {
                        Blessings.Op op = Blessings.Op.byMethod(name);
                        if (op != null) {
                            Blessings.Key key = new Blessings.Key(current, op, op == Blessings.Op.SET ? priority : 0);
                            if (current != null) {
                                keys.add(key);
                            } else {
                                loose.add(key);
                            }
                            current = null;
                            priority = 0;
                        } else if (name.equals("priority")) {
                            Integer p = intConstant(previous);
                            if (p != null) {
                                priority = p;
                            } else {
                                doubts.add(where + " calls priority() with a computed number; only set() at priorities"
                                        + " written as plain numbers is prepared");
                            }
                        }
                    }
                    case "Sermons", "ChatCommands" -> flags[0] |= name.equals("preach");
                    case "Scripture", "Resources" -> flags[1] |= name.equals("reveal");
                    case "Creation", "Content" -> flags[2] |= name.equals("item") || name.equals("block");
                    case "Telepathy", "Networking", "Telepathy$Channel" -> flags[3] = true;
                    case "Gestures", "Keybinds" -> flags[4] |= name.equals("key");
                    default -> {
                    }
                }
            }
            previous = ins;
        }
    }

    private static Integer intConstant(Instruction ins) {
        if (ins instanceof ConstantInstruction c && c.constantValue() instanceof Integer i) {
            return i;
        }
        return null;
    }
}
