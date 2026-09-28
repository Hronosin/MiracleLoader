package com.example.hello;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.world.entity.player.Player;

/**
 * The whole "toolchain" for this mod is javac and jar. See build.sh.
 */
public final class HelloMod implements MiracleMod {

    @Override
    public void transform(Rgct rgct) {
        rgct.target("net.minecraft.world.entity.player.Player")
                .method("jumpFromGround")
                .atHead(self -> System.out.println("[hello-mod] hop, bytecode patch for "
                        + ((Player) self).getName()))
                .and()
                .method("getScore")
                .atReturn(self -> System.out.println("[hello-mod] getScore returning for '"
                        + ((Player) self).getName() + "'"))
                .and()
                .method("<init>")
                .atHead(self -> System.out.println("[hello-mod] constructor head, self=" + self))
                .atReturn(self -> System.out.println("[hello-mod] constructor done, self is a Player: "
                        + (self instanceof Player)))
                .and()
                .method("ticks")
                .atHead(self -> System.out.println("[hello-mod] static method, self=" + self));
    }

    @Override
    public void onLaunch() {
        System.out.println("[hello-mod] launched. Miracles are real.");
    }
}
