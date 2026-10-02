package io.github.hronosin.miracle.horizon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lensing's client half. From 26.3 the player has a list of post effects (the server's, kept on
 * {@code LocalPlayer}), and ours join it; before, the game renderer has one slot, found by its
 * shape (a private method taking an id), since its name differs between versions.
 */
final class LensingClient {

    private static final Set<String> HERE = new LinkedHashSet<>();
    private static volatile List<Identifier> fromServer = List.of();

    private LensingClient() {
    }

    private static boolean stacked() {
        return Lensing.dialect() == Lensing.Dialect.SEPARATE;
    }

    static void here(String id) {
        Identifier rid = Identifier.parse(id);
        Minecraft.getInstance().execute(() -> {
            synchronized (HERE) {
                if (stacked()) {
                    HERE.add(rid.toString());
                } else {
                    HERE.clear();
                    HERE.add(rid.toString());
                }
            }
            if (stacked()) {
                refresh();
            } else {
                Slot.set(rid);
            }
        });
    }

    static void away(String id) {
        String rid = Identifier.parse(id).toString();
        Minecraft.getInstance().execute(() -> {
            boolean had;
            synchronized (HERE) {
                had = HERE.remove(rid);
            }
            if (!had) {
                return;
            }
            if (stacked()) {
                refresh();
            } else {
                Slot.clear();
            }
        });
    }

    static void clear() {
        Minecraft.getInstance().execute(() -> {
            synchronized (HERE) {
                HERE.clear();
            }
            if (stacked()) {
                refresh();
            } else {
                Slot.clear();
            }
        });
    }

    static List<String> showing() {
        List<String> out = new ArrayList<>();
        if (stacked()) {
            Object player = Minecraft.getInstance().player;
            if (player != null && Stack.GET != null) {
                try {
                    for (Object o : (List<?>) Stack.GET.invoke(player)) {
                        out.add(o.toString());
                    }
                } catch (Throwable ignored) {
                    // nothing to show
                }
            }
        } else {
            Identifier now = Slot.current();
            if (now != null) {
                out.add(now.toString());
            }
        }
        return out;
    }

    /** 26.3+: the server's list, with ours after it; null when we have nothing to add. Hooked in. */
    static Object merge(Object list) {
        if (!(list instanceof List<?> l)) {
            return null;
        }
        List<Identifier> server = new ArrayList<>();
        for (Object o : l) {
            if (o instanceof Identifier id && !isOurs(id)) {
                server.add(id);
            }
        }
        fromServer = List.copyOf(server);
        List<Identifier> merged = new ArrayList<>(server);
        synchronized (HERE) {
            if (HERE.isEmpty()) {
                return server.size() == l.size() ? null : merged;
            }
            for (String h : HERE) {
                Identifier id = Identifier.parse(h);
                if (!merged.contains(id)) {
                    merged.add(id);
                }
            }
        }
        return merged;
    }

    private static boolean isOurs(Identifier id) {
        synchronized (HERE) {
            return HERE.contains(id.toString());
        }
    }

    private static void refresh() {
        Object player = Minecraft.getInstance().player;
        if (player == null || Stack.SET == null) {
            return;
        }
        try {
            Stack.SET.invoke(player, new ArrayList<>(fromServer));
        } catch (Throwable t) {
            EventHorizon.LOG.accept("Lensing: couldn't update the post effects: " + t);
        }
    }

    /** 26.3+: LocalPlayer's list, by name (older versions don't have it). */
    private static final class Stack {
        static final MethodHandle SET = find("setActivePostEffects", MethodType.methodType(void.class, List.class));
        static final MethodHandle GET = find("getActivePostEffects", MethodType.methodType(List.class));

        private static MethodHandle find(String name, MethodType type) {
            try {
                return MethodHandles.publicLookup().findVirtual(LocalPlayer.class, name, type);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }

    /** Up to 26.2: the game renderer's one slot, found by its shape. */
    private static final class Slot {
        private static final Method SET;
        private static final Field FIELD;

        static {
            Method set = null;
            Field field = null;
            for (Method m : GameRenderer.class.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
                        && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Identifier.class) {
                    set = m;
                    break;
                }
            }
            for (Field f : GameRenderer.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == Identifier.class) {
                    field = f;
                    break;
                }
            }
            if (set != null) {
                set.setAccessible(true);
            }
            if (field != null) {
                field.setAccessible(true);
            }
            SET = set;
            FIELD = field;
        }

        static void set(Identifier id) {
            try {
                SET.invoke(Minecraft.getInstance().gameRenderer, id);
            } catch (ReflectiveOperationException | RuntimeException e) {
                EventHorizon.LOG.accept("Lensing: couldn't show " + id + ": " + e);
            }
        }

        static void clear() {
            try {
                FIELD.set(Minecraft.getInstance().gameRenderer, null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                EventHorizon.LOG.accept("Lensing: couldn't clear the post effect: " + e);
            }
        }

        static Identifier current() {
            try {
                return FIELD == null ? null : (Identifier) FIELD.get(Minecraft.getInstance().gameRenderer);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }
    }
}
