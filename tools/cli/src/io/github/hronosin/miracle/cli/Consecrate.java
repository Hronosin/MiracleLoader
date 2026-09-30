package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code miracle consecrate} (alias {@code install}): puts MiracleLoader into a Prism Launcher
 * instance as a custom component, the same way on Linux, macOS and Windows.
 *
 * <p>The loader jar goes into the instance's {@code libraries/}, a component into
 * {@code patches/io.github.hronosin.miracle.json} (its {@code mainClass} is
 * {@code Resurrection}, so the instance's Java needn't be 25), an entry into
 * {@code mmc-pack.json} (backed up first), and the MiracleToolChain library into the mods folder.
 */
final class Consecrate {

    static final String UID = "io.github.hronosin.miracle";
    private static final String MAIN_CLASS = "io.github.hronosin.miracle.Resurrection";
    private static final List<String> OTHER_LOADERS = List.of("net.fabricmc.fabric-loader", "org.quiltmc.quilt-loader",
            "net.neoforged", "net.minecraftforge", "com.mumfrey.liteloader");
    /** Example mods copied along with --examples, when they sit next to the toolchain (a checkout's build/). */
    private static final List<String> EXAMPLES = List.of("title-mod", "dirt-diamonds", "super-jump", "sprint-jump",
            "jump-counter", "hallelujah");

    private Consecrate() {
    }

    static int run(List<String> args, String prismOption) throws IOException {
        boolean list = args.remove("--list");
        boolean uninstall = args.remove("--uninstall");
        boolean examples = args.remove("--examples");
        Path prism = prismData(prismOption);
        if (list) {
            listInstances(prism);
            return 0;
        }
        if (args.isEmpty()) {
            listInstances(prism);
            throw new Miracle.Heresy("which instance? miracle consecrate \"Instance name\" (or --uninstall \"Instance name\")");
        }
        Path instance = findInstance(prism, String.join(" ", args));
        if (prismRunning()) {
            throw new Miracle.Heresy("Prism Launcher is running. Close it first: it rewrites mmc-pack.json on exit and would"
                    + " undo this.");
        }
        return uninstall ? uninstall(instance) : install(instance, examples);
    }

    // --- install ----------------------------------------------------------------------------

    private static int install(Path instance, boolean examples) throws IOException {
        Path packFile = instance.resolve("mmc-pack.json");
        Map<String, Object> pack = Json.obj(Json.parse(Files.readString(packFile)));
        List<Object> components = new ArrayList<>(Json.arr(pack.get("components")));
        String mc = "?";
        for (Object c : components) {
            String uid = Json.str(c, "uid");
            if ("net.minecraft".equals(uid)) {
                mc = Optional.ofNullable(Json.str(c, "version")).orElse(Optional.ofNullable(Json.str(c, "cachedVersion")).orElse("?"));
            }
            if (OTHER_LOADERS.contains(uid)) {
                throw new Miracle.Heresy(name(instance) + " already has " + uid + ". Two loaders both want to own mainClass."
                        + " Use a clean vanilla instance.");
            }
        }
        if (!mc.startsWith("26.")) {
            System.out.println("NOTE: Minecraft " + mc + " is obfuscated. Mods need a variant baked for exactly " + mc
                    + " (miracle bake does it when " + mc + " is in targets).");
            System.out.println("      MiracleLoader needs Java 25; if Prism starts this version with an older Java, it finds"
                    + " a Java 25 by itself and relaunches the game in it.");
        }
        String version = Miracle.VERSION;
        Path loader = Miracle.loaderJar();

        Path libraries = instance.resolve("libraries");
        Files.createDirectories(libraries);
        removeOldLoaders(libraries);
        String libFile = "miracle-loader-" + version + ".jar";
        Files.copy(loader, libraries.resolve(libFile), StandardCopyOption.REPLACE_EXISTING);

        Path patches = instance.resolve("patches");
        Files.createDirectories(patches);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("formatVersion", 1);
        patch.put("uid", UID);
        patch.put("name", "MiracleLoader");
        patch.put("version", version);
        patch.put("mainClass", MAIN_CLASS);
        patch.put("libraries", List.of(map("name", "io.github.hronosin:miracle-loader:" + version, "MMC-hint", "local")));
        patch.put("requires", List.of(map("uid", "net.minecraft")));
        patch.put("order", 10);
        Path patchFile = patches.resolve(UID + ".json");
        Files.writeString(patchFile, Json.write(patch));

        Path backup = instance.resolve("mmc-pack.json.bak");
        Files.copy(packFile, backup, StandardCopyOption.REPLACE_EXISTING);
        components.removeIf(c -> UID.equals(Json.str(c, "uid")));
        components.add(map("uid", UID, "cachedName", "MiracleLoader", "cachedVersion", version,
                "cachedRequires", List.of(map("uid", "net.minecraft"))));
        pack.put("components", components);
        Files.writeString(packFile, Json.write(pack));

        // Prism uses either "minecraft" or ".minecraft" as the game folder.
        Path game = Files.isDirectory(instance.resolve(".minecraft")) ? instance.resolve(".minecraft") : instance.resolve("minecraft");
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        List<String> copied = new ArrayList<>();
        try {
            Path lib = Miracle.toolchainJar();
            Files.copy(lib, mods.resolve("miracle-toolchain.jar"), StandardCopyOption.REPLACE_EXISTING);
            copied.add("miracle-toolchain.jar");
        } catch (Miracle.Heresy noLibrary) {
            System.out.println("NOTE: no MiracleToolChain library next to the toolchain, so none was copied; mods that"
                    + " depend on miracle-toolchain need it in " + mods + ".");
        }
        if (examples) {
            for (String e : EXAMPLES) {
                Path jar = loader.resolveSibling(e + ".jar");
                if (Files.isRegularFile(jar)) {
                    Files.copy(jar, mods.resolve(e + ".jar"), StandardCopyOption.REPLACE_EXISTING);
                    copied.add(e + ".jar");
                }
            }
        }

        System.out.println("MiracleLoader " + version + " consecrated in \"" + name(instance) + "\" (Minecraft " + mc + ").");
        System.out.println("  loader : " + libraries.resolve(libFile));
        System.out.println("  patch  : " + patchFile);
        System.out.println("  mods   : " + mods + (copied.isEmpty() ? "" : "   (" + String.join(", ", copied) + ")"));
        System.out.println("  backup : " + backup);
        System.out.println();
        System.out.println("Now open Prism, launch the instance and watch the log for [Miracle] lines. Amen.");
        return 0;
    }

    private static int uninstall(Path instance) throws IOException {
        Path packFile = instance.resolve("mmc-pack.json");
        Map<String, Object> pack = Json.obj(Json.parse(Files.readString(packFile)));
        List<Object> components = new ArrayList<>(Json.arr(pack.get("components")));
        boolean had = components.removeIf(c -> UID.equals(Json.str(c, "uid")));
        pack.put("components", components);
        Files.writeString(packFile, Json.write(pack));
        Files.deleteIfExists(instance.resolve("patches").resolve(UID + ".json"));
        removeOldLoaders(instance.resolve("libraries"));
        System.out.println(had ? "MiracleLoader removed from \"" + name(instance) + "\". Mods in the mods folder were left alone."
                : "\"" + name(instance) + "\" had no MiracleLoader; its leftovers, if any, are gone.");
        return 0;
    }

    private static void removeOldLoaders(Path libraries) throws IOException {
        if (!Files.isDirectory(libraries)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libraries, "miracle-loader-*.jar")) {
            for (Path p : ds) {
                Files.delete(p);
            }
        }
    }

    // --- finding Prism ----------------------------------------------------------------------

    /**
     * Prism Launcher's data folder: --prism, PRISM_DATA, or where Prism keeps it on this OS
     * (Flatpak or native on Linux, %APPDATA% on Windows, Application Support on macOS).
     */
    static Path prismData(String option) {
        String explicit = option != null ? option : System.getenv("PRISM_DATA");
        if (explicit != null && !explicit.isEmpty()) {
            Path p = Path.of(explicit);
            if (!Files.isDirectory(p.resolve("instances"))) {
                throw new Miracle.Heresy("no Prism Launcher data at " + p + " (it should have an instances folder)");
            }
            return p;
        }
        List<Path> candidates = candidates();
        for (Path c : candidates) {
            if (Files.isDirectory(c.resolve("instances"))) {
                return c;
            }
        }
        StringBuilder sb = new StringBuilder("Prism Launcher's data folder wasn't found. Looked in:");
        candidates.forEach(c -> sb.append("\n    ").append(c));
        sb.append("\n  Portable or elsewhere? Pass --prism \"folder\" or set PRISM_DATA (Prism shows it: Folders > Launcher Root).");
        throw new Miracle.Heresy(sb.toString());
    }

    static List<Path> candidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        List<Path> out = new ArrayList<>();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            out.add(appData != null ? Path.of(appData, "PrismLauncher") : Path.of(home, "AppData", "Roaming", "PrismLauncher"));
            out.add(Path.of(home, "scoop", "persist", "prismlauncher"));
        } else if (os.contains("mac")) {
            out.add(Path.of(home, "Library", "Application Support", "PrismLauncher"));
        } else {
            out.add(Path.of(home, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher"));
            String xdg = System.getenv("XDG_DATA_HOME");
            out.add(xdg != null && !xdg.isEmpty() ? Path.of(xdg, "PrismLauncher") : Path.of(home, ".local", "share", "PrismLauncher"));
        }
        return out;
    }

    /** By folder name, or by the name Prism shows (instance.cfg's name=), ignoring case. */
    static Path findInstance(Path prism, String name) throws IOException {
        Path byFolder = prism.resolve("instances").resolve(name);
        if (Files.isRegularFile(byFolder.resolve("mmc-pack.json"))) {
            return byFolder;
        }
        List<Path> matches = new ArrayList<>();
        for (Path i : instances(prism)) {
            if (i.getFileName().toString().equalsIgnoreCase(name) || displayName(i).equalsIgnoreCase(name)) {
                matches.add(i);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        listInstances(prism);
        throw new Miracle.Heresy(matches.isEmpty() ? "no instance called \"" + name + "\""
                : "\"" + name + "\" is the name of " + matches.size() + " instances; use the folder name");
    }

    static List<Path> instances(Path prism) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(prism.resolve("instances"))) {
            for (Path p : ds) {
                if (Files.isRegularFile(p.resolve("mmc-pack.json"))) {
                    out.add(p);
                }
            }
        }
        out.sort(null);
        return out;
    }

    static void listInstances(Path prism) throws IOException {
        System.out.println("Instances in " + prism.resolve("instances") + ":");
        for (Path i : instances(prism)) {
            String shown = displayName(i);
            boolean ours = Files.isRegularFile(i.resolve("patches").resolve(UID + ".json"));
            System.out.println("  " + name(i) + (shown.isEmpty() || shown.equals(name(i)) ? "" : "   (\"" + shown + "\")")
                    + (ours ? "   [consecrated]" : ""));
        }
    }

    private static String displayName(Path instance) {
        Path cfg = instance.resolve("instance.cfg");
        if (Files.isRegularFile(cfg)) {
            try {
                for (String line : Files.readAllLines(cfg)) {
                    if (line.startsWith("name=")) {
                        return line.substring(5).strip();
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // an odd instance.cfg only costs the pretty name
            }
        }
        return "";
    }

    private static String name(Path instance) {
        return instance.getFileName().toString();
    }

    /** Whether a Prism Launcher process is running, on any OS (Flatpak's included). */
    static boolean prismRunning() {
        long self = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses().anyMatch(p -> p.pid() != self && p.info().command()
                .map(c -> c.substring(Math.max(c.lastIndexOf('/'), c.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT)
                        .startsWith("prismlauncher"))
                .orElse(false));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
