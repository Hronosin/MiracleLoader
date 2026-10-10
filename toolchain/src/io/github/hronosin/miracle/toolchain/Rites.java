package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.api.Mods;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Rites: a sculpted being's animations, played from code. Known as {@link Animations} to the
 * secular.
 *
 * <pre>{@code
 * // the heretic raises its book: plays animation.heretic.preach (or anything ending in .preach)
 * Rites.play(heretic, "preach");
 * Rites.stop(heretic, "preach");      // a looping one plays until stopped
 * }</pre>
 *
 * <p>From the server, every player who can see the entity sees it play (those who come later
 * don't: for a state that lasts, give the being a {@link Being#query query} and let an animation
 * controller decide). From a client, it plays on that client only. It plays on top of whatever
 * the being is doing: idle and walking, or its animation controllers. A {@code loop: true}
 * animation plays until stopped, {@code hold_on_last_frame} holds its end until stopped, and the
 * rest play once. Playing one that's playing starts it again.
 *
 * <p>The name is an animation's full name ({@code animation.heretic.preach}) or the last part of
 * it ({@code preach}). One the being's model doesn't have is said once in the client's log.
 */
public class Rites {

    static final String KEY = "rites";
    static final String CHANNEL = "miracle-toolchain:rites";

    protected Rites() {
    }

    /** Plays a sculpted being's animation, once or until stopped: see the class. */
    public static void play(Entity entity, String animation) {
        send(entity, animation, true);
    }

    /** Stops an animation {@link #play} started. */
    public static void stop(Entity entity, String animation) {
        send(entity, animation, false);
    }

    private static void send(Entity entity, String animation, boolean play) {
        if (entity == null || animation == null || animation.isBlank()) {
            throw new IllegalArgumentException("Rites." + (play ? "play" : "stop") + " needs an entity and an animation name");
        }
        if (entity.level().isClientSide()) {
            Sculptor.rite(entity, animation, play);
            return;
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        Scroll s = new Scroll().writeInt(entity.getId()).writeString(animation).writeBoolean(play);
        double range = Math.max(1, entity.getType().clientTrackingRange()) * 16.0;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(entity) <= range * range) {
                channel().toPlayer(p, s);
            }
        }
    }

    static Telepathy.Channel channel() {
        return Telepathy.channel(CHANNEL);
    }

    /** Startup, on a client: plays what the server says. In the library's name. */
    static void install() {
        if (!Mods.game().client()) {
            return;
        }
        channel().client.add(s -> Sculptor.received(s.readInt(), s.readString(), s.readBoolean()));
    }
}
