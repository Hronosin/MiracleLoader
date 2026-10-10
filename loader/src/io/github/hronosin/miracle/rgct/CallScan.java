package io.github.hronosin.miracle.rgct;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out which call a {@link Redirect} shape names: a method reference ({@code Foo::bar}, or
 * {@code Foo::new} for a {@code new}) says it directly; a lambda ({@code f -> f.bar()}) is read, and
 * must make exactly one call. Done once, at
 * registration. A baked mod's method references already carry the version's own names, so this
 * finds the call as the game's bytecode spells it.
 */
final class CallScan {

    /** A method call as the bytecode names it. {@code kind} is a {@link MethodHandleInfo} REF_ kind. */
    record Member(int kind, String owner, String name, String desc) {

        /** Named by strings ({@link Rgct#call}): any call but a super one, static or not as the site says. */
        static final int NAMED = 100;
        /** Named by strings ({@link Rgct#superCall}): an invokespecial that isn't a constructor. */
        static final int SUPER = 101;

        boolean isStatic() {
            return kind == MethodHandleInfo.REF_invokeStatic;
        }

        boolean isNamed() {
            return kind == NAMED || kind == SUPER;
        }

        /** Static at this site: a named call can only tell from the instruction. */
        boolean isStaticAt(InvokeInstruction ii) {
            return isNamed() ? ii.opcode() == Opcode.INVOKESTATIC : isStatic();
        }

        /** The same call, however it was named (a method reference, a lambda, strings). */
        boolean sameCall(Member o) {
            return owner.equals(o.owner) && name.equals(o.name) && desc.equals(o.desc)
                    && isNew() == o.isNew() && (kind == SUPER) == (o.kind == SUPER);
        }

        /** A {@code new}: the constructor of {@code owner}. */
        boolean isNew() {
            return kind == MethodHandleInfo.REF_newInvokeSpecial;
        }

        boolean matches(InvokeInstruction ii) {
            return matches(ii, null);
        }

        /**
         * The call, as the bytecode spells it. javac spells a method reference with the class that
         * declares the method ({@code ServerLevel::getBlockRandomPos} is {@code Level.getBlockRandomPos}),
         * while a call site spells it with the type it's called on: with {@code supers}, a site
         * naming a subclass, a subinterface or an implementing class matches too. It calls the same method.
         */
        boolean matches(InvokeInstruction ii, Supers supers) {
            if (!ii.name().equalsString(name) || !ii.type().equalsString(desc)) {
                return false;
            }
            if (isNamed()) {
                // javac spells private calls (since Java 11, nestmates) invokevirtual/invokeinterface,
                // and only super calls invokespecial: the opcode tells them apart.
                boolean special = ii.opcode() == Opcode.INVOKESPECIAL;
                if (special != (kind == SUPER)) {
                    return false;
                }
                String site = ii.owner().asInternalName();
                return (site.equals(owner) || supers != null && supers.isSubtype(site, owner))
                        && (supers == null || supers.has(owner, name, desc));   // strings name a real method, or nothing
            }
            Opcode want = switch (kind) {
                case MethodHandleInfo.REF_invokeStatic -> Opcode.INVOKESTATIC;
                case MethodHandleInfo.REF_invokeInterface -> Opcode.INVOKEINTERFACE;
                case MethodHandleInfo.REF_newInvokeSpecial -> Opcode.INVOKESPECIAL;
                default -> Opcode.INVOKEVIRTUAL;
            };
            String site = ii.owner().asInternalName();
            if (site.equals(owner)) {
                return ii.opcode() == want;
            }
            if (supers == null || isNew()) {
                return false;
            }
            boolean opcode = ii.opcode() == want
                    || want == Opcode.INVOKEINTERFACE && ii.opcode() == Opcode.INVOKEVIRTUAL;   // a default method, called on a class
            return opcode && supers.isSubtype(site, owner);
        }

        String label() {
            if (kind == SUPER) {
                return "super " + owner.replace('/', '.') + "." + name + desc;
            }
            return isNew() ? "new " + owner.replace('/', '.') + desc.substring(0, desc.indexOf(')') + 1)
                    : owner.replace('/', '.') + "." + name + desc;
        }
    }

    private CallScan() {
    }

    /** The lambda behind a serializable shape, or null: the way back for one that wasn't relinked. */
    static SerializedLambda lambda(Object fn) {
        try {
            Method writeReplace = fn.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            return writeReplace.invoke(fn) instanceof SerializedLambda l ? l : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The call {@code fn} names. Throws, with a message for the mod author, if it can't tell. */
    static Member call(Object fn) {
        if (fn instanceof Rgct.Call c) {
            return new Member(c.isSuper ? Member.SUPER : Member.NAMED, c.owner.replace('.', '/'), c.name, c.descriptor);
        }
        if (fn instanceof Shapes.Named n) {
            if (n.captured.length != 0) {
                throw new IllegalArgumentException("the call to redirect can't capture anything: write it as Foo::bar");
            }
            Member m = n.member();
            if (m.name().startsWith("lambda$")) {
                m = onlyCallIn(n.loader, m);
            }
            return checked(m);
        }
        SerializedLambda l = lambda(fn);
        if (l == null) {
            throw new IllegalArgumentException("the call to redirect must be written as a method reference "
                    + "(Foo::bar) or a lambda making that one call");
        }
        if (l.getCapturedArgCount() != 0) {
            throw new IllegalArgumentException("the call to redirect can't capture anything: write it as Foo::bar");
        }
        Member m = new Member(l.getImplMethodKind(), l.getImplClass(), l.getImplMethodName(), l.getImplMethodSignature());
        if (m.owner().equals(l.getCapturingClass()) && m.name().startsWith("lambda$")) {
            m = onlyCallIn(fn.getClass().getClassLoader(), m);
        }
        return checked(m);
    }

    private static Member checked(Member m) {
        switch (m.kind()) {
            case MethodHandleInfo.REF_invokeStatic, MethodHandleInfo.REF_invokeVirtual,
                 MethodHandleInfo.REF_invokeInterface -> {
            }
            case MethodHandleInfo.REF_newInvokeSpecial -> {
            }
            default -> throw new IllegalArgumentException(m.label() + " is a private or super call, which a method"
                    + " reference can't name: name it with Rgct.call(...) or Rgct.superCall(...)");
        }
        return m;
    }

    /** {@code f -> f.bar()}: the one call inside the lambda's body. */
    private static Member onlyCallIn(ClassLoader loader, Member lambdaBody) {
        byte[] bytes;
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(lambdaBody.owner() + ".class")) {
            if (in == null) {
                throw new IllegalArgumentException("can't read " + lambdaBody.owner() + " to find the call to redirect");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("can't read " + lambdaBody.owner() + ": " + e.getMessage());
        }
        ClassModel cm = ClassFile.of().parse(bytes);
        for (MethodModel mm : cm.methods()) {
            if (!mm.methodName().equalsString(lambdaBody.name()) || !mm.methodType().equalsString(lambdaBody.desc())) {
                continue;
            }
            List<InvokeInstruction> calls = new ArrayList<>();
            for (CodeElement e : mm.code().orElseThrow()) {
                if (e instanceof InvokeInstruction ii) {
                    calls.add(ii);
                }
            }
            if (calls.size() != 1) {
                throw new IllegalArgumentException("the lambda naming the call to redirect makes " + calls.size()
                        + " calls; it must make exactly one (or write it as Foo::bar)");
            }
            InvokeInstruction ii = calls.getFirst();
            int kind = switch (ii.opcode()) {
                case INVOKESTATIC -> MethodHandleInfo.REF_invokeStatic;
                case INVOKEINTERFACE -> MethodHandleInfo.REF_invokeInterface;
                case INVOKEVIRTUAL -> MethodHandleInfo.REF_invokeVirtual;
                default -> ii.name().equalsString("<init>") ? MethodHandleInfo.REF_newInvokeSpecial
                        : MethodHandleInfo.REF_invokeSpecial;
            };
            return new Member(kind, ii.owner().asInternalName(), ii.name().stringValue(), ii.type().stringValue());
        }
        throw new IllegalArgumentException("can't find " + lambdaBody.name() + " in " + lambdaBody.owner());
    }
}
