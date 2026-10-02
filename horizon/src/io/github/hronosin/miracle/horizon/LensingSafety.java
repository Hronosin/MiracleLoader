package io.github.hronosin.miracle.horizon;

import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A mod's post effect that doesn't compile mustn't stop the game. The game logs the error, keeps
 * nothing for that id (so it isn't tried again until resources reload), and then asks for a
 * recovery: a reload without resource packs, which can't take a mod's resources away and ends in
 * a crash. For effects outside {@code minecraft}, that recovery is skipped.
 */
final class LensingSafety {

    private static final ThreadLocal<Object> LOADING = new ThreadLocal<>();
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private LensingSafety() {
    }

    /** At the head of {@code ShaderManager#getPostChain}: which effect is being made. */
    static void loading(Object id) {
        LOADING.set(id);
    }

    /** At its return. */
    static void done() {
        LOADING.remove();
    }

    /** At the head of {@code ShaderManager#tryTriggerRecovery}: true to skip it. */
    static boolean spare() {
        Object id = LOADING.get();
        if (!(id instanceof Identifier i) || i.getNamespace().equals("minecraft")) {
            return false;
        }
        if (SAID.add(i.toString())) {
            EventHorizon.LOG.accept("Lensing: the post effect " + i + " doesn't compile (the error is above). It stays off,"
                    + " and the game goes on; fix it and press F3+T to try again.");
        }
        return true;
    }
}
