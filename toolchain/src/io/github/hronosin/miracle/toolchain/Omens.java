package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Omens: things that happen in the game, and code of yours that runs when they do. Known as
 * {@link Events} to people who don't enjoy things.
 *
 * <pre>{@code
 * @Override
 * public void onLaunch() {
 *     Omens.playerJoined(p -> p.sendSystemMessage(Component.literal("Welcome, pilgrim.")));
 *     Omens.chat((player, message) -> message.contains("creeper") ? Verdict.SMITE : Verdict.SPARE);
 * }
 * }</pre>
 *
 * <p>Subscribe in {@code onLaunch()} (or any time after). There is nothing to set up in
 * {@code transform()}: at startup MiracleToolChain reads your mod's classes, sees which omens
 * it subscribes to, and patches exactly those game methods, in your mod's name. Omens nobody
 * asked for cost nothing; the startup report, conflict checks and crash blame all name your mod.
 * This is the Prophecy. It needs {@code depends = ["miracle-toolchain"]} in your
 * {@code miracle.mod.toml}, and calls written in your mod's own classes.
 *
 * <p>Server omens fire on the dedicated server and on the singleplayer (integrated) server alike,
 * on the server thread. Don't block it.
 */
public class Omens {

    /** What a handler decides about something that can be stopped. */
    public enum Verdict {
        /** Let it happen. */
        SPARE,
        /** Stop it. It never happened; the player sees it undone. */
        SMITE;

        /** {@link #SPARE}, for the humorless. */
        public static final Verdict ALLOW = SPARE;
        /** {@link #SMITE}, for the humorless. */
        public static final Verdict CANCEL = SMITE;
    }

    /** A chat message about to go out to everyone. */
    @FunctionalInterface
    public interface ChatJudge {
        Verdict judge(ServerPlayer player, String message);
    }

    /** A player about to break a block. */
    @FunctionalInterface
    public interface BlockJudge {
        Verdict judge(ServerPlayer player, BlockPos pos);
    }

    /** Something alive about to take damage. */
    @FunctionalInterface
    public interface HurtJudge {
        Verdict judge(LivingEntity victim, DamageSource source, float amount);
    }

    /** Every omen there is. The names match the methods. */
    enum Omen {
        SERVER_STARTED("serverStarted"),
        SERVER_STOPPING("serverStopping"),
        SERVER_TICK("serverTick"),
        PLAYER_JOINED("playerJoined"),
        PLAYER_LEFT("playerLeft"),
        PLAYER_JUMPED("playerJumped"),
        CHAT("chat"),
        BLOCK_BROKEN("blockBroken"),
        ENTITY_HURT("entityHurt"),
        ENTITY_DIED("entityDied"),
        CLIENT_TICK("clientTick");

        final String method;

        Omen(String method) {
            this.method = method;
        }

        static Omen byMethod(String name) {
            for (Omen o : values()) {
                if (o.method.equals(name)) {
                    return o;
                }
            }
            return null;
        }
    }

    protected Omens() {
    }

    // --- the server -------------------------------------------------------------------------

    /** The world is loaded and the server is about to start ticking. */
    public static void serverStarted(Consumer<MinecraftServer> handler) {
        Faithful.join("Omens.serverStarted", Omen.SERVER_STARTED, handler);
    }

    /** The server is shutting down; the world is still there, about to be saved. */
    public static void serverStopping(Consumer<MinecraftServer> handler) {
        Faithful.join("Omens.serverStopping", Omen.SERVER_STOPPING, handler);
    }

    /** After every server tick: 20 times a second, if the server keeps up. */
    public static void serverTick(Consumer<MinecraftServer> handler) {
        Faithful.join("Omens.serverTick", Omen.SERVER_TICK, handler);
    }

    // --- players ----------------------------------------------------------------------------

    /** A player has joined and is in the world. */
    public static void playerJoined(Consumer<ServerPlayer> handler) {
        Faithful.join("Omens.playerJoined", Omen.PLAYER_JOINED, handler);
    }

    /** A player is leaving; still in the world for one last look around. */
    public static void playerLeft(Consumer<ServerPlayer> handler) {
        Faithful.join("Omens.playerLeft", Omen.PLAYER_LEFT, handler);
    }

    /** A player jumped. Server side, so it counts jumps in singleplayer and multiplayer alike. */
    public static void playerJumped(Consumer<ServerPlayer> handler) {
        Faithful.join("Omens.playerJumped", Omen.PLAYER_JUMPED, handler);
    }

    /**
     * A player said something in chat. {@link Verdict#SMITE} and nobody hears it. Commands
     * ({@code /...}) are not chat and don't come through here.
     */
    public static void chat(ChatJudge judge) {
        Faithful.join("Omens.chat", Omen.CHAT, judge);
    }

    /** A player is about to break a block. {@link Verdict#SMITE} and the block stays. */
    public static void blockBroken(BlockJudge judge) {
        Faithful.join("Omens.blockBroken", Omen.BLOCK_BROKEN, judge);
    }

    // --- anything alive ---------------------------------------------------------------------

    /**
     * Something alive (a player, a mob, an armor stand...) is about to take damage.
     * {@code amount} is the damage before armor, and before any mod's {@link Blessings}.
     * {@link Verdict#SMITE} and the hit never lands. Server side.
     */
    public static void entityHurt(HurtJudge judge) {
        Faithful.join("Omens.entityHurt", Omen.ENTITY_HURT, judge);
    }

    /** Something alive has died, players included. The source says who or what did it. Server side. */
    public static void entityDied(BiConsumer<LivingEntity, DamageSource> handler) {
        Faithful.join("Omens.entityDied", Omen.ENTITY_DIED, handler);
    }

    // --- the client -------------------------------------------------------------------------

    /**
     * After every client tick (20 a second, paused or not). Never fires on a dedicated server,
     * which has no client to tick.
     */
    public static void clientTick(Consumer<Minecraft> handler) {
        Faithful.join("Omens.clientTick", Omen.CLIENT_TICK, handler);
    }

    // --- installation (startup, in the subscribing mod's name) ---------------------------------

    /** Patches the game method behind {@code omen}; the hook fires {@code mod}'s handlers. */
    static void install(Rgct rgct, Omen omen) {
        String mod = rgct.modId();
        Faithful.foresee(mod, omen);
        switch (omen) {
            case SERVER_STARTED -> {
                List<Consumer<MinecraftServer>> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.server.MinecraftServer")
                        .method("loadLevel", "()V")
                        .atReturn(self -> hs.forEach(h -> h.accept((MinecraftServer) self)));
            }
            case SERVER_STOPPING -> {
                List<Consumer<MinecraftServer>> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.server.MinecraftServer")
                        .method("stopServer", "()V")
                        .atHead(self -> hs.forEach(h -> h.accept((MinecraftServer) self)));
            }
            case SERVER_TICK -> {
                List<Consumer<MinecraftServer>> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.server.MinecraftServer")
                        .method("tickServer", "(Ljava/util/function/BooleanSupplier;)V")
                        .atReturn(self -> hs.forEach(h -> h.accept((MinecraftServer) self)));
            }
            case PLAYER_JOINED -> {
                List<Consumer<ServerPlayer>> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.server.players.PlayerList")
                        .method("placeNewPlayer", "(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;"
                                + "Lnet/minecraft/server/network/CommonListenerCookie;)V")
                        .interceptReturn(ctx -> hs.forEach(h -> h.accept((ServerPlayer) ctx.arg(1))));
            }
            case PLAYER_LEFT -> {
                List<Consumer<ServerPlayer>> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.server.players.PlayerList")
                        .method("remove", "(Lnet/minecraft/server/level/ServerPlayer;)V")
                        .interceptHead(ctx -> hs.forEach(h -> h.accept((ServerPlayer) ctx.arg(0))));
            }
            case PLAYER_JUMPED -> {
                List<Consumer<ServerPlayer>> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.server.level.ServerPlayer")
                        .method("jumpFromGround", "()V")
                        .atHead(self -> hs.forEach(h -> h.accept((ServerPlayer) self)));
            }
            case CHAT -> {
                List<ChatJudge> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.server.network.ServerGamePacketListenerImpl")
                        .method("broadcastChatMessage", "(Lnet/minecraft/network/chat/PlayerChatMessage;)V")
                        .interceptHead(ctx -> {
                            ServerPlayer player = ((ServerGamePacketListenerImpl) ctx.self()).player;
                            String message = ((PlayerChatMessage) ctx.arg(0)).signedContent();
                            for (ChatJudge h : hs) {
                                if (h.judge(player, message) == Verdict.SMITE) {
                                    ctx.cancel();
                                    return;
                                }
                            }
                        });
            }
            case BLOCK_BROKEN -> {
                List<BlockJudge> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.server.level.ServerPlayerGameMode")
                        .method("destroyBlock", "(Lnet/minecraft/core/BlockPos;)Z")
                        .interceptHead(ctx -> {
                            if (hs.isEmpty()) {
                                return;
                            }
                            ServerPlayer player = GameModePlayer.of((ServerPlayerGameMode) ctx.self());
                            BlockPos pos = (BlockPos) ctx.arg(0);
                            for (BlockJudge h : hs) {
                                if (h.judge(player, pos) == Verdict.SMITE) {
                                    ctx.cancel(false);
                                    return;
                                }
                            }
                        });
            }
            case ENTITY_HURT -> {
                List<HurtJudge> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.world.entity.LivingEntity")
                        .method("hurtServer", "(Lnet/minecraft/server/level/ServerLevel;"
                                + "Lnet/minecraft/world/damagesource/DamageSource;F)Z")
                        .interceptHead(ctx -> {
                            LivingEntity victim = (LivingEntity) ctx.self();
                            DamageSource source = (DamageSource) ctx.arg(1);
                            float amount = (Float) ctx.arg(2);
                            for (HurtJudge h : hs) {
                                if (h.judge(victim, source, amount) == Verdict.SMITE) {
                                    ctx.cancel(false);
                                    return;
                                }
                            }
                        });
            }
            case ENTITY_DIED -> {
                // ServerPlayer does its own dying without calling LivingEntity's, so both.
                List<BiConsumer<LivingEntity, DamageSource>> hs = Faithful.list(mod, omen);
                rgct.target("net.minecraft.world.entity.LivingEntity")
                        .method("die", "(Lnet/minecraft/world/damagesource/DamageSource;)V")
                        .interceptHead(ctx -> hs.forEach(h -> h.accept((LivingEntity) ctx.self(), (DamageSource) ctx.arg(0))));
                rgct.target("net.minecraft.server.level.ServerPlayer")
                        .method("die", "(Lnet/minecraft/world/damagesource/DamageSource;)V")
                        .interceptHead(ctx -> hs.forEach(h -> h.accept((LivingEntity) ctx.self(), (DamageSource) ctx.arg(0))));
            }
            case CLIENT_TICK -> {
                if (Mods.game().client()) {
                    List<Consumer<Minecraft>> hs = Faithful.list(mod, omen);
                    rgct.target("net.minecraft.client.Minecraft")
                            .method("tick", "()V")
                            .atReturn(self -> hs.forEach(h -> h.accept((Minecraft) self)));
                }
            }
        }
    }

    /**
     * ServerPlayerGameMode keeps its player in a protected field with no getter. Found by type,
     * not by name, so it works the same when the name is obfuscated.
     */
    private static final class GameModePlayer {
        private static final VarHandle PLAYER = find();

        static ServerPlayer of(ServerPlayerGameMode mode) {
            return (ServerPlayer) PLAYER.get(mode);
        }

        private static VarHandle find() {
            for (Field f : ServerPlayerGameMode.class.getDeclaredFields()) {
                if (f.getType() == ServerPlayer.class) {
                    try {
                        return MethodHandles.privateLookupIn(ServerPlayerGameMode.class, MethodHandles.lookup())
                                .unreflectVarHandle(f);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            throw new IllegalStateException("ServerPlayerGameMode has no ServerPlayer field in this version");
        }
    }
}
