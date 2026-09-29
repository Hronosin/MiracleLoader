package io.github.hronosin.miracle.bake;

import io.github.hronosin.miracle.bake.VersionDict.ClassInfo;

import java.lang.classfile.Attribute;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.PseudoInstruction;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Translates a mod's classes from readable Minecraft names into one version's jar names, using
 * that version's {@link VersionDict}. Every game reference is looked up; whatever the dictionary
 * doesn't have is collected in {@link #missing}, so a version with holes is reported and never
 * baked.
 */
final class Remapper {

    /** What the mod's own classes look like (readable names), for inherited lookups. */
    record ModClass(String name, String superName, List<String> interfaces,
                    Set<String> methods, Set<String> fields, boolean isInterface) {
    }

    private static final String RGCT = "io/github/hronosin/miracle/rgct/Rgct";
    private static final String CLASS_TARGET = RGCT + "$ClassTarget";
    private static final String METHOD_TARGET = RGCT + "$MethodTarget";
    private static final String TARGET_DESC = "(Ljava/lang/String;)L" + CLASS_TARGET + ";";
    private static final String METHOD1_DESC = "(Ljava/lang/String;)L" + METHOD_TARGET + ";";
    private static final String METHOD2_DESC = "(Ljava/lang/String;Ljava/lang/String;)L" + METHOD_TARGET + ";";
    private static final String UNKNOWN_TARGET = "\0unknown";

    private final VersionDict dict;
    private final Set<String> universe;
    private final Map<String, ModClass> mod;
    private final ClassFile cf;

    /** Readable descriptions of every reference this version doesn't have. */
    final Set<String> missing = new TreeSet<>();
    /** Game references translated (or confirmed, for unobfuscated versions). */
    final Set<String> checked = new HashSet<>();
    /** References into libraries RGCT can't see (Brigadier, DFU...): kept as they are. */
    final Set<String> unverified = new TreeSet<>();
    /**
     * Jar name -> readable name, only for RGCT targets, so the loader's startup report stays
     * readable on obfuscated versions. A handful of lines, not the mappings.
     */
    final Map<String, String> targetNames = new java.util.TreeMap<>();

    Remapper(VersionDict dict, Set<String> universe, Map<String, ModClass> mod,
             ClassHierarchyResolver jarHierarchy) {
        this.dict = dict;
        this.universe = universe;
        this.mod = mod;
        this.cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                modHierarchy().orElse(jarHierarchy).orElse(ClassHierarchyResolver.defaultResolver())
                        .orElse(lenient())));
    }

    /** Library classes the resolver can't see get treated as plain classes. Good enough for stack maps of mod code. */
    private static ClassHierarchyResolver lenient() {
        return cd -> ClassHierarchyResolver.ClassHierarchyInfo.ofClass(ConstantDescs.CD_Object);
    }

    private ClassHierarchyResolver modHierarchy() {
        return cd -> {
            ModClass m = mod.get(internal(cd));
            if (m == null) {
                return null;
            }
            if (m.isInterface()) {
                return ClassHierarchyResolver.ClassHierarchyInfo.ofInterface();
            }
            return ClassHierarchyResolver.ClassHierarchyInfo.ofClass(
                    m.superName() == null ? null : ClassDesc.ofInternalName(mapClass(m.superName())));
        };
    }

    // --- names ----------------------------------------------------------------------------------

    String mapClass(String named) {
        if (named.startsWith("[")) {
            return mapDesc(named);
        }
        ClassInfo c = dict.get(named);
        if (c != null) {
            checked.add(named);
            return c.obf;
        }
        if (universe.contains(named)) {
            missing.add("class " + dotted(named));
        }
        return named;
    }

    ClassDesc mapCd(ClassDesc cd) {
        if (cd.isPrimitive()) {
            return cd;
        }
        if (cd.isArray()) {
            return mapCd(cd.componentType()).arrayType();
        }
        return ClassDesc.ofInternalName(mapClass(internal(cd)));
    }

    String mapDesc(String desc) {
        StringBuilder out = new StringBuilder(desc.length());
        for (int i = 0; i < desc.length(); i++) {
            char ch = desc.charAt(i);
            out.append(ch);
            if (ch == 'L') {
                int end = desc.indexOf(';', i);
                out.append(mapClass(desc.substring(i + 1, end))).append(';');
                i = end;
            }
        }
        return out.toString();
    }

    MethodTypeDesc mapMtd(MethodTypeDesc m) {
        return MethodTypeDesc.ofDescriptor(mapDesc(m.descriptorString()));
    }

    // --- members --------------------------------------------------------------------------------

    /** Jar name of a method or field as referenced from mod code. */
    String mapMember(String owner, String name, String desc, boolean method) {
        if (owner.startsWith("[")) {
            return name; // array clone() and friends
        }
        if (!dict.classes.containsKey(owner) && !mod.containsKey(owner) && !universe.contains(owner)) {
            return name; // JDK or library class: nothing to translate, nothing to check
        }
        String what = (method ? "method " : "field ") + dotted(owner) + "#" + name + (method ? desc : ":" + desc);
        if (name.equals("<init>") || name.equals("<clinit>")) {
            ClassInfo c = dict.get(owner);
            if (c != null) {
                if (c.methods.containsKey(name + desc)) {
                    checked.add(what);
                } else {
                    missing.add(what);
                }
            }
            return name;
        }

        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(owner);
        boolean library = false;
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (c == null || !seen.add(c)) {
                continue;
            }
            ClassInfo gi = dict.get(c);
            if (gi != null) {
                String hit = method ? gi.methods.get(name + desc) : gi.fields.get(name + ":" + desc);
                if (hit != null) {
                    checked.add(what);
                    return hit;
                }
                queue.add(gi.superNamed);
                queue.addAll(gi.interfacesNamed);
            } else if (mod.containsKey(c)) {
                ModClass m = mod.get(c);
                if (method ? m.methods().contains(name + desc) : m.fields().contains(name + ":" + desc)) {
                    return name; // the mod's own member
                }
                queue.add(m.superName());
                queue.addAll(m.interfaces());
            } else if (universe.contains(c)) {
                // a game class this version doesn't have; already reported as a missing class
            } else if (Jdk.has(c, name, desc, method)) {
                return name; // inherited from java.lang.Object, an interface in the JDK...
            } else if (!Jdk.isJdk(c)) {
                library = true;
            }
        }
        if (library) {
            unverified.add(what);
        } else {
            missing.add(what);
        }
        return name;
    }

    /** If a mod method overrides a game method, it must take the game method's jar name. */
    private String overrideName(ModClass self, String name, String desc) {
        if (name.startsWith("<")) {
            return name;
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(self.superName());
        queue.addAll(self.interfaces());
        while (!queue.isEmpty()) {
            String c = queue.poll();
            if (c == null || !seen.add(c)) {
                continue;
            }
            ClassInfo gi = dict.get(c);
            if (gi != null) {
                String hit = gi.methods.get(name + desc);
                if (hit != null) {
                    checked.add("override " + dotted(c) + "#" + name + desc);
                    return hit;
                }
                queue.add(gi.superNamed);
                queue.addAll(gi.interfacesNamed);
            } else if (mod.containsKey(c)) {
                queue.add(mod.get(c).superName());
                queue.addAll(mod.get(c).interfaces());
            }
        }
        return name;
    }

    // --- whole class ----------------------------------------------------------------------------

    byte[] remap(ClassModel cm) {
        ModClass self = mod.get(cm.thisClass().asInternalName());
        return cf.build(cm.thisClass().asSymbol(), clb -> {
            clb.withVersion(cm.majorVersion(), cm.minorVersion());
            clb.withFlags(cm.flags().flagsMask());
            cm.superclass().ifPresent(s -> clb.withSuperclass(ClassDesc.ofInternalName(mapClass(s.asInternalName()))));
            clb.withInterfaceSymbols(cm.interfaces().stream()
                    .map(i -> ClassDesc.ofInternalName(mapClass(i.asInternalName()))).toList());
            for (ClassElement e : cm) {
                switch (e) {
                    case FieldModel f -> copyField(clb, f);
                    case MethodModel m -> copyMethod(clb, cm, self, m);
                    case InnerClassesAttribute a -> copyInnerClasses(clb, a);
                    case EnclosingMethodAttribute a -> clb.with(EnclosingMethodAttribute.of(
                            mapCd(a.enclosingClass().asSymbol()),
                            a.enclosingMethodName().map(n -> n.stringValue()),
                            a.enclosingMethodTypeSymbol().map(this::mapMtd)));
                    case RecordAttribute a -> clb.with(RecordAttribute.of(a.components().stream()
                            .map(c -> RecordComponentInfo.of(c.name().stringValue(), mapCd(c.descriptorSymbol())))
                            .toList()));
                    case SignatureAttribute ignored -> {
                        // generic signatures only matter to compilers and reflection
                    }
                    case Attribute<?> a -> clb.with((ClassElement) a);
                    default -> {
                        // version, flags, superclass, interfaces: done above
                    }
                }
            }
        });
    }

    private void copyInnerClasses(ClassBuilder clb, InnerClassesAttribute a) {
        List<InnerClassInfo> keep = new ArrayList<>();
        for (InnerClassInfo i : a.classes()) {
            String inner = i.innerClass().asInternalName();
            if (!universe.contains(inner)) {
                keep.add(i); // the mod's own nested classes; game ones are only for reflection
            }
        }
        if (!keep.isEmpty()) {
            clb.with(InnerClassesAttribute.of(keep));
        }
    }

    private void copyField(ClassBuilder clb, FieldModel f) {
        clb.withField(f.fieldName().stringValue(), mapCd(f.fieldTypeSymbol()), fb -> {
            fb.withFlags(f.flags().flagsMask());
            for (FieldElement fe : f) {
                if (fe instanceof SignatureAttribute) {
                    continue;
                }
                if (fe instanceof Attribute<?>) {
                    fb.with(fe);
                }
            }
        });
    }

    private void copyMethod(ClassBuilder clb, ClassModel cm, ModClass self, MethodModel m) {
        String name = m.methodName().stringValue();
        String desc = m.methodType().stringValue();
        boolean canOverride = !m.flags().has(AccessFlag.STATIC) && !m.flags().has(AccessFlag.PRIVATE);
        String newName = canOverride && self != null ? overrideName(self, name, desc) : name;
        String where = cm.thisClass().asInternalName().replace('/', '.') + "#" + name;
        clb.withMethod(newName, MethodTypeDesc.ofDescriptor(mapDesc(desc)), m.flags().flagsMask(), mb -> {
            for (MethodElement me : m) {
                switch (me) {
                    case CodeModel code -> mb.transformCode(code, codeTransform(code, where));
                    case ExceptionsAttribute ex -> mb.with(ExceptionsAttribute.ofSymbols(ex.exceptions().stream()
                            .map(c -> mapCd(c.asSymbol())).toList()));
                    case SignatureAttribute ignored -> {
                    }
                    case Attribute<?> a -> mb.with((MethodElement) a);
                    default -> {
                    }
                }
            }
        });
    }

    // --- code -----------------------------------------------------------------------------------

    /** What to do with specific instructions, worked out before rewriting (RGCT strings need lookahead). */
    private record Plan(Map<Integer, List<ConstantDesc>> replaceLdc, Set<Integer> upgradeInvoke) {
    }

    private CodeTransform codeTransform(CodeModel code, String where) {
        List<CodeElement> elements = code.elementList();
        Plan plan = planRgctStrings(elements, where);
        int[] index = {0};
        return (b, e) -> {
            int i = index[0]++;
            if (i >= elements.size() || elements.get(i).getClass() != e.getClass()) {
                throw new IllegalStateException("code element order changed while baking " + where);
            }
            List<ConstantDesc> ldc = plan.replaceLdc().get(i);
            if (ldc != null) {
                ldc.forEach(b::loadConstant);
                return;
            }
            if (plan.upgradeInvoke().contains(i)) {
                b.invokevirtual(ClassDesc.ofInternalName(CLASS_TARGET), "method", MethodTypeDesc.ofDescriptor(METHOD2_DESC));
                return;
            }
            switch (e) {
                case FieldInstruction fi -> {
                    String owner = fi.owner().asInternalName();
                    String fdesc = fi.type().stringValue();
                    b.fieldAccess(fi.opcode(), mapCd(fi.owner().asSymbol()),
                            mapMember(owner, fi.name().stringValue(), fdesc, false), mapCd(fi.typeSymbol()));
                }
                case InvokeInstruction ii -> {
                    String owner = ii.owner().asInternalName();
                    String mdesc = ii.type().stringValue();
                    b.invoke(ii.opcode(), mapCd(ii.owner().asSymbol()),
                            mapMember(owner, ii.name().stringValue(), mdesc, true),
                            MethodTypeDesc.ofDescriptor(mapDesc(mdesc)), ii.isInterface());
                }
                case InvokeDynamicInstruction indy -> b.invokedynamic(mapIndy(indy));
                case TypeCheckInstruction tc -> {
                    ClassDesc t = mapCd(tc.type().asSymbol());
                    if (tc.opcode() == Opcode.INSTANCEOF) {
                        b.instanceOf(t);
                    } else {
                        b.checkcast(t);
                    }
                }
                case NewObjectInstruction n -> b.new_(mapCd(n.className().asSymbol()));
                case NewReferenceArrayInstruction n -> b.anewarray(mapCd(n.componentType().asSymbol()));
                case NewMultiArrayInstruction n -> b.multianewarray(mapCd(n.arrayType().asSymbol()), n.dimensions());
                case ConstantInstruction.LoadConstantInstruction lc -> b.loadConstant(mapConstant(lc.constantValue()));
                case ExceptionCatch ec -> {
                    if (ec.catchType().isPresent()) {
                        b.exceptionCatch(ec.tryStart(), ec.tryEnd(), ec.handler(), mapCd(ec.catchType().get().asSymbol()));
                    } else {
                        b.exceptionCatchAll(ec.tryStart(), ec.tryEnd(), ec.handler());
                    }
                }
                case LocalVariable ignored -> {
                    // debug info with old names; dropped
                }
                case LocalVariableType ignored -> {
                }
                default -> b.with(e);
            }
        };
    }

    private DynamicCallSiteDesc mapIndy(InvokeDynamicInstruction indy) {
        DirectMethodHandleDesc bsm = indy.bootstrapMethod();
        MethodTypeDesc type = indy.typeSymbol();
        List<ConstantDesc> args = indy.bootstrapArgs();
        String name = indy.name().stringValue();
        // A lambda for a game functional interface is named after the interface's (obfuscated) method.
        if (bsm.owner().equals(ClassDesc.of("java.lang.invoke.LambdaMetafactory"))
                && !type.returnType().isPrimitive() && !args.isEmpty() && args.getFirst() instanceof MethodTypeDesc sam) {
            String iface = internal(type.returnType());
            if (dict.classes.containsKey(iface) || universe.contains(iface)) {
                name = mapMember(iface, name, sam.descriptorString(), true);
            }
        }
        ConstantDesc[] mapped = args.stream().map(this::mapConstant).toArray(ConstantDesc[]::new);
        return DynamicCallSiteDesc.of(mapHandle(bsm), name, mapMtd(type), mapped);
    }

    private ConstantDesc mapConstant(ConstantDesc c) {
        return switch (c) {
            case ClassDesc cd -> mapCd(cd);
            case MethodTypeDesc m -> mapMtd(m);
            case DirectMethodHandleDesc h -> mapHandle(h);
            default -> c;
        };
    }

    private DirectMethodHandleDesc mapHandle(DirectMethodHandleDesc h) {
        String owner = internal(h.owner());
        String desc = h.lookupDescriptor();
        return switch (h.kind()) {
            case GETTER, SETTER, STATIC_GETTER, STATIC_SETTER -> MethodHandleDesc.ofField(h.kind(), mapCd(h.owner()),
                    mapMember(owner, h.methodName(), desc, false), ClassDesc.ofDescriptor(mapDesc(desc)));
            default -> MethodHandleDesc.ofMethod(h.kind(), mapCd(h.owner()),
                    mapMember(owner, h.methodName(), desc, true), MethodTypeDesc.ofDescriptor(mapDesc(desc)));
        };
    }

    /**
     * RGCT names its targets with strings: {@code rgct.target("...LivingEntity").method("getJumpPower", "()F")}.
     * Those strings are translated too. Obfuscation reuses short names across overloads
     * ({@code a}, {@code a}, {@code a}...), so a baked target always carries the exact
     * descriptor: {@code method(name)} becomes {@code method(name, descriptor)}.
     */
    private Plan planRgctStrings(List<CodeElement> els, String where) {
        Map<Integer, List<ConstantDesc>> replace = new HashMap<>();
        Set<Integer> upgrade = new HashSet<>();
        String current = null;
        for (int i = 0; i < els.size(); i++) {
            CodeElement e = els.get(i);
            if (isInvoke(e, RGCT, "target", TARGET_DESC)) {
                int p = prevReal(els, i - 1);
                if (p < 0 || !(ldcString(els.get(p)) != null)) {
                    current = UNKNOWN_TARGET;
                }
                continue;
            }
            String s = ldcString(e);
            if (s == null) {
                continue;
            }
            int j = nextReal(els, i + 1);
            if (j < 0) {
                continue;
            }
            if (isInvoke(els.get(j), RGCT, "target", TARGET_DESC)) {
                current = s.replace('.', '/');
                ClassInfo c = dict.get(current);
                if (c != null) {
                    checked.add("target " + s);
                    if (dict.obfuscated) {
                        replace.put(i, List.of(c.obf.replace('/', '.')));
                        targetNames.put(c.obf.replace('/', '.'), s);
                    }
                } else if (universe.contains(current)) {
                    missing.add("class " + s + " (RGCT target in " + where + ")");
                }
            } else if (isInvoke(els.get(j), CLASS_TARGET, "method", METHOD1_DESC)) {
                planMethod(current, s, null, i, -1, j, replace, upgrade, where);
            } else {
                String d = ldcString(els.get(j));
                int k = d == null ? -1 : nextReal(els, j + 1);
                if (k >= 0 && isInvoke(els.get(k), CLASS_TARGET, "method", METHOD2_DESC)) {
                    planMethod(current, s, d, i, j, k, replace, upgrade, where);
                    i = j; // the descriptor string is handled
                }
            }
        }
        return new Plan(replace, upgrade);
    }

    private void planMethod(String target, String name, String desc, int nameAt, int descAt, int invokeAt,
                            Map<Integer, List<ConstantDesc>> replace, Set<Integer> upgrade, String where) {
        if (target == null || UNKNOWN_TARGET.equals(target)) {
            if (dict.obfuscated) {
                missing.add("RGCT target in " + where + " isn't a string constant, so it can't be baked");
            }
            return;
        }
        ClassInfo c = dict.get(target);
        if (c == null) {
            return; // not a game class (another mod?), or already reported missing
        }
        String what = "RGCT target " + dotted(target) + "#" + name + (desc == null ? "" : desc);
        if (desc == null) {
            List<String> overloads = c.overloads.getOrDefault(name, List.of());
            if (overloads.isEmpty()) {
                missing.add(what);
                return;
            }
            if (overloads.size() > 1) {
                if (dict.obfuscated) {
                    missing.add(what + " is overloaded " + overloads + ": give the descriptor, "
                            + "obfuscated overloads don't share a name");
                }
                return;
            }
            desc = overloads.getFirst();
        }
        String jarName = c.methods.get(name + desc);
        if (jarName == null) {
            missing.add(what);
            return;
        }
        checked.add(what);
        if (!dict.obfuscated) {
            return;
        }
        targetNames.put(c.obf.replace('/', '.') + "#" + jarName + mapDesc(desc), name);
        if (descAt < 0) {
            replace.put(nameAt, List.of(jarName, mapDesc(desc)));
            upgrade.add(invokeAt);
        } else {
            replace.put(nameAt, List.of(jarName));
            replace.put(descAt, List.of(mapDesc(desc)));
        }
    }

    private static String ldcString(CodeElement e) {
        return e instanceof ConstantInstruction ci && ci.constantValue() instanceof String s ? s : null;
    }

    private static boolean isInvoke(CodeElement e, String owner, String name, String desc) {
        return e instanceof InvokeInstruction ii && ii.owner().asInternalName().equals(owner)
                && ii.name().equalsString(name) && ii.type().equalsString(desc);
    }

    private static int nextReal(List<CodeElement> els, int from) {
        for (int i = from; i < els.size(); i++) {
            if (els.get(i) instanceof Instruction) {
                return i;
            }
            if (!(els.get(i) instanceof PseudoInstruction)) {
                return -1;
            }
        }
        return -1;
    }

    private static int prevReal(List<CodeElement> els, int from) {
        for (int i = from; i >= 0; i--) {
            if (els.get(i) instanceof Instruction) {
                return i;
            }
        }
        return -1;
    }

    static String internal(ClassDesc cd) {
        String d = cd.descriptorString();
        return d.startsWith("L") ? d.substring(1, d.length() - 1) : d;
    }

    static String dotted(String internal) {
        return internal.replace('/', '.');
    }

    /** Checks inherited members against the running JDK. */
    static final class Jdk {
        private Jdk() {
        }

        static boolean isJdk(String internal) {
            return internal.startsWith("java/") || internal.startsWith("javax/") || internal.startsWith("jdk/")
                    || internal.startsWith("sun/");
        }

        static boolean has(String internal, String name, String desc, boolean method) {
            if (!isJdk(internal)) {
                return false;
            }
            Class<?> c;
            try {
                c = Class.forName(internal.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
            Deque<Class<?>> queue = new ArrayDeque<>();
            queue.add(c);
            while (!queue.isEmpty()) {
                Class<?> k = queue.poll();
                if (method) {
                    for (Method m : k.getDeclaredMethods()) {
                        if (m.getName().equals(name) && descriptor(m).equals(desc)) {
                            return true;
                        }
                    }
                } else {
                    for (Field f : k.getDeclaredFields()) {
                        if (f.getName().equals(name) && f.getType().descriptorString().equals(desc)
                                && !Modifier.isPrivate(f.getModifiers())) {
                            return true;
                        }
                    }
                }
                if (k.getSuperclass() != null) {
                    queue.add(k.getSuperclass());
                }
                queue.addAll(List.of(k.getInterfaces()));
            }
            // interfaces inherit Object's methods too
            return method && c.isInterface() && has("java/lang/Object", name, desc, true);
        }

        private static String descriptor(Method m) {
            StringBuilder sb = new StringBuilder("(");
            for (Class<?> p : m.getParameterTypes()) {
                sb.append(p.descriptorString());
            }
            return sb.append(')').append(m.getReturnType().descriptorString()).toString();
        }
    }
}
