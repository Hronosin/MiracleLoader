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
     * For libraries: patches registered through the returned view are in {@code dependent}'s
     * name, so the startup report, conflict checks and crash blame all name the mod that
     * actually asked for them. Only for a mod that lists this one in its {@code depends}.
     */
    public Rgct onBehalfOf(String dependent) {
        registry.checkOpen();
        var mod = io.github.hronosin.miracle.api.Mods.of(dependent);
        boolean depends = mod.depends().stream().anyMatch(modId::equals);
        if (!depends) {
            throw new IllegalArgumentException("'" + modId + "' may only patch on behalf of mods that depend on it, and '"
                    + dependent + "' doesn't (depends = " + mod.depends() + ")");
        }
        return registry.viewFor(dependent);
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

        /**
         * Replaces a call this method makes: every {@code call} inside it calls {@code replacement}
         * instead, with the same arguments (the receiver first, for an instance method), and the
         * method goes on with what the replacement returned. One mod per call: two mods
         * redirecting the same call in the same method is a conflict, reported at startup.
         *
         * <pre>{@code
         * .method("extractSectionDrawGroups")
         * .redirect(ChunkSectionLayer::values, MyMod::layers)
         * }</pre>
         *
         * <p>Costs nothing at run time: the call site is bound once to the replacement, which the
         * JIT inlines like the original. Unlike intercept hooks, nothing is boxed or wrapped. For
         * calls that return nothing, use {@link #redirectVoid}. A {@code new} is redirected too:
         * name it {@code Foo::new}, and the replacement, a factory taking the constructor's
         * arguments, makes the object instead (since 1.5.0). Private and {@code super} calls can't
         * be redirected.
         *
         * <p>Safe inside {@code transform()}: as a mod class loads, RGCT rewrites its redirect
         * lambdas so they're written down by name, and looked up only when the game first makes
         * the call. {@code Player::getName} here loads no {@code Player}. (Anything the lambda
         * captures is still evaluated, so capture nothing from the game.)
         *
         * @param call        the call to replace, as a method reference ({@code Foo::bar}) or a
         *                    lambda making exactly that one call
         * @param replacement what to call instead: a method reference or a lambda of the same shape
         */
        public <R> MethodTarget redirect(Redirect.Call0<R> call, Redirect.Call0<R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, R> MethodTarget redirect(Redirect.Call1<A, R> call, Redirect.Call1<A, R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, R> MethodTarget redirect(Redirect.Call2<A, B, R> call, Redirect.Call2<A, B, R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, R> MethodTarget redirect(Redirect.Call3<A, B, C, R> call, Redirect.Call3<A, B, C, R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, R> MethodTarget redirect(Redirect.Call4<A, B, C, D, R> call, Redirect.Call4<A, B, C, D, R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, R> MethodTarget redirect(Redirect.Call5<A, B, C, D, E, R> call, Redirect.Call5<A, B, C, D, E, R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, F, R> MethodTarget redirect(Redirect.Call6<A, B, C, D, E, F, R> call, Redirect.Call6<A, B, C, D, E, F, R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, F, G, R> MethodTarget redirect(Redirect.Call7<A, B, C, D, E, F, G, R> call, Redirect.Call7<A, B, C, D, E, F, G, R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, F, G, H, R> MethodTarget redirect(Redirect.Call8<A, B, C, D, E, F, G, H, R> call, Redirect.Call8<A, B, C, D, E, F, G, H, R> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, F, G, H, I, R> MethodTarget redirect(Redirect.Call9<A, B, C, D, E, F, G, H, I, R> call, Redirect.Call9<A, B, C, D, E, F, G, H, I, R> replacement) {
            return redirectAny(call, replacement);
        }

        /** {@link #redirect} for a call that returns nothing. */
        public MethodTarget redirectVoid(Redirect.Do0 call, Redirect.Do0 replacement) {
            return redirectAny(call, replacement);
        }

        public <A> MethodTarget redirectVoid(Redirect.Do1<A> call, Redirect.Do1<A> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B> MethodTarget redirectVoid(Redirect.Do2<A, B> call, Redirect.Do2<A, B> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C> MethodTarget redirectVoid(Redirect.Do3<A, B, C> call, Redirect.Do3<A, B, C> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D> MethodTarget redirectVoid(Redirect.Do4<A, B, C, D> call, Redirect.Do4<A, B, C, D> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E> MethodTarget redirectVoid(Redirect.Do5<A, B, C, D, E> call, Redirect.Do5<A, B, C, D, E> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, F> MethodTarget redirectVoid(Redirect.Do6<A, B, C, D, E, F> call, Redirect.Do6<A, B, C, D, E, F> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, F, G> MethodTarget redirectVoid(Redirect.Do7<A, B, C, D, E, F, G> call, Redirect.Do7<A, B, C, D, E, F, G> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, F, G, H> MethodTarget redirectVoid(Redirect.Do8<A, B, C, D, E, F, G, H> call, Redirect.Do8<A, B, C, D, E, F, G, H> replacement) {
            return redirectAny(call, replacement);
        }

        public <A, B, C, D, E, F, G, H, I> MethodTarget redirectVoid(Redirect.Do9<A, B, C, D, E, F, G, H, I> call, Redirect.Do9<A, B, C, D, E, F, G, H, I> replacement) {
            return redirectAny(call, replacement);
        }

        private MethodTarget redirectAny(Object call, Object replacement) {
            registry.addRedirect(owner.className, modId, name, descriptor, call, replacement);
            return this;
        }

        /** Back to the class, to patch another method. */
        public ClassTarget and() {
            return owner;
        }
    }
}
