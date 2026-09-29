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
 *     .interceptReturn(ctx -> ctx.multiplyReturnValue(1.5f));
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

        /**
         * OSHI, Old School Hook Integration: the class as plain bytes in, bytes out. Bring your
         * own tools (ASM, a Mixin bridge...), shaded into your mod; the loader itself stays
         * dependency-free. Runs after every other patch on the class. Like {@link #raw}, it's
         * outside the layer system: RGCT can't merge it with other mods, and says so in the log.
         *
         * <p>Your tool must understand Java 25 class files (ASM 9.8 or newer).
         */
        public ClassTarget rawBytes(java.util.function.UnaryOperator<byte[]> transform) {
            registry.addRawBytes(className, modId, transform);
            return this;
        }
    }

    /** Patches for one method (or every overload of a name). */
    public final class MethodTarget {
        private final ClassTarget owner;
        private final String name;
        private final String descriptor;
        private int priority;

        private MethodTarget(ClassTarget owner, String name, String descriptor) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }

        /**
         * Priority for the intercept hooks registered after this call (default 0). Only matters
         * when mods {@code set} the same value or cancel with different values: the highest
         * priority wins. Stacking effects (addTo, multiply, clamp) always all apply.
         */
        public MethodTarget priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** Runs the hook before the first instruction of the method. Observes only. */
        public MethodTarget atHead(Hook hook) {
            registry.addHook(owner.className, modId, name, descriptor, TransformRegistry.Where.HEAD, hook, 0);
            return this;
        }

        /** Runs the hook before every normal return (not on exceptions). Observes only. */
        public MethodTarget atReturn(Hook hook) {
            registry.addHook(owner.className, modId, name, descriptor, TransformRegistry.Where.RETURN, hook, 0);
            return this;
        }

        /**
         * Runs the hook before the first instruction. It sees the original arguments and can ask
         * to change them or to skip the method; see {@link HookContext} for how requests from
         * several mods are merged. Not supported on constructors.
         */
        public MethodTarget interceptHead(ContextHook hook) {
            registry.addHook(owner.className, modId, name, descriptor, TransformRegistry.Where.INTERCEPT_HEAD, hook, priority);
            return this;
        }

        /**
         * Runs the hook before every normal return. It sees the arguments and the value vanilla
         * returns, and can ask to change that value; see {@link HookContext}.
         */
        public MethodTarget interceptReturn(ContextHook hook) {
            registry.addHook(owner.className, modId, name, descriptor, TransformRegistry.Where.INTERCEPT_RETURN, hook, priority);
            return this;
        }

        /** Back to the class, to patch another method. */
        public ClassTarget and() {
            return owner;
        }
    }
}
