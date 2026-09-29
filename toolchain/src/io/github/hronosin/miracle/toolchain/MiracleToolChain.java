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
        if (Commandments.of(ID).flag("smite", true,
                "/smite [reason]: operators may crash the server on purpose, with a crash report blaming the heavens."
                        + "\nUseless, and it stays that way.")) {
            Smite.install(rgct);
        }
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
            if (f.scripture()) {
                Scripture.install(theirs);
            }
            Log.info("MiracleToolChain foresees for " + mod.id() + ": " + f);
        }
    }
}
