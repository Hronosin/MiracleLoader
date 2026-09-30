import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * MiracleLoader's build. No Gradle, no Maven, no Loom: javac and jar, as promised, and the same
 * on Linux, macOS and Windows. Run it from the repository root with Java 25:
 *
 * <pre>
 *   java tools/build/Build.java        (build.sh and build.cmd do exactly this)
 * </pre>
 *
 * <p>Environment: {@code JAVA_HOME} (which JDK), {@code MC_JAR} + {@code MC_LIBS} (a Minecraft
 * 26.x client and the folder with exactly its libraries), {@code PRISM_DATA} (Prism Launcher's
 * data folder, if it isn't in the usual place), {@code MIRACLE_TARGETS} (versions to bake for;
 * default {@code 1.21.11 26.*}), {@code MIRACLE_OFFLINE=1} (use only cached dictionaries),
 * {@code MIRACLE_HOME} and {@code MIRACLE_DICTIONARIES} (where the caches are).
 *
 * <p>Written in Java 11's syntax on purpose, so that an older JDK can still run it far enough to
 * say which JDK is needed.
 */
public final class Build {

    static final int NEEDED = 25;
    static final Path OUT = Paths.get("build");
    static final String SEP = File.pathSeparator;
    static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    static final boolean WINDOWS = OS.contains("win");
    static final boolean MAC = OS.contains("mac");
    /** Scripture nobody asked for, in the places nobody looks (the third copy is in MiracleMain). */
    static final String GOSPEL = "Linus Torvalds loves C++. [citation needed]";
    static final List<String> REAL_MODS = List.of("dirt-diamonds", "super-jump", "sprint-jump", "jump-counter", "hallelujah");

    static javax.tools.JavaCompiler javac;
    static java.util.spi.ToolProvider jar;
    static Path dicts;
    static Path testDicts;

    /** A build that can't go on; the message says why. */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }

    private Build() {
    }

    public static void main(String[] args) {
        try {
            checkJdk();
            if (!Files.isDirectory(Paths.get("loader")) || !Files.isDirectory(Paths.get("toolchain"))) {
                throw new Failure("Run the build from MiracleLoader's folder (the one with build.sh and build.cmd).");
            }
            build();
        } catch (Failure f) {
            System.err.println(f.getMessage());
            System.exit(1);
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Build failed: " + e);
            if (WINDOWS) {
                System.err.println("(On Windows this is often a file still open in a running game or an Explorer window.)");
            }
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(130);
        }
    }

    static void checkJdk() {
        int feature = Runtime.version().feature();
        javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (feature < NEEDED || javac == null) {
            String found = "Java " + feature + " at " + System.getProperty("java.home")
                    + (javac == null ? " (a JRE: it has no javac)" : "");
            throw new Failure("Need a JDK " + NEEDED + " or newer (the ClassFile API and Minecraft 26.x both want it), found: "
                    + found + ".\n" + installHint()
                    + "\nWith several JDKs installed, point JAVA_HOME at the new one.");
        }
        jar = java.util.spi.ToolProvider.findFirst("jar")
                .orElseThrow(() -> new Failure("This JDK has no jar tool. Install a full JDK " + NEEDED + "."));
    }

    static String installHint() {
        if (WINDOWS) {
            return "Windows: winget install EclipseAdoptium.Temurin." + NEEDED + ".JDK (or the installer from adoptium.net).";
        }
        if (MAC) {
            return "macOS: brew install --cask temurin (or the installer from adoptium.net).";
        }
        return "Fedora: sudo dnf install java-" + NEEDED + "-openjdk-devel; Debian/Ubuntu: sudo apt install openjdk-"
                + NEEDED + "-jdk; or adoptium.net.";
    }

    // --- the build --------------------------------------------------------------------------

    static void build() throws IOException, InterruptedException {
        Path home = env("MIRACLE_HOME") != null ? Paths.get(env("MIRACLE_HOME"))
                : Paths.get(System.getProperty("user.home"), ".cache", "miracle");
        dicts = env("MIRACLE_DICTIONARIES") != null ? Paths.get(env("MIRACLE_DICTIONARIES")) : home.resolve("dictionaries");
        testDicts = OUT.resolve("test-dictionaries");

        deleteTree(OUT);
        mkdirs(OUT.resolve("classes"));
        mkdirs(OUT.resolve("mods"));
        mkdirs(OUT.resolve("test-mods"));

        // The icon goes into the loader's and the library's jars, and so does the gospel: a
        // dotfile here, the jar's ZIP comment (unzip -z) below.
        for (String j : List.of("miracle-loader", "miracle-toolchain")) {
            Path classes = OUT.resolve("classes").resolve(j);
            mkdirs(classes.resolve("META-INF/miracle"));
            Files.copy(Paths.get("docs/icon.png"), classes.resolve("icon.png"));
            Files.writeString(classes.resolve("META-INF/miracle/.gospel"), GOSPEL + "\n");
        }

        Path loaderJar = OUT.resolve("miracle-loader.jar");
        say("==> loader");
        // The one class any Java can run: on a Java older than 25 it finds a newer one and relaunches.
        compile(List.of("--release", "8", "-encoding", "UTF-8", "-Xlint:all,-options,-serial", "-Werror",
                "-d", OUT.resolve("classes/miracle-loader").toString(),
                "loader/boot/src/io/github/hronosin/miracle/Resurrection.java"));
        buildJar(Paths.get("loader"), loaderJar, null, null);
        preach(loaderJar);

        say("==> miracle-bake");
        Path bakeJar = OUT.resolve("miracle-bake.jar");
        buildJar(Paths.get("tools/bake"), bakeJar, null, null);
        Path bakeManifest = OUT.resolve("classes/bake-manifest.txt");
        Files.writeString(bakeManifest, "Main-Class: io.github.hronosin.miracle.bake.Bake\n");
        runJar("--update", "--file", bakeJar.toString(), "--manifest", bakeManifest.toString());

        say("==> miracle (MiracleToolChain command line)");
        Path cliClasses = OUT.resolve("classes/miracle");
        mkdirs(cliClasses);
        List<String> cliSources = new ArrayList<>(sources(Paths.get("tools/cli/src")));
        cliSources.addAll(sources(Paths.get("tools/bake/src")));
        javac(loaderJar.toString(), cliClasses, cliSources);
        copyTree(Paths.get("tools/cli/resources"), cliClasses);
        Path cliManifest = OUT.resolve("classes/cli-manifest.txt");
        Files.writeString(cliManifest, "Main-Class: io.github.hronosin.miracle.cli.Miracle\nClass-Path: miracle-loader.jar\n");
        Path cliJar = OUT.resolve("miracle.jar");
        runJar("--create", "--file", cliJar.toString(), "--manifest", cliManifest.toString(), "-C", cliClasses.toString(), ".");

        say("==> fake game");
        Path fakeGame = OUT.resolve("fake-minecraft.jar");
        buildJar(Paths.get("examples/fake-game"), fakeGame, null, null);

        say("==> fake obfuscated game (for OSHI tests)");
        Path fakeObf = OUT.resolve("fake-minecraft-obf.jar");
        buildJar(Paths.get("test-fixtures/fake-obf-game"), fakeObf, null, null);
        mkdirs(testDicts.resolve("fake-obf"));
        Files.copy(fakeObf, testDicts.resolve("fake-obf/client.jar"));
        Files.copy(Paths.get("test-fixtures/fake-obf-game/mappings.txt"), testDicts.resolve("fake-obf/mappings.txt"));

        String api = cp(loaderJar, fakeGame);

        say("==> example mods");
        buildJar(Paths.get("examples/hello-mod"), OUT.resolve("mods/hello-mod.jar"), api, null);
        buildJar(Paths.get("examples/chaos-mod"), OUT.resolve("mods/chaos-mod.jar"), api, null);
        // Real-game mod: needs only the loader on the class path, no Minecraft jar.
        buildJar(Paths.get("examples/title-mod"), OUT.resolve("title-mod.jar"), loaderJar.toString(), null);

        realGame(loaderJar, cliJar, bakeJar);

        say("==> test mods");
        Path testMods = OUT.resolve("test-mods");
        // dep-lib first: needs-lib compiles against it; heir-lib too: heir-mod extends its class.
        buildJar(Paths.get("tests/dep-lib"), testMods.resolve("dep-lib.jar"), api, loaderJar.toString());
        buildJar(Paths.get("tests/heir-lib"), testMods.resolve("heir-lib.jar"), api, loaderJar.toString());
        for (Path m : children(Paths.get("tests"))) {
            String name = m.getFileName().toString();
            if (name.equals("dep-lib") || name.equals("heir-lib")) {
                continue;
            }
            buildJar(m, testMods.resolve(name + ".jar"),
                    api + SEP + cp(testMods.resolve("dep-lib.jar"), testMods.resolve("heir-lib.jar")), loaderJar.toString());
        }

        say("Built. Try: " + (WINDOWS ? "run.cmd" : "./run.sh"));
    }

    /**
     * The library and the real-game examples, compiled against Minecraft itself: the client jar
     * AND the libraries that version uses (Minecraft's classes extend Brigadier, DataFixerUpper...).
     * Found from Prism Launcher, from MiracleToolChain's download cache (anything 'miracle pray'
     * ever ran), or from MC_JAR + MC_LIBS.
     */
    static void realGame(Path loaderJar, Path cliJar, Path bakeJar) throws IOException, InterruptedException {
        say("==> real-game mods (compiled against Minecraft itself)");
        Path prism = prismRoot();
        Path mcJar = env("MC_JAR") != null ? Paths.get(env("MC_JAR")) : null;
        if (mcJar == null && prism != null) {
            mcJar = newestPrismClient(prism);
        }
        List<Path> libs = null;
        if (mcJar != null && Files.isRegularFile(mcJar)) {
            if (env("MC_LIBS") != null) {
                try (Stream<Path> s = Files.walk(Paths.get(env("MC_LIBS")))) {
                    libs = s.filter(p -> p.toString().endsWith(".jar")).sorted().collect(Collectors.toList());
                }
            } else if (prism != null) {
                // Only the libraries this version declares, per Prism's metadata. Globbing the whole
                // libraries folder drags in every other instance's jars (old Forge and friends).
                String version = mcJar.getParent().getFileName().toString();
                libs = prismClasspath(prism, version);
                if (libs == null) {
                    say("    no Prism metadata for " + version + " (launch that instance once)");
                }
            }
        }
        Path home = env("MIRACLE_HOME") != null ? Paths.get(env("MIRACLE_HOME"))
                : Paths.get(System.getProperty("user.home"), ".cache", "miracle");
        if (libs == null && env("MC_JAR") == null) {
            Path cached = newestVersionDir(home.resolve("minecraft/versions"));
            if (cached != null && Files.isRegularFile(cached.resolve("client.jar"))) {
                // The toolchain knows exactly which libraries that version compiles against (all cached).
                List<String> out = capture(List.of(javaExe(), "-jar", cliJar.toString(), "classpath",
                        cached.getFileName().toString()), null);
                String last = out.isEmpty() ? "" : out.get(out.size() - 1);
                List<Path> all = new ArrayList<>();
                for (String p : last.split(Pattern.quote(SEP))) {
                    if (!p.isEmpty()) {
                        all.add(Paths.get(p));
                    }
                }
                if (!all.isEmpty()) {
                    mcJar = all.get(0);
                    libs = all.subList(1, all.size());
                }
            }
        }
        if (libs == null || mcJar == null) {
            say("    skipped: no Minecraft 26.x jar + libraries found (launch a 26.x instance in Prism once, run"
                    + " 'miracle pray' once, or set MC_JAR=... MC_LIBS=...)");
            return;
        }
        say("    against " + mcJar + " (" + libs.size() + " libraries)");
        String libsCp = loaderJar + SEP + join(libs);
        String mcCp = mcJar + (libs.isEmpty() ? "" : SEP + join(libs));

        say("==> MiracleToolChain library");
        Path toolchain = OUT.resolve("miracle-toolchain.jar");
        buildJar(Paths.get("toolchain"), toolchain, loaderJar + SEP + mcCp, libsCp);
        for (String m : REAL_MODS) {
            buildJar(Paths.get("examples", m), OUT.resolve(m + ".jar"), cp(loaderJar, toolchain) + SEP + mcCp,
                    libsCp + SEP + toolchain);
        }

        // OSHI: dictionaries for every supported version, fetched automatically (cached: only new
        // versions download anything).
        List<String> targets = new ArrayList<>(Arrays.asList(
                (env("MIRACLE_TARGETS") != null ? env("MIRACLE_TARGETS") : "1.21.11 26.*").trim().split("\\s+")));
        if (!"1".equals(env("MIRACLE_OFFLINE"))) {
            say("==> dictionaries (" + String.join(" ", targets) + ")");
            List<String> cmd = new ArrayList<>(List.of(javaExe(), "-jar", cliJar.toString(), "dictionary"));
            cmd.addAll(targets);
            List<String> out = new ArrayList<>();
            int code = capture(cmd, Map.of("MIRACLE_DICTIONARIES", dicts.toString()), out);
            if (!out.isEmpty()) {
                say(out.get(out.size() - 1));
            }
            if (code != 0) {
                say("    couldn't fetch dictionaries; baking with what's cached");
            }
        }
        // Check the mods against every cached dictionary, and bake a variant for each obfuscated one.
        String mcId = versionId(mcJar);
        List<String> bakeArgs = new ArrayList<>(List.of("--native", mcId + "=" + mcJar));
        for (Path d : children(dicts)) {
            if (!Files.isRegularFile(d.resolve("client.jar"))) {
                continue;
            }
            String v = d.getFileName().toString();
            if (Files.isRegularFile(d.resolve("mappings.txt"))) {
                bakeArgs.addAll(List.of("--obf", v + "=" + d.resolve("client.jar") + "," + d.resolve("mappings.txt")));
            } else if (!v.equals(mcId)) {
                bakeArgs.addAll(List.of("--native", v + "=" + d.resolve("client.jar")));
            }
        }
        if (bakeArgs.size() <= 2) {
            say("    not baked: no dictionaries in " + dicts + ". These mods run on unobfuscated versions (26.x) only.");
            say("    Connect to the internet once (or set MIRACLE_TARGETS) and build again.");
        } else {
            say("==> baking (OSHI)");
            List<String> cmd = new ArrayList<>(List.of(javaExe(), "-jar", bakeJar.toString(), "--lib", toolchain.toString()));
            cmd.addAll(bakeArgs);
            cmd.add(toolchain.toString());
            for (String m : REAL_MODS) {
                cmd.add(OUT.resolve(m + ".jar").toString());
            }
            cmd.add(OUT.resolve("title-mod.jar").toString());
            run(cmd);
        }
        preach(toolchain); // after baking: baking rewrites the jar
    }

    // --- jars -------------------------------------------------------------------------------

    /**
     * Compiles {@code src/src} into a jar, with {@code miracle.mod.toml}, {@code resources/},
     * OSHI fallbacks ({@code fallback/<version>/src}, against that version's readable API; fcp is
     * their class path, like cp but without the game jar) and hand-made variants
     * ({@code baked/<version>/src}, for the tests).
     */
    static void buildJar(Path src, Path jarFile, String cp, String fcp) throws IOException, InterruptedException {
        String name = jarFile.getFileName().toString().replaceFirst("\\.jar$", "");
        Path classes = OUT.resolve("classes").resolve(name);
        mkdirs(classes);
        javac(cp, classes, sources(src.resolve("src")));
        if (Files.isRegularFile(src.resolve("miracle.mod.toml"))) {
            Files.copy(src.resolve("miracle.mod.toml"), classes.resolve("miracle.mod.toml"), StandardCopyOption.REPLACE_EXISTING);
        }
        copyTree(src.resolve("resources"), classes);
        for (Path f : children(src.resolve("fallback"))) {
            String v = f.getFileName().toString();
            Path api = apiFor(v);
            if (api == null) {
                say("    " + src.getFileName() + ": fallbacks for " + v + " skipped, no dictionary (miracle dictionary " + v + ")");
                continue;
            }
            javac((fcp == null ? "" : fcp + SEP) + classes + SEP + api, classes.resolve("META-INF/miracle/fallback").resolve(v),
                    sources(f.resolve("src")));
        }
        List<Path> baked = children(src.resolve("baked"));
        if (!baked.isEmpty()) {
            List<String> versions = new ArrayList<>();
            for (Path b : baked) {
                String v = b.getFileName().toString();
                versions.add("\"" + v + "\"");
                javac(cp, classes.resolve("META-INF/miracle/baked").resolve(v), sources(b.resolve("src")));
            }
            Files.writeString(classes.resolve("META-INF/miracle/bake.toml"),
                    "baked = [" + String.join(",", versions) + "]\nchecked = []\n");
        }
        runJar("--create", "--file", jarFile.toString(), "-C", classes.toString(), ".");
    }

    /**
     * A jar with readable names for a version, to compile fallbacks against; null without a
     * dictionary. Obfuscated versions get an API jar made from their mappings, cached beside them.
     */
    static Path apiFor(String v) throws IOException, InterruptedException {
        for (Path d : List.of(dicts.resolve(v), testDicts.resolve(v))) {
            Path client = d.resolve("client.jar");
            if (!Files.isRegularFile(client)) {
                continue;
            }
            Path mappings = d.resolve("mappings.txt");
            if (!Files.isRegularFile(mappings)) {
                return client;
            }
            Path api = d.resolve("api.jar");
            if (!Files.isRegularFile(api) || newer(mappings, api) || newer(client, api)) {
                run(List.of(javaExe(), "-jar", OUT.resolve("miracle-bake.jar").toString(),
                        "--api", v + "=" + client + "," + mappings, api.toString()));
            }
            return api;
        }
        return null;
    }

    static void javac(String cp, Path out, List<String> files) {
        List<String> args = new ArrayList<>(List.of("--release", String.valueOf(NEEDED), "-encoding", "UTF-8",
                // Lint our code, not the environment's quirks: -path is a stale jar manifest
                // somewhere on the class path, -classfile annotations inside Minecraft's jar whose
                // classes aren't shipped (JetBrains @Contract).
                "-Xlint:all,-serial,-path,-classfile", "-Werror"));
        if (cp != null && !cp.isEmpty()) {
            args.add("-cp");
            args.add(cp);
        }
        args.add("-d");
        args.add(out.toString());
        args.addAll(files);
        compile(args);
    }

    static void compile(List<String> args) {
        if (javac.run(null, null, null, args.toArray(new String[0])) != 0) {
            throw new Failure("The compiler has spoken (see above).");
        }
    }

    static void runJar(String... args) {
        if (jar.run(System.out, System.err, args) != 0) {
            throw new Failure("jar " + String.join(" ", args) + " failed.");
        }
    }

    /** Puts the gospel into a jar's ZIP comment (unzip -z shows it): the jar is copied entry by entry. */
    static void preach(Path jarFile) throws IOException {
        Path tmp = jarFile.resolveSibling(jarFile.getFileName() + ".tmp");
        try (ZipFile in = new ZipFile(jarFile.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                out.putNextEntry(copy);
                try (InputStream is = in.getInputStream(e)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }
            out.setComment(GOSPEL);
        }
        Files.move(tmp, jarFile, StandardCopyOption.REPLACE_EXISTING);
    }

    // --- finding Minecraft ------------------------------------------------------------------

    /** Prism Launcher's data folder, if it has downloaded any Minecraft. */
    static Path prismRoot() {
        List<Path> candidates = new ArrayList<>();
        if (env("PRISM_DATA") != null) {
            candidates.add(Paths.get(env("PRISM_DATA")));
        }
        String user = System.getProperty("user.home");
        if (WINDOWS) {
            if (env("APPDATA") != null) {
                candidates.add(Paths.get(env("APPDATA"), "PrismLauncher"));
            }
        } else if (MAC) {
            candidates.add(Paths.get(user, "Library", "Application Support", "PrismLauncher"));
        } else {
            candidates.add(Paths.get(user, ".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"));
            String xdg = env("XDG_DATA_HOME");
            candidates.add(xdg != null ? Paths.get(xdg, "PrismLauncher") : Paths.get(user, ".local/share/PrismLauncher"));
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c.resolve("libraries/com/mojang/minecraft"))) {
                return c;
            }
        }
        return null;
    }

    static Path newestPrismClient(Path prism) throws IOException {
        Path best = null;
        String bestVersion = null;
        for (Path d : children(prism.resolve("libraries/com/mojang/minecraft"))) {
            String v = d.getFileName().toString();
            Path client = d.resolve("minecraft-" + v + "-client.jar");
            if (v.startsWith("26.") && Files.isRegularFile(client) && (bestVersion == null || compareVersions(v, bestVersion) > 0)) {
                best = client;
                bestVersion = v;
            }
        }
        return best;
    }

    static Path newestVersionDir(Path versions) throws IOException {
        Path best = null;
        for (Path d : children(versions)) {
            String v = d.getFileName().toString();
            if (v.startsWith("26.") && (best == null || compareVersions(v, best.getFileName().toString()) > 0)) {
                best = d;
            }
        }
        return best;
    }

    /**
     * The compile class path of one Minecraft version, as Prism has it on disk: its own metadata
     * (meta/net.minecraft/<version>.json, plus the LWJGL component it requires), each declared
     * library mapped to its file under libraries/. Null without the metadata.
     */
    @SuppressWarnings("unchecked")
    static List<Path> prismClasspath(Path prism, String version) throws IOException {
        Path metaFile = prism.resolve("meta/net.minecraft").resolve(version + ".json");
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        Map<String, Object> meta = (Map<String, Object>) Json.parse(Files.readString(metaFile));
        List<String> names = new ArrayList<>(libraryNames(meta));
        for (Object r : list(meta.get("requires"))) {
            Map<String, Object> req = (Map<String, Object>) r;
            Object uid = req.get("uid");
            Object ver = req.get("suggests") != null ? req.get("suggests") : req.get("equals");
            if (uid != null && ver != null) {
                Path dep = prism.resolve("meta").resolve(uid.toString()).resolve(ver + ".json");
                if (Files.isRegularFile(dep)) {
                    names.addAll(libraryNames((Map<String, Object>) Json.parse(Files.readString(dep))));
                }
            }
        }
        Set<Path> jars = new LinkedHashSet<>();
        for (String n : names) {
            Path rel = mavenPath(n);
            if (rel == null) {
                continue;
            }
            Path p = prism.resolve("libraries").resolve(rel);
            if (Files.isRegularFile(p) && !p.getFileName().toString().contains("natives")) {
                jars.add(p);
            }
        }
        return new ArrayList<>(jars);
    }

    @SuppressWarnings("unchecked")
    static List<String> libraryNames(Map<String, Object> meta) {
        List<String> out = new ArrayList<>();
        for (Object l : list(meta.get("libraries"))) {
            Object n = ((Map<String, Object>) l).get("name");
            if (n != null) {
                out.add(n.toString());
            }
        }
        return out;
    }

    /** group:artifact:version[:classifier][@ext] -> group/path/artifact/version/artifact-version[-classifier].ext */
    static Path mavenPath(String name) {
        String ext = "jar";
        int at = name.indexOf('@');
        if (at >= 0) {
            ext = name.substring(at + 1);
            name = name.substring(0, at);
        }
        String[] parts = name.split(":");
        if (parts.length < 3) {
            return null;
        }
        String file = parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + "." + ext;
        return Paths.get(parts[0].replace('.', '/'), parts[1], parts[2], file);
    }

    /** The version id in a client jar's version.json. */
    static String versionId(Path clientJar) throws IOException {
        try (ZipFile z = new ZipFile(clientJar.toFile())) {
            ZipEntry e = z.getEntry("version.json");
            if (e == null) {
                throw new Failure(clientJar + " has no version.json: is it a Minecraft client jar?");
            }
            try (InputStream in = z.getInputStream(e)) {
                Object id = ((Map<?, ?>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).get("id");
                return String.valueOf(id);
            }
        }
    }

    /** 26.1.2 &gt; 26.1 &gt; 1.21.11: numbers compared as numbers. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : -1;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : -1;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return a.compareTo(b);
    }

    // --- processes --------------------------------------------------------------------------

    static String javaExe() {
        return Paths.get(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
    }

    static void run(List<String> cmd) throws IOException, InterruptedException {
        int code = new ProcessBuilder(cmd).inheritIO().start().waitFor();
        if (code != 0) {
            throw new Failure(Paths.get(cmd.get(cmd.size() > 2 ? 2 : 0)).getFileName() + " failed (exit code " + code + ").");
        }
    }

    static List<String> capture(List<String> cmd, Map<String, String> env) throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        capture(cmd, env, out);
        return out;
    }

    /** Runs a command, collecting its output lines (stdout and stderr); returns the exit code. */
    static int capture(List<String> cmd, Map<String, String> env, List<String> out) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (env != null) {
            pb.environment().putAll(env);
        }
        Process p = pb.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(bytes);
        }
        int code = p.waitFor();
        for (String line : bytes.toString(StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return code;
    }

    // --- files ------------------------------------------------------------------------------

    static List<String> sources(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().collect(Collectors.toList());
        }
    }

    /** Subfolders, sorted; none if the folder doesn't exist. */
    static List<Path> children(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    out.add(p);
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    mkdirs(target);
                } else {
                    mkdirs(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void mkdirs(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    static boolean newer(Path a, Path b) throws IOException {
        return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b)) > 0;
    }

    static String cp(Path... paths) {
        return Arrays.stream(paths).map(Path::toString).collect(Collectors.joining(SEP));
    }

    static String join(List<Path> paths) {
        return paths.stream().map(Path::toString).collect(Collectors.joining(SEP));
    }

    static List<?> list(Object o) {
        return o instanceof List ? (List<?>) o : List.of();
    }

    static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? null : v;
    }

    static void say(String s) {
        System.out.println(s);
    }

    /** Just enough JSON for Prism's metadata and version.json. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            return j.value();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') {
                        return m;
                    }
                }
            }
            if (c == '[') {
                List<Object> a = new ArrayList<>();
                i++;
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return a;
                }
                while (true) {
                    ws();
                    a.add(value());
                    ws();
                    if (s.charAt(i++) == ']') {
                        return a;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String word = s.substring(start, i);
            return word.equals("null") ? null : word.equals("true") ? Boolean.TRUE : word.equals("false") ? Boolean.FALSE : word;
        }

        private void expect(char c) {
            if (s.charAt(i++) != c) {
                throw new IllegalArgumentException("JSON: expected " + c + " at " + (i - 1));
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                if (e == 'u') {
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                } else {
                    sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == 'b' ? '\b' : e == 'f' ? '\f' : e);
                }
            }
        }
    }
}
