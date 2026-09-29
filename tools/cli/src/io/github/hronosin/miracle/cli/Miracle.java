package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MiracleToolChain's command line: {@code miracle}. Every command has a solemn name and a boring
 * alias, for people without a sense of humour and for scripts.
 */
public final class Miracle {

    static final String VERSION = "0.4.0";

    /** A user error: printed without a stack trace. */
    static final class Heresy extends RuntimeException {
        Heresy(String message) {
            super(message);
        }
    }

    private Miracle() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Heresy h) {
            System.err.println("HERESY: " + h.getMessage());
            code = 1;
        } catch (IOException e) {
            System.err.println("The heavens are silent: " + e.getMessage());
            code = 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = 130;
        }
        System.exit(code);
    }

    static int run(String[] args) throws IOException, InterruptedException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            help();
            return 0;
        }
        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        switch (args[0]) {
            case "genesis", "new" -> {
                if (rest.remove("--templates")) {
                    Genesis.listTemplates();
                    return 0;
                }
                boolean ascetic = rest.remove("--ascetic");
                String template = option(rest, "--template");
                String pkg = option(rest, "--package");
                String minecraft = option(rest, "--minecraft");
                String name = positional(rest, "genesis needs a name: miracle genesis my-mod");
                Genesis.create(Path.of(""), name, pkg, minecraft, ascetic, template);
                return 0;
            }
            case "classpath" -> {
                // Plumbing for build.sh: a Minecraft client jar and the libraries it compiles against.
                Mojang.Version v = Mojang.version(positional(rest, "classpath needs a Minecraft version"));
                List<Path> cp = new ArrayList<>();
                cp.add(Mojang.clientJar(v));
                cp.addAll(Mojang.compileLibraries(v));
                System.out.println(String.join(java.io.File.pathSeparator, cp.stream().map(Path::toString).toList()));
                return 0;
            }
            case "bake", "build" -> {
                Builder.build(Project.find(Path.of("")));
                return 0;
            }
            case "pray", "run" -> {
                List<String> extra = new ArrayList<>();
                int dashes = rest.indexOf("--");
                if (dashes >= 0) {
                    extra.addAll(rest.subList(dashes + 1, rest.size()));
                    rest = new ArrayList<>(rest.subList(0, dashes));
                }
                String version = option(rest, "--version");
                String username = option(rest, "--username");
                boolean noBuild = rest.remove("--no-build");
                boolean eula = rest.remove("--eula");
                String side = positional(rest, "pray for what? miracle pray client, or miracle pray server");
                return Runner.pray(Project.find(Path.of("")), new Runner.Options(side, version, !noBuild, eula,
                        username != null ? username : "Pilgrim", extra));
            }
            case "ascend", "publish" -> {
                return Ascend.run(Path.of(""), rest);
            }
            case "confess", "doctor" -> {
                return Confess.run();
            }
            case "dictionary", "mappings" -> {
                return Dictionaries.run(rest);
            }
            case "bonfire", "backup" -> {
                return Bonfire.run(Path.of(""), rest, Bonfire.Voice.BONFIRE);
            }
            case "grace" -> {
                return Bonfire.run(Path.of(""), rest, Bonfire.Voice.GRACE);
            }
            case "scribe", "assets" -> {
                boolean force = rest.remove("--force");
                String title = option(rest, "--title");
                boolean noEgg = rest.remove("--no-egg");
                String egg = noEgg ? "" : option(rest, "--egg");
                return Scribe.run(Path.of(""), rest, title, egg, force);
            }
            case "messages", "todo" -> {
                return Messages.run(Path.of(""));
            }
            case "zandatsu", "inspect" -> {
                return Zandatsu.run(Path.of(""), rest.isEmpty() ? null : rest.getFirst());
            }
            case "gradle" -> {
                return Rituals.gradle(rest.remove("--really"));
            }
            case "forge", "neoforge", "fabric", "loom" -> {
                return Rituals.forge(args[0]);
            }
            case "heresy" -> {
                return Rituals.heresy(Path.of(""));
            }
            case "fast" -> {
                String secs = option(rest, "--seconds");
                if (secs != null && !secs.matches("\\d{1,6}")) {
                    throw new Heresy("--seconds wants a whole number of seconds, not '" + secs + "'");
                }
                return Rituals.fast(secs == null ? 40 : Integer.parseInt(secs));
            }
            case "tithe" -> {
                return Rituals.tithe();
            }
            case "exorcise", "clean" -> {
                return Rituals.exorcise(Path.of(""), rest.remove("--yes"));
            }
            case "--version", "version" -> {
                System.out.println("MiracleToolChain " + VERSION);
                return 0;
            }
            default -> throw new Heresy("'" + args[0] + "' is not in the scripture. Try: miracle help");
        }
    }

    private static void help() {
        System.out.println("""
                MiracleToolChain %s: Forge hammers, Fabric stitches, we just pray.

                  miracle genesis <name>          (new)     create a mod project. Let there be mod.
                      --package com.you.mod  --minecraft 26.2
                      --ascetic           no MiracleToolChain library, just RGCT and you
                      --template aura     start from a template: aura (RWBY), stylish (DMC),
                                          zandatsu (MGR:R), you-died (Dark Souls), grace (Elden Ring)
                      --templates         describe them
                  miracle bake                    (build)   compile, check against every target, bake
                  miracle pray client|server      (run)     bake, then play it with MiracleLoader
                      --version 1.21.11   run a target version (uses its baked variant)
                      --username Name     offline name for the client (default: Pilgrim)
                      --eula              accept Mojang's EULA for the server (theirs is real)
                      --no-build          don't bake first
                      -- ...              anything after this goes to the game
                  miracle ascend modrinth|github  (publish) bake, then publish the jar as a new version
                      --dry-run           show what would go up, send nothing
                      -m "text" | --notes CHANGES.md   the changelog   --type release|beta|alpha
                      --project slug      Modrinth project (else modrinth = "..." in miracle.project.toml)
                      --repo you/mod      GitHub repository (else github = "...")  --tag v1.0  --draft
                      tokens: MODRINTH_TOKEN; GITHUB_TOKEN, GH_TOKEN or gh auth login
                  miracle confess                 (doctor)  list what's wrong with your setup, and your Aura
                  miracle dictionary <v|26.*|>=1.21.11|latest>...  (mappings)  fetch what baking needs; --list
                  miracle bonfire [list|rest [name]]  (backup)  checkpoint the worlds in run/; rest to go back
                  miracle grace ...                         the same, for the Tarnished
                  miracle scribe item|block|entity <name>  (assets) models, placeholder texture, names, loot table
                      --title "Holy Wafer"   the English name   --force   overwrite
                      --egg zombie           entity: borrow a vanilla spawn egg's look instead of a placeholder
                      --no-egg               entity: no spawn egg, no loot (a projectile, say)
                  miracle messages                (todo)    TODO/FIXME/HACK/XXX, as messages on the ground
                  miracle zandatsu [jar]          (inspect) cut a mod jar open: patches, library use, bakes
                  miracle exorcise [--yes]        (clean)   cast out build/, logs, crash reports (worlds spared)

                Rituals nobody needs:
                  miracle gradle [--really]       a faithful reenactment of a Gradle build. Builds nothing.
                  miracle forge                   did you mean: miracle?
                  miracle heresy                  find Forge/Fabric/Mixin imports, assign penance
                  miracle fast [--seconds 40]     the Great Lent of the build: do nothing, with feeling
                  miracle tithe                   offer 10%% of your cache to the heavens (deletes nothing)

                Downloads are cached in ~/.cache/miracle ($MIRACLE_HOME).""".formatted(VERSION));
    }

    private static String option(List<String> args, String name) {
        int i = args.indexOf(name);
        if (i < 0) {
            return null;
        }
        if (i + 1 >= args.size()) {
            throw new Heresy(name + " needs a value");
        }
        String v = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        return v;
    }

    private static String positional(List<String> args, String error) {
        for (String a : args) {
            if (!a.startsWith("--")) {
                args.remove(a);
                return a;
            }
        }
        throw new Heresy(error);
    }

    /** MiracleLoader's jar: next to the toolchain's own jar, or wherever -Dmiracle.loaderJar says. */
    static Path loaderJar() {
        return sibling("miracle-loader.jar", "miracle.loaderJar", "MIRACLE_LOADER_JAR",
                "MiracleLoader not found at %s (set -Dmiracle.loaderJar=...)");
    }

    /** The MiracleToolChain library jar, for mods that depend on miracle-toolchain. */
    static Path toolchainJar() {
        return sibling("miracle-toolchain.jar", "miracle.toolchainJar", "MIRACLE_TOOLCHAIN_JAR",
                "The MiracleToolChain library isn't at %s. It comes in the release zip; in a checkout,"
                        + " ./build.sh builds it once it can find a Minecraft 26.x (run 'miracle pray client'"
                        + " in any project once, or set MC_JAR). Or point -Dmiracle.toolchainJar=... at it.");
    }

    private static Path sibling(String fileName, String property, String env, String missing) {
        String prop = System.getProperty(property, System.getenv(env));
        Path jar;
        if (prop != null) {
            jar = Path.of(prop);
        } else {
            try {
                Path self = Path.of(Miracle.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                jar = self.resolveSibling(fileName);
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        if (!Files.isRegularFile(jar)) {
            throw new Heresy(missing.formatted(jar));
        }
        return jar.toAbsolutePath();
    }

    static String javaExecutable() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }
}
