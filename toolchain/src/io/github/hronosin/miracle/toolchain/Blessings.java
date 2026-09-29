package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.rgct.ContextHook;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * Blessings: well-known game values that many mods want to change, with the merge rules
 * already worked out. Known as {@link Tweaks} to the unimaginative.
 *
 * <pre>{@code
 * @Override
 * public void onLaunch() {
 *     Blessings.jumpPower().forPlayers().multiply(1.5);
 *     Blessings.fallDamage().forPlayers().multiply(0.5);
 *     Blessings.damageTaken().forType(Cow.class).clamp(0, 2);
 * }
 * }</pre>
 *
 * <p>Every value is merged the RGCT way: {@code clamp((vanilla + all adds) x all factors)}.
 * Ten mods multiplying jump power all get their say; nobody overwrites anybody. {@code set}
 * is there for when you mean it, and priority decides between two mods that both mean it.
 *
 * <p>The library knows where each value lives, in every supported version, including the
 * places vanilla computes it twice (a player's speed isn't a mob's speed). You name the value;
 * it finds the methods. Like {@link Omens}, call it from {@code onLaunch()}; the Prophecy has
 * already patched what your mod will need.
 */
public class Blessings {

    /** The values. */
    enum Value {
        JUMP_POWER("jumpPower", "jump power", 'F', -1),
        MOVEMENT_SPEED("movementSpeed", "movement speed", 'F', -1),
        FALL_DAMAGE("fallDamage", "fall damage", 'I', -1),
        DAMAGE_TAKEN("damageTaken", "damage taken", 'F', 2);

        final String method;
        final String label;
        /** The value's JVM type: F float, I int. */
        final char kind;
        /** Which argument holds the value, or -1 for the return value. */
        final int arg;

        Value(String method, String label, char kind, int arg) {
            this.method = method;
            this.label = label;
            this.kind = kind;
            this.arg = arg;
        }

        static Value byMethod(String name) {
            for (Value v : values()) {
                if (v.method.equals(name)) {
                    return v;
                }
            }
            return null;
        }
    }

    /** What a blessing does to its value. */
    enum Op {
        MULTIPLY, ADD, CLAMP, SET;

        static Op byMethod(String name) {
            return switch (name) {
                case "multiply" -> MULTIPLY;
                case "add" -> ADD;
                case "clamp" -> CLAMP;
                case "set" -> SET;
                default -> null;
            };
        }
    }

    /** One hook: a value, an operation, and (for set, where it matters) a priority. */
    record Key(Value value, Op op, int priority) {
    }

    protected Blessings() {
    }

    /**
     * How hard a living thing pushes off the ground when it jumps. Vanilla: 0.42 for most
     * things, which lifts a player about 1.25 blocks. For your own player this runs on your
     * client (movement is the client's business), so the mod belongs on the client too.
     */
    public static Blessing<LivingEntity> jumpPower() {
        return new Blessing<>(Value.JUMP_POWER, LivingEntity.class);
    }

    /**
     * Walking speed. Players and mobs compute it in different places; both are covered. Like
     * jump power, a player's own movement is decided on their client.
     */
    public static Blessing<LivingEntity> movementSpeed() {
        return new Blessing<>(Value.MOVEMENT_SPEED, LivingEntity.class);
    }

    /** Fall damage in half-hearts, after vanilla's own math (safe distance, jump boost). Server side. */
    public static Blessing<LivingEntity> fallDamage() {
        return new Blessing<>(Value.FALL_DAMAGE, LivingEntity.class);
    }

    /**
     * Damage about to be dealt, in half-hearts, before armor and enchantments take their cut.
     * Every kind of damage. Server side.
     */
    public static Blessing<LivingEntity> damageTaken() {
        return new Blessing<>(Value.DAMAGE_TAKEN, LivingEntity.class);
    }

    // --- installation (startup, in the blessing mod's name) ------------------------------------

    /** One hook per value, operation and priority; it applies every matching rite the mod adds later. */
    static void install(Rgct rgct, Key key) {
        Faithful.foresee(rgct.modId(), key);
        List<Blessing.Rite> rites = Faithful.list(rgct.modId(), key);
        ContextHook hook = key.value().arg < 0 ? onReturn(key.op(), key.value().kind, rites)
                : onArg(key.op(), key.value().kind, key.value().arg, rites);
        int p = key.priority();
        switch (key.value()) {
            case JUMP_POWER -> rgct.target("net.minecraft.world.entity.LivingEntity")
                    .method("getJumpPower", "()F").priority(p).interceptReturn(hook);
            case MOVEMENT_SPEED -> {
                rgct.target("net.minecraft.world.entity.LivingEntity")
                        .method("getSpeed", "()F").priority(p).interceptReturn(hook);
                rgct.target("net.minecraft.world.entity.player.Player")
                        .method("getSpeed", "()F").priority(p).interceptReturn(hook);
            }
            case FALL_DAMAGE -> rgct.target("net.minecraft.world.entity.LivingEntity")
                    .method("calculateFallDamage", "(DF)I").priority(p).interceptReturn(hook);
            case DAMAGE_TAKEN -> rgct.target("net.minecraft.world.entity.LivingEntity")
                    .method("hurtServer", "(Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/damagesource/DamageSource;F)Z")
                    .priority(p).interceptHead(hook);
        }
    }

    /**
     * Separate lambdas per operation, so RGCT's startup scan sees exactly which effect each
     * hook can have ("modifies return", "sets return") and warns only about real clashes.
     */
    private static ContextHook onReturn(Op op, char kind, List<Blessing.Rite> rites) {
        return switch (op) {
            case MULTIPLY -> ctx -> {
                for (Blessing.Rite r : rites) {
                    if (r.applies(ctx.self())) {
                        ctx.multiplyReturnValue(r.amount(ctx.self()));
                    }
                }
            };
            case ADD -> ctx -> {
                for (Blessing.Rite r : rites) {
                    if (r.applies(ctx.self())) {
                        ctx.addToReturnValue(r.amount(ctx.self()));
                    }
                }
            };
            case CLAMP -> ctx -> {
                for (Blessing.Rite r : rites) {
                    if (r.applies(ctx.self())) {
                        ctx.clampReturnValue(r.min(), r.max());
                    }
                }
            };
            case SET -> ctx -> {
                for (Blessing.Rite r : rites) {
                    if (r.applies(ctx.self())) {
                        ctx.setReturnValue(box(kind, r.amount(ctx.self())));
                    }
                }
            };
        };
    }

    private static ContextHook onArg(Op op, char kind, int arg, List<Blessing.Rite> rites) {
        return switch (op) {
            case MULTIPLY -> ctx -> {
                for (Blessing.Rite r : rites) {
                    if (r.applies(ctx.self())) {
                        ctx.multiplyArg(arg, r.amount(ctx.self()));
                    }
                }
            };
            case ADD -> ctx -> {
                for (Blessing.Rite r : rites) {
                    if (r.applies(ctx.self())) {
                        ctx.addToArg(arg, r.amount(ctx.self()));
                    }
                }
            };
            case CLAMP -> ctx -> {
                for (Blessing.Rite r : rites) {
                    if (r.applies(ctx.self())) {
                        ctx.clampArg(arg, r.min(), r.max());
                    }
                }
            };
            case SET -> ctx -> {
                for (Blessing.Rite r : rites) {
                    if (r.applies(ctx.self())) {
                        ctx.setArg(arg, box(kind, r.amount(ctx.self())));
                    }
                }
            };
        };
    }

    private static Object box(char kind, double v) {
        return switch (kind) {
            case 'F' -> (float) v;
            case 'I' -> (int) Math.round(v);
            default -> v;
        };
    }
}
