package io.github.hronosin.miracle;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

/**
 * Where the game and the mods live: MiracleLoader's own class loader (when it's the main class)
 * or the JVM's class path (when it's a Java agent). Everything between finding the mods and
 * handing over to the game is the same for both.
 */
interface Host {

    /** The loader the game's and the mods' classes come from. */
    ClassLoader loader();

    /** A resource of the game or a mod (version.json, a class file), or null. */
    URL findResource(String name);

    /** Puts a mod jar where its classes can be loaded. */
    void addMod(Path jar) throws IOException;

    /** Puts a baked variant (a folder inside the mod's jar) in front of the mod's own classes. */
    void addVariant(Path jar, String dir) throws IOException;

    /** True if the class was already loaded, so patches for it can no longer apply. */
    boolean isAlreadyLoaded(String className);
}
