package net.minecraft.client.main;

import net.minecraft.world.entity.player.Player;

/**
 * Stand-in for the real client entry point, so the loader can be tested without shipping
 * Mojang's jar. Same class name the real 26.x client uses.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("[FakeMinecraft] starting, args=" + String.join(" ", args));
        Player steve = new Player("Steve");
        steve.jumpFromGround();
        System.out.println("[FakeMinecraft] score=" + steve.getScore());
        Player nobody = new Player("");
        System.out.println("[FakeMinecraft] nobody score=" + nobody.getScore());
        System.out.println("[FakeMinecraft] ticks=" + Player.ticks());
        System.out.println("[FakeMinecraft] jumpPower=" + steve.getJumpPower());
        steve.damage(10f, "zombie");
        steve.explode();
        System.out.println("[FakeMinecraft] moved=" + steve.move(2L, 0.5, false, 'x'));
        System.out.println("[FakeMinecraft] motd=" + Player.motd());
        System.out.println("[FakeMinecraft] done");
    }
}
