package io.github.hronosin.miracle.rgct;

/**
 * Runtime side of RGCT. Patched game methods call {@link #fire(int, Object)} with a hook id
 * baked into their bytecode.
 *
 * <p>Lives in the loader's own class loader, which game classes can see through delegation.
 * Not part of the mod API: mods should never call this directly.
 */
public final class HookDispatch {

    record Entry(String modId, String where, Hook hook) {
    }

    private static final Object LOCK = new Object();
    private static volatile Entry[] entries = new Entry[0];

    private HookDispatch() {
    }

    static int register(String modId, String where, Hook hook) {
        synchronized (LOCK) {
            Entry[] old = entries;
            Entry[] grown = java.util.Arrays.copyOf(old, old.length + 1);
            grown[old.length] = new Entry(modId, where, hook);
            entries = grown;
            return old.length;
        }
    }

    /** Called from patched game bytecode. */
    public static void fire(int id, Object self) {
        Entry e = entries[id];
        try {
            e.hook().run(self);
        } catch (RuntimeException ex) {
            // Keep the original exception type (the game may rely on it) but make the crash
            // report say who did it.
            ex.addSuppressed(new Blame(e.modId(), e.where()));
            throw ex;
        }
    }

    /** Attached to exceptions that escape a hook, purely so stack traces name the culprit. */
    public static final class Blame extends RuntimeException {
        Blame(String modId, String where) {
            super("thrown by a hook of mod '" + modId + "' at " + where, null, false, false);
        }
    }
}
