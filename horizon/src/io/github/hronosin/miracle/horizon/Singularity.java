package io.github.hronosin.miracle.horizon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Singularity (boring name: Raycast): what a ray hits, and what's inside a {@link Shape}. Works
 * on both sides; on the server it's the truth, on the client it's what the player sees.
 *
 * <pre>{@code
 * Singularity.Hit hit = Singularity.look(player, 32);
 * if (hit.entity() != null) { ... }                       // looking at a mob, within 32 blocks
 * for (LivingEntity e : Singularity.entities(level, Shape.sphere(center, 5), LivingEntity.class)) { ... }
 * }</pre>
 */
public final class Singularity {

    private Singularity() {
    }

    /** What a ray hit: a block, an entity, or nothing ({@code where} is then the ray's end). */
    public record Hit(Vec where, BlockPos block, Entity entity) {

        public boolean missed() {
            return block == null && entity == null;
        }

        public double distanceFrom(Vec from) {
            return where.distance(from);
        }
    }

    /** What an entity is looking at, blocks and entities both, within {@code range} blocks. */
    public static Hit look(Entity looker, double range) {
        Vec from = Geodesic.eyes(looker);
        return ray(looker.level(), from, from.add(Geodesic.look(looker).mul(range)), looker, e -> e.isPickable() && !e.isSpectator());
    }

    /**
     * The first thing between {@code from} and {@code to}: a solid block (by its collision shape),
     * or an entity matching {@code entities} (null: blocks only), whichever comes first.
     * {@code ignore} (may be null) is never hit, usually whoever is looking or shooting.
     */
    public static Hit ray(Level level, Vec from, Vec to, Entity ignore, Predicate<Entity> entities) {
        Vec3 a = Geodesic.vec3(from);
        Vec3 b = Geodesic.vec3(to);
        BlockHitResult block = level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ignore));
        Vec3 end = block.getType() == HitResult.Type.MISS ? b : block.getLocation();
        if (entities != null) {
            AABB box = new AABB(Math.min(a.x, end.x), Math.min(a.y, end.y), Math.min(a.z, end.z),
                    Math.max(a.x, end.x), Math.max(a.y, end.y), Math.max(a.z, end.z)).inflate(1);
            EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, ignore, a, end, box,
                    e -> e != ignore && entities.test(e), 0.3f);
            if (hit != null) {
                return new Hit(Geodesic.vec(hit.getLocation()), null, hit.getEntity());
            }
        }
        if (block.getType() == HitResult.Type.MISS) {
            return new Hit(to, null, null);
        }
        return new Hit(Geodesic.vec(block.getLocation()), block.getBlockPos(), null);
    }

    /** Whether nothing solid stands between two points. */
    public static boolean clear(Level level, Vec from, Vec to) {
        return ray(level, from, to, null, null).missed();
    }

    /** Every entity of a class inside a shape (by the middle of its body), nearest the shape's center first. */
    public static <T extends Entity> List<T> entities(Level level, Shape shape, Class<T> type) {
        return entities(level, shape, type, e -> true);
    }

    public static <T extends Entity> List<T> entities(Level level, Shape shape, Class<T> type, Predicate<? super T> filter) {
        Vec[] b = shape.bounds();
        AABB box = new AABB(b[0].x(), b[0].y(), b[0].z(), b[1].x(), b[1].y(), b[1].z());
        Vec center = b[0].lerp(b[1], 0.5);
        List<T> out = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box, e -> type.isInstance(e) && e.isAlive())) {
            T t = type.cast(e);
            if (shape.contains(Geodesic.middle(e)) && filter.test(t)) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparingDouble(e -> Geodesic.middle(e).distance(center)));
        return out;
    }

    /** Every block position inside a shape (by its center). */
    public static List<BlockPos> blocks(Shape shape) {
        List<BlockPos> out = new ArrayList<>();
        for (int[] b : shape.blocks()) {
            out.add(Geodesic.block(b));
        }
        return out;
    }
}
