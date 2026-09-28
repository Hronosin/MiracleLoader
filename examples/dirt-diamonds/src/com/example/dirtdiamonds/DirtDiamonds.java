package com.example.dirtdiamonds;

import io.github.hronosin.miracle.api.MiracleMod;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Classic: craft diamonds out of dirt.
 *
 * <p>Recipes are data, not code, so the mod doesn't build any recipe objects. It ships an
 * ordinary {@code data/dirt_diamonds/recipe/*.json} inside its own jar and, right before the
 * game assembles its built-in "vanilla" pack, adds the jar as one more root of that pack.
 * The game then finds the recipe exactly like its own.
 *
 * <p>Compiled against the real client jar: {@code javac -cp miracle-loader.jar:minecraft-26.2-client.jar}.
 */
public final class DirtDiamonds implements MiracleMod {

    static final String NAMESPACE = "dirt_diamonds";

    private static Path modRoot;

    @Override
    public void transform(Rgct rgct) {
        // VanillaPackResourcesBuilder builds both the vanilla data pack (server) and the vanilla
        // resource pack (client). Adding our root to both is harmless: we only have data/.
        rgct.target("net.minecraft.server.packs.VanillaPackResourcesBuilder")
                .method("build")
                .atHead(self -> addOurData((VanillaPackResourcesBuilder) self));
    }

    private static void addOurData(VanillaPackResourcesBuilder builder) {
        builder.pushUniversalPath(root()).exposeNamespace(NAMESPACE);
        System.out.println("[dirt-diamonds] Recipes smuggled into the vanilla pack. Dirt is now a currency.");
    }

    /** The mod's own jar, opened as a file system, so the game can read it like a folder. */
    private static synchronized Path root() {
        if (modRoot == null) {
            try {
                Path jar = Path.of(DirtDiamonds.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.isDirectory(jar)) {
                    modRoot = jar; // running from exploded classes during development
                } else {
                    FileSystem fs = FileSystems.newFileSystem(jar); // stays open for the game's lifetime
                    modRoot = fs.getPath("/");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        return modRoot;
    }
}
