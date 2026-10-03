package io.github.hronosin.miracle;

import io.github.hronosin.miracle.rgct.TransformRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * MiracleLoader as a Java agent: {@code -javaagent:miracle-loader.jar} next to the game's own main
 * class, nothing else changed. The launcher starts Minecraft as usual; before the game's main runs,
 * this finds the mods, puts them on the class path and patches game classes as they load.
 *
 * <p>{@code -javaagent:miracle-loader.jar=<folder>} takes the mods from that folder instead of
 * {@code mods}. The same system properties as {@link MiracleMain} apply, except
 * {@code miracle.target} and {@code miracle.gameClasspath}: here the game is whatever the JVM runs.
 * Reached through {@code Agent}, which any Java can load and which checks the version first.
 */
public final class AgentMain {

    private AgentMain() {
    }

    public static void premain(String args, Instrumentation inst) {
        try {
            Log.info("MiracleLoader " + MiracleMain.VERSION + " - praying for a miracle, as a Java agent...");
            String folder = args != null && !args.isBlank() ? args.strip() : System.getProperty("miracle.modsDir", "mods");
            String dump = System.getProperty("miracle.dump");
            Path dumpDir = dump == null ? null : Path.of(dump);
            if (dumpDir != null) {
                Files.createDirectories(dumpDir);
                Log.info("Dumping patched classes to " + dumpDir.toAbsolutePath());
            }
            TransformRegistry rgct = new TransformRegistry();
            Host host = new AgentHost(inst);
            // Mods' redirect lambdas are relinked as their classes load, before transform() runs.
            inst.addTransformer(new Relinker());
            MiracleMain.prepare(host, rgct, Path.of(folder), () -> inst.addTransformer(new Patcher(rgct, dumpDir)));
            // As MiracleMain would: the game's main dying of an exception gets the crash banner,
            // with the blame a hook's exception carries.
            Thread main = Thread.currentThread();
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
                if (thread == main) {
                    MiracleMain.crash(t);
                    System.exit(1);
                } else if (previous != null) {
                    previous.uncaughtException(thread, t);
                } else {
                    System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                    t.printStackTrace();
                }
            });
            Log.info("Handing over to the game's own main. Amen.");
        } catch (Throwable t) {
            MiracleMain.crash(t);
            System.exit(1);
        }
    }

    /** Mod classes, from the start: Redirect lambdas written down by name instead of linked. */
    private static final class Relinker implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String internalName, Class<?> redefining, ProtectionDomain domain,
                                byte[] bytes) {
            if (loader == null || internalName == null || redefining != null) {
                return null;
            }
            try {
                byte[] relinked = TransformRegistry.relink(bytes, loader);
                return relinked == bytes ? null : relinked;
            } catch (Throwable t) {
                MiracleMain.crash(new MiracleFailure("RGCT could not read the redirects in " + internalName.replace('/', '.'), t));
                Runtime.getRuntime().halt(1);
                return null;
            }
        }
    }

    /** Game classes come from the JVM's class path; mods are appended to it. */
    private static final class AgentHost implements Host {
        private final Instrumentation inst;

        AgentHost(Instrumentation inst) {
            this.inst = inst;
        }

        @Override
        public ClassLoader loader() {
            return ClassLoader.getSystemClassLoader();
        }

        @Override
        public URL findResource(String name) {
            return loader().getResource(name);
        }

        @Override
        public void addMod(Path jar) throws IOException {
            inst.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));
        }

        /**
         * The class path takes whole jars only, so the variant's folder is copied into a jar of its
         * own (deleted when the game exits), appended before the mod's.
         */
        @Override
        public void addVariant(Path jar, String dir) throws IOException {
            Path variant = Files.createTempFile("miracle-variant-", ".jar");
            variant.toFile().deleteOnExit();
            try (JarFile in = new JarFile(jar.toFile());
                 JarOutputStream out = new JarOutputStream(Files.newOutputStream(variant))) {
                Enumeration<JarEntry> entries = in.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith(dir) || e.getName().length() == dir.length()) {
                        continue;
                    }
                    out.putNextEntry(new JarEntry(e.getName().substring(dir.length())));
                    try (InputStream is = in.getInputStream(e)) {
                        is.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
            inst.appendToSystemClassLoaderSearch(new JarFile(variant.toFile()));
            io.github.hronosin.miracle.api.Mods.standIn(variant, jar);
        }

        @Override
        public boolean isAlreadyLoaded(String className) {
            Set<String> loaded = new HashSet<>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                loaded.add(c.getName());
            }
            return loaded.contains(className);
        }
    }

    /**
     * RGCT on every class the JVM loads outside the JDK. The JVM ignores a transformer's exceptions
     * and would load the class unpatched, so a failure here stops the game, loudly.
     */
    private static final class Patcher implements ClassFileTransformer {
        private final TransformRegistry rgct;
        private final Path dumpDir;

        Patcher(TransformRegistry rgct, Path dumpDir) {
            this.rgct = rgct;
            this.dumpDir = dumpDir;
        }

        @Override
        public byte[] transform(ClassLoader loader, String internalName, Class<?> redefining, ProtectionDomain domain,
                                byte[] bytes) {
            if (loader == null || internalName == null || redefining != null) {
                return null;
            }
            String name = internalName.replace('/', '.');
            try {
                byte[] patched = rgct.transform(name, bytes, loader);
                if (patched == bytes) {
                    return null;
                }
                if (dumpDir != null) {
                    Path out = dumpDir.resolve(internalName + ".class");
                    Files.createDirectories(out.getParent());
                    Files.write(out, patched);
                }
                return patched;
            } catch (Throwable t) {
                MiracleMain.crash(new MiracleFailure("RGCT could not transform " + name
                        + " (patched by: " + String.join(", ", rgct.modsTargeting(name)) + ")", t));
                Runtime.getRuntime().halt(1);
                return null;
            }
        }
    }
}
