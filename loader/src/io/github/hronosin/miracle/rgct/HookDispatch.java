package io.github.hronosin.miracle.rgct;

import java.util.Arrays;

/**
 * Runtime side of RGCT. Patched game methods call into this class with ids baked into their
 * bytecode.
 *
 * <p>Lives in the loader's own class loader, which game classes can see through delegation.
 * Not part of the mod API: mods should never call this directly.
 */
public final class HookDispatch {

    record Entry(String modId, String where, Object hook) {
    }

    /** One intercepted method position: every context hook that runs there, in order. */
    record Site(int[] hookIds, String argKinds, char returnKind, boolean head, String methodLabel) {
    }

    private static final Object LOCK = new Object();
    private static volatile Entry[] entries = new Entry[0];
    private static volatile Site[] sites = new Site[0];

    private HookDispatch() {
    }

    static int register(String modId, String where, Object hook) {
        synchronized (LOCK) {
            Entry[] old = entries;
            Entry[] grown = Arrays.copyOf(old, old.length + 1);
            grown[old.length] = new Entry(modId, where, hook);
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

    /** Called from patched bytecode: plain observing hook. */
    public static void fire(int id, Object self) {
        Entry e = entries[id];
        try {
            ((Hook) e.hook()).run(self);
        } catch (RuntimeException ex) {
            throw blame(ex, e);
        }
    }

    /** Called from patched bytecode at a method head. {@code args} is written back afterwards. */
    public static HookContext interceptHead(int siteId, Object self, Object[] args) {
        Site site = sites[siteId];
        HookContext ctx = new HookContext(self, args, site.argKinds(), site.returnKind(), true, null, site.methodLabel());
        for (int id : site.hookIds()) {
            run(id, ctx);
            if (ctx.isCancelled()) {
                break; // the method won't run, so neither do hooks meant for its head
            }
        }
        return ctx;
    }

    /** Called from patched bytecode before a return. Returns the (possibly replaced) value. */
    public static Object interceptReturn(int siteId, Object self, Object[] args, Object returnValue) {
        Site site = sites[siteId];
        HookContext ctx = new HookContext(self, args, site.argKinds(), site.returnKind(), false, returnValue, site.methodLabel());
        for (int id : site.hookIds()) {
            run(id, ctx);
        }
        return ctx.currentReturnValue();
    }

    private static void run(int id, HookContext ctx) {
        Entry e = entries[id];
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
