package com.example.hallelujah;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.toolchain.Blessings;
import io.github.hronosin.miracle.toolchain.Commandments;
import io.github.hronosin.miracle.toolchain.Omens;
import io.github.hronosin.miracle.toolchain.Omens.Verdict;
import io.github.hronosin.miracle.toolchain.Sermons;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tour of the MiracleToolChain library in one small mod, all of it configurable in
 * {@code config/hallelujah.toml}:
 * <ul>
 *   <li>a greeting for everyone who joins (Omens);</li>
 *   <li>words that never make it into chat (Omens, cancellable);</li>
 *   <li>a sanctuary around 0,0 where only operators may break blocks (Omens, cancellable);</li>
 *   <li>higher jumps and softer landings for players (Blessings, stacking with other mods);</li>
 *   <li>{@code /hallelujah} and {@code /miracles} (Sermons);</li>
 *   <li>the settings themselves (Commandments).</li>
 * </ul>
 * There is no transform() here, and no game method is named anywhere in this file. At startup
 * the library reads this class, sees what it subscribes to, and patches exactly that.
 */
public final class Hallelujah implements MiracleMod {

    private static final AtomicLong AMENS = new AtomicLong();

    @Override
    public void onLaunch() {
        Commandments config = Commandments.mine();
        String greeting = config.text("greeting", "Welcome, %s. Miracles are enabled on this server.",
                "Said to every player who joins. %s is their name.");
        List<String> forbidden = config.list("forbidden_words", List.of("creeper", "herobrine"),
                "Chat messages containing any of these (any case) are never delivered.");
        long sanctuary = config.integer("sanctuary_radius", 8,
                "Blocks within this many blocks of x=0, z=0 can only be broken by operators. 0 = no sanctuary.");
        double jump = config.number("jump_multiplier", 1.25, "Player jump power. 1 = vanilla.");
        double landing = config.number("fall_damage_multiplier", 0.5, "Player fall damage. 1 = vanilla, 0 = none.");

        Omens.serverStarted(server -> System.out.println("[hallelujah] The server has risen. "
                + Mods.all().size() + " mod(s) bless it."));
        Omens.playerJoined(player -> player.sendSystemMessage(
                Component.literal(greeting.formatted(player.getName().getString()))));
        Omens.chat((player, message) -> {
            String lower = message.toLowerCase(Locale.ROOT);
            for (String word : forbidden) {
                if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                    player.sendSystemMessage(Component.literal("Thou shalt not say \"" + word + "\"."));
                    return Verdict.SMITE;
                }
            }
            return Verdict.SPARE;
        });
        Omens.blockBroken((player, pos) -> sanctuary > 0
                && Math.abs(pos.getX()) <= sanctuary && Math.abs(pos.getZ()) <= sanctuary
                && !isOperator(player)
                ? Verdict.SMITE : Verdict.SPARE);

        Blessings.jumpPower().forPlayers().multiply(jump);
        Blessings.fallDamage().forPlayers().multiply(landing);

        Sermons.preach(d -> {
            d.register(Commands.literal("hallelujah")
                    .executes(c -> {
                        long n = AMENS.incrementAndGet();
                        Sermons.reply(c.getSource(), "Amen. (" + n + (n == 1 ? " time" : " times") + " so far)");
                        return 1;
                    })
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                            .executes(c -> {
                                AMENS.incrementAndGet();
                                Sermons.reply(c.getSource(), "Amen, for " + StringArgumentType.getString(c, "reason") + ".");
                                return 1;
                            })));
            d.register(Commands.literal("miracles").executes(c -> {
                for (Mods.Mod m : Mods.all()) {
                    Sermons.reply(c.getSource(), "- " + m.name() + " " + m.version() + (m.library() ? " (library)" : ""));
                }
                return Mods.all().size();
            }));
        });
    }

    private static boolean isOperator(ServerPlayer player) {
        return player.level().getServer().getPlayerList().isOp(player.nameAndId());
    }
}
