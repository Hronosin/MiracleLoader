package io.github.hronosin.miracle.toolchain;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /smite [reason]}: crashes the server on purpose, with a crash report that blames the
 * heavens. Operators only (owner level). The only command in the library nobody needs; Forge
 * has a whole toolchain like that, we have one command.
 *
 * <p>Only the game's own server stops, the proper way: the next tick throws, the game writes
 * its crash report ({@code crash-reports/}) and shuts down. In singleplayer you land on the
 * title screen; the world is saved up to the last autosave, as with any crash. Turn it off with
 * {@code smite = false} in {@code config/miracle-toolchain.toml}.
 */
final class Smite {

    /** What the crash report shows as the cause. */
    static final class Smitten extends RuntimeException {
        Smitten(String reason) {
            super("Smitten by MiracleToolChain: " + reason);
        }
    }

    private static volatile String pending;

    private Smite() {
    }

    static void install(Rgct rgct) {
        rgct.target("net.minecraft.commands.Commands")
                .method("<init>", "(Lnet/minecraft/commands/Commands$CommandSelection;"
                        + "Lnet/minecraft/commands/CommandBuildContext;)V")
                .atReturn(self -> register((Commands) self));
        rgct.target("net.minecraft.server.MinecraftServer")
                .method("tickServer", "(Ljava/util/function/BooleanSupplier;)V")
                .atReturn(self -> {
                    String reason = pending;
                    if (reason != null) {
                        pending = null;
                        throw new Smitten(reason);
                    }
                });
    }

    private static void register(Commands commands) {
        commands.getDispatcher().register(Commands.literal("smite")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(c -> smite(c, "thou hast asked for it"))
                .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> smite(c, StringArgumentType.getString(c, "reason")))));
    }

    private static int smite(CommandContext<CommandSourceStack> c, String reason) {
        Sermons.reply(c.getSource(), "So be it. The server will fall on the next tick.");
        pending = reason;
        return 1;
    }
}
