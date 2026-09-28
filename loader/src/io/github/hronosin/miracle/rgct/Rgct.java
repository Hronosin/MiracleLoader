package io.github.hronosin.miracle.rgct;

import java.lang.classfile.ClassTransform;

/**
 * RGCT — Runtime Game Class Transformer. Each mod gets its own view, so every patch knows
 * which mod it came from.
 *
 * <pre>{@code
 * rgct.target("net.minecraft.world.entity.player.Player")
 *     .method("jumpFromGround")
 *     .atHead(self -> System.out.println("jump!"));
 *
 * rgct.target("net.minecraft.world.entity.LivingEntity")
 *     .method("getJumpPower", "()F")
 *     .interceptReturn(ctx -> ctx.setReturnValue((float) ctx.returnValue() * 1.5f));
 * }</pre>
 */
public final class Rgct {

    private final String modId;
    private final TransformRegistry registry;

    Rgct(String modId, TransformRegistry registry) {
        this.modId = modId;
        this.registry = registry;
    }

    /** Id of the mod this view belongs to. */
    public String modId() {
        return modId;
    }

    /**
     * Starts patching a class. Takes a binary name with dots, e.g.
     * {@code net.minecraft.world.entity.player.Player} or {@code a.b.Outer$Inner}.
     */
    public ClassTarget target(String className) {
        registry.checkOpen();
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("class name is empty");
        }
        return new ClassTarget(className.replace('/', '.'));
    }

    /** Patches for one class. */
    public final class ClassTarget {
        private final String className;

        private ClassTarget(String className) {
            this.className = className;
        }

        /** Every method with this name, whatever its descriptor. */
        public MethodTarget method(String name) {
            return new MethodTarget(this, name, null);
        }

        /** One exact method, e.g. {@code method("hurt", "(F)Z")}. */
        public MethodTarget method(String name, String descriptor) {
            return new MethodTarget(this, name, descriptor);
        }

        /**
         * Escape hatch: a raw ClassFile API transform over the whole class. You are on your own
         * here — RGCT cannot merge or check it, and it says so in the log.
         */
        public ClassTarget raw(ClassTransform transform) {
            registry.addRaw(className, modId, transform);
            return this;
        }
    }

    /** Patches for one method (or every overload of a name). */
    public final class MethodTarget {
        private final ClassTarget owner;
        private final String name;
        private final String descriptor;

        private MethodTarget(ClassTarget owner, String name, String descriptor) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }

        /** Runs the hook before the first instruction of the method. */
        public MethodTarget atHead(Hook hook) {
            registry.addHook(owner.className, modId, name, descriptor, TransformRegistry.Where.HEAD, hook);
            return this;
        }

        /** Runs the hook before every normal return (not on exceptions). */
        public MethodTarget atReturn(Hook hook) {
            registry.addHook(owner.className, modId, name, descriptor, TransformRegistry.Where.RETURN, hook);
            return this;
        }

        /**
         * Runs the hook before the first instruction, with access to the arguments. It can change
         * them ({@link HookContext#setArg}) or skip the method entirely ({@link HookContext#cancel}).
         * Not supported on constructors.
         */
        public MethodTarget interceptHead(ContextHook hook) {
            registry.addHook(owner.className, modId, name, descriptor, TransformRegistry.Where.INTERCEPT_HEAD, hook);
            return this;
        }

        /**
         * Runs the hook before every normal return, with access to the arguments and the return
         * value, which it can replace ({@link HookContext#setReturnValue}).
         */
        public MethodTarget interceptReturn(ContextHook hook) {
            registry.addHook(owner.className, modId, name, descriptor, TransformRegistry.Where.INTERCEPT_RETURN, hook);
            return this;
        }

        /** Back to the class, to patch another method. */
        public ClassTarget and() {
            return owner;
        }
    }
}
