package io.github.hronosin.miracle.toolchain;

import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * One value from {@link Blessings}, narrowed down to who it applies to, then changed. Narrowing
 * returns a new blessing; the terminal calls ({@link #multiply}, {@link #add}, {@link #clamp},
 * {@link #set}) put the change into effect, from then on.
 *
 * <pre>{@code
 * Blessings.jumpPower()
 *     .forPlayers()
 *     .when(p -> p.isSprinting())
 *     .add(0.1);
 * }</pre>
 *
 * @param <E> what the value belongs to
 */
public final class Blessing<E> {

    /** One change, as a hook applies it: to whom, and by how much. */
    record Rite(Predicate<Object> who, ToDoubleFunction<Object> amount, double min, double max) {

        boolean applies(Object self) {
            return who.test(self);
        }

        double amount(Object self) {
            return amount.applyAsDouble(self);
        }
    }

    private final Blessings.Value value;
    private final Class<E> type;
    /** Applied to the raw {@code self}: true if it's of our type and every condition holds. */
    private final Predicate<Object> who;
    private final int priority;

    Blessing(Blessings.Value value, Class<E> type) {
        this(value, type, type::isInstance, 0);
    }

    private Blessing(Blessings.Value value, Class<E> type, Predicate<Object> who, int priority) {
        this.value = value;
        this.type = type;
        this.who = who;
        this.priority = priority;
    }

    // --- narrowing ---------------------------------------------------------------------------

    /** Only for players. */
    public Blessing<Player> forPlayers() {
        return forType(Player.class);
    }

    /** Only for things of this type (a subtype of what the value belongs to). */
    public <T> Blessing<T> forType(Class<T> t) {
        if (!type.isAssignableFrom(t)) {
            throw new IllegalArgumentException(value.label + " belongs to " + type.getSimpleName() + ", and "
                    + t.getSimpleName() + " is never one. This blessing would never apply.");
        }
        Predicate<Object> outer = who;
        return new Blessing<>(value, t, self -> t.isInstance(self) && outer.test(self), priority);
    }

    /** Only when this holds. Checked every time the game computes the value. */
    public Blessing<E> when(Predicate<? super E> condition) {
        Predicate<Object> outer = who;
        Class<E> t = type;
        return new Blessing<>(value, type, self -> outer.test(self) && condition.test(t.cast(self)), priority);
    }

    /**
     * Priority for {@link #set} (default 0): when two mods set the same value, the higher
     * priority wins. Stacking changes (multiply, add, clamp) always all apply, so it doesn't
     * matter for them. Write it as a number in your code ({@code .priority(10)}): the Prophecy
     * reads it at startup.
     */
    public Blessing<E> priority(int p) {
        return new Blessing<>(value, type, who, p);
    }

    // --- changes -----------------------------------------------------------------------------

    /** Multiplies the value. Stacks with every other mod's factors. */
    public void multiply(double factor) {
        rite("multiply", Blessings.Op.MULTIPLY, self -> factor, 0, 0);
    }

    /** Multiplies by a factor computed each time (from a config, the entity, the moon phase...). */
    public void multiply(ToDoubleFunction<? super E> factor) {
        rite("multiply", Blessings.Op.MULTIPLY, computed(factor), 0, 0);
    }

    /** Adds to the value (before everyone's factors). */
    public void add(double amount) {
        rite("add", Blessings.Op.ADD, self -> amount, 0, 0);
    }

    /** Adds an amount computed each time. */
    public void add(ToDoubleFunction<? super E> amount) {
        rite("add", Blessings.Op.ADD, computed(amount), 0, 0);
    }

    /**
     * Keeps the value within [min, max], after everything else. If two mods clamp to ranges that
     * don't overlap, that's a conflict and RGCT says so by name.
     */
    public void clamp(double min, double max) {
        if (min > max) {
            throw new IllegalArgumentException(value.label + ": clamp(" + min + ", " + max + ") has min > max");
        }
        rite("clamp", Blessings.Op.CLAMP, self -> 0, min, max);
    }

    /**
     * Replaces the value outright; other mods' adds and factors still apply on top. Two mods
     * setting different values at the same priority is a conflict.
     */
    public void set(double v) {
        rite("set", Blessings.Op.SET, self -> v, 0, 0);
    }

    @Override
    public String toString() {
        return value.label + " for " + type.getSimpleName();
    }

    private ToDoubleFunction<Object> computed(ToDoubleFunction<? super E> f) {
        Class<E> t = type;
        return self -> f.applyAsDouble(t.cast(self));
    }

    private void rite(String op, Blessings.Op kind, ToDoubleFunction<Object> amount, double min, double max) {
        int p = kind == Blessings.Op.SET ? priority : 0;
        String what = "Blessings." + value.method + "()..." + op + "()" + (p != 0 ? " at priority " + p : "");
        Faithful.join(what, new Blessings.Key(value, kind, p), new Rite(who, amount, min, max));
    }
}
