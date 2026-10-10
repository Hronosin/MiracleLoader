package test.nest;

import io.github.hronosin.miracle.api.MiracleMod;
import net.minecraft.world.entity.Wings;
import net.minecraft.world.entity.player.Player;

import java.io.Serializable;
import java.util.function.Supplier;

/**
 * Written for the readable fake game, which has Player#fly and Wings; the obfuscated one has
 * neither. Its fallback replaces say() (and with it everything only say() used: a lambda, a
 * serializable lambda, an anonymous class) and the nested Feathers, whose very header needs Wings.
 */
public final class NestMod implements MiracleMod {

    private static String mood = "calm";

    @Override
    public void onLaunch() {
        System.out.println("[nest-mod] " + say() + " / " + new Feathers().flap() + " / " + kept().get());
    }

    static String say() {
        Player p = new Player("Alex");
        Runnable lambda = () -> p.fly();
        Runnable saved = (Runnable & Serializable) () -> p.fly();
        Object anon = new Object() {
            @Override
            public String toString() {
                p.fly();
                return "an anonymous flight";
            }
        };
        lambda.run();
        saved.run();
        return anon.toString();
    }

    /** Not replaced: its lambda stays. */
    static Supplier<String> kept() {
        return () -> "kept " + mood;
    }

    static final class Feathers implements Wings {
        @Override
        public String flap() {
            return "feathers";
        }
    }
}
