package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** {@code miracle genesis my-mod}: in the beginning there was nothing. Then there was a mod. */
final class Genesis {

    private Genesis() {
    }

    static Path create(Path where, String name, String pkg, String minecraft) throws IOException {
        Path dir = where.resolve(name).toAbsolutePath().normalize();
        if (Files.exists(dir) && (!Files.isDirectory(dir) || Files.list(dir).findAny().isPresent())) {
            throw new Miracle.Heresy(dir + " already exists and isn't empty. Creation happens ex nihilo.");
        }
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-").replaceAll("^[^a-z]+", "");
        if (id.length() < 2) {
            throw new Miracle.Heresy("'" + name + "' makes a poor mod id: use at least two letters.");
        }
        String cls = camel(id);
        String title = String.join(" ", java.util.Arrays.stream(id.split("[-_]"))
                .filter(w -> !w.isEmpty()).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).toList());
        if (pkg == null) {
            pkg = "com.example." + id.replaceAll("[^a-z0-9]", "");
        }
        if (minecraft == null) {
            minecraft = defaultMinecraft();
        }

        Path src = dir.resolve("src").resolve(pkg.replace('.', '/'));
        Files.createDirectories(src);
        Files.createDirectories(dir.resolve("resources"));
        Files.createDirectories(dir.resolve("fallback"));

        write(dir.resolve(Project.MOD_FILE), """
                id = "%s"
                name = "%s"
                version = "0.1.0"
                entrypoint = "%s.%s"
                authors = ["%s"]
                """.formatted(id, title, pkg, cls, System.getProperty("user.name", "you")));

        write(dir.resolve(Project.PROJECT_FILE), """
                # What you write and compile against. Must be unobfuscated (26.1+), so names are readable.
                minecraft = "%s"

                # Versions miracle bake checks the mod against. Add obfuscated ones (e.g. "1.21.11") and
                # the mod gets a baked variant for each; fill any holes in fallback/<version>/src.
                targets = ["%s"]
                """.formatted(minecraft, minecraft));

        write(src.resolve(cls + ".java"), """
                package %s;

                import io.github.hronosin.miracle.api.MiracleMod;
                import io.github.hronosin.miracle.rgct.Rgct;
                import net.minecraft.world.entity.player.Player;

                /** Let there be mod. */
                public final class %s implements MiracleMod {

                    @Override
                    public void transform(Rgct rgct) {
                        // Players jump a quarter higher. multiply, not set: it stacks with other mods.
                        rgct.target("net.minecraft.world.entity.LivingEntity")
                                .method("getJumpPower", "()F")
                                .interceptReturn(ctx -> {
                                    if (ctx.self() instanceof Player) {
                                        ctx.multiplyReturnValue(1.25f);
                                    }
                                });
                    }

                    @Override
                    public void onLaunch() {
                        System.out.println("[%s] Let there be mod.");
                    }
                }
                """.formatted(pkg, cls, id));

        write(dir.resolve(".gitignore"), "build/\nrun/\n");
        write(dir.resolve("fallback/README.md"), """
                # Fallbacks

                When `miracle bake` reports a hole for a target version, put the one method that must
                differ there into `fallback/<version>/src/`, as a partial class with the same name.
                See MiracleLoader's README, "Fallback functions".
                """);
        write(dir.resolve("README.md"), """
                # %s

                A MiracleLoader mod.

                    miracle bake            # compile, check against every target, bake
                    miracle pray client     # play it (offline, singleplayer)
                    miracle pray server     # host it
                """.formatted(title));

        System.out.println("In the beginning there was nothing. Then there was " + title + ".");
        System.out.println("  " + where.toAbsolutePath().normalize().relativize(dir) + "/  (" + id
                + ", Minecraft " + minecraft + ")");
        System.out.println("Next: cd " + name + " && miracle pray client");
        return dir;
    }

    private static String defaultMinecraft() {
        try {
            String latest = Mojang.latestRelease();
            if (!Mojang.version(latest).obfuscated()) {
                return latest;
            }
        } catch (IOException e) {
            System.out.println("(couldn't ask Mojang for the latest version: " + e.getMessage() + ")");
        }
        return "26.2";
    }

    private static String camel(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("[-_]")) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
