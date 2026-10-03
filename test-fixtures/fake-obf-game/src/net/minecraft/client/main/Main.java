package net.minecraft.client.main;

import o.a;

/** examples/fake-game's Main, against the obfuscated Player. Entry points keep their names, as in the real game. */
public class Main {
    public static void main(String[] args) {
        System.out.println("[FakeMinecraft] starting, args=" + String.join(" ", args));
        a steve = new a("Steve");
        steve.a();
        System.out.println("[FakeMinecraft] score=" + steve.b());
        a nobody = new a("");
        System.out.println("[FakeMinecraft] nobody score=" + nobody.b());
        System.out.println("[FakeMinecraft] ticks=" + a.d());
        System.out.println("[FakeMinecraft] jumpPower=" + steve.e());
        steve.a(10f, "zombie");
        steve.f();
        System.out.println("[FakeMinecraft] moved=" + steve.a(2L, 0.5, false, 'x'));
        System.out.println("[FakeMinecraft] motd=" + a.g());
        System.out.println("[FakeMinecraft] title=" + steve.h());
        System.out.println("[FakeMinecraft] done");
    }
}
