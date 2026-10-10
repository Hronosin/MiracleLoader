package io.github.hronosin.miracle.toolchain;

import io.github.hronosin.miracle.api.Mods;
import io.github.hronosin.miracle.rgct.Rgct;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * namespace (your mod id with {@code _} instead of {@code -}) for your own things.
 *
 * <p>Since 1.6 each mod's files are a pack of their own, right above vanilla's (and below every
 * pack a player adds), so they work the way a data pack's or a resource pack's do: what the game
 * merges across packs merges, with vanilla's and with other mods' (tags, unless
 * {@code "replace": true}, such as {@code data/minecraft/tags/block/mineable/pickaxe.json} or
 * {@code data/c/tags/item/ingots.json}; atlases; {@code sounds.json}; languages), and anything
 * else replaces the file below it (a vanilla recipe, model or texture).
 */
public class Scripture {

    static final String KEY = "scripture";

    protected Scripture() {
    }

    /** Adds your jar's {@code data/} and {@code assets/} to the game's built-in data and resource packs. */
    public static void reveal() {
        Faithful.join("Scripture.reveal", KEY, Boolean.TRUE);
    }

    /** Startup: notes the mod's jar; one hook for every mod's pack is installed by {@link #installPsalter}. */
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
        if (!namespaces.isEmpty()) {
            PSALTER.add(new Psalm(rgct.modId(), new Holy(jar), revealed));
        }
    }

    // --- each mod's files: a pack of its own, right above vanilla's ----------------------------------

    /** A mod with data/ or assets/ in its jar, and whether it has revealed them. */
    private record Psalm(String modId, Holy root, List<Boolean> revealed) {
    }

    private static final List<Psalm> PSALTER = new CopyOnWriteArrayList<>();

    /**
     * Startup, after every mod's install: one hook, in the library's name, that puts each mod's
     * pack right above vanilla's in every resource manager the game makes (resource packs, data
     * packs, /reload, the client's known packs). One hook for all, so mods never compete for the
     * same argument.
     */
    static void installPsalter(Rgct rgct) {
        if (PSALTER.isEmpty()) {
            return;
        }
        rgct.target("net.minecraft.server.packs.resources.MultiPackResourceManager")
                .method("<init>", "(Lnet/minecraft/server/packs/PackType;Ljava/util/List;)V")
                .interceptHead(ctx -> {
                    List<?> packs = (List<?>) ctx.arg(1);
                    List<?> with = withPsalms(packs);
                    if (with != packs) {
                        ctx.setArg(1, with);
                    }
                });
    }

    /** The packs, with each revealed mod's pack right after vanilla's (or first), in mod order. */
    @SuppressWarnings("unchecked")
    static List<?> withPsalms(List<?> packs) {
        List<PackResources> mine = new ArrayList<>();
        for (Psalm p : PSALTER) {
            if (!p.revealed().isEmpty()) {
                mine.add(new PathPackResources(new PackLocationInfo("miracle/" + p.modId(), Component.literal(p.modId()),
                        PackSource.BUILT_IN, Optional.empty()), p.root().get()));
            }
        }
        if (mine.isEmpty()) {
            return packs;
        }
        List<PackResources> out = new ArrayList<>((List<PackResources>) packs);
        int at = 0;
        for (int i = 0; i < out.size(); i++) {
            if ("vanilla".equals(out.get(i).packId())) {
                at = i + 1;
                break;
            }
        }
        out.addAll(at, mine);
        return out;
    }

    /** Namespaces under data/ and assets/. */
    static Set<String> namespaces(Path jar) {
        Set<String> out = new TreeSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            jf.stream().forEach(e -> {
                String[] parts = e.getName().split("/");
                if (parts.length >= 3 && (parts[0].equals("data") || parts[0].equals("assets")) && !parts[1].isEmpty()) {
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
