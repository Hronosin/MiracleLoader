package io.github.hronosin.miracle.bake;

import java.lang.classfile.Attribute;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fallback functions: per-version replacements for single methods, written by the mod author
 * where the dictionary shows a hole.
 *
 * <p>A fallback source file is a <em>partial</em> class with the same name as the real one:
 * <ul>
 *   <li>a method <b>with a body</b> replaces the method of the same name and descriptor (or is
 *       added, if the real class has none);</li>
 *   <li>a {@code native} method, and any field, is only a declaration so the file compiles: it
 *       refers to the real class's member and is dropped;</li>
 *   <li>constructors and static initializers of the partial class are dropped too.</li>
 * </ul>
 * Lambdas inside fallback methods get their synthetic methods renamed so they can't collide with
 * the real class's. Classes that exist only in the fallback folder are added as they are.
 *
 * <p>Merging happens in readable names, before the version's dictionary check; so a fallback
 * that covers every hole makes the version bakeable.
 */
final class Fallbacks {

    static final String DIR = "META-INF/miracle/fallback/";

    record Result(Map<String, ClassModel> classes, int replaced, int added, List<String> notes) {
    }

    private Fallbacks() {
    }

    /**
     * @param main  the mod's own classes (entry path -> model)
     * @param fb    this version's fallback classes (entry path relative to the version folder -> bytes)
     */
    static Result merge(Map<String, ClassModel> main, Map<String, byte[]> fb, String version) {
        // Stack maps are regenerated when the merged classes are translated for the version,
        // with that version's class hierarchy; here they would only be wrong.
        ClassFile cf = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS);
        Map<String, ClassModel> out = new LinkedHashMap<>(main);
        List<String> notes = new ArrayList<>();
        int[] replaced = {0};
        int[] added = {0};
        String tag = version.replaceAll("[^A-Za-z0-9]", "_");

        for (var e : fb.entrySet()) {
            String path = e.getKey();
            ClassModel f = cf.parse(e.getValue());
            ClassModel m = main.get(path);
            if (m == null) {
                String outer = path.contains("$") ? path.substring(0, path.indexOf('$')) + ".class" : null;
                if (outer != null && main.containsKey(outer)) {
                    throw new IllegalArgumentException("fallback for " + version + ": " + path + " is a nested or anonymous"
                            + " class of " + outer + ", which could clash with the real one's. Use a lambda,"
                            + " or a top-level helper class.");
                }
                out.put(path, f);
                notes.add("added class " + f.thisClass().asInternalName().replace('/', '.'));
                continue;
            }
            out.put(path, cf.parse(mergeClass(cf, m, f, tag, replaced, added, notes)));
        }
        return new Result(out, replaced[0], added[0], notes);
    }

    private static String key(MethodModel m) {
        return m.methodName().stringValue() + m.methodType().stringValue();
    }

    private static byte[] mergeClass(ClassFile cf, ClassModel real, ClassModel partial, String tag,
                                     int[] replaced, int[] added, List<String> notes) {
        String owner = real.thisClass().asInternalName();
        String who = owner.replace('/', '.');
        Map<String, MethodModel> realMethods = new HashMap<>();
        real.methods().forEach(m -> realMethods.put(key(m), m));

        // Synthetic methods (lambda bodies) of the partial class get a unique name.
        Map<String, String> renames = new HashMap<>();
        for (MethodModel m : partial.methods()) {
            String name = m.methodName().stringValue();
            if (m.flags().has(AccessFlag.SYNTHETIC) && name.startsWith("lambda$")) {
                renames.put(name, "lambda$fallback$" + tag + "$" + name.substring("lambda$".length()));
            }
        }

        List<MethodModel> take = new ArrayList<>();
        Set<String> drop = new HashSet<>();
        for (MethodModel m : partial.methods()) {
            String name = m.methodName().stringValue();
            if (m.code().isEmpty() || name.equals("<init>") || name.equals("<clinit>")
                    || name.equals("$deserializeLambda$")) {
                continue; // declarations, the partial class's own plumbing
            }
            if (renames.containsKey(name)) {
                take.add(m); // a lambda body: renamed, never replaces anything
                continue;
            }
            MethodModel r = realMethods.get(key(m));
            if (r != null) {
                if (r.flags().has(AccessFlag.STATIC) != m.flags().has(AccessFlag.STATIC)) {
                    throw new IllegalArgumentException("fallback " + who + "#" + key(m) + " is "
                            + (m.flags().has(AccessFlag.STATIC) ? "static" : "not static") + ", the real one isn't");
                }
                drop.add(key(m));
                replaced[0]++;
                notes.add("replaced " + who + "#" + key(m));
            } else {
                added[0]++;
                notes.add("added " + who + "#" + key(m) + " (no method with this name and descriptor to replace)");
            }
            take.add(m);
        }

        return cf.transformClass(real, new ClassTransform() {
            @Override
            public void accept(ClassBuilder b, ClassElement e) {
                if (e instanceof MethodModel m && drop.contains(key(m))) {
                    return;
                }
                b.with(e);
            }

            @Override
            public void atEnd(ClassBuilder b) {
                for (MethodModel m : take) {
                    boolean lambda = renames.containsKey(m.methodName().stringValue());
                    MethodModel r = lambda ? null : realMethods.get(key(m));
                    int flags = r != null ? r.flags().flagsMask() : m.flags().flagsMask();
                    String name = renames.getOrDefault(m.methodName().stringValue(), m.methodName().stringValue());
                    b.withMethod(name, m.methodTypeSymbol(), flags, mb -> {
                        for (MethodElement me : m) {
                            if (me instanceof CodeModel code) {
                                if (renames.isEmpty()) {
                                    mb.with(code);
                                } else {
                                    mb.transformCode(code, renamer(owner, renames));
                                }
                            } else if (me instanceof Attribute<?>) {
                                mb.with(me);
                            }
                        }
                    });
                }
            }
        });
    }

    /** Points calls and method handles at the renamed lambda bodies. */
    private static CodeTransform renamer(String owner, Map<String, String> renames) {
        return (b, e) -> {
            switch (e) {
                case InvokeInstruction ii when ii.owner().asInternalName().equals(owner)
                        && renames.containsKey(ii.name().stringValue()) ->
                        b.invoke(ii.opcode(), ii.owner().asSymbol(), renames.get(ii.name().stringValue()),
                                ii.typeSymbol(), ii.isInterface());
                case InvokeDynamicInstruction indy -> {
                    ConstantDesc[] args = indy.bootstrapArgs().stream().map(a -> rename(a, owner, renames))
                            .toArray(ConstantDesc[]::new);
                    b.invokedynamic(DynamicCallSiteDesc.of(indy.bootstrapMethod(), indy.name().stringValue(),
                            indy.typeSymbol(), args));
                }
                case ConstantInstruction.LoadConstantInstruction lc
                        when lc.constantValue() instanceof DirectMethodHandleDesc ->
                        b.loadConstant(rename(lc.constantValue(), owner, renames));
                default -> b.with(e);
            }
        };
    }

    private static ConstantDesc rename(ConstantDesc c, String owner, Map<String, String> renames) {
        if (c instanceof DirectMethodHandleDesc h && Remapper.internal(h.owner()).equals(owner)
                && renames.containsKey(h.methodName())) {
            return MethodHandleDesc.ofMethod(h.kind(), h.owner(), renames.get(h.methodName()),
                    MethodTypeDesc.ofDescriptor(h.lookupDescriptor()));
        }
        return c;
    }
}
