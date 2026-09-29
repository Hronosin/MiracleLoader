package io.github.hronosin.miracle.rgct;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads a hook lambda's bytecode at startup to learn which effects it can produce, so RGCT can
 * warn about possible conflicts before the game even starts. A hint, not a proof: a hook that
 * "may set" a value only does so when its own conditions say so.
 */
final class EffectScan {

    enum Kind {
        SETS_RETURN("sets return"),
        MODIFIES_RETURN("modifies return"),
        SETS_ARGS("sets args"),
        MODIFIES_ARGS("modifies args"),
        CANCELS("cancels"),
        CANCELS_WITH_VALUE("cancels with a value");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private static final String CTX = HookContext.class.getName().replace('.', '/');
    private static final int MAX_DEPTH = 4;

    private EffectScan() {
    }

    /** The effects this hook may produce, or null if it isn't a lambda we can read. */
    static Set<Kind> scan(Object hook) {
        try {
            Method writeReplace = hook.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            if (!(writeReplace.invoke(hook) instanceof SerializedLambda lambda)) {
                return null;
            }
            Set<Kind> found = EnumSet.noneOf(Kind.class);
            ClassLoader loader = hook.getClass().getClassLoader();
            boolean ok = walk(loader, lambda.getImplClass(), lambda.getImplMethodName(),
                    lambda.getImplMethodSignature(), found, new HashSet<>(), 0);
            return ok ? found : null;
        } catch (ReflectiveOperationException | RuntimeException | IOException e) {
            return null;
        }
    }

    /** Collects HookContext calls in one method, following calls into the same class. */
    private static boolean walk(ClassLoader loader, String owner, String name, String desc,
                                Set<Kind> found, Set<String> visited, int depth) throws IOException {
        if (depth > MAX_DEPTH || !visited.add(owner + "." + name + desc)) {
            return true;
        }
        byte[] bytes;
        try (InputStream in = loader == null ? null : loader.getResourceAsStream(owner + ".class")) {
            if (in == null) {
                return depth > 0; // the lambda's own class must be readable, helpers may not be
            }
            bytes = in.readAllBytes();
        }
        ClassModel cm = ClassFile.of().parse(bytes);
        for (MethodModel mm : cm.methods()) {
            if (!mm.methodName().equalsString(name) || !mm.methodType().equalsString(desc)) {
                continue;
            }
            CodeModel code = mm.code().orElse(null);
            if (code == null) {
                return depth > 0;
            }
            for (CodeElement e : code) {
                if (e instanceof InvokeInstruction inv) {
                    String target = inv.owner().asInternalName();
                    if (target.equals(CTX)) {
                        classify(inv.name().stringValue(), inv.type().stringValue(), found);
                    } else if (target.equals(owner)) {
                        walk(loader, owner, inv.name().stringValue(), inv.type().stringValue(), found, visited, depth + 1);
                    }
                }
            }
            return true;
        }
        return depth > 0;
    }

    private static void classify(String method, String desc, Set<Kind> found) {
        switch (method) {
            case "setReturnValue" -> found.add(Kind.SETS_RETURN);
            case "addToReturnValue", "multiplyReturnValue", "clampReturnValue" -> found.add(Kind.MODIFIES_RETURN);
            case "setArg" -> found.add(Kind.SETS_ARGS);
            case "addToArg", "multiplyArg", "clampArg" -> found.add(Kind.MODIFIES_ARGS);
            case "cancel" -> found.add(desc.equals("()V") ? Kind.CANCELS : Kind.CANCELS_WITH_VALUE);
            default -> {
            }
        }
    }
}
