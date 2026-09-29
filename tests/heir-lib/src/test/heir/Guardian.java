package test.heir;

import net.minecraft.world.entity.player.Player;

/**
 * A library class that extends a game class and overrides one of its methods, like
 * MiracleToolChain's Reliquary. Mods extend it in turn.
 */
public class Guardian extends Player {
    public Guardian(String name) {
        super(name);
    }

    @Override
    public int getScore() {
        return 7;
    }

    /** The library's own method: no game name to translate. */
    public String blessing() {
        return "blessed";
    }
}
