package test.redirect;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

import java.io.PrintStream;

/** Exercises RGCT redirects against the fake game. */
public final class RedirectMod implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("title")
                // a static call, replaced by a static method reference (bound directly)
                .redirect(Player::motd, RedirectMod::motd)
                // a virtual call (receiver first), replaced by a lambda (bound through its interface)
                .redirect(Player::getName, p -> "Mr. " + p.getName())
                // a call title() never makes: warned about, harmless
                .redirect(Player::ticks, () -> -1)
                .and()
                .method("jumpFromGround")
                // a void call on a JDK class, an overloaded one, picked by the shape
                .redirectVoid(PrintStream::print, (PrintStream out, String s) -> out.print("~" + s))
                // a replacement taking wider types than the call passes
                .<PrintStream, String>redirectVoid(PrintStream::println, RedirectMod::shout)
                .and()
                .method("badge")
                // a new: StringBuilder::new (overloaded, so the witness picks the one taking a String)
                .<String, StringBuilder>redirect(StringBuilder::new, RedirectMod::badge)
                .and()
                .method("status")
                // an inherited method: javac names Entity.isAlive, the call site Player.isAlive
                .redirect(Player::isAlive, RedirectMod::gone);
    }

    public static void shout(Object out, Object line) {
        ((PrintStream) out).println(String.valueOf(line).toUpperCase());
    }

    public static StringBuilder badge(String name) {
        return new StringBuilder("~" + name + "!");
    }

    public static boolean gone(Player p) {
        return false;
    }

    public static String motd() {
        return "redirected";
    }
}
