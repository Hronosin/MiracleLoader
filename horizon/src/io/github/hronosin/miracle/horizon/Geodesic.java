package io.github.hronosin.miracle.horizon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Geodesic (boring name: Vectors): between {@link Vec} and the game's own types, and frames of
 * reference: "two blocks in front of the player, one to the left" without trigonometry.
 *
 * <pre>{@code
 * Geodesic.Frame f = Geodesic.frame(player);
 * Vec spot = f.toWorld(-1, 0, 2);            // right, up, forward: 1 left, 2 ahead of the eyes
 * Vec3 forTheGame = Geodesic.vec3(spot);
 * }</pre>
 */
public final class Geodesic {

    private Geodesic() {
    }

    public static Vec vec(Vec3 v) {
        return new Vec(v.x, v.y, v.z);
    }

    public static Vec3 vec3(Vec v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    /** The center of a block. */
    public static Vec center(BlockPos pos) {
        return new Vec(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** The block a point is in. */
    public static BlockPos block(Vec v) {
        return BlockPos.containing(v.x(), v.y(), v.z());
    }

    /** {x, y, z} from {@link Shape#blocks()} to a block position. */
    public static BlockPos block(int[] xyz) {
        return new BlockPos(xyz[0], xyz[1], xyz[2]);
    }

    /** Where an entity stands (its feet). */
    public static Vec position(Entity e) {
        return vec(e.position());
    }

    /** Where it looks from. */
    public static Vec eyes(Entity e) {
        return vec(e.getEyePosition());
    }

    /** Where it looks (length 1). */
    public static Vec look(Entity e) {
        return Vec.look(e.getYRot(), e.getXRot());
    }

    /** The middle of its body. */
    public static Vec middle(Entity e) {
        return position(e).add(0, e.getBbHeight() / 2, 0);
    }

    /** The frame of an entity's eyes: forward where it looks, up toward the top of its head. */
    public static Frame frame(Entity e) {
        return Frame.of(eyes(e), e.getYRot(), e.getXRot());
    }

    /** The frame of an entity's body: forward the way it faces, but level, with up straight up. */
    public static Frame bodyFrame(Entity e) {
        return Frame.of(position(e), e.getYRot(), 0);
    }

    /**
     * A frame of reference: an origin and three directions at right angles. Local coordinates
     * are (right, up, forward).
     */
    public record Frame(Vec origin, Vec right, Vec up, Vec forward) {

        public static Frame of(Vec origin, double yawDegrees, double pitchDegrees) {
            Quat q = Quat.yawPitch(yawDegrees, pitchDegrees);
            // the game's "right" of a south-facing look is west (-x)
            return new Frame(origin, q.rotate(Vec.WEST), q.rotate(Vec.UP), q.rotate(Vec.SOUTH));
        }

        public Vec toWorld(double right, double up, double forward) {
            return origin.add(this.right.mul(right)).add(this.up.mul(up)).add(this.forward.mul(forward));
        }

        public Vec toWorld(Vec local) {
            return toWorld(local.x(), local.y(), local.z());
        }

        /** A world point in this frame's (right, up, forward). */
        public Vec toLocal(Vec world) {
            Vec d = world.sub(origin);
            return new Vec(d.dot(right), d.dot(up), d.dot(forward));
        }

        /** A direction given in this frame, in the world. */
        public Vec direction(double right, double up, double forward) {
            return this.right.mul(right).add(this.up.mul(up)).add(this.forward.mul(forward));
        }
    }
}
