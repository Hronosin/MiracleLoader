package io.github.hronosin.miracle;

import io.github.hronosin.miracle.api.MiracleMod;
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
 * </ul>
 */
public final class MiracleMain {

    public static final String VERSION = "0.1.0-mvp";
    static final String DEFAULT_TARGET = "net.minecraft.client.main.Main";

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
        Log.info("MiracleLoader " + VERSION + " - praying for a miracle...");

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

        // --- discover -------------------------------------------------------------------------
        List<ModDiscovery.ModInfo> infos;
        try {
            infos = ModDiscovery.discover(modsDir);
        } catch (ModDiscovery.DiscoveryException e) {
            throw new MiracleFailure(e.getMessage());
        }
        for (ModDiscovery.ModInfo info : infos) {
            loader.addJar(info.jar());
        }
        Log.info("Found " + infos.size() + " mod(s)" + (infos.isEmpty() ? "." : ":"));
        infos.forEach(m -> Log.info("  - " + m.display()));

        Thread.currentThread().setContextClassLoader(loader);

        // --- instantiate ----------------------------------------------------------------------
        Map<ModDiscovery.ModInfo, MiracleMod> mods = new LinkedHashMap<>();
        for (ModDiscovery.ModInfo info : infos) {
            mods.put(info, instantiate(info, loader));
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

        List<String> tooEarly = new ArrayList<>();
        for (String cls : rgct.targetedClasses()) {
            if (loader.isAlreadyLoaded(cls)) {
                tooEarly.add(cls + " (targeted by " + String.join(", ", rgct.modsTargeting(cls)) + ")");
            }
        }
        if (!tooEarly.isEmpty()) {
            throw new MiracleFailure("These classes were loaded during the transform phase, so their patches "
                    + "can no longer apply. Some mod touched game classes inside transform():\n    "
                    + String.join("\n    ", tooEarly));
        }

        // --- phase 2: launch ------------------------------------------------------------------
        for (var e : mods.entrySet()) {
            try {
                e.getValue().onLaunch();
            } catch (Throwable t) {
                throw new MiracleFailure("Mod " + e.getKey().display() + " failed in onLaunch()", t);
            }
        }

        MethodHandle gameMain;
        try {
            Class<?> mainClass = Class.forName(target, false, loader);
            gameMain = MethodHandles.publicLookup().findStatic(mainClass, "main",
                    MethodType.methodType(void.class, String[].class));
        } catch (ClassNotFoundException e) {
            throw new MiracleFailure("Game main class " + target + " not found. Is the game jar on the class path? "
                    + "(set -Dmiracle.target for servers or other games)", e);
        }

        Log.info("Handing over to " + target + ". Amen.");
        gameMain.invokeExact(args);
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

    private static void crash(Throwable t) {
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
}
