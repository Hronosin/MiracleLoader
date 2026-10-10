package com.example.hallelujah;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.toolchain.Being;
import io.github.hronosin.miracle.toolchain.Blessings;
import io.github.hronosin.miracle.toolchain.Commandments;
import io.github.hronosin.miracle.toolchain.Creation;
import io.github.hronosin.miracle.toolchain.Gestures;
import io.github.hronosin.miracle.toolchain.Omens.Verdict;
import io.github.hronosin.miracle.toolchain.Omens;
import io.github.hronosin.miracle.toolchain.Proclamations;
import io.github.hronosin.miracle.toolchain.Relic;
import io.github.hronosin.miracle.toolchain.Reliquary;
import io.github.hronosin.miracle.toolchain.Sanctuary;
import io.github.hronosin.miracle.toolchain.Scroll;
import io.github.hronosin.miracle.toolchain.Shrine;
import io.github.hronosin.miracle.toolchain.Sermons;
import io.github.hronosin.miracle.toolchain.Telepathy;
import io.github.hronosin.miracle.toolchain.Vision;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>holy water to throw, and heretics (Creation), with models, textures and names from
 *       {@code resources/} (written by {@code miracle scribe});</li>
 *   <li>an altar that fills empty bottles with holy water, showing its progress (a Shrine that
 *       keeps Vigil, and a Vision with a caption), and a reliquary to keep things in (three rows,
 *       one line of code);</li>
 *   <li>a key, G, to pray (Gestures): the client asks, the server decides (Telepathy);</li>
 *   <li>the settings themselves (Commandments).</li>
 * </ul>
 * There is no transform() here, and no game method is named anywhere in this file. At startup
 * the library reads this class, sees what it subscribes to, and patches exactly that.
 */
public final class Hallelujah implements MiracleMod {

    private static final AtomicLong AMENS = new AtomicLong();

    /** Client to server: "I pray". Carries nothing the server should believe. */
    static final Telepathy.Channel PRAYER = Telepathy.channel("hallelujah:prayer");
    /** Server to client: the answer. */
    static final Telepathy.Channel ANSWER = Telepathy.channel("hallelujah:answer");

    static Relic<Item> holyWater;
    static Relic<Block> altar;
    static Shrine<AltarEntity> altarEntity;
    static Vision<AltarMenu> altarMenu;
    static Relic<Block> reliquary;
    static Shrine<Reliquary> reliquaryEntity;
    static Being<ThrownHolyWater> thrownHolyWater;
    static Being<Heretic> heretic;
    private static final Map<UUID, Integer> LAST_PRAYER = new ConcurrentHashMap<>();
    private static volatile int tick;

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

        // New things. Their models, textures, names and the altar's loot table are in resources/.
        holyWater = Creation.item("holy_water", p -> new HolyWaterItem(p.stacksTo(16))).inTab("food_and_drinks");
        // The altar: a block with insides (a Shrine) that bless bottles, and a menu (a Vision)
        // that shows how far along the blessing is.
        altar = Creation.block("altar", p -> new Sanctuary(p.strength(2f))).inTab("functional_blocks");
        altarEntity = Creation.shrine("altar", AltarEntity::new, altar).enshrines(0); // what's on it floats above
        altarMenu = Creation.vision("altar", AltarMenu::new)
                .caption(menu -> menu.progress() > 0
                        ? Component.translatable("container.hallelujah.altar.blessing", menu.progress())
                        : Component.translatable("container.hallelujah.altar.idle"));
        // A chest of our own: three rows, saved, dropped when broken, hopper-friendly.
        reliquary = Creation.block("reliquary", p -> new Sanctuary(p.strength(2.5f))).inTab("functional_blocks");
        reliquaryEntity = Creation.reliquary("reliquary", 3, reliquary);
        // Holy water in flight, drawn as the item it carries.
        thrownHolyWater = Creation.entity("thrown_holy_water", () -> EntityType.Builder
                        .<ThrownHolyWater>of(ThrownHolyWater::new, MobCategory.MISC)
                        .sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(10))
                .looksLikeItem();
        // A zombie with opinions, in a robe of its own. Holy water hits it twice as hard.
        heretic = Creation.entity("heretic", () -> EntityType.Builder
                        .of(Heretic::new, MobCategory.MONSTER).sized(0.6f, 1.95f).clientTrackingRange(8))
                .attributes(() -> Zombie.createAttributes())
                .sculpted()     // its own model, from Blockbench: resources/assets/hallelujah/geo/heretic.geo.json
                // ...animated by Blockbench's controllers (animation_controllers/heretic...json), which can
                // ask "q.is_burning": heretics burn in the sun like any zombie, and panic when they do.
                .query("is_burning", h -> h.isOnFire() ? 1 : 0)
                .spawnEgg()
                .spawns(30, 1, 2, "#minecraft:is_overworld");   // at night, in the dark, like other monsters

        // Press G to pray. The client only asks; the server decides whether anything happens,
        // how much, and how often. Never let the client say how much to heal.
        Gestures.key("pray", "G", () -> PRAYER.toServer(new Scroll().writeString("hallelujah")));
        Omens.serverTick(server -> tick = server.getTickCount());
        PRAYER.onServer((player, scroll) -> {
            Integer last = LAST_PRAYER.get(player.getUUID());
            if (last != null && tick - last < 200) {
                Proclamations.overlay(player, "The heavens are busy. Pray again in " + (200 - (tick - last)) / 20 + "s.");
                return;
            }
            LAST_PRAYER.put(player.getUUID(), tick);
            player.heal(4f);
            Proclamations.overlay(player, "Your prayer was heard. (+2 hearts)");
            ANSWER.toPlayer(player, new Scroll().writeString("heard").writeInt((int) AMENS.incrementAndGet()));
        });
        ANSWER.onClient(scroll -> System.out.println("[hallelujah] The heavens answered: " + scroll));

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
