package io.github.hronosin.miracle.toolchain;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Proclamations: telling players things, the same way on every version (the game renamed half
 * of these between 1.21 and 26.x; packets didn't move). Known as {@link Notices} to the laity.
 * No hooks involved: use it from anywhere, any time after launch.
 */
public class Proclamations {

    protected Proclamations() {
    }

    /** A line above the hotbar, gone in a few seconds. */
    public static void overlay(ServerPlayer player, Component text) {
        player.connection.send(new ClientboundSetActionBarTextPacket(text));
    }

    /** {@link #overlay(ServerPlayer, Component)} with plain text. */
    public static void overlay(ServerPlayer player, String text) {
        overlay(player, Component.literal(text));
    }

    /**
     * A big title across the screen, and an optional smaller line below it. Times in ticks
     * (20 a second): fade in, stay, fade out.
     */
    public static void title(ServerPlayer player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        if (subtitle != null) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    /** A title with the usual timing: half a second in, three seconds up, a second out. */
    public static void title(ServerPlayer player, Component title, Component subtitle) {
        title(player, title, subtitle, 10, 60, 20);
    }

    /** A chat line for everyone on the server. */
    public static void broadcast(MinecraftServer server, Component text) {
        server.getPlayerList().broadcastSystemMessage(text, false);
    }

    /** {@link #broadcast(MinecraftServer, Component)} with plain text. */
    public static void broadcast(MinecraftServer server, String text) {
        broadcast(server, Component.literal(text));
    }
}
