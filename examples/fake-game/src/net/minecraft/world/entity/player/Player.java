package net.minecraft.world.entity.player;

/** A very small Steve. */
public class Player {
    private static int ticks;
    private final String name;

    public Player(String name) {
        this.name = name;
        System.out.println("[FakeMinecraft] player created: '" + name + "'");
    }

    public void jumpFromGround() {
        System.out.print(name);
        System.out.println(" jumps");
        ticks++;
    }

    /** Two return paths on purpose: RGCT must patch both. */
    public int getScore() {
        if (name.isEmpty()) {
            return 0;
        }
        return 42;
    }

    public String getName() {
        return name;
    }

    public static int ticks() {
        return ticks;
    }
}
