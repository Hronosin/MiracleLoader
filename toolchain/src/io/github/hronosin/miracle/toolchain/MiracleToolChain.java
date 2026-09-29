package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.Log;
import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;

/**
 * The library's own entrypoint. It has one job, done at startup: read every mod that depends on
 * it, foresee what each will use, and patch exactly that, in each mod's name. See {@link Prophecy}.
 */
public final class MiracleToolChain implements MiracleMod {

    /** The library's mod id, for {@code depends}. */
    public static final String ID = "miracle-toolchain";

    @Override
    public void transform(Rgct rgct) {
        Commandments settings = Commandments.of(ID);
        if (settings.flag("smite", true,
                "/smite [reason]: operators may crash the server on purpose, with a crash report blaming the heavens."
                        + "\nUseless, and it stays that way.")) {
            Smite.install(rgct);
        }
        Telepathy.action = settings.text("inquisition", "drop",
                "What the server does with Telepathy batches out of the honest rhythm (replayed, flooded, unknown"
                        + "\nchannels, garbage): \"log\" and carry on, \"drop\" the batch, or \"kick\" the player.");
        Telepathy.batchesPerTick = (int) settings.integer("inquisition_batches_per_tick", 4,
                "Telepathy batches a client may send in one tick before the Inquisition takes an interest.");
        Communion.enabled = settings.flag("communion", true,
                "Compare mods with the other side when a player joins, and turn them away with a list of what's"
                        + "\nmissing instead of letting them crash on the first unknown block.");
        Communion.timeoutSeconds = (int) Math.max(1, settings.integer("communion_timeout", 10,
                "Seconds the server waits for a joining game to answer communion before deciding it's vanilla"
                        + "\n(only when the server has mods the client must have too)."));

        boolean creation = false;
        boolean beings = false;
        boolean visions = false;
        boolean telepathy = false;
        boolean gestures = false;
        for (Mods.Mod mod : Mods.all()) {
            if (!mod.depends().contains(ID)) {
                continue;
            }
            Prophecy.Foresight f = Prophecy.read(mod.jar());
            f.doubts().forEach(d -> Log.warn("MiracleToolChain: " + mod.id() + ": " + d));
            if (f.empty()) {
                Log.info("MiracleToolChain: " + mod.id() + " depends on the library but uses none of its hooks.");
                continue;
            }
            Rgct theirs = rgct.onBehalfOf(mod.id());
            f.omens().forEach(o -> Omens.install(theirs, o));
            f.blessings().forEach(k -> Blessings.install(theirs, k));
            if (f.sermons()) {
                Sermons.install(theirs);
            }
            if (f.scripture() || f.creation()) {
                // New items and blocks can't do without their models, names and loot tables:
                // a mod that creates things has its data and assets revealed without asking.
                Scripture.install(theirs, f.creation());
            }
            if (f.creation()) {
                Faithful.foresee(mod.id(), Creation.KEY);
                creation = true;
                beings |= f.beings();
                visions |= f.visions();
            }
            if (f.telepathy()) {
                Faithful.foresee(mod.id(), Telepathy.KEY);
                telepathy = true;
            }
            if (f.creation() || f.telepathy()) {
                Communion.bind(mod.id(), f.creation()); // both sides need it, and in the same version
            }
            if (f.gestures()) {
                Faithful.foresee(mod.id(), Gestures.KEY);
                gestures = true;
            }
            Log.info("MiracleToolChain foresees for " + mod.id() + ": " + f);
        }

        // The shared machinery, in the library's own name: one registry, one wire, one keyboard.
        boolean client = Mods.game().client();
        if (creation) {
            Creation.install(rgct, beings, visions, client);
        }
        if (Communion.enabled) {
            Communion.install(rgct, client);
        }
        if (telepathy) {
            Telepathy.install(rgct, client);
        }
        if (gestures && client) {
            Gestures.install(rgct);
        }
    }
}
