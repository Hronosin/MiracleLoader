package io.github.hronosin.miracle.horizon;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Accretion (boring name: Attributes): what builds up around a living thing. Two parts:
 *
 * <p><b>Boosts</b> change the game's own attributes (speed, damage, armor, scale, anything with a
 * {@code Holder<Attribute>}) for a while, while a condition holds, or by a formula that's worked out
 * again every tick. They're ordinary attribute modifiers, so the game's rules stack them: all
 * additions first, then the base multipliers, then the total ones. The same id on the same entity
 * replaces the earlier boost. Boosts live while the entity is loaded; they aren't saved.
 *
 * <pre>{@code
 * Accretion.boost(player, Attributes.MOVEMENT_SPEED, "mymod:blessed_feet").multiplyBase(0.3).forTicks(200).start();
 * Accretion.boost(player, Attributes.ARMOR, "mymod:tide").add(6).when(Entity::isInWater).start();
 * Accretion.boost(player, Attributes.ATTACK_DAMAGE, "mymod:desperation")
 *         .value(e -> e.getHealth() < 6 ? 4 : 0).start();                  // recomputed every tick
 * }</pre>
 *
 * <p><b>Stats</b> are numbers of your own (mana, faith, heat) kept per entity on the world's
 * scoreboard, so they're saved with the world, and commands see them:
 * {@code /scoreboard players get @s mymod.mana}. Whole numbers, clamped to their range, server side.
 *
 * <pre>{@code
 * static final Accretion.Stat MANA = Accretion.stat("mymod:mana", 100).range(0, 200);
 * if (MANA.spend(player, 30)) castSpell(player);
 * }</pre>
 */
public final class Accretion {

    private static final Map<UUID, Map<Identifier, Boost>> BOOSTS = new ConcurrentHashMap<>();
    private static final List<Stat> STATS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Accretion() {
    }

    // --- boosts -------------------------------------------------------------------------------

    /** A boost to prepare; nothing happens until {@link Boost#start()}. {@code id}: "yourmod:name". */
    public static Boost boost(LivingEntity entity, Holder<Attribute> attribute, String id) {
        return new Boost(entity, attribute, Identifier.parse(id));
    }

    /** Ends a boost early, if it's there. */
    public static void stop(LivingEntity entity, String id) {
        Map<Identifier, Boost> mine = BOOSTS.get(entity.getUUID());
        if (mine != null) {
            Boost b = mine.remove(Identifier.parse(id));
            if (b != null) {
                b.detach();
            }
        }
    }

    /** The ids of an entity's running boosts. */
    public static List<String> active(LivingEntity entity) {
        Map<Identifier, Boost> mine = BOOSTS.get(entity.getUUID());
        List<String> out = new ArrayList<>();
        if (mine != null) {
            mine.keySet().forEach(k -> out.add(k.toString()));
        }
        return out;
    }

    /** A change to one attribute of one entity, by one id. */
    public static final class Boost {
        private final LivingEntity entity;
        private final Holder<Attribute> attribute;
        private final Identifier id;
        private AttributeModifier.Operation operation = AttributeModifier.Operation.ADD_VALUE;
        private double amount;
        private ToDoubleFunction<LivingEntity> formula;
        private Predicate<LivingEntity> condition;
        private long until = -1;
        private int ticks = -1;
        private double applied = Double.NaN;

        private Boost(LivingEntity entity, Holder<Attribute> attribute, Identifier id) {
            this.entity = entity;
            this.attribute = attribute;
            this.id = id;
        }

        /** Adds to the value (before any multiplying). */
        public Boost add(double amount) {
            this.operation = AttributeModifier.Operation.ADD_VALUE;
            this.amount = amount;
            return this;
        }

        /** Adds {@code fraction} of the base value: 0.3 is +30% of the base. */
        public Boost multiplyBase(double fraction) {
            this.operation = AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            this.amount = fraction;
            return this;
        }

        /** Multiplies the total by 1 + {@code fraction}, after everything else. */
        public Boost multiplyTotal(double fraction) {
            this.operation = AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            this.amount = fraction;
            return this;
        }

        /** The amount, worked out again every tick (with the operation chosen before, or adding). */
        public Boost value(ToDoubleFunction<LivingEntity> formula) {
            this.formula = formula;
            return this;
        }

        /** Only while this holds, checked every tick; the boost waits, doesn't end, while it doesn't. */
        public Boost when(Predicate<LivingEntity> condition) {
            this.condition = condition;
            return this;
        }

        /** Ends by itself after this many ticks. */
        public Boost forTicks(int ticks) {
            this.ticks = ticks;
            return this;
        }

        /** Replaces a boost with the same id on the same entity. */
        public Boost start() {
            if (ticks >= 0) {
                until = Redshift.now() + ticks;
            }
            Map<Identifier, Boost> mine = BOOSTS.computeIfAbsent(entity.getUUID(), k -> new ConcurrentHashMap<>());
            Boost old = mine.put(id, this);
            if (old != null) {
                old.detach();
            }
            update();
            return this;
        }

        public void stop() {
            Map<Identifier, Boost> mine = BOOSTS.get(entity.getUUID());
            if (mine != null && mine.remove(id, this)) {
                detach();
            }
        }

        /** Whether it's changing the attribute right now. */
        public boolean applied() {
            return !Double.isNaN(applied);
        }

        /** Ticks left, or -1 if it has no end. */
        public long remaining() {
            return until < 0 ? -1 : Math.max(0, until - Redshift.now());
        }

        /** False when it's over: expired, or its entity is gone. */
        private boolean update() {
            if (entity.isRemoved() || (until >= 0 && Redshift.now() >= until)) {
                detach();
                return false;
            }
            boolean on = condition == null || condition.test(entity);
            double want = on ? (formula != null ? formula.applyAsDouble(entity) : amount) : Double.NaN;
            if (Double.isNaN(want) || want == 0 && formula != null) {
                detach();
            } else if (want != applied) {
                AttributeInstance inst = entity.getAttribute(attribute);
                if (inst == null) {
                    return false; // this entity doesn't have the attribute at all
                }
                inst.removeModifier(id);
                inst.addTransientModifier(new AttributeModifier(id, want, operation));
                applied = want;
            }
            return true;
        }

        private void detach() {
            if (!Double.isNaN(applied)) {
                AttributeInstance inst = entity.getAttribute(attribute);
                if (inst != null) {
                    inst.removeModifier(id);
                }
                applied = Double.NaN;
            }
        }
    }

    /** Called by Event Horizon after every server tick. */
    static void tick() {
        for (var entry : BOOSTS.entrySet()) {
            entry.getValue().values().removeIf(b -> !b.update());
            if (entry.getValue().isEmpty()) {
                BOOSTS.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** The world ends. */
    static void reset() {
        BOOSTS.clear();
    }

    // --- stats --------------------------------------------------------------------------------

    /** A stat named "yourmod:name", with a value for entities that don't have one yet. */
    public static Stat stat(String id, int initial) {
        Identifier parsed = Identifier.parse(id);
        Stat s = new Stat(parsed.getNamespace() + "." + parsed.getPath(), initial);
        STATS.add(s);
        return s;
    }

    /** What a stat's change listener hears. */
    @FunctionalInterface
    public interface Change {
        void changed(Entity entity, int before, int after);
    }

    /** A number of your own, per entity, on the scoreboard (objective {@code yourmod.name}). */
    public static final class Stat {
        private final String objective;
        private final int initial;
        private int min = Integer.MIN_VALUE;
        private int max = Integer.MAX_VALUE;
        private final List<Change> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

        private Stat(String objective, int initial) {
            this.objective = objective;
            this.initial = initial;
        }

        public Stat range(int min, int max) {
            this.min = min;
            this.max = max;
            return this;
        }

        /** Hears every change made through this stat (not ones made by commands). */
        public Stat onChange(Change listener) {
            listeners.add(listener);
            return this;
        }

        /** The scoreboard objective's name. */
        public String objective() {
            return objective;
        }

        public int get(Entity e) {
            Scoreboard board = board(e);
            Objective o = board.getObjective(objective);
            if (o == null) {
                return clamp(initial);
            }
            ReadOnlyScoreInfo info = board.getPlayerScoreInfo(e, o);
            return info == null ? clamp(initial) : info.value();
        }

        /** Sets it (clamped to the range); returns what it became. */
        public int set(Entity e, int value) {
            int before = get(e);
            int after = clamp(value);
            board(e).getOrCreatePlayerScore(e, objective(board(e))).set(after);
            if (after != before) {
                listeners.forEach(l -> l.changed(e, before, after));
            }
            return after;
        }

        public int add(Entity e, int delta) {
            return set(e, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) get(e) + delta)));
        }

        /** Takes {@code cost} if there's that much, and says whether it did. */
        public boolean spend(Entity e, int cost) {
            if (get(e) < cost) {
                return false;
            }
            add(e, -cost);
            return true;
        }

        /** Back to the initial value (the entity's score is removed). */
        public void reset(Entity e) {
            Scoreboard board = board(e);
            Objective o = board.getObjective(objective);
            if (o != null) {
                board.resetSinglePlayerScore(e, o);
            }
        }

        private int clamp(int v) {
            return Math.max(min, Math.min(max, v));
        }

        private Objective objective(Scoreboard board) {
            Objective o = board.getObjective(objective);
            if (o == null) {
                o = board.addObjective(objective, ObjectiveCriteria.DUMMY, Component.literal(objective),
                        ObjectiveCriteria.RenderType.INTEGER, true, null);
            }
            return o;
        }

        private static Scoreboard board(Entity e) {
            if (!(e.level() instanceof ServerLevel level)) {
                throw new IllegalStateException("Accretion stats live on the server's scoreboard; this is the client side");
            }
            MinecraftServer server = level.getServer();
            return server.getScoreboard();
        }
    }

    /** A mob died: its stats go with it (players keep theirs). */
    static void forget(Entity dead) {
        if (dead instanceof net.minecraft.world.entity.player.Player) {
            return;
        }
        if (dead.level() instanceof ServerLevel && !STATS.isEmpty()) {
            for (Stat s : STATS) {
                s.reset(dead);
            }
        }
    }
}
