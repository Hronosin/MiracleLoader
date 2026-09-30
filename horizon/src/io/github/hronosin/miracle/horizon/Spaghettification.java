package io.github.hronosin.miracle.horizon;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Spaghettification (boring name: Scale): what gravity this strong does to things. Grows and
 * shrinks living things through the game's own scale attribute (hitbox, reach and step height
 * follow), at once or eased over time. Uniformly: actual spaghetti is left as an exercise.
 *
 * <pre>{@code
 * Spaghettification.stretch(player, 2.0, 40, Ease.OUT_BACK);   // twice the size, over two seconds
 * Spaghettification.restore(player, 20, Ease.IN_OUT_SINE);
 * }</pre>
 */
public final class Spaghettification {

    private static final String ID = "event-horizon:spaghettification";

    private Spaghettification() {
    }

    /** {@code factor} times the normal size, at once (1: normal). */
    public static void stretch(LivingEntity e, double factor) {
        if (Math.abs(factor - 1) < 1e-9) {
            Accretion.stop(e, ID);
            return;
        }
        Accretion.boost(e, Attributes.SCALE, ID).multiplyTotal(factor - 1).start();
    }

    /** From its current size to {@code factor} times normal, over {@code ticks}, paced by {@code ease}. */
    public static Redshift.Task stretch(LivingEntity e, double factor, int ticks, Ease ease) {
        double from = factor(e);
        return Redshift.over(ticks, t -> stretch(e, ease.between(from, factor, t))).bound(e);
    }

    /** Back to normal at once. */
    public static void restore(LivingEntity e) {
        Accretion.stop(e, ID);
    }

    public static Redshift.Task restore(LivingEntity e, int ticks, Ease ease) {
        return stretch(e, 1, ticks, ease);
    }

    /** How stretched it is by this (1: not at all). Other things may scale it too. */
    public static double factor(LivingEntity e) {
        var inst = e.getAttribute(Attributes.SCALE);
        if (inst == null) {
            return 1;
        }
        var id = net.minecraft.resources.Identifier.parse(ID);
        var mod = inst.getModifier(id);
        return mod == null ? 1 : 1 + mod.amount();
    }
}
