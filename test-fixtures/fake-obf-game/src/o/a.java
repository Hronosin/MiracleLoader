package o;

/**
 * The fake Player as an obfuscator would ship it: same behaviour and output as
 * examples/fake-game's net.minecraft.world.entity.player.Player, scrambled names, and short names
 * reused across overloads (a(), a(float, String), a(long, double, boolean, char)) like the real
 * thing. It has no fly(), on purpose.
 */
public class a {
    private static int a;
    private final String b;

    public a(String var1) {
        this.b = var1;
        System.out.println("[FakeMinecraft] player created: '" + var1 + "'");
    }

    public void a() {
        System.out.print(b);
        System.out.println(" jumps");
        a++;
    }

    public int b() {
        if (b.isEmpty()) {
            return 0;
        }
        return 42;
    }

    public String c() {
        return b;
    }

    public static int d() {
        return a;
    }

    public float e() {
        return 0.42f;
    }

    public void a(float var1, String var2) {
        System.out.println("[FakeMinecraft] " + b + " takes " + var1 + " damage from " + var2);
    }

    public void f() {
        System.out.println("[FakeMinecraft] BOOM");
    }

    public double a(long var1, double var3, boolean var5, char var6) {
        double r = var1 + var3 + (var5 ? 1000 : 0);
        System.out.println("[FakeMinecraft] move " + var1 + " " + var3 + " " + var5 + " " + var6 + " -> " + r);
        return r;
    }

    public static String g() {
        return "vanilla";
    }

    public String i() {
        return new StringBuilder(c()).reverse().toString();
    }

    public String h() {
        return g() + "/" + c();
    }
}
