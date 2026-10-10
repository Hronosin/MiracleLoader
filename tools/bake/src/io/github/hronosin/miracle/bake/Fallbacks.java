package io.github.hronosin.miracle.bake;

import java.lang.classfile.Attribute;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li>constructors and static initializers of the partial class are dropped too;</li>
 *   <li>a <b>named nested class</b> is whole, not partial: it replaces the real nested class of
 *       the same name (header, fields, constructors and all), or is added;</li>
 *   <li>anonymous and local classes, and lambda bodies, are renamed
 *       ({@code Outer$fallback$<v>$1}, {@code lambda$fallback$<v>$...}) so they can't collide with
 *       the real class's.</li>
 * </ul>
 * What only the replaced code used (the real class's lambda bodies, anonymous and local classes)
 * is dropped, so its references don't count as holes. Classes that exist only in the fallback
 * folder are added as they are.
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
        String tag = version.replaceAll("[^A-Za-z0-9]", "_");
        Set<String> mainNames = new HashSet<>();
        main.values().forEach(m -> mainNames.add(m.thisClass().asInternalName()));

        Map<String, ClassModel> parsed = new LinkedHashMap<>();
        for (var e : fb.entrySet()) {
            ClassModel f = cf.parse(e.getValue());
            parsed.put(f.thisClass().asInternalName(), f);
        }

        // --- names: what of the fallback is renamed, so it can't collide with the main code ---
        Map<String, String> classRenames = new HashMap<>();
        Map<String, Map<String, String>> methodRenames = new HashMap<>();
        List<String> byLength = new ArrayList<>(parsed.keySet());
        byLength.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        for (String name : byLength) {
            ClassModel f = parsed.get(name);
            if (!mainNames.contains(host(f))) {
                continue; // a nest of the fallback's own: added as it is
            }
            if (name.equals(host(f))) {
                Map<String, String> lambdas = new HashMap<>();
                for (MethodModel m : f.methods()) {
                    String n = m.methodName().stringValue();
                    if (m.flags().has(AccessFlag.SYNTHETIC) && n.startsWith("lambda$")) {
                        lambdas.put(n, "lambda$fallback$" + tag + "$" + n.substring("lambda$".length()));
                    }
                }
                if (!lambdas.isEmpty()) {
                    methodRenames.put(name, lambdas);
                }
                continue;
            }
            Optional<InnerClassInfo> self = selfEntry(f);
            if (self.isPresent() && self.get().outerClass().isEmpty()) {
                // anonymous or local: Outer$1, Outer$1Local -> Outer$fallback$<v>$1...
                String enclosing = enclosing(f);
                String renamedEnclosing = classRenames.getOrDefault(enclosing, enclosing);
                String rest = name.startsWith(enclosing + "$") ? name.substring(enclosing.length() + 1) : name.replace('/', '_');
                classRenames.put(name, renamedEnclosing + "$fallback$" + tag + "$" + rest);
            } else if (self.isPresent()) {
                String outer = self.get().outerClass().get().asInternalName();
                if (classRenames.containsKey(outer) && name.startsWith(outer)) {
                    classRenames.put(name, classRenames.get(outer) + name.substring(outer.length()));
                }
            }
        }
        Renamer renamer = new Renamer(classRenames, methodRenames);
        Map<String, ClassModel> renamed = new LinkedHashMap<>();
        for (ClassModel f : parsed.values()) {
            ClassModel r = renamer.touches(f) ? cf.parse(renamer.rename(cf, f)) : f;
            renamed.put(r.thisClass().asInternalName(), r);
        }

        // --- merge -----------------------------------------------------------------------------
        Map<String, ClassModel> out = new LinkedHashMap<>(main);
        List<String> notes = new ArrayList<>();
        int[] replaced = {0};
        int[] added = {0};
        Set<String> mergedHosts = new TreeSet<>();
        Set<String> fromFallback = new HashSet<>();
        for (ClassModel f : renamed.values()) {
            String name = f.thisClass().asInternalName();
            String path = name + ".class";
            String host = host(f);
            fromFallback.add(name);
            if (!mainNames.contains(host)) {
                out.put(path, f);
                notes.add("added class " + Remapper.dotted(name));
            } else if (name.equals(host)) {
                out.put(path, cf.parse(mergeClass(cf, main.get(path), f, replaced, added, notes)));
                mergedHosts.add(host);
            } else {
                notes.add((main.containsKey(path) ? "replaced class " : "added class ") + Remapper.dotted(name));
                out.put(path, f);
                mergedHosts.add(host);
            }
        }
        prune(cf, out, mergedHosts, fromFallback, notes);
        return new Result(out, replaced[0], added[0], notes);
    }

    // --- one partial class ------------------------------------------------------------------------

    private static String key(MethodModel m) {
        return m.methodName().stringValue() + m.methodType().stringValue();
    }

    private static boolean isFallbackLambda(MethodModel m) {
        return m.flags().has(AccessFlag.SYNTHETIC) && m.methodName().stringValue().startsWith("lambda$fallback$");
    }

    private static byte[] mergeClass(ClassFile cf, ClassModel real, ClassModel partial,
                                     int[] replaced, int[] added, List<String> notes) {
        String who = real.thisClass().asInternalName().replace('/', '.');
        Map<String, MethodModel> realMethods = new HashMap<>();
        real.methods().forEach(m -> realMethods.put(key(m), m));

        List<MethodModel> take = new ArrayList<>();
        Set<String> drop = new HashSet<>();
        for (MethodModel m : partial.methods()) {
            String name = m.methodName().stringValue();
            if (m.code().isEmpty() || name.equals("<init>") || name.equals("<clinit>")
                    || name.equals("$deserializeLambda$")) {
                continue; // declarations, the partial class's own plumbing
            }
            if (isFallbackLambda(m)) {
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

        // The nest: the real class's nested classes, and the fallback's (renamed, or whole replacements).
        Map<String, InnerClassInfo> inner = new LinkedHashMap<>();
        Set<String> members = new java.util.LinkedHashSet<>();
        for (ClassModel c : List.of(real, partial)) {
            c.findAttribute(java.lang.classfile.Attributes.innerClasses()).ifPresent(a -> a.classes()
                    .forEach(i -> inner.putIfAbsent(i.innerClass().asInternalName(), i)));
            c.findAttribute(java.lang.classfile.Attributes.nestMembers()).ifPresent(a -> a.nestMembers()
                    .forEach(m -> members.add(m.asInternalName())));
        }

        return cf.transformClass(real, new ClassTransform() {
            @Override
            public void accept(ClassBuilder b, ClassElement e) {
                if (e instanceof MethodModel m && drop.contains(key(m))) {
                    return;
                }
                if (e instanceof InnerClassesAttribute || e instanceof NestMembersAttribute) {
                    return; // merged ones at the end
                }
                b.with(e);
            }

            @Override
            public void atEnd(ClassBuilder b) {
                for (MethodModel m : take) {
                    MethodModel r = isFallbackLambda(m) ? null : realMethods.get(key(m));
                    int flags = r != null ? r.flags().flagsMask() : m.flags().flagsMask();
                    b.withMethod(m.methodName().stringValue(), m.methodTypeSymbol(), flags, mb -> {
                        for (MethodElement me : m) {
                            if (me instanceof CodeModel || me instanceof Attribute<?>) {
                                mb.with(me);
                            }
                        }
                    });
                }
                if (!inner.isEmpty()) {
                    b.with(InnerClassesAttribute.of(List.copyOf(inner.values())));
                }
                if (!members.isEmpty()) {
                    b.with(NestMembersAttribute.ofSymbols(members.stream().map(ClassDesc::ofInternalName).toList()));
                }
            }
        });
    }

    // --- what only the replaced code used ---------------------------------------------------------

    /**
     * Drops, from the nests a fallback touched, the real code's lambda bodies and anonymous and
     * local classes that nothing kept uses any more. {@code $deserializeLambda$} doesn't keep a
     * lambda alive: where it would make a dropped one, it throws, as for any unknown lambda.
     */
    private static void prune(ClassFile cf, Map<String, ClassModel> out, Set<String> hosts,
                              Set<String> fromFallback, List<String> notes) {
        if (hosts.isEmpty()) {
            return;
        }
        Map<String, ClassModel> byName = new HashMap<>();
        out.values().forEach(c -> byName.put(c.thisClass().asInternalName(), c));

        // Candidates: anonymous and local classes of the real code (with whatever is nested in
        // them), and the real code's lambda bodies in the merged classes.
        Set<String> candidateClasses = new HashSet<>();
        for (ClassModel c : out.values()) {
            String name = c.thisClass().asInternalName();
            if (hosts.contains(host(c)) && !fromFallback.contains(name)) {
                Optional<InnerClassInfo> self = selfEntry(c);
                if (self.isPresent() && self.get().outerClass().isEmpty()) {
                    candidateClasses.add(name);
                }
            }
        }
        Set<String> candidateMethods = new HashSet<>();
        for (String h : hosts) {
            ClassModel c = byName.get(h);
            if (c == null) {
                continue;
            }
            for (MethodModel m : c.methods()) {
                String n = m.methodName().stringValue();
                if (m.flags().has(AccessFlag.SYNTHETIC) && n.startsWith("lambda$") && !n.startsWith("lambda$fallback$")) {
                    candidateMethods.add(h + "." + n + m.methodType().stringValue());
                }
            }
        }
        if (candidateClasses.isEmpty() && candidateMethods.isEmpty()) {
            return;
        }

        Set<String> liveClasses = new HashSet<>();
        Set<String> liveMethods = new HashSet<>();
        Deque<MethodModel> work = new ArrayDeque<>();
        for (ClassModel c : out.values()) {
            String name = c.thisClass().asInternalName();
            if (group(name, candidateClasses) != null) {
                continue;
            }
            for (MethodModel m : c.methods()) {
                String id = name + "." + m.methodName().stringValue() + m.methodType().stringValue();
                if (!candidateMethods.contains(id) && !m.methodName().stringValue().equals("$deserializeLambda$")) {
                    work.add(m);
                }
            }
        }
        while (!work.isEmpty()) {
            MethodModel m = work.poll();
            Set<String> classRefs = new HashSet<>();
            Set<String> methodRefs = new HashSet<>();
            references(m, classRefs, methodRefs);
            for (String ref : classRefs) {
                String g = group(ref, candidateClasses);
                if (g != null && liveClasses.add(g)) {
                    for (ClassModel c : out.values()) {
                        String n = c.thisClass().asInternalName();
                        if (n.equals(g) || n.startsWith(g + "$")) {
                            c.methods().forEach(work::add);
                        }
                    }
                }
            }
            for (String ref : methodRefs) {
                if (candidateMethods.contains(ref) && liveMethods.add(ref)) {
                    String owner = ref.substring(0, ref.indexOf('.'));
                    for (MethodModel lm : byName.get(owner).methods()) {
                        if (ref.equals(owner + "." + lm.methodName().stringValue() + lm.methodType().stringValue())) {
                            work.add(lm);
                        }
                    }
                }
            }
        }

        Set<String> deadClasses = new TreeSet<>();
        for (ClassModel c : out.values()) {
            String n = c.thisClass().asInternalName();
            String g = group(n, candidateClasses);
            if (g != null && !liveClasses.contains(g)) {
                deadClasses.add(n);
            }
        }
        Set<String> deadMethods = new TreeSet<>(candidateMethods);
        deadMethods.removeAll(liveMethods);
        if (deadClasses.isEmpty() && deadMethods.isEmpty()) {
            return;
        }
        deadClasses.forEach(n -> {
            out.remove(n + ".class");
            notes.add("dropped " + Remapper.dotted(n) + " (only replaced code used it)");
        });
        Map<String, Integer> lambdasPerClass = new java.util.TreeMap<>();
        deadMethods.forEach(id -> lambdasPerClass.merge(id.substring(0, id.indexOf('.')), 1, Integer::sum));
        lambdasPerClass.forEach((owner, n) -> notes.add("dropped " + n + " lambda bod" + (n == 1 ? "y" : "ies")
                + " in " + Remapper.dotted(owner) + " (only replaced code used " + (n == 1 ? "it" : "them") + ")"));

        for (var e : out.entrySet()) {
            ClassModel c = e.getValue();
            if (!hosts.contains(host(c))) {
                continue;
            }
            String name = c.thisClass().asInternalName();
            boolean mentions = deadMethods.stream().anyMatch(id -> id.startsWith(name + "."))
                    || c.findAttribute(java.lang.classfile.Attributes.innerClasses()).map(a -> a.classes().stream()
                            .anyMatch(i -> deadClasses.contains(i.innerClass().asInternalName()))).orElse(false)
                    || c.findAttribute(java.lang.classfile.Attributes.nestMembers()).map(a -> a.nestMembers().stream()
                            .anyMatch(m -> deadClasses.contains(m.asInternalName()))).orElse(false);
            if (mentions) {
                e.setValue(cf.parse(without(cf, c, deadClasses, deadMethods)));
            }
        }
    }

    private static byte[] without(ClassFile cf, ClassModel c, Set<String> deadClasses, Set<String> deadMethods) {
        String name = c.thisClass().asInternalName();
        return cf.transformClass(c, (b, e) -> {
            switch (e) {
                case MethodModel m when deadMethods.contains(name + "." + m.methodName().stringValue() + m.methodType().stringValue()) -> {
                }
                case MethodModel m when m.methodName().stringValue().equals("$deserializeLambda$") ->
                        b.transformMethod(m, MethodTransform.transformingCode(unmake(deadMethods)));
                case InnerClassesAttribute a -> {
                    List<InnerClassInfo> keep = a.classes().stream()
                            .filter(i -> !deadClasses.contains(i.innerClass().asInternalName())).toList();
                    if (!keep.isEmpty()) {
                        b.with(InnerClassesAttribute.of(keep));
                    }
                }
                case NestMembersAttribute a -> {
                    List<ClassEntry> keep = a.nestMembers().stream()
                            .filter(m -> !deadClasses.contains(m.asInternalName())).toList();
                    if (!keep.isEmpty()) {
                        b.with(NestMembersAttribute.of(keep));
                    }
                }
                default -> b.with(e);
            }
        });
    }

    /** In {@code $deserializeLambda$}: a dropped lambda isn't made, the call throws instead. */
    private static CodeTransform unmake(Set<String> deadMethods) {
        return (b, e) -> {
            if (e instanceof InvokeDynamicInstruction indy && indy.bootstrapArgs().stream().anyMatch(a ->
                    a instanceof DirectMethodHandleDesc h && deadMethods.contains(handleId(h)))) {
                for (ClassDesc p : indy.typeSymbol().parameterList().reversed()) {
                    if (TypeKind.from(p).slotSize() == 2) {
                        b.pop2();
                    } else {
                        b.pop();
                    }
                }
                ClassDesc iae = ClassDesc.of("java.lang.IllegalArgumentException");
                b.new_(iae).dup().ldc("Invalid lambda deserialization: dropped by a fallback")
                        .invokespecial(iae, "<init>", MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V")).athrow();
                return;
            }
            b.with(e);
        };
    }

    private static String handleId(DirectMethodHandleDesc h) {
        return Remapper.internal(h.owner()) + "." + h.methodName() + h.lookupDescriptor();
    }

    /** The classes and methods a method's code names. */
    private static void references(MethodModel m, Set<String> classes, Set<String> methods) {
        descriptorClasses(m.methodType().stringValue(), classes);
        Optional<CodeModel> code = m.code();
        if (code.isEmpty()) {
            return;
        }
        for (CodeElement e : code.get()) {
            switch (e) {
                case InvokeInstruction ii -> {
                    classes.add(ii.owner().asInternalName());
                    methods.add(ii.owner().asInternalName() + "." + ii.name().stringValue() + ii.type().stringValue());
                    descriptorClasses(ii.type().stringValue(), classes);
                }
                case FieldInstruction fi -> {
                    classes.add(fi.owner().asInternalName());
                    descriptorClasses(fi.type().stringValue(), classes);
                }
                case NewObjectInstruction n -> classes.add(n.className().asInternalName());
                case TypeCheckInstruction tc -> descriptorClasses(tc.type().asInternalName(), classes);
                case NewReferenceArrayInstruction n -> descriptorClasses(n.componentType().asInternalName(), classes);
                case NewMultiArrayInstruction n -> descriptorClasses(n.arrayType().asInternalName(), classes);
                case ConstantInstruction.LoadConstantInstruction lc -> constantReferences(lc.constantValue(), classes, methods);
                case InvokeDynamicInstruction indy -> {
                    constantReferences(indy.bootstrapMethod(), classes, methods);
                    indy.bootstrapArgs().forEach(a -> constantReferences(a, classes, methods));
                    descriptorClasses(indy.typeSymbol().descriptorString(), classes);
                }
                default -> {
                }
            }
        }
    }

    private static void constantReferences(ConstantDesc c, Set<String> classes, Set<String> methods) {
        switch (c) {
            case ClassDesc cd -> descriptorClasses(cd.descriptorString(), classes);
            case DirectMethodHandleDesc h -> {
                classes.add(Remapper.internal(h.owner()));
                methods.add(handleId(h));
                descriptorClasses(h.lookupDescriptor(), classes);
            }
            case MethodTypeDesc mt -> descriptorClasses(mt.descriptorString(), classes);
            default -> {
            }
        }
    }

    private static final Pattern OBJECT = Pattern.compile("L([^;]+);");

    private static void descriptorClasses(String desc, Set<String> classes) {
        if (!desc.contains(";")) {
            classes.add(desc); // a plain internal name (an array's is a descriptor)
            return;
        }
        Matcher m = OBJECT.matcher(desc);
        while (m.find()) {
            classes.add(m.group(1));
        }
    }

    /** The candidate class that is {@code name} or encloses it by name, if any. */
    private static String group(String name, Set<String> candidates) {
        for (String c : candidates) {
            if (name.equals(c) || name.startsWith(c + "$")) {
                return c;
            }
        }
        return null;
    }

    // --- nests --------------------------------------------------------------------------------------

    /** The top-level class of a class's nest: its NestHost, or itself. */
    private static String host(ClassModel c) {
        return c.findAttribute(java.lang.classfile.Attributes.nestHost())
                .map(NestHostAttribute::nestHost).map(ClassEntry::asInternalName)
                .orElse(c.thisClass().asInternalName());
    }

    /** The class's own entry in its InnerClasses attribute: present for every nested class. */
    private static Optional<InnerClassInfo> selfEntry(ClassModel c) {
        String self = c.thisClass().asInternalName();
        return c.findAttribute(java.lang.classfile.Attributes.innerClasses())
                .flatMap(a -> a.classes().stream().filter(i -> i.innerClass().asInternalName().equals(self)).findFirst());
    }

    /** The class an anonymous or local class was written in. */
    private static String enclosing(ClassModel c) {
        Optional<EnclosingMethodAttribute> em = c.findAttribute(java.lang.classfile.Attributes.enclosingMethod());
        if (em.isPresent()) {
            return em.get().enclosingClass().asInternalName();
        }
        String self = c.thisClass().asInternalName();
        return self.contains("$") ? self.substring(0, self.lastIndexOf('$')) : host(c);
    }
}
