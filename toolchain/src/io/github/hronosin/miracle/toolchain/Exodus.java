package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.rgct.Rgct;

/**
 * Natural spawning for {@link Being}s that {@link Being#spawns spawn}: the game asks which mobs
 * may appear at a spot, and ours join the answer; where and when they may appear follows their
 * category. Touches no game class itself: it's loaded at startup.
 */
final class Exodus {

    private Exodus() {
    }

    static void install(Rgct rgct) {
        // Which mobs may spawn here. The signature changed in 26.3 (the biome is no longer passed),
        // so the method is named without a descriptor, and the hook copes with both.
        rgct.target("net.minecraft.world.level.NaturalSpawner")
                .method("mobsAt")
                .interceptReturn(ctx -> {
                    Object more = Roads.mobsAt(ctx.returnValue(), ctx.arg(0), ctx.arg(3), ctx.arg(4),
                            ctx.argCount() > 5 ? ctx.arg(5) : null);
                    if (more != null) {
                        ctx.setReturnValue(more);
                    }
                });
        rgct.target("net.minecraft.world.entity.SpawnPlacements")
                .method("getPlacementType", "(Lnet/minecraft/world/entity/EntityType;)Lnet/minecraft/world/entity/SpawnPlacementType;")
                .interceptHead(ctx -> {
                    Object p = Roads.placement(ctx.arg(0));
                    if (p != null) {
                        ctx.cancel(p);
                    }
                });
        rgct.target("net.minecraft.world.entity.SpawnPlacements")
                .method("getHeightmapType", "(Lnet/minecraft/world/entity/EntityType;)Lnet/minecraft/world/level/levelgen/Heightmap$Types;")
                .interceptHead(ctx -> {
                    Object h = Roads.heightmap(ctx.arg(0));
                    if (h != null) {
                        ctx.cancel(h);
                    }
                });
        rgct.target("net.minecraft.world.entity.SpawnPlacements")
                .method("checkSpawnRules", "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/ServerLevelAccessor;"
                        + "Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)Z")
                .interceptHead(ctx -> {
                    Boolean ok = Roads.rules(ctx.arg(0), ctx.arg(1), ctx.arg(2), ctx.arg(3), ctx.arg(4));
                    if (ok != null) {
                        ctx.cancel(ok);
                    }
                });
    }
}
