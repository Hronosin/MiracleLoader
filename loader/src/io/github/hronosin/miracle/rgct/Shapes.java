package io.github.hronosin.miracle.rgct;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps {@link Redirect} shapes from loading game classes in {@code transform()}.
 *
 * <p>{@code redirect(Player::getName, ...)} is a lambda, and linking a lambda resolves the classes
 * it names: {@code Player} would be loaded before RGCT got to patch it. So, as a mod class is
 * loaded, every lambda of a Redirect shape in it is relinked to {@link #bootstrap}, which builds a
 * {@link Named}: the same method, written down by name, looked up only when someone calls it. The
 * mod writes plain method references and never knows.
 */
@io.github.hronosin.miracle.api.Internal
public final class Shapes {

    private static final String SHAPE = "io/github/hronosin/miracle/rgct/Redirect$";
    private static final byte[] MARK = SHAPE.getBytes(StandardCharsets.UTF_8);
    private static final ClassDesc LMF = ClassDesc.of("java.lang.invoke.LambdaMetafactory");
    private static final DirectMethodHandleDesc BOOTSTRAP = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, ClassDesc.of(Shapes.class.getName()), "bootstrap",
            MethodTypeDesc.of(ConstantDescs.CD_CallSite, ConstantDescs.CD_MethodHandles_Lookup,
                    ConstantDescs.CD_String, ConstantDescs.CD_MethodType,
                    ConstantDescs.CD_int, ConstantDescs.CD_String, ConstantDescs.CD_String, ConstantDescs.CD_String));

    private Shapes() {
    }

    // --- the class side --------------------------------------------------------------------------

    /** {@code bytes}, with every Redirect-shaped lambda relinked to {@link #bootstrap}; or {@code bytes} itself. */
    static byte[] unlink(byte[] bytes, ClassLoader resolverLoader) {
        if (!mentionsShapes(bytes)) {
            return bytes;
        }
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(resolverLoader)
                        .orElse(ClassHierarchyResolver.defaultResolver())));
        ClassModel model = cf.parse(bytes);
        boolean[] any = {false};
        CodeTransform relink = (b, e) -> {
            if (e instanceof InvokeDynamicInstruction indy && isShape(indy)) {
                DirectMethodHandleDesc impl = (DirectMethodHandleDesc) indy.bootstrapArgs().get(1);
                String owner = impl.owner().descriptorString();
                b.invokedynamic(DynamicCallSiteDesc.of(BOOTSTRAP, indy.name().stringValue(), indy.typeSymbol(),
                        impl.refKind(), owner.substring(1, owner.length() - 1), impl.methodName(),
                        impl.lookupDescriptor()));
                any[0] = true;
            } else {
                b.with(e);
            }
        };
        byte[] out = cf.transformClass(model, ClassTransform.transformingMethodBodies(relink));
        return any[0] ? out : bytes;
    }

    private static boolean isShape(InvokeDynamicInstruction indy) {
        if (!indy.bootstrapMethod().owner().equals(LMF)) {
            return false;
        }
        String made = indy.typeSymbol().returnType().descriptorString();
        List<ConstantDesc> args = indy.bootstrapArgs();
        return made.startsWith("L" + SHAPE) && args.size() >= 3 && args.get(1) instanceof DirectMethodHandleDesc;
    }

    private static boolean mentionsShapes(byte[] bytes) {
        outer:
        for (int i = 0, end = bytes.length - MARK.length; i <= end; i++) {
            for (int j = 0; j < MARK.length; j++) {
                if (bytes[i + j] != MARK[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // --- the running side ------------------------------------------------------------------------

    /**
     * Where a relinked Redirect lambda now comes from. Only strings and an int: linking it resolves
     * nothing but the shape's own interface (and the types of anything the lambda captures).
     */
    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type,
                                     int kind, String owner, String method, String desc) {
        boolean isDo = type.returnType().getSimpleName().startsWith("Do");
        ClassLoader loader = caller.lookupClass().getClassLoader();
        MethodHandle make = MethodHandles.insertArguments(MAKE, 0, isDo, kind, owner, method, desc, loader)
                .asCollector(Object[].class, type.parameterCount());
        return new ConstantCallSite(make.asType(type));
    }

    private static final MethodHandle MAKE;

    static {
        try {
            MAKE = MethodHandles.lookup().findStatic(Shapes.class, "make", MethodType.methodType(Named.class,
                    boolean.class, int.class, String.class, String.class, String.class, ClassLoader.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Named make(boolean isDo, int kind, String owner, String method, String desc,
                              ClassLoader loader, Object[] captured) {
        return isDo ? new NamedDo(kind, owner, method, desc, loader, captured)
                : new NamedCall(kind, owner, method, desc, loader, captured);
    }

    /** A Redirect lambda, written down by name. Its method is looked up the first time it's needed. */
    abstract static sealed class Named permits NamedCall, NamedDo {
        final int kind;
        final String owner;
        final String method;
        final String desc;
        final ClassLoader loader;
        final Object[] captured;
        private volatile MethodHandle handle;

        Named(int kind, String owner, String method, String desc, ClassLoader loader, Object[] captured) {
            this.kind = kind;
            this.owner = owner;
            this.method = method;
            this.desc = desc;
            this.loader = loader;
            this.captured = captured;
        }

        CallScan.Member member() {
            return new CallScan.Member(kind, owner, method, desc);
        }

        /** The method itself, with whatever the lambda captured already bound. */
        MethodHandle handle() {
            MethodHandle h = handle;
            if (h == null) {
                handle = h = resolve();
            }
            return h;
        }

        private MethodHandle resolve() {
            try {
                Class<?> c = Class.forName(owner.replace('/', '.'), false, loader);
                MethodType type = MethodType.fromMethodDescriptorString(desc, loader);
                MethodHandle h;
                try {
                    h = find(MethodHandles.privateLookupIn(c, MethodHandles.lookup()), c, type);
                } catch (IllegalAccessException closed) {
                    h = find(MethodHandles.publicLookup(), c, type);
                }
                return captured.length == 0 ? h : MethodHandles.insertArguments(h, 0, captured);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RGCT: can't find " + member().label(), e);
            }
        }

        private MethodHandle find(MethodHandles.Lookup lookup, Class<?> c, MethodType type)
                throws ReflectiveOperationException {
            return switch (kind) {
                case MethodHandleInfo.REF_invokeStatic -> lookup.findStatic(c, method, type);
                case MethodHandleInfo.REF_invokeSpecial -> lookup.findSpecial(c, method, type, c);
                case MethodHandleInfo.REF_newInvokeSpecial -> lookup.findConstructor(c, type);
                default -> lookup.findVirtual(c, method, type);
            };
        }

        final Object invoke(Object... args) {
            try {
                return handle().invokeWithArguments(args);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        @Override
        public String toString() {
            return member().label() + (captured.length == 0 ? "" : " with " + Arrays.toString(captured));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class NamedCall extends Named implements Redirect.Call0, Redirect.Call1, Redirect.Call2,
            Redirect.Call3, Redirect.Call4, Redirect.Call5, Redirect.Call6, Redirect.Call7, Redirect.Call8, Redirect.Call9 {

        NamedCall(int kind, String owner, String method, String desc, ClassLoader loader, Object[] captured) {
            super(kind, owner, method, desc, loader, captured);
        }

        public Object call() {
            return invoke();
        }

        public Object call(Object a) {
            return invoke(a);
        }

        public Object call(Object a, Object b) {
            return invoke(a, b);
        }

        public Object call(Object a, Object b, Object c) {
            return invoke(a, b, c);
        }

        public Object call(Object a, Object b, Object c, Object d) {
            return invoke(a, b, c, d);
        }

        public Object call(Object a, Object b, Object c, Object d, Object e) {
            return invoke(a, b, c, d, e);
        }

        public Object call(Object a, Object b, Object c, Object d, Object e, Object f) {
            return invoke(a, b, c, d, e, f);
        }

        public Object call(Object a, Object b, Object c, Object d, Object e, Object f, Object g) {
            return invoke(a, b, c, d, e, f, g);
        }

        public Object call(Object a, Object b, Object c, Object d, Object e, Object f, Object g, Object h) {
            return invoke(a, b, c, d, e, f, g, h);
        }

        public Object call(Object a, Object b, Object c, Object d, Object e, Object f, Object g, Object h, Object i) {
            return invoke(a, b, c, d, e, f, g, h, i);
        }
    }

    @SuppressWarnings("rawtypes")
    static final class NamedDo extends Named implements Redirect.Do0, Redirect.Do1, Redirect.Do2,
            Redirect.Do3, Redirect.Do4, Redirect.Do5, Redirect.Do6, Redirect.Do7, Redirect.Do8, Redirect.Do9 {

        NamedDo(int kind, String owner, String method, String desc, ClassLoader loader, Object[] captured) {
            super(kind, owner, method, desc, loader, captured);
        }

        public void call() {
            invoke();
        }

        public void call(Object a) {
            invoke(a);
        }

        public void call(Object a, Object b) {
            invoke(a, b);
        }

        public void call(Object a, Object b, Object c) {
            invoke(a, b, c);
        }

        public void call(Object a, Object b, Object c, Object d) {
            invoke(a, b, c, d);
        }

        public void call(Object a, Object b, Object c, Object d, Object e) {
            invoke(a, b, c, d, e);
        }

        public void call(Object a, Object b, Object c, Object d, Object e, Object f) {
            invoke(a, b, c, d, e, f);
        }

        public void call(Object a, Object b, Object c, Object d, Object e, Object f, Object g) {
            invoke(a, b, c, d, e, f, g);
        }

        public void call(Object a, Object b, Object c, Object d, Object e, Object f, Object g, Object h) {
            invoke(a, b, c, d, e, f, g, h);
        }

        public void call(Object a, Object b, Object c, Object d, Object e, Object f, Object g, Object h, Object i) {
            invoke(a, b, c, d, e, f, g, h, i);
        }
    }
}
