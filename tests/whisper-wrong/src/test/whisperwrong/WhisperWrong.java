package test.whisperwrong;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/** Names Entity.whisper: Player is an Entity, and has a whisper, but Entity has none. Matches nothing. */
public final class WhisperWrong implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("secret")
                .redirect(Rgct.call("net.minecraft.world.entity.Entity", "whisper", "(Ljava/lang/String;)Ljava/lang/String;"),
                        WhisperWrong::shout);
    }

    static String shout(Player p, String s) {
        return "WRONG";
    }
}
