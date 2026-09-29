package com.example.jumpcounter;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fallback for Minecraft 1.21.11, compiled against 1.21.11's readable API. A partial class:
 * only show() has a body, so only show() replaces the real one. Everything else stays as the
 * main source wrote it.
 */
final class JumpCounter {

    static void show(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true); // true: above the hotbar, not in chat
    }
}
