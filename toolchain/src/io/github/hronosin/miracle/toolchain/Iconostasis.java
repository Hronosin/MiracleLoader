package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

/**
 * The client-only half of {@link Shrine} looks: joins our block entity renderers to the game's
 * providers before the game builds its renderers. Loaded only on a client, once the game runs.
 */
final class Iconostasis {

    private static boolean enlisted;

    private Iconostasis() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static synchronized void enlist() {
        if (enlisted) {
            return;
        }
        enlisted = true;
        List<Shrine<?>> shrines = Creation.shrines();
        Map<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>> providers;
        try {
            providers = (Map) staticMap().get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.warn("MiracleToolChain: couldn't reach the block entity renderers; shrines will show only their blocks: " + e);
            return;
        }
        int n = 0;
        for (Shrine<?> s : shrines) {
            if (!s.exists() || providers.containsKey(s.get())) {
                continue;
            }
            if (s.renderer != null) {
                providers.put(s.get(), (BlockEntityRendererProvider) ctx -> custom(s, ctx));
                n++;
            } else if (s.enshrined >= 0) {
                int slot = s.enshrined;
                providers.put(s.get(), (BlockEntityRendererProvider) ctx -> Monstrance.of(slot, ctx));
                n++;
            }
        }
        if (n > 0) {
            Log.info("MiracleToolChain: " + n + " block entity renderer(s) enlisted.");
        }
    }

    @SuppressWarnings("rawtypes")
    private static BlockEntityRenderer custom(Shrine<?> s, BlockEntityRendererProvider.Context ctx) {
        try {
            Class<?> cls = Class.forName(s.renderer, true, s.loader);
            Object made = cls.getConstructor(BlockEntityRendererProvider.Context.class).newInstance(ctx);
            return made instanceof Altarpiece<?> piece ? Monstrance.of(piece) : (BlockEntityRenderer) made;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            Throwable why = e instanceof InvocationTargetException ite ? ite.getCause() : e;
            Log.error("MiracleToolChain: " + s + " is rendered by " + s.renderer + ", which couldn't be made (an"
                    + " Altarpiece or BlockEntityRenderer with a public constructor taking BlockEntityRendererProvider.Context?): " + why
                    + ". Showing it as an item display instead of nothing.");
            return Monstrance.of(Math.max(0, s.enshrined), ctx);
        }
    }

    private static Field staticMap() throws NoSuchFieldException {
        for (Field f : BlockEntityRenderers.class.getDeclaredFields()) {
            if (f.getType() == Map.class && Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new NoSuchFieldException("BlockEntityRenderers has no static Map in this version");
    }
}
