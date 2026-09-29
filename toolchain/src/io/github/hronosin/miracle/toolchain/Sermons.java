package io.github.hronosin.miracle.toolchain;

import com.mojang.brigadier.CommandDispatcher;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Sermons: commands players type in chat. Known as {@link ChatCommands} to the unconverted.
 * Plain Brigadier, the same library vanilla uses for its own commands, so everything vanilla
 * can do (arguments, suggestions, permissions) you can do too.
 *
 * <pre>{@code
 * @Override
 * public void onLaunch() {
 *     Sermons.preach(d -> d.register(Commands.literal("hallelujah").executes(c -> {
 *         Sermons.reply(c.getSource(), "Amen.");
 *         return 1;
 *     })));
 * }
 * }</pre>
 *
 * <p>The game builds a fresh command tree whenever it (re)loads data packs; your commands are
 * added to every one of them, so {@code /reload} doesn't lose them. They work on dedicated
 * servers and in singleplayer. Players need the mod only on the server. Like {@link Omens},
 * preach from {@code onLaunch()}; the Prophecy has prepared the pulpit.
 */
public class Sermons {

    /** Registers commands, with what the game knows about items, blocks and the like at hand. */
    @FunctionalInterface
    public interface Pulpit {
        void preach(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context);
    }

    /** The key the Prophecy and the congregation know sermons by. */
    static final String KEY = "sermons";

    protected Sermons() {
    }

    /** Adds commands to every command tree the game builds from now on. */
    public static void preach(Consumer<CommandDispatcher<CommandSourceStack>> commands) {
        Faithful.join("Sermons.preach", KEY, (Pulpit) (dispatcher, context) -> commands.accept(dispatcher));
    }

    /**
     * Like {@link #preach(Consumer)}, with the build context that item, block and similar
     * arguments need.
     */
    public static void preach(Pulpit commands) {
        Faithful.join("Sermons.preach", KEY, commands);
    }

    /** Tells whoever ran the command something, in white. Not broadcast to operators. */
    public static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    /** Tells whoever ran the command that it didn't work out, in red. */
    public static void rebuke(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
    }

    /** Startup: every command tree the game builds gets this mod's sermons. */
    static void install(Rgct rgct) {
        Faithful.foresee(rgct.modId(), KEY);
        List<Pulpit> pulpits = Faithful.list(rgct.modId(), KEY);
        rgct.target("net.minecraft.commands.Commands")
                .method("<init>", "(Lnet/minecraft/commands/Commands$CommandSelection;"
                        + "Lnet/minecraft/commands/CommandBuildContext;)V")
                .interceptReturn(ctx -> {
                    for (Pulpit p : pulpits) {
                        p.preach(((Commands) ctx.self()).getDispatcher(), (CommandBuildContext) ctx.arg(1));
                    }
                });
    }
}
