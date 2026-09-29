package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

/**
 * Scripture: the {@code data/} and {@code assets/} folders inside your mod's jar, revealed to the
 * game as if they were its own. Known as {@link Resources} to the secular.
 *
 * <pre>{@code
 * // resources/data/my_mod/recipe/dirt_to_diamond.json   -> a recipe
 * // resources/assets/my_mod/lang/en_us.json             -> translations
 * public void onLaunch() {
 *     Scripture.reveal();
 * }
 * }</pre>
 *
 * <p>Recipes, loot tables, tags, advancements, structures (data), and textures, models, sounds,
 * translations (assets): anything the game reads from a data pack or a resource pack. Your
 * files join the game's built-in packs, so they're always on and need no pack menu. Use your own
 * namespace (your mod id with {@code _} instead of {@code -}); replacing vanilla's own files
 * this way is not promised to work.
 */
public class Scripture {

    static final String KEY = "scripture";

    protected Scripture() {
    }

    /** Adds your jar's {@code data/} and {@code assets/} to the game's built-in data and resource packs. */
    public static void reveal() {
        Faithful.join("Scripture.reveal", KEY, Boolean.TRUE);
    }

    /** Startup: the pack hook, which adds the jar once the mod has called reveal(). */
    static void install(Rgct rgct, boolean revealNow) {
        Path jar = Mods.of(rgct.modId()).jar();
        Set<String> namespaces = namespaces(jar);
        if (namespaces.isEmpty() && !revealNow) {
            io.github.hronosin.miracle.Log.warn("MiracleToolChain: " + rgct.modId() + " reveals its scripture, but "
                    + jar.getFileName() + " has no data/<namespace>/ or assets/<namespace>/ folders. Nothing to reveal.");
        }
        Faithful.foresee(rgct.modId(), KEY);
        List<Boolean> revealed = Faithful.list(rgct.modId(), KEY);
        if (revealNow) {
            revealed.add(Boolean.TRUE);
        }
        String[] exposed = namespaces.toArray(String[]::new);
        Holy root = new Holy(jar);
        rgct.target("net.minecraft.server.packs.VanillaPackResourcesBuilder")
                .method("build", "(Lnet/minecraft/server/packs/PackLocationInfo;)"
                        + "Lnet/minecraft/server/packs/VanillaPackResources;")
                .atHead(self -> {
                    if (!revealed.isEmpty() && exposed.length > 0) {
                        ((VanillaPackResourcesBuilder) self).pushUniversalPath(root.get()).exposeNamespace(exposed);
                    }
                });
    }

    /** Namespaces under data/ and assets/, minus minecraft's own (vanilla exposes that already). */
    static Set<String> namespaces(Path jar) {
        Set<String> out = new TreeSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            jf.stream().forEach(e -> {
                String[] parts = e.getName().split("/");
                if (parts.length >= 3 && (parts[0].equals("data") || parts[0].equals("assets"))
                        && !parts[1].equals("minecraft") && !parts[1].isEmpty()) {
                    out.add(parts[1]);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The jar, opened as a file system the first time the game asks, and kept open. */
    private static final class Holy {
        private final Path jar;
        private Path root;

        Holy(Path jar) {
            this.jar = jar;
        }

        synchronized Path get() {
            if (root == null) {
                try {
                    FileSystem fs = FileSystems.newFileSystem(jar);
                    root = fs.getPath("/");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return root;
        }
    }
}
