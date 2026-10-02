package io.github.hronosin.miracle.horizon;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.hronosin.miracle.toolchain.Sermons;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Telescope (boring name: Debug): {@code /horizon}, for looking into Event Horizon from the game.
 * Game masters only (permission level 2).
 * <ul>
 * <li>{@code /horizon values}: every {@link Penrose} value, who declared it, what it is now, how many
 *     layers, how many conflicts.</li>
 * <li>{@code /horizon why <id>}: how one came out, step by step, for whoever runs the command
 *     (use {@code /execute as <entity> run horizon why <id>} for someone else).</li>
 * <li>{@code /horizon bridges}: every {@link Wormhole} bridge (open, closed, failed, and why) and
 *     every service, with the one that wins.</li>
 * <li>{@code /horizon dice <rolls> <pool>}: rolls a named {@link QuantumFoam} pool and compares what
 *     came up with what should have.</li>
 * </ul>
 */
final class Telescope {

    private Telescope() {
    }

    static void install() {
        Sermons.preach(Telescope::register);
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("horizon")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("values").executes(Telescope::values))
                .then(Commands.literal("bridges").executes(Telescope::bridges))
                .then(Commands.literal("why")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .suggests((c, b) -> suggest(Penrose.ids(), b))
                                .executes(Telescope::why)))
                .then(Commands.literal("dice")
                        .then(Commands.argument("rolls", IntegerArgumentType.integer(1, 1_000_000))
                                .then(Commands.argument("pool", StringArgumentType.greedyString())
                                        .suggests((c, b) -> suggest(QuantumFoam.named().keySet(), b))
                                        .executes(Telescope::dice)))));
    }

    private static CompletableFuture<Suggestions> suggest(Collection<String> ids, SuggestionsBuilder b) {
        String typed = b.getRemaining().toLowerCase(java.util.Locale.ROOT);
        ids.stream().sorted().filter(id -> id.startsWith(typed) || id.substring(id.indexOf(':') + 1).startsWith(typed))
                .forEach(b::suggest);
        return b.buildFuture();
    }

    private static int values(CommandContext<CommandSourceStack> c) {
        List<String> ids = Penrose.ids();
        if (ids.isEmpty()) {
            Sermons.reply(c.getSource(), "No Penrose values: nobody declared or touched any.");
            return 0;
        }
        StringBuilder sb = new StringBuilder(ids.size() + " Penrose value(s):");
        for (String id : ids) {
            List<Penrose.Layer> layers = Penrose.layers(id);
            var declared = Penrose.declared(id);
            sb.append("\n  ").append(id);
            if (declared.isPresent()) {
                Penrose.Value<?> v = declared.get();
                Object now = v.get();
                sb.append(" = ").append(now instanceof Double d ? Penrose.num(d) : now)
                        .append("  [").append(v.kind()).append(" of ").append(v.owner()).append(", ")
                        .append(layers.size()).append(" layer(s)");
                int conflicts = v.conflicts(null).size();
                if (conflicts > 0) {
                    sb.append(", ").append(conflicts).append(" conflict(s)");
                }
                sb.append(']');
            } else {
                sb.append("  [not declared by any mod here; ").append(layers.size()).append(" layer(s) waiting]");
            }
        }
        reply(c.getSource(), sb.toString());
        return ids.size();
    }

    private static int bridges(CommandContext<CommandSourceStack> c) {
        reply(c.getSource(), Wormhole.report());
        return Wormhole.bridges().size();
    }

    private static int why(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "id").trim();
        var declared = Penrose.declared(id);
        if (declared.isEmpty()) {
            List<Penrose.Layer> layers = Penrose.layers(id);
            if (layers.isEmpty()) {
                Sermons.rebuke(c.getSource(), "No Penrose value '" + id + "'. /horizon values lists them.");
                return 0;
            }
            StringBuilder sb = new StringBuilder(id + " isn't declared by any mod here, so these wait for nothing:");
            layers.forEach(l -> sb.append("\n  ").append(l));
            reply(c.getSource(), sb.toString());
            return 0;
        }
        Object who = c.getSource().getEntity();
        reply(c.getSource(), declared.get().explain(who));
        return 1;
    }

    private static int dice(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "pool").trim();
        int rolls = IntegerArgumentType.getInteger(c, "rolls");
        QuantumFoam.Pool<?> pool = QuantumFoam.named().get(id);
        if (pool == null) {
            Sermons.rebuke(c.getSource(), "No pool named '" + id + "'. Named pools: "
                    + (QuantumFoam.named().isEmpty() ? "none." : String.join(", ", QuantumFoam.named().keySet())));
            return 0;
        }
        reply(c.getSource(), QuantumFoam.histogram(pool, rolls));
        return rolls;
    }

    private static void reply(CommandSourceStack source, String text) {
        for (String line : text.split("\n")) {
            Sermons.reply(source, line);
        }
    }
}
