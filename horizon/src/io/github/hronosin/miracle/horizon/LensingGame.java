package io.github.hronosin.miracle.horizon;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Lensing's server half. The game's own post effect list on players exists from 26.3 on; it's
 * found by name, so older versions (which don't have it) simply say no.
 */
final class LensingGame {

    private LensingGame() {
    }

    private static final class Methods {
        static final MethodHandle ADD = find("addPostEffect", MethodType.methodType(boolean.class, Identifier.class));
        static final MethodHandle REMOVE = find("removePostEffect", MethodType.methodType(boolean.class, Identifier.class));
        static final MethodHandle CLEAR = find("clearPostEffects", MethodType.methodType(boolean.class));

        private static MethodHandle find(String name, MethodType type) {
            try {
                return MethodHandles.publicLookup().findVirtual(ServerPlayer.class, name, type);
            } catch (ReflectiveOperationException olderGame) {
                return null;
            }
        }
    }

    static boolean supported() {
        try {
            return Methods.ADD != null;
        } catch (LinkageError noGame) {
            return false;
        }
    }

    private static volatile boolean said;

    private static boolean unsupported() {
        if (!supported()) {
            if (!said) {
                said = true;
                EventHorizon.LOG.accept("Lensing: post effects from the server need Minecraft 26.3 or newer;"
                    + " here, show them from the client with Lensing.here(id).");
            }
            return true;
        }
        return false;
    }

    static boolean add(ServerPlayer player, String id) {
        if (unsupported()) {
            return false;
        }
        try {
            return (boolean) Methods.ADD.invoke(player, Identifier.parse(id));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean remove(ServerPlayer player, String id) {
        if (unsupported()) {
            return false;
        }
        try {
            return (boolean) Methods.REMOVE.invoke(player, Identifier.parse(id));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean clear(ServerPlayer player) {
        if (unsupported()) {
            return false;
        }
        try {
            return (boolean) Methods.CLEAR.invoke(player);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean forTicks(ServerPlayer player, String id, int ticks) {
        if (!add(player, id)) {
            return false;
        }
        Redshift.after(ticks, () -> remove(player, id)).bound(player);
        return true;
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
