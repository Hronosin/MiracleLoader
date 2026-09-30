package io.github.hronosin.miracle.rgct;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

/**
 * Runtime side of RGCT. Patched game methods call into this class.
 *
 * <p><b>Direct calls.</b> Each patched spot is an {@code invokedynamic} whose call site this class
 * binds, the first time it runs, straight to the hook object: an observing hook's {@code run}, or
 * for an intercepted method with a single hook, that hook with no loop and no lookups around it.
 * The call site never changes, so the JIT sees the hook as a constant and can inline it into the
 * game method like hand-written code. Methods with several hooks get their site bound instead,
 * and loop over it. {@code -Dmiracle.directCalls=false} patches the old way (a static call with
 * an id, looked up on every call), for comparison or suspicion.
 *
 * <p>Lives in the loader's own class loader, which game classes can see through delegation.
 * Not part of the mod API: mods should never call this directly.
 */
@io.github.hronosin.miracle.api.Internal
public final class HookDispatch {

    /** Returned by {@link #interceptHead} when the method should run normally. */
    public static final Object PROCEED = new Object() {
        @Override
        public String toString() {
            return "PROCEED";
        }
    };

    record Entry(String modId, String where, Object hook, int priority) {
    }

    /** One intercepted method position: every context hook that runs there. */
    record Site(int[] hookIds, String argKinds, char returnKind, boolean head, String methodLabel) {
    }

    private static final Object LOCK = new Object();
    private static volatile Entry[] entries = new Entry[0];
    private static volatile Site[] sites = new Site[0];

    private HookDispatch() {
    }

    static int register(String modId, String where, Object hook, int priority) {
        synchronized (LOCK) {
            Entry[] old = entries;
            Entry[] grown = Arrays.copyOf(old, old.length + 1);
            grown[old.length] = new Entry(modId, where, hook, priority);
            entries = grown;
            return old.length;
        }
    }

    static int registerSite(Site site) {
        synchronized (LOCK) {
            Site[] old = sites;
            Site[] grown = Arrays.copyOf(old, old.length + 1);
            grown[old.length] = site;
            sites = grown;
            return old.length;
        }
    }

    // --- direct calls ----------------------------------------------------------------------------

    private static final MethodHandle FIRE_ONE;
    private static final MethodHandle HEAD_ONE;
    private static final MethodHandle HEAD_MANY;
    private static final MethodHandle RETURN_ONE;
    private static final MethodHandle RETURN_MANY;

    static {
        MethodHandles.Lookup l = MethodHandles.lookup();
        try {
            FIRE_ONE = l.findStatic(HookDispatch.class, "fireOne",
                    MethodType.methodType(void.class, Hook.class, Entry.class, Object.class));
            HEAD_ONE = l.findStatic(HookDispatch.class, "headOne", MethodType.methodType(Object.class,
                    ContextHook.class, Entry.class, int.class, Site.class, Object.class, Object[].class));
            HEAD_MANY = l.findStatic(HookDispatch.class, "headMany",
                    MethodType.methodType(Object.class, Site.class, Object.class, Object[].class));
            RETURN_ONE = l.findStatic(HookDispatch.class, "returnOne", MethodType.methodType(Object.class,
                    ContextHook.class, Entry.class, int.class, Site.class, Object.class, Object[].class, Object.class));
            RETURN_MANY = l.findStatic(HookDispatch.class, "returnMany",
                    MethodType.methodType(Object.class, Site.class, Object.class, Object[].class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Bootstrap for the {@code invokedynamic} RGCT writes into patched methods. {@code name} says
     * what's there: {@code fire} ({@code id} is a hook), {@code interceptHead} or
     * {@code interceptReturn} ({@code id} is a site). Called by the JVM once per spot.
     */
    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, int id) {
        MethodHandle target = switch (name) {
            case "fire" -> {
                Entry e = entries[id];
                yield MethodHandles.insertArguments(FIRE_ONE, 0, (Hook) e.hook(), e);
            }
            case "interceptHead" -> {
                Site site = sites[id];
                if (site.hookIds().length == 1) {
                    int hookId = site.hookIds()[0];
                    Entry e = entries[hookId];
                    yield MethodHandles.insertArguments(HEAD_ONE, 0, (ContextHook) e.hook(), e, hookId, site);
                }
                yield MethodHandles.insertArguments(HEAD_MANY, 0, site);
            }
            case "interceptReturn" -> {
                Site site = sites[id];
                if (site.hookIds().length == 1) {
                    int hookId = site.hookIds()[0];
                    Entry e = entries[hookId];
                    yield MethodHandles.insertArguments(RETURN_ONE, 0, (ContextHook) e.hook(), e, hookId, site);
                }
                yield MethodHandles.insertArguments(RETURN_MANY, 0, site);
            }
            default -> throw new IllegalArgumentException("RGCT: no such call site kind: " + name);
        };
        return new ConstantCallSite(target.asType(type));
    }

    private static void fireOne(Hook hook, Entry e, Object self) {
        try {
            hook.run(self);
        } catch (RuntimeException ex) {
            throw blame(ex, e);
        }
    }

    private static Object headOne(ContextHook hook, Entry e, int id, Site site, Object self, Object[] args) {
        HookContext ctx = new HookContext(self, args, site.argKinds(), site.returnKind(), true, null, site.methodLabel());
        ctx.enter(id, e.modId(), e.priority());
        try {
            hook.run(ctx);
        } catch (RuntimeException ex) {
            throw blame(ex, e);
        }
        return Layers.head(ctx, args);
    }

    private static Object headMany(Site site, Object self, Object[] args) {
        HookContext ctx = new HookContext(self, args, site.argKinds(), site.returnKind(), true, null, site.methodLabel());
        for (int id : site.hookIds()) {
            run(id, ctx);
        }
        return Layers.head(ctx, args);
    }

    private static Object returnOne(ContextHook hook, Entry e, int id, Site site, Object self, Object[] args,
                                    Object returnValue) {
        HookContext ctx = new HookContext(self, args, site.argKinds(), site.returnKind(), false, returnValue,
                site.methodLabel());
        ctx.enter(id, e.modId(), e.priority());
        try {
            hook.run(ctx);
        } catch (RuntimeException ex) {
            throw blame(ex, e);
        }
        return Layers.ret(ctx, returnValue);
    }

    private static Object returnMany(Site site, Object self, Object[] args, Object returnValue) {
        HookContext ctx = new HookContext(self, args, site.argKinds(), site.returnKind(), false, returnValue,
                site.methodLabel());
        for (int id : site.hookIds()) {
            run(id, ctx);
        }
        return Layers.ret(ctx, returnValue);
    }

    // --- the old way: a static call with an id (-Dmiracle.directCalls=false) ---------------------

    /** Called from patched bytecode: plain observing hook. */
    public static void fire(int id, Object self) {
        Entry e = entries[id];
        try {
            ((Hook) e.hook()).run(self);
        } catch (RuntimeException ex) {
            throw blame(ex, e);
        }
    }

    /**
     * Called from patched bytecode at a method head. Every hook sees the original arguments; the
     * merged arguments are then written back into {@code args}. Returns {@link #PROCEED}, or the
     * value to return right away if the method was cancelled.
     */
    public static Object interceptHead(int siteId, Object self, Object[] args) {
        return headMany(sites[siteId], self, args);
    }

    /** Called from patched bytecode before a return. Returns the value the method finally returns. */
    public static Object interceptReturn(int siteId, Object self, Object[] args, Object returnValue) {
        return returnMany(sites[siteId], self, args, returnValue);
    }

    private static void run(int id, HookContext ctx) {
        Entry e = entries[id];
        ctx.enter(id, e.modId(), e.priority());
        try {
            ((ContextHook) e.hook()).run(ctx);
        } catch (RuntimeException ex) {
            throw blame(ex, e);
        }
    }

    private static RuntimeException blame(RuntimeException ex, Entry e) {
        // Keep the original exception type (the game may rely on it) but make the crash report
        // say who did it.
        ex.addSuppressed(new Blame(e.modId(), e.where()));
        return ex;
    }

    /** Attached to exceptions that escape a hook, purely so stack traces name the culprit. */
    public static final class Blame extends RuntimeException {
        Blame(String modId, String where) {
            super("thrown by a hook of mod '" + modId + "' at " + where, null, false, false);
        }
    }
}
