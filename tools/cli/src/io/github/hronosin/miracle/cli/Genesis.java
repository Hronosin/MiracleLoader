package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** {@code miracle genesis my-mod}: in the beginning there was nothing. Then there was a mod. */
final class Genesis {

    private Genesis() {
    }

    /** Starting points beyond the default, by name. Each one is a small, working mod. */
    static final java.util.Map<String, String> TEMPLATES = new java.util.LinkedHashMap<>();

    static {
        TEMPLATES.put("aura", "RWBY: an Aura shield that soaks hits until it breaks, then regenerates");
        TEMPLATES.put("stylish", "Devil May Cry: a style meter from D to SSS");
        TEMPLATES.put("zandatsu", "Metal Gear Rising: sneak-kills heal you, nanomachines cheat death");
        TEMPLATES.put("you-died", "Dark Souls: YOU DIED, a death tally, VICTORY ACHIEVED on bosses");
        TEMPLATES.put("grace", "Elden Ring: Rise, Tarnished; messages on the ground; GREAT ENEMY FELLED");
    }

    static void listTemplates() {
        System.out.println("Templates (miracle genesis <name> --template <template>):");
        System.out.println("  (none)      the default: a little of every part of the library");
        TEMPLATES.forEach((k, v) -> System.out.println("  " + k + " ".repeat(Math.max(1, 12 - k.length())) + v));
        System.out.println("  --ascetic   no library at all, just RGCT and you");
    }

    /**
     * {@code ascetic}: a bare RGCT mod, without the MiracleToolChain library. {@code template}:
     * one of {@link #TEMPLATES}, or null for the default.
     */
    static Path create(Path where, String name, String pkg, String minecraft, boolean ascetic, String template)
            throws IOException {
        if (template != null && !TEMPLATES.containsKey(template)) {
            throw new Miracle.Heresy("No template called '" + template + "'. There's " + String.join(", ", TEMPLATES.keySet())
                    + " (miracle genesis --templates describes them).");
        }
        if (template != null && ascetic) {
            throw new Miracle.Heresy("--ascetic and --template: the templates all use the library. Pick one.");
        }
        Path dir = where.resolve(name).toAbsolutePath().normalize();
        if (Files.exists(dir) && (!Files.isDirectory(dir) || Files.list(dir).findAny().isPresent())) {
            throw new Miracle.Heresy(dir + " already exists and isn't empty. Creation happens ex nihilo.");
        }
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-").replaceAll("^[^a-z]+", "")
                .replaceAll("[-_]+$", "");
        if (id.length() > 64) {
            id = id.substring(0, 64).replaceAll("[-_]+$", "");
        }
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
                """.formatted(id, title, pkg, cls, System.getProperty("user.name", "you"))
                + (ascetic ? "" : "depends = [\"miracle-toolchain>=0.2.0\"]\n"));

        write(dir.resolve(Project.PROJECT_FILE), """
                # What you write and compile against. Must be unobfuscated (26.1+), so names are readable.
                minecraft = "%s"

                # Versions miracle bake checks the mod against. Add obfuscated ones (e.g. "1.21.11") and
                # the mod gets a baked variant for each; fill any holes in fallback/<version>/src.
                targets = ["%s"]
                """.formatted(minecraft, minecraft));

        if (template != null) {
            write(src.resolve(cls + ".java"), template(template).replace("__PACKAGE__", pkg)
                    .replace("__CLASS__", cls).replace("__ID__", id));
        } else {
            write(src.resolve(cls + ".java"), (ascetic ? ASCETIC : BLESSED).formatted(pkg, cls, id));
        }

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

    /** The default: the whole MiracleToolChain library, whether you need it or not. */
    private static final String BLESSED = """
            package %1$s;

            import io.github.hronosin.miracle.api.MiracleMod;
            import io.github.hronosin.miracle.toolchain.Blessings;
            import io.github.hronosin.miracle.toolchain.Commandments;
            import io.github.hronosin.miracle.toolchain.Omens;
            import io.github.hronosin.miracle.toolchain.Sermons;
            import net.minecraft.commands.Commands;
            import net.minecraft.network.chat.Component;

            /**
             * Let there be mod. Everything happens in onLaunch(): at startup MiracleToolChain reads
             * this class, sees what it uses, and patches exactly that.
             */
            public final class %2$s implements MiracleMod {

                @Override
                public void onLaunch() {
                    // Settings: written to config/%3$s.toml the first time, with these defaults.
                    Commandments config = Commandments.mine();
                    double jump = config.number("jump_multiplier", 1.25, "How high players jump. 1 = vanilla.");

                    // Things that happen.
                    Omens.playerJoined(player -> player.sendSystemMessage(Component.literal("Let there be %3$s.")));

                    // Well-known values. multiply, not set: it stacks with other mods.
                    Blessings.jumpPower().forPlayers().multiply(jump);

                    // Commands: /%3$s
                    Sermons.preach(d -> d.register(Commands.literal("%3$s").executes(c -> {
                        Sermons.reply(c.getSource(), "Amen.");
                        return 1;
                    })));
                }
            }
            """;


    /** --ascetic: RGCT and nothing else. */
    private static final String ASCETIC = """
            package %1$s;

            import io.github.hronosin.miracle.api.MiracleMod;
            import io.github.hronosin.miracle.rgct.Rgct;
            import net.minecraft.world.entity.player.Player;

            /** Let there be mod. */
            public final class %2$s implements MiracleMod {

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
                    System.out.println("[%3$s] Let there be mod.");
                }
            }
            """;

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

    private static String template(String name) throws IOException {
        try (var in = Genesis.class.getResourceAsStream("/templates/" + name + ".java.txt")) {
            if (in == null) {
                throw new Miracle.Heresy("The template '" + name + "' is missing from this toolchain build. Rebuild it.");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
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
