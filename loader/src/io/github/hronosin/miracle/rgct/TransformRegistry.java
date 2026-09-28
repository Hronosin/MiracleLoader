package io.github.hronosin.miracle.rgct;

import io.github.hronosin.miracle.Log;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Collects every mod's patches, then applies them to classes as they load.
 *
 * <p>Two phases: open (mods register patches) and frozen (the game runs, classes get patched).
 * Registration after the freeze is an error — the classes may already be loaded.
 *
 * <p>Internal to the loader. Mods talk to {@link Rgct}.
 */
public final class TransformRegistry {

    enum Where { HEAD, RETURN }

    record HookPatch(String modId, String method, String descriptor, Where where, int hookId) {
        boolean matches(MethodModel mm) {
            return mm.methodName().equalsString(method)
                    && (descriptor == null || mm.methodType().equalsString(descriptor));
        }

        String label() {
            return method + (descriptor == null ? "" : descriptor);
        }
    }

    record RawPatch(String modId, ClassTransform transform) {
    }

    private static final class ClassPatches {
        final List<HookPatch> hooks = new ArrayList<>();
        final List<RawPatch> raws = new ArrayList<>();
    }

    private static final ClassDesc DISPATCH = ClassDesc.of(HookDispatch.class.getName());
    private static final MethodTypeDesc FIRE = MethodTypeDesc.ofDescriptor("(ILjava/lang/Object;)V");

    private final Map<String, ClassPatches> byClass = new LinkedHashMap<>();
    private volatile boolean frozen;

    /** A view of RGCT that stamps every patch with this mod's id. */
    public Rgct viewFor(String modId) {
        return new Rgct(modId, this);
    }

    void checkOpen() {
        if (frozen) {
            throw new IllegalStateException(
                    "RGCT is frozen: transforms can only be registered from MiracleMod.transform()");
        }
    }

    synchronized void addHook(String className, String modId, String method, String descriptor,
                              Where where, Hook hook) {
        checkOpen();
        if (hook == null) {
            throw new IllegalArgumentException("hook is null");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method name is empty");
        }
        String at = className + "#" + method + " @" + where;
        int id = HookDispatch.register(modId, at, hook);
        patchesFor(className).hooks.add(new HookPatch(modId, method, descriptor, where, id));
    }

    synchronized void addRaw(String className, String modId, ClassTransform transform) {
        checkOpen();
        if (transform == null) {
            throw new IllegalArgumentException("transform is null");
        }
        patchesFor(className).raws.add(new RawPatch(modId, transform));
    }

    private ClassPatches patchesFor(String className) {
        return byClass.computeIfAbsent(className, k -> new ClassPatches());
    }

    public synchronized void freeze() {
        frozen = true;
    }

    public Set<String> targetedClasses() {
        return Collections.unmodifiableSet(byClass.keySet());
    }

    /** Mods that registered anything for this class, for error messages. */
    public List<String> modsTargeting(String className) {
        ClassPatches p = byClass.get(className);
        if (p == null) {
            return List.of();
        }
        List<String> mods = new ArrayList<>();
        p.hooks.forEach(h -> { if (!mods.contains(h.modId())) mods.add(h.modId()); });
        p.raws.forEach(r -> { if (!mods.contains(r.modId())) mods.add(r.modId()); });
        return mods;
    }

    /** Who layered what onto which method. Printed once at startup. */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        if (byClass.isEmpty()) {
            lines.add("RGCT: no patches registered. Vanilla, but with extra steps.");
            return lines;
        }
        lines.add("RGCT: patches on " + byClass.size() + " class(es):");
        for (var e : new TreeMap<>(byClass).entrySet()) {
            lines.add("  " + e.getKey());
            for (HookPatch h : e.getValue().hooks) {
                lines.add(String.format("    %-28s @%-7s <- %s", h.label(), h.where(), h.modId()));
            }
            for (RawPatch r : e.getValue().raws) {
                lines.add(String.format("    %-28s  %-7s <- %s (raw: you're on your own)", "<whole class>", "", r.modId()));
            }
        }
        return lines;
    }

    /**
     * Applies every patch registered for {@code className}. Returns the input array unchanged
     * when nothing targets the class.
     */
    public byte[] transform(String className, byte[] bytes, ClassLoader resolverLoader) {
        ClassPatches patches = byClass.get(className);
        if (patches == null) {
            return bytes;
        }

        // Stack maps get regenerated, which needs the class hierarchy. Resolve it through the
        // game loader first so game superclasses are found.
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(resolverLoader)
                        .orElse(ClassHierarchyResolver.defaultResolver())));
        ClassModel model = cf.parse(bytes);

        Set<HookPatch> matched = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        ClassTransform transform = hookTransform(patches.hooks, matched);
        for (RawPatch raw : patches.raws) {
            Log.warn("RGCT: mod '" + raw.modId() + "' raw-patches " + className
                    + ". If it breaks, that one is to blame.");
            transform = transform.andThen(raw.transform());
        }

        byte[] out = cf.transformClass(model, transform);

        for (HookPatch h : patches.hooks) {
            if (!matched.contains(h)) {
                Log.warn("RGCT: mod '" + h.modId() + "' targets " + className + "#" + h.label()
                        + ", but no such method with a body exists. Wrong game version?");
            }
        }
        return out;
    }

    private static ClassTransform hookTransform(List<HookPatch> hooks, Set<HookPatch> matched) {
        if (hooks.isEmpty()) {
            return ClassTransform.ACCEPT_ALL;
        }
        return (clb, cle) -> {
            if (cle instanceof MethodModel mm && mm.code().isPresent()) {
                List<Integer> head = new ArrayList<>();
                List<Integer> ret = new ArrayList<>();
                for (HookPatch h : hooks) {
                    if (h.matches(mm)) {
                        matched.add(h);
                        (h.where() == Where.HEAD ? head : ret).add(h.hookId());
                    }
                }
                if (!head.isEmpty() || !ret.isEmpty()) {
                    boolean isStatic = mm.flags().has(AccessFlag.STATIC);
                    boolean isCtor = mm.methodName().equalsString("<init>");
                    // In a constructor's head `this` is uninitialized and can't be passed around.
                    boolean selfAtHead = !isStatic && !isCtor;
                    boolean selfAtReturn = !isStatic;
                    clb.transformMethod(mm, MethodTransform.transformingCode(
                            new Injector(toArray(head), toArray(ret), selfAtHead, selfAtReturn)));
                    return;
                }
            }
            clb.with(cle);
        };
    }

    private static int[] toArray(List<Integer> ids) {
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    private record Injector(int[] headIds, int[] returnIds, boolean selfAtHead, boolean selfAtReturn)
            implements CodeTransform {

        @Override
        public void atStart(CodeBuilder b) {
            for (int id : headIds) {
                emitFire(b, id, selfAtHead);
            }
        }

        @Override
        public void accept(CodeBuilder b, CodeElement e) {
            if (e instanceof ReturnInstruction) {
                for (int id : returnIds) {
                    emitFire(b, id, selfAtReturn);
                }
            }
            b.with(e);
        }

        private static void emitFire(CodeBuilder b, int id, boolean passSelf) {
            b.loadConstant(id);
            if (passSelf) {
                b.aload(0);
            } else {
                b.aconst_null();
            }
            b.invokestatic(DISPATCH, "fire", FIRE);
        }
    }
}
