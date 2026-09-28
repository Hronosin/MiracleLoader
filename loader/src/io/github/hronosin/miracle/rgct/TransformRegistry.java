package io.github.hronosin.miracle.rgct;

import io.github.hronosin.miracle.Log;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Collects every mod's patches, then applies them to classes as they load.
 *
 * <p>Two phases: open (mods register patches) and frozen (the game runs, classes get patched).
 * Registration after the freeze is an error, since the classes may already be loaded.
 *
 * <p>Internal to the loader. Mods talk to {@link Rgct}.
 */
public final class TransformRegistry {

    enum Where {
        HEAD("@HEAD"), RETURN("@RETURN"), INTERCEPT_HEAD("intercept@HEAD"), INTERCEPT_RETURN("intercept@RETURN");

        final String label;

        Where(String label) {
            this.label = label;
        }
    }

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
    private static final ClassDesc CONTEXT = ClassDesc.of(HookContext.class.getName());
    private static final MethodTypeDesc FIRE = MethodTypeDesc.ofDescriptor("(ILjava/lang/Object;)V");
    private static final MethodTypeDesc INTERCEPT_HEAD = MethodTypeDesc.of(CONTEXT,
            ConstantDescs.CD_int, ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType());
    private static final MethodTypeDesc INTERCEPT_RETURN = MethodTypeDesc.of(ConstantDescs.CD_Object,
            ConstantDescs.CD_int, ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_Object);

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
                              Where where, Object hook) {
        checkOpen();
        if (hook == null) {
            throw new IllegalArgumentException("hook is null");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method name is empty");
        }
        if (descriptor != null) {
            MethodTypeDesc.ofDescriptor(descriptor); // fail now on a typo, not at class load
        }
        String at = className + "#" + method + (descriptor == null ? "" : descriptor) + " " + where.label;
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
                lines.add(String.format("    %-28s %-17s <- %s", h.label(), h.where().label, h.modId()));
            }
            for (RawPatch r : e.getValue().raws) {
                lines.add(String.format("    %-28s %-17s <- %s (raw: you're on your own)", "<whole class>", "", r.modId()));
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

        Set<HookPatch> matched = Collections.newSetFromMap(new IdentityHashMap<>());
        ClassTransform transform = hookTransform(className, patches.hooks, matched);
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

    private static ClassTransform hookTransform(String className, List<HookPatch> hooks, Set<HookPatch> matched) {
        if (hooks.isEmpty()) {
            return ClassTransform.ACCEPT_ALL;
        }
        return (clb, cle) -> {
            if (cle instanceof MethodModel mm && mm.code().isPresent()) {
                Map<Where, List<HookPatch>> found = new LinkedHashMap<>();
                for (HookPatch h : hooks) {
                    if (h.matches(mm)) {
                        found.computeIfAbsent(h.where(), w -> new ArrayList<>()).add(h);
                    }
                }
                if (!found.isEmpty()) {
                    boolean isCtor = mm.methodName().equalsString("<init>");
                    List<HookPatch> interceptHead = found.getOrDefault(Where.INTERCEPT_HEAD, List.of());
                    if (isCtor && !interceptHead.isEmpty()) {
                        for (HookPatch h : interceptHead) {
                            Log.warn("RGCT: mod '" + h.modId() + "' uses interceptHead on a constructor of "
                                    + className + ". Not supported (this isn't initialized yet), skipping that hook.");
                            matched.add(h); // reported already, don't also call it missing
                        }
                        interceptHead = List.of();
                    }
                    found.values().forEach(l -> l.forEach(matched::add));

                    MethodShape shape = MethodShape.of(className, mm);
                    int headSite = site(shape, interceptHead, true);
                    int returnSite = site(shape, found.getOrDefault(Where.INTERCEPT_RETURN, List.of()), false);

                    Injector injector = new Injector(shape,
                            ids(found.get(Where.HEAD)), ids(found.get(Where.RETURN)), headSite, returnSite);
                    if (injector.isEmpty()) {
                        clb.with(cle);
                    } else {
                        clb.transformMethod(mm, MethodTransform.transformingCode(injector));
                    }
                    return;
                }
            }
            clb.with(cle);
        };
    }

    private static int site(MethodShape shape, List<HookPatch> hooks, boolean head) {
        if (hooks.isEmpty()) {
            return -1;
        }
        return HookDispatch.registerSite(new HookDispatch.Site(
                hooks.stream().mapToInt(HookPatch::hookId).toArray(),
                shape.argKinds(), shape.returnKind(), head, shape.label()));
    }

    private static int[] ids(List<HookPatch> hooks) {
        return hooks == null ? new int[0] : hooks.stream().mapToInt(HookPatch::hookId).toArray();
    }

    /** Everything about a method's signature the injector needs. */
    private record MethodShape(String label, boolean isStatic, boolean isCtor,
                               List<ClassDesc> params, int[] slots, ClassDesc returnType) {

        static MethodShape of(String className, MethodModel mm) {
            MethodTypeDesc type = mm.methodTypeSymbol();
            boolean isStatic = mm.flags().has(AccessFlag.STATIC);
            List<ClassDesc> params = type.parameterList();
            int[] slots = new int[params.size()];
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < params.size(); i++) {
                slots[i] = slot;
                slot += TypeKind.from(params.get(i)).slotSize();
            }
            String label = className + "#" + mm.methodName().stringValue() + mm.methodType().stringValue();
            return new MethodShape(label, isStatic, mm.methodName().equalsString("<init>"),
                    params, slots, type.returnType());
        }

        String argKinds() {
            StringBuilder sb = new StringBuilder();
            params.forEach(p -> sb.append(p.descriptorString().charAt(0)));
            return sb.toString();
        }

        char returnKind() {
            return returnType.descriptorString().charAt(0);
        }

        boolean returnsVoid() {
            return returnKind() == 'V';
        }
    }

    /** Rewrites one method body: observe hooks, then intercept sites, at head and at each return. */
    private static final class Injector implements CodeTransform {
        private final MethodShape shape;
        private final int[] headIds;
        private final int[] returnIds;
        private final int headSite;
        private final int returnSite;
        private int returnTemp = -1;

        Injector(MethodShape shape, int[] headIds, int[] returnIds, int headSite, int returnSite) {
            this.shape = shape;
            this.headIds = headIds;
            this.returnIds = returnIds;
            this.headSite = headSite;
            this.returnSite = returnSite;
        }

        boolean isEmpty() {
            return headIds.length == 0 && returnIds.length == 0 && headSite < 0 && returnSite < 0;
        }

        private boolean selfAvailableAtHead() {
            return !shape.isStatic() && !shape.isCtor();
        }

        @Override
        public void atStart(CodeBuilder b) {
            for (int id : headIds) {
                emitFire(b, id, selfAvailableAtHead());
            }
            if (headSite >= 0) {
                emitInterceptHead(b);
            }
        }

        @Override
        public void accept(CodeBuilder b, CodeElement e) {
            if (e instanceof ReturnInstruction) {
                if (returnSite >= 0) {
                    emitInterceptReturn(b);
                }
                for (int id : returnIds) {
                    emitFire(b, id, !shape.isStatic());
                }
            }
            b.with(e);
        }

        private void emitFire(CodeBuilder b, int id, boolean passSelf) {
            b.loadConstant(id);
            loadSelf(b, passSelf);
            b.invokestatic(DISPATCH, "fire", FIRE);
        }

        private void loadSelf(CodeBuilder b, boolean passSelf) {
            if (passSelf) {
                b.aload(0);
            } else {
                b.aconst_null();
            }
        }

        /** Leaves a fresh Object[] holding the (boxed) current argument values on the stack. */
        private void packArgs(CodeBuilder b) {
            List<ClassDesc> params = shape.params();
            b.loadConstant(params.size());
            b.anewarray(ConstantDescs.CD_Object);
            for (int i = 0; i < params.size(); i++) {
                TypeKind kind = TypeKind.from(params.get(i));
                b.dup();
                b.loadConstant(i);
                b.loadLocal(kind, shape.slots()[i]);
                box(b, kind);
                b.aastore();
            }
        }

        private void emitInterceptHead(CodeBuilder b) {
            List<ClassDesc> params = shape.params();
            int argsSlot = b.allocateLocal(TypeKind.REFERENCE);
            packArgs(b);
            b.astore(argsSlot);

            b.loadConstant(headSite);
            loadSelf(b, selfAvailableAtHead());
            b.aload(argsSlot);
            b.invokestatic(DISPATCH, "interceptHead", INTERCEPT_HEAD);
            // stack: ctx
            b.dup();
            b.invokevirtual(CONTEXT, "isCancelled", MethodTypeDesc.of(ConstantDescs.CD_boolean));
            Label proceed = b.newLabel();
            b.ifeq(proceed);
            if (shape.returnsVoid()) {
                b.pop();
                b.return_();
            } else {
                b.invokevirtual(CONTEXT, "cancelReturnValue", MethodTypeDesc.of(ConstantDescs.CD_Object));
                unboxOrCast(b, shape.returnType());
                b.return_(TypeKind.from(shape.returnType()));
            }
            b.labelBinding(proceed);
            b.pop();

            // Write the (possibly changed) arguments back into their locals.
            for (int i = 0; i < params.size(); i++) {
                b.aload(argsSlot);
                b.loadConstant(i);
                b.aaload();
                unboxOrCast(b, params.get(i));
                b.storeLocal(TypeKind.from(params.get(i)), shape.slots()[i]);
            }
        }

        private void emitInterceptReturn(CodeBuilder b) {
            MethodTypeDesc desc = INTERCEPT_RETURN;
            if (shape.returnsVoid()) {
                b.loadConstant(returnSite);
                loadSelf(b, !shape.isStatic());
                packArgs(b);
                b.aconst_null();
                b.invokestatic(DISPATCH, "interceptReturn", desc);
                b.pop();
                return;
            }
            TypeKind kind = TypeKind.from(shape.returnType());
            if (returnTemp < 0) {
                returnTemp = b.allocateLocal(kind);
            }
            b.storeLocal(kind, returnTemp);
            b.loadConstant(returnSite);
            loadSelf(b, !shape.isStatic());
            packArgs(b);
            b.loadLocal(kind, returnTemp);
            box(b, kind);
            b.invokestatic(DISPATCH, "interceptReturn", desc);
            unboxOrCast(b, shape.returnType());
            // the original return instruction follows
        }
    }

    // --- boxing helpers --------------------------------------------------------------------------

    private static ClassDesc boxType(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> ConstantDescs.CD_Boolean;
            case BYTE -> ConstantDescs.CD_Byte;
            case CHAR -> ConstantDescs.CD_Character;
            case SHORT -> ConstantDescs.CD_Short;
            case INT -> ConstantDescs.CD_Integer;
            case LONG -> ConstantDescs.CD_Long;
            case FLOAT -> ConstantDescs.CD_Float;
            case DOUBLE -> ConstantDescs.CD_Double;
            default -> null;
        };
    }

    private static ClassDesc primitiveType(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case BYTE -> ConstantDescs.CD_byte;
            case CHAR -> ConstantDescs.CD_char;
            case SHORT -> ConstantDescs.CD_short;
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static String unboxMethod(TypeKind kind) {
        return switch (kind) {
            case BOOLEAN -> "booleanValue";
            case BYTE -> "byteValue";
            case CHAR -> "charValue";
            case SHORT -> "shortValue";
            case INT -> "intValue";
            case LONG -> "longValue";
            case FLOAT -> "floatValue";
            case DOUBLE -> "doubleValue";
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    /** Primitive on the stack -> its box. References are left alone. */
    private static void box(CodeBuilder b, TypeKind kind) {
        ClassDesc box = boxType(kind);
        if (box != null) {
            b.invokestatic(box, "valueOf", MethodTypeDesc.of(box, primitiveType(kind)));
        }
    }

    /** Object on the stack -> the given type (unboxing primitives, checkcasting references). */
    private static void unboxOrCast(CodeBuilder b, ClassDesc type) {
        TypeKind kind = TypeKind.from(type);
        ClassDesc box = boxType(kind);
        if (box != null) {
            b.checkcast(box);
            b.invokevirtual(box, unboxMethod(kind), MethodTypeDesc.of(primitiveType(kind)));
        } else if (!type.equals(ConstantDescs.CD_Object)) {
            b.checkcast(type);
        }
    }
}
