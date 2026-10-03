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

    public float getJumpPower() {
        return 0.42f;
    }

    public void damage(float amount, String source) {
        System.out.println("[FakeMinecraft] " + name + " takes " + amount + " damage from " + source);
    }

    public void explode() {
        System.out.println("[FakeMinecraft] BOOM");
    }

    /** Wide locals (long, double) and small ones (boolean, char) in one signature. */
    public double move(long dx, double dy, boolean fast, char tag) {
        double result = dx + dy + (fast ? 1000 : 0);
        System.out.println("[FakeMinecraft] move " + dx + " " + dy + " " + fast + " " + tag + " -> " + result);
        return result;
    }

    public static String motd() {
        return "vanilla";
    }

    /** Two calls (one static, one virtual) for RGCT redirects to replace. */
    public String title() {
        return motd() + "/" + getName();
    }

    /** Exists here but not in test-fixtures/fake-obf-game: something for miracle-bake to catch. */
    public void fly() {
        System.out.println("[FakeMinecraft] " + name + " flies");
    }
}
