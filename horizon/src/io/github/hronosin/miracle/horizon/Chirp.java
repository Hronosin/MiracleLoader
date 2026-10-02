package io.github.hronosin.miracle.horizon;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Chirp (boring name: Sounds): what two black holes sound like as they merge, and what your mod
 * sounds like. Plays sounds by id, the mod's own ({@code miracle scribe sound bell bell.wav} puts
 * them in place) or the game's, with nothing to register: the id goes to the players' games, and
 * their {@code sounds.json} knows the rest.
 *
 * <pre>{@code
 * Chirp.play(level, Geodesic.center(pos), "mymod:bell");            // everyone nearby, from that spot
 * Chirp.play(entity, "mymod:roar", 2.0f, 0.8f);                       // from a mob, louder and lower
 * Chirp.to(player, "mymod:secret");                                 // only that player hears it
 * Chirp.music(player, "mymod:music.theme");                         // on the music volume slider
 * Chirp.here("mymod:click");                                        // client side, for the player at the keyboard
 * }</pre>
 *
 * <p>Server side, except {@link #here}. A sound from a place is heard from there only if it's mono;
 * {@code scribe sound} makes it so.
 */
public final class Chirp {

    private Chirp() {
    }

    /** From a spot, for everyone near enough; neutral volume slider. */
    public static void play(Level level, Vec at, String id) {
        play(level, at, id, 1, 1, SoundSource.NEUTRAL);
    }

    public static void play(Level level, Vec at, String id, float volume, float pitch) {
        play(level, at, id, volume, pitch, SoundSource.NEUTRAL);
    }

    /** {@code volume} above 1 carries further (16 blocks each); {@code pitch} from 0.5 to 2. */
    public static void play(Level level, Vec at, String id, float volume, float pitch, SoundSource slider) {
        level.playSound(null, at.x(), at.y(), at.z(), event(id), slider, volume, pitch);
    }

    /** From an entity's position. */
    public static void play(Entity from, String id) {
        play(from, id, 1, 1);
    }

    public static void play(Entity from, String id, float volume, float pitch) {
        play(from.level(), Geodesic.position(from), id, volume, pitch, from.getSoundSource());
    }

    /** Only this player hears it, at their own position. */
    public static void to(ServerPlayer player, String id) {
        to(player, id, 1, 1);
    }

    public static void to(ServerPlayer player, String id, float volume, float pitch) {
        send(player, id, SoundSource.MASTER, volume, pitch);
    }

    /** For one player, on the music slider: their own soundtrack (a {@code --music} sound streams). */
    public static void music(ServerPlayer player, String id) {
        send(player, id, SoundSource.MUSIC, 1, 1);
    }

    private static void send(ServerPlayer player, String id, SoundSource slider, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(Holder.direct(event(id)), slider,
                player.getX(), player.getEyeY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }

    /** Client side: for the player at this keyboard only, like a button's click. */
    public static void here(String id) {
        ChirpClient.play(event(id), 1, 1);
    }

    public static void here(String id, float volume, float pitch) {
        ChirpClient.play(event(id), volume, pitch);
    }

    /** The game's sound event for an id ("mymod:bell"); unregistered ones are sent by name. */
    public static SoundEvent event(String id) {
        return SoundEvent.createVariableRangeEvent(Identifier.parse(id));
    }
}
