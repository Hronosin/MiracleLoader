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
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
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
@io.github.hronosin.miracle.api.Internal
public final class TransformRegistry {

    enum Where {
        HEAD("@HEAD"), RETURN("@RETURN"), INTERCEPT_HEAD("intercept@HEAD"), INTERCEPT_RETURN("intercept@RETURN");

        final String label;

        Where(String label) {
            this.label = label;
        }
    }

    /** {@code effects} is null for observe hooks and for intercept hooks RGCT couldn't read. */
    record HookPatch(String modId, String method, String descriptor, Where where, int hookId,
                     int priority, Set<EffectScan.Kind> effects) {
        boolean matches(MethodModel mm) {
            return mm.methodName().equalsString(method)
                    && (descriptor == null || mm.methodType().equalsString(descriptor));
        }

        String label() {
            return method + (descriptor == null ? "" : descriptor);
        }
    }

    /** A call {@code call} inside {@code method} that {@code modId} replaces; {@code id} is its HookDispatch slot. */
    record RedirectPatch(String modId, String method, String descriptor, CallScan.Member call, int id) {
        boolean matches(MethodModel mm) {
            return mm.methodName().equalsString(method)
                    && (descriptor == null || mm.methodType().equalsString(descriptor));
        }

        String label() {
            return method + (descriptor == null ? "" : descriptor);
        }

        /** The call site the replacement is bound to: the call's own type, receiver first if it has one. */
        MethodTypeDesc siteType() {
            MethodTypeDesc t = MethodTypeDesc.ofDescriptor(call.desc());
            return call.isStatic() ? t : t.insertParameterTypes(0, ClassDesc.ofInternalName(call.owner()));
        }
    }

    record RawPatch(String modId, ClassTransform transform) {
    }

    record RawBytesPatch(String modId, java.util.function.UnaryOperator<byte[]> transform) {
    }

    private static final class ClassPatches {
        final List<HookPatch> hooks = new ArrayList<>();
        final List<RedirectPatch> redirects = new ArrayList<>();
        final List<RawPatch> raws = new ArrayList<>();
        final List<RawBytesPatch> rawBytes = new ArrayList<>();
    }

    /**
     * Patched spots are invokedynamic sites bound to their hooks (see {@link HookDispatch}), unless
     * {@code -Dmiracle.directCalls=false}.
     */
    static final boolean DIRECT = !"false".equals(System.getProperty("miracle.directCalls"));

    // ConstantDescs first: touching MethodTypeDesc before it can trip a class-initialization
    // cycle in the JDK's constant API.
    private static final ClassDesc DISPATCH = ClassDesc.of(HookDispatch.class.getName());
    private static final MethodTypeDesc FIRE = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int,
            ConstantDescs.CD_Object);
    private static final DirectMethodHandleDesc BOOTSTRAP = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, DISPATCH, "bootstrap", MethodTypeDesc.of(ConstantDescs.CD_CallSite,
                    ConstantDescs.CD_MethodHandles_Lookup, ConstantDescs.CD_String, ConstantDescs.CD_MethodType,
                    ConstantDescs.CD_int));
    private static final MethodTypeDesc FIRE_DIRECT = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object);
    private static final MethodTypeDesc HEAD_DIRECT = MethodTypeDesc.of(ConstantDescs.CD_Object,
            ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType());
    private static final MethodTypeDesc RETURN_DIRECT = MethodTypeDesc.of(ConstantDescs.CD_Object,
            ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_Object);
    private static final MethodTypeDesc INTERCEPT_HEAD = MethodTypeDesc.of(ConstantDescs.CD_Object,
            ConstantDescs.CD_int, ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType());
    private static final MethodTypeDesc INTERCEPT_RETURN = MethodTypeDesc.of(ConstantDescs.CD_Object,
            ConstantDescs.CD_int, ConstantDescs.CD_Object, ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_Object);

    private final Map<String, ClassPatches> byClass = new LinkedHashMap<>();
    /** Obfuscated jar name -> readable name, from baked variants; only used to make the report readable. */
    private final Map<String, String> readable = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code "chl" -> "net.minecraft.world.entity.LivingEntity"}, {@code "chl#fF()F" -> "getJumpPower()F"}. */
    public void addReadableName(String jarName, String readableName) {
        readable.put(jarName, readableName);
    }
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
                              Where where, Object hook, int priority) {
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
        int id = HookDispatch.register(modId, at, hook, priority);
        boolean intercept = where == Where.INTERCEPT_HEAD || where == Where.INTERCEPT_RETURN;
        Set<EffectScan.Kind> effects = intercept ? EffectScan.scan(hook) : null;
        patchesFor(className).hooks.add(new HookPatch(modId, method, descriptor, where, id, priority, effects));
    }

    synchronized void addRedirect(String className, String modId, String method, String descriptor,
                                  Object call, Object replacement) {
        checkOpen();
        if (call == null || replacement == null) {
            throw new IllegalArgumentException("redirect needs both the call and its replacement");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method name is empty");
        }
        if (descriptor != null) {
            MethodTypeDesc.ofDescriptor(descriptor);
        }
        CallScan.Member member;
        try {
            member = CallScan.call(call);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mod '" + modId + "', redirect in " + className + "#" + method + ": "
                    + e.getMessage(), e);
        }
        ClassPatches patches = patchesFor(className);
        for (RedirectPatch other : patches.redirects) {
            boolean sameMethod = other.method().equals(method)
                    && (other.descriptor() == null || descriptor == null || other.descriptor().equals(descriptor));
            if (sameMethod && other.call().equals(member)) {
                throw new RgctConflictException("RGCT conflict at " + className + "#" + method
                        + (descriptor == null ? "" : descriptor) + ": '" + other.modId() + "' and '" + modId
                        + "' both redirect the call to " + member.label() + ". Only one mod can replace a call.");
            }
        }
        String at = className + "#" + method + (descriptor == null ? "" : descriptor) + " redirect " + member.label();
        int id = HookDispatch.registerRedirect(new HookDispatch.Redirection(modId, at, replacement));
        patches.redirects.add(new RedirectPatch(modId, method, descriptor, member, id));
    }

    synchronized void addRaw(String className, String modId, ClassTransform transform) {
        checkOpen();
        if (transform == null) {
            throw new IllegalArgumentException("transform is null");
        }
        patchesFor(className).raws.add(new RawPatch(modId, transform));
    }

    synchronized void addRawBytes(String className, String modId, java.util.function.UnaryOperator<byte[]> transform) {
        checkOpen();
        if (transform == null) {
            throw new IllegalArgumentException("transform is null");
        }
        patchesFor(className).rawBytes.add(new RawBytesPatch(modId, transform));
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
        p.redirects.forEach(r -> { if (!mods.contains(r.modId())) mods.add(r.modId()); });
        p.raws.forEach(r -> { if (!mods.contains(r.modId())) mods.add(r.modId()); });
        p.rawBytes.forEach(r -> { if (!mods.contains(r.modId())) mods.add(r.modId()); });
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
            String cls = readable.containsKey(e.getKey()) ? e.getKey() + "  (" + readable.get(e.getKey()) + ")" : e.getKey();
            lines.add("  " + cls);
            // Grouped by method, so everything layered onto one method sits together.
            List<HookPatch> sorted = new ArrayList<>(e.getValue().hooks);
            sorted.sort(java.util.Comparator.comparing(HookPatch::method)
                    .thenComparing(h -> h.descriptor() == null ? "" : h.descriptor())
                    .thenComparing(HookPatch::where)
                    .thenComparing(HookPatch::modId));
            for (HookPatch h : sorted) {
                String nice = readable.get(e.getKey() + "#" + h.label());
                String label = nice == null ? h.label() : h.label() + " (" + nice + ")";
                lines.add(String.format("    %-28s %-17s <- %s%s", label, h.where().label, h.modId(), details(h)));
            }
            for (RedirectPatch r : e.getValue().redirects) {
                String nice = readable.get(e.getKey() + "#" + r.label());
                String label = nice == null ? r.label() : r.label() + " (" + nice + ")";
                lines.add(String.format("    %-28s %-17s <- %s  [replaces %s]", label, "redirect", r.modId(), r.call().label()));
            }
            for (RawPatch r : e.getValue().raws) {
                lines.add(String.format("    %-28s %-17s <- %s (raw: you're on your own)", "<whole class>", "", r.modId()));
            }
            for (RawBytesPatch r : e.getValue().rawBytes) {
                lines.add(String.format("    %-28s %-17s <- %s (OSHI: brought its own tools)", "<whole class>", "rawBytes", r.modId()));
            }
        }
        return lines;
    }

    /**
     * What each mod patches, one sorted line per patch, in readable names where the baked
     * variants gave them: what {@code miracle.lock} pins. Two identical hooks of one mod on one
     * place read as one line with {@code x2}.
     */
    public synchronized Map<String, List<String>> pins() {
        Map<String, Map<String, Integer>> counted = new TreeMap<>();
        for (var e : byClass.entrySet()) {
            String cls = readable.getOrDefault(e.getKey(), e.getKey());
            for (HookPatch h : e.getValue().hooks) {
                String nice = readable.get(e.getKey() + "#" + h.label());
                String what = h.effects() == null
                        ? (h.where() == Where.HEAD || h.where() == Where.RETURN ? "observes" : "effects unknown")
                        : h.effects().isEmpty() ? "reads only"
                        : h.effects().stream().map(k -> k.label).collect(java.util.stream.Collectors.joining(", "));
                String line = cls + "#" + (nice != null ? nice : h.label()) + " " + h.where().label + " [" + what
                        + (h.priority() != 0 ? ", priority " + h.priority() : "") + "]";
                counted.computeIfAbsent(h.modId(), k -> new TreeMap<>()).merge(line, 1, Integer::sum);
            }
            for (RedirectPatch r : e.getValue().redirects) {
                String nice = readable.get(e.getKey() + "#" + r.label());
                String line = cls + "#" + (nice != null ? nice : r.label()) + " redirect [" + r.call().label() + "]";
                counted.computeIfAbsent(r.modId(), k -> new TreeMap<>()).merge(line, 1, Integer::sum);
            }
            for (RawPatch r : e.getValue().raws) {
                counted.computeIfAbsent(r.modId(), k -> new TreeMap<>()).merge(cls + " raw [whole class]", 1, Integer::sum);
            }
            for (RawBytesPatch r : e.getValue().rawBytes) {
                counted.computeIfAbsent(r.modId(), k -> new TreeMap<>()).merge(cls + " rawBytes [whole class]", 1, Integer::sum);
            }
        }
        Map<String, List<String>> out = new TreeMap<>();
        counted.forEach((mod, lines) -> out.put(mod,
                lines.entrySet().stream().map(x -> x.getKey() + (x.getValue() > 1 ? " x" + x.getValue() : "")).toList()));
        return out;
    }

    private static String details(HookPatch h) {
        if (h.where() == Where.HEAD || h.where() == Where.RETURN) {
            return "";
        }
        String what;
        if (h.effects() == null) {
            what = "effects unknown (not a lambda)";
        } else if (h.effects().isEmpty()) {
            what = "reads only";
        } else {
            what = h.effects().stream().map(k -> k.label).collect(java.util.stream.Collectors.joining(", "));
        }
        return "  [" + what + (h.priority() != 0 ? ", priority " + h.priority() : "") + "]";
    }

    /**
     * Possible conflicts, found before the game starts: several mods that may {@code set} the same
     * value (or cancel with a value) at the same priority. Only a warning, because hooks usually
     * set things conditionally; if they ever actually disagree, the game stops with a conflict
     * error naming them.
     */
    public List<String> lint() {
        List<String> warnings = new ArrayList<>();
        for (var e : new TreeMap<>(byClass).entrySet()) {
            Map<String, List<HookPatch>> byMethod = new TreeMap<>();
            for (HookPatch h : e.getValue().hooks) {
                if (h.effects() != null) {
                    byMethod.computeIfAbsent(h.method(), k -> new ArrayList<>()).add(h);
                }
            }
            for (var m : byMethod.entrySet()) {
                String label = e.getKey() + "#" + m.getKey();
                clash(warnings, m.getValue(), Where.INTERCEPT_RETURN, EffectScan.Kind.SETS_RETURN, label, "set its return value");
                clash(warnings, m.getValue(), Where.INTERCEPT_HEAD, EffectScan.Kind.SETS_ARGS, label, "set its arguments");
                clash(warnings, m.getValue(), Where.INTERCEPT_HEAD, EffectScan.Kind.CANCELS_WITH_VALUE, label, "cancel it with a value");
            }
        }
        return warnings;
    }

    private static void clash(List<String> out, List<HookPatch> hooks, Where where, EffectScan.Kind kind,
                              String label, String doing) {
        Map<Integer, java.util.Set<String>> modsByPriority = new TreeMap<>();
        for (HookPatch h : hooks) {
            if (h.where() == where && h.effects().contains(kind)) {
                modsByPriority.computeIfAbsent(h.priority(), p -> new java.util.TreeSet<>()).add(h.modId());
            }
        }
        modsByPriority.forEach((priority, mods) -> {
            if (mods.size() > 1) {
                out.add("RGCT: mods " + String.join(", ", mods.stream().map(x -> "'" + x + "'").toList())
                        + " may all " + doing + " (" + label + ") at priority " + priority
                        + ". Fine while they agree; if they ever don't, the game stops with a conflict error."
                        + " Give one of them a higher priority to decide.");
            }
        });
    }

    /** A class with its {@link Redirect} lambdas relinked so they load nothing early; or {@code bytes} itself. */
    public static byte[] relink(byte[] bytes, ClassLoader resolverLoader) {
        return Shapes.unlink(bytes, resolverLoader);
    }

    /**
     * Applies every patch registered for {@code className} (and relinks Redirect lambdas). Returns
     * the input array unchanged when there was nothing to do.
     */
    public byte[] transform(String className, byte[] bytes, ClassLoader resolverLoader) {
        bytes = Shapes.unlink(bytes, resolverLoader);
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
        Map<RedirectPatch, int[]> calls = new IdentityHashMap<>();
        patches.redirects.forEach(r -> calls.put(r, new int[] {-1}));
        ClassTransform transform = hookTransform(className, patches.hooks, patches.redirects, matched, calls);
        for (RawPatch raw : patches.raws) {
            Log.warn("RGCT: mod '" + raw.modId() + "' raw-patches " + className
                    + ". If it breaks, that one is to blame.");
            transform = transform.andThen(raw.transform());
        }

        byte[] out = patches.hooks.isEmpty() && patches.redirects.isEmpty() && patches.raws.isEmpty()
                ? bytes : cf.transformClass(model, transform);
        for (RawBytesPatch p : patches.rawBytes) {
            Log.warn("OSHI: mod '" + p.modId() + "' brings its own hooks for " + className
                    + ". Old school rules: you break it, you bought it.");
            byte[] next = p.transform().apply(out);
            if (next == null || next.length == 0) {
                throw new IllegalStateException("mod '" + p.modId() + "' returned no bytes for " + className);
            }
            out = next;
        }

        for (RedirectPatch r : patches.redirects) {
            int found = calls.get(r)[0];
            if (found < 0) {
                Log.warn("RGCT: mod '" + r.modId() + "' redirects a call in " + className + "#" + r.label()
                        + ", but no such method with a body exists. Wrong game version?");
            } else if (found == 0) {
                Log.warn("RGCT: mod '" + r.modId() + "' redirects " + r.call().label() + " in " + className + "#"
                        + r.label() + ", but that method never makes that call. Wrong game version?");
            }
        }
        for (HookPatch h : patches.hooks) {
            if (!matched.contains(h)) {
                Log.warn("RGCT: mod '" + h.modId() + "' targets " + className + "#" + h.label()
                        + ", but no such method with a body exists. Wrong game version?");
            }
        }
        return out;
    }

    private static ClassTransform hookTransform(String className, List<HookPatch> hooks, List<RedirectPatch> redirects,
                                                Set<HookPatch> matched, Map<RedirectPatch, int[]> calls) {
        if (hooks.isEmpty() && redirects.isEmpty()) {
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
                List<RedirectPatch> here = new ArrayList<>();
                for (RedirectPatch r : redirects) {
                    if (r.matches(mm)) {
                        here.add(r);
                        int[] n = calls.get(r);
                        if (n[0] < 0) {
                            n[0] = 0;
                        }
                    }
                }
                if (!found.isEmpty() || !here.isEmpty()) {
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
                            ids(found.get(Where.HEAD)), ids(found.get(Where.RETURN)), headSite, returnSite, here, calls);
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
        private final List<RedirectPatch> redirects;
        private final Map<RedirectPatch, int[]> calls;
        private int returnTemp = -1;

        Injector(MethodShape shape, int[] headIds, int[] returnIds, int headSite, int returnSite,
                 List<RedirectPatch> redirects, Map<RedirectPatch, int[]> calls) {
            this.shape = shape;
            this.headIds = headIds;
            this.returnIds = returnIds;
            this.headSite = headSite;
            this.returnSite = returnSite;
            this.redirects = redirects;
            this.calls = calls;
        }

        boolean isEmpty() {
            return headIds.length == 0 && returnIds.length == 0 && headSite < 0 && returnSite < 0 && redirects.isEmpty();
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
            if (e instanceof InvokeInstruction ii && !redirects.isEmpty()) {
                for (RedirectPatch r : redirects) {
                    if (r.call().matches(ii)) {
                        calls.get(r)[0]++;
                        // The arguments (and receiver) are already on the stack, exactly as the call wanted them.
                        b.invokedynamic(DynamicCallSiteDesc.of(BOOTSTRAP, "redirect", r.siteType(), r.id()));
                        return;
                    }
                }
            }
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
            if (DIRECT) {
                loadSelf(b, passSelf);
                b.invokedynamic(DynamicCallSiteDesc.of(BOOTSTRAP, "fire", FIRE_DIRECT, id));
                return;
            }
            b.loadConstant(id);
            loadSelf(b, passSelf);
            b.invokestatic(DISPATCH, "fire", FIRE);
        }

        /** Calls the dispatcher for an intercept site: the self, args (and return value) are on the stack. */
        private void callSite(CodeBuilder b, String name, int site) {
            if (DIRECT) {
                b.invokedynamic(DynamicCallSiteDesc.of(BOOTSTRAP, name,
                        name.equals("interceptHead") ? HEAD_DIRECT : RETURN_DIRECT, site));
            } else {
                b.invokestatic(DISPATCH, name, name.equals("interceptHead") ? INTERCEPT_HEAD : INTERCEPT_RETURN);
            }
        }

        /** The old calls take the site id first. */
        private void siteId(CodeBuilder b, int site) {
            if (!DIRECT) {
                b.loadConstant(site);
            }
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
            if (params.isEmpty()) {
                b.getstatic(DISPATCH, "NO_ARGS", ConstantDescs.CD_Object.arrayType());
                return;
            }
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

            siteId(b, headSite);
            loadSelf(b, selfAvailableAtHead());
            b.aload(argsSlot);
            callSite(b, "interceptHead", headSite);
            // stack: result (PROCEED, or the value to return because the method was cancelled)
            b.dup();
            b.getstatic(DISPATCH, "PROCEED", ConstantDescs.CD_Object);
            Label proceed = b.newLabel();
            b.if_acmpeq(proceed);
            if (shape.returnsVoid()) {
                b.pop();
                b.return_();
            } else {
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
            if (shape.returnsVoid()) {
                siteId(b, returnSite);
                loadSelf(b, !shape.isStatic());
                packArgs(b);
                b.aconst_null();
                callSite(b, "interceptReturn", returnSite);
                b.pop();
                return;
            }
            TypeKind kind = TypeKind.from(shape.returnType());
            if (returnTemp < 0) {
                returnTemp = b.allocateLocal(kind);
            }
            b.storeLocal(kind, returnTemp);
            siteId(b, returnSite);
            loadSelf(b, !shape.isStatic());
            packArgs(b);
            b.loadLocal(kind, returnTemp);
            box(b, kind);
            callSite(b, "interceptReturn", returnSite);
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
