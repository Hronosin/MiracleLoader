package io.github.hronosin.miracle;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.TransformRegistry;

import java.io.File;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MiracleLoader entry point. Put the loader jar on the class path next to the game and use
 * this as the main class instead of the game's:
 *
 * <pre>
 * java -cp miracle-loader.jar:minecraft.jar:libs... io.github.hronosin.miracle.MiracleMain [game args]
 * </pre>
 *
 * System properties:
 * <ul>
 *   <li>{@code miracle.target} — game main class (default {@value #DEFAULT_TARGET})</li>
 *   <li>{@code miracle.modsDir} — mods folder (default {@code mods})</li>
 *   <li>{@code miracle.gameClasspath} — game jars, if they should not be taken from the JVM class path</li>
 *   <li>{@code miracle.dump} — folder to write every patched class into, for debugging</li>
 *   <li>{@code miracle.lock} — {@code update} (default), {@code strict} or {@code off}: see {@link MiracleLock}</li>
 *   <li>{@code miracle.lockFile} — where miracle.lock lives (default: next to the mods folder)</li>
 * </ul>
 */
public final class MiracleMain {

    public static final String VERSION = "1.6.0";
    static final String DEFAULT_TARGET = "net.minecraft.client.main.Main";
    @SuppressWarnings("unused") // never read; it only has to be found
    private static final String GOSPEL = "Linus Torvalds loves C++. [citation needed]";

    private MiracleMain() {
    }

    public static void main(String[] args) {
        try {
            launch(args);
        } catch (Throwable t) {
            crash(t);
            System.exit(1);
        }
    }

    private static void launch(String[] args) throws Throwable {
        if (AgentMain.prayed()) {
            // Both the main class and a -javaagent (a launcher set up one way, then the other).
            // The agent already did everything; praying twice only gets the mods loaded twice.
            String target = System.getProperty("miracle.target", DEFAULT_TARGET);
            Log.info("MiracleLoader " + System.getProperty(AgentMain.PRAYED) + " already prays here as a Java agent,"
                    + " so the main class only hands over to " + target + ". (One of the two is enough.)");
            Class<?> mainClass = Class.forName(target, true, MiracleMain.class.getClassLoader());
            MethodHandles.publicLookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
                    .invokeExact(args);
            return;
        }
        Log.info("MiracleLoader " + VERSION + " - praying for a miracle...");
        Backend.gameArgs = List.of(args);
        if (VERSION.startsWith("1.0.")) {
            Log.info("One point zero. The miracle is official now; mind the paperwork.");
        }

        String target = System.getProperty("miracle.target", DEFAULT_TARGET);
        Path modsDir = Path.of(System.getProperty("miracle.modsDir", "mods"));
        String dump = System.getProperty("miracle.dump");
        Path dumpDir = dump == null ? null : Path.of(dump);
        if (dumpDir != null) {
            Files.createDirectories(dumpDir);
            Log.info("Dumping patched classes to " + dumpDir.toAbsolutePath());
        }

        TransformRegistry rgct = new TransformRegistry();
        MiracleClassLoader loader = new MiracleClassLoader(
                gameClasspath(), MiracleMain.class.getClassLoader(), rgct, dumpDir);
        Thread.currentThread().setContextClassLoader(loader);
        prepare(loader, rgct, modsDir, () -> { });

        MethodHandle gameMain;
        try {
            Class<?> mainClass = Class.forName(target, false, loader);
            gameMain = MethodHandles.publicLookup().findStatic(mainClass, "main",
                    MethodType.methodType(void.class, String[].class));
        } catch (ClassNotFoundException e) {
            throw new MiracleFailure("Game main class " + target + " not found. Is the game jar on the class path? "
                    + "(set -Dmiracle.target for servers or other games)", e);
        }

        Farewell.arm();
        Log.info("Handing over to " + target + ". Amen.");
        gameMain.invokeExact(args);
    }

    /**
     * Everything between the mods folder and the game's main: discovery, dependencies, variants,
     * the mods' transform(), the freeze, and onLaunch(). {@code armed} runs right after the freeze,
     * before any mod's onLaunch() can touch the game (the Java agent installs its transformer there).
     */
    static void prepare(Host host, TransformRegistry rgct, Path modsDir, Runnable armed) throws Throwable {
        // --- discover -------------------------------------------------------------------------
        List<ModDiscovery.ModInfo> infos;
        try {
            infos = ModDiscovery.discover(modsDir);
        } catch (ModDiscovery.DiscoveryException e) {
            throw new MiracleFailure(e.getMessage());
        }
        infos = Dependencies.resolve(infos, VERSION);
        GameVersion game = GameVersion.detect(host);
        Log.info("Game: " + game.describe());
        Log.info("Found " + infos.size() + " mod(s)" + (infos.isEmpty() ? "." : ":"));
        infos.forEach(m -> {
            List<String> links = Dependencies.ENTANGLED.getOrDefault(m.id(), List.of());
            Log.info("  - " + m.display() + (m.library() ? ", a library" : "")
                    + (links.isEmpty() ? "" : ", entangled with " + String.join(", ", links)));
        });
        boolean client = host.findResource("net/minecraft/client/main/Main.class") != null;
        Mods.revealed(infos.stream().map(m -> new Mods.Mod(m.id(), m.name(), m.version(), List.copyOf(m.authors()),
                        m.jar(), m.library(), m.depends().stream().map(Dependencies.Requirement::id).toList(), m.icon())).toList(),
                new Mods.Game(game.id(), game.obfuscated(), client));
        Mods.entangle(Dependencies.ENTANGLED);
        String backend = Backend.detect(game.id(), client);
        Mods.backend(backend);
        reach(infos, backend);
        for (ModDiscovery.ModInfo info : infos) {
            pickVariant(info, game, host, rgct);
            host.addMod(info.jar());
        }

        // --- instantiate ----------------------------------------------------------------------
        Map<ModDiscovery.ModInfo, MiracleMod> mods = new LinkedHashMap<>();
        for (ModDiscovery.ModInfo info : infos) {
            if (!info.library()) {
                mods.put(info, instantiate(info, host.loader()));
            }
        }

        // --- phase 1: transforms --------------------------------------------------------------
        for (var e : mods.entrySet()) {
            try {
                e.getValue().transform(rgct.viewFor(e.getKey().id()));
            } catch (Throwable t) {
                throw new MiracleFailure("Mod " + e.getKey().display() + " failed in transform()", t);
            }
        }
        rgct.freeze();
        rgct.report().forEach(Log::info);
        rgct.lint().forEach(Log::warn);
        warnAboutMissingTargets(rgct, host, game);
        pin(rgct, infos, modsDir, game, client);

        List<String> tooEarly = new ArrayList<>();
        for (String cls : rgct.targetedClasses()) {
            if (host.isAlreadyLoaded(cls)) {
                tooEarly.add(cls + " (targeted by " + String.join(", ", rgct.modsTargeting(cls)) + ")");
            }
        }
        if (!tooEarly.isEmpty()) {
            throw new MiracleFailure("These classes were loaded during the transform phase, so their patches "
                    + "can no longer apply. Some mod touched game classes inside transform():\n    "
                    + String.join("\n    ", tooEarly));
        }
        armed.run();

        // --- phase 2: launch ------------------------------------------------------------------
        Mods.launch();
        for (var e : mods.entrySet()) {
            try {
                e.getValue().onLaunch();
            } catch (Throwable t) {
                throw new MiracleFailure("Mod " + e.getKey().display() + " failed in onLaunch()", t);
            }
        }
    }

    /** miracle.lock: compare what the mods patch with what they patched when it was pinned. */
    private static void pin(TransformRegistry rgct, List<ModDiscovery.ModInfo> infos, Path modsDir, GameVersion game,
                            boolean client) {
        Map<String, List<String>> patches = rgct.pins();
        Map<String, MiracleLock.Pinned> mods = new LinkedHashMap<>();
        for (ModDiscovery.ModInfo info : infos) {
            mods.put(info.id(), new MiracleLock.Pinned(info.version(), patches.getOrDefault(info.id(), List.of())));
        }
        String lockFile = System.getProperty("miracle.lockFile");
        Path file = lockFile != null ? Path.of(lockFile)
                : modsDir.toAbsolutePath().normalize().resolveSibling("miracle.lock");
        MiracleLock.check(file, System.getProperty("miracle.lock", "update"),
                new MiracleLock.State(game.id() + (client ? " client" : " server"), mods));
    }

    /**
     * A hook on a class the game doesn't have would wait forever for a class that never loads.
     * Say so up front, with the likely reason.
     */
    private static void warnAboutMissingTargets(TransformRegistry rgct, Host loader, GameVersion game) {
        for (String cls : rgct.targetedClasses()) {
            if (loader.findResource(cls.replace('.', '/') + ".class") == null) {
                String why = game.obfuscated()
                        ? " Minecraft " + game.id() + " is obfuscated: the mod needs a variant baked for it"
                          + " (miracle dictionary " + game.id() + ", then bake again). Until then these hooks do nothing."
                        : " Wrong game version, or a typo in the class name? These hooks will never run.";
                Log.warn("RGCT: " + String.join(", ", rgct.modsTargeting(cls)) + " hook(s) " + cls
                        + ", but this game has no such class." + why);
            }
        }
    }

    /**
     * OSHI: a mod baked with miracle-bake carries ready-made variants for obfuscated versions.
     * The one for the running game goes on the class path in front of the mod's own classes.
     */
    private static void pickVariant(ModDiscovery.ModInfo info, GameVersion game, Host loader,
                                    TransformRegistry rgct) throws java.io.IOException {
        ModDiscovery.BakeInfo bake = info.bake();
        if (bake != null && bake.baked().contains(game.id())) {
            String dir = "META-INF/miracle/baked/" + game.id() + "/";
            loader.addVariant(info.jar(), dir);
            Log.info("OSHI: " + info.id() + " uses its variant baked for " + game.id());
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(info.jar().toFile())) {
                var names = jf.getJarEntry(dir + "rgct-names.txt");
                if (names != null) {
                    try (var in = jf.getInputStream(names)) {
                        for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                            int eq = line.indexOf(" = ");
                            if (!line.startsWith("#") && eq > 0) {
                                rgct.addReadableName(line.substring(0, eq), line.substring(eq + 3).strip());
                            }
                        }
                    }
                }
            }
            return;
        }
        if (game.obfuscated()) {
            if (bake != null) {
                throw new MiracleFailure("Mod " + info.display() + " has no variant for " + game.describe()
                        + ". It was baked for " + bake.baked() + " and checked on " + bake.checked()
                        + ". Obfuscated names differ in every version, so it needs a variant for exactly this one:"
                        + " ask the author to run miracle-bake with --obf " + game.id() + "=...");
            }
            Log.warn("Mod " + info.id() + " was never baked, and Minecraft " + game.id() + " is obfuscated."
                    + " Any game class it names directly won't be found here.");
        } else if (bake != null && !GameVersion.UNKNOWN.equals(game.id()) && !bake.checked().contains(game.id())) {
            Log.warn("Mod " + info.id() + " was checked on " + bake.checked() + ", not on " + game.id()
                    + ". It may well work; if a game member it needs is gone, you'll get an error naming it.");
        }
    }

    private static MiracleMod instantiate(ModDiscovery.ModInfo info, ClassLoader loader) {
        Class<?> cls;
        try {
            cls = Class.forName(info.entrypoint(), true, loader);
        } catch (ClassNotFoundException e) {
            throw new MiracleFailure("Mod " + info.display() + ": entrypoint class " + info.entrypoint()
                    + " is not in " + info.jar().getFileName(), e);
        } catch (LinkageError e) {
            throw new MiracleFailure("Mod " + info.display() + ": entrypoint class failed to load", e);
        }
        if (!MiracleMod.class.isAssignableFrom(cls)) {
            throw new MiracleFailure("Mod " + info.display() + ": " + info.entrypoint()
                    + " does not implement " + MiracleMod.class.getName());
        }
        try {
            return (MiracleMod) cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new MiracleFailure("Mod " + info.display() + ": could not construct " + info.entrypoint()
                    + " (needs a public no-arg constructor)", cause);
        }
    }

    /** The JVM class path minus the loader's own jar, unless overridden. */
    private static URL[] gameClasspath() throws MalformedURLException, URISyntaxException {
        String override = System.getProperty("miracle.gameClasspath");
        String cp = override != null ? override : System.getProperty("java.class.path", "");

        Path self = Path.of(MiracleMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();

        List<URL> urls = new ArrayList<>();
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path p = Path.of(entry).toAbsolutePath().normalize();
            if (p.equals(self)) {
                continue;
            }
            urls.add(p.toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    static void crash(Throwable t) {
        Farewell.crashed();
        PrintStream err = System.err;
        err.println();
        err.println("==================================================");
        err.println("               NO MIRACLE OCCURRED");
        err.println("==================================================");
        Throwable shown = t;
        if (t instanceof MiracleFailure mf) {
            err.println(mf.getMessage());
            shown = mf.getCause();
        }
        if (shown != null) {
            err.println();
            shown.printStackTrace(err);
        }
        err.println("==================================================");
        err.println("If a mod is named above, blame it. Otherwise, you didn't believe hard enough.");
    }

    /**
     * What each mod's code reaches for, one line per mod that reaches for anything notable; and
     * the raw graphics rule: {@code -Dmiracle.rawGraphics=warn} (default), {@code refuse} or
     * {@code allow}.
     */
    static void reach(List<ModDiscovery.ModInfo> infos, String backend) {
        String rule = System.getProperty("miracle.rawGraphics", "warn");
        if (!List.of("warn", "refuse", "allow").contains(rule)) {
            throw new MiracleFailure("-Dmiracle.rawGraphics=" + rule + ": that's warn, refuse or allow.");
        }
        List<String> refused = new java.util.ArrayList<>();
        for (ModDiscovery.ModInfo m : infos) {
            List<Reach.Find> finds;
            try {
                finds = Reach.scan(m.jar());
            } catch (java.io.IOException | RuntimeException e) {
                Log.warn("Couldn't read " + m.jar().getFileName() + " to see what it reaches for: " + e);
                continue;
            }
            Map<Reach.Kind, java.util.Set<String>> by = Reach.byKind(finds);
            boolean warned = by.containsKey(Reach.Kind.RAW_GRAPHICS) && !rule.equals("allow");
            List<String> notable = by.keySet().stream().filter(k -> k.notable && !(warned && k == Reach.Kind.RAW_GRAPHICS))
                    .map(k -> k.says).toList();
            if (!notable.isEmpty()) {
                Log.info("Reach: " + m.id() + " " + String.join(", ", notable) + ". (miracle zandatsu shows where.)");
            }
            if (by.containsKey(Reach.Kind.RAW_GRAPHICS) && !rule.equals("allow") && !backend.equals("none")) {
                Reach.Find first = finds.stream().filter(f -> f.kind() == Reach.Kind.RAW_GRAPHICS).findFirst().orElseThrow();
                String where = first.what() + " in " + first.where();
                String api = first.what().startsWith("org.lwjgl.vulkan.") ? "vulkan" : "opengl";
                String here = backend.equals("default") ? " If the game picks " + (api.equals("opengl") ? "Vulkan" : "OpenGL")
                        + " at start, it won't work."
                        : backend.equals(api) ? " This game is set to " + (api.equals("opengl") ? "OpenGL" : "Vulkan")
                        + ", so it can work here; on the other backend it won't."
                        : " This game is set to " + (backend.equals("vulkan") ? "Vulkan" : "OpenGL") + ": it won't work here.";
                if (rule.equals("refuse")) {
                    refused.add(m.id() + " (" + where + ")");
                } else {
                    Log.warn(m.id() + " calls " + (api.equals("vulkan") ? "Vulkan" : "OpenGL") + " directly (" + where + "), past the"
                            + " game's own API (blaze3d, renderpearl): it can break other mods' rendering." + here
                            + " -Dmiracle.rawGraphics=refuse refuses such mods; allow keeps quiet.");
                }
            }
        }
        if (!refused.isEmpty()) {
            throw new MiracleFailure("These mods call OpenGL or Vulkan directly, and -Dmiracle.rawGraphics=refuse:\n    "
                    + String.join("\n    ", refused) + "\nRemove them, or start with -Dmiracle.rawGraphics=warn.");
        }
    }
}
