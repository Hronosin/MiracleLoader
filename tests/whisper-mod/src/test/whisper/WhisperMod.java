package test.whisper;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Redirects calls a method reference can't name: a private one, and a super one. */
public final class WhisperMod implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("secret")
                .redirect(Rgct.call("net.minecraft.world.entity.player.Player", "whisper", "(Ljava/lang/String;)Ljava/lang/String;"),
                        WhisperMod::shout)
                .and()
                .method("describe")
                .redirect(Rgct.superCall("net.minecraft.world.entity.Entity", "describe", "()Ljava/lang/String;"),
                        WhisperMod::creature);
        if (Boolean.getBoolean("whisper.misfit")) {
            rgct.target("net.minecraft.world.entity.player.Player")
                    .method("status")
                    .redirect(Rgct.call("net.minecraft.world.entity.Entity", "isAlive", "()Z"), WhisperMod::alone);
        }
    }

    static String shout(Player p, String s) {
        return s.toUpperCase() + "!";
    }

    static String creature(Entity e) {
        return "creature";
    }

    /** Forgets the receiver: doesn't fit an instance call. */
    static boolean alone() {
        return false;
    }
}
