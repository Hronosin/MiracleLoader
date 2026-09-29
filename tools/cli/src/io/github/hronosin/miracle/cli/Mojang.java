package io.github.hronosin.miracle.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Everything the toolchain fetches from Mojang, cached under {@code ~/.cache/miracle}
 * (or {@code $MIRACLE_HOME}):
 * <pre>
 *   minecraft/versions/&lt;v&gt;/&lt;v&gt;.json, client.jar, server/...
 *   minecraft/libraries/&lt;maven path&gt;
 *   minecraft/assets/indexes, objects
 *   dictionaries/&lt;v&gt;/client.jar, mappings.txt, api.jar   (for miracle-bake)
 * </pre>
 */
final class Mojang {

    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** One Minecraft version's metadata. */
    record Version(String id, Map<String, Object> json, Path dir) {
        boolean obfuscated() {
            return Json.get(json, "downloads", "client_mappings") != null;
        }

        int javaMajor() {
            Object v = Json.get(json, "javaVersion", "majorVersion");
            return v == null ? 8 : ((Number) v).intValue();
        }
    }

    record Server(Path jar, List<Path> libraries) {
    }

    private Mojang() {
    }

    static Path home() {
        String env = System.getenv("MIRACLE_HOME");
        return env != null ? Path.of(env) : Path.of(System.getProperty("user.home"), ".cache", "miracle");
    }

    static Path mc() {
        return home().resolve("minecraft");
    }

    static Path dictionaries() {
        String env = System.getenv("MIRACLE_DICTIONARIES");
        return env != null ? Path.of(env) : home().resolve("dictionaries");
    }

    // --- versions -------------------------------------------------------------------------------

    static Version version(String id) throws IOException {
        Path dir = mc().resolve("versions").resolve(id);
        Path json = dir.resolve(id + ".json");
        if (!Files.isRegularFile(json)) {
            Object manifest = Json.parse(manifest());
            String url = null;
            for (Object v : Json.arr(Json.get(manifest, "versions"))) {
                if (id.equals(Json.str(v, "id"))) {
                    url = Json.str(v, "url");
                }
            }
            if (url == null) {
                throw new Miracle.Heresy("Mojang has never heard of Minecraft '" + id + "'. Latest release: "
                        + Json.str(manifest, "latest", "release"));
            }
            Files.createDirectories(dir);
            Files.writeString(json, Http.text(url));
        }
        return new Version(id, Json.obj(Json.parse(Files.readString(json))), dir);
    }

    /** The version list, refreshed at most once an hour. */
    static String manifest() throws IOException {
        Path cached = mc().resolve("version_manifest_v2.json");
        if (Files.isRegularFile(cached)
                && Files.getLastModifiedTime(cached).toInstant().isAfter(Instant.now().minus(Duration.ofHours(1)))) {
            return Files.readString(cached);
        }
        try {
            String text = Http.text(MANIFEST);
            Files.createDirectories(cached.getParent());
            Files.writeString(cached, text);
            return text;
        } catch (IOException e) {
            if (Files.isRegularFile(cached)) {
                return Files.readString(cached); // offline: an old list beats no list
            }
            throw e;
        }
    }

    static String latestRelease() throws IOException {
        return Json.str(Json.parse(manifest()), "latest", "release");
    }

    // --- client -------------------------------------------------------------------------------

    static Path clientJar(Version v) throws IOException {
        Path jar = v.dir().resolve("client.jar");
        Http.fetch(new Http.Job(Json.str(v.json(), "downloads", "client", "url"), jar,
                Json.str(v.json(), "downloads", "client", "sha1")));
        return jar;
    }

    /** Every library this OS needs, natives included. */
    static List<Path> libraries(Version v) throws IOException {
        List<Http.Job> jobs = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        for (Object lib : Json.arr(v.json().get("libraries"))) {
            if (!rulesAllow(Json.get(lib, "rules"))) {
                continue;
            }
            Object artifact = Json.get(lib, "downloads", "artifact");
            if (artifact == null) {
                continue;
            }
            String name = Json.str(lib, "name");
            if (name != null && name.contains(":natives-") && !nativesMatchArch(name)) {
                continue;
            }
            Path dest = mc().resolve("libraries").resolve(Json.str(artifact, "path"));
            jobs.add(new Http.Job(Json.str(artifact, "url"), dest, Json.str(artifact, "sha1")));
            paths.add(dest);
        }
        Http.fetchAll("libraries for " + v.id(), jobs);
        return paths;
    }

    /** Libraries for compiling against: no natives. */
    static List<Path> compileLibraries(Version v) throws IOException {
        return libraries(v).stream().filter(p -> !p.getFileName().toString().contains("-natives-")).toList();
    }

    /** Downloads the asset index and objects; returns the index id. */
    static String assets(Version v) throws IOException {
        String id = Json.str(v.json(), "assetIndex", "id");
        Path index = mc().resolve("assets/indexes/" + id + ".json");
        Http.fetch(new Http.Job(Json.str(v.json(), "assetIndex", "url"), index,
                Json.str(v.json(), "assetIndex", "sha1")));
        List<Http.Job> jobs = new ArrayList<>();
        Map<String, Object> objects = Json.obj(Json.get(Json.parse(Files.readString(index)), "objects"));
        for (Object o : objects.values()) {
            String hash = Json.str(o, "hash");
            String sub = hash.substring(0, 2) + "/" + hash;
            jobs.add(new Http.Job("https://resources.download.minecraft.net/" + sub,
                    mc().resolve("assets/objects/" + sub), hash));
        }
        Http.fetchAll("assets (sounds, languages) for " + v.id(), jobs);
        return id;
    }

    // --- server -------------------------------------------------------------------------------

    /** The dedicated server, unpacked from Mojang's bundler: its jar and its libraries. */
    static Server server(Version v) throws IOException {
        Path bundle = v.dir().resolve("server-bundle.jar");
        String url = Json.str(v.json(), "downloads", "server", "url");
        if (url == null) {
            throw new IOException("Minecraft " + v.id() + " has no dedicated server download");
        }
        Http.fetch(new Http.Job(url, bundle, Json.str(v.json(), "downloads", "server", "sha1")));

        Path out = v.dir().resolve("server");
        Path serverJar = null;
        List<Path> libs = new ArrayList<>();
        try (JarFile jf = new JarFile(bundle.toFile())) {
            JarEntry versions = jf.getJarEntry("META-INF/versions.list");
            if (versions == null) {
                return new Server(bundle, List.of()); // old single-jar server
            }
            for (String listName : List.of("META-INF/versions.list", "META-INF/libraries.list")) {
                String list = new String(jf.getInputStream(jf.getJarEntry(listName)).readAllBytes(), StandardCharsets.UTF_8);
                String dir = listName.contains("versions") ? "META-INF/versions/" : "META-INF/libraries/";
                for (String line : list.split("\n")) {
                    String[] parts = line.strip().split("\t");
                    if (parts.length < 3) {
                        continue;
                    }
                    Path dest = out.resolve(dir.substring("META-INF/".length())).resolve(parts[2]);
                    if (!Files.isRegularFile(dest)) {
                        Files.createDirectories(dest.getParent());
                        try (InputStream in = jf.getInputStream(jf.getJarEntry(dir + parts[2]))) {
                            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    if (dir.contains("versions")) {
                        serverJar = dest;
                    } else {
                        libs.add(dest);
                    }
                }
            }
        }
        return new Server(serverJar, libs);
    }

    // --- dictionaries (for miracle-bake) ------------------------------------------------------

    /** client.jar, plus mappings.txt for obfuscated versions, where build.sh and miracle-bake expect them. */
    static Path dictionary(Version v) throws IOException {
        Path d = dictionaries().resolve(v.id());
        Files.createDirectories(d);
        Path client = clientJar(v);
        Path copy = d.resolve("client.jar");
        if (!Files.isRegularFile(copy) || Files.size(copy) != Files.size(client)) {
            Files.deleteIfExists(copy);
            try {
                Files.createLink(copy, client); // same bytes, no second copy on disk
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(client, copy, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (v.obfuscated()) {
            Http.fetch(new Http.Job(Json.str(v.json(), "downloads", "client_mappings", "url"),
                    d.resolve("mappings.txt"), Json.str(v.json(), "downloads", "client_mappings", "sha1")));
        }
        return d;
    }

    // --- rules ------------------------------------------------------------------------------------

    static String osName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
    }

    static boolean arm64() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64");
    }

    /** Mojang lists natives for every architecture under the same OS rule; keep ours only. */
    private static boolean nativesMatchArch(String name) {
        String classifier = name.substring(name.indexOf(":natives-") + ":natives-".length());
        boolean isArm = classifier.endsWith("-arm64") || classifier.endsWith("aarch_64");
        boolean isX86 = classifier.endsWith("-x86");
        return arm64() ? isArm : !isArm && !isX86;
    }

    /**
     * Mojang's rule lists: start disallowed, every matching rule sets allow/disallow. Rules that
     * need launcher "features" (demo mode, custom resolution...) never match: we don't use them.
     */
    static boolean rulesAllow(Object rules) {
        List<Object> list = Json.arr(rules);
        if (list.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Object r : list) {
            if (Json.get(r, "features") != null) {
                continue;
            }
            String os = Json.str(r, "os", "name");
            String arch = Json.str(r, "os", "arch");
            boolean matches = (os == null || os.equals(osName()))
                    && (arch == null || (arch.equals("x86") ? System.getProperty("os.arch").equals("x86") : true));
            if (matches) {
                allowed = "allow".equals(Json.str(r, "action"));
            }
        }
        return allowed;
    }
}
