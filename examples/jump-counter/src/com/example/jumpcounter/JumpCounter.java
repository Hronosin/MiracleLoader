package com.example.jumpcounter;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts your jumps and shows the count above the hotbar.
 *
 * <p>Written for 26.2, where the hotbar message is {@code sendOverlayMessage(Component)}.
 * Minecraft 1.21.11 doesn't have that method, so miracle-bake reports a hole there, and
 * {@code fallback/1.21.11/src} fills it: one replacement method, nothing else.
 */
public final class JumpCounter implements MiracleMod {

    private static final Map<UUID, Integer> JUMPS = new ConcurrentHashMap<>();

    @Override
    public void transform(Rgct rgct) {
        // Server side: runs once per jump, for singleplayer (integrated server) and multiplayer alike.
        rgct.target("net.minecraft.server.level.ServerPlayer")
                .method("jumpFromGround", "()V")
                .atHead(self -> count((ServerPlayer) self));
    }

    static void count(ServerPlayer player) {
        int n = JUMPS.merge(player.getUUID(), 1, Integer::sum);
        show(player, Component.literal("Jumps: " + n));
    }

    /** The one method that differs between versions. */
    static void show(ServerPlayer player, Component message) {
        player.sendOverlayMessage(message);
    }
}
